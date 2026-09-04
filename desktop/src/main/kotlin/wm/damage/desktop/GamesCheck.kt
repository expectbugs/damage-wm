package wm.damage.desktop

import wm.damage.core.windows.games.holdem.Equity
import wm.damage.core.windows.games.holdem.HoldemRules
import wm.damage.core.windows.games.holdem.Street
import wm.damage.core.windows.games.kit.ActionLevel
import wm.damage.core.windows.games.kit.Money
import wm.damage.core.windows.games.roster.Background
import wm.damage.core.windows.games.roster.Character
import wm.damage.core.windows.games.roster.Roster

/**
 * `--games-check` (`HOLDEM.md` §13.4) — headless and deterministic, run
 * against a **SCRATCH WORLD**: it never opens Adam's saved roster, bankroll or
 * table.
 *
 * What it prints, and why each number is here:
 *
 *  - **per-character ROI, VPIP, aggression frequency, average finish** — does
 *    each character play the way their sheet says?
 *  - **roster differentiation** — does skill actually separate from variance in
 *    the number of sessions Adam will really play?
 *  - **outcome spread** — is there a spread, or is one archetype quietly
 *    eating everyone?
 *  - 🔴 **total money in the system** — §5.3 argues the visible fee out-scales
 *    the refill injections, so the supply should FLATTEN rather than compound.
 *    That is a claim, not a fact, and this is the number for it.
 *  - **tournament length per table** — what the blind ladder should be tuned
 *    against, rather than arithmetic. (§2 retracted an earlier ~400-hand
 *    estimate that priced tournaments on blind erosion alone; in no-limit one
 *    all-in ends a player at any level.)
 *
 * `--games-check deep` runs the 10,000-tournament supply measurement, which
 * takes minutes; the default run is the quick one.
 */
object GamesCheck {

    fun run(cfg: Config, deep: Boolean): Nothing {
        val games = if (deep) 10_000 else 400
        println("--games-check: a scratch world, $games tournaments, " +
            "${Equity.CHEAP_ROLLOUTS} rollouts a decision" + if (deep) "  (deep)" else "")
        println()

        val roster = Roster(worldSeed = 20260904)
        roster.ensurePopulation()
        val startSupply = roster.moneySupply()

        // per character, measured over hands actually played
        val hands = HashMap<String, Int>()
        val vpip = HashMap<String, Int>()
        val aggr = HashMap<String, Int>()
        val entered = HashMap<String, Int>()
        val spent = HashMap<String, Int>()
        val won = HashMap<String, Int>()
        val lenByTable = HashMap<HoldemRules.Table, ArrayList<Int>>()
        val supply = ArrayList<Int>()
        var fees = 0L
        var refills = 0L
        var refillMoney = 0L
        var empty = 0

        // a REFILL is the money supply's only source (§5.3); the roster's own
        // lives machinery does it, so it is counted by watching bankrolls
        // cross back up to generalWealth
        val lastState = HashMap<String, Character.State>()
        for (c in roster.characters) lastState[c.id] = c.state

        val t0 = System.currentTimeMillis()
        val firstSeen = HashMap<String, Int>()
        repeat(games) { g ->
            val before = roster.characters.associate { it.id to it.bankroll }
            val s = Background.playTournament(roster) { seat, view, d ->
                val c = view.seats[seat].who.id
                if (view.street == Street.PREFLOP && view.seats[seat].lastAction.isEmpty()) {
                    hands[c] = (hands[c] ?: 0) + 1
                    if (d.kind != ActionLevel.Kind.FOLD && d.kind != ActionLevel.Kind.CHECK)
                        vpip[c] = (vpip[c] ?: 0) + 1
                }
                if (d.kind == ActionLevel.Kind.BET || d.kind == ActionLevel.Kind.RAISE ||
                    d.kind == ActionLevel.Kind.ALL_IN) aggr[c] = (aggr[c] ?: 0) + 1
            }
            if (s == null) { empty++; return@repeat }
            fees += s.fees
            lenByTable.getOrPut(s.spec) { ArrayList() }.add(s.hands)
            for ((id, place) in s.places) {
                entered[id] = (entered[id] ?: 0) + 1
                firstSeen.putIfAbsent(id, g)
                val delta = (roster.get(id)?.bankroll ?: 0) - (before[id] ?: 0)
                if (delta > 0) won[id] = (won[id] ?: 0) + delta else spent[id] = (spent[id] ?: 0) - delta
                if (place == 1) Unit
            }
            // refills: a character whose state left BETWEEN_LIVES/RETIRED had
            // their bankroll set back to generalWealth by the roster
            for (c in roster.characters) {
                val was = lastState[c.id]
                if (was != null && was != Character.State.PLAYING && c.state == Character.State.PLAYING) {
                    refills++
                    refillMoney += c.generalWealth
                }
                lastState[c.id] = c.state
            }
            if (g % maxOf(1, games / 40) == 0) supply.add(roster.moneySupply())
        }
        val ms = System.currentTimeMillis() - t0

        // ---------------------------------------------------------------- report
        println("ran ${games - empty} tournaments in ${ms / 1000}s " +
            "(${ms / maxOf(1, games - empty)} ms each)" +
            if (empty > 0) "  · $empty could not fill a table" else "")
        println()

        println("TOURNAMENT LENGTH (hands) — what the blind ladder is tuned against")
        for (spec in HoldemRules.Table.entries) {
            val l = lenByTable[spec] ?: continue
            val sorted = l.sorted()
            println("  ${"%-10s".format(spec.label)} n=${"%-5d".format(l.size)} " +
                "min ${sorted.first()}  p25 ${pct(sorted, 0.25)}  median ${pct(sorted, 0.5)}  " +
                "p75 ${pct(sorted, 0.75)}  max ${sorted.last()}  mean ${"%.0f".format(l.average())}")
        }
        println()

        println("CHARACTERS — measured play against their sheets (top 12 by hands)")
        println("  ${"%-11s".format("name")} ${"%-5s".format("VPIP")} ${"%-5s".format("aggr")} " +
            "${"%-6s".format("avgFin")} ${"%-7s".format("ROI")} ${"%-8s".format("worth")} " +
            "${"%-6s".format("lives")} state        sheet")
        val ranked = roster.characters.sortedByDescending { hands[it.id] ?: 0 }
        for (c in ranked.take(12)) {
            val h = hands[c.id] ?: 0
            if (h == 0) continue
            val v = (vpip[c.id] ?: 0).toDouble() / h
            val a = (aggr[c.id] ?: 0).toDouble() / maxOf(1, vpip[c.id] ?: 1)
            val sp = spent[c.id] ?: 0
            val roi = if (sp == 0) 0.0 else ((won[c.id] ?: 0) - sp).toDouble() / sp
            println("  ${"%-11s".format(c.name)} ${"%-5s".format("%.0f%%".format(v * 100))} " +
                "${"%-5s".format("%.2f".format(a))} " +
                "${"%-6s".format("%.2f".format(c.career.avgFinish))} " +
                "${"%-7s".format("%+.0f%%".format(roi * 100))} " +
                "${"%-8s".format(Money.compact(c.worth))} " +
                "${"%-6s".format("${c.livesLeft}/${c.livesTotal}")} " +
                "${"%-12s".format(c.state.name.lowercase())} ${c.traits.archetype()}")
        }
        println()

        println("DIFFERENTIATION — does skill separate from variance?")
        val played = roster.characters.filter { (entered[it.id] ?: 0) >= 5 }
        if (played.size >= 4) {
            val fin = played.map { it.career.avgFinish }
            val mean = fin.average()
            val sd = kotlin.math.sqrt(fin.sumOf { (it - mean) * (it - mean) } / fin.size)
            println("  average finish over ${played.size} characters: mean ${"%.2f".format(mean)}, " +
                "sd ${"%.2f".format(sd)}  (sd near 0 = nobody is better than anybody)")
            val best = played.minByOrNull { it.career.avgFinish }!!
            val worst = played.maxByOrNull { it.career.avgFinish }!!
            println("  best  ${best.name}  ${"%.2f".format(best.career.avgFinish)}  ${best.traits.archetype()}")
            println("  worst ${worst.name}  ${"%.2f".format(worst.career.avgFinish)}  ${worst.traits.archetype()}")
            // outcome spread by archetype: is one style quietly eating everyone?
            val byArch = played.groupBy { it.traits.archetype() }
            for ((arch, cs) in byArch.entries.sortedBy { it.key }) {
                println("  ${"%-18s".format(arch)} n=${"%-3d".format(cs.size)} " +
                    "avg finish ${"%.2f".format(cs.map { it.career.avgFinish }.average())}  " +
                    "median worth ${Money.compact(cs.map { it.worth }.sorted()[cs.size / 2])}")
            }
        } else println("  too few characters played enough hands to say")
        println()

        println("WHO CAN AFFORD WHAT — can Adam's chosen table even fill?")
        for (spec in HoldemRules.Table.entries) {
            val n = roster.characters.count {
                it.state == Character.State.PLAYING && roster.canAfford(it, spec)
            }
            println("  ${"%-10s".format(spec.label)} $n of ${roster.characters.size} characters" +
                if (n < 5) "   <- cannot seat a full table" else "")
        }
        val worths = roster.characters.map { it.worth }.sorted()
        println("  worth: median ${Money.compact(worths[worths.size / 2])}  " +
            "p90 ${Money.compact(worths[(worths.size * 9) / 10])}  " +
            "max ${Money.compact(worths.last())}   (§7.6's wealth-concentration watch)")
        println()

        println("MONEY SUPPLY — §5.3's claim that the fee out-scales the refills")
        println("  start          ${Money.fmt(startSupply)}")
        println("  end            ${Money.fmt(roster.moneySupply())}")
        println("  fees taken     ${Money.fmt(fees.toInt())}   (the only sink)")
        println("  refills        $refills for ${Money.fmt(refillMoney.toInt())}   (a source)")
        println("  new characters ${roster.characters.size - Roster.TARGET} beyond the target " +
            "(each born with their own wealth — the other source)")
        val trend = if (supply.size >= 4) {
            val head = supply.take(supply.size / 4).average()
            val tail = supply.takeLast(supply.size / 4).average()
            "%.1f%%".format((tail - head) / head * 100)
        } else "n/a"
        println("  drift          $trend over the run" +
            "  (flat or negative is the design; compounding growth is the failure)")
        print("  curve          ")
        for (v in supply) print("${v / 1000}k ")
        println()
        println()

        val fails = ArrayList<String>()
        val lens = lenByTable.values.flatten()
        if (lens.isEmpty()) fails.add("no tournament finished")
        else {
            val med = lens.sorted()[lens.size / 2]
            if (med < 8) fails.add("median tournament is $med hands — the blinds are eating the table")
            if (med > 600) fails.add("median tournament is $med hands — it will never end on glass")
        }
        if (roster.characters.none { it.state == Character.State.PLAYING })
            fails.add("the whole room is between lives — the birth rate is too low")
        if (roster.moneySupply() <= 0) fails.add("the economy ran to zero")

        if (fails.isEmpty()) {
            println("games-check: ALL CHECKS PASS")
            kotlin.system.exitProcess(0)
        }
        println("games-check: ${fails.size} FAILURE(S):")
        for (f in fails) println("  - $f")
        kotlin.system.exitProcess(1)
    }

    private fun pct(sorted: List<Int>, p: Double): Int =
        sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)]
}
