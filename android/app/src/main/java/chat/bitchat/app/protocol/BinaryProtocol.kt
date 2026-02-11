// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.protocol

import chat.bitchat.app.model.BitchatPacket
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Low-level binary encoding and decoding for BitChat protocol messages.
 * Optimized for Bluetooth LE's limited bandwidth and MTU constraints.
 *
 * ## Wire Format (MUST match Swift's BinaryProtocol exactly)
 * ```
 * Header (Fixed 14 bytes for v1, 16 bytes for v2):
 * +--------+------+-----+-----------+-------+------------------+
 * |Version | Type | TTL | Timestamp | Flags | PayloadLength    |
 * |1 byte  |1 byte|1byte| 8 bytes   | 1 byte| 2 or 4 bytes     |
 * +--------+------+-----+-----------+-------+------------------+
 *
 * Variable sections:
 * +----------+-------------+---------+------------+
 * | SenderID | RecipientID | Payload | Signature  |
 * | 8 bytes  | 8 bytes*    | Variable| 64 bytes*  |
 * +----------+-------------+---------+------------+
 * * Optional fields based on flags
 * ```
 *
 * All multi-byte values use network byte order (big-endian).
 */
object BinaryProtocol {
    const val V1_HEADER_SIZE = 14
    const val V2_HEADER_SIZE = 16
    const val SENDER_ID_SIZE = 8
    const val RECIPIENT_ID_SIZE = 8
    const val SIGNATURE_SIZE = 64

    // Max allowed payload (matches Swift's FileTransferLimits.maxFramedFileBytes)
    private const val MAX_FRAMED_FILE_BYTES = 10 * 1024 * 1024 // 10 MB

    /** Flag bits — must match Swift's BinaryProtocol.Flags */
    object Flags {
        const val HAS_RECIPIENT: Int = 0x01
        const val HAS_SIGNATURE: Int = 0x02
        const val IS_COMPRESSED: Int = 0x04
        const val HAS_ROUTE: Int = 0x08
        const val IS_RSR: Int = 0x10
    }

    fun headerSize(version: Int): Int? = when (version) {
        1 -> V1_HEADER_SIZE
        2 -> V2_HEADER_SIZE
        else -> null
    }

    private fun lengthFieldSize(version: Int): Int = if (version == 2) 4 else 2

    // ======================== ENCODE ========================

    /**
     * Encode BitchatPacket to binary format.
     * Wire-compatible with Swift's BinaryProtocol.encode().
     */
    fun encode(packet: BitchatPacket, padding: Boolean = true): ByteArray? {
        val version = packet.version.toInt()
        if (version != 1 && version != 2) return null

        // Compression
        var payload = packet.payload
        var isCompressed = false
        var originalPayloadSize: Int? = null

        if (CompressionUtil.shouldCompress(payload)) {
            val maxRepresentable = if (version == 2) UInt.MAX_VALUE.toLong() else UShort.MAX_VALUE.toLong()
            if (payload.size.toLong() <= maxRepresentable) {
                CompressionUtil.compress(payload)?.let { compressed ->
                    originalPayloadSize = payload.size
                    payload = compressed
                    isCompressed = true
                }
            }
        }

        val lengthFieldBytes = lengthFieldSize(version)

        // Route (v2+ only)
        val originalRoute = if (version >= 2) (packet.route ?: emptyList()) else emptyList()
        if (originalRoute.any { it.isEmpty() }) return null

        val sanitizedRoute = originalRoute.map { hop ->
            when {
                hop.size == SENDER_ID_SIZE -> hop
                hop.size > SENDER_ID_SIZE -> hop.copyOfRange(0, SENDER_ID_SIZE)
                else -> hop + ByteArray(SENDER_ID_SIZE - hop.size)
            }
        }
        if (sanitizedRoute.size > 255) return null

        val hasRoute = sanitizedRoute.isNotEmpty()
        val routeLength = if (hasRoute) 1 + sanitizedRoute.size * SENDER_ID_SIZE else 0
        val originalSizeFieldBytes = if (isCompressed) lengthFieldBytes else 0
        val payloadDataSize = payload.size + originalSizeFieldBytes

        if (version == 1 && payloadDataSize > UShort.MAX_VALUE.toInt()) return null
        if (version == 2 && payloadDataSize.toLong() > UInt.MAX_VALUE.toLong()) return null

        val hdrSize = headerSize(version) ?: return null
        val estimatedSize = hdrSize + SENDER_ID_SIZE +
            (if (packet.recipientID != null) RECIPIENT_ID_SIZE else 0) +
            routeLength + payloadDataSize +
            (if (packet.signature != null) SIGNATURE_SIZE else 0) + 255

        val buf = ByteBuffer.allocate(estimatedSize).order(ByteOrder.BIG_ENDIAN)

        // Header
        buf.put(packet.version.toByte())
        buf.put(packet.type.toByte())
        buf.put(packet.ttl.toByte())

        // Timestamp (8 bytes big-endian)
        for (shift in (56 downTo 0 step 8)) {
            buf.put(((packet.timestamp shr shift) and 0xFFu).toByte())
        }

        // Flags
        var flags = 0
        if (packet.recipientID != null) flags = flags or Flags.HAS_RECIPIENT
        if (packet.signature != null) flags = flags or Flags.HAS_SIGNATURE
        if (isCompressed) flags = flags or Flags.IS_COMPRESSED
        if (hasRoute && version >= 2) flags = flags or Flags.HAS_ROUTE
        if (packet.isRSR) flags = flags or Flags.IS_RSR
        buf.put(flags.toByte())

        // PayloadLength
        if (version == 2) {
            buf.putInt(payloadDataSize)
        } else {
            buf.putShort(payloadDataSize.toShort())
        }

        // SenderID (8 bytes, zero-padded if short)
        val senderBytes = packet.senderID.take(SENDER_ID_SIZE)
        buf.put(senderBytes)
        if (senderBytes.size < SENDER_ID_SIZE) {
            buf.put(ByteArray(SENDER_ID_SIZE - senderBytes.size))
        }

        // RecipientID (optional, 8 bytes)
        packet.recipientID?.let { recipientID ->
            val recipBytes = recipientID.take(RECIPIENT_ID_SIZE)
            buf.put(recipBytes)
            if (recipBytes.size < RECIPIENT_ID_SIZE) {
                buf.put(ByteArray(RECIPIENT_ID_SIZE - recipBytes.size))
            }
        }

        // Route (optional, v2+ only)
        if (hasRoute) {
            buf.put(sanitizedRoute.size.toByte())
            for (hop in sanitizedRoute) {
                buf.put(hop)
            }
        }

        // Original payload size (if compressed)
        if (isCompressed && originalPayloadSize != null) {
            if (version == 2) {
                buf.putInt(originalPayloadSize!!)
            } else {
                buf.putShort(originalPayloadSize!!.toShort())
            }
        }

        // Payload
        buf.put(payload)

        // Signature (optional, 64 bytes)
        packet.signature?.let {
            buf.put(it.take(SIGNATURE_SIZE))
        }

        val result = ByteArray(buf.position())
        buf.flip()
        buf.get(result)

        return if (padding) {
            val optimalSize = MessagePadding.optimalBlockSize(result.size)
            MessagePadding.pad(result, optimalSize)
        } else {
            result
        }
    }

    // ======================== DECODE ========================

    /**
     * Decode binary data to BitchatPacket.
     * Wire-compatible with Swift's BinaryProtocol.decode().
     */
    fun decode(data: ByteArray): BitchatPacket? {
        // Try decode as-is first (robust when padding wasn't applied)
        decodeCore(data)?.let { return it }
        // If that fails, try after removing padding
        val unpadded = MessagePadding.unpad(data)
        if (unpadded.contentEquals(data)) return null
        return decodeCore(unpadded)
    }

    private fun decodeCore(raw: ByteArray): BitchatPacket? {
        if (raw.size < V1_HEADER_SIZE + SENDER_ID_SIZE) return null

        var offset = 0

        fun require(n: Int): Boolean = offset + n <= raw.size

        fun read8(): Int? {
            if (!require(1)) return null
            return raw[offset++].toInt() and 0xFF
        }

        fun read16(): Int? {
            if (!require(2)) return null
            val v = ((raw[offset].toInt() and 0xFF) shl 8) or (raw[offset + 1].toInt() and 0xFF)
            offset += 2
            return v
        }

        fun read32(): Long? {
            if (!require(4)) return null
            val v = ((raw[offset].toLong() and 0xFF) shl 24) or
                ((raw[offset + 1].toLong() and 0xFF) shl 16) or
                ((raw[offset + 2].toLong() and 0xFF) shl 8) or
                (raw[offset + 3].toLong() and 0xFF)
            offset += 4
            return v
        }

        fun readData(n: Int): ByteArray? {
            if (!require(n)) return null
            val data = raw.copyOfRange(offset, offset + n)
            offset += n
            return data
        }

        // Version
        val version = read8() ?: return null
        if (version != 1 && version != 2) return null
        val hdrSize = headerSize(version) ?: return null
        if (raw.size < hdrSize + SENDER_ID_SIZE) return null

        // Type, TTL
        val type = read8() ?: return null
        val ttl = read8() ?: return null

        // Timestamp (8 bytes big-endian)
        var timestamp: ULong = 0u
        for (i in 0 until 8) {
            val byte = read8() ?: return null
            timestamp = (timestamp shl 8) or byte.toULong()
        }

        // Flags
        val flags = read8() ?: return null
        val hasRecipient = (flags and Flags.HAS_RECIPIENT) != 0
        val hasSignature = (flags and Flags.HAS_SIGNATURE) != 0
        val isCompressed = (flags and Flags.IS_COMPRESSED) != 0
        val hasRoute = (version >= 2) && (flags and Flags.HAS_ROUTE) != 0
        val isRSR = (flags and Flags.IS_RSR) != 0

        // PayloadLength
        val payloadLength: Int = if (version == 2) {
            (read32() ?: return null).toInt()
        } else {
            read16() ?: return null
        }
        if (payloadLength < 0 || payloadLength > MAX_FRAMED_FILE_BYTES) return null

        // SenderID
        val senderID = readData(SENDER_ID_SIZE) ?: return null

        // RecipientID (optional)
        var recipientID: ByteArray? = null
        if (hasRecipient) {
            recipientID = readData(RECIPIENT_ID_SIZE) ?: return null
        }

        // Route (optional, v2+)
        var route: List<ByteArray>? = null
        if (hasRoute) {
            val routeCount = read8() ?: return null
            if (routeCount > 0) {
                val hops = mutableListOf<ByteArray>()
                for (i in 0 until routeCount) {
                    val hop = readData(SENDER_ID_SIZE) ?: return null
                    hops.add(hop)
                }
                route = hops
            }
        }

        // Payload
        val lengthFieldBytes = lengthFieldSize(version)
        val payload: ByteArray
        if (isCompressed) {
            if (payloadLength < lengthFieldBytes) return null
            val originalSize: Int = if (version == 2) {
                (read32() ?: return null).toInt()
            } else {
                read16() ?: return null
            }
            if (originalSize < 0 || originalSize > MAX_FRAMED_FILE_BYTES) return null

            val compressedSize = payloadLength - lengthFieldBytes
            if (compressedSize <= 0) return null
            val compressed = readData(compressedSize) ?: return null

            // Check compression ratio for zip bomb protection
            val ratio = originalSize.toDouble() / compressedSize.toDouble()
            if (ratio > 50_000.0) {
                Timber.w("Suspicious compression ratio: %.0f:1", ratio)
                return null
            }

            val decompressed = CompressionUtil.decompress(compressed, originalSize) ?: return null
            if (decompressed.size != originalSize) return null
            payload = decompressed
        } else {
            payload = readData(payloadLength) ?: return null
        }

        // Signature (optional)
        var signature: ByteArray? = null
        if (hasSignature) {
            signature = readData(SIGNATURE_SIZE) ?: return null
        }

        if (offset > raw.size) return null

        return BitchatPacket(
            version = version.toUByte(),
            type = type.toUByte(),
            senderID = senderID,
            recipientID = recipientID,
            timestamp = timestamp,
            payload = payload,
            signature = signature,
            ttl = ttl.toUByte(),
            route = route,
            isRSR = isRSR
        )
    }
}

/** Helper extension: take first N bytes from ByteArray */
private fun ByteArray.take(n: Int): ByteArray {
    return if (size <= n) this else copyOfRange(0, n)
}
