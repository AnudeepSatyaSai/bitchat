// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import chat.bitchat.app.model.*
import chat.bitchat.app.noise.NoiseHandshakeState
import chat.bitchat.app.noise.NoiseSessionManager
import chat.bitchat.app.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE Transport for BitChat mesh networking.
 * Port of Swift's BLEService — same UUIDs, same GATT layout, same advertising payload.
 *
 * Simultaneously acts as:
 * - GATT Server (peripheral role): advertises and accepts connections
 * - GATT Client (central role): scans and connects to peers
 *
 * Wire-compatible with Swift/iOS peers (same service UUID, characteristic UUID, packet format).
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val localStaticPrivateKey: ByteArray,
    private val localStaticPublicKey: ByteArray,
    private val deviceID: PeerID
) : Transport {

    // ============ Constants — MUST match Swift BLEService exactly ============

    companion object {
        // Debug/Release UUIDs match Swift
        val SERVICE_UUID_DEBUG: UUID = UUID.fromString("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5A")
        val SERVICE_UUID_RELEASE: UUID = UUID.fromString("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")
        val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb") // CCC descriptor

        private const val DEFAULT_FRAGMENT_SIZE = 180
        private const val BLE_MAX_MTU = 512
        private const val MESSAGE_TTL: Int = 7
        private const val ANNOUNCE_MIN_INTERVAL_MS = 5000L
        private const val MAX_MESSAGE_LENGTH = 2000
        private const val SCAN_PERIOD_MS = 10_000L
        private const val MAINTENANCE_INTERVAL_MS = 15_000L
        private const val MAX_CENTRAL_LINKS = 7
        private const val CONNECT_RATE_LIMIT_MS = 2000L
        private const val PEER_TIMEOUT_MS = 120_000L // 2 minutes
    }

    // Use debug UUID for now; switch via BuildConfig in release
    private val serviceUUID = SERVICE_UUID_DEBUG

    // ============ Transport Interface Properties ============

    override val name: String = "ble"
    override var isAvailable: Boolean = false
        private set

    override val myPeerID: PeerID = deviceID
    override var myNickname: String = "anon"
    override var delegate: BitchatDelegate? = null

    private val _peerSnapshots = MutableStateFlow<List<TransportPeerSnapshot>>(emptyList())
    override val peerSnapshots: StateFlow<List<TransportPeerSnapshot>> = _peerSnapshots.asStateFlow()

    // ============ BLE Core Objects ============

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattCharacteristic: BluetoothGattCharacteristic? = null

    // ============ Peer Tracking ============

    data class PeerInfo(
        val peerID: PeerID,
        var nickname: String = "",
        var isConnected: Boolean = false,
        var noisePublicKey: ByteArray? = null,
        var lastSeen: Long = System.currentTimeMillis()
    )

    private val peers = ConcurrentHashMap<PeerID, PeerInfo>()
    private val gattConnections = ConcurrentHashMap<String, BluetoothGatt>() // device address → gatt
    private val deviceToPeer = ConcurrentHashMap<String, PeerID>()           // device address → peerID
    private val peerToDevice = ConcurrentHashMap<PeerID, String>()           // peerID → device address

    // ============ Message Handling ============

    private val messageDeduplicator = MessageDeduplicator()
    private val noiseSessionManager = NoiseSessionManager(localStaticPrivateKey, localStaticPublicKey)
    private val pendingWriteBuffers = ConcurrentHashMap<String, ByteArray>()
    private val negotiatedMTU = ConcurrentHashMap<String, Int>()

    // ============ Coroutine Scope ============

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null
    private var maintenanceJob: Job? = null
    private var lastAnnounceSent = 0L
    private var lastGlobalConnectAttempt = 0L

    // ============ Lifecycle ============

    override fun startServices() {
        Timber.i("BLE Transport starting...")
        val adapter = bluetoothAdapter ?: run {
            Timber.e("Bluetooth adapter is null")
            delegate?.didUpdateTransportState(name, TransportState.UNSUPPORTED)
            return
        }

        if (!adapter.isEnabled) {
            Timber.w("Bluetooth is not enabled")
            delegate?.didUpdateTransportState(name, TransportState.POWERED_OFF)
            return
        }

        isAvailable = true
        delegate?.didUpdateTransportState(name, TransportState.POWERED_ON)

        setupGattServer()
        startAdvertising()
        startScanning()
        startMaintenance()
    }

    override fun stopServices() {
        Timber.i("BLE Transport stopping...")
        scope.coroutineContext.cancelChildren()
        stopAdvertising()
        stopScanning()
        closeAllConnections()
        gattServer?.close()
        gattServer = null
        isAvailable = false
    }

    override fun emergencyDisconnectAll() {
        Timber.w("Emergency disconnect all!")
        closeAllConnections()
        peers.clear()
        publishPeerData()
    }

    // ============ GATT Server (Peripheral Role) ============

    private fun setupGattServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

        val service = BluetoothGattService(serviceUUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        gattCharacteristic = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or
                BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        // Add CCC descriptor for notifications
        val cccDescriptor = BluetoothGattDescriptor(
            CLIENT_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        gattCharacteristic!!.addDescriptor(cccDescriptor)
        service.addCharacteristic(gattCharacteristic!!)

        gattServer?.addService(service)
        Timber.d("GATT Server set up with service UUID: $serviceUUID")
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Timber.d("Peripheral: Device connected: ${device.address}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Timber.d("Peripheral: Device disconnected: ${device.address}")
                    handleDeviceDisconnected(device.address)
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            // Return our announce data (peer ID + capabilities)
            val announceData = buildAnnouncePayload()
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, announceData)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray?
        ) {
            value?.let { data ->
                handleIncomingData(device.address, data)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray?
        ) {
            // Handle CCC descriptor write (enable/disable notifications)
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.w("Notification send failed to ${device.address}: status=$status")
            }
        }
    }

    // ============ Advertising (Peripheral Role) ============

    private fun startAdvertising() {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: run {
            Timber.e("BLE Advertising not supported")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0) // Advertise indefinitely
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false) // Privacy: no device name
            .addServiceUuid(ParcelUuid(serviceUUID))
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
        Timber.d("BLE Advertising started")
    }

    private fun stopAdvertising() {
        advertiser?.stopAdvertising(advertiseCallback)
        Timber.d("BLE Advertising stopped")
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Timber.i("BLE Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Timber.e("BLE Advertising failed with error code: $errorCode")
        }
    }

    // ============ Scanning (Central Role) ============

    private fun startScanning() {
        scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            Timber.e("BLE Scanner not available")
            return
        }

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(serviceUUID))
                .build()
        )

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        scanner?.startScan(filters, scanSettings, scanCallback)
        Timber.d("BLE Scanning started for service UUID: $serviceUUID")
    }

    private fun stopScanning() {
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Timber.w("Error stopping scan: ${e.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Timber.e("BLE Scan failed with error code: $errorCode")
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val device = result.device
        val address = device.address

        // Skip if already connected or connecting
        if (gattConnections.containsKey(address)) return

        // Rate limit connection attempts
        val now = System.currentTimeMillis()
        if (now - lastGlobalConnectAttempt < CONNECT_RATE_LIMIT_MS) return

        // Connection budget check
        if (gattConnections.size >= MAX_CENTRAL_LINKS) return

        lastGlobalConnectAttempt = now
        connectToDevice(device)
    }

    // ============ GATT Client (Central Role) ============

    private fun connectToDevice(device: BluetoothDevice) {
        Timber.d("Central: Connecting to ${device.address}")

        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattClientCallback)
        }

        gattConnections[device.address] = gatt
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Timber.d("Central: Connected to $address, requesting MTU")
                    gatt.requestMtu(BLE_MAX_MTU)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Timber.d("Central: Disconnected from $address")
                    gattConnections.remove(address)
                    handleDeviceDisconnected(address)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val address = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMTU[address] = mtu
                Timber.d("MTU negotiated with $address: $mtu")
            }
            // Discover services after MTU negotiation
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.e("Service discovery failed for ${gatt.device.address}")
                gatt.disconnect()
                return
            }

            val service = gatt.getService(serviceUUID)
            val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
            if (characteristic == null) {
                Timber.e("BitChat characteristic not found on ${gatt.device.address}")
                gatt.disconnect()
                return
            }

            // Enable notifications
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_UUID)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }

            // Read characteristic to get peer's announce data
            gatt.readCharacteristic(characteristic)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                characteristic.value?.let { data ->
                    handleAnnounceData(gatt.device.address, data)
                }

                // Send our announce after reading peer's
                sendAnnounceToDevice(gatt)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            characteristic.value?.let { data ->
                handleIncomingData(gatt.device.address, data)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.w("Write failed to ${gatt.device.address}: status=$status")
            }
        }
    }

    // ============ Data Handling ============

    private fun handleAnnounceData(deviceAddress: String, data: ByteArray) {
        // Parse announce payload: [8-byte peerID][remaining nickname bytes]
        if (data.size < 8) return

        val peerIDBytes = data.copyOfRange(0, 8)
        val peerID = PeerID.fromRoutingData(peerIDBytes) ?: return

        // Don't connect to ourselves
        if (peerID == myPeerID) return

        val nickname = if (data.size > 8) {
            String(data, 8, data.size - 8, Charsets.UTF_8)
        } else {
            ""
        }

        deviceToPeer[deviceAddress] = peerID
        peerToDevice[peerID] = deviceAddress

        val peerInfo = peers.getOrPut(peerID) { PeerInfo(peerID) }
        peerInfo.nickname = nickname
        peerInfo.isConnected = true
        peerInfo.lastSeen = System.currentTimeMillis()

        Timber.i("Peer discovered: ${peerID.id} nickname='$nickname' via BLE")

        delegate?.didConnectToPeer(peerID)
        publishPeerData()
    }

    private fun handleIncomingData(deviceAddress: String, data: ByteArray) {
        scope.launch {
            try {
                val packet = BitchatPacket.from(data) ?: run {
                    Timber.d("Failed to decode packet from $deviceAddress (${data.size} bytes)")
                    return@launch
                }

                // Determine sender PeerID
                val senderPeerID = deviceToPeer[deviceAddress]
                    ?: PeerID.fromRoutingData(packet.senderID)
                    ?: return@launch

                // Dedup
                val packetKey = messageDeduplicator.packetKey(packet)
                if (messageDeduplicator.isDuplicate(packetKey)) return@launch
                messageDeduplicator.markSeen(packetKey)

                // Route based on message type
                val msgType = MessageType.fromByte(packet.type.toByte())
                when (msgType) {
                    MessageType.ANNOUNCE -> handleAnnouncePacket(senderPeerID, packet)
                    MessageType.MESSAGE -> handlePublicMessage(senderPeerID, packet)
                    MessageType.LEAVE -> handleLeavePacket(senderPeerID)
                    MessageType.NOISE_HANDSHAKE -> handleNoiseHandshake(senderPeerID, packet)
                    MessageType.NOISE_ENCRYPTED -> handleNoiseEncrypted(senderPeerID, packet)
                    MessageType.FRAGMENT -> handleFragment(senderPeerID, packet)
                    MessageType.FILE_TRANSFER -> handleFileTransfer(senderPeerID, packet)
                    MessageType.REQUEST_SYNC -> handleRequestSync(senderPeerID, packet)
                    null -> Timber.w("Unknown message type: 0x${packet.type.toString(16)}")
                }

                // Relay if TTL allows
                if (packet.ttl > 1u) {
                    relayPacket(packet, excludeDevice = deviceAddress)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error handling data from $deviceAddress")
            }
        }
    }

    // ============ Message Type Handlers ============

    private fun handleAnnouncePacket(senderID: PeerID, packet: BitchatPacket) {
        val nickname = String(packet.payload, Charsets.UTF_8)
        val info = peers.getOrPut(senderID) { PeerInfo(senderID) }
        info.nickname = nickname
        info.lastSeen = System.currentTimeMillis()
        Timber.d("Announce from ${senderID.id}: '$nickname'")
        publishPeerData()
    }

    private fun handlePublicMessage(senderID: PeerID, packet: BitchatPacket) {
        val message = BitchatMessage.fromBinaryPayload(packet.payload) ?: run {
            // Fallback: treat payload as raw text
            val content = String(packet.payload, Charsets.UTF_8)
            val nickname = peers[senderID]?.nickname ?: senderID.id.take(8)
            delegate?.didReceivePublicMessage(
                from = senderID,
                nickname = nickname,
                content = content,
                timestamp = Date(packet.timestamp.toLong()),
                messageID = null
            )
            return
        }

        delegate?.didReceiveMessage(message)
    }

    private fun handleLeavePacket(senderID: PeerID) {
        peers.remove(senderID)
        delegate?.didDisconnectFromPeer(senderID)
        publishPeerData()
    }

    private suspend fun handleNoiseHandshake(senderID: PeerID, packet: BitchatPacket) {
        try {
            val response = noiseSessionManager.handleIncomingHandshake(senderID, packet.payload)
            if (response != null) {
                // Send handshake response
                val responsePacket = BitchatPacket(
                    type = MessageType.NOISE_HANDSHAKE.value,
                    ttl = 1u,
                    senderID = myPeerID,
                    payload = response
                )
                sendPacketToPeer(senderID, responsePacket)
            }
        } catch (e: Exception) {
            Timber.e(e, "Noise handshake failed with ${senderID.id}")
        }
    }

    private suspend fun handleNoiseEncrypted(senderID: PeerID, packet: BitchatPacket) {
        try {
            val decrypted = noiseSessionManager.decrypt(packet.payload, senderID)
            val noisePayload = NoisePayload.decode(decrypted) ?: return

            delegate?.didReceiveNoisePayload(
                from = senderID,
                type = noisePayload.type,
                payload = noisePayload.data,
                timestamp = Date(packet.timestamp.toLong())
            )
        } catch (e: Exception) {
            Timber.e(e, "Noise decryption failed from ${senderID.id}")
        }
    }

    private fun handleFragment(senderID: PeerID, packet: BitchatPacket) {
        // TODO: Implement fragment reassembly (same logic as Swift)
        Timber.d("Fragment received from ${senderID.id}")
    }

    private fun handleFileTransfer(senderID: PeerID, packet: BitchatPacket) {
        // TODO: Implement file transfer handling
        Timber.d("File transfer from ${senderID.id}")
    }

    private fun handleRequestSync(senderID: PeerID, packet: BitchatPacket) {
        // TODO: Implement sync request handling
        Timber.d("Sync request from ${senderID.id}")
    }

    // ============ Sending ============

    private fun buildAnnouncePayload(): ByteArray {
        val peerIDBytes = myPeerID.routingData ?: ByteArray(8)
        val nicknameBytes = myNickname.toByteArray(Charsets.UTF_8)
        return peerIDBytes + nicknameBytes
    }

    private fun sendAnnounceToDevice(gatt: BluetoothGatt) {
        val announcePayload = myNickname.toByteArray(Charsets.UTF_8)
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = MESSAGE_TTL.toUByte(),
            senderID = myPeerID,
            payload = announcePayload
        )
        val data = packet.toBinaryData() ?: return
        writeToGatt(gatt, data)
    }

    override fun sendMessage(content: String, mentions: List<String>) {
        sendMessage(content, mentions, UUID.randomUUID().toString(), Date())
    }

    override fun sendMessage(content: String, mentions: List<String>, messageID: String, timestamp: Date) {
        if (content.length > MAX_MESSAGE_LENGTH) {
            Timber.w("Message too long: ${content.length} > $MAX_MESSAGE_LENGTH")
            return
        }

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
            ttl = MESSAGE_TTL.toUByte(),
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
                    ttl = MESSAGE_TTL.toUByte(),
                    senderID = myPeerID,
                    payload = encrypted
                )

                sendPacketToPeer(to, packet)
                delegate?.didUpdateMessageDeliveryStatus(messageID, DeliveryStatus.Sent)
            } catch (e: Exception) {
                Timber.e(e, "Failed to send private message to ${to.id}")
                delegate?.didUpdateMessageDeliveryStatus(messageID, DeliveryStatus.Failed(e.message ?: "Unknown error"))
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
                    type = MessageType.NOISE_ENCRYPTED.value,
                    ttl = 1u,
                    senderID = myPeerID,
                    payload = encrypted
                )
                sendPacketToPeer(to, packet)
            } catch (e: Exception) {
                Timber.e(e, "Failed to send read receipt to ${to.id}")
            }
        }
    }

    override fun sendFavoriteNotification(to: PeerID, isFavorite: Boolean) {
        // TODO: Implement favorite notification
    }

    override fun sendBroadcastAnnounce() {
        val now = System.currentTimeMillis()
        if (now - lastAnnounceSent < ANNOUNCE_MIN_INTERVAL_MS) return
        lastAnnounceSent = now

        val payload = myNickname.toByteArray(Charsets.UTF_8)
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = MESSAGE_TTL.toUByte(),
            senderID = myPeerID,
            payload = payload
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
                    type = MessageType.NOISE_ENCRYPTED.value,
                    ttl = 1u,
                    senderID = myPeerID,
                    payload = encrypted
                )
                sendPacketToPeer(to, packet)
            } catch (e: Exception) {
                Timber.d("Delivery ACK failed: ${e.message}")
            }
        }
    }

    override fun sendFileBroadcast(packet: ByteArray, transferId: String) {
        // TODO: Implement file broadcast
    }

    override fun sendFilePrivate(packet: ByteArray, to: PeerID, transferId: String) {
        // TODO: Implement private file transfer
    }

    override fun cancelTransfer(transferId: String) {
        // TODO: Implement transfer cancellation
    }

    override fun sendVerifyChallenge(to: PeerID, noiseKeyHex: String, nonceA: ByteArray) {
        // TODO: Implement QR verification challenge
    }

    override fun sendVerifyResponse(to: PeerID, noiseKeyHex: String, nonceA: ByteArray) {
        // TODO: Implement QR verification response
    }

    override fun acceptPendingFile(id: String): String? {
        // TODO: Implement file acceptance
        return null
    }

    override fun declinePendingFile(id: String) {
        // TODO: Implement file decline
    }

    override fun triggerHandshake(peerID: PeerID) {
        scope.launch {
            try {
                val initMessage = noiseSessionManager.initiateHandshake(peerID)
                val packet = BitchatPacket(
                    type = MessageType.NOISE_HANDSHAKE.value,
                    ttl = 1u,
                    senderID = myPeerID,
                    payload = initMessage
                )
                sendPacketToPeer(peerID, packet)
            } catch (e: Exception) {
                Timber.e(e, "Handshake initiation failed for ${peerID.id}")
            }
        }
    }

    override fun sendRawData(peerID: PeerID, data: ByteArray): Boolean {
        val deviceAddress = peerToDevice[peerID] ?: return false
        val gatt = gattConnections[deviceAddress] ?: return false
        return writeToGatt(gatt, data)
    }

    override fun broadcastRawData(data: ByteArray) {
        for ((_, gatt) in gattConnections) {
            writeToGatt(gatt, data)
        }
        broadcastViaNotifications(data)
    }

    // ============ Internal Send Helpers ============

    private fun sendPacketToPeer(peerID: PeerID, packet: BitchatPacket) {
        val data = packet.toBinaryData() ?: return
        val deviceAddress = peerToDevice[peerID]

        if (deviceAddress != null) {
            val gatt = gattConnections[deviceAddress]
            if (gatt != null) {
                writeToGatt(gatt, data)
                return
            }
        }

        // If not directly connected, broadcast
        broadcastPacket(packet)
    }

    private fun broadcastPacket(packet: BitchatPacket) {
        val data = packet.toBinaryData() ?: return
        broadcastRawData(data)
    }

    private fun relayPacket(packet: BitchatPacket, excludeDevice: String) {
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

        for ((address, gatt) in gattConnections) {
            if (address != excludeDevice) {
                writeToGatt(gatt, data)
            }
        }

        broadcastViaNotifications(data, excludeDevice)
    }

    private fun writeToGatt(gatt: BluetoothGatt, data: ByteArray): Boolean {
        val service = gatt.getService(serviceUUID) ?: return false
        val char = service.getCharacteristic(CHARACTERISTIC_UUID) ?: return false

        val mtu = negotiatedMTU[gatt.device.address] ?: 20
        val chunkSize = mtu - 3 // ATT overhead

        if (data.size <= chunkSize) {
            char.value = data
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            return gatt.writeCharacteristic(char)
        }

        // Fragment for MTU (L2CAP level, not protocol level)
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + chunkSize, data.size)
            val chunk = data.copyOfRange(offset, end)
            char.value = chunk
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            gatt.writeCharacteristic(char)
            offset = end
        }
        return true
    }

    private fun broadcastViaNotifications(data: ByteArray, excludeDevice: String? = null) {
        val char = gattCharacteristic ?: return
        val server = gattServer ?: return

        val connectedDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
        for (device in connectedDevices) {
            if (device.address == excludeDevice) continue
            char.value = data
            server.notifyCharacteristicChanged(device, char, false)
        }
    }

    // ============ Connection Management ============

    private fun handleDeviceDisconnected(deviceAddress: String) {
        val peerID = deviceToPeer.remove(deviceAddress) ?: return
        peerToDevice.remove(peerID)
        gattConnections.remove(deviceAddress)?.close()
        negotiatedMTU.remove(deviceAddress)

        peers[peerID]?.isConnected = false

        delegate?.didDisconnectFromPeer(peerID)
        publishPeerData()
    }

    private fun closeAllConnections() {
        for ((address, gatt) in gattConnections) {
            gatt.close()
            Timber.d("Closed connection to $address")
        }
        gattConnections.clear()
        deviceToPeer.clear()
        peerToDevice.clear()
        negotiatedMTU.clear()
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

        // Prune stale peers
        val stalePeers = peers.entries.filter { (_, info) ->
            now - info.lastSeen > PEER_TIMEOUT_MS && !info.isConnected
        }
        for ((peerID, _) in stalePeers) {
            peers.remove(peerID)
            delegate?.didDisconnectFromPeer(peerID)
        }

        // Periodic announce
        sendBroadcastAnnounce()

        // Publish updated peer data
        if (stalePeers.isNotEmpty()) {
            publishPeerData()
        }

        // Clean deduplicator
        messageDeduplicator.cleanup()
    }

    // ============ Peer Queries ============

    override fun isPeerConnected(peerID: PeerID): Boolean = peers[peerID]?.isConnected == true

    override fun isPeerReachable(peerID: PeerID): Boolean {
        val info = peers[peerID] ?: return false
        val now = System.currentTimeMillis()
        return info.isConnected || (now - info.lastSeen < PEER_TIMEOUT_MS)
    }

    override fun peerNickname(peerID: PeerID): String? = peers[peerID]?.nickname

    override fun getPeerNicknames(): Map<PeerID, String> {
        return peers.entries.associate { (k, v) -> k to v.nickname }
    }

    override fun getFingerprint(peerID: PeerID): String? {
        return peers[peerID]?.noisePublicKey?.let {
            chat.bitchat.app.model.PeerID.sha256Fingerprint(it)
        }
    }

    override fun getNoiseSessionState(peerID: PeerID): LazyHandshakeState {
        // Query the noise session manager state
        return LazyHandshakeState.None // Simplified for now
    }

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

    // ============ Peer Data Publishing ============

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

/**
 * Simple message deduplication using a time-bounded set.
 * Port of Swift's message deduplication bloom filter approach,
 * using a simpler Set-based approach for correctness.
 */
class MessageDeduplicator {
    private data class Entry(val key: String, val timestamp: Long)

    private val seen = mutableSetOf<String>()
    private val entries = mutableListOf<Entry>()
    private val maxAge = 120_000L // 2 minutes
    private val maxSize = 10_000
    private val lock = Object()

    fun packetKey(packet: BitchatPacket): String {
        val senderHex = packet.senderID.joinToString("") { "%02x".format(it) }
        return "$senderHex:${packet.timestamp}:${packet.type}"
    }

    fun isDuplicate(key: String): Boolean = synchronized(lock) {
        seen.contains(key)
    }

    fun markSeen(key: String) = synchronized(lock) {
        seen.add(key)
        entries.add(Entry(key, System.currentTimeMillis()))
        if (entries.size > maxSize) {
            cleanup()
        }
    }

    fun cleanup() = synchronized(lock) {
        val cutoff = System.currentTimeMillis() - maxAge
        entries.removeAll { entry ->
            if (entry.timestamp < cutoff) {
                seen.remove(entry.key)
                true
            } else false
        }
    }
}
