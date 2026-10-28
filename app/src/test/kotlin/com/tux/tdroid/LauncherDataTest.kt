package com.tux.tdroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LauncherDataTest {
    // Removing Linux empties its home but leaves the launcher's layout, widgets and style.
    @Test fun clearingHomeKeepsTheLaunchersConfig() {
        val home = Files.createTempDirectory("home").toFile()
        File(home, ".bashrc").writeText("x")
        File(home, ".config/nix-on-droid").mkdirs()
        File(home, ".config/nix-on-droid/flake.nix").writeText("x")
        File(home, ".config/tdroid/widgets").mkdirs()
        File(home, ".config/tdroid/widgets/a.owl").writeText("widget")
        File(home, ".config/tdroid/home").mkdirs()
        File(home, ".config/tdroid/home/layout.toml").writeText("layout")
        LauncherData.clearHome(home)
        assertFalse(File(home, ".bashrc").exists())
        assertFalse(File(home, ".config/nix-on-droid").exists())
        assertEquals("widget", File(home, ".config/tdroid/widgets/a.owl").readText())
        assertTrue(File(home, ".config/tdroid/home/layout.toml").isFile)
        home.deleteRecursively()
    }
}
