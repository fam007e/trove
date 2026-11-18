package com.trove.server.ftp.commands

import com.trove.core.fs.Result
import com.trove.server.ftp.FtpSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Writer
import java.io.IOException

class RetrCommand : FtpCommand {
    override suspend fun execute(session: FtpSession, argument: String, writer: Writer) {
        val result = session.currentDirectory.findChild(argument)
        if (result is Result.Failure) {
            writer.writeResponse(550, "File not found.")
            return
        }

        val fileItem = (result as Result.Success).value
        if (fileItem.isDirectory) {
            writer.writeResponse(550, "Requested item is a directory.")
            return
        }

        writer.writeResponse(150, "Opening BINARY mode data connection for ${fileItem.name}.")
        
        val dataSocket = session.getDataSocket()
        if (dataSocket == null) {
            writer.writeResponse(425, "Can't open data connection.")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                dataSocket.use { socket ->
                    val inputStreamResult = fileItem.openInputStream()
                    if (inputStreamResult is Result.Success) {
                        inputStreamResult.value.use { input ->
                            socket.getOutputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        writer.writeResponse(226, "Transfer complete.")
                    } else {
                        writer.writeResponse(451, "Requested action aborted: local error in processing.")
                    }
                }
            } catch (e: Exception) {
                writer.writeResponse(426, "Connection closed; transfer aborted.")
            }
        }
    }
}

class StorCommand : FtpCommand {
    override suspend fun execute(session: FtpSession, argument: String, writer: Writer) {
        writer.writeResponse(150, "Ok to send data.")
        
        val dataSocket = session.getDataSocket()
        if (dataSocket == null) {
            writer.writeResponse(425, "Can't open data connection.")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                dataSocket.use { socket ->
                    val createFileResult = session.currentDirectory.createFile(argument)
                    if (createFileResult is Result.Success) {
                        val outputStreamResult = createFileResult.value.openOutputStream(false)
                        if (outputStreamResult is Result.Success) {
                            socket.getInputStream().use { input ->
                                outputStreamResult.value.use { output ->
                                    input.copyTo(output)
                                }
                            }
                            writer.writeResponse(226, "Transfer complete.")
                        } else {
                            writer.writeResponse(451, "Requested action aborted: local error in processing.")
                        }
                    } else {
                        writer.writeResponse(550, "Could not create file.")
                    }
                }
            } catch (e: Exception) {
                writer.writeResponse(426, "Connection closed; transfer aborted.")
            }
        }
    }
}
