package com.bonevane.bridge

import android.content.Context
import android.provider.Settings
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Starts and stops the shell-uid [Daemon] from inside the app, no computer needed.
 *
 * Precondition: this app holds WRITE_SECURE_SETTINGS (granted once over USB by
 * the Mac, or by an earlier daemon), so it can switch USB debugging on for the
 * session and off again afterwards.
 *
 * Steps: enable USB debugging (adbd must stay alive: ADB-spawned processes die
 * with it) → open the wireless-debugging door for a second → `app_process` the
 * daemon → wait for it on 5577. adbd never listens on TCP.
 */
object DaemonManager {
    const val DAEMON_PORT = 5577

    fun isDaemonAlive(): Boolean = probe(DAEMON_PORT) != null

    private fun portOpen(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }; true
    }.getOrDefault(false)

    /** Returns the daemon's hello line ("bridge-daemon uid=2000 pid=… up=…s") or null. */
    fun probe(port: Int, timeoutMs: Int = 500): String? = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            s.soTimeout = timeoutMs
            s.getInputStream().bufferedReader().readLine()
        }
    }.getOrNull()

    /** Blocking; call from a background thread. Throws with a readable message on failure. */
    fun start(ctx: Context) {
        probe(DAEMON_PORT)?.let { hello ->
            // A daemon from before an app update still runs the old code from a
            // directory that no longer exists; replace it.
            val apk = hello.substringAfter("apk=", "")
            if (apk == ctx.applicationInfo.sourceDir) { TunnelState.log("Daemon already running"); return }
            TunnelState.log("Daemon is from an older install; restarting it")
            runCatching {
                Socket("127.0.0.1", DAEMON_PORT).use { s ->
                    s.getInputStream().bufferedReader().readLine()
                    s.getOutputStream().write("QUIT\n".toByteArray()); s.getOutputStream().flush()
                    s.getInputStream().read()
                }
            }
            Thread.sleep(500)
        }

        if (!AdbToggle.isGranted(ctx)) {
            error("WRITE_SECURE_SETTINGS not granted yet; run \"Set up over USB\" once")
        }
        if (Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) != 1) {
            AdbToggle.set(ctx, true)
        }

        // Safety net: if adbd is in TCP mode (a leftover `adb tcpip` from before a
        // reboot clears it), switch it back to USB-only first. This restarts adbd,
        // so it has to happen before we spawn anything.
        if (probe(5555, timeoutMs = 300) != null || portOpen(5555)) {
            TunnelState.log("adbd is listening on TCP 5555; switching it back to USB mode")
            WifiBootstrap.withAdb(ctx) { it.service("usb:") }
            Thread.sleep(2000)
        }

        WifiBootstrap.withAdb(ctx) {
            TunnelState.log("Connected to adbd")
            // Keep the permission fresh (idempotent), then spawn the daemon detached.
            it.shell("pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS")
            val apk = ctx.applicationInfo.sourceDir
            // The legacy `shell:` service gives us a PTY that adbd hangs up when the
            // shell exits, so the daemon must be in its own session (setsid), not
            // attached to the PTY (</dev/null), and the shell waits 1 s for that.
            val cmd = "(CLASSPATH=$apk exec setsid app_process / com.bonevane.bridge.Daemon " +
                "</dev/null >/data/local/tmp/bridge-daemon.out 2>&1) & sleep 1"
            it.shell(cmd)
        }

        for (attempt in 1..20) {
            probe(DAEMON_PORT)?.let { TunnelState.log("Daemon up: $it"); return }
            Thread.sleep(250)
        }
        error("daemon did not answer on $DAEMON_PORT")
    }

    /**
     * Turns USB debugging off, which takes the daemon down with it (init kills
     * adbd's whole cgroup). This is the "lock down" state banking apps want.
     *
     * Exception, agreed with Bone: on cellular (no Wi-Fi) the daemon stays up,
     * because starting it again needs wireless debugging, which needs Wi-Fi;
     * locking down there would mean no way back in until the next Wi-Fi.
     * Returns a one-line description of what happened.
     */
    fun stop(ctx: Context, force: Boolean = false): String {
        if (!force && Prefs.keepReady(ctx)) {
            TunnelState.log("Disconnect: keeping the daemon (\"keep ready\" is on)")
            return "kept ready (setting)"
        }
        if (!force && !onWifi(ctx)) {
            TunnelState.log("Disconnect on cellular: keeping the daemon (no Wi-Fi to restart it)")
            return "kept ready: phone is on cellular"
        }
        AdbToggle.set(ctx, false)
        TunnelState.log("Daemon stopped")
        return "stopped, USB debugging off"
    }

    fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }
}
