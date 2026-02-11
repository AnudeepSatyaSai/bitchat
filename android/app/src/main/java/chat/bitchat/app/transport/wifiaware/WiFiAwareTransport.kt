// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.transport.wifiaware

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.*
import android.os.Build
import chat.bitchat.app.model.*
import chat.bitchat.app.noise.NoiseSessionManager
import chat.bitchat.app.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Wi-Fi Aware (NAN) Transport for BitChat mesh networking.
 *
 * This is a new transport alongside BLE. It provides:
 * - Higher throughput: ~250 Mbps vs BLE's ~2 Mbps
 * - Longer range: 100-200m vs BLE's 10-100m
 * - TCP socket-based communication over Aware L2 network
 *
 * Wire protocol over TCP:
 * ```
 * [4-byte length prefix (big-endian)] [BitchatPacket bytes]
 * ```
 *
 * Uses the EXACT same BitchatPacket binary format as BLE.
 */
@SuppressLint("MissingPermission")
class WiFiAwareTransport(
    private val context: Context,
    private val localStaticPrivateKey: ByteArray,
    private val localStaticPublicKey: ByteArray,
    private val deviceID: PeerID
) : Transport {

    companion object {
        private const val SERVICE_NAME = "bitchat-mesh"
        private const val SERVICE_TYPE = "_bitchat._tcp"
        private const val SERVER_PORT = 0 // OS assigns port
        private const val MATCH_FILTER_PREFIX = "BC" // BitChat match filter
        private const val MAX_PACKET_SIZE = 10 * 1024 * 1024 // 10 MB
        private const val MAINTENANCE_INTERVAL_MS = 15_000L
        private const val PEER_TIMEOUT_MS = 120_000L
        private const val RECONNECT_DELAY_MS = 5_000L
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

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var wifiAwareManager: WifiAwareManager? = null
    private var awareSession: WifiAwareSession? = null
    private var publishDiscoverySession: PublishDiscoverySession? = null
    private var subscribeDiscoverySession: SubscribeDiscoverySession? = null
    private var serverSocket: ServerSocket? = null

    // ============ Peer Tracking ============

    data class AwarePeerInfo(
        val peerID: PeerID,
        var nickname: String = "",
        var isConnected: Boolean = false,
        var lastSeen: Long = System.currentTimeMillis(),
        var peerHandle: PeerHandle? = null,
        var socket: Socket? = null,
        var outputStream: DataOutputStream? = null,
        var inputStream: DataInputStream? = null,
        var network: Network? = null
    )

    private val peers = ConcurrentHashMap<PeerID, AwarePeerInfo>()
    private val handleToPeer = ConcurrentHashMap<Int, PeerID>() // PeerHandle.peerId -> PeerID

    // ============ Noise & Dedup ============

    private val noiseSessionManager = NoiseSessionManager(localStaticPrivateKey, localStaticPublicKey)
    private val messageDeduplicator = chat.bitchat.app.transport.ble.MessageDeduplicator()

    // ============ Coroutines ============

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var maintenanceJob: Job? = null
    private var serverJob: Job? = null

    // ============ Availability Receiver ============

    private val availabilityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED == intent.action) {
                val available = wifiAwareManager?.isAvailable == true
                Timber.i("Wi-Fi Aware availability changed: $available")
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
        Timber.i("Wi-Fi Aware Transport starting...")

        // Check hardware support
        if (!context.packageManager.hasSystemFeature("android.hardware.wifi.aware")) {
            Timber.w("Wi-Fi Aware not supported on this device")
            delegate?.didUpdateTransportState(name, TransportState.UNSUPPORTED)
            return
        }

        wifiAwareManager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
        if (wifiAwareManager == null) {
            Timber.e("WifiAwareManager is null")
            delegate?.didUpdateTransportState(name, TransportState.UNSUPPORTED)
            return
        }

        // Register availability listener
        val filter = IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED)
        context.registerReceiver(availabilityReceiver, filter)

        if (wifiAwareManager?.isAvailable == true) {
            attachToAware()
        } else {
            Timber.w("Wi-Fi Aware not currently available")
            delegate?.didUpdateTransportState(name, TransportState.POWERED_OFF)
        }
    }

    override fun stopServices() {
        Timber.i("Wi-Fi Aware Transport stopping...")
        scope.coroutineContext.cancelChildren()

        try {
            context.unregisterReceiver(availabilityReceiver)
        } catch (e: Exception) { /* Not registered */ }

        closeAllConnections()
        serverSocket?.close()
        publishDiscoverySession?.close()
        subscribeDiscoverySession?.close()
        awareSession?.close()
        awareSession = null
        isAvailable = false
    }

    override fun emergencyDisconnectAll() {
        Timber.w("Emergency disconnect all (Wi-Fi Aware)!")
        closeAllConnections()
        peers.clear()
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
                startTcpServer()
                startMaintenance()
            }

            override fun onAttachFailed() {
                Timber.e("Wi-Fi Aware attach failed")
                isAvailable = false
                delegate?.didUpdateTransportState(name, TransportState.POWERED_OFF)
            }
        }, null) // handler = null → use main thread
    }

    private fun handleAwareUnavailable() {
        isAvailable = false
        closeAllConnections()
        publishDiscoverySession?.close()
        subscribeDiscoverySession?.close()
        awareSession?.close()
        awareSession = null
        delegate?.didUpdateTransportState(name, TransportState.POWERED_OFF)
    }

    // ============ Publishing (like BLE peripheral advertising) ============

    private fun startPublishing() {
        val session = awareSession ?: return

        val config = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
            .setServiceSpecificInfo(buildServiceInfo())
            .build()

        session.publish(config, object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) {
                Timber.i("Wi-Fi Aware publish started: $SERVICE_NAME")
                publishDiscoverySession = session
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                handleDiscoveryMessage(peerHandle, message)
            }
        }, null)
    }

    // ============ Subscribing (like BLE central scanning) ============

    private fun startSubscribing() {
        val session = awareSession ?: return

        val config = SubscribeConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
            .build()

        session.subscribe(config, object : DiscoverySessionCallback() {
            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray?,
                matchFilter: MutableList<ByteArray>?
            ) {
                Timber.d("Wi-Fi Aware peer discovered: handle=${peerHandle.peerId}")
                handlePeerDiscovered(peerHandle, serviceSpecificInfo)
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                handleDiscoveryMessage(peerHandle, message)
            }

            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                Timber.i("Wi-Fi Aware subscribe started: $SERVICE_NAME")
                subscribeDiscoverySession = session
            }
        }, null)
    }

    // ============ TCP Server (for incoming data connections) ============

    private fun startTcpServer() {
        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(SERVER_PORT)
                val port = serverSocket!!.localPort
                Timber.i("Wi-Fi Aware TCP server listening on port $port")

                while (isActive) {
                    val clientSocket = serverSocket!!.accept()
                    Timber.d("TCP connection accepted from ${clientSocket.inetAddress}")
                    launch { handleTcpConnection(clientSocket) }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Timber.e(e, "TCP server error")
                }
            }
        }
    }

    /**
     * Handle data over a TCP socket.
     * Wire protocol: [4-byte length (big-endian)][BitchatPacket bytes]
     */
    private suspend fun handleTcpConnection(socket: Socket) {
        try {
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            // Exchange announce data first
            val announceData = buildServiceInfo()
            output.writeInt(announceData.size)
            output.write(announceData)
            output.flush()

            // Read peer announce
            val peerAnnounceLength = input.readInt()
            if (peerAnnounceLength in 1..1024) {
                val peerAnnounce = ByteArray(peerAnnounceLength)
                input.readFully(peerAnnounce)
                val peerID = parsePeerIDFromServiceInfo(peerAnnounce)

                if (peerID != null && peerID != myPeerID) {
                    val peerInfo = peers.getOrPut(peerID) { AwarePeerInfo(peerID) }
                    peerInfo.socket = socket
                    peerInfo.outputStream = output
                    peerInfo.inputStream = input
                    peerInfo.isConnected = true
                    peerInfo.lastSeen = System.currentTimeMillis()

                    val nickname = parseNicknameFromServiceInfo(peerAnnounce)
                    peerInfo.nickname = nickname

                    delegate?.didConnectToPeer(peerID)
                    publishPeerData()

                    // Read loop
                    readLoop(input, peerID)
                }
            }
        } catch (e: Exception) {
            Timber.d("TCP connection ended: ${e.message}")
        } finally {
            socket.close()
        }
    }

    /**
     * Read framed packets from TCP stream.
     * Format: [4-byte length][packet bytes] ... repeated
     */
    private suspend fun readLoop(input: DataInputStream, peerID: PeerID) {
        try {
            while (true) {
                val length = input.readInt()
                if (length <= 0 || length > MAX_PACKET_SIZE) {
                    Timber.w("Invalid frame length: $length from ${peerID.id}")
                    break
                }

                val data = ByteArray(length)
                input.readFully(data)

                processIncomingPacket(peerID, data)
            }
        } catch (e: Exception) {
            Timber.d("Read loop ended for ${peerID.id}: ${e.message}")
            handlePeerDisconnected(peerID)
        }
    }

    // ============ Peer Discovery & Connection ============

    private fun handlePeerDiscovered(peerHandle: PeerHandle, serviceInfo: ByteArray?) {
        // Extract peer ID from service info
        val peerID = serviceInfo?.let { parsePeerIDFromServiceInfo(it) }
        if (peerID == null || peerID == myPeerID) return

        handleToPeer[peerHandle.peerId] = peerID

        if (peers.containsKey(peerID)) return // Already known

        // Request a network with this peer
        requestNetwork(peerHandle, peerID)
    }

    private fun handleDiscoveryMessage(peerHandle: PeerHandle, message: ByteArray) {
        val peerID = handleToPeer[peerHandle.peerId]
        if (peerID != null) {
            scope.launch {
                processIncomingPacket(peerID, message)
            }
        }
    }

    /**
     * Request a TCP network to a discovered peer via Wi-Fi Aware.
     */
    private fun requestNetwork(peerHandle: PeerHandle, peerID: PeerID) {
        val publishSession = publishDiscoverySession ?: subscribeDiscoverySession ?: return

        val port = serverSocket?.localPort ?: return

        val networkSpecifier = if (publishSession is PublishDiscoverySession) {
            WifiAwareNetworkSpecifier.Builder(publishSession, peerHandle)
                .setPmk("bitchat-pmk-key-v1-secure-mesh!!".toByteArray(Charsets.UTF_8))
                .setPort(port)
                .build()
        } else {
            WifiAwareNetworkSpecifier.Builder(publishSession as SubscribeDiscoverySession, peerHandle)
                .setPmk("bitchat-pmk-key-v1-secure-mesh!!".toByteArray(Charsets.UTF_8))
                .build()
        }

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(networkSpecifier)
            .build()

        connectivityManager.requestNetwork(networkRequest, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Timber.i("Wi-Fi Aware network available with ${peerID.id}")
                scope.launch {
                    connectToPeer(network, peerHandle, peerID)
                }
            }

            override fun onLost(network: Network) {
                Timber.d("Wi-Fi Aware network lost with ${peerID.id}")
                handlePeerDisconnected(peerID)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val peerAwareInfo = networkCapabilities.transportInfo as? WifiAwareNetworkInfo
                peerAwareInfo?.let {
                    Timber.d("Peer address: ${it.peerIpv6Addr}, port: ${it.port}")
                }
            }
        })
    }

    private suspend fun connectToPeer(network: Network, peerHandle: PeerHandle, peerID: PeerID) {
        try {
            val socket = network.socketFactory.createSocket()
            // The port and address are resolved via the Aware network
            val peerInfo = peers.getOrPut(peerID) { AwarePeerInfo(peerID) }
            peerInfo.network = network
            peerInfo.socket = socket
            peerInfo.peerHandle = peerHandle
            peerInfo.isConnected = true
            peerInfo.lastSeen = System.currentTimeMillis()

            delegate?.didConnectToPeer(peerID)
            publishPeerData()

            Timber.i("Connected to peer ${peerID.id} via Wi-Fi Aware")
        } catch (e: Exception) {
            Timber.e(e, "Failed to connect to peer ${peerID.id}")
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
                            sendPacketToPeer(peerID, respPacket)
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Noise handshake failed via Wi-Fi Aware")
                    }
                }
                MessageType.NOISE_ENCRYPTED -> {
                    try {
                        val decrypted = noiseSessionManager.decrypt(packet.payload, peerID)
                        val noisePayload = NoisePayload.decode(decrypted) ?: return
                        delegate?.didReceiveNoisePayload(
                            from = peerID,
                            type = noisePayload.type,
                            payload = noisePayload.data,
                            timestamp = Date(packet.timestamp.toLong())
                        )
                    } catch (e: Exception) {
                        Timber.e(e, "Noise decryption failed via Wi-Fi Aware")
                    }
                }
                MessageType.FRAGMENT, MessageType.FILE_TRANSFER, MessageType.REQUEST_SYNC -> {
                    Timber.d("${msgType?.name} received via Wi-Fi Aware from ${peerID.id}")
                }
                null -> Timber.w("Unknown type: 0x${packet.type.toString(16)}")
            }

            // Relay if TTL allows
            if (packet.ttl > 1u) {
                relayPacket(packet, excludePeer = peerID)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error processing packet from ${peerID.id}")
        }
    }

    // ============ Sending ============

    private fun sendPacketToPeer(peerID: PeerID, packet: BitchatPacket) {
        val data = packet.toBinaryData() ?: return
        val peerInfo = peers[peerID] ?: return

        scope.launch {
            try {
                val output = peerInfo.outputStream ?: return@launch
                synchronized(output) {
                    output.writeInt(data.size) // 4-byte length prefix
                    output.write(data)
                    output.flush()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to send to ${peerID.id} via Wi-Fi Aware")
                handlePeerDisconnected(peerID)
            }
        }
    }

    private fun relayPacket(packet: BitchatPacket, excludePeer: PeerID) {
        val relayed = BitchatPacket(
            version = packet.version,
            type = packet.type,
            senderID = packet.senderID,
            recipientID = packet.recipientID,
            timestamp = packet.timestamp,
            payload = packet.payload,
            signature = packet.signature,
            ttl = (packet.ttl - 1u).toUByte(),
            route = packet.route,
            isRSR = packet.isRSR
        )

        val data = relayed.toBinaryData() ?: return

        for ((peerID, peerInfo) in peers) {
            if (peerID == excludePeer) continue
            if (!peerInfo.isConnected) continue

            scope.launch {
                try {
                    val output = peerInfo.outputStream ?: return@launch
                    synchronized(output) {
                        output.writeInt(data.size)
                        output.write(data)
                        output.flush()
                    }
                } catch (e: Exception) {
                    Timber.w("Relay to ${peerID.id} failed: ${e.message}")
                }
            }
        }
    }

    // ============ Transport Interface Implementations ============

    override fun sendMessage(content: String, mentions: List<String>) {
        sendMessage(content, mentions, UUID.randomUUID().toString(), Date())
    }

    override fun sendMessage(content: String, mentions: List<String>, messageID: String, timestamp: Date) {
        val message = BitchatMessage(
            id = messageID,
            sender = myNickname,
            content = content,
            timestamp = timestamp,
            isRelay = false,
            senderPeerID = myPeerID,
            mentions = mentions
        )
        val payload = message.toBinaryPayload() ?: return
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = 7u,
            senderID = myPeerID,
            payload = payload
        )
        broadcastPacket(packet)
    }

    override fun sendPrivateMessage(content: String, to: PeerID, recipientNickname: String, messageID: String) {
        scope.launch {
            try {
                val message = BitchatMessage(
                    id = messageID,
                    sender = myNickname,
                    content = content,
                    timestamp = Date(),
                    isRelay = false,
                    isPrivate = true,
                    recipientNickname = recipientNickname,
                    senderPeerID = myPeerID
                )
                val payload = message.toBinaryPayload() ?: return@launch
                val noisePayload = NoisePayload(NoisePayloadType.PRIVATE_MESSAGE, payload)
                val encrypted = noiseSessionManager.encrypt(noisePayload.encode(), to)

                val packet = BitchatPacket(
                    type = MessageType.NOISE_ENCRYPTED.value,
                    ttl = 7u,
                    senderID = myPeerID,
                    payload = encrypted
                )
                sendPacketToPeer(to, packet)
                delegate?.didUpdateMessageDeliveryStatus(messageID, DeliveryStatus.Sent)
            } catch (e: Exception) {
                Timber.e(e, "Private message failed via Wi-Fi Aware")
                delegate?.didUpdateMessageDeliveryStatus(messageID, DeliveryStatus.Failed(e.message ?: "Error"))
            }
        }
    }

    override fun sendReadReceipt(receipt: ReadReceipt, to: PeerID) {
        scope.launch {
            try {
                val data = receipt.messageId.toByteArray(Charsets.UTF_8)
                val noisePayload = NoisePayload(NoisePayloadType.READ_RECEIPT, data)
                val encrypted = noiseSessionManager.encrypt(noisePayload.encode(), to)
                val packet = BitchatPacket(
                    type = MessageType.NOISE_ENCRYPTED.value, ttl = 1u,
                    senderID = myPeerID, payload = encrypted
                )
                sendPacketToPeer(to, packet)
            } catch (e: Exception) {
                Timber.d("Read receipt failed: ${e.message}")
            }
        }
    }

    override fun sendFavoriteNotification(to: PeerID, isFavorite: Boolean) {}
    override fun sendBroadcastAnnounce() {
        val payload = myNickname.toByteArray(Charsets.UTF_8)
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value, ttl = 7u,
            senderID = myPeerID, payload = payload
        )
        broadcastPacket(packet)
    }

    override fun sendDeliveryAck(messageID: String, to: PeerID) {
        scope.launch {
            try {
                val data = messageID.toByteArray(Charsets.UTF_8)
                val noisePayload = NoisePayload(NoisePayloadType.DELIVERED, data)
                val encrypted = noiseSessionManager.encrypt(noisePayload.encode(), to)
                val packet = BitchatPacket(
                    type = MessageType.NOISE_ENCRYPTED.value, ttl = 1u,
                    senderID = myPeerID, payload = encrypted
                )
                sendPacketToPeer(to, packet)
            } catch (e: Exception) {
                Timber.d("ACK failed: ${e.message}")
            }
        }
    }

    override fun sendFileBroadcast(packet: ByteArray, transferId: String) {}
    override fun sendFilePrivate(packet: ByteArray, to: PeerID, transferId: String) {}
    override fun cancelTransfer(transferId: String) {}
    override fun sendVerifyChallenge(to: PeerID, noiseKeyHex: String, nonceA: ByteArray) {}
    override fun sendVerifyResponse(to: PeerID, noiseKeyHex: String, nonceA: ByteArray) {}
    override fun acceptPendingFile(id: String): String? = null
    override fun declinePendingFile(id: String) {}

    override fun triggerHandshake(peerID: PeerID) {
        scope.launch {
            try {
                val initMsg = noiseSessionManager.initiateHandshake(peerID)
                val packet = BitchatPacket(
                    type = MessageType.NOISE_HANDSHAKE.value, ttl = 1u,
                    senderID = myPeerID, payload = initMsg
                )
                sendPacketToPeer(peerID, packet)
            } catch (e: Exception) {
                Timber.e(e, "Handshake failed via Wi-Fi Aware")
            }
        }
    }

    override fun isPeerConnected(peerID: PeerID): Boolean = peers[peerID]?.isConnected == true

    override fun isPeerReachable(peerID: PeerID): Boolean {
        val info = peers[peerID] ?: return false
        return info.isConnected || (System.currentTimeMillis() - info.lastSeen < PEER_TIMEOUT_MS)
    }

    override fun peerNickname(peerID: PeerID): String? = peers[peerID]?.nickname

    override fun getPeerNicknames(): Map<PeerID, String> =
        peers.entries.associate { (k, v) -> k to v.nickname }

    override fun getFingerprint(peerID: PeerID): String? = null

    override fun getNoiseSessionState(peerID: PeerID): LazyHandshakeState =
        LazyHandshakeState.None

    override fun getNoiseService(): NoiseEncryptionServiceInterface {
        return object : NoiseEncryptionServiceInterface {
            override suspend fun getSessionManager(): NoiseSessionManagerInterface {
                return object : NoiseSessionManagerInterface {
                    override suspend fun initiateHandshake(peerID: PeerID) =
                        noiseSessionManager.initiateHandshake(peerID)
                    override suspend fun handleIncomingHandshake(peerID: PeerID, message: ByteArray) =
                        noiseSessionManager.handleIncomingHandshake(peerID, message)
                    override suspend fun encrypt(plaintext: ByteArray, peerID: PeerID) =
                        noiseSessionManager.encrypt(plaintext, peerID)
                    override suspend fun decrypt(ciphertext: ByteArray, peerID: PeerID) =
                        noiseSessionManager.decrypt(ciphertext, peerID)
                    override suspend fun getRemoteStaticKey(peerID: PeerID) =
                        noiseSessionManager.getRemoteStaticKey(peerID)
                    override suspend fun removeSession(peerID: PeerID) =
                        noiseSessionManager.removeSession(peerID)
                }
            }
        }
    }

    override fun sendRawData(peerID: PeerID, data: ByteArray): Boolean {
        val peerInfo = peers[peerID] ?: return false
        val output = peerInfo.outputStream ?: return false
        return try {
            synchronized(output) {
                output.writeInt(data.size)
                output.write(data)
                output.flush()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun broadcastRawData(data: ByteArray) {
        for ((_, peerInfo) in peers) {
            if (!peerInfo.isConnected) continue
            val output = peerInfo.outputStream ?: continue
            scope.launch {
                try {
                    synchronized(output) {
                        output.writeInt(data.size)
                        output.write(data)
                        output.flush()
                    }
                } catch (e: Exception) {
                    Timber.w("Broadcast to ${peerInfo.peerID.id} failed")
                }
            }
        }
    }

    private fun broadcastPacket(packet: BitchatPacket) {
        val data = packet.toBinaryData() ?: return
        broadcastRawData(data)
    }

    // ============ Helpers ============

    private fun buildServiceInfo(): ByteArray {
        val peerIDBytes = myPeerID.routingData ?: ByteArray(8)
        val nicknameBytes = myNickname.toByteArray(Charsets.UTF_8)
        val portBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            .putInt(serverSocket?.localPort ?: 0).array()
        return peerIDBytes + portBytes + nicknameBytes
    }

    private fun parsePeerIDFromServiceInfo(data: ByteArray): PeerID? {
        if (data.size < 8) return null
        return PeerID.fromRoutingData(data.copyOfRange(0, 8))
    }

    private fun parseNicknameFromServiceInfo(data: ByteArray): String {
        if (data.size <= 12) return "" // 8 peerID + 4 port
        return String(data, 12, data.size - 12, Charsets.UTF_8)
    }

    private fun handlePeerDisconnected(peerID: PeerID) {
        val info = peers.remove(peerID) ?: return
        info.socket?.close()
        info.outputStream?.close()
        info.inputStream?.close()
        info.peerHandle?.let { handleToPeer.remove(it.peerId) }

        delegate?.didDisconnectFromPeer(peerID)
        publishPeerData()
    }

    private fun closeAllConnections() {
        for ((_, info) in peers) {
            info.socket?.close()
            info.outputStream?.close()
            info.inputStream?.close()
        }
        peers.clear()
        handleToPeer.clear()
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
        val stalePeers = peers.entries.filter { (_, info) ->
            now - info.lastSeen > PEER_TIMEOUT_MS && !info.isConnected
        }
        for ((peerID, _) in stalePeers) {
            handlePeerDisconnected(peerID)
        }
        messageDeduplicator.cleanup()
    }

    private fun publishPeerData() {
        val snapshots = peers.values.map { info ->
            TransportPeerSnapshot(
                peerID = info.peerID,
                nickname = info.nickname,
                isConnected = info.isConnected,
                isReachable = true,
                lastSeen = info.lastSeen
            )
        }
        _peerSnapshots.value = snapshots
        delegate?.didUpdatePeerList(peers.keys.toList())
    }
}
