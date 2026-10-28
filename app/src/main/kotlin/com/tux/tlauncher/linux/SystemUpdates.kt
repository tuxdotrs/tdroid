package com.tux.tlauncher.linux

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A tool that can be upgraded, by its name. */
data class ToolUpdate(
    val name: String,
    val current: String,
    val latest: String,
)

/** What the last check found. [error] is set when it couldn't reach the servers. */
data class PendingUpdates(
    /** Each as "name old → new". */
    val packages: List<String>,
    val tools: List<ToolUpdate>,
    val checkedAt: Long,
    val error: String? = null,
) {
    val count get() = packages.size + tools.size
}

/**
 * Updates for the guest. omalauncher checked pacman and mise here; on Nix the system is the user's
 * flake, updated with `nix flake update` and [NIX_SWITCH], which the launcher doesn't run on its own
 * yet. So nothing is ever pending, and settings shows no system updates.
 */
class SystemUpdates {
    var pending by mutableStateOf<PendingUpdates?>(null)
        private set

    var checking by mutableStateOf(false)
        private set

    var job by mutableStateOf<JobState>(JobState.Idle)
        private set

    fun load() {}

    fun forget() {}

    suspend fun check(force: Boolean = false) {}

    suspend fun updateAll(): Boolean = false
}
