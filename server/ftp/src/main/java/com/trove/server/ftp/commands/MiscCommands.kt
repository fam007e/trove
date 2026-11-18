package com.trove.server.ftp.commands

import com.trove.server.ftp.FtpSession
import java.io.Writer

class FeatCommand : FtpCommand {
    override suspend fun execute(session: FtpSession, argument: String, writer: Writer) {
        writer.write("211-Extensions supported:\r\n")
        writer.write(" UTF8\r\n")
        writer.write(" SIZE\r\n")
        writer.write(" EPSV\r\n")
        writer.write(" PASV\r\n")
        writer.write("211 End\r\n")
        writer.flush()
    }
}

class NoopCommand : FtpCommand {
    override suspend fun execute(session: FtpSession, argument: String, writer: Writer) {
        writer.writeResponse(200, "NOOP ok.")
    }
}

class SizeCommand : FtpCommand {
    override suspend fun execute(session: FtpSession, argument: String, writer: Writer) {
        val result = session.currentDirectory.findChild(argument)
        if (result is com.trove.core.fs.Result.Success) {
            writer.writeResponse(213, result.value.size.toString())
        } else {
            writer.writeResponse(550, "Could not get file size.")
        }
    }
}

class EpsvCommand : FtpCommand {
    override suspend fun execute(session: FtpSession, argument: String, writer: Writer) {
        try {
            val server = java.net.ServerSocket(0)
            session.passiveServer = server
            writer.writeResponse(229, "Entering Extended Passive Mode (|||${server.localPort}|).")
        } catch (e: Exception) {
            writer.writeResponse(425, "Can't open data connection.")
        }
    }
}
