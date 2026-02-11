// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.model

import java.security.MessageDigest

/**
 * Represents a peer identity in the BitChat network.
 *
 * PeerID supports multiple formats:
 * - Short routing IDs: 16 hex characters (8 bytes) derived from SHA-256 of public key
 * - Full Noise key hex: 64 hex characters (32 bytes)
 * - Prefixed IDs: "mesh:", "name:", "noise:", "nostr_", "nostr:"
 *
 * Wire-compatible with the Swift implementation.
 */
data class PeerID(
    val prefix: Prefix,
    val bare: String
) : Comparable<PeerID> {

    enum class Prefix(val value: String) {
        EMPTY(""),
        MESH("mesh:"),
        NAME("name:"),
        NOISE("noise:"),
        GEO_DM("nostr_"),
        GEO_CHAT("nostr:");

        companion object {
            fun fromString(str: String): Prefix? {
                return entries.firstOrNull { it != EMPTY && str.startsWith(it.value) }
            }
        }
    }

    /** Full ID combining prefix and bare value */
    val id: String get() = prefix.value + bare

    val isEmpty: Boolean get() = id.isEmpty()
    val isGeoChat: Boolean get() = prefix == Prefix.GEO_CHAT
    val isGeoDM: Boolean get() = prefix == Prefix.GEO_DM

    val isHex: Boolean get() = bare.all { it.isHexDigit() }
    val isShort: Boolean get() = bare.length == HEX_ID_LENGTH && isHex
    val isNoiseKeyHex: Boolean get() = noiseKey != null

    /** Full Noise key (exact 64-hex) as ByteArray */
    val noiseKey: ByteArray?
        get() {
            if (bare.length != MAX_ID_LENGTH) return null
            return hexStringToByteArray(bare)
        }

    /** 8-byte routing data for wire protocol */
    val routingData: ByteArray?
        get() {
            hexStringToByteArray(id)?.let { if (it.size == 8) return it }
            hexStringToByteArray(bare)?.let { if (it.size == 8) return it }
            val short = toShort()
            return hexStringToByteArray(short.id)
        }

    val isValid: Boolean
        get() {
            if (prefix != Prefix.EMPTY) {
                return PeerID(Prefix.EMPTY, bare).isValid
            }
            if (isShort || isNoiseKeyHex) return true
            if (id.length == HEX_ID_LENGTH || id.length == MAX_ID_LENGTH) return false
            val validChars = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('-', '_')
            return id.isNotEmpty() && id.length < MAX_ID_LENGTH && id.all { it in validChars }
        }

    /** Derive the stable 16-hex short peer ID from a full Noise static public key */
    fun toShort(): PeerID {
        noiseKey?.let { key ->
            return fromPublicKey(key)
        }
        return this
    }

    fun toPercentEncoded(): String {
        return java.net.URLEncoder.encode(id, "UTF-8")
    }

    override fun compareTo(other: PeerID): Int = id.compareTo(other.id)
    override fun toString(): String = id

    companion object {
        private const val MAX_ID_LENGTH = 64
        const val HEX_ID_LENGTH = 16 // 8 bytes = 16 hex chars
        private const val NOSTR_CONV_KEY_PREFIX_LENGTH = 16
        private const val NOSTR_SHORT_KEY_DISPLAY_LENGTH = 8

        /** Create PeerID from a string, auto-detecting prefix */
        fun fromString(str: String): PeerID {
            val prefix = Prefix.fromString(str)
            return if (prefix != null) {
                PeerID(prefix, str.removePrefix(prefix.value).lowercase())
            } else {
                PeerID(Prefix.EMPTY, str.lowercase())
            }
        }

        /** Create PeerID from raw byte data (UTF-8 string) */
        fun fromData(data: ByteArray): PeerID? {
            val str = data.toString(Charsets.UTF_8)
            return if (str.isNotEmpty()) fromString(str) else null
        }

        /** Create PeerID from hex-encoded data */
        fun fromHexData(data: ByteArray): PeerID {
            return fromString(data.toHexString())
        }

        /** Create PeerID from 8-byte routing data */
        fun fromRoutingData(data: ByteArray): PeerID? {
            if (data.size != 8) return null
            return fromHexData(data)
        }

        /** Derive stable 16-hex peer ID from Noise static public key */
        fun fromPublicKey(publicKey: ByteArray): PeerID {
            val fingerprint = sha256Fingerprint(publicKey)
            return fromString(fingerprint.take(16))
        }

        /** Create GeoDM PeerID: "nostr_" + first 16 chars of pubkey */
        fun nostrDM(pubKey: String): PeerID {
            return PeerID(Prefix.GEO_DM, pubKey.take(NOSTR_CONV_KEY_PREFIX_LENGTH).lowercase())
        }

        /** Create GeoChat PeerID: "nostr:" + first 8 chars of pubkey */
        fun nostrChat(pubKey: String): PeerID {
            return PeerID(Prefix.GEO_CHAT, pubKey.take(NOSTR_SHORT_KEY_DISPLAY_LENGTH).lowercase())
        }

        /** SHA-256 fingerprint as hex string */
        fun sha256Fingerprint(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(data).toHexString()
        }

        private fun Char.isHexDigit(): Boolean =
            this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

        private fun hexStringToByteArray(hex: String): ByteArray? {
            if (hex.length % 2 != 0) return null
            if (!hex.all { it.isHexDigit() }) return null
            return ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
    }
}

/** Extension to convert ByteArray to lowercase hex string */
fun ByteArray.toHexString(): String =
    joinToString("") { "%02x".format(it) }

/** Extension to convert hex string to ByteArray */
fun String.hexToByteArray(): ByteArray? {
    if (length % 2 != 0) return null
    if (!all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return ByteArray(length / 2) { i ->
        substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
