package com.trove.feature.cleaner.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Persistent su shell for Magisk-style root.
 *
 * DESIGN: one `su` process is kept alive for the lifetime of a clean. Every
 * logical command is framed with echo sentinels (see [ShellProtocol]) so the
 * exit code and output of each command are recoverable from the shared
 * stream. A pending Magisk grant prompt blocks the stream — handled as a
 * timeout that tears the session down, so the next attempt re-raises the
 * prompt (or reports denial) instead of hanging forever.
 *
 * SAFETY: this class never builds paths itself. Callers interpolate only
 * names validated by [PackageNameValidator]; root-area paths are compile-time
 * constants. The shell is never left with a half-written command: writes are
 * whole framed blocks under a mutex.
 */
class RootShell(
    /** Default per-command deadline; a Magisk prompt usually resolves in seconds. */
    private val defaultTimeoutMs: Long = 15_000,
    /**
     * How su is launched. Magisk default: -M forces the GLOBAL mount
     * namespace — without it su inherits the calling app's isolated
     * namespace, where /data/data shows only the app's own directory and
     * every root scan silently sees one entry. Non-Magisk su may not know
     * -M, hence the fallback.
     */
    private val suCommand: Array<String> = arrayOf("su", "-M"),
    private val fallbackSuCommand: Array<String>? = arrayOf("su")
) {

    sealed class Status {
        /** su responded with uid 0. */
        object Granted : Status()

        /** No root, with the reason surfaced to the UI. */
        data class Unavailable(val reason: String) : Status()
    }

    data class ExecResult(val stdout: String, val stderr: String, val exitCode: Int)

    class RootUnavailableException(message: String) : RuntimeException(message)
    class RootTimeoutException(message: String) : RuntimeException(message)

    private val mutex = Mutex()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val outQueue = LinkedBlockingQueue<String>()

    /**
     * EOF marker enqueued when the su stream closes. LinkedBlockingQueue
     * rejects nulls (an earlier null poison NPE'd the pump thread — caught
     * by the app crash log), so a sentinel the shell can never emit is used
     * instead: real lines don't contain NUL.
     */
    private val eofToken = "\u0000trove-eof"
    private var pumpThread: Thread? = null
    private val stderrBuffer = StringBuilder()
    private var nextId = 0

    /** Currently active launcher; switches to the fallback after one failure. */
    private var activeCommand: Array<String> = suCommand

    /** Probe root availability. Safe to call repeatedly (it re-arms su). */
    suspend fun probe(): Status = withContext(Dispatchers.IO) {
        try {
            val result = exec("id -u", timeoutMs = defaultTimeoutMs)
            if (result.exitCode == 0 && ShellProtocol.parseIdOutput(result.stdout) == 0) {
                Status.Granted
            } else {
                Status.Unavailable("su answered but not as uid 0 (denied by the superuser manager?)")
            }
        } catch (e: RootUnavailableException) {
            // `su -M` unsupported → try the plain invocation once.
            if (fallbackSuCommand != null && activeCommand.contentEquals(suCommand)) {
                activeCommand = fallbackSuCommand
                teardown()
                return@withContext probe()
            }
            Status.Unavailable(e.message ?: "su unavailable")
        } catch (e: RootTimeoutException) {
            Status.Unavailable(
                "No response from su — a grant prompt may be waiting in the " +
                    "superuser manager, or access was denied"
            )
        }
    }

    suspend fun isAvailable(): Boolean = probe() is Status.Granted

    /**
     * Run one framed command. Single-line command strings only.
     * Throws [RootUnavailableException] if the su session cannot be
     * established, [RootTimeoutException] on deadline.
     */
    suspend fun exec(command: String, timeoutMs: Long = defaultTimeoutMs): ExecResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val proc = ensureProcess()
                val id = nextId++
                val frame = ShellProtocol.frame(id, command)

                stderrBuffer.setLength(0)
                // Drain any stale lines from an earlier interrupted command so
                // sentinels can't be confused with old payloads.
                outQueue.clear()

                try {
                    writer?.let { w ->
                        frame.forEach { w.write(it); w.newLine() }
                        w.flush()
                    }
                } catch (e: IOException) {
                    teardown()
                    throw RootUnavailableException("su stream broke while writing: ${e.message}")
                }

                var sawStart = false
                val payload = StringBuilder()
                val deadline = System.currentTimeMillis() + timeoutMs
                var result: ExecResult? = null

                while (result == null) {
                    val remainingMs = deadline - System.currentTimeMillis()
                    if (remainingMs <= 0) {
                        teardown()
                        proc.destroy()
                        throw RootTimeoutException(
                            "su did not answer within ${timeoutMs}ms" +
                                stderrTail().let { if (it.isEmpty()) "" else ": $it" }
                        )
                    }
                    val line = outQueue.poll(remainingMs, TimeUnit.MILLISECONDS)
                        ?: throw RootUnavailableException("su process exited unexpectedly")
                    if (line == eofToken) {
                        throw RootUnavailableException("su process exited unexpectedly")
                    }

                    when (val classified = ShellProtocol.classify(line)) {
                        is ShellProtocol.Line.Start ->
                            // A start of another id would mean a prior frame
                            // leaked past its end; skip it.
                            if (classified.id == id) sawStart = true
                        is ShellProtocol.Line.End ->
                            if (classified.id == id && sawStart) {
                                result = ExecResult(
                                    payload.toString(),
                                    stderrBuffer.toString(),
                                    classified.exitCode
                                )
                            }
                        is ShellProtocol.Line.Payload ->
                            if (sawStart) {
                                payload.appendLine(line)
                            }
                    }
                }
                result
            }
        }

    /** Close the session (does not kill anything owned by the device). */
    fun close() {
        teardown()
    }

    // ---- internals -----------------------------------------------------------

    private fun ensureProcess(): Process {
        process?.let { if (it.isAlive) return it }
        teardown()

        val p = try {
            // `su` resolves through PATH; Magisk supplies /sbin/su & co.
            Runtime.getRuntime().exec(activeCommand)
        } catch (e: IOException) {
            throw RootUnavailableException("No su binary found — this device is not rooted")
        }

        writer = BufferedWriter(OutputStreamWriter(p.outputStream))
        stderrBuffer.setLength(0)

        pumpThread = Thread {
            try {
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { outQueue.put(it) }
                }
            } catch (_: Exception) { }
            try { outQueue.put(eofToken) } catch (_: Exception) { }
        }.apply {
            isDaemon = true
            name = "trove-su-pump"
            start()
        }

        // Stderr pump: bounded, only used for diagnostics.
        Thread {
            try {
                p.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach {
                        synchronized(stderrBuffer) {
                            if (stderrBuffer.length < 8_000) stderrBuffer.appendLine(it)
                        }
                    }
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; name = "trove-su-err"; start() }

        process = p
        return p
    }

    private fun stderrTail(): String = synchronized(stderrBuffer) {
        stderrBuffer.toString().trim().take(300)
    }

    private fun teardown() {
        try { writer?.close() } catch (_: Exception) { }
        writer = null
        pumpThread = null
        process?.destroy()
        process = null
        outQueue.clear()
    }
}
