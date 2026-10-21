package com.bonevane.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds, downloads and installs new versions of the phone app from the
 * project's GitHub releases.
 *
 *  - Checks once a day (if allowed), and whenever asked. Nothing is
 *    downloaded or installed until someone taps Update, here or on a computer.
 *  - Downloads resume: a dropped connection retries by itself, a pause or an
 *    app restart picks up from the bytes already on disk.
 *  - Before installing, the APK must match the release's SHA256SUMS, be
 *    Bridge, be newer, and be signed with the same key as the installed app.
 *  - Installs through the helper when it's running (silent: it's shell), else
 *    through Android's installer (one confirmation).
 *  - Around an install, the tunnel, Bluetooth and the rest are stopped
 *    cleanly, and brought back if the install doesn't happen. After it does,
 *    the new version starts itself (BootReceiver) in the mode it was in.
 */
object Updater {
    private const val API = "https://api.github.com/repos/Bonevane/Bridge/releases/latest"
    private const val DAY = 24L * 60 * 60 * 1000
    private const val CHANNEL_ID = "updates"
    private const val NOTIFY_AVAILABLE = 30
    private const val NOTIFY_ACTION = 31

    data class Release(
        val version: String,
        val notes: String,
        val apkUrl: String,
        val apkName: String,
        val apkSize: Long,
        val sumsUrl: String?,
    )

    sealed class State {
        object Idle : State()
        object Checking : State()
        data class UpToDate(val at: Long) : State()
        data class Available(val release: Release) : State()
        data class Downloading(val release: Release, val done: Long, val total: Long, val perSecond: Long) : State()
        /** Stopped part-way: by the user, or after the connection kept failing ([reason]). */
        data class Paused(val release: Release, val done: Long, val total: Long, val reason: String?) : State()
        data class Verifying(val release: Release) : State()
        /** Bridge isn't allowed to install apps yet; the file is ready. */
        data class NeedsPermission(val release: Release) : State()
        /** Android's installer is waiting for the user to confirm. */
        data class Confirming(val release: Release) : State()
        data class Installing(val release: Release) : State()
        data class Failed(val message: String, val release: Release?) : State()
    }

    @Volatile var state: State = State.Idle
        private set

    /** 0 = run, 1 = pause, 2 = cancel: read by the download loop between reads. */
    private val control = AtomicInteger(0)
    @Volatile private var working = false
    private var lastProgressSent = 0L

    private fun set(ctx: Context, s: State, tellComputers: Boolean = true) {
        state = s
        TunnelState.notifyListeners()
        if (tellComputers) TunnelService.current?.ble?.sendStatus()
    }

    fun currentVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()

    // MARK: - Checking

    /**
     * [manual]: someone asked (a button, a computer). Automatic checks happen
     * at most once a day, never while GitHub has asked us to back off, and
     * never interrupt a download or install already under way.
     */
    fun check(ctx: Context, manual: Boolean) {
        val now = System.currentTimeMillis()
        if (!manual) {
            if (!Prefs.updateChecks(ctx)) return
            if (now - Prefs.updateCheckedAt(ctx) < DAY) return
            if (now < Prefs.updateRetryAfter(ctx)) return
        }
        if (working) return
        when (state) {
            is State.Downloading, is State.Verifying, is State.Installing, is State.Confirming -> return
            else -> {}
        }
        working = true
        val before = state
        if (manual) set(ctx, State.Checking, tellComputers = false)
        Thread {
            try {
                val release = fetchLatest(ctx)
                Prefs.setUpdateCheckedAt(ctx, System.currentTimeMillis())
                val mine = currentVersion(ctx)
                if (release == null || !isNewer(release.version, mine)) {
                    set(ctx, State.UpToDate(System.currentTimeMillis()))
                } else {
                    // A download from before (paused, or the app was closed
                    // mid-way) carries on from where it stopped.
                    val part = partFile(ctx, release)
                    val next = if (part.exists() && part.length() in 1 until release.apkSize)
                        State.Paused(release, part.length(), release.apkSize, null)
                    else State.Available(release)
                    set(ctx, next)
                    if (!manual && Prefs.updateAnnounced(ctx) != release.version) {
                        Prefs.setUpdateAnnounced(ctx, release.version)
                        announce(ctx, release)
                    }
                }
            } catch (e: RateLimited) {
                Prefs.setUpdateRetryAfter(ctx, e.until)
                val minutes = ((e.until - System.currentTimeMillis()) / 60_000).coerceAtLeast(1)
                set(ctx, if (manual) State.Failed("GitHub is limiting checks right now. Try again in $minutes min.", null) else before)
            } catch (e: Exception) {
                TunnelState.log("Update check failed: ${e.message}")
                // An automatic check that fails (offline, say) changes nothing
                // on screen; it simply tries again later.
                set(ctx, if (manual) State.Failed("Couldn't reach GitHub. Check the connection and try again.", null) else before)
            } finally {
                working = false
            }
        }.start()
    }

    private class RateLimited(val until: Long) : Exception()

    private fun fetchLatest(ctx: Context): Release? {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Bridge-Android/${currentVersion(ctx)}")
        }
        try {
            val code = conn.responseCode
            if (code == 403 || code == 429) {
                val reset = conn.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()?.times(1000)
                throw RateLimited(reset ?: (System.currentTimeMillis() + 60 * 60_000))
            }
            if (code == 404) return null                        // no releases at all
            if (code != 200) throw IOException("GitHub answered $code")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val version = json.optString("tag_name").removePrefix("v").trim()
            val assets = json.optJSONArray("assets") ?: return null
            var apk: JSONObject? = null
            var sums: JSONObject? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                if (name.endsWith(".apk")) apk = a
                if (name.startsWith("SHA256SUMS")) sums = a
            }
            apk ?: return null                                   // a release without a phone app
            return Release(
                version = version,
                notes = json.optString("body").trim(),
                apkUrl = apk.getString("browser_download_url"),
                apkName = apk.getString("name"),
                apkSize = apk.optLong("size"),
                sumsUrl = sums?.optString("browser_download_url"),
            )
        } finally {
            conn.disconnect()
        }
    }

    /** "0.3.10" > "0.3.9"; anything unparsable counts as not newer. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.split('.', '-').map { it.toIntOrNull() ?: -1 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    // MARK: - Downloading

    private fun dir(ctx: Context) = File(ctx.cacheDir, "updates").apply { mkdirs() }
    private fun partFile(ctx: Context, r: Release) = File(dir(ctx), "Bridge-${r.version}.apk.part")
    private fun apkFile(ctx: Context, r: Release) = File(dir(ctx), "Bridge-${r.version}.apk")

    /** The release on screen right now, whatever the state. */
    fun release(): Release? = when (val s = state) {
        is State.Available -> s.release
        is State.Downloading -> s.release
        is State.Paused -> s.release
        is State.Verifying -> s.release
        is State.NeedsPermission -> s.release
        is State.Confirming -> s.release
        is State.Installing -> s.release
        is State.Failed -> s.release
        else -> null
    }

    /** Update: download (or resume), verify, install. One tap does all three. */
    fun start(ctx: Context) {
        val r = release() ?: run { check(ctx, manual = true); return }
        if (working) return
        when (state) {
            is State.Downloading, is State.Verifying, is State.Installing, is State.Confirming -> return
            else -> {}
        }
        working = true
        control.set(0)
        Thread {
            try {
                val file = download(ctx, r) ?: return@Thread
                if (!verify(ctx, r, file)) return@Thread
                install(ctx, r, file)
            } finally {
                working = false
            }
        }.start()
    }

    fun pause(ctx: Context) { if (state is State.Downloading) control.set(1) }

    /** Stops the download and throws away what was fetched. */
    fun cancel(ctx: Context) {
        if (state is State.Downloading) { control.set(2); return }
        val r = release() ?: return
        partFile(ctx, r).delete()
        apkFile(ctx, r).delete()
        set(ctx, State.Available(r))
    }

    /** Returns the finished file, or null if paused, cancelled or failed (state says which). */
    private fun download(ctx: Context, r: Release): File? {
        val done = apkFile(ctx, r)
        if (done.exists() && done.length() == r.apkSize) return done
        val part = partFile(ctx, r)
        val have = if (part.exists()) part.length() else 0L
        val need = r.apkSize - have
        if (dir(ctx).usableSpace < need + 50L * 1024 * 1024) {
            set(ctx, State.Failed("Not enough free space: the update needs ${(need shr 20) + 50} MB.", r))
            return null
        }
        val waits = longArrayOf(2_000, 5_000, 10_000, 20_000, 30_000)
        var attempt = 0
        while (true) {
            try {
                fetchInto(ctx, r, part)
                when (control.get()) {
                    1 -> { set(ctx, State.Paused(r, part.length(), r.apkSize, null)); return null }
                    2 -> { part.delete(); set(ctx, State.Available(r)); return null }
                }
                if (part.length() != r.apkSize) throw IOException("got ${part.length()} of ${r.apkSize} bytes")
                done.delete()
                if (!part.renameTo(done)) throw IOException("couldn't finish writing the file")
                return done
            } catch (e: IOException) {
                if (control.get() == 2) { part.delete(); set(ctx, State.Available(r)); return null }
                if (control.get() == 1) { set(ctx, State.Paused(r, part.length(), r.apkSize, null)); return null }
                TunnelState.log("Update download interrupted: ${e.message}")
                if (attempt >= waits.size) {
                    set(ctx, State.Paused(r, part.length(), r.apkSize,
                        "The connection kept dropping. Tap Resume to carry on from ${(part.length() shr 20)} MB."))
                    return null
                }
                set(ctx, State.Paused(r, part.length(), r.apkSize, "Connection lost. Retrying…"))
                Thread.sleep(waits[attempt++])
            }
        }
    }

    /**
     * One pass: asks for the bytes we don't have (Range), follows GitHub's
     * redirect to its file host by hand so the Range header survives, and
     * appends. A server that ignores Range (200 instead of 206) means
     * starting from zero, so the file is truncated rather than corrupted.
     */
    private fun fetchInto(ctx: Context, r: Release, part: File) {
        var url = URL(r.apkUrl)
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "Bridge-Android/${currentVersion(ctx)}")
                if (part.length() > 0) setRequestProperty("Range", "bytes=${part.length()}-")
            }
            val code = conn.responseCode
            if (code in 300..399 && hops < 6) {
                val next = conn.getHeaderField("Location") ?: throw IOException("redirect without a target")
                url = URL(url, next); hops++; conn.disconnect(); continue
            }
            if (code == 416) {                       // asked past the end
                conn.disconnect()
                if (part.length() == r.apkSize) return
                part.delete(); throw IOException("the file on the server changed; starting again")
            }
            if (code != 200 && code != 206) { conn.disconnect(); throw IOException("server answered $code") }
            break
        }
        try {
            val append = conn.responseCode == 206
            RandomAccessFile(part, "rw").use { out ->
                if (!append) out.setLength(0)
                out.seek(out.length())
                val input = conn.inputStream
                val buf = ByteArray(64 * 1024)
                var windowStart = System.currentTimeMillis()
                var windowBytes = 0L
                var rate = 0L
                set(ctx, State.Downloading(r, out.length(), r.apkSize, 0))
                while (true) {
                    if (control.get() != 0) return
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    windowBytes += n
                    val now = System.currentTimeMillis()
                    if (now - windowStart >= 500) {
                        rate = windowBytes * 1000 / (now - windowStart)
                        windowStart = now; windowBytes = 0
                        // The screen every half second; the computers less often.
                        val tell = now - lastProgressSent > 2_500
                        if (tell) lastProgressSent = now
                        set(ctx, State.Downloading(r, out.length(), r.apkSize, rate), tellComputers = tell)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    // MARK: - Verifying

    private fun verify(ctx: Context, r: Release, file: File): Boolean {
        set(ctx, State.Verifying(r))
        // 1. The checksum the release lists for this file.
        if (r.sumsUrl != null) {
            val expected = runCatching {
                val conn = URL(r.sumsUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000; conn.readTimeout = 15_000
                conn.setRequestProperty("User-Agent", "Bridge-Android")
                conn.inputStream.bufferedReader().readLines()
                    .firstOrNull { it.trim().endsWith(r.apkName) }?.trim()?.substringBefore(' ')?.lowercase()
            }.getOrNull()
            if (expected == null) {
                set(ctx, State.Failed("Couldn't fetch the release's checksum to check the download. Try again.", r))
                return false
            }
            val actual = sha256(file)
            if (actual != expected) {
                file.delete()
                set(ctx, State.Failed("The download was damaged on the way (its checksum doesn't match). Try again.", r))
                return false
            }
        } else {
            TunnelState.log("Update: this release has no SHA256SUMS; relying on the signature check")
        }
        // 2. It's Bridge, it's newer, and it's signed by the same key as this app.
        val pm = ctx.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = runCatching {
            if (Build.VERSION.SDK_INT >= 33) pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
            else @Suppress("DEPRECATION") pm.getPackageArchiveInfo(file.path, flags)
        }.getOrNull()
        val mine = runCatching {
            if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            else @Suppress("DEPRECATION") pm.getPackageInfo(ctx.packageName, flags)
        }.getOrNull()
        val problem = when {
            archive == null -> "The download isn't a valid app file."
            archive.packageName != ctx.packageName -> "The download isn't Bridge."
            mine != null && archive.longVersionCode <= mine.longVersionCode -> "The download isn't newer than what's installed."
            mine?.signingInfo != null && archive.signingInfo != null &&
                signers(archive.signingInfo!!) != signers(mine.signingInfo!!) ->
                "The update is signed with a different key than the installed app (a test build, perhaps). " +
                    "Android won't install it over this one; uninstall Bridge and install the new version instead."
            else -> null
        }
        if (problem != null) {
            set(ctx, State.Failed(problem, r))
            return false
        }
        return true
    }

    private fun signers(info: android.content.pm.SigningInfo): Set<String> =
        (if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory)
            .map { sha256(it.toByteArray()) }.toSet()

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // MARK: - Installing

    private fun install(ctx: Context, r: Release, file: File) {
        if (TunnelState.mirroring || TunnelState.openStreams.get() > 0) {
            set(ctx, State.Failed("A computer is mirroring or browsing files. Installing restarts Bridge, so " +
                "finish that first, then tap Install.", r))
            return
        }
        // Silent through the helper (it's shell) when it's there; it also
        // starts its own successor from the new version, so "keep ready"
        // stays ready across the update.
        if (DaemonManager.probe(DaemonManager.DAEMON_PORT) != null) {
            installViaHelper(ctx, r, file)
            return
        }
        installViaAndroid(ctx, r, file)
    }

    private fun installViaHelper(ctx: Context, r: Release, file: File) {
        set(ctx, State.Installing(r))
        Prefs.setResumeAfterUpdate(ctx, true)
        // Let the computers hear "installing" before Bluetooth goes, so they
        // show the phone as updating instead of as gone.
        Thread.sleep(1_500)
        TunnelService.current?.prepareForUpdate()
        val reply = runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", DaemonManager.DAEMON_PORT), 2_000)
                s.soTimeout = 60_000
                val out = s.getOutputStream()
                out.write((Pairing.authLine(ctx) + "\nINSTALL ${file.length()}\n").toByteArray())
                file.inputStream().use { it.copyTo(out, 256 * 1024) }
                out.flush()
                val input = s.getInputStream().bufferedReader()
                input.readLine()                           // hello
                input.readLine().orEmpty()
            }
        }.getOrElse { "ERR ${it.message}" }
        TunnelState.log("Update via the helper: $reply")
        if (reply.startsWith("OK")) {
            // From here `pm install` replaces this app and Android ends this
            // process, so success looks like this loop never finishing. A
            // failure the helper reports ends it early; silence for two
            // minutes means it didn't happen either.
            var why = "the helper didn't say why"
            for (i in 1..24) {
                Thread.sleep(5_000)
                val last = lastHelperInstall(ctx) ?: continue
                if (last.startsWith("failed")) { why = last.removePrefix("failed:").trim(); break }
            }
            failAndRestore(ctx, r, "The update didn't install ($why).")
        } else {
            failAndRestore(ctx, r, "The helper couldn't install the update (${reply.removePrefix("ERR ").trim()}).")
        }
    }

    /** The helper's account of its last `pm install`, e.g. "failed: INSTALL_FAILED_…". */
    private fun lastHelperInstall(ctx: Context): String? = runCatching {
        Socket("127.0.0.1", DaemonManager.DAEMON_PORT).use { s ->
            s.soTimeout = 3_000
            s.getOutputStream().write((Pairing.authLine(ctx) + "\nLASTINSTALL\n").toByteArray())
            val input = s.getInputStream().bufferedReader()
            input.readLine()
            input.readLine()?.removePrefix("OK ")?.trim()?.takeIf { it.isNotEmpty() }
        }
    }.getOrNull()

    private fun installViaAndroid(ctx: Context, r: Release, file: File) {
        val pm = ctx.packageManager
        if (!pm.canRequestPackageInstalls()) {
            set(ctx, State.NeedsPermission(r))
            notifyAction(ctx, "Allow Bridge to install its update",
                "Bridge ${r.version} is downloaded. Allow Bridge to install apps, then tap Install.",
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:${ctx.packageName}")))
            return
        }
        set(ctx, State.Confirming(r))
        try {
            val installer = pm.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(ctx.packageName)
                setSize(file.length())
                // Once Bridge installed itself before, Android lets later
                // self-updates through without asking (Android 12+).
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out, 256 * 1024) }
                    session.fsync(out)
                }
                Prefs.setResumeAfterUpdate(ctx, true)
                val callback = PendingIntent.getBroadcast(
                    ctx, id, Intent(ctx, InstallResultReceiver::class.java).setPackage(ctx.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
            }
        } catch (e: Exception) {
            failAndRestore(ctx, r, "Android's installer refused the update: ${e.message}")
        }
    }

    /** Android's installer reporting back (see [installViaAndroid]). */
    fun onInstallResult(ctx: Context, intent: Intent) {
        val r = release()
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Android only lets a visible app open the confirmation; from a
                // computer, with the phone in a pocket, it waits in a notification.
                val shown = MainActivity.visible && runCatching { ctx.startActivity(confirm) }.isSuccess
                if (!shown) notifyAction(ctx, "Tap to finish updating Bridge",
                    "Bridge ${r?.version.orEmpty()} is ready. Android needs you to confirm the install.", confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> TunnelState.log("Update installed")
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Prefs.setResumeAfterUpdate(ctx, false)
                r?.let { set(ctx, State.Available(it)) }
                TunnelState.log("Update cancelled on the phone")
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                failAndRestore(ctx, r, "Android couldn't install the update: $msg")
            }
        }
    }

    private fun failAndRestore(ctx: Context, r: Release?, message: String) {
        Prefs.setResumeAfterUpdate(ctx, false)
        TunnelState.log(message)
        TunnelService.restoreAfterFailedUpdate(ctx)
        set(ctx, State.Failed(message, r))
    }

    // MARK: - Housekeeping

    /** Deletes downloads for versions already installed (or older). */
    fun cleanUp(ctx: Context) {
        val mine = currentVersion(ctx)
        dir(ctx).listFiles()?.forEach { f ->
            val v = f.name.removePrefix("Bridge-").substringBefore(".apk")
            if (!isNewer(v, mine)) f.delete()
        }
    }

    /** For the status line: what the computers show about the phone's update. */
    fun statusCode(): String = when (val s = state) {
        is State.Available -> "avail:${s.release.version}"
        is State.Downloading -> "dl:${s.release.version}:${if (s.total > 0) s.done * 100 / s.total else 0}"
        is State.Paused -> "paused:${s.release.version}:${if (s.total > 0) s.done * 100 / s.total else 0}"
        is State.Verifying -> "verify:${s.release.version}"
        is State.NeedsPermission -> "perm:${s.release.version}"
        is State.Confirming -> "confirm:${s.release.version}"
        is State.Installing -> "installing:${s.release.version}"
        is State.Failed -> "failed:${s.release?.version.orEmpty()}"
        else -> "none"
    }

    // MARK: - Notifications

    private fun channel(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Updates", NotificationManager.IMPORTANCE_LOW).apply {
                description = "When a new version of Bridge is out"
            }
        )
        return nm
    }

    private fun announce(ctx: Context, r: Release) {
        val open = PendingIntent.getActivity(ctx, NOTIFY_AVAILABLE, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle("Bridge ${r.version} is available")
            .setContentText("Open Bridge to see what's new and update.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { channel(ctx).notify(NOTIFY_AVAILABLE, n) }
    }

    private fun notifyAction(ctx: Context, title: String, text: String, target: Intent) {
        val pi = PendingIntent.getActivity(ctx, NOTIFY_ACTION, target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { channel(ctx).notify(NOTIFY_ACTION, n) }
    }
}

/** Receives Android's installer results for [Updater]. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Updater.onInstallResult(context, intent)
}
