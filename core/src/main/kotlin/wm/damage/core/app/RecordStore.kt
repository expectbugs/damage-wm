package wm.damage.core.app

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.shell.Persistence
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P1 (`CONTRACT.md` §9.7 item 1, §13 (b) "PC record and seq"; dec. A.12): `record.json` in the
 * data directory — the whole record, the stream table and the `seq` it covers, saved on pacing by temp-and-move
 * through `Persistence`'s atomic write (forced to disk first). Never `state.json`: that file is also written by sync
 * applies and `saveAll` from other threads. Written by this saver only, from a snapshot the core executor takes in
 * one step, so the covered `seq` is exactly the last line whose effect the snapshot holds; it is written last.
 */
class RecordStore(
    val file: Path,
    private val write: (Path, String) -> Unit = { f, t -> Persistence.writeAtomic(f, t, sync = true) },
    writer: Executor? = null,
) {
    /** One save: the record's epoch, the `seq` it covers, its entries and the stream table. */
    data class Saved(
        val epoch: Long,
        val covered: Long,
        val items: Map<String, Item>,
        val streams: Map<StreamKey, Long>,
    )

    /** What a start found: the save, or null (missing, or unreadable: [problem] says which, the file kept as .bad). */
    class Loaded(val saved: Saved?, val problem: String?)

    private val own: ExecutorService? = if (writer == null) Executors.newSingleThreadExecutor { r ->
        Thread(r, "damage-record-save").apply { isDaemon = true }
    } else null
    private val writer: Executor = writer ?: own!!

    fun load(): Loaded {
        val text = try {
            String(Files.readAllBytes(file), Charsets.UTF_8)
        } catch (e: NoSuchFileException) {
            return Loaded(null, null)
        } catch (e: Exception) {
            return Loaded(null, setAside("could not be read", e))
        }
        return try {
            Loaded(decode(text), null)
        } catch (e: Exception) {
            Loaded(null, setAside("did not decode", e))
        }
    }

    private fun setAside(what: String, e: Exception): String {
        // fix round 2, ruling 1.5: one kept aside before is never replaced (`.bad`, then `.bad.2`, `.bad.3` ...)
        var bad = file.resolveSibling(file.fileName.toString() + ".bad")
        var k = 1
        while (Files.exists(bad)) bad = file.resolveSibling(file.fileName.toString() + ".bad." + ++k)
        val moved = try {
            Files.move(file, bad)
            "kept as ${bad.fileName}"
        } catch (m: Exception) {
            Log.e(TAG, "could not keep the unreadable ${file.fileName} aside - the next save replaces it", m)
            "NOT kept aside (${m::class.simpleName})"
        }
        val problem = "${file.fileName} $what (${e::class.simpleName}: ${e.message}); $moved; the logs beside it are replayed"
        Log.e(TAG, problem)
        return problem
    }

    /** Saves [s] on the writer thread; [onDone] gets null when it landed, else the failure (on the writer thread). */
    fun saveAsync(s: Saved, onDone: (Exception?) -> Unit) {
        try {
            writer.execute { onDone(saveNow(s)) }
        } catch (e: Exception) {
            onDone(e)
        }
    }

    /** Saves [s] on the caller: null when it landed, else the failure. */
    fun saveNow(s: Saved): Exception? = try {
        write(file, encode(s))
        null
    } catch (e: Exception) {
        e
    }

    fun close() {
        own?.shutdown()
    }

    companion object {
        private const val TAG = "record"
        const val VERSION = 1L

        /** The file's one form: the entries first, the covered `seq` last (dec. A.12; the spec's P1). */
        fun encode(s: Saved): String = buildJsonObject {
            put("v", VERSION)
            put("epoch", s.epoch)
            put("items", RecordJson.items(s.items))
            put("streams", buildJsonObject {
                for ((device, byStream) in s.streams.entries.groupBy({ it.key.device }, { it }).toSortedMap())
                    put(device, buildJsonObject { for (e in byStream.sortedBy { it.key.stream }) put(e.key.stream, e.value) })
            })
            put("covered", s.covered)
        }.toString()

        fun decode(text: String): Saved {
            val j = Json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("not an object")
            val v = OpJson.num(j, "v")
            require(v == VERSION) { "version $v, this build reads $VERSION" }
            val streams = HashMap<StreamKey, Long>()
            (j["streams"] as? JsonObject ?: throw IllegalArgumentException("no streams")).forEach { (device, m) ->
                (m as? JsonObject ?: throw IllegalArgumentException("streams of $device")).forEach { (stream, hw) ->
                    streams[StreamKey(device, stream)] = OpJson.longOf(hw) ?: throw IllegalArgumentException("high-water of $stream")
                }
            }
            val s = Saved(
                epoch = OpJson.num(j, "epoch"),
                covered = OpJson.num(j, "covered"),
                // L1 C4: a 0.67 file's `colls` (always empty) is not read
                items = RecordJson.items(j["items"] as? JsonObject ?: throw IllegalArgumentException("no items")),
                streams = streams,
            )
            require(s.epoch > 0 && s.covered >= 0) { "epoch ${s.epoch}, covered ${s.covered}" }
            return s
        }
    }
}
