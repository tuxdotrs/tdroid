package com.tux.tlauncher.linux

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Linux apps render OpenGL in software (Mesa's llvmpipe, when an app brings Mesa). */
internal val SOFTWARE_ENV = listOf("LIBGL_ALWAYS_SOFTWARE=1")

/**
 * GPU acceleration for Linux apps. omalauncher installed Turnip or PanVK and a patched Mesa
 * with pacman; that doesn't carry over to Nix, so for now there is no driver for any phone
 * and apps always render in software. The settings row hides while [supported] is false.
 */
class Gpu {
    val supported = false

    val driverLabel = ""

    val wanted = false

    val active = false

    val device: String? = null

    var state by mutableStateOf<JobState>(JobState.Idle)
        private set

    suspend fun recheck() {}

    suspend fun turnOn() {}

    fun turnOff() {}
}
