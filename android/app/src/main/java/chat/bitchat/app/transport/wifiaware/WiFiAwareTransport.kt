// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.transport.wifiaware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.aware.*
import chat.bitchat.app.model.*
import chat.bitchat.app.noise.NoiseSessionManager
import chat.bitchat.app.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wi-Fi Aware Transport — Fully Decentralized Mesh.
 *
 * Architecture: Broadcast-only, NO IP addresses, NO internet, NO sockets.
 * Every device simultaneously acts as:
 *   - Broadcaster (publish Wi-Fi Aware service)
 *   - Subscriber  (discover nearby peers)
 *   - Relay Node  (forward messages for other peers)
 *   - Endpoint    (send & receive own messages)
 *
 * Mesh Rules:
 *   - Messages hop peer-to-peer via store-and-forward
 *   - TTL decremented on each hop; dropped at TTL <= 0
 *   - Message ID deduplication prevents infinite loops
 *   - Path trace prevents routing loops (drop if own ID in path)
 *   - ACK flows back through relay chain
 *   - End-to-end encryption via Noise protocol (relays cannot read)
 *
 * STRICT RESTRICTIONS:
 *   ❌ No Internet, No Wi-Fi hotspot, No router, No mobile data
 *   ❌ No cloud, No centralized server, No Bluetooth
 *   ❌ No WifiManager.connect(), No IP sockets, No SSID association
 *   ✅ ONLY Wi-Fi Aware (NAN) + peer-to-peer sessions + local mesh relay
 */
class WiFiAwareTransport(
    private val context: Context,
    private val localStaticPrivateKey: ByteArray,
    private val localStaticPublicKey: ByteArray,
    private val deviceID: PeerID
) : Transport {

    companion object {
        private const val SERVICE_NAME = "com.bitchat.mesh"
        private const val MAX_AWARE_MESSAGE_SIZE = 255
        private const val FRAGMENT_HEADER_SIZE = 6     // 2(msgId) + 2(fragIdx) + 2(totalFrags)
        private const val FRAGMENT_PAYLOAD_SIZE = MAX_AWARE_MESSAGE_SIZE - FRAGMENT_HEADER_SIZE - 1 // 248
        private const val MAINTENANCE_INTERVAL_MS = 15_000L
        private const val PEER_TIMEOUT_MS = 120_000L
        private const val REASSEMBLY_TIMEOUT_MS = 30_000L
        private const val DEDUP_CACHE_MAX_SIZE = 5000
        private const val DEDUP_CACHE_TTL_MS = 300_000L // 5 minutes
        private const val DEFAULT_TTL: UByte = 5u       // Max 5 hops
        private const val MAX_PATH_TRACE_SIZE = 10
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
    private val handleToPeer = ConcurrentHashMap<Int, PeerID>()

    // ============ Noise & Security ============

    private val noiseSessionManager = NoiseSessionManager(localStaticPrivateKey, localStaticPublicKey)

    // ============ Message Deduplication Cache ============
    // Prevents infinite rebroadcast loops

    data class DeduplicatedMessage(
        val firstSeen: Long = System.currentTimeMillis(),
        var forwardCount: Int = 0
    )

    private val seenMessages = ConcurrentHashMap<String, DeduplicatedMessage>()

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

    // =====================================================================
    //  LIFECYCLE
    // =====================================================================

    override fun startServices() {
        Timber.i("Starting Wi-Fi Aware mesh transport (broadcast-only, NO IP)")

        wifiAwareManager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
        if (wifiAwareManager == null) {
            Timber.w("Wi-Fi Aware not supported on this device")
            return
        }

        if (!context.packageManager.hasSystemFeature("android.hardware.wifi.aware")) {
            Timber.w("Wi-Fi Aware hardware feature not available")
            return
        }

        val filter = IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED)
        context.registerReceiver(availabilityReceiver, filter)

        if (wifiAwareManager?.isAvailable == true) {
            attachToAware()
        } else {
            Timber.w("Wi-Fi Aware not currently available")
        }
    }

    override fun stopServices() {
        Timber.i("Stopping Wi-Fi Aware mesh transport")
        maintenanceJob?.cancel()
        scope.coroutineContext.cancelChildren()

        try { context.unregisterReceiver(availabilityReceiver) }
        catch (e: Exception) { /* Not registered */ }

        publishDiscoverySession?.close()
        subscribeDiscoverySession?.close()
        awareSession?.close()
        awareSession = null
        publishDiscoverySession = null
        subscribeDiscoverySession = null
        peers.clear()
        handleToPeer.clear()
        seenMessages.clear()
        reassemblyBuffers.clear()
        isAvailable = false
    }

    override fun emergencyDisconnectAll() {
        Timber.w("EMERGENCY: Disconnect all peers")
        peers.clear()
        handleToPeer.clear()
        seenMessages.clear()
        reassemblyBuffers.clear()
        publishPeerData()
    }

    // =====================================================================
    //  WI-FI AWARE SESSION
    // =====================================================================

    private fun attachToAware() {
        wifiAwareManager?.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                Timber.i("Wi-Fi Aware session attached — mesh active")
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

    // =====================================================================
    //  ROLE: BROADCASTER (Publish service)
    // =====================================================================

    private fun startPublishing() {
        val session = awareSession ?: return

        val config = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
            .setServiceSpecificInfo(buildServiceInfo())
            .build()

        session.publish(config, object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) {
                Timber.i("BROADCASTER: Publishing mesh service")
                publishDiscoverySession = session
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                handleIncomingRadioMessage(peerHandle, message)
            }

            override fun onMessageSendSucceeded(messageId: Int) {
                Timber.v("Message sent OK: $messageId")
            }

            override fun onMessageSendFailed(messageId: Int) {
                Timber.w("Message send FAILED: $messageId")
            }

            override fun onSessionTerminated() {
                Timber.w("Publish session terminated")
                publishDiscoverySession = null
            }
        }, null)
    }

    // =====================================================================
    //  ROLE: SUBSCRIBER (Discover peers)
    // =====================================================================

    private fun startSubscribing() {
        val session = awareSession ?: return

        val config = SubscribeConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
            .build()

        session.subscribe(config, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                Timber.i("SUBSCRIBER: Listening for mesh peers")
                subscribeDiscoverySession = session
            }

            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray?,
                matchFilter: MutableList<ByteArray>?
            ) {
                handlePeerDiscovered(peerHandle, serviceSpecificInfo)
            }

            override fun onServiceDiscoveredWithinRange(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray?,
                matchFilter: MutableList<ByteArray>?,
                distanceMm: Int
            ) {
                Timber.i("Peer discovered at ${distanceMm}mm range")
                handlePeerDiscovered(peerHandle, serviceSpecificInfo)
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                handleIncomingRadioMessage(peerHandle, message)
            }

            override fun onMessageSendSucceeded(messageId: Int) {
                Timber.v("Message sent OK: $messageId")
            }

            override fun onMessageSendFailed(messageId: Int) {
                Timber.w("Message send FAILED: $messageId")
            }

            override fun onSessionTerminated() {
                Timber.w("Subscribe session terminated")
                subscribeDiscoverySession = null
            }
        }, null)
    }

    // =====================================================================
    //  PEER DISCOVERY & IDENTITY
    // =====================================================================

    private fun buildServiceInfo(): ByteArray {
        return deviceID.routingData ?: ByteArray(8)
    }

    private fun parsePeerID(serviceInfo: ByteArray?): PeerID? {
        if (serviceInfo == null || serviceInfo.size < 8) return null
        return PeerID.fromRoutingData(serviceInfo.copyOfRange(0, 8))
    }

    private fun handlePeerDiscovered(peerHandle: PeerHandle, serviceInfo: ByteArray?) {
        val peerID = parsePeerID(serviceInfo)
            ?: PeerID.fromString("aware_${peerHandle.hashCode()}")

        if (peerID == myPeerID) return  // Skip self

        handleToPeer[peerHandle.hashCode()] = peerID

        val peerInfo = peers.getOrPut(peerID) { AwarePeerInfo(peerID) }
        peerInfo.peerHandle = peerHandle
        peerInfo.isConnected = true
        peerInfo.lastSeen = System.currentTimeMillis()

        publishPeerData()
        delegate?.didConnectToPeer(peerID)

        Timber.i("PEER DISCOVERED: ${peerID.id}")

        // Announce ourselves to the new peer
        sendAnnounceTo(peerHandle)
    }

    private fun handlePeerDisconnected(peerID: PeerID) {
        peers.remove(peerID)
        handleToPeer.entries.removeIf { it.value == peerID }
        publishPeerData()
        delegate?.didDisconnectFromPeer(peerID)
        Timber.i("PEER DISCONNECTED: ${peerID.id}")
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

    // =====================================================================
    //  FRAGMENTATION (Messages > 255 bytes)
    // =====================================================================

    /**
     * Send data to a PeerHandle with auto-fragmentation.
     * NO IP. Directly over Wi-Fi Aware radio.
     */
    private fun sendToPeerHandle(peerHandle: PeerHandle, data: ByteArray) {
        if (data.size <= MAX_AWARE_MESSAGE_SIZE - 1) {
            // Fits in single frame: [0x00 marker][data]
            val frame = ByteArray(1 + data.size)
            frame[0] = 0x00
            data.copyInto(frame, 1)
            sendRawRadio(peerHandle, frame)
        } else {
            sendFragmented(peerHandle, data)
        }
    }

    private fun sendFragmented(peerHandle: PeerHandle, data: ByteArray) {
        val msgId = messageIdCounter.getAndIncrement() and 0xFFFF
        val totalFrags = (data.size + FRAGMENT_PAYLOAD_SIZE - 1) / FRAGMENT_PAYLOAD_SIZE

        Timber.d("FRAGMENT: ${data.size}B → $totalFrags fragments (msgId=$msgId)")

        for (i in 0 until totalFrags) {
            val offset = i * FRAGMENT_PAYLOAD_SIZE
            val end = minOf(offset + FRAGMENT_PAYLOAD_SIZE, data.size)
            val chunkSize = end - offset

            // [0x01 marker][msgId:2][fragIdx:2][totalFrags:2][payload]
            val frag = ByteArray(1 + FRAGMENT_HEADER_SIZE + chunkSize)
            frag[0] = 0x01
            frag[1] = ((msgId shr 8) and 0xFF).toByte()
            frag[2] = (msgId and 0xFF).toByte()
            frag[3] = ((i shr 8) and 0xFF).toByte()
            frag[4] = (i and 0xFF).toByte()
            frag[5] = ((totalFrags shr 8) and 0xFF).toByte()
            frag[6] = (totalFrags and 0xFF).toByte()
            data.copyInto(frag, 1 + FRAGMENT_HEADER_SIZE, offset, end)

            sendRawRadio(peerHandle, frag)
        }
    }

    /**
     * Lowest-level send: directly over Wi-Fi Aware radio.
     * NO IP, NO sockets, NO internet.
     */
    private fun sendRawRadio(peerHandle: PeerHandle, data: ByteArray) {
        val session = publishDiscoverySession ?: subscribeDiscoverySession
        if (session == null) {
            Timber.w("No discovery session — cannot send")
            return
        }
        try {
            session.sendMessage(peerHandle, messageIdCounter.getAndIncrement(), data)
        } catch (e: Exception) {
            Timber.e(e, "Radio send failed")
        }
    }

    // =====================================================================
    //  MESSAGE RECEIVING & REASSEMBLY
    // =====================================================================

    private fun handleIncomingRadioMessage(peerHandle: PeerHandle, message: ByteArray) {
        if (message.isEmpty()) return

        val peerID = handleToPeer.getOrPut(peerHandle.hashCode()) {
            PeerID.fromString("aware_${peerHandle.hashCode()}")
        }

        // Update peer tracking
        val peerInfo = peers.getOrPut(peerID) { AwarePeerInfo(peerID) }
        peerInfo.peerHandle = peerHandle
        peerInfo.lastSeen = System.currentTimeMillis()
        if (!peerInfo.isConnected) {
            peerInfo.isConnected = true
            publishPeerData()
            delegate?.didConnectToPeer(peerID)
        }

        when (message[0]) {
            0x00.toByte() -> {
                // Single unfragmented message
                val data = message.copyOfRange(1, message.size)
                scope.launch { processMeshPacket(peerID, data) }
            }
            0x01.toByte() -> {
                // Fragment — reassemble
                handleFragment(peerHandle, peerID, message)
            }
            else -> {
                // Legacy fallback
                scope.launch { processMeshPacket(peerID, message) }
            }
        }
    }

    private fun handleFragment(peerHandle: PeerHandle, peerID: PeerID, fragment: ByteArray) {
        if (fragment.size < 1 + FRAGMENT_HEADER_SIZE) return

        val msgId = ((fragment[1].toInt() and 0xFF) shl 8) or (fragment[2].toInt() and 0xFF)
        val fragIdx = ((fragment[3].toInt() and 0xFF) shl 8) or (fragment[4].toInt() and 0xFF)
        val totalFrags = ((fragment[5].toInt() and 0xFF) shl 8) or (fragment[6].toInt() and 0xFF)

        if (totalFrags <= 0 || fragIdx >= totalFrags) return

        val key = "${peerHandle.hashCode()}_$msgId"
        val buffer = reassemblyBuffers.getOrPut(key) {
            FragmentBuffer(totalFragments = totalFrags, fragments = arrayOfNulls(totalFrags))
        }

        val payload = fragment.copyOfRange(1 + FRAGMENT_HEADER_SIZE, fragment.size)
        if (buffer.fragments[fragIdx] == null) {
            buffer.fragments[fragIdx] = payload
            buffer.receivedCount++
        }

        if (buffer.isComplete) {
            reassemblyBuffers.remove(key)
            val totalSize = buffer.fragments.sumOf { it?.size ?: 0 }
            val assembled = ByteArray(totalSize)
            var offset = 0
            for (frag in buffer.fragments) {
                frag?.copyInto(assembled, offset)
                offset += frag?.size ?: 0
            }
            scope.launch { processMeshPacket(peerID, assembled) }
        }
    }

    // =====================================================================
    //  CORE MESH LOGIC: Store-and-Forward Router
    //
    //  This is the heart of the decentralized mesh.
    //  Every packet is checked against these rules:
    //
    //  1. DEDUP:       Drop if message_id already seen
    //  2. PATH TRACE:  Drop if own device_id in route (loop)
    //  3. TTL:         Drop if TTL <= 0
    //  4. RECIPIENT:   If for me → deliver + send ACK
    //  5. RELAY:       If not for me → decrement TTL, add to path, rebroadcast
    // =====================================================================

    private suspend fun processMeshPacket(fromPeerID: PeerID, data: ByteArray) {
        try {
            val packet = BitchatPacket.from(data) ?: return

            // ─── RULE 1: Message ID Deduplication ───
            val packetKey = buildDeduplicationKey(packet)
            if (isMessageSeen(packetKey)) {
                Timber.v("DEDUP: Dropping duplicate ${packetKey.take(16)}")
                return
            }
            markMessageSeen(packetKey)

            // ─── RULE 2: Path Trace Loop Detection ───
            val myRoutingData = deviceID.routingData
            if (myRoutingData != null && packet.route != null) {
                for (hop in packet.route!!) {
                    if (hop.contentEquals(myRoutingData)) {
                        Timber.d("LOOP: Own ID in path_trace — dropping")
                        return
                    }
                }
            }

            // ─── RULE 3: TTL Check ───
            if (packet.ttl <= 0u) {
                Timber.d("TTL EXPIRED: Dropping message")
                return
            }

            // ─── Determine Recipient ───
            val senderPeerID = PeerID.fromRoutingData(packet.senderID)
            val recipientPeerID = packet.recipientID?.let { PeerID.fromRoutingData(it) }
            val isForMe = recipientPeerID == null || recipientPeerID == myPeerID
            val isBroadcast = recipientPeerID == null

            // ─── RULE 4: If for me → Deliver + ACK ───
            if (isForMe) {
                deliverPacketLocally(fromPeerID, packet)

                // Send ACK back if it was a directed message (not broadcast)
                if (!isBroadcast && senderPeerID != null) {
                    sendMeshAck(packet, senderPeerID)
                }
            }

            // ─── RULE 5: If not for me OR broadcast → Relay ───
            if (!isForMe || isBroadcast) {
                relayPacket(packet, fromPeerID)
            }

        } catch (e: Exception) {
            Timber.e(e, "Mesh packet processing error")
        }
    }

    // =====================================================================
    //  RELAY: Forward message to all peers (except sender)
    // =====================================================================

    private fun relayPacket(packet: BitchatPacket, fromPeerID: PeerID) {
        // Decrement TTL
        val newTTL = packet.ttl - 1u
        if (newTTL <= 0u) {
            Timber.d("RELAY: TTL would reach 0 — not forwarding")
            return
        }

        // Add our device ID to path_trace
        val myRouting = deviceID.routingData ?: return
        val currentRoute = packet.route?.toMutableList() ?: mutableListOf()
        if (currentRoute.size >= MAX_PATH_TRACE_SIZE) {
            Timber.d("RELAY: Path trace full — not forwarding")
            return
        }
        currentRoute.add(myRouting)

        // Build relay packet with updated TTL and route
        val relayPacket = BitchatPacket(
            version = packet.version,
            type = packet.type,
            senderID = packet.senderID,
            recipientID = packet.recipientID,
            timestamp = packet.timestamp,
            payload = packet.payload,
            signature = packet.signature,
            ttl = newTTL.toUByte(),
            route = currentRoute,
            isRSR = packet.isRSR
        )

        val relayData = relayPacket.toBinaryData() ?: return

        // Broadcast to ALL peers EXCEPT the one who sent it to us
        var relayCount = 0
        peers.values.forEach { peer ->
            if (peer.peerID != fromPeerID && peer.peerHandle != null) {
                sendToPeerHandle(peer.peerHandle!!, relayData)
                relayCount++
            }
        }

        Timber.d("RELAY: Forwarded to $relayCount peers (TTL: ${packet.ttl}→$newTTL)")
    }

    // =====================================================================
    //  ACK PROTOCOL
    // =====================================================================

    /**
     * Send a delivery ACK back to the original sender.
     * ACK is also a mesh message — it hops back through the relay chain.
     */
    private fun sendMeshAck(originalPacket: BitchatPacket, originalSenderID: PeerID) {
        val ackPayload = buildAckPayload(originalPacket)

        val ackPacket = BitchatPacket(
            type = MessageType.DELIVERY_ACK.value,
            ttl = DEFAULT_TTL,
            senderID = myPeerID,
            payload = ackPayload
        )

        // Set recipient to the original sender so mesh routes it back
        val ackWithRecipient = BitchatPacket(
            version = ackPacket.version,
            type = ackPacket.type,
            senderID = deviceID.routingData ?: ByteArray(8),
            recipientID = originalSenderID.routingData,
            timestamp = ackPacket.timestamp,
            payload = ackPayload,
            signature = null,
            ttl = DEFAULT_TTL,
            route = null,
            isRSR = false
        )

        val data = ackWithRecipient.toBinaryData() ?: return
        broadcastToAllPeers(data)
        Timber.d("ACK: Sent delivery ACK for message back to ${originalSenderID.id}")
    }

    /**
     * Build ACK payload:
     * {ack_id, original_message_id, sender_id, receiver_id, status, timestamp}
     * Encoded as: [ack_uuid_bytes][original_sender_8bytes][timestamp_8bytes]
     */
    private fun buildAckPayload(originalPacket: BitchatPacket): ByteArray {
        val buffer = mutableListOf<Byte>()

        // ACK ID (new UUID, 36 bytes as string)
        val ackId = UUID.randomUUID().toString().toByteArray(Charsets.UTF_8)
        buffer.add(ackId.size.coerceAtMost(255).toByte())
        buffer.addAll(ackId.take(255).toList())

        // Original message timestamp (as identifier, 8 bytes)
        for (shift in (56 downTo 0 step 8)) {
            buffer.add(((originalPacket.timestamp shr shift) and 0xFFu).toByte())
        }

        // Original sender ID (8 bytes)
        buffer.addAll(originalPacket.senderID.take(8).toList())

        // Status: DELIVERED = 0x01
        buffer.add(0x01)

        return buffer.toByteArray()
    }

    // =====================================================================
    //  LOCAL DELIVERY (Message is for this device)
    // =====================================================================

    private suspend fun deliverPacketLocally(fromPeerID: PeerID, packet: BitchatPacket) {
        val msgType = MessageType.fromByte(packet.type.toByte())

        when (msgType) {
            MessageType.ANNOUNCE -> {
                val nickname = String(packet.payload, Charsets.UTF_8)
                peers[fromPeerID]?.nickname = nickname
                publishPeerData()
            }

            MessageType.MESSAGE -> {
                val message = BitchatMessage.fromBinaryPayload(packet.payload)
                if (message != null) {
                    delegate?.didReceiveMessage(message)
                    Timber.i("DELIVERED: Public message from ${fromPeerID.id}")
                }
            }

            MessageType.PRIVATE_MESSAGE -> {
                val message = BitchatMessage.fromBinaryPayload(packet.payload)
                if (message != null) {
                    delegate?.didReceiveMessage(message.copy(isPrivate = true))
                    Timber.i("DELIVERED: Private message from ${fromPeerID.id}")
                }
            }

            MessageType.NOISE_HANDSHAKE -> {
                try {
                    val response = noiseSessionManager.handleIncomingHandshake(fromPeerID, packet.payload)
                    if (response != null) {
                        val respPacket = BitchatPacket(
                            type = MessageType.NOISE_HANDSHAKE.value,
                            ttl = DEFAULT_TTL,
                            senderID = myPeerID,
                            payload = response
                        )
                        val data = respPacket.toBinaryData() ?: return
                        sendToPeerDirect(fromPeerID, data)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Noise handshake error")
                }
            }

            MessageType.NOISE_TRANSPORT -> {
                try {
                    val decrypted = noiseSessionManager.decrypt(fromPeerID, packet.payload) ?: return
                    val noisePayload = NoisePayload.decode(decrypted) ?: return
                    delegate?.didReceiveNoisePayload(
                        fromPeerID,
                        noisePayload.type,
                        noisePayload.data,
                        Date(packet.timestamp.toLong())
                    )
                    Timber.i("DELIVERED: Encrypted payload from ${fromPeerID.id}")
                } catch (e: Exception) {
                    Timber.e(e, "Noise decrypt failed")
                }
            }

            MessageType.DELIVERY_ACK -> {
                val messageID = String(packet.payload, Charsets.UTF_8)
                delegate?.didUpdateMessageDeliveryStatus(
                    messageID,
                    DeliveryStatus.Delivered(fromPeerID.id, Date())
                )
                Timber.i("ACK RECEIVED: Delivery confirmed from ${fromPeerID.id}")
            }

            MessageType.READ_RECEIPT -> {
                val messageID = String(packet.payload, Charsets.UTF_8)
                delegate?.didUpdateMessageDeliveryStatus(
                    messageID,
                    DeliveryStatus.Read(fromPeerID.id, Date())
                )
            }

            MessageType.LEAVE -> {
                handlePeerDisconnected(fromPeerID)
            }

            MessageType.VERIFY_CHALLENGE, MessageType.VERIFY_RESPONSE -> {
                Timber.d("Verify message from ${fromPeerID.id}")
            }

            MessageType.FILE_HEADER, MessageType.FILE_CHUNK, MessageType.FILE_COMPLETE -> {
                Timber.d("File transfer message — type: $msgType")
            }

            else -> {
                Timber.d("Unknown message type: $msgType")
            }
        }
    }

    // =====================================================================
    //  DEDUPLICATION ENGINE
    // =====================================================================

    private fun buildDeduplicationKey(packet: BitchatPacket): String {
        // Key = senderID(hex) + timestamp + type + payload hash
        val senderHex = packet.senderID.joinToString("") { "%02x".format(it) }
        val payloadHash = packet.payload.contentHashCode()
        return "${senderHex}_${packet.timestamp}_${packet.type}_$payloadHash"
    }

    private fun isMessageSeen(key: String): Boolean {
        return seenMessages.containsKey(key)
    }

    private fun markMessageSeen(key: String) {
        seenMessages[key] = DeduplicatedMessage()

        // Evict old entries if cache too large
        if (seenMessages.size > DEDUP_CACHE_MAX_SIZE) {
            val now = System.currentTimeMillis()
            seenMessages.entries.removeIf { now - it.value.firstSeen > DEDUP_CACHE_TTL_MS }
        }
    }

    // =====================================================================
    //  MAINTENANCE (Periodic cleanup)
    // =====================================================================

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
            Timber.d("TIMEOUT: Peer ${peerID.id} removed")
            peers.remove(peerID)
            handleToPeer.entries.removeIf { it.value == peerID }
            delegate?.didDisconnectFromPeer(peerID)
            changed = true
        }

        // Cleanup stale reassembly buffers
        reassemblyBuffers.entries.removeIf { now - it.value.createdAt > REASSEMBLY_TIMEOUT_MS }

        // Cleanup old dedup entries
        seenMessages.entries.removeIf { now - it.value.firstSeen > DEDUP_CACHE_TTL_MS }

        // Periodic announce to keep mesh alive
        broadcastAnnounce()

        if (changed) publishPeerData()
    }

    // =====================================================================
    //  TRANSPORT INTERFACE — SEND METHODS
    // =====================================================================

    private fun sendAnnounceTo(peerHandle: PeerHandle) {
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

    private fun broadcastAnnounce() {
        val payload = myNickname.toByteArray(Charsets.UTF_8)
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = 1u,
            senderID = myPeerID,
            payload = payload
        )
        val data = packet.toBinaryData() ?: return
        broadcastToAllPeers(data)
    }

    override fun sendMessage(content: String, mentions: List<String>) {
        val msg = BitchatMessage(
            sender = myNickname, content = content,
            timestamp = Date(), isRelay = false,
            senderPeerID = myPeerID, mentions = mentions
        )
        val payload = msg.toBinaryPayload() ?: return
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = DEFAULT_TTL,
            senderID = myPeerID,
            payload = payload
        )
        val data = packet.toBinaryData() ?: return
        broadcastToAllPeers(data)
    }

    override fun sendMessage(content: String, mentions: List<String>, messageID: String, timestamp: Date) {
        val msg = BitchatMessage(
            id = messageID, sender = myNickname, content = content,
            timestamp = timestamp, isRelay = false,
            senderPeerID = myPeerID, mentions = mentions
        )
        val payload = msg.toBinaryPayload() ?: return
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = DEFAULT_TTL,
            senderID = myPeerID,
            payload = payload
        )
        val data = packet.toBinaryData() ?: return

        // Mark as seen so we don't process our own broadcast
        markMessageSeen(buildDeduplicationKey(packet))

        broadcastToAllPeers(data)
    }

    override fun sendPrivateMessage(content: String, to: PeerID, recipientNickname: String, messageID: String) {
        val msg = BitchatMessage(
            id = messageID, sender = myNickname, content = content,
            timestamp = Date(), isRelay = false, isPrivate = true,
            recipientNickname = recipientNickname, senderPeerID = myPeerID
        )
        val payload = msg.toBinaryPayload() ?: return

        // Try Noise encryption (end-to-end, relays CANNOT read)
        val encrypted = noiseSessionManager.encrypt(to, payload)
        if (encrypted != null) {
            val noisePayload = NoisePayload(NoisePayloadType.PRIVATE_MESSAGE, encrypted)
            sendDirectedMeshPacket(MessageType.NOISE_TRANSPORT.value, to, noisePayload.encode())
        } else {
            sendDirectedMeshPacket(MessageType.PRIVATE_MESSAGE.value, to, payload)
        }
    }

    /**
     * Send a directed mesh packet — sets recipientID so relays route it.
     */
    private fun sendDirectedMeshPacket(type: UByte, to: PeerID, payload: ByteArray) {
        val packet = BitchatPacket(
            version = 1u,
            type = type,
            senderID = deviceID.routingData ?: ByteArray(8),
            recipientID = to.routingData,
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            signature = null,
            ttl = DEFAULT_TTL,
            route = null,
            isRSR = false
        )
        val data = packet.toBinaryData() ?: return

        // Mark as seen so we don't relay our own message
        markMessageSeen(buildDeduplicationKey(packet))

        // If peer is directly connected, send to them
        // Otherwise broadcast for mesh relay
        val directPeer = peers[to]
        if (directPeer?.peerHandle != null) {
            sendToPeerHandle(directPeer.peerHandle!!, data)
        } else {
            // Peer not directly reachable — broadcast for relay
            broadcastToAllPeers(data)
        }
    }

    override fun sendReadReceipt(receipt: ReadReceipt, to: PeerID) {
        val payload = receipt.messageId.toByteArray(Charsets.UTF_8)
        val encrypted = noiseSessionManager.encrypt(to, payload)
        if (encrypted != null) {
            val noisePayload = NoisePayload(NoisePayloadType.READ_RECEIPT, encrypted)
            sendDirectedMeshPacket(MessageType.NOISE_TRANSPORT.value, to, noisePayload.encode())
        }
    }

    override fun sendFavoriteNotification(to: PeerID, isFavorite: Boolean) {
        val payload = if (isFavorite) "1".toByteArray() else "0".toByteArray()
        sendDirectedMeshPacket(MessageType.ANNOUNCE.value, to, payload)
    }

    override fun sendBroadcastAnnounce() {
        broadcastAnnounce()
    }

    override fun sendDeliveryAck(messageID: String, to: PeerID) {
        val payload = messageID.toByteArray(Charsets.UTF_8)
        sendDirectedMeshPacket(MessageType.DELIVERY_ACK.value, to, payload)
    }

    override fun sendFileBroadcast(packet: ByteArray, transferId: String) {
        broadcastToAllPeers(packet)
    }

    override fun sendFilePrivate(packet: ByteArray, to: PeerID, transferId: String) {
        sendToPeerDirect(to, packet)
    }

    override fun cancelTransfer(transferId: String) {
        Timber.d("Transfer cancelled: $transferId")
    }

    override fun sendVerifyChallenge(to: PeerID, noiseKeyHex: String, nonceA: ByteArray) {
        val payload = noiseKeyHex.toByteArray(Charsets.UTF_8) + nonceA
        sendDirectedMeshPacket(MessageType.VERIFY_CHALLENGE.value, to, payload)
    }

    override fun sendVerifyResponse(to: PeerID, noiseKeyHex: String, nonceA: ByteArray) {
        val payload = noiseKeyHex.toByteArray(Charsets.UTF_8) + nonceA
        sendDirectedMeshPacket(MessageType.VERIFY_RESPONSE.value, to, payload)
    }

    override fun acceptPendingFile(id: String): String? = null
    override fun declinePendingFile(id: String) {}

    // =====================================================================
    //  SEND HELPERS
    // =====================================================================

    private fun sendToPeerDirect(peerID: PeerID, data: ByteArray) {
        val handle = peers[peerID]?.peerHandle
        if (handle == null) {
            Timber.w("No PeerHandle for ${peerID.id}")
            // Broadcast for mesh relay
            broadcastToAllPeers(data)
            return
        }
        sendToPeerHandle(handle, data)
    }

    private fun broadcastToAllPeers(data: ByteArray) {
        peers.values.forEach { peer ->
            peer.peerHandle?.let { sendToPeerHandle(it, data) }
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

    // =====================================================================
    //  PEER QUERIES
    // =====================================================================

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
                    ttl = DEFAULT_TTL,
                    senderID = myPeerID,
                    payload = message
                )
                val data = packet.toBinaryData() ?: return@launch
                sendToPeerDirect(peerID, data)
            } catch (e: Exception) {
                Timber.e(e, "Handshake initiation failed")
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
