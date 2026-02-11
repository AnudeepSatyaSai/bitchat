// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.protocol

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Compression utility for BitChat payloads.
 * Uses zlib (Deflater/Inflater) for compatibility with Swift's zlib compression.
 *
 * Strategy: Auto-compress payloads > 256 bytes when beneficial.
 */
object CompressionUtil {
    private const val COMPRESSION_THRESHOLD = 256

    /** Whether the payload should be compressed */
    fun shouldCompress(data: ByteArray): Boolean {
        return data.size > COMPRESSION_THRESHOLD
    }

    /**
     * Compress data using zlib (DEFLATE).
     * Returns null if compression fails or result is larger than input.
     */
    fun compress(data: ByteArray): ByteArray? {
        return try {
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false) // zlib format (with header)
            deflater.setInput(data)
            deflater.finish()

            val output = ByteArrayOutputStream(data.size)
            val buffer = ByteArray(1024)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                output.write(buffer, 0, count)
            }
            deflater.end()

            val compressed = output.toByteArray()
            // Only use compression if it actually saves space
            if (compressed.size < data.size) compressed else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Decompress data using zlib (INFLATE).
     * Returns null if decompression fails or output size doesn't match expected.
     */
    fun decompress(data: ByteArray, originalSize: Int): ByteArray? {
        return try {
            val inflater = Inflater(false) // zlib format (with header)
            inflater.setInput(data)

            val output = ByteArray(originalSize)
            val resultSize = inflater.inflate(output)
            inflater.end()

            if (resultSize == originalSize) output else null
        } catch (e: Exception) {
            null
        }
    }
}
