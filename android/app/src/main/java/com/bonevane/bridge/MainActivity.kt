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
import com.bonevane.bridge.ui.Mode

/**
 * The one screen of the app. The layout lives in [BridgeScreen] (Compose,
 * Material 3); this class keeps the Android-side plumbing: intents, the
 * clipboard, permissions and the service.
 */
class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_AUTHORIZE = "com.bonevane.bridge.AUTHORIZE"
        const val ACTION_NEARBY = "com.bonevane.bridge.NEARBY"
    }

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
                    onModeChange = ::setMode,
                    onCopyTicket = ::copyTicket,
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
                    onQuietStatusChange = { on ->
                        Prefs.setQuietStatus(this, on)
                        TunnelService.current?.refreshNotification()
                    },
                    onBatteryExemption = ::askBatteryExemption,
                    onNewIdentity = ::newIdentity,
                    onGrantAccess = ::grantAccess,
                    onOpenLink = { url ->
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    },
                    version = versionName(),
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
        // Diagnostic: which USB-debugging signal the app can see. Android 17
        // hides adb_enabled from apps, so the truth has to come from getprop,
        // and this line in the Activity log shows what each source says.
        Thread {
            val props = listOf("sys.usb.config", "persist.sys.usb.config", "sys.usb.state", "init.svc.adbd")
                .joinToString(" ") { n ->
                    val v = runCatching { ProcessBuilder("getprop", n).start().inputStream.bufferedReader().readText().trim() }.getOrDefault("ERR")
                    "$n=${v.ifEmpty { "(empty)" }}"
                }
            val setting = Settings.Global.getString(contentResolver, Settings.Global.ADB_ENABLED) ?: "null"
            TunnelState.log("USB probe: adb_enabled=$setting $props")
        }.start()
        accessState = Access.all(this)      // a permission may have just been granted
        // Bluetooth permission may have just been granted, here or in system
        // settings. Starting again is harmless if the link is already up.
        TunnelService.current?.ble?.let { runCatching { it.start() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * Off / Nearby / Anywhere. Off stops the service outright (no Bluetooth, no
     * tunnel). Nearby runs the service with the tunnel off; Anywhere adds the
     * tunnel. The tunnel preference is set *before* the service starts so it
     * doesn't briefly come up in the previous mode.
     */
    private fun setMode(mode: Mode) {
        when (mode) {
            Mode.OFF -> TunnelService.stop(this)
            Mode.NEARBY -> { Prefs.setTunnelEnabled(this, false); TunnelService.setTunnel(this, false) }
            Mode.ANYWHERE -> { Prefs.setTunnelEnabled(this, true); TunnelService.setTunnel(this, true) }
        }
    }

    /** The Mac's "Set up over USB" button opens the app with ACTION_START. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == TunnelService.ACTION_START || Prefs.wantRunning(this)) {
            TunnelService.start(this)
        }
        // Set Up Over USB needs a ticket, and only a running tunnel makes one,
        // so setup switches the tunnel on and switches it back to Nearby when
        // it's done (ACTION_NEARBY). The mode the user picked is untouched.
        if (intent?.action == TunnelService.ACTION_START) TunnelService.setTunnel(this, true, remember = false)
        if (intent?.action == ACTION_NEARBY && !Prefs.tunnelEnabled(this)) TunnelService.setTunnel(this, false, remember = false)
        if (intent?.action == ACTION_AUTHORIZE) authorizeKey()
    }

    /**
     * Gets Bridge's own ADB key trusted by adbd. The Mac, over the cable, has
     * put adbd in TCP mode for a moment; connecting to it from here with the
     * legacy (non-TLS) protocol makes adbd show "Allow USB debugging?" for
     * this key. Once the user ticks it, every later wireless bootstrap works.
     */
    private fun authorizeKey() {
        Thread {
            TunnelState.log("Asking adbd to trust Bridge's key: tap Allow on the phone")
            runCatching {
                AdbClient("127.0.0.1", 5555, AdbKey.load(this)).use { it.connect(waitForUserMs = 120_000) }
            }.onSuccess {
                Prefs.setAdbKeyAuthorized(this, true)
                TunnelState.log("adbd trusts Bridge's key now")
            }.onFailure {
                TunnelState.log("Key not accepted: ${it.message}")
            }
        }.start()
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

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    }.getOrDefault("")

    private fun currentTicket(): String? = TunnelState.ticket ?: Prefs.ticket(this)

    private fun copyTicket() {
        val ticket = currentTicket() ?: return
        // Ticket and pairing secret together: the Mac needs both, and the
        // copy-paste route is the only one that doesn't go over the cable.
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Bridge ticket", "$ticket ${Prefs.pairSecret(this)}"))
        Toast.makeText(this, "Ticket copied", Toast.LENGTH_SHORT).show()
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
