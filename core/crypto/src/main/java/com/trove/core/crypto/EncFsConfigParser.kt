package com.trove.core.crypto

import android.util.Base64

/**
 * Clean-room parser for legacy MiXplorer EncFS configuration files (`.encfs6.xml`).
 * 
 * Allows users migrating from MiXplorer to detect, import, and decrypt their existing
 * encrypted volumes without needing closed-source addons or unauthenticated encryption for new data.
 */
data class EncFsConfig(
    val cipher: String,
    val keySize: Int,
    val blockSize: Int,
    val kdfIterations: Int,
    val salt: ByteArray,
    val encodedKeyData: ByteArray,
    val nameCoding: String
) {
    companion object {
        const val CONFIG_FILE_NAME = ".encfs6.xml"

        fun parse(xml: String): EncFsConfig {
            val cipher = extractTag(xml, "cipher") ?: "ssl/aes"
            val keySize = extractTag(xml, "keySize")?.toIntOrNull() ?: 256
            val blockSize = extractTag(xml, "blockSize")?.toIntOrNull() ?: 1024
            val kdfIterations = extractTag(xml, "kdfIterations")?.toIntOrNull() ?: 100000
            
            val saltBase64 = extractTag(xml, "saltData")
                ?: throw IllegalArgumentException("Missing saltData in EncFS config")
            val salt = Base64.decode(saltBase64.trim(), Base64.DEFAULT)

            val keyDataBase64 = extractTag(xml, "encodedKeyData")
                ?: throw IllegalArgumentException("Missing encodedKeyData in EncFS config")
            val encodedKeyData = Base64.decode(keyDataBase64.trim(), Base64.DEFAULT)

            val nameCoding = extractTag(xml, "nameCoding") ?: "nameio/block"

            return EncFsConfig(
                cipher = cipher,
                keySize = keySize,
                blockSize = blockSize,
                kdfIterations = kdfIterations,
                salt = salt,
                encodedKeyData = encodedKeyData,
                nameCoding = nameCoding
            )
        }

        private fun extractTag(xml: String, tag: String): String? {
            val startTag = "<$tag>"
            val endTag = "</$tag>"
            val start = xml.indexOf(startTag)
            if (start == -1) return null
            val end = xml.indexOf(endTag, start + startTag.length)
            if (end == -1) return null
            return xml.substring(start + startTag.length, end).trim()
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncFsConfig) return false
        return cipher == other.cipher &&
                keySize == other.keySize &&
                blockSize == other.blockSize &&
                kdfIterations == other.kdfIterations &&
                salt.contentEquals(other.salt) &&
                encodedKeyData.contentEquals(other.encodedKeyData) &&
                nameCoding == other.nameCoding
    }

    override fun hashCode(): Int {
        var result = cipher.hashCode()
        result = 31 * result + keySize
        result = 31 * result + blockSize
        result = 31 * result + kdfIterations
        result = 31 * result + salt.contentHashCode()
        result = 31 * result + encodedKeyData.contentHashCode()
        result = 31 * result + nameCoding.hashCode()
        return result
    }
}
