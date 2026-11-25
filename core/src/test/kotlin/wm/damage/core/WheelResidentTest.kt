package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import wm.damage.core.comp.CachedText
import wm.damage.core.gfx.IconKind
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellSettings
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.effects
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.EvenHubMsg

/**
 * C3 stage DW-1a (`private/c3-design/C3-DW1-amendment.md` §3.2–§3.5, §7.1; decisions CK): the switcher's wheel's block
 * resident in Main's region at ladder stage 5 — placed, staged ahead in idle, kept current in place — with nothing bound.
 * Context for the reader: Adam's own glasses on his own firmware fork; nothing here contacts a device — the glasses are
 * the simulator's model behind a start rig on a bit-11 build (DamageCaps 0xdff) at stage 5 with a 256 KiB session.
 */
class WheelResidentTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tmp: Path = Files.createTempDirectory("wheel-resident")
    @AfterTest fun clean() { scope.cancel(); tmp.toFile().deleteRecursively() }

    private class Win(id: String, name: String, icon: IconKind) : DamageWindow(id, name, icon) {
        override fun view(): WindowView = WindowView.ListView(ListModel(), { 0 }, { _, _, _, _ -> }, { _, _, _ -> }, {})
        override fun summary() = Summary("summary of $name", detail = "detail")
        override fun saveState(): JsonObject = JsonObject(emptyMap())
        override fun restoreState(state: JsonObject) {}
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    /** Main's region's last byte + 1 on a 256 KiB session with BIND: the wheel's block is its first tenant from there. */
    private val regionEnd = CfwModes.mainRegion(CfwModes.CACHE2_MAX_RIDERS, bind = true).last + 1

    /** A lone cache write (mode 19 or 25) whose every entry lies in the 20 KiB below Main's region's end — where only the
     *  wheel's block lies (decisions BQ (4): its cap). */
    private fun wheelWrite(img: ByteArray): Boolean {
        val mode = img[0].toInt() and 0x7F
        if (mode != 19 && mode != 25) return false
        val ws = ShipVectors.cacheWrites(img)
        return ws.isNotEmpty() && ws.all { (off, d) -> off >= regionEnd - CAP && off + d.size <= regionEnd }
    }

    /** One act's record: its label, each lens's content rows at its end (CRC-32 of the rows between the bars, 4bpp), and —
     *  not compared, for the report — the bytes and images each lens took in it (the wheel's own writes apart). */
    private class Act(val label: String, val endL: Long, val endR: Long, val bytes: Int, val images: Int, val wheel: List<String>, val kinds: Set<String>) {
        val line: String get() = "# $label: end L %08x R %08x".format(endL, endR)
    }

    /**
     * The switcher's acts at stage 5 at [height] (as `WheelStage4Test`'s at stage 4): a shell on a bit-11 build at stage 5
     * with a 256 KiB session, Global `Depth` 8, five windows opened in turn; the wheel opened by the chord, a notch down, a
     * notch up, three up (the third wraps), the commit, the wheel opened again and cancelled — each act settled before the
     * next, over a paced link (each image's ack 100 ms after the model took it). [start] receives the bytes and images
     * LEFT took from the session's start to the first act, and the wheel's among them.
     */
    private suspend fun acts(height: Int, start: MutableList<String> = ArrayList()): List<Act> {
        val dir = Files.createTempDirectory(tmp, "s")
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0xDFF }
        val images = java.util.concurrent.CopyOnWriteArrayList<Pair<Arm, ByteArray>>()
        sim.imageObserver = { a, img -> images += a to img.copyOf() }
        val c1 = dir.resolve("c1-stage").also { Files.writeString(it, wm.damage.core.transport.LadderRecord(stage = 5, sessionKib = 256).encode()) }
        val t = StartRig(sim, scope, c1StageFile = c1)
        val late = Channel<Triple<Long, Arm, ByteArray>>(Channel.UNLIMITED)
        t.onNotify = { a, p -> if (StartRig.sid(p) == EvenHubMsg.SID) { late.trySend(Triple(System.currentTimeMillis() + 100, a, p)); null } else p }
        scope.launch { for ((due, a, p) in late) { val w = due - System.currentTimeMillis(); if (w > 0) delay(w); t.deliver(a, p) } }
        scope.launch { while (true) { val now = System.currentTimeMillis(); for (a in Arm.entries) sim.conformanceWake(a, now); delay(4) } }
        val p = Persistence(dir.resolve("s.json"))
        p.put("shell.settings", ShellSettings(heightMode = height, depth = 8, cachedText = "on").toJson())
        val shell = Shell(CachedText(FakeText()), t, p, null, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
        val kinds = listOf(IconKind.READER, IconKind.TERMINAL, IconKind.FILES, IconKind.MUSIC, IconKind.FEED)
        for (i in 0 until 5) shell.register(Win("w$i", "Window $i", kinds[i]))
        shell.start()
        /** Settled: quiescent, and on one loop sample no cache work in flight, no count unjudged, the atlas's extensions all
         *  live, and no image more for 300 ms (each decided on one evaluation); the content rows' CRCs read in that sample. */
        suspend fun settle(what: String): Pair<Long, Long> {
            repeat(400) {
                waitUntil(60_000, 10, { "$what: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
                val n0 = images.size
                val rest: List<Long>? = shell.sampleIdle {
                    if (shell.cacheWorkInFlight || shell.cacheCountUnjudged != 0L || shell.atlasExtensionsLive != shell.atlasExtensionsQueued) emptyList<Long>()
                    else synchronized(sim) {
                        val c = shell.layout.content
                        Arm.entries.map { a -> java.util.zip.CRC32().also { it.update(sim.snapshot(a), c.y * 320, c.h * 320) }.value }
                    }
                }
                delay(300)
                if (rest != null && rest.size == 2 && shell.isQuiescent() && images.size == n0) return rest[0] to rest[1]
            }
            fail("$what: the shell never came to rest: ${shell.quiescenceReport()}")
        }
        waitUntil(60_000, 10, { "the start at stage 5: ${shell.quiescenceReport()}" }) { shell.isQuiescent() && t.state.value.effects && t.state.value.cacheSize == CfwModes.CACHE2_MAX_RIDERS }
        settle("the start")
        for (i in 0 until 5) { shell.services.runOnShell { shell.services.openWindow("w$i", null) }; settle("open w$i") }
        fun left(from: Int) = images.toList().drop(from).filter { it.first == Arm.LEFT }.map { it.second }
        left(0).let { ls -> start += "the start and five windows opened: ${ls.size} images, ${ls.sumOf { it.size }} B; " +
            "cache writes outside the block's 20 KiB ${ls.filter { (it[0].toInt() and 0x7F) in setOf(12, 19, 25) && !wheelWrite(it) }.let { w -> "${w.size} images, ${w.sumOf { it.size }} B" }}; " +
            "the block's ${ls.filter { wheelWrite(it) }.let { w -> "${w.size} images, ${w.sumOf { it.size }} B" }}" }
        val out = ArrayList<Act>()
        suspend fun act(label: String, vararg gestures: Int) {
            val from = images.size
            for (g in gestures) shell.postGesture(g)
            val (l, r) = settle(label)
            val ls = left(from)
            // the rules' idle SETTLE naming no list left out: it goes on a pace after the shell falls idle, so whether one falls
            // inside an act depends on how long the act waits (`WheelStage4Test`'s rule)
            val kinds = ls.filter { !wheelWrite(it) }.flatMap { subs(it) }
                .filter { s -> !(s.size == 10 && s[0].toInt() == 26 && s[1].toInt() == 5 && s[5] == 0xFF.toByte()) }
                .map { s -> (s[0].toInt() and 0x7F).let { m -> if (m == 26 || m == 27) "$m/${s[1]}" else "$m" } }.toSortedSet()
            out += Act(label, l, r, ls.filter { !wheelWrite(it) }.sumOf { it.size }, ls.count { !wheelWrite(it) }, ls.filter { wheelWrite(it) }.map { "${it.size} B" }, kinds)
        }
        act("open (the chord)", EvenHubMsg.EV_RING_LONG_PRESS, EvenHubMsg.EV_RING_LONG_PRESS_RELEASE, EvenHubMsg.EV_DOUBLE_CLICK)
        assertTrue(shell.switcherIsOpen, "$height: the chord opens the wheel")
        act("a notch down", EvenHubMsg.EV_SCROLL_BOTTOM)
        act("a notch up", EvenHubMsg.EV_SCROLL_TOP)
        act("up 1", EvenHubMsg.EV_SCROLL_TOP)
        act("up 2", EvenHubMsg.EV_SCROLL_TOP)
        act("up 3, the wrap", EvenHubMsg.EV_SCROLL_TOP)
        act("commit", EvenHubMsg.EV_CLICK)
        assertTrue(!shell.switcherIsOpen, "$height: the tap commits")
        act("open again", EvenHubMsg.EV_RING_LONG_PRESS, EvenHubMsg.EV_RING_LONG_PRESS_RELEASE, EvenHubMsg.EV_DOUBLE_CLICK)
        assertTrue(shell.switcherIsOpen, "$height: the chord opens the wheel again")
        act("cancel", EvenHubMsg.EV_DOUBLE_CLICK)
        assertTrue(!shell.switcherIsOpen, "$height: the double tap cancels")
        shell.stop()
        return out
    }

    private fun file(name: String): List<String>? = javaClass.getResource("/wheelwire/$name")?.readText()?.lines()?.filter { it.isNotBlank() }

    private fun subs(img: ByteArray): List<ByteArray> {
        if (img.isEmpty() || img[0].toInt() != 8) return listOf(img)
        val out = ArrayList<ByteArray>()
        var i = 2
        repeat(img[1].toInt() and 0xFF) {
            val n = (img[i].toInt() and 0xFF) or ((img[i + 1].toInt() and 0xFF) shl 8)
            out += img.copyOfRange(i + 2, i + 2 + n); i += 2 + n
        }
        return out
    }

    /**
     * DW-1a's "nothing bound": at stage 5 with the block resident, the switcher's open, turns, commit and cancel leave each
     * lens's content rows as they left them on the base before DW-1a (`wheelwire/stage5-h<height>.txt`, recorded on the base
     * `fe1a7b93` by this test's first run there: a missing file is written under `build/wheelwire/` and the test fails), and
     * nothing in them binds the wheel — no message of the bound lists' (mode 27) or the engine's (mode 26) that the base's
     * acts did not send either, and of the block's writes none while the wheel is open; after the commit (a new order) the
     * item table's only. (The messages themselves are not compared byte for byte: on this paced link two runs of the base
     * differed in how the flushes fell and where the atlas placed a glyph — the session's history, not the switcher's.)
     */
    @Test
    fun theSwitcherAtStage5DoesWhatItDidOnTheBaseWithTheBlockResident_DW1a(): Unit = runBlocking {
        val missing = ArrayList<String>()
        for (h in listOf(352, 480)) {
            val start = ArrayList<String>()
            val acts = acts(h, start)
            for (a in acts) println("DW-1a stage-5 act at $h '${a.label}': ${a.images} image(s) ${a.bytes} B on LEFT, kinds ${a.kinds}; the wheel's writes ${a.wheel}")
            println("DW-1a stage-5 at $h: ${start.joinToString()}")
            val got = acts.map { it.line } + acts.map { "# ${it.label}: kinds ${it.kinds.filter { k -> k.startsWith("26") || k.startsWith("27") }}" }
            val name = "stage5-h$h.txt"
            val want = file(name) ?: run {
                val o = Paths.get("build", "wheelwire").also { Files.createDirectories(it) }.resolve(name)
                Files.write(o, got)
                missing += "no recording $name: this run's acts written to ${o.toAbsolutePath()}"
                got
            }
            val ends = want.filter { ": end " in it }
            assertEquals(ends, acts.map { it.line }, "$h: each act's end on both lenses' content rows, against the base's")
            // the engine's and the bound lists' messages: none the base's acts did not send
            for ((i, a) in acts.withIndex()) {
                val baseKinds = want.firstOrNull { it.startsWith("# ${a.label}: kinds") }?.substringAfter("kinds ")?.removeSurrounding("[", "]")
                    ?.split(", ")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
                val mine = a.kinds.filter { it.startsWith("26") || it.startsWith("27") }.toSet()
                assertTrue(baseKinds.containsAll(mine), "$h act $i '${a.label}': mode-26/27 messages the base's act did not send: ${mine - baseKinds}")
                // while the wheel is open nothing of the block is written; after the commit (a new order) the item table alone
                if (a.label != "commit") assertEquals(emptyList(), a.wheel, "$h: a wheel write inside the act '${a.label}'")
            }
        }
        if (missing.isNotEmpty()) fail(missing.joinToString("\n"))
    }

    // ------------------------------------------------------------------------------------------- the shell's lane
    /** A window whose summary and preferred height a test sets. */
    private class Row(id: String, name: String, icon: IconKind) : DamageWindow(id, name, icon) {
        @Volatile var s = "summary of $name"
        @Volatile var h: Int? = null
        /** Main's row for this window ink-heavy (`MainLocalCheck.worstCase`'s summary). */
        @Volatile var heavy = false
        override val preferredHeight: Int? get() = h
        override fun view(): WindowView = WindowView.ListView(ListModel(), { 0 }, { _, _, _, _ -> }, { _, _, _ -> }, {})
        override fun summary() = if (heavy) Summary("M@W#".repeat(24), detail = "detail of $name", more = true) else Summary(s, detail = "detail of $name")
        override fun saveState(): JsonObject = JsonObject(emptyMap())
        override fun restoreState(state: JsonObject) {}
    }

    /**
     * A shell on a bit-11 build at stage 5 with a 256 KiB session (a ladder record holding stage 5 and 256 KiB), Global
     * `Depth` [depth], at [height], [n] windows registered (Settings beside them), started in Main — or, with [current], in
     * that window; the instant link; every image either lens takes kept in order; the engine woken every 4 ms.
     */
    private inner class Rig(val n: Int = 5, height: Int = 352, depth: Int = 8, current: String? = null, text: wm.damage.core.text.TextRasterizer = FakeText(),
                            name: (Int) -> String = { "Window $it" }) {
        val dir: Path = Files.createTempDirectory(tmp, "r")
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0xDFF }
        val t = StartRig(sim, scope, c1StageFile = dir.resolve("c1-stage").also { Files.writeString(it, wm.damage.core.transport.LadderRecord(stage = 5, sessionKib = 256).encode()) })
        val journal: Path = dir.resolve("journal.jsonl")
        val p = Persistence(dir.resolve("s.json")).also { p ->
            p.put("shell.settings", ShellSettings(heightMode = height, depth = depth, cachedText = "on").toJson())
            if (current != null) p.put("shell.state", JsonObject(mapOf("mode" to kotlinx.serialization.json.JsonPrimitive("WINDOW"),
                "current" to kotlinx.serialization.json.JsonPrimitive(current))))
        }
        val shell = Shell(CachedText(text), t, p, journal, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
        val kinds = listOf(IconKind.READER, IconKind.TERMINAL, IconKind.FILES, IconKind.MUSIC, IconKind.FEED, IconKind.TORRENTS, IconKind.GAMES)
        val rows = (0 until n).map { Row("w$it", name(it), kinds[it % kinds.size]) }
        /** Every top-level image a lens took, in order. */
        val images = java.util.concurrent.CopyOnWriteArrayList<Pair<Arm, ByteArray>>()

        init {
            for (r in rows) shell.register(r)
            sim.imageObserver = { a, img -> images += a to img.copyOf() }
            scope.launch { while (true) { val now = System.currentTimeMillis(); for (a in Arm.entries) sim.conformanceWake(a, now); delay(4) } }
        }

        fun notes(kind: String): List<String> = if (!Files.exists(journal)) emptyList() else Files.readAllLines(journal)
            .filter { "\"ev\":\"note\",\"kind\":\"$kind\"" in it }.map { it.substringAfter("\"detail\":\"").substringBefore("\"") }
        fun allNotes(): List<Pair<String, String>> = if (!Files.exists(journal)) emptyList() else Files.readAllLines(journal)
            .filter { "\"ev\":\"note\"" in it }.map { it.substringAfter("\"kind\":\"").substringBefore("\"") to it.substringAfter("\"detail\":\"").substringBefore("\"") }

        suspend fun until(what: String, maxMs: Long = 60_000, cond: () -> Boolean) =
            waitUntil(maxMs, 5, { "$what — wheel ${notes("wheel").takeLast(4)}; mainstage ${notes("mainstage").takeLast(2)}; stage ${notes("stage").takeLast(3)}; ${shell.quiescenceReport()}" }, cond)

        fun <T> onLoop(block: () -> T): T {
            val out = java.util.concurrent.CompletableFuture<T>()
            shell.services.runOnShell { out.complete(block()) }
            return out.get(10, java.util.concurrent.TimeUnit.SECONDS)
        }

        /** The wheel's lane has nothing left to write and no cache work is on its way — one evaluation on the loop — then
         *  the shell quiescent. */
        suspend fun done(what: String) {
            until("$what: the wheel's lane done") { shell.isQuiescent() && onLoop { shell.wheelLaneDoneForTest() && !shell.cacheWorkInFlight && shell.cacheCountUnjudged == 0L } }
            until("$what: settled") { shell.isQuiescent() }
        }

        suspend fun start() {
            shell.start()
            until("the start at stage 5 on 256 KiB") { t.state.value.effects && t.state.value.cacheSize == CfwModes.CACHE2_MAX_RIDERS }
            until("the atlas goes live") { shell.cachedTextActive && shell.cachedFontsLive.isNotEmpty() }
            done("the start")
        }

        suspend fun open(id: String) {
            shell.services.runOnShell { shell.services.openWindow(id, null) }
            until("$id in front") { onLoop { shell.currentWindowId() } == id }
            done("open $id")
        }

        fun block(): wm.damage.core.motion.Wheel.Block? = onLoop { shell.wheelBlockForTest() }

        /** The cache writes either lens took from image [from] on that fall in [b] (LEFT's: both lenses take the same), as
         *  the record each lands in ([part]) with its offset. */
        fun wheelWrites(from: Int, b: wm.damage.core.motion.Wheel.Block): List<String> = images.toList().drop(from).filter { it.first == Arm.LEFT }
            .flatMap { (_, img) -> if ((img[0].toInt() and 0x7F) in setOf(19, 25)) ShipVectors.cacheWrites(img) else emptyList() }
            .filter { (off, _) -> off >= b.base && off < regionEnd }.map { (off, d) -> "${part(b, off)} @$off ${d.size} B" }

        /** The messages LEFT took from image [from] on that write into [b] (their sizes) — the link's share of the block. */
        fun wheelMessages(from: Int, b: wm.damage.core.motion.Wheel.Block): List<Int> = images.toList().drop(from).filter { it.first == Arm.LEFT }
            .filter { (_, img) -> (img[0].toInt() and 0x7F) in setOf(19, 25) && ShipVectors.cacheWrites(img).any { (off, _) -> off >= b.base && off < regionEnd } }
            .map { it.second.size }

        /** Each lens's cache holds every write of [b] at its offset. */
        fun assertOnTheLenses(b: wm.damage.core.motion.Wheel.Block, what: String) {
            for (arm in Arm.entries) synchronized(sim) {
                val c = assertNotNull((if (arm == Arm.LEFT) sim.left else sim.right).textureCache, "$what: $arm holds a cache")
                for ((off, bytes) in b.writes()) assertTrue(bytes.contentEquals(c.copyOfRange(off, off + bytes.size)), "$what: $arm's cache at $off is not the block's ${part(b, off)}")
            }
        }

        suspend fun close() { shell.stop() }
    }

    /** The block's record [off] lies in. */
    private fun part(b: wm.damage.core.motion.Wheel.Block, off: Int): String = when {
        off in b.ruleOff until b.itemsOff -> "the rule"
        off in b.itemsOff until b.itemsOff + b.itemSlot -> "the item table"
        off >= b.progOff -> "the programs"
        else -> b.cellOffs.indices.firstOrNull { off in b.cellOffs[it] until b.cellOffs[it] + b.cellSlots[it] }?.let { "cell $it" } ?: "a gap at $off"
    }

    /** The item table a lens holds for [b]: its [count] off4s, as cell indices. */
    private fun lensOrder(r: Rig, b: wm.damage.core.motion.Wheel.Block, count: Int): List<Int> = synchronized(r.sim) {
        val c = r.sim.left.textureCache!!
        (0 until count).map { i -> ((c[b.itemsOff + 2 * i].toInt() and 0xFF) or ((c[b.itemsOff + 2 * i + 1].toInt() and 0xFF) shl 8)) * 4 }
            .map { off -> b.cellOffs.indexOf(off) }
    }

    /**
     * The amendment's §7.1 "Resident ahead": a session start in Main — Main's staging, then the block, counted with the
     * switcher never opened (the journal's order); the block is the first tenant from Main's region's end, a cell per
     * registered window (Main's entry, the five windows and Settings), the item table Main's alone (nothing visited yet), and
     * both lenses hold every byte of it at its offset; `wheelBase` is its base.
     */
    @Test
    fun theBlockIsStagedAheadAfterMainsStagingAndCountedWithTheWheelNeverOpened_DW1a(): Unit = runBlocking {
        val r = Rig()
        try {
            r.start()
            val b = assertNotNull(r.block(), "the block placed: ${r.notes("wheel")}")
            val all = r.allNotes()
            val staged = all.indexOfFirst { it.first == "mainstage" && it.second.startsWith("staged") }
            val placed = all.indexOfFirst { it.first == "wheel" && it.second.startsWith("placed") }
            val counted = all.indexOfFirst { it.first == "wheel" && it.second.startsWith("counted") }
            println("DW-1a resident ahead in Main: ${r.notes("wheel")}")
            val msgs = r.wheelMessages(0, b)
            println("DW-1a the first staging on the model (FakeText faces, drawn icons): ${msgs.size} message(s) ${msgs} = ${msgs.sum()} B for a block of ${b.size} B (${b.writes().sumOf { it.second.size }} B written)")
            assertTrue(staged in 0 until placed && placed < counted, "Main staged, then the block placed, then counted: ${all.filter { it.first == "wheel" || it.first == "mainstage" }}")
            assertTrue(!r.shell.switcherIsOpen)
            assertEquals(wm.damage.core.shell.WheelResident.baseFor(regionEnd, b.size), b.base, "the first tenant from the region's end")
            assertTrue(b.end <= regionEnd && b.size <= CAP, "${b.describe()}")
            assertEquals(7, b.cells.size, "a cell per registered window: Main's, five and Settings")
            assertEquals(listOf(0), b.order.toList(), "Main's entry alone: nothing visited")
            assertEquals(b.base, r.onLoop { r.shell.wheelBaseForTest() })
            assertTrue(r.onLoop { r.shell.wheelRecordsForTest() }.all { it.state == wm.damage.core.shell.StagedRecord.State.PROVEN })
            r.assertOnTheLenses(b, "in Main")
        } finally { r.close() }
    }

    /** "Resident ahead", in a window: a session start with a window in front writes the block with no staging of Main's before
     *  it; the item table is Main's and that window's. */
    @Test
    fun aStartInAWindowWritesTheBlockWithNoStagingOfMainsBeforeIt_DW1a(): Unit = runBlocking {
        val r = Rig(current = "w2")
        try {
            r.start()
            assertEquals("w2", r.onLoop { r.shell.currentWindowId() })
            val b = assertNotNull(r.block(), "the block placed: ${r.notes("wheel")}")
            val all = r.allNotes()
            val placed = all.indexOfFirst { it.first == "wheel" && it.second.startsWith("placed") }
            assertTrue(placed >= 0 && all.take(placed).none { it.first == "mainstage" && it.second.startsWith("staged") }, "no staging of Main's before the block: $all")
            assertEquals(listOf(0, 3), b.order.toList(), "Main's entry and w2's (the registered order: Main, w0, w1, w2, …)")
            assertEquals(listOf(0, 3), lensOrder(r, b, 2))
            r.assertOnTheLenses(b, "in w2")
        } finally { r.close() }
    }

    /**
     * DW-1a binds nothing, so nothing of the block goes while the wheel is open: a dirty flip on a window behind the open
     * wheel writes nothing until the wheel closes; at the cancel that window's cell alone goes.
     */
    @Test
    fun nothingOfTheBlockGoesWhileTheWheelIsOpen_DW1a(): Unit = runBlocking {
        val r = Rig(n = 3)
        try {
            r.start()
            r.open("w0"); r.open("w1")
            for (g in listOf(EvenHubMsg.EV_RING_LONG_PRESS, EvenHubMsg.EV_RING_LONG_PRESS_RELEASE, EvenHubMsg.EV_DOUBLE_CLICK)) r.shell.postGesture(g)
            r.until("the wheel open") { r.shell.switcherIsOpen && r.shell.isQuiescent() }
            val from = r.images.size
            r.shell.services.runOnShell { r.rows[2].dirty = true; r.shell.services.requestRender(r.rows[2]) }
            delay(1_500)
            r.until("settled with the wheel open") { r.shell.isQuiescent() }
            val b = assertNotNull(r.block())
            assertEquals(emptyList(), r.wheelWrites(from, b), "no wheel write while the wheel is open")
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.until("the wheel closed") { !r.shell.switcherIsOpen }
            r.done("after the cancel")
            assertEquals(listOf("cell 3"), r.wheelWrites(from, assertNotNull(r.block())).map { it.substringBefore(" @") }.distinct(), "w2's cell after the close")
        } finally { r.close() }
    }

    /**
     * The envelope's (a): every `stageClear` takes the block with it, and it is written again — here Cached text turned off
     * and on again (its clear, "cached text went off"): the block placed a second time, counted, and on both lenses.
     */
    @Test
    fun aClearedRegistryWritesTheBlockAgain_DW1a(): Unit = runBlocking {
        val r = Rig()
        try {
            r.start()
            assertEquals(1, r.notes("wheel").count { it.startsWith("placed") })
            r.shell.updateSettings { it.copy(cachedText = "off") }
            r.until("the block dropped") { r.notes("wheel").any { it.startsWith("dropped") } }
            r.shell.updateSettings { it.copy(cachedText = "on") }
            r.until("the atlas live again") { r.shell.cachedTextActive && r.shell.cachedFontsLive.isNotEmpty() }
            r.until("placed again") { r.notes("wheel").count { it.startsWith("placed") } == 2 }
            r.done("placed again")
            val b = assertNotNull(r.block())
            assertTrue(r.onLoop { r.shell.wheelRecordsForTest() }.all { it.state == wm.damage.core.shell.StagedRecord.State.PROVEN })
            r.assertOnTheLenses(b, "placed again")
            println("DW-1a cleared and placed again: ${r.notes("wheel")}")
        } finally { r.close() }
    }

    /**
     * The amendment's §7.1 "A cell per registered window" (decisions CK A1): a process start (nothing visited), then six
     * first visits — the base and every cell's offset never move, and each visit writes the item table alone, which then
     * holds the switcher's order (Main, the window in front, the rest by recency).
     */
    @Test
    fun firstVisitsWriteTheItemTableAloneAndNothingMoves_DW1a(): Unit = runBlocking {
        val r = Rig(n = 6)
        try {
            r.start()
            val b0 = assertNotNull(r.block())
            val visited = ArrayList<Int>()
            for (i in 0 until 6) {
                val from = r.images.size
                r.open("w$i")
                val b = assertNotNull(r.block())
                assertEquals(b0.base, b.base, "visit $i: the base")
                assertEquals(b0.cellOffs.toList(), b.cellOffs.toList(), "visit $i: the cells' offsets")
                val ws = r.wheelWrites(from, b)
                assertTrue(ws.isNotEmpty() && ws.all { it.startsWith("the item table") }, "visit $i writes the item table alone: $ws")
                visited.add(0, i + 1)
                assertEquals(listOf(0) + visited, b.order.toList(), "visit $i: Main, then by recency")
                assertEquals(listOf(0) + visited, lensOrder(r, b, b.n), "visit $i: the lens's item table")
            }
            println("DW-1a first visits: ${r.notes("wheel").filter { it.startsWith("kept") }}")
        } finally { r.close() }
    }

    /**
     * The amendment's §7.1 "Kept current in place" (the closed half): between two windows at one height only the item table
     * goes; a dirty flip on a window not in front rewrites that window's cell alone; a switch between windows at 352 and 480
     * rewrites the programs' slot and the item table; the Depth alone, the programs' slot alone — never another record,
     * and the lenses hold the block as kept.
     */
    @Test
    fun aChangeRewritesOnlyTheRecordItChanges_DW1a(): Unit = runBlocking {
        val r = Rig(n = 3)
        try {
            r.rows[2].h = 480
            r.start()
            r.open("w0"); r.open("w1"); r.open("w0")
            var b = assertNotNull(r.block())
            // a commit to another window at the same height: the item table
            var from = r.images.size
            r.open("w1")
            b = assertNotNull(r.block())
            assertEquals(listOf("the item table"), r.wheelWrites(from, b).map { it.substringBefore(" @") }.distinct(), "w0 → w1")
            // a dirty flip on w0, not in front: its cell (index 1) alone
            from = r.images.size
            r.shell.services.runOnShell { r.rows[0].dirty = true; r.shell.services.requestRender(r.rows[0]) }
            r.until("the dirty cell kept") { r.notes("wheel").any { "(dirty)" in it && it.startsWith("kept") } }
            r.done("the dirty flip")
            b = assertNotNull(r.block())
            assertEquals(listOf("cell 1"), r.wheelWrites(from, b).map { it.substringBefore(" @") }.distinct(), "the dirty flip")
            // w1 (352) → w2 (480): the programs and the item table
            from = r.images.size
            r.open("w2")
            b = assertNotNull(r.block())
            assertEquals(setOf("the programs", "the item table"), r.wheelWrites(from, b).map { it.substringBefore(" @") }.toSet(), "w1 at 352 → w2 at 480")
            assertEquals(r.onLoop { r.shell.layout.switcherPanel }, b.geo.panel, "the programs for w2's layout (480)")
            // the Depth alone (the order unchanged): the programs alone, at the new disparity
            from = r.images.size
            r.shell.updateSettings { it.copy(depth = 4) }
            r.until("the programs kept at Depth 4") { r.notes("wheel").any { it.startsWith("kept — the programs") && " d 4)" in it } }
            r.done("Depth 4")
            b = assertNotNull(r.block())
            assertEquals(listOf("the programs"), r.wheelWrites(from, b).map { it.substringBefore(" @") }.distinct(), "Depth 8 → 4 in w2")
            assertEquals(4, b.geo.d)
            r.assertOnTheLenses(b, "after the switches")
            println("DW-1a kept current: ${r.notes("wheel").filter { it.startsWith("kept") }}; the messages of the switch to 480: ${r.wheelMessages(from, b)}")
        } finally { r.close() }
    }

    /**
     * The amendment's §7.1 "Page staging leaves the block" (decisions CK A7): with the block counted, Page staging's drops as
     * a book change (`Staging.retain`), an Encode act's lists over the whole cache (`stagePagesDropUnder`) and its own
     * clear (`stagePagesClear`) make them leave the block's records in the registry, proven, with no wheel write after and
     * none counted as a drawable strip.
     */
    @Test
    fun pageStagingsDropsLeaveTheBlock_DW1a(): Unit = runBlocking {
        val r = Rig()
        try {
            r.start()
            val before = r.onLoop { r.shell.wheelRecordsForTest() }
            assertTrue(before.isNotEmpty() && before.all { it.state == wm.damage.core.shell.StagedRecord.State.PROVEN })
            val from = r.images.size
            val notes0 = r.notes("wheel").size
            r.onLoop { r.shell.pageStagingDropsForTest() }
            delay(500)
            r.done("after Page staging's drops")
            val after = r.onLoop { r.shell.wheelRecordsForTest() }
            assertEquals(before.map { System.identityHashCode(it) }, after.map { System.identityHashCode(it) }, "the same records")
            assertTrue(after.all { it.state == wm.damage.core.shell.StagedRecord.State.PROVEN })
            assertEquals(emptyList(), r.wheelWrites(from, assertNotNull(r.block())), "no wheel write after the drops")
            assertEquals(notes0, r.notes("wheel").size, "no wheel note after the drops: ${r.notes("wheel")}")
            assertEquals(0, r.shell.stagedProvenCount, "the block is not a drawable strip")
        } finally { r.close() }
    }

    /**
     * The amendment's §7.1 "The cap" (decisions BQ (4)): 20,480 B is placed, 20,481 B is not ([WheelResident.refusal]); and on
     * the shell, a block past the cap (twenty-four windows registered) is never placed — no wheel record, one note with its
     * bytes, said once although the item table it would hold changes.
     */
    @Test
    fun aBlockPastTheCapIsNotPlacedAndSaidOnce_DW1a(): Unit = runBlocking {
        val end = regionEnd
        assertEquals(null, wm.damage.core.shell.WheelResident.refusal(end, CAP, null))
        assertTrue(assertNotNull(wm.damage.core.shell.WheelResident.refusal(end, CAP + 1, null), "a block of ${CAP + 1} B is past the cap").contains("${CAP + 1} B"))
        assertTrue(wm.damage.core.shell.WheelResident.refusal(end, 10_000, end - 9_000) != null, "Main's staging over the base")
        assertEquals(null, wm.damage.core.shell.WheelResident.refusal(end, 10_000, wm.damage.core.shell.WheelResident.baseFor(end, 10_000)))
        val r = Rig(n = 24)
        try {
            r.start()
            r.open("w0"); r.open("w1")
            assertEquals(null, r.block())
            assertEquals(emptyList(), r.onLoop { r.shell.wheelRecordsForTest() })
            val said = r.notes("wheel")
            println("DW-1a past the cap: $said")
            assertEquals(1, said.size, "one note: $said")
            val bytes = Regex("its (\\d+) B pass the cap").find(said[0])?.groupValues?.get(1)?.toInt()
            assertTrue(bytes != null && bytes > CAP, "the note gives the block's bytes: $said")
        } finally { r.close() }
    }

    /**
     * The amendment's §7.1 "Main wins" (the closed half, decisions BD): Main's staging laid out to meet the block drops it —
     * its records leave the registry, one `dropped` note and one `not placed` note however many pumps follow — and a
     * smaller staging of Main's places it again. Main grows by its rows' content: one more of its rows ink-heavy at a time,
     * each followed by a `Depth` change that stages Main whole again (its planes changed), until its staging ends past the
     * block's base; it shrinks the same way back. The faces are `NoisyText`'s (glyphs that compress as text on glass does
     * not: FakeText's whole rows reached only 192,876 of the 210,908 needed) and the windows' names short, so the block's
     * cells stay small while Main's rows grow.
     */
    @Test
    fun mainsStagingOverTheBlockDropsItAndASmallerOnePlacesItAgain_DW1a(): Unit = runBlocking {
        val r = Rig(n = MAIN_WINS_ROWS, text = NoisyText(), name = { "W$it" })
        try {
            r.start()
            val b0 = assertNotNull(r.block(), "placed at the start: ${r.notes("wheel")}")
            val ends = arrayListOf(assertNotNull(r.onLoop { r.shell.mainBinding.staging?.end }))
            var depth = 8
            /** Main staged whole again with the rows as they are now (or refused): its staging's end, null when refused. */
            suspend fun restage(): Int? {
                val notes0 = r.notes("mainstage").count { it.startsWith("staged") || "cannot be staged" in it }
                depth = if (depth == 8) 4 else 8
                r.shell.updateSettings { it.copy(depth = depth) }
                r.until("Main staged whole again at Depth $depth") { r.notes("mainstage").count { it.startsWith("staged") || "cannot be staged" in it } > notes0 &&
                    r.onLoop { r.shell.mainBinding.staging == null || r.shell.mainBinding.counted } }
                r.done("Main at Depth $depth")
                return r.onLoop { r.shell.mainBinding.staging?.end }
            }
            var k = 0
            while (ends.last() <= b0.base && k < r.rows.size) {
                r.rows[k++].heavy = true
                ends += restage() ?: fail("Main's staging was refused at $k ink-heavy rows before it reached the block's base ${b0.base}: ends $ends")
            }
            println("DW-1a Main wins: Main's staging ended at $ends ($k rows ink-heavy); the block at ${b0.base} (${b0.size} B); wheel ${r.notes("wheel")}")
            assertTrue(ends.last() > b0.base, "the fixture: Main's grown staging (${ends.last()}) reaches the block's base (${b0.base})")
            assertEquals(null, r.block())
            assertEquals(emptyList(), r.onLoop { r.shell.wheelRecordsForTest() })
            delay(1_000)
            val wheel = r.notes("wheel")
            assertEquals(1, wheel.count { it.startsWith("dropped — Main's staging") }, "one drop: $wheel")
            assertEquals(1, wheel.count { it.startsWith("not placed — Main's staging") }, "one not-placed note: $wheel")
            for (w in r.rows) w.heavy = false
            assertTrue(assertNotNull(restage()) <= b0.base, "Main smaller again")
            r.until("placed again") { r.notes("wheel").count { it.startsWith("placed") } >= 2 }
            r.done("Main smaller again")
            val b2 = assertNotNull(r.block())
            assertTrue(assertNotNull(r.onLoop { r.shell.mainBinding.staging?.end }) <= b2.base)
            r.assertOnTheLenses(b2, "placed again")
        } finally { r.close() }
    }

    /**
     * The block's records, kept current (`Wheel.Block.rebuilt`, the amendment's §3.4) on the drawn icons at Depth 8: another
     * item table rewrites the item table alone; a dirty tick that fits its cell's slot, that cell alone; another height,
     * the programs alone (a cell is the same picture at every height); a cell past its slot, or a cell more, no block on
     * these offsets (the block is placed anew); and the size never hangs on the item table.
     */
    @Test
    fun aRebuiltBlockDiffersOnlyInTheRecordThatChanged_DW1a() {
        val fx = wm.damage.core.motion.WheelCheck.effects()
        val m = wm.damage.core.shell.MainStaging.ScrollMotion.DEFAULT
        val rig = wm.damage.core.motion.WheelCheck.Rig(FakeText(), 352, 9, 8)
        val cells = rig.cells()
        val b = rig.block(cells = cells)
        fun changed(o: wm.damage.core.motion.Wheel.Block) = o.parts().filter { p -> !p.same(b.parts().first { it.name == p.name }) }.map { it.name }
        assertEquals(listOf("item table"), changed(assertNotNull(b.rebuilt(rig.geo, cells, intArrayOf(0, 4, 2), fx, m))))
        assertEquals(b.size, wm.damage.core.motion.Wheel.block(b.base, rig.geo, cells, fx, m, order = intArrayOf(0, 4, 2)).size, "the size does not hang on the order")
        val dirty = wm.damage.core.motion.WheelCheck.Rig(FakeText(), 352, 9, 8, dirty = setOf(2)).cells()
        assertEquals(listOf("cell 3"), changed(assertNotNull(b.rebuilt(rig.geo, dirty, b.order, fx, m))), "a dirty tick on the third window (cell 3)")
        val r480 = wm.damage.core.motion.WheelCheck.Rig(FakeText(), 480, 9, 8)
        val c480 = r480.cells()
        assertTrue(cells.indices.all { cells[it].encoded.contentEquals(c480[it].encoded) }, "the cells at 480 are the cells at 352")
        assertTrue(rig.geo.panel != r480.geo.panel)
        assertEquals(listOf("programs"), changed(assertNotNull(b.rebuilt(r480.geo, cells, b.order, fx, m))), "another height")
        val big = cells.toMutableList().also { it[1] = wm.damage.core.motion.Wheel.Cell(it[1].entry, it[1].pic, ByteArray(b.cellSlots[1] + 4) { 7 }) }
        assertEquals(null, b.rebuilt(rig.geo, big, b.order, fx, m), "a cell past its slot")
        assertEquals(null, b.rebuilt(rig.geo, cells + cells[1], b.order, fx, m), "a cell more")
    }

    // ------------------------------------------------------------------------------- DW-1aF: the first review's fixes
    /**
     * V1-DW1a F1: a session start in Main where the count's first reading after each write into Main's records comes back
     * one short inside the settle window (pending, asked again half a second on — as the glasses' first reading after a
     * write often does): Main is counted and offered before the block's first piece goes. Watched to fail on `5f21143c`: the
     * wheel's lane placed the block in the pending pace, and the reading that counted Main waited for every piece of it.
     */
    @Test
    fun mainIsOfferedBeforeTheBlocksFirstPieceWhenItsReadingIsPending_DW1aF(): Unit = runBlocking {
        val r = Rig()
        val mainLow = CfwModes.mainRegion(CfwModes.CACHE2_MAX_RIDERS, bind = true).first
        val mainWrites = java.util.concurrent.atomic.AtomicInteger()   // cache writes LEFT took into Main's records (below the cap)
        val askedAt = java.util.concurrent.atomic.AtomicInteger()      // [mainWrites] when the last count reading was asked
        val shortFor = java.util.concurrent.atomic.AtomicInteger()     // the last [mainWrites] whose first reading was answered short
        val shorted = java.util.concurrent.atomic.AtomicInteger()
        val keep = r.sim.imageObserver
        r.sim.imageObserver = { a, img ->
            keep?.invoke(a, img)
            if (a == Arm.LEFT && (img[0].toInt() and 0x7F) in setOf(19, 25) &&
                ShipVectors.cacheWrites(img).any { (off, _) -> off >= mainLow && off < regionEnd - CAP }) mainWrites.incrementAndGet()
        }
        r.t.onWrite = { _, p -> if (StartRig.damageOp(p) == wm.damage.core.wire.DamageMsg.OP_TELEMETRY) askedAt.set(mainWrites.get()); p }
        r.t.onNotify = { _, p ->
            val g = StartRig.telemetry(p)?.cacheGen
            val m = askedAt.get()
            if (g != null && g > 0 && m > 0 && shortFor.getAndSet(m) != m) { shorted.incrementAndGet(); StartRig.withRecordField(p, 17, g - 1) } else p
        }
        try {
            r.start()
            val b = assertNotNull(r.block(), "the block placed: ${r.notes("wheel")}")
            val all = r.allNotes()
            val offered = all.indexOfFirst { it.first == "mainstage" && it.second.startsWith("offered") }
            val staged = all.take(maxOf(offered, 0)).indexOfLast { it.first == "mainstage" && it.second.startsWith("staged") }
            val placed = all.indexOfFirst { it.first == "wheel" && it.second.startsWith("placed") }
            val pending = (maxOf(staged, 0) until maxOf(offered, 0)).filter { "pending" in all[it].second }
            val seen = all.filter { it.first in setOf("wheel", "mainstage") || "pending" in it.second }
            println("DW-1aF F1: $shorted reading(s) answered one short after $mainWrites write(s) into Main's records; $seen")
            assertTrue(staged >= 0 && offered > staged && pending.isNotEmpty(),
                "precondition: Main staged, a reading after its records pending, then Main offered: $seen")
            assertTrue(placed > offered, "Main offered before the block's first piece: $seen")
            assertTrue(r.onLoop { r.shell.wheelRecordsForTest() }.all { it.state == wm.damage.core.shell.StagedRecord.State.PROVEN })
            r.assertOnTheLenses(b, "after Main's count")
        } finally { r.close() }
    }

    /**
     * V1-DW1a F2: a notice's faces going live after the block is placed change the faces live on the glasses (`atlasLive`,
     * which drops Page staging's strips and has Main's rows painted again): the wheel's cells are painted again too, the block
     * is built anew from those paints (only a part whose bytes differ written in place), and both lenses hold it. Watched to
     * fail on `5f21143c` (the cells kept their paints under the old set) and on the review's one line alone (the cells painted
     * again, the block never rebuilt from them: its want was still current for the unchanged keys).
     */
    @Test
    fun aChangeOfTheLiveFacesPaintsTheCellsAgain_DW1aF(): Unit = runBlocking {
        val r = Rig()
        try {
            r.start()
            val b0 = assertNotNull(r.block())
            val live0 = r.onLoop { r.shell.cachedFontsLive.toSet() }
            val from = r.images.size
            r.shell.postNotice(wm.damage.core.shell.Notifications.Notice("SMS · WARM", "t0", "the faces go live", "12:00"))
            r.until("the notice's faces live") { r.onLoop { r.shell.cachedFontsLive.toSet() } != live0 }
            repeat(wm.damage.core.shell.Notifications.GRACE_STEPS) {
                if (!r.onLoop { r.shell.notifications.focused }) { r.shell.graceStepByHand(); r.until("a grace step") { r.shell.isQuiescent() } }
            }
            assertTrue(r.onLoop { r.shell.notifications.focused }, "the notice took focus")
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)                     // focused: dismissed
            r.until("the notice dismissed") { r.onLoop { !r.shell.notifications.active } }
            r.done("the live faces changed")
            val b = assertNotNull(r.block())
            println("DW-1aF F2: live ${live0.size} -> ${r.onLoop { r.shell.cachedFontsLive.size }} face(s); wheel writes ${r.wheelWrites(from, b)}; ${r.notes("wheel").takeLast(3)}")
            assertEquals(b0.base, b.base, "kept in place")
            assertTrue(b.cells.indices.all { b.cells[it] !== b0.cells[it] }, "every cell painted again under the new live set, and the block built from those paints")
            r.assertOnTheLenses(b, "after the live faces changed")
        } finally { r.close() }
    }

    /**
     * V1-DW1a F3: an exclusive screen (Music Mode's kind) at a non-zero Depth — the chord never arms there and the double tap
     * returns to the window, so the wheel would open at that window's disparity: nothing of the block is written on entering
     * it or on leaving it. Watched to fail on `5f21143c` (the programs' slot was rewritten for d 0 on entry and for d 8 on exit).
     */
    @Test
    fun anExclusiveVisitWritesNothingOfTheBlock_DW1aF(): Unit = runBlocking {
        val r = Rig(n = 3)
        try {
            r.start()
            r.open("w1")
            assertNotNull(r.block())
            val kept0 = r.notes("wheel").size
            val from = r.images.size
            assertTrue(r.onLoop { r.shell.services.enterExclusive(r.rows[1]) }, "w1 takes the whole panel")
            r.until("exclusive") { r.onLoop { r.shell.exclusiveMode } }
            r.done("exclusive")
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)                     // the double tap returns to the window
            r.until("back in w1") { r.onLoop { !r.shell.exclusiveMode && r.shell.currentWindowId() == "w1" } }
            r.done("back in w1")
            val b = assertNotNull(r.block())
            val notes = r.notes("wheel").drop(kept0)
            assertEquals(emptyList(), r.wheelWrites(from, b), "nothing of the block written across the visit: $notes")
            assertTrue(notes.none { it.startsWith("kept") || it.startsWith("placed") }, "no write of the wheel's across the visit: $notes")
            r.assertOnTheLenses(b, "back in w1")
        } finally { r.close() }
    }

    private companion object {
        /** The windows `mainsStagingOverTheBlock…` registers: enough that Main's ink-heavy staging reaches the block. */
        const val MAIN_WINS_ROWS = 8
        /** Decisions BQ (4): the wheel's block may take up to 20 KiB of Main's region. */
        const val CAP = 20 * 1024
    }
}
