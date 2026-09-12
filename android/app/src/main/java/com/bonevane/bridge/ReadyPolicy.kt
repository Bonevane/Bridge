package com.bonevane.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The user's choice for what happens between sessions (Prefs.keepReady):
 *
 *  - off (default): USB debugging goes off at Disconnect; nothing to do here
 *    except honour a "pause" (which is just lock-down with a timer).
 *  - on: keep the phone ready. Whenever Wi-Fi is available and the daemon is
 *    not running (after a reboot, after a pause), start it. Once running it
 *    survives moving to cellular, so Connect works anywhere.
 *
 * "Pause for banking" (button, Quick Settings tile, Mac menu): USB debugging
 * off for N minutes. With keepReady on, it resumes by itself afterwards (needs
 * Wi-Fi at that moment); with keepReady off, it simply stays off.
 *
 * Lives inside the tunnel service, so it runs as long as the tunnel does.
 */
class ReadyPolicy(private val ctx: Context) {
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val starting = AtomicBoolean(false)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { maybeStart("Wi-Fi available") }
    }

    companion object {
        /** Lock down this long after the Mac goes quiet (it may have crashed). */
        const val IDLE_LOCKDOWN_MS = 10 * 60_000L
    }

    fun start() {
        scheduleWatchdog()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        cm.registerNetworkCallback(request, callback)
        scheduleResumeCheck()
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(callback) }
        main.removeCallbacksAndMessages(null)
    }

    val isPaused: Boolean get() = Prefs.pausedUntil(ctx) > System.currentTimeMillis()

    fun pause(minutes: Int): String {
        Prefs.setPausedUntil(ctx, System.currentTimeMillis() + minutes * 60_000L)
        DaemonManager.stop(ctx, force = true)
        val resume = if (Prefs.keepReady(ctx)) "back in $minutes min" else "stays off"
        TunnelState.log("Paused for $minutes min (USB debugging off, $resume)")
        scheduleResumeCheck()
        return "paused $minutes min, USB debugging off ($resume)"
    }

    fun resume() {
        Prefs.setPausedUntil(ctx, 0)
        main.removeCallbacksAndMessages(null)
        TunnelState.log("Pause ended")
        maybeStart("resume")
    }

    /** Starts the daemon in the background if the setting says so and it's possible. */
    fun maybeStart(reason: String) {
        if (!Prefs.keepReady(ctx) || isPaused || !AdbToggle.isGranted(ctx)) return
        if (DaemonManager.isDaemonAlive()) return
        if (!starting.compareAndSet(false, true)) return
        Thread {
            try {
                TunnelState.log("Getting ready ($reason)")
                DaemonManager.start(ctx)
            } catch (e: Exception) {
                TunnelState.log("Not ready yet: ${e.message}")
            } finally {
                starting.set(false)
            }
        }.start()
    }

    /**
     * If the Mac disappears mid-session (crash, closed laptop, lost network) nothing
     * would otherwise turn USB debugging off again. Check every minute and lock
     * down once it has been quiet for [IDLE_LOCKDOWN_MS], unless the user asked
     * to keep the phone ready.
     */
    private fun scheduleWatchdog() {
        main.postDelayed(object : Runnable {
            override fun run() {
                if (!Prefs.keepReady(ctx) && !isPaused && AdbToggle.isEnabled(ctx) &&
                    !TunnelState.macActive(IDLE_LOCKDOWN_MS) && DaemonManager.isDaemonAlive()) {
                    TunnelState.log("No Mac for 10 minutes: turning USB debugging off")
                    DaemonManager.stop(ctx, force = true)
                }
                main.postDelayed(this, 60_000)
            }
        }, 60_000)
    }

    private fun scheduleResumeCheck() {
        main.removeCallbacksAndMessages(null)
        val delay = Prefs.pausedUntil(ctx) - System.currentTimeMillis()
        if (delay > 0) main.postDelayed({ resume() }, delay + 500)
    }
}
