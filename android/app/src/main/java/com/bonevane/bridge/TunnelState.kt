package com.bonevane.bridge

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory state shared by the service, the screen and the ticket provider
 * (they all live in the same app process).
 */
object TunnelState {
    @Volatile var running = false
    /** The internet tunnel specifically; Bluetooth runs independently of it. */
    @Volatile var tunnelOn = false
    @Volatile var ready = false
    @Volatile var status = "Stopped"
    @Volatile var ticket: String? = null
    /** A Mac is subscribed over Bluetooth right now. */
    @Volatile var macLinked = false
        set(value) { if (field != value) { field = value; notifyListeners() } }

    /** When the Mac last said anything, and how many streams it has open now. */
    @Volatile var lastMacContact = 0L
    val openStreams = java.util.concurrent.atomic.AtomicInteger(0)

    fun macSeen() { lastMacContact = System.currentTimeMillis() }

    /** True while the Mac is actively using a stream, or spoke in the last [ms]. */
    fun macActive(ms: Long): Boolean =
        openStreams.get() > 0 || System.currentTimeMillis() - lastMacContact < ms

    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun update(status: String, ready: Boolean) {
        this.status = status
        this.ready = ready
        notifyListeners()
    }

    fun log(line: String) {
        android.util.Log.i("Bridge", line) // also visible via `adb logcat -s Bridge`
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > 200) lines.removeFirst()
        }
        notifyListeners()
    }

    fun logText(): String = synchronized(lines) { lines.joinToString("\n") }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun notifyListeners() {
        main.post { listeners.forEach { it() } }
    }
}
