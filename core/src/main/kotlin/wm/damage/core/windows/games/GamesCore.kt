package wm.damage.core.windows.games

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import wm.damage.core.app.ActionSpec
import wm.damage.core.app.ActionTable
import wm.damage.core.app.Item
import wm.damage.core.app.Kind
import wm.damage.core.app.Levels
import wm.damage.core.app.Migrate
import wm.damage.core.app.WindowCore
import wm.damage.core.app.WindowState
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ShellSettings
import wm.damage.core.windows.games.holdem.HoldemRules
import wm.damage.core.windows.games.holdem.HoldemTable
import wm.damage.core.windows.games.kit.ActionLevel
import wm.damage.core.windows.games.kit.Bankroll
import wm.damage.core.windows.games.kit.Money

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-5, stage G (the lean spec's §4.4): Games' core. Its world lives in the PC record as `rec.games.<item>`: the
 * tournament in play (`table`: the table's own record and his figures at it; removed between tournaments), the
 * `bankroll`, the `world` (its seed and counters) and one `char.<id>` per character, beside the eight rows. One engine
 * host writes them (the glasses' presenter, `GamesGlasses`, at the settle points of the spec's §3); no apply rule. No
 * pixels here.
 */
class GamesCore : WindowCore {
    override val window: String = WINDOW

    /** The description (C§3.1): Main's summary and the rows; the world is read by the presenter, not described. */
    override fun describe(state: WindowState): JsonObject = buildJsonObject {
        put("id", WINDOW)
        put("name", "Games")
        put("min", Levels.VIEW)
        put("availability", "live")
        put("summary", summaryJson(summary(state)))
        put("settings", settings(state))
    }

    override fun level(state: WindowState, addr: String): JsonObject? = null

    @Volatile private var cached: Triple<Item?, Item?, DamageWindow.Summary>? = null

    /** Main's line from the record — the function the glasses' Main row uses ([summaryOf]); one table replay per change
     *  of its two items. */
    fun summary(state: WindowState): DamageWindow.Summary {
        val b = state.itemNamed(BANKROLL)
        val t = state.itemNamed(TABLE)
        cached?.let { (cb, ct, s) -> if (cb === b && ct === t) return s }
        val bank = Bankroll().also { bk -> (live(b) as? JsonObject)?.let(bk::load) }
        val tour = (live(t) as? JsonObject)?.let(::tournamentOf)
        return summaryOf(bank, tour?.table, tour?.mySeat ?: 0).also { cached = Triple(b, t, it) }
    }

    /** Main's card (C§3.1; the page's home): the glasses' Main line; there is no page ("on the glasses"). */
    fun card(state: WindowState): JsonObject = buildJsonObject {
        put("window", WINDOW)
        put("name", "Games")
        put("icon", "games")
        put("min", Levels.VIEW)
        put("availability", "live")
        put("page", false)
        put("summary", summaryJson(summary(state)))
    }

    /** One stored row: [id] its record item, [label] the glasses' words, [scope] `glasses` or `shared`, [options] its
     *  values with their words in the glasses' order, [default] the value an absent item reads as. */
    data class Row(val id: String, val label: String, val scope: String, val options: List<Pair<JsonElement, String>>,
                   val default: JsonElement) {
        val type: String get() = if (options.all { (it.first as? JsonPrimitive)?.booleanOrNull != null }) "bool" else "choice"

        /** [v] when it is one of the options, else the default. */
        fun valueOf(v: JsonElement?): JsonElement = options.firstOrNull { it.first == v }?.first ?: default

        fun labelOf(v: JsonElement?): String = valueOf(v).let { x -> options.first { it.first == x }.second }
    }

    /** The table's record with his figures at it (what the window blob held beside the table before the conversion). */
    class Tournament(val table: HoldemTable, val mySeat: Int, val myStake: Int, val myCashedOut: Int, val myFee: Int,
                     val castStake: Map<Int, Int>, val bgOwed: Int, val cashOutPending: Boolean)

    companion object {
        const val WINDOW = "games"

        // ---- the world's items ----
        const val TABLE = "table"
        const val BANKROLL = "bankroll"
        const val WORLD = "world"
        const val CHAR = "char."

        // ---- the rows ----
        const val CONFIRM = "confirm"
        const val PACE = "paceMs"
        const val DEAL = "dealAnim"
        const val ARCHETYPES = "archetypes"
        const val NOTIFY_BUST = "notify.bust"
        const val NOTIFY_WIN = "notify.win"
        const val NOTIFY_RETURN = "notify.return"
        const val SIZE = "glasses.size"

        // ---- actions ----
        /** The engine's writes (register). */
        const val A_PLAY = "play"
        /** A removal: the table at a tournament's end, a character the roster let go. */
        const val A_DROP = "drop"
        /** A row. */
        const val A_SET = "set"
        const val MIGRATE = Migrate.ACTION

        fun levelOf(action: String): String? = when (action) {
            A_PLAY, A_DROP, A_SET, MIGRATE -> Levels.CONTROL
            else -> null
        }

        val ACTIONS = ActionTable { w, a ->
            if (w != WINDOW) null else when (a) {
                A_PLAY, A_SET -> ActionSpec(Kind.REGISTER)
                A_DROP -> ActionSpec(Kind.REMOVE)
                MIGRATE -> ActionSpec(Kind.POSITION)
                else -> null
            }
        }

        /** The bot pace choices (§14). */
        val PACES: Map<String, Long> = linkedMapOf("instant" to 0L, "300 ms" to 300L, "600 ms" to 600L, "1 s" to 1_000L, "1.5 s" to 1_500L)

        private fun onOff(onFirst: Boolean) = if (onFirst) listOf(JsonPrimitive(true) to "on", JsonPrimitive(false) to "off")
            else listOf(JsonPrimitive(false) to "off", JsonPrimitive(true) to "on")

        /** The eight rows, in the glasses' order; verdict 30: every Notify row off by default, in Games' own category. */
        val ROWS: List<Row> = listOf(
            Row(CONFIRM, "Confirm", "glasses", ActionLevel.Confirm.entries.map { JsonPrimitive(it.name) to it.label },
                JsonPrimitive(ActionLevel.Confirm.ALL.name)),
            Row(PACE, "Bot pace", "glasses", PACES.map { (l, ms) -> JsonPrimitive(ms) to l }, JsonPrimitive(600L)),
            Row(DEAL, "Deal animation", "glasses", onOff(true), JsonPrimitive(true)),
            Row(ARCHETYPES, "Archetype labels", "glasses", onOff(false), JsonPrimitive(false)),
            Row(NOTIFY_BUST, "Notify · bot busted", "shared", onOff(false), JsonPrimitive(false)),
            Row(NOTIFY_WIN, "Notify · tournament won", "shared", onOff(false), JsonPrimitive(false)),
            Row(NOTIFY_RETURN, "Notify · character returned", "shared", onOff(false), JsonPrimitive(false)),
            // 0 = the Global size (Music's form)
            Row(SIZE, "Size", "glasses", listOf(JsonPrimitive(0) to "global") + ShellSettings.HEIGHTS.map { JsonPrimitive(it) to "$it" },
                JsonPrimitive(0)),
        )

        val ROW: Map<String, Row> = ROWS.associateBy { it.id }

        /** The rows (C§6) as the record holds them: `{id, label, scope, type, options[{id, label}], value, default, level}`. */
        fun settings(state: WindowState): JsonArray = buildJsonArray {
            for (r in ROWS) add(buildJsonObject {
                put("id", r.id); put("label", r.label); put("scope", r.scope); put("type", r.type)
                put("options", buildJsonArray { for ((v, l) in r.options) add(buildJsonObject { put("id", v); put("label", l) }) })
                put("value", r.valueOf(live(state.itemNamed(r.id))))
                put("default", r.default)
                put("level", Levels.CONTROL)
            })
        }

        /** The one description of Games' Main line (the glasses' row and the page's card): the live table, else the
         *  bankroll's scoreboard. */
        fun summaryOf(bankroll: Bankroll, table: HoldemTable?, mySeat: Int): DamageWindow.Summary {
            if (table != null) {
                val v = table.view()
                val mine = v.seats.getOrNull(mySeat)?.stack ?: 0
                return DamageWindow.Summary("Hold'em · ${v.activeSeats.size} left · ${Money.fmt(mine)}",
                    detail = "${table.spec.label} · hand ${v.handNo + 1}", more = true)
            }
            return DamageWindow.Summary("${Money.fmt(bankroll.cash)} · W${bankroll.tournamentsWon} · L${bankroll.loserCount}",
                detail = if (bankroll.broke(HoldemRules.cheapestSeat())) "broke — refill in Bankroll" else "no tournament running")
        }

        fun summaryJson(s: DamageWindow.Summary): JsonObject = buildJsonObject {
            put("line", s.line); put("detail", s.detail); put("more", s.more)
        }

        /** An item's value, or null when absent or removed. */
        fun live(i: Item?): JsonElement? = i?.takeIf { !it.gone }?.value

        // ---- the table item ----

        fun tableJson(t: Tournament): JsonObject = buildJsonObject {
            put("table", t.table.toJson())
            put("mySeat", t.mySeat)
            put("myStake", t.myStake)
            put("myCashedOut", t.myCashedOut)
            put("myFee", t.myFee)
            putJsonObject("castStake") { for ((k, v) in t.castStake.toSortedMap()) put(k.toString(), v) }
            put("bgOwed", t.bgOwed)
            put("cashOutPending", t.cashOutPending)
        }

        /** The table item read back; null when its table will not replay. The figures are read as tolerantly as the
         *  window blob's were (one malformed entry drops that entry, not the table). */
        fun tournamentOf(o: JsonObject): Tournament? {
            val t = (o["table"] as? JsonObject)?.let(HoldemTable::load) ?: return null
            return Tournament(t, (int(o["mySeat"]) ?: 0).coerceIn(0, t.occupants.size - 1), int(o["myStake"]) ?: 0,
                (int(o["myCashedOut"]) ?: 0).coerceAtLeast(0), (int(o["myFee"]) ?: 0).coerceAtLeast(0),
                castStakeOf(o["castStake"]), (int(o["bgOwed"]) ?: 0).coerceIn(0, 8), bool(o["cashOutPending"]) ?: false)
        }

        fun castStakeOf(v: JsonElement?): Map<Int, Int> {
            val out = HashMap<Int, Int>()
            (v as? JsonObject)?.forEach { (k, x) -> k.toIntOrNull()?.let { out[it] = int(x) ?: 0 } }
            return out
        }

        fun int(v: JsonElement?): Int? = (v as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        fun long(v: JsonElement?): Long? = (v as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        fun bool(v: JsonElement?): Boolean? = (v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        fun text(v: JsonElement?): String? = (v as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}
