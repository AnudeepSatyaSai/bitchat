// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import chat.bitchat.app.BitchatApp
import chat.bitchat.app.model.*
import chat.bitchat.app.transport.*
import chat.bitchat.app.transport.ble.BleTransport
import chat.bitchat.app.transport.wifiaware.WiFiAwareTransport
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Date

/**
 * Main ViewModel for the chat screen.
 * Port of Swift's ChatViewModel.
 *
 * Bridges the transport layer to the Compose UI.
 * Holds messages, peers, and transport state as observable flows.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as BitchatApp

    // ============ State Flows ============

    private val _messages = MutableStateFlow<List<BitchatMessage>>(emptyList())
    val messages: StateFlow<List<BitchatMessage>> = _messages.asStateFlow()

    private val _peers = MutableStateFlow<List<TransportPeerSnapshot>>(emptyList())
    val peers: StateFlow<List<TransportPeerSnapshot>> = _peers.asStateFlow()

    private val _connectionState = MutableStateFlow(TransportState.UNKNOWN)
    val connectionState: StateFlow<TransportState> = _connectionState.asStateFlow()

    private val _nickname = MutableStateFlow(app.identityManager.nickname)
    val nickname: StateFlow<String> = _nickname.asStateFlow()

    private val _activeTransports = MutableStateFlow<List<String>>(emptyList())
    val activeTransports: StateFlow<List<String>> = _activeTransports.asStateFlow()

    // ============ Transport ============

    private val transportSelector: TransportSelector

    init {
        val identity = app.identityManager
        val privateKey = identity.noiseStaticPrivateKey ?: ByteArray(32)
        val publicKey = identity.noiseStaticPublicKey ?: ByteArray(32)
        val peerID = identity.devicePeerID ?: PeerID.fromString("00000000")

        // Create both transports
        val bleTransport = BleTransport(
            context = application,
            localStaticPrivateKey = privateKey,
            localStaticPublicKey = publicKey,
            deviceID = peerID
        )

        val wifiAwareTransport = WiFiAwareTransport(
            context = application,
            localStaticPrivateKey = privateKey,
            localStaticPublicKey = publicKey,
            deviceID = peerID
        )

        // Create transport selector
        transportSelector = TransportSelector(
            context = application,
            transports = listOf(bleTransport, wifiAwareTransport)
        )

        // Set delegate
        transportSelector.delegate = object : BitchatDelegate {
            override fun didReceiveMessage(message: BitchatMessage) {
                viewModelScope.launch {
                    val current = _messages.value.toMutableList()
                    // Dedup by ID
                    if (current.none { it.id == message.id }) {
                        current.add(message)
                        // Keep last 500 messages
                        if (current.size > 500) {
                            _messages.value = current.takeLast(500)
                        } else {
                            _messages.value = current
                        }
                    }
                }
            }

            override fun didConnectToPeer(peerID: PeerID) {
                Timber.i("Peer connected: ${peerID.id}")
            }

            override fun didDisconnectFromPeer(peerID: PeerID) {
                Timber.i("Peer disconnected: ${peerID.id}")
            }

            override fun didUpdatePeerList(peers: List<PeerID>) {
                // Peer list is updated via peerSnapshots flow
            }

            override fun isFavorite(fingerprint: String): Boolean = false

            override fun didUpdateMessageDeliveryStatus(messageID: String, status: DeliveryStatus) {
                viewModelScope.launch {
                    val current = _messages.value.toMutableList()
                    val idx = current.indexOfFirst { it.id == messageID }
                    if (idx >= 0) {
                        current[idx] = current[idx].copy(deliveryStatus = status)
                        _messages.value = current
                    }
                }
            }

            override fun didReceiveNoisePayload(
                from: PeerID, type: NoisePayloadType,
                payload: ByteArray, timestamp: Date
            ) {
                when (type) {
                    NoisePayloadType.PRIVATE_MESSAGE -> {
                        val message = BitchatMessage.fromBinaryPayload(payload)
                        if (message != null) {
                            didReceiveMessage(message)
                        }
                    }
                    NoisePayloadType.READ_RECEIPT -> {
                        val messageID = String(payload, Charsets.UTF_8)
                        didUpdateMessageDeliveryStatus(
                            messageID,
                            DeliveryStatus.Read(from.id, timestamp)
                        )
                    }
                    NoisePayloadType.DELIVERED -> {
                        val messageID = String(payload, Charsets.UTF_8)
                        didUpdateMessageDeliveryStatus(
                            messageID,
                            DeliveryStatus.Delivered(from.id, timestamp)
                        )
                    }
                    else -> {
                        Timber.d("Unhandled noise payload type: $type from ${from.id}")
                    }
                }
            }

            override fun didUpdateTransportState(transport: String, state: TransportState) {
                viewModelScope.launch {
                    _connectionState.value = state
                    val active = _activeTransports.value.toMutableList()
                    if (state == TransportState.POWERED_ON && transport !in active) {
                        active.add(transport)
                    } else if (state != TransportState.POWERED_ON) {
                        active.remove(transport)
                    }
                    _activeTransports.value = active
                }
            }

            override fun didReceivePublicMessage(
                from: PeerID, nickname: String, content: String,
                timestamp: Date, messageID: String?
            ) {
                val msg = BitchatMessage(
                    id = messageID ?: java.util.UUID.randomUUID().toString(),
                    sender = nickname,
                    content = content,
                    timestamp = timestamp,
                    isRelay = false,
                    senderPeerID = from
                )
                didReceiveMessage(msg)
            }
        }

        // Observe peer snapshots from transport
        viewModelScope.launch {
            transportSelector.peerSnapshots.collect { snapshots ->
                _peers.value = snapshots
            }
        }
    }

    // ============ Actions ============

    fun startMesh() {
        transportSelector.myNickname = _nickname.value
        transportSelector.startServices()
    }

    fun stopMesh() {
        transportSelector.stopServices()
    }

    fun sendMessage(content: String) {
        if (content.isBlank()) return
        val messageID = java.util.UUID.randomUUID().toString()
        val timestamp = Date()

        // Add to local messages immediately
        val myMessage = BitchatMessage(
            id = messageID,
            sender = _nickname.value,
            content = content.trim(),
            timestamp = timestamp,
            isRelay = false,
            senderPeerID = app.identityManager.devicePeerID ?: PeerID.fromString("local"),
            deliveryStatus = DeliveryStatus.Sending
        )
        _messages.value = _messages.value + myMessage

        // Send via transport
        transportSelector.sendMessage(content.trim(), emptyList(), messageID, timestamp)

        // Mark as sent
        viewModelScope.launch {
            kotlinx.coroutines.delay(200)
            val updated = _messages.value.toMutableList()
            val idx = updated.indexOfFirst { it.id == messageID }
            if (idx >= 0) {
                updated[idx] = updated[idx].copy(deliveryStatus = DeliveryStatus.Sent)
                _messages.value = updated
            }
        }
    }

    fun sendPrivateMessage(content: String, to: PeerID, recipientNickname: String) {
        if (content.isBlank()) return
        val messageID = java.util.UUID.randomUUID().toString()

        val myMessage = BitchatMessage(
            id = messageID,
            sender = _nickname.value,
            content = content.trim(),
            timestamp = Date(),
            isRelay = false,
            isPrivate = true,
            recipientNickname = recipientNickname,
            senderPeerID = app.identityManager.devicePeerID ?: PeerID.fromString("local"),
            deliveryStatus = DeliveryStatus.Sending
        )
        _messages.value = _messages.value + myMessage

        transportSelector.sendPrivateMessage(content.trim(), to, recipientNickname, messageID)
    }

    fun updateNickname(name: String) {
        val trimmed = name.trim().take(32)
        if (trimmed.isNotEmpty()) {
            _nickname.value = trimmed
            app.identityManager.saveNickname(trimmed)
            transportSelector.myNickname = trimmed
        }
    }

    fun triggerHandshake(peerID: PeerID) {
        transportSelector.triggerHandshake(peerID)
    }

    fun getDeviceFingerprint(): String =
        app.identityManager.getFormattedFingerprint()

    override fun onCleared() {
        super.onCleared()
        transportSelector.stopServices()
    }
}
