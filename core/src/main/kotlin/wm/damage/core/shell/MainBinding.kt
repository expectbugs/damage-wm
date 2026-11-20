package wm.damage.core.shell

import wm.damage.core.local.BoundList

/**
 * Build C2, stage DM2 (`C2-spec.md` §6.4 "Where it lives", "Kept current"; `C2-decisions.md` B.4; `FIRMWARE.md` §6.7
 * P9): the state of Main's list as the glasses hold it — the staging, its records in the shell's stage registry, which
 * rows are stale and what their re-stage owes. Context for the reader: Adam's own glasses on his own firmware fork
 * (display-rendering work on a personal device). Nothing here sends or waits: the shell's pump writes ([Shell]'s
 * `pumpMainBind`), the cache count proves (field 17), and the shell tells this object what happened. Loop-confined.
 *
 * **The six questions (`FORK.md` §3.8) for its state.**
 * - The staged records ([staging], [records]). (a) Written at a full staging — Main in front, BIND in force, the count
 *   readable — and a row's re-stage in place; gone with every registry clear (a session start, a lapse, a sleep, the
 *   atlas reset or its live set changed, cached text off, the Page staging row off), at 160 KiB when a document's strips
 *   take the shared reserve (`Staging.retain`), and at a full re-stage ([drop]: the layout, the planes, the rows or the
 *   region changed, a row outgrew its slot). (b) Each write's wait is the stage lane's — its FlushDone and the count's
 *   reading — and a failed one leaves the registry: a base record's loss drops the staging (it is staged again), and so
 *   does a re-stage's, booked or not (the focused read of B082, F1: an unbooked one may have left bytes the copy lacks). (c) Keyed on the staging object (`MainStageKey`) and the offsets it laid out;
 *   the list's [BoundList.mark] is the staging's identity. (d) The shell's loop only. (e) One read of the link state per
 *   pump. (f) Its siblings: Page staging (the same registry and lane), the `mainseq` probe (refused while BIND is in
 *   force: no PLAY into a bound slot, P9).
 * - A row's stale mark ([stale]: [dirty], [owed]). Set by a key change at a compose ([keysChanged]), the
 *   round-robin repaint ([verifyDue]) and a re-stage write that failed; cleared when its re-stage is submitted or a
 *   repaint shows nothing changed. It decides only what is re-staged: a row older than Main's truth is NOT a reason to
 *   discard (decisions X.1 — the phone's END is taken from its own copy of the staged records, so belief equals what
 *   the lens drew; the ordinary repaint brings the changed cell). Never persisted: a staging is a session's.
 * - A band switch ([previous] on the list, the retired slot): set when its flush is submitted, released when that flush
 *   is acknowledged (no later report names the old slot; a later write into it follows the switch in stream order);
 *   a switch whose write is not counted drops the staging (what the item table holds is then not known).
 * - The withdrawal for a strip write ([withdrawnFor]): the list is not offered from the pump that withdraws it until the
 *   write is submitted, which goes in a LATER pump — the rules' CLEAR rides the flush between (their list in front is
 *   read at the top of every pump's flush section, and the idle step that writes never runs in a pump that flushes).
 * - The re-staging's pace ([RESTAGE_PACE_MS] for one row in all, the stalest first; [VERIFY_PACE_MS]). No wait of its own: the idle pump decides on
 *   each pass and asks the loop back when the next row is due; writes only while Main is in front, no burst is open
 *   (P9) and nothing else is in flight (the stage lane's own rule).
 */
internal class MainBinding {
    var staging: MainStaging? = null
        private set
    /** The base records (the registry's own objects), in [MainStaging.blobs]' order. */
    var records: List<StagedRecord> = emptyList()
        private set
    /** The list the staging makes, offered by `MainSurface` once every base record is counted. */
    var list: BoundList? = null
        private set
    private var markNext = 1
    private var mark = 0

    /** The row whose strip segments wait for their write with the list withdrawn, and the pump that withdrew it. */
    var withdrawnFor: Int? = null
        private set
    var withdrawnAtPump = 0L
        private set

    /** A row's re-stage in flight or acked and not yet counted: the registry's record and what it wrote. */
    class Patch(val row: Int, val record: StagedRecord, val restage: MainStaging.Restage.Changed) {
        /** Its band switch has been acknowledged and the old slot released. */
        var released = false
        /** A strip switch (AD.2): the flush that carried the rules' SETs naming the new strip, while it is in flight. */
        var setsFlush: Long? = null
        /** ... and that flush completed: both lenses hold the new pans, so no later fire draws the old strip. */
        var setsLanded = false
    }
    val patches = ArrayList<Patch>()

    private var keys: Array<Any?> = emptyArray()
    /** The row's key changed or its round-robin repaint is due: its pictures are painted again before anything else. */
    private var dirty = BooleanArray(0)
    /** Its pictures differ from what the glasses were sent: a re-stage is owed. */
    private var owed = BooleanArray(0)
    /** A re-stage write of it failed: what the glasses hold there is not known, so it is written again whatever it shows. */
    /** When each row's re-stage became owed (its staleness, decisions AF.8: the stalest goes first). */
    private var owedSinceMs = LongArray(0)
    /** The last re-stage written, any row: ONE row per [RESTAGE_PACE_MS] in all (AF.8, the contract's wording). */
    private var lastAnyWriteMs = Long.MIN_VALUE / 2
    private var verifyNext = 0
    private var verifyAtMs = 0L
    /** Paints of the full staging that met characters the atlas did not hold yet (the atlas goes first), this attempt. */
    var paints = 0

    /** Bytes of the full stagings this session and of the row re-stages, and how many of each (the trial reads them). */
    var stagedBytes = 0L; private set
    var stagings = 0; private set
    var restagedBytes = 0L; private set
    var restageCount = 0; private set

    /** B082 (row slack): the segment room each row's window has earned by changing — the largest picture it drew in a
     *  staging and a margin ([MainStaging.roomFor]) — carried into the next staging by window, for this process. */
    val roomById = HashMap<String, Int>()
    private var ids: List<String?> = emptyList()

    /** The current staging's changed rows into [roomById] (at a drop and before the next staging). */
    private fun harvestRoom() {
        val ms = staging ?: return
        for (r in 0 until minOf(ms.rows, ids.size)) {
            val id = ids[r] ?: continue
            if (r < ms.changed.size && ms.changed[r]) roomById[id] = maxOf(roomById[id] ?: 0, MainStaging.roomFor(ms.seenMax[r]))
        }
    }

    /** B082: the room each row of a staging about to be made gets, by the windows its rows show. */
    fun roomFor(idsNow: List<String?>): IntArray { harvestRoom(); return IntArray(idsNow.size) { idsNow[it]?.let { id -> roomById[id] } ?: 0 } }

    /** A new staging and its base records (placed, WANTED): the list made now carries a fresh mark. */
    fun begin(ms: MainStaging, recs: List<StagedRecord>, keysNow: List<Any?>, nowMs: Long, idsNow: List<String?> = emptyList()) {
        harvestRoom()
        ids = idsNow
        staging = ms
        records = recs
        mark = markNext
        list = ms.boundList(mark)
        markNext = (markNext % 0xFF) + 1                 // a u8, never 0 twice in a row
        withdrawnFor = null
        patches.clear()
        keys = keysNow.toTypedArray()
        dirty = BooleanArray(ms.rows); owed = BooleanArray(ms.rows)
        owedSinceMs = LongArray(ms.rows)
        verifyNext = 0
        verifyAtMs = nowMs + VERIFY_PACE_MS
        paints = 0
        stagedBytes += ms.bytes
        stagings++
    }

    /** The staging goes (its records are the caller's to take out of the registry): [records] and [patches] as they
     *  were, for that. */
    fun drop(): List<StagedRecord> {
        harvestRoom()
        val out = records + patches.map { it.record }
        staging = null; records = emptyList(); list = null; patches.clear(); withdrawnFor = null
        keys = emptyArray()
        dirty = BooleanArray(0); owed = BooleanArray(0); owedSinceMs = LongArray(0)
        paints = 0
        return out
    }

    /** Every base record counted (field 17): the list may be offered. */
    val counted: Boolean get() = records.isNotEmpty() && records.all { it.state == StagedRecord.State.PROVEN }

    fun stale(row: Int): Boolean = row in dirty.indices && (dirty[row] || owed[row])
    val staleRows: List<Int> get() = dirty.indices.filter { stale(it) }

    /** The list is withdrawn so that row [row]'s strip segments can be written (no table names the strip then). */
    fun withdraw(row: Int, pump: Long) { withdrawnFor = row; withdrawnAtPump = pump }

    /** At a compose of Main: the rows whose key ([MainSurface.rowKey]) changed are marked for a repaint. */
    fun keysChanged(keysNow: List<Any?>) {
        if (staging == null || keysNow.size != keys.size) return
        for (i in keys.indices) if (keysNow[i] != keys[i]) { keys[i] = keysNow[i]; dirty[i] = true }
    }

    /** The round-robin repaint ([VERIFY_PACE_MS]): one row marked for a repaint when due. */
    fun verifyDue(nowMs: Long): Boolean {
        val ms = staging ?: return false
        if (nowMs < verifyAtMs) return false
        dirty[verifyNext] = true
        verifyNext = (verifyNext + 1) % ms.rows
        verifyAtMs = nowMs + VERIFY_PACE_MS
        return true
    }

    fun nextDirty(): Int? = dirty.indices.firstOrNull { dirty[it] }

    /** Decisions AU.2: every row painted again and compared (a live font set changed under the staging's pictures). */
    fun recheckAll() { if (staging != null) for (i in dirty.indices) dirty[i] = true }

    /** A repaint of [row] found its pictures what the glasses hold. */
    fun same(row: Int) { dirty[row] = false; owed[row] = false }
    /** A repaint of [row] found them changed: a re-stage is owed (since [nowMs], if it was not already). */
    fun changed(row: Int, nowMs: Long = System.currentTimeMillis()) { dirty[row] = false; if (!owed[row]) owedSinceMs[row] = nowMs; owed[row] = true }

    /** The owed row to write at [nowMs], or null: ONE re-stage per [RESTAGE_PACE_MS] in all, the row owed longest first
     *  (decisions AF.8; `FIRMWARE.md` §6.7 P9 "one row per 5 s"); [dueInMs] says when one is. */
    fun nextOwed(nowMs: Long): Int? = if (nowMs < lastAnyWriteMs + RESTAGE_PACE_MS || patches.any { p -> !p.released }) null
        else owed.indices.filter { owed[it] }.minByOrNull { owedSinceMs[it] }

    fun dueInMs(nowMs: Long): Long? = if (owed.indices.none { owed[it] }) null
        else (lastAnyWriteMs + RESTAGE_PACE_MS - nowMs).coerceAtLeast(0L)
    fun verifyInMs(nowMs: Long): Long? = if (staging == null) null else (verifyAtMs - nowMs).coerceAtLeast(0L)

    /** [r]'s writes were submitted as [rec]: the pictures and the item table become [r]'s, the row is current; a band
     *  move makes a new list (same mark) whose [BoundList.previous] is the one before, until [switchAcked]; a withdrawal
     *  ends (the list is offered again: its SETs ride a later flush, after these writes in stream order). */
    /** Decisions BF (G4): the staging's period in force changed — the same list (mark, items, programs) with its entries at
     *  the new period, so the rules SET them again; a switch's [BoundList.previous] stays. */
    fun relisted(): BoundList? {
        val ms = staging ?: return null
        val l = list ?: return null
        list = ms.boundList(mark, previous = l.previous)
        return list
    }

    fun submitted(r: MainStaging.Restage.Changed, rec: StagedRecord, nowMs: Long) {
        val ms = staging!!
        ms.apply(r)
        if (r.band != null || r.toStrip != null) list = ms.boundList(mark, previous = list)
        if (withdrawnFor == r.row) withdrawnFor = null
        patches += Patch(r.row, rec, r)
        owed[r.row] = false; dirty[r.row] = false
        lastAnyWriteMs = nowMs
        restagedBytes += r.bytes
        restageCount++
    }

    /** Decisions AF.4 and AD.2: a switch's old version (the retired band slot, the other strip's pans) is released only
     *  when no report can still name it — its write [acked], no burst open (a report adopted before the ack may still be
     *  COMMITted from it), and for a strip switch the flush carrying the SETs that name the new strip completed
     *  ([Patch.setsLanded]): a fire before a lens applies them draws the old pan, and its report comes before that ack. */
    fun releaseIfDue(p: Patch, acked: Boolean, burstOpen: Boolean) {
        if (acked && !burstOpen && (p.restage.toStrip == null || p.setsLanded)) switchAcked(p)
    }

    /** A flush [id] carrying the rules' messages was submitted; [entriesHeld]: the rules now hold the offered list's scroll
     *  entries (their SETs were in it, or before it). A strip switch waiting for its SETs takes this flush as theirs. */
    fun tailSent(id: Long, entriesHeld: Boolean) {
        if (entriesHeld) for (p in patches) if (p.restage.toStrip != null && !p.setsLanded && p.setsFlush == null) p.setsFlush = id
    }

    /** The flush [id] completed: its strip switches' SETs landed ([ok]), or not — a later flush carries them again (the
     *  table sent again after a refusal, a new session's start). */
    fun tailDone(id: Long, ok: Boolean) {
        for (p in patches) if (p.setsFlush == id) { if (ok) p.setsLanded = true; p.setsFlush = null }
    }

    /** A new session: the flushes in flight are not this session's; the start's own batch SETs the entries again. */
    fun tailsForgotten() { for (p in patches) p.setsFlush = null }

    /** A re-stage's flush was acknowledged: its band switch (if any) is released — the old slot is free again and the
     *  list forgets its [BoundList.previous]. */
    fun switchAcked(p: Patch) {
        if (p.released) return
        p.released = true
        p.restage.band?.let { m -> staging?.release(m.oldOff4) }
        if (p.restage.band != null || p.restage.toStrip != null) list?.previous = null
    }

    /** A re-stage's record was counted: it leaves the books (its bytes are the staging's now). */
    fun patchCounted(p: Patch) { patches.remove(p) }

    /** A row's re-stage was not written ([withdrawnFor] cleared: the row stays owed). */
    fun notWritten(row: Int) { if (withdrawnFor == row) withdrawnFor = null }

    /** The repaint of a withdrawn row found nothing to write: the list is offered again. */
    fun sameWhileWithdrawn(row: Int) { same(row); if (withdrawnFor == row) withdrawnFor = null }

    companion object {
        /** One re-stage, of any row, at most this often (decisions AF.8, `FIRMWARE.md` §6.7 P9: one row per 5 s — a row
         *  older than Main's truth costs nothing but its cell's repaint after the COMMIT); the trial reads the bytes. */
        const val RESTAGE_PACE_MS = 5_000L
        /** One row's pictures painted again this often while Main is in front and staged — what a row's key does not
         *  carry (a theme icon that resolved, a face) is found within one round of the rows. */
        const val VERIFY_PACE_MS = 5_000L
        /** Paints of a full staging that may meet characters the atlas does not hold before it is staged as painted. */
        const val PAINTS_MAX = 3
        /** A re-stage's record's key index starts here (the base records take 0..n). */
        const val PATCH_KEY = 1_000
    }
}
