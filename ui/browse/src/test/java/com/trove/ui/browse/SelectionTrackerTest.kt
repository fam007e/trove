package com.trove.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure selection state behind [FileAdapter].
 * Encodes the semantics the adapter's notify* calls depend on:
 * - toggle() returns the resulting selected state
 * - clear() returns the previous count so no-op clears skip notifications
 */
class SelectionTrackerTest {

    @Test
    fun `toggle selects then deselects`() {
        val tracker = SelectionTracker<String>()
        assertTrue(tracker.toggle("a"))
        assertTrue(tracker.contains("a"))
        assertEquals(setOf("a"), tracker.selectedItems)

        assertFalse(tracker.toggle("a"))
        assertFalse(tracker.contains("a"))
        assertTrue(tracker.isEmpty)
    }

    @Test
    fun `toggle preserves selection order`() {
        val tracker = SelectionTracker<String>()
        tracker.toggle("b")
        tracker.toggle("a")
        tracker.toggle("c")
        assertEquals(listOf("b", "a", "c"), tracker.selectedItems.toList())
    }

    @Test
    fun `toggle returns the new state per item`() {
        val tracker = SelectionTracker<String>()
        assertTrue(tracker.toggle("x"))
        assertTrue(tracker.toggle("y"))
        // x toggled off, y still on
        assertFalse(tracker.toggle("x"))
        assertTrue(tracker.contains("y"))
        assertFalse(tracker.contains("x"))
    }

    @Test
    fun `clear returns previous count and zero on repeat`() {
        val tracker = SelectionTracker<Int>()
        tracker.toggle(1)
        tracker.toggle(2)
        tracker.toggle(3)
        assertEquals(3, tracker.clear())
        assertTrue(tracker.isEmpty)
        assertEquals(0, tracker.clear())
    }

    @Test
    fun `clear then re-toggle is fresh`() {
        val tracker = SelectionTracker<String>()
        tracker.toggle("old")
        tracker.clear()
        assertFalse(tracker.contains("old"))
        assertTrue(tracker.toggle("new"))
        assertEquals(setOf("new"), tracker.selectedItems)
    }

    @Test
    fun `selectedItems is unmodifiable`() {
        val tracker = SelectionTracker<String>()
        tracker.toggle("a")
        try {
            @Suppress("UNCHECKED_CAST")
            (tracker.selectedItems as MutableSet<String>).add("b")
            // If the cast worked and it mutated, that is a failure of encapsulation
            assertTrue("selectedItems must not be externally mutable", tracker.selectedItems.size == 1)
        } catch (_: UnsupportedOperationException) {
            // expected on a properly unmodifiable view
        }
        assertEquals(setOf("a"), tracker.selectedItems)
    }
}
