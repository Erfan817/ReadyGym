package com.readygym.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The "±2 items" preload rule from review document §5, pinned as a pure function.
 *
 * Testing the *window* rather than the loading itself is deliberate: what can regress silently is
 * the arithmetic (loading three items ahead but none behind, or loading the entire list), not the
 * `ImageLoader.enqueue` call.
 */
class PreloadWindowTest {

    @Test fun aMiddleViewportLoadsTwoOnEachSide() {
        assertEquals(2..8, preloadWindow(firstVisible = 4, lastVisible = 6, size = 12))
    }

    @Test fun theFirstItemsDoNotWrapAroundToTheEnd() {
        assertEquals(0..4, preloadWindow(firstVisible = 0, lastVisible = 2, size = 12))
    }

    @Test fun theLastItemsDoNotWrapAroundToTheStart() {
        assertEquals(7..11, preloadWindow(firstVisible = 9, lastVisible = 11, size = 12))
    }

    /** A list shorter than the window must not produce out-of-range indices. */
    @Test fun aShortListIsClamped() {
        assertEquals(0..2, preloadWindow(firstVisible = 0, lastVisible = 2, size = 3))
        assertEquals(0..0, preloadWindow(firstVisible = 0, lastVisible = 0, size = 1))
    }

    @Test fun anEmptyListLoadsNothing() {
        assertEquals(IntRange.EMPTY, preloadWindow(firstVisible = 0, lastVisible = 0, size = 0))
    }

    /** A stale scroll position (after a data refresh) must stay inside the new list. */
    @Test fun outOfRangeViewportIsClampedInsteadOfCrashing() {
        // Both ends past the list: clamp to the last valid item, then take its ±2 window.
        assertEquals(7..9, preloadWindow(firstVisible = 99, lastVisible = 120, size = 10))
        // Both ends before the list: clamp to the first item.
        assertEquals(0..2, preloadWindow(firstVisible = -5, lastVisible = -1, size = 10))
    }
}
