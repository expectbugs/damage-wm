package wm.damage.core.windows.games.kit

/**
 * Seats and the PER-SEAT VIEW PROJECTION (`HOLDEM.md` §6). Kept from the
 * multiplayer design that verdict 3 removed, because a bot needs exactly the
 * same projection a remote player would: **a seat is only ever handed what it
 * can see.** Nothing here knows what Hold'em is.
 *
 * That is not ceremony. A decision function that can reach the whole table
 * state can accidentally read another seat's holding, and the resulting bot
 * plays perfectly for reasons nobody can find. Projecting first makes the leak
 * impossible rather than forbidden.
 */
object Seats {

    /** Who is in a seat. [botId] is the roster character's id, null for you. */
    data class Occupant(val id: String, val name: String, val human: Boolean) {
        val botId: String? get() = if (human) null else id
    }

    /**
     * One seat at a table. Chips are dollars 1:1, so everything is an Int.
     * [committed] is this BETTING ROUND's contribution; [contributed] is the
     * whole hand's — side pots are built from the latter (`Pots`).
     */
    data class Seat(
        val index: Int,
        val who: Occupant,
        val stack: Int,
        val cards: List<Card> = emptyList(),
        val folded: Boolean = false,
        val allIn: Boolean = false,
        val committed: Int = 0,
        val contributed: Int = 0,
        /** Out of the tournament — the seat is cleared (verdict 19). */
        val busted: Boolean = false,
        /** The last thing this seat did, for the table's per-seat line (§34). */
        val lastAction: String = "",
    ) {
        val live: Boolean get() = !folded && !busted
        /** Can still act: live, with chips behind. */
        val canAct: Boolean get() = live && !allIn && stack > 0
    }

    /**
     * The projection. Every seat but [viewer] comes back with its holding
     * replaced by [hiddenCount] anonymous placeholders, so a caller can draw
     * "two cards face down" without ever being able to read them.
     *
     * [reveal] is the showdown: the seats whose cards are now public.
     */
    fun project(seats: List<Seat>, viewer: Int, reveal: Set<Int> = emptySet()): List<SeatView> =
        seats.map { s ->
            val mine = s.index == viewer
            SeatView(
                seat = s,
                cards = if (mine || s.index in reveal) s.cards else emptyList(),
                hiddenCount = if (mine || s.index in reveal) 0 else s.cards.size,
                isViewer = mine,
            )
        }

    /** What one seat is allowed to know about another. */
    data class SeatView(
        val seat: Seat,
        val cards: List<Card>,
        val hiddenCount: Int,
        val isViewer: Boolean,
    ) {
        val index: Int get() = seat.index
        val name: String get() = seat.who.name
        val stack: Int get() = seat.stack
    }

    /**
     * Seat order clockwise from [from], skipping busted seats. The first
     * element is the seat immediately after [from] — which is the small blind
     * after the button, and the seat odd chips start at (`Pots` rule 3).
     */
    fun orderFrom(seats: List<Seat>, from: Int): List<Int> {
        val n = seats.size
        val out = ArrayList<Int>(n)
        for (k in 1..n) {
            val i = (from + k) % n
            if (!seats[i].busted) out.add(i)
        }
        return out
    }

    /**
     * The OPPONENT STRIP: every seat but [viewer], in table order starting
     * from the seat on the viewer's left. Busted seats stay in the list — a
     * cell that empties must not shuffle the others along, and the rotation is
     * by a seat index that is fixed for the tournament, so an opponent keeps
     * the same cell from the first hand to the last.
     */
    fun strip(seats: List<Seat>, viewer: Int): List<Seat> {
        val n = seats.size
        if (n <= 1) return emptyList()
        val v = viewer.mod(n)
        return (1 until n).map { seats[(v + it) % n] }
    }

    /** The next seat after [from] that satisfies [ok], or null. */
    fun next(seats: List<Seat>, from: Int, ok: (Seat) -> Boolean): Int? {
        val n = seats.size
        for (k in 1..n) {
            val i = (from + k) % n
            if (ok(seats[i])) return i
        }
        return null
    }
}
