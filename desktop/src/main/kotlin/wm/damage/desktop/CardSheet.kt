package wm.damage.desktop

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import wm.damage.core.geom.Geometry
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Level
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.windows.games.kit.Card
import wm.damage.core.windows.games.kit.CardArt
import wm.damage.core.windows.games.kit.Deck
import wm.damage.core.windows.games.kit.HandFan
import wm.damage.core.windows.games.kit.Money
import wm.damage.core.windows.games.kit.Rank
import wm.damage.core.windows.games.kit.Suit

/**
 * `--card-render DIR` — the render Adam judges the look on before any layout is
 * built around it (`HOLDEM.md` §13.5, M2).
 *
 * 🔴 **True 1× only.** A 2× view flatters delicate type and has misled this
 * project for several passes (`CLAUDE.md`). Everything here is written at the
 * panel's own scale, through the same green mapping the snapshots use, so a
 * card on this sheet is the size it will be on the glass.
 *
 * Three sheets:
 *
 *  - `cards-<size>.png` — the whole rank ladder in one black suit and one red,
 *    plus a back, at each rung of the §9.1 ladder.
 *  - `cards-contrast.png` — the same cards over a BRIGHT field and a dark one.
 *    The panel is additive: the question verdict 7 answers is whether the
 *    outline/filled split still reads when the world behind it is not black.
 *  - `cards-table.png` — a board of five and a hole pair as the table lays
 *    them, at 288 and at 480.
 */
object CardSheet {

    fun run(cfg: Config, dir: Path): Nothing {
        Files.createDirectories(dir)
        val tx = AwtText()
        for (s in CardArt.Size.LADDER) sheet(tx, dir, s)
        contrast(tx, dir)
        table(tx, dir)
        scoreboard(tx, dir)
        println("card renders in $dir  (true 1x — never judge these scaled up)")
        kotlin.system.exitProcess(0)
    }

    private fun ink(g: Gray8): Double = g.pix.count { it.toInt() != 0 }.toDouble() / g.pix.size

    private fun sheet(tx: AwtText, dir: Path, s: CardArt.Size) {
        val gap = 8
        val cols = 13
        val w = Geometry.snapX(cols * (s.w + gap) + gap)
        val h = 3 * (s.h + gap) + gap + 20
        val g = Gray8(w, h)
        val f = FontSpec(Face.SYSTEM, 13, bold = true)
        tx.draw(g, gap, 4, "${s.w}x${s.h}  outline = black suit   filled = red suit", f, Level.DIM)
        for ((row, suit) in listOf(Suit.SPADES, Suit.HEARTS).withIndex()) {
            for ((col, r) in Rank.entries.withIndex()) {
                CardArt.card(g, tx, gap + col * (s.w + gap), 20 + gap + row * (s.h + gap), s, Card(r, suit))
            }
        }
        // row 3: the other two suits, a back, and a dimmed (folded) pair
        val y = 20 + gap + 2 * (s.h + gap)
        var x = gap
        for (c in listOf(Card(Rank.ACE, Suit.CLUBS), Card(Rank.ACE, Suit.DIAMONDS),
            Card(Rank.TEN, Suit.CLUBS), Card(Rank.TEN, Suit.DIAMONDS))) {
            CardArt.card(g, tx, x, y, s, c); x += s.w + gap
        }
        CardArt.back(g, x, y, s); x += s.w + gap
        CardArt.card(g, tx, x, y, s, Card(Rank.KING, Suit.SPADES), dim = true); x += s.w + gap
        CardArt.card(g, tx, x, y, s, Card(Rank.KING, Suit.HEARTS), dim = true)
        write(g, dir, "cards-${s.w}x${s.h}", "ladder rung ${s.w}x${s.h}")
    }

    private fun contrast(tx: AwtText, dir: Path) {
        // The additive-panel question: does the split still read when the world
        // behind the glass is bright? The panel ADDS light, so the honest model
        // is `world + panel`, clamped — not the panel painted over the world.
        // A first version drew the field and let the card overwrite it, which
        // made a filled card look DARKER than its surroundings: the opposite of
        // what the hardware does.
        val s = CardArt.Size.S480
        val small = CardArt.Size.S288
        val panel = Gray8(Geometry.PANEL_W, 240)
        val f = FontSpec(Face.SYSTEM, 13, bold = true)
        tx.draw(panel, 8, 4, "over black", f, Level.DIM)
        tx.draw(panel, Geometry.PANEL_W / 2 + 8, 4, "over a bright field", f, Level.DIM)
        for (x0 in listOf(8, Geometry.PANEL_W / 2 + 8)) {
            CardArt.card(panel, tx, x0, 24, s, Card(Rank.ACE, Suit.SPADES))
            CardArt.card(panel, tx, x0 + s.w + 8, 24, s, Card(Rank.ACE, Suit.HEARTS))
            CardArt.card(panel, tx, x0, 24 + s.h + 8, small, Card(Rank.QUEEN, Suit.CLUBS))
            CardArt.card(panel, tx, x0 + small.w + 8, 24 + s.h + 8, small, Card(Rank.QUEEN, Suit.DIAMONDS))
        }
        val out = Gray8(panel.w, panel.h)
        val field = Level.of(7)
        for (y in 0 until panel.h) for (x in 0 until panel.w) {
            val world = if (x >= panel.w / 2) field else 0
            out[x, y] = minOf(255, world + panel[x, y])
        }
        write(out, dir, "cards-contrast", "outline vs filled, panel ADDED to the world")
    }

    private fun table(tx: AwtText, dir: Path) {
        val g = Gray8(Geometry.PANEL_W, Geometry.PANEL_H)
        val f = FontSpec(Face.SYSTEM, 13, bold = true)
        val deck = Deck.shuffled(20260904)
        var y = 4
        for (h in listOf(288, 480)) {
            val layout = wm.damage.core.geom.Layout(wm.damage.core.geom.Rect(0, 0, 640, h))
            val t = wm.damage.core.windows.games.kit.TableLayout(layout.content, h)
            tx.draw(g, 16, y, "h=$h  card ${t.card.w}x${t.card.h}", f, Level.DIM)
            y += 18
            val boardFan = HandFan.layout(5, t.card, t.board.w, 16 + t.board.w / 2, if (t.card.w >= 64) 16 else 12)
            HandFan.draw(g, tx, boardFan, y, t.card, deck.take(5))
            y += t.card.h + 10
            val holeFan = HandFan.layout(2, t.card, t.hole.w, 16 + t.hole.w / 2, if (t.card.w >= 64) 16 else 12)
            HandFan.draw(g, tx, holeFan, y, t.card, deck.drop(5).take(2))
            y += t.card.h + 8
            Money.dollarMark(g, 24, y, Money.Seg.SMALL)
            Money.digits(g, 24 + Money.dollarWidth(Money.Seg.SMALL) + Money.Seg.SMALL.gap, y,
                Money.group(1847), Money.Seg.SMALL)
            y += Money.Seg.SMALL.h + 12
        }
        write(g, dir, "cards-table", "the board and hole strips at 288 and 480")
    }

    /** The seven-segment scoreboard at its three sizes, against the captions
     *  that ride beside it — a segment display cannot spell CASH. */
    private fun scoreboard(tx: AwtText, dir: Path) {
        val g = Gray8(Geometry.PANEL_W, 200)
        val cap = FontSpec(Face.SYSTEM, 12, bold = true)
        var y = 6
        for ((name, s) in listOf("small" to Money.Seg.SMALL, "medium" to Money.Seg.MEDIUM,
            "large" to Money.Seg.LARGE)) {
            tx.draw(g, 8, y + s.h / 2 - 6, "$name ${s.w}x${s.h}", cap, Level.DIM)
            val w = Money.scoreboard(g, tx, 112, y, s, 1847, 12, 3)
            y += Money.scoreboardHeight(s) + 14
            if (w > Geometry.PANEL_W - 112) println("  ! the $name scoreboard is ${w}px wide")
        }
        write(g, dir, "cards-scoreboard", "the drawn scoreboard at three sizes")
    }

    private fun write(g: Gray8, dir: Path, name: String, note: String) {
        val img = BufferedImage(g.w, g.h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until g.h) for (x in 0 until g.w) {
            // quantize to the panel's 16 levels first — the sheet must show
            // what the firmware will actually hold, not the 8-bit composition
            val v = ((g[x, y] + 8) / 17).coerceIn(0, 15) * 17
            img.setRGB(x, y, ((v * 0.16).toInt() shl 16) or
                (minOf(255, (v * 1.05).toInt()) shl 8) or (v * 0.34).toInt())
        }
        ImageIO.write(img, "png", dir.resolve("$name.png").toFile())
        println("  ${"%-22s".format("$name.png")} ${g.w}x${g.h}  ink ${"%.1f".format(ink(g) * 100)}%  $note")
    }
}
