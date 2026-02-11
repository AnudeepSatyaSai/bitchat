// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.noise

import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Manages symmetric encryption state for Noise protocol sessions.
 * Handles ChaCha20-Poly1305 AEAD encryption with automatic nonce management
 * and replay protection using a sliding window algorithm.
 *
 * Port of Swift's NoiseCipherState — identical nonce layout, same AEAD construction.
 *
 * Nonce format for ChaCha20-Poly1305 (12 bytes):
 *   [4 bytes zeros] [8 bytes counter little-endian]
 */
class NoiseCipherState(
    private var key: ByteArray? = null,
    private val useExtractedNonce: Boolean = false
) {
    companion object {
        private const val NONCE_SIZE_BYTES = 4
        private const val REPLAY_WINDOW_SIZE = 1024
        private const val REPLAY_WINDOW_BYTES = REPLAY_WINDOW_SIZE / 8 // 128 bytes
        private const val HIGH_NONCE_WARNING_THRESHOLD = 1_000_000_000L
        private const val KEY_SIZE = 32
        private const val TAG_SIZE = 16
        private const val CHACHA20_NONCE_SIZE = 12
    }

    private var nonce: Long = 0
    private var highestReceivedNonce: Long = 0
    private var replayWindow = ByteArray(REPLAY_WINDOW_BYTES)

    fun initializeKey(key: ByteArray) {
        require(key.size == KEY_SIZE) { "Key must be $KEY_SIZE bytes" }
        this.key = key.copyOf()
        this.nonce = 0
    }

    fun hasKey(): Boolean = key != null

    // ============ Sliding Window Replay Protection ============

    /**
     * Check if nonce is valid for replay protection.
     * BCH-01-010: Uses safe arithmetic to prevent integer overflow.
     */
    private fun isValidNonce(receivedNonce: Long): Boolean {
        // Safe overflow check
        if (highestReceivedNonce >= REPLAY_WINDOW_SIZE &&
            receivedNonce <= highestReceivedNonce - REPLAY_WINDOW_SIZE) {
            return false // Too old, outside window
        }

        if (receivedNonce > highestReceivedNonce) {
            return true // Always accept newer nonces
        }

        val offset = (highestReceivedNonce - receivedNonce).toInt()
        val byteIndex = offset / 8
        val bitIndex = offset % 8
        return (replayWindow[byteIndex].toInt() and (1 shl bitIndex)) == 0 // Not yet seen
    }

    /** Mark nonce as seen in replay window */
    private fun markNonceAsSeen(receivedNonce: Long) {
        if (receivedNonce > highestReceivedNonce) {
            val shift = (receivedNonce - highestReceivedNonce).toInt()

            if (shift >= REPLAY_WINDOW_SIZE) {
                replayWindow = ByteArray(REPLAY_WINDOW_BYTES)
            } else {
                // Shift window right by `shift` bits
                for (i in REPLAY_WINDOW_BYTES - 1 downTo 0) {
                    val sourceByteIndex = i - shift / 8
                    var newByte = 0

                    if (sourceByteIndex >= 0) {
                        newByte = (replayWindow[sourceByteIndex].toInt() and 0xFF) ushr (shift % 8)
                        if (sourceByteIndex > 0 && shift % 8 != 0) {
                            newByte = newByte or
                                ((replayWindow[sourceByteIndex - 1].toInt() and 0xFF) shl (8 - shift % 8))
                        }
                    }

                    replayWindow[i] = (newByte and 0xFF).toByte()
                }
            }

            highestReceivedNonce = receivedNonce
            replayWindow[0] = (replayWindow[0].toInt() or 1).toByte() // Mark most recent as seen
        } else {
            val offset = (highestReceivedNonce - receivedNonce).toInt()
            val byteIndex = offset / 8
            val bitIndex = offset % 8
            replayWindow[byteIndex] = (replayWindow[byteIndex].toInt() or (1 shl bitIndex)).toByte()
        }
    }

    /**
     * Extract nonce from combined payload <nonce><ciphertext>.
     * Returns (nonce, ciphertext) or null if invalid.
     */
    private fun extractNonceFromPayload(combinedPayload: ByteArray): Pair<Long, ByteArray>? {
        if (combinedPayload.size < NONCE_SIZE_BYTES) return null

        // Extract 4-byte nonce (big-endian)
        var extractedNonce: Long = 0
        for (i in 0 until NONCE_SIZE_BYTES) {
            extractedNonce = (extractedNonce shl 8) or (combinedPayload[i].toLong() and 0xFF)
        }

        val ciphertext = combinedPayload.copyOfRange(NONCE_SIZE_BYTES, combinedPayload.size)
        return Pair(extractedNonce, ciphertext)
    }

    /** Convert nonce to 4-byte array (big-endian) */
    private fun nonceToBytes(nonce: Long): ByteArray {
        val bytes = ByteArray(NONCE_SIZE_BYTES)
        val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(nonce)
        // Copy last 4 bytes from the 8-byte long
        buf.array().copyInto(bytes, 0, 4, 8)
        return bytes
    }

    /**
     * Build the 12-byte ChaCha20 nonce from a counter value.
     * Format: [4 bytes zeros][8 bytes counter little-endian]
     * Matches Swift: nonceData[4..<12] = counter.littleEndian
     */
    private fun buildChaChaNonce(counter: Long): ByteArray {
        val nonceData = ByteArray(CHACHA20_NONCE_SIZE)
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(counter)
        buf.array().copyInto(nonceData, 4, 0, 8)
        return nonceData
    }

    // ============ Encrypt / Decrypt ============

    /**
     * Encrypt plaintext using ChaCha20-Poly1305.
     * Output format depends on useExtractedNonce:
     *   true:  [4-byte nonce][ciphertext][16-byte tag]
     *   false: [ciphertext][16-byte tag]
     */
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray {
        val k = key ?: throw NoiseError.UninitializedCipher()
        val currentNonce = nonce

        // Check 4-byte nonce limit
        if (currentNonce > UInt.MAX_VALUE.toLong() - 1) {
            throw NoiseError.NonceExceeded()
        }

        val chaChaNonce = buildChaChaNonce(currentNonce)

        // ChaCha20-Poly1305 encryption
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        val keySpec = SecretKeySpec(k, "ChaCha20")
        val ivSpec = IvParameterSpec(chaChaNonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        if (associatedData.isNotEmpty()) {
            cipher.updateAAD(associatedData)
        }
        val sealed = cipher.doFinal(plaintext) // Returns ciphertext + tag appended

        // Increment nonce
        nonce++

        // Build combined payload
        return if (useExtractedNonce) {
            val nonceBytes = nonceToBytes(currentNonce)
            nonceBytes + sealed
        } else {
            sealed
        }
    }

    /**
     * Decrypt ciphertext using ChaCha20-Poly1305.
     * Input format depends on useExtractedNonce:
     *   true:  [4-byte nonce][ciphertext][16-byte tag]
     *   false: [ciphertext][16-byte tag]
     */
    fun decrypt(ciphertext: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray {
        val k = key ?: throw NoiseError.UninitializedCipher()
        if (ciphertext.size < TAG_SIZE) throw NoiseError.InvalidCiphertext()

        val decryptionNonce: Long
        val actualCiphertext: ByteArray

        if (useExtractedNonce) {
            val extracted = extractNonceFromPayload(ciphertext)
                ?: throw NoiseError.InvalidCiphertext()

            if (!isValidNonce(extracted.first)) {
                Timber.d("Replay attack detected: nonce ${extracted.first} rejected")
                throw NoiseError.ReplayDetected()
            }

            decryptionNonce = extracted.first
            actualCiphertext = extracted.second
        } else {
            decryptionNonce = nonce
            actualCiphertext = ciphertext
        }

        val chaChaNonce = buildChaChaNonce(decryptionNonce)

        try {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            val keySpec = SecretKeySpec(k, "ChaCha20")
            val ivSpec = IvParameterSpec(chaChaNonce)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            if (associatedData.isNotEmpty()) {
                cipher.updateAAD(associatedData)
            }
            val plaintext = cipher.doFinal(actualCiphertext)

            // BCH-01-010: Atomic nonce state update
            if (useExtractedNonce) {
                markNonceAsSeen(decryptionNonce)
            }
            nonce++

            if (decryptionNonce > HIGH_NONCE_WARNING_THRESHOLD) {
                Timber.w("High nonce value detected: $decryptionNonce - consider rekeying")
            }

            return plaintext
        } catch (e: Exception) {
            Timber.d("Decrypt failed: ${e.message} for nonce $decryptionNonce")
            throw e
        }
    }

    /** Securely clear sensitive cryptographic data from memory */
    fun clearSensitiveData() {
        key?.let { k ->
            k.fill(0)
            key = null
        }
        nonce = 0
        highestReceivedNonce = 0
        replayWindow.fill(0)
    }
}
