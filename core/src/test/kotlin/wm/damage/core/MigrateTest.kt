package wm.damage.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
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
 * Build S1-2, package Q2 (the spec's §2.5 and §5 F4): the generic migration of a window's local blob — each register
 * field offered once per host as a migrate op stamped with the blob's time (L1 C1), the ledger `window.<id>.conv`
 * written once the offers landed, a second start offering nothing, a row set after the conversion never replaced by
 * an older blob, and a `migrate` note. The made-up window `t` of `RecordRig` (its `place` is the position kind, as a
 * window's `migrate` is declared), a store in a temp directory.
 */
class MigrateTest {
    private val rig = RecordRig()

    @AfterTest
    fun cleanUp() = rig.cleanUp()

    private val stamp = 1_700_000_000_000L

    private val migrate = Migrate("t", listOf(
        Migrate.Field("notifyDone", "notify.done") { (it as? JsonPrimitive)?.booleanOrNull?.let { b -> JsonPrimitive(b) } },
        Migrate.Field("height", "glasses.size") { (it as? JsonPrimitive)?.intOrNull?.let { n -> JsonPrimitive(n) } },
        Migrate.Field("absent", "never") { it },
    ), action = "place")

    private fun store(name: String, stampAt: Long = stamp): Persistence = Persistence(rig.dir.resolve(name)).also {
        it.tryApplyRemote("window.t", buildJsonObject { put("notifyDone", false); put("height", 352); put("pollMs", 2_000) }, stampAt)
    }

    @Test
    fun eachFieldIsOfferedOnceStampedWithTheBlobsTime() {
        val plan = migrate.plan(store("pc.json"))
        assertEquals(listOf("notify.done", "glasses.size"), plan.offers.map { it.targets.single() })
        assertTrue(plan.offers.all { it.at == stamp && it.action == "place" }, "${plan.offers}")
        assertEquals(listOf<JsonElement>(JsonPrimitive(false), JsonPrimitive(352)), plan.offers.map { it.result })
        assertTrue(migrate.plan(store("baseline.json", 0L)).offers.isEmpty(), "a stamp of 0 is a baseline no one edited")
    }

    /** Pin (the spec's Q2: "a second migration offers again"). */
    @Test
    fun aSecondStartOffersNothing() {
        val h = rig.opened()
        val s = store("pc.json")
        val notes = ArrayList<String>()
        val first = migrate.onHost(h, s) { notes += it }
        assertEquals(emptyList(), first.refused, first.toString())
        assertTrue(first.ledgerWritten)
        assertEquals(JsonPrimitive(352), rig.item(h, "glasses.size")?.value)
        assertEquals(stamp, rig.item(h, "glasses.size")?.at, "a migrated item keeps the old stamp")
        assertTrue(notes.single().startsWith("pc: t: 2 field(s) offered (notify.done, glasses.size)"), notes.single())
        val reread = Persistence(rig.dir.resolve("pc.json")).also { it.load() }
        assertTrue(migrate.plan(reread).offers.isEmpty(), "the ledger holds what was offered")
        assertEquals(0, migrate.onHost(h, reread) { notes += it }.plan.offers.size)
        assertEquals(1, notes.size, "nothing to say at a second start")
    }

    @Test
    fun aRowSetAfterTheConversionIsNeverReplacedByAnOlderBlob() {
        val h = rig.opened()
        // set in a browser after the PC's conversion: a write the record holds since
        assertTrue(rig.ask(h, op("web-00aa01", "s1", 1, "set", listOf("glasses.size"), JsonPrimitive(480), at = stamp + 1)) is Answer.Seen)
        val notes = ArrayList<String>()
        val r = migrate.onHost(h, store("phone.json")) { notes += it }
        assertEquals(JsonPrimitive(480), rig.item(h, "glasses.size")?.value, "the row set at the desk stands")
        assertEquals("web-00aa01", rig.item(h, "glasses.size")?.by)
        assertEquals(emptyList(), r.refused, r.toString())
        assertTrue(r.ledgerWritten, "the ledger is written and the field is not offered again")
    }
}
