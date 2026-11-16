package wm.damage.core

import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import wm.damage.core.app.Answer
import wm.damage.core.app.Expect
import wm.damage.core.app.Loss
import wm.damage.core.app.LossWords
import wm.damage.core.app.Migrate
import wm.damage.core.app.Peer
import wm.damage.core.shell.Persistence

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q2 (the spec's §2.5 and §5 F4): the generic migration of a window's local blob — each register
 * field offered once per host as a migrate op expecting `PreConversion(the blob's stamp)`, the ledger
 * `window.<id>.conv` written once the offers landed, a second start offering nothing, a row set after the conversion
 * never replaced by an older blob, and a `migrate` note; and the loss words a glasses presenter says once per
 * standing write. The made-up window `t` of `RecordRig` (its `place` is the position kind, as a window's `migrate`
 * is declared), a store in a temp directory.
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
    fun eachFieldIsOfferedOnceExpectingTheBlobsStamp() {
        val plan = migrate.plan(store("pc.json"))
        assertEquals(listOf("notify.done", "glasses.size"), plan.offers.map { it.targets.single() })
        assertTrue(plan.offers.all { it.expect == Expect.PreConversion(stamp) && it.action == "place" }, "${plan.offers}")
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
        assertEquals(2, first.applied, first.toString())
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
        assertTrue(rig.ask(h, op("web-00aa01", "s1", 1, "set", listOf("glasses.size"), JsonPrimitive(480), Expect.Value(JsonNull))) is Answer.Seen)
        val notes = ArrayList<String>()
        val r = migrate.onHost(h, store("phone.json")) { notes += it }
        assertEquals(JsonPrimitive(480), rig.item(h, "glasses.size")?.value, "the row set at the desk stands")
        assertEquals(1, r.lost.size, r.toString())
        assertTrue("glasses.size stays 480 (web-00aa01)" in notes.single(), notes.single())
        assertTrue(r.ledgerWritten, "a loss is an answer: the ledger is written and the field is not offered again")
    }

    @Test
    fun theLossWordsAreSaidOncePerStandingWrite() {
        val words = LossWords(ZoneOffset.UTC)
        val at = 1_700_000_000_000L          // 22:13 UTC
        val loss = Loss("notify.done", JsonPrimitive(true), "web-00aa01", at + 600_000, JsonPrimitive(false))
        val line = words.register("Notify · done", loss, "phone", at)
        assertEquals("not applied — Notify · done: you off (phone 22:13) · standing on, set in a browser 22:23", line)
        assertNull(words.register("Notify · done", loss, "phone", at), "the same standing write is said once")
        val newer = loss.copy(by = Peer.LOCAL.device, at = at + 1_200_000)
        assertEquals("not applied — Notify · done: you off (phone 22:13) · standing on, set on the PC 22:33",
            words.register("Notify · done", newer, "phone", at))
        assertEquals("by an earlier build", LossWords.where(Migrate.DEVICE))
        assertEquals("480", LossWords.plain(JsonPrimitive(480)))
    }
}
