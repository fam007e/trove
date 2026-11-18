package com.trove.feature.archive

import com.trove.core.fs.FileItem
import java.util.Locale

enum class ArchiveType(val defaultExtension: String, val mimeType: String) {
    ZIP("zip", "application/zip"),
    SEVEN_Z("7z", "application/x-7z-compressed"),
    TAR("tar", "application/x-tar"),
    TAR_GZ("tar.gz", "application/gzip"),
    TAR_BZ2("tar.bz2", "application/x-bzip2"),
    TAR_XZ("tar.xz", "application/x-xz"),
    GZIP("gz", "application/gzip"),
    BZIP2("bz2", "application/x-bzip2"),
    XZ("xz", "application/x-xz"),
    RAR("rar", "application/vnd.rar"),
    CPIO("cpio", "application/x-cpio"),
    AR("ar", "application/x-archive");

    companion object {
        fun fromFileName(fileName: String): ArchiveType? {
            val lower = fileName.lowercase(Locale.ROOT)
            return when {
                lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> TAR_GZ
                lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") -> TAR_BZ2
                lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> TAR_XZ
                lower.endsWith(".zip") || lower.endsWith(".jar") || lower.endsWith(".apk") -> ZIP
                lower.endsWith(".7z") -> SEVEN_Z
                lower.endsWith(".tar") -> TAR
                lower.endsWith(".gz") -> GZIP
                lower.endsWith(".bz2") -> BZIP2
                lower.endsWith(".xz") -> XZ
                lower.endsWith(".rar") -> RAR
                lower.endsWith(".cpio") -> CPIO
                lower.endsWith(".ar") || lower.endsWith(".deb") -> AR
                else -> null
            }
        }

        fun fromFileItem(fileItem: FileItem): ArchiveType? {
            return fromFileName(fileItem.name)
        }

        fun isArchive(fileItem: FileItem): Boolean {
            return !fileItem.isDirectory && fromFileItem(fileItem) != null
        }
    }
}
