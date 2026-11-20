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
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ListModel
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
 * The first pin is taken on the tree as it came from APK 0.82, before any of DP2 was built: without stage 5 a Select, a
 * Back and the silent clock's enter and exit write exactly 0.82's bytes.
 */
class TransitionShellTest {

    private class Row(id: String, name: String, var s: DamageWindow.Summary) : DamageWindow(id, name, IconKind.FILES) {
        /** A mark in the window's picture a test may move (DP2: a write that is not his input). */
        @Volatile var mark = 0
        /** A busy picture (a level per 4 × 2 block): its frame takes several flushes. */
        @Volatile var noise = false
        override fun view(): WindowView = WindowView.CanvasView({ g, r ->
            g.fillRect(r, 0)
            if (noise) { val rnd = java.util.Random(7); for (y in r.y until r.bottom - 1 step 2) for (x in r.x until r.right - 3 step 4) g.fillRect(x, y, 4, 2, rnd.nextInt(16) * 17) }
            for (k in 0 until 6) g.fillRect(r.x + 8 + k * (r.w - 24) / 5, r.y + 6 + k * (r.h - 20) / 5, 8, 8, (6 + k) * 17)
            if (mark != 0) g.fillRect(r.x + 40 + mark * 8, r.y + r.h / 2, 24, 12, 15 * 17)
        })
        override fun summary() = s
        override fun saveState(): JsonObject = JsonObject(emptyMap())
        override fun restoreState(state: JsonObject) {}
    }

    private class Rig(val features: Int = 0xdff, val stage: Int = 4, val taps: Taps = Taps.REPORT) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp = Files.createTempDirectory("damage-transition")
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = features }
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        val journal = tmp.resolve("journal.jsonl")
        val shell = Shell(CachedText(FakeText()), t, Persistence(tmp.resolve("state.json")), journal, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
        val rows = (0 until 5).map { Row("w$it", "Win $it", DamageWindow.Summary("summary $it", detail = "detail of $it")) }
        /** Every top-level image a lens is handed, in order, while [recording]. */
        val images = java.util.concurrent.CopyOnWriteArrayList<String>()
        @Volatile var recording = false
        @Volatile var onImage: ((Arm, ByteArray) -> Unit)? = null

        init {
            for (r in rows) shell.register(r)
            t.devProbe("c1", "$stage")
            sim.imageObserver = { a, img -> onImage?.invoke(a, img); if (recording) images += "${a.name.first()} " + img.joinToString("") { "%02x".format(it) } }
            scope.launch { while (true) { val now = System.currentTimeMillis(); for (a in Arm.entries) sim.conformanceWake(a, now); delay(4) } }
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

        suspend fun up(settings: (wm.damage.core.shell.ShellSettings) -> wm.damage.core.shell.ShellSettings = { it }) {
            shell.start(); settle("start")
            shell.updateSettings { settings(it.copy(heightMode = 352, cachedText = "on", taps = taps)) }
            until("the atlas goes live") { shell.cachedTextActive && shell.cachedFontsLive.isNotEmpty() }
            until("Main's list bound") { onLoop { shell.mainBoundForTest().first } != null }
            settle("up")
        }

        /** A ring tap: the hook on both lenses, then its stock message through the transport. */
        fun tap() {
            val now = System.currentTimeMillis()
            for (a in Arm.entries) sim.conformanceInput(a, CfwModes.DEV_RING, CfwModes.EV_TAP.toLong(), 0, now)
            t.injectInput(BoundList.stockEvent(CfwModes.EV_TAP)!!)
        }

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

        /** Belief equals both lenses' panels, read ON the loop with nothing else pending. */
        suspend fun assertOracle(what: String) {
            var diff: String? = null
            var read = false
            val deadline = System.currentTimeMillis() + 30_000
            while (!read && System.currentTimeMillis() < deadline) {
                if (!shell.isQuiescent()) { delay(5); continue }
                val r = shell.sampleIdle {
                    val out = StringBuilder()
                    for (left in listOf(true, false)) {
                        val b = shell.comp.expectedLens(left); val g = panel(left)
                        loop@ for (y in 0 until Geometry.PANEL_H) for (x in 0 until Geometry.PANEL_W)
                            if (wm.damage.core.gfx.Pack.level(b[x, y]) * 17 != g[x, y]) { out.append("${if (left) "LEFT" else "RIGHT"} belief != glass at ($x,$y); "); break@loop }
                    }
                    out.toString()
                }
                if (r != null) { read = true; diff = r }
            }
            assertTrue(read, "$what: the shell never held still to be sampled — ${shell.quiescenceReport()}")
            assertTrue(diff.isNullOrEmpty(), "$what: $diff")
        }

        fun close() { scope.cancel(); tmp.toFile().deleteRecursively() }
    }

    private fun file(name: String): List<String>? = javaClass.getResource("/transitions/$name")?.readText()?.lines()?.filter { it.isNotBlank() }

    private fun assertWire(name: String, got: List<String>, what: String) {
        val want = file(name) ?: run {
            val out = Paths.get("build", "transitions").also { Files.createDirectories(it) }.resolve(name)
            Files.write(out, got)
            fail("no expectation for $name: this run's ${got.size} images written to ${out.toAbsolutePath()}")
        }
        val (w, g) = normal(want) to normal(got)
        assertEquals(w.size, g.size, "$what: drawing image count against $name")
        for (i in w.indices) assertEquals(w[i], g[i], "$what: drawing image $i differs from $name")
    }

    /**
     * The images as the pin compares them, measured on the recordings of the tree as it came from 0.82 (five runs: the
     * same drawing bytes every time, two readings that are not the act's varying): each image a lens took as its
     * sub-messages (a lone image is one), less (1) an image holding only the rules' mode-26/27 messages — answers,
     * SETTLEs, a table repair's re-send to RIGHT — whose moment follows the telemetry reads (in two runs of five one of
     * them fell inside the recording), and (2) a text draw holding "K/s", the link cell's measured throughput. What is not
     * compared here is checked raw by the test: no C3 form at all.
     */
    private fun normal(images: List<String>): List<String> = images.mapNotNull { line ->
        val ss = subs(bytes(line.substring(2)))
        if (ss.all { (it[0].toInt() and 0x7F) == 26 || (it[0].toInt() and 0x7F) == 27 }) null
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
     * write exactly APK 0.82's bytes (the file written by this test's first run, on the tree as it came from 0.82 before
     * DP2's code, and the same again on a second run); no mode-26 sub 8 and no version-2 record among them.
     */
    @Test
    fun withoutStage5TheFourActsWrite082sBytes_DP2(): Unit = runBlocking {
        val r = Rig(stage = 4)
        try {
            r.up()
            val got = fourActs(r)
            // raw, every image: nothing of C3 — no PLAY-AFTER, no PLAY into a transition's slots, no LIST-RING
            for (line in got) for (s in subs(bytes(line.substring(2)))) {
                assertTrue(!playAfter(s) && !playIn(s, 2) && !playIn(s, 3) && !isSub(s, 27, CfwModes.BIND_LIST_RING), "a C3 form below stage 5: $line")
            }
            assertWire("acts-0xdff-stage4.txt", got, "0xdff stage 4")
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
    private fun closeOf(s: ByteArray, slot: Int) = isSub(s, 26, CfwModes.MOTION_CLOSE) && (s[2].toInt() and 0xFF) == slot
    private fun playIn(s: ByteArray, slot: Int) = isSub(s, 26, CfwModes.MOTION_PLAY) && (s[2].toInt() and 0xFF) == slot
    private fun playAfter(s: ByteArray) = isSub(s, 26, CfwModes.MOTION_PLAY_AFTER)

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
     * PLAY-AFTER (slot 3, `after` 2) after its drawing subs and no drawing image after that until the in half ends; then a
     * CLOSE of slot 2 alone (§7.11). The frame is not on RIGHT's panel when the PLAY-AFTER's image reaches it, and after
     * the in half belief equals both lenses.
     */
    @Test
    fun aSelectAtStage5IsAHeldTransition_DP2(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.up()
            r.settle("up")
            var panelAtPlayAfter: Gray8? = null
            r.onImage = { a, img -> if (a == Arm.RIGHT && panelAtPlayAfter == null && subs(img).any { playAfter(it) }) panelAtPlayAfter = r.panel(false) }
            r.recording = true
            selectAndSettle(r)
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
            assertTrue(imgs.subList(pa + 1, close).none { img -> subs(img).any { drawing(it) } }, "nothing drawn between the PLAY-AFTER and the CLOSE")
            assertTrue(r.notes("transition").any { it.startsWith("in half's last step drawn on RIGHT") }, "${r.notes("transition")}")
            r.assertOracle("after the Select")
            val final = r.panel(false)
            val seen = assertNotNull(panelAtPlayAfter, "RIGHT saw the PLAY-AFTER")
            assertTrue(!seen.pix.contentEquals(final.pix), "the window's frame was not on RIGHT's panel before its in half")
            println("DP2 Select at stage 5: ${r.notes("transition").joinToString("\n")}")
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
            r.recording = false
            assertTrue(r.notes("transition").any { "no in-half body by its nominal end plus one period" in it }, "${r.notes("transition")}")
            val imgs = right(r)
            assertTrue(imgs.any { img -> subs(img).let { s -> s.size == 2 && closeOf(s[0], 2) && closeOf(s[1], 3) } }, "CLOSE 2 and CLOSE 3 in one image")
            val t = wm.damage.core.motion.Transitions.timing(450, 10)
            assertTrue(took >= 2 * t.halfMs, "the deadline is past the nominal end: $took ms")
            r.assertOracle("after the deadline")
        } finally { r.close() }
    }

    /** K1 §9: a notice that arrives during a transition waits until its end, then shows. */
    @Test
    fun aNoticeWaitsBehindATransition_DP2(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.up()
            r.tap()
            r.until("the out half sent") { r.onLoop { r.shell.transitionPhaseForTest() }.let { it == "FRAME" || it == "IN" } }
            r.shell.postNotice(wm.damage.core.shell.Notifications.Notice("SMS · test", "sms", "a message", "12:00"))
            var shownDuring = false
            while (true) {
                val (phase, up) = r.onLoop { r.shell.transitionPhaseForTest() to r.shell.notificationsUpForTest() }
                if (phase != null && up) shownDuring = true
                if (phase == null) break
                delay(5)
            }
            assertTrue(!shownDuring, "the notice did not show during the transition")
            r.until("the notice shows after it") { r.onLoop { r.shell.notificationsUpForTest() } }
        } finally { r.close() }
    }

    /** DP2 item 1: in the in half a write that is not his input (the window repaints by itself) waits for the
     *  transition's end — no drawing image between the PLAY-AFTER and the CLOSE that ends it — and then goes. */
    @Test
    fun aWriteThatIsNotInputWaitsForTheInHalf_DP2(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.up()
            r.recording = true
            r.tap()
            r.until("the in half sent") { r.onLoop { r.shell.transitionPhaseForTest() } == "IN" }
            r.rows[0].mark = 3
            r.shell.services.requestRender(r.rows[0])
            r.until("the transition ends") { r.onLoop { r.shell.transitionPhaseForTest() } == null }
            r.settle("after")
            r.recording = false
            val imgs = right(r)
            val pa = imgs.indexOfFirst { img -> subs(img).any { playAfter(it) } }
            val close = imgs.indexOfFirst { img -> subs(img).any { closeOf(it, 2) } }
            assertTrue(pa in 0 until close, "PLAY-AFTER $pa, then the CLOSE $close")
            assertTrue(imgs.subList(pa + 1, close).none { img -> subs(img).any { drawing(it) } }, "the window's own repaint waited")
            assertTrue(imgs.drop(close + 1).any { img -> subs(img).any { drawing(it) } }, "and went after")
            r.assertOracle("after")
        } finally { r.close() }
    }

    /** DP2 items 1 and 6: a frame of several flushes — every one held (no CLOSE of slot 2), the PLAY-AFTER on the last
     *  only (no drawing image after it before the CLOSE that ends the transition), and nothing of the frame on RIGHT's
     *  panel when the PLAY-AFTER reaches it. */
    @Test
    fun aSeveralFlushFrameShowsNothingBeforeItsInHalf_DP2(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.up()
            r.rows[0].noise = true
            var panelAtPlayAfter: Gray8? = null
            r.onImage = { a, img -> if (a == Arm.RIGHT && panelAtPlayAfter == null && subs(img).any { playAfter(it) }) panelAtPlayAfter = r.panel(false) }
            r.recording = true
            selectAndSettle(r)
            r.recording = false
            val imgs = right(r)
            val out = imgs.indexOfFirst { img -> subs(img).any { playIn(it, 2) } }
            val pa = imgs.indexOfFirst { img -> subs(img).any { playAfter(it) } }
            val close = imgs.indexOfFirst { img -> subs(img).any { closeOf(it, 2) } }
            val frame = (out + 1..pa).filter { i -> subs(imgs[i]).any { drawing(it) } }
            assertTrue(frame.size >= 2, "the frame took ${frame.size} flush(es) — the test needs several")
            assertEquals(pa, frame.last(), "the PLAY-AFTER rides the frame's last flush")
            val paSubs = subs(imgs[pa])
            assertTrue(paSubs.indexOfFirst { playAfter(it) } > paSubs.indexOfLast { drawing(it) }, "after its drawing subs")
            assertTrue(imgs.subList(out, pa + 1).none { img -> subs(img).any { closeOf(it, 2) || closeOf(it, 0xFF) } }, "every frame flush held")
            assertTrue(close > pa && imgs.subList(pa + 1, close).none { img -> subs(img).any { drawing(it) } })
            r.assertOracle("after")
            val seen = assertNotNull(panelAtPlayAfter)
            val final = r.panel(false)
            val c = r.onLoop { r.shell.layout.content }
            var same = 0
            for (y in c.y until c.bottom) for (x in c.x until c.right) if (seen[x, y] == final[x, y] && final[x, y] != 0) same++
            assertTrue(same < c.w * c.h / 20, "the frame's busy picture was not on RIGHT's panel before the in half ($same px of it already there)")
        } finally { r.close() }
    }
}
