package com.trove.feature.cleaner

import android.content.Context
import android.content.SharedPreferences

/**
 * Cleaner preferences, separate from the browse UI prefs so the feature
 * module owns its own storage.
 */
object CleanerPrefs {

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.getSharedPreferences("trove_cleaner", Context.MODE_PRIVATE)
        }
    }

    /** AppCleaner: include system apps in scans (they cache too). */
    var includeSystemApps: Boolean
        get() = prefs.getBoolean("include_system_apps", false)
        set(v) = prefs.edit().putBoolean("include_system_apps", v).apply()

    /** AppCleaner: prefer the accessibility automation over root when both exist. */
    var preferAutomation: Boolean
        get() = prefs.getBoolean("prefer_automation", false)
        set(v) = prefs.edit().putBoolean("prefer_automation", v).apply()

    /** Last scan totals, surfaced on the hub cards before the next scan. */
    var lastCorpsesCount: Int
        get() = prefs.getInt("last_corpses", -1)
        set(v) = prefs.edit().putInt("last_corpses", v).apply()

    var lastAppCleanerBytes: Long
        get() = prefs.getLong("last_app_cache", -1)
        set(v) = prefs.edit().putLong("last_app_cache", v).apply()

    var lastSystemCleanerCount: Int
        get() = prefs.getInt("last_system", -1)
        set(v) = prefs.edit().putInt("last_system", v).apply()

    var lastDupeGroups: Int
        get() = prefs.getInt("last_dupe_groups", -1)
        set(v) = prefs.edit().putInt("last_dupe_groups", v).apply()

    /** Deduplicator: only group byte-identical files that also share a name. */
    var dedupeMatchByName: Boolean
        get() = prefs.getBoolean("dedupe_match_by_name", true)
        set(v) = prefs.edit().putBoolean("dedupe_match_by_name", v).apply()
}
