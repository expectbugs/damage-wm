package wm.damage.core.shell

import wm.damage.core.gfx.Gray8

/**
 * Page staging (`HANDOFF.md` §66, Adam's decisions of 2026-09-16; `FIRMWARE.md` §4's phone side).
 *
 * The focused document's next and previous strips — one notch's worth of lines each — are
 * painted ahead of time through the same paint path a live repaint takes, encoded as v2 image
 * records and written by mode 19 into a reserve at the top of the session's texture cache
 * (`CfwModes.STAGE_RESERVE`, outside the atlas's layout). A notch whose exposed rows a PROVEN
 * record covers then ships one mode-17 draw under a clip instead of the strip's pixels or its
 * text draws (`Compositor.emitStaged`). Nothing here touches the wire: the shell owns the pump,
 * the flushes and the proof; these are the registry, the ring the records live in, and the
 * ledger that turns the glasses' cache generation count into a proof.
 *
 * Every class is loop-confined (the shell's), like the atlas bookkeeping beside it.
 */

/** What a strip is a rendering OF: the document's layout identity (the `DocView.contentKey`), the
 *  lines it covers, the line box and the width it was painted at. Two keys are equal only for the
 *  same layout object — a relayout, a rescale or another book never matches an old record. */
open class StageKey(val content: Any, val firstLine: Int, val lines: Int, val lineH: Int, val w: Int) {
    override fun equals(other: Any?): Boolean = other is StageKey && other.content === content &&
        other.firstLine == firstLine && other.lines == lines && other.lineH == lineH && other.w == w
    override fun hashCode(): Int = System.identityHashCode(content) * 31 + firstLine * 7 + lines * 3 + lineH + w
    override fun toString() = "lines $firstLine..${firstLine + lines - 1}"
}

/** C3 DW-1a (`C3-DW1-amendment.md` §4.5, decisions CK A7): a record of a tenant of Main's region — Main's staging
 *  ([MainStageKey]) or the wheel's block ([WheelStageKey]) — never a document's strip: Page staging's filters (a book
 *  change, an Encode act's lists, its own clear, its counts) read "not a tenant" where they read "not Main's". */
abstract class RegionStageKey(content: Any, index: Int, val name: String) : StageKey(content, index, 1, 0, 0)

/** Build C2, stage DMS: one of Main's staged records ([MainStaging]) — [content] the staging it belongs to,
 *  [firstLine] its index there, [name] what the journal calls it. Never a document's: no placement draws it. */
class MainStageKey(content: Any, index: Int, name: String) : RegionStageKey(content, index, name) {
    override fun toString() = "Main's $name"
}

/** C3 DW-1a: one of the wheel's block's records ([WheelResident]) — [content] the books it belongs to, [firstLine] its
 *  serial, [name] what the journal calls it. Not Main's: Main's filters read `is MainStageKey`. Its record's `encoded` is
 *  the bytes written, which may lie in several runs (the block's records with their slots' slack between): the block's
 *  extent is `WheelResident.block`'s, never `off + size4`. */
class WheelStageKey(content: Any, index: Int, name: String) : RegionStageKey(content, index, name) {
    override fun toString() = "the wheel's $name"
}

/** One staged strip: the pixels as painted (RAW — the diff compares raw bytes, and a slide that
 *  blits them into `composed` must leave exactly what a direct paint would), the v2 record the
 *  glasses hold (the same pixels quantised), and where it stands on the way to being drawable. */
class StagedRecord(val key: StageKey, val pixels: Gray8, val encoded: ByteArray) {
    val firstLine: Int get() = key.firstLine
    val lines: Int get() = key.lines
    val w: Int get() = pixels.w
    val h: Int get() = pixels.h
    /** The record's bytes in the cache, padded to the 4-byte boundary the next record starts on. */
    val size4: Int = (encoded.size + 3) / 4 * 4
    /** Byte offset in the cache; −1 while the ring does not hold it. */
    var off: Int = -1
    var state: State = State.WANTED
    /** The ledger's acked count right after this record's write was acked ([CacheLedger.acked]). */
    var ackedAt: Long = 0L

    enum class State {
        /** Rendered and placed in the ring; the write has not gone out (or is going again). */
        WANTED,
        /** Its write is in flight. */
        SENT,
        /** Acked by the glasses; the ack precedes the decode, so nothing draws from it yet. */
        ACKED,
        /** The generation count vouched for its write: the compositor may draw it. */
        PROVEN,
    }

    fun covers(line: Int): Boolean = line >= firstLine && line < firstLine + lines
    override fun toString() = "$key (${encoded.size} B at $off, $state)"
}

/**
 * The reserve as a ring: records are placed at a bump pointer that wraps to the start, and a
 * placement evicts whatever it overlaps — unless one of those is PINNED (on screen, or wanted for
 * the next notch), in which case the placement is refused and the caller tries again later. A
 * record never moves once placed (the glasses hold it at that offset), and a refused placement
 * never touches the ring.
 */
class StageRing(val start: Int, val end: Int) {
    private val held = ArrayList<StagedRecord>()
    private var next = start

    val resident: List<StagedRecord> get() = held
    val bytesHeld: Int get() = held.sumOf { it.size4 }
    val capacity: Int get() = end - start

    /** Place [rec]. Returns the records evicted for it (possibly none), or null when it does not fit
     *  without evicting a pinned record — nothing changes then. */
    fun place(rec: StagedRecord, pinned: (StagedRecord) -> Boolean): List<StagedRecord>? {
        val size = rec.size4
        if (size <= 0 || size > capacity) return null
        for (cand in listOf(next, start).distinct()) {
            if (cand + size > end) continue
            val overlapping = held.filter { it.off < cand + size && cand < it.off + it.size4 }
            if (overlapping.any(pinned)) continue
            for (e in overlapping) { e.off = -1; held.remove(e) }
            rec.off = cand
            held.add(rec)
            next = cand + size
            return overlapping
        }
        return null
    }

    /** Build C2, stage DMS: place [rec] at the byte offset [off] (Main's records keep their offsets: a program
     *  names its records by them). Evicts whatever it overlaps unless one of those is [pinned]; null, and nothing
     *  changes, when it is not inside the ring or a pinned record is in the way. The bump pointer moves past it. */
    fun placeAt(rec: StagedRecord, off: Int, pinned: (StagedRecord) -> Boolean = { false }): List<StagedRecord>? {
        val size = rec.size4
        if (size <= 0 || off % 4 != 0 || off < start || off + size > end) return null
        val overlapping = held.filter { it !== rec && it.off < off + size && off < it.off + it.size4 }
        if (overlapping.any(pinned)) return null
        for (e in overlapping) { e.off = -1; held.remove(e) }
        held.remove(rec)
        rec.off = off
        held.add(rec)
        next = off + size
        return overlapping
    }

    fun free(rec: StagedRecord) {
        if (held.remove(rec)) rec.off = -1
    }

    /** C3 X2T item 3: the bump pointer back to [off] — the lowest offset of the strips an Encode act's cell lists dropped —
     *  so a dropped strip is written back where it lay instead of waiting while a pinned strip holds the ring's start (the
     *  pointer alone tries `next` and the start). */
    fun rewind(off: Int) { if (off in start until end) next = off }

    fun clear() {
        for (r in held) r.off = -1
        held.clear()
        next = start
    }
}

/** The registry: every record the ring holds, by key. */
class Staging {
    var ring: StageRing? = null
    val records = LinkedHashMap<StageKey, StagedRecord>()

    /** The record whose pixels hold [line] of the layout [content] at this line box and width, if any
     *  — for a paint that can blit the line instead of drawing it. Any state: the pixels are right
     *  whether or not the glasses hold the record yet. */
    fun recordFor(line: Int, content: Any?, lineH: Int, w: Int): StagedRecord? {
        if (content == null) return null
        for (r in records.values) {
            if (r.key.content === content && r.key.lineH == lineH && r.key.w == w && r.covers(line)) return r
        }
        return null
    }

    /** Drop every record that is not of [content] at this line box and width (a relayout, another
     *  book, a rescale): their pixels can never be shown again. Returns what was dropped. */
    fun retain(content: Any?, lineH: Int, w: Int): List<StagedRecord> {
        // build C2, stage DM2: Main's records outside the ring (its own region at 256 KiB, a row's re-stage) are not the
        // document's to drop; in the ring (the shared reserve at 160 KiB) the document's strips take their place. C3 DW-1a:
        // nor the wheel's block, the region's other tenant (never in the ring)
        val gone = records.values.filter { (it.key !is RegionStageKey || it in ring?.resident.orEmpty()) &&
            (it.key.content !== content || it.key.lineH != lineH || it.key.w != w) }
        for (r in gone) { ring?.free(r); records.remove(r.key) }
        return gone
    }

    fun remove(rec: StagedRecord) {
        ring?.free(rec)
        records.remove(rec.key)
    }

    fun clear() {
        ring?.clear()
        records.clear()
    }

    fun count(state: StagedRecord.State): Int = records.values.count { it.state == state }
}

/**
 * The cache-write ledger (Adam's decision 5, 2026-09-16): the proof that a cache write LANDED.
 *
 * The success ack for an image message precedes its decode, and a refused or dropped cache write is
 * otherwise silent (`FIRMWARE.md` §4; the §59 and §65 findings). The firmware's telemetry field 17 is
 * the number of non-empty cache writes it has taken since boot, so the phone can prove a batch of
 * writes by counting: every write it sends and sees acked is one expected bump, and a read of the
 * generation after them must show exactly that many. This class holds the count and the last
 * consistent reading and turns each new reading into a verdict; the shell decides what a verdict
 * means for the atlas and for the staged records.
 *
 * Both write lanes are held still while a reading is on its way (the shell's pumps), so the count
 * at the send is the count the answer speaks for. **The bump lands well after the ack** — measured on
 * glass 2026-09-16 (§66.1): up to about a second after, while a reading answers in 40 ms — so a short
 * reading sent before the acked writes have had [SETTLE_MS] to land is [Verdict.Pending] and counts for
 * nothing; only a short reading sent after that window counts ([Verdict.Short]), and [SHORT_READS_MAX]
 * of those make a shortfall. More bumps than expected ([Verdict.Foreign]) re-base the count and
 * invalidate nothing: the acked writes are not made bad by an extra bump (the same night's reads
 * showed one, cause unread), and another writer's bytes are caught by the transport's writer tag.
 *
 * Since 0.64 the count has ONE reader, wherever the shell is (`Shell.pumpLedger`, op 1 on RIGHT):
 * the base is read before a session's first cache write, and a reading is owed whenever writes are
 * acked past the last agreed one ([unjudged]). An interval is the batches since the last agreed reading.
 *
 * **The count's two rules, for every cache write the shell sends** (C3 X6T, decisions AW; the 44 of 45,
 * `C3-X5T-compare.md`): each write is booked here at its send ([writeSent]) and at its completion
 * ([writeDone]), whoever wrote it. (1) A reading is launched only when no booked write is open
 * ([writesOpen] 0), and a write sent while a reading is on its way voids that reading ([Verdict.Void]:
 * its answer may or may not hold the write, so it is dropped whole and asked again). (2) Every ack
 * restarts the settle window ([lastAckMs]). Before X6T a transition's records' and lists' writes kept
 * neither: one went under a reading and left the base one write behind, and its ack did not restart
 * the window, so two "settled" readings later the shell called a shortfall, re-sent the atlas and
 * keyframed in the middle of an act.
 */
class CacheLedger {
    /** Non-empty cache writes acked this session, in lane order — atlas chunks and staged records. */
    var acked: Long = 0L; private set
    /** The atlas's share of [acked]. */
    var atlasAcked: Long = 0L; private set
    /** The generation the glasses reported at the last consistent reading, and the counts then. */
    var baseGen: Long? = null; private set
    var baseAcked: Long = 0L; private set
    var baseAtlasAcked: Long = 0L; private set
    /** A cache-write flush ended without its ack (the link, the session's sweep): what landed is
     *  unknown, and the next reading can only re-base. */
    var unsure: Boolean = false; private set
    var shortReads: Int = 0; private set
    /** When the last cache-write ack was counted (ms, the caller's clock): the settle window runs from here, restarted
     *  by EVERY ack ([writeAcked] is the one place an ack is counted). */
    var lastAckMs: Long = 0L; private set
    /** The cache-write flushes sent and not yet completed, by flush id → whether the write is the atlas's. */
    private val open = HashMap<Long, Boolean>()
    /** Booked cache writes still on their way: no reading is launched while any is. */
    val writesOpen: Int get() = open.size
    /** A reading is on its way ([readingSent] until its answer or its drop). */
    private var reading = false
    /** A cache write was sent while the reading on its way was: its answer counts for nothing. */
    var readingVoid: Boolean = false; private set

    /** A cache-write flush [id] (one non-empty mode-12/19/25 message) was sent; it voids a reading on its way. */
    fun writeSent(id: Long, atlas: Boolean) {
        open[id] = atlas
        if (reading) readingVoid = true
    }

    /** Flush [id] completed. A booked write is counted when acked ([ok]) and makes the books unsure when not (what
     *  landed is unknown); any other flush is no business of the count. True when [id] was a booked write. */
    fun writeDone(id: Long, ok: Boolean, nowMs: Long): Boolean {
        val atlas = open.remove(id) ?: return false
        if (ok) writeAcked(1, atlas, nowMs) else writeLost()
        return true
    }

    fun writeAcked(writes: Int, atlas: Boolean, nowMs: Long = System.currentTimeMillis()) {
        acked += writes
        if (atlas) atlasAcked += writes
        lastAckMs = nowMs
    }

    /** Whether a short reading sent at [nowMs] has given the acked writes [SETTLE_MS] to land. */
    fun settled(nowMs: Long): Boolean = nowMs - lastAckMs >= SETTLE_MS

    /** A reading was launched (its counts are taken by the caller now). */
    fun readingSent() { reading = true; readingVoid = false }

    /** The reading on its way stops meaning anything (its epoch ended, it was not answered): nothing of it is judged. */
    fun readingDropped() { reading = false; readingVoid = false }

    /** Cache-write messages acked past the last agreed reading: what a reading is still owed for. */
    val unjudged: Long get() = acked - baseAcked

    fun writeLost() { rebaseNext() }

    /** The writes acked since the base can no longer be judged by counting (a write whose ack never
     *  came, a layout replaced under them): the next reading re-bases and proves nothing. */
    fun rebaseNext() { unsure = true }

    fun reset() {
        acked = 0L; atlasAcked = 0L
        baseGen = null; baseAcked = 0L; baseAtlasAcked = 0L
        unsure = false; shortReads = 0
        lastAckMs = 0L
        open.clear()                       // a write of the session that ended is no longer this count's (its ids never repeat)
        readingDropped()
    }

    sealed class Verdict {
        /** The first reading of the session ([first]), or one after a lost write or a replaced
         *  layout: a base, nothing proven. */
        class Base(val gen: Long, val first: Boolean) : Verdict()
        /** Every write acked up to [upTo] landed. */
        class Proven(val upTo: Long) : Verdict()
        /** Short, but the acked writes have not had [SETTLE_MS] to land yet: counts for nothing; ask again. */
        class Pending(val missing: Long) : Verdict()
        /** Short after the settle window — asked again before it is called a shortfall. */
        class Short(val missing: Long, val reads: Int) : Verdict()
        /** Short [SHORT_READS_MAX] times: [missing] of the writes acked in ([fromAcked], [toAcked]] were
         *  never counted; [atlasWrites] of that interval's messages were the atlas's (the rest staged strips). */
        class Shortfall(val missing: Long, val atlasWrites: Long, val fromAcked: Long, val toAcked: Long) : Verdict() {
            val atlasInvolved: Boolean get() = atlasWrites > 0
        }
        /** More bumps than this shell's writes: something else wrote the cache. */
        class Foreign(val extra: Long, val fromAcked: Long, val toAcked: Long) : Verdict()
        /** C3 X6T: a cache write was sent while the reading was on its way, so its generation may or may not hold it:
         *  nothing is judged (no base moves, nothing proven or called short) and the reading is asked again. */
        object Void : Verdict()
    }

    /** A reading of field 17 taken with [sendAcked] / [sendAtlasAcked] the counts at its send; [settled]
     *  = the send came at least [SETTLE_MS] after the last cache-write ack, so a short count is not a
     *  decode still on its way. */
    fun answer(gen: Long, sendAcked: Long, sendAtlasAcked: Long, settled: Boolean): Verdict {
        val void = readingVoid
        readingDropped()
        if (void) return Verdict.Void
        val base = baseGen
        if (base == null || unsure) {
            rebase(gen, sendAcked, sendAtlasAcked)
            return Verdict.Base(gen, first = base == null)
        }
        val expected = base + (sendAcked - baseAcked)
        return when {
            gen == expected -> {
                rebase(gen, sendAcked, sendAtlasAcked)
                Verdict.Proven(sendAcked)
            }
            gen < expected && !settled -> Verdict.Pending(expected - gen)
            gen < expected -> {
                shortReads++
                if (shortReads < SHORT_READS_MAX) Verdict.Short(expected - gen, shortReads)
                else {
                    val from = baseAcked
                    val atlasWrites = sendAtlasAcked - baseAtlasAcked     // taken before the re-base
                    rebase(gen, sendAcked, sendAtlasAcked)
                    Verdict.Shortfall(expected - gen, atlasWrites, from, sendAcked)
                }
            }
            else -> {
                val from = baseAcked
                rebase(gen, sendAcked, sendAtlasAcked)
                Verdict.Foreign(gen - expected, from, sendAcked)
            }
        }
    }

    private fun rebase(gen: Long, sendAcked: Long, sendAtlasAcked: Long) {
        baseGen = gen; baseAcked = sendAcked; baseAtlasAcked = sendAtlasAcked
        unsure = false; shortReads = 0
    }

    companion object {
        /** Settled readings short in a row before the count is a shortfall. */
        const val SHORT_READS_MAX = 2
        /** How long the acked writes get to land before a short reading counts (measured 2026-09-16:
         *  bumps up to ~1 s after the ack on the phone's link). A pace, not a bound: nothing is dropped
         *  or given up inside it, the reading is only asked again. */
        const val SETTLE_MS = 2_000L
        /** No second reading sooner than this after a pending or short one. */
        const val REREAD_PACE_MS = 500L
        /** No reading sooner than this after one that was NOT ANSWERED (0.64 R1: the transport's null —
         *  its first write failed or its session is ending). A pace, not a bound: the reading is asked
         *  again when it ends; the bound is the shell's count of unanswered readings in a row. */
        const val UNANSWERED_PACE_MS = 2_000L
    }
}
