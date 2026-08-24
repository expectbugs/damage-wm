package wm.damage.core.shell

import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Level
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.text.Wrap

/**
 * Notifications — bespoke, deliberately not the switcher (DESIGN.md §4.5).
 * The box is a HOLE, not a card: it clears its region to transparency and
 * floats marks in the gap, bracketed by two horizontal rules (RLE-cheap).
 * It unfurls downward from a rule (the reveal costs the same bytes as one
 * static paint), arrives at the content plane, and STEPS FORWARD when it takes
 * focus after the grace period. One box at a time; queue coalesces by
 * source+thread; emergencies get the 608-wide doubled-rule band at plane +1.
 */
class Notifications(private val text: TextRasterizer) {

    data class Notice(
        val source: String,           // "SMS · MOM"
        val thread: String,           // coalescing key
        val body: String,
        val timeHHMM: String,
        val emergency: Boolean = false,
        val appId: String? = null,    // tap opens this app when set
        var read: Boolean = false,
    )

    private val queue = ArrayDeque<Notice>()
    var current: Notice? = null
        private set

    /** true once the grace has elapsed and gestures land on the box (§4.5). */
    var focused = false
        private set

    /** Set while the user is actively clearing a queue: the NEXT box skips the
     *  grace (§4.5 grace rule 2). */
    private var clearingRun = false

    /** Unfurl animation: 0..4; 4 = fully shown. Furl runs it backward. */
    private var unfurl = 0
    private var furling = false
    var scroll = 0
        private set

    private val fSmall = FontSpec(Face.SYSTEM, 13, bold = true)
    private val fTiny = FontSpec(Face.SYSTEM, 12, bold = true)
    private val fBody = FontSpec(Face.SYSTEM, 17)
    private val fBig = FontSpec(Face.SYSTEM, 21, bold = true)

    val active: Boolean get() = current != null
    val animating: Boolean get() = (current != null && unfurl < 4) || furling
    val queueDepth: Int get() = queue.size

    /** Coalesce by source+thread (§4.5): a new message in the shown thread
     *  replaces the body; in a queued thread it replaces that entry. */
    fun post(n: Notice): Boolean {
        val cur = current
        if (cur != null && cur.source == n.source && cur.thread == n.thread) {
            current = n
            return true
        }
        val i = queue.indexOfFirst { it.source == n.source && it.thread == n.thread }
        if (i >= 0) queue[i] = n else queue.addLast(n)
        if (current == null) show()
        return true
    }

    private fun show(): Boolean {
        val n = queue.removeFirstOrNull() ?: run { clearingRun = false; return false }
        current = n
        scroll = 0
        unfurl = 0
        furling = false
        focused = clearingRun          // an actively-cleared queue skips the grace
        return true
    }

    /** Grace elapsed with no input: the box takes focus and steps forward. */
    fun takeFocus() {
        if (current != null && unfurl >= 4) focused = true
    }

    fun stepUnfurl(): Boolean {
        if (furling) {
            unfurl--
            if (unfurl <= 0) { furling = false; current = null; return show() }
            return true
        }
        if (unfurl < 4) { unfurl++; return unfurl < 4 }
        return false
    }

    /** Dismiss the current box. [markRead] per gesture (§4.5). Returns the
     *  notice so the shell can update read state. */
    fun dismiss(markRead: Boolean, clearing: Boolean = true): Notice? {
        val n = current ?: return null
        n.read = n.read || markRead
        clearingRun = clearing && queue.isNotEmpty()
        furling = true
        return n
    }

    /** Silent-mode drop: no furl animation, stays unread, next box (if any)
     *  appears fresh (§4.5 silent: "the box is gone in 5 s"). */
    fun dropSilent() {
        current = null
        furling = false
        unfurl = 0
        show()
    }

    fun scrollBody(delta: Int, l: Layout) {
        val n = current ?: return
        val lines = bodyLines(n, l)
        val visible = maxLinesFor(n)
        scroll = (scroll + delta).coerceIn(0, maxOf(0, lines.size - visible))
    }

    /** Marks all queued+current notices of [appId] read (activation auto-read,
     *  §4.5 — preview must NOT call this). */
    fun markAppRead(appId: String) {
        current?.let { if (it.appId == appId) it.read = true }
        for (n in queue) if (n.appId == appId) n.read = true
    }

    // ------------------------------------------------------------------ paint
    /** The box rect at the CURRENT unfurl step (grows downward from its top
     *  rule, §4.5), in whole lines so it quantizes for free. */
    fun boxRect(l: Layout, silent: Boolean): Rect? {
        val n = current ?: return null
        val full = fullRect(n, l, silent)
        if (unfurl >= 4) return full
        val h = maxOf(2, Geometry.snapY(full.h * unfurl / 4))
        return Rect(full.x, full.y, full.w, h)
    }

    fun fullRect(n: Notice, l: Layout, silent: Boolean): Rect {
        if (n.emergency) {
            // full-content-width band, doubled rules, plane +1 (§4.5)
            return Rect(l.content.x, Geometry.snapY(l.content.y + l.content.h / 2 - 38), 608.coerceAtMost(l.content.w), 76)
        }
        if (silent) {
            val cx = l.safe.x + l.safe.w / 2
            return Rect(Geometry.snapX(cx - 100), Geometry.snapY(l.content.y + l.content.h / 2 - 28), 200, 56)
        }
        val lines = bodyLines(n, l).size.coerceIn(1, 3)
        val h = 16 + 2 + 6 + lines * 24 + 6 + 2
        val m = l.notificationMax
        return Rect(m.x, m.y, m.w, h)
    }

    private fun maxLinesFor(n: Notice) = if (n.emergency) 1 else 3

    /** Wrapped against the box's fixed width (248 max, §4.5) — NOT fullRect,
     *  whose height depends on this count (that cycle was a real stack
     *  overflow, caught by the first selfcheck run). */
    fun bodyLines(n: Notice, l: Layout): List<String> =
        Wrap.wrap(n.body, fBody, text, l.notificationMax.w - 16)

    /** Paint the current box (full or partially unfurled) over the content. */
    fun paint(g: Gray8, l: Layout, silent: Boolean): Rect? {
        val n = current ?: return null
        val box = boxRect(l, silent) ?: return null
        g.fillRect(box, Level.BG)                        // the hole — level 0 is see-through
        val full = fullRect(n, l, silent)

        if (n.emergency) {
            for (yy in intArrayOf(full.y, full.y + 4, full.bottom - 6, full.bottom - 2)) {
                if (yy < box.bottom) g.fillRect(full.x, yy, full.w, 2, Level.HOT)
            }
            if (box.h >= 30) drawStr(g, full.x + 16, full.y + 12, "EMERGENCY ALERT", Level.HOT, fSmall)
            if (box.h >= 56) drawStr(g, full.x + 16, full.y + 32, n.body, Level.HOT, fBig)
            return box
        }

        val bright = if (focused) 1.0 else 0.6           // dim until focus (§4.5)
        fun lv(base: Int) = (base * bright).toInt()
        // top rule + source line
        g.fillRect(full.x, full.y + 16, full.w, 2, lv(Level.DIM))
        if (box.h >= 16) {
            drawStr(g, full.x + 8, full.y + 2, n.source, lv(Level.HEAD), fSmall)
            if (queue.isNotEmpty()) {
                drawStr(g, full.x + full.w / 2 - 12, full.y + 4, "+${queue.size}", lv(Level.DIM), fTiny)
            }
            val tw = text.measure(n.timeHHMM, fTiny)
            drawStr(g, full.right - 8 - tw, full.y + 4, n.timeHHMM, lv(Level.DIM), fTiny)
        }
        val lines = bodyLines(n, l)
        val visible = maxLinesFor(n)
        for (i in 0 until visible) {
            val idx = scroll + i
            if (idx >= lines.size) break
            val y = full.y + 24 + i * 24
            if (y + 20 <= box.bottom) drawStr(g, full.x + 8, y, lines[idx], lv(Level.BODY), fBody)
        }
        // bottom rule carries scroll position within the message (§4.5)
        if (box.h >= full.h) {
            g.fillRect(full.x, full.bottom - 2, full.w, 2, Level.FAINT)
            if (lines.size > visible) {
                val frac = scroll.toDouble() / (lines.size - visible)
                val span = full.w * visible / lines.size
                g.fillRect(full.x + ((full.w - span) * frac).toInt(), full.bottom - 2, span, 2, lv(Level.HEAD))
            }
        }
        return box
    }

    private fun drawStr(g: Gray8, x: Int, y: Int, s: String, lv: Int, f: FontSpec) {
        text.draw(g, x / 4 * 4, y / 2 * 2, s, f, lv)
    }
}
