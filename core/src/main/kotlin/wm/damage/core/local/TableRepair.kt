package wm.damage.core.local

import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.DamageMsg

/**
 * Build C2, the table repair (`FIRMWARE.md` §6.7, ruled 2026-11-18): a mode 27 that did not land — lost, or refused on
 * one lens — leaves the lens's table different from the phone's belief, and the phone converges on the evidence a later
 * 115 or stock message gives by re-sending the ONE object the evidence names, journaled, in the next batch, after its
 * drawing subs. Each re-send is idempotent (a SET writes its entry whole, a CLEAR removes one), so a spurious one
 * changes nothing. A stale LIST needs no evidence since the rulings on stage DP2 (`C2-decisions.md` U.1): every answer
 * naming a list rides behind that list's LIST with shift 0, so a report whose mark is not the phone's is answered by a
 * DISCARD that carries the LIST again.
 *
 * The evidence for a 115, in this order (the first that holds names the object; a second difference shows at the next
 * report): an entry the phone does not hold → its CLEAR; a mark that is not the phone's → nothing more is read (the
 * answer's riding LIST repairs it, and fields 10–14 from another staging say nothing); a tap's form
 * that disagrees with `Taps` → the tap entry's SET; parameters that differ from the phone's prediction, or a fire refused
 * 5 or 12, with mark and form agreeing → the SET of the entry field 4 names. Then the stock message's: a scroll message
 * applied as unreported when no scroll 115 of that list was handled since the last SETTLE sent → the list's scroll
 * entries' SETs, in the batch that carries the next SETTLE.
 *
 * A batch that already carries a message for the object (a SET or CLEAR of that entry) sends no
 * re-send; a SET is never re-sent for a list that is not in front (the batch that brings it back SETs its entries).
 * State (`FORK.md` §3.8): the owed objects, cleared at the batch that carries them and at a session start; per list,
 * whether a scroll 115 was handled since the last SETTLE sent, cleared at each SETTLE sent. Nothing waits.
 */
class TableRepair {
    sealed class Owed {
        data class Clear(val entry: Int) : Owed()
        data class Set(val entry: Int) : Owed()
        data class ScrollSets(val list: Int) : Owed()
    }

    /** The objects owed a re-send, each with the evidence that named it (for the `repair` note). */
    private val owed = LinkedHashMap<Owed, String>()
    /** Lists with a scroll 115 handled since the last SETTLE sent (the fifth kind's test). */
    private val scrolledSinceSettle = HashSet<Int>()
    /** Re-sends by kind, for the `local` note. */
    val sent = java.util.TreeMap<String, Int>()

    fun sessionStart() { owed.clear(); scrolledSinceSettle.clear() }
    fun settleSent() { scrolledSinceSettle.clear() }

    /** An owed re-send other than the fifth kind's, which waits for a batch with a SETTLE. */
    fun owedNow(): Boolean = owed.keys.any { it !is Owed.ScrollSets }
    fun owedObjects(): Set<Owed> = owed.keys.toSet()

    /** One handled 115's evidence (it never changes how the report is judged). */
    fun onReport(a: DamageMsg.Applied, rules: LocalRules) {
        val entry = a.entry ?: return                    // a 3 or 14 body carries no entry, list or mark: no evidence
        val list = a.list ?: return
        if (!a.isTap) scrolledSinceSettle += list
        val f = rules.front
        // 1. an entry the phone does not hold (never SET this session, or CLEARed since: a cover, a switch to `phone`)
        if (!rules.holdsEntry(entry)) { owe(Owed.Clear(entry), "entry $entry not held"); return }
        // 2. a mark that is not the phone's current mark for its list: the answer's riding LIST (U.1) re-sends it
        if (a.mark != rules.markOf(list)) return
        val held = rules.heldEntry(entry)?.entry ?: return
        // 3. a tap's form that disagrees with `Taps` (read on outcome 0 and refused 17 or 19, the bodies that carry 10–14
        //    for an entry with a program; 19 is 17's twin, §7.3, §7.14)
        if (a.isTap && (a.outcome == DamageMsg.APPLIED_DREW || (a.outcome == DamageMsg.APPLIED_REFUSED && (a.reason == 17 || a.reason == 19)))) {
            val carried = a.params != null
            val wantPress = f?.let { rules.tapEntry(it) }?.reportOnly == false
            if (carried != wantPress) { if (f != null && f.list == list) owe(Owed.Set(entry), "tap form ${if (carried) "press" else "report-only"} under ${rules.taps.word}"); return }
        }
        // 4. parameters that differ from the phone's prediction, or a fire refused 5 or 12
        val refused512 = a.outcome == DamageMsg.APPLIED_REFUSED && (a.reason == 5 || a.reason == 12)
        // (judged as P3 judges it: the staging before or after a band form's switch, `BoundList.names`)
        val differs = a.params?.let { p ->
            val c = a.cursor ?: return@let false
            !(f != null && f.list == list && f.names(c, held, a.prog, p))
        } ?: false
        if ((refused512 || differs) && f != null && f.list == list)
            owe(Owed.Set(entry), if (refused512) "fire refused ${a.reason}" else "parameters differ from the prediction")
    }

    /** A stock scroll message applied as unreported (P5: no owe, a scroll entry held for the list in front). */
    fun onUnreportedScroll(list: Int, rules: LocalRules) {
        if (list !in scrolledSinceSettle && rules.front?.list == list) owe(Owed.ScrollSets(list), "unreported scroll message")
    }

    private fun owe(o: Owed, why: String) { if (o !in owed) owed[o] = why }

    /**
     * The re-sends this batch carries: every owed object not already carried by the batch ([listed] lists, [setEntries]
     * and [clearedEntries] entries), the fifth kind only when the batch carries a SETTLE ([settleHere]); a SET or LIST
     * for a list not in front is dropped (the return SETs it). Each one taken is journaled.
     */
    fun take(rules: LocalRules, settleHere: Boolean, listed: Set<Int>, setEntries: Set<Int>, clearedEntries: Set<Int>): List<Owed> {
        val out = ArrayList<Owed>()
        val f = rules.front
        for ((o, why) in owed.toList()) {
            val keep = when (o) {
                is Owed.ScrollSets -> !settleHere
                else -> false
            }
            if (keep) continue
            owed.remove(o)
            val carriedBy: String? = when (o) {
                is Owed.Clear -> if (o.entry in clearedEntries || o.entry in setEntries) "the batch's own message" else null
                is Owed.Set -> if (o.entry in setEntries || o.entry in clearedEntries) "the batch's own message"
                    else if (f == null || rules.heldEntry(o.entry)?.list != f.list || rules.entriesOf(f).none { it.entry == o.entry }) "list not in front" else null
                is Owed.ScrollSets -> if (f == null || f.list != o.list) "list not in front"
                    else if (rules.entriesOf(f).filter { !it.isTap }.all { it.entry in setEntries }) "the batch's own SETs" else null
            }
            if (carriedBy != null) continue
            val kind = when (o) { is Owed.Clear -> "clear"; is Owed.Set -> "set"; is Owed.ScrollSets -> "scrollsets" }
            sent[kind] = (sent[kind] ?: 0) + 1
            rules.repairNote("evidence=\"$why\" object=$o")
            out += o
        }
        return out
    }

    companion object {
        /** The entry numbers a ScrollSets names on [list] (for tests). */
        fun scrollEntries(list: BoundList): List<Int> = list.entries.filter { it.eventId != CfwModes.EV_TAP }.map { it.entry }
    }
}
