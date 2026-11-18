package com.trove.feature.media

import android.net.Uri
import com.trove.core.fs.FileItem
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe registry that holds active [FileItem] references during media playback.
 * 
 * Since [FileItem] implementations contain network sessions or open channels that cannot
 * be serialized into Intent extras, this registry bridges Android's Intent/Uri system
 * and Trove's VFS layer.
 */
object TroveMediaRegistry {

    private val registry = ConcurrentHashMap<String, FileItem>()

    /**
     * Register a [FileItem] and return its custom `trove://media` URI.
     */
    fun register(item: FileItem): Uri? {
        registry[item.path] = item
        return try {
            Uri.Builder()
                .scheme("trove")
                .authority("media")
                .appendQueryParameter("path", item.path)
                .build()
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Retrieve a registered [FileItem] by its path.
     */
    fun get(path: String): FileItem? = registry[path]

    /**
     * Retrieve a registered [FileItem] from a `trove://media` URI.
     */
    fun get(uri: Uri): FileItem? {
        val path = uri.getQueryParameter("path") ?: uri.path ?: return null
        return registry[path]
    }

    /**
     * Unregister an item when no longer needed.
     */
    fun unregister(path: String) {
        registry.remove(path)
    }

    /**
     * Clear all registered items.
     */
    fun clear() {
        registry.clear()
    }
}
