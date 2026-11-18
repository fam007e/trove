package com.trove.privilege

import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * A hardened privileged service for Trove.
 * 
 * DESIGN PRINCIPLES:
 * 1. Mutual Trust: This service only responds to the UID of the Trove application.
 * 2. Absolute Pathing: Always uses canonical paths to prevent traversal attacks.
 * 3. Atomic Operations: Prefers atomic filesystem calls where possible.
 */
class TrovePrivilegedService(private val expectedUid: Int) : ITrovePrivileged.Stub() {

    private val TAG = "TrovePrivileged"

    /**
     * Verifies that the caller's UID matches the expected Trove app UID.
     * This is the primary line of defense.
     */
    private fun enforceCaller() {
        val callingUid = Binder.getCallingUid()
        if (callingUid != expectedUid && callingUid != Process.myUid()) {
            Log.e(TAG, "ACCESS DENIED: Unauthorized UID $callingUid attempted to access privileged service.")
            throw SecurityException("Unauthorized access to TrovePrivilegedService.")
        }
    }

    private fun getCanonicalPath(path: String): String =
        com.trove.privilege.fs.PathCanonicalizer.canonical(path)

    override fun stat(path: String): Bundle {
        enforceCaller()
        val canonicalPath = getCanonicalPath(path)
        val file = File(canonicalPath)
        
        if (!file.exists()) return Bundle()

        return Bundle().apply {
            putString("name", file.name)
            putLong("size", if (file.isDirectory) 0 else file.length())
            putLong("lastModified", file.lastModified())
            putBoolean("isDirectory", file.isDirectory)
        }
    }

    override fun listFiles(path: String): List<Bundle> {
        enforceCaller()
        val canonicalPath = getCanonicalPath(path)
        val dir = File(canonicalPath)
        val files = dir.listFiles() ?: return emptyList()

        return files.map { file ->
            Bundle().apply {
                putString("name", file.name)
                putLong("size", if (file.isDirectory) 0 else file.length())
                putLong("lastModified", file.lastModified())
                putBoolean("isDirectory", file.isDirectory)
            }
        }
    }

    override fun openFile(path: String, mode: String): ParcelFileDescriptor? {
        enforceCaller()
        val canonicalPath = getCanonicalPath(path)
        val file = File(canonicalPath)
        
        val pfdMode = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "w" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE
            "rw" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
            "wa" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_APPEND
            else -> throw IllegalArgumentException("Unsupported file mode: $mode")
        }

        return try {
            ParcelFileDescriptor.open(file, pfdMode)
        } catch (e: Exception) {
            Log.e(TAG, "Privileged open failed for: $canonicalPath", e)
            null
        }
    }

    override fun rename(from: String, to: String): Boolean {
        enforceCaller()
        val canonicalFrom = getCanonicalPath(from)
        val canonicalTo = getCanonicalPath(to)
        return File(canonicalFrom).renameTo(File(canonicalTo))
    }

    override fun delete(path: String): Boolean {
        enforceCaller()
        val canonicalPath = getCanonicalPath(path)
        return File(canonicalPath).deleteRecursively()
    }

    override fun mkdir(path: String): Boolean {
        enforceCaller()
        val canonicalPath = getCanonicalPath(path)
        return File(canonicalPath).mkdirs()
    }
}
