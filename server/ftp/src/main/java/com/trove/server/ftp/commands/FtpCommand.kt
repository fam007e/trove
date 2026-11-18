package com.trove.server.ftp.commands

import com.trove.server.ftp.FtpSession
import java.io.Writer

interface FtpCommand {
    suspend fun execute(session: FtpSession, argument: String, writer: Writer)
}

fun Writer.writeResponse(code: Int, message: String) {
    write("$code $message\r\n")
    flush()
}
