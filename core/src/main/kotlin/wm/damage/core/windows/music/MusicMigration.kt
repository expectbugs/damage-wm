package wm.damage.core.windows.music

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import wm.damage.core.app.AppHost
import wm.damage.core.app.Migrate
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.ShellSettings
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-4, package K (the lean spec's §3.8): Music's state from before its conversion — the PC store's synced
 * `window.music` blob and its `player` sub-key, in APK 0.77's shapes (`MusicWindow.saveState`, `PlayerCore.persist`) —
 * becomes record items where the record holds none ([Migrate], at the PC's two process starts before any presenter or
 * player reads the record): the six Notify rows, Default mode, Lyrics sources, the glasses rows (`height` → Size), the
 * phone's Auto lyric offset, the queue (its ids kept) and the place (stopped). `out` stays absent: the phone plays
 * (answer 1). A second start writes nothing.
 */
object MusicMigration {
    private fun bool(v: JsonElement): JsonElement? = MusicCore.bool(v)?.let { JsonPrimitive(it) }

    val FIELDS: List<Migrate.Field> = listOf(
        Migrate.Field("notifyTrack", MusicCore.NOTIFY_TRACK, ::bool),
        Migrate.Field("notifyQueueEnd", MusicCore.NOTIFY_QUEUE, ::bool),
        Migrate.Field("notifyRoute", MusicCore.NOTIFY_ROUTE, ::bool),
        Migrate.Field("notifyPc", MusicCore.NOTIFY_PC, ::bool),
        Migrate.Field("notifyYt", MusicCore.NOTIFY_YT, ::bool),
        Migrate.Field("notifyPlaylist", MusicCore.NOTIFY_PLAYLIST, ::bool),
        Migrate.Field("defaultMode", MusicCore.DEFAULT_MODE) { v ->
            MusicCore.text(v)?.takeIf { n -> Mode.entries.any { it.name == n } }?.let { JsonPrimitive(it) }
        },
        Migrate.Field("lyricsSources", MusicCore.LYRIC_SOURCES) { v -> MusicCore.text(v)?.let { JsonPrimitive(it) } },
        Migrate.Field("viz", MusicCore.VIZ) { v -> MusicCore.text(v)?.let { JsonPrimitive(it) } },
        Migrate.Field("vizRate", MusicCore.VIZ_RATE) { v -> MusicCore.int(v)?.let { JsonPrimitive(it) } },
        Migrate.Field("height", MusicCore.SIZE) { v ->
            MusicCore.int(v)?.takeIf { it == 0 || it in ShellSettings.HEIGHTS }?.let { JsonPrimitive(it) }
        },
    ) + listOf("card", "lyrics", "viz", "peek", "clock", "link").map { s ->
        Migrate.Field("mm" + s.replaceFirstChar { it.uppercase() }, MusicCore.MM + s, ::bool)
    }

    /** The Auto route's lyric offset; the per-connection keys (Android's device handles) are dropped, said. */
    fun derived(blob: JsonObject): List<Pair<String, JsonElement>> {
        val offsets = blob["lyricsOffsets"] as? JsonObject ?: return emptyList()
        val dropped = offsets.keys.filter { it != Output.AUTO }
        if (dropped.isNotEmpty()) Log.i("migrate", "music: lyric offsets of ${dropped.size} per-connection output(s) not carried over ($dropped)")
        return listOfNotNull(MusicCore.int(offsets[Output.AUTO])?.let { MusicCore.OFFSET_PHONE + "Auto" to JsonPrimitive(it) })
    }

    /** The player's record (`window.music.player`): `engine` → `q` (ids kept), the entry at `index` at `posMs` → `now`,
     *  stopped. */
    fun player(sub: String, blob: JsonObject): Map<String, JsonElement> {
        if (sub.isNotEmpty()) return emptyMap()
        val eng = blob["engine"] as? JsonObject ?: return emptyMap()
        val entries = (eng["queue"] as? JsonArray).orEmpty().mapNotNull { el ->
            val e = el as? JsonObject ?: return@mapNotNull null
            val qid = MusicCore.long(e["qid"]) ?: return@mapNotNull null
            val id = MusicCore.int(e["id"]) ?: return@mapNotNull null
            QueueEntry(qid, TrackRef(id, MusicCore.text(e["title"]) ?: "", MusicCore.text(e["artist"]) ?: "",
                MusicCore.text(e["album"]) ?: "", MusicCore.int(e["dur"]) ?: 0))
        }
        if (entries.isEmpty()) return emptyMap()
        val mode = MusicCore.text(eng["mode"])?.let { n -> Mode.entries.firstOrNull { it.name == n } } ?: Mode.SHUFFLE
        val cur = entries[(MusicCore.int(eng["index"]) ?: 0).coerceIn(0, entries.size - 1)]
        val now = MusicCore.Now(cur.qid, MusicCore.long(blob["posMs"]) ?: 0L, MusicCore.long(blob["posAt"]) ?: 0L,
            MusicCore.STOPPED, MusicCore.int(blob["volume"]) ?: 50)
        return mapOf(MusicCore.Q to MusicCore.queueJson(entries, mode, MusicCore.text(eng["label"]) ?: ""), MusicCore.NOW to now.json())
    }

    val MIGRATE = Migrate(MusicCore.WINDOW, FIELDS, MusicCore.MIGRATE, ::derived, subs = listOf(Migrate.Sub("player", ::player)))

    /** The PC's migration (both host builders, at the process start, before any presenter or player reads the record). */
    fun onHost(host: AppHost, store: Persistence, note: (String) -> Unit) = MIGRATE.onHost(host, store, note)
}
