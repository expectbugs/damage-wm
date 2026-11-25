package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.comp.Compositor
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.shell.KeyboardSurface
import wm.damage.core.shell.MenuSurface
import wm.damage.core.shell.Notifications
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellServices
import wm.damage.core.shell.ShellSettings
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.transport.bind
import wm.damage.core.transport.effects
import wm.damage.core.wire.EvenHubMsg
import wm.damage.core.windows.games.GamesWindow

/**
 * The RANDOM-GESTURE ORACLE WALK (review 2026-09-05).
 *
 * `LensOracleTest` proves the compositor against hand-built plane maps;
 * `DivergenceTest` proves one forced disagreement. Neither drives the SHELL:
 * every plane map the glasses actually see is produced by real surfaces — the
 * wheel, a context menu, the keyboard, exclusive mode, a notification stepping
 * forward, a window's own `contentPlanes` — in an order no scripted walk
 * enumerates. The 2026-09-03 review found its worst finding exactly this way
 * and the walk was never committed; this is it.
 *
 * A seeded random walk of the §1 grammar over a real shell, at every one of
 * the four heights, asserting after each settle that
 *
 *  1. the compositor's BELIEF equals the firmware model's panel (what the
 *     shell's own mirror check compares), and
 *  2. the belief equals the INDEPENDENT per-lens truth of `comp.composed`
 *     under `comp.planes` — recomputed here without reference to the
 *     compositor's own code, splitting the panel by plane PIECES (the §25
 *     note: raw plane rects report false pixels at the lens edges).
 *
 * (2) is what catches a defect that writes wrong pixels into the shadow AND
 * sends them: belief and glass then agree, and only truth disagrees.
 */
class OracleWalkTest {

    /** [contract] 2 with [cached] on drives the contract-2 drawing path through the same walk:
     *  per-lens draws, fills, the reseed and a mode-24 hint on every flush whose rows fit, with the
     *  model keeping only the hinted rows on its panel — so a hint that misses a changed row fails
     *  the oracle here (2026-09-15, the second review: the standing gates had never run contract 2). */
    private class Rig(height: Int, seed: Long, contract: Int = 1, cached: Boolean = false, val stage4: Boolean = false,
                      taps: wm.damage.core.local.Taps = wm.damage.core.local.Taps.REPORT,
                      /** Build C3 (DP2): a bit-11 build (0xdff) at the ladder's stage 5 — EFFECTS armed, transitions played. */
                      val stage5: Boolean = false,
                      /** C3 DM-1: Main's `Scroll effect` (the walk's local events then fire it on the glasses). */
                      scrollEffect: String = ShellSettings().scrollEffect,
                      /** K1-2 stage 4c: Files registered over a disposable world in [tmp] ([walkFiles]), its provider's calls
                       *  counted in [filesIo]. */
                      files: Boolean = false) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp: java.nio.file.Path = Files.createTempDirectory("damage-walk")
        val store = Persistence(tmp.resolve("state.json"))
        // build C2, stage DM2: a bit-10 build (DamageCaps 0x5ff) at the ladder's stage 4, so BIND is in force and the
        // shell stages Main's list
        val sim = GlassFirmwareSim().also { if (contract > 1) it.damageContract = contract; if (stage4) it.damageFeatures = 0x5ff; if (stage5) it.damageFeatures = 0xdff }
        // C3 DM-T: the stage-5 walk on a 256 KiB session (a stored ladder record's last size, `MainLocalShellTest`'s form),
        // as stage 5 runs on his glasses — Encode plays only there
        val transport: wm.damage.core.transport.CfwTransportBase = if (stage5) StartRig(sim, scope, c1StageFile = java.nio.file.Files.createTempFile("damage-walk", ".c1").also { f ->
                java.nio.file.Files.writeString(f, wm.damage.core.transport.LadderRecord(stage = 5, sessionKib = 256).encode()); f.toFile().deleteOnExit() })
            else SimTransport(sim, scope, SimTransport.Timing(instant = true)).also { if (stage4) it.devProbe("c1", "4") }
        val clock = Shell.LocalClock(12, 0, "12:00", "PM")
        val text = if (cached) wm.damage.core.comp.CachedText(FakeText()) else FakeText()
        /** C3 X5T (the 44 of 45): the stage-5 walk keeps the shell's journal, read by [notWholeReport] only. */
        val journalFile: java.nio.file.Path? = if (stage5) tmp.resolve("journal.jsonl") else null
        val shell = Shell(text, transport, store, journalFile, scope) { clock }
        val rnd = java.util.Random(seed)
        // C3 X2T item 6 (decisions AM (2)): the shell's effect pick seeded from the scene's seed, so the stage-5 walk's
        // picks are the same at every run (a scripted scene pins its seed)
        init { if (stage5) shell.transitionRandom = java.util.Random(seed xor 0x7E57_5EEDL) }
        val flushFails = ArrayList<String>()
        val faults = ArrayList<String>()
        /** C3 X5T: RIGHT's 114 bodies as the transport handed them (stage 5 only), when, and the wake loop's gaps past
         *  [WAKE_GAP_MS] — what [notWholeReport] sets beside an act that did not run whole. */
        val bodies = ArrayList<Pair<Long, TransportEvent.LocalBody>>()
        val wakeGaps = ArrayList<Pair<Long, Long>>()
        private val marks = ArrayList<Pair<Long, String>>()
        /** The walk's own input, stamped (stage 5): which step an act came from, and whether input arrived inside it. */
        fun mark(what: String) { if (stage5) synchronized(marks) { marks += System.currentTimeMillis() to what } }
        /** K1-2 stage 4c: Files' provider calls under way (0 with no Files registered). */
        val filesIo = java.util.concurrent.atomic.AtomicInteger()
        /** K1-2 stage 4c: Files' "Open on PC" taken (counted, never run), and the provider calls the rig refused ([walkFiles]). */
        val filesOpened = java.util.concurrent.atomic.AtomicInteger()
        val filesRefused = java.util.concurrent.CopyOnWriteArrayList<String>()

        init {
            // DP2: every tier's every effect checked, at the shortest length, so the walk meets them all quickly
            store.put("shell.settings", ShellSettings(heightMode = height, cachedText = if (cached) "on" else "off", taps = taps, scrollEffect = scrollEffect).let { s ->
                if (!stage5) s else wm.damage.core.motion.Transitions.Tier.entries.fold(s) { a, t ->
                    a.withTier(t, length = 225, frames = 6, effects = wm.damage.core.motion.Transitions.effectsOf(t).map { it.id }) } }.toJson())
            // Games is the one real window core can build with no host at all
            shell.register(GamesWindow(FakeText(), scope) { 1_757_000_000_000L })
            shell.register(SurfaceWindow())
            shell.register(WalkDocWindow())
            if (files) shell.register(wm.damage.core.windows.files.FilesWindow(FakeText(), walkFiles(tmp, filesIo, filesOpened, filesRefused), scope))
            // the glasses' engine thread: a step due is drawn (the model draws only when woken)
            if (stage4 || stage5) scope.launch {
                var last = 0L
                while (true) {
                    val now = System.currentTimeMillis()
                    if (stage5 && last != 0L && now - last > WAKE_GAP_MS) synchronized(wakeGaps) { wakeGaps += now to (now - last) }
                    last = now
                    for (a in Arm.entries) sim.conformanceWake(a, now); delay(4)
                }
            }
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                transport.events.collect { e ->
                    when (e) {
                        is TransportEvent.FlushDone ->
                            if (!e.ok) synchronized(flushFails) { flushFails.add("${e.id}: ${e.error}") }
                        is TransportEvent.Fault ->
                            if (e.what != "warmup") synchronized(faults) { faults.add("${e.what}: ${e.detail}") }
                        is TransportEvent.LocalBody -> if (stage5) synchronized(bodies) { bodies += System.currentTimeMillis() to e }
                        else -> {}
                    }
                }
            }
        }

        /** Generous on purpose: a scroll while the Hold'em bots are acting sets
         *  §10.1's SKIP, and the table then plays a whole hand at
         *  [wm.damage.core.windows.games.holdem.Equity.LIVE_ROLLOUTS] with no
         *  pacing — real work that keeps a message on the loop the whole time.
         *  It is progress, not a hang; the bound is only here so a genuine
         *  hang fails loudly instead of parking the suite. */
        /** One evaluation decides (`HANDOFF.md` §27.6): the loop used to exit on a quiescent shell and
         *  then ask AGAIN in the assert, so work that arrived in that gap — since 2026-09-15 an atlas
         *  check's answer — failed a settle that had succeeded. */
        suspend fun settle(what: String) {
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 120_000) {
                if (shell.isQuiescent()) return
                delay(5)
            }
            throw AssertionError("$what: the shell did not settle — " +
                "${shell.quiescenceReport()}\n${busyThreads()}")
        }

        /** K1-2 stage 4c: the window-list walk's decision point — settled, the lanes that write into the cache done (the
         *  notice's, the window list's, no cache work in flight, no transition), Files' provider idle; three readings in a
         *  row, 10 ms apart (a provider call that returned posts its answer to the loop just after it). A shell at rest for
         *  10 s with a lane still not done is a STALL: said in [stalls] with [laneReport], and the walk decides there (more
         *  than ten in one height fail it). */
        suspend fun ready(what: String) {
            val t0 = System.currentTimeMillis()
            var calm = 0
            while (true) {
                val waited = System.currentTimeMillis() - t0
                val quiet = filesIo.get() == 0 && shell.isQuiescent()
                val ok = quiet && shell.sampleIdle {
                    shell.noticeLaneDoneForTest() && shell.windowListLaneDoneForTest() && !shell.cacheWorkInFlight && shell.transitionPhaseForTest() == null
                } == true
                if (!ok) calm = 0 else if (++calm >= 3) return
                if (quiet && waited > 10_000) {
                    stalls += "$what, at rest after $waited ms: ${laneReport()}"
                    if (stalls.size > 10) throw AssertionError("$what: more than ten stalls in one height —\n${stalls.joinToString("\n")}")
                    return
                }
                if (waited > 60_000) throw AssertionError("$what: the shell never came to rest — files io ${filesIo.get()}; ${laneReport()}; ${shell.quiescenceReport()}\n${busyThreads()}")
                delay(10)
            }
        }

        /** K1-2 stage 4c: the decision points the walk took with a lane not done ([ready]). */
        val stalls = ArrayList<String>()

        /** K1-2 stage 4c: each lane condition [ready] reads, the window list's binding, the session, and the journal's last
         *  `liststage` notes — one evaluation on the loop. Diagnostic only. */
        fun laneReport(): String {
            val st = transport.state.value
            val r = shell.onLoop {
                val b = shell.windowListBinding
                "notice lane done ${shell.noticeLaneDoneForTest()}, window list lane done ${shell.windowListLaneDoneForTest()}, " +
                    "wheel lane done ${shell.wheelLaneDoneForTest()}, cache work in flight ${shell.cacheWorkInFlight}, transition ${shell.transitionPhaseForTest()}; " +
                    "slot 0 ${shell.slot0ForTest()}, on screen ${surface()}, its row ${shell.focusedRowForTest()}; the window list: staging " +
                    "${b.staging?.let { "${it.rows} rows at ${it.start}..${it.end}" }}, counted ${b.counted}, list ${b.list?.let { "mark ${it.mark} count ${it.count}" }}, " +
                    "records ${b.records.map { it.state }}, stagings ${b.stagings}, refusals ${shell.windowListRefusalsForTest()}; " +
                    "the notice lane: ${shell.noticeLaneReportForTest()}"
            }
            fun notes(kind: String, n: Int) = journalFile?.takeIf { Files.exists(it) }?.let { f ->
                Files.readAllLines(f).filter { "\"kind\":\"$kind\"" in it }.takeLast(n).map { DETAIL_FIELD.find(it)?.groupValues?.get(1) ?: it }
            }
            return "$r; session effects ${st.effects} bind ${st.bind} cache ${st.cacheSize}; liststage notes ${notes("liststage", 6)}; notice notes ${notes("notice", 4)}"
        }

        /** K1-2 stage 4c: who holds slot 0 ("main", "window" or null), the list it offers when that list's two notch
         *  entries are held on RIGHT (null otherwise), and its row — one evaluation on the loop, the entries read under the
         *  model's lock. A holder whose list is not held yet is asked again for up to 5 s (Main's lane has no door that says
         *  it is done). */
        suspend fun pick(what: String): Pick {
            val t0 = System.currentTimeMillis()
            while (true) {
                val p = shell.sampleIdle {
                    val holder = shell.slot0ForTest()
                    val (list, row) = when (holder) { "main" -> shell.mainBoundForTest(); "window" -> shell.windowBoundForTest(); else -> null to -1 }
                    val held = list != null && synchronized(sim) { sim.bindForTest(Arm.RIGHT).entries.filterNotNull().count { it.list == list.list && !it.tap } == 2 }
                    Pick(surface(), holder, if (held) list else null, row)
                }
                if (p != null && (p.holder == null || p.list != null || System.currentTimeMillis() - t0 > 5_000)) return p
                delay(10)
                ready(what)
            }
        }

        /** WHERE it is stuck, not just THAT it is: a bound that fires with no
         *  stack behind it tells the next reader nothing, and the one time this
         *  fired for a real reason it took a separate investigation to find the
         *  wheel that never stopped spinning (review §30). */
        private fun busyThreads(): String {
            val all = Thread.getAllStackTraces()
            val ours = all.entries.mapNotNull { (t, st) ->
                val mine = st.filter { it.className.startsWith("wm.damage") }
                if (mine.isEmpty()) null
                else "  ${t.name} [${t.state}]\n" + mine.take(12).joinToString("\n") { "    at $it" }
            }
            if (ours.isNotEmpty()) return ours.joinToString("\n")
            // 🔴 Nothing is RUNNING our code, so the loop is PARKED, not busy —
            // and then the question is what it is parked on. Dump every worker
            // (review §31): the filtered view said "no thread was inside
            // wm.damage code" and stopped the investigation there.
            return "  (no thread inside wm.damage — every worker's top frames follow)\n" +
                all.entries
                    .filter { (t, _) -> t.name.startsWith("DefaultDispatcher") || t.name.startsWith("kotlinx") ||
                        t.name.startsWith("Test worker") || t.name.startsWith("pool-") }
                    .joinToString("\n") { (t, st) ->
                        "  ${t.name} [${t.state}]\n" + st.take(8).joinToString("\n") { "    at $it" }
                    }
        }

        fun lens(left: Boolean): Gray8 {
            val ctx = if (left) sim.left else sim.right
            val g = Gray8(Geometry.PANEL_W, Geometry.PANEL_H)
            for (y in 0 until Geometry.PANEL_H) for (x in 0 until Geometry.PANEL_W) {
                val b = ctx.panel[y * ctx.stride + (x shr 1)].toInt() and 0xFF
                g[x, y] = (if (x and 1 == 0) b shr 4 else b and 0x0F) * 17
            }
            return g
        }

        /** One settled reading of everything the assertions compare. */
        class Snap(val believedL: Gray8, val believedR: Gray8, val truthL: Gray8, val truthR: Gray8,
            val glassL: Gray8, val glassR: Gray8, val planes: String,
            /** APK 0.83 (stage SV, item W): the areas the notice's timer is drawing in on each lens, left out of that lens's
             *  comparison ([noticeTimerAreas]). */
            val skipL: List<Rect> = emptyList(), val skipR: List<Rect> = emptyList())

        /**
         * APK 0.83 (stage SV, item W; `FR2-083-read.md` F1, decisions BX): the areas on [arm]'s lens of the notice's timer
         * while it draws on the glasses' engine — its own tags in slot 2 (`Shell.NOTICE_TAG`), not any slot-2 instance. There
         * the lens shows the timer's step and belief its END (`FIRMWARE.md` §5), as the shell's own mirror scan leaves an open
         * instance's areas out (`unknownAreas`); the areas are the model's (`motionWindows`). Read under the model's lock.
         */
        private fun noticeTimerAreas(arm: Arm): List<Rect> {
            val o = sim.motionForTest(arm).slots[wm.damage.core.motion.PopupPrograms.SLOT] ?: return emptyList()
            if (!o.drawing || (o.tag and 0xF000) != Shell.NOTICE_TAG) return emptyList()
            return sim.motionWindows(arm).firstOrNull { it.slot == wm.damage.core.motion.PopupPrograms.SLOT }?.areas ?: emptyList()
        }

        /**
         * Both assertions, both lenses, plus the loud-failure surfaces.
         *
         * 🔴 The whole reading is taken ON the shell loop with nothing else
         * pending (`Shell.sampleIdle`, review §30): read field by field from
         * the test thread, belief, composed, the plane map and the two panels
         * span a window the shell can repaint inside, and the comparison then
         * holds halves of two different frames. The same torn read failed the
         * `--selfcheck` oracle about one run in ten.
         */
        suspend fun assertOracle(what: String) {
            synchronized(flushFails) { assertTrue(flushFails.isEmpty(), "$what: failed flushes $flushFails") }
            synchronized(faults) { assertTrue(faults.isEmpty(), "$what: transport faults $faults") }
            var snap: Snap? = null
            val deadline = System.currentTimeMillis() + 30_000
            while (snap == null && System.currentTimeMillis() < deadline) {
                if (!shell.isQuiescent()) { delay(5); continue }
                // 0.83, decisions CC: the shell learns a transition half's end from RIGHT's body alone, so it can be quiescent
                // while LEFT's engine still has a step of that half to draw (`TransitionShellTest.Rig.assertOracle`: LEFT 30 ms
                // behind on the model, read mid-way in 2 of 10 runs, right by itself 0.1–0.25 s later). A lens still drawing a
                // half (the halves' own tags) is not at rest: asked in the evaluation that reads the panels, under the lock.
                val read: Any? = shell.sampleIdle<Any> {
                    if (synchronized(sim) { Arm.entries.any { a -> sim.motionForTest(a).slots.any { o ->
                            o != null && o.drawing && (o.tag and 0xE000) == wm.damage.core.shell.TransitionRun.TAG_OUT } } })
                        return@sampleIdle Unit
                    // APK 0.83 (stage SV, item W; decisions BX): while the notice's timer draws on the glasses' engine, its areas
                    // are left out of each lens's comparison and the rest is compared now — the walk steps on, so its next
                    // gesture lands inside the box's grace, as on his glasses (the wait the integration put here outlasted the
                    // 2.0 s grace: every box had taken focus before the next gesture, `FR2-083-read.md` F1). Once the timer
                    // has ended the box is compared whole. One evaluation on the loop, the model read under its lock.
                    val skip = synchronized(sim) { Arm.entries.associateWith { noticeTimerAreas(it) } }
                    Snap(quantised(shell.comp.expectedLens(true)), quantised(shell.comp.expectedLens(false)),
                        quantised(truthOf(shell.comp.composed, shell.comp.planes, true)),
                        quantised(truthOf(shell.comp.composed, shell.comp.planes, false)),
                        lens(true), lens(false),
                        shell.comp.planes.joinToString { p -> "${p.rect}@${p.disparity}" }, skip[Arm.LEFT]!!, skip[Arm.RIGHT]!!)
                }
                if (read is Snap) snap = read else if (read != null) delay(5)
            }
            val s = snap ?: throw AssertionError("$what: the shell never held still long enough to " +
                "sample — ${shell.quiescenceReport()}")
            if (s.skipL.isNotEmpty() || s.skipR.isNotEmpty()) timerReads++
            for (arm in Arm.entries) {
                val left = arm == Arm.LEFT
                val glass = if (left) s.glassL else s.glassR
                val skip = if (left) s.skipL else s.skipR
                val skipped = if (skip.isEmpty()) "" else " (the notice's timer drawing at $skip: left out)"
                firstDiff(if (left) s.believedL else s.believedR, glass, skip)?.let {
                    throw AssertionError("$what: ${arm.name} BELIEF != glass at $it$skipped")
                }
                firstDiff(if (left) s.truthL else s.truthR, glass, skip)?.let {
                    throw AssertionError("$what: ${arm.name} glass != TRUTH at $it$skipped — planes=${s.planes}")
                }
            }
            val flags = sim.flags(Arm.LEFT) + sim.flags(Arm.RIGHT)
            assertTrue(flags.none { it.value }, "$what: sticky diagnostic flags ${flags.filterValues { it }}")
        }

        /** APK 0.83 (stage SV, item W): oracle reads taken while a notice's timer drew on the engine — the stage-5 walk's
         *  proof that its gestures and acts meet a box in its grace again. */
        var timerReads = 0
            private set

        /** Flushes the model transferred through the partial path (mode 24) — only the contract-2
         *  ops produce one, so this is the walk's proof that it exercised them. */
        var hintedTransfers = 0
            private set

        fun notePath() { if (sim.lastPath(Arm.RIGHT) == 1) hintedTransfers++ }

        /** What is on screen right now — the walk's own coverage report. */
        fun surface(): String = buildString {
            append(shell.currentWindowId() ?: "main")
            if (shell.menuIsOpen) append("+menu")
            if (shell.switcherIsOpen) append("+wheel")
            if (shell.keyboardIsOpen) append("+kbd")
            if (shell.exclusiveMode) append("+excl")
            if (shell.notifications.active) append(if (shell.notifications.focused) "+notice!" else "+notice")
        }

        /**
         * C3 X5T (the 44 of 45): for every act of this walk that did not run whole (no "in half's last step drawn"
         * note), what ended it — its `transition` notes, the submits and acks of the flushes they name, RIGHT's 114
         * bodies of its two tags (drawn / folded / outcome), every other note in its window, the walk's input before and
         * inside it, and the engine loop's late wakes. Times are ms from the act's first note. Diagnostic print only.
         */
        fun notWholeReport(tag: String): String = buildString {
            val f = journalFile ?: return "$tag: no journal kept"
            class Line(val t: Long, val raw: String)
            val all = Files.readAllLines(f).mapNotNull { l -> T_FIELD.find(l)?.let { Line(it.groupValues[1].toLong(), l) } }
            fun detail(l: Line) = DETAIL_FIELD.find(l.raw)?.groupValues?.get(1) ?: l.raw
            fun isTransition(l: Line) = "\"ev\":\"note\"" in l.raw && "\"kind\":\"transition\"" in l.raw
            fun terminal(g: List<Line>) = g.any { detail(it).startsWith("owed:") || "is refused offline" in detail(it) }
            // one group per act, in order (`transitionActs` counts from 0 in this shell): an act's notes begin with its
            // pick's "left out", "a new act replaces", "owed:" or "refused offline", and a group closes at its "owed:" or refusal
            val acts = ArrayList<MutableList<Line>>()
            var cur = ArrayList<Line>()
            for (n in all.filter(::isTransition)) {
                val d = detail(n)
                val begins = d.startsWith("left out of this act's pick") || d.startsWith("a new act replaces") ||
                    d.startsWith("owed:") || "is refused offline" in d
                if (begins && terminal(cur)) { acts += cur; cur = ArrayList() }
                cur += n
            }
            if (cur.isNotEmpty()) acts += cur
            val (owed, whole) = shell.transitionCountsForTest()
            appendLine("$tag: the journal holds ${acts.size} act(s) (the shell counted $owed owed, $whole whole)")
            // `DAMAGE_WALK_REPORT_ALL` (set by hand): every act's detail, whole or not — the report's own check;
            // `DAMAGE_WALK_JOURNALS=DIR`: a height with an act not whole keeps its journal there
            val showAll = System.getenv("DAMAGE_WALK_REPORT_ALL") != null
            if (whole < owed) System.getenv("DAMAGE_WALK_JOURNALS")?.let { d ->
                val to = java.nio.file.Path.of(d).resolve("${tag.replace(Regex("[^A-Za-z0-9=-]"), "_")}-${System.currentTimeMillis()}.jsonl")
                Files.createDirectories(to.parent); Files.copy(f, to); appendLine("  its journal kept: $to")
            }
            for ((k, g) in acts.withIndex()) {
                val isWhole = g.any { detail(it).startsWith("in half's last step drawn on RIGHT") }
                if (isWhole && !showAll) continue
                val t0 = g.first().t
                val tEnd = acts.getOrNull(k + 1)?.first()?.t ?: Long.MAX_VALUE
                appendLine("  act $k ${if (isWhole) "whole" else "NOT WHOLE"} (journal t0 $t0):")
                synchronized(marks) {
                    marks.lastOrNull { it.first <= t0 }?.let { appendLine("    the walk's input before it: ${it.second} at ${it.first - t0} ms") }
                    marks.filter { it.first in (t0 + 1) until tEnd }.forEach { appendLine("    the walk's input INSIDE it: ${it.second} at ${it.first - t0} ms") }
                }
                g.forEach { appendLine("    note ${it.t - t0} ms: ${detail(it)}") }
                val ids = g.flatMap { n -> FLUSH_ID.findAll(detail(n)).map { it.groupValues[1] }.toList() }.toSet()
                for (l in all) {
                    val id = ID_FIELD.find(l.raw)?.groupValues?.get(1) ?: continue
                    if (id !in ids) continue
                    if ("\"ev\":\"submit\"" in l.raw) appendLine("    submit ${l.t - t0} ms: flush $id label ${LABEL_FIELD.find(l.raw)?.groupValues?.get(1)} ops " +
                        OP_FIELD.findAll(l.raw).map { it.groupValues[1] }.groupingBy { it }.eachCount())
                    else if ("\"ev\":\"done\"" in l.raw) appendLine("    done ${l.t - t0} ms: ${l.raw.substringAfter("\"id\":").take(160)}")
                }
                all.filter { it.t in t0 until tEnd && "\"ev\":\"note\"" in it.raw && !isTransition(it) }.take(40)
                    .forEach { appendLine("    other note ${it.t - t0} ms: ${KIND_FIELD.find(it.raw)?.groupValues?.get(1)}: ${detail(it).take(200)}") }
                val tags = setOf(wm.damage.core.shell.TransitionRun.TAG_OUT + (k and 0xFFF), wm.damage.core.shell.TransitionRun.TAG_IN + (k and 0xFFF))
                synchronized(bodies) {
                    bodies.filter { it.second.tag in tags }.forEach { (t, b) ->
                        appendLine("    114 ${t - t0} ms: slot ${b.slot} tag 0x%04x outcome ${b.outcome} reason ${b.reason} drawn ${b.drawn} folded ${b.folded} stepUs ${b.stepUs}".format(b.tag))
                    }
                }
                synchronized(wakeGaps) {
                    val late = wakeGaps.filter { it.first in (t0 - 100) until minOf(tEnd, t0 + 5_000) }
                    appendLine("    engine wakes later than $WAKE_GAP_MS ms in its window: " + (if (late.isEmpty()) "none" else late.joinToString { "${it.second} ms at ${it.first - t0}" }))
                }
            }
            synchronized(wakeGaps) { appendLine("  this height's late engine wakes in all: ${wakeGaps.size}, the longest ${wakeGaps.maxOfOrNull { it.second } ?: 0} ms") }
        }

        suspend fun stop() {
            shell.stop()
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    /**
     * A list window whose rows open each SHELL SURFACE in turn — the context
     * menu, the §4.8 keyboard and §4.9 exclusive mode — so their plane maps
     * are reachable from a random walk. Row 0 is harmless (§1.7).
     */
    /** K1-2 stage 4c: [Rig.pick]'s reading — the surface on screen, slot 0's holder, its list when held on RIGHT, its row. */
    private class Pick(val surface: String, val holder: String?, val list: wm.damage.core.local.BoundList?, val row: Int)

    private class SurfaceWindow : wm.damage.core.shell.DamageWindow(
        "surfaces", "Surfaces", wm.damage.core.gfx.IconKind.FILES,
    ) {
        private val model = wm.damage.core.shell.ListModel()
        private var ctx: ShellServices? = null
        private var level = 0
        private val labels = listOf("Nothing", "Menu", "Keyboard", "Exclusive", "Deeper")

        override fun onRegistered(ctx: ShellServices) { this.ctx = ctx }
        override fun onActivate(ctx: ShellServices, from: wm.damage.core.shell.ActivationSource) { this.ctx = ctx }

        /** 🔴 Exclusive mode SWALLOWS every ring input, so a window there only
         *  ever repaints because it asked to (Music does it from its channel).
         *  Without these asks the delta arm is never called at all and the
         *  walk's oracle never sees one — which is how the §31 shift path came
         *  to have no coverage here. Four is bounded, so the shell still
         *  settles. */
        override fun onExclusive(on: Boolean) {
            excl = 0
            if (on) repeat(4) { ctx?.requestRender(this) }
        }

        override fun view() = wm.damage.core.shell.WindowView.ListView(
            model, { labels.size },
            { g, i, r, _ -> g.fillRect(r.x + 8, r.y + 6, 60 + i * 24, 12, ((i % 12) + 2) * 17) },
            { g, r, i -> g.fillRect(r.x + 8, r.y + 8, 120, 16, ((i % 15) + 1) * 17) },
            { i ->
                when (labels[i]) {
                    "Menu" -> ctx?.openMenu(MenuSurface.Spec("walk", listOf(
                        MenuSurface.Item("Close"), MenuSurface.Item("Also close", "detail"),
                        MenuSurface.Item("Third row", "more detail here")), onCommit = { }), owner = this)
                    "Keyboard" -> ctx?.openKeyboard(KeyboardSurface.Spec("walk", "abc", onCommit = { }), owner = this)
                    "Exclusive" -> ctx?.enterExclusive(this)
                    "Deeper" -> level = (level + 1) % 3
                    else -> {}
                }
            },
        )

        /**
         * 🔴 The DELTA arm paints a band that TRANSLATES (review §31).
         *
         * Exclusive mode is the other surface that owns its own damage, and
         * this arm used to return an empty list — so a delta drew nothing and
         * `paintExclusiveDelta` had no oracle behind it at all, shift path
         * included. The band is a strict SUB-rect of `safe` on purpose: the
         * canvas call site always passes the whole content area, so the
         * offset arithmetic for a non-zero snapshot origin is exercised only
         * here. Every 12th delta jumps instead of stepping, so the walk sees
         * the detector DECLINE as well as accept.
         */
        private var excl = 0
        override fun paintExclusive(g: Gray8, safe: Rect, full: Boolean): List<Rect> {
            val band = Rect(safe.x, safe.y + 64, safe.w, (safe.h - 128) and 1.inv())
            if (full) {
                g.fillRect(safe.x + 32, safe.y + 32, safe.w - 64, 40, 9 * 17)
                excl = 0
                if (band.h >= 64) paintBand(g, band)
                return listOf(safe)
            }
            if (band.h < 64) return emptyList()
            excl++
            paintBand(g, band)
            return listOf(band)
        }

        /** Rows whose look depends only on the LINE index, so a step IS a pure
         *  translation. Every mark stays inside [band] — a rect a paint returns
         *  is a promise (§27). */
        private fun paintBand(g: Gray8, band: Rect) {
            g.fillRect(band, 0)
            for (i in 0 until 40) {
                val y = band.y + i * 12 - (excl % 12) * 24
                if (y < band.y || y + 6 > band.bottom) continue
                var x = band.x + 8 + (i * 37) % maxOf(1, band.w / 3)
                var wd = 40 + (i * 13) % 90
                if (x + wd <= band.right) g.fillRect(x, y, wd, 6, ((i % 14) + 1) * 17)
                x = band.x + band.w / 2 + (i * 53) % maxOf(1, band.w / 5)
                wd = 30 + (i * 7) % 60
                if (x + wd <= band.right) g.fillRect(x, y, wd, 6, ((i % 9) + 3) * 17)
            }
        }

        /** §16.1 deep links, so the walk can REACH each surface deliberately
         *  instead of hoping the dice land on the right row. */
        override fun open(target: String): Boolean = when (target) {
            "menu" -> ctx?.openMenu(MenuSurface.Spec("walk", listOf(
                MenuSurface.Item("Close"), MenuSurface.Item("Also close", "detail"),
                MenuSurface.Item("Third row", "more detail here")), onCommit = { }), owner = this) == true
            "kbd" -> ctx?.openKeyboard(KeyboardSurface.Spec("walk", "abc", onCommit = { }), owner = this) == true
            "excl" -> ctx?.enterExclusive(this) == true
            "deep" -> { level = (level + 1) % 3; true }
            else -> false
        }

        override fun summary() = Summary("surfaces")
        override fun levelDepth() = 1 + level
        override fun back(): Boolean = if (level > 0) { level--; true } else false
        override fun saveState() = kotlinx.serialization.json.buildJsonObject { }
        override fun restoreState(state: kotlinx.serialization.json.JsonObject) {}
    }

    /** A document window that also declares its own §3 depth region — the
     *  `contentPlanes` path, which only Games exercised before. */
    private class WalkDocWindow : wm.damage.core.shell.DamageWindow(
        "walkdoc", "WalkDoc", wm.damage.core.gfx.IconKind.READER,
    ) {
        private val doc = wm.damage.core.shell.DocModel()
        override fun view() = wm.damage.core.shell.WindowView.DocView(doc, { 120 }, 24,
            { g, i, r -> g.fillRect(r.x + 4, r.y + 4, 40 + (i * 17) % 300, 14, ((i % 10) + 3) * 17) },
            {}, stepLines = { 3 },
            // §66: one layout for the window's life, so the contract-2 walk stages its strips and
            // notches through them (belief = glass = truth holds through staged draws too)
            contentKey = { this })

        override fun contentPlanes(content: Rect): List<Pair<Rect, Int>> {
            val h = Geometry.snapY(content.h / 3)
            return listOf(Rect(content.x + 16, Geometry.snapY(content.y + content.h / 3), content.w - 32, h) to 0)
        }

        override fun summary() = Summary("walk doc")
        override fun saveState() = kotlinx.serialization.json.buildJsonObject { }
        override fun restoreState(state: kotlinx.serialization.json.JsonObject) {}
    }

    /** A list of three rows whose tap leads to a level that is a list of NO rows (Feed's flagged
     *  level, emptied from inside: the one list in the tree that can reach zero). */
    private class EmptyingWindow : wm.damage.core.shell.DamageWindow(
        "emptying", "Emptying", wm.damage.core.gfx.IconKind.FILES,
    ) {
        private val model = wm.damage.core.shell.ListModel()
        private var emptied = false
        override fun view() = wm.damage.core.shell.WindowView.ListView(
            model, { if (emptied) 0 else 3 },
            { g, i, r, _ -> g.fillRect(r.x + 8, r.y + 6, 200 + i * 40, 12, ((i % 12) + 4) * 17) },
            { g, r, i -> g.fillRect(r.x + 8, r.y + 8, 240, 16, ((i % 12) + 6) * 17) },
            { emptied = true },
        )
        override fun back(): Boolean = if (emptied) { emptied = false; true } else false
        override fun levelDepth() = if (emptied) 2 else 1
        override fun summary() = Summary("three rows, then none")
        override fun saveState() = kotlinx.serialization.json.buildJsonObject { }
        override fun restoreState(state: kotlinx.serialization.json.JsonObject) {}
    }

    /**
     * RB-4 (0.64 R1), one scripted scene in the walk's rig: a list that goes to ZERO rows.
     * `ContentKit.paintList` cleared the content of the composed surface and returned before it
     * declared the damage, so nothing was scanned: the glass kept the three rows while the shell
     * sat on an empty list, and belief agreed with the glass — only the truth comparison can see it.
     * Both contracts, the default depth (the view stays a list, so the plane map does not change
     * and no hint comes from there either). The scene pins its seed; nothing in it is random.
     * THE SHAPE GUARD: the rows are lit on the glass before the tap. Mutation watched: the damage
     * at zero rows removed (`glass != TRUTH` at a row's first lit pixel).
     */
    @Test
    fun aListThatGoesToZeroRowsIsClearedOnTheGlass(): Unit = runBlocking {
        for ((contract, cached) in listOf(1 to false, 2 to true)) {
            val rig = Rig(480, seed = 0x0E_4B_04L, contract = contract, cached = cached)
            val tag = "c$contract"
            try {
                rig.shell.register(EmptyingWindow())
                rig.shell.start()
                rig.settle("$tag boot")
                rig.shell.services.runOnShell { rig.shell.services.openWindow("emptying", null) }
                rig.settle("$tag the list of three rows")
                rig.assertOracle("$tag the list of three rows")
                suspend fun litInTheContent(what: String): Int {
                    repeat(2_000) {
                        rig.settle(what)
                        rig.shell.sampleIdle {
                            val c = rig.shell.layout.content
                            val g = rig.lens(true)
                            var n = 0
                            for (y in c.y until c.bottom) for (x in c.x until c.right) if (g[x, y] != 0) n++
                            n
                        }?.let { return it }
                    }
                    throw AssertionError("$what: the shell never sat idle for a sample — ${rig.shell.quiescenceReport()}")
                }
                assertTrue(litInTheContent("$tag the rows on the glass") > 3 * 12 * 100, "$tag — the shape guard: the three rows are lit on the glass before the tap")
                rig.shell.postGesture(EvenHubMsg.EV_CLICK)
                rig.settle("$tag the list, emptied")
                rig.assertOracle("$tag the list, emptied")
                assertTrue(litInTheContent("$tag the empty list on the glass") == 0, "$tag: nothing of the three rows is left on the glass")
                // ...and back: the rows return
                rig.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
                rig.settle("$tag back to the three rows")
                rig.assertOracle("$tag back to the three rows")
                assertTrue(rig.shell.currentWindowId() == "emptying", "$tag: the double-tap went back one level, not to Main")
            } finally {
                rig.stop()
            }
        }
    }

    @Test
    fun beliefEqualsGlassEqualsTruthAcrossARandomWalkAtEveryHeight(): Unit = runBlocking { walk(1, false) }

    /** The same walk on a contract-2 build with cached text on: fills, per-lens draws, reseeds and a
     *  present hint on nearly every flush. The model transfers only a hint's rows to its panel, so
     *  belief = glass here is the proof that the rows cover every change (`FIRMWARE.md` §4). */
    @Test
    fun beliefEqualsGlassEqualsTruthOnContractTwoAtEveryHeight(): Unit = runBlocking { walk(2, true) }

    private suspend fun walk(contract: Int, cached: Boolean) {
        for (h in ShellSettings.HEIGHTS) {
            val rig = Rig(h, seed = 0xDA_4A_6EL + h + contract, contract = contract, cached = cached)
            val seen = LinkedHashSet<String>()
            val tag = if (contract >= 2) "c2 h=$h" else "h=$h"
            try {
                rig.shell.start()
                rig.settle("$tag boot")
                rig.assertOracle("$tag boot")
                for (step in 1..STEPS) {
                    // every so often, HAND OFF to a named surface: a random
                    // walk of five rows reaches the far corners only by luck,
                    // and a walk that misses exclusive mode proves nothing
                    // about exclusive mode
                    if (step % HANDOFF_EVERY == 0) {
                        val (win, target) = HANDOFFS[(step / HANDOFF_EVERY - 1) % HANDOFFS.size]
                        rig.shell.services.runOnShell { rig.shell.services.openWindow(win, target) }
                        val where = "$tag step $step (open $win${target?.let { ":$it" } ?: ""})"
                        rig.settle(where)
                        rig.assertOracle(where)
                        seen.add(rig.surface())
                        continue
                    }
                    val g = GESTURES[rig.rnd.nextInt(GESTURES.size)]
                    rig.shell.postGesture(g)
                    // a notice every so often: the box arrives at the content
                    // plane and steps FORWARD after its grace — two plane maps
                    // no gesture produces
                    if (rig.rnd.nextInt(19) == 0) {
                        rig.shell.postNotice(Notifications.Notice(
                            "SMS · WALK", "t$step", "step $step of the walk", "12:00",
                            emergency = rig.rnd.nextInt(9) == 0))
                    }
                    val where = "$tag step $step (${EvenHubMsg.eventName(g)})"
                    rig.settle(where)
                    rig.assertOracle(where)
                    rig.notePath()
                    seen.add(rig.surface())
                }
                // …and the one plane map a settle always races past: the box
                // takes FOCUS after its grace and steps forward to plane 0.
                // The grace belongs to WINDOW mode (§4.5 rule 4 — the quiet
                // modes auto-dismiss instead), so land there first, whatever
                // the walk left on screen.
                rig.shell.services.runOnShell { rig.shell.services.openWindow("walkdoc", null) }
                rig.settle("$tag back to a window")
                rig.shell.postNotice(Notifications.Notice(
                    "SMS · WALK", "focus", "the box takes focus and steps forward", "12:00"))
                rig.settle("$tag notice shown")
                waitUntil(8_000, 20, { "$tag: the box never took focus" }) { rig.shell.notifications.focused }
                rig.settle("$tag notice focused")
                rig.assertOracle("$tag notice focused")
                seen.add(rig.surface())
                println("$tag reached ${seen.size} surfaces: ${seen.sorted()}")
                // the walk is only worth its runtime if it actually gets
                // somewhere: every shell surface must have been on screen
                for (must in listOf("+menu", "+wheel", "+kbd", "+excl", "+notice!")) {
                    assertTrue(seen.any { it.contains(must) },
                        "$tag: the walk never reached '$must' — it proves less than it claims (saw $seen)")
                }
                // …and on contract 2 it has to have DRAWN with the v2 ops. A session that loses
                // DRAW2 falls back to v1 shapes SILENTLY (`FIRMWARE.md` §4), so without this the
                // 240 steps could prove nothing about the hint or the per-lens draws and still
                // pass (2026-09-15, the third review). A partial transfer is the observable that
                // only mode 24 produces.
                if (contract >= 2) assertTrue(rig.hintedTransfers > 0,
                    "$tag: no flush took the partial path — the walk never exercised the v2 ops")
            } finally {
                rig.stop()
            }
        }
    }

    /**
     * Build C2 (decisions B.9; stage DI joined the halves): the contract-2 walk on a bit-10 build at stage 4, where the
     * shell stages Main's list, offers it once counted, and its own rules SET the entries and answer every report — with
     * RANDOM LOCAL EVENTS: notches and taps fired through the simulator's input hook on both lenses (`FIRMWARE.md` §6.2)
     * whenever Main is in front with nothing over it and its entries are live on RIGHT, each followed by its stock message
     * through the transport (the echo, after the 115 the hook sent). Nothing stands in for the rules: the shell adopts,
     * COMMITs, DISCARDs, SETTLEs and acts as it would on the glasses. One `Taps` value per height (`report`, `glasses`,
     * `phone`, `report`). The truth oracle holds at every settle, and the walk must have fired local events that drew.
     */
    @Test
    fun localEventsOnMainsBoundListKeepTheOracle_DI(): Unit = runBlocking {
        val tapsByHeight = listOf(wm.damage.core.local.Taps.REPORT, wm.damage.core.local.Taps.GLASSES, wm.damage.core.local.Taps.PHONE, wm.damage.core.local.Taps.REPORT)
        for ((hi, h) in ShellSettings.HEIGHTS.withIndex()) {
            val taps = tapsByHeight[hi]
            val rig = Rig(h, seed = 0xD2_10CAL + h, contract = 2, cached = true, stage4 = true, taps = taps)
            val tag = "c2 stage 4 h=$h taps=${taps.word}"
            try {
                val (local, drew, presses, acted) = localWalk(rig, tag, taps)
                println("$tag: $local local event(s), $drew notch(es) drew, $acted tap(s) fired the entry ($presses press(es)); " +
                    "${rig.shell.divergencesReported} divergence(s) reported")
                assertTrue(drew >= 8, "$tag: the walk fired $local local event(s), $drew notches that drew — it proves less than it claims")
                if (taps == wm.damage.core.local.Taps.GLASSES) assertTrue(presses >= 1, "$tag: no press drew")
            } finally {
                rig.stop()
            }
        }
    }

    /** One local walk of [LOCAL_STEPS] (stage DI's body, shared by the stage-4 and the C3 DM-1 stage-5 walks): (local events,
     *  notches that drew, presses, taps that fired the entry). */
    private suspend fun localWalk(rig: Rig, tag: String, taps: wm.damage.core.local.Taps): List<Int> {
        var local = 0; var drew = 0; var presses = 0; var acted = 0
        rig.shell.start()
        rig.settle("$tag boot")
        rig.assertOracle("$tag boot")
        for (step in 1..LOCAL_STEPS) {
            rig.settle("$tag before step $step")
            val offer = if (rig.surface() == "main") rig.shell.sampleIdle { rig.shell.mainBoundForTest() } else null
            val list = offer?.first
            // the entries live on RIGHT (the rules' SETs landed): ids 4 and 5 at least
            val live = list != null && rig.sim.bindForTest(Arm.RIGHT).field39() and 0x30 == 0x30
            if (live && rig.rnd.nextInt(3) != 0) {
                val e = list!!.entries[rig.rnd.nextInt(list.entries.size)]
                val row = offer.second
                val before = rig.sim.bindForTest(Arm.RIGHT).seq
                val now = System.currentTimeMillis()
                for (arm in Arm.entries) rig.sim.conformanceInput(arm, wm.damage.core.wire.CfwModes.DEV_RING, e.eventId.toLong(), 0, now)
                val fired = rig.sim.bindForTest(Arm.RIGHT).seq != before
                rig.transport.ringStockForTest(wm.damage.core.local.BoundList.stockEvent(e.eventId)!!)
                if (fired && !e.isTap) { val f = list.predict(row, e); if (f is wm.damage.core.local.BoundList.Fire.Drew) drew++ }
                if (fired && e.isTap) { acted++; if (taps == wm.damage.core.local.Taps.GLASSES) presses++ }
                // the lens draws the local picture to its end (a period of steps and one more)
                val prog = e.program
                if (prog != null) delay(((prog.T + 1) * e.period * 1000L / 1024) + 20)
                local++
                val where = "$tag step $step (local ${if (e.isTap) "tap" else if (e.step > 0) "down" else "up"} from row $row)"
                rig.settle(where)
                rig.assertOracle(where)
                continue
            }
            // away from Main, a double tap back one level now and then: the local events are Main's
            val g = if (rig.surface() != "main" && rig.rnd.nextInt(3) == 0) EvenHubMsg.EV_DOUBLE_CLICK else GESTURES[rig.rnd.nextInt(GESTURES.size)]
            rig.shell.postGesture(g)
            val where = "$tag step $step (${EvenHubMsg.eventName(g)})"
            rig.settle(where)
            rig.assertOracle(where)
        }
        return listOf(local, drew, presses, acted)
    }

    /**
     * C3 DM-1 (the brief's item 8): the local walk at stage 5 on a bit-11 build (256 KiB) with Crush and with Flip on Main —
     * version-2 pans fired through the simulator's input hook on both lenses (Crush at pitch +rowH, its source windows; a
     * ring list at 288 and 352, the wrap among the notches), the shell's own rules answering every report — the truth
     * oracle at every settle, and the walk must have fired notches that drew.
     */
    @Test
    fun localEventsWithCrushAndFlipKeepTheOracleAtStage5_DM1(): Unit = runBlocking {
        for ((h, effect) in listOf(288 to "crush", 352 to "crush", 352 to "flip", 416 to "flip", 480 to "crush")) {
            val taps = wm.damage.core.local.Taps.REPORT
            val rig = Rig(h, seed = 0xD3_11CAL + h + effect.length, contract = 2, cached = true, stage5 = true, taps = taps, scrollEffect = effect)
            val tag = "c3 stage 5 h=$h $effect"
            try {
                val (local, drew, _, _) = localWalk(rig, tag, taps)
                val plays = rig.shell.mainBinding.staging?.plays
                println("$tag: $local local event(s), $drew notch(es) drew; plays $plays; ${rig.shell.divergencesReported} divergence(s) reported")
                assertEquals(wm.damage.core.motion.ScrollEffect.byId(effect), plays?.effect, "$tag: Main's staging plays $effect")
                assertTrue(plays!!.v2, "$tag: in version 2")
                assertTrue(drew >= 8, "$tag: the walk fired $local local event(s), $drew notches that drew — it proves less than it claims")
                assertEquals(0, rig.shell.divergencesReported, "$tag: no divergence")
            } finally {
                rig.stop()
            }
        }
    }

    /**
     * Build C3, stage DP2: the contract-2 walk on a bit-11 build at stage 5, every transition tier's every effect checked
     * (225 ms, 6 frames: a random pick at each act) — Selects into windows and levels, Backs, the silent clock both ways,
     * notices arriving, the wheel, menus, a document — with the truth oracle at every settle (a settle waits for a
     * transition's end) at the four heights. The walk must have played transitions that ran whole.
     */
    @Test
    fun transitionsKeepTheOracleAtEveryHeight_DP2(): Unit = runBlocking {
        val byEffect = HashMap<String, Int>()
        var timerReads = 0
        for (h in ShellSettings.HEIGHTS) {
            val rig = Rig(h, seed = 0xD9_7A_25L + h, contract = 2, cached = true, stage5 = true)
            val tag = "c3 stage 5 h=$h"
            try {
                rig.shell.start()
                rig.settle("$tag boot")
                rig.assertOracle("$tag boot")
                for (step in 1..TRANSITION_STEPS) {
                    if (step % HANDOFF_EVERY == 0) {
                        val (win, target) = HANDOFFS[(step / HANDOFF_EVERY - 1) % HANDOFFS.size]
                        rig.mark("step $step (open $win)")
                        rig.shell.services.runOnShell { rig.shell.services.openWindow(win, target) }
                        rig.settle("$tag step $step (open $win)")
                        rig.assertOracle("$tag step $step (open $win)")
                        continue
                    }
                    val g = if (rig.rnd.nextInt(3) == 0) EvenHubMsg.EV_DOUBLE_CLICK else GESTURES[rig.rnd.nextInt(GESTURES.size)]
                    rig.mark("step $step (${EvenHubMsg.eventName(g)})")
                    rig.shell.postGesture(g)
                    if (rig.rnd.nextInt(13) == 0) { rig.mark("step $step notice"); rig.shell.postNotice(Notifications.Notice("SMS · WALK", "t$step", "step $step", "12:00")) }
                    val where = "$tag step $step (${EvenHubMsg.eventName(g)})"
                    rig.settle(where)
                    rig.assertOracle(where)
                }
                val (acts, whole) = rig.shell.transitionCountsForTest()
                println("$tag: $acts transition(s) owed, $whole ran whole (${rig.shell.onLoop { rig.shell.transitionsWholeByEffectForTest() }.toSortedMap()}); ${rig.shell.divergencesReported} divergence(s) reported; " +
                    "${rig.timerReads} oracle read(s) with a notice timer drawing")
                timerReads += rig.timerReads
                // C3 X5T (the 44 of 45): an act that did not run whole is named, with what ended it
                println(rig.notWholeReport(tag))
                rig.shell.onLoop { rig.shell.transitionsWholeByEffectForTest() }.forEach { (k, v) -> byEffect.merge(k, v, Int::plus) }
                assertTrue(whole >= 6, "$tag: $acts transition(s), $whole ran whole — the walk proves less than it claims")
                assertEquals(0, rig.shell.divergencesReported, "$tag: no divergence")
            } finally {
                rig.stop()
            }
        }
        // C3 DM-T: Encode and Matrix are among the walk's picks, and ran whole (over the four heights; the pick seeded, X2T)
        println("c3 stage 5: whole by effect over the four heights ${byEffect.toSortedMap()} (${byEffect.values.sum()} in all)")
        assertTrue((byEffect["encode"] ?: 0) > 0 && (byEffect["matrix"] ?: 0) > 0, "Encode and Matrix in the walk: $byEffect")
        // APK 0.83 (stage SV, item W): the walk reads the oracle while a notice's timer draws on the engine, so its next
        // gesture lands in the box's grace (timer restarts, hand-overs, acts that lift a box whose timer runs)
        assertTrue(timerReads > 0, "the stage-5 walk never read the oracle while a notice's timer drew — it no longer meets a box in its grace")
    }

    /**
     * K1-2 stage 4c (the K1-2 spec §7, stage 4's row: "`OracleWalkTest` (its stage-5 walk then binds window lists)"; §8's
     * gate "OracleWalk at stage 5 twice"; decisions DA): the local walk at stage 5 on a bit-11 build (256 KiB, every
     * transition tier played) entering windows whose own list the shell binds as glasses list 1 — Settings' categories
     * and a category's rows, Files' locations and folders (a disposable world in the rig's directory), the walk's Surfaces
     * — and firing local events through the simulator's input hook on both lenses on whichever list holds slot 0 (list 1,
     * or Main's), each followed by its stock message. The truth oracle judges every settle at the four heights, and a
     * list-1 notch that drew leaves the window's row where the bound list said (its adoption: a snap-back keeps belief,
     * glass and truth equal, so the pixels alone cannot see it). Each step decides at one point ([Rig.ready], [Rig.pick])
     * and takes the same draws whatever it decides, so the pinned seed walks the same steps at every run: each height
     * prints its steps and their digest, which two runs compare. Every height must have drawn list-1 notches, and Settings
     * and Files must both have taken list-1 local events.
     */
    @Test
    fun localEventsOnWindowListsKeepTheOracleAtStage5_K12S4c(): Unit = runBlocking {
        val byWindow = java.util.TreeMap<String, Int>()
        for (h in ShellSettings.HEIGHTS) {
            val rig = Rig(h, seed = 0x4C_12_5A4CL + h, contract = 2, cached = true, stage5 = true, files = true)
            val tag = "c3 stage 5 window lists h=$h"
            try {
                val c = windowListWalk(rig, tag)
                val (owed, whole) = rig.shell.transitionCountsForTest()
                println("$tag: ${c.l1Local} local event(s) on list 1, ${c.l1Drew} notch(es) drew, ${c.l1Taps} tap(s), ${c.l1Edges} edge(s) " +
                    "(by window ${c.l1ByWindow}); ${c.mainLocal} on Main's list, ${c.mainDrew} drew; $owed transition(s) owed, $whole whole; " +
                    "${rig.shell.divergencesReported} divergence(s) reported; steps digest %08x".format(c.trace.toString().hashCode()))
                println("$tag steps:${c.trace}")
                println("$tag: ${rig.stalls.size} stall(s)${rig.stalls.joinToString("") { "\n  $it" }}")
                println("$tag: Files' \"Open on PC\" taken ${rig.filesOpened.get()} time(s), counted and not run; provider calls refused ${rig.filesRefused}")
                // the walk with Files registered starts no process and reads nothing outside its directory ([walkFiles])
                assertTrue(rig.filesRefused.isEmpty(), "$tag: the walk's Files rig was asked for what it does not run: ${rig.filesRefused}")
                c.l1ByWindow.forEach { (k, v) -> byWindow.merge(k, v, Int::plus) }
                assertTrue(c.l1Drew > 0, "$tag: no local notch on list 1 drew (${c.l1Local} fired) — the walk never reached a bound window list")
                assertEquals(0, rig.shell.divergencesReported, "$tag: no divergence")
            } finally {
                rig.stop()
            }
        }
        println("c3 stage 5 window lists: list-1 local events by window over the four heights $byWindow")
        assertTrue((byWindow["settings"] ?: 0) > 0 && (byWindow["files"] ?: 0) > 0, "list-1 local events in Settings and in Files: $byWindow")
    }

    /** What [windowListWalk] counted, and its steps as one line (two runs of a pinned seed compare it). */
    private class WalkCounts {
        var l1Local = 0; var l1Drew = 0; var l1Taps = 0; var l1Edges = 0; var mainLocal = 0; var mainDrew = 0
        val l1ByWindow = java.util.TreeMap<String, Int>()
        val trace = StringBuilder()
    }

    /** K1-2 stage 4c: one walk of [WINDOW_LIST_STEPS] (see [localEventsOnWindowListsKeepTheOracleAtStage5_K12S4c]). */
    private suspend fun windowListWalk(rig: Rig, tag: String): WalkCounts {
        val c = WalkCounts()
        rig.shell.start()
        rig.ready("$tag boot")
        rig.assertOracle("$tag boot")
        for (step in 1..WINDOW_LIST_STEPS) {
            // the same four draws at every step whatever it decides: the seed's stream never forks on a reading
            val dLocal = rig.rnd.nextInt(4); val dEntry = rig.rnd.nextInt(1 shl 16); val dBack = rig.rnd.nextInt(3); val dGesture = rig.rnd.nextInt(GESTURES.size)
            if (step % WINDOW_LIST_HANDOFF_EVERY == 0) {
                val (win, target) = WINDOW_LIST_HANDOFFS[(step / WINDOW_LIST_HANDOFF_EVERY - 1) % WINDOW_LIST_HANDOFFS.size]
                rig.shell.services.runOnShell { rig.shell.services.openWindow(win, target) }
                val where = "$tag step $step (open $win${target?.let { ":$it" } ?: ""})"
                rig.ready(where)
                rig.assertOracle(where)
                c.trace.append(" $step:open:$win")
                continue
            }
            val p = rig.pick("$tag step $step")
            val list = p.list
            if (list != null && dLocal != 0) {
                val e = list.entries[dEntry % list.entries.size]
                val before = rig.sim.bindForTest(Arm.RIGHT).seq
                val now = System.currentTimeMillis()
                for (arm in Arm.entries) rig.sim.conformanceInput(arm, wm.damage.core.wire.CfwModes.DEV_RING, e.eventId.toLong(), 0, now)
                val fired = rig.sim.bindForTest(Arm.RIGHT).seq != before
                rig.transport.ringStockForTest(wm.damage.core.local.BoundList.stockEvent(e.eventId)!!)
                val f = list.predict(p.row, e)
                // the lens draws the local picture to its end (a period of steps and one more), and the rules' burst closes
                val q = rig.shell.onLoop { rig.shell.localQForTest() }
                e.program?.let { prog -> delay(((prog.T + 1) * e.period * 1000L / 1024) + q + 20) }
                val dir = if (e.isTap) "tap" else if (e.step > 0) "down" else "up"
                val where = "$tag step $step (local $dir on list ${list.list} in ${p.surface} from row ${p.row})"
                rig.ready(where)
                rig.assertOracle(where)
                val rowAfter = rig.shell.onLoop { rig.shell.focusedRowForTest() }
                c.trace.append(" $step:L${list.list}:${p.surface}:$dir@${p.row}>$rowAfter${if (fired) "" else "!"}")
                if (list.list == wm.damage.core.shell.WindowListCheck.LIST) {
                    c.l1Local++
                    c.l1ByWindow.merge(p.surface, 1, Int::plus)
                    if (fired && e.isTap) c.l1Taps++
                    if (fired && !e.isTap && f is wm.damage.core.local.BoundList.Fire.Edge) c.l1Edges++
                    if (fired && !e.isTap && f is wm.damage.core.local.BoundList.Fire.Drew) {
                        c.l1Drew++
                        assertEquals(f.cursor, rowAfter, "$where: the window's row after a notch that drew on the lens (the report adopted)")
                    }
                } else {
                    c.mainLocal++
                    if (fired && !e.isTap && f is wm.damage.core.local.BoundList.Fire.Drew) c.mainDrew++
                }
                continue
            }
            // away from Main, a double tap back one level now and then
            val g = if (p.surface != "main" && dBack == 0) EvenHubMsg.EV_DOUBLE_CLICK else GESTURES[dGesture]
            rig.shell.postGesture(g)
            val where = "$tag step $step (${EvenHubMsg.eventName(g)} in ${p.surface})"
            rig.ready(where)
            rig.assertOracle(where)
            c.trace.append(" $step:${p.surface}:${EvenHubMsg.eventName(g)}")
        }
        return c
    }

    internal companion object {
        const val STEPS = 240
        /** DP2: the stage-5 walk's steps per height. */
        const val TRANSITION_STEPS = 90
        /** The local walk's steps per height (each local event waits for its picture to be held). */
        const val LOCAL_STEPS = 120
        const val HANDOFF_EVERY = 17
        /** K1-2 stage 4c: the window-list walk's steps per height, and how often it opens a window whose list binds. */
        const val WINDOW_LIST_STEPS = 120
        const val WINDOW_LIST_HANDOFF_EVERY = 11
        /** K1-2 stage 4c: the windows the window-list walk opens, in rotation (Settings at its categories or a category). */
        val WINDOW_LIST_HANDOFFS: List<Pair<String, String?>> = listOf(
            "settings" to null, "files" to null, "settings" to "cat:Global", "surfaces" to null, "files" to null, "settings" to "cat:Files",
        )

        /** K1-2 stage 4c: the `FilesProvider` calls the walk's Files rig hands to the real provider — each reads or changes
         *  only paths under the rig's directory and starts no process (the K1-2 S4c report lists what each does). */
        val WALK_FILES_FORWARDED = setOf("stateLine", "locations", "list", "cachedLocations", "cachedList", "stat", "du", "readText",
            "readBytes", "trash", "trashList", "restore", "purge", "rename", "mkdir", "copy", "move", "refuseTrashStore")

        /**
         * K1-2 stage 4c: Files' provider over a disposable world in [tmp] — three roots (4, 6 and 8 folders, each with one
         * to three sub folders; nothing else), the rig's own trash and books directories, no mounts read. Nothing outside
         * [tmp] is listed: the locations are the three roots, and Files' Back never climbs above a root. Only the calls in
         * [WALK_FILES_FORWARDED] reach the real provider, each counted in [io] while it runs (the walk decides once a listing
         * has landed). `openOnPc` (the real one starts `xdg-open` on the PC's display) is counted in [opened] and returns;
         * any other call (the PDF calls start `pdfinfo`, `pdftotext`, `pdftoppm`; `thumb` decodes an image; a call added to
         * the interface later) is recorded in [refused] and throws. The real provider's own `openOnPc` is replaced too: a
         * call that reaches it is recorded in [refused] and throws, so even a rig that forwarded it starts nothing — and
         * [refused] fails the walk.
         */
        fun walkFiles(tmp: java.nio.file.Path, io: java.util.concurrent.atomic.AtomicInteger, opened: java.util.concurrent.atomic.AtomicInteger,
                      refused: MutableList<String>): wm.damage.core.windows.files.FilesProvider {
            val roots = listOf("SC", "Docs", "Media").mapIndexed { k, label ->
                val r = tmp.resolve("files").resolve(label.lowercase())
                for (i in 0 until 4 + 2 * k) for (j in 0..(i % 3)) Files.createDirectories(r.resolve("folder-${'a' + i}").resolve("sub-$j"))
                label to r
            }
            val local = wm.damage.core.windows.files.LocalFilesProvider(tmp.resolve("files-books"), tmp.resolve("files-trash"),
                decoder = null, mountsFile = tmp.resolve("files-no-mounts"))
            val base = object : wm.damage.core.windows.files.FilesProvider by local {
                override fun locations() = roots.map { (label, r) -> wm.damage.core.windows.files.FLocation(label, r.toString(), "mount", 1_000_000, 500_000) }
                override fun openOnPc(path: String) {
                    refused += "openOnPc reached the provider ($path)"
                    throw IllegalStateException("the walk's Files rig opens nothing on the PC")
                }
            }
            return java.lang.reflect.Proxy.newProxyInstance(wm.damage.core.windows.files.FilesProvider::class.java.classLoader,
                arrayOf(wm.damage.core.windows.files.FilesProvider::class.java)) { proxy, m, args ->
                when (m.name) {
                    "openOnPc" -> { opened.incrementAndGet(); null }
                    in WALK_FILES_FORWARDED -> {
                        io.incrementAndGet()
                        try { m.invoke(base, *(args ?: emptyArray())) }
                        catch (e: java.lang.reflect.InvocationTargetException) { throw e.targetException }
                        finally { io.decrementAndGet() }
                    }
                    "toString" -> "the walk's Files provider"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    else -> {
                        refused += m.name
                        throw UnsupportedOperationException("the walk's Files rig does not run ${m.name}")
                    }
                }
            } as wm.damage.core.windows.files.FilesProvider
        }

        /** C3 X5T: the engine loop sleeps 4 ms a turn; a turn later than this is printed beside an act not whole. */
        const val WAKE_GAP_MS = 20L
        /** The journal's fields [Rig.notWholeReport] reads (`Journal`'s line shapes). */
        val T_FIELD = Regex(""""t":(\d+)""")
        val ID_FIELD = Regex(""""id":(\d+)""")
        val DETAIL_FIELD = Regex(""""detail":"((?:[^"\\]|\\.)*)"""")
        val KIND_FIELD = Regex(""""kind":"([^"]*)"""")
        val LABEL_FIELD = Regex(""""label":"([^"]*)"""")
        val OP_FIELD = Regex(""""op":"([a-z0-9]+)"""")
        val FLUSH_ID = Regex("""flush (\d+)""")

        /** The surfaces the walk visits deliberately, in rotation. */
        val HANDOFFS: List<Pair<String, String?>> = listOf(
            "surfaces" to "menu", "walkdoc" to null, "surfaces" to "kbd",
            "games" to null, "surfaces" to "excl", "surfaces" to "deep",
            "settings" to null, "surfaces" to null,
        )

        /** The §1 grammar as the ring delivers it, long-press pair included. */
        val GESTURES = intArrayOf(
            EvenHubMsg.EV_CLICK, EvenHubMsg.EV_CLICK, EvenHubMsg.EV_CLICK,
            EvenHubMsg.EV_DOUBLE_CLICK, EvenHubMsg.EV_DOUBLE_CLICK,
            EvenHubMsg.EV_SCROLL_TOP, EvenHubMsg.EV_SCROLL_TOP,
            EvenHubMsg.EV_SCROLL_BOTTOM, EvenHubMsg.EV_SCROLL_BOTTOM, EvenHubMsg.EV_SCROLL_BOTTOM,
            EvenHubMsg.EV_RING_LONG_PRESS, EvenHubMsg.EV_RING_LONG_PRESS_RELEASE,
        )

        /** 8-bit belief through the emitter's quantiser, so it compares with
         *  a panel that holds nibbles. */
        fun quantised(g: Gray8): Gray8 {
            val out = Gray8(g.w, g.h)
            for (i in g.pix.indices) {
                out.pix[i] = (wm.damage.core.gfx.Pack.level(g.pix[i].toInt() and 0xFF) * 17).toByte()
            }
            return out
        }

        fun firstDiff(a: Gray8, b: Gray8): String? {
            for (y in 0 until Geometry.PANEL_H) for (x in 0 until Geometry.PANEL_W) {
                if (a[x, y] != b[x, y]) return "($x,$y) expected ${a[x, y]} got ${b[x, y]}"
            }
            return null
        }

        /** [firstDiff] with every pixel inside one of [skip] left out (APK 0.83, stage SV item W: a notice timer drawing). */
        fun firstDiff(a: Gray8, b: Gray8, skip: List<Rect>): String? {
            if (skip.isEmpty()) return firstDiff(a, b)
            for (y in 0 until Geometry.PANEL_H) for (x in 0 until Geometry.PANEL_W) {
                if (a[x, y] == b[x, y] || skip.any { x >= it.x && x < it.right && y >= it.y && y < it.bottom }) continue
                return "($x,$y) expected ${a[x, y]} got ${b[x, y]}"
            }
            return null
        }

        /**
         * The independent per-lens truth: the nominal frame is the transparent
         * base, every region vacates its nominal area to black, and the region
         * PIECES render at their shift far to near with the nearest winning.
         * Written without reference to `Compositor.renderTruth`.
         */
        fun truthOf(composed: Gray8, planes: List<Compositor.PlaneRegion>, left: Boolean): Gray8 {
            val out = composed.copy()
            for (p in planes) out.fillRect(p.rect, 0)
            val xs = sortedSetOf(0, Geometry.PANEL_W)
            val ys = sortedSetOf(0, Geometry.PANEL_H)
            for (p in planes) { xs.add(p.rect.x); xs.add(p.rect.right); ys.add(p.rect.y); ys.add(p.rect.bottom) }
            val xa = xs.toIntArray()
            val ya = ys.toIntArray()
            class Piece(val r: Rect, val d: Int)
            val pieces = ArrayList<Piece>()
            for (i in 0 until xa.size - 1) for (j in 0 until ya.size - 1) {
                val r = Rect(xa[i], ya[j], xa[i + 1] - xa[i], ya[j + 1] - ya[j])
                if (r.w <= 0 || r.h <= 0) continue
                val owner = planes.lastOrNull { it.rect.contains(r) } ?: continue
                pieces.add(Piece(r, owner.disparity))
            }
            for (p in pieces.sortedByDescending { it.d }) {
                val shift = if (left) -p.d else p.d
                for (y in p.r.y until p.r.bottom) for (x in p.r.x until p.r.right) {
                    val tx = x + shift
                    if (tx in 0 until Geometry.PANEL_W) out[tx, y] = composed[x, y]
                }
            }
            return out
        }
    }
}
