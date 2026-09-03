package wm.damage.phone.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import wm.damage.core.util.Log
import wm.damage.core.windows.music.Backend
import wm.damage.core.windows.music.MusicLibrary
import wm.damage.core.windows.music.Output
import wm.damage.core.windows.music.PlayState
import wm.damage.core.windows.music.PlayerCore
import wm.damage.core.windows.music.SpotifyNow
import wm.damage.core.windows.music.TrackRef

/**
 * The phone's player (`MUSIC.md` §7): [PlayerCore] over an ExoPlayer sink
 * with a media3 [MediaSession] (the buds' single/double/triple taps arrive
 * as play-pause / next / previous through it — from anywhere), audio focus
 * (transient loss pauses, regain resumes), a 60–300 s load control (the
 * Tailscale insurance), output routing (`setPreferredAudioDevice`; Auto
 * refuses to start with no external output — the speaker plays only when
 * chosen), the media-stream volume synced both ways (a broadcast receiver
 * + a 1 s poll while playing), the boost as a [LoudnessEnhancer] on our
 * session, the prefetch store, Spotify through [SpotifyRemote], and the
 * limiter notice through [MusicListener]. Everything ExoPlayer touches
 * runs on the main looper (its application thread).
 *
 * API references: media3 1.5.1 (ExoPlayer.Builder / ForwardingPlayer /
 * MediaSession), android.media.AudioManager (STREAM_MUSIC, getDevices,
 * the VOLUME_CHANGED_ACTION broadcast — a hidden-but-stable string since
 * API 21), android.media.audiofx.LoudnessEnhancer (API 19).
 */
class AndroidMusicPlayer(
    private val ctx: Context,
    library: MusicLibrary,
    private val scope: CoroutineScope,
    /** Playback engaged for the first time: the service adds the mediaPlayback
     *  foreground type (never at boot — Android 15 refuses it there). */
    private val onEngaged: () -> Unit = {},
) : PlayerCore(library, { System.currentTimeMillis() }) {
    private var engaged = false

    private val main = Handler(Looper.getMainLooper())
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val cache = TrackCache(java.io.File(ctx.cacheDir, "music"))
    private var exo: ExoPlayer? = null
    private var session: MediaSession? = null
    private var enhancer: LoudnessEnhancer? = null
    private var boostPct = 100
    private var preferredDevice: AudioDeviceInfo? = null
    private var currentTrack: TrackRef? = null
    @Volatile private var pcUp = true
    private var tickJob: kotlinx.coroutines.Job? = null
    private var pollJob: kotlinx.coroutines.Job? = null
    private var spotify: SpotifyRemote? = null
    @Volatile private var spotifyState: SpotifyRemoteState? = null
    /** ExoPlayer may only be touched on the main looper: these samples serve
     *  every other thread (the shell loop reads the position for the card,
     *  the lyric scheduler, the summary). */
    @Volatile private var playingFlag = false
    @Volatile private var posSample = 0L
    @Volatile private var posSampleAt = 0L

    private fun onMain(): Boolean = Looper.myLooper() === Looper.getMainLooper()

    private fun samplePosition() {
        val p = exo?.currentPosition?.coerceAtLeast(0) ?: 0L
        posSample = p
        posSampleAt = clock()
    }

    override fun positionMs(): Long {
        if (curBackend == Backend.SPOTIFY) return spotifyPos()
        if (onMain()) { val p = super.positionMs(); posSample = p; posSampleAt = clock(); return p }
        return if (playingFlag) posSample + (clock() - posSampleAt) else posSample
    }

    override fun post(block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block() else main.post(block)
    }

    override fun <T> runAsync(block: () -> T, then: (Result<T>) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val r = try { Result.success(block()) } catch (e: Exception) { Result.failure(e) }
            main.post { then(r) }
        }
    }

    // ------------------------------------------------------------------ the sink
    private val exoListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) onEnded()
        }
        override fun onPlayerError(error: PlaybackException) {
            onSinkError("playback failed: ${error.errorCodeName} ${error.message ?: ""}".trim())
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playingFlag = isPlaying
            samplePosition()
            // an audio-focus pause (another app, a call) shows honestly
            if (!isPlaying && play == PlayState.PLAYING && exo?.playbackState == Player.STATE_READY && exo?.playWhenReady == false) {
                play = PlayState.PAUSED
                changed()
            }
            ensureTicks()
        }
    }

    private fun player(): ExoPlayer {
        exo?.let { return it }
        val attrs = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(60_000, 300_000, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
            .build()
        val p = ExoPlayer.Builder(ctx)
            .setAudioAttributes(attrs, true)          // audio focus: GAIN; transient loss pauses, regain resumes
            .setHandleAudioBecomingNoisy(true)        // buds gone → pause
            .setLoadControl(load)
            .build()
        p.addListener(exoListener)
        exo = p
        // the media session: the buds' taps route through it FROM ANYWHERE.
        // The forwarding player turns "next/previous" into OUR queue moves
        // (ExoPlayer holds one item at a time; the queue is the core's)
        val fwd = object : ForwardingPlayer(p) {
            override fun seekToNext() { this@AndroidMusicPlayer.next() }
            override fun seekToNextMediaItem() { this@AndroidMusicPlayer.next() }
            override fun seekToPrevious() { this@AndroidMusicPlayer.prev() }
            override fun seekToPreviousMediaItem() { this@AndroidMusicPlayer.prev() }
            override fun play() { this@AndroidMusicPlayer.play() }
            override fun pause() { this@AndroidMusicPlayer.pause() }
            override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
                .addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_PLAY_PAUSE).build()
            override fun isCommandAvailable(command: Int): Boolean =
                command == Player.COMMAND_SEEK_TO_NEXT || command == Player.COMMAND_SEEK_TO_PREVIOUS || command == Player.COMMAND_PLAY_PAUSE || super.isCommandAvailable(command)
        }
        session = try { MediaSession.Builder(ctx, fwd).setId("damage-music").build() } catch (e: Exception) {
            Log.e("music-android", "media session not created — bud taps will not reach the player", e); null
        }
        preferredDevice?.let { p.setPreferredAudioDevice(it) }
        return p
    }

    private fun externalOutputPresent(): Boolean = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { isExternal(it) }

    private fun isExternal(d: AudioDeviceInfo): Boolean = when (d.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_AUX_LINE -> true
        else -> false
    }

    private fun kindOf(d: AudioDeviceInfo): String = when (d.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET -> "bluetooth"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> "wired"
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_AUX_LINE -> "line"
        else -> "output ${d.type}"
    }

    private val androidSink = object : Sink {
        override fun open(url: String, startMs: Long, playWhenReady: Boolean, track: TrackRef) {
            val local = cache.fileFor(url)
            if (local == null && !pcUp) throw PlayerCore.StalledException("PC unreachable and '${track.title}' is not cached")
            if (preferredDevice == null && !externalOutputPresent()) {
                throw IllegalStateException("no external output — choose the phone speaker in Output to play aloud")
            }
            val p = player()
            currentTrack = track
            val item = if (local != null) MediaItem.fromUri(android.net.Uri.fromFile(local)) else MediaItem.fromUri(url)
            p.setMediaItem(item, startMs)
            p.prepare()
            p.playWhenReady = playWhenReady
            if (!engaged) { engaged = true; try { onEngaged() } catch (e: Exception) { Log.e("music-android", "engaged hook", e) } }
            applyBoost()
            ensureTicks()
            Log.i("music-android", "open ${track.id} ${if (local != null) "from the cache" else "streamed"} at $startMs (${if (playWhenReady) "playing" else "staged"})")
        }
        override fun play() { player().play(); ensureTicks() }
        override fun pause() { exo?.pause(); ensureTicks() }
        override fun stop() { exo?.stop(); exo?.clearMediaItems(); currentTrack = null; ensureTicks() }
        override fun seekTo(ms: Long) { exo?.seekTo(ms) }
        override fun positionMs(): Long = exo?.currentPosition?.coerceAtLeast(0) ?: 0L
        override fun durationMs(): Long = exo?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        override fun setVolumePct(pct: Int) {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val idx = Math.round(pct / 100.0 * max).toInt().coerceIn(0, max)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0)
        }
        override fun volumePct(): Int = readVolumePct()
        override fun setBoostPct(pct: Int) { boostPct = pct; applyBoost() }
        override fun outputs(): List<Output> = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY && it.type != AudioDeviceInfo.TYPE_REMOTE_SUBMIX }
            .map { Output("${it.id}", it.productName?.toString()?.ifEmpty { null } ?: kindOf(it), kindOf(it)) }
        override fun setOutput(id: String): Boolean {
            if (id == Output.AUTO) { preferredDevice = null; exo?.setPreferredAudioDevice(null); return true }
            val d = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { "${it.id}" == id } ?: return false
            preferredDevice = d
            exo?.setPreferredAudioDevice(d)
            return true
        }
        override fun prefetch(urls: List<Pair<Int, String>>) { cache.prefetch(urls, curPrefetch()) }
        override fun isPlaying(): Boolean = exo?.isPlaying == true
    }

    override val sink: Sink get() = androidSink

    private fun curPrefetch(): Int = prefetchCount

    private fun readVolumePct(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return Math.round(audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100.0 / max).toInt()
    }

    /** +12 dB at 400 % (a ratio in percent → millibels: 2000·log10(pct/100)). */
    private fun applyBoost() {
        val p = exo ?: return
        val mB = if (boostPct <= 100) 0 else Math.round(2000.0 * Math.log10(boostPct / 100.0)).toInt().coerceIn(0, 1200)
        try {
            val sid = p.audioSessionId
            if (sid == C.AUDIO_SESSION_ID_UNSET) return
            // one enhancer for the life of the player: exo is built once in player()
            // and released only in close(), so its session id never changes
            val e = enhancer ?: LoudnessEnhancer(sid).also { enhancer = it }
            e.setTargetGain(mB)
            e.enabled = mB > 0
        } catch (e: Exception) {
            Log.e("music-android", "boost not applied", e)
            emit(wm.damage.core.windows.music.PlayerEvent.Error("boost failed: ${e.message}"))
        }
    }

    // ------------------------------------------------------------------ pacing (ticks, the volume poll)
    private var focusedNow = false
    override fun setFocused(focused: Boolean) { super.setFocused(focused); focusedNow = focused; post { ensureTicks() } }

    /** Main thread only (it reads the player); the loops it starts read the
     *  volatile samples, never the player. */
    private fun ensureTicks() {
        playingFlag = exo?.isPlaying == true
        val playing = playingFlag || curBackend == Backend.SPOTIFY
        if (playing && tickJob?.isActive != true) {
            tickJob = scope.launch {
                while (isActive) {
                    delay(if (focusedNow) 1_000 else 5_000)
                    main.post { if (playingFlag || curBackend == Backend.SPOTIFY) { samplePosition(); tick() } }
                    if (!playingFlag && curBackend != Backend.SPOTIFY) break
                }
            }
        }
        if (playing && pollJob?.isActive != true) {
            // a 1 s poll backs the broadcast receiver up (the phone's buttons)
            pollJob = scope.launch {
                while (isActive) {
                    delay(1_000)
                    val v = readVolumePct()
                    if (v != volume) onVolumeObserved(v, "poll")
                    if (!playingFlag) break
                }
            }
        }
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != AudioManager.STREAM_MUSIC) return
            val v = readVolumePct()
            onVolumeObserved(v, "broadcast")
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            // only the ACTIVE route's loss is a route loss: the OS pauses the
            // player itself (becoming-noisy) when the route it played on went;
            // a dongle unplugged elsewhere must not stop the music (review
            // 2026-09-02). Look a moment later at whether playback stopped.
            if (removed.any { isExternal(it) }) {
                val wasPlaying = playingFlag
                main.postDelayed({ if (wasPlaying && !playingFlag) onRouteLost("the output went away") else changed() }, 500)
            }
            if (preferredDevice != null && removed.any { it.id == preferredDevice!!.id }) { preferredDevice = null; curOutput = Output.AUTO; changed() }
        }
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { changed() }
    }

    init {
        try { ctx.registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION")) } catch (e: Exception) { Log.e("music-android", "volume receiver", e) }
        audio.registerAudioDeviceCallback(deviceCallback, main)
        volume = readVolumePct()
        heldVolume = volume
        MusicListener.onVolumeLowered = { text -> onLimiterNotice(text) }
        listenerGranted = MusicListener.granted(ctx)
        if (!listenerGranted) problem = "grant notification access on the phone (Spotify + hold my volume)"
        changed()
        Log.i("music-android", "player ready: volume $volume%, listener ${if (listenerGranted) "granted" else "NOT granted"}, outputs ${androidSink.outputs().map { it.name }}")
    }

    fun onPcLinkLine(line: String) {
        pcUp = line.isEmpty()
        onPcLink(pcUp)
    }

    // ------------------------------------------------------------------ Spotify (verdicts 3/20/25)
    private fun remote(): SpotifyRemote = spotify ?: SpotifyRemote(ctx) { s ->
        spotifyState = s
        main.post {
            spotifyNow = if (s.present && s.now != null) s.now else spotifyNow
            if (curBackend == Backend.SPOTIFY) { play = if (s.playing) PlayState.PLAYING else PlayState.PAUSED; changed() }
        }
    }.also { spotify = it; it.start() }

    override fun spotifyStart(): Boolean {
        listenerGranted = MusicListener.granted(ctx)
        val r = remote()
        val s = spotifyState
        if (s?.present == true) { r.play(); return true }
        val ok = r.coldStart()
        if (!ok) Log.w("music-android", "Spotify cold start refused")
        return ok
    }

    override fun spotifyCmd(cmd: String) {
        val r = remote()
        val ok = when (cmd) { "play" -> r.play(); "pause" -> r.pause(); "next" -> r.next(); "prev" -> r.prev(); "toggle" -> r.toggle(); else -> false }
        if (!ok) emit(wm.damage.core.windows.music.PlayerEvent.Error("Spotify: no session for '$cmd' — is it running?"))
    }

    override fun spotifyPos(): Long {
        val s = spotifyState ?: return 0L
        return if (s.playing) s.posMs + (SystemClock.elapsedRealtime() - s.posAtMs) else s.posMs
    }

    override fun onRestored() {
        // Sink-side settings re-applied after a restore; the volume is NOT
        // touched (it is the phone's own). The output is matched on its STABLE
        // identity — product name + kind — because AudioDeviceInfo.getId() is a
        // per-connection handle that changes on every reconnect and gets reused
        // for other devices. Matching on the id alone either silently selected
        // nothing (the refusal was dropped on the floor) or pinned playback to
        // whatever inherited the number (review 2026-09-02).
        if (curOutput == Output.AUTO) return
        val devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        // exact identity first: the product name AND the kind
        var matches = devices.filter { curOutputName.isNotEmpty() && it.productName?.toString() == curOutputName && kindOf(it) == curOutputKind }
        if (matches.isEmpty() && curOutputName.isNotEmpty() && curOutputName == curOutputKind) {
            // the saved device had no product name, so its name IS the kind
            // label — accept a same-kind device only when there is exactly ONE.
            // Matching "any bluetooth output" would re-create the very defect
            // this replaced: with the earbud AND the glasses connected it is a
            // coin flip which one the music goes to (review 2026-09-03).
            matches = devices.filter { kindOf(it) == curOutputKind }
        }
        val d = matches.singleOrNull()
        if (d == null) {
            val why = if (matches.size > 1) "is ambiguous (${matches.size} ${curOutputKind} outputs)" else "is not here"
            Log.w("music-android", "saved output '${curOutputName.ifEmpty { curOutput }}' ($curOutputKind) $why — Auto")
            preferredDevice = null
            exo?.setPreferredAudioDevice(null)
            outputRestoreFailed()
            return
        }
        preferredDevice = d
        exo?.setPreferredAudioDevice(d)
        curOutput = "${d.id}"                  // the handle it has THIS session
        Log.i("music-android", "output restored: ${d.productName} (${kindOf(d)}) as id ${d.id}")
    }

    override fun close() {
        try { ctx.unregisterReceiver(volumeReceiver) } catch (e: Exception) { /* not registered */ }
        try { audio.unregisterAudioDeviceCallback(deviceCallback) } catch (e: Exception) { /* not registered */ }
        MusicListener.onVolumeLowered = null
        tickJob?.cancel(); pollJob?.cancel()
        post {
            try { session?.release() } catch (e: Exception) { Log.w("music-android", "session release: ${e.message}") }
            try { enhancer?.release() } catch (e: Exception) { /* released */ }
            try { exo?.release() } catch (e: Exception) { Log.w("music-android", "player release: ${e.message}") }
            exo = null; session = null; enhancer = null
        }
        spotify?.stop()
        cache.close()
    }
}
