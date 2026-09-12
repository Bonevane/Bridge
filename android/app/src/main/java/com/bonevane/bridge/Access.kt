package com.bonevane.bridge

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Everything Bridge needs the user to grant, in one place, with the screen that
 * grants it. Android scatters these across half a dozen settings pages, so the
 * app lists them with their current state and a button that goes straight there.
 */
data class Access(
    val name: String,
    val why: String,
    val granted: Boolean,
    /** Null when the user can't act on it directly (see [Access.secureSettings]). */
    val intent: Intent?,
    /** True when a runtime permission prompt is the right way to ask. */
    val runtimePermissions: List<String> = emptyList(),
) {
    companion object {

        fun all(ctx: Context): List<Access> = listOfNotNull(
            notifications(ctx),
            notificationAccess(ctx),
            bluetooth(ctx),
            battery(ctx),
            secureSettings(ctx),
            developerOptions(ctx),
        )

        private fun notifications(ctx: Context) = Access(
            name = "Notifications",
            why = "Lets Bridge show the status notification while it's running.",
            granted = Build.VERSION.SDK_INT < 33 ||
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
            intent = appNotificationSettings(ctx),
            runtimePermissions = if (Build.VERSION.SDK_INT >= 33)
                listOf(android.Manifest.permission.POST_NOTIFICATIONS) else emptyList(),
        )

        private fun notificationAccess(ctx: Context) = Access(
            name = "Notification access",
            why = "Lets your notifications appear on the Mac. Works even with USB debugging off.",
            granted = NotificationRelay.hasAccess(ctx),
            intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        )

        private fun bluetooth(ctx: Context) = Access(
            name = "Nearby devices",
            why = "The short-range link that carries notifications and the clipboard.",
            granted = Build.VERSION.SDK_INT < 31 || listOf(
                "android.permission.BLUETOOTH_ADVERTISE",
                "android.permission.BLUETOOTH_CONNECT",
            ).all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED },
            intent = appDetails(ctx),
            runtimePermissions = if (Build.VERSION.SDK_INT >= 31) listOf(
                "android.permission.BLUETOOTH_ADVERTISE",
                "android.permission.BLUETOOTH_CONNECT",
            ) else emptyList(),
        )

        private fun battery(ctx: Context) = Access(
            name = "Unrestricted battery",
            why = "Stops Android suspending Bridge, which would drop the link while the screen is off.",
            granted = ctx.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(ctx.packageName),
            intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${ctx.packageName}"),
            ),
        )

        /**
         * Not grantable by hand: it's `signature|privileged`, and Bridge gets it
         * from the shell during "Set up over USB". Listed anyway because without
         * it the phone can't turn its own USB debugging on and off.
         */
        private fun secureSettings(ctx: Context) = Access(
            name = "Change developer settings",
            why = "Granted over USB during setup. Lets Bridge switch USB debugging on for a session and off afterwards.",
            granted = AdbToggle.isGranted(ctx),
            intent = null,
        )

        private fun developerOptions(ctx: Context) = Access(
            name = "Wireless debugging",
            why = "Needed for a second when Bridge starts its helper. Android only offers it on Wi-Fi.",
            granted = Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) == 1 ||
                AdbToggle.isEnabled(ctx),
            intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        )

        private fun appDetails(ctx: Context) = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${ctx.packageName}"),
        )

        private fun appNotificationSettings(ctx: Context) =
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
    }
}
