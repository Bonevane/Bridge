package com.bonevane.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.File

/**
 * Foreground service that runs the bundled dumbpipe binary:
 *
 *   dumbpipe listen-tcp --host 127.0.0.1:5580
 *
 * Incoming iroh streams are forwarded to the ControlProxy. If dumbpipe dies,
 * it is restarted after 5 seconds.
 */
class TunnelService : Service() {

    companion object {
        /** The running service, so the proxy, screen and tile can reach the policy. */
        @Volatile var current: TunnelService? = null

        const val ACTION_START = "com.bonevane.bridge.START"
        const val ACTION_STOP = "com.bonevane.bridge.STOP"
        /** Turns the internet tunnel off but leaves Bluetooth running. */
        const val ACTION_TUNNEL = "com.bonevane.bridge.TUNNEL"

        private const val CHANNEL_ID = "tunnel"
        /** Same notification, but on a channel Android shows without a status-bar icon. */
        private const val QUIET_CHANNEL_ID = "tunnel_quiet"
        private const val NOTIFICATION_ID = 1
        private val TICKET_REGEX = Regex("endpoint[a-z0-9]+")

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, TunnelService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, TunnelService::class.java).setAction(ACTION_STOP))
        }

        /** Turns the internet tunnel on or off, leaving Bluetooth alone. */
        /**
         * [remember] = false is for the Mac and for setup: the tunnel comes up
         * for a session (or to mint the ticket) without changing the mode the
         * user chose, so the phone lands back in Nearby afterwards.
         */
        fun setTunnel(ctx: Context, on: Boolean, remember: Boolean = true) {
            ctx.startForegroundService(
                Intent(ctx, TunnelService::class.java)
                    .setAction(ACTION_TUNNEL)
                    .putExtra(EXTRA_ON, on)
                    .putExtra(EXTRA_REMEMBER, remember)
            )
        }

        const val EXTRA_ON = "on"
        const val EXTRA_REMEMBER = "remember"
    }

    @Volatile private var active = false
    @Volatile private var tunnelActive = false
    @Volatile private var process: Process? = null
    /** Every dumbpipe ever started here, so a stray from an earlier run can't
     *  keep the tunnel alive after the UI says it's off. */
    private val startedProcesses = java.util.Collections.synchronizedList(mutableListOf<Process>())
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var proxy: ControlProxy? = null
    var policy: ReadyPolicy? = null
        private set
    var ble: BleLink? = null
        private set
    var clipboard: ClipboardWatcher? = null
        private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setWantRunning(this, false)
            shutdown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // Must be called within a few seconds of startForegroundService().
        goForeground()
        Prefs.setWantRunning(this, true)
        if (!active) launch()
        if (intent?.action == ACTION_TUNNEL) {
            // No extra means "toggle" (the phone's own button); an extra means the
            // Mac asked for a particular state.
            val on = if (intent.hasExtra(EXTRA_ON)) intent.getBooleanExtra(EXTRA_ON, true)
                     else !TunnelState.tunnelOn
            if (intent.getBooleanExtra(EXTRA_REMEMBER, true)) Prefs.setTunnelEnabled(this, on)
            if (on) startTunnel() else stopTunnel()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    /** Re-posts the status notification, e.g. after the quiet setting changed. */
    fun refreshNotification() = goForeground()

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        // A channel's importance is fixed once created, so quiet is a second
        // channel rather than a change to the first.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Status", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(QUIET_CHANNEL_ID, "Status (quiet)", NotificationManager.IMPORTANCE_MIN)
        )
        val channel = if (Prefs.quietStatus(this)) QUIET_CHANNEL_ID else CHANNEL_ID
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopTunnel = PendingIntent.getService(
            this, 1, Intent(this, TunnelService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle("Bridge is on")
            .setContentText("Your Mac can connect to this phone")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as Icon?, "Stop", stopTunnel).build())
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun launch() {
        active = true
        TunnelState.running = true
        // Tunnel streams land on the proxy, which sorts ADB traffic from commands.
        proxy = ControlProxy(this).also { p ->
            runCatching { p.start() }.onFailure { TunnelState.log("Proxy failed: ${it.message}") }
        }
        policy = ReadyPolicy(this).also { it.start() }
        ble = BleLink(this).also { runCatching { it.start() }.onFailure { e -> TunnelState.log("Bluetooth: ${e.message}") } }
        // Copying on the phone reaches the Mac through here (see ClipboardWatcher).
        clipboard = ClipboardWatcher { ble }.also { it.start() }
        current = this
        // Bluetooth and the tunnel are independent: with the tunnel off, the
        // phone still mirrors notifications and the clipboard to a nearby Mac,
        // and stops paying for relay keepalives.
        if (Prefs.tunnelEnabled(this)) startTunnel() else {
            TunnelState.update("Bluetooth only. The tunnel is off.", ready = false)
        }
    }

    @Synchronized private fun startTunnel() {
        if (tunnelActive) {
            TunnelState.log("Tunnel already running")
            return
        }
        killDumbpipe()          // never run two, and clear anything left over
        tunnelActive = true
        TunnelState.tunnelOn = true
        // Same idea as `termux-wake-lock`: keep the CPU awake while the screen is
        // off, so the tunnel stays answerable. Only needed while it runs.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Bridge::tunnel")
            .apply { setReferenceCounted(false); acquire() }
        worker = Thread({ runLoop() }, "dumbpipe").also { it.start() }
        ble?.sendStatus()
    }

    @Synchronized private fun stopTunnel() {
        tunnelActive = false
        worker?.interrupt()
        worker = null
        killDumbpipe()
        TunnelState.tunnelOn = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        holdWifiAwake(false)
        TunnelState.ticket = null
        TunnelState.update("Bluetooth only. The tunnel is off.", ready = false)
        ble?.sendStatus()
    }

    /**
     * Holds the Wi-Fi radio out of power-saving *only while a session is running*.
     * It's what stops an idle radio adding ~150 ms to the first packet after a
     * gap, but it's a high-power mode, so holding it all day would be wasteful.
     */
    @Synchronized fun holdWifiAwake(hold: Boolean) {
        if (hold && wifiLock == null) {
            val wifi = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= 29)
                android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else @Suppress("DEPRECATION") android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wifi.createWifiLock(mode, "Bridge::wifi")
                .apply { setReferenceCounted(false); acquire() }
        } else if (!hold) {
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        }
    }

    /**
     * Ends every dumbpipe this service started and waits for them, so the state
     * the screen shows is the state the phone is actually in. `destroy()` alone
     * left one running once, which made "tunnel off" a lie.
     */
    private fun killDumbpipe() {
        val all = synchronized(startedProcesses) { startedProcesses.toList() }
        for (p in all) {
            runCatching {
                p.destroy()
                if (!p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly()
                    p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            }
        }
        synchronized(startedProcesses) {
            startedProcesses.removeAll { !it.isAlive }
            if (startedProcesses.isNotEmpty()) {
                TunnelState.log("Warning: ${startedProcesses.size} dumbpipe still alive")
            }
        }
        process = null
    }

    private fun runLoop() {
        // Android installs files from jniLibs here, with permission to execute.
        val binary = File(applicationInfo.nativeLibraryDir, "libdumbpipe.so")

        while (tunnelActive) {
            if (!binary.exists()) {
                TunnelState.update("dumbpipe is missing. Run scripts/fetch-dumbpipe.sh and rebuild.", ready = false)
                return
            }
            TunnelState.update("Starting...", ready = false)
            try {
                val builder = ProcessBuilder(
                    binary.absolutePath, "listen-tcp", "--host", "127.0.0.1:${ControlProxy.PORT}"
                ).redirectErrorStream(true)
                builder.environment().apply {
                    put("IROH_SECRET", Prefs.secret(this@TunnelService))
                    put("HOME", filesDir.absolutePath)
                    put("TMPDIR", cacheDir.absolutePath)
                }
                val p = builder.start()
                process = p
                startedProcesses.add(p)

                // Read dumbpipe's output line by line until it exits.
                p.inputStream.bufferedReader().useLines { output ->
                    for (line in output) {
                        TunnelState.log(line)
                        val match = TICKET_REGEX.find(line)
                        if (match != null) {
                            TunnelState.ticket = match.value
                            Prefs.setTicket(this, match.value)
                            TunnelState.update("Ready. Waiting for your Mac.", ready = true)
                        }
                        if (line.contains("Failed to connect to the home relay")) {
                            TunnelState.log("Warning: no relay. Only same-network connections may work.")
                        }
                    }
                }
                TunnelState.log("dumbpipe exited with code ${p.waitFor()}")
            } catch (e: Exception) {
                if (tunnelActive) TunnelState.log("Error: ${e.message}")
            }
            process = null
            if (!tunnelActive) break
            TunnelState.update("Tunnel stopped. Restarting in 5 seconds...", ready = false)
            try {
                Thread.sleep(5000)
            } catch (e: InterruptedException) {
                break
            }
        }
        TunnelState.update("Stopped", ready = false)
    }

    private fun shutdown() {
        active = false
        tunnelActive = false
        TunnelState.running = false
        TunnelState.tunnelOn = false
        worker?.interrupt()
        worker = null
        killDumbpipe()
        proxy?.stop()
        proxy = null
        policy?.stop()
        policy = null
        ble?.stop()
        ble = null
        clipboard?.stop()
        clipboard = null
        current = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        TunnelState.update("Stopped", ready = false)
    }
}
