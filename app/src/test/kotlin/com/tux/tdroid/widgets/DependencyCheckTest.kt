package com.tux.tdroid.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DependencyCheckTest {
    @Test fun quotesNamesAndReadsBackWhatsMissing() {
        val script = DependencyCheck.script(listOf("jq", "it's"), listOf("curl"))
        assertTrue(script.startsWith("for p in 'jq' 'it'\\''s'; do command -v \"\$p\" >/dev/null || echo \"pkg \$p\"; done; "))
        assertTrue(script.contains("for c in 'curl'; do"))
        assertEquals("true", DependencyCheck.script(emptyList(), emptyList()))
        val missing = DependencyCheck.parse("pkg jq\n  bin curl \nsomething else\n")
        assertEquals(setOf("jq"), missing.packages)
        assertEquals(setOf("curl"), missing.programs)
    }
}
