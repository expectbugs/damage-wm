package wm.damage.core

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
import wm.damage.core.app.Migrate
import wm.damage.core.shell.Persistence

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * L1 M1: a converted window's rows from before its conversion, read once on the PC from the PC store's blob — each
 * row the record holds none of is written at the PC's clock (S1-5's close: the store keeps no stamps); a row the record
 * holds stands; a second start writes nothing. The made-up window `t` of `RecordRig`. Stores in temp directories.
 */
class MigrateTest {
    private val rig = RecordRig().also { it.now = 1_700_000_000_000L + 3_600_000 }   // the PC's clock

    @AfterTest
    fun cleanUp() = rig.cleanUp()

    private val stamp = 1_700_000_000_000L

    private val migrate = Migrate("t", listOf(
        Migrate.Field("notifyDone", "notify.done") { (it as? JsonPrimitive)?.booleanOrNull?.let { b -> JsonPrimitive(b) } },
        Migrate.Field("height", "glasses.size") { (it as? JsonPrimitive)?.intOrNull?.let { n -> JsonPrimitive(n) } },
        Migrate.Field("absent", "never") { it },
    ), action = "place")

    private fun store(name: String, key: String = "window.t", blob: Map<String, Any> =
        mapOf("notifyDone" to false, "height" to 352, "pollMs" to 2_000)): Persistence = Persistence(rig.dir.resolve(name)).also {
        it.put(key, buildJsonObject {
            for ((k, v) in blob) when (v) { is Boolean -> put(k, v); is Int -> put(k, v); else -> put(k, v.toString()) }
        })
    }

    @Test
    fun eachRowTheRecordLacksIsWrittenOnce_atThePcsClock() {
        val h = rig.opened()
        val notes = ArrayList<String>()
        migrate.onHost(h, store("pc.json")) { notes += it }
        assertEquals(JsonPrimitive(false), rig.item(h, "notify.done")?.value)
        assertEquals(JsonPrimitive(352), rig.item(h, "glasses.size")?.value)
        assertEquals(rig.now, rig.item(h, "glasses.size")?.at, "a migrated item is at the PC's clock")
        assertEquals(Migrate.DEVICE, rig.item(h, "glasses.size")?.by)
        assertTrue(notes.single().startsWith("t: 2 row(s) the record did not hold"), notes.single())
        val seq = h.seq
        migrate.onHost(h, store("again.json")) { notes += it }
        assertEquals(seq, h.seq, "a second start writes nothing: the record holds every row")
        assertEquals(1, notes.size, "nothing to say at a second start")
    }

    @Test
    fun aRowTheRecordHoldsStands() {
        val h = rig.opened()
        // set in a browser after the PC's conversion, at an input time older than the PC's clock: still never replaced
        assertTrue(rig.ask(h, op("web-00aa01", "s1", 1, "set", listOf("glasses.size"), JsonPrimitive(480), at = stamp - 1)) is Answer.Seen)
        migrate.onHost(h, store("pc.json")) { }
        assertEquals(JsonPrimitive(480), rig.item(h, "glasses.size")?.value, "the row set at the desk stands")
        assertEquals("web-00aa01", rig.item(h, "glasses.size")?.by)
        assertEquals(JsonPrimitive(false), rig.item(h, "notify.done")?.value, "the row it did not hold is written")
    }
}
