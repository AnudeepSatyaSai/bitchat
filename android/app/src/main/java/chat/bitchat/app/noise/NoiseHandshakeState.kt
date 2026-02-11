// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.noise

import timber.log.Timber
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/**
 * Orchestrates the complete Noise XX handshake process.
 * This is the main interface for establishing encrypted sessions between peers.
 *
 * Pattern: Noise_XX_25519_ChaChaPoly_SHA256
 *
 * XX Handshake Flow (MUST match Swift exactly):
 * ```
 * Initiator                              Responder
 * ---------                              ---------
 * -> e                                   (ephemeral key)
 * <- e, ee, s, es                       (ephemeral, DH, static encrypted, DH)
 * -> s, se                              (static encrypted, DH)
 * ```
 *
 * Port of Swift's NoiseHandshakeState.
 */
class NoiseHandshakeState(
    private val role: NoiseRole,
    private val pattern: NoisePattern,
    private val localStaticPrivateKey: ByteArray,   // 32-byte Curve25519 private key
    private val localStaticPublicKey: ByteArray,     // 32-byte Curve25519 public key
    remoteStaticPublicKey: ByteArray? = null,
    prologue: ByteArray = ByteArray(0),
    predeterminedEphemeralKey: ByteArray? = null      // Test support only
) {
    private val symmetricState: NoiseSymmetricState
    private var messagePatterns: List<List<NoiseMessagePattern>>
    private var currentPattern = 0

    // Keys
    private var localEphemeralPrivate: ByteArray? = null
    private var localEphemeralPublic: ByteArray? = null
    private var remoteStaticPublic: ByteArray? = remoteStaticPublicKey?.copyOf()
    private var remoteEphemeralPublic: ByteArray? = null
    private var predeterminedEphemeral: ByteArray? = predeterminedEphemeralKey?.copyOf()

    val isComplete: Boolean get() = currentPattern >= messagePatterns.size

    /** Get the remote static public key (available after handshake step 2 or 3) */
    fun getRemoteStaticPublicKey(): ByteArray? = remoteStaticPublic?.copyOf()

    init {
        val protocolName = "Noise_${pattern.patternName}_25519_ChaChaPoly_SHA256"
        symmetricState = NoiseSymmetricState(protocolName)

        messagePatterns = pattern.messagePatterns.toMutableList()

        // Mix pre-message keys
        symmetricState.mixHash(prologue)

        when (pattern) {
            NoisePattern.XX -> { /* No pre-message keys */ }
            NoisePattern.IK, NoisePattern.NK -> {
                if (role == NoiseRole.INITIATOR && remoteStaticPublic != null) {
                    symmetricState.getHandshakeHash()
                    symmetricState.mixHash(remoteStaticPublic!!)
                }
            }
        }
    }

    /**
     * Write the next handshake message.
     * Returns the message bytes to send to the peer.
     */
    fun writeMessage(payload: ByteArray = ByteArray(0)): ByteArray {
        if (currentPattern >= messagePatterns.size) {
            throw NoiseError.HandshakeComplete()
        }

        val messageBuffer = mutableListOf<Byte>()
        val patterns = messagePatterns[currentPattern]

        for (pat in patterns) {
            when (pat) {
                NoiseMessagePattern.E -> {
                    // Generate ephemeral key
                    val ephemeral = predeterminedEphemeral?.let {
                        predeterminedEphemeral = null
                        it
                    } ?: generateX25519PrivateKey()

                    localEphemeralPrivate = ephemeral
                    localEphemeralPublic = deriveX25519PublicKey(ephemeral)

                    messageBuffer.addAll(localEphemeralPublic!!.toList())
                    symmetricState.mixHash(localEphemeralPublic!!)
                }

                NoiseMessagePattern.S -> {
                    val encrypted = symmetricState.encryptAndHash(localStaticPublicKey)
                    messageBuffer.addAll(encrypted.toList())
                }

                NoiseMessagePattern.EE -> {
                    val shared = x25519DH(localEphemeralPrivate!!, remoteEphemeralPublic!!)
                    symmetricState.mixKey(shared)
                    shared.fill(0) // Clear sensitive data
                }

                NoiseMessagePattern.ES -> {
                    val shared = if (role == NoiseRole.INITIATOR) {
                        x25519DH(localEphemeralPrivate!!, remoteStaticPublic!!)
                    } else {
                        x25519DH(localStaticPrivateKey, remoteEphemeralPublic!!)
                    }
                    symmetricState.mixKey(shared)
                    shared.fill(0)
                }

                NoiseMessagePattern.SE -> {
                    val shared = if (role == NoiseRole.INITIATOR) {
                        x25519DH(localStaticPrivateKey, remoteEphemeralPublic!!)
                    } else {
                        x25519DH(localEphemeralPrivate!!, remoteStaticPublic!!)
                    }
                    symmetricState.mixKey(shared)
                    shared.fill(0)
                }

                NoiseMessagePattern.SS -> {
                    val shared = x25519DH(localStaticPrivateKey, remoteStaticPublic!!)
                    symmetricState.mixKey(shared)
                    shared.fill(0)
                }
            }
        }

        // Encrypt payload
        val encryptedPayload = symmetricState.encryptAndHash(payload)
        messageBuffer.addAll(encryptedPayload.toList())

        currentPattern++
        return messageBuffer.toByteArray()
    }

    /**
     * Read and process an incoming handshake message.
     * Returns the decrypted payload.
     */
    fun readMessage(message: ByteArray): ByteArray {
        if (currentPattern >= messagePatterns.size) {
            throw NoiseError.HandshakeComplete()
        }

        var buffer = message
        val patterns = messagePatterns[currentPattern]

        for (pat in patterns) {
            when (pat) {
                NoiseMessagePattern.E -> {
                    if (buffer.size < 32) throw NoiseError.InvalidMessage()
                    val ephemeralData = buffer.copyOfRange(0, 32)
                    buffer = buffer.copyOfRange(32, buffer.size)

                    remoteEphemeralPublic = validatePublicKey(ephemeralData)
                    symmetricState.mixHash(ephemeralData)
                }

                NoiseMessagePattern.S -> {
                    val keyLength = if (symmetricState.hasCipherKey()) 48 else 32
                    if (buffer.size < keyLength) throw NoiseError.InvalidMessage()
                    val staticData = buffer.copyOfRange(0, keyLength)
                    buffer = buffer.copyOfRange(keyLength, buffer.size)

                    try {
                        val decrypted = symmetricState.decryptAndHash(staticData)
                        remoteStaticPublic = validatePublicKey(decrypted)
                    } catch (e: Exception) {
                        Timber.e("Authentication failed during handshake")
                        throw NoiseError.AuthenticationFailure()
                    }
                }

                NoiseMessagePattern.EE, NoiseMessagePattern.ES,
                NoiseMessagePattern.SE, NoiseMessagePattern.SS -> {
                    performDHOperation(pat)
                }
            }
        }

        // Decrypt payload
        val payload = symmetricState.decryptAndHash(buffer)
        currentPattern++
        return payload
    }

    /** Split into transport cipher states after handshake completes */
    fun split(): Pair<NoiseCipherState, NoiseCipherState> {
        return symmetricState.split(useExtractedNonce = true)
    }

    fun getHandshakeHash(): ByteArray = symmetricState.getHandshakeHash()

    // ============ Private DH Operations ============

    private fun performDHOperation(pattern: NoiseMessagePattern) {
        when (pattern) {
            NoiseMessagePattern.EE -> {
                val shared = x25519DH(localEphemeralPrivate!!, remoteEphemeralPublic!!)
                symmetricState.mixKey(shared)
                shared.fill(0)
            }

            NoiseMessagePattern.ES -> {
                val shared = if (role == NoiseRole.INITIATOR) {
                    x25519DH(localEphemeralPrivate!!, remoteStaticPublic!!)
                } else {
                    x25519DH(localStaticPrivateKey, remoteEphemeralPublic!!)
                }
                symmetricState.mixKey(shared)
                shared.fill(0)
            }

            NoiseMessagePattern.SE -> {
                val shared = if (role == NoiseRole.INITIATOR) {
                    x25519DH(localStaticPrivateKey, remoteEphemeralPublic!!)
                } else {
                    x25519DH(localEphemeralPrivate!!, remoteStaticPublic!!)
                }
                symmetricState.mixKey(shared)
                shared.fill(0)
            }

            NoiseMessagePattern.SS -> {
                val shared = x25519DH(localStaticPrivateKey, remoteStaticPublic!!)
                symmetricState.mixKey(shared)
                shared.fill(0)
            }

            else -> { /* E, S handled separately */ }
        }
    }

    // ============ Curve25519 Operations (via BouncyCastle) ============

    companion object {
        /** Generate a random X25519 private key (32 bytes) */
        fun generateX25519PrivateKey(): ByteArray {
            val params = X25519PrivateKeyParameters(java.security.SecureRandom())
            return params.encoded
        }

        /** Derive X25519 public key from private key (32 bytes → 32 bytes) */
        fun deriveX25519PublicKey(privateKey: ByteArray): ByteArray {
            val privateParams = X25519PrivateKeyParameters(privateKey, 0)
            return privateParams.generatePublicKey().encoded
        }

        /** X25519 Diffie-Hellman key agreement */
        fun x25519DH(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
            val agreement = X25519Agreement()
            val privateParams = X25519PrivateKeyParameters(privateKey, 0)
            agreement.init(privateParams)

            val publicParams = X25519PublicKeyParameters(publicKey, 0)
            val sharedSecret = ByteArray(agreement.agreementSize)
            agreement.calculateAgreement(publicParams, sharedSecret, 0)
            return sharedSecret
        }

        /** Validate that a 32-byte buffer is a valid X25519 public key */
        fun validatePublicKey(data: ByteArray): ByteArray {
            if (data.size != 32) throw NoiseError.InvalidPublicKey()
            // Check for all zeros (invalid key)
            if (data.all { it == 0.toByte() }) throw NoiseError.InvalidPublicKey()
            // BouncyCastle will perform additional validation
            try {
                X25519PublicKeyParameters(data, 0)
            } catch (e: Exception) {
                throw NoiseError.InvalidPublicKey()
            }
            return data.copyOf()
        }

        /** Generate a new X25519 key pair. Returns (privateKey, publicKey) both 32 bytes */
        fun generateX25519KeyPair(): Pair<ByteArray, ByteArray> {
            val privateKey = generateX25519PrivateKey()
            val publicKey = deriveX25519PublicKey(privateKey)
            return Pair(privateKey, publicKey)
        }
    }
}
