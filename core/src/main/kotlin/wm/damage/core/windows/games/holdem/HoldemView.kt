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
    )

    private val fName = FontSpec(Face.SYSTEM, 15, bold = true)
    private val fSmall = FontSpec(Face.SYSTEM, 12)
    private val fTiny = FontSpec(Face.SYSTEM, 11)
    private val fStack = FontSpec(Face.SYSTEM, 14)
    private val fStatus = FontSpec(Face.SYSTEM, 16)
    private val fStatusBig = FontSpec(Face.SYSTEM, 17, bold = true)

    /** The last layout painted — the window asks it for the depth region. */
    var layout: TableLayout? = null
        private set

    fun paint(g: Gray8, rect: Rect, m: Model) {
        val t = TableLayout(rect, rect.h + 64)
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
    private fun paintSeats(g: Gray8, t: TableLayout, m: Model) {
        val others = m.v.seats.filter { it.index != m.mySeat }
        if (others.isEmpty()) return
        for ((i, s) in others.withIndex()) {
            paintSeat(g, t.seatCell(i, others.size), t, s, m)
        }
    }

    private fun paintSeat(g: Gray8, cell: Rect, t: TableLayout, s: Seats.Seat, m: Model) {
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
                markW = 16
            } else if (s.index == m.v.sbSeat || s.index == m.v.bbSeat) {
                val lbl = if (s.index == m.v.sbSeat) "sb" else "bb"
                tx.draw(g, x, y + 2, lbl, fTiny, Level.DIM)
                markW = tx.measure(lbl, fTiny) + 6
            }
        }
        Draw.fit(g, tx, x + markW, y, Draw.dynamic(tx, s.who.name, fName), nameLv, fName, w - markW)
        y += 17

        if (gone) {
            tx.draw(g, x, y, "out", fSmall, Level.FAINT)
            return
        }
        // stack + what is in front of them
        val stack = Money.compact(s.stack)
        tx.draw(g, x, y, stack, fStack, if (s.allIn) Level.HOT else bodyLv)
        val inFront = if (s.committed > 0) Money.compact(s.committed) else ""
        if (inFront.isNotEmpty()) {
            Draw.right(g, tx, cell.right - 6, y + 2, inFront, if (dim) Level.DIM else Level.MID, fSmall)
            // §9.2's top rung: drawn chip stacks in place of bare numbers
            if (t.showsChipStacks) {
                Money.chipStack(g, cell.right - 22, y + 22, 16, s.committed, m.v.bb,
                    if (dim) Level.REST else Level.MID)
            }
        }
        y += 16

        if (t.showsLastAction) {
            val shown = m.v.result?.shown ?: emptySet()
            if (s.index in shown && s.cards.isNotEmpty()) {
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
            y += 15
        }
        // §9.2 left "actual backs at 480" as a CONSIDERATION and the mark won
        // it at every rung: two 72x100 backs per seat is ten lit rectangles
        // carrying no information, which is the ink trap that section names.
        if (!s.folded && s.cards.isNotEmpty()) {
            CardArt.holdingMark(g, cell.right - 22, cell.y + 20, 16, 10, 2,
                if (dim) Level.REST else Level.MID)
        }
        if (t.showsObservedStats && m.showStats) {
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
        if (m.note.isNotEmpty()) {
            Draw.fit(g, tx, r.x, r.y + 2, Draw.dynamic(tx, m.note, fStatusBig), Level.HOT, fStatusBig, r.w)
            return
        }
        if (result != null) {
            // the left fit is sized against the MEASURED right-hand string,
            // never a magic constant: a wrap width that differs from the draw
            // bound is how text lands outside its own damage rect (§25 #3)
            val tail = "tap to deal"
            Draw.fit(g, tx, r.x, r.y + 2, Draw.dynamic(tx, result.line, fStatusBig),
                Level.HEAD, fStatusBig, r.w - tx.measure(tail, fSmall) - 16)
            Draw.right(g, tx, r.right, r.y + 4, tail, Level.DIM, fSmall)
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
        var lx = r.x
        if (t.showsChipStacks && m.v.pot > 0) {
            Money.chipStack(g, r.x, r.y + r.h - 4, 14, m.v.pot, m.v.bb, Level.MID)
            lx += 22
        }
        // the line carries a character NAME: sanitize it like any other
        // dynamic string, or a glyph the face lacks is silent tofu
        Draw.fit(g, tx, lx, r.y + 2, Draw.dynamic(tx, left, fStatus),
            if (m.v.toAct == m.mySeat) Level.HEAD else Level.BODY, fStatus, r.right - lx - bw)
        Draw.right(g, tx, r.right, r.y + 4, blinds, Level.DIM, fSmall)
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
        val lines = m.v.history.takeLast(r.h / 14)
        var y = r.y
        for (l in lines) {
            Draw.fit(g, tx, r.x, y, Draw.dynamic(tx, l, fTiny), Level.DIM, fTiny, r.w)
            y += 14
        }
    }

    companion object {
        /** Hands together before a seat's observed stats mean anything — the
         *  same threshold the bot's own read uses (§7.6). */
        const val READ_HANDS = 50
    }

    /** Lit fraction of [rect] — the §9.2 ink target (≈8 % at 288, ≈10 % at
     *  480). Used by the harness, not by the paint. */
    fun ink(g: Gray8, rect: Rect): Double {
        var lit = 0
        for (y in rect.y until rect.bottom) for (x in rect.x until rect.right) if (g[x, y] != 0) lit++
        return lit.toDouble() / (rect.w * rect.h)
    }
}
