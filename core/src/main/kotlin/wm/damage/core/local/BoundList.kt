package wm.damage.core.local

import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.motion.MotionProgram
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.EvenHubMsg

/**
 * Build C2 (`FIRMWARE.md` §6.3, §6.7 P3, P5, P9; spec DP1): the description a list bound on the glasses gives — what the
 * phone staged for it and what it SETs — so that the rules (DP2) and the trial surface (DM) speak of one object. Nothing
 * here sends or holds state across a session: it is built from a staging and replaced by the next one (a re-stage gets a
 * new [mark]).
 *
 * [list] and [slot] are the glasses' list (0..3) and the engine slot every entry of it draws in (§6.3: one slot per list,
 * a tap entry shares it). [count] rows are staged at [pitch] (kind 2's step); [items] is the item table as staged (one
 * off4 per row, kind 1's lookup) at [items4] in the cache, or null with [CfwModes.ITEMS_NONE]. [entries] are the SETs, at
 * most one per event id (the glasses fire the lowest-numbered match, so a second would never fire).
 *
 * [predict] is §6.2 steps 8–9 on the phone's own staging: what a fire from a cursor draws (the program and P0..P3), or the
 * staged edge, or the refusals the cursor and the parameters decide (18, 12). The program's own walk (5, 2, 11) is the
 * Motion DSL's ([MotionProgram]), which built the program for this staging.
 */
class BoundList(
    val list: Int,
    val slot: Int,
    val count: Int,
    val pitch: Int,
    val mark: Int,
    val items4: Int,
    val items: IntArray?,
    val entries: List<Entry>,
    /** Build C2, stage DP2 (P10): END of the program at [Entry.prog4] with P0..P3 painted into one lens's model ([pic],
     *  the compositor's shadow) exactly as the lens writes it into its shadow — the staging owner's (DM: Main's own row
     *  painters, pinned END = paint). Null: the compositor cannot follow a COMMIT here, so the rules answer with DISCARD. */
    val paintEnd: ((left: Boolean, prog4: Int, p: IntArray, pic: wm.damage.core.gfx.Gray8) -> Unit)? = null,
    /** Build C3, stage DM-0 (`FIRMWARE.md` §7.8, §7.11): a ring list — bound with LIST-RING (flags 1), so its shift and
     *  its fires move the cursor modulo [count] and kinds 1 and 2 take the virtual row v (the cursor plus the step,
     *  unwrapped). Only where the staging's margins hold the other end's rows (Main's paint wraps), at stage 5. */
    val ring: Boolean = false,
) {
    /** One SET (§6.3). [program] is the program at [prog4] as the DSL built it — null for the report-only tap entry
     *  ([prog4] = [CfwModes.PROG_NONE]). [params] are the entry's four {kind, arg}. */
    class Entry(val entry: Int, val eventId: Int, val step: Int, val period: Int, val prog4: Int,
                val params: List<CfwModes.BindParam>, val program: MotionProgram?) {
        val isTap: Boolean get() = eventId == CfwModes.EV_TAP
        val reportOnly: Boolean get() = isTap && prog4 == CfwModes.PROG_NONE
        val devType: Int get() = if (isTap) CfwModes.DEV_RING else CfwModes.DEV_ANY
        init {
            if (reportOnly != (program == null)) throw LintError("entry $entry: a program is given exactly when prog is not 0xFFFF (report-only)")
            if (program != null && program.at != prog4) throw LintError("entry $entry: prog $prog4 is not its program's place ${program.at}")
            if (params.size != 4) throw LintError("entry $entry: four parameters, not ${params.size}")
        }
    }

    init {
        if (list !in 0 until CfwModes.BIND_LISTS) throw LintError("a bound list is 0..3, not $list")
        if (slot !in 0 until CfwModes.MOTION_SLOTS) throw LintError("a bound list's slot is 0..3, not $slot")
        if (count !in 1..CfwModes.LIST_MAX) throw LintError("a bound list holds 1..${CfwModes.LIST_MAX} rows, not $count")
        if (pitch !in Short.MIN_VALUE..Short.MAX_VALUE) throw LintError("a bound list's pitch $pitch is not an s16")
        if (mark !in 0..0xFF) throw LintError("a bound list's mark $mark is not a u8")
        if ((items == null) != (items4 == CfwModes.ITEMS_NONE)) throw LintError("an item table is given exactly when its place is not 0xFFFF")
        if (items != null && items.size != count) throw LintError("the item table holds ${items.size} entries for $count rows")
        if (entries.map { it.entry }.toSet().size != entries.size) throw LintError("two SETs of one entry number")
        if (entries.map { it.eventId }.toSet().size != entries.size) throw LintError("two entries for one event id: the second never fires")
        for (e in entries) CfwModes.bindSet(e.entry, e.devType, e.eventId, list, e.step, slot, e.period, e.prog4, e.params)   // its lint
    }

    /** Build C2, stage DM2 (decisions X.2): the list as it stood before its owner's last switch of a record (Main's band
     *  form moved to another slot and the item table's word written once), kept until that write is acknowledged — a
     *  report from a fire before the switch names the old slot in its P, and the phone still holds that picture. Null:
     *  no switch is on its way. Set by the staging's owner. */
    var previous: BoundList? = null

    /** Whether a report's program [prog] and P0..P3 [p] at the cursor after the fire [c] (the 115's field 8) name a
     *  staging the phone holds — this list's prediction, or [previous]'s (decisions X.2: a report that names a staging
     *  the phone no longer holds is the only `restaged` discard). The answer's COMMIT carries the report's own (prog, P),
     *  and [paintEnd] paints the pictures they name. */
    fun names(c: Int, e: Entry, prog: Int?, p: IntArray?, wrapped: Boolean = false): Boolean {
        // the program and the parameters each judged against both stagings: a band switch changes the item table under the
        // same pans, and a programs switch (K1-1) the pans under the same table once they are counted — a fire before the
        // SETs naming the new pans land draws the OLD pan with the table as it is then (AD.2's strip switch, which made
        // this first, is gone since K1-2 stage 1). Each list's own entry for the event ([e] when it holds none).
        // Build C3, stage DM-0 (S4): a ring fire that wrapped (field 18 = 1) computed kinds 1 and 2 at the virtual row
        // v = ((c − step) mod count) + step, so the prediction is made there
        if (p == null) return false
        val lists = listOfNotNull(this, previous)
        val progOk = lists.any { (it.entryFor(e.eventId) ?: e).prog4 == prog }
        val pOk = lists.any { l -> val le = l.entryFor(e.eventId) ?: e
            c in 0 until l.count && l.paramsAt(c, le, l.virtualRow(c, le.step, wrapped)).let { it != null && it !== REFUSED_12 && it.contentEquals(p) } }
        return progOk && pOk
    }

    /** Build C3, stage DM-0 (S4, `FIRMWARE.md` §7.8): the virtual row a fire of [step] that landed on [c] computed its
     *  parameters at — [c] itself unless the 115 said it wrapped (field 18 = 1, [wrapped]): then the row before the move,
     *  unwrapped by the step, ((c − step) mod count) + step (−1 for an up notch from row 0, [count] for a down notch from
     *  the last row). */
    fun virtualRow(c: Int, step: Int, wrapped: Boolean): Int = if (!wrapped) c else Math.floorMod(c - step, count) + step

    /** Build C3, stage DM-0 (S4): how far the cursor [a] lies from the row [b] — on a ring list modulo the count, the
     *  shorter way round (a wrap notch from the last row to row 0 is one row); else the plain difference. */
    fun distance(a: Int, b: Int): Int = if (!ring) kotlin.math.abs(a - b) else Math.floorMod(a - b, count).let { minOf(it, count - it) }

    /** The entry an event id fires, or null. */
    fun entryFor(eventId: Int): Entry? = entries.firstOrNull { it.eventId == eventId }

    /** The scroll entries' largest |step| (§6.7's tap rule (4)); 0 with none. */
    val maxStep: Int get() = entries.filter { !it.isTap }.maxOfOrNull { kotlin.math.abs(it.step) } ?: 0

    /** The SET of [e], as §6.3 lays it out. */
    fun set(e: Entry): ByteArray = CfwModes.bindSet(e.entry, e.devType, e.eventId, list, e.step, slot, e.period, e.prog4, e.params)

    /** Every entry's SET, in entry order. */
    fun sets(): List<ByteArray> = entries.sortedBy { it.entry }.map { set(it) }

    /** The LIST of this staging with [shift] (0: the cursor kept, or lost when out of range) at [mark]; a ring list's is
     *  LIST-RING with flags 1 (DM-0, S5: a plain LIST clears the RING flag on the glasses, §7.8). */
    fun listMessage(shift: Int = 0, mark: Int = this.mark): ByteArray =
        if (ring) CfwModes.bindListRing(list, items4, count, shift, pitch, mark, true) else CfwModes.bindList(list, items4, count, shift, pitch, mark)

    /** The item table's bytes as the cache holds them (`count u16 off4 entries`), or null with none. */
    fun itemTable(): ByteArray? = items?.let { t -> ByteArray(2 * t.size).also { b ->
        t.forEachIndexed { i, v -> b[2 * i] = (v and 0xFF).toByte(); b[2 * i + 1] = ((v shr 8) and 0xFF).toByte() } } }

    /** Every area the slot's programs draw in, (left, right) — what a batch's drawing subs must not meet for a COMMIT
     *  (§6.5's batch order, P3). */
    val areas: List<Pair<Rect, Rect>> get() = entries.mapNotNull { it.program }.flatMap { p -> p.tracks.map { it.areaL to it.areaR } }

    /** What a fire of [entry] from [cursor] does on a lens whose list is this staging (§6.2 steps 8–9). */
    sealed class Fire {
        /** It draws [prog4] with [p], and the list's cursor becomes [cursor] (unchanged for a tap). A report-only tap
         *  entry "draws" nothing: [p] is null and [prog4] is [CfwModes.PROG_NONE]. */
        class Drew(val cursor: Int, val prog4: Int, val p: IntArray?) : Fire()
        /** The staged edge: nothing drawn, the cursor kept. */
        object Edge : Fire()
        /** Refused with [reason] — 18 a lost cursor, 12 a kind-2 parameter outside s16. */
        class Refused(val reason: Int) : Fire()
    }

    fun predict(cursor: Int, entry: Entry): Fire {
        if (cursor == LOST) return Fire.Refused(18)
        if (entry.reportOnly) return Fire.Drew(cursor, CfwModes.PROG_NONE, null)
        // DM-0 (§7.8): on a ring list one row with a step is the edge; else c' = (cursor + step) mod count, the parameters
        // at the virtual row v = cursor + step
        if (ring && count == 1 && entry.step != 0) return Fire.Edge
        val v = cursor + entry.step
        val c = if (ring) Math.floorMod(v, count) else v
        if (c !in 0 until count) return Fire.Edge
        val r = paramsAt(c, entry, v)
        return when {
            r == null -> Fire.Edge
            r === REFUSED_12 -> Fire.Refused(12)
            else -> Fire.Drew(c, entry.prog4, r)
        }
    }

    /** The parameters a fire of [entry] that lands at [cPrime] computes (P3's prediction "at the 115's cursor"), in index
     *  order, the first that gives the edge or 12 deciding (§6.2 step 9, ruled 2026-11-18): null for the edge,
     *  [REFUSED_12] (the one array of that identity) for a kind-2 value outside s16. [v]: the virtual row (DM-0, §7.8) —
     *  a ring list takes kind 1 at (v + arg) mod count and kind 2 at v × pitch + arg; [cPrime] elsewhere. */
    fun paramsAt(cPrime: Int, entry: Entry, v: Int = cPrime): IntArray? {
        val out = IntArray(4)
        for ((i, p) in entry.params.withIndex()) {
            out[i] = when (p.kind) {
                CfwModes.KIND_ARG -> p.arg
                CfwModes.KIND_ITEM -> {
                    val t = items ?: return null
                    val k = if (ring) Math.floorMod(v + p.arg, count) else cPrime + p.arg
                    if (k !in 0 until count) return null
                    // the lens carries the entry's 16 bits as an s16 P (§6.2 step 9, §6.5 fields 10–13; `parseApplied`
                    // reads them so): an off4 past 32767 — a record in the top half of a 256 KiB cache — is negative
                    // (build C2, stage DM2: the pin against the simulator's fire found it on Main's 256 KiB region)
                    t[k].toShort().toInt()
                }
                else -> {
                    val x = v.toLong() * pitch + p.arg
                    if (x !in Short.MIN_VALUE..Short.MAX_VALUE) return REFUSED_12
                    x.toInt()
                }
            }
        }
        return out
    }

    companion object {
        /** A lost cursor (§6.3 LIST, 115's field 8). */
        const val LOST = 0xFFFF
        /** [paramsAt]'s answer for a fire refused 12: compared by identity. */
        val REFUSED_12 = IntArray(0)

        /** §6.1: the stock message each bound event id makes when stock sends one — id 4 (UPROLL) direction 2, which the
         *  shell reads as [EvenHubMsg.EV_SCROLL_BOTTOM] (the next row); id 5 (DOWNROLL) direction 1, EV_SCROLL_TOP; id 0
         *  the click. V for the directions (`FIRMWARE.md` §6.1); I that the text event's type is that direction (the
         *  first armed session's journal confirms it, spec §8). Null for an id no entry binds. */
        fun stockEvent(eventId: Int): Int? = when (eventId) {
            CfwModes.EV_UPROLL -> EvenHubMsg.EV_SCROLL_BOTTOM
            CfwModes.EV_DOWNROLL -> EvenHubMsg.EV_SCROLL_TOP
            CfwModes.EV_TAP -> EvenHubMsg.EV_CLICK
            else -> null
        }

        /** The row step the shell gives that stock message (`Shell.handleInput`: EV_SCROLL_BOTTOM scrolls by +1), and so
         *  the step a staged list's scroll entry carries: +1 for id 4, −1 for id 5, 0 for a tap. */
        fun rowStep(eventId: Int): Int = when (eventId) { CfwModes.EV_UPROLL -> 1; CfwModes.EV_DOWNROLL -> -1; else -> 0 }
    }
}
