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
        /** §16.1 deep link (2026-09-01): tap = commit + activate + open(target)
         *  in [appId]'s window. Null = the window opens at wherever it was. */
        val target: String? = null,
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

    /** What sat under the box, captured before its first paint — the furl
     *  restores from this per frame ("restore the content beneath", §4.5).
     *  Invalidated when the content under the box repaints. */
    private var under: Gray8? = null
    private var underRect: Rect? = null

    /** Capture the under-content from [g] for [box] if not yet captured. Call
     *  BEFORE the first paint of a new box. */
    fun captureUnder(g: Gray8, box: Rect) {
        if (underRect == box && under != null) return
        val u = Gray8(box.w, box.h)
        u.blit(g, box, 0, 0)
        under = u
        underRect = box
    }

    /** The content beneath changed (composeContent while a box is up): the
     *  snapshot no longer restores truthfully. */
    fun invalidateUnder() {
        under = null
        underRect = null
    }

    /** Restore the under-content region [r] (panel coords) into [g]; false if
     *  no valid snapshot covers it. */
    fun restoreUnder(g: Gray8, r: Rect): Boolean {
        val u = under ?: return false
        val ur = underRect ?: return false
        if (!ur.contains(r)) return false
        g.blit(u, Rect(r.x - ur.x, r.y - ur.y, r.w, r.h), r.x, r.y)
        return true
    }

    /** The furl finished: restore the whole covered region and clear the
     *  snapshot. Returns the restored rect for damage, or null when the
     *  snapshot was invalidated (caller repaints instead). */
    fun restoreUnderFinished(g: Gray8): Rect? {
        val u = under
        val ur = underRect
        invalidateUnder()
        if (u == null || ur == null) return null
        g.blit(u, Rect(0, 0, ur.w, ur.h), ur.x, ur.y)
        return ur
    }

    /** Persistence support (§9.1): the queue's contents, and re-adding them at
     *  boot (before the shell loop starts). */
    fun queued(): List<Notice> = queue.toList()

    fun enqueueRestored(n: Notice) {
        queue.addLast(n)
    }

    /** Same-instance session start (the keeper restarts the Shell all day):
     *  the persisted notices about to re-enqueue ARE the in-memory ones —
     *  drop the live set first or restarts duplicate the unread queue. */
    fun resetForRestore() {
        current = null
        furling = false
        unfurl = 0
        clearingRun = false   // a restart mid-clear must not skip the next box's grace (R4#8)
        queue.clear()
        invalidateUnder()
    }

    private val fSmall = FontSpec(Face.SYSTEM, 13, bold = true)
    private val fTiny = FontSpec(Face.SYSTEM, 12, bold = true)
    private val fBody = FontSpec(Face.SYSTEM, 17)
    private val fBig = FontSpec(Face.SYSTEM, 21, bold = true)

    val active: Boolean get() = current != null
    val animating: Boolean get() = (current != null && unfurl < 4) || furling
    /** Mid-furl: the under snapshot is being consumed strip by strip. */
    val furlingOut: Boolean get() = furling
    val queueDepth: Int get() = queue.size

    /** Coalesce by source+thread (§4.5): a new message in the shown thread
     *  replaces the body; in a queued thread it replaces that entry. Returns
     *  the box rect the replaced current occupied (for union damage) or null.
     *  A FURLING current is mid-dismissal: replacing it would silently drop the
     *  notice when the furl completes (review round 1) — queue instead. */
    /** [show] = false queues only (a notice arriving while the switcher wheel
     *  is open waits behind it — HANDOFF.md §8.1 decision 6). */
    fun post(n: Notice, l: Layout, show: Boolean = true): Rect? {
        val cur = current
        if (cur != null && !furling && cur.source == n.source && cur.thread == n.thread) {
            val oldRect = fullRect(cur, l, silent = false)
            current = n
            scroll = 0
            return oldRect
        }
        val i = queue.indexOfFirst { it.source == n.source && it.thread == n.thread }
        // an emergency jumps the queue (§4.5 — it must never wait behind an
        // ordinary box; review 2026-09-01 R2#3: the menu-cancel path requeues
        // the parked ordinary box at the head, and addLast seated it AHEAD of
        // the alert, inverting "the emergency shows now"). One that COALESCES
        // into an existing thread jumps too (R3d#6) — replacing in place kept
        // it parked at the ordinary entry's position.
        when {
            i >= 0 && n.emergency -> { queue.removeAt(i); queue.addFirst(n) }
            i >= 0 -> queue[i] = n
            n.emergency -> queue.addFirst(n)
            else -> queue.addLast(n)
        }
        if (show && current == null) show()
        return null
    }

    /** A furl interrupted by the switcher wheel: the box being dismissed is
     *  dropped as dismissed (never requeued); the next box stays queued until
     *  the wheel closes. The caller restores the under-content first. */
    fun abandonFurl() {
        if (!furling) return
        current = null
        furling = false
        unfurl = 0
        invalidateUnder()
    }

    /** Show the next queued box if nothing is current (leaving silent mode,
     *  or after a requeue). */
    fun showNextIfIdle(): Boolean = if (current == null) show() else false

    /** Entering silent mode: put the current box back at the queue's head,
     *  unread, with no animation — silent shows its own smaller form (§1.5). */
    fun requeueCurrent() {
        val n = current ?: return
        current = null
        furling = false
        unfurl = 0
        invalidateUnder()
        queue.addFirst(n)
    }

    private fun show(): Boolean {
        // a queued notice marked read meanwhile (its app was entered, §4.5) is
        // not shown as new — the next unread one is
        var n = queue.removeFirstOrNull()
        while (n != null && n.read) n = queue.removeFirstOrNull()
        if (n == null) { clearingRun = false; return false }
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

    /** One animation frame. Returns the strip to restore-from-under when a
     *  furl step vacated one (panel coords), via [lastVacated]. */
    var lastVacated: Rect? = null
        private set

    /** Set when a furl just completed — the shell restores the old box's
     *  under-content (restoreUnderFinished) BEFORE painting whatever is next. */
    var furlFinished = false
        private set

    fun consumeFurlFinished(): Boolean {
        val f = furlFinished
        furlFinished = false
        return f
    }

    fun stepUnfurl(l: Layout, silent: Boolean): Boolean {
        lastVacated = null
        if (furling) {
            val before = boxRect(l, silent)
            unfurl--
            if (unfurl <= 0) {
                furling = false
                current = null
                furlFinished = true      // the under snapshot stays valid for the
                show()                   // shell's restore, queue-advance included
                return current != null
            }
            val after = boxRect(l, silent)
            if (before != null && after != null && before.h > after.h) {
                lastVacated = Rect(before.x, after.bottom, before.w, before.bottom - after.bottom)
            }
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
    /** Activating an app marks its notices read (§4.5): the shown box stays
     *  (read), the queued ones LEAVE the queue — `show()` would skip them
     *  anyway, and the badge and the persisted queue must not count them. */
    fun markAppRead(appId: String) {
        current?.let { if (it.appId == appId) it.read = true }
        queue.removeAll { it.appId == appId }
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
            // FITTED: the band is 608 px and the body is external text — an
            // unbounded draw at fBig walks off the band into undamaged pixels
            // (review 2 2026-09-02, the same class as the source line below)
            if (box.h >= 56) Draw.fit(g, text, full.x + 16, full.y + 32,
                Draw.dynamic(text, n.body, fBig), Level.HOT, fBig, full.w - 32, Level.HOT)
            return box
        }

        val bright = if (focused) 1.0 else 0.6           // dim until focus (§4.5)
        fun lv(base: Int) = (base * bright).toInt()
        // top rule + source line
        g.fillRect(full.x, full.y + 16, full.w, 2, lv(Level.DIM))
        if (box.h >= 16) {
            val tw = text.measure(n.timeHHMM, fTiny)
            // the source is a HANDLE and shares its line with the queue badge
            // and the clock: fit it to the room before whichever comes first,
            // or "DAMAGE · compositor" overprints both (every internal source
            // is long enough to reach the badge — review 2 2026-09-02)
            val srcEnd = if (queue.isEmpty()) full.right - 8 - tw - 6 else full.x + full.w / 2 - 16
            Draw.fit(g, text, full.x + 8, full.y + 2, Draw.dynamic(text, n.source, fSmall),
                lv(Level.HEAD), fSmall, srcEnd - (full.x + 8), lv(Level.DIM))
            if (queue.isNotEmpty()) {
                drawStr(g, full.x + full.w / 2 - 12, full.y + 4, "+${queue.size}", lv(Level.DIM), fTiny)
            }
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
        // notice text is external (SMS bodies, session tails): '?'-substitute
        // uncoverable glyphs — silent tofu on glass is the §Type failure
        // (review 2026-09-01 L1)
        text.draw(g, x / 4 * 4, y / 2 * 2, Draw.dynamic(text, s, f), f, lv)
    }
}
