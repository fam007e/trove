package com.trove.feature.cleaner

import com.trove.feature.cleaner.core.RootShell
import com.trove.feature.cleaner.core.ShellProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persistent shell session is exercised against a REAL process — the
 * host /bin/sh instead of su — so the framed sentinel protocol, the pump
 * thread and multi-command session reuse are proven against actual stream
 * behavior, not just pure parsing.
 */
class RootShellTest {

    private fun newShell(): RootShell = RootShell(
        suCommand = arrayOf("/bin/sh"),
        fallbackSuCommand = null
    )

    @Test
    fun `session survives multiple commands and reports exit codes`() = runBlocking {
        val shell = newShell()
        try {
            val first = shell.exec("echo hello")
            assertEquals(0, first.exitCode)
            assertEquals("hello\n", first.stdout)

            val second = shell.exec("echo world")
            assertEquals("world\n", second.stdout)

            // Non-zero exit code propagates.
            val failing = shell.exec("ls /definitely/not/here-xyz 2>/dev/null")
            assertTrue(failing.exitCode != 0)

            // Multi-command line.
            val combined = shell.exec("echo a; echo b")
            assertEquals("a\nb\n", combined.stdout)
        } finally {
            shell.close()
        }
    }

    @Test
    fun `sentinel-shaped output neither crashes nor corrupts the session`() = runBlocking {
        val shell = newShell()
        try {
            // Contract: an exact sentinel line of a FOREIGN id is consumed as
            // framing and skipped; non-sentinel text passes through; the
            // session keeps working afterwards.
            val result = shell.exec("printf 'TROVE_S_99\\nactual payload\\n'")
            assertEquals(0, result.exitCode)
            assertEquals("actual payload\n", result.stdout)

            // The session is still healthy after eating a foreign sentinel.
            val after = shell.exec("echo fine")
            assertEquals("fine\n", after.stdout)
        } finally {
            shell.close()
        }
    }

    @Test
    fun `probe reflects the host shell uid honestly`() = runBlocking {
        val shell = newShell()
        try {
            val status = shell.probe()
            val hostUid = Runtime.getRuntime().exec(arrayOf("id", "-u"))
                .inputStream.bufferedReader().readText().trim().toInt()
            if (hostUid == 0) {
                assertTrue(status is RootShell.Status.Granted)
            } else {
                assertTrue(
                    "non-root host must report unavailable",
                    status is RootShell.Status.Unavailable
                )
            }
        } finally {
            shell.close()
        }
    }

    @Test
    fun `du listing round trip matches device output shape`() {
        val map = ShellProtocol.parseDuListing("4\t/data/data/com.a/cache\n0 /data/data/com.b/cache")
        assertEquals(4096L, map["/data/data/com.a/cache"])
        assertEquals(0L, map["/data/data/com.b/cache"])
    }
}
