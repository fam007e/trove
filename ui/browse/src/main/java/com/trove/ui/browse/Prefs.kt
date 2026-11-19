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

    var dualPane: Boolean
        get() = prefs.getBoolean("dual_pane", false)
        set(v) = prefs.edit().putBoolean("dual_pane", v).apply()

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

    /** Saved network connections as "PROTOCOL|host|port|user|password|path". */
    fun savedConnections(): Set<String> =
        prefs.getStringSet("saved_connections", emptySet()) ?: emptySet()

    fun addSavedConnection(spec: com.trove.core.net.NetworkConnectionFactory.ConnectionSpec) {
        val entry = "${spec.protocol.name}|${spec.host}|${spec.port}|${spec.user}|${spec.password}|${spec.path}"
        val set = savedConnections().toMutableSet()
        set += entry
        prefs.edit().putStringSet("saved_connections", set).apply()
    }
}
