package com.bonevane.bridge

import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Makes copying on the phone reach the Mac by itself.
 *
 * Android won't let this app read the clipboard in the background, but the
 * shell-uid daemon can, so the *phone* subscribes to its own daemon locally
 * (the same control-only scrcpy-server the Mac used to open over the tunnel)
 * and forwards anything copied over Bluetooth. Nothing leaves the phone except
 * the copied text, and no tunnel is involved.
 *
 * Needs the daemon, i.e. USB debugging on. With the phone locked down, use the
 * "Send clipboard" tile instead, which works with no privileges at all.
 */
class ClipboardWatcher(private val ble: () -> BleLink?) {

    @Volatile private var active = false
    private var socket: Socket? = null

    /** The last value seen, so a clipboard that came *from* the Mac isn't echoed back. */
    @Volatile var lastValue: String? = null

    fun start() {
        if (active) return
        active = true
        Thread({ loop() }, "clipboard-watcher").apply { isDaemon = true }.start()
    }

    fun stop() {
        active = false
        runCatching { socket?.close() }
        socket = null
    }

    private fun loop() {
        while (active) {
            runCatching { watch() }
                .onFailure { if (active) TunnelState.log("Clipboard watch ended: ${it.message}") }
            if (active) Thread.sleep(15_000)      // daemon may be down; try again later
        }
    }

    private fun watch() {
        if (!DaemonManager.isDaemonAlive()) return
        val s = Socket()
        s.connect(InetSocketAddress("127.0.0.1", DaemonManager.DAEMON_PORT), 3000)
        socket = s
        val input = DataInputStream(s.getInputStream())
        val output = s.getOutputStream()

        readLine(input)                       // the daemon's hello
        output.write("CLIP\n".toByteArray()); output.flush()
        if (readLine(input) != "OK") { s.close(); return }
        TunnelState.log("Watching the clipboard for changes")

        // scrcpy's device → client messages; type 0 is a clipboard change.
        while (active) {
            val type = input.read()
            if (type < 0) break
            when (type) {
                0 -> {
                    val length = input.readInt()
                    if (length !in 0..(1 shl 20)) break
                    val text = String(ByteArray(length).also { input.readFully(it) })
                    forward(text)
                }
                1 -> input.skipBytes(8)                       // clipboard ack
                2 -> input.skipBytes(input.readUnsignedShort().let { input.readUnsignedShort() })
                else -> break
            }
        }
        runCatching { s.close() }
    }

    private fun forward(text: String) {
        if (text.isEmpty() || text == lastValue) return       // don't echo the Mac back at itself
        lastValue = text
        val link = ble()
        if (link?.connected == true) {
            link.send(BleLink.TYPE_CLIPBOARD, text)
            TunnelState.log("Clipboard sent to the Mac")
        }
    }

    private fun readLine(input: DataInputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) break
            sb.append(c.toChar())
        }
        return sb.toString()
    }
}
