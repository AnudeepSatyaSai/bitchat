// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.transport.wifiaware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.aware.*
import android.os.Build
import chat.bitchat.app.model.*
import chat.bitchat.app.noise.NoiseSessionManager
import chat.bitchat.app.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wi-Fi Aware Transport — Broadcast-only, NO IP addresses.
 *
 * Uses Wi-Fi Aware publish/subscribe message passing to send data
 * directly between peers, exactly like BLE uses GATT characteristics.
 *
 * Architecture:
 *   - Publish service → advertise our presence
 *   - Subscribe service → discover peers
 *   - sendMessage(PeerHandle, data) → send data directly via radio
 *   - onMessageReceived(PeerHandle, data) → receive data from peers
 *
 * NO TCP, NO sockets, NO IP addresses, NO ConnectivityManager.
 * Messages > 255 bytes are fragmented and reassembled automatically.
 */
class WiFiAwareTransport(
    private val context: Context,
    private val localStaticPrivateKey: ByteArray,
    private val localStaticPublicKey: ByteArray,
    private val deviceID: PeerID
) : Transport {

    companion object {
        private const val SERVICE_NAME = "bitchat-mesh"
        private const val MAX_AWARE_MESSAGE_SIZE = 255 // Wi-Fi Aware message limit
        private const val FRAGMENT_HEADER_SIZE = 6     // 2 (msgId) + 2 (fragIndex) + 2 (totalFrags)
        private const val FRAGMENT_PAYLOAD_SIZE = MAX_AWARE_MESSAGE_SIZE - FRAGMENT_HEADER_SIZE // 249 bytes
        private const val MAINTENANCE_INTERVAL_MS = 15_000L
        private const val PEER_TIMEOUT_MS = 120_000L
        private const val REASSEMBLY_TIMEOUT_MS = 30_000L
    }

    // ============ Transport Interface Properties ============

    override val name: String = "wifi-aware"
    override var isAvailable: Boolean = false
        private set

    override val myPeerID: PeerID = deviceID
    override var myNickname: String = "anon"
    override var delegate: BitchatDelegate? = null

    private val _peerSnapshots = MutableStateFlow<List<TransportPeerSnapshot>>(emptyList())
    override val peerSnapshots: StateFlow<List<TransportPeerSnapshot>> = _peerSnapshots.asStateFlow()

    // ============ Wi-Fi Aware State ============

    private var wifiAwareManager: WifiAwareManager? = null
    private var awareSession: WifiAwareSession? = null
    private var publishDiscoverySession: PublishDiscoverySession? = null
    private var subscribeDiscoverySession: SubscribeDiscoverySession? = null

    // ============ Peer Tracking ============

    data class AwarePeerInfo(
        val peerID: PeerID,
        var nickname: String = "",
        var isConnected: Boolean = false,
        var lastSeen: Long = System.currentTimeMillis(),
        var peerHandle: PeerHandle? = null
    )

    private val peers = ConcurrentHashMap<PeerID, AwarePeerInfo>()
    private val handleToPeer = ConcurrentHashMap<Int, PeerID>() // PeerHandle.peerId -> PeerID

    // ============ Noise & Dedup ============

    private val noiseSessionManager = NoiseSessionManager(localStaticPrivateKey, localStaticPublicKey)
    private val messageDeduplicator = chat.bitchat.app.transport.ble.MessageDeduplicator()

    // ============ Fragment Reassembly ============

    private val messageIdCounter = AtomicInteger(0)

    data class FragmentBuffer(
        val totalFragments: Int,
        val fragments: Array<ByteArray?>,
        val createdAt: Long = System.currentTimeMillis(),
        var receivedCount: Int = 0
    ) {
        val isComplete: Boolean get() = receivedCount >= totalFragments
    }

    // Key: "${peerHandleId}_${messageId}" -> FragmentBuffer
    private val reassemblyBuffers = ConcurrentHashMap<String, FragmentBuffer>()

    // ============ Coroutines ============

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var maintenanceJob: Job? = null

    // ============ Availability Receiver ============

    private val availabilityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED) {
                val available = wifiAwareManager?.isAvailable == true
                if (available && awareSession == null) {
                    attachToAware()
                } else if (!available) {
                    handleAwareUnavailable()
                }
            }
        }
    }

    // ============ Lifecycle ============

    override fun startServices() {
        Timber.i("Starting Wi-Fi Aware transport (broadcast mode, no IP)")

        wifiAwareManager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
        if (wifiAwareManager == null) {
            Timber.w("Wi-Fi Aware not supported on this device")
            return
        }

        if (!context.packageManager.hasSystemFeature("android.hardware.wifi.aware")) {
            Timber.w("Wi-Fi Aware hardware feature not available")
            return
        }

        // Register for availability changes
        val filter = IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED)
        context.registerReceiver(availabilityReceiver, filter)

        if (wifiAwareManager?.isAvailable == true) {
            attachToAware()
        } else {
            Timber.w("Wi-Fi Aware not currently available")
        }
    }

    override fun stopServices() {
        Timber.i("Stopping Wi-Fi Aware transport")
        maintenanceJob?.cancel()
        scope.coroutineContext.cancelChildren()

        try {
            context.unregisterReceiver(availabilityReceiver)
        } catch (e: Exception) { /* Not registered */ }

        publishDiscoverySession?.close()
        subscribeDiscoverySession?.close()
        awareSession?.close()
        awareSession = null
        publishDiscoverySession = null
        subscribeDiscoverySession = null
        peers.clear()
        handleToPeer.clear()
        reassemblyBuffers.clear()
        isAvailable = false
    }

    override fun emergencyDisconnectAll() {
        Timber.w("Emergency disconnect all (Wi-Fi Aware)!")
        peers.clear()
        handleToPeer.clear()
        reassemblyBuffers.clear()
        publishPeerData()
    }

    // ============ Wi-Fi Aware Session ============

    private fun attachToAware() {
        wifiAwareManager?.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                Timber.i("Wi-Fi Aware session attached")
                awareSession = session
                isAvailable = true
                delegate?.didUpdateTransportState(name, TransportState.POWERED_ON)

                startPublishing()
                startSubscribing()
                startMaintenance()
            }

            override fun onAttachFailed() {
                Timber.e("Wi-Fi Aware attach failed")
                isAvailable = false
                delegate?.didUpdateTransportState(name, TransportState.POWERED_OFF)
            }
        }, null)
    }

    private fun handleAwareUnavailable() {
        isAvailable = false
        publishDiscoverySession?.close()
        subscribeDiscoverySession?.close()
        awareSession?.close()
        awareSession = null
        publishDiscoverySession = null
        subscribeDiscoverySession = null
        delegate?.didUpdateTransportState(name, TransportState.POWERED_OFF)
    }

    // ============ Publish (Advertise our presence) ============

    private fun startPublishing() {
        val session = awareSession ?: return

        val config = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
            .setServiceSpecificInfo(buildServiceInfo())
            .build()

        session.publish(config, object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) {
                Timber.i("Wi-Fi Aware publishing started")
                publishDiscoverySession = session
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                handleIncomingMessage(peerHandle, message)
            }

            override fun onMessageSendSucceeded(messageId: Int) {
                Timber.v("Publish message sent successfully: $messageId")
            }

            override fun onMessageSendFailed(messageId: Int) {
                Timber.w("Publish message send failed: $messageId")
            }

            override fun onSessionTerminated() {
                Timber.w("Publish session terminated")
                publishDiscoverySession = null
            }
        }, null)
    }

    // ============ Subscribe (Discover peers) ============

    private fun startSubscribing() {
        val session = awareSession ?: return

        val config = SubscribeConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
            .build()

        session.subscribe(config, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                Timber.i("Wi-Fi Aware subscribing started")
                subscribeDiscoverySession = session
            }

            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray?,
                matchFilter: MutableList<ByteArray>?
            ) {
                Timber.i("Wi-Fi Aware peer discovered: handle=${peerHandle.hashCode()}")
                handlePeerDiscovered(peerHandle, serviceSpecificInfo)
            }

            override fun onServiceDiscoveredWithinRange(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray?,
                matchFilter: MutableList<ByteArray>?,
                distanceMm: Int
            ) {
                Timber.i("Wi-Fi Aware peer discovered at ${distanceMm}mm")
                handlePeerDiscovered(peerHandle, serviceSpecificInfo)
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                handleIncomingMessage(peerHandle, message)
            }

            override fun onMessageSendSucceeded(messageId: Int) {
                Timber.v("Subscribe message sent successfully: $messageId")
            }

            override fun onMessageSendFailed(messageId: Int) {
                Timber.w("Subscribe message send failed: $messageId")
            }

            override fun onSessionTerminated() {
                Timber.w("Subscribe session terminated")
                subscribeDiscoverySession = null
            }
        }, null)
    }

    // ============ Service Info (Peer Identity) ============

    /**
     * Build service-specific info containing our PeerID.
     * Format: [8 bytes routing data]
     */
    private fun buildServiceInfo(): ByteArray {
        return deviceID.routingData ?: ByteArray(8)
    }

    /**
     * Parse peer identity from service-specific info.
     */
    private fun parsePeerID(serviceInfo: ByteArray?): PeerID? {
        if (serviceInfo == null || serviceInfo.size < 8) return null
        return PeerID.fromRoutingData(serviceInfo.copyOfRange(0, 8))
    }

    // ============ Peer Discovery ============

    private fun handlePeerDiscovered(peerHandle: PeerHandle, serviceInfo: ByteArray?) {
        val peerID = parsePeerID(serviceInfo) ?: PeerID.fromString("aware_${peerHandle.hashCode()}")

        // Skip self
        if (peerID == myPeerID) return

        handleToPeer[peerHandle.hashCode()] = peerID

        val peerInfo = peers.getOrPut(peerID) { AwarePeerInfo(peerID) }
        peerInfo.peerHandle = peerHandle
        peerInfo.isConnected = true
        peerInfo.lastSeen = System.currentTimeMillis()

        publishPeerData()
        delegate?.didConnectToPeer(peerID)

        Timber.i("Peer registered: ${peerID.id}")

        // Send announce to introduce ourselves
        sendAnnounceToPeer(peerHandle)
    }

    private fun sendAnnounceToPeer(peerHandle: PeerHandle) {
        val payload = myNickname.toByteArray(Charsets.UTF_8)
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = 1u,
            senderID = myPeerID,
            payload = payload
        )
        val data = packet.toBinaryData() ?: return
        sendToPeerHandle(peerHandle, data)
    }

    // ============ Message Sending (Broadcast via Radio) ============

    /**
     * Send raw data to a specific PeerHandle.
     * If data > 255 bytes, it is fragmented automatically.
     * Uses DiscoverySession.sendMessage() — NO IP, NO sockets.
     */
    private fun sendToPeerHandle(peerHandle: PeerHandle, data: ByteArray) {
        if (data.size <= MAX_AWARE_MESSAGE_SIZE) {
            // Small message: send directly (no fragmentation header)
            // Prefix with 0x00 to indicate "unfragmented"
            val frame = ByteArray(1 + data.size)
            frame[0] = 0x00 // unfragmented marker
            data.copyInto(frame, 1)
            sendRawMessage(peerHandle, frame)
        } else {
            // Large message: fragment
            sendFragmented(peerHandle, data)
        }
    }

    /**
     * Fragment and send a large message.
     * Each fragment: [0x01 (marker)] [2 bytes msgId] [2 bytes fragIndex] [2 bytes totalFrags] [payload]
     */
    private fun sendFragmented(peerHandle: PeerHandle, data: ByteArray) {
        val messageId = messageIdCounter.getAndIncrement() and 0xFFFF
        val totalFragments = (data.size + FRAGMENT_PAYLOAD_SIZE - 1) / FRAGMENT_PAYLOAD_SIZE

        Timber.d("Fragmenting ${data.size} bytes into $totalFragments fragments (msgId=$messageId)")

        for (i in 0 until totalFragments) {
            val offset = i * FRAGMENT_PAYLOAD_SIZE
            val end = minOf(offset + FRAGMENT_PAYLOAD_SIZE, data.size)
            val chunkSize = end - offset

            // Build fragment: [0x01] [msgId:2] [fragIndex:2] [totalFrags:2] [payload]
            val fragment = ByteArray(1 + FRAGMENT_HEADER_SIZE + chunkSize)
            fragment[0] = 0x01 // fragmented marker
            fragment[1] = ((messageId shr 8) and 0xFF).toByte()
            fragment[2] = (messageId and 0xFF).toByte()
            fragment[3] = ((i shr 8) and 0xFF).toByte()
            fragment[4] = (i and 0xFF).toByte()
            fragment[5] = ((totalFragments shr 8) and 0xFF).toByte()
            fragment[6] = (totalFragments and 0xFF).toByte()
            data.copyInto(fragment, 1 + FRAGMENT_HEADER_SIZE, offset, end)

            sendRawMessage(peerHandle, fragment)
        }
    }

    /**
     * Send a single raw message via the Wi-Fi Aware discovery session.
     * This is the lowest-level send — directly over the radio.
     */
    private fun sendRawMessage(peerHandle: PeerHandle, data: ByteArray) {
        val msgId = messageIdCounter.getAndIncrement()

        // Try publish session first, then subscribe session
        val session = publishDiscoverySession ?: subscribeDiscoverySession
        if (session == null) {
            Timber.w("No active discovery session to send message")
            return
        }

        try {
            session.sendMessage(peerHandle, msgId, data)
        } catch (e: Exception) {
            Timber.e(e, "Failed to send Wi-Fi Aware message")
        }
    }

    // ============ Message Receiving ============

    /**
     * Handle incoming message from Wi-Fi Aware radio.
     * Checks for fragmentation and reassembles if needed.
     */
    private fun handleIncomingMessage(peerHandle: PeerHandle, message: ByteArray) {
        if (message.isEmpty()) return

        val peerID = handleToPeer[peerHandle.hashCode()]
            ?: PeerID.fromString("aware_${peerHandle.hashCode()}")

        // Update peer tracking
        val peerInfo = peers.getOrPut(peerID) { AwarePeerInfo(peerID) }
        peerInfo.peerHandle = peerHandle
        peerInfo.lastSeen = System.currentTimeMillis()
        if (!peerInfo.isConnected) {
            peerInfo.isConnected = true
            handleToPeer[peerHandle.hashCode()] = peerID
            publishPeerData()
            delegate?.didConnectToPeer(peerID)
        }

        val marker = message[0]
        when (marker) {
            0x00.toByte() -> {
                // Unfragmented message
                val data = message.copyOfRange(1, message.size)
                scope.launch { processIncomingPacket(peerID, data) }
            }
            0x01.toByte() -> {
                // Fragmented message — reassemble
                handleFragment(peerHandle, peerID, message)
            }
            else -> {
                // Legacy: treat entire message as data (no marker)
                scope.launch { processIncomingPacket(peerID, message) }
            }
        }
    }

    /**
     * Handle a single fragment and reassemble when all fragments arrive.
     */
    private fun handleFragment(peerHandle: PeerHandle, peerID: PeerID, fragment: ByteArray) {
        if (fragment.size < 1 + FRAGMENT_HEADER_SIZE) return

        val messageId = ((fragment[1].toInt() and 0xFF) shl 8) or (fragment[2].toInt() and 0xFF)
        val fragIndex = ((fragment[3].toInt() and 0xFF) shl 8) or (fragment[4].toInt() and 0xFF)
        val totalFragments = ((fragment[5].toInt() and 0xFF) shl 8) or (fragment[6].toInt() and 0xFF)

        if (totalFragments <= 0 || fragIndex >= totalFragments) return

        val bufferKey = "${peerHandle.hashCode()}_$messageId"
        val buffer = reassemblyBuffers.getOrPut(bufferKey) {
            FragmentBuffer(
                totalFragments = totalFragments,
                fragments = arrayOfNulls(totalFragments)
            )
        }

        // Store fragment payload
        val payload = fragment.copyOfRange(1 + FRAGMENT_HEADER_SIZE, fragment.size)
        if (buffer.fragments[fragIndex] == null) {
            buffer.fragments[fragIndex] = payload
            buffer.receivedCount++
        }

        // Check if complete
        if (buffer.isComplete) {
            reassemblyBuffers.remove(bufferKey)

            // Concatenate all fragments
            val totalSize = buffer.fragments.sumOf { it?.size ?: 0 }
            val assembled = ByteArray(totalSize)
            var offset = 0
            for (frag in buffer.fragments) {
                frag?.copyInto(assembled, offset)
                offset += frag?.size ?: 0
            }

            Timber.d("Reassembled ${totalFragments} fragments → ${assembled.size} bytes from ${peerID.id}")
            scope.launch { processIncomingPacket(peerID, assembled) }
        }
    }

    // ============ Packet Processing ============

    private suspend fun processIncomingPacket(peerID: PeerID, data: ByteArray) {
        try {
            val packet = BitchatPacket.from(data) ?: return

            // Dedup
            val key = messageDeduplicator.packetKey(packet)
            if (messageDeduplicator.isDuplicate(key)) return
            messageDeduplicator.markSeen(key)

            // Update last seen
            peers[peerID]?.lastSeen = System.currentTimeMillis()

            // Route based on type
            val msgType = MessageType.fromByte(packet.type.toByte())
            when (msgType) {
                MessageType.ANNOUNCE -> {
                    val nickname = String(packet.payload, Charsets.UTF_8)
                    peers[peerID]?.nickname = nickname
                    publishPeerData()
                }
                MessageType.MESSAGE -> {
                    val message = BitchatMessage.fromBinaryPayload(packet.payload)
                    if (message != null) {
                        delegate?.didReceiveMessage(message)
                    }
                }
                MessageType.LEAVE -> {
                    handlePeerDisconnected(peerID)
                }
                MessageType.NOISE_HANDSHAKE -> {
                    try {
                        val response = noiseSessionManager.handleIncomingHandshake(peerID, packet.payload)
                        if (response != null) {
                            val respPacket = BitchatPacket(
                                type = MessageType.NOISE_HANDSHAKE.value,
                                ttl = 1u,
                                senderID = myPeerID,
                                payload = response
                            )
                            val respData = respPacket.toBinaryData() ?: return
                            val handle = peers[peerID]?.peerHandle ?: return
                            sendToPeerHandle(handle, respData)
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Noise handshake error from ${peerID.id}")
                    }
                }
                MessageType.NOISE_TRANSPORT -> {
                    try {
                        val decrypted = noiseSessionManager.decrypt(peerID, packet.payload) ?: return
                        val noisePayload = NoisePayload.decode(decrypted) ?: return
                        delegate?.didReceiveNoisePayload(
                            peerID,
                            noisePayload.type,
                            noisePayload.data,
                            Date(packet.timestamp.toLong())
                        )
                    } catch (e: Exception) {
                        Timber.e(e, "Noise decrypt failed from ${peerID.id}")
                    }
                }
                MessageType.PRIVATE_MESSAGE -> {
                    val message = BitchatMessage.fromBinaryPayload(packet.payload)
                    if (message != null) {
                        val privateMsg = message.copy(isPrivate = true)
                        delegate?.didReceiveMessage(privateMsg)
                    }
                }
                MessageType.DELIVERY_ACK -> {
                    val messageID = String(packet.payload, Charsets.UTF_8)
                    delegate?.didUpdateMessageDeliveryStatus(
                        messageID,
                        DeliveryStatus.Delivered(peerID.id, Date())
                    )
                }
                MessageType.FILE_HEADER, MessageType.FILE_CHUNK, MessageType.FILE_COMPLETE -> {
                    Timber.d("File transfer message from ${peerID.id} — type: $msgType")
                    // TODO: File transfer via broadcast
                }
                MessageType.READ_RECEIPT -> {
                    val messageID = String(packet.payload, Charsets.UTF_8)
                    delegate?.didUpdateMessageDeliveryStatus(
                        messageID,
                        DeliveryStatus.Read(peerID.id, Date())
                    )
                }
                MessageType.VERIFY_CHALLENGE, MessageType.VERIFY_RESPONSE -> {
                    Timber.d("Verify message from ${peerID.id}")
                }
                else -> {
                    Timber.d("Unknown message type: $msgType from ${peerID.id}")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error processing packet from ${peerID.id}")
        }
    }

    // ============ Peer Management ============

    private fun handlePeerDisconnected(peerID: PeerID) {
        peers.remove(peerID)
        handleToPeer.entries.removeIf { it.value == peerID }
        publishPeerData()
        delegate?.didDisconnectFromPeer(peerID)
    }

    private fun publishPeerData() {
        _peerSnapshots.value = peers.values.map { info ->
            TransportPeerSnapshot(
                peerID = info.peerID,
                nickname = info.nickname,
                isConnected = info.isConnected,
                lastSeen = Date(info.lastSeen),
                transport = name
            )
        }
        delegate?.didUpdatePeerList(peers.keys.toList())
    }

    // ============ Maintenance ============

    private fun startMaintenance() {
        maintenanceJob = scope.launch {
            while (isActive) {
                delay(MAINTENANCE_INTERVAL_MS)
                performMaintenance()
            }
        }
    }

    private fun performMaintenance() {
        val now = System.currentTimeMillis()
        var changed = false

        // Remove timed-out peers
        val timedOut = peers.entries.filter { now - it.value.lastSeen > PEER_TIMEOUT_MS }
        for ((peerID, _) in timedOut) {
            Timber.d("Peer timed out: ${peerID.id}")
            peers.remove(peerID)
            handleToPeer.entries.removeIf { it.value == peerID }
            delegate?.didDisconnectFromPeer(peerID)
            changed = true
        }

        // Cleanup stale reassembly buffers
        reassemblyBuffers.entries.removeIf { now - it.value.createdAt > REASSEMBLY_TIMEOUT_MS }

        // Send periodic announce to keep peers aware
        broadcastAnnounce()

        if (changed) publishPeerData()
    }

    private fun broadcastAnnounce() {
        val payload = myNickname.toByteArray(Charsets.UTF_8)
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = 1u,
            senderID = myPeerID,
            payload = payload
        )
        val data = packet.toBinaryData() ?: return

        peers.values.forEach { peer ->
            peer.peerHandle?.let { handle ->
                sendToPeerHandle(handle, data)
            }
        }
    }

    // ============ Transport Interface — Sending ============

    override fun sendMessage(content: String, mentions: List<String>) {
        val msg = BitchatMessage(
            sender = myNickname,
            content = content,
            timestamp = Date(),
            isRelay = false,
            senderPeerID = myPeerID,
            mentions = mentions
        )
        val payload = msg.toBinaryPayload() ?: return
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = 3u,
            senderID = myPeerID,
            payload = payload
        )
        val data = packet.toBinaryData() ?: return
        broadcastToAllPeers(data)
    }

    override fun sendMessage(content: String, mentions: List<String>, messageID: String, timestamp: Date) {
        val msg = BitchatMessage(
            id = messageID,
            sender = myNickname,
            content = content,
            timestamp = timestamp,
            isRelay = false,
            senderPeerID = myPeerID,
            mentions = mentions
        )
        val payload = msg.toBinaryPayload() ?: return
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = 3u,
            senderID = myPeerID,
            payload = payload
        )
        val data = packet.toBinaryData() ?: return
        broadcastToAllPeers(data)
    }

    override fun sendPrivateMessage(content: String, to: PeerID, recipientNickname: String, messageID: String) {
        val msg = BitchatMessage(
            id = messageID,
            sender = myNickname,
            content = content,
            timestamp = Date(),
            isRelay = false,
            isPrivate = true,
            recipientNickname = recipientNickname,
            senderPeerID = myPeerID
        )
        val payload = msg.toBinaryPayload() ?: return

        // Try Noise encryption first
        val encrypted = noiseSessionManager.encrypt(to, payload)
        if (encrypted != null) {
            val noisePayload = NoisePayload(NoisePayloadType.PRIVATE_MESSAGE, encrypted)
            val packet = BitchatPacket(
                type = MessageType.NOISE_TRANSPORT.value,
                ttl = 1u,
                senderID = myPeerID,
                payload = noisePayload.encode()
            )
            val data = packet.toBinaryData() ?: return
            sendToPeer(to, data)
        } else {
            // Fallback: unencrypted private
            val packet = BitchatPacket(
                type = MessageType.PRIVATE_MESSAGE.value,
                ttl = 1u,
                senderID = myPeerID,
                payload = payload
            )
            val data = packet.toBinaryData() ?: return
            sendToPeer(to, data)
        }
    }

    override fun sendReadReceipt(receipt: ReadReceipt, to: PeerID) {
        val payload = receipt.messageId.toByteArray(Charsets.UTF_8)
        val encrypted = noiseSessionManager.encrypt(to, payload)
        if (encrypted != null) {
            val noisePayload = NoisePayload(NoisePayloadType.READ_RECEIPT, encrypted)
            val packet = BitchatPacket(
                type = MessageType.NOISE_TRANSPORT.value,
                ttl = 1u,
                senderID = myPeerID,
                payload = noisePayload.encode()
            )
            val data = packet.toBinaryData() ?: return
            sendToPeer(to, data)
        }
    }

    override fun sendFavoriteNotification(to: PeerID, isFavorite: Boolean) {
        val payload = if (isFavorite) "1".toByteArray() else "0".toByteArray()
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = 1u,
            senderID = myPeerID,
            payload = payload
        )
        val data = packet.toBinaryData() ?: return
        sendToPeer(to, data)
    }

    override fun sendBroadcastAnnounce() {
        broadcastAnnounce()
    }

    override fun sendDeliveryAck(messageID: String, to: PeerID) {
        val payload = messageID.toByteArray(Charsets.UTF_8)
        val packet = BitchatPacket(
            type = MessageType.DELIVERY_ACK.value,
            ttl = 1u,
            senderID = myPeerID,
            payload = payload
        )
        val data = packet.toBinaryData() ?: return
        sendToPeer(to, data)
    }

    override fun sendFileBroadcast(packet: ByteArray, transferId: String) {
        broadcastToAllPeers(packet)
    }

    override fun sendFilePrivate(packet: ByteArray, to: PeerID, transferId: String) {
        sendToPeer(to, packet)
    }

    override fun cancelTransfer(transferId: String) {
        Timber.d("Transfer cancelled: $transferId")
    }

    override fun sendVerifyChallenge(to: PeerID, noiseKeyHex: String, nonceA: ByteArray) {
        val payload = (noiseKeyHex.toByteArray(Charsets.UTF_8)) + nonceA
        val packet = BitchatPacket(
            type = MessageType.VERIFY_CHALLENGE.value,
            ttl = 1u,
            senderID = myPeerID,
            payload = payload
        )
        val data = packet.toBinaryData() ?: return
        sendToPeer(to, data)
    }

    override fun sendVerifyResponse(to: PeerID, noiseKeyHex: String, nonceA: ByteArray) {
        val payload = (noiseKeyHex.toByteArray(Charsets.UTF_8)) + nonceA
        val packet = BitchatPacket(
            type = MessageType.VERIFY_RESPONSE.value,
            ttl = 1u,
            senderID = myPeerID,
            payload = payload
        )
        val data = packet.toBinaryData() ?: return
        sendToPeer(to, data)
    }

    override fun acceptPendingFile(id: String): String? = null
    override fun declinePendingFile(id: String) {}

    // ============ Send Helpers ============

    /**
     * Send data to a specific peer via their PeerHandle.
     * NO IP address used — directly over Wi-Fi Aware radio.
     */
    private fun sendToPeer(peerID: PeerID, data: ByteArray) {
        val peerInfo = peers[peerID]
        val handle = peerInfo?.peerHandle
        if (handle == null) {
            Timber.w("No PeerHandle for ${peerID.id} — cannot send")
            return
        }
        sendToPeerHandle(handle, data)
    }

    /**
     * Broadcast data to ALL connected peers.
     * Each peer receives the message individually via sendMessage().
     */
    private fun broadcastToAllPeers(data: ByteArray) {
        peers.values.forEach { peer ->
            peer.peerHandle?.let { handle ->
                sendToPeerHandle(handle, data)
            }
        }
    }

    override fun sendRawData(peerID: PeerID, data: ByteArray): Boolean {
        val handle = peers[peerID]?.peerHandle ?: return false
        sendToPeerHandle(handle, data)
        return true
    }

    override fun broadcastRawData(data: ByteArray) {
        broadcastToAllPeers(data)
    }

    // ============ Peer Queries ============

    override fun isPeerConnected(peerID: PeerID): Boolean =
        peers[peerID]?.isConnected == true

    override fun isPeerReachable(peerID: PeerID): Boolean =
        peers.containsKey(peerID)

    override fun peerNickname(peerID: PeerID): String? =
        peers[peerID]?.nickname?.ifEmpty { null }

    override fun getPeerNicknames(): Map<PeerID, String> =
        peers.mapValues { it.value.nickname }.filter { it.value.isNotEmpty() }

    override fun getFingerprint(peerID: PeerID): String? = null

    override fun getNoiseSessionState(peerID: PeerID): LazyHandshakeState =
        LazyHandshakeState.None

    override fun triggerHandshake(peerID: PeerID) {
        scope.launch {
            try {
                val message = noiseSessionManager.initiateHandshake(peerID) ?: return@launch
                val packet = BitchatPacket(
                    type = MessageType.NOISE_HANDSHAKE.value,
                    ttl = 1u,
                    senderID = myPeerID,
                    payload = message
                )
                val data = packet.toBinaryData() ?: return@launch
                sendToPeer(peerID, data)
            } catch (e: Exception) {
                Timber.e(e, "Failed to initiate handshake with ${peerID.id}")
            }
        }
    }

    override fun getNoiseService(): NoiseEncryptionServiceInterface =
        object : NoiseEncryptionServiceInterface {
            override fun encrypt(peerID: PeerID, data: ByteArray): ByteArray? =
                noiseSessionManager.encrypt(peerID, data)
            override fun decrypt(peerID: PeerID, data: ByteArray): ByteArray? =
                noiseSessionManager.decrypt(peerID, data)
        }
}
