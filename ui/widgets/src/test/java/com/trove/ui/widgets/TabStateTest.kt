package com.trove.ui.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure tab-list state behind [TroveTabBar].
 * These encode the selection semantics the View layer relies on:
 * - select() only fires on valid, *different* indices (returns old index)
 * - remove() clamps the selection into the remaining range
 * - tabIndexAt() maps touch coordinates defensively
 */
class TabStateTest {

    @Test
    fun `add accumulates labels in order`() {
        val state = TabState()
        state.add("Home")
        state.add("Downloads")
        assertEquals(listOf("Home", "Downloads"), state.labelList)
        assertEquals(2, state.size)
    }

    @Test
    fun `select returns previous index only on change`() {
        val state = TabState()
        repeat(3) { state.add("tab$it") }

        assertEquals(-1, state.select(0)) // already selected
        assertEquals(0, state.select(2))  // 0 -> 2
        assertEquals(2, state.selectedIndex)
        assertEquals(-1, state.select(2)) // no-op
        assertEquals(2, state.select(1))  // 2 -> 1
        assertEquals(1, state.selectedIndex)
    }

    @Test
    fun `select out of range is rejected`() {
        val state = TabState()
        state.add("only")
        assertEquals(-1, state.select(1))
        assertEquals(-1, state.select(-1))
        assertEquals(0, state.selectedIndex)
    }

    @Test
    fun `remove clamps selection to remaining tabs`() {
        val state = TabState()
        repeat(3) { state.add("tab$it") }
        state.select(2)

        assertTrue(state.remove(2))
        assertEquals(2, state.size)
        assertEquals(1, state.selectedIndex) // clamped from 2

        state.remove(0)
        assertEquals(1, state.size)
        assertEquals(0, state.selectedIndex)
    }

    @Test
    fun `remove out of range is rejected`() {
        val state = TabState()
        state.add("keep")
        assertFalse(state.remove(1))
        assertFalse(state.remove(-1))
        assertEquals(1, state.size)
    }

    @Test
    fun `remove last tab clamps selection to zero`() {
        val state = TabState()
        state.add("solo")
        state.select(0)
        assertTrue(state.remove(0))
        assertEquals(0, state.size)
        assertEquals(0, state.selectedIndex)
    }

    @Test
    fun `update replaces label only in range`() {
        val state = TabState()
        state.add("a")
        state.add("b")
        assertTrue(state.update(1, "B"))
        assertEquals(listOf("a", "B"), state.labelList)
        assertFalse(state.update(2, "c"))
        assertFalse(state.update(-1, "x"))
        assertEquals(listOf("a", "B"), state.labelList)
    }

    @Test
    fun `tabIndexAt maps coordinates across equal-width tabs`() {
        // 4 tabs over 400px = 100px each
        assertEquals(0, TabState.tabIndexAt(0f, 400f, 4))
        assertEquals(0, TabState.tabIndexAt(99f, 400f, 4))
        assertEquals(1, TabState.tabIndexAt(100f, 400f, 4))
        assertEquals(3, TabState.tabIndexAt(399f, 400f, 4))
        assertEquals(3, TabState.tabIndexAt(10_000f, 400f, 4)) // clamped high
        assertEquals(0, TabState.tabIndexAt(-50f, 400f, 4))    // clamped low
    }

    @Test
    fun `tabIndexAt handles degenerate geometry`() {
        assertEquals(-1, TabState.tabIndexAt(10f, 0f, 4))   // zero width
        assertEquals(-1, TabState.tabIndexAt(10f, 400f, 0)) // no tabs
    }
}
