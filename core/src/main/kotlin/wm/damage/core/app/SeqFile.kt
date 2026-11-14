package wm.damage.core.app

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.shell.Persistence

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P1 (`CONTRACT.md` §9.3, §13 (b) "sequence file"): the highest `seq` the record has published,
 * with the epoch it was published in, in a small file beside `record.json` and outside the nightly copy. Written
 * before each publish; read once at a start. A store found older than it (a restore from the nightly copy looks
 * exactly so), or of another epoch, or the file missing, makes the start mint a new epoch: a client then holds no
 * cursor the store could answer wrongly.
 */
class SeqFile(val file: Path) {
    /** What the file held: the epoch and the highest `seq` published in it. */
    data class Held(val epoch: Long, val seq: Long)

    /** The file's content; null when it is missing, or unreadable (said here: the start treats both as missing). */
    fun read(): Held? = try {
        val j = Json.parseToJsonElement(String(Files.readAllBytes(file), Charsets.UTF_8)) as JsonObject
        val h = Held(OpJson.num(j, "epoch"), OpJson.num(j, "seq"))
        require(h.epoch > 0 && h.seq >= 0) { "epoch ${h.epoch}, seq ${h.seq}" }
        h
    } catch (e: NoSuchFileException) {
        null
    } catch (e: Exception) {
        wm.damage.core.util.Log.e("record", "${file.fileName} unreadable - taken as missing (a new epoch)", e)
        null
    }

    /** Writes [epoch] and [seq] in one atomic step; throws what the write throws (the caller says it). */
    fun write(epoch: Long, seq: Long) {
        val text = buildJsonObject { put("epoch", epoch); put("seq", seq) }.toString()
        Persistence.writeAtomic(file, text)
    }
}
