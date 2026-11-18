package com.trove.server.webdav

import com.trove.core.fs.FileItem
import com.trove.core.fs.Result
import com.trove.core.fs.resolve
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import java.io.InputStream
import java.io.OutputStream

private val propfindMethod = HttpMethod("PROPFIND")
private val mkcolMethod = HttpMethod("MKCOL")
private val moveMethod = HttpMethod("MOVE")
private val copyMethod = HttpMethod("COPY")

fun Route.configureWebDavRoutes(root: FileItem) {
    val xmlBuilder = WebDavXmlBuilder()

    route("{path...}") {
        options {
            call.response.header("Allow", "GET,POST,PUT,DELETE,PROPFIND,OPTIONS,MKCOL,COPY,MOVE")
            call.response.header("DAV", "1,2") // Class 1 and 2 WebDAV
            call.respond(HttpStatusCode.OK)
        }

        method(propfindMethod) {
            handle {
                val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
                val depth = call.request.header("Depth") ?: "1"
                
                when (val result = root.resolve(path, jailRoot = root)) {
                    is Result.Success -> {
                        val item = result.value
                        val itemsToRender = mutableListOf(item)
                        if (depth != "0" && item.isDirectory) {
                            when (val listResult = item.listFiles()) {
                                is Result.Success -> itemsToRender.addAll(listResult.value)
                                is Result.Failure -> {} // Ignore failures for sub-items
                            }
                        }
                        val xml = xmlBuilder.buildMultiStatus(itemsToRender, root.path)
                        call.respondText(xml, ContentType.Text.Xml, HttpStatusCode.MultiStatus)
                    }
                    is Result.Failure -> call.respond(HttpStatusCode.NotFound)
                }
            }
        }

        get {
            val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
            when (val result = root.resolve(path, jailRoot = root)) {
                is Result.Success -> {
                    val item = result.value
                    if (item.isDirectory) {
                        call.respond(HttpStatusCode.BadRequest, "Cannot GET a directory")
                    } else {
                        when (val inputResult = item.openInputStream()) {
                            is Result.Success -> {
                                call.respondOutputStream(ContentType.parse(item.mimeType)) {
                                    inputResult.value.use { input -> input.copyTo(this) }
                                }
                            }
                            is Result.Failure -> call.respond(HttpStatusCode.InternalServerError)
                        }
                    }
                }
                is Result.Failure -> call.respond(HttpStatusCode.NotFound)
            }
        }

        put {
            val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
            val parentPath = path.substringBeforeLast("/", "")
            val fileName = path.substringAfterLast("/")

            when (val parentResult = root.resolve(parentPath, jailRoot = root)) {
                is Result.Success -> {
                    val parent = parentResult.value
                    when (val fileResult = parent.createFile(fileName)) {
                        is Result.Success -> {
                            val item = fileResult.value
                            when (val outputResult = item.openOutputStream(append = false)) {
                                is Result.Success -> {
                                    call.receiveStream().use { input ->
                                        outputResult.value.use { output -> input.copyTo(output) }
                                    }
                                    call.respond(HttpStatusCode.Created)
                                }
                                is Result.Failure -> call.respond(HttpStatusCode.InternalServerError)
                            }
                        }
                        is Result.Failure -> {
                            // If file already exists, overwrite
                            when (val existingResult = parent.findChild(fileName)) {
                                is Result.Success -> {
                                    val item = existingResult.value
                                    when (val outputResult = item.openOutputStream(append = false)) {
                                        is Result.Success -> {
                                            call.receiveStream().use { input ->
                                                outputResult.value.use { output -> input.copyTo(output) }
                                            }
                                            call.respond(HttpStatusCode.NoContent)
                                        }
                                        is Result.Failure -> call.respond(HttpStatusCode.InternalServerError)
                                    }
                                }
                                is Result.Failure -> call.respond(HttpStatusCode.Forbidden)
                            }
                        }
                    }
                }
                is Result.Failure -> call.respond(HttpStatusCode.Conflict)
            }
        }

        delete {
            val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
            when (val result = root.resolve(path, jailRoot = root)) {
                is Result.Success -> {
                    when (val deleteResult = result.value.delete()) {
                        is Result.Success -> call.respond(HttpStatusCode.NoContent)
                        is Result.Failure -> call.respond(HttpStatusCode.InternalServerError)
                    }
                }
                is Result.Failure -> call.respond(HttpStatusCode.NotFound)
            }
        }

        method(mkcolMethod) {
            handle {
                val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
                val parentPath = path.substringBeforeLast("/", "")
                val dirName = path.substringAfterLast("/")

                when (val parentResult = root.resolve(parentPath, jailRoot = root)) {
                    is Result.Success -> {
                        when (val createResult = parentResult.value.createDirectory(dirName)) {
                            is Result.Success -> call.respond(HttpStatusCode.Created)
                            is Result.Failure -> call.respond(HttpStatusCode.Forbidden)
                        }
                    }
                    is Result.Failure -> call.respond(HttpStatusCode.Conflict)
                }
            }
        }

        method(moveMethod) {
            handle {
                val sourcePath = call.parameters.getAll("path")?.joinToString("/") ?: ""
                val destHeader = call.request.header("Destination") ?: ""
                val destUrl = Url(destHeader)
                // Extract relative path from destination URL
                // This is a simplified extraction
                val destPath = destUrl.encodedPath.substringAfter("/").removePrefix("/")

                when (val sourceResult = root.resolve(sourcePath, jailRoot = root)) {
                    is Result.Success -> {
                        val sourceItem = sourceResult.value
                        val destParentPath = destPath.substringBeforeLast("/", "")
                        val destName = destPath.substringAfterLast("/")

                        when (val destParentResult = root.resolve(destParentPath, jailRoot = root)) {
                            is Result.Success -> {
                                when (val moveResult = sourceItem.moveTo(destParentResult.value, destName)) {
                                    is Result.Success -> call.respond(HttpStatusCode.Created)
                                    is Result.Failure -> call.respond(HttpStatusCode.Forbidden)
                                }
                            }
                            is Result.Failure -> call.respond(HttpStatusCode.Conflict)
                        }
                    }
                    is Result.Failure -> call.respond(HttpStatusCode.NotFound)
                }
            }
        }
    }
}
