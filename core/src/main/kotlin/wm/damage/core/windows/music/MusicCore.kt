package wm.damage.core.windows.music

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import wm.damage.core.app.ActionSpec
import wm.damage.core.app.ActionTable
import wm.damage.core.app.Admit
import wm.damage.core.app.Answer
import wm.damage.core.app.ApplyRule
import wm.damage.core.app.Kind
import wm.damage.core.app.Levels
import wm.damage.core.app.Migrate
import wm.damage.core.app.Peer
import wm.damage.core.app.Record
import wm.damage.core.app.RecordView
import wm.damage.core.app.WindowCore
import wm.damage.core.app.WindowState

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-4, package K (the lean spec's §3.1, §3.2; decisions B.1–B.3): Music's core. The queue, the place and the
 * output live in the PC record as `rec.music.q`, `now` and `out`; the shared rows (Notify, Default mode, Lyrics
 * sources), the lyric offsets per output and the glasses rows beside them. The queue and the place are written only
 * by the host that plays (`out` names it; absent = the phone), so the queue is one register with one writer and "play
 * next" is a command that host applies where it stands. The catalog, playlists, lyrics, art and YouTube stay the
 * PC's library (source state). No pixels here: the glasses' presenter is `MusicGlasses`.
 */
class MusicCore : WindowCore {
    override val window: String = WINDOW

    /** Half A: the record's three items as they stand (half B's `desc/music` builds the page's description). */
    override fun describe(state: WindowState): JsonObject = buildJsonObject {
        put("window", WINDOW)
        put("title", "Music")
        state.itemNamed(Q)?.takeIf { !it.gone }?.let { put("q", it.value) }
        state.itemNamed(NOW)?.takeIf { !it.gone }?.let { put("now", it.value) }
        put("out", outOf(state).host)
    }

    override fun level(state: WindowState, addr: String): JsonObject? = null

    /** `now`: the place of entry [e] at [pos] ms, sampled at [t] (the writer's clock); viewers extrapolate while
     *  [state] is playing. [why]: a stop's reason ("the PC stopped at 14:02"). */
    data class Now(val e: Long, val pos: Long, val t: Long, val state: String, val vol: Int,
                   val sleep: String? = null, val boost: Int? = null, val why: String? = null) {
        val playing: Boolean get() = state == PLAYING

        /** The place at [clock]: extrapolated while playing, never below 0. */
        fun posAt(clock: Long): Long = if (playing) maxOf(0L, pos + (clock - t)) else pos

        fun json(): JsonObject = buildJsonObject {
            put("e", e); put("pos", pos); put("t", t); put("state", state); put("vol", vol)
            sleep?.let { put("sleep", it) }
            boost?.let { put("boost", it) }
            why?.let { put("why", it) }
        }
    }

    /** `out`: the host that plays and the time of the input that chose it (a move's input, §3.5). */
    data class Out(val host: String, val t: Long) {
        fun json(): JsonObject = buildJsonObject { put("host", host); put("t", t) }
    }

    /** `q` as the record holds it. */
    data class Queue(val entries: List<QueueEntry>, val mode: Mode, val label: String)

    companion object {
        const val WINDOW = "music"

        // ---- the record's items ----
        const val Q = "q"
        const val NOW = "now"
        const val OUT = "out"
        const val NOTIFY_TRACK = "notify.track"
        const val NOTIFY_QUEUE = "notify.queue"
        const val NOTIFY_ROUTE = "notify.route"
        const val NOTIFY_PC = "notify.pc"
        const val NOTIFY_YT = "notify.yt"
        const val NOTIFY_PLAYLIST = "notify.playlist"
        const val DEFAULT_MODE = "defaultMode"
        const val LYRIC_SOURCES = "lyricSources"
        const val OFFSET_PC = "offset.pc"
        const val OFFSET_PHONE = "offset.phone/"
        const val SIZE = "glasses.size"
        const val VIZ = "glasses.viz"
        const val VIZ_RATE = "glasses.vizRate"
        const val MM = "glasses.mm."

        // ---- the hosts and `now`'s states ----
        const val PHONE = "phone"
        const val PC = "pc"
        const val PLAYING = "playing"
        const val PAUSED = "paused"
        const val STOPPED = "stopped"
        const val BUFFERING = "buffering"
        const val SPOTIFY = "spotify"

        // ---- actions ----
        const val A_QUEUE = "queue"
        const val A_NOW = "now"
        const val A_OUT = "out"
        const val A_SET = "set"
        const val MIGRATE = Migrate.ACTION

        /** The program verbs (§3.2): every one through `MusicProgram` on the PC, never queued. */
        val CONTROL_VERBS = setOf("play", "pause", "toggle", "next", "prev", "seek", "playFrom", "volume", "sleep", "boost",
            "mode", "add", "playNext", "playNow", "moveEntry", "remove", "clear", "shuffleRest", "move")
        val PROGRAM: Set<String> = CONTROL_VERBS

        fun levelOf(action: String): String? = when (action) {
            A_QUEUE, A_NOW, A_OUT, A_SET, MIGRATE -> Levels.CONTROL
            in CONTROL_VERBS -> Levels.CONTROL
            else -> null
        }

        val ACTIONS = ActionTable { w, a ->
            if (w != WINDOW) null else when (a) {
                A_QUEUE, A_NOW, MIGRATE -> ActionSpec(Kind.POSITION)
                A_OUT, A_SET -> ActionSpec(Kind.REGISTER)
                else -> null
            }
        }

        /**
         * §3.1: `q` and `now` are taken from device `local` (the PC) only while `out` names the PC, from `phone` only
         * while it is absent or names the phone — otherwise taken and kept (a queue written while away does not
         * overwrite the playing host's); `out` naming the PC only from `local`, the phone only from `phone`, else
         * refused in words. The migration passes; everything else is the merge rule's.
         */
        val RULE = ApplyRule { rec, op ->
            if (op.window != WINDOW || op.device == Migrate.DEVICE) return@ApplyRule null
            when (op.action) {
                A_QUEUE, A_NOW -> {
                    val host = hostOf(op.device) ?: return@ApplyRule Admit.Keep
                    if (host == outOf(rec).host) null else Admit.Keep
                }
                A_OUT -> {
                    val named = (op.result as? JsonObject)?.let { text(it["host"]) }
                    if (named == null || hostOf(op.device) != named)
                        Admit.Refuse(Answer.Refused("the output names the host that plays it: $named is not ${op.device}", Answer.INVALID))
                    else null
                }
                else -> null
            }
        }

        /** The host a device writes as: `local` is the PC, `phone` the phone. */
        fun hostOf(device: String): String? = when (device) {
            Peer.LOCAL.device -> PC
            Peer.PHONE.device -> PHONE
            else -> null
        }

        // ---- reading the record ----

        fun text(v: JsonElement?): String? = (v as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun long(v: JsonElement?): Long? = (v as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        fun int(v: JsonElement?): Int? = (v as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        fun bool(v: JsonElement?): Boolean? = (v as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toBooleanStrictOrNull()

        private fun live(rec: RecordView, name: String): JsonElement? =
            rec.item(Record.key(WINDOW, name))?.takeIf { !it.gone }?.value

        /** Absent or unreadable = the phone (answer 1). */
        fun outOf(rec: RecordView): Out {
            val o = live(rec, OUT) as? JsonObject ?: return Out(PHONE, 0L)
            val h = text(o["host"])?.takeIf { it == PC || it == PHONE } ?: return Out(PHONE, 0L)
            return Out(h, long(o["t"]) ?: 0L)
        }

        fun nowOf(rec: RecordView): Now? = (live(rec, NOW) as? JsonObject)?.let(::nowOf)

        fun nowOf(o: JsonObject): Now? {
            val e = long(o["e"]) ?: return null
            return Now(e, long(o["pos"]) ?: 0L, long(o["t"]) ?: 0L, text(o["state"]) ?: STOPPED, int(o["vol"]) ?: 50,
                text(o["sleep"]), int(o["boost"]), text(o["why"]))
        }

        fun queueOf(rec: RecordView): Queue? = (live(rec, Q) as? JsonObject)?.let(::queueOf)

        fun queueOf(o: JsonObject): Queue {
            val entries = (o["entries"] as? JsonArray).orEmpty().mapNotNull { el ->
                val e = el as? JsonObject ?: return@mapNotNull null
                val id = long(e["id"]) ?: return@mapNotNull null
                val t = int(e["t"]) ?: return@mapNotNull null
                QueueEntry(id, TrackRef(t, text(e["title"]) ?: "", text(e["artist"]) ?: "", text(e["album"]) ?: "", int(e["dur"]) ?: 0))
            }
            return Queue(entries, text(o["mode"])?.let { n -> Mode.entries.firstOrNull { it.name == n } } ?: Mode.SHUFFLE,
                text(o["label"]) ?: "")
        }

        fun queueJson(entries: List<QueueEntry>, mode: Mode, label: String): JsonObject = buildJsonObject {
            put("entries", entriesJson(entries))
            put("mode", mode.name)
            put("label", label)
        }

        fun entriesJson(entries: List<QueueEntry>): JsonArray = buildJsonArray {
            for (e in entries) add(buildJsonObject {
                put("id", e.qid); put("t", e.track.id); put("title", e.track.title)
                put("artist", e.track.artist); put("album", e.track.album); put("dur", e.track.durMs)
            })
        }

        /** A Notify row: absent = its default (track, queue end, route and YouTube on; PC and playlist off). */
        fun notify(rec: RecordView, row: String): Boolean = bool(live(rec, row))
            ?: (row in setOf(NOTIFY_TRACK, NOTIFY_QUEUE, NOTIFY_ROUTE, NOTIFY_YT))

        fun defaultMode(rec: RecordView): Mode =
            text(live(rec, DEFAULT_MODE))?.let { n -> Mode.entries.firstOrNull { it.name == n } } ?: Mode.SHUFFLE

        fun lyricSources(rec: RecordView): String = text(live(rec, LYRIC_SOURCES)) ?: "lrclib+local"

        /** The lyric offset of an output: the PC's, or the phone's route by its name (Auto when none is chosen). */
        fun offsetItem(output: Output?): String = if (output?.id == Output.PC) OFFSET_PC else OFFSET_PHONE + (output?.name ?: "Auto")

        fun offset(rec: RecordView, item: String): Int = int(live(rec, item)) ?: 0

        /** Size: absent = 480 (Music's own default), 0 = Global. */
        fun size(rec: RecordView): Int? = when (val h = int(live(rec, SIZE))) { null -> 480; 0 -> null; else -> h }

        fun viz(rec: RecordView): String = text(live(rec, VIZ)) ?: "Off"

        fun vizRate(rec: RecordView): Int = int(live(rec, VIZ_RATE))?.takeIf { it in listOf(4, 8, 12) } ?: 8

        /** A Music Mode surface row; absent = its default (card, lyrics, clock and PC link on). */
        fun mm(rec: RecordView, surface: String): Boolean = bool(live(rec, MM + surface))
            ?: (surface in setOf("card", "lyrics", "clock", "link"))
    }
}
