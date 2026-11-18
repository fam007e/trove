package com.trove.feature.media

import java.util.Locale

/**
 * Utility functions for media format detection and classification.
 */
object MediaHelper {

    val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "ogg", "oga", "opus", "m4a", "aac", "wav",
        "wma", "aiff", "aif", "ape", "wv", "mid", "midi"
    )

    val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "webm", "avi", "mov", "3gp", "3g2", "m4v",
        "ts", "mts", "m2ts", "flv", "wmv", "vob", "ogv"
    )

    fun isAudio(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in AUDIO_EXTENSIONS
    }

    fun isVideo(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in VIDEO_EXTENSIONS
    }

    fun isMedia(fileName: String): Boolean {
        return isAudio(fileName) || isVideo(fileName)
    }
}
