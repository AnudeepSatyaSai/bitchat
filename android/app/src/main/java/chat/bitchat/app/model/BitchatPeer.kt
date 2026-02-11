// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.model

import java.util.Date

/**
 * Represents a peer in the BitChat network with all associated metadata.
 * Port of BitchatPeer.swift.
 */
data class BitchatPeer(
    val peerID: PeerID,
    val noisePublicKey: ByteArray,
    val nickname: String,
    val lastSeen: Date = Date(),
    val isConnected: Boolean = false,
    val isReachable: Boolean = false,
    var favoriteStatus: FavoriteRelationship? = null,
    var nostrPublicKey: String? = null
) {

    enum class ConnectionState {
        DIRECT_CONNECTED,   // BLE or Wi-Fi Aware direct connection
        MESH_REACHABLE,     // Seen via mesh recently, not directly connected
        NOSTR_AVAILABLE,    // Mutual favorite, reachable via Nostr
        OFFLINE             // Not connected via any transport
    }

    val connectionState: ConnectionState
        get() = when {
            isConnected -> ConnectionState.DIRECT_CONNECTED
            isReachable -> ConnectionState.MESH_REACHABLE
            favoriteStatus?.isMutual == true -> ConnectionState.NOSTR_AVAILABLE
            else -> ConnectionState.OFFLINE
        }

    val isFavorite: Boolean get() = favoriteStatus?.isFavorite ?: false
    val isMutualFavorite: Boolean get() = favoriteStatus?.isMutual ?: false
    val theyFavoritedUs: Boolean get() = favoriteStatus?.theyFavoritedUs ?: false

    val displayName: String
        get() = if (nickname.isEmpty()) peerID.id.take(8) else nickname

    val statusIcon: String
        get() = when (connectionState) {
            ConnectionState.DIRECT_CONNECTED -> "📻"
            ConnectionState.MESH_REACHABLE -> "📡"
            ConnectionState.NOSTR_AVAILABLE -> "🌐"
            ConnectionState.OFFLINE -> if (theyFavoritedUs && !isFavorite) "🌙" else ""
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BitchatPeer) return false
        return peerID == other.peerID
    }

    override fun hashCode(): Int = peerID.hashCode()
}

/**
 * Favorite relationship state between peers.
 */
data class FavoriteRelationship(
    val isFavorite: Boolean,
    val isMutual: Boolean,
    val theyFavoritedUs: Boolean
)
