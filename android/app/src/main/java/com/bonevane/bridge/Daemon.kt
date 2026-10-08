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
 *   AUDIO <scid>              attach to that session's audio stream (AAC)
 *   CTRL <scid>               attach to that session's control stream
 *   LIST <dir>                a folder in shared storage, one entry per line
 *   PULL <file>               a file from shared storage: "OK <size>" + bytes
 *   CLIP                      a standalone clipboard channel: a control-only
 *                             scrcpy-server (no video), relayed both ways, so
 *                             the clipboard syncs without a mirroring window
 *   PUSH <bytes> <name>       then that many bytes: saves the file into
 *                             /sdcard/Download and asks Android to index it
 *   INSTALL <bytes>           then that many bytes of APK: installs it with
 *                             `pm install` (shell may), so updates need no cable
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

    private class Session(val scid: String, val process: Process, val audio: LocalSocket, val control: LocalSocket)
    private val sessions = ConcurrentHashMap<String, Session>()

    @JvmStatic
    fun main(args: Array<String>) {
        val started = System.currentTimeMillis()
        val uid = android.os.Process.myUid()
        val pid = android.os.Process.myPid()
        log("started uid=$uid pid=$pid")

        // Handed over by whoever spawned us (the app, or the Mac over USB). Any
        // app on the phone can reach this loopback port, so without this the
        // shell uid would be a gift to whoever asked first.
        val secret = System.getenv("BRIDGE_SECRET")?.takeIf { it.isNotBlank() }
            ?: run { log("refusing to start without BRIDGE_SECRET"); System.exit(2); return }

        // reuseAddress: a successor spawned by install() binds this port a
        // second after its predecessor exits, before the kernel has let go.
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 4)
        }
        while (true) {
            val s = server.accept()
            Thread {
                runCatching {
                    s.soTimeout = 3000
                    val auth = readLine(s.getInputStream())
                    val presented = auth?.takeIf { it.startsWith("AUTH ") }?.substring(5)?.trim().orEmpty()
                    if (!java.security.MessageDigest.isEqual(secret.toByteArray(), presented.toByteArray())) {
                        s.getOutputStream().write("ERR unauthorized\n".toByteArray())
                        log("refused a connection: bad or missing secret")
                        return@runCatching
                    }
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
            "AUDIO" -> audio(s, rest.trim())
            "CTRL" -> control(s, rest.trim())
            "CLIP" -> clip(s)
            "PUSH" -> push(s, rest)
            "LIST" -> list(s, rest.trim())
            "PULL" -> pull(s, rest.trim())
            "INSTALL" -> install(s, rest.trim().toLongOrNull() ?: 0)
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
            "scid=$scid", "tunnel_forward=true", "audio=true", "audio_codec=aac",
            "send_dummy_byte=false", "log_level=info"
        )
        args += options.split(' ').filter { it.isNotBlank() }
        val process = ProcessBuilder(args).apply {
            environment()["CLASSPATH"] = jar
            redirectErrorStream(true)
            redirectOutput(File("/data/local/tmp/scrcpy-server.log"))
        }.start()
        log("session $scid: started scrcpy-server (${args.drop(4).joinToString(" ")})")

        // The server accepts the sockets in a fixed order: video, audio, control.
        val videoSock = connectLocal("scrcpy_$scid") ?: run {
            process.destroy(); reply(s, "ERR scrcpy-server did not start"); return
        }
        val audioSock = connectLocal("scrcpy_$scid") ?: run {
            videoSock.close(); process.destroy(); reply(s, "ERR audio socket"); return
        }
        val controlSock = connectLocal("scrcpy_$scid") ?: run {
            videoSock.close(); audioSock.close(); process.destroy(); reply(s, "ERR control socket"); return
        }
        sessions[scid] = Session(scid, process, audioSock, controlSock)
        reply(s, "OK scid=$scid")
        try {
            pump(videoSock.inputStream, s.getOutputStream())
        } finally {
            log("session $scid: video ended")
            sessions.remove(scid)
            runCatching { videoSock.close() }
            runCatching { audioSock.close() }
            runCatching { controlSock.close() }
            process.destroy()
        }
    }

    /** Pipes a running session's audio stream to [s]. */
    private fun audio(s: Socket, scid: String) {
        val session = sessions[scid] ?: run { reply(s, "ERR no such session"); return }
        reply(s, "OK")
        pump(session.audio.inputStream, s.getOutputStream())
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

    /**
     * Receives a dropped file and saves it to the phone's Download folder. The
     * shell user is in sdcard_rw, so it may write there; a media-scan broadcast
     * afterwards makes the file show up in Files and the gallery straight away.
     */
    private fun push(s: Socket, args: String) {
        val size = args.substringBefore(' ').trim().toLongOrNull() ?: 0
        val rawName = args.substringAfter(' ', "").trim()
        // Keep the name a plain file name: never let it escape the folder.
        val name = File(rawName).name.ifEmpty { "bridge-file" }
        if (size <= 0 || size > MAX_PUSH) { reply(s, "ERR size"); return }
        val target = freeName(File("/sdcard/Download"), name)
        runCatching {
            target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var left = size
                val input = s.getInputStream()
                while (left > 0) {
                    val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (r < 0) error("short upload")
                    out.write(buf, 0, r); left -= r
                }
            }
        }.onFailure { reply(s, "ERR ${it.message}"); target.delete(); return }
        runCatching {
            ProcessBuilder(
                "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                "-d", "file://${target.absolutePath}"
            ).start().waitFor()
        }
        log("pushed ${target.absolutePath} (${target.length()} bytes)")
        reply(s, "OK saved to Download/${target.name}")
    }

    /** "photo.jpg" → "photo (1).jpg" … so a drop never overwrites what's there. */
    private fun freeName(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val (stem, ext) = name.substring(0, dot) to name.substring(dot)
        var i = 1
        while (f.exists()) f = File(dir, "$stem (${i++})$ext")
        return f
    }

    /** Shared storage only: what the Files app shows. Never the app-private or system dirs. */
    private const val STORAGE_ROOT = "/storage/emulated/0"

    private fun inStorage(path: String): File? {
        val raw = path.ifEmpty { STORAGE_ROOT }.replaceFirst(Regex("^/sdcard"), STORAGE_ROOT)
        val f = runCatching { File(raw).canonicalFile }.getOrNull() ?: return null
        return f.takeIf { it.path == STORAGE_ROOT || it.path.startsWith("$STORAGE_ROOT/") }
    }

    /**
     * LIST <dir>: "OK", then one line per entry, "d|f <tab> size <tab>
     * modified-ms <tab> name", then an empty line. Names with a tab or
     * newline in them are skipped (they'd break the framing, and are rare).
     */
    private fun list(s: Socket, path: String) {
        val dir = inStorage(path)?.takeIf { it.isDirectory }
            ?: run { reply(s, "ERR not a folder in shared storage"); return }
        val entries = dir.listFiles() ?: run { reply(s, "ERR can't read ${dir.path}"); return }
        val out = StringBuilder("OK ${dir.path}\n")
        entries.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).forEach { f ->
            if ('\t' in f.name || '\n' in f.name) return@forEach
            out.append(if (f.isDirectory) 'd' else 'f').append('\t')
                .append(if (f.isDirectory) 0 else f.length()).append('\t')
                .append(f.lastModified()).append('\t').append(f.name).append('\n')
        }
        out.append('\n')
        s.getOutputStream().write(out.toString().toByteArray()); s.getOutputStream().flush()
    }

    /** PULL <file>: "OK <size>", then exactly that many bytes. */
    private fun pull(s: Socket, path: String) {
        val f = inStorage(path)?.takeIf { it.isFile && it.canRead() }
            ?: run { reply(s, "ERR not a readable file in shared storage"); return }
        val size = f.length()
        reply(s, "OK $size")
        runCatching {
            f.inputStream().use { input ->
                val buf = ByteArray(128 * 1024)
                val out = s.getOutputStream()
                var left = size
                while (left > 0) {
                    val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (r < 0) break
                    out.write(buf, 0, r); left -= r
                }
                out.flush()
            }
        }
        log("sent ${f.path} ($size bytes)")
    }

    private const val MAX_APK = 200L * 1024 * 1024
    private const val MAX_PUSH = 4L * 1024 * 1024 * 1024

    private fun install(s: Socket, size: Long) {
        if (size <= 0 || size > MAX_APK) { reply(s, "ERR size"); return }
        val apk = File("/data/local/tmp/bridge-update.apk")
        apk.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            var left = size
            val input = s.getInputStream()
            while (left > 0) {
                val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r < 0) { reply(s, "ERR short upload"); return }
                out.write(buf, 0, r); left -= r
            }
        }
        log("installing ${apk.length()} bytes")
        // Reply first: `pm install` kills the app process, and that process is the
        // one relaying this answer to the Mac, so a later reply would never arrive.
        // The app restarts itself through MY_PACKAGE_REPLACED (see BootReceiver).
        reply(s, "OK received ${apk.length()} bytes, installing")
        // --pkg: pm refuses anything that isn't Bridge, and Android itself
        // refuses a Bridge APK with a different signature. So this can only
        // ever update Bridge with a real Bridge build, never install something else.
        val p = ProcessBuilder("pm", "install", "-r", "--pkg", "com.bonevane.bridge", apk.absolutePath)
            .redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText().trim()
        val code = p.waitFor()
        apk.delete()
        log(if (code == 0) "install ok: $output" else "install failed: $output")
        if (code == 0) {
            // This process still runs the *old* code from an install directory
            // that no longer exists. Spawn a successor from the new APK before
            // leaving: we are already shell, so no adb bootstrap is needed, and
            // without this an update on cellular left the phone with no helper
            // until it next saw Wi-Fi or a cable. Same detached-launch recipe
            // as DaemonManager; the secret is passed on unchanged.
            val newApk = runCatching {
                ProcessBuilder("pm", "path", "com.bonevane.bridge").start()
                    .inputStream.bufferedReader().readLine()?.substringAfter("package:")?.trim()
            }.getOrNull()
            val secret = System.getenv("BRIDGE_SECRET").orEmpty()
            if (!newApk.isNullOrEmpty()) {
                log("starting the new helper from $newApk")
                val cmd = "(BRIDGE_SECRET=$secret CLASSPATH=$newApk exec setsid app_process / " +
                    "com.bonevane.bridge.Daemon </dev/null >/data/local/tmp/bridge-daemon.out 2>&1) &"
                // A moment for the port to free up: the successor binds 5577 too.
                runCatching { ProcessBuilder("sh", "-c", "sleep 1; $cmd").start() }
            }
            log("exiting; the new helper takes over")
            System.exit(0)
        }
    }

    /**
     * A control-only scrcpy-server (video=false audio=false control=true) whose
     * single control socket is relayed to [s]. scrcpy's clipboard autosync then
     * works with no encoder running.
     *
     * Each CLIP gets its own server, ended when its socket closes. It used to be
     * "a new CLIP replaces the old", which with two users (the phone's own
     * clipboard watcher and a Mac's background sync) meant they kicked each
     * other out forever: a fresh JVM every few seconds, each one switching the
     * screen on (see power_on below).
     */
    private fun clip(s: Socket) {
        val jar = findJar() ?: run { reply(s, "ERR scrcpy jar not found"); return }
        // Must fit a *signed* 32-bit int: scrcpy does Integer.parseInt(scid, 16).
        val scid = "%08x".format((Math.random() * 0x7fffffff).toInt())
        val args = listOf(
            "app_process", "/", "com.genymobile.scrcpy.Server", SCRCPY_VERSION,
            "scid=$scid", "tunnel_forward=true", "video=false", "audio=false",
            // With video off the control socket is the *first* socket, and scrcpy
            // would prefix it with its 64-byte device-name header; we don't want that.
            "control=true", "send_dummy_byte=false", "send_device_meta=false",
            // scrcpy turns the screen on when its controller starts (power_on
            // defaults to true). Right for a mirroring session; for a silent
            // clipboard channel it lit the phone up in your pocket at every start.
            "power_on=false",
            "cleanup=false", "log_level=info"
        )
        val process = ProcessBuilder(args).apply {
            environment()["CLASSPATH"] = jar
            redirectErrorStream(true); redirectOutput(File("/data/local/tmp/clip.log"))
        }.start()
        log("clip channel $scid started")
        val ctl = connectLocal("scrcpy_$scid") ?: run { process.destroy(); reply(s, "ERR clip server"); return }
        reply(s, "OK")
        val t = Thread { pump(ctl.inputStream, s.getOutputStream()) }
        t.start()
        pump(s.getInputStream(), ctl.outputStream)
        runCatching { ctl.close() }; t.join(); process.destroy()
        log("clip channel ended")
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
