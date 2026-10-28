package com.tux.tdroid.ui

import com.tux.tdroid.widgets.NEW_PAGE_LEFT
import com.tux.tdroid.widgets.isFacePage
import org.junit.Assert.assertEquals
import org.junit.Test

class SlotsTest {
    // Faces left of page 1 are full pages; so is page 4 here, holding a face.
    private fun full(page: Int) = page <= 0 || page == 4

    @Test fun foldedEveryPageIsASlot() {
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), slotsOf(listOf(0, 1, 2), 1, ::full))
    }

    @Test fun unfoldedPageOneStartsAPair() {
        // As 0.8.1 paired them, (1, 2) and (3, 4), whatever faces stand left of page 1.
        assertEquals(listOf(listOf(-1), listOf(0), listOf(1, 2), listOf(3, 4)), slotsOf(listOf(-1, 0, 1, 2, 3), 2) { it <= 0 })
    }

    @Test fun unfoldedFaceTakesASlotOfItsOwn() {
        // Page 3 has a face after it: it stands alone. The last page gets an empty partner.
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3), listOf(4), listOf(5, 6)), slotsOf(listOf(0, 1, 2, 3, 4, 5), 2, ::full))
    }

    // Carrying a tile unfolded: the page that would become page 1 stands alone, and the pairs after it don't shift.
    @Test fun theNewLeftPageStandsAlone() {
        assertEquals(listOf(listOf(NEW_PAGE_LEFT), listOf(1, 2), listOf(3, 4)), slotsOf(listOf(NEW_PAGE_LEFT, 1, 2, 3), 2, ::isFacePage))
    }

    // The wallpaper stays put as a carried tile adds empty pages at both ends, and moves as the pager turns between home's own.
    @Test fun addedPagesDontMoveTheWallpaper() {
        val resting = listOf(listOf(1), listOf(2))
        val carrying = listOf(listOf(NEW_PAGE_LEFT), listOf(1), listOf(2), listOf(3))
        val own = setOf(1, 2)
        assertEquals(0f to 2, restingPosition(resting, own, 0f))
        assertEquals(0f to 2, restingPosition(carrying, own, 1f))
        assertEquals(0f to 2, restingPosition(carrying, own, 0f))
        assertEquals(1f to 2, restingPosition(carrying, own, 2f))
        assertEquals(1f to 2, restingPosition(carrying, own, 3f))
        assertEquals(0.5f to 2, restingPosition(carrying, own, 1.5f))
    }
}
