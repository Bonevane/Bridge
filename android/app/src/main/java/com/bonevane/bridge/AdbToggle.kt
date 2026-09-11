package com.bonevane.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * Turns USB debugging on and off from inside the app.
 *
 * Writing `adb_enabled` needs WRITE_SECURE_SETTINGS, which an ordinary app can't
 * request. But the shell user can hand it to us once (`pm grant`), and the grant
 * survives reboots. That is how the app can bring adbd back after a banking app
 * made you turn debugging off, without a cable or Wi-Fi (adbd keeps its TCP port).
 */
object AdbToggle {
    private const val TAG = "Bridge"

    fun isGranted(ctx: Context): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    fun set(ctx: Context, enabled: Boolean): Boolean {
        val ok = runCatching {
            Settings.Global.putInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, if (enabled) 1 else 0)
        }.getOrElse { Log.e(TAG, "adb_enabled write failed", it); false }
        Log.i(TAG, "adb_enabled=${if (enabled) 1 else 0} ok=$ok")
        TunnelState.log("USB debugging ${if (enabled) "on" else "off"}: ${if (ok) "ok" else "FAILED"}")
        return ok
    }

    /** Experiment helper: off now, on again after 20 seconds. */
    fun cycle(ctx: Context) {
        if (!isGranted(ctx)) { TunnelState.log("WRITE_SECURE_SETTINGS not granted"); return }
        set(ctx, false)
        Handler(Looper.getMainLooper()).postDelayed({ set(ctx.applicationContext, true) }, 20_000)
    }
}
