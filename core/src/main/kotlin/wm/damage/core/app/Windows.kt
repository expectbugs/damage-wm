package wm.damage.core.app

import wm.damage.core.windows.reader.ReaderCore

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q2 (the spec's §2.5; `CONTRACT.md` §3.1, §4.1, §9.7 item 2): the one list of converted windows.
 * Each entry is what every host knows of a window without its running parts — its id and title, its contract version,
 * the actions the record judges, the level each action needs, the level the window needs, and which of its actions go
 * into an outside program (never queued, `(conn, k)`). `AppHost.WINDOWS`/`LEVELS`/`minLevel`, `Contracts.MINE` and
 * the mismatch line's titles, and `Persistence`'s converted windows (their keys leave the sync channel) read it.
 *
 * The running half of a window on the PC — its PC-served scopes, its program handler and its card on Main — is handed
 * to the `app` lane by the host builders (`AppNet.serve`), as those parts are made from the host's providers.
 *
 * Each window package adds its own line, in the build's order (the spec's §6): notices (Q3), torrents (Q4), files
 * (Q5), each at contract version 1 (S1-2 R0 §2). A line goes in with the window's conversion and not before: an entry
 * takes the window's keys off the sync channel and makes the PC share a window it must then serve.
 */
object Windows {
    /** One converted window. [levelOf] gives the level an action needs (null: no such action, which then needs
     *  full); [program] names the actions that go into an outside program. */
    data class Entry(
        val id: String,
        val title: String,
        val version: Int,
        val actions: ActionTable,
        val levelOf: (String) -> String?,
        val minLevel: String,
        val program: Set<String> = emptySet(),
    )

    /** The converted windows, in Main's order. */
    val ALL: List<Entry> = listOf(
        Entry(ReaderCore.WINDOW, "Reader", 1, ReaderCore.ACTIONS, ReaderCore::levelOf, Levels.VIEW),
    )

    private val BY_ID: Map<String, Entry> = ALL.associateBy { it.id }

    fun entry(window: String): Entry? = BY_ID[window]

    /** True when [window] is converted: its keys never sync, its state is the PC record's. */
    fun converted(window: String): Boolean = window in BY_ID

    /** Each converted window's contract version (the `app` lane's `hello` and `welcome`). */
    val VERSIONS: Map<String, Int> = ALL.associate { it.id to it.version }

    /** The name a presenter shows for [window]. */
    fun title(window: String): String = BY_ID[window]?.title ?: window

    /** The record's judge of every converted window's actions. */
    val ACTIONS = ActionTable { w, a -> BY_ID[w]?.actions?.spec(w, a) }

    /** The level each converted window's action needs. */
    val LEVELS = ActionLevels { w, a -> BY_ID[w]?.levelOf?.invoke(a) }

    /** The level a window's subscription needs (its description's `min`); a window this build does not know, full. */
    fun minLevel(window: String): String = BY_ID[window]?.minLevel ?: Levels.FULL

    /** True when [action] of [window] goes into an outside program (never queued). */
    fun isProgram(window: String, action: String): Boolean = BY_ID[window]?.program?.contains(action) == true
}
