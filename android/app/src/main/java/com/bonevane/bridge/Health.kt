package com.bonevane.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager

/**
 * What's wrong with the phone's setup right now, worked out in one place so
 * the phone's screen, its notification and the computers all agree.
 *
 * The computers get only the codes ("issues=keylost,battery" in the status
 * line, see BleLink.sendStatus) and word them themselves: Bluetooth is slow,
 * and the wording belongs to whoever shows it.
 */
object Health {
    enum class Level { ERROR, WARNING }

    /** One problem. [code] is what travels to the computers; [fix] is what the phone's button does. */
    data class Issue(
        val code: String,
        val level: Level,
        val title: String,
        val detail: String,
        val fix: Fix? = null,
    )

    enum class Fix(val label: String) {
        RENEW("Check now"),
        CHECK_KEY("Check again"),
        LISTENER("Fix"),
        NOTIFICATION_ACCESS("Allow"),
        AUTOSTART("Turn on"),
        BATTERY("Allow"),
    }

    private const val CHANNEL_ID = "problems"
    private const val NOTIFICATION_ID = 20

    /** When the listener was first seen missing; it gets a minute to connect after a start. */
    @Volatile private var listenerMissingSince = 0L
    /** The codes last reported, so listeners only hear about real changes. */
    @Volatile var lastCodes: String = ""
        private set

    fun issues(ctx: Context): List<Issue> {
        val out = mutableListOf<Issue>()
        val now = System.currentTimeMillis()
        val setUp = Prefs.ticket(ctx) != null && Prefs.adbKeyAuthorized(ctx)

        if (setUp && !AdbToggle.isGranted(ctx)) out += Issue(
            "nogrant", Level.ERROR, "Bridge can't switch USB debugging",
            "The permission Set up over USB gave Bridge is gone, which happens after reinstalling it. " +
                "Plug the phone into your computer and run Set up over USB.",
        )
        if (setUp && Prefs.keyLost(ctx)) out += Issue(
            "keylost", Level.ERROR, "Bridge lost its debugging permission",
            "Android no longer trusts Bridge, so mirroring and phone files can't start. Plug the phone into " +
                "your computer and run Set up over USB, then tap Always allow.",
            Fix.CHECK_KEY,
        ) else if (setUp && Prefs.keyUsedAt(ctx) == 0L && Prefs.goodNetworks(ctx).isEmpty() &&
                   !KeyKeeper.isForever(ctx) && DaemonManager.isDaemonAlive()) out += Issue(
            // From before Bridge tracked this, with the helper already
            // running: nothing will use the key until it has quietly lapsed,
            // and a renewal can't start by itself without knowing a network
            // where it's allowed. One tap here teaches it.
            "keycheck", Level.WARNING, "Bridge needs to check its debugging permission",
            "Android forgets it if it goes unused. Tap Check now while on your usual Wi-Fi; after that Bridge " +
                "keeps it fresh by itself.",
            Fix.RENEW,
        ) else if (setUp) KeyKeeper.expiresAt(ctx)?.let { expires ->
            val days = Math.floorDiv(expires - now, 86_400_000L)
            // keyexp:-1 = probably lapsed already, keyexp:0 = today, keyexp:N = N+1 days.
            if (expires <= now) out += Issue(
                "keyexp:-1", Level.WARNING, "Bridge's debugging permission may have expired",
                "It hasn't been used for a while, and Android forgets permissions that go unused. Connect to a " +
                    "Wi-Fi network Bridge has used before so it can check.",
                Fix.RENEW,
            ) else if (expires - now < 2 * 86_400_000L) out += Issue(
                "keyexp:$days", Level.WARNING,
                if (days < 1) "Bridge's debugging permission expires today"
                else "Bridge's debugging permission expires in ${days + 1} days",
                "Bridge renews it by itself on a Wi-Fi network it has used before. Connect to one, or tap Renew now.",
                Fix.RENEW,
            )
        }

        val access = NotificationRelay.hasAccess(ctx)
        val running = NotificationService.current != null
        if (access && !running) {
            if (listenerMissingSince == 0L) listenerMissingSince = now
        } else listenerMissingSince = 0L
        if (access && !running && now - listenerMissingSince > 60_000) out += Issue(
            "nolistener", Level.ERROR, "Notifications aren't reaching your computer",
            "Notification access is on, but Android isn't running Bridge's listener." +
                if (Access.isXiaomi()) " On Xiaomi phones this is Autostart being off." else "",
            Fix.LISTENER,
        )
        if (!access && Prefs.ticket(ctx) != null) out += Issue(
            "notifoff", Level.WARNING, "Notification access is off",
            "Your notifications can't appear on your computer until Bridge is allowed to read them.",
            Fix.NOTIFICATION_ACCESS,
        )
        if (Access.isXiaomi() && !Access.miuiAutostartAllowed(ctx)) out += Issue(
            "autostart", Level.WARNING, "Autostart is off",
            "Xiaomi phones stop Bridge in the background without it, which also stops notifications.",
            Fix.AUTOSTART,
        )
        if (!ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)) out += Issue(
            "battery", Level.WARNING, "Battery use is restricted",
            "Android may pause Bridge while the screen is off, which drops the link to your computer.",
            Fix.BATTERY,
        )
        return out.sortedBy { it.level }
    }

    /** "keylost,battery", for the status line. Empty when all is well. */
    fun codes(ctx: Context): String = issues(ctx).joinToString(",") { it.code }

    /**
     * Something that affects health may have changed: tell the screen, the
     * computers and the notification, but only if the answer is different.
     * Cheap enough to call from anywhere, any thread.
     */
    fun changed(ctx: Context) {
        val list = runCatching { issues(ctx) }.getOrDefault(emptyList())
        val codes = list.joinToString(",") { it.code }
        if (codes == lastCodes) return
        lastCodes = codes
        TunnelState.notifyListeners()
        TunnelService.current?.ble?.sendStatus()
        notify(ctx, list)
    }

    /**
     * One notification for the problems that stop something working (and the
     * key about to lapse, which will). Warnings that only make things worse,
     * like battery restrictions, stay on the screen.
     */
    private fun notify(ctx: Context, list: List<Issue>) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val worth = list.filter { it.level == Level.ERROR || it.code.startsWith("keyexp") || it.code == "keycheck" }
        if (worth.isEmpty()) { nm.cancel(NOTIFICATION_ID); return }
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Problems", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When something stops Bridge working, and how to fix it"
            }
        )
        val top = worth.first()
        val open = PendingIntent.getActivity(
            ctx, 20, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val more = worth.size - 1
        val n = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(top.title)
            .setContentText(top.detail)
            .setStyle(Notification.BigTextStyle().bigText(
                top.detail + if (more > 0) "\n\nAnd $more more in Bridge." else ""))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    /** What a fix button does on the phone. */
    fun intentFor(ctx: Context, fix: Fix): Intent? = when (fix) {
        Fix.LISTENER, Fix.NOTIFICATION_ACCESS -> {
            runCatching {
                android.service.notification.NotificationListenerService.requestRebind(
                    ComponentName(ctx, NotificationService::class.java))
            }
            if (Access.isXiaomi() && !Access.miuiAutostartAllowed(ctx)) Access.autostartIntent()
            else Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        Fix.AUTOSTART -> Access.autostartIntent()
        Fix.BATTERY -> Intent(
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:${ctx.packageName}"),
        )
        Fix.RENEW, Fix.CHECK_KEY -> null
    }
}
