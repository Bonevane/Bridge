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

    override fun onCreate(): Boolean = true

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
        return MatrixCursor(arrayOf("ticket", "ready", "status")).apply {
            addRow(arrayOf<Any>(ticket, if (TunnelState.ready) 1 else 0, TunnelState.status))
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
