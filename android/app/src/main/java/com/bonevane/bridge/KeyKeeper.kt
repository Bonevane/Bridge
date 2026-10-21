package com.bonevane.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps adbd trusting Bridge's own key.
 *
 * Android forgets an "Always allow" key that hasn't connected for a while
 * (Developer options → "Disable adb authorization timeout"; 7 days by
 * default). Bridge only uses its key when it has to start the helper, and
 * with "keep ready" the helper can run for weeks, so the key went stale
 * unnoticed and the next helper start failed with CERTIFICATE_UNKNOWN.
 *
 * The timer restarts every time the key connects, so this simply connects
 * once in a while: the same one-second wireless-debugging hop the helper
 * start uses, run when the key is past half its lifetime. Only on a Wi-Fi
 * network where that hop has worked before (Android asks "allow wireless
 * debugging on this network?" the first time, and that prompt must never pop
 * up out of nowhere), never during a session, never while paused for banking.
 *
 * Every computer's key except Bridge's still expires as Android intends.
 * The opt-in [setForever] is the blunt alternative: it switches the timeout
 * off for every key, which is why it's off by default.
 */
object KeyKeeper {
    /** Android's own default when the setting has never been touched. */
    private const val DEFAULT_ALLOWED_MS = 7L * 24 * 60 * 60 * 1000
    private const val RETRY_AFTER_FAILURE_MS = 6L * 60 * 60 * 1000
    private val renewing = AtomicBoolean(false)

    /** How long adbd keeps an unused key, or 0 for never. */
    fun allowedMs(ctx: Context): Long =
        Settings.Global.getLong(ctx.contentResolver, "adb_allowed_connection_time", DEFAULT_ALLOWED_MS)

    /** Whether there's a key to look after at all: set up, and trusted at some point. */
    fun applies(ctx: Context): Boolean = Prefs.adbKeyAuthorized(ctx) && Prefs.ticket(ctx) != null

    /** When the key will lapse if nothing uses it (epoch ms); null = never, or not known. */
    fun expiresAt(ctx: Context): Long? {
        val allowed = allowedMs(ctx)
        val used = Prefs.keyUsedAt(ctx)
        if (allowed <= 0 || used <= 0) return null
        return used + allowed
    }

    /** adbd just accepted the key over [net] (null = not over Wi-Fi). */
    fun markUsed(ctx: Context, net: String?) {
        val wasLost = Prefs.keyLost(ctx)
        Prefs.setKeyUsed(ctx, System.currentTimeMillis())
        Prefs.setKeyRenewFailures(ctx, 0)
        net?.let { Prefs.addGoodNetwork(ctx, it) }
        if (wasLost) TunnelState.log("Bridge's debugging permission works again")
        Health.changed(ctx)
    }

    /** A connection to adbd failed; if adbd refused the key itself, remember that. */
    fun markFailure(ctx: Context, error: Throwable) {
        val text = generateSequence(error) { it.cause }.joinToString(" ") { it.message.orEmpty() }
        if ("CERTIFICATE_UNKNOWN" in text || "unauthorized" in text.lowercase()) {
            if (!Prefs.keyLost(ctx)) TunnelState.log("adbd no longer trusts Bridge's key: Set up over USB again")
            Prefs.setKeyLost(ctx)
            Health.changed(ctx)
        }
    }

    /**
     * A rough identity for the Wi-Fi network the phone is on, readable without
     * location permission (which the SSID needs): its gateway, DHCP server and
     * DNS servers. Two networks can collide; the worst that does is one
     * "allow on this network?" prompt.
     */
    fun currentWifi(ctx: Context): String? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val lp = cm.getLinkProperties(net) ?: return null
        val gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress.orEmpty()
        val dhcp = lp.dhcpServerAddress?.hostAddress.orEmpty()
        val dns = lp.dnsServers.mapNotNull { it.hostAddress }.sorted().joinToString(",")
        return "$gateway|$dhcp|$dns".takeIf { it != "||" }
    }

    /**
     * Called every minute by [ReadyPolicy]. Renews the key in the background
     * when it's due and it's safe to; otherwise does nothing.
     */
    fun maybeRenew(ctx: Context) {
        if (!applies(ctx) || Prefs.keyLost(ctx) || !AdbToggle.isGranted(ctx)) return
        val allowed = allowedMs(ctx)
        if (allowed <= 0) return                                   // never expires
        val used = Prefs.keyUsedAt(ctx)
        val now = System.currentTimeMillis()
        // Not known (an install from before this existed): renew at the first chance,
        // which also tells us whether the key still works.
        if (used > 0 && now - used < allowed / 2) return
        if (now - Prefs.keyRenewTriedAt(ctx) < RETRY_AFTER_FAILURE_MS && Prefs.keyRenewFailures(ctx) > 0) return
        if (TunnelService.current?.policy?.isPaused == true) return
        if (TunnelState.openStreams.get() > 0 || TunnelState.mirroring) return
        val net = currentWifi(ctx) ?: return
        if (net !in Prefs.goodNetworks(ctx)) return
        renew(ctx, reason = "keeping it from expiring")
    }

    /** Uses the key once, now. From [maybeRenew], or the phone's "Renew now". */
    fun renew(ctx: Context, reason: String): Boolean {
        if (!renewing.compareAndSet(false, true)) return false
        Thread {
            Prefs.setKeyRenewTriedAt(ctx, System.currentTimeMillis())
            // Wireless debugging only exists while USB debugging is on. With
            // "keep ready" off it's normally off between sessions: switch it on
            // for the hop and back off afterwards, the way a session start does.
            val wasOn = AdbToggle.isEnabled(ctx)
            try {
                TunnelState.log("Renewing Bridge's debugging permission ($reason)")
                if (!wasOn) { AdbToggle.set(ctx, true); Thread.sleep(2_000) }
                WifiBootstrap.withAdb(ctx) { it.shell("true") }
                TunnelState.log("Debugging permission renewed")
            } catch (e: Exception) {
                val failures = Prefs.keyRenewFailures(ctx) + 1
                Prefs.setKeyRenewFailures(ctx, failures)
                TunnelState.log("Couldn't renew the debugging permission: ${e.message}")
                // Twice in a row on the same network without adbd answering:
                // its approval for this network was probably withdrawn.
                if (failures >= 2 && !Prefs.keyLost(ctx)) currentWifi(ctx)?.let { Prefs.removeGoodNetwork(ctx, it) }
            } finally {
                if (!wasOn && !DaemonManager.isDaemonAlive() && !Prefs.keepReady(ctx)) AdbToggle.set(ctx, false)
                renewing.set(false)
                Health.changed(ctx)
            }
        }.start()
        return true
    }

    // MARK: the opt-in

    fun isForever(ctx: Context): Boolean = allowedMs(ctx) <= 0

    /**
     * Switches Android's ADB authorization timeout off (or back to what it
     * was). Applies to *every* computer's key, not just Bridge's.
     */
    fun setForever(ctx: Context, on: Boolean): String? = runCatching {
        if (!AdbToggle.isGranted(ctx)) return "Set up over USB first: Bridge needs that permission to change this."
        val cr = ctx.contentResolver
        if (on) {
            val current = allowedMs(ctx)
            Settings.Global.putLong(cr, "adb_allowed_connection_time", 0)
            Prefs.setKeyForever(ctx, true, if (current > 0) current else DEFAULT_ALLOWED_MS)
            TunnelState.log("Debugging permissions set to never expire (every computer's)")
        } else {
            val restore = Prefs.savedConnectionTime(ctx).takeIf { it > 0 } ?: DEFAULT_ALLOWED_MS
            Settings.Global.putLong(cr, "adb_allowed_connection_time", restore)
            Prefs.setKeyForever(ctx, false, 0)
            TunnelState.log("Debugging permissions expire again after ${restore / 86_400_000} days unused")
        }
        Health.changed(ctx)
        null
    }.getOrElse { "Android refused the change: ${it.message}" }
}
