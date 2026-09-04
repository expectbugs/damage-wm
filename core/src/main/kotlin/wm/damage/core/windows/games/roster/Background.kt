package wm.damage.core.windows.games.roster

import wm.damage.core.util.Log
import wm.damage.core.windows.games.holdem.Equity
import wm.damage.core.windows.games.holdem.HoldemBot
import wm.damage.core.windows.games.holdem.HoldemRules
import wm.damage.core.windows.games.holdem.HoldemTable
import wm.damage.core.windows.games.kit.Rng
import wm.damage.core.windows.games.kit.Seats

/**
 * The background economy (`HOLDEM.md` §7.5, verdicts 26 and 27).
 *
 * 🔴 **It advances ONLY while Adam is playing.** Never wall-clock, never on a
 * schedule. Leaving the window genuinely freezes the world — which is what
 * makes verdict 30's "no notifications" correct rather than a shortcut.
 *
 * **2–3 background tournaments per tournament Adam plays**, over a roster of
 * ~35. Throughput comes from frequency, not concurrency: a bigger roster
 * dilutes the cast, and a high ratio switches off the free adaptive-difficulty
 * property — at fifteen times Adam's rate only a rounding error of a
 * character's lifetime action involves him, and the ecology sorts purely on
 * bot-vs-bot fitness instead of on who takes Adam's money.
 *
 * **Same engine, same rules** — a different function would make the difficulty
 * curve meaningless. Only the DECISION FIDELITY drops
 * ([Equity.CHEAP_ROLLOUTS]), and only for games nobody sees.
 */
object Background {

    /** How many games run per tournament of Adam's (verdict 26's 2–3 band). */
    fun owedFor(worldSeed: Long, gameNo: Int): Int =
        2 + Rng.int(2, worldSeed, 0xB6L, gameNo.toLong())

    /** Guard against a pathological table that will not resolve. Real 6-max
     *  sit-and-gos run 60–120 hands (§2), so this is far above any of them and
     *  exists only so a defect ends loudly instead of spinning. */
    const val MAX_HANDS = 4_000

    data class Summary(
        val spec: HoldemRules.Table,
        val seats: Int,
        val prize: Int,
        val fees: Int,
        val hands: Int,
        val winner: String,
        /** Character id → finishing place, 1 = winner. */
        val places: Map<String, Int>,
    )

    /**
     * Run one whole tournament between roster characters and settle it into
     * the world. Returns null when the room could not fill a table — which is
     * a real state (everyone between lives), not a failure.
     */
    fun playTournament(roster: Roster, rollouts: Int = Equity.CHEAP_ROLLOUTS,
        onAction: ((seat: Int, view: HoldemTable.View, decision: HoldemBot.Decision) -> Unit)? = null): Summary? {
        val rng = Rng.stream(roster.worldSeed, 0xBEE, roster.gameNo.toLong())
        val pool = roster.available()
        if (pool.size < 2) return null
        // the table is chosen by whoever is feeling brave: pick a leader, then
        // fill from everyone who can afford the same room (§7.4)
        val leader = pool[rng.nextInt(pool.size)]
        val spec = roster.pickTable(leader, rng)
        val seated = roster.seat(spec, HoldemRules.MAX_SEATS, key = 0xC0DE)
        if (seated.size < 2) {
            // give the stakes back — nobody sat down
            for (s in seated) s.who.bankroll += s.stake + s.fee
            return null
        }
        val occupants = seated.map { Seats.Occupant(it.who.id, it.who.name, human = false) }
        val table = HoldemTable.start(spec, Rng.hash(roster.worldSeed, roster.gameNo.toLong()),
            occupants, seated.map { it.stake }.toIntArray())
        val cast = seated.mapIndexed { i, s -> i to s.who }.toMap()
        val hands = playOut(table, cast, rollouts, onAction)
        val prize = seated.sumOf { it.stake }
        val fees = seated.sumOf { it.fee }
        return settle(roster, table, cast, spec, prize, fees, hands,
            seated.associate { it.who.id to it.stake + it.fee })
    }

    /**
     * Play a table to its end with bots in every seat that has a character.
     * Used by the background economy and by verdict 11's *"you can leave early
     * and cash out"* — a table Adam walks away from is **played out**, which is
     * what keeps the economy conserved and lands the winner's cashflow where it
     * belongs (verdict 23 depends on it).
     */
    fun playOut(table: HoldemTable, cast: Map<Int, Character>, rollouts: Int,
        onAction: ((seat: Int, view: HoldemTable.View, decision: HoldemBot.Decision) -> Unit)? = null): Int {
        var hands = 0
        while (hands < MAX_HANDS) {
            var guard = 0
            while (guard++ < 500) {
                val before = table.view()
                val seat = before.toAct ?: break
                val c = cast[seat]
                if (c == null) {
                    Log.e("games", "seat $seat has no character to act for it — the table stalls")
                    return hands
                }
                val d = HoldemBot.play(table, seat, c, rollouts)
                onAction?.invoke(seat, before, d)
            }
            if (!table.handComplete) {
                Log.e("games", "hand ${table.handNo} did not resolve — the table stops here")
                return hands
            }
            // mood follows results hand by hand (§7.2)
            val v = table.view()
            for ((seat, c) in cast) {
                if (!table.inPlay(seat)) continue
                val s = v.seats[seat]
                val delta = (v.result?.won?.get(seat) ?: 0) - s.contributed
                Mood.afterHand(c, delta, maxOf(1, s.stack + s.contributed))
            }
            hands++
            if (!table.nextHand()) break
        }
        return hands
    }

    /** Move the prize, update careers and moods, and let the roster deal with
     *  anyone who busted their bankroll (§7.3). */
    fun settle(roster: Roster, table: HoldemTable, cast: Map<Int, Character>,
        spec: HoldemRules.Table, prize: Int, fees: Int, hands: Int,
        stakes: Map<String, Int> = emptyMap()): Summary {
        val field = cast.size
        val places = HashMap<String, Int>(field)
        var winnerName = "-"
        for ((seat, c) in cast) {
            val place = table.finishPlace(seat) ?: 1
            places[c.id] = place
            // winner takes the whole prize pool: a sit-and-go with no rebuys
            // is conserved, and the fee was already taken at the door
            val won = if (place == 1) prize else 0
            c.bankroll += won
            c.career.tournaments++
            c.career.finishSum += place
            if (place == 1) {
                c.career.wins++
                winnerName = c.name
            }
            // the career's lifetime net is the NET, not the gross: what came
            // back minus what went in at the door
            Mood.afterTournament(c, place, field, won - stakes.getOrDefault(c.id, 0))
        }
        for (c in cast.values) roster.settleBroke(c)
        roster.gameNo++
        roster.tick()
        roster.ensurePopulation()
        return Summary(spec, field, prize, fees, hands, winnerName, places)
    }
}
