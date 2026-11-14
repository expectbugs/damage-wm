package wm.damage.core.app

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P3 (`CONTRACT.md` §2.2, §2.3, §11.1; the spec's §2 P3): the one seam a presenter reads a window
 * through, so it cannot tell which host it runs on. On the PC the source sits on the PC's own record (`HostBackend`);
 * on the phone on the working copy (`WorkingCopy`). Both run on one core executor per host. A presenter reads an
 * immutable [WindowState] per paint, never waits: [WindowSource.act] returns at once, and the state's changes and the
 * ops' answers come back through [SourceListener] on the executor.
 */

/** What a presenter asks a window to do: an op without its numbering, its device or its cursor, which the source adds
 *  (C§2.4: the result, never a recipe; what it expected to find). */
data class Draft(
    val action: String,
    val targets: List<String>,
    val result: JsonElement = JsonNull,
    val expect: Expect = Expect.Any,
    val params: JsonObject = JsonObject(emptyMap()),
)

/**
 * One window's state as a source shows it: the record's entries of [window] (full keys `rec.<window>.<name>`) with
 * this device's ops that are not yet in them applied on top. [pending] = the keys an unanswered op of this device
 * writes; [cursor] = the record's place the entries stand at. Immutable: one per paint (C§13 (b) "working copy" (e)).
 */
class WindowState(
    val window: String,
    val items: Map<String, Item>,
    val colls: Map<String, Coll>,
    val pending: Set<String> = emptySet(),
    val cursor: Cursor? = null,
) : RecordView {
    override fun item(key: String): Item? = items[key]
    override fun coll(key: String): Coll? = colls[key]

    /** The item [name] of this window (its key less `rec.<window>.`). */
    fun itemNamed(name: String): Item? = items[Record.key(window, name)]

    fun collNamed(name: String): Coll? = colls[Record.key(window, name)]

    /** Entries that carry a "not applied" mark (C§2.4; the away line's count). */
    val notApplied: Int get() = items.values.count { it.notApplied != null } + colls.values.count { it.notApplied != null }

    /**
     * The keys whose drawn form differs from [before]'s: a value, a removal, an order, or the losing value of a "not
     * applied" mark. A revision, a writer or a time is not drawn, so an answer that only confirms what was shown
     * changes nothing here and nothing repaints (C§2.3 step 3: "equal to what it showed: nothing repaints").
     */
    fun drawnChanges(before: WindowState?): Set<String> {
        val out = HashSet<String>()
        val bi = before?.items ?: emptyMap()
        val bc = before?.colls ?: emptyMap()
        for (k in items.keys + bi.keys) if (drawn(items[k]) != drawn(bi[k])) out += k
        for (k in colls.keys + bc.keys) if (drawn(colls[k]) != drawn(bc[k])) out += k
        return out
    }

    override fun toString() = "WindowState($window, ${items.size} items, ${colls.size} collections, pending $pending, at $cursor)"

    companion object {
        fun empty(window: String) = WindowState(window, emptyMap(), emptyMap())

        private fun drawn(i: Item?): Any? = i?.let { Triple(if (it.gone) null else it.value, it.gone, it.notApplied?.mine) }
        private fun drawn(c: Coll?): Any? = c?.let { Pair(it.order, it.notApplied?.mine) }
    }
}

/** What a source tells its presenters, on the core executor: hand off to the presenter's own loop, never block. */
interface SourceListener {
    /** The state moved; [keys] = the entries whose drawn form changed (empty: only revisions or writers moved). */
    fun changed(state: WindowState, keys: Set<String>) {}

    /** An op of this device was answered (C§4.4). A loss's standing value is already in the state. */
    fun answered(op: Op, answer: Answer) {}
}

/** A window's core as the seam needs it (C§11.1): the window's own logic over a state, no pixels. Each window's core
 *  adds its typed objects beside this (Reader's arrive in P4). */
interface WindowCore {
    val window: String

    /** The window's description (C§3.1) from [state]. */
    fun describe(state: WindowState): JsonObject

    /** One level's instance by address (C§5), or null when [addr] names nothing. */
    fun level(state: WindowState, addr: String): JsonObject?
}

/** The seam of C§2.2: `desc`, `level(addr)`, `act(op)` and a change flow. `live(src, need)` arrives with the live
 *  sources (S1-2). */
interface WindowSource {
    val window: String

    /** The state now; never blocks. */
    fun state(): WindowState

    fun desc(): JsonObject

    fun level(addr: String): JsonObject?

    /** Takes [draft] and returns at once: no disk, no network and no wait on the caller's thread. The answer comes
     *  through [SourceListener.answered]. */
    fun act(draft: Draft)

    fun addListener(l: SourceListener)

    fun removeListener(l: SourceListener)

    /** Fix round 2, ruling 1.2: a presenter has SAID the loss answered to [op] (its notice call has run on its loop):
     *  the loss is kept until then and handed to each presenter that listens meanwhile. */
    fun said(op: Op) {}
}

/** What a [LocalWindowSource] sits on: the PC's record or the phone's working copy. Every call is made on [executor]. */
interface RecordBackend {
    val executor: CoreExecutor

    /** The window's state now. */
    fun stateOf(window: String): WindowState

    /** Numbers [draft] in this device's stream and takes it. */
    fun submit(window: String, draft: Draft)

    fun watch(window: String, l: SourceListener)

    fun unwatch(window: String, l: SourceListener)

    /** [WindowSource.said], on [executor]: a kept loss of [window]'s [op] is kept no longer. */
    fun said(window: String, op: Op) {}

    /** Fix round 2, ruling 1.7: a line in this host's journal (a refusal for good no presenter heard). */
    fun journal(line: String) {}
}
