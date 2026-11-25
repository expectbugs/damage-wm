package wm.damage.core.app

import wm.damage.core.windows.reader.ReaderCore
import wm.damage.core.windows.torrents.TorrentsCore

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q2 (the spec's §2.5; `CONTRACT.md` §3.1, §4.1, §9.7 item 2): the one list of converted windows.
 * Each entry is what every host knows of a window without its running parts — its id and title, its contract version,
 * the actions the record judges, the level each action needs, the level the window needs, and which of its actions go
 * into an outside program (never queued, `(conn, k)`). `AppHost.WINDOWS`/`LEVELS`/`minLevel`, `Contracts.MINE` and
 * the mismatch line's titles read it.
 *
 * The running half of a window on the PC — its PC-served scopes, its program handler and its card on Main — is handed
 * to the `app` lane by the host builders (`AppNet.serve`), as those parts are made from the host's providers.
 *
 * Each window package adds its own line, in the build's order (the spec's §6): notices (Q3, in), torrents (Q4, in),
 * files (Q5), each at contract version 1 (S1-2 R0 §2). A line goes in with the window's conversion and not before: an entry
 * makes the PC share a window it must then serve.
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
        /** S1-2 Q3: the window's own rule at apply time, or null. */
        val rule: ApplyRule? = null,
        /** S1-5 W: the window's own settings rows in [WindowState] (its core's `settings` function, the rows its page and
         *  description carry), or null when its core describes none. `desc/settings` composes them ([PcScopes]). */
        val rows: ((WindowState) -> kotlinx.serialization.json.JsonArray)? = null,
    )

    /** The converted windows, in Main's order. */
    val ALL: List<Entry> = listOf(
        Entry(ReaderCore.WINDOW, "Reader", 1, ReaderCore.ACTIONS, ReaderCore::levelOf, Levels.VIEW, rows = ReaderCore::settings),
        // S1-2 Q3: the PC's notice record (C§7.1; decisions A.1): a pseudo-window with no card and no scopes of its own
        Entry(Notices.WINDOW, "Notices", 1, Notices.ACTIONS, Notices::levelOf, Levels.VIEW, rule = Notices.RULE),
        // S1-3 B16: Tmux (C§12; min full): its rows and mutes in the record; every key and session action a program op
        // into tmux, never queued. After Reader, as the glasses' Main lists it
        Entry(wm.damage.core.windows.tmux.TmuxCore.WINDOW, "Tmux", 1, wm.damage.core.windows.tmux.TmuxCore.ACTIONS,
            wm.damage.core.windows.tmux.TmuxCore::levelOf, Levels.FULL, program = wm.damage.core.windows.tmux.TmuxCore.PROGRAM,
            rows = wm.damage.core.windows.tmux.TmuxCore::settings),
        // S1-2 Q5: Files (C§12; min full, C§10.4), its program ops into the filesystem; its rule keeps the core's clear of
        // a cut to the clip it names (L1 F2: the rows take what they are given; every reader drops a bad value). Before
        // Torrents, as the glasses' Main lists them (S1-2 R2 #15)
        Entry(wm.damage.core.windows.files.FilesCore.WINDOW, "Files", 1, wm.damage.core.windows.files.FilesCore.ACTIONS,
            wm.damage.core.windows.files.FilesCore::levelOf, Levels.FULL, program = wm.damage.core.windows.files.FilesCore.PROGRAM,
            rule = wm.damage.core.windows.files.FilesCore.RULE, rows = wm.damage.core.windows.files.FilesCore::settings),
        // S1-2 Q4: Torrents (C§12): its rows and search recents in the record; its program actions go into qBittorrent,
        // the tracker and `xdg-open`, never queued
        Entry(TorrentsCore.WINDOW, "Torrents", 1, TorrentsCore.ACTIONS, TorrentsCore::levelOf, Levels.VIEW,
            program = TorrentsCore.PROGRAM, rows = TorrentsCore::settings),
        // S1-3 B16: Feed (C§12): its reading marks, places and rows in the record; refresh and browse go to the sites
        Entry(wm.damage.core.windows.feed.FeedCore.WINDOW, "Feed", 1, wm.damage.core.windows.feed.FeedCore.ACTIONS,
            wm.damage.core.windows.feed.FeedCore::levelOf, Levels.VIEW, program = wm.damage.core.windows.feed.FeedCore.PROGRAM,
            rows = wm.damage.core.windows.feed.FeedCore::settings),
        // S1-4 K: Music (C§12): its queue, place and output written by the host that plays (its rule), its rows beside
        // them; every transport and queue act a program op through the PC's door (`MusicProgram`). Its core describes
        // the rows that are record items (S1-5 F); the player's rows stay the player's
        Entry(wm.damage.core.windows.music.MusicCore.WINDOW, "Music", 1, wm.damage.core.windows.music.MusicCore.ACTIONS,
            wm.damage.core.windows.music.MusicCore::levelOf, Levels.VIEW, program = wm.damage.core.windows.music.MusicCore.PROGRAM,
            rule = wm.damage.core.windows.music.MusicCore.RULE, rows = wm.damage.core.windows.music.MusicCore::settings),
        // S1-5 G: Games (C§12): its world (the table in play, the bankroll, the world and every character) and its rows,
        // written by the one engine host at its settle points; no program actions, no rule
        Entry(wm.damage.core.windows.games.GamesCore.WINDOW, "Games", 1, wm.damage.core.windows.games.GamesCore.ACTIONS,
            wm.damage.core.windows.games.GamesCore::levelOf, Levels.VIEW, rows = wm.damage.core.windows.games.GamesCore::settings),
        // S1-5 S: Settings (C§6): one item per stored field and one per app's style, set by whichever surface he uses
        // (the latest input wins); no card, no program actions, no rule. Last, as the glasses' Main lists it
        Entry(wm.damage.core.shell.SettingsCore.WINDOW, "Settings", 1, wm.damage.core.shell.SettingsCore.ACTIONS,
            wm.damage.core.shell.SettingsCore::levelOf, Levels.VIEW),
    )

    private val BY_ID: Map<String, Entry> = ALL.associateBy { it.id }

    fun entry(window: String): Entry? = BY_ID[window]

    /** Each converted window's contract version (the `app` lane's `hello` and `welcome`). */
    val VERSIONS: Map<String, Int> = ALL.associate { it.id to it.version }

    /** The name a presenter shows for [window]. */
    fun title(window: String): String = BY_ID[window]?.title ?: window

    /** The record's judge of every converted window's actions. */
    val ACTIONS = ActionTable { w, a -> BY_ID[w]?.actions?.spec(w, a) }

    /** The level each converted window's action needs. */
    val LEVELS = ActionLevels { w, a -> BY_ID[w]?.levelOf?.invoke(a) }

    /** S1-2 Q3: every converted window's own apply-time rule (`AppHost`). */
    val RULES = ApplyRule { rec, op -> BY_ID[op.window]?.rule?.admit(rec, op) }

    /** The level a window's subscription needs (its description's `min`); a window this build does not know, full. */
    fun minLevel(window: String): String = BY_ID[window]?.minLevel ?: Levels.FULL

    /** True when [action] of [window] goes into an outside program (never queued). */
    fun isProgram(window: String, action: String): Boolean = BY_ID[window]?.program?.contains(action) == true
}

/**
 * S1-2 Q3: a window's own rule at apply time, beside the one merge rule (which it never changes): asked on the executor
 * after the op's shape passed and before the judge. Null = the merge rule decides alone. The notice record is the first
 * window with one ([Notices.RULE]: ordered by its occurrence and a state that never moves backwards, not by time;
 * raise and clear are the PC's own).
 */
fun interface ApplyRule {
    fun admit(rec: RecordView, op: Op): Admit?
}

/** What an [ApplyRule] decides in the judge's place. */
sealed class Admit {
    /** Refused for good: nothing changes. */
    data class Refuse(val answer: Answer.Refused) : Admit()

    /** Taken and answered, and nothing changes: the record keeps what it holds (L1 C1: nothing is said). */
    object Keep : Admit() {
        override fun toString() = "Keep"
    }

    /** Written as the op makes it whatever the times: the window's rule orders it. */
    object Apply : Admit() {
        override fun toString() = "Apply"
    }
}
