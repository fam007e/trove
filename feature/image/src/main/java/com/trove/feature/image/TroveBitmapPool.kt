package com.trove.feature.image

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import java.util.NavigableMap
import java.util.TreeMap

/**
 * High-performance, thread-safe Bitmap pool for Trove.
 *
 * PEDAGOGICAL NOTE:
 * Frequent allocation and deallocation of large Bitmaps is the #1 cause of
 * OutOfMemoryError and GC stutter on Android.
 * This pool uses [BitmapFactory.Options.inBitmap] compatibility:
 * on KitKat (API 19)+, an existing mutable Bitmap can be reused for decoding any
 * smaller or equal-sized image as long as byte count allows.
 */
class TroveBitmapPool(
    val maxSizeBytes: Long = (Runtime.getRuntime().maxMemory() * 0.15).toLong()
) {
    private val TAG = "TroveBitmapPool"
    private var currentSizeBytes: Long = 0L

    // Ordered map of allocation byte size -> List of reusable bitmaps
    private val pooledBitmaps: NavigableMap<Int, MutableList<Bitmap>> = TreeMap()

    @Synchronized
    fun get(width: Int, height: Int, config: Bitmap.Config): Bitmap? {
        val bytesNeeded = getBytesPerPixel(config) * width * height
        // Find the smallest bitmap in the pool that is >= bytesNeeded
        val ceilingKey = pooledBitmaps.ceilingKey(bytesNeeded) ?: return null
        val candidates = pooledBitmaps[ceilingKey] ?: return null

        if (candidates.isEmpty()) return null
        val bitmap = candidates.removeAt(candidates.size - 1)
        if (candidates.isEmpty()) {
            pooledBitmaps.remove(ceilingKey)
        }

        currentSizeBytes -= bitmap.allocationByteCountCompat()
        bitmap.reconfigure(width, height, config)
        return bitmap
    }

    @Synchronized
    fun put(bitmap: Bitmap) {
        if (!bitmap.isMutable || bitmap.isRecycled) {
            return
        }

        val size = bitmap.allocationByteCountCompat()
        if (size > maxSizeBytes) {
            bitmap.recycle()
            return
        }

        trimToSize(maxSizeBytes - size)

        val list = pooledBitmaps.getOrPut(size) { mutableListOf() }
        list.add(bitmap)
        currentSizeBytes += size
    }

    @Synchronized
    fun clear() {
        trimToSize(0L)
    }

    @Synchronized
    fun getCurrentSizeBytes(): Long = currentSizeBytes

    @Synchronized
    private fun trimToSize(targetSize: Long) {
        while (currentSizeBytes > targetSize && pooledBitmaps.isNotEmpty()) {
            val largestKey = pooledBitmaps.lastKey()
            val candidates = pooledBitmaps[largestKey]
            if (!candidates.isNullOrEmpty()) {
                val evicted = candidates.removeAt(0)
                currentSizeBytes -= evicted.allocationByteCountCompat()
                evicted.recycle()
            }
            if (candidates.isNullOrEmpty()) {
                pooledBitmaps.remove(largestKey)
            }
        }
    }

    private fun Bitmap.allocationByteCountCompat(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            allocationByteCount
        } else {
            byteCount
        }
    }

    private fun getBytesPerPixel(config: Bitmap.Config): Int {
        return when (config) {
            Bitmap.Config.ARGB_8888 -> 4
            Bitmap.Config.RGB_565, Bitmap.Config.ARGB_4444 -> 2
            Bitmap.Config.ALPHA_8 -> 1
            else -> 4
        }
    }

    companion object {
        val shared by lazy { TroveBitmapPool() }
    }
}
