// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.noise

/**
 * Noise Protocol error types.
 * Maps to Swift's NoiseError enum.
 */
sealed class NoiseError(message: String) : Exception(message) {
    class UninitializedCipher : NoiseError("Cipher state is not initialized")
    class NonceExceeded : NoiseError("Nonce has exceeded the maximum value")
    class InvalidCiphertext : NoiseError("Invalid ciphertext data")
    class ReplayDetected : NoiseError("Replay attack detected: duplicate nonce")
    class HandshakeComplete : NoiseError("Handshake is already complete")
    class MissingLocalStaticKey : NoiseError("Missing local static key")
    class MissingKeys : NoiseError("Missing required keys for DH operation")
    class InvalidMessage : NoiseError("Invalid handshake message")
    class AuthenticationFailure : NoiseError("Authentication failed during handshake")
    class InvalidPublicKey : NoiseError("Invalid public key data")
}

/**
 * Noise Session error types.
 * Maps to Swift's NoiseSessionError enum.
 */
sealed class NoiseSessionError(message: String) : Exception(message) {
    class AlreadyEstablished : NoiseSessionError("Session is already established")
    class SessionNotFound : NoiseSessionError("No session found for peer")
    class NotEstablished : NoiseSessionError("Session is not yet established")
    class EncryptionFailed : NoiseSessionError("Encryption failed")
    class DecryptionFailed : NoiseSessionError("Decryption failed")
}

/** Supported Noise handshake patterns */
enum class NoisePattern {
    XX,  // Most versatile, mutual authentication
    IK,  // Initiator knows responder's static key
    NK;  // Anonymous initiator

    val patternName: String
        get() = name

    /** Message patterns for the handshake */
    val messagePatterns: List<List<NoiseMessagePattern>>
        get() = when (this) {
            XX -> listOf(
                listOf(NoiseMessagePattern.E),
                listOf(NoiseMessagePattern.E, NoiseMessagePattern.EE, NoiseMessagePattern.S, NoiseMessagePattern.ES),
                listOf(NoiseMessagePattern.S, NoiseMessagePattern.SE)
            )
            IK -> listOf(
                listOf(NoiseMessagePattern.E, NoiseMessagePattern.ES, NoiseMessagePattern.S, NoiseMessagePattern.SS),
                listOf(NoiseMessagePattern.E, NoiseMessagePattern.EE, NoiseMessagePattern.SE)
            )
            NK -> listOf(
                listOf(NoiseMessagePattern.E, NoiseMessagePattern.ES),
                listOf(NoiseMessagePattern.E, NoiseMessagePattern.EE)
            )
        }
}

enum class NoiseRole {
    INITIATOR,
    RESPONDER
}

/** DH operation tokens in Noise handshake patterns */
enum class NoiseMessagePattern {
    E,   // Ephemeral key
    S,   // Static key
    EE,  // DH(ephemeral, ephemeral)
    ES,  // DH(ephemeral, static)
    SE,  // DH(static, ephemeral)
    SS   // DH(static, static)
}

/**
 * Noise session state tracking.
 * Maps to Swift's NoiseSessionState.
 */
enum class NoiseSessionState {
    IDLE,
    HANDSHAKING,
    ESTABLISHED,
    FAILED
}
