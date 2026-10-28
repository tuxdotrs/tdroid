package com.tux.tlauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Test

class NixConfigTest {
    @Test fun packagesRoundTrip() {
        val attrs = listOf("claude-code", "nodePackages.prettier")
        assertEquals(attrs, NixConfig.parsePackages(NixConfig.formatPackages(attrs)))
    }

    /** The template's file, before the launcher wrote it. */
    @Test fun emptyPackageList() {
        assertEquals(emptyList<String>(), NixConfig.parsePackages("[]\n"))
        assertEquals("[]\n", NixConfig.formatPackages(emptyList()))
    }
}
