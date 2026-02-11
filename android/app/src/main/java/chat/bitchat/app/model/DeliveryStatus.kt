// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.model

import java.util.Date

/**
 * Delivery status for messages.
 * Wire-compatible with Swift's DeliveryStatus enum.
 */
sealed class DeliveryStatus {
    data object Sending : DeliveryStatus()
    data object Sent : DeliveryStatus()           // Left our device
    data class Delivered(val to: String, val at: Date) : DeliveryStatus()
    data class Read(val by: String, val at: Date) : DeliveryStatus()
    data class Failed(val reason: String) : DeliveryStatus()
    data class PartiallyDelivered(val reached: Int, val total: Int) : DeliveryStatus()

    val displayText: String
        get() = when (this) {
            is Sending -> "Sending..."
            is Sent -> "Sent"
            is Delivered -> "Delivered to $to"
            is Read -> "Read by $by"
            is Failed -> "Failed: $reason"
            is PartiallyDelivered -> "Delivered to $reached/$total"
        }
}

/**
 * Lazy handshake state tracking.
 * Maps to Swift's LazyHandshakeState enum.
 */
sealed class LazyHandshakeState {
    data object None : LazyHandshakeState()               // No session, no handshake attempted
    data object HandshakeQueued : LazyHandshakeState()     // User action requires handshake
    data object Handshaking : LazyHandshakeState()         // Currently in handshake process
    data object Established : LazyHandshakeState()         // Session ready for use
    data class Failed(val error: Throwable) : LazyHandshakeState()
}

/**
 * Read receipt model.
 */
data class ReadReceipt(
    val messageId: String,
    val readBy: String,
    val readAt: Date
)
