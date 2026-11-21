package com.trove.ui.browse

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.trove.core.fs.FileItem
import com.trove.core.fs.Result
import com.trove.core.fs.PosixPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * MiX-style virtual category views ("content://mix/audio" equivalents):
 * MediaStore-backed listings of a media type — Audio, Video, Images,
 * Documents, APKs — as a browsable FileItem tree. Works across any backend
 * ops the ContentResolver supports (open, delete); everything else reports
 * unsupported instead of pretending.
 */
class MediaStoreFileItem private constructor(
    private val context: Context,
    private val collection: Uri,
    override val name: String,
    override val path: String,
    override val size: Long,
    override val lastModified: Long,
    override val isDirectory: Boolean,
    private val entryUri: Uri?,
    private val selection: String? = null
) : FileItem {

    override val permissions: PosixPermissions
        get() = PosixPermissions.DIRECTORY_DEFAULT

    override val mimeType: String
        get() = if (isDirectory) "inode/directory" else "application/octet-stream"

    override suspend fun listFiles(): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED
            )
            val out = mutableListOf<FileItem>()
            context.contentResolver.query(collection, projection, selection, null, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, c.getLong(idCol))
                    out += MediaStoreFileItem(
                        context, collection,
                        name = c.getString(nameCol) ?: "?",
                        path = uri.toString(),
                        size = c.getLong(sizeCol),
                        lastModified = c.getLong(dateCol) * 1000L,
                        isDirectory = false,
                        entryUri = uri
                    )
                }
            }
            Result.Success(out)
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun openInputStream(): Result<InputStream> = withContext(Dispatchers.IO) {
        try {
            val uri = entryUri ?: return@withContext Result.Failure(
                IllegalStateException("Category nodes have no stream")
            )
            Result.Success(context.contentResolver.openInputStream(uri)!!)
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun openOutputStream(append: Boolean): Result<java.io.OutputStream> =
        Result.Failure(UnsupportedOperationException("MediaStore categories are read-only"))

    override suspend fun createFile(name: String): Result<FileItem> =
        Result.Failure(UnsupportedOperationException("MediaStore categories are read-only"))

    override suspend fun createDirectory(name: String): Result<FileItem> =
        Result.Failure(UnsupportedOperationException("MediaStore categories are read-only"))

    override suspend fun delete(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val uri = entryUri ?: return@withContext Result.Failure(
                IllegalStateException("Category nodes have no entry")
            )
            val n = context.contentResolver.delete(uri, null, null)
            if (n > 0) Result.Success(Unit) else Result.Failure(java.io.IOException("Delete failed"))
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun rename(newName: String): Result<FileItem> =
        Result.Failure(UnsupportedOperationException("Rename via MediaStore not supported yet"))

    override suspend fun copyTo(dest: FileItem): Result<FileItem> {
        // Stream copy works over any backend destination via the generic path.
        return when (val input = openInputStream()) {
            is com.trove.core.fs.Result.Success -> {
                val target = when (val t = dest.createFile(name)) {
                    is com.trove.core.fs.Result.Success -> t.value
                    is com.trove.core.fs.Result.Failure -> return com.trove.core.fs.Result.Failure(t.exception)
                }
                try {
                    (input as com.trove.core.fs.Result.Success).value.use { i ->
                        when (val out = target.openOutputStream(false)) {
                            is com.trove.core.fs.Result.Success -> {
                                (out as com.trove.core.fs.Result.Success).value.use { o -> i.copyTo(o) }
                                com.trove.core.fs.Result.Success(target)
                            }
                            is com.trove.core.fs.Result.Failure -> com.trove.core.fs.Result.Failure(
                                (out as com.trove.core.fs.Result.Failure).exception
                            )
                        }
                    }
                } catch (e: Exception) {
                    com.trove.core.fs.Result.Failure(e)
                }
            }
            is com.trove.core.fs.Result.Failure -> com.trove.core.fs.Result.Failure(input.exception)
        }
    }

    override suspend fun moveTo(destDir: FileItem, newName: String): Result<FileItem> =
        Result.Failure(UnsupportedOperationException("Move from MediaStore categories not supported"))

    override suspend fun findChild(name: String): Result<FileItem> =
        Result.Failure(UnsupportedOperationException("findChild not supported on categories"))

    override suspend fun getParent(): Result<FileItem?> = Result.Success(null)

    override suspend fun exists(): Boolean = true

    companion object {
        /** Category roots — the "content://mix/audio" equivalents. */
        fun categories(context: Context): List<MediaStoreFileItem> = listOf(
            of(context, "Audio", MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null),
            of(context, "Video", MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null),
            of(context, "Images", MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null),
            of(context, "Documents", MediaStore.Files.getContentUri("external"),
                "(mime_type LIKE 'text/%' OR mime_type LIKE 'application/pdf' " +
                "OR mime_type LIKE 'application/msword%' OR mime_type LIKE 'application/vnd.openxmlformats-officedocument%' " +
                "OR mime_type LIKE 'application/rtf' OR mime_type LIKE 'application/vnd.ms-excel%')")
        )

        private fun of(context: Context, name: String, collection: Uri, selection: String?) = MediaStoreFileItem(
            context, collection, name, path = "content://trove/$name",
            size = 0, lastModified = 0, isDirectory = true, entryUri = null, selection = selection
        )

        /** Aggregated count + total bytes for the dashboard cards. */
        suspend fun stats(context: Context, collection: Uri, selection: String? = null): Pair<Int, Long> =
            withContext(Dispatchers.IO) {
                var count = 0
                var bytes = 0L
                try {
                    val projection = arrayOf(
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.SIZE
                    )
                    context.contentResolver.query(collection, projection, selection, null, null)?.use { c ->
                        val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                        while (c.moveToNext()) {
                            count++
                            bytes += c.getLong(sizeCol)
                        }
                    }
                } catch (_: Exception) { }
                count to bytes
            }
    }
}
