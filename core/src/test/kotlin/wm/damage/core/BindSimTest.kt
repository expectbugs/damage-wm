package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Zl
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.sim.MotionSim
import wm.damage.core.transport.Arm
import wm.damage.core.wire.AaFrame
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.Pb

/**
 * `FIRMWARE.md` §6 (build C2) through the simulator: the rules of local input written from the contract's TEXT and the
 * lead's decisions on the build spec, never from the fork's tests or the vectors' answers — S at the entry match on
 * both lenses, the empty table, LIST total (a lost cursor), COMMIT's newest-S rule, DISCARD ≥ S across the wrap,
 * SETTLE's unsigned age, HOLD, the silent same-slot close, 17 with a done local, the report-only fire, the parameters
 * and the edge, mode 28's row map and DRAW's scaled forms. The messages are built by this file's own encoder, from
 * §6.3, §6.5 and §6.6's tables — a third reading beside `make_vectors.py` and the phone's.
 */
class BindSimTest {

    // ------------------------------------------------------------------------------- a rig on the simulator
    private class Rig(vararg arms: Arm = arrayOf(Arm.RIGHT), keyframe: Boolean = true, val features: Int = C2, start: Long = 1000) {
        val arms = arms.toList()
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = features; it.clockIsOsTick = true }
        var now = start
        val telemetry = ArrayList<ByteArray>()
        fun msg(m: ByteArray, arm: Arm = arms[0]) = sim.conformanceMessage(arm, m, now)
        /** [m] to every lens of the rig; true when every one took it. */
        fun all(m: ByteArray) = arms.map { msg(m, it) }.all { it }
        fun input(id: Int, dev: Int = RING, arm: Arm? = null) =
            (arm?.let { listOf(it) } ?: arms).forEach { sim.conformanceInput(it, dev, id.toLong(), 0, now) }
        fun wake(at: Long) { now = at; arms.forEach { sim.conformanceWake(it, now) } }
        /** RIGHT's notifies since the last call: (field, the body's varints). */
        fun sent(): List<Pair<Int, Map<Int, Long>>> =
            sim.conformanceSent(Arm.RIGHT).map { (f, b) -> f to Pb.fields(b).associate { it.field to it.varint!! } }
        fun reports() = sent().filter { it.first == 115 }.map { it.second }
        fun stock(arm: Arm = Arm.RIGHT) = sim.conformanceStockCalls(arm)
        fun bind(arm: Arm = arms[0]) = sim.bindForTest(arm)
        fun motion(arm: Arm = arms[0]) = sim.motionForTest(arm)
        fun lens(arm: Arm = arms[0]) = if (arm == Arm.LEFT) sim.left else sim.right
        fun panel(x: Int, y: Int, arm: Arm = arms[0]) = nib(lens(arm).panel, x, y)
        fun shadow(x: Int, y: Int, arm: Arm = arms[0]) = nib(lens(arm).shadow, x, y)
        fun arm() = arms.forEach {
            sim.conformanceLease(it, true, now)
            sim.conformanceControl(it, DamageMsg.OP_FLAGS_SET, if (features and (1 shl 10) != 0) ARMED else ARMED and BIND.inv(), now)
        }
        /** Telemetry (field 111) from RIGHT, as a map of its varints. */
        fun read(): Map<Int, Long> {
            telemetry.clear()
            sim.conformanceControl(Arm.RIGHT, DamageMsg.OP_TELEMETRY, 9, now)
            val f = AaFrame.Reassembler { }.offer(telemetry.single())!!
            return Pb.fields(Pb.bytesField(f.payload, 111)!!).associate { it.field to it.varint!! }
        }
        init {
            sim.attachListener(object : GlassFirmwareSim.SimDiag {
                override fun event(kind: String, detail: String) {}
                override fun notify(arm: Arm, packet: ByteArray) { val f = AaFrame.Reassembler { }.offer(packet); if (f != null && Pb.bytesField(f.payload, 111) != null) telemetry += packet }
                override fun panelChanged(arm: Arm) {}
            })
            arm()
            if (keyframe) for (a in this.arms) assertTrue(msg(CfwModes.keyframe(Zl.encodeCfw(ByteArray(320 * 480))), a), "the keyframe")
            for (a in this.arms) {
                write(a, PA, prog(track(rect(0, 100, 64, 8), 2, seg(FILL, 3, kind = 9))))
                write(a, PB, prog(track(rect(0, 104, 64, 8), 2, seg(FILL, 3, kind = 5))))
                write(a, PC, prog(track(rect(0, 200, 64, 8), 2, seg(FILL, 3, kind = 7))))
            }
        }
        fun write(a: Arm, off4: Int, b: ByteArray) = assertTrue(msg(byteArrayOf(19) + u16(off4) + u16(b.size) + b, a), "mode 19 at $off4")
        /** One list and its scroll entries: list 0 bound to slot 0 (id 4 down the list, id 5 up it), program A. */
        fun bound(count: Int = 16, mark: Int = 1) {
            assertTrue(all(list(0, NONE, count, 0, 8, mark)), "LIST")
            assertTrue(all(set(0, UPROLL, 0, +1, 0, PA)), "SET 0")
            assertTrue(all(set(1, DOWNROLL, 0, -1, 0, PA)), "SET 1")
        }
    }

    // --------------------------------------------------------------------------- §6.2 step 4: S at the match
    /** "S := S + 1 … every matched event on the EvenHub page takes one, whatever follows, so both lenses number every
     *  matched event alike" — a refusal before the program (14, 18), an edge and a draw alike; an event that matches no
     *  entry, a stock page in front and an unarmed BIND take none. Telemetry 35 counts every S taken. */
    @Test
    fun sIsTakenAtTheEntryMatchWhateverFollowsOnBothLenses_6_2() {
        val r = Rig(Arm.LEFT, Arm.RIGHT)
        r.bound(count = 2)
        fun seqs() = listOf(r.bind(Arm.LEFT).seq, r.bind(Arm.RIGHT).seq)
        r.input(1); r.input(ID_TAP, dev = 1); r.input(3)            // a double tap, a temple tap, a long press: no entry
        assertEquals(listOf(0, 0), seqs(), "no match, no S")
        assertTrue(r.reports().isEmpty())
        assertEquals(3, r.stock(), "every record still reaches the stock dispatcher")
        r.input(UPROLL)                                            // drew: cursor 0 → 1
        assertEquals(listOf(1, 1), seqs())
        r.arms.forEach { r.sim.motionForTest(it).holdWordForTest(true) }
        r.input(UPROLL)                                            // the word held: 14, carrying S
        r.arms.forEach { r.sim.motionForTest(it).holdWordForTest(false) }
        assertEquals(listOf(2, 2), seqs())
        r.input(UPROLL)                                            // cursor 1 + 1 = 2 of 2: the edge
        assertEquals(listOf(3, 3), seqs())
        r.all(list(0, NONE, 2, 9, 8, 1))                            // the cursor lost
        r.input(DOWNROLL)                                              // 18
        assertEquals(listOf(4, 4), seqs())
        val got = r.reports()
        assertEquals(listOf(1L, 2L, 3L, 4L), got.map { it[1] }, "RIGHT's reports carry S")
        assertEquals(listOf(0L, 2L, 1L, 2L), got.map { it[5] }, "drew, refused, edge, refused")
        assertEquals(listOf(null, 14L, null, 18L), got.map { it[6] })
        assertEquals(listOf(1, 2, 3, 5, 6, 15), got[1].keys.sorted(), "a 14 body carries fields 1, 2, 3, 5, 6 and 15 only")
        // a stock page in front, or BIND not armed: no S
        r.arms.forEach { r.sim.conformancePage(it, false) }
        r.input(UPROLL)
        r.arms.forEach { r.sim.conformancePage(it, true) }
        r.arms.forEach { r.sim.conformanceControl(it, DamageMsg.OP_FLAGS_SET, ARMED and BIND.inv(), r.now) }
        r.input(UPROLL)
        assertEquals(listOf(4, 4), seqs(), "a stock page in front, BIND clear: the stock path, no S")
        assertTrue(r.reports().isEmpty())
        assertEquals(4L, r.read()[35], "telemetry 35: every S taken")
    }

    // ------------------------------------------------------------------------------- §6.3 the empty table
    /** "`bind_live` … is 0 when the context is made and after every release point … The first mode 27 accepted while
     *  `bind_live` is 0 first empties every entry and every list (count 0, cursor 0, mark 0, no newest S), then applies,
     *  then sets `bind_live` to 1. A refused mode 27 changes nothing." With telemetry 36 (S, 0 after a release point),
     *  35 (since boot) and 39 (0 while `bind_live` is clear). */
    @Test
    fun theEmptyTableAfterAReleasePoint_6_3() {
        val r = Rig()
        r.bound(count = 8, mark = 7)
        assertTrue(r.msg(list(1, NONE, 4, 2, 8, 3)))                // list 1: cursor 0 + 2
        r.input(UPROLL)                                            // S 1, list 0's cursor 1, its newest S 1
        r.reports()
        val before = r.read()
        assertEquals(listOf(1L, 1L, 0L, (1L shl 4) or (1L shl 5)), listOf(before[35], before[36], before[38], before[39]))
        assertNull(before[37], "37 is a µs figure the model does not know: left out")
        // a release point (FB_RELEASE), then a fresh lease and the flags again
        r.sim.conformanceLease(Arm.RIGHT, false, r.now)
        r.arm()
        val after = r.read()
        assertEquals(listOf(1L, 0L, 0L), listOf(after[35], after[36], after[39]), "S and bind_live cleared; 35 since boot")
        r.input(UPROLL)
        assertTrue(r.reports().isEmpty(), "bind_live clear: every input passes through")
        assertEquals(0, r.bind().seq)
        assertFalse(r.msg(list(2, NONE, 0, 0, 8, 1)), "count 0: refused 12")
        assertFalse(r.bind().live, "a refused mode 27 changes nothing")
        assertTrue(r.msg(list(1, NONE, 4, 1, 8, 9)))                // the first accepted mode 27 since the release point
        assertTrue(r.bind().live)
        val l0 = r.bind().lists[0]; val l1 = r.bind().lists[1]
        assertEquals(listOf(0, 0, 0), listOf(l0.count, l0.cursor, l0.mark), "list 0 emptied")
        assertNull(l0.newest, "no newest S")
        assertEquals(listOf(4, 1, 9), listOf(l1.count, l1.cursor, l1.mark), "list 1 from cursor 0, then shifted by 1")
        assertTrue(r.bind().entries.all { it == null }, "every entry emptied")
        r.input(UPROLL)
        assertTrue(r.reports().isEmpty(), "no entry: no fire")
        assertEquals(0L, r.read()[39])
    }

    // ------------------------------------------------------------------- §6.1 the live path: both lenses, RIGHT's answer
    /** §6.1: "LEFT handles every gesture about one inter-lens answer before RIGHT, and a lost answer leaves a gesture
     *  that LEFT alone handled"; §6.2: the report is put "before stock's own scroll message or click". The live gesture
     *  ([GlassFirmwareSim.injectGesture]) runs the hook on both lenses, then RIGHT's stock message (id 4 → direction 2,
     *  SCROLL_BOTTOM); with RIGHT's answer dropped LEFT alone fires and nothing reaches the phone. */
    @Test
    fun aGestureReachesTheHookOnBothLensesBeforeRightsStockMessage_6_1() {
        val r = Rig(Arm.LEFT, Arm.RIGHT)
        r.bound()
        val order = ArrayList<String>()
        r.sim.attachListener(object : GlassFirmwareSim.SimDiag {
            override fun event(kind: String, detail: String) {}
            override fun notify(arm: Arm, packet: ByteArray) {
                val f = AaFrame.Reassembler { }.offer(packet) ?: return
                order += if (Pb.bytesField(f.payload, 115) != null) "115" else "sid ${f.sid}"
            }
            override fun panelChanged(arm: Arm) {}
        })
        r.sim.injectGesture(wm.damage.core.wire.EvenHubMsg.EV_SCROLL_BOTTOM, now = r.now)
        assertEquals(listOf("115", "sid ${wm.damage.core.wire.EvenHubMsg.SID}"), order, "the report, then RIGHT's stock message")
        assertEquals(listOf(1, 1), listOf(r.bind(Arm.LEFT).seq, r.bind(Arm.RIGHT).seq))
        assertEquals(listOf(1, 1), listOf(r.bind(Arm.LEFT).lists[0].cursor, r.bind(Arm.RIGHT).lists[0].cursor), "id 4: the entry of step +1")
        order.clear()
        r.sim.dropRightAnswers = 1
        r.sim.injectGesture(wm.damage.core.wire.EvenHubMsg.EV_SCROLL_BOTTOM, now = r.now)
        assertTrue(order.isEmpty(), "RIGHT never injects it: no report, no stock message")
        assertEquals(listOf(2, 1), listOf(r.bind(Arm.LEFT).seq, r.bind(Arm.RIGHT).seq), "LEFT one ahead")
        assertEquals(listOf(2, 1), listOf(r.bind(Arm.LEFT).lists[0].cursor, r.bind(Arm.RIGHT).lists[0].cursor))
    }

    // ------------------------------------------------------------------------------- §6.3 mode 27's check order
    /** "the container's list (8) → length (1) → the lease (3) → the engine's busy word claimed (14) → DRAW2 (9) → ENGINE
     *  (15) → BIND (15) → values (12) … → LIST: the item table … inside the cache (5) → SET: the program record's own
     *  structure … (5)" (ENGINE as ruled on 2026-11-18); "A refused mode 27 changes nothing" and sends no notify. */
    @Test
    fun mode27sCheckOrder_6_3() {
        val r = Rig()
        r.bound()
        fun reason(m: ByteArray, flags: Int = ARMED): Int {
            r.sim.conformanceControl(Arm.RIGHT, DamageMsg.OP_FLAGS_SET, flags, r.now)
            assertFalse(r.msg(m), "a mode 27 that must be refused was taken"); return r.sim.refusalRecord(Arm.RIGHT)[1]
        }
        val ok = list(1, NONE, 4, 0, 8, 1)
        assertEquals(1, reason(ok.copyOf(11)), "length")
        assertEquals(1, reason(byteArrayOf(27, 7)), "an unknown sub: 3 bytes")
        r.motion().holdWordForTest(true)
        assertEquals(14, reason(ok)); r.motion().holdWordForTest(false)
        assertEquals(9, reason(ok, BIND), "BIND alone: DRAW2 first")
        assertEquals(15, reason(ok, ARMED and (1 shl 3).inv()), "DRAW2 and BIND, ENGINE clear")
        assertEquals(15, reason(ok, ARMED and BIND.inv()), "BIND clear")
        assertEquals(12, reason(byteArrayOf(27, 7, 0)), "an unknown sub: 12 at values")
        assertEquals(12, reason(set(2, UPROLL, 0, 0, 0, PA)), "a scroll entry of step 0")
        assertEquals(12, reason(set(2, ID_TAP, 0, 0, 0, PA, dev = 1)), "a tap entry of a temple")
        assertEquals(12, reason(set(2, UPROLL, 0, +1, 1, PA)), "list 0 is slot 0's: one slot per list")
        assertEquals(12, reason(set(2, UPROLL, 1, +1, 0, PA, period = 15)), "a period below 16")
        assertEquals(5, reason(set(2, UPROLL, 1, +1, 1, NONE)), "a scroll entry naming 0xFFFF: no program fits")
        assertEquals(5, reason(list(1, 0x3FFF, 4, 0, 8, 1)), "an item table past the cache's end")
        assertTrue(r.msg(byteArrayOf(16, 0)), "the self-test's begin")
        assertEquals(8, reason(byteArrayOf(16, 1) + ok), "a step's own list")
        assertEquals(27, r.sim.refusalRecord(Arm.RIGHT)[0], "recorded with the step's message")
        assertTrue(r.msg(byteArrayOf(16, 2)))
        assertEquals(0, r.bind().lists[1].count, "nothing written by any of them")
        assertTrue(r.sent().isEmpty(), "no notify")
        assertTrue(r.msg(set(2, ID_TAP, 0, 0, 0, NONE)), "a report-only tap entry: no program read")
    }

    // --------------------------------------------------------------------------------- §6.3 LIST is total
    /** "the cursor becomes old + shift when that is inside 0..count−1, else lost (0xFFFF) … a lost cursor stays lost. A
     *  fire on a lost cursor is refused 18; DISCARD and SETTLE set an absolute cursor and clear it." */
    @Test
    fun listIsTotalAndALostCursorStaysLostUntilDiscardOrSettle_6_3() {
        val r = Rig()
        r.bound(count = 4)
        r.input(UPROLL)                                            // cursor 1, local tag 1
        assertTrue(r.msg(list(0, NONE, 4, 5, 8, 2)), "1 + 5 is outside 0..3: still taken")
        assertEquals(LOST, r.bind().lists[0].cursor)
        r.input(UPROLL)
        val lost = r.reports().last()
        assertEquals(listOf(2L, 18L, LOST.toLong(), 2L), listOf(lost[5], lost[6], lost[8], lost[17]), "18, the cursor lost, the mark")
        assertEquals(1, r.motion().slots[0]!!.tag, "nothing drawn: the slot still holds the first fire's instance")
        assertTrue(r.msg(list(0, NONE, 4, -6, 8, 3)))
        assertEquals(LOST, r.bind().lists[0].cursor, "a lost cursor stays lost across a LIST")
        assertTrue(r.msg(discard(0, 3, 0, 2)), "DISCARD at S 3 leaves tag 1 and sets the cursor")
        assertEquals(2, r.bind().lists[0].cursor)
        r.input(UPROLL)
        assertEquals(3L, r.reports().last()[8], "drew from cursor 2")
        assertTrue(r.msg(list(0, NONE, 4, 9, 8, 3)))
        assertTrue(r.msg(settle(0xFF, 5, 0, 1, 0)), "SETTLE at S 5: no local at or after 5 draws — the cursor is set")
        assertEquals(1, r.bind().lists[0].cursor)
    }

    // ------------------------------------------------------------------------------ §6.5 COMMIT's newest S
    /** "the list's cursor := COMMIT's cursor only if S is that list's newest fire"; END into the shadow always; a local
     *  tagged newer left drawing; the local tagged S adopted; one outcome-3 body carrying the END CRC. */
    @Test
    fun commitWritesTheCursorOnlyForTheNewestFire_6_5() {
        val r = Rig()
        r.bound()
        r.input(UPROLL); r.input(UPROLL)                          // S 1 (cursor 1), S 2 (cursor 2) closing S 1's local
        r.sent()
        assertTrue(r.msg(commit(0, 1, 0, 5, PA)))
        assertEquals(2, r.bind().lists[0].cursor, "S 1 is not the newest fire: the cursor stays")
        val newer = assertNotNull(r.motion().slots[0], "a local tagged newer is left, not closed")
        assertTrue(newer.local && newer.drawing && newer.tag == 2, "a local tagged newer is left drawing")
        assertEquals(9, r.shadow(10, 103), "END written into the shadow, always")
        val end = java.util.zip.CRC32().also { it.update(ByteArray(32 * 8) { 0x99.toByte() }) }.value
        val b3 = r.sent().single()
        assertEquals(114, b3.first)
        assertEquals(listOf(0L, 1L, 3L, 0L, 0L, 0L, end, 0L), listOf(b3.second[2], b3.second[3], b3.second[4], b3.second[5], b3.second[6], b3.second[7], b3.second[8], b3.second[10]),
            "outcome 3: slot, tag = S, ordinal 0, none adopted, the END CRC, reason 0")
        assertTrue(r.msg(commit(0, 2, 0, 7, PA)))
        assertEquals(7, r.bind().lists[0].cursor, "S 2 is the newest fire: the cursor is COMMIT's")
        assertFalse(r.motion().slots[0]!!.local, "the local tagged S is adopted: ordinary")
        r.sent()
        r.input(UPROLL)
        assertEquals(8L, r.reports().last()[8], "the next fire moves from the committed cursor")
    }

    // --------------------------------------------------------------------- §6.5 DISCARD ≥ S across the wrap
    /** "stops every local instance tagged at or after S (`(int16_t)(tag − S) ≥ 0`), drawing or holding, outcome 1 each
     *  … ordinary instances are untouched; sets the named list's cursor". */
    @Test
    fun discardStopsLocalsAtOrAfterSAcrossTheWrap_6_5() {
        val r = Rig()
        r.bound()
        r.input(UPROLL)                                            // tag 1
        r.sent()
        for (s in listOf(2, 0x8001)) {                              // 1 − 2 = −1; 1 − 0x8001 = −32768 as an int16
            assertTrue(r.msg(discard(0, s, 0xFF, 0)))
            assertEquals(1, r.motion().slots[0]?.tag, "tag 1 is before S 0x${s.toString(16)}")
        }
        assertTrue(r.sent().isEmpty())
        assertTrue(r.msg(discard(0, 0xFFFF, 0, 3)), "1 − 0xFFFF = 2 as an int16: at or after")
        assertNull(r.motion().slots[0])
        val stop = r.sent().single().second
        assertEquals(listOf(1L, 1L, 0L, 0L), listOf(stop[3], stop[4], stop[5], stop[8]), "outcome 1 with tag S, ordinal 0, CRC 0")
        assertEquals(3, r.bind().lists[0].cursor, "the cursor set to 3")
        r.now = 2000
        r.input(UPROLL)                                            // tag 2, cursor 4
        for (t in listOf(2000L, 2034L, 2068L)) r.wake(t)           // T 3 at P 34: HOLD
        assertTrue(r.motion().slots[0]!!.drawing)
        r.sent()
        assertTrue(r.msg(play(1, 34, PC, 77)))                       // an ordinary instance in slot 1
        assertTrue(r.msg(discard(0xFF, 2, 0xFF, 0)), "S equal to the tag: at or after")
        assertNull(r.motion().slots[0], "the holding local stopped")
        assertEquals(77, r.motion().slots[1]?.tag, "the ordinary instance untouched")
        assertEquals(listOf(2L), r.sent().filter { it.first == 114 }.map { it.second[3]!! }, "one outcome-1 body, the holding local's")
    }

    // --------------------------------------------------------------------- §6.5 SETTLE's unsigned age
    /** "stops each local instance tagged at or after S that has held its last step for at least `age` OS ticks — d = T
     *  and `now − (t0 + (T−1)·P)` ≥ age as an unsigned 32-bit difference … drawing ones, younger ones … untouched;
     *  then sets the named list's cursor … unless a local instance tagged at or after S is still drawing … after the
     *  pass; it presents only when it stopped one". Here step T falls on the tick's wrap. */
    @Test
    fun settlesAgeIsAnUnsignedDifferenceAcrossTheWrap_6_5() {
        val r = Rig(start = 0xFFFF0000L)
        r.write(Arm.RIGHT, PD, prog(track(rect(0, 100, 64, 8), 2, seg(FILL, 2, kind = 9))))
        r.bound()
        assertTrue(r.msg(set(0, UPROLL, 0, +1, 0, PD)))
        r.now = 0xFFFFFFF0L
        r.input(UPROLL)                                            // t0 0xFFFFFFF0; P the entry's, 34
        val o = r.motion().slots[0]!!
        assertEquals(0xFFFFFFF0L, o.t0)
        assertEquals(34, o.period)
        r.wake(0xFFFFFFF0L)                                         // step 1
        assertTrue(r.msg(settle(0, 1, 0, 4, 0)), "a drawing local (d 1 < T 2) is untouched")
        assertEquals(1, r.bind().lists[0].cursor, "and keeps the cursor")
        r.wake(0x12L)                                               // t0 + 34 = 2³² + 0x12: step 2 = T, HOLD
        assertEquals(2, r.motion().slots[0]!!.d)
        assertEquals(9, r.panel(0, 100), "the lens shows the local's step")
        r.now = 0x31                                                // held 0x31 − 0x12 = 31 ticks
        assertTrue(r.msg(settle(0, 1, 0, 4, 32)))
        assertTrue(r.motion().slots[0]!!.drawing, "younger than age: untouched")
        assertEquals(1, r.bind().lists[0].cursor, "a local at or after S still draws: the cursor kept")
        assertEquals(9, r.panel(0, 100), "a SETTLE that stopped nothing presents nothing")
        r.sent()
        r.now = 0x32                                                // held 32
        assertTrue(r.msg(settle(0, 1, 0, 4, 32)))
        assertNull(r.motion().slots[0], "held for age: stopped")
        assertEquals(1L, r.sent().single().second[4], "outcome 1")
        assertEquals(4, r.bind().lists[0].cursor, "nothing at or after S draws now: the cursor set")
        assertEquals(0, r.panel(0, 100), "it presents: the lens shows the shadow")
    }

    // --------------------------------------------------------------------------------------- §6.4 HOLD
    /** "At step T a local instance stays drawing at d = T and owes no 114: the thread's plan skips it (d ≥ T); the
     *  preserve path skips it … the direct path redraws step T at every phone frame's present … Field 30 counts it as
     *  drawing." A stock-path present turns it done and leaves the flag (§6.2 step 11). */
    @Test
    fun holdStaysDrawingAtStepTOwesNoBodyAndIsRedrawnByAPhoneFrame_6_4() {
        val r = Rig()
        r.bound()
        r.input(UPROLL)
        for (t in listOf(1000L, 1034L, 1068L)) r.wake(t)
        val o = r.motion().slots[0]!!
        assertTrue(o.drawing && o.d == 3 && o.local, "HOLD: drawing at d = T")
        assertTrue(r.sent().none { it.first == 114 }, "no 114 at step T")
        assertTrue(r.motion().field30() and (1 shl 4) != 0, "field 30's drawing bit")
        assertEquals(MotionSim.PLAN_FOREVER, r.sim.conformancePlan(Arm.RIGHT, 1100), "the plan has nothing to draw")
        assertEquals(listOf(9, 0), listOf(r.panel(10, 100), r.shadow(10, 100)), "on the panel, never in the shadow")
        r.now = 1200
        assertTrue(r.msg(CfwModes.fill(Rect(0, 300, 64, 4), 6)))   // a phone frame elsewhere: the direct path
        assertEquals(listOf(9, 6), listOf(r.panel(10, 100), r.panel(10, 300)), "step T redrawn over the copied shadow")
        assertTrue(r.sent().isEmpty())
        // a lapse no message has noticed, then a v1 frame (no lease read): the stock path
        r.now = 1000 + 90_000
        assertTrue(r.msg(byteArrayOf(9) + u16(0) + u16(0) + u16(4) + u16(2) + u16(0) + u16(0) + u16(4) + u16(2)))
        val done = r.motion().slots[0]!!
        assertTrue(!done.drawing && done.local, "the stock path turns it done and leaves the flag")
    }

    // ------------------------------------------------------------------- §6.2 step 11: the silent same-slot close
    /** "each is closed — a drawing one's outcome-1 body captured …, a done one silently, and a local instance of this
     *  fire's own slot silently whatever its state"; a fire from another slot whose areas meet a drawing local closes it
     *  with its body, sent before the fire's report. */
    @Test
    fun aFireClosesItsOwnSlotsLocalSilentlyAndAnotherSlotsWithItsBody_6_2() {
        val r = Rig()
        r.bound()
        assertTrue(r.msg(list(1, NONE, 4, 0, 8, 1)))
        assertTrue(r.msg(set(2, DOWNROLL, 1, +1, 1, PB)))             // id 5 on list 1, slot 1, an area meeting A's
        assertTrue(r.msg(set(1, UPROLL, 0, +1, 0, PA)))            // (entry 1 re-SET to id 4: entry 0 is the lower)
        r.input(UPROLL); r.sent()
        r.input(UPROLL)                                            // its own slot's drawing local: no 114
        assertEquals(listOf(115), r.sent().map { it.first })
        r.input(DOWNROLL)                                              // slot 1's fire meets slot 0's drawing local
        val s = r.sent()
        assertEquals(listOf(114, 115), s.map { it.first }, "the close's body, then the report")
        assertEquals(listOf(0L, 2L, 1L), listOf(s[0].second[2], s[0].second[3], s[0].second[4]), "slot 0, tag 2, outcome 1")
        assertEquals(2L, s[1].second[4], "the report names entry 2")
        assertNull(r.motion().slots[0])
    }

    // ---------------------------------------------------------------------------- §6.2 step 11: 17, a done local
    /** "A tap fire whose close set holds a local instance is refused 17, nothing closed … a done local refuses a tap fire
     *  17 as a drawing one does, and the report-only fire's test below reads it the same way"; DISCARD empties a done
     *  local silently (§6.5). */
    @Test
    fun aDoneLocalRefusesATapSeventeenAndDiscardEmptiesItSilently_6_2() {
        for (reportOnly in listOf(false, true)) {
            val r = Rig(keyframe = false)                          // no Damage frame up: a present takes the stock path
            r.bound()
            assertTrue(r.msg(set(4, ID_TAP, 0, 0, 0, if (reportOnly) NONE else PA)))
            r.input(UPROLL)
            r.wake(1000)
            val o = r.motion().slots[0]!!
            assertTrue(o.local && !o.drawing, "the stock path turned it done")
            r.sent()
            r.input(ID_TAP)
            val rep = r.reports().single()
            assertEquals(listOf(2L, 17L), listOf(rep[5], rep[6]), "reportOnly=$reportOnly: refused 17")
            assertEquals(!reportOnly, rep.containsKey(10) && rep.containsKey(14), "fields 10–14 only for an entry with a program")
            assertEquals(1, r.motion().slots[0]?.tag, "nothing closed")
            assertTrue(r.msg(discard(0, 1, 0xFF, 0)))
            assertNull(r.motion().slots[0])
            assertTrue(r.sent().isEmpty(), "a done local is emptied silently")
        }
    }

    // ------------------------------------------------------------------------- §6.2 the report-only fire
    /** "runs steps 1–8 as a tap fire's … and step 11's test … Otherwise it computes no parameters, walks nothing,
     *  closes and opens nothing, leaves the list as it is (cursor and newest S) and sets no engine flag … outcome 0 with
     *  no fields 10–14" — the newest S read through a COMMIT of the preceding scroll (§6.10 `v4-tap`). */
    @Test
    fun theReportOnlyFireDrawsNothingAndLeavesTheList_6_2() {
        val r = Rig()
        r.bound()
        assertTrue(r.msg(set(4, ID_TAP, 0, 0, 0, NONE)))
        r.input(UPROLL)                                            // S 1, cursor 1
        r.input(ID_TAP)                                             // a local in its slot: 17
        assertEquals(17L, r.reports().last()[6])
        assertTrue(r.msg(commit(0, 1, 0, 1, PA)))                   // adopted: an ordinary instance, still drawing
        r.sent()
        val panel = r.sim.panelCrc32(Arm.RIGHT)
        r.input(ID_TAP)                                             // S 3
        val rep = r.reports().single()
        assertEquals(listOf(3L, 4L, 0L, 1L, 0L), listOf(rep[1], rep[3 + 1], rep[5], rep[8], rep[9]), "S, entry 4, outcome 0, the cursor, the slot")
        assertTrue((10..14).none { it in rep }, "no fields 10–14")
        val o = r.motion().slots[0]!!
        assertTrue(o.tag == 1 && !o.local && o.drawing, "the ordinary instance keeps drawing; nothing opened")
        assertEquals(panel, r.sim.panelCrc32(Arm.RIGHT), "nothing drawn")
        assertEquals(1, r.bind().lists[0].newest, "the newest S untouched")
        assertTrue(r.msg(commit(0, 1, 0, 5, PA)))                   // S 1 is still the newest fire: written
        r.sent()
        r.input(UPROLL)
        assertEquals(6L, r.reports().single()[8])
    }

    // ------------------------------------------------------------------------ §6.2 step 9: parameters, edge
    /** "Pi = arg (kind 0) · the item table's entry at c' + arg (kind 1; no table, or c' + arg outside 0..count−1 → edge)
     *  · c' × pitch + arg (kind 2, computed in s32; outside s16 → refused 12)"; the edge draws nothing and keeps the
     *  cursor (§6.4). */
    @Test
    fun theParametersAndTheStagedEdge_6_2() {
        val r = Rig()
        r.write(Arm.RIGHT, 300, u16(500) + u16(501) + u16(502) + u16(0xFFF0))
        assertTrue(r.msg(list(0, 300, 4, 0, 10, 1)))
        assertTrue(r.msg(set(0, UPROLL, 0, +1, 0, PA, k = listOf(KIND_ITEM to 0, KIND_PITCH to 5, 0 to -7, KIND_ITEM to 2))))
        r.input(UPROLL)                                            // c' 1: P = 501, 15, −7, item 3
        val p = r.reports().single()
        assertEquals(listOf(501L, 15L, 0xFFF9L, 0xFFF0L, PA.toLong()), (10..14).map { p[it] })
        r.input(UPROLL)                                            // c' 2: item 2 + 2 = 4 is past the list
        val edge = r.reports().single()
        assertEquals(listOf(1L, 1L), listOf(edge[5], edge[8]), "the edge, the cursor kept")
        assertNull(edge[6])
        assertTrue(r.msg(set(0, UPROLL, 0, +1, 0, PA, k = listOf(KIND_PITCH to 30000))))
        assertTrue(r.msg(list(0, 300, 4, 0, 30000, 1)))
        r.input(UPROLL)                                            // 2 × 30000 + 30000 is outside s16
        assertEquals(12L, r.reports().single()[6])
        assertTrue(r.msg(list(0, 300, 4, 2, 8, 1)))                 // cursor 1 + 2 = 3, the last row
        assertTrue(r.msg(set(0, UPROLL, 0, +1, 0, PA)))
        r.input(UPROLL)
        assertEquals(listOf(1L, 3L), r.reports().single().let { listOf(it[5]!!, it[8]!!) }, "3 + 1 = 4 of 4: the edge")
    }

    // --------------------------------------------------------------------------------- §6.6 mode 28's row map
    /** "output row r takes source row ⌊(2r + 1)·h / (2H)⌋ … columns as they are"; H outside 1..2048 is 12. */
    @Test
    fun mode28sRowMapAtHOneHAndTwoH_6_6() {
        val rows = intArrayOf(1, 2, 3, 4)                           // a 2×4 record, one level per row
        for ((hOut, want) in listOf(1 to listOf(3), 4 to listOf(1, 2, 3, 4), 8 to listOf(1, 1, 2, 2, 3, 3, 4, 4))) {
            val r = Rig()
            r.write(Arm.RIGHT, 400, u16(2) + u16(4) + rows.fold(ByteArray(0)) { a, l -> a + byteArrayOf(((2 shl 4) or l).toByte()) })
            assertTrue(r.msg(mode28(400, 10, 20, 15, hOut)), "H $hOut")
            assertEquals(want, (0 until hOut).map { r.shadow(11, 20 + it) }, "H $hOut")
            assertEquals(0, r.shadow(11, 20 + hOut), "H $hOut: nothing below row H")
        }
        val r = Rig()
        r.write(Arm.RIGHT, 400, u16(2) + u16(4) + byteArrayOf(0x81.toByte()))
        for (h in listOf(0, 2049)) { assertFalse(r.msg(mode28(400, 10, 20, 15, h))); assertEquals(12, r.sim.refusalRecord(Arm.RIGHT)[1], "H $h") }
        assertFalse(r.msg(mode28(400, 10, 20, 15, 4).copyOf(9)))
        assertEquals(1, r.sim.refusalRecord(Arm.RIGHT)[1], "length")
    }

    // ------------------------------------------------------------------------------------ §6.6 DRAW scaled
    /** "DRAW's h0, h1, anchor: the output height eased from h0 to h1 …; anchor 0 top · 1 centre (`y + ((h − H) >> 1)`)
     *  · 2 bottom (`y + h − H`) … h0 and h1 both 0, or every step's eased H inside 1..2048, else 12"; on a C1 build
     *  they must be 0 (§5.4). */
    @Test
    fun aScaledDrawTakesItsAnchorAndTheHRule_6_6() {
        fun withDraw(features: Int, h0: Int, h1: Int, anchor: Int, steps: Int = 1): Pair<Rig, Boolean> {
            val r = Rig(features = features)
            r.write(Arm.RIGHT, 400, u16(2) + u16(4) + byteArrayOf(0x21, 0x22, 0x23, 0x24))
            r.write(Arm.RIGHT, PD, prog(track(rect(0, 80, 64, 40), 0, seg(DRAW, steps, src = 400, xL = 10, y = 100, lvl0 = 15, lvl1 = 15, h0 = h0, h1 = h1, anchor = anchor))))
            return r to r.msg(play(0, 34, PD, 1))
        }
        for ((anchor, top) in listOf(0 to 100, 1 to 98, 2 to 96)) {        // H 8 from h 4: centre 100 + (−4 >> 1), bottom 100 − 4
            val (r, ok) = withDraw(C2, 8, 8, anchor)
            assertTrue(ok, "anchor $anchor")
            assertEquals(listOf(1, 1, 2, 2, 3, 3, 4, 4), (0 until 8).map { r.shadow(10, top + it) }, "anchor $anchor: END eight rows from $top")
        }
        assertTrue(withDraw(C2, 0, 2, 0, steps = 4).second, "h0 0, h1 2 over four linear steps: H 1, 1, 2, 2")
        for ((h0, h1) in listOf(0 to 1, 1000 to 3000)) {
            val (r, ok) = withDraw(C2, h0, h1, 0, steps = 4)
            assertFalse(ok, "h0 $h0 h1 $h1: an eased H outside 1..2048 must be refused"); assertEquals(12, r.sim.refusalRecord(Arm.RIGHT)[1], "h0 $h0 h1 $h1")
        }
        withDraw(C2, 0, 0, 3).let { (r, ok) -> assertFalse(ok, "anchor 3 must be refused"); assertEquals(5, r.sim.refusalRecord(Arm.RIGHT)[1], "anchor 3") }
        withDraw(C1, 4, 4, 0).let { (r, ok) -> assertFalse(ok, "C1: h0/h1 must be 0"); assertEquals(12, r.sim.refusalRecord(Arm.RIGHT)[1], "C1: h0/h1 = 0") }
        withDraw(C1, 0, 0, 1).let { (r, ok) -> assertFalse(ok, "C1: anchor must be 0"); assertEquals(5, r.sim.refusalRecord(Arm.RIGHT)[1], "C1: anchor 0") }
    }

    // ------------------------------------------------------------------------------- the encoder of this file
    private companion object {
        const val C2 = 0x5FF
        const val C1 = 0x1FF
        const val BIND = 1 shl 4
        const val ARMED = (1 shl 2) or (1 shl 3) or BIND           // DRAW2, ENGINE, BIND
        const val RING = 4
        /** §6.1: ids 0 SINGLE_CLICK, 4 UPROLL, 5 DOWNROLL. */
        const val ID_TAP = 0; const val UPROLL = 4; const val DOWNROLL = 5
        const val NONE = 0xFFFF
        const val LOST = 0xFFFF
        const val KIND_ITEM = 1; const val KIND_PITCH = 2
        const val PA = 64; const val PB = 96; const val PC = 128; const val PD = 160
        const val WAIT = 0; const val FILL = 1; const val DRAW = 2

        fun nib(buf: ByteArray, x: Int, y: Int): Int = (buf[y * 320 + x / 2].toInt() and 0xFF).let { if (x % 2 == 0) it shr 4 else it and 0x0F }
        fun u16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        fun rect(x: Int, y: Int, w: Int, h: Int) = u16(x) + u16(y) + u16(w) + u16(h)
        /** §5.4: verb steps ease kind · src · xL xR y dxL dxR dy · lvl0 lvl1 opt 0 · table h0 h1 · anchor 0 pref0 pref1. */
        fun seg(verb: Int, steps: Int, kind: Int = 0, src: Int = 0, xL: Int = 0, y: Int = 0, lvl0: Int = 0, lvl1: Int = 0,
                h0: Int = 0, h1: Int = 0, anchor: Int = 0): ByteArray =
            byteArrayOf(verb.toByte(), steps.toByte(), 0, kind.toByte()) + u16(src) + u16(xL) + u16(0) + u16(y) + u16(0) + u16(0) + u16(0) +
                byteArrayOf(lvl0.toByte(), lvl1.toByte(), 0, 0) + u16(0) + u16(h0) + u16(h1) + byteArrayOf(anchor.toByte(), 0, 0, 0)
        fun track(area: ByteArray, backdrop: Int, vararg segs: ByteArray) =
            byteArrayOf(segs.size.toByte(), backdrop.toByte(), 0, 0) + area + segs.fold(ByteArray(0)) { a, s -> a + s }
        fun prog(vararg tracks: ByteArray): ByteArray {
            val body = tracks.fold(ByteArray(0)) { a, t -> a + t }
            return byteArrayOf(1, tracks.size.toByte()) + u16(4 + body.size) + body
        }
        fun params(p: IntArray) = (0 until 4).fold(ByteArray(0)) { a, i -> a + u16(p.getOrElse(i) { 0 }) }
        fun play(slot: Int, period: Int, prog4: Int, tag: Int) =
            byteArrayOf(26, 0, slot.toByte(), period.toByte()) + u16(prog4) + u16(tag) + u16(0) + params(IntArray(0))
        /** §6.3 SET: entry · devType · eventId · list · step s8 · slot · period · prog u16 · 4 × {kind u8, arg s16}. */
        fun set(entry: Int, id: Int, list: Int, step: Int, slot: Int, prog: Int, period: Int = 34,
                dev: Int = if (id == ID_TAP) RING else 0xFF, k: List<Pair<Int, Int>> = emptyList()) =
            byteArrayOf(27, 0, entry.toByte(), dev.toByte(), id.toByte(), list.toByte(), step.toByte(), slot.toByte(), period.toByte()) +
                u16(prog) + (0 until 4).fold(ByteArray(0)) { a, i -> val (kind, arg) = k.getOrElse(i) { 0 to 0 }; a + byteArrayOf(kind.toByte()) + u16(arg) }
        /** §6.3 LIST: list · items u16 · count u16 · shift s16 · pitch s16 · mark. */
        fun list(list: Int, items: Int, count: Int, shift: Int, pitch: Int, mark: Int) =
            byteArrayOf(27, 2, list.toByte()) + u16(items) + u16(count) + u16(shift) + u16(pitch) + byteArrayOf(mark.toByte())
        /** §6.5 COMMIT: slot · S u16 · list · cursor u16 · prog u16 · P0..P3. */
        fun commit(slot: Int, s: Int, list: Int, cursor: Int, prog: Int, p: IntArray = IntArray(0)) =
            byteArrayOf(26, 3, slot.toByte()) + u16(s) + byteArrayOf(list.toByte()) + u16(cursor) + u16(prog) + params(p)
        fun discard(slot: Int, s: Int, list: Int, cursor: Int) = byteArrayOf(26, 4, slot.toByte()) + u16(s) + byteArrayOf(list.toByte()) + u16(cursor)
        fun settle(slot: Int, s: Int, list: Int, cursor: Int, age: Int) = discard(slot, s, list, cursor).also { it[1] = 5 } + u16(age)
        /** §6.6 mode 28: off4 · x · y · options · H. */
        fun mode28(off4: Int, x: Int, y: Int, options: Int, hOut: Int) = byteArrayOf(28) + u16(off4) + u16(x) + u16(y) + byteArrayOf(options.toByte()) + u16(hOut)
    }
}
