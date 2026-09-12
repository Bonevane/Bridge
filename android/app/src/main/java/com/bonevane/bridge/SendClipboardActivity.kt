package com.bonevane.bridge

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Sends the phone's clipboard to the Mac.
 *
 * Android 10+ only lets the *focused* app read the clipboard, which is why this
 * is an activity rather than a background action: it appears for a fraction of a
 * second (it's transparent and finishes immediately), and that moment of focus
 * is enough to read the clipboard legally. KDE Connect hits the same wall and
 * solves it the same way, by needing its app opened.
 *
 * Two ways in:
 *  - the Quick Settings tile, which sends whatever is on the clipboard
 *  - the share sheet, where the text comes in the intent and no clipboard
 *    access is needed at all
 */
class SendClipboardActivity : Activity() {

    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Shared text arrives directly, so it needs no clipboard read or focus.
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            finishAfter(text)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Only once we actually hold focus is the clipboard readable.
        if (!hasFocus || handled) return
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        finishAfter(text)
    }

    private fun finishAfter(text: String?) {
        handled = true
        when {
            text.isNullOrBlank() -> toast("Nothing to send")
            sendToMac(text) -> toast("Sent to your Mac")
            else -> toast("Your Mac isn't connected")
        }
        finish()
        overridePendingTransition(0, 0)   // no flash
    }

    /** Bluetooth if the Mac is nearby; that's the only path that needs nothing else. */
    private fun sendToMac(text: String): Boolean {
        val ble = TunnelService.current?.ble ?: return false
        if (!ble.connected) return false
        ble.send(BleLink.TYPE_CLIPBOARD, text)
        return true
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

/** Quick Settings tile: one tap sends the clipboard to the Mac. */
class ClipboardTile : android.service.quicksettings.TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = if (TunnelService.current?.ble?.connected == true)
                android.service.quicksettings.Tile.STATE_INACTIVE
            else
                android.service.quicksettings.Tile.STATE_UNAVAILABLE
            label = "Send clipboard"
            subtitle = if (TunnelService.current?.ble?.connected == true) "to your Mac" else "Mac not nearby"
            updateTile()
        }
    }

    override fun onClick() {
        val intent = Intent(this, SendClipboardActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this, 0, intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
