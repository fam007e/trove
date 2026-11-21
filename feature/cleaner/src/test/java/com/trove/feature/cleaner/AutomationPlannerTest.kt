package com.trove.feature.cleaner

import com.trove.feature.cleaner.appcleaner.automation.AutomationPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationPlannerTest {

    private fun node(
        text: String? = null,
        viewId: String? = null,
        clickable: Boolean = false,
        enabled: Boolean = true
    ) = AutomationPlanner.Node(text, viewId, clickable, enabled)

    @Test
    fun `storage row is recognized by both keywords`() {
        assertTrue(
            AutomationPlanner.isStorageRow(node("Storage & cache"))
        )
        assertTrue(AutomationPlanner.isStorageRow(node("Speicher & Cache")))
        assertTrue(AutomationPlanner.isStorageRow(node("存储和缓存")))
        // Buttons that only mention storage are NOT the row.
        assertTrue(!AutomationPlanner.isStorageRow(node("Clear storage")))
        assertTrue(!AutomationPlanner.isStorageRow(node("Notifications")))
    }

    @Test
    fun `clear cache button matches text and view ids`() {
        assertTrue(AutomationPlanner.isClearCacheButton(node("Clear cache")))
        assertTrue(AutomationPlanner.isClearCacheButton(node("CLEAR CACHE")))
        assertTrue(AutomationPlanner.isClearCacheButton(node(viewId = "com.android.settings:id/left_button")))
        assertTrue(!AutomationPlanner.isClearCacheButton(node("Clear storage")))
    }

    @Test
    fun `app info screen routes to the storage row`() {
        val nodes = listOf(
            node("App info"),
            node("Open by default"),
            node("Storage & cache"),
            node("Notifications")
        )
        val decision = AutomationPlanner.decide(nodes, onStorageScreen = false, attempts = 0)
        assertEquals(AutomationPlanner.Action.CLICK_STORAGE_ROW, decision.action)
        assertEquals(2, decision.targetIndex)
    }

    @Test
    fun `enabled clear cache button is clicked`() {
        val nodes = listOf(
            node("Clear storage"),
            node("Clear cache", viewId = "com.android.settings:id/left_button", clickable = true)
        )
        val decision = AutomationPlanner.decide(nodes, onStorageScreen = true, attempts = 0)
        assertEquals(AutomationPlanner.Action.CLICK_CLEAR_CACHE, decision.action)
        assertEquals(1, decision.targetIndex)
    }

    @Test
    fun `disabled clear cache button means done`() {
        val nodes = listOf(
            node("Clear cache", viewId = "com.android.settings:id/left_button", clickable = true, enabled = false)
        )
        assertEquals(
            AutomationPlanner.Action.DONE,
            AutomationPlanner.decide(nodes, onStorageScreen = true, attempts = 0).action
        )
    }

    @Test
    fun `empty storage screen settles after a few waits`() {
        assertEquals(
            AutomationPlanner.Action.WAIT,
            AutomationPlanner.decide(emptyList(), onStorageScreen = true, attempts = 0).action
        )
        // After several fruitless looks, declare victory rather than loop:
        // the button never appeared, so there was nothing to clear.
        assertEquals(
            AutomationPlanner.Action.DONE,
            AutomationPlanner.decide(emptyList(), onStorageScreen = true, attempts = 3).action
        )
    }

    @Test
    fun `attempts are bounded`() {
        assertEquals(
            AutomationPlanner.Action.GIVE_UP,
            AutomationPlanner.decide(emptyList(), onStorageScreen = false, attempts = AutomationPlanner.MAX_ATTEMPTS).action
        )
    }

    @Test
    fun `nothing on screen waits`() {
        assertEquals(
            AutomationPlanner.Action.WAIT,
            AutomationPlanner.decide(listOf(node("App info"), node("Uninstall")), onStorageScreen = false, attempts = 0).action
        )
    }
}
