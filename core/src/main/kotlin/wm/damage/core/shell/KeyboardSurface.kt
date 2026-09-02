package wm.damage.core.shell

import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Icons
import wm.damage.core.gfx.Level
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer

/**
 * The KEYBOARD — DESIGN.md §4.8 (Adam, 2026-09-01: "a picture of a keyboard
 * with an arrow to select row then key, with all regular keys and special
 * keys available … implemented globally and activated when requested").
 * The fourth bespoke shell surface next to the wheel, the notification box
 * and the context menu (§0: no generic overlay abstraction): a HOLE in the
 * content at plane 0, drawn as a WIREFRAME — every key a 2 px outline with
 * its label centred — where brightness carries the focus.
 *
 * Grammar (his verdicts): two stages. ROW: scroll moves the row highlight
 * (wrapping), tap enters the row on its FIRST key, double-tap cancels with
 * the draft KEPT. KEY: scroll moves along the row (wrapping — no key is more
 * than half a row away), tap types the key and STAYS in the row, double-tap
 * returns to ROW. `Enter` commits. Every row's first key is harmless (§1.7);
 * Enter and Clear sit at row ends, never at a rest position.
 *
 * The shell routes input while [open]; this class owns the model and the
 * paint. Nothing persists here — the draft belongs to the requester.
 */
class KeyboardSurface(private val text: TextRasterizer) {

    /** A requester-supplied LIVE key (Tmux's quick keys): tapping it calls
     *  [Spec.onExtra] at once and the keyboard stays open. */
    class ExtraKey(val label: String, val id: String)

    class Spec(
        /** The prompt, drawn small in the text line — short by design. */
        val title: String,
        val initial: String = "",
        /** An optional sixth row of live keys; the first one must be harmless. */
        val extra: List<ExtraKey> = emptyList(),
        /** Runs on the shell loop AFTER the keyboard closed. */
        val onCommit: (String) -> Unit,
        /** Cancel path (double-tap at ROW, wheel, silent, relayout, an
         *  emergency): the draft the requester should pre-fill next time. */
        val onCancel: ((String) -> Unit)? = null,
        /** A live key was tapped (the keyboard stays open). */
        val onExtra: ((String) -> Unit)? = null,
    )

    enum class Stage { ROW, KEY }

    /** What a tap did — the shell acts on COMMIT and EXTRA. */
    sealed class Tap {
        object None : Tap()
        class Commit(val text: String) : Tap()
        class Extra(val id: String) : Tap()
    }

    internal enum class Kind { CHAR, BACKSPACE, ENTER, SHIFT, SYMBOLS, SPACE, LEFT, RIGHT, DEL, CLEAR, EXTRA }

    internal class Key(val kind: Kind, val span: Int, val ch: String = "", val label: String = "", val id: String = "")

    var open = false
        private set
    var stage = Stage.ROW
        private set
    var row = 0
        private set
    var key = 0
        private set
    /** 0 = off · 1 = the next key capitalized · 2 = caps lock. */
    var shift = 0
        private set
    var symbols = false
        private set

    private var spec: Spec? = null
    private var layoutName = "qwerty"
    private val buf = StringBuilder()
    private var caret = 0

    val draft: String get() = buf.toString()
    fun current(): Spec? = spec

    private var under: Gray8? = null
    private var underRect: Rect? = null

    // ------------------------------------------------------------------ model
    fun openWith(s: Spec, layout: String) {
        spec = s
        layoutName = if (layout in LAYOUTS) layout else "qwerty"
        buf.setLength(0)
        buf.append(s.initial)
        caret = buf.length
        shift = 0
        symbols = false
        stage = Stage.ROW
        row = HOME_ROW                      // opens on the home row (§4.8)
        key = 0
        open = true
        invalidateUnder()
    }

    /** Close without running anything; the caller restores the under-content. */
    fun close(): Spec? {
        val s = spec
        open = false
        spec = null
        return s
    }

    /** A replica typed a whole line while the keyboard was open: it becomes
     *  the draft (the shell then commits it — a real keyboard beat the ring). */
    fun setDraft(s: String) {
        buf.setLength(0)
        buf.append(s)
        caret = buf.length
    }

    internal fun rows(): List<List<Key>> = buildRows(layoutName, symbols, spec?.extra ?: emptyList())

    fun scroll(delta: Int) {
        val rs = rows()
        when (stage) {
            Stage.ROW -> row = (row + delta).mod(rs.size)
            Stage.KEY -> {
                val n = rs[row.coerceIn(0, rs.size - 1)].size
                if (n > 0) key = (key + delta).mod(n)
            }
        }
    }

    /** Double-tap: KEY → ROW (true); at ROW = cancel wanted (false). */
    fun back(): Boolean {
        if (stage == Stage.KEY) { stage = Stage.ROW; return true }
        return false
    }

    fun tap(): Tap {
        val rs = rows()
        row = row.coerceIn(0, rs.size - 1)
        if (stage == Stage.ROW) {
            stage = Stage.KEY
            key = 0                          // cursor rest: the row's first, harmless key (§1.7)
            return Tap.None
        }
        val r = rs[row]
        if (r.isEmpty()) return Tap.None
        key = key.coerceIn(0, r.size - 1)
        val k = r[key]
        when (k.kind) {
            Kind.CHAR -> {
                val ch = if (shift > 0) k.ch.uppercase() else k.ch
                insert(ch)
                if (shift == 1) shift = 0
            }
            Kind.SPACE -> insert(" ")
            Kind.BACKSPACE -> if (caret > 0) {
                val start = caret - Character.charCount(buf.codePointBefore(caret))
                buf.delete(start, caret)
                caret = start
            }
            Kind.DEL -> if (caret < buf.length) {
                val end = caret + Character.charCount(buf.codePointAt(caret))
                buf.delete(caret, end)
            }
            Kind.LEFT -> if (caret > 0) caret -= Character.charCount(buf.codePointBefore(caret))
            Kind.RIGHT -> if (caret < buf.length) caret += Character.charCount(buf.codePointAt(caret))
            Kind.CLEAR -> { buf.setLength(0); caret = 0 }
            Kind.SHIFT -> shift = (shift + 1) % 3
            Kind.SYMBOLS -> {
                symbols = !symbols
                // the row keeps its position; the key index clamps to the new row
                val n = rows()[row].size
                key = key.coerceIn(0, maxOf(0, n - 1))
            }
            Kind.ENTER -> return Tap.Commit(draft)
            Kind.EXTRA -> return Tap.Extra(k.id)
        }
        return Tap.None
    }

    private fun insert(s: String) {
        buf.insert(caret, s)
        caret += s.length
    }

    // ------------------------------------------------------------------ geometry
    /** Row pitch for this layout: 48 px at the full height, shrinking on the
     *  4-height ladder so the whole keyboard always fits the content area
     *  (§4.8) — never below 24 (a label still reads at 1×). */
    private fun pitch(l: Layout, nRows: Int): Int {
        val avail = l.content.h - 2 * PAD
        return Geometry.snapY(avail / (nRows + 1)).coerceIn(24, 48)
    }

    fun rect(l: Layout): Rect? {
        if (spec == null) return null
        val n = rows().size
        val p = pitch(l, n)
        val h = Geometry.snapY((n + 1) * p + 2 * PAD)
        val cy = l.content.y + l.content.h / 2
        val y = Geometry.snapY(cy - h / 2).coerceIn(l.content.y, maxOf(l.content.y, l.content.bottom - h))
        return Rect(l.content.x, y, l.content.w, h)
    }

    fun captureUnder(g: Gray8, box: Rect) {
        if (underRect == box && under != null) return
        val u = Gray8(box.w, box.h)
        u.blit(g, box, 0, 0)
        under = u
        underRect = box
    }

    fun invalidateUnder() {
        under = null
        underRect = null
    }

    fun restoreUnderFinished(g: Gray8): Rect? {
        val u = under
        val ur = underRect
        invalidateUnder()
        if (u == null || ur == null) return null
        g.blit(u, Rect(0, 0, ur.w, ur.h), ur.x, ur.y)
        return ur
    }

    // ------------------------------------------------------------------ paint
    private val fPrompt = FontSpec(Face.SYSTEM, 13, bold = true)
    private val fDraft = FontSpec(Face.SYSTEM, 18)

    /** Paint the keyboard (capturing the under-content first). Returns the box. */
    fun paint(g: Gray8, l: Layout): Rect? {
        val s = spec ?: return null
        val box = rect(l) ?: return null
        captureUnder(g, box)
        g.fillRect(box, Level.BG)                       // the hole
        g.fillRect(box.x, box.y, box.w, 2, Level.DIM)   // the bracketing rules
        g.fillRect(box.x, box.bottom - 2, box.w, 2, Level.DIM)

        val rs = rows()
        val p = pitch(l, rs.size)
        val x0 = box.x + (box.w - UNITS * UNIT) / 2 / 4 * 4
        val textY = box.y + PAD
        paintTextLine(g, s, box, x0, textY, p)

        val fBase = (p * 2 / 5).coerceIn(12, 18)
        for ((ri, r) in rs.withIndex()) {
            val ry = textY + (ri + 1) * p
            val focusedRow = ri == row
            if (focusedRow && stage == Stage.ROW) Icons.tri(g, x0 - 12, ry + p / 2 - 5, 11, Level.MID)
            // the row's label size: the largest that fits EVERY label of the
            // row in its cell (a live row of "Ctrl-C"/"Enter" at one unit each
            // overran its outlines — review 2026-09-01 K3); Draw.fit stays
            // underneath as the marked last resort
            val fLabel = rowFont(r, fBase)
            val fm = text.metrics(fLabel)
            var u = 0
            for ((ki, k) in r.withIndex()) {
                val kx = x0 + u * UNIT
                val cell = Rect(kx + 4, ry + 2, k.span * UNIT - 8, p - 4)
                val focusedKey = focusedRow && stage == Stage.KEY && ki == key
                val latched = (k.kind == Kind.SHIFT && shift > 0) || (k.kind == Kind.SYMBOLS && symbols)
                val outline = when {
                    focusedKey -> Level.HEAD
                    focusedRow -> Level.DIM
                    else -> Level.FAINT
                }
                val lv = when {
                    focusedKey || latched -> Level.HEAD
                    focusedRow -> Level.BODY
                    else -> Level.DIM
                }
                g.outlineRect(cell.x, cell.y, cell.w, cell.h, outline, 2)
                paintLabel(g, k, cell, fLabel, fm.lineHeight, lv)
                u += k.span
            }
        }
        return box
    }

    private fun labelOf(k: Key): String = when (k.kind) {
        Kind.CHAR -> if (shift > 0) k.ch.uppercase() else k.ch
        Kind.SYMBOLS -> if (symbols) "abc" else "?123"
        Kind.LEFT, Kind.RIGHT, Kind.SHIFT -> ""
        else -> k.label
    }

    private fun rowFont(r: List<Key>, base: Int): FontSpec {
        var size = base
        while (size > 11) {
            val f = FontSpec(Face.SYSTEM, size, bold = true)
            val fits = r.all { k ->
                val l = labelOf(k)
                l.isEmpty() || text.measure(display(l, f), f) <= k.span * UNIT - 16
            }
            if (fits) return f
            size -= 2
        }
        return FontSpec(Face.SYSTEM, 11, bold = true)
    }

    private fun paintLabel(g: Gray8, k: Key, cell: Rect, f: FontSpec, lineH: Int, lv: Int) {
        when (k.kind) {
            Kind.LEFT -> Icons.tri(g, cell.x + cell.w / 2 + 5, cell.y + cell.h / 2 - 6, 13, lv, left = true)
            Kind.RIGHT -> Icons.tri(g, cell.x + cell.w / 2 - 5, cell.y + cell.h / 2 - 6, 13, lv)
            Kind.SHIFT -> {
                // an up-arrow: a filled triangle over a stem — a closed form (§2.4 r9)
                val cx = cell.x + cell.w / 2
                val top = cell.y + cell.h / 2 - 8
                g.fillPolygon(intArrayOf(cx, cx - 8, cx + 8), intArrayOf(top, top + 8, top + 8), lv)
                g.fillRect(cx - 3, top + 8, 6, 8, lv)
                if (shift == 2) g.fillRect(cx - 6, top + 18, 12, 2, lv)   // caps lock: underlined
            }
            else -> {
                val label = display(labelOf(k), f)
                val maxW = cell.w - 8
                val w = minOf(text.measure(label, f), maxW)
                val x = (cell.x + (cell.w - w) / 2) / 4 * 4
                val y = (cell.y + (cell.h - lineH) / 2) / 2 * 2
                Draw.fit(g, text, x, y, label, lv, f, maxW)
            }
        }
    }

    /** The text line: prompt, then the draft with its caret. A draft wider
     *  than the line PANS so the caret stays visible — the cut is marked at
     *  the edge it happened on and the whole text is reachable by moving the
     *  caret (§2.4 r3: advertised and reachable, never silent). */
    /** A 1:1 display form of [s]: every code point the face cannot draw
     *  becomes '?' — ONE per UTF-16 unit, so caret indices into the draft
     *  and into its display string coincide (Draw.dynamic collapses a
     *  surrogate pair to one '?', which would shift the caret). */
    private val coverCache = HashMap<Int, Boolean>()
    private fun display(s: String, f: FontSpec): String {
        var clean = true
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (cp < 0x20 || !(cp in 0x20..0x7E || coverCache.getOrPut(cp) { text.covers(String(Character.toChars(cp)), f) })) {
                clean = false; break
            }
        }
        if (clean) return s
        val sb = StringBuilder(s.length)
        i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = Character.charCount(cp)
            i += n
            val ok = cp >= 0x20 && (cp in 0x20..0x7E || coverCache.getOrPut(cp) { text.covers(String(Character.toChars(cp)), f) })
            if (ok) sb.append(String(Character.toChars(cp))) else repeat(n) { sb.append('?') }
        }
        return sb.toString()
    }

    private fun paintTextLine(g: Gray8, s: Spec, box: Rect, x0: Int, y: Int, p: Int) {
        val prompt = display(s.title.uppercase(), fPrompt)
        val pm = text.metrics(fPrompt)
        val dm = text.metrics(fDraft)
        val right = x0 + UNITS * UNIT
        // the prompt is a handle: fitted to a third of the line with the
        // mark (a long Tmux session name must never push the draft off the
        // line — review 2026-09-01 K4)
        val promptMax = UNITS * UNIT / 3
        val promptW = minOf(text.measure(prompt, fPrompt), promptMax)
        Draw.fit(g, text, x0, (y + (p - pm.lineHeight) / 2) / 2 * 2, prompt, Level.DIM, fPrompt, promptMax)
        var dx = (x0 + promptW + 16) / 4 * 4
        val avail = right - dx - 8
        val dy = (y + (p - dm.lineHeight) / 2) / 2 * 2
        val d = display(draft, fDraft)
        // the visible window: from `start` so that the head up to the caret fits
        var start = 0
        val headMax = avail - 14
        if (text.measure(d.substring(0, caret), fDraft) > headMax) {
            // smallest start whose [start, caret) fits — monotone, binary search
            var a = 0
            var b = caret
            while (a < b) {
                val m = (a + b) / 2
                if (text.measure(d.substring(m, caret), fDraft) <= headMax) b = m else a = m + 1
            }
            start = a
        }
        if (start > 0) {
            Icons.tri(g, dx + 10, dy + 5, 11, Level.DIM, left = true)
            dx += 16
        }
        val shown = d.substring(start)
        val cut = Draw.fit(g, text, dx, dy, shown, Level.BODY, fDraft, right - dx - 4)
        val caretX = (dx + text.measure(d.substring(start, caret), fDraft)).coerceAtMost(right - 4)
        // the caret: a 2 px bar at HEAD, tall as the line
        g.fillRect(caretX / 2 * 2, dy, 2, dm.lineHeight, Level.HEAD)
        if (cut) Unit   // the tail's cut is already marked by Draw.fit
    }

    companion object {
        const val UNIT = 48                 // one key unit, ×4 ✓
        const val UNITS = 12                // 576 px of keys in the 608 px content
        const val MAX_EXTRA = 12            // live keys: two rows at most
        const val PAD = 8
        /** The home row (asdf…) — where the ROW stage opens. */
        const val HOME_ROW = 2
        val LAYOUTS = listOf("qwerty", "abc")

        private fun chars(vararg cs: String) = cs.map { Key(Kind.CHAR, 1, ch = it) }

        /** The rows for a layout + layer (+ the requester's live row). Every
         *  row sums to [UNITS]; KeyboardTest pins that and the ASCII coverage. */
        internal fun buildRows(layout: String, symbols: Boolean, extra: List<ExtraKey>): List<List<Key>> {
            val digits = chars("1", "2", "3", "4", "5", "6", "7", "8", "9", "0") +
                Key(Kind.BACKSPACE, 2, label = "Bksp")
            val letters: List<List<Key>> = if (symbols) listOf(
                chars("!", "@", "#", "$", "%", "^", "&", "*", "(", ")", "{", "}"),
                chars("+", "=", "[", "]", ";", ":", "\"", "<", ">", "~") + Key(Kind.ENTER, 2, label = "Enter"),
                listOf(Key(Kind.SHIFT, 2), Key(Kind.CHAR, 2, ch = "`"), Key(Kind.CHAR, 2, ch = "\\"),
                    Key(Kind.CHAR, 2, ch = "|"), Key(Kind.CHAR, 2, ch = "?"), Key(Kind.BACKSPACE, 2, label = "Bksp")),
            ) else if (layout == "abc") listOf(
                chars("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "-", "/"),
                chars("k", "l", "m", "n", "o", "p", "q", "r", "s", "'") + Key(Kind.ENTER, 2, label = "Enter"),
                listOf(Key(Kind.SHIFT, 2)) + chars("t", "u", "v", "w", "x", "y", "z", ",", ".", "_"),
            ) else listOf(
                chars("q", "w", "e", "r", "t", "y", "u", "i", "o", "p", "-", "/"),
                chars("a", "s", "d", "f", "g", "h", "j", "k", "l", "'") + Key(Kind.ENTER, 2, label = "Enter"),
                listOf(Key(Kind.SHIFT, 2)) + chars("z", "x", "c", "v", "b", "n", "m", ",", ".", "_"),
            )
            val bottom = listOf(
                Key(Kind.SYMBOLS, 2), Key(Kind.SPACE, 5, label = "Space"),
                Key(Kind.LEFT, 1), Key(Kind.RIGHT, 1), Key(Kind.DEL, 1, label = "Del"),
                Key(Kind.CLEAR, 2, label = "Clear"),
            )
            val out = ArrayList<List<Key>>()
            out.add(digits)
            out.addAll(letters)
            out.add(bottom)
            if (extra.isNotEmpty()) {
                // live keys: one row up to six, two rows up to twelve (labels
                // keep at least two units), more is refused LOUDLY — a
                // requester must cap what it sends, never lose keys silently
                // (review 2026-09-01 K1: Tmux's 14 defaults lost Tab and q)
                require(extra.size <= MAX_EXTRA) { "a keyboard live row holds at most $MAX_EXTRA keys, got ${extra.size}" }
                val chunks = if (extra.size <= 6) listOf(extra) else
                    listOf(extra.take((extra.size + 1) / 2), extra.drop((extra.size + 1) / 2))
                for (chunk in chunks) {
                    val n = chunk.size
                    val span = UNITS / n
                    out.add(chunk.mapIndexed { i, e ->
                        Key(Kind.EXTRA, if (i == n - 1) UNITS - span * (n - 1) else span, label = e.label, id = e.id)
                    })
                }
            }
            return out
        }
    }
}
