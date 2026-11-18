package com.trove.server.ftp.commands

import com.trove.core.fs.Result
import com.trove.core.fs.resolve
import com.trove.server.ftp.FtpSession
import java.io.Writer

class DeleCommand : FtpCommand {
    override suspend fun execute(session: FtpSession, argument: String, writer: Writer) {
        val result = session.currentDirectory.findChild(argument)
        if (result is Result.Success) {
            val item = result.value
            if (item.isDirectory) {
                writer.writeResponse(550, "Item is a directory.")
            } else {
                when (val deleteResult = item.delete()) {
                    is Result.Success -> writer.writeResponse(250, "File deleted.")
                    is Result.Failure -> writer.writeResponse(550, "Delete failed: ${deleteResult.exception.message}")
                }
            }
        } else {
            writer.writeResponse(550, "File not found.")
        }
    }
}

class MkdCommand : FtpCommand {
    override suspend fun execute(session: FtpSession, argument: String, writer: Writer) {
        when (val result = session.currentDirectory.createDirectory(argument)) {
            is Result.Success -> writer.writeResponse(257, "\"$argument\" created.")
            is Result.Failure -> writer.writeResponse(550, "Create directory failed: ${result.exception.message}")
        }
    }
}

class RmdCommand : FtpCommand {
    override suspend fun execute(session: FtpSession, argument: String, writer: Writer) {
        val result = session.currentDirectory.findChild(argument)
        if (result is Result.Success) {
            val item = result.value
            if (!item.isDirectory) {
                writer.writeResponse(550, "Item is not a directory.")
            } else {
                when (val deleteResult = item.delete()) {
                    is Result.Success -> writer.writeResponse(250, "Directory removed.")
                    is Result.Failure -> writer.writeResponse(550, "Remove failed: ${deleteResult.exception.message}")
                }
            }
        } else {
            writer.writeResponse(550, "Directory not found.")
        }
    }
}

class RnfrCommand : FtpCommand {
    override suspend fun execute(session: FtpSession, argument: String, writer: Writer) {
        val result = session.currentDirectory.findChild(argument)
        if (result is Result.Success) {
            session.renameFrom = result.value
            writer.writeResponse(350, "File exists, ready for destination name.")
        } else {
            writer.writeResponse(550, "File not found.")
        }
    }
}

class RntoCommand : FtpCommand {
    override suspend fun execute(session: FtpSession, argument: String, writer: Writer) {
        val from = session.renameFrom
        if (from == null) {
            writer.writeResponse(503, "Bad sequence of commands.")
            return
        }

        val destDirPath = if (argument.contains("/")) argument.substringBeforeLast("/") else ""
        val newName = if (argument.contains("/")) argument.substringAfterLast("/") else argument
        
        val destDirResult = if (destDirPath.isEmpty()) {
            if (argument.startsWith("/")) Result.Success(session.rootDirectory)
            else Result.Success(session.currentDirectory)
        } else {
            val base = if (argument.startsWith("/")) session.rootDirectory else session.currentDirectory
            val relativePath = if (argument.startsWith("/")) destDirPath.substring(1) else destDirPath
            base.resolve(relativePath, jailRoot = session.rootDirectory)
        }

        when (destDirResult) {
            is Result.Success -> {
                when (val result = from.moveTo(destDirResult.value, newName)) {
                    is Result.Success -> {
                        session.renameFrom = null
                        writer.writeResponse(250, "Rename successful.")
                    }
                    is Result.Failure -> {
                        writer.writeResponse(550, "Rename failed: ${result.exception.message}")
                    }
                }
            }
            is Result.Failure -> {
                writer.writeResponse(550, "Target directory not found: ${destDirResult.exception.message}")
            }
        }
    }
}
