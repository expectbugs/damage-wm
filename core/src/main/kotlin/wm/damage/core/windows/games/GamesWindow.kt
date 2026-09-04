package wm.damage.core.windows.games

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.gfx.IconNames
import wm.damage.core.gfx.IconPaint
import wm.damage.core.gfx.Level
import wm.damage.core.shell.ActivationSource
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.DocModel
import wm.damage.core.shell.Draw
import wm.damage.core.shell.HostSetting
import wm.damage.core.shell.KeyboardSurface
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.MenuSurface
import wm.damage.core.shell.ShellServices
import wm.damage.core.shell.ShellSettings
import wm.damage.core.shell.WindowView
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.util.Log
import wm.damage.core.windows.games.holdem.Equity
import wm.damage.core.windows.games.holdem.HoldemBot
import wm.damage.core.windows.games.holdem.HoldemRules
import wm.damage.core.windows.games.holdem.HoldemTable
import wm.damage.core.windows.games.holdem.HoldemView
import wm.damage.core.windows.games.holdem.Street
import wm.damage.core.windows.games.kit.ActionLevel
import wm.damage.core.windows.games.kit.Bankroll
import wm.damage.core.windows.games.kit.Money
import wm.damage.core.windows.games.kit.Rng
import wm.damage.core.windows.games.kit.Seats
import wm.damage.core.windows.games.roster.Background
import wm.damage.core.windows.games.roster.Character
import wm.damage.core.windows.games.roster.Mood
import wm.damage.core.windows.games.roster.Roster

/**
 * GAMES — `HOLDEM.md`. One window with games inside it (verdict 1), the shared
 * bankroll and the standings; Hold'em is the first and only game built, and
 * the kit under it is built so the next one is cheap (verdict 2).
 *
 * ```
 * Games (root, from Main)
 * ├─ Hold'em ──────────────▶ the live table, or the table-select level
 * ├─ Standings ────────────▶ the roster by wealth ──▶ a character's career
 * └─ Bankroll ─────────────▶ cash · Loser Count · tournaments won · refill
 *    (wrap-to-end)  Settings
 * ```
 *
 * **Pure Kotlin, no host** — a first for this system. There is no provider, no
 * channel and no `needs`: the whole world lives in this window's own records.
 *
 * 🔴 **The world advances only while Adam is playing** (verdict 27). The
 * background economy runs in the gaps between his own decisions, off-loop, and
 * stops the moment he leaves. That is what makes verdict 30's "no
 * notifications by default" correct rather than a shortcut.
 */
class GamesWindow(
    private val text: TextRasterizer,
    private val bg: CoroutineScope,
    /** Injected so a harness can drive the pacer without wall-clock waits. */
    private val clock: () -> Long = { System.currentTimeMillis() },
) : DamageWindow("games", "Games", IconKind.GAMES) {

    private val tx = styledText(text)
    private val view = HoldemView(tx)

    private enum class Level_ { GAMES, TABLES, TABLE, STANDINGS, CHARACTER, BANKROLL, HISTORY }

    private var level = Level_.GAMES
    private val gamesModel = ListModel()
    private val tablesModel = ListModel()
    private val standModel = ListModel()
    private val charDoc = DocModel()
    private val bankDoc = DocModel()
    private val histDoc = DocModel()

    private var services: ShellServices? = null
    private var active = false

    // ---- the world ----------------------------------------------------------
    val bankroll = Bankroll()
    val roster = Roster()
    private var table: HoldemTable? = null
    private var mySeat = 0
    /** Seat → character, for the bots at Adam's table. */
    private val cast = HashMap<Int, Character>()
    /** Chips Adam put in at the door, so a cash-out reports a real net. */
    private var myStake = 0
    private var revealed = 0
    private var inspect = -1
    private var acting: Int? = null
    private var openChar: String? = null
    /** Background tournaments still owed for the tournament Adam is playing. */
    private var bgOwed = 0
    private var bgLastHand = -99
    /** The hand number already settled into moods and careers — a repaint or
     *  a second `afterAction` on the same result must not count it twice. */
    private var lastSettledHand = -1

    // ---- settings (all window-owned; §14) -----------------------------------
    private var confirmPolicy = ActionLevel.Confirm.ALL
    private var paceMs = 600L
    private var dealAnim = true
    private var archetypes = false
    private var notifyBust = false
    private var notifyWin = false
    private var notifyReturn = false
    private var heightPref: Int? = null

    private var notice: String? = null
    private var noticeUntil = 0L
    private var pacerGen = 0
    private var thinking = false

    private val fRow = FontSpec(Face.SYSTEM, 18)
    private val fRowB = FontSpec(Face.SYSTEM, 18, bold = true)
    private val fSmall = FontSpec(Face.SYSTEM, 13)
    private val fLens = FontSpec(Face.SYSTEM, 15)
    private val fDoc = FontSpec(Face.SYSTEM, 16)
    private val fHead = FontSpec(Face.SYSTEM, 17, bold = true)

    override val preferredHeight: Int? get() = heightPref

    // ---- test/harness reach -------------------------------------------------
    // The `Shell.menuLabels` precedent (2026-09-03): a script must be able to
    // ASK what the table is doing rather than infer it from pixels — counting
    // notches and reading renders is how five tests broke silently when one
    // menu row moved.
    val tableRunning: Boolean get() = table != null
    val isMyTurn: Boolean get() = table?.view()?.toAct == mySeat
    val handIsComplete: Boolean get() = table?.view()?.result != null
    val seatsLeft: Int get() = table?.view()?.activeSeats?.size ?: 0
    val myStack: Int get() = table?.stackOf(mySeat) ?: 0
    val handNumber: Int get() = table?.view()?.handNo ?: -1
    val boardShown: Int get() = revealed
    val levelName: String get() = level.name
    /** The Games-root row the cursor rests on, BY NAME. */
    val rootRow: String get() = rowLabel(gamesRows()[gamesModel.cursor.mod(gamesRows().size)]).first

    // ================================================================ helpers
    private fun setNotice(s: String) {
        notice = s
        noticeUntil = clock() + 4_000
        services?.requestRender(this)
    }

    private fun onShell(action: () -> Unit) {
        services?.runOnShell(action) ?: action()
    }

    private fun dn(s: String, f: FontSpec = fRow): String = Draw.dynamic(tx, s, f)

    private fun lineH(f: FontSpec) = tx.metrics(f).lineHeight

    /** Adam, as a roster character (verdict 25) — $1,000 wealth, infinite
     *  lives, invisible traits emergent from play, in the standings. */
    private fun me(): Character = roster.get(ME) ?: Character(
        ME, "You", Character.Traits.load(null), Bankroll.BASE, livesTotal = 99,
    ).also { roster.put(it) }

    // ================================================================ lifecycle
    override fun onRegistered(ctx: ShellServices) {
        services = ctx
        seedWorld()
    }

    override fun onActivate(ctx: ShellServices, from: ActivationSource) {
        services = ctx
        active = true
        seedWorld()
        // Adam's rule, 2026-09-04 (HOLDEM.md §3, verdict 35): "Going to Games
        // from the switcher should auto-resume… Going to Games from Main
        // should present the Games List."
        if (from == ActivationSource.MAIN) goRoot()
        if (level == Level_.TABLE) pump()
    }

    override fun onDeactivate() {
        active = false
        // 🔴 verdict 27: the world stops when he leaves. The pacer's
        // generation is bumped so an answer already in flight is dropped
        // rather than applied to a window nobody is looking at.
        pacerGen++
        acting = null
    }

    private fun goRoot() {
        level = Level_.GAMES
        openChar = null
        pacerGen++
        acting = null
    }

    private fun seedWorld() {
        if (roster.worldSeed == 0L) {
            roster.worldSeed = Rng.mix(clock() xor 0x6A_11EDL)
            Log.i("games", "a new world, seed ${roster.worldSeed}")
        }
        me()
        roster.ensurePopulation()
    }

    // ================================================================ contract
    override fun view(): WindowView = when (level) {
        Level_.GAMES -> WindowView.ListView(gamesModel, { gamesRows().size },
            ::paintGamesRow, ::paintGamesLens, ::commitGames)
        Level_.TABLES -> WindowView.ListView(tablesModel, { tableRows().size },
            ::paintTableRow, ::paintTableLens, ::commitTable)
        Level_.TABLE -> WindowView.CanvasView(
            paint = { g, r -> paintTable(g, r) },
            onScroll = ::tableScroll,
            onTap = ::tableTap,
        )
        Level_.STANDINGS -> WindowView.ListView(standModel, { standRows().size },
            ::paintStandRow, ::paintStandLens, ::commitStanding)
        Level_.CHARACTER -> WindowView.DocView(charDoc, { charLines().size }, lineH(fDoc),
            { g, i, r -> paintDocLine(g, charLines(), i, r) }, {}, stepLines = { 4 })
        Level_.BANKROLL -> WindowView.DocView(bankDoc, { bankLines().size }, lineH(fDoc),
            { g, i, r -> paintDocLine(g, bankLines(), i, r) }, ::openBankrollMenu, stepLines = { 4 })
        Level_.HISTORY -> WindowView.DocView(histDoc, { histLines().size }, lineH(fSmall),
            { g, i, r -> paintDocLine(g, histLines(), i, r, fSmall) }, {}, stepLines = { 6 })
    }

    override fun title(): String {
        val n = notice
        if (n != null && clock() < noticeUntil) return n
        return when (level) {
            Level_.GAMES -> "games"
            Level_.TABLES -> "hold'em"
            Level_.TABLE -> table?.let { "hold'em · ${it.spec.label.lowercase()}" } ?: "hold'em"
            Level_.STANDINGS -> "standings"
            Level_.CHARACTER -> dn(roster.get(openChar ?: "")?.name ?: "character", fSmall)
            Level_.BANKROLL -> "bankroll"
            Level_.HISTORY -> "hand history"
        }
    }

    override fun summary(): Summary {
        // cheap and side-effect-free (WINDOWS.md §1): cached state only
        val t = table
        if (t != null) {
            val v = t.view()
            val left = v.activeSeats.size
            val mine = v.seats.getOrNull(mySeat)?.stack ?: 0
            return Summary("Hold'em · $left left · ${Money.fmt(mine)}",
                detail = "${t.spec.label} · hand ${v.handNo + 1}", more = true)
        }
        return Summary("${Money.fmt(bankroll.cash)} · W${bankroll.tournamentsWon} · L${bankroll.loserCount}",
            detail = if (bankroll.broke(HoldemRules.cheapestSeat())) "broke — refill in Bankroll"
            else "no tournament running")
    }

    override fun levelDepth(): Int = when (level) {
        Level_.GAMES -> 1
        Level_.TABLES, Level_.TABLE, Level_.STANDINGS, Level_.BANKROLL -> 2
        Level_.CHARACTER, Level_.HISTORY -> 3
    }

    override fun back(): Boolean = when (level) {
        Level_.GAMES -> false
        // 🔴 double-tap NEVER cashes out (§10.1): backing out of the window
        // leaves the table exactly as it is
        Level_.TABLE -> { pacerGen++; acting = null; level = Level_.GAMES; true }
        Level_.TABLES, Level_.STANDINGS, Level_.BANKROLL -> { level = Level_.GAMES; true }
        Level_.CHARACTER -> { openChar = null; level = Level_.STANDINGS; true }
        Level_.HISTORY -> { level = if (table != null) Level_.TABLE else Level_.GAMES; pump(); true }
    }

    override fun contentPlanes(content: Rect): List<Pair<Rect, Int>> {
        // §9.2: the table sits at the content plane, YOUR HOLE CARDS come
        // forward to plane 0 — your own hand reads as yours without lighting
        // one extra pixel
        if (level != Level_.TABLE) return emptyList()
        val r = view.holePlane(content) ?: return emptyList()
        return listOf(r to 0)
    }

    override fun onLayoutChanged() {
        charCache = null
        bankCache = null
    }

    override fun onFontScaleChanged(scale: Double) = onLayoutChanged()

    // ================================================================ games root
    private sealed class GRow {
        object Holdem : GRow()
        object Standings : GRow()
        object BankrollRow : GRow()
        object SettingsRow : GRow()
    }

    private fun gamesRows(): List<GRow> =
        listOf(GRow.Holdem, GRow.Standings, GRow.BankrollRow, GRow.SettingsRow)

    private fun rowLabel(r: GRow): Pair<String, String> = when (r) {
        GRow.Holdem -> "Hold'em" to (table?.let {
            "${it.spec.label} · ${it.view().activeSeats.size} left"
        } ?: "sit down")
        GRow.Standings -> "Standings" to "${roster.characters.size} characters"
        GRow.BankrollRow -> "Bankroll" to Money.fmt(bankroll.cash)
        GRow.SettingsRow -> "Settings" to "games"
    }

    private fun paintGamesRow(g: Gray8, i: Int, r: Rect, dim: Boolean) {
        val row = gamesRows().getOrNull(i) ?: return
        val (label, detail) = rowLabel(row)
        val lv = if (dim) Level.REST else Level.BODY
        val icons = services?.icons()
        if (row == GRow.Holdem) {
            IconPaint.draw(g, icons, IconNames.forKind(IconKind.GAMES), r.x + 4, r.y + 6, 20,
                IconKind.GAMES, lv)
        }
        Draw.fit(g, tx, r.x + 32, r.y + 5, label, lv, fRow, r.w - 200)
        Draw.right(g, tx, r.right - 8, r.y + 8, dn(detail, fSmall), if (dim) Level.REST else Level.DIM, fSmall)
    }

    private fun paintGamesLens(g: Gray8, r: Rect, i: Int) {
        val row = gamesRows().getOrNull(i) ?: return
        val (label, _) = rowLabel(row)
        when (row) {
            GRow.BankrollRow -> {
                // the SCOREBOARD (§4): cash · tournaments won · Loser Count, in
                // the seven-segment digits the silent clock already uses
                val s = Money.Seg.MEDIUM
                val w = Money.scoreboard(Gray8(1, 1), tx, 0, 0, s, bankroll.cash,
                    bankroll.tournamentsWon, bankroll.loserCount, captions = false)
                val x = r.x + (r.w - w) / 2
                Money.scoreboard(g, tx, (x / 4) * 4, r.y + 6, s, bankroll.cash,
                    bankroll.tournamentsWon, bankroll.loserCount)
            }
            GRow.Holdem -> {
                tx.draw(g, r.x + 8, r.y + 8, label, fRowB, Level.HEAD)
                val t = table
                val line = if (t == null) "three tables · " + HoldemRules.Table.entries
                    .joinToString(" · ") { it.label }
                else t.view().let { v ->
                    "hand ${v.handNo + 1} · blinds ${Money.fmt(v.sb)}/${Money.fmt(v.bb)} · " +
                        "${v.activeSeats.size} left"
                }
                Draw.fit(g, tx, r.x + 8, r.y + 34, line, Level.DIM, fLens, r.w - 16)
            }
            else -> {
                tx.draw(g, r.x + 8, r.y + 8, label, fRowB, Level.HEAD)
                val line = when (row) {
                    GRow.Standings -> roster.standings().take(3)
                        .joinToString(" · ") { "${dn(it.name, fLens)} ${Money.compact(it.worth)}" }
                    else -> "font, size, confirm, bot pace, notifications"
                }
                Draw.fit(g, tx, r.x + 8, r.y + 34, line, Level.DIM, fLens, r.w - 16)
            }
        }
    }

    private fun commitGames(i: Int) {
        when (gamesRows().getOrNull(i)) {
            GRow.Holdem -> {
                if (table != null) { level = Level_.TABLE; inspect = -1; pump() }
                else { level = Level_.TABLES; tablesModel.cursor = 0 }
            }
            GRow.Standings -> { level = Level_.STANDINGS; standModel.cursor = 0 }
            GRow.BankrollRow -> { level = Level_.BANKROLL; bankDoc.topLine = 0; bankCache = null }
            GRow.SettingsRow ->
                if (services?.openWindow("settings") != true) setNotice("Settings is not available here")
            null -> {}
        }
    }

    // ================================================================ table select
    private fun tableRows(): List<HoldemRules.Table> = HoldemRules.Table.entries

    private fun entryFor(spec: HoldemRules.Table): Int = spec.entry ?: unlimitedStake

    /** Adam's chosen Unlimited buy-in — his own call, with no minimum (§5.2). */
    private var unlimitedStake = 1_000

    private fun paintTableRow(g: Gray8, i: Int, r: Rect, dim: Boolean) {
        val spec = tableRows().getOrNull(i) ?: return
        val lv = if (dim) Level.REST else Level.BODY
        Draw.fit(g, tx, r.x + 8, r.y + 5, spec.label, lv, fRow, r.w - 220)
        val entry = entryFor(spec)
        val detail = "${Money.fmt(entry)} + ${Money.fmt(HoldemRules.fee(entry))}"
        val afford = bankroll.cash >= entry + HoldemRules.fee(entry)
        Draw.right(g, tx, r.right - 8, r.y + 8, detail,
            if (!afford) Level.FAINT else if (dim) Level.REST else Level.DIM, fSmall)
    }

    private fun paintTableLens(g: Gray8, r: Rect, i: Int) {
        val spec = tableRows().getOrNull(i) ?: return
        tx.draw(g, r.x + 8, r.y + 6, spec.label, fRowB, Level.HEAD)
        val entry = entryFor(spec)
        val fee = HoldemRules.fee(entry)
        // 🔴 verdict 24: a VISIBLE fee, on the buy-in row, and it applies to
        // Adam too
        val line1 = "${Money.fmt(entry)} + ${Money.fmt(fee)} fee · blinds " +
            "${Money.fmt(spec.sbAt(0))}/${Money.fmt(spec.bbAt(0))}"
        Draw.fit(g, tx, r.x + 8, r.y + 30, line1, Level.BODY, fLens, r.w - 16)
        val ladder = "up every ${HoldemRules.HANDS_PER_LEVEL} hands: ${spec.ladder()}"
        Draw.fit(g, tx, r.x + 8, r.y + 46, ladder, Level.DIM, fSmall, r.w - 16)
    }

    private fun commitTable(i: Int) {
        val spec = tableRows().getOrNull(i) ?: return
        if (spec.entry == null) { openStakeMenu(spec); return }
        confirmBuyIn(spec, spec.entry!!)
    }

    /** Unlimited takes ANY entry (§5.2), so it asks first — presets plus the
     *  §4.8 keyboard for a custom amount. */
    private fun openStakeMenu(spec: HoldemRules.Table) {
        val items = ArrayList<MenuSurface.Item>()
        val acts = ArrayList<() -> Unit>()
        fun add(label: String, detail: String, act: () -> Unit) {
            items.add(MenuSurface.Item(label, detail, enabled = true)); acts.add(act)
        }
        for (v in listOf(200, 500, 1_000, 2_500, 5_000, 10_000)) {
            val total = v + HoldemRules.fee(v)
            if (total > bankroll.cash) continue
            add(Money.fmt(v), "+ ${Money.fmt(HoldemRules.fee(v))}") {
                unlimitedStake = v; confirmBuyIn(spec, v)
            }
        }
        val all = maxStakeFromCash()
        if (all > 0) add("Everything", Money.fmt(all)) { unlimitedStake = all; confirmBuyIn(spec, all) }
        add("Custom", "keyboard") { openStakeKeyboard(spec) }
        if (items.isEmpty()) { setNotice("not enough to sit anywhere"); return }
        if (services?.openMenu(MenuSurface.Spec("unlimited buy-in", items,
                onCommit = { idx -> acts.getOrNull(idx)?.invoke() }), owner = this) != true) {
            setNotice("could not open the buy-in list")
        }
    }

    /** The largest stake whose 5 % fee still fits in the bankroll. */
    private fun maxStakeFromCash(): Int {
        var s = (bankroll.cash.toLong() * 100 / 105).toInt()
        while (s > 0 && s + HoldemRules.fee(s) > bankroll.cash) s--
        return s
    }

    private fun openStakeKeyboard(spec: HoldemRules.Table) {
        val ok = services?.openKeyboard(KeyboardSurface.Spec(
            title = "buy-in dollars", initial = unlimitedStake.toString(),
            onCommit = { txt ->
                val v = txt.filter { it.isDigit() }.toIntOrNull()
                if (v == null || v <= 0) setNotice("that is not an amount")
                else { unlimitedStake = v; confirmBuyIn(spec, v) }
            }), owner = this) == true
        if (!ok) setNotice("the keyboard is not available here")
    }

    private fun confirmBuyIn(spec: HoldemRules.Table, entry: Int) {
        val fee = HoldemRules.fee(entry)
        if (entry + fee > bankroll.cash) {
            setNotice("${Money.fmt(entry + fee)} is more than you have")
            return
        }
        val ok = services?.openMenu(MenuSurface.Spec(
            "${spec.label} · ${Money.fmt(entry)} + ${Money.fmt(fee)}",
            listOf(MenuSurface.Item("Cancel"),
                MenuSurface.Item("Sit down", "no rebuys · last one standing")),
            onCommit = { i -> if (i == 1) startTournament(spec, entry) }), owner = this) == true
        if (!ok) setNotice("could not open the confirm")
    }

    // ================================================================ the tournament
    private fun startTournament(spec: HoldemRules.Table, entry: Int) {
        val fee = HoldemRules.fee(entry)
        if (!bankroll.take(entry + fee)) { setNotice("not enough for the entry and the fee"); return }
        bankroll.payFee(fee)
        bankroll.tournamentsPlayed++
        roster.ensurePopulation()
        val seated = roster.seat(spec, HoldemRules.MAX_SEATS - 1, key = roster.gameNo.toLong())
        if (seated.size < 1) {
            bankroll.add(entry + fee)
            bankroll.tournamentsPlayed--
            setNotice("nobody in the room can afford that table")
            return
        }
        // Adam takes a seat among them, at a position the world chooses
        val seedRng = Rng.stream(roster.worldSeed, 0x5EA7, roster.gameNo.toLong())
        mySeat = seedRng.nextInt(seated.size + 1)
        val occupants = ArrayList<Seats.Occupant>(seated.size + 1)
        val stacks = ArrayList<Int>(seated.size + 1)
        cast.clear()
        var si = 0
        for (i in 0..seated.size) {
            if (i == mySeat) {
                occupants.add(Seats.Occupant(ME, "You", human = true))
                stacks.add(entry)
            } else {
                val s = seated[si++]
                occupants.add(Seats.Occupant(s.who.id, s.who.name, human = false))
                stacks.add(s.stake)
                cast[i] = s.who
            }
        }
        myStake = entry
        table = HoldemTable.start(spec, Rng.hash(roster.worldSeed, roster.gameNo.toLong()),
            occupants, stacks.toIntArray(), button = seedRng.nextInt(occupants.size))
        revealed = 0
        inspect = -1
        acting = null
        bgOwed = Background.owedFor(roster.worldSeed, roster.gameNo)
        bgLastHand = -99
        level = Level_.TABLE
        Log.i("games", "sat down at ${spec.label} for ${Money.fmt(entry)} " +
            "(+${Money.fmt(fee)} fee) against ${seated.size}")
        pump()
    }

    /** The table's own paint, with the paced reveal applied. */
    private fun paintTable(g: Gray8, r: Rect) {
        val t = table
        if (t == null) {
            tx.draw(g, r.x + 8, r.y + 8, "no table", fRow, Level.DIM)
            return
        }
        val v = t.view()
        val n = notice
        view.paint(g, r, HoldemView.Model(
            v = v, spec = t.spec, mySeat = mySeat, revealed = revealed, cast = cast,
            cursor = inspect, showStats = true, archetypes = archetypes,
            handsToLevel = HoldemRules.handsToNextLevel(v.handNo),
            note = if (n != null && clock() < noticeUntil) n else "",
            acting = acting))
    }

    /** §10.1: scroll SKIPS the pacing while bots act, and moves a seat-inspect
     *  cursor when it is your turn or a showdown is up. */
    private fun tableScroll(delta: Int) {
        val t = table ?: return
        val v = t.view()
        if (v.toAct != null && v.toAct != mySeat) { skipToMe(); return }
        val seats = v.seats.filter { !it.busted && it.index != mySeat }
        if (seats.isEmpty()) return
        val cur = seats.indexOfFirst { it.index == inspect }
        val next = if (cur < 0) (if (delta > 0) 0 else seats.size - 1) else (cur + delta).mod(seats.size)
        inspect = seats[next].index
        services?.requestRender(this)
    }

    private fun tableTap() {
        val t = table ?: return
        val v = t.view()
        when {
            v.result != null -> dealNext()
            v.toAct == mySeat -> openActionLevel()
            v.toAct != null -> skipToMe()
            else -> services?.requestRender(this)
        }
    }

    /** Run the bots on at full speed until it is Adam's turn again — the skip
     *  §10.1 gives to both gestures while the table is pacing. */
    private fun skipToMe() {
        val t = table ?: return
        pacerGen++
        acting = null
        var guard = 0
        while (guard++ < 400) {
            val v = t.view()
            val seat = v.toAct ?: break
            if (seat == mySeat) break
            if (!playBot(t, seat)) break
        }
        revealed = t.view().board.size
        services?.requestRender(this)
        pump()
    }

    /**
     * The PACER (verdict 28, §10.1): each bot action is drawn as it happens,
     * `Bot pace` ms apart. **A pacing loop, not a timeout** — the absolute rule
     * stands, and the bots themselves wait forever for Adam.
     *
     * The decision itself (a Monte-Carlo rollout) computes OFF the loop and
     * applies through `runOnShell`; the generation guard drops an answer that
     * arrives after the user has moved on.
     */
    private fun pump() {
        val t = table ?: return
        if (!active || level != Level_.TABLE) return
        if (thinking) return
        val v = t.view()
        if (v.result != null) {
            // a showdown STAYS UP until you act (verdict 29). Reveal the rest
            // of the board first so the finale is not one flat jump.
            if (revealed < v.board.size) { schedule(t); return }
            maybeBackground()
            return
        }
        val seat = v.toAct ?: return
        if (seat == mySeat) {
            // your turn: the "who just acted" highlight belongs to the pacer,
            // and leaving it lit marks a folded seat as live under your own
            // decision (the first table render, 2026-09-04)
            acting = null
            revealed = v.board.size
            return
        }
        if (revealed < v.board.size) { schedule(t); return }
        schedule(t)
    }

    private fun schedule(t: HoldemTable) {
        val gen = ++pacerGen
        thinking = true
        bg.launch(Dispatchers.Default) {
            val v = t.view()
            val seat = v.toAct
            val needReveal = revealed < v.board.size
            // compute the decision off-loop; the table is stable because only
            // the loop mutates it and Adam cannot act while a bot is to act
            val decision = if (needReveal || seat == null || seat == mySeat) null else {
                val c = cast[seat]
                if (c == null) null else try {
                    HoldemBot.decide(t, seat, c, Equity.LIVE_ROLLOUTS, readOf(c))
                } catch (e: Exception) {
                    Log.e("games", "seat $seat could not decide", e)
                    null
                }
            }
            if (paceMs > 0) delay(paceMs)
            onShell {
                thinking = false
                if (gen != pacerGen || table !== t || !active || level != Level_.TABLE) return@onShell
                if (needReveal) {
                    revealed = (revealed + 1).coerceAtMost(t.view().board.size)
                    services?.requestRender(this@GamesWindow)
                    pump()
                    return@onShell
                }
                if (seat == null) return@onShell
                if (decision == null) {
                    setNotice("a seat could not act — the hand is paused")
                    return@onShell
                }
                acting = seat
                applyDecision(t, seat, decision)
                revealed = revealed.coerceAtMost(t.view().board.size)
                if (!dealAnim) revealed = t.view().board.size
                services?.requestRender(this@GamesWindow)
                pump()
            }
        }
    }

    /** One bot action, on the loop. Returns false when it could not act. */
    private fun playBot(t: HoldemTable, seat: Int): Boolean {
        val c = cast[seat] ?: return false
        return try {
            val d = HoldemBot.decide(t, seat, c, Equity.LIVE_ROLLOUTS, readOf(c))
            applyDecision(t, seat, d)
            true
        } catch (e: Exception) {
            Log.e("games", "seat $seat could not act", e)
            setNotice("a seat could not act — the hand is paused")
            false
        }
    }

    /** §7.6: what THIS character has actually seen Adam do. No read until
     *  they have sat through enough hands for the number to mean anything —
     *  a read off twelve hands is noise wearing a stat's clothes. */
    private fun readOf(c: Character): Double =
        if (c.career.handsVsYou >= READ_HANDS) c.career.vpip else HoldemBot.NO_READ

    private fun applyDecision(t: HoldemTable, seat: Int, d: HoldemBot.Decision) {
        val before = t.view()
        recordAgainstYou(before, seat, d)
        when (d.kind) {
            ActionLevel.Kind.BET, ActionLevel.Kind.RAISE, ActionLevel.Kind.ALL_IN -> t.act(d.kind, d.to)
            else -> t.act(d.kind)
        }
        afterAction(t)
    }

    /**
     * 🔑 §7.7: `observance` reaches Adam for free — if his opponents' stats are
     * tracked like everyone else's, an observant character who has played 300
     * hands against him starts folding to his bluffs through machinery that
     * already exists. These are the numbers a character's detail level shows,
     * and they are **measured over hands you actually sat through**, never read
     * off a trait sheet (never displayed, verdict 37's neighbour).
     */
    private fun recordAgainstYou(v: HoldemTable.View, seat: Int, d: HoldemBot.Decision) {
        val c = cast[seat] ?: return
        if (v.seats[mySeat].busted) return
        if (v.street == Street.PREFLOP && v.seats[seat].lastAction.isEmpty()) {
            c.career.handsVsYou++
            if (d.kind != ActionLevel.Kind.FOLD && d.kind != ActionLevel.Kind.CHECK) c.career.vpipVsYou++
        }
        if (d.kind == ActionLevel.Kind.BET || d.kind == ActionLevel.Kind.RAISE ||
            d.kind == ActionLevel.Kind.ALL_IN) c.career.aggressiveVsYou++
    }

    /** Called after every action: when a hand COMPLETES, settle mood and the
     *  head-to-head record §7.7 shows on a character's detail level. */
    private fun afterAction(t: HoldemTable) {
        val v = t.view()
        val r = v.result ?: return
        if (r.handNo == lastSettledHand) return       // a repaint must not double-count
        lastSettledHand = r.handNo
        val mine = v.seats[mySeat]
        val iPlayed = mine.contributed > 0 && !mine.busted
        for ((seat, c) in cast) {
            val s = v.seats[seat]
            if (s.busted) continue
            val net = (r.won[seat] ?: 0) - s.contributed
            Mood.afterHand(c, net, maxOf(1, s.stack + s.contributed))
            // "you and them" counts only hands you actually sat through
            if (iPlayed && s.contributed > 0) c.career.netVsYou += net
        }
        // knock-outs, attributed to whoever took the chips
        if (iPlayed && mine.stack == 0) {
            val taker = r.won.entries.filter { it.key != mySeat }.maxByOrNull { it.value }?.key
            taker?.let { cast[it]?.career?.knockedYouOut = (cast[it]!!.career.knockedYouOut + 1) }
        }
        if ((r.won[mySeat] ?: 0) > 0) {
            for ((seat, c) in cast) {
                val s = v.seats[seat]
                if (!s.busted && s.contributed > 0 && s.stack == 0) c.career.youKnockedOut++
            }
        }
        // verdict 30: OFF by default, and in Games' own Settings category
        if (notifyBust) {
            for ((seat, c) in cast) {
                val s = v.seats[seat]
                if (!s.busted && s.stack == 0 && s.contributed > 0) {
                    services?.notifyInternal("games", "${dn(c.name, fSmall)} is out",
                        appId = id, thread = "bust:${c.id}", target = "char:${c.id}")
                }
            }
        }
        services?.requestRender(this)
    }

    /** Tap on a finished hand: deal the next one (verdict 29). */
    private fun dealNext() {
        val t = table ?: return
        if (t.view().result == null) return
        val stillIn = t.inPlay(mySeat) && t.stackOf(mySeat) > 0
        val running = try { t.nextHand() } catch (e: Exception) {
            Log.e("games", "the hand would not settle", e)
            setNotice("the hand would not settle")
            return
        }
        revealed = 0
        inspect = -1
        acting = null
        if (!running) { finishTournament(t); return }
        if (!stillIn || !t.inPlay(mySeat)) { playOutWithoutMe(t); return }
        maybeBackground()
        services?.requestRender(this)
        pump()
    }

    /** Adam is out but the table is not: verdict 11 — **the remaining
     *  characters play the tournament out**, which is what keeps the economy
     *  conserved and lands the winner's cashflow where it belongs. */
    private fun playOutWithoutMe(t: HoldemTable) {
        setNotice("you are out — the table plays on")
        pacerGen++
        thinking = true
        bg.launch(Dispatchers.Default) {
            try { Background.playOut(t, HashMap(cast), Equity.CHEAP_ROLLOUTS) } catch (e: Exception) {
                Log.e("games", "the table could not be played out", e)
            }
            onShell {
                thinking = false
                if (table === t) finishTournament(t)
            }
        }
    }

    /**
     * The tournament is over. **Chips are dollars 1:1 and a sit-and-go is
     * conserved**, so the settlement is simply: whoever holds the chips takes
     * them home. A cash-out has already moved its own chips off the table
     * (verdict 11), so what is left is exactly the prize.
     */
    private fun finishTournament(t: HoldemTable) {
        val v = t.view()
        val winner = t.winner()
        val prize = v.seats.sumOf { it.stack }
        val myPlace = t.finishPlace(mySeat) ?: 1
        if (winner == mySeat) {
            bankroll.add(prize)
            bankroll.tournamentsWon++
            if (notifyWin) services?.notifyInternal("games",
                "you won the ${t.spec.label} table · ${Money.fmt(prize)}",
                appId = id, thread = "won")
        } else if (winner != null) {
            cast[winner]?.let { it.bankroll += prize }
        }
        // careers and mood for the whole field, then the roster's own lives
        // machinery — the same settlement a background game runs (§7.5)
        val field = v.seats.size
        for ((seat, c) in cast) {
            val place = t.finishPlace(seat) ?: 1
            c.career.tournaments++
            c.career.finishSum += place
            if (place == 1) c.career.wins++
            Mood.afterTournament(c, place, field, if (place == 1) prize else 0)
        }
        val meC = me()
        meC.career.tournaments++
        meC.career.finishSum += myPlace
        if (myPlace == 1) meC.career.wins++
        meC.career.lifetimeNet += (if (winner == mySeat) prize else 0) - myStake
        meC.bankroll = bankroll.cash
        roster.gameNo++
        for (c in cast.values) roster.settleBroke(c)
        roster.tick()
        roster.ensurePopulation()
        Log.i("games", "the ${t.spec.label} table is done: " +
            "${if (winner == mySeat) "you" else cast[winner]?.name ?: "?"} took ${Money.fmt(prize)}")
        table = null
        cast.clear()
        myStake = 0
        lastSettledHand = -1
        level = Level_.GAMES
        setNotice(if (winner == mySeat) "you win ${Money.fmt(prize)}" else "you finished ${ordinal(myPlace)}")
        offerRefillIfBroke()
        services?.requestRender(this)
    }

    private fun ordinal(n: Int): String = when (n) {
        1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${n}th"
    }

    /** §7.5's ratio, spent between Adam's own hands. */
    private fun maybeBackground() {
        val t = table ?: return
        if (bgOwed <= 0 || thinking) return
        val v = t.view()
        if (v.handNo - bgLastHand < BG_EVERY) return
        bgLastHand = v.handNo
        bgOwed--
        thinking = true
        bg.launch(Dispatchers.Default) {
            val s = try { Background.playTournament(roster) } catch (e: Exception) {
                Log.e("games", "a background tournament failed", e); null
            }
            onShell {
                thinking = false
                s?.let { Log.i("games", "background: ${it.spec.label}, ${it.hands} hands, ${it.winner} won") }
                if (notifyReturn && s != null) {
                    for (c in roster.characters) if (c.returnsAt == roster.gameNo &&
                        c.state == Character.State.PLAYING) {
                        services?.notifyInternal("games", "${dn(c.name, fSmall)} is back at the tables",
                            appId = id, thread = "return:${c.id}")
                    }
                }
                pump()
            }
        }
    }

    // ================================================================ action levels
    /** §10.2. Row 0 is ALWAYS the contextual give-up row — Check when checking
     *  is free, Fold when facing a bet — and verdict 33 does NOT exempt it from
     *  the confirm. The wrap-end window actions are ordered so one notch UP
     *  from rest lands on something harmless. */
    private fun openActionLevel() {
        val t = table ?: return
        val v = t.view()
        if (v.toAct != mySeat) return
        val legal = t.legalActions()
        if (legal.isEmpty()) return
        val items = ArrayList<MenuSurface.Item>()
        val acts = ArrayList<() -> Unit>()
        fun add(label: String, detail: String, act: () -> Unit) {
            items.add(MenuSurface.Item(label, detail)); acts.add(act)
        }
        for (a in legal) when (a.kind) {
            ActionLevel.Kind.BET, ActionLevel.Kind.RAISE -> add(a.label + " ->", "sizes") { openSizing() }
            else -> add(a.label, a.detail) { stage(a) }
        }
        add("Cash out", "and leave") { confirmCashOut() }
        add("Standings", "${roster.characters.size}") { level = Level_.STANDINGS; standModel.cursor = 0 }
        add("Hand history", "${v.history.size} lines") { level = Level_.HISTORY; histDoc.topLine = 0 }
        if (services?.openMenu(MenuSurface.Spec("your move", items,
                onCommit = { i -> acts.getOrNull(i)?.invoke() }), owner = this) != true) {
            setNotice("could not open the action list")
        }
    }

    /** §10.3: a preset ladder plus a Custom row that opens the §4.8 keyboard. */
    private fun openSizing() {
        val t = table ?: return
        val ladder = t.sizingLadder()
        if (ladder.isEmpty()) { setNotice("no sizes are available"); return }
        val items = ArrayList<MenuSurface.Item>()
        val acts = ArrayList<() -> Unit>()
        for (a in ladder) {
            items.add(MenuSurface.Item(a.label, a.detail))
            acts.add { stage(a) }
        }
        items.add(MenuSurface.Item("Custom", "keyboard"))
        acts.add { openAmountKeyboard() }
        if (services?.openMenu(MenuSurface.Spec("how much", items,
                onCommit = { i -> acts.getOrNull(i)?.invoke() }), owner = this) != true) {
            setNotice("could not open the sizes")
        }
    }

    private fun openAmountKeyboard() {
        val t = table ?: return
        val min = t.minRaiseTo()
        val max = t.maxRaiseTo()
        val ok = services?.openKeyboard(KeyboardSurface.Spec(
            title = "raise to ${Money.fmt(min)}-${Money.fmt(max)}", initial = min.toString(),
            onCommit = { txt ->
                val v = txt.filter { it.isDigit() }.toIntOrNull()
                when {
                    v == null -> setNotice("that is not an amount")
                    v < min && v < max -> setNotice("the minimum is ${Money.fmt(min)}")
                    v > max -> setNotice("you only have ${Money.fmt(max)}")
                    else -> stage(ActionLevel.Action(
                        if (v >= max) ActionLevel.Kind.ALL_IN
                        else if (t.view().currentBet > 0) ActionLevel.Kind.RAISE else ActionLevel.Kind.BET,
                        "Raise", Money.fmt(v), v))
                }
            }), owner = this) == true
        if (!ok) setNotice("the keyboard is not available here")
    }

    /**
     * §10.4: the confirm level. **Cursor rests on Cancel** — one notch, one
     * tap — and the exact amount is shown HERE, which is why the confirm sits
     * after sizing rather than before it.
     */
    private fun stage(a: ActionLevel.Action) {
        if (!ActionLevel.needsConfirm(confirmPolicy, a)) { commitAction(a); return }
        val ok = services?.openMenu(MenuSurface.Spec(a.label,
            listOf(MenuSurface.Item("Cancel"), MenuSurface.Item(ActionLevel.confirmLabel(a))),
            onCommit = { i -> if (i == 1) commitAction(a) }), owner = this) == true
        if (!ok) setNotice("could not open the confirm")
    }

    private fun commitAction(a: ActionLevel.Action) {
        val t = table ?: return
        try {
            when (a.kind) {
                ActionLevel.Kind.BET, ActionLevel.Kind.RAISE, ActionLevel.Kind.ALL_IN ->
                    t.act(a.kind, a.amount)
                else -> t.act(a.kind)
            }
        } catch (e: Exception) {
            Log.e("games", "your action was refused", e)
            setNotice(e.message ?: "that action is not legal here")
            return
        }
        afterAction(t)
        acting = null
        revealed = revealed.coerceAtMost(t.view().board.size)
        if (!dealAnim) revealed = t.view().board.size
        services?.requestRender(this)
        pump()
    }

    /** 🔴 Leaving a tournament is an explicit menu row with a confirm — never
     *  a double-tap (§10.1). */
    private fun confirmCashOut() {
        val t = table ?: return
        val chips = t.stackOf(mySeat)
        val ok = services?.openMenu(MenuSurface.Spec(
            "Cash out ${Money.fmt(chips)}?",
            listOf(MenuSurface.Item("Cancel"),
                MenuSurface.Item("Cash out", "no re-entry · the table plays on")),
            onCommit = { i -> if (i == 1) cashOut() }), owner = this) == true
        if (!ok) setNotice("could not open the confirm")
    }

    private fun cashOut() {
        val t = table ?: return
        if (t.view().result == null && t.view().seats[mySeat].contributed > 0) {
            setNotice("finish the hand first — cash out is offered between hands")
            return
        }
        val chips = try { t.cashOut(mySeat) } catch (e: Exception) {
            setNotice(e.message ?: "cannot cash out mid-hand"); return
        }
        bankroll.add(chips)
        setNotice("cashed out ${Money.fmt(chips)}")
        playOutWithoutMe(t)
    }

    // ================================================================ standings
    private fun standRows(): List<Character> = roster.standings()

    private fun paintStandRow(g: Gray8, i: Int, r: Rect, dim: Boolean) {
        val c = standRows().getOrNull(i) ?: return
        val lv = if (dim) Level.REST else Level.BODY
        val mine = c.id == ME
        Draw.fit(g, tx, r.x + 8, r.y + 5, dn(c.name, fRow), if (mine && !dim) Level.HEAD else lv,
            if (mine) fRowB else fRow, r.w - 200)
        val mark = when (c.state) {
            Character.State.PLAYING -> ""
            Character.State.BETWEEN_LIVES -> " · away"
            Character.State.RETIRED -> " · retired"
        }
        Draw.right(g, tx, r.right - 8, r.y + 8, Money.compact(c.worth) + mark,
            if (dim) Level.REST else Level.DIM, fSmall)
    }

    private fun paintStandLens(g: Gray8, r: Rect, i: Int) {
        val c = standRows().getOrNull(i) ?: return
        tx.draw(g, r.x + 8, r.y + 6, dn(c.name, fRowB), fRowB, Level.HEAD)
        val bits = ArrayList<String>()
        bits.add(Money.fmt(c.worth))
        if (c.career.tournaments > 0) {
            bits.add("${c.career.wins}/${c.career.tournaments} won")
            bits.add("avg ${"%.1f".format(c.career.avgFinish)}")
        }
        if (c.id != ME) bits.add("${c.livesLeft}/${c.livesTotal} lives")
        Draw.fit(g, tx, r.x + 8, r.y + 30, bits.joinToString(" · "), Level.BODY, fLens, r.w - 16)
        val h2h = if (c.career.handsVsYou > 0)
            "${c.career.handsVsYou} hands with you · vpip ${(c.career.vpip * 100).toInt()}%"
        else "you have not played them"
        Draw.fit(g, tx, r.x + 8, r.y + 46, if (c.id == ME) "that's you" else h2h, Level.DIM, fSmall, r.w - 16)
    }

    private fun commitStanding(i: Int) {
        val c = standRows().getOrNull(i) ?: return
        openChar = c.id
        charDoc.topLine = 0
        charCache = null
        level = Level_.CHARACTER
    }

    // ================================================================ documents
    private class DocLine(val s: String, val f: FontSpec, val lv: Int, val gap: Boolean = false)

    private var charCache: List<DocLine>? = null
    private var bankCache: List<DocLine>? = null

    private fun paintDocLine(g: Gray8, lines: List<DocLine>, i: Int, r: Rect, f: FontSpec = fDoc) {
        val l = lines.getOrNull(i) ?: return
        if (l.s.isEmpty()) return
        Draw.fit(g, tx, r.x + 8, r.y + 2, Draw.dynamic(tx, l.s, l.f), l.lv, l.f, r.w - 16)
    }

    private fun charLines(): List<DocLine> {
        charCache?.let { return it }
        val c = roster.get(openChar ?: "") ?: return listOf(DocLine("gone", fDoc, Level.DIM))
        val out = ArrayList<DocLine>()
        fun line(s: String, f: FontSpec = fDoc, lv: Int = Level.BODY) = out.add(DocLine(s, f, lv))
        line(c.name, fHead, Level.HEAD)
        line("")
        line("net worth ${Money.fmt(c.worth)}")
        if (c.id != ME) {
            // 🔴 the trait SHEET is never displayed (§7.7): only the wealth
            // BAND, which is a circumstance, and a coarse archetype when the
            // Settings row asks for one
            line("wealth band ${wealthBand(c.generalWealth)}", fDoc, Level.DIM)
            line("lives ${c.livesLeft} of ${c.livesTotal}", fDoc, Level.DIM)
            line("state ${c.state.name.lowercase().replace('_', ' ')}", fDoc, Level.DIM)
            if (archetypes) line("plays ${c.traits.archetype()}", fDoc, Level.DIM)
        } else {
            line("your General Wealth ${Money.fmt(Bankroll.BASE)} · lives spent ${bankroll.loserCount}",
                fDoc, Level.DIM)
        }
        line("")
        line("career", fHead, Level.HEAD)
        line("${c.career.tournaments} tournaments · ${c.career.wins} won")
        if (c.career.tournaments > 0) line("average finish ${"%.2f".format(c.career.avgFinish)}")
        line("lifetime net ${Money.fmt(c.career.lifetimeNet)}")
        if (c.id != ME) {
            line("")
            line("you and them", fHead, Level.HEAD)
            if (c.career.handsVsYou == 0) line("no hands together yet", fDoc, Level.DIM)
            else {
                line("${c.career.handsVsYou} hands played")
                // measured over hands YOU sat through — the read you would be
                // keeping in your head at a real table (§7.7)
                line("they play ${(c.career.vpip * 100).toInt()}% of hands against you")
                line("aggression ${"%.2f".format(c.career.aggression)} bets and raises per pot")
                line("they knocked you out ${c.career.knockedYouOut} time(s)")
                line("you knocked them out ${c.career.youKnockedOut} time(s)")
            }
        }
        charCache = out
        return out
    }

    private fun wealthBand(w: Int): String = when {
        w < 1_500 -> "short"
        w < 4_000 -> "comfortable"
        w < 8_000 -> "well off"
        else -> "rich"
    }

    private fun bankLines(): List<DocLine> {
        bankCache?.let { return it }
        val out = ArrayList<DocLine>()
        fun line(s: String, f: FontSpec = fDoc, lv: Int = Level.BODY) = out.add(DocLine(s, f, lv))
        line("cash ${Money.fmt(bankroll.cash)}", fHead, Level.HEAD)
        val t = table
        if (t != null) line("on the table ${Money.fmt(t.stackOf(mySeat))}", fDoc, Level.MID)
        line("")
        line("Loser Count ${bankroll.loserCount}", fDoc,
            if (bankroll.loserCount > 0) Level.HOT else Level.BODY)
        line("tournaments won ${bankroll.tournamentsWon} of ${bankroll.tournamentsPlayed}")
        line("entry fees paid ${Money.fmt(bankroll.feesPaid)}", fDoc, Level.DIM)
        line("")
        line("The pool is shared by every betting game.", fDoc, Level.DIM)
        line("Refill puts you back to ${Money.fmt(Bankroll.BASE)}", fDoc, Level.DIM)
        line("and adds one to the Loser Count.", fDoc, Level.DIM)
        line("")
        line("tap for the bankroll menu", fSmall, Level.DIM)
        bankCache = out
        return out
    }

    private fun openBankrollMenu() {
        val items = ArrayList<MenuSurface.Item>()
        val acts = ArrayList<() -> Unit>()
        items.add(MenuSurface.Item("Close")); acts.add { }
        items.add(MenuSurface.Item("Refill to ${Money.fmt(Bankroll.BASE)}",
            "Loser Count ${bankroll.loserCount} -> ${bankroll.loserCount + 1}"))
        acts.add { confirmRefill() }
        if (services?.openMenu(MenuSurface.Spec("bankroll", items,
                onCommit = { i -> acts.getOrNull(i)?.invoke() }), owner = this) != true) {
            setNotice("could not open the bankroll menu")
        }
    }

    private fun confirmRefill() {
        val ok = services?.openMenu(MenuSurface.Spec("Refill to ${Money.fmt(Bankroll.BASE)}?",
            listOf(MenuSurface.Item("Cancel"),
                MenuSurface.Item("Refill", "Loser Count +1")),
            onCommit = { i -> if (i == 1) doRefill() }), owner = this) == true
        if (!ok) setNotice("could not open the confirm")
    }

    private fun doRefill() {
        bankroll.refill()
        me().bankroll = bankroll.cash
        bankCache = null
        setNotice("refilled · Loser Count ${bankroll.loserCount}")
        services?.requestRender(this)
    }

    /** Verdict 14: offered when broke, and reachable manually at any time. */
    private fun offerRefillIfBroke() {
        if (!bankroll.broke(HoldemRules.cheapestSeat())) return
        onShell {
            if (!active) return@onShell
            val ok = services?.openMenu(MenuSurface.Spec(
                "broke · ${Money.fmt(bankroll.cash)}",
                listOf(MenuSurface.Item("Not yet"),
                    MenuSurface.Item("Refill to ${Money.fmt(Bankroll.BASE)}", "Loser Count +1")),
                onCommit = { i -> if (i == 1) doRefill() }), owner = this) == true
            if (!ok) setNotice("broke — refill from the Bankroll level")
        }
    }

    private fun histLines(): List<DocLine> {
        val t = table ?: return listOf(DocLine("no hand", fSmall, Level.DIM))
        val v = t.view()
        val out = ArrayList<DocLine>()
        out.add(DocLine("hand ${v.handNo + 1} · ${t.spec.label} · " +
            "blinds ${Money.fmt(v.sb)}/${Money.fmt(v.bb)}", fHead, Level.HEAD))
        for (l in v.history) out.add(DocLine(l, fSmall, Level.BODY))
        v.result?.let { out.add(DocLine(it.line, fSmall, Level.HEAD)) }
        return out
    }

    // ================================================================ settings
    private val settingsRows: List<HostSetting> by lazy {
        listOf(
            HostSetting("Confirm", ActionLevel.Confirm.LABELS,
                { confirmPolicy.label }, { confirmPolicy = ActionLevel.Confirm.byLabel(it) }),
            HostSetting("Bot pace", PACES.keys.toList(),
                { PACES.entries.firstOrNull { e -> e.value == paceMs }?.key ?: "600 ms" },
                { paceMs = PACES[it] ?: 600L }),
            HostSetting("Deal animation", listOf("on", "off"),
                { if (dealAnim) "on" else "off" }, { dealAnim = it == "on" }),
            HostSetting("Archetype labels", listOf("off", "on"),
                { if (archetypes) "on" else "off" }, { archetypes = it == "on"; charCache = null }),
            // 🔴 every notification default OFF (verdict 30): the state does
            // not change while he is away from the game, so there is nothing
            // to tell him. In GAMES' own category, never Global.
            HostSetting("Notify · bot busted", listOf("off", "on"),
                { if (notifyBust) "on" else "off" }, { notifyBust = it == "on" }),
            HostSetting("Notify · tournament won", listOf("off", "on"),
                { if (notifyWin) "on" else "off" }, { notifyWin = it == "on" }),
            HostSetting("Notify · character returned", listOf("off", "on"),
                { if (notifyReturn) "on" else "off" }, { notifyReturn = it == "on" }),
            HostSetting("Size", listOf("global") + ShellSettings.HEIGHTS.map { "$it" },
                { heightPref?.toString() ?: "global" }, { heightPref = it.toIntOrNull() }),
        )
    }

    override fun appSettings(): List<HostSetting> = settingsRows

    // ================================================================ persistence
    override fun saveState(): JsonObject = buildJsonObject {
        put("level", level.name)
        put("gamesCursor", gamesModel.cursor)
        put("tablesCursor", tablesModel.cursor)
        put("standCursor", standModel.cursor)
        put("charTop", charDoc.topLine)
        put("bankTop", bankDoc.topLine)
        openChar?.let { put("openChar", it) }
        put("mySeat", mySeat)
        put("myStake", myStake)
        put("revealed", revealed)
        put("unlimitedStake", unlimitedStake)
        put("bgOwed", bgOwed)
        put("confirm", confirmPolicy.name)
        put("paceMs", paceMs)
        put("dealAnim", dealAnim)
        put("archetypes", archetypes)
        put("notifyBust", notifyBust)
        put("notifyWin", notifyWin)
        put("notifyReturn", notifyReturn)
        heightPref?.let { put("height", it) }
    }

    override fun restoreState(state: JsonObject) {
        level = state["level"]?.jsonPrimitive?.contentOrNull
            ?.let { n -> Level_.entries.firstOrNull { it.name == n } } ?: Level_.GAMES
        gamesModel.cursor = state["gamesCursor"]?.jsonPrimitive?.intOrNull ?: 0
        tablesModel.cursor = state["tablesCursor"]?.jsonPrimitive?.intOrNull ?: 0
        standModel.cursor = state["standCursor"]?.jsonPrimitive?.intOrNull ?: 0
        charDoc.topLine = state["charTop"]?.jsonPrimitive?.intOrNull ?: 0
        bankDoc.topLine = state["bankTop"]?.jsonPrimitive?.intOrNull ?: 0
        openChar = state["openChar"]?.jsonPrimitive?.contentOrNull
        mySeat = (state["mySeat"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
        myStake = state["myStake"]?.jsonPrimitive?.intOrNull ?: 0
        revealed = (state["revealed"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
        unlimitedStake = (state["unlimitedStake"]?.jsonPrimitive?.intOrNull ?: 1_000).coerceAtLeast(1)
        bgOwed = (state["bgOwed"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, 8)
        confirmPolicy = state["confirm"]?.jsonPrimitive?.contentOrNull
            ?.let { n -> ActionLevel.Confirm.entries.firstOrNull { it.name == n } } ?: ActionLevel.Confirm.ALL
        paceMs = state["paceMs"]?.jsonPrimitive?.longOrNull?.takeIf { it in PACES.values } ?: 600L
        dealAnim = state["dealAnim"]?.jsonPrimitive?.booleanOrNull ?: true
        archetypes = state["archetypes"]?.jsonPrimitive?.booleanOrNull ?: false
        notifyBust = state["notifyBust"]?.jsonPrimitive?.booleanOrNull ?: false
        notifyWin = state["notifyWin"]?.jsonPrimitive?.booleanOrNull ?: false
        notifyReturn = state["notifyReturn"]?.jsonPrimitive?.booleanOrNull ?: false
        heightPref = state["height"]?.jsonPrimitive?.intOrNull?.takeIf { it in ShellSettings.HEIGHTS }
        // a level that needs a table has none until the sub-record lands; the
        // reconciliation below runs after every sub-restore
        pacerGen++
        acting = null
        lastSettledHand = -1
        charCache = null
        bankCache = null
        reconcileLevel()
    }

    /** A restored level that no longer has anything under it falls back rather
     *  than showing a bare surface forever (WINDOWS.md §5, the §25 #7 trap). */
    private fun reconcileLevel() {
        if (level == Level_.TABLE && table == null) level = Level_.GAMES
        if (level == Level_.HISTORY && table == null) level = Level_.GAMES
        if (level == Level_.CHARACTER && roster.get(openChar ?: "") == null) {
            openChar = null
            level = Level_.STANDINGS
        }
        val t = table
        if (t != null) {
            mySeat = mySeat.coerceIn(0, t.occupants.size - 1)
            cast.clear()
            for (i in t.occupants.indices) {
                if (i == mySeat) continue
                roster.get(t.occupants[i].id)?.let { cast[i] = it }
            }
            revealed = revealed.coerceIn(0, t.view().board.size)
        }
    }

    override fun restoreStateLive(state: JsonObject) {
        restoreState(state)
        if (active && level == Level_.TABLE) pump()
        services?.requestRender(this)
    }

    /**
     * §11.1's records. Per-item sub-records because two drivers touching
     * DIFFERENT items must not clobber each other under last-write-wins — and
     * a roster of 35 characters in one blob would do exactly that.
     *
     * ⚠ Never report an EMPTY object: an empty blob IS the §16.4a removal
     * TOMBSTONE and would fresh-stamp a deletion of the peer's real record
     * (the §25 #8 trap).
     */
    override fun saveSubState(): Map<String, JsonObject> {
        val out = LinkedHashMap<String, JsonObject>()
        out["bankroll"] = bankroll.toJson()
        out["world"] = roster.toJson()
        table?.let { out["table"] = it.toJson() }
        for (c in roster.characters) out["char.${c.id}"] = c.toJson()
        return out
    }

    override fun restoreSubState(subKey: String, state: JsonObject) {
        if (state.isEmpty()) {
            // the removal tombstone
            when {
                subKey == "table" -> { table = null; cast.clear() }
                subKey.startsWith("char.") -> roster.remove(subKey.removePrefix("char."))
            }
            reconcileLevel()
            return
        }
        when {
            subKey == "bankroll" -> bankroll.load(state)
            subKey == "world" -> roster.loadWorld(state)
            subKey == "table" -> {
                table = HoldemTable.load(state)
                if (table == null) setNotice("the saved table would not replay — it was dropped")
            }
            subKey.startsWith("char.") -> Character.load(state)?.let { roster.put(it) }
        }
        reconcileLevel()
    }

    /** §16.1: `char:<id>` opens a character; `table` opens the live table. */
    override fun open(target: String): Boolean = when {
        target == "table" && table != null -> { level = Level_.TABLE; pump(); true }
        target.startsWith("char:") -> {
            val id = target.removePrefix("char:")
            if (roster.get(id) == null) false
            else { openChar = id; charCache = null; charDoc.topLine = 0; level = Level_.CHARACTER; true }
        }
        else -> false
    }

    companion object {
        /** Adam's own character id (verdict 25: he is a roster character). */
        const val ME = "you"

        /** §7.5's ratio in practice: one background tournament every N hands
         *  of Adam's, which over a 60–120-hand sit-and-go lands in the 2–3
         *  band verdict 26 asks for. */
        const val BG_EVERY = 22

        /** Hands together before a character trusts their read (§7.6). */
        const val READ_HANDS = HoldemView.READ_HANDS

        val PACES = linkedMapOf(
            "instant" to 0L, "300 ms" to 300L, "600 ms" to 600L,
            "1 s" to 1_000L, "1.5 s" to 1_500L,
        )
    }
}
