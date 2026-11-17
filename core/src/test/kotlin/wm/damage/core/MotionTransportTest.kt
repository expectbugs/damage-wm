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
import kotlinx.coroutines.async
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
        // build C1's ruling B-S1: the window also needs this model to have drawn the instance to its end — a mirror is
        // given no wake, and its panel holds the step its last present drew
        assertEquals(listOf(area), sim.unknownAreas(Arm.LEFT), "notified and at the nominal end, but this model still draws it: unknown")
        Arm.entries.forEach { sim.conformanceWake(it, tick) }          // a refresh at the end draws step T here
        assertEquals(emptyList(), sim.unknownAreas(Arm.LEFT), "all three: known")
        tick = 2000; play(2)
        tick = 2200
        Arm.entries.forEach { sim.conformanceWake(it, tick) }
        assertEquals(listOf(area), sim.unknownAreas(Arm.RIGHT), "past the nominal end and drawn to it, no notify: still unknown")
        sim.motionNotified(0, 2, 0)
        assertEquals(emptyList(), sim.unknownAreas(Arm.RIGHT), "all three: known")
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

    // ------------------------------------------------------------------------------------------- mode 25 (A.4)
    /** `FIRMWARE.md` §5.9, spec §6.2: with ZWRITE in force a mode-19 list goes as mode 25; RIGHT's refusal of it (the
     *  record at the next read) sends the same list as mode 19 and the count moves by one write; mode 25 is then off
     *  for the session. */
    @Test
    fun aRefusedMode25GoesAgainAsMode19AndTheCountMovesByOne_5_9(): Unit = runBlocking {
        val glass = GlassFirmwareSim().apply { damageContract = 2; damageFeatures = 0x1ff }
        val t = StartRig(glass, scope)
        collect(t)
        t.devProbe("c1", "1")
        t.start(full)
        assertEquals(0x24, t.state.value.flagsInForce, "stage 1: DRAW2 and ZWRITE")
        val right = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        glass.imageObserver = { arm, img -> if (arm == Arm.RIGHT && (img[0].toInt() == 19 || img[0].toInt() == 25)) right += img }
        fun list(off4: Int) = CfwModes.cacheUpdate2(listOf(CfwModes.CacheWrite2(off4, ByteArray(2000) { (it / 100).toByte() })), 160 * 1024)
        val a = list(1000)
        t.submit(FlushRequest(listOf(DisplayOp.CacheWrite2(a)), 0, "ATLAS"))
        waitUntil(10_000, message = { "the first write landed" }) { right.size >= 1 }
        assertEquals(25, right[0][0].toInt(), "ZWRITE in force and the deflated form smaller: mode 25")
        assertEquals(1L, glass.cacheGen(Arm.RIGHT), "one write, one bump")
        t.devProbe("telemetry", "read")
        delay(200)
        assertEquals(1, right.size, "a landed mode 25 is not sent again")
        // RIGHT's ZWRITE goes off behind the phone's back: the next mode 25 is refused there (15)
        glass.conformanceControl(Arm.RIGHT, DamageMsg.OP_FLAGS_SET, DamageMsg.FLAG_DRAW2, System.currentTimeMillis())
        val b = list(2000)
        t.submit(FlushRequest(listOf(DisplayOp.CacheWrite2(b)), 0, "ATLAS"))
        waitUntil(10_000, message = { "the second write reached RIGHT" }) { right.size >= 2 }
        assertEquals(25, right[1][0].toInt())
        assertEquals(1L, glass.cacheGen(Arm.RIGHT), "refused: no bump")
        t.devProbe("telemetry", "read")
        waitUntil(10_000, message = { "the fall-back: $notes" }) { right.size >= 3 }
        assertTrue(right[2].contentEquals(b), "the same list, as mode 19")
        waitUntil(10_000, message = { "the bump" }) { glass.cacheGen(Arm.RIGHT) == 2L }
        assertTrue(has("glass", "mode 25 refused by RIGHT"), "said: $notes")
        t.submit(FlushRequest(listOf(DisplayOp.CacheWrite2(list(3000))), 0, "ATLAS"))
        waitUntil(10_000, message = { "the third write" }) { right.size >= 4 }
        assertEquals(19, right[3][0].toInt(), "mode 25 off for the session after a refusal")
        assertEquals(3L, glass.cacheGen(Arm.RIGHT), "three writes, three bumps: the count is the shell's")
        t.stop()
    }

    /** Spec §6.2: on the installed image (0x7f), and on a C1 build at stage 0, a seeded walk of the shell with cached
     *  text on (its atlas written by mode 19) puts no mode 25 or 26 on the wire, top level or inside a batch. */
    @Test
    fun noMode25Or26ReachesTheWireWithoutTheLaddersBits_6_2(): Unit = runBlocking {
        for (features in listOf<Int?>(null, 0x1ff)) {
            val tmp = Files.createTempDirectory("damage-motion-walk")
            try {
                val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = features }
                val bad = java.util.concurrent.CopyOnWriteArrayList<String>()
                val images = java.util.concurrent.atomic.AtomicInteger()
                sim.imageObserver = { _, img ->
                    images.incrementAndGet()
                    val m = img[0].toInt() and 0x7F
                    if (m == 25 || m == 26) bad += "top-level mode $m"
                    if (m == 8) { var i = 2; repeat(img[1].toInt() and 0xFF) { val n = (img[i].toInt() and 0xFF) or ((img[i + 1].toInt() and 0xFF) shl 8)
                        if ((img[i + 2].toInt() and 0x7F) == 26) bad += "mode 26 in a batch"; i += 2 + n } }
                }
                val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
                val shell = Shell(wm.damage.core.comp.CachedText(GlyphyText()), t, Persistence(tmp.resolve("s.json")), null, scope)
                shell.start()
                waitUntil(10_000, 10, { "start" }) { shell.isQuiescent() }
                shell.updateSettings { it.copy(cachedText = "on") }
                waitUntil(20_000, 10, { "the atlas uploads" }) { shell.cachedTextActive && shell.cachedFontsLive.isNotEmpty() && shell.isQuiescent() }
                val rnd = java.util.Random(681)
                val moves = intArrayOf(EvenHubMsg.EV_SCROLL_BOTTOM, EvenHubMsg.EV_SCROLL_TOP, EvenHubMsg.EV_CLICK, EvenHubMsg.EV_DOUBLE_CLICK)
                repeat(24) {
                    shell.postGesture(moves[rnd.nextInt(moves.size)])
                    waitUntil(10_000, 10, { "a step of the walk" }) { shell.isQuiescent() }
                }
                assertTrue(images.get() > 20, "the walk put images on the wire: ${images.get()}")
                assertEquals(emptyList(), bad.toList(), "features ${features?.let { "0x" + it.toString(16) } ?: "derived"}: no mode 25 or 26")
                shell.stop()
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }
    }

    // ------------------------------------------------------------------------------------- review pass 1's rulings
    private fun controlArg(p: ByteArray): Int { val b = StartRig.body(p); val i = StartRig.controlAt(b)
        return (b[i + 4].toInt() and 0xFF) or ((b[i + 5].toInt() and 0xFF) shl 8) }

    /** Build C1's ruling B-M1: the 256 KiB size is a rung of the ladder, and it records itself (the time and stage 2)
     *  before op 5 goes out. Glasses that reset on it — after op 5, before the first FLAGS_SET — are held back at the
     *  next start: stage 3 drops to stage 1, so the next start asks 160 KiB and arms ZWRITE. The mirror here is the
     *  transport's own (a tee), the glasses a separate model. */
    @Test
    fun glassesThatResetBetweenOp5AndTheFirstFlagsSetAreHeldBackAtTheNextStart_BM1(): Unit = runBlocking {
        val glass = GlassFirmwareSim().apply { damageContract = 2; damageFeatures = 0x1ff }
        var clock = 1_000_000L
        val t = StartRig(glass, scope, clock = { clock })
        t.heapReads = false
        collect(t)
        t.devProbe("c1", "3")
        val sizes = java.util.concurrent.CopyOnWriteArrayList<String>()
        val swallow = java.util.concurrent.atomic.AtomicBoolean(true)
        t.onWrite = { a, p ->
            if (StartRig.damageOp(p) != DamageMsg.OP_CACHE_SIZE) p
            else { val kib = controlArg(p); sizes += "${a.name.first()}:$kib"; if (swallow.get() && kib == 256) null else p }
        }
        val first = scope.async { runCatching { t.start(full) } }
        waitUntil(10_000, message = { "op 5 for 256 KiB written to both arms: $sizes" }) { "L:256" in sizes && "R:256" in sizes }
        // the glasses reset on it: both lenses lose their RAM, RIGHT's uptime starts again, the link ends
        clock += 10_000L
        for (arm in Arm.entries) glass.rebootForTest(arm, clock)
        t.endLink("test: the glasses reset on op 5")
        first.await()
        clock += 200_000L
        swallow.set(false)
        val before = sizes.size
        t.start(full)
        fun said() = notes.filter { it.first == "keeper" }.joinToString(" | ") { it.second }
        assertTrue(has("keeper", "armed stage 2 — stage 1 from here"), "the size rung is blamed and dropped: ${said()}")
        assertEquals(listOf("L:160", "R:160"), sizes.drop(before).distinct().sorted(), "the next start asks 160 KiB")
        assertEquals(0x24, t.state.value.flagsInForce, "stage 1: DRAW2 and ZWRITE, no ENGINE: ${said()}")
        assertEquals(160 * 1024, t.state.value.cacheSize)
        t.stop()
    }

    /** Build C1's ruling B-N4 (with B-M1): what an arming records is what this build arms. A build with the riders and
     *  no engine (0x17f) at stage 3 arms ZWRITE and the 256 KiB size — stage 2's changes — so a reset drops it to
     *  stage 1 at once (the size goes). A build with the engine and no riders (0x0ff, without bit 8) at stage 3 arms
     *  ENGINE alone, records stage 3, and a reset drops ENGINE. */
    @Test
    fun aHoldBackDropsFromWhatTheBuildArmed_BN4(): Unit = runBlocking {
        data class Case(val features: Int, val flags3: Int, val kib3: Int, val note: String, val flagsAfter: Int, val kibAfter: Int)
        for (c in listOf(Case(0x17f, 0x24, 256, "armed stage 2 — stage 1 from here", 0x24, 160),
                         Case(0x0ff, 0xc, 160, "armed stage 3 — stage 2 from here", 0x4, 160))) {
            notes.clear()
            val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = c.features }
            var clock = 1_000_000L
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true), clock = { clock })
            collect(t)
            t.devProbe("c1", "3")
            t.start(full)
            val what = "features 0x${c.features.toString(16)}"
            fun said() = notes.filter { it.first == "keeper" }.joinToString(" | ") { it.second }
            assertEquals(c.flags3, t.state.value.flagsInForce, "$what at stage 3: ${said()}")
            assertEquals(c.kib3 * 1024, t.state.value.cacheSize, what)
            clock += 10_000L; sim.uptimeOffsetMs = clock; clock += 200_000L
            assertTrue(t.restartSession("test: after a reset"))
            t.start(full)
            waitUntil(10_000, message = { "$what: the hold-back: ${said()}" }) { has("keeper", c.note) }
            assertEquals(c.flagsAfter, t.state.value.flagsInForce, "$what after the hold-back: ${said()}")
            assertEquals(c.kibAfter * 1024, t.state.value.cacheSize, "$what after the hold-back")
            t.stop()
        }
    }

    /** Build C1's ruling B-M1, its third site: a ladder bit armed by hand (a `flags=` probe, within the stage) is a
     *  rung's byte too and records its stage with its time. A session that started at stage 0, then `c1=3` and
     *  `flags=0x2c` by hand, then a reset: the next start blames the ladder (stage 3 → 2) and keeps DRAW2, instead of
     *  today's hold-back of the whole set. */
    @Test
    fun aLadderBitArmedByHandRecordsItsStage_BM1(): Unit = runBlocking {
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x1ff }
        var clock = 1_000_000L
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true), clock = { clock })
        collect(t)
        t.start(full)
        assertEquals(0x4, t.state.value.flagsInForce, "stage 0: DRAW2 only")
        t.devProbe("c1", "3")
        t.devProbe("flags", "0x2c")
        waitUntil(10_000, message = { "the hand-set flags reached both lenses" }) { Arm.entries.all { sim.damageFlags(it) == 0x2c } }
        clock += 10_000L; sim.uptimeOffsetMs = clock; clock += 200_000L
        assertTrue(t.restartSession("test: after a reset"))
        t.start(full)
        fun said() = notes.filter { it.first == "keeper" }.joinToString(" | ") { it.second }
        waitUntil(10_000, message = { "the ladder's hold-back: ${said()}" }) { has("keeper", "armed stage 3 — stage 2 from here") }
        assertEquals(0x24, t.state.value.flagsInForce, "stage 2: ENGINE gone, DRAW2 and ZWRITE kept: ${said()}")
        assertEquals(256 * 1024, t.state.value.cacheSize)
        t.stop()
    }

    /** Build C1's ruling B-S1, in the phone's real arrangement — the mirror a tee of this transport's bytes, the
     *  glasses a separate model: the lens runs a program to its end on its own clock and RIGHT's notify comes, but the
     *  mirror, given no wake, still holds step 1 there, so the area stays unknown; the next present into the mirror at
     *  or past the end draws step T, and only then is the area known. */
    @Test
    fun anAreaStaysUnknownWhileTheTeedMirrorStillDrawsIt_BS1(): Unit = runBlocking {
        val glass = GlassFirmwareSim().apply { damageContract = 2; damageFeatures = 0x1ff }
        var clock = 1_000_000L
        val t = StartRig(glass, scope, clock = { clock })
        collect(t)
        t.devProbe("c1", "3")
        t.start(full)
        assertEquals(0x2c, t.state.value.flagsInForce)
        val area = Rect(100, 100, 40, 20)
        assertTrue(t.sendMotion(messages(program(area, 5), slot = 0, period = 34, tag = 0x81)))
        waitUntil(10_000, message = { "the PLAY landed on the glasses and in the mirror" }) {
            glass.motionWindows(Arm.RIGHT).any { it.slot == 0 } && t.mirror.unknownAreas(Arm.RIGHT) == listOf(area) }
        clock += 1_000L                                                  // well past the nominal end (4 × 34 ticks)
        for (arm in Arm.entries) glass.conformanceWake(arm, clock)       // the lens draws step T and RIGHT notifies
        waitUntil(10_000, message = { "RIGHT's notify: $notes" }) { has("motion", "tag 0x0081 drew its last step") }
        for (arm in Arm.entries) assertEquals(listOf(area), t.mirror.unknownAreas(arm),
            "$arm: notified and past the nominal end, but the mirror still draws it (its panel holds step 1): unknown")
        t.submit(FlushRequest(listOf(DisplayOp.Fill(Rect(400, 300, 8, 4), Rect(400, 300, 8, 4), 7)), 0, "elsewhere"))
        waitUntil(10_000, message = { "the mirror's present drew step T: ${t.mirror.unknownAreas(Arm.RIGHT)}" }) {
            Arm.entries.all { t.mirror.unknownAreas(it).isEmpty() } }
        t.stop()
    }

    /** Build C1's ruling B-S2: RIGHT refuses a mode-26 message for a slot the mirror holds open (here RIGHT's ENGINE is
     *  off behind the phone's back, so it refuses 15 while LEFT and the mirror take everything): a divergence, which the
     *  shell keyframes on — under the cap of a CRC mismatch, two per instance: the PLAY's refusal and a first
     *  RETARGET's are divergences, a second RETARGET's is said and asks for no third keyframe. A refused PLAY whose tag
     *  the mirror's slot does not hold (the mirror refused it as well) is none. */
    @Test
    fun aRefusalForASlotTheMirrorHoldsOpenIsADivergenceTwicePerInstance_BS2(): Unit = runBlocking {
        val glass = GlassFirmwareSim().apply { damageContract = 2; damageFeatures = 0x1ff }
        val t = StartRig(glass, scope)
        collect(t)
        t.devProbe("c1", "3")
        t.start(full)
        assertEquals(0x2c, t.state.value.flagsInForce)
        // the rig's model answers inside the write, before the transport's own mirror has taken the packet; a motion
        // notify is handed back a little later, as the radio's round trip does
        t.onNotify = { arm, p ->
            if (StartRig.sid(p) == SettingsMsg.SID && DamageMsg.parseMotion(StartRig.body(p)) != null) { scope.launch { delay(20); t.deliver(arm, p) }; null } else p
        }
        fun divergences() = notes.filter { it.first == "divergence" && it.second.startsWith("motion: slot 0 refused by RIGHT") }
        val p = program(Rect(200, 200, 40, 20), 200)
        // first a PLAY both sides take (slot 0 open with tag 0x90), then one both refuse (period 0: 12): RIGHT's body
        // names tag 0x91, which the mirror's slot does not hold — the mirror refused it too, so it is no divergence
        assertTrue(t.sendMotion(messages(p, 0, 128, 0x90)))
        assertTrue(t.sendMotion(listOf(Motion.play(p, 0, 128, 0x91, 0, IntArray(4)).also { it[3] = 0 })))
        waitUntil(10_000, message = { "RIGHT's refusal of tag 0x91: $notes" }) { notes.any { it.first == "motion" && "slot 0 tag 0x0091 refused" in it.second } }
        // now RIGHT's ENGINE goes off behind the phone's back: RIGHT refuses every mode 26 (15), LEFT and the mirror do not
        glass.conformanceControl(Arm.RIGHT, DamageMsg.OP_FLAGS_SET, DamageMsg.FLAG_DRAW2 or DamageMsg.FLAG_ZWRITE, System.currentTimeMillis())
        assertTrue(t.sendMotion(listOf(Motion.play(p, 0, 128, 0x92, 0, IntArray(4)))))
        waitUntil(10_000, message = { "the PLAY's refusal: $notes" }) { divergences().size >= 1 }
        // ruling B-N2 (`C1-R5-decisions.md`): a PLAY's refusal is matched by the mirror's record of that play
        assertTrue("the mirror took that PLAY (tag 0x0092 ordinal 0)" in divergences()[0].second, divergences()[0].second)
        assertTrue(t.sendMotion(listOf(Motion.retarget(p, 0, IntArray(4)))))
        waitUntil(10_000, message = { "the first RETARGET's refusal: $notes" }) { divergences().size >= 2 }
        assertTrue(t.sendMotion(listOf(Motion.retarget(p, 0, IntArray(4)))))
        waitUntil(10_000, message = { "the second RETARGET's refusal, capped: $notes" }) {
            divergences().size > 2 || has("motion", "not keyframed: this instance has had 2 keyframes (the cap)") }
        assertEquals(2, divergences().size, "two keyframes asked for one instance, never a third: ${divergences()}")
        t.stop()
    }

    /** Build C1's ruling B-N5, at the batch's limit: with ENGINE not in force (RIGHT's flags; here the phone's stage 1),
     *  a flush of the batch's whole count (255 sub-messages) that meets an instance open on the glasses goes as the
     *  encoder made it — no CLOSE (it would be refused 15 and end the batch, and it would not fit). */
    @Test
    fun aCloseRidesOnlyWhileEngineIsInForce_BN5(): Unit = runBlocking {
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x1ff }
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        collect(t)
        val done = java.util.concurrent.CopyOnWriteArrayList<TransportEvent.FlushDone>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { t.events.collect { if (it is TransportEvent.FlushDone) done.add(it) } }
        t.devProbe("c1", "1")
        t.start(full)
        assertEquals(0x24, t.state.value.flagsInForce, "stage 1: ENGINE not in force")
        val area = Rect(100, 100, 40, 20)
        val now = System.currentTimeMillis()
        for (arm in Arm.entries) {                                       // an instance open on the glasses all the same
            sim.conformanceControl(arm, DamageMsg.OP_FLAGS_SET, 0x2c, now)
            for (m in messages(program(area, 200), 1, 128, 0xA1)) assertTrue(sim.conformanceMessage(arm, m, now))
        }
        assertTrue(sim.motionWindows(Arm.RIGHT).any { it.slot == 1 })
        val batches = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        sim.imageObserver = { arm, img -> if (arm == Arm.RIGHT && img[0].toInt() == 8) batches += img }
        val ops = (0 until 254).map { i -> Rect(200 + (i % 40) * 10, 300 + (i / 40) * 10, 4, 4).let { DisplayOp.Fill(it, it, 7) } } +
            DisplayOp.Fill(Rect(110, 104, 8, 4), Rect(110, 104, 8, 4), 7)
        t.submit(FlushRequest(ops, 0, "the batch's whole count, one sub over the area"))
        waitUntil(10_000, message = { "the flush ended: $done" }) { done.isNotEmpty() }
        assertTrue(done.single().ok, "the flush landed: ${done.single()}")
        assertEquals(255, batches.single()[1].toInt() and 0xFF, "the encoder's batch, its whole count")
        assertEquals(21, batches.single()[4].toInt() and 0x7F, "no CLOSE at its head")
        assertTrue(sim.motionWindows(Arm.RIGHT).any { it.slot == 1 }, "the phone closed nothing")
        t.stop()
    }

    /** Build C1's ruling B-N5, the shell's half: before each assemble the shell tells the compositor whether the mirror
     *  holds an instance open, so one sub-message of the batch's count is kept for the transport's CLOSE (the count
     *  itself is `BaseMaskTest`'s pin). */
    @Test
    fun theShellTellsTheCompositorWhileAnInstanceIsOpen_BN5(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-motion-open")
        try {
            val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x1ff }
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            t.devProbe("c1", "3")
            var clock = Shell.LocalClock(12, 0, "12:00", "PM")
            val shell = Shell(FakeText(), t, Persistence(tmp.resolve("s.json")), null, scope) { clock }
            shell.start()
            waitUntil(10_000, 10, { "shell did not settle" }) { shell.isQuiescent() }
            assertEquals(false, shell.comp.motionOpen, "nothing open at the start")
            assertTrue(t.sendMotion(messages(program(Rect(560, 440, 60, 30), 200), slot = 2, period = 128, tag = 0x72)))
            waitUntil(10_000, message = { "the PLAY landed" }) { sim.motionWindows(Arm.LEFT).any { it.slot == 2 } }
            val images = java.util.concurrent.atomic.AtomicInteger()
            sim.imageObserver = { arm, _ -> if (arm == Arm.RIGHT) images.incrementAndGet() }
            clock = Shell.LocalClock(12, 1, "12:01", "PM")
            shell.postMinuteTick()
            waitUntil(10_000, 10, { "the minute's flush" }) { images.get() > 0 && shell.isQuiescent() }
            assertEquals(true, shell.comp.motionOpen, "the flush was assembled with the open instance known")
            shell.stop()
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /** Spec §6.1's stage 2: a 256 KiB session on a build with the riders lays its atlas over 192 KiB (the cache less
     *  the strips' reserve) and cached text goes live — the atlas's bound is the build's, not §4's 160. */
    @Test
    fun aStage2SessionsAtlasLaysOutOverTheWiderCache_6_1(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-motion-256")
        try {
            val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x1ff }
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            t.devProbe("c1", "2")
            val shell = Shell(wm.damage.core.comp.CachedText(GlyphyText()), t, Persistence(tmp.resolve("s.json")), null, scope)
            shell.start()
            waitUntil(10_000, 10, { "start" }) { shell.isQuiescent() }
            assertEquals(256 * 1024, t.state.value.cacheSize, "stage 2: the 256 KiB cache")
            shell.updateSettings { it.copy(cachedText = "on") }
            waitUntil(20_000, 10, { "the atlas over 192 KiB goes live" }) { shell.cachedTextActive && shell.cachedFontsLive.isNotEmpty() && shell.isQuiescent() }
            assertEquals(256 * 1024, sim.cacheSize(Arm.RIGHT), "the glasses' cache is the session's")
            shell.stop()
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // ------------------------------------------------------------------------------------- review pass 2's rulings
    private fun holdBacks() = notes.filter { it.first == "keeper" && it.second.startsWith("hold-back:") }.map { it.second }

    /** Build C1's ruling B-S1 (`C1-R5-decisions.md`): LEFT cannot report its uptime, so a rung that restarts LEFT alone
     *  shows only as LEFT's link ending the way a restart looks (a supervision timeout) within the hold-back after the
     *  arming; RIGHT's uptime runs on. After each arming LEFT alone restarts: the ladder steps 3 → 2 → 1 → 0, each drop
     *  said with what was read, and at 0 it stays — DRAW2 armed, no hold-back of today's kind (the rule is the
     *  ladder's). Each start comes 200 s after the end: the end's own time is what the window measures. */
    @Test
    fun aRestartOfLeftAloneAfterEachArmingStepsTheLadderDownToZero_BS1(): Unit = runBlocking {
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x1ff }
        var clock = 1_000_000L
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true), clock = { clock })
        collect(t)
        t.devProbe("c1", "3")
        t.start(full)
        fun said() = notes.filter { it.first == "keeper" }.joinToString(" | ") { it.second }
        assertEquals(0x2c, t.state.value.flagsInForce, "stage 3 armed: ${said()}")
        suspend fun leftRestartsThenStart() {
            clock += 10_000L
            sim.rebootForTest(Arm.LEFT, clock)
            t.endLink("LEFT disconnected: supervision timeout")
            clock += 200_000L
            t.start(full)
        }
        for ((stage, flags, kib) in listOf(Triple(2, 0x24, 256), Triple(1, 0x24, 160), Triple(0, 0x4, 160))) {
            leftRestartsThenStart()
            waitUntil(10_000, message = { "the hold-back to stage $stage: ${said()}" }) { holdBacks().size == 3 - stage }
            val text = holdBacks().last()
            assertTrue("LEFT's link ended as a restart of that lens would (LEFT disconnected: supervision timeout) 10 s after the " +
                "C1 ladder armed stage ${stage + 1} — LEFT cannot report its uptime" in text && "stage $stage from here" in text,
                "the journal says why the stage was dropped: $text")
            assertEquals(flags, t.state.value.flagsInForce, "stage $stage: the flags in force, DRAW2 kept: ${said()}")
            assertEquals(kib * 1024, t.state.value.cacheSize, "stage $stage: the cache size")
        }
        leftRestartsThenStart()
        assertEquals(0x4, t.state.value.flagsInForce, "stage 0 stays: DRAW2 armed: ${said()}")
        assertEquals(3, holdBacks().size, "three drops and no fourth (nothing of the ladder was armed): ${holdBacks()}")
        t.stop()
    }

    /** B-S1's bounds: a LEFT link end after the hold-back's window, one that is not restart-like (the phone ended it),
     *  and one after an arming that carried none of the ladder (a stage-0 session, then `c1=3` by hand) change nothing:
     *  the next start arms what the stage says (stage 3: ENGINE and the 256 KiB cache). */
    @Test
    fun aLeftLinkEndOutsideTheWindowOrWithNoRungArmedChangesNothing_BS1(): Unit = runBlocking {
        data class Case(val label: String, val c1First: Int, val c1Then: Int?, val endAfterMs: Long, val reason: String, val restart: Boolean)
        for (c in listOf(
            Case("after the window", 3, null, 130_000L, "LEFT disconnected: supervision timeout", true),
            Case("not restart-like", 3, null, 10_000L, "LEFT disconnected: ended by phone", false),
            Case("no rung armed", 0, 3, 10_000L, "LEFT disconnected: supervision timeout", true))) {
            notes.clear()
            val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x1ff }
            var clock = 1_000_000L
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true), clock = { clock })
            collect(t)
            if (c.c1First != 0) t.devProbe("c1", "${c.c1First}")
            t.start(full)
            c.c1Then?.let { t.devProbe("c1", "$it") }
            clock += c.endAfterMs
            if (c.restart) sim.rebootForTest(Arm.LEFT, clock)
            t.endLink(c.reason)
            clock += 200_000L
            t.start(full)
            fun said() = notes.filter { it.first == "keeper" }.joinToString(" | ") { it.second }
            assertEquals(0x2c, t.state.value.flagsInForce, "${c.label}: stage 3 armed: ${said()}")
            assertEquals(256 * 1024, t.state.value.cacheSize, "${c.label}: the 256 KiB cache")
            assertEquals(emptyList(), holdBacks(), "${c.label}: no hold-back")
            t.stop()
        }
    }

    /** Build C1's ruling B-N2: a refusal body that carries a tag (a PLAY's) is matched by the mirror's record of that
     *  play, not by the slot being open when the notify arrives. RIGHT refuses a PLAY that LEFT and the mirror took
     *  (its ENGINE is off behind the phone's back); before the refusal arrives a flush meets the area, so its CLOSE
     *  empties the mirror's slot; the refusal is still a divergence. Every motion notify is held and only that one is
     *  handed back. */
    @Test
    fun aRefusedPlayIsMatchedByItsPlaysRecordNotBySlotAtTheNotify_BN2(): Unit = runBlocking {
        val glass = GlassFirmwareSim().apply { damageContract = 2; damageFeatures = 0x1ff }
        val t = StartRig(glass, scope)
        collect(t)
        t.devProbe("c1", "3")
        t.start(full)
        assertEquals(0x2c, t.state.value.flagsInForce)
        val held = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        t.onNotify = { _, p -> if (StartRig.sid(p) == SettingsMsg.SID && DamageMsg.parseMotion(StartRig.body(p)) != null) { held += p; null } else p }
        glass.conformanceControl(Arm.RIGHT, DamageMsg.OP_FLAGS_SET, DamageMsg.FLAG_DRAW2 or DamageMsg.FLAG_ZWRITE, System.currentTimeMillis())
        val area = Rect(200, 200, 40, 20)
        assertTrue(t.sendMotion(messages(program(area, 200), 0, 128, 0x93)))
        fun refusal() = held.firstOrNull { DamageMsg.parseMotion(StartRig.body(it))?.let { m -> m.outcome == DamageMsg.OUTCOME_REFUSED && m.tag == 0x93 } == true }
        waitUntil(10_000, message = { "RIGHT refused the PLAY and the mirror took it" }) { refusal() != null && t.mirror.unknownAreas(Arm.LEFT) == listOf(area) }
        t.submit(FlushRequest(listOf(DisplayOp.Fill(Rect(210, 205, 8, 4), Rect(210, 205, 8, 4), 7)), 0, "over the area"))
        waitUntil(10_000, message = { "the flush's CLOSE emptied the mirror's slot" }) { t.mirror.unknownAreas(Arm.LEFT).isEmpty() }
        t.deliver(Arm.RIGHT, refusal()!!)
        waitUntil(10_000, message = { "the refusal is a divergence: $notes" }) {
            notes.any { it.first == "divergence" && it.second.startsWith("motion: slot 0 refused by RIGHT") && "tag 0x0093" in it.second } }
        t.stop()
    }

    /** Build C1's ruling B-N3: the mode-19 copy of a refused mode 25 is not sent while the glasses are in Silent Mode,
     *  nor while no lease is held — said, and the wire (each image message's first packet) carries nothing more. */
    @Test
    fun aRefusedMode25IsNotSentAgainInSilentModeOrWithNoLease_BN3(): Unit = runBlocking {
        for (case in listOf("silent", "no lease")) {
            notes.clear()
            val glass = GlassFirmwareSim().apply { damageContract = 2; damageFeatures = 0x1ff }
            val t = StartRig(glass, scope)
            collect(t)
            t.devProbe("c1", "1")
            t.start(full)
            assertEquals(0x24, t.state.value.flagsInForce, "$case: stage 1, ZWRITE in force")
            val images = java.util.concurrent.atomic.AtomicInteger()
            // every image message goes out on LEFT (the bulk arm); its first packet is counted
            t.onWrite = { a, p -> if (a == Arm.LEFT && StartRig.firstOfImage(p)) images.incrementAndGet(); p }
            // RIGHT's ZWRITE goes off behind the phone's back: the mode 25 is refused there (15)
            glass.conformanceControl(Arm.RIGHT, DamageMsg.OP_FLAGS_SET, DamageMsg.FLAG_DRAW2, System.currentTimeMillis())
            val list = CfwModes.cacheUpdate2(listOf(CfwModes.CacheWrite2(1000, ByteArray(2000) { (it / 100).toByte() })), 160 * 1024)
            t.submit(FlushRequest(listOf(DisplayOp.CacheWrite2(list)), 0, "ATLAS"))
            waitUntil(10_000, message = { "$case: the mode 25 went out" }) { images.get() == 1 }
            if (case == "silent") {
                glass.setSilent(true)
                waitUntil(10_000, message = { "$case: the push" }) { t.state.value.glassesSilent }
            } else {
                t.setLeaseWanted(false)
                assertEquals(false, t.state.value.leaseHeld, case)
            }
            t.devProbe("telemetry", "read")
            waitUntil(10_000, message = { "$case: the refusal judged: $notes" }) { has("glass", "mode 25 refused by RIGHT") }
            delay(300)
            val why = if (case == "silent") "the glasses are in Silent Mode" else "no lease is held"
            assertEquals(1, images.get(), "$case: no mode-19 copy on the wire")
            assertTrue(has("glass", "list is not sent again: $why"), "$case: said: ${notes.filter { it.first == "glass" }}")
            t.stop()
        }
    }

    /** A mirror that counts the shell's questions to it (build C1's ruling B-N4). */
    private class CountingMirror(val inner: wm.damage.core.transport.LensPanels) : wm.damage.core.transport.LensPanels by inner {
        val asked = java.util.concurrent.atomic.AtomicInteger()
        override fun motionOpen(): Boolean { asked.incrementAndGet(); return inner.motionOpen() }
    }
    private class CountedRig(glass: GlassFirmwareSim, scope: CoroutineScope) : StartRig(glass, scope) {
        @Volatile var asking: CountingMirror? = null
        override val mirror: wm.damage.core.transport.LensPanels get() = asking ?: super.mirror
        init { asking = CountingMirror(super.mirror) }
    }

    /** Build C1's ruling B-N4: the shell asks the mirror whether an instance is open (its lock) only while ENGINE is in
     *  force. With today's firmware (0x7f) the assemble path never asks, over a start and a seeded walk; at stage 3 on a
     *  C1 build it asks on the assembles (the shape guard). */
    @Test
    fun withTodaysFirmwareTheAssemblePathNeverAsksTheMirror_BN4(): Unit = runBlocking {
        for ((features, stage) in listOf(0x7f to 0, 0x1ff to 3)) {
            val tmp = Files.createTempDirectory("damage-motion-ask")
            try {
                val glass = GlassFirmwareSim().apply { damageContract = 2; damageFeatures = features }
                val t = CountedRig(glass, scope)
                if (stage != 0) t.devProbe("c1", "$stage")
                val shell = Shell(FakeText(), t, Persistence(tmp.resolve("s.json")), null, scope)
                shell.start()
                waitUntil(10_000, 10, { "shell did not settle" }) { shell.isQuiescent() }
                val images = java.util.concurrent.atomic.AtomicInteger()
                glass.imageObserver = { arm, _ -> if (arm == Arm.RIGHT) images.incrementAndGet() }
                // a seeded walk, one gesture a step: each moves the list and is flushed
                val rnd = java.util.Random(684)
                val moves = intArrayOf(EvenHubMsg.EV_SCROLL_BOTTOM, EvenHubMsg.EV_SCROLL_TOP, EvenHubMsg.EV_CLICK, EvenHubMsg.EV_DOUBLE_CLICK)
                repeat(6) {
                    shell.postGesture(moves[rnd.nextInt(moves.size)])
                    waitUntil(10_000, 10, { "a step of the walk" }) { shell.isQuiescent() }
                }
                assertTrue(images.get() >= 3, "the walk put images on the wire: ${images.get()}")
                val what = "features 0x${features.toString(16)} stage $stage"
                if (features == 0x7f) assertEquals(0, t.asking!!.asked.get(), "$what: the mirror's lock is never taken on the assemble path")
                else assertTrue(t.asking!!.asked.get() >= 3, "$what: ENGINE in force — asked on each assemble: ${t.asking!!.asked.get()}")
                shell.stop()
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }
    }

    /** Build C1's ruling B-N6: `sendMotion` with nothing to send fails at once — false, its deferred failed, a note —
     *  so nothing waits on it (the deferred rides the last message, and there is none). */
    @Test
    fun sendMotionWithNothingToSendFailsAtOnce_BN6(): Unit = runBlocking {
        val (_, t) = engineSession()
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        assertEquals(false, t.sendMotion(emptyList(), done), "nothing to send: not sent")
        assertTrue(done.isCompleted, "the deferred is failed at once, nothing waits on it")
        assertEquals("not sent: the program has no message", runCatching { done.await() }.exceptionOrNull()?.message)
        assertTrue(has("motion", "not sent: the program has no message"), "said: $notes")
        t.stop()
    }

    /** Build C1's A-S1, the phone's half (`C1-R5-decisions.md`), against the simulator: the engine's busy word held on
     *  both lenses (planted, as a restart that RAM survives leaves it) refuses every cache write 14 (R1.15). On the
     *  third in a row — each its own refusal record (a present between, so the sequence moves) — the phone says it
     *  once, a fault in plain words, and sends nothing because of it; a fourth says nothing more. In a new session it is
     *  said again, and a write taken (field 17 moving) ends the run: two refusals, a write taken, then two more say
     *  nothing, and the third of the new run is said. A record read twice counts once, and a new process takes the
     *  record the glasses still hold as its baseline. At stage 0: every cache write takes the word. */
    @Test
    fun threeCacheWritesRefused14InARowAreSaidOncePerSession_AS1(): Unit = runBlocking {
        val glass = GlassFirmwareSim().apply { damageContract = 2; damageFeatures = 0x1ff }
        val t = SimTransport(glass, scope, SimTransport.Timing(instant = true))
        collect(t)
        t.start(full)
        assertEquals(0x4, t.state.value.flagsInForce, "stage 0: DRAW2 only")
        val writes = java.util.concurrent.atomic.AtomicInteger()
        val images = java.util.concurrent.atomic.AtomicInteger()
        glass.imageObserver = { arm, img -> if (arm == Arm.RIGHT) { images.incrementAndGet(); if (img[0].toInt() == 19) writes.incrementAndGet() } }
        fun hold(on: Boolean) = synchronized(glass) { for (arm in Arm.entries) glass.motionForTest(arm).holdWordForTest(on) }
        fun said() = notes.filter { it.first == "fault" && it.second.startsWith("uploads:") }
        var n = 0
        suspend fun writeThenRead(): DamageMsg.Telemetry? {
            n++
            val before = images.get()
            val list = CfwModes.cacheUpdate2(listOf(CfwModes.CacheWrite2(1000 + 100 * n, ByteArray(64) { (it + n).toByte() })), 160 * 1024)
            t.submit(FlushRequest(listOf(DisplayOp.CacheWrite2(list)), 0, "ATLAS"))
            // a present between: the next refusal is its own record
            t.submit(FlushRequest(listOf(DisplayOp.Fill(Rect(400, 300, 8, 4), Rect(400, 300, 8, 4), n % 15)), 0, "elsewhere"))
            waitUntil(10_000, message = { "write $n and its present reached RIGHT" }) { images.get() >= before + 2 }
            return t.telemetry("test read")
        }
        hold(true)
        for (i in 1..2) {
            val r = writeThenRead()
            assertEquals(14, r?.refusal?.reason, "write $n refused 14: ${r?.describe()}")
            t.telemetry("test read again")                  // the same record again: one refusal, counted once
            assertEquals(emptyList(), said(), "after $i refusal(s): nothing said")
        }
        writeThenRead()
        waitUntil(10_000, message = { "the third refusal in a row is said: $notes" }) { said().size == 1 }
        val text = said().single().second
        assertTrue("the glasses are refusing uploads and need a restart" in text && "nothing is sent again" in text, text)
        writeThenRead()
        delay(200)
        assertEquals(1, said().size, "said once per session: $notes")
        assertEquals(4, writes.get(), "four cache writes on the wire, none sent again")

        assertTrue(t.restartSession("test: a new session"))
        t.start(full)
        writeThenRead()
        val g0 = writeThenRead()?.cacheGen
        hold(false)
        val g1 = writeThenRead()?.cacheGen
        assertEquals(g0?.plus(1), g1, "the write is taken: field 17 moves by one")
        hold(true)
        for (i in 1..2) {
            writeThenRead()
            delay(100)
            assertEquals(1, said().size, "a write taken ended the run: $i refusal(s) since, nothing said in this session yet")
        }
        writeThenRead()
        waitUntil(10_000, message = { "the new session says it once: $notes" }) { said().size == 2 }
        hold(false)                                         // the model has one thread: no release point with the word held
        t.stop()

        // a new transport (the phone's process restarted) over the same glasses: the refusal record they still hold is
        // the session's baseline, not one of its refusals — the third refusal of this session is said, not the second
        val t2 = SimTransport(glass, scope, SimTransport.Timing(instant = true))
        val before = said().size
        collect(t2)
        t2.start(full)
        hold(true)
        for (i in 1..2) {
            n++
            val at = images.get()
            t2.submit(FlushRequest(listOf(DisplayOp.CacheWrite2(CfwModes.cacheUpdate2(listOf(CfwModes.CacheWrite2(3000 + i, ByteArray(64))), 160 * 1024))), 0, "ATLAS"))
            t2.submit(FlushRequest(listOf(DisplayOp.Fill(Rect(400, 300, 8, 4), Rect(400, 300, 8, 4), i)), 0, "elsewhere"))
            waitUntil(10_000, message = { "the new process's write $i" }) { images.get() >= at + 2 }
            t2.telemetry("test read")
            delay(100)
            assertEquals(before, said().size, "the new process: $i refusal(s) of its own, nothing said")
        }
        t2.stop()
    }
}
