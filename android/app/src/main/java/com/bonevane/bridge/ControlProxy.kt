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
 *  - an ADB session: every one starts with the 4 bytes "CNXN", so we pipe it
 *    straight through to adbd on 127.0.0.1:5555 (only running during a session), or
 *  - a control line from the Mac, e.g. "START\n": bring the daemon up (turning
 *    USB debugging on), "STOP\n": tear it down and turn USB debugging off,
 *    "STATUS\n": report. Replies are one line: "OK …" or "ERR …".
 *
 * This lets one ticket and one port carry both, so the Mac's Connect button can
 * enable everything remotely and Disconnect can put the phone back into the
 * "no USB debugging" state that banking apps insist on.
 */
class ControlProxy(private val ctx: Context) {
    companion object {
        const val PORT = 5580
        private val CNXN = "CNXN".toByteArray()
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
                "CNXN" -> pipeToAdb(it, head)
                "VIDE", "CTRL" -> pipeToDaemon(it, head)   // "VIDEO …" / "CTRL …" session streams
                else -> control(it, head)
            }
        }
    }

    private fun pipeToAdb(client: Socket, head: ByteArray) {
        val adb = runCatching { Socket("127.0.0.1", DaemonManager.ADB_PORT) }
            .getOrElse { TunnelState.log("adb stream refused: adbd not running"); return }
        adb.use {
            it.getOutputStream().write(head)
            val t = Thread { pump(it.getInputStream(), client.getOutputStream()); runCatching { client.shutdownOutput() } }
            t.start()
            pump(client.getInputStream(), it.getOutputStream())
            runCatching { it.shutdownOutput() }
            t.join()
        }
    }

    /** Session streams go to the shell-uid daemon, which relays scrcpy-server's sockets. */
    private fun pipeToDaemon(client: Socket, head: ByteArray) {
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
            "STOP" -> { DaemonManager.stop(ctx, disableAdb = true); reply("OK stopped, USB debugging off") }
            "BOOTSTRAP" -> runCatching { WifiBootstrap.run(ctx) }
                .onSuccess { reply("OK bootstrapped") }
                .onFailure { reply("ERR ${it.message}") }
            "STATUS" -> {
                val adb = Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
                reply("OK adb=$adb daemon=${DaemonManager.isDaemonAlive()}")
            }
            else -> reply("ERR unknown command")
        }
    }
}
