package wm.damage.core.windows.music

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import wm.damage.core.util.Log

/**
 * The player's LOGIC, shared by the phone (ExoPlayer under it) and the
 * in-memory simulator (tests, selfcheck): the queue engine + mode, the
 * transport rules (a prev-tap ≥ 3 s in restarts; a skip before 80 % counts
 * as skipped in history; never auto-play on boot — verdict 9), the
 * low-water fill for Radio / Library random, the sleep deadline (verdict
 * 28), the volume boost that goes with the track (verdict 15), the
 * hold-my-volume classification (verdict 14: a large instant drop is the
 * limiter, a run of single steps is the user), prefetch bookkeeping and
 * the Spotify fallback state machine (verdicts 20/25).
 *
 * A subclass supplies the [Sink] (what actually plays), [post] (its thread)
 * and [runAsync] (off-thread work: library calls). Every public command is
 * safe from any thread — it is posted to the player's thread.
 */
abstract class PlayerCore(
    protected val library: MusicLibrary,
    protected val clock: () -> Long,
) : MusicPlayer {

    /** What plays. Called on the player's thread; reports back through the
     *  `on*` methods below (any thread). */
    interface Sink {
        /** Load [url] (or a local file) at [startMs]; play at once when [playWhenReady]. */
        fun open(url: String, startMs: Long, playWhenReady: Boolean, track: TrackRef)
        fun play(); fun pause(); fun stop()
        fun seekTo(ms: Long)
        fun positionMs(): Long
        fun durationMs(): Long
        /** The phone's media stream, 0–100. */
        fun setVolumePct(pct: Int); fun volumePct(): Int
        /** The boost gain in percent (100 = off). */
        fun setBoostPct(pct: Int)
        fun outputs(): List<Output>
        fun setOutput(id: String): Boolean
        /** Keep the next tracks' files at hand (the prefetch store). */
        fun prefetch(urls: List<Pair<Int, String>>)
        fun isPlaying(): Boolean
        /** The open item streams from the PC (it is not played from the cache). */
        fun streamed(): Boolean
        /** The open item has no data to play from (the player is buffering), whether or not it should play. */
        fun waitingForData(): Boolean
    }

    /** A sink that cannot open because the PC is down and the file is not
     *  cached: the core switches to the Spotify fallback (verdict 25). */
    class StalledException(msg: String) : Exception(msg)

    protected abstract val sink: Sink
    protected val prefetchCount: Int get() = prefetchN
    /** Run [block] on the player's thread. */
    protected abstract fun post(block: () -> Unit)
    /** Run [block] off the player's thread (library I/O); [then] back on it. */
    protected abstract fun <T> runAsync(block: () -> T, then: (Result<T>) -> Unit)

    protected val engine = QueueEngine()
    private val listeners = CopyOnWriteArrayList<MusicPlayer.Listener>()

    @Volatile protected var play: PlayState = PlayState.STOPPED
    @Volatile protected var curBackend: Backend = Backend.LIBRARY
    @Volatile protected var spotifyAuto = false
    @Volatile protected var volume = 50
    @Volatile protected var curBoost = 100
    @Volatile protected var curOutput = Output.AUTO
    /** The chosen output's stable identity — see [Output]. */
    @Volatile protected var curOutputName = ""
    @Volatile protected var curOutputKind = ""
    @Volatile protected var curSleep = Sleep.OFF
    @Volatile protected var curHold = true
    @Volatile protected var curProfile = AudioProfile.DEFAULT
    @Volatile protected var prefetchN = 3
    @Volatile protected var curSpotifyFallback = true
    @Volatile protected var pcLink = PcLink()
    @Volatile protected var problem = ""
    @Volatile protected var spotifyNow: SpotifyNow? = null
    @Volatile protected var listenerGranted = false
    @Volatile private var lastPos = 0L
    @Volatile private var lastPosAt = 0L
    @Volatile private var focused = false
    /** The current track's history row: when it started, for the skip rule. */
    private var historyStart = 0L
    private var historyTrack: TrackRef? = null
    private var fillInFlight = false
    /** An end-of-queue advance that arrived while a fill was in flight: it
     *  runs when the fill lands (never "the queue ended" with rows coming). */
    private var advanceAfterFill: String? = null
    private var queueGen = 0
    /** Bumped by a user's pick (play-from): a fill dispatched earlier with
     *  "advance when you land" keeps its rows but drops that advance, or it
     *  would step one past the row the user chose (ultrareview 2026-09-02). */
    private var pickGen = 0
    /** The volume we last set ourselves (a change to another value with no
     *  cause of ours is the phone's). */
    protected var ourVolume = -1
    /** The user's held level for the limiter rule. */
    protected var heldVolume = -1
    private val resets = ArrayDeque<Long>()
    /** The quiet-stream notice fires once per playback RUN — a per-track
     *  notice would nag every four minutes. Cleared when playback stops or
     *  the level comes back up. */
    private var quietWarned = false
    private var lastPersistPos = 0L
    @Volatile private var pcDownSince = 0L
    /** When the current streamed track was first seen waiting for data while it should play; 0 = it is not. */
    private var waitingSince = 0L
    /** Spotify's session was PLAYING at its last report: a Spotify he starts is the edge from not playing. */
    private var spotifyWasPlaying = false

    /** The phone's journal (`music` notes): the player's decisions, one line each (M6). Set by the host. */
    @Volatile var journal: (String) -> Unit = {}

    /** A decision of the player: one line in the log and one note in the journal. */
    protected fun decided(line: String) {
        Log.i("player", line)
        try { journal(line) } catch (e: Exception) { Log.e("player", "journal note not written", e) }
    }

    // ------------------------------------------------------------------ state
    protected fun snapshot(): PlayerState = PlayerState(
        backend = curBackend, play = play, queue = engine.entries.toList(), index = engine.index,
        posMs = positionMs(), posAtMs = clock(), durMs = if (curBackend == Backend.LIBRARY) sink.durationMs().takeIf { it > 0 } ?: (engine.current?.track?.durMs?.toLong() ?: 0L) else (spotifyNow?.durMs ?: 0L),
        mode = engine.mode, volume = volume, boost = curBoost, output = curOutput, outputs = safeOutputs(),
        pcLink = pcLink, sleep = curSleep, holdVolume = curHold, profile = curProfile, label = engine.label,
        spotify = spotifyNow, spotifyAuto = spotifyAuto, problem = problem, listenerGranted = listenerGranted,
    )

    private fun safeOutputs(): List<Output> = try { listOf(Output.AUTO_OUTPUT) + sink.outputs().filter { it.id != Output.AUTO } } catch (e: Exception) { listOf(Output.AUTO_OUTPUT) }

    @Volatile private var cached: PlayerState = PlayerState()
    override val state: PlayerState get() = cached

    protected fun changed() {
        cached = snapshot()
        for (l in listeners) try { l.state(cached) } catch (e: Exception) { Log.e("player", "state listener", e) }
    }

    protected fun emit(e: PlayerEvent) {
        for (l in listeners) try { l.event(e) } catch (ex: Exception) { Log.e("player", "event listener", ex) }
    }

    override fun positionMs(): Long {
        if (curBackend == Backend.SPOTIFY) return spotifyPos()
        return if (play == PlayState.PLAYING) {
            val p = try { sink.positionMs() } catch (e: Exception) { lastPos }
            lastPos = p; lastPosAt = clock(); p
        } else lastPos
    }

    protected open fun spotifyPos(): Long = 0L

    // ------------------------------------------------------------------ transport
    private fun currentUrl(t: TrackRef): String = library.streamUrl(t.id, curProfile)

    /** Open the current entry (a real open ends the previous track's history row). */
    protected fun openCurrent(startMs: Long, playNow: Boolean, cause: String) {
        val e = engine.current ?: run { Log.w("player", "openCurrent($cause): empty queue"); return }
        closeHistory(cause)
        val t = e.track
        if (curBoost != 100) { curBoost = 100; try { sink.setBoostPct(100) } catch (ex: Exception) { Log.w("player", "boost off: ${ex.message}") }; emit(PlayerEvent.BoostOff) }
        waitingSince = 0
        try {
            sink.open(currentUrl(t), startMs, playNow, t)
        } catch (ex: StalledException) {
            Log.w("player", "open of track ${t.id} stalled: ${ex.message}")
            // M3: a staged open (paused, the sleep timer's next track) is not a request to play: it is said, never switched
            if (playNow) libraryStalled(ex.message ?: "not cached", resumeAt = startMs)
            else {
                decided("stall of a staged track, not switched: ${ex.message}")
                emit(PlayerEvent.Error("PC unreachable and no cached track — ${ex.message ?: "not cached"}"))
            }
            return
        } catch (ex: Exception) {
            Log.e("player", "open of track ${t.id} failed", ex)
            problem = "open failed: ${ex.message}"
            emit(PlayerEvent.Error("open failed: ${ex.message}"))
            changed(); return
        }
        play = if (playNow) PlayState.PLAYING else PlayState.PAUSED
        if (playNow) warnIfQuiet()
        lastPos = startMs; lastPosAt = clock()
        historyStart = clock(); historyTrack = t
        problem = ""
        emit(PlayerEvent.TrackChange(t, engine.index, engine.size))
        prefetchAhead()
        maybeFill("open")
        changed()
    }

    /**
     * Playing into a stream too quiet to hear is INDISTINGUISHABLE on glass
     * from playing normally: the state is right, the position advances, the
     * queue moves on. It happened for real on 2026-09-02 — four tracks end to
     * end at 8 % — so it is now SAID. Once per run; [volume] is the phone's
     * own media level, read from the sink, never set by us at boot.
     */
    private fun warnIfQuiet() {
        if (curBackend != Backend.LIBRARY) return          // Spotify owns its own level
        if (volume > QUIET_PCT) { quietWarned = false; return }
        if (quietWarned) return
        quietWarned = true
        Log.w("player", "playback started at $volume% media volume — that will not be audible")
        emit(PlayerEvent.QuietStream(volume))
    }

    private fun closeHistory(cause: String) {
        val t = historyTrack ?: return
        historyTrack = null
        val started = historyStart
        val ended = clock()
        val pos = lastPos
        val dur = t.durMs.toLong()
        val completed = cause == "ended"
        val skipped = !completed && dur > 0 && pos < dur * 0.8
        runAsync({ library.played(t.id, started, ended, completed, skipped) }) { r ->
            r.exceptionOrNull()?.let { Log.w("player", "play history for track ${t.id} not recorded: ${it.message}") }
        }
    }

    protected fun prefetchAhead() {
        val urls = ArrayList<Pair<Int, String>>()
        for (i in engine.index + 1..minOf(engine.size - 1, engine.index + prefetchN)) {
            val t = engine.entries[i].track
            urls.add(t.id to currentUrl(t))
        }
        try { sink.prefetch(urls) } catch (e: Exception) { Log.w("player", "prefetch: ${e.message}") }
    }

    override fun play() = post {
        if (curBackend == Backend.SPOTIFY) { spotifyCmd("play"); return@post }
        when (play) {
            PlayState.PLAYING -> {}
            PlayState.PAUSED -> { try { sink.play() } catch (e: Exception) { fail("play", e); return@post }; play = PlayState.PLAYING; warnIfQuiet(); changed() }
            PlayState.STOPPED -> if (!engine.isEmpty) openCurrent(lastPos, true, "play") else emit(PlayerEvent.Error("nothing queued"))
        }
    }

    override fun pause() = post {
        if (curBackend == Backend.SPOTIFY) { spotifyCmd("pause"); return@post }
        if (play != PlayState.PLAYING) return@post
        lastPos = positionMs()
        try { sink.pause() } catch (e: Exception) { fail("pause", e); return@post }
        play = PlayState.PAUSED
        changed()
    }

    override fun toggle() = post {
        if (curBackend == Backend.SPOTIFY) { spotifyCmd("toggle"); return@post }
        when (play) {
            PlayState.PLAYING -> pause()
            PlayState.PAUSED -> play()
            PlayState.STOPPED -> if (!engine.isEmpty) openCurrent(lastPos, true, "toggle") else emit(PlayerEvent.Error("nothing queued — Ask or Browse first"))
        }
    }

    override fun next() = post {
        if (curBackend == Backend.SPOTIFY) { spotifyCmd("next"); return@post }
        advance("next", playNow = play != PlayState.STOPPED)
    }

    /** Forward one entry, or fill/end honestly. */
    private fun advance(cause: String, playNow: Boolean) {
        if (engine.isEmpty) return
        if (engine.next()) { openCurrent(0, playNow, cause); return }
        if (fillInFlight) { advanceAfterFill = cause; return }      // the fill lands and advances
        if (engine.needsFill()) {
            // the fill lands asynchronously and advances when it has rows
            maybeFill(cause, thenAdvance = true)
            return
        }
        endOfQueue(cause)
    }

    private fun endOfQueue(cause: String) {
        quietWarned = false
        closeHistory(cause)
        try { sink.stop() } catch (e: Exception) { Log.w("player", "stop at queue end: ${e.message}") }
        play = PlayState.STOPPED
        lastPos = 0
        emit(PlayerEvent.QueueEnd)
        changed()
    }

    override fun prev() = post {
        if (curBackend == Backend.SPOTIFY) { spotifyCmd("prev"); return@post }
        if (engine.isEmpty) return@post
        // Spotify-identical: ≥ 3 s in restarts the track; at the head restarts too
        if (positionMs() >= PREV_RESTART_MS || !engine.prev()) openCurrent(0, play != PlayState.STOPPED, "restart")
        else openCurrent(0, play != PlayState.STOPPED, "prev")
    }

    override fun stop() = post {
        if (curBackend == Backend.SPOTIFY) { spotifyCmd("pause"); return@post }
        quietWarned = false
        closeHistory("stop")
        try { sink.stop() } catch (e: Exception) { Log.w("player", "stop: ${e.message}") }
        play = PlayState.STOPPED
        lastPos = 0          // stop = restart-from-top semantics
        if (curBoost != 100) {
            curBoost = 100
            try { sink.setBoostPct(100) } catch (e: Exception) { Log.w("player", "boost off on stop: ${e.message}") }
            emit(PlayerEvent.BoostOff)
        }
        changed()
    }

    override fun seekTo(ms: Long) = post {
        if (curBackend == Backend.SPOTIFY || play == PlayState.STOPPED) return@post
        val dur = sink.durationMs().takeIf { it > 0 } ?: engine.current?.track?.durMs?.toLong() ?: Long.MAX_VALUE
        val v = ms.coerceIn(0, dur)
        try { sink.seekTo(v) } catch (e: Exception) { fail("seek", e); return@post }
        lastPos = v; lastPosAt = clock()
        changed()
    }

    override fun seekBy(ms: Long) = post { seekTo(positionMs() + ms) }

    override fun playQueue(tracks: List<TrackRef>, startIndex: Int, mode: Mode, label: String) = post {
        if (tracks.isEmpty()) { emit(PlayerEvent.Error("nothing to play")); return@post }
        if (curBackend == Backend.SPOTIFY && !spotifyAuto) {    // a deliberate library play leaves a chosen Spotify
            curBackend = Backend.LIBRARY
            decided("back to the PC library (a library play)")
        }
        queueGen++
        advanceAfterFill = null
        engine.set(tracks, startIndex, mode, label)
        openCurrent(0, true, "new-queue")
    }

    override fun playFrom(qid: Long) = post {
        if (!engine.playFrom(qid)) { emit(PlayerEvent.Error("that row is gone")); return@post }
        pickGen++
        advanceAfterFill = null
        openCurrent(0, true, "play-from")
    }

    override fun playNext(tracks: List<TrackRef>) = post {
        if (tracks.isEmpty()) return@post
        engine.insertNext(tracks)
        prefetchAhead()
        changed()
    }

    override fun append(tracks: List<TrackRef>) = post {
        if (tracks.isEmpty()) return@post
        engine.append(tracks)
        prefetchAhead()
        changed()
    }

    override fun replace(tracks: List<TrackRef>, label: String) = playQueue(tracks, 0, engine.mode, label)

    override fun remove(qid: Long) = post {
        if (!engine.remove(qid)) emit(PlayerEvent.Error("the current track cannot be removed"))
        else { prefetchAhead(); changed() }
    }

    override fun move(qid: Long, delta: Int) = post { if (engine.move(qid, delta)) { prefetchAhead(); changed() } }

    override fun clear() = post {
        closeHistory("clear")
        try { sink.stop() } catch (e: Exception) { Log.w("player", "stop on clear: ${e.message}") }
        queueGen++
        engine.clear()
        play = PlayState.STOPPED
        lastPos = 0
        changed()
    }

    override fun shuffleRest() = post { engine.shuffleRest(); prefetchAhead(); changed() }

    override fun setMode(mode: Mode) = post {
        engine.setMode(mode)
        maybeFill("mode")
        prefetchAhead()
        changed()
    }

    // ------------------------------------------------------------------ the low-water fill
    private fun maybeFill(reason: String, thenAdvance: Boolean = false) {
        if (!engine.needsFill() || fillInFlight) { if (thenAdvance) endOfQueue(reason); return }
        fillInFlight = true
        val gen = queueGen
        val pick = pickGen
        val mode = engine.mode
        val seeds = engine.seedIds()
        val exclude = engine.ids()
        runAsync({
            if (mode == Mode.RADIO) library.similar(seeds, exclude, RADIO_BATCH) else library.randomLibrary(RADIO_BATCH, exclude)
        }) { r ->
            fillInFlight = false
            val adv = (thenAdvance && pick == pickGen) || advanceAfterFill != null
            val cause = advanceAfterFill ?: reason
            advanceAfterFill = null
            if (gen != queueGen) { Log.i("player", "fill discarded — the queue was replaced meanwhile"); return@runAsync }
            val tracks = r.getOrElse { e ->
                Log.w("player", "${mode.label} fill failed: ${e.message}")
                emit(PlayerEvent.Error("${mode.label}: ${e.message}"))
                emptyList()
            }
            if (tracks.isEmpty()) {
                Log.w("player", "${mode.label}: no fresh tracks — the queue ends honestly")
                if (adv) endOfQueue(cause)
                return@runAsync
            }
            engine.append(tracks)
            Log.i("player", "${mode.label} appended ${tracks.size} tracks ($reason)")
            if (adv) advance(cause, playNow = play != PlayState.STOPPED) else { prefetchAhead(); changed() }
        }
    }

    // ------------------------------------------------------------------ volume, curBoost, hold
    override fun setVolume(pct: Int, cause: String) = post {
        val v = pct.coerceIn(0, 100)
        Log.i("player", "volume $volume → $v ($cause)")
        ourVolume = v
        heldVolume = v
        volume = v
        // the quiet notice says "scroll here to raise it": doing so must ARM
        // the notice again, exactly as an observed rise does. Without this the
        // latch stayed set for the rest of the run and a later drop back under
        // 10 % played silently with nothing said (review 2026-09-03).
        if (v > QUIET_PCT) quietWarned = false
        try { sink.setVolumePct(v) } catch (e: Exception) { fail("volume", e) }
        changed()
    }

    /**
     * The sink saw the stream change to [pct] (the phone's buttons, another
     * app, the OS limiter). Verdict 14: a drop ≥ max(3 steps ≈ 20 %, 25 %
     * of range) in ONE event from the held level, not ours, is the limiter
     * → re-set to the held level (paced: at most 3 re-sets in 10 minutes,
     * then a notice that the phone keeps lowering it). Anything else is the
     * user, and the held level follows it.
     */
    fun onVolumeObserved(pct: Int, cause: String = "unknown") = post {
        val v = pct.coerceIn(0, 100)
        // our own set echoes back once: clear the marker on that echo, even
        // when the level already matches — a later user move to the same
        // value must not read as ours (review 2026-09-02)
        if (v == ourVolume) { ourVolume = -1; if (v != volume) { volume = v; changed() }; return@post }
        if (v == volume) return@post
        val drop = heldVolume - v
        val limiter = curHold && heldVolume > 0 && drop >= LIMITER_DROP && cause != "user-button"
        Log.i("player", "volume $volume → $v observed ($cause${if (limiter) ", limiter suspected" else ""})")
        volume = v
        if (v > QUIET_PCT) quietWarned = false
        if (limiter) restoreHeld("drop of $drop") else { heldVolume = v; changed() }
    }

    /** The listener saw the system's "volume lowered" notice — the
     *  high-confidence signal (verdict 14). */
    fun onLimiterNotice(text: String) = post {
        Log.i("player", "phone volume-lowered notice: ${text.take(120)}")
        if (curHold && heldVolume > 0) restoreHeld("notice") else changed()
    }

    private fun restoreHeld(why: String) {
        val now = clock()
        while (resets.isNotEmpty() && now - resets.first() > RESET_WINDOW_MS) resets.removeFirst()
        if (resets.size >= RESET_MAX) {
            Log.w("player", "the phone keeps lowering the volume ($why) — ${resets.size} re-sets in 10 min, leaving it")
            emit(PlayerEvent.LimiterKeeps("the phone keeps lowering the volume — left at $volume%"))
            heldVolume = volume
            changed()
            return
        }
        resets.addLast(now)
        Log.i("player", "re-setting the volume to the held $heldVolume% ($why)")
        ourVolume = heldVolume
        volume = heldVolume
        try { sink.setVolumePct(heldVolume) } catch (e: Exception) { fail("hold-reset", e) }
        emit(PlayerEvent.LimiterUndone(heldVolume))
        changed()
    }

    override fun setBoost(pct: Int) = post {
        val v = pct.coerceIn(100, 400)
        curBoost = v
        try { sink.setBoostPct(v) } catch (e: Exception) { fail("boost", e) }
        if (v > 100 && volume >= 100) emit(PlayerEvent.BoostLoud(volume, v))
        changed()
    }

    override fun setOutput(id: String) = post {
        val ok = try { sink.setOutput(id) } catch (e: Exception) { fail("output", e); false }
        if (ok) {
            curOutput = id
            // remember what it IS, not only which handle it had today
            val o = if (id == Output.AUTO) null else safeOutputs().firstOrNull { it.id == id }
            curOutputName = o?.name ?: ""
            curOutputKind = o?.kind ?: ""
            problem = ""
            changed()
        } else emit(PlayerEvent.Error("output '$id' refused"))
    }

    /** A restored output that no longer exists: say so and fall back to Auto
     *  rather than leave the UI naming a device nothing is routed to (review
     *  2026-09-02 — `onRestored` used to ignore the sink's refusal). */
    protected fun outputRestoreFailed() {
        val was = curOutputName.ifEmpty { curOutput }
        curOutput = Output.AUTO
        curOutputName = ""
        curOutputKind = ""
        emit(PlayerEvent.OutputGone(was))
        changed()
    }

    override fun outputs(): List<Output> = safeOutputs()

    override fun setSleep(s: Sleep) = post { curSleep = s; changed() }
    override fun setHoldVolume(on: Boolean) = post { curHold = on; if (on) heldVolume = volume; changed() }
    override fun setProfile(p: AudioProfile) = post { curProfile = p; prefetchAhead(); changed() }
    override fun setPrefetch(n: Int) = post { prefetchN = n.coerceIn(1, 10); prefetchAhead(); changed() }
    override fun setSpotifyFallback(auto: Boolean) = post { curSpotifyFallback = auto; changed() }
    override fun setFocused(focused: Boolean) { this.focused = focused }

    // ------------------------------------------------------------------ ticks (paced, from the subclass)
    /** Called on the player's thread on the subclass's pacing while playing. */
    protected fun tick() {
        val now = clock()
        val pos = positionMs()
        watchStream(now)
        // sleep (verdict 28): a deadline checked on ticks — pacing, never a timer wrapper
        if (curSleep.kind == Sleep.Kind.TIMER && now >= curSleep.deadlineMs && play == PlayState.PLAYING) {
            curSleep = Sleep.OFF
            pause()
            emit(PlayerEvent.SleepEnded("sleep timer — paused"))
        }
        for (l in listeners) try { l.tick(pos) } catch (e: Exception) { Log.e("player", "tick listener", e) }
        if (now - lastPersistPos >= 10_000) { lastPersistPos = now; changed() }
    }

    /** The sink reached the end of the current item. */
    fun onEnded() = post {
        if (curSleep.kind == Sleep.Kind.AFTER_TRACK) {
            curSleep = Sleep.OFF
            closeHistory("ended")
            if (engine.next()) openCurrent(0, false, "sleep-after-track") else endOfQueue("ended")
            emit(PlayerEvent.SleepEnded("sleep — stopped after the track"))
            return@post
        }
        advance("ended", playNow = true)
    }

    /** [sourceFailed]: the error's cause is the source or the network (the player library's I/O codes). */
    fun onSinkError(detail: String, sourceFailed: Boolean = false) = post {
        Log.e("player", "sink: $detail")
        // M1 (a): a stream the PC cannot serve switches like an open that cannot be served (the setting off: said)
        if (sourceFailed && streamWanted()) { streamStalled("the stream failed: $detail"); return@post }
        problem = detail
        play = PlayState.PAUSED
        emit(PlayerEvent.Error(detail))
        changed()
    }

    /** The output went away. The sink may already have paused itself (the
     *  becoming-noisy handler) — the notice still fires while a track is loaded. */
    fun onRouteLost(detail: String) = post {
        if (play == PlayState.STOPPED) return@post
        if (play == PlayState.PLAYING) {
            lastPos = positionMs()
            try { sink.pause() } catch (e: Exception) { Log.w("player", "pause on route loss: ${e.message}") }
            play = PlayState.PAUSED
        }
        emit(PlayerEvent.RouteLost(detail))
        changed()
    }

    /** The PC link as the library reports it (staleness with duration). */
    fun onPcLink(up: Boolean, detail: String = "") = post {
        val was = pcLink
        pcLink = if (up) PcLink(true, 0) else PcLink(false, if (was.up) clock() else was.sinceMs)
        if (!up && was.up) {
            pcDownSince = clock()
            decided("PC link down: ${detail.ifEmpty { "no reason given" }}")
            emit(PlayerEvent.PcUnreachable(pcDownSince))
        }
        if (up && !was.up) {
            val since = was.sinceMs
            decided("PC link up" + if (since > 0) " — down since ${clockTime(since)}, ${(clock() - since) / 1000} s" else "")
            pcDownSince = 0
        }
        changed()
    }

    private fun clockTime(ms: Long): String =
        java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0).toString()

    // ------------------------------------------------------------------ the stream's supervision (M1)
    /** The library should be playing a track the PC streams, with the automatic switch on. */
    private fun streamWanted(): Boolean =
        curBackend == Backend.LIBRARY && curSpotifyFallback && play == PlayState.PLAYING && sink.streamed()

    /** On the ticks: a streamed track that has waited [STREAM_STALL_MS] for data while it should play is a PC that
     *  does not serve it — the stream's open, or a stall mid-song. */
    private fun watchStream(now: Long) {
        if (!(streamWanted() && sink.waitingForData())) { waitingSince = 0; return }
        if (waitingSince == 0L) { waitingSince = now; return }
        if (now - waitingSince >= STREAM_STALL_MS) streamStalled("no data from the PC for ${(now - waitingSince) / 1000} s")
    }

    /** The current streamed track cannot be served: the library stops trying and the automatic switch decides. */
    private fun streamStalled(why: String) {
        waitingSince = 0
        val t = engine.current?.track
        libraryStalled(if (t != null) "'${t.title}': $why" else why, resumeAt = positionMs())
    }

    /** The library's place is kept and its player paused (a switch away from it). */
    private fun pauseLibrary() {
        if (play != PlayState.PLAYING) return
        lastPos = positionMs()
        try { sink.pause() } catch (e: Exception) { Log.w("player", "library pause at the switch: ${e.message}") }
        play = PlayState.PAUSED
    }

    // ------------------------------------------------------------------ Spotify (verdicts 3/20/25)
    /** The subclass's Spotify session, when it has one. */
    protected open fun spotifyCmd(cmd: String) { emit(PlayerEvent.Error("Spotify is not available on this host")) }
    /** Start (or resume) Spotify: null when it was started, else the reason it was not (M5: [SPOTIFY_NO_GRANT],
     *  [SPOTIFY_NOT_STARTED]). */
    protected open fun spotifyStart(): String? = SPOTIFY_NOT_STARTED

    override fun setBackend(b: Backend) = post {
        if (b == curBackend) return@post
        if (b == Backend.SPOTIFY) {
            if (play == PlayState.PLAYING) pause()
            spotifyStart()?.let { why ->
                decided("Spotify's start refused: $why")
                emit(PlayerEvent.Error(why)); return@post
            }
            curBackend = Backend.SPOTIFY
            spotifyAuto = false
            decided("switched to Spotify (chosen)")
            emit(PlayerEvent.BackendChanged(Backend.SPOTIFY, automatic = false))
        } else {
            spotifyCmd("pause")
            curBackend = Backend.LIBRARY
            spotifyAuto = false
            decided("back to the PC library (chosen)")
            emit(PlayerEvent.BackendChanged(Backend.LIBRARY, automatic = false))
        }
        changed()
    }

    override fun backToPc() = setBackend(Backend.LIBRARY)

    /** The library backend cannot continue (a track that is not cached and a PC that does not serve it): switch
     *  to Spotify automatically when the setting allows. Called only for a track that is playing or asked to (M3).
     *  The library stops where it was ([resumeAt]): a later play opens the track again there, which a player
     *  left in its failed state would not. */
    protected fun libraryStalled(why: String, resumeAt: Long = positionMs()) {
        if (curBackend == Backend.SPOTIFY) return
        decided("stall: $why")
        if (!curSpotifyFallback) { emit(PlayerEvent.Error("PC unreachable and no cached track — $why")); return }
        if (play == PlayState.PLAYING) {
            try { sink.stop() } catch (e: Exception) { Log.w("player", "library stop at the stall: ${e.message}") }
            play = PlayState.STOPPED
        }
        lastPos = resumeAt
        spotifyStart()?.let { refused ->
            decided("Spotify's start refused: $refused")
            problem = refused
            emit(PlayerEvent.Error("PC unreachable — $refused"))
            changed(); return
        }
        curBackend = Backend.SPOTIFY
        spotifyAuto = true
        decided("switched to Spotify (automatic): $why")
        emit(PlayerEvent.BackendChanged(Backend.SPOTIFY, automatic = true))
        changed()
    }

    /** Spotify did not start after a start that was dispatched (the cold start's connection was refused): the
     *  switch is undone and the reason said (M5). */
    protected fun spotifyStartFailed(why: String) {
        decided("Spotify's start refused: $why")
        if (curBackend == Backend.SPOTIFY) {
            curBackend = Backend.LIBRARY
            spotifyAuto = false
            decided("back to the PC library (Spotify did not start)")
        }
        emit(PlayerEvent.Error(why))
        changed()
    }

    /** M4: Spotify's media session as the phone reports it. When it starts PLAYING while the library is the
     *  backend — a Spotify he started himself — the window follows it: a deliberate choice, no notice. */
    protected fun onSpotifySession(playing: Boolean) {
        val started = playing && !spotifyWasPlaying
        spotifyWasPlaying = playing
        if (!started || curBackend != Backend.LIBRARY) return
        pauseLibrary()
        curBackend = Backend.SPOTIFY
        spotifyAuto = false
        decided("switched to Spotify (followed: its session started playing on the phone)")
        changed()
    }

    // ------------------------------------------------------------------ persistence
    /** P6 item 3: the queue list [persist] last built its array from, and that array (immutable). The caller's thread
     *  only — the shell loop's save; one reference, so a reader never pairs one call's list with another's array. */
    @Volatile private var persistedQueue: Pair<List<QueueEntry>, kotlinx.serialization.json.JsonArray>? = null

    /** From the CACHED snapshot: the shell loop saves while the player's
     *  thread may be reordering the engine's list. */
    override fun persist(): JsonObject = buildJsonObject {
        val s = cached
        // P6 item 3 (the loop-cost sweep's LC-6, step 1): the queue array is rebuilt only when the list differs from
        // the one it was built from. The 10 s tick makes a new snapshot list of the same entries, so the key is the
        // list's equality (data classes), not its identity; the pair is one reference, read and written once here.
        val kept = persistedQueue?.takeIf { it.first == s.queue }
            ?: (s.queue to QueueEngine.queueJsonOf(s.queue)).also { persistedQueue = it }
        put("engine", QueueEngine.jsonOf(s.queue, s.index, s.mode, s.label, queueJson = kept.second))
        put("play", s.play.name)           // the truth for the mirror; a RESTORE never auto-plays (see restore)
        put("posMs", s.posMs)
        put("posAt", s.posAtMs)
        put("backend", curBackend.name)
        put("spotifyAuto", spotifyAuto)
        put("volume", volume)
        put("holdVolume", curHold)
        put("profile", curProfile.name)
        put("prefetch", prefetchN)
        put("spotifyFallback", curSpotifyFallback)
        put("output", curOutput)
        put("outputName", curOutputName)
        put("outputKind", curOutputKind)
    }

    override fun restore(o: JsonObject) = post {
        try {
            (o["engine"] as? JsonObject)?.let { engine.fromJson(it) }
            lastPos = o["posMs"]?.jsonPrimitive?.longOrNull ?: 0L
            curBackend = o["backend"]?.jsonPrimitive?.contentOrNull?.let { n -> Backend.entries.firstOrNull { it.name == n } } ?: Backend.LIBRARY
            spotifyAuto = o["spotifyAuto"]?.jsonPrimitive?.booleanOrNull ?: false
            // the media stream's level is the PHONE's truth, read from the sink
            // at start — a record's stale value would make the first observed
            // level look like a limiter drop (review 2026-09-02)
            curHold = o["holdVolume"]?.jsonPrimitive?.booleanOrNull ?: true
            curProfile = o["profile"]?.jsonPrimitive?.contentOrNull?.let { AudioProfile.parse(it) } ?: AudioProfile.DEFAULT
            prefetchN = o["prefetch"]?.jsonPrimitive?.intOrNull ?: 3
            curSpotifyFallback = o["spotifyFallback"]?.jsonPrimitive?.booleanOrNull ?: true
            curOutput = o["output"]?.jsonPrimitive?.contentOrNull ?: Output.AUTO
            // the STABLE half of the identity: Android's device id is a
            // per-connection handle and is worthless a session later
            curOutputName = o["outputName"]?.jsonPrimitive?.contentOrNull ?: ""
            curOutputKind = o["outputKind"]?.jsonPrimitive?.contentOrNull ?: ""
            // NEVER auto-play on boot: a restored queue is STAGED (a tap or Play starts it)
            play = PlayState.STOPPED
            onRestored()
            changed()
        } catch (e: Exception) {
            Log.e("player", "player record not restored", e)
        }
    }

    /** The subclass re-applies sink-side settings after a restore. */
    protected open fun onRestored() {}

    override fun addListener(l: MusicPlayer.Listener) {
        if (!listeners.addIfAbsent(l)) return
        try { l.state(state) } catch (e: Exception) { Log.e("player", "listener", e) }
    }
    override fun removeListener(l: MusicPlayer.Listener) { listeners.remove(l) }

    private fun fail(what: String, e: Exception) {
        Log.e("player", "$what failed", e)
        emit(PlayerEvent.Error("$what failed: ${e.message}"))
    }

    companion object {
        const val PREV_RESTART_MS = 3_000L
        const val RADIO_BATCH = 10
        /** At or below this the media stream is not going to be heard: an
         *  8 % session is what put this here (2026-09-02). */
        const val QUIET_PCT = 10
        /** max(3 volume steps ≈ 20 % of a 15-step stream, 25 % of range). */
        const val LIMITER_DROP = 25
        const val RESET_MAX = 3
        const val RESET_WINDOW_MS = 10 * 60_000L
        /** The supervision of a network stream (M1): a streamed track that has waited this long for data while it
         *  should play is a PC that does not serve it. Outside the radio, render and input paths. */
        const val STREAM_STALL_MS = 15_000L
        /** Why Spotify did not start (M5), in the notice's words. */
        const val SPOTIFY_NO_GRANT = "Spotify could not start: notification access is not granted on the phone"
        const val SPOTIFY_NOT_STARTED = "Spotify did not start — open it once on the phone"
    }
}
