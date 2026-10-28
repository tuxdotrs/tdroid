package com.tux.tdroid.linux

import android.content.Context
import com.tux.tdroid.LauncherData
import com.tux.tdroid.upgrade.writeAtomically
import java.io.File

/**
 * Paths and process launching for the Nix-on-Droid system. Like Nix-on-Droid's own login script, proot keeps
 * Android's root and binds the system over it: [rootfs]'s nix, bin, etc and usr at /nix, /bin, /etc and /usr.
 * The guest runs as the app's own user, in [home], a real path that's the same inside and out.
 */
class Proot(
    private val context: Context,
) {
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    val filesDir: File = context.filesDir
    /** Nix-on-Droid's installation dir, its `build.installationDir` (patches/userspace). */
    val rootfs = File(filesDir, "usr")
    val tmpDir = File(filesDir, "tmp")

    /** Bound to XDG_RUNTIME_DIR inside the guest; holds the Wayland socket and the bridges' FIFOs. */
    val runtimeDir = File(filesDir, "xdg")
    val waylandSocket = File(runtimeDir, "wayland-0")
    val keymap = File(filesDir, "keymap.xkb")

    /** The guest user's home, [GUEST_HOME], and the launcher's places in it. */
    val home = File(filesDir, "home")
    val applications = File(home, ".local/share/applications")
    val launcherConfig = LauncherData.configDir(context)

    /**
     * A listable stand-in for /sys: apps may only read /sys/devices/system/cpu,
     * and tools like btop abort when they can't walk the directories above it.
     */
    private val sysDir = File(filesDir, "sys")

    /** Stand-ins for /proc files Android hides from apps. */
    val procShim = ProcShim(File(filesDir, "proc"))

    /** How guest apps render OpenGL and Vulkan: in software, or on the GPU once [Gpu] turns it on. */
    @Volatile var graphicsEnv: List<String> = emptyList()

    /** Replaces /usr/bin/bwrap in the guest, see assets/bwrap-shim.sh. */
    private val bwrapShim = File(filesDir, "bwrap-shim")

    /** Launcher data the guest sees in its home, kept outside the rootfs; see [LauncherData]. */
    private val dataBinds =
        listOf(
            "$GUEST_HOME/.local/share/omarchy" to LauncherData.omarchyDir(context),
            "$GUEST_HOME/.config/omarchy/themes" to LauncherData.userThemesDir(context),
            "$GUEST_HOME/.config/omarchy/backgrounds" to LauncherData.userBackgroundsDir(context),
        )

    /** Host directories bound over guest paths by [command]. */
    private val binds = listOf("/tmp" to tmpDir, "/dev/shm" to tmpDir, GUEST_RUNTIME_DIR to runtimeDir) + dataBinds

    /**
     * Where an absolute guest path lives on the Android side, following the
     * directory binds in [command]: /tmp in the guest isn't rootfs/tmp.
     */
    fun hostFile(guestPath: String): File = hostFile(guestPath, rootfs, binds)

    /** Where the guest can see that's ours: the system, the home and the binds, as real paths. */
    private val visible by lazy { (listOf(rootfs, home) + binds.map { it.second }).map { it.canonicalFile } }

    /** Whether [real], a canonical host file, is one the guest can see, rather than the launcher's own. */
    fun guestVisible(real: File) = visible.any { real.startsWith(it) }

    /** The guest's files as it sees them; see [GuestFs]. */
    val guestFs by lazy { GuestFs(::hostFile, ::guestVisible) }

    /**
     * Where a guest path that Linux or a widget names is on the Android side, as the guest sees it:
     * `..` stops at /, links are followed inside the guest. Null for a link in a loop or out of the
     * guest, so no path reaches the launcher's own files ([hostFile] is for paths of our own).
     */
    fun guestFile(guestPath: String): File? = runCatching { guestFs.host(guestPath) }.getOrNull()

    /** The guest path of a host file, the reverse of [hostFile]; null if the guest can't see it. */
    fun guestPath(host: File): String? {
        for ((guest, dir) in binds) {
            if (host.startsWith(dir)) return (guest + "/" + host.relativeTo(dir).path).trimEnd('/')
        }
        for (dir in SYSTEM_DIRS) {
            val bound = File(rootfs, dir)
            if (host.startsWith(bound)) return ("/$dir/" + host.relativeTo(bound).path).trimEnd('/')
        }
        return if (host.startsWith(home)) (GUEST_HOME + "/" + host.relativeTo(home).path).trimEnd('/') else null
    }

    /**
     * Resets what only lives as long as our process (guest processes die with
     * it): /tmp and the runtime dir. Writes the host-side
     * stand-ins every guest command needs. Call once, before any guest runs.
     */
    fun prepare() {
        for (dir in listOf(tmpDir, runtimeDir)) {
            LauncherData.deleteTree(dir)
            dir.mkdirs()
        }
        // proot skips a bind whose host directory is missing, and the guest would then write into the home.
        // The empty mount points make the bound directories show up in listings of their parents.
        for ((guest, host) in dataBinds) {
            host.mkdirs()
            hostFile(guest, rootfs, emptyList()).mkdirs()
        }
        // The launcher's shims (OpenBridge, owl-check) go here: first on PATH, outside the Nix store.
        if (rootfs.isDirectory) File(rootfs, "usr/local/bin").mkdirs()
        for (sub in listOf("devices/system/cpu", "class", "block", "bus", "dev", "fs", "kernel")) File(sysDir, sub).mkdirs()
        val shim = context.assets.open("bwrap-shim.sh").use { it.readBytes() }
        if (!bwrapShim.isFile || !bwrapShim.readBytes().contentEquals(shim)) writeAtomically(bwrapShim, shim, executable = true)
    }

    private fun command(script: String): List<String> =
        listOf(
            File(nativeDir, "libproot.so").path,
            "--kill-on-exit",
            "--link2symlink",
            "--sysvipc",
        ) +
            // Nix-on-Droid's binds (its login script): /bin and /etc are symlinks on Android, and `!` binds over the link.
            listOf(
                "-b", "${File(rootfs, "nix").path}:/nix",
                "-b", "${File(rootfs, "bin").path}:/bin!",
                "-b", "${File(rootfs, "etc").path}:/etc!",
                "-b", "${File(rootfs, "usr").path}:/usr",
                "-b", "/:/android",
            ) + procShim.binds.flatMap { (guest, host) -> listOf("-b", "${host.path}:$guest") } +
            listOf(
                "-b", "${sysDir.path}:/sys",
                "-b", "/sys/devices/system/cpu",
            ) +
            binds.flatMap { (guest, host) -> listOf("-b", "${host.path}:$guest") } +
            listOf(
                "-b", "${bwrapShim.path}:/usr/bin/bwrap",
                "-w", GUEST_HOME,
                "/bin/sh", "-c", "$SESSION_INIT\n$script",
            )

    /**
     * The guest's environment. proot passes its own on to the guest, so this is all of it, besides
     * proot's variables, which the guest unsets ([SESSION_INIT]). Nix-on-Droid has no /usr/bin/env
     * until its first switch, so `env -i` can't clear it in the guest.
     */
    private fun guestEnv(): List<String> =
        listOf(
            "HOME=$GUEST_HOME",
            "USER=$GUEST_USER",
            "LANG=C.UTF-8",
            // Android's zone name (e.g. Europe/Berlin); the guest otherwise runs on UTC.
            "TZ=${java.util.TimeZone.getDefault().id}",
            "TERM=xterm-256color",
            // The launcher's shims first (/usr/bin has only env and the bwrap shim), then the Nix profile; the
            // session init adds Nix-on-Droid's own.
            "PATH=/usr/local/bin:/usr/bin:$GUEST_HOME/.nix-profile/bin:/nix/var/nix/profiles/default/bin:/bin",
            // Coding agents come from Nix and update with the system; updating themselves would fail on
            // the read-only store anyway.
            "DISABLE_AUTOUPDATER=1",
            "OPENCODE_DISABLE_AUTOUPDATE=1",
            "BROWSER=xdg-open",
            // The launcher's editor; yazi and git open files with it. Its shim falls back to micro.
            "EDITOR=$EDIT_COMMAND",
            "VISUAL=$EDIT_COMMAND",
            "XDG_RUNTIME_DIR=$GUEST_RUNTIME_DIR",
            "WAYLAND_DISPLAY=${waylandSocket.name}",
            "XDG_SESSION_TYPE=wayland",
            "GDK_BACKEND=wayland",
            "QT_QPA_PLATFORM=wayland",
            "MOZ_ENABLE_WAYLAND=1",
            "SDL_VIDEODRIVER=wayland",
            ) + graphicsEnv

    /**
     * Kills every proot we started and every guest process: before the rootfs
     * is deleted. A SIGKILLed proot doesn't take its tracees along
     * (--kill-on-exit needs a clean exit); they run on untraced and spin, so
     * they go too, found by their executable, proot's loader. Our own
     * processes are the only ones whose /proc entries we can read.
     */
    fun killAll() {
        val proot = File(nativeDir, "libproot.so").path
        val loader = File(nativeDir, "libproot-loader.so").path
        for (dir in File("/proc").listFiles().orEmpty()) {
            val pid = dir.name.toIntOrNull() ?: continue
            val argv0 = runCatching { File(dir, "cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray().decodeToString() }.getOrNull()
            val exe = runCatching { android.system.Os.readlink(File(dir, "exe").path) }.getOrNull()
            if (argv0 == proot || exe == loader) runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL) }
        }
    }

    /** A guest `sh -c [script]`, stderr merged into stdout. Needs [prepare] to have run. */
    fun process(script: String): ProcessBuilder =
        ProcessBuilder(command(script)).apply {
            environment().apply {
                clear()
                for (entry in guestEnv()) put(entry.substringBefore('='), entry.substringAfter('='))
                put("PROOT_LOADER", File(nativeDir, "libproot-loader.so").path)
                put("PROOT_TMP_DIR", tmpDir.path)
                // Where --link2symlink keeps the files hard links point to; Nix-on-Droid's login uses the same.
                put("PROOT_L2S_DIR", File(rootfs, ".l2s").path)
                put("LD_LIBRARY_PATH", nativeDir.path)
            }
            redirectErrorStream(true)
        }

    companion object {
        /** Nix-on-Droid's `user.home` (patches/userspace); the guest sees the real directory, [home]. */
        const val GUEST_HOME = "/data/data/com.tux.tdroid/files/home"

        /** Nix-on-Droid's `user.userName`. */
        const val GUEST_USER = "nix-on-droid"

        /** XDG_RUNTIME_DIR in the guest, bound to [runtimeDir]. */
        const val GUEST_RUNTIME_DIR = "/run/tdroid"

        /**
         * Before every guest command: drops proot's variables (LD_LIBRARY_PATH names Android's libraries), then
         * sources what login-inner does, Nix-on-Droid's environment (sessionVariables, nix.sh). There's none
         * before first boot made the profile.
         */
        private const val SESSION_INIT =
            "unset LD_LIBRARY_PATH PROOT_LOADER PROOT_TMP_DIR PROOT_L2S_DIR; " +
                "f=$GUEST_HOME/.nix-profile/etc/profile.d/nix-on-droid-session-init.sh; [ -r \"\$f\" ] && . \"\$f\"; unset f"

        /** The guest dirs that are [rootfs]'s, bound in [command]. */
        private val SYSTEM_DIRS = listOf("nix", "bin", "etc", "usr")

        /**
         * Where [guestPath] is on Android: in a bind, in [rootfs] for the system dirs, the real file under
         * /android, and otherwise the same path, since the guest shares Android's root.
         */
        internal fun hostFile(
            guestPath: String,
            rootfs: File,
            binds: List<Pair<String, File>>,
        ): File {
            fun under(dir: String) = guestPath == dir || guestPath.startsWith("$dir/")
            for ((guest, host) in binds) {
                if (under(guest)) return File(host, guestPath.removePrefix(guest).removePrefix("/"))
            }
            for (dir in SYSTEM_DIRS) {
                if (under("/$dir")) return File(rootfs, guestPath.removePrefix("/"))
            }
            if (under("/android")) return File("/" + guestPath.removePrefix("/android").removePrefix("/"))
            return File(guestPath)
        }
    }
}
