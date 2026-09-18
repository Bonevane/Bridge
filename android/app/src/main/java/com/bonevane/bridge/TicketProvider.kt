package com.bonevane.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder

/**
 * Lets the Mac app read the ticket over USB:
 *
 *   adb shell content query --uri content://com.bonevane.bridge.ticket/ticket
 *
 * Only adb shell (uid 2000) or root may read it; every other app is refused.
 */
class TicketProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        // Providers are created before anything else in the process, so this is
        // the earliest place to keep an application context for the objects
        // that have none of their own (DaemonManager, the daemon probes).
        context?.let { AppContext.value = it.applicationContext }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val caller = Binder.getCallingUid()
        if (caller != SHELL_UID && caller != ROOT_UID) {
            throw SecurityException("Only adb shell can read the Bridge ticket")
        }
        val ctx = context ?: throw IllegalStateException("No context")
        val ticket = TunnelState.ticket ?: Prefs.ticket(ctx) ?: ""
        // The pairing secret travels the same shell-only road as the ticket.
        // daemon=1 only if the helper answers *with this secret*: a leftover
        // helper from an older install holds the port but is useless to us.
        val daemon = DaemonManager.probe(DaemonManager.DAEMON_PORT) != null
        return MatrixCursor(arrayOf("ticket", "ready", "status", "secret", "keyOk", "daemon")).apply {
            addRow(arrayOf<Any>(ticket, if (TunnelState.ready) 1 else 0, TunnelState.status,
                                Prefs.pairSecret(ctx), if (Prefs.adbKeyAuthorized(ctx)) 1 else 0, if (daemon) 1 else 0))
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0

    private companion object {
        const val SHELL_UID = 2000
        const val ROOT_UID = 0
    }
}
