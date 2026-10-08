package com.bonevane.bridge

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the phone is playing, for the computers' Now Playing card, and the
 * controls back. Any app with a media session (Spotify, YouTube, podcasts…).
 *
 * Reading other apps' media sessions needs no extra permission for a
 * notification listener: Bridge already is one. So this works with USB
 * debugging off, like notifications.
 *
 * Sent as one tab-separated line of key=value pairs (titles have spaces):
 *   state=playing  app=Spotify  pkg=…  title=…  artist=…  album=…
 *   pos=<ms>  dur=<ms>  at=<epoch ms when pos was true>  art=<key>
 * "state=none" when nothing is playing. The receiver moves the position
 * along by itself between updates, so nothing is sent while a song plays.
 * Album art goes separately, once per track, as "<key>\t<base64 JPEG>".
 */
object MediaRelay {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val artListeners = CopyOnWriteArrayList<(String) -> Unit>()

    private var manager: MediaSessionManager? = null
    private var component: ComponentName? = null
    private var controller: MediaController? = null
    @Volatile private var lastLine = "state=none"
    @Volatile private var lastArt: String? = null   // "key\tbase64"
    private var lastArtKey: String? = null

    fun subscribe(line: (String) -> Unit, art: (String) -> Unit) {
        listeners.add(line); artListeners.add(art)
    }

    fun unsubscribe(line: (String) -> Unit, art: (String) -> Unit) {
        listeners.remove(line); artListeners.remove(art)
    }

    /** The current state, for a computer that has just linked. */
    fun snapshot(): Pair<String, String?> = lastLine to lastArt

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        pick(sessions.orEmpty())
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() {
            controller = null
            manager?.let { m -> component?.let { pick(runCatching { m.getActiveSessions(it) }.getOrDefault(emptyList())) } }
        }
    }

    fun start(ctx: Context) {
        main.post {
            val m = ctx.getSystemService(MediaSessionManager::class.java) ?: return@post
            val c = ComponentName(ctx, NotificationService::class.java)
            manager = m; component = c
            runCatching {
                // Started again on every rebind of the listener: one subscription, not one per bind.
                m.removeOnActiveSessionsChangedListener(sessionsChanged)
                m.addOnActiveSessionsChangedListener(sessionsChanged, c, main)
                pick(m.getActiveSessions(c))
            }.onFailure { TunnelState.log("Now Playing unavailable: ${it.message}") }
        }
    }

    /**
     * The listener was unbound. The sessions API keeps working as long as the
     * listener is *enabled*, so keep watching; only forget the handle if
     * reading the sessions now fails (access was actually revoked).
     */
    fun stop() {
        main.post {
            val ok = runCatching { manager?.getActiveSessions(component) }.isSuccess
            if (!ok) {
                runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsChanged) }
                controller?.unregisterCallback(callback)
                controller = null
                manager = null
                publish()   // "state=none": the computers drop the card instead of showing a stale one
            }
        }
    }

    /** The session to show: one that's playing, else the most recent one. */
    private fun pick(sessions: List<MediaController>) {
        val best = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, main)
        }
        publish()
    }

    private fun publish() {
        val c = controller
        val meta = c?.metadata
        val state = c?.playbackState
        val line = if (c == null || meta == null || state == null || state.state == PlaybackState.STATE_NONE ||
            state.state == PlaybackState.STATE_STOPPED) {
            "state=none"
        } else {
            fun clean(s: CharSequence?) = s?.toString()?.replace('\t', ' ')?.replace('\n', ' ')?.trim().orEmpty()
            val title = clean(meta.getText(MediaMetadata.METADATA_KEY_TITLE))
            val artist = clean(meta.getText(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))
            val album = clean(meta.getText(MediaMetadata.METADATA_KEY_ALBUM))
            val app = runCatching {
                val pm = AppContext.value.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
            }.getOrDefault(c.packageName)
            val playing = state.state == PlaybackState.STATE_PLAYING || state.state == PlaybackState.STATE_BUFFERING
            val artKey = sendArtIfNew(meta, title, artist, album)
            // Position as of a known moment; the computer runs the clock itself.
            val elapsedNow = android.os.SystemClock.elapsedRealtime()
            val pos = if (playing && state.lastPositionUpdateTime > 0)
                state.position + ((elapsedNow - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
            else state.position
            listOf(
                "state=${if (playing) "playing" else "paused"}",
                "app=${clean(app)}", "pkg=${c.packageName}",
                "title=$title", "artist=$artist", "album=$album",
                "pos=${pos.coerceAtLeast(0)}",
                "dur=${meta.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0)}",
                "at=${System.currentTimeMillis()}",
                "art=${artKey.orEmpty()}",
            ).joinToString("\t")
        }
        // Position alone moving on is not news: the computer extrapolates.
        if (line.substringBefore("\tpos=") == lastLine.substringBefore("\tpos=") &&
            line.contains("state=playing") == lastLine.contains("state=playing") &&
            kotlin.math.abs(posOf(line) - expectedPos(lastLine)) < 2_000) return
        lastLine = line
        listeners.forEach { runCatching { it(line) } }
    }

    private fun field(line: String, key: String) =
        line.split('\t').firstOrNull { it.startsWith("$key=") }?.substringAfter('=')

    private fun posOf(line: String) = field(line, "pos")?.toLongOrNull() ?: 0

    /** Where the last sent position should be by now, if it was playing. */
    private fun expectedPos(line: String): Long {
        val pos = posOf(line)
        if (!line.contains("state=playing")) return pos
        val at = field(line, "at")?.toLongOrNull() ?: return pos
        return pos + (System.currentTimeMillis() - at)
    }

    /** Album art, small (128 px JPEG, a few KB), once per track. Returns its key. */
    private fun sendArtIfNew(meta: MediaMetadata, title: String, artist: String, album: String): String? {
        val bitmap = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: return null
        val key = Integer.toHexString("$title|$artist|$album".hashCode())
        if (key == lastArtKey) return key
        val jpeg = runCatching {
            val scaled = Bitmap.createScaledBitmap(bitmap, 128, 128 * bitmap.height / bitmap.width.coerceAtLeast(1), true)
            java.io.ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        }.getOrNull() ?: return null
        lastArtKey = key
        val message = key + "\t" + android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
        lastArt = message
        artListeners.forEach { runCatching { it(message) } }
        return key
    }

    /**
     * "media play|pause|toggle|next|prev|seek <ms>" from a computer.
     *
     * If the player handle has gone (Android rebinds the notification
     * listener now and then, and the handle went with it), pick it up again
     * here rather than drop the button press: that silent drop was the
     * "controls do nothing" bug.
     */
    fun command(args: String) {
        main.post {
            if (controller == null) reacquire()
            val c = controller
            val t = c?.transportControls
            if (t == null) {
                TunnelState.log("Media: ${args.trim()} ignored, nothing is playing on the phone")
                publish()
                return@post
            }
            val words = args.trim().split(' ')
            runCatching {
                when (words.firstOrNull()) {
                    "play" -> t.play()
                    "pause" -> t.pause()
                    "toggle" -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
                    "next" -> t.skipToNext()
                    "prev" -> t.skipToPrevious()
                    "seek" -> words.getOrNull(1)?.toLongOrNull()?.let { t.seekTo(it) }
                }
            }.onSuccess { TunnelState.log("Media: ${words.firstOrNull()} sent to ${c.packageName}") }
                .onFailure { TunnelState.log("Media: ${words.firstOrNull()} failed: ${it.message}") }
        }
    }

    /** Finds the active players again, without needing the listener to be bound right now. */
    private fun reacquire() {
        val ctx = AppContext.value
        val m = manager ?: ctx.getSystemService(MediaSessionManager::class.java) ?: return
        val c = component ?: ComponentName(ctx, NotificationService::class.java)
        manager = m; component = c
        runCatching {
            m.removeOnActiveSessionsChangedListener(sessionsChanged)
            m.addOnActiveSessionsChangedListener(sessionsChanged, c, main)
            pick(m.getActiveSessions(c))
        }.onFailure { TunnelState.log("Media: couldn't find the players: ${it.message}") }
    }
}
