package com.tux.tdroid.linux

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tux.tdroid.LauncherData
import java.io.File

/** Where the config installs tdroid's Mesa (pkgs.tdroid.mesa in the template), the same on every system. */
private const val MESA = "${Proot.GUEST_HOME}/.nix-profile"

/** Vulkan driver manifests Mesa installs, by the CPU they're built for. */
private fun icd(
    name: String,
    arch: String,
) = "$MESA/share/vulkan/icd.d/${name}_icd.$arch.json"

/**
 * Software rendering: OpenGL on llvmpipe and Vulkan on lavapipe. Both variables name only lavapipe, so the Vulkan loader
 * doesn't search on its own and can't pick up the GPU driver.
 */
internal fun softwareEnv(arch: String) =
    listOf("LIBGL_ALWAYS_SOFTWARE=1", "VK_ICD_FILENAMES=${icd("lvp", arch)}", "VK_DRIVER_FILES=${icd("lvp", arch)}")

/**
 * The GPU: Vulkan on Turnip, Mesa's Adreno driver, through KGSL (/dev/kgsl-3d0, Android's kernel interface), and OpenGL as
 * zink on top of it. Frames come back as wl_shm buffers (MESA_VK_WSI_DEBUG=sw), the only kind the compositor takes;
 * patches/mesa keeps zink on that path. One app can still opt out with `GALLIUM_DRIVER=llvmpipe app`.
 */
internal val TURNIP_ENV =
    listOf(
        "VK_ICD_FILENAMES=${icd("freedreno", "aarch64")}",
        "VK_DRIVER_FILES=${icd("freedreno", "aarch64")}",
        "MESA_VK_WSI_DEBUG=sw",
        "MESA_LOADER_DRIVER_OVERRIDE=zink",
        "GALLIUM_DRIVER=zink",
    )

/** The three-digit Adreno model in a kgsl `gpu_model` string ("Adreno830v2"), or null when it isn't there. */
internal fun adrenoModel(model: String?): Int? {
    if (model.isNullOrBlank()) return null
    val match = Regex("(?i)adreno\\D*(\\d{3})").find(model) ?: Regex("(?<!\\d)(\\d{3})(?!\\d)").find(model.trim())
    return match?.groupValues?.get(1)?.toIntOrNull()
}

/** Turnip speaks Adreno 6xx and newer; older ones fail its probe. */
internal fun turnipSupports(model: String?) = (adrenoModel(model) ?: 0) >= 600

/** The GPU's name in tdroid-gpu-check's output: "zink Vulkan 1.4(Adreno (TM) 830 (MESA_TURNIP))" -> "Adreno (TM) 830". */
internal fun rendererName(line: String): String? =
    Regex("""renderer: zink Vulkan [\d.]+\((.+?)(?: \(MESA_\w+\))?\)\s*$""").find(line)?.groupValues?.get(1)

/**
 * Opt-in GPU acceleration for Linux apps, on Adreno 6xx and newer. Mali isn't offered: upstream PanVK speaks the
 * panfrost DRM interface, and Android's Mali driver is kbase. The driver is Mesa from nixpkgs (tdroid's cache) and
 * talks to a kernel interface Android updates can change, so it's only used after a check passes on the phone, and
 * checked again after every Android update; until then apps render in software.
 */
class Gpu(
    context: Context,
    private val proot: Proot,
) {
    private val prefs = LauncherData.prefs(context)

    /** The CPU the guest's packages are for, as Mesa names its manifests. */
    private val arch = if (File(context.applicationInfo.nativeLibraryDir).name == "x86_64") "x86_64" else "aarch64"

    private val model = runCatching { File("/sys/class/kgsl/kgsl-3d0/gpu_model").readText().trim() }.getOrNull()

    /** The phone has a GPU we have a driver for. */
    val supported = arch == "aarch64" && File("/dev/kgsl-3d0").exists() && turnipSupports(model)

    /** For the settings row; empty when [supported] is false. */
    val driverLabel = if (supported) "Adreno ${adrenoModel(model)}" else ""

    /** The user turned it on; it may still be inactive until [recheck] passes. */
    var wanted by mutableStateOf(prefs.getBoolean(WANTED, false))
        private set

    /** Linux apps started from now on render on the GPU. */
    var active by mutableStateOf(false)
        private set

    /** The GPU the last check found, e.g. "Adreno (TM) 830". */
    var device by mutableStateOf(prefs.getString(DEVICE, null))
        private set

    var state by mutableStateOf<JobState>(JobState.Idle)
        private set

    /** What changes under the driver: the kernel and Android's GPU driver come with system updates. */
    private val build get() = Build.FINGERPRINT + " " + System.getProperty("os.version")

    init {
        proot.graphicsEnv = softwareEnv(arch)
    }

    /** Activates the GPU if it passed its check on this system build; after an Android update, checks it again first. */
    suspend fun recheck() {
        if (!supported || !wanted || active || state is JobState.Working) return
        if (prefs.getString(CHECKED_BUILD, null) == build) return activate()
        Log.i(TAG, "the system changed since the last GPU check, checking again")
        if (check("checking")) activate()
    }

    /** Checks the GPU and turns it on only if the check passes. */
    suspend fun turnOn() {
        if (!supported || state is JobState.Working) return
        prefs.edit().putBoolean(WANTED, true).apply()
        wanted = true
        if (check("checking")) {
            activate()
        } else {
            // Nothing to come back to on the next start; the failure stays on screen.
            prefs.edit().putBoolean(WANTED, false).apply()
            wanted = false
        }
    }

    /** Back to software rendering for apps started from now on. */
    fun turnOff() {
        prefs.edit().putBoolean(WANTED, false).remove(CHECKED_BUILD).apply()
        wanted = false
        active = false
        state = JobState.Idle
        proot.graphicsEnv = softwareEnv(arch)
    }

    private fun activate() {
        active = true
        proot.graphicsEnv = TURNIP_ENV
    }

    /**
     * Runs tdroid-gpu-check with the GPU's environment over the software one; on success remembers the build it passed
     * on and the GPU's name. A config from before tdroid's Mesa has no tdroid-gpu-check, and says so.
     */
    private suspend fun check(action: String): Boolean {
        val exports = TURNIP_ENV.joinToString(" ") { "export ${it.substringBefore('=')}=${shellQuote(it.substringAfter('='))};" }
        val script =
            "unset LIBGL_ALWAYS_SOFTWARE; $exports " +
                "command -v tdroid-gpu-check >/dev/null || { echo 'the Nix config has no GPU driver yet: run nix flake update, then nix-on-droid switch'; exit 1; }; " +
                "timeout 60 tdroid-gpu-check"
        val (ok, tail) = proot.trackedJob(action, script) { state = it }
        val name = tail.toList().firstNotNullOfOrNull(::rendererName)
        Log.i(TAG, "$action: ${if (ok) "ok, $name" else "failed"}")
        if (ok) {
            device = name
            prefs.edit().putString(CHECKED_BUILD, build).putString(DEVICE, name).apply()
        }
        return ok
    }

    private companion object {
        const val TAG = "gpu"
        const val WANTED = "gpu_wanted"
        const val CHECKED_BUILD = "gpu_checked_build"
        const val DEVICE = "gpu_device"
    }
}
