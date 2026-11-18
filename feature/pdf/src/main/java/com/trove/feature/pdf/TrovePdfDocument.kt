package com.trove.feature.pdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.trove.core.fs.FileItem
import com.trove.core.fs.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Locale

data class PdfMetadata(
    val title: String?,
    val author: String?,
    val subject: String?,
    val creator: String?,
    val pageCount: Int
)

object TrovePdfDocument {

    private var initialized = false

    fun initialize(context: Context) {
        if (!initialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            initialized = true
        }
    }

    fun isPdf(fileName: String): Boolean {
        return fileName.lowercase(Locale.ROOT).endsWith(".pdf")
    }

    fun isPdf(fileItem: FileItem): Boolean {
        return !fileItem.isDirectory && isPdf(fileItem.name)
    }

    suspend fun getMetadata(fileItem: FileItem): Result<PdfMetadata> = withContext(Dispatchers.IO) {
        when (val inResult = fileItem.openInputStream()) {
            is Result.Failure -> Result.Failure(inResult.exception)
            is Result.Success -> {
                try {
                    inResult.value.use { stream ->
                        PDDocument.load(stream).use { doc ->
                            val info = doc.documentInformation
                            Result.Success(
                                PdfMetadata(
                                    title = info.title,
                                    author = info.author,
                                    subject = info.subject,
                                    creator = info.creator,
                                    pageCount = doc.numberOfPages
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Result.Failure(e)
                }
            }
        }
    }

    suspend fun extractText(
        fileItem: FileItem,
        startPage: Int = 1,
        endPage: Int = 1
    ): Result<String> = withContext(Dispatchers.IO) {
        when (val inResult = fileItem.openInputStream()) {
            is Result.Failure -> Result.Failure(inResult.exception)
            is Result.Success -> {
                try {
                    inResult.value.use { stream ->
                        PDDocument.load(stream).use { doc ->
                            val stripper = PDFTextStripper().apply {
                                this.startPage = startPage
                                this.endPage = endPage
                            }
                            val text = stripper.getText(doc)
                            Result.Success(text)
                        }
                    }
                } catch (e: Exception) {
                    Result.Failure(e)
                }
            }
        }
    }
}
