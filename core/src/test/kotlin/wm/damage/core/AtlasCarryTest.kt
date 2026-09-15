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
import wm.damage.core.transport.draw2
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
        /** A link that stops carrying anything once the next ACQUIRE has gone out: the start
         *  that wrote it cannot complete, and its release cannot reach the glasses either. */
        @Volatile var deadAfterAcquire = false
        @Volatile private var acquireWritten = false
        override suspend fun writeArm(arm: Arm, packet: ByteArray) {
            if (deadAfterAcquire && acquireWritten) throw java.io.IOException("test: the link carries nothing more")
            glass.write(arm, packet, clock())
            if (deadAfterAcquire && packet.size > 12 && packet[6].toInt() == SettingsMsg.SID &&
                (0 until packet.size - 3).any { i -> packet[i] == 'F'.code.toByte() && packet[i + 1] == 'C'.code.toByte() && packet[i + 2].toInt() == 1 && packet[i + 3].toInt() == SettingsMsg.OP_FB_ACQUIRE })
                acquireWritten = arm == Arm.RIGHT || acquireWritten
        }
        fun revive() { deadAfterAcquire = false; acquireWritten = false }
        /** The lease lost as the maintenance tick reports a lapse (fail-open). */
        fun loseLease() = setLease(false, "test: lease LOST — stock repaints (fail-open)")
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

    private class Rig(scope: CoroutineScope, contract: Int = 1) {
        val tmp = Files.createTempDirectory("damage-atlas-carry")
        var clock = 1_000_000L
        val sim = GlassFirmwareSim().also { it.damageContract = contract }     // a Damage build: RIGHT's uptime answers the reset check
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
            assertTrue(note != null && "L link ended as a reboot would" in note, "LEFT timed out: reset — ${rig.atlasNotes().drop(notesBefore).take(4)}")
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
            // the atlas goes either way: dropped the moment the shell's pump sees the foreign writer (2026-09-15
            // review), or at the rebuild's start if the rebuild comes first — which one is timing
            assertTrue(rig.atlasNotes().any { "reset — the last cache write through the transport was not this shell's (another-shell)" in it ||
                "the texture cache was written by 'another-shell' mid-session" in it },
                "the writer note: ${rig.atlasNotes().takeLast(8)}")
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

    /** 2026-09-15 review: a start that wrote its ACQUIRE and then ended (its link gone, so its release
     *  never reached the glasses) made a carry decision the shell never acted on, and that decision
     *  had already cleared the reboot mark — the next session carried an atlas the rebooted lens no
     *  longer held. The mark stands until a start completes, and an unfinished decision means "not carried". */
    @Test
    fun aStartThatEndsAfterItsAcquireLeavesTheNextSessionNotCarried(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim().also { it.damageContract = 1 }
            var clock = 1_000_000L
            val t = ClockedTransport(sim, scope) { clock }
            val full = Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480)))
            t.start(full)                                                   // session A completes
            clock += 20_000L
            t.endLink("LEFT disconnected: supervision timeout")             // LEFT may have rebooted
            clock += 1_000L
            t.deadAfterAcquire = true
            val failed = runCatching { t.start(full) }                      // session B decides, writes its ACQUIRE, then its link is gone
            assertTrue(failed.isFailure, "session B's start cannot complete")
            assertFalse(t.state.value.leaseCarried, "B's own decision: not carried (${t.state.value.leaseCarry})")
            t.revive()
            clock += 2_000L
            t.start(full)                                                   // session C: B's acquire was struck, A's is 23 s old
            assertFalse(t.state.value.leaseCarried, "C must not carry: ${t.state.value.leaseCarry}")
            assertTrue("the last start did not complete" in t.state.value.leaseCarry, t.state.value.leaseCarry)
            assertTrue("L link ended as a reboot would" in t.state.value.leaseCarry, "LEFT's mark stood through B: ${t.state.value.leaseCarry}")
            t.stop()
        } finally {
            scope.cancel()
        }
    }

    /** 2026-09-15 review: the reset check's own record says whether RIGHT holds a cache at all
     *  (field 18). A reboot the uptime rule cannot see (here, a model whose uptime runs on as if
     *  nothing happened) still resets the atlas instead of leaving the right lens blind. */
    @Test
    fun aRightLensThatHoldsNoCacheResetsTheAtlasWhateverItsUptimeSays(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope)
        try {
            rig.upWithTheAtlasLive()
            val uploads = rig.atlasUploads()
            rig.clock += 20_000L
            val offset = rig.sim.uptimeOffsetMs
            rig.sim.rebootForTest(Arm.RIGHT, rig.clock)                   // its RAM is gone: no cache, no lease
            rig.sim.uptimeOffsetMs = offset                               // ...and its uptime reads as if it ran on
            val notesBefore = rig.atlasNotes().size
            rig.rebuild(2, "RIGHT disconnected: supervision timeout")
            val note = rig.atlasNotes().drop(notesBefore).firstOrNull { "reset — " in it }
            assertTrue(note != null && "R holds no cache" in note, "the reset note names the empty cache: ${rig.atlasNotes().drop(notesBefore).take(4)}")
            rig.until("the atlas is uploaded again") { rig.atlasUploads() > uploads && rig.shell.cachedFontsLive.isNotEmpty() }
            rig.settle("after the re-upload")
            repeat(2) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("notch $it") }
            rig.assertGlassMatchesBelief("the right lens draws from its new cache")
            rig.keeper.stop()
        } finally {
            scope.cancel()
            rig.tmp.toFile().deleteRecursively()
        }
    }

    /** 2026-09-15 review, the hold-back rule on a contract-2 build: a reset soon after DRAW2 was armed
     *  holds it back — the session draws with v1 shapes (the v1 atlas builds over 64 KiB although the
     *  160 KiB size was taken first; it used to throw and leave cached text off), later starts keep it
     *  off until asked for by hand, and a hand-set flag set with bit 2 lifts the hold. */
    @Test
    fun aHoldBackOnContractTwoKeepsDraw2OffUntilAskedAndCachedTextWorks(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope, contract = 2)
        try {
            rig.upWithTheAtlasLive()
            assertTrue(rig.transport.state.value.draw2, "session 1 arms DRAW2")
            rig.clock += 30_000L
            for (arm in Arm.entries) rig.sim.rebootForTest(arm, rig.clock)   // both lenses reset 30 s after the arming
            rig.rebuild(2, "RIGHT disconnected: supervision timeout")
            assertFalse(rig.transport.state.value.draw2, "held back: ${rig.transport.state.value.flagsInForce}")
            assertTrue(rig.lines().any { "\"kind\":\"keeper\"" in it && "hold-back" in it }, "the hold-back is journaled")
            rig.until("cached text goes live on the v1 atlas") { rig.shell.cachedTextActive && rig.shell.cachedFontsLive.isNotEmpty() }
            assertTrue(rig.atlasNotes().any { "built for the v1 cache" in it }, "a v1 atlas: ${rig.atlasNotes().takeLast(4)}")
            rig.settle("after the v1 upload")
            rig.assertGlassMatchesBelief("v1 shapes after the hold-back")

            rig.clock += 30_000L
            rig.rebuild(3, "RIGHT disconnected: supervision timeout")          // no reset this time
            assertFalse(rig.transport.state.value.draw2, "still held back at the next start")
            assertTrue(rig.lines().any { "DRAW2 held back" in it }, "the start says why")

            rig.transport.devProbe("flags", "0x0004")                         // asked for by hand
            rig.clock += 30_000L
            rig.rebuild(4, "RIGHT disconnected: supervision timeout")
            assertTrue(rig.transport.state.value.draw2, "armed again once asked: flags 0x${rig.transport.state.value.flagsInForce.toString(16)}")
            rig.until("the v2 atlas goes live") { rig.shell.cachedFontsLive.isNotEmpty() && rig.atlasNotes().last { "built for" in it }.contains("contract 2") }
            rig.settle("after the v2 upload")
            rig.assertGlassMatchesBelief("v2 again")
            rig.keeper.stop()
        } finally {
            scope.cancel()
            rig.tmp.toFile().deleteRecursively()
        }
    }

    /** 2026-09-15 review: a lease lost mid-session on a Damage build took DRAW2 and the cache's size with
     *  it; the transport kept saying DRAW2 and the renewal re-armed nothing, so every v2 op after it was
     *  refused. The state now drops the flags at once and the shell rebuilds the session. */
    @Test
    fun aLeaseLostMidSessionOnContractTwoRebuildsAndDrawsAgain(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope, contract = 2)
        try {
            rig.upWithTheAtlasLive()
            assertTrue(rig.transport.state.value.draw2)
            rig.transport.loseLease()
            assertFalse(rig.transport.state.value.draw2, "the lease took DRAW2 with it")
            assertEquals(CfwModes.TEXTURE_CACHE_SIZE, rig.transport.state.value.cacheSize, "and the cache's size")
            rig.until("the shell rebuilt the session") { rig.keeper.attempts == 2 && rig.driving() }
            rig.settle("after the rebuild")
            assertTrue(rig.transport.state.value.draw2, "the rebuild's start armed DRAW2 again")
            rig.until("cached text is live again") { rig.shell.cachedFontsLive.isNotEmpty() }
            rig.settle("after the atlas")
            repeat(2) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("notch $it") }
            assertEquals(0, rig.failedFlushes(), "no flush failed")
            rig.assertGlassMatchesBelief("v2 draws after the rebuild")
            rig.keeper.stop()
        } finally {
            scope.cancel()
            rig.tmp.toFile().deleteRecursively()
        }
    }

    /** 2026-09-15 review: `tools/glassdrive.py selftest:` sends a vector's cache writes into the LIVE
     *  cache the shell's atlas sits in. The shell kept drawing from bytes that were no longer its own;
     *  it now drops the atlas at the first write it did not make (pixels, belief = glass), and a fresh
     *  atlas after it is not dropped again. */
    @Test
    fun aSelfTestsLiveCacheWriteDropsTheAtlasForTheSession(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope)
        try {
            rig.upWithTheAtlasLive()
            // a vector's write over the start of the atlas: records the shell packed are now garbage on the glasses
            val garbage = CfwModes.cacheUpdate(listOf(CfwModes.CacheWrite(TextureCache.GUARD, ByteArray(600) { (it * 7 + 3).toByte() })))
            rig.transport.devProbe("selftest", "live:" + garbage.joinToString("") { "%02x".format(it) })
            rig.until("the shell dropped its atlas") { !rig.shell.cachedTextActive && rig.atlasNotes().any { "written by 'selftest#" in it } }
            repeat(3) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("notch $it") }
            rig.assertGlassMatchesBelief("pixels after the foreign write")
            // a Cached text toggle builds a fresh atlas over it, and that one stays
            rig.shell.updateSettings { it.copy(cachedText = "off") }
            rig.settle("off")
            rig.shell.updateSettings { it.copy(cachedText = "on") }
            rig.until("a fresh atlas goes live") { rig.shell.cachedTextActive && rig.shell.cachedFontsLive.isNotEmpty() }
            rig.settle("after the fresh upload")
            repeat(2) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_TOP); rig.settle("notch back $it") }
            assertTrue(rig.shell.cachedTextActive, "the fresh atlas is not dropped again")
            rig.assertGlassMatchesBelief("the fresh atlas draws")
            rig.keeper.stop()
        } finally {
            scope.cancel()
            rig.tmp.toFile().deleteRecursively()
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
