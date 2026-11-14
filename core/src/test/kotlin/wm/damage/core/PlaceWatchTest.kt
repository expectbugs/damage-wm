package wm.damage.core

import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.After
import wm.damage.core.bastion.FilePlaceStore
import wm.damage.core.bastion.Fix
import wm.damage.core.bastion.PhonePresence
import wm.damage.core.bastion.PlaceSource
import wm.damage.core.bastion.PlaceStore
import wm.damage.core.bastion.PlaceWatch
import wm.damage.core.util.Log

/** The platform's position updates, stood in: a permission switch, a refusal to throw, and fixes handed by the test. */
internal class FakePlaceSource : PlaceSource {
    @Volatile var allowed = true
    @Volatile var refuse: Exception? = null
    @Volatile var onFix: ((Fix) -> Unit)? = null
    /** The callback of the last start, kept after a stop: a platform callback that arrives late. */
    @Volatile var lastOnFix: ((Fix) -> Unit)? = null
    val starts = AtomicInteger()
    val stops = AtomicInteger()

    override fun permitted(): Boolean = allowed
    override fun start(onFix: (Fix) -> Unit): String {
        starts.incrementAndGet()
        refuse?.let { throw it }
        this.onFix = onFix
        lastOnFix = onFix
        return "the stand-in"
    }
    override fun stop() { stops.incrementAndGet(); onFix = null }
    fun give(f: Fix) = (onFix ?: error("the stand-in source is not started")).invoke(f)
}

internal class MemPlaceStore(var saved: Fix? = null) : PlaceStore {
    val saves = AtomicInteger()
    override fun load(): Fix? = saved
    override fun save(f: Fix) { saved = f; saves.incrementAndGet() }
}

/** Made-up positions: the "bus" in open water, and points due north of it at a given distance. */
internal object Places {
    const val BUS_LAT = 12.345678
    const val BUS_LON = 98.765432
    private const val M_PER_DEG = 6_371_008.8 * Math.PI / 180
    fun north(m: Double, at: Long, acc: Float? = 20f) = Fix(BUS_LAT + m / M_PER_DEG, BUS_LON, at, acc)
}

/**
 * Build W, package F (W-decisions G.1, G.2, G.5, G.7): the phone's position watch with a stand-in source, store and
 * clock. Pinned: the bus's position is learned only from a fix TAKEN while the PC says the phone is on the bus
 * network; home within 300 m, away beyond 500 m, the band between keeps the last word; a fix 30 minutes old is no
 * fix; nothing starts without the permission, a revocation stops the updates, a platform refusal is caught and said
 * once, the row off stops them, and the stack's stop ends them for good.
 */
class PlaceWatchTest {

    private val clock = AtomicLong(T0)
    private val said = CopyOnWriteArrayList<String>()
    private val logs = CopyOnWriteArrayList<String>()
    private val sink = Log.Sink { level, tag, message -> logs += "$level $tag $message" }

    init { Log.addSink(sink) }

    @After
    fun tearDown() { Log.removeSink(sink) }

    private fun watch(src: FakePlaceSource, store: PlaceStore, enabled: () -> Boolean = { true }) =
        PlaceWatch(src, store, enabled, clock::get) { said += it }

    /** An answer heard the moment its ask was sent (an instant answer, the stand-in clock's now). Verify 3 gave
     *  [PlaceWatch.onBus] the ask's send time; [aLateAnswerConfirmsOnlyWhatItsAskCould] sends a late one. */
    private fun PlaceWatch.onBus(on: Boolean) = onBus(on, clock.get())

    /** Fix round 2 (R2.1): a fix taken on the bus network is a candidate, the bus's position once a later answer
     *  confirms it ([PlaceWatch.LEARN_CONFIRM_MS] after the fix was taken): [confirm] is that answer. */
    private fun confirm(w: PlaceWatch, fixAt: Long) {
        clock.set(fixAt + PlaceWatch.LEARN_CONFIRM_MS)
        w.onBus(true)
    }

    @Test
    fun theBusIsLearnedOnlyFromFixesTakenOnTheBusNetwork() {
        val src = FakePlaceSource()
        val store = MemPlaceStore()
        val w = watch(src, store)
        w.tick()
        assertEquals(1, src.starts.get())
        assertEquals("location: no fix younger than 30 min", w.stateLine())
        src.give(Places.north(0.0, T0))
        assertNull(store.saved, "off the bus network nothing is learned")
        assertTrue("no bus position yet" in w.stateLine(), w.stateLine())

        // the signal comes 3 min after that fix: an answer long enough after it to confirm a candidate, had it been one
        clock.set(T0 + 3 * MIN)
        w.onBus(true)
        assertNull(store.saved, "the fix from before the bus network is not learned when the signal comes")
        val late = T0 + 3 * MIN - 30_000
        src.give(Places.north(0.0, late))
        confirm(w, late)
        assertNull(store.saved, "a fix taken before the signal (handed over late) is not learned")
        val coarseAt = clock.get()
        src.give(Places.north(0.0, coarseAt, acc = 150f))
        confirm(w, coarseAt)
        assertNull(store.saved, "a fix coarser than 100 m is not a candidate")

        clock.set(T0 + 10 * MIN)
        val c = Places.north(0.0, T0 + 10 * MIN)
        src.give(c)
        assertNull(store.saved, "a fix taken on the bus network is a candidate, not yet the bus's position")
        clock.set(c.at + PlaceWatch.LEARN_CONFIRM_MS - 1)
        w.onBus(true)
        assertNull(store.saved, "an answer 1 ms short of the confirm time does not confirm it")
        confirm(w, c.at)
        assertSame(c, store.saved, "confirmed by an answer 150 s after it was taken: the bus's position")
        assertEquals(PhonePresence.HOME, w.currentWord)
        assertEquals(1, said.count { "the bus's position is taken" in it }, said.joinToString("\n"))

        clock.set(T0 + 20 * MIN)
        val d = Places.north(50.0, T0 + 20 * MIN)
        src.give(d)
        src.give(Places.north(60.0, T0 + 20 * MIN + 1, acc = 150f))
        confirm(w, d.at)
        assertSame(d, store.saved, "it follows the bus; a fix coarser than 100 m is not taken as the bus's position")
        assertEquals(1, said.count { "the bus's position is taken" in it }, "said once per run on the bus network")
        assertEquals(PhonePresence.HOME, w.currentWord, "...but the coarse fix places the phone in the band")

        w.onBus(false)
        clock.set(T0 + 30 * MIN)
        src.give(Places.north(2_000.0, T0 + 30 * MIN))
        clock.set(T0 + 30 * MIN + PlaceWatch.LEARN_CONFIRM_MS)
        assertSame(d, store.saved, "off the bus network again: nothing learned")
        assertEquals(PhonePresence.AWAY, w.currentWord)
        assertEquals(2, store.saves.get())

        // a new run on the bus network says it again
        w.onBus(true)
        val e = Places.north(0.0, clock.get())
        src.give(e)
        confirm(w, e.at)
        assertSame(e, store.saved)
        assertEquals(2, said.count { "the bus's position is taken" in it })
    }

    /**
     * Fix round 2 (R2.1; review 2, finding 1): the PC reads the daemon once a minute and the phone asks every 15 s,
     * so answers go on saying `bus:true` for a while after the phone has left the bus network. A fix taken then is
     * NOT the bus's position unless an answer [PlaceWatch.LEARN_CONFIRM_MS] or more after it still says the bus
     * network, with no `bus:false` answer and no channel end (both [PlaceWatch.onBus] false; the channel's end is
     * `RemoteBastion`'s `onBus(false)`, pinned in `PlacePrivacyTest.theChannelsEndStopsTheLearning`) in between.
     * A later fix as accurate or better replaces the candidate and its clock starts again; a coarser one does not.
     * Watched failing with the confirm step disabled: the stale `bus:true` answer 15 s after the fix saved it.
     */
    @Test
    fun aFixIsTheBusPositionOnlyOnceALaterAnswerConfirmsIt() {
        // (a) the fix taken 30 s before the answer that says bus:false — with a stale bus:true answer between
        val src = FakePlaceSource()
        val store = MemPlaceStore()
        val w = watch(src, store)
        w.tick()
        w.onBus(true)
        val f = Places.north(800.0, T0 + MIN)              // the phone is already driving away
        clock.set(f.at)
        src.give(f)
        clock.set(f.at + 15_000)
        w.onBus(true)                                       // the PC's read from before he left
        clock.set(f.at + 30_000)
        w.onBus(false)                                      // the read that saw it
        for (t in listOf(PlaceWatch.LEARN_CONFIRM_MS, 2 * PlaceWatch.LEARN_CONFIRM_MS)) {
            clock.set(f.at + t)
            w.onBus(true)                                   // back on the bus network later: a new run
        }
        assertNull(store.saved, "a fix taken 30 s before the bus:false answer is not learned")
        assertEquals(0, said.count { "the bus's position is taken" in it }, said.joinToString("\n"))

        // (b) the channel's end (onBus false, no answer to say otherwise) drops it the same way
        val g = Places.north(0.0, clock.get())
        src.give(g)
        clock.set(g.at + 15_000)
        w.onBus(true)
        w.onBus(false)                                      // the channel ends
        confirm(w, g.at)                                    // it comes back 150 s after the fix
        assertNull(store.saved, "a candidate does not outlive the channel's end")

        // (c) a later fix as accurate replaces the candidate and restarts its clock; a coarser one does not
        val h = Places.north(0.0, clock.get(), acc = 20f)
        src.give(h)
        clock.set(h.at + 100_000)
        val i = Places.north(10.0, h.at + 100_000, acc = 20f)
        src.give(i)
        confirm(w, h.at)
        assertNull(store.saved, "the replacing fix is 50 s old at the answer that would have confirmed the first")
        val j = Places.north(20.0, clock.get(), acc = 80f)
        src.give(j)                                         // coarser than the candidate: it stays as it is
        confirm(w, i.at)
        assertSame(i, store.saved, "the candidate confirmed is the more accurate fix, 150 s after it was taken")
        assertEquals(1, store.saves.get())
    }

    /**
     * Verify 3 (the focused read of fix round 2, D1): the confirm age is measured at the ASK an answer replies to,
     * not at its arrival. The channel can go quiet without ending (a path change under a live connection) while one
     * ask is out; the pinger skips meanwhile and nothing calls [PlaceWatch.onBus]. A fix taken away from the bus in
     * that silence is a candidate; the PC's held-back answer to the ask sent BEFORE the fix arrives 240 s on and says
     * `bus:true` from a read older than the fix. Measured at arrival (fix round 2's form) it saved the away fix.
     * An ask sent [PlaceWatch.LEARN_CONFIRM_MS] after the fix still confirms it, however late its answer; an ask
     * time ahead of the clock (a clock set back between ask and answer) confirms nothing the clock does not.
     * Watched failing with the age read at `clock()` (the arrival) and with it read at `askedAt` alone.
     */
    @Test
    fun aLateAnswerConfirmsOnlyWhatItsAskCould() {
        val src = FakePlaceSource()
        val store = MemPlaceStore()
        val w = watch(src, store)
        w.tick()
        w.onBus(true)                                       // the run is open: the phone is at the bus
        val askSent = clock.get()                           // ask N goes out; the channel then goes quiet
        val away = Places.north(900.0, askSent + MIN, acc = 15f)
        clock.set(away.at)
        src.give(away)                                      // a fix taken away, inside the silence
        clock.set(askSent + 4 * MIN)
        w.onBus(true, askSent)                              // ask N's answer, held back, arrives 240 s on
        assertNull(store.saved, "an answer to an ask sent before the fix does not confirm it, however late it arrives")
        assertEquals(0, said.count { "the bus's position is taken" in it }, said.joinToString("\n"))

        // a clock set back between the ask and its answer: the ask's time is ahead of now and confirms nothing
        clock.set(away.at + PlaceWatch.LEARN_CONFIRM_MS - 1)
        w.onBus(true, away.at + PlaceWatch.LEARN_CONFIRM_MS + MIN)
        assertNull(store.saved, "an ask time ahead of the clock does not confirm what the clock does not")

        // the PC's read for an ask sent 150 s after the fix still says the bus network (the phone was there after
        // all): that answer confirms the candidate, even when it arrives late
        val laterAsk = away.at + PlaceWatch.LEARN_CONFIRM_MS
        clock.set(laterAsk + 3 * MIN)
        w.onBus(true, laterAsk)
        assertSame(away, store.saved, "an ask sent 150 s after the fix confirms it, however late its answer")
    }

    @Test
    fun homeWithin300mAwayBeyond500mTheBandKeepsTheLastWord() {
        val src = FakePlaceSource()
        val w = watch(src, MemPlaceStore(Places.north(0.0, T0 - DAY, null)))
        w.tick()
        val steps = listOf(250.0 to "home", 400.0 to "home", 501.0 to "away", 400.0 to "away", 299.5 to "home",
            499.5 to "home", 500.5 to "away", 300.5 to "away", 299.0 to "home")
        for ((i, s) in steps.withIndex()) {
            clock.addAndGet(MIN)
            src.give(Places.north(s.first, clock.get()))
            assertEquals(s.second, w.currentWord, "step $i at ${s.first} m")
        }
        assertEquals("location: home (fix 0 min old)", w.stateLine())

        // with no word yet, the band decides nothing
        val src2 = FakePlaceSource()
        val w2 = watch(src2, MemPlaceStore(Places.north(0.0, T0 - DAY, null)))
        w2.tick()
        src2.give(Places.north(400.0, clock.get()))
        assertEquals(PhonePresence.UNKNOWN, w2.currentWord)
        assertEquals("location: fix 0 min old, not decided yet", w2.stateLine())
        src2.give(Places.north(600.0, clock.get()))
        assertEquals(PhonePresence.AWAY, w2.currentWord)
        // a fix coarser than the band is not used at all
        val src3 = FakePlaceSource()
        val w3 = watch(src3, MemPlaceStore(Places.north(0.0, T0 - DAY, null)))
        w3.tick()
        src3.give(Places.north(0.0, clock.get(), acc = 250f))
        src3.give(Places.north(0.0, clock.get(), acc = null))
        assertEquals(PhonePresence.UNKNOWN, w3.currentWord, "no usable fix: ${w3.stateLine()}")
    }

    @Test
    fun aFixThirtyMinutesOldIsNoFix() {
        val src = FakePlaceSource()
        val w = watch(src, MemPlaceStore(Places.north(0.0, T0 - DAY, null)))
        w.tick()
        src.give(Places.north(10.0, T0))
        clock.set(T0 + 30 * MIN - 1)
        val fresh = w.report()
        assertEquals(PhonePresence.HOME, fresh.word)
        assertEquals(30 * MIN - 1, fresh.fixAgeMs)
        clock.set(T0 + 30 * MIN)
        val stale = w.report()
        assertEquals(PhonePresence.UNKNOWN, stale.word, "a fix 30 minutes old")
        assertNull(stale.fixAgeMs, "no age goes with unknown")
        assertEquals("location: no fix younger than 30 min", w.stateLine())
        // a cached fix handed over late is judged by when it was TAKEN
        src.give(Places.north(10.0, clock.get() - 31 * MIN))
        assertEquals(PhonePresence.UNKNOWN, w.currentWord)
        src.give(Places.north(10.0, clock.get() - 2 * MIN))
        assertEquals(PhonePresence.HOME, w.report().word)
        assertEquals(2 * MIN, w.report().fixAgeMs)
    }

    @Test
    fun permissionAbsentRevokedRefusedAndTheStacksStop() {
        val src = FakePlaceSource().apply { allowed = false }
        val row = AtomicInteger(1)
        val w = watch(src, MemPlaceStore(Places.north(0.0, T0 - DAY, null))) { row.get() == 1 }
        w.tick()
        assertEquals(0, src.starts.get(), "nothing starts without the permission")
        assertEquals(PhonePresence.UNKNOWN, w.currentWord)
        assertTrue(w.stateLine().startsWith("location: not allowed"), w.stateLine())
        assertEquals(PhonePresence.UNKNOWN, w.report().word)

        src.allowed = true
        w.tick()
        assertEquals(1, src.starts.get())
        src.give(Places.north(0.0, T0))
        assertEquals(PhonePresence.HOME, w.currentWord)

        // revoked mid-run: the next tick stops the updates; a callback that arrives late is not taken
        src.allowed = false
        w.tick()
        assertEquals(1, src.stops.get())
        assertEquals(PhonePresence.UNKNOWN, w.currentWord)
        assertTrue(w.stateLine().startsWith("location: not allowed"), w.stateLine())
        src.lastOnFix!!.invoke(Places.north(2_000.0, T0 + 1))
        assertEquals(PhonePresence.UNKNOWN, w.currentWord, "a late callback after the stop is not taken")

        // the platform refuses although the check passed: caught, said once, tried again each minute
        src.allowed = true
        src.refuse = SecurityException("the stand-in platform refuses")
        w.tick()
        w.tick()
        assertEquals(3, src.starts.get(), "tried again at the next tick")
        assertTrue("refused by the platform (SecurityException: the stand-in platform refuses)" in w.stateLine(), w.stateLine())
        assertEquals(1, said.count { "SecurityException" in it }, "said once: ${said.joinToString("\n")}")
        assertEquals(1, logs.count { "WARN" in it && "SecurityException" in it })
        assertEquals(PhonePresence.UNKNOWN, w.report().word)
        src.refuse = null
        w.tick()
        assertEquals(4, src.starts.get())
        assertEquals(PhonePresence.HOME, w.currentWord, "the late far fix was not taken: the last fix taken still decides")
        src.give(Places.north(0.0, T0 + 2))
        assertEquals(PhonePresence.HOME, w.currentWord, "started again: ${w.stateLine()}")

        // the row off stops the updates; the stack's stop ends them for good
        row.set(0)
        w.tick()
        assertEquals(2, src.stops.get())
        assertEquals("location: off (the Presence · location row)", w.stateLine())
        row.set(1)
        w.tick()
        assertEquals(5, src.starts.get())
        w.close()
        assertEquals(3, src.stops.get(), "the stack's stop removes the updates")
        w.tick()
        assertEquals(5, src.starts.get(), "a tick after the stack's stop starts nothing")
    }

    @Test
    fun theStoredPositionSurvivesARestartAndItsTextIsNeverSaid() {
        val dir = Files.createTempDirectory("damage-place")
        try {
            val file = dir.resolve("bus-place.txt")
            val store = FilePlaceStore(file)
            assertNull(store.load())
            val f = Places.north(0.0, T0)
            store.save(f)
            val back = FilePlaceStore(file).load()!!
            assertEquals(f.lat, back.lat)
            assertEquals(f.lon, back.lon)
            assertEquals(f.at, back.at)
            Files.writeString(file, "${Places.BUS_LAT} not-a-number")
            val src = FakePlaceSource()
            val w = watch(src, FilePlaceStore(file))
            w.tick()
            src.give(Places.north(0.0, T0))
            assertTrue("no bus position yet" in w.stateLine(), "an unreadable file is no position: ${w.stateLine()}")
            val line = logs.single { "stored bus position could not be read" in it }
            assertTrue("12.34" !in line, "the file's text is not said: $line")
            assertEquals("Fix(taken at $T0)", f.toString(), "a fix prints no number of the position")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    companion object {
        const val T0 = 1_800_000_000_000L
        const val MIN = 60_000L
        const val DAY = 24 * 60 * MIN
    }
}
