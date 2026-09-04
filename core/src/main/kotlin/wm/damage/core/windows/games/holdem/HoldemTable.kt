package wm.damage.core.windows.games.holdem

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import wm.damage.core.util.Log
import wm.damage.core.windows.games.kit.ActionLevel
import wm.damage.core.windows.games.kit.Card
import wm.damage.core.windows.games.kit.Deck
import wm.damage.core.windows.games.kit.HandEval
import wm.damage.core.windows.games.kit.Money
import wm.damage.core.windows.games.kit.Pots
import wm.damage.core.windows.games.kit.Rng
import wm.damage.core.windows.games.kit.Seats

/**
 * The Hold'em engine (`HOLDEM.md` §5, §12) — a 6-max single-table sit-and-go.
 * No UI depends on it and it depends on no UI: M3 exists so the engine is
 * provably right before a pixel rests on it.
 *
 * 🔴 **The state is an ACTION LOG, not a snapshot** (§11.2). What persists is
 * the tournament seed, the hand number, the button, the stacks AT THE START of
 * the hand, who is busted, and the actions taken so far. Everything else — the
 * deck, who holds what, the board, the pot, whose turn it is — is REPLAYED,
 * exactly, every time. That gives the two properties the Tmux Focus-mode
 * failure taught this project to demand: a resumed hand is the same hand, and
 * a resumed bot is the same bot.
 *
 * 🔴 **No rebuys, no top-ups, no re-entry** (verdict 11). Play until one player
 * holds every chip. Busted seats are cleared and the table shrinks 6→5→…→2.
 */
class HoldemTable private constructor(
    val spec: HoldemRules.Table,
    val tournamentSeed: Long,
    val occupants: List<Seats.Occupant>,
    /** Stacks at the START of the current hand — the replay baseline. */
    private val handStacks: IntArray,
    /** 0 = still in; otherwise the ORDER they went out in, 1 = first out. An
     *  order rather than a hand number so two players busting on the same hand
     *  still get distinct finishing places (§7 standings). */
    private val bustedAt: IntArray,
    handNo0: Int,
    button0: Int,
    private val log: ArrayList<Act>,
) {

    /** The hand being played, 0-based. Drives the blind level (§5.2). */
    var handNo: Int = handNo0
        private set

    /** The dealer button's seat. Moves to the next seat still in (§5.2). */
    var button: Int = button0
        private set

    /** One recorded action. [to] is the seat's TOTAL for the street after it —
     *  a total rather than a delta so a replay cannot drift, and so a
     *  mis-attributed seat is caught rather than absorbed. */
    data class Act(val seat: Int, val kind: ActionLevel.Kind, val to: Int)

    /** A settled hand, for the table's result line and the history level. */
    data class Result(
        val handNo: Int,
        val pots: List<Pots.SidePot>,
        val uncalled: Pots.Uncalled?,
        val won: Map<Int, Int>,
        /** Seat → showdown score; empty when everyone else folded. */
        val scores: Map<Int, Int>,
        /** Seats whose cards are now public. */
        val shown: Set<Int>,
        val line: String,
    )

    /** Everything a paint or a bot needs, all of it derived. */
    class View(
        val handNo: Int,
        val level: Int,
        val sb: Int,
        val bb: Int,
        val button: Int,
        val sbSeat: Int,
        val bbSeat: Int,
        val seats: List<Seats.Seat>,
        val board: List<Card>,
        val street: Street,
        /** Whose turn, or null when the hand needs no more actions. */
        val toAct: Int?,
        val currentBet: Int,
        val lastRaiseSize: Int,
        val pot: Int,
        /** Per seat: has it acted since the last FULL raise? A seat that has
         *  may call or fold but not raise — §12 item 4. */
        val actedSinceRaise: List<Boolean>,
        val result: Result?,
        /** Street-by-street action, for the 480 history band and the log level. */
        val history: List<String>,
    ) {
        val liveSeats: List<Seats.Seat> get() = seats.filter { it.live }
        val activeSeats: List<Seats.Seat> get() = seats.filter { !it.busted }
        fun seat(i: Int): Seats.Seat = seats[i]
        val toCall: Int get() = toAct?.let { maxOf(0, currentBet - seats[it].committed) } ?: 0
        val complete: Boolean get() = result != null
    }

    // ------------------------------------------------------------------ derived
    /** The derived view. VOLATILE because a bot decision computes OFF the
     *  shell loop (`GamesWindow.schedule`) while the loop may read the same
     *  table: the value is identical either way — `replay()` is a pure
     *  function of state nothing mutates while a decision is in flight — but
     *  the publication has to be real, not assumed. */
    @Volatile private var cache: View? = null

    fun view(): View = cache ?: replay().also { cache = it }

    private fun invalidate() {
        cache = null
    }

    /** The deck for THIS hand — derived, never stored (§11.2). */
    private fun deck(): List<Card> = Deck.shuffled(Rng.hash(tournamentSeed, handNo.toLong()))

    /** Seats still in the tournament, in seat order. */
    fun activeSeats(): List<Int> = occupants.indices.filter { bustedAt[it] == 0 }

    /** How many actions this hand has taken — the DECISION COUNTER the bots'
     *  counter-based RNG is keyed by (§11.2). It is derived from the log, so a
     *  resumed decision draws exactly the same numbers. */
    val actionCount: Int get() = log.size

    /**
     * Rebuild the whole hand from (seed, handNo, handStacks, button, log).
     * Cheap — a hand is at most a few dozen actions — and it is what makes the
     * persisted record a few hundred bytes.
     */
    private fun replay(): View {
        val n = occupants.size
        val level = HoldemRules.levelOf(handNo)
        val sb = spec.sbAt(level)
        val bb = spec.bbAt(level)
        val active = activeSeats()
        // the BUTTON must sit on a live seat. `nextHand` keeps it there, but
        // `cashOut` can empty the seat it is on mid-tournament, and heads-up
        // that seat posts the small blind — the blinds would land on a player
        // who is not in the hand.
        val button = if (bustedAt[this.button] == 0 || active.isEmpty()) this.button
        else nextActive(this.button)
        if (active.size <= 1) {
            // the tournament is OVER: there is no hand to deal, and dealing
            // one would post a small AND a big blind against the winner's own
            // stack — `nextActive` wraps to the only seat left, so both blinds
            // land on the same player and the final stack reads wrong
            val seats = occupants.indices.map { i ->
                Seats.Seat(i, occupants[i], handStacks[i], busted = bustedAt[i] != 0)
            }
            return View(handNo, level, sb, bb, button, button, button, seats, emptyList(),
                Street.SHOWDOWN, null, 0, bb, 0, List(n) { false }, null, emptyList())
        }
        val stack = handStacks.copyOf()
        val committed = IntArray(n)          // this street
        val contributed = IntArray(n)        // this hand
        val folded = BooleanArray(n)
        val allIn = BooleanArray(n)
        val last = arrayOfNulls<String>(n)

        // the deal: two cards each from the small blind round, then the board.
        // The deal ORDER depends on who is still in, which is persisted, so
        // the same record always deals the same cards.
        val d = deck()
        val holes = HashMap<Int, MutableList<Card>>()
        val dealOrder = Seats.orderFrom(seatsForOrder(), button)
        var di = 0
        repeat(2) {
            for (i in dealOrder) holes.getOrPut(i) { ArrayList(2) }.add(d[di++])
        }
        val boardAll = (0 until 5).map { d[di + it] }

        // blinds. HEADS-UP: the BUTTON posts the small blind, acts first
        // preflop and last postflop (§5.2).
        val heads = active.size == 2
        val sbSeat = if (heads) button else nextActive(button)
        val bbSeat = nextActive(sbSeat)
        fun post(seat: Int, amount: Int) {
            val v = minOf(amount, stack[seat])
            stack[seat] -= v
            committed[seat] += v
            contributed[seat] += v
            if (stack[seat] == 0) allIn[seat] = true
        }
        post(sbSeat, sb)
        post(bbSeat, bb)

        var street = Street.PREFLOP
        var currentBet = committed.max()
        var lastRaiseSize = bb
        val acted = BooleanArray(n)
        val history = ArrayList<String>()

        fun liveCount() = active.count { !folded[it] }
        fun actors() = active.filter { !folded[it] && !allIn[it] }

        fun nextToAct(from: Int): Int? {
            for (k in 1..n) {
                val i = (from + k) % n
                if (bustedAt[i] == 0 && !folded[i] && !allIn[i]) return i
            }
            return null
        }

        /** Betting is over for good on this street. */
        fun bettingOver(): Boolean {
            if (liveCount() <= 1) return true
            val a = actors()
            if (a.isEmpty()) return true
            // one player with chips and nothing to call has nobody to bet
            // against — the rest of the board simply runs out
            return a.size == 1 && committed[a[0]] >= currentBet
        }

        fun roundClosed(): Boolean {
            if (bettingOver()) return true
            for (i in actors()) if (!acted[i] || committed[i] != currentBet) return false
            return true
        }

        var turn: Int? = if (heads) {
            if (!folded[sbSeat] && !allIn[sbSeat]) sbSeat else nextToAct(sbSeat)
        } else nextToAct(bbSeat)

        fun advanceStreet() {
            for (i in active) committed[i] = 0
            acted.fill(false)
            currentBet = 0
            lastRaiseSize = bb
            street = street.next()
            // postflop the first live seat after the BUTTON acts, which
            // heads-up is the big blind — the button acts last
            turn = if (street == Street.SHOWDOWN) null else nextToAct(button)
        }

        var li = 0
        var guard = 0
        while (guard++ < 4096) {
            if (roundClosed()) turn = null
            if (turn == null) {
                if (street == Street.SHOWDOWN || liveCount() <= 1) break
                advanceStreet()
                continue
            }
            if (li >= log.size) break                 // waiting for the next action
            val a = log[li]
            val t = turn!!
            if (a.seat != t) {
                // NO SILENT FAILURES: a log that does not replay is a defect,
                // and continuing would deal a different hand under the old pot
                throw IllegalStateException(
                    "hand $handNo replay diverged at action $li: the log says seat ${a.seat} " +
                        "acted, the rules say seat $t was to act")
            }
            when (a.kind) {
                ActionLevel.Kind.FOLD -> {
                    folded[t] = true
                    last[t] = "fold"
                    history.add("${street.label}: ${occupants[t].name} folds")
                }
                ActionLevel.Kind.CHECK -> {
                    if (committed[t] != currentBet) throw IllegalStateException(
                        "hand $handNo action $li: seat $t cannot check facing a bet")
                    last[t] = "check"
                    history.add("${street.label}: ${occupants[t].name} checks")
                }
                ActionLevel.Kind.NAV -> throw IllegalStateException(
                    "hand $handNo action $li: NAV is not a table action")
                else -> {
                    val target = minOf(a.to, committed[t] + stack[t])
                    val delta = target - committed[t]
                    if (delta < 0) throw IllegalStateException(
                        "hand $handNo action $li: seat $t cannot un-bet (${a.to} < ${committed[t]})")
                    stack[t] -= delta
                    committed[t] = target
                    contributed[t] += delta
                    if (stack[t] == 0) allIn[t] = true
                    val raised = target > currentBet
                    if (raised) {
                        if (HoldemRules.isFullRaise(target, currentBet, lastRaiseSize)) {
                            lastRaiseSize = target - currentBet
                            // a FULL raise reopens the betting for everyone
                            // else (§12 item 4); an incomplete all-in does not
                            for (i in active) if (i != t) acted[i] = false
                        }
                        currentBet = target
                    }
                    last[t] = when {
                        allIn[t] -> "all-in ${Money.fmt(target)}"
                        !raised -> "call ${Money.fmt(target)}"
                        street == Street.PREFLOP || a.kind == ActionLevel.Kind.RAISE ->
                            "raise ${Money.fmt(target)}"
                        else -> "bet ${Money.fmt(target)}"
                    }
                    history.add("${street.label}: ${occupants[t].name} ${last[t]}")
                }
            }
            acted[t] = true
            li++
            turn = if (roundClosed()) null else nextToAct(t)
        }
        if (li < log.size) throw IllegalStateException(
            "hand $handNo replay ended with ${log.size - li} action(s) unconsumed — the record " +
                "and the rules disagree")
        if (liveCount() <= 1) street = Street.SHOWDOWN

        val board = boardAll.take(if (street == Street.SHOWDOWN) 5 else street.boardCards)

        var result: Result? = null
        if (street == Street.SHOWDOWN) {
            val entries = active.map { Pots.Entry(it, contributed[it], folded[it]) }
            val live = active.filter { !folded[it] }
            val shown: Set<Int>
            val scores: Map<Int, Int>
            if (live.size == 1) {
                scores = mapOf(live[0] to 0)
                shown = emptySet()
            } else {
                scores = live.associateWith { HandEval.score(holes.getValue(it) + boardAll) }
                shown = live.toSet()
            }
            val order = Seats.orderFrom(seatsForOrder(), button)
            val s = Pots.settleHand(entries, scores, order)
            for ((seat, v) in s.won) stack[seat] += v
            s.uncalled?.let { stack[it.seat] += it.amount }
            result = Result(handNo, s.pots, s.uncalled, s.won, scores, shown,
                resultLine(s, scores, live))
        }

        val seats = occupants.indices.map { i ->
            Seats.Seat(
                index = i,
                who = occupants[i],
                stack = stack[i],
                cards = holes[i]?.toList() ?: emptyList(),
                folded = folded[i],
                allIn = allIn[i],
                committed = committed[i],
                contributed = contributed[i],
                busted = bustedAt[i] != 0,
                lastAction = last[i] ?: "",
            )
        }
        return View(handNo, level, sb, bb, button, sbSeat, bbSeat, seats, board, street, turn,
            currentBet, lastRaiseSize, active.sumOf { contributed[it] },
            acted.toList(), result, history)
    }

    private fun resultLine(s: Pots.Settlement, scores: Map<Int, Int>, live: List<Int>): String {
        if (live.size == 1) return "${occupants[live[0]].name} wins ${Money.fmt(s.won[live[0]] ?: 0)}"
        val best = s.won.entries.maxByOrNull { it.value } ?: return "no winner"
        val bestScore = live.maxOfOrNull { scores[it] ?: 0 } ?: 0
        val tied = live.filter { (scores[it] ?: -1) == bestScore }
        val hand = HandEval.describe(bestScore)
        return if (tied.size > 1)
            "${tied.joinToString(" and ") { occupants[it].name }} split ${Money.fmt(s.total)} with $hand"
        else "${occupants[best.key].name} wins ${Money.fmt(best.value)} with $hand"
    }

    /** A seat list good enough for [Seats.orderFrom]'s busted test. */
    private fun seatsForOrder(): List<Seats.Seat> = occupants.indices.map { i ->
        Seats.Seat(i, occupants[i], 0, busted = bustedAt[i] != 0)
    }

    private fun nextActive(from: Int): Int {
        val n = occupants.size
        for (k in 1..n) {
            val i = (from + k) % n
            if (bustedAt[i] == 0) return i
        }
        throw IllegalStateException("no active seat after $from")
    }

    // ------------------------------------------------------------------ acting
    /**
     * The legal actions for the seat to act, in NATURAL POKER ORDER — row 0 is
     * always the contextual give-up row (Check when checking is free, Fold
     * when facing a bet), which verdict 33 makes one row and does NOT exempt
     * from the confirm.
     */
    fun legalActions(): List<ActionLevel.Action> {
        val v = view()
        val seat = v.toAct ?: return emptyList()
        val s = v.seats[seat]
        val toCall = maxOf(0, v.currentBet - s.committed)
        val out = ArrayList<ActionLevel.Action>(3)
        if (toCall == 0) out.add(ActionLevel.Action(ActionLevel.Kind.CHECK, "Check"))
        else out.add(ActionLevel.Action(ActionLevel.Kind.FOLD, "Fold"))
        if (toCall > 0) {
            val amount = minOf(toCall, s.stack)
            val allIn = amount >= s.stack
            out.add(ActionLevel.Action(
                if (allIn) ActionLevel.Kind.ALL_IN else ActionLevel.Kind.CALL,
                if (allIn) "Call all-in" else "Call ${Money.fmt(amount)}",
                Money.fmt(amount), s.committed + amount))
        }
        if (canRaise(v, seat)) {
            // the verb follows the BET on the table, not what you owe: the big
            // blind facing a table of limpers owes nothing and still RAISES
            val raising = v.currentBet > 0
            out.add(ActionLevel.Action(
                if (raising) ActionLevel.Kind.RAISE else ActionLevel.Kind.BET,
                if (raising) "Raise" else "Bet", "", minRaiseTo(v, seat)))
        }
        return out
    }

    /** Can this seat raise at all? False when calling would put it all in, and
     *  false when it has already acted since the last FULL raise — an all-in
     *  for less than a full raise does not reopen the betting (§12 item 4). */
    fun canRaise(v: View = view(), seat: Int = v.toAct ?: -1): Boolean {
        if (seat < 0) return false
        val s = v.seats[seat]
        val toCall = maxOf(0, v.currentBet - s.committed)
        if (s.stack <= toCall) return false
        return !v.actedSinceRaise[seat]
    }

    /** The smallest legal raise TOTAL, capped by the stack (an all-in for less
     *  is always available). */
    fun minRaiseTo(v: View = view(), seat: Int = v.toAct ?: -1): Int {
        if (seat < 0) return 0
        val s = v.seats[seat]
        return minOf(HoldemRules.minRaiseTo(v.currentBet, v.lastRaiseSize), s.committed + s.stack)
    }

    fun maxRaiseTo(v: View = view(), seat: Int = v.toAct ?: -1): Int {
        if (seat < 0) return 0
        val s = v.seats[seat]
        return s.committed + s.stack
    }

    /** The §10.3 preset ladder for the seat to act. */
    fun sizingLadder(): List<ActionLevel.Action> {
        val v = view()
        val seat = v.toAct ?: return emptyList()
        val s = v.seats[seat]
        val toCall = maxOf(0, v.currentBet - s.committed)
        return ActionLevel.sizingLadder(v.pot, toCall, s.committed,
            minRaiseTo(v, seat), maxRaiseTo(v, seat),
            if (v.currentBet > 0) ActionLevel.Kind.RAISE else ActionLevel.Kind.BET)
    }

    /**
     * Take an action for the seat to act. An illegal action RAISES rather than
     * being clamped: a clamped action is a different hand played in silence.
     */
    fun act(kind: ActionLevel.Kind, to: Int = 0) {
        val v = view()
        val seat = v.toAct ?: throw IllegalStateException("nobody is to act (street=${v.street})")
        val s = v.seats[seat]
        val toCall = maxOf(0, v.currentBet - s.committed)
        val recorded = when (kind) {
            ActionLevel.Kind.FOLD -> Act(seat, ActionLevel.Kind.FOLD, 0)
            ActionLevel.Kind.CHECK -> {
                if (toCall > 0) throw IllegalStateException(
                    "seat $seat cannot check facing ${Money.fmt(toCall)}")
                Act(seat, ActionLevel.Kind.CHECK, s.committed)
            }
            ActionLevel.Kind.CALL -> Act(seat, ActionLevel.Kind.CALL, s.committed + minOf(toCall, s.stack))
            ActionLevel.Kind.BET, ActionLevel.Kind.RAISE, ActionLevel.Kind.ALL_IN -> {
                val max = maxRaiseTo(v, seat)
                val min = minRaiseTo(v, seat)
                val target = if (kind == ActionLevel.Kind.ALL_IN) max else to
                if (target > max) throw IllegalStateException(
                    "seat $seat cannot put in ${Money.fmt(target)}; the stack allows ${Money.fmt(max)}")
                if (target < min && target < max) throw IllegalStateException(
                    "seat $seat must raise to at least ${Money.fmt(min)} or go all-in for ${Money.fmt(max)}")
                if (target <= v.currentBet && target < max) throw IllegalStateException(
                    "seat $seat cannot raise to ${Money.fmt(target)} over a bet of ${Money.fmt(v.currentBet)}")
                if (target > v.currentBet && !canRaise(v, seat)) throw IllegalStateException(
                    "seat $seat may not raise: an all-in for less than a full raise does not reopen the betting")
                Act(seat, kind, target)
            }
            ActionLevel.Kind.NAV -> throw IllegalStateException("NAV is not a table action")
        }
        log.add(recorded)
        invalidate()
    }

    // ------------------------------------------------------------------ hands
    /** The hand is settled and waiting for a tap (verdict 29: a showdown stays
     *  up until you act). True when [nextHand] is the next thing to do. */
    val handComplete: Boolean get() = view().result != null

    /** One player holds every chip (§5.1). Null while the tournament runs. */
    fun winner(): Int? {
        val alive = activeSeats()
        return if (alive.size == 1) alive[0] else null
    }

    /** Chips at this instant, settled or not. */
    fun stackOf(seat: Int): Int = view().seats[seat].stack

    /**
     * Settle the finished hand into the baseline, bust whoever hit zero, move
     * the button and start the next hand. Verdict 19: **busted bots clear the
     * seat** — the table shrinks 6→5→…→2, with no re-entry. Returns false when
     * that leaves one player, i.e. the tournament is over.
     */
    fun nextHand(): Boolean {
        // a finished tournament has no next hand — say so rather than throwing
        // about a result that was never going to exist
        if (activeSeats().size <= 1) return false
        val v = view()
        if (v.result == null) throw IllegalStateException("the hand is not over yet (${v.street})")
        // the stacks THIS hand started with decide the order of a double
        // knock-out: the shorter stack finishes lower, as at a real table
        val startStacks = handStacks.copyOf()
        for (i in occupants.indices) handStacks[i] = v.seats[i].stack
        var order = bustedAt.max()
        val out = occupants.indices.filter { bustedAt[it] == 0 && handStacks[it] <= 0 }
            .sortedBy { startStacks[it] }
        for (i in out) bustedAt[i] = ++order
        log.clear()
        invalidate()
        if (out.isNotEmpty()) Log.i("holdem",
            "hand $handNo: ${out.size} seat(s) out, ${activeSeats().size} left")
        if (activeSeats().size <= 1) return false
        handNo++
        button = nextActive(button)
        return true
    }

    /** The finishing position a seat went out in (1 = the winner, 6 = first
     *  out at a full table), or null while they are still playing. */
    fun finishPlace(seat: Int): Int? {
        if (bustedAt[seat] == 0) return null
        return occupants.size - bustedAt[seat] + 1
    }

    /**
     * Verdict 11: cashing out early returns your stack and **the remaining
     * characters play the tournament out** (§7.5) — the table does not
     * evaporate. The seat is marked out so the engine can finish without you.
     * Only between hands: leaving mid-hand would abandon chips already in the
     * pot.
     */
    fun cashOut(seat: Int): Int {
        val v = view()
        if (v.result == null && v.seats[seat].contributed > 0) throw IllegalStateException(
            "cash out is only offered between hands — this one is still live")
        val chips = v.seats[seat].stack
        handStacks[seat] = 0
        bustedAt[seat] = bustedAt.max() + 1
        // the button cannot stay on an empty seat — heads-up it posts the
        // small blind, so the next hand would post a blind for a player who
        // has left
        if (button == seat && activeSeats().isNotEmpty()) button = nextActive(seat)
        log.clear()
        invalidate()
        return chips
    }

    /** Is [seat] still in the tournament? */
    fun inPlay(seat: Int): Boolean = bustedAt[seat] == 0

    // ------------------------------------------------------------------ state
    fun toJson(): JsonObject = buildJsonObject {
        put("spec", spec.id)
        put("seed", tournamentSeed)
        put("handNo", handNo)
        put("button", button)
        putJsonArray("who") {
            for (o in occupants) add(buildJsonObject {
                put("id", o.id); put("name", o.name); put("human", o.human)
            })
        }
        putJsonArray("stacks") { for (s in handStacks) add(JsonPrimitive(s)) }
        putJsonArray("busted") { for (b in bustedAt) add(JsonPrimitive(b)) }
        putJsonArray("log") {
            for (a in log) add(buildJsonArray {
                add(JsonPrimitive(a.seat)); add(JsonPrimitive(a.kind.name)); add(JsonPrimitive(a.to))
            })
        }
    }

    companion object {
        /** A fresh tournament. [stacks] are the buy-ins, already paid. */
        fun start(spec: HoldemRules.Table, seed: Long, occupants: List<Seats.Occupant>,
            stacks: IntArray, button: Int = 0): HoldemTable {
            require(occupants.size in 2..HoldemRules.MAX_SEATS) {
                "a table seats 2..${HoldemRules.MAX_SEATS}, got ${occupants.size}"
            }
            require(stacks.size == occupants.size) { "one stack per seat" }
            require(stacks.all { it > 0 }) { "every seat buys in with chips" }
            return HoldemTable(spec, seed, occupants, stacks.copyOf(),
                IntArray(occupants.size), 0, button.mod(occupants.size), ArrayList())
        }

        /** Restore. A record that will not replay is refused LOUDLY and the
         *  caller starts fresh, rather than dealing a different hand under the
         *  old pot. */
        fun load(o: JsonObject): HoldemTable? {
            return try {
                val spec = HoldemRules.Table.byId(o["spec"]?.jsonPrimitive?.contentOrNull ?: "")
                    ?: return null
                val who = o["who"]?.jsonArray?.map {
                    val j = it.jsonObject
                    Seats.Occupant(
                        j["id"]?.jsonPrimitive?.contentOrNull ?: return null,
                        j["name"]?.jsonPrimitive?.contentOrNull ?: "?",
                        j["human"]?.jsonPrimitive?.booleanOrNull ?: false)
                } ?: return null
                if (who.size < 2 || who.size > HoldemRules.MAX_SEATS) return null
                val stacks = o["stacks"]?.jsonArray?.map { it.jsonPrimitive.intOrNull ?: 0 }
                    ?.toIntArray() ?: return null
                val busted = o["busted"]?.jsonArray?.map { it.jsonPrimitive.intOrNull ?: 0 }
                    ?.toIntArray() ?: return null
                if (stacks.size != who.size || busted.size != who.size) return null
                val log = ArrayList<Act>()
                o["log"]?.jsonArray?.forEach { e ->
                    val a = e.jsonArray
                    log.add(Act(
                        a[0].jsonPrimitive.intOrNull ?: return null,
                        ActionLevel.Kind.valueOf(a[1].jsonPrimitive.content),
                        a[2].jsonPrimitive.intOrNull ?: return null))
                }
                val t = HoldemTable(spec, o["seed"]?.jsonPrimitive?.longOrNull ?: 0L, who, stacks, busted,
                    (o["handNo"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0),
                    (o["button"]?.jsonPrimitive?.intOrNull ?: 0).mod(who.size), log)
                t.view()          // prove it replays before anyone can see it
                t
            } catch (e: Exception) {
                Log.e("holdem", "the stored table will not replay — it is dropped, not guessed at", e)
                null
            }
        }
    }
}
