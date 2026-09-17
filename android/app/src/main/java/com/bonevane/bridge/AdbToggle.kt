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

    /**
     * Whether USB debugging is on. Not read from the setting: since Android 17
     * the settings provider reports `adb_enabled` as 0 to apps even when it is
     * 1 (checked against `adb shell settings get` on a Pixel 9 Pro). The USB
     * gadget configuration and adbd's own service state are still readable
     * through getprop, and they're the truth.
     */
    fun isEnabled(ctx: Context): Boolean {
        val config = prop("sys.usb.config").ifEmpty { prop("persist.sys.usb.config") }
        if (config.split(',').contains("adb")) return true
        if (prop("init.svc.adbd") == "running") return true
        return Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
    }

    private fun prop(name: String): String = runCatching {
        ProcessBuilder("getprop", name).start().inputStream.bufferedReader().readText().trim()
    }.getOrDefault("")

    fun set(ctx: Context, enabled: Boolean): Boolean {
        val ok = runCatching {
            Settings.Global.putInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, if (enabled) 1 else 0)
        }.getOrElse { Log.e(TAG, "adb_enabled write failed", it); false }
        Log.i(TAG, "adb_enabled=${if (enabled) 1 else 0} ok=$ok")
        TunnelState.log("USB debugging ${if (enabled) "on" else "off"}: ${if (ok) "ok" else "FAILED"}")
        return ok
    }
}
