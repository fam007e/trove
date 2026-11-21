package com.trove.feature.cleaner.core

/**
 * Guard for names that flow into root shell commands.
 *
 * Corpse/App-Cleaner names come from directory listings of app data dirs and
 * from PackageManager — never from raw user input — but they are interpolated
 * into su commands, so a hostile package name (or a crafted directory in
 * /data/data) must never survive to the shell. Everything the Cleaner deletes
 * is passed through here first; anything that doesn't match a plausible Java
 * package name is skipped, not cleaned.
 *
 * Note: dot-less names are allowed so the system package "android" matches;
 * it only ever reaches the shell when it is also present in the installed
 * package set, so unrelated dot-less dirs (".cache", "lost+found") can't ride
 * along.
 */
object PackageNameValidator {

    private val PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*$")

    /** Conservative cap; real package names are far shorter. */
    private const val MAX_LEN = 200

    fun isValid(name: String): Boolean =
        name.isNotEmpty() && name.length <= MAX_LEN && PATTERN.matches(name)

    fun requireValid(name: String): String {
        require(isValid(name)) { "Unsafe package/directory name: $name" }
        return name
    }

    /**
     * Shell-quote for double-quoted interpolation. Used on top of
     * [isValid] — belt and braces: even a validated name can't escape quotes.
     */
    fun shellQuote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("$", "\\$").replace("\"", "\\\"") + "\""
}
