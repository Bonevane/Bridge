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
 *
 * Careful: streams are matched on their first four bytes, so no control command
 * may begin with VIDE, AUDI, CTRL, CLIP, INST, PUSH or NOTI.
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
        client.use {
            val input = it.getInputStream()
            // Every stream opens with "AUTH <secret>\n". Without it, whoever
            // reached this port (any app on the phone, any process on the Mac
            // that found dumbpipe's local port) is a stranger and gets nothing.
            val auth = readLine(input, 200)?.takeIf { it.startsWith("AUTH ") }?.substring(5)
            if (!Pairing.matches(ctx, auth)) {
                runCatching { it.getOutputStream().write("ERR unauthorized\n".toByteArray()) }
                TunnelState.log("Refused a stream: wrong or missing pairing secret")
                return
            }
            TunnelState.macSeen()
            val head = ByteArray(4)
            var n = 0
            while (n < 4) {
                val r = input.read(head, n, 4 - n)
                if (r < 0) return
                n += r
            }
            when (String(head)) {
                "VIDE", "AUDI", "CTRL", "CLIP", "INST", "PUSH" -> pipeToDaemon(it, head)   // daemon streams
                "NOTI" -> notifications(it)
                else -> control(it, head)
            }
        }
    }

    /** Session streams go to the shell-uid daemon, which relays scrcpy-server's sockets. */
    private fun pipeToDaemon(client: Socket, head: ByteArray) {
        // A session's worth of traffic is starting: keep the radio responsive
        // while it lasts, and let it idle again afterwards.
        if (TunnelState.openStreams.incrementAndGet() == 1) {
            TunnelService.current?.holdWifiAwake(true)
        }
        try { pipeToDaemonInner(client, head) } finally {
            if (TunnelState.openStreams.decrementAndGet() == 0) {
                TunnelService.current?.holdWifiAwake(false)
            }
            TunnelState.macSeen()
        }
    }

    private fun pipeToDaemonInner(client: Socket, head: ByteArray) {
        val daemon = runCatching { Socket("127.0.0.1", DaemonManager.DAEMON_PORT) }
            .getOrElse { TunnelState.log("session stream refused: daemon not running"); return }
        daemon.use {
            it.getOutputStream().write((Pairing.authLine(ctx) + "\n").toByteArray())
            it.getOutputStream().flush()
            if (readLine(it.getInputStream(), 200)?.startsWith("bridge-daemon") != true) {
                TunnelState.log("session stream refused: the helper rejected the pairing secret"); return
            }
            it.getOutputStream().write(head)
            val t = Thread { pump(it.getInputStream(), client.getOutputStream()); runCatching { client.shutdownOutput() } }
            t.start()
            pump(client.getInputStream(), it.getOutputStream())
            runCatching { it.shutdownOutput() }
            t.join()
        }
    }

    /** One line, without pulling a byte past the newline (the rest is binary). */
    private fun readLine(input: InputStream, max: Int): String? {
        val sb = StringBuilder()
        while (sb.length < max) {
            val c = input.read()
            if (c < 0) return null
            if (c == '\n'.code) return sb.toString()
            sb.append(c.toChar())
        }
        return null
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

    /**
     * Streams notifications to the Mac, one per line, until it disconnects.
     * Needs no daemon: [NotificationRelay] is plain app code, so this works even
     * with USB debugging off.
     */
    private fun notifications(client: Socket) {
        val out = client.getOutputStream()
        if (!NotificationRelay.hasAccess(ctx)) {
            out.write("ERR notification access not granted on the phone\n".toByteArray()); out.flush(); return
        }
        out.write("OK\n".toByteArray()); out.flush()

        // onNotificationPosted runs on the main thread, where Android forbids
        // socket writes, so the callback only queues and this thread does the
        // writing. The blank line every 30 s is a keepalive: writing is also how
        // we notice the Mac has gone.
        val queue = java.util.concurrent.LinkedBlockingQueue<String>(200)
        val listener: (String) -> Unit = { queue.offer(it) }
        NotificationRelay.subscribe(listener)
        try {
            while (true) {
                val line = queue.poll(30, java.util.concurrent.TimeUnit.SECONDS)
                out.write(((line ?: "") + "\n").toByteArray())
                out.flush()
            }
        } catch (e: Exception) {
            TunnelState.log("Notification stream ended: ${e.message}")
        } finally {
            NotificationRelay.unsubscribe(listener)
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
            "STOP" -> {
                reply("OK " + DaemonManager.stop(ctx))
                // The reply just went over the tunnel; give it a moment to
                // leave before the tunnel itself is switched off.
                Thread { Thread.sleep(1500); TunnelService.settleTunnelAfterSession(ctx) }.start()
            }
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
            // Experiment: can the *app* (normal uid) touch the clipboard in the
            // background? Android 10+ normally refuses. Names avoid the stream
            // prefixes CLIP/PUSH/NOTI.
            "CBGET" -> {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() }
                    .getOrElse { "EXCEPTION ${it.javaClass.simpleName}" }
                reply("OK read=${text ?: "<null>"}")
            }
            "CBSET" -> {
                val value = line.substringAfter(' ', "")
                val ok = runCatching {
                    ctx.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Bridge", value)); true
                }.getOrElse { false }
                reply("OK wrote=$ok")
            }
            "STATUS" -> {
                val adb = AdbToggle.isEnabled(ctx)
                val paused = TunnelService.current?.policy?.isPaused ?: false
                reply("OK adb=$adb daemon=${DaemonManager.isDaemonAlive()} keep=${Prefs.keepReady(ctx)}" +
                    " keepAt=${Prefs.keepReadyAt(ctx)}" +
                    " paused=$paused notif=${NotificationRelay.hasAccess(ctx)}")
            }
            else -> reply("ERR unknown command")
        }
    }
}
