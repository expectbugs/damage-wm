package wm.damage.desktop.music

import java.util.Locale
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import wm.damage.core.util.Log
import wm.damage.core.windows.music.Output
import wm.damage.core.windows.music.PlayerCore
import wm.damage.core.windows.music.TrackRef

/**
 * [PlayerCore.Sink] over the PC's player program (S1-4 §3.6, lane P). Called on the player's thread; nothing here
 * waits for an answer, and only [open] and [play] start the program ([MpvLink.ensure]) — a volume, seek, pause or
 * stop with no program running changes only what the next start uses.
 *
 * The forms, as `man mpv` 0.41 has them and the hand run of 2026-11-19 answered them:
 * - open = `loadfile <path> replace -1 start=<s>,pause=<yes|no>` (the answer carries `playlist_entry_id`);
 * - play / pause = `set_property pause`; seek = `seek <s> absolute`; volume = mpv's own `volume` property;
 * - position and duration = the observed `time-pos` and `duration` (about 13 changes a second while playing), taken
 *   only once the entry last opened has both its answer and its `start-file`: the old file's last changes still
 *   arrive after a replace, and a track change must not carry the previous track's place;
 * - paused or not is what was last asked: mpv does not pause by itself here, and its per-file `pause` is put back
 *   when a file ends, which would arrive after the next open;
 * - `end-file` `eof` → [Events.ended], `error` → [Events.failed] with its `file_error`; `stop` (a replace, a stop)
 *   is ours and says nothing. An `end-file` of any entry but the one last opened is stale and dropped: a replace's
 *   `stop` arrives after the new entry's answer, and an `eof` racing a skip would otherwise advance twice;
 * - `playback-restart` → [Events.restarted]: it fires after every open (a staged one too, paused) and every seek.
 * - Play with nothing loaded (the program ended, or the open failed) opens the last file again at the last place.
 */
class MpvSink(
    private val link: MpvLink,
    private val events: Events,
    /** `musicPcDevice` as he named it, for the one output's name. */
    private val device: String? = null,
    /** The monotonic clock [positionMs] ages the last observed place by (nanoseconds; tests give their own). */
    private val nanos: () -> Long = System::nanoTime,
) : PlayerCore.Sink, MpvProcess.Owner {

    /** What the sink reports to the host (K wires these to [PlayerCore]). Called off the player's thread. */
    interface Events {
        /** The current file played to its end → `PlayerCore.onEnded()`. */
        fun ended()
        /** The current file cannot play, or a command that loads one was refused → `PlayerCore.onSinkError(detail)`. */
        fun failed(detail: String)
        /** The open file restarted at its place (after an open or a seek); [playing] = sound is coming (§3.5 step 2). */
        fun restarted(playing: Boolean)
        /** The program ended with nobody asking: said (log, a `music` note, `now.why`), never restarted by itself. */
        fun exited(detail: String)
    }

    @Volatile private var ipc: MpvIpc? = null
    /** The entry the last `loadfile` made: [PENDING] until its answer, [NONE] with nothing open. */
    @Volatile private var entry = NONE
    /** The entry of the last `start-file`. */
    @Volatile private var fileEntry = NONE
    @Volatile private var opens = 0L
    @Volatile private var loaded = false
    @Volatile private var paused = true
    @Volatile private var posMs = 0L
    /** When [posMs] was last observed from `time-pos` (on [nanos]); null after an open, a seek or a pause until the next
     *  observation — only an observed place is aged. */
    @Volatile private var posSeenNs: Long? = null
    @Volatile private var durMs = 0L
    @Volatile private var wantVolume = 100
    /** The last file opened, kept across an exit: the next Play reopens it at [posMs]. */
    @Volatile private var lastPath: String? = null
    @Volatile private var lastTrack: TrackRef? = null

    private fun live(): MpvIpc? = ipc?.takeIf { it.open }

    /** The running program, started if none runs; observation and volume set on a fresh one. */
    private fun connected(): MpvIpc {
        live()?.let { return it }
        val c = link.ensure(this)
        if (c !== ipc) {
            ipc = c
            for (p in OBSERVED) c.observe(p)
            if (wantVolume != 100) c.command("set_property", "volume", wantVolume) { warnIfRefused("volume", it) }
        }
        return c
    }

    override fun open(url: String, startMs: Long, playWhenReady: Boolean, track: TrackRef) {
        val c = connected()
        lastPath = url; lastTrack = track
        posMs = startMs; posSeenNs = null; durMs = track.durMs.toLong(); paused = !playWhenReady; loaded = true
        val mine = ++opens
        entry = PENDING
        c.command("loadfile", url, "replace", -1, "start=${secs(startMs)},pause=${if (playWhenReady) "no" else "yes"}") { a ->
            if (mine != opens) return@command
            if (a.ok) {
                entry = (a.data as? JsonObject)?.get("playlist_entry_id")?.let { (it as? JsonPrimitive)?.longOrNull } ?: run {
                    Log.w(TAG, "loadfile answered without a playlist entry: ${a.data}"); NONE
                }
            } else {
                entry = NONE; loaded = false
                // the connection's end answers every pending command: the exit says the true reason (S1-4 review 2, F3)
                if (c.open) events.failed("the PC player refused the file: ${a.error}")
            }
        }
    }

    override fun play() {
        val c = live()
        if (c == null || !loaded) {
            // The program ended or the open failed (said then): the last file again at the last place, playing.
            val path = lastPath ?: throw IllegalStateException("nothing is open on the PC player")
            open(path, posMs, true, lastTrack ?: TrackRef(0, path))
            return
        }
        paused = false
        c.command("set_property", "pause", false) { if (!it.ok && c.open) events.failed("the PC player did not play: ${it.error}") }
    }

    override fun pause() {
        paused = true
        posSeenNs = null                  // a resume ages the place from the next time-pos, not from the sample before the pause
        live()?.command("set_property", "pause", true) { warnIfRefused("pause", it) }
    }

    override fun stop() {
        loaded = false; entry = NONE; lastPath = null; lastTrack = null
        live()?.command("stop") { warnIfRefused("stop", it) }
    }

    override fun seekTo(ms: Long) {
        posMs = ms; posSeenNs = null
        if (loaded) live()?.command("seek", ms / 1000.0, "absolute") { warnIfRefused("seek", it) }
    }

    /**
     * Music's motion update, stage L (`MM-lean-spec.md` §3): the last observed `time-pos` aged by the time since it came,
     * while playing — mpv says it about 13 times a second, so the bare value is up to ≈ 77 ms behind, and the glasses'
     * bar and lyrics are planned from this place. Aged by at most [AGE_MAX_MS]: a stall (buffering) does not run on.
     */
    override fun positionMs(): Long {
        val seen = posSeenNs ?: return posMs
        if (paused || !loaded) return posMs
        return posMs + ((nanos() - seen) / 1_000_000L).coerceIn(0L, AGE_MAX_MS)
    }
    override fun durationMs(): Long = durMs

    override fun setVolumePct(pct: Int) {
        wantVolume = pct
        live()?.command("set_property", "volume", pct) { warnIfRefused("volume", it) }
    }

    override fun volumePct(): Int = wantVolume

    /** The boost is the phone's (verdict 15); 100 (off) is accepted, anything else refused in words. */
    override fun setBoostPct(pct: Int) {
        if (pct != 100) throw UnsupportedOperationException("boost is not on the PC output")
    }

    override fun outputs(): List<Output> = listOf(Output(OUTPUT_ID, device?.removePrefix(MpvProcess.DEVICE_PREFIX) ?: "PC default output", "pc"))
    override fun setOutput(id: String): Boolean = id == OUTPUT_ID
    override fun prefetch(urls: List<Pair<Int, String>>) {}
    override fun isPlaying(): Boolean = loaded && !paused && live() != null
    override fun streamed(): Boolean = false
    override fun waitingForData(): Boolean = false

    // ------------------------------------------------------------------ the program's side (its reading thread)

    override fun event(name: String, msg: JsonObject) {
        when (name) {
            "end-file" -> {
                val id = (msg["playlist_entry_id"] as? JsonPrimitive)?.longOrNull
                if (entry == PENDING || id != entry) return
                entry = NONE; loaded = false
                when (val reason = (msg["reason"] as? JsonPrimitive)?.contentOrNull) {
                    "eof" -> events.ended()
                    "error" -> events.failed("the PC player could not play the file: ${(msg["file_error"] as? JsonPrimitive)?.contentOrNull ?: "no reason given"}")
                    else -> Log.d(TAG, "end-file $reason (ours)")
                }
            }
            "start-file" -> fileEntry = (msg["playlist_entry_id"] as? JsonPrimitive)?.longOrNull ?: NONE
            "playback-restart" -> if (loaded && current()) events.restarted(!paused)
        }
    }

    override fun property(name: String, value: JsonElement?) {
        if (!current()) return
        val v = (value as? JsonPrimitive)?.doubleOrNull ?: return // unavailable after a stop: the last place stays
        when (name) {
            "time-pos" -> { posMs = (v * 1000).toLong(); posSeenNs = nanos() }
            "duration" -> durMs = (v * 1000).toLong()
        }
    }

    /** The program's messages now concern the entry last opened. */
    private fun current(): Boolean = entry > 0 && fileEntry == entry

    override fun exited(detail: String) {
        ipc = null; loaded = false; entry = NONE; fileEntry = NONE; paused = true
        events.exited(detail)
    }

    private fun warnIfRefused(what: String, a: MpvIpc.Answer) {
        if (!a.ok) Log.w(TAG, "the PC player refused $what: ${a.error}")
    }

    companion object {
        private const val TAG = "music-pc"
        const val OUTPUT_ID = "pc"
        /** Stage L: the most [positionMs] ages an observation (≈ 2.6 of mpv's spacings of ≈ 77 ms). */
        const val AGE_MAX_MS = 200L
        private const val NONE = 0L
        private const val PENDING = -1L
        private val OBSERVED = listOf("time-pos", "duration")

        private fun secs(ms: Long): String = String.format(Locale.ROOT, "%.3f", ms / 1000.0)
    }
}
