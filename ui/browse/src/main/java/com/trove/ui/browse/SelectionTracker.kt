package com.trove.ui.browse

/**
 * Pure selection state for [FileAdapter], kept free of RecyclerView/View
 * dependencies so the toggle/clear semantics are unit-testable on the JVM.
 *
 * The adapter keeps its notify* calls in sync with the mutation results
 * returned from these methods.
 */
class SelectionTracker<T : Any> {

    private val selected = LinkedHashSet<T>()

    /** Read-only view of the currently selected items, in selection order. */
    val selectedItems: Set<T> get() = java.util.Collections.unmodifiableSet(selected)

    val isEmpty: Boolean get() = selected.isEmpty()

    fun contains(item: T): Boolean = selected.contains(item)

    /**
     * Toggles [item]. Returns true if the item is now selected,
     * false if it was deselected.
     */
    fun toggle(item: T): Boolean {
        return if (selected.remove(item)) {
            false
        } else {
            selected.add(item)
            true
        }
    }

    /**
     * Clears the selection. Returns the number of items that were selected,
     * so the caller can skip notifications when nothing changed.
     */
    fun clear(): Int {
        val count = selected.size
        selected.clear()
        return count
    }
}
