package wm.damage.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.DisplayOp
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.Pb

/**
 * APK 0.65, P5 item 15 (the identity-guard sweep's IG-1, its corrected fix): the glasses keep their 16-deep fid ring
 * for as long as they are up (the fork's context is a singleton nothing re-creates; a link, a lease and a CREATE leave
 * the ring alone), while a NEW transport instance — a phone process restarted, a PC standby claim — starts its fids at
 * 1. Its first delta then hits the fid the last instance put there and is skipped in silence (`cfw_diag`'s exact-hit
 * rule, `DESIGN.md` §8.2), and nothing on the host sees it: belief and the mirror both hold the whole picture. Fixed: one
 * awaited start gate after the warmup's ack sends mode 7 sub 0, which clears the ring on both lenses; the allocator is
 * not restarted (`Round3Test`'s wrap pin keeps reaching the wrap). A refusal, or a first copy released by the second's
 * ack, is one `control` note and the start goes on; not sent when this session's READ says Silent Mode. Each pin is
 * watched to fail as the build report's pins table says.
 */
class FidRingStartTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @AfterTest fun clean() { scope.cancel() }

    private val full = Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480)))

    private class Seen {
        val notes = CopyOnWriteArrayList<Pair<String, String>>()
        val faults = CopyOnWriteArrayList<String>()
        val done = CopyOnWriteArrayList<TransportEvent.FlushDone>()
        val links = CopyOnWriteArrayList<TransportEvent.Link>()
        fun doneOf(id: Long) = done.firstOrNull { it.id == id }
        fun controlNotes(about: String) = notes.filter { it.first == "control" && about in it.second }.map { it.second }
    }

    private fun rig(glass: GlassFirmwareSim): Pair<StartRig, Seen> {
        val t = StartRig(glass, scope)
        val seen = Seen()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            t.events.collect {
                when (it) {
                    is TransportEvent.Note -> seen.notes.add(it.kind to it.detail)
                    is TransportEvent.Fault -> seen.faults.add("${it.what}: ${it.detail}")
                    is TransportEvent.FlushDone -> seen.done.add(it)
                    is TransportEvent.Link -> seen.links.add(it)
                    else -> {}
                }
            }
        }
        return t to seen
    }

    /** The data field of a single-packet EvenHub image message (`EvenHubMsg.imageFragment`: field 5's wrapper, its
     *  field 8 — G2CC docs/G2_BLE_PROTOCOL.md §6.5), or null for any other packet. */
    private fun imageData(p: ByteArray): ByteArray? {
        if (!StartRig.firstOfImage(p) || p[4].toInt() != 1) return null
        val wrapper = Pb.fields(StartRig.body(p)).firstOrNull { it.field == 5 }?.bytes ?: return null
        return Pb.bytesField(wrapper, 8)
    }
    private fun isRingClear(p: ByteArray) = imageData(p)?.contentEquals(byteArrayOf(7, 0)) == true

    private fun pixel(glass: GlassFirmwareSim, arm: Arm, x: Int, y: Int): Int {
        val c = if (arm == Arm.LEFT) glass.left else glass.right
        val b = c.panel[y * c.stride + (x shr 1)].toInt() and 0xFF
        return if (x and 1 == 0) b shr 4 else b and 0x0F
    }

    private fun delta(r: Rect, level: Int): DisplayOp.Delta {
        val g = Gray8(640, 480)
        g.fillRect(r.x, r.y, r.w, r.h, level * 17)
        return DisplayOp.Delta(r, Zl.encodeCfw(Pack.rect(g, r)))
    }

    /** Runs a start under a harness bound that decides once (a start that parks fails here, it does not hang the
     *  suite); returns its error, null when it completed. */
    private suspend fun startEnds(t: StartRig, seen: Seen, what: String): Throwable? {
        val starting = scope.async { runCatching { t.start(full) } }
        waitUntil(15_000, message = { "the start did not end ($what): notes ${seen.notes.takeLast(6)}" }) { starting.isCompleted }
        return starting.await().exceptionOrNull()
    }
    private suspend fun started(t: StartRig, seen: Seen, what: String) {
        val e = startEnds(t, seen, what)
        if (e != null) throw AssertionError("the start ended ($what): $e", e)
    }

    /** Stops [t] and waits for its stop's own link event: the events come in order through one collector, so every
     *  note the session wrote has been taken by then — a note read before it may still be on its way. */
    private suspend fun stopped(t: StartRig, seen: Seen) {
        t.stop()
        waitUntil(10_000, message = { "the stop's link event did not arrive: ${seen.links}" }) { seen.links.any { !it.connected && "stopped" in it.detail } }
    }

    private suspend fun flushed(t: StartRig, seen: Seen, what: String, vararg ids: Long) =
        waitUntil(10_000, message = { "$what: flushes ${ids.toList()} not all answered — ${seen.done}; notes ${seen.notes.takeLast(6)}" }) {
            ids.all { seen.doneOf(it) != null }
        }

    /** Pin 1, the finding: one pair of glasses (the model keeps its ring), two transport instances in turn (the host's
     *  process restarted, the glasses did not). B's first delta carries fid 1, which A's first delta left in the ring. */
    @Test
    fun aNewInstancesFirstDeltaIsNotSkippedAgainstTheRingTheLastOneLeft(): Unit = runBlocking {
        val glass = GlassFirmwareSim()
        val (a, seenA) = rig(glass)
        started(a, seenA, "the first instance")
        val a1 = a.submit(FlushRequest(listOf(DisplayOp.Keyframe(full)), 1, wide = true))
        val a2 = a.submit(FlushRequest(listOf(delta(Rect(100, 100, 40, 20), 8)), 2))
        flushed(a, seenA, "the first instance", a1, a2)
        a.stop()
        a.onNotify = { _, _ -> null }          // the model answers every listener: only the driving instance hears it

        val (b, seenB) = rig(glass)
        started(b, seenB, "the second instance")
        val b1 = b.submit(FlushRequest(listOf(DisplayOp.Keyframe(full)), 1, wide = true))
        val r = Rect(300, 200, 40, 20)
        val b2 = b.submit(FlushRequest(listOf(delta(r, 12)), 2))
        flushed(b, seenB, "the second instance", b1, b2)
        // the ack precedes the decode in the model as on the glasses: the write call that sent it still holds the model
        synchronized(glass) {
            for (arm in Arm.entries) {
                assertFalse(glass.flags(arm).getValue("f_dup"), "$arm: the second instance's delta hit the last one's fid (${glass.flags(arm)})")
                assertEquals(12, pixel(glass, arm, r.x + 5, r.y + 5), "$arm: the second instance's first delta is on the panel")
            }
        }
        assertTrue(seenB.done.all { it.ok }, "every flush of the second instance was accepted: ${seenB.done}")
        stopped(b, seenB)
        assertEquals(emptyList(), seenB.controlNotes("fid-ring"), "the clear was confirmed: no note")
    }

    /** Pin 3: the clear's first ack is lost. The re-send's ack releases the first copy (§34): one `control` note says the
     *  clear was not confirmed, the start completes, and the session's first keyframe completes behind it. */
    @Test
    fun aLostAckForTheRingClearIsOneNoteAndTheStartGoesOn(): Unit = runBlocking {
        val glass = GlassFirmwareSim()
        val (t, seen) = rig(glass)
        val imageAcks = AtomicInteger(0)
        // the first image ack is the warmup's; the second, the clear's first copy, is lost
        t.onNotify = { _, p -> if (isImageAckOnly(p) && imageAcks.incrementAndGet() == 2) null else p }
        started(t, seen, "a lost clear ack")
        val k = t.submit(FlushRequest(listOf(DisplayOp.Keyframe(full)), 1, wide = true))
        flushed(t, seen, "the keyframe after a lost clear ack", k)
        assertTrue(seen.doneOf(k)!!.ok, "the first keyframe completes: ${seen.doneOf(k)}")
        stopped(t, seen)
        assertEquals(1, seen.controlNotes("fid-ring clear went unacked").size, "one re-send: ${seen.notes}")
        val said = seen.controlNotes("fid-ring clear at the session start was not confirmed")
        assertEquals(1, said.size, "one note says the clear was not confirmed: ${seen.notes}")
        assertTrue("lost" in said.single() && "fids continue at" in said.single(), "it names the cause and where the fids go on: $said")
    }

    /** R4 item 5 (review 3B open finding (b)): the clear's acks are held through five re-asks, then delivered. Three
     *  copies fill the window; the rest are queued behind it. Unfixed, the image lane wrote every queued copy as the
     *  delivered acks freed the slots, after the first ack had already ended the wait they were queued for (3 here).
     *  Fixed: a copy whose wait has completed is not written (no slot left taken), and the re-ask's note says "queued"
     *  while the window has no free slot. One copy is still written (6 of 6 runs): the one already waiting for a slot
     *  when the first ack lands — the ack frees the slot before the clear's wait completes (the ack handler releases
     *  the slot, then completes the message; the wait completes on the completion coroutine after it). The end is
     *  read behind a keyframe queued after the copies (one lane, in order). */
    @Test
    fun aRingClearCopyQueuedBehindAFullWindowIsNotWrittenOnceTheClearIsAcked(): Unit = runBlocking {
        val glass = GlassFirmwareSim()
        val (t, seen) = rig(glass)
        val imageAcks = AtomicInteger(0)
        val held = CopyOnWriteArrayList<Pair<Arm, ByteArray>>()
        val holding = java.util.concurrent.atomic.AtomicBoolean(true)
        val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
        val clearsAfter = AtomicInteger(0)
        // the first image ack is the warmup's; from the second on (the clear's copies) they are held
        t.onNotify = { a, p -> if (isImageAckOnly(p) && imageAcks.incrementAndGet() >= 2 && holding.get()) { held.add(a to p); null } else p }
        t.onWrite = { _, p -> if (delivered.get() && isRingClear(p)) clearsAfter.incrementAndGet(); p }
        val starting = scope.async { runCatching { t.start(full) } }
        waitUntil(15_000, message = { "five re-asks of the clear: ${seen.notes.takeLast(6)}" }) {
            seen.controlNotes("fid-ring clear went unacked").size >= 5
        }
        holding.set(false)
        delivered.set(true)
        for ((a, p) in held.toList()) t.deliver(a, p)
        waitUntil(15_000, message = { "the start did not end: notes ${seen.notes.takeLast(6)}" }) { starting.isCompleted }
        starting.await().exceptionOrNull()?.let { throw AssertionError("the start ended: $it", it) }
        val k = t.submit(FlushRequest(listOf(DisplayOp.Keyframe(full)), 1, wide = true))
        flushed(t, seen, "the keyframe behind the queued copies", k)
        stopped(t, seen)
        assertTrue(clearsAfter.get() <= 1, "after its first ack at most the copy that held the freed slot is written " +
            "(${clearsAfter.get()}; ${held.size} acks held)")
        val reasks = seen.controlNotes("fid-ring clear went unacked")
        assertTrue(reasks.any { "queued" in it }, "a re-ask while the window has no free slot says it was queued: $reasks")
    }

    /** The session's own end during the clear is the start's to report, not the gate's: the link ends with the clear's
     *  ack outstanding; the start ends with the session-ended error and no "not confirmed" note. */
    @Test
    fun aLinkThatEndsDuringTheRingClearEndsTheStartWithNoNote(): Unit = runBlocking {
        val glass = GlassFirmwareSim()
        val (t, seen) = rig(glass)
        val imageAcks = AtomicInteger(0)
        t.onNotify = { _, p ->
            if (isImageAckOnly(p) && imageAcks.incrementAndGet() == 2) {
                scope.launch { t.endLink("test: the link ends with the ring clear un-acked") }
                null
            } else p
        }
        val e = startEnds(t, seen, "a link end during the clear")
        assertTrue("the session ended during the warmup or the fid-ring clear" in (e?.message ?: ""), "the start ends: $e")
        stopped(t, seen)
        assertEquals(emptyList(), seen.controlNotes("not confirmed"), "the session's end is not a clear that failed: ${seen.notes}")
    }

    /** Pin 4: Silent Mode. (a) The start's READ says the glasses are in Silent Mode: no `[7][0]` goes to them (§36 —
     *  nothing of ours beyond the wake path; the session the wake rebuilds sends it). (b) The READ does not carry the
     *  state: the clear is sent and refused, the start completes, and the note says so. */
    @Test
    fun noRingClearGoesToGlassesTheReadSaysAreSilent(): Unit = runBlocking {
        val glass = GlassFirmwareSim()
        glass.setSilent(true, push = false)
        val (t, seen) = rig(glass)
        val clears = AtomicInteger(0)
        t.onWrite = { _, p -> if (isRingClear(p)) clears.incrementAndGet(); p }
        started(t, seen, "Silent Mode, said by the READ")
        assertTrue(t.state.value.glassesSilent, "the start's READ said Silent Mode")
        assertEquals(0, clears.get(), "no ring clear reaches glasses in Silent Mode")
        stopped(t, seen)
        assertEquals(1, seen.controlNotes("fid-ring clear not sent").size, "the start says why: ${seen.notes}")
    }

    @Test
    fun aRingClearRefusedUnderSilentModeIsOneNoteAndTheStartCompletes(): Unit = runBlocking {
        val glass = GlassFirmwareSim()
        glass.reportSilentRestored = false               // this READ does not carry the state
        glass.setSilent(true, push = false)
        val (t, seen) = rig(glass)
        val clears = AtomicInteger(0)
        t.onWrite = { _, p -> if (isRingClear(p)) clears.incrementAndGet(); p }
        started(t, seen, "Silent Mode, not said")        // completes: a refusal does not end the start
        assertFalse(t.state.value.glassesSilent, "nothing told the transport")
        assertEquals(1, clears.get(), "the clear was sent once")
        stopped(t, seen)
        val said = seen.controlNotes("fid-ring clear at the session start was not confirmed")
        assertEquals(1, said.size, "one note: ${seen.notes}")
        assertTrue("ImgResCmd" in said.single(), "it names the refusal: $said")
    }
}
