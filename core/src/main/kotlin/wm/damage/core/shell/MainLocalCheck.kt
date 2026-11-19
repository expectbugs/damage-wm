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
 * DRAW2, ENGINE and BIND in force: Main staged ([MainStaging]) and its list SET ([MainStaging.boundList]: the two scroll
 * entries and the press; LIST with its mark). Then, at EVERY cursor and for both directions, the gesture fired through
 * the simulator's input hook on both lenses (`FIRMWARE.md` §6.2):
 * - RIGHT's report (field 115) is what [BoundList.predict] says — the outcome, the cursor, the program and P0..P3;
 * - when it drew: after its last step the lens holds Main's OWN paint at the moved cursor in the program's areas (the
 *   compositor's truth of `ContentKit.paintList` over `MainSurface`), Main at the old cursor everywhere else; and the
 *   COMMIT that answers it writes END into the shadow — what [MainStaging.paintEndOf] puts into belief — byte for byte;
 * - the press (`Taps · glasses`) at every cursor: after its three steps the lens holds the band row at the cursor drawn
 *   at the forward depth (the truth with the band's plane one notch nearer), until the DISCARD after the act's frame;
 * - the report-only tap entry (`Taps · report`): a report with no fields 10–14 and nothing drawn;
 * - a re-staged row: Music's summary changes, the row is re-staged IN PLACE (its writes inside its own slots, the mark
 *   kept), and a notch onto it lands on its new paint.
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

        fun staging(cacheSize: Int, region: IntRange): MainStaging =
            MainStaging.build(layout, view, kit, listD, bandD, cacheSize, region.first, region.last + 1)
    }

    /** Runs every case at [heights] × [cachesKib]; [frames] keeps the strips' frames (the desktop writes them). */
    fun run(text: TextRasterizer, heights: List<Int> = ShellSettings.HEIGHTS, cachesKib: List<Int> = listOf(160, 256),
            depth: Int = 8, frames: Boolean = true): Result {
        val cases = ArrayList<Case>()
        val table = ArrayList<String>()
        for (h in heights) for (kib in cachesKib) {
            val r = runOne(text, h, kib, depth, frames)
            cases += r.first
            table += r.second
        }
        return Result(cases, table)
    }

    private const val FEATURES_C2 = 0x5ff

    private class Lens(val sim: GlassFirmwareSim, val cacheSize: Int) {
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
    private fun differ(nibbles: ByteArray, want: Gray8, areas: List<Rect>?): Int {
        var n = 0
        val rs = areas ?: listOf(Rect(0, 0, Geometry.PANEL_W, Geometry.PANEL_H))
        for (r in rs) for (y in r.y until r.bottom) for (x in r.x until r.right)
            if (level(nibbles, x, y) != Pack.level(want[x, y].toInt() and 0xFF)) n++
        return n
    }

    private fun runOne(text: TextRasterizer, height: Int, kib: Int, depth: Int, keepFrames: Boolean): Pair<List<Case>, String> {
        val rows = sampleRows()
        val m = MainRig(text, height, rows, depth)
        val cacheSize = kib * 1024
        val region = CfwModes.mainRegion(cacheSize, bind = true)
        val ms = m.staging(cacheSize, region)
        val list = ms.boundList(1)
        val tag = "h$height-${kib}k"
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = FEATURES_C2; it.clockIsOsTick = true }
        val lens = Lens(sim, cacheSize)
        val setup = ArrayList<String>()
        for (arm in Arm.entries) {
            sim.conformanceLease(arm, true, lens.now)
            sim.conformanceControl(arm, DamageMsg.OP_CACHE_SIZE, kib, lens.now)
            sim.conformanceControl(arm, DamageMsg.OP_FLAGS_SET, DamageMsg.FLAG_DRAW2 or DamageMsg.FLAG_ENGINE or DamageMsg.FLAG_BIND, lens.now)
        }
        for (b in ms.blobs) lens.write(b.off, b.bytes, b.name, setup)
        for (s in list.sets()) lens.send("a SET", s, setup)
        lens.send("the LIST", list.listMessage(0), setup)
        val whole = ms.pan(MainStaging.WHOLE, 1)
        val inside = region.first <= ms.start && ms.end <= region.last + 1
        if (!inside) setup += "the staging ${ms.start}..${ms.end} leaves its region ${region.first}..${region.last + 1}"
        val tableLine = "$tag: ${ms.bytes} B at ${ms.start}..${ms.end} (${if (region.last + 1 == cacheSize) "shared reserve" else "Main's region"}), " +
            "strip ${ms.blobs[0].bytes.size} B in a ${ms.strip.slot} B slot, band forms ${ms.blobs.drop(1).take(ms.rows).sumOf { it.bytes.size }} B in ${ms.bands.sumOf { it.slot }} B of slots, " +
            "programs ${ms.blobs.last().bytes.size} B; whole list ${whole.kA}+${whole.kB} rows, ${whole.areaPx} px a step, P ${ms.period} T ${ms.t}, press ${ms.bandD}→${ms.press.pressD}"
        val cases = ArrayList<Case>()
        if (setup.isNotEmpty()) { cases += Case("$tag-setup", "the staging written and the list SET", Rect(0, 0, 1, 1), emptyList(), emptyList(), setup); return cases to tableLine }

        val down = list.entryFor(CfwModes.EV_UPROLL)!!; val up = list.entryFor(CfwModes.EV_DOWNROLL)!!; val tap = list.entryFor(CfwModes.EV_TAP)!!
        var s = 0
        /** One fire of [e] from cursor [c]: the cursor set, the lens keyframed with Main at [c], the gesture through the
         *  hook, its report against the prediction, and — when it drew — its last step and its answer. */
        fun one(e: BoundList.Entry, c: Int, fails: MutableList<String>, lines: MutableList<String>, frames: MutableList<Frame>?, answer: (Int, IntArray, Int) -> Unit) {
            lens.now += 1_000
            lens.keyframe(m.truth(c, true), m.truth(c, false))
            lens.send("DISCARD to cursor $c", CfwModes.motionDiscard(MainStaging.SLOT, 1, MainStaging.LIST, c), fails)
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
                    val pan = ms.pan(MainStaging.WHOLE, e.step)
                    for (arm in Arm.entries) {
                        val left = arm == Arm.LEFT
                        val areas = pan.areas(left)
                        val held = lens.panel(arm)
                        differ(held, m.truth(cP, left), areas).let { if (it > 0) fails += "$name from $c $arm: $it px at HOLD where the lens is not Main's paint at $cP" }
                        differ(held, m.truth(cP - e.step, left), null).let { n -> val inA = differ(held, m.truth(cP - e.step, left), areas); if (n - inA > 0) fails += "$name from $c $arm: ${n - inA} px outside the areas changed at HOLD" }
                    }
                    lens.send("COMMIT S $s", Motion.commit(ms.programAt(prog4), MainStaging.SLOT, s, MainStaging.LIST, cP, p), fails)
                    for (arm in Arm.entries) {
                        val left = arm == Arm.LEFT
                        val belief = m.truth(cP - e.step, left).copy().also { ms.paintEndOf(prog4, p, left, it) }
                        differ(lens.shadow(arm), belief, null).let { if (it > 0) fails += "$name from $c $arm: $it px where COMMIT's END in the shadow is not what belief takes" }
                        differ(lens.shadow(arm), m.truth(cP, left), pan.areas(left)).let { if (it > 0) fails += "$name from $c $arm: $it px of END not Main's paint at $cP" }
                        if (sim.panelCrc32(arm) != sim.shadowCrc32(arm)) fails += "$name from $c $arm: after the COMMIT the lens does not show the shadow"
                    }
                    val bodies = lens.notifies()
                    if (bodies.none { it.outcome == DamageMsg.OUTCOME_COMMITTED } || bodies.any { it.outcome == 2 }) fails += "$name from $c: RIGHT's notifies after the COMMIT ${bodies.map { it.describe() }}"
                }
                if (frames != null) kept = frames
            }
            val pan = ms.pan(MainStaging.WHOLE, e.step)
            cases += Case("$tag-$name", "a notch $name at every cursor: the report as predicted, the last step Main's paint, COMMIT's END belief's; the strip is the notch from row $mid",
                union(pan.areasL + pan.areasR), kept, lines, fails)
        }

        // ---- the press at every cursor (Taps · glasses)
        run {
            val fails = ArrayList<String>(); val lines = ArrayList<String>()
            var kept: List<Frame> = emptyList()
            for (c in 0 until ms.rows) {
                val frames: MutableList<Frame>? = if (keepFrames && c == ms.rows / 2) ArrayList() else null
                one(tap, c, fails, lines, frames) { cP, p, _ ->
                    for (arm in Arm.entries) {
                        val left = arm == Arm.LEFT
                        val areas = ms.press.areas(left)
                        differ(lens.panel(arm), m.truth(cP, left, band = ms.press.pressD), areas).let { if (it > 0) fails += "tap at $c $arm: $it px at HOLD where the press is not the band row at the forward depth" }
                        val end = m.truth(cP, left).copy().also { ms.paintEndOf(ms.press.prog4, p, left, it) }
                        differ(lens.panel(arm), end, areas).let { if (it > 0) fails += "tap at $c $arm: $it px at HOLD where the lens is not the press's END" }
                    }
                    // the act's first frame, then the slot's DISCARD: the press goes, the lens shows the shadow
                    lens.send("DISCARD after the act", CfwModes.motionDiscard(MainStaging.SLOT, s, MainStaging.LIST, cP), fails)
                    for (arm in Arm.entries) if (sim.panelCrc32(arm) != sim.shadowCrc32(arm)) fails += "tap at $c $arm: after the DISCARD the lens does not show the shadow"
                    lens.notifies()
                }
                if (frames != null) kept = frames
            }
            cases += Case("$tag-press", "the press at every cursor (Taps · glasses): the band row one notch forward (${ms.bandD}→${ms.press.pressD}), held, then the DISCARD",
                union(ms.press.areas(true) + ms.press.areas(false)), kept, lines, fails)
        }

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

        // ---- a re-staged row: Music's summary changes, written in place, the mark kept, a notch lands on the new paint
        run {
            val fails = ArrayList<String>(); val lines = ArrayList<String>()
            val row = rows.indexOfFirst { it.id == "music" }
            (rows[row] as SampleRow).s = DamageWindow.Summary("Night Drive - Low Tide", detail = "2:32 / 4:05", more = true, progress = 0.63)
            m.repainted()
            val r = ms.restage(row, m.view, m.kit, { _, b -> b() })
            var kept: List<Frame> = emptyList()
            if (r !is MainStaging.Restage.Changed) fails += "Music's row after its summary changed: $r, not a change in place"
            else {
                for (w in r.writes) {
                    val slots = ms.strip.segOff!!.indices.map { ms.strip.off + ms.strip.segOff[it] until ms.strip.off + ms.strip.segOff[it] + ms.strip.segLen!![it] } +
                        ms.bands.map { it.off until it.off + it.slot }
                    if (slots.none { w.off >= it.first && w.off + w.bytes.size <= it.last + 1 }) fails += "${w.what}'s write ${w.off}+${w.bytes.size} is not inside one slot"
                    lens.write(w.off, w.bytes, w.what, fails)
                }
                ms.apply(r)
                lines += "Music's row re-staged in place: ${r.bytes} B in ${r.writes.size} write(s) (${r.writes.joinToString { "${it.what} ${it.bytes.size} B" }}), mark ${list.mark} kept"
                val frames: MutableList<Frame>? = if (keepFrames) ArrayList() else null
                one(down, row - 1, fails, lines, frames) { cP, p, prog4 ->
                    for (arm in Arm.entries) {
                        val left = arm == Arm.LEFT
                        differ(lens.panel(arm), m.truth(cP, left), ms.pan(MainStaging.WHOLE, 1).areas(left)).let { if (it > 0) fails += "the notch onto the re-staged row $arm: $it px not its new paint" }
                    }
                    lens.send("COMMIT", Motion.commit(ms.programAt(prog4), MainStaging.SLOT, s, MainStaging.LIST, cP, p), fails)
                    // belief after the COMMIT is painted from the pictures as re-staged (`MainStaging.apply`)
                    for (arm in Arm.entries) {
                        val left = arm == Arm.LEFT
                        val belief = m.truth(cP - 1, left).copy().also { ms.paintEndOf(prog4, p, left, it) }
                        differ(lens.shadow(arm), belief, null).let { if (it > 0) fails += "the COMMIT onto the re-staged row $arm: $it px where END in the shadow is not what belief takes" }
                    }
                    lens.notifies()
                }
                kept = frames ?: emptyList()
            }
            val pan = ms.pan(MainStaging.WHOLE, 1)
            cases += Case("$tag-restaged", "Music's summary changed: its row re-staged in place, the mark kept, a notch onto it", union(pan.areasL + pan.areasR), kept, lines, fails)
        }
        return cases to tableLine
    }

    private fun union(rs: List<Rect>): Rect = rs.reduce { a, b -> a.union(b) }
}
