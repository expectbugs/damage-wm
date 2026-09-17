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
class StageKey(val content: Any, val firstLine: Int, val lines: Int, val lineH: Int, val w: Int) {
    override fun equals(other: Any?): Boolean = other is StageKey && other.content === content &&
        other.firstLine == firstLine && other.lines == lines && other.lineH == lineH && other.w == w
    override fun hashCode(): Int = System.identityHashCode(content) * 31 + firstLine * 7 + lines * 3 + lineH + w
    override fun toString() = "lines $firstLine..${firstLine + lines - 1}"
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

    fun free(rec: StagedRecord) {
        if (held.remove(rec)) rec.off = -1
    }

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
        val gone = records.values.filter { it.key.content !== content || it.key.lineH != lineH || it.key.w != w }
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

    fun writeAcked(writes: Int, atlas: Boolean) {
        acked += writes
        if (atlas) atlasAcked += writes
    }

    fun writeLost() { unsure = true }

    fun reset() {
        acked = 0L; atlasAcked = 0L
        baseGen = null; baseAcked = 0L; baseAtlasAcked = 0L
        unsure = false; shortReads = 0
    }

    sealed class Verdict {
        /** The first reading of the session, or one after a lost write: a base, nothing proven. */
        class Base(val gen: Long) : Verdict()
        /** Every write acked up to [upTo] landed. */
        class Proven(val upTo: Long) : Verdict()
        /** Short, but the acked writes have not had [SETTLE_MS] to land yet: counts for nothing; ask again. */
        class Pending(val missing: Long) : Verdict()
        /** Short after the settle window — asked again before it is called a shortfall. */
        class Short(val missing: Long, val reads: Int) : Verdict()
        /** Short [SHORT_READS_MAX] times: [missing] writes since the last base never landed. */
        class Shortfall(val missing: Long, val atlasInvolved: Boolean, val fromAcked: Long, val toAcked: Long) : Verdict()
        /** More bumps than this shell's writes: something else wrote the cache. */
        class Foreign(val extra: Long, val fromAcked: Long, val toAcked: Long) : Verdict()
    }

    /** A reading of field 17 taken with [sendAcked] / [sendAtlasAcked] the counts at its send; [settled]
     *  = the send came at least [SETTLE_MS] after the last cache-write ack, so a short count is not a
     *  decode still on its way. */
    fun answer(gen: Long, sendAcked: Long, sendAtlasAcked: Long, settled: Boolean): Verdict {
        val base = baseGen
        if (base == null || unsure) {
            rebase(gen, sendAcked, sendAtlasAcked)
            return Verdict.Base(gen)
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
                    val atlasInvolved = sendAtlasAcked > baseAtlasAcked
                    rebase(gen, sendAcked, sendAtlasAcked)
                    Verdict.Shortfall(expected - gen, atlasInvolved, from, sendAcked)
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
    }
}
