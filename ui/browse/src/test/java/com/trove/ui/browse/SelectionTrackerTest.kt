package com.trove.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure selection state behind [FileAdapter].
 * Encodes the semantics the adapter's notify* calls depend on:
 * - toggle() returns the resulting selected state
 * - selectAll() selects all provided items
 * - invert() correctly flips selection state
 * - selectMatching() filters by predicate
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
    fun `selectAll selects all items and returns delta`() {
        val tracker = SelectionTracker<String>()
        tracker.select("a")

        val delta = tracker.selectAll(listOf("a", "b", "c"))
        assertEquals(2, delta)
        assertEquals(3, tracker.selectedItems.size)
        assertTrue(tracker.contains("a"))
        assertTrue(tracker.contains("b"))
        assertTrue(tracker.contains("c"))
    }

    @Test
    fun `invert selection flips selected and unselected items`() {
        val tracker = SelectionTracker<String>()
        val all = listOf("a", "b", "c", "d")
        tracker.select("a")
        tracker.select("c")

        val newCount = tracker.invert(all)
        assertEquals(2, newCount)
        assertFalse(tracker.contains("a"))
        assertTrue(tracker.contains("b"))
        assertFalse(tracker.contains("c"))
        assertTrue(tracker.contains("d"))
    }

    @Test
    fun `selectMatching selects items satisfying predicate`() {
        val tracker = SelectionTracker<String>()
        val all = listOf("report.pdf", "image.png", "doc.pdf", "script.py")

        val added = tracker.selectMatching(all) { it.endsWith(".pdf") }
        assertEquals(2, added)
        assertEquals(setOf("report.pdf", "doc.pdf"), tracker.selectedItems)
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
            assertTrue("selectedItems must not be externally mutable", tracker.selectedItems.size == 1)
        } catch (_: UnsupportedOperationException) {
            // expected on a properly unmodifiable view
        }
        assertEquals(setOf("a"), tracker.selectedItems)
    }
}
