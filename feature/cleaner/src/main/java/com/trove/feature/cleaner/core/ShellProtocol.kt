package com.trove.feature.cleaner.core

/**
 * Pure framing logic for the persistent su shell (see [RootShell]).
 *
 * A su session is an interactive stream — stdout of one command can't be
 * told apart from the next without markers. Every logical command is wrapped
 * in echo sentinels so replies can be matched to requests and the exit code
 * recovered from the shell's own `$?`:
 *
 *     echo TROVE_S_<id>_
 *     <command>            (single line)
 *     echo TROVE_E_<id>_$?
 *
 * Split from RootShell so framing and output parsing stay unit-testable
 * without spawning a process.
 */
object ShellProtocol {

    const val START_PREFIX = "TROVE_S_"
    const val END_PREFIX = "TROVE_E_"

    /**
     * The three stdin lines that frame one logical command. `$?` and `$$`
     * are written literally — the su shell expands them, not Kotlin.
     * Commands must be single-line; multi-command work is composed with
     * `;` / `&&` / shell loops by the caller.
     */
    fun frame(id: Int, command: String): List<String> = listOf(
        "echo ${START_PREFIX}${id}_",
        command,
        "echo $END_PREFIX${id}_\$?"
    )

    sealed class Line {
        data class Start(val id: Int) : Line()
        data class End(val id: Int, val exitCode: Int) : Line()
        object Payload : Line()
    }

    /** Classify one stdout line as a sentinel or as command payload. */
    fun classify(line: String): Line {
        if (line.startsWith(START_PREFIX)) {
            val id = line.removePrefix(START_PREFIX).substringBefore('_').toIntOrNull()
            if (id != null) return Line.Start(id)
        }
        if (line.startsWith(END_PREFIX)) {
            val rest = line.removePrefix(END_PREFIX)
            val id = rest.substringBefore('_').toIntOrNull()
            val code = rest.substringAfter('_', "").toIntOrNull()
            if (id != null && code != null) return Line.End(id, code)
        }
        return Line.Payload
    }

    /**
     * Parse a `du -sk` line ("12\t/path" or "12 /path" — busybox uses a
     * space) into (kilobytes, path). Returns null for non-du output.
     */
    fun parseDuLine(line: String): Pair<Long, String>? {
        val trimmed = line.trim()
        val space = trimmed.indexOfFirst { it == ' ' || it == '\t' }
        if (space <= 0) return null
        val kb = trimmed.substring(0, space).toLongOrNull() ?: return null
        val path = trimmed.substring(space).trim()
        if (path.isEmpty()) return null
        return kb to path
    }

    /** Map a batched du listing to path → bytes. Malformed lines are skipped. */
    fun parseDuListing(output: String): Map<String, Long> =
        output.lineSequence()
            .mapNotNull { parseDuLine(it) }
            .associate { it.second to it.first * 1024L }

    /** Parse the stdout of `id -u` (just the number, possibly with noise). */
    fun parseIdOutput(output: String): Int? =
        output.trim().lineSequence().lastOrNull { it.isNotBlank() }?.trim()?.toIntOrNull()
}
