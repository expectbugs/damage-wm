package wm.damage.core.motion

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonObject
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.geom.VPos
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.gfx.Level
import wm.damage.core.gfx.Pack
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.MainSurface
import wm.damage.core.shell.WindowView
import wm.damage.core.text.FontMetrics
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.wire.TextureCache

/**
 * Build C2, stage DM0 (`C2-decisions.md` C): Main's pictures for the `mainpan` test programs ([TestPrograms]).
 * Context for the reader: Adam's own glasses on his own firmware fork; these programs play only by hand.
 *
 * Main's geometry is the shell's own — [Layout] at [height] with the design's row pitch and band (the floors
 * `Shell.listRhythm` gives at 100 %), the rows where `ContentKit.paintList` puts them — and its pictures are drawn by
 * [MainSurface]'s own `paintRow` (the row form) and `paintLens` under `ContentKit.paintLensBand`'s fill and rules (the
 * band form). Two things are stand-ins, because the probe builds a program in the transport, which holds no rasterizer
 * and no windows (core has no face of its own; the hosts' are AWT and Android): the text is [BoxFace] (a box per
 * character at the real face's metrics) and the rows are Main's eight windows in the shell's order with fixed
 * summaries. Both builds of one program — the probe's, and the shell's END into belief — draw the same pixels.
 *
 * The planes are the shell's at Global `Depth` 8 (the default): the list at disparity 8, the band one notch nearer at
 * 4 (`Shell.updatePlanes`), with `Emit.stereo`'s sign — the left lens at x − d, the right at x + d.
 */
internal object MainPan {
    /** The height Main ran at on his phone's journal of 2026-11-18 (the band at (16,146 604x64), disparity 4; three
     *  pulls). Every number below follows from it through [Layout]; 416 and 480 build and play too (the tests). */
    const val HEIGHT = 352
    /** The cursor the programs start from (Torrents, the fourth row): one notch down to Music keeps rows c − 3 .. c + 4
     *  inside 0..7, so no height wraps inside a strip. */
    const val CURSOR = 3
    const val LIST_D = 8
    const val BAND_D = 4
    /** The pan: T steps at P = 34 (spec §6.6), the band's swap after [SWAP] of them, the far level [LVL_BACK]. */
    const val T = 6
    const val SWAP = 2
    const val LVL_BACK = 8

    private class Row(id: String, name: String, icon: IconKind, private val s: DamageWindow.Summary) : DamageWindow(id, name, icon) {
        override fun view(): WindowView = WindowView.ListView(ListModel(), { 0 }, { _, _, _, _ -> }, { _, _, _ -> }, {})
        override fun summary() = s
        override fun saveState(): JsonObject = JsonObject(emptyMap())
        override fun restoreState(state: JsonObject) {}
    }

    /** Main's rows in the shell's order (`Shell.mainRows`: the windows as registered, Settings last). */
    private val ROWS: List<DamageWindow> = listOf(
        Row("reader", "Reader", IconKind.READER, DamageWindow.Summary("The Long Way Home", detail = "chapter 12 of 31", progress = 0.41)),
        Row("tmux", "Tmux", IconKind.TERMINAL, DamageWindow.Summary("3 sessions", detail = "build: idle 4 min")),
        Row("files", "Files", IconKind.FILES, DamageWindow.Summary("Documents", detail = "24 items", more = true)),
        Row("torrents", "Torrents", IconKind.TORRENTS, DamageWindow.Summary("2 active, 3.1 MB/s", detail = "debian-13.iso 64%", progress = 0.64)),
        Row("music", "Music", IconKind.MUSIC, DamageWindow.Summary("Night Drive - Low Tide", detail = "2:31 / 4:05", more = true, progress = 0.62)),
        Row("games", "Games", IconKind.GAMES, DamageWindow.Summary("Hold'em, 6 left", detail = "stack 1,840")),
        Row("feed", "Feed", IconKind.FEED, DamageWindow.Summary("12 new", detail = "4 sources", more = true)),
        Row("settings", "Settings", IconKind.SETTINGS, DamageWindow.Summary("Global and 8 apps")),
    )
    private val atHeight = ConcurrentHashMap<Int, Pictures>()

    /** Main at [height], its pictures drawn once per process (they never change). */
    fun at(height: Int): Pictures = atHeight.getOrPut(height) { Pictures(height) }

    class Pictures internal constructor(val height: Int) {
        val layout: Layout = Layout().withHeightMode(height, VPos.TOP)
        /** A row's width: the content less the rail (`ContentKit.rowRect`). */
        val rowW: Int = layout.content.w - Layout.RAIL_W
        private val view = MainSurface(BoxFace, { ROWS }, {}).view()
        val rows: Int get() = ROWS.size
        /** `ContentKit.wrapIndex`: the list wraps when it has more rows than Main shows. */
        val wraps: Boolean = ROWS.size > layout.rowsAbove + layout.rowsBelow + 1

        /** Rows 0..7 in row form stacked at the pitch, as `paintRow` draws them at Main's x. */
        val strip: TextureCache.Image2 by lazy {
            val g = Gray8(layout.content.x + rowW, ROWS.size * layout.rowH)
            for (i in ROWS.indices) view.paintRow(g, i, Rect(layout.content.x, i * layout.rowH, rowW, layout.rowH), false)
            crop(g, Rect(layout.content.x, 0, rowW, g.h))
        }

        private val bands = ConcurrentHashMap<Int, TextureCache.Image2>()

        /** Row [i]'s band form: the lens band as `ContentKit.paintLensBand` paints it with [i] at the cursor, the rail aside. */
        fun band(i: Int): TextureCache.Image2 = bands.getOrPut(i) {
            val g = Gray8(layout.content.x + rowW, layout.lens.bottom)
            paintBand(g, i)
            crop(g, Rect(layout.lens.x, layout.lens.y, rowW, layout.lens.h))
        }

        private fun paintBand(g: Gray8, i: Int) {
            val l = layout.lens
            g.fillRect(l, Level.BG)
            g.fillRect(l.x, l.y, rowW, 2, Level.DIM)
            g.fillRect(l.x, l.bottom - 2, rowW, 2, Level.DIM)
            view.paintLens(g, Rect(l.x, l.y, rowW, l.h), i)
        }

        /** Row [i]'s rect with the cursor at [c] (`ContentKit.rowRect`), null when it is not shown. */
        fun rowRect(i: Int, c: Int): Rect? {
            val n = ROWS.size
            for (off in -layout.rowsAbove..layout.rowsBelow) {
                if (off == 0) continue
                val idx = if (wraps) (c + off).mod(n) else (c + off).takeIf { it in 0 until n }
                if (idx != i) continue
                return if (off < 0) Rect(layout.content.x, layout.lens.y + off * layout.rowH, rowW, layout.rowH)
                else Rect(layout.content.x, layout.lens.bottom + (off - 1) * layout.rowH, rowW, layout.rowH)
            }
            return null
        }

        /** Main's list area with the cursor at [c], nominal (before the planes' shifts), as `ContentKit.paintList`
         *  paints it — the rail aside: no track reaches its columns. */
        fun picture(c: Int): Gray8 {
            val g = Gray8(wm.damage.core.geom.Geometry.PANEL_W, wm.damage.core.geom.Geometry.PANEL_H)
            g.fillRect(layout.content, Level.BG)
            for (i in ROWS.indices) rowRect(i, c)?.let { view.paintRow(g, i, it, false) }
            paintBand(g, c)
            return g
        }

        private fun crop(g: Gray8, r: Rect) =
            TextureCache.Image2(r.w, r.h, ByteArray(r.w * r.h) { p -> Pack.level(g[r.x + p % r.w, r.y + p / r.w]).toByte() })
    }
}

/**
 * The `mainpan` programs' stand-in for the row faces (see [MainPan]): each character a box of its class's ink — capitals
 * and digits to the cap height, lower case to the x-height with its ascender or descender — edged at half level, at
 * an advance near Clear Sans's. Its ink (ascent + descent) at 18 px is 27, the real face's, so Main's rows and band
 * keep the design's 32 / 64 and `Draw.lineBelow` puts line two where the real face does.
 */
internal object BoxFace : TextRasterizer {
    private fun ascent(s: Int) = (s * 11 + 9) / 10
    private fun descent(s: Int) = (s * 37 + 99) / 100

    private fun advance(cp: Int, f: FontSpec): Int {
        val s = f.sizePx
        val base = when {
            cp == ' '.code -> s * 28 / 100
            cp.toChar() in "ilj.,:;'|!" -> s * 30 / 100
            cp.toChar() in "mwMW%" -> s * 84 / 100
            Character.isUpperCase(cp) || Character.isDigit(cp) -> s * 62 / 100
            Character.isLowerCase(cp) -> s * 52 / 100
            else -> s * 46 / 100
        }
        return maxOf(3, base + if (f.bold) maxOf(1, s / 14) else 0)
    }

    override fun measure(text: String, font: FontSpec): Int {
        var w = 0
        var i = 0
        while (i < text.length) { val cp = text.codePointAt(i); w += advance(cp, font); i += Character.charCount(cp) }
        return w
    }

    override fun metrics(font: FontSpec) = FontMetrics(ascent(font.sizePx), descent(font.sizePx), ascent(font.sizePx) + descent(font.sizePx) + font.sizePx / 3)

    override fun covers(text: String, font: FontSpec) = true

    override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) {
        val s = font.sizePx
        val b = y + ascent(s)
        var cx = x
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val adv = advance(cp, font)
            val ch = cp.toChar()
            val (top, bottom) = when {
                cp == ' '.code -> 0 to 0
                ch in ".," -> b - s * 16 / 100 to b + (if (ch == ',') s * 10 / 100 else 0)
                ch in "-:;" -> b - s * 40 / 100 to b - s * 24 / 100
                Character.isUpperCase(cp) || Character.isDigit(cp) -> b - s * 72 / 100 to b
                ch in "bdfhklt" -> b - s * 74 / 100 to b
                ch in "gjpqy" -> b - s * 52 / 100 to b + s * 21 / 100
                Character.isLowerCase(cp) -> b - s * 52 / 100 to b
                else -> b - s * 72 / 100 to b
            }
            if (bottom > top) {
                val w = adv - 2
                if (w >= 3) {
                    surface.fillRect(cx + 1, top, 1, bottom - top, level / 2)
                    surface.fillRect(cx + 2, top, w - 2, bottom - top, level)
                    surface.fillRect(cx + w, top, 1, bottom - top, level / 2)
                } else surface.fillRect(cx + 1, top, maxOf(1, w), bottom - top, level)
            }
            cx += adv
        }
    }
}
