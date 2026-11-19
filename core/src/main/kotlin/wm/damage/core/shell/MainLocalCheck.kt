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

        /** The staging as the shell builds it for the bound list: the whole list's pans only (AF.10), and a second strip in
         *  Main's own region at 256 KiB (AD.2). */
        fun staging(cacheSize: Int, region: IntRange, motion: MainStaging.ScrollMotion? = MainStaging.ScrollMotion.DEFAULT): MainStaging =
            MainStaging.build(layout, view, kit, listD, bandD, cacheSize, region.first, region.last + 1,
                widths = listOf(MainStaging.WHOLE), twoStrips = region.last + 1 < cacheSize, motion = motion)
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
        for (h in heights) table += worstCase(text, h, depth)
        return Result(cases, table)
    }

    /** Decisions AU.1: the fullest his eight rows can draw — every line and detail past the row's width (the ▸ mark), a
     *  bar on each, dense ink — staged at 256 KiB with two strips and with one, against Main's own region. */
    fun worstCase(text: TextRasterizer, height: Int, depth: Int = 8): String {
        val ink = "M@W#".repeat(24)
        val rows = sampleRows().onEach { it.s = DamageWindow.Summary(ink, detail = ink, more = true, progress = 0.5) }
        val m = MainRig(text, height, rows, depth)
        val cache = CfwModes.CACHE2_MAX_RIDERS
        val region = CfwModes.mainRegion(cache, bind = true)
        fun bytes(two: Boolean): Int = MainStaging.build(m.layout, m.view, m.kit, m.listD, m.bandD, cache, 0, cache,
            widths = listOf(MainStaging.WHOLE), twoStrips = two).let { it.end - it.start }
        val size = region.last + 1 - region.first
        val two = bytes(true); val one = bytes(false)
        return "worst case h$height at 256 KiB (Main's region $size B): two strips $two B (${if (two <= size) "fit" else "do not fit"}), " +
            "one strip $one B (${if (one <= size) "fits" else "DOES NOT FIT"})"
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
            "strip ${ms.blobs[0].bytes.size} B in a ${ms.strip.slot} B slot${if (ms.stripB != null) " (and its second slot)" else ""}, band forms ${ms.blobs.drop(if (ms.stripB != null) 2 else 1).take(ms.rows).sumOf { it.bytes.size }} B in ${ms.bands.sumOf { it.slot }} B of slots, " +
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
            lens.send("DISCARD to cursor $c", CfwModes.motionDiscard(MainStaging.SLOT, 1, MainStaging.LIST, c), fails)
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
            lens.send("$what: COMMIT S ${a.s}", Motion.commit(ms.programAt(prog), MainStaging.SLOT, a.s!!, MainStaging.LIST, cP, p), fails)
            for (arm in Arm.entries) {
                val left = arm == Arm.LEFT
                val belief = m.truth(cBefore, left).copy().also { ms.paintEndOf(prog, p, left, it) }
                differ(lens.shadow(arm), belief, null).let { if (it > 0) fails += "$what $arm: $it px where the shadow after the COMMIT is not belief" }
                if (sim.panelCrc32(arm) != sim.shadowCrc32(arm)) fails += "$what $arm: after the COMMIT the lens does not show the shadow"
            }
            val bodies = lens.notifies()
            if (bodies.none { it.outcome == DamageMsg.OUTCOME_COMMITTED } || bodies.any { it.outcome == 2 }) fails += "$what: RIGHT's notifies after the COMMIT ${bodies.map { it.describe() }}"
        }
        fun named(l: BoundList, a: DamageMsg.Applied?, e: BoundList.Entry) = a != null && l.names(a.cursor ?: -1, e, a.prog, a.params)

        // (a) a band form re-staged into a free slot: a notch before the switch draws the old slot and is named and
        //     committed after the switch has landed; a notch after it draws the new slot
        run {
            val fails = ArrayList<String>(); val lines = ArrayList<String>()
            (rows[music] as SampleRow).s = rows[music].summary().copy(detail = "Low Tide · q 6/12 · shuffle")     // the band form shows the detail; the row form does not
            m.repainted()
            val r = ms.restage(music, m.view, m.kit, relay0)
            val mv = (r as? MainStaging.Restage.Changed)?.band
            if (r !is MainStaging.Restage.Changed || r.strip.isNotEmpty() || mv == null) fails += "a change of Music's detail: ${(r as? MainStaging.Restage.Outgrown)?.why ?: r}, not its band form alone"
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
                    for (arm in Arm.entries) differ(lens.shadow(arm), m.truth(music, arm == Arm.LEFT), ms.pan(MainStaging.WHOLE, 1).areas(arm == Arm.LEFT))
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
            if (r !is MainStaging.Restage.Changed || r.strip.isEmpty()) fails += "a change of Music's line: ${(r as? MainStaging.Restage.Outgrown)?.why ?: r}, not its strip segment"
            else if (ms.stripB != null) {
                // AD.2: Main's own region at 256 KiB holds a second strip — the new strip is written there and the entries are
                // SET to its pans, with no withdrawal: notches before, during (written, not yet SET) and after the switch are
                // each named by their report and committed
                if (r.toStrip == null) fails += "a row-form change at 256 KiB is not switched to the other strip"
                val before = fireHeld(down, c0, fails)
                if (!named(cur, before, down)) fails += "the notch before the switch is not named: ${before?.describe()}"
                before?.let { commitReport(it, c0, fails, "the notch before the switch") }
                for (w in r.writes) lens.write(w.off, w.bytes, w.what, fails)
                ms.apply(r)
                val nl = ms.boundList(cur.mark, previous = cur)
                if (nl.entries.filter { !it.isTap }.any { e -> cur.entryFor(e.eventId)?.prog4 == e.prog4 }) fails += "the switched list's pans are the old ones"
                val during = fireHeld(down, c0, fails)
                if (!named(nl, during, down)) fails += "the notch after the write, before the SETs, is not named: ${during?.describe()}"
                during?.let { commitReport(it, c0, fails, "the notch during the switch") }
                for (b in nl.sets()) lens.send("a SET of the other strip's pans", b, fails)
                val after = fireHeld(down, c0, fails)
                if (!named(nl, after, down)) fails += "the notch after the switch is not named: ${after?.describe()}"
                if (after != null) {
                    if (after.prog == before?.prog) fails += "the notch after the switch drew the old strip's pan"
                    commitReport(after, c0, fails, "the notch after the switch")
                    for (arm in Arm.entries) differ(lens.shadow(arm), m.truth(music, arm == Arm.LEFT), ms.pan(MainStaging.WHOLE, 1).areas(arm == Arm.LEFT))
                        .let { if (it > 0) fails += "after the switch $arm: $it px of END not Main's paint" }
                }
                r.band?.let { ms.release(it.oldOff4) }; nl.previous = null
                cur = nl
                lines += "Music's row form switched to the other strip: ${r.bytes} B in one flush (${r.writes.joinToString { "${it.what} ${it.bytes.size} B" }}), then ${nl.entries.size} SETs; no withdrawal"
            }
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
                    for (arm in Arm.entries) differ(lens.shadow(arm), m.truth(music, arm == Arm.LEFT), ms.pan(MainStaging.WHOLE, 1).areas(arm == Arm.LEFT))
                        .let { if (it > 0) fails += "after the strip's write $arm: $it px of END not Main's paint" }
                }
                r.band?.let { ms.release(it.oldOff4) }; nl.previous = null
                cur = nl
                lines += "Music's row form re-staged with the entries cleared: ${r.bytes} B in one flush (${r.writes.joinToString { "${it.what} ${it.bytes.size} B" }}), then ${nl.entries.size} SETs"
            }
            cases += Case("$tag-strip", if (ms.stripB != null) "a row form switched to the other strip (AD.2): notches before, during and after the switch, each named and committed; no withdrawal"
                else "a row form re-staged with the list withdrawn: no notch matches meanwhile; after the SETs a notch is named and committed", Rect(0, 0, 1, 1), emptyList(), lines, fails)
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
                val areas = { left: Boolean -> ms.pan(MainStaging.WHOLE, 1).areas(left) }
                if (Arm.entries.none { arm -> differ(lens.shadow(arm), m.truth(music, arm == Arm.LEFT), areas(arm == Arm.LEFT)) > 0 })
                    fails += "the shape guard: the stale row's staged pictures equal Main's truth"
                // the ordinary repaint (the phone's frame of what differs from belief)
                lens.keyframe(m.truth(music, true), m.truth(music, false))
                frames?.add(Frame("repainted", lens.panel(Arm.LEFT), lens.panel(Arm.RIGHT)))
                for (arm in Arm.entries) differ(lens.panel(arm), m.truth(music, arm == Arm.LEFT), null).let { if (it > 0) fails += "after the repaint $arm: $it px not Main's paint" }
                lines += "Music's row three changes behind its staging: the notch named and committed from the staged copies, then the repaint"
            }
            val pan = ms.pan(MainStaging.WHOLE, 1)
            cases += Case("$tag-stale", "a row stale by several changes: a COMMIT from the staged copies, belief equal to both lenses, then the repaint",
                union(pan.areasL + pan.areasR), frames ?: emptyList(), lines, fails)
        }
        return cases to tableLine
    }

    private fun union(rs: List<Rect>): Rect = rs.reduce { a, b -> a.union(b) }
}
