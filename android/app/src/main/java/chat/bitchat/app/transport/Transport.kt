// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.transport

import chat.bitchat.app.model.*
import kotlinx.coroutines.flow.StateFlow

/**
 * Transport interface — the abstract communication layer.
 * Port of Swift's Transport protocol.
 *
 * Both BleTransport and WiFiAwareTransport implement this interface.
 * The upper layers (MeshRouter, ChatViewModel) are transport-agnostic.
 */
interface Transport {

    /** Unique name of this transport (e.g., "ble", "wifi-aware") */
    val name: String

    /** Whether this transport is currently available/running */
    val isAvailable: Boolean

    /** Our own peer ID */
    val myPeerID: PeerID

    /** Our current nickname */
    var myNickname: String

    /** Delegate for receiving events */
    var delegate: BitchatDelegate?

    /** Observable peer snapshots for services */
    val peerSnapshots: StateFlow<List<TransportPeerSnapshot>>

    // ============ Lifecycle ============

    fun startServices()
    fun stopServices()
    fun emergencyDisconnectAll()

    // ============ Connectivity ============

    fun isPeerConnected(peerID: PeerID): Boolean
    fun isPeerReachable(peerID: PeerID): Boolean
    fun peerNickname(peerID: PeerID): String?
    fun getPeerNicknames(): Map<PeerID, String>

    // ============ Noise Protocol ============

    fun getFingerprint(peerID: PeerID): String?
    fun getNoiseSessionState(peerID: PeerID): LazyHandshakeState
    fun triggerHandshake(peerID: PeerID)
    fun getNoiseService(): NoiseEncryptionServiceInterface

    // ============ Messaging ============

    fun sendMessage(content: String, mentions: List<String>)
    fun sendMessage(content: String, mentions: List<String>, messageID: String, timestamp: java.util.Date)
    fun sendPrivateMessage(content: String, to: PeerID, recipientNickname: String, messageID: String)
    fun sendReadReceipt(receipt: ReadReceipt, to: PeerID)
    fun sendFavoriteNotification(to: PeerID, isFavorite: Boolean)
    fun sendBroadcastAnnounce()
    fun sendDeliveryAck(messageID: String, to: PeerID)
    fun sendFileBroadcast(packet: ByteArray, transferId: String)
    fun sendFilePrivate(packet: ByteArray, to: PeerID, transferId: String)
    fun cancelTransfer(transferId: String)

    // ============ QR Verification ============

    fun sendVerifyChallenge(to: PeerID, noiseKeyHex: String, nonceA: ByteArray)
    fun sendVerifyResponse(to: PeerID, noiseKeyHex: String, nonceA: ByteArray)

    // ============ Pending File Management ============

    fun acceptPendingFile(id: String): String?  // Returns file path
    fun declinePendingFile(id: String)

    // ============ Raw Data (for transport-level access) ============

    /**
     * Send raw bytes to a specific peer.
     * Returns true if queued/sent successfully.
     */
    fun sendRawData(peerID: PeerID, data: ByteArray): Boolean

    /**
     * Broadcast raw bytes to all connected peers.
     */
    fun broadcastRawData(data: ByteArray)
}

/**
 * Snapshot of a peer's state from the transport layer.
 * Used by non-UI services for peer tracking.
 */
data class TransportPeerSnapshot(
    val peerID: PeerID,
    val nickname: String,
    val isConnected: Boolean,
    val isReachable: Boolean,
    val lastSeen: Long  // epoch millis
)

/**
 * Delegate for receiving events from the transport layer.
 * Port of Swift's BitchatDelegate protocol.
 */
interface BitchatDelegate {
    fun didReceiveMessage(message: BitchatMessage)
    fun didConnectToPeer(peerID: PeerID)
    fun didDisconnectFromPeer(peerID: PeerID)
    fun didUpdatePeerList(peers: List<PeerID>)
    fun isFavorite(fingerprint: String): Boolean
    fun didUpdateMessageDeliveryStatus(messageID: String, status: DeliveryStatus)
    fun didReceiveNoisePayload(from: PeerID, type: NoisePayloadType, payload: ByteArray, timestamp: java.util.Date)
    fun didUpdateTransportState(transport: String, state: TransportState)
    fun didReceivePublicMessage(from: PeerID, nickname: String, content: String, timestamp: java.util.Date, messageID: String?)
}

/**
 * Transport state — replaces Swift's CBManagerState with transport-agnostic state.
 */
enum class TransportState {
    UNKNOWN,
    UNSUPPORTED,
    UNAUTHORIZED,
    POWERED_OFF,
    POWERED_ON,
    RESETTING
}

/**
 * Interface for the Noise encryption service.
 * Abstraction so transports don't depend on specific implementations.
 */
interface NoiseEncryptionServiceInterface {
    suspend fun getSessionManager(): NoiseSessionManagerInterface
}

interface NoiseSessionManagerInterface {
    suspend fun initiateHandshake(peerID: PeerID): ByteArray
    suspend fun handleIncomingHandshake(peerID: PeerID, message: ByteArray): ByteArray?
    suspend fun encrypt(plaintext: ByteArray, peerID: PeerID): ByteArray
    suspend fun decrypt(ciphertext: ByteArray, peerID: PeerID): ByteArray
    suspend fun getRemoteStaticKey(peerID: PeerID): ByteArray?
    suspend fun removeSession(peerID: PeerID)
}
