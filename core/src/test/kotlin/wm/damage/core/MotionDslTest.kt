package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.motion.Motion
import wm.damage.core.motion.Motion.Backdrop
import wm.damage.core.motion.Motion.Ease
import wm.damage.core.motion.Motion.Edge
import wm.damage.core.motion.Motion.Source
import wm.damage.core.motion.MotionProgram
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.sim.MotionSim
import wm.damage.core.transport.Arm
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.TextureCache

/**
 * Build C1, package D3a: the Motion DSL (spec §6.3) against two other readings of `FIRMWARE.md` §5.4 — the vector
 * inputs `firmware/make_vectors.py` wrote (the programs byte for byte, spec §6.2's last pin) and the simulator's
 * END (`MotionSim`, written by another stage): END as §4 ops applied to one model must leave the shadow the PLAY
 * leaves in another. And the builder's loud refusals (§5.5's checks it can decide offline).
 */
class MotionDslTest {

    private fun vectorDir(): Path {
        var d: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (d != null) {
            val v = d.resolve("firmware/vectors")
            if (Files.isDirectory(v)) return v
            d = d.parent
        }
        fail("firmware/vectors not found above ${System.getProperty("user.dir")}")
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    private fun vector(name: String): JsonObject = Json.parseToJsonElement(Files.readString(vectorDir().resolve("$name.json"))).jsonObject
    private fun ops(v: JsonObject, step: Int) = v["steps"]!!.jsonArray[step].jsonObject["ops"]!!.jsonArray.map { it.jsonObject }
    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    /** The cache a vector's top-level mode-19 writes leave (64 KiB, as the vectors' sessions have it). */
    private fun cacheOf(v: JsonObject, steps: IntRange = 0..0): ByteArray {
        val cache = ByteArray(64 * 1024)
        for (s in steps) for (op in ops(v, s)) {
            val m = op["msg"]?.jsonPrimitive?.content?.let(::hex) ?: continue
            if (m[0].toInt() != 19) continue
            var i = 1
            while (i < m.size) { val off = u16(m, i) * 4; val n = u16(m, i + 2); m.copyInto(cache, off, i + 4, i + 4 + n); i += 4 + n }
        }
        return cache
    }

    /** Every PLAY a vector sends, in order: (prog4, cap4). */
    private fun plays(v: JsonObject): List<Pair<Int, Int>> = v["steps"]!!.jsonArray.flatMap { st ->
        st.jsonObject["ops"]!!.jsonArray.mapNotNull { o -> o.jsonObject["msg"]?.jsonPrimitive?.content?.let(::hex) }
            .filter { it.size == 18 && it[0].toInt() == 26 && it[1].toInt() == 0 }.map { u16(it, 4) to u16(it, 8) }
    }

    /** The program record at [prog4] as the vector's cache holds it. */
    private fun recordAt(cache: ByteArray, prog4: Int) = cache.copyOfRange(prog4 * 4, prog4 * 4 + u16(cache, prog4 * 4 + 2))

    /** The v2 image record at [off4] and the off4 of the next 4-byte-aligned record after it. */
    private fun image(cache: ByteArray, off4: Int): Pair<Source.Record, Int> {
        val (_, end) = MotionSim.record(cache, off4 * 4) ?: fail("no v2 record at off4 $off4")
        return Source.record(off4, cache.copyOfRange(off4 * 4, end)) to (end + 3) / 4
    }

    private fun r(x: Int, y: Int, w: Int, h: Int) = Rect(x, y, w, h)

    private fun playProgram(pic: Source, at: Int) = Motion.program(at) {
        track(r(100, 100, 240, 60), Backdrop.Level(2)) { fill(5, 8) }
        track(r(110, 110, 200, 40), Backdrop.None) { draw(pic, x = 110, y = 115, dx = 150, n = 8) }
    }

    private fun drawProgram(pic: Source, at: Int) = Motion.program(at) {
        track(r(100, 200, 160, 60), r(116, 200, 160, 60), Backdrop.Self) {
            draw(Source.self, x = 100, y = 200, dx = 40, dy = 12, xR = 116, dxR = 56, lvl0 = 15, lvl1 = 6, opt = 0x10, ease = Ease.easeOut, n = 6) }
        track(r(320, 200, 200, 80), Backdrop.Level(1)) { draw(pic, x = 300, y = 210, dx = 210, lvl0 = 3, lvl1 = 15, opt = 0x20, n = 6) }
        track(r(100, 300, 200, 50), r(108, 300, 200, 50), Backdrop.Level(5)) {
            draw(pic, x = 100, y = 310, dx = 100, dy = -5, xR = 108, dxR = 124, opt = 0x30, n = 4) }
        track(r(401, 321, 33, 11), Backdrop.Level(0)) { draw(Source.self, x = 401, y = 321, dx = -6, dy = 3, n = 6) }
        track(r(10, 420, 21, 9), r(14, 420, 21, 9), Backdrop.Self) { draw(Source.self, x = 10, y = 420, dx = 4, xR = 14, dxR = 4, n = 3) }
    }

    private fun revealPrograms(ra: Source, col1: Source, row1: Source, at1: Int, at3: Int): Pair<MotionProgram, MotionProgram> {
        fun g(i: Int, j: Int) = r(20 + 100 * i, 20 + 100 * j, 80, 60)
        val r1 = Motion.program(at1) {
            track(g(0, 0), Backdrop.Level(2)) { reveal(ra, Edge.TOP, n = 5, x = 40, y = 35) }
            track(g(1, 0), Backdrop.Level(4)) { reveal(Source.self, Edge.BOTTOM, n = 5, x = 120, y = 20) }
            track(g(2, 0), Backdrop.Level(0)) { reveal(Source.level(11, 50, 40), Edge.LEFT, n = 5, x = 230, y = 30) }
            track(g(3, 0), Backdrop.Level(6)) { reveal(ra, Edge.RIGHT, n = 5, x = 340, y = 35) }
            track(g(0, 1), Backdrop.Level(3)) { reveal(Source.self, Edge.TOP, slide = true, n = 5, x = 20, y = 120) }
            track(g(1, 1), Backdrop.Level(1)) { reveal(Source.level(13, 60, 40), Edge.BOTTOM, slide = true, n = 5, x = 130, y = 130) }
            track(g(2, 1), Backdrop.Level(5)) { reveal(ra, Edge.LEFT, slide = true, n = 5, x = 240, y = 135) }
            track(g(3, 1), Backdrop.Level(7)) { reveal(Source.self, Edge.RIGHT, slide = true, n = 5, x = 320, y = 120) }
        }
        val r3 = Motion.program(at3) {
            track(r(20, 220, 80, 20), Backdrop.Level(2)) { reveal(Source.level(15, 40, 1), Edge.TOP, n = 4, x = 30, y = 225) }
            track(r(120, 220, 80, 30), Backdrop.Level(3)) { reveal(col1, Edge.LEFT, slide = true, n = 4, x = 150, y = 225) }
            track(r(220, 220, 80, 30), Backdrop.Level(4)) { reveal(Source.level(9, 1, 20), Edge.RIGHT, n = 3, x = 250, y = 225) }
            track(r(320, 220, 80, 30), Backdrop.Level(1)) { reveal(row1, Edge.BOTTOM, slide = true, n = 4, x = 340, y = 230) }
        }
        return r1 to r3
    }

    private fun easeProgram(bar: Source, at: Int, n: Int, ease: Ease, ramp: Boolean) = Motion.program(at) {
        val lv = if (ramp) 2 else 15
        track(r(0, 40, 640, 24), Backdrop.Level(1)) { draw(bar, x = 4, y = 42, dx = 600, ease = ease, n = n) }
        track(r(0, 70, 640, 24), Backdrop.Level(1)) { draw(bar, x = 610, y = 72, dx = -597, lvl0 = lv, lvl1 = 15, ease = ease, n = n) }
        track(r(0, 100, 640, 60), Backdrop.Level(1)) { draw(bar, x = 300, y = 100, dx = 7, dy = 37, ease = ease, n = n) }
    }

    private fun assertSame(name: String, cache: ByteArray, prog4: Int, p: MotionProgram) =
        assertContentEquals(recordAt(cache, prog4), p.bytes, "$name: the program at off4 $prog4, DSL against make_vectors.py")

    /** Spec §6.2's last pin: the two encoders of §5.4 (this DSL; `make_vectors.py`) write the v3 programs alike. */
    @Test
    fun theDslWritesTheV3ProgramsByteForByteAsTheVectorInputsDo_6_2() {
        var compared = 0
        vector("v3-play").let { v ->
            val cache = cacheOf(v)
            val (pp, _) = plays(v).first()
            assertSame("v3-play", cache, pp, playProgram(image(cache, 16).first, pp)); compared++
        }
        vector("v3-draw").let { v ->
            val cache = cacheOf(v)
            val (pd, _) = plays(v).first()
            val p = drawProgram(image(cache, 16).first, pd)
            assertSame("v3-draw", cache, pd, p); compared++
            assertEquals(5087, p.captureBytes, "decision A.5: three `self` captures, 4-byte aligned (make_vectors' cap_total)")
            assertEquals(listOf(4800, 0, 0, 187, 99), p.captureSizes.toList(), "only tracks that use `self` capture")
        }
        vector("v3-reveal").let { v ->
            val cache = cacheOf(v)
            val (ra, n1) = image(cache, 16)
            val (col1, n2) = image(cache, n1)
            val (row1, _) = image(cache, n2)
            val (p1, p3) = plays(v).let { it[0].first to it[1].first }
            val (r1, r3) = revealPrograms(ra, col1, row1, p1, p3)
            assertSame("v3-reveal r1", cache, p1, r1); assertSame("v3-reveal r3", cache, p3, r3); compared += 2
        }
        vector("v3-ease").let { v ->
            val cache = cacheOf(v)
            val (bar, overAt) = image(cache, 16)
            val underAt = overAt + (2 + 2 * 8 + 3) / 4
            val over = Ease.table(overAt, intArrayOf(800, 1800, 3000, 4300, 4700, 4400, 4000, 4096))
            val under = Ease.table(underAt, intArrayOf(-400, -200, 600, 2000, 3600, 4500, 4200, 4096))
            assertContentEquals((over as Ease.Table).record, cache.copyOfRange(overAt * 4, overAt * 4 + 18), "the overshoot table record")
            assertContentEquals((under as Ease.Table).record, cache.copyOfRange(underAt * 4, underAt * 4 + 18), "the undershoot table record")
            val pl = plays(v)
            var i = 0
            for (n in listOf(1, 2, 7, 255)) for (e in listOf(Ease.linear, Ease.easeOut)) {
                val at = pl[i++].first
                assertSame("v3-ease n=$n ${e.code}", cache, at, easeProgram(bar, at, n, e, ramp = true)); compared++
            }
            for (tb in listOf(over, under)) { val at = pl[i++].first; assertSame("v3-ease table", cache, at, easeProgram(bar, at, 8, tb, ramp = false)); compared++ }
        }
        assertEquals(14, compared)
        println("MotionDslTest: $compared programs of the v3 vectors encoded byte for byte")
    }

    /** §5.4's closing paragraph: END as §4 ops. One model plays the program (END into its shadow, `MotionSim`); another
     *  takes the DSL's END ops as one batch (a capture drawn as a v2 record cut from that model's own panel); the two
     *  shadows must be one, per lens. */
    @Test
    fun endAsFourOpsLeavesTheShadowThePlayLeaves_5_4() {
        fun check(name: String, prog: MotionProgram, p: IntArray = IntArray(4), cap4: Int = 0) {
            val v = vector(name)
            for (arm in Arm.entries) {
                fun sim() = GlassFirmwareSim().apply { damageContract = 2; damageFeatures = 0x1ff; clockIsOsTick = true }.also { s ->
                    for (op in ops(v, 0)) when {
                        "lease" in op -> s.conformanceLease(arm, true, 1000)
                        "flags" in op -> s.conformanceControl(arm, DamageMsg.OP_FLAGS_SET, op["flags"]!!.jsonPrimitive.int, 1000)
                        "msg" in op -> assertTrue(s.conformanceMessage(arm, hex(op["msg"]!!.jsonPrimitive.content), 1000), "$name: step 0 accepted")
                    }
                }
                val a = sim()
                assertTrue(a.conformanceMessage(arm, CfwModes.motionPlay(0, Motion.PERIOD_30HZ, prog.at, 1, cap4, p), 2000), "$name: the PLAY accepted")
                val b = sim()
                val panel = b.snapshot(arm)
                val subs = ArrayList<ByteArray>()
                var scratch4 = 56 * 1024 / 4
                for (op in Motion.end(prog, p)) when (op) {
                    is Motion.EndOp.Sub -> subs += op.bytes
                    is Motion.EndOp.Capture -> {
                        val area = if (arm == Arm.LEFT) op.areaL else op.areaR
                        val lv = ByteArray(area.w * area.h) { i ->
                            val x = area.x + i % area.w; val y = area.y + i / area.w
                            val byte = panel[y * 320 + x / 2].toInt() and 0xFF
                            (if (x and 1 == 0) byte shr 4 else byte and 0x0F).toByte()
                        }
                        val rec = TextureCache.Image2(area.w, area.h, lv).encode()
                        assertTrue(b.conformanceMessage(arm, CfwModes.cacheUpdate2(listOf(CfwModes.CacheWrite2(scratch4, rec)), 64 * 1024), 2000))
                        subs += CfwModes.drawImage2(scratch4, if (arm == Arm.LEFT) op.xL else op.xR, op.y, op.options ?: 15, area.w, area.h)
                        scratch4 += (rec.size + 3) / 4
                    }
                }
                assertTrue(b.conformanceMessage(arm, CfwModes.batch(subs), 2000), "$name: END's batch accepted")
                assertEquals(a.shadowCrc32(arm), b.shadowCrc32(arm), "$name $arm: END as §4 ops against the model's END")
            }
        }
        vector("v3-play").let { v -> val c = cacheOf(v); val (pp, _) = plays(v).first(); check("v3-play", playProgram(image(c, 16).first, pp)) }
        vector("v3-draw").let { v -> val c = cacheOf(v); val (pd, cap) = plays(v).first(); check("v3-draw", drawProgram(image(c, 16).first, pd), cap4 = cap) }
        vector("v3-reveal").let { v ->
            val c = cacheOf(v)
            val (ra, n1) = image(c, 16); val (col1, n2) = image(c, n1); val (row1, _) = image(c, n2)
            val pl = plays(v)
            val (r1, r3) = revealPrograms(ra, col1, row1, pl[0].first, pl[1].first)
            check("v3-reveal", r1, cap4 = pl[0].second); check("v3-reveal", r3)
        }
    }

    /** §5.5's checks the builder can decide offline: each is a loud refusal, never a message the firmware refuses. */
    @Test
    fun theBuilderRefusesLoudlyWhatTheFirmwareWouldRefuse_5_5() {
        val rec = Source.Record(16, 40, 30, 600)
        val a = r(100, 100, 100, 40)
        fun refused(what: String, block: () -> Unit) { assertFailsWith<LintError>(what) { block() } }
        refused("nine tracks") { Motion.program(400) { repeat(9) { track(a, Backdrop.Level(1)) { fill(2, 1) } } } }
        refused("nine segments") { Motion.program(400) { track(a, Backdrop.Level(1)) { repeat(9) { wait(1) } } } }
        refused("no segment") { Motion.program(400) { track(a, Backdrop.Level(1)) {} } }
        refused("0 steps") { Motion.program(400) { track(a, Backdrop.Level(1)) { fill(2, 0) } } }
        refused("an area past the panel") { Motion.program(400) { track(r(600, 0, 41, 10), Backdrop.Level(1)) { fill(2, 1) } } }
        refused("a pair of two sizes") { Motion.program(400) { track(a, r(104, 100, 100, 41), Backdrop.Level(1)) { fill(2, 1) } } }
        refused("a pair of two row ranges") { Motion.program(400) { track(a, r(104, 102, 100, 40), Backdrop.Level(1)) { fill(2, 1) } } }
        refused("a none backdrop first") { Motion.program(400) { track(a, Backdrop.None) { fill(2, 1) } } }
        refused("a none backdrop outside the earlier area") { Motion.program(400) {
            track(a, Backdrop.Level(1)) { fill(2, 1) }; track(r(150, 100, 100, 40), Backdrop.None) { fill(3, 1) } } }
        refused("T past 255") { Motion.program(400) { track(a, Backdrop.Level(1)) { wait(200); fill(2, 56) } } }
        refused("a level 16") { Motion.program(400) { track(a, Backdrop.Level(1)) { fill(16, 1) } } }
        refused("a ramp an overshoot table carries past 15") { Motion.program(400) {
            track(a, Backdrop.Level(1)) { draw(rec, x = 100, y = 100, lvl0 = 2, lvl1 = 15, ease = Ease.table(300, intArrayOf(2000, 5000, 4096)), n = 3) } } }
        refused("a table of n ≠ steps") { Motion.program(400) {
            track(a, Backdrop.Level(1)) { draw(rec, x = 100, y = 100, ease = Ease.table(300, intArrayOf(2000, 4096)), n = 3) } } }
        refused("E[n] ≠ 4096") { Ease.table(300, intArrayOf(2000, 4000)) }
        refused("a REVEAL extent past F") { Motion.program(400) {
            track(a, Backdrop.Level(1)) { reveal(rec, Edge.TOP, ease = Ease.table(300, intArrayOf(5000, 4096)), n = 2, x = 100, y = 100) } } }
        refused("xR on a one-rect track") { Motion.program(400) { track(a, Backdrop.Level(1)) { draw(rec, x = 100, y = 100, xR = 104, n = 1) } } }
        refused("DRAW of a level") { Motion.program(400) { track(a, Backdrop.Level(1)) { draw(Source.level(3, 4, 4), x = 100, y = 100, n = 1) } } }
        refused("an h0 parameter") { Motion.program(400) {
            track(a, Backdrop.Level(1)) { draw(rec, x = 100, y = 100, n = 1, prefs = listOf(Motion.Pref(0, Motion.Field.H0))) } } }
        // what only the parameters decide: refused at the PLAY, before a byte is built
        val ps = Motion.program(400) { track(a, Backdrop.Level(1)) { draw(rec, x = 100, y = 100, n = 4, prefs = listOf(Motion.Pref(0, Motion.Field.LVL1))) } }
        refused("lvl1 = 16 after substitution") { Motion.play(ps, 0, 34, 1, 0, intArrayOf(16, 0, 0, 0)) }
        Motion.play(ps, 0, 34, 1, 0, intArrayOf(9, 0, 0, 0))
        val pt = Motion.program(400) { track(a, Backdrop.Level(1)) { wait(200); fill(3, 1) }; track(a, Backdrop.Level(1)) { draw(rec, x = 100, y = 100, n = 1, prefs = listOf(Motion.Pref(1, Motion.Field.STEPS))) } }
        refused("T = 256 after substitution") { Motion.play(pt, 0, 34, 1, 0, intArrayOf(0, 256, 0, 0)) }
        val src = Motion.program(400) { track(a, Backdrop.Level(1)) { draw(rec, x = 100, y = 100, n = 1, prefs = listOf(Motion.Pref(0, Motion.Field.SRC))) } }
        refused("a src parameter naming a record the builder was not given") { Motion.play(src, 0, 34, 1, 0, intArrayOf(99, 0, 0, 0)) }
        val self = Motion.program(400) { track(a, Backdrop.Self) { fill(3, 2) } }
        refused("a capture range over the program's own first range") { Motion.play(self, 0, 34, 1, 401, IntArray(4)) }
        Motion.play(self, 0, 34, 1, 400 + (self.bytes.size + 3) / 4, IntArray(4))
        refused("RETARGET of two moving segments") { Motion.retarget(Motion.program(400) { track(a, Backdrop.Level(1)) { fill(2, 1); fill(3, 1) } }, 0, IntArray(4)) }
        refused("a period of 15") { CfwModes.motionPlay(0, 15, 400, 1, 0, IntArray(4)) }
        refused("a slot 4") { CfwModes.motionPlay(4, 34, 400, 1, 0, IntArray(4)) }
        refused("a CLOSE of slot 4") { CfwModes.motionClose(4) }
        assertContentEquals(byteArrayOf(26, 2, 0xFF.toByte()), CfwModes.motionClose(CfwModes.MOTION_ALL))
    }
}
