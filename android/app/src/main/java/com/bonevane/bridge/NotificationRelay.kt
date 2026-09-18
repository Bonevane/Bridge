package com.bonevane.bridge

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Mirrors the phone's notifications to the Mac.
 *
 * This is an ordinary Android API (a notification listener the user grants once
 * in Settings), not a privileged one, so it keeps working while USB debugging
 * is off: notifications arrive even when the phone is fully locked down.
 *
 * The text is handed to [NotificationRelay] as one line per notification, and
 * `ControlProxy` streams those lines to whoever asked for them.
 */
class NotificationService : NotificationListenerService() {

    override fun onListenerConnected() {
        NotificationRelay.connected = true
        TunnelState.log("Notification access granted")
    }

    override fun onListenerDisconnected() {
        NotificationRelay.connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return          // never mirror our own
        val n = sbn.notification ?: return

        val app = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        }.getOrDefault(sbn.packageName)
        // Remembered even when skipped, so the user can find it in the list.
        Prefs.rememberApp(this, sbn.packageName, app)

        NotificationFilter.reasonToSkip(this, this, sbn)?.let { reason ->
            TunnelState.log("Not mirrored ($reason): $app")
            return
        }

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        NotificationRelay.post(app, title, text, sbn.packageName)
    }
}

/** Shared fan-out point between the listener service and the tunnel. */
object NotificationRelay {
    @Volatile var connected = false

    private val subscribers = CopyOnWriteArrayList<(String) -> Unit>()

    /** One notification as a single line: app, title and text, tab separated. */
    /** app, title, text, and the package (so the receiver can ask for its icon). */
    fun post(app: String, title: String, text: String, pkg: String = "") {
        fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').trim()
        val line = "${clean(app)}\t${clean(title)}\t${clean(text)}\t${clean(pkg)}"
        subscribers.forEach { runCatching { it(line) } }
    }

    fun subscribe(listener: (String) -> Unit) { subscribers.add(listener) }
    fun unsubscribe(listener: (String) -> Unit) { subscribers.remove(listener) }

    /** Whether the user has granted notification access to Bridge. */
    fun hasAccess(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
        val me = ComponentName(ctx, NotificationService::class.java).flattenToString()
        return enabled.split(':').any { it == me || it.endsWith(NotificationService::class.java.name) }
    }
}
