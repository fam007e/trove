package com.trove.feature.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.trove.core.fs.FileItem
import com.trove.core.fs.LocalFileItem
import com.trove.core.fs.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * Thread-safe wrapper around Android's native [PdfRenderer].
 *
 * PEDAGOGICAL NOTE:
 * Android's native [PdfRenderer] operates directly through PDFium bindings in the framework.
 * Important constraints to honor:
 * 1. Only ONE page can be opened at a time per PdfRenderer instance.
 * 2. It requires a seekable file descriptor ([ParcelFileDescriptor.MODE_READ_ONLY]).
 * 3. Night Mode is rendered via high-performance hardware canvas color matrix inversion.
 */
class TrovePdfRenderer private constructor(
    private val pfd: ParcelFileDescriptor,
    private val tempFile: File? = null
) : Closeable {

    private val renderer: PdfRenderer = PdfRenderer(pfd)
    private val lock = Any()

    val pageCount: Int
        get() = synchronized(lock) { renderer.pageCount }

    /**
     * Renders a specific page into a Bitmap.
     */
    fun renderPage(
        pageIndex: Int,
        targetWidth: Int,
        targetHeight: Int,
        nightMode: Boolean = false
    ): Bitmap = synchronized(lock) {
        val page = renderer.openPage(pageIndex)
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)

        // Fill background with white before rendering
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        // Native render
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()

        if (nightMode) {
            applyNightModeInversion(bitmap)
        }

        return bitmap
    }

    fun getPageDimensions(pageIndex: Int): Pair<Int, Int> = synchronized(lock) {
        val page = renderer.openPage(pageIndex)
        val w = page.width
        val h = page.height
        page.close()
        Pair(w, h)
    }

    private fun applyNightModeInversion(bitmap: Bitmap) {
        val canvas = Canvas(bitmap)
        val paint = Paint()
        val invertMatrix = ColorMatrix(
            floatArrayOf(
                -1f,  0f,  0f, 0f, 255f,
                 0f, -1f,  0f, 0f, 255f,
                 0f,  0f, -1f, 0f, 255f,
                 0f,  0f,  0f, 1f,   0f
            )
        )
        paint.colorFilter = ColorMatrixColorFilter(invertMatrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
    }

    override fun close() {
        synchronized(lock) {
            try {
                renderer.close()
            } finally {
                try {
                    pfd.close()
                } finally {
                    tempFile?.delete()
                }
            }
        }
    }

    companion object {
        suspend fun open(fileItem: FileItem): Result<TrovePdfRenderer> = withContext(Dispatchers.IO) {
            try {
                if (fileItem is LocalFileItem) {
                    val file = File(fileItem.path)
                    if (file.exists() && file.isFile) {
                        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                        return@withContext Result.Success(TrovePdfRenderer(pfd))
                    }
                }

                // If remote or SAF, stage to a temporary cache file
                when (val inResult = fileItem.openInputStream()) {
                    is Result.Failure -> Result.Failure(inResult.exception)
                    is Result.Success -> {
                        val tempFile = File.createTempFile("trove_pdf_", ".pdf")
                        tempFile.deleteOnExit()
                        inResult.value.use { input ->
                            tempFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                        Result.Success(TrovePdfRenderer(pfd, tempFile))
                    }
                }
            } catch (e: Exception) {
                Result.Failure(e)
            }
        }
    }
}
