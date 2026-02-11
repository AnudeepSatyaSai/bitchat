// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app

import android.app.Application
import chat.bitchat.app.identity.IdentityManager
import chat.bitchat.app.service.KeystoreManager
import timber.log.Timber

/**
 * Application class — initializes core services.
 * Port of Swift's BitchatApp.
 */
class BitchatApp : Application() {

    lateinit var keystoreManager: KeystoreManager
        private set
    lateinit var identityManager: IdentityManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize Timber logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Initialize keystore
        keystoreManager = KeystoreManager(this)

        // Initialize identity
        identityManager = IdentityManager(keystoreManager)
        identityManager.initialize()

        Timber.i("BitChat initialized. Device ID: ${identityManager.devicePeerID?.id}")
    }

    companion object {
        const val BUNDLE_ID = "chat.bitchat.app"

        lateinit var instance: BitchatApp
            private set
    }
}
