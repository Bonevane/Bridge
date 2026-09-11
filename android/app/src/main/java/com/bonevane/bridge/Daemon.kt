package com.bonevane.bridge

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Experiment for Milestone 2: a process that runs as the *shell* user (uid 2000),
 * outside the normal app sandbox, started the same way scrcpy and Shizuku start
 * theirs:
 *
 *   adb shell CLASSPATH=<our apk> nohup setsid app_process / com.bonevane.bridge.Daemon &
 *
 * `app_process` is the launcher Android uses for every Java process; pointing
 * it at our APK runs this `main` with whatever privileges the caller (adb
 * shell) has. Later this is where screen capture and input injection go.
 * For now it only answers "are you alive?" so we can test whether the process
 * survives USB debugging being turned off and the phone sleeping overnight.
 */
object Daemon {
    private const val PORT = 5577
    private val LOG = File("/data/local/tmp/bridge-daemon.log")

    @JvmStatic
    fun main(args: Array<String>) {
        val started = System.currentTimeMillis()
        val uid = android.os.Process.myUid()
        val pid = android.os.Process.myPid()
        log("started uid=$uid pid=$pid")

        // Heartbeat thread: one line every 10 s so the log shows when we died.
        Thread {
            while (true) {
                Thread.sleep(10_000)
                log("alive up=${(System.currentTimeMillis() - started) / 1000}s")
            }
        }.apply { isDaemon = true }.start()

        // Loopback-only server; the app process will connect here later.
        val server = ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1"))
        while (true) {
            server.accept().use { s ->
                val up = (System.currentTimeMillis() - started) / 1000
                s.getOutputStream().write("bridge-daemon uid=$uid pid=$pid up=${up}s\n".toByteArray())
            }
        }
    }

    private fun log(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        runCatching { LOG.appendText("$ts $msg\n") }
        println("$ts $msg")
    }
}
