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
import io.ktor.client.request.put
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
 * Microsoft OneDrive file/folder backed by Microsoft Graph API v1.0.
 * Uses OAuth 2.0 PKCE authentication.
 */
class OneDriveFileItem(
    private val tokenEngine: OAuthTokenEngine,
    val itemId: String,
    override val name: String,
    override val path: String,
    override val size: Long,
    override val lastModified: Long,
    override val isDirectory: Boolean,
    override val mimeType: String = if (isDirectory) "inode/directory" else "application/octet-stream"
) : FileItem {

    companion object {
        private const val GRAPH_BASE = "https://graph.microsoft.com/v1.0/me/drive"

        fun root(tokenEngine: OAuthTokenEngine): OneDriveFileItem {
            return OneDriveFileItem(
                tokenEngine = tokenEngine,
                itemId = "root",
                name = "OneDrive",
                path = "/",
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
            ?: throw OAuthException("Not authenticated to OneDrive")
        return "Bearer $token"
    }

    override suspend fun listFiles(): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            val items = mutableListOf<FileItem>()
            var nextUrl: String? = "$GRAPH_BASE/items/$itemId/children?\$top=100"

            while (nextUrl != null) {
                val response = httpClient.get(nextUrl) {
                    header("Authorization", authHeader())
                }
                val json = JSONObject(response.bodyAsText())
                val value = json.optJSONArray("value") ?: JSONArray()
                for (i in 0 until value.length()) {
                    val entry = value.getJSONObject(i)
                    items.add(fromJson(entry))
                }
                nextUrl = if (json.has("@odata.nextLink")) json.getString("@odata.nextLink") else null
            }

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

            val response = httpClient.get("$GRAPH_BASE/items/$itemId/content") {
                header("Authorization", authHeader())
            }
            Result.Success(response.bodyAsChannel().toInputStream())
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun openOutputStream(append: Boolean): Result<OutputStream> {
        return Result.Failure(UnsupportedOperationException("OneDrive uses upload API — use createFile()"))
    }

    override suspend fun createFile(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val encodedName = java.net.URLEncoder.encode(name, "UTF-8")
            val response = httpClient.put("$GRAPH_BASE/items/$itemId:/$encodedName:/content") {
                header("Authorization", authHeader())
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
            val body = JSONObject().apply {
                put("name", name)
                put("folder", JSONObject())
                put("@microsoft.graph.conflictBehavior", "rename")
            }

            val response = httpClient.post("$GRAPH_BASE/items/$itemId/children") {
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

    override suspend fun delete(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            httpClient.delete("$GRAPH_BASE/items/$itemId") {
                header("Authorization", authHeader())
            }
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun rename(newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply { put("name", newName) }
            val response = httpClient.patch("$GRAPH_BASE/items/$itemId") {
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

    override suspend fun copyTo(dest: FileItem): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            if (dest !is OneDriveFileItem) {
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

            val body = JSONObject().apply {
                put("parentReference", JSONObject().apply { put("id", dest.itemId) })
                put("name", name)
            }
            val response = httpClient.post("$GRAPH_BASE/items/$itemId/copy") {
                header("Authorization", authHeader())
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            if (response.status.value in 200..299) {
                // Returns 202 Accepted with monitor URL in async copy; construct target placeholder
                Result.Success(
                    OneDriveFileItem(
                        tokenEngine = tokenEngine,
                        itemId = itemId,
                        name = name,
                        path = "${dest.path}/$name",
                        size = size,
                        lastModified = System.currentTimeMillis(),
                        isDirectory = isDirectory
                    )
                )
            } else {
                Result.Failure(Exception("Copy failed with HTTP ${response.status.value}"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun moveTo(destDir: FileItem, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            if (destDir !is OneDriveFileItem) {
                val copied = copyTo(destDir)
                if (copied is Result.Success) delete()
                return@withContext copied
            }

            val body = JSONObject().apply {
                put("parentReference", JSONObject().apply { put("id", destDir.itemId) })
                put("name", newName)
            }
            val response = httpClient.patch("$GRAPH_BASE/items/$itemId") {
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

    override suspend fun findChild(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val encoded = java.net.URLEncoder.encode(name, "UTF-8")
            val response = httpClient.get("$GRAPH_BASE/items/$itemId:/$encoded") {
                header("Authorization", authHeader())
            }
            val json = JSONObject(response.bodyAsText())
            Result.Success(fromJson(json))
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun getParent(): Result<FileItem?> {
        return Result.Success(null)
    }

    override suspend fun exists(): Boolean {
        return try {
            val response = httpClient.get("$GRAPH_BASE/items/$itemId?\$select=id") {
                header("Authorization", authHeader())
            }
            response.status.value in 200..299
        } catch (_: Exception) {
            false
        }
    }

    private fun fromJson(json: JSONObject): OneDriveFileItem {
        val id = json.getString("id")
        val itemName = json.getString("name")
        val isDir = json.has("folder")
        val itemSize = json.optLong("size", 0L)
        val modifiedStr = json.optString("lastModifiedDateTime", "")
        val modified = try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .parse(modifiedStr)?.time ?: 0L
        } catch (_: Exception) { 0L }

        val mime = if (isDir) "inode/directory" else json.optJSONObject("file")?.optString("mimeType", "application/octet-stream") ?: "application/octet-stream"

        return OneDriveFileItem(
            tokenEngine = tokenEngine,
            itemId = id,
            name = itemName,
            path = "$path$itemName${if (isDir) "/" else ""}",
            size = itemSize,
            lastModified = modified,
            isDirectory = isDir,
            mimeType = mime
        )
    }
}
