package wm.damage.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
import wm.damage.core.geom.Layout
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.motion.MainPan
import wm.damage.core.motion.Motion
import wm.damage.core.motion.TestPrograms
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.Pb

/**
 * Build C1, package D3b (spec §3 D3b, decision A.15): the feel session's test programs and the probe that plays one.
 * Context for the reader: Adam's own glasses on his own firmware fork; nothing here contacts a device — the stand-in is
 * the simulator behind `SimTransport`. Each program lays out in the top 64 KiB of the session's cache with its first
 * read range before its captures; `probe motion=NAME` sends its records and its PLAY only while ENGINE is in force, and
 * nothing for a name that is not a test program.
 */
class TestProgramsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @AfterTest fun clean() { scope.cancel() }

    private val full = Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480)))
    private val notes = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()

    /** A, the layout: every record and capture of every program inside the top [CfwModes.STAGE_RESERVE] bytes of a
     *  160 and a 256 KiB cache, the first read range (records, tables, program) wholly before the capture range. */
    @Test
    fun everyProgramLaysOutInTheTopOfTheCacheWithItsCapturesAfterItsRecords_A15() {
        for (size in listOf(CfwModes.CACHE2_MAX, CfwModes.CACHE2_MAX_RIDERS)) for (name in TestPrograms.NAMES) {
            val tp = TestPrograms.build(name, size)!!
            val first = tp.program.firstRange(tp.params)
            val lo = tp.writes.minOf { it.first * 4 }
            val hi = tp.writes.maxOf { (o, b) -> o * 4 + b.size }
            assertTrue(lo >= size - CfwModes.STAGE_RESERVE, "$name at ${size / 1024} KiB: the first write at $lo is below the reserve")
            assertTrue(tp.end <= size, "$name at ${size / 1024} KiB: ends at ${tp.end}")
            assertEquals(lo until hi, first, "$name: what is written is the first read range, tight")
            if (tp.program.captureBytes > 0) assertTrue(tp.cap4 * 4 >= hi, "$name: the captures at ${tp.cap4 * 4} start after the records end at $hi")
            // the messages: each record's bytes once, in mode-19 messages, then the PLAY
            val msgs = tp.messages()
            assertEquals(CfwModes.MOTION_MODE, msgs.last()[0].toInt(), "$name: the PLAY goes last")
            assertTrue(msgs.dropLast(1).all { it[0].toInt() == 19 }, "$name: records go as mode-19 writes")
        }
        assertNull(TestPrograms.build("bogus", CfwModes.CACHE2_MAX), "a name that is not a test program builds nothing")
    }

    private fun collect(t: SimTransport) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { t.events.collect { if (it is TransportEvent.Note) notes.add(it.kind to it.detail) } }
    }

    /** A session on a C1 build (0x1ff) at ladder stage [stage]; [seen] takes the mode-19 and mode-26 images the
     *  simulator receives on RIGHT. */
    private suspend fun session(stage: Int, seen: MutableList<ByteArray>): Pair<GlassFirmwareSim, SimTransport> {
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x1ff }
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        collect(t)
        if (stage != 0) t.devProbe("c1", "$stage")
        t.start(full)
        sim.imageObserver = { arm, img -> val m = img[0].toInt() and 0x7F; if (arm == Arm.RIGHT && (m == 19 || m == CfwModes.MOTION_MODE)) seen += img }
        return sim to t
    }

    /** The probe plays the named program with ENGINE in force — its records in the top of the cache, then its PLAY, and
     *  the instance opens over the program's areas — and sends nothing at stage 0 (ENGINE not armed) or for a name
     *  that is not a test program. */
    @Test
    fun theProbePlaysATestProgramOnlyWithEngineInForce_A15(): Unit = runBlocking {
        val off = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        val (_, t0) = session(0, off)
        t0.devProbe("motion", "popover")
        // one evaluation decides: the refusal note, or an image that should not have gone
        waitUntil(10_000, message = { "the refusal note: $notes" }) { off.isNotEmpty() || notes.any { it.first == "motion" && "ENGINE is not in force" in it.second } }
        delay(200)
        assertEquals(0, off.size, "stage 0: no record and no PLAY reached the glasses")
        assertTrue(notes.any { it.first == "motion" && "ENGINE is not in force" in it.second }, "the note says why: $notes")
        t0.stop()

        val seen = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        val (sim, t) = session(3, seen)
        assertEquals(0x2c, t.state.value.flagsInForce, "stage 3: ENGINE in force")
        t.devProbe("motion", "bogus")
        delay(200)
        assertEquals(0, seen.size, "a name that is not a test program sends nothing")
        t.devProbe("motion", "popover")
        val tp = TestPrograms.build("popover", t.state.value.cacheSize)!!
        waitUntil(10_000, message = { "the PLAY landed: ${seen.size} images; $notes" }) { sim.motionWindows(Arm.RIGHT).any { it.slot == 0 } }
        // the PLAY carries this play's own tag (build C1's ruling B-N11; its pin is eachPlayOfTheProbeHasItsOwnTag_BN11)
        val tag = seen.last().let { (it[6].toInt() and 0xFF) or ((it[7].toInt() and 0xFF) shl 8) }
        assertEquals(tp.withTag(tag).messages().map { it.toList() }, seen.map { it.toList() }, "the program's records, then its PLAY, as built")
        val w = sim.motionWindows(Arm.RIGHT).single()
        assertEquals(tp.program.tracks.map { it.areaR }, w.areas, "the instance opened over the program's areas")
        assertTrue(notes.any { it.first == "motion" && "test program sent (popover" in it.second }, "the probe says what it sent: $notes")
        t.stop()
    }

    /** Build C1's ruling B-N11: each play of the probe has its own tag, so two plays of one program keep two END records
     *  in the mirror ((slot, tag, ordinal) each) and a late notify of the first is never compared with the second's. */
    @Test
    fun eachPlayOfTheProbeHasItsOwnTag_BN11(): Unit = runBlocking {
        val seen = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        val (sim, t) = session(3, seen)
        fun plays() = seen.filter { it[0].toInt() == CfwModes.MOTION_MODE && it[1].toInt() == 0 }
        for (n in 1..2) {
            t.devProbe("motion", "list")
            waitUntil(10_000, message = { "play $n landed: ${plays().size} PLAYs; $notes" }) { plays().size >= n }
        }
        val tags = plays().map { (it[6].toInt() and 0xFF) or ((it[7].toInt() and 0xFF) shl 8) }
        assertEquals(2, tags.distinct().size, "two plays of `list`, two tags: $tags")
        for (tag in tags) assertTrue(sim.motionEndCrc(0, tag, 0) != null, "the mirror's END record for tag 0x%04x".format(tag))
        t.stop()
    }

    /** Build C1's ruling R1.16, the picture: END painted onto each lens's picture at the PLAY ([TestPrograms.TestProgram.paintEnd])
     *  is, pixel for pixel, the shadow the simulator's engine writes when it takes the program's PLAY over that picture
     *  — every program, both lenses (`recede` is per lens). The picture is a pattern of every level, not the program's
     *  own stage, so a backdrop or a capture that came out wrong cannot hide behind content that already matches. */
    @Test
    fun paintEndIsTheShadowThePlayWrites_R1_16() {
        val before = Gray8(640, 480).also { g -> for (i in g.pix.indices) g.pix[i] = ((((i % 640) / 3 + (i / 640) / 5) % 16) * 17).toByte() }
        for (name in TestPrograms.NAMES) {
            val tp = TestPrograms.build(name, CfwModes.CACHE2_MAX)!!
            val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x1ff; it.clockIsOsTick = true }
            for (arm in Arm.entries) {
                sim.conformanceLease(arm, true, 1000)
                sim.conformanceControl(arm, DamageMsg.OP_CACHE_SIZE, CfwModes.CACHE2_MAX / 1024, 1000)
                sim.conformanceControl(arm, DamageMsg.OP_FLAGS_SET, DamageMsg.FLAG_DRAW2 or DamageMsg.FLAG_ENGINE, 1000)
                assertTrue(sim.conformanceMessage(arm, CfwModes.keyframe(Zl.encodeCfw(Pack.rect(before, Rect(0, 0, 640, 480)))), 1000))
                for (m in tp.messages()) assertTrue(sim.conformanceMessage(arm, m, 2000), "$name $arm: ${sim.refusalRecord(arm)}")
            }
            for (arm in Arm.entries) {
                val left = arm == Arm.LEFT
                val pic = Gray8(640, 480).also { before.pix.copyInto(it.pix) }
                tp.paintEnd(left, pic)
                val shadow = if (left) sim.left.shadow else sim.right.shadow
                var differ = 0
                for (y in 0 until 480) for (x in 0 until 640) {
                    val b = shadow[y * 320 + x / 2].toInt() and 0xFF
                    if (Pack.level(pic[x, y]) != (if (x % 2 == 0) b shr 4 else b and 0x0F)) differ++
                }
                assertEquals(0, differ, "$name $arm: px where END painted on the picture differs from the shadow the PLAY wrote")
            }
        }
    }

    /** Build C1's ruling R1.16, through the shell on the simulator: the probe's PLAY acknowledged, END goes into the
     *  shell's belief of both lenses, so when `list`, `flip` and `mainpan2` (each ends on new content) have run to their last step
     *  the agreement check finds END on both sides — no divergence and no keyframe. */
    @Test
    fun theProbesPlayPutsEndIntoBeliefSoListAndFlipEndWithNoDivergence_R1_16(): Unit = runBlocking {
        val tmp = java.nio.file.Files.createTempDirectory("damage-motion-end")
        try {
            val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x1ff }
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            collect(t)
            t.devProbe("c1", "3")
            var clock = wm.damage.core.shell.Shell.LocalClock(12, 0, "12:00", "PM")
            val shell = wm.damage.core.shell.Shell(FakeText(), t, wm.damage.core.shell.Persistence(tmp.resolve("s.json")), null, scope) { clock }
            shell.start()
            waitUntil(10_000, 10, { "shell did not settle" }) { shell.isQuiescent() }
            assertEquals(0x2c, t.state.value.flagsInForce, "ENGINE in force under the shell")
            val keyframes = java.util.concurrent.atomic.AtomicInteger()
            val images = java.util.concurrent.atomic.AtomicInteger()
            sim.imageObserver = { arm, img -> if (arm == Arm.RIGHT) { images.incrementAndGet(); if ((img[0].toInt() and 0x7F) == 6) keyframes.incrementAndGet() } }
            for ((i, name) in listOf("list", "flip", "mainpan2").withIndex()) {
                t.devProbe("motion", name)
                waitUntil(10_000, message = { "$name played: $notes" }) { notes.any { it.first == "motion" && it.second.startsWith(TestPrograms.PLAYED + name) } }
                waitUntil(10_000, 10, { "shell did not settle" }) { shell.isQuiescent() }
                // the instance runs to its last step on the model's engine (one wake, well inside the lease); RIGHT's
                // notify and the nominal end close the area's unknown window
                val later = System.currentTimeMillis() + 5_000
                sim.motionClock = { later }
                for (arm in Arm.entries) sim.conformanceWake(arm, later)
                waitUntil(10_000, message = { "$name ran to its end: ${sim.motionWindows(Arm.RIGHT)}" }) {
                    sim.motionWindows(Arm.RIGHT).none { it.drawing } && sim.unknownAreas(Arm.RIGHT).isEmpty() && sim.unknownAreas(Arm.LEFT).isEmpty() }
                // the clock's minute: a small flush elsewhere, after which the shell compares belief with the lens
                clock = wm.damage.core.shell.Shell.LocalClock(12, i + 1, "12:0${i + 1}", "PM")
                val before = images.get()
                shell.postMinuteTick()
                waitUntil(10_000, 10, { "the minute's flush" }) { images.get() > before && shell.isQuiescent() }
                assertEquals(0, shell.divergencesReported, "$name: belief holds END where the lens does: ${shell.lastDivergence}")
            }
            assertEquals(0, keyframes.get(), "no keyframe followed either program")
            shell.stop()
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // ------------------------------------------------------------------------------- build C2, stage DM0: Main's pan
    private val mainPans = TestPrograms.NAMES.filter { it.startsWith("mainpan") }

    /** One lens's picture of Main's nominal [g] under the shell's planes at Global `Depth` 8, as `Compositor.renderTruth`
     *  makes it: the content vacated, then the list at disparity 8 and the band (the content's full width) at 4, the
     *  left lens at x − d, the right at x + d. */
    private fun onLens(g: Gray8, l: Layout, left: Boolean): Gray8 {
        val out = Gray8(640, 480).also { g.pix.copyInto(it.pix) }
        out.fillRect(l.content, 0)
        for (y in l.content.y until l.content.bottom) {
            val d = if (y in l.lens.y until l.lens.bottom) 4 else 8
            for (x in l.content.x until l.content.right) out[x + (if (left) -d else d), y] = g[x, y]
        }
        return out
    }

    /** Every track's state before its first step (`FIRMWARE.md` §5.4: its backdrop, then its first segment at j = 0)
     *  painted onto [pic] — what the lens would show if step 1 drew nothing new. */
    private fun paintStart(tp: TestPrograms.TestProgram, left: Boolean, pic: Gray8) {
        for (t in tp.program.tracks) {
            val area = t.area(left)
            (t.backdrop as? Motion.Backdrop.Level)?.let { pic.fillRect(area, it.code * 17) }
            val s = t.segments.first { it.verb != Motion.WAIT }
            val img = tp.images.getValue(s.src)
            val x0 = if (t.perLens && !left) s.xR else s.xL
            for (yy in maxOf(area.y, s.y) until minOf(area.bottom, s.y + img.h))
                for (xx in maxOf(area.x, x0) until minOf(area.right, x0 + img.w))
                    pic[xx, yy] = (img.levels[(yy - s.y) * img.w + (xx - x0)] * s.lvl0 / 15) * 17
        }
    }

    private fun differ(a: Gray8, b: Gray8, areas: List<Rect>): Int {
        var n = 0
        for (r in areas) for (y in r.y until r.bottom) for (x in r.x until r.right) if (Pack.level(a[x, y]) != Pack.level(b[x, y])) n++
        return n
    }

    /** Stage DM0: at each of Main's four heights, each `mainpan` program, played on the simulator over each lens's own
     *  picture of Main (the cursor on row 3, the planes' shifts), starts from Main as the lens shows it — no jump at step
     *  1 — and ends, every step drawn and none folded, on Main one notch on (row 4 at the cursor) in every area it
     *  draws; the shadow its PLAY wrote is END painted on that picture ([TestPrograms.TestProgram.paintEnd], what the
     *  shell puts into belief), and each lens shows it after the last step. `mainpan3` is refused at 288, where Main
     *  shows two rows each side. */
    @Test
    fun mainPanStartsOnMainAndEndsOnMainOneNotchOnAtEveryHeight_DM0() {
        val c = MainPan.CURSOR
        for (h in listOf(288, 352, 416, 480)) {
            val m = MainPan.at(h)
            for (name in mainPans) {
                val tp = try { TestPrograms.build(name, CfwModes.CACHE2_MAX, h)!! } catch (e: LintError) {
                    assertTrue(h == 288 && name == "mainpan3", "$name at $h refused: ${e.message}"); continue
                }
                assertEquals(MainPan.T, tp.program.T, "$name at $h: T")
                val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x1ff; it.clockIsOsTick = true }
                val before = Arm.entries.associateWith { onLens(m.picture(c), m.layout, it == Arm.LEFT) }
                for (arm in Arm.entries) {
                    sim.conformanceLease(arm, true, 1000)
                    sim.conformanceControl(arm, DamageMsg.OP_CACHE_SIZE, CfwModes.CACHE2_MAX / 1024, 1000)
                    sim.conformanceControl(arm, DamageMsg.OP_FLAGS_SET, DamageMsg.FLAG_DRAW2 or DamageMsg.FLAG_ENGINE, 1000)
                    assertTrue(sim.conformanceMessage(arm, CfwModes.keyframe(Zl.encodeCfw(Pack.rect(before.getValue(arm), Rect(0, 0, 640, 480)))), 1000))
                    for (msg in tp.messages()) assertTrue(sim.conformanceMessage(arm, msg, 2000), "$name at $h, $arm: ${sim.refusalRecord(arm)}")
                }
                for (k in 2..MainPan.T) for (arm in Arm.entries) sim.conformanceWake(arm, 2000L + (k - 1) * tp.period)
                val notifies = sim.conformanceNotifies(Arm.RIGHT).map { DamageMsg.parseMotion(Pb.l(DamageMsg.MOTION_FIELD, it))!! }
                assertEquals(listOf(MainPan.T.toLong() to 0L), notifies.filter { it.outcome == DamageMsg.OUTCOME_DREW }.map { it.drawn to it.folded },
                    "$name at $h: RIGHT's notify, every step drawn, none folded: ${notifies.map { it.describe() }}")
                for (arm in Arm.entries) {
                    val left = arm == Arm.LEFT
                    val areas = tp.program.tracks.map { it.area(left) }
                    val pic = before.getValue(arm)
                    val start = pic.copy().also { paintStart(tp, left, it) }
                    assertEquals(0, differ(start, pic, areas), "$name at $h, $arm: px where the start state is not Main as the lens shows it")
                    val end = pic.copy().also { tp.paintEnd(left, it) }
                    assertEquals(0, differ(end, onLens(m.picture(c + 1), m.layout, left), areas), "$name at $h, $arm: px where END is not Main one notch on")
                    val shadow = if (left) sim.left.shadow else sim.right.shadow
                    var off = 0
                    for (y in 0 until 480) for (x in 0 until 640) {
                        val b = shadow[y * 320 + x / 2].toInt() and 0xFF
                        if (Pack.level(end[x, y]) != (if (x % 2 == 0) b shr 4 else b and 0x0F)) off++
                    }
                    assertEquals(0, off, "$name at $h, $arm: px where the shadow the PLAY wrote is not END (the belief)")
                    assertEquals(sim.shadowCrc32(arm), sim.panelCrc32(arm), "$name at $h, $arm: the lens shows END after the last step")
                }
            }
        }
    }

    /** Stage DM0: the area a step repaints grows with k — each program's tracks lie side by side (none meets another),
     *  the band's between the two strips', and `mainpanall` at the staged height covers every row Main shows. */
    @Test
    fun mainPanAreasGrowWithKAndAllCoversEveryRowMainShows_DM0() {
        val l = MainPan.at(MainPan.HEIGHT).layout
        val areas = mainPans.map { n -> TestPrograms.build(n, CfwModes.CACHE2_MAX)!!.program.tracks.sumOf { it.areaL.w * it.areaL.h } }
        assertEquals(areas.sorted(), areas, "area per step by program: $areas")
        val all = TestPrograms.build("mainpanall", CfwModes.CACHE2_MAX)!!.program.tracks
        assertEquals(listOf(l.lens.y - l.rowsAbove * l.rowH, l.lens.bottom, l.lens.y), all.map { it.areaL.y }, "the strips' and the band's tops")
        assertEquals(listOf(l.rowsAbove * l.rowH, l.rowsBelow * l.rowH, l.lens.h), all.map { it.areaL.h }, "the strips' and the band's heights")
        for (n in mainPans) {
            val t = TestPrograms.build(n, CfwModes.CACHE2_MAX)!!.program.tracks
            for (left in listOf(true, false)) for (i in t.indices) for (j in t.indices)
                if (i < j) assertNull(t[i].area(left).intersect(t[j].area(left)), "$n: tracks $i and $j meet")
        }
    }
}
