package com.trove.core.crypto

import java.io.OutputStream
import javax.crypto.SecretKey

/**
 * Output stream that segments plaintext into chunk-authenticated ciphertext blocks.
 */
class TrveChunkedOutputStream(
    private val underlying: OutputStream,
    private val key: SecretKey,
    salt: ByteArray,
    noncePrefix: ByteArray,
    private val chunkSize: Int = TrveHeader.DEFAULT_CHUNK_SIZE
) : OutputStream() {

    private val cipher = TrveChunkedCipher(key, noncePrefix)
    private val buffer = ByteArray(chunkSize)
    private var bufferPos = 0
    private var chunkIndex = 0
    private var closed = false

    init {
        val header = TrveHeader(
            salt = salt,
            noncePrefix = noncePrefix,
            chunkSize = chunkSize
        )
        header.write(underlying)
    }

    override fun write(b: Int) {
        val single = byteArrayOf(b.toByte())
        write(single, 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (closed) throw IllegalStateException("Stream is closed")
        var currentOff = off
        var remaining = len

        while (remaining > 0) {
            val spaceInBuffer = chunkSize - bufferPos
            val toCopy = Math.min(spaceInBuffer, remaining)
            System.arraycopy(b, currentOff, buffer, bufferPos, toCopy)
            bufferPos += toCopy
            currentOff += toCopy
            remaining -= toCopy

            // If buffer is full, flush an intermediate chunk (isLast = false)
            if (bufferPos == chunkSize) {
                flushChunk(isLast = false)
            }
        }
    }

    private fun flushChunk(isLast: Boolean) {
        val encrypted = cipher.encryptChunk(buffer, 0, bufferPos, chunkIndex, isLast)
        // Write chunk length prefix so stream readers know the payload boundary
        val lenBuf = java.nio.ByteBuffer.allocate(4).putInt(encrypted.size).array()
        underlying.write(lenBuf)
        underlying.write(encrypted)
        bufferPos = 0
        chunkIndex++
    }

    override fun flush() {
        underlying.flush()
    }

    override fun close() {
        if (!closed) {
            // Flush final chunk with isLast = true
            flushChunk(isLast = true)
            underlying.flush()
            underlying.close()
            closed = true
        }
    }
}
