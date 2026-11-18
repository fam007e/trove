package com.trove.server.ftp.commands

import com.trove.core.fs.resolve
import com.trove.server.ftp.FtpSession
import java.io.Writer

class SystCommand : FtpCommand {
    override suspend fun execute(session: FtpSession, argument: String, writer: Writer) {
        writer.writeResponse(215, "UNIX Type: L8")
    }
}

class PwdCommand : FtpCommand {
    override suspend fun execute(session: FtpSession, argument: String, writer: Writer) {
        writer.writeResponse(257, "\"${session.currentDirectory.path}\" is current directory.")
    }
}

class TypeCommand : FtpCommand {
    override suspend fun execute(session: FtpSession, argument: String, writer: Writer) {
        val type = argument.uppercase()
        if (type == "A" || type == "I") {
            session.isBinaryMode = (type == "I")
            writer.writeResponse(200, "Type set to $type.")
        } else {
            writer.writeResponse(504, "Command not implemented for that parameter.")
        }
    }
}

class CwdCommand : FtpCommand {
    override suspend fun execute(session: FtpSession, argument: String, writer: Writer) {
        val root = session.rootDirectory
        val base = if (argument.startsWith("/")) root else session.currentDirectory
        val path = if (argument.startsWith("/")) argument.substring(1) else argument
        
        when (val result = base.resolve(path, jailRoot = root)) {
            is com.trove.core.fs.Result.Success -> {
                val target = result.value
                if (target.isDirectory) {
                    session.currentDirectory = target
                    writer.writeResponse(250, "Directory changed to ${target.path}")
                } else {
                    writer.writeResponse(550, "Target is not a directory.")
                }
            }
            is com.trove.core.fs.Result.Failure -> {
                writer.writeResponse(550, "Failed to change directory: ${result.exception.message}")
            }
        }
    }
}
