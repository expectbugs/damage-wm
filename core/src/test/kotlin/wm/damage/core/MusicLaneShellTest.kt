package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Pack
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.WindowLanes
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.windows.music.Mode
import wm.damage.core.windows.music.MusicWindow
import wm.damage.core.windows.music.PlayState
import wm.damage.core.windows.music.SimMusicPlayer
import wm.damage.core.windows.music.TrackMeta
import wm.damage.core.windows.music.TrackRef
import wm.damage.core.wire.EvenHubMsg

/**
 * Music's motion update, stage L, through the real shell, Music's presenter and the firmware model at ladder stage 5 on a
 * 256 KiB session (and at stage 4, where nothing of it runs): the bar on Now Playing is a held lane — its area written
 * and counted, a chain open in slots 0 and 1, the glasses growing the bar while the phone writes nothing into it (the
 * lens's shadow under the bar stays the chain's start while the window's own bar moves on), the elapsed time painted
 * each second — and a pause ends it, the panel then the truth everywhere. One clock: the player's, the window's and the
 * model's engine read the same one.
 */
/** A packed lens buffer's bytes inside [box] (whole bytes: two pixels each). */
private fun cut(packed: ByteArray, box: Rect): ByteArray {
    val stride = (Geometry.PANEL_W + 1) / 2
    val o = java.io.ByteArrayOutputStream()
    for (y in box.y until box.bottom) o.write(packed, y * stride + box.x / 2, (box.w + 1) / 2)
    return o.toByteArray()
}

/** Stage SF2 (`MM-S-V2-review.md` 1): RIGHT's [strip] at every panel change from [watch] to [stop] — a picture only when it
 *  differs from the last — each with whether an instance of the chain that was open at the watch's start ([watch]'s tags,
 *  taken on the loop together with the change that ends it) was still open on RIGHT at that present. */
private class StripRec(val sim: GlassFirmwareSim, val strip: Rect) : GlassFirmwareSim.SimDiag {
    class Pic(val atMs: Long, val bytes: ByteArray, val oldOpen: Boolean)
    @Volatile private var old: Set<Int>? = null
    @Volatile var t0 = 0L
    val pics = java.util.concurrent.CopyOnWriteArrayList<Pic>()
    fun watch(oldTags: Set<Int>) { t0 = System.currentTimeMillis(); old = oldTags }
    fun stop() { old = null }
    override fun event(kind: String, detail: String) {}
    override fun notify(arm: Arm, packet: ByteArray) {}
    override fun panelChanged(arm: Arm) {
        val o = old ?: return
        if (arm != Arm.RIGHT) return
        val c = cut(sim.right.panel.copyOf(), strip)
        if (pics.isNotEmpty() && pics.last().bytes.contentEquals(c)) return
        pics += Pic(System.currentTimeMillis(), c, (0 until 4).any { s -> sim.motionTagIn(Arm.RIGHT, s)?.let { it in o } == true })
    }
    /** The pictures from the first with nothing of the old chain open on RIGHT: what the panel showed after its close. */
    fun afterClose(): List<Pic> = pics.dropWhile { it.oldOpen }
}

/** The places in [from]..[to] (5-ms steps, one pixel of Scope's pan) at which Scope's own paint of [strip] with [data] is
 *  exactly the picture [pic] (`cut`'s bytes; [strip] starts on an even column). */
private fun scopePlaces(pic: ByteArray, strip: Rect, data: wm.damage.core.windows.music.VizData, from: Long, to: Long): List<Long> {
    check(strip.x % 2 == 0) { "the strip starts on an even column: $strip" }
    val rowB = (strip.w + 1) / 2
    val g = wm.damage.core.gfx.Gray8(Geometry.PANEL_W, Geometry.PANEL_H)
    val out = ArrayList<Long>()
    var q = from
    while (q <= to) {
        g.fillRect(strip, 0)
        wm.damage.core.windows.music.ScopeViz.paint(g, strip, data, q)
        val same = (0 until strip.h).all { y -> (0 until strip.w).all { x ->
            val b = pic[y * rowB + (x shr 1)].toInt() and 0xFF
            (if (x and 1 == 0) b shr 4 else b and 0x0F) == Pack.level(g[strip.x + x, strip.y + y]) } }
        if (same) out += q
        q += 5
    }
    return out
}

/** The journal's submit records in [lines] with an op whose box meets [r]. */
private fun submitsMeeting(lines: List<String>, r: Rect): List<String> {
    val box = Regex("\"box\":\"\\((-?\\d+),(-?\\d+) (\\d+)x(\\d+)\\)\"")
    return lines.filter { "\"ev\":\"submit\"" in it && box.findAll(it).any { m ->
        val (x, y, w, h) = m.destructured; Rect(x.toInt(), y.toInt(), w.toInt(), h.toInt()).overlaps(r) } }
}

class MusicLaneShellTest {

    private class Lib : wm.damage.core.windows.music.MusicLibrary {
        val tracks = listOf(
            TrackMeta(1, "Short", "A", "B", 40_000, 4, 1, 2020, listOf("rock")),
            TrackMeta(2, "Shorter", "A", "B", 15_000, 6, 1, 2020, listOf("rock")),
        )
        val cat = wm.damage.core.windows.music.Catalog("c1", 1, tracks)
        override fun stateLine() = ""
        override fun catalog() = cat
        override fun refreshCatalog() {}
        override fun search(q: String) = emptyList<TrackRef>()
        override fun ask(request: String) = wm.damage.core.windows.music.ResolvedQueue()
        override fun similar(trackIds: List<Int>, exclude: List<Int>, n: Int) = emptyList<TrackRef>()
        override fun randomLibrary(n: Int, exclude: List<Int>) = emptyList<TrackRef>()
        override fun playlists() = emptyList<wm.damage.core.windows.music.Playlist>()
        override fun playlistTracks(id: Int) = emptyList<TrackRef>()
        override fun savePlaylist(name: String, trackIds: List<Int>, overwrite: Boolean) = wm.damage.core.windows.music.Playlist(1, name)
        override fun renamePlaylist(id: Int, name: String) {}
        override fun deletePlaylist(id: Int) {}
        override fun setPlaylistTracks(id: Int, trackIds: List<Int>) {}
        /** Stage S: the lyrics and the RMS envelope a test hands the window (none by default), and the listeners it told. */
        @Volatile var lyricsFor: (Int) -> wm.damage.core.windows.music.Lyrics? = { null }
        @Volatile var vizFor: (Int) -> wm.damage.core.windows.music.VizData? = { null }
        val listeners = java.util.concurrent.CopyOnWriteArrayList<wm.damage.core.windows.music.MusicLibrary.Listener>()
        override fun lyrics(trackId: Int): wm.damage.core.windows.music.Lyrics? = lyricsFor(trackId)
        override fun searchLyrics(trackId: Int, query: String) = emptyList<wm.damage.core.windows.music.Lyrics>()
        override fun setLyrics(trackId: Int, choice: wm.damage.core.windows.music.Lyrics) {}
        override fun art(trackId: Int, px: Int): ByteArray? = ByteArray(px * px / 2) { 0x77 }
        override fun viz(trackId: Int): wm.damage.core.windows.music.VizData? = vizFor(trackId)
        override fun ytSearch(q: String) = emptyList<wm.damage.core.windows.music.YtResult>()
        override fun ytGrab(id: String) = "j"
        override fun ytStatus(job: String) = wm.damage.core.windows.music.YtJob(job)
        override fun played(trackId: Int, startedMs: Long, endedMs: Long, completed: Boolean, skipped: Boolean) {}
        override fun streamUrl(trackId: Int, profile: wm.damage.core.windows.music.AudioProfile) = "sim://$trackId"
        override fun pretranscode(profile: wm.damage.core.windows.music.AudioProfile) = ""
        override fun rescan() = ""
        override fun addListener(l: wm.damage.core.windows.music.MusicLibrary.Listener) { listeners += l }
        override fun removeListener(l: wm.damage.core.windows.music.MusicLibrary.Listener) { listeners -= l }
        override fun setFocused(focused: Boolean, paceMs: Long) {}
    }

    private class Rig(stage: Int) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp = Files.createTempDirectory("damage-mml")
        /** The one clock the player, the window and the engine read. */
        val clock: () -> Long = { System.currentTimeMillis() }
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0xdff }
        val t = StartRig(sim, scope, clock = clock, c1StageFile = tmp.resolve("c1-stage").also { f ->
            Files.writeString(f, wm.damage.core.transport.LadderRecord(stage = stage, sessionKib = 256).encode()) })
        val journal = tmp.resolve("journal.jsonl")
        val ct = wm.damage.core.comp.CachedText(DigitText())
        val shell = Shell(ct, t, Persistence(tmp.resolve("state.json")), journal, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
        val lib = Lib()
        val player = SimMusicPlayer(lib, clock)
        /** Music draws with the shell's text, as the hosts hand it (cached glyph runs while Cached text is on — the time
         *  digits beside the bar go as such, which the transport reads as reaching the panel's edges). */
        val win = MusicWindow(ct, lib, player, scope, clock = clock)

        init {
            shell.register(win)
            t.devProbe("c1", "$stage")
            scope.launch { while (true) { val now = clock(); for (a in Arm.entries) sim.conformanceWake(a, now); delay(4) } }
        }

        fun notes(kind: String): List<String> = if (!Files.exists(journal)) emptyList() else Files.readAllLines(journal)
            .filter { "\"ev\":\"note\",\"kind\":\"$kind\"" in it }.map { it.substringAfter("\"detail\":\"").substringBefore("\"") }

        suspend fun until(what: String, maxMs: Long = 30_000, cond: () -> Boolean) =
            waitUntil(maxMs, 5, { "$what — lane ${notes("lane").takeLast(5)}; ${onLoop { shell.lanesForTest() }}; ${shell.quiescenceReport()}" }, cond)

        fun <T> onLoop(block: () -> T): T {
            val out = java.util.concurrent.CompletableFuture<T>()
            shell.services.runOnShell { out.complete(block()) }
            return out.get(10, java.util.concurrent.TimeUnit.SECONDS)
        }

        /** Up at 480 with Cached text on, Music open at Now Playing, a track playing. */
        suspend fun up() {
            shell.start(); until("start") { shell.isQuiescent() }
            shell.updateSettings { it.copy(heightMode = 480, cachedText = "on") }
            until("the atlas goes live") { shell.cachedTextActive && shell.cachedFontsLive.isNotEmpty() }
            until("the idle lanes at rest") { onLoop { shell.noticeLaneDoneForTest() && !shell.cacheWorkInFlight } }
            shell.postGesture(EvenHubMsg.EV_CLICK)
            until("music open") { shell.currentWindowId() == "music" }
            player.playQueue(lib.tracks.map { it.ref() }, 0, Mode.QUEUE, "test")
            until("playing") { player.state.play == PlayState.PLAYING }
        }

        fun laneTagIn(arm: Arm): Int? = (0..1).firstNotNullOfOrNull { s -> sim.motionTagIn(arm, s)?.takeIf { it and 0xF000 == WindowLanes.TAG } }
        /** Stage SF2: every lane tag open on [arm] now. */
        fun laneTags(arm: Arm): Set<Int> = (0 until 4).mapNotNull { s -> sim.motionTagIn(arm, s)?.takeIf { it and 0xF000 == WindowLanes.TAG } }.toSet()
        /** The journal's lines. */
        fun lines(): List<String> = if (!Files.exists(journal)) emptyList() else Files.readAllLines(journal)

        /** Open the Music menu at Now Playing and take the row labelled [label] (by name, never by counting notches). */
        suspend fun menuPick(label: String) {
            shell.postGesture(EvenHubMsg.EV_CLICK)
            until("the Music menu") { shell.menuIsOpen && shell.isQuiescent() }
            val i = shell.menuLabels.indexOfFirst { it == label || it.startsWith(label) }
            assertTrue(i >= 0, "menu row '$label' not in ${shell.menuLabels}")
            repeat((i - shell.menuCursor).mod(shell.menuLabels.size)) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
            until("the cursor on '$label'") { shell.menuCursor == i }
            shell.postGesture(EvenHubMsg.EV_CLICK)
        }

        /** The elapsed digits on RIGHT's panel: left of the bar on its row ([bar] is RIGHT's rect; the state glyph left of
         *  them is still while the song plays). How many times they changed in [ms], or — [first] — when they first did. */
        suspend fun digitChanges(bar: Rect, ms: Long, first: Boolean = false): Int {
            val box = Rect(maxOf(0, bar.x - 60), bar.y - 8, 56, 20)
            val stride = (Geometry.PANEL_W + 1) / 2
            fun cut(): ByteArray { val p = sim.right.panel.copyOf(); val o = java.io.ByteArrayOutputStream()
                for (y in box.y until box.bottom) o.write(p, y * stride + box.x / 2, (box.w + 1) / 2); return o.toByteArray() }
            var last = cut(); var n = 0
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) {
                delay(10)
                val c = cut()
                if (!c.contentEquals(last)) { n++; last = c; if (first) return n }
            }
            return n
        }

        /** Stage S: Music Mode with Scope as its visualizer, from Now Playing. */
        suspend fun scopeMode() {
            onLoop { for ((row, v) in listOf("Visualizer" to "Scope", "Music Mode · visualizer" to "on")) win.appSettings().first { it.name == row }.apply(v) }
            menuPick("Music Mode")
            until("Music Mode") { shell.exclusiveMode && shell.isQuiescent() }
        }

        fun setRow(row: String, v: String) = onLoop { win.appSettings().first { it.name == row }.apply(v) }

        /** The held lane's rects now (left, right), or null. */
        fun held(): Pair<Rect, Rect>? = onLoop { shell.comp.laneHold.singleOrNull() }

        /** How many times RIGHT's panel inside [box] changed in [ms]. */
        suspend fun changes(box: Rect, ms: Long): Int {
            var last = cut(sim.right.panel.copyOf(), box); var n = 0
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) { delay(10); val c = cut(sim.right.panel.copyOf(), box); if (!c.contentEquals(last)) { n++; last = c } }
            return n
        }

        fun starts() = notes("lane").count { "a chain starts" in it }

        /** Stage SF (finding 1): pause half a second past a whole second of the place (the strip's last once-a-second
         *  paint is then ≈ 100 px behind), wait for the chain to close on both lenses and the shell to settle, then the
         *  oracle at the pause: both lenses' whole panels are the truth (exclusive mode has no planes: `composed` on each),
         *  and the strip is Scope's own paint at the paused place, within 2 px. Stage SF2 (`MM-S-V2-review.md` 1): and
         *  EVERY picture of RIGHT's strip from the chain's close to the settle is that paint, within 2 px, in ONE flush —
         *  before the cure the release frame showed the strip's last once-a-second paint first (≈ 90 px back) for one flush. */
        suspend fun pauseShowsTheTruth(data: wm.damage.core.windows.music.VizData, strip: Rect, what: String) {
            until("$what: half a second past a whole second", 3_000) { onLoop { player.positionMs() } % 1000 in 450..600 }
            val rec = StripRec(sim, strip)
            sim.attachListener(rec)
            val j0 = lines().size
            onLoop { rec.watch(laneTags(Arm.RIGHT)); player.pause() }
            until("$what: the chain closed on both lenses") { laneTagIn(Arm.RIGHT) == null && laneTagIn(Arm.LEFT) == null && !onLoop { shell.lanesOpenForTest } }
            until("$what: settled") { shell.isQuiescent() }
            delay(300)
            rec.stop(); sim.detachListener(rec)
            val (truth, pos) = onLoop { assertEquals(emptyList(), shell.comp.planes, "$what: exclusive mode has no planes"); shell.comp.composed.copy() to player.positionMs() }
            val after = rec.afterClose()
            assertTrue(after.isNotEmpty(), "$what: RIGHT's strip was written after the chain closed (${rec.pics.size} picture(s), none after the close)")
            for ((i, p) in after.withIndex()) {
                assertFalse(p.oldOpen, "$what: picture ${i + 1} after the close (+${p.atMs - rec.t0} ms) is a step of the ended chain")
                if (scopePlaces(p.bytes, strip, data, pos - 10, pos + 10).isEmpty()) fail(
                    "$what, paused at $pos ms: picture ${i + 1} of ${after.size} after the chain closed (+${p.atMs - rec.t0} ms) is Scope's paint " +
                    "at the paused place within 2 px — it is at ${scopePlaces(p.bytes, strip, data, pos - 1_500, pos + 400).let { q -> if (q.isEmpty()) "no place" else "${q.first() - pos}..${q.last() - pos} ms" }}")
            }
            val flushes = submitsMeeting(lines().drop(j0), strip)
            assertEquals(1, flushes.size, "$what: ONE flush writes the strip from the pause to the settle: ${flushes.map { it.substringAfter("\"label\":").take(160) }}")
            val stride = (Geometry.PANEL_W + 1) / 2
            fun lv(p: ByteArray, x: Int, y: Int): Int { val b = p[y * stride + (x shr 1)].toInt() and 0xFF; return if (x and 1 == 0) b shr 4 else b and 0x0F }
            for ((name, panel) in listOf("LEFT" to sim.left.panel.copyOf(), "RIGHT" to sim.right.panel.copyOf())) {
                val off = ArrayList<String>(); var n = 0
                for (y in 0 until Geometry.PANEL_H) for (x in 0 until Geometry.PANEL_W)
                    if (Pack.level(truth[x, y]) != lv(panel, x, y)) { n++; if (off.size < 4) off += "($x,$y)" }
                assertEquals(0, n, "$what, paused: $name's panel is the truth everywhere — $n pixels off, the first at $off")
            }
            val near = (-2..2).firstOrNull { s ->
                val c = wm.damage.core.gfx.Gray8(Geometry.PANEL_W, Geometry.PANEL_H)
                wm.damage.core.windows.music.ScopeViz.paint(c, strip, data, pos + s * 5L)
                (strip.y until strip.bottom).all { y -> (strip.x until strip.right).all { x -> Pack.level(c[x, y]) == Pack.level(truth[x, y]) } }
            }
            assertNotNull(near, "$what, paused at ${pos} ms: the strip is Scope's paint at the paused place within 2 px")
        }

        suspend fun stop() { shell.stop(); scope.cancel(); tmp.toFile().deleteRecursively() }
    }

    /** [FakeText] whose digits differ — a box whose height follows the digit — so the elapsed time's repaint each second
     *  changes pixels and goes out (as a cached glyph run while Cached text is on). With [FakeText] every digit is the same
     *  box: a time-text repaint changed nothing and sent nothing, and no gate here could see the time text at all. */
    private class DigitText : wm.damage.core.text.TextRasterizer by FakeText() {
        override fun draw(surface: wm.damage.core.gfx.Gray8, x: Int, y: Int, text: String, font: wm.damage.core.text.FontSpec, level: Int) {
            for ((i, ch) in text.withIndex()) if (ch != ' ') {
                val h = if (ch.isDigit()) 1 + (ch - '0') else 10
                surface.fillRect(x + i * 8, y + 12 - h, 6, h, level)
            }
        }
    }

    /** The decoded level at (x, y) of a packed lens buffer. */
    private fun lv(packed: ByteArray, x: Int, y: Int): Int { val b = packed[y * ((Geometry.PANEL_W + 1) / 2) + (x shr 1)].toInt() and 0xFF; return if (x and 1 == 0) b shr 4 else b and 0x0F }

    /** The lit pixels of a bar row in a packed buffer: the run from [x0] at [lit]. */
    private fun litRun(packed: ByteArray, x0: Int, w: Int, y: Int, lit: Int): Int = (0 until w).takeWhile { lv(packed, x0 + it, y) == lit }.count()

    @Test
    fun theBarIsAHeldLaneTheGlassesGrowWhileThePhoneWritesNothingIntoIt(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.up()
            assertTrue(r.onLoop { r.shell.services.lanesInForce() }, "stage 5 on 256 KiB: lanes in force")
            r.until("a chain open on the glasses") { r.laneTagIn(Arm.RIGHT) != null && r.laneTagIn(Arm.LEFT) != null }
            val (barL, bar) = assertNotNull(r.onLoop { r.shell.comp.laneHold.firstOrNull() }, "the compositor holds the lane")   // (LEFT's rect, RIGHT's)
            val y = bar.y + bar.h / 2
            // the chain's start: RIGHT's shadow under the bar is the picture the chain started over, and stays so
            val shadow0 = r.sim.right.shadow.copyOf()
            val lit0 = litRun(shadow0, bar.x, bar.w, y, 8)
            delay(2_500)                                         // a 40-s song on a ≈ 400-px bar: ≈ 10 px a second
            val shadow1 = r.sim.right.shadow.copyOf()
            val panel1 = r.sim.right.panel.copyOf()
            for (x in bar.x until bar.right) assertEquals(lv(shadow0, x, y), lv(shadow1, x, y), "no write into the lane at x $x while it is held")
            val litPanel = litRun(panel1, bar.x, bar.w, y, 8)
            assertTrue(litPanel >= lit0 + 15, "the glasses grew the bar: $lit0 px at the start, $litPanel px on RIGHT's panel 2.5 s on")
            // the window's own bar (the truth) moved on with it, within a few pixels of the lens's
            val truthLit = r.onLoop { val c = r.shell.comp.expectedLens(false); c }   // belief: still the start
            assertEquals(lit0, (0 until bar.w).takeWhile { truthLit[bar.x + it, y] / 17 == 8 }.count(), "belief keeps the start under the lane")
            // the chain's start is said in the journal (the time text each second: theElapsedTimeTicksEachSecond…)
            assertTrue(r.notes("lane").any { "a chain starts" in it }, "said: ${r.notes("lane")}")
            // a seek: the chain starts again from the song's new place (a new tag, the bar written from the truth first)
            val tagBefore = r.laneTagIn(Arm.RIGHT)
            val chainsBefore = r.notes("lane").count { "a chain starts" in it }
            r.player.seekTo(30_000)
            // at once — not at the old chain's next PLAY-AFTER, half a stretch on (≈ 12 s here)
            r.until("a new chain after the seek", maxMs = 3_000) { r.notes("lane").count { "a chain starts" in it } > chainsBefore && r.laneTagIn(Arm.RIGHT).let { it != null && it != tagBefore } }
            assertTrue(r.notes("lane").any { "its key changed" in it }, "the seek changed the key: ${r.notes("lane").takeLast(4)}")
            r.until("the seek's bar on the panel") { litRun(r.sim.right.panel.copyOf(), bar.x, bar.w, y, 8) >= bar.w * 7 / 10 }
            // a pause ends the chain: nothing of it open, the panel the truth under the bar
            r.player.pause()
            r.until("the chain closed on both lenses") { r.laneTagIn(Arm.RIGHT) == null && r.laneTagIn(Arm.LEFT) == null && !r.onLoop { r.shell.lanesOpenForTest } }
            r.until("settled") { r.shell.isQuiescent() }
            delay(300)
            val panel2 = r.sim.right.panel.copyOf()
            val belief = r.onLoop { r.shell.comp.expectedLens(false).copy() }
            for (x in bar.x until bar.right) assertEquals(belief[x, y] / 17, lv(panel2, x, y), "paused: the panel is belief at x $x")
            // the window's continuous bar (the truth) at the paused place: its lit run is the place's, within a pixel
            val nominalX = (barL.x + bar.x) / 2
            val (pausedLit, pausedWant) = r.onLoop {
                val dur = r.player.state.durMs.takeIf { it > 0 } ?: r.player.state.track!!.durMs.toLong()
                (0 until bar.w).takeWhile { Pack.level(r.shell.comp.composed[nominalX + it, y]) == 8 }.count() to
                    wm.damage.core.windows.music.MusicMotion.litPx(bar.w, r.player.positionMs(), dur)
            }
            assertTrue(kotlin.math.abs(pausedLit - pausedWant) <= 1, "the window's bar at the paused place: $pausedLit px lit, the place's $pausedWant")
        } finally { r.stop() }
    }

    /** The elapsed time each second after a level is pushed over Now Playing and popped (the first review's finding 2,
     *  `MM-decisions.md` "Stage L, review pass 1" item 2): the push ended the time text's run (its next tick found another
     *  level on top) and the lanes' hook starts it again — RIGHT's panel changes at the digits twice in 2.5 s. */
    @Test
    fun theElapsedTimeTicksEachSecondAfterALevelIsPushedAndPopped(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.up()
            r.until("a chain open on the glasses") { r.laneTagIn(Arm.RIGHT) != null }
            val bar = assertNotNull(r.onLoop { r.shell.comp.laneHold.firstOrNull() }, "the compositor holds the lane").second
            val before = r.digitChanges(bar, 2_200)              // the control: the run before any push
            r.menuPick("Queue")
            r.until("the queue level") { r.win.levelDepth() == 2 && r.shell.isQuiescent() }
            delay(1_500)                                         // past a second: the run's next tick sees the level
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.until("back at Now Playing") { r.win.levelDepth() == 1 && r.shell.isQuiescent() }
            val composes0 = r.onLoop { r.shell.contentComposesForTest() }
            val changes = r.digitChanges(bar, 2_500)
            val composes = r.onLoop { r.shell.contentComposesForTest() } - composes0
            assertTrue(changes >= 2, "the elapsed digits changed $changes time(s) on RIGHT's panel in 2.5 s after the pop (before the push: $before; " +
                "content composed $composes time(s) meanwhile; ${r.onLoop { r.shell.lanesForTest() }}; lane ${r.notes("lane").takeLast(8)}; local ${r.notes("local").takeLast(4)})")
        } finally { r.stop() }
    }

    /** A chain ended by the menu opening over Now Playing: as the menu settles nothing of the chain is open on RIGHT, and
     *  the bar beside the menu is the window's own, moved on from the chain's start — not the start picture the shadow
     *  held under the chain. The tap comes just after a time-text repaint landed. Measured (MM-LF): this holds with the
     *  release's write removed too (row `MML-release-cells` not caught) — something else in that path writes the bar;
     *  it is the menu path's outcome, not a pin of the release. */
    @Test
    fun theMenuOverNowPlayingEndsTheChainAndTheBarIsWrittenFromTheTruth(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.up()
            r.until("a chain open on the glasses") { r.laneTagIn(Arm.RIGHT) != null && r.laneTagIn(Arm.LEFT) != null }
            val bar = assertNotNull(r.onLoop { r.shell.comp.laneHold.firstOrNull() }, "the compositor holds the lane").second
            val y = bar.y + bar.h / 2
            val lit0 = litRun(r.sim.right.shadow.copyOf(), bar.x, bar.w, y, 8)
            delay(3_000)                                         // ≈ 10 px a second: the window's bar moves on
            r.digitChanges(bar, 1_500, first = true)             // a time-text repaint has just landed
            val t0 = System.currentTimeMillis()
            r.shell.postGesture(EvenHubMsg.EV_CLICK)
            r.until("the menu up and settled") { r.shell.menuIsOpen && r.shell.isQuiescent() }
            val panel = r.sim.right.panel.copyOf()
            val tag = r.laneTagIn(Arm.RIGHT)
            val took = System.currentTimeMillis() - t0
            assertTrue(took < 900, "the menu settled ${took} ms after the tap — past the next time-text repaint, the check cannot tell")
            assertEquals(null, tag, "nothing of the chain open on RIGHT as the menu settles")
            val lit = litRun(panel, bar.x, bar.w, y, 8)
            assertTrue(lit >= lit0 + 15, "the bar beside the menu is the window's own: $lit px lit on RIGHT's panel, $lit0 at the chain's start")
        } finally { r.stop() }
    }

    /** Music Mode at a chain restart (the second review's finding 1, `MM-decisions.md` "Stage L, review pass 2" item 1).
     *  While the chain runs, the window's own bar (the truth) moves on each second with the time line, and no byte goes
     *  into the lane — RIGHT's shadow under it stays the chain's start. Then a seek to 0:30 of 0:40 arrives as one from
     *  another surface does: the player moves on its own thread and the window hears of it a message later, so a pump can
     *  come between (here the seek runs inside a message on the loop, whose own pump follows before the window's state
     *  message). Once the new chain is open, RIGHT's shadow under the bar — what the chain's end wrote, which the new chain
     *  plays over — holds the seek's place, not the card's paint at the song's start nor the place before the seek. */
    @Test
    fun aChainRestartInMusicModeWritesTheCurrentPlaceUnderTheLane(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.up()
            r.menuPick("Music Mode")
            r.until("Music Mode") { r.shell.exclusiveMode && r.shell.isQuiescent() }
            r.until("Music Mode's chain open on the glasses") { r.laneTagIn(Arm.RIGHT) != null && r.laneTagIn(Arm.LEFT) != null &&
                r.onLoop { r.shell.comp.laneHold.isNotEmpty() } }
            val (barL, bar) = r.onLoop { r.shell.comp.laneHold.first() }
            val y = bar.y + bar.h / 2
            val nominalX = (barL.x + bar.x) / 2
            fun truthLit() = r.onLoop { (0 until bar.w).takeWhile { Pack.level(r.shell.comp.composed[nominalX + it, y]) == 6 }.count() }
            val shadow0 = r.sim.right.shadow.copyOf()
            val truth0 = truthLit()
            delay(2_600)                                         // a 40-s song: ≈ bar.w / 40 px a second, two time lines
            val shadow1 = r.sim.right.shadow.copyOf()
            for (x in bar.x until bar.right) assertEquals(lv(shadow0, x, y), lv(shadow1, x, y), "no write into the lane at x $x while it is held")
            val truth1 = truthLit()
            assertTrue(truth1 >= truth0 + bar.w / 40, "the window's own bar moved on with the time line: $truth0 px lit, $truth1 px 2.6 s on (bar ${bar.w} px)")
            val tagBefore = r.laneTagIn(Arm.RIGHT)
            val chainsBefore = r.notes("lane").count { "a chain starts" in it }
            r.onLoop { r.player.seekTo(30_000) }
            r.until("a new chain after the seek", maxMs = 3_000) { r.notes("lane").count { "a chain starts" in it } > chainsBefore &&
                r.laneTagIn(Arm.RIGHT).let { it != null && it != tagBefore } }
            val under = litRun(r.sim.right.shadow.copyOf(), bar.x, bar.w, y, 6)
            assertTrue(under >= bar.w * 7 / 10, "under the new chain RIGHT's shadow holds the seek's place: $under px lit of ${bar.w} " +
                "(0:30 of 0:40 is ${bar.w * 3 / 4}; before the seek ${truth1}) — lane ${r.notes("lane").takeLast(4)}")
        } finally { r.stop() }
    }

    /** A stall while the song plays (the second review's finding 3, `MM-decisions.md` "Stage L, review pass 2" item 3): the
     *  PC stops giving the stream data, so the place stands while the state stays PLAYING. The chain ends once and none
     *  starts over 5 s of it (each start is a release frame and a PLAY); when the place moves again the chain starts. */
    @Test
    fun aStallWhilePlayingStartsNoChainUntilThePlaceMoves(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.up()
            r.until("a chain open on the glasses") { r.laneTagIn(Arm.RIGHT) != null }
            delay(1_200)
            val ends0 = r.notes("lane").count { "the chain ends" in it }
            r.player.pcStopsServing()
            assertEquals(PlayState.PLAYING, r.player.state.play, "the song still plays")
            delay(1_500)                                         // a pump has seen the place stand (the time line each second)
            val starts0 = r.notes("lane").count { "a chain starts" in it }
            delay(5_000)
            val starts = r.notes("lane").count { "a chain starts" in it } - starts0
            val ends = r.notes("lane").count { "the chain ends" in it } - ends0
            assertEquals(0, starts, "no chain starts over 5 s of the stall — lane ${r.notes("lane").takeLast(6)}")
            assertTrue(ends <= 1, "the chain ended once at most: $ends — lane ${r.notes("lane").takeLast(6)}")
            assertFalse(r.onLoop { r.shell.lanesOpenForTest }, "no chain open while the place stands")
            r.player.pcServes = true                             // data again: the place moves
            r.until("a chain again once the place moves", maxMs = 3_000) { r.notes("lane").count { "a chain starts" in it } > starts0 && r.laneTagIn(Arm.RIGHT) != null }
            // once: not a chain on the zero before the stall and a second at the next paint (the time line's each second)
            val after = r.notes("lane").count { "a chain starts" in it }
            delay(2_500)
            assertEquals(after, r.notes("lane").count { "a chain starts" in it }, "one start when the place moves — lane ${r.notes("lane").takeLast(6)}")
        } finally { r.stop() }
    }

    /** The release's write (`MM-decisions.md` "Stage L, the focused read: ruled" item 1; the pin of `MML-release-cells`):
     *  a seek on a stream whose data stops at the seek — the place stands where the seek put it, so the chain the seek
     *  starts is ended by the stall rule with the window's bar exactly the picture under the lane (no cell differs). Only
     *  the release's own write then meets the chain's instance: with it, nothing of the chain stays open and the panel
     *  under the bar is belief; without it the instance keeps drawing the bar on past the place that stands (measured in
     *  the focused read: 1.5 s on, the chain open on both lenses and RIGHT's bar 20 px past belief). */
    @Test
    fun aStallAtASeekIsClosedOnBothLensesByTheReleasesWrite(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.up()
            r.until("a chain open on the glasses") { r.laneTagIn(Arm.RIGHT) != null && r.laneTagIn(Arm.LEFT) != null }
            val bar = assertNotNull(r.onLoop { r.shell.comp.laneHold.firstOrNull() }, "the compositor holds the lane").second
            val y = bar.y + bar.h / 2
            val starts0 = r.notes("lane").count { "a chain starts" in it }
            r.player.pcStopsServing()
            r.player.seekTo(30_000)
            r.until("a chain starts after the seek", maxMs = 3_000) { r.notes("lane").count { "a chain starts" in it } > starts0 }
            val ends0 = r.notes("lane").count { "the chain ends" in it }
            r.until("the stall ends that chain", maxMs = 4_000) { r.notes("lane").count { "the chain ends" in it } > ends0 && !r.onLoop { r.shell.lanesOpenForTest } }
            r.until("settled") { r.shell.isQuiescent() }
            delay(1_500)
            val tagR = r.laneTagIn(Arm.RIGHT); val tagL = r.laneTagIn(Arm.LEFT)
            val panel = r.sim.right.panel.copyOf()
            val belief = r.onLoop { r.shell.comp.expectedLens(false).copy() }
            val off = (bar.x until bar.right).count { belief[it, y] / 17 != lv(panel, it, y) }
            assertTrue(tagR == null && tagL == null && off == 0, "1.5 s into the stall — lane tag on RIGHT $tagR, LEFT $tagL; " +
                "$off px of the bar's row differ from belief on RIGHT's panel (lit: panel ${litRun(panel, bar.x, bar.w, y, 8)}, " +
                "belief ${(0 until bar.w).takeWhile { belief[bar.x + it, y] / 17 == 8 }.count()}) — lane ${r.notes("lane").takeLast(6)}")
        } finally { r.stop() }
    }

    // ------------------------------------------------------------------------------------------------ stage S: Scope
    /** A made-up RMS envelope (40 s: loud and quiet passages and a silence) — never a file of his. */
    private val SCOPE_DATA = wm.damage.core.windows.music.ScopeRehearsal.envelope(IntArray(2_000) { i -> if (i % 500 in 400..449) 0 else 1 + (i * 7 + i / 25) % 15 })
    /** Stage SF2 (the review's "observed, not chased"): the rig's second track's own made-up envelope (15 s, no silence), so
     *  a picture of the strip shows which track it came from. */
    private val SCOPE_DATA_2 = wm.damage.core.windows.music.ScopeRehearsal.envelope(IntArray(750) { i -> 1 + (i * 11 + i / 7) % 15 })
    /** Synced lyrics whose line changes every 400 ms, each line digits (so [DigitText] draws it differently). */
    private val LYRICS = wm.damage.core.windows.music.Lyrics("test", (0 until 100).joinToString("\n") { i ->
        "[%02d:%02d.%02d]%s".format(i * 400 / 60_000, i * 400 / 1000 % 60, i * 400 % 1000 / 10, "$i".repeat(4)) })

    /** Stage S: in Music Mode with Scope and its data, the strip ALONE is the held lane (not the card's bar), the glasses
     *  pan it while the phone writes nothing into it, a new area is written at each link through the ring in turn with
     *  nothing closed, and a notice — whose box, in the content's middle, does not meet the strip — leaves the chain
     *  running beside it. **The pin of `MM-S-built.md` §0:** the lyrics between the card and the strip move on on RIGHT's
     *  panel while the chain runs (one program holding the bar and the strip owns the box between them, and the panel then
     *  never shows a lyric change there). */
    @Test
    fun scopeIsMusicModesHeldLaneTheGlassesPanItAndTheLyricsMoveOn(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.lib.vizFor = { SCOPE_DATA }; r.lib.lyricsFor = { LYRICS }
            r.up()
            r.scopeMode()
            r.until("Scope's chain open on the glasses") { r.laneTagIn(Arm.RIGHT) != null && r.laneTagIn(Arm.LEFT) != null && r.held()?.second?.h == 64 }
            val strip = r.held()!!.second
            assertEquals(wm.damage.core.windows.music.ScopeRehearsal.strip(480), strip, "the rehearsal's strip (the fuzz's, the vectors') is Music Mode's")
            val starts0 = r.starts(); val notes0 = r.notes("lane").size
            val shadow0 = cut(r.sim.right.shadow.copyOf(), strip)
            val panned = r.changes(strip, 2_000)
            assertContentEquals(shadow0, cut(r.sim.right.shadow.copyOf(), strip), "no write into the strip while it is held")
            assertTrue(panned >= 20, "the glasses moved the strip: RIGHT's panel there changed $panned times in 2 s")
            val lyrics = Rect(strip.x, 160, strip.w, strip.y - 160 - 16)
            val moved = r.changes(lyrics, 2_500)
            assertTrue(moved >= 3, "the lyrics between the card and the strip changed $moved time(s) on RIGHT's panel in 2.5 s while Scope's chain ran " +
                "(held ${r.held()}; lane ${r.notes("lane").takeLast(4)})")
            delay(3_500)
            val areas = r.notes("lane").filter { "written for its programs" in it }.map { it.substringAfter("area ").substringBefore(" ").toInt() }
            assertTrue(areas.size >= 3, "an area a link: ${r.notes("lane")}")
            assertEquals(areas.indices.map { (areas[0] + it) % WindowLanes.AREAS }, areas, "the areas in turn")
            assertEquals(starts0, r.starts(), "the chain ran on: ${r.notes("lane").drop(notes0)}")
            assertTrue(r.notes("lane").drop(notes0).none { "ends" in it || "strike" in it }, "nothing ended it: ${r.notes("lane").drop(notes0)}")
            // a notice: its box does not meet the strip; the chain runs on beside it
            r.shell.postNotice(wm.damage.core.shell.Notifications.Notice("SMS · TEST", "t1", "one line of body", "12:00"))
            delay(2_000)
            assertEquals(starts0, r.starts(), "the chain ran on beside the notice: ${r.notes("lane").drop(notes0)}")
            assertTrue(r.notes("lane").none { "a notice box over a lane" in it } && r.laneTagIn(Arm.RIGHT) != null, "the notice did not end it: ${r.notes("lane").takeLast(4)}")
        } finally { r.stop() }
    }

    /** Stage S: Scope's chain at a seek (a new chain at once from the new place), a pause (nothing of it open on either
     *  lens, the panel under the strip the truth), the play again, and a stall (none while the place stands; one when it
     *  moves). */
    @Test
    fun scopesChainEndsAtASeekAPauseAndAStallAndStartsAgain(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.lib.vizFor = { SCOPE_DATA }
            r.up()
            r.scopeMode()
            r.until("Scope's chain open") { r.laneTagIn(Arm.RIGHT) != null && r.held()?.second?.h == 64 }
            val strip = r.held()!!.second
            // ruling 8: while the chain is open the window paints the strip once a second, with the time line — not at the
            // visualizer's rate (8 a second); no lyrics here, so Music Mode repaints about twice in 2 s (a window-mode compose
            // counter never moves in exclusive mode: the repaints are counted where exclusive mode makes them)
            val paints0 = r.onLoop { r.shell.exclusiveRepaintsForTest() }
            delay(2_000)
            val paints = r.onLoop { r.shell.exclusiveRepaintsForTest() } - paints0
            assertTrue(paints <= 5, "Music Mode repainted $paints times in 2 s while Scope's chain was open")
            val tagBefore = r.laneTagIn(Arm.RIGHT); val starts0 = r.starts()
            // stage SF2: a seek to the middle of a second, so the pause below (450..600 ms past the second) comes before
            // the time line's next tick — no paint with the new chain held between them (the gate's red: a seek to a whole
            // second raced that tick, and the strip stayed the seek's release paint at the pause)
            r.onLoop { r.player.seekTo(20_100) }
            r.until("a new chain after the seek", maxMs = 3_000) { r.starts() > starts0 && r.laneTagIn(Arm.RIGHT).let { it != null && it != tagBefore } }
            assertTrue(r.notes("lane").any { "its key changed" in it }, "the seek changed the key: ${r.notes("lane").takeLast(4)}")
            r.pauseShowsTheTruth(SCOPE_DATA, strip, "after the seek")
            val starts1 = r.starts()
            r.player.play()
            r.until("a chain again", maxMs = 3_000) { r.starts() > starts1 && r.laneTagIn(Arm.RIGHT) != null }
            delay(800)
            r.player.pcStopsServing()
            delay(1_500)
            val starts2 = r.starts()
            delay(4_000)
            assertEquals(starts2, r.starts(), "no chain starts while the place stands — lane ${r.notes("lane").takeLast(6)}")
            assertFalse(r.onLoop { r.shell.lanesOpenForTest }, "none open during the stall")
            r.player.pcServes = true
            r.until("a chain once the place moves", maxMs = 3_000) { r.starts() > starts2 && r.laneTagIn(Arm.RIGHT) != null }
        } finally { r.stop() }
    }

    /** Stage S: what the lane set is as things change in Music Mode — before Scope's data is in hand the card's bar is the
     *  lane and the strip is the window's; its arrival (the host's `vizReady`) makes the strip the lane; Bars puts the bar
     *  back and Scope the strip again; with the card off the strip stays the lane and runs, and on again; leaving Music Mode
     *  ends Scope's chain (nothing of it open on either lens) and Now Playing's bar is the lane again. */
    @Test
    fun scopesLaneFollowsItsDataTheVisualizerTheCardAndMusicMode(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.up()
            r.scopeMode()
            r.until("the bar's chain (no data yet)") { r.laneTagIn(Arm.RIGHT) != null && r.held()?.second?.h == 6 }
            // stage SF (finding 3): the visualizer changed while the bar is the lane leaves the bar's chain running — no new
            // PLAY, the same instance on the glasses
            for (v in listOf("Bars", "Pulse", "Scope")) {
                val s0 = r.starts(); val tag0 = r.laneTagIn(Arm.RIGHT)
                r.setRow("Visualizer", v)
                delay(800)
                assertEquals(s0, r.starts(), "Visualizer $v with the bar the lane: no new chain — lane ${r.notes("lane").takeLast(4)}")
                assertEquals(tag0, r.laneTagIn(Arm.RIGHT), "Visualizer $v: the bar's chain runs on")
            }
            r.lib.vizFor = { SCOPE_DATA }
            val id = r.onLoop { r.player.state.track!!.id }
            for (l in r.lib.listeners) l.vizReady(id)
            r.until("the data arrived: Scope's chain") { r.laneTagIn(Arm.RIGHT) != null && r.held()?.second?.h == 64 }
            fun endedOpen(before: Set<Int>) = listOfNotNull(r.laneTagIn(Arm.RIGHT), r.laneTagIn(Arm.LEFT)).filter { it in before }
            for ((row, v, h) in listOf(Triple("Visualizer", "Bars", 6), Triple("Visualizer", "Scope", 64), Triple("Music Mode · card", "off", 64), Triple("Music Mode · card", "on", 64))) {
                val tags = setOfNotNull(r.laneTagIn(Arm.RIGHT), r.laneTagIn(Arm.LEFT))
                val starts = r.starts()
                r.setRow(row, v)
                r.until("$row $v: a chain on the ${if (h == 6) "bar" else "strip"}", maxMs = 5_000) { r.shell.isQuiescent() && r.held()?.second?.h == h && r.laneTagIn(Arm.RIGHT) != null }
                if (r.starts() > starts) assertEquals(emptyList(), endedOpen(tags), "$row $v: the ended chain's instances are closed")
                delay(600)
                assertTrue(r.laneTagIn(Arm.RIGHT) != null, "$row $v: running")
            }
            // stage SF (finding 2): Scope's chain keys on the zero its STRIP was painted at — with the card off nothing
            // paints the bar, so a seek re-keys the lane only through the strip's own zero
            r.setRow("Music Mode · card", "off")
            r.until("card off: Scope's chain", maxMs = 5_000) { r.shell.isQuiescent() && r.held()?.second?.h == 64 && r.laneTagIn(Arm.RIGHT) != null }
            val seekStarts = r.starts(); val seekNotes = r.notes("lane").size
            r.onLoop { r.player.seekTo(25_000) }
            r.until("card off: a new chain after the seek", maxMs = 1_500) { r.starts() > seekStarts && r.laneTagIn(Arm.RIGHT) != null }
            assertTrue(r.notes("lane").drop(seekNotes).any { "its key changed" in it }, "card off: the seek changed the key: ${r.notes("lane").drop(seekNotes)}")
            r.setRow("Music Mode · card", "on")
            r.until("card on again", maxMs = 5_000) { r.shell.isQuiescent() && r.held()?.second?.h == 64 && r.laneTagIn(Arm.RIGHT) != null }
            val tags = setOfNotNull(r.laneTagIn(Arm.RIGHT), r.laneTagIn(Arm.LEFT))
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.until("Now Playing") { !r.shell.exclusiveMode && r.shell.isQuiescent() }
            r.until("Now Playing's bar chain") { r.held()?.second?.h == 10 && r.laneTagIn(Arm.RIGHT) != null }
            assertEquals(emptyList(), endedOpen(tags), "Scope's chain is closed on both lenses")
        } finally { r.stop() }
    }

    /** Stage SF (finding 1, the oracle at stage 5): at a pause with Scope's chain ended, both lenses' whole panels are the
     *  truth and the strip is Scope's own paint at the paused place — at every height the lane runs (its strip the
     *  rehearsal's, finding 4). Before the cure the strip stayed its last once-a-second paint, up to ≈ 190 px back. */
    @Test
    fun aPauseShowsScopesStripAtThePausedPlaceAtEveryHeight(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.lib.vizFor = { SCOPE_DATA }
            r.up()
            for (h in listOf(480, 416, 352, 288)) {
                // Music's own Size, set at Now Playing (exclusive mode's repaint does not re-lay the shell), then Music Mode
                r.setRow("Size", "$h")
                r.until("at $h: Now Playing laid out at $h") { r.onLoop { r.shell.layout.safe.h } == h && r.shell.isQuiescent() }
                r.scopeMode()
                val want = wm.damage.core.windows.music.ScopeRehearsal.strip(h)
                r.until("at $h: Scope's chain on the rehearsal's strip $want (held ${r.held()})", maxMs = 8_000) { r.shell.isQuiescent() && r.held()?.second == want && r.laneTagIn(Arm.RIGHT) != null }
                delay(1_200)                                     // a once-a-second paint or more behind the chain's start
                r.pauseShowsTheTruth(SCOPE_DATA, want, "at $h")
                r.player.play()
                r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
                r.until("at $h: back at Now Playing") { !r.shell.exclusiveMode && r.shell.isQuiescent() }
            }
        } finally { r.stop() }
    }

    /** Stage SF2 (`MM-S-V2-review.md` 1, the class swept): a chain that ends with no pause — the visualizer changed away
     *  from Scope through the window's record (as from another surface: the row reaches the presenter, which only posts its
     *  render) — shows no picture of Scope on RIGHT's strip after its close: the strip's first write after the close is
     *  the new visualizer's, not Scope's last once-a-second paint. */
    @Test
    fun aVisualizerChangedAwayFromScopeShowsNoScopePictureAfterTheChainCloses(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.lib.vizFor = { SCOPE_DATA }
            r.up()
            r.scopeMode()
            r.until("Scope's chain open") { r.shell.isQuiescent() && r.held()?.second?.h == 64 && r.laneTagIn(Arm.RIGHT) != null }
            val strip = r.held()!!.second
            delay(1_200)
            r.until("half a second past a whole second", 3_000) { r.onLoop { r.player.positionMs() } % 1000 in 450..600 }
            val rec = StripRec(r.sim, strip)
            r.sim.attachListener(rec)
            val pos = r.onLoop { rec.watch(r.laneTags(Arm.RIGHT)); r.win.appSettings().first { it.name == "Visualizer" }.apply("Bars"); r.player.positionMs() }
            r.until("the bar's chain") { r.shell.isQuiescent() && r.held()?.second?.h == 6 && r.laneTagIn(Arm.RIGHT) != null }
            delay(300)
            rec.stop(); r.sim.detachListener(rec)
            val after = rec.afterClose()
            assertTrue(after.isNotEmpty(), "RIGHT's strip was written after Scope's chain closed (${rec.pics.size} picture(s))")
            for ((i, p) in after.withIndex()) {
                val q = scopePlaces(p.bytes, strip, SCOPE_DATA, pos - 1_500, pos + 400)
                if (q.isNotEmpty()) fail("picture ${i + 1} of ${after.size} after Scope's chain closed (+${p.atMs - rec.t0} ms) is Scope's paint at " +
                    "${q.first() - pos}..${q.last() - pos} ms from the change")
            }
        } finally { r.stop() }
    }

    /** Stage SF2 (`MM-S-V2-review.md` 2): with the deflated cache write in force, each of Scope's areas written while its
     *  chain runs goes as ONE stage message under 1 KB on the wire — not a deflated first piece and a raw second (the
     *  transport deflates one waiting message at a time; a 4.5–5.4 KB area went as 3,064 B + the rest). */
    @Test
    fun scopesAreaGoesAsOneDeflatedMessageWhileItsChainRuns(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.lib.vizFor = { SCOPE_DATA }
            r.up()
            r.scopeMode()
            r.until("Scope's chain open") { r.shell.isQuiescent() && r.held()?.second?.h == 64 && r.laneTagIn(Arm.RIGHT) != null }
            val j0 = r.lines().size
            delay(9_500)                                         // three links of ≈ 2.9 s
            val lines = r.lines()
            val done = lines.filter { "\"ev\":\"done\"" in it }.associate {
                Regex("\"id\":(\\d+)").find(it)!!.groupValues[1] to Regex("\"bytes\":(\\d+)").find(it)!!.groupValues[1].toInt() }
            fun stageNote(l: String) = "\"ev\":\"note\",\"kind\":\"stage\"" in l && "the held lanes' area" in l
            val firsts = lines.drop(j0).filter { stageNote(it) && "submitted as flush" in it }
            val more = lines.drop(j0).filter { stageNote(it) && ": piece " in it }
            val said = firsts.map { l ->
                val d = l.substringAfter("\"detail\":\"").substringBefore("\"")
                val id = d.substringAfter("submitted as flush ").substringBefore(" ")
                "$d -> ${done[id]} B on the wire"
            }
            println("SF2 area writes while the chain ran: ${said.joinToString("; ")}; later pieces: ${more.size}")
            assertTrue(firsts.size >= 2, "an area a link while the chain ran: $said")
            assertEquals(emptyList(), more.map { it.substringAfter("\"detail\":\"").substringBefore("\"") }, "no area went as a second piece")
            for (l in firsts) {
                val d = l.substringAfter("\"detail\":\"").substringBefore("\"")
                assertTrue("piece 1 of 1 " in d, "one message: $d")
                val id = d.substringAfter("submitted as flush ").substringBefore(" ")
                val b = assertNotNull(done[id], "flush $id done: $d")
                assertTrue(b < 1_024, "under 1 KB on the wire: $b B — $d")
            }
        } finally { r.stop() }
    }

    /** Stage SF2 (the review's "observed, not chased"): the rig's two tracks have their own envelopes, so a picture shows
     *  which track it came from; at a track change while Scope's chain runs, no picture of RIGHT's strip after the ended
     *  chain's close is the old track's. Each picture's places on both tracks are printed for the report. */
    @Test
    fun aTrackChangeShowsNoPictureOfTheOldTrackAfterTheChainCloses(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.lib.vizFor = { id -> if (id == 2) SCOPE_DATA_2 else SCOPE_DATA }
            r.up()
            r.scopeMode()
            r.until("Scope's chain open") { r.shell.isQuiescent() && r.held()?.second?.h == 64 && r.laneTagIn(Arm.RIGHT) != null }
            val strip = r.held()!!.second
            r.until("past 2 s, half a second past a whole second", 20_000) { r.onLoop { r.player.positionMs() }.let { it > 2_000 && it % 1000 in 450..600 } }
            val rec = StripRec(r.sim, strip)
            r.sim.attachListener(rec)
            val (old, tags) = r.onLoop { val p = r.player.positionMs(); val t = r.laneTags(Arm.RIGHT); rec.watch(t); r.player.next(); p to t }
            r.until("the second track's chain on the strip", maxMs = 8_000) {
                r.shell.isQuiescent() && r.held()?.second?.h == 64 && r.laneTagIn(Arm.RIGHT).let { it != null && it !in tags } }
            delay(300)
            rec.stop(); r.sim.detachListener(rec)
            assertEquals(2, r.onLoop { r.player.state.track?.id }, "the second track plays")
            fun rng(q: List<Long>, ref: Long) = if (q.isEmpty()) "-" else "${q.first() - ref}..${q.last() - ref}"
            println("SF2 track change at the old track's $old ms: " + rec.pics.joinToString("; ") { p ->
                "+${p.atMs - rec.t0} ms ${if (p.oldOpen) "(old chain open)" else ""} old ${rng(scopePlaces(p.bytes, strip, SCOPE_DATA, old - 1_500, old + 400), old)} " +
                    "new ${rng(scopePlaces(p.bytes, strip, SCOPE_DATA_2, -300, 1_000), 0)}" })
            val after = rec.afterClose()
            assertTrue(after.isNotEmpty(), "RIGHT's strip was written after the old chain closed (${rec.pics.size} picture(s))")
            for ((i, p) in after.withIndex()) {
                val q = scopePlaces(p.bytes, strip, SCOPE_DATA, old - 1_500, old + 400)
                if (q.isNotEmpty()) fail("picture ${i + 1} of ${after.size} after the old chain closed (+${p.atMs - rec.t0} ms) is the old track's Scope " +
                    "at ${q.first() - old}..${q.last() - old} ms from the change")
            }
        } finally { r.stop() }
    }

    /** Stage S below stage 5: Scope in Music Mode paints as today — no lane, its frames at the visualizer's rate. */
    @Test
    fun belowStage5ScopePaintsItsOwnFrames(): Unit = runBlocking {
        val r = Rig(stage = 4)
        try {
            r.lib.vizFor = { SCOPE_DATA }
            r.up()
            r.scopeMode()
            delay(1_000)
            val strip = Rect(0, 400, Geometry.PANEL_W, 76)          // the strip's band at 480 on either lens (no lyrics or peek there)
            val frames = r.changes(strip, 2_000)
            assertTrue(frames >= 8, "Scope's own frames on RIGHT's panel: $frames in 2 s")
            assertTrue(r.onLoop { r.shell.comp.laneHold.isEmpty() && !r.shell.lanesOpenForTest } && r.notes("lane").isEmpty(), "nothing of the lanes: ${r.notes("lane")}")
        } finally { r.stop() }
    }

    /** Stage 4 (no EFFECTS): nothing of the lanes — not in force, no lane tag ever, Music's blocks as before. */
    @Test
    fun belowStage5NothingOfTheLanesRuns(): Unit = runBlocking {
        val r = Rig(stage = 4)
        try {
            r.up()
            delay(1_500)
            assertFalse(r.onLoop { r.shell.services.lanesInForce() }, "stage 4: not in force")
            assertEquals(null, r.laneTagIn(Arm.RIGHT), "no lane tag")
            assertTrue(r.onLoop { r.shell.comp.laneHold.isEmpty() && !r.shell.lanesOpenForTest }, "nothing held")
            assertTrue(r.notes("lane").isEmpty(), "nothing said: ${r.notes("lane")}")
        } finally { r.stop() }
    }
}
