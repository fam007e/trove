package com.trove.feature.media

import android.media.audiofx.Equalizer

/**
 * Manages an Android [Equalizer] instance attached to ExoPlayer's audio session.
 * 
 * Provides band manipulation, frequency querying, and preset selection
 * with safe error handling across diverse OEM Android audio stacks.
 */
class TroveEqualizer(private val audioSessionId: Int) {

    private var equalizer: Equalizer? = null
    var isAvailable: Boolean = false
        private set

    init {
        try {
            if (audioSessionId != 0) {
                equalizer = Equalizer(0, audioSessionId).apply {
                    enabled = true
                }
                isAvailable = true
            }
        } catch (_: Exception) {
            isAvailable = false
            equalizer = null
        }
    }

    val numberOfBands: Short
        get() = equalizer?.numberOfBands ?: 0

    val bandLevelRange: Pair<Short, Short>
        get() {
            val range = equalizer?.bandLevelRange ?: shortArrayOf(-1500, 1500)
            return Pair(range[0], range[1])
        }

    fun getCenterFreq(band: Short): Int {
        return try {
            equalizer?.getCenterFreq(band) ?: 0
        } catch (_: Exception) {
            0
        }
    }

    fun getBandLevel(band: Short): Short {
        return try {
            equalizer?.getBandLevel(band) ?: 0
        } catch (_: Exception) {
            0
        }
    }

    fun setBandLevel(band: Short, level: Short) {
        try {
            equalizer?.setBandLevel(band, level)
        } catch (_: Exception) {
        }
    }

    fun getPresets(): List<String> {
        val count = equalizer?.numberOfPresets ?: 0
        return (0 until count).map { index ->
            try {
                equalizer?.getPresetName(index.toShort()) ?: "Preset $index"
            } catch (_: Exception) {
                "Preset $index"
            }
        }
    }

    fun usePreset(presetIndex: Short) {
        try {
            equalizer?.usePreset(presetIndex)
        } catch (_: Exception) {
        }
    }

    fun setEnabled(enable: Boolean) {
        try {
            equalizer?.enabled = enable
        } catch (_: Exception) {
        }
    }

    fun isEnabled(): Boolean {
        return try {
            equalizer?.enabled ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun release() {
        try {
            equalizer?.release()
        } catch (_: Exception) {
        } finally {
            equalizer = null
            isAvailable = false
        }
    }
}
