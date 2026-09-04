package wm.damage.core.windows.games.kit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The SHARED cash pool (`HOLDEM.md` §5.4, verdict 12): winnings at Hold'em
 * fund a blackjack buy-in and back, so the bankroll lives at Games level and
 * not under any one game.
 *
 * Persisted as its own sub-record (`window.games.bankroll`) so two drivers
 * touching different Games records never clobber each other under
 * last-write-wins (§11.1).
 */
class Bankroll(
    var cash: Int = BASE,
    /** Verdict 14: refills increment it, *"to embarass me for being a loser haha."* */
    var loserCount: Int = 0,
    var tournamentsWon: Int = 0,
    /** Lifetime totals, for the Bankroll level's honest arithmetic. */
    var feesPaid: Int = 0,
    var tournamentsPlayed: Int = 0,
) {

    /**
     * Verdict 15: broke = **can't post the blind**, not literal zero. Kept as a
     * DERIVATION rather than a magic number so it stays correct if the
     * Unlimited blinds ever move: with no table running, the cheapest seat in
     * the game is Unlimited with a tiny stack — its big blind plus the fee's
     * $1 floor.
     */
    fun broke(cheapestSeat: Int): Boolean = cash < cheapestSeat

    /** Take [amount] out (a buy-in plus its fee). False when it does not fit —
     *  the caller says so loudly rather than going negative. */
    fun take(amount: Int): Boolean {
        if (amount < 0 || amount > cash) return false
        cash -= amount
        return true
    }

    fun add(amount: Int) {
        require(amount >= 0) { "add takes a positive amount; use take() to spend" }
        cash += amount
    }

    /** §5.3: the money-supply SINK. Fees are the only thing that removes money
     *  from the economy; refills and new characters are the only things that
     *  add it. */
    fun payFee(amount: Int) {
        feesPaid += amount
    }

    /** Verdict 14: back to $1,000 — Adam's General Wealth — and the Loser
     *  Count goes up. Reachable from the Bankroll level at any time, with the
     *  same cost, and offered when broke. */
    fun refill() {
        cash = BASE
        loserCount++
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("cash", cash)
        put("loser", loserCount)
        put("won", tournamentsWon)
        put("fees", feesPaid)
        put("played", tournamentsPlayed)
    }

    fun load(o: JsonObject) {
        cash = (o["cash"]?.jsonPrimitive?.intOrNull ?: BASE).coerceAtLeast(0)
        loserCount = (o["loser"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
        tournamentsWon = (o["won"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
        feesPaid = (o["fees"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
        tournamentsPlayed = (o["played"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
    }

    companion object {
        /** Verdict 13, and Adam's General Wealth as a roster character (verdict 25). */
        const val BASE = 1_000
    }
}
