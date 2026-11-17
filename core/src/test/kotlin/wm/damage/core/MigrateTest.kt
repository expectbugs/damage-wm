package wm.damage.core

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import wm.damage.core.app.Answer
import wm.damage.core.app.AppHost
import wm.damage.core.app.CoreExecutor
import wm.damage.core.app.Migrate
import wm.damage.core.app.Record
import wm.damage.core.shell.Persistence
import wm.damage.core.windows.files.FilesCore
import wm.damage.core.windows.files.FilesMigration
import wm.damage.core.windows.torrents.TorrentsCore
import wm.damage.core.windows.torrents.TorrentsMigration

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * L1 M1: a converted window's rows from before its conversion, read once on the PC from the PC store's blob — each
 * row the record holds none of is written stamped with the blob's time; a row the record holds stands; a second start
 * writes nothing; a blob of defaults no one edited (stamp 0) writes nothing. The made-up window `t` of `RecordRig`,
 * then Torrents' and Files' own fields on a record with their actions. Stores in temp directories.
 */
class MigrateTest {
    private val rig = RecordRig().also { it.now = 1_700_000_000_000L + 3_600_000 }   // the PC's clock past every stamp

    @AfterTest
    fun cleanUp() = rig.cleanUp()

    private val stamp = 1_700_000_000_000L

    private val migrate = Migrate("t", listOf(
        Migrate.Field("notifyDone", "notify.done") { (it as? JsonPrimitive)?.booleanOrNull?.let { b -> JsonPrimitive(b) } },
        Migrate.Field("height", "glasses.size") { (it as? JsonPrimitive)?.intOrNull?.let { n -> JsonPrimitive(n) } },
        Migrate.Field("absent", "never") { it },
    ), action = "place")

    private fun store(name: String, key: String = "window.t", stampAt: Long = stamp, blob: Map<String, Any> =
        mapOf("notifyDone" to false, "height" to 352, "pollMs" to 2_000)): Persistence = Persistence(rig.dir.resolve(name)).also {
        it.tryApplyRemote(key, buildJsonObject {
            for ((k, v) in blob) when (v) { is Boolean -> put(k, v); is Int -> put(k, v); else -> put(k, v.toString()) }
        }, stampAt)
    }

    @Test
    fun eachRowTheRecordLacksIsWrittenOnce_stampedWithTheBlobsTime() {
        val h = rig.opened()
        val notes = ArrayList<String>()
        migrate.onHost(h, store("pc.json")) { notes += it }
        assertEquals(JsonPrimitive(false), rig.item(h, "notify.done")?.value)
        assertEquals(JsonPrimitive(352), rig.item(h, "glasses.size")?.value)
        assertEquals(stamp, rig.item(h, "glasses.size")?.at, "a migrated item keeps the blob's stamp")
        assertEquals(Migrate.DEVICE, rig.item(h, "glasses.size")?.by)
        assertTrue(notes.single().startsWith("t: 2 row(s) the record did not hold"), notes.single())
        val seq = h.seq
        migrate.onHost(h, store("again.json", stampAt = stamp + 5)) { notes += it }
        assertEquals(seq, h.seq, "a second start writes nothing: the record holds every row")
        assertEquals(1, notes.size, "nothing to say at a second start")
        assertTrue(migrate.offers(store("baseline.json", stampAt = 0L), emptySet()).isEmpty(), "a stamp of 0 is a baseline no one edited")
    }

    @Test
    fun aRowTheRecordHoldsStands() {
        val h = rig.opened()
        // set in a browser after the PC's conversion, at an input time older than the blob's: still never replaced
        assertTrue(rig.ask(h, op("web-00aa01", "s1", 1, "set", listOf("glasses.size"), JsonPrimitive(480), at = stamp - 1)) is Answer.Seen)
        migrate.onHost(h, store("pc.json")) { }
        assertEquals(JsonPrimitive(480), rig.item(h, "glasses.size")?.value, "the row set at the desk stands")
        assertEquals("web-00aa01", rig.item(h, "glasses.size")?.by)
        assertEquals(JsonPrimitive(false), rig.item(h, "notify.done")?.value, "the row it did not hold is written")
    }

    @Test
    fun torrentsAndFilesReadTheirOwnFields() {
        val h = AppHost(Files.createDirectories(rig.dir.resolve("pc")), AppHost.WINDOWS, CoreExecutor("damage-core-migrate-test"),
            clock = { rig.now })
        h.open()
        try {
            TorrentsMigration.onHost(h, store("tor.json", "window.torrents", blob = mapOf("notifyDone" to false,
                "notifyErrors" to true, "pollMs" to 5_000, "height" to 352))) { }
            FilesMigration.onHost(h, store("files.json", "window.files", blob = mapOf("sort" to "SIZE", "hidden" to true,
                "clipVerb" to "CUT", "clipPath" to "/made/up/a.txt", "height" to 416))) { }
            fun v(w: String, row: String) = h.read(w).third.items[Record.key(w, row)]?.value
            assertEquals(JsonPrimitive(false), v(TorrentsCore.WINDOW, TorrentsCore.NOTIFY_DONE))
            assertEquals(JsonPrimitive(352), v(TorrentsCore.WINDOW, TorrentsCore.SIZE))
            assertEquals(null, v(TorrentsCore.WINDOW, TorrentsCore.RECENTS), "no recents in the blob: nothing written")
            assertEquals(JsonPrimitive("size"), v(FilesCore.WINDOW, FilesCore.SORT), "the old upper case, the record's lower")
            assertEquals(FilesCore.clipJson("cut", "/made/up/a.txt"), v(FilesCore.WINDOW, FilesCore.CLIP))
            assertEquals(JsonPrimitive(416), v(FilesCore.WINDOW, FilesCore.SIZE))
            assertTrue(h.read(TorrentsCore.WINDOW).third.items.values.all { it.at == stamp })
        } finally {
            h.close()
            h.executor.shutdown()
        }
    }
}
