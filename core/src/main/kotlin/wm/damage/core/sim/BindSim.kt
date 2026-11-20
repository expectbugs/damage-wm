package wm.damage.core.sim

import wm.damage.core.wire.Pb

/**
 * `FIRMWARE.md` §6 (build C2): local input on ONE lens as the simulator models it — the binding table and its lists
 * (image mode 27, §6.3), the lens's sequence S, the input hook and its fire (§6.2), the applied-state report it sends
 * (notify field 115, §6.5) and telemetry 35–39 (§6.8). Written from §6's text and the lead's decisions on the build
 * spec alone, never from the fork's C, so that the conformance vectors compare two readings of one contract. The
 * local instance a fire opens, HOLD and COMMIT · DISCARD · SETTLE are [MotionSim]'s.
 *
 * What the model leaves out, as [MotionSim] does: it has one thread, so the hint read without the engine word and the
 * reads under it see one state, and a claim fails only on a word a test plants ([MotionSim.holdWordForTest]); nothing
 * is timed, so field 16 (the fire's µs) is carried as 0 and telemetry 37 (a µs figure) is left out, as field 34 is; a
 * report's send never fails here, so field 38 stays 0. Where §6 allows two readings, the one taken is marked READING.
 */
class BindSim(private val motion: MotionSim, private val effects: () -> Boolean = { false }) {

    /** §6.3 SET: one entry. A tap entry whose [prog] is 0xFFFF is report-only; its presence is its own mark. */
    class Entry(val devType: Int, val eventId: Int, val list: Int, val step: Int, val slot: Int, val period: Int,
                val prog: Int, val kinds: IntArray, val args: IntArray) {
        val tap: Boolean get() = eventId == ID_TAP
        val reportOnly: Boolean get() = tap && prog == NONE
    }

    /** §6.3 LIST: one list's staged range, and the S of its newest fire (null: none since the table was emptied). */
    class BList {
        var items = NONE; var count = 0; var cursor = 0; var pitch = 0; var mark = 0
        var newest: Int? = null
        /** §7.8 (build C3): the RING flag — set by LIST-RING's flags bit 0, cleared by a plain LIST and the empty table. */
        var ring = false
    }

    /** What the hook needs of its lens: the context's existence (the hook's peek), the flags, the cache, the clocks,
     *  the lease predicate (noticing a lapse is a release point, §5.6), the two notify senders (RIGHT only sends), the
     *  panel's stale mark. */
    interface Lens {
        val made: Boolean
        val flags: Int
        val cache: ByteArray?
        val tick: Long
        val uptime: Long
        val left: Boolean
        fun leaseActive(): Boolean
        fun send114(body: ByteArray)
        fun send115(body: ByteArray)
        fun markStale()
    }

    // ------------------------------------------------------------------------------------------------- state
    /** §6.3 "the empty table": 0 when the context is made and after every release point. */
    var live = false
        private set
    val entries = arrayOfNulls<Entry>(ENTRIES)
    val lists = Array(LISTS) { BList() }
    /** §6.2 step 4: the lens's u16 sequence (telemetry 36). */
    var seq = 0
        private set
    /** Telemetry 35: matched events since boot (every S taken, whatever its outcome). */
    var matched = 0L
        private set
    /** §6.1: the EvenHub page in front (`FW_MODE(UI_CTX)` reads 0xE0) — the vectors' `page` op; true by default. */
    var page = true
    /** The stock dispatcher's calls since the last take (the vectors' `K`). */
    private var stockCalls = 0
    fun takeStockCalls(): Int = stockCalls.also { stockCalls = 0 }

    /** §6.3 and §6.11: a release point clears `bind_live` and S (one store each beside the engine's settlement). The
     *  entries and lists stay in memory; the next accepted mode 27 empties them. */
    fun releasePoint() { live = false; seq = 0 }

    /** Tests only (decisions AZ.3): a lens that took [s] matched events since its last release point — what an earlier
     *  process's session left on it. No wire semantics change. */
    internal fun seqForTest(s: Int) { seq = s and 0xFFFF; matched = s.toLong() }

    /** Telemetry 39: bit n for event id n with a live entry on this lens (bit 0 the tap entry of either form, 4 and 5
     *  the scroll entries), 0 while `bind_live` is clear. */
    fun field39(): Int = if (!live) 0 else entries.fold(0) { v, e -> if (e == null) v else v or (1 shl e.eventId) }

    /** §6.2 step 2: the lowest-numbered entry matching the record, or −1 — id 4 or 5 against a scroll entry of that
     *  id (any devType), id 0 with devType 4 against a tap entry. */
    fun match(devType: Int, eventId: Int): Int = entries.indexOfFirst { e ->
        e != null && e.eventId == eventId && (if (e.tap) devType == DEV_RING else eventId == ID_UP || eventId == ID_DOWN)
    }

    // -------------------------------------------------------------------------------------------- the hook
    /**
     * `damage_input_hook(record, len)` (§6.2), on this lens: it never consumes — every path ends in the stock call
     * ([stockCalls]), after its own work. [eventId] and [value] are the record's u32 fields; only the id is read.
     */
    fun hook(lens: Lens, devType: Int, eventId: Long, @Suppress("UNUSED_PARAMETER") value: Long) {
        // 1: peek, never get — no context, no work
        if (!lens.made) { stockCalls++; return }
        // 2: the hint, read without the word — BIND armed, `bind_live`, a matching entry; 3: the EvenHub page
        val ei = if (lens.flags and FLAG_BIND != 0 && live && eventId in 0L..0xFFL) match(devType, eventId.toInt()) else -1
        if (ei < 0 || !page) { stockCalls++; return }
        // 4: one add on the lens's u16 S, whatever follows
        val s = (seq + 1) and 0xFFFF
        seq = s
        matched++
        val id = eventId.toInt()
        // 5: the lease — a lapse it notices is a release point on this task (S and `bind_live` go to 0; this fire
        // keeps the S it took); 6: the word, by a compare-exchange from IDLE
        val early = when {
            !lens.leaseActive() -> REF_NO_LEASE
            motion.wordHeld -> REF_BUSY
            else -> 0
        }
        if (early != 0) {
            lens.send115(Pb.cat(Pb.v(1, s), Pb.v(2, devType), Pb.v(3, eventId), Pb.v(5, OUTCOME_REFUSED), Pb.v(6, early), Pb.v(15, lens.uptime)))
            stockCalls++
            return
        }
        motion.claim()
        val report = try { fire(lens, s, ei, devType, id) } finally {
            motion.letDown()                                // 13: every exit lets the word down before any send
            if (motion.takeStaleMark()) lens.markStale()
        }
        // 14: the engine flag — the model's thread plans from the table at its next wake (§5.2); nothing to set.
        // 15: RIGHT sends the captured 114 bodies, then the report
        for (b in motion.takeUiSends()) lens.send114(b)
        lens.send115(report)
        stockCalls++                                        // 16: the stock call with the unchanged record
    }

    /** §6.2 steps 7–12 under the engine word; returns the report's body. */
    private fun fire(lens: Lens, s: Int, ei: Int, devType: Int, id: Int): ByteArray {
        val e = entries[ei]!!
        val l = lists[e.list]
        fun body(outcome: Int, reason: Int = 0, p: IntArray? = null, wrapped: Boolean = false): ByteArray = Pb.cat(
            Pb.v(1, s), Pb.v(2, devType), Pb.v(3, id), Pb.v(4, ei), Pb.v(5, outcome),
            Pb.v(6, reason),                                // §6.5: "1–9 and 15–17" — 0 unless refused (C2-decisions I.7)
            Pb.v(7, e.list), Pb.v(8, l.cursor), Pb.v(9, e.slot),
            if (p != null) Pb.cat(Pb.v(10, p[0] and 0xFFFF), Pb.v(11, p[1] and 0xFFFF), Pb.v(12, p[2] and 0xFFFF),
                                  Pb.v(13, p[3] and 0xFFFF), Pb.v(14, e.prog)) else ByteArray(0),
            Pb.v(15, lens.uptime), Pb.v(16, 0), Pb.v(17, l.mark),
            // §7.8 (C3): field 18 = 1 when the virtual row is not c' (a drawn ring fire). READING (C3): absent otherwise
            if (wrapped) Pb.v(18, 1) else ByteArray(0))
        // 7: each flag once under the word
        val f = lens.flags
        if (f and FLAG_DRAW2 == 0 || f and FLAG_ENGINE == 0 || f and FLAG_BIND == 0 || !live) return body(OUTCOME_REFUSED, REF_UNARMED)
        // §6.2 step 7 (C3): EFFECTS in the same once; a RING list, or an entry with a kind-3 parameter, with it clear → 15
        val armed = effects() && f and FLAG_EFFECTS != 0
        if (effects() && !armed && (l.ring || e.kinds.any { it == KIND_CURSOR })) return body(OUTCOME_REFUSED, REF_UNARMED)
        // 8: a lost cursor
        if (l.cursor == LOST) return body(OUTCOME_REFUSED, REF_LOST)
        // the report-only entry (§6.2): steps 9, 10, 12 and 14 do nothing; step 11's test reads its slot's local flag
        if (e.reportOnly) {
            if (motion.slots[e.slot]?.local == true) return body(OUTCOME_REFUSED, REF_LOCAL)
            return body(OUTCOME_DREW)
        }
        // 9: the new cursor and the parameters, in index order: the first that gives an edge or 12 decides (ruled
        // 2026-11-18, C2-decisions G.3)
        // §7.8 (C3): a RING list — count 1 with a step is the edge (a tap is not); else c' = (cursor + step) mod count
        // (Euclidean) and the virtual row v = cursor + step, unwrapped
        if (l.ring && l.count == 1 && e.step != 0) return body(OUTCOME_EDGE)
        val virt = l.cursor + e.step
        val c1 = if (l.ring) Math.floorMod(virt, l.count) else virt
        if (c1 !in 0 until l.count) return body(OUTCOME_EDGE)
        val p = IntArray(4)
        for (i in 0 until 4) {
            when (e.kinds[i]) {
                KIND_ARG -> p[i] = e.args[i]
                KIND_ITEM -> {
                    // a RING list takes the item at (v + arg) mod count; no item table is the edge
                    val k = if (l.ring) Math.floorMod(virt + e.args[i], l.count) else c1 + e.args[i]
                    if (l.items == NONE || k !in 0 until l.count) return body(OUTCOME_EDGE)
                    p[i] = itemAt(lens.cache, l.items, k).toShort().toInt()
                }
                // §7.8 (C3) kind 3: the cursor BEFORE the move + arg (s32, outside s16 → 12)
                KIND_CURSOR -> {
                    val v = l.cursor + e.args[i]
                    if (v !in -32768..32767) return body(OUTCOME_REFUSED, REF_VALUE)
                    p[i] = v
                }
                else -> {
                    val v = virt * l.pitch + e.args[i]                   // s32: |v·pitch| < 2^26; v = c' off a ring
                    if (v !in -32768..32767) return body(OUTCOME_REFUSED, REF_VALUE)
                    p[i] = v
                }
            }
        }
        // 10: §5.5's checks from the walk onward, `self` refused (5); §7.2 (C3) a bound program's rules, EFFECTS as read
        val v = motion.validate(lens.cache, e.prog, p, MotionSim.Op.FIRE, armed)
        val prog = v as? MotionSim.Prog ?: return body(OUTCOME_REFUSED, -(v as Int))
        // §7.12 (C3): the step budget after the values — the local instance's C in the sum over its close set's leavings
        val cost = if (effects()) motion.cost(prog) else 0L
        if (effects() && !motion.fireBudgetOk(prog, e.slot, cost)) return body(OUTCOME_REFUSED, REF_VALUE)
        motion.threadExists = true                          // the thread and its flags at first use (11 never: static memory)
        // 11: the close set; §7.3 (C3) one holding an owning instance is refused 19, nothing closed, before 17's test; a
        // tap fire whose set holds a local instance (a done one counts) is refused 17, nothing closed
        val set = motion.closeSet(e.slot, prog)
        if (effects() && motion.holdsOwner(set)) return body(OUTCOME_REFUSED, REF_OWNER, p)
        if (e.tap && set.any { motion.slots[it]!!.local }) return body(OUTCOME_REFUSED, REF_LOCAL, p)
        for (i in set) motion.closeForFire(i, e.slot, lens.uptime)
        // 12: the local instance; the list's newest S and cursor
        motion.openLocal(e.slot, s, e.period, e.prog, prog, lens.tick, lens.left, cost)
        l.newest = s
        l.cursor = c1
        return body(OUTCOME_DREW, 0, p, wrapped = virt != c1)
    }

    /** An item table's entry [k] — a u16 off4 — at off4 [items]. §6.3's LIST put `[items·4, items·4 + 2·count)` inside
     *  the cache, and only a release point frees the cache, which clears `bind_live` (the fire then stops at 15): a
     *  table outside the cache here is a defect of this model, never a value. */
    private fun itemAt(cache: ByteArray?, items: Int, k: Int): Int {
        val at = items * 4 + 2 * k
        check(cache != null && at + 2 <= cache.size) { "an item table outside the cache at a fire" }
        return MotionSim.u16(cache, at)
    }

    // ------------------------------------------------------------------------------------------- mode 27
    /**
     * `[27][sub]…` (§6.3), alone or a batch's sub-message: the container's list (8: [inStep]) → length (1) → the lease
     * (3) → the engine word claimed (14) → DRAW2 (9) → ENGINE (15) → BIND (15) → values (12) → LIST's item table inside the cache (5)
     * → SET's program record, its own structure and no `self` (5) — then the write. Returns 0 or the reason; a refusal
     * changes nothing and sends no notify. Not gated: no present, no fid.
     *
     * While `bind_live` is 0 the table is empty for every reader (C2-decisions I.1): SET's one-slot-per-list test reads
     * no entry, so a re-staging that assigns slots differently after a release point is taken.
     */
    fun op27(lens: Lens, src: ByteArray, inStep: Boolean): Int {
        if (inStep) return REF_MODE
        val sub = if (src.size >= 2) src[1].toInt() and 0xFF else -1
        // CLEAR and an unknown sub: 3; §6.3 (C3) LIST-RING 13 on a bit-11 build (an unknown sub elsewhere)
        val need = when { sub == SUB_SET -> 23; sub == SUB_LIST -> 12; sub == SUB_RING && effects() -> 13; else -> 3 }
        if (sub < 0 || src.size != need) return REF_LENGTH
        if (!lens.leaseActive()) return REF_NO_LEASE
        if (motion.wordHeld) return REF_BUSY
        motion.claim()
        try {
            val f = lens.flags
            if (f and FLAG_DRAW2 == 0) return REF_DRAW2
            if (f and FLAG_ENGINE == 0) return REF_UNARMED         // ruled 2026-11-18 (C2-decisions G.2): ENGINE, then BIND
            if (f and FLAG_BIND == 0) return REF_UNARMED
            // §6.3 (C3): LIST-RING needs EFFECTS (15), after BIND
            val armed = effects() && f and FLAG_EFFECTS != 0
            if (sub == SUB_RING && effects() && !armed) return REF_UNARMED
            fun u8(i: Int) = src[i].toInt() and 0xFF
            val reason = when (sub) {
                SUB_SET -> {
                    val (entry, e) = parseSet(src)
                    val scroll = (e.eventId == ID_UP || e.eventId == ID_DOWN) && e.devType == DEV_ANY && e.step != 0
                    val tap = e.eventId == ID_TAP && e.devType == DEV_RING && e.step == 0
                    if (entry >= ENTRIES || e.list >= LISTS || e.slot >= MotionSim.SLOTS || e.period !in MotionSim.P_MIN..MotionSim.P_MAX ||
                        e.kinds.any { it > KIND_PITCH && !(it == KIND_CURSOR && armed) } || !(scroll || tap) ||
                        (live && entries.withIndex().any { (j, o) -> j != entry && o != null && o.list == e.list && o.slot != e.slot })) REF_VALUE
                    // a report-only tap entry names no program; a scroll entry naming 0xFFFF fails here (no program fits)
                    else if (!(tap && e.prog == NONE) && !motion.ownStructureOk(lens.cache, e.prog, armed)) REF_RECORD
                    else 0
                }
                SUB_CLEAR -> if (u8(2) >= ENTRIES && u8(2) != ALL) REF_VALUE else 0
                // sub 4 is LIST-RING on a bit-11 build only; elsewhere an unknown sub (3 bytes, 12 here)
                SUB_LIST, SUB_RING -> if (sub == SUB_RING && !effects()) REF_VALUE else {
                    val list = u8(2); val items = MotionSim.u16(src, 3); val count = MotionSim.u16(src, 5)
                    val cache = lens.cache
                    if (list >= LISTS || count !in 1..COUNT_MAX || (sub == SUB_RING && u8(12) and 0xFE != 0)) REF_VALUE
                    else if (items != NONE && (cache == null || items * 4 + 2 * count > cache.size)) REF_RECORD
                    else 0
                }
                else -> REF_VALUE                           // an unknown sub, 3..255: 3 bytes, then 12 here
            }
            if (reason != 0) return reason
            // the first mode 27 accepted while `bind_live` is 0 empties every entry and every list first
            if (!live) {
                entries.fill(null)
                for (l in lists) { l.items = NONE; l.count = 0; l.cursor = 0; l.pitch = 0; l.mark = 0; l.newest = null; l.ring = false }
            }
            when (sub) {
                SUB_SET -> parseSet(src).let { (entry, e) -> entries[entry] = e }
                SUB_CLEAR -> if (u8(2) == ALL) entries.fill(null) else entries[u8(2)] = null
                else -> {
                    val l = lists[u8(2)]
                    val old = l.cursor
                    l.items = MotionSim.u16(src, 3); l.count = MotionSim.u16(src, 5); l.pitch = MotionSim.s16(src, 9); l.mark = u8(11)
                    // §7.8 (C3): LIST-RING sets RING to its flags bit 0, a plain LIST clears it
                    l.ring = sub == SUB_RING && u8(12) and 1 != 0
                    // always applied: old + shift inside 0..count−1, else lost; a lost cursor stays lost. With RING the
                    // shift is modulo the count (Euclidean), never lost by the shift
                    val moved = old + MotionSim.s16(src, 7)
                    l.cursor = if (old == LOST) LOST else if (l.ring) Math.floorMod(moved, l.count) else if (moved in 0 until l.count) moved else LOST
                }
            }
            live = true
            return 0
        } finally {
            motion.letDown()
        }
    }

    /** SET's 21 bytes after `[27][0]`: `entry · devType · eventId · list · step s8 · slot · period · prog u16 ·
     *  4 × {kind u8, arg s16}`. */
    private fun parseSet(src: ByteArray): Pair<Int, Entry> {
        fun u8(i: Int) = src[i].toInt() and 0xFF
        return u8(2) to Entry(u8(3), u8(4), u8(5), src[6].toInt(), u8(7), u8(8), MotionSim.u16(src, 9),
            IntArray(4) { u8(11 + 3 * it) }, IntArray(4) { MotionSim.s16(src, 12 + 3 * it) })
    }

    /** §6.5 COMMIT: the list's cursor := COMMIT's cursor only if S is that list's newest fire. */
    fun commitCursor(list: Int, s: Int, cursor: Int) { lists[list].let { if (it.newest == s) it.cursor = cursor } }

    /** §6.5 DISCARD and SETTLE: the named list's cursor, absolute, clearing lost. */
    fun setCursor(list: Int, cursor: Int) { if (list != ALL) lists[list].cursor = cursor }

    /** §6.5 values (12): a named list's cursor below its count — count 0 while `bind_live` is 0 (the table is empty for
     *  every reader, C2-decisions I.1), so a COMMIT, DISCARD or SETTLE naming a list is then refused and writes no cursor. */
    fun cursorOk(list: Int, cursor: Int): Boolean = list == ALL || (live && cursor < lists[list].count)

    companion object {
        const val ENTRIES = 8
        const val LISTS = 4
        const val COUNT_MAX = 1024
        /** §6.3: LIST's "none" (an item table, a program), and 0xFF for "all" (CLEAR) or "none" (a list, §6.5). */
        const val NONE = 0xFFFF
        const val ALL = 0xFF
        /** §6.3: the lost cursor. */
        const val LOST = 0xFFFF
        const val SUB_SET = 0; const val SUB_CLEAR = 1; const val SUB_LIST = 2
        /** §7.8 (build C3): mode 27 sub 4 LIST-RING. */
        const val SUB_RING = 4
        /** §6.1: event ids 0 SINGLE_CLICK, 4 UPROLL, 5 DOWNROLL; devType 4 the ring; a scroll entry's devType 0xFF any. */
        const val ID_TAP = 0; const val ID_UP = 4; const val ID_DOWN = 5
        const val DEV_RING = 4; const val DEV_ANY = 0xFF
        /** §6.2 step 9: a parameter's kind — 0 the arg · 1 the item table's entry at c' + arg · 2 c' × pitch + arg. */
        const val KIND_ARG = 0; const val KIND_ITEM = 1; const val KIND_PITCH = 2
        /** §7.8 (build C3): kind 3, the cursor before the move + arg (while EFFECTS is armed). */
        const val KIND_CURSOR = 3
        /** §6.5 field 5. */
        const val OUTCOME_DREW = 0; const val OUTCOME_EDGE = 1; const val OUTCOME_REFUSED = 2
        /** §3 / §5.8 / §6.8 flag bits: DRAW2 2, ENGINE 3, BIND 4. */
        const val FLAG_DRAW2 = 1 shl 2
        const val FLAG_ENGINE = 1 shl 3
        const val FLAG_BIND = 1 shl 4
        /** §7.9 (build C3): flag bit 6 EFFECTS. */
        const val FLAG_EFFECTS = 1 shl 6
        /** §4 / §5.8 / §6.8 reasons. */
        const val REF_LENGTH = 1
        const val REF_NO_LEASE = 3
        const val REF_RECORD = 5
        const val REF_MODE = 8
        const val REF_DRAW2 = 9
        const val REF_VALUE = 12
        const val REF_BUSY = 14
        const val REF_UNARMED = 15
        const val REF_LOCAL = 17
        const val REF_LOST = 18
        /** §7.9 (build C3): a fire whose close set holds an owning instance. */
        const val REF_OWNER = 19
    }
}
