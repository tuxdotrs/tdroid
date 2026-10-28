package com.tux.tdroid.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PagerPagesTest {
    @Test fun anEmptyPageWaitsAfterTheLast() {
        assertEquals(listOf(1, 2, 3), pagerPages(listOf(1, 2), lastEmpty = false, emptyPageRight = true))
    }

    @Test fun anEmptyLastPageIsTheOneAfter() {
        assertEquals(listOf(1, 2), pagerPages(listOf(1, 2), lastEmpty = true, emptyPageRight = true))
    }

    @Test fun nothingWaitsLeftOfFaces() {
        assertEquals(listOf(-1, 0, 1, 2), pagerPages(listOf(-1, 0, 1), lastEmpty = false, emptyPageRight = true))
    }

    @Test fun withoutItOnlyHomesOwnPages() {
        assertEquals(listOf(1, 2), pagerPages(listOf(1, 2), lastEmpty = false, emptyPageRight = false))
        assertEquals(listOf(0, 1), pagerPages(listOf(0, 1), lastEmpty = false, emptyPageRight = false))
    }

    @Test fun anEmptyHomeKeepsItsOnePage() {
        assertEquals(listOf(1), pagerPages(listOf(1), lastEmpty = true, emptyPageRight = false))
    }
}
