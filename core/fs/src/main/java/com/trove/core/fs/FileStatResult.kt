package com.trove.core.fs

data class FileStatResult(
    val name: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
    val permissions: PosixPermissions
)
