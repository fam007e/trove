package com.trove.ui.browse

import com.trove.core.fs.FileItem

/**
 * Pure state machine for dual-pane navigation.
 * Manages pane visibility, active pane focus, and pane routing.
 * Kept free of Android View dependencies so it is 100% unit-testable on JVM.
 */
class DualPaneState(
    initialDualPane: Boolean = false
) {
    var isDualPane: Boolean = initialDualPane
        private set

    var activePaneIndex: Int = 0
        private set

    val oppositePaneIndex: Int
        get() = if (activePaneIndex == 0) 1 else 0

    fun toggleDualPane(): Boolean {
        isDualPane = !isDualPane
        if (!isDualPane) {
            activePaneIndex = 0
        }
        return isDualPane
    }

    fun setDualPane(enabled: Boolean) {
        isDualPane = enabled
        if (!enabled) {
            activePaneIndex = 0
        }
    }

    fun setActivePane(index: Int): Boolean {
        if (index != 0 && index != 1) return false
        activePaneIndex = index
        return true
    }
}

/**
 * Host interface for dual-pane coordination between BrowseActivity and BrowseFragment.
 */
interface DualPaneHost {
    val isDualPaneActive: Boolean
    val activePaneIndex: Int
    fun getOppositePaneDirectory(currentPaneIndex: Int): FileItem?
    fun reloadOppositePane(currentPaneIndex: Int)
    fun notifyPaneActive(paneIndex: Int)
    fun openTab(paneIndex: Int, root: FileItem, label: String)
}
