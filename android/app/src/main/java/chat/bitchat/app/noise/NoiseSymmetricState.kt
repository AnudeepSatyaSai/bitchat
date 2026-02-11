// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.noise

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Manages the symmetric cryptographic state during Noise handshakes.
 * Responsible for key derivation, protocol name hashing, and maintaining
 * the chaining key that provides key separation between handshake messages.
 *
 * Port of Swift's NoiseSymmetricState — identical HKDF and hash operations.
 */
class NoiseSymmetricState(protocolName: String) {
    private val cipherState = NoiseCipherState()
    private var chainingKey: ByteArray
    private var hash: ByteArray

    init {
        val nameData = protocolName.toByteArray(Charsets.UTF_8)
        hash = if (nameData.size <= 32) {
            nameData + ByteArray(32 - nameData.size)
        } else {
            sha256(nameData)
        }
        chainingKey = hash.copyOf()
    }

    fun mixKey(inputKeyMaterial: ByteArray) {
        val output = hkdf(chainingKey, inputKeyMaterial, 2)
        chainingKey = output[0]
        cipherState.initializeKey(output[1])
    }

    fun mixHash(data: ByteArray) {
        hash = sha256(hash + data)
    }

    fun mixKeyAndHash(inputKeyMaterial: ByteArray) {
        val output = hkdf(chainingKey, inputKeyMaterial, 3)
        chainingKey = output[0]
        mixHash(output[1])
        cipherState.initializeKey(output[2])
    }

    fun getHandshakeHash(): ByteArray = hash.copyOf()

    fun hasCipherKey(): Boolean = cipherState.hasKey()

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        return if (cipherState.hasKey()) {
            val ciphertext = cipherState.encrypt(plaintext, hash)
            mixHash(ciphertext)
            ciphertext
        } else {
            mixHash(plaintext)
            plaintext
        }
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        return if (cipherState.hasKey()) {
            val plaintext = cipherState.decrypt(ciphertext, hash)
            mixHash(ciphertext)
            plaintext
        } else {
            mixHash(ciphertext)
            ciphertext
        }
    }

    /**
     * Split the symmetric state into two cipher states for transport.
     * Called after handshake completes.
     */
    fun split(useExtractedNonce: Boolean): Pair<NoiseCipherState, NoiseCipherState> {
        val output = hkdf(chainingKey, ByteArray(0), 2)

        val c1 = NoiseCipherState(key = output[0], useExtractedNonce = useExtractedNonce)
        val c2 = NoiseCipherState(key = output[1], useExtractedNonce = useExtractedNonce)

        // BCH-01-010: Clear symmetric state after split per Noise spec
        clearSensitiveData()

        return Pair(c1, c2)
    }

    /** BCH-01-010: Securely clear sensitive cryptographic state */
    fun clearSensitiveData() {
        chainingKey.fill(0)
        hash.fill(0)
        cipherState.clearSensitiveData()
    }

    // ============ Cryptographic Primitives ============

    /** SHA-256 hash */
    private fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    /**
     * HKDF implementation using HMAC-SHA256.
     * Matches Swift's HKDF exactly:
     *   tempKey = HMAC(chainingKey, inputKeyMaterial)
     *   output[i] = HMAC(tempKey, output[i-1] || byte(i))
     */
    private fun hkdf(chainingKey: ByteArray, inputKeyMaterial: ByteArray, numOutputs: Int): List<ByteArray> {
        val tempKey = hmacSha256(chainingKey, inputKeyMaterial)

        val outputs = mutableListOf<ByteArray>()
        var currentOutput = ByteArray(0)

        for (i in 1..numOutputs) {
            currentOutput = hmacSha256(tempKey, currentOutput + byteArrayOf(i.toByte()))
            outputs.add(currentOutput)
        }

        return outputs
    }

    /** HMAC-SHA256 */
    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
