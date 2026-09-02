package wm.damage.phone.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import wm.damage.core.util.Log
import wm.damage.core.windows.music.Art
import wm.damage.core.windows.music.SpotifyNow

/**
 * What Spotify reports through its media session, as the player consumes it.
 *
 * [posMs] is the position Spotify last published and [posAtMs] is the
 * `SystemClock.elapsedRealtime()` instant it published it (that is the clock
 * `PlaybackState.getLastPositionUpdateTime()` uses, NOT wall clock), so the
 * live position while playing is
 * `posMs + (SystemClock.elapsedRealtime() - posAtMs)` — extrapolate against
 * that clock and nothing else.
 *
 * [artGray56] is 56×56 packed 4-bit gray, two pixels a byte, high nibble
 * first — the same packing the PC's `Art` produces, so the card draws Spotify
 * art and library art through one path. It is null when Spotify published no
 * bitmap (which is normal for the first metadata update of a track — Spotify
 * fills the art in a moment later). The array is reused between states while
 * the track is unchanged, so an identity comparison of two states behaves as
 * expected for "did anything change".
 */
data class SpotifyRemoteState(
    val present: Boolean = false,
    val playing: Boolean = false,
    val now: SpotifyNow? = null,
    val posMs: Long = 0,
    val posAtMs: Long = 0,
    val artGray56: ByteArray? = null,
)

/**
 * Spotify as the phone's fallback backend (`MUSIC.md` verdicts 3 / 20 / 25,
 * §3.10 and §7 "Spotify"). Spotify is not driven through any SDK or account:
 * it is read and controlled through the media session it already publishes,
 * which is what the one-time notification-access grant authorizes
 * ([MusicListener]). The automatic switch on PC loss and the deliberate
 * switchback are the player's decisions; this class only reports and commands.
 *
 * Threading: the session-change listener and the controller callback are
 * registered against a private [HandlerThread], so nothing here runs on the
 * app's main thread and the art packing cannot stall it. Every callback body
 * is wrapped — an exception must never reach the system's binder thread.
 * No timeouts anywhere: [coldStart] reports what the connection callbacks say
 * and nothing waits on a deadline.
 *
 * API reference used (Android SDK docs, API 31+):
 *   `MediaSessionManager.getActiveSessions(ComponentName)` — needs the
 *     notification-listener grant, throws `SecurityException` without it;
 *   `MediaSessionManager.addOnActiveSessionsChangedListener(l, comp, handler)`;
 *   `MediaController.registerCallback(cb, handler)`, `getTransportControls()`;
 *   `PlaybackState.getPosition()` / `getLastPositionUpdateTime()`
 *     (elapsedRealtime base) / `getState()`;
 *   `MediaMetadata.METADATA_KEY_TITLE|ARTIST|ALBUM_ARTIST|ALBUM|DURATION`,
 *     `METADATA_KEY_ART` / `METADATA_KEY_ALBUM_ART` (Bitmap);
 *   `MediaBrowser(Context, ComponentName, ConnectionCallback, Bundle)` and
 *     `getSessionToken()` for the cold start;
 *   `PackageManager.queryIntentServices(Intent, int)` to find Spotify's
 *     `android.media.browse.MediaBrowserService`.
 */
class SpotifyRemote(
    private val ctx: Context,
    private val onState: (SpotifyRemoteState) -> Unit,
) {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var msm: MediaSessionManager? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var controller: MediaController? = null
    private var controllerCb: MediaController.Callback? = null
    private var browser: MediaBrowser? = null
    private var browserHandler: Handler? = null

    @Volatile private var started = false
    /** Set while the grant is missing, so a polled [start] logs once, not endlessly. */
    @Volatile private var warnedNoGrant = false

    /** Art is packed once per track; "" means "not packed yet — try again". */
    private var artKey = ""
    private var art: ByteArray? = null

    // ------------------------------------------------------------------ lifecycle

    /**
     * Register for Spotify's media session. Never throws.
     *
     * With no notification-access grant this reports `present = false`, logs
     * why, and registers nothing — call [start] again once the grant is given
     * (the repeat is cheap and only logs when the answer changes).
     */
    fun start() {
        // The player's handler is NEVER called with our lock held (a callback
        // that reaches back in from another thread would otherwise stall).
        var already = false
        val sessions: List<MediaController>? = try {
            synchronized(this) {
                if (started) { already = true; null } else startLocked()
            }
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            synchronized(this) { cleanup() }
            null
        }
        if (already) Log.i(TAG, "start ignored — already watching media sessions")
        // No session list to work from: report what is attached now, which is
        // "no Spotify" on every failure path above.
        if (sessions == null) emit() else onSessions(sessions)
    }

    /** Registers everything and returns the first session list, or null when
     *  there is nothing to watch. Caller holds the lock. */
    private fun startLocked(): List<MediaController>? {
        if (!MusicListener.granted(ctx)) {
            if (!warnedNoGrant) {
                warnedNoGrant = true
                Log.w(TAG, "notification access not granted — Spotify's media session cannot be read; grant it in Settings → Notification access, then start again")
            }
            return null
        }
        warnedNoGrant = false
        val m = ctx.getSystemService(MediaSessionManager::class.java)
        if (m == null) {
            Log.e(TAG, "no MediaSessionManager on this device — Spotify cannot be used as a backend")
            return null
        }
        val t = HandlerThread("damage-spotify").apply { start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        msm = m
        val comp = MusicListener.component(ctx)
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { list -> onSessions(list) }
        return try {
            // Registered BEFORE the first query so a session appearing between
            // the two is not missed; attach() dedupes by session token.
            m.addOnActiveSessionsChangedListener(listener, comp, h)
            sessionsListener = listener
            started = true
            val sessions = m.getActiveSessions(comp)
            Log.i(TAG, "watching media sessions (${sessions.size} active)")
            sessions
        } catch (e: SecurityException) {
            Log.e(TAG, "media sessions refused — the notification-access grant is not in place: ${e.message}")
            cleanup()
            null
        } catch (e: Exception) {
            Log.e(TAG, "registering for media sessions failed", e)
            cleanup()
            null
        }
    }

    /** Unregister everything. Idempotent; safe to call without a [start]. No
     *  state is reported from here — the caller is tearing down. */
    @Synchronized
    fun stop() {
        cleanup()
        Log.i(TAG, "stopped")
    }

    /** Tear down whatever is registered. Caller holds the lock. */
    private fun cleanup() {
        sessionsListener?.let { l ->
            try {
                msm?.removeOnActiveSessionsChangedListener(l)
            } catch (e: Exception) {
                Log.w(TAG, "removing the session listener failed: ${e.message}")
            }
        }
        sessionsListener = null
        detachController()
        controller = null
        msm = null
        val b = browser
        val bh = browserHandler
        browser = null
        browserHandler = null
        if (b != null) {
            // disconnect on the thread that connected — MediaBrowser builds its
            // handler on the thread that constructs it.
            val run = Runnable {
                try {
                    b.disconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "media browser disconnect failed: ${e.message}")
                }
            }
            if (bh == null || !bh.post(run)) run.run()
        }
        thread?.let {
            try {
                it.quitSafely()
            } catch (e: Exception) {
                Log.w(TAG, "handler thread stop failed: ${e.message}")
            }
        }
        thread = null
        handler = null
        artKey = ""
        art = null
        started = false
    }

    // ------------------------------------------------------------------ sessions

    private fun onSessions(list: List<MediaController>?) {
        try {
            val spotify = list?.firstOrNull { it.packageName == PKG }
            if (spotify == null) {
                if (synchronized(this) { controller != null }) Log.i(TAG, "Spotify's media session went away")
                attach(null)
            } else {
                attach(spotify)
            }
        } catch (e: Exception) {
            Log.e(TAG, "handling a media-session change failed", e)
        }
    }

    private fun attach(c: MediaController?) {
        synchronized(this) { attachLocked(c) }
        // reported outside the lock — see start()
        emit()
    }

    /** Caller holds the lock. */
    private fun attachLocked(c: MediaController?) {
        if (c != null && c.sessionToken == controller?.sessionToken) return   // same session
        detachController()
        controller = c
        artKey = ""
        art = null
        if (c == null) return
        val cb = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) = safeEmit("playback state")
            override fun onMetadataChanged(metadata: MediaMetadata?) = safeEmit("metadata")
            override fun onSessionDestroyed() {
                try {
                    Log.i(TAG, "Spotify's media session was destroyed")
                    synchronized(this@SpotifyRemote) {
                        detachController()
                        controller = null
                        artKey = ""
                        art = null
                    }
                    emitAbsent()
                } catch (e: Exception) {
                    Log.e(TAG, "session-destroyed handling failed", e)
                }
            }
        }
        controllerCb = cb
        try {
            c.registerCallback(cb, handler ?: Handler(Looper.getMainLooper()))
            Log.i(TAG, "attached to Spotify's media session")
        } catch (e: Exception) {
            Log.e(TAG, "registering the Spotify controller callback failed — state will not follow Spotify", e)
            controllerCb = null
        }
    }

    /** Caller holds the lock. Leaves [controller] as it is. */
    private fun detachController() {
        val c = controller
        val cb = controllerCb
        if (c != null && cb != null) {
            try {
                c.unregisterCallback(cb)
            } catch (e: Exception) {
                Log.w(TAG, "unregistering the controller callback failed: ${e.message}")
            }
        }
        controllerCb = null
    }

    // ------------------------------------------------------------------ state

    private fun safeEmit(what: String) {
        try {
            emit()
        } catch (e: Exception) {
            Log.e(TAG, "reporting a $what change failed", e)
        }
    }

    private fun emitAbsent() = report(SpotifyRemoteState())

    private fun emit() {
        val c = synchronized(this) { controller } ?: run { emitAbsent(); return }
        val state = try {
            val md = c.metadata
            val ps = c.playbackState
            val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
            val artist = (md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty()
            val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
            val dur = (md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L)
            SpotifyRemoteState(
                present = true,
                playing = ps?.state == PlaybackState.STATE_PLAYING,
                now = SpotifyNow(title, artist, album, dur),
                posMs = (ps?.position ?: 0L).coerceAtLeast(0L),
                posAtMs = ps?.lastPositionUpdateTime ?: 0L,
                artGray56 = artFor(md, artist, title, album),
            )
        } catch (e: Exception) {
            Log.e(TAG, "reading Spotify's session state failed", e)
            SpotifyRemoteState(present = true)
        }
        report(state)
    }

    private fun report(s: SpotifyRemoteState) {
        try {
            onState(s)
        } catch (e: Exception) {
            Log.e(TAG, "the player's state handler failed", e)
        }
    }

    /**
     * The packed 56×56 art for the current track, or null when Spotify has not
     * published a bitmap yet. The key is committed ONLY once a bitmap is in
     * hand: Spotify sends the text metadata first and fills the art in a later
     * update, and a key committed on the empty first update would skip that
     * track's art for good (the same defect G2CC's MediaBridge fixed).
     */
    private fun artFor(md: MediaMetadata?, artist: String, title: String, album: String): ByteArray? {
        val key = "$artist|$title|$album"
        synchronized(this) {
            if (key == artKey && art != null) return art
        }
        val bmp = try {
            md?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        } catch (e: Exception) {
            Log.w(TAG, "reading the album-art bitmap failed: ${e.message}")
            null
        }
        if (bmp == null) {
            synchronized(this) { if (key != artKey) { artKey = ""; art = null } }
            return null
        }
        val packed = packArt(bmp)
        synchronized(this) {
            if (packed != null) { artKey = key; art = packed } else { artKey = ""; art = null }
        }
        return packed
    }

    /**
     * Bitmap → 56×56 packed 4-bit gray. Box-sampled (every source pixel in the
     * cell averaged — the `scale=…:flags=area` the PC's `Art` uses), alpha
     * premultiplied toward black because the panel is additive and transparent
     * means unlit (the rule `AndroidImages` already follows), then packed by
     * [Art.pack] so the nibble order and the level rule are the host's.
     */
    private fun packArt(bmp: Bitmap): ByteArray? {
        var src = bmp
        var owned: Bitmap? = null
        try {
            if (src.width <= 0 || src.height <= 0) {
                Log.w(TAG, "album art is ${src.width}×${src.height} — no art for this track")
                return null
            }
            // A HARDWARE bitmap refuses getPixels; copy it into memory first.
            if (src.config == Bitmap.Config.HARDWARE) {
                val copy = src.copy(Bitmap.Config.ARGB_8888, false)
                if (copy == null) {
                    Log.w(TAG, "album art is a hardware bitmap that will not copy — no art for this track")
                    return null
                }
                owned = copy
                src = copy
            }
            // Very large art is pre-reduced so the box pass stays cheap; the
            // reduction is still a downscale, so the box pass below is exact
            // enough for a 56 px cell.
            if (src.width.toLong() * src.height > MAX_SRC_PIXELS) {
                val side = PRESCALE_PX
                val scaled = Bitmap.createScaledBitmap(src, side, side, true)
                if (scaled !== src) {
                    owned?.recycle()
                    owned = scaled
                    src = scaled
                }
            }
            val sw = src.width
            val sh = src.height
            val px = IntArray(sw * sh)
            src.getPixels(px, 0, sw, 0, 0, sw, sh)
            val gray = ByteArray(ART_PX * ART_PX)
            for (y in 0 until ART_PX) {
                val y0 = y * sh / ART_PX
                val y1 = maxOf(y0 + 1, (y + 1) * sh / ART_PX)
                for (x in 0 until ART_PX) {
                    val x0 = x * sw / ART_PX
                    val x1 = maxOf(x0 + 1, (x + 1) * sw / ART_PX)
                    var sum = 0L
                    var n = 0
                    for (yy in y0 until minOf(y1, sh)) {
                        val row = yy * sw
                        for (xx in x0 until minOf(x1, sw)) {
                            val c = px[row + xx]
                            val a = (c ushr 24) and 0xFF
                            val lum = (((c ushr 16) and 0xFF) * 299 + ((c ushr 8) and 0xFF) * 587 +
                                (c and 0xFF) * 114) / 1000
                            sum += (lum * a / 255).toLong()
                            n++
                        }
                    }
                    gray[y * ART_PX + x] = (if (n > 0) (sum / n).toInt() else 0).toByte()
                }
            }
            return Art.pack(gray, ART_PX * ART_PX)
        } catch (e: Exception) {
            Log.w(TAG, "packing the album art failed: ${e.message}")
            return null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "album art too large to read (${src.width}×${src.height}) — no art for this track")
            return null
        } finally {
            // only ever a bitmap this method created; the metadata's own bitmap
            // belongs to the controller
            owned?.recycle()
        }
    }

    // ------------------------------------------------------------------ transport

    /** Resume Spotify. False (with a log) when no Spotify session is attached. */
    fun play(): Boolean = command("play") { it.play() }

    fun pause(): Boolean = command("pause") { it.pause() }

    fun next(): Boolean = command("next") { it.skipToNext() }

    fun prev(): Boolean = command("previous") { it.skipToPrevious() }

    /** Play/pause against the session's own current state. */
    fun toggle(): Boolean {
        val c = synchronized(this) { controller }
        if (c == null) {
            Log.w(TAG, "toggle refused — no Spotify media session (is Spotify running?)")
            return false
        }
        val playing = try {
            c.playbackState?.state == PlaybackState.STATE_PLAYING
        } catch (e: Exception) {
            Log.e(TAG, "reading the playback state for toggle failed", e)
            return false
        }
        return if (playing) pause() else play()
    }

    private fun command(what: String, action: (MediaController.TransportControls) -> Unit): Boolean {
        val c = synchronized(this) { controller }
        if (c == null) {
            Log.w(TAG, "$what refused — no Spotify media session (is Spotify running?)")
            return false
        }
        return try {
            action(c.transportControls)
            Log.i(TAG, "$what sent to Spotify")
            true
        } catch (e: Exception) {
            Log.e(TAG, "$what failed", e)
            false
        }
    }

    // ------------------------------------------------------------------ cold start

    /**
     * Start Spotify from cold (verdict 20: "Spotify can be started cold") by
     * connecting to its media browser service and playing through the session
     * that connect produces.
     *
     * Returns false — with a log naming which — when the package is not
     * installed, when it publishes no media browser service, or when the
     * connect is refused on the spot. Returns true when the connect was
     * dispatched: the outcome then arrives on the connection callbacks, which
     * log it. Nothing here waits on a deadline.
     */
    @Suppress("DEPRECATION")   // the (Intent, int) / (String, int) forms are the ones minSdk 31 has
    fun coldStart(): Boolean {
        val pm = ctx.packageManager
        val services = try {
            pm.queryIntentServices(Intent(BROWSER_ACTION).setPackage(PKG), 0)
        } catch (e: Exception) {
            Log.e(TAG, "looking up Spotify's media browser service failed", e)
            return false
        }
        val info = services.firstOrNull()?.serviceInfo
        if (info == null) {
            val installed = try {
                pm.getPackageInfo(PKG, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            } catch (e: Exception) {
                Log.w(TAG, "package check for $PKG failed: ${e.message}")
                false
            }
            if (installed) {
                Log.e(TAG, "Spotify is installed but publishes no $BROWSER_ACTION service — it cannot be started cold")
            } else {
                Log.e(TAG, "Spotify ($PKG) is not installed — it cannot be started cold")
            }
            return false
        }
        val comp = ComponentName(info.packageName, info.name)
        // The browser this callback belongs to, so a second cold start's
        // callback can never read the other one's token.
        val mine = java.util.concurrent.atomic.AtomicReference<MediaBrowser?>()
        val cb = object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                try {
                    val b = mine.get()
                    if (b == null) {
                        Log.w(TAG, "media browser connected before it was recorded — nothing started")
                        return
                    }
                    if (synchronized(this@SpotifyRemote) { browser } !== b) {
                        Log.w(TAG, "media browser connected after it was replaced or stopped — nothing started")
                        return
                    }
                    val token = b.sessionToken
                    val c = MediaController(ctx, token)
                    Log.i(TAG, "media browser connected to $comp — sending play()")
                    attach(c)
                    if (!play()) Log.e(TAG, "cold start connected but play() was refused")
                } catch (e: Exception) {
                    Log.e(TAG, "cold start failed after the browser connected", e)
                }
            }

            override fun onConnectionSuspended() {
                Log.w(TAG, "media browser connection to $comp suspended")
            }

            override fun onConnectionFailed() {
                Log.e(TAG, "media browser connection to $comp was refused — Spotify was not started")
            }
        }
        // MediaBrowser builds its handler on the constructing thread, so it is
        // built on a Looper thread: this one when it has a Looper (the outcome
        // of connect() is then returned honestly), the main Looper otherwise.
        val here = Looper.myLooper()
        val h = if (here != null) Handler(here) else Handler(Looper.getMainLooper())
        val connect = {
            val b = MediaBrowser(ctx, comp, cb, null)
            mine.set(b)
            synchronized(this@SpotifyRemote) {
                browser?.let {
                    try {
                        it.disconnect()
                    } catch (e: Exception) {
                        Log.w(TAG, "disconnecting the previous media browser failed: ${e.message}")
                    }
                }
                browser = b
                browserHandler = h
            }
            b.connect()
        }
        return if (here != null) {
            try {
                connect()
                true
            } catch (e: Exception) {
                Log.e(TAG, "connecting to $comp was refused", e)
                false
            }
        } else {
            val posted = h.post {
                try {
                    connect()
                } catch (e: Exception) {
                    Log.e(TAG, "connecting to $comp was refused", e)
                }
            }
            if (!posted) Log.e(TAG, "cold start could not be dispatched to the main thread")
            posted
        }
    }

    companion object {
        private const val TAG = "music-spotify"

        /** Spotify's application id — the only package this class talks to. */
        const val PKG = "com.spotify.music"

        /** The action a media browser service publishes (`MediaBrowserService`). */
        private const val BROWSER_ACTION = "android.media.browse.MediaBrowserService"

        /** The card's art cell (`MUSIC.md` §8.1). */
        private const val ART_PX = 56

        /** Above this the source is reduced before the box pass. */
        private const val MAX_SRC_PIXELS = 2_000_000L
        private const val PRESCALE_PX = 512
    }
}
