package com.trove.ui.browse

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import com.trove.core.fs.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Small on-disk-of-memory thumbnail cache keyed by path + mtime, with
 * downscaled decode (inSampleSize) for images and frame-0 extraction for
 * videos. All work happens on Dispatchers.IO; callers get null for anything
 * that is not an image/video or fails to decode.
 */
object ThumbnailCache {

    private const val TARGET_PX = 144

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(item: FileItem): Bitmap? = cache.get(key(item))

    suspend fun load(item: FileItem, isVideo: Boolean): Bitmap? = withContext(Dispatchers.IO) {
        val k = key(item)
        cache.get(k)?.let { return@withContext it }
        try {
            val bmp = if (isVideo) decodeVideoFrame(item) else decodeImage(item)
            if (bmp != null) cache.put(k, bmp)
            bmp
        } catch (_: Exception) {
            null
        }
    }

    private fun key(item: FileItem) = "${item.path}|${item.lastModified}"

    private suspend fun decodeImage(item: FileItem): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        item.openInputStream().getOrNull()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_PX && bounds.outHeight / (sample * 2) >= TARGET_PX) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return item.openInputStream().getOrNull()?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    private fun decodeVideoFrame(item: FileItem): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            // FileItem.openInputStream gives remote/archive support for free,
            // but MediaMetadataRetriever needs seekable input; only use the
            // direct path here.
            retriever.setDataSource(item.path)
            return retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            retriever.release()
        }
    }
}
