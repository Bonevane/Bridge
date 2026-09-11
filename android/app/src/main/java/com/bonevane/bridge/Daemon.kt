package com.bonevane.bridge

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * The privileged half of Bridge: a process running as the *shell* user (uid
 * 2000), outside the app sandbox, started the way scrcpy and Shizuku start
 * theirs (see DaemonManager):
 *
 *   CLASSPATH=<our apk> app_process / com.bonevane.bridge.Daemon
 *
 * It listens on 127.0.0.1:5577. Every connection first gets a hello line, then
 * may send one command line:
 *
 *   VIDEO <scrcpy options…>   spawn scrcpy's server for this session; this
 *                             connection then carries its raw video stream
 *   CTRL <scid>               attach to that session's control stream
 *   QUIT                      exit (the app does this after it was updated,
 *                             because this process still points at the old APK)
 *
 * scrcpy's server only talks over a Unix "localabstract" socket, which the app
 * (untrusted uid) isn't allowed to reach; a shell process is. So the daemon's
 * job here is to spawn it and relay its two streams to loopback TCP for the app.
 */
object Daemon {
    private const val PORT = 5577
    private const val SCRCPY_VERSION = "4.1"  // must match the bundled jar
    private val LOG = File("/data/local/tmp/bridge-daemon.log")

    private class Session(val scid: String, val process: Process, val control: LocalSocket)
    private val sessions = ConcurrentHashMap<String, Session>()

    @JvmStatic
    fun main(args: Array<String>) {
        val started = System.currentTimeMillis()
        val uid = android.os.Process.myUid()
        val pid = android.os.Process.myPid()
        log("started uid=$uid pid=$pid")

        val server = ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"))
        while (true) {
            val s = server.accept()
            Thread {
                runCatching {
                    val up = (System.currentTimeMillis() - started) / 1000
                    val apk = System.getenv("CLASSPATH") ?: "?"
                    s.getOutputStream().write("bridge-daemon uid=$uid pid=$pid up=${up}s apk=$apk\n".toByteArray())
                    s.getOutputStream().flush()
                    handle(s)
                }.onFailure { log("connection error: $it") }
                runCatching { s.close() }
            }.start()
        }
    }

    private fun handle(s: Socket) {
        s.soTimeout = 3000
        val line = readLine(s.getInputStream()) ?: return  // plain probe: hello only
        s.soTimeout = 0
        val cmd = line.substringBefore(' ')
        val rest = line.substringAfter(' ', "")
        when (cmd) {
            "VIDEO" -> video(s, rest)
            "CTRL" -> control(s, rest.trim())
            "QUIT" -> { log("quit requested"); reply(s, "OK bye"); System.exit(0) }
            else -> s.getOutputStream().write("ERR unknown command\n".toByteArray())
        }
    }

    /** Spawns scrcpy-server and pipes its video socket to [s]. Blocks for the session. */
    private fun video(s: Socket, options: String) {
        val jar = findJar() ?: run { reply(s, "ERR scrcpy jar not found"); return }
        val scid = "%08x".format((Math.random() * 0x7fffffff).toInt())
        val args = mutableListOf(
            "app_process", "/", "com.genymobile.scrcpy.Server", SCRCPY_VERSION,
            "scid=$scid", "tunnel_forward=true", "audio=false", "send_dummy_byte=false",
            "log_level=info"
        )
        args += options.split(' ').filter { it.isNotBlank() }
        val process = ProcessBuilder(args).apply {
            environment()["CLASSPATH"] = jar
            redirectErrorStream(true)
            redirectOutput(File("/data/local/tmp/scrcpy-server.log"))
        }.start()
        log("session $scid: started scrcpy-server (${args.drop(4).joinToString(" ")})")

        // The server accepts the video socket first, then the control socket.
        val videoSock = connectLocal("scrcpy_$scid") ?: run {
            process.destroy(); reply(s, "ERR scrcpy-server did not start"); return
        }
        val controlSock = connectLocal("scrcpy_$scid") ?: run {
            videoSock.close(); process.destroy(); reply(s, "ERR control socket"); return
        }
        sessions[scid] = Session(scid, process, controlSock)
        reply(s, "OK scid=$scid")
        try {
            pump(videoSock.inputStream, s.getOutputStream())
        } finally {
            log("session $scid: video ended")
            sessions.remove(scid)
            runCatching { videoSock.close() }
            runCatching { controlSock.close() }
            process.destroy()
        }
    }

    /** Attaches [s] to a running session's control stream (both directions). */
    private fun control(s: Socket, scid: String) {
        val session = sessions[scid] ?: run { reply(s, "ERR no such session"); return }
        reply(s, "OK")
        val ctl = session.control
        val t = Thread { pump(ctl.inputStream, s.getOutputStream()) }
        t.start()
        pump(s.getInputStream(), ctl.outputStream)
        runCatching { ctl.close() }
        t.join()
    }

    private fun connectLocal(name: String): LocalSocket? {
        repeat(30) {
            runCatching {
                LocalSocket().apply { connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT)) }
            }.onSuccess { return it }
            Thread.sleep(100)
        }
        return null
    }

    /** The scrcpy jar sits next to the dumbpipe binary, wherever Android put our native libs. */
    private fun findJar(): String? {
        val apk = System.getenv("CLASSPATH") ?: return null
        val base = File(apk).parentFile ?: return null
        return listOf("lib/arm64", "lib/arm64-v8a").map { File(base, "$it/libscrcpy.so") }
            .firstOrNull { it.exists() }?.absolutePath
    }

    private fun pump(from: InputStream, to: OutputStream) {
        val buf = ByteArray(128 * 1024)
        runCatching {
            while (true) {
                val r = from.read(buf)
                if (r < 0) break
                to.write(buf, 0, r); to.flush()
            }
        }
    }

    private fun reply(s: Socket, line: String) {
        s.getOutputStream().write("$line\n".toByteArray()); s.getOutputStream().flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = runCatching { input.read() }.getOrElse { return null }
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            sb.append(c.toChar())
        }
    }

    private fun log(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        runCatching { LOG.appendText("$ts $msg\n") }
        println("$ts $msg")
    }
}
