// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.transport

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import chat.bitchat.app.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.util.Date

/**
 * Intelligent Transport Selector.
 *
 * Manages both BLE and Wi-Fi Aware transports, routing messages through
 * the optimal transport based on availability, message size, battery level,
 * and connection state.
 *
 * Selection Rules (STRICT):
 * 1. BLE = default discovery (always on, low power)
 * 2. Wi-Fi Aware = preferred data path when available
 * 3. Large messages (>200 bytes) → Wi-Fi Aware preferred
 * 4. Low battery (<15%) → BLE preferred (lower power)
 * 5. Noise sessions survive transport switching (transport-agnostic identity)
 */
class TransportSelector(
    private val context: Context,
    private val transports: List<Transport>
) : Transport {

    companion object {
        private const val LARGE_MESSAGE_THRESHOLD = 200
        private const val LOW_BATTERY_THRESHOLD = 15
        private const val TAG = "TransportSelector"
    }

    override val name: String = "multi"

    override val isAvailable: Boolean
        get() = transports.any { it.isAvailable }

    override val myPeerID: PeerID
        get() = transports.first().myPeerID

    override var myNickname: String = "anon"
        set(value) {
            field = value
            transports.forEach { it.myNickname = value }
        }

    override var delegate: BitchatDelegate? = null
        set(value) {
            field = value
            transports.forEach { it.delegate = value }
        }

    // Merged peer snapshots from all transports
    private val _peerSnapshots = MutableStateFlow<List<TransportPeerSnapshot>>(emptyList())
    override val peerSnapshots: StateFlow<List<TransportPeerSnapshot>> = _peerSnapshots.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ============ Transport Selection ============

    /**
     * Get the best transport for a specific peer.
     * Prefers Wi-Fi Aware when available and appropriate.
     */
    private fun bestTransport(peerID: PeerID, dataSize: Int = 0): Transport {
        val batteryLevel = getBatteryLevel()
        val bleTransport = transports.firstOrNull { it.name == "ble" }
        val wifiAwareTransport = transports.firstOrNull { it.name == "wifi-aware" }

        // Decision logging
        val decision = StringBuilder("Transport decision for ${peerID.id}: ")

        // Rule 4: Low battery → prefer BLE
        if (batteryLevel in 1 until LOW_BATTERY_THRESHOLD) {
            if (bleTransport?.isPeerReachable(peerID) == true) {
                decision.append("BLE (low battery: $batteryLevel%)")
                Timber.d(decision.toString())
                return bleTransport
            }
        }

        // Rule 3: Large messages → prefer Wi-Fi Aware
        if (dataSize > LARGE_MESSAGE_THRESHOLD) {
            if (wifiAwareTransport?.isAvailable == true && wifiAwareTransport.isPeerConnected(peerID)) {
                decision.append("Wi-Fi Aware (large message: $dataSize bytes)")
                Timber.d(decision.toString())
                return wifiAwareTransport
            }
        }

        // Rule 2: Prefer Wi-Fi Aware when connected
        if (wifiAwareTransport?.isAvailable == true && wifiAwareTransport.isPeerConnected(peerID)) {
            decision.append("Wi-Fi Aware (preferred data path)")
            Timber.d(decision.toString())
            return wifiAwareTransport
        }

        // Rule 1: Default to BLE
        if (bleTransport?.isPeerReachable(peerID) == true) {
            decision.append("BLE (default)")
            Timber.d(decision.toString())
            return bleTransport
        }

        // Fallback: any available transport that has the peer
        for (transport in transports) {
            if (transport.isPeerReachable(peerID)) {
                decision.append("${transport.name} (fallback)")
                Timber.d(decision.toString())
                return transport
            }
        }

        decision.append("BLE (no peer route, broadcasting)")
        Timber.d(decision.toString())
        return bleTransport ?: transports.first()
    }

    /** Get the best transport for broadcasting (uses all available) */
    private fun broadcastTransports(): List<Transport> =
        transports.filter { it.isAvailable }

    private fun getBatteryLevel(): Int {
        val batteryStatus = context.registerReceiver(null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100) / scale else -1
    }

    // ============ Lifecycle ============

    override fun startServices() {
        Timber.i("TransportSelector starting all transports...")
        transports.forEach { it.startServices() }
        startPeerMerging()
    }

    override fun stopServices() {
        Timber.i("TransportSelector stopping all transports...")
        scope.coroutineContext.cancelChildren()
        transports.forEach { it.stopServices() }
    }

    override fun emergencyDisconnectAll() {
        transports.forEach { it.emergencyDisconnectAll() }
    }

    // ============ Peer Merging ============

    /**
     * Merge peer snapshots from all transports.
     * Same peer seen on multiple transports = single entry (transport-agnostic identity).
     */
    private fun startPeerMerging() {
        scope.launch {
            // Collect from all transport flows
            combine(transports.map { it.peerSnapshots }) { snapshots ->
                // Merge: same PeerID from multiple transports → take most connected one
                val merged = mutableMapOf<PeerID, TransportPeerSnapshot>()
                for (snapshot in snapshots.flatMap { it.toList() }) {
                    val existing = merged[snapshot.peerID]
                    if (existing == null || (!existing.isConnected && snapshot.isConnected) ||
                        snapshot.lastSeen > existing.lastSeen) {
                        merged[snapshot.peerID] = snapshot
                    }
                }
                merged.values.toList()
            }.collect { merged ->
                _peerSnapshots.value = merged
            }
        }
    }

    // ============ Peer Queries ============

    override fun isPeerConnected(peerID: PeerID): Boolean =
        transports.any { it.isPeerConnected(peerID) }

    override fun isPeerReachable(peerID: PeerID): Boolean =
        transports.any { it.isPeerReachable(peerID) }

    override fun peerNickname(peerID: PeerID): String? =
        transports.firstNotNullOfOrNull { it.peerNickname(peerID) }

    override fun getPeerNicknames(): Map<PeerID, String> {
        val merged = mutableMapOf<PeerID, String>()
        transports.forEach { merged.putAll(it.getPeerNicknames()) }
        return merged
    }

    override fun getFingerprint(peerID: PeerID): String? =
        transports.firstNotNullOfOrNull { it.getFingerprint(peerID) }

    override fun getNoiseSessionState(peerID: PeerID): LazyHandshakeState =
        transports.firstNotNullOfOrNull {
            val state = it.getNoiseSessionState(peerID)
            if (state != LazyHandshakeState.None) state else null
        } ?: LazyHandshakeState.None

    override fun triggerHandshake(peerID: PeerID) {
        bestTransport(peerID).triggerHandshake(peerID)
    }

    override fun getNoiseService(): NoiseEncryptionServiceInterface =
        transports.first().getNoiseService()

    // ============ Messaging (transport-selected) ============

    override fun sendMessage(content: String, mentions: List<String>) {
        broadcastTransports().forEach { it.sendMessage(content, mentions) }
    }

    override fun sendMessage(content: String, mentions: List<String>, messageID: String, timestamp: Date) {
        broadcastTransports().forEach { it.sendMessage(content, mentions, messageID, timestamp) }
    }

    override fun sendPrivateMessage(content: String, to: PeerID, recipientNickname: String, messageID: String) {
        bestTransport(to, content.length).sendPrivateMessage(content, to, recipientNickname, messageID)
    }

    override fun sendReadReceipt(receipt: ReadReceipt, to: PeerID) {
        bestTransport(to).sendReadReceipt(receipt, to)
    }

    override fun sendFavoriteNotification(to: PeerID, isFavorite: Boolean) {
        bestTransport(to).sendFavoriteNotification(to, isFavorite)
    }

    override fun sendBroadcastAnnounce() {
        broadcastTransports().forEach { it.sendBroadcastAnnounce() }
    }

    override fun sendDeliveryAck(messageID: String, to: PeerID) {
        bestTransport(to).sendDeliveryAck(messageID, to)
    }

    override fun sendFileBroadcast(packet: ByteArray, transferId: String) {
        // Large files: prefer Wi-Fi Aware
        val wifiAware = transports.firstOrNull { it.name == "wifi-aware" && it.isAvailable }
        (wifiAware ?: transports.first { it.isAvailable }).sendFileBroadcast(packet, transferId)
    }

    override fun sendFilePrivate(packet: ByteArray, to: PeerID, transferId: String) {
        bestTransport(to, packet.size).sendFilePrivate(packet, to, transferId)
    }

    override fun cancelTransfer(transferId: String) {
        transports.forEach { it.cancelTransfer(transferId) }
    }

    override fun sendVerifyChallenge(to: PeerID, noiseKeyHex: String, nonceA: ByteArray) {
        bestTransport(to).sendVerifyChallenge(to, noiseKeyHex, nonceA)
    }

    override fun sendVerifyResponse(to: PeerID, noiseKeyHex: String, nonceA: ByteArray) {
        bestTransport(to).sendVerifyResponse(to, noiseKeyHex, nonceA)
    }

    override fun acceptPendingFile(id: String): String? {
        return transports.firstNotNullOfOrNull { it.acceptPendingFile(id) }
    }

    override fun declinePendingFile(id: String) {
        transports.forEach { it.declinePendingFile(id) }
    }

    override fun sendRawData(peerID: PeerID, data: ByteArray): Boolean {
        return bestTransport(peerID, data.size).sendRawData(peerID, data)
    }

    override fun broadcastRawData(data: ByteArray) {
        broadcastTransports().forEach { it.broadcastRawData(data) }
    }
}
