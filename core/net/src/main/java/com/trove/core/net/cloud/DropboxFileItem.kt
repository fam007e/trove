package com.trove.core.net.cloud

import com.trove.core.fs.FileItem
import com.trove.core.fs.PosixPermissions
import com.trove.core.fs.Result
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
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
 * Dropbox file/folder backed by Dropbox API v2.
 * Uses OAuth 2.0 PKCE with short-lived tokens and refresh tokens.
 *
 * Key endpoints:
 *   POST https://api.dropboxapi.com/2/files/list_folder
 *   POST https://content.dropboxapi.com/2/files/download
 *   POST https://content.dropboxapi.com/2/files/upload
 *   POST https://api.dropboxapi.com/2/files/delete_v2
 *   POST https://api.dropboxapi.com/2/files/move_v2
 *   POST https://api.dropboxapi.com/2/files/copy_v2
 *   POST https://api.dropboxapi.com/2/files/create_folder_v2
 */
class DropboxFileItem(
    private val tokenEngine: OAuthTokenEngine,
    override val name: String,
    override val path: String,
    override val size: Long,
    override val lastModified: Long,
    override val isDirectory: Boolean,
    override val mimeType: String = if (isDirectory) "inode/directory" else "application/octet-stream"
) : FileItem {

    companion object {
        private const val RPC_BASE = "https://api.dropboxapi.com/2/files"
        private const val CONTENT_BASE = "https://content.dropboxapi.com/2/files"

        fun root(tokenEngine: OAuthTokenEngine): DropboxFileItem {
            return DropboxFileItem(
                tokenEngine = tokenEngine,
                name = "Dropbox",
                path = "",
                size = 0,
                lastModified = System.currentTimeMillis(),
                isDirectory = true,
                mimeType = "inode/directory"
            )
        }
    }

    private val httpClient = HttpClient(OkHttp)

    override val permissions: PosixPermissions =
        if (isDirectory) PosixPermissions.DIRECTORY_DEFAULT else PosixPermissions.DEFAULT

    private suspend fun authHeader(): String {
        val token = tokenEngine.getValidAccessToken()
            ?: throw OAuthException("Not authenticated to Dropbox")
        return "Bearer $token"
    }

    override suspend fun listFiles(): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            val items = mutableListOf<FileItem>()
            var cursor: String? = null
            var hasMore: Boolean

            do {
                val url = if (cursor == null) "$RPC_BASE/list_folder" else "$RPC_BASE/list_folder/continue"
                val body = JSONObject().apply {
                    if (cursor == null) {
                        put("path", path)
                        put("recursive", false)
                        put("include_deleted", false)
                    } else {
                        put("cursor", cursor)
                    }
                }

                val response = httpClient.post(url) {
                    header("Authorization", authHeader())
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
                val json = JSONObject(response.bodyAsText())
                val entries = json.optJSONArray("entries") ?: JSONArray()
                for (i in 0 until entries.length()) {
                    val entry = entries.getJSONObject(i)
                    items.add(fromJson(entry))
                }

                hasMore = json.optBoolean("has_more", false)
                cursor = if (json.has("cursor")) json.getString("cursor") else null
            } while (hasMore && cursor != null)

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

            val arg = JSONObject().apply { put("path", path) }.toString()
            val response = httpClient.post("$CONTENT_BASE/download") {
                header("Authorization", authHeader())
                header("Dropbox-API-Arg", arg)
            }
            Result.Success(response.bodyAsChannel().toInputStream())
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun openOutputStream(append: Boolean): Result<OutputStream> {
        return Result.Failure(UnsupportedOperationException("Dropbox uses upload API — use createFile()"))
    }

    override suspend fun createFile(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val targetPath = if (path.isEmpty()) "/$name" else "$path/$name"
            val arg = JSONObject().apply {
                put("path", targetPath)
                put("mode", "add")
                put("autorename", true)
                put("mute", false)
            }.toString()

            val response = httpClient.post("$CONTENT_BASE/upload") {
                header("Authorization", authHeader())
                header("Dropbox-API-Arg", arg)
                contentType(ContentType.Application.OctetStream)
                setBody(ByteArray(0))
            }
            val json = JSONObject(response.bodyAsText())
            Result.Success(fromJson(json))
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun createDirectory(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val targetPath = if (path.isEmpty()) "/$name" else "$path/$name"
            val body = JSONObject().apply {
                put("path", targetPath)
                put("autorename", false)
            }

            val response = httpClient.post("$RPC_BASE/create_folder_v2") {
                header("Authorization", authHeader())
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            val json = JSONObject(response.bodyAsText())
            val metadata = json.getJSONObject("metadata")
            Result.Success(fromJson(metadata))
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun delete(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply { put("path", path) }
            httpClient.post("$RPC_BASE/delete_v2") {
                header("Authorization", authHeader())
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun rename(newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val parentPath = path.substringBeforeLast('/', "")
            val targetPath = if (parentPath.isEmpty()) "/$newName" else "$parentPath/$newName"
            val body = JSONObject().apply {
                put("from_path", path)
                put("to_path", targetPath)
                put("autorename", false)
            }

            val response = httpClient.post("$RPC_BASE/move_v2") {
                header("Authorization", authHeader())
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            val json = JSONObject(response.bodyAsText())
            val metadata = json.getJSONObject("metadata")
            Result.Success(fromJson(metadata))
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun copyTo(dest: FileItem): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            if (dest !is DropboxFileItem) {
                // Cross-backend transfer
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

            val targetPath = if (dest.path.isEmpty()) "/$name" else "${dest.path}/$name"
            val body = JSONObject().apply {
                put("from_path", path)
                put("to_path", targetPath)
                put("autorename", true)
            }

            val response = httpClient.post("$RPC_BASE/copy_v2") {
                header("Authorization", authHeader())
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            val json = JSONObject(response.bodyAsText())
            val metadata = json.getJSONObject("metadata")
            Result.Success(fromJson(metadata))
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun moveTo(destDir: FileItem, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            if (destDir !is DropboxFileItem) {
                val copied = copyTo(destDir)
                if (copied is Result.Success) delete()
                return@withContext copied
            }

            val targetPath = if (destDir.path.isEmpty()) "/$newName" else "${destDir.path}/$newName"
            val body = JSONObject().apply {
                put("from_path", path)
                put("to_path", targetPath)
                put("autorename", false)
            }

            val response = httpClient.post("$RPC_BASE/move_v2") {
                header("Authorization", authHeader())
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            val json = JSONObject(response.bodyAsText())
            val metadata = json.getJSONObject("metadata")
            Result.Success(fromJson(metadata))
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun findChild(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val targetPath = if (path.isEmpty()) "/$name" else "$path/$name"
            val body = JSONObject().apply { put("path", targetPath) }
            val response = httpClient.post("$RPC_BASE/get_metadata") {
                header("Authorization", authHeader())
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            val json = JSONObject(response.bodyAsText())
            Result.Success(fromJson(json))
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun getParent(): Result<FileItem?> {
        val parentPath = path.substringBeforeLast('/', "")
        if (parentPath.isEmpty() && path.isNotEmpty()) {
            return Result.Success(root(tokenEngine))
        }
        return Result.Success(null)
    }

    override suspend fun exists(): Boolean {
        return try {
            val body = JSONObject().apply { put("path", path) }
            val response = httpClient.post("$RPC_BASE/get_metadata") {
                header("Authorization", authHeader())
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            response.status.value in 200..299
        } catch (_: Exception) {
            false
        }
    }

    private fun fromJson(json: JSONObject): DropboxFileItem {
        val tag = json.optString(".tag", "file")
        val itemName = json.getString("name")
        val itemPath = json.optString("path_display", json.optString("path_lower", "/$itemName"))
        val isDir = tag == "folder"
        val itemSize = if (isDir) 0L else json.optLong("size", 0L)
        val modifiedStr = json.optString("client_modified", "")
        val modified = try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .parse(modifiedStr)?.time ?: 0L
        } catch (_: Exception) { 0L }

        return DropboxFileItem(
            tokenEngine = tokenEngine,
            name = itemName,
            path = itemPath,
            size = itemSize,
            lastModified = modified,
            isDirectory = isDir
        )
    }
}
