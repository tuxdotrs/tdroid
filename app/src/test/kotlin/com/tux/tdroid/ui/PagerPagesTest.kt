package com.tux.tdroid.ui

import com.tux.tdroid.widgets.NEW_PAGE_LEFT
import org.junit.Assert.assertEquals
import org.junit.Test

class PagerPagesTest {
    @Test fun onlyHomesOwnPagesAtRest() {
        assertEquals(listOf(1, 2), pagerPages(listOf(1, 2), lastEmpty = false, carrying = false))
        assertEquals(listOf(0, 1), pagerPages(listOf(0, 1), lastEmpty = false, carrying = false))
    }

    @Test fun carryingAddsAnEmptyPageAtEachEnd() {
        assertEquals(listOf(NEW_PAGE_LEFT, 1, 2, 3), pagerPages(listOf(1, 2), lastEmpty = false, carrying = true))
    }

    @Test fun anEmptyLastPageIsTheOneAfter() {
        assertEquals(listOf(NEW_PAGE_LEFT, 1, 2), pagerPages(listOf(1, 2), lastEmpty = true, carrying = true))
    }

    @Test fun theNewPageGoesBetweenFacesAndPageOne() {
        assertEquals(listOf(-1, 0, NEW_PAGE_LEFT, 1, 2), pagerPages(listOf(-1, 0, 1), lastEmpty = false, carrying = true))
    }

    @Test fun anEmptyHomeKeepsItsOnePage() {
        assertEquals(listOf(1), pagerPages(listOf(1), lastEmpty = true, carrying = false))
    }
}
