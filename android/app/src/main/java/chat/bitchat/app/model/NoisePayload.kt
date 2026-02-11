// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.model

/**
 * Types of payloads embedded within noiseEncrypted messages.
 * The first byte of decrypted Noise payload indicates the type.
 * This provides privacy — observers can't distinguish message types.
 *
 * Wire-compatible with Swift's NoisePayloadType enum.
 */
enum class NoisePayloadType(val value: UByte) {
    // Messages and status
    PRIVATE_MESSAGE(0x01u),    // Private chat message
    READ_RECEIPT(0x02u),       // Message was read
    DELIVERED(0x03u),          // Message was delivered

    // Verification (QR-based OOB binding)
    VERIFY_CHALLENGE(0x10u),   // Verification challenge
    VERIFY_RESPONSE(0x11u);    // Verification response

    companion object {
        fun fromByte(value: UByte): NoisePayloadType? = entries.firstOrNull { it.value == value }
        fun fromByte(value: Byte): NoisePayloadType? = fromByte(value.toUByte())
    }
}

/**
 * Helper to create and parse typed Noise payloads.
 * Wire format: [1-byte type] [payload data]
 */
data class NoisePayload(
    val type: NoisePayloadType,
    val data: ByteArray
) {
    /** Encode payload with type prefix */
    fun encode(): ByteArray {
        val result = ByteArray(1 + data.size)
        result[0] = type.value.toByte()
        data.copyInto(result, 1)
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NoisePayload) return false
        return type == other.type && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }

    companion object {
        /** Decode payload from data */
        fun decode(data: ByteArray): NoisePayload? {
            if (data.isEmpty()) return null
            val type = NoisePayloadType.fromByte(data[0]) ?: return null
            val payloadData = if (data.size > 1) data.copyOfRange(1, data.size) else ByteArray(0)
            return NoisePayload(type, payloadData)
        }
    }
}
