// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.protocol

/**
 * Privacy-preserving message padding to obscure actual content length.
 * Uses PKCS#7-style padding with deterministic pad bytes.
 *
 * Wire-compatible with Swift's MessagePadding.
 */
object MessagePadding {
    /** Standard block sizes for padding */
    val BLOCK_SIZES = intArrayOf(256, 512, 1024, 2048)

    /**
     * Add PKCS#7-style padding to reach target size.
     * All pad bytes are equal to the pad length value.
     */
    fun pad(data: ByteArray, toSize: Int): ByteArray {
        if (data.size >= toSize) return data
        val paddingNeeded = toSize - data.size
        if (paddingNeeded <= 0 || paddingNeeded > 255) return data

        val padded = ByteArray(toSize)
        data.copyInto(padded)
        // PKCS#7: all pad bytes equal to padding length
        for (i in data.size until toSize) {
            padded[i] = paddingNeeded.toByte()
        }
        return padded
    }

    /**
     * Remove PKCS#7 padding from data.
     * Validates that all trailing bytes match the pad length value.
     */
    fun unpad(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val last = data.last().toInt() and 0xFF
        if (last == 0 || last > data.size) return data

        val start = data.size - last
        // Verify PKCS#7: all last N bytes must equal pad length
        for (i in start until data.size) {
            if ((data[i].toInt() and 0xFF) != last) return data
        }
        return data.copyOfRange(0, start)
    }

    /**
     * Find optimal block size for data.
     * Accounts for encryption overhead (~16 bytes for AEAD tag).
     */
    fun optimalBlockSize(dataSize: Int): Int {
        val totalSize = dataSize + 16
        for (blockSize in BLOCK_SIZES) {
            if (totalSize <= blockSize) return blockSize
        }
        // Very large messages use original size (will be fragmented)
        return dataSize
    }
}
