package com.tux.tdroid.linux

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * tdroid's binary cache, where the patched Mesa is (see the flake's tdroidOverlay), as Nix settings. The config lists it
 * too (nix.substituters), but a switch reads nix.conf from the system it replaces: on first boot, and on a system from
 * before the cache, that one doesn't have it yet, and Mesa would be compiled on the phone.
 */
const val TDROID_CACHE =
    "extra-substituters = https://tuxdotrs.cachix.org\\n" +
        "extra-trusted-public-keys = tuxdotrs.cachix.org-1:L9A8WmHYbSKBVsoxTzMCH/CMX8dExo7cnfPN+hEIcg4="

/** Builds and activates ~/.config/nix-on-droid; Nix-on-Droid's own `nix-on-droid switch`. */
const val NIX_SWITCH = "NIX_CONFIG=\"$(printf '$TDROID_CACHE')\" nix-on-droid switch --flake ~/.config/nix-on-droid"

/**
 * The guest's Nix-on-Droid config, ~/.config/nix-on-droid, made on first boot from tdroid's
 * template (userspace-template in the repo). The launcher only edits tdroid-packages.json in
 * it, the nixpkgs attributes it added (coding agents); the rest of the config is the user's.
 */
class NixConfig(
    private val proot: Proot,
) {
    /** One `nix-on-droid switch` at a time; everything that changes the system takes this first. */
    val lock = Mutex()

    private val packagesFile get() = File(proot.home, ".config/nix-on-droid/$PACKAGES_FILE")

    /** The attributes in tdroid-packages.json; empty before first boot made the config. */
    fun packages(): List<String> = runCatching { parsePackages(packagesFile.readText()) }.getOrDefault(emptyList())

    /** Writes [attrs] to tdroid-packages.json; the system has them after the next [NIX_SWITCH]. */
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
        const val PACKAGES_FILE = "tdroid-packages.json"

        private val STRING = Regex(""""((?:[^"\\]|\\.)*)"""")

        /** A JSON array of attribute names; they never need escapes. */
        fun parsePackages(json: String): List<String> = STRING.findAll(json).map { it.groupValues[1] }.toList()

        fun formatPackages(attrs: List<String>): String = attrs.joinToString(",\n", prefix = "[\n", postfix = "\n]\n") { "  \"$it\"" }.let {
            if (attrs.isEmpty()) "[]\n" else it
        }
    }
}
