package wm.damage.core.windows.games.holdem

import wm.damage.core.windows.games.kit.ActionLevel
import wm.damage.core.windows.games.kit.Rng
import wm.damage.core.windows.games.roster.Character

/**
 * The bot decision model (`HOLDEM.md` §8, verdict 4: **decent-casual**).
 *
 * ```
 * equity  = MonteCarlo(hole, board, opponentCount, rollouts)
 * potOdds = toCall / (pot + toCall)
 * dials   = modulate(traits, state)
 * action  = policy(equity, potOdds, dials, legalActions)
 * ```
 *
 * [modulate] is deliberately NOT a black box, so *"why did Steve do that"*
 * always has an answer.
 *
 * 🔑 **Scared money is a feature.** Big stacks loosening is realistic *and*
 * theoretically correct. Short stacks tightening is realistic and
 * **theoretically wrong** — push-fold theory says a short stack must widen or
 * blind out. Modelling the mistake is the point: the specific, recognisable way
 * people play badly under pressure is what makes an opponent feel like a
 * person. One dial does double duty — a character's discipline under pressure
 * is simultaneously their personality and their skill.
 *
 * 🔴 **No tells** (verdict 37). Nothing here consults the strength of the hand
 * to choose a SIZE or a delay that would leak it. Sizing comes from the pot and
 * the dials; the read a player earns comes from watching the same dials produce
 * the same tendencies over many hands (§7.7).
 *
 * 🔴 **Determinism.** Every random draw is keyed by
 * (tournamentSeed, handNo, seat, decisionNo), so a resumed bot is the same bot
 * (§11.2). Nothing here holds mutable state between calls.
 */
object HoldemBot {

    /** What [modulate] produces: the character as this spot has bent them. */
    data class Dials(
        val tightness: Double,
        val aggression: Double,
        val bluff: Double,
        val courage: Double,
        /** This decision's jitter, already sampled at the character's own
         *  amplitude — an erratic player is reliably erratic. */
        val noise: Double,
    )

    /** The spot, as far as a decision needs to know it. */
    data class Spot(
        val bbDepth: Double,
        val stackRatio: Double,
        val playersLeft: Int,
        val mood: Double,
        val form: Double,
        /** 0 = first to act, 1 = last. */
        val position: Double,
        /**
         * 🔑 §7.6: the OBSERVED loose-ness of the opponent this character has
         * a real read on — their VPIP over hands actually played together, or
         * 0.5 when there is no read yet. This is how `observance` reaches Adam
         * for free: his stats are tracked like everyone else's, so a character
         * who has sat through 300 hands with him starts folding to his bluffs
         * through machinery that already exists. No player-modelling special
         * case, and 🔴 no planted tell (verdict 37) — the read is EARNED.
         */
        val read: Double = NO_READ,
    )

    /** "I have no read on you" — the neutral value, which shifts nothing. */
    const val NO_READ = 0.5

    /** How hard the blinds are pressing: 1 at 5bb, 0 at 20bb and above. */
    fun pressure(bbDepth: Double): Double = ((20.0 - bbDepth) / 15.0).coerceIn(0.0, 1.0)

    /** How much room a big stack has to push people around. */
    fun bravado(stackRatio: Double): Double = (stackRatio - 1.0).coerceIn(0.0, 1.0)

    /**
     * The §8 formula, written out:
     *
     * ```
     * effTightness = base
     *   + (1 − discipline) · pressure(bbDepth) · scaredMoney
     *   − bravado(stackRatio) · headroom
     *   + tiltSign · moodiness · moodBadness
     *   + noise(consistency)
     * ```
     */
    fun modulate(t: Character.Traits, s: Spot, rng: Rng.Stream): Dials {
        val moodBadness = (-s.mood).coerceIn(0.0, 1.0)
        val noise = rng.nextNoise() * (1.0 - t.consistency) * NOISE_SPAN
        // a LOOSER opponent means a weaker range, so the read widens this
        // character rather than narrowing them — scaled by how much they
        // notice at all
        val readShift = t.observance * (s.read - NO_READ) * OBSERVE
        val tight = (t.tightness
            + (1.0 - t.discipline) * pressure(s.bbDepth) * SCARED_MONEY
            - bravado(s.stackRatio) * HEADROOM
            + t.tiltSign * t.moodiness * moodBadness * TILT
            - s.position * POSITION
            - readShift
            + noise).coerceIn(0.02, 0.98)
        val aggr = (t.aggression
            // the same tilt, read the other way: a loose-aggressive tilt
            // (negative sign) makes them push harder, not merely wider
            - t.tiltSign * t.moodiness * moodBadness * TILT * 0.6
            + bravado(s.stackRatio) * HEADROOM * 0.5
            + s.form * FORM
            + noise * 0.5).coerceIn(0.02, 0.98)
        // and they bluff a loose caller LESS: the same read, the other way up
        val bluff = (t.bluffFreq * (0.6 + 0.8 * s.position) *
            (1.0 - t.observance * (s.read - NO_READ) * OBSERVE_BLUFF)).coerceIn(0.0, 0.6)
        return Dials(tight, aggr, bluff, t.stackCourage, noise)
    }

    /** What the bot decided, and the amount when one applies. */
    data class Decision(val kind: ActionLevel.Kind, val to: Int = 0)

    /**
     * Choose. [legal] is the engine's own list, so a decision can never be
     * illegal: the policy expresses a preference and then takes the best
     * available match.
     */
    fun policy(equity: Double, pot: Int, toCall: Int, committed: Int, stack: Int,
        minRaiseTo: Int, maxRaiseTo: Int, canRaise: Boolean, d: Dials,
        bbDepth: Double, rng: Rng.Stream): Decision {
        val eq = (equity + d.noise * READ_NOISE).coerceIn(0.0, 1.0)
        val potOdds = if (toCall > 0) toCall.toDouble() / (pot + toCall) else 0.0

        // --- short stack: push or fold, and HOW they get it wrong is the
        // character. A courageous short stack shoves correctly; a timid one
        // folds toward the felt (§8's scared money).
        if (bbDepth <= PUSH_FOLD_BB && canRaise) {
            val shoveAt = 0.62 - 0.30 * d.courage
            if (eq >= shoveAt) return Decision(ActionLevel.Kind.ALL_IN, maxRaiseTo)
        }

        // the equity a raise wants, and the equity a call wants
        val raiseAt = (0.66 - 0.20 * d.aggression + 0.14 * d.tightness).coerceIn(0.35, 0.92)
        val callAt = potOdds * (0.85 + 0.95 * d.tightness)

        if (toCall == 0) {
            if (eq >= raiseAt && canRaise) return Decision(ActionLevel.Kind.BET, sizeFor(d, pot, toCall, committed, minRaiseTo, maxRaiseTo, rng))
            // a bluff is a FREQUENCY, not a read on the board: it fires on the
            // character's own dial, which is what keeps it un-exploitable and
            // un-tellable (verdict 37)
            if (canRaise && rng.nextDouble() < d.bluff * (1.0 - eq))
                return Decision(ActionLevel.Kind.BET, sizeFor(d, pot, toCall, committed, minRaiseTo, maxRaiseTo, rng))
            return Decision(ActionLevel.Kind.CHECK)
        }

        if (eq >= raiseAt && canRaise)
            return Decision(ActionLevel.Kind.RAISE, sizeFor(d, pot, toCall, committed, minRaiseTo, maxRaiseTo, rng))
        if (eq >= callAt) {
            return if (toCall >= stack) Decision(ActionLevel.Kind.ALL_IN, committed + stack)
            else Decision(ActionLevel.Kind.CALL)
        }
        if (canRaise && rng.nextDouble() < d.bluff * BLUFF_RAISE * (1.0 - eq))
            return Decision(ActionLevel.Kind.RAISE, sizeFor(d, pot, toCall, committed, minRaiseTo, maxRaiseTo, rng))
        return Decision(ActionLevel.Kind.FOLD)
    }

    /**
     * Sizing comes from the POT and the dials — never from the hand. A size
     * that tracked strength is a tell, and verdict 37 rules those out.
     */
    private fun sizeFor(d: Dials, pot: Int, toCall: Int, committed: Int,
        minRaiseTo: Int, maxRaiseTo: Int, rng: Rng.Stream): Int {
        val potAfterCall = pot + toCall
        val frac = 0.35 + 0.55 * d.aggression + d.noise * 0.15
        val want = committed + toCall + (potAfterCall * frac).toInt()
        // a raise that would leave under a third of the stack behind just goes
        // in — the standard commitment rule, and it stops a bot from betting
        // itself into an unfoldable dribble
        val left = maxRaiseTo - want
        if (left <= (maxRaiseTo - committed) / 3) return maxRaiseTo
        return want.coerceIn(minRaiseTo, maxRaiseTo)
    }

    /**
     * The whole decision for [seat] at [table], using [c]'s sheet. [rollouts]
     * is the fidelity: [Equity.LIVE_ROLLOUTS] at Adam's table,
     * [Equity.CHEAP_ROLLOUTS] for the background economy (§7.5).
     */
    fun decide(table: HoldemTable, seat: Int, c: Character,
        rollouts: Int = Equity.LIVE_ROLLOUTS, read: Double = NO_READ): Decision {
        val v = table.view()
        require(v.toAct == seat) { "seat $seat is not to act (it is ${v.toAct})" }
        val s = v.seats[seat]
        val rng = Rng.stream(table.tournamentSeed, v.handNo.toLong(), seat.toLong(),
            table.actionCount.toLong())
        val opponents = v.liveSeats.count { it.index != seat }.coerceAtLeast(1)
        val equity = Equity.estimate(s.cards, v.board, opponents, rollouts, rng)

        val stacks = v.liveSeats.map { it.stack + it.contributed }
        val avg = if (stacks.isEmpty()) 1.0 else stacks.average().coerceAtLeast(1.0)
        val spot = Spot(
            bbDepth = s.stack.toDouble() / v.bb.coerceAtLeast(1),
            stackRatio = (s.stack + s.contributed) / avg,
            playersLeft = v.activeSeats.size,
            mood = c.mood,
            form = c.form,
            position = positionOf(v, seat),
            read = read.coerceIn(0.0, 1.0),
        )
        val dials = modulate(c.traits, spot, rng)
        val toCall = maxOf(0, v.currentBet - s.committed)
        val decision = policy(equity, v.pot, toCall, s.committed, s.stack,
            table.minRaiseTo(v, seat), table.maxRaiseTo(v, seat), table.canRaise(v, seat),
            dials, spot.bbDepth, rng)
        return conform(decision, table)
    }

    /** 0 = first to act on this street, 1 = last. */
    fun positionOf(v: HoldemTable.View, seat: Int): Double {
        val order = ArrayList<Int>()
        val n = v.seats.size
        val from = if (v.street == Street.PREFLOP) v.bbSeat else v.button
        for (k in 1..n) {
            val i = (from + k) % n
            val st = v.seats[i]
            if (!st.busted && !st.folded) order.add(i)
        }
        val idx = order.indexOf(seat)
        return if (order.size <= 1 || idx < 0) 1.0 else idx.toDouble() / (order.size - 1)
    }

    /** A preference becomes a LEGAL action. The engine refuses anything else
     *  loudly, so the bot must never hand it a near-miss. */
    private fun conform(d: Decision, table: HoldemTable): Decision {
        val legal = table.legalActions()
        if (legal.isEmpty()) return d
        val kinds = legal.map { it.kind }.toSet()
        return when (d.kind) {
            ActionLevel.Kind.CHECK ->
                if (ActionLevel.Kind.CHECK in kinds) d else Decision(ActionLevel.Kind.FOLD)
            ActionLevel.Kind.FOLD ->
                // never fold when checking is free — that is not a style, it is
                // a bug wearing one
                if (ActionLevel.Kind.CHECK in kinds) Decision(ActionLevel.Kind.CHECK) else d
            ActionLevel.Kind.CALL ->
                if (ActionLevel.Kind.CALL in kinds || ActionLevel.Kind.ALL_IN in kinds) d
                else Decision(ActionLevel.Kind.CHECK)
            ActionLevel.Kind.BET, ActionLevel.Kind.RAISE, ActionLevel.Kind.ALL_IN -> {
                if (!table.canRaise()) {
                    return if (ActionLevel.Kind.CALL in kinds || ActionLevel.Kind.ALL_IN in kinds)
                        Decision(ActionLevel.Kind.CALL) else Decision(ActionLevel.Kind.CHECK)
                }
                val min = table.minRaiseTo()
                val max = table.maxRaiseTo()
                val to = d.to.coerceIn(min, max)
                Decision(if (to >= max) ActionLevel.Kind.ALL_IN else d.kind, to)
            }
            ActionLevel.Kind.NAV -> Decision(ActionLevel.Kind.FOLD)
        }
    }

    /** Apply a decision to the table. */
    fun play(table: HoldemTable, seat: Int, c: Character, rollouts: Int = Equity.LIVE_ROLLOUTS,
        read: Double = NO_READ): Decision {
        val d = decide(table, seat, c, rollouts, read)
        when (d.kind) {
            ActionLevel.Kind.BET, ActionLevel.Kind.RAISE, ActionLevel.Kind.ALL_IN ->
                table.act(d.kind, d.to)
            else -> table.act(d.kind)
        }
        return d
    }

    // --- the constants the formula is written in terms of. Each is a lever
    // `--games-check` tunes against measured distributions (§13.4), not a
    // number anyone should change on a hunch.
    private const val SCARED_MONEY = 0.30
    private const val HEADROOM = 0.22
    private const val TILT = 0.35
    private const val POSITION = 0.10
    private const val FORM = 0.08
    private const val NOISE_SPAN = 0.22
    private const val READ_NOISE = 0.10
    private const val BLUFF_RAISE = 0.5
    private const val PUSH_FOLD_BB = 10.0
    private const val OBSERVE = 0.35
    private const val OBSERVE_BLUFF = 1.4
}
