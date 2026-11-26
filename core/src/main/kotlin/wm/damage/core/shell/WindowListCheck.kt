package wm.damage.core.shell

import wm.damage.core.comp.Compositor
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.geom.VPos
import wm.damage.core.gfx.Gray8
import wm.damage.core.local.BoundList
import wm.damage.core.motion.Motion
import wm.damage.core.motion.ScrollEffect
import wm.damage.core.motion.ScrollEffects
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.text.TextRasterizer
import wm.damage.core.transport.Arm
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.DamageMsg

/**
 * K1-2 stage 2 (the K1-2 spec §2.1, §7's stage-2 row, §8 pin 10): a window's list in the window form
 * ([wm.damage.core.motion.ListForm.STRIP_WINDOWS]) checked end to end on the firmware model — `desktop --local-check`'s
 * window-list cases ([run]) and `--motion-check`'s `scroll-list-<effect>-h<height>-<down|up>` strips ([strips]). Context
 * for the reader: Adam's own glasses on his own firmware fork (display-rendering work on a personal device); nothing here
 * contacts a device, and in this stage the shell sends none of it (stage 3 binds a window's list).
 *
 * The lists are Settings' own, through its painters ([SettingsWindow]): its categories (ten: Global, Animations and eight
 * app categories — a ring list at 288, 352 and 416, a plain one at 480), Global's rows (the window's and the shell's own,
 * as on the glasses: a ring list at every height) and a three-row app category (Bastion's size: a plain list
 * everywhere). At each of the four heights, on a bit-11 build at stage 5 with a 256 KiB cache, the list is laid out
 * where stage 3 lays it: from the wheel block's base ([top]: the shell's own `wheelBase`, [WheelResident.baseIn], over the
 * fixture's block of nine cells at that height and depth) down, over Main's staging of its eight sample rows (its end is the
 * floor). Bound as glasses list 1, entries 3–5 (decisions BD), slot 0.
 * Then for each of the form's effects ([ScrollEffects.WINDOW_EFFECTS]), at EVERY cursor and both directions, the notch
 * fired through the simulator's input hook on both lenses:
 * - RIGHT's report (field 115) is what [BoundList.predict] says (the edge at a plain list's ends; on a ring list the wrap
 *   drawn with field 18 = 1);
 * - when it drew: after its last step the lens holds, in the rows' areas, the window's OWN paint at the moved cursor and,
 *   in the band's area (the band less its two rules), the entering row's row form at the area's first line; outside the
 *   areas nothing changed (the rules among them); the COMMIT that answers it writes END into the shadow — what
 *   [ListStaging.paintEndOf] puts into belief — byte for byte;
 * - at a plain list's end (the staged edge): nothing changes on either lens.
 * And per list and height the tap entry, report-only under `Taps · glasses` too (no press on a window list).
 */
object WindowListCheck {
    /** Decisions BD: a window's list is glasses list 1, its entries 3 (down), 4 (up) and 5 (the tap). */
    const val LIST = 1
    val ENTRIES: IntArray get() = intArrayOf(3, 4, 5)

    /** The three lists checked (see the class's text). */
    enum class Sample(val id: String) { CATEGORIES("categories"), GLOBAL("global"), APP3("app3") }

    /** Eight app categories, with as many rows as his have (`K1-2-spec.md` §3: Reader, Tmux and Torrents 9, Files 6, Games 13,
     *  Feed 16, Music 35, Bastion 3 — Games', Feed's and Music's trimmed here: the lists checked are the categories, Global and
     *  the shortest). */
    private val APPS = listOf("Reader" to 9, "Tmux" to 9, "Files" to 6, "Torrents" to 9, "Music" to 9, "Games" to 7, "Feed" to 8, "Bastion" to 3)
    private val ROW_NAMES = listOf("Font", "Depth", "Scroll effect", "Notify", "Height", "Sort", "Refresh", "Preview", "Quiet")

    /** S1-5 S (the lead's ruling: a check is measured on the thing it names): the shell's own Global rows after the
     *  window's (`Shell.globalFontRows`: names, options, values and the three font rows' preview faces), so the Global
     *  sample is the list on the glasses — 20 rows. Names, options and values from the one description
     *  ([SettingsCore.ROWS], which `SettingsCoreTest` holds equal to the shell's rows). */
    private val SHELL_ROWS = listOf("fontFace", "fontScale", "fontStyle", "silentClock", "slideFrames", "slideFill", "cachedText",
        "pageStaging", "linkPriority")

    private fun shellRows(settings: ShellSettings): List<HostSetting> {
        val v = SettingsCore.encode(settings)
        return SHELL_ROWS.map { id ->
            val r = SettingsCore.ROWS.first { it.id == id }
            val label = r.options.first { it.first == v[id] }.second
            HostSetting(r.label, { r.options.map { it.second } }, { label }, {}, optionFont = when (id) {
                "fontFace" -> { opt -> wm.damage.core.text.Faces.byLabel(opt)?.let { wm.damage.core.text.FontSpec(it, 18, raw = true) } }
                "fontScale" -> { opt -> ShellSettings.SCALES.firstOrNull { ShellSettings.scaleLabel(it) == opt }?.let {
                    wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM, maxOf(6, Math.round(18 * it).toInt()), raw = true) } }
                "fontStyle" -> { opt -> wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM, 18, bold = opt == "bold", italic = opt == "italic", raw = true) }
                else -> null
            })
        }
    }

    private fun appRows(app: String, n: Int, values: Map<String, String>): List<HostSetting> = List(n) { i ->
        HostSetting(ROW_NAMES[i % ROW_NAMES.size], listOf("On", "Off"), { values["$app/$i"] ?: if (i % 2 == 0) "On" else "Off" }, {})
    }

    /** A window's list at [height] as the shell draws it: Settings ([sample]'s list) with the planes `Shell.updatePlanes`
     *  gives a window at Global `Depth` [depth] (the content at d, the band one notch nearer), `ContentKit.paintList` over
     *  its view, and the per-lens truth the compositor renders under those planes. */
    class WindowRig(text: TextRasterizer, val height: Int, val sample: Sample, val depth: Int = 8) {
        val layout: Layout = Layout().let { if (height >= Geometry.PANEL_H) it else it.withHeightMode(height, VPos.TOP) }
        val comp = Compositor()
        val kit = ContentKit(comp)
        private val settings = ShellSettings()
        /** An app category's row values by "App/row" (the re-stage pins change one); the rest read On and Off. */
        val values = HashMap<String, String>()
        private val shellRows = shellRows(settings)
        private val window = SettingsWindow(text, { settings }, {}, { shellRows }, apps = { APPS.map { (n, k) -> n to appRows(n, k, values) } }, effectsInForce = { true })
        /** The app category [sample] opens (Bastion's three rows for [Sample.APP3]), or null. */
        val app: String? = if (sample == Sample.APP3) APPS.first { it.second == 3 }.first else null
        val view: WindowView.ListView = run {
            val root = window.view() as WindowView.ListView
            when (sample) {
                Sample.CATEGORIES -> root
                Sample.GLOBAL -> { root.onCommit(0); window.view() as WindowView.ListView }
                Sample.APP3 -> { root.onCommit(2 + APPS.indexOfFirst { it.second == 3 }); window.view() as WindowView.ListView }
            }
        }
        val rows: Int get() = view.rowCount()
        val listD = depth
        val bandD = if (depth == 0) 0 else maxOf(0, depth - Geometry.X_STEP)

        private fun planes(): List<Compositor.PlaneRegion> {
            val out = ArrayList<Compositor.PlaneRegion>()
            if (depth != 0) {
                out += Compositor.PlaneRegion(Rect(layout.topBar.x, layout.topBar.y, layout.topBar.w, Layout.TOP_H + Layout.DIV_H), depth)
                out += Compositor.PlaneRegion(Rect(layout.statusBar.x, layout.bottomDivider.y, layout.statusBar.w, Layout.DIV_H + Layout.STATUS_H), depth)
                out += Compositor.PlaneRegion(layout.content, depth)
            }
            if (bandD != depth) out += Compositor.PlaneRegion(layout.lens, bandD)
            return out
        }

        private val truths = HashMap<Int, Pair<Gray8, Gray8>>()
        /** Forgets the truths and the row forms (a row's value changed). */
        fun repainted() { truths.clear(); rowForms.clear() }

        /** The compositor's per-lens truth of the list with its cursor at [c]. */
        fun truth(c: Int, left: Boolean): Gray8 = truths.getOrPut(c) {
            comp.planes = planes()
            view.model.cursor = c
            comp.composed.clear()
            kit.resetRail()
            kit.paintList(comp.composed, layout, view)
            if (comp.needsKeyframe) { comp.assembleFlush(Geometry.rectBudget(1)); kit.paintList(comp.composed, layout, view) }
            comp.assembleFlush(Geometry.rectBudget(1))
            comp.truthForTest(true).copy() to comp.truthForTest(false).copy()
        }.let { if (left) it.first else it.second }

        private val rowForms = HashMap<Int, Gray8>()

        /** Row [c] in its row form, as the strip holds it. */
        fun rowForm(c: Int): Gray8 = rowForms.getOrPut(c) { ListStaging.paintStripRow(view, layout, c) { _, b -> b() } }

        /** [base] with the band's [area] as the window form leaves it on one lens ([left]): level 0, and row [c]'s row form
         *  at the area's first line ([ScrollEffects.BAND_ROW_DY]) on the band's plane, its raw pixels. */
        fun withBandRow(base: Gray8, c: Int, left: Boolean, area: Rect): Gray8 {
            val g = base.copy()
            g.fillRect(area, 0)
            val rf = rowForm(c)
            val x0 = layout.content.x + (if (left) -bandD else bandD)
            val y0 = layout.lens.y + ScrollEffects.BAND_ROW_DY
            for (y in maxOf(area.y, y0) until minOf(area.bottom, y0 + rf.h)) for (x in maxOf(area.x, x0) until minOf(area.right, x0 + rf.w)) g[x, y] = rf[x - x0, y - y0]
            return g
        }

        /** The window form staged for [effect] at stage 5 on 256 KiB in `[floor, top)`. */
        fun staging(effect: ScrollEffect, floor: Int, top: Int): ListStaging =
            ListStaging.buildWindow(layout, view, kit, listD, bandD, CfwModes.CACHE2_MAX_RIDERS, ListStaging.ScrollMotion.DEFAULT.copy(effect = effect),
                MainLocalCheck.effectsAt(true), floor, top)
    }

    /** Main's end at [height] on 256 KiB, stage 5: its eight sample rows staged as the shell stages them (the floor). */
    private fun mainEnd(text: TextRasterizer, height: Int, depth: Int): Int {
        val cache = CfwModes.CACHE2_MAX_RIDERS
        return MainLocalCheck.MainRig(text, height, MainLocalCheck.sampleRows(), depth)
            .staging(cache, CfwModes.mainRegion(cache, bind = true), ListStaging.ScrollMotion.DEFAULT, stage5 = true).end
    }

    /** The window list's top at [height] and Global `Depth` [depth] (C3 DW-1b-2, decisions DA (c) 3: one source, no
     *  constant): the shell's own base ([WheelResident.baseIn], what `Shell.wheelBase` reads) with the fixture's block placed
     *  — nine cells (Main's entry and eight windows), laid out as the shell lays it (`WheelResident.baseFor`). */
    fun top(text: TextRasterizer, height: Int, depth: Int): Int =
        WheelResident().also { it.block = wm.damage.core.motion.WheelCheck.Rig(text, height, 9, depth).block() }
            .baseIn(CfwModes.mainRegion(CfwModes.CACHE2_MAX_RIDERS, bind = true).last + 1)

    /** Every case at [heights] for every list and the form's effects; [frames] keeps one notch's frames a case. */
    fun run(text: TextRasterizer, heights: List<Int> = ShellSettings.HEIGHTS, depth: Int = 8, frames: Boolean = true): MainLocalCheck.Result {
        val cases = ArrayList<MainLocalCheck.Case>()
        val table = ArrayList<String>()
        for (h in heights) {
            val floor = mainEnd(text, h, depth)
            val top = top(text, h, depth)
            for (sample in Sample.entries) for (e in ScrollEffects.WINDOW_EFFECTS) {
                val r = runOne(text, h, sample, e, depth, floor, top, frames, tapCase = e == ScrollEffect.DEPTH)
                cases += r.first
                table += r.second
            }
        }
        return MainLocalCheck.Result(cases, table)
    }

    /** `--motion-check`'s strips: Global's list (a ring list at every height), each of the form's effects, the notch from
     *  its middle row down and up, every step — `scroll-list-<effect>-h<height>-<down|up>`; each case handed over as it is
     *  made (the desktop writes its strip), the result keeping it without its frames. */
    fun strips(text: TextRasterizer, heights: List<Int> = ShellSettings.HEIGHTS, depth: Int = 8,
               onCase: ((MainLocalCheck.Case) -> Unit)? = null): MainLocalCheck.Result {
        val cases = ArrayList<MainLocalCheck.Case>()
        val table = ArrayList<String>()
        for (h in heights) {
            val floor = mainEnd(text, h, depth)
            val top = top(text, h, depth)
            for (e in ScrollEffects.WINDOW_EFFECTS) {
                val r = runOne(text, h, Sample.GLOBAL, e, depth, floor, top, true, midOnly = true)
                for (c0 in r.first) {
                    val dir = c0.name.substringAfterLast('-')
                    val c = MainLocalCheck.Case("scroll-list-${e.id}-h$h-$dir", "a window's list (Settings' Global) in the window form, ${e.word}: ${c0.about}",
                        c0.crop, c0.frames, c0.lines, c0.failures)
                    if (onCase == null) cases += c else { onCase(c); cases += MainLocalCheck.Case(c.name, c.about, c.crop, emptyList(), c.lines, c.failures) }
                }
                table += r.second
            }
        }
        return MainLocalCheck.Result(cases, table)
    }

    private fun runOne(text: TextRasterizer, height: Int, sample: Sample, effect: ScrollEffect, depth: Int, floor: Int, top: Int, keepFrames: Boolean,
                       midOnly: Boolean = false, tapCase: Boolean = false): Pair<List<MainLocalCheck.Case>, String> {
        val rig = WindowRig(text, height, sample, depth)
        val cacheSize = CfwModes.CACHE2_MAX_RIDERS
        val ms = rig.staging(effect, floor, top)
        val list = ms.boundList(1, list = LIST, entryIds = ENTRIES)
        val tag = "list-${sample.id}-h$height-${effect.id}"
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = MainLocalCheck.features(true); it.clockIsOsTick = true }
        val lens = MainLocalCheck.Lens(sim, cacheSize)
        val setup = ArrayList<String>()
        for (arm in Arm.entries) {
            sim.conformanceLease(arm, true, lens.now)
            sim.conformanceControl(arm, DamageMsg.OP_CACHE_SIZE, cacheSize / 1024, lens.now)
            sim.conformanceControl(arm, DamageMsg.OP_FLAGS_SET, MainLocalCheck.flags(true), lens.now)
        }
        if (ms.ring != ListStaging.ringFits(ms.layout, ms.rows)) setup += "the staging's form: ring ${ms.ring} with ${ms.rows} rows at $height"
        if (ms.start < floor || ms.end > top) setup += "the staging ${ms.start}..${ms.end} leaves its space $floor..$top"
        if (ms.blobs.size != 2 || ms.bands.isNotEmpty() || ms.wrap != null) setup += "a window list stages its strip and its programs alone: ${ms.blobs.map { it.name }}"
        for (b in ms.blobs) lens.write(b.off, b.bytes, b.name, setup)
        for (s in list.sets()) lens.send("a SET", s, setup)
        lens.send("the LIST", list.listMessage(0), setup)
        for (arm in Arm.entries) if (sim.bindForTest(arm).lists[LIST].ring != ms.ring) setup += "$arm: the list's RING flag is not ${ms.ring} after the LIST"
        val whole = ms.pan(ListStaging.WHOLE, 1)
        val stripRows = ms.strip.raw.h / ms.layout.rowH
        val avail = top - floor
        val perRow = ms.strip.slot.toDouble() / stripRows
        val fits = ((avail - (ms.progSlots?.size ?: 1) * ms.progSlot) / perRow).toInt() - (stripRows - ms.rows)
        val tableLine = "h$height ${sample.id} (${ms.rows} rows, ${if (ms.ring) "ring" else "plain"}) ${effect.word}: ${ms.bytes} B at ${ms.start}..${ms.end} — the strip " +
            "${ms.blobs[0].bytes.size} B in a ${ms.strip.slot} B slot ($stripRows strip rows: ${"%.0f".format(perRow)} B a strip row in the region, " +
            "${"%.0f".format(ms.strip.encoded.size.toDouble() / stripRows)} B written), programs ${ms.blobs[1].bytes.size} B" +
            (ms.progSlots?.let { " (pan slots of ${ms.progSlot} B at ${it[0]} and ${it[1]})" } ?: " (no spare pan slot)") +
            "; ${whole.program.tracks.size} tracks, ${whole.areaPx} px a step, P ${ms.period} T ${ms.t}; " +
            "Main's end $floor (its eight sample rows), the window list's top $top (the wheel's block's base, the shell's own: " +
            "${CfwModes.mainRegion(cacheSize, bind = true).last + 1 - top} B below the region's end): $avail B — " +
            "rows of this size that fit there ≈ $fits"
        val cases = ArrayList<MainLocalCheck.Case>()
        if (setup.isNotEmpty()) { cases += MainLocalCheck.Case("$tag-setup", "the staging written and the list SET", Rect(0, 0, 1, 1), emptyList(), emptyList(), setup); return cases to tableLine }

        val down = list.entryFor(CfwModes.EV_UPROLL)!!; val up = list.entryFor(CfwModes.EV_DOWNROLL)!!; val tap = list.entryFor(CfwModes.EV_TAP)!!
        var s = 0
        fun one(e: BoundList.Entry, c: Int, fails: MutableList<String>, lines: MutableList<String>, frames: MutableList<MainLocalCheck.Frame>?,
                answer: (Int, IntArray, Int) -> Unit) {
            lens.now += 1_000
            lens.keyframe(rig.truth(c, true), rig.truth(c, false))
            lens.send("DISCARD to cursor $c", CfwModes.motionDiscard(ListStaging.SLOT, 1, LIST, c), fails)
            lens.notifies()
            frames?.add(MainLocalCheck.Frame("before", lens.panel(Arm.LEFT), lens.panel(Arm.RIGHT)))
            val a = lens.fire(CfwModes.DEV_RING, e.eventId)
            s++
            val what = "${if (e.isTap) "tap" else if (e.step > 0) "down" else "up"} from $c"
            if (a == null) { fails += "$what: RIGHT sent no report"; return }
            if (a.s != s) fails += "$what: the report's S ${a.s}, not $s"
            if (a.mark != list.mark) fails += "$what: the report's mark ${a.mark}, not ${list.mark}"
            if (a.list != LIST) fails += "$what: the report names list ${a.list}, not $LIST"
            when (val f = list.predict(c, e)) {
                is BoundList.Fire.Edge -> {
                    if (a.outcome != DamageMsg.APPLIED_EDGE || a.cursor != c) fails += "$what: predicted the edge, the lens said ${a.describe()}"
                    // K1-2 stage 4c (decisions DA (c) 2, pin 10's cells): at a plain list's end the lens draws nothing (the
                    // phone applies the end notch, the K1-2 spec §2.1) — both panels counted one notch's length after the fire
                    lens.wake(lens.now + (ms.t + 1L) * e.period)
                    for (arm in Arm.entries) MainLocalCheck.differ(lens.panel(arm), rig.truth(c, arm == Arm.LEFT), null).let { n ->
                        if (n > 0) fails += "$what $arm: $n px changed on the lens at the staged edge (it draws nothing)"
                    }
                    lines += "$what: the staged edge, nothing drawn on either lens"
                }
                is BoundList.Fire.Refused -> fails += "$what: predicted refused ${f.reason} (${a.describe()})"
                is BoundList.Fire.Drew -> {
                    val wraps = !e.isTap && c + e.step !in 0 until ms.rows
                    if (a.outcome == DamageMsg.APPLIED_DREW && a.field18 != (if (wraps) 1L else null)) fails += "$what: field 18 ${a.field18}, a fire that ${if (wraps) "wrapped" else "did not wrap"}"
                    if (a.outcome != DamageMsg.APPLIED_DREW || a.cursor != f.cursor) fails += "$what: predicted drew to ${f.cursor}, the lens said ${a.describe()}"
                    else if (f.p == null) { if (a.params != null || a.prog != null) fails += "$what: a report-only entry's report carries fields 10–14 (${a.describe()})" }
                    else if (a.prog != f.prog4 || !a.params.contentEquals(f.p)) fails += "$what: predicted prog ${f.prog4} P ${f.p.joinToString(",")}, the lens said ${a.describe()}"
                    else {
                        val t0 = lens.now
                        val steps = ms.programAt(f.prog4).stepsWith(f.p)
                        for (k in 1..steps) { lens.wake(t0 + (k - 1).toLong() * e.period); frames?.add(MainLocalCheck.Frame("step $k", lens.panel(Arm.LEFT), lens.panel(Arm.RIGHT))) }
                        lens.wake(t0 + steps.toLong() * e.period)
                        val held = lens.notifies()
                        if (held.isNotEmpty()) fails += "$what: a local instance at HOLD owes no notify, RIGHT sent ${held.map { it.describe() }}"
                        answer(f.cursor, f.p, f.prog4)
                        lines += "$what → ${f.cursor}: prog ${f.prog4} P ${f.p.joinToString(",")}, $steps steps at P ${e.period}"
                    }
                }
            }
        }

        // ---- the pans at every cursor (or, for a strip, the middle row), both directions
        val mid = ms.rows / 2
        for ((e, name) in listOf(down to "down", up to "up")) {
            val fails = ArrayList<String>(); val lines = ArrayList<String>()
            var kept: List<MainLocalCheck.Frame> = emptyList()
            val pan = ms.pan(ListStaging.WHOLE, e.step)
            for (c in if (midOnly) listOf(mid) else (0 until ms.rows).toList()) {
                val frames: MutableList<MainLocalCheck.Frame>? = if (keepFrames && c == mid) ArrayList() else null
                one(e, c, fails, lines, frames) { cP, p, prog4 ->
                    for (arm in Arm.entries) {
                        val left = arm == Arm.LEFT
                        val areas = pan.areas(left)
                        val want = rig.withBandRow(rig.truth(cP, left), cP, left, areas[2])
                        val held = lens.panel(arm)
                        MainLocalCheck.differ(held, want, areas).let { if (it > 0) fails += "$name from $c $arm: $it px at HOLD where the lens is not the list's paint at $cP (the band's area: its row form)" }
                        MainLocalCheck.differ(held, rig.truth(c, left), null).let { n -> val inA = MainLocalCheck.differ(held, rig.truth(c, left), areas); if (n - inA > 0) fails += "$name from $c $arm: ${n - inA} px outside the areas changed at HOLD" }
                    }
                    lens.send("the riding LIST and COMMIT S $s", CfwModes.batch(listOf(list.listMessage(0), Motion.commit(ms.programAt(prog4), ListStaging.SLOT, s, LIST, cP, p)), engine = true, bind = true), fails)
                    for (arm in Arm.entries) if (sim.bindForTest(arm).lists[LIST].ring != ms.ring) fails += "$name from $c $arm: the list's RING flag is not ${ms.ring} after the answer"
                    for (arm in Arm.entries) {
                        val left = arm == Arm.LEFT
                        val belief = rig.truth(c, left).copy().also { ms.paintEndOf(prog4, p, left, it) }
                        MainLocalCheck.differ(lens.shadow(arm), belief, null).let { if (it > 0) fails += "$name from $c $arm: $it px where COMMIT's END in the shadow is not what belief takes" }
                        MainLocalCheck.differ(lens.shadow(arm), rig.withBandRow(rig.truth(cP, left), cP, left, pan.areas(left)[2]), pan.areas(left)).let {
                            if (it > 0) fails += "$name from $c $arm: $it px of END not the list's paint at $cP (the band's area: its row form)" }
                        if (sim.panelCrc32(arm) != sim.shadowCrc32(arm)) fails += "$name from $c $arm: after the COMMIT the lens does not show the shadow"
                    }
                    val bodies = lens.notifies()
                    if (bodies.none { it.outcome == DamageMsg.OUTCOME_COMMITTED } || bodies.any { it.outcome == 2 }) fails += "$name from $c: RIGHT's notifies after the COMMIT ${bodies.map { it.describe() }}"
                }
                if (frames != null) kept = frames
            }
            cases += MainLocalCheck.Case("$tag-$name", "a notch $name ${if (midOnly) "from row $mid" else "at every cursor (the strip: from row $mid)"}: the report as predicted, " +
                "the last step the list's paint in the rows' areas and the entering row's row form in the band's area (its rules left out), COMMIT's END belief's",
                MainLocalCheck.union(pan.areasL + pan.areasR), kept, lines, fails)
        }

        // ---- the tap entry: report-only (no press on a window list), under `Taps · glasses` too
        if (tapCase) {
            val fails = ArrayList<String>()
            if (!tap.reportOnly) fails += "the window list's tap entry names a program (${tap.prog4}): it is report-only"
            for (c in 0 until ms.rows) one(tap, c, fails, ArrayList(), null) { _, _, _ -> fails += "the report-only entry drew" }
            for (arm in Arm.entries) if (sim.panelCrc32(arm) != sim.shadowCrc32(arm)) fails += "$arm: the report-only entry left a picture on the lens"
            cases += MainLocalCheck.Case("$tag-tap", "the tap entry at every cursor, report-only: a report with no fields 10–14, nothing drawn", Rect(0, 0, 1, 1), emptyList(), emptyList(), fails)
        }
        return cases to tableLine
    }
}
