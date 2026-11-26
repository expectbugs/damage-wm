package wm.damage.core

import java.nio.file.Files
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
import wm.damage.core.shell.DocModel
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.Notifications
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellSettings
import wm.damage.core.shell.StagedRecord
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.effects
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.EvenHubMsg

/**
 * C3 stage DW-1b-1 (`private/c3-design/C3-DW1-amendment.md` §3.3, §4.1, §4.5, §5, §7.1; the DW spec §4.2–§4.4, §7.1;
 * decisions CK, CZ, DA): the switcher's wheel bound on the glasses as list 2 in slot 0 — the offer, the open's and the
 * close's batches, the adopted notch, the tap on the lens's row, the preview's wait, Page staging's gate, the lane's writes
 * in an unbound open, Main's staging waiting while the wheel is bound, the window list's yield, and the envelope's cases.
 * Context for the reader: Adam's own glasses on his own firmware fork; nothing here contacts a device — the glasses are the
 * firmware model behind [StartRig] on a bit-11 build (DamageCaps 0xdff) at the ladder's stage 5 on a 256 KiB session, its
 * engine woken every 4 ms. A gesture on the glasses is fired through the input hook on both lenses, its stock message
 * reaching the shell through the transport after the 115 (§6.1, P2), as `WindowListShellTest` does for list 1.
 */
class WheelBindTest {
    /** A window in Main's list whose own view is a list of [n] rows. */
    private class Win(id: String, name: String, @Volatile var n: Int = 4) : DamageWindow(id, name, IconKind.FILES) {
        val model = ListModel()
        @Volatile var heavy = false
        override fun view(): WindowView = WindowView.ListView(model, { n },
            { g, i, r, _ -> for (k in 0..(i % 5)) g.fillRect(r.x + 12 + k * 22, r.y + 6, 14, 12, 120 + 20 * (k % 4)) },
            { g, r, i -> g.fillRect(r.x + 4, r.y + 4, 8 + (i * 13) % 200, r.h - 8, 255) }, {})
        override fun summary() = if (heavy) Summary("M@W#".repeat(24), detail = "detail of $name", more = true) else Summary("summary of $name", detail = "detail")
        override fun saveState(): JsonObject = JsonObject(emptyMap())
        override fun restoreState(state: JsonObject) {}
    }

    /** A document of two hundred lines (Page staging stages its strips ahead while it is in front; with [key] null it
     *  stages nothing, as every `DocView` but Reader's). */
    private class Doc(private val key: Any? = "doc") : DamageWindow("doc", "Doc", IconKind.READER) {
        val model = DocModel()
        override fun view() = WindowView.DocView(model, { 200 }, 24,
            { g, i, r -> for (k in 0..(i % 7)) g.fillRect(r.x + 6 + k * 30, r.y + 5, 20 + (i * 3) % 7, 12, 90 + 25 * ((i + k) % 6)) },
            {}, stepLines = { 3 }, contentKey = { key })
        override fun summary() = Summary("doc")
        override fun saveState(): JsonObject = JsonObject(emptyMap())
        override fun restoreState(state: JsonObject) {}
    }

    private class Rig(stage: Int = 5, kib: Int = 256, nRows: Int = 3, text: wm.damage.core.text.TextRasterizer = FakeText(),
                      val extra: List<DamageWindow> = emptyList(), names: (Int) -> String = { "W$it" }, taps: Taps = Taps.REPORT,
                      tweak: (ShellSettings) -> ShellSettings = { it }) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp = Files.createTempDirectory("damage-wheelbind")
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0xdff }
        val t = StartRig(sim, scope, c1StageFile = tmp.resolve("c1-stage").also {
            Files.writeString(it, wm.damage.core.transport.LadderRecord(stage = stage, sessionKib = kib).encode()) })
        val journal = tmp.resolve("journal.jsonl")
        val persistence = Persistence(tmp.resolve("state.json")).also { p ->
            p.put("shell.settings", tweak(ShellSettings().copy(heightMode = 352, cachedText = "on", taps = taps)).toJson())
        }
        val shell = Shell(CachedText(text), t, persistence, journal, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
        val rows = (0 until nRows).map { Win("w$it", names(it)) }
        /** Every image either lens took, in order; RIGHT's alone. */
        val images = java.util.concurrent.CopyOnWriteArrayList<Pair<Arm, ByteArray>>()
        val right = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        /** C3 DW-2: when each of [images] was taken (ms, the PC's clock), index for index. */
        val imageMs = java.util.concurrent.CopyOnWriteArrayList<Long>()
        /** The registered windows the wheel's cells are for, as ids (null: Main's entry; Settings is registered at the start). */
        val regs: List<String?> get() = listOf<String?>(null) + rows.map { it.id } + extra.map { it.id } + "settings"

        init {
            for (r in rows + extra) shell.register(r)
            sim.imageObserver = { a, img ->
                synchronized(images) { images += a to img.copyOf(); imageMs += System.currentTimeMillis() }
                if (a == Arm.RIGHT) right += img.copyOf()
            }
            scope.launch { while (true) { val now = System.currentTimeMillis(); for (a in Arm.entries) sim.conformanceWake(a, now); delay(4) } }
        }

        fun notes(kind: String): List<String> = if (!Files.exists(journal)) emptyList() else Files.readAllLines(journal)
            .filter { "\"ev\":\"note\",\"kind\":\"$kind\"" in it }.map { it.substringAfter("\"detail\":\"").substringBefore("\"") }

        suspend fun until(what: String, maxMs: Long = 60_000, cond: () -> Boolean) =
            waitUntil(maxMs, 5, { "$what — wheel ${notes("wheel").takeLast(4)}; mainstage ${notes("mainstage").takeLast(2)}; " +
                "local ${notes("local").takeLast(3)}; ${shell.quiescenceReport()}" }, cond)

        suspend fun settle(what: String) = until("$what: settled") { shell.isQuiescent() }

        fun <T> onLoop(block: () -> T): T {
            val out = java.util.concurrent.CompletableFuture<T>()
            shell.services.runOnShell { out.complete(block()) }
            return out.get(10, java.util.concurrent.TimeUnit.SECONDS)
        }

        /** Every lane done (one evaluation on the loop), then quiescent. */
        suspend fun done(what: String) {
            until("$what: lanes done") { shell.isQuiescent() && onLoop { shell.noticeLaneDoneForTest() && shell.wheelLaneDoneForTest() &&
                shell.windowListLaneDoneForTest() && !shell.cacheWorkInFlight && shell.cacheCountUnjudged == 0L && shell.transitionPhaseForTest() == null } }
            settle(what)
        }

        suspend fun up() {
            shell.start()
            until("the atlas goes live") { shell.cachedTextActive && shell.cachedFontsLive.isNotEmpty() }
            done("the start")
        }

        suspend fun open(id: String) {
            onLoop { shell.services.openWindow(id, null) }
            until("$id in front") { onLoop { shell.currentWindowId() } == id }
            done("open $id")
        }

        /** Back to Main by the double tap (a window's root returns to its caller, then to Main). */
        suspend fun toMain() {
            repeat(4) {
                if (onLoop { shell.currentWindowId() } == null) return
                shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
                done("a double tap toward Main")
            }
            assertEquals(null, onLoop { shell.currentWindowId() }, "back in Main")
        }

        fun chord() { for (g in listOf(EvenHubMsg.EV_RING_LONG_PRESS, EvenHubMsg.EV_RING_LONG_PRESS_RELEASE, EvenHubMsg.EV_DOUBLE_CLICK)) shell.postGesture(g) }

        /** The wheel bound on RIGHT: slot 0 is the wheel's, and both lenses hold its two scroll entries (list 2). */
        suspend fun bound(what: String) {
            until("$what: the wheel bound on both lenses") { onLoop { shell.slot0ForTest() == "wheel" && shell.wheelBoundForTest().first != null } &&
                Arm.entries.all { a -> entriesOn(a, 2).containsAll(listOf(6, 7)) } }
            settle(what)
        }

        fun list(): BoundList = assertNotNull(onLoop { shell.wheelBoundForTest().first }, "the wheel offered")
        fun row(): Int = onLoop { shell.wheelBoundForTest().second }
        fun q(): Long = onLoop { shell.localQForTest() }
        fun block(): wm.damage.core.motion.Wheel.Block? = onLoop { shell.wheelBlockForTest() }

        fun fire(eventId: Int): Int {
            val now = System.currentTimeMillis()
            for (a in Arm.entries) sim.conformanceInput(a, CfwModes.DEV_RING, eventId.toLong(), 0, now)
            t.ringStockForTest(BoundList.stockEvent(eventId)!!)
            return sim.bindForTest(Arm.RIGHT).seq
        }

        fun entriesOn(a: Arm, list: Int): List<Int> = synchronized(sim) { sim.bindForTest(a).entries.withIndex().filter { it.value?.list == list }.map { it.index } }

        /** The window the lens's list 2 shows at its cursor: its item table's entry there, as a registered window's id. */
        fun lensWindow(b: wm.damage.core.motion.Wheel.Block): String? = synchronized(sim) {
            val l = sim.bindForTest(Arm.RIGHT).lists[2]
            val c = sim.right.textureCache!!
            val at = l.items * 4 + 2 * l.cursor
            val off = ((c[at].toInt() and 0xFF) or ((c[at + 1].toInt() and 0xFF) shl 8)) * 4
            regs[b.cellOffs.indexOf(off)]
        }

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

        fun subs(img: ByteArray): List<ByteArray> {
            if (img[0].toInt() != 8) return listOf(img)
            val out = ArrayList<ByteArray>()
            var i = 2
            repeat(img[1].toInt() and 0xFF) {
                val n = (img[i].toInt() and 0xFF) or ((img[i + 1].toInt() and 0xFF) shl 8)
                out += img.copyOfRange(i + 2, i + 2 + n); i += 2 + n
            }
            return out
        }
        fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
        fun isSub(s: ByteArray, mode: Int, sub: Int) = (s[0].toInt() and 0x7F) == mode && s[1].toInt() == sub
        fun isCommit(s: ByteArray, tag: Int) = isSub(s, 26, 3) && u16(s, 3) == tag
        fun isSetOf(s: ByteArray, list: Int) = isSub(s, 27, CfwModes.BIND_SET) && s[5].toInt() == list
        fun setEntry(s: ByteArray) = s[2].toInt() and 0xFF
        fun isClearOf(s: ByteArray, entry: Int) = isSub(s, 27, CfwModes.BIND_CLEAR) && (s[2].toInt() and 0xFF) == entry
        fun isListOf(s: ByteArray, list: Int) = (isSub(s, 27, CfwModes.BIND_LIST) || isSub(s, 27, CfwModes.BIND_LIST_RING)) && s[2].toInt() == list
        fun isDiscard0(s: ByteArray) = isSub(s, 26, 4) && s[2].toInt() == 0
        fun since(at: Int): List<List<ByteArray>> = right.drop(at).map { subs(it) }
        /** The cache writes LEFT took from image [from] on, as (offset, bytes). */
        fun cacheWrites(from: Int): List<Pair<Int, ByteArray>> = images.toList().drop(from).filter { it.first == Arm.LEFT }
            .flatMap { (_, img) -> if ((img[0].toInt() and 0x7F) in setOf(19, 25)) ShipVectors.cacheWrites(img) else emptyList() }

        fun close() { scope.cancel(); tmp.toFile().deleteRecursively() }
    }

    /** One notch on the bound wheel: adopted and committed (a COMMIT of its S on RIGHT), the cursor moved by its step modulo
     *  n, no snap-back, belief equal to both lenses once the motion has ended. */
    private suspend fun notch(r: Rig, eventId: Int, what: String) {
        r.settle("$what: before")
        val list = r.list()
        val e = assertNotNull(list.entryFor(eventId))
        val row0 = r.row()
        val at = r.right.size
        val snaps = r.notes("snapback").size
        val s = r.fire(eventId)
        delay(((e.program!!.T + 1) * e.period * 1000L) / 1024 + r.q() + 300)
        r.settle("$what: after")
        assertEquals((row0 + e.step).mod(list.count), r.row(), "$what: the cursor moved by its step")
        assertTrue(r.since(at).any { ss -> ss.any { r.isCommit(it, s) } }, "$what: a COMMIT of S $s")
        assertEquals(snaps, r.notes("snapback").size, "$what: no snap-back: ${r.notes("snapback").takeLast(2)}")
        r.assertOracle(what)
    }

    /** The open's batch on RIGHT from image [at] on: the first flush carrying a SET of list 2. */
    private fun bindBatch(r: Rig, at: Int): List<ByteArray>? = r.since(at).firstOrNull { ss -> ss.any { r.isSetOf(it, 2) } }

    // ------------------------------------------------------------------------------------------------------- the bind
    /**
     * The DW spec's §7.1 "the bind" with the amendment's list 2 and DA (d) 1: from Main, the open's batch carries, after its
     * drawing subs, Main's CLEARs (0–2), list 2's LIST-RING, SETs of 6 and 7 and the report-only tap at entry 0 (the rules'
     * number for a list that stages none), and slot 0's DISCARD naming list 2 at the cursor — in that order; both lenses
     * then hold 0, 6 and 7 for list 2 and nothing of Main's. A notch each way is drawn by the glasses, adopted and committed.
     * The cancel's batch CLEARs 6, 7 and 0 and SETs Main's list again with its DISCARD; the open's `wheel` note says it was
     * bound at the open. Without the wheel's case in the decider nothing of list 2 is sent.
     */
    @Test
    fun theOpenBindsTheWheelAsList2AndTheCloseGivesSlot0Back_DW1b1(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up(); r.open("w0"); r.open("w1"); r.toMain()
            r.until("Main bound") { r.onLoop { r.shell.slot0ForTest() == "main" } && r.entriesOn(Arm.RIGHT, 0).size == 3 }
            val b = assertNotNull(r.block(), "the block resident: ${r.notes("wheel")}")
            val at = r.right.size
            r.chord()
            r.bound("the open")
            val l = r.list()
            assertEquals(listOf(2, 0, 3, 0), listOf(l.list, l.slot, l.count, l.pitch), "list 2 in slot 0, Main and two windows, pitch 0")
            assertTrue(l.ring && l.items!!.contentEquals(b.items4) && l.items4 == b.itemsOff / 4, "a ring list over the block's item table")
            for (a in Arm.entries) {
                assertEquals(listOf(0, 6, 7), r.entriesOn(a, 2), "$a holds entries 0 (the tap), 6 and 7 for list 2")
                assertEquals(emptyList(), r.entriesOn(a, 0), "$a holds nothing of Main's list")
            }
            val ss = assertNotNull(bindBatch(r, at), "a batch binds list 2")
            val firstBind = ss.indexOfFirst { (it[0].toInt() and 0x7F) == 27 }
            assertTrue(ss.take(firstBind).any { (it[0].toInt() and 0x7F) !in setOf(26, 27) }, "the bind after the open's drawing subs: ${ss.map { "${it[0]}/${it[1]}" }}")
            val clears = (0..2).map { e -> ss.indexOfFirst { r.isClearOf(it, e) } }
            val li = ss.indexOfFirst { r.isListOf(it, 2) }
            val sets = ss.withIndex().filter { r.isSetOf(it.value, 2) }
            val di = ss.indexOfFirst { r.isDiscard0(it) && it[5].toInt() == 2 }
            assertTrue(clears.all { it >= 0 } && li > clears.max() && sets.all { it.index > li } && di > sets.maxOf { it.index },
                "CLEAR 0–2, LIST-RING, SETs, DISCARD in that order: ${ss.map { "${it[0]}/${it[1]}" }}")
            assertTrue(r.isSub(ss[li], 27, CfwModes.BIND_LIST_RING), "a LIST-RING")
            assertEquals(setOf(0, 6, 7), sets.map { r.setEntry(it.value) }.toSet(), "SET 6, 7 and the tap at 0")
            assertEquals(r.row(), r.u16(ss[di], 6), "the DISCARD names the cursor")
            notch(r, CfwModes.EV_UPROLL, "a notch down on the wheel")
            notch(r, CfwModes.EV_UPROLL, "a notch down across the wrap")
            notch(r, CfwModes.EV_DOWNROLL, "a notch up across the wrap")
            notch(r, CfwModes.EV_DOWNROLL, "a notch up")
            val at2 = r.right.size
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.until("the wheel closed") { !r.shell.switcherIsOpen }
            r.done("the cancel")
            r.until("Main bound again") { r.onLoop { r.shell.slot0ForTest() == "main" } && r.entriesOn(Arm.RIGHT, 0).size == 3 }
            for (a in Arm.entries) assertEquals(emptyList(), r.entriesOn(a, 2), "$a holds nothing of list 2 after the close")
            val back = assertNotNull(r.since(at2).firstOrNull { ss2 -> ss2.any { r.isSetOf(it, 0) } }, "Main's SETs again")
            assertTrue(listOf(6, 7).all { e -> back.any { r.isClearOf(it, e) } } && back.any { r.isDiscard0(it) && it[5].toInt() == 0 },
                "the close's batch CLEARs 6 and 7 and names Main's row: ${back.map { "${it[0]}/${it[1]}" }}")
            val note = r.notes("wheel").lastOrNull { it.startsWith("closed") }
            assertTrue(note != null && "bound at the open" in note && "4 notch(es) drawn by the glasses" in note, "the open's note: ${r.notes("wheel").takeLast(3)}")
            r.assertOracle("after the close")
        } finally { r.close() }
    }

    /**
     * The DW spec's §7.1 "the tap on the lens's row": a notch whose report and stock message are both lost leaves the phone's
     * cursor a row behind the lens's; the tap that follows commits to the window the lens shows (field 8's row, set through
     * `setRow` before the click reaches `commitSwitcher`), not the phone's.
     */
    @Test
    fun aTapAfterALostReportCommitsToTheWindowTheLensShows_DW1b1(): Unit = runBlocking {
        val r = Rig(nRows = 4)
        try {
            r.up(); r.open("w0"); r.open("w1"); r.open("w2")
            r.chord(); r.bound("the open")
            val b = assertNotNull(r.block())
            val row0 = r.row()
            val phones = r.onLoop { r.shell.wheelBoundForTest().first!!.items!![row0] }
            // the notch on the lenses with every notification dropped (its 115) and no stock message
            r.t.onNotify = { _, _ -> null }
            val now = System.currentTimeMillis()
            for (a in Arm.entries) r.sim.conformanceInput(a, CfwModes.DEV_RING, CfwModes.EV_UPROLL.toLong(), 0, now)
            r.t.onNotify = null
            delay(800); r.settle("the lost notch")
            assertEquals(row0, r.row(), "the phone's cursor did not move")
            val shown = assertNotNull(r.lensWindow(b), "the lens shows a window")
            assertTrue(b.cellOffs.indexOf(phones * 4).let { r.regs[it] } != shown, "precondition: the lens shows another window than the phone's row")
            r.fire(CfwModes.EV_TAP)
            r.until("the tap commits") { !r.shell.switcherIsOpen }
            r.done("the commit")
            assertEquals(shown, r.onLoop { r.shell.currentWindowId() }, "the commit goes to the window the lens showed")
        } finally { r.close() }
    }

    // --------------------------------------------------------------------------------------------- the lanes under it
    /**
     * The amendment's §7.1 "Page staging's gate": a document in front with strips owed (eight notches of it and the chord in
     * one loop turn) and the block resident — the wheel binds at the open, six notches on the glasses, a pause, the cancel:
     * Page staging lays out no strip from the open to the close (its records in the ring unchanged), and after the close its
     * strips go.
     */
    @Test
    fun pageStagingWritesNoStripWhileTheSwitcherIsOpen_DW1b1(): Unit = runBlocking {
        val doc = Doc()
        val r = Rig(extra = listOf(doc))
        try {
            r.up(); r.open("w0"); r.open("w1")
            r.onLoop { r.shell.services.openWindow("doc", null) }
            r.until("Page staging has staged strips") { r.onLoop { r.shell.pageStripsForTest().isNotEmpty() } }
            r.until("the doc: the wheel's lane done") { r.shell.isQuiescent() && r.onLoop { r.shell.wheelLaneDoneForTest() && r.shell.transitionPhaseForTest() == null } }
            fun strips() = r.onLoop { r.shell.pageStripsForTest().map { it.first to it.second.contentHashCode() } }.toSet()
            r.onLoop { repeat(8) { r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }; r.chord() }
            r.bound("the open over the doc")
            val strips0 = strips()
            for (i in 0 until 6) { r.fire(if (i < 3) CfwModes.EV_UPROLL else CfwModes.EV_DOWNROLL); delay(260) }
            delay(1_500); r.settle("six notches and a pause")
            val strips1 = strips()
            assertEquals(emptySet(), strips1 - strips0, "no strip laid out while the switcher is open")
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.until("the wheel closed") { !r.shell.switcherIsOpen }
            r.until("strips go after the close") { (strips() - strips1).isNotEmpty() }
            assertTrue(r.onLoop { r.shell.wheelRecordsForTest() }.all { it.state == StagedRecord.State.PROVEN }, "the block stays proven")
        } finally { r.close() }
    }

    /**
     * The DW spec's §7.1 "the preview's wait" (§4.3): with the wheel bound, two notches 150 ms apart — while the burst is open
     * the preview behind the panel stays the one painted at the open; after the burst's SETTLE it is the window the cursor
     * now names. Without the wait the preview follows each adopted notch.
     */
    @Test
    fun thePreviewWaitsForTheBurstsSettle_DW1b1(): Unit = runBlocking {
        val r = Rig(nRows = 4)
        try {
            r.up(); r.open("w0"); r.open("w1"); r.open("w2")
            r.chord(); r.bound("the open")
            r.until("the open's preview painted") { r.onLoop { r.shell.previewForTest().second } != null }
            r.settle("the preview")
            val p0 = r.onLoop { r.shell.previewForTest().second }
            val samples = java.util.concurrent.CopyOnWriteArrayList<Pair<Boolean, String?>>()
            val sampling = r.scope.launch { while (true) { samples += r.onLoop { r.shell.previewForTest() }; delay(5) } }
            r.fire(CfwModes.EV_UPROLL); delay(150); r.fire(CfwModes.EV_UPROLL)
            r.until("the burst opened") { samples.any { it.first } }
            r.until("the burst's SETTLE") { samples.isNotEmpty() && samples.last().first.not() && samples.any { it.first } }
            delay(600); r.settle("after the burst")
            sampling.cancel()
            val during = samples.filter { it.first }.map { it.second }.toSet()
            assertEquals(setOf(p0), during, "the preview while the burst is open: the open's")
            val want = r.regs.let { regs -> val l = r.list(); val b = assertNotNull(r.block()); regs[b.cellOffs.indexOf(l.items!![r.row()] * 4)] ?: "@main" }
            assertEquals(want, r.onLoop { r.shell.previewForTest().second }, "after the SETTLE the preview is the cursor's window")
            assertTrue(want != p0, "precondition: two notches moved the selection")
        } finally { r.close() }
    }

    // ------------------------------------------------------------------------------------------------------ the offer
    /**
     * The amendment's §7.1 "The offer's conditions" (the shell's half): not offered at two entries (Main and one window), on
     * a 160 KiB session, below stage 5 — the switcher phone-drawn, nothing of list 2 sent, slot 0 nobody's while it is open,
     * and the open's `wheel` note gives the reason.
     */
    @Test
    fun theWheelIsNotOfferedWhereItsConditionsFail_DW1b1(): Unit = runBlocking {
        for ((label, rig, why) in listOf(Triple("two entries", { Rig(nRows = 1) }, "2 entries"),
            Triple("160 KiB", { Rig(kib = 160) }, "no 256 KiB session"), Triple("stage 4", { Rig(stage = 4) }, "below stage 5"))) {
            val r = rig()
            try {
                r.up()
                for (w in r.rows.take(2)) r.open(w.id)
                val at = r.right.size
                r.chord()
                r.until("$label: the wheel open") { r.shell.switcherIsOpen }
                r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
                delay(800); r.settle("$label: open")
                assertEquals(null, r.onLoop { r.shell.slot0ForTest() }, "$label: slot 0 is nobody's under the open wheel")
                assertTrue(r.since(at).none { ss -> ss.any { r.isSetOf(it, 2) } }, "$label: nothing of list 2 sent")
                r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
                r.until("$label: closed") { !r.shell.switcherIsOpen }
                r.until("$label: the open's note") { r.notes("wheel").any { it.startsWith("closed") } }
                val note = r.notes("wheel").last { it.startsWith("closed") }
                assertTrue("never bound" in note && why in note && "1 by the phone" in note, "$label: $note")
            } finally { r.close() }
        }
    }

    /**
     * The amendment's §7.1 "The order is current or not offered", with "An older cell binds"' unbound half (CK A4, A5): a
     * commit to another window by the phone's click, a dirty flip and the chord in one loop turn — at the open the item table
     * is the old order, so the wheel is not offered (its open phone-drawn); the lane writes the item table alone in place (the
     * dirty cell waits for the close), and once it is counted the wheel binds mid-open with the DISCARD naming the cursor. A
     * notch on the glasses, then a tap: the commit goes to the window the lens showed. After the close the dirty cell goes.
     */
    @Test
    fun aChordBeforeTheItemTablesCountIsPhoneDrawnThenBindsMidOpen_DW1b1(): Unit = runBlocking {
        val r = Rig(nRows = 4)
        try {
            r.up(); r.open("w0"); r.open("w1"); r.open("w2")
            r.chord(); r.bound("the first open")
            val mark0 = r.list().mark
            val from = r.images.size
            val at = r.right.size
            // the phone's click commits to the cursor's window (w1); w0 dirty; the chord — before any idle pump
            r.onLoop { r.shell.postGesture(EvenHubMsg.EV_CLICK); r.rows[0].dirty = true; r.shell.services.requestRender(r.rows[0]); r.chord() }
            r.until("committed and open again") { r.onLoop { r.shell.currentWindowId() == "w1" && r.shell.switcherIsOpen } }
            r.bound("bound mid-open")
            val b = assertNotNull(r.block())
            val writes = r.cacheWrites(from).filter { (off, _) -> off >= b.base && off < b.end }
            assertTrue(writes.isNotEmpty() && writes.all { (off, _) -> off >= b.itemsOff && off < b.itemsOff + b.itemSlot },
                "the item table alone written in the open (the dirty cell waits): ${writes.map { it.first }}")
            val ss = assertNotNull(bindBatch(r, at), "the bind")
            val opened = r.notes("wheel").lastOrNull { it.startsWith("closed") }
            assertTrue(opened != null && "bound at the open" in opened, "the first open was bound at the open: $opened")
            val di = ss.indexOfFirst { r.isDiscard0(it) && it[5].toInt() == 2 }
            assertTrue(di >= 0 && r.u16(ss[di], 6) == r.row(), "the mid-open bind's DISCARD names the cursor: ${ss.map { "${it[0]}/${it[1]}" }}")
            assertEquals(r.list().items!!.toList(), b.items4.toList(), "bound on the order now")
            assertTrue(r.list().mark != mark0, "a new mark for the new order (its LIST-RING is fresh, not the riding one)")
            notch(r, CfwModes.EV_DOWNROLL, "a notch after the late bind (onto w1: the commit leaves w0 dirty)")
            val shown = assertNotNull(r.lensWindow(b))
            r.fire(CfwModes.EV_TAP)
            r.until("the tap commits") { !r.shell.switcherIsOpen }
            r.done("the commit")
            assertEquals(shown, r.onLoop { r.shell.currentWindowId() }, "the window the lens showed")
            val note = r.notes("wheel").last { it.startsWith("closed") }
            assertTrue("mid-open" in note && "the item table is not this open's order" in note, "the late bind's note: $note")
            val after = r.cacheWrites(from).filter { (off, _) -> off >= b.base && off < b.end && off in b.cellOffs[1] until b.cellOffs[1] + b.cellSlots[1] }
            assertTrue(after.isNotEmpty(), "w0's dirty cell written after the close")
        } finally { r.close() }
    }

    /**
     * The amendment's §7.1 "An older cell binds" (CK A4): a dirty flip and the chord with the lanes held — the wheel binds at the
     * open with the cell as written (not a cell's picture: the item table and the programs are current); a notch lands on the
     * dirty window and after it the panel equals the switcher's paint (the held repaint); nothing of the block is written while
     * the wheel is bound, and the cell goes after the close.
     */
    @Test
    fun anOlderCellBindsAtTheOpenAndIsWrittenAfterTheClose_DW1b1(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up(); r.open("w0"); r.open("w1")
            val b = assertNotNull(r.block())
            val from = r.images.size
            val at = r.right.size
            // the flip and the chord with no room at the pumps: the idle lanes cannot paint or write w0's cell before the
            // open (the first run without the hold wrote it there, and the open waited for its count — the case of a
            // keep-current write in flight, not this one); the open's poll offers the wheel all the same
            try {
                r.shell.noRoomAfterPollForTest = { true }
                r.onLoop { r.rows[0].dirty = true; r.shell.services.requestRender(r.rows[0]) }
                delay(300)
                r.chord()
                r.until("the wheel open and offered") { r.shell.switcherIsOpen && r.onLoop { r.shell.slot0ForTest() } == "wheel" }
            } finally { r.shell.noRoomAfterPollForTest = null }
            r.bound("the open")
            assertNotNull(bindBatch(r, at), "list 2 bound")
            notch(r, CfwModes.EV_UPROLL, "a notch off the dirty window's row")
            notch(r, CfwModes.EV_DOWNROLL, "a notch back onto it (END draws its cell as written)")
            assertEquals(emptyList(), r.cacheWrites(from).filter { (off, _) -> off >= b.base && off < b.end }.map { it.first }, "nothing of the block while bound: ${r.notes("wheel").takeLast(4)}")
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.until("closed") { !r.shell.switcherIsOpen }
            r.done("after the close")
            val cell = r.cacheWrites(from).filter { (off, _) -> off >= b.base && off < b.end }.map { it.first }
            assertTrue(cell.isNotEmpty() && cell.all { it in b.cellOffs[1] until b.cellOffs[1] + b.cellSlots[1] }, "w0's cell alone after the close: $cell")
            val note = r.notes("wheel").last { it.startsWith("closed") }
            assertTrue("bound at the open" in note, "bound at the open's first poll — in the open's own flush, whose tail may ride its last image: $note")
        } finally { r.close() }
    }

    /**
     * The amendment's §7.1 "Main wins, never under a bound wheel" (CK A6): Main grown (its rows ink-heavy, as DW-1a's pin finds
     * the count of them) to meet the block while the wheel is bound in Main — Main's staging does not start: one `waits` note,
     * the block placed and proven, the wheel bound and a notch drawn by the glasses; after the cancel Main is staged over it
     * and the block leaves (Main wins).
     */
    @Test
    fun mainsStagingThatMeetsTheBlockWaitsForTheClose_DW1b1(): Unit = runBlocking {
        val r = Rig(nRows = 8, text = NoisyText())
        try {
            r.up(); r.open("w0"); r.open("w1"); r.toMain()
            val b0 = assertNotNull(r.block())
            var depth = 8
            suspend fun restage(): Int? {
                val n0 = r.notes("mainstage").count { it.startsWith("staged") || "cannot be staged" in it }
                depth = if (depth == 8) 4 else 8
                r.shell.updateSettings { it.copy(depth = depth) }
                r.until("Main staged again at Depth $depth") { r.notes("mainstage").count { it.startsWith("staged") || "cannot be staged" in it } > n0 &&
                    r.onLoop { r.shell.mainBinding.staging == null || r.shell.mainBinding.counted } }
                r.done("Main at Depth $depth")
                return r.onLoop { r.shell.mainBinding.staging?.end }
            }
            var k = 0
            var end = 0
            while (end <= b0.base && k < r.rows.size) { r.rows[k++].heavy = true; end = restage() ?: fail("Main refused at $k heavy rows") }
            assertTrue(end > b0.base, "the fixture: Main reaches the block's base with $k heavy rows")
            for (w in r.rows) w.heavy = false
            restage()
            r.until("the block placed again") { r.block() != null }
            r.done("placed again")
            val b = assertNotNull(r.block())
            // the wheel bound in Main, then Main's rows grow again
            r.chord(); r.bound("the open in Main")
            val waits0 = r.notes("mainstage").count { it.startsWith("waits") }
            val staged0 = r.notes("mainstage").count { it.startsWith("staged") }
            r.onLoop { for (i in 0 until k) { r.rows[i].heavy = true; r.shell.services.requestRender(r.rows[i]) } }
            r.until("Main's staging waits for the close") { r.notes("mainstage").count { it.startsWith("waits") } > waits0 }
            delay(1_000); r.settle("waiting")
            assertEquals(1, r.notes("mainstage").count { it.startsWith("waits") } - waits0, "said once: ${r.notes("mainstage").takeLast(4)}")
            assertEquals(staged0, r.notes("mainstage").count { it.startsWith("staged") }, "no staging of Main's while the wheel is bound")
            assertTrue(r.block() === b || r.block()?.base == b.base, "the block stays")
            assertTrue(r.onLoop { r.shell.wheelRecordsForTest() }.all { it.state == StagedRecord.State.PROVEN })
            notch(r, CfwModes.EV_UPROLL, "a notch while Main waits")
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.until("closed") { !r.shell.switcherIsOpen }
            r.until("Main staged after the close") { r.notes("mainstage").count { it.startsWith("staged") } > staged0 }
            r.until("the block leaves (Main wins)") { r.block() == null }
            assertTrue(r.notes("wheel").any { it.startsWith("dropped — Main's staging") }, "${r.notes("wheel").takeLast(3)}")
        } finally { r.close() }
    }

    // ------------------------------------------------------------------- decisions DE: a report met by a stale front
    /** No Select or Back transition (the stale-front pins change the screen with no room: an act would wait for room). */
    private val noActs: (ShellSettings) -> ShellSettings = { it.copy(selectEffects = emptyList(), backEffects = emptyList()) }

    /** A notch on the glasses on whatever list they hold, waited out (the motion, Q and a margin) and settled. */
    private suspend fun step(r: Rig, eventId: Int, what: String) { r.fire(eventId); delay(1_300); r.settle(what) }

    private fun mainRow(r: Rig): Int = r.onLoop { r.shell.mainBoundForTest().second }

    /**
     * Decisions DE (the second review of K1-2, finding 1), Main and a window list: Files' list bound, its cursor one row
     * past the row Main shows after a double tap back (a dry pass finds it); then, with no room at the pumps, the double tap
     * and a notch up on the glasses, which still hold list 1 — its report (cursor = Main's row) is handled while Main is in
     * front and the rules still hold list 1: it is applied to Main as today (one row up), never adopted onto Main's row.
     */
    @Test
    fun aWindowListsReportAfterTheDoubleTapBackDoesNotSetMainsCursor_DW1b1(): Unit = runBlocking {
        val files = Win("files", "Files", n = 6)
        val r = Rig(extra = listOf(files), tweak = noActs)
        try {
            r.up()
            suspend fun inFiles() { r.open("files"); r.until("Files' list bound") { r.onLoop { r.shell.slot0ForTest() == "window" } && r.entriesOn(Arm.RIGHT, 1).size == 3 }; r.settle("Files") }
            suspend fun back() { r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK); r.until("Main in front") { r.onLoop { r.shell.currentWindowId() == null && r.shell.slot0ForTest() == "main" } } }
            inFiles(); back(); r.done("Main (the dry pass)")
            val (main0, r1) = r.onLoop { r.shell.mainBoundForTest() }
            val count = assertNotNull(main0, "Main offered").count
            inFiles()
            while (r.onLoop { r.shell.windowBoundForTest().second } != r1 + 1) step(r, CfwModes.EV_UPROLL, "Files' cursor toward ${r1 + 1}")
            try {
                r.shell.noRoomAfterPollForTest = { true }
                back()
                assertEquals(r1, mainRow(r), "precondition: Main's row as in the dry pass")
                r.fire(CfwModes.EV_DOWNROLL)                                       // list 1 fires on the glasses: its cursor becomes r1
                delay(500)
            } finally { r.shell.noRoomAfterPollForTest = null }
            delay(1_000); r.done("after")
            assertEquals((r1 - 1).mod(count), mainRow(r), "list 1's report applied to Main as today (one row up), not adopted onto Main's row")
        } finally { r.close() }
    }

    /**
     * Decisions DE with the wheel, Main and the wheel both ways: (1) Main's row 0, no room, the chord and a notch down on the
     * glasses (still Main's entries: Main's cursor becomes 1, the wheel's open cursor) — applied to the wheel as a phone notch
     * (cursor 2), never adopted onto its cursor; (2) Main's row 2, the wheel bound at cursor 0, no room, the phone's click
     * (to Main) and a notch down on the glasses (still list 2's: its cursor becomes 1) — applied to Main as today (row 3),
     * never adopted onto Main's row.
     */
    @Test
    fun aReportOfMainsOrTheWheelsListMetByTheOtherInFrontMovesOnlyAsToday_DW1b1(): Unit = runBlocking {
        val r = Rig(tweak = noActs)
        try {
            r.up(); r.open("w0"); r.open("w1"); r.toMain()
            r.until("Main bound") { r.onLoop { r.shell.slot0ForTest() == "main" } && r.entriesOn(Arm.RIGHT, 0).size == 3 }
            val count = assertNotNull(r.onLoop { r.shell.mainBoundForTest().first }).count
            while (mainRow(r) != 0) step(r, CfwModes.EV_DOWNROLL, "Main toward row 0")
            try {
                r.shell.noRoomAfterPollForTest = { true }
                r.chord()
                r.until("the wheel open and offered") { r.shell.switcherIsOpen && r.onLoop { r.shell.slot0ForTest() } == "wheel" }
                assertEquals(1, r.row(), "precondition: the wheel opens at row 1")
                r.fire(CfwModes.EV_UPROLL)                                         // Main's entry fires: its cursor becomes 1
                delay(500)
            } finally { r.shell.noRoomAfterPollForTest = null }
            delay(1_000); r.settle("(1)")
            assertEquals(2, r.row(), "(1) Main's report applied to the wheel as a phone notch, not adopted onto its cursor")
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.until("closed") { !r.shell.switcherIsOpen }
            r.done("(1) closed")
            r.until("Main bound again") { r.onLoop { r.shell.slot0ForTest() == "main" } && r.entriesOn(Arm.RIGHT, 0).size == 3 }
            while (mainRow(r) != 2) step(r, CfwModes.EV_UPROLL, "Main toward row 2")
            r.chord(); r.bound("(2) the open")
            step(r, CfwModes.EV_DOWNROLL, "(2) the wheel to row 0 (Main)")
            assertEquals(0, r.row(), "precondition: the wheel at Main's entry")
            try {
                r.shell.noRoomAfterPollForTest = { true }
                r.shell.postGesture(EvenHubMsg.EV_CLICK)
                r.until("committed to Main") { !r.shell.switcherIsOpen && r.onLoop { r.shell.currentWindowId() == null && r.shell.slot0ForTest() == "main" } }
                assertEquals(2, mainRow(r), "precondition: Main's row 2")
                r.fire(CfwModes.EV_UPROLL)                                         // list 2's entry 6 fires: its cursor becomes 1
                delay(500)
            } finally { r.shell.noRoomAfterPollForTest = null }
            delay(1_000); r.done("(2)")
            assertEquals(3.mod(count), mainRow(r), "(2) the wheel's report applied to Main as today (one row down), not adopted onto Main's row")
        } finally { r.close() }
    }

    /**
     * Decisions DE with the wheel, a window list and the wheel: Files' list bound at row 1, no room, the chord and a notch down
     * on the glasses (still list 1's: its cursor becomes 2, the wheel's open cursor) — applied to the wheel as a phone notch
     * (cursor 0 of three), never adopted onto its cursor.
     */
    @Test
    fun aWindowListsReportMetByTheOpenWheelMovesItOnlyAsToday_DW1b1(): Unit = runBlocking {
        val files = Win("files", "Files", n = 6)
        val r = Rig(extra = listOf(files), tweak = noActs)
        try {
            r.up(); r.open("w0"); r.open("files")
            r.until("Files' list bound") { r.onLoop { r.shell.slot0ForTest() == "window" } && r.entriesOn(Arm.RIGHT, 1).size == 3 }
            r.settle("Files")
            step(r, CfwModes.EV_UPROLL, "Files to row 1")
            assertEquals(1, r.onLoop { r.shell.windowBoundForTest().second }, "precondition: Files at row 1")
            try {
                r.shell.noRoomAfterPollForTest = { true }
                r.chord()
                r.until("the wheel open and offered") { r.shell.switcherIsOpen && r.onLoop { r.shell.slot0ForTest() } == "wheel" }
                assertEquals(2, r.row(), "precondition: the wheel opens at row 2 (Main, Files, w0)")
                r.fire(CfwModes.EV_UPROLL)                                         // list 1 fires: its cursor becomes 2
                delay(500)
            } finally { r.shell.noRoomAfterPollForTest = null }
            delay(1_000); r.settle("after")
            assertEquals(0, r.row(), "list 1's report applied to the wheel as a phone notch, not adopted onto its cursor")
            assertEquals(1, r.onLoop { files.model.cursor }, "Files' own cursor untouched by its stale report")
        } finally { r.close() }
    }

    // --------------------------------------------------------------------------------------------------- the envelope
    /**
     * The envelope's lost CLEARs (decisions DA (d) 1): the close's CLEARs of the wheel's entries lost on both lenses. Into
     * Main — Main's SET 0 replaced the tap entry and Main's 0 and 1 match before 6 and 7, so a notch is Main's, adopted.
     * Into a window whose list is bound (3–5) — a tap fires the wheel's stale tap entry 0 first: its 115 names an entry the
     * phone does not hold, the table repair CLEARs it in the next batch (the rule that exists), and the next tap fires list
     * 1's. (That first tap is not acted on: the cost of the case, said in the report.)
     */
    @Test
    fun aLostClearOfTheWheelsEntriesIsMendedByTheRulesThatExist_DW1b1(): Unit = runBlocking {
        val r = Rig(extra = listOf(Win("files", "Files", n = 6)))
        try {
            r.up(); r.open("w0"); r.open("w1"); r.toMain()
            r.chord(); r.bound("the open in Main")
            val kept = Arm.entries.associateWith { a -> synchronized(r.sim) { listOf(6, 7).associateWith { r.sim.bindForTest(a).entries[it] } } }
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.until("closed into Main") { !r.shell.switcherIsOpen }
            r.until("Main bound") { r.onLoop { r.shell.slot0ForTest() == "main" } && r.entriesOn(Arm.RIGHT, 0).size == 3 }
            r.settle("Main")
            synchronized(r.sim) { for (a in Arm.entries) for ((e, v) in kept.getValue(a)) r.sim.bindForTest(a).entries[e] = v }
            assertEquals(listOf(0, 1, 2), r.entriesOn(Arm.RIGHT, 0), "Main's SET 0 replaced the wheel's tap entry")
            val at = r.right.size
            val (main0, row0) = r.onLoop { r.shell.mainBoundForTest() }
            val s = r.fire(CfwModes.EV_UPROLL)
            delay(1_200); r.settle("a notch in Main")
            assertEquals((row0 + 1).mod(assertNotNull(main0).count), r.onLoop { r.shell.mainBoundForTest().second }, "the notch is Main's, adopted")
            assertTrue(r.since(at).any { ss -> ss.any { r.isCommit(it, s) } }, "a COMMIT of S $s (a fire of list 2 would be answered by a DISCARD)")
            // into a window whose list is bound
            r.open("files")
            r.until("Files' list bound") { r.onLoop { r.shell.slot0ForTest() == "window" } && r.entriesOn(Arm.RIGHT, 1).size == 3 }
            r.chord(); r.bound("the open in Files")
            val kept2 = Arm.entries.associateWith { a -> synchronized(r.sim) { listOf(0, 6, 7).associateWith { r.sim.bindForTest(a).entries[it] } } }
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.until("closed into Files") { !r.shell.switcherIsOpen }
            r.until("Files' list bound again") { r.onLoop { r.shell.slot0ForTest() == "window" } && r.entriesOn(Arm.RIGHT, 1).size == 3 }
            r.settle("Files")
            synchronized(r.sim) { for (a in Arm.entries) for ((e, v) in kept2.getValue(a)) r.sim.bindForTest(a).entries[e] = v }
            val at2 = r.right.size
            r.fire(CfwModes.EV_TAP)
            r.until("the stale entry 0 CLEARed on RIGHT") { r.entriesOn(Arm.RIGHT, 2).let { 0 !in it } }
            r.settle("the repair")
            assertTrue(r.since(at2).any { ss -> ss.any { r.isClearOf(it, 0) } }, "the repair's CLEAR of entry 0")
            assertTrue(r.notes("tapdrop").any { "list=2" in it }, "the stale tap's report is not acted on: ${r.notes("tapdrop").takeLast(2)}")
            val s2 = r.fire(CfwModes.EV_TAP)
            r.until("the next tap acts on Files' list") { r.notes("tapdrop").none { "S=$s2 " in it } && r.onLoop { r.shell.localCountsForTest()["tapActed"] ?: 0 } > 0 }
        } finally { r.close() }
    }

    /**
     * C3 DW-2c (decisions EA; `C3-DW2a-built.md` §6 item 5, seed 424200905): the close's batch lost on RIGHT alone — RIGHT
     * keeps the wheel's table (its report-only tap at entry 0, 6 and 7) and none of Main's SETs, while the record holds
     * entry 0 for Main (its down entry). A tap on RIGHT reports "entry 0, list 2": the tap is dropped (`focus`), and the
     * report is the table repair's evidence — the entry is held, but for list 0 — so the next batch carries Main's SET of
     * entry 0, which replaces the wheel's tap entry on RIGHT. The NEXT tap is acted on: RIGHT now holds no tap entry
     * (Main's SET of 2 went with the lost close), so it is a click with no report, and the stale tap's report did not set
     * `tapsLive` — under `glasses` such a click is acted on as today, not dropped as `no-report`. Before the cure every
     * tap reported the wheel's entry and was dropped until the next front change.
     */
    @Test
    fun aLostCloseOnRightLeavesTheWheelsTapEntryAndTheRepairTakesItFromTheReport_DW2c(): Unit = runBlocking {
        val r = Rig(taps = Taps.GLASSES, tweak = noActs)
        try {
            r.up(); r.open("w0"); r.open("w1"); r.toMain()
            r.until("Main bound") { r.onLoop { r.shell.slot0ForTest() == "main" } && r.entriesOn(Arm.RIGHT, 0).size == 3 }
            r.chord(); r.bound("the open in Main")
            val wheels = synchronized(r.sim) { r.sim.bindForTest(Arm.RIGHT).entries.copyOf() }
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.until("closed into Main") { !r.shell.switcherIsOpen }
            r.until("Main bound again") { r.onLoop { r.shell.slot0ForTest() == "main" } && r.entriesOn(Arm.RIGHT, 0).size == 3 }
            r.settle("Main")
            // the close's table messages lost on RIGHT alone: RIGHT's table is the wheel's again, LEFT's is Main's
            synchronized(r.sim) { wheels.copyInto(r.sim.bindForTest(Arm.RIGHT).entries) }
            assertEquals(listOf(0, 6, 7) to emptyList<Int>(), r.entriesOn(Arm.RIGHT, 2) to r.entriesOn(Arm.RIGHT, 0), "precondition: RIGHT holds the wheel's table")
            assertEquals(listOf(0, 1, 2), r.entriesOn(Arm.LEFT, 0), "precondition: LEFT holds Main's")
            assertEquals(null, r.onLoop { r.shell.currentWindowId() }, "precondition: Main in front")
            val at = r.right.size
            val s1 = r.fire(CfwModes.EV_TAP)
            r.until("the stale tap's report dropped") { r.notes("tapdrop").any { "S=$s1 " in it && "list=2" in it } }
            r.until("the repair's SET of entry 0 in Main's form on RIGHT", maxMs = 10_000) {
                synchronized(r.sim) { r.sim.bindForTest(Arm.RIGHT).entries[0]?.list == 0 } }
            r.settle("the repair")
            assertTrue(r.since(at).any { ss -> ss.any { r.isSetOf(it, 0) && r.setEntry(it) == 0 } }, "a SET of entry 0 for list 0 after the tap")
            assertTrue(r.notes("repair").any { "entry 0" in it && "list 2" in it }, "the repair's note names the evidence: ${r.notes("repair").takeLast(2)}")
            assertEquals(null, r.onLoop { r.shell.currentWindowId() }, "the stale tap was not acted on")
            val s2 = r.fire(CfwModes.EV_TAP)
            r.until("the next tap acts on Main's row", maxMs = 10_000) { r.onLoop { r.shell.currentWindowId() } != null }
            assertTrue(r.notes("tapdrop").none { "cause=no-report" in it }, "no tap dropped as no-report: ${r.notes("tapdrop").takeLast(2)} (S $s2)")
        } finally { r.close() }
    }

    /**
     * The envelope's "a session that ends with the wheel open" (the DW spec §5): the session ends with the wheel bound (the
     * shell stopped and started: the rig has no keeper to rebuild a dropped link) — the new
     * session's start sends nothing of list 2 (the offer goes at the start; the cache went with the session), the block is
     * written again, and once it is counted the wheel binds mid-open with the DISCARD naming the cursor.
     */
    @Test
    fun aSessionThatEndsWithTheWheelOpenBindsAgainOnceTheBlockIsCounted_DW1b1(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up(); r.open("w0"); r.open("w1")
            r.chord(); r.bound("the open")
            val clears = r.shell.stageClears
            val from = r.images.size
            r.shell.stop()
            r.shell.start()
            r.until("a new session") { r.shell.stageClears > clears && r.t.state.value.effects }
            assertTrue(r.shell.switcherIsOpen, "the switcher stays open across the session's end")
            r.bound("bound again in the new session")
            val b = assertNotNull(r.block())
            val imgs = r.images.toList().drop(from).filter { it.first == Arm.LEFT }
            val firstWrite = imgs.indexOfFirst { (_, img) -> (img[0].toInt() and 0x7F) in setOf(19, 25) && ShipVectors.cacheWrites(img).any { (off, _) -> off >= b.base && off < b.end } }
            val firstSet = imgs.indexOfFirst { (_, img) -> r.subs(img).any { r.isSetOf(it, 2) } }
            assertTrue(firstWrite >= 0 && firstSet > firstWrite, "no SET of list 2 before the block is written again (write $firstWrite, SET $firstSet)")
            val ss = r.subs(imgs[firstSet].second)
            assertTrue(ss.any { r.isDiscard0(it) && it[5].toInt() == 2 && r.u16(it, 6) == r.row() }, "the return names the cursor: ${ss.map { "${it[0]}/${it[1]}" }}")
            notch(r, CfwModes.EV_UPROLL, "a notch in the new session")
        } finally { r.close() }
    }

    /**
     * The envelope's "a commit or a cancel while the bind's batch is unanswered", and "a notice arriving while it is bound":
     * the chord with RIGHT's notifications held, the cancel before any answer, then released — list 2 ends CLEARed and Main's
     * list bound. Bound again: an ordinary notice waits behind the wheel (nothing of it drawn, the wheel still bound); an
     * emergency cancels the wheel (list 2 CLEARed) and shows.
     */
    @Test
    fun aCancelInsideTheBindAndANoticeUnderTheWheel_DW1b1(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up(); r.open("w0"); r.open("w1"); r.toMain()
            r.until("Main bound") { r.onLoop { r.shell.slot0ForTest() == "main" } && r.entriesOn(Arm.RIGHT, 0).size == 3 }
            val held = java.util.concurrent.CopyOnWriteArrayList<Pair<Arm, ByteArray>>()
            r.t.onNotify = { a, p -> held += a to p; null }
            r.chord()
            r.until("the wheel open") { r.shell.switcherIsOpen }
            delay(200)
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.until("closed") { !r.shell.switcherIsOpen }
            delay(200)
            r.t.onNotify = null
            for ((a, p) in held) r.t.deliver(a, p)
            r.done("released")
            r.until("Main bound after the held cancel") { r.onLoop { r.shell.slot0ForTest() == "main" } && r.entriesOn(Arm.RIGHT, 0).size == 3 }
            for (a in Arm.entries) assertEquals(emptyList(), r.entriesOn(a, 2), "$a holds nothing of list 2")
            r.assertOracle("after the held cancel")
            // a notice under the bound wheel
            r.chord(); r.bound("the open")
            r.shell.postNotice(Notifications.Notice("SMS · MOM", "t1", "dinner at six", "12:00"))
            delay(600); r.settle("an ordinary notice")
            assertTrue(r.shell.switcherIsOpen && r.onLoop { r.shell.slot0ForTest() } == "wheel" && !r.onLoop { r.shell.notificationsUpForTest() }, "the notice waits behind the wheel")
            r.shell.postNotice(Notifications.Notice("WEA · ALERT", "e1", "tornado warning", "12:01", emergency = true))
            r.until("the emergency cancels the wheel") { !r.shell.switcherIsOpen }
            r.until("list 2 CLEARed") { Arm.entries.all { a -> r.entriesOn(a, 2).isEmpty() } }
            assertTrue(r.onLoop { r.shell.notificationsUpForTest() }, "the emergency shows")
        } finally { r.close() }
    }

    /**
     * The class swept (decisions DA (c) 4): the window list's lane writes nothing while the switcher is open — Settings' rows
     * change level (a new list-1 staging owed) and the chord in one loop turn: the wheel binds at the open and no list-1
     * staging is laid out until the cancel; then it is, and bound.
     */
    @Test
    fun theWindowListsLaneWaitsUnderTheOpenWheel_DW1b1(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up(); r.open("w0"); r.open("w1"); r.open("settings")
            r.until("Settings bound") { r.onLoop { r.shell.slot0ForTest() == "window" } && r.entriesOn(Arm.RIGHT, 1).size == 3 }
            r.done("Settings")
            val staged0 = r.notes("liststage").count { it.startsWith("staged") }
            r.onLoop { r.shell.postGesture(EvenHubMsg.EV_CLICK); r.chord() }        // a click (Global's rows) and the chord in one loop turn
            r.until("the wheel open") { r.shell.switcherIsOpen }
            r.bound("the open over Settings' next level")
            delay(1_000); r.settle("under the wheel")
            assertEquals(staged0, r.notes("liststage").count { it.startsWith("staged") }, "no list-1 staging under the open wheel: ${r.notes("liststage").takeLast(3)}")
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.until("closed") { !r.shell.switcherIsOpen }
            r.until("list 1 staged after the close") { r.notes("liststage").count { it.startsWith("staged") } > staged0 }
        } finally { r.close() }
    }

    // ------------------------------------------------------------------------------------------------------- DW-1b-2
    /**
     * C3 DW-1b-2 (the DW spec §6; decisions DA (d) 4): `Switcher` = None enters the programs' key. Set in the same loop turn
     * as the chord, the open is not offered until None's programs are written and counted, the lane rewrites the
     * programs in place during the unbound open (CK A5) — nothing else of the block, at the same base — and the wheel binds
     * mid-open with None's one-step programs; a notch is drawn by the glasses in one step and committed.
     */
    @Test
    fun switcherNoneHoldsTheOfferUntilTheProgramsAreRewrittenInPlace_DW1b2(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up(); r.open("w0"); r.open("w1")
            val b0 = assertNotNull(r.block(), "the block placed")
            assertTrue(!b0.none, "Drum by default")
            val from = r.images.size
            val notes0 = r.notes("wheel").size
            r.onLoop { r.shell.updateSettings { it.copy(switcher = "none") }; r.chord() }
            r.until("the wheel open") { r.shell.switcherIsOpen }
            r.bound("bound under None")
            val e = assertNotNull(r.list().entryFor(CfwModes.EV_UPROLL))
            assertEquals(1, e.program!!.T, "the bound program is None's one step")
            val b1 = assertNotNull(r.block())
            assertTrue(b1.none && b1.base == b0.base, "the block rebuilt for None in place (base ${b0.base} -> ${b1.base}, none ${b1.none})")
            val writes = r.cacheWrites(from).filter { (off, _) -> off >= b1.base && off < b1.end }
            assertTrue(writes.isNotEmpty() && writes.all { (off, _) -> off >= b1.progOff },
                "only the programs' slot written for None: ${writes.map { it.first }} (programs at ${b1.progOff})")
            notch(r, CfwModes.EV_UPROLL, "a notch under None")
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.until("closed") { !r.shell.switcherIsOpen }
            r.until("the open's note") { r.notes("wheel").drop(notes0).any { it.startsWith("closed") } }
            val note = r.notes("wheel").drop(notes0).first { it.startsWith("closed") }
            assertTrue("mid-open (at the open: " in note, "the open was not offered until None's programs were written: $note")
        } finally { r.close() }
    }

    /**
     * C3 DW-1b-2 (decisions DF finding 2, amending CK A3's order): at a session start with a window's list in front
     * (Settings), the list goes first — the wheel's block's place reserved by its size, the list laid out under it, staged,
     * counted and bound — and the block's write follows: list 1 is bound before the block's last write goes, and the list
     * never yields to the block.
     */
    @Test
    fun aSessionStartWithAWindowsListInFrontBindsTheListBeforeTheBlocksWrite_DW1b2(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up(); r.open("w0"); r.open("w1"); r.open("settings")
            r.until("Settings bound") { r.onLoop { r.shell.slot0ForTest() == "window" } && r.entriesOn(Arm.RIGHT, 1).size == 3 }
            r.done("Settings")
            val clears = r.shell.stageClears
            val from = r.images.size
            val yields0 = r.notes("liststage").count { "the list yields" in it }
            r.shell.stop()
            r.shell.start()
            r.until("a new session") { r.shell.stageClears > clears && r.t.state.value.effects }
            r.until("Settings bound again and the block placed") {
                r.onLoop { r.shell.slot0ForTest() == "window" && r.shell.wheelBlockForTest() != null } && r.entriesOn(Arm.RIGHT, 1).size == 3 }
            r.done("the new session")
            val b = assertNotNull(r.block())
            val imgs = r.images.toList().drop(from).filter { it.first == Arm.LEFT }
            val lastWrite = imgs.indexOfLast { (_, img) -> (img[0].toInt() and 0x7F) in setOf(19, 25) && ShipVectors.cacheWrites(img).any { (off, _) -> off >= b.base && off < b.end } }
            val firstSet = imgs.indexOfFirst { (_, img) -> r.subs(img).any { r.isSetOf(it, 1) } }
            assertTrue(lastWrite >= 0 && firstSet >= 0 && firstSet < lastWrite,
                "list 1 bound (its first SET, image $firstSet) before the block's last write (image $lastWrite)")
            assertEquals(yields0, r.notes("liststage").count { "the list yields" in it }, "the list never yields to the block: ${r.notes("liststage").takeLast(3)}")
        } finally { r.close() }
    }

    /**
     * C3 DW-2 (decisions DM 3, DP 9): at a session start with a document in front, Page staging's first strip goes before
     * the wheel's block — the block's place reserved, its first write after the strip's first write (both read from the
     * cache writes LEFT took: a strip lies in the stage reserve, the block in Main's region below it). Prints the strip's
     * and the block's first writes in ms from the new session's first image (the simulator's link: modeled time).
     */
    @Test
    fun aSessionStartWithADocumentInFrontWritesItsFirstStripBeforeTheBlock_DW2(): Unit = runBlocking {
        val doc = Doc()
        val r = Rig(extra = listOf(doc))
        try {
            r.up(); r.open("w0"); r.open("w1"); r.open("doc")
            r.until("Page staging has staged strips") { r.onLoop { r.shell.pageStripsForTest().isNotEmpty() } }
            r.done("the doc")
            val clears = r.shell.stageClears
            val from = r.images.size
            r.shell.stop()
            r.shell.start()
            r.until("a new session") { r.shell.stageClears > clears && r.t.state.value.effects }
            r.until("the doc in front again, a strip staged and the block placed") {
                r.onLoop { r.shell.currentWindowId() == "doc" && r.shell.pageStripsForTest().isNotEmpty() && r.shell.wheelBlockForTest() != null } }
            r.done("the new session")
            val b = assertNotNull(r.block())
            val strips = r.onLoop { r.shell.pageStripsForTest().map { it.first until it.first + it.second.size } }
            val (imgs, ms) = synchronized(r.images) { r.images.toList() to r.imageMs.toList() }
            val left = (from until imgs.size).filter { imgs[it].first == Arm.LEFT }
            fun writesAt(i: Int, inside: (Int) -> Boolean) = (imgs[i].second[0].toInt() and 0x7F) in setOf(19, 25) &&
                ShipVectors.cacheWrites(imgs[i].second).any { (off, _) -> inside(off) }
            val firstStrip = left.firstOrNull { writesAt(it) { off -> strips.any { s -> off in s } } }
            val firstBlock = left.firstOrNull { writesAt(it) { off -> off >= b.base && off < b.end } }
            assertTrue(firstStrip != null && firstBlock != null, "the new session wrote a strip ($firstStrip) and the block ($firstBlock)")
            val t0 = ms[from]
            // what LEFT carried before the strip's first write, and of it the block's (the price of the order on the phone's link)
            val ahead = left.filter { it < firstStrip }
            val blockAhead = ahead.filter { writesAt(it) { off -> off >= b.base && off < b.end } }.sumOf { imgs[it].second.size }
            val figures = "the first strip's write at ${ms[firstStrip] - t0} ms (image ${firstStrip - from}), the block's first write at " +
                "${ms[firstBlock] - t0} ms (image ${firstBlock - from}) after the new session's first image (the simulator's link); LEFT carried " +
                "${ahead.sumOf { imgs[it].second.size }} B before the strip, $blockAhead B of them the block's (its ${b.size} B in all)"
            println("DW-2 a start with a document in front: $figures")
            assertTrue(firstStrip < firstBlock, "the document's first strip before the block's first write: $figures")
        } finally { r.close() }
    }

    /**
     * C3 DW-2 (decisions DP 9: "the block never waits for a strip that will not come"): at a session start with a document
     * in front that Page staging does not stage (no content key — Files', Games', Music's, Torrents' and Feed's documents),
     * the wheel's block is placed and counted, no strip is written, and every lane comes to rest.
     */
    @Test
    fun aSessionStartWithADocumentThatStagesNothingStillPlacesTheBlock_DW2(): Unit = runBlocking {
        val r = Rig(extra = listOf(Doc(key = null)))
        try {
            r.up(); r.open("w0"); r.open("doc")
            val clears = r.shell.stageClears
            r.shell.stop()
            r.shell.start()
            r.until("a new session") { r.shell.stageClears > clears && r.t.state.value.effects }
            r.until("the doc in front again and the block placed") { r.onLoop { r.shell.currentWindowId() == "doc" && r.shell.wheelBlockForTest() != null } }
            r.done("the new session")
            assertTrue(r.onLoop { r.shell.wheelRecordsForTest() }.all { it.state == StagedRecord.State.PROVEN }, "the block counted")
            assertEquals(emptyList(), r.onLoop { r.shell.pageStripsForTest() }.map { it.first }, "no strip written for a document that stages nothing")
        } finally { r.close() }
    }

    /**
     * C3 DW-1b-2 (decisions DJ item 5): a session rebuild reached with NO pump between the link's end and the next start —
     * the link drops with the wheel bound (a pump returns at its first gate while the transport says `started` false, so
     * no poll clears the offer) and the keeper stops and starts the shell (this rig has no keeper: its two calls). The new
     * session's start reads the front with the switcher still open: nothing of list 2 — no SET, no LIST — goes before the
     * block is written again, and once it is counted the wheel binds again.
     */
    @Test
    fun aLinkThatDropsWithTheWheelBoundStartsWithNothingOfList2BeforeTheBlock_DW1b2(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up(); r.open("w0"); r.open("w1")
            r.chord(); r.bound("the open")
            val clears = r.shell.stageClears
            val from = r.images.size
            r.t.endLink("a test: the link drops with the wheel bound")
            r.shell.stop()
            r.shell.start()
            r.until("a new session") { r.shell.stageClears > clears && r.t.state.value.effects }
            assertTrue(r.shell.switcherIsOpen, "the switcher stays open across the session's end")
            r.bound("bound again in the new session")
            val b = assertNotNull(r.block())
            val imgs = r.images.toList().drop(from).filter { it.first == Arm.LEFT }
            val firstWrite = imgs.indexOfFirst { (_, img) -> (img[0].toInt() and 0x7F) in setOf(19, 25) && ShipVectors.cacheWrites(img).any { (off, _) -> off >= b.base && off < b.end } }
            val firstSet = imgs.indexOfFirst { (_, img) -> r.subs(img).any { r.isSetOf(it, 2) } }
            val firstList = imgs.indexOfFirst { (_, img) -> r.subs(img).any { r.isListOf(it, 2) } }
            assertTrue(firstWrite >= 0 && firstSet > firstWrite && firstList > firstWrite,
                "nothing of list 2 before the block is written again (the block's first write: image $firstWrite; list 2's first SET $firstSet, first LIST $firstList)")
            notch(r, CfwModes.EV_UPROLL, "a notch in the new session")
        } finally { r.close() }
    }
}
