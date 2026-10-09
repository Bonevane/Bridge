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
    /** Where to go instead if [intent]'s screen doesn't exist on this phone. */
    val fallback: Intent? = null,
) {
    companion object {

        fun all(ctx: Context): List<Access> = listOfNotNull(
            notifications(ctx),
            notificationAccess(ctx),
            bluetooth(ctx),
            battery(ctx),
            xiaomiAutostart(ctx),
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
            why = "Lets your notifications appear on the Mac. The switch will be greyed out at first (Android does this " +
                "for apps installed outside Play): tap the greyed switch once, then open Bridge's App info, tap ⋮ top right → " +
                "Allow restricted settings, and come back to turn it on.",
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
            why = "Set Up Over USB grants this; you can't tap it. Lets Bridge switch USB debugging on for a session and off afterwards." +
                if (isXiaomi()) " On Xiaomi, first turn on Developer options → USB debugging (Security settings) " +
                    "(Xiaomi asks for a SIM and a Mi account), or the grant is refused." else "",
            granted = AdbToggle.isGranted(ctx),
            intent = null,
        )

        /** Xiaomi, Redmi and POCO all run MIUI/HyperOS and report Xiaomi here. */
        fun isXiaomi(): Boolean =
            listOf(Build.MANUFACTURER, Build.BRAND).any { it.equals("xiaomi", true) || it.equals("redmi", true) || it.equals("poco", true) }

        /**
         * MIUI's own switch, on top of Android's: with Autostart off it refuses
         * to start an app's background parts, including the notification
         * listener Android itself has to launch. Notification access then looks
         * granted and nothing arrives ("AutoStartManagerService: Reject service").
         */
        private fun xiaomiAutostart(ctx: Context): Access? {
            if (!isXiaomi()) return null
            return Access(
                name = "Autostart (Xiaomi)",
                why = "Xiaomi phones won't let Bridge run in the background without it, so notifications never reach the " +
                    "computer. Turn on Autostart for Bridge. In Battery saver, set Bridge to No restrictions as well.",
                granted = miuiAutostartAllowed(ctx),
                intent = Intent().setClassName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                fallback = appDetails(ctx),
            )
        }

        /**
         * MIUI keeps Autostart as a private app-op (10008) that no public API
         * reads. Asked by reflection; if MIUI ever hides that, we can't tell,
         * so it shows as not done and the user can check.
         */
        private fun miuiAutostartAllowed(ctx: Context): Boolean = runCatching {
            val ops = ctx.getSystemService(android.app.AppOpsManager::class.java)
            val check = android.app.AppOpsManager::class.java.getMethod(
                "checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
            check.invoke(ops, 10008, android.os.Process.myUid(), ctx.packageName) == android.app.AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)

        private fun developerOptions(ctx: Context) = Access(
            name = "USB debugging",
            why = "Settings → About phone → tap Build number 7 times, then System → Developer options → USB debugging. " +
                "When the Mac asks \"Allow USB debugging?\", tick Always allow from this computer. " +
                "After setup you can turn Developer options off again, but do it while on Wi-Fi: that also turns USB " +
                "debugging off, and Bridge can only restart its helper over Wi-Fi. Once it's back (a few seconds), " +
                "cellular is fine again.",
            granted = AdbToggle.isEnabled(ctx),
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
