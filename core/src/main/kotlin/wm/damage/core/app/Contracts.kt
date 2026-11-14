package wm.damage.core.app

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P2 (`CONTRACT.md` §9.7 item 2, §13 (b) "contract version per window"; dec. A.4): the version of
 * each converted window's contract this build speaks. The `app` lane's `hello` carries the client's, `welcome` the
 * PC's; a window whose two versions differ is not shared on that connection — its subscriptions and ops are refused on
 * the PC, and each side runs it from its own copy — until both match (a deploy and an install, never a timer).
 */
object Contracts {
    /** This build's contract version per converted window: Reader is the first (S1-1). */
    val MINE: Map<String, Int> = mapOf("reader" to 1)

    /** The name a presenter shows for a window in the mismatch line. */
    private val TITLES = mapOf("reader" to "Reader")

    /** One window's two versions; null = that side does not convert it (or speaks no `app` lane at all). */
    data class Match(val window: String, val mine: Int?, val theirs: Int?) {
        val same: Boolean get() = mine != null && mine == theirs
    }

    /**
     * What the presenters read for each window this side converts: [Unknown] until the PC has answered once (no
     * line is shown — the PC is only out of reach), then [Known]. [Known.oldPc] = the PC refused the `app` lane in
     * band or speaks another lane protocol: every window is on different builds.
     */
    sealed class State {
        object Unknown : State() {
            override fun toString() = "Unknown"
        }

        data class Known(val matches: Map<String, Match>, val oldPc: Boolean = false) : State()

        /** True when [window] is shared on this connection (both sides speak its version). */
        fun shared(window: String): Boolean = this is Known && matches[window]?.same == true

        /** The line Main and the window's status show while the two builds differ, or null. */
        fun line(window: String): String? =
            if (this is Known && matches[window]?.same != true) mismatchLine(window) else null
    }

    /** Every window either side names, with both versions. */
    fun compare(mine: Map<String, Int>, theirs: Map<String, Int>): Map<String, Match> =
        (mine.keys + theirs.keys).sorted().associateWith { Match(it, mine[it], theirs[it]) }

    /** The windows both sides speak at the same version. */
    fun shared(mine: Map<String, Int>, theirs: Map<String, Int>): Set<String> =
        mine.filter { (w, v) -> theirs[w] == v }.keys

    /** C§9.7 item 2's line. */
    fun mismatchLine(window: String): String =
        "PC and phone on different builds for ${TITLES[window] ?: window}"
}
