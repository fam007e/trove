package com.trove.feature.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.Locale

object ImageDecoderUtils {

    private val STANDARD_IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "jxl", "tif", "tiff"
    )

    private val RAW_IMAGE_EXTENSIONS = setOf(
        "cr2", "cr3", "nef", "arw", "dng", "orf", "rw2", "pef", "raf"
    )

    fun isImage(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return isStandardImage(ext) || isRawImage(ext)
    }

    fun isStandardImage(ext: String): Boolean = STANDARD_IMAGE_EXTENSIONS.contains(ext)

    fun isRawImage(ext: String): Boolean = RAW_IMAGE_EXTENSIONS.contains(ext)

    fun calculateInSampleSize(
        outWidth: Int,
        outHeight: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1
        if (outHeight > reqHeight || outWidth > reqWidth) {
            val halfHeight: Int = outHeight / 2
            val halfWidth: Int = outWidth / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    fun decodeSampledBitmap(
        inputStream: InputStream,
        reqWidth: Int,
        reqHeight: Int,
        pool: TroveBitmapPool? = TroveBitmapPool.shared,
        preferRgb565: Boolean = false
    ): Bitmap? {
        val buffered = if (inputStream is BufferedInputStream) inputStream else BufferedInputStream(inputStream)
        buffered.mark(1024 * 1024) // 1 MB mark buffer for bounds checking

        // First decode with inJustDecodeBounds=true to check dimensions
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeStream(buffered, null, options)
        buffered.reset()

        if (options.outWidth <= 0 || options.outHeight <= 0) {
            return null
        }

        // Calculate inSampleSize
        val sampleSize = calculateInSampleSize(options.outWidth, options.outHeight, reqWidth, reqHeight)
        val targetWidth = options.outWidth / sampleSize
        val targetHeight = options.outHeight / sampleSize
        val targetConfig = if (preferRgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888

        // Attempt inBitmap reuse from TroveBitmapPool
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = targetConfig
            inMutable = true
            pool?.get(targetWidth, targetHeight, targetConfig)?.let { reusableBitmap ->
                inBitmap = reusableBitmap
            }
        }

        return try {
            BitmapFactory.decodeStream(buffered, null, decodeOptions)
        } catch (_: IllegalArgumentException) {
            // inBitmap candidate rejected (e.g. edge-case dimension mismatch), retry without inBitmap
            buffered.reset()
            decodeOptions.inBitmap = null
            BitmapFactory.decodeStream(buffered, null, decodeOptions)
        }
    }

    fun correctOrientation(bitmap: Bitmap, inputStream: InputStream): Bitmap {
        return try {
            val exif = ExifInterface(inputStream)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                else -> return bitmap
            }

            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) {
                TroveBitmapPool.shared.put(bitmap)
            }
            rotated
        } catch (_: Exception) {
            bitmap
        }
    }
}
