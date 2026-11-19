package com.trove.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for [DualPaneState].
 * Verifies pane toggling, index bounds checking, and opposite pane resolution.
 */
class DualPaneStateTest {

    @Test
    fun `initial state defaults to single pane with pane 0 active`() {
        val state = DualPaneState()
        assertFalse(state.isDualPane)
        assertEquals(0, state.activePaneIndex)
        assertEquals(1, state.oppositePaneIndex)
    }

    @Test
    fun `initial state can be initialized with dual pane enabled`() {
        val state = DualPaneState(initialDualPane = true)
        assertTrue(state.isDualPane)
        assertEquals(0, state.activePaneIndex)
        assertEquals(1, state.oppositePaneIndex)
    }

    @Test
    fun `toggleDualPane toggles state and resets activePaneIndex to 0 when disabled`() {
        val state = DualPaneState()
        assertTrue(state.toggleDualPane())
        assertTrue(state.isDualPane)

        // Switch to pane 1
        assertTrue(state.setActivePane(1))
        assertEquals(1, state.activePaneIndex)
        assertEquals(0, state.oppositePaneIndex)

        // Disabling resets activePane to 0
        assertFalse(state.toggleDualPane())
        assertFalse(state.isDualPane)
        assertEquals(0, state.activePaneIndex)
        assertEquals(1, state.oppositePaneIndex)
    }

    @Test
    fun `setDualPane controls enabled state and resets activePaneIndex on disable`() {
        val state = DualPaneState()
        state.setDualPane(true)
        assertTrue(state.isDualPane)

        state.setActivePane(1)
        assertEquals(1, state.activePaneIndex)

        state.setDualPane(false)
        assertFalse(state.isDualPane)
        assertEquals(0, state.activePaneIndex)
    }

    @Test
    fun `setActivePane validates valid indices and rejects invalid ones`() {
        val state = DualPaneState(initialDualPane = true)

        assertTrue(state.setActivePane(1))
        assertEquals(1, state.activePaneIndex)
        assertEquals(0, state.oppositePaneIndex)

        assertTrue(state.setActivePane(0))
        assertEquals(0, state.activePaneIndex)
        assertEquals(1, state.oppositePaneIndex)

        // Invalid indices
        assertFalse(state.setActivePane(-1))
        assertEquals(0, state.activePaneIndex)

        assertFalse(state.setActivePane(2))
        assertEquals(0, state.activePaneIndex)
    }
}
