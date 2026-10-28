package com.tux.tlauncher.linux

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.tux.tlauncher.LauncherData
import com.tux.tlauncher.hex
import com.tux.tlauncher.openHttp
import com.tux.tlauncher.upgrade.Provisioner
import com.tux.tlauncher.upgrade.StepJournal
import com.tux.tlauncher.upgrade.writeAtomically
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.concurrent.thread

private const val TAG = "bootstrap"
/**
 * Nix-on-Droid's bootstrap zip with tlauncher's patches, from scripts/build-userspace.sh. Rebuild and
 * upload it whenever patches/userspace or the userspace submodule change, and update both values.
 */
private const val BOOTSTRAP_URL = "https://github.com/tuxdotrs/tlauncher/releases/download/userspace-3264519/bootstrap-aarch64.zip"
private const val BOOTSTRAP_SHA256 = "fa342631c133fa88fe9c84339a843b326be8d79aedf32e5d337cb6495d50d205"
private const val BOOTSTRAP = "bootstrap-aarch64.zip"

/**
 * Where the patched Nix-on-Droid expects to be, baked into the zip. Only the phone's first user has
 * its app data there; a secondary user or a work profile has it under /data/user/<n>.
 */
private val INSTALL_PARENTS = setOf("/data/data/com.tux.tlauncher/files", "/data/user/0/com.tux.tlauncher/files")

/** Attempts per network step before setup (or an update) reports a failure. */
private const val NETWORK_TRIES = 3

/** How long `echo | cat` may take in the guest before setup calls the phone's kernel broken; it takes well under a second. */
private const val FORK_CHECK_SECONDS = 30L

/** Room for the download (~36 MB). */
private const val TARBALL_SPACE = 50_000_000L

/** Room for the unpacked system (~125 MB) and what first boot builds into /nix (~1.5 GB with an agent), with some to spare. */
private const val SYSTEM_SPACE = 2_000_000_000L

/** A failure that trying again can't fix, like a full disk: a network step stops at once rather than downloading again. */
private class NoRetry(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

private fun Throwable.outOfSpace() =
    generateSequence(this) { it.cause }.any {
        (it is ErrnoException && it.errno == OsConstants.ENOSPC) || it.message?.contains("ENOSPC") == true
    }

private fun gigabytes(bytes: Long) = "%.1f GB".format(bytes / 1e9)

/** The phases of a fresh install, in order, as the setup screen lists them. */
enum class Stage(
    val label: String,
) {
    Download("download nix-on-droid"),
    Extract("unpack the system"),
    Configure("check the system"),
    Packages("build the nix system"),
    Agents("install coding agents"),
}

/** Where the optional Linux system stands. The launcher works in every state; Linux features only when [Installed]. */
sealed interface LinuxState {
    data object Checking : LinuxState

    /** Never installed, or removed: nothing of it is on disk. */
    data object NotInstalled : LinuxState

    data class Installing(
        val stage: Stage,
        val step: String,
        val progress: Float?,
        val log: List<String>,
    ) : LinuxState

    /** An install, or an update that must finish before Linux runs, stopped; [Bootstrap.run] retries it. */
    data class Failed(
        val stage: Stage,
        val message: String,
        val log: List<String>,
    ) : LinuxState

    data object Installed : LinuxState

    data object Removing : LinuxState
}

/**
 * One idempotent change to the rootfs. Once it succeeds its [id] is recorded
 * and it doesn't run again; to bring a changed step to existing installs,
 * give it a new id. [network] steps of an update run in the background, so
 * an offline phone still reaches the home screen. [isDone], when set, reads
 * the step's state from disk instead of the journal.
 */
private class Step(
    val id: String,
    val stage: Stage,
    val label: String,
    val network: Boolean,
    /** A failure doesn't stop a fresh install: the system works without it, and it's retried later. */
    val optional: Boolean = false,
    val isDone: (() -> Boolean)? = null,
    val run: suspend () -> Unit,
)

/**
 * Sets up the Nix-on-Droid system and keeps it current. A fresh install
 * runs every [steps] entry behind the setup screen; after an app update only
 * the new ones run, see [Step].
 */
class Bootstrap(
    private val context: Context,
    private val proot: Proot,
    private val agents: AgentStore,
    private val nix: NixConfig,
    /** The guest's files; see [Provisioner]. */
    private val provisioner: Provisioner,
) {
    private val _state = MutableStateFlow<LinuxState>(LinuxState.Checking)
    val state = _state.asStateFlow()

    val isInstalled get() = _state.value == LinuxState.Installed

    /** An install was started (and may still need to finish), or one finished. */
    val wanted get() = journal.setupComplete || choiceFile.exists()

    /** Update steps running after the home screen is up; Idle when there are none. */
    private val _updates = MutableStateFlow<JobState>(JobState.Idle)
    val updates = _updates.asStateFlow()

    private val log = OutputTail(200)
    private val journal = StepJournal(File(proot.filesDir, "rootfs-steps.json"))

    /** The agents picked when installing Linux, one bin per line; its existence means an install was started. */
    private val choiceFile get() = File(proot.filesDir, "setup-agents")

    /** The coding agents setup installs, in the order they were listed. */
    val chosenAgents: List<Agent>
        get() = runCatching { choiceFile.readLines() }.getOrDefault(emptyList()).mapNotNull { bin -> AGENTS.find { it.bin == bin } }

    fun choose(picked: List<Agent>) {
        proot.filesDir.mkdirs()
        choiceFile.writeText(picked.joinToString("") { it.bin + "\n" })
    }

    private var stage = Stage.Download
    private var step = ""

    private val steps =
        listOf(
            Step("bootstrap", Stage.Download, "Downloading Nix-on-Droid", network = true) { installSystem() },
            // Only before the first setup: a phone where Linux already runs can fork.
            Step("forks-1", Stage.Configure, "Checking that programs can start", network = false, isDone = { journal.setupComplete }) {
                checkForks()
            },
            Step("first-boot-1", Stage.Packages, "Building the first Nix-on-Droid generation", network = true) { firstBoot() },
            Step("keymap-1", Stage.Packages, "Compiling keymap", network = false, isDone = { proot.keymap.exists() }) { compileKeymap() },
            // Omarchy's themes were a step here ("omarchy-<ref>") until Linux became optional; ThemeStore fetches them now.
        )

    private fun Step.done() = isDone?.invoke() ?: (id in journal)

    /**
     * Runs the step; one that needs the network gets a few tries, since phone connections drop.
     * Retrying is cheap: Nix keeps what it already fetched in the store, and downloads resume.
     */
    private suspend fun Step.attempt() {
        repeat(if (network) NETWORK_TRIES - 1 else 0) { n ->
            try {
                return run()
            } catch (e: Exception) {
                if (e is NoRetry || e is CancellationException) throw e
                log.add("${e.message}; trying again (${n + 2} of $NETWORK_TRIES)")
                kotlinx.coroutines.delay(5_000L * (n + 1))
            }
        }
        run()
    }

    /**
     * Brings Linux to [LinuxState.Installed] if it's wanted: updates an
     * installed system, or continues an install that was started (also
     * after the app was killed during it). Otherwise reports NotInstalled.
     */
    suspend fun run() =
        withContext(Dispatchers.IO) {
            try {
                when {
                    journal.setupComplete -> update()
                    !choiceFile.exists() -> {
                        _state.value = LinuxState.NotInstalled
                        // Left by a removal that didn't finish (the app was killed, or a file resisted), or omalauncher's Arch.
                        for (dir in listOf(proot.rootfs, proot.home, legacyArch)) {
                            if (dir.exists()) runCatching { LauncherData.deleteTree(dir) }.onFailure { Log.e(TAG, "leftover $dir", it) }
                        }
                        return@withContext
                    }
                    else -> setup()
                }
                _state.value = LinuxState.Installed
            } catch (e: Throwable) {
                // A cancelled install is about to be removed; it didn't fail.
                if (cancelled) {
                    Log.i(TAG, "setup cancelled")
                    return@withContext
                }
                // Errors too (out of memory): otherwise the screen shows the step running forever.
                Log.e(TAG, "setup failed", e)
                _state.value = LinuxState.Failed(stage, e.message ?: e.toString(), log.toList())
            }
        }

    @Volatile private var cancelled = false

    @Volatile private var connection: HttpURLConnection? = null

    /** Whether the install can still be cancelled: until setup completes. An update of an installed system can't. */
    val cancellable get() = !journal.setupComplete && !cancelled

    /**
     * Stops an install that hasn't completed: the download and the unpack stop at their next block. The caller kills
     * guest commands, waits for [run] to return, then calls [remove]. False when there's nothing to cancel.
     */
    fun cancel(): Boolean {
        if (!cancellable) return false
        cancelled = true
        connection?.disconnect()
        return true
    }

    private fun checkCancelled() {
        if (cancelled) throw CancellationException("cancelled")
    }

    /** Starts installing Linux with [picked] coding agents; see [run]. */
    suspend fun install(picked: List<Agent>) {
        choose(picked)
        run()
    }

    /** Where omalauncher kept its Arch Linux rootfs; an install from then is deleted, not migrated. */
    private val legacyArch get() = File(proot.filesDir, "arch")

    /**
     * Deletes the system, the guest home and what we recorded about them. Callers stop guest
     * processes first. Launcher data (themes, widgets) lives outside them and stays.
     */
    suspend fun remove() =
        withContext(Dispatchers.IO) {
            _state.value = LinuxState.Removing
            backlog = emptyList()
            nix.lock.withLock {
                for (dir in listOf(proot.rootfs, proot.home, staging)) {
                    try {
                        LauncherData.deleteTree(dir)
                    } catch (e: Exception) {
                        // Whatever is left can't run; a later install unpacks over a fresh directory anyway.
                        Log.e(TAG, "$dir not fully deleted", e)
                    }
                }
                journal.reset()
                provisioner.forgetAll()
                choiceFile.delete()
                proot.keymap.delete()
                context.cacheDir.listFiles { f -> f.name.startsWith(BOOTSTRAP) }?.forEach { it.delete() }
                log.clear()
                cancelled = false
            }
            _state.value = LinuxState.NotInstalled
        }

    /**
     * A fresh install: every step, in order. A failure stops here and a
     * retry picks up at the failed step. Agents are extras: the system
     * works without them, and settings can retry.
     */
    private suspend fun setup() {
        for (step in steps) {
            if (step.done()) continue
            checkCancelled()
            report(step.stage, step.label)
            try {
                step.attempt()
                journal.mark(step.id)
            } catch (e: Exception) {
                if (!step.optional || e is CancellationException) throw e
                log.add("${step.label} failed: ${e.message}; retry in settings")
            }
        }
        installAgents()
        checkCancelled()
        journal.completeSetup()
    }

    /**
     * After an app update: offline steps run before the home screen, so it
     * never shows a half-configured system; from the first step that needs
     * the network on, the rest continue in the background (see [updates]).
     */
    private suspend fun update() {
        val pending = steps.filterNot { it.done() }
        val blocking = pending.takeWhile { !it.network }
        for (step in blocking) {
            report(step.stage, step.label)
            step.run()
            journal.mark(step.id)
        }
        backlog = pending.drop(blocking.size)
    }

    private var backlog: List<Step> = emptyList()

    private val backlogRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Runs update steps that need the network; call once the home screen is up. Safe to call again to retry. */
    suspend fun runBacklog() {
        if (!backlogRunning.compareAndSet(false, true)) return
        try {
            runSteps()
        } finally {
            backlogRunning.set(false)
        }
    }

    private suspend fun runSteps() =
        withContext(Dispatchers.IO) {
            val todo = backlog.filterNot { it.done() }
            if (todo.isEmpty()) return@withContext
            for (step in todo) {
                _updates.value = JobState.Working(step.label, emptyList())
                try {
                    // Package steps switch the Nix config; nothing else may switch meanwhile.
                    nix.lock.withLock { step.attempt() }
                    journal.mark(step.id)
                } catch (e: Throwable) {
                    Log.w(TAG, "update step ${step.id} failed", e)
                    _updates.value = JobState.Failed(log.toList().takeLast(12) + (e.message ?: e.toString()))
                    return@withContext
                }
            }
            _updates.value = JobState.Idle
        }

    /**
     * Installs the agents picked on the welcome screen, in one switch. A failed switch doesn't
     * fail setup: they can be installed again from settings.
     */
    private suspend fun installAgents() {
        val chosen = chosenAgents
        val missing = chosen.filterNot { agents.isInstalled(it) }
        if (missing.isNotEmpty()) {
            checkCancelled()
            report(Stage.Agents, "Installing ${missing.joinToString { it.name }}")
            val before = nix.packages()
            nix.writePackages((before + missing.map { it.attr }).distinct())
            try {
                guest(NIX_SWITCH)
            } catch (e: IllegalStateException) {
                nix.writePackages(before)
                log.add("${missing.joinToString { it.name }} didn't install; try again in settings › coding agents")
            }
            agents.onInstalled()
        }
        chosen.firstOrNull { agents.isInstalled(it) }?.let(agents::choose)
    }

    private fun report(
        stage: Stage,
        step: String,
        progress: Float? = null,
    ) {
        this.stage = stage
        this.step = step
        if (journal.setupComplete && _state.value == LinuxState.Installed) return
        _state.value = LinuxState.Installing(stage, step, progress, log.toList())
    }

    /** Where the zip is unpacked; renamed to the system once complete, so a half-unpacked one never runs. */
    private val staging get() = File(proot.filesDir, "usr.staging")

    /** Always a fresh unpack: this step only runs until one completed, so a system here is a half-set-up one. */
    private fun installSystem() {
        // Unpacking replaces the system: the user's packages and agents. Only ever before a setup finished.
        check(!journal.setupComplete) { "refusing to replace a set-up system" }
        val parent = proot.filesDir.canonicalFile.path
        if (parent !in INSTALL_PARENTS && proot.filesDir.path !in INSTALL_PARENTS) {
            throw NoRetry("Linux can only be installed for the phone's main user: Nix-on-Droid's paths are fixed (this app's files are in $parent)")
        }
        // A half-set-up system from an earlier try only takes up room the download needs.
        for (dir in listOf(proot.rootfs, staging, legacyArch)) LauncherData.deleteTree(dir)
        val zip =
            try {
                obtainTarball()
            } catch (e: Exception) {
                throw if (e.outOfSpace()) outOfSpace(e) else e
            }
        try {
            verify(zip)
            extract(zip)
        } catch (e: Exception) {
            if (e is NoRetry || e is CancellationException || e.outOfSpace()) {
                // The download is fine and kept for the next try; what was unpacked gives its room back.
                runCatching { LauncherData.deleteTree(staging) }
                throw if (e is NoRetry || e is CancellationException) e else outOfSpace(e)
            }
            // A truncated or bogus download (a captive portal's page) would fail every retry otherwise.
            if (zip.parentFile == context.cacheDir) zip.delete()
            throw e
        }
        if (zip.parentFile == context.cacheDir) zip.delete()
    }

    /** The zip is the one this build expects; one pushed with adb for development may be any build. */
    private fun verify(zip: File) {
        if (zip.parentFile != context.cacheDir) return
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(zip.inputStream(), digest).use { input ->
            val buf = ByteArray(1 shl 16)
            while (input.read(buf) >= 0) checkCancelled()
        }
        check(digest.digest().hex() == BOOTSTRAP_SHA256) { "the download isn't the expected Nix-on-Droid build; retry to download it again" }
    }

    /** Stops before writing when the app's storage has less than [bytes] free: a full phone fails at once, not after the download. */
    private fun requireSpace(bytes: Long) {
        val free = proot.filesDir.usableSpace
        if (free < bytes) {
            throw NoRetry("not enough space: Linux needs ${gigabytes(bytes)} free, the phone has ${gigabytes(free)}; free some up, then try again")
        }
    }

    private fun outOfSpace(e: Exception) =
        NoRetry("the phone ran out of space; Linux needs about ${gigabytes(TARBALL_SPACE + SYSTEM_SPACE)} free; free some up, then try again", e)

    /**
     * Prefers a zip pushed via adb to the app's external files dir; downloads otherwise.
     * An interrupted download resumes where it stopped, as long as the file on the server is
     * still the same one (If-Range): two versions mustn't be spliced.
     */
    private fun obtainTarball(): File {
        val external = context.getExternalFilesDir(null)?.let { File(it, BOOTSTRAP) }
        if (external != null && external.exists()) return external
        val target = File(context.cacheDir, BOOTSTRAP)
        if (target.exists()) return target
        val partial = File(context.cacheDir, "$BOOTSTRAP.part")
        val validator = File(context.cacheDir, "$BOOTSTRAP.version")
        requireSpace(TARBALL_SPACE - (if (partial.exists()) partial.length() else 0L) + SYSTEM_SPACE)
        report(Stage.Download, "Downloading Nix-on-Droid", 0f)
        val conn = openHttp(BOOTSTRAP_URL)
        connection = conn
        try {
            checkCancelled()
            download(conn, partial, validator)
        } finally {
            connection = null
            conn.disconnect()
        }
        checkCancelled()
        // Only a complete download is kept for a retry.
        check(partial.renameTo(target)) { "can't move the download into place" }
        validator.delete()
        return target
    }

    /** Into [partial], continuing it if the server still has the version [validator] names. Throws if it ends short. */
    private fun download(
        conn: HttpURLConnection,
        partial: File,
        validator: File,
    ) {
        val have = if (partial.exists() && validator.exists()) partial.length() else 0L
        if (have > 0) {
            conn.setRequestProperty("Range", "bytes=$have-")
            conn.setRequestProperty("If-Range", validator.readText())
        }
        val resumed = conn.responseCode == HttpURLConnection.HTTP_PARTIAL
        if (conn.responseCode !in 200..299) error("download failed: HTTP ${conn.responseCode}")
        val start = if (resumed) have else 0L
        val total = conn.contentLengthLong.let { if (it > 0) it + start else -1L }
        (conn.getHeaderField("ETag") ?: conn.getHeaderField("Last-Modified"))?.let(validator::writeText) ?: validator.delete()
        conn.inputStream.use { input ->
            java.io.FileOutputStream(partial, resumed).use { out ->
                val buf = ByteArray(1 shl 16)
                var done = start
                var lastReport = 0L
                while (true) {
                    checkCancelled()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - lastReport > 4 shl 20) {
                        lastReport = done
                        report(
                            Stage.Download,
                            if (total > 0) "${done shr 20} of ${total shr 20} MB" else "${done shr 20} MB",
                            if (total > 0) done.toFloat() / total else null,
                        )
                    }
                }
            }
        }
        check(total <= 0 || partial.length() == total) { "the download was cut short; retry to continue it" }
    }

    /**
     * Unpacks Nix-on-Droid's bootstrap zip the way its app does: zip has no symlinks or modes, so
     * SYMLINKS.txt lists the links (`target←path`) and EXECUTABLES.txt the files to make executable.
     */
    private fun extract(zip: File) {
        report(Stage.Extract, "Starting", 0f)
        val root = staging
        LauncherData.deleteTree(root)
        requireSpace(SYSTEM_SPACE)
        root.mkdirs()
        // Nothing we recorded about an old system holds for this one.
        journal.reset()
        provisioner.forgetAll()
        ZipFile(zip).use { archive ->
            val entries = archive.entries().toList()
            for ((count, entry) in entries.withIndex()) {
                checkCancelled()
                val file = File(root, entry.name)
                // A zip naming a path outside the root (../) would write over the launcher's own files.
                check(file.canonicalPath.startsWith(root.canonicalPath + "/") || file.canonicalFile == root.canonicalFile) { "bad path in the zip: ${entry.name}" }
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    archive.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it, 1 shl 16) } }
                }
                if (count % 200 == 0) report(Stage.Extract, "$count of ${entries.size} files", count.toFloat() / entries.size)
            }
        }
        val symlinks = File(root, "SYMLINKS.txt")
        for (line in symlinks.readLines()) {
            if (line.isBlank()) continue
            val (target, path) = line.split('←', limit = 2)
            val link = File(root, path)
            link.parentFile?.mkdirs()
            link.delete()
            Os.symlink(target, link.path)
        }
        val executables = File(root, "EXECUTABLES.txt")
        for (path in executables.readLines()) if (path.isNotBlank()) Os.chmod(File(root, path).path, 0x1c0) // 0700
        symlinks.delete()
        executables.delete()
        check(root.renameTo(proot.rootfs)) { "can't move the system into place" }
        proot.home.mkdirs()
    }

    /**
     * Nix-on-Droid's first boot, which its app runs on the first login: login-inner, with flakes rather
     * than its prompt (USE_FLAKE). It makes ~/.config/nix-on-droid from tlauncher's template, builds it
     * and switches to it. A try that stopped part way left that config; `nix flake new` won't write over it.
     */
    /**
     * Until this switch the system is only Nix and the bash it came with: no coreutils, so the config a failed try
     * left behind is deleted from here. login-inner ends in an interactive bash, which reads stdin, hence /dev/null.
     */
    private fun firstBoot() {
        // A link to /etc/static, which only resolves inside the guest.
        if (Files.exists(proot.hostFile("/etc/UNINTIALISED").toPath(), LinkOption.NOFOLLOW_LINKS)) {
            proot.hostFile("${Proot.GUEST_HOME}/.config/nix-on-droid").deleteRecursively()
        }
        guest("USE_FLAKE=1 /bin/sh /usr/lib/login-inner true </dev/null")
    }

    private fun compileKeymap() {
        guest("xkbcli compile-keymap --layout us > /tmp/keymap.xkb")
        // Clients mmap the keymap and parse it as a C string.
        writeAtomically(proot.keymap, proot.hostFile("/tmp/keymap.xkb").readBytes() + 0)
    }

    /**
     * Runs a pipeline into a second shell (before first boot there's nothing else to run), the smallest guest command that forks. Some custom ROM kernels (4.14, with Android's cgroup v2
     * freezer backported but not its follow-up, upstream 05b289263772) make every fork of a traced process restart
     * forever (#105), and the next step, first boot, would hang without a word. Such a phone gets an error naming the cause.
     */
    private fun checkForks() {
        val process = proot.process("echo forks | /bin/sh -c 'read -r l; echo \"\$l\"'").start()
        val output = StringBuffer()
        val reader = thread(isDaemon = true) { runCatching { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } } }
        if (process.waitFor(FORK_CHECK_SECONDS, TimeUnit.SECONDS)) {
            reader.join(1_000)
            output.lines().forEach(log::add)
            check(process.exitValue() == 0 && "forks" in output) { "Linux can't run commands here (exit ${process.exitValue()})" }
            return
        }
        process.destroyForcibly()
        // The guest shell is proot's tracee and outlives a killed proot.
        proot.killAll()
        log.add("a guest command that starts another one didn't finish in $FORK_CHECK_SECONDS s (kernel ${System.getProperty("os.version")})")
        error(
            "this phone's kernel stops Linux programs from starting other programs, so setup can't go on. " +
                "custom ROMs with a 4.14 kernel need kernel commit 05b289263772 from their maintainer; see omalauncher's github issue #105",
        )
    }

    private fun guest(script: String) {
        log.add("$ $script")
        val code = proot.run(script) { line -> if (log.add(line)) report(stage, step) }
        check(code == 0) { "`${script.take(60)}` failed (exit $code)" }
    }
}
