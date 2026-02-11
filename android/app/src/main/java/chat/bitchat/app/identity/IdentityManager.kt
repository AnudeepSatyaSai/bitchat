// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.identity

import chat.bitchat.app.model.PeerID
import chat.bitchat.app.model.toHexString
import chat.bitchat.app.noise.NoiseHandshakeState
import chat.bitchat.app.service.KeystoreManager
import timber.log.Timber

/**
 * Manages the device's cryptographic identity.
 * Port of Swift's SecureIdentityStateManager + IdentityModels.
 *
 * Generates and stores:
 * - Noise static keypair (Curve25519) for encrypted communication
 * - Device ID = SHA256(publicKey)[0:8] (8 bytes = 16 hex chars)
 *
 * All keys are stored encrypted via KeystoreManager (Android Keystore-backed).
 */
class IdentityManager(private val keystoreManager: KeystoreManager) {

    /** Our Noise public key, loaded lazily */
    var noiseStaticPublicKey: ByteArray? = null
        private set

    /** Our Noise private key, loaded lazily */
    var noiseStaticPrivateKey: ByteArray? = null
        private set

    /** Our derived device PeerID */
    var devicePeerID: PeerID? = null
        private set

    /** Our nickname */
    var nickname: String = "anon"

    /** Whether identity has been loaded or generated */
    val isInitialized: Boolean get() = noiseStaticPublicKey != null

    /**
     * Initialize identity: load existing keys or generate new ones.
     * Must be called before any crypto operations.
     */
    fun initialize() {
        // Try loading existing keys
        val existingPrivate = keystoreManager.getIdentityKey(KeystoreManager.KEY_NOISE_STATIC)
        val existingPublic = keystoreManager.getIdentityKey(KeystoreManager.KEY_NOISE_STATIC_PUBLIC)

        if (existingPrivate != null && existingPublic != null &&
            existingPrivate.size == 32 && existingPublic.size == 32) {
            noiseStaticPrivateKey = existingPrivate
            noiseStaticPublicKey = existingPublic
            devicePeerID = PeerID.fromPublicKey(existingPublic)
            Timber.i("Identity loaded: ${devicePeerID?.id}")
        } else {
            generateNewIdentity()
        }

        // Load nickname
        val savedNickname = keystoreManager.load("nickname", "identity")
        if (savedNickname != null) {
            nickname = String(savedNickname, Charsets.UTF_8)
        }
    }

    /**
     * Generate a fresh cryptographic identity.
     * Replaces any existing identity.
     */
    fun generateNewIdentity() {
        val (privateKey, publicKey) = NoiseHandshakeState.generateX25519KeyPair()

        // Store keys
        keystoreManager.saveIdentityKey(privateKey, KeystoreManager.KEY_NOISE_STATIC)
        keystoreManager.saveIdentityKey(publicKey, KeystoreManager.KEY_NOISE_STATIC_PUBLIC)

        noiseStaticPrivateKey = privateKey
        noiseStaticPublicKey = publicKey
        devicePeerID = PeerID.fromPublicKey(publicKey)

        Timber.i("New identity generated: ${devicePeerID?.id}")
        Timber.i("Public key fingerprint: ${getFullFingerprint()}")
    }

    /** Save nickname to persistent storage */
    fun saveNickname(name: String) {
        nickname = name
        keystoreManager.save("nickname", name.toByteArray(Charsets.UTF_8), "identity")
    }

    /**
     * Get the full SHA-256 fingerprint of our public key.
     * For device verification display.
     */
    fun getFullFingerprint(): String {
        val pubKey = noiseStaticPublicKey ?: return ""
        return PeerID.sha256Fingerprint(pubKey)
    }

    /**
     * Get formatted fingerprint for display: "XXXX XXXX XXXX XXXX"
     */
    fun getFormattedFingerprint(): String {
        val full = getFullFingerprint()
        if (full.length < 16) return full
        return full.take(16).chunked(4).joinToString(" ").uppercase()
    }

    /**
     * Emergency wipe — destroy all identity data.
     * Used for panic mode (triple-tap gesture).
     */
    fun emergencyWipe() {
        Timber.w("EMERGENCY WIPE — destroying identity")
        keystoreManager.deleteAllKeychainData()
        noiseStaticPrivateKey?.fill(0)
        noiseStaticPublicKey?.fill(0)
        noiseStaticPrivateKey = null
        noiseStaticPublicKey = null
        devicePeerID = null
        nickname = "anon"
    }
}
