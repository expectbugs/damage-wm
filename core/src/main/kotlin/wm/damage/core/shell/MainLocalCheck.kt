package wm.damage.core.shell

import kotlinx.serialization.json.JsonObject
import wm.damage.core.comp.Compositor
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.geom.VPos
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.local.BoundList
import wm.damage.core.motion.Motion
import wm.damage.core.motion.ScrollEffect
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.text.TextRasterizer
import wm.damage.core.transport.Arm
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.Pb

/**
 * Build C2, stage DM2: Main's bound list checked end to end on the firmware model — the core of `desktop --local-check
 * DIR` (`C2-spec.md` §3 DM, §7) and of `MainBindTest`. Context for the reader: Adam's own glasses on his own firmware
 * fork (display-rendering work on a personal device); nothing here contacts a device — the glasses are
 * [GlassFirmwareSim] driven message by message, its clock the OS tick.
 *
 * Per height (the four) and per cache (160 KiB: the shared reserve; 256 KiB: Main's own region), on a bit-10 build with
 * DRAW2, ENGINE and BIND in force: Main staged ([ListStaging]) and its list SET ([ListStaging.boundList]: the two scroll
 * entries and the press; LIST with its mark). Then, at EVERY cursor and for both directions, the gesture fired through
 * the simulator's input hook on both lenses (`FIRMWARE.md` §6.2):
 * - RIGHT's report (field 115) is what [BoundList.predict] says — the outcome, the cursor, the program and P0..P3;
 * - when it drew: after its last step the lens holds Main's OWN paint at the moved cursor in the program's areas (the
 *   compositor's truth of `ContentKit.paintList` over `MainSurface`), Main at the old cursor everywhere else; and the
 *   COMMIT that answers it writes END into the shadow — what [ListStaging.paintEndOf] puts into belief — byte for byte;
 * - the press (`Taps · glasses`) at every cursor: after its three steps the lens holds the band row at the cursor drawn
 *   at the forward depth (the truth with the band's plane one notch nearer), until the DISCARD after the act's frame;
 * - the report-only tap entry (`Taps · report`): a report with no fields 10–14 and nothing drawn;
 * - a re-staged row: Music's summary changes, the row is re-staged IN PLACE (its writes inside its own slots, the mark
 *   kept), and a notch onto it lands on its new paint.
 * K1-1: every Scroll effect ([ScrollEffect]) staged on its own — the pans at every cursor both ways and the wrap, as
 * above (END = Main's paint) — and the pans re-made alone ([reprogramCase]): written into the free slot (no strip, band
 * form or table byte), switched by SETs, a notch before the SETs and one after each named and committed.
 * Each case keeps the frames of one notch for the strips the desktop writes.
 */
object MainLocalCheck {

    /** A window that is only a Main row: its summary is the check's to change. */
    class SampleRow(id: String, name: String, icon: IconKind, var s: DamageWindow.Summary) : DamageWindow(id, name, icon) {
        override fun view(): WindowView = WindowView.ListView(ListModel(), { 0 }, { _, _, _, _ -> }, { _, _, _ -> }, {})
        override fun summary() = s
        override fun saveState(): JsonObject = JsonObject(emptyMap())
        override fun restoreState(state: JsonObject) {}
    }

    /** Main's eight rows in the shell's order (Settings last), with summaries of the length his own carry. */
    fun sampleRows(): List<SampleRow> = listOf(
        SampleRow("reader", "Reader", IconKind.READER, DamageWindow.Summary("The Long Way Home", detail = "chapter 12 of 31", progress = 0.41)),
        SampleRow("tmux", "Tmux", IconKind.TERMINAL, DamageWindow.Summary("3 sessions", detail = "build: idle 4 min")),
        SampleRow("files", "Files", IconKind.FILES, DamageWindow.Summary("Documents", detail = "24 items", more = true)),
        SampleRow("torrents", "Torrents", IconKind.TORRENTS, DamageWindow.Summary("2 active, 3.1 MB/s", detail = "debian-13.iso 64%", progress = 0.64)),
        SampleRow("music", "Music", IconKind.MUSIC, DamageWindow.Summary("Night Drive - Low Tide", detail = "2:31 / 4:05", more = true, progress = 0.62)),
        SampleRow("games", "Games", IconKind.GAMES, DamageWindow.Summary("Hold'em, 6 left", detail = "stack 1,840")),
        SampleRow("feed", "Feed", IconKind.FEED, DamageWindow.Summary("12 new", detail = "4 sources", more = true)),
        SampleRow("settings", "Settings", IconKind.SETTINGS, DamageWindow.Summary("Global and 8 apps")),
    )

    /** What a lens showed at one moment (the 4-bit panel, `GlassFirmwareSim.snapshot`). */
    class Frame(val label: String, val left: ByteArray, val right: ByteArray)

    /** One case: its frames (for a strip), the region they are cropped to, what was checked, and what failed. */
    class Case(val name: String, val about: String, val crop: Rect, val frames: List<Frame>, val lines: List<String>, val failures: List<String>)

    class Result(val cases: List<Case>, val table: List<String>) {
        val failures: List<String> get() = cases.flatMap { c -> c.failures.map { "${c.name}: $it" } }
    }

    /** Main at [height] as the shell draws it: the planes `Shell.updatePlanes` gives Main at Global `Depth` [depth]
     *  (both bars and the content at d, the band one notch nearer, never nearer than the screen plane), `ContentKit.
     *  paintList` over `MainSurface`, and the per-lens truth the compositor renders under those planes. */
    class MainRig(text: TextRasterizer, val height: Int, val rows: List<DamageWindow>, val depth: Int = 8) {
        val layout: Layout = Layout().let { if (height >= Geometry.PANEL_H) it else it.withHeightMode(height, VPos.TOP) }
        val comp = Compositor()
        val kit = ContentKit(comp)
        val surface = MainSurface(text, { rows }, {})
        val view = surface.view()
        val listD = depth
        val bandD = if (depth == 0) 0 else maxOf(0, depth - Geometry.X_STEP)
        /** Where the press puts the band row, said here on its own (`FIRMWARE.md` §6.4, §6.9; `DESIGN.md` §3.1): one depth
         *  notch nearer than the band, never nearer than the screen plane. */
        val pressD = maxOf(0, bandD - Geometry.X_STEP)

        private fun planes(band: Int): List<Compositor.PlaneRegion> {
            val out = ArrayList<Compositor.PlaneRegion>()
            if (depth != 0) {
                out += Compositor.PlaneRegion(Rect(layout.topBar.x, layout.topBar.y, layout.topBar.w, Layout.TOP_H + Layout.DIV_H), depth)
                out += Compositor.PlaneRegion(Rect(layout.statusBar.x, layout.bottomDivider.y, layout.statusBar.w, Layout.DIV_H + Layout.STATUS_H), depth)
                out += Compositor.PlaneRegion(layout.content, depth)
            }
            if (band != (if (depth == 0) 0 else depth)) out += Compositor.PlaneRegion(layout.lens, band)
            return out
        }

        private val truths = HashMap<Pair<Int, Int>, Pair<Gray8, Gray8>>()

        /** The compositor's per-lens truth of Main with the cursor at [c] and its band at [band] (the planes' own by default). */
        fun truth(c: Int, left: Boolean, band: Int = bandD): Gray8 = truths.getOrPut(c to band) {
            comp.planes = planes(band)
            view.model.cursor = c
            comp.composed.clear()
            kit.resetRail()
            kit.paintList(comp.composed, layout, view)
            if (comp.needsKeyframe) { comp.assembleFlush(Geometry.rectBudget(1)); kit.paintList(comp.composed, layout, view) }  // the keyframe path renders no truth
            comp.assembleFlush(Geometry.rectBudget(1))           // renders the truth under the plane map
            comp.truthForTest(true).copy() to comp.truthForTest(false).copy()
        }.let { if (left) it.first else it.second }

        /** Forgets the truths (a row's summary changed). */
        fun repainted() = truths.clear()

        /** The staging as the shell builds it for the bound list: the whole list's pans only (AF.10), one strip at every
         *  cache size (K1-2 stage 1, decisions BC O1). */
        fun staging(cacheSize: Int, region: IntRange, motion: ListStaging.ScrollMotion? = ListStaging.ScrollMotion.DEFAULT, stage5: Boolean = false): ListStaging =
            ListStaging.build(layout, view, kit, listD, bandD, cacheSize, region.first, region.last + 1,
                widths = listOf(ListStaging.WHOLE), motion = motion, stage5 = stage5, fx = effectsAt(stage5))
    }

    /** Runs every case at [heights] × [cachesKib] for [effects]; [frames] keeps the strips' frames (the desktop writes them). */
    fun run(text: TextRasterizer, heights: List<Int> = ShellSettings.HEIGHTS, cachesKib: List<Int> = listOf(160, 256),
            depth: Int = 8, frames: Boolean = true, effects: List<ScrollEffect> = ScrollEffect.entries): Result {
        val cases = ArrayList<Case>()
        val table = ArrayList<String>()
        for (h in heights) for (kib in cachesKib) {
            // C3 DM-0: and each at stage 5 on a bit-11 build — the ring form where Main wraps (288, 352), today's elsewhere
            for (stage5 in listOf(false, true)) {
                for (e in listOf(ScrollEffect.DEPTH) + effects.filter { it != ScrollEffect.DEPTH }) {
                    val r = runOne(text, h, kib, depth, frames, e, stage5 = stage5)
                    cases += r.first
                    table += r.second
                }
                cases += reprogramCase(text, h, kib, depth, stage5)
                // C3 DM-1 (S6): the switch between the two pitch forms, at stage 5
                if (stage5) cases += pitchSwitchCase(text, h, kib, depth)
            }
        }
        for (h in heights) table += worstCase(text, h, depth)
        // C3 DW-1a: the wheel's block beside Main's staging at stage 5 on 256 KiB, where it is resident
        if (256 in cachesKib) for (h in heights) wheelBlock(text, h, depth).let { (c, l) -> cases += c; table += l }
        return Result(cases, table)
    }

    /**
     * C3 DW-1a (`C3-DW1-amendment.md` §3.2, §3.5): the wheel's block resident in Main's region at stage 5 on a 256 KiB
     * session at [height] — Main staged from the region's start on the fixture's rows, and the wheel's block of nine cells
     * (Main's entry and the eight windows the rows stand for) at Global `Depth` [depth] placed as the shell places it
     * (`WheelResident`: the first tenant from the region's end, inside the cap, not met by Main's staging). The line is the
     * region's split; the case fails where the block would not be placed.
     */
    fun wheelBlock(text: TextRasterizer, height: Int, depth: Int = 8): Pair<Case, String> {
        val cache = CfwModes.CACHE2_MAX_RIDERS
        val region = CfwModes.mainRegion(cache, bind = true)
        val ms = MainRig(text, height, sampleRows(), depth).staging(cache, region, ListStaging.ScrollMotion.DEFAULT, stage5 = true)
        val block = wm.damage.core.motion.WheelCheck.Rig(text, height, 9, depth).block()
        val end = region.last + 1
        val why = WheelResident.refusal(end, block.size, ms.end)
        val line = "the wheel's block h$height-256k-s5 (Depth $depth, nine cells): Main's staging ${ms.start}..${ms.end} (${ms.end - ms.start} B), " +
            "${block.base - ms.end} B free, the block ${block.size} B at ${block.base}..${block.end} of the region's end $end (the cap ${WheelResident.CAP} B): " +
            (why?.let { "NOT PLACED — $it" } ?: "placed")
        return Case("wheel-block-h$height", "the wheel's block resident beside Main's staging at $height, stage 5, 256 KiB", Rect(0, 0, 1, 1), emptyList(),
            listOf(line, block.describe()), listOfNotNull(why?.let { "the wheel's block is not placed: $it" })) to line
    }

    /** K1-1: each effect's notches as strips (the middle row both ways, and the wrap where Main wraps) at [heights] on
     *  256 KiB, his cache — `desktop --motion-check`'s scroll section, for the lead's look before his. C3 FF: and each at
     *  stage 5 (`scroll-<effect>-h<height>-s5-<notch>`), its programs on the fast DRAW path's fit, the wrap a ring fire
     *  where Main's paint wraps. */
    fun effectStrips(text: TextRasterizer, heights: List<Int> = ShellSettings.HEIGHTS, depth: Int = 8,
                     /** C3 DM-1: each case handed over as it is made (the desktop writes its strip), the result then keeping
                      *  it without its frames — six effects' strips held at once passed the desktop test's heap. */
                     onCase: ((Case) -> Unit)? = null): Result {
        val cases = ArrayList<Case>()
        val table = ArrayList<String>()
        for (stage5 in listOf(false, true)) for (h in heights) for (e in ScrollEffect.entries) {
            val r = runOne(text, h, 256, depth, true, e, pansOnly = true, stage5 = stage5)
            val s5 = if (stage5) "-s5" else ""
            val tag = "h$h-256k" + (if (e == ScrollEffect.DEPTH) "" else "-${e.id}") + s5
            for (c0 in r.first) {
                val c = Case("scroll-${e.id}-h$h$s5-${c0.name.removePrefix("$tag-")}", "${e.word}${if (stage5) " at stage 5" else ""}: ${c0.about}", c0.crop, c0.frames, c0.lines, c0.failures)
                if (onCase == null) cases += c else { onCase(c); cases += Case(c.name, c.about, c.crop, emptyList(), c.lines, c.failures) }
            }
            table += (if (stage5) "stage 5: " else "") + r.second
        }
        return Result(cases, table)
    }

    /** C3 X1DM1: one sequence of notches — its frames in rows (the frame before, then a row a notch), the region they are
     *  cropped to, what was checked and what failed. */
    class Burst(val name: String, val about: String, val crop: Rect, val rows: List<List<Frame>>, val lines: List<String>, val failures: List<String>)

    /** 230 OS ticks: the floor of the stock scroll messages that reach the phone (`FIRMWARE.md` §6.1, M: no scroll-to-scroll
     *  gap under 224 ms in 565 messages; the rubber band paces them) — NOT his fastest fires: the glasses fire at the ring's
     *  own pace (C3 X2DM1a, decisions CA (1)). The wheel's burst's pace too. */
    const val BURST_GAP_TICKS = 230L
    /** The pause between his six notches down and his six up. */
    const val BURST_PAUSE_TICKS = 820L
    /** The fast three's gap, 120 ticks (117 ms): his fires 100–150 ms apart (15 of 288 gaps in his fast session of 11-19,
     *  M: RIGHT's report stamps, `private/build083/B083-real.md`), past Crush's first phase (94 ms). */
    const val FAST_GAP_TICKS = 120L
    /** C3 X2DM1a (decisions CA (1)): the second fast three's gap, 45 ticks (44 ms): his commonest gap under 224 ms in that
     *  session (23 of 288 in 30–60 ms, M), inside Crush's first phase. */
    const val FAST45_GAP_TICKS = 45L

    /**
     * C3 X1DM1 (decisions BP (4)): what he judges — sequences, not single notches — at [height] on 256 KiB at stage 5,
     * `desktop --motion-check`'s burst strips: for each of [effects] `scroll-<effect>-h<H>-s5-burst` (six notches down
     * from row 1 [BURST_GAP_TICKS] apart, a pause of [BURST_PAUSE_TICKS], six up) and for each of [fast] `-fast` (three
     * notches down from row 2 [FAST_GAP_TICKS] apart, then rest) and, C3 X2DM1a, `-fast45` (the same [FAST45_GAP_TICKS]
     * apart). Every notch fires through the simulator's input hook on
     * both lenses, as on the glasses: a notch that lands while the one before still draws closes it (`FIRMWARE.md` §6.2
     * step 11) and draws its own program from Main's paint at the new cursor of the one before; a frame after every step
     * drawn until the next fire's tick. After the last notch's last step, the phone's answer: the riding LIST and the
     * COMMIT of the last report. Checks, as the notch strips': every message taken by both lenses; each report as
     * [BoundList.predict] says from the cursor the list holds; nothing notified before the answer; at HOLD the lens is
     * Main's paint at the last cursor in the pan's areas and unchanged outside them; COMMIT's END in the shadow is
     * belief's and Main's paint; the lens shows the shadow after it. Each notch's lines say where the next one cut it
     * and the rows' travel it had left there (from the program's own segments: modeled).
     */
    fun effectBursts(text: TextRasterizer, height: Int = 352, depth: Int = 8,
                     effects: List<ScrollEffect> = listOf(ScrollEffect.NONE, ScrollEffect.DEPTH, ScrollEffect.SLIDE, ScrollEffect.FALL, ScrollEffect.CRUSH, ScrollEffect.FLIP),
                     fast: List<ScrollEffect> = listOf(ScrollEffect.DEPTH, ScrollEffect.CRUSH),
                     /** Each burst handed over as it is made (the desktop writes its strip); the list then keeps it without frames. */
                     onBurst: ((Burst) -> Unit)? = null): List<Burst> {
        val out = ArrayList<Burst>()
        fun keep(b: Burst) { if (onBurst == null) out += b else { onBurst(b); out += Burst(b.name, b.about, b.crop, emptyList(), b.lines, b.failures) } }
        val burst = List(6) { 1 to BURST_GAP_TICKS } + listOf(-1 to BURST_PAUSE_TICKS) + List(5) { -1 to BURST_GAP_TICKS }
        val three = List(3) { 1 to FAST_GAP_TICKS }
        val three45 = List(3) { 1 to FAST45_GAP_TICKS }
        for (e in effects) {
            keep(sequence(text, height, depth, e, "burst", 1, burst,
                "six notches down $BURST_GAP_TICKS ticks apart (224 ms: the floor of the stock scroll messages that reach the phone, " +
                    "not his fastest fires, which come 13-150 ms apart for one gap in six in his fast session), a pause of $BURST_PAUSE_TICKS ticks, six up"))
            if (e in fast) {
                keep(sequence(text, height, depth, e, "fast", 2, three, "three notches down $FAST_GAP_TICKS ticks apart (his 100-150 ms gaps), then rest"))
                keep(sequence(text, height, depth, e, "fast45", 2, three45, "three notches down $FAST45_GAP_TICKS ticks apart (his commonest gap under 224 ms, 30-60 ms), then rest"))
            }
        }
        return out
    }

    /** [effectBursts]' one sequence: [notches] (+1 down or −1 up, and the ticks after the notch before: the first's is not
     *  read) fired from row [c0]. */
    private fun sequence(text: TextRasterizer, height: Int, depth: Int, effect: ScrollEffect, kind: String, c0: Int,
                         notches: List<Pair<Int, Long>>, about: String): Burst {
        val kib = 256
        val m = MainRig(text, height, sampleRows(), depth)
        val cacheSize = kib * 1024
        val ms = m.staging(cacheSize, CfwModes.mainRegion(cacheSize, bind = true), ListStaging.ScrollMotion.DEFAULT.copy(effect = effect), stage5 = true)
        val list = ms.boundList(1)
        val name = "scroll-${effect.id}-h$height-s5-$kind"
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = features(true); it.clockIsOsTick = true }
        val lens = Lens(sim, cacheSize)
        val fails = ArrayList<String>(); val lines = ArrayList<String>()
        for (arm in Arm.entries) {
            sim.conformanceLease(arm, true, lens.now)
            sim.conformanceControl(arm, DamageMsg.OP_CACHE_SIZE, kib, lens.now)
            sim.conformanceControl(arm, DamageMsg.OP_FLAGS_SET, flags(true), lens.now)
        }
        for (b in ms.blobs) lens.write(b.off, b.bytes, b.name, fails)
        for (s in list.sets()) lens.send("a SET", s, fails)
        lens.send("the LIST", list.listMessage(0), fails)
        val pans = listOf(1, -1).associateWith { ms.pan(ListStaging.WHOLE, it) }
        val crop = union(pans.values.flatMap { it.areasL + it.areasR })
        lines += "${effect.word} at stage 5 (plays ${ms.plays.effect.word}${if (ms.plays.v2) " in version 2" else ""}), P ${ms.period} T ${ms.t}, " +
            "${if (ms.ring) "a ring list" else "a plain list"} of ${ms.rows} rows, 256 KiB; $about"
        if (fails.isNotEmpty()) return Burst(name, about, crop, emptyList(), lines, fails)
        val rows = ArrayList<List<Frame>>()
        lens.now += 1_000
        lens.keyframe(m.truth(c0, true), m.truth(c0, false))
        lens.send("DISCARD to cursor $c0", CfwModes.motionDiscard(ListStaging.SLOT, 1, ListStaging.LIST, c0), fails)
        lens.notifies()
        rows += listOf(Frame("before (row $c0)", lens.panel(Arm.LEFT), lens.panel(Arm.RIGHT)))
        val down = list.entryFor(CfwModes.EV_UPROLL)!!; val up = list.entryFor(CfwModes.EV_DOWNROLL)!!
        var c = c0; var s = 0
        var tFire = lens.now + 1_000
        var last: Triple<BoundList.Fire.Drew, Int, Int>? = null    // the last notch's prediction, the row it left and its period
        for ((i, n) in notches.withIndex()) {
            val (step, gap) = n
            if (i > 0) tFire += gap
            val e = if (step > 0) down else up
            val what = "notch ${i + 1} (${if (step > 0) "down" else "up"} from row $c at tick $tFire)"
            lens.now = tFire
            val a = lens.fire(CfwModes.DEV_RING, e.eventId); s++
            val f = list.predict(c, e) as? BoundList.Fire.Drew
            if (a == null || f == null || f.p == null) { fails += "$what: ${if (a == null) "RIGHT sent no report" else "not a drawn notch (${a.describe()})"}"; break }
            if (a.s != s || a.mark != list.mark || a.outcome != DamageMsg.APPLIED_DREW || a.cursor != f.cursor || a.prog != f.prog4 || !a.params.contentEquals(f.p)) {
                fails += "$what: predicted S $s drew to ${f.cursor} prog ${f.prog4} P ${f.p.joinToString(",")}, the lens said ${a.describe()}"; break
            }
            val prog = ms.programAt(f.prog4)
            val steps = prog.stepsWith(f.p)
            val next = notches.getOrNull(i + 1)?.let { tFire + it.second }
            val row = ArrayList<Frame>()
            for (k in 1..steps) {
                val at = tFire + (k - 1).toLong() * e.period
                if (next != null && at >= next) break
                lens.wake(at)
                row += Frame("notch ${i + 1} step $k", lens.panel(Arm.LEFT), lens.panel(Arm.RIGHT))
            }
            rows += row
            val cut = if (row.size < steps) "cut after step ${row.size} of $steps by notch ${i + 2}; the rows' travel left there: ${travelLeft(prog, ms.layout, row.size)}" else "all $steps steps drawn"
            lines += "$what → row ${f.cursor}: prog ${f.prog4} P ${f.p.joinToString(",")}, period ${e.period}; $cut"
            last = Triple(f, c, e.period)
            c = f.cursor
        }
        val done = last
        if (fails.isEmpty() && done != null) {
            val (f, cBefore, period) = done
            val p = f.p!!
            val prog = ms.programAt(f.prog4)
            val pan = pans.values.first { it.prog4 == f.prog4 }
            lens.wake(tFire + prog.stepsWith(p).toLong() * period)          // a period past the last: the lens holds (HOLD)
            lens.notifies().let { if (it.isNotEmpty()) fails += "RIGHT notified before the answer: ${it.map { b -> b.describe() }}" }
            for (arm in Arm.entries) {
                val left = arm == Arm.LEFT
                val held = lens.panel(arm)
                differ(held, m.truth(c, left), pan.areas(left)).let { if (it > 0) fails += "$arm: $it px at HOLD where the lens is not Main's paint at row $c" }
                differ(held, m.truth(c0, left), null).let { n -> val inA = differ(held, m.truth(c0, left), pan.areas(left)); if (n - inA > 0) fails += "$arm: ${n - inA} px outside the areas changed at HOLD" }
            }
            lens.send("the riding LIST and COMMIT S $s", CfwModes.batch(listOf(list.listMessage(0), Motion.commit(prog, ListStaging.SLOT, s, ListStaging.LIST, c, p)), engine = true, bind = true), fails)
            for (arm in Arm.entries) {
                val left = arm == Arm.LEFT
                val belief = m.truth(c0, left).copy().also { ms.paintEndOf(f.prog4, p, left, it) }
                differ(lens.shadow(arm), belief, null).let { if (it > 0) fails += "$arm: $it px where COMMIT's END in the shadow is not what belief takes" }
                differ(lens.shadow(arm), m.truth(c, left), pan.areas(left)).let { if (it > 0) fails += "$arm: $it px of END not Main's paint at row $c" }
                if (sim.panelCrc32(arm) != sim.shadowCrc32(arm)) fails += "$arm: after the COMMIT the lens does not show the shadow"
            }
            val bodies = lens.notifies()
            if (bodies.none { it.outcome == DamageMsg.OUTCOME_COMMITTED } || bodies.any { it.outcome == 2 }) fails += "RIGHT's notifies after the COMMIT ${bodies.map { it.describe() }}"
            lines += "the answer to notch ${notches.size} (from row $cBefore): the riding LIST and COMMIT S $s; the last cursor row $c"
        }
        return Burst(name, about, crop, rows, lines, fails)
    }

    /** The travel [prog]'s two row tracks (0 and 1) have left after local step [k] (from their segments' own eases; the
     *  track over the band's top is "above"): what a notch that cuts it there makes those rows jump. */
    private fun travelLeft(prog: wm.damage.core.motion.MotionProgram, l: Layout, k: Int): String = (0..1).joinToString(", ") { ti ->
        val tr = prog.tracks[ti]
        var start = 0; var total = 0; var moved = 0
        for (sg in tr.segments) {
            if (sg.verb == Motion.DRAW && sg.dy != 0) {
                total += sg.dy
                val j = (k - start).coerceIn(0, sg.steps)
                moved += Motion.eased(0, sg.dy, Motion.easeE(sg.ease, sg.steps)[j])
            }
            start += sg.steps
        }
        "rows ${if (tr.areaL.y < l.lens.y) "above" else "below"} ${kotlin.math.abs(total - moved)} px"
    }

    /** Decisions AU.1: the fullest his eight rows can draw — every line and detail past the row's width (the ▸ mark), a
     *  bar on each, dense ink — staged at 256 KiB (one strip since K1-2 stage 1), against Main's own region. */
    fun worstCase(text: TextRasterizer, height: Int, depth: Int = 8): String {
        val ink = "M@W#".repeat(24)
        val rows = sampleRows().onEach { it.s = DamageWindow.Summary(ink, detail = ink, more = true, progress = 0.5) }
        val m = MainRig(text, height, rows, depth)
        val cache = CfwModes.CACHE2_MAX_RIDERS
        val region = CfwModes.mainRegion(cache, bind = true)
        val one = ListStaging.build(m.layout, m.view, m.kit, m.listD, m.bandD, cache, 0, cache, widths = listOf(ListStaging.WHOLE)).let { it.end - it.start }
        val size = region.last + 1 - region.first
        return "worst case h$height at 256 KiB (Main's region $size B): one strip $one B (${if (one <= size) "fits" else "DOES NOT FIT"})"
    }

    private const val FEATURES_C2 = 0x5ff
    /** C3 DM-0: a bit-11 build (`FIRMWARE.md` §7.9: features 0xdff), armed at stage 5 (EFFECTS beside BIND). */
    private const val FEATURES_C3 = 0xdff
    internal fun features(stage5: Boolean) = if (stage5) FEATURES_C3 else FEATURES_C2
    internal fun flags(stage5: Boolean) = DamageMsg.FLAG_DRAW2 or DamageMsg.FLAG_ENGINE or DamageMsg.FLAG_BIND or (if (stage5) DamageMsg.FLAG_EFFECTS else 0)
    /** C3 DM-1: the gate's token as the model's link state gives it — a bit-11 build with EFFECTS armed at stage 5; none below. */
    fun effectsAt(stage5: Boolean): Motion.Effects? = if (stage5) Motion.effects(DamageMsg.Caps(2, FEATURES_C3), flags(true)) else null

    internal class Lens(val sim: GlassFirmwareSim, val cacheSize: Int) {
        var now = 1_000L
        fun send(what: String, m: ByteArray, fails: MutableList<String>) {
            for (arm in Arm.entries) if (!sim.conformanceMessage(arm, m, now)) fails += "$arm refused $what (refusal ${sim.refusalRecord(arm)})"
        }
        fun write(off: Int, bytes: ByteArray, what: String, fails: MutableList<String>) {
            var pos = 0
            while (pos < bytes.size) {
                val len = minOf(Shell.STAGE_CHUNK, bytes.size - pos)
                send("$what at ${off + pos}", CfwModes.cacheUpdate2(listOf(CfwModes.CacheWrite2((off + pos) / 4, bytes.copyOfRange(pos, pos + len))), cacheSize), fails)
                pos += len
            }
        }
        fun keyframe(left: Gray8, right: Gray8) {
            val full = Rect(0, 0, Geometry.PANEL_W, Geometry.PANEL_H)
            sim.conformanceMessage(Arm.LEFT, CfwModes.keyframe(Zl.encodeCfw(Pack.rect(left, full))), now)
            sim.conformanceMessage(Arm.RIGHT, CfwModes.keyframe(Zl.encodeCfw(Pack.rect(right, full))), now)
        }
        /** The gesture through the input hook, LEFT first as on the glasses (§6.1); RIGHT's report. */
        fun fire(devType: Int, eventId: Int): DamageMsg.Applied? {
            sim.conformanceSent(Arm.RIGHT); sim.conformanceSent(Arm.LEFT)
            sim.conformanceInput(Arm.LEFT, devType, eventId.toLong(), 0, now)
            sim.conformanceInput(Arm.RIGHT, devType, eventId.toLong(), 0, now)
            return sim.conformanceSent(Arm.RIGHT).filter { it.first == DamageMsg.APPLIED_FIELD }
                .mapNotNull { DamageMsg.parseApplied(Pb.l(DamageMsg.APPLIED_FIELD, it.second)) }.singleOrNull()
        }
        fun wake(at: Long) { now = at; for (arm in Arm.entries) sim.conformanceWake(arm, now) }
        fun notifies(): List<DamageMsg.Motion> = sim.conformanceSent(Arm.RIGHT).filter { it.first == DamageMsg.MOTION_FIELD }
            .mapNotNull { DamageMsg.parseMotion(Pb.l(DamageMsg.MOTION_FIELD, it.second)) }
        fun panel(arm: Arm): ByteArray = sim.snapshot(arm)
        fun shadow(arm: Arm): ByteArray = (if (arm == Arm.LEFT) sim.left else sim.right).shadow.copyOf()
    }

    private fun level(b: ByteArray, x: Int, y: Int): Int {
        val v = b[y * (Geometry.PANEL_W / 2) + (x shr 1)].toInt() and 0xFF
        return if (x and 1 == 0) v shr 4 else v and 0x0F
    }

    /** Pixels of [nibbles] (a 4-bit panel or shadow) inside [areas] (or everywhere) whose level is not [want]'s. */
    internal fun differ(nibbles: ByteArray, want: Gray8, areas: List<Rect>?): Int {
        var n = 0
        val rs = areas ?: listOf(Rect(0, 0, Geometry.PANEL_W, Geometry.PANEL_H))
        for (r in rs) for (y in r.y until r.bottom) for (x in r.x until r.right)
            if (level(nibbles, x, y) != Pack.level(want[x, y].toInt() and 0xFF)) n++
        return n
    }

    private fun runOne(text: TextRasterizer, height: Int, kib: Int, depth: Int, keepFrames: Boolean,
                       effect: ScrollEffect = ScrollEffect.DEPTH, pansOnly: Boolean = false, stage5: Boolean = false): Pair<List<Case>, String> {
        val rows = sampleRows()
        val m = MainRig(text, height, rows, depth)
        val cacheSize = kib * 1024
        val region = CfwModes.mainRegion(cacheSize, bind = true)
        val ms = m.staging(cacheSize, region, ListStaging.ScrollMotion.DEFAULT.copy(effect = effect), stage5)
        val list = ms.boundList(1)
        val tag = "h$height-${kib}k" + (if (effect == ScrollEffect.DEPTH) "" else "-${effect.id}") + if (stage5) "-s5" else ""
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = features(stage5); it.clockIsOsTick = true }
        val lens = Lens(sim, cacheSize)
        val setup = ArrayList<String>()
        for (arm in Arm.entries) {
            sim.conformanceLease(arm, true, lens.now)
            sim.conformanceControl(arm, DamageMsg.OP_CACHE_SIZE, kib, lens.now)
            sim.conformanceControl(arm, DamageMsg.OP_FLAGS_SET, flags(stage5), lens.now)
        }
        // DM-0 (S2): the ring form exactly where Main's paint wraps at stage 5; today's form everywhere else
        if (ms.ring != (stage5 && ListStaging.ringFits(ms.layout, ms.rows))) setup += "the staging's form: ring ${ms.ring} at stage ${if (stage5) 5 else 4} with ${ms.rows} rows at $height"
        if (ms.ring && ms.wrap != null) setup += "a ring staging built a wrap record"
        for (b in ms.blobs) lens.write(b.off, b.bytes, b.name, setup)
        for (s in list.sets()) lens.send("a SET", s, setup)
        lens.send("the LIST", list.listMessage(0), setup)
        for (arm in Arm.entries) if (sim.bindForTest(arm).lists[ListStaging.LIST].ring != ms.ring) setup += "$arm: the list's RING flag is not ${ms.ring} after the LIST"
        val whole = ms.pan(ListStaging.WHOLE, 1)
        val inside = region.first <= ms.start && ms.end <= region.last + 1
        if (!inside) setup += "the staging ${ms.start}..${ms.end} leaves its region ${region.first}..${region.last + 1}"
        val tableLine = "$tag: ${effect.word}, ${ms.bytes} B at ${ms.start}..${ms.end} (${if (region.last + 1 == cacheSize) "shared reserve" else "Main's region"}), " +
            "strip ${ms.blobs[0].bytes.size} B in a ${ms.strip.slot} B slot, band forms ${ms.blobs.drop(1).take(ms.rows).sumOf { it.bytes.size }} B in ${ms.bands.sumOf { it.slot }} B of slots, " +
            "${ms.wrap?.let { "wrap record ${it.encoded.size} B in a ${it.slot} B slot, " } ?: ""}programs ${ms.blobs.last().bytes.size} B${ms.progSlots?.let { " (pan slots of ${ms.progSlot} B at ${it[0]} and ${it[1]})" } ?: " (no spare pan slot)"}; " +
            "whole list ${whole.kA}+${whole.kB} rows, ${whole.program.tracks.size} tracks, ${whole.areaPx} px a step, P ${ms.period} T ${ms.t}, press ${ms.bandD}→${ms.press.pressD}; " +
            "the region's free span ${ms.freeSpan} B"
        val cases = ArrayList<Case>()
        if (setup.isNotEmpty()) { cases += Case("$tag-setup", "the staging written and the list SET", Rect(0, 0, 1, 1), emptyList(), emptyList(), setup); return cases to tableLine }

        val down = list.entryFor(CfwModes.EV_UPROLL)!!; val up = list.entryFor(CfwModes.EV_DOWNROLL)!!; val tap = list.entryFor(CfwModes.EV_TAP)!!
        var s = 0
        /** One fire of [e] from cursor [c]: the cursor set, the lens keyframed with Main at [c], the gesture through the
         *  hook, its report against the prediction, and — when it drew — its last step and its answer. */
        fun one(e: BoundList.Entry, c: Int, fails: MutableList<String>, lines: MutableList<String>, frames: MutableList<Frame>?, answer: (Int, IntArray, Int) -> Unit) {
            lens.now += 1_000
            lens.keyframe(m.truth(c, true), m.truth(c, false))
            lens.send("DISCARD to cursor $c", CfwModes.motionDiscard(ListStaging.SLOT, 1, ListStaging.LIST, c), fails)
            lens.notifies()
            frames?.add(Frame("before", lens.panel(Arm.LEFT), lens.panel(Arm.RIGHT)))
            val a = lens.fire(CfwModes.DEV_RING, e.eventId)       // the ring: a tap entry's devType, and a scroll entry takes any
            s++
            val what = "${if (e.isTap) "tap" else if (e.step > 0) "down" else "up"} from $c"
            if (a == null) { fails += "$what: RIGHT sent no report"; return }
            if (a.s != s) fails += "$what: the report's S ${a.s}, not $s"
            if (a.mark != list.mark) fails += "$what: the report's mark ${a.mark}, not ${list.mark}"
            when (val f = list.predict(c, e)) {
                is BoundList.Fire.Edge -> if (a.outcome != DamageMsg.APPLIED_EDGE || a.cursor != c) fails += "$what: predicted the edge, the lens said ${a.describe()}"
                is BoundList.Fire.Refused -> if (a.outcome != 2 || a.reason != f.reason) fails += "$what: predicted refused ${f.reason}, the lens said ${a.describe()}"
                is BoundList.Fire.Drew -> {
                    // DM-0 (§7.8): field 18 = 1 exactly when a ring fire wrapped
                    val wraps = !e.isTap && c + e.step !in 0 until ms.rows
                    if (a.outcome == DamageMsg.APPLIED_DREW && a.field18 != (if (wraps) 1L else null)) fails += "$what: field 18 ${a.field18}, a fire that ${if (wraps) "wrapped" else "did not wrap"}"
                    if (a.outcome != DamageMsg.APPLIED_DREW || a.cursor != f.cursor) fails += "$what: predicted drew to ${f.cursor}, the lens said ${a.describe()}"
                    else if (f.p == null) { if (a.params != null || a.prog != null) fails += "$what: a report-only entry's report carries fields 10–14 (${a.describe()})" }
                    else if (a.prog != f.prog4 || !a.params.contentEquals(f.p)) fails += "$what: predicted prog ${f.prog4} P ${f.p.joinToString(",")}, the lens said ${a.describe()}"
                    else {
                        val t0 = lens.now
                        val steps = ms.programAt(f.prog4).stepsWith(f.p)
                        // the fire sets the engine flag; the thread draws step 1 at its next wake (§6.2 step 14), then a step a period
                        for (k in 1..steps) { lens.wake(t0 + (k - 1).toLong() * e.period); frames?.add(Frame("step $k", lens.panel(Arm.LEFT), lens.panel(Arm.RIGHT))) }
                        lens.wake(t0 + steps.toLong() * e.period)          // a period past the last: the lens holds (HOLD)
                        val held = lens.notifies()
                        if (held.isNotEmpty()) fails += "$what: a local instance at HOLD owes no notify, RIGHT sent ${held.map { it.describe() }}"
                        answer(f.cursor, f.p, f.prog4)
                        lines += "$what → ${f.cursor}: prog ${f.prog4} P ${f.p.joinToString(",")}, $steps steps at P ${e.period}"
                    }
                }
            }
        }

        // ---- the pans at every cursor, both directions
        for ((e, name) in listOf(down to "down", up to "up")) {
            val fails = ArrayList<String>(); val lines = ArrayList<String>()
            var frames: MutableList<Frame>? = null
            var kept: List<Frame> = emptyList()
            val mid = ms.rows / 2
            for (c in 0 until ms.rows) {
                frames = if (keepFrames && c == mid) ArrayList() else null
                one(e, c, fails, lines, frames) { cP, p, prog4 ->
                    // (the row before the fire is the loop's cursor [c]: on a ring list c' − step is outside the rows at the wrap)
                    val pan = ms.pan(ListStaging.WHOLE, e.step)
                    for (arm in Arm.entries) {
                        val left = arm == Arm.LEFT
                        val areas = pan.areas(left)
                        val held = lens.panel(arm)
                        differ(held, m.truth(cP, left), areas).let { if (it > 0) fails += "$name from $c $arm: $it px at HOLD where the lens is not Main's paint at $cP" }
                        differ(held, m.truth(c, left), null).let { n -> val inA = differ(held, m.truth(c, left), areas); if (n - inA > 0) fails += "$name from $c $arm: ${n - inA} px outside the areas changed at HOLD" }
                    }
                    // DM-0 (S5): the COMMIT rides behind the list's LIST with shift 0, as every answer does — LIST-RING on a ring list
                    lens.send("the riding LIST and COMMIT S $s", CfwModes.batch(listOf(list.listMessage(0), Motion.commit(ms.programAt(prog4), ListStaging.SLOT, s, ListStaging.LIST, cP, p)), engine = true, bind = true), fails)
                    for (arm in Arm.entries) if (sim.bindForTest(arm).lists[ListStaging.LIST].ring != ms.ring) fails += "$name from $c $arm: the list's RING flag is not ${ms.ring} after the answer"
                    for (arm in Arm.entries) {
                        val left = arm == Arm.LEFT
                        val belief = m.truth(c, left).copy().also { ms.paintEndOf(prog4, p, left, it) }
                        differ(lens.shadow(arm), belief, null).let { if (it > 0) fails += "$name from $c $arm: $it px where COMMIT's END in the shadow is not what belief takes" }
                        differ(lens.shadow(arm), m.truth(cP, left), pan.areas(left)).let { if (it > 0) fails += "$name from $c $arm: $it px of END not Main's paint at $cP" }
                        if (sim.panelCrc32(arm) != sim.shadowCrc32(arm)) fails += "$name from $c $arm: after the COMMIT the lens does not show the shadow"
                    }
                    val bodies = lens.notifies()
                    if (bodies.none { it.outcome == DamageMsg.OUTCOME_COMMITTED } || bodies.any { it.outcome == 2 }) fails += "$name from $c: RIGHT's notifies after the COMMIT ${bodies.map { it.describe() }}"
                }
                if (frames != null) kept = frames
            }
            val pan = ms.pan(ListStaging.WHOLE, e.step)
            cases += Case("$tag-$name", "a notch $name at every cursor: the report as predicted, the last step Main's paint, COMMIT's END belief's; the strip is the notch from row $mid",
                union(pan.areasL + pan.areasR), kept, lines, fails)
        }

        // ---- the press at every cursor (Taps · glasses); K1-1: the press is no effect's, so Depth's staging alone checks it
        if (effect == ScrollEffect.DEPTH && !pansOnly) run {
            val fails = ArrayList<String>(); val lines = ArrayList<String>()
            if (ms.press.pressD != m.pressD) fails += "the press goes to depth ${ms.press.pressD}, not one notch nearer than the band's ${ms.bandD} (${m.pressD})"
            var kept: List<Frame> = emptyList()
            for (c in 0 until ms.rows) {
                val frames: MutableList<Frame>? = if (keepFrames && c == ms.rows / 2) ArrayList() else null
                one(tap, c, fails, lines, frames) { cP, p, _ ->
                    for (arm in Arm.entries) {
                        val left = arm == Arm.LEFT
                        val areas = ms.press.areas(left)
                        differ(lens.panel(arm), m.truth(cP, left, band = m.pressD), areas).let { if (it > 0) fails += "tap at $c $arm: $it px at HOLD where the press is not the band row at the forward depth" }
                        val end = m.truth(cP, left).copy().also { ms.paintEndOf(ms.press.prog4, p, left, it) }
                        differ(lens.panel(arm), end, areas).let { if (it > 0) fails += "tap at $c $arm: $it px at HOLD where the lens is not the press's END" }
                    }
                    // the act's first frame, then the slot's DISCARD: the press goes, the lens shows the shadow
                    lens.send("DISCARD after the act", CfwModes.motionDiscard(ListStaging.SLOT, s, ListStaging.LIST, cP), fails)
                    for (arm in Arm.entries) if (sim.panelCrc32(arm) != sim.shadowCrc32(arm)) fails += "tap at $c $arm: after the DISCARD the lens does not show the shadow"
                    lens.notifies()
                }
                if (frames != null) kept = frames
            }
            cases += Case("$tag-press", "the press at every cursor (Taps · glasses): the band row one notch forward (${ms.bandD}→${ms.press.pressD}), held, then the DISCARD",
                union(ms.press.areas(true) + ms.press.areas(false)), kept, lines, fails)
        }

        // ---- decisions AZ.4: the wrap where Main wraps — the edge at each end, then the phone's PLAY of the same pan in
        //      the wrap's slot beside the DISCARD naming the other end: every step drawn, the last one Main's paint at the
        //      wrapped row, END in the shadow as belief takes it, RIGHT's cursor there, the instance's last step notified
        if (ms.wraps) for ((e, name) in listOf(down to "wrap-down", up to "wrap-up")) {
            val fails = ArrayList<String>(); val lines = ArrayList<String>()
            val c = if (e.step > 0) ms.rows - 1 else 0
            val cPrime = c + e.step
            val to = cPrime.mod(ms.rows)
            val frames: MutableList<Frame>? = if (keepFrames) ArrayList() else null
            lens.now += 1_000
            lens.keyframe(m.truth(c, true), m.truth(c, false))
            lens.send("DISCARD to cursor $c", CfwModes.motionDiscard(ListStaging.SLOT, 1, ListStaging.LIST, c), fails)
            lens.notifies()
            frames?.add(Frame("before", lens.panel(Arm.LEFT), lens.panel(Arm.RIGHT)))
            val a = lens.fire(CfwModes.DEV_RING, e.eventId)
            s++
            if (a == null || a.outcome != DamageMsg.APPLIED_EDGE || a.cursor != c) fails += "$name from $c: the lens did not report the edge (${a?.describe()})"
            val pan = ms.wrapPan(e.step) ?: ms.pan(ListStaging.WHOLE, e.step)
            val p = ms.wrapParams(pan, cPrime)
            if (p == null) fails += "$name from $c: no parameters across the wrap"
            else {
                val play = Motion.play(pan.program, ListStaging.WRAP_SLOT, ms.period, Shell.WRAP_TAG, 0, p)
                lens.send("the wrap's PLAY and DISCARD", CfwModes.batch(listOf(play, CfwModes.motionDiscard(ListStaging.SLOT, (s + 1 - 0x4000) and 0xFFFF, ListStaging.LIST, to)), engine = true, bind = true), fails)
                val t0 = lens.now
                frames?.add(Frame("step 1", lens.panel(Arm.LEFT), lens.panel(Arm.RIGHT)))     // the PLAY's own present
                for (k in 2..ms.t) { lens.wake(t0 + (k - 1).toLong() * ms.period); frames?.add(Frame("step $k", lens.panel(Arm.LEFT), lens.panel(Arm.RIGHT))) }
                lens.wake(t0 + ms.t.toLong() * ms.period)
                for (arm in Arm.entries) {
                    val left = arm == Arm.LEFT
                    differ(lens.panel(arm), m.truth(to, left), pan.areas(left)).let { if (it > 0) fails += "$name $arm: $it px of the last step not Main's paint at $to" }
                    val belief = m.truth(c, left).copy().also { ms.paintEnd(pan, p, left, it) }
                    differ(lens.shadow(arm), belief, null).let { if (it > 0) fails += "$name $arm: $it px where END in the shadow is not what belief takes" }
                    differ(lens.shadow(arm), m.truth(to, left), pan.areas(left)).let { if (it > 0) fails += "$name $arm: $it px of END not Main's paint at $to" }
                    if (sim.panelCrc32(arm) != sim.shadowCrc32(arm)) fails += "$name $arm: after the last step the lens does not show the shadow"
                    if (sim.bindForTest(arm).lists[ListStaging.LIST].cursor != to) fails += "$name $arm: the list's cursor ${sim.bindForTest(arm).lists[ListStaging.LIST].cursor}, not $to"
                }
                val bodies = lens.notifies()
                if (bodies.none { it.slot == ListStaging.WRAP_SLOT && it.outcome == DamageMsg.OUTCOME_DREW } || bodies.any { it.outcome == 2 })
                    fails += "$name: RIGHT's notifies ${bodies.map { it.describe() }}"
                lines += "$name from $c → $to: PLAY slot ${ListStaging.WRAP_SLOT} prog ${pan.prog4} P ${p.joinToString(",")}, ${ms.t} steps at P ${ms.period}"
            }
            cases += Case("$tag-$name", "the wrap from row $c to row $to: the edge, then the phone's PLAY of the pan across it (decisions AZ.4)",
                union(pan.areasL + pan.areasR), frames ?: emptyList(), lines, fails)
        }
        if (ms.ring) ringCases(ms, m, list, lens, keepFrames, tag, { ++s }, cases)
        // K1-1: another effect's staging is checked for its pans and its wrap; the press, the taps and keeping rows current
        // are the effect's no more than Depth's (the same records, the same press)
        if (effect != ScrollEffect.DEPTH || pansOnly) return cases to tableLine

        // ---- the report-only tap entry (Taps · report)
        run {
            val fails = ArrayList<String>()
            val ro = BoundList.Entry(tap.entry, CfwModes.EV_TAP, 0, tap.period, CfwModes.PROG_NONE, tap.params, null)
            lens.send("the report-only SET", list.set(ro), fails)
            for (c in 0 until ms.rows) one(ro, c, fails, ArrayList(), null) { _, _, _ -> fails += "the report-only entry drew" }
            for (arm in Arm.entries) if (sim.panelCrc32(arm) != sim.shadowCrc32(arm)) fails += "$arm: the report-only entry left a picture on the lens"
            lens.send("the press's SET again", list.set(tap), fails)
            cases += Case("$tag-report", "the report-only tap entry at every cursor: a report with no fields 10–14, nothing drawn", Rect(0, 0, 1, 1), emptyList(), emptyList(), fails)
        }

        // ---- keeping rows current (decisions X): what a fire drew is always named by its report, and a COMMIT answers it
        val relay0: (Gray8, () -> Unit) -> Unit = { _, b -> b() }
        val music = rows.indexOfFirst { it.id == "music" }
        val c0 = music - 1
        var cur = list
        /** [e] fired from cursor [c] — the lens keyframed with Main at [c] (as the phone believes it) and its list's
         *  cursor put there — and played to its HOLD; RIGHT's report, or null when no entry matched (no report). */
        fun fireHeld(e: BoundList.Entry, c: Int, fails: MutableList<String>): DamageMsg.Applied? {
            lens.now += 1_000
            lens.keyframe(m.truth(c, true), m.truth(c, false))
            lens.send("DISCARD to cursor $c", CfwModes.motionDiscard(ListStaging.SLOT, 1, ListStaging.LIST, c), fails)
            lens.notifies()
            val a = lens.fire(CfwModes.DEV_RING, e.eventId) ?: return null
            val t0 = lens.now
            val p = a.params
            if (a.outcome == DamageMsg.APPLIED_DREW && p != null && a.prog != null)
                for (k in 1..ms.programAt(a.prog!!).stepsWith(p) + 1) lens.wake(t0 + (k - 1).toLong() * e.period)
            return a
        }
        /** The answer to report [a]: a COMMIT with the report's OWN program and P (X.1) — END into both shadows from the
         *  records the lens drew — and belief (Main at [cBefore] as keyframed, END painted from the phone's copy of the
         *  records the P names) equal to both lenses' shadows and panels. */
        fun commitReport(a: DamageMsg.Applied, cBefore: Int, fails: MutableList<String>, what: String) {
            val p = a.params ?: run { fails += "$what: the report carries no P (${a.describe()})"; return }
            val prog = a.prog!!; val cP = a.cursor!!
            lens.send("$what: COMMIT S ${a.s}", Motion.commit(ms.programAt(prog), ListStaging.SLOT, a.s!!, ListStaging.LIST, cP, p), fails)
            for (arm in Arm.entries) {
                val left = arm == Arm.LEFT
                val belief = m.truth(cBefore, left).copy().also { ms.paintEndOf(prog, p, left, it) }
                differ(lens.shadow(arm), belief, null).let { if (it > 0) fails += "$what $arm: $it px where the shadow after the COMMIT is not belief" }
                if (sim.panelCrc32(arm) != sim.shadowCrc32(arm)) fails += "$what $arm: after the COMMIT the lens does not show the shadow"
            }
            val bodies = lens.notifies()
            if (bodies.none { it.outcome == DamageMsg.OUTCOME_COMMITTED } || bodies.any { it.outcome == 2 }) fails += "$what: RIGHT's notifies after the COMMIT ${bodies.map { it.describe() }}"
        }
        fun named(l: BoundList, a: DamageMsg.Applied?, e: BoundList.Entry) = a != null && l.names(a.cursor ?: -1, e, a.prog, a.params, a.wrapped)

        // (a) a band form re-staged into a free slot: a notch before the switch draws the old slot and is named and
        //     committed after the switch has landed; a notch after it draws the new slot
        run {
            val fails = ArrayList<String>(); val lines = ArrayList<String>()
            (rows[music] as SampleRow).s = rows[music].summary().copy(detail = "Low Tide · q 6/12 · shuffle")     // the band form shows the detail; the row form does not
            m.repainted()
            val r = ms.restage(music, m.view, m.kit, relay0)
            val mv = (r as? ListStaging.Restage.Changed)?.band
            if (r !is ListStaging.Restage.Changed || r.strip.isNotEmpty() || mv == null) fails += "a change of Music's detail: ${(r as? ListStaging.Restage.Outgrown)?.why ?: r}, not its band form alone"
            else {
                val oldOff4 = ms.itemOffs[music]
                lens.write(mv.write.off, mv.write.bytes, mv.write.what, fails)           // into a slot no table names
                val before = fireHeld(down, c0, fails)
                if (before?.params?.get(1) != oldOff4.toShort().toInt()) fails += "the notch before the switch drew ${before?.describe()}, not the old slot ${oldOff4}"
                if (!named(cur, before, down)) fails += "the notch before the switch is not named by the list as it stands"
                lens.write(mv.switch.off, mv.switch.bytes, mv.switch.what, fails)        // the one write that names the new slot
                ms.apply(r)
                val after = ms.boundList(cur.mark, previous = cur)
                if (!named(after, before, down)) fails += "a report from before the switch is not named once the switch has gone (no previous)"
                if (named(ms.boundList(cur.mark), before, down)) fails += "the shape guard: without the previous list the old slot is still named"
                if (before != null) commitReport(before, c0, fails, "the notch before the switch, answered after it")
                val a2 = fireHeld(down, c0, fails)
                if (a2?.params?.get(1) != (mv.off / 4).toShort().toInt()) fails += "the notch after the switch drew ${a2?.describe()}, not the new slot ${mv.off / 4}"
                if (!named(after, a2, down)) fails += "the notch after the switch is not named"
                if (a2 != null) {
                    commitReport(a2, c0, fails, "the notch after the switch")
                    for (arm in Arm.entries) differ(lens.shadow(arm), m.truth(music, arm == Arm.LEFT), ms.pan(ListStaging.WHOLE, 1).areas(arm == Arm.LEFT))
                        .let { if (it > 0) fails += "after the switch $arm: $it px of END not Main's paint" }
                }
                // the item word's other entry is untouched: a notch onto the neighbour row draws its own band form
                val nb = music xor 1
                val a3 = fireHeld(if (nb > music) down else up, music, fails)
                if (!named(after, a3, if (nb > music) down else up) || a3?.cursor != nb) fails += "the notch onto row $nb (the item word's other entry) is not named: ${a3?.describe()}"
                else commitReport(a3, music, fails, "the notch onto the item word's other row")
                ms.release(oldOff4); after.previous = null
                if (ms.free[oldOff4 * 4] == null) fails += "the old slot is not free once the switch is acknowledged"
                cur = after
                lines += "Music's band form moved: ${mv.write.bytes.size} B into the free slot at off4 ${mv.off / 4} and the item word ${mv.switch.bytes.size} B, one flush; the old slot $oldOff4 free once acknowledged"
            }
            cases += Case("$tag-switch", "a band form re-staged into a free slot: a notch before the switch and one after, each named by its report and committed", Rect(0, 0, 1, 1), emptyList(), lines, fails)
        }

        // (b) a row form re-staged: its strip segment written with the list's entries cleared, SET again after
        run {
            val fails = ArrayList<String>(); val lines = ArrayList<String>()
            (rows[music] as SampleRow).s = rows[music].summary().copy(line = "Night Drive - Low Tides")
            m.repainted()
            val r = ms.restage(music, m.view, m.kit, relay0)
            if (r !is ListStaging.Restage.Changed || r.strip.isEmpty()) fails += "a change of Music's line: ${(r as? ListStaging.Restage.Outgrown)?.why ?: r}, not its strip segment"
            else {
                for (e in cur.entries) lens.send("CLEAR ${e.entry}", CfwModes.bindClear(e.entry), fails)
                if (fireHeld(down, c0, fails) != null) fails += "a notch while the entries are cleared still matched an entry"
                for (w in r.writes) lens.write(w.off, w.bytes, w.what, fails)
                ms.apply(r)
                val nl = ms.boundList(cur.mark, previous = if (r.band != null) cur else null)
                for (b in nl.sets()) lens.send("a SET again", b, fails)
                val a = fireHeld(down, c0, fails)
                if (!named(nl, a, down)) fails += "the notch after the strip's write is not named: ${a?.describe()}"
                if (a != null) {
                    commitReport(a, c0, fails, "the notch after the strip's write")
                    for (arm in Arm.entries) differ(lens.shadow(arm), m.truth(music, arm == Arm.LEFT), ms.pan(ListStaging.WHOLE, 1).areas(arm == Arm.LEFT))
                        .let { if (it > 0) fails += "after the strip's write $arm: $it px of END not Main's paint" }
                }
                r.band?.let { ms.release(it.oldOff4) }; nl.previous = null
                cur = nl
                lines += "Music's row form re-staged with the entries cleared: ${r.bytes} B written (${r.writes.joinToString { "${it.what} ${it.bytes.size} B" }}), then ${nl.entries.size} SETs"
            }
            cases += Case("$tag-strip", "a row form re-staged with the list withdrawn: no notch matches meanwhile; after the SETs a notch is named and committed",
                Rect(0, 0, 1, 1), emptyList(), lines, fails)
        }

        // (c) a row stale by several changes: the notch is named and committed, belief equal to both lenses; the ordinary
        //     repaint then brings the changed cell
        run {
            val fails = ArrayList<String>(); val lines = ArrayList<String>()
            for ((i, pr) in listOf(0.70, 0.83, 0.97).withIndex())
                (rows[music] as SampleRow).s = rows[music].summary().copy(progress = pr, detail = "Low Tide · q ${7 + i}/12 · shuffle")
            m.repainted()
            val frames: MutableList<Frame>? = if (keepFrames) ArrayList() else null
            frames?.add(Frame("before", lens.panel(Arm.LEFT), lens.panel(Arm.RIGHT)))
            val a = fireHeld(down, c0, fails)
            if (!named(cur, a, down)) fails += "the notch onto the stale row is not named: ${a?.describe()}"
            if (a != null) {
                frames?.add(Frame("held", lens.panel(Arm.LEFT), lens.panel(Arm.RIGHT)))
                commitReport(a, c0, fails, "the notch onto the stale row")
                val areas = { left: Boolean -> ms.pan(ListStaging.WHOLE, 1).areas(left) }
                if (Arm.entries.none { arm -> differ(lens.shadow(arm), m.truth(music, arm == Arm.LEFT), areas(arm == Arm.LEFT)) > 0 })
                    fails += "the shape guard: the stale row's staged pictures equal Main's truth"
                // the ordinary repaint (the phone's frame of what differs from belief)
                lens.keyframe(m.truth(music, true), m.truth(music, false))
                frames?.add(Frame("repainted", lens.panel(Arm.LEFT), lens.panel(Arm.RIGHT)))
                for (arm in Arm.entries) differ(lens.panel(arm), m.truth(music, arm == Arm.LEFT), null).let { if (it > 0) fails += "after the repaint $arm: $it px not Main's paint" }
                lines += "Music's row three changes behind its staging: the notch named and committed from the staged copies, then the repaint"
            }
            val pan = ms.pan(ListStaging.WHOLE, 1)
            cases += Case("$tag-stale", "a row stale by several changes: a COMMIT from the staged copies, belief equal to both lenses, then the repaint",
                union(pan.areasL + pan.areasR), frames ?: emptyList(), lines, fails)
        }
        return cases to tableLine
    }

    /**
     * K1-1: the pans re-made alone. Main staged with Depth and its list SET; the scroll rows ask for Slide: the re-made pans
     * go into the free pan slot — a range that meets no record of the staging (no strip, band form, item table or press
     * byte) — then a notch fired BEFORE the SETs draws the old pan and is named (the list's previous) and committed from
     * the old program; the SETs go; a notch after them draws the new pan, named and committed, its END Main's paint. The
     * old pans released, Depth re-made back into the first slot gives that slot's bytes as staged, byte for byte.
     */
    fun reprogramCase(text: TextRasterizer, height: Int, kib: Int, depth: Int = 8, stage5: Boolean = false): Case {
        val m = MainRig(text, height, sampleRows(), depth)
        val cacheSize = kib * 1024
        val region = CfwModes.mainRegion(cacheSize, bind = true)
        val ms = m.staging(cacheSize, region, stage5 = stage5)
        val tag = "h$height-${kib}k${if (stage5) "-s5" else ""}-reprogram"
        val fails = ArrayList<String>(); val lines = ArrayList<String>()
        val slots = ms.progSlots ?: return Case(tag, "the pans re-made alone", Rect(0, 0, 1, 1), emptyList(),
            listOf("no spare pan slot in this region: a change of the scroll rows is a full staging (${ms.bytes} B)"), emptyList())
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = features(stage5); it.clockIsOsTick = true }
        val lens = Lens(sim, cacheSize)
        for (arm in Arm.entries) {
            sim.conformanceLease(arm, true, lens.now)
            sim.conformanceControl(arm, DamageMsg.OP_CACHE_SIZE, kib, lens.now)
            sim.conformanceControl(arm, DamageMsg.OP_FLAGS_SET, flags(stage5), lens.now)
        }
        for (b in ms.blobs) lens.write(b.off, b.bytes, b.name, fails)
        var cur = ms.boundList(1)
        for (s in cur.sets()) lens.send("a SET", s, fails)
        lens.send("the LIST", cur.listMessage(0), fails)
        val down = cur.entryFor(CfwModes.EV_UPROLL)!!
        val c0 = ms.rows / 2 - 1
        fun fireHeld(c: Int, period: Int): DamageMsg.Applied? {
            lens.now += 1_000
            lens.keyframe(m.truth(c, true), m.truth(c, false))
            lens.send("DISCARD to cursor $c", CfwModes.motionDiscard(ListStaging.SLOT, 1, ListStaging.LIST, c), fails)
            lens.notifies()
            val a = lens.fire(CfwModes.DEV_RING, down.eventId) ?: return null
            val t0 = lens.now
            if (a.outcome == DamageMsg.APPLIED_DREW && a.params != null && a.prog != null)
                for (k in 1..ms.programAt(a.prog!!).stepsWith(a.params!!) + 1) lens.wake(t0 + (k - 1).toLong() * period)
            return a
        }
        fun commit(a: DamageMsg.Applied?, what: String) {
            if (a == null || a.outcome != DamageMsg.APPLIED_DREW) { fails += "$what: no drawn report (${a?.describe()})"; return }
            val p = a.params!!; val prog = a.prog!!; val cP = a.cursor!!
            if (!cur.names(cP, down, prog, p, a.wrapped)) fails += "$what: the report is not named by the list as it stands (${a.describe()})"
            lens.send("$what: COMMIT", Motion.commit(ms.programAt(prog), ListStaging.SLOT, a.s!!, ListStaging.LIST, cP, p), fails)
            for (arm in Arm.entries) {
                val left = arm == Arm.LEFT
                val belief = m.truth((cP - down.step).mod(ms.rows), left).copy().also { ms.paintEndOf(prog, p, left, it) }
                differ(lens.shadow(arm), belief, null).let { if (it > 0) fails += "$what $arm: $it px where the shadow after the COMMIT is not belief" }
                differ(lens.shadow(arm), m.truth(cP, left), ms.programAt(prog).tracks.map { it.area(left) }).let { if (it > 0) fails += "$what $arm: $it px of END not Main's paint at $cP" }
                if (sim.panelCrc32(arm) != sim.shadowCrc32(arm)) fails += "$what $arm: after the COMMIT the lens does not show the shadow"
            }
            lens.notifies()
        }
        /** The re-make for [want] into the free slot, checked against every record's range, and written. */
        fun remade(want: ListStaging.ScrollMotion): ListStaging.Programs? {
            val p = (ms.remake(want) as? ListStaging.Remake.Owed)?.programs ?: run { fails += "no re-make for ${want.words()}: ${ms.remake(want)}"; return null }
            if (p.off != slots[p.slot] || p.bytes.size > ms.progSlot) fails += "${want.words()}: ${p.bytes.size} B at ${p.off}, not inside slot ${p.slot} (${slots[p.slot]}, ${ms.progSlot} B)"
            val press = ms.press.prog4 * 4
            for (b in ms.blobs) {
                // the programs record's own span holds the press and the first pan slot: only the press is a record there
                val (lo, hi) = if (b.name == "programs") press to press + ms.press.program.bytes.size else b.off to b.off + b.bytes.size
                if (p.off < hi && lo < p.off + p.bytes.size) fails += "${want.words()}: the re-make's write ${p.off}..${p.off + p.bytes.size} meets ${b.name} ($lo..$hi)"
            }
            lens.write(p.off, p.bytes, "the re-made pans", fails)
            return p
        }
        val p1 = remade(ListStaging.ScrollMotion.DEFAULT.copy(effect = ScrollEffect.SLIDE))
        if (p1 != null) {
            val before = fireHeld(c0, down.period)                      // the lens still holds Depth's entries
            commit(before, "a notch before the SETs")
            ms.switchTo(p1)
            val nl = ms.boundList(cur.mark, previous = cur)
            val oldProg = before?.prog
            cur = nl
            // a report from before the switch, answered after it: named through the previous list, END from the old pan
            val during = fireHeld(c0, down.period)
            if (during?.prog != oldProg) fails += "a notch after the write, before the SETs, drew ${during?.describe()}, not the old pan $oldProg"
            commit(during, "a notch after the write, before the SETs")
            val sets = nl.sets()
            for (s in sets) lens.send("a SET of the re-made pans", s, fails)
            val after = fireHeld(c0, nl.entryFor(CfwModes.EV_UPROLL)!!.period)
            if (after?.prog != nl.entryFor(CfwModes.EV_UPROLL)!!.prog4) fails += "a notch after the SETs drew ${after?.describe()}, not the re-made pan"
            commit(after, "a notch after the SETs")
            ms.releasePrograms(); nl.previous = null
            if (runCatching { ms.programAt(oldProg ?: -1) }.isSuccess) fails += "the old pans are still named after their release"
            lines += "Depth → Slide: ${p1.bytes.size} B into the free slot at ${p1.off} and ${sets.size} SETs ${sets.sumOf { it.size }} B, against a whole staging's ${ms.bytes} B"
            // and back: Depth into the first slot is that slot's bytes as staged
            val p2 = remade(ListStaging.ScrollMotion.DEFAULT)
            if (p2 != null) {
                val base = ms.blobs.first { it.name == "programs" }
                val slot0 = base.bytes.copyOfRange(slots[0] - base.off, slots[0] - base.off + p2.bytes.size)
                if (p2.slot != 0 || !p2.bytes.contentEquals(slot0)) fails += "Depth re-made into slot ${p2.slot} is not the first slot's bytes as staged"
                ms.switchTo(p2)
                val nl2 = ms.boundList(cur.mark, previous = cur)
                cur = nl2
                for (s in nl2.sets()) lens.send("a SET of Depth's pans again", s, fails)
                commit(fireHeld(c0, nl2.entryFor(CfwModes.EV_UPROLL)!!.period), "a notch on Depth again")
                ms.releasePrograms(); nl2.previous = null
                lines += "Slide → Depth: ${p2.bytes.size} B into slot 0, the bytes it was staged with"
            }
        }
        return Case(tag, "the pans re-made alone (Depth → Slide → Depth): the free slot only, then SETs; notches before and after each named and committed",
            Rect(0, 0, 1, 1), emptyList(), lines, fails)
    }

    /**
     * C3 DM-1 (decisions AD's S6; the brief's item 5): a change between an effect of pitch −rowH and one of +rowH, at stage
     * 5. Main staged with Depth and its list SET; the rows ask for Crush: K1-1's re-make writes Crush's pans into the free
     * slot (a range that meets no record of the staging — no strip write); a notch fired BEFORE the switch draws Depth's
     * pan with the old mark (P3 refuses its report: a mark that is not the phone's, `LocalRules.scrollRule`); then ONE
     * batch carries the list's LIST (LIST-RING on a ring list) with pitch +rowH and a fresh mark AHEAD of the SETs naming
     * Crush's pans — each lens applies it whole, its list at the new pitch, mark and the cursor kept; a notch after it
     * draws Crush's pan, named by the new list and committed, its END Main's paint. And back to Depth the same way.
     */
    fun pitchSwitchCase(text: TextRasterizer, height: Int, kib: Int, depth: Int = 8): Case {
        val m = MainRig(text, height, sampleRows(), depth)
        val cacheSize = kib * 1024
        val region = CfwModes.mainRegion(cacheSize, bind = true)
        val fx = effectsAt(true)
        val ms = m.staging(cacheSize, region, stage5 = true)
        val tag = "h$height-${kib}k-s5-pitchswitch"
        val fails = ArrayList<String>(); val lines = ArrayList<String>()
        val slots = ms.progSlots ?: return Case(tag, "the switch between the pitch forms", Rect(0, 0, 1, 1), emptyList(),
            listOf("no spare pan slot in this region: a change of the scroll rows is a full staging (${ms.bytes} B)"), emptyList())
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = features(true); it.clockIsOsTick = true }
        val lens = Lens(sim, cacheSize)
        for (arm in Arm.entries) {
            sim.conformanceLease(arm, true, lens.now)
            sim.conformanceControl(arm, DamageMsg.OP_CACHE_SIZE, kib, lens.now)
            sim.conformanceControl(arm, DamageMsg.OP_FLAGS_SET, flags(true), lens.now)
        }
        for (b in ms.blobs) lens.write(b.off, b.bytes, b.name, fails)
        var mark = 1
        var cur = ms.boundList(mark)
        for (s in cur.sets()) lens.send("a SET", s, fails)
        lens.send("the LIST", cur.listMessage(0), fails)
        val c0 = ms.rows / 2 - 1
        fun fireHeld(c: Int, e: BoundList.Entry): DamageMsg.Applied? {
            lens.now += 1_000
            lens.keyframe(m.truth(c, true), m.truth(c, false))
            lens.send("DISCARD to cursor $c", CfwModes.motionDiscard(ListStaging.SLOT, 1, ListStaging.LIST, c), fails)
            lens.notifies()
            val a = lens.fire(CfwModes.DEV_RING, e.eventId) ?: return null
            val t0 = lens.now
            if (a.outcome == DamageMsg.APPLIED_DREW && a.params != null && a.prog != null)
                for (k in 1..ms.programAt(a.prog!!).stepsWith(a.params!!) + 1) lens.wake(t0 + (k - 1).toLong() * e.period)
            return a
        }
        fun commit(a: DamageMsg.Applied?, e: BoundList.Entry, what: String) {
            if (a == null || a.outcome != DamageMsg.APPLIED_DREW) { fails += "$what: no drawn report (${a?.describe()})"; return }
            val p = a.params!!; val prog = a.prog!!; val cP = a.cursor!!
            if (a.mark != mark) fails += "$what: the report's mark ${a.mark}, not the list's $mark"
            if (!cur.names(cP, e, prog, p, a.wrapped)) fails += "$what: the report is not named by the list as it stands (${a.describe()})"
            lens.send("$what: the riding LIST and COMMIT", CfwModes.batch(listOf(cur.listMessage(0, mark), Motion.commit(ms.programAt(prog), ListStaging.SLOT, a.s!!, ListStaging.LIST, cP, p)), engine = true, bind = true), fails)
            for (arm in Arm.entries) {
                val left = arm == Arm.LEFT
                val belief = m.truth((cP - e.step).mod(ms.rows), left).copy().also { ms.paintEndOf(prog, p, left, it) }
                differ(lens.shadow(arm), belief, null).let { if (it > 0) fails += "$what $arm: $it px where the shadow after the COMMIT is not belief" }
                differ(lens.shadow(arm), m.truth(cP, left), ms.programAt(prog).tracks.map { it.area(left) }).let { if (it > 0) fails += "$what $arm: $it px of END not Main's paint at $cP" }
                if (sim.panelCrc32(arm) != sim.shadowCrc32(arm)) fails += "$what $arm: after the COMMIT the lens does not show the shadow"
            }
            lens.notifies()
        }
        /** The switch to [want]: re-made into the free slot (no record of the staging met), written; a notch before the
         *  switch's batch (the old entries, the old mark); the batch; a notch after it. */
        fun switchTo(want: ListStaging.ScrollMotion) {
            val p = (ms.remake(want, fx) as? ListStaging.Remake.Owed)?.programs ?: run { fails += "no re-make for ${want.words()}: ${ms.remake(want, fx)}"; return }
            if (p.off != slots[p.slot] || p.bytes.size > ms.progSlot) fails += "${want.words()}: ${p.bytes.size} B at ${p.off}, not inside slot ${p.slot}"
            val press = ms.press.prog4 * 4
            for (b in ms.blobs) {
                val (lo, hi) = if (b.name == "programs") press to press + ms.press.program.bytes.size else b.off to b.off + b.bytes.size
                if (p.off < hi && lo < p.off + p.bytes.size) fails += "${want.words()}: the re-make's write ${p.off}..${p.off + p.bytes.size} meets ${b.name} ($lo..$hi) — a strip or record write"
            }
            lens.write(p.off, p.bytes, "the re-made pans", fails)
            val down0 = cur.entryFor(CfwModes.EV_UPROLL)!!
            val before = fireHeld(c0, down0)                                     // the lens still holds the old form
            val oldMark = mark
            val oldPitch = cur.pitch
            ms.switchTo(p)
            mark++
            val nl = ms.boundList(mark, previous = cur)
            if (nl.pitch == oldPitch) fails += "${want.words()}: the list's pitch did not change (${nl.pitch})"
            // P3's rule for a report computed at the old pitch: its mark is not the phone's — never adopted against the new form
            if (before == null || before.mark != oldMark || before.mark == mark) fails += "${want.words()}: the notch before the switch reports mark ${before?.mark} (old $oldMark, new $mark)"
            // ONE batch: the LIST with the new pitch and a fresh mark ahead of the SETs (the batch's order: a fire on a lens
            // runs before it whole or after it whole; a refused LIST leaves the old entries with the old pitch)
            val batch = CfwModes.batch(listOf(nl.listMessage(0, mark)) + nl.sets(), engine = true, bind = true)
            lens.send("the switch's batch (LIST then SETs)", batch, fails)
            for (arm in Arm.entries) sim.bindForTest(arm).lists[ListStaging.LIST].let { l ->
                if (l.pitch != nl.pitch || l.mark != mark || l.ring != nl.ring) fails += "${want.words()} $arm: the lens's list after the batch: pitch ${l.pitch} mark ${l.mark} ring ${l.ring}, not ${nl.pitch} $mark ${nl.ring}"
            }
            lens.notifies()
            cur = nl
            val down = nl.entryFor(CfwModes.EV_UPROLL)!!
            val after = fireHeld(c0, down)
            if (after?.prog != down.prog4) fails += "${want.words()}: a notch after the switch drew ${after?.describe()}, not the new pan ${down.prog4}"
            commit(after, down, "${want.words()}: a notch after the switch")
            ms.releasePrograms(); nl.previous = null
            lines += "${p.motion.effect.word} (pitch ${nl.pitch}, ${ms.plays.effect.word}${if (ms.plays.v2) " v2" else ""}): ${p.bytes.size} B into the free slot at ${p.off}, then ONE batch of ${batch.size} B " +
                "(the LIST ${nl.listMessage(0, mark).size} B ahead of ${nl.sets().size} SETs); the notch before it reported mark ${before?.mark} (refused by P3), the one after committed"
        }
        switchTo(ListStaging.ScrollMotion.DEFAULT.copy(effect = ScrollEffect.CRUSH))
        if (ms.plays != wm.damage.core.motion.ScrollEffects.Plays(ScrollEffect.CRUSH, true)) fails += "Crush does not play itself at stage 5: ${ms.plays}"
        switchTo(ListStaging.ScrollMotion.DEFAULT)
        return Case(tag, "the switch between the pitch forms (Depth → Crush → Depth, S6): the free slot only, then ONE batch with the LIST ahead of the SETs; a notch before refused by its mark, one after committed",
            Rect(0, 0, 1, 1), emptyList(), lines, fails)
    }

    /**
     * C3 DM-0: the ring list's wrap (the DM spec §2, §5.1). At each end the notch fires through the input hook like any
     * other — a local instance draws, the 115 says drew with field 18 = 1 and the other end's cursor, nothing is PLAYed in
     * slot 1; every step drawn, the last one Main's paint at the wrapped row; the answer (the LIST-RING riding before the
     * COMMIT of the report's own parameters) leaves both lenses' lists ringing, END in the shadow belief's. A ring staging
     * has no wrap pan (no phone PLAY across its wrap: C3 X2M0, decisions AZ (2)).
     */
    private fun ringCases(ms: ListStaging, m: MainRig, list: BoundList, lens: Lens, keepFrames: Boolean, tag: String, nextS: () -> Int, cases: MutableList<Case>) {
        val sim = lens.sim
        val down = list.entryFor(CfwModes.EV_UPROLL)!!; val up = list.entryFor(CfwModes.EV_DOWNROLL)!!
        fun from(c: Int, fails: MutableList<String>, frames: MutableList<Frame>?) {
            lens.now += 1_000
            lens.keyframe(m.truth(c, true), m.truth(c, false))
            lens.send("DISCARD to cursor $c", CfwModes.motionDiscard(ListStaging.SLOT, 1, ListStaging.LIST, c), fails)
            lens.notifies()
            frames?.add(Frame("before", lens.panel(Arm.LEFT), lens.panel(Arm.RIGHT)))
        }
        fun ended(name: String, c: Int, to: Int, pan: ListStaging.Pan, p: IntArray, fails: MutableList<String>) {
            for (arm in Arm.entries) {
                val left = arm == Arm.LEFT
                val belief = m.truth(c, left).copy().also { ms.paintEndOf(pan.prog4, p, left, it) }
                differ(lens.shadow(arm), belief, null).let { if (it > 0) fails += "$name $arm: $it px where END in the shadow is not what belief takes" }
                differ(lens.shadow(arm), m.truth(to, left), pan.areas(left)).let { if (it > 0) fails += "$name $arm: $it px of END not Main's paint at $to" }
                if (sim.panelCrc32(arm) != sim.shadowCrc32(arm)) fails += "$name $arm: the lens does not show the shadow"
                val l = sim.bindForTest(arm).lists[ListStaging.LIST]
                if (l.cursor != to) fails += "$name $arm: the list's cursor ${l.cursor}, not $to"
                if (!l.ring) fails += "$name $arm: the list no longer rings (S5)"
            }
        }
        for ((e, dir) in listOf(down to "down", up to "up")) {
            val c = if (e.step > 0) ms.rows - 1 else 0
            val to = (c + e.step).mod(ms.rows)
            val pan = ms.pan(ListStaging.WHOLE, e.step)
            val want = list.predict(c, e) as? BoundList.Fire.Drew
            // ---- the wrap notch, on the glasses
            run {
                val name = "ring-wrap-$dir"
                val fails = ArrayList<String>(); val lines = ArrayList<String>()
                val frames: MutableList<Frame>? = if (keepFrames) ArrayList() else null
                from(c, fails, frames)
                val a = lens.fire(CfwModes.DEV_RING, e.eventId)
                val s = nextS()
                // C3 X2M0 (decisions AZ (2)): the shell finds no wrap pan on a ring staging — an edge reported there would go as
                // its frames, as at 416 and 480
                if (ms.wrapPan(e.step) != null) fails += "$name: a ring staging gives the shell a wrap pan for step ${e.step}"
                if (a == null || a.outcome != DamageMsg.APPLIED_DREW || a.cursor != to || a.field18 != 1L) fails += "$name from $c: not a drawn ring fire to $to with field 18 = 1 (${a?.describe()})"
                else if (want == null || want.cursor != to || a.prog != want.prog4 || !a.params.contentEquals(want.p)) fails += "$name from $c: the report ${a.describe()} is not the prediction at v (${want?.p?.joinToString(",")})"
                else {
                    val p = a.params!!
                    if (!list.names(to, e, a.prog, p, wrapped = true)) fails += "$name: the list does not name its own wrapped report (S4)"
                    val t0 = lens.now
                    val steps = pan.program.stepsWith(p)
                    for (k in 1..steps) { lens.wake(t0 + (k - 1).toLong() * e.period); frames?.add(Frame("step $k", lens.panel(Arm.LEFT), lens.panel(Arm.RIGHT))) }
                    lens.wake(t0 + steps.toLong() * e.period)
                    for (arm in Arm.entries) {
                        val left = arm == Arm.LEFT
                        if (sim.motionForTest(arm).slots[ListStaging.WRAP_SLOT] != null) fails += "$name $arm: an instance in the wrap's slot ${ListStaging.WRAP_SLOT}"
                        differ(lens.panel(arm), m.truth(to, left), pan.areas(left)).let { if (it > 0) fails += "$name $arm: $it px of the last step not Main's paint at $to" }
                    }
                    lens.send("the riding LIST-RING and the COMMIT", CfwModes.batch(listOf(list.listMessage(0),
                        Motion.commit(pan.program, ListStaging.SLOT, s, ListStaging.LIST, to, p)), engine = true, bind = true), fails)
                    ended(name, c, to, pan, p, fails)
                    val bodies = lens.notifies()
                    if (bodies.none { it.outcome == DamageMsg.OUTCOME_COMMITTED } || bodies.any { it.outcome == 2 }) fails += "$name: RIGHT's notifies after the COMMIT ${bodies.map { it.describe() }}"
                    lines += "$name from $c → $to on the glasses: field 18 = 1, prog ${pan.prog4} P ${p.joinToString(",")}, $steps steps at P ${e.period}; slot ${ListStaging.WRAP_SLOT} empty"
                }
                cases += Case("$tag-$name", "the wrap from row $c to row $to on a ring list: fired through the hook like any notch (field 18), committed (DM-0)",
                    union(pan.areasL + pan.areasR), frames ?: emptyList(), lines, fails)
            }
        }
    }

    internal fun union(rs: List<Rect>): Rect = rs.reduce { a, b -> a.union(b) }
}
