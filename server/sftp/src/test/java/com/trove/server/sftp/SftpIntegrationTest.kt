package com.trove.server.sftp

import org.apache.sshd.client.SshClient
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mindrot.jbcrypt.BCrypt
import java.io.File

/**
 * Full-stack JVM integration test for the SFTP server: starts a real
 * [SftpServerManager] on 127.0.0.1 with an ephemeral port, connects with a
 * real MINA SSH client, and exercises password authentication, the SFTP
 * subsystem, and the sandbox root enforcement.
 *
 * This is the test that makes the "SFTP server is functional" claim real:
 * the previous suite only proved the authenticator logic in isolation.
 */
class SftpIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var manager: SftpServerManager
    private var client: SshClient? = null

    private val user = "trove-test"
    private val password = "correct-horse-battery"

    @Before
    fun setUp() {
        val keyDir = tmp.newFolder("keys")
        val sandbox = tmp.newFolder("sandbox")

        manager = SftpServerManager(
            host = "127.0.0.1",
            port = 0, // ephemeral; real port read from boundPort
            rootPath = sandbox.absolutePath,
            keyPairProvider = HostKeyProviderFactory.createHostKeyProvider(
                File(keyDir, "host.ser").toPath()
            ),
            passwordAuthenticator = SftpPasswordAuthenticator { lookup ->
                if (lookup == user) BCrypt.hashpw(password, BCrypt.gensalt(4))
                else null
            }
        )
        manager.start()
    }

    @After
    fun tearDown() {
        client?.stop()
        manager.close()
    }

    private fun connect(passwordAttempt: String): SftpClient {
        val c = SshClient.setUpDefaultClient()
        c.start()
        client = c

        val session = c.connect(user, "127.0.0.1", manager.boundPort)
            .verify(10_000)
            .session
        session.addPasswordIdentity(passwordAttempt)
        session.auth().verify(10_000)
        return SftpClientFactory.instance().createSftpClient(session)
    }

    private fun sandboxRoot(): File =
        tmp.root.resolve("sandbox")

    private fun listNames(sftp: SftpClient, path: String): List<String> =
        sftp.openDir(path).use { handle ->
            sftp.listDir(handle).map { it.filename }
        }

    @Test
    fun `server starts and binds an ephemeral port`() {
        assertTrue(manager.isRunning)
        assertTrue(manager.boundPort > 0)
    }

    @Test
    fun `wrong password is rejected`() {
        val c = SshClient.setUpDefaultClient()
        client = c
        c.start()
        val session = c.connect(user, "127.0.0.1", manager.boundPort)
            .verify(10_000)
            .session
        session.addPasswordIdentity("wrong-password")
        val future = session.auth()
        future.await(10_000)
        assertFalse("auth must fail for a wrong password", future.isSuccess)
        session.close(false)
    }

    @Test
    fun `unknown user is rejected`() {
        val c = SshClient.setUpDefaultClient()
        client = c
        c.start()
        val session = c.connect("no-such-user", "127.0.0.1", manager.boundPort)
            .verify(10_000)
            .session
        session.addPasswordIdentity(password)
        val future = session.auth()
        future.await(10_000)
        assertFalse("auth must fail for an unknown user", future.isSuccess)
        session.close(false)
    }

    @Test
    fun `authenticated client can write read list mkdir and stat`() {
        val sftp = connect(password)

        // Write
        val payload = "trove sftp integration payload".toByteArray(Charsets.UTF_8)
        sftp.write("/hello.txt").use { it.write(payload) }

        // Read back and compare
        val roundTrip = sftp.read("/hello.txt").use { it.readBytes() }
        assertEquals(payload.decodeToString(), roundTrip.decodeToString())

        // Stat reports the size
        val attrs = sftp.stat("/hello.txt")
        assertEquals(payload.size.toLong(), attrs.size)

        // List shows the entry
        val names = listNames(sftp, "/")
        assertTrue(names.contains("hello.txt"))

        // mkdir + stat directory
        sftp.mkdir("/subdir")
        val dir = sftp.stat("/subdir")
        assertNotNull(dir)
        assertTrue(dir.isDirectory)

        // remove
        sftp.remove("/hello.txt")
        val afterRemove = listNames(sftp, "/")
        assertFalse(afterRemove.contains("hello.txt"))

        sftp.close()
    }

    @Test
    fun `paths cannot escape the sandbox root`() {
        val sftp = connect(password)

        // Attempt a traversal write; the virtual FS must either reject the
        // path or clamp it inside the root — never write outside it.
        val probe = "escape-probe"
        try {
            sftp.write("/../$probe.txt").use { it.write("leak".toByteArray()) }
        } catch (_: Exception) {
            // rejected outright — also acceptable
        }

        val escapedParent = sandboxRoot().parentFile?.resolve("$probe.txt")
        assertFalse(
            "file must never be created outside the sandbox root",
            escapedParent?.exists() == true
        )

        // Whatever happened, the server must remain usable
        val names = listNames(sftp, "/")
        assertNotNull(names)
        sftp.close()
    }
}
