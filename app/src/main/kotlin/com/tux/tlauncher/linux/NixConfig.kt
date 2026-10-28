package com.tux.tlauncher.linux

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Builds and activates ~/.config/nix-on-droid; Nix-on-Droid's own `nix-on-droid switch`. */
const val NIX_SWITCH = "nix-on-droid switch --flake ~/.config/nix-on-droid"

/**
 * The guest's Nix-on-Droid config, ~/.config/nix-on-droid, made on first boot from tlauncher's
 * template (userspace-template in the repo). The launcher only edits tlauncher-packages.json in
 * it, the nixpkgs attributes it added (coding agents); the rest of the config is the user's.
 */
class NixConfig(
    private val proot: Proot,
) {
    /** One `nix-on-droid switch` at a time; everything that changes the system takes this first. */
    val lock = Mutex()

    private val packagesFile get() = File(proot.home, ".config/nix-on-droid/$PACKAGES_FILE")

    /** The attributes in tlauncher-packages.json; empty before first boot made the config. */
    fun packages(): List<String> = runCatching { parsePackages(packagesFile.readText()) }.getOrDefault(emptyList())

    /** Writes [attrs] to tlauncher-packages.json; the system has them after the next [NIX_SWITCH]. */
    fun writePackages(attrs: List<String>) {
        packagesFile.parentFile?.mkdirs()
        packagesFile.writeText(formatPackages(attrs))
    }

    /**
     * Adds [add] and drops [remove] from the config, then switches to it. A failed switch puts the
     * file back, so the next switch doesn't fail on the same package.
     */
    suspend fun change(
        add: Collection<String> = emptyList(),
        remove: Collection<String> = emptyList(),
        action: String,
        publish: (JobState) -> Unit,
    ): Boolean {
        publish(JobState.Working("waiting", emptyList()))
        return lock.withLock {
            val before = packages()
            writePackages((before - remove.toSet() + add).distinct())
            val (ok, _) = proot.trackedJob(action, NIX_SWITCH, publish = publish)
            if (!ok) {
                Log.w(TAG, "switch failed; restoring $PACKAGES_FILE")
                writePackages(before)
            }
            ok
        }
    }

    companion object {
        private const val TAG = "nix"
        const val PACKAGES_FILE = "tlauncher-packages.json"

        private val STRING = Regex(""""((?:[^"\\]|\\.)*)"""")

        /** A JSON array of attribute names; they never need escapes. */
        fun parsePackages(json: String): List<String> = STRING.findAll(json).map { it.groupValues[1] }.toList()

        fun formatPackages(attrs: List<String>): String = attrs.joinToString(",\n", prefix = "[\n", postfix = "\n]\n") { "  \"$it\"" }.let {
            if (attrs.isEmpty()) "[]\n" else it
        }
    }
}
