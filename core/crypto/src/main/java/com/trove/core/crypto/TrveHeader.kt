package com.trove.core.crypto

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary header specification for Trove encrypted file containers (`TRVE` format).
 * 
 * Header Layout (57 bytes total):
 * - Offset 0 (4B): Magic `0x54525645` ("TRVE")
 * - Offset 4 (1B): Format Version (currently 0x01)
 * - Offset 5 (32B): Argon2id Salt
 * - Offset 37 (8B): Random File Nonce Prefix
 * - Offset 45 (4B): Chunk Size in bytes (default 65536 = 64KB)
 * - Offset 49 (8B): Plaintext length in bytes (-1L if streaming unknown length)
 */
data class TrveHeader(
    val version: Byte = CURRENT_VERSION,
    val salt: ByteArray,
    val noncePrefix: ByteArray,
    val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    val plaintextLength: Long = -1L
) {
    companion object {
        const val MAGIC = 0x54525645 // "TRVE" in ASCII
        const val CURRENT_VERSION: Byte = 0x01
        const val DEFAULT_CHUNK_SIZE = 64 * 1024 // 64 KB
        const val SALT_LENGTH = 32
        const val NONCE_PREFIX_LENGTH = 8
        const val HEADER_SIZE = 4 + 1 + SALT_LENGTH + NONCE_PREFIX_LENGTH + 4 + 8 // 57 bytes

        fun read(input: InputStream): TrveHeader {
            val bytes = ByteArray(HEADER_SIZE)
            var read = 0
            while (read < HEADER_SIZE) {
                val r = input.read(bytes, read, HEADER_SIZE - read)
                if (r == -1) throw IllegalArgumentException("Premature EOF while reading TRVE header")
                read += r
            }

            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val magic = buf.int
            if (magic != MAGIC) {
                throw IllegalArgumentException("Invalid magic bytes: 0x${Integer.toHexString(magic)}, expected TRVE")
            }

            val version = buf.get()
            if (version != CURRENT_VERSION) {
                throw IllegalArgumentException("Unsupported TRVE version: $version")
            }

            val salt = ByteArray(SALT_LENGTH)
            buf.get(salt)

            val noncePrefix = ByteArray(NONCE_PREFIX_LENGTH)
            buf.get(noncePrefix)

            val chunkSize = buf.int
            val plaintextLength = buf.long

            return TrveHeader(version, salt, noncePrefix, chunkSize, plaintextLength)
        }
    }

    fun write(output: OutputStream) {
        val buf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(MAGIC)
        buf.put(version)
        buf.put(salt)
        buf.put(noncePrefix)
        buf.putInt(chunkSize)
        buf.putLong(plaintextLength)
        output.write(buf.array())
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrveHeader) return false
        return version == other.version &&
                chunkSize == other.chunkSize &&
                plaintextLength == other.plaintextLength &&
                salt.contentEquals(other.salt) &&
                noncePrefix.contentEquals(other.noncePrefix)
    }

    override fun hashCode(): Int {
        var result = version.toInt()
        result = 31 * result + salt.contentHashCode()
        result = 31 * result + noncePrefix.contentHashCode()
        result = 31 * result + chunkSize
        result = 31 * result + plaintextLength.hashCode()
        return result
    }
}
