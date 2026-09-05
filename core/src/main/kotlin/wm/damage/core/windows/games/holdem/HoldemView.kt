package wm.damage.core.windows.games.holdem

import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Level
import wm.damage.core.shell.Draw
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.windows.games.kit.CardArt
import wm.damage.core.windows.games.kit.HandFan
import wm.damage.core.windows.games.kit.Money
import wm.damage.core.windows.games.kit.Seats
import wm.damage.core.windows.games.kit.TableLayout
import wm.damage.core.windows.games.roster.Character

/**
 * The table, painted (`HOLDEM.md` §9.2) — a `CanvasView`, so this window owns
 * its own damage.
 *
 * 🔴 **Extra height buys INFORMATION DENSITY, never bigger cards** (verdict 9).
 * The card rung comes from the ladder; what grows with the panel is the seat
 * band, the number of bands, and what each seat cell says.
 *
 * Ink is the design's real budget here (§9.2 models ≈8.1 % at 288 and ≈10.0 %
 * at 480). The two things that keep it there: **black-suit cards are outlines**
 * (verdict 7), and opponents' holdings are a **small mark** rather than drawn
 * card backs below the top rung — five seats × two backs is ten lit rectangles
 * carrying no information.
 */
class HoldemView(private val tx: TextRasterizer) {

    /** Everything the paint needs. Assembled on the loop by the window; this
     *  class reads and never reaches back. */
    class Model(
        val v: HoldemTable.View,
        val spec: HoldemRules.Table,
        val mySeat: Int,
        /** How many board cards have been turned face-up so far — the paced
         *  reveal, so an all-in run-out is not one flat jump to five. */
        val revealed: Int,
        val cast: Map<Int, Character>,
        /** The seat-inspect cursor (§10.1), or −1. */
        val cursor: Int,
        val showStats: Boolean,
        val archetypes: Boolean,
        val handsToLevel: Int,
        /** A one-shot line on the status band (an error, a notice). */
        val note: String = "",
        /** The seat the pacer is currently showing acting, or null. */
        val acting: Int? = null,
        /** You have asked to cash out and the hand is playing itself out —
         *  the next tap takes the chips and hands the table over (§10.2). */
        val leaving: Boolean = false,
    )

    /** A drawn chip's width in a seat cell. */
    private val CHIP_W = 9

    // the SEAT strip's faces are measured per cell — see [seatFaces]; these
    // are the shared ones the rest of the table draws with
    private val fSmall = FontSpec(Face.SYSTEM, 12)
    private val fTiny = FontSpec(Face.SYSTEM, 11)
    private val fStatus = FontSpec(Face.SYSTEM, 16)
    private val fStatusBig = FontSpec(Face.SYSTEM, 17, bold = true)

    /** The last layout painted — the window asks it for the depth region. */
    var layout: TableLayout? = null
        private set

    /** A face's drawn extent: ascent + descent. */
    private fun ink(f: FontSpec) = tx.metrics(f).let { it.ascent + it.descent }

    private fun evenUp(v: Int) = (v + 1) / 2 * 2

    fun paint(g: Gray8, rect: Rect, m: Model) {
        // the status band and your line are sized from the MEASURED ink of
        // the faces they carry (review §28 #9): under a per-app font scale
        // the 24 px design band held a 33 px status line, whose lower third
        // fell into the hole-card plane and was drawn shifted — a cut through
        // the text on every frame. MEASURED at 100 %: Clear Sans 17 bold inks
        // 25 rows, so the band is 30 there (the design's 24 held that line
        // only by lending its descent to the gap below; the seat strip gives
        // up the six rows), and your line's 12 px face inks 19, so that band
        // stays the design's 24.
        val t = TableLayout(rect, rect.h + 64,
            statusH = evenUp(ink(fStatusBig) + 4), lineH = evenUp(ink(fSmall) + 4))
        layout = t
        g.fillRect(rect, Level.BG)
        paintSeats(g, t, m)
        paintBoard(g, t, m)
        paintStatus(g, t, m)
        paintHole(g, t, m)
        if (t.showsYourLine) paintYourLine(g, t, m)
        if (t.showsHistory) paintHistory(g, t, m)
    }

    /** §9.2: **your hole cards come forward to plane 0**, the table sits at the
     *  content plane. Grid-legal by construction and clamped into the content
     *  area — the shell refuses anything else loudly. */
    fun holePlane(content: Rect): Rect? {
        val t = layout ?: return null
        val r = Rect(content.x, Geometry.snapY(t.hole.y - 2), content.w,
            Geometry.snapY(t.hole.h + 4))
        return r.intersect(content)
    }

    // ------------------------------------------------------------------ seats
    /** The four faces a seat cell draws, after the fit below. */
    private class SeatFaces(val name: FontSpec, val stack: FontSpec, val small: FontSpec, val tiny: FontSpec)

    /** Whether the strip has already SAID it could not fit — said once per
     *  run, not once per frame. */
    private var seatSqueezeSaid = false

    /**
     * 🔴 The seat strip is what the allocator has LEFT OVER, so it is the
     * band a bigger face breaks first. MEASURED on the live table at the 288
     * rung with the global scale at 130 %: the status band grows to 38 and
     * leaves the strip a 120x34 cell, while the 15 px name and the 14 px
     * stack want 2 + 23 + 27 = 52 rows there — so every opponent's stack was
     * drawn straight through the top edge of the board's card slots, ink
     * outside the rect its own allocator handed out, the standing §27 defect
     * ("a rect a paint returns is a promise").
     *
     * The card art does NOT scale with the face (verdict 9) and the 288 rung
     * is already the smallest, so there is no room to buy from the two card
     * rows: the seat FACES step down instead, until the two rows the cell
     * draws fit inside it. This is the measured cap the chrome already takes
     * (`Shell.chromeScale`). At 100 % nothing steps: the design's 15/14 want
     * 41 rows and the ladder gives the cell 42, so that render is untouched.
     *
     * Note the 17 px floor in [seatTwoLines] is the DESIGN's pitch and does
     * not shrink with a stepped face, so the two-row form gives out while the
     * faces still have steps left. That is deliberate: one row at a face you
     * can read beats two rows at one you cannot.
     */
    private fun seatFaces(cell: Rect, seats: List<Seats.Seat>): SeatFaces {
        for (k in 0..SEAT_STEPS) {
            val f = seatFacesAt(k)
            if (seatTwoLines(f) <= cell.h) return f
        }
        // Two rows do not fit at any face: the strip goes COMPACT, one row per
        // seat. The face is then bound by the cell's WIDTH, not its height —
        // measured against the names actually at the table and the money
        // beside them. Picked by height alone the 15 px face fitted, and every
        // name on the glass read "Ma ▸ $200": the money survived and the WHO
        // did not (review §30, the live walk's own second look).
        val f = (0..SEAT_STEPS).map { seatFacesAt(it) }.firstOrNull { c ->
            ink(c.name) + 2 <= cell.h && seats.all { s -> compactWidth(c, s) <= cell.w - 12 }
        } ?: seatFacesAt(SEAT_STEPS)
        if (!seatSqueezeSaid) {
            seatSqueezeSaid = true
            wm.damage.core.util.Log.w("games", "a ${cell.w}x${cell.h} seat cell cannot hold two " +
                "rows even at the smallest seat face — the strip goes COMPACT: the name and the " +
                "money share one line at ${f.name.sizePx}px")
        }
        return f
    }

    /** What one COMPACT seat row costs in width: the name, a gap, and the
     *  money. The position mark is NOT charged — it sits on two of the five
     *  seats and it moves every hand, so paying for it everywhere costs the
     *  whole strip a face step; on the two seats that have one the name is
     *  fitted with its own drawn mark and the money still never moves. */
    private fun compactWidth(f: SeatFaces, s: Seats.Seat): Int =
        tx.measure(s.who.name, f.name) + 8 +
            tx.measure(if (s.busted) "out" else Money.compact(s.stack), f.stack)

    /** One row per seat, because two do not fit. */
    private fun compact(f: SeatFaces, cellH: Int) = seatTwoLines(f) > cellH

    private fun seatFacesAt(k: Int) = SeatFaces(
        FontSpec(Face.SYSTEM, maxOf(9, 15 - k), bold = true),
        FontSpec(Face.SYSTEM, maxOf(8, 14 - k)),
        FontSpec(Face.SYSTEM, maxOf(8, 12 - k)),
        FontSpec(Face.SYSTEM, maxOf(7, 11 - k)),
    )

    /** What the name row and the stack row cost together, the same arithmetic
     *  [paintSeat] walks — one place, so the fit and the paint cannot drift. */
    private fun seatTwoLines(f: SeatFaces) = 2 + maxOf(17, ink(f.name) - 6) + ink(f.stack)

    private fun paintSeats(g: Gray8, t: TableLayout, m: Model) {
        // read the strip the way you read a table: from the seat on YOUR LEFT,
        // clockwise. Raw index order put the two blinds at opposite ends of
        // the strip on half the hands (first live session, 2026-09-04). The
        // rotation is by mySeat, which is fixed for the tournament, so every
        // opponent keeps the same cell all the way through.
        val others = Seats.strip(m.v.seats, m.mySeat)
        if (others.isEmpty()) return
        val faces = seatFaces(t.seatCell(0, others.size), others)
        for ((i, s) in others.withIndex()) {
            paintSeat(g, t.seatCell(i, others.size), t, s, m, faces)
        }
    }

    private fun paintSeat(g: Gray8, cell: Rect, t: TableLayout, s: Seats.Seat, m: Model, f: SeatFaces) {
        val fName = f.name
        val fStack = f.stack
        val fSmall = f.small
        val fTiny = f.tiny
        val gone = s.busted
        val dim = gone || s.folded
        val turn = m.v.toAct == s.index || m.acting == s.index
        val nameLv = when {
            gone -> Level.FAINT
            turn -> Level.HOT
            dim -> Level.REST
            else -> Level.HEAD
        }
        val bodyLv = if (dim) Level.REST else Level.BODY
        var y = cell.y + 2
        val x = cell.x + 6
        val w = cell.w - 12
        val compact = compact(f, cell.h)
        if (compact) y = cell.y + ((cell.h - ink(fName)) / 2).coerceAtLeast(0)

        // the inspect cursor is a bracket, not a fill — ink is opacity here
        if (m.cursor == s.index) {
            g.fillRect(cell.x + 2, cell.y, 2, cell.h, Level.MID)
            g.fillRect(cell.right - 4, cell.y, 2, cell.h, Level.MID)
        }

        // position marks: the button is a ring, the blinds are words
        var markW = 0
        if (!gone) {
            if (s.index == m.v.button) {
                g.outlineEllipse(x, y + 2, 12, 12, if (dim) Level.DIM else Level.HEAD, 2)
                markW = 18            // the same 6 px gap the "sb"/"bb" words leave
            } else if (s.index == m.v.sbSeat || s.index == m.v.bbSeat) {
                val lbl = if (s.index == m.v.sbSeat) "sb" else "bb"
                tx.draw(g, x, y + 2, lbl, fTiny, Level.DIM)
                markW = tx.measure(lbl, fTiny) + 6
            }
        }
        // the name is the cell's whole reason to exist, so the fit above is
        // what keeps it drawable; the guard is here so "never outside the
        // cell" holds without an argument about arithmetic
        if (y + ink(fName) > cell.bottom) {
            if (!seatSqueezeSaid) {
                seatSqueezeSaid = true
                wm.damage.core.util.Log.w("games", "a ${cell.h}px seat cell cannot hold even a " +
                    "name at the smallest seat face — the cell is left empty rather than painted over")
            }
            return
        }
        if (compact) {
            // 🔴 ONE row: the name, and the money right-aligned beside it.
            // Dropping the second row instead lost EVERY opponent's stack at
            // the 288 rung under a 130 % face — the whole table's money gone
            // from a poker screen (review §30, seen live). The money is placed
            // FIRST and the name fitted into what is left, so the number can
            // never be the thing that is cut. What this row does NOT carry is
            // the holding mark and the amount in front: who is still in the
            // hand is read from the dimming (folded draws REST, busted says
            // "out") and what you face is on the status band, which is where
            // "to call" has always been.
            val money = if (gone) "out" else Money.compact(s.stack)
            val moneyLv = when {
                gone -> Level.FAINT
                s.allIn -> Level.HOT
                else -> bodyLv
            }
            val mw = tx.measure(money, fStack)
            Draw.right(g, tx, cell.right - 6, y + maxOf(0, ink(fName) - ink(fStack)), money, moneyLv, fStack)
            Draw.fit(g, tx, x + markW, y, Draw.dynamic(tx, s.who.name, fName), nameLv, fName,
                (cell.right - 12 - mw) - (x + markW))
            return
        }
        Draw.fit(g, tx, x + markW, y, Draw.dynamic(tx, s.who.name, fName), nameLv, fName, w - markW)
        // the row pitches follow the MEASURED ink (review §28 #9): 17/16/15
        // are what the design faces need at 100 % — ink 23/22/19 less the
        // leading a row can lend the next — and under a per-app scale the
        // fixed pitches ran every descender into the caps of the row below
        y += maxOf(17, ink(fName) - 6)

        if (gone) {
            if (y + ink(fSmall) <= cell.bottom) tx.draw(g, x, y, "out", fSmall, Level.FAINT)
            return
        }
        // stack + what is in front of them. Both sit in the LEFT column,
        // beside the stack they came out of: right-aligned they landed in the
        // same box as the holding mark and read as the NEXT seat's money
        // (first live session, 2026-09-04).
        val stack = Money.compact(s.stack)
        // the same lens-ladder rule the third line already took, now on EVERY
        // line: a row that would run past the cell is DROPPED, never drawn
        // over the band below. [seatFaces] steps the faces down so it almost
        // never comes to this; the guard is what makes the promise true.
        if (y + ink(fStack) > cell.bottom) return
        tx.draw(g, x, y, stack, fStack, if (s.allIn) Level.HOT else bodyLv)
        if (s.committed > 0) {
            // the holding mark owns the cell's right edge — never draw into it
            val bound = if (s.cards.isNotEmpty() && !s.folded) cell.right - 26 else cell.right - 6
            val cx = x + tx.measure(stack, fStack) + 8
            val inFront = Money.compact(s.committed)
            Draw.fit(g, tx, cx, y + 2, inFront, if (dim) Level.DIM else Level.MID, fSmall, bound - cx)
            // §9.2's top rung: the drawn stack rides AFTER the amount. Set
            // BETWEEN two numbers, a two-bar stack read as an equals sign
            // (first live session, 2026-09-04). It grows upward from the row's
            // base, so it caps at three bars or it climbs into the name.
            if (t.showsChipStacks) {
                val bx = cx + tx.measure(inFront, fSmall) + 6
                if (bx + CHIP_W <= bound) {
                    Money.chipStack(g, bx, y + 14, CHIP_W, s.committed, m.v.bb,
                        if (dim) Level.REST else Level.MID, maxChips = 4)
                }
            }
        }
        y += maxOf(16, ink(fStack) - 6)

        val inspected = m.cursor == s.index
        // a line that would run past the cell's bottom is DROPPED, never
        // drawn over the band below (the lens-ladder rule, WINDOWS.md §5)
        if (t.showsLastAction && y + ink(fSmall) <= cell.bottom) {
            val shown = m.v.result?.shown ?: emptySet()
            if (inspected) {
                // inspecting a seat is a question, so it gets an answer: what
                // you have actually seen them do (§7.7). The brackets alone
                // said nothing (the first live session, 2026-09-04).
                val c = m.cast[s.index]
                val line = when {
                    c == null -> "you"
                    c.career.handsVsYou == 0 -> "no hands together"
                    c.career.handsVsYou < READ_HANDS -> hands(c.career.handsVsYou)
                    else -> "vp ${(c.career.vpip * 100).toInt()} · ag ${"%.1f".format(c.career.aggression)}"
                }
                Draw.fit(g, tx, x, y, line, Level.MID, fSmall, w)
            } else if (s.index in shown && s.cards.isNotEmpty()) {
                // the showdown: the cards themselves replace the last action
                Draw.fit(g, tx, x, y, s.cards.joinToString(" ") { it.code },
                    if (dim) Level.DIM else Level.HEAD, fSmall, w)
            } else {
                // nothing to say is drawn as nothing: a dash under every seat
                // that has not acted yet is five cells of noise (the first
                // table render, 2026-09-04)
                val act = if (s.folded) "folded" else s.lastAction
                if (act.isNotEmpty()) Draw.fit(g, tx, x, y, act, if (dim) Level.REST else Level.DIM, fSmall, w)
            }
            y += maxOf(15, ink(fSmall) - 4)
        }
        // §9.2 left "actual backs at 480" as a CONSIDERATION and the mark won
        // it at every rung: two 72x100 backs per seat is ten lit rectangles
        // carrying no information, which is the ink trap that section names.
        // It sits under the name's ink, wherever the scale puts that.
        if (!s.folded && s.cards.isNotEmpty()) {
            val my = cell.y + maxOf(20, ink(fName) - 3)
            if (my + 10 <= cell.bottom) {
                CardArt.holdingMark(g, cell.right - 22, my, 16, 10, 2, if (dim) Level.REST else Level.MID)
            }
        }
        if (t.showsObservedStats && m.showStats && y + ink(fTiny) <= cell.bottom) {
            val c = m.cast[s.index]
            // §7.7: the read is EARNED. Below the threshold there is nothing
            // honest to say, so the line stays empty rather than printing
            // "0 hands" under every seat — five cells of noise carrying no
            // information is exactly what the ink budget is for.
            val line = when {
                c == null -> ""
                c.career.handsVsYou >= READ_HANDS ->
                    "vp ${(c.career.vpip * 100).toInt()} · ag ${"%.1f".format(c.career.aggression)}"
                m.archetypes -> c.traits.archetype()
                else -> ""
            }
            if (line.isNotEmpty()) Draw.fit(g, tx, x, y, line, Level.DIM, fTiny, w)
        }
    }

    // ------------------------------------------------------------------ board
    private fun paintBoard(g: Gray8, t: TableLayout, m: Model) {
        val fan = t.boardFan(5)
        val shown = m.revealed.coerceIn(0, m.v.board.size)
        for (i in 0 until 5) {
            val x = fan.xs[i]
            if (i < shown) CardArt.card(g, tx, x, t.board.y, t.card, m.v.board[i])
            else {
                // an undealt slot is a faint outline, not a card back: the
                // board is public information and an empty seat in it should
                // not read as a hidden card
                g.outlineRect(x, t.board.y, t.card.w, t.card.h, Level.FAINT, 1)
            }
        }
    }

    // ------------------------------------------------------------------ status
    private fun paintStatus(g: Gray8, t: TableLayout, m: Model) {
        val r = t.status
        val me = m.v.seats.getOrNull(m.mySeat)
        val result = m.v.result
        // the band was sized for fStatusBig's ink (+4): the text rides 2 px
        // down from its top, exactly the design's placement at 100 %
        val by = r.y + 2
        if (m.note.isNotEmpty()) {
            Draw.fit(g, tx, r.x, by, Draw.dynamic(tx, m.note, fStatusBig), Level.HOT, fStatusBig, r.w)
            return
        }
        if (result != null) {
            // the left fit is sized against the MEASURED right-hand string,
            // never a magic constant: a wrap width that differs from the draw
            // bound is how text lands outside its own damage rect (§25 #3).
            // A pending cash-out changes what the tap DOES, so it changes what
            // the tail says — the confirm's notice is gone after four seconds
            // and nothing else on the table admits you are leaving.
            val tail = if (m.leaving) "tap to leave" else "tap to deal"
            Draw.fit(g, tx, r.x, by, Draw.dynamic(tx, result.line, fStatusBig),
                Level.HEAD, fStatusBig, r.w - tx.measure(tail, fSmall) - 16)
            Draw.right(g, tx, r.right, by + 2, tail, Level.DIM, fSmall)
            return
        }
        val parts = ArrayList<String>(4)
        parts.add("pot ${Money.compact(m.v.pot)}")
        val toCall = if (me != null) maxOf(0, m.v.currentBet - me.committed) else 0
        if (m.v.toAct == m.mySeat && toCall > 0) parts.add("to call ${Money.compact(toCall)}")
        else if (m.v.toAct == m.mySeat) parts.add("your move")
        else if (m.acting != null) parts.add("${m.v.seats[m.acting].who.name} …")
        if (me != null) parts.add("you ${Money.compact(me.stack)}")
        val left = parts.joinToString(" · ")
        val blinds = "${Money.compact(m.v.sb)}/${Money.compact(m.v.bb)}" +
            if (t.tier >= 1) " · up in ${m.handsToLevel}" else ""
        val bw = tx.measure(blinds, fSmall) + 12
        // 🔴 no drawn chip stack HERE. A short horizontal stack in front of a
        // word is punctuation, not chips — one bar read as a dash and two read
        // as an equals sign (first live session, 2026-09-04). The status line
        // already says the pot in figures; the drawn stacks live in the seat
        // cells, where each one sits beside the amount it stands for.
        val lx = r.x
        // the line carries a character NAME: sanitize it like any other
        // dynamic string, or a glyph the face lacks is silent tofu
        Draw.fit(g, tx, lx, by, Draw.dynamic(tx, left, fStatus),
            if (m.v.toAct == m.mySeat) Level.HEAD else Level.BODY, fStatus, r.right - lx - bw)
        Draw.right(g, tx, r.right, by + 2, blinds, Level.DIM, fSmall)
    }

    // ------------------------------------------------------------------ you
    private fun paintHole(g: Gray8, t: TableLayout, m: Model) {
        val me = m.v.seats.getOrNull(m.mySeat) ?: return
        val fan = t.holeFan(2)
        if (me.cards.isEmpty()) {
            for (x in fan.xs) g.outlineRect(x, t.hole.y, t.card.w, t.card.h, Level.FAINT, 1)
            return
        }
        HandFan.draw(g, tx, fan, t.hole.y, t.card, me.cards, dim = me.folded)
        if (me.busted) {
            Draw.right(g, tx, t.hole.right, t.hole.y + t.hole.h / 2, "out", Level.DIM, fSmall)
        }
    }

    private fun paintYourLine(g: Gray8, t: TableLayout, m: Model) {
        val me = m.v.seats.getOrNull(m.mySeat) ?: return
        val r = t.yourLine
        val bits = ArrayList<String>(4)
        bits.add(Money.fmt(me.stack))
        bits.add(Money.bb(me.stack, m.v.bb))
        if (me.index == m.v.button) bits.add("button")
        else if (me.index == m.v.sbSeat) bits.add("small blind")
        else if (me.index == m.v.bbSeat) bits.add("big blind")
        if (me.folded) bits.add("folded")
        else if (me.allIn) bits.add("all-in")
        val left = m.v.activeSeats.size
        val tail = "$left left · hand ${m.v.handNo + 1}"
        Draw.fit(g, tx, r.x, r.y + 2, bits.joinToString(" · "), Level.BODY, fSmall,
            r.w - tx.measure(tail, fSmall) - 16)
        Draw.right(g, tx, r.right, r.y + 2, tail, Level.DIM, fSmall)
    }

    private fun paintHistory(g: Gray8, t: TableLayout, m: Model) {
        val r = t.history
        // the pitch is MEASURED, never guessed: at 14 px under a face whose
        // ink is 17 the descenders of each line sat in the tops of the next
        // (review pass 3, 2026-09-04). `lineHeight` alone is not the measure
        // the §27 rule asks for — AWT ceils ascent and descent separately and
        // the height once, so at 0.85/1.15/1.3 the face's INK is a row TALLER
        // than its line height (measured: 11 px at 130 % inks 22 with a 21 px
        // line) and the last line of the band would hang past it (review §30)
        val pitch = tx.metrics(fTiny).let { maxOf(it.lineHeight, it.ascent + it.descent) }
        val room = (r.h / pitch).coerceAtLeast(1)
        val lines = m.v.history.takeLast(room)
        var y = r.y
        for (l in lines) {
            Draw.fit(g, tx, r.x, y, Draw.dynamic(tx, l, fTiny), Level.DIM, fTiny, r.w)
            y += pitch
        }
    }

    companion object {
        /** Hands together before a seat's observed stats mean anything — the
         *  same threshold the bot's own read uses (§7.6). */
        const val READ_HANDS = 50

        /** How far [seatFaces] may step the seat strip's faces down. Six
         *  steps takes the 15 px name to 9, which is under the 100 % design
         *  everywhere and only ever reached when a large global scale meets
         *  the shortest rung. */
        const val SEAT_STEPS = 6

        /** "1 hand", "12 hands" — the seat cell read "1 hands" on glass
         *  (review §28 #6), the same class as "You checks". */
        fun hands(n: Int): String = plural(n, "hand")

        fun plural(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"
    }

    /** Lit fraction of [rect] — the §9.2 ink target (≈8 % at 288, ≈10 % at
     *  480). Used by the harness, not by the paint. */
    fun ink(g: Gray8, rect: Rect): Double {
        var lit = 0
        for (y in rect.y until rect.bottom) for (x in rect.x until rect.right) if (g[x, y] != 0) lit++
        return lit.toDouble() / (rect.w * rect.h)
    }
}
