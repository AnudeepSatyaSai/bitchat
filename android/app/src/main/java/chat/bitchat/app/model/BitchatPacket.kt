// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.model

/**
 * The core packet structure for all BitChat protocol messages.
 * Encapsulates all data needed for routing through the mesh network,
 * including TTL for hop limiting and optional encryption.
 *
 * Wire format is identical to the Swift BitchatPacket — same field order,
 * same byte sizes, same endianness (big-endian).
 *
 * @see chat.bitchat.app.protocol.BinaryProtocol for encoding/decoding.
 */
data class BitchatPacket(
    val version: UByte,
    val type: UByte,
    val senderID: ByteArray,
    val recipientID: ByteArray?,
    val timestamp: ULong,
    val payload: ByteArray,
    var signature: ByteArray?,
    var ttl: UByte,
    var route: List<ByteArray>? = null,
    var isRSR: Boolean = false
) {

    /**
     * Convenience constructor for new packets.
     * Converts hex PeerID to 8-byte binary sender data, sets timestamp to now.
     */
    constructor(
        type: UByte,
        ttl: UByte,
        senderID: PeerID,
        payload: ByteArray,
        isRSR: Boolean = false
    ) : this(
        version = 1u,
        type = type,
        senderID = senderID.routingData ?: ByteArray(8),
        recipientID = null,
        timestamp = (System.currentTimeMillis()).toULong(),
        payload = payload,
        signature = null,
        ttl = ttl,
        route = null,
        isRSR = isRSR
    )

    /** Encode to binary with padding (default) */
    fun toBinaryData(padding: Boolean = true): ByteArray? {
        return chat.bitchat.app.protocol.BinaryProtocol.encode(this, padding)
    }

    /**
     * Create binary representation for signing (without signature and TTL fields).
     * TTL is excluded because it changes during packet relay operations.
     */
    fun toBinaryDataForSigning(): ByteArray? {
        val unsignedPacket = BitchatPacket(
            version = version,
            type = type,
            senderID = senderID,
            recipientID = recipientID,
            timestamp = timestamp,
            payload = payload,
            signature = null,    // Remove signature for signing
            ttl = 0u,            // Fixed TTL=0 for signing (relay compatibility)
            route = route,
            isRSR = false        // RSR flag is mutable, not part of signature
        )
        return chat.bitchat.app.protocol.BinaryProtocol.encode(unsignedPacket, padding = false)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BitchatPacket) return false
        return version == other.version &&
            type == other.type &&
            senderID.contentEquals(other.senderID) &&
            (recipientID?.contentEquals(other.recipientID ?: ByteArray(0)) ?: (other.recipientID == null)) &&
            timestamp == other.timestamp &&
            payload.contentEquals(other.payload) &&
            (signature?.contentEquals(other.signature ?: ByteArray(0)) ?: (other.signature == null)) &&
            ttl == other.ttl &&
            isRSR == other.isRSR
    }

    override fun hashCode(): Int {
        var result = version.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + senderID.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + ttl.hashCode()
        return result
    }

    companion object {
        /** Decode from binary data */
        fun from(data: ByteArray): BitchatPacket? {
            return chat.bitchat.app.protocol.BinaryProtocol.decode(data)
        }
    }
}
