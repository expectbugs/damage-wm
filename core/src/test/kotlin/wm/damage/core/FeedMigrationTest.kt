package wm.damage.core

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import wm.damage.core.app.Answer
import wm.damage.core.app.AppHost
import wm.damage.core.app.CoreExecutor
import wm.damage.core.app.Migrate
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.app.Record
import wm.damage.core.shell.Persistence
import wm.damage.core.windows.feed.FeedCore
import wm.damage.core.windows.feed.FeedMigration

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-3, piece B7: Feed's state from before its conversion, in APK 0.68's shape (`feed/migrate-068.json`, made up,
 * as the old `saveState` / `saveSubState` wrote it), becomes record items once, on the PC — each stamped with its own
 * key's time; `pcLossSec` dropped, `docTop` and `images` not migrated; a second start writes nothing; an item the
 * record holds stands. Stores and records in temp directories.
 */
class FeedMigrationTest {
    private val dir = Files.createTempDirectory("damage-feed-migrate")
    private val now = 1_700_000_000_000L + 86_400_000L                          // the PC's clock past every stamp
    private val host = AppHost(Files.createDirectories(dir.resolve("pc")), AppHost.WINDOWS, CoreExecutor("damage-core-feed-migrate"),
        clock = { now }).also { it.open() }

    @AfterTest
    fun tearDown() {
        host.close()
        host.executor.shutdown()
        dir.toFile().deleteRecursively()
    }

    private val fixture: JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/feed/migrate-068.json")!!.readBytes().toString(Charsets.UTF_8)).jsonObject

    private fun stamp(key: String) = fixture[key]!!.jsonObject["stamp"]!!.jsonPrimitive.long

    private fun store(): Persistence = Persistence(dir.resolve("state.json")).also { s ->
        for ((k, v) in fixture) if (k.startsWith("window.")) s.tryApplyRemote(k, v.jsonObject["blob"]!!.jsonObject, v.jsonObject["stamp"]!!.jsonPrimitive.long)
    }

    private fun item(name: String) = host.read(FeedCore.WINDOW).third.items[Record.key(FeedCore.WINDOW, name)]

    @Test
    fun the068KeysBecomeRecordItemsOnce_eachWithItsKeysTime() {
        val notes = ArrayList<String>()
        FeedMigration.onHost(host, store()) { notes += it }
        fun s(vararg v: String) = JsonArray(v.map { JsonPrimitive(it) })
        assertEquals(s("linux", "rust"), item("recent.reddit")?.value)
        assertEquals(s("Linux"), item("recent.slashdot")?.value)
        assertEquals(JsonPrimitive(true), item("pin.r:linux")?.value)
        assertEquals(JsonPrimitive(30), item("fetch")?.value)
        assertEquals(JsonPrimitive(90), item("keep")?.value)
        assertEquals(JsonPrimitive(true), item("notify.reddit")?.value)
        assertEquals(JsonPrimitive(false), item("notify.slashdot")?.value)
        assertEquals(JsonPrimitive(352), item("glasses.size")?.value)
        assertEquals(JsonPrimitive("never"), item("glasses.art.xkcd")?.value)
        assertEquals(JsonPrimitive("always"), item("glasses.art.binge")?.value)
        assertEquals(JsonPrimitive(8), item("glasses.levels")?.value)
        assertEquals(stamp("window.feed"), item("fetch")?.at, "a blob's row keeps the blob's stamp")
        // a source's record: its read list as it stands, its flag's facts, its seen stamp — at that key's own time
        assertEquals(s("a1a1a1a1a1a1a1a1", "b2b2b2b2b2b2b2b2", "c3c3c3c3c3c3c3c3"), item("read.popular")?.value)
        assertEquals(stamp("window.feed.src.popular"), item("read.popular")?.at)
        val flag = FeedCore.flagOf(item("flag.b2b2b2b2b2b2b2b2")?.value)
        assertEquals("A made-up flagged story", flag?.title)
        assertEquals(1_700_000_150_000L, flag?.atMs)
        assertEquals(JsonPrimitive(1_700_000_190_000L), item("seen.popular")?.value)
        assertEquals(JsonPrimitive(1_700_000_290_000L), item("seen.xkcd")?.value)
        assertNull(item("read.xkcd"), "an empty list: nothing to migrate")
        assertEquals(Migrate.DEVICE, item("seen.xkcd")?.by)
        // the archive's place at the PC glasses' width
        val b = FeedCore.bingeOf(item("binge.8bt")?.value)
        assertEquals(FeedCore.Binge(7, null, 3, FeedMigration.PC_WIDTH), b)
        assertEquals(564, FeedMigration.PC_WIDTH)
        assertEquals(JsonNull, item("binge.8bt")?.value?.jsonObject?.get("f"))
        // dropped, not migrated, left local
        val names = host.read(FeedCore.WINDOW).third.items.keys.map { it.removePrefix(Record.prefix(FeedCore.WINDOW)) }
        assertTrue(names.none { "pcLoss" in it || "docTop" in it || "images" in it || it == "place" }, "$names")
        assertTrue(notes.single().startsWith("feed: 18 row(s) the record did not hold"), notes.single())
        // a second start writes nothing
        val seq = host.read(FeedCore.WINDOW).second
        FeedMigration.onHost(host, store()) { notes += it }
        assertEquals(seq, host.read(FeedCore.WINDOW).second, "a second start writes nothing")
        assertEquals(1, notes.size)
    }

    @Test
    fun anItemTheRecordHoldsStands() {
        // read in a browser after the PC's conversion, at an input time older than the key's: still never replaced
        val got = ArrayList<Answer>()
        host.act(Op("web-00feed", OpId.Queued("w1", 1), FeedCore.WINDOW, FeedCore.A_READ, listOf("read.popular"),
            JsonArray(listOf(JsonPrimitive("d4d4d4d4d4d4d4d4"))), at = stamp("window.feed.src.popular") - 1)) { got += it }
        host.executor.call {}
        assertTrue(got.single() is Answer.Seen, "$got")
        FeedMigration.onHost(host, store()) { }
        assertEquals(JsonArray(listOf(JsonPrimitive("d4d4d4d4d4d4d4d4"))), item("read.popular")?.value, "the browser's list stands")
        assertEquals(JsonPrimitive(1_700_000_190_000L), item("seen.popular")?.value, "the item it did not hold is written")
    }
}
