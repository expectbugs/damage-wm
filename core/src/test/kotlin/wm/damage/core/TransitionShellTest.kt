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
 * The first pin is taken on the tree as it came from APK 0.82, before any of DP2 was built: without stage 5 a Select, a
 * Back and the silent clock's enter and exit write exactly 0.82's bytes.
 */
class TransitionShellTest {

    private class Row(id: String, name: String, var s: DamageWindow.Summary) : DamageWindow(id, name, IconKind.FILES) {
        /** A mark in the window's picture a test may move (DP2: a write that is not his input). */
        @Volatile var mark = 0
        /** A busy picture (a level per 4 × 2 block): its frame takes several flushes. */
        @Volatile var noise = false
        /** X2P2: a 120 × 40 box at full level in the middle of the content — what a scroll in this window (his input) or
         *  a test (the window's own repaint) puts up. */
        @Volatile var big = false
        override fun view(): WindowView = WindowView.CanvasView({ g, r ->
            g.fillRect(r, 0)
            if (noise) { val rnd = java.util.Random(7); for (y in r.y until r.bottom - 1 step 2) for (x in r.x until r.right - 3 step 4) g.fillRect(x, y, 4, 2, rnd.nextInt(16) * 17) }
            for (k in 0 until 6) g.fillRect(r.x + 8 + k * (r.w - 24) / 5, r.y + 6 + k * (r.h - 20) / 5, 8, 8, (6 + k) * 17)
            if (mark != 0) g.fillRect(r.x + 40 + mark * 8, r.y + r.h / 2, 24, 12, 15 * 17)
            if (big) g.fillRect(r.x + r.w / 2 - 60, r.y + r.h / 2 - 20, 120, 40, 15 * 17)
        }, onScroll = { big = true })
        override fun summary() = s
        override fun saveState(): JsonObject = JsonObject(emptyMap())
        override fun restoreState(state: JsonObject) {}
    }

    private class Rig(val features: Int = 0xdff, val stage: Int = 4, val taps: Taps = Taps.REPORT, nRows: Int = 5) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp = Files.createTempDirectory("damage-transition")
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = features }
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        val journal = tmp.resolve("journal.jsonl")
        val shell = Shell(CachedText(FakeText()), t, Persistence(tmp.resolve("state.json")), journal, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
        val rows = (0 until nRows).map { Row("w$it", "Win $it", DamageWindow.Summary("summary $it", detail = "detail of $it")) }
        /** Every top-level image a lens is handed, in order, while [recording]. */
        val images = java.util.concurrent.CopyOnWriteArrayList<String>()
        /** Every top-level image a lens is handed from the rig's start — the session start included (X2P2 stage 2). */
        val allImages = java.util.concurrent.CopyOnWriteArrayList<String>()
        @Volatile var recording = false
        @Volatile var onImage: ((Arm, ByteArray) -> Unit)? = null

        init {
            for (r in rows) shell.register(r)
            t.devProbe("c1", "$stage")
            sim.imageObserver = { a, img -> onImage?.invoke(a, img)
                val line = "${a.name.first()} " + img.joinToString("") { "%02x".format(it) }
                allImages += line; if (recording) images += line }
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

        /** A ring gesture: the hook on both lenses, then its stock message through the transport. */
        fun fire(eventId: Int) {
            val now = System.currentTimeMillis()
            for (a in Arm.entries) sim.conformanceInput(a, CfwModes.DEV_RING, eventId.toLong(), 0, now)
            t.injectInput(BoundList.stockEvent(eventId)!!)
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
        // every run's recording kept beside the build's results, for a reader of a failure
        Paths.get("build", "transitions").also { Files.createDirectories(it) }.resolve("got-${System.nanoTime()}.txt").let { Files.write(it, got) }
        assertEquals(w.size, g.size, "$what: drawing image count against $name")
        for (i in w.indices) assertEquals(w[i], g[i], "$what: drawing image $i differs from $name")
    }

    /**
     * The images as the pin compares them, measured on the recordings of the tree as it came from 0.82 (five runs: the
     * same drawing bytes every time, two readings that are not the act's varying): each image a lens took as its
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
     * write exactly APK 0.82's bytes (the file written by this test's first run, on the tree as it came from 0.82 before
     * DP2's code, and the same again on a second run); no mode-26 sub 8 and no version-2 record among them — read raw in
     * every image, inside every cache write too (X2P2 item 5), over the WHOLE session from its start (X2P2 stage 2: Main's
     * staging and the atlas's first uploads go before `up()` returns; the comparison itself stays over the four acts).
     */
    @Test
    fun withoutStage5TheFourActsWrite082sBytes_DP2(): Unit = runBlocking {
        val r = Rig(stage = 4)
        try {
            r.up()
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
            assertWire("acts-0xdff-stage4.txt", got, "0xdff stage 4")
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
            assertTrue(r.notes("transition").any { "no in-half body by its nominal end plus one period" in it }, "${r.notes("transition")}")
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

    // ------------------------------------------------------- X2P2 stage 2 item 1: no wrap PLAY under a transition
    /**
     * The second review pass's finding 1, ruled (`FIRMWARE.md` §7.11 at `81faf329`): a Back into Main from w0 (Main's first
     * row) at height 480 with twelve windows — Main's list wraps there and is no ring list — then a notch past row 0 while
     * the Back's transition is in hand: in its frame phase ([frame]: Main's frame in several flushes, the in half held back
     * meanwhile) or inside its in half (RIGHT's in half drawing). The edge is answered as today without its wrap motion: no
     * slot-1 PLAY from the Back's out PLAY to its run's end, both halves drew their last step, the wrapped row on both
     * lenses at the in half's LAND, belief equals both lenses at rest; and the first edge notch after the run's end plays
     * its wrap as before.
     */
    private suspend fun edgeUnderABack(frame: Boolean) {
        val r = Rig(stage = 5, nRows = 12)
        try {
            r.up { it.copy(heightMode = 480) }
            assertTrue(r.onLoop { r.shell.mainBinding.staging?.wraps == true }, "Main's list wraps at 480 with twelve windows")
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
            if (frame) {
                val imgs = right(r)
                val out = imgs.indexOfFirst { img -> subs(img).any { playIn(it, 2) } }
                val frameImgs = imgs.drop(out + 1).filter { img -> subs(img).any { drawing(it) } }
                assertTrue(frameImgs.size >= 2, "Main's frame took more than one flush: ${frameImgs.map { it.size }} B")
                println("X2P2 stage 2: Main's frame under the Back in ${frameImgs.size} flushes, ${frameImgs.map { it.size }} B")
                r.onLoop { r.shell.comp.batchMax = batchMax }
                for (left in listOf(true, false)) againstTruth(r, left, shadow = true, area = r.onLoop { r.shell.layout.content })?.let { fail("Main's frame whole in the shadow before the notch: $it") }
            }
            r.fire(CfwModes.EV_DOWNROLL)
            r.until("the edge answered: the row is Main's last") { r.row() == count - 1 }
            // the edge's repaint (held) whole in both shadows, the run still in hand and its in half still to land
            val content = r.onLoop { r.shell.layout.content }
            r.until("the wrapped row in both shadows") {
                r.onLoop { !r.shell.comp.hasPending && "slides " !in r.shell.quiescenceReport() && "inflight=" !in r.shell.quiescenceReport() } &&
                    listOf(true, false).all { againstTruth(r, it, shadow = true, area = content) == null } }
            if (frame) {
                assertEquals("FRAME", r.onLoop { r.shell.transitionPhaseForTest() }, "still the frame phase when the edge's row is in the shadow")
                r.shell.transitionHoldInHalfForTest = false; r.shell.postIdleTick()
            } else {
                for (a in Arm.entries) assertTrue(r.sim.motionForTest(a).slots[3] != null, "$a: the in half's slot was emptied — closed — under the edge")
                assertTrue(Arm.entries.all { r.sim.motionForTest(it).slots[3]?.drawing == true },
                    "precondition: both in halves still drawing when the edge's row was in the shadow (a run too loaded to exercise the hold)")
            }
            r.until("both lenses' in halves landed") { Arm.entries.all { r.sim.motionForTest(it).slots[3]?.let { s -> !s.drawing && s.d >= 1 } == true } }
            delay(20)
            for (left in listOf(true, false)) againstTruth(r, left, shadow = false, area = content)?.let { fail("the wrapped row at the in half's LAND: $it") }
            r.until("the Back's run ends") { r.onLoop { r.shell.transitionPhaseForTest() } == null }
            r.until("the Back's CLOSE of slot 2 reaches RIGHT") { right(r).count { img -> subs(img).let { it.size == 1 && closeOf(it[0], 2) } } >= 1 }
            r.settle("after the Back")
            noCloseUnderTheRun(r, act = 0, alsoNot = { playIn(it, MainStaging.WRAP_SLOT) })
            bothHalvesWhole(r, act = 1)
            assertTrue(r.notes("local").any { it.startsWith("an edge at Main's end under Back's transition (${if (frame) "FRAME" else "IN"})") }, "${r.notes("local").takeLast(4)}")
            r.assertOracle("at rest after the edge under the Back")
            // the first edge notch after the run's end plays its wrap as before
            val at = right(r).size
            r.fire(CfwModes.EV_UPROLL)
            r.until("the wrap's PLAY on RIGHT") { right(r).drop(at).any { img -> subs(img).any { playIn(it, MainStaging.WRAP_SLOT) } } }
            r.until("the row is Main's first again") { r.row() == 0 }
            r.settle("after the wrap")
            r.recording = false
            assertTrue(r.notes("local").any { it.startsWith("the wrap's PLAY went ahead of its frame") }, "${r.notes("local").takeLast(4)}")
            r.assertOracle("after the wrap")
        } finally { r.close() }
    }

    @Test
    fun anEdgeNotchInABacksFramePhaseSendsNoWrapPlay_X2P2(): Unit = runBlocking { edgeUnderABack(frame = true) }

    @Test
    fun anEdgeNotchInABacksInHalfSendsNoWrapPlay_X2P2(): Unit = runBlocking { edgeUnderABack(frame = false) }

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
     * both lenses; no keyframe goes.
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
            assertTrue(imgs.drop(pa).none { img -> subs(img).any { (it[0].toInt() and 0x7F) == 6 } }, "no keyframe")
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
}
