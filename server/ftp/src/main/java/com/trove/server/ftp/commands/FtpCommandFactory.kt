package com.trove.server.ftp.commands

import java.util.concurrent.ConcurrentHashMap

object FtpCommandFactory {
    private val commands = ConcurrentHashMap<String, FtpCommand>()

    init {
        commands["USER"] = UserCommand()
        commands["PASS"] = PassCommand()
        commands["SYST"] = SystCommand()
        commands["PWD"]  = PwdCommand()
        commands["XPWD"] = PwdCommand() // Common alias
        commands["TYPE"] = TypeCommand()
        commands["PASV"] = PasvCommand()
        commands["LIST"] = ListCommand()
        commands["RETR"] = RetrCommand()
        commands["STOR"] = StorCommand()
        commands["CWD"]  = CwdCommand()
        commands["XCWD"] = CwdCommand()
        commands["FEAT"] = FeatCommand()
        commands["NOOP"] = NoopCommand()
        commands["SIZE"] = SizeCommand()
        commands["EPSV"] = EpsvCommand()
        commands["DELE"] = DeleCommand()
        commands["MKD"]  = MkdCommand()
        commands["XMKD"] = MkdCommand()
        commands["RMD"]  = RmdCommand()
        commands["XRMD"] = RmdCommand()
        commands["RNFR"] = RnfrCommand()
        commands["RNTO"] = RntoCommand()
        commands["QUIT"] = QuitCommand()
    }

    fun getCommand(name: String): FtpCommand? {
        return commands[name.uppercase()]
    }
}
