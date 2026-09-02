package wm.damage.core.windows.music

import wm.damage.core.util.Log

/**
 * The in-memory player (tests, `--selfcheck`, `--snapshot`, the desktop sim
 * mode): [PlayerCore] over a sink that keeps a position on a controllable
 * clock and "ends" a track when the position passes its duration. No
 * audio, no threads — commands run inline, async work runs inline too
 * (the library it is handed is synchronous anyway).
 */
class SimMusicPlayer(
    library: MusicLibrary,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Spotify pretend: null = "not available"; a name = a session that starts. */
    private val spotify: String? = null,
) : PlayerCore(library, now) {

    val opened = ArrayList<String>()
    val cmds = ArrayList<String>()
    var outputsAvailable: List<Output> = listOf(Output("buds", "Pixel Buds 2a", "a2dp"), Output("speaker", "Phone speaker", "speaker"))
    var refuseOutput: String? = null

    private inner class SimSink : Sink {
        var playing = false
        var pos = 0L
        var posAt = 0L
        var dur = 0L
        var vol = 50
        var gain = 100
        var out = Output.AUTO
        var prefetched: List<Pair<Int, String>> = emptyList()
        var current: TrackRef? = null

        private fun freeze() { if (playing) { pos = positionMs(); posAt = now() } }

        override fun open(url: String, startMs: Long, playWhenReady: Boolean, track: TrackRef) {
            if (url.contains("fail")) throw IllegalStateException("cannot open $url")
            opened.add("${track.id}@$startMs${if (playWhenReady) "" else " (staged)"}")
            current = track; dur = track.durMs.toLong(); pos = startMs; posAt = now(); playing = playWhenReady
        }
        override fun play() { cmds.add("play"); if (!playing) { posAt = now(); playing = true } }
        override fun pause() { cmds.add("pause"); freeze(); playing = false }
        override fun stop() { cmds.add("stop"); playing = false; pos = 0; current = null }
        override fun seekTo(ms: Long) { cmds.add("seek:$ms"); pos = ms; posAt = now() }
        override fun positionMs(): Long = if (playing) minOf(dur.takeIf { it > 0 } ?: Long.MAX_VALUE, pos + (now() - posAt)) else pos
        override fun durationMs(): Long = dur
        override fun setVolumePct(pct: Int) { cmds.add("vol:$pct"); vol = pct }
        override fun volumePct(): Int = vol
        override fun setBoostPct(pct: Int) { cmds.add("boost:$pct"); gain = pct }
        override fun outputs(): List<Output> = outputsAvailable
        override fun setOutput(id: String): Boolean { cmds.add("out:$id"); if (id == refuseOutput) return false; out = id; return true }
        override fun prefetch(urls: List<Pair<Int, String>>) { prefetched = urls }
        override fun isPlaying(): Boolean = playing
    }

    private val simSink = SimSink()
    override val sink: Sink get() = simSink
    override fun post(block: () -> Unit) = synchronized(this) { block() }
    /** Tests: with [deferAsync] set, async work runs but its completion waits
     *  for [flushAsync] — the gap in which a user acts while a fill is in flight. */
    var deferAsync = false
    private val deferred = ArrayDeque<() -> Unit>()
    override fun <T> runAsync(block: () -> T, then: (Result<T>) -> Unit) {
        val r = try { Result.success(block()) } catch (e: Exception) { Result.failure(e) }
        if (deferAsync) deferred.addLast { then(r) } else then(r)
    }
    fun flushAsync() = post { while (deferred.isNotEmpty()) deferred.removeFirst()() }

    val prefetched: List<Pair<Int, String>> get() = simSink.prefetched
    val sinkVolume: Int get() = simSink.vol
    val sinkBoost: Int get() = simSink.gain

    /** The harness's clock moved: a track past its end "ends"; ticks run. */
    fun advance() = post {
        if (play == PlayState.PLAYING && simSink.dur > 0 && simSink.positionMs() >= simSink.dur) onEnded()
        else if (play == PlayState.PLAYING) tick()
        else tick()
    }

    /** The phone's buttons / the OS moved the stream (the hold-volume rule). */
    fun observeVolume(pct: Int, cause: String = "unknown") = onVolumeObserved(pct, cause)

    override fun spotifyStart(): Boolean {
        if (spotify == null) return false
        spotifyNow = SpotifyNow(spotify, "Spotify", "", 200_000)
        return true
    }
    override fun spotifyCmd(cmd: String) { cmds.add("spotify:$cmd"); if (cmd == "next") spotifyNow = spotifyNow?.copy(title = spotifyNow!!.title + "+") }

    fun pretendPcDown() { onPcLink(false); libraryStalled("prefetch exhausted") }
    fun pretendPcUp() = onPcLink(true)
    fun grantListener(on: Boolean) = post { listenerGranted = on; changed() }

    init {
        Log.d("player", "sim player ready")
        changed()
    }
}
