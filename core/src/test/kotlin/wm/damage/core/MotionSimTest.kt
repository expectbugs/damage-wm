package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Zl
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.sim.MotionSim
import wm.damage.core.sim.MotionSim.Companion.PLAN_ACT
import wm.damage.core.sim.MotionSim.Companion.PLAN_FOREVER
import wm.damage.core.sim.MotionSim.PlanSlot
import wm.damage.core.transport.Arm
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.Pb

/**
 * `FIRMWARE.md` §5 (build C1) through the simulator's engine, each rule written from the contract's TEXT and the
 * lead's decisions (`C1-decisions.md` A.2–A.13), never from the fork's tests: the plan as a table (§5.2), the eases
 * (§5.4) against hand-computed E, an END CRC worked by hand (§5.6), a read range with a record between two others
 * (§5.6), and the rules the conformance vectors cannot reach alone. The records are built here by a small encoder
 * of this file's own, from §5.4's table — a third reading beside `make_vectors.py` and the phone's DSL.
 */
class MotionSimTest {

    // ------------------------------------------------------------------------------------------ §5.2 the plan
    private fun slot(t0: Long, p: Int, t: Int, d: Int, drawing: Boolean = true) = PlanSlot(drawing, t0, p, t, d)

    @Test
    fun thePlanIsATable_5_2() {
        val p = MotionSim.Companion
        // nothing drawing: osWaitForever; a done instance is not drawing
        assertEquals(PLAN_FOREVER, p.plan(5000, listOf(null, null), false, false))
        assertEquals(PLAN_FOREVER, p.plan(5000, listOf(slot(1000, 34, 8, 8, drawing = false)), false, false))
        // not due: k = 1 + ⌊10/34⌋ = 1 = d, next due t0 + d·P = 1034, w = 1034 − 1010
        assertEquals(24L, p.plan(1010, listOf(slot(1000, 34, 8, 1)), false, false))
        // due now (k = 2 > d = 1) with nothing outstanding: the thread queues
        assertEquals(PLAN_ACT, p.plan(1034, listOf(slot(1000, 34, 8, 1)), false, false))
        // due now while a refresh is outstanding, or after a failed try or put: one period, the smallest P among the
        // DUE instances (the P-16 one is not due)
        val two = listOf(slot(1000, 40, 8, 1), slot(1000, 20, 8, 1), slot(1070, 16, 8, 1))
        assertEquals(20L, p.plan(1072, two, outstanding = true, failed = false))
        assertEquals(20L, p.plan(1072, two, outstanding = false, failed = true))
        // the earliest due time of several: 1000 + 2·40 = 1080 against 1030 + 2·16 = 1062
        assertEquals(12L, p.plan(1050, listOf(slot(1000, 40, 8, 2), slot(1030, 16, 8, 2)), false, false))
        // "a slot whose P reads outside 16..128 is skipped": alone it plans nothing; beside a good one it is ignored
        assertEquals(PLAN_FOREVER, p.plan(1100, listOf(slot(1000, 15, 8, 1)), false, false))
        assertEquals(PLAN_FOREVER, p.plan(1100, listOf(slot(1000, 129, 8, 1)), false, false))
        assertEquals(24L, p.plan(1010, listOf(slot(1000, 200, 8, 0), slot(1000, 34, 8, 1)), false, false))   // P 200 would be due
        // "never past step T" and the floor of one tick: a time already passed gives 1, never 0 nor ≈ 2³²
        assertEquals(1L, p.plan(1000, listOf(slot(0, 16, 4, 4)), false, false))
        // the wrap: now − t0 unsigned (k = 1 + ⌊240/64⌋ = 4 = d), due t0 + 4·64 = 2³² ≡ 0, signed: 16 ticks ahead
        assertEquals(16L, p.plan(0xFFFFFFF0, listOf(slot(0xFFFFFF00, 64, 8, 4)), false, false))
        // across it: now − t0 = 261 unsigned, k = 5 > 4: due now
        assertEquals(PLAN_ACT, p.plan(5, listOf(slot(0xFFFFFF00, 64, 8, 4)), false, false))
    }

    // ------------------------------------------------------------------------------------------ §5.4 the eases
    @Test
    fun theEasesAgainstHandComputedE_5_4() {
        fun e(ease: Int, n: Int) = MotionSim.easeE(ease, n, null)
        // linear ⌊4096·j/n⌋ and ease-out 4096 − ⌊4096·(n−j)²/n²⌋, E[0] = 0, E[n] = 4096
        assertContentEquals(intArrayOf(0, 4096), e(0, 1)); assertContentEquals(intArrayOf(0, 4096), e(1, 1))
        assertContentEquals(intArrayOf(0, 2048, 4096), e(0, 2)); assertContentEquals(intArrayOf(0, 3072, 4096), e(1, 2))
        val l7 = e(0, 7); val o7 = e(1, 7)
        assertEquals(listOf(0, 585, 1170, 1755, 3510, 4096), listOf(l7[0], l7[1], l7[2], l7[3], l7[6], l7[7]))   // 4096/7 = 585.1
        assertEquals(listOf(1087, 2007, 2759, 4013, 4096), listOf(o7[1], o7[2], o7[3], o7[6], o7[7]))          // 4096·36/49 = 3009.3
        val l255 = e(0, 255); val o255 = e(1, 255)
        assertEquals(listOf(16, 32, 48, 4079, 4096), listOf(l255[1], l255[2], l255[3], l255[254], l255[255]))
        assertEquals(listOf(33, 64, 96, 4096, 4096), listOf(o255[1], o255[2], o255[3], o255[254], o255[255]))  // 4096·254²/255² = 4063.9
        // v(j) = v0 + ((D·E[j] + 2048) >> 12), arithmetic: v(n) = v0 + D exactly, a negative D rounds the same way
        for (d in intArrayOf(600, -597, 1, -1, 32767, -32768, 13)) assertEquals(7 + d, MotionSim.eased(7, d, 4096))
        assertEquals(-298, MotionSim.eased(0, -597, 2048))        // −597·2048 + 2048 = −1,220,608 = −298·4096
        assertEquals(-1, MotionSim.eased(0, -1, 2049))            // −2049 + 2048 = −1, shifted: −1, not 0
        assertEquals(0, MotionSim.eased(0, -1, 2048))
        // an uploaded table is E[1..n] as sent (E may leave 0..4096)
        assertContentEquals(intArrayOf(0, -400, 4700, 4096), MotionSim.easeE(2, 3, intArrayOf(-400, 4700, 4096)))
    }

    // ------------------------------------------------------------------------------- a rig on the simulator
    private class Rig(features: Int = 0x1FF, val arm: Arm = Arm.RIGHT) {
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = features; it.clockIsOsTick = true }
        var now = 1000L
        val lens get() = if (arm == Arm.LEFT) sim.left else sim.right
        fun msg(m: ByteArray) = sim.conformanceMessage(arm, m, now)
        fun write(off4: Int, b: ByteArray) = assertTrue(msg(byteArrayOf(19) + u16(off4) + u16(b.size) + b), "mode 19 at $off4")
        fun wake(at: Long) { now = at; sim.conformanceWake(arm, now) }
        fun bodies() = sim.conformanceNotifies(arm).map { b -> Pb.fields(b).associate { it.field to it.varint!! } }
        fun panel(x: Int, y: Int) = nib(lens.panel, x, y)
        fun shadow(x: Int, y: Int) = nib(lens.shadow, x, y)
        init {
            sim.conformanceLease(arm, true, now)
            sim.conformanceControl(arm, DamageMsg.OP_FLAGS_SET, DamageMsg.FLAG_DRAW2 or ENGINE, now)
            assertTrue(msg(CfwModes.keyframe(Zl.encodeCfw(ByteArray(320 * 480)))), "the keyframe")
        }
    }

    // ----------------------------------------------------------------- §5.6 END and its CRC, worked by hand
    @Test
    fun theEndCrcOfTwoTracksWorkedByHand_5_6() {
        val r = Rig()
        // track 1: a 3×2 area, backdrop level 5, one WAIT; track 2: a 2×1 area, backdrop 10, FILL level 12. T = 1
        r.write(64, prog(track(rect(0, 0, 3, 2), 5, seg(WAIT, 1)), track(rect(10, 0, 2, 1), 10, seg(FILL, 1, kind = 12))))
        r.now = 2000
        assertTrue(r.msg(play(0, 34, 64, 0x0A0B)))
        // END in the shadow: track 1 is its backdrop, track 2 its FILL over its backdrop
        assertEquals(listOf(5, 5, 5, 0, 12, 12), listOf(r.shadow(0, 0), r.shadow(2, 1), r.shadow(1, 1), r.shadow(3, 0), r.shadow(10, 0), r.shadow(11, 0)))
        // tight packed rows, (w+1)/2 bytes, high nibble first, in track order: 55 50 | 55 50 | CC
        val crc = java.util.zip.CRC32().also { it.update(byteArrayOf(0x55, 0x50, 0x55, 0x50, 0xCC.toByte())) }.value
        // T = 1: the PLAY's own present draws the last step — outcome 0, owed and sent after the refresh (§5.8)
        val b = r.bodies().single()
        assertEquals(crc, b[8], "field 8, the END CRC")
        assertEquals(listOf(0L, 0x0A0BL, 0L, 0L, 1L, 0L), listOf(b[2], b[3], b[4], b[5], b[6], b[7]))
    }

    // ------------------------------------------------- §5.6 a read range with a record between two others
    @Test
    fun aCacheWriteBetweenTwoOfAnInstancesRecordsClosesItAndOneBesideDoesNot_5_6() {
        fun run(writeOff4: Int): List<Map<Int, Long>> {
            val r = Rig()
            r.write(128, image2(4, 2, 7))                  // [512, 517)
            r.write(160, image2(4, 2, 9))                  // [640, 645): named by no program, between the two
            r.write(192, image2(4, 2, 11))                 // [768, 773)
            r.write(64, prog(track(rect(0, 100, 50, 10), 1, seg(DRAW, 8, kind = 0, src = 128, xL = 0, y = 100, dxL = 40, lvl0 = 15, lvl1 = 15)),
                             track(rect(0, 120, 50, 10), 1, seg(DRAW, 8, kind = 0, src = 192, xL = 0, y = 120, dxL = 40, lvl0 = 15, lvl1 = 15))))
            r.now = 2000
            assertTrue(r.msg(play(1, 34, 64, 0x0101)))
            r.bodies()
            r.now = 2010
            r.write(writeOff4, byteArrayOf(1, 2, 3, 4))
            return r.bodies()
        }
        // the first range is [lowest, highest) = [256, 773): the filler at 640 lies inside it
        val closed = run(160).single()
        assertEquals(listOf(1L, 0x0101L, 1L), listOf(b(closed, 2), b(closed, 3), b(closed, 4)), "outcome 1, closed while drawing")
        assertTrue(run(194).isEmpty(), "[776, 780) is past the range's end (half-open): nothing closes")
        assertTrue(run(63).isEmpty(), "[252, 256) ends where the program starts: nothing closes")
    }

    // ----------------------------------------------------------------------- §5.5 the check order, sampled
    @Test
    fun theCheckOrderRecordsTheContractsReason_5_5() {
        val r = Rig()
        r.write(64, prog(track(rect(0, 100, 50, 10), 3, seg(FILL, 4, kind = 9))))
        fun reason(m: ByteArray): Int { assertFalse(r.msg(m)); return r.sim.refusalRecord(r.arm)[1] }
        assertEquals(1, reason(play(9, 15, 0xFFFF, 0).copyOf(17)), "length before slot, period and record")
        assertEquals(1, reason(byteArrayOf(26)), "no sub")
        assertEquals(12, reason(play(4, 34, 0xFFFF, 0)), "slot before the record")
        assertEquals(12, reason(play(0, 15, 64, 0)), "period 16..128")
        assertEquals(16, reason(retarget(2)), "RETARGET of an empty slot")
        assertEquals(5, reason(play(0, 34, 0xFFFF, 0)), "the program past the cache")
        // a pair is checked whole on BOTH lenses: LEFT refuses for RIGHT's rect
        val l = Rig(arm = Arm.LEFT)
        l.write(64, prog(track(rect(0, 40, 100, 40) + rect(560, 40, 100, 40), 2, seg(FILL, 4, kind = 3), perLens = 1)))
        assertFalse(l.msg(play(0, 34, 64, 0))); assertEquals(2, l.sim.refusalRecord(Arm.LEFT)[1])
        // ENGINE (15) comes after DRAW2 (9) and before the slot (12)
        r.sim.conformanceControl(r.arm, DamageMsg.OP_FLAGS_SET, DamageMsg.FLAG_DRAW2, r.now)
        assertEquals(15, reason(play(9, 15, 0xFFFF, 0)))
        r.sim.conformanceControl(r.arm, DamageMsg.OP_FLAGS_SET, ENGINE, r.now)
        assertEquals(9, reason(play(9, 15, 0xFFFF, 0)))
    }

    // ----------------------------------------------------------------------- §5.4 REVEAL, wipe and slide
    @Test
    fun revealWipesOrSlidesInFromTheEdge_5_4() {
        val r = Rig()
        r.write(128, u16(2) + u16(4) + byteArrayOf(0x21, 0x22, 0x23, 0x24))   // 2×4, its rows levels 1, 2, 3, 4: F = 4
        fun rv(x: Int, opt: Int) = seg(REVEAL, 4, kind = 0, src = 128, xL = x, y = 102, opt = opt)
        r.write(64, prog(track(rect(0, 100, 10, 10), 0, rv(2, 0)),            // wipe from the top
                         track(rect(20, 100, 10, 10), 0, rv(22, 4)),          // slide from the top
                         track(rect(40, 100, 10, 10), 0, rv(42, 5))))         // slide from the bottom
        r.now = 2000
        assertTrue(r.msg(play(0, 34, 64, 1)))
        r.wake(2034)                                        // step 2: e = (4·2048 + 2048) >> 12 = 2
        // wipe: the source at rest, only its top e rows visible
        assertEquals(listOf(1, 2, 0), listOf(r.panel(2, 102), r.panel(2, 103), r.panel(2, 104)))
        // slide from the top: drawn at y − (F − e) = 100, visible [y, y + e): its rows 2 and 3
        assertEquals(listOf(0, 3, 4, 0), listOf(r.panel(22, 101), r.panel(22, 102), r.panel(22, 103), r.panel(22, 104)))
        // slide from the bottom: drawn at y + (F − e) = 104, visible [y + F − e, y + F) = [104, 106): its rows 0 and 1
        assertEquals(listOf(0, 1, 2, 0), listOf(r.panel(42, 103), r.panel(42, 104), r.panel(42, 105), r.panel(42, 106)))
        // END is every source whole at rest
        assertEquals(listOf(1, 4, 1, 4), listOf(r.shadow(2, 102), r.shadow(22, 105), r.shadow(42, 102), r.shadow(43, 105)))
    }

    // ----------------------------------------------------------- decision A.5: the capture layout and range
    @Test
    fun capturesOnlyForSelfTracksInTrackOrderEachFourByteAligned_A5() {
        val r = Rig()
        r.msg(CfwModes.fill(Rect(100, 50, 33, 11), 6))
        // track 1 self (33×11: 17 B a row, 187 B), track 2 a level backdrop (no capture), track 3 self backdrop (21×9)
        r.write(64, prog(track(rect(100, 50, 33, 11), SELF, seg(DRAW, 2, kind = 2, xL = 100, y = 50, dxL = 4, lvl0 = 15, lvl1 = 15)),
                         track(rect(300, 50, 10, 10), 4, seg(WAIT, 2)),
                         track(rect(10, 420, 21, 9), SELF, seg(WAIT, 2))))
        r.now = 2000
        assertTrue(r.msg(play(0, 34, 64, 1, cap4 = 1024)))
        val cache = r.lens.textureCache!!
        assertEquals(0x66, cache[4096].toInt() and 0xFF, "track 1's first row at cap·4")
        assertEquals(0x60, cache[4096 + 16].toInt() and 0xFF, "its odd last pixel and a zero pad nibble")
        // 4096 + 187 = 4283, aligned up to 4284: track 3's capture (zeros) — and nothing for track 2
        val m = r.sim.motionForTest(r.arm).slots[0]!!
        assertEquals(MotionSim.Span(4096, 4284 + 11 * 9), m.cap)
        assertEquals(1L, r.sim.cacheGen(r.arm), "a capture is not counted in field 17")
        // a capture range meeting the PLAY's own first range is refused (5)
        assertFalse(r.msg(play(1, 34, 64, 2, cap4 = 64))); assertEquals(5, r.sim.refusalRecord(r.arm)[1])
    }

    // ------------------------------------------------------------- decisions A.2 and A.11: bodies, counters
    @Test
    fun theNotifyBodiesAndCounters_A2_A11() {
        val r = Rig()
        r.write(64, prog(track(rect(0, 100, 50, 10), 3, seg(FILL, 8, kind = 9))))
        r.now = 2000
        assertTrue(r.msg(play(2, 34, 64, 0x0707)))
        r.wake(2000 + 5 * 34)                               // k = 6: steps 2..5 passed over (4 folded)
        r.wake(2000 + 7 * 34)                               // k = 8 = T: steps 7 folded, 8 drawn
        val b0 = r.bodies().single()
        assertEquals((1..11).toList(), b0.keys.sorted(), "outcome 0 carries fields 1-11")
        assertEquals(listOf(0L, 3L, 5L), listOf(b0[4], b0[6], b0[7]), "drawn + folded = T")
        val m = r.sim.motionForTest(r.arm)
        assertEquals(listOf(2L, 5L), listOf(m.engineFrames, m.foldsSinceBoot), "field 32 the puts, 33 the steps passed over")
        // a refusal: outcome 2 with 1, 2 and 3 when the message holds them, 4, 10, 11 — a short one holds no slot
        assertFalse(r.msg(byteArrayOf(26, 0)))
        assertEquals(listOf(1, 4, 10, 11), r.bodies().single().keys.sorted())
        assertFalse(r.msg(play(1, 200, 64, 0x0808)))
        val b2 = r.bodies().single()
        assertEquals(listOf(1, 2, 3, 4, 10, 11), b2.keys.sorted()); assertEquals(listOf(1L, 0x0808L, 2L, 12L), listOf(b2[2], b2[3], b2[4], b2[10]))
        // a CLOSE of a drawing instance: outcome 1, all of 1-11; of a done one: none
        assertTrue(r.msg(play(0, 34, 64, 0x0909))); r.bodies()
        assertTrue(r.msg(close(0xFF)))
        val b1 = r.bodies().single()
        assertEquals(listOf(1L, 0L, 0x0909L), listOf(b1[4], b1[2], b1[3])); assertEquals(11, b1.size)
        assertEquals(b1[1], m.sequence, "field 31: the last sequence")
    }

    // ------------------------------------------------- decision A.12: the stale mark after the last step
    @Test
    fun thePresentThatDrawsTheLastStepMarksThePanelStale_A12() {
        val r = Rig()
        r.write(64, prog(track(rect(0, 100, 50, 10), 3, seg(FILL, 2, kind = 9))))
        r.now = 2000
        assertTrue(r.msg(play(0, 34, 64, 1)))
        r.wake(2034)                                        // the last step
        fun hinted(y: Int, level: Int) { assertTrue(r.msg(batch(CfwModes.fill(Rect(0, 300, 640, 4), level), CfwModes.fill(Rect(0, y, 640, 2), level), CfwModes.presentHint(y, y + 1)))) }
        r.now = 2100; hinted(400, 5)
        assertEquals(5, r.panel(0, 300), "the next present goes whole: an unhinted row reaches the lens")
        r.now = 2200; hinted(410, 6)
        assertEquals(5, r.panel(0, 300), "the one after takes its rows only")
        assertEquals(6, r.panel(0, 410))
        // a present that draws a step goes whole (§5.3), the hint ignored for it
        assertTrue(r.msg(play(0, 34, 64, 2)))
        r.now = 2300; hinted(420, 7)
        assertEquals(7, r.panel(0, 300))
    }

    // ------------------------------------- decision A.13 and §5.5: RETARGET from what this lens last drew
    @Test
    fun aRetargetMovesFromTheLastDrawnStateOrTheStartStateWhenNothingIsDrawn_A13() {
        val r = Rig()
        r.write(128, image2(1, 1, 15))
        r.write(64, prog(track(rect(0, 100, 600, 1), 0, seg(DRAW, 4, kind = 0, src = 128, xL = 10, y = 100, lvl0 = 15, lvl1 = 15, pref0 = 0x80 or (5 shl 2)))))
        r.now = 2000
        // one batch: PLAY P0 = 100, then RETARGET P0 = 200 — nothing drawn between, so from x = 10 (old parameters, j = 0)
        assertTrue(r.msg(batch(play(0, 34, 64, 1, p = intArrayOf(100)), retarget(0, intArrayOf(200)))))
        assertEquals(15, r.panel(60, 100), "step 1: 10 + ((200·1024 + 2048) >> 12) = 60")
        assertEquals(0, r.panel(35, 100))
        assertEquals(15, r.shadow(210, 100), "END(prog, new P) in the shadow")
        // drawn at 60; a RETARGET now (P0 = −10, final x 0) eases from 60: step 1 at 60 + ((−60·1024 + 2048) >> 12) = 45
        r.now = 2010
        assertTrue(r.msg(retarget(0, intArrayOf(-10))))
        assertEquals(15, r.panel(45, 100))
        val m = r.sim.motionForTest(r.arm).slots[0]!!
        assertEquals(listOf(2, 1, 0), listOf(m.ordinal, m.d, m.folded), "END ordinal + 1 at every RETARGET")
    }

    // -------------------------------- §5.6, decisions A.7 and A.8: release points, mode 6, the stock path
    @Test
    fun releasePointsEndEveryInstanceWithNoNotifyAndALiveKeyframeClosesThemFirst_A7_A8() {
        val r = Rig()
        r.write(64, prog(track(rect(0, 100, 50, 10), 3, seg(FILL, 8, kind = 9))))
        r.now = 2000
        assertTrue(r.msg(play(0, 34, 64, 1)))
        assertTrue(r.msg(byteArrayOf(11)))                     // mode 11: a release point
        assertTrue(r.bodies().isEmpty()); assertFalse(r.sim.motionForTest(r.arm).anyOpen)
        // a live mode 6 closes before its inflate, decided from the bytes: a broken stream closes too (outcome 1)
        r.sim.conformanceLease(r.arm, true, r.now)
        r.sim.conformanceControl(r.arm, DamageMsg.OP_FLAGS_SET, DamageMsg.FLAG_DRAW2 or ENGINE, r.now)
        assertTrue(r.msg(CfwModes.keyframe(Zl.encodeCfw(ByteArray(320 * 480)))))
        r.write(64, prog(track(rect(0, 100, 50, 10), 3, seg(FILL, 8, kind = 9))))
        assertTrue(r.msg(play(1, 34, 64, 2)))
        assertFalse(r.msg(byteArrayOf(6, 0x78, 0x01, 0x00)))
        assertEquals(1L, r.bodies().single()[4])
        // a lapse no message noticed: the wake's present takes the stock path — done, no notify, nothing plans
        assertTrue(r.msg(play(2, 34, 64, 3)))
        r.wake(r.now + 200_000)
        assertTrue(r.bodies().isEmpty())
        assertFalse(r.sim.motionForTest(r.arm).anyDrawing)
        assertEquals(PLAN_FOREVER, r.sim.conformancePlan(r.arm, r.now))
        assertEquals(0x5A, r.lens.panel[0].toInt() and 0xFF, "the stock copy (the vectors' convention)")
    }

    // ---------------------------------------------------- §5.11 row 3: the busy word's observable effect
    @Test
    fun aHookThatFindsTheEngineWordHeldFoldsItsStep_5_11() {
        val r = Rig()
        r.write(64, prog(track(rect(0, 100, 50, 10), 3, seg(FILL, 4, kind = 9))))
        r.now = 2000
        assertTrue(r.msg(play(0, 34, 64, 1)))
        val m = r.sim.motionForTest(r.arm)
        m.holdWordForTest(true)
        r.now = 2040
        assertTrue(r.msg(CfwModes.fill(Rect(0, 300, 8, 8), 2)))   // a phone frame: the shadow copied, step 2 folded
        m.holdWordForTest(false)
        assertEquals(listOf<Number>(1L, 1), listOf<Number>(m.foldsSinceBoot, m.slots[0]!!.d))
        assertEquals(PLAN_ACT, r.sim.conformancePlan(r.arm, r.now), "step 2 is still due")
    }

    // --------------------------------------------------------- §5.9 the riders, and today's build unchanged
    @Test
    fun theRidersAndTheInstalledBuild_5_9() {
        val r = Rig()
        r.sim.conformanceControl(r.arm, DamageMsg.OP_CACHE_SIZE, 256, r.now)
        assertEquals(0, r.sim.damageStatus(r.arm), "op 5 takes 256 on a bit-8 build")
        // the installed image (0x7f): ENGINE is not a flag (the FLAGS_SET refused whole), mode 26 has no handler, op 5
        // stops at 160, nothing is notified
        val old = Rig(features = 0x7F)
        assertEquals(0, old.sim.damageFlags(old.arm))
        assertFalse(old.msg(play(0, 34, 64, 1))); assertEquals(8, old.sim.refusalRecord(old.arm)[1])
        old.sim.conformanceControl(old.arm, DamageMsg.OP_CACHE_SIZE, 256, old.now); assertEquals(5, old.sim.damageStatus(old.arm))
        assertTrue(old.bodies().isEmpty())
    }

    // ------------------------------------------------------------------------- the encoder of this file
    private companion object {
        const val ENGINE = 1 shl 3
        const val WAIT = 0; const val FILL = 1; const val DRAW = 2; const val REVEAL = 3
        const val SELF = 0x20

        fun nib(buf: ByteArray, x: Int, y: Int): Int = (buf[y * 320 + x / 2].toInt() and 0xFF).let { if (x % 2 == 0) it shr 4 else it and 0x0F }
        fun b(m: Map<Int, Long>, f: Int): Long? = m[f]
        fun u16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        fun rect(x: Int, y: Int, w: Int, h: Int) = u16(x) + u16(y) + u16(w) + u16(h)
        /** §5.4: 32 bytes — verb steps ease kind · src · xL xR y dxL dxR dy · lvl0 lvl1 opt 0 · table h0 h1 · anchor 0 pref0 pref1. */
        fun seg(verb: Int, steps: Int, ease: Int = 0, kind: Int = 0, src: Int = 0, xL: Int = 0, y: Int = 0, dxL: Int = 0,
                lvl0: Int = 0, lvl1: Int = 0, opt: Int = 0, pref0: Int = 0): ByteArray =
            byteArrayOf(verb.toByte(), steps.toByte(), ease.toByte(), kind.toByte()) + u16(src) + u16(xL) + u16(0) + u16(y) +
                u16(dxL) + u16(0) + u16(0) + byteArrayOf(lvl0.toByte(), lvl1.toByte(), opt.toByte(), 0) + u16(0) + u16(0) + u16(0) +
                byteArrayOf(0, 0, pref0.toByte(), 0)
        fun track(area: ByteArray, backdrop: Int, vararg segs: ByteArray, perLens: Int = 0) =
            byteArrayOf(segs.size.toByte(), backdrop.toByte(), perLens.toByte(), 0) + area + segs.fold(ByteArray(0)) { a, s -> a + s }
        fun prog(vararg tracks: ByteArray): ByteArray {
            val body = tracks.fold(ByteArray(0)) { a, t -> a + t }
            return byteArrayOf(1, tracks.size.toByte()) + u16(4 + body.size) + body
        }
        /** A v2 record of one level: `[w u16][h u16]` then runs of at most 15. */
        fun image2(w: Int, h: Int, level: Int): ByteArray {
            var left = w * h
            var rle = ByteArray(0)
            while (left > 0) { val n = minOf(15, left); rle += byteArrayOf(((n shl 4) or level).toByte()); left -= n }
            return u16(w) + u16(h) + rle
        }
        fun params(p: IntArray) = (0 until 4).fold(ByteArray(0)) { a, i -> a + u16(p.getOrElse(i) { 0 }) }
        fun play(slot: Int, period: Int, prog4: Int, tag: Int, cap4: Int = 0, p: IntArray = IntArray(0)) =
            byteArrayOf(26, 0, slot.toByte(), period.toByte()) + u16(prog4) + u16(tag) + u16(cap4) + params(p)
        fun retarget(slot: Int, p: IntArray = IntArray(0)) = byteArrayOf(26, 1, slot.toByte()) + params(p)
        fun close(slot: Int) = byteArrayOf(26, 2, slot.toByte())
        fun batch(vararg subs: ByteArray) = byteArrayOf(8, subs.size.toByte()) + subs.fold(ByteArray(0)) { a, s -> a + u16(s.size) + s }
    }
}
