package wm.damage.core

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import wm.damage.core.comp.CachedText
import wm.damage.core.geom.Geometry
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.local.BoundList
import wm.damage.core.local.Taps
import wm.damage.core.motion.Transitions
import wm.damage.core.motion.Transitions.Tier
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.MainStaging
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.transitionRegion
import wm.damage.core.transport.atlasCapacity
import wm.damage.core.transport.bind
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.EvenHubMsg

/**
 * Build C3, stage DP2: the shell's full-screen transitions on the owning instance (`FIRMWARE.md` §7.3, §7.7, §7.11;
 * `MOTION.md` §7). Context for the reader: Adam's own glasses on his own firmware fork; nothing here contacts a device —
 * the glasses are the firmware model behind [SimTransport], its engine woken every 4 ms, on a bit-11 build (DamageCaps
 * 0xdff) at the ladder's stage 4 or 5.
 *
 * The first pin is taken on the tree below stage 5 as it stands without any C3 code: without stage 5 a Select, a Back and
 * the silent clock's enter and exit write exactly the bytes of `wip/083-int` at 8324c12a (APK 0.82 with 0.83's
 * integration before the effects branch joined: K1-1, the Files relist, K1-4a, the scroll package, Main staged behind
 * the clock, RR; recorded there by the integration's part 2, as C3 DM-T stage 0 had recorded 495dbc9e, 0.82's own
 * recording having been DP2's).
 */
class TransitionShellTest {

    internal class Row(id: String, name: String, var s: DamageWindow.Summary) : DamageWindow(id, name, IconKind.FILES) {
        /** A mark in the window's picture a test may move (DP2: a write that is not his input). */
        @Volatile var mark = 0
        /** A busy picture (a level per 4 × 2 block): its frame takes several flushes. */
        @Volatile var noise = false
        /** X2P2: a 120 × 40 box at full level in the middle of the content — what a scroll in this window (his input) or
         *  a test (the window's own repaint) puts up. */
        @Volatile var big = false
        /** C3 DM-T: strings the window draws through the shell's cached text (Encode's cells come from them; a face drawn
         *  for the first time is an atlas chunk queued). */
        @Volatile var labels: List<Pair<String, wm.damage.core.text.FontSpec>> = emptyList()
        /** C3 X2T: the labels' line pitch (a dense page takes a smaller one). */
        @Volatile var gap = 26
        /** C3 X2T: no squares — the labels alone on black (the notice row reads every glyph's block on each lens). */
        @Volatile var plain = false
        @Volatile var text: CachedText? = null
        /** X3D (`ShipVectors`, the cut act): with [levels] a tap in the window opens its second level — a Select inside
         *  the window, drawn as a band across the content. Off by default: a tap changes nothing, as before. */
        @Volatile var levels = false
        @Volatile var deep = false
        override fun levelDepth(): Int = if (deep) 2 else 1
        override fun view(): WindowView = WindowView.CanvasView({ g, r ->
            g.fillRect(r, 0)
            if (noise) { val rnd = java.util.Random(7); for (y in r.y until r.bottom - 1 step 2) for (x in r.x until r.right - 3 step 4) g.fillRect(x, y, 4, 2, rnd.nextInt(16) * 17) }
            if (!plain) for (k in 0 until 6) g.fillRect(r.x + 8 + k * (r.w - 24) / 5, r.y + 6 + k * (r.h - 20) / 5, 8, 8, (6 + k) * 17)
            if (mark != 0) g.fillRect(r.x + 40 + mark * 8, r.y + r.h / 2, 24, 12, 15 * 17)
            if (big) g.fillRect(r.x + r.w / 2 - 60, r.y + r.h / 2 - 20, 120, 40, 15 * 17)
            labels.forEachIndexed { i, (l, f) -> text?.draw(g, r.x + 40, r.y + 40 + gap * i, l, f, 255) }
            if (deep) g.fillRect(r.x + 16, r.y + r.h / 3, r.w - 32, 32, 12 * 17)
        }, onScroll = { big = true }, onTap = if (levels) ({ deep = !deep }) else null)
        override fun summary() = s
        override fun saveState(): JsonObject = JsonObject(emptyMap())
        override fun restoreState(state: JsonObject) {}
    }

    /** C3 X2T item 3: a document (level 1: one layout for its life, so page staging stages its strips) whose tap opens an
     *  actions level (2) and whose back returns to it; [chars] letters a line through the shell's cached text. */
    private class Pages(val text: CachedText) : DamageWindow("pages", "Pages", IconKind.READER) {
        val model = wm.damage.core.shell.DocModel()
        @Volatile var level = 1
        @Volatile var chars = 60
        @Volatile var step = 6
        private val f = wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM, 18)
        private val actions = ListModel()
        private val prose = "the quick brown fox jumps over the lazy dog while five wizards box and jump quickly at dawn ".repeat(3)
        override fun view(): WindowView = if (level == 1) WindowView.DocView(model, { 200 }, 24,
                { g, i, r -> text.draw(g, r.x + 4, r.y + 4, prose.substring(i * 7 % 60, i * 7 % 60 + chars), f, 255) }, { level = 2 },
                stepLines = { step }, contentKey = { this })
            else WindowView.ListView(actions, { 3 }, { g, i, r, _ -> text.draw(g, r.x + 8, r.y + 4, "Action $i", f, 255) },
                { g, r, i -> text.draw(g, r.x + 8, r.y + 4, "Action $i", f, 255) }, {})
        override fun back(): Boolean = if (level == 2) { level = 1; true } else false
        override fun levelDepth() = level
        override fun summary() = Summary("pages")
        override fun saveState(): JsonObject = JsonObject(emptyMap())
        override fun restoreState(state: JsonObject) {}
    }

    /** [kib256] (C3 DM-T): the transport made over a stored ladder record that holds [stage] and a 256 KiB last session
     *  (`MainLocalShellTest`'s form, decisions AD.1), so the first start asks 256 KiB — the session Encode plays on.
     *  [nRows] (X2P2 stage 2): Main's row count, for a frame that takes several flushes. The shell over the firmware
     *  model on the instant link; internal since X3D: `ShipVectors` takes the shipped programs from it. */
    internal class Rig(val features: Int = 0xdff, val stage: Int = 4, val taps: Taps = Taps.REPORT, val kib256: Boolean = false, nRows: Int = 5,
                       val paced: Boolean = false) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp = Files.createTempDirectory("damage-transition")
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = features }
        val t: wm.damage.core.transport.CfwTransportBase = if (paced) StartRig(sim, scope)
            else if (!kib256) SimTransport(sim, scope, SimTransport.Timing(instant = true))
            else StartRig(sim, scope, c1StageFile = tmp.resolve("c1-stage").also { f ->
                Files.writeString(f, wm.damage.core.transport.LadderRecord(stage = stage, sessionKib = 256).encode()) })
        /** C3 TR1, a [paced] rig: his link as his first stage-5 session measured it (2026-11-21), from the phone's side.
         *  RIGHT's image acks reach the transport [ackDelayMs] after the model handled the image; its 114 bodies
         *  [outBodyDelayMs] (slot 2) and [inBodyDelayMs] (slot 3) after the model drew the half's last step — where the
         *  time went on his glasses (RIGHT ran the PLAY late after its ack, late steps, the body's way back) is one sum
         *  seen from the phone; the bodies of [dropBodySlots] never come. All 0 until a test sets them: the rig's start
         *  runs unpaced. Acks and bodies each keep their order. */
        @Volatile var ackDelayMs = 0L
        @Volatile var outBodyDelayMs = 0L
        @Volatile var inBodyDelayMs = 0L
        @Volatile var dropBodySlots: Set<Int> = emptySet()
        private val lateAcks = kotlinx.coroutines.channels.Channel<Triple<Long, Arm, ByteArray>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        private val lateBodies = kotlinx.coroutines.channels.Channel<Triple<Long, Arm, ByteArray>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val journal = tmp.resolve("journal.jsonl")
        val ct = CachedText(FakeText())
        val shell = Shell(ct, t, Persistence(tmp.resolve("state.json")), journal, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
        val rows = (0 until nRows).map { Row("w$it", "Win $it", DamageWindow.Summary("summary $it", detail = "detail of $it")).also { r -> r.text = ct } }
        /** Every top-level image a lens is handed, in order, while [recording]. */
        val images = java.util.concurrent.CopyOnWriteArrayList<String>()
        /** Every top-level image a lens is handed from the rig's start — the session start included (X2P2 stage 2). */
        val allImages = java.util.concurrent.CopyOnWriteArrayList<String>()
        @Volatile var recording = false
        @Volatile var onImage: ((Arm, ByteArray) -> Unit)? = null
        /** 0.83 (decisions CC): LEFT's engine sees the clock this many ms late — each lens draws on its own clock, and the
         *  shell learns a half's end from RIGHT's body alone. */
        @Volatile var leftEngineLagMs = 0L

        init {
            for (r in rows) shell.register(r)
            t.devProbe("c1", "$stage")
            sim.imageObserver = { a, img -> onImage?.invoke(a, img)
                val line = "${a.name.first()} " + img.joinToString("") { "%02x".format(it) }
                allImages += line; if (recording) images += line }
            scope.launch { while (true) { val now = System.currentTimeMillis(); for (a in Arm.entries) sim.conformanceWake(a, if (a == Arm.LEFT) now - leftEngineLagMs else now); delay(4) } }
            if (paced) {
                val rig = t as StartRig
                rig.onNotify = { a, p ->
                    val now = System.currentTimeMillis()
                    val motion = if (StartRig.sid(p) == wm.damage.core.wire.SettingsMsg.SID && p.size >= wm.damage.core.wire.AaFrame.HEADER + wm.damage.core.wire.AaFrame.CRC)
                        wm.damage.core.wire.DamageMsg.parseMotion(StartRig.body(p)) else null
                    val slot = motion?.slot ?: -1
                    when {
                        slot >= 0 && slot in dropBodySlots -> null
                        slot == 2 && outBodyDelayMs > 0 -> { lateBodies.trySend(Triple(now + outBodyDelayMs, a, p)); null }
                        slot == 3 && inBodyDelayMs > 0 -> { lateBodies.trySend(Triple(now + inBodyDelayMs, a, p)); null }
                        StartRig.sid(p) == EvenHubMsg.SID && ackDelayMs > 0 -> { lateAcks.trySend(Triple(now + ackDelayMs, a, p)); null }
                        else -> p
                    }
                }
                for (ch in listOf(lateAcks, lateBodies)) scope.launch {
                    for ((due, a, p) in ch) { val w = due - System.currentTimeMillis(); if (w > 0) delay(w); rig.deliver(a, p) }
                }
            }
        }

        fun notes(kind: String): List<String> = if (!Files.exists(journal)) emptyList() else Files.readAllLines(journal)
            .filter { "\"ev\":\"note\",\"kind\":\"$kind\"" in it }.map { it.substringAfter("\"detail\":\"").substringBefore("\"") }

        suspend fun until(what: String, maxMs: Long = 30_000, cond: () -> Boolean) =
            waitUntil(maxMs, 5, { "$what — transition ${notes("transition").takeLast(4)}; local ${notes("local").takeLast(3)}; ${shell.quiescenceReport()}" }, cond)

        suspend fun settle(what: String) = until("$what: settled") { shell.isQuiescent() }

        fun <T> onLoop(block: () -> T): T {
            val out = java.util.concurrent.CompletableFuture<T>()
            shell.services.runOnShell { out.complete(block()) }
            return out.get(10, java.util.concurrent.TimeUnit.SECONDS)
        }

        suspend fun up(settings: (wm.damage.core.shell.ShellSettings) -> wm.damage.core.shell.ShellSettings = { it }) = upAt(352, settings)

        /** C3 X2T: [up] at [height]. */
        suspend fun upAt(height: Int, settings: (wm.damage.core.shell.ShellSettings) -> wm.damage.core.shell.ShellSettings = { it }) {
            shell.start(); settle("start")
            shell.updateSettings { settings(it.copy(heightMode = height, cachedText = "on", taps = taps)) }
            until("the atlas goes live") { shell.cachedTextActive && shell.cachedFontsLive.isNotEmpty() }
            until("Main's list bound") { onLoop { shell.mainBoundForTest().first } != null }
            settle("up")
            // FX-083 T1 (as `MainLocalShellTest`'s up() since K1-4a): on a 256 KiB session with BIND the notice's programs
            // are written and counted after the start, one at a time; a row begins once they are and no cache work is on
            // its way — one evaluation on the loop deciding — else a program's write (mode 25 in the notice's slice, inside
            // the stage reserve) lands in the row's recording ahead of its act (2 of 3 runs on the vector lane's branch)
            until("the idle lanes at rest") { onLoop { shell.noticeLaneDoneForTest() && !shell.cacheWorkInFlight } }
            settle("up, the idle lanes at rest")
        }

        /** A ring gesture: the hook on both lenses, then its stock message through the transport — as the ring's own (the
         *  0.83 integration: since the scroll package's fix stage [wm.damage.core.transport.Transport.injectInput] is a
         *  screen's gesture the local rules leave alone, so the rig's echo goes as `MainLocalShellTest`'s does). */
        fun fire(eventId: Int) {
            val now = System.currentTimeMillis()
            for (a in Arm.entries) sim.conformanceInput(a, CfwModes.DEV_RING, eventId.toLong(), 0, now)
            t.ringStockForTest(BoundList.stockEvent(eventId)!!)
        }

        fun tap() = fire(CfwModes.EV_TAP)

        /** Main's bound row as the shell holds it. */
        fun row(): Int = onLoop { shell.mainBoundForTest().second }

        fun doubleTap() = shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)

        fun panel(left: Boolean): Gray8 {
            val ctx = if (left) sim.left else sim.right
            val g = Gray8(Geometry.PANEL_W, Geometry.PANEL_H)
            for (y in 0 until Geometry.PANEL_H) for (x in 0 until Geometry.PANEL_W) {
                val b = ctx.panel[y * ctx.stride + (x shr 1)].toInt() and 0xFF
                g[x, y] = (if (x and 1 == 0) b shr 4 else b and 0x0F) * 17
            }
            return g
        }

        /** A lens whose engine still has a step of a transition's half to draw (the halves' own tags). The shell learns a
         *  half's end from RIGHT's body alone, so it can be quiescent while LEFT's engine is a step or more behind: on the
         *  model with LEFT 30 ms late the quick-Back row's oracle read LEFT mid-way in 2 of 10 runs, the difference gone by
         *  itself 0.1–0.25 s later (0.83, decisions CC; seen once without the lag, under four lanes' load). Under the
         *  model's lock. */
        fun aLensStillDrawsATransition(): Boolean = synchronized(sim) { Arm.entries.any { a ->
            sim.motionForTest(a).slots.any { o -> o != null && o.drawing && (o.tag and 0xE000) == wm.damage.core.shell.TransitionRun.TAG_OUT } } }

        /** Belief equals both lenses' panels, read ON the loop with nothing else pending and no lens still drawing a
         *  transition's half ([aLensStillDrawsATransition]) — both asked in the one evaluation that reads the panels. */
        suspend fun assertOracle(what: String) {
            var diff: String? = null
            var read = false
            val deadline = System.currentTimeMillis() + 30_000
            while (!read && System.currentTimeMillis() < deadline) {
                if (!shell.isQuiescent()) { delay(5); continue }
                val r = shell.sampleIdle {
                    if (aLensStillDrawsATransition()) return@sampleIdle STILL_DRAWING
                    val out = StringBuilder()
                    for (left in listOf(true, false)) {
                        val b = shell.comp.expectedLens(left); val g = panel(left)
                        loop@ for (y in 0 until Geometry.PANEL_H) for (x in 0 until Geometry.PANEL_W)
                            if (wm.damage.core.gfx.Pack.level(b[x, y]) * 17 != g[x, y]) { out.append("${if (left) "LEFT" else "RIGHT"} belief != glass at ($x,$y); "); break@loop }
                    }
                    out.toString()
                }
                if (r === STILL_DRAWING) { delay(5); continue }
                if (r != null) { read = true; diff = r }
            }
            assertTrue(read, "$what: the shell never held still to be sampled, or a lens never drew its transition's last step — ${shell.quiescenceReport()}; " +
                "engines ${Arm.entries.map { a -> "$a " + sim.motionWindows(a).map { "${it.slot}/${if (it.drawing) "drawing" else "done"}" } }}")
            assertTrue(diff.isNullOrEmpty(), "$what: $diff")
        }

        fun close() { scope.cancel(); tmp.toFile().deleteRecursively() }

        private companion object { /** [assertOracle]: a lens still draws — sample again. */ val STILL_DRAWING = String(charArrayOf('~')) }
    }

    private fun file(name: String): List<String>? = javaClass.getResource("/transitions/$name")?.readText()?.lines()?.filter { it.isNotBlank() }

    private fun assertWire(name: String, got: List<String>, what: String) {
        val want = file(name) ?: run {
            val out = Paths.get("build", "transitions").also { Files.createDirectories(it) }.resolve(name)
            Files.write(out, got)
            fail("no expectation for $name: this run's ${got.size} images written to ${out.toAbsolutePath()}")
        }
        val (w, g) = normal(want) to normal(got)
        // every run's recording kept beside the build's results, for a reader of a failure
        Paths.get("build", "transitions").also { Files.createDirectories(it) }.resolve("got-${System.nanoTime()}.txt").let { Files.write(it, got) }
        assertEquals(w.size, g.size, "$what: drawing image count against $name")
        for (i in w.indices) assertEquals(w[i], g[i], "$what: drawing image $i differs from $name")
    }

    /**
     * The images as the pin compares them, measured on the recordings of the tree as it came from 0.82 (five runs: the
     * same drawing bytes every time, two readings that are not the act's varying; `wip/083-int`'s four runs, DM-T: the
     * same under this form, the link cell's "K/s" text varying): each image a lens took as its
     * sub-messages (a lone image is one), less (1) an image holding only mode-26/27 messages — the rules' answers
     * (COMMIT, DISCARD, SETTLE) and tables (SET, CLEAR, LIST), a table repair's re-send to RIGHT — whose moment follows
     * the telemetry reads (in two runs of five one of them fell inside the recording); a lone CLOSE, PLAY or PLAY-AFTER
     * would be dropped by this rule too, so the test reads every such image raw and refuses one that holds anything
     * but the rules' answers and tables; (2) a lone cache write (mode 12, 19, 25: the atlas's uploads in idle, whose
     * chunks follow what the process's earlier shells drew — the class's own order gives other chunks than a lone run),
     * which the test reads raw for a version-2 program record (mode 25 inflated first); and (3) a text draw holding
     * "K/s", the link cell's measured throughput.
     */
    private fun normal(images: List<String>): List<String> = images.mapNotNull { line ->
        val ss = subs(bytes(line.substring(2)))
        if (ss.all { (it[0].toInt() and 0x7F) == 26 || (it[0].toInt() and 0x7F) == 27 } ||
            ss.size == 1 && (ss[0][0].toInt() and 0x7F) in setOf(12, 19, 25)) null
        else line.substring(0, 2) + ss.map { b -> b.joinToString("") { "%02x".format(it) } }
            .filter { h -> (0 until h.length - 5 step 2).none { h.startsWith("4b2f73", it) } }.joinToString("|")
    }

    /** The four acts, each settled, recorded from the first: a Select (a tap on Main's row 0), a Back (a double tap at the
     *  window's root), the silent clock entered (a double tap at Main) and left (a double tap there). */
    private suspend fun fourActs(r: Rig): List<String> {
        r.settle("before the acts")
        r.recording = true
        r.tap()
        r.until("w0 opens") { r.onLoop { r.shell.currentWindowId() } == "w0" }
        r.settle("Select")
        r.doubleTap()
        r.until("back on Main") { r.onLoop { r.shell.currentWindowId() } == null }
        r.settle("Back")
        r.until("Main's list bound again") { r.onLoop { r.shell.mainBoundForTest().first } != null }
        r.settle("bound again")
        r.doubleTap()
        r.settle("the silent clock")
        r.doubleTap()
        r.settle("Main again")
        r.recording = false
        return r.images.toList()
    }

    /**
     * DP2 item 4: without stage 5 — a bit-11 build at stage 4 — a Select, a Back and the silent clock's enter and exit
     * write exactly the bytes of the tree below stage 5 without C3: `wip/083-int` at 8324c12a (d93b064c's code: K1-4a,
     * the scroll package, Main staged behind the clock, RR — 0.83 integration part 2, item 1: the file is that tree's
     * recording, made by this rig — Row, Rig and [fourActs] copied verbatim from c0e8d188 — in a scratch copy holding
     * none of the C3 branch's code, four runs agreeing under [normal]; against 495dbc9e's recording, which DM-T stage 0
     * made and this one replaced, nothing differs under [normal]; against 0.82's, the only difference is K1-1's program
     * layout: the two scroll entries' SETs name the pans 56 B higher, `0x6b81` → `0x6b8f` and `0x6bb1` → `0x6bbf`, in
     * the two frames that bind Main's list); no mode-26 sub 8 and no version-2 record
     * among them — read raw in every image, inside every cache write too (X2P2 item 5), over the WHOLE session from its
     * start (X2P2 stage 2: Main's staging and the atlas's first uploads go before `up()` returns; the comparison itself
     * stays over the four acts).
     */
    @Test
    fun withoutStage5TheFourActsWriteTheBytesOf083int_DP2(): Unit = runBlocking { fourActsBelowStage5(glyphsStored = false) }

    /** C3 DM-T: below stage 5 nothing changes — the same pin with Encode and Matrix stored in every tier's rows. */
    @Test
    fun withoutStage5EncodeAndMatrixStoredChangeNoByte_DMT(): Unit = runBlocking { fourActsBelowStage5(glyphsStored = true) }

    /**
     * C3 X2T item 4 (decisions AM (1), AQ (4)): below stage 5 no screen is recorded — the counter in `screenRuns()` stays
     * at 0 through the four acts with Encode and Matrix stored in every tier, on the plain session (160 KiB) and on a
     * 256 KiB session with BIND (stage 4 holds BIND: everything Encode needs but EFFECTS), where the stage-5 gate is the
     * only one left. (DM-T's pin `below-stage5-capture` survived because no byte shows a recording; this is its observable.)
     */
    @Test
    fun belowStage5NoScreenIsRecorded_X2T(): Unit = runBlocking {
        for (kib256 in listOf(false, true)) {
            val r = Rig(stage = 4, kib256 = kib256)
            try {
                r.up(only("encode", "matrix"))
                assertEquals(kib256, r.t.state.value.cacheSize == CfwModes.CACHE2_MAX_RIDERS && r.t.state.value.bind, "the session asked for")
                fourActs(r)
                assertEquals(0, r.onLoop { r.shell.screenRunsForTest() }, "256 KiB $kib256: no screen recorded below stage 5")
                assertTrue(r.notes("transition").isEmpty(), "256 KiB $kib256: no transition below stage 5: ${r.notes("transition")}")
            } finally { r.close() }
        }
    }

    /**
     * C3 X2T item 4 (the review's 5, decisions AQ (4)): the act's effect is picked before anything is painted and the
     * screens are recorded only when the pick is Encode — with Depth and Encode checked and the pick seeded, three
     * Selects and three Backs: two recordings per act that played Encode, none for an act that played Depth.
     */
    @Test
    fun theScreensAreRecordedOnlyForEncodePicks_X2T(): Unit = runBlocking {
        val r = Rig(stage = 5, kib256 = true)
        try {
            r.rows[0].labels = listOf("Window zero" to wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM, 18))
            r.up(only("depth", "encode"))
            r.shell.transitionRandom = java.util.Random(0x5EED_0004L)
            val runs0 = r.onLoop { r.shell.screenRunsForTest() }
            repeat(3) {
                selectAndSettle(r)
                r.doubleTap(); r.until("back on Main") { r.onLoop { r.shell.currentWindowId() } == null }; runEnds(r, "the Back ends"); r.settle("on Main")
            }
            val owed = r.notes("transition").filter { it.startsWith("owed: ") }
            val encode = owed.count { "Encode" in it.substringBefore(" over") }
            val depth = owed.count { "Depth" in it.substringBefore(" over") }
            println("X2T item 4: ${owed.map { it.removePrefix("owed: ").substringBefore(" over") }}; screens recorded ${r.onLoop { r.shell.screenRunsForTest() } - runs0}")
            assertTrue(encode > 0 && depth > 0 && encode + depth == 6, "both effects among the six acts: $owed")
            assertTrue(r.notes("transition").none { "left out" in it }, "${r.notes("transition")}")
            assertEquals(2 * encode, r.onLoop { r.shell.screenRunsForTest() } - runs0, "two screens per Encode act, none per Depth act")
        } finally { r.close() }
    }

    /**
     * C3 X3 item B (decisions CD): an Encode act works each half's cost out ONCE — the share check, the PLAY, the PLAY-AFTER
     * and the `owed:` line read the plan's kept figures — for a Select into a window and its Back, and for the Select into
     * Settings and into a category (every tier Encode only, at 352); and an act that plays no transition records no
     * screen: in Settings the tap that enters a row's adjust level and the Back that leaves it (the same screen key before
     * and after them), while the taps that open Settings and a category record their two each.
     */
    @Test
    fun anEncodeActWorksEachCostOutOnceAndAnActOnTheSameScreenRecordsNothing_X3(): Unit = runBlocking {
        val r = Rig(stage = 5, kib256 = true, nRows = 7)
        try {
            r.rows[0].labels = listOf("Window zero" to wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM, 18))
            r.up(only("encode"))
            fun runs() = r.onLoop { r.shell.screenRunsForTest() }
            fun owed() = r.notes("transition").count { it.startsWith("owed: ") }
            /** One act, its run held at the in half until its plan is in hand; the plan's two programs' counts once it ended. */
            suspend fun encodeAct(what: String, done: () -> Boolean, act: () -> Unit) {
                val runs0 = runs()
                r.shell.transitionHoldInHalfForTest = true
                act()
                r.until("$what: the run in hand") { r.onLoop { r.shell.transitionBuiltForTest() } != null }
                val b = r.onLoop { r.shell.transitionBuiltForTest() }!!
                r.shell.transitionHoldInHalfForTest = false
                r.shell.postIdleTick()
                runEnds(r, "$what ends"); r.until(what) { done() }; r.settle(what)
                assertEquals(wm.damage.core.motion.Transitions.Effect.ENCODE, b.effect, "$what plays Encode")
                assertEquals(1 to 1, b.out.costsWorked to b.inn.costsWorked, "$what: each half's cost worked out once (out to in)")
                assertEquals(runs0 + 2, runs(), "$what: the leaving and the arriving screen recorded")
            }
            fun win() = r.onLoop { r.shell.currentWindowId() }
            encodeAct("the Select into w0", { win() == "w0" }) { r.tap() }
            encodeAct("the Back to Main", { win() == null }) { r.doubleTap() }
            r.fire(CfwModes.EV_DOWNROLL); r.until("Main's cursor on Settings") { r.row() == 7 }; r.settle("on Settings' row")
            encodeAct("the Select into Settings", { win() == "settings" }) { r.tap() }
            val sw = r.shell.javaClass.getDeclaredField("settingsWindow").also { it.isAccessible = true }.get(r.shell) as wm.damage.core.shell.SettingsWindow
            fun depth() = r.onLoop { sw.levelDepth() }
            val d1 = depth()
            encodeAct("the Select into Global", { depth() == d1 + 1 }) { r.tap() }
            // Global's first row (Brightness) adjusts: its tap enters the adjust level, the Back leaves it — no screen changes
            val d2 = depth()
            val runs0 = runs(); val owed0 = owed()
            r.tap(); r.until("the adjust level entered") { depth() == d2 + 1 }; r.settle("adjusting")
            assertEquals(runs0, runs(), "a tap entering the adjust level records no screen")
            r.doubleTap(); r.until("the adjust level left") { depth() == d2 }; r.settle("back on the rows")
            assertEquals(runs0, runs(), "a Back leaving the adjust level records no screen")
            assertEquals(owed0, owed(), "neither plays a transition")
            // the predicate's other side: back to the categories, into Animations, and a tap on its first row (Scroll, a
            // tier: its sub menu is one more level) — each changes the screen and records its two
            encodeAct("the Back to the categories", { depth() == d1 }) { r.doubleTap() }
            val catModel = wm.damage.core.shell.SettingsWindow::class.java.getDeclaredField("catModel").also { it.isAccessible = true }
            r.fire(CfwModes.EV_UPROLL)
            r.until("the categories' cursor on Animations") { r.onLoop { (catModel.get(sw) as wm.damage.core.shell.ListModel).cursor } == 1 }
            r.settle("on Animations")
            encodeAct("the Select into Animations", { depth() == d1 + 1 }) { r.tap() }
            encodeAct("the Select into Scroll's rows", { depth() == d1 + 2 }) { r.tap() }
            assertTrue(r.notes("transition").none { "left out" in it }, "${r.notes("transition")}")
        } finally { r.close() }
    }

    private suspend fun fourActsBelowStage5(glyphsStored: Boolean) {
        val r = Rig(stage = 4)
        try {
            if (glyphsStored) r.up { s -> Tier.entries.fold(s) { a, t -> a.withTier(t, effects = listOf("encode", "matrix")) } } else r.up()
            if (glyphsStored) assertEquals(listOf("encode", "matrix"), r.onLoop { r.shell.settings.effectsOf(Tier.SELECT) }, "stored")
            val got = fourActs(r)
            // raw, every image: nothing of C3 — no PLAY-AFTER, no PLAY into a transition's slots, no LIST-RING, no CLOSE of
            // a transition's slots, and no version-2 program record inside any cache write
            var writes = 0
            val session = r.allImages.toList()
            assertTrue(session.size > got.size, "the session's images from its start hold more than the acts' (${session.size} vs ${got.size})")
            for (line in session) {
                val ss = subs(bytes(line.substring(2)))
                for (s in ss) {
                    assertTrue(!playAfter(s) && !playIn(s, 2) && !playIn(s, 3) && !closeOf(s, 2) && !closeOf(s, 3) &&
                        !isSub(s, 27, CfwModes.BIND_LIST_RING), "a C3 form below stage 5: $line")
                    if ((s[0].toInt() and 0x7F) in setOf(19, 25)) writes++
                    v2RecordIn(s)?.let { fail("a version-2 program record below stage 5 ($it): $line") }
                }
                // the transport's own reader too (a PLAY-AFTER, a LIST-RING, a SET's kind 3, anywhere in the image)
                assertTrue(!CfwModes.carriesEffects(bytes(line.substring(2))), "a C3 form below stage 5 (the transport's reader): $line")
            }
            // what `normal` drops from the acts as the rules' messages: their answers and tables only, never a CLOSE, PLAY or
            // RETARGET (the session's start, not compared, is read above for C3 forms only)
            for (line in got) {
                val ss = subs(bytes(line.substring(2)))
                if (ss.all { (it[0].toInt() and 0x7F) == 26 || (it[0].toInt() and 0x7F) == 27 })
                    assertTrue(ss.all { (it[0].toInt() and 0x7F) == 27 || (it[1].toInt() and 0xFF) in 3..5 }, "an image the comparison leaves out holds more than the rules' answers and tables: $line")
            }
            assertTrue(writes > 0, "the recording holds cache writes (the check above read some)")
            assertWire("acts-0xdff-stage4-083int.txt", got, "0xdff stage 4")
            // and the shell did not so much as plan one (no program built, no note): the act is today's cut
            assertTrue(r.notes("transition").isEmpty(), "no transition below stage 5: ${r.notes("transition")}")
        } finally { r.close() }
    }

    // ----------------------------------------------------------------------------------------------- stage 5
    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun bytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun subs(img: ByteArray): List<ByteArray> {
        if (img[0].toInt() != 8) return listOf(img)
        val out = ArrayList<ByteArray>()
        var i = 2
        repeat(img[1].toInt() and 0xFF) { val n = u16(img, i); out += img.copyOfRange(i + 2, i + 2 + n); i += 2 + n }
        return out
    }
    private fun isSub(s: ByteArray, mode: Int, sub: Int) = s.size >= 2 && s[0].toInt() == mode && s[1].toInt() == sub
    private fun drawing(s: ByteArray) = (s[0].toInt() and 0x7F) !in setOf(24, 26, 27, 19, 25)
    /** An image the chrome's idle tick sent: it redraws the link cell, whose text holds "K/s" (X2P2: under the in half
     *  it no longer waits, so it may fall there; nothing else repaints in these tests). */
    private fun chromeTick(img: ByteArray) = subs(img).any { s -> s.joinToString("") { "%02x".format(it) }.let { h -> (0 until h.length - 5 step 2).any { h.startsWith("4b2f73", it) } } }
    private fun closeOf(s: ByteArray, slot: Int) = isSub(s, 26, CfwModes.MOTION_CLOSE) && (s[2].toInt() and 0xFF) == slot
    private fun playIn(s: ByteArray, slot: Int) = isSub(s, 26, CfwModes.MOTION_PLAY) && (s[2].toInt() and 0xFF) == slot
    private fun playAfter(s: ByteArray) = isSub(s, 26, CfwModes.MOTION_PLAY_AFTER)

    /** X2P2 item 5: where [s], a cache write (mode 19, or 25 inflated), holds a version-2 program record (`FIRMWARE.md`
     *  §7.2's header at a 4-byte-aligned cache offset — version 2, 1..8 tracks, flags bits 2–7 and the reserved bytes 0 —
     *  whose tracks and 40-B segments walk to exactly its length); null when it holds none or is no cache write. */
    private fun v2RecordIn(s: ByteArray): String? {
        val list = when (s[0].toInt() and 0x7F) {
            19 -> s.copyOfRange(1, s.size)
            25 -> wm.damage.core.gfx.Zl.inflate(s.copyOfRange(1, s.size), CfwModes.ZWRITE_MAX)
            else -> return null
        }
        var i = 0
        while (i + 4 <= list.size) {
            val off = u16(list, i) * 4; val n = u16(list, i + 2); val d = list.copyOfRange(i + 4, minOf(list.size, i + 4 + n))
            for (o in d.indices) if ((off + o) % 4 == 0 && isV2Record(d, o)) return "cache offset ${off + o}"
            i += 4 + n
        }
        return null
    }

    private fun isV2Record(d: ByteArray, o: Int): Boolean {
        if (o + 8 > d.size || d[o].toInt() != 2) return false
        val tracks = d[o + 1].toInt() and 0xFF; val len = u16(d, o + 2); val flags = d[o + 4].toInt() and 0xFF
        if (tracks !in 1..8 || flags and 0xFC != 0 || d[o + 5].toInt() != 0 || u16(d, o + 6) != 0 || len < 8 || o + len > d.size) return false
        var p = o + 8
        repeat(tracks) {
            if (p + 12 > o + len) return false
            val segs = d[p].toInt() and 0xFF; val perLens = d[p + 2].toInt() and 0xFF
            if (segs !in 1..8 || perLens > 1 || d[p + 3].toInt() != 0) return false
            p += 4 + (if (perLens == 1) 16 else 8) + segs * 40
        }
        return p == o + len
    }

    /** RIGHT's images since recording began, decoded. */
    private fun right(r: Rig): List<ByteArray> = r.images.filter { it.startsWith("R ") }.map { bytes(it.substring(2)) }

    private suspend fun selectAndSettle(r: Rig) {
        r.tap()
        r.until("w0 opens") { r.onLoop { r.shell.currentWindowId() } == "w0" }
        r.until("the transition ends") { r.onLoop { r.shell.transitionPhaseForTest() } == null }
        r.settle("Select")
    }

    /**
     * DP2 items 1 and 6, at stage 5 on RIGHT's wire: a Select writes the records into the transition region (one mode-19
     * message), then — in an image of their own, nothing drawn — the cover's CLEARs, the out half's PLAY in slot 2 and
     * the tap's DISCARD after it; the window's frame follows with no CLOSE of slot 2, the last of its images carrying the
     * PLAY-AFTER (slot 3, `after` 2) after its drawing subs and no CLOSE of either owner after that until the in half
     * ends (a drawing image there is the chrome's idle tick, which no longer waits); then a CLOSE of slot 2 alone (§7.11).
     * The frame is not on RIGHT's panel when the PLAY-AFTER's image reaches it, and after the in half belief equals both
     * lenses. X2P2 item 3: the journal's first flush of the gesture is the out PLAY's (`in` 1, label TRANSITION).
     */
    @Test
    fun aSelectAtStage5IsAHeldTransition_DP2(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.up()
            r.settle("up")
            var panelAtPlayAfter: Gray8? = null
            r.onImage = { a, img -> if (a == Arm.RIGHT && panelAtPlayAfter == null && subs(img).any { playAfter(it) }) panelAtPlayAfter = r.panel(false) }
            val lines0 = Files.readAllLines(r.journal).size
            r.recording = true
            selectAndSettle(r)
            // the CLOSE after the in half's body goes in a flush of its own: its image reaches the lens after the settle
            r.until("the CLOSE of slot 2 reaches RIGHT") { right(r).any { img -> subs(img).any { closeOf(it, 2) } } }
            r.recording = false
            val imgs = right(r)
            val st = r.t.state.value
            val region = assertNotNull(st.transitionRegion, "a transition region at stage 5")
            // the region lies between the atlas's layout and Main's region / the stage reserve: nothing else writes there
            assertTrue(region.first >= st.atlasCapacity && region.last < st.cacheSize - CfwModes.reserveAbove(st.cacheSize, st.bind),
                "the region $region above the atlas (${st.atlasCapacity}) and below ${st.cacheSize - CfwModes.reserveAbove(st.cacheSize, st.bind)}")
            // mode 19 into the region, or that list deflated as mode 25 where ZWRITE is in force
            val write = imgs.indexOfFirst { ((it[0].toInt() and 0x7F) == 19 && u16(it, 1) * 4 in region) || (it[0].toInt() and 0x7F) == 25 }
            val out = imgs.indexOfFirst { img -> subs(img).any { playIn(it, 2) } }
            assertTrue(write in 0 until out, "the records' write ($write) before the out PLAY ($out): ${imgs.map { "%02x".format(it[0]) }}")
            // X2P2 item 5: the stage-4 pin's raw readers see these forms — the records' version-2 program inside their
            // write, and the out PLAY's image (one the comparison leaves out) holding more than answers and tables
            assertNotNull(v2RecordIn(imgs[write]), "the version-2 reader finds the records in their write")
            assertTrue(subs(imgs[out]).any { (it[0].toInt() and 0x7F) == 26 && (it[1].toInt() and 0xFF) !in 3..5 }, "the out PLAY's image is not answers and tables only")
            // X2P2 item 3(b): the contract lets no cache write ride a batch (§4 reason 8, §5.5, §6.5): two images
            assertTrue(write != out && subs(imgs[out]).none { (it[0].toInt() and 0x7F) in setOf(12, 19, 25) }, "the records' write and the out PLAY are two images")
            val outSubs = subs(imgs[out])
            assertTrue(outSubs.none { drawing(it) }, "the out PLAY's image draws nothing")
            val p = outSubs.indexOfFirst { playIn(it, 2) }
            assertTrue(outSubs.take(p).all { isSub(it, 27, CfwModes.BIND_CLEAR) } && outSubs.take(p).isNotEmpty(), "the cover's CLEARs first: ${outSubs.map { "${it[0]}/${it[1]}" }}")
            assertTrue(outSubs.drop(p + 1).any { isSub(it, 26, CfwModes.MOTION_DISCARD) }, "the tap's DISCARD after the PLAY: ${outSubs.map { "${it[0]}/${it[1]}" }}")
            val pa = imgs.indexOfFirst { img -> subs(img).any { playAfter(it) } }
            assertTrue(pa > out, "a PLAY-AFTER after the out PLAY")
            assertEquals(1, imgs.count { img -> subs(img).any { playAfter(it) } }, "one PLAY-AFTER")
            val paSubs = subs(imgs[pa]).let { s -> s.first { playAfter(it) } }
            assertEquals(3, paSubs[2].toInt() and 0xFF, "slot 3"); assertEquals(2, paSubs[8].toInt() and 0xFF, "after 2")
            assertTrue(imgs.subList(out, pa + 1).none { img -> subs(img).any { closeOf(it, 2) || closeOf(it, 0xFF) } }, "no CLOSE of slot 2 under the frame")
            assertTrue(imgs.subList(out + 1, pa).all { img -> subs(img).none { playAfter(it) } })
            val close = imgs.indexOfFirst { img -> subs(img).any { closeOf(it, 2) } }
            assertTrue(close > pa && subs(imgs[close]).size == 1, "a CLOSE of slot 2 alone after the PLAY-AFTER: $close")
            assertTrue(imgs.subList(pa + 1, close).none { img -> subs(img).any { closeOf(it, 2) || closeOf(it, 3) || closeOf(it, 0xFF) } }, "no CLOSE of an owner under the in half")
            assertTrue(imgs.subList(pa + 1, close).filter { img -> subs(img).any { drawing(it) } }.all { chromeTick(it) }, "nothing of the frame drawn after the PLAY-AFTER")
            assertTrue(r.notes("transition").any { it.startsWith("in half's last step drawn on RIGHT") }, "${r.notes("transition")}")
            // X2P2 item 3(c): the out PLAY's flush is the gesture's first flush in the journal, the frame's after it
            val outFlush = Regex("then the out PLAY as flush (\\d+)").find(r.notes("transition").joinToString("\n"))?.groupValues?.get(1)
            assertNotNull(outFlush, "the out-half note names the out PLAY's flush: ${r.notes("transition")}")
            val submits = Files.readAllLines(r.journal).drop(lines0).filter { "\"ev\":\"submit\"" in it }
            val firstIn = submits.first { "\"in\":1" in it }
            assertTrue("\"id\":$outFlush," in firstIn && "\"label\":\"TRANSITION+select\"" in firstIn, "the gesture's first flush is the out PLAY's: $firstIn")
            r.assertOracle("after the Select")
            val final = r.panel(false)
            val seen = assertNotNull(panelAtPlayAfter, "RIGHT saw the PLAY-AFTER")
            assertTrue(!seen.pix.contentEquals(final.pix), "the window's frame was not on RIGHT's panel before its in half")
            println("DP2 Select at stage 5: ${r.notes("transition").joinToString("\n")}")
            println("DP2 Select at stage 5, RIGHT's images: " + imgs.joinToString("; ") { img ->
                "${img.size} B [" + subs(img).joinToString(" ") { "${it[0].toInt() and 0x7F}/${if ((it[0].toInt() and 0x7F) in setOf(26, 27)) it[1].toInt() else it.size}" } + "]" })
        } finally { r.close() }
    }

    /** A Back and the silent clock both ways are played as their tiers say (Back reversed; Lock forward entering,
     *  reversed leaving), each landing with belief equal to both lenses. */
    @Test
    fun aBackAndTheClockAreTransitionsOfTheirTiers_DP2(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.up()
            selectAndSettle(r)
            r.doubleTap()
            r.until("back on Main") { r.onLoop { r.shell.currentWindowId() } == null }
            r.until("the Back ends") { r.onLoop { r.shell.transitionPhaseForTest() } == null }
            r.assertOracle("after the Back")
            r.doubleTap()
            r.until("the clock's transition ends") { r.notes("transition").count { it.startsWith("owed: Lock") } == 1 && r.onLoop { r.shell.transitionPhaseForTest() } == null }
            r.assertOracle("on the silent clock")
            r.doubleTap()
            r.until("Main's transition ends") { r.notes("transition").count { it.startsWith("owed: Lock") } == 2 && r.onLoop { r.shell.transitionPhaseForTest() } == null }
            r.assertOracle("Main again")
            val owed = r.notes("transition").filter { it.startsWith("owed: ") }.map { it.removePrefix("owed: ").substringBefore(" over") }
            assertEquals(listOf("Select Depth", "Back Depth reversed", "Lock Fade", "Lock Fade reversed"), owed)
            // the acts take the two record slots in turn: an act's write never meets the act before it
            val offs = r.notes("transition").filter { it.startsWith("owed: ") }.map { Regex("at off4 (\\d+)").find(it)!!.groupValues[1].toInt() }
            assertEquals(2, offs.distinct().size, "two slots: $offs")
            assertTrue(offs.zipWithNext().all { (a, b) -> a != b }, "taken in turn: $offs")
            assertEquals(4, r.notes("transition").count { it.startsWith("in half's last step drawn on RIGHT") }, "${r.notes("transition")}")
        } finally { r.close() }
    }

    /** §7.11 and B'3: a Back right after a Select (before its in half has ended) PLAYs slot 2 again — no CLOSE of slot 2
     *  follows the Back's out PLAY until the Back's own in half has ended; then the picture lands. */
    @Test
    fun aQuickBackAfterASelectSendsNoCloseOverItsOutHalf_DP2(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.up()
            r.recording = true
            r.tap()
            r.until("w0 opens") { r.onLoop { r.shell.currentWindowId() } == "w0" }
            r.until("the Select's in half sent") { r.onLoop { r.shell.transitionPhaseForTest() } == "IN" }
            r.doubleTap()
            r.until("back on Main") { r.onLoop { r.shell.currentWindowId() } == null }
            r.until("the Back ends") { r.notes("transition").count { it.startsWith("owed: ") } == 2 && r.onLoop { r.shell.transitionPhaseForTest() } == null }
            r.settle("after")
            r.recording = false
            val imgs = right(r)
            val outs = imgs.withIndex().filter { (_, img) -> subs(img).any { playIn(it, 2) } }.map { it.index }
            assertEquals(2, outs.size, "two out PLAYs")
            assertEquals(2, imgs.count { img -> subs(img).any { playAfter(it) } }, "two PLAY-AFTERs: the Back's in half was sent too")
            assertEquals(1, r.notes("transition").count { it.startsWith("in half's last step drawn") }, "the Back's in half ran whole (the Select's was closed by the Back's PLAY): ${r.notes("transition")}")
            val backIn = r.notes("transition").indexOfFirst { it.startsWith("in half's last step drawn") }
            assertTrue(backIn >= 0, "${r.notes("transition")}")
            val closes = imgs.withIndex().filter { (i, img) -> i > outs[1] && subs(img).any { closeOf(it, 2) } }.map { it.index }
            val backPa = imgs.withIndex().last { (_, img) -> subs(img).any { playAfter(it) } }.index
            assertTrue(closes.all { it > backPa }, "no CLOSE of slot 2 between the Back's out PLAY and its PLAY-AFTER: closes $closes, PLAY-AFTER $backPa")
            assertTrue(closes.size <= 1, "one CLOSE after the Back's in half at most: $closes")
            r.assertOracle("after the quick Back")
        } finally { r.close() }
    }

    /** 0.83, decisions CC: each lens draws on its own clock and only RIGHT reports. With LEFT's engine 30 ms behind
     *  RIGHT's through a Select and a quick Back, the shell is quiescent while LEFT still draws the Back's in half; LEFT
     *  then ends on the same frame — belief equals both lenses once no lens draws. (The rig's oracle asked only the
     *  shell and read LEFT mid-way: 2 of 10 runs of this sequence, and once in the plain row under load.) */
    @Test
    fun aQuickBackWithLeftsEngineBehindRightsEndsOnTheSameFrame_CC(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.up()
            r.leftEngineLagMs = 30
            r.tap()
            r.until("w0 opens") { r.onLoop { r.shell.currentWindowId() } == "w0" }
            r.until("the Select's in half sent") { r.onLoop { r.shell.transitionPhaseForTest() } == "IN" }
            r.doubleTap()
            r.until("back on Main") { r.onLoop { r.shell.currentWindowId() } == null }
            r.until("the Back ends") { r.notes("transition").count { it.startsWith("owed: ") } == 2 && r.onLoop { r.shell.transitionPhaseForTest() } == null }
            r.settle("after")
            r.assertOracle("after the quick Back, LEFT's engine 30 ms behind")
        } finally { r.close() }
    }

    /** §7.11 and DP2 item 5: with RIGHT's in-half body lost, the phone CLOSEs both slots at the in half's nominal end plus
     *  one period — never a pose held until he acts — and the frame shows whole. */
    @Test
    fun aLostInHalfBodyEndsBothSlotsAtTheDeadline_DP2(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.up()
            r.shell.transitionBodyLostForTest = true
            r.recording = true
            val t0 = System.currentTimeMillis()
            selectAndSettle(r)
            val took = System.currentTimeMillis() - t0
            // the CLOSEs go in a flush of their own: their image reaches the lens after the settle
            r.until("CLOSE 2 and CLOSE 3 reach RIGHT") { right(r).any { img -> subs(img).let { s -> s.size == 2 && closeOf(s[0], 2) && closeOf(s[1], 3) } } }
            r.recording = false
            assertTrue(r.notes("transition").any { "no in-half body by its nominal end" in it }, "${r.notes("transition")}")
            val imgs = right(r)
            assertTrue(imgs.any { img -> subs(img).let { s -> s.size == 2 && closeOf(s[0], 2) && closeOf(s[1], 3) } }, "CLOSE 2 and CLOSE 3 in one image")
            val t = wm.damage.core.motion.Transitions.timing(450, 10)
            assertTrue(took >= 2 * t.halfMs, "the deadline is past the nominal end: $took ms")
            r.assertOracle("after the deadline")
        } finally { r.close() }
    }

    /**
     * X2P2 item 4 (the first review pass's finding 4): the deadline counts the out half's end from its PLAY's ACK, not
     * its submit. A busy link held the out PLAY 300 ms after its submit, and the PLAY-AFTER's ack came 10 ms after the out
     * PLAY's: the out PLAY reached the glasses no later than its ack, so the in half's last step falls no later than the
     * later of the PLAY-AFTER's ack and the out half's end (from that ack), plus T − 1 periods — and the deadline falls two
     * periods after that, never inside the in half. No deadline is set until both acks are in.
     */
    @Test
    fun theDeadlineCountsFromTheOutPlaysAck_X2P2() {
        val fx = wm.damage.core.motion.Motion.effects(wm.damage.core.wire.DamageMsg.Caps(2, 0xdff),
            wm.damage.core.wire.DamageMsg.FLAG_DRAW2 or wm.damage.core.wire.DamageMsg.FLAG_ENGINE or wm.damage.core.wire.DamageMsg.FLAG_EFFECTS)
        val timing = wm.damage.core.motion.Transitions.timing(450, 10)
        val b = wm.damage.core.motion.Transitions.build(fx, wm.damage.core.motion.Transitions.Effect.DEPTH, false,
            wm.damage.core.geom.Rect(0, 0, Geometry.PANEL_W, 352), timing, 1024, CfwModes.TRANSITION_SLOT)
        val run = wm.damage.core.shell.TransitionRun(wm.damage.core.motion.Transitions.Tier.SELECT, b, ByteArray(1), ByteArray(1), ByteArray(1), 0xC000, 0xD000, 0L)
        run.phase = wm.damage.core.shell.TransitionRun.Phase.IN
        run.outSentAtMs = 10_000; run.inDoneAtMs = 10_310
        assertTrue(run.deadlineMs() == null, "no deadline before the out PLAY's ack")
        run.outDoneAtMs = 10_300
        val p = timing.period * 1000.0 / 1024
        val inLastLatest = maxOf(10_310.0, 10_300 + timing.steps * p) + (timing.steps - 1) * p
        val deadline = assertNotNull(run.deadlineMs(), "a deadline once both acks are in")
        assertTrue(deadline >= inLastLatest + 2 * p - 2, "the deadline $deadline falls two periods after the in half's latest last step ${inLastLatest.toLong()}")
    }

    // ------------------------------------------------------------- C3 TR1: the backstop on a link like his
    /**
     * C3 TR1 (`private/c3-design/C3-TR1-built.md`): once RIGHT's out-half body is in, the in half's nominal end is that
     * body plus T periods — never before the PLAY-AFTER's ack plus T periods — and the backstop falls the allowance past
     * it; until then (and when that body never comes) the acks' rule stands. No deadline without the PLAY-AFTER's ack.
     */
    @Test
    fun theDeadlineCountsFromRightsOutHalfBodyOnceItCame_TR1() {
        val fx = wm.damage.core.motion.Motion.effects(wm.damage.core.wire.DamageMsg.Caps(2, 0xdff),
            wm.damage.core.wire.DamageMsg.FLAG_DRAW2 or wm.damage.core.wire.DamageMsg.FLAG_ENGINE or wm.damage.core.wire.DamageMsg.FLAG_EFFECTS)
        val timing = wm.damage.core.motion.Transitions.timing(450, 10)
        val b = wm.damage.core.motion.Transitions.build(fx, wm.damage.core.motion.Transitions.Effect.DEPTH, false,
            wm.damage.core.geom.Rect(0, 0, Geometry.PANEL_W, 352), timing, 1024, CfwModes.TRANSITION_SLOT)
        val run = wm.damage.core.shell.TransitionRun(wm.damage.core.motion.Transitions.Tier.SELECT, b, ByteArray(1), ByteArray(1), ByteArray(1), 0xC000, 0xD000, 0L)
        val tp = Math.round(timing.steps * timing.period * 1000.0 / 1024)
        val allow = wm.damage.core.shell.TransitionRun.BODY_ALLOWANCE_MS
        run.phase = wm.damage.core.shell.TransitionRun.Phase.IN
        run.outDoneAtMs = 10_200
        run.outBodyAtMs = 10_600
        assertTrue(run.deadlineMs() == null, "no deadline before the PLAY-AFTER's ack, the out body in hand or not")
        run.inDoneAtMs = 10_400
        assertEquals(10_600 + tp + allow, run.deadlineMs(), "from RIGHT's out-half body: T periods and the allowance")
        run.inDoneAtMs = 10_700
        assertEquals(10_700 + tp + allow, run.deadlineMs(), "never before the PLAY-AFTER's ack plus T periods")
        run.outBodyAtMs = 0
        run.inDoneAtMs = 10_400
        assertEquals(maxOf(10_400, 10_200 + tp) + Math.round((timing.steps + 1) * timing.period * 1000.0 / 1024) +
            wm.damage.core.shell.TransitionRun.ACKS_ALLOWANCE_MS, run.deadlineMs(), "no out-half body: the acks' rule, with its own allowance (decisions BV (2), ML1)")
    }

    /** C3 TR1: his link as the paced rig plays it — acks [ackMs] after RIGHT handled an image (his out PLAY's median
     *  182–197 ms from submit), the out half's body [outLate] ms past the out PLAY's ack plus T − 1 periods (his median
     *  194–197 ms, worst 297), the in half's body that plus T periods plus [inExtra] (his median −18/−23 ms, worst 240:
     *  a PLAY-AFTER RIGHT ran late). */
    private fun paceLikeHis(r: Rig, ackMs: Long = 190, outLate: Long = 195, inExtra: Long = 0) {
        r.ackDelayMs = ackMs; r.outBodyDelayMs = ackMs + outLate; r.inBodyDelayMs = ackMs + outLate + inExtra
    }

    /** When the journal wrote each note of [kind] whose detail contains [part] (ms, the journal's own clock). */
    private fun noteTimesOf(r: Rig, kind: String, part: String): List<Long> = Files.readAllLines(r.journal)
        .filter { "\"ev\":\"note\",\"kind\":\"$kind\"" in it && part in it }
        .map { it.substringAfter("\"t\":").substringBefore(",").toLong() }

    /**
     * C3 TR1: on a link paced like his first stage-5 session (at his median, and at his worst out-body lateness with the
     * in half's body at its worst past the out body) a Select's transition ends at RIGHT's in-half body — no backstop,
     * no CLOSE of slot 3 or of both under the run (the CLOSE that cut one in half of 119 on his glasses at step 3 of 5),
     * both halves drawn whole, the CLOSE of slot 2 alone after the body — and belief equals both lenses after. On the
     * tree before TR1 the backstop fired first in both (his session: 119 of 139).
     */
    @Test
    fun onALinkLikeHisTheBackstopWaitsForTheInHalfsBody_TR1(): Unit = runBlocking {
        for ((outLate, inExtra) in listOf(195L to 0L, 297L to 240L)) {
            val r = Rig(stage = 5, paced = true)
            try {
                r.up()
                paceLikeHis(r, outLate = outLate, inExtra = inExtra)
                r.recording = true
                selectAndSettle(r)
                r.recording = false
                val what = "out body +$outLate ms, in body +$inExtra ms"
                assertTrue(r.notes("transition").none { "no in-half body" in it }, "$what: the backstop fired: ${r.notes("transition")}")
                assertTrue(inHalfWhole(r), "$what: ended by RIGHT's in-half body: ${r.notes("transition")}")
                bothHalvesWhole(r)
                noCloseUnderTheRun(r)
                // decisions BV (3): each half's `motion` note carries the body's field 11, RIGHT's uptime in ticks
                val halves = r.notes("motion").filter { "tag 0xc000 " in it || "tag 0xd000 " in it }
                assertEquals(2, halves.size, "$what: $halves")
                assertTrue(halves.all { Regex(" uptime \\d+ endCrc ").containsMatchIn(it) }, "$what: $halves")
                r.assertOracle("after the Select on a link like his ($what)")
            } finally { r.close() }
        }
    }

    /** C3 TR1: on that link with RIGHT's in-half body lost, the backstop still ends the run — at RIGHT's out-half body
     *  plus T periods and the allowance, both slots CLOSEd in one image — and belief equals both lenses after. */
    @Test
    fun onALinkLikeHisALostInHalfBodyEndsBothSlotsAfterTheAllowance_TR1(): Unit = runBlocking {
        val r = Rig(stage = 5, paced = true)
        try {
            r.up()
            paceLikeHis(r)
            r.dropBodySlots = setOf(3)
            r.recording = true
            selectAndSettle(r)
            r.until("CLOSE 2 and CLOSE 3 reach RIGHT") { right(r).any { img -> subs(img).let { s -> s.size == 2 && closeOf(s[0], 2) && closeOf(s[1], 3) } } }
            r.recording = false
            val ended = r.notes("transition").filter { "no in-half body" in it }
            assertEquals(1, ended.size, "${r.notes("transition")}")
            assertTrue("from RIGHT's out-half body" in ended[0], ended[0])
            val owed = r.notes("transition").first { it.startsWith("owed: ") }
            val (steps, period) = Regex("(\\d+) steps a half at P (\\d+)").find(owed)!!.destructured
            val tp = Math.round(steps.toInt() * period.toInt() * 1000.0 / 1024)
            val outBody = noteTimesOf(r, "motion", "slot 2 tag 0xc000 drew its last step").single()
            val end = noteTimesOf(r, "transition", "no in-half body").single()
            val want = tp + wm.damage.core.shell.TransitionRun.BODY_ALLOWANCE_MS
            assertTrue(end - outBody in (want - 5)..(want + 100),
                "the backstop ${end - outBody} ms after RIGHT's out-half body (T periods $tp + the allowance: $want, the loop's lateness allowed)")
            r.assertOracle("after the backstop on a link like his")
        } finally { r.close() }
    }

    /** C3 TR1, decisions BV (2): on that link with RIGHT's out-half body lost, the acks' rule takes the same allowance,
     *  so it waits for the in half's body as the out-body rule does — the run ends at that body, no CLOSE of slot 3 or of
     *  both under it. (0.83's acks' rule fired first here, as on 119 of his 139.) */
    @Test
    fun onALinkLikeHisALostOutHalfBodyStillEndsAtTheInHalfsBody_TR1(): Unit = runBlocking {
        val r = Rig(stage = 5, paced = true)
        try {
            r.up()
            paceLikeHis(r)
            r.dropBodySlots = setOf(2)
            r.recording = true
            selectAndSettle(r)
            r.recording = false
            assertTrue(r.notes("transition").none { "no in-half body" in it }, "the backstop fired: ${r.notes("transition")}")
            assertTrue(inHalfWhole(r), "ended by RIGHT's in-half body: ${r.notes("transition")}")
            noCloseUnderTheRun(r)
            r.assertOracle("after the Select on a link like his, the out-half body lost")
        } finally { r.close() }
    }

    /** C3 TR1, decisions BV (2): with both bodies lost, the acks' rule ends the run — the later of the PLAY-AFTER's ack
     *  and the out half's end from its PLAY's ack, plus T + 1 periods and its allowance (750 ms, ML1) — both slots CLOSEd in one image;
     *  no waiter stays. The acks are the shell's own `done` rows for the two flushes the run's notes name. */
    @Test
    fun onALinkLikeHisBothBodiesLostEndAtTheAcksRuleAndTheAllowance_TR1(): Unit = runBlocking {
        val r = Rig(stage = 5, paced = true)
        try {
            r.up()
            paceLikeHis(r)
            r.dropBodySlots = setOf(2, 3)
            r.recording = true
            selectAndSettle(r)
            r.until("CLOSE 2 and CLOSE 3 reach RIGHT") { right(r).any { img -> subs(img).let { s -> s.size == 2 && closeOf(s[0], 2) && closeOf(s[1], 3) } } }
            r.recording = false
            val notes = r.notes("transition")
            val ended = notes.filter { "no in-half body" in it }
            assertEquals(1, ended.size, "$notes")
            assertTrue("from its acks plus one period and" in ended[0], ended[0])
            val owed = notes.first { it.startsWith("owed: ") }
            val (steps, period) = Regex("(\\d+) steps a half at P (\\d+)").find(owed)!!.destructured
            val p = period.toInt() * 1000.0 / 1024
            val outId = Regex("then the out PLAY as flush (\\d+)").find(notes.first { it.startsWith("out half sent") })!!.groupValues[1]
            val inId = Regex("rides flush (\\d+)").find(notes.first { it.startsWith("in half's PLAY-AFTER rides") })!!.groupValues[1]
            fun doneAt(id: String) = Files.readAllLines(r.journal).single { "\"ev\":\"done\"" in it && "\"id\":$id," in it }
                .substringAfter("\"t\":").substringBefore(",").toLong()
            val acksRule = maxOf(doneAt(inId), doneAt(outId) + Math.round(steps.toInt() * p)) + Math.round((steps.toInt() + 1) * p) +
                wm.damage.core.shell.TransitionRun.ACKS_ALLOWANCE_MS
            val end = noteTimesOf(r, "transition", "no in-half body").single()
            assertTrue(end - acksRule in -5L..100L, "the backstop at ${end - acksRule} ms from the acks' rule plus its allowance")
            r.assertOracle("after the acks' backstop on a link like his, both bodies lost")
        } finally { r.close() }
    }

    /** K1 §9: a notice that arrives during a transition waits until its end, then shows — a Select's, and ([lock]) the
     *  silent clock's entry, where the small form waits the same (X2P2 stage 2: one rule in every mode). */
    private suspend fun noticeBehind(lock: Boolean) {
        val r = Rig(stage = 5)
        try {
            r.up()
            if (lock) r.doubleTap() else r.tap()
            r.until("the out half sent") { r.onLoop { r.shell.transitionPhaseForTest() }.let { it == "FRAME" || it == "IN" } }
            r.shell.postNotice(wm.damage.core.shell.Notifications.Notice("SMS · test", "sms", "a message", "12:00"))
            var shownDuring = false
            var first = true
            while (true) {
                // the notice was posted before this read, so the loop has handled it when the read runs
                val (phase, up) = r.onLoop { r.shell.transitionPhaseForTest() to r.shell.notificationsUpForTest() }
                if (first) assertTrue(phase != null, "the notice was handled while the transition was in hand"); first = false
                if (phase != null && up) shownDuring = true
                if (phase == null) break
                delay(5)
            }
            assertTrue(!shownDuring, "the notice did not show during the transition")
            r.until("the notice shows after it") { r.onLoop { r.shell.notificationsUpForTest() } }
            if (lock) assertTrue(r.notes("transition").any { it.startsWith("owed: Lock") }, "the silent clock's entry was a transition")
        } finally { r.close() }
    }

    @Test
    fun aNoticeWaitsBehindATransition_DP2(): Unit = runBlocking { noticeBehind(lock = false) }

    @Test
    fun aNoticeOnTheSilentClockWaitsBehindItsTransition_X2P2(): Unit = runBlocking { noticeBehind(lock = true) }

    // ------------------------------------------------------------- X2P2 item 1: a transition plays in full
    /** The big box's core in panel coordinates: its 120 × 40 less 24 px a side (Depth's in half draws `new` up to 8 px off
     *  rest; the content's own disparity), so on each lens, at rest and at every step of that in half, it lies inside it. */
    private fun core(r: Rig): wm.damage.core.geom.Rect {
        val c = r.onLoop { r.shell.layout.content }
        return wm.damage.core.geom.Rect(c.x + c.w / 2 - 36, c.y + c.h / 2 - 20, 72, 40)
    }

    /** Every pixel of [core] on one lens's shadow or panel passes [test] (a level 0..15). */
    private fun every(r: Rig, left: Boolean, shadow: Boolean, core: wm.damage.core.geom.Rect, test: (Int) -> Boolean): Boolean {
        val ctx = if (left) r.sim.left else r.sim.right
        val buf = if (shadow) ctx.shadow else ctx.panel
        for (y in core.y until core.bottom) for (x in core.x until core.right) {
            val b = buf[y * ctx.stride + (x shr 1)].toInt() and 0xFF
            if (!test(if (x and 1 == 0) b shr 4 else b and 0x0F)) return false
        }
        return true
    }

    /**
     * The big box's write reaches both shadows, then [release] runs; on each lens the in half's step d0 + 1 shows it — d0
     * the steps that lens's in half had drawn when the write was in the shadow (its step [steps] is LAND) — and no later
     * step: the core lit on the panel while the in half stands at d0 + 1, never first at d0 + 2. [openBefore]: the in half
     * was open on both lenses before the write went (a write that CLOSEd it empties its slot in the same batch).
     * Preconditions, each failed by name and never passed (the second review pass's 3(a)): the in half still had a step
     * to draw when the write was in the shadow — a write that reaches the shadow only after its last step was held back,
     * or the run was too loaded to exercise the hold — and a look found it at d0 + 1. Returns the step per lens.
     */
    private suspend fun shownByTheNextStep(r: Rig, core: wm.damage.core.geom.Rect, openBefore: Boolean, steps: Int, release: () -> Unit = {}): Map<Arm, Int> {
        r.until("the box's write in both shadows") { listOf(true, false).all { every(r, it, true, core) { v -> v == 15 } } }
        val arms = listOf(Arm.LEFT, Arm.RIGHT)
        val d0 = arms.associateWith { r.sim.motionForTest(it).slots[3]?.d ?: 0 }
        val open0 = arms.associateWith { openBefore || r.sim.motionForTest(it).slots[3] != null }
        assertTrue(r.onLoop { r.shell.transitionPhaseForTest() } != null, "the write reached the shadow only after the run had ended — held back to its end")
        for (a in arms) {
            val s = r.sim.motionForTest(a).slots[3]
            if (s == null && open0.getValue(a)) fail("$a: the in half's slot was emptied — closed — by the time the write was in the shadow")
            assertTrue(s == null || (s.drawing && s.d < steps), "precondition ($a): the write reached the shadow after the in half's last step (d ${s?.d} of $steps, " +
                "${if (s?.drawing == false) "landed" else "drawing"}) — held back, or a run too loaded to exercise the hold")
        }
        release()
        val shown = HashMap<Arm, Int>()
        val atNext = HashMap<Arm, Long>()
        val t0 = System.currentTimeMillis()
        while (shown.size < 2) {
            assertTrue(System.currentTimeMillis() - t0 < 20_000, "the in half never drew a step past the write: $shown, ${r.shell.quiescenceReport()}")
            for (a in arms) if (a !in shown) {
                val s = r.sim.motionForTest(a).slots[3]
                val want = d0.getValue(a) + 1
                if (s == null && open0.getValue(a)) fail("$a: the in half's slot was emptied — closed — before it drew step $want")
                if (s == null || (s.drawing && s.d < want)) continue                     // its next step not drawn yet
                if (s.d > want) {
                    if (a in atNext) fail("$a: the in half drew step $want and the box was not on the panel (it had drawn ${d0.getValue(a)} when the write was in the shadow)")
                    fail("precondition ($a): no look found the in half at step $want (it was at ${s.d}) — a run too loaded to see the step after the write")
                }
                val since = atNext.getOrPut(a) { System.currentTimeMillis() }
                if (every(r, a == Arm.LEFT, false, core) { it != 0 }) { shown[a] = want; continue }
                // the panel's write may trail the step's count by a moment; at LAND there is no later step to wait for
                assertTrue(s.drawing || System.currentTimeMillis() - since < 200, "$a: the in half landed at step $want and the box is not on the panel")
            }
            delay(1)
        }
        return shown
    }

    /** Both halves of the act [act] (counted from the first) ran whole on RIGHT: each body says it drew its last step,
     *  with every step drawn or folded by the model's wake (a fold is the model's lateness, not the phone's) — never
     *  "closed while drawing". */
    private fun bothHalvesWhole(r: Rig, act: Int = 0) {
        val owed = r.notes("transition").filter { it.startsWith("owed: ") }[act]
        val (out, inn) = Regex("tags out 0x([0-9a-f]{4}) in 0x([0-9a-f]{4})").find(owed)!!.destructured
        val steps = Regex("(\\d+) steps a half").find(owed)!!.groupValues[1].toInt()
        for ((slot, tag) in listOf(2 to out, 3 to inn)) {
            val ns = r.notes("motion").filter { "slot $slot tag 0x$tag " in it }
            assertTrue(ns.none { "closed while drawing" in it }, "slot $slot tag 0x$tag was closed: $ns")
            val n = ns.firstOrNull { "drew its last step" in it } ?: fail("slot $slot tag 0x$tag never drew its last step: $ns")
            val (drawn, folded) = Regex("drawn (\\d+) folded (\\d+)").find(n)!!.destructured
            assertEquals(steps, drawn.toInt() + folded.toInt(), "slot $slot: every step drawn or folded: $n")
        }
    }

    /** RIGHT's images: no CLOSE of either owner (or of all) from the out PLAY to the CLOSE of slot 2 alone that follows the
     *  in half's body. */
    private fun noCloseUnderTheRun(r: Rig, act: Int = 0, alsoNot: (ByteArray) -> Boolean = { false }) {
        val imgs = right(r)
        val out = imgs.withIndex().filter { (_, img) -> subs(img).any { playIn(it, 2) } }.getOrNull(act)?.index ?: -1
        val end = imgs.withIndex().indexOfFirst { (i, img) -> i > out && subs(img).let { it.size == 1 && closeOf(it[0], 2) } }
        assertTrue(out in 0 until end, "the out PLAY ($out), then the CLOSE of slot 2 alone ($end)")
        assertTrue(imgs.subList(out + 1, end).none { img -> subs(img).any { closeOf(it, 2) || closeOf(it, 3) || closeOf(it, 0xFF) } }, "a CLOSE of an owner under the transition")
        assertTrue(imgs.subList(out, end + 1).none { img -> subs(img).any { alsoNot(it) } }, "a sub the run must not carry, from its out PLAY to its end")
    }

    private enum class At { OWED, FRAME, IN }

    /**
     * X2P2 item 1 (`FIRMWARE.md` §7.11 as amended 2026-11-20): a write during a transition — his input (a scroll in the
     * arriving window puts the box up) or, with [input] false, the window's own repaint — in the phase [at]: the out
     * half's PLAY still owed behind a full link (OWED), the arriving frame going in with the in half not yet sent (FRAME),
     * or the in half sent (IN). Every flush is held by both owners: the transition's two halves each draw every step,
     * the box is on both lenses at the in half's step after the write (OWED and FRAME: its first; IN: the step after the
     * one it had drawn), no CLOSE of an owner goes before the one after the in half's body, and at rest belief equals both
     * lenses.
     */
    private suspend fun writeDuring(at: At, input: Boolean = true): Map<Arm, Int> {
        val r = Rig(stage = 5)
        try {
            r.up()
            r.recording = true
            if (at == At.OWED) r.shell.pumpHeldForTest = true
            if (at == At.FRAME) r.shell.transitionHoldInHalfForTest = true
            r.tap()
            r.until("w0 opens") { r.onLoop { r.shell.currentWindowId() } == "w0" }
            when (at) {
                At.OWED -> assertEquals("OWED", r.onLoop { r.shell.transitionPhaseForTest() }, "the out half owed behind the held link")
                At.FRAME -> r.until("the frame in, the in half held back") { r.onLoop { r.shell.transitionPhaseForTest() == "FRAME" && !r.shell.comp.hasPending } &&
                    r.sim.motionForTest(Arm.RIGHT).slots[2]?.owns == true }
                // he scrolls as the window first shows: RIGHT's in half has drawn a step and is still drawing
                At.IN -> r.until("RIGHT's in half drawing") { r.onLoop { r.shell.transitionPhaseForTest() } == "IN" &&
                    r.sim.motionForTest(Arm.RIGHT).slots[3]?.let { it.drawing && it.d >= 1 } == true }
            }
            val core = core(r)
            if (at != At.OWED) assertTrue(every(r, false, true, core) { it == 0 }, "the box is not in RIGHT's shadow before the write")
            if (input) r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
            else { r.rows[0].big = true; r.shell.services.requestRender(r.rows[0]) }
            if (at == At.OWED) {
                r.until("the box painted behind the owed out half") { r.onLoop { r.rows[0].big && r.shell.comp.hasPending } }
                assertEquals("OWED", r.onLoop { r.shell.transitionPhaseForTest() }, "still owed when the write is painted")
                // the link has room again: the out PLAY, then the frame with the box in it, held
                r.shell.pumpHeldForTest = false; r.shell.postIdleTick()
            }
            val steps = Regex("(\\d+) steps a half").find(r.notes("transition").first { it.startsWith("owed: ") })!!.groupValues[1].toInt()
            val d0 = Arm.entries.associateWith { r.sim.motionForTest(it).slots[3]?.d ?: 0 }
            val shown = shownByTheNextStep(r, core, openBefore = at == At.IN, steps = steps, release = {
                if (at == At.FRAME) { r.shell.transitionHoldInHalfForTest = false; r.shell.postIdleTick() }
            })
            // the measured form: OWED and FRAME at the in half's first step; IN and the repaint at the step after the write
            if (at != At.IN) assertEquals(mapOf(Arm.LEFT to 1, Arm.RIGHT to 1), shown, "shown at the in half's first step")
            else assertTrue(shown.values.all { it in 2..steps } && d0.values.all { it >= 1 }, "shown at a step after the one drawn when the write went: $shown (drawn then: $d0)")
            r.until("the transition ends") { r.onLoop { r.shell.transitionPhaseForTest() } == null }
            r.until("the CLOSE of slot 2 reaches RIGHT") { right(r).any { img -> subs(img).let { it.size == 1 && closeOf(it[0], 2) } } }
            r.settle("after")
            r.recording = false
            bothHalvesWhole(r)
            noCloseUnderTheRun(r)
            assertTrue(r.notes("transition").any { it.startsWith("in half's last step drawn on RIGHT") }, "${r.notes("transition")}")
            assertTrue(r.notes("transition").none { "ended in" in it }, "the transition was not cut: ${r.notes("transition")}")
            r.assertOracle("at rest after the write in $at")
            assertTrue(every(r, true, false, core) { it == 15 } && every(r, false, false, core) { it == 15 }, "the box at full level on both lenses at rest")
            println("X2P2 ${if (input) "input" else "repaint"} in $at: shown at ${shown.entries.joinToString { "${it.key} step ${it.value}" }}")
            return shown
        } finally { r.close() }
    }

    @Test
    fun anInputWhileTheOutHalfIsOwedShowsAtTheInHalf_X2P2(): Unit = runBlocking { writeDuring(At.OWED) }

    @Test
    fun anInputWhileTheFrameGoesInShowsAtTheInHalf_X2P2(): Unit = runBlocking { writeDuring(At.FRAME) }

    @Test
    fun anInputDuringTheInHalfShowsAtItsNextStep_X2P2(): Unit = runBlocking { writeDuring(At.IN) }

    /** X2P2 item 1 (was DP2's "a write that is not his input waits for the in half"): the window's own repaint during the
     *  in half is held by both owners and shows at the in half's next step; the transition plays in full. */
    @Test
    fun aWriteThatIsNotInputIsHeldAndShownAtTheNextStep_DP2(): Unit = runBlocking { writeDuring(At.IN, input = false) }

    // ------------------------------------- X2P2 stage 2 item 1, as C3 X2M0 rules it: a wrap notch under a transition
    /**
     * Decisions AZ (3): the case he will meet. A Back into Main from w0 (Main's first row) at height 480 with twelve
     * windows — Main's paint wraps there, so at stage 5 its list is bound as a ring list (LIST-RING) — then a notch past
     * row 0 while the Back's transition is in hand: in its frame phase ([frame]: Main's frame in several flushes, the in
     * half held back meanwhile) or inside its in half (RIGHT's in half drawing). The ring fire is refused 19 on RIGHT
     * (a transition's owner in its close set, `FIRMWARE.md` §7.14) and the phone applies the notch as today (the DISCARD
     * naming the wrapped row); no PLAY in slot 1 from the Back's out PLAY to its run's end, both halves drew their last
     * step, the wrapped row on both lenses at the in half's LAND, belief equals both lenses at rest. After the run the list
     * still rings: the next notch past the end is the glasses' own wrap (field 18), with no PLAY in slot 1.
     */
    private suspend fun wrapNotchUnderABack(frame: Boolean) {
        val r = Rig(stage = 5, nRows = 12)
        try {
            r.up { it.copy(heightMode = 480) }
            assertTrue(r.onLoop { r.shell.mainBinding.staging?.ring == true }, "Main's list is staged as a ring list at 480 with twelve windows")
            selectAndSettle(r)
            val count = r.onLoop { r.shell.mainBoundForTest().first }?.count ?: r.rows.size
            val batchMax = r.onLoop { r.shell.comp.batchMax }
            r.recording = true
            // Main's frame in several flushes: the batch cap lowered for the Back's frame only (an op larger than the cap could
            // not go at all), put back before the notch
            if (frame) { r.shell.transitionHoldInHalfForTest = true; r.onLoop { r.shell.comp.batchMax = 400 } }
            r.doubleTap()
            r.until("back on Main") { r.onLoop { r.shell.currentWindowId() } == null }
            if (frame) r.until("Main's frame in, the in half held back") { r.onLoop { r.shell.transitionPhaseForTest() == "FRAME" && !r.shell.comp.hasPending } }
            else r.until("RIGHT's in half drawing") { r.onLoop { r.shell.transitionPhaseForTest() } == "IN" &&
                r.sim.motionForTest(Arm.RIGHT).slots[3]?.let { it.drawing && it.d >= 1 } == true }
            r.until("Main's list bound on RIGHT") { r.sim.bindForTest(Arm.RIGHT).field39() == 0x31 }
            assertEquals(0, r.row(), "back on Main's first row")
            for (a in Arm.entries) assertTrue(r.sim.bindForTest(a).lists[MainStaging.LIST].ring, "$a: the list as bound rings")
            if (frame) {
                val imgs = right(r)
                val out = imgs.indexOfFirst { img -> subs(img).any { playIn(it, 2) } }
                val frameImgs = imgs.drop(out + 1).filter { img -> subs(img).any { drawing(it) } }
                assertTrue(frameImgs.size >= 2, "Main's frame took more than one flush: ${frameImgs.map { it.size }} B")
                println("X2M0: Main's frame under the Back in ${frameImgs.size} flushes, ${frameImgs.map { it.size }} B")
                r.onLoop { r.shell.comp.batchMax = batchMax }
                for (left in listOf(true, false)) againstTruth(r, left, shadow = true, area = r.onLoop { r.shell.layout.content })?.let { fail("Main's frame whole in the shadow before the notch: $it") }
            }
            val applied = r.notes("applied").size
            val repairs = r.notes("repair").size
            r.fire(CfwModes.EV_DOWNROLL)
            r.until("the notch applied as today: the row is Main's last") { r.row() == count - 1 }
            r.until("RIGHT's report of the notch in the journal") { r.notes("applied").size > applied }
            r.notes("applied").drop(applied).let { a -> assertTrue(a.size == 1 && a[0].contains("refused (fire-over-owner)") && !a[0].contains("wrapped"),
                "RIGHT refused the ring fire 19 under the transition's owner: $a") }
            // the notch's repaint (held) whole in both shadows, the run still in hand and its in half still to land
            val content = r.onLoop { r.shell.layout.content }
            r.until("the wrapped row in both shadows") {
                r.onLoop { !r.shell.comp.hasPending && "slides " !in r.shell.quiescenceReport() && "inflight=" !in r.shell.quiescenceReport() } &&
                    listOf(true, false).all { againstTruth(r, it, shadow = true, area = content) == null } }
            if (frame) {
                assertEquals("FRAME", r.onLoop { r.shell.transitionPhaseForTest() }, "still the frame phase when the wrapped row is in the shadow")
                r.shell.transitionHoldInHalfForTest = false; r.shell.postIdleTick()
            } else {
                for (a in Arm.entries) assertTrue(r.sim.motionForTest(a).slots[3] != null, "$a: the in half's slot was emptied — closed — under the notch")
                assertTrue(Arm.entries.all { r.sim.motionForTest(it).slots[3]?.drawing == true },
                    "precondition: both in halves still drawing when the wrapped row was in the shadow (a run too loaded to exercise the hold)")
            }
            // C3 X3M0 (decisions BB (4)): landed is the half's own T — an emptied slot or a done one short of T is not
            r.until("both lenses' in halves landed") { Arm.entries.all { r.sim.motionForTest(it).slots[3]?.let { s -> !s.drawing && s.d == s.T } == true } }
            delay(20)
            for (left in listOf(true, false)) againstTruth(r, left, shadow = false, area = content)?.let { fail("the wrapped row at the in half's LAND: $it") }
            r.until("the Back's run ends") { r.onLoop { r.shell.transitionPhaseForTest() } == null }
            r.until("the Back's CLOSE of slot 2 reaches RIGHT") { right(r).count { img -> subs(img).let { it.size == 1 && closeOf(it[0], 2) } } >= 1 }
            r.settle("after the Back")
            noCloseUnderTheRun(r, act = 0, alsoNot = { playIn(it, MainStaging.WRAP_SLOT) })
            bothHalvesWhole(r, act = 1)
            r.assertOracle("at rest after the wrap notch under the Back")
            // C3 X3M0 (decisions BB (2)): a fire refused 19 is no evidence for the table repair — no SET, no `repair` note
            assertEquals(emptyList(), r.notes("repair").drop(repairs), "no repair for the notch refused 19 under the Back")
            // after the run the list still rings: the next notch past the end is the glasses' own wrap, no PLAY in slot 1
            val at = right(r).size
            val after = r.notes("applied").size
            r.fire(CfwModes.EV_UPROLL)
            r.until("the row is Main's first again") { r.row() == 0 }
            r.until("RIGHT's report of the wrap in the journal") { r.notes("applied").size > after }
            r.settle("after the wrap")
            r.recording = false
            r.notes("applied").drop(after).let { a -> assertTrue(a.size == 1 && a[0].contains(" drew ") && a[0].contains("wrapped"), "the glasses' own wrap (field 18): $a") }
            assertTrue(right(r).drop(at).none { img -> subs(img).any { playIn(it, MainStaging.WRAP_SLOT) } }, "no PLAY in slot ${MainStaging.WRAP_SLOT} for the wrap")
            r.assertOracle("after the wrap")
        } finally { r.close() }
    }

    @Test
    fun aWrapNotchInABacksFramePhaseIsRefused19AndAppliedAsToday_X2M0(): Unit = runBlocking { wrapNotchUnderABack(frame = true) }

    @Test
    fun aWrapNotchInABacksInHalfIsRefused19AndAppliedAsToday_X2M0(): Unit = runBlocking { wrapNotchUnderABack(frame = false) }

    // --------------------------------------------- X2P2 stage 2 item 2: a refused in half whose body comes first
    /** In RIGHT's copy of a batch: the PLAY-AFTER's period made 0 (§5.5's check order: refused 12), in place, before RIGHT
     *  handles it. True when it held one. */
    private fun refusePlayAfter(img: ByteArray): Boolean {
        if (img[0].toInt() != 8) return false
        var i = 2
        repeat(img[1].toInt() and 0xFF) {
            val n = u16(img, i)
            if (n >= 4 && img[i + 2].toInt() == 26 && img[i + 3].toInt() == CfwModes.MOTION_PLAY_AFTER) { img[i + 5] = 0; return true }
            i += 2 + n
        }
        return false
    }

    /**
     * The second review pass's finding 2, ruled: RIGHT refuses the in half's PLAY-AFTER (riding the frame's batch), LEFT
     * takes it, and RIGHT's outcome-2 body comes before the batch's failed ack. The run ends on the body with both slots
     * CLOSEd in the next flush — the out half's far pose does not wait for anything else; the arriving frame is whole on
     * both lenses; the run sends no keyframe (0.83 integration: the engine-refusal gate's repair at RIGHT's next read sends
     * one, decisions BS.3 and BU A2).
     */
    @Test
    fun aRefusedInHalfWhoseBodyComesFirstClosesBothSlots_X2P2(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.up()
            val refused = java.util.concurrent.atomic.AtomicBoolean(false)
            r.onImage = { a, img -> if (a == Arm.RIGHT && !refused.get() && refusePlayAfter(img)) refused.set(true) }
            r.recording = true
            r.tap()
            r.until("w0 opens") { r.onLoop { r.shell.currentWindowId() } == "w0" }
            r.until("the run ends") { r.onLoop { r.shell.transitionPhaseForTest() } == null }
            r.until("CLOSE 2 and CLOSE 3 reach RIGHT") { right(r).any { img -> subs(img).let { s -> s.size == 2 && closeOf(s[0], 2) && closeOf(s[1], 3) } } }
            // 0.83 integration (RR's gate meets this row): RIGHT's next read names the refused mode 26 — decided before the look
            r.until("RIGHT's next read decided the refusal") { r.notes("local").any { "the glasses refused a mode 26" in it } }
            r.settle("after")
            r.recording = false
            assertTrue(refused.get(), "RIGHT's copy of the PLAY-AFTER was made refusable")
            assertTrue(r.sim.motionForTest(Arm.LEFT).slots[3] == null && r.sim.motionForTest(Arm.RIGHT).slots[3] == null, "slot 3 empty on both lenses")
            assertTrue(r.notes("motion").any { "slot 3 " in it && "refused" in it }, "RIGHT's refusal body: ${r.notes("motion").takeLast(4)}")
            assertTrue(r.notes("transition").any { "its in half ended on RIGHT with outcome 2" in it }, "the body ended the run (it came before the ack): ${r.notes("transition")}")
            val imgs = right(r)
            val pa = imgs.indexOfFirst { img -> subs(img).any { playAfter(it) } }
            // the next image after the refused one that is not a cache write (the atlas's idle chunk may slip between)
            val next = imgs.drop(pa + 1).map { subs(it) }.first { s -> s.none { (it[0].toInt() and 0x7F) in setOf(12, 19, 25) } }
            assertTrue(next.size == 2 && closeOf(next[0], 2) && closeOf(next[1], 3), "the next image after the refused one: CLOSE 2 and CLOSE 3 alone, not ${next.map { "${it[0]}/${it[1]}" }}")
            // 0.83 integration, a test line where two lanes met (decisions BS.3/BU A2): the run itself sends no keyframe; the
            // refusal record naming 26 at RIGHT's next read, with the out PLAY's batch (its answers behind the PLAY) and the
            // CLOSEs' batch in the gate's span, is repaired as any is — the whole table and ONE keyframe, after the CLOSEs
            val closes = imgs.indexOfFirst { img -> subs(img).let { s -> s.size == 2 && closeOf(s[0], 2) && closeOf(s[1], 3) } }
            val kfs = imgs.withIndex().filter { (i, img) -> i > pa && subs(img).any { (it[0].toInt() and 0x7F) == 6 } }.map { it.index }
            assertTrue(kfs.size == 1 && kfs.single() > closes, "the gate's one keyframe after the CLOSEs ($closes), none of the run's: $kfs")
            assertTrue(Files.readAllLines(r.journal).any { "\"kind\":\"repair\"" in it && "evidence=refusal mode=26" in it && "keyframe=yes" in it },
                "the keyframe is the gate's repair: ${r.notes("repair")}")
            for (left in listOf(true, false)) againstTruth(r, left, shadow = false)?.let { fail("the arriving frame whole: $it") }
            r.assertOracle("after the refused in half")
        } finally { r.close() }
    }

    // ------------------------------------------------------------- X2P2 item 2: the silent clock right after a notch
    /** The silent clock entered by a double tap as soon as an adopted notch on Main is COMMITted and Main's cells
     *  behind it are held (its motion ≈ 270 ms at the defaults; the review's case is a double tap within 100 ms).
     *  Returns the moment the held cells' wait would have ended and RIGHT's truth (Main's) just before the double tap. */
    private suspend fun lockRightAfterANotch(r: Rig): Pair<Long, ByteArray> {
        // the notch must fire on the glasses (a fire RIGHT's table did not hold yet is drawn by the phone as a stock
        // scroll, and holds nothing): its up, down and tap entries on RIGHT, as MainLocalShellTest's `bound` waits
        r.until("Main's list bound on RIGHT") { r.sim.bindForTest(Arm.RIGHT).field39() == 0x31 }
        r.fire(CfwModes.EV_UPROLL)
        r.until("adopted") { r.row() == 1 }
        r.until("Main's cells held behind the notch's motion", 2_000) { r.onLoop { r.shell.comp.deferring } }
        val until = r.onLoop { r.shell.comp.deferUntilMs }
        assertTrue(System.currentTimeMillis() < until - 100, "the double tap falls inside the wait (${until - System.currentTimeMillis()} ms left)")
        val main = r.onLoop { r.shell.comp.truthForTest(false).pix.copyOf() }
        r.doubleTap()
        return until to main
    }

    /** The shell's truth against one lens's [shadow] or panel over [area] (the whole panel by default), every pixel; the
     *  first that differs, or null. */
    private fun againstTruth(r: Rig, left: Boolean, shadow: Boolean,
                             area: wm.damage.core.geom.Rect = wm.damage.core.geom.Rect(0, 0, Geometry.PANEL_W, Geometry.PANEL_H)): String? {
        val truth = r.onLoop { r.shell.comp.truthForTest(left) }
        val ctx = if (left) r.sim.left else r.sim.right
        val buf = if (shadow) ctx.shadow else ctx.panel
        for (y in area.y until area.bottom) for (x in area.x until area.right) {
            val b = buf[y * ctx.stride + (x shr 1)].toInt() and 0xFF
            val v = if (x and 1 == 0) b shr 4 else b and 0x0F
            if (v != wm.damage.core.gfx.Pack.level(truth[x, y])) return "${if (left) "LEFT" else "RIGHT"} ${if (shadow) "shadow" else "panel"} differs from the clock's truth at ($x,$y): $v vs ${wm.damage.core.gfx.Pack.level(truth[x, y])}"
        }
        return null
    }

    /**
     * X2P2 item 2 (the first review pass's finding 1): at stage 5 the Lock entry right after a notch — the transition's
     * out PLAY ends the adopted motion, so Main's held cells go with the clock's frame: when the PLAY-AFTER has been taken
     * (the frame complete in the shadow, §7.11) both shadows are the clock's truth, Main's rows nowhere; RIGHT's panel at
     * the in half's LAND is the clock's truth; no lone CLOSE of the list's slot goes ahead of the out PLAY (X2P2 item
     * 3(a)); at rest belief equals both lenses.
     */
    @Test
    fun theSilentClockRightAfterANotchLandsWhole_X2P2(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.up()
            r.recording = true
            lockRightAfterANotch(r)
            r.until("the clock's in half taken on both lenses") { Arm.entries.all { r.sim.motionForTest(it).slots[3] != null } }
            for (left in listOf(true, false)) againstTruth(r, left, shadow = true)?.let { fail("with the in half taken: $it") }
            r.until("RIGHT's in half landed") { r.sim.motionForTest(Arm.RIGHT).slots[3]?.drawing == false }
            delay(20)
            againstTruth(r, false, shadow = false)?.let { fail("at the in half's LAND: $it") }
            r.until("the transition ends") { r.onLoop { r.shell.transitionPhaseForTest() } == null }
            r.settle("on the clock")
            r.recording = false
            val imgs = right(r)
            val out = imgs.indexOfFirst { img -> subs(img).any { playIn(it, 2) } }
            assertTrue(out >= 0 && imgs.subList(0, out).none { img -> subs(img).let { it.size == 1 && closeOf(it[0], 0) } }, "no lone CLOSE of slot 0 ahead of the out PLAY")
            assertTrue(r.notes("transition").any { "the cells held behind an adopted motion go with the frame" in it }, "${r.notes("transition")}")
            r.assertOracle("on the clock")
        } finally { r.close() }
    }

    /**
     * X2P2 item 2's class at stage 4 (no transition): the silent clock covers the whole list as a window does (decisions
     * AW.3), so a CLOSE of the slot goes alone ahead of its frame and Main's held cells go with it — RIGHT's panel is the
     * clock's truth well before the notch's motion would have ended; at rest belief equals both lenses.
     */
    @Test
    fun theSilentClockRightAfterANotchLandsWholeAtStage4_X2P2(): Unit = runBlocking {
        val r = Rig(stage = 4)
        try {
            r.up()
            r.recording = true
            val (until, main) = lockRightAfterANotch(r)
            r.until("RIGHT's panel is the clock's truth") { !r.onLoop { r.shell.comp.truthForTest(false).pix.contentEquals(main) } && againstTruth(r, false, shadow = false) == null }
            val at = System.currentTimeMillis()
            assertTrue(at < until, "the clock landed whole ${until - at} ms before the notch's motion would have ended (not at its end)")
            r.settle("on the clock")
            r.recording = false
            assertTrue(right(r).any { img -> subs(img).let { it.size == 1 && closeOf(it[0], 0) } }, "a lone CLOSE of slot 0 ahead of the clock's frame")
            assertTrue(r.notes("local").any { it.startsWith("the silent clock over the list") }, "${r.notes("local").takeLast(3)}")
            assertTrue(r.notes("transition").isEmpty(), "no transition at stage 4")
            r.assertOracle("on the clock")
        } finally { r.close() }
    }

    /**
     * D-4 (ruled; `FIRMWARE.md` §7.11 "an owned rect stays out of the phone's agreement scan while its mark stands"): the
     * in half held back (a test seam), the out half plays to done on RIGHT (notified, owning, its step T on the panel);
     * the window then repaints three times beneath it — each a held flush whose completion runs the agreement scan with
     * nothing pending — and no divergence is reported; let go, the in half lands and belief equals both lenses.
     */
    @Test
    fun aDoneOutHalfStaysOutOfTheAgreementScanWhileItOwns_DP2(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.up()
            r.shell.transitionHoldInHalfForTest = true
            r.recording = true
            r.tap()
            r.until("the out half done and still owning on RIGHT") {
                r.onLoop { r.shell.transitionPhaseForTest() } == "FRAME" && r.sim.motionForTest(Arm.RIGHT).slots[2]?.let { !it.drawing && it.owns } == true }
            val before = r.shell.divergencesReported
            for (m in 1..3) {
                val drawn = right(r).count { img -> subs(img).any { drawing(it) } }
                r.rows[0].mark = m
                r.shell.services.requestRender(r.rows[0])
                r.until("repaint $m reaches RIGHT") { right(r).count { img -> subs(img).any { drawing(it) } } > drawn }
                delay(150)                                   // its completion handled: the scan has run
                assertTrue(r.sim.motionForTest(Arm.RIGHT).slots[2]?.owns == true, "repaint $m: the out half still owns (held, not closed)")
            }
            assertEquals(before, r.shell.divergencesReported, "no divergence while the done out half owns: ${r.notes("divergence")}")
            r.shell.transitionHoldInHalfForTest = false
            r.rows[0].mark = 4
            r.shell.services.requestRender(r.rows[0])
            r.until("the transition ends") { r.onLoop { r.shell.transitionPhaseForTest() } == null }
            r.settle("after")
            r.recording = false
            assertTrue(r.notes("transition").any { it.startsWith("in half's last step drawn on RIGHT") }, "${r.notes("transition")}")
            assertEquals(before, r.shell.divergencesReported, "no divergence at all: ${r.notes("divergence")}")
            r.assertOracle("after the in half")
        } finally { r.close() }
    }

    /** DP2 items 1 and 6: a frame of several flushes — every one held (no CLOSE of slot 2), the PLAY-AFTER on the last
     *  only (no image of the frame after it before the CLOSE that ends the transition; the chrome's idle tick may fall
     *  there), and nothing of the frame on RIGHT's panel when the PLAY-AFTER reaches it. */
    @Test
    fun aSeveralFlushFrameShowsNothingBeforeItsInHalf_DP2(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.up()
            r.rows[0].noise = true
            // a frame splits by bytes only past the batch's cap (a whole panel); the compositor's cap lowered for the test
            r.onLoop { r.shell.comp.batchMax = 1500 }
            var panelAtPlayAfter: Gray8? = null
            r.onImage = { a, img -> if (a == Arm.RIGHT && panelAtPlayAfter == null && subs(img).any { playAfter(it) }) panelAtPlayAfter = r.panel(false) }
            r.recording = true
            selectAndSettle(r)
            r.until("the CLOSE of slot 2 reaches RIGHT") { right(r).any { img -> subs(img).any { closeOf(it, 2) } } }
            r.recording = false
            val imgs = right(r)
            val out = imgs.indexOfFirst { img -> subs(img).any { playIn(it, 2) } }
            val pa = imgs.indexOfFirst { img -> subs(img).any { playAfter(it) } }
            val close = imgs.indexOfFirst { img -> subs(img).any { closeOf(it, 2) } }
            // the frame: every drawing image between the out PLAY and the CLOSE that ends the transition (the chrome's idle
            // tick, which no longer waits for the in half, is not the frame)
            val frame = (out + 1 until close).filter { i -> subs(imgs[i]).any { drawing(it) } && !chromeTick(imgs[i]) }
            assertTrue(frame.size >= 2, "the frame took ${frame.size} flush(es) — the test needs several")
            // after every image of the frame: in its last flush's batch after the drawing subs, or alone right after it (when
            // the compositor's pending damage turned out empty at the next assemble, `transitionAlone`)
            val last = frame.last()
            assertTrue(pa >= last, "the PLAY-AFTER after the frame's last drawing image (PLAY-AFTER $pa, the frame's images $frame)")
            val paSubs = subs(imgs[pa])
            if (pa == last) assertTrue(paSubs.indexOfFirst { playAfter(it) } > paSubs.indexOfLast { drawing(it) }, "after its drawing subs")
            assertTrue(imgs.subList(out, close).none { img -> subs(img).any { closeOf(it, 2) || closeOf(it, 3) || closeOf(it, 0xFF) } }, "every frame flush held, and nothing after it closes an owner")
            assertTrue(close > pa)
            r.assertOracle("after")
            val seen = assertNotNull(panelAtPlayAfter)
            val final = r.panel(false)
            val c = r.onLoop { r.shell.layout.content }
            var same = 0
            for (y in c.y until c.bottom) for (x in c.x until c.right) if (seen[x, y] == final[x, y] && final[x, y] != 0) same++
            assertTrue(same < c.w * c.h / 20, "the frame's busy picture was not on RIGHT's panel before the in half ($same px of it already there)")
        } finally { r.close() }
    }

    // ------------------------------------------------------------------------------------------ C3 DM-T: Encode and Matrix
    /** Every tier set to [ids] only. */
    private fun only(vararg ids: String): (wm.damage.core.shell.ShellSettings) -> wm.damage.core.shell.ShellSettings =
        { s -> Tier.entries.fold(s) { a, t -> a.withTier(t, effects = ids.toList()) } }

    /** A cache write's entries as (byte offset, length) — mode 19, or 25 inflated; empty for any other image. */
    private fun cacheWrites(img: ByteArray): List<Pair<Int, Int>> {
        val list = when (img[0].toInt() and 0x7F) {
            19 -> img.copyOfRange(1, img.size)
            25 -> wm.damage.core.gfx.Zl.inflate(img.copyOfRange(1, img.size), CfwModes.ZWRITE_MAX)
            else -> return emptyList()
        }
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i + 4 <= list.size) { val n = u16(list, i + 2); out += u16(list, i) * 4 to n; i += 4 + n }
        return out
    }

    /** When the shell's journal wrote each `transition` note starting with [prefix] (ms, the journal's own clock). */
    private fun noteTimes(r: Rig, prefix: String): List<Long> = Files.readAllLines(r.journal)
        .filter { "\"ev\":\"note\",\"kind\":\"transition\"" in it && "\"detail\":\"$prefix" in it }
        .map { Regex("\"t\":(\\d+)").find(it)!!.groupValues[1].toLong() }

    private suspend fun runEnds(r: Rig, what: String) = r.until(what) { r.onLoop { r.shell.transitionPhaseForTest() } == null }

    /** The in half's notes: drawn + folded of the last body whose note was written. */
    private fun inHalfWhole(r: Rig): Boolean = r.notes("transition").any { it.startsWith("in half's last step drawn on RIGHT") }

    /**
     * Stage 2: Matrix as a transition — Select, Back (Select reversed: the same rain, S1) and the silent clock both ways,
     * each its records in one write into the act's slot (programs, a rain record, an alphabet — C3 MX2), the out PLAY, the frame
     * held, the PLAY-AFTER, RIGHT's in half drawn to its last step, and belief equal to both lenses after each.
     */
    @Test
    fun matrixIsATransitionOfEveryTier_DMT(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.up(only("matrix"))
            r.recording = true
            selectAndSettle(r); r.assertOracle("after the Select")
            r.doubleTap(); r.until("back on Main") { r.onLoop { r.shell.currentWindowId() } == null }; runEnds(r, "the Back ends"); r.assertOracle("after the Back")
            r.doubleTap(); r.until("the clock's ends") { r.notes("transition").count { it.startsWith("owed: Lock") } == 1 && r.onLoop { r.shell.transitionPhaseForTest() } == null }
            r.assertOracle("on the clock")
            r.doubleTap(); r.until("Main's ends") { r.notes("transition").count { it.startsWith("owed: Lock") } == 2 && r.onLoop { r.shell.transitionPhaseForTest() } == null }
            r.assertOracle("Main again")
            r.recording = false
            val owed = r.notes("transition").filter { it.startsWith("owed: ") }.map { it.removePrefix("owed: ").substringBefore(" over") }
            assertEquals(listOf("Select Matrix", "Back Matrix reversed", "Lock Matrix", "Lock Matrix reversed"), owed, "${r.notes("transition")}")
            assertEquals(4, r.notes("transition").count { it.startsWith("in half's last step drawn on RIGHT") }, "${r.notes("transition")}")
            assertTrue(r.notes("transition").none { "left out" in it || "refused" in it }, "${r.notes("transition")}")
            val region = r.t.state.value.transitionRegion!!
            // each act's records: ONE write, inside the slot (no list anywhere else)
            for (img in right(r)) { val w = cacheWrites(img); if (w.any { it.first in region }) assertTrue(w.all { it.first in region && it.first + it.second <= region.last + 1 }, "Matrix's records stay in the slot: $w") }
            println("DM-T Matrix: ${r.notes("transition").filter { it.startsWith("owed") }.joinToString("\n")}")
            println("DM-T Matrix, RIGHT's images (four acts): " + right(r).joinToString("; ") { img ->
                "${img.size} B [" + subs(img).joinToString(" ") { "${it[0].toInt() and 0x7F}/${if ((it[0].toInt() and 0x7F) in setOf(26, 27)) it[1].toInt() else it.size}" } + "]" })
        } finally { r.close() }
    }

    /** C3 X1MX2: the programs of the act [act] makes — run with the link held (the transition stays owed), its programs
     *  read, then the link let go and the run waited out. */
    private suspend fun builtOf(r: Rig, what: String, act: () -> Unit): Transitions.Built {
        r.shell.pumpHeldForTest = true
        act()
        r.until("$what owed") { r.onLoop { r.shell.transitionPhaseForTest() } == "OWED" }
        val b = assertNotNull(r.onLoop { r.shell.transitionBuiltForTest() }, "$what: the run's programs")
        r.shell.pumpHeldForTest = false; r.shell.postIdleTick()
        runEnds(r, "$what ends")
        return b
    }

    /**
     * C3 X1MX2 item 2 (decisions CB (2)): Matrix's two halves share ONE rain grid — the hand-over between them depends on
     * it — at the nearer of the two screens' content planes. Global `Depth` 8 with w0's own Depth row at 4: the Select
     * (Main 8 → w0 4) and the Back (w0 4 → Main 8) at 4; with w0's row at 12 both at Main's 8; the silent clock both ways
     * at 0, one rect in both halves. The Depth effect still reads Global `Depth` (Global 12, w0 at 4: its push back 4 px,
     * its bytes as before).
     */
    @Test
    fun matrixsTwoHalvesShareOneGridAtTheNearerScreensPlane_X1MX2(): Unit = runBlocking {
        for ((app, want) in listOf(4 to listOf(4, 4, 0, 0), 12 to listOf(8, 8, 0, 0))) {
            val r = Rig(stage = 5)
            try {
                r.up({ s -> only("matrix")(s).copy(depth = 8).withAppStyle("w0") { it.copy(depth = app) } })
                val acts = listOf(
                    builtOf(r, "the Select") { r.tap() }.also { assertEquals("w0", r.onLoop { r.shell.currentWindowId() }) },
                    builtOf(r, "the Back") { r.doubleTap() }.also { assertEquals(null, r.onLoop { r.shell.currentWindowId() }) },
                    builtOf(r, "the clock") { r.doubleTap() },
                    builtOf(r, "leaving the clock") { r.doubleTap() })
                for ((i, b) in acts.withIndex()) {
                    val at = "w0 at $app, act ${i + 1} (${b.describe().substringBefore(" over")})"
                    val grids = listOf(b.out, b.inn).map { p ->
                        val track = p.tracks.single { t -> t.segments.any { it.verb == wm.damage.core.motion.Motion.GLYPHS } }
                        val seg = track.segments.single()
                        listOf(seg.xL, if (track.perLens) seg.xR else seg.xL, if (track.perLens) 1 else 0, seg.h0, seg.rain!!.cols, seg.rain!!.cw, track.areaL.h)
                    }
                    assertEquals(grids[0], grids[1], "$at: the out half's drops and the in half's rain on one grid")
                    val (xL, xR, pair) = grids[0]
                    val d = want[i]
                    assertEquals(2 * d, xR - xL, "$at: the grid at the plane ${d}, the nearer screen's")
                    assertEquals(if (d != 0) 1 else 0, pair, "$at: a pair only where the lenses differ")
                    assertEquals(0, (-d - xL) % grids[0][5], "$at: moved by whole columns")
                }
                r.assertOracle("w0 at $app, after the four acts")
            } finally { r.close() }
        }
        val r = Rig(stage = 5)
        try {
            r.up({ s -> only("depth")(s).copy(depth = 12).withAppStyle("w0") { it.copy(depth = 4) } })
            val seg = builtOf(r, "the Depth Select") { r.tap() }.out.tracks.single().segments.single()
            assertEquals(-4 to 4, seg.dxL to seg.dxR, "the Depth effect pushes back by Global's 4 px, not the nearer plane's 8")
        } finally { r.close() }
    }

    /**
     * Stage 3 and C3 X2T items 2 and 4: an Encode Select on a 256 KiB session with BIND — RIGHT's images: the records' write
     * (the slot's programs, the out lists and, above them, the in lists in the stage reserve: ONE message, they fit one
     * compressed message), the out PLAY (the act's first visible image, journaled as the gesture's first flush), the frame
     * with the PLAY-AFTER, the CLOSE of slot 2 after RIGHT's in-half body; the other cache writes held from the act to one
     * in-half period past that body; two screens recorded (the counter in `screenRuns()`); belief equals both lenses.
     */
    @Test
    fun anEncodeSelectWritesBothHalvesListsWithItsRecordsAndRunsWhole_X2T(): Unit = runBlocking {
        val r = Rig(stage = 5, kib256 = true)
        try {
            r.rows[0].labels = listOf("Window zero" to wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM, 18))
            r.up(only("encode"))
            val st = r.t.state.value
            assertTrue(st.cacheSize == CfwModes.CACHE2_MAX_RIDERS && st.bind, "a 256 KiB session with BIND: ${st.cacheSize} ${st.bind}")
            val reserve = (st.cacheSize - CfwModes.stageReserve(st.cacheSize, st.bind)) until st.cacheSize
            val lines0 = Files.readAllLines(r.journal).size
            val runs0 = r.onLoop { r.shell.screenRunsForTest() }
            // FX-083 T1: what the idle lanes held when the recording began (read on the loop, one evaluation)
            val lanes0 = r.onLoop { "notice lane done ${r.shell.noticeLaneDoneForTest()}, programs ${r.shell.noticeProgramsForTest()}, cache work in flight ${r.shell.cacheWorkInFlight}" }
            r.shell.transitionHoldInHalfForTest = true
            r.recording = true
            r.tap()
            r.until("the frame phase") { r.onLoop { r.shell.transitionPhaseForTest() } == "FRAME" }
            assertTrue(r.onLoop { r.shell.cacheWritesHeldForTest() }, "the other cache writes are held during the run")
            r.shell.transitionHoldInHalfForTest = false
            r.shell.postIdleTick()
            runEnds(r, "the Encode Select ends")
            r.until("the CLOSE of slot 2 reaches RIGHT") { right(r).any { img -> subs(img).any { closeOf(it, 2) } } }
            r.settle("after"); r.recording = false
            r.until("the hold released after its period") { !r.onLoop { r.shell.cacheWritesHeldForTest() } }
            assertEquals(runs0 + 2, r.onLoop { r.shell.screenRunsForTest() }, "an Encode act records two screens: the leaving one and the arriving one")
            val imgs = right(r)
            val region = st.transitionRegion!!
            val write = imgs.indexOfFirst { img -> cacheWrites(img).any { it.first in region } }
            val out = imgs.indexOfFirst { img -> subs(img).any { playIn(it, 2) } }
            val lists = imgs.indexOfFirst { img -> cacheWrites(img).let { w -> w.isNotEmpty() && w.all { it.first in reserve } } }
            val pa = imgs.indexOfFirst { img -> subs(img).any { playAfter(it) } }
            // FX-083 T1: when it fails, say what the image ahead held — its writes, the slice each lies in — and the journal since
            val notice = CfwModes.noticeRegion(st.cacheSize, st.bind)
            fun slice(off: Int) = when {
                notice != null && off in notice.first until notice.first + CfwModes.NOTICE_PROGRAMS -> "the notice's programs (slot ${(off - notice.first) / wm.damage.core.motion.PopupPrograms.SLOT_BYTES})"
                notice != null && off in notice -> "the notice's capture"
                off in reserve -> "the stage reserve below the notice slice"
                off in region -> "the transitions' region"
                else -> "elsewhere"
            }
            fun said(i: Int) = if (i < 0) "" else " — image $i: " + cacheWrites(imgs[i]).joinToString { (o, n) -> "$o+$n (${slice(o)})" } +
                "; at the recording's start: $lanes0; the journal since: " + Files.readAllLines(r.journal).drop(lines0)
                    .filter { "\"ev\":\"note\"" in it && Regex("\"kind\":\"(notice|stage|atlas|mainstage)\"").containsMatchIn(it) }.take(14)
                    .joinToString(" | ") { it.substringAfter("\"kind\":\"").substringBefore("\"") + ": " + it.substringAfter("\"detail\":\"").substringBefore("\"").take(150) }
            assertTrue(write in 0 until out && out < pa && lists == -1, "records $write, out PLAY $out, PLAY-AFTER $pa, no list write of its own ($lists)${said(lists)}")
            val w = cacheWrites(imgs[write])
            assertTrue(w.size == 3 && w[0].first in region && w[1].first in reserve && w[2].first == w[1].first + w[1].second,
                "the records' write: the slot, the out lists and the in lists right above them, one message: $w")
            assertEquals(CfwModes.ZWRITE_MODE, imgs[write][0].toInt() and 0x7F, "the records' write went compressed (mode 25)")
            assertTrue(inHalfWhole(r), "${r.notes("transition")}")
            assertTrue(r.notes("transition").any { it.startsWith("owed: Select Encode") }, "${r.notes("transition")}")
            // the first image after the tap is priced as DP2's: the out PLAY's flush is the gesture's first in the journal
            val outFlush = Regex("then the out PLAY as flush (\\d+)").find(r.notes("transition").joinToString("\n"))!!.groupValues[1]
            val firstIn = Files.readAllLines(r.journal).drop(lines0).filter { "\"ev\":\"submit\"" in it }.first { "\"in\":1" in it }
            assertTrue("\"id\":$outFlush," in firstIn && "\"label\":\"TRANSITION+select\"" in firstIn, "the gesture's first flush is the out PLAY's: $firstIn")
            r.assertOracle("after the Encode Select")
            println("DM-T Encode Select, RIGHT's images: " + imgs.joinToString("; ") { img ->
                "${img.size} B [" + subs(img).joinToString(" ") { "${it[0].toInt() and 0x7F}/${if ((it[0].toInt() and 0x7F) in setOf(26, 27)) it[1].toInt() else it.size}" } + "]" })
            println("DM-T Encode Select: ${r.notes("transition").joinToString("\n")}")
        } finally { r.close() }
    }

    /** Each image of [imgs] as the report reads it: its index, its bytes, what it is (a cache write's entries as
     *  offset+length and its mode, the out PLAY, the PLAY-AFTER, a CLOSE of slot 2, or a frame's flush). */
    private fun describeImages(imgs: List<ByteArray>, region: IntRange, reserve: IntRange): List<String> = imgs.mapIndexed { i, img ->
        val w = cacheWrites(img)
        val ss = subs(img)
        val what = when {
            w.isNotEmpty() -> "mode ${img[0].toInt() and 0x7F} write " + w.joinToString(" ") { (o, n) ->
                (if (o in region) "slot" else if (o in reserve) "reserve" else "atlas") + "@$o+$n" }
            ss.any { playIn(it, 2) } -> "out PLAY (${ss.size} sub(s))"
            ss.any { playAfter(it) } -> "frame flush with the PLAY-AFTER (${ss.size} sub(s))"
            ss.any { closeOf(it, 2) } -> "CLOSE of slot 2"
            else -> "frame flush (${ss.size} sub(s))"
        }
        "#$i ${img.size} B $what"
    }

    /**
     * C3 X2T item 2 (the review's 2, decisions AQ (2)): what an Encode Select sends — RIGHT's images in order, printed for
     * the report — into a typical window at 352, into a dense page at 480 (14 lines of 60 letters) and into a page too
     * dense for one compressed message (23 lines of 70 letters at a 16-px pitch). The first two send the records, the
     * out lists and the in lists in ONE write before the out PLAY (compressed: mode 25); the third keeps the order DM-T
     * built — the records and the out lists, the out PLAY, then the in lists' write right above the out lists, before the
     * PLAY-AFTER. Each runs whole.
     */
    @Test
    fun anEncodeSelectSendsItsInListsWithItsRecordsWhenTheyFit_X2T(): Unit = runBlocking {
        val f = wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM, 18)
        val prose = "the quick brown fox jumps over the lazy dog while five wizards box and jump quickly at dawn "
        data class Case(val name: String, val h: Int, val lines: Int, val chars: Int, val gap: Int, val fits: Boolean)
        for (c in listOf(Case("typical at 352", 352, 1, 11, 26, true), Case("dense at 480", 480, 14, 60, 26, true),
                Case("too dense at 480", 480, 23, 70, 16, false))) {
            val r = Rig(stage = 5, kib256 = true)
            try {
                r.rows[0].labels = if (c.lines == 1) listOf("Window zero" to f)
                    else (0 until c.lines).map { i -> (prose + prose).substring(i * 3 % 40, i * 3 % 40 + c.chars) to f }
                r.rows[0].gap = c.gap
                r.upAt(c.h, only("depth"))
                // one visit with Depth first, so the atlas packs every letter the page draws before the Encode act
                selectAndSettle(r)
                r.doubleTap(); r.until("back on Main") { r.onLoop { r.shell.currentWindowId() } == null }; runEnds(r, "the Back ends"); r.settle("on Main")
                r.shell.updateSettings(only("encode")); r.settle("Encode checked")
                r.recording = true
                selectAndSettle(r)
                r.until("the CLOSE of slot 2 reaches RIGHT") { right(r).any { img -> subs(img).any { closeOf(it, 2) } } }
                r.settle("after"); r.recording = false
                val st = r.t.state.value
                val region = st.transitionRegion!!
                val reserve = (st.cacheSize - CfwModes.stageReserve(st.cacheSize, st.bind)) until st.cacheSize
                val imgs = right(r)
                val owed = r.notes("transition").filter { it.startsWith("owed: ") }
                println("X2T item 2, ${c.name}: ${owed.last()}\n  " + describeImages(imgs, region, reserve).joinToString("\n  "))
                assertTrue(owed.last().startsWith("owed: Select Encode"), "${c.name}: Encode played: ${r.notes("transition")}")
                assertTrue(inHalfWhole(r), "${c.name}: ran whole: ${r.notes("transition")}")
                val write = imgs.indexOfFirst { img -> cacheWrites(img).any { it.first in region } }
                val out = imgs.indexOfFirst { img -> subs(img).any { playIn(it, 2) } }
                val lists = imgs.indexOfFirst { img -> cacheWrites(img).let { w -> w.isNotEmpty() && w.all { it.first in reserve } } }
                val pa = imgs.indexOfFirst { img -> subs(img).any { playAfter(it) } }
                val w = cacheWrites(imgs[write])
                if (c.fits) {
                    assertTrue(write in 0 until out && out < pa && lists == -1, "${c.name}: records $write, out PLAY $out, PLAY-AFTER $pa, no list write of its own ($lists)")
                    assertTrue(w.size == 3 && w[0].first in region && w[1].first in reserve && w[2].first == w[1].first + w[1].second,
                        "${c.name}: the slot, the out lists and the in lists right above them, one message: $w")
                    assertEquals(CfwModes.ZWRITE_MODE, imgs[write][0].toInt() and 0x7F, "${c.name}: compressed (mode 25)")
                } else {
                    assertTrue(write in 0 until out && out < lists && lists < pa, "${c.name}: records $write, out PLAY $out, in lists $lists, PLAY-AFTER $pa")
                    assertTrue(w.size == 2 && w[0].first in region && w[1].first in reserve, "${c.name}: the slot and the out lists, one message: $w")
                    assertTrue(cacheWrites(imgs[lists]).single().first == w[1].first + w[1].second, "${c.name}: the in lists right above the out lists")
                }
                r.assertOracle("${c.name}: after the Encode Select")
            } finally { r.close() }
        }
    }

    /**
     * Stage 4, D6's hold (the DM spec §5.1), and C3 X2T item 1: the arriving window draws a face the atlas does not hold,
     * so an atlas chunk is queued during the run; it goes after RIGHT's in-half body (after the CLOSE of slot 2 the body
     * sends) and no earlier than one in-half period after that body — and it does go; the in half draws all its steps (no
     * "ended in" note) — for Encode and for Matrix, each seen to play (X2T item 6: a left-out act plays Depth, which holds
     * nothing).
     */
    @Test
    fun anAtlasChunkQueuedDuringAGlyphRunGoesOnePeriodAfterTheInHalfsBody_X2T(): Unit = runBlocking {
        for (effect in listOf("encode", "matrix")) {
            val r = Rig(stage = 5, kib256 = true)
            try {
                val mono = wm.damage.core.text.FontSpec(wm.damage.core.text.Face.MONO, 16)
                r.rows[0].labels = listOf("A new face here" to mono)
                r.up(only(effect))
                val atlasEnd0 = r.t.state.value.atlasCapacity
                // when each atlas write reached RIGHT (the act's own writes lie above the atlas's layout)
                val atlasAt = java.util.concurrent.CopyOnWriteArrayList<Long>()
                r.onImage = { a, img -> if (a == Arm.RIGHT && cacheWrites(img).any { it.first < atlasEnd0 }) atlasAt += System.currentTimeMillis() }
                r.recording = true
                val t0 = System.currentTimeMillis()
                selectAndSettle(r)
                r.until("the CLOSE of slot 2 reaches RIGHT") { right(r).any { img -> subs(img).any { closeOf(it, 2) } } }
                r.until("the new face is held") { mono in r.shell.cachedFontsLive }
                r.settle("after"); r.recording = false
                val imgs = right(r)
                val region = r.t.state.value.transitionRegion!!
                val atlasEnd = r.t.state.value.atlasCapacity
                val close = imgs.indexOfFirst { img -> subs(img).any { closeOf(it, 2) } }
                val out = imgs.indexOfFirst { img -> subs(img).any { playIn(it, 2) } }
                val atlasWrites = imgs.withIndex().filter { (i, img) -> i > out && cacheWrites(img).any { it.first < atlasEnd } }.map { it.index }
                assertTrue(atlasWrites.isNotEmpty(), "$effect: the atlas wrote the new face after the act")
                assertTrue(atlasWrites.all { it > close }, "$effect: every atlas write after RIGHT's in-half body (CLOSE at $close): $atlasWrites")
                assertTrue(inHalfWhole(r) && r.notes("transition").none { " ended in " in it }, "$effect: the in half drew all its steps: ${r.notes("transition")}")
                val label = if (effect == "encode") "Encode" else "Matrix"
                val owed = r.notes("transition").firstOrNull { it.startsWith("owed: Select $label") }
                assertNotNull(owed, "$effect: the act played $label: ${r.notes("transition")}")
                // X2T item 1: the body's note is written just before the hold's period starts; no atlas write reached RIGHT
                // within one in-half period of it
                val periodMs = Regex("at P (\\d+)").find(owed)!!.groupValues[1].toInt() * 1000.0 / 1024
                val bodyAt = noteTimes(r, "in half's last step drawn on RIGHT").single()
                val after = atlasAt.filter { it >= t0 }
                assertTrue(after.isNotEmpty() && after.all { it - bodyAt >= periodMs }, "$effect: the atlas wrote no earlier than one period (%.1f ms) after the body: %s".format(periodMs, after.map { it - bodyAt }))
            } finally { r.close() }
        }
    }

    /**
     * Stage 4, the act's preconditions: each leaves Encode and Matrix out of the act's pick and says why once — an atlas
     * chunk queued or in flight, a half past 80 % of the step budget, a screen whose lists do not fit (eight face and plane
     * groups) — and the tier's default plays (never a refusal he sees).
     */
    @Test
    fun eachPreconditionLeavesEncodeAndMatrixOutAndSaysWhy_DMT(): Unit = runBlocking {
        // a 160 KiB session: Encode left out (its lists live in the stage reserve of a 256 KiB session with BIND)
        Rig(stage = 5).let { r -> try {
            r.up(only("encode"))
            assertEquals(CfwModes.CACHE2_MAX, r.t.state.value.cacheSize, "the plain rig's session")
            selectAndSettle(r)
            val n = r.notes("transition")
            assertTrue(n.any { it.startsWith("left out of this act's pick: Encode (Encode plays on a 256 KiB session with BIND only") }, "$n")
            assertTrue(n.any { it.startsWith("owed: Select Depth") }, "the default plays: $n")
        } finally { r.close() } }
        // the atlas busy
        Rig(stage = 5, kib256 = true).let { r -> try {
            r.up(only("encode", "matrix"))
            r.shell.atlasBusyForTest = true
            selectAndSettle(r)
            val n = r.notes("transition")
            assertTrue(n.any { it.startsWith("left out of this act's pick: ") && "Encode (an atlas chunk" in it && "Matrix (an atlas chunk" in it }, "$n")
            assertTrue(n.any { it.startsWith("owed: Select Depth") }, "the default plays: $n")
        } finally { r.close() } }
        // a half past the share — the pick seeded so Encode is the first pick (X2T item 4: the leaving screen is recorded
        // only for an Encode picked before the handler; Encode left out, the pick goes to Matrix, left out too)
        Rig(stage = 5, kib256 = true).let { r -> try {
            r.up(only("encode", "matrix"))
            r.shell.transitionRandom = object : java.util.Random() { override fun nextInt(bound: Int) = 0 }     // the first checked: Encode
            r.shell.transitionShareForTest = 1
            // the Depth that plays instead held at its frame phase: the hold is read while that run is in hand
            r.shell.transitionHoldInHalfForTest = true
            r.tap()
            r.until("the frame phase") { r.onLoop { r.shell.transitionPhaseForTest() } == "FRAME" }
            assertTrue(!r.onLoop { r.shell.cacheWritesHeldForTest() }, "an act Encode does not play (left out: a refused half) holds no cache write")
            r.shell.transitionHoldInHalfForTest = false
            r.shell.postIdleTick()
            runEnds(r, "the Depth ends")
            val n = r.notes("transition")
            assertTrue(n.any { it.startsWith("left out of this act's pick: ") && "Encode (a half costs" in it && "Matrix (a half costs" in it }, "$n")
            assertTrue(n.any { it.startsWith("owed: Select Depth") }, "$n")
        } finally { r.close() } }
        // the lists do not fit: the arriving window draws in eight faces the glasses hold
        Rig(stage = 5, kib256 = true).let { r -> try {
            r.rows[0].labels = (0 until 8).map { "Label $it ABC" to wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM, 12 + 2 * it) }
            r.up(only("depth"))
            selectAndSettle(r)                                         // the window once: its eight faces packed
            r.until("the eight faces held") { r.rows[0].labels.all { it.second in r.shell.cachedFontsLive } }
            r.doubleTap(); r.until("back on Main") { r.onLoop { r.shell.currentWindowId() } == null }; runEnds(r, "the Back ends"); r.settle("on Main")
            r.shell.updateSettings(only("encode"))
            r.settle("the setting")
            selectAndSettle(r)
            val n = r.notes("transition")
            assertTrue(n.any { it.startsWith("left out of this act's pick: Encode (") && "cell lists" in it }, "$n")
            assertTrue(n.count { it.startsWith("owed: Select Depth") } == 2, "$n")
        } finally { r.close() } }
    }

    /** The bytes [ctx]'s cache holds at [off] equal [bytes] (the glasses hold the record there). */
    private fun holds(ctx: GlassFirmwareSim.LensCtx, off: Int, bytes: ByteArray): Boolean =
        ctx.textureCache?.let { c -> off + bytes.size <= c.size && c.copyOfRange(off, off + bytes.size).contentEquals(bytes) } == true

    /**
     * C3 X2T item 3 (the review's 3, decisions AQ (3)): a document staged (four strips of six lines in the 32 KiB stage
     * reserve), then an Encode Select (the document → its actions) and an Encode Back — each act's lists go where they
     * meet the fewest strip bytes and drop only the strips they meet: the strips neither act met stay the same records,
     * proven, at their offsets, the glasses hold their bytes there on both lenses and nothing wrote over them; the strips
     * met are staged again once the document is in front and idle; a page turn then goes from the strips.
     */
    @Test
    fun anEncodeActDropsOnlyTheStripsItsListsMeet_X2T(): Unit = runBlocking {
        val r = Rig(stage = 5, kib256 = true)
        val pages = Pages(r.ct)
        r.shell.register(pages)
        try {
            r.up(only("depth"))
            r.onLoop { r.shell.services.openWindow("pages", null) }
            r.until("the document in front") { r.onLoop { r.shell.currentWindowId() } == "pages" }
            // one layout every run: the three strips ahead staged at the top, then a notch down stages the one behind and
            // one more ahead — five strips of 5.9 KB in the 32 KiB reserve, 3.3 KB left above them (measured on the model)
            r.until("the three strips ahead proven") { r.shell.stagedProvenCount >= 3 && r.shell.isQuiescent() && !r.shell.cacheWorkInFlight }
            r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); r.settle("a notch down: the strip behind is wanted too")
            r.until("five strips proven") { r.shell.stagedProvenCount >= 5 && r.shell.isQuiescent() && !r.shell.cacheWorkInFlight }
            val st = r.t.state.value
            val reserve = (st.cacheSize - CfwModes.stageReserve(st.cacheSize, st.bind)) until st.cacheSize
            val before = r.onLoop { r.shell.stagedRecordsForTest() }.filter { it.key !is wm.damage.core.shell.MainStageKey && it.state == wm.damage.core.shell.StagedRecord.State.PROVEN }
            val offs = before.associateWith { it.off }
            println("X2T item 3: the reserve ${reserve.first}..${reserve.last}; strips " + before.joinToString { "${it.key} ${it.size4} B at ${it.off}" })
            r.shell.updateSettings(only("encode")); r.settle("Encode checked")
            r.recording = true
            // the Select: the document's letters out, the actions' in
            val ranges = ArrayList<IntRange>()
            for ((act, gesture) in listOf("Select" to EvenHubMsg.EV_CLICK, "Back" to EvenHubMsg.EV_DOUBLE_CLICK)) {
                r.shell.transitionHoldInHalfForTest = true
                r.shell.postGesture(gesture)
                r.until("the $act's frame phase") { r.onLoop { r.shell.transitionPhaseForTest() } == "FRAME" }
                assertTrue(r.notes("transition").last { it.startsWith("owed: ") }.startsWith("owed: ${if (act == "Select") "Select Encode" else "Back Encode reversed"}"),
                    "$act: Encode played: ${r.notes("transition")}")
                ranges += assertNotNull(r.onLoop { r.shell.transitionBuiltForTest()?.listsRange }, "$act: the run's lists")
                r.shell.transitionHoldInHalfForTest = false
                r.shell.postIdleTick()
                runEnds(r, "the $act ends")
                r.settle("after the $act")
            }
            val met = before.filter { b -> ranges.any { l -> offs.getValue(b) <= l.last && l.first < offs.getValue(b) + b.size4 } }
            val kept = before - met.toSet()
            println("X2T item 3: lists ${ranges.joinToString { "${it.first}..${it.last}" }}; met ${met.map { it.key }}, kept ${kept.map { it.key }}")
            assertTrue(met.isNotEmpty() && kept.isNotEmpty(), "the row needs strips met and strips not met: met ${met.map { it.key }}, kept ${kept.map { it.key }}")
            assertTrue(ranges.all { it.first in reserve && it.last in reserve }, "the lists lie in the stage reserve: $ranges")
            // the first act's lists met the fewest strip bytes any start could (the reserve's start or a strip's end)
            val need = ranges[0].last + 1 - ranges[0].first
            fun cost(at: Int) = before.filter { offs.getValue(it) < at + need && at < offs.getValue(it) + it.size4 }.sumOf { it.size4 }
            val best = (listOf(reserve.first) + before.map { offs.getValue(it) + it.size4 }).filter { it + need <= reserve.last + 1 }.minOf { cost(it) }
            assertEquals(best, cost(ranges[0].first), "the Select's lists went where they meet the fewest strip bytes")
            // the strips neither act met: the same records, proven, where they were, on the glasses; nothing wrote over them
            val now = r.onLoop { r.shell.stagedRecordsForTest() }
            for (k in kept) {
                assertTrue(now.any { it === k } && k.state == wm.damage.core.shell.StagedRecord.State.PROVEN && k.off == offs[k], "${k.key} kept: ${now.map { it.toString() }}")
                assertTrue(holds(r.sim.left, k.off, k.encoded) && holds(r.sim.right, k.off, k.encoded), "${k.key}: the glasses still hold its bytes at ${k.off}")
                for (img in right(r)) for ((o, n) in cacheWrites(img)) assertTrue(o + n <= k.off || o >= k.off + k.size4, "a write [$o, ${o + n}) over ${k.key}")
            }
            // the strips met left the registry and are staged again with the document in front and idle
            assertTrue(met.none { m -> now.any { it === m } && m.off >= 0 }, "the strips the lists met left the registry")
            r.until("the strips met staged again") {
                val recs = r.onLoop { r.shell.stagedRecordsForTest() }
                met.all { m -> recs.any { it.key == m.key && it.state == wm.damage.core.shell.StagedRecord.State.PROVEN } }
            }
            r.settle("staged again")
            val shipped = r.shell.stagedRectsShipped
            r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); r.settle("a page turn")
            assertTrue(r.shell.stagedRectsShipped > shipped, "the page turn went from a strip (${r.shell.stagedRectsShipped} vs $shipped)")
            r.recording = false
            r.assertOracle("after the page turn")
        } finally { r.close() }
    }

    /** FakeText's glyph at a cell whose box's left edge lies at [x] on one lens, top [y], in [g]: a 6 × 10 block two rows
     *  down its box, lit, with a 2-px gap of black on each side (C3 X2T item 6). */
    private fun onGlyph(g: Gray8, x: Int, y: Int): Boolean =
        (0 until 6).all { i -> (2 until 12).all { j -> g[x + i, y + j] != 0 } } && g[x - 1, y + 6] == 0 && g[x + 6, y + 6] == 0

    /**
     * C3 X4T item 1 (`C3-V2T-review.md` 1, decisions AT (1)), with C3 X2T items 5 and 6: Encode acts while a notice box is
     * up over a window's text, its grace running — the window at its own Depth (16), Main and the box at the Global one (8).
     *  - A Back out of the window: its text under the box is no cell. No out cell's box meets the box's middle (its rect
     *    less 24 px a side: room for a kept run's offset and the widest glyph's box) on either lens, and at every out step
     *    the middle on each lens is the leaving picture there under ONE level map (the dim) — a cell's fill or glyph would
     *    map one level two ways. Precondition: the window's text runs under the box.
     *  - A Select back into it: its text under the box is cells at the window's own plane — the box is lifted at the act,
     *    so the in half shows that text. Every in cell sits on its glyph on each lens in the picture LAND shows (FakeText's
     *    glyph: a 6 × 10 block two rows down its 8-px box, a 2-px gap after it); one placed with the box's offset sits 8
     *    px off (X2T item 5), a wrong sign 16 (item 6). Precondition: in cells under the box.
     */
    @Test
    fun noLeavingCellUnderANoticeBoxAndEveryArrivingCellOnItsGlyph_X4T(): Unit = runBlocking {
        val r = Rig(stage = 5, kib256 = true)
        try {
            val f = wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM, 18)
            // eight lines of words across the box's columns, all inside the content rect; a soft hyphen (no cached code: the
            // host draws it) ends each line's first run, so the second run lies wholly inside the box on the lines it covers
            r.rows[0].labels = (0 until 8).map { i -> "Ab${'c' + i}d efgh ijk lmno­pqrs tuvw xyz" to f }
            r.rows[0].plain = true
            r.up({ s -> only("depth")(s).withAppStyle("w0") { it.copy(depth = 16) } })
            selectAndSettle(r)                                     // the window once (Depth): its text packed; in it now
            r.shell.updateSettings(only("encode")); r.settle("Encode checked")
            // the notice once, so its text is packed too; dismissed once it takes focus; then again, its grace running
            r.shell.postNotice(wm.damage.core.shell.Notifications.Notice("SMS · test", "x1", "a message", "12:00"))
            r.until("the first notice takes focus") { r.onLoop { r.shell.notificationsFocusedForTest() } }
            r.doubleTap(); r.until("the first notice gone") { !r.onLoop { r.shell.notificationsUpForTest() } }
            r.settle("after the first notice")
            r.shell.postNotice(wm.damage.core.shell.Notifications.Notice("SMS · test", "x2", "a message", "12:00"))
            // every present on each lens: the box's middle at each out step (the in half not started), the panel at LAND
            val atStep = java.util.concurrent.ConcurrentHashMap<Pair<Arm, Int>, IntArray>()
            val landed = java.util.concurrent.ConcurrentHashMap<Arm, Gray8>()
            val mid = java.util.concurrent.atomic.AtomicReference(wm.damage.core.geom.Rect(0, 0, 1, 1))
            fun levels(arm: Arm, rect: wm.damage.core.geom.Rect): IntArray {
                val ctx = if (arm == Arm.LEFT) r.sim.left else r.sim.right
                return IntArray(rect.w * rect.h) { k -> val x = rect.x + k % rect.w; val y = rect.y + k / rect.w
                    val b = ctx.panel[y * ctx.stride + (x shr 1)].toInt() and 0xFF; if (x and 1 == 0) b shr 4 else b and 0x0F }
            }
            val listener = object : GlassFirmwareSim.SimDiag {
                override fun event(kind: String, detail: String) {}
                override fun notify(arm: Arm, packet: ByteArray) {}
                override fun panelChanged(arm: Arm) {
                    val m = r.sim.motionForTest(arm)
                    val out = m.slots[2]?.takeIf { it.tag and 0xF000 == 0xC000 }
                    val inn = m.slots[3]?.takeIf { it.tag and 0xF000 == 0xD000 }
                    if (out != null && (inn == null || inn.d == 0) && out.d >= 1) atStep[arm to out.d] = levels(arm, mid.get())
                    if (inn != null && inn.d == inn.T && !landed.containsKey(arm)) landed[arm] = r.panel(arm == Arm.LEFT)
                }
            }
            r.sim.attachListener(listener)
            class Act(val built: Transitions.Built, val box: wm.damage.core.geom.Rect, val runs: List<wm.damage.core.comp.TextDraw>, val before: Map<Arm, Gray8>)
            /** One act ([act]: a double tap or a tap) with the box up, unfocused and still: the screen's text runs and each
             *  lens's picture just before it, the run's records read at its frame phase. */
            suspend fun withTheBoxUp(what: String, act: () -> Unit): Act {
                r.until("$what: the box up, still, its grace running") { r.onLoop { r.shell.noticePlaneForTest() != null && !r.shell.notificationsFocusedForTest() &&
                    !r.shell.notifications.animating } && !r.shell.cacheWorkInFlight }
                r.settle("$what: the box painted")
                val box = r.onLoop { r.shell.noticePlaneForTest() }!!
                assertTrue(!r.onLoop { r.shell.notificationsFocusedForTest() }, "$what: the box still unfocused")
                val runs = r.onLoop { r.shell.screenCellsForTest() }.first
                val before = mapOf(Arm.LEFT to r.panel(true), Arm.RIGHT to r.panel(false))
                mid.set(wm.damage.core.geom.Rect(box.x + 24, box.y, box.w - 48, box.h))
                atStep.clear(); landed.clear()
                r.shell.transitionHoldInHalfForTest = true
                act()
                r.until("$what: the frame phase") { r.onLoop { r.shell.transitionPhaseForTest() } == "FRAME" }
                val built = assertNotNull(r.onLoop { r.shell.transitionBuiltForTest() })
                r.until("$what: the out half drew its last step on both lenses") { Arm.entries.all { atStep.containsKey(it to built.timing.steps) } }
                r.shell.transitionHoldInHalfForTest = false
                r.shell.postIdleTick()
                runEnds(r, "$what ends")
                r.until("$what: LAND seen on both lenses") { landed.size == 2 }
                return Act(built, box, runs, before)
            }
            // the Back: the window's text under the box is no cell of its out half
            val a1 = withTheBoxUp("the Back") { r.doubleTap() }
            val back = a1.built; val box1 = a1.box; val runs = a1.runs
            assertTrue(r.notes("transition").last { it.startsWith("owed: ") }.startsWith("owed: Back Encode reversed"), "Encode played: ${r.notes("transition")}")
            val mid1 = wm.damage.core.geom.Rect(box1.x + 24, box1.y, box1.w - 48, box1.h)
            assertTrue(runs.any { it.rect.overlaps(mid1) }, "precondition: the window's text runs under the box ($box1)")
            var outCells = 0
            val inBox = ArrayList<String>()
            for (t in back.out.tracks) for (seg in t.segments) {
                val list = seg.cells?.lists?.get(seg.aux0) ?: continue
                for (c in list.cells) {
                    outCells++
                    for ((dx, lens) in listOf(seg.xL to "LEFT", (if (t.perLens) seg.xR else seg.xL) to "RIGHT"))
                        if (wm.damage.core.geom.Rect(c.bx + dx, c.by, c.bw, c.bh).overlaps(mid1)) inBox += "'${Char(c.old)}' at ${c.bx},${c.by} on $lens"
                }
            }
            assertTrue(outCells > 0, "the out half has cells (the window's text beside the box)")
            assertTrue(inBox.isEmpty(), "${inBox.size} out cells under the box ($box1): ${inBox.take(8)}")
            val twoWays = ArrayList<String>()
            for (arm in Arm.entries) {
                val g0 = a1.before.getValue(arm)
                val before = IntArray(mid1.w * mid1.h) { k -> g0[mid1.x + k % mid1.w, mid1.y + k / mid1.w] / 17 }
                for (d in 1..back.timing.steps) {
                    val now = atStep[arm to d] ?: continue
                    val map = HashMap<Int, Int>()
                    for (k in now.indices) { val was = map.putIfAbsent(before[k], now[k]); if (was != null && was != now[k]) { twoWays += "$arm out step $d: level ${before[k]} shows as $was and ${now[k]}"; break } }
                }
            }
            assertTrue(Arm.entries.all { atStep.containsKey(it to 1) }, "out step 1 seen on both lenses: ${atStep.keys}")
            assertTrue(twoWays.isEmpty(), "the box's middle is not the dimmed picture: $twoWays")
            // the Select: the window's text under the box is cells at the window's own plane, each on its glyph at LAND
            r.until("the box back after the Back") { r.onLoop { r.shell.notificationsUpForTest() } }
            val a2 = withTheBoxUp("the Select") { r.tap() }
            val sel = a2.built; val box2 = a2.box
            assertTrue(r.notes("transition").last { it.startsWith("owed: ") }.startsWith("owed: Select Encode"), "Encode played: ${r.notes("transition")}")
            var inCells = 0; var under = 0
            val bad = ArrayList<String>()
            for (t in sel.inn.tracks) for (seg in t.segments) {
                val list = seg.cells?.lists?.get(seg.aux0) ?: continue
                for (c in list.cells) {
                    inCells++
                    if (box2.contains(wm.damage.core.geom.Rect(c.bx, c.by, c.bw, c.bh))) under++
                    for ((arm, dx) in listOf(Arm.LEFT to seg.xL, Arm.RIGHT to (if (t.perLens) seg.xR else seg.xL)))
                        if (!onGlyph(landed.getValue(arm), c.bx + dx, c.by)) bad += "cell at ${c.bx},${c.by} on $arm at x ${c.bx + dx} (offset $dx)"
                }
            }
            r.sim.detachListener(listener)
            assertTrue(inCells > 0 && under > 0, "in cells $inCells, under the box ($box2) $under")
            assertTrue(bad.isEmpty(), "${bad.size} of ${2 * inCells} in-cell placements off their glyph: ${bad.take(8)}")
            r.until("the box back after the Select") { r.onLoop { r.shell.notificationsUpForTest() } }
        } finally { r.close() }
    }

    /**
     * C3 X4T item 2 (`C3-V2T-review.md` 2, decisions AT (2)): a second Encode act right after a first, into a dense page at
     * 480 (14 lines of 60 letters: two cell lists). The first — a Back out of the page — writes its records and lists
     * deflated (mode 25), and that message waits for the read that judges it, so a cache write submitted now goes raw (the
     * transport says so: the precondition, read just before the second act). The second — a Select back into the page
     * during the first's in half — then sends the small form first: its records and the out lists, then its out PLAY, then
     * the in lists in a write of their own right above the out lists, before its PLAY-AFTER (DM-T's order) — never all
     * three raw in front of the first image.
     */
    @Test
    fun aQuickSecondEncodeActSendsItsInListsAfterItsOutPlay_X4T(): Unit = runBlocking {
        val r = Rig(stage = 5, kib256 = true)
        try {
            val f = wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM, 18)
            val prose = "the quick brown fox jumps over the lazy dog while five wizards box and jump quickly at dawn "
            r.rows[0].labels = (0 until 14).map { i -> (prose + prose).substring(i * 3 % 40, i * 3 % 40 + 60) to f }
            r.upAt(480, only("depth"))
            selectAndSettle(r)                                                  // the page once: its letters packed
            r.doubleTap(); r.until("back on Main") { r.onLoop { r.shell.currentWindowId() } == null }; runEnds(r, "the Back ends"); r.settle("on Main")
            selectAndSettle(r)                                                  // into the page again (Depth)
            r.shell.updateSettings(only("encode")); r.settle("Encode checked")
            // from the first act until the second is made, the read that would judge the first act's mode-25 write gets no
            // answer (the transport asks again 2 s on): the window a phone's round trip gives on the glasses' link
            val dropReads = java.util.concurrent.atomic.AtomicBoolean(false)
            (r.t as StartRig).onNotify = { _, p -> if (dropReads.get() && StartRig.telemetry(p) != null) null else p }
            val region0 = r.t.state.value.transitionRegion!!
            r.recording = true
            dropReads.set(true)
            r.doubleTap()                                                       // the first act: Back, Encode
            r.until("the first act's in half drawing on RIGHT") { r.onLoop { r.shell.transitionPhaseForTest() } == "IN" &&
                synchronized(r.sim) { r.sim.motionForTest(Arm.RIGHT).slots[3]?.let { it.drawing && it.d >= 1 && it.d < it.T } } == true }
            assertTrue(r.notes("transition").last { it.startsWith("owed: ") }.startsWith("owed: Back Encode reversed"), "${r.notes("transition")}")
            val first = right(r).first { img -> cacheWrites(img).any { it.first in region0 } }
            assertEquals(CfwModes.ZWRITE_MODE, first[0].toInt() and 0x7F, "precondition: the first act's records' write went deflated (mode 25)")
            assertTrue(!r.t.nextListDeflates(), "precondition: the first act's mode-25 write still waits for its judgment, so a write now goes raw")
            r.images.clear()
            val owed0 = r.notes("transition").count { it.startsWith("owed: ") }
            r.tap()                                                             // the second act: Select, Encode
            r.until("w0 opens") { r.onLoop { r.shell.currentWindowId() } == "w0" }
            r.until("the second act owed") { r.notes("transition").count { it.startsWith("owed: ") } == owed0 + 1 }
            dropReads.set(false)
            runEnds(r, "the second act ends")
            r.until("the CLOSE of slot 2 reaches RIGHT") { right(r).any { img -> subs(img).any { closeOf(it, 2) } } }
            r.settle("after"); r.recording = false
            val notes = r.notes("transition")
            assertTrue(notes.last { it.startsWith("owed: ") }.startsWith("owed: Select Encode") && notes.any { it.startsWith("a new act replaces Back's (IN)") }, "$notes")
            val st = r.t.state.value
            val region = st.transitionRegion!!
            val reserve = (st.cacheSize - CfwModes.stageReserve(st.cacheSize, st.bind)) until st.cacheSize
            val imgs = right(r)
            val write = imgs.indexOfFirst { img -> cacheWrites(img).any { it.first in region } }
            val out = imgs.indexOfFirst { img -> subs(img).any { playIn(it, 2) } }
            val lists = imgs.indexOfFirst { img -> cacheWrites(img).let { w -> w.isNotEmpty() && w.all { it.first in reserve } } }
            val pa = imgs.indexOfFirst { img -> subs(img).any { playAfter(it) } }
            val w = cacheWrites(imgs[write])
            val said = "the second act's records' write #$write ${imgs[write].size} B (mode ${imgs[write][0].toInt() and 0x7F}, entries ${w.map { it.second }})" +
                (if (lists >= 0) ", its in lists' write #$lists ${imgs[lists].size} B (mode ${imgs[lists][0].toInt() and 0x7F})" else "") + "; out PLAY #$out, PLAY-AFTER #$pa"
            println("X4T item 2: $said\n  " + describeImages(imgs, region, reserve).joinToString("\n  "))
            assertTrue(w.size == 2 && w[0].first in region && w[1].first in reserve, "the first write is the records and the out lists only: $said")
            assertTrue(write in 0 until out && out < lists && lists < pa, "records, out PLAY, in lists, PLAY-AFTER in that order: $said")
            assertTrue(cacheWrites(imgs[lists]).single().first == w[1].first + w[1].second, "the in lists right above the out lists: $said")
            assertTrue(inHalfWhole(r), "$notes")
            r.assertOracle("after the second act")
        } finally { r.close() }
    }

    /**
     * C3 X4T item 4 (`C3-V2T-review.md` 5, decisions AT (5)): a harness never finds the shell idle while the glyph hold's
     * extra period stands (one in-half period after RIGHT's in-half body of an Encode or Matrix run, a held cache write
     * waiting for its pump). Sampled on the loop from the act until the hold has gone, no idle sample sees the hold.
     * Precondition: the hold was seen with the run ended (its extra period).
     */
    @Test
    fun theShellIsNotIdleWhileTheGlyphHoldStands_X4T(): Unit = runBlocking {
        val r = Rig(stage = 5, kib256 = true)
        try {
            r.rows[0].labels = listOf("Window zero" to wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM, 18))
            r.up(only("encode"))
            r.tap()
            var idleHeld = 0; var extra = 0; var idle = 0
            val t0 = System.currentTimeMillis()
            while (true) {
                when (r.shell.sampleIdle { r.shell.cacheWritesHeldForTest() }) { true -> idleHeld++; false -> idle++; null -> {} }
                val (phase, held) = r.onLoop { r.shell.transitionPhaseForTest() to r.shell.cacheWritesHeldForTest() }
                val body = r.notes("transition").any { it.startsWith("in half's last step drawn on RIGHT") }
                if (phase == null && held && body) extra++
                if (phase == null && !held && body) break
                assertTrue(System.currentTimeMillis() - t0 < 30_000, "the run and its hold never ended: ${r.notes("transition").takeLast(4)}")
                delay(1)
            }
            assertTrue(r.notes("transition").any { it.startsWith("owed: Select Encode") }, "Encode played: ${r.notes("transition")}")
            assertTrue(extra > 0, "precondition: the hold's extra period was seen after the run had ended")
            assertEquals(0, idleHeld, "idle samples taken while the hold stood ($extra looks inside the extra period, $idle idle samples without the hold)")
        } finally { r.close() }
    }

    /** Stage 3 and C3 X2T items 1, 3 and 6: the hold on the other cache writes released at each end of an Encode run — the
     *  body (plus its one period: the Encode Select row above), the deadline, a second act (a Back with Depth over the
     *  run), a session start — with Encode seen to play in each (a left-out act plays Depth, which holds nothing). There is
     *  no owner of the stage reserve any more: an Encode run's lists drop only the strips they meet and the hold covers
     *  what the owner guarded. */
    @Test
    fun theHoldIsReleasedAtEveryEndOfAnEncodeRun_X2T(): Unit = runBlocking {
        // the deadline: RIGHT's body lost
        Rig(stage = 5, kib256 = true).let { r -> try {
            r.up(only("encode"))
            r.shell.transitionBodyLostForTest = true
            r.shell.transitionHoldInHalfForTest = true
            r.tap()
            r.until("the frame phase") { r.onLoop { r.shell.transitionPhaseForTest() } == "FRAME" }
            assertTrue(r.onLoop { r.shell.cacheWritesHeldForTest() }, "held during the run")
            r.shell.transitionHoldInHalfForTest = false
            r.shell.postIdleTick()
            runEnds(r, "the run ends at its deadline")
            r.settle("after the deadline")
            assertTrue(r.notes("transition").any { it.startsWith("owed: Select Encode") }, "Encode played: ${r.notes("transition")}")
            assertTrue(r.notes("transition").any { "no in-half body" in it }, "${r.notes("transition")}")
            assertTrue(!r.onLoop { r.shell.cacheWritesHeldForTest() }, "released at the deadline")
        } finally { r.close() } }
        // a second act: a Back (Depth) while the Encode Select still runs
        Rig(stage = 5, kib256 = true).let { r -> try {
            r.up { s -> s.withTier(Tier.SELECT, effects = listOf("encode")).withTier(Tier.BACK, effects = listOf("depth")) }
            r.shell.transitionHoldInHalfForTest = true
            r.tap()
            r.until("the frame phase") { r.onLoop { r.shell.transitionPhaseForTest() } == "FRAME" }
            assertTrue(r.notes("transition").any { it.startsWith("owed: Select Encode") }, "Encode played: ${r.notes("transition")}")
            assertTrue(r.onLoop { r.shell.cacheWritesHeldForTest() }, "held during the run")
            r.doubleTap()
            r.until("the Back owed or sent") { r.notes("transition").any { it.startsWith("owed: Back Depth") } }
            assertTrue(!r.onLoop { r.shell.cacheWritesHeldForTest() }, "released when a new act replaces the run")
            r.shell.transitionHoldInHalfForTest = false
            r.shell.postIdleTick()
            runEnds(r, "the Back ends")
        } finally { r.close() } }
        // a session start in the middle of the run
        Rig(stage = 5, kib256 = true).let { r -> try {
            r.up(only("encode"))
            r.shell.transitionHoldInHalfForTest = true
            r.tap()
            r.until("the frame phase") { r.onLoop { r.shell.transitionPhaseForTest() } == "FRAME" }
            assertTrue(r.notes("transition").any { it.startsWith("owed: Select Encode") }, "Encode played: ${r.notes("transition")}")
            assertTrue(r.onLoop { r.shell.cacheWritesHeldForTest() }, "held during the run")
            r.shell.transitionHoldInHalfForTest = false
            assertTrue(r.t.restartSession("test: a session start mid-run"), "the session restarts")
            r.until("the run ended at the start") { r.onLoop { r.shell.transitionPhaseForTest() } == null }
            assertTrue(!r.onLoop { r.shell.cacheWritesHeldForTest() }, "released at a session start")
        } finally { r.close() } }
    }

    // ------------------------------------------------------------------ C3 X6T: the count's two rules (decisions AW)
    /** C3 X6T: RIGHT's count readings (op 1) held on their way while [hold] is set — the reading stays out — and handed to
     *  the model by [release] once nothing else is in flight on the link (no message is cut in two on RIGHT's
     *  characteristic): the glasses then answer with every write applied since the reading was sent. */
    private class HeldReads(val r: Rig) {
        val hold = java.util.concurrent.atomic.AtomicBoolean(false)
        val held = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        init {
            (r.t as StartRig).onWrite = { arm, p ->
                if (hold.get() && arm == Arm.RIGHT && StartRig.damageOp(p) == wm.damage.core.wire.DamageMsg.OP_TELEMETRY) { held += p; null } else p
            }
        }
        suspend fun release() {
            hold.set(false)
            r.until("the link idle before the held reading goes") { r.t.state.value.inFlight == 0 }
            for (p in held) r.sim.write(Arm.RIGHT, p, System.currentTimeMillis())
            held.clear()
        }
    }

    /** Up at 352 with no transition checked, into w0 and back once — both screens' letters packed, so no atlas chunk goes
     *  during the acts that follow. */
    private suspend fun visitW0First(r: Rig) {
        r.upAt(352, only())
        selectAndSettle(r)
        r.doubleTap(); r.until("back on Main") { r.onLoop { r.shell.currentWindowId() } == null }; r.settle("on Main")
    }

    /** The journal's keyframes (a submit carrying one). */
    private fun keyframesIn(r: Rig): Int = Files.readAllLines(r.journal).count { "\"ev\":\"submit\"" in it && "\"op\":\"keyframe\"" in it }

    /** The count's notes that say it went wrong: an extra bump, a short reading, a shortfall, a re-send, its keyframe. */
    private fun countTrouble(r: Rig): List<String> = (r.notes("atlas") + r.notes("stage")).filter { n ->
        listOf("count over", "count short", "short after the settle window", "never landed", "re-send", "keyframing", "more generation bump").any { it in n } }

    /** The count's journal from the rig's start, for a failure's reader. */
    private fun countNotes(r: Rig): String = (r.notes("atlas") + r.notes("stage")).filter { "count" in it || "check" in it }.takeLast(12).toString()

    /**
     * C3 X6T (decisions AW; the 44 of 45, `C3-X5T-compare.md`): run 22's shape made deterministic. The count's reading is
     * on its way (RIGHT's request held) when an act's records' write goes, and RIGHT answers after that write's ack, with
     * the write applied. The reading is voided and asked again: no extra bump read, no short reading, no shortfall, no
     * atlas re-send, no keyframe; the count agrees after; both acts' halves draw every step.
     * Watched to fail on `c637a67b` (the write was not booked, so the answer read one bump over and re-based the count one
     * write behind: "count over", then short readings to a shortfall).
     */
    @Test
    fun aRecordsWriteUnderACountReadingVoidsIt_X6T(): Unit = runBlocking {
        val r = Rig(stage = 5, kib256 = true)
        try {
            visitW0First(r)
            r.shell.updateSettings(only("fade")); r.settle("Fade checked")
            r.until("the count agreed") { r.shell.cacheCountUnjudged == 0L && !r.shell.cacheWorkInFlight }
            val reads = HeldReads(r)
            val kf0 = keyframesIn(r)
            reads.hold.set(true)
            r.tap()                                                             // act 0: Select, Fade
            r.until("w0 opens") { r.onLoop { r.shell.currentWindowId() } == "w0" }
            runEnds(r, "act 0 ends")
            // act 0's records' write was acked: the count's reading goes, and stays on its way
            r.until("the count's reading on its way, held") { reads.held.isNotEmpty() && r.shell.cacheWorkInFlight }
            r.doubleTap()                                                       // act 1: Back, Fade — its records' write goes under the reading
            r.until("back on Main") { r.onLoop { r.shell.currentWindowId() } == null }
            runEnds(r, "act 1 ends")
            assertTrue(r.notes("transition").count { it.startsWith("owed: ") } == 2, "${r.notes("transition")}")
            reads.release()                                                     // RIGHT answers now, act 1's write applied
            r.until("the count agrees again", maxMs = 15_000) { r.shell.cacheCountUnjudged == 0L && !r.shell.cacheWorkInFlight }
            r.settle("after")
            assertEquals(emptyList(), countTrouble(r), "the count: ${countNotes(r)}")
            assertTrue(r.notes("atlas").any { it.startsWith("count void") }, "the held reading was voided: ${countNotes(r)}")
            assertEquals(kf0, keyframesIn(r), "no keyframe")
            bothHalvesWhole(r, 0); bothHalvesWhole(r, 1)
        } finally { r.close() }
    }

    /**
     * C3 X6T: the same for an Encode act whose in lists go in a write of their own after the out PLAY (the page too dense
     * for one compressed message, `select-encode-apart-480`'s): the count's reading is on its way (held) through the act's
     * two writes, RIGHT answers after both acks. Voided and asked again; nothing over, short or re-sent, no keyframe, the
     * count agrees, both halves of both acts draw every step. Watched to fail on `c637a67b` (two writes unbooked: two
     * bumps over, the count two writes behind).
     */
    @Test
    fun anEncodeActsListsWriteUnderACountReadingVoidsIt_X6T(): Unit = runBlocking {
        val r = Rig(stage = 5, kib256 = true)
        val f = wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM, 18)
        val prose = "the quick brown fox jumps over the lazy dog while five wizards box and jump quickly at dawn "
        r.rows[0].labels = (0 until 23).map { i -> (prose + prose).substring(i * 3 % 40, i * 3 % 40 + 70) to f }
        r.rows[0].gap = 16
        try {
            r.upAt(480) { s -> s.withTier(Tier.SELECT, effects = emptyList()).withTier(Tier.BACK, effects = listOf("fade")).withTier(Tier.LOCK, effects = emptyList()) }
            selectAndSettle(r)                                                  // into w0 with no act: its letters packed
            r.shell.updateSettings { it.withTier(Tier.SELECT, effects = listOf("encode")) }; r.settle("Encode checked")
            r.until("the count agreed") { r.shell.cacheCountUnjudged == 0L }
            val reads = HeldReads(r)
            val kf0 = keyframesIn(r)
            reads.hold.set(true)
            r.doubleTap()                                                       // act 0: Back, Fade — its write acked, the reading goes, held
            r.until("back on Main") { r.onLoop { r.shell.currentWindowId() } == null }
            runEnds(r, "act 0 ends")
            r.until("the count's reading on its way, held") { reads.held.isNotEmpty() && r.shell.cacheWorkInFlight }
            r.tap()                                                             // act 1: Select, Encode — records + out lists, then the in lists
            r.until("w0 opens") { r.onLoop { r.shell.currentWindowId() } == "w0" }
            runEnds(r, "act 1 ends")
            val notes = r.notes("transition")
            assertTrue(notes.last { it.startsWith("owed: ") }.startsWith("owed: Select Encode"), "$notes")
            assertTrue(notes.any { it.startsWith("the in half's lists' write (flush") }, "precondition: the in lists went in a write of their own: $notes")
            reads.release()
            r.until("the count agrees again", maxMs = 15_000) { r.shell.cacheCountUnjudged == 0L && !r.shell.cacheWorkInFlight }
            r.settle("after")
            assertEquals(emptyList(), countTrouble(r), "the count: ${countNotes(r)}")
            assertTrue(r.notes("atlas").any { it.startsWith("count void") }, "the held reading was voided: ${countNotes(r)}")
            assertEquals(kf0, keyframesIn(r), "no keyframe")
            bothHalvesWhole(r, 0); bothHalvesWhole(r, 1)
        } finally { r.close() }
    }

    /**
     * C3 X6T: every cache write's ack restarts the settle window. Every atlas and stage ack is more than a window old; an
     * act's records' write is acked and the count's reading follows it within ms, answered one bump short (a decode still
     * on its way, as the glasses' link gives it): the reading is pending (inside the window), not a settled short one.
     * Watched to fail on `c637a67b` (a transition's ack did not restart the window: "count short (settled reading 1)").
     */
    @Test
    fun aReadingRightAfterARecordsWritesAckIsNotSettled_X6T(): Unit = runBlocking {
        val r = Rig(stage = 5, kib256 = true)
        try {
            visitW0First(r)
            r.shell.updateSettings(only("fade")); r.settle("Fade checked")
            r.until("the count agreed") { r.shell.cacheCountUnjudged == 0L && !r.shell.cacheWorkInFlight }
            delay(wm.damage.core.shell.CacheLedger.SETTLE_MS + 300)
            val lastWrite = Files.readAllLines(r.journal).filter { "\"ev\":\"submit\"" in it && ("\"label\":\"ATLAS\"" in it || "\"label\":\"STAGE\"" in it) }
                .maxOfOrNull { Regex("\"t\":(\\d+)").find(it)!!.groupValues[1].toLong() } ?: 0L
            assertTrue(System.currentTimeMillis() - lastWrite >= wm.damage.core.shell.CacheLedger.SETTLE_MS, "precondition: no atlas or stage write in the last window")
            // the next count reading reads one bump short; after the count's first word on it, the answers are true again
            val short = java.util.concurrent.atomic.AtomicBoolean(true)
            (r.t as StartRig).onNotify = { _, p ->
                val g = if (short.get()) StartRig.telemetry(p)?.cacheGen else null
                if (g != null && g > 0) StartRig.withRecordField(p, 17, g - 1) else p
            }
            val atlas0 = r.notes("atlas").size
            r.tap()                                                             // the act: Select, Fade
            r.until("w0 opens") { r.onLoop { r.shell.currentWindowId() } == "w0" }
            r.until("the count's word on the reading after the act's write") {
                r.notes("atlas").drop(atlas0).any { it.startsWith("count pending") || it.startsWith("count short") } }
            short.set(false)
            runEnds(r, "the act ends")
            r.until("the count agrees again", maxMs = 15_000) { r.shell.cacheCountUnjudged == 0L && !r.shell.cacheWorkInFlight }
            val after = r.notes("atlas").drop(atlas0)
            assertTrue(after.first { it.startsWith("count pending") || it.startsWith("count short") }.startsWith("count pending"),
                "the reading after the act's write is inside the settle window: $after")
            assertEquals(emptyList(), countTrouble(r), "the count: ${countNotes(r)}")
            bothHalvesWhole(r, 0)
        } finally { r.close() }
    }

    // ------------------------------------------------------------------------- APK 0.83 integration, part 2
    /**
     * 0.83 integration, part 2, items 3b and 3d (K1-4a's notice slice meets X2T's cell lists in the stage reserve): an
     * Encode Select into a page too dense for one compressed message (23 lines of 70 letters at 480,
     * `select-encode-apart-480`'s), whose lists reach the notice's slice (the reserve's top 16 KiB at 256 KiB with BIND)
     * while the notice's programs are counted there. The programs leave the books at the act (their bytes are the lists'
     * now), are not written again while the run holds (D6: the notice lane reads [Shell]'s hold as every cache writer
     * does) and go again after it: the in half draws every step, no write into the slice reaches RIGHT before one in-half
     * period after its in-half body, and at rest every program the books count is on both lenses byte for byte.
     */
    @Test
    fun anEncodeActsListsInTheNoticeSliceTakeItsProgramsOutUntilTheRunEnds_INT083(): Unit = runBlocking {
        val r = Rig(stage = 5, kib256 = true)
        val f = wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM, 18)
        val prose = "the quick brown fox jumps over the lazy dog while five wizards box and jump quickly at dawn "
        r.rows[0].labels = (0 until 23).map { i -> (prose + prose).substring(i * 3 % 40, i * 3 % 40 + 70) to f }
        r.rows[0].gap = 16
        try {
            r.upAt(480, only("depth"))
            // one visit with Depth first, so the atlas packs every letter the page draws before the Encode act
            selectAndSettle(r)
            r.doubleTap(); r.until("back on Main") { r.onLoop { r.shell.currentWindowId() } == null }; runEnds(r, "the Back ends"); r.settle("on Main")
            r.shell.updateSettings(only("encode")); r.settle("Encode checked")
            r.until("the notice's programs counted") { r.onLoop { r.shell.noticeLaneDoneForTest() && r.shell.noticeProgramRecordsForTest().isNotEmpty() } }
            val st = r.t.state.value
            val slice = CfwModes.noticeRegion(st.cacheSize, st.bind)!!
            val before = r.onLoop { r.shell.noticeProgramRecordsForTest() }
            // when each write that starts inside the slice (the notice lane's: the act's lists start below it) reached RIGHT
            val sliceAt = java.util.concurrent.CopyOnWriteArrayList<Long>()
            r.onImage = { a, img -> if (a == Arm.RIGHT && cacheWrites(img).any { it.first in slice }) sliceAt += System.currentTimeMillis() }
            val t0 = System.currentTimeMillis()
            selectAndSettle(r)
            r.until("the Encode act's in-half body") { noteTimes(r, "in half's last step drawn on RIGHT").any { it >= t0 } }
            r.until("the notice's programs counted again", maxMs = 20_000) { r.onLoop { r.shell.noticeLaneDoneForTest() && r.shell.noticeProgramRecordsForTest().isNotEmpty() } }
            r.settle("after")
            val owed = r.notes("transition").last { it.startsWith("owed: ") }
            assertTrue(owed.startsWith("owed: Select Encode"), "Encode played: ${r.notes("transition")}")
            val m = Regex("cell lists (\\d+) B at off4 (\\d+) \\(out\\) and (\\d+) B at off4 (\\d+) \\(in\\)").find(owed)!!.groupValues.drop(1).map { it.toInt() }
            val lists = minOf(m[1], m[3]) * 4 until maxOf(m[1] * 4 + m[0], m[3] * 4 + m[2])
            assertTrue(before.any { (off, b, _) -> off <= lists.last && lists.first < off + b.size },
                "precondition: the lists ($lists) meet a counted notice program (${before.map { it.first until it.first + it.second.size }})")
            val bodies = noteTimes(r, "in half's last step drawn on RIGHT").filter { it >= t0 }
            assertTrue(bodies.size == 1 && r.notes("transition").none { " ended in " in it }, "the in half drew all its steps: ${r.notes("transition")}")
            val periodMs = Regex("at P (\\d+)").find(owed)!!.groupValues[1].toInt() * 1000.0 / 1024
            val bodyAt = bodies.single()
            val after = sliceAt.filter { it >= t0 }
            assertTrue(after.isNotEmpty() && after.all { it - bodyAt >= periodMs },
                "the notice's programs went again no earlier than one in-half period (%.1f ms) after the body: %s".format(periodMs, after.map { it - bodyAt }))
            val progs = r.onLoop { r.shell.noticeProgramRecordsForTest() }
            for ((off, b, state) in progs) {
                assertEquals("PROVEN", state, "every notice program counted at rest: $progs")
                for (lens in listOf(r.sim.left, r.sim.right)) assertTrue(lens.textureCache!!.copyOfRange(off, off + b.size).contentEquals(b),
                    "the notice program at $off (${b.size} B) is on ${if (lens === r.sim.left) "LEFT" else "RIGHT"} byte for byte")
            }
            r.assertOracle("after the Encode Select")
        } finally { r.close() }
    }

    /** FX-083 T1: from [on] the glasses' image acks wait on the link in order (the glasses applied the images; their acks
     *  are late); [release] hands them over in the order they came, and nothing passes them meanwhile. */
    private class HeldAcks(r: Rig) {
        private val sr = r.t as StartRig
        private val held = ArrayList<Pair<Arm, ByteArray>>()
        private var holding = false
        init {
            sr.onNotify = { a, p ->
                synchronized(held) { if (holding && StartRig.sid(p) == EvenHubMsg.SID && p[7].toInt() == EvenHubMsg.FLAG_ACK) { held += a to p; null } else p }
            }
        }
        val count: Int get() = synchronized(held) { held.size }
        fun on() = synchronized(held) { holding = true }
        fun release() = synchronized(held) { for ((a, p) in held) sr.deliver(a, p); held.clear(); holding = false }
    }

    /**
     * FX-083 T1, the product question: the notice lane's program write is sent and not yet acked when an act starts —
     * real use reaches it, as the lane writes again right after an act's hold ends and a Select then a Back inside a second
     * starts the next act there. With Encode ([effect] "encode", the 23 × 70 page at 480 whose lists reach the notice's
     * slice) the programs leave the books at the act; the late ack marks nothing written; the lane writes them again after
     * the run's hold with no further input; the count stays right (nothing called short, over or re-based); belief equals
     * both lenses at rest; and a notice after the act PLAYs only programs the books count, whose bytes both lenses hold at
     * that PLAY. Matrix ("matrix", no lists) is the control: nothing leaves the books, the late ack counts the program.
     */
    private fun aNoticeProgramsWriteInFlightAtAnAct(effect: String): Unit = runBlocking {
        val r = Rig(stage = 5, kib256 = true)
        val f = wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM, 18)
        val prose = "the quick brown fox jumps over the lazy dog while five wizards box and jump quickly at dawn "
        r.rows[0].labels = (0 until 23).map { i -> (prose + prose).substring(i * 3 % 40, i * 3 % 40 + 70) to f }
        r.rows[0].gap = 16
        try {
            r.upAt(480, only("depth"))
            selectAndSettle(r)
            r.doubleTap(); r.until("back on Main") { r.onLoop { r.shell.currentWindowId() } == null }; runEnds(r, "the Back ends"); r.settle("on Main")
            // every Popup style on: nine programs (three box heights × timer, fade, slide), written one at a time
            r.shell.updateSettings { only(effect)(it).copy(popupFade = true, popupSlide = true) }; r.settle("$effect checked")
            r.until("the notice's programs counted") { r.onLoop { r.shell.noticeLaneDoneForTest() && r.shell.noticeProgramRecordsForTest().isNotEmpty() } }
            val st = r.t.state.value
            val slice = CfwModes.noticeRegion(st.cacheSize, st.bind)!!
            val acks = HeldAcks(r)
            acks.on()
            r.shell.noticeYieldForTest()                       // the programs go again: the lane's first write leaves, its ack held
            r.until("a program's write sent, its ack held") { acks.count > 0 && r.onLoop { r.shell.noticeProgramsForTest().any { it.endsWith(" SENT") } } }
            val lines0 = Files.readAllLines(r.journal).size
            r.recording = true
            r.tap()
            r.until("the act is owed") { Files.readAllLines(r.journal).drop(lines0).any { "\"kind\":\"transition\"" in it && "owed: Select" in it } }
            val yielded = Files.readAllLines(r.journal).drop(lines0).any { "the notice's programs leave the slice" in it }
            delay(150)
            acks.release()                                     // the program's ack lands after the act took the slice
            runEnds(r, "the act ends")
            r.recording = false
            // the order the cache writes reached RIGHT, from the act's start: mode, entries (offset+length)
            val order = right(r).mapNotNull { img -> cacheWrites(img).takeIf { it.isNotEmpty() }?.let { "${img[0].toInt() and 0x7F}:" + it.joinToString("/") { (o, n) -> "$o+$n" } } }
            val notice = slice
            r.until("the notice's programs counted again without an input", maxMs = 20_000) {
                r.onLoop { r.shell.noticeLaneDoneForTest() && r.shell.noticeProgramRecordsForTest().isNotEmpty() && !r.shell.cacheWorkInFlight } }
            r.settle("after")
            val owed = r.notes("transition").last { it.startsWith("owed: ") }
            assertTrue(owed.startsWith("owed: Select ${effect.replaceFirstChar { it.uppercase() }}"), "the act played $effect: $owed")
            assertEquals(effect == "encode", yielded, "the programs leave the books at an Encode act whose lists take the slice, not at Matrix's")
            val since = Files.readAllLines(r.journal).drop(lines0)
            // (c) the count: booked at the send, counted at the late ack, nothing judged short, over or re-based
            assertTrue(since.none { l -> listOf("count short", "count over", "never landed", "count re-based", "re-based the generation").any { it in l } },
                "the count stayed right: ${since.filter { "\"kind\":\"atlas\"" in it || "\"kind\":\"stage\"" in it }.map { it.substringAfter("\"detail\":\"").take(120) }}")
            assertEquals(0L, r.shell.cacheCountUnjudged, "every acked write judged")
            // (a) the books: every counted program is on both lenses byte for byte (the late ack marked nothing written)
            val progs = r.onLoop { r.shell.noticeProgramRecordsForTest() }
            for ((off, b, state) in progs) {
                assertEquals("PROVEN", state, "every notice program counted at rest: $progs")
                for (lens in listOf(r.sim.left, r.sim.right)) assertTrue(lens.textureCache!!.copyOfRange(off, off + b.size).contentEquals(b),
                    "the notice program at $off (${b.size} B) is on ${if (lens === r.sim.left) "LEFT" else "RIGHT"} byte for byte")
            }
            r.assertOracle("at rest after the act")
            // (a), at the PLAY: a notice after the act, once with each Popup style (the pick forced, as it is random among the
            // rows that are on), names counted programs whose bytes both lenses hold when it lands
            val counted = progs.associate { (off, b, _) -> off to b }
            val plays = java.util.concurrent.CopyOnWriteArrayList<String>()
            r.onImage = { a, img ->
                for (s in subs(img)) if (s.size >= 8 && s[0].toInt() == CfwModes.MOTION_MODE && s[1].toInt() == CfwModes.MOTION_PLAY && s[2].toInt() == 2 && u16(s, 6) >= 0xE000) {
                    val off = u16(s, 4) * 4
                    val want = counted[off]
                    val lens = if (a == Arm.LEFT) r.sim.left else r.sim.right
                    plays += "$a $off " + when {
                        want == null -> "names no counted program"
                        !lens.textureCache!!.copyOfRange(off, off + want.size).contentEquals(want) -> "names a program the lens does not hold"
                        else -> "ok"
                    }
                }
            }
            val styles = r.onLoop { r.shell.noticeProgramsForTest() }.map { it.substringBefore(' ') }
            val styleAt = progs.mapIndexed { i, (off, _, _) -> off to styles[i] }.toMap()
            for (arrival in listOf(wm.damage.core.shell.Notifications.Arrival.FADE, wm.damage.core.shell.Notifications.Arrival.SLIDE,
                    wm.damage.core.shell.Notifications.Arrival.NONE)) {
                r.shell.notifications.arrivalFor = { arrival }
                val p0 = plays.size
                r.shell.postNotice(wm.damage.core.shell.Notifications.Notice("SMS · TEST", "t", "a notice after the act (${arrival.name.lowercase()})", "12:00"))
                r.until("the $arrival box's timer PLAYed on both lenses", 10_000) {
                    plays.drop(p0).count { styleAt[it.split(' ')[1].toInt()] == "TIMER" } >= 2 }
                if (arrival != wm.damage.core.shell.Notifications.Arrival.NONE) assertTrue(plays.drop(p0).any { styleAt[it.split(' ')[1].toInt()] == arrival.name },
                    "the $arrival box played its arrival program: ${plays.drop(p0)}")
                r.until("the $arrival box's timer drew its last step", 10_000) { Arm.entries.all { a -> r.sim.motionForTest(a).slots[2]?.drawing != true } }
                r.settle("the $arrival timer's end")
                r.assertOracle("during the $arrival notice, its timer done")
                val t0 = System.currentTimeMillis()
                while (r.shell.notifications.current != null && System.currentTimeMillis() - t0 < 20_000) {
                    if (r.shell.notifications.focused && !r.shell.notifications.animating) r.shell.postGesture(EvenHubMsg.EV_CLICK)
                    delay(60)
                }
                assertEquals(null, r.shell.notifications.current, "the $arrival box dismissed")
                r.settle("the $arrival box gone")
            }
            assertTrue(plays.all { it.endsWith(" ok") }, "every notice PLAY names a counted program both lenses hold: $plays")
            assertTrue(r.notes("motion").none { "slot 2" in it && "refused" in it } && r.notes("divergence").isEmpty(),
                "no refusal of the notice's PLAY, no divergence: ${r.notes("motion").filter { "slot 2" in it }.takeLast(3)} ${r.notes("divergence")}")
            r.assertOracle("at rest after the three notices")
            println("T1 $effect: yielded=$yielded programs=${progs.size} notice slice $notice; RIGHT's cache writes from the act on: $order; plays=$plays")
        } finally { r.close() }
    }

    @Test fun aNoticeProgramsWriteInFlightWhenAnEncodeActTakesTheSlice_T1() = aNoticeProgramsWriteInFlightAtAnAct("encode")
    @Test fun aNoticeProgramsWriteInFlightAtAMatrixAct_T1() = aNoticeProgramsWriteInFlightAtAnAct("matrix")

    /**
     * 0.83 integration, part 2, item 3e: the scroll package's rules across two acts. Neither instrument drives this (the
     * stage-5 walk settles between gestures and fires nothing on the hook; the sequence fuzz's harness has no acts): a
     * burst of four notches on Main's bound list — each fired on both lenses' hooks and reported, its stock echo held back
     * — cut by a Select (the ring's tap: the hook and its click through the rules), the echoes then arriving late: one
     * right after the act (the out half drawing), one under the in half, one under the Back's own transition (Main in
     * front again, the run in hand: `FIRMWARE.md` §7.11) and one after the Back's run. Whatever the rules decide for each
     * (an owe pays it, it reaches the window, it is a new notch on Main — `LocalRules.front`'s carry rule, decisions BM.2,
     * BO.1, BR), at rest Main's row is the row their verdicts give (each Adopt's row, each scroll applied with Main in
     * front ±1, the tap's Act), and belief equals both lenses. Seeded: the transition pick (Fade), the rig's order.
     */
    @Test
    fun lateEchoesOfABurstCutByASelectLeaveMainOnTheRowTheRulesApplied_INT083(): Unit = runBlocking {
        val r = Rig(stage = 5, nRows = 8)
        try {
            r.up(only("fade"))
            r.shell.transitionRandom = java.util.Random(0x0083_3E5EL)
            val log = java.util.Collections.synchronizedList(ArrayList<String>())
            r.onLoop { r.shell.localVerdictsForTest = log }
            r.until("Main's scroll entries live on RIGHT") { r.sim.bindForTest(Arm.RIGHT).field39() and 0x30 == 0x30 }
            r.settle("before the burst")
            val r0 = r.row()
            val next = CfwModes.EV_UPROLL                                       // the next row (its stock message: SCROLL_BOTTOM)
            fun hook(eventId: Int) { val now = System.currentTimeMillis(); for (a in Arm.entries) r.sim.conformanceInput(a, CfwModes.DEV_RING, eventId.toLong(), 0, now) }
            fun echo(eventId: Int) = r.t.ringStockForTest(wm.damage.core.local.BoundList.stockEvent(eventId)!!)
            val phases = ArrayList<String?>()
            // the burst: four notches, 40 ms apart, their echoes held back by the band
            repeat(4) { hook(next); delay(40) }
            r.until("the burst's four reports read") { log.size >= 4 }
            // cut by a Select: the ring's tap on the row reached, the hook's report and its click through the rules
            hook(CfwModes.EV_TAP); r.t.ringStockForTest(EvenHubMsg.EV_CLICK)
            r.until("a window opens") { r.onLoop { r.shell.currentWindowId() } != null }
            phases += r.onLoop { r.shell.transitionPhaseForTest() }; echo(next)                     // 1: the out half drawing
            r.until("the in half") { r.onLoop { r.shell.transitionPhaseForTest() } == "IN" }
            val owed = r.notes("transition").last { it.startsWith("owed: ") }
            val p = Regex("at P (\\d+)").find(owed)!!.groupValues[1].toInt()
            val steps = Regex("(\\d+) steps a half").find(owed)!!.groupValues[1].toInt()
            delay((steps + 1) * p * 1000L / 1024)                               // past the out half's last step
            phases += r.onLoop { r.shell.transitionPhaseForTest() }; echo(next)                     // 2: under the in half
            runEnds(r, "the Select ends"); r.settle("in the window")
            r.doubleTap()                                                       // the Back
            r.until("Main in front, the Back's transition in hand") { r.onLoop { r.shell.currentWindowId() == null && r.shell.transitionPhaseForTest() != null } }
            phases += r.onLoop { r.shell.transitionPhaseForTest() }; echo(next)                     // 3: under the Back's run
            runEnds(r, "the Back ends")
            phases += r.onLoop { r.shell.transitionPhaseForTest() }; echo(next)                     // 4: after the Back's run
            r.settle("at rest")
            r.until("Main's list bound again") { r.onLoop { r.shell.mainBoundForTest().first } != null }
            r.settle("at rest, bound")
            val notes = r.notes("transition").filter { it.startsWith("owed: ") }
            assertTrue(notes.size == 2 && notes[0].startsWith("owed: Select") && notes[1].startsWith("owed: Back"), "two acts played: $notes")
            assertTrue(phases[0] != null && phases[1] == "IN" && phases[2] != null && phases[3] == null, "where the echoes arrived: $phases")
            assertTrue(r.notes("transition").none { " ended in " in it }, "both acts ran whole: ${r.notes("transition")}")
            // the row the rules' verdicts give, in their order (only Main's moves: a window's own notch moves the window)
            var row = r0
            val verdicts = synchronized(log) { log.toList() }
            for (v in verdicts) {
                val mode = v.substringBefore(' ')
                Regex("Adopt\\(row=(\\d+)\\)").find(v)?.let { row = it.groupValues[1].toInt() }
                Regex("Act\\(row=(\\d+)\\)").find(v)?.let { if (mode == "MAIN") row = it.groupValues[1].toInt() }
                Regex("Apply\\(type=(\\d+)\\)").find(v)?.let { if (mode == "MAIN") when (it.groupValues[1].toInt()) {
                    EvenHubMsg.EV_SCROLL_BOTTOM -> row++
                    EvenHubMsg.EV_SCROLL_TOP -> row-- } }
            }
            println("0.83 integration 3e: from row $r0, verdicts $verdicts; echoes arrived at $phases; counters ${r.onLoop { r.shell.localCountsForTest() }}")
            assertTrue(verdicts.count { "Adopt" in it } >= 1, "the burst's notches were adopted: $verdicts")
            assertEquals(row, r.row(), "Main's row at rest is the row the rules applied (from $r0): $verdicts")
            r.assertOracle("at rest")
        } finally { r.close() }
    }

    /**
     * 0.83 integration, part 2, item 3d (slot 2 is K1-4a's notice timer's and a transition's out half's, at different
     * times): the two draw their tags from ranges of their own. The transport's mirror keeps one END record per (slot,
     * tag) for the session (build C1's B-N11: a tag is one play's); with one range (0xC000 + a counter each) the
     * session's first act inside its first notice's grace — both counters at 0 — had its out PLAY write over the live
     * timer's record, the timer's closed body then read as a divergence, and its keyframe cut the act after one step.
     * RIGHT's answers are held back 30 ms in order, as a link's round trip does (the mirror takes a PLAY before its body can
     * come back; with none the body was compared first and the defect hid). The act runs whole, no divergence, no keyframe.
     */
    @Test
    fun aNoticeTimerThenTheSessionsFirstActRunsWhole_INT083(): Unit = runBlocking {
        val r = Rig(stage = 5, kib256 = true)
        try {
            r.up()
            val q = kotlinx.coroutines.channels.Channel<Pair<Long, () -> Unit>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            r.scope.launch { for ((due, f) in q) { val w = due - System.currentTimeMillis(); if (w > 0) delay(w); f() } }
            val link = r.t as StartRig
            link.onNotify = { arm, p -> q.trySend(System.currentTimeMillis() + 30 to { link.deliver(arm, p) }); null }
            r.until("the notice's programs counted") { r.onLoop { r.shell.noticeLaneDoneForTest() && r.shell.noticeProgramRecordsForTest().isNotEmpty() } }
            r.settle("before the notice")
            val kf0 = keyframesIn(r)
            r.shell.postNotice(wm.damage.core.shell.Notifications.Notice("SMS · TEST", "t", "a notice before the act", "12:00"))
            r.until("the notice's timer in slot 2 on both lenses", 10_000) { r.sim.motionForTest(Arm.RIGHT).slots[2] != null && r.sim.motionForTest(Arm.LEFT).slots[2] != null }
            val noticeTag = r.sim.motionForTest(Arm.RIGHT).slots[2]!!.tag
            r.tap()                                                             // the Select, inside the timer's grace
            r.until("a window opens") { r.onLoop { r.shell.currentWindowId() } != null }
            runEnds(r, "the Select ends")
            r.settle("after")
            val owed = r.notes("transition").last { it.startsWith("owed: ") }
            assertTrue(owed.startsWith("owed: Select") && "tags out 0xc000 " in owed, "precondition: the session's first act ($owed)")
            assertEquals(emptyList(), r.notes("divergence"), "no divergence (the notice's tag 0x%04x)".format(noticeTag))
            assertEquals(kf0, keyframesIn(r), "no keyframe")
            assertTrue(inHalfWhole(r) && r.notes("transition").none { " ended in " in it }, "the act ran whole: ${r.notes("transition")}")
            // the box comes back after the run with its timer on the engine: the glass shows the timer's steps where belief
            // holds its END (`FIRMWARE.md` §5) until the last one — the oracle reads after it, as `MainLocalShellTest`'s does
            r.until("the returned box's timer drew its last step", 10_000) { Arm.entries.all { a -> r.sim.motionForTest(a).slots[2]?.drawing != true } }
            r.settle("the timer's end")
            r.assertOracle("after the act")
        } finally { r.close() }
    }
}
