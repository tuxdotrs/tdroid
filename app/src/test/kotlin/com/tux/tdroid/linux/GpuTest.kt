package com.tux.tdroid.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuTest {
    @Test fun adrenoModelsFromKgsl() {
        assertEquals(830, adrenoModel("Adreno830v2"))
        assertEquals(650, adrenoModel("Adreno (TM) 650"))
        assertEquals(619, adrenoModel("619"))
        assertNull(adrenoModel(""))
        assertNull(adrenoModel(null))
    }

    @Test fun turnipFromAdreno6xx() {
        assertTrue(turnipSupports("Adreno830v2"))
        assertTrue(turnipSupports("Adreno619"))
        assertFalse(turnipSupports("Adreno540"))
        assertFalse(turnipSupports(null))
    }

    @Test fun rendererNameFromEglinfo() {
        assertEquals("Adreno (TM) 830", rendererName("OpenGL core profile renderer: zink Vulkan 1.4(Adreno (TM) 830 (MESA_TURNIP))"))
        assertNull(rendererName("OpenGL core profile renderer: llvmpipe (LLVM 21.1.8, 128 bits)"))
    }

    @Test fun softwareNamesOnlyLavapipe() {
        val env = softwareEnv("aarch64")
        assertTrue("LIBGL_ALWAYS_SOFTWARE=1" in env)
        assertTrue(env.filter { it.startsWith("VK_") }.all { it.endsWith("/lvp_icd.aarch64.json") })
    }
}
