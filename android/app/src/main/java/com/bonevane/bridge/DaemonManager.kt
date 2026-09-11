package com.bonevane.bridge

import android.content.Context
import android.provider.Settings
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Starts and stops the shell-uid [Daemon] from inside the app, no computer needed.
 *
 * Preconditions (both set up once over USB by the Mac, or by an earlier run):
 *  - adbd is in TCP mode on 5555 (`adb tcpip 5555`; the setting lasts until reboot)
 *  - this app holds WRITE_SECURE_SETTINGS (`pm grant`), so it can switch USB
 *    debugging on for the session and off again afterwards.
 *
 * Steps: enable USB debugging → wait for adbd on 127.0.0.1:5555 → authenticate
 * with our RSA key → run the `app_process` command → wait for the daemon on 5577.
 */
object DaemonManager {
    const val ADB_PORT = 5555
    const val DAEMON_PORT = 5577

    fun isDaemonAlive(): Boolean = probe(DAEMON_PORT) != null

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
        if (isDaemonAlive()) { TunnelState.log("Daemon already running"); return }

        if (!AdbToggle.isGranted(ctx)) {
            error("WRITE_SECURE_SETTINGS not granted yet; run \"Set up over USB\" once")
        }
        if (Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) != 1) {
            AdbToggle.set(ctx, true)
        }

        // adbd takes a second or two to come up after the setting flips. If it
        // never listens on 5555, the phone was rebooted: set TCP mode again over
        // wireless debugging, then retry.
        var client = connectWithRetry(ctx, attempts = 5)
        if (client == null) {
            WifiBootstrap.run(ctx)
            client = connectWithRetry(ctx, attempts = 10) ?: error("adbd not reachable on $ADB_PORT")
        }
        TunnelState.log("Connected to adbd")

        client.use {
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

    private fun connectWithRetry(ctx: Context, attempts: Int): AdbClient? {
        val key = AdbKey.load(ctx)
        repeat(attempts) {
            val client = AdbClient("127.0.0.1", ADB_PORT, key)
            try { client.connect(); return client } catch (e: Exception) { client.close(); Thread.sleep(1000) }
        }
        return null
    }

    /** Kills the daemon and, if asked, turns USB debugging off again. */
    fun stop(ctx: Context, disableAdb: Boolean) {
        runCatching {
            AdbClient("127.0.0.1", ADB_PORT, AdbKey.load(ctx)).use {
                it.connect(); it.shell("pkill -f com.bonevane.bridge.Daemon")
            }
            TunnelState.log("Daemon stopped")
        }.onFailure { TunnelState.log("Stop failed: ${it.message}") }
        if (disableAdb) AdbToggle.set(ctx, false)
    }
}
