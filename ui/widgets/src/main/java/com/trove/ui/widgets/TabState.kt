package com.trove.ui.widgets

/**
 * Pure tab-list state for [TroveTabBar], kept free of any View/Canvas
 * dependencies so the selection semantics are unit-testable on the JVM.
 *
 * Selection semantics (mirrors the original TroveTabBar implementation):
 * - [select] only acts when [index] is valid AND different from the current
 *   selection; it returns the previous selection index, or -1 if nothing changed.
 * - [remove] clamps the selection down to the last tab when the current
 *   selection index falls out of range.
 */
class TabState {

    private val labels = mutableListOf<String>()

    val size: Int get() = labels.size
    val labelList: List<String> get() = labels.toList()
    var selectedIndex: Int = 0
        private set

    fun add(label: String) {
        labels.add(label)
    }

    fun update(index: Int, label: String): Boolean {
        if (index !in labels.indices) return false
        labels[index] = label
        return true
    }

    /**
     * Removes the tab at [index]. Returns true if the list changed, and
     * additionally reports the (possibly clamped) selection via [newSelection].
     */
    fun remove(index: Int): Boolean {
        if (index !in labels.indices) return false
        labels.removeAt(index)
        if (selectedIndex >= labels.size) {
            selectedIndex = (labels.size - 1).coerceAtLeast(0)
        }
        return true
    }

    /**
     * Selects [index] if valid and different. Returns the previous selection
     * index on change, or -1 when the selection did not change.
     */
    fun select(index: Int): Int {
        if (index !in labels.indices || selectedIndex == index) return -1
        val old = selectedIndex
        selectedIndex = index
        return old
    }

    companion object {
        /**
         * Maps a touch x-coordinate within [width] to a tab index among [count]
         * tabs. Returns -1 when there are no tabs or no measurable width.
         */
        fun tabIndexAt(x: Float, width: Float, count: Int): Int {
            if (count <= 0 || width <= 0f) return -1
            return (x / (width / count)).toInt().coerceIn(0, count - 1)
        }
    }
}
