package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import wm.damage.core.comp.CachedText
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.gfx.Level
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.geom.Rect
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellKeeper
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.transport.Arm
import wm.damage.core.transport.CfwTransportBase
import wm.damage.core.transport.DisplayOp
import wm.damage.core.transport.FlushRequest
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.EvenHubMsg
import wm.damage.core.wire.SettingsMsg
import wm.damage.core.wire.TextureCache

/**
 * `HANDOFF.md` §54 (2026-09-15), Adam's ruling of §51.8 — the bounded atlas skip. The
 * glasses keep the texture cache across a session rebuild whenever the new acquire is a
 * renewal on both lenses (`CLAIMS.md`: freed on expiry, FB_RELEASE, a fresh acquire and
 * mode 11; kept on a renewal). The transport records its lease writes per arm and decides
 * at the rebuild's acquire whether the previous lease was still inside its window; the
 * shell keeps its atlas — the fonts live from the first compose, no upload — when it was,
 * and resets otherwise. No firmware flag: the simulator's cache follows the installed
 * renewal rule, and the checks below read it directly.
 */
class AtlasCarryTest {

    /** The base choreography over the model, on a clock the test moves; the link can be
     *  ended from the test (a supervision timeout) and the model's lease state is mirrored
     *  as `SimTransport` does. */
    private class ClockedTransport(val glass: GlassFirmwareSim, scope: CoroutineScope, val clock: () -> Long) :
        CfwTransportBase(scope, "clocked") {
        override val instant: Boolean get() = true
        override fun nowMs(): Long = clock()

        init {
            glass.attachListener(object : GlassFirmwareSim.SimDiag {
                override fun event(kind: String, detail: String) {}
                override fun notify(arm: Arm, packet: ByteArray) { onNotifyPacket(arm, packet) }
                override fun panelChanged(arm: Arm) {}
            })
        }

        override suspend fun connectLink() {}
        override suspend fun disconnectLink() { glass.linkReset() }
        override suspend fun writeArm(arm: Arm, packet: ByteArray) { glass.write(arm, packet, clock()) }
        override fun onMaintenanceTick() {
            glass.tick(clock())
            val held = glass.leaseHeld(Arm.LEFT, clock()) && glass.leaseHeld(Arm.RIGHT, clock())
            if (!held && state.value.started) setLease(false, "lease LOST — stock repaints (fail-open)")
            else if (held) setLease(true, "lease held")
        }
        fun endLink(reason: String) = onLinkDown(reason)
    }

    /** Rows of text in one face — drawn every frame, so the face is packed at once. */
    private class Rows(private val tx: TextRasterizer) : DamageWindow("rows", "Rows", IconKind.FILES) {
        private val model = ListModel()
        private val font = FontSpec(Face.SYSTEM, 20)
        override fun view(): WindowView = WindowView.ListView(model, { 40 },
            paintRow = { g, i, r, _ -> tx.draw(g, r.x + 40, r.y + 4, "Row $i text", font, Level.BODY) },
            paintLens = { g, r, i -> tx.draw(g, r.x + 44, r.y + 6, "Row $i", font, Level.HEAD) },
            onCommit = {})
        override fun summary() = Summary("40 rows")
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) {}
    }

    private class Rig(scope: CoroutineScope) {
        val tmp = Files.createTempDirectory("damage-atlas-carry")
        var clock = 1_000_000L
        val sim = GlassFirmwareSim().also { it.damageContract = 1 }     // a Damage build: RIGHT's uptime answers the reset check
        val transport = ClockedTransport(sim, scope) { clock }
        val text = CachedText(FakeText())
        val journalPath = tmp.resolve("journal.jsonl")
        val shell = Shell(text, transport, Persistence(tmp.resolve("state.json")), journalPath, scope)
        val keeper = ShellKeeper(shell, transport, scope, retryPauseMs = 100)

        init { shell.register(Rows(text)) }

        fun lines(): List<String> = if (!Files.exists(journalPath)) emptyList() else Files.readAllLines(journalPath)
        fun atlasNotes(): List<String> = lines().filter { it.contains("\"kind\":\"atlas\"") }
        fun atlasUploads(): Int = lines().count { it.contains("\"ev\":\"submit\"") && it.contains("\"label\":\"ATLAS\"") }
        fun failedFlushes(): Int = lines().count { it.contains("\"ev\":\"done\"") && it.contains("\"ok\":false") }
        fun cachedDrawsSubmitted(): Int = lines().count { it.contains("\"ev\":\"submit\"") && it.contains("\"op\":\"drawtext\"") }
        fun driving() = keeper.state == ShellKeeper.State.RUNNING && transport.state.value.started

        suspend fun settle(what: String) {
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 10_000) {
                if (shell.isQuiescent()) return
                delay(10)
            }
            throw AssertionError("$what: did not settle — ${shell.quiescenceReport()}")
        }

        suspend fun until(what: String, cond: () -> Boolean) {
            val t0 = System.currentTimeMillis()
            while (!cond() && System.currentTimeMillis() - t0 < 15_000) delay(10)
            assertTrue(cond(), "$what — live=${shell.cachedFontsLive} notes=${atlasNotes().takeLast(6)}")
        }

        /** Up, drawing the rows window with cached text on and its face live. */
        suspend fun upWithTheAtlasLive() {
            keeper.start()
            until("the keeper drives") { driving() }
            settle("start")
            shell.services.runOnShell { shell.services.openWindow("rows", null) }
            settle("open rows")
            shell.updateSettings { it.copy(cachedText = "on") }
            until("the atlas uploads and the face goes live") { shell.cachedTextActive && shell.cachedFontsLive.isNotEmpty() }
            settle("after the upload")
            assertTrue(Arm.entries.all { sim.cacheAllocated(it) }, "the model holds the cache on both arms")
        }

        /** The link ends now; the keeper rebuilds the session; the shell drives again. */
        suspend fun rebuild(attempt: Int, reason: String) {
            transport.endLink(reason)
            until("session $attempt drives") { keeper.attempts == attempt && driving() }
            until("the glasses saw connect $attempt") { sim.preludeAcks == attempt }
            settle("after rebuild $attempt")
        }

        fun lens(left: Boolean): Gray8 {
            val ctx = if (left) sim.left else sim.right
            val g = Gray8(640, 480)
            for (y in 0 until 480) for (x in 0 until 640) {
                val b = ctx.panel[y * ctx.stride + (x shr 1)].toInt() and 0xFF
                g[x, y] = (if (x and 1 == 0) b shr 4 else b and 0x0F) * 17
            }
            return g
        }

        fun assertGlassMatchesBelief(what: String) {
            for (left in booleanArrayOf(true, false)) {
                val e = shell.comp.expectedLens(left); val g = lens(left)
                var diffs = 0; var first: String? = null
                for (y in 0 until 480) for (x in 0 until 640) {
                    val q = minOf(15, (e[x, y] + 8) / 17) * 17
                    if (q != g[x, y]) { diffs++; if (first == null) first = "($x,$y) belief $q glass ${g[x, y]}" }
                }
                assertEquals(0, diffs, "$what: ${if (left) "L" else "R"} belief != glass ($diffs px, first $first)")
            }
        }
    }

    @Test
    fun theAtlasIsKeptAcrossARebuildInsideTheLeaseWindow(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope)
        try {
            rig.upWithTheAtlasLive()
            val live = rig.shell.cachedFontsLive
            val uploads = rig.atlasUploads()
            val gen = Arm.entries.associateWith { rig.sim.cacheGen(it) }
            assertTrue(uploads > 0, "the first session uploaded the atlas")
            assertTrue(rig.atlasNotes().any { "reset — no atlas from a previous session" in it }, "the first session's note: ${rig.atlasNotes()}")

            // the link ends 20 s into the lease; the rebuild's acquire is a renewal on both arms
            rig.clock += 20_000L
            rig.rebuild(2, "test: supervision timeout")
            val kept = rig.atlasNotes().lastOrNull { "kept across the rebuild" in it }
            assertTrue(kept != null, "the kept note: ${rig.atlasNotes().takeLast(6)}")
            assertTrue("L gap 20.0 s, R gap 20.0 s" in kept!!, "the gaps in the note: $kept")
            assertEquals(live, rig.shell.cachedFontsLive, "the fonts are live from the first compose")
            assertTrue(rig.shell.cachedTextActive, "the compositor ships cached draws again")
            assertEquals(uploads, rig.atlasUploads(), "nothing was uploaded again")
            assertTrue(Arm.entries.all { rig.sim.cacheAllocated(it) && rig.sim.cacheGen(it) == gen[it] },
                "the model kept the cache on both arms, unwritten")
            assertTrue(rig.transport.state.value.leaseCarried, "the transport says the acquire was a renewal")

            // and the kept cache serves: a notch's delta carries cached draws, and the glass
            // equals belief with no refusal anywhere
            val drawsBefore = rig.cachedDrawsSubmitted()
            repeat(3) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("notch $it") }
            assertTrue(rig.cachedDrawsSubmitted() > drawsBefore, "the notches shipped cached draws: ${rig.lines().takeLast(6)}")
            assertEquals(0, rig.failedFlushes(), "no flush failed: ${rig.lines().filter { "\"ok\":false" in it }}")
            rig.assertGlassMatchesBelief("after notches on the kept cache")
            rig.keeper.stop()
        } finally {
            scope.cancel()
            rig.tmp.toFile().deleteRecursively()
        }
    }

    /** 2026-09-15 12:54 on glass (`HANDOFF.md` §59): the right lens rebooted, the phone's skip
     *  judged the lease timing alone (58.8 s gaps) and kept the atlas; the right lens, its
     *  cache empty, refused every cached draw while the left drew them. The session start now
     *  reads RIGHT's uptime before the carry decision: a reboot since the last acquire resets. */
    @Test
    fun theAtlasIsResetWhenTheRightLensRebootedInsideTheWindow(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope)
        try {
            rig.upWithTheAtlasLive()
            val uploads = rig.atlasUploads()
            rig.clock += 20_000L
            rig.sim.rebootForTest(Arm.RIGHT, rig.clock)                   // its RAM is gone: no cache, no lease
            val notesBefore = rig.atlasNotes().size
            rig.rebuild(2, "RIGHT disconnected: supervision timeout")
            val note = rig.atlasNotes().drop(notesBefore).firstOrNull { "reset — " in it }
            assertTrue(note != null && "R reset" in note, "the reset note names the reboot: ${rig.atlasNotes().drop(notesBefore).take(4)}")
            assertTrue(rig.lines().any { "\"kind\":\"keeper\"" in it && "the glasses reset" in it }, "the keeper noted the reset")
            rig.until("the atlas is uploaded again and the face goes live") { rig.atlasUploads() > uploads && rig.shell.cachedFontsLive.isNotEmpty() }
            rig.settle("after the re-upload")
            assertTrue(Arm.entries.all { rig.sim.cacheAllocated(it) }, "both lenses hold the cache again")
            repeat(3) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("notch $it") }
            assertEquals(0, rig.failedFlushes(), "no flush failed")
            rig.assertGlassMatchesBelief("after notches on the re-uploaded cache: the right lens draws too")
            rig.keeper.stop()
        } finally {
            scope.cancel()
            rig.tmp.toFile().deleteRecursively()
        }
    }

    /** LEFT cannot report an uptime (the senders' lens rule): its link timing out is taken as a
     *  possible reboot and resets the atlas; RIGHT's timing out without a reset keeps it. */
    @Test
    fun aLeftSupervisionTimeoutResetsTheAtlasARightOneWithoutARebootKeepsIt(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope)
        try {
            rig.upWithTheAtlasLive()
            val uploads = rig.atlasUploads()
            rig.clock += 20_000L
            val kept0 = rig.atlasNotes().size
            rig.rebuild(2, "RIGHT disconnected: supervision timeout")
            assertTrue(rig.atlasNotes().drop(kept0).any { "kept across the rebuild" in it }, "RIGHT timed out, no reboot read: kept — ${rig.atlasNotes().drop(kept0).take(3)}")
            assertEquals(uploads, rig.atlasUploads(), "nothing uploaded again")
            rig.clock += 20_000L
            val notesBefore = rig.atlasNotes().size
            rig.rebuild(3, "LEFT disconnected: supervision timeout")
            val note = rig.atlasNotes().drop(notesBefore).firstOrNull { "reset — " in it }
            assertTrue(note != null && "L link timed out" in note, "LEFT timed out: reset — ${rig.atlasNotes().drop(notesBefore).take(4)}")
            rig.until("the atlas is uploaded again") { rig.atlasUploads() > uploads && rig.shell.cachedFontsLive.isNotEmpty() }
            rig.settle("after the re-upload")
            rig.assertGlassMatchesBelief("after the LEFT timeout's re-upload")
            rig.keeper.stop()
        } finally {
            scope.cancel()
            rig.tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun theAtlasIsResetWhenTheRebuildComesAfterTheLeaseWindow(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope)
        try {
            rig.upWithTheAtlasLive()
            val uploads = rig.atlasUploads()
            val gen = rig.sim.cacheGen(Arm.LEFT)

            // 200 s pass before the link is seen to end: the lease has lapsed on the
            // glasses (the model frees the cache), and the rebuild's acquire is fresh
            rig.clock += 200_000L
            rig.rebuild(2, "test: supervision timeout, late")
            val reset = rig.atlasNotes().lastOrNull { "reset — the acquire was not a renewal on both arms" in it }
            assertTrue(reset != null, "the reset note: ${rig.atlasNotes().takeLast(6)}")
            assertTrue("L gap 200.0 s, R gap 200.0 s" in reset!!, "the gaps in the note: $reset")
            assertFalse(rig.transport.state.value.leaseCarried)
            rig.until("the atlas goes up again and the face is live") {
                rig.atlasUploads() > uploads && rig.shell.cachedFontsLive.isNotEmpty()
            }
            rig.settle("after the second upload")
            assertTrue(rig.sim.cacheGen(Arm.LEFT) > gen, "the model's cache was written anew")
            repeat(2) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("notch $it") }
            rig.assertGlassMatchesBelief("after the re-upload")
            rig.keeper.stop()
        } finally {
            scope.cancel()
            rig.tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun aReleaseOrAnotherWriterResetsTheAtlas(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope)
        try {
            rig.upWithTheAtlasLive()

            // another driver wrote the cache through the same transport (a takeover over
            // the seam): the bytes on the glasses are not this shell's atlas
            val foreign = CfwModes.cacheUpdate(listOf(CfwModes.CacheWrite(CfwModes.TEXTURE_CACHE_SIZE - 64, ByteArray(32) { 7 })))
            rig.transport.submit(FlushRequest(listOf(DisplayOp.CacheWrite(foreign)), 1L, "ATLAS", writer = "another-shell"))
            rig.until("the foreign write is on record") { rig.transport.state.value.cacheWriter == "another-shell" }
            rig.clock += 20_000L                              // past the settle window: the acquire at T0 stands
            rig.rebuild(2, "test: supervision timeout")
            assertTrue(rig.transport.state.value.leaseCarried, "the acquire itself was a renewal")
            assertTrue(rig.atlasNotes().any { "reset — the last cache write through the transport was not this shell's (another-shell)" in it },
                "the writer note: ${rig.atlasNotes().takeLast(6)}")
            rig.until("the atlas goes up again") { rig.shell.cachedFontsLive.isNotEmpty() }
            rig.settle("after the re-upload")

            // the lease released on purpose (the Silent-Mode sleep does this): the next
            // acquire is fresh on the glasses, so the atlas resets by construction
            rig.transport.setLeaseWanted(false)
            rig.until("released on the glasses") { Arm.entries.none { rig.sim.leaseHeld(it, rig.clock) } }
            rig.clock += 5_000L
            rig.rebuild(3, "test: link end after a release")
            assertFalse(rig.transport.state.value.leaseCarried)
            assertTrue(rig.atlasNotes().any { "reset — the acquire was not a renewal on both arms — L released, R released" in it },
                "the release note: ${rig.atlasNotes().takeLast(6)}")
            rig.until("the atlas goes up once more") { rig.shell.cachedFontsLive.isNotEmpty() }
            rig.settle("after the third upload")
            rig.assertGlassMatchesBelief("after the third upload")
            rig.keeper.stop()
        } finally {
            scope.cancel()
            rig.tmp.toFile().deleteRecursively()
        }
    }

    /** The transport's record on its own: a lease write inside [CfwTransportBase.LINK_SETTLE_MS]
     *  of the link's end is struck (the packets of the last seconds before a supervision
     *  timeout were never exchanged), the window is the expiry less the margin, and a
     *  release is never struck. */
    @Test
    fun theLeaseLogStrikesTheLastSecondsAndHonoursTheWindow(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim()
            var clock = 1_000_000L
            val t = ClockedTransport(sim, scope) { clock }
            val full = Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480)))
            fun carry() = t.state.value.leaseCarried to t.state.value.leaseCarry

            t.start(full)                                     // the first acquire, at T0
            assertEquals(false to "L no lease on record, R no lease on record", carry())
            clock += 30_000L
            t.setLeaseWanted(false); t.setLeaseWanted(true)   // an acquire at T0+30 s
            clock += 15_000L
            t.endLink("test: supervision timeout")            // T0+45 s: the T0+30 s acquire stands (15 s old)
            clock += 5_000L
            t.start(full)                                     // T0+50 s: 20 s since the last acquire taken as arrived
            assertEquals(true to "L gap 20.0 s, R gap 20.0 s", carry())

            clock += 5_000L
            t.endLink("test: supervision timeout")            // T0+55 s: the T0+50 s acquire is struck (5 s old)
            clock += 54_000L
            t.start(full)                                     // T0+109 s: 79 s since T0+30 s — inside the 80 s window
            assertEquals(true to "L gap 79.0 s, R gap 79.0 s", carry())

            clock += 6_000L
            t.endLink("test: supervision timeout")            // T0+115 s: the T0+109 s acquire is struck
            clock += 1_000L
            t.start(full)                                     // T0+116 s: 86 s since T0+30 s — outside
            assertEquals(false to "L gap 86.0 s, R gap 86.0 s", carry())

            clock += 20_000L
            t.stop()                                          // the link is up: a release goes out
            clock += 1_000L
            t.start(full)
            assertEquals(false to "L released, R released", carry())
            t.stop()
            assertEquals(SettingsMsg.LEASE_EXPIRY_MS - CfwTransportBase.LEASE_CARRY_MARGIN_MS, CfwTransportBase.LEASE_CARRY_WINDOW_MS)
        } finally {
            scope.cancel()
        }
    }

    /** The chunk in flight when the link ends is not a refusal: it goes again from the acked
     *  mark, and a kept atlas carries it into the next session. */
    @Test
    fun anUndeliveredChunkGoesAgainFromTheAckedMark() {
        val a = wm.damage.core.comp.GlyphAtlas(FakeText())
        assertTrue(a.add(FontSpec(Face.SYSTEM, 20)))
        val chunks = a.takeUpload(maxMessage = 200)
        assertTrue(chunks.size >= 2, "more than one chunk for the test: ${chunks.size}")
        a.acked()                                             // the first landed
        val acked = a.ackedBytes
        assertTrue(acked > TextureCache.GUARD && acked < a.used)
        a.rewindToAcked()
        val again = a.takeUpload(maxMessage = 200)
        assertEquals(chunks.size - 1, again.size, "the chunks after the acked mark go again")
        assertEquals(a.used, a.sentBytes)
        assertEquals(acked, a.ackedBytes, "the acked mark is untouched")
    }
}
