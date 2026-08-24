package wm.damage.core

import java.io.File
import kotlin.test.Test
import wm.damage.core.comp.Compositor
import wm.damage.core.comp.Compositor.PlaneRegion
import wm.damage.core.geom.FidAllocator
import wm.damage.core.geom.FidTracker
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.transport.DisplayOp
import wm.damage.core.transport.Emit
import wm.damage.core.transport.FlushRequest

/** TEMPORARY review harness — per-lens simulation of Compositor op sequences. Deleted after review. */
class ReviewSimTmpTest {
    private val W = 640
    private val H = 480
    private val full = Rect(0, 0, W, H)
    private val out = StringBuilder()
    private fun log(s: String) { out.append(s).append('\n') }

    class Glass {
        val L = Gray8(640, 480)
        val R = Gray8(640, 480)
        val fids = FidAllocator()
        val tracker = FidTracker()
    }

    private fun decode(payload: ByteArray, w: Int, h: Int): Gray8 = Pack.unpack(Zl.decodeCfw(payload, w * h), w, h)

    private fun describe(op: DisplayOp): String = when (op) {
        is DisplayOp.Keyframe -> "KF"
        is DisplayOp.Delta -> "D${op.box}@${op.disparity}"
        is DisplayOp.StereoPair -> "SP[L${op.left} R${op.right}]"
        is DisplayOp.Copy -> "CP${op.src}->${op.dst}@${op.disparity}"
    }

    /** Apply one flush to the per-lens model after validating it through the real emitter. */
    private fun apply(g: Glass, a: Compositor.Assembled, label: String): Int {
        val window = if (a.wide) 1 else 3
        try {
            Emit.encode(FlushRequest(a.ops, a.epoch, label, a.wide), g.fids, g.tracker, window)
        } catch (e: Exception) {
            log("  !! EMIT REJECTED [$label]: ${e.message}")
            return -1
        }
        var fids = 0
        for (op in a.ops) when (op) {
            is DisplayOp.Keyframe -> {
                val k = decode(op.payload, W, H)
                g.L.blit(k, full, 0, 0); g.R.blit(k, full, 0, 0)
            }
            is DisplayOp.Delta -> {
                fids++
                val p = decode(op.payload, op.box.w, op.box.h)
                val src = Rect(0, 0, op.box.w, op.box.h)
                g.L.blit(p, src, op.box.x - op.disparity, op.box.y)
                g.R.blit(p, src, op.box.x + op.disparity, op.box.y)
            }
            is DisplayOp.StereoPair -> {
                fids++
                val p = decode(op.payload, op.left.w, op.left.h)
                val src = Rect(0, 0, op.left.w, op.left.h)
                g.L.blit(p, src, op.left.x, op.left.y)
                g.R.blit(p, src, op.right.x, op.right.y)
            }
            is DisplayOp.Copy -> {
                for ((lens, sgn) in listOf(g.L to -1, g.R to 1)) {
                    val s = op.src.translate(sgn * op.disparity, 0)
                    val d = op.dst.translate(sgn * op.disparity, 0)
                    val tmp = Gray8(s.w, s.h)
                    tmp.blit(lens, s, 0, 0)
                    lens.blit(tmp, Rect(0, 0, s.w, s.h), d.x, d.y)
                }
            }
        }
        return fids
    }

    /** Ground truth. Every pixel takes the disparity of the LAST region containing it; regions
     *  paint far to near (nearest last) at their shift; black where nothing renders. With
     *  [transparent] the plane-0 REMAINDER (pixels in no region) is painted first at nominal and
     *  does not occlude — DESIGN.md §3.3's "the inset is the shift budget" reading. */
    private fun truth(c: Gray8, planes: List<PlaneRegion>, transparent: Boolean): Pair<Gray8, Gray8> {
        val disp = IntArray(W * H)
        val inRegion = BooleanArray(W * H)
        for (p in planes) for (y in p.rect.y until p.rect.bottom) for (x in p.rect.x until p.rect.right) {
            disp[y * W + x] = p.disparity; inRegion[y * W + x] = true
        }
        val L = Gray8(W, H); val R = Gray8(W, H)
        if (transparent) for (y in 0 until H) for (x in 0 until W) if (!inRegion[y * W + x]) {
            val v = Pack.level(c[x, y]) * 17; L[x, y] = v; R[x, y] = v
        }
        for (d in disp.toSet().sortedDescending()) for (y in 0 until H) for (x in 0 until W) {
            val i = y * W + x
            if (disp[i] != d || (transparent && !inRegion[i])) continue
            val v = Pack.level(c[x, y]) * 17
            L[x - d, y] = v
            R[x + d, y] = v
        }
        return L to R
    }

    /** Mismatch clusters: mark 4x2 cells, connect cells within 2 cells horizontally / 1 vertically
     *  (the fill has 1/16 coincidence holes), report bounding boxes + pixel counts. */
    private fun diff(a: Gray8, b: Gray8): List<String> {
        val cw = W / 4; val ch = H / 2
        val mark = BooleanArray(cw * ch)
        val cnt = IntArray(cw * ch)
        var total = 0
        for (y in 0 until H) for (x in 0 until W) if (a[x, y] != b[x, y]) {
            val i = (y / 2) * cw + x / 4; mark[i] = true; cnt[i]++; total++
        }
        val seen = BooleanArray(cw * ch)
        val res = ArrayList<Pair<Int, String>>()
        for (start in 0 until cw * ch) {
            if (!mark[start] || seen[start]) continue
            var x0 = cw; var x1 = -1; var y0 = ch; var y1 = -1; var px = 0
            val stack = ArrayDeque<Int>(); stack.add(start); seen[start] = true
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                val cx = i % cw; val cy = i / cw
                x0 = minOf(x0, cx); x1 = maxOf(x1, cx); y0 = minOf(y0, cy); y1 = maxOf(y1, cy); px += cnt[i]
                for (dy in -1..1) for (dx in -2..2) {
                    val nx = cx + dx; val ny = cy + dy
                    if (nx !in 0 until cw || ny !in 0 until ch) continue
                    val j = ny * cw + nx
                    if (mark[j] && !seen[j]) { seen[j] = true; stack.add(j) }
                }
            }
            res.add(px to "rows [${y0 * 2},${(y1 + 1) * 2}) cols [${x0 * 4},${(x1 + 1) * 4}) ($px px)")
        }
        return res.sortedByDescending { it.first }.map { it.second }
    }

    private fun check(g: Glass, comp: Compositor, planes: List<PlaneRegion>, label: String): Boolean {
        val (ol, orr) = truth(comp.composed, planes, transparent = false)
        val (tl, tr) = truth(comp.composed, planes, transparent = true)
        val dol = diff(g.L, ol); val dor = diff(g.R, orr)
        val dtl = diff(g.L, tl); val dtr = diff(g.R, tr)
        val opaqueOk = dol.isEmpty() && dor.isEmpty()
        val transOk = dtl.isEmpty() && dtr.isEmpty()
        if (opaqueOk || transOk) {
            log("  OK   $label" + (if (!opaqueOk) "  [fails OPAQUE-remainder truth only: L${dol.size}/R${dor.size} clusters]" else "") +
                (if (!transOk) "  [fails TRANSPARENT-remainder truth only: L${dtl.size}/R${dtr.size} clusters]" else ""))
            if (!opaqueOk) { for (x in dol.take(4)) log("       opaque L $x"); for (x in dor.take(4)) log("       opaque R $x") }
            if (!transOk) { for (x in dtl.take(4)) log("       transp L $x"); for (x in dtr.take(4)) log("       transp R $x") }
            return true
        }
        log("  FAIL(both truths) $label")
        for (x in dol.take(8)) log("       opaque L $x"); if (dol.size > 8) log("       opaque L ... ${dol.size}")
        for (x in dor.take(8)) log("       opaque R $x"); if (dor.size > 8) log("       opaque R ... ${dor.size}")
        for (x in dtl.take(8)) log("       transp L $x"); if (dtl.size > 8) log("       transp L ... ${dtl.size}")
        for (x in dtr.take(8)) log("       transp R $x"); if (dtr.size > 8) log("       transp R ... ${dtr.size}")
        return false
    }

    private fun drain(g: Glass, comp: Compositor, planes: List<PlaneRegion>, label: String,
                      budget: Int = Geometry.rectBudget(3), verbose: Boolean = false, checkEach: Boolean = false): Int {
        var n = 0
        while (comp.hasPending || comp.needsKeyframe) {
            val a = comp.assembleFlush(budget) ?: run { log("    (assembleFlush returned null with pending work)"); null } ?: break
            n++
            val fids = apply(g, a, "$label#$n")
            log("    flush $n: ${a.ops.size} ops, $fids fids, wide=${a.wide}, kf=${a.keyframe}, explicit=${a.explicit.size}" +
                (if (verbose) "\n      " + a.ops.joinToString("\n      ") { describe(it) } else ""))
            if (checkEach) check(g, comp, planes, "$label after flush $n")
            if (n > 25) { log("  !! drain did not converge"); break }
        }
        return n
    }

    /** Shift-sensitive but compressible fill: one random level per 4-px column block per 16-row
     *  band, keyed to absolute coordinates and the seed. Any horizontal shift on the 4 px ladder
     *  lands in a different block (mismatch probability 15/16 per block). */
    private fun randomize(c: Gray8, r: Rect, seed: Long) {
        for (y in r.y until r.bottom) for (x in r.x until r.right) {
            val h = (seed * 1_000_003L + (x / 4) * 7919L + (y / 16) * 104_729L)
            val v = ((h xor (h ushr 17)) * 0x2545F4914F6CDD1DL ushr 40).toInt() and 0xF
            c[x, y] = v * 17
        }
    }

    private fun restore(c: Gray8, base: Gray8, r: Rect) = c.blit(base, r, r.x, r.y)

    /** The shell's real surface shape: everything random, but the 16-px insets beside the content
     *  band are BLACK (composeFullSurface fills BG; content is inset). */
    private fun fillBase(c: Gray8, seed: Long) {
        randomize(c, full, seed)
        c.fillRect(Rect(0, 34, 16, 416), 0)
        c.fillRect(Rect(624, 34, 16, 416), 0)
    }

    private fun PR(r: Rect, d: Int) = PlaneRegion(r, d)

    @Test
    fun review() {
        val l = Layout()
        val content = l.content; val lens = l.lens; val panel = l.switcherPanel
        val centre = Rect(panel.x, panel.y + 44, panel.w, 88)
        val box = Rect(l.notificationMax.x, l.notificationMax.y, l.notificationMax.w, 104)
        val emerg = Rect(content.x, Geometry.snapY(content.y + content.h / 2 - 38), 608, 76)
        val rail = l.rail
        val bandAbove = Rect(content.x, content.y + Layout.CONTENT_PAD, content.w - Layout.RAIL_W, l.rowsAbove * Layout.ROW_H)
        val bandBelow = Rect(content.x, lens.bottom, content.w - Layout.RAIL_W, l.rowsBelow * Layout.ROW_H)
        log("geometry: content=$content lens=$lens panel=$panel centre=$centre box=$box emerg=$emerg rail=$rail above=$bandAbove below=$bandBelow")

        fun maps(d: Int): List<Pair<String, List<PlaneRegion>>> = listOf(
            "doc" to listOf(PR(content, d)),
            "list" to listOf(PR(content, d), PR(lens, 0)),
            "switcher" to listOf(PR(content, d), PR(lens, 0), PR(panel, d), PR(centre, 0)),
            "list+box@d" to listOf(PR(content, d), PR(lens, 0), PR(box, d)),
            "list+box@0" to listOf(PR(content, d), PR(lens, 0), PR(box, 0)),
            "list+emerg@d" to listOf(PR(content, d), PR(lens, 0), PR(emerg, d)),
            "list+emerg@-4" to listOf(PR(content, d), PR(lens, 0), PR(emerg, -4)),
            "switcher+box@d" to listOf(PR(content, d), PR(lens, 0), PR(panel, d), PR(centre, 0), PR(box, d)),
            "switcher+box@0" to listOf(PR(content, d), PR(lens, 0), PR(panel, d), PR(centre, 0), PR(box, 0)),
            "doc+box@d" to listOf(PR(content, d), PR(box, d)),
            "doc+box@0" to listOf(PR(content, d), PR(box, 0)),
            "doc+emerg@-4" to listOf(PR(content, d), PR(emerg, -4)),
        )
        fun map(d: Int, name: String) = maps(d).first { it.first == name }.second

        // ---------------------------------------------------------------- 1. keyframes
        log("\n===== PART 1: KEYFRAME per-lens exactness =====")
        for (d in listOf(8, 12, 16)) for ((name, m) in maps(d)) {
            val comp = Compositor(); fillBase(comp.composed, 1)
            comp.planes = m; comp.requestKeyframe()
            val g = Glass()
            log("KEYFRAME d=$d $name")
            drain(g, comp, m, "kf-$d-$name", verbose = (d == 8), checkEach = true)
        }

        // ---------------------------------------------------------------- 2. transitions
        log("\n===== PART 2: PLANE TRANSITIONS =====")
        fun transition(name: String, d: Int, oldMap: List<PlaneRegion>, newMap: List<PlaneRegion>,
                       planesFirst: Boolean = true, mutate: (Compositor, Gray8) -> Unit) {
            val comp = Compositor(); fillBase(comp.composed, 2)
            val base = comp.composed.copy()
            comp.planes = oldMap; comp.requestKeyframe()
            val g = Glass()
            drain(g, comp, oldMap, "t-$name-before")
            val okBefore = check(g, comp, oldMap, "$name d=$d BEFORE (keyframe of old map)")
            log("TRANSITION $name d=$d")
            if (planesFirst) { comp.planes = newMap; mutate(comp, base) } else { mutate(comp, base); comp.planes = newMap }
            drain(g, comp, newMap, "t-$name", verbose = true, checkEach = true)
            if (!okBefore) log("  (note: BEFORE state was already inexact)")
        }
        for (d in listOf(8, 12, 16)) {
            transition("box-arrives", d, map(d, "list"), map(d, "list+box@d")) { c, _ -> randomize(c.composed, box, 3); c.damage(box) }
            transition("focus-step", d, map(d, "list+box@d"), map(d, "list+box@0")) { c, _ -> randomize(c.composed, box, 4); c.damage(box) }
            transition("furl-finish-focused", d, map(d, "list+box@0"), map(d, "list"), planesFirst = false) { c, b -> restore(c.composed, b, box); c.damage(box) }
            transition("furl-finish-unfocused", d, map(d, "list+box@d"), map(d, "list"), planesFirst = false) { c, b -> restore(c.composed, b, box); c.damage(box) }
            transition("emerg-arrives", d, map(d, "list"), map(d, "list+emerg@d")) { c, _ -> randomize(c.composed, emerg, 5); c.damage(emerg) }
            transition("emerg-focus", d, map(d, "list+emerg@d"), map(d, "list+emerg@-4")) { c, _ -> randomize(c.composed, emerg, 6); c.damage(emerg) }
            transition("emerg-furl-finish", d, map(d, "list+emerg@-4"), map(d, "list"), planesFirst = false) { c, b -> restore(c.composed, b, emerg); c.damage(emerg) }
            transition("switcher-open", d, map(d, "list"), map(d, "switcher")) { c, _ -> randomize(c.composed, panel, 7); c.damage(panel) }
            transition("switcher-close", d, map(d, "switcher"), map(d, "list")) { c, b -> restore(c.composed, b, panel); c.damage(content) }
            transition("doc-box-arrives", d, map(d, "doc"), map(d, "doc+box@d")) { c, _ -> randomize(c.composed, box, 3); c.damage(box) }
            transition("doc-focus-step", d, map(d, "doc+box@d"), map(d, "doc+box@0")) { c, _ -> randomize(c.composed, box, 4); c.damage(box) }
            transition("doc-furl-finish", d, map(d, "doc+box@0"), map(d, "doc"), planesFirst = false) { c, b -> restore(c.composed, b, box); c.damage(box) }
            transition("box-arrives-in-switcher", d, map(d, "switcher"), map(d, "switcher+box@d")) { c, _ -> randomize(c.composed, box, 3); c.damage(box) }
            transition("depth-to-0-list+box@0", d, map(d, "list+box@0"), emptyList()) { c, _ -> c.damage(content); c.damage(box) }
            transition("depth-from-0-list", d, emptyList(), map(d, "list")) { c, _ -> c.damage(content) }
        }
        for ((d0, d1) in listOf(8 to 12, 12 to 8, 8 to 16, 16 to 8, 12 to 16)) {
            for (name in listOf("list", "doc", "list+box@0", "list+box@d", "switcher", "list+emerg@-4")) {
                transition("depth-$name-$d0->$d1", d1, map(d0, name), map(d1, name)) { c, _ -> c.damage(content); c.damage(box) }
            }
        }
        // chained lifecycle at d=8: arrive -> focus -> furl, checking accumulated state
        run {
            val d = 8
            val comp = Compositor(); fillBase(comp.composed, 9)
            val base = comp.composed.copy()
            comp.planes = map(d, "list"); comp.requestKeyframe()
            val g = Glass()
            log("CHAIN d=8 list -> box arrives -> focus -> furl finish")
            drain(g, comp, map(d, "list"), "chain0")
            check(g, comp, map(d, "list"), "chain: keyframe")
            comp.planes = map(d, "list+box@d"); randomize(comp.composed, box, 10); comp.damage(box)
            drain(g, comp, map(d, "list+box@d"), "chain1"); check(g, comp, map(d, "list+box@d"), "chain: box arrived")
            comp.planes = map(d, "list+box@0"); randomize(comp.composed, box, 11); comp.damage(box)
            drain(g, comp, map(d, "list+box@0"), "chain2"); check(g, comp, map(d, "list+box@0"), "chain: focused")
            restore(comp.composed, base, box); comp.damage(box); comp.planes = map(d, "list")
            drain(g, comp, map(d, "list"), "chain3"); check(g, comp, map(d, "list"), "chain: furled")
            // a list slide afterwards repaints both bands + lens: does that heal everything?
            randomize(comp.composed, bandAbove, 12); comp.damage(bandAbove)
            randomize(comp.composed, bandBelow, 13); comp.damage(bandBelow)
            randomize(comp.composed, lens, 14); comp.damage(lens)
            drain(g, comp, map(d, "list"), "chain4"); check(g, comp, map(d, "list"), "chain: after full band+lens repaint")
        }

        // ---------------------------------------------------------------- 3. damage path
        log("\n===== PART 3: DAMAGE FLUSHES after an exact keyframe =====")
        for (d in listOf(8, 16)) for (name in listOf("list", "list+box@0", "switcher", "doc+box@0", "list+emerg@-4")) {
            val m = map(d, name)
            val comp = Compositor(); fillBase(comp.composed, 20)
            comp.planes = m; comp.requestKeyframe()
            val g = Glass()
            drain(g, comp, m, "dmg-$name-kf")
            check(g, comp, m, "damage-path d=$d $name: keyframe")
            val cases = listOf(
                "rail" to rail,
                "bandAbove" to bandAbove,
                "bandBelow" to bandBelow,
                "lens" to lens,
                "box" to box,
                "content-left-of-box-rows190" to Rect(16, 190, 180, 20),
                "content-right-of-box-rows274" to Rect(444, 274, 180, 20),
                "content-left-of-centre" to Rect(16, 198, 184, 12),
                "whole content" to content,
            )
            for ((cn, r) in cases) {
                randomize(comp.composed, r, 21 + cn.hashCode().toLong()); comp.damage(r)
                log("  damage $cn $r")
                drain(g, comp, m, "dmg-$name-$cn", verbose = true)
                check(g, comp, m, "damage-path d=$d $name: after $cn")
            }
        }

        // ---------------------------------------------------------------- 4. leftovers vs copies / damage
        log("\n===== PART 4: KEYFRAME LEFTOVERS interacting with copies and damage =====")
        for (band in listOf("below" to bandBelow, "above" to bandAbove)) {
            val d = 8
            val m = map(d, "list+box@d")
            val comp = Compositor(); fillBase(comp.composed, 30)
            comp.planes = m; comp.requestKeyframe()
            val g = Glass()
            val a1 = comp.assembleFlush(Geometry.rectBudget(3))!!
            apply(g, a1, "lo1")
            log("leftover scenario (${band.first}): first flush ${a1.ops.size} ops, explicit=${a1.explicit.size}; queued=${comp.hasPending}")
            check(g, comp, m, "after partial keyframe (expected transient mismatch)")
            // a list slide step: shift the band up by 32 (declareShift) + paint the entering strip
            val r = band.second
            val s = 32
            val src = Rect(r.x, r.y + s, r.w, r.h - s); val dst = Rect(r.x, r.y, r.w, r.h - s)
            val tmp = Gray8(src.w, src.h); tmp.blit(comp.composed, src, 0, 0); comp.composed.blit(tmp, Rect(0, 0, src.w, src.h), dst.x, dst.y)
            comp.declareShift(src, dst)
            val strip = Rect(r.x, r.bottom - s, r.w, s)
            randomize(comp.composed, strip, 31); comp.damage(strip)
            drain(g, comp, m, "lo-${band.first}", verbose = true)
            check(g, comp, m, "leftovers + slide (${band.first}) drained")
            // then a plain damage inside the band: does the hash filter now hide the divergence?
            randomize(comp.composed, Rect(r.x, r.y, r.w, 32), 32); comp.damage(Rect(r.x, r.y, r.w, 32))
            drain(g, comp, m, "lo-${band.first}-2", verbose = true)
            check(g, comp, m, "leftovers + slide + later damage (${band.first})")
        }
        run {
            // damage arriving while leftovers drain, inside a piece whose explicit delta is still queued
            val d = 8
            val m = map(d, "list+box@d")
            val comp = Compositor(); fillBase(comp.composed, 40)
            comp.planes = m; comp.requestKeyframe()
            val g = Glass()
            val a1 = comp.assembleFlush(Geometry.rectBudget(3))!!
            apply(g, a1, "ld1")
            randomize(comp.composed, Rect(16, 300, 300, 40), 41); comp.damage(Rect(16, 300, 300, 40))
            randomize(comp.composed, Rect(16, 60, 300, 40), 42); comp.damage(Rect(16, 60, 300, 40))
            log("damage-during-leftovers: queued explicit before damage flush")
            drain(g, comp, m, "ld", verbose = true, checkEach = true)
            check(g, comp, m, "damage during leftovers drained")
        }

        // ---------------------------------------------------------------- 5. rollback sequencing
        log("\n===== PART 5: ROLLBACK =====")
        run {
            val d = 8
            val m = map(d, "list+box@d")
            val comp = Compositor(); fillBase(comp.composed, 50)
            comp.planes = m; comp.requestKeyframe()
            val g = Glass()
            val f1 = comp.assembleFlush(Geometry.rectBudget(3))!!
            val f2 = comp.assembleFlush(Geometry.rectBudget(3))!!
            log("double-failure: F1 ${f1.ops.size} ops (explicit ${f1.explicit.size}), F2 ${f2.ops.size} ops (explicit ${f2.explicit.size}): " +
                f2.ops.joinToString(" ") { describe(it) })
            // both lost (link blip). Meanwhile content changed inside a piece F2 repaints:
            val target = f2.ops.filterIsInstance<DisplayOp.Delta>().first().box
            val inner = Rect(target.x + 4, target.y + 2, minOf(40, target.w - 8), minOf(20, target.h - 4))
            randomize(comp.composed, inner, 51); comp.damage(inner)
            comp.rollback(f1)
            val f3 = comp.assembleFlush(Geometry.rectBudget(3))!!
            log("  F3 after rollback(F1): kf=${f3.keyframe} ops=${f3.ops.size} explicit=${f3.explicit.size}")
            apply(g, f3, "f3")
            comp.rollback(f2)
            log("  rollback(F2) re-queued ${f2.explicit.size} explicit ops; needsKeyframe=${comp.needsKeyframe}")
            drain(g, comp, m, "rb", verbose = true)
            check(g, comp, m, "double failure: F1,F2 lost, content changed in $inner, F3 keyframe, then F2's re-queued ops")
            // does a later damage in the same rect repair it?
            comp.damage(inner)
            val a = comp.assembleFlush(Geometry.rectBudget(3))
            log("  re-damage of $inner assembles to: ${a?.ops?.joinToString { describe(it) } ?: "null (hash-filtered)"}")
        }
        run {
            // single failure of the leftover flush; content changed inside one of its pieces before rollback
            val d = 8
            val m = map(d, "list+box@d")
            val comp = Compositor(); fillBase(comp.composed, 60)
            comp.planes = m; comp.requestKeyframe()
            val g = Glass()
            val f1 = comp.assembleFlush(Geometry.rectBudget(3))!!
            apply(g, f1, "s1")
            val f2 = comp.assembleFlush(Geometry.rectBudget(3))!!
            val target = f2.ops.filterIsInstance<DisplayOp.Delta>().first().box
            val inner = Rect(target.x + 4, target.y + 2, minOf(40, target.w - 8), minOf(20, target.h - 4))
            randomize(comp.composed, inner, 61); comp.damage(inner)
            comp.rollback(f2)
            drain(g, comp, m, "srb", verbose = true)
            check(g, comp, m, "single failure of leftover flush, content changed in $inner")
        }
        run {
            // rollback of a damage flush that carried copies
            val d = 8
            val m = map(d, "list")
            val comp = Compositor(); fillBase(comp.composed, 70)
            comp.planes = m; comp.requestKeyframe()
            val g = Glass()
            drain(g, comp, m, "cp0")
            val r = bandBelow; val s = 32
            val src = Rect(r.x, r.y + s, r.w, r.h - s); val dst = Rect(r.x, r.y, r.w, r.h - s)
            val tmp = Gray8(src.w, src.h); tmp.blit(comp.composed, src, 0, 0); comp.composed.blit(tmp, Rect(0, 0, src.w, src.h), dst.x, dst.y)
            comp.declareShift(src, dst)
            val strip = Rect(r.x, r.bottom - s, r.w, s)
            randomize(comp.composed, strip, 71); comp.damage(strip)
            val f = comp.assembleFlush(Geometry.rectBudget(3))!!
            log("copy flush: " + f.ops.joinToString(" ") { describe(it) })
            comp.rollback(f)
            drain(g, comp, m, "cp1", verbose = true)
            check(g, comp, m, "copy flush lost + rollback + re-flush")
        }

        // ---------------------------------------------------------------- 6. budget mechanics
        log("\n===== PART 6: BUDGET / DEFERRAL =====")
        run {
            val m0 = map(8, "list+box@0"); val m1 = map(16, "list+box@0")
            val comp = Compositor(); fillBase(comp.composed, 80)
            comp.planes = m0; comp.requestKeyframe()
            val g = Glass()
            drain(g, comp, m0, "bd0")
            comp.planes = m1; comp.damage(content); comp.damage(box)
            randomize(comp.composed, rail, 81); comp.damage(rail)
            randomize(comp.composed, Rect(16, 60, 100, 20), 82); comp.damage(Rect(16, 60, 100, 20))
            log("depth 8->16 with rail + small damage in the same frame")
            drain(g, comp, m1, "bd", verbose = true, checkEach = true)
        }
        run {
            // small pipelined budget (window 3 => 5) with damage spanning many planes
            val m = map(8, "switcher+box@0")
            val comp = Compositor(); fillBase(comp.composed, 90)
            comp.planes = m; comp.requestKeyframe()
            val g = Glass()
            drain(g, comp, m, "sb0")
            check(g, comp, m, "switcher+box@0 keyframe")
            randomize(comp.composed, content, 91); comp.damage(content)
            log("full-content damage under switcher+box@0 at budget 5")
            drain(g, comp, m, "sb", verbose = true, checkEach = true)
        }
        run {
            // many explicit ops queued (seam pairs) + 2-piece damage: does it escalate to a keyframe?
            val m0 = map(8, "switcher+box@0"); val m1 = map(16, "switcher+box@0")
            val comp = Compositor(); fillBase(comp.composed, 100)
            comp.planes = m0; comp.requestKeyframe()
            val g = Glass()
            drain(g, comp, m0, "es0")
            comp.planes = m1
            randomize(comp.composed, Rect(16, 60, 40, 20), 101); comp.damage(Rect(16, 60, 40, 20))
            randomize(comp.composed, Rect(16, 400, 40, 20), 102); comp.damage(Rect(16, 400, 40, 20))
            log("depth 8->16 on switcher+box@0 + two small damages")
            drain(g, comp, m1, "es", verbose = true, checkEach = true)
        }

        // ---------------------------------------------------------------- 7. extra
        log("\n===== PART 7: ROLLBACK AFTER A PLANE CHANGE / 28-LEFTOVER DEFERRAL / REACHABLE FULL-CONTENT DAMAGE =====")
        run {
            // F1 ok; F2 (leftovers) in flight and FAILS; before its failure arrives the box takes focus
            // (plane change + repaint) and F4 goes out. Then rollback(F2) re-queues F2's ops.
            val m0 = map(8, "list+box@d"); val m1 = map(8, "list+box@0")
            val comp = Compositor(); fillBase(comp.composed, 110)
            comp.planes = m0; comp.requestKeyframe()
            val g = Glass()
            val f1 = comp.assembleFlush(Geometry.rectBudget(3))!!; apply(g, f1, "r1")
            val f2 = comp.assembleFlush(Geometry.rectBudget(3))!!
            log("rollback-after-focus: F2 = " + f2.ops.joinToString(" ") { describe(it) })
            comp.planes = m1; randomize(comp.composed, box, 111); comp.damage(box)
            val f4 = comp.assembleFlush(Geometry.rectBudget(3))!!; apply(g, f4, "r4")
            log("  F4 = " + f4.ops.joinToString(" ") { describe(it) })
            comp.rollback(f2)
            drain(g, comp, m1, "r5", verbose = true)
            check(g, comp, m1, "F2 lost after the focus step's flush went out")
        }
        run {
            // same, but F2's failure arrives BEFORE the focus step's flush is assembled (wide serialization)
            val m0 = map(8, "list+box@d"); val m1 = map(8, "list+box@0")
            val comp = Compositor(); fillBase(comp.composed, 120)
            comp.planes = m0; comp.requestKeyframe()
            val g = Glass()
            val f1 = comp.assembleFlush(Geometry.rectBudget(3))!!; apply(g, f1, "q1")
            val f2 = comp.assembleFlush(Geometry.rectBudget(3))!!
            comp.planes = m1; randomize(comp.composed, box, 121); comp.damage(box)
            comp.rollback(f2)
            drain(g, comp, m1, "q3", verbose = true)
            check(g, comp, m1, "F2 lost, rollback before the focus step's flush")
        }
        run {
            // F2 lost; meanwhile the box FURLS (region removed) — stale box@8 ops re-queued
            val m0 = map(8, "list+box@d"); val m1 = map(8, "list")
            val comp = Compositor(); fillBase(comp.composed, 130)
            val base = comp.composed.copy()
            comp.planes = m0; comp.requestKeyframe()
            val g = Glass()
            val f1 = comp.assembleFlush(Geometry.rectBudget(3))!!; apply(g, f1, "u1")
            val f2 = comp.assembleFlush(Geometry.rectBudget(3))!!
            restore(comp.composed, base, box); comp.damage(box); comp.planes = m1
            val f4 = comp.assembleFlush(Geometry.rectBudget(3))!!; apply(g, f4, "u4")
            comp.rollback(f2)
            drain(g, comp, m1, "u5", verbose = true)
            check(g, comp, m1, "F2 lost after a furl-finish flush went out")
        }
        run {
            // 28 leftovers: damage must defer behind two full explicit flushes and still land
            val m = map(8, "switcher+box@d")
            val comp = Compositor(); fillBase(comp.composed, 140)
            comp.planes = m; comp.requestKeyframe()
            val g = Glass()
            val f1 = comp.assembleFlush(Geometry.rectBudget(3))!!; apply(g, f1, "d1")
            randomize(comp.composed, Rect(16, 60, 100, 20), 141); comp.damage(Rect(16, 60, 100, 20))
            log("28-leftover deferral: damage (16,60 100x20) queued behind ${f1.explicit.size} + 28 explicit")
            drain(g, comp, m, "d2", verbose = true, checkEach = true)
        }
        run {
            // reachable: switcher open + box arrived, then settlePreview repaints all of content
            val m = map(8, "switcher+box@d")
            val comp = Compositor(); fillBase(comp.composed, 150)
            comp.planes = m; comp.requestKeyframe()
            val g = Glass()
            drain(g, comp, m, "p0")
            check(g, comp, m, "switcher+box@d keyframe")
            randomize(comp.composed, content, 151); comp.damage(content)
            randomize(comp.composed, panel, 152); comp.damage(panel)
            randomize(comp.composed, box, 153); comp.damage(box)
            log("switcher+box@d: content + panel + box damage at budget 5")
            drain(g, comp, m, "p1", verbose = true, checkEach = true)
        }
        run {
            // keyframe payload cap: full-panel incompressible content under a plane map
            val comp = Compositor()
            val rnd = java.util.Random(7)
            for (i in comp.composed.pix.indices) comp.composed.pix[i] = (rnd.nextInt(16) * 17).toByte()
            comp.planes = map(8, "list"); comp.requestKeyframe()
            val a = comp.assembleFlush(Geometry.rectBudget(3))!!
            val kf = a.ops.filterIsInstance<DisplayOp.Keyframe>().first()
            log("keyframe cap: noise keyframe payload = ${kf.payload.size} B; ops in flush = ${a.ops.size}; " +
                "len16 cap 65535, MODE8_MAX ${Geometry.MODE8_MAX}")
            try { Emit.encode(FlushRequest(a.ops, a.epoch, "cap", a.wide), FidAllocator(), FidTracker(), 1); log("  emitted OK") }
            catch (e: Exception) { log("  EMIT: ${e.message}") }
            // and a bare keyframe of the same content (empty plane map)
            val comp2 = Compositor(); comp.composed.pix.copyInto(comp2.composed.pix); comp2.requestKeyframe()
            val b = comp2.assembleFlush(Geometry.rectBudget(3))!!
            try { Emit.encode(FlushRequest(b.ops, b.epoch, "cap2", b.wide), FidAllocator(), FidTracker(), 1); log("  bare keyframe (${b.ops.size} ops) emitted OK") }
            catch (e: Exception) { log("  bare keyframe EMIT: ${e.message}") }
        }

        File("/tmp/claude-1000/-home-user-damagewm/6fd3c0e6-76a7-45d6-9a1e-90a7608b69ac/scratchpad/review-sim.txt").writeText(out.toString())
    }
}
