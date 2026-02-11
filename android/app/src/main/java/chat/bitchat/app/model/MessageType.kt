// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.model

/**
 * Simplified BitChat protocol message types.
 * Wire-compatible with Swift's MessageType enum.
 * All private communication metadata is embedded in noiseEncrypted payloads.
 */
enum class MessageType(val value: UByte) {
    // Public messages (unencrypted)
    ANNOUNCE(0x01u),         // "I'm here" with nickname
    MESSAGE(0x02u),          // Public chat message
    LEAVE(0x03u),            // "I'm leaving"
    REQUEST_SYNC(0x21u),     // GCS filter-based sync request (local-only)

    // Noise encryption
    NOISE_HANDSHAKE(0x10u),  // Handshake (init or response determined by payload)
    NOISE_ENCRYPTED(0x11u),  // All encrypted payloads (messages, receipts, etc.)

    // Fragmentation
    FRAGMENT(0x20u),         // Single fragment type for large messages
    FILE_TRANSFER(0x22u);    // Binary file/audio/image payloads

    companion object {
        fun fromByte(value: UByte): MessageType? = entries.firstOrNull { it.value == value }
        fun fromByte(value: Byte): MessageType? = fromByte(value.toUByte())
    }
}
