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

/** What a presenter asks a window to do: an op without its id, its device or its cursor, which the source adds
 *  (C§2.4: the result, never a recipe). [at]: the time of the input that made it, when the presenter knows it (Reader's
 *  place, set at its save point after the scroll); null = the source stamps its clock as it takes the draft. */
data class Draft(
    val action: String,
    val targets: List<String>,
    val result: JsonElement = JsonNull,
    val params: JsonObject = JsonObject(emptyMap()),
    val at: Long? = null,
)

/**
 * One window's state as a source shows it: the record's items of [window] (full keys `rec.<window>.<name>`) with
 * this device's ops that are not yet in them applied on top. [pending] = the keys an unanswered op of this device
 * writes; [cursor] = the record's place the entries stand at. Immutable: one per paint (C§13 (b) "working copy" (e)).
 */
class WindowState(
    val window: String,
    val items: Map<String, Item>,
    val pending: Set<String> = emptySet(),
    val cursor: Cursor? = null,
) : RecordView {
    override fun item(key: String): Item? = items[key]

    /** The item [name] of this window (its key less `rec.<window>.`). */
    fun itemNamed(name: String): Item? = items[Record.key(window, name)]

    /**
     * The keys whose drawn form differs from [before]'s: a value or a removal. A writer or a time is not drawn, so an
     * answer that only confirms what was shown changes nothing here and nothing repaints (C§2.3 step 3: "equal to what
     * it showed: nothing repaints").
     */
    fun drawnChanges(before: WindowState?): Set<String> {
        val out = HashSet<String>()
        val bi = before?.items ?: emptyMap()
        for (k in items.keys + bi.keys) if (drawn(items[k]) != drawn(bi[k])) out += k
        return out
    }

    override fun toString() = "WindowState($window, ${items.size} items, pending $pending, at $cursor)"

    companion object {
        fun empty(window: String) = WindowState(window, emptyMap())

        private fun drawn(i: Item?): Any? = i?.let { Pair(if (it.gone) null else it.value, it.gone) }
    }
}

/** What a source tells its presenters, on the core executor: hand off to the presenter's own loop, never block. */
interface SourceListener {
    /** The state moved; [keys] = the entries whose drawn form changed (empty: only writers or times moved). */
    fun changed(state: WindowState, keys: Set<String>) {}

    /** An op of this device was answered (C§4.4). */
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

    /** This device's clock, as its ops are stamped with it (L1 C1: a presenter that keeps an input's time reads it). */
    fun now(): Long = System.currentTimeMillis()
}

/** What a [LocalWindowSource] sits on: the PC's record or the phone's working copy. Every call is made on [executor]. */
interface RecordBackend {
    val executor: CoreExecutor

    /** The window's state now. */
    fun stateOf(window: String): WindowState

    /** Names [draft] in this device's stream, stamps it ([Draft.at], or the backend's clock) and takes it. */
    fun submit(window: String, draft: Draft)

    fun watch(window: String, l: SourceListener)

    fun unwatch(window: String, l: SourceListener)

    /** Fix round 2, ruling 1.7: a line in this host's journal (a refusal for good no presenter heard). */
    fun journal(line: String) {}

    /** The clock this backend stamps a draft with when the draft carries no input time ([Draft.at]). */
    fun now(): Long = System.currentTimeMillis()
}
