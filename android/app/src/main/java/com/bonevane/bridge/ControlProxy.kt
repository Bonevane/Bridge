package com.bonevane.bridge

import android.content.Context
import android.provider.Settings
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * What dumbpipe forwards incoming tunnel streams to. Each stream is either:
 *
 *  - a session stream for the daemon ("VIDEO …" / "CTRL …", see Daemon.kt), or
 *  - a control line from the Mac: "START" brings the daemon up (turning USB
 *    debugging on); "STOP" ends a session (USB debugging off unless "keep ready"
 *    or on cellular); "LOCKDOWN" forces it off; "PAUSE <min>" / "RESUME";
 *    "MODE keep|lock" sets the keep-ready choice; "STATUS".
 *    Replies are one line: "OK …" or "ERR …".
 *
 * One ticket and one port carry everything, so the Mac's Connect button can
 * enable everything remotely and Disconnect can put the phone back into the
 * "no USB debugging" state that banking apps insist on.
 */
class ControlProxy(private val ctx: Context) {
    companion object {
        const val PORT = 5580
    }

    private var server: ServerSocket? = null

    fun start() {
        val s = ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
        server = s
        Thread({
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                Thread({ handle(client) }, "proxy-conn").start()
            }
        }, "proxy-accept").start()
    }

    fun stop() { runCatching { server?.close() }; server = null }

    private fun handle(client: Socket) {
        TunnelState.macSeen()
        client.use {
            val input = it.getInputStream()
            val head = ByteArray(4)
            var n = 0
            while (n < 4) {
                val r = input.read(head, n, 4 - n)
                if (r < 0) return
                n += r
            }
            when (String(head)) {
                "VIDE", "AUDI", "CTRL", "CLIP", "INST", "PUSH" -> pipeToDaemon(it, head)   // daemon streams
                else -> control(it, head)
            }
        }
    }

    /** Session streams go to the shell-uid daemon, which relays scrcpy-server's sockets. */
    private fun pipeToDaemon(client: Socket, head: ByteArray) {
        TunnelState.openStreams.incrementAndGet()
        try { pipeToDaemonInner(client, head) } finally {
            TunnelState.openStreams.decrementAndGet()
            TunnelState.macSeen()
        }
    }

    private fun pipeToDaemonInner(client: Socket, head: ByteArray) {
        val daemon = runCatching { Socket("127.0.0.1", DaemonManager.DAEMON_PORT) }
            .getOrElse { TunnelState.log("session stream refused: daemon not running"); return }
        daemon.use {
            it.getInputStream().bufferedReader().let { r -> r.readLine() }  // skip hello line
            it.getOutputStream().write(head)
            val t = Thread { pump(it.getInputStream(), client.getOutputStream()); runCatching { client.shutdownOutput() } }
            t.start()
            pump(client.getInputStream(), it.getOutputStream())
            runCatching { it.shutdownOutput() }
            t.join()
        }
    }

    private fun pump(from: InputStream, to: OutputStream) {
        val buf = ByteArray(64 * 1024)
        runCatching {
            while (true) {
                val r = from.read(buf)
                if (r < 0) break
                to.write(buf, 0, r); to.flush()
            }
        }
    }

    private fun control(client: Socket, head: ByteArray) {
        val rest = client.getInputStream().bufferedReader().readLine() ?: ""
        val line = (String(head) + rest).trim()
        val out = client.getOutputStream()
        fun reply(s: String) { out.write("$s\n".toByteArray()); out.flush() }
        TunnelState.log("Mac: $line")
        when (line.substringBefore(' ')) {
            "START" -> runCatching { DaemonManager.start(ctx) }
                .onSuccess { reply("OK daemon running") }
                .onFailure { reply("ERR ${it.message}") }
            "STOP" -> reply("OK " + DaemonManager.stop(ctx))
            "LOCKDOWN" -> reply("OK " + DaemonManager.stop(ctx, force = true))
            "PAUSE" -> {
                val minutes = line.substringAfter(' ', "15").trim().toIntOrNull() ?: 15
                val policy = TunnelService.current?.policy
                reply("OK " + (policy?.pause(minutes) ?: DaemonManager.stop(ctx, force = true)))
            }
            "RESUME" -> { TunnelService.current?.policy?.resume(); reply("OK resuming") }
            "MODE" -> {   // MODE keep | MODE lock: the Mac mirrors the user's setting
                val keep = line.substringAfter(' ', "").trim() == "keep"
                Prefs.setKeepReady(ctx, keep)
                reply("OK mode=${if (keep) "keep" else "lock"}")
            }
            "STATUS" -> {
                val adb = Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
                val paused = TunnelService.current?.policy?.isPaused ?: false
                reply("OK adb=$adb daemon=${DaemonManager.isDaemonAlive()} keep=${Prefs.keepReady(ctx)} paused=$paused")
            }
            else -> reply("ERR unknown command")
        }
    }
}
