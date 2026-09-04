package wm.damage.core.windows.games

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
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
    /** Seat → what that bot paid at the door (stake + fee), so the settlement
     *  records a NET lifetime figure rather than a gross one. */
    private val castStake = HashMap<Int, Int>()
    private var revealed = 0
    private var inspect = -1
    private var acting: Int? = null
    private var openChar: String? = null
    /** Where Standings was opened from, so back returns there (the action
     *  level offers it from the table). */
    private var standFrom: Level_? = null
    /** Background tournaments still owed for the tournament Adam is playing. */
    private var bgOwed = 0
    private var bgLastHand = -99
    /** The hand number already settled into moods and careers — a repaint or
     *  a second `afterAction` on the same result must not count it twice. */
    private var lastSettledHand = -1
    /** You asked to leave while your hand was live: you folded, and the chips
     *  come off the table the moment the hand settles (§10.2). */
    private var cashOutPending = false

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
    /** §10.1's skip: the pace is bypassed until it is Adam's turn again. */
    private var skipping = false

    private val fRow = FontSpec(Face.SYSTEM, 18)
    private val fRowB = FontSpec(Face.SYSTEM, 18, bold = true)
    private val fSmall = FontSpec(Face.SYSTEM, 13)
    private val fLens = FontSpec(Face.SYSTEM, 15)
    // The 64 px lens holds three lines only at these sizes. Measured on the
    // real rasterizer (Clear Sans, x-height normalised): ink — ascent plus
    // descent — is 27 px at 18 bold, 25 at 17 bold, 23 at 15, 20 at 13 and 17
    // at 11, so 18/15/13 at 6/30/46 could not fit and the third line landed on
    // the second and on the lens rule (first live session, 2026-09-04).
    private val fLensHead = FontSpec(Face.SYSTEM, 17, bold = true)
    private val fLensBody = FontSpec(Face.SYSTEM, 13)
    private val fLensTail = FontSpec(Face.SYSTEM, 11)
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
    /** What you have put into THIS hand — 0 out of the blinds, before you act. */
    val myContributed: Int get() = table?.view()?.seats?.getOrNull(mySeat)?.contributed ?: 0
    val handNumber: Int get() = table?.view()?.handNo ?: -1
    val boardShown: Int get() = revealed
    val levelName: String get() = level.name
    /** Adam's net worth as the standings show it: bankroll plus his stack. */
    val myWorth: Int get() = bankroll.cash + (table?.stackOf(mySeat) ?: 0)
    /** The Games-root row the cursor rests on, BY NAME. */
    val rootRow: String get() = rowLabel(gamesRows()[gamesModel.cursor.mod(gamesRows().size)]).first
    /** The Hand-history document, as strings — the doc rhythm is one line per
     *  entry, so a harness can pin the header's spacer. */
    val historyDocLines: List<String> get() = histLines().map { it.s }

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

    /** A line's drawn extent: ascent plus descent. The line HEIGHT adds
     *  leading, which is empty and may hang past a box's rule harmlessly. */
    private fun ink(f: FontSpec) = tx.metrics(f).let { it.ascent + it.descent }

    /** Adam, as a roster character (verdict 25) — $1,000 wealth, infinite
     *  lives, invisible traits emergent from play, in the standings. */
    private fun me(): Character = roster.get(ME) ?: Character(
        ME, "You", Character.Traits.load(null), Bankroll.BASE, livesTotal = 99,
    ).also { roster.put(it) }

    // ================================================================ lifecycle
    override fun onRegistered(ctx: ShellServices) {
        // 🔴 NO world seeding here. `onRegistered` runs BEFORE the sub-records
        // arrive, so populating would mint 35 characters against a fresh clock
        // seed; the restore then overwrites the ids it has and leaves the rest
        // behind as strangers with full bankrolls — free money on every start.
        services = ctx
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
        skipping = false
        // 🔴 verdict 27: the world stops when he leaves. The pacer's
        // generation is bumped so an answer already in flight is dropped
        // rather than applied to a window nobody is looking at.
        pacerGen++
        acting = null
    }

    private fun goRoot() {
        level = Level_.GAMES
        openChar = null
        // the root LIST, from its first row — coming in from Main and landing
        // on Bankroll because that is where the cursor was left is not
        // "present the Games List" (first live session, 2026-09-04). The
        // Reader precedent resets its folder and cursor the same way.
        gamesModel.cursor = 0
        standFrom = null
        pacerGen++
        acting = null
    }

    private fun seedWorld() {
        roster.humanId = ME
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
        // the TABLE draws its own notice across the status band, where it is
        // readable; repeating it in the 400 px title cell only truncates it
        if (n != null && clock() < noticeUntil && level != Level_.TABLE) return n
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
        Level_.STANDINGS -> {
            level = standFrom?.takeIf { it == Level_.TABLE && table != null } ?: Level_.GAMES
            standFrom = null
            if (level == Level_.TABLE) pump()
            true
        }
        Level_.TABLES, Level_.BANKROLL -> { level = Level_.GAMES; true }
        Level_.CHARACTER -> { openChar = null; charCache = null; level = Level_.STANDINGS; true }
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
        // the left fit is sized against the MEASURED detail, not a constant
        val d = dn(detail, fSmall)
        Draw.fit(g, tx, r.x + 32, r.y + 5, label, lv, fRow, r.w - 40 - tx.measure(d, fSmall) - 16)
        Draw.right(g, tx, r.right - 8, r.y + 8, d, if (dim) Level.REST else Level.DIM, fSmall)
    }

    private fun paintGamesLens(g: Gray8, r: Rect, i: Int) {
        val row = gamesRows().getOrNull(i) ?: return
        val (label, _) = rowLabel(row)
        when (row) {
            GRow.BankrollRow -> {
                // the SCOREBOARD (§4): cash · tournaments won · Loser Count, in
                // the seven-segment digits the silent clock already uses
                val s = Money.Seg.MEDIUM
                val w = Money.scoreboardWidth(tx, s, bankroll.cash,
                    bankroll.tournamentsWon, bankroll.loserCount)
                val x = r.x + (r.w - w) / 2
                Money.scoreboard(g, tx, (x / 4) * 4, r.y + 6, s, bankroll.cash,
                    bankroll.tournamentsWon, bankroll.loserCount)
            }
            GRow.Holdem -> {
                tx.draw(g, r.x + 8, r.y + 6, label, fRowB, Level.HEAD)
                val t = table
                val line = if (t == null) "three tables · " + HoldemRules.Table.entries
                    .joinToString(" · ") { it.label }
                else t.view().let { v ->
                    "hand ${v.handNo + 1} · blinds ${Money.fmt(v.sb)}/${Money.fmt(v.bb)} · " +
                        "${v.activeSeats.size} left"
                }
                Draw.fit(g, tx, r.x + 8, r.y + 36, line, Level.DIM, fLens, r.w - 16)
            }
            else -> {
                tx.draw(g, r.x + 8, r.y + 6, label, fRowB, Level.HEAD)
                val line = when (row) {
                    GRow.Standings -> standRows().take(3)
                        .joinToString(" · ") { "${dn(it.name, fLens)} ${Money.compact(worthOf(it))}" }
                    else -> "font, size, confirm, bot pace, notifications"
                }
                Draw.fit(g, tx, r.x + 8, r.y + 36, line, Level.DIM, fLens, r.w - 16)
            }
        }
    }

    private fun commitGames(i: Int) {
        when (gamesRows().getOrNull(i)) {
            GRow.Holdem -> {
                if (table != null) { level = Level_.TABLE; inspect = -1; pump() }
                else { level = Level_.TABLES; tablesModel.cursor = 0 }
            }
            GRow.Standings -> { standFrom = Level_.GAMES; level = Level_.STANDINGS; standModel.cursor = 0 }
            GRow.BankrollRow -> { level = Level_.BANKROLL; bankDoc.topLine = 0; bankCache = null }
            // §16.1: the row promises the GAMES category, so it deep-links to
            // it. Landing on whatever category Settings was last left in is
            // the same surprise the activation rule exists to remove.
            GRow.SettingsRow ->
                if (services?.openWindow("settings", "cat:Games") != true)
                    setNotice("Settings is not available here")
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
        val entry = entryFor(spec)
        // §5.2: Unlimited takes ANY entry, so its row says the RULE and the
        // chooser asks the amount — printing the last amount you happened to
        // pick reads as a fixed price it does not have
        val detail = if (spec.entry == null) "any + 5%"
        else "${Money.fmt(entry)} + ${Money.fmt(HoldemRules.fee(entry))}"
        val afford = spec.entry == null || bankroll.cash >= entry + HoldemRules.fee(entry)
        Draw.fit(g, tx, r.x + 8, r.y + 5, spec.label, lv, fRow,
            r.w - 16 - tx.measure(detail, fSmall) - 16)
        Draw.right(g, tx, r.right - 8, r.y + 8, detail,
            if (!afford) Level.FAINT else if (dim) Level.REST else Level.DIM, fSmall)
    }

    private fun paintTableLens(g: Gray8, r: Rect, i: Int) {
        val spec = tableRows().getOrNull(i) ?: return
        tx.draw(g, r.x + 8, r.y + LENS_1, spec.label, fLensHead, Level.HEAD)
        val entry = entryFor(spec)
        val fee = HoldemRules.fee(entry)
        // 🔴 verdict 24: a VISIBLE fee, on the buy-in row, and it applies to
        // Adam too
        val line1 = (if (spec.entry == null) "any entry + 5% fee · blinds "
        else "${Money.fmt(entry)} + ${Money.fmt(fee)} fee · blinds ") +
            "${Money.fmt(spec.sbAt(0))}/${Money.fmt(spec.bbAt(0))}"
        Draw.fit(g, tx, r.x + 8, r.y + LENS_2, line1, Level.BODY, fLensBody, r.w - 16)
        val ladder = "up every ${HoldemRules.HANDS_PER_LEVEL} hands: ${spec.ladder()}"
        if (lensThirdFits()) {
            Draw.fit(g, tx, r.x + 8, r.y + LENS_3, ladder, Level.DIM, fLensTail, r.w - 16)
        }
    }

    /** The third lens line is drawn only when the second cannot reach it. A
     *  per-app font scale can push the ladder past its own spacing, and a line
     *  drawn through the one above it is worse than a line not drawn. */
    private fun lensThirdFits(): Boolean = LENS_2 + ink(fLensBody) <= LENS_3

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
        roster.ensurePopulation()
        val seated = roster.seat(spec, HoldemRules.MAX_SEATS - 1, key = roster.gameNo.toLong(),
            exclude = setOf(ME))
        if (seated.size < 1) {
            // nothing was spent: the fee is recorded only once a seat is real
            bankroll.add(entry + fee)
            setNotice("nobody in the room can afford that table")
            return
        }
        bankroll.payFee(fee)
        bankroll.tournamentsPlayed++
        // Adam takes a seat among them, at a position the world chooses
        val seedRng = Rng.stream(roster.worldSeed, 0x5EA7, roster.gameNo.toLong())
        mySeat = seedRng.nextInt(seated.size + 1)
        val occupants = ArrayList<Seats.Occupant>(seated.size + 1)
        val stacks = ArrayList<Int>(seated.size + 1)
        cast.clear()
        castStake.clear()
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
                castStake[i] = s.stake + s.fee
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
        syncMe()
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
            acting = acting, leaving = cashOutPending))
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

    /**
     * §10.1's SKIP: get on with it. It does not run the bots on the shell loop
     * — a Monte-Carlo decision is ~25 ms and a whole street of them would
     * freeze the panel for most of a second. It clears the PACE instead, so
     * every decision still computes off-loop and applies in the same order,
     * and the hand plays out exactly as watching it would have.
     */
    private fun skipToMe() {
        if (table == null) return
        skipping = true
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
        if (!dealAnim) revealed = v.board.size
        if (v.result != null) {
            skipping = false
            // a showdown STAYS UP until you act (verdict 29). Reveal the rest
            // of the board first so the finale is not one flat jump.
            if (revealed < v.board.size) { schedule(t, v, null); return }
            maybeBackground()
            return
        }
        val seat = v.toAct ?: return
        if (seat == mySeat) {
            // your turn: the "who just acted" highlight belongs to the pacer,
            // and leaving it lit marks a folded seat as live under your own
            // decision (the first table render, 2026-09-04)
            acting = null
            skipping = false
            revealed = v.board.size
            return
        }
        if (revealed < v.board.size) { schedule(t, v, null); return }
        schedule(t, v, seat)
    }

    /**
     * Hand ONE step to the background: either a board card to turn over or one
     * seat's decision.
     *
     * 🔴 Everything the coroutine needs is captured HERE, on the loop.
     * `revealed`, `cast`, `mySeat` and the pace are loop-owned fields; reading
     * them from the decision thread is the same reader race `WINDOWS.md` §5
     * puts first, running the other way.
     */
    private fun schedule(t: HoldemTable, v: HoldemTable.View, seat: Int?) {
        val gen = ++pacerGen
        thinking = true
        val character = seat?.let { cast[it] }
        val read = character?.let { readOf(it) } ?: HoldemBot.NO_READ
        val revealing = seat == null
        // a board card is not a decision: five of them at the full bot pace
        // is three seconds of waiting a hand. The flop still arrives card by
        // card, at a third of the pace, which is about one action in total.
        val pace = when {
            skipping -> 0L
            revealing -> if (paceMs == 0L) 0L else maxOf(REVEAL_MIN_MS, paceMs / 3)
            else -> paceMs
        }
        if (seat != null && character == null) {
            thinking = false
            setNotice("seat $seat has nobody to play it — the hand is paused")
            Log.e("games", "seat $seat has no character; the table cannot continue")
            return
        }
        bg.launch(Dispatchers.Default) {
            val decision = if (revealing || character == null) null else try {
                HoldemBot.decide(t, seat!!, character, Equity.LIVE_ROLLOUTS, read)
            } catch (e: Exception) {
                Log.e("games", "seat $seat could not decide", e)
                null
            }
            if (pace > 0) delay(pace)
            onShell {
                thinking = false
                if (gen != pacerGen || table !== t || !active || level != Level_.TABLE) return@onShell
                if (revealing) {
                    revealed = (revealed + 1).coerceAtMost(t.view().board.size)
                    services?.requestRender(this@GamesWindow)
                    pump()
                    return@onShell
                }
                if (decision == null) {
                    setNotice("a seat could not act — the hand is paused")
                    return@onShell
                }
                acting = seat
                if (!applyDecision(t, seat!!, decision)) return@onShell
                revealed = revealed.coerceAtMost(t.view().board.size)
                if (!dealAnim) revealed = t.view().board.size
                services?.requestRender(this@GamesWindow)
                pump()
            }
        }
    }

    /** §7.6: what THIS character has actually seen Adam do. No read until
     *  they have sat through enough hands for the number to mean anything —
     *  a read off twelve hands is noise wearing a stat's clothes. */
    private fun readOf(c: Character): Double =
        if (c.career.handsVsYou >= READ_HANDS) c.career.vpip else HoldemBot.NO_READ

    /** Apply a bot's decision on the loop. A refusal is LOUD and pauses the
     *  hand rather than propagating out of the pacer's completion. */
    private fun applyDecision(t: HoldemTable, seat: Int, d: HoldemBot.Decision): Boolean {
        val before = t.view()
        try {
            recordAgainstYou(before, seat, d)
            when (d.kind) {
                ActionLevel.Kind.BET, ActionLevel.Kind.RAISE, ActionLevel.Kind.ALL_IN -> t.act(d.kind, d.to)
                else -> t.act(d.kind)
            }
        } catch (e: Exception) {
            Log.e("games", "seat $seat's action was refused by the rules", e)
            setNotice("a seat could not act — the hand is paused")
            return false
        }
        afterAction(t)
        return true
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
        syncMe()
        charCache = null
        bankCache = null
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
        // a pending cash-out fires HERE, before the next hand is dealt: the
        // engine settles the finished hand as part of leaving, and dealing
        // first would post a blind for a seat that is on its way out
        if (cashOutPending) { doCashOut(t); return }
        val stillIn = t.inPlay(mySeat) && t.stackOf(mySeat) > 0
        val running = try { t.nextHand() } catch (e: Exception) {
            Log.e("games", "the hand would not settle", e)
            setNotice("the hand would not settle")
            return
        }
        revealed = 0
        inspect = -1
        acting = null
        if (!running) { cashOutPending = false; finishTournament(t, HashMap(cast), mySeat); return }
        if (!stillIn || !t.inPlay(mySeat)) { playOutWithoutMe(t); return }
        maybeBackground()
        services?.requestRender(this)
        pump()
    }

    /**
     * Adam is out but the table is not: verdict 11 — **the remaining
     * characters play the tournament out**, which is what keeps the economy
     * conserved and lands the winner's cashflow where it belongs (verdict 23
     * depends on it).
     *
     * 🔴 **ON THE LOOP, deliberately.** MEASURED: `--games-check` runs a
     * WHOLE 6-seat tournament from scratch in 13 ms at [Equity.CHEAP_ROLLOUTS],
     * so a play-out from mid-tournament is a few milliseconds — less than the
     * 16–80 ms `maybeBackground` already spends on this same loop, and far
     * less than one frame costs to push.
     *
     * An earlier version handed it to a background coroutine on the belief
     * that it took "seconds". It does not, and the coroutine opened two real
     * defects (review pass 5, 2026-09-04): sitting down at a NEW table inside
     * the hand-off window had its `cast` and stakes cleared out from under it
     * by the OLD table's settlement, and a shell restart inside that window
     * lost the whole prize pool — the seats' buy-ins had already left their
     * bankrolls and nothing was left to pay them from.
     */
    private fun playOutWithoutMe(t: HoldemTable) {
        pacerGen++
        acting = null
        skipping = false
        val field = HashMap(cast)
        val seat = mySeat
        table = null
        if (level == Level_.TABLE || level == Level_.HISTORY) level = Level_.GAMES
        try {
            Background.playOut(t, field, Equity.CHEAP_ROLLOUTS)
        } catch (e: Exception) {
            Log.e("games", "the table could not be played out", e)
        }
        finishTournament(t, field, seat)
    }

    /**
     * The tournament is over. **Chips are dollars 1:1 and a sit-and-go is
     * conserved**, so the settlement is simply: whoever holds the chips takes
     * them home. A cash-out has already moved its own chips off the table
     * (verdict 11), so what is left is exactly the prize.
     */
    private fun finishTournament(t: HoldemTable, field: Map<Int, Character>, seat: Int) {
        val v = t.view()
        val prize = v.seats.sumOf { it.stack }
        // 🔴 A PLACE FOR EVERY SEAT, never "unfinished means first". Normally
        // exactly one seat has no finishing order — the winner — but a table
        // that stopped early (a `playOut` that reported a stall) leaves
        // several, and `finishPlace(s) ?: 1` then handed EACH of them first
        // place and the whole prize with it: money printed and careers
        // corrupted on an error path (review pass 3, 2026-09-04). Ranking the
        // survivors by chips gives one winner in both cases, so the prize
        // moves exactly once and the economy stays conserved.
        val placeOf = placesFor(t, v)
        val winner = placeOf.entries.firstOrNull { it.value == 1 }?.key
        val myPlace = placeOf[seat] ?: 1
        if (winner == seat) {
            bankroll.add(prize)
            bankroll.tournamentsWon++
            if (notifyWin) services?.notifyInternal("games",
                "you won the ${t.spec.label} table · ${Money.fmt(prize)}",
                appId = id, thread = "won")
        } else if (winner != null) {
            field[winner]?.let { it.bankroll += prize }
                ?: Log.e("games", "seat $winner won ${Money.fmt(prize)} with nobody to pay it to")
        }
        // careers and mood for the whole field, then the roster's own lives
        // machinery — the same settlement a background game runs (§7.5)
        val fieldSize = v.seats.size
        for ((s, c) in field) {
            val place = placeOf[s] ?: 1
            c.career.tournaments++
            c.career.finishSum += place
            if (place == 1) c.career.wins++
            // the NET, not the gross: what came back minus what went in at the
            // door, exactly as `Background.settle` records it. Passing the
            // gross prize for a win and 0 for a loss made every bot who ever
            // sat with Adam show a rising lifetime net (review pass 3).
            Mood.afterTournament(c, place, fieldSize,
                (if (place == 1) prize else 0) - (castStake[s] ?: 0))
        }
        val meC = me()
        meC.career.tournaments++
        meC.career.finishSum += myPlace
        if (myPlace == 1) meC.career.wins++
        meC.career.lifetimeNet += (if (winner == seat) prize else 0) - myStake
        roster.gameNo++
        for (c in field.values) roster.settleBroke(c)
        roster.tick()
        roster.ensurePopulation()
        Log.i("games", "the ${t.spec.label} table is done: " +
            "${if (winner == seat) "you" else field[winner]?.name ?: "?"} took ${Money.fmt(prize)}")
        // only the CURRENT table's state is reset. A settlement that arrives
        // for a table Adam has already left behind must not empty the cast of
        // the one he is sitting at (review pass 5, 2026-09-04).
        val wasCurrent = table == null || table === t
        if (table === t) table = null
        if (wasCurrent) {
            cast.clear()
            castStake.clear()
            myStake = 0
            lastSettledHand = -1
            cashOutPending = false
            skipping = false
            acting = null
            if (level == Level_.TABLE || level == Level_.HISTORY) level = Level_.GAMES
        }
        syncMe()
        charCache = null
        bankCache = null
        setNotice(if (winner == seat) "you win ${Money.fmt(prize)}" else "you finished ${ordinal(myPlace)}")
        offerRefillIfBroke()
        services?.requestRender(this)
    }

    /**
     * A finishing place for EVERY seat. Seats that busted or cashed out carry
     * their own order; whoever is still in is ranked by chips, so there is
     * exactly one first place whether or not the table actually resolved.
     */
    private fun placesFor(t: HoldemTable, v: HoldemTable.View): Map<Int, Int> {
        val out = HashMap<Int, Int>(v.seats.size)
        val standing = v.seats.indices.filter { t.inPlay(it) }
            .sortedByDescending { v.seats[it].stack }
        for ((i, s) in standing.withIndex()) out[s] = i + 1
        for (i in v.seats.indices) t.finishPlace(i)?.let { out[i] = it }
        if (standing.size > 1) Log.e("games",
            "the ${t.spec.label} table settled with ${standing.size} seats still in — " +
                "ranking them by chips so the prize moves exactly once")
        return out
    }

    /**
     * Verdict 25: Adam is a roster character, so the standings must show the
     * money he actually has — the bankroll plus whatever is in front of him.
     * It is DERIVED at read time rather than stored: his stack moves every
     * hand, and a copy would be stale between them.
     */
    private fun worthOf(c: Character): Int =
        if (c.id == ME) bankroll.cash + (table?.stackOf(mySeat) ?: 0) else c.worth

    /** The persisted copy, for peers and for the record — kept in step at
     *  every point the number actually changes. */
    private fun syncMe() {
        me().bankroll = worthOf(me())
    }

    private fun ordinal(n: Int): String = when (n) {
        1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${n}th"
    }

    /**
     * §7.5's ratio, spent between Adam's own hands.
     *
     * 🔴 It runs ON THE SHELL LOOP, deliberately. A background tournament
     * structurally mutates the roster — new births, characters moving between
     * lives — and doing that on a background thread while the loop paints the
     * standings or reads a summary is a `ConcurrentModificationException`
     * inside a paint, which is the L1 class this project has met before.
     * Measured cost: 16–80 ms, once every [BG_EVERY] hands of Adam's. The
     * design's "the CPU spends none" is about WALL CLOCK — he is thinking —
     * and a frame here already costs 100–140 ms.
     *
     * Characters seated at Adam's table are excluded: seating one twice would
     * debit a second buy-in from a bankroll already committed.
     */
    private fun maybeBackground() {
        val t = table ?: return
        if (bgOwed <= 0 || thinking) return
        val v = t.view()
        if (v.handNo - bgLastHand < BG_EVERY) return
        bgLastHand = v.handNo
        bgOwed--
        val busy = cast.values.map { it.id }.toSet() + ME
        val before = roster.characters.filter { it.state != Character.State.PLAYING }.map { it.id }.toSet()
        val s = try {
            Background.playTournament(roster, Equity.CHEAP_ROLLOUTS, busy)
        } catch (e: Exception) {
            Log.e("games", "a background tournament failed", e)
            null
        } ?: return
        Log.i("games", "background: ${s.spec.label}, ${s.hands} hands, ${s.winner} won " +
            "${Money.fmt(s.prize)}")
        if (notifyReturn) {
            for (c in roster.characters) {
                if (c.state == Character.State.PLAYING && c.id in before) {
                    services?.notifyInternal("games", "${dn(c.name, fSmall)} is back at the tables",
                        appId = id, thread = "return:${c.id}", target = "char:${c.id}")
                }
            }
        }
        charCache = null
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
            // "Bet →" as §10.2 writes it. Proven drawable: `tools/lint.py
            // --codepoint →` reports U+2192 present in all four locked faces,
            // and SYM002 keeps it that way.
            ActionLevel.Kind.BET, ActionLevel.Kind.RAISE -> add(a.label + " →", "sizes") { openSizing() }
            else -> add(a.label, a.detail) { stage(a) }
        }
        add("Cash out", "and leave") { confirmCashOut() }
        add("Standings", "${roster.characters.size}") {
            standFrom = Level_.TABLE
            level = Level_.STANDINGS
            standModel.cursor = 0
        }
        add("Hand history", "${v.history.size} lines") { level = Level_.HISTORY; histDoc.topLine = 0 }
        // the floating menu covers the middle of the table (§9.3), so the two
        // numbers a decision needs ride in its TITLE — found by looking at the
        // live screen with the menu up
        if (services?.openMenu(MenuSurface.Spec(spotLine(v), items,
                onCommit = { i -> acts.getOrNull(i)?.invoke() }), owner = this) != true) {
            setNotice("could not open the action list")
        }
    }

    /** Your hand and the two numbers a decision needs, for a menu title that
     *  is sitting on top of them. */
    private fun spotLine(v: HoldemTable.View): String {
        val me = v.seats.getOrNull(mySeat)
        val hand = me?.cards?.joinToString(" ") { it.code } ?: ""
        val toCall = if (me == null) 0 else maxOf(0, v.currentBet - me.committed)
        return listOf(hand, "pot ${Money.compact(v.pot)}",
            if (toCall > 0) "call ${Money.compact(toCall)}" else "check")
            .filter { it.isNotEmpty() }.joinToString(" · ")
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
        if (services?.openMenu(MenuSurface.Spec(spotLine(t.view()), items,
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
                    else -> {
                        // the verb follows the BET on the table, in the label
                        // as well as the kind: the confirm's title carries the
                        // label, and "Raise" over a check-through read wrong
                        val raising = t.view().currentBet > 0
                        stage(ActionLevel.Action(
                            if (v >= max) ActionLevel.Kind.ALL_IN
                            else if (raising) ActionLevel.Kind.RAISE else ActionLevel.Kind.BET,
                            if (v >= max) "All-in" else if (raising) "Raise" else "Bet",
                            Money.fmt(v), v))
                    }
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
        val ok = services?.openMenu(MenuSurface.Spec(
            table?.let { "${a.label} · ${spotLine(it.view())}" } ?: a.label,
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

    /**
     * 🔴 Leaving a tournament is an explicit menu row with a confirm — never a
     * double-tap (§10.1).
     *
     * The action level is the only place this row lives (§10.2), and that
     * level is only open ON YOUR TURN — mid-hand. You cannot take chips off a
     * table while your hand is live, so the honest act is the one a player
     * makes: **fold, and leave when the hand is over.** The first live test of
     * this build found the row refusing itself every time, which made verdict
     * 11's "you can leave early and cash out" unreachable in practice.
     */
    private fun confirmCashOut() {
        val t = table ?: return
        val v = t.view()
        // "live" is exactly the case that has to be folded first — which is
        // any hand that is not settled and that you have not already folded
        val live = v.result == null && !v.seats[mySeat].folded
        val chips = t.stackOf(mySeat)
        val ok = services?.openMenu(MenuSurface.Spec(
            if (live) "Fold and cash out ${Money.fmt(chips)}?" else "Cash out ${Money.fmt(chips)}?",
            listOf(MenuSurface.Item("Cancel"),
                MenuSurface.Item(if (live) "Fold and leave" else "Cash out",
                    if (live) "after this hand" else "no re-entry · the table plays on")),
            onCommit = { i -> if (i == 1) requestCashOut() }), owner = this) == true
        if (!ok) setNotice("could not open the confirm")
    }

    private fun requestCashOut() {
        val t = table ?: return
        val v = t.view()
        // a SETTLED hand: the chips are already yours, take them now
        if (v.result != null) { doCashOut(t); return }
        // 🔴 every other case is a LIVE hand and is folded first — including a
        // hand nothing has been put into yet. `contributed == 0` is the
        // commonest spot at the table (first to act, preflop, out of the
        // blinds) and an earlier version short-circuited on it straight into
        // `cashOut`, which refuses a live hand: the row confirmed and then
        // printed an error, which is the same unreachable-row defect the first
        // live session found one branch over (review pass 3, 2026-09-04).
        if (!v.seats[mySeat].folded) {
            if (v.toAct != mySeat) {
                setNotice("wait for your turn to fold, then you can leave")
                return
            }
            try {
                t.act(ActionLevel.Kind.FOLD)
            } catch (e: Exception) {
                Log.e("games", "the fold before a cash-out was refused", e)
                setNotice(e.message ?: "could not fold")
                return
            }
            afterAction(t)
        }
        cashOutPending = true
        acting = null
        setNotice("folded — cashing out when the hand ends")
        services?.requestRender(this)
        pump()
    }

    /** Take the chips and hand the table over (verdict 11 §7.5: it plays on). */
    private fun doCashOut(t: HoldemTable) {
        cashOutPending = false
        val chips = try { t.cashOut(mySeat) } catch (e: Exception) {
            Log.e("games", "the cash-out was refused", e)
            setNotice(e.message ?: "cannot cash out mid-hand")
            return
        }
        bankroll.add(chips)
        bankCache = null
        playOutWithoutMe(t)
        syncMe()
        // last, so it survives the settlement's own outcome line: what he
        // asked for is what the panel should say
        setNotice("cashed out ${Money.fmt(chips)}")
    }

    // ================================================================ standings
    private fun standRows(): List<Character> = roster.standings(::worthOf)

    private fun paintStandRow(g: Gray8, i: Int, r: Rect, dim: Boolean) {
        val c = standRows().getOrNull(i) ?: return
        val lv = if (dim) Level.REST else Level.BODY
        val mine = c.id == ME
        val f = if (mine) fRowB else fRow
        val mark = when (c.state) {
            Character.State.PLAYING -> ""
            Character.State.BETWEEN_LIVES -> " · away"
            Character.State.RETIRED -> " · retired"
        }
        val detail = Money.compact(worthOf(c)) + mark
        Draw.fit(g, tx, r.x + 8, r.y + 5, dn(c.name, f), if (mine && !dim) Level.HEAD else lv,
            f, r.w - 16 - tx.measure(detail, fSmall) - 16)
        Draw.right(g, tx, r.right - 8, r.y + 8, detail,
            if (dim) Level.REST else Level.DIM, fSmall)
    }

    private fun paintStandLens(g: Gray8, r: Rect, i: Int) {
        val c = standRows().getOrNull(i) ?: return
        val bits = ArrayList<String>()
        bits.add(Money.fmt(worthOf(c)))
        if (c.career.tournaments > 0) {
            bits.add("${c.career.wins}/${c.career.tournaments} won")
            bits.add("avg ${"%.1f".format(c.career.avgFinish)}")
        }
        if (c.id != ME) bits.add("${c.livesLeft}/${c.livesTotal} lives")
        val h2h = if (c.career.handsVsYou > 0)
            "${c.career.handsVsYou} hands with you · vpip ${(c.career.vpip * 100).toInt()}%"
        else "you have not played them"
        tx.draw(g, r.x + 8, r.y + LENS_1, dn(c.name, fLensHead), fLensHead, Level.HEAD)
        Draw.fit(g, tx, r.x + 8, r.y + LENS_2, bits.joinToString(" · "), Level.BODY, fLensBody, r.w - 16)
        if (lensThirdFits()) {
            Draw.fit(g, tx, r.x + 8, r.y + LENS_3, if (c.id == ME) "that's you" else h2h,
                Level.DIM, fLensTail, r.w - 16)
        }
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
        line("net worth ${Money.fmt(worthOf(c))}")
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
                line("they knocked you out ${times(c.career.knockedYouOut)}")
                line("you knocked them out ${times(c.career.youKnockedOut)}")
            }
        }
        charCache = out
        return out
    }

    private fun times(n: Int): String = if (n == 1) "once" else "$n times"

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
        // "puts you back to" reads as a top-up; it SETS the cash, and above
        // the base that takes money away (review pass 5, 2026-09-04)
        line("Refill sets the cash to ${Money.fmt(Bankroll.BASE)}", fDoc, Level.DIM)
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
        // both halves must fit the 248 px box — the detail is capped at half of
        // it, and "Refill to $1,000 / Loser Count 1 -> 2" elided BOTH (first
        // live session, 2026-09-04). The amount rides the detail, once.
        items.add(MenuSurface.Item("Refill",
            "${Money.fmt(Bankroll.BASE)} · count ${bankroll.loserCount + 1}"))
        acts.add { confirmRefill() }
        if (services?.openMenu(MenuSurface.Spec("bankroll", items,
                onCommit = { i -> acts.getOrNull(i)?.invoke() }), owner = this) != true) {
            setNotice("could not open the bankroll menu")
        }
    }

    private fun confirmRefill() {
        // 🔴 a refill SETS the cash to the base, so above it the money goes
        // DOWN. "Refill to $1,000?" over $3,400 reads like a top-up and is
        // not one (review pass 3, 2026-09-04) — the confirm says which way.
        val over = bankroll.cash - Bankroll.BASE
        val title = if (over > 0) "Set ${Money.fmt(bankroll.cash)} back to ${Money.fmt(Bankroll.BASE)}?"
        else "Refill to ${Money.fmt(Bankroll.BASE)}?"
        val detail = if (over > 0) "you LOSE ${Money.fmt(over)} · Loser Count +1"
        else "Loser Count +1"
        val ok = services?.openMenu(MenuSurface.Spec(title,
            listOf(MenuSurface.Item("Cancel"), MenuSurface.Item("Refill", detail)),
            onCommit = { i -> if (i == 1) doRefill() }), owner = this) == true
        if (!ok) setNotice("could not open the confirm")
    }

    private fun doRefill() {
        val over = bankroll.cash - Bankroll.BASE
        bankroll.refill()
        syncMe()
        bankCache = null
        setNotice(if (over > 0) "set back to ${Money.fmt(Bankroll.BASE)} · Loser Count ${bankroll.loserCount}"
        else "refilled · Loser Count ${bankroll.loserCount}")
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
        // the doc rhythm is one fSmall line; a taller header needs the spacer
        // or the first entry crowds it (first live session, 2026-09-04)
        out.add(DocLine("", fSmall, Level.DIM))
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
        putJsonObject("castStake") { for ((k, v) in castStake) put(k.toString(), v) }
        put("revealed", revealed)
        put("unlimitedStake", unlimitedStake)
        put("bgOwed", bgOwed)
        put("cashOutPending", cashOutPending)
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
        castStake.clear()
        // tolerant on purpose: one malformed entry here must not take the
        // whole main record down with it (the shell drops a record whose
        // restore throws, and this one carries the level, the seat and every
        // Settings value)
        (state["castStake"] as? JsonObject)?.forEach { (k, v) ->
            val seat = k.toIntOrNull() ?: return@forEach
            castStake[seat] = (v as? JsonPrimitive)?.intOrNull ?: 0
        }
        revealed = (state["revealed"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
        unlimitedStake = (state["unlimitedStake"]?.jsonPrimitive?.intOrNull ?: 1_000).coerceAtLeast(1)
        bgOwed = (state["bgOwed"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, 8)
        cashOutPending = state["cashOutPending"]?.jsonPrimitive?.booleanOrNull ?: false
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
        skipping = false
        // a decision in flight when the shell stopped never gets its
        // `runOnShell` back, and a latched `thinking` would leave the table
        // frozen for good. A restore is a new session: clear it.
        thinking = false
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
        /** The three-line lens ladder, in pixels from the lens top. Measured:
         *  17 bold inks 25 px, 13 inks 20 and 11 inks 17, so 2 / 28 / 48 sets
         *  every ascent inside the 64 px box and only empty descent tails
         *  cross the bottom rule. */
        const val LENS_1 = 2
        const val LENS_2 = 28
        const val LENS_3 = 48
        /** Adam's own character id (verdict 25: he is a roster character). */
        const val ME = "you"

        /** §7.5's ratio in practice: one background tournament every N hands
         *  of Adam's, which over a 60–120-hand sit-and-go lands in the 2–3
         *  band verdict 26 asks for. */
        const val BG_EVERY = 22

        /** Hands together before a character trusts their read (§7.6). */
        const val READ_HANDS = HoldemView.READ_HANDS

        /** The floor on a board-card reveal, so "instant" stays instant and
         *  300 ms does not become 100. */
        const val REVEAL_MIN_MS = 80L

        val PACES = linkedMapOf(
            "instant" to 0L, "300 ms" to 300L, "600 ms" to 600L,
            "1 s" to 1_000L, "1.5 s" to 1_500L,
        )
    }
}
