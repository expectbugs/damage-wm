package wm.damage.core.shell

import wm.damage.core.geom.LintError
import wm.damage.core.gfx.Gray8
import wm.damage.core.motion.TxBench

class TxbStageKey(i: Int) : StageKey(TxbStageKey::class, i, 1, 0, 0) {
    override fun toString() = "the txbench probe's record $firstLine"
}

/**
 * C3 stage TXB (`private/c3-design/TX-spec.md` §1.2): one run of the probe `txbench` — its phase-A records in the stage
 * lane's own books, then B0–B3 each [TxBench.PLAYS] times in slot 2, one PLAY at a time, the next sent once RIGHT has
 * reported the one before it; then phase B's records over phase A's (B4's image), then B4 the same way. Context for the reader: Adam's own glasses on his own firmware fork; display-rendering work
 * on a personal device. It starts only on his word (`probe txbench=<list>`); the shell checks §1.2's conditions first.
 *
 * **The answer** is the journal: each PLAY's `motion` note `test program played: txbench <name> …` (its tag, once the
 * flush carrying it is acknowledged) beside the transport's `motion` note of RIGHT's report for that tag (`stepUs`), and
 * the `txbench` note at the end with every reported step time by program.
 *
 * **Ended**, each said in a `txbench` note, and in every ending its records leave the books (the room is the window
 * lists' again; their bytes stay in the cache until a tenant writes there): all plays reported; any reason the shell
 * gives ([pump]'s `why`: Main leaves the front, an overlay, a transition, a notice, Silent Mode, the link, the lease,
 * stage 5); the room changed (Main's staging or a kept window list's made again); slot 2 holding another tag; the area no
 * longer at level 0 on either lens; a PLAY not submitted, its flush failed, or RIGHT's report anything but its last step
 * drawn (a play closed by a frame is played again, at most [CLOSED_MAX] times running); and, by the shell's own calls, an input event, a keyframe, another cache writer, a session start or a second
 * `probe txbench`. Nothing waits on a clock: a PLAY whose report never comes holds the run until one of those.
 *
 * Loop-confined; free of the shell (every input an argument), so its endings are pinned in `TxBenchTest`.
 */
class TxBenchRun(private val say: (kind: String, detail: String) -> Unit) {

    private class Run(val list: String, val bench: TxBench.Bench, val room: List<Any?>, var records: List<StagedRecord>) {
        var reported = 0
        /** 0 phase A (B0–B3), 1 phase B (B4). */
        var phase = 0
        /** Plays closed by a frame meeting the area and played again: in the run, and of the play in hand. */
        var closed = 0
        var closedThis = 0
        /** The PLAY in hand (sent, its report not in), its flush, and its program's index. */
        var tag: Int? = null
        var flush: Long? = null
        /** The flush carrying the PLAY in hand is acknowledged: from then the mirror's slot 2 holds its tag. */
        var acked = false
        var index = 0
        val stepUs = Array(TxBench.NAMES.size) { ArrayList<String>() }
    }

    private var run: Run? = null
    private var tags = 0
    /** Set by every ending: slot 2 may still hold one of its instances (drawn to its end, or closed by nothing yet). The
     *  shell sends one CLOSE of slot 2 where RIGHT's slot 2 still holds a tag of the probe's ([ownTag]), then clears it:
     *  slot 2 is handed back with the room, and the mirror's area there is known again. */
    var closeOwed = false

    val running: Boolean get() = run != null
    /** Its records, for the stage lane's and the ledger's books; empty when no run. */
    val records: List<StagedRecord> get() = run?.records ?: emptyList()
    /** The record the stage lane should write next, or null. */
    fun wanted(): StagedRecord? = run?.records?.firstOrNull { it.state == StagedRecord.State.WANTED }

    /** Phase [k]'s bytes of [bench] as records of at most [RECORD_MAX], WANTED at their offsets. */
    private fun recordsOf(bench: TxBench.Bench, k: Int): List<StagedRecord> {
        val blob = bench.blob(k)
        return (0 until blob.size step RECORD_MAX).mapIndexed { i, from ->
            StagedRecord(TxbStageKey(1000 * k + i), Gray8(1, 1), blob.copyOfRange(from, minOf(blob.size, from + RECORD_MAX))).also { it.off = bench.at + from }
        }
    }

    /** A run of [bench] (laid for [list]) begins with phase A's records; [room] the room's fingerprint. */
    fun start(list: String, bench: TxBench.Bench, room: List<Any?>) {
        run = Run(list, bench, room, recordsOf(bench, 0))
        say("txbench", "started on '$list': ${bench.cells} cells in ${bench.faces} face(s); records at ${bench.at}: phase A ${bench.bytes(0)} B to ${bench.end(0)} " +
            "(B0–B3), phase B ${bench.bytes(1)} B to ${bench.end(1)} (B4's image, over phase A); ${TxBench.NAMES.joinToString(" ")} each ${TxBench.PLAYS} times " +
            "in slot ${TxBench.SLOT}, T ${TxBench.T} at P ${TxBench.PERIOD}, level 0 over rows ${TxBench.AREA.y}..${TxBench.AREA.bottom - 1}")
    }

    /**
     * One pump: [why] non-null ends the run with it; [room] the room's fingerprint now (compared by identity); [tagIn] the
     * mirror's tag in slot 2 on RIGHT, read once the PLAY in hand's flush is acknowledged (before it the mirror may still
     * show the PLAY before); [levelZero] why the area is not level 0 on both lenses (null: it is), asked only
     * before a PLAY. Returns the PLAY to submit alone after this pump's frame ([sent] or [notSent] follows), or null.
     */
    fun pump(why: String?, room: List<Any?>, tagIn: Int?, levelZero: () -> String?): ByteArray? {
        val r = run ?: return null
        if (why != null) { end(why); return null }
        if (!roomHolds(room)) { end("the room changed (Main's staging or a kept window list's was made again)"); return null }
        if (r.records.any { it.state != StagedRecord.State.PROVEN }) return null
        r.tag?.let { t ->
            if (r.acked && tagIn != null && tagIn != t) end("slot ${TxBench.SLOT} holds another PLAY (tag 0x%04x), not its own 0x%04x".format(tagIn, t))
            return null
        }
        if (r.reported == TxBench.NAMES.size * TxBench.PLAYS) { end("done"); return null }
        if (r.phase == 0 && r.reported == TxBench.PHASE_B * TxBench.PLAYS) {
            r.phase = 1; r.records = recordsOf(r.bench, 1)
            say("txbench", "phase B: B0–B3 played; ${r.bench.bytes(1)} B of B4's records (${r.records.size}) written over phase A's at ${r.bench.at}")
            return null
        }
        levelZero()?.let { end(it); return null }
        val i = r.reported / TxBench.PLAYS
        val tag = TAG + (tags++ and 0xFFF)
        val msg = try { r.bench.play(i, tag) } catch (e: LintError) { end("${TxBench.NAMES[i]}'s PLAY is refused offline (${e.message})"); return null }
        r.tag = tag; r.index = i; r.flush = null; r.acked = false
        return msg
    }

    /** The room still the one the run began with ([room] compared by identity): a record is written only then. */
    fun roomHolds(room: List<Any?>): Boolean = run?.room?.let { r -> r.size == room.size && room.indices.all { room[it] === r[it] } } ?: false

    fun sent(id: Long) { run?.flush = id }

    fun notSent(why: String) = end("its PLAY was not submitted ($why)")

    /** A flush ended: the one carrying the PLAY in hand names its tag's program, or ends the run when it failed. */
    fun flushDone(id: Long, ok: Boolean, error: String?) {
        val r = run ?: return
        if (r.flush != id) return
        r.flush = null
        if (!ok) { end("the flush carrying its PLAY failed ($error)"); return }
        val t = r.tag ?: return
        r.acked = true
        say("motion", "test program played: txbench ${TxBench.NAMES[r.index]} (play ${r.reported % TxBench.PLAYS + 1} of ${TxBench.PLAYS}, " +
            "slot ${TxBench.SLOT}, tag 0x%04x, T ${TxBench.T} at P ${TxBench.PERIOD}, list '${r.list}')".format(t))
    }

    /** RIGHT's motion report ([detail], the transport's `motion` note) for the PLAY in hand: its last step drawn moves on,
     *  anything else ends the run. True when it was this run's. */
    fun notify(detail: String): Boolean {
        val r = run ?: return false
        val n = MotionNotify.parse(detail) ?: return false
        if (n.tag != r.tag || detail.substringAfter(" slot ", "").substringBefore(' ') != TxBench.SLOT.toString()) return false
        if (n.outcome == "closed" && r.closedThis < CLOSED_MAX) {
            r.closed++; r.closedThis++; r.tag = null
            say("txbench", "${TxBench.NAMES[r.index]} play ${r.reported % TxBench.PLAYS + 1} of ${TxBench.PLAYS} (tag 0x%04x) closed by a frame meeting its area (§5.6) — played again (${r.closedThis} of $CLOSED_MAX for this play, ${r.closed} in the run)".format(n.tag))
            return true
        }
        if (n.outcome != "drew") { end("RIGHT's report for ${TxBench.NAMES[r.index]} (tag 0x%04x): ${n.outcome}".format(n.tag) +
            if (n.outcome == "closed") " — closed by a frame ${CLOSED_MAX + 1} times running: the screen keeps changing" else ""); return true }
        r.closedThis = 0
        r.stepUs[r.index] += "${n.stepUs ?: "?"}" + if ((n.folded ?: 0L) != 0L) " (${n.folded} folded)" else ""
        r.reported++
        r.tag = null
        return true
    }

    /** Ends the run, said with [why]: its records leave the books. Nothing when none runs. */
    fun end(why: String) {
        val r = run ?: return
        run = null
        closeOwed = true
        say("txbench", "ended — $why; ${r.reported} of ${TxBench.NAMES.size * TxBench.PLAYS} plays reported on '${r.list}'" +
            (if (r.closed == 0) "" else ", ${r.closed} play(s) closed by a frame and played again") +
            (if (r.reported == 0) "" else ", stepUs by program: " + TxBench.NAMES.indices.filter { r.stepUs[it].isNotEmpty() }
                .joinToString("; ") { "${TxBench.NAMES[it]} ${r.stepUs[it].joinToString(", ")}" }) +
            "; its ${r.records.size} records leave the books (the room is free again)")
    }

    companion object {
        /** The packed glyphs of [view]'s rows at [layout] — each row's row form, then each row's band form — as [ct]'s atlas
         *  holds them, from runs [ct] surveys while each is painted into a scratch: nothing is counted (no face seen, no
         *  character noted), so the atlas learns nothing from the probe. */
        internal fun glyphsOf(view: WindowView.ListView, kit: ContentKit, layout: wm.damage.core.geom.Layout, ct: wm.damage.core.comp.CachedText): List<Pair<wm.damage.core.text.FontSpec, wm.damage.core.motion.CellLists.Glyph>> {
            val atlas = ct.atlas ?: throw LintError("no atlas holds the list's faces")
            val out = ArrayList<Pair<wm.damage.core.text.FontSpec, wm.damage.core.motion.CellLists.Glyph>>()
            for (band in listOf(false, true)) for (i in 0 until view.rowCount()) {
                var got: List<wm.damage.core.comp.TextDraw> = emptyList()
                val via: (Gray8, () -> Unit) -> Unit = { g, b -> got = ct.survey(g, false, b) }
                if (band) ListStaging.paintBandForm(view, kit, layout, i, via) else ListStaging.paintStripRow(view, layout, i, via)
                out += wm.damage.core.motion.CellLists.glyphs(got, { atlas.entry(it) }, { ct.kernOf(it) })
            }
            return out
        }

        /** The probe's records for [view]'s text laid in `[at, end)` (refused, [LintError], where they do not fit). */
        internal fun bench(view: WindowView.ListView, kit: ContentKit, layout: wm.damage.core.geom.Layout, ct: wm.damage.core.comp.CachedText, at: Int, end: Int,
                           fx: wm.damage.core.motion.Motion.Effects): TxBench.Bench {
            val atlas = ct.atlas ?: throw LintError("no atlas holds the list's faces")
            val (faces, cells) = TxBench.cells(glyphsOf(view, kit, layout, ct)) { atlas.entry(it)!!.images.toList() }
            return TxBench.lay(faces, cells, TxBench.picture(faces, cells), at, end, fx)
        }

        /** Its tags: 0x9000 + a counter, apart from the lanes' 0xA000, the wrap's 0xB000, a transition's 0xC000 / 0xD000
         *  and the notice's 0xE000. */
        const val TAG = 0x9000
        /** A tag of the probe's own (0x9000..0x9FFF). */
        fun ownTag(tag: Int?): Boolean = tag != null && tag and 0xF000 == TAG
        /** A play closed by a frame (`FIRMWARE.md` §5.6: a flush meeting the area closes the instance first — any cached
         *  text run counts to the panel's bottom-right, so the clock's repaint does) is played again, this many times
         *  running; one more ends the run. A count, not a clock: the run's plays are at most 30 × (1 + this). */
        const val CLOSED_MAX = 3
        /** A record's most bytes: the records go as one deflated message each where the stage lane can (mode 25's inflate,
         *  `CfwModes.ZWRITE_MAX`, less the entry's 4 B), else in its pieces. */
        const val RECORD_MAX = wm.damage.core.wire.CfwModes.ZWRITE_MAX - 4
    }
}
