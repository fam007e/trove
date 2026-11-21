package com.trove.feature.cleaner.appcleaner.automation

/**
 * Pure decision logic for the accessibility-driven cache clearing
 * (clean-room behavior, SD-Maid-SE-style automation): given a snapshot of
 * the current Settings window, decide what to click next. Split from
 * [TroveAutomationService] so the matching rules are unit-testable without
 * an accessibility runtime.
 *
 * Flow per package: App info screen → click the "Storage & cache" row →
 * storage screen → click "Clear cache" → button disabled (or a second click
 * had no effect) = done → next package.
 */
object AutomationPlanner {

    enum class Action {
        CLICK_STORAGE_ROW,
        CLICK_CLEAR_CACHE,
        /** Cache was cleared or the button is gone/disabled. */
        DONE,
        /** Nothing actionable in this snapshot — keep waiting. */
        WAIT,
        /** Out of attempts — skip this package. */
        GIVE_UP
    }

    /** One flattened node from the Settings window. */
    data class Node(
        val text: String?,
        val viewIdResourceName: String?,
        val clickable: Boolean,
        val enabled: Boolean
    )

    data class Decision(val action: Action, val targetIndex: Int = -1)

    /** Window events we allow before giving up on one package. */
    const val MAX_ATTEMPTS = 10

    // Buttons accepted as "Clear cache", matched by text across common
    // locales; view-id fallbacks cover OEM builds with different strings.
    val CLEAR_CACHE_TEXTS = listOf(
        "clear cache", "cache löschen", "清除缓存", "キャッシュを削除",
        "vider le cache", "borrar caché", "limpar cache", "cancella cache"
    )

    // Android 12+ storage screen: left button is "Clear cache".
    val CLEAR_CACHE_IDS = listOf(
        "com.android.settings:id/left_button",
        "com.android.settings:id/button2"
    )

    // The storage row shows both keywords ("Storage & cache"), which
    // separates it from buttons like "Clear storage" (no cache word).
    val STORAGE_KEYWORDS = listOf(
        "storage", "speicher", "存储", "ストレージ", "stockage",
        "almacenamiento", "archiviazione", "armazenamento"
    )
    val CACHE_KEYWORDS = listOf(
        "cache", "zwischenspeicher", "缓存", "キャッシュ", "caché",
        "cache", "cache"
    )

    fun isClearCacheButton(node: Node): Boolean {
        val text = node.text?.lowercase()
        if (text != null && CLEAR_CACHE_TEXTS.any { text.contains(it) }) return true
        return node.viewIdResourceName in CLEAR_CACHE_IDS
    }

    fun isStorageRow(node: Node): Boolean {
        val text = node.text?.lowercase() ?: return false
        val hasStorage = STORAGE_KEYWORDS.any { text.contains(it) }
        val hasCache = CACHE_KEYWORDS.any { text.contains(it) }
        return hasStorage && hasCache
    }

    fun decide(nodes: List<Node>, onStorageScreen: Boolean, attempts: Int): Decision {
        if (attempts >= MAX_ATTEMPTS) return Decision(Action.GIVE_UP)

        val clearCacheIndex = nodes.indexOfFirst { isClearCacheButton(it) }
        if (clearCacheIndex >= 0) {
            val button = nodes[clearCacheIndex]
            // A disabled Clear cache means the cache is already empty —
            // the clear already happened (or never needed to).
            return if (button.enabled) Decision(Action.CLICK_CLEAR_CACHE, clearCacheIndex)
            else Decision(Action.DONE)
        }

        if (onStorageScreen) {
            // We navigated here but the button never showed up — nothing
            // else in Settings is ours to touch.
            return if (attempts >= 3) Decision(Action.DONE) else Decision(Action.WAIT)
        }

        val rowIndex = nodes.indexOfFirst { isStorageRow(it) }
        if (rowIndex >= 0) return Decision(Action.CLICK_STORAGE_ROW, rowIndex)

        return Decision(Action.WAIT)
    }
}
