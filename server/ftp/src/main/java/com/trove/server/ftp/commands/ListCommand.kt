package com.trove.server.ftp.commands

import com.trove.core.fs.Result
import com.trove.server.ftp.FtpSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ListCommand : FtpCommand {
    private val dateFormatter = SimpleDateFormat("MMM dd HH:mm", Locale.ENGLISH)

    override suspend fun execute(session: FtpSession, argument: String, writer: Writer) {
        writer.writeResponse(150, "Here comes the directory listing.")
        
        val dataSocket = session.getDataSocket()
        if (dataSocket == null) {
            writer.writeResponse(425, "Can't open data connection.")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                dataSocket.use { socket ->
                    val out = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
                    
                    when (val result = session.currentDirectory.listFiles()) {
                        is Result.Success -> {
                            for (item in result.value) {
                                val line = formatListItem(item)
                                out.write("$line\r\n")
                            }
                            out.flush()
                            writer.writeResponse(226, "Directory send OK.")
                        }
                        is Result.Failure -> {
                            writer.writeResponse(450, "Requested file action not taken.")
                        }
                    }
                }
            } catch (e: Exception) {
                writer.writeResponse(426, "Connection closed; transfer aborted.")
            }
        }
    }

    private fun formatListItem(item: com.trove.core.fs.FileItem): String {
        val type = if (item.isDirectory) "d" else "-"
        val perms = item.permissions.toString()
        val size = item.size.toString().padStart(12)
        val date = dateFormatter.format(Date(item.lastModified))
        return "$type$perms   1 owner    group $size $date ${item.name}"
    }
}
