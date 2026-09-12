package com.bonevane.bridge

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bonevane.bridge.ui.BridgeScreen
import com.bonevane.bridge.ui.BridgeTheme

/**
 * The one screen of the app. The layout lives in [BridgeScreen] (Compose,
 * Material 3); this class keeps the Android-side plumbing: intents, the
 * clipboard, permissions and the service.
 */
class MainActivity : ComponentActivity() {

    /// Permission state is read once per screen; granting happens in a system
    /// screen, so it has to be re-read when we come back or the list lies.
    private var accessState by mutableStateOf<List<Access>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        accessState = Access.all(this)

        // Notifications for the foreground service, Bluetooth for the short-range
        // link to the Mac. Asked for together so there's one round of prompts.
        val wanted = listOf(
            Manifest.permission.POST_NOTIFICATIONS,
            "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.BLUETOOTH_CONNECT",
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)

        setContent {
            BridgeTheme {
                BridgeScreen(
                    onToggleTunnel = {
                        // Toggles the internet tunnel only; Bluetooth keeps running.
                        startForegroundService(
                            Intent(this, TunnelService::class.java).setAction(TunnelService.ACTION_TUNNEL)
                        )
                    },
                    onCopyTicket = ::copyTicket,
                    onShareTicket = ::shareTicket,
                    onGetReady = {
                        Thread {
                            runCatching { DaemonManager.start(this) }
                                .onFailure { TunnelState.log("Couldn't get ready: ${it.message}") }
                        }.start()
                    },
                    onPause = {
                        val policy = TunnelService.current?.policy
                        Thread {
                            if (policy != null) policy.pause(15)
                            else DaemonManager.stop(this, force = true)
                        }.start()
                    },
                    onLockDown = {
                        Thread { DaemonManager.stop(this, force = true) }.start()
                    },
                    onKeepReadyChange = { on ->
                        Prefs.setKeepReady(this, on)
                        if (on) TunnelService.current?.policy?.maybeStart("setting turned on")
                    },
                    onAutostartChange = { Prefs.setAutostart(this, it) },
                    onBatteryExemption = ::askBatteryExemption,
                    onNewIdentity = ::newIdentity,
                    onGrantAccess = ::grantAccess,
                    isIgnoringBatteryOptimisations = {
                        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
                    },
                    accessItems = accessState,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accessState = Access.all(this)      // a permission may have just been granted
        // Bluetooth permission may have just been granted, here or in system
        // settings. Starting again is harmless if the link is already up.
        TunnelService.current?.ble?.let { runCatching { it.start() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** The Mac's "Set up over USB" button opens the app with ACTION_START. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == TunnelService.ACTION_START || Prefs.wantRunning(this)) {
            TunnelService.start(this)
        }
    }

    /// Asks for one permission: a runtime prompt where that applies, otherwise
    /// the settings screen that grants it.
    private fun grantAccess(item: Access) {
        val missing = item.runtimePermissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 2)
            return
        }
        item.intent?.let { intent ->
            runCatching { startActivity(intent) }
                .onFailure { Toast.makeText(this, "Couldn't open that screen", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun currentTicket(): String? = TunnelState.ticket ?: Prefs.ticket(this)

    private fun copyTicket() {
        val ticket = currentTicket() ?: return
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Bridge ticket", ticket))
        Toast.makeText(this, "Ticket copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareTicket() {
        val ticket = currentTicket() ?: return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ticket)
        startActivity(Intent.createChooser(send, "Send ticket to your Mac"))
    }

    private fun askBatteryExemption() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }

    private fun newIdentity() {
        TunnelService.stop(this)
        Prefs.resetIdentity(this)
        TunnelState.ticket = null
        TunnelState.update("Stopped. New identity: set up over USB again.", ready = false)
    }
}
