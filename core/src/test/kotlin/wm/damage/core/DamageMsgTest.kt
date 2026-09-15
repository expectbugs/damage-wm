package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.Pb

/** `FIRMWARE.md` §0/§3 (draft, Phase 1): the DamageCaps field, the control ops and the
 *  telemetry record — bytes from the contract text, then through the transport's probes
 *  against the simulator's model of a Damage build and of the installed upstream build. */
class DamageMsgTest {

    @Test
    fun controlBytesFollowTheContract() {
        // G2SettingPackage{1: 1, 2: 0, 112: ['D','M',1,op,argLo,argHi]}; field 112 wire 2 = 82 07
        assertEquals("08011000" + "8207" + "06" + "444d01012a00", DamageMsg.control(DamageMsg.OP_TELEMETRY, 42).hex())
        assertEquals("08011000" + "8207" + "06" + "444d01020080", DamageMsg.control(DamageMsg.OP_FLAGS_SET, DamageMsg.FLAG_PROBE).hex())
        assertEquals("08011000" + "8207" + "06" + "444d01040700", DamageMsg.control(DamageMsg.OP_CACHE_INFO, 7).hex())
        // the self-test rides the image lane as mode 16 (CfwModes)
        assertEquals("1000", wm.damage.core.wire.CfwModes.selfTestBegin().hex())
        assertEquals("1002", wm.damage.core.wire.CfwModes.selfTestEnd().hex())
        assertEquals("1001" + "0900000000", wm.damage.core.wire.CfwModes.selfTestStep("0900000000".unhex()).hex())
        assertTrue(runCatching { wm.damage.core.wire.CfwModes.selfTestStep("0c0000".unhex()) }.isFailure, "a cache write is not a self-test step")
    }

    @Test
    fun presentedNotifyParses() {
        // field 113 DamagePresented { 1 seq, 2 worker µs, 3 copy µs, 4 transfer µs, 5 lens }
        val body = Pb.cat(Pb.v(1, 42), Pb.v(2, 900), Pb.v(3, 210), Pb.v(4, 3100), Pb.v(5, 1))
        val p = assertNotNull(DamageMsg.parsePresented(Pb.cat(Pb.v(1, 3), Pb.v(2, 0), Pb.l(113, body))))
        assertEquals(DamageMsg.Presented(42, 900, 210, 3100, 1), p)
        assertNull(DamageMsg.parsePresented(Pb.cat(Pb.v(1, 3), Pb.v(2, 0))), "no field 113, no notify")
        val t = assertNotNull(DamageMsg.parseTelemetry(Pb.cat(Pb.v(1, 3), Pb.v(2, 0),
            Pb.l(111, Pb.cat(Pb.v(1, 1), Pb.v(15, 3100), Pb.v(16, 7), Pb.v(17, 2), Pb.v(18, 65536), Pb.v(19, 0x1234abcdL), Pb.v(20, 3), Pb.v(21, 0), Pb.v(22, 0x066e64a1L))))))
        assertEquals("id=1 transferUs=3100 presents=7 cacheGen=2 cacheSize=65536 cacheCrc=1234abcd stSteps=3 stRefused=0 stCrc=066e64a1", t.describe())
        assertEquals(3100L, t.transferUs); assertEquals(0x066e64a1L, t.selfTestCrc)
    }

    /** F1.3 and F1.5 through the transport against the model: the presented notify per
     *  present while armed (RIGHT only), the cache's generation, and the cache kept across a
     *  lapse and the fresh acquire after it under CACHE_KEEP. */
    @Test
    fun presentedNotifyAndCacheKeepThroughTheTransport(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim().also { it.damageContract = 1 }
            var clock = 1_000_000L
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true), clock = { clock })
            val notes = ArrayList<Pair<String, String>>()
            val presented = ArrayList<TransportEvent.Presented>()
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                t.events.collect {
                    if (it is TransportEvent.Note) synchronized(notes) { notes.add(it.kind to it.detail) }
                    if (it is TransportEvent.Presented) synchronized(presented) { presented.add(it) }
                }
            }
            fun has(kind: String, text: String) = synchronized(notes) { notes.any { it.first == kind && text in it.second } }
            val full = Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480)))
            t.start(full)
            until("the Damage build is named") { has("glass", "DamageCaps contract 1 features 0x1f") }
            assertTrue(synchronized(presented) { presented.isEmpty() }, "no notify while unarmed")
            t.devProbe("flags", "0x0003")             // PRESENTED | CACHE_KEEP
            until("both arms armed") { Arm.entries.all { sim.damageFlags(it) == 3 } }
            t.submit(wm.damage.core.transport.FlushRequest(listOf(wm.damage.core.transport.DisplayOp.Keyframe(full)), 1L, "test"))
            until("one presented notify, from RIGHT, with the glasses' count") {
                synchronized(presented) { presented.size == 1 && presented[0].seq == 1L }   // the warmup burst is dropped by the firmware, never presented
            }
            // a cache write bumps the generation on both arms
            val write = wm.damage.core.wire.CfwModes.cacheUpdate(listOf(wm.damage.core.wire.CfwModes.CacheWrite(0, ByteArray(300) { it.toByte() })))
            t.submit(wm.damage.core.transport.FlushRequest(listOf(wm.damage.core.transport.DisplayOp.CacheWrite(write)), 1L, "atlas"))
            until("the cache is written on both arms") { Arm.entries.all { sim.cacheGen(it) == 1L && sim.cacheAllocated(it) } }
            t.devProbe("cache", "info")
            until("CACHE_INFO reports the generation, size and CRC") { has("glass", "cacheGen=1 cacheSize=65536 cacheCrc=") }
            // the lease lapses on the glasses' clock: the flags clear, the cache stays (F1.5)
            clock += 200_000L
            t.devProbe("telemetry", "read")
            until("the lapse cleared the flags") { Arm.entries.all { sim.damageFlags(it) == 0 } }
            assertTrue(Arm.entries.all { sim.cacheAllocated(it) }, "CACHE_KEEP: the cache survives the lapse on both arms")
            // the fresh acquire that follows carries it over (the latch is spent)
            for (arm in Arm.entries) sim.conformanceLease(arm, true, clock)
            assertTrue(Arm.entries.all { sim.cacheAllocated(it) && sim.cacheGen(it) == 1L }, "the cache carried over the fresh acquire")
            // not re-armed: the next lapse frees it (upstream's rule)
            clock += 200_000L
            t.devProbe("telemetry", "read")
            until("the second lapse freed the cache") { Arm.entries.none { sim.cacheAllocated(it) } }
            t.stop()
        } finally {
            scope.cancel()
        }
    }

    /** `FORK.md` §3.2: the keeper re-arms the wanted flags after every session start — unless
     *  the glasses reset within the hold-back window after the last arming, which disarms the
     *  wish and says so. The model's uptime is its clock less an offset; a reset sets the offset. */
    @Test
    fun theKeeperReArmsAndHoldsBackAfterAReset(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim().also { it.damageContract = 1 }
            var clock = 1_000_000L
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true), clock = { clock })
            val notes = ArrayList<Pair<String, String>>()
            val faults = ArrayList<String>()
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                t.events.collect {
                    if (it is TransportEvent.Note) synchronized(notes) { notes.add(it.kind to it.detail) }
                    if (it is TransportEvent.Fault) synchronized(faults) { faults.add("${it.what}: ${it.detail}") }
                }
            }
            fun has(kind: String, text: String) = synchronized(notes) { notes.any { it.first == kind && text in it.second } }
            val full = Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480)))
            t.start(full)
            t.devProbe("flags", "probe")
            until("armed by the probe") { Arm.entries.all { sim.damageFlags(it) == DamageMsg.FLAG_PROBE } }
            // the glasses reset 10 s after the arming; the lease lapses; the session is rebuilt
            clock += 10_000L
            sim.uptimeOffsetMs = clock
            clock += 200_000L
            assertTrue(t.restartSession("test: after a reset"))
            t.start(full)
            until("the reset is seen and the wish held back") { has("keeper", "hold-back: the glasses reset 10 s after flags 0x8000 were armed") }
            until("a fault the wearer sees") { synchronized(faults) { faults.any { it.startsWith("holdback:") } } }
            delay(300)
            assertTrue(Arm.entries.all { sim.damageFlags(it) == 0 }, "nothing armed after the hold-back")
            // asked for again: armed now, and re-armed after a rebuild with no reset
            t.devProbe("flags", "probe")
            until("armed again by the probe") { Arm.entries.all { sim.damageFlags(it) == DamageMsg.FLAG_PROBE } }
            clock += 200_000L                           // a lapse clears the flags on the glasses; no reset
            assertTrue(t.restartSession("test: no reset"))
            t.start(full)
            until("the keeper re-armed the wish after the start") { has("keeper", "armed bit 15 — flags in force 0x8000") }
            until("in force on both arms") { Arm.entries.all { sim.damageFlags(it) == DamageMsg.FLAG_PROBE } }
            t.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun capsAndTelemetryParse() {
        // FIRMWARE.md §0's own example body: 0a 03 44 4d 47 10 01 18 00
        val read = Pb.cat(Pb.v(1, 2), Pb.v(2, 5), Pb.l(110, "0a03444d4710011800".unhex()))
        assertEquals(DamageMsg.Caps(1, 0), DamageMsg.parseCaps(read))
        assertNull(DamageMsg.parseCaps(Pb.cat(Pb.v(1, 2), Pb.l(110, Pb.s(1, "XYZ")))), "a field 110 without the marker is not DamageCaps")
        assertNull(DamageMsg.parseCaps(Pb.cat(Pb.v(1, 2), Pb.v(2, 5))), "an upstream READ has no field 110")

        val record = Pb.cat(Pb.v(1, 42), Pb.v(2, 5000), Pb.v(3, 0x8000), Pb.v(4, 0), Pb.v(10, 0x0070B024), Pb.v(12, 90000), Pb.v(14, 2))
        val t = assertNotNull(DamageMsg.parseTelemetry(Pb.cat(Pb.v(1, 3), Pb.v(2, 0), Pb.l(111, record))))
        assertEquals(42L, t.requestId); assertEquals(0x8000L, t.flags); assertEquals(90000L, t.leaseMsLeft); assertEquals(2L, t.lens)
        assertEquals("id=42 uptimeMs=5000 flags=0x8000 lastStatus=0 panel=0x70b024(JBD4010) leaseMs=90000 lens=2", t.describe())
    }

    @Test
    fun probesAgainstADamageBuildAndAnUpstreamBuild(): Unit = runBlocking {
        for (contract in listOf(1, null)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val sim = GlassFirmwareSim().also { it.damageContract = contract }
                var clock = 1_000_000L
                val t = SimTransport(sim, scope, SimTransport.Timing(instant = true), clock = { clock })
                val notes = ArrayList<Pair<String, String>>()
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    t.events.collect { if (it is TransportEvent.Note) synchronized(notes) { notes.add(it.kind to it.detail) } }
                }
                fun has(kind: String, text: String) = synchronized(notes) { notes.any { it.first == kind && text in it.second } }
                t.start(Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480))))

                if (contract == null) {
                    until("the upstream build is named") { has("glass", "no DamageCaps field") }
                    t.devProbe("telemetry", "read")
                    until("a probe into an upstream build says what to expect") { has("probe", "without DamageCaps") }
                    delay(200)
                    assertTrue(!has("glass", "uptimeMs="), "an upstream build answers no telemetry")
                    t.stop()
                    continue
                }
                until("the Damage build is named") { has("glass", "DamageCaps contract 1 features 0x1f") }
                t.devProbe("flags", "probe")
                until("both arms armed") { Arm.entries.all { sim.damageFlags(it) == DamageMsg.FLAG_PROBE } }
                // only RIGHT can answer (the stock senders' lens rule, CLAIMS.md 2026-09-14); LEFT armed all the same
                until("RIGHT answered with the flag in force") { has("glass", "R ") && has("glass", "flags=0x8000") }
                delay(200)
                assertTrue(synchronized(notes) { notes.none { it.first == "glass" && it.second.startsWith("L ") } }, "LEFT sends nothing")
                t.devProbe("flags", "0x0100")            // bit 8: no Phase 1 build implements it
                until("an unimplemented bit answers with the register at 2") { has("glass", "lastStatus=2") }
                assertTrue(Arm.entries.all { sim.damageFlags(it) == DamageMsg.FLAG_PROBE }, "a refused set changes nothing")
                // a lease lapse on the glasses' clock clears the flags (the texture cache's release points)
                clock += 200_000L
                t.devProbe("telemetry", "read")
                until("the lapse cleared both arms") { Arm.entries.all { sim.damageFlags(it) == 0 } }
                // the register is not the flags: the refusal is still readable after the lapse,
                // and only a recording op (here FLAGS_CLEAR) writes it
                until("the lapse left the register alone") { has("glass", "flags=0x0 lastStatus=2") }
                t.devProbe("flags", "clear")
                until("FLAGS_CLEAR records 0") { has("glass", "flags=0x0 lastStatus=0") }
                t.devProbe("flags", "bogus")
                t.stop()
            } finally {
                scope.cancel()
            }
        }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun String.unhex() = ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    private suspend fun until(what: String, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < 10_000) delay(5)
        assertTrue(cond(), "never true: $what")
    }
}
