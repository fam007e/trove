package com.trove.server.sftp

import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.io.Closeable
import java.nio.file.Paths

class SftpServerManager(
    private val host: String,
    private val port: Int,
    private val rootPath: String,
    private val keyPairProvider: KeyPairProvider,
    private val passwordAuthenticator: PasswordAuthenticator,
    private val publickeyAuthenticator: PublickeyAuthenticator? = null
) : Closeable {

    private var sshServer: SshServer? = null
    var isRunning: Boolean = false
        private set

    /**
     * The port the server actually bound to. Meaningful after [start]; always
     * -1 when stopped. Pass port=0 to bind an ephemeral port and read the
     * real value from here.
     */
    var boundPort: Int = -1
        private set

    @Synchronized
    fun start() {
        if (isRunning) return

        val server = SshServer.setUpDefaultServer().apply {
            this.host = this@SftpServerManager.host
            this.port = this@SftpServerManager.port
            this.keyPairProvider = this@SftpServerManager.keyPairProvider
            this.passwordAuthenticator = this@SftpServerManager.passwordAuthenticator
            this@SftpServerManager.publickeyAuthenticator?.let {
                this.publickeyAuthenticator = it
            }

            // Register SFTP subsystem
            val sftpFactory = SftpSubsystemFactory()
            this.subsystemFactories = listOf(sftpFactory)

            // Restrict file system to the specified root directory
            this.fileSystemFactory = VirtualFileSystemFactory(Paths.get(rootPath))
        }

        server.start()
        sshServer = server
        isRunning = true
        boundPort = server.boundAddresses
            .filterIsInstance<java.net.InetSocketAddress>()
            .firstOrNull()?.port ?: -1
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        try {
            sshServer?.stop(true)
        } finally {
            sshServer = null
            isRunning = false
            boundPort = -1
        }
    }

    override fun close() {
        stop()
    }
}
