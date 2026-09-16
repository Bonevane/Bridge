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

    /** Post the status notification on a minimum-importance channel: no icon in the status bar. */
    fun quietStatus(ctx: Context): Boolean = prefs(ctx).getBoolean("quietStatus", false)
    fun setQuietStatus(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("quietStatus", on).apply()

    /** Whether the user has ever gone through setup (a ticket exists and the Mac granted the permission). */
    fun setupDone(ctx: Context): Boolean = ticket(ctx) != null && AdbToggle.isGranted(ctx)

    // MARK: notification filters

    /** Drop notifications Android itself showed silently (no sound, no peek). */
    fun skipSilent(ctx: Context): Boolean = prefs(ctx).getBoolean("skipSilent", true)
    fun setSkipSilent(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("skipSilent", on).apply()

    /** Drop notifications from apps whose Mac twin is open (it shows its own). */
    fun skipOpenOnMac(ctx: Context): Boolean = prefs(ctx).getBoolean("skipOpenOnMac", true)
    fun setSkipOpenOnMac(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("skipOpenOnMac", on).apply()

    /** Per-app switch; apps are on until switched off. */
    fun appMirrored(ctx: Context, pkg: String): Boolean = pkg !in mutedApps(ctx)
    fun setAppMirrored(ctx: Context, pkg: String, on: Boolean) {
        val set = mutedApps(ctx).toMutableSet()
        if (on) set.remove(pkg) else set.add(pkg)
        prefs(ctx).edit().putStringSet("mutedApps", set).apply()
    }
    private fun mutedApps(ctx: Context): Set<String> = prefs(ctx).getStringSet("mutedApps", emptySet()) ?: emptySet()

    /**
     * Apps that have posted a notification since Bridge got access, so the
     * screen can offer a switch for each. Stored as "package\tlabel".
     */
    fun seenApps(ctx: Context): Map<String, String> =
        (prefs(ctx).getStringSet("seenApps", emptySet()) ?: emptySet())
            .mapNotNull { it.split('\t', limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] } }
            .toMap()
    fun rememberApp(ctx: Context, pkg: String, label: String) {
        val set = (prefs(ctx).getStringSet("seenApps", emptySet()) ?: emptySet()).toMutableSet()
        if (set.add("$pkg\t$label")) prefs(ctx).edit().putStringSet("seenApps", set).apply()
    }
}
