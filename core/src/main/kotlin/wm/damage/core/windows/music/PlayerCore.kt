package wm.damage.core.windows.music

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import wm.damage.core.app.Draft
import wm.damage.core.app.SourceListener
import wm.damage.core.app.WindowSource
import wm.damage.core.app.WindowState
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
        /** S1-4: the host's end (the PC's player process is stopped by its host's shutdown hook, not here). */
        fun close() {}
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
        posMs = positionMs(), posAtMs = clock(), durMs = if (curBackend == Backend.LIBRARY && !offHost()) sink.durationMs().takeIf { it > 0 } ?: (engine.current?.track?.durMs?.toLong() ?: 0L) else if (curBackend == Backend.LIBRARY) (engine.current?.track?.durMs?.toLong() ?: 0L) else (spotifyNow?.durMs ?: 0L),
        mode = engine.mode, volume = if (offHost()) askedVol ?: recNow?.vol ?: volume else volume, boost = curBoost,
        output = shownOutput(), outputs = safeOutputs(),
        pcLink = pcLink, sleep = curSleep, holdVolume = curHold, profile = curProfile, label = engine.label,
        spotify = spotifyNow, spotifyAuto = spotifyAuto, problem = problem, listenerGranted = listenerGranted,
    )

    private fun safeOutputs(): List<Output> = try { hostOutputs() } catch (e: Exception) { listOf(Output.AUTO_OUTPUT) }

    /** The Output menu's rows: the phone's routes (Auto first), then the PC once this player is on the record (S1-4,
     *  ruling 8). The PC's player lists the phone, then its one device (`PcMusicPlayer`). */
    protected open fun hostOutputs(): List<Output> =
        listOf(Output.AUTO_OUTPUT) + sink.outputs().filter { it.id != Output.AUTO } + (if (bound) listOf(Output.PC_OUTPUT) else emptyList())

    /** The output shown: this host's own while it plays, the other host's output id while that one does. */
    private fun shownOutput(): String = if (!offHost()) curOutput else if (out.host == MusicCore.PC) Output.PC else Output.PHONE

    @Volatile private var cached: PlayerState = PlayerState()
    override val state: PlayerState get() = cached

    protected fun changed() {
        writeRecord()
        cached = snapshot()
        for (l in listeners) try { l.state(cached) } catch (e: Exception) { Log.e("player", "state listener", e) }
    }

    protected fun emit(e: PlayerEvent) {
        for (l in listeners) try { l.event(e) } catch (ex: Exception) { Log.e("player", "event listener", ex) }
    }

    override fun positionMs(): Long {
        if (offHost()) return recNow?.posAt(clock()) ?: lastPos           // S1-4: the playing host's place, extrapolated
        if (curBackend == Backend.SPOTIFY) return spotifyPos()
        return if (play == PlayState.PLAYING) {
            val p = try { sink.positionMs() } catch (e: Exception) { lastPos }
            lastPos = p; lastPosAt = clock(); p
        } else lastPos
    }

    protected open fun spotifyPos(): Long = 0L

    // ------------------------------------------------------------------ transport
    /** S1-4: what the sink opens for [t] — the phone's stream URL; the PC reads a local path (`PcMusicPlayer`). */
    protected open fun mediaFor(t: TrackRef): String = library.streamUrl(t.id, curProfile)
    private fun currentUrl(t: TrackRef): String = mediaFor(t)

    /** Open the current entry (a real open ends the previous track's history row). */
    protected fun openCurrent(startMs: Long, playNow: Boolean, cause: String): Boolean {
        val e = engine.current ?: run { Log.w("player", "openCurrent($cause): empty queue"); return false }
        // S1-4 (§3.4): one output — a call that makes sound runs only on the host `out` names, or in its own take-over
        if (playNow && !mayPlay()) { refuseSound("open ($cause)"); return false }
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
            return false
        } catch (ex: Exception) {
            Log.e("player", "open of track ${t.id} failed", ex)
            problem = "open failed: ${ex.message}"
            emit(PlayerEvent.Error("open failed: ${ex.message}"))
            changed(); return false
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
        return true
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
        if (routed("play")) return@post
        if (curBackend == Backend.SPOTIFY) { if (mayPlay()) spotifyCmd("play") else refuseSound("Spotify play"); return@post }
        when (play) {
            PlayState.PLAYING -> {}
            PlayState.PAUSED -> {
                if (!mayPlay()) { refuseSound("play"); return@post }
                try { sink.play() } catch (e: Exception) { fail("play", e); return@post }; play = PlayState.PLAYING; warnIfQuiet(); changed()
            }
            PlayState.STOPPED -> if (!engine.isEmpty) openCurrent(lastPos, true, "play") else emit(PlayerEvent.Error("nothing queued"))
        }
    }

    override fun pause() = post {
        if (routed("pause")) return@post
        if (curBackend == Backend.SPOTIFY) { spotifyCmd("pause"); return@post }
        if (play != PlayState.PLAYING) return@post
        lastPos = positionMs()
        try { sink.pause() } catch (e: Exception) { fail("pause", e); return@post }
        play = PlayState.PAUSED
        changed()
    }

    override fun toggle() = post {
        if (routed("toggle")) return@post
        if (curBackend == Backend.SPOTIFY) { if (mayPlay()) spotifyCmd("toggle") else refuseSound("Spotify toggle"); return@post }
        when (play) {
            PlayState.PLAYING -> pause()
            PlayState.PAUSED -> play()
            PlayState.STOPPED -> if (!engine.isEmpty) openCurrent(lastPos, true, "toggle") else emit(PlayerEvent.Error("nothing queued — Ask or Browse first"))
        }
    }

    override fun next() = post {
        if (routed("next")) return@post
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
        nowWhy = QUEUE_ENDED
        changed()
    }

    override fun prev() = post {
        if (routed("prev")) return@post
        if (curBackend == Backend.SPOTIFY) { spotifyCmd("prev"); return@post }
        if (engine.isEmpty) return@post
        // Spotify-identical: ≥ 3 s in restarts the track; at the head restarts too
        if (positionMs() >= PREV_RESTART_MS || !engine.prev()) openCurrent(0, play != PlayState.STOPPED, "restart")
        else openCurrent(0, play != PlayState.STOPPED, "prev")
    }

    override fun stop() = post {
        if (routed("pause")) return@post         // S1-4: off the playing host, Stop pauses it (no stop verb on the door)
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
        if (routed("seek", obj("pos" to ms))) return@post
        if (curBackend == Backend.SPOTIFY || play == PlayState.STOPPED) return@post
        val dur = sink.durationMs().takeIf { it > 0 } ?: engine.current?.track?.durMs?.toLong() ?: Long.MAX_VALUE
        val v = ms.coerceIn(0, dur)
        try { sink.seekTo(v) } catch (e: Exception) { fail("seek", e); return@post }
        lastPos = v; lastPosAt = clock()
        writeNowIfActive()                        // S1-4: a seek moves the place without changing the state
        changed()
    }

    override fun seekBy(ms: Long) = post { seekTo(positionMs() + ms) }

    override fun playQueue(tracks: List<TrackRef>, startIndex: Int, mode: Mode, label: String) = post {
        if (tracks.isEmpty()) { emit(PlayerEvent.Error("nothing to play")); return@post }
        if (offHost()) {
            engine.set(tracks, startIndex, mode, label)          // the asker's ids, drawn at once (the first flush)
            send("playNow", obj("entries" to MusicCore.entriesJson(engine.entries), "index" to engine.index, "mode" to mode.name, "label" to label))
            changed(); return@post
        }
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
        if (routed("playFrom", obj("e" to qid))) return@post
        if (!engine.playFrom(qid)) { emit(PlayerEvent.Error("that row is gone")); return@post }
        pickGen++
        advanceAfterFill = null
        openCurrent(0, true, "play-from")
    }

    override fun playNext(tracks: List<TrackRef>) = post {
        if (tracks.isEmpty()) return@post
        val fresh = engine.insertNext(tracks)
        if (offHost()) { send("playNext", obj("entries" to MusicCore.entriesJson(fresh))); changed(); return@post }
        prefetchAhead()
        changed()
    }

    override fun append(tracks: List<TrackRef>) = post {
        if (tracks.isEmpty()) return@post
        val fresh = engine.append(tracks)
        if (offHost()) { send("add", obj("entries" to MusicCore.entriesJson(fresh))); changed(); return@post }
        prefetchAhead()
        changed()
    }

    override fun replace(tracks: List<TrackRef>, label: String) = playQueue(tracks, 0, engine.mode, label)

    override fun remove(qid: Long) = post {
        if (offHost()) { if (engine.remove(qid)) changed(); send("remove", obj("e" to qid)); return@post }
        if (!engine.remove(qid)) emit(PlayerEvent.Error("the current track cannot be removed"))
        else { prefetchAhead(); changed() }
    }

    override fun move(qid: Long, delta: Int) = post {
        if (offHost()) { if (engine.move(qid, delta)) changed(); send("moveEntry", obj("e" to qid, "delta" to delta)); return@post }
        if (engine.move(qid, delta)) { prefetchAhead(); changed() }
    }

    override fun clear() = post {
        if (offHost()) { engine.clear(); send("clear"); changed(); return@post }
        closeHistory("clear")
        try { sink.stop() } catch (e: Exception) { Log.w("player", "stop on clear: ${e.message}") }
        queueGen++
        engine.clear()
        play = PlayState.STOPPED
        lastPos = 0
        changed()
    }

    override fun shuffleRest() = post {
        if (offHost()) { send("shuffleRest"); return@post }            // the playing host shuffles; its `q` follows
        engine.shuffleRest(); prefetchAhead(); changed()
    }

    override fun setMode(mode: Mode) = post {
        if (offHost()) { send("mode", obj("mode" to mode.name)); return@post }
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
        if (offHost()) { askedVol = v; send("volume", obj("pct" to v)); changed(); return@post }   // shown as asked until `now` moves
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
        if (offHost() || host == MusicCore.PC) { if (v != 100) emit(PlayerEvent.Error("boost is not on the PC output")); return@post }
        curBoost = v
        try { sink.setBoostPct(v) } catch (e: Exception) { fail("boost", e) }
        if (v > 100 && volume >= 100) emit(PlayerEvent.BoostLoud(volume, v))
        changed()
    }

    override fun setOutput(id: String) = post {
        if (bound && moveOutput(id)) return@post
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

    override fun setSleep(s: Sleep) = post {
        if (offHost()) { send("sleep", obj("choice" to s.choice())); return@post }
        curSleep = s; changed()
    }
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
        if (takeoverFailed(detail)) return@post
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
            if (!mayPlay()) { refuseSound("Spotify"); return@post }
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
        if (!mayPlay()) { refuseSound("Spotify (a stall)"); changed(); return }
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

    // ------------------------------------------------------------------ the record (S1-4, the lean spec's §3.4, §3.5)
    /** A transport or queue verb for the host that plays, and its answer: null = taken, else the refusal in words with
     *  [unreachable] true when that host could not be reached at all (the phone's away rule reads it). */
    fun interface Remote {
        fun send(verb: String, args: JsonObject, done: (why: String?, unreachable: Boolean) -> Unit)
    }

    private var src: WindowSource? = null
    /** This player's host on the record: `phone` or `pc`. */
    protected var host: String = MusicCore.PHONE
        private set
    private var remote: Remote? = null
    @Volatile private var out = MusicCore.Out(MusicCore.PHONE, 0L)
    /** The record's `now` while another host plays (the place this host shows, extrapolated). */
    @Volatile private var recNow: MusicCore.Now? = null
    /** The volume asked here for the other host, shown until its `now` moves (this host's own level is untouched). */
    @Volatile private var askedVol: Int? = null
    private var lastQ: JsonObject? = null
    private var lastNowKey: List<Any?>? = null
    /** A stop's reason, written with the next `now`. */
    private var nowWhy: String? = null
    /** This host's take-over in flight (§3.5): the move's input time and who waits for its answer. */
    private class Takeover(val t: Long, val playing: Boolean, val done: (String?) -> Unit) { var written = false }
    private var takeover: Takeover? = null
    /** Since when the other host has not answered a verb (0: it answers): the words "PC not answering since 14:02". */
    private var remoteDownSince = 0L

    /** True once this player is on the record ([bind]); a player with none plays as before (tests, no working copy). */
    val bound: Boolean get() = src != null

    /** True when `out` names this host (or there is no record). */
    val isOutput: Boolean get() = src == null || out.host == host

    /** Off the playing host and not taking over: verbs go to the host that plays. */
    protected fun offHost(): Boolean = src != null && out.host != host && takeover == null

    /** §3.4's guard at the calls that make sound: `out` names this host, or its own take-over is in flight. */
    protected fun mayPlay(): Boolean = src == null || out.host == host || takeover != null

    private fun refuseSound(what: String) {
        decided("$what refused: the output is the ${out.host}")
        emit(PlayerEvent.Error("the music plays on the ${hostName(out.host)} — move the output here first"))
    }

    private fun hostName(h: String) = if (h == MusicCore.PC) "PC" else "phone"

    /**
     * Puts this player on the record ([source]: Music's source on this host's record), as [host], with [remote] the way
     * to the other host (the phone: its music channel to the PC; the PC: the relay to the phone). At a process start,
     * never on a gesture. The queue and place are loaded from `q` and `now`; while `out` names this host and `now` says
     * playing (the process ended while it played), `now` is written stopped at its place with the reason. Nothing here
     * makes sound.
     */
    fun bind(source: WindowSource, host: String, remote: Remote?) {
        this.host = host
        this.remote = remote
        source.addListener(object : SourceListener {
            override fun changed(state: WindowState, keys: Set<String>) = post { onRecord(state) }
        })
        post {
            src = source
            val st = source.state()
            out = MusicCore.outOf(st)
            val n = MusicCore.nowOf(st)
            MusicCore.queueOf(st)?.let { engine.load(it, n?.e) }
            lastQ = engine.recordJson()
            if (n != null) {
                lastPos = n.pos
                if (out.host == host && n.state != MusicCore.STOPPED && n.state != MusicCore.PAUSED) {
                    nowWhy = "stopped when the $host's player ended; Play resumes at ${Fmt.mmss(n.pos)}"
                    decided("the record said playing at this start: $nowWhy")
                    writeNow()
                } else if (out.host == host) lastNowKey = nowKey()
            }
            if (out.host != host) follow(st)
            changed()
        }
    }

    /** The record moved (the player's thread). */
    private fun onRecord(st: WindowState) {
        if (src == null) return                                   // the binding reads the state itself
        val o = MusicCore.outOf(st)
        val was = out
        out = o
        val tk = takeover
        if (tk != null) {
            when {
                o.host == host -> takeover = null                   // this host's `out` stands: it plays
                o.t > tk.t -> { takeover = null; stopHere("a later move (${hostName(o.host)}) stands"); if (!tk.written) tk.done("a later move stands") }
            }
            changed(); return
        }
        if (was.host == host && o.host != host) leave(o)
        if (o.host != host) follow(st)
        changed()
        recordMoved()
    }

    /** The record moved this player's state (the player's thread): a host whose ticks follow its own sink paces them
     *  for the other host's playing too, so the card's place moves while that host plays ([following]). */
    protected open fun recordMoved() {}

    /** Off the playing host while it plays: the place shown moves on ticks. */
    protected fun following(): Boolean = offHost() && play == PlayState.PLAYING

    /** §3.5 step 3: `out` left this host — a stop, not a pause; a move made while this host was out of reach is said. */
    private fun leave(o: MusicCore.Out) {
        val now = clock()
        val wasPlaying = play == PlayState.PLAYING
        stopHere("the output moved to the ${hostName(o.host)}")
        if (wasPlaying && now - o.t > MOVED_SAY_MS) movedAway(if (host == MusicCore.PC) "the PC output played until ${clockTime(now).take(5)}"
            else "music moved to the ${hostName(o.host)} output at ${clockTime(o.t).take(5)}")
    }

    private fun stopHere(why: String) {
        if (play != PlayState.STOPPED || curBackend == Backend.SPOTIFY) {
            lastPos = positionMs()
            closeHistory("moved")
            try { sink.stop() } catch (e: Exception) { Log.w("player", "stop at a move: ${e.message}") }
            play = PlayState.STOPPED
            decided("stopped: $why")
        }
    }

    /** A move made while this host was out of reach, said: the phone on the glasses; the PC raises its notice. */
    protected open fun movedAway(text: String) = emit(PlayerEvent.MovedAway(text))

    /** Off the playing host: the queue, the place and the state as the record has them; `now`'s moves become today's
     *  events, so the notices are today's. */
    private fun follow(st: WindowState) {
        val n = MusicCore.nowOf(st)
        val before = recNow
        val beforeQid = engine.current?.qid
        MusicCore.queueOf(st)?.let { engine.load(it, n?.e) } ?: n?.let { engine.playFrom(it.e) }
        recNow = n
        if (n == null) return
        play = when (n.state) { MusicCore.PLAYING, MusicCore.BUFFERING -> PlayState.PLAYING; MusicCore.PAUSED -> PlayState.PAUSED; else -> PlayState.STOPPED }
        if (n != before) askedVol = null
        lastPos = n.pos
        val e = engine.current
        if (e != null && e.qid != beforeQid && n.playing && before != null) emit(PlayerEvent.TrackChange(e.track, engine.index, engine.size))
        if (n.why == QUEUE_ENDED && before?.why != QUEUE_ENDED && before != null) emit(PlayerEvent.QueueEnd)
    }

    /** On the playing host, after each change of the player: `q` when the queue differs from the last written, `now`
     *  when the entry, the state, the volume, sleep or boost differs (never on a tick: the place alone moves nothing). */
    private fun writeRecord() {
        if (src == null || out.host != host || takeover != null) return
        val q = engine.recordJson()
        if (q != lastQ) { lastQ = q; act(MusicCore.A_QUEUE, MusicCore.Q, q) }
        if (nowKey() != lastNowKey || nowWhy != null) writeNow()
    }

    private fun writeNowIfActive() { if (src != null && out.host == host && takeover == null) writeNow() }

    private fun nowState(): String = when {
        curBackend == Backend.SPOTIFY -> MusicCore.SPOTIFY
        play == PlayState.PLAYING && (try { sink.waitingForData() } catch (e: Exception) { false }) -> MusicCore.BUFFERING
        play == PlayState.PLAYING -> MusicCore.PLAYING
        play == PlayState.PAUSED -> MusicCore.PAUSED
        else -> MusicCore.STOPPED
    }

    private fun nowKey(): List<Any?> = listOf(engine.current?.qid, nowState(), volume, curSleep.choice(), curBoost)

    private fun writeNow() {
        val e = engine.current ?: return
        lastNowKey = nowKey()
        val n = MusicCore.Now(e.qid, positionMs(), clock(), nowState(), volume,
            curSleep.takeIf { it.kind != Sleep.Kind.OFF }?.choice(), curBoost.takeIf { it != 100 }, nowWhy)
        nowWhy = null
        act(MusicCore.A_NOW, MusicCore.NOW, n.json())
    }

    private fun writeOut(t: Long) = act(MusicCore.A_OUT, MusicCore.OUT, MusicCore.Out(host, t).json(), at = t)

    private fun act(action: String, item: String, value: kotlinx.serialization.json.JsonElement, at: Long? = null) {
        val s = src ?: return
        try {
            s.act(Draft(action, listOf(item), value, at = at))
        } catch (e: Exception) {
            Log.e("player", "the record did not take $action", e)
        }
    }

    /** True when [verb] went to the host that plays (this host does not play and takes over nothing). */
    private fun routed(verb: String, args: JsonObject = JsonObject(emptyMap())): Boolean {
        if (!offHost()) return false
        send(verb, args)
        return true
    }

    private fun send(verb: String, args: JsonObject = JsonObject(emptyMap())) {
        val r = remote ?: run { emit(PlayerEvent.Error("the ${hostName(out.host)} plays and is not reachable from here")); return }
        r.send(verb, args) { why, unreachable -> post { answered(verb, args, why, unreachable) } }
    }

    /** The other host's answer to a relayed verb (the player's thread). The phone's away rule (answer 2): while the PC
     *  does not answer, a verb that would start sound takes the output; any other says since when. */
    private fun answered(verb: String, args: JsonObject, why: String?, unreachable: Boolean) {
        if (why == null) { remoteDownSince = 0; return }
        if (unreachable && remoteDownSince == 0L) remoteDownSince = clock()
        val starts = verb in setOf("play", "playNow", "playFrom") || (verb == "toggle" && play != PlayState.PLAYING)
        if (unreachable && host == MusicCore.PHONE && starts && offHost()) {
            val n = recNow
            val (e, p) = when (verb) {
                "playFrom" -> (MusicCore.long(args["e"]) ?: return) to 0L
                "playNow" -> (engine.current?.qid ?: return) to 0L
                else -> placeOf(n)
            }
            decided("the ${hostName(out.host)} is not answering: Play on the phone takes the output")
            takeOver(e, p, true, clock()) { w -> if (w != null) emit(PlayerEvent.Error(w)) }
            return
        }
        val words = if (unreachable) "${hostName(out.host)} not answering since ${clockTime(remoteDownSince).take(5)}" else why
        emit(PlayerEvent.Error(words))
    }

    /** The place to start from when this host takes over without the asker's exact place: `now` extrapolated, and
     *  past the entry's end the next entry from its start (§3.5). */
    private fun placeOf(n: MusicCore.Now?): Pair<Long, Long> {
        val cur = engine.current ?: return 0L to 0L
        if (n == null) return cur.qid to lastPos
        val p = n.posAt(clock())
        val i = engine.indexOf(n.e).takeIf { it >= 0 } ?: return cur.qid to 0L
        val dur = engine.entries[i].track.durMs.toLong()
        return if (dur > 0 && p >= dur && i + 1 < engine.size) engine.entries[i + 1].qid to 0L else n.e to p
    }

    /** §3.5 for the output id chosen in the Output menu: true when it was a move (or nothing to do). */
    private fun moveOutput(id: String): Boolean {
        val target = when {
            id == Output.PC -> MusicCore.PC
            id == Output.PHONE -> MusicCore.PHONE
            host == MusicCore.PHONE && out.host == MusicCore.PC -> {      // a phone route chosen while the PC plays
                try { if (sink.setOutput(id)) { curOutput = id; val o = sink.outputs().firstOrNull { it.id == id }; curOutputName = o?.name ?: ""; curOutputKind = o?.kind ?: "" } }
                catch (e: Exception) { Log.w("player", "route $id: ${e.message}") }
                MusicCore.PHONE
            }
            else -> return false
        }
        move(target, null, clock()) { why -> if (why != null) emit(PlayerEvent.Error(why)) }
        return true
    }

    /**
     * A move of the output to [to] (§3.5), asked here at [t]. [args]: the asker's exact place (`e`, `pos`, `playing`)
     * when it was sent by the host that played, else null. The target takes over; when this host is the one that
     * plays, it sends its exact place to the target and plays on until `out` moves. [done] gets null or the words.
     */
    fun move(to: String, args: JsonObject?, t: Long, done: (String?) -> Unit) = post {
        when {
            to == host && out.host == host -> { writeOut(t); done(null) }   // the same output: `out` again, a newer time
            to == host -> {
                val e = args?.let { MusicCore.long(it["e"]) }
                val (qe, p) = if (e != null) e to (MusicCore.long(args["pos"]) ?: 0L) else placeOf(recNow)
                val playing = args?.let { MusicCore.bool(it["playing"]) } ?: (recNow?.playing ?: false)
                takeOver(qe, p, playing, t, done)
            }
            out.host == host -> {
                val e = engine.current ?: run { done("nothing is queued to move"); return@post }
                val r = remote ?: run { done("the ${hostName(to)} is not reachable from here"); return@post }
                r.send("move", obj("to" to to, "e" to e.qid, "pos" to positionMs(), "playing" to (play == PlayState.PLAYING), "t" to t)) { why, _ -> done(why) }
            }
            else -> {
                val r = remote ?: run { done("the ${hostName(to)} is not reachable from here"); return@post }
                r.send("move", obj("to" to to, "t" to t)) { why, _ -> done(why) }
            }
        }
    }

    /** §3.5 steps 1–2 on the target: open [e] at [p], playing or staged; `out` and `now` are written when it plays
     *  ([onStarted]) or, staged, once opened; a failure stops here, `out` unchanged, and says why. */
    protected fun takeOver(e: Long, p: Long, playing: Boolean, t: Long, done: (String?) -> Unit) {
        if (!engine.playFrom(e)) { done("that entry is not in the queue on the ${hostName(host)}"); return }
        if (curBackend == Backend.SPOTIFY) { curBackend = Backend.LIBRARY; spotifyAuto = false }
        val tk = Takeover(t, playing, done)
        takeover = tk
        if (!openCurrent(p, playing, "move")) {
            takeover = null
            stopHere("the take-over did not open")
            done(problem.ifEmpty { "the ${hostName(host)} could not open the track" })
            changed(); return
        }
        if (!playing || !startsAsync) started(tk)
    }

    /** True when the sink reports playback started later ([onStarted]): the PC's player and ExoPlayer. */
    protected open val startsAsync: Boolean = false

    /** The sink plays now ([playing]) or has opened staged: a take-over in flight writes `out` then `now`. Any thread. */
    fun onStarted(playing: Boolean) = post {
        val tk = takeover ?: return@post
        if (!tk.written && (playing || !tk.playing)) started(tk)
    }

    private fun started(tk: Takeover) {
        tk.written = true
        writeOut(tk.t)
        lastQ = engine.recordJson()
        act(MusicCore.A_QUEUE, MusicCore.Q, lastQ!!)
        // the take-over stays until the record shows this host's `out` (or a later one): a write of the old host's that
        // lands first is not read as the output leaving this host
        writeNow()
        decided("took the output at ${Fmt.mmss(positionMs())}")
        tk.done(null)
        changed()
    }

    private fun takeoverFailed(detail: String): Boolean {
        val tk = takeover?.takeIf { !it.written } ?: return false
        takeover = null
        stopHere("the take-over failed: $detail")
        emit(PlayerEvent.Error(detail))
        tk.done(detail)
        changed()
        return true
    }

    /**
     * A verb from another surface for this host (the relay's `cmd` on the phone, `MusicProgram` on the PC; §3.2):
     * applied while this host plays, else refused. A verb naming an entry this host does not hold is refused in words.
     */
    fun command(verb: String, args: JsonObject, done: (String?) -> Unit) = post {
        if (verb == "move") {
            val to = MusicCore.text(args["to"]) ?: run { done("a move names its output"); return@post }
            move(to, args.takeIf { it["e"] != null }, MusicCore.long(args["t"]) ?: clock(), done)
            return@post
        }
        if (src != null && out.host != host) { done("the ${hostName(host)} is not the output now"); return@post }
        val e = MusicCore.long(args["e"])
        if (verb in setOf("playFrom", "remove", "moveEntry") && (e == null || engine.indexOf(e) < 0)) {
            done("that entry is not in the queue on the ${hostName(host)}"); return@post
        }
        fun entries(): List<QueueEntry> = MusicCore.queueOf(JsonObject(mapOf("entries" to (args["entries"] ?: JsonArray(emptyList()))))).entries
        when (verb) {
            "play" -> play()
            "pause" -> pause()
            "toggle" -> toggle()
            "next" -> next()
            "prev" -> prev()
            "seek" -> seekTo(MusicCore.long(args["pos"]) ?: 0L)
            "playFrom" -> playFrom(e!!)
            "volume" -> setVolume(MusicCore.int(args["pct"]) ?: volume, "relayed")
            "sleep" -> setSleep(Sleep.fromChoice(MusicCore.text(args["choice"]) ?: "off", clock()))
            "boost" -> setBoost(MusicCore.int(args["pct"]) ?: 100)
            "mode" -> Mode.entries.firstOrNull { it.name == MusicCore.text(args["mode"]) }?.let { setMode(it) }
            "add" -> { engine.appendEntries(entries()); prefetchAhead(); changed() }
            "playNext" -> { engine.insertNextEntries(entries()); prefetchAhead(); changed() }
            "playNow" -> {
                val es = entries()
                if (es.isEmpty()) { done("nothing to play"); return@post }
                if (curBackend == Backend.SPOTIFY && !spotifyAuto) curBackend = Backend.LIBRARY
                queueGen++; advanceAfterFill = null
                engine.setEntries(es, MusicCore.int(args["index"]) ?: 0,
                    Mode.entries.firstOrNull { it.name == MusicCore.text(args["mode"]) } ?: engine.mode, MusicCore.text(args["label"]) ?: "")
                openCurrent(0, true, "new-queue")
            }
            "moveEntry" -> move(e!!, MusicCore.int(args["delta"]) ?: 0)
            "remove" -> remove(e!!)
            "clear" -> clear()
            "shuffleRest" -> shuffleRest()
            else -> { done("no such verb \"$verb\""); return@post }
        }
        done(null)
    }

    /** The host's orderly end (the PC's shutdown hook): `now` written with the exact place and stopped. */
    fun endOrderly(why: String) = post {
        if (src == null || out.host != host || play == PlayState.STOPPED) return@post
        lastPos = positionMs()
        play = PlayState.STOPPED
        nowWhy = "$why; Play resumes at ${Fmt.mmss(lastPos)}"
        writeNow()
        decided("ended in order: $nowWhy")
    }

    /** The sink's program ended with nobody asking (the PC's player process): stopped at its place, said. */
    protected fun sinkExited(detail: String) = post {
        lastPos = positionMs()
        if (takeoverFailed("the PC player ended: $detail")) return@post
        if (play == PlayState.STOPPED) return@post
        play = PlayState.STOPPED
        problem = "the player ended: $detail"
        nowWhy = "the player ended ($detail); Play resumes at ${Fmt.mmss(lastPos)}"
        decided(nowWhy!!)
        emit(PlayerEvent.Error("the PC player ended: $detail"))
        changed()
    }

    private fun obj(vararg kv: Pair<String, Any?>): JsonObject = buildJsonObject {
        for ((k, v) in kv) when (v) {
            null -> {}
            is String -> put(k, v)
            is Int -> put(k, v)
            is Long -> put(k, v)
            is Boolean -> put(k, v)
            is kotlinx.serialization.json.JsonElement -> put(k, v)
            else -> put(k, v.toString())
        }
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
        // S1-4: on the record the queue is `q` (§3.4); only a player with no record keeps it here
        if (!bound) put("engine", QueueEngine.jsonOf(s.queue, s.index, s.mode, s.label, queueJson = kept.second))
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
            // S1-4: on the record the queue and place are `q` and `now`, loaded at the binding, never here
            if (!bound) {
                (o["engine"] as? JsonObject)?.let { engine.fromJson(it) }
                lastPos = o["posMs"]?.jsonPrimitive?.longOrNull ?: 0L
            }
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
            if (!bound) play = PlayState.STOPPED
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
        /** S1-4 (§3.5 step 3): a move whose input is older than this at the stop was made while this host was away. */
        const val MOVED_SAY_MS = 5_000L
        /** `now.why` at the queue's end (another host's presenter raises today's "queue ended" from it). */
        const val QUEUE_ENDED = "queue ended"
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
