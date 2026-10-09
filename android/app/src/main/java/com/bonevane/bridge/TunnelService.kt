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
        /** "Stop mirroring", from the phone's screen or its notification. */
        const val ACTION_END_SESSIONS = "com.bonevane.bridge.END_SESSIONS"

        fun endMirroring(ctx: Context) {
            ctx.startService(Intent(ctx, TunnelService::class.java).setAction(ACTION_END_SESSIONS))
        }

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
        /**
         * After a session: the tunnel only ran for it (setup, or the Mac woke
         * it), so if the chosen mode is Nearby, switch it off again. The phone
         * decides this, not the Mac: the Mac can't always tell who turned it on.
         */
        /**
         * A computer says its session is over (STOP over the tunnel, or
         * "session over" over Bluetooth). With two computers, the other one
         * may still be mirroring: stopping the helper or the tunnel then cut
         * its session off mid-stream. So wait for the ending computer's own
         * streams to close (they do within a second or two of its message),
         * and only wind down if no session stream is left open at all.
         */
        fun endSession(ctx: Context, who: String) {
            Thread {
                // A beat first, so a reply already written to the tunnel leaves
                // before the tunnel can be switched off.
                Thread.sleep(1_000)
                var waited = 0
                while (TunnelState.openStreams.get() > 0 && waited < 4_000) {
                    Thread.sleep(250); waited += 250
                }
                if (TunnelState.openStreams.get() > 0) {
                    TunnelState.log("$who ended its session; another computer is still mirroring, so the helper and tunnel stay up")
                } else {
                    TunnelState.log("$who ended the session: ${DaemonManager.stop(ctx)}")
                    settleTunnelAfterSession(ctx)
                }
                current?.ble?.sendStatus()
            }.start()
        }

        fun settleTunnelAfterSession(ctx: Context) {
            if (!Prefs.tunnelEnabled(ctx) && TunnelState.tunnelOn) {
                TunnelState.log("Session over: back to Nearby")
                setTunnel(ctx, false, remember = false)
            }
        }

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
        // Access granted doesn't mean Android is running our listener: after
        // a reinstall MIUI leaves it switched on but unbound, and no
        // notifications arrive. Asking for a rebind is harmless if it's running.
        if (NotificationRelay.hasAccess(this)) runCatching {
            android.service.notification.NotificationListenerService.requestRebind(
                android.content.ComponentName(this, NotificationService::class.java))
        }
        if (intent?.action == ACTION_END_SESSIONS) {
            Thread { TunnelState.log("Stop mirroring: ${DaemonManager.endSessions()}") }.start()
            return START_STICKY
        }
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

    /** What the status notification last said, so it's only re-posted on a change. */
    private var shownStatus = ""
    private val statusListener: () -> Unit = { goForeground() }

    /**
     * The status notification says what's going on, in one line: who's
     * linked, or that the screen is being shared and with whom (with Stop).
     * Re-posted only when that text changes; the state listener fires for
     * every log line too.
     */
    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Status", NotificationManager.IMPORTANCE_LOW)
        )
        // The old "quiet" channel: Android wouldn't let it hide the icon anyway
        // (a running service's notification stays visible), so it's gone.
        runCatching { nm.deleteNotificationChannel(QUIET_CHANNEL_ID) }
        val mirroring = TunnelState.mirroring
        val mode = if (TunnelState.tunnelOn) "Anywhere" else "Nearby"
        val linked = TunnelState.linkedComputers
        val title = if (mirroring) "Your screen is being shared" else "Bridge · $mode"
        val text = when {
            mirroring -> "With ${TunnelState.sessionBy.ifEmpty { "your computer" }}"
            linked.isNotEmpty() -> "Linked to ${linked.joinToString(", ")}"
            else -> "No computer nearby"
        }
        val key = "$title|$text"
        if (key == shownStatus && active) return
        shownStatus = key
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        // Stop ends the mirroring while there is any; otherwise it stops Bridge.
        val stop = PendingIntent.getService(
            this, if (mirroring) 2 else 1,
            Intent(this, TunnelService::class.java).setAction(if (mirroring) ACTION_END_SESSIONS else ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as Icon?, if (mirroring) "Stop mirroring" else "Stop", stop).build())
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Opens Android's own settings for the status notification (Silent, Minimise…). */
    fun statusChannelSettings(): Intent =
        Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)

    private fun launch() {
        active = true
        TunnelState.addListener(statusListener)
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
        TunnelState.removeListener(statusListener)
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
