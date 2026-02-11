// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import chat.bitchat.app.service.MeshForegroundService
import chat.bitchat.app.ui.screens.ChatScreen
import chat.bitchat.app.ui.theme.BitchatColors
import chat.bitchat.app.ui.theme.BitchatTheme
import chat.bitchat.app.ui.viewmodel.ChatViewModel
import timber.log.Timber

/**
 * Main activity — launches the mesh and displays the chat.
 * Port of Swift's ContentView entry point.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Timber.i("All permissions granted")
            startMeshServices()
        } else {
            Timber.w("Some permissions denied: ${permissions.filter { !it.value }.keys}")
            // Start mesh with available permissions
            startMeshServices()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BitchatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = BitchatColors.Background
                ) {
                    ChatScreen(viewModel = viewModel)
                }
            }
        }

        requestPermissions()
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()

        // BLE permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            ))
        }

        // Location (required for BLE scanning on older devices + Wi-Fi Aware)
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        // Wi-Fi Aware (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            startMeshServices()
        }
    }

    private fun startMeshServices() {
        // Start foreground service
        val serviceIntent = Intent(this, MeshForegroundService::class.java).apply {
            action = MeshForegroundService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Start transport services
        viewModel.startMesh()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            val serviceIntent = Intent(this, MeshForegroundService::class.java).apply {
                action = MeshForegroundService.ACTION_STOP
            }
            startService(serviceIntent)
        }
    }
}
