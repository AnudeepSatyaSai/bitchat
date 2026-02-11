// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Represents a user-visible message in the BitChat system.
 * Handles both broadcast messages and private encrypted messages,
 * with support for mentions, replies, and delivery tracking.
 *
 * Binary payload format is identical to Swift's BitchatMessage.toBinaryPayload().
 */
data class BitchatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: String,
    val content: String,
    val timestamp: Date,
    val isRelay: Boolean,
    val originalSender: String? = null,
    val isPrivate: Boolean = false,
    val recipientNickname: String? = null,
    val senderPeerID: PeerID? = null,
    val mentions: List<String>? = null,
    var deliveryStatus: DeliveryStatus? = if (isPrivate) DeliveryStatus.Sending else null
) {
    val formattedTimestamp: String
        get() = timestampFormatter.format(timestamp)

    /**
     * Encode message to binary payload for wire transmission.
     * Format must match Swift's BitchatMessage.toBinaryPayload() exactly.
     *
     * Layout:
     * - Flags: 1 byte (bit 0: isRelay, bit 1: isPrivate, bit 2: hasOriginalSender,
     *                   bit 3: hasRecipientNickname, bit 4: hasSenderPeerID, bit 5: hasMentions)
     * - Timestamp: 8 bytes (milliseconds since epoch, big-endian)
     * - ID length: 1 byte + ID: variable
     * - Sender length: 1 byte + Sender: variable
     * - Content length: 2 bytes + Content: variable
     * Optional fields based on flags:
     * - Original sender length + data
     * - Recipient nickname length + data
     * - Sender peer ID length + data
     * - Mentions array
     */
    fun toBinaryPayload(): ByteArray? {
        val buffer = mutableListOf<Byte>()

        // Flags
        var flags: Int = 0
        if (isRelay) flags = flags or 0x01
        if (isPrivate) flags = flags or 0x02
        if (originalSender != null) flags = flags or 0x04
        if (recipientNickname != null) flags = flags or 0x08
        if (senderPeerID != null) flags = flags or 0x10
        if (!mentions.isNullOrEmpty()) flags = flags or 0x20
        buffer.add(flags.toByte())

        // Timestamp (milliseconds, big-endian, 8 bytes)
        val timestampMillis = timestamp.time.toULong()
        for (i in 7 downTo 0) {
            buffer.add(((timestampMillis shr (i * 8)) and 0xFFu).toByte())
        }

        // ID
        val idBytes = id.toByteArray(Charsets.UTF_8)
        buffer.add(idBytes.size.coerceAtMost(255).toByte())
        buffer.addAll(idBytes.take(255).toList())

        // Sender
        val senderBytes = sender.toByteArray(Charsets.UTF_8)
        buffer.add(senderBytes.size.coerceAtMost(255).toByte())
        buffer.addAll(senderBytes.take(255).toList())

        // Content (2-byte length, big-endian)
        val contentBytes = content.toByteArray(Charsets.UTF_8)
        val contentLength = contentBytes.size.coerceAtMost(65535)
        buffer.add(((contentLength shr 8) and 0xFF).toByte())
        buffer.add((contentLength and 0xFF).toByte())
        buffer.addAll(contentBytes.take(contentLength).toList())

        // Optional: Original sender
        if (originalSender != null) {
            val origBytes = originalSender.toByteArray(Charsets.UTF_8)
            buffer.add(origBytes.size.coerceAtMost(255).toByte())
            buffer.addAll(origBytes.take(255).toList())
        }

        // Optional: Recipient nickname
        if (recipientNickname != null) {
            val recipBytes = recipientNickname.toByteArray(Charsets.UTF_8)
            buffer.add(recipBytes.size.coerceAtMost(255).toByte())
            buffer.addAll(recipBytes.take(255).toList())
        }

        // Optional: Sender peer ID
        if (senderPeerID != null) {
            val peerBytes = senderPeerID.id.toByteArray(Charsets.UTF_8)
            buffer.add(peerBytes.size.coerceAtMost(255).toByte())
            buffer.addAll(peerBytes.take(255).toList())
        }

        // Optional: Mentions array
        if (!mentions.isNullOrEmpty()) {
            buffer.add(mentions.size.coerceAtMost(255).toByte())
            for (mention in mentions.take(255)) {
                val mentionBytes = mention.toByteArray(Charsets.UTF_8)
                buffer.add(mentionBytes.size.coerceAtMost(255).toByte())
                buffer.addAll(mentionBytes.take(255).toList())
            }
        }

        return buffer.toByteArray()
    }

    companion object {
        private val timestampFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)

        /**
         * Decode message from binary payload.
         * Must match Swift's BitchatMessage.init(_: Data) exactly.
         */
        fun fromBinaryPayload(data: ByteArray): BitchatMessage? {
            if (data.size < 13) return null

            var offset = 0

            fun readByte(): Int? {
                if (offset >= data.size) return null
                return data[offset++].toInt() and 0xFF
            }

            // Flags
            val flags = readByte() ?: return null
            val isRelay = (flags and 0x01) != 0
            val isPrivate = (flags and 0x02) != 0
            val hasOriginalSender = (flags and 0x04) != 0
            val hasRecipientNickname = (flags and 0x08) != 0
            val hasSenderPeerID = (flags and 0x10) != 0
            val hasMentions = (flags and 0x20) != 0

            // Timestamp (8 bytes, big-endian, milliseconds)
            if (offset + 8 > data.size) return null
            var timestampMillis: Long = 0
            for (i in 0 until 8) {
                timestampMillis = (timestampMillis shl 8) or (data[offset++].toLong() and 0xFF)
            }
            val timestamp = Date(timestampMillis)

            // ID
            val idLength = readByte() ?: return null
            if (offset + idLength > data.size) return null
            val id = String(data, offset, idLength, Charsets.UTF_8)
            offset += idLength

            // Sender
            val senderLength = readByte() ?: return null
            if (offset + senderLength > data.size) return null
            val sender = String(data, offset, senderLength, Charsets.UTF_8)
            offset += senderLength

            // Content (2-byte length, big-endian)
            if (offset + 2 > data.size) return null
            val contentLength = ((data[offset].toInt() and 0xFF) shl 8) or
                (data[offset + 1].toInt() and 0xFF)
            offset += 2
            if (offset + contentLength > data.size) return null
            val content = String(data, offset, contentLength, Charsets.UTF_8)
            offset += contentLength

            // Optional: Original sender
            var originalSender: String? = null
            if (hasOriginalSender && offset < data.size) {
                val length = data[offset++].toInt() and 0xFF
                if (offset + length <= data.size) {
                    originalSender = String(data, offset, length, Charsets.UTF_8)
                    offset += length
                }
            }

            // Optional: Recipient nickname
            var recipientNickname: String? = null
            if (hasRecipientNickname && offset < data.size) {
                val length = data[offset++].toInt() and 0xFF
                if (offset + length <= data.size) {
                    recipientNickname = String(data, offset, length, Charsets.UTF_8)
                    offset += length
                }
            }

            // Optional: Sender peer ID
            var senderPeerID: PeerID? = null
            if (hasSenderPeerID && offset < data.size) {
                val length = data[offset++].toInt() and 0xFF
                if (offset + length <= data.size) {
                    val peerIdStr = String(data, offset, length, Charsets.UTF_8)
                    senderPeerID = PeerID.fromString(peerIdStr)
                    offset += length
                }
            }

            // Optional: Mentions array
            var mentions: List<String>? = null
            if (hasMentions && offset < data.size) {
                val mentionCount = data[offset++].toInt() and 0xFF
                if (mentionCount > 0) {
                    val mentionList = mutableListOf<String>()
                    for (i in 0 until mentionCount) {
                        if (offset >= data.size) break
                        val length = data[offset++].toInt() and 0xFF
                        if (offset + length <= data.size) {
                            mentionList.add(String(data, offset, length, Charsets.UTF_8))
                            offset += length
                        }
                    }
                    mentions = mentionList
                }
            }

            return BitchatMessage(
                id = id,
                sender = sender,
                content = content,
                timestamp = timestamp,
                isRelay = isRelay,
                originalSender = originalSender,
                isPrivate = isPrivate,
                recipientNickname = recipientNickname,
                senderPeerID = senderPeerID,
                mentions = mentions
            )
        }

        /**
         * Filter empty messages, deduplicate by ID, sort oldest to newest.
         */
        fun List<BitchatMessage>.cleanedAndDeduped(): List<BitchatMessage> {
            val filtered = filter { it.content.isNotBlank() }
            if (filtered.size <= 1) return filtered
            val seen = mutableSetOf<String>()
            return filtered
                .sortedBy { it.timestamp }
                .filter { seen.add(it.id) }
        }
    }
}
