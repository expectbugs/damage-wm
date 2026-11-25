package wm.damage.core.shell

import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.motion.Wheel

/**
 * C3 stage DW-1a (`private/c3-design/C3-DW1-amendment.md` §3.1–§3.5, §5; decisions BD's O5, BQ (4), CK A1–A7): the books of
 * the switcher's wheel's block resident in Main's region — its first tenant from the region's end at ladder stage 5 on a
 * 256 KiB session, staged ahead in idle and kept current in place while the wheel is closed. Context for the reader:
 * Adam's own glasses on his own firmware fork (display-rendering work on a personal device). Nothing here sends or binds;
 * the shell's lane (`Shell.pumpWheel`) paints, places and writes through the stage lane, and its poll (`Shell.wheelPoll`)
 * reads the registry. DW-1b binds the block as list 2.
 *
 * The envelope (`FORK.md` §3.8; the amendment's §5, the rows that belong to DW-1a): (a) the block goes with its records —
 * every `stageClear` (a session start, an atlas reset, cached text off, a lapse, the glasses asleep), a write that fails
 * or a reading that drops it, Main's staging laid out over it ("Main wins"), and a re-place (a cell or the programs past
 * their slot, the face, the registered windows); (b) a write that never lands leaves the registry (the stage lane's own
 * rules) — the whole block's write: the block is placed again; an in-place write: that record's bytes on the glasses are
 * unknown and it is written again; (c) keyed on the cells' keys (the window, its name, icon and dirty tick, the icon
 * source's answers), the item table's order and the programs' geometry and timing — compared, never painted, each pump;
 * (d) loop-only; both lenses take the writes in stream order, LEFT cannot report (the count is RIGHT's); (e) one read of
 * the link state per pump; (f) the siblings — Main's records, Page staging's strips, the notice's slice, Encode's lists —
 * are kept apart by placement and by [WheelStageKey], which Page staging's filters leave alone.
 */
class WheelResident {
    /** What one cell was painted for (the amendment's §3.4): its window (null: Main's pseudo-entry), name, icon kind, dirty
     *  tick, and the icon source's answers at 56 and 20 px — a theme bitmap by identity, or null where the drawn icon
     *  paints (a bitmap resolved later moves the key). The faces' metrics are the block's face ([face]). */
    class CellKey(val window: DamageWindow?, val name: String, val icon: IconKind, val dirty: Boolean, val bm56: Gray8?, val bm20: Gray8?) {
        override fun equals(other: Any?) = other is CellKey && other.window === window && other.name == name && other.icon == icon &&
            other.dirty == dirty && other.bm56 === bm56 && other.bm20 === bm20
        override fun hashCode() = System.identityHashCode(window) * 31 + name.hashCode() * 7 + icon.hashCode() * 3 + (if (dirty) 1 else 0)
        override fun toString() = "'$name'${if (dirty) " (dirty)" else ""}"
    }

    /** The registered windows the cells are for (null first: Main's), in registration order — the block's cells. */
    var regs: List<DamageWindow?> = emptyList()
    /** The cells' face ([Wheel.Geo]'s cell width and form heights): another one paints every cell again. */
    var face: String? = null
    /** The cells painted, by index into [regs], with the key each was painted for. */
    val painted = HashMap<Int, Pair<CellKey, Wheel.Cell>>()
    /** The cell painted once more after the atlas took what its paint drew (§73's rule, as Page staging's: once). */
    var paintedAgain: CellKey? = null

    /** The block placed this session (its layout, its pictures as last built) as the first tenant from Main's region's end;
     *  null while none is. */
    var block: Wheel.Block? = null
    /** The whole block's write (one record, in pieces). */
    var record: StagedRecord? = null
    /** Each record's bytes as last sent (by [Wheel.Block.Part.name]); a record lost on its way is not here, so it goes again. */
    val sent = HashMap<String, Wheel.Block.Part>()
    /** In-place writes not yet counted: the record's name and its staged record. */
    val patches = ArrayList<Pair<String, StagedRecord>>()
    /** What [want] was built for, and the block it gives on [block]'s offsets (null: it does not fit them — re-placed). */
    var wantFor: Any? = null
    var want: Wheel.Block? = null
    /** The block's write has been counted (said once a placement). */
    var countedSaid = false
    /** The last "not placed" note, said once per change of it (decisions AL.1's rule; its bytes are part of it, BQ (4)). */
    var notPlacedSaid: String? = null
    /** The want's key a placement was refused for (past the cap, a cell or layout refused): not tried again until it
     *  changes. A placement met by Main's staging is tried again as that changes (cheaply: [sized]). */
    var refusedFor: Any? = null
    /** The block's size for a want's key (its programs built once for it). */
    var sized: Pair<Any, Int>? = null
    /** C3 DW-1b-2 (decisions DF finding 2): the size of the block the lane would place, measured and inside the cap and
     *  clear of Main's staging, its write held while a window's list in front is staged first — its place below the
     *  region's end is reserved ([baseIn]) so that list is laid out under it, never where the block goes. Kept across a
     *  drop and a session start (a block of that size goes back there), set again at each placement's measure, cleared
     *  where a measure refuses the block (past the cap, met by Main's staging). */
    var reserved: Int? = null
    /** Keys to records' serials. */
    var serial = 0
    /** This process: placements, their bytes; in-place writes, their bytes. */
    var placements = 0
    var placedBytes = 0L
    var kept = 0
    var keptBytes = 0L

    /** The block leaves: its records (the whole write and any in-place write on its way) for the caller to take out of the
     *  registry; [keepCells] false: the painted cells go too (the cache went with them: a session start, a lapse). */
    fun drop(keepCells: Boolean): List<StagedRecord> {
        val out = listOfNotNull(record) + patches.map { it.second }
        block = null; record = null; sent.clear(); patches.clear(); wantFor = null; want = null; countedSaid = false
        if (!keepCells) { painted.clear(); paintedAgain = null }
        return out
    }

    /** The faces live on the glasses changed (`Shell.atlasLive`; V1-DW1a F2): a cell's live paint may now differ, so every
     *  cell is painted again and the block built anew from the new paints — on a placed block compared part by part, only a
     *  part whose bytes differ written in place; unplaced, its size measured again and a refusal for the old paints tried
     *  once more. */
    fun liveChanged() { painted.clear(); paintedAgain = null; wantFor = null; sized = null; refusedFor = null }

    /** THE BLOCK'S BASE in a region ending at [regionEnd] (`Shell.wheelBase`, and `WindowListCheck.top` for its fixture: one
     *  source) — the placed block's, else the place [reserved] for it, else the region's end: a window's list is laid out
     *  from here down. */
    fun baseIn(regionEnd: Int): Int = block?.base ?: reserved?.let { baseFor(regionEnd, it) } ?: regionEnd

    companion object {
        /** Decisions BQ (4): the block may take up to 20 KiB of Main's region; past it the block is not placed (the wheel
         *  stays phone-drawn) and the journal says its bytes. */
        const val CAP = 20 * 1024

        /** C3 DW-1b-2: why a session of [cacheSize] B whose Main's region ends at [regionEnd] offers no wheel (null: it may) —
         *  the offer's session condition (`Shell.wheelNotOffered`, and `--local-check`'s "not offered" case: one source). */
        fun sessionRefusal(cacheSize: Int, regionEnd: Int): String? =
            if (cacheSize != wm.damage.core.wire.CfwModes.CACHE2_MAX_RIDERS || regionEnd >= cacheSize) "no 256 KiB session with a region of Main's own" else null

        /** Where a block of [size] B lies as the first tenant from [regionEnd] (decisions BD): 4-byte aligned below it. */
        fun baseFor(regionEnd: Int, size: Int): Int = (regionEnd - size) and 3.inv()

        /** Why a block of [size] B is not placed below [regionEnd] with Main's staging in that region ending at [mainEnd]
         *  (null: no staging there) — null when it is placed. Main wins an overlap (the amendment's §3.2). */
        fun refusal(regionEnd: Int, size: Int, mainEnd: Int?): String? = when {
            size > CAP -> "its $size B pass the cap of $CAP B (decisions BQ (4))"
            mainEnd != null && mainEnd > baseFor(regionEnd, size) -> "Main's staging ends at $mainEnd, past the base ${baseFor(regionEnd, size)} " +
                "the block's $size B would take (Main wins)"
            else -> null
        }
    }
}
