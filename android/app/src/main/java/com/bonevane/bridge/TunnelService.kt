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

        private const val CHANNEL_ID = "tunnel"
        private const val NOTIFICATION_ID = 1
        private val TICKET_REGEX = Regex("endpoint[a-z0-9]+")

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, TunnelService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, TunnelService::class.java).setAction(ACTION_STOP))
        }
    }

    @Volatile private var active = false
    @Volatile private var process: Process? = null
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var proxy: ControlProxy? = null
    var policy: ReadyPolicy? = null
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
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Tunnel", NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopTunnel = PendingIntent.getService(
            this, 1, Intent(this, TunnelService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
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
        // Same idea as `termux-wake-lock`: keep the CPU awake while the screen is off.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Bridge::tunnel")
            .apply { setReferenceCounted(false); acquire() }
        // Keep the Wi-Fi radio out of power-saving. Without this, an idle phone's
        // radio sleeps between beacons and adds ~150 ms to every packet after any
        // gap, which shows up as constant lag and stutter while mirroring.
        val wifi = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29)
            android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else @Suppress("DEPRECATION") android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wifi.createWifiLock(mode, "Bridge::wifi").apply { setReferenceCounted(false); acquire() }
        // Tunnel streams land on the proxy, which sorts ADB traffic from commands.
        proxy = ControlProxy(this).also { p ->
            runCatching { p.start() }.onFailure { TunnelState.log("Proxy failed: ${it.message}") }
        }
        policy = ReadyPolicy(this).also { it.start() }
        current = this
        worker = Thread({ runLoop() }, "dumbpipe").also { it.start() }
    }

    private fun runLoop() {
        // Android installs files from jniLibs here, with permission to execute.
        val binary = File(applicationInfo.nativeLibraryDir, "libdumbpipe.so")

        while (active) {
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
                TunnelState.log("Error: ${e.message}")
            }
            process = null
            if (!active) break
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
        TunnelState.running = false
        process?.destroy()          // closes dumbpipe; the read loop then ends
        worker?.interrupt()
        worker = null
        proxy?.stop()
        proxy = null
        policy?.stop()
        policy = null
        current = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        TunnelState.update("Stopped", ready = false)
    }
}
