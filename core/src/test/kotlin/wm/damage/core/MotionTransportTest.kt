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
import wm.damage.core.util.UncaughtRecord
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
    private companion object {
        /** Build C1-Q: past the lease's 87.9 s and the 15 s margin (`CfwTransportBase.LEASE_FRESH_AFTER_MS`, 102.9 s),
         *  written out so that a change of the constant is seen by the pins that read the margin. */
        const val PAST_THE_LAPSE = 110_000L

        /** The lease op a sid-0x09 packet carries (`SettingsMsg.control`: 'F' 'C' 1 op …, field 101), or -1. */
        fun leaseOp(p: ByteArray): Int {
            if (p.size <= 12 || StartRig.sid(p) != SettingsMsg.SID) return -1
            for (i in 0 until p.size - 3) if (p[i] == 'F'.code.toByte() && p[i + 1] == 'C'.code.toByte() && p[i + 2].toInt() == 1) return p[i + 3].toInt()
            return -1
        }
    }

    /** Build C1-Q (the focused read's F2): a rig whose ACQUIRE to LEFT, while [failLeftAcquire] is set, reaches the lens
     *  and is then reported failed by the stack — the lens applied it, the transport's write threw. */
    private class LeftAcquireFailsAfterDelivery(glass: GlassFirmwareSim, scope: CoroutineScope, clock: () -> Long) :
        StartRig(glass, scope, clock) {
        @Volatile var failLeftAcquire = false
        override suspend fun writeArm(arm: Arm, packet: ByteArray) {
            if (failLeftAcquire && arm == Arm.LEFT && leaseOp(packet) == SettingsMsg.OP_FB_ACQUIRE) {
                glass.write(arm, packet, nowMs())
                throw IllegalStateException("test: the stack reported LEFT's acquire as failed after it went out")
            }
            super.writeArm(arm, packet)
        }
    }
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
    private suspend fun startWire(features: Int?, stage: Int, store: java.nio.file.Path? = null, stepDown: Boolean = false,
                                  fresh: Boolean = false): List<String> {
        val glass = GlassFirmwareSim().apply { damageContract = 2; damageFeatures = features }
        var now = 1_763_000_000_000L
        val t = StartRig(glass, scope, clock = { now }, instantPacing = false, c1StageFile = store, c1StageStepDown = stepDown)
        if (store != null) collect(t)
        t.heapReads = false
        if (stage != 0) t.devProbe("c1", "$stage")
        // build C1-Q: [fresh] takes the start after a first start and a lapse — the session ends and the clock moves past
        // the lease's expiry and its margin, so both lenses are known to hold no cache and the start asks the stage's size
        // (a transport's first start asks at most 160 KiB); the first start's own writes settle before its end
        if (fresh) { t.start(full); delay(300); assertTrue(t.restartSession("test: the session ends")); now += PAST_THE_LAPSE }
        val sent = java.util.Collections.synchronizedList(ArrayList<String>())
        t.onWrite = { a, p -> sent.add("${a.name.first()} " + p.joinToString("") { "%02x".format(it) }); p }
        t.start(full)
        delay(300)
        val got = synchronized(sent) { sent.toList() }
        t.stop()
        return got
    }

    /** Build C1-Q: a start where both lenses are known to hold no cache — a first start, its session's end, and [pastTheLapse]
     *  moving the transport's clock by at least [PAST_THE_LAPSE] — so the stage's 256 KiB size goes out (a transport's
     *  first start asks at most 160 KiB). Known fresh comes only from the lapse (`C1-Q-ruling.md` §7). */
    private suspend fun startKnownFresh(t: CfwTransportBase, pastTheLapse: () -> Unit) {
        t.start(full)
        assertTrue(t.restartSession("test: the session ends"))
        pastTheLapse()
        t.start(full)
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

    /** Every packet that is not a FLAGS_SET or a CACHE_SIZE, with the AA sequence byte, the CRC and the message id taken
     *  off (one more packet moves every later sequence number). */
    private fun rest(wire: List<String>): List<String> = wire.filter { line ->
        val op = StartRig.damageOp(unhex(line.substring(2))); op != DamageMsg.OP_FLAGS_SET && op != DamageMsg.OP_CACHE_SIZE
    }.map { line ->
        val h = line.substring(2)
        // an EvenHub message's msgId (`08 <cmd> 10 <msgId>` after the 8-byte header) moves by one per extra control write;
        // C1-Q: and a settings request's own (`08 <cmd> 10 <msgId>` as well) after the start and stop that come before a
        // known-fresh start (`startWire`'s [fresh]) was seen one apart between two such runs (0x0a against 0x0b; the
        // request that made the difference was not traced)
        val raw = h.substring(16, h.length - 4)
        val body = if (raw.length >= 8 && raw.startsWith("08") && raw.substring(4, 6) == "10") raw.substring(0, 6) + "··" + raw.substring(8) else raw
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
     *  2, ENGINE at 3 — and only on a build with the bit it needs; every other packet is stage 0's. Each start is one
     *  after a release (C1-Q: known fresh); a first start's size is `aStage0StartSendsTodaysBytesAtEveryKindOfStart_C1Q`'s. */
    @Test
    fun eachLadderStageAddsOnlyItsOwnChange_6_2(): Unit = runBlocking {
        val base = startWire(0x1ff, 0, fresh = true)
        val cases = listOf(
            Triple(0x1ff, 0, listOf("5:a0", "2:4")),
            Triple(0x1ff, 1, listOf("5:a0", "2:4", "2:24")),
            Triple(0x1ff, 2, listOf("5:100", "2:4", "2:24")),
            Triple(0x1ff, 3, listOf("5:100", "2:4", "2:c", "2:2c")),
            Triple(0x17f, 3, listOf("5:100", "2:4", "2:24")),             // the riders without the engine: no ENGINE
            Triple(0x0ff, 3, listOf("5:a0", "2:4", "2:c")))               // the engine without the riders: no ZWRITE, 160
        for ((features, stage, want) in cases) {
            val got = if (features == 0x1ff && stage == 0) base else startWire(features, stage, fresh = true)
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
        startKnownFresh(t) { clock += PAST_THE_LAPSE }
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

    // ------------------------------------------------------------------------------------------- build C1-P: the stored stage
    /** A disposable directory, made when a test asks for it; the stage's file in it. */
    private val stageDir = lazy { Files.createTempDirectory("c1p-") }
    private val stageFile by lazy { stageDir.value.resolve(CfwTransportBase.C1_STAGE_FILE) }
    @AfterTest fun cleanStageDir() { if (stageDir.isInitialized()) stageDir.value.toFile().let { d ->
        d.walkTopDown().forEach { it.setWritable(true) }; d.deleteRecursively() } }
    private fun stored() = Files.readString(stageFile).trim()
    private fun sayings(prefix: String) = notes.count { it.first == "keeper" && it.second.startsWith(prefix) }
    private fun c1Session(file: java.nio.file.Path, stepDown: Boolean = false, clock: () -> Long = System::currentTimeMillis) =
        StartRig(GlassFirmwareSim().apply { damageContract = 2; damageFeatures = 0x1ff }, scope, clock = clock, c1StageFile = file,
            c1StageStepDown = stepDown).also { collect(it) }
    /** The starts whose DamageCaps line is in: the notes come in order on the collector's coroutine, so all a start said
     *  before that line is in too. */
    private fun startsSaid() = notes.count { it.first == "glass" && it.second.startsWith("DamageCaps") }

    /** C1-P (1): a transport made over a file holding N sends at its first start, byte for byte, what a start after
     *  `c1=N` in the same process sends — N = 1, 2, 3 on a C1 build — and says the restore once; a build without a C1
     *  bit (the installed image before C1, an upstream-shaped build) sends today's start over a stored 3. C1-Q (the
     *  focused read's F4): a first start at stage 2 writes stage 1's bytes (its size waits), so N = 2 is compared again
     *  at a start known fresh by the lapse, where stage 2's own 256 KiB goes out. */
    @Test
    fun aStoredStageStartsAsAC1ProbeInTheSameProcessDoes_C1P(): Unit = runBlocking {
        for (n in 1..CfwTransportBase.C1_TOP_STAGE) {
            Files.writeString(stageFile, "$n\n")
            val restored = startWire(0x1ff, 0, store = stageFile)
            assertEquals(startWire(0x1ff, n), restored, "stage $n restored: the start's bytes are an in-process c1=$n start's")
            waitUntil(10_000, message = { "stage $n: said: $notes" }) { sayings("C1 ladder: stage $n restored from the last run") > 0 }
        }
        Files.writeString(stageFile, "2\n")
        val restored2 = startWire(0x1ff, 0, store = stageFile, fresh = true)
        assertEquals(listOf("5:100", "2:4", "2:24"), controls(restored2, 'L'), "stage 2 restored, at a known-fresh start: its 256 KiB")
        assertEquals(startWire(0x1ff, 2, fresh = true), restored2, "stage 2 restored: a known-fresh start's bytes are an in-process c1=2 one's")
        val want = javaClass.getResource("/startwire/awake-start-contract2.txt")!!.readText().lines().filter { it.isNotBlank() }
        Files.writeString(stageFile, "3\n")
        for (features in listOf(null, 0x7f)) assertEquals(want, startWire(features, 0, store = stageFile), "features $features over a stored 3")
    }

    /** C1-P (2), (3): `c1=N` stores N; a hold-back stores the stage it lowered to, and a new transport over that file —
     *  a restart of the process — starts at the lowered stage and says so once, not again at its next start. */
    @Test
    fun theProbeAndTheHoldBackStoreTheStageAndARestartStartsThere_C1P(): Unit = runBlocking {
        var clock = 1_000_000L
        val t = c1Session(stageFile) { clock }
        t.devProbe("c1", "2")
        assertEquals("2", stored(), "c1=2 stores 2")
        t.devProbe("c1", "3")
        assertEquals("3", stored(), "c1=3 stores 3")
        t.start(full)
        assertEquals(0x2c, t.state.value.flagsInForce, "stage 3 armed")
        clock += 10_000L; t.glass.uptimeOffsetMs = clock; clock += 200_000L
        assertTrue(t.restartSession("test: after a reset"))
        t.start(full)
        waitUntil(10_000, message = { "the hold-back: $notes" }) { has("keeper", "armed stage 3 — stage 2 from here") }
        assertEquals("2", stored(), "the hold-back stores the stage it lowered to")
        t.stop()
        notes.clear()
        val t2 = c1Session(stageFile) { clock }
        t2.start(full)
        assertEquals(0x24, t2.state.value.flagsInForce, "the new process arms stage 2: ZWRITE and DRAW2, not ENGINE")
        // C1-Q: a process's first start asks at most 160 KiB (it cannot know what the last process left on the lenses);
        // the focused read's F4 — stage 1 gives the same, so the start after a lapse shows stage 2's own size
        assertEquals(160 * 1024, t2.state.value.cacheSize, "and 160 KiB until a start after a lapsed lease")
        assertTrue(t2.restartSession("test: the next session"))
        clock += PAST_THE_LAPSE
        t2.start(full)
        assertEquals(256 * 1024, t2.state.value.cacheSize, "stage 2's 256 KiB at the start after a lapse")
        waitUntil(10_000, message = { "the second start's notes: $notes" }) { startsSaid() == 2 }
        assertEquals(1, sayings("C1 ladder: stage 2 restored from the last run"), "said once, at the first start: $notes")
        t2.stop()
    }

    /** C1-P (4): a stored stage out of 0..3, not a number, empty, or a file that cannot be read reads as 0 — today's
     *  stage-0 start — and is said. */
    @Test
    fun aStoredStageThatCannotBeUsedReadsAs0AndIsSaid_C1P(): Unit = runBlocking {
        val base = startWire(0x1ff, 0)
        for (text in listOf("4", "-1", "x", "")) {
            Files.writeString(stageFile, text)
            assertEquals(base, startWire(0x1ff, 0, store = stageFile), "'$text' starts at stage 0")
            waitUntil(10_000, message = { "'$text' is said: $notes" }) { sayings("C1 ladder: the stored stage '$text'") > 0 }
            assertEquals(1, sayings("C1 ladder: the stored stage '$text' is not 0..3 — stage 0"), "'$text' is said: $notes")
        }
        Files.delete(stageFile); Files.createDirectory(stageFile)       // a name that cannot be read as a file
        assertEquals(base, startWire(0x1ff, 0, store = stageFile), "an unreadable store starts at stage 0")
        waitUntil(10_000, message = { "an unreadable store is said: $notes" }) { sayings("C1 ladder: the stored stage could not be read") > 0 }
        assertEquals(1, sayings("C1 ladder: the stored stage could not be read"), "an unreadable store is said: $notes")
    }

    /** C1-P (5): a stage that cannot be stored is a fault and a `keeper` note, the file keeps what it held, and the run
     *  arms the stage asked for. */
    @Test
    fun aStageThatCannotBeStoredIsAFaultAndStillAppliesToThisRun_C1P(): Unit = runBlocking {
        Files.writeString(stageFile, "1\n")
        stageDir.value.toFile().setWritable(false)
        assertTrue(!Files.isWritable(stageDir.value), "the pin needs a directory this user cannot write")
        val t = c1Session(stageFile)
        t.devProbe("c1", "3")
        waitUntil(10_000, message = { "the fault: $notes" }) { has("fault", "c1stage: C1 ladder: stage 3 could not be stored") }
        waitUntil(10_000, message = { "the note: $notes" }) { has("keeper", "C1 ladder: stage 3 could not be stored") }
        assertEquals("1", stored(), "the file keeps what it held")
        t.start(full)
        assertEquals(0x2c, t.state.value.flagsInForce, "this run arms stage 3")
        t.stop()
    }

    /** C1-P R1 §1 (a), (c): the last run ended on an uncaught error (the phone's first stack is told so). A stored 2 reads
     *  as 0: the first start sends stage 0's bytes, the file holds 0, and the step-down is said once — a `keeper` note
     *  and a `holdback` fault — not again at the next start. A stored 0 says nothing. (b), no such error and a stored 2,
     *  is pin (1). And R1 §2 (A 2.2): a `c1=N` before the first start leaves only its own note standing. */
    @Test
    fun anUncaughtErrorInTheLastRunStepsAStoredStageDownTo0_C1P(): Unit = runBlocking {
        val base = startWire(0x1ff, 0)
        val said = "C1 ladder: an uncaught error ended the last run with stage 2 stored — stage 0 from here (probe c1=2 to try again)"
        fun ladder() = notes.filter { "C1 ladder" in it.second }
        Files.writeString(stageFile, "2\n")
        assertEquals(base, startWire(0x1ff, 0, store = stageFile, stepDown = true), "(a) a stored 2: stage 0's bytes")
        assertEquals("0", stored(), "(a) the file holds 0")
        Files.writeString(stageFile, "2\n")
        notes.clear()
        val t = c1Session(stageFile, stepDown = true)
        t.start(full)
        assertEquals(0x4, t.state.value.flagsInForce, "(a) stage 0: DRAW2 only")
        assertTrue(t.restartSession("test: the next session"))
        t.start(full)
        waitUntil(10_000, message = { "(a) the second start's notes: $notes" }) { startsSaid() == 2 }
        assertEquals(listOf("keeper" to said, "fault" to "holdback: $said"), ladder(), "(a) said once, a note and a fault")
        t.stop()
        notes.clear()
        Files.writeString(stageFile, "0\n")
        assertEquals(base, startWire(0x1ff, 0, store = stageFile, stepDown = true), "(c) a stored 0: stage 0")
        waitUntil(10_000, message = { "(c) the start's notes: $notes" }) { startsSaid() == 1 }
        assertEquals(emptyList(), ladder(), "(c) a stored 0: nothing said")
        for ((stepDown, was) in listOf(false to 2, true to 0)) {
            notes.clear()
            Files.writeString(stageFile, "2\n")
            val p = c1Session(stageFile, stepDown)
            p.devProbe("c1", "1")
            p.start(full)
            waitUntil(10_000, message = { "A 2.2, step-down $stepDown: the start's notes: $notes" }) { startsSaid() == 1 }
            assertEquals(listOf("C1 ladder: stage $was → 1"), ladder().map { it.second.substringBefore(" (") }, "A 2.2, step-down $stepDown")
            p.stop()
        }
    }

    /** C1-P R1 §1 (d): a start loop on one fault takes the uncaught-error record's "the same again" path, whose pickup
     *  says nothing (null) past 8. The phone reads the pending file before the pickup ([UncaughtRecord.isPending]), so
     *  the ninth start steps down all the same. */
    @Test
    fun theSameAgainPathOfTheUncaughtRecordStepsDownToo_C1P(): Unit = runBlocking {
        val dir = Files.createDirectories(stageDir.value.resolve("data"))
        val previous = Thread.UncaughtExceptionHandler { _, _ -> }
        var pending = false
        var picked: String? = ""
        for (i in 1..9) {            // nine processes, each ended by the same fault (one call line)
            UncaughtRecord.handlerFor(dir, "b", previous).uncaughtException(Thread("DefaultDispatcher-worker-$i"), IllegalStateException("start $i"))
            pending = UncaughtRecord.isPending(dir)
            picked = UncaughtRecord.takePending(dir, null)
        }
        assertEquals(null, picked, "the ninth pickup of one fault says nothing")
        assertTrue(pending, "the record was pending before it")
        Files.writeString(stageFile, "2\n")
        assertEquals(startWire(0x1ff, 0), startWire(0x1ff, 0, store = stageFile, stepDown = pending), "a stored 2: stage 0's bytes")
        assertEquals("0", stored(), "the file holds 0")
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
        // C1-Q: a first start, its session's end, and the clock 200 s on — past the lease's lapse, so the start below is
        // known fresh and asks 256 KiB, and past the hold-back's window, so the arming the first start's FLAGS_SETs
        // stamped is not what a reset below is read against — only the size rung's own stamp can be (C1-Q's sweep:
        // without the move, `P-sizerung-stamp` was not caught)
        t.start(full)
        assertTrue(t.restartSession("test: the session ends"))
        clock += 200_000L
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
            startKnownFresh(t) { clock += PAST_THE_LAPSE }
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
        // C1-Q: the 256 KiB size below needs a start the lenses are known fresh at — the session ends before the clock
        // moves past the lease, so no renewal is written at the later time
        clock += 10_000L; sim.uptimeOffsetMs = clock
        assertTrue(t.restartSession("test: after a reset"))
        clock += 200_000L
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
            var clock = 1_000_000L
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true), clock = { clock })
            t.devProbe("c1", "2")
            t.start(full); t.stop()          // C1-Q: a first start, then the clock past the lapse: the shell's start is
            clock += PAST_THE_LAPSE          // known fresh and asks 256 KiB
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
     *  the next start arms what the stage says (stage 3: ENGINE and the 256 KiB cache). DX5b: and that last end read
     *  again one start later, against the stage-3 arming that came AFTER it (a plain end of the phone's between) —
     *  an end before the last arming is not that arming's reset. */
    @Test
    fun aLeftLinkEndOutsideTheWindowOrWithNoRungArmedChangesNothing_BS1(): Unit = runBlocking {
        data class Case(val label: String, val c1First: Int, val c1Then: Int?, val endAfterMs: Long, val reason: String, val restart: Boolean,
                        val armedSince: Boolean = false)
        for (c in listOf(
            Case("after the window", 3, null, 130_000L, "LEFT disconnected: supervision timeout", true),
            Case("not restart-like", 3, null, 10_000L, "LEFT disconnected: ended by phone", false),
            Case("no rung armed", 0, 3, 10_000L, "LEFT disconnected: supervision timeout", true),
            Case("before the last arming", 0, 3, 10_000L, "LEFT disconnected: supervision timeout", true, armedSince = true))) {
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
            if (c.armedSince) {
                // that start armed stage 3 after the LEFT end; the phone then ends the link, and the next start reads
                // the old end against this arming
                assertEquals(0x2c, t.state.value.flagsInForce, "${c.label}: stage 3 armed after the end")
                clock += 10_000L
                t.endLink("LEFT disconnected: ended by phone")
                clock += 200_000L
                t.start(full)
            }
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
            // C1-R6 B6-6: on any failure the transport's notes go to the test's output with the case, then the failure
            try {
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
            // C1-P R1 §2: the count is taken before the model has the packet (StartRig.writeArm), so the case's change
            // could reach the model first; RIGHT's own refusal record of the mode 25 is the model having taken it
            waitUntil(10_000, message = { "$case: RIGHT's model took the mode 25: ${glass.refusalRecord(Arm.RIGHT)}" }) {
                (glass.refusalRecord(Arm.RIGHT)[0] and 0x7F) == 25 }
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
            } catch (e: Throwable) {
                println("B-N3 pin, case '$case', failed (${e.message}); the transport's notes in order:\n" +
                    notes.joinToString("\n") { "  ${it.first}: ${it.second}" })
                throw e
            }
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

    // ------------------------------------------------------------------------------------- round 6's rulings (C1-R6 §B)
    /** C1-R6 B6-3 (focus B, NOTE 2): an arming stamps itself only when it really sends. A FLAGS_SET called under an
     *  epoch the session has left (the arming loop's race with a link end, between its check and the call) sends
     *  nothing and leaves the stamp as it was, so a LEFT end that came before it is still read against the stage-3
     *  arming at the next start: one stage down, said as 10 s after that arming. */
    @Test
    fun aFlagsSetWhoseSessionEndedLeavesTheArmingStampAsItWas_R6_B63(): Unit = runBlocking {
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x1ff }
        var clock = 1_000_000L
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true), clock = { clock })
        collect(t)
        t.devProbe("c1", "3")
        t.start(full)
        fun said() = notes.filter { it.first == "keeper" }.joinToString(" | ") { it.second }
        assertEquals(0x2c, t.state.value.flagsInForce, "stage 3 armed: ${said()}")
        clock += 10_000L
        sim.rebootForTest(Arm.LEFT, clock)
        t.endLink("LEFT disconnected: supervision timeout")
        clock += 5_000L
        val writes = sim.damageControlWritesSeen
        assertEquals(null, t.flagsSetUnderAStaleEpochForTest(0x2c), "a FLAGS_SET under an epoch the session left is not answered")
        delay(100)
        assertEquals(writes, sim.damageControlWritesSeen, "nothing was sent")
        clock += 200_000L
        t.start(full)
        waitUntil(10_000, message = { "the LEFT end still read against the stage-3 arming: ${said()}" }) { holdBacks().size == 1 }
        assertTrue("10 s after the C1 ladder armed stage 3" in holdBacks().single(), "the stamp was the stage-3 arming's: ${holdBacks()}")
        assertEquals(0x24, t.state.value.flagsInForce, "stage 2: ENGINE gone, DRAW2 and ZWRITE kept: ${said()}")
        t.stop()
    }

    /** C1-R6 B6-5: B-S1's once-guard. A LEFT end drops one stage once. A start that holds back on it and then arms
     *  nothing at all (its op 5 is never answered and the link ends) leaves the arming's stamp where it was; the next
     *  start reads the same end against the same arming and does not hold back again. */
    @Test
    fun aLeftEndHoldsBackOnceWhenTheStartAfterItArmsNothing_R6_B65(): Unit = runBlocking {
        val glass = GlassFirmwareSim().apply { damageContract = 2; damageFeatures = 0x1ff }
        var clock = 1_000_000L
        val t = StartRig(glass, scope, clock = { clock })
        t.heapReads = false
        collect(t)
        t.devProbe("c1", "2")
        startKnownFresh(t) { clock += PAST_THE_LAPSE }
        fun said() = notes.filter { it.first == "keeper" }.joinToString(" | ") { it.second }
        assertEquals(0x24, t.state.value.flagsInForce, "stage 2 armed: ${said()}")
        assertEquals(256 * 1024, t.state.value.cacheSize)
        clock += 10_000L
        glass.rebootForTest(Arm.LEFT, clock)
        t.endLink("LEFT disconnected: supervision timeout")
        clock += 200_000L
        val sizes = java.util.concurrent.CopyOnWriteArrayList<String>()
        val swallow = java.util.concurrent.atomic.AtomicBoolean(true)
        t.onWrite = { a, p ->
            if (StartRig.damageOp(p) != DamageMsg.OP_CACHE_SIZE) p
            else { sizes += "${a.name.first()}:${controlArg(p)}"; if (swallow.get()) null else p }
        }
        val second = scope.async { runCatching { t.start(full) } }
        waitUntil(10_000, message = { "op 5 for 160 KiB written to both arms: $sizes; ${said()}" }) { "L:160" in sizes && "R:160" in sizes }
        assertEquals(1, holdBacks().size, "the LEFT end held stage 2 back: ${said()}")
        assertTrue("stage 1 from here" in holdBacks().single(), holdBacks().single())
        t.endLink("test: the start ends before it arms anything")
        second.await()
        swallow.set(false)
        clock += 200_000L
        t.start(full)
        assertEquals(0x24, t.state.value.flagsInForce, "stage 1: DRAW2 and ZWRITE: ${said()}")
        assertEquals(160 * 1024, t.state.value.cacheSize, "stage 1: 160 KiB")
        assertEquals(1, holdBacks().size, "the same LEFT end, against the same arming, holds back once: ${holdBacks()}")
        t.stop()
    }

    /** C1-R6 B6-5: A-S1's engine gate. Only a build that reports the engine (DamageCaps bit 7) holds the engine word, so
     *  a refusal 14 is judged on no other build. Glasses that report 0x17f at the session start (the riders, no engine)
     *  and then refuse four cache writes in a row 14 — planted: after the capability read the model becomes the engine
     *  build and holds the word — are not said to refuse uploads. */
    @Test
    fun aBuildWithoutTheEngineIsNotSaidToRefuseUploads_R6_B65(): Unit = runBlocking {
        val glass = GlassFirmwareSim().apply { damageContract = 2; damageFeatures = 0x17f }
        val t = SimTransport(glass, scope, SimTransport.Timing(instant = true))
        collect(t)
        t.start(full)
        assertEquals(0x4, t.state.value.flagsInForce, "stage 0: DRAW2 only")
        glass.damageFeatures = 0x1ff                    // after the capability read: the transport still holds 0x17f
        val images = java.util.concurrent.atomic.AtomicInteger()
        glass.imageObserver = { arm, _ -> if (arm == Arm.RIGHT) images.incrementAndGet() }
        fun hold(on: Boolean) = synchronized(glass) { for (arm in Arm.entries) glass.motionForTest(arm).holdWordForTest(on) }
        fun said() = notes.filter { it.first == "fault" && it.second.startsWith("uploads:") }
        hold(true)
        for (n in 1..4) {
            val before = images.get()
            val list = CfwModes.cacheUpdate2(listOf(CfwModes.CacheWrite2(1000 + 100 * n, ByteArray(64) { (it + n).toByte() })), 160 * 1024)
            t.submit(FlushRequest(listOf(DisplayOp.CacheWrite2(list)), 0, "ATLAS"))
            t.submit(FlushRequest(listOf(DisplayOp.Fill(Rect(400, 300, 8, 4), Rect(400, 300, 8, 4), n % 15)), 0, "elsewhere"))
            waitUntil(10_000, message = { "write $n and its present reached RIGHT" }) { images.get() >= before + 2 }
            val r = t.telemetry("test read")
            assertEquals(14, r?.refusal?.reason, "write $n refused 14 (planted): ${r?.describe()}")
        }
        delay(200)
        assertEquals(emptyList(), said(), "a build that does not report the engine: nothing said of reason 14")
        hold(false)                                     // the model has one thread: no release point with the word held
        t.stop()
    }

    // --------------------------------------------------------------- DM1: the Kotlin sweep's survivors (C1-DM1-built.md)
    /** The data field of a single-packet EvenHub image message (`EvenHubMsg.imageFragment`: field 5's wrapper, its
     *  field 8 — G2CC docs/G2_BLE_PROTOCOL.md §6.5), or null for any other packet. */
    private fun imageData(p: ByteArray): ByteArray? {
        if (!StartRig.firstOfImage(p) || p[4].toInt() != 1) return null
        val wrapper = Pb.fields(StartRig.body(p)).firstOrNull { it.field == 5 }?.bytes ?: return null
        return Pb.bytesField(wrapper, 8)
    }

    /** The sub-messages of a mode-8 batch ([8][count][len16][sub]…), or the image itself when it is not one. */
    private fun subsOf(img: ByteArray): List<ByteArray> {
        if (img[0].toInt() != 8) return listOf(img)
        val out = ArrayList<ByteArray>()
        var i = 2
        repeat(img[1].toInt() and 0xFF) {
            val n = (img[i].toInt() and 0xFF) or ((img[i + 1].toInt() and 0xFF) shl 8)
            out += img.copyOfRange(i + 2, i + 2 + n)
            i += 2 + n
        }
        return out
    }

    /** DM1 (`P-isC1-features`), spec §6.2 for the hand probe: on the installed image (features 0x7f, no C1 bit) a
     *  hand-set `flags=` goes out as asked — bits 3 and 5 included, no ladder note — as it did before C1. */
    @Test
    fun onTheInstalledImageAHandSetFlagsProbeGoesOutAsAsked_DM1(): Unit = runBlocking {
        val glass = GlassFirmwareSim().apply { damageContract = 2; damageFeatures = 0x7f }
        val t = StartRig(glass, scope)
        t.heapReads = false
        collect(t)
        t.start(full)
        assertEquals(0x4, t.state.value.flagsInForce, "DRAW2 armed")
        val sets = java.util.concurrent.CopyOnWriteArrayList<String>()
        t.onWrite = { a, p -> if (StartRig.damageOp(p) == DamageMsg.OP_FLAGS_SET) sets += "${a.name.first()}:${controlArg(p).toString(16)}"; p }
        t.devProbe("flags", "0x2c")
        waitUntil(10_000, message = { "the probe's FLAGS_SET written to both arms: $sets" }) { sets.size >= 2 }
        assertEquals(listOf("L:2c", "R:2c"), sets.take(2).sorted(), "the set goes out as asked: $notes")
        assertTrue(notes.none { "the C1 ladder's" in it.second }, "no ladder note on a build without a C1 bit: $notes")
        t.onWrite = null
        t.stop()
    }

    /** DM1 (`P-start-holdback-unladdered`, `P-ladder-without-draw2`), spec §6.1: a reset after an arming that carried none
     *  of the ladder is today's hold-back alone — that start asks 160 KiB and arms nothing of the ladder, though the stage
     *  was set to 3 by hand since — and the start after it, with DRAW2 held back, arms none of the ladder either (its
     *  bits ride only with DRAW2). */
    @Test
    fun aResetAfterAnArmingWithoutTheLadderArmsNoneOfItThenOrNext_DM1(): Unit = runBlocking {
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x1ff }
        var clock = 1_000_000L
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true), clock = { clock })
        collect(t)
        t.start(full)
        assertEquals(0x4, t.state.value.flagsInForce, "stage 0: DRAW2 only")
        t.devProbe("c1", "3")
        fun said() = notes.filter { it.first == "keeper" }.joinToString(" | ") { it.second }
        clock += 10_000L; sim.uptimeOffsetMs = clock; clock += 200_000L
        assertTrue(t.restartSession("test: after a reset"))
        t.start(full)
        waitUntil(10_000, message = { "today's hold-back: ${said()}" }) { has("keeper", "hold-back: the glasses reset 10 s after flags 0x4 were armed") }
        assertEquals(0, t.state.value.flagsInForce, "DRAW2 held back as today, nothing of the ladder: ${said()}")
        assertEquals(160 * 1024, t.state.value.cacheSize, "no 256 KiB ask at the start after the reset")
        clock += 1_000L
        assertTrue(t.restartSession("test: the start after"))
        t.start(full)
        assertEquals(0, t.state.value.flagsInForce, "DRAW2 still held back, so no ZWRITE or ENGINE: ${said()}")
        t.stop()
    }

    /** DM1 (`P-reset-window`), `FORK.md` §3.2's window read by the ladder: a reset 130 s after a stage-3 arming is
     *  outside the hold-back window — the next start arms stage 3 again. */
    @Test
    fun aResetOutsideTheHoldBackWindowLeavesTheStage_DM1(): Unit = runBlocking {
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x1ff }
        var clock = 1_000_000L
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true), clock = { clock })
        collect(t)
        t.devProbe("c1", "3")
        t.start(full)
        fun said() = notes.filter { it.first == "keeper" }.joinToString(" | ") { it.second }
        assertEquals(0x2c, t.state.value.flagsInForce, "stage 3 armed: ${said()}")
        // C1-Q: the session ends before the clock moves, so no renewal is written at the later time and the start after
        // the reset is known fresh (its lease lapsed) — the first start asked 160, this one asks the stage's 256
        assertTrue(t.restartSession("test: a reset outside the window"))
        clock += 130_000L; sim.uptimeOffsetMs = clock; clock += 10_000L
        t.start(full)
        assertEquals(0x2c, t.state.value.flagsInForce, "stage 3 again: ${said()}")
        assertEquals(256 * 1024, t.state.value.cacheSize, "the 256 KiB size at the start after the lapse")
        t.stop()
    }

    /** DM1 (`P-sizerung-got`), B-N4's size rung: it counts only when the session got 256 KiB. Stage 1 allocates its
     *  cache at 160; `c1=2`; the session ends and the phone's clock moves past the lapse — but RIGHT's lease was renewed
     *  meanwhile by a writer this transport does not know (planted in the model: the shape of the PC's own transport
     *  driving by hand, the focused read's F3). C1-Q: the start is known fresh by the phone's record, so op 5 asks 256
     *  and finds RIGHT's 160 KiB cache allocated; the session runs at 160 and the flag set after it records stage 1 — a
     *  reset then drops to stage 0, not to stage 1. (Before C1-Q a renewal asked 256; it now asks 160.) */
    @Test
    fun aSizeRungTheSessionDidNotGetIsNotCounted_DM1(): Unit = runBlocking {
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x1ff }
        var clock = 1_000_000L
        val t = StartRig(sim, scope, clock = { clock })
        t.heapReads = false
        collect(t)
        t.devProbe("c1", "1")
        t.start(full)
        fun said() = notes.filter { it.first == "keeper" || it.first == "glass" }.joinToString(" | ") { it.second }
        assertEquals(0x24, t.state.value.flagsInForce, "stage 1: ${said()}")
        t.submit(FlushRequest(listOf(DisplayOp.CacheWrite2(CfwModes.cacheUpdate2(listOf(CfwModes.CacheWrite2(1000, ByteArray(64) { it.toByte() })), 160 * 1024))), 0, "ATLAS"))
        waitUntil(10_000, message = { "the first write allocated the cache" }) { sim.left.textureCache != null && sim.right.textureCache != null }
        t.devProbe("c1", "2")
        assertTrue(t.restartSession("test: the session ends"))
        sim.forceLease(Arm.RIGHT, clock + PAST_THE_LAPSE + 60_000L)       // another writer kept RIGHT's lease alive
        clock += PAST_THE_LAPSE
        t.start(full)
        assertTrue(has("glass", "cache size: asked 256 KiB, status 4, 160 KiB this session"), "known fresh by the record: ${said()}")
        assertEquals(160 * 1024, t.state.value.cacheSize, "asked 256, RIGHT's 160 KiB cache was still allocated: ${said()}")
        assertEquals(0x24, t.state.value.flagsInForce, "ZWRITE armed: ${said()}")
        clock += 10_000L; sim.uptimeOffsetMs = clock; clock += 200_000L
        assertTrue(t.restartSession("test: after a reset"))
        t.start(full)
        waitUntil(10_000, message = { "the hold-back from stage 1: ${said()}" }) { has("keeper", "armed stage 1 — stage 0 from here") }
        assertEquals(0x4, t.state.value.flagsInForce, "stage 0: ZWRITE gone, DRAW2 kept: ${said()}")
        t.stop()
    }

    /** DM1 (`P-probe-close-acked`), §5.8: the disarm waits for its CLOSE — a CLOSE of every instance whose write fails
     *  is said, and the FLAGS_SET that clears ENGINE is not sent (the glasses keep ENGINE). */
    @Test
    fun aDisarmWhoseCloseDidNotLandIsNotSent_DM1(): Unit = runBlocking {
        val glass = GlassFirmwareSim().apply { damageContract = 2; damageFeatures = 0x1ff }
        val t = StartRig(glass, scope)
        t.heapReads = false
        collect(t)
        t.devProbe("c1", "3")
        t.start(full)
        assertEquals(0x2c, t.state.value.flagsInForce, "ENGINE in force")
        val sets = java.util.concurrent.CopyOnWriteArrayList<Int>()
        t.onWrite = { _, p ->
            if (StartRig.damageOp(p) == DamageMsg.OP_FLAGS_SET) sets += controlArg(p)
            if (imageData(p)?.contentEquals(CfwModes.motionClose(CfwModes.MOTION_ALL)) == true) throw java.io.IOException("test: the CLOSE's write fails")
            p
        }
        t.devProbe("flags", "0x4")
        waitUntil(10_000, message = { "the failed CLOSE is said: $notes" }) { has("motion", "CLOSE of every instance not acked") }
        delay(300)
        assertEquals(emptyList(), sets.toList(), "no FLAGS_SET clears ENGINE after a CLOSE that did not land")
        for (arm in Arm.entries) assertEquals(0x2c, glass.damageFlags(arm), "$arm keeps ENGINE")
        t.onWrite = null
        t.stop()
    }

    /** DM1 (`P-closes-lens`, `P-closes-text-to-edge`), §5.6's rule as the transport bounds a write: each lens's write is
     *  read against that lens's area (a pair here: L at x 100, R at x 300) — a left write over the RIGHT lens's area
     *  closes nothing — and a cached glyph run counts from its origin to the panel's edges, so one that starts above and
     *  left of the area closes it. */
    @Test
    fun theCloseRuleReadsEachLensAndAGlyphRunToTheEdges_DM1(): Unit = runBlocking {
        val (sim, t) = engineSession()
        val p = Motion.program(40_000) { track(Rect(100, 100, 40, 20), Rect(300, 100, 40, 20), Motion.Backdrop.Level(3)) { fill(9, 200) } }
        assertTrue(t.sendMotion(messages(p, slot = 1, period = 128, tag = 0x81)))
        waitUntil(10_000, message = { "the PLAY landed" }) { sim.motionWindows(Arm.RIGHT).any { it.slot == 1 } }
        val batches = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        sim.imageObserver = { arm, img -> if (arm == Arm.RIGHT && img[0].toInt() == 8) batches += img }
        t.submit(FlushRequest(listOf(DisplayOp.Fill(Rect(300, 104, 8, 4), Rect(500, 104, 8, 4), 7)), 0, "over the other lens's area"))
        waitUntil(10_000, message = { "the first flush landed" }) { batches.size >= 1 }
        assertTrue(subsOf(batches[0]).none { it[0].toInt() == 26 }, "each lens's write misses that lens's area: no CLOSE")
        assertTrue(sim.motionWindows(Arm.RIGHT).any { it.slot == 1 }, "the instance still open")
        t.submit(FlushRequest(listOf(DisplayOp.DrawText2(1000, 80, 280, 90, 15, byteArrayOf(0x41))), 0, "a glyph run above and left"))
        waitUntil(10_000, message = { "the second flush landed" }) { batches.size >= 2 }
        assertEquals(listOf(26, 2, 1), subsOf(batches[1])[0].take(3).map { it.toInt() and 0xFF }, "the run counts to the panel's edges: a CLOSE of slot 1 heads the batch")
        t.stop()
    }

    /** DM1 (`P-shell-divergence-keyframe`), spec §6.4: an END CRC that disagrees with the mirror's is a `divergence` note
     *  and the shell keyframes on it. The probe's `list` runs to its end with END in belief (so nothing else asks for a
     *  keyframe: `TestProgramsTest`'s R1.16 pin), and RIGHT's notify arrives with its CRC changed by one bit. */
    @Test
    fun anEndCrcThatDisagreesKeyframesTheShell_DM1(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-motion-crc")
        try {
            val glass = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x1ff }
            val t = StartRig(glass, scope)
            collect(t)
            t.devProbe("c1", "3")
            t.onNotify = { _, p ->
                val m = if (StartRig.sid(p) == SettingsMsg.SID) DamageMsg.parseMotion(StartRig.body(p)) else null
                if (m?.outcome != DamageMsg.OUTCOME_DREW) p
                else { val f = java.util.TreeMap(m.fields).also { it[8] = it[8]!! xor 1L }
                    StartRig.reframe(p, Pb.cat(Pb.v(1, 3), Pb.v(2, 0), Pb.l(DamageMsg.MOTION_FIELD, Pb.cat(f.entries.map { Pb.v(it.key, it.value) })))) }
            }
            var clock = Shell.LocalClock(12, 0, "12:00", "PM")
            val shell = Shell(FakeText(), t, Persistence(tmp.resolve("s.json")), null, scope) { clock }
            shell.start()
            waitUntil(10_000, 10, { "shell did not settle" }) { shell.isQuiescent() }
            assertEquals(0x2c, t.state.value.flagsInForce, "ENGINE in force under the shell")
            val keyframes = java.util.concurrent.atomic.AtomicInteger()
            glass.imageObserver = { arm, img -> if (arm == Arm.RIGHT && subsOf(img).any { (it[0].toInt() and 0x7F) == 6 }) keyframes.incrementAndGet() }
            t.devProbe("motion", "list")
            waitUntil(10_000, message = { "list played: $notes" }) { notes.any { it.first == "motion" && it.second.startsWith(wm.damage.core.motion.TestPrograms.PLAYED + "list") } }
            waitUntil(10_000, 10, { "shell did not settle" }) { shell.isQuiescent() }
            val later = System.currentTimeMillis() + 5_000
            glass.motionClock = { later }
            for (arm in Arm.entries) glass.conformanceWake(arm, later)
            waitUntil(10_000, message = { "the altered notify: $notes" }) { has("divergence", "END CRC") }
            clock = Shell.LocalClock(12, 1, "12:01", "PM")
            shell.postMinuteTick()
            waitUntil(10_000, message = { "a keyframe after the divergence: ${keyframes.get()}" }) { keyframes.get() >= 1 }
            shell.stop()
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /** DM1 (`P-zw-one-at-a-time`, `P-zw-max-list`, `P-zw-withdrawn`), spec §2 row 23: one mode 25 waits for its judge at
     *  a time (a second write while it waits goes as mode 19); a list past the 16,384 B inflate goes as mode 19; a mode-25
     *  refusal on record with nothing waiting (one the judge took as landed) turns mode 25 off for the session. */
    @Test
    fun theMode25JudgesBounds_DM1(): Unit = runBlocking {
        val glass = GlassFirmwareSim().apply { damageContract = 2; damageFeatures = 0x1ff }
        val t = StartRig(glass, scope)
        collect(t)
        t.devProbe("c1", "1")
        t.start(full)
        assertEquals(0x24, t.state.value.flagsInForce, "stage 1: DRAW2 and ZWRITE")
        val right = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        glass.imageObserver = { arm, img -> if (arm == Arm.RIGHT && (img[0].toInt() == 19 || img[0].toInt() == 25)) right += img }
        fun list(off4: Int, n: Int = 2000) = CfwModes.cacheUpdate2(listOf(CfwModes.CacheWrite2(off4, ByteArray(n) { (it / 100).toByte() })), 160 * 1024)
        t.submit(FlushRequest(listOf(DisplayOp.CacheWrite2(list(1000))), 0, "ATLAS"))
        t.submit(FlushRequest(listOf(DisplayOp.CacheWrite2(list(2000))), 0, "ATLAS"))
        waitUntil(10_000, message = { "two writes reached RIGHT" }) { right.size >= 2 }
        assertEquals(listOf(25, 19), right.take(2).map { it[0].toInt() }, "the first waits for its judge; the second goes as mode 19")
        t.devProbe("telemetry", "read")
        delay(200)
        t.submit(FlushRequest(listOf(DisplayOp.CacheWrite2(list(3000, 16_400))), 0, "ATLAS"))
        waitUntil(10_000, message = { "the long list reached RIGHT: $notes" }) { right.size >= 3 }
        assertEquals(19, right[2][0].toInt(), "a 16,404 B list is past the inflate: mode 19")
        // a mode-25 refusal on RIGHT's record that no waiting message answers for
        val now = System.currentTimeMillis()
        glass.conformanceControl(Arm.RIGHT, DamageMsg.OP_FLAGS_SET, DamageMsg.FLAG_DRAW2, now)
        val planted = list(9000)
        assertTrue(!glass.conformanceMessage(Arm.RIGHT, byteArrayOf(25) + Zl.deflate(planted.copyOfRange(1, planted.size)), now), "RIGHT refuses it (15)")
        glass.conformanceControl(Arm.RIGHT, DamageMsg.OP_FLAGS_SET, DamageMsg.FLAG_DRAW2 or DamageMsg.FLAG_ZWRITE, now)
        t.devProbe("telemetry", "read")
        waitUntil(10_000, message = { "the refusal on record is said: $notes" }) { has("glass", "a mode-25 refusal on record") }
        val seen = right.size                               // the planted message reached the observer too
        t.submit(FlushRequest(listOf(DisplayOp.CacheWrite2(list(4000))), 0, "ATLAS"))
        waitUntil(10_000, message = { "the next write reached RIGHT" }) { right.size > seen }
        assertEquals(19, right[seen][0].toInt(), "mode 25 off for the session")
        t.stop()
    }

    /** DM1 (`P-busy-reason-14`), A-S1: only reason 14 counts — cache writes refused in a row for another reason (5: an
     *  entry outside the session's cache) say nothing of a busy engine. */
    @Test
    fun cacheWritesRefusedForAnotherReasonAreNotSaidAsBusy_DM1(): Unit = runBlocking {
        val glass = GlassFirmwareSim().apply { damageContract = 2; damageFeatures = 0x1ff }
        val t = SimTransport(glass, scope, SimTransport.Timing(instant = true))
        collect(t)
        t.start(full)
        assertEquals(160 * 1024, t.state.value.cacheSize)
        val images = java.util.concurrent.atomic.AtomicInteger()
        glass.imageObserver = { arm, _ -> if (arm == Arm.RIGHT) images.incrementAndGet() }
        for (n in 1..4) {
            val before = images.get()
            // laid out for 256 KiB, so the encoder takes an entry past this session's 160: RIGHT refuses it 5
            val list = CfwModes.cacheUpdate2(listOf(CfwModes.CacheWrite2(41_000 + 100 * n, ByteArray(64) { (it + n).toByte() })), 256 * 1024)
            t.submit(FlushRequest(listOf(DisplayOp.CacheWrite2(list)), 0, "ATLAS"))
            t.submit(FlushRequest(listOf(DisplayOp.Fill(Rect(400, 300, 8, 4), Rect(400, 300, 8, 4), n)), 0, "elsewhere"))
            waitUntil(10_000, message = { "write $n and its present reached RIGHT" }) { images.get() >= before + 2 }
            val r = t.telemetry("test read")
            assertEquals(5, r?.refusal?.reason, "write $n refused 5: ${r?.describe()}")
        }
        delay(200)
        assertEquals(emptyList(), notes.filter { it.first == "fault" && it.second.startsWith("uploads:") }, "nothing said of a busy engine")
        t.stop()
    }

    // ------------------------------------------------------------------------------- build C1-Q: the session's cache size
    /** Cache writes the model took, per arm — modes 12 and 19, and 25 (mode 19 deflated, sent while ZWRITE is in
     *  force). The observer runs inside the model's write, so a count read afterwards has its message's effect behind it. */
    private val cacheWritesSeen = Arm.entries.associateWith { java.util.concurrent.atomic.AtomicInteger() }
    private fun countCacheWrites(sim: GlassFirmwareSim) {
        sim.imageObserver = { arm, img -> val m = img[0].toInt() and 0x7F
            if (m == 12 || m == 19 || m == CfwModes.ZWRITE_MODE) cacheWritesSeen.getValue(arm).incrementAndGet() }
    }
    private fun c1Sim(features: Int = 0x1ff) = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = features }
    private fun said() = notes.filter { it.first == "keeper" || it.first == "glass" }.joinToString("\n  ") { "${it.first}: ${it.second}" }
    /** The starts whose `cache size:` note said that [kib] KiB waits for a lapsed lease (C1-Q: the ask was capped). */
    private fun waitsSaid(kib: Int) = notes.count { it.first == "glass" && it.second.startsWith("cache size: asked") &&
        it.second.contains("; $kib KiB waits for a start after a lapsed lease") }

    /** C1-Q's invariant, read off both lenses of the model: each lens's cache — or, with none, the size its first write
     *  allocates at — is at least the session's size. */
    private fun assertEachLensHoldsTheSession(sim: GlassFirmwareSim, t: CfwTransportBase, what: String) {
        val s = t.state.value.cacheSize
        for (arm in Arm.entries) {
            val holds = synchronized(sim) { (if (arm == Arm.LEFT) sim.left else sim.right).cacheBytes.let { if (it != 0) it else CfwModes.TEXTURE_CACHE_SIZE } }
            assertTrue(holds >= s, "$what: $arm holds ${holds / 1024} KiB under a ${s / 1024} KiB session")
        }
    }

    /** After a start: a mode-19 write at the bottom of the session's cache and one at its top (where the shell's strip
     *  ring sits) — or, [v1], one mode-12 write — land on both lenses: each generation moves by one per write and no
     *  refusal is recorded; then the invariant. */
    private suspend fun writesLandOnBoth(sim: GlassFirmwareSim, t: CfwTransportBase, what: String, v1: Boolean = false) {
        val size = t.state.value.cacheSize
        val gen = Arm.entries.associateWith { sim.cacheGen(it) }
        val seen = Arm.entries.associateWith { cacheWritesSeen.getValue(it).get() }
        val writes = if (v1) listOf(DisplayOp.CacheWrite(CfwModes.cacheUpdate(listOf(CfwModes.CacheWrite(1024, ByteArray(512) { 7 })))))
            else listOf(1, size / 1024 - 4).map { kib -> DisplayOp.CacheWrite2(CfwModes.cacheUpdate2(
                listOf(CfwModes.CacheWrite2(kib * 256, ByteArray(2000) { (it / 100 + kib).toByte() })), size)) }
        for (w in writes) t.submit(FlushRequest(listOf(w), 0, "ATLAS"))
        waitUntil(10_000, message = { "$what: the writes reached both lenses: ${said()}" }) {
            Arm.entries.all { cacheWritesSeen.getValue(it).get() >= seen.getValue(it) + writes.size } }
        for (arm in Arm.entries) {
            assertEquals(0, sim.refusalRecord(arm)[0], "$what: $arm refused a write inside the ${size / 1024} KiB session " +
                "(record ${sim.refusalRecord(arm)}, cache ${sim.cacheSize(arm) / 1024} KiB)")
            assertEquals(gen.getValue(arm) + writes.size, sim.cacheGen(arm), "$what: every write landed on $arm")
        }
        assertEachLensHoldsTheSession(sim, t, what)
    }

    /** C1-Q, H1 (the C1-P fix stage's §3 reproduction, committed): stage 3 at 256 KiB on both lenses; then twice LEFT
     *  restarts alone and the next start comes 5 s later, inside RIGHT's lease. The ladder's hold-back lowers the stage
     *  each time; at stage 1 the start asks 160 while RIGHT still holds its 256 KiB cache and LEFT, fresh, allocates
     *  160. After every start each write the session's size allows lands on both lenses. */
    private suspend fun h1(): Triple<GlassFirmwareSim, SimTransport, java.util.concurrent.atomic.AtomicLong> {
        val sim = c1Sim()
        val clock = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true), clock = { clock.get() })
        collect(t)
        countCacheWrites(sim)
        t.devProbe("c1", "3")
        startKnownFresh(t) { clock.addAndGet(PAST_THE_LAPSE) }
        assertEquals(256 * 1024, t.state.value.cacheSize, "stage 3 at a known-fresh start: 256 KiB: ${said()}")
        writesLandOnBoth(sim, t, "stage 3")
        for (stage in listOf(2, 1)) {
            clock.addAndGet(10_000L)
            sim.rebootForTest(Arm.LEFT, clock.get())
            t.endLink("LEFT disconnected: supervision timeout")
            clock.addAndGet(5_000L)
            t.start(full)
            waitUntil(10_000, message = { "the hold-back to stage $stage: ${said()}" }) { holdBacks().size == 3 - stage }
            writesLandOnBoth(sim, t, "after the hold-back to stage $stage")
        }
        assertEquals(160 * 1024, t.state.value.cacheSize, "stage 1: the session is the 160 KiB it asked: ${said()}")
        assertEquals(256 * 1024, sim.cacheSize(Arm.RIGHT), "RIGHT keeps its 256 KiB until its next release point")
        assertEquals(160 * 1024, sim.cacheSize(Arm.LEFT), "LEFT, fresh after its restart, allocated the 160 KiB asked")
        assertTrue(has("glass", "cache size: asked 160 KiB, status 4, 160 KiB this session (allocated already at 256 KiB on RIGHT"), said())
        return Triple(sim, t, clock)
    }

    @Test
    fun aRestartOfLeftAloneWithTheNextStartInsideTheLease_H1_C1Q(): Unit = runBlocking {
        val (_, t, _) = h1()
        t.stop()
    }

    /** C1-Q, H3: after H1 (the session at 160, RIGHT holding 256, LEFT 160), `c1=2` and a start that is a renewal on both
     *  arms. RIGHT reports 256, the stage's own size; the start still asks no more than the last session's 160, so LEFT
     *  takes every write; its `cache size:` note says why. */
    @Test
    fun aRenewalAfterH1AsksNoMoreThanTheLastSession_H3_C1Q(): Unit = runBlocking {
        val (sim, t, clock) = h1()
        t.devProbe("c1", "2")
        clock.addAndGet(20_000L)
        assertTrue(t.restartSession("test: a renewal on both arms"))
        t.start(full)
        assertTrue(t.state.value.leaseCarried, "a renewal on both arms: ${t.state.value.leaseCarry}")
        assertEquals(160 * 1024, t.state.value.cacheSize, "the renewal asks no more than the last session's 160 KiB: ${said()}")
        writesLandOnBoth(sim, t, "the renewal after c1=2")
        // said at H1's first start (stage 3 asked 160 there) and at this renewal
        assertEquals(2, waitsSaid(256), said())
        t.stop()
    }

    /** C1-Q, H2: the ask must not RISE while LEFT carries. Both lenses at 160 under one lease; `c1=2` and a renewal (160,
     *  the wait said); the session runs on past the hold-back's window, then RIGHT restarts alone and the next start comes
     *  5 s after its link ended. RIGHT, fresh, answers status 0; LEFT still holds 160 — so the start asks 160, not 256. */
    @Test
    fun theAskDoesNotRiseWhileLeftCarries_H2_C1Q(): Unit = runBlocking {
        val sim = c1Sim()
        val clock = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true), clock = { clock.get() })
        collect(t)
        countCacheWrites(sim)
        t.start(full)
        writesLandOnBoth(sim, t, "stage 0")
        t.devProbe("c1", "2")
        clock.addAndGet(20_000L)
        assertTrue(t.restartSession("test: a renewal on both arms"))
        t.start(full)
        assertEquals(160 * 1024, t.state.value.cacheSize, "c1=2 and a renewal: 160 KiB: ${said()}")
        writesLandOnBoth(sim, t, "the renewal at stage 2")
        // past the hold-back's window, renewed on the model's clock in steps inside its lease
        repeat(2) {
            val acquires = sim.acquiresSeen
            clock.addAndGet(65_000L)
            waitUntil(10_000, message = { "renewals at the later time reached both lenses" }) { sim.acquiresSeen >= acquires + 6 }
        }
        sim.rebootForTest(Arm.RIGHT, clock.get())
        t.endLink("RIGHT disconnected: supervision timeout")
        clock.addAndGet(5_000L)
        t.start(full)
        assertEquals(160 * 1024, t.state.value.cacheSize, "RIGHT fresh, LEFT carrying 160 KiB: the ask stays 160: ${said()}")
        assertEquals(160 * 1024, sim.cacheSize(Arm.LEFT), "LEFT still holds its 160 KiB cache")
        writesLandOnBoth(sim, t, "after RIGHT restarted alone")
        assertEquals(emptyList(), holdBacks(), "RIGHT's restart came after the hold-back's window")
        assertEquals(2, waitsSaid(256), "said at both capped starts: ${said()}")
        t.stop()
    }

    /** C1-Q, the DRAW2 hold-back lifted by hand (installed image, no ladder): a reset after DRAW2's arming holds it back;
     *  the held-back start (64 KiB) comes after LEFT restarted alone, so LEFT allocates 64 while RIGHT carries 160 and
     *  the session is 64. `flags=0x4` lifts the hold-back; the next start, a renewal, asks the last session's 64 — DRAW2
     *  not armed, said — and LEFT takes every write. The wake after the shell's release is no lapse: 64 again, said. The
     *  start after a lapse asks 160 and arms DRAW2. */
    @Test
    fun aDraw2HoldBackLiftedByHandKeepsTheHeldBackSizeUntilAFreshStart_C1Q(): Unit = runBlocking {
        val sim = c1Sim(0x7f)
        val clock = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true), clock = { clock.get() })
        collect(t)
        countCacheWrites(sim)
        t.start(full)
        writesLandOnBoth(sim, t, "the first session")
        clock.addAndGet(10_000L)
        sim.rebootForTest(Arm.RIGHT, clock.get())
        t.endLink("RIGHT disconnected: supervision timeout")
        clock.addAndGet(5_000L)
        t.start(full)
        waitUntil(10_000, message = { "DRAW2's hold-back: ${said()}" }) { has("keeper", "hold-back: the glasses reset 10 s after flags 0x4 were armed") }
        assertEquals(0, t.state.value.flagsInForce, "DRAW2 held back")
        writesLandOnBoth(sim, t, "the session after the reset (v1)", v1 = true)      // RIGHT, fresh, allocates 160
        clock.addAndGet(10_000L)
        sim.rebootForTest(Arm.LEFT, clock.get())
        t.endLink("LEFT disconnected: supervision timeout")
        clock.addAndGet(5_000L)
        t.start(full)
        assertTrue(has("keeper", "DRAW2 held back since a reset followed its arming"), said())
        assertEquals(64 * 1024, t.state.value.cacheSize, "the held-back start's 64 KiB, though RIGHT carries 160: ${said()}")
        writesLandOnBoth(sim, t, "the held-back session (v1)", v1 = true)              // LEFT, fresh, allocates 64
        assertEquals(64 * 1024, sim.cacheSize(Arm.LEFT), "LEFT holds 64 KiB")
        assertEquals(160 * 1024, sim.cacheSize(Arm.RIGHT), "RIGHT holds 160 KiB")
        t.devProbe("flags", "0x4")
        clock.addAndGet(20_000L)
        assertTrue(t.restartSession("test: a renewal after the lift"))
        t.start(full)
        assertEquals(64 * 1024, t.state.value.cacheSize, "the renewal asks the last session's 64 KiB: ${said()}")
        assertTrue(has("keeper", "DRAW2 not armed: this session's cache is 64 KiB"), said())
        assertEquals(0, t.state.value.flagsInForce and DamageMsg.FLAG_DRAW2, "no DRAW2 over 64 KiB")
        writesLandOnBoth(sim, t, "the renewal after the lift (v1)", v1 = true)
        t.setLeaseWanted(false)                                                       // the shell's release on purpose
        assertTrue(t.restartSession("test: the wake"))
        t.start(full)
        assertEquals(64 * 1024, t.state.value.cacheSize, "a wake after a release is no lapse: 64 KiB again: ${said()}")
        assertEquals(2, waitsSaid(160), "the renewal and the wake say why: ${said()}")
        assertTrue(t.restartSession("test: the session ends"))
        clock.addAndGet(PAST_THE_LAPSE)
        t.start(full)
        assertEquals(160 * 1024, t.state.value.cacheSize, "the start after a lapse is known fresh: 160 KiB: ${said()}")
        assertEquals(DamageMsg.FLAG_DRAW2, t.state.value.flagsInForce and DamageMsg.FLAG_DRAW2, "DRAW2 armed again")
        writesLandOnBoth(sim, t, "the start after the lapse")
        t.stop()
    }

    /** C1-Q: the 256 KiB size goes out only at a start where both lenses are known to hold no cache — the lapse. `c1=2`
     *  and two renewals: each asks the last session's 160, its `cache size:` note says why, and no size rung is
     *  recorded — a reset after them blames ZWRITE (stage 1). `c1=2` again and the shell's release on purpose (the
     *  Silent Mode path): the wake is no lapse and asks 160 again. The start after a lapse asks 256 and both lenses
     *  hold 256 KiB. */
    @Test
    fun aRenewalOrAWakeWaitsAndTheStartAfterALapseAsks256_C1Q(): Unit = runBlocking {
        val sim = c1Sim()
        val clock = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true), clock = { clock.get() })
        collect(t)
        countCacheWrites(sim)
        t.start(full)
        writesLandOnBoth(sim, t, "stage 0")
        t.devProbe("c1", "2")
        for (n in 1..2) {
            clock.addAndGet(20_000L)
            assertTrue(t.restartSession("test: renewal $n"))
            t.start(full)
            assertEquals(160 * 1024, t.state.value.cacheSize, "renewal $n at stage 2: 160 KiB: ${said()}")
            assertEquals(0x24, t.state.value.flagsInForce, "renewal $n: DRAW2 and ZWRITE")
        }
        waitUntil(10_000, message = { "the third start's notes: ${said()}" }) { startsSaid() == 3 }
        assertEquals(2, waitsSaid(256), "each capped start says why: ${said()}")
        clock.addAndGet(10_000L); sim.uptimeOffsetMs = clock.get()
        assertTrue(t.restartSession("test: after a reset"))
        clock.addAndGet(1_000L)
        t.start(full)
        waitUntil(10_000, message = { "the hold-back blames what was armed: ${said()}" }) { holdBacks().isNotEmpty() }
        assertTrue("the C1 ladder armed stage 1 — stage 0 from here" in holdBacks().single(), "no size rung was recorded: ${holdBacks()}")
        t.devProbe("c1", "2")
        t.setLeaseWanted(false)                                                       // the shell's release on purpose
        assertTrue(Arm.entries.none { sim.cacheAllocated(it) }, "the release freed both lenses' caches")
        assertTrue(t.restartSession("test: the wake"))
        t.start(full)
        assertEquals(160 * 1024, t.state.value.cacheSize, "a wake after a release is no lapse: 160 KiB: ${said()}")
        writesLandOnBoth(sim, t, "the wake at stage 2")
        waitUntil(10_000, message = { "the wake's notes: ${said()}" }) { startsSaid() == 5 }
        assertEquals(3, waitsSaid(256), "the wake says why: ${said()}")
        assertTrue(t.restartSession("test: the session ends"))
        clock.addAndGet(PAST_THE_LAPSE)
        t.start(full)
        assertEquals(256 * 1024, t.state.value.cacheSize, "the start after a lapse is known fresh: 256 KiB: ${said()}")
        writesLandOnBoth(sim, t, "the start after the lapse at stage 2")
        assertTrue(Arm.entries.all { sim.cacheSize(it) == 256 * 1024 }, "both lenses hold 256 KiB")
        t.stop()
    }

    /** C1-Q: a lapsed lease makes a start known fresh only past the firmware's 87.9 s and the margin (15 s:
     *  `LEASE_FRESH_MARGIN_MS`) from the last acquire attempted — the values are written out here, not read from the
     *  constant. At stage 2 with the last session at 160: a start 1 s inside the margin asks 160 (the model's lease has
     *  lapsed by then; the margin is the phone's allowance for delivery and the clocks, which the model does not have);
     *  one 1 s past it asks 256 and records the size rung, so a reset after it blames stage 2. */
    @Test
    fun aLapsedLeaseIsKnownFreshOnlyPastItsMargin_C1Q(): Unit = runBlocking {
        val sim = c1Sim()
        val clock = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true), clock = { clock.get() })
        collect(t)
        countCacheWrites(sim)
        t.start(full)
        writesLandOnBoth(sim, t, "stage 0")
        t.devProbe("c1", "2")
        for ((gap, kib) in listOf(SettingsMsg.LEASE_EXPIRY_MS + 14_000L to 160, SettingsMsg.LEASE_EXPIRY_MS + 16_000L to 256)) {
            t.endLink("test: the link ends")
            clock.addAndGet(gap)
            t.start(full)
            assertEquals(kib * 1024, t.state.value.cacheSize, "a start $gap ms after the last acquire: $kib KiB: ${said()}")
            writesLandOnBoth(sim, t, "$gap ms after the last acquire")
        }
        clock.addAndGet(10_000L); sim.uptimeOffsetMs = clock.get()
        assertTrue(t.restartSession("test: after a reset"))
        clock.addAndGet(1_000L)
        t.start(full)
        waitUntil(10_000, message = { "the hold-back blames the size rung: ${said()}" }) { holdBacks().isNotEmpty() }
        assertTrue("the C1 ladder armed stage 2 — stage 1 from here" in holdBacks().single(), "the size rung was recorded: ${holdBacks()}")
        t.stop()
    }

    /** C1-Q, the carry record's strike: an acquire written within 10 s of a link end is struck from the carry's record
     *  (it may never have arrived) — but one that did arrive renewed the lease. The renewals 80 s after the start reach
     *  LEFT and are lost on the way to RIGHT; the link ends 5 s later and the next start comes 105 s after the session's
     *  first acquire. RIGHT's lease has lapsed (it is fresh); LEFT's runs on with its 160 KiB cache. The start is not
     *  known fresh: it asks 160, not the stage's 256. */
    @Test
    fun aStruckAcquireStillCountsForTheLapse_C1Q(): Unit = runBlocking {
        val glass = c1Sim()
        val clock = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        val t = StartRig(glass, scope, clock = { clock.get() })
        t.heapReads = false
        collect(t)
        countCacheWrites(glass)
        t.start(full)
        writesLandOnBoth(glass, t, "stage 0")
        t.devProbe("c1", "2")
        t.onWrite = { a, p -> if (a == Arm.RIGHT && leaseOp(p) == SettingsMsg.OP_FB_ACQUIRE) null else p }
        val acquires = glass.acquiresSeen
        clock.addAndGet(80_000L)
        waitUntil(10_000, message = { "LEFT's renewals at the later time" }) { glass.acquiresSeen >= acquires + 3 }
        clock.addAndGet(5_000L)
        t.endLink("test: the link ends")
        t.onWrite = null
        clock.addAndGet(20_000L)
        t.start(full)
        assertEquals(160 * 1024, t.state.value.cacheSize, "not known fresh — LEFT's lease was renewed 25 s ago: ${said()}")
        assertEquals(160 * 1024, glass.cacheSize(Arm.LEFT), "LEFT still holds its 160 KiB cache")
        writesLandOnBoth(glass, t, "after the struck renewal")
        t.stop()
    }

    /** C1-Q, the focused read's F1 turned round: a RELEASE the phone's stack took that LEFT never applied — a stop's
     *  release, then its disconnect at once. Stage 2 by hand; the first start asks 160 and both lenses allocate it; the
     *  stop's release reaches RIGHT only; the next start, 3 s later, is no lapse, so it asks 160 — RIGHT, fresh, answers
     *  status 0, LEFT carries its 160 KiB and takes every write. (With the release route, the record made that start
     *  known fresh: 256 over LEFT's 160, LEFT refusing the top.) */
    @Test
    fun aReleaseLeftNeverAppliedDoesNotRaiseTheAsk_F1_C1Q(): Unit = runBlocking {
        val glass = c1Sim()
        val clock = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        val t = StartRig(glass, scope, clock = { clock.get() })
        t.heapReads = false
        collect(t)
        countCacheWrites(glass)
        t.devProbe("c1", "2")
        t.start(full)
        writesLandOnBoth(glass, t, "the first start")
        t.onWrite = { a, p -> if (a == Arm.LEFT && leaseOp(p) == SettingsMsg.OP_FB_RELEASE) null else p }
        t.stop()
        t.onWrite = null
        assertEquals(160 * 1024, glass.cacheSize(Arm.LEFT), "LEFT kept its cache: its release never arrived")
        assertEquals(0, glass.cacheSize(Arm.RIGHT), "RIGHT applied its release")
        clock.addAndGet(3_000L)
        t.start(full)
        assertEquals(160 * 1024, t.state.value.cacheSize, "a release is no lapse: the start asks 160: ${said()}")
        assertEquals(160 * 1024, glass.cacheSize(Arm.LEFT), "LEFT still carries its 160 KiB")
        writesLandOnBoth(glass, t, "the start after the release LEFT missed")
        t.stop()
    }

    /** C1-Q, the focused read's F2 turned round: an ACQUIRE that reached LEFT while its write was reported failed. Stage 2
     *  by hand; the first start at T0 asks 160, both allocate. At T0 + 45 s a renewal reaches LEFT and is reported
     *  failed (RIGHT's is then not written). The link ends; the next start at T0 + 110 s: LEFT's acquire was ATTEMPTED
     *  65 s before, so this is no lapse and the start asks 160 — RIGHT's lease lapsed (fresh, status 0), LEFT's runs on
     *  with its 160 KiB and takes every write. (Stamped after the write, the record read T0 on both arms: known fresh,
     *  256 over LEFT's 160.) */
    @Test
    fun anAcquireReportedFailedAfterLeftAppliedItDoesNotRaiseTheAsk_F2_C1Q(): Unit = runBlocking {
        val glass = c1Sim()
        val t0 = 1_000_000L
        val clock = java.util.concurrent.atomic.AtomicLong(t0)
        val t = LeftAcquireFailsAfterDelivery(glass, scope) { clock.get() }
        t.heapReads = false
        collect(t)
        countCacheWrites(glass)
        t.devProbe("c1", "2")
        t.start(full)
        writesLandOnBoth(glass, t, "the first start")
        clock.set(t0 + 45_000L)
        t.failLeftAcquire = true
        waitUntil(10_000, message = { "a renewal at the later time reached LEFT" }) {
            synchronized(glass) { glass.left.leaseDeadline } == t0 + 45_000L + SettingsMsg.LEASE_EXPIRY_MS }
        t.endLink("test: the link ends")
        t.failLeftAcquire = false
        t.stop()
        clock.set(t0 + 110_000L)
        t.start(full)
        assertEquals(160 * 1024, t.state.value.cacheSize, "LEFT's acquire was attempted 65 s ago: no lapse, 160: ${said()}")
        assertEquals(160 * 1024, glass.cacheSize(Arm.LEFT), "LEFT still carries its 160 KiB")
        writesLandOnBoth(glass, t, "the start after the acquire reported failed")
        t.stop()
    }

    /** C1-Q: at stage 0 (and 1) with no hold-back every ask is 160 and the last session's size is 160, so every start
     *  writes today's cache size and flag sets on both arms — a process's first start (its whole bytes are the
     *  contract-2 file: `theInstalledImageAndAStage0C1BuildWriteTodaysStart_6_2`), a renewal, a start after a release,
     *  a start after LEFT restarted alone. And a first start at stage 2 writes stage 1's: the 256 KiB size waits. */
    @Test
    fun aStage0StartSendsTodaysBytesAtEveryKindOfStart_C1Q(): Unit = runBlocking {
        val glass = c1Sim()
        val clock = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        val t = StartRig(glass, scope, clock = { clock.get() })
        t.heapReads = false
        collect(t)
        val sent = java.util.concurrent.CopyOnWriteArrayList<String>()
        t.onWrite = { a, p -> sent.add("${a.name.first()} " + p.joinToString("") { "%02x".format(it) }); p }
        suspend fun startAndRead(what: String) {
            sent.clear()
            t.start(full)
            for (arm in listOf('L', 'R')) assertEquals(listOf("5:a0", "2:4"), controls(sent.toList(), arm), "$what: written to $arm")
        }
        startAndRead("a first start")
        clock.addAndGet(20_000L)
        assertTrue(t.restartSession("test: a renewal"))
        startAndRead("a renewal")
        t.setLeaseWanted(false)
        assertTrue(t.restartSession("test: the wake"))
        startAndRead("a start after a release")
        clock.addAndGet(10_000L)
        glass.rebootForTest(Arm.LEFT, clock.get())
        t.endLink("LEFT disconnected: supervision timeout")
        clock.addAndGet(5_000L)
        startAndRead("a start after LEFT restarted alone")
        t.stop()
        val first2 = startWire(0x1ff, 2)
        for (arm in listOf('L', 'R')) assertEquals(listOf("5:a0", "2:4", "2:24"), controls(first2, arm), "a first start at stage 2: $arm")
    }

    /** C1-Q: a process's first start has no record and is not known fresh — it asks at most 160 KiB. (1) The last process
     *  left both lenses at 160 with a stored 2 and ended with no release; RIGHT restarts alone; the next process's first
     *  start asks 160 (LEFT still holds 160) and says the wait; the start after its own release asks 256. (2) The C1-P fix
     *  stage's "check and say": a process at 256 KiB ends with no release, the next one steps a stored 2 down after an
     *  uncaught error and starts 5 s later inside the lease — the session is the 160 it asks while RIGHT keeps 256. */
    @Test
    fun aProcessesFirstStartAsksNoMoreThan160_C1Q(): Unit = runBlocking {
        val clock = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        fun process(glass: GlassFirmwareSim, scope: CoroutineScope, stepDown: Boolean = false) =
            StartRig(glass, scope, clock = { clock.get() }, c1StageFile = stageFile, c1StageStepDown = stepDown).also { it.heapReads = false; collect(it) }
        // (1)
        val glass = c1Sim()
        countCacheWrites(glass)
        Files.writeString(stageFile, "0\n")
        val old = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val p1 = process(glass, old)
        p1.start(full)
        writesLandOnBoth(glass, p1, "process 1 at stage 0")
        p1.devProbe("c1", "2")
        assertEquals("2", stored(), "stage 2 stored for the next process")
        old.cancel()                                          // the process ends: no stop, no release
        clock.addAndGet(2_000L)
        glass.rebootForTest(Arm.RIGHT, clock.get())           // RIGHT restarts alone; LEFT keeps its lease and its 160 KiB
        clock.addAndGet(5_000L)
        val p2 = process(glass, scope)
        p2.start(full)
        assertEquals(160 * 1024, p2.state.value.cacheSize, "a process's first start with a stored 2 asks 160: ${said()}")
        assertEquals(0x24, p2.state.value.flagsInForce, "stage 2's flags: ZWRITE and DRAW2")
        writesLandOnBoth(glass, p2, "process 2's first start")
        assertEquals(1, waitsSaid(256), said())
        assertTrue(p2.restartSession("test: the session ends"))
        clock.addAndGet(PAST_THE_LAPSE)
        p2.start(full)
        assertEquals(256 * 1024, p2.state.value.cacheSize, "the start after a lapse asks 256: ${said()}")
        writesLandOnBoth(glass, p2, "process 2 after a lapse")
        p2.stop()
        // (2)
        notes.clear()
        val glass2 = c1Sim()
        countCacheWrites(glass2)
        Files.writeString(stageFile, "2\n")
        val old2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val q1 = process(glass2, old2)
        startKnownFresh(q1) { clock.addAndGet(PAST_THE_LAPSE) }
        assertEquals(256 * 1024, q1.state.value.cacheSize, "the last process ran at 256 KiB: ${said()}")
        writesLandOnBoth(glass2, q1, "the last process at 256 KiB")
        old2.cancel()
        clock.addAndGet(5_000L)
        val q2 = process(glass2, scope, stepDown = true)
        q2.start(full)
        assertEquals(0x4, q2.state.value.flagsInForce, "stepped down to stage 0: ${said()}")
        assertEquals(160 * 1024, q2.state.value.cacheSize, "the session is the 160 KiB it asked: ${said()}")
        assertEquals(256 * 1024, glass2.cacheSize(Arm.RIGHT), "RIGHT keeps its 256 KiB until its next release point")
        writesLandOnBoth(glass2, q2, "the stepped-down start inside the lease")
        q2.stop()
    }
}
