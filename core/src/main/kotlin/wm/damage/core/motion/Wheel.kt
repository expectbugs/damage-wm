package wm.damage.core.motion

import wm.damage.core.geom.Layout
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Level
import wm.damage.core.shell.MainStaging
import wm.damage.core.shell.Switcher
import wm.damage.core.shell.TextureCacheImage
import wm.damage.core.sim.MotionSim
import wm.damage.core.wire.CfwModes

/**
 * C3 package DW, stage DW-0 (`private/c3-design/C3-DW-spec.md` §2, §3.1; `C3-decisions.md` part BD): the switcher's wheel
 * as the glasses draw it — "a proper animated wheel or gear type thingy" (`MOTION.md` §7) — a drum that turns one detent
 * per notch between today's rest pictures (O1 (A)). Context for the reader: Adam's own glasses on his own firmware fork
 * (display-rendering work on a personal device); nothing here sends, binds or places anything (stages DW-1 to DW-3).
 *
 * **The records** ([Block]): one cell per window — its centre form (rows `0 until hC`, `Switcher.paint`'s own drawing of
 * the centre band) stacked on its neighbour form (rows `hC until hC + hN`, the neighbour row's box), painted by the
 * switcher's own painters through the caller's relay ([cell]); the rule record (240 × 2, every pixel DIM); the two ease
 * tables of the swapping cells; the item table (one off4 per entry, the switcher's order); the two programs. The layout
 * runs up from a base the caller gives and assumes no region (BD: the block is Main's region's first tenant from its
 * end — the shell places it and keeps it current, C3 DW-1a: `shell/WheelResident.kt`).
 *
 * **The programs** ([Block.down], [Block.up]; `FIRMWARE.md` §7.2 version 2, bound, no OWN): seven per-lens tracks over one
 * area pair — the panel and the margin of its disparity d on the far side of each lens (left `[p.x − d, p.right)`, right
 * `[p.x, p.right + d)`, rows inside the outer rules) — every DRAW of a record, transparent, width unscaled: the row
 * leaving squashes into the outer rule and fades (its track's level-0 backdrop paints the whole area every step), the
 * row entering grows out of the other one, the centre and the neighbour arriving change form at half the travel (the
 * first step whose ease-out reaches 2048, both showing the neighbour form's own brightness there: [LEVEL_SWAP_CENTRE],
 * [LEVEL_SWAP_NEIGHBOUR]) and move on one shared ease-out, and the
 * three inner rules slide. Every mover's centre goes the same fraction of its travel at every step (what moves, moves
 * together); END is the switcher's paint at the new cursor on each lens, and the start pose its paint at the old one
 * (`WheelTest`). Parameters: four kind-1 rules ([rules]) on a ring list. `Switcher` = None: the same tracks in one step.
 *
 * **Timing** ([timing]): the fast DRAW path's fit (C3 FF, `MainStaging.fastStepMs`, modeled) on the pixels a step writes
 * and the bytes it decodes, then K1-1's rule (`MainStaging.timingOf`) at the Scroll tier's length and frames.
 */
object Wheel {
    /** The level a swapping cell's CENTRE form is scaled to at the swap: the neighbour form's own level, so the picture
     *  that takes over there is as bright as the one that leaves (decisions BK (3), from the lead's look at DW-0's strips:
     *  O9's first value, 6 for both forms, showed a centre at about 6 beside a neighbour at about 1.6 — its level-4 pixels
     *  scaled by 6/15 — so the leaving item dropped and the arriving one faded out and then jumped in one step). */
    const val LEVEL_SWAP_CENTRE = Switcher.NEIGHBOUR_LEVEL
    /** The level a swapping cell's NEIGHBOUR form is scaled to at the swap: unscaled, its pixels are at their own level. */
    const val LEVEL_SWAP_NEIGHBOUR = 15
    /** The rules' level: `Level.DIM` (the switcher's inner rules). */
    const val RULE_LEVEL = 3
    /** DRAW opt bit 4: a source pixel 0 is not drawn (§5.4's LUT), so the cells and rules never clear one another. */
    private const val TRANSPARENT = 0x10

    /** The wheel's geometry at rest on one layout and face (the DW spec §2.1's notation): the [panel], the disparity [d]
     *  of its neighbour rows (the stage-5 margin), the centre band `[bandTop, bandBot)` (its rules at both, two rows
     *  each), the neighbour form's box height [hN] and the neighbour rows' tops [yA] (above) and [yB] (below). */
    class Geo(val panel: Rect, val d: Int, val bandTop: Int, val bandBot: Int, val hN: Int, val yA: Int, val yB: Int) {
        /** The centre form's rows: `[bandTop + 2, bandBot)`. */
        val hC: Int get() = bandBot - bandTop - 2
        val w: Int get() = panel.w
        val cellH: Int get() = hC + hN
        /** Inside the outer rules: rows `[y0, y1)`. */
        val y0: Int get() = panel.y + 2
        val y1: Int get() = panel.bottom - 2
        val areaL: Rect get() = Rect(panel.x - d, y0, panel.w + d, y1 - y0)
        val areaR: Rect get() = Rect(panel.x, y0, panel.w + d, y1 - y0)
        fun area(left: Boolean) = if (left) areaL else areaR
        /** One track's area in pixels (a lens). */
        val areaPx: Int get() = areaL.w * areaL.h
        override fun toString() = "panel $panel d $d band [$bandTop, $bandBot) hC $hC hN $hN yA $yA yB $yB"
    }

    fun geo(sw: Switcher, l: Layout, d: Int): Geo {
        val r = sw.rest(l, stage5 = true)
        return Geo(r.panel, d, r.bandTop, r.bandBot, (maxOf(20, r.smallInk) + 1) / 2 * 2, r.yA, r.yB)
    }

    /** Why the programs cannot draw the wheel of [n] entries at [g] (null: they can) — the offer's shape conditions. (The
     *  neighbour rows are on even rows at stage 5, `Switcher.rest`, so one neighbour form serves both.) */
    fun refusal(g: Geo, n: Int): String? = when {
        n < 3 -> "$n entries: the wheel's paint shows no row below at two and none at one (the DW spec S-W4, O6)"
        g.yA < g.y0 || g.yB + g.hN > g.y1 -> "the neighbour form's ${g.hN} rows at ${g.yA} and ${g.yB} leave the area [${g.y0}, ${g.y1})"
        else -> null
    }

    // ------------------------------------------------------------------------------------------------- the cells
    /** One window's cell: [pic] (`w × (hC + hN)`, raw as painted: the centre form over the neighbour form) and its v2 record. */
    class Cell(val entry: Switcher.Entry, val pic: Gray8, val encoded: ByteArray)

    /**
     * [e]'s cell at [g]: its centre form painted by the switcher's own centre painter at the rest band, cropped to `[p.x,
     * p.right) × [bandTop + 2, bandBot)`, over its neighbour form painted at [Geo.yA], cropped to the form's box — each
     * through [relay] (the shell's cached-text relay: a face the glasses hold blits from the atlas here as on the live
     * paint, nothing recorded). Refuses, loudly, a form that inks outside its box (a rect a paint returns is a promise).
     */
    fun cell(sw: Switcher, l: Layout, g: Geo, e: Switcher.Entry, relay: (Gray8, () -> Unit) -> Unit = { _, b -> b() }): Cell {
        val p = g.panel
        val centre = Rect(p.x, g.bandTop + 2, p.w, g.hC)
        val nb = Rect(p.x, g.yA, p.w, g.hN)
        val c = Gray8(p.right + 8, p.bottom + 8).also { s -> relay(s) { sw.paintCentreForm(s, l, e) } }
        val n = Gray8(p.right + 8, p.bottom + 8).also { s -> relay(s) { sw.paintNeighbourForm(s, l, e, g.yA) } }
        inkOutside(c, centre)?.let { throw LintError("the wheel's centre form of '${e.name}' inks at $it, outside its rows $centre") }
        inkOutside(n, nb)?.let { throw LintError("the wheel's neighbour form of '${e.name}' inks at $it, outside its box $nb") }
        val pic = Gray8(p.w, g.cellH)
        pic.blit(c, centre, 0, 0)
        pic.blit(n, nb, 0, g.hC)
        return Cell(e, pic, TextureCacheImage.encode(pic))
    }

    private fun inkOutside(s: Gray8, box: Rect): String? {
        for (y in 0 until s.h) for (x in 0 until s.w) if (s[x, y] != 0 && !(x >= box.x && x < box.right && y >= box.y && y < box.bottom)) return "($x, $y)"
        return null
    }

    /** The rule record: [w] × 2, every pixel at [RULE_LEVEL]. */
    fun ruleRecord(w: Int): ByteArray = TextureCacheImage.encode(Gray8(w, 2).also { it.fillRect(0, 0, w, 2, Level.of(RULE_LEVEL)) })

    // ------------------------------------------------------------------------------------------------ the timing
    /** The pixels one step writes per lens, at most: the first track's level backdrop over the area, two centre forms,
     *  two neighbour forms and three rules at full size (a squashed draw writes fewer). */
    fun stepPx(g: Geo): Int = g.areaPx + 2 * g.w * g.hC + 2 * g.w * g.hN + 3 * g.w * 2

    /** The bytes one step decodes on the fast DRAW path (`FIRMWARE.md` §7.12: each DRAW of a record decodes from its kept
     *  start to the last source row it takes): two centre windows and two neighbour windows of the largest cells, three
     *  rules. */
    fun walkBytes(g: Geo, cells: List<Cell>): Int {
        fun rec(c: Cell) = Motion.Source.Record(0, g.w, g.cellH, c.encoded.size, c.encoded)
        val cMax = cells.maxOf { rec(it).tokenBytes(0, g.hC) }
        val nMax = cells.maxOf { rec(it).tokenBytes(g.hC, g.cellH) }
        val rule = ruleRecord(g.w).let { Motion.Source.Record(0, g.w, 2, it.size, it).tokenBytes() }
        return (2 * cMax + 2 * nMax + 3 * rule).toInt()
    }

    /** (period in OS ticks, steps) on the fast DRAW path's fit (modeled: `MainStaging.fastStepMs`, the period × 1.10
     *  floored at 16) at the Scroll tier's [motion]; [none]: one step at the fitted period. */
    fun timing(g: Geo, cells: List<Cell>, motion: MainStaging.ScrollMotion, none: Boolean): Pair<Int, Int> {
        val ms = MainStaging.fastStepMs(stepPx(g), walkBytes(g, cells))
        return if (none) MainStaging.timingOf(ms, motion.copy(frames = 0)).first to 1 else MainStaging.timingOf(ms, motion)
    }

    /** The two ease tables of the swapping cells over [t] steps (the DW spec §2.1): the swap's step n1 — the first step
     *  whose ease-out reaches 2048, at most t − 1 — then E1 the ease-out's first n1 steps rescaled to end at 4096 and E2 its
     *  last t − n1 rescaled from 0, so a centre moved by E1 then E2 is where one ease-out over t puts it. C3 X2DM1a
     *  (decisions CA (4)): the one copy of this arithmetic — Crush's phases pass their own [split]
     *  (`ScrollEffects.halfStep`, the step nearest half); the wheel keeps the first step past half. */
    fun swapTables(t: Int, split: Int? = null): Pair<IntArray, IntArray> {
        if (t < 2) throw LintError("the drum's swap needs two steps or more, not $t")
        val eo = Motion.easeE(Motion.Ease.EaseOut, t)
        val n1 = split ?: (1 until t).first { eo[it] >= 2048 || it == t - 1 }
        val e1 = IntArray(n1) { i -> Math.round(4096.0 * eo[i + 1] / eo[n1]).toInt() }
        val e2 = IntArray(t - n1) { i -> Math.round(4096.0 * (eo[n1 + i + 1] - eo[n1]) / (4096 - eo[n1])).toInt() }
        return e1 to e2
    }

    /** The parameters' rules for a notch in [step] (+1 down, −1 up), every one kind 1 on the ring list (`FIRMWARE.md`
     *  §7.8: the item at (v + arg) mod n, v = c + step): P0 the row leaving, P1 the centre leaving, P2 the row arriving
     *  in the centre, P3 the row entering. */
    fun rules(step: Int): List<MainStaging.Rule> =
        (if (step > 0) listOf(-2, -1, 0, 1) else listOf(2, 1, 0, -1)).map { MainStaging.Rule(CfwModes.KIND_ITEM, it) }

    // ------------------------------------------------------------------------------------------------- the block
    /**
     * The wheel's records laid out from [base] (4-byte aligned) up: the rule record, the item table's slot (an entry per
     * cell), the cells each in a slot of its own size with slack (`MainStaging.slack`), then the programs' slot — the
     * drum's tables and two programs at this T, with slack, so `Switcher` = None ([none]: its one-step programs in the same
     * slot) or a cell rewritten in place keeps every offset. Its [Block.size] does not depend on [base].
     *
     * C3 DW-1a (decisions CK A1): [cells] are one per REGISTERED window (Main's pseudo-entry and each window, in the order
     * the caller keeps them), so the block's size is constant for the process; [order] is the switcher's entries as indices
     * into [cells] — the item table, a permutation of [n] of them — so a window's first visit or a commit to another window
     * rewrites the item table alone ([Block.parts]), and the programs are re-made in place at another height ([rebuilt]).
     */
    class Block internal constructor(val base: Int, val geo: Geo, val cells: List<Cell>, val order: IntArray, val rule: ByteArray, val ruleOff: Int,
        val itemsOff: Int, val itemSlot: Int, val cellOffs: IntArray, val cellSlots: IntArray, val progOff: Int,
        val progSlot: Int, val tables: List<Motion.Ease.Table>, val down: MotionProgram, val up: MotionProgram,
        val period: Int, val t: Int, val none: Boolean) {
        /** The drum's entries: the item table's length (the switcher's entries), at most the cells'. */
        val n: Int get() = order.size
        val end: Int get() = progOff + progSlot
        val size: Int get() = end - base
        /** The item table: each entry's cell, a u16 off4, in the switcher's order. */
        val items4: IntArray get() = IntArray(n) { cellOffs[order[it]] / 4 }
        val records: List<Motion.Source.Record> get() = cells.indices.map { cellRecord(it) }
        fun cellRecord(i: Int) = Motion.Source.Record(cellOffs[i] / 4, geo.w, geo.cellH, cellSlots[i], cells[i].encoded)
        fun program(step: Int): MotionProgram = if (step > 0) down else up

        /** P0..P3 of a notch in [step] from cursor [c], as the glasses compute them on the ring list (§7.8). */
        fun params(step: Int, c: Int): IntArray {
            val v = c + step
            return IntArray(4) { i -> items4[(v + rules(step)[i].arg).mod(n)].toShort().toInt() }
        }

        /** C3 DW-1a: one record of the block as it is written in place — the rule, the item table, a cell (by its index),
         *  the programs (their tables and both programs) — with its writes, (byte offset, bytes padded to 4 with zeros). */
        class Part(val name: String, val writes: List<Pair<Int, ByteArray>>) {
            val bytes: Int get() = writes.sumOf { it.second.size }
            fun same(o: Part?): Boolean = o != null && o.writes.size == writes.size &&
                writes.indices.all { o.writes[it].first == writes[it].first && o.writes[it].second.contentEquals(writes[it].second) }
        }

        /** The block's records in layout order (the rule, the item table, the cells, the programs). */
        fun parts(): List<Part> {
            val out = ArrayList<Part>()
            out += Part(RULE, listOf(ruleOff to pad4(rule)))
            out += Part(ITEMS, listOf(itemsOff to pad4(ByteArray(2 * n).also { b -> items4.forEachIndexed { i, v -> b[2 * i] = (v and 0xFF).toByte(); b[2 * i + 1] = (v shr 8).toByte() } })))
            cells.forEachIndexed { i, c -> out += Part(cellName(i), listOf(cellOffs[i] to pad4(c.encoded))) }
            out += Part(PROGRAMS, tables.map { it.off4 * 4 to pad4(it.record) } + listOf(down.at * 4 to pad4(down.bytes), up.at * 4 to pad4(up.bytes)))
            return out
        }

        /** What goes into the cache, (byte offset, bytes padded to 4 with zeros), in layout order. */
        fun writes(): List<Pair<Int, ByteArray>> = parts().flatMap { it.writes }

        /** A cache of [cacheSize] bytes holding the block (and nothing else). */
        fun cache(cacheSize: Int): ByteArray = ByteArray(cacheSize).also { c -> for ((o, b) in writes()) b.copyInto(c, o) }

        /**
         * C3 DW-1a (the amendment's §3.4): the block for [cells], [order] and the programs at [g] and [motion], on THIS block's
         * offsets and slots — what an in-place write makes the glasses hold. Null when it does not fit them: another number
         * of cells, another cell size (the face), a cell or the programs past their slot — then the block is placed anew.
         */
        fun rebuilt(g: Geo, cells: List<Cell>, order: IntArray, effects: Motion.Effects, motion: MainStaging.ScrollMotion, none: Boolean = false): Block? {
            if (cells.size != this.cells.size || g.w != geo.w || g.hC != geo.hC || g.hN != geo.hN) return null
            if (cells.indices.any { pad4(cells[it].encoded).size > cellSlots[it] }) return null
            return layOut(base, g, cells, order, effects, motion, none, ruleOff, itemsOff, itemSlot, cellOffs, cellSlots, progOff, progSlot)
        }

        /**
         * C3 DW-1b-1 (`C3-DW1-amendment.md` §4.1; decisions DA (d) 1): the block bound on the glasses as a ring list — list
         * [list] in slot [slot], the item table's [n] entries at [itemsOff], pitch 0 — with two scroll entries at the block's
         * period: [entryIds]`[0]` event 4 (UPROLL, step +1) [down], [entryIds]`[1]` event 5 (DOWNROLL, step −1) [up], each with
         * `rules(±1)`. It stages no tap: `LocalRules.tapEntry` numbers the report-only tap the first entry the list does not use
         * (the glasses hold entries 0..7, `FIRMWARE.md` §6.3). END comes from the block's own bytes on the model ([paintEnd]
         * over [image]: a cache holding the block, made once by the caller when first asked for). [mark] is the staging's
         * identity (a new one where the item table's order or n changed).
         */
        fun boundList(list: Int, slot: Int, entryIds: IntArray, mark: Int, image: () -> ByteArray): wm.damage.core.local.BoundList {
            fun params(step: Int) = rules(step).map { CfwModes.BindParam(it.kind, it.arg) }
            val entries = listOf(
                wm.damage.core.local.BoundList.Entry(entryIds[0], CfwModes.EV_UPROLL, 1, period, down.at, params(1), down),
                wm.damage.core.local.BoundList.Entry(entryIds[1], CfwModes.EV_DOWNROLL, -1, period, up.at, params(-1), up))
            return wm.damage.core.local.BoundList(list, slot, n, 0, mark, itemsOff / 4, items4, entries,
                paintEnd = { left, prog4, p, pic -> paintEnd(image(), prog4, p, left, pic) }, ring = true)
        }

        /** C3 DW-1b-1: END of the program at [prog4] with [p] on one lens's model [pic] (8 bits a pixel, the compositor's
         *  shadow) inside the wheel's area, drawn by the simulator's renderers over [image] (a cache holding this block) as
         *  the lens writes it at the COMMIT (`Motion.endOnModel`): the first track's level-0 backdrop covers the area, so
         *  nothing under it is read. */
        fun paintEnd(image: ByteArray, prog4: Int, p: IntArray, left: Boolean, pic: Gray8) {
            val prog = if (prog4 == down.at) down else if (prog4 == up.at) up else throw LintError("the wheel's block holds no program at off4 $prog4")
            val stride = wm.damage.core.geom.Geometry.PANEL_W / 2
            val a = geo.area(left)
            val x0 = maxOf(0, a.x); val x1 = minOf(pic.w, a.right, wm.damage.core.geom.Geometry.PANEL_W)
            val y0 = maxOf(0, a.y); val y1 = minOf(pic.h, a.bottom, wm.damage.core.geom.Geometry.PANEL_H)
            val base = ByteArray(stride * wm.damage.core.geom.Geometry.PANEL_H)
            val out = Motion.endOnModel(prog, p, image, base, base, left)
            for (y in y0 until y1) for (x in x0 until x1) {
                val b = out[y * stride + (x shr 1)].toInt() and 0xFF
                pic[x, y] = (if (x and 1 == 0) b shr 4 else b and 0x0F) * 17
            }
        }

        fun describe(): String = "the wheel's block at $base: $size B (cells ${cells.map { it.encoded.size }} B in slots of ${cellSlots.sum()} B, " +
            "the rule ${rule.size} B, the item table $n of ${cells.size} entries in a slot of $itemSlot B, programs ${down.bytes.size} + ${up.bytes.size} B and tables " +
            "${tables.sumOf { it.record.size }} B in a slot of $progSlot B); P $period T $t${if (none) " (None)" else ""}"

        companion object {
            const val RULE = "rule"
            const val ITEMS = "item table"
            const val PROGRAMS = "programs"
            fun cellName(i: Int) = "cell $i"
        }
    }

    /**
     * The block for [cells] (one per registered window, ≥ 3) at [g] from [base], its item table [order] (the switcher's
     * entries as indices into [cells]; by default every cell in turn), its programs on the timing of [motion] ([none]:
     * `Switcher` = None). Built only through [effects] (version 2: EFFECTS in force). Refuses, loudly, a shape [refusal]
     * names and a program past 80 % of the step budget's cap at any cursor (§7.12: the phone may refuse more than the
     * glasses do; `MotionProgram.cost`).
     */
    fun block(base: Int, g: Geo, cells: List<Cell>, effects: Motion.Effects, motion: MainStaging.ScrollMotion = MainStaging.ScrollMotion.DEFAULT,
              none: Boolean = false, order: IntArray = IntArray(cells.size) { it }): Block {
        if (base % 4 != 0 || base < 0) throw LintError("the wheel's block at $base is not 4-byte aligned")
        var at = base
        fun next(size: Int): Int { val o = at; at += (size + 3) and 3.inv(); return o }
        val rule = ruleRecord(g.w)
        val ruleOff = next(rule.size)
        val itemSlot = 2 * cells.size
        val itemsOff = next(itemSlot)
        // each cell in a slot of its own size with slack (`MainStaging.slack`: a quarter, at least 64 B — a dirty tick or a
        // rename rewrites it in place)
        val cellSlots = IntArray(cells.size) { MainStaging.slack(cells[it].encoded.size) }
        val cellOffs = IntArray(cells.size) { next(cellSlots[it]) }
        val progOff = at
        return layOut(base, g, cells, order, effects, motion, none, ruleOff, itemsOff, itemSlot, cellOffs, cellSlots, progOff, null)
            ?: throw LintError("the wheel's programs do not fit the slot sized for them")
    }

    /** The block on the offsets given; [progSlot0] null: the programs' slot sized from the drum's programs with slack, else
     *  that slot — null when the drum's programs pass it. */
    private fun layOut(base: Int, g: Geo, cells: List<Cell>, order: IntArray, effects: Motion.Effects, motion: MainStaging.ScrollMotion, none: Boolean,
                       ruleOff: Int, itemsOff: Int, itemSlot: Int, cellOffs: IntArray, cellSlots: IntArray, progOff: Int, progSlot0: Int?): Block? {
        refusal(g, cells.size)?.let { throw LintError("no wheel: $it") }
        if (cells.any { it.pic.w != g.w || it.pic.h != g.cellH }) throw LintError("a cell is not ${g.w} x ${g.cellH}")
        if (order.isEmpty() || order.size > cells.size || order.any { it !in cells.indices } || order.distinct().size != order.size)
            throw LintError("the wheel's item table ${order.toList()} is not a permutation of some of its ${cells.size} cells")
        val rule = ruleRecord(g.w)
        val recs = cellOffs.indices.map { i -> Motion.Source.Record(cellOffs[i] / 4, g.w, g.cellH, cellSlots[i], cells[i].encoded) }
        val ruleRec = Motion.Source.Record(ruleOff / 4, g.w, 2, rule.size, rule)
        val (period, tDrum) = timing(g, cells, motion, none = false)
        val drum = programs(progOff, g, recs, ruleRec, effects, tDrum, none = false)
        val progSlot = progSlot0 ?: MainStaging.slack(drum.size)
        if (drum.size > progSlot) return null
        val (p, t) = if (none) timing(g, cells, motion, none = true) else period to tDrum
        val use = if (none) programs(progOff, g, recs, ruleRec, effects, 1, none = true) else drum
        val b = Block(base, g, cells, order, rule, ruleOff, itemsOff, itemSlot, cellOffs, cellSlots, progOff, progSlot,
            use.tables, use.down, use.up, p, t, none)
        for (step in intArrayOf(1, -1)) for (c in 0 until b.n) {
            val cost = b.program(step).cost(b.params(step, c))
            if (cost * 100 > Motion.BUDGET_CAP * 80) throw LintError("the wheel's ${if (step > 0) "down" else "up"} program at cursor $c costs $cost u, " +
                "past 80 % of the step budget's cap ${Motion.BUDGET_CAP} (§7.12) — not built")
        }
        return b
    }

    private class Progs(val tables: List<Motion.Ease.Table>, val down: MotionProgram, val up: MotionProgram, val size: Int)

    private fun programs(at0: Int, g: Geo, cells: List<Motion.Source.Record>, rule: Motion.Source.Record, effects: Motion.Effects, t: Int, none: Boolean): Progs {
        var at = at0
        val tables = if (none) emptyList() else swapTables(t).toList().map { e -> Motion.Ease.Table(at / 4, e).also { at += pad4(it.record).size } }
        val down = program(at / 4, g, cells, rule, effects, 1, t, tables, none).also { at += pad4(it.bytes).size }
        val up = program(at / 4, g, cells, rule, effects, -1, t, tables, none).also { at += pad4(it.bytes).size }
        return Progs(tables, down, up, at - at0)
    }

    /**
     * One notch's program at off4 [at4] (the DW spec §2.2, track by track; [step] +1 down, −1 up — the mirror), over [t]
     * steps on [tables] (E1, E2), or with [none] every track in one step to its final pose. x per lens is "left / right":
     * the panel's plane at p.x ∓ d, the screen plane at p.x.
     */
    fun program(at4: Int, g: Geo, cells: List<Motion.Source.Record>, rule: Motion.Source.Record, effects: Motion.Effects, step: Int, t: Int,
                tables: List<Motion.Ease.Table>, none: Boolean): MotionProgram {
        val down = step > 0
        val p = g.panel
        val d = g.d
        val xl = p.x - d; val xr = p.x + d
        val hC = g.hC; val hN = g.hN
        val cell = cells[0]                                             // every cell one size: the parameters name the real one
        val eo: Motion.Ease = if (none) Motion.Ease.Linear else Motion.Ease.EaseOut
        val n = if (none) 1 else t
        fun src(i: Int) = listOf(Motion.Pref(i, Motion.Field.SRC))
        val cTop = g.bandTop + 2
        // the neighbour slots the leaving centre goes to and the arriving neighbour comes from
        val toY = if (down) g.yA else g.yB
        val fromY = if (down) g.yB else g.yA
        return Motion.program2(at4, effects, records = cells + rule) {
            // W: the row leaving, squashed into the outer rule and faded out (its last row at the area's edge, level 0); the
            // track's level-0 backdrop paints the whole area every step
            track(g.areaL, g.areaR, Motion.Backdrop.Level(0)) {
                if (down) draw(cell, x = xl, xR = xr, y = g.yA, dy = g.y0 - g.yA, lvl0 = 15, lvl1 = 0, ease = eo, n = n, opt = TRANSPARENT,
                    prefs = src(0), h0 = hN, h1 = 1, anchor = 0, windowFirst = hC, windowRows = hN)
                else draw(cell, x = xl, xR = xr, y = g.yB, dy = (g.y1 - hN) - g.yB, lvl0 = 15, lvl1 = 0, ease = eo, n = n, opt = TRANSPARENT,
                    prefs = src(0), h0 = hN, h1 = 1, anchor = 2, windowFirst = hC, windowRows = hN)
            }
            // Z: the row entering, grown out of the other outer rule
            track(g.areaL, g.areaR, Motion.Backdrop.None) {
                if (down) draw(cell, x = xl, xR = xr, y = g.y1 - hN, dy = g.yB - (g.y1 - hN), lvl0 = 0, lvl1 = 15, ease = eo, n = n, opt = TRANSPARENT,
                    prefs = src(3), h0 = 1, h1 = hN, anchor = 2, windowFirst = hC, windowRows = hN)
                else draw(cell, x = xl, xR = xr, y = g.y0, dy = g.yA - g.y0, lvl0 = 0, lvl1 = 15, ease = eo, n = n, opt = TRANSPARENT,
                    prefs = src(3), h0 = 1, h1 = hN, anchor = 0, windowFirst = hC, windowRows = hN)
            }
            if (none) {
                // X: the centre leaving, at once its neighbour form in the slot; Y: the neighbour arriving, its centre form
                track(g.areaL, g.areaR, Motion.Backdrop.None) {
                    draw(cell, x = xl, xR = xr, y = toY, n = 1, opt = TRANSPARENT, prefs = src(1), windowFirst = hC, windowRows = hN)
                }
                track(g.areaL, g.areaR, Motion.Backdrop.None) {
                    draw(cell, x = p.x, xR = p.x, y = cTop, n = 1, opt = TRANSPARENT, prefs = src(2), windowFirst = 0, windowRows = hC)
                }
            } else {
                val (e1, e2) = tables
                val n1 = e1.e.size; val n2 = e2.e.size
                val f = Motion.easeE(Motion.Ease.EaseOut, t)[n1] / 4096.0
                val dx1 = Math.round(d * f).toInt()
                // the centres at the swap: where one ease-out over t puts the leaving centre and the arriving neighbour
                val cC = cTop + hC / 2
                val mX = Math.round(cC + (toY + hN / 2 - cC) * f).toInt()
                val mY = Math.round(fromY + hN / 2 + (cC - (fromY + hN / 2)) * f).toInt()
                // X: the centre flattens and dims toward the slot (to the neighbour form's own level), going back in depth; at
                // the swap its neighbour form, which settles into the slot at its own brightness
                track(g.areaL, g.areaR, Motion.Backdrop.None) {
                    draw(cell, x = p.x, xR = p.x, y = cTop, dy = (mX - hC / 2) - cTop, dx = -dx1, dxR = dx1, lvl0 = 15, lvl1 = LEVEL_SWAP_CENTRE,
                        ease = e1, n = n1, opt = TRANSPARENT, prefs = src(1), h0 = hC, h1 = hC / 2, anchor = 1, windowFirst = 0, windowRows = hC)
                    draw(cell, x = p.x - dx1, xR = p.x + dx1, y = mX - hN / 2, dy = toY - (mX - hN / 2), dx = -(d - dx1), dxR = d - dx1,
                        lvl0 = LEVEL_SWAP_NEIGHBOUR, lvl1 = 15, ease = e2, n = n2, opt = TRANSPARENT, prefs = src(1), windowFirst = hC, windowRows = hN)
                }
                // Y: the neighbour rises toward the centre at its own brightness; at the swap its centre form, which starts at
                // that brightness, grows to full height, comes forward to the screen plane and brightens
                track(g.areaL, g.areaR, Motion.Backdrop.None) {
                    draw(cell, x = xl, xR = xr, y = fromY, dy = (mY - hN / 2) - fromY, dx = dx1, dxR = -dx1, lvl0 = 15, lvl1 = LEVEL_SWAP_NEIGHBOUR,
                        ease = e1, n = n1, opt = TRANSPARENT, prefs = src(2), windowFirst = hC, windowRows = hN)
                    draw(cell, x = xl + dx1, xR = xr - dx1, y = mY - hC / 2, dy = cTop - (mY - hC / 2), dx = d - dx1, dxR = -(d - dx1),
                        lvl0 = LEVEL_SWAP_CENTRE, lvl1 = 15, ease = e2, n = n2, opt = TRANSPARENT, prefs = src(2), h0 = hC / 2, h1 = hC, anchor = 1,
                        windowFirst = 0, windowRows = hC)
                }
            }
            if (down) {
                // A: the top inner rule leaves into the outer rule (clipped out at the end), back in depth, fading
                track(g.areaL, g.areaR, Motion.Backdrop.None) {
                    draw(rule, x = p.x, xR = p.x, y = g.bandTop, dy = (g.y0 - 2) - g.bandTop, dx = -d, dxR = d, lvl0 = 15, lvl1 = 0, ease = eo, n = n, opt = TRANSPARENT)
                }
                // B: the bottom inner rule rises to bracket the new centre, coming forward with it
                track(g.areaL, g.areaR, Motion.Backdrop.None) {
                    draw(rule, x = xl, xR = xr, y = g.bandBot, dy = g.bandTop - g.bandBot, dx = d, dxR = -d, ease = eo, n = n, opt = TRANSPARENT)
                }
                // C: a new rule rises from the bottom edge to the lower inner place
                track(g.areaL, g.areaR, Motion.Backdrop.None) {
                    draw(rule, x = xl, xR = xr, y = g.y1, dy = g.bandBot - g.y1, lvl0 = 0, lvl1 = 15, ease = eo, n = n, opt = TRANSPARENT)
                }
            } else {
                // A': the bottom inner rule leaves into the bottom edge, fading
                track(g.areaL, g.areaR, Motion.Backdrop.None) {
                    draw(rule, x = xl, xR = xr, y = g.bandBot, dy = g.y1 - g.bandBot, lvl0 = 15, lvl1 = 0, ease = eo, n = n, opt = TRANSPARENT)
                }
                // B': the top inner rule sinks to the lower inner place, back in depth
                track(g.areaL, g.areaR, Motion.Backdrop.None) {
                    draw(rule, x = p.x, xR = p.x, y = g.bandTop, dy = g.bandBot - g.bandTop, dx = -d, dxR = d, ease = eo, n = n, opt = TRANSPARENT)
                }
                // C': a new rule comes down from the outer rule to the top inner place, forward to the screen plane
                track(g.areaL, g.areaR, Motion.Backdrop.None) {
                    draw(rule, x = xl, xR = xr, y = g.y0 - 2, dy = g.bandTop - (g.y0 - 2), dx = d, dxR = -d, lvl0 = 0, lvl1 = 15, ease = eo, n = n, opt = TRANSPARENT)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- on the model
    /** The picture [program] leaves at step [k] (0: its start pose, every track's first segment at j = 0) on one lens,
     *  drawn by the simulator's renderers as [Motion.endOnModel] draws step T ([cache] holds the records it names; [base]
     *  the panel it draws over, 4bpp packed, 320 B a row). A bound program names no `self` or `new`. */
    fun pictureOnModel(program: MotionProgram, p: IntArray, cache: ByteArray, base: ByteArray, left: Boolean, k: Int): ByteArray {
        program.resolve(p, staticOnly = false)
        val model = MotionSim(scaled = { true }, effects = { true })
        val withProg = cache.copyOf()
        for ((o4, b) in program.records) b.copyInto(withProg, o4 * 4)
        val prog = model.validate(withProg, program.at, p, MotionSim.Op.PLAY, armed = true) as? MotionSim.Prog
            ?: throw LintError("the model refuses the wheel's program at off4 ${program.at}")
        val out = base.copyOf()
        model.paint(MotionSim.Instance(0, 0, Motion.PERIOD_30HZ, program.at, prog, null, arrayOfNulls(prog.tracks.size)), k, out, 320, left, null, 0)
        return out
    }

    private fun pad4(b: ByteArray) = if (b.size % 4 == 0) b else b.copyOf((b.size + 3) / 4 * 4)
}
