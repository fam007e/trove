package com.trove.privilege.fs

import java.io.File
import java.io.IOException

/**
 * Pure path canonicalization used by the privileged service ("Absolute
 * Pathing" design principle). Extracted from [com.trove.privilege.TrovePrivilegedService]
 * so the traversal behavior is unit-testable on the JVM without Binder/Parcel
 * classes.
 */
object PathCanonicalizer {

    /**
     * Resolves [path] to its canonical absolute form, collapsing `.` and
     * `..` segments and resolving symlinks. Falls back to the plain absolute
     * path when canonicalization fails (e.g. missing filesystem entry on
     * exotic mount points) — never returns a relative path.
     */
    fun canonical(path: String): String {
        return try {
            File(path).canonicalPath
        } catch (_: IOException) {
            File(path).absolutePath
        }
    }
}
