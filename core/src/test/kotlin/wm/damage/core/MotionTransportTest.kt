package wm.damage.core

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
import wm.damage.core.motion.Motion
import wm.damage.core.motion.MotionProgram
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.CfwTransportBase
import wm.damage.core.transport.DisplayOp
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.EvenHubMsg
import wm.damage.core.wire.Pb
import wm.damage.core.wire.SettingsMsg

/**
 * Build C1, package D3a: the phone's transport for a C1 build (spec §6.1, §6.2, §6.4, §6.5). The proof the package
 * owes: with a firmware that reports no C1 bit, and with one that reports them while the ladder is at stage 0, a
 * session start writes byte for byte what it wrote before (the awake-start expectation file, untouched); each stage
 * adds exactly its own change; the hold-back drops one stage; a disarm of ENGINE CLOSEs first; a write that meets an
 * open area CLOSEs it in its batch; a notify's END CRC is compared with the mirror's and a gap is journaled; the
 * shell's agreement check skips an area while the lens draws it on its own clock. Nothing here contacts a device.
 */
class MotionTransportTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @AfterTest fun clean() { scope.cancel() }

    private val full = Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480)))
    private val notes = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
    private fun has(kind: String, text: String) = notes.any { it.first == kind && text in it.second }
    private fun collect(t: CfwTransportBase) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            t.events.collect {
                if (it is TransportEvent.Note) notes.add(it.kind to it.detail)
                if (it is TransportEvent.Fault) notes.add("fault" to "${it.what}: ${it.detail}")
            }
        }
    }

    // ------------------------------------------------------------------------------------------- the start's bytes
    /** One awake start's packets as `SilentStartTest.anAwakeStartWritesWhatItWroteBefore` takes them. */
    private suspend fun startWire(features: Int?, stage: Int): List<String> {
        val glass = GlassFirmwareSim().apply { damageContract = 2; damageFeatures = features }
        val t = StartRig(glass, scope, clock = { 1_763_000_000_000L }, instantPacing = false)
        t.heapReads = false
        if (stage != 0) t.devProbe("c1", "$stage")
        val sent = java.util.Collections.synchronizedList(ArrayList<String>())
        t.onWrite = { a, p -> sent.add("${a.name.first()} " + p.joinToString("") { "%02x".format(it) }); p }
        t.start(full)
        delay(300)
        val got = synchronized(sent) { sent.toList() }
        t.stop()
        return got
    }

    private fun unhex(h: String) = ByteArray(h.length / 2) { i -> h.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    /** The DamageControl ops a start wrote to [arm], "op:arg" in hex, the reads (1, 4) left out: what a stage arms. */
    private fun controls(wire: List<String>, arm: Char): List<String> = wire.filter { it[0] == arm }.mapNotNull { line ->
        val p = unhex(line.substring(2))
        val op = StartRig.damageOp(p)
        if (op < 0 || op == DamageMsg.OP_TELEMETRY || op == DamageMsg.OP_CACHE_INFO) null
        else { val b = StartRig.body(p); val i = StartRig.controlAt(b)
            "$op:" + ((b[i + 4].toInt() and 0xFF) or ((b[i + 5].toInt() and 0xFF) shl 8)).toString(16) }
    }

    /** Every packet that is not a FLAGS_SET or a CACHE_SIZE, with the AA sequence byte and the CRC taken off (one more
     *  packet moves every later sequence number). */
    private fun rest(wire: List<String>): List<String> = wire.filter { line ->
        val op = StartRig.damageOp(unhex(line.substring(2))); op != DamageMsg.OP_FLAGS_SET && op != DamageMsg.OP_CACHE_SIZE
    }.map { line ->
        val h = line.substring(2)
        // an EvenHub message's msgId (`08 <cmd> 10 <msgId>` after the 8-byte header) moves by one per extra control write
        val body = if (StartRig.sid(unhex(h)) == EvenHubMsg.SID) h.substring(16, 22) + "··" + h.substring(24, h.length - 4) else h.substring(16, h.length - 4)
        line.substring(0, 2) + h.substring(0, 4) + h.substring(6, 16) + body
    }

    /** Spec §6.2: the installed image (features 0x7f) at any stage, an upstream-shaped Damage build that reports no
     *  features word, and a C1 build (0x1ff) at stage 0 write exactly `awake-start-contract2.txt`. */
    @Test
    fun theInstalledImageAndAStage0C1BuildWriteTodaysStart_6_2(): Unit = runBlocking {
        val want = javaClass.getResource("/startwire/awake-start-contract2.txt")!!.readText().lines().filter { it.isNotBlank() }
        for ((features, stage) in listOf<Pair<Int?, Int>>(null to 0, null to 3, 0x7f to 3, 0x1ff to 0)) {
            val got = startWire(features, stage)
            val what = "features ${features?.let { "0x" + it.toString(16) } ?: "derived"} stage $stage"
            assertEquals(want.size, got.size, "$what: packet count")
            for (i in want.indices) assertEquals(want[i], got[i], "$what: packet $i differs from the contract-2 start")
        }
    }

    /** Spec §6.1/§6.2: each stage adds exactly its own change to the stage-0 start — ZWRITE at 1, the 256 KiB size at
     *  2, ENGINE at 3 — and only on a build with the bit it needs; every other packet is stage 0's. */
    @Test
    fun eachLadderStageAddsOnlyItsOwnChange_6_2(): Unit = runBlocking {
        val base = startWire(0x1ff, 0)
        val cases = listOf(
            Triple(0x1ff, 0, listOf("5:a0", "2:4")),
            Triple(0x1ff, 1, listOf("5:a0", "2:4", "2:24")),
            Triple(0x1ff, 2, listOf("5:100", "2:4", "2:24")),
            Triple(0x1ff, 3, listOf("5:100", "2:4", "2:c", "2:2c")),
            Triple(0x17f, 3, listOf("5:100", "2:4", "2:24")),             // the riders without the engine: no ENGINE
            Triple(0x0ff, 3, listOf("5:a0", "2:4", "2:c")))               // the engine without the riders: no ZWRITE, 160
        for ((features, stage, want) in cases) {
            val got = if (features == 0x1ff && stage == 0) base else startWire(features, stage)
            val what = "features 0x${features.toString(16)} stage $stage"
            for (arm in listOf('L', 'R')) assertEquals(want, controls(got, arm), "$what: the cache size and flag sets written to $arm")
            assertEquals(rest(base), rest(got), "$what: every other packet is stage 0's")
        }
    }

    /** Spec §6.1: a reset within the hold-back after a stage-3 arming leaves stage 2 — ENGINE off, ZWRITE and the 256
     *  KiB cache kept, DRAW2 armed; each further reset drops one more stage; below stage 0 today's hold-back stands. */
    @Test
    fun theHoldBackDropsOneStageAndKeepsTheRest_6_1(): Unit = runBlocking {
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x1ff }
        var clock = 1_000_000L
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true), clock = { clock })
        collect(t)
        t.devProbe("c1", "3")
        t.start(full)
        fun said() = notes.filter { it.first == "keeper" }.joinToString(" | ") { it.second }
        assertEquals(0x2c, t.state.value.flagsInForce, "stage 3 armed: ${said()}")
        assertEquals(256 * 1024, t.state.value.cacheSize)
        suspend fun resetAndStart() {
            clock += 10_000L; sim.uptimeOffsetMs = clock; clock += 200_000L
            assertTrue(t.restartSession("test: after a reset"))
            t.start(full)
        }
        for ((stage, flags, kib) in listOf(Triple(2, 0x24, 256), Triple(1, 0x24, 160), Triple(0, 0x4, 160))) {
            resetAndStart()
            waitUntil(10_000, message = { "the hold-back to stage $stage: ${said()}" }) { has("keeper", "armed stage ${stage + 1} — stage $stage from here") }
            assertEquals(flags, t.state.value.flagsInForce, "stage $stage: the flags in force, DRAW2 kept: ${said()}")
            assertEquals(kib * 1024, t.state.value.cacheSize, "stage $stage: the cache size")
        }
        resetAndStart()
        waitUntil(10_000, message = { "today's hold-back below stage 0: ${said()}" }) { has("keeper", "hold-back: the glasses reset 10 s after flags 0x4 were armed") }
        assertEquals(0, t.state.value.flagsInForce, "DRAW2 held back, as today")
        t.stop()
    }

    // ------------------------------------------------------------------------------------------- motion on a session
    /** A session at stage 3 on a C1 build: ENGINE in force. */
    private suspend fun engineSession(): Pair<GlassFirmwareSim, SimTransport> {
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x1ff }
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        collect(t)
        t.devProbe("c1", "3")
        t.start(full)
        assertEquals(0x2c, t.state.value.flagsInForce, "ENGINE in force")
        return sim to t
    }

    private fun program(area: Rect, steps: Int, at: Int = 40_000): MotionProgram =
        Motion.program(at) { track(area, Motion.Backdrop.Level(3)) { fill(9, steps) } }

    private fun messages(p: MotionProgram, slot: Int, period: Int, tag: Int) = listOf(
        CfwModes.cacheUpdate2(p.records.map { (o, b) -> CfwModes.CacheWrite2(o, b) }, 256 * 1024),
        Motion.play(p, slot, period, tag, 0, IntArray(4)))

    /** `FIRMWARE.md` §5.8: a FLAGS_SET that clears ENGINE goes out only after a CLOSE of every instance reached both
     *  lenses — the glasses see the CLOSE before the disarm. */
    @Test
    fun aDisarmOfEngineClosesEveryInstanceFirst_5_8(): Unit = runBlocking {
        val (sim, t) = engineSession()
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        sim.imageObserver = { arm, img -> if (img.size == 3 && img[0].toInt() == 26 && img[1].toInt() == 2) seen += "$arm CLOSE ${img[2].toInt() and 0xFF} after ${sim.damageControlWritesSeen} control writes" }
        val before = sim.damageControlWritesSeen
        t.devProbe("flags", "0x4")
        waitUntil(10_000, message = { "the disarm reached the glasses: flags 0x${sim.damageFlags(Arm.RIGHT).toString(16)}; $seen" }) {
            Arm.entries.all { sim.damageFlags(it) == 0x4 } }
        for (arm in Arm.entries) assertTrue("$arm CLOSE 255 after $before control writes" in seen, "$arm took a CLOSE of every instance before the FLAGS_SET: $seen")
        t.stop()
    }

    /** `FIRMWARE.md` §5.6, ruling 4: a write that meets an open instance's area carries a CLOSE of it at the head of its
     *  batch; a write elsewhere goes as the encoder made it. */
    @Test
    fun aWriteThatMeetsAnOpenAreaClosesItInItsBatch_5_6(): Unit = runBlocking {
        val (sim, t) = engineSession()
        val area = Rect(100, 100, 40, 20)
        assertTrue(t.sendMotion(messages(program(area, 200), slot = 1, period = 128, tag = 0x51)))
        waitUntil(10_000, message = { "the PLAY landed" }) { sim.motionWindows(Arm.RIGHT).any { it.slot == 1 } }
        val batches = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        sim.imageObserver = { arm, img -> if (arm == Arm.RIGHT && img[0].toInt() == 8) batches += img }
        t.submit(FlushRequest(listOf(DisplayOp.Fill(Rect(400, 300, 8, 4), Rect(400, 300, 8, 4), 7)), 0, "elsewhere"))
        waitUntil(10_000, message = { "the first flush landed" }) { batches.size >= 1 }
        assertEquals(1, batches[0][1].toInt(), "a write elsewhere: the encoder's batch, one sub")
        assertTrue(sim.motionWindows(Arm.RIGHT).any { it.slot == 1 }, "the instance still open")
        t.submit(FlushRequest(listOf(DisplayOp.Fill(Rect(110, 104, 8, 4), Rect(110, 104, 8, 4), 7)), 0, "over the area"))
        waitUntil(10_000, message = { "the second flush landed" }) { batches.size >= 2 }
        val b = batches[1]
        assertEquals(2, b[1].toInt(), "the CLOSE and the fill")
        assertEquals(listOf(3, 0, 26, 2, 1), (2 until 7).map { b[it].toInt() and 0xFF }, "the CLOSE of slot 1 heads the batch")
        assertTrue(sim.motionWindows(Arm.RIGHT).none { it.slot == 1 }, "the instance is closed")
        t.stop()
    }

    /** Spec §6.4: a notify's END CRC equal to the mirror's is a `motion` note only; a different one is a `divergence`
     *  note (the shell keyframes on it); a sequence at or below field 31 that never came is journaled as a gap. */
    @Test
    fun aNotifyIsComparedWithTheMirrorAndAGapIsJournaled_6_4(): Unit = runBlocking {
        val glass = GlassFirmwareSim().apply { damageContract = 2; damageFeatures = 0x1ff }
        val t = StartRig(glass, scope)
        collect(t)
        t.devProbe("c1", "3")
        t.start(full)
        assertEquals(0x2c, t.state.value.flagsInForce)
        // the rig's model answers inside the write, before the transport's own mirror has taken the packet; a notify
        // is handed back a little later, as the radio's round trip does
        t.onNotify = { arm, p ->
            val m = if (StartRig.sid(p) == SettingsMsg.SID) DamageMsg.parseMotion(StartRig.body(p)) else null
            val out = when (m?.tag) {
                null -> p
                0x62 -> { val f = java.util.TreeMap(m.fields).also { it[8] = it[8]!! xor 1L }
                    StartRig.reframe(p, Pb.cat(Pb.v(1, 3), Pb.v(2, 0), Pb.l(DamageMsg.MOTION_FIELD, Pb.cat(f.entries.map { Pb.v(it.key, it.value) })))) }
                0x63 -> null                                              // lost on the way
                else -> p
            }
            if (m == null) out else { if (out != null) scope.launch { delay(20); t.deliver(arm, out) }; null }
        }
        val p = program(Rect(200, 200, 40, 20), 1)                     // T = 1: the PLAY's own present draws its last step
        assertTrue(t.sendMotion(messages(p, 0, 34, 0x61)))
        waitUntil(10_000, message = { "the first notify: $notes" }) { has("motion", "tag 0x0061 drew its last step") }
        assertTrue(notes.none { it.first == "divergence" || "not compared" in it.second }, "an equal END CRC, compared: no divergence: $notes")
        assertTrue(t.sendMotion(listOf(Motion.play(p, 0, 34, 0x62, 0, IntArray(4)))))
        waitUntil(10_000, message = { "the altered notify: $notes" }) { has("divergence", "motion: slot 0 tag 0x0062 ordinal 0 END CRC") }
        assertTrue(t.sendMotion(listOf(Motion.play(p, 0, 34, 0x63, 0, IntArray(4)))))
        waitUntil(10_000, message = { "the third PLAY landed" }) { glass.motionForTest(Arm.RIGHT).sequence >= 3 }
        t.devProbe("telemetry", "read")
        waitUntil(10_000, message = { "the gap: $notes" }) { has("motion", "gap: notify sequence(s) 3 not received by the telemetry read (field 31 = 3)") }
        t.stop()
    }

    /** Spec §6.5: an instance's areas are unknown from its PLAY until the LATER of RIGHT's notify and its nominal end
     *  (step T's tick); a CLOSE ends the window at once. */
    @Test
    fun theAreaIsUnknownUntilTheLaterOfTheNotifyAndTheNominalEnd_6_5() {
        val sim = GlassFirmwareSim().apply { damageContract = 2; damageFeatures = 0x1ff; clockIsOsTick = true }
        var tick = 1000L
        sim.motionClock = { tick }
        val area = Rect(100, 100, 40, 20)
        val p = program(area, 5, at = 1000)
        for (arm in Arm.entries) {
            sim.conformanceLease(arm, true, tick)
            sim.conformanceControl(arm, DamageMsg.OP_FLAGS_SET, 0xC, tick)
            assertTrue(sim.conformanceMessage(arm, CfwModes.keyframe(full), tick))
            assertTrue(sim.conformanceMessage(arm, CfwModes.cacheUpdate2(p.records.map { (o, b) -> CfwModes.CacheWrite2(o, b) }, 64 * 1024), tick))
        }
        fun play(tag: Int) = Arm.entries.forEach { assertTrue(sim.conformanceMessage(it, Motion.play(p, 0, 20, tag, 0, IntArray(4)), tick)) }
        play(1)                                                          // nominal end: 1000 + 4 × 20 = 1080
        assertEquals(listOf(area), sim.unknownAreas(Arm.RIGHT))
        tick = 1079; sim.motionNotified(0, 1, 0)
        assertEquals(listOf(area), sim.unknownAreas(Arm.LEFT), "notified, the nominal end not reached: still unknown")
        tick = 1080
        assertEquals(emptyList(), sim.unknownAreas(Arm.LEFT), "both: known")
        tick = 2000; play(2)
        tick = 2200
        assertEquals(listOf(area), sim.unknownAreas(Arm.RIGHT), "past the nominal end, no notify: still unknown")
        sim.motionNotified(0, 2, 0)
        assertEquals(emptyList(), sim.unknownAreas(Arm.RIGHT), "both: known")
        tick = 3000; play(3)
        Arm.entries.forEach { assertTrue(sim.conformanceMessage(it, Motion.close(0), tick)) }
        assertEquals(emptyList(), sim.unknownAreas(Arm.RIGHT), "a CLOSE: the shadow is shown")
    }

    /** Spec §6.5 at the shell: while an instance draws, its area's pixels are the lens's own; the agreement check does
     *  not compare them (the model's panel shows a step, belief does not) — no divergence episode. */
    @Test
    fun theShellsAgreementCheckSkipsAnAreaTheLensDrawsOnItsOwnClock_6_5(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-motion-skip")
        try {
            val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x1ff }
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            t.devProbe("c1", "3")
            var clock = Shell.LocalClock(12, 0, "12:00", "PM")
            val shell = Shell(FakeText(), t, Persistence(tmp.resolve("s.json")), null, scope) { clock }
            shell.start()
            waitUntil(10_000, 10, { "shell did not settle" }) { shell.isQuiescent() }
            assertEquals(0x2c, t.state.value.flagsInForce, "ENGINE in force under the shell")
            assertEquals(0, shell.divergencesReported)
            // a long instance in the bottom-right corner, which the clock's flush does not repaint
            val area = Rect(560, 440, 60, 30)
            assertTrue(t.sendMotion(messages(program(area, 200), slot = 2, period = 128, tag = 0x71)))
            waitUntil(10_000, message = { "the PLAY landed" }) { sim.motionWindows(Arm.LEFT).any { it.slot == 2 } }
            val images = java.util.concurrent.atomic.AtomicInteger()
            sim.imageObserver = { arm, _ -> if (arm == Arm.RIGHT) images.incrementAndGet() }
            // the clock's minute: a small flush in the top chrome, after which the shell checks belief against the mirror
            clock = Shell.LocalClock(12, 1, "12:01", "PM")
            shell.postMinuteTick()
            waitUntil(10_000, 10, { "shell did not settle" }) { images.get() > 0 && shell.isQuiescent() }
            assertTrue(images.get() > 0, "the minute produced a real flush")
            assertEquals(0, shell.divergencesReported, "the area the lens draws is not compared: ${shell.lastDivergence}")
            assertTrue(sim.motionWindows(Arm.LEFT).any { it.slot == 2 }, "the flush did not meet the area: the instance still draws")
            shell.stop()
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
