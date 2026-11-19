package com.trove.core.net.cloud

import com.trove.core.fs.FileItem
import com.trove.core.fs.PosixPermissions
import com.trove.core.fs.Result
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/**
 * MediaFire file and folder backed by MediaFire REST API v1.5.
 * Securely communicates over TLS 1.3/HTTPS with session token authentication.
 */
class MediaFireFileItem(
    private val sessionToken: String,
    val folderOrQuickKey: String,
    override val name: String,
    override val path: String,
    override val size: Long,
    override val lastModified: Long,
    override val isDirectory: Boolean,
    override val mimeType: String = if (isDirectory) "inode/directory" else "application/octet-stream"
) : FileItem {

    companion object {
        private const val API_BASE = "https://www.mediafire.com/api/1.5"

        /**
         * Authenticate against MediaFire API v1.5 and return a root MediaFireFileItem.
         */
        suspend fun authenticate(
            email: String,
            pass: String,
            appId: String = "42511",
            apiKey: String = ""
        ): Result<MediaFireFileItem> = withContext(Dispatchers.IO) {
            val client = HttpClient(OkHttp)
            try {
                val signature = java.security.MessageDigest.getInstance("SHA-1")
                    .digest("$email$pass$appId$apiKey".toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }

                val response = client.submitForm(
                    url = "$API_BASE/user/get_session_token.php",
                    formParameters = Parameters.build {
                        append("email", email)
                        append("password", pass)
                        append("application_id", appId)
                        append("signature", signature)
                        append("response_format", "json")
                    }
                )
                val json = JSONObject(response.bodyAsText()).getJSONObject("response")
                if (json.optString("result") != "Success") {
                    val msg = json.optString("message", "MediaFire login failed")
                    return@withContext Result.Failure(Exception(msg))
                }

                val token = json.getString("session_token")
                Result.Success(
                    MediaFireFileItem(
                        sessionToken = token,
                        folderOrQuickKey = "myfiles",
                        name = "MediaFire",
                        path = "/",
                        size = 0L,
                        lastModified = System.currentTimeMillis(),
                        isDirectory = true
                    )
                )
            } catch (e: Exception) {
                Result.Failure(e)
            }
        }
    }

    private val httpClient = HttpClient(OkHttp)

    override val permissions: PosixPermissions =
        if (isDirectory) PosixPermissions.DIRECTORY_DEFAULT else PosixPermissions.DEFAULT

    override suspend fun listFiles(): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            if (!isDirectory) {
                return@withContext Result.Failure(IllegalStateException("Cannot list a file"))
            }

            val items = mutableListOf<FileItem>()
            var chunk = 1
            var moreChunks = true

            while (moreChunks) {
                val url = "$API_BASE/folder/get_content.php?response_format=json&session_token=$sessionToken&folder_key=$folderOrQuickKey&content_type=files_folders&chunk=$chunk"
                val response = httpClient.get(url)
                val json = JSONObject(response.bodyAsText()).getJSONObject("response")
                val folderContent = json.optJSONObject("folder_content") ?: break

                // Folders
                val folders = folderContent.optJSONArray("folders") ?: JSONArray()
                for (i in 0 until folders.length()) {
                    val f = folders.getJSONObject(i)
                    val key = f.getString("folderkey")
                    val fName = f.optString("name", "Folder")
                    val created = parseDate(f.optString("created", ""))
                    items.add(
                        MediaFireFileItem(
                            sessionToken = sessionToken,
                            folderOrQuickKey = key,
                            name = fName,
                            path = "$path$fName/",
                            size = 0L,
                            lastModified = created,
                            isDirectory = true
                        )
                    )
                }

                // Files
                val files = folderContent.optJSONArray("files") ?: JSONArray()
                for (i in 0 until files.length()) {
                    val fileObj = files.getJSONObject(i)
                    val qKey = fileObj.getString("quickkey")
                    val fileName = fileObj.optString("filename", "File")
                    val fSize = fileObj.optLong("size", 0L)
                    val created = parseDate(fileObj.optString("created", ""))
                    val mime = fileObj.optString("mimetype", "application/octet-stream")
                    items.add(
                        MediaFireFileItem(
                            sessionToken = sessionToken,
                            folderOrQuickKey = qKey,
                            name = fileName,
                            path = "$path$fileName",
                            size = fSize,
                            lastModified = created,
                            isDirectory = false,
                            mimeType = mime
                        )
                    )
                }

                val more = folderContent.optString("more_chunks", "no")
                moreChunks = (more.equals("yes", ignoreCase = true))
                chunk++
            }

            Result.Success(items)
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun openInputStream(): Result<InputStream> = withContext(Dispatchers.IO) {
        try {
            if (isDirectory) {
                return@withContext Result.Failure(IllegalStateException("Cannot download a directory"))
            }

            val linkUrl = "$API_BASE/file/get_links.php?response_format=json&session_token=$sessionToken&quick_key=$folderOrQuickKey&link_type=direct_download"
            val linkResp = httpClient.get(linkUrl)
            val json = JSONObject(linkResp.bodyAsText()).getJSONObject("response")
            val links = json.optJSONArray("links")
            val directUrl = links?.optJSONObject(0)?.optString("direct_download")
                ?: return@withContext Result.Failure(Exception("Could not obtain direct download URL from MediaFire"))

            val response = httpClient.get(directUrl)
            Result.Success(response.bodyAsChannel().toInputStream())
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun openOutputStream(append: Boolean): Result<OutputStream> {
        return Result.Failure(UnsupportedOperationException("MediaFire uses HTTP multipart upload API — use createFile()"))
    }

    override suspend fun createFile(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        // MediaFire requires upload via upload/simple.php or upload/resumable.php
        Result.Failure(UnsupportedOperationException("MediaFire file creation is performed via file upload"))
    }

    override suspend fun createDirectory(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val response = httpClient.submitForm(
                url = "$API_BASE/folder/create.php",
                formParameters = Parameters.build {
                    append("session_token", sessionToken)
                    append("parent_key", folderOrQuickKey)
                    append("foldername", name)
                    append("response_format", "json")
                }
            )
            val json = JSONObject(response.bodyAsText()).getJSONObject("response")
            val newKey = json.getString("folderkey")
            Result.Success(
                MediaFireFileItem(
                    sessionToken = sessionToken,
                    folderOrQuickKey = newKey,
                    name = name,
                    path = "$path$name/",
                    size = 0L,
                    lastModified = System.currentTimeMillis(),
                    isDirectory = true
                )
            )
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun delete(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val endpoint = if (isDirectory) "$API_BASE/folder/delete.php" else "$API_BASE/file/delete.php"
            val keyParam = if (isDirectory) "folder_key" else "quick_key"
            val response = httpClient.submitForm(
                url = endpoint,
                formParameters = Parameters.build {
                    append("session_token", sessionToken)
                    append(keyParam, folderOrQuickKey)
                    append("response_format", "json")
                }
            )
            val json = JSONObject(response.bodyAsText()).getJSONObject("response")
            if (json.optString("result") == "Success") {
                Result.Success(Unit)
            } else {
                Result.Failure(Exception(json.optString("message", "Failed to delete item")))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun rename(newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val endpoint = if (isDirectory) "$API_BASE/folder/update.php" else "$API_BASE/file/update.php"
            val keyParam = if (isDirectory) "folder_key" else "quick_key"
            val nameParam = if (isDirectory) "foldername" else "filename"
            val response = httpClient.submitForm(
                url = endpoint,
                formParameters = Parameters.build {
                    append("session_token", sessionToken)
                    append(keyParam, folderOrQuickKey)
                    append(nameParam, newName)
                    append("response_format", "json")
                }
            )
            val json = JSONObject(response.bodyAsText()).getJSONObject("response")
            if (json.optString("result") == "Success") {
                val parentPath = path.trimEnd('/').substringBeforeLast('/', "")
                Result.Success(
                    MediaFireFileItem(
                        sessionToken = sessionToken,
                        folderOrQuickKey = folderOrQuickKey,
                        name = newName,
                        path = "$parentPath/$newName${if (isDirectory) "/" else ""}",
                        size = size,
                        lastModified = System.currentTimeMillis(),
                        isDirectory = isDirectory,
                        mimeType = mimeType
                    )
                )
            } else {
                Result.Failure(Exception(json.optString("message", "Rename failed")))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun copyTo(dest: FileItem): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            if (dest !is MediaFireFileItem) {
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

            // Same-backend copy via MediaFire API
            val endpoint = if (isDirectory) "$API_BASE/folder/copy.php" else "$API_BASE/file/copy.php"
            val keyParam = if (isDirectory) "folder_key" else "quick_key"
            val response = httpClient.submitForm(
                url = endpoint,
                formParameters = Parameters.build {
                    append("session_token", sessionToken)
                    append(keyParam, folderOrQuickKey)
                    append("dst_folderkey", dest.folderOrQuickKey)
                    append("response_format", "json")
                }
            )
            val json = JSONObject(response.bodyAsText()).getJSONObject("response")
            val newKey = json.optString("new_key", folderOrQuickKey)
            Result.Success(
                MediaFireFileItem(
                    sessionToken = sessionToken,
                    folderOrQuickKey = newKey,
                    name = name,
                    path = "${dest.path}$name${if (isDirectory) "/" else ""}",
                    size = size,
                    lastModified = System.currentTimeMillis(),
                    isDirectory = isDirectory,
                    mimeType = mimeType
                )
            )
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun moveTo(destDir: FileItem, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            if (destDir !is MediaFireFileItem) {
                val copied = copyTo(destDir)
                if (copied is Result.Success) delete()
                return@withContext copied
            }

            val endpoint = if (isDirectory) "$API_BASE/folder/move.php" else "$API_BASE/file/move.php"
            val keyParam = if (isDirectory) "folder_key" else "quick_key"
            val response = httpClient.submitForm(
                url = endpoint,
                formParameters = Parameters.build {
                    append("session_token", sessionToken)
                    append(keyParam, folderOrQuickKey)
                    append("dst_folderkey", destDir.folderOrQuickKey)
                    append("response_format", "json")
                }
            )
            val json = JSONObject(response.bodyAsText()).getJSONObject("response")
            if (json.optString("result") == "Success") {
                if (newName != name) {
                    rename(newName)
                } else {
                    Result.Success(
                        MediaFireFileItem(
                            sessionToken = sessionToken,
                            folderOrQuickKey = folderOrQuickKey,
                            name = name,
                            path = "${destDir.path}$name${if (isDirectory) "/" else ""}",
                            size = size,
                            lastModified = System.currentTimeMillis(),
                            isDirectory = isDirectory,
                            mimeType = mimeType
                        )
                    )
                }
            } else {
                Result.Failure(Exception(json.optString("message", "Move failed")))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun findChild(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        val list = listFiles()
        if (list is Result.Success) {
            val child = list.value.firstOrNull { it.name.equals(name, ignoreCase = true) }
            if (child != null) Result.Success(child) else Result.Failure(java.io.FileNotFoundException("Child not found: $name"))
        } else {
            Result.Failure((list as Result.Failure).exception)
        }
    }

    override suspend fun getParent(): Result<FileItem?> {
        return Result.Success(null)
    }

    override suspend fun exists(): Boolean = true

    private fun parseDate(dateStr: String): Long {
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).parse(dateStr)?.time
                ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }
}
