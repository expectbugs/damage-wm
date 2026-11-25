package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.local.BoundList
import wm.damage.core.local.Taps
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.MainBinding
import wm.damage.core.shell.MainStaging
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellSettings
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.EvenHubMsg

/**
 * K1-2 stage 3a (the K1-2 spec §5, §6, §8 pins 1–7 and 11–14; decisions CK A8, CV): a window's list bound on the glasses
 * as glasses list 1 (entries 3–5, slot 0) by the real shell, end to end in the firmware model. Context for the reader:
 * Adam's own glasses on his own firmware fork; nothing here contacts a device — the glasses are the firmware model behind
 * [StartRig] on a bit-11 build (DamageCaps 0xdff) at the ladder's stage 5 on a 256 KiB session, its engine woken every
 * 4 ms. Every gesture is fired through the input hook on both lenses, then its stock message reaches the shell through the
 * transport after the 115 (§6.1, P2), as `MainLocalShellTest` does for Main.
 */
class WindowListShellTest {
    private companion object {
        val ROW_NAMES = listOf("Font", "Depth", "Notify", "Height", "Sort", "Refresh", "Preview", "Quiet", "Wrap", "Autoplay", "Shuffle", "Repeat")
        /** His app categories and their rows in all (`K1-2-spec.md` §3, counted on his phone): style rows plus the app's own. */
        val HIS_APPS = listOf("Reader" to 9, "Tmux" to 9, "Files" to 6, "Torrents" to 9, "Music" to 35, "Games" to 13, "Feed" to 16, "Bastion" to 3)
    }

    /** A window in Main's list (its own view a list of [n] rows, each row drawn as marks of its own, when [list]). */
    private class Win(id: String, name: String, @Volatile var n: Int = 0, val list: Boolean = false) : DamageWindow(id, name, IconKind.FILES) {
        @Volatile var heavy = false
        /** Its own rows in its Settings category (beside the shell's style rows), each an On/Off row of a real-looking name. */
        @Volatile var appRows = 0
        override fun appSettings(): List<wm.damage.core.shell.HostSetting> = List(appRows) { i ->
            wm.damage.core.shell.HostSetting(ROW_NAMES[i % ROW_NAMES.size] + (if (i >= ROW_NAMES.size) " ${i / ROW_NAMES.size + 1}" else ""),
                listOf("On", "Off"), { if (i % 2 == 0) "On" else "Off" }, {})
        }
        val model = ListModel()
        /** K1-2 stage 3b: its own height (null: Global's); what row i draws — row [order]`[i]`'s marks (a reorder). */
        @Volatile var height: Int? = null
        override val preferredHeight: Int? get() = height
        @Volatile var order: IntArray? = null
        /** Decisions CY: its title (a window's 4-second notice is its title). */
        @Volatile var notice = ""
        override fun title(): String = notice
        override fun view(): WindowView = WindowView.ListView(model, { n },
            { g, i, r, _ ->
                val k0 = order?.getOrNull(i) ?: i
                for (k in 0..(k0 % 5)) g.fillRect(r.x + 12 + k * 22 + (k0 * 7) % 40, r.y + 6, 14, 12, 120 + 20 * (k % 4)) },
            { g, r, i -> g.fillRect(r.x + 4, r.y + 4, 8 + (i * 13) % 200, r.h - 8, 255) }, {})
        override fun summary() = if (heavy) Summary("M@W#".repeat(24), detail = "detail of $name", more = true) else Summary("summary of $name", detail = "detail")
        override fun saveState(): JsonObject = JsonObject(emptyMap())
        override fun restoreState(state: JsonObject) {}
    }

    private class Rig(val stage: Int = 5, features: Int = 0xdff, nRows: Int = 4, text: wm.damage.core.text.TextRasterizer = FakeText(),
                      startOn: String? = null, val extra: List<Win> = emptyList(), names: List<String>? = null,
                      tweak: (ShellSettings) -> ShellSettings = { it }) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp = Files.createTempDirectory("damage-windowlist")
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = features }
        val t = StartRig(sim, scope, c1StageFile = tmp.resolve("c1-stage").also {
            Files.writeString(it, wm.damage.core.transport.LadderRecord(stage = stage, sessionKib = 256).encode()) })
        val journal = tmp.resolve("journal.jsonl")
        val persistence = Persistence(tmp.resolve("state.json")).also { p ->
            p.put("shell.settings", tweak(ShellSettings().copy(heightMode = 352, cachedText = "on", taps = Taps.REPORT)).toJson())
            if (startOn != null) p.put("shell.state", JsonObject(mapOf("mode" to kotlinx.serialization.json.JsonPrimitive("WINDOW"),
                "current" to kotlinx.serialization.json.JsonPrimitive(startOn))))
        }
        val shell = Shell(CachedText(text), t, persistence, journal, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
        val rows = names?.mapIndexed { i, n -> Win("w$i", n) } ?: (0 until nRows).map { Win("w$it", "W$it") }
        val right = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        /** K1-2 stage 3b: called with each image RIGHT takes, before the model applies it (the sim's own thread). */
        @Volatile var onImage: ((ByteArray) -> Unit)? = null

        init {
            for (r in rows + extra) shell.register(r)
            sim.imageObserver = { a, img -> if (a == Arm.RIGHT) { onImage?.invoke(img); right += img } }
            scope.launch { while (true) { val now = System.currentTimeMillis(); for (a in Arm.entries) sim.conformanceWake(a, now); delay(4) } }
        }

        fun notes(kind: String): List<String> = if (!Files.exists(journal)) emptyList() else Files.readAllLines(journal)
            .filter { "\"ev\":\"note\",\"kind\":\"$kind\"" in it }.map { it.substringAfter("\"detail\":\"").substringBefore("\"") }

        suspend fun until(what: String, maxMs: Long = 60_000, cond: () -> Boolean) =
            waitUntil(maxMs, 5, { "$what — liststage ${notes("liststage").takeLast(4)}; mainstage ${notes("mainstage").takeLast(2)}; " +
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
                shell.windowListLaneDoneForTest() && !shell.cacheWorkInFlight && shell.transitionPhaseForTest() == null } }
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

        /** The window list bound on RIGHT: slot 0 is the window list's, and RIGHT holds its scroll entries (list 1). */
        suspend fun windowBound(what: String) {
            until("$what: the window list bound on RIGHT") { onLoop { shell.slot0ForTest() == "window" && shell.windowBoundForTest().first != null } &&
                synchronized(sim) { sim.bindForTest(Arm.RIGHT).entries.filterNotNull().count { it.list == 1 && !it.tap } == 2 } }
            settle(what)
        }

        fun list(): BoundList = assertNotNull(onLoop { shell.windowBoundForTest().first })
        fun row(): Int = onLoop { shell.windowBoundForTest().second }
        fun q(): Long = onLoop { shell.localQForTest() }
        fun motionMs(): Long = onLoop { shell.windowListBinding.staging?.let { it.t * it.periodInForce * 1000L / 1024 } ?: 0L }

        fun fire(eventId: Int): Int {
            val now = System.currentTimeMillis()
            for (a in Arm.entries) sim.conformanceInput(a, CfwModes.DEV_RING, eventId.toLong(), 0, now)
            t.ringStockForTest(BoundList.stockEvent(eventId)!!)
            return sim.bindForTest(Arm.RIGHT).seq
        }

        fun entriesOn(a: Arm, list: Int): List<Int> = synchronized(sim) { sim.bindForTest(a).entries.withIndex().filter { it.value?.list == list }.map { it.index } }

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
                    if (synchronized(sim) { listOf(sim.left, sim.right).any { it.motion.slots[wm.damage.core.motion.PopupPrograms.SLOT]?.drawing == true } })
                        return@sampleIdle "timer"
                    val out = StringBuilder()
                    for (left in listOf(true, false)) {
                        val b = shell.comp.expectedLens(left); val g = panel(left)
                        loop@ for (y in 0 until Geometry.PANEL_H) for (x in 0 until Geometry.PANEL_W)
                            if (wm.damage.core.gfx.Pack.level(b[x, y]) * 17 != g[x, y]) { out.append("${if (left) "LEFT" else "RIGHT"} belief != glass at ($x,$y); "); break@loop }
                    }
                    out.toString()
                }
                if (r == "timer") delay(5) else if (r != null) { read = true; diff = r }
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
        /** A SET naming list [list] (mode 27 sub 0: byte 5 the list). */
        fun isSetOf(s: ByteArray, list: Int) = isSub(s, 27, CfwModes.BIND_SET) && s[5].toInt() == list
        /** A CLEAR of entry [entry]. */
        fun isClearOf(s: ByteArray, entry: Int) = isSub(s, 27, CfwModes.BIND_CLEAR) && (s[2].toInt() and 0xFF) == entry
        fun isListOf(s: ByteArray, list: Int) = (isSub(s, 27, CfwModes.BIND_LIST) || isSub(s, 27, CfwModes.BIND_LIST_RING)) && s[2].toInt() == list
        /** A DISCARD in slot 0 (mode 26 sub 4: byte 2 the slot, byte 5 the list, bytes 6–7 the cursor). */
        fun isDiscard0(s: ByteArray) = isSub(s, 26, 4) && s[2].toInt() == 0
        fun cacheWrite(img: ByteArray) = (img[0].toInt() and 0x7F) in setOf(12, 19, 25)
        fun since(at: Int): List<List<ByteArray>> = right.drop(at).map { subs(it) }

        fun close() { scope.cancel(); tmp.toFile().deleteRecursively() }
    }

    /** One notch on the bound window list: adopted and committed (a COMMIT of its S on RIGHT), the row moved by its step
     *  (modulo the count on a ring list), no snap-back, belief equal to both lenses once the motion has ended. */
    private suspend fun notch(r: Rig, eventId: Int, what: String) {
        r.settle("$what: before")
        val list = r.list()
        val e = assertNotNull(list.entryFor(eventId))
        val row0 = r.row()
        val at = r.right.size
        val snaps = r.notes("snapback").size
        val s = r.fire(eventId)
        for (a in Arm.entries) r.until("$what: $a plays the notch in slot 0") { synchronized(r.sim) { r.sim.motionForTest(a).slots[MainStaging.SLOT] != null } }
        delay(((e.program!!.T + 1) * e.period * 1000L) / 1024 + r.q() + 300)
        r.settle("$what: after")
        val want = if (list.ring) (row0 + e.step).mod(list.count) else row0 + e.step
        assertEquals(want, r.row(), "$what: the row moved by its step")
        assertTrue(r.since(at).any { ss -> ss.any { r.isCommit(it, s) } }, "$what: a COMMIT of S $s")
        assertEquals(snaps, r.notes("snapback").size, "$what: no snap-back: ${r.notes("snapback").takeLast(2)}")
        r.assertOracle(what)
    }

    /** Pin 1 and CK A8: Settings' categories, bound as list 1 in slot 0 at stage 5 — Main's entries CLEARed, entries 3–5
     *  SET on both lenses, the `liststage` notes; a notch down and up fire on both lenses, adopted and COMMITted; END is the
     *  window's own paint with the held band landing after the motion (belief equals both lenses). */
    @Test
    fun settingsCategoriesBindAsList1AndANotchIsLocal_K12S3a(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up()
            val mainMs = r.onLoop { r.shell.mainBinding.staging }
            r.open("settings")
            r.windowBound("Settings")
            for (a in Arm.entries) {
                assertEquals(listOf(3, 4, 5), r.entriesOn(a, 1), "$a holds entries 3–5 for list 1")
                assertEquals(emptyList(), r.entriesOn(a, 0), "$a holds no entry of Main's list")
            }
            val l = r.list()
            assertEquals(1 to 0, l.list to l.slot, "list 1 in slot 0")
            assertTrue(r.notes("liststage").any { it.startsWith("staged — ") } && r.notes("liststage").any { it.startsWith("offered — the window list's") },
                "the liststage notes: ${r.notes("liststage")}")
            println("K12S3a settings: ${r.notes("liststage").filter { it.startsWith("staged") }}")
            notch(r, CfwModes.EV_UPROLL, "a notch down on Settings")
            notch(r, CfwModes.EV_UPROLL, "a second notch down")
            notch(r, CfwModes.EV_DOWNROLL, "a notch up")
            assertTrue(r.onLoop { r.shell.mainBinding.staging } === mainMs, "Main's staging untouched by the window list's")
        } finally { r.close() }
    }

    /** Pin 2: Settings' adjust is a cover — entering it CLEARs entries 3–5 in the act's batch and a notch changes the value,
     *  not the list; leaving it SETs them again with the slot's DISCARD naming the row. */
    @Test
    fun adjustUnbindsAndLeavingBindsAgain_K12S3a(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up()
            r.open("settings")
            r.windowBound("Settings")
            r.fire(CfwModes.EV_TAP)                                        // Global (row 0): its rows, a new staging
            r.until("Global's rows open") { r.onLoop { r.shell.slot0ForTest() == "window" && r.shell.windowBoundForTest().first?.count?.let { it > 5 } == true } }
            r.done("Global"); r.windowBound("Global")
            val row = r.row()
            val at = r.right.size
            r.fire(CfwModes.EV_TAP)                                        // Brightness: the adjust level
            r.until("adjusting: slot 0 is nobody's") { r.onLoop { r.shell.slot0ForTest() } == null }
            r.until("entries 3–5 cleared on RIGHT") { r.entriesOn(Arm.RIGHT, 1).isEmpty() }
            r.settle("adjusting")
            val clears = r.since(at).indexOfFirst { ss -> (3..5).all { e -> ss.any { r.isClearOf(it, e) } } }
            assertTrue(clears >= 0 && r.since(at)[clears].any { s -> (s[0].toInt() and 0x7F) !in setOf(24, 26, 27) },
                "the CLEARs of 3–5 ride the act's batch (a frame): ${r.since(at).map { ss -> ss.map { "${it[0]}/${it[1]}" } }}")
            val at2 = r.right.size
            r.fire(CfwModes.EV_UPROLL)                                     // the value moves, not the list
            delay(300); r.settle("a notch while adjusting")
            assertEquals(row, r.onLoop { r.shell.windowBoundForTest().second }, "the cursor did not move")
            assertTrue(r.since(at2).none { ss -> ss.any { r.isSub(it, 26, 3) } }, "no COMMIT while adjusting")
            r.fire(CfwModes.EV_TAP)                                        // leaves the adjust level
            r.windowBound("adjust left")
            val back = r.since(at2).firstOrNull { ss -> ss.any { r.isSetOf(it, 1) } }
            assertNotNull(back, "the SETs of list 1 again")
            assertTrue(back.any { r.isDiscard0(it) && it[5].toInt() == 1 && r.u16(it, 6) == row }, "with the slot's DISCARD naming row $row")
            notch(r, CfwModes.EV_UPROLL, "a notch after the adjust")
        } finally { r.close() }
    }

    /** Pins 3 and 13: Settings → Main → Settings writes nothing of the window list (its staging kept, its mark the same) and
     *  the first notch is local; each change of the list in front CLEARs the old list's entries and SETs the new one's with
     *  the slot's DISCARD naming the new list's row (it stops the other list's locals in slot 0). */
    @Test
    fun keptAcrossMainAndSlot0IsSharedByTheLists_K12S3a(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up()
            r.open("settings")
            r.windowBound("Settings")
            val ms1 = r.onLoop { r.shell.windowListBinding.staging }
            val mark = r.list().mark
            val wireMark = synchronized(r.sim) { r.sim.bindForTest(Arm.RIGHT).lists[1].mark }
            val staged = r.notes("liststage").count { it.startsWith("staged") }
            notch(r, CfwModes.EV_UPROLL, "a notch on Settings")
            val at = r.right.size
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.until("back on Main and its list bound on RIGHT") { r.onLoop { r.shell.currentWindowId() == null && r.shell.slot0ForTest() == "main" } && r.entriesOn(Arm.RIGHT, 0).size >= 2 }
            r.done("back on Main")
            val toMain = r.since(at).firstOrNull { ss -> ss.any { r.isSetOf(it, 0) } }
            assertNotNull(toMain, "Main's SETs on its return")
            assertTrue((3..5).all { e -> r.since(at).any { ss -> ss.any { r.isClearOf(it, e) } } }, "list 1's entries CLEARed")
            assertTrue(toMain.any { r.isDiscard0(it) && it[5].toInt() == 0 }, "Main's return names its row in slot 0's DISCARD")
            assertTrue(r.onLoop { r.shell.windowListBinding.staging } === ms1, "the window list's staging kept while Main is in front")
            val at2 = r.right.size
            r.open("settings")
            r.windowBound("Settings again")
            assertTrue(r.onLoop { r.shell.windowListBinding.staging } === ms1 && r.list().mark == mark, "the same staging and mark")
            assertEquals(staged, r.notes("liststage").count { it.startsWith("staged") }, "no staging of the window list")
            val lo = ms1!!.start; val hi = ms1.end
            val back = r.since(at2).firstOrNull { ss -> ss.any { r.isSetOf(it, 1) } }
            assertNotNull(back, "list 1's SETs on the return")
            // the riding LIST (decisions U.1: every answer naming a list rides behind its LIST, shift 0, the mark it holds)
            assertTrue(back.filter { r.isListOf(it, 1) }.all { r.u16(it, 7) == 0 && (it[11].toInt() and 0xFF) == wireMark },
                "no new LIST: the glasses hold the staging (only the riding one, shift 0, mark $wireMark): " +
                "${back.filter { r.isListOf(it, 1) }.map { "shift ${r.u16(it, 7)} mark ${it[11].toInt() and 0xFF}" }}")
            assertTrue(back.any { r.isDiscard0(it) && it[5].toInt() == 1 }, "the return's DISCARD names list 1's row")
            println("K12S3a kept across Main: list 1 at $lo..$hi")
            notch(r, CfwModes.EV_UPROLL, "the first notch back on Settings")
        } finally { r.close() }
    }

    /** Pins 5 and 6: Main wins the space. A session started on Settings stages its list with no staging of Main's (from the
     *  region's start, so it lies under the wheel's block); Main's ink-heavy staging laid out over it drops it (never the
     *  reverse: Main's staging stands, and the list is staged again only from Main's end); a list that does not fit there
     *  stays unbound with one note however many pumps follow, and is tried again when its space changes. */
    @Test
    fun mainWinsTheSpaceAndAListThatDoesNotFitStaysUnbound_K12S3a(): Unit = runBlocking {
        val r = Rig(nRows = 8, text = NoisyText(), startOn = "settings")
        try {
            r.up()
            r.windowBound("started on Settings")
            val ms1 = assertNotNull(r.onLoop { r.shell.windowListBinding.staging })
            assertEquals(null, r.onLoop { r.shell.mainBinding.staging }, "no staging of Main's at a start on Settings")
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.until("Main staged") { r.onLoop { r.shell.currentWindowId() == null && r.shell.mainBinding.staging != null && r.shell.mainBinding.counted } }
            r.done("Main")
            // Main grows by its rows' content, one ink-heavy row at a time, each followed by a `Depth` change that stages it
            // whole again (as `WheelResidentTest`'s Main-wins pin), until its staging reaches the list's records
            var depth = 8
            var k = 0
            while (r.onLoop { r.shell.mainBinding.staging?.end ?: 0 } <= ms1.start && k < r.rows.size) {
                r.rows[k++].heavy = true
                val n0 = r.notes("mainstage").count { it.startsWith("staged") || "cannot be staged" in it }
                depth = if (depth == 8) 4 else 8
                r.shell.updateSettings { it.copy(depth = depth) }
                r.until("Main staged whole again at Depth $depth") { r.notes("mainstage").count { it.startsWith("staged") || "cannot be staged" in it } > n0 &&
                    r.onLoop { r.shell.mainBinding.staging == null || r.shell.mainBinding.counted } }
                r.done("Main at Depth $depth")
                if (r.onLoop { r.shell.mainBinding.staging } == null) {
                    r.rows[--k].heavy = false                            // past what Main's region holds: one row lighter
                    r.until("Main staged again one row lighter") { r.onLoop { r.shell.mainBinding.staging != null && r.shell.mainBinding.counted } }
                    r.done("Main one row lighter")
                    break
                }
            }
            val main = assertNotNull(r.onLoop { r.shell.mainBinding.staging }, "Main staged: ${r.notes("mainstage").takeLast(3)}")
            println("K12S3a Main wins: Main ${main.start}..${main.end}, the list was ${ms1.start}..${ms1.end}; liststage ${r.notes("liststage").takeLast(3)}")
            assertTrue(main.end > ms1.start, "the fixture: Main's heavy staging (to ${main.end}) reaches the list's records (from ${ms1.start})")
            assertTrue(r.notes("liststage").any { it.startsWith("dropped — Main's staging at") && "(Main wins)" in it }, "the list dropped: ${r.notes("liststage")}")
            r.open("settings")
            delay(1_000); r.done("Settings over a heavy Main")
            assertTrue(r.onLoop { r.shell.mainBinding.staging } === main, "Main's staging stands (never the reverse)")
            val again = r.onLoop { r.shell.windowListBinding.staging }
            if (again != null) {
                assertTrue(again.start >= main.end, "staged again only from Main's end: ${again.start} < ${main.end}")
                println("K12S3a Main wins: the list staged again at ${again.start}..${again.end}")
            } else {
                val refusals = r.notes("liststage").filter { "cannot be staged" in it }
                assertEquals(1, refusals.size, "one note for a list that does not fit: ${r.notes("liststage")}")
                assertEquals(null, r.onLoop { r.shell.slot0ForTest() }, "unbound")
                println("K12S3a does not fit: ${refusals.single()}")
                for (w in r.rows) w.heavy = false
                r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
                r.until("Main again") { r.onLoop { r.shell.currentWindowId() } == null }
                depth = if (depth == 8) 4 else 8
                r.shell.updateSettings { it.copy(depth = depth) }      // Main staged whole again, smaller
                r.until("Main smaller") { r.onLoop { r.shell.mainBinding.staging?.let { it.end < main.end && r.shell.mainBinding.counted } == true } }
                r.done("Main smaller")
                r.open("settings")
                r.windowBound("tried again in the larger space")
            }
        } finally { r.close() }
    }

    /** Pin 7 (its first half): a row count change of the list in front makes a new staging with a new mark — the old one
     *  dropped ("rows changed"), the list unbound meanwhile, then a LIST of the new count and its SETs. */
    @Test
    fun aCountChangeMakesANewStagingWithANewMark_K12S3a(): Unit = runBlocking {
        val win = Win("lw", "Listy", n = 6, list = true)
        val r = Rig(extra = listOf(win))
        try {
            r.up()
            r.open("lw")
            r.windowBound("Listy")
            val m1 = r.list().mark
            val at = r.right.size
            win.n = 7
            r.onLoop { r.shell.services.requestRender(win) }
            r.until("a new staging of 7 rows") { r.onLoop { r.shell.windowBoundForTest().first?.let { it.count == 7 } == true } }
            r.windowBound("7 rows")
            assertTrue(r.list().mark != m1, "a new mark")
            assertTrue(r.notes("liststage").any { it.startsWith("dropped — the window list's rows changed (6 staged, 7 now)") }, "${r.notes("liststage")}")
            assertTrue(r.since(at).any { ss -> ss.any { r.isListOf(it, 1) && r.u16(it, 5) == 7 } }, "a LIST of 7 rows")
            notch(r, CfwModes.EV_UPROLL, "a notch on the new staging")
        } finally { r.close() }
    }

    /** Pin 11 (the half that is not stage 4's): a Select from the bound categories — the window list's entries CLEARed on
     *  the out half, no SET of list 1 while the transition runs, and the new level staged and bound after the run. */
    @Test
    fun aSelectFromABoundListStagesTheNewLevelAfterTheRun_K12S3a(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up()
            r.open("settings")
            r.windowBound("Settings")
            val staged = r.notes("liststage").count { it.startsWith("staged") }
            val at = r.right.size
            r.fire(CfwModes.EV_TAP)
            r.until("a transition in hand") { r.onLoop { r.shell.transitionPhaseForTest() } != null }
            val runFrom = r.right.size
            r.until("the run ended") { r.onLoop { r.shell.transitionPhaseForTest() } == null }
            val runTo = r.right.size
            assertTrue((3..5).all { e -> r.since(at).take(runTo - at).any { ss -> ss.any { r.isClearOf(it, e) } } }, "the CLEARs of 3–5 by the run's end")
            fun words(ss: List<ByteArray>) = ss.filter { (it[0].toInt() and 0x7F) in setOf(26, 27) }.joinToString(" ") { "${it[0].toInt() and 0x7F}/${it[1]}:" + it.drop(2).take(6).joinToString(",") { b -> "${b.toInt() and 0xFF}" } }
            assertTrue(r.right.subList(runFrom, runTo).none { img -> r.subs(img).any { r.isSetOf(it, 1) } }, "no SET of list 1 in the run: " +
                r.since(at).withIndex().joinToString(" | ") { (i, ss) -> "${if (i + at in runFrom until runTo) "RUN " else ""}${words(ss)}" } +
                " — local ${r.notes("local").takeLast(6)}; liststage ${r.notes("liststage").takeLast(4)}")
            r.done("Global"); r.windowBound("Global, after the run")
            assertTrue(r.notes("liststage").count { it.startsWith("staged") } > staged, "the new level staged")
            notch(r, CfwModes.EV_UPROLL, "a notch on Global")
        } finally { r.close() }
    }

    /** Pin 12: a wrap notch on a ring list 1 (Global's rows at 352): RIGHT's report carries field 18, it is adopted modulo
     *  the count, and the list was bound with LIST-RING naming list 1. */
    @Test
    fun aWrapNotchOnARingList1_K12S3a(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up()
            r.open("settings")
            r.windowBound("Settings")
            val at = r.right.size
            r.fire(CfwModes.EV_TAP)
            r.until("Global's rows bound") { r.onLoop { r.shell.slot0ForTest() == "window" && r.shell.windowBoundForTest().first?.count?.let { it > 5 } == true } }
            r.done("Global"); r.windowBound("Global")
            assertTrue(r.list().ring, "Global's rows at 352 are a ring list")
            assertTrue(r.since(at).any { ss -> ss.any { r.isSub(it, 27, CfwModes.BIND_LIST_RING) && it[2].toInt() == 1 } }, "a LIST-RING naming list 1")
            assertEquals(0, r.row(), "at the first row")
            val s = r.onLoop { r.sim.bindForTest(Arm.RIGHT).seq } + 1
            notch(r, CfwModes.EV_DOWNROLL, "a notch up across the wrap")
            assertEquals(r.list().count - 1, r.row(), "the last row, adopted modulo the count")
            assertTrue(r.notes("applied").any { it.startsWith("S $s ") && " list 1 " in it && it.contains(" wrapped") },
                "RIGHT's report carries field 18 = 1 on list 1: ${r.notes("applied").takeLast(2)}")
        } finally { r.close() }
    }

    /** Pin 14 (BF per list): steps measured on the window list raise its period and never Main's. */
    @Test
    fun theWindowListsStepsNeverMoveMainsPeriod_K12S3a(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up()
            val mainP = r.onLoop { r.shell.periodInForceForTest() }
            r.open("settings")
            r.windowBound("Settings")
            for (a in Arm.entries) r.sim.motionForTest(a).stepUsForTest = 30_000L
            var k = 0
            while (r.onLoop { r.shell.windowListBinding.staging?.periodInForce } == r.onLoop { r.shell.windowListBinding.staging?.period } && k < 4) {
                r.fire(if (r.row() < r.list().count - 2) CfwModes.EV_UPROLL else CfwModes.EV_DOWNROLL); k++
                delay(r.motionMs() + r.q() + 300); r.settle("slow notch $k")
            }
            val (mw, ww) = r.onLoop { r.shell.stepWindowsForTest() }
            assertTrue(ww.isNotEmpty() && ww.all { it == 30_000L }, "the window list's samples: $ww")
            assertEquals(emptyList(), mw.filter { it == 30_000L }, "none of them is Main's")
            assertTrue(r.notes("liststage").any { it.startsWith("period ") }, "the window list's period raised: ${r.notes("liststage").filter { it.startsWith("period") }}")
            assertEquals(mainP, r.onLoop { r.shell.periodInForceForTest() }, "Main's period not moved")
        } finally { r.close() }
    }

    /** Pin 4 (with decisions CV's effect change): an effect changed in Settings re-makes Main's pans inside Main's span (its
     *  covered re-make) and the window list's inside its own — no new staging of either — and a notch on the window list
     *  after both plays its whole motion (no instance closed short). */
    @Test
    fun anEffectChangeKeepsTheStagingsAndMainsReMakeMeetsNoList1Instance_K12S3a(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up()
            r.open("settings")
            r.windowBound("Settings")
            val ms1 = assertNotNull(r.onLoop { r.shell.windowListBinding.staging })
            val main = assertNotNull(r.onLoop { r.shell.mainBinding.staging })
            r.shell.updateSettings { it.copy(scrollEffect = "slide") }
            r.until("both re-makes counted") { r.notes("liststage").any { it.startsWith("pans switched") } &&
                r.onLoop { r.shell.mainBinding.reprogram?.record?.state } == wm.damage.core.shell.StagedRecord.State.PROVEN }
            r.done("after the change")
            val mr = assertNotNull(r.onLoop { r.shell.mainBinding.reprogram?.record })
            assertTrue(mr.off >= main.start && mr.off + mr.encoded.size <= main.end, "Main's re-make inside Main's span")
            assertTrue(mr.off + mr.encoded.size <= ms1.start || mr.off >= ms1.end, "and outside the window list's records")
            assertTrue(r.onLoop { r.shell.windowListBinding.staging } === ms1 && r.onLoop { r.shell.mainBinding.staging } === main, "no new staging of either")
            assertEquals(wm.damage.core.motion.ScrollEffect.SLIDE, ms1.motion?.effect, "the window list plays Slide")
            val short0 = r.onLoop { r.shell.localCountsForTest()["localClosedShort"] ?: 0 }
            notch(r, CfwModes.EV_UPROLL, "a notch after the re-makes")
            assertEquals(short0, r.onLoop { r.shell.localCountsForTest()["localClosedShort"] ?: 0 }, "no instance closed short")
        } finally { r.close() }
    }

    /** Decisions CV: the wheel's block placed again over a window list already staged in its bytes — the list yields (its
     *  staging drops, its entries CLEARed before the block's write) and is staged again below the block. */
    @Test
    fun theWheelsBlockPlacedOverTheListMakesItYield_K12S3a(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up()
            r.open("settings")
            r.windowBound("Settings")
            r.fire(CfwModes.EV_TAP)
            r.until("Global's rows bound") { r.onLoop { r.shell.slot0ForTest() == "window" && r.shell.windowBoundForTest().first?.count?.let { it > 5 } == true } }
            r.done("Global"); r.windowBound("Global")
            val ms1 = assertNotNull(r.onLoop { r.shell.windowListBinding.staging })
            val base0 = assertNotNull(r.onLoop { r.shell.wheelBlockForTest() }).base
            assertEquals(base0, ms1.end, "the list lies flush below the block")
            r.onLoop { r.shell.register(Win("w9", "W9")) }                 // one more cell: the block placed again, larger
            r.until("the block placed again") { r.onLoop { r.shell.wheelBlockForTest()?.base?.let { it < base0 } == true } }
            r.done("the block again"); r.windowBound("Global again")
            val b1 = assertNotNull(r.onLoop { r.shell.wheelBlockForTest() })
            val ms2 = assertNotNull(r.onLoop { r.shell.windowListBinding.staging })
            assertTrue(ms2 !== ms1 && ms2.end <= b1.base, "staged again below the block: ${ms2.start}..${ms2.end}, the block at ${b1.base}")
            assertTrue(r.notes("liststage").any { it.startsWith("dropped — the wheel's block goes at") }, "${r.notes("liststage")}")
        } finally { r.close() }
    }

    /** Below stage 5 nothing of the window list is sent: Settings at stage 4 on 256 KiB stages nothing, no SET names list 1,
     *  and no `liststage` note is written. */
    @Test
    fun belowStage5NothingOfTheWindowListIsSent_K12S3a(): Unit = runBlocking {
        val r = Rig(stage = 4, features = 0x5ff)
        try {
            r.up()
            val at = r.right.size
            r.open("settings")
            delay(1_000); r.done("Settings at stage 4")
            assertEquals(null, r.onLoop { r.shell.slot0ForTest() })
            assertEquals(null, r.onLoop { r.shell.windowListBinding.staging })
            assertTrue(r.since(at).none { ss -> ss.any { r.isSetOf(it, 1) || r.isListOf(it, 1) } }, "nothing names list 1")
            assertEquals(emptyList(), r.notes("liststage"))
        } finally { r.close() }
    }

    /**
     * Decisions CT, CV (a reading, not a pin): each Settings list at stage 5 on 256 KiB against the room list 1 has — Global's
     * rows, Animations as it is now (five rows) and each of its tier sub menus, and his eight app categories with as many
     * rows as his have (test rows: On/Off rows of real-looking names beside the shell's style rows; FakeText faces) — at the
     * four heights, with Main's eight rows staged. Each list either staged and bound, or refused with one note; the table
     * is printed (`K12S3a room`).
     */
    @Test
    fun eachSettingsListAgainstTheRoomForList1_K12S3a(): Unit = runBlocking {
        val r = Rig(names = HIS_APPS.map { it.first })
        try {
            r.up()
            val style = r.onLoop { r.shell.settingsApps().first { it.first == "Reader" }.second.size }
            for ((w, app) in r.rows.zip(HIS_APPS)) w.appRows = maxOf(0, app.second - style)
            val table = ArrayList<String>()
            suspend fun read(h: Int, what: String) {
                r.done("$h $what")
                val line = r.onLoop {
                    r.shell.windowListBinding.staging?.let { ms -> "${ms.rows} rows: ${ms.end - ms.start} B at ${ms.start}..${ms.end} in ${ms.regionEnd - ms.regionStart} B " +
                        "(${ms.regionStart}..${ms.regionEnd}), ${ms.start - ms.regionStart} B to spare${if (ms.ring) ", ring" else ""}${if (ms.progSlots == null) ", ONE pan slot" else ""}" }
                } ?: "NOT STAGED: ${r.notes("liststage").lastOrNull { "cannot be staged" in it }}"
                table += "h$h $what: $line"
            }
            suspend fun to(row: Int) {
                while (true) {
                    val at = r.onLoop { r.shell.focusedRowForTest() }
                    if (at == row) break
                    val step = if (at < row) 1 else -1
                    r.fire(if (step > 0) CfwModes.EV_UPROLL else CfwModes.EV_DOWNROLL)
                    r.until("row ${at + step}") { r.onLoop { r.shell.focusedRowForTest() } == at + step }
                    delay(r.motionMs() + r.q() + 200); r.settle("at row ${at + step}")
                }
            }
            suspend fun into(what: String) { r.fire(CfwModes.EV_TAP); r.until("into $what") { r.onLoop { r.shell.transitionPhaseForTest() } == null && r.shell.isQuiescent() } }
            suspend fun back() { r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK); delay(200); r.until("back") { r.onLoop { r.shell.transitionPhaseForTest() } == null && r.shell.isQuiescent() } }
            r.open("settings")
            for (h in listOf(288, 352, 416, 480)) {
                r.shell.updateSettings { it.copy(heightMode = h) }
                read(h, "categories")
                for (c in 0 until 2 + HIS_APPS.size) {
                    to(c); into("category $c")
                    val name = if (c == 0) "Global" else if (c == 1) "Animations" else HIS_APPS[c - 2].first
                    read(h, name)
                    if (c == 1) {
                        val tiers = r.onLoop { r.shell.windowListBinding.staging?.rows } ?: 5
                        for (t in 0 until tiers) { to(t); into("tier $t"); read(h, "Animations tier $t"); back() }
                    }
                    back()
                }
            }
            println("K12S3a room (test rows, FakeText, Main's eight rows staged; measured on the model):\n" + table.joinToString("\n"))
            assertTrue(table.size >= 4 * 11, "every list read: ${table.size}")
        } finally { r.close() }
    }

    // ------------------------------------------------------------------ K1-2 stage 3b

    /** Main staged, counted and bound on RIGHT, with nothing else in hand. */
    private suspend fun mainBound(r: Rig, what: String) {
        r.until("$what: Main bound on RIGHT") { r.onLoop { r.shell.currentWindowId() == null && r.shell.slot0ForTest() == "main" &&
            r.shell.mainBinding.counted } && r.entriesOn(Arm.RIGHT, 0).size >= 2 }
        r.done(what)
    }

    /** The reorder case (the K1-2 spec §1 and §6, "Order: row re-stages"; decisions BC O5): two rows on screen trade places in
     *  a bound window list of the same count — row 1 below the band and row 11, shown above it by the wrap. The compose
     *  compare finds both; each is re-staged by withdrawal (its entries CLEARed, its strip segments written, the SETs again),
     *  one per 5 s, with no new staging and the mark kept, and a notch after is local with belief equal to both lenses.
     *  Without the compare only the round-robin finds them: row 11 a minute after the staging. */
    @Test
    fun aReorderOnScreenIsReStagedRowByRowWithTheMarkKept_K12S3b(): Unit = runBlocking {
        val win = Win("lw", "Listy", n = 12, list = true)
        val r = Rig(extra = listOf(win))
        try {
            r.up()
            r.open("lw")
            r.windowBound("Listy")
            val ms = assertNotNull(r.onLoop { r.shell.windowListBinding.staging })
            val mark = r.list().mark
            val staged = r.notes("liststage").count { it.startsWith("staged") }
            assertEquals(0, r.row())
            val shown = r.onLoop { r.shell.layout }.let { l -> (1..l.rowsBelow).toList() + (1..l.rowsAbove).map { 12 - it } }
            assertTrue(1 in shown && 11 in shown, "rows 1 and 11 both on screen at row 0: $shown")
            val (bytes0, marks0) = r.onLoop { r.shell.windowListBinding.restagedBytes to r.shell.windowListBinding.composeMarks }
            val at = r.right.size
            val t0 = System.currentTimeMillis()
            win.order = IntArray(12) { it }.also { it[1] = 11; it[11] = 1 }
            r.onLoop { r.shell.services.requestRender(win) }
            r.until("rows 1 and 11 re-staged", 12_000) { r.notes("liststage").count { it.startsWith("row 1 re-staged") || it.startsWith("row 11 re-staged") } == 2 }
            val took = System.currentTimeMillis() - t0
            r.windowBound("offered again")
            val (bytes1, marks1) = r.onLoop { r.shell.windowListBinding.restagedBytes to r.shell.windowListBinding.composeMarks }
            println("K12S3b reorder: rows 1 and 11 re-staged ${bytes1 - bytes0} B in ${took} ms on the rig (one per 5 s); compose marks ${marks1 - marks0}; " +
                "${r.notes("liststage").filter { it.startsWith("withdrawn") || "re-staged" in it }.takeLast(4)}")
            assertTrue(marks1 - marks0 >= 2, "the compose compare marked the two rows")
            assertTrue(r.notes("liststage").count { it.startsWith("withdrawn — row 1'") || it.startsWith("withdrawn — row 11'") } == 2, "each withdrawn for its strip write")
            assertTrue(r.onLoop { r.shell.windowListBinding.staging } === ms && r.list().mark == mark, "the same staging and mark")
            assertEquals(staged, r.notes("liststage").count { it.startsWith("staged") }, "no new staging")
            assertTrue(r.notes("liststage").none { it.startsWith("dropped") }, "${r.notes("liststage")}")
            // the path on RIGHT: the CLEARs of 3–5, the strip's write, then the SETs again
            val imgs = r.right.drop(at)
            val clearAt = imgs.indexOfFirst { img -> r.subs(img).any { r.isClearOf(it, 3) } }
            val writeAt = imgs.indexOfFirst { r.cacheWrite(it) }
            val setAt = imgs.indices.firstOrNull { it > writeAt && r.subs(imgs[it]).any { s -> r.isSetOf(s, 1) } } ?: -1
            assertTrue(clearAt in 0 until writeAt && setAt > writeAt, "CLEAR at $clearAt, the write at $writeAt, the SETs at $setAt of RIGHT's images")
            r.onLoop { r.shell.services.requestRender(win) }
            r.done("composed again")
            assertEquals(marks1, r.onLoop { r.shell.windowListBinding.composeMarks }, "once re-staged, a compose marks nothing")
            r.assertOracle("re-staged")
            notch(r, CfwModes.EV_UPROLL, "a notch after the re-stages")
            notch(r, CfwModes.EV_DOWNROLL, "and back")
        } finally { r.close() }
    }

    /** Pin 8 (the K1-2 spec §8): an adjusted value is re-staged within 5 s and the next notch's END shows it. Global's
     *  `Presence` (row 3) adjusted from `dim at rest` to `away at rest` under the band: leaving the adjust level, the row is
     *  re-staged (the band's picture changed with the row unmoved — the compose compare's band half), and a notch down then
     *  ends with RIGHT's panel, read before the first image after the motion's end, showing row 3 above the band as the
     *  window paints it. Without: the old value at that notch's END and every one after, until the round-robin reaches row 3. */
    @Test
    fun anAdjustedValueIsReStagedWithin5sAndTheNextNotchsEndShowsIt_K12S3b(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up()
            r.open("settings")
            r.windowBound("Settings")
            r.fire(CfwModes.EV_TAP)                                        // Global
            r.until("Global's rows open") { r.onLoop { r.shell.slot0ForTest() == "window" && r.shell.windowBoundForTest().first?.count?.let { it > 10 } == true } }
            r.done("Global"); r.windowBound("Global")
            for (k in 1..3) notch(r, CfwModes.EV_UPROLL, "to row $k")
            assertEquals(3, r.row(), "at Presence")
            r.done("at Presence")
            val ms = assertNotNull(r.onLoop { r.shell.windowListBinding.staging })
            val restaged0 = r.notes("liststage").count { it.startsWith("row 3 re-staged") }
            assertEquals(1, r.onLoop { r.shell.settings.presence }, "the fixture: dim at rest")
            r.fire(CfwModes.EV_TAP)                                        // the adjust level
            r.until("adjusting") { r.onLoop { r.shell.slot0ForTest() } == null }
            r.settle("adjusting")
            r.fire(CfwModes.EV_DOWNROLL)                                   // dim at rest → away at rest
            r.until("Presence changed") { r.onLoop { r.shell.settings.presence } == 0 }
            r.settle("the value moved")
            r.fire(CfwModes.EV_TAP)                                        // kept: the adjust level left
            val t0 = System.currentTimeMillis()
            r.until("row 3 re-staged", 8_000) { r.notes("liststage").count { it.startsWith("row 3 re-staged") } > restaged0 }
            val took = System.currentTimeMillis() - t0
            r.windowBound("offered again")
            println("K12S3b adjusted value: row 3 re-staged $took ms after the adjust level was left (on the rig); " +
                "${r.notes("liststage").lastOrNull { it.startsWith("row 3 re-staged") }}")
            assertTrue(took < MainBinding.RESTAGE_PACE_MS, "within 5 s: $took ms")
            assertTrue(r.onLoop { r.shell.windowListBinding.staging } === ms, "the same staging (a row re-staged keeps it)")
            // the next notch: RIGHT's panel at its END — read before the first image RIGHT takes after the motion's end
            val l = r.onLoop { r.shell.layout }
            val go = java.util.concurrent.atomic.AtomicBoolean(false)
            val end = java.util.concurrent.atomic.AtomicReference<Gray8?>(null)
            r.onImage = { _ ->
                if (go.get() && end.get() == null && synchronized(r.sim) { r.sim.motionForTest(Arm.RIGHT).slots[MainStaging.SLOT]?.drawing != true })
                    end.set(r.panel(false))
            }
            r.settle("before the notch")
            val e = assertNotNull(r.list().entryFor(CfwModes.EV_UPROLL))
            r.fire(CfwModes.EV_UPROLL)
            r.until("RIGHT plays the notch") { synchronized(r.sim) { r.sim.motionForTest(Arm.RIGHT).slots[MainStaging.SLOT]?.drawing == true } }
            go.set(true)
            delay(((e.program!!.T + 1) * e.period * 1000L) / 1024 + r.q() + 300)
            r.settle("after the notch")
            r.onImage = null
            assertEquals(4, r.row(), "the notch moved the row")
            r.assertOracle("after the notch")
            val endPanel = assertNotNull(end.get(), "an image after the motion's end (the held band)")
            val fin = r.panel(false)
            val y0 = l.lens.y - l.rowH
            val x0 = l.content.x + 24
            val x1 = l.content.x + l.content.w - wm.damage.core.geom.Layout.RAIL_W - 24
            var diff = 0
            for (y in y0 + 1 until l.lens.y - 1) for (x in x0 until x1) if (endPanel[x, y] != fin[x, y]) diff++
            assertEquals(0, diff, "the notch's END shows row 3 (above the band) as the window paints it — $diff px differ")
        } finally { r.close() }
    }

    /** Pin 6's content half (the K1-2 spec §6; decisions AF.6, AL.1): a window list the window form refuses (70 rows at 352:
     *  its strip past 2,048 px) stays unbound with one note through composes and a phone-drawn notch that change nothing it
     *  draws, and is tried again — painted and refused once more, with no second note — when the rows on screen draw
     *  otherwise. Without: never tried again until its rows, layout, planes or space change. */
    @Test
    fun aListThatDoesNotFitIsTriedAgainOnAContentChange_K12S3b(): Unit = runBlocking {
        val win = Win("nw", "Long", n = 70, list = true)
        val r = Rig(extra = listOf(win))
        try {
            r.up()
            r.open("nw")
            r.until("refused") { r.onLoop { r.shell.windowListRefusalsForTest() } >= 1 }
            r.done("refused")
            val refusal = r.notes("liststage").filter { "cannot be staged" in it }
            println("K12S3b does not fit: $refusal")
            assertEquals(1, refusal.size, "one note: ${r.notes("liststage")}")
            for (k in 1..3) { r.onLoop { r.shell.services.requestRender(win) }; delay(200); r.done("compose $k") }
            r.fire(CfwModes.EV_UPROLL)                                     // unbound: the phone draws it, as today
            r.until("the row moved") { r.onLoop { r.shell.focusedRowForTest() } == 1 }
            delay(300); r.done("a notch drawn by the phone")
            assertEquals(1, r.onLoop { r.shell.windowListRefusalsForTest() }, "not tried again while nothing it draws changed")
            assertEquals(null, r.onLoop { r.shell.slot0ForTest() }, "unbound")
            win.order = IntArray(70) { it + 3 }                            // every row draws otherwise
            r.onLoop { r.shell.services.requestRender(win) }
            r.until("tried again on the content change", 20_000) { r.onLoop { r.shell.windowListRefusalsForTest() } == 2 }
            r.done("refused again")
            assertEquals(1, r.notes("liststage").count { "cannot be staged" in it }, "still one note: ${r.notes("liststage")}")
            assertEquals(null, r.onLoop { r.shell.slot0ForTest() }, "unbound")
            for (k in 1..2) { r.onLoop { r.shell.services.requestRender(win) }; delay(200); r.done("compose again $k") }
            assertEquals(2, r.onLoop { r.shell.windowListRefusalsForTest() }, "and not again while nothing changes")
        } finally { r.close() }
    }

    /** The compose compare against rows that did not change (its cost is the phone's CPU: a mark is a row painted again in
     *  idle): Settings' categories and Global's rows at the four heights, FakeText and NoisyText faces — after each staging,
     *  notches and their composes mark no row. */
    @Test
    fun aComposeOfRowsUnchangedMarksNone_K12S3b(): Unit = runBlocking {
        for ((name, text) in listOf<Pair<String, wm.damage.core.text.TextRasterizer>>("FakeText" to FakeText(), "NoisyText" to NoisyText())) {
            val r = Rig(text = text)
            try {
                r.up()
                r.open("settings")
                suspend fun read(what: String, down: Int) {
                    for (h in listOf(288, 352, 416, 480)) {
                        r.shell.updateSettings { it.copy(heightMode = h) }
                        r.done("$name $h $what"); r.windowBound("$name $h $what")
                        val m0 = r.onLoop { r.shell.windowListBinding.composeMarks }
                        notch(r, CfwModes.EV_UPROLL, "$name $h $what: a notch down")
                        notch(r, if (down > 1) CfwModes.EV_UPROLL else CfwModes.EV_DOWNROLL, "$name $h $what: another")
                        assertEquals(m0, r.onLoop { r.shell.windowListBinding.composeMarks }, "$name $h $what: the composes marked no row")
                    }
                }
                read("the categories", 1)
                r.fire(CfwModes.EV_TAP)
                r.until("$name: Global's rows") { r.onLoop { r.shell.slot0ForTest() == "window" && r.shell.windowBoundForTest().first?.count?.let { it > 10 } == true } }
                r.done("$name Global")
                read("Global", 2)
            } finally { r.close() }
        }
    }

    /** Main's staging kept across a window of another height (decisions CX): Main → a window at 480 (Global's height is 352)
     *  → Main writes no staging of Main's and binds the same staging and mark again (the riding LIST, shift 0). Without:
     *  Main's staging dropped while the window is in front ("Main's layout changed") and staged whole on the way back. */
    @Test
    fun mainsStagingIsKeptAcrossAWindowOfAnotherHeight_K12S3b(): Unit = runBlocking {
        val tall = Win("tw", "Tall").also { it.height = 480 }
        val r = Rig(extra = listOf(tall))
        try {
            r.up()
            mainBound(r, "the start")
            val ms = assertNotNull(r.onLoop { r.shell.mainBinding.staging })
            val mark = assertNotNull(r.onLoop { r.shell.mainBoundForTest().first }).mark
            val wireMark = synchronized(r.sim) { r.sim.bindForTest(Arm.RIGHT).lists[0].mark }
            val (bytes0, n0) = r.onLoop { r.shell.mainBinding.stagedBytes to r.shell.mainBinding.stagings }
            val mainLayout = r.onLoop { r.shell.layout }
            r.open("tw")
            assertTrue(r.onLoop { r.shell.layout } != mainLayout, "the tall window has a layout of its own")
            delay(1_000); r.done("the tall window in front")
            val kept = r.onLoop { r.shell.mainBinding.staging } === ms
            val at = r.right.size
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            mainBound(r, "back on Main")
            val (bytes1, n1) = r.onLoop { r.shell.mainBinding.stagedBytes to r.shell.mainBinding.stagings }
            val writes = r.right.drop(at).filter { r.cacheWrite(it) }
            println("K12S3b return to Main from a window of another height: Main staged ${n1 - n0} time(s), ${bytes1 - bytes0} B; " +
                "RIGHT's cache writes after the return ${writes.size} message(s), ${writes.sumOf { it.size }} B (measured on the model)")
            assertTrue(kept, "Main's staging kept while the tall window is in front: ${r.notes("mainstage").takeLast(3)}")
            assertEquals(n0, n1, "no staging of Main's on the way back")
            assertEquals(bytes0, bytes1)
            assertTrue(r.onLoop { r.shell.mainBinding.staging } === ms && r.onLoop { r.shell.mainBoundForTest().first }?.mark == mark, "the same staging and mark")
            assertTrue(r.notes("mainstage").none { it.startsWith("dropped") }, "${r.notes("mainstage")}")
            val back = r.since(at).firstOrNull { ss -> ss.any { r.isSetOf(it, 0) } }
            assertNotNull(back, "Main's SETs on the return")
            assertTrue(back.filter { r.isListOf(it, 0) }.all { r.u16(it, 7) == 0 && (it[11].toInt() and 0xFF) == wireMark },
                "no new LIST: the glasses hold the staging (only the riding one, shift 0, mark $wireMark)")
        } finally { r.close() }
    }

    /** The other side (decisions CX): a Global height change while a window of its own height is in front changes Main's
     *  own layout and not the shell's — Main's staging is dropped then, and staged again for the new height on the way
     *  back. */
    @Test
    fun aGlobalHeightChangeUnderAWindowStillReStagesMain_K12S3b(): Unit = runBlocking {
        val tall = Win("tw", "Tall").also { it.height = 480 }
        val r = Rig(extra = listOf(tall))
        try {
            r.up()
            mainBound(r, "the start")
            val ms = assertNotNull(r.onLoop { r.shell.mainBinding.staging })
            val n0 = r.onLoop { r.shell.mainBinding.stagings }
            r.open("tw")
            val winLayout = r.onLoop { r.shell.layout }
            r.shell.updateSettings { it.copy(heightMode = 416) }
            r.until("Main's staging dropped") { r.notes("mainstage").any { it.startsWith("dropped — Main's layout changed") } }
            r.done("416 under the tall window")
            assertEquals(winLayout, r.onLoop { r.shell.layout }, "the shell's layout is the window's own still")
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            mainBound(r, "back on Main at 416")
            val ms2 = assertNotNull(r.onLoop { r.shell.mainBinding.staging })
            assertTrue(ms2 !== ms && ms2.layout == r.onLoop { r.shell.layout }, "a new staging for Main's new layout")
            assertEquals(n0 + 1, r.onLoop { r.shell.mainBinding.stagings }, "one staging on the way back")
        } finally { r.close() }
    }

    // ------------------------------------------------------------------ K1-2 stage 3b: the first review of 3a (decisions CY)

    /** Finding A: a window's title is no part of its list's key — a notice in the title, and its end, drop nothing: the list
     *  stays bound with the same staging and mark. Without: unbound, dropped and staged again at each title change. */
    @Test
    fun aTitleNoticeKeepsTheListBound_K12S3b(): Unit = runBlocking {
        val win = Win("lw", "Listy", n = 12, list = true)
        val r = Rig(extra = listOf(win))
        try {
            r.up()
            r.open("lw")
            r.windowBound("Listy")
            val ms = assertNotNull(r.onLoop { r.shell.windowListBinding.staging })
            val mark = r.list().mark
            val n0 = r.onLoop { r.shell.windowListBinding.stagings }
            for (t in listOf("moved 3 files", "")) {
                win.notice = t
                r.onLoop { r.shell.services.requestRender(win) }
                delay(500); r.done("the title '$t'")
                assertEquals("window", r.onLoop { r.shell.slot0ForTest() }, "bound under the title '$t'")
            }
            r.windowBound("still bound")
            assertTrue(r.onLoop { r.shell.windowListBinding.staging } === ms && r.list().mark == mark, "the same staging and mark")
            assertEquals(n0, r.onLoop { r.shell.windowListBinding.stagings }, "no staging")
            assertTrue(r.notes("liststage").none { it.startsWith("dropped") }, "${r.notes("liststage")}")
            notch(r, CfwModes.EV_UPROLL, "a notch after the notice")
        } finally { r.close() }
    }

    /** Finding A's other side: a different list under the same key — every row on screen drawing otherwise (Files' sibling
     *  folder of one size) — is a new staging with a new mark, not a re-stage of each row 5 s apart. */
    @Test
    fun everyRowOnScreenChangedIsANewStaging_K12S3b(): Unit = runBlocking {
        val win = Win("lw", "Listy", n = 12, list = true)
        val r = Rig(extra = listOf(win))
        try {
            r.up()
            r.open("lw")
            r.windowBound("Listy")
            val ms = assertNotNull(r.onLoop { r.shell.windowListBinding.staging })
            val mark = r.list().mark
            win.order = IntArray(12) { it + 3 }
            r.onLoop { r.shell.services.requestRender(win) }
            r.until("a new staging", 10_000) { r.onLoop { r.shell.windowListBinding.staging.let { it != null && it !== ms } } }
            r.windowBound("the other list")
            assertTrue(r.list().mark != mark, "a new mark")
            assertTrue(r.notes("liststage").any { it.startsWith("dropped — every row on screen draws otherwise") }, "${r.notes("liststage")}")
            assertTrue(r.notes("liststage").none { "re-staged" in it }, "no row re-staged: ${r.notes("liststage")}")
            notch(r, CfwModes.EV_UPROLL, "a notch on the new staging")
        } finally { r.close() }
    }

    /** Finding B: a notice over a bound window list inside the wait of an adopted notch cuts nothing — the held cells wait
     *  for the motion's end and the shell sends no CLOSE of its own, as over Main (AW.3). `Scroll length` 600 ms, so the
     *  notice comes up inside the motion. Without: a CLOSE ahead of the notice's frame, the motion cut short. */
    @Test
    fun aNoticeOverABoundWindowListInsideTheWaitCutsNothing_K12S3b(): Unit = runBlocking {
        val r = Rig(tweak = { it.copy(scrollLength = 600, frames = 0) })
        try {
            r.up()
            r.open("settings")
            r.windowBound("Settings")
            r.fire(CfwModes.EV_UPROLL)
            r.until("adopted") { r.row() == 1 }
            assertTrue(r.onLoop { r.shell.comp.deferring }, "the held cells wait for the motion's end")
            r.shell.postNotice(wm.damage.core.shell.Notifications.Notice("TEST · a", "t", "a notice over the window list", "12:00"))
            r.until("the notice up") { r.onLoop { r.shell.notificationsUpForTest() } }
            assertTrue(r.onLoop { r.shell.comp.deferring }, "the held cells still wait under a notice")
            r.settle("the motion's end")
            assertTrue(r.notes("local").none { "adopted motion closed ahead of its frame" in it }, "the shell cut nothing: ${r.notes("local").takeLast(3)}")
            r.assertOracle("after the notice")
        } finally { r.close() }
    }

    /** Finding C: a slot-0 body whose report was not heard, once a window's list has been staged, is credited to no list —
     *  never to Main's measured window (pin 14's class through the lost-report door). Without: its step joins Main's window
     *  and raises Main's period. */
    @Test
    fun aSlot0BodyWithNoReportHeardMovesNoPeriod_K12S3b(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up()
            r.open("settings")
            r.windowBound("Settings")
            val (mw0, p0, t) = r.onLoop { Triple(r.shell.stepWindowsForTest().first, r.shell.periodInForceForTest(), r.shell.mainBinding.staging!!.t) }
            r.onLoop { r.shell.localBodyForTest(wm.damage.core.transport.TransportEvent.LocalBody(MainStaging.SLOT, 0x7ABC,
                wm.damage.core.wire.DamageMsg.OUTCOME_DREW, null, drawn = t.toLong(), folded = 0L, endCrc = 1L, stepUs = 60_000L)) }
            r.done("the body read")
            assertEquals(mw0, r.onLoop { r.shell.stepWindowsForTest().first }, "Main's measured window unchanged")
            assertEquals(p0, r.onLoop { r.shell.periodInForceForTest() }, "Main's period unchanged")
        } finally { r.close() }
    }
}
