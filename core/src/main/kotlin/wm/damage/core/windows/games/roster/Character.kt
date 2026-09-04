package wm.damage.core.windows.games.roster

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wm.damage.core.windows.games.kit.Rng

/**
 * One character (`HOLDEM.md` §7.1, verdict 20: *"I don't need the opponents to
 * all be expert players, but I do want them to have depth and feel real."*).
 *
 * Two halves. **Traits are nine dials, fixed for life and NEVER DISPLAYED**
 * (§7.7 — an `Archetype labels` Settings row may show a coarse label, default
 * off, and that is all). **Circumstances are the situation they play from** and
 * do change: wealth, lives, mood, form, career.
 *
 * 🔑 `consistency` is the answer to "randomness that does not erode character".
 * If every character has the same jitter, jitter dilutes identity. When the
 * amplitude is itself a trait, an erratic player is *reliably* erratic and a
 * rock is *reliably* a rock — the noise expresses who they are instead of
 * smearing it.
 *
 * 🔴 **No tells** (verdict 37). Nothing here is a signal planted for the
 * player to find: a read is earned from a character's actual play over hands
 * you actually sat through (§7.7), or it is not there.
 */
class Character(
    val id: String,
    val name: String,
    val traits: Traits,
    /** $500–$10,000, skewed low: sets the starting stake and the refill size. */
    val generalWealth: Int,
    /** 1–12, skewed low, rolled INDEPENDENTLY of wealth (verdict 21) — which
     *  is what produces a cast: 1–2 lives are extras, 3–6 regulars, 8–12 the
     *  handful you build a history with. */
    val livesTotal: Int,
    var livesLeft: Int = livesTotal,
    var bankroll: Int = generalWealth,
    /** ONE mood value with two readers (§7.2): in-game it is tilt, between
     *  games it is ambition. −1 (running bad) … +1 (running hot). */
    var mood: Double = 0.0,
    /** A slower decayed streak that carries BETWEEN tournaments, so "Steve's
     *  been running hot lately" is real while traits never drift. */
    var form: Double = 0.0,
    var state: State = State.PLAYING,
    /** The game number they come back on, when between lives or retired. */
    var returnsAt: Int = 0,
    val career: Career = Career(),
) {

    enum class State { PLAYING, BETWEEN_LIVES, RETIRED }

    /** The nine dials. All 0..1 except [tiltSign], which is signed. */
    data class Traits(
        /** Baseline hand-selection threshold. */
        val tightness: Double,
        /** Bet/raise vs call/check when they are in a pot. */
        val aggression: Double,
        /** How often they represent what they do not have. */
        val bluffFreq: Double,
        /** Adam's *fatigue resistance* — how far pressure moves them off baseline. */
        val discipline: Double,
        /** Mood swing amplitude and decay rate. */
        val moodiness: Double,
        /**
         * SIGNED. Some people tilt tight-passive (+1) and some loose-aggressive
         * (−1); two very different opponents after a bad beat. This is the sign
         * of the tightness shift, so positive means "shuts down".
         */
        val tiltSign: Double,
        /** Short-stack response: correct push-fold (high) or folding toward
         *  the felt (low). */
        val stackCourage: Double,
        /** Whether they adapt to opponents at all. */
        val observance: Double,
        /** PER-DECISION noise amplitude — the trait that makes randomness
         *  express character instead of smearing it. */
        val consistency: Double,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("ti", tightness); put("ag", aggression); put("bl", bluffFreq)
            put("di", discipline); put("mo", moodiness); put("ts", tiltSign)
            put("sc", stackCourage); put("ob", observance); put("co", consistency)
        }

        /** A coarse, honest label for the `Archetype labels` Settings row
         *  (default OFF). It says nothing the character's play would not. */
        fun archetype(): String {
            val loose = tightness < 0.45
            val aggro = aggression > 0.55
            return when {
                loose && aggro -> "loose-aggressive"
                loose -> "calling station"
                aggro -> "tight-aggressive"
                else -> "rock"
            }
        }

        companion object {
            fun load(o: JsonObject?): Traits {
                fun d(k: String, dflt: Double) = o?.get(k)?.jsonPrimitive?.doubleOrNull ?: dflt
                return Traits(d("ti", 0.5), d("ag", 0.5), d("bl", 0.2), d("di", 0.5),
                    d("mo", 0.5), d("ts", 0.0), d("sc", 0.5), d("ob", 0.5), d("co", 0.5))
            }

            /**
             * Roll a sheet from a key. Every dial is a mix of a uniform draw
             * and a centre pull, so the population clusters around competent
             * with real extremes at the edges — six uniform dials would make
             * every character an average of nothing.
             */
            fun roll(seed: Long, key: Long): Traits {
                val r = Rng.stream(seed, key)
                fun dial(): Double = ((r.nextDouble() + r.nextDouble() + r.nextDouble()) / 3.0)
                    .let { 0.08 + it * 0.84 }
                return Traits(
                    tightness = dial(),
                    aggression = dial(),
                    bluffFreq = 0.03 + dial() * 0.42,
                    discipline = dial(),
                    moodiness = dial(),
                    // tilt DIRECTION is close to a coin flip with a magnitude
                    tiltSign = (r.nextDouble() * 2 - 1),
                    stackCourage = dial(),
                    observance = dial(),
                    consistency = dial(),
                )
            }
        }
    }

    /** What the standings and the head-to-head level show (§7.7). Everything
     *  here is OBSERVED — hands actually played — never the trait sheet. */
    class Career(
        var tournaments: Int = 0,
        var wins: Int = 0,
        var finishSum: Int = 0,
        var handsVsYou: Int = 0,
        var vpipVsYou: Int = 0,
        var aggressiveVsYou: Int = 0,
        var netVsYou: Int = 0,
        var knockedYouOut: Int = 0,
        var youKnockedOut: Int = 0,
        var lifetimeNet: Int = 0,
    ) {
        val avgFinish: Double get() = if (tournaments == 0) 0.0 else finishSum.toDouble() / tournaments

        /** VPIP measured over hands YOU sat through — the read you would be
         *  keeping in your head at a real table. */
        val vpip: Double get() = if (handsVsYou == 0) 0.0 else vpipVsYou.toDouble() / handsVsYou
        val aggression: Double get() = if (vpipVsYou == 0) 0.0 else aggressiveVsYou.toDouble() / vpipVsYou

        fun toJson(): JsonObject = buildJsonObject {
            put("t", tournaments); put("w", wins); put("fs", finishSum)
            put("h", handsVsYou); put("v", vpipVsYou); put("a", aggressiveVsYou)
            put("n", netVsYou); put("ko", knockedYouOut); put("kb", youKnockedOut)
            put("ln", lifetimeNet)
        }

        companion object {
            fun load(o: JsonObject?): Career {
                fun i(k: String) = o?.get(k)?.jsonPrimitive?.intOrNull ?: 0
                return Career(i("t"), i("w"), i("fs"), i("h"), i("v"), i("a"), i("n"),
                    i("ko"), i("kb"), i("ln"))
            }
        }
    }

    /** Net worth for the standings sort. */
    val worth: Int get() = bankroll

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("traits", traits.toJson())
        put("wealth", generalWealth)
        put("lives", livesTotal)
        put("left", livesLeft)
        put("bank", bankroll)
        put("mood", mood)
        put("form", form)
        put("state", state.name)
        put("returns", returnsAt)
        put("career", career.toJson())
    }

    companion object {
        fun load(o: JsonObject): Character? {
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return null
            val c = Character(
                id = id,
                name = name,
                traits = Traits.load(o["traits"] as? JsonObject),
                generalWealth = (o["wealth"]?.jsonPrimitive?.intOrNull ?: 1000).coerceIn(1, 1_000_000),
                livesTotal = (o["lives"]?.jsonPrimitive?.intOrNull ?: 3).coerceIn(1, 99),
                career = Career.load(o["career"] as? JsonObject),
            )
            c.livesLeft = (o["left"]?.jsonPrimitive?.intOrNull ?: c.livesTotal).coerceIn(0, c.livesTotal)
            c.bankroll = (o["bank"]?.jsonPrimitive?.intOrNull ?: c.generalWealth).coerceAtLeast(0)
            c.mood = (o["mood"]?.jsonPrimitive?.doubleOrNull ?: 0.0).coerceIn(-1.0, 1.0)
            c.form = (o["form"]?.jsonPrimitive?.doubleOrNull ?: 0.0).coerceIn(-1.0, 1.0)
            c.state = o["state"]?.jsonPrimitive?.contentOrNull
                ?.let { n -> State.entries.firstOrNull { it.name == n } } ?: State.PLAYING
            c.returnsAt = (o["returns"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
            return c
        }
    }
}
