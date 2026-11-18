package com.trove.core.fs

import java.io.IOException

/**
 * Extension functions for FileItem to handle common navigation tasks.
 * @param jailRoot The root FileItem that navigation must be constrained to.
 */
suspend fun FileItem.resolve(path: String, jailRoot: FileItem? = null): Result<FileItem> {
    if (path.isEmpty() || path == ".") return Result.Success(this)
    
    val parts = path.split("/").filter { it.isNotEmpty() }
    var current: FileItem = this
    
    for (part in parts) {
        if (part == "..") {
            // Constrain traversal: if we are at jailRoot, we cannot go up
            if (jailRoot != null && current.path == jailRoot.path) {
                // Stay at root or return error. FTP usually stays at root.
                continue
            }
            
            val parentResult = current.getParent()
            if (parentResult is Result.Success && parentResult.value != null) {
                current = parentResult.value!!
            } else {
                return Result.Failure(IOException("Cannot resolve '..' above filesystem root or parent not found"))
            }
        } else {
            val childResult = current.findChild(part)
            if (childResult is Result.Success) {
                current = childResult.value
            } else {
                return Result.Failure(IOException("Path component not found: $part"))
            }
        }
    }
    
    // Final sanity check: ensure the resulting path is still within jailRoot.
    // We check for exact match or that it's a child (path starts with root path + separator)
    if (jailRoot != null) {
        val rootPath = jailRoot.path.let { if (it.endsWith("/")) it else "$it/" }
        val currentPath = current.path
        if (currentPath != jailRoot.path && !currentPath.startsWith(rootPath)) {
            return Result.Failure(IOException("Security: Attempted path traversal detected."))
        }
    }
    
    return Result.Success(current)
}
