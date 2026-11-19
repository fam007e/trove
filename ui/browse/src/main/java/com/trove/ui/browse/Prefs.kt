package com.trove.ui.browse

import android.content.Context
import android.content.SharedPreferences

/**
 * App-wide UI preferences, persisted to SharedPreferences. Read once per
 * process; writes update both memory and disk.
 */
object Prefs {

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.getSharedPreferences("trove_ui", Context.MODE_PRIVATE)
        }
    }

    var sortBy: String
        get() = prefs.getString("sort_by", "name") ?: "name"
        set(v) = prefs.edit().putString("sort_by", v).apply()

    var showHidden: Boolean
        get() = prefs.getBoolean("show_hidden", false)
        set(v) = prefs.edit().putBoolean("show_hidden", v).apply()

    /** Bookmarks as "name|path" pairs. */
    fun bookmarks(): Set<String> = prefs.getStringSet("bookmarks", emptySet()) ?: emptySet()

    fun addBookmark(name: String, path: String) {
        val set = bookmarks().toMutableSet()
        set += "$name|$path"
        prefs.edit().putStringSet("bookmarks", set).apply()
    }

    fun removeBookmark(name: String, path: String) {
        val set = bookmarks().toMutableSet()
        set -= "$name|$path"
        prefs.edit().putStringSet("bookmarks", set).apply()
    }
}
