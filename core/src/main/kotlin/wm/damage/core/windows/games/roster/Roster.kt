package wm.damage.core.windows.games.roster

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import wm.damage.core.util.Log
import wm.damage.core.windows.games.holdem.HoldemRules
import wm.damage.core.windows.games.kit.Rng

/**
 * The ecology (`HOLDEM.md` §7, verdicts 20–23). Characters, wealth, lives,
 * mood, table selection and the standings — **behind one call**: *"give me five
 * opponents for this table."*
 *
 * 🔑 That seam is what keeps a large subsystem contained. The table asks the
 * roster for opponents; everything about wealth, mood, lives and retirement
 * lives behind it, and the poker engine never learns it exists.
 *
 * 🔴 **The world advances only while Adam is playing** (verdict 27) — never
 * wall-clock, never on a schedule. That is what makes "nothing changes while I
 * am away" true, and it is what justifies having no notifications (verdict 30).
 */
class Roster(
    var worldSeed: Long = 0L,
    /** Every tournament that has ever been played in this world, Adam's and
     *  the background's alike. The clock the ecology runs on. */
    var gameNo: Int = 0,
    /** How many characters have ever been born — the id counter. */
    var born: Int = 0,
) {

    private val byId = LinkedHashMap<String, Character>()

    val characters: Collection<Character> get() = byId.values

    fun get(id: String): Character? = byId[id]

    fun put(c: Character) {
        byId[c.id] = c
    }

    fun remove(id: String) {
        byId.remove(id)
    }

    /** Characters who could sit down right now. */
    fun available(): List<Character> = byId.values.filter { it.state == Character.State.PLAYING }

    /**
     * Fill the room to [target] (§7.5's roster of ~35). New characters are
     * born from the world seed so a rebuilt world is the same world.
     */
    fun ensurePopulation(target: Int = TARGET) {
        // the room is measured by who can SIT DOWN, not by how many ids exist:
        // characters between lives or in retirement are out of it, and §7.6
        // makes the birth rate the parameter that replaces them ("broke
        // characters leave, fresh entrants replace them at the bottom"). The
        // hard cap stops a wave of returns from diluting the cast (§7.5).
        while (available().size < target && byId.size < target * 3 / 2) {
            val c = birth(worldSeed, born++)
            byId[c.id] = c
        }
    }

    /**
     * 🔑 The seam: opponents for a table. [count] characters who can afford
     * [buyIn] plus its fee, chosen by CONFIDENCE rather than by wealth
     * (verdict 22 — *"a bad poor player can make the dumb decision to sit at
     * the Unlimited Table, just make it much less likely"*), and each of them
     * commits the stake they actually bring.
     *
     * Returns the seated characters paired with their buy-in. Money leaves
     * their bankroll here: entry plus fee (verdict 24 applies to everyone).
     */
    fun seat(spec: HoldemRules.Table, count: Int, key: Long): List<Seated> {
        val rng = Rng.stream(worldSeed, gameNo.toLong(), key)
        // the sort key is drawn ONCE per character. Calling ambition() inside
        // the comparator draws a fresh number on every comparison, which is an
        // inconsistent comparator — undefined order at best, and TimSort
        // refuses some inputs outright with "comparison method violates its
        // general contract".
        val keyed = available().filter { canAfford(it, spec) }
            .map { it to ambition(it, rng) + tierFit(it, spec) }
        val pool = keyed.sortedByDescending { it.second }.map { it.first }
        val out = ArrayList<Seated>(count)
        for (c in pool) {
            if (out.size == count) break
            val stake = stakeFor(c, spec, rng)
            val fee = HoldemRules.fee(stake)
            if (stake <= 0 || c.bankroll < stake + fee) continue
            c.bankroll -= stake + fee
            out.add(Seated(c, stake, fee))
        }
        return out
    }

    data class Seated(val who: Character, val stake: Int, val fee: Int)

    /**
     * The affordability gate (§7.4). A fixed table wants its entry AND its
     * fee; **Unlimited wants only the cheapest seat in the game** — that is
     * the whole point of the table, and an earlier version asked for
     * `bankroll >= bankroll + fee`, which is never true and quietly closed
     * Unlimited to every character in the world.
     */
    fun canAfford(c: Character, spec: HoldemRules.Table): Boolean {
        val entry = spec.entry ?: return c.bankroll >= HoldemRules.cheapestSeat()
        return c.bankroll >= entry + HoldemRules.fee(entry)
    }

    /**
     * §5.2: at Unlimited a character buys in according to wealth and nerve, in
     * **$1,000 increments where they can afford at least $1,000**, and
     * otherwise with whatever they bring. *"A broke character on a heater
     * sitting down with $600 against $8,000 stacks is intended behaviour, not
     * a defect."*
     */
    fun stakeFor(c: Character, spec: HoldemRules.Table, rng: Rng.Stream): Int {
        spec.entry?.let { return minOf(it, c.bankroll) }
        val affordable = maxStake(c.bankroll)
        if (affordable <= 0) return 0
        if (affordable < 1_000) return affordable
        // verdict 16: **$1,000–$10,000 in $1,000 steps** — an ABSOLUTE range,
        // not a fraction of the roll. Reading it as a fraction is what makes
        // the whole economy compound: a rich character who risks half their
        // bankroll every game busts often, and every bust injects a whole
        // General Wealth. `--games-check` measured +236 % money supply over
        // 400 tournaments before this was corrected.
        val nerve = (0.25 + 0.75 * ambition(c, rng)).coerceIn(0.05, 1.0)
        val steps = (affordable / 1_000).coerceAtMost(MAX_UNLIMITED_STEPS)
        val want = 1 + (steps * nerve * rng.nextDouble()).toInt()
        return (want.coerceIn(1, steps)) * 1_000
    }

    /** The largest stake whose 5 % fee still fits inside [bank]. */
    fun maxStake(bank: Int): Int {
        var s = (bank.toLong() * 100 / 105).toInt()
        while (s > 0 && s + HoldemRules.fee(s) > bank) s--
        return s
    }

    /**
     * §7.4: `affordability gate → ambition = baseConfidence(traits) × mood ×
     * form → tier, with noise`. Only PARTIALLY tied to wealth, deliberately —
     * which is also what keeps all three tables populated instead of sorting
     * sterilely by bankroll.
     */
    fun ambition(c: Character, rng: Rng.Stream): Double {
        val t = c.traits
        val base = 0.35 * t.aggression + 0.25 * (1 - t.tightness) +
            0.25 * t.stackCourage + 0.15 * (1 - t.discipline)
        val swing = (1.0 + 0.40 * c.mood) * (1.0 + 0.30 * c.form)
        return (base * swing + rng.nextNoise() * AMBITION_NOISE).coerceIn(0.0, 1.5)
    }

    /** How well a character's means match a tier — the WEAK half of the pull,
     *  so a rich cautious grinder still shows up at Regular. */
    private fun tierFit(c: Character, spec: HoldemRules.Table): Double = when (spec) {
        HoldemRules.Table.REGULAR -> if (c.bankroll < 2_000) 0.25 else 0.0
        HoldemRules.Table.BIG_BOY -> if (c.bankroll in 2_000..12_000) 0.25 else 0.0
        HoldemRules.Table.UNLIMITED -> if (c.bankroll > 6_000) 0.30 else 0.0
    }

    /** Which table a character would CHOOSE, for the background economy. */
    fun pickTable(c: Character, rng: Rng.Stream): HoldemRules.Table {
        val a = ambition(c, rng)
        val choices = HoldemRules.Table.entries.filter { canAfford(c, it) }
        if (choices.isEmpty()) return HoldemRules.Table.REGULAR
        val want = when {
            a > 0.72 -> HoldemRules.Table.UNLIMITED
            a > 0.45 -> HoldemRules.Table.BIG_BOY
            else -> HoldemRules.Table.REGULAR
        }
        return if (want in choices) want else choices.last()
    }

    // ------------------------------------------------------------------ lives
    /**
     * §7.3. Bust the bankroll → spend a life → a short downtime → refill to
     * `generalWealth` → back in. Out of lives → **retired**, with a
     * WEALTH-BASED recovery: `generalWealth` regenerates a stake (they went
     * back to work) and they return when they can afford one, so richer
     * characters return sooner. A flat game counter was proposed and replaced
     * by this — it is characterful, self-tuning, and uses a trait that already
     * exists.
     */
    fun settleBroke(c: Character) {
        if (c.state != Character.State.PLAYING) return
        if (!brokeFor(c)) return
        if (c.livesLeft > 1) {
            c.livesLeft--
            c.state = Character.State.BETWEEN_LIVES
            c.returnsAt = gameNo + Mood.downtime(c)
        } else {
            c.livesLeft = 0
            c.state = Character.State.RETIRED
            // richer characters have more outside income, so they come back
            // sooner — the wealth-based recovery
            val wait = (RETIRE_BASE * 10_000.0 / c.generalWealth.coerceAtLeast(500)).toInt()
            c.returnsAt = gameNo + wait.coerceIn(RETIRE_MIN, RETIRE_MAX)
            Log.i("games", "${c.name} is out of lives — back around game ${c.returnsAt}")
        }
    }

    /** Bring back everyone whose break is over. Called once per game. */
    fun tick() {
        for (c in byId.values) {
            if (c.state == Character.State.PLAYING || gameNo < c.returnsAt) continue
            val break_ = (gameNo - (c.returnsAt - 1)).coerceAtLeast(1)
            Mood.coolOff(c, break_)
            c.bankroll = c.generalWealth
            if (c.state == Character.State.RETIRED) {
                // a returning character comes back with a FRESH life
                // allocation and their career record intact (§7.3)
                c.livesLeft = c.livesTotal
                c.form = 0.0
            }
            c.state = Character.State.PLAYING
            c.returnsAt = 0
        }
    }

    /**
     * A character has **bust the bankroll** (§7.3) when they can no longer buy
     * into the cheapest REAL room. Sitting at Unlimited with $60 against
     * $10,000 stacks is not a bankroll, it is the last hand before the ATM.
     *
     * ⚠ This is deliberately NOT verdict 15's [HoldemRules.cheapestSeat],
     * which is Adam's threshold: he chooses to sit short and that is the point
     * of the table. `--games-check` measured what the literal reading costs —
     * with the $11 floor, 400 background tournaments left only 8 of 35
     * characters able to afford Regular, so Adam's own table could barely
     * fill, and the poor never cycled back up.
     */
    fun brokeFor(c: Character): Boolean {
        val floor = HoldemRules.Table.REGULAR.entry ?: return false
        return c.bankroll < floor + HoldemRules.fee(floor)
    }

    /** The standings (§4): everyone by net worth, with the state mark. */
    fun standings(): List<Character> = byId.values.sortedWith(
        compareByDescending<Character> { it.worth }.thenBy { it.name })

    /** Total money held by the roster — the §7.6 inflation watch. */
    fun moneySupply(): Int = byId.values.sumOf { it.bankroll }

    // ------------------------------------------------------------------ state
    fun toJson(): JsonObject = buildJsonObject {
        put("seed", worldSeed)
        put("gameNo", gameNo)
        put("born", born)
    }

    fun loadWorld(o: JsonObject) {
        worldSeed = o["seed"]?.jsonPrimitive?.longOrNull ?: worldSeed
        gameNo = (o["gameNo"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
        born = (o["born"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
    }

    companion object {
        /** §7.5: *"a bigger roster dilutes the cast"*. */
        const val TARGET = 35

        /** Verdict 16's ceiling: bots bring at most $10,000 to Unlimited,
         *  however rich they get. */
        const val MAX_UNLIMITED_STEPS = 10
        private const val AMBITION_NOISE = 0.22
        private const val RETIRE_BASE = 40
        private const val RETIRE_MIN = 8
        private const val RETIRE_MAX = 200

        /**
         * A new character. Both distributions are **power-law-ish, not
         * uniform** (§7.1): uniform $500–$10,000 would put the average
         * character at $5,250 and leave Regular populated only by the
         * low-confidence, not the poor — the pyramid shape is what keeps the
         * bottom table busy. Same reasoning for lives, and it produces a cast
         * structure: 1–2 lives are extras who bust and vanish, 3–6 are
         * regulars, 8–12 are institutions.
         */
        fun birth(worldSeed: Long, n: Int): Character {
            val r = Rng.stream(worldSeed, 0x60D, n.toLong())
            val u = r.nextDouble()
            val wealth = (500 + (9_500 * u * u * u).toInt()) / 100 * 100
            val lv = r.nextDouble()
            val lives = (1 + (12 * Math.pow(lv, 2.2)).toInt()).coerceIn(1, 12)
            return Character(
                id = "c$n",
                name = nameFor(worldSeed, n),
                traits = Character.Traits.roll(worldSeed, 0xF00D + n.toLong()),
                generalWealth = wealth,
                livesTotal = lives,
            )
        }

        /** Plain given names, ours to use. A surname initial keeps two Steves
         *  apart without inventing people. */
        private val NAMES = listOf(
            "Steve", "Marla", "Dex", "Ruth", "Ivo", "Nell", "Cass", "Bo", "Wren", "Otis",
            "June", "Hal", "Pia", "Rex", "Ada", "Gus", "Nita", "Sol", "Vera", "Cliff",
            "Iris", "Mack", "Fay", "Roy", "Lena", "Ozzy", "Tess", "Vic", "Nan", "Emmet",
            "Bea", "Dane", "Lou", "Ines", "Curt", "Ora", "Sid", "Maud", "Guy", "Etta",
            "Pete", "Zoe", "Ward", "Lila", "Hank", "Cleo", "Ross", "Nadia", "Jory", "Elke",
        )
        private const val INITIALS = "ABCDEFGHJKLMNPRSTVW"
        private const val NAME_KEY = 0x4E414D45L

        fun nameFor(worldSeed: Long, n: Int): String {
            val r = Rng.stream(worldSeed, NAME_KEY, n.toLong())
            val first = NAMES[r.nextInt(NAMES.size)]
            val i = INITIALS[r.nextInt(INITIALS.length)]
            return "$first $i."
        }
    }
}
