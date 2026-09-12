package com.bonevane.bridge

import android.content.Context
import java.security.SecureRandom

/** Small wrapper around SharedPreferences (private to this app). */
object Prefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    /**
     * The phone's permanent iroh identity: 32 random bytes as hex.
     * Passed to dumbpipe as IROH_SECRET, so the ticket survives restarts.
     */
    fun secret(ctx: Context): String {
        val p = prefs(ctx)
        p.getString("secret", null)?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hex = bytes.joinToString("") { "%02x".format(it) }
        p.edit().putString("secret", hex).apply()
        return hex
    }

    fun resetIdentity(ctx: Context) {
        prefs(ctx).edit().remove("secret").remove("ticket").apply()
    }

    fun ticket(ctx: Context): String? = prefs(ctx).getString("ticket", null)
    fun setTicket(ctx: Context, ticket: String) = prefs(ctx).edit().putString("ticket", ticket).apply()

    /** Start the tunnel automatically after reboot (if it was running before). */
    fun autostart(ctx: Context): Boolean = prefs(ctx).getBoolean("autostart", true)
    fun setAutostart(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("autostart", on).apply()

    /**
     * After a session ends: false = turn USB debugging off (default, safest);
     * true = keep the daemon so Connect works on cellular; pause manually for banking.
     */
    fun keepReady(ctx: Context): Boolean = prefs(ctx).getBoolean("keepReady", false)

    /** When this was last changed, so the two devices can tell whose copy is newer. */
    fun keepReadyAt(ctx: Context): Long = prefs(ctx).getLong("keepReadyAt", 0)

    fun setKeepReady(ctx: Context, on: Boolean, at: Long = System.currentTimeMillis()) =
        prefs(ctx).edit().putBoolean("keepReady", on).putLong("keepReadyAt", at).apply()

    /** "Pause for banking": USB debugging stays off until this time (epoch ms). */
    fun pausedUntil(ctx: Context): Long = prefs(ctx).getLong("pausedUntil", 0)
    fun setPausedUntil(ctx: Context, t: Long) = prefs(ctx).edit().putLong("pausedUntil", t).apply()

    /**
     * Whether the internet tunnel should run. Off means Bluetooth only: the Mac
     * can't connect from afar, but notifications and clipboard still work nearby
     * and the phone stops paying for relay keepalives.
     */
    fun tunnelEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("tunnelEnabled", true)
    fun setTunnelEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("tunnelEnabled", on).apply()

    /** Whether the user last left the tunnel on. */
    fun wantRunning(ctx: Context): Boolean = prefs(ctx).getBoolean("wantRunning", false)
    fun setWantRunning(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("wantRunning", on).apply()
}
