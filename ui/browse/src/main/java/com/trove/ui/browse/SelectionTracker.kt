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

    fun select(item: T): Boolean = selected.add(item)

    fun deselect(item: T): Boolean = selected.remove(item)

    /**
     * Selects all [items]. Returns count of newly added items.
     */
    fun selectAll(items: Collection<T>): Int {
        val initialSize = selected.size
        selected.addAll(items)
        return selected.size - initialSize
    }

    /**
     * Inverts the selection against [allItems].
     * Items that were selected become unselected; unselected items become selected.
     */
    fun invert(allItems: Collection<T>): Int {
        val newSelection = LinkedHashSet<T>()
        for (item in allItems) {
            if (!selected.contains(item)) {
                newSelection.add(item)
            }
        }
        selected.clear()
        selected.addAll(newSelection)
        return selected.size
    }

    /**
     * Selects all items from [items] that satisfy [predicate].
     * Returns count of newly selected items.
     */
    fun selectMatching(items: Collection<T>, predicate: (T) -> Boolean): Int {
        var added = 0
        for (item in items) {
            if (predicate(item) && selected.add(item)) {
                added++
            }
        }
        return added
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
