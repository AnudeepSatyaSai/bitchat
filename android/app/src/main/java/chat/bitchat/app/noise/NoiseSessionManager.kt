// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.noise

import chat.bitchat.app.model.PeerID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Manages all Noise sessions for connected peers.
 * Port of Swift's NoiseSessionManager.
 *
 * Thread-safe via Kotlin Mutex (replaces Swift's concurrent DispatchQueue with barrier).
 */
class NoiseSessionManager(
    private val localStaticPrivateKey: ByteArray,
    private val localStaticPublicKey: ByteArray
) {
    private val sessions = mutableMapOf<PeerID, NoiseSession>()
    private val mutex = Mutex()

    // Callbacks
    var onSessionEstablished: ((PeerID, ByteArray) -> Unit)? = null
    var onSessionFailed: ((PeerID, Exception) -> Unit)? = null

    // ============ Session Management ============

    suspend fun getSession(peerID: PeerID): NoiseSession? = mutex.withLock {
        sessions[peerID]
    }

    suspend fun removeSession(peerID: PeerID) = mutex.withLock {
        sessions.remove(peerID)?.reset()
    }

    suspend fun removeAllSessions() = mutex.withLock {
        sessions.values.forEach { it.reset() }
        sessions.clear()
    }

    // ============ Handshake ============

    /**
     * Initiate handshake with a peer.
     * Returns the first handshake message bytes.
     */
    suspend fun initiateHandshake(peerID: PeerID): ByteArray = mutex.withLock {
        // Check for existing established session
        sessions[peerID]?.let { existing ->
            if (existing.isEstablished()) {
                throw NoiseSessionError.AlreadyEstablished()
            }
            // Remove non-established session
            sessions.remove(peerID)
        }

        // Create new initiator session
        val session = SecureNoiseSession(
            peerID = peerID,
            role = NoiseRole.INITIATOR,
            localStaticPrivateKey = localStaticPrivateKey,
            localStaticPublicKey = localStaticPublicKey
        )
        sessions[peerID] = session

        try {
            session.startHandshake()
        } catch (e: Exception) {
            sessions.remove(peerID)
            Timber.e(e, "Handshake initiation failed for ${peerID.id}")
            throw e
        }
    }

    /**
     * Handle an incoming handshake message from a peer.
     * Returns a response message, or null if handshake is complete.
     */
    suspend fun handleIncomingHandshake(peerID: PeerID, message: ByteArray): ByteArray? = mutex.withLock {
        var shouldCreateNew = false
        var existingSession: NoiseSession? = null

        sessions[peerID]?.let { existing ->
            if (existing.isEstablished()) {
                // Peer cleared session — accept re-handshake
                Timber.i("Accepting handshake from ${peerID.id} despite existing session")
                sessions.remove(peerID)
                shouldCreateNew = true
            } else {
                // Mid-handshake: if we receive a new initiation (32 bytes = ephemeral key),
                // reset and start fresh
                if (existing.getState() == NoiseSessionState.HANDSHAKING && message.size == 32) {
                    sessions.remove(peerID)
                    shouldCreateNew = true
                } else {
                    existingSession = existing
                }
            }
        } ?: run {
            shouldCreateNew = true
        }

        val session: NoiseSession = if (shouldCreateNew) {
            SecureNoiseSession(
                peerID = peerID,
                role = NoiseRole.RESPONDER,
                localStaticPrivateKey = localStaticPrivateKey,
                localStaticPublicKey = localStaticPublicKey
            ).also { sessions[peerID] = it }
        } else {
            existingSession!!
        }

        try {
            val response = session.processHandshakeMessage(message)

            // Check if session established
            if (session.isEstablished()) {
                session.getRemoteStaticPublicKey()?.let { remoteKey ->
                    onSessionEstablished?.invoke(peerID, remoteKey)
                }
            }

            response
        } catch (e: Exception) {
            sessions.remove(peerID)
            onSessionFailed?.invoke(peerID, e as? Exception ?: Exception(e))
            Timber.e(e, "Handshake failed for ${peerID.id}")
            throw e
        }
    }

    // ============ Encryption / Decryption ============

    suspend fun encrypt(plaintext: ByteArray, peerID: PeerID): ByteArray {
        val session = getSession(peerID) ?: throw NoiseSessionError.SessionNotFound()
        return session.encrypt(plaintext)
    }

    suspend fun decrypt(ciphertext: ByteArray, peerID: PeerID): ByteArray {
        val session = getSession(peerID) ?: throw NoiseSessionError.SessionNotFound()
        return session.decrypt(ciphertext)
    }

    // ============ Key Management ============

    suspend fun getRemoteStaticKey(peerID: PeerID): ByteArray? {
        return getSession(peerID)?.getRemoteStaticPublicKey()
    }

    suspend fun getSessionsNeedingRekey(): List<PeerID> = mutex.withLock {
        sessions.entries
            .filter { (_, session) ->
                session is SecureNoiseSession &&
                    session.isEstablished() &&
                    session.needsRenegotiation()
            }
            .map { it.key }
    }

    suspend fun initiateRekey(peerID: PeerID): ByteArray {
        removeSession(peerID)
        return initiateHandshake(peerID)
    }
}
