// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.noise

import chat.bitchat.app.model.PeerID
import timber.log.Timber

/**
 * A Noise session interface.
 * Maps to Swift's NoiseSession protocol.
 */
interface NoiseSession {
    fun getState(): NoiseSessionState
    fun isEstablished(): Boolean
    fun startHandshake(): ByteArray
    fun processHandshakeMessage(message: ByteArray): ByteArray?
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
    fun getRemoteStaticPublicKey(): ByteArray?
    fun needsRenegotiation(): Boolean
    fun reset()
}

/**
 * Secure Noise Session implementation.
 * Port of Swift's SecureNoiseSession.
 *
 * Manages the full lifecycle of a Noise XX session:
 *   IDLE → HANDSHAKING → ESTABLISHED
 *
 * After establishment, provides encrypt/decrypt via transport cipher states.
 */
class SecureNoiseSession(
    private val peerID: PeerID,
    private val role: NoiseRole,
    private val localStaticPrivateKey: ByteArray,
    private val localStaticPublicKey: ByteArray
) : NoiseSession {

    private var state = NoiseSessionState.IDLE
    private var handshakeState: NoiseHandshakeState? = null

    // Transport cipher states (after handshake completes)
    private var sendCipher: NoiseCipherState? = null
    private var recvCipher: NoiseCipherState? = null

    // Remote static key (available after handshake)
    private var remoteStaticKey: ByteArray? = null

    // Session metadata
    private var establishedAt: Long = 0
    private var messagesSent: Long = 0
    private var messagesReceived: Long = 0

    // Renegotiation threshold (same as Swift)
    companion object {
        private const val REKEY_MESSAGE_THRESHOLD = 1_000_000L
        private const val REKEY_TIME_THRESHOLD_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    override fun getState(): NoiseSessionState = state

    override fun isEstablished(): Boolean = state == NoiseSessionState.ESTABLISHED

    /**
     * Start the handshake as initiator.
     * Returns the first handshake message (ephemeral key, 32 bytes).
     */
    override fun startHandshake(): ByteArray {
        handshakeState = NoiseHandshakeState(
            role = NoiseRole.INITIATOR,
            pattern = NoisePattern.XX,
            localStaticPrivateKey = localStaticPrivateKey,
            localStaticPublicKey = localStaticPublicKey
        )
        state = NoiseSessionState.HANDSHAKING

        return handshakeState!!.writeMessage()
    }

    /**
     * Process an incoming handshake message.
     * Returns a response message to send back, or null if handshake is complete.
     */
    override fun processHandshakeMessage(message: ByteArray): ByteArray? {
        if (state == NoiseSessionState.IDLE) {
            // First message received as responder
            handshakeState = NoiseHandshakeState(
                role = NoiseRole.RESPONDER,
                pattern = NoisePattern.XX,
                localStaticPrivateKey = localStaticPrivateKey,
                localStaticPublicKey = localStaticPublicKey
            )
            state = NoiseSessionState.HANDSHAKING
        }

        val hs = handshakeState ?: throw NoiseSessionError.NotEstablished()

        try {
            // Read the incoming message
            hs.readMessage(message)

            if (hs.isComplete) {
                // Handshake complete - derive transport keys
                finalizeHandshake()
                return null
            }

            // Write response
            val response = hs.writeMessage()

            if (hs.isComplete) {
                // Handshake complete after writing
                finalizeHandshake()
            }

            return response
        } catch (e: Exception) {
            state = NoiseSessionState.FAILED
            Timber.e(e, "Handshake failed for peer ${peerID.id}")
            throw e
        }
    }

    private fun finalizeHandshake() {
        val hs = handshakeState ?: return

        remoteStaticKey = hs.getRemoteStaticPublicKey()
        val (c1, c2) = hs.split()

        // Initiator sends with c1, receives with c2
        // Responder sends with c2, receives with c1
        if (role == NoiseRole.INITIATOR) {
            sendCipher = c1
            recvCipher = c2
        } else {
            sendCipher = c2
            recvCipher = c1
        }

        state = NoiseSessionState.ESTABLISHED
        establishedAt = System.currentTimeMillis()
        handshakeState = null // Clear handshake state

        Timber.d("Noise session established with ${peerID.id}")
    }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        if (!isEstablished()) throw NoiseSessionError.NotEstablished()
        val cipher = sendCipher ?: throw NoiseSessionError.EncryptionFailed()

        val result = cipher.encrypt(plaintext)
        messagesSent++
        return result
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        if (!isEstablished()) throw NoiseSessionError.NotEstablished()
        val cipher = recvCipher ?: throw NoiseSessionError.DecryptionFailed()

        val result = cipher.decrypt(ciphertext)
        messagesReceived++
        return result
    }

    override fun getRemoteStaticPublicKey(): ByteArray? = remoteStaticKey?.copyOf()

    override fun needsRenegotiation(): Boolean {
        if (!isEstablished()) return false
        val elapsed = System.currentTimeMillis() - establishedAt
        return messagesSent > REKEY_MESSAGE_THRESHOLD ||
            messagesReceived > REKEY_MESSAGE_THRESHOLD ||
            elapsed > REKEY_TIME_THRESHOLD_MS
    }

    override fun reset() {
        sendCipher?.clearSensitiveData()
        recvCipher?.clearSensitiveData()
        sendCipher = null
        recvCipher = null
        handshakeState = null
        remoteStaticKey?.fill(0)
        remoteStaticKey = null
        state = NoiseSessionState.IDLE
        messagesSent = 0
        messagesReceived = 0
        establishedAt = 0
    }
}
