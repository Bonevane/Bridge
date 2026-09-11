package com.bonevane.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Starts the tunnel again after a reboot or an app update, if it was on before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Prefs.autostart(context) || !Prefs.wantRunning(context)) return
        try {
            TunnelService.start(context)
        } catch (e: Exception) {
            // Some Android versions block this; opening the app starts it instead.
            Log.w("Bridge", "Could not start tunnel after boot", e)
        }
    }
}
