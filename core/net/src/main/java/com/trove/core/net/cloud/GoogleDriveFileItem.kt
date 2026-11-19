package com.trove.core.net.cloud

import com.trove.core.fs.FileItem
import com.trove.core.fs.PosixPermissions
import com.trove.core.fs.Result
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/**
 * Google Drive file/folder backed by the Drive REST API v3.
 *
 * PEDAGOGICAL NOTES:
 *
 * Google Drive is NOT a hierarchical filesystem — it's a graph. Files
 * have "parents" (array of folder IDs) but a file can live in multiple
 * folders. We present a tree view by treating each folder's children as
 * its listing and tracking the parent-chain via Drive file IDs.
 *
 * Key API endpoints:
 *   GET  /drive/v3/files?q=...             — list/search
 *   GET  /drive/v3/files/{id}?alt=media    — download content
 *   POST /upload/drive/v3/files            — upload
 *   PATCH /drive/v3/files/{id}             — rename/move
 *   DELETE /drive/v3/files/{id}            — trash/delete
 *
 * The `q` parameter uses Drive's query language:
 *   "'folderId' in parents and trashed=false"
 *
 * We use `alt=media` for downloads and multipart upload for new files.
 */
class GoogleDriveFileItem(
    private val tokenEngine: OAuthTokenEngine,
    private val fileId: String,
    override val name: String,
    override val path: String,
    override val size: Long,
    override val lastModified: Long,
    override val isDirectory: Boolean,
    override val mimeType: String
) : FileItem {

    companion object {
        private const val API_BASE = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val FIELDS = "files(id,name,mimeType,size,modifiedTime,parents)"

        /**
         * Create a root-level GoogleDriveFileItem representing "My Drive".
         */
        fun root(tokenEngine: OAuthTokenEngine): GoogleDriveFileItem {
            return GoogleDriveFileItem(
                tokenEngine = tokenEngine,
                fileId = "root",
                name = "My Drive",
                path = "/",
                size = 0,
                lastModified = System.currentTimeMillis(),
                isDirectory = true,
                mimeType = FOLDER_MIME
            )
        }
    }

    private val httpClient = HttpClient(OkHttp)

    override val permissions: PosixPermissions =
        if (isDirectory) PosixPermissions.DIRECTORY_DEFAULT else PosixPermissions.DEFAULT

    private suspend fun authHeader(): String {
        val token = tokenEngine.getValidAccessToken()
            ?: throw OAuthException("Not authenticated to Google Drive")
        return "Bearer $token"
    }

    override suspend fun listFiles(): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            val items = mutableListOf<FileItem>()
            var pageToken: String? = null

            do {
                val query = "'$fileId' in parents and trashed=false"
                val url = buildString {
                    append("$API_BASE/files?q=")
                    append(java.net.URLEncoder.encode(query, "UTF-8"))
                    append("&fields=nextPageToken,$FIELDS")
                    append("&pageSize=100")
                    append("&orderBy=folder,name")
                    if (pageToken != null) {
                        append("&pageToken=").append(pageToken)
                    }
                }

                val response = httpClient.get(url) {
                    header("Authorization", authHeader())
                }
                val json = JSONObject(response.bodyAsText())

                val files = json.optJSONArray("files") ?: JSONArray()
                for (i in 0 until files.length()) {
                    val file = files.getJSONObject(i)
                    items.add(fromJson(file))
                }

                pageToken = if (json.has("nextPageToken")) json.getString("nextPageToken") else null
            } while (!pageToken.isNullOrEmpty())

            Result.Success(items)
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun openInputStream(): Result<InputStream> = withContext(Dispatchers.IO) {
        try {
            if (isDirectory) {
                return@withContext Result.Failure(IllegalStateException("Cannot read a directory"))
            }

            // Google Docs/Sheets/Slides need export instead of download
            if (mimeType.startsWith("application/vnd.google-apps.")) {
                val exportMime = when (mimeType) {
                    "application/vnd.google-apps.document" -> "application/pdf"
                    "application/vnd.google-apps.spreadsheet" -> "text/csv"
                    "application/vnd.google-apps.presentation" -> "application/pdf"
                    else -> "application/pdf"
                }
                val url = "$API_BASE/files/$fileId/export?mimeType=${java.net.URLEncoder.encode(exportMime, "UTF-8")}"
                val response = httpClient.get(url) {
                    header("Authorization", authHeader())
                }
                Result.Success(response.bodyAsChannel().toInputStream())
            } else {
                val url = "$API_BASE/files/$fileId?alt=media"
                val response = httpClient.get(url) {
                    header("Authorization", authHeader())
                }
                Result.Success(response.bodyAsChannel().toInputStream())
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun openOutputStream(append: Boolean): Result<OutputStream> {
        // Drive API uses multipart upload; a streaming OutputStream is not
        // straightforward. For now, callers should use createFile + write.
        return Result.Failure(UnsupportedOperationException(
            "Google Drive uses upload API — use createFile() instead"
        ))
    }

    override suspend fun createFile(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val metadata = JSONObject().apply {
                put("name", name)
                put("parents", JSONArray().put(fileId))
            }

            val response = httpClient.post("$UPLOAD_BASE/files?uploadType=multipart") {
                header("Authorization", authHeader())
                contentType(ContentType.Application.Json)
                setBody(metadata.toString())
            }
            val json = JSONObject(response.bodyAsText())
            Result.Success(fromJson(json))
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun createDirectory(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val metadata = JSONObject().apply {
                put("name", name)
                put("mimeType", FOLDER_MIME)
                put("parents", JSONArray().put(fileId))
            }

            val response = httpClient.post("$API_BASE/files") {
                header("Authorization", authHeader())
                contentType(ContentType.Application.Json)
                setBody(metadata.toString())
            }
            val json = JSONObject(response.bodyAsText())
            Result.Success(fromJson(json))
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun delete(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            httpClient.delete("$API_BASE/files/$fileId") {
                header("Authorization", authHeader())
            }
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun rename(newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val metadata = JSONObject().apply { put("name", newName) }
            val response = httpClient.patch("$API_BASE/files/$fileId") {
                header("Authorization", authHeader())
                contentType(ContentType.Application.Json)
                setBody(metadata.toString())
            }
            val json = JSONObject(response.bodyAsText())
            Result.Success(fromJson(json))
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun copyTo(dest: FileItem): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            if (dest !is GoogleDriveFileItem) {
                // Cross-backend copy: download + upload to dest
                val input = openInputStream()
                if (input is Result.Failure) return@withContext Result.Failure(input.exception)
                val created = dest.createFile(name)
                if (created is Result.Failure) return@withContext Result.Failure(created.exception)
                val os = (created as Result.Success).value.openOutputStream()
                if (os is Result.Failure) return@withContext Result.Failure(os.exception)
                (input as Result.Success).value.use { i ->
                    (os as Result.Success).value.use { o -> i.copyTo(o) }
                }
                return@withContext created
            }

            // Same-backend copy via Drive API
            val metadata = JSONObject().apply {
                put("name", name)
                put("parents", JSONArray().put(dest.fileId))
            }
            val response = httpClient.post("$API_BASE/files/$fileId/copy") {
                header("Authorization", authHeader())
                contentType(ContentType.Application.Json)
                setBody(metadata.toString())
            }
            val json = JSONObject(response.bodyAsText())
            Result.Success(fromJson(json))
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun moveTo(destDir: FileItem, newName: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            try {
                if (destDir !is GoogleDriveFileItem) {
                    // Cross-backend: copy then delete source
                    val copied = copyTo(destDir)
                    if (copied is Result.Success) delete()
                    return@withContext copied
                }

                // Same-backend move via Drive API: change parents
                val currentParents = path.substringBeforeLast('/').ifEmpty { "root" }
                val url = "$API_BASE/files/$fileId?addParents=${destDir.fileId}&removeParents=$currentParents"
                val metadata = JSONObject().apply { put("name", newName) }
                val response = httpClient.patch(url) {
                    header("Authorization", authHeader())
                    contentType(ContentType.Application.Json)
                    setBody(metadata.toString())
                }
                val json = JSONObject(response.bodyAsText())
                Result.Success(fromJson(json))
            } catch (e: Exception) {
                Result.Failure(e)
            }
        }

    override suspend fun findChild(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val query = "'$fileId' in parents and name='${name.replace("'", "\\'")}' and trashed=false"
            val url = "$API_BASE/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&fields=$FIELDS&pageSize=1"
            val response = httpClient.get(url) {
                header("Authorization", authHeader())
            }
            val json = JSONObject(response.bodyAsText())
            val files = json.optJSONArray("files")
            if (files == null || files.length() == 0) {
                Result.Failure(java.io.FileNotFoundException("Child not found: $name"))
            } else {
                Result.Success(fromJson(files.getJSONObject(0)))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun getParent(): Result<FileItem?> {
        // Would need to query the file metadata for parents array
        // For now, return null (top-level)
        return Result.Success(null)
    }

    override suspend fun exists(): Boolean {
        return try {
            val response = httpClient.get("$API_BASE/files/$fileId?fields=id,trashed") {
                header("Authorization", authHeader())
            }
            val json = JSONObject(response.bodyAsText())
            !json.optBoolean("trashed", false)
        } catch (_: Exception) {
            false
        }
    }

    private fun fromJson(json: JSONObject): GoogleDriveFileItem {
        val id = json.getString("id")
        val itemName = json.optString("name", "")
        val itemMime = json.optString("mimeType", "application/octet-stream")
        val itemSize = json.optLong("size", 0)
        val modifiedStr = json.optString("modifiedTime", "")
        val modified = try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .parse(modifiedStr)?.time ?: 0L
        } catch (_: Exception) { 0L }

        return GoogleDriveFileItem(
            tokenEngine = tokenEngine,
            fileId = id,
            name = itemName,
            path = "$path$itemName${if (itemMime == FOLDER_MIME) "/" else ""}",
            size = itemSize,
            lastModified = modified,
            isDirectory = itemMime == FOLDER_MIME,
            mimeType = itemMime
        )
    }
}
