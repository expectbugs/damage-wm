package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Zl
import wm.damage.core.sim.EffectsSim
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.sim.MotionSim
import wm.damage.core.sim.MotionSim.PlanSlot
import wm.damage.core.transport.Arm
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.Pb

/**
 * `FIRMWARE.md` §7 (build C3) through the simulator, each rule written from the contract's TEXT alone, never from the
 * fork's tests: the hash's table (§7.6), the drop orders (§7.5), a PIECES radial step and a SHRINK centre step worked by
 * hand, a cell schedule and a rain head from the formulas, and — through [GlassFirmwareSim] — the owning instance and its
 * held present, LAND, refusal 19, meeting by the owned rect, PLAY-AFTER, the step budget at its cap, the ring list and
 * kind 3, the fold invariant on in-place programs and the late step being no part of the model. The records are built by
 * this file's own encoder, from §5.4's and §7.2's tables — a reading beside `make_vectors.py` and the phone's DSL.
 */
class EffectsSimTest {

    // ------------------------------------------------------------------------------------------- §7.6 the hash
    @Test
    fun theHashGivesTheContractsTenValues_7_6() {
        val h = EffectsSim
        assertEquals(listOf(0, 0x688990C0, 0x6768824A, 0xF5E71C96.toInt()), listOf(h.h(0), h.h(1), h.h(-1), h.h(0x12345678)))
        assertEquals(listOf(0xAE6F80F1.toInt(), 0xA07C7A97.toInt(), 0x2EC199FE), listOf(h.h2(0, 0), h.h2(1, 0), h.h2(0x10001, 7)))
        assertEquals(listOf(0xB015CF54.toInt(), 0x848366AC.toInt(), 0xB056A444.toInt()), listOf(h.h3(0, 0, 0), h.h3(5, 3, 9), h.h3(0xBEEF, 12, 1)))
        // "mod" is the unsigned remainder: 0xFFFFFFFF mod 10 = 5 (a signed one would give −1)
        assertEquals(5, EffectsSim.umod(-1, 10))
    }

    // -------------------------------------------------------------------------------------- §7.5 drop orders
    @Test
    fun theDropOrdersOfTheContract_7_5() {
        assertContentEquals(intArrayOf(0), EffectsSim.dropOrder(1))
        assertContentEquals(intArrayOf(0, 4, 2, 1, 3), EffectsSim.dropOrder(5))
        assertContentEquals(intArrayOf(0, 4, 2, 1, 5, 3), EffectsSim.dropOrder(6))
        assertContentEquals(intArrayOf(0, 8, 4, 2, 10, 6, 1, 9, 5, 3, 11, 7), EffectsSim.dropOrder(12))
    }

    // ------------------------------------------------------------------------- §7.4 a PIECES radial step by hand
    /** A 8×4 area at (100, 50), 2×2 pieces, radial about (103, 52), f 0 → 2048 in one step. Centres (102, 51), (106, 51),
     *  (102, 53), (106, 53); D = ((c − anchor)·2048 + 2048) >> 12 = (0, 0), (2, 0), (0, 1), (2, 1) — the +2048 decides
     *  (3·2048 = 1.5·4096 rounds to 2; 2048 = 0.5·4096 to 1). Pieces moved off their home leave the vacate (0). */
    @Test
    fun aPiecesRadialStepWorkedByHand_7_4() {
        val area = Rect(100, 50, 8, 4)
        val buf = ByteArray(320 * 480)
        for (y in 0 until 4) for (x in 0 until 8) EffectsSim.setNib(buf, 320, 100 + x, 50 + y, 1 + x + y)   // base: 1 + x + y
        val a = EffectsSim.PiecesArgs(2, 2, EffectsSim.MODEL_RADIAL, 0, 0, 2048, 103, 52, 0, 0, 0, false, intArrayOf(0, 4096), null)
        EffectsSim.piecesOld(buf, 320, area, a, 0, 1, EffectsSim.gTable(intArrayOf(15, 15)), 0)
        // p0 stays; p1 two right (columns 4–5 vacated, 6–7 from 4–5); p2 one down (row 2 vacated); p3 both
        val want = arrayOf(intArrayOf(1, 2, 3, 4, 0, 0, 5, 6), intArrayOf(2, 3, 4, 5, 0, 0, 6, 7),
                           intArrayOf(0, 0, 0, 0, 0, 0, 0, 0), intArrayOf(3, 4, 5, 6, 0, 0, 7, 8))
        for (y in 0 until 4) for (x in 0 until 8) assertEquals(want[y][x], EffectsSim.nib(buf, 320, 100 + x, 50 + y), "pixel ($x, $y)")
    }

    /** §7.4 G: g_t(v) = ⌊v·L_t / L_(t−1)⌋ (0 when L_(t−1) = 0), capped at 15, composed from b + 1 to k; the vacate level
     *  is not passed through it. */
    @Test
    fun gIsTheComposedRampAndTheVacateIsNotDimmed_7_4() {
        val g = EffectsSim.gTable(intArrayOf(15, 12, 6))
        assertEquals(listOf(0, 2, 6), listOf(g[0], g[7], g[15]))          // 7 → ⌊84/15⌋ = 5 → ⌊30/12⌋ = 2; 15 → 12 → 6
        assertEquals(List(16) { it }, EffectsSim.gTable(intArrayOf(9)).toList(), "b = k: the identity")
        assertEquals(List(16) { 0 }, EffectsSim.gTable(intArrayOf(15, 0, 8)).toList(), "after a level of 0 every value is 0")
        assertEquals(15, EffectsSim.gTable(intArrayOf(5, 15))[9], "capped at 15 (27)")
        // uniform layer old over a dimming ramp: the moved pixels dimmed, the vacate (12) left bright
        val buf = ByteArray(320 * 480)
        EffectsSim.fill(buf, 320, Rect(0, 0, 4, 1), 10)
        val a = EffectsSim.PiecesArgs(1, 1, EffectsSim.MODEL_UNIFORM, 0, 0, 4096, 0, 0, 2, 0, 0, false, intArrayOf(0, 4096), null)
        EffectsSim.piecesOld(buf, 320, Rect(0, 0, 4, 1), a, 0, 1, EffectsSim.gTable(intArrayOf(15, 6)), 12)
        assertEquals(listOf(12, 12, 4, 4), (0 until 4).map { EffectsSim.nib(buf, 320, it, 0) })       // ⌊10·6/15⌋ = 4
    }

    // ------------------------------------------------------------------------- §7.5 a SHRINK centre step by hand
    /** Wa = 6 to W = 3 at the centre: the drop order of 6 is 0, 4, 2, 1, 5, 3, so columns {0, 2, 4} are kept, placed at
     *  a = (6 − 3) >> 1 = 1: [v, c0, c2, c4, v, v]. Then 3 → 1 keeps column 0 at a = 2, read from its place at W = 3 (1),
     *  which equals 6 → 1 drawn in one step (the fold). */
    @Test
    fun aShrinkCentreStepWorkedByHand_7_5() {
        val area = Rect(10, 20, 6, 1)
        fun base() = ByteArray(320 * 480).also { b -> for (x in 0 until 6) EffectsSim.setNib(b, 320, 10 + x, 20, x + 1) }
        val id = EffectsSim.gTable(intArrayOf(15))
        fun row(b: ByteArray) = (0 until 6).map { EffectsSim.nib(b, 320, 10 + it, 20) }
        val b = base()
        EffectsSim.shrink(b, 320, area, 6, 1, 3, 1, 1 shl 2, id, 9)
        assertEquals(listOf(9, 1, 3, 5, 9, 9), row(b))
        EffectsSim.shrink(b, 320, area, 3, 1, 1, 1, 1 shl 2, id, 9)
        assertEquals(listOf(9, 9, 1, 9, 9, 9), row(b))
        val once = base()
        EffectsSim.shrink(once, 320, area, 6, 1, 1, 1, 1 shl 2, id, 9)
        assertEquals(row(b), row(once), "two steps equal one (the fold)")
        // the right anchor places the kept columns at Wa − W
        val r = base()
        EffectsSim.shrink(r, 320, area, 6, 1, 3, 1, 2 shl 2, id, 9)
        assertEquals(listOf(9, 9, 9, 1, 3, 5), row(r))
    }

    // -------------------------------------------------------------------------------- §7.6 a cell schedule
    /** Seed 0x1234, n = 6, N = 2: s_c = 1 + (H2(seed, c) mod 4) = 4, 2, 4 (computed from the hash's definition); noise is
     *  alphabet[H3(seed, c, t) mod 2] — 68, 68 / 68, 67 / 67, 67. Glyph c is one pixel of level c − 64. Cell 1 fills its
     *  box (level 8) until it settles to its new code, cell 2 (new 0) always. */
    @Test
    fun aCellScheduleAndTheFillByPhase_7_6() {
        fun g(level: Int) = EffectsSim.Glyph(1, 1, byteArrayOf(level.toByte()), 1)
        val glyphs = mapOf(0 to mapOf(65 to g(1), 66 to g(2), 67 to g(3), 68 to g(4)))
        val cells = listOf(EffectsSim.Cell(0, 0, 1, 1, 0, 65, 66, 0xFF, 15), EffectsSim.Cell(2, 0, 2, 1, 0, 65, 66, 8, 15),
                           EffectsSim.Cell(5, 0, 2, 1, 0, 65, 0, 8, 15))
        val list = EffectsSim.CellList(cells, intArrayOf(67, 68), 2, glyphs)
        val seen = (0..6).map { t ->
            val buf = ByteArray(320 * 480)
            EffectsSim.cells(buf, 320, Rect(0, 0, 640, 480), list, 0x1234, 6, t, 0, 0, 15)
            listOf(EffectsSim.nib(buf, 320, 0, 0), EffectsSim.nib(buf, 320, 2, 0), EffectsSim.nib(buf, 320, 3, 0), EffectsSim.nib(buf, 320, 5, 0), EffectsSim.nib(buf, 320, 6, 0))
        }
        // cell 0: old ×4, 68, 68, new · cell 1: old ×2, 68, 67, new (its box's second pixel filled until then) · cell 2: new 0
        assertEquals(listOf(1, 1, 1, 1, 4, 4, 2), seen.map { it[0] })
        assertEquals(listOf(1, 1, 4, 3, 2, 2, 2), seen.map { it[1] })
        assertEquals(listOf(8, 8, 8, 8, 0, 0, 0), seen.map { it[2] }, "the fill by phase: none once settled to a non-zero code")
        assertEquals(listOf(1, 1, 1, 1, 3, 3, 8), seen.map { it[3] }, "settled to 0: no glyph, the box filled")
        assertEquals(List(7) { 8 }, seen.map { it[4] })
    }

    // ---------------------------------------------------------------------------------------- §7.6 a rain head
    /** Ha 40 and ch 10: R = 4; trail 2; seed 0x55, spread 3: o = H2(seed, c) mod 4 = 0, 2, 2. At e = 2048 the heads are
     *  (6·2048 >> 12) = 3 and −2 + (8·2048 >> 12) = 2: column 0 lit at rows 2–3, columns 1–2 at rows 1–2; above the trail
     *  reveal 2 fills with fillLvl. A 6-px glyph stays inside its 4-px cell. With e = −1 the head is (6·−1) >> 12 = −1:
     *  nothing is lit (a truncating division would light row 0). */
    @Test
    fun aRainHeadItsTrailAndItsCellClip_7_6() {
        val glyph = EffectsSim.Glyph(6, 1, ByteArray(6) { 15 }, 1)
        val rain = EffectsSim.Rain(3, 2, 4, 10, intArrayOf(65), 15, 15, 2, 3, 3, 1, mapOf(65 to glyph))
        val area = Rect(100, 200, 12, 40)
        val buf = ByteArray(320 * 480)
        EffectsSim.rain(buf, 320, null, area, rain, 0x55, 1, 2048, 0, 0, 15)
        fun at(c: Int, r: Int, dx: Int = 0) = EffectsSim.nib(buf, 320, 100 + 4 * c + dx, 200 + 10 * r)
        assertEquals(listOf(3, 3, 15, 15), (0..3).map { at(0, it) }, "column 0: rows 0–1 above the trail, 2–3 lit")
        assertEquals(listOf(3, 15, 15, 0), (0..3).map { at(1, it) }, "column 1: row 0 above, 1–2 lit, 3 below the head")
        assertEquals(listOf(15, 15, 15, 15, 0), (0..4).map { at(0, 3, it) }, "a lit cell's glyph clipped to its 4-px cell")
        val neg = ByteArray(320 * 480)
        EffectsSim.rain(neg, 320, null, area, rain, 0x55, 1, -1, 0, 0, 15)
        assertTrue(neg.all { it.toInt() == 0 }, "e = −1: every head below row 0, nothing drawn")
    }

    // ------------------------------------------------------------------------------- a rig on the simulator
    private class Rig(val features: Int = C3, val arm: Arm = Arm.RIGHT) {
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = features; it.clockIsOsTick = true }
        var now = 1000L
        val lens get() = if (arm == Arm.LEFT) sim.left else sim.right
        fun msg(m: ByteArray) = sim.conformanceMessage(arm, m, now)
        fun write(off4: Int, b: ByteArray) = assertTrue(msg(byteArrayOf(19) + u16(off4) + u16(b.size) + b), "mode 19 at $off4")
        fun wake(at: Long) { now = at; sim.conformanceWake(arm, now) }
        fun plan() = sim.conformancePlan(arm, now)
        fun sent() = sim.conformanceSent(arm).map { (f, b) -> f to Pb.fields(b).associate { it.field to it.varint!! } }
        fun panel(x: Int, y: Int) = EffectsSim.nib(lens.panel, 320, x, y)
        fun shadow(x: Int, y: Int) = EffectsSim.nib(lens.shadow, 320, x, y)
        fun reason() = sim.refusalRecord(arm)[1]
        fun motion() = sim.motionForTest(arm)
        fun flags(f: Int) = sim.conformanceControl(arm, DamageMsg.OP_FLAGS_SET, f, now)
        init {
            sim.conformanceLease(arm, true, now)
            flags(if (features and (1 shl 11) != 0) ARMED else ARMED and EFFECTS.inv())
            assertTrue(msg(CfwModes.keyframe(Zl.encodeCfw(ByteArray(320 * 480) { 0x33 }))), "the keyframe (level 3 everywhere)")
        }
    }

    // ------------------------------------------------------------------------------- §7.9 the flag and caps
    @Test
    fun effectsIsAFlagOnlyOnABit11BuildAndNeedsTheLease_7_9() {
        for ((features, ok) in listOf(C2 to false, C3 to true)) {
            val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = features; it.clockIsOsTick = true }
            sim.conformanceControl(Arm.RIGHT, DamageMsg.OP_FLAGS_SET, EFFECTS, 1000)
            assertEquals(DamageMsg.STATUS_NO_LEASE, sim.damageStatus(Arm.RIGHT), "0x${features.toString(16)}: no lease")
            sim.conformanceLease(Arm.RIGHT, true, 1000)
            sim.conformanceControl(Arm.RIGHT, DamageMsg.OP_FLAGS_SET, EFFECTS, 1000)
            assertEquals(if (ok) DamageMsg.STATUS_OK else DamageMsg.STATUS_UNSUPPORTED, sim.damageStatus(Arm.RIGHT), "0x${features.toString(16)}")
            assertEquals(if (ok) EFFECTS else 0, sim.damageFlags(Arm.RIGHT), "bit 6 alone is taken on a bit-11 build only")
        }
    }

    // ---------------------------------------------------------------- §7.2 version-2 records: where each may run
    @Test
    fun aVersion2RecordsWalkAndItsContextRules_7_2() {
        fun refused(prog: ByteArray, play: (Int) -> ByteArray = { play(0, 20, it, 1) }, arm: Int = ARMED): Int {
            val r = Rig()
            r.flags(arm)
            r.write(64, prog)
            return if (r.msg(play(64))) 0 else r.reason()
        }
        val fill = track(rect(0, 0, 10, 10), 2, seg2(FILL, 2, kind = 9))
        assertEquals(0, refused(prog2(0, fill)), "a version-2 FILL program plays")
        assertEquals(5, refused(prog2(0, fill), arm = ARMED and EFFECTS.inv()), "version 2 without EFFECTS: 5")
        assertEquals(5, refused(prog2(LAND, fill)), "LAND needs OWN")
        assertEquals(5, refused(prog2(0, track(rect(0, 0, 10, 10), 2, seg2(FILL, 2, kind = 9, w0 = 1)))), "an unused field: 5")
        assertEquals(12, refused(prog2(0, track(rect(0, 0, 10, 10), 2, seg2(FILL, 2, kind = 16)))), "a FILL level of 16: 12")
        assertEquals(5, refused(prog2(0, track(rect(0, 0, 10, 10), BD_NEW, seg2(WAIT, 2)))), "`new` outside an OWN program")
        assertEquals(5, refused(prog2(0, track(rect(0, 0, 10, 10), NONE, seg2(SHRINK, 2, w0 = 10, w1 = 2)))), "SHRINK outside OWN")
        // DRAW v2's anchor: bits 0–1 vertical, 2–3 horizontal; 3 on either axis or bits 4–7: 5
        fun drawNew(anchor: Int = 0, lvl1: Int = 15) = prog2(OWN, track(rect(0, 0, 10, 10), 2, seg2(DRAW, 2, kind = 4, lvl0 = 15, lvl1 = lvl1, anchor = anchor)))
        assertEquals(listOf(0, 5, 5, 5, 0), listOf(0, 3, 0x0C, 0x10, 5).map { refused(drawNew(anchor = it)) }, "anchors 0, 3, 3 << 2, bit 4, centre-centre")
        assertEquals(12, refused(drawNew(lvl1 = 16)), "an eased level past 15: 12")
        // in place: the first track and the only one (12), its backdrop none (5), its ease never falling (5)
        val shrink = track(rect(0, 0, 10, 10), NONE, seg2(SHRINK, 2, w0 = 10, w1 = 2, lvl0 = 15, lvl1 = 15))
        assertEquals(0, refused(prog2(OWN, shrink)))
        assertEquals(12, refused(prog2(OWN, track(rect(0, 0, 10, 10), 2, seg2(FILL, 1, kind = 1)), shrink)), "an in-place track not first")
        assertEquals(5, refused(prog2(OWN, track(rect(0, 0, 10, 10), 4, seg2(SHRINK, 2, w0 = 10, w1 = 2, lvl0 = 15, lvl1 = 15)))), "its backdrop")
        assertEquals(12, refused(prog2(OWN, shrink, track(rect(5, 5, 10, 10), 2, seg2(FILL, 1, kind = 1)))), "a later track meeting a moving one")
        assertEquals(0, refused(prog2(OWN, shrink, track(rect(50, 5, 10, 10), 2, seg2(FILL, 1, kind = 1)))), "one beside it")
        // an OWN program by PLAY-AFTER only, never at a SET; a PLAY-AFTER of a program without OWN: 12
        assertEquals(12, refused(prog2(0, fill), play = { after(1, 20, it, 2, 0) }), "PLAY-AFTER of a non-OWN program")
        val r = Rig()
        r.write(64, prog2(OWN, fill))
        assertTrue(r.msg(list(0, 0xFFFF, 4, 0, 8, 1)))
        assertFalse(r.msg(set(0, UPROLL, 0, +1, 0, 64)), "an OWN program at a SET")
        assertEquals(5, r.reason())
    }

    // ------------------------------------------------------- §7.3 the owning instance, the held present, LAND
    /** A uniform layer-old PIECES moves the area right by D = ((8·f + 2048) >> 12): 2, 4, 6, 8 at f = 1024 … 4096 (linear,
     *  4 steps), from the panel's own picture, the vacate (1) behind it. A phone frame while it owns lands in the shadow
     *  and shows outside the owned rect only; the done owner holds step T until a CLOSE, whose present copies the shadow. */
    @Test
    fun anOwnerMovesThePanelsPictureAndHoldsItsAreaThroughPhoneFrames_7_3() {
        val r = Rig()
        assertTrue(r.msg(CfwModes.fill(Rect(100, 100, 20, 20), 9)))          // the left half of the area 9, the rest 3
        r.write(64, prog2(OWN, track(rect(100, 100, 40, 20), NONE,
            seg2(PIECES, 4, kind = 0, src = 1, dxL = 8, lvl0 = 15, lvl1 = 15, w1 = 4096, aux0 = 0x0101))))
        r.now = 2000
        assertTrue(r.msg(play(0, 20, 64, 7)))
        fun row() = listOf(r.panel(100, 105), r.panel(101, 105), r.panel(102, 105), r.panel(121, 105), r.panel(122, 105), r.panel(139, 105))
        assertEquals(listOf(1, 1, 9, 9, 3, 3), row(), "step 1: D = 2")
        assertEquals(1, r.motion().field40(), "telemetry 40: slot 0 owns")
        assertEquals(listOf(9, 3), listOf(r.shadow(100, 105), r.shadow(125, 105)), "no END in the shadow: the fill's 9, the keyframe's 3")
        r.now = 2005
        assertTrue(r.msg(CfwModes.fill(Rect(90, 90, 60, 40), 12)))           // a phone frame over the owner
        assertEquals(listOf(1, 1, 9, 9, 3, 3), row(), "the owned rect held")
        assertEquals(12, r.panel(95, 95), "shown outside it")
        assertEquals(12, r.shadow(102, 105), "written into the shadow")
        r.wake(2020)
        assertEquals(listOf(1, 1, 1, 1, 9, 9, 3), listOf(100, 101, 102, 103, 104, 123, 124).map { r.panel(it, 105) }, "step 2: D = 4 from b = 1")
        r.wake(2060)                                                           // step 4 = T, step 3 folded
        assertEquals(listOf(1, 9, 9, 3), listOf(107, 108, 127, 128).map { r.panel(it, 105) }, "step T: D = 8, the fold drawn from b = 2")
        assertEquals(1, r.motion().field40(), "done and owning")
        r.sent()
        r.now = 2100
        repeat(3) { assertTrue(r.msg(CfwModes.fill(Rect(95, 95, 10, 10), 7))) }
        assertEquals(listOf(7, 1, 9), listOf(r.panel(97, 97), r.panel(100, 100), r.panel(110, 105)), "held across three presents")
        assertTrue(r.msg(close(0)))
        assertEquals(listOf(7, 12, 12), listOf(r.panel(100, 100), r.panel(110, 105), r.panel(130, 110)), "the CLOSE's present copies the shadow")
        assertEquals(0, r.motion().field40())
    }

    /** §7.3: an owner draws a step only at k > d — a phone frame's present while it owns does not redraw it, even where its
     *  source is `new` (the live shadow): the owned rect keeps step d until the next step is due. */
    @Test
    fun anOwnerDrawsNoStepAtKNotPastD_7_3() {
        val r = Rig()
        r.write(64, prog2(OWN, track(rect(100, 100, 40, 20), BD_NEW, seg2(WAIT, 3))))
        r.now = 1000
        assertTrue(r.msg(play(0, 20, 64, 1)))
        assertEquals(3, r.panel(110, 110), "step 1: the shadow's own picture")
        r.now = 1005
        assertTrue(r.msg(CfwModes.fill(Rect(100, 100, 40, 20), 12)))
        assertEquals(3, r.panel(110, 110), "k = 1 = d: not redrawn from the new shadow")
        r.wake(1020)
        assertEquals(12, r.panel(110, 110), "step 2 reads the live shadow")
    }

    @Test
    fun landCopiesTheShadowAtStepTAndEndsOwning_7_3() {
        val r = Rig()
        r.write(64, prog2(OWN or LAND, track(rect(100, 100, 40, 20), 5, seg2(WAIT, 2))))
        r.now = 3000
        assertTrue(r.msg(play(1, 20, 64, 9)))
        assertEquals(5, r.panel(110, 110), "step 1: the backdrop")
        assertEquals(2, r.motion().field40())
        r.wake(3020)
        assertEquals(3, r.panel(110, 110), "step T: the shadow copied in place of the tracks")
        assertEquals(0, r.motion().field40(), "LAND ends the owns mark")
        val body = r.sent().single { it.first == 114 }.second
        assertEquals(listOf(1L, 9L, 0L, 0L), listOf(body[2], body[3], body[4], body[5]), "outcome 0, END ordinal 0")
        val crc = java.util.zip.CRC32().also { it.update(ByteArray(20 * 20) { 0x33 }) }.value
        assertEquals(crc, body[8], "field 8: the shadow's CRC over the areas (no END was written)")
    }

    // ------------------------------------------------------------- §7.3 meeting by the owned rect; refusal 19
    @Test
    fun anOwnerMeetsByItsOwnedRect_7_3() {
        for (own in listOf(true, false)) {
            val r = Rig()
            val tracks = arrayOf(track(rect(0, 0, 10, 10), 2, seg2(WAIT, 8)), track(rect(100, 0, 10, 10), 2, seg2(WAIT, 8)))
            r.write(64, prog2(if (own) OWN else 0, *tracks))
            r.write(128, prog2(0, track(rect(50, 0, 10, 10), 4, seg2(WAIT, 8))))    // in the gap between the two tracks
            assertTrue(r.msg(play(0, 20, 64, 1)))
            assertTrue(r.msg(play(1, 20, 128, 2)))
            assertEquals(own, r.motion().slots[0] == null, "own=$own: the gap meets the owned rect, not the tracks")
        }
    }

    @Test
    fun aFireWhoseCloseSetHoldsAnOwnerIsRefused19WithNothingClosed_7_3() {
        val r = Rig()
        r.write(64, prog2(OWN, track(rect(100, 100, 40, 20), 5, seg2(WAIT, 1))))
        r.write(128, prog(track(rect(0, 104, 200, 8), 2, seg(FILL, 3, kind = 9))))
        assertTrue(r.msg(list(0, 0xFFFF, 8, 0, 8, 1)))
        assertTrue(r.msg(set(0, UPROLL, 0, +1, 1, 128, k = listOf(0 to 77))))
        assertTrue(r.msg(play(0, 20, 64, 1)))
        r.sent()
        r.sim.conformanceInput(r.arm, 4, UPROLL.toLong(), 0, r.now)
        val rep = r.sent().single { it.first == 115 }.second
        assertEquals(listOf(2L, 19L, 77L, 128L), listOf(rep[5], rep[6], rep[10], rep[14]), "refused 19, fields 10–14 carried")
        assertTrue(r.motion().slots[0]?.owns == true, "nothing closed")
        assertEquals(0, r.sim.bindForTest(r.arm).lists[0].cursor, "nothing moved")
    }

    /** §7.3 as ruled (`C3-decisions.md` H): the meeting box spans both lenses — a per-lens owner (xL 0, xR 200) meets a
     *  PLAY at x 200 on LEFT too, where its own rect is at 0 — so both lenses close it alike. */
    @Test
    fun anOwnersMeetingBoxSpansBothLenses_7_3() {
        val r = Rig(arm = Arm.LEFT)
        r.write(64, prog2(OWN, byteArrayOf(1, 2, 1, 0) + rect(0, 0, 10, 10) + rect(200, 0, 10, 10) + seg2(WAIT, 8)))
        r.write(128, prog2(0, track(rect(200, 0, 10, 10), 4, seg2(WAIT, 8))))
        assertTrue(r.msg(play(0, 20, 64, 1)))
        assertEquals(Rect(0, 0, 10, 10), r.motion().jobRects(true).single(), "the held copy uses LEFT's own rect")
        assertTrue(r.msg(play(1, 20, 128, 2)))
        assertNull(r.motion().slots[0], "closed on LEFT by the box")
    }

    /** §7.2 as ruled (H): in an OWN program a track of WAITs and exactly one REVEAL with an ease that never falls may have
     *  no backdrop outside any earlier track (its uncovered part is the panel's picture); two REVEALs are 2. */
    @Test
    fun theRevealExceptionIsExactlyOneRevealBetweenWaits_7_2() {
        fun play(vararg segs: ByteArray): Int {
            val r = Rig()
            r.write(64, prog2(OWN, track(rect(100, 100, 20, 20), NONE, *segs)))
            return if (r.msg(play(0, 20, 64, 1))) 0 else r.reason()
        }
        val reveal = seg2(3, 2, kind = 3, src = 5, xL = 100, y = 100, dxL = 20, dy = 20)
        assertEquals(0, play(seg2(WAIT, 1), reveal, seg2(WAIT, 1)))
        assertEquals(2, play(reveal, reveal), "two REVEALs in one such track")
        assertEquals(2, play(seg2(FILL, 2, kind = 5)), "a FILL with no backdrop is not excepted")
    }

    /** §7.3 as ruled (H): a rebuild of the container (CREATE, REBUILD) ends every owning instance as a close does — a
     *  drawing one with its outcome-1 body, a done one with none — and leaves other instances open. */
    @Test
    fun aContainerRebuildEndsEveryOwner_7_3() {
        val r = Rig()
        r.write(64, prog2(OWN, track(rect(100, 100, 40, 20), 6, seg2(WAIT, 3))))
        r.write(96, prog2(OWN, track(rect(300, 100, 40, 20), 6, seg2(WAIT, 1))))
        r.write(128, prog(track(rect(0, 300, 40, 20), 2, seg(WAIT, 8))))
        assertTrue(r.msg(play(0, 20, 64, 1))); assertTrue(r.msg(play(1, 20, 96, 2))); assertTrue(r.msg(play(2, 20, 128, 3)))
        r.sent()
        var seq = 0
        for (pk in wm.damage.core.wire.AaFrame.frame(++seq, wm.damage.core.wire.LaunchMsg.SID, wm.damage.core.wire.LaunchMsg.FLAG_REQUEST,
                wm.damage.core.wire.LaunchMsg.prelude(1))) r.sim.write(Arm.RIGHT, pk, r.now)
        for (pk in wm.damage.core.wire.AaFrame.frame(++seq, wm.damage.core.wire.EvenHubMsg.SID, wm.damage.core.wire.EvenHubMsg.FLAG_REQUEST,
                wm.damage.core.wire.EvenHubMsg.carrierCreate(2))) r.sim.write(Arm.RIGHT, pk, r.now)
        assertNull(r.motion().slots[0]); assertNull(r.motion().slots[1])
        assertTrue(r.motion().slots[2] != null, "an instance that does not own stays")
        assertEquals(listOf(0L to 1L), r.sent().filter { it.first == 114 }.map { it.second[2] to it.second[4] }, "the drawing owner's outcome 1 only")
        // the site keys on an owner being open, whatever flag bit 6 says: an owner left by a hand disarm (which closes
        // nothing, §7.9) is ended by a REBUILD too
        val q = Rig()
        q.write(64, prog2(OWN, track(rect(100, 100, 40, 20), 6, seg2(WAIT, 3))))
        assertTrue(q.msg(play(0, 20, 64, 1)))
        q.flags(ARMED and EFFECTS.inv())
        for (pk in wm.damage.core.wire.AaFrame.frame(1, wm.damage.core.wire.EvenHubMsg.SID, wm.damage.core.wire.EvenHubMsg.FLAG_REQUEST,
                Pb.cat(Pb.v(1, wm.damage.core.wire.EvenHubMsg.CMD_REBUILD.toLong()), Pb.v(2, 3)))) q.sim.write(Arm.RIGHT, pk, q.now)
        assertNull(q.motion().slots[0], "EFFECTS clear, an owner open: ended")
    }

    /** §7.6 as ruled: codes 0 and 32 name no glyph record — never walked — so a cell list whose codes and alphabet include
     *  32 is taken though the font's entry 0 points outside the cache; the other codes' glyphs are walked as before. */
    @Test
    fun codes0And32AreNeverWalked_7_6() {
        val cache = ByteArray(2048)
        fun put16(at: Int, v: Int) { cache[at] = (v and 0xFF).toByte(); cache[at + 1] = (v shr 8).toByte() }
        put16(0, 1); put16(4, 16)                                    // cell table at 0: n 1, entry 0 → the list at 64
        put16(64, 2); put16(66, 32)                                  // list: count 2, alphabet at 128, noise 1
        cache[68] = 1
        // cells: (0,0) 2×2 font at 256: old 32 new 65 · old 0 new 32
        intArrayOf(0, 0, 0, 0, 2, 2, 64, 0, 32, 65, 0xFF, 15).forEachIndexed { i, v -> cache[72 + i] = v.toByte() }
        intArrayOf(0, 0, 0, 0, 2, 2, 64, 0, 0, 32, 0xFF, 15).forEachIndexed { i, v -> cache[84 + i] = v.toByte() }
        cache[128] = 2; cache[129] = 32; cache[130] = 65              // alphabet: 32, 65
        put16(256, 0x7FFF)                                           // font entry 0 (code 32): far outside the cache
        put16(256 + 2 * (65 - 32), 200)                              // code 65 → a glyph at 800
        cache[800] = 1; cache[801] = 1; cache[802] = 0x1F             // 1×1, one run of level 15
        val reads = EffectsSim.Reads(0, 4)
        val list = EffectsSim.walkCells(cache, 0, 0, reads)
        assertTrue(list != null, "taken: code 32 is not walked")
        assertEquals(setOf(65), list.glyphs[256]!!.keys, "only code 65's glyph")
        assertEquals(803, reads.hi, "the read range ends at code 65's glyph, not code 32's entry")
        put16(256 + 2 * (65 - 32), 0x7FFF)
        assertNull(EffectsSim.walkCells(cache, 0, 0, EffectsSim.Reads(0, 4)), "code 65's glyph outside the cache: 5")
    }

    // -------------------------------------------------------------------------------------- §7.7 PLAY-AFTER
    @Test
    fun playAfterStartsWhenItsOutHalfHasDrawnItsLastStep_7_7() {
        val r = Rig()
        r.write(64, prog2(OWN, track(rect(100, 100, 40, 20), 6, seg2(WAIT, 3))))               // the out half, T 3
        r.write(128, prog2(OWN or LAND, track(rect(100, 100, 40, 20), 9, seg2(WAIT, 2))))      // the in half, T 2
        r.now = 1000
        assertTrue(r.msg(play(2, 20, 64, 0x22)))
        r.now = 1010
        assertTrue(r.msg(after(3, 16, 128, 0x33, 2)))
        val inHalf = r.motion().slots[3]!!
        assertEquals(1060L, inHalf.t0, "t0: the later of now (1010) and 1000 + 3·20")
        assertEquals(6, r.panel(110, 110), "its own present draws nothing of it")
        assertEquals(0x84 or 0x0C, r.motion().field40(), "40: slots 2 and 3 own, slot 3 has drawn nothing")
        assertEquals(10L, r.plan(), "the out half's step 2 at 1020; the in half's due time 1060")
        r.wake(1020); r.wake(1040)
        assertEquals(1, r.sent().count { it.first == 114 && it.second[2] == 2L }, "the out half's last-step body")
        assertEquals(20L, r.plan(), "the in half waits for its t0")
        r.wake(1060)
        assertNull(r.motion().slots[2], "the done out half emptied at the in half's first step")
        assertTrue(r.sent().isEmpty(), "silently")
        assertEquals(9, r.panel(110, 110))
        r.wake(1076)
        assertEquals(3, r.panel(110, 110), "the in half lands")
    }

    /** The out half's step T and the in half's step 1 are never drawn by one present, whatever their slots: the in half
     *  in slot 0, its out half in slot 2 late (step 2 of 2 due, not drawn) when the PLAY-AFTER arrives. */
    @Test
    fun anOutHalfsLastStepAndItsInHalfsFirstAreNeverOnePresent_7_7() {
        val r = Rig()
        r.write(64, prog2(OWN, track(rect(100, 100, 40, 20), 6, seg2(WAIT, 2))))
        r.write(128, prog2(OWN, track(rect(100, 100, 40, 20), 9, seg2(WAIT, 2))))
        r.now = 1000
        assertTrue(r.msg(play(2, 20, 64, 0x22)))
        r.now = 1050
        assertTrue(r.msg(after(0, 20, 128, 0x33, 2)))
        assertEquals(1050L, r.motion().slots[0]!!.t0, "the out half's end (1040) has passed: t0 = now")
        assertEquals(0, r.motion().slots[0]!!.d, "waiting: its out half was drawing as the present began")
        assertFalse(r.motion().slots[2]!!.drawing, "that present drew the out half's step T")
        r.wake(1051)
        assertEquals(1, r.motion().slots[0]!!.d)
        assertNull(r.motion().slots[2])
        // the in half in a HIGHER slot (3) than its late out half (1): the present draws the out half's step T first, and
        // the in half still waits — its test reads the states as the present began, not as they are by then
        val q = Rig()
        q.write(64, prog2(OWN, track(rect(100, 100, 40, 20), 6, seg2(WAIT, 2))))
        q.write(128, prog2(OWN, track(rect(100, 100, 40, 20), 9, seg2(WAIT, 2))))
        q.now = 1000
        assertTrue(q.msg(play(1, 20, 64, 0x22)))
        q.now = 1050
        assertTrue(q.msg(after(3, 20, 128, 0x33, 1)))
        assertFalse(q.motion().slots[1]!!.drawing, "the out half's step T drawn by this present")
        assertEquals(0, q.motion().slots[3]!!.d, "the in half not drawn by the same present")
        assertEquals(6, q.panel(110, 110))
        // a close of a waiting instance is a close of a drawing one: outcome 1
        r.now = 1200
        assertTrue(r.msg(play(2, 20, 64, 0x44)))
        assertTrue(r.msg(after(3, 20, 128, 0x55, 2)))
        r.sent()
        assertTrue(r.msg(close(3)))
        assertEquals(listOf(1L), r.sent().filter { it.first == 114 && it.second[2] == 3L }.map { it.second[4] })
    }

    @Test
    fun thePlanGivesAPlayAfterItsHoldAsItsDueTime_7_7() {
        val p = MotionSim.Companion
        assertEquals(40L, p.plan(1000, listOf(PlanSlot(true, 1040, 20, 3, 0, hold = 1040)), false, false), "t0 ahead")
        assertEquals(16L, p.plan(1000, listOf(PlanSlot(true, 900, 16, 3, 0, hold = 1016)), false, false), "waiting: now + P")
        assertEquals(MotionSim.PLAN_ACT, p.plan(1000, listOf(PlanSlot(true, 900, 16, 3, 0, hold = 1016), PlanSlot(true, 900, 20, 9, 1)), false, false),
            "another slot due now still acts")
    }

    // ------------------------------------------------------------------------------- §7.12 the step budget
    /** One OWN instance: two full-panel tracks of backdrop `new` under a two-axis DRAW of `new` (1,024 + 11·307,200 each),
     *  one of 275 × 450 with no backdrop (1,024 + 10·123,750) and a FILL of 1 × 4 (1,024 + 4): exactly 8,000,000 u,
     *  taken; the FILL 1 × 5 is one unit over, refused 12. */
    @Test
    fun aProgramAtTheCapIsTakenAndOneUnitOverRefused_7_12() {
        for ((h, ok) in listOf(4 to true, 5 to false)) {
            val r = Rig()
            val big = track(rect(0, 0, 640, 480), BD_NEW, seg2(DRAW, 1, kind = 4, lvl0 = 15, lvl1 = 15, w0 = 320, w1 = 320))
            r.write(64, prog2(OWN, big, big, track(rect(0, 0, 275, 450), NONE, seg2(DRAW, 1, kind = 4, lvl0 = 15, lvl1 = 15, w0 = 137, w1 = 137)),
                track(rect(0, 0, 1, h), NONE, seg2(FILL, 1, kind = 5))))
            assertEquals(ok, r.msg(play(0, 20, 64, 1)), "1 × $h")
            if (ok) assertEquals(8_000_000L, r.motion().slots[0]!!.cost) else assertEquals(12, r.reason())
        }
    }

    /** The sum over two instances: a version-1 FILL over the left half (1,024 + 2·153,600 = 308,224, counted) and an OWN
     *  instance over the right half summing to 7,691,776 (4 × (1,024 + 11·153,600), 1,024 + 10·259·358, 1,024 + 12):
     *  8,000,000 taken; with the last track 1 × 13 one unit over, refused — and taken once the first is closed. */
    @Test
    fun theBudgetJudgesTheSumOverTheDrawingInstances_7_12() {
        for (case in listOf("cap", "over", "alone")) {
            val r = Rig()
            r.write(64, prog(track(rect(0, 0, 320, 480), 2, seg(FILL, 8, kind = 4))))
            val half = track(rect(320, 0, 320, 480), BD_NEW, seg2(DRAW, 1, kind = 4, lvl0 = 15, lvl1 = 15, w0 = 160, w1 = 160))
            r.write(128, prog2(OWN, half, half, half, half, track(rect(320, 0, 259, 358), NONE, seg2(DRAW, 1, kind = 4, lvl0 = 15, lvl1 = 15, w0 = 100, w1 = 100)),
                track(rect(320, 0, 1, if (case == "cap") 12 else 13), NONE, seg2(FILL, 1, kind = 5))))
            assertTrue(r.msg(play(0, 20, 64, 1)), "a version-1 instance is never refused")
            assertEquals(308_224L, r.motion().slots[0]!!.cost)
            if (case == "alone") assertTrue(r.msg(close(0)))
            assertEquals(case != "over", r.msg(play(1, 20, 128, 2)), case)
            if (case == "over") assertEquals(12, r.reason())
        }
    }

    // ----------------------------------------------------------------------------- §7.8 the ring list, kind 3
    @Test
    fun theRingWrapsBothWaysAndKind3ReadsTheCursorBeforeTheMove_7_8() {
        val r = Rig()
        r.write(128, prog(track(rect(0, 104, 64, 8), 2, seg(FILL, 3, kind = 9))))
        assertTrue(r.msg(ring(0, 0xFFFF, 5, 0, 8, 1, 1)))
        assertTrue(r.msg(ring(0, 0xFFFF, 5, -1, 8, 1, 1)))
        assertEquals(4, r.sim.bindForTest(r.arm).lists[0].cursor, "LIST-RING's shift is modulo the count: 0 − 1 → 4")
        assertTrue(r.msg(set(0, UPROLL, 0, +1, 0, 128, k = listOf(2 to 100, 3 to 7))))
        assertTrue(r.msg(set(1, DOWNROLL, 0, -1, 0, 128, k = listOf(2 to 100, 3 to 7))))
        r.sent()
        r.sim.conformanceInput(r.arm, 4, UPROLL.toLong(), 0, r.now)
        var rep = r.sent().single { it.first == 115 }.second
        assertEquals(listOf(0L, 140L, 11L, 1L), listOf(rep[5], rep[10], rep[11], rep[18]), "c' = 0, v = 5: P0 = 5·8 + 100, P1 = 4 + 7, field 18")
        assertEquals(0, r.sim.bindForTest(r.arm).lists[0].cursor)
        r.sim.conformanceInput(r.arm, 4, DOWNROLL.toLong(), 0, r.now)
        rep = r.sent().single { it.first == 115 }.second
        assertEquals(listOf(0L, 92L, 7L, 1L), listOf(rep[5], rep[10], rep[11], rep[18]), "c' = 4, v = −1")
        r.sim.conformanceInput(r.arm, 4, DOWNROLL.toLong(), 0, r.now)
        rep = r.sent().single { it.first == 115 }.second
        assertNull(rep[18], "no wrap: no field 18")
        // a plain LIST clears RING: the next step past the end is the edge
        assertTrue(r.msg(list(0, 0xFFFF, 5, 0, 8, 1)))
        assertTrue(r.msg(list(0, 0xFFFF, 5, 1, 8, 1)))                      // 3 → 4
        r.sim.conformanceInput(r.arm, 4, UPROLL.toLong(), 0, r.now)
        assertEquals(1L, r.sent().single { it.first == 115 }.second[5], "the edge")
        // a one-item ring: a step is the edge
        assertTrue(r.msg(ring(0, 0xFFFF, 1, 0, 8, 1, 1)))
        r.sim.conformanceInput(r.arm, 4, UPROLL.toLong(), 0, r.now)
        assertEquals(1L, r.sent().single { it.first == 115 }.second[5])
        // EFFECTS cleared by hand: a ring fire is 15; kind 3 at a SET is 12
        r.flags(ARMED and EFFECTS.inv())
        assertTrue(r.msg(list(0, 0xFFFF, 5, 0, 8, 1)))
        r.sim.conformanceInput(r.arm, 4, UPROLL.toLong(), 0, r.now)
        assertEquals(listOf(2L, 15L), r.sent().single { it.first == 115 }.second.let { listOf(it[5], it[6]) }, "an entry with kind 3, EFFECTS clear")
        assertFalse(r.msg(set(2, UPROLL, 1, +1, 1, 128, k = listOf(3 to 0))))
        assertEquals(12, r.reason())
        assertFalse(r.msg(ring(1, 0xFFFF, 5, 0, 8, 1, 1)))
        assertEquals(15, r.reason(), "LIST-RING needs EFFECTS")
    }

    // ------------------------------------------------------------------------ §7.2 the fold invariant (own test)
    /** For in-place programs that keep §7.2's fold condition, two wake schedules that draw different steps end with the
     *  same panel: radial pieces outward under a dimming ramp, and SHRINK in both axes about the centre. */
    @Test
    fun twoWakeSchedulesOfAnInPlaceProgramEndOnTheSamePanel_7_2() {
        val programs = listOf(
            prog2(OWN, track(rect(200, 100, 120, 60), NONE, seg2(PIECES, 6, ease = 1, kind = 0, src = 2, xL = 260, y = 130,
                lvl0 = 15, lvl1 = 6, w1 = 2048, aux0 = 0x0304, aux1 = 1))),
            prog2(OWN, track(rect(200, 100, 120, 60), NONE, seg2(SHRINK, 5, src = 1, lvl0 = 15, lvl1 = 9, h0 = 60, h1 = 15, w0 = 120, w1 = 30, anchor = 5))))
        for ((n, prog) in programs.withIndex()) {
            val panels = listOf(listOf(1, 2, 3, 4, 5, 6), listOf(3, 6), listOf(2, 5, 6)).map { steps ->
                val r = Rig()
                assertTrue(r.msg(CfwModes.keyframe(Zl.encodeCfw(ByteArray(320 * 480) { (it * 37 + it / 320 * 11).toByte() }))))
                r.write(64, prog)
                r.now = 5000
                assertTrue(r.msg(play(0, 20, 64, 1)), "program $n")
                for (k in steps.drop(if (steps[0] == 1) 1 else 0)) r.wake(5000L + (k - 1) * 20)
                r.sim.panelCrc32(r.arm)
            }
            assertEquals(1, panels.toSet().size, "program $n: $panels")
        }
    }

    // ------------------------------------------------------------------------- §7.10 the late step is not the model's
    /** §7.10: "the pure plan, and so the vectors' W, are unchanged" — after a wake that drew a step with every due slot at
     *  d + 1 and EFFECTS armed, the model's plan is still §5.2's (the next due time), never the thread's 1-tick wait. */
    @Test
    fun theLateStepIsNoPartOfTheModel_7_10() {
        val r = Rig()
        r.write(64, prog(track(rect(0, 0, 10, 10), 2, seg(WAIT, 4))))
        r.now = 1000
        assertTrue(r.msg(play(0, 34, 64, 1)))
        r.wake(1039)                                         // a few ticks late: k = 2 = d + 1
        assertEquals(2, r.motion().slots[0]!!.d)
        assertEquals(1068L - 1039L, r.plan(), "§5.2's wait to t0 + d·P")
    }

    // ------------------------------------------------------------------------------- the encoder of this file
    private companion object {
        const val C2 = 0x5FF
        const val C3 = 0xDFF
        const val EFFECTS = 1 shl 6
        /** DRAW2 · ENGINE · BIND · ZWRITE · EFFECTS (stage 5's 0x7c). */
        const val ARMED = 0x7C
        const val OWN = 1; const val LAND = 2
        const val WAIT = 0; const val FILL = 1; const val DRAW = 2; const val PIECES = 4; const val SHRINK = 5
        const val BD_NEW = 0x21; const val NONE = 0xFF
        const val UPROLL = 4; const val DOWNROLL = 5

        fun u16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        fun rect(x: Int, y: Int, w: Int, h: Int) = u16(x) + u16(y) + u16(w) + u16(h)
        /** §5.4's 32 bytes. */
        fun seg(verb: Int, steps: Int, kind: Int = 0) = byteArrayOf(verb.toByte(), steps.toByte(), 0, kind.toByte()) + ByteArray(28)
        /** §7.2's 40 bytes: §5.4's 32, then w0 · w1 · aux0 · aux1. */
        fun seg2(verb: Int, steps: Int, ease: Int = 0, kind: Int = 0, src: Int = 0, xL: Int = 0, xR: Int = 0, y: Int = 0,
                 dxL: Int = 0, dxR: Int = 0, dy: Int = 0, lvl0: Int = 0, lvl1: Int = 0, opt: Int = 0, table: Int = 0,
                 h0: Int = 0, h1: Int = 0, anchor: Int = 0, w0: Int = 0, w1: Int = 0, aux0: Int = 0, aux1: Int = 0): ByteArray =
            byteArrayOf(verb.toByte(), steps.toByte(), ease.toByte(), kind.toByte()) + u16(src) + u16(xL) + u16(xR) + u16(y) +
                u16(dxL) + u16(dxR) + u16(dy) + byteArrayOf(lvl0.toByte(), lvl1.toByte(), opt.toByte(), 0) + u16(table) +
                u16(h0) + u16(h1) + byteArrayOf(anchor.toByte(), 0, 0, 0) + u16(w0) + u16(w1) + u16(aux0) + u16(aux1)
        fun track(area: ByteArray, backdrop: Int, vararg segs: ByteArray) =
            byteArrayOf(segs.size.toByte(), backdrop.toByte(), 0, 0) + area + segs.fold(ByteArray(0)) { a, s -> a + s }
        fun prog(vararg tracks: ByteArray): ByteArray {
            val body = tracks.fold(ByteArray(0)) { a, t -> a + t }
            return byteArrayOf(1, tracks.size.toByte()) + u16(4 + body.size) + body
        }
        /** §7.2's 8-B header: version 2 · tracks · length · flags · 0 · 0 u16. */
        fun prog2(flags: Int, vararg tracks: ByteArray): ByteArray {
            val body = tracks.fold(ByteArray(0)) { a, t -> a + t }
            return byteArrayOf(2, tracks.size.toByte()) + u16(8 + body.size) + byteArrayOf(flags.toByte(), 0, 0, 0) + body
        }
        fun params(p: IntArray) = (0 until 4).fold(ByteArray(0)) { a, i -> a + u16(p.getOrElse(i) { 0 }) }
        fun play(slot: Int, period: Int, prog4: Int, tag: Int, cap4: Int = 0, p: IntArray = IntArray(0)) =
            byteArrayOf(26, 0, slot.toByte(), period.toByte()) + u16(prog4) + u16(tag) + u16(cap4) + params(p)
        /** §7.7: `[26][8] slot · period · prog u16 · tag u16 · after · P0..P3`. */
        fun after(slot: Int, period: Int, prog4: Int, tag: Int, after: Int, p: IntArray = IntArray(0)) =
            byteArrayOf(26, 8, slot.toByte(), period.toByte()) + u16(prog4) + u16(tag) + byteArrayOf(after.toByte()) + params(p)
        fun close(slot: Int) = byteArrayOf(26, 2, slot.toByte())
        fun set(entry: Int, id: Int, list: Int, step: Int, slot: Int, prog: Int, period: Int = 34, k: List<Pair<Int, Int>> = emptyList()) =
            byteArrayOf(27, 0, entry.toByte(), (if (id == 0) 4 else 0xFF).toByte(), id.toByte(), list.toByte(), step.toByte(), slot.toByte(), period.toByte()) +
                u16(prog) + (0 until 4).fold(ByteArray(0)) { a, i -> val (kind, arg) = k.getOrElse(i) { 0 to 0 }; a + byteArrayOf(kind.toByte()) + u16(arg) }
        fun list(list: Int, items: Int, count: Int, shift: Int, pitch: Int, mark: Int) =
            byteArrayOf(27, 2, list.toByte()) + u16(items) + u16(count) + u16(shift) + u16(pitch) + byteArrayOf(mark.toByte())
        /** §7.8 LIST-RING: LIST's body, then `flags u8`. */
        fun ring(list: Int, items: Int, count: Int, shift: Int, pitch: Int, mark: Int, flags: Int) =
            list(list, items, count, shift, pitch, mark).also { it[1] = 4 } + byteArrayOf(flags.toByte())
    }
}
