// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.service

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secure key storage using Android Keystore.
 * Port of Swift's KeychainManager.
 *
 * Stores identity keys (Noise static key, Ed25519 signing key) encrypted
 * with an Android Keystore master key. The master key never leaves the
 * hardware-backed keystore.
 *
 * Architecture:
 * - Master key: AES-256-GCM in Android Keystore (hardware-backed when available)
 * - Identity keys: Encrypted with master key, stored in SharedPreferences
 * - This avoids Keystore's limitation of not supporting raw X25519 keys natively
 */
class KeystoreManager(private val context: Context) {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "bitchat_master_key"
        private const val PREFS_NAME = "bitchat_secure_keys"
        private const val GCM_TAG_LENGTH = 128
        private const val IV_SIZE = 12

        // Key identifiers (match Swift's keychain keys)
        const val KEY_NOISE_STATIC = "noiseStaticKey"
        const val KEY_NOISE_STATIC_PUBLIC = "noiseStaticPublicKey"
        const val KEY_SIGNING_PRIVATE = "signingPrivateKey"
        const val KEY_SIGNING_PUBLIC = "signingPublicKey"
        const val KEY_DEVICE_ID = "deviceID"
    }

    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        ensureMasterKey()
    }

    // ============ Master Key Management ============

    private fun ensureMasterKey() {
        if (!keyStore.containsAlias(MASTER_KEY_ALIAS)) {
            generateMasterKey()
        }
    }

    private fun generateMasterKey() {
        val spec = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false) // Allow access without biometric
            .build()

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )
        keyGenerator.init(spec)
        keyGenerator.generateKey()
        Timber.i("Master key generated in Android Keystore")
    }

    private fun getMasterKey(): SecretKey {
        return keyStore.getKey(MASTER_KEY_ALIAS, null) as SecretKey
    }

    // ============ Identity Key Operations ============

    /**
     * Save an identity key (encrypted with master key).
     * Returns true on success.
     */
    fun saveIdentityKey(keyData: ByteArray, forKey: String): Boolean {
        return try {
            val fullKey = "identity_$forKey"
            val encrypted = encrypt(keyData)
            prefs.edit()
                .putString("${fullKey}_data", encrypted.ciphertext.toBase64())
                .putString("${fullKey}_iv", encrypted.iv.toBase64())
                .apply()
            Timber.d("Identity key saved: $forKey")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to save identity key: $forKey")
            false
        }
    }

    /**
     * Retrieve an identity key (decrypted with master key).
     * Returns null if key doesn't exist or decryption fails.
     */
    fun getIdentityKey(forKey: String): ByteArray? {
        return try {
            val fullKey = "identity_$forKey"
            val ciphertextB64 = prefs.getString("${fullKey}_data", null) ?: return null
            val ivB64 = prefs.getString("${fullKey}_iv", null) ?: return null

            val ciphertext = ciphertextB64.fromBase64()
            val iv = ivB64.fromBase64()

            decrypt(EncryptedData(ciphertext, iv))
        } catch (e: Exception) {
            Timber.e(e, "Failed to retrieve identity key: $forKey")
            null
        }
    }

    /**
     * Delete an identity key.
     * Returns true on success.
     */
    fun deleteIdentityKey(forKey: String): Boolean {
        val fullKey = "identity_$forKey"
        prefs.edit()
            .remove("${fullKey}_data")
            .remove("${fullKey}_iv")
            .apply()
        Timber.d("Identity key deleted: $forKey")
        return true
    }

    /** Check if a Noise static key exists */
    fun verifyIdentityKeyExists(): Boolean {
        return getIdentityKey(KEY_NOISE_STATIC) != null
    }

    /**
     * Delete ALL stored keys. Used for emergency wipe / panic mode.
     */
    fun deleteAllKeychainData(): Boolean {
        Timber.w("PANIC MODE — deleting all keystore data")
        try {
            // Clear all shared preferences
            prefs.edit().clear().apply()

            // Remove master key from keystore
            if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MASTER_KEY_ALIAS)
            }

            Timber.w("All keychain data deleted")
            return true
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete all keychain data")
            return false
        }
    }

    // ============ Generic Data Storage ============

    fun save(key: String, data: ByteArray, service: String) {
        try {
            val encrypted = encrypt(data)
            prefs.edit()
                .putString("${service}_${key}_data", encrypted.ciphertext.toBase64())
                .putString("${service}_${key}_iv", encrypted.iv.toBase64())
                .apply()
        } catch (e: Exception) {
            Timber.e(e, "Failed to save data: $service/$key")
        }
    }

    fun load(key: String, service: String): ByteArray? {
        return try {
            val ciphertextB64 = prefs.getString("${service}_${key}_data", null) ?: return null
            val ivB64 = prefs.getString("${service}_${key}_iv", null) ?: return null
            decrypt(EncryptedData(ciphertextB64.fromBase64(), ivB64.fromBase64()))
        } catch (e: Exception) {
            Timber.e(e, "Failed to load data: $service/$key")
            null
        }
    }

    fun delete(key: String, service: String) {
        prefs.edit()
            .remove("${service}_${key}_data")
            .remove("${service}_${key}_iv")
            .apply()
    }

    // ============ Encryption/Decryption ============

    private data class EncryptedData(val ciphertext: ByteArray, val iv: ByteArray)

    private fun encrypt(plaintext: ByteArray): EncryptedData {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getMasterKey())
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedData(ciphertext, cipher.iv)
    }

    private fun decrypt(data: EncryptedData): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, data.iv)
        cipher.init(Cipher.DECRYPT_MODE, getMasterKey(), spec)
        return cipher.doFinal(data.ciphertext)
    }

    // ============ Secure Clear ============

    fun secureClear(data: ByteArray) {
        data.fill(0)
    }

    // ============ Base64 Extensions ============

    private fun ByteArray.toBase64(): String =
        android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)

    private fun String.fromBase64(): ByteArray =
        android.util.Base64.decode(this, android.util.Base64.NO_WRAP)
}
