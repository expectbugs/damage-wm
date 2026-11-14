package wm.damage.core.shell

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P4 (`CONTRACT.md` §11.1, §11.3): the one shared index-to-anchor helper of the glasses'
 * presenters. A list keeps an index cursor for paint; what is saved is the id of the row it rests on, so a restore
 * lands on the same row when the list has changed underneath (a book added to the shelf, a row that comes and goes).
 * The index is still saved beside it: it is what an older build reads (the downgrade copy).
 */

/**
 * One list's cursor anchor: [saveAnchor] names the row an index rests on; [restore] holds a saved anchor until the
 * rows it names have arrived, and [resolve] then gives the index it names — once.
 */
class AnchorCursor {
    /** The anchor restored and not yet resolved against rows, or null. */
    var pending: String? = null
        private set

    /** Hold [anchor] (a saved row id, or null for none) until [resolve] meets the rows. */
    fun restore(anchor: String?) {
        pending = anchor?.takeIf { it.isNotEmpty() }
    }

    /**
     * The index of the pending anchor in [ids], or null when none is pending or [ids] does not hold it (the caller keeps
     * the index it restored). The anchor is spent either way: content that arrived decides it once, and a later list
     * never moves a cursor that input has moved since.
     */
    fun resolve(ids: List<String>): Int? {
        val a = pending ?: return null
        pending = null
        return ids.indexOf(a).takeIf { it >= 0 }
    }

    /** Drop the pending anchor: input moved the cursor before the rows arrived. */
    fun clear() {
        pending = null
    }

    companion object {
        /** The anchor of [index] in [ids] (the id of the row it rests on), or "" when the index names no row. */
        fun saveAnchor(ids: List<String>, index: Int): String = ids.getOrNull(index) ?: ""
    }
}
