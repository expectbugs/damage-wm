package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.wire.AaFrame
import wm.damage.core.wire.EvenHubMsg

/** An IMAGE ack exactly: an AA response on the EvenHub sid with the ack flag whose body's field 1 is the image Cmd + 1
 *  (`EvenHubMsg.parseAck`: "f1 = request Cmd + 1") — a control message's ack on the same sid passes. */
internal fun isImageAckOnly(p: ByteArray): Boolean =
    p.size > AaFrame.HEADER + 1 && (p[1].toInt() and 0xFF) == AaFrame.TYPE_RESPONSE &&
        (p[6].toInt() and 0xFF) == EvenHubMsg.SID && (p[7].toInt() and 0xFF) == EvenHubMsg.FLAG_ACK &&
        p[AaFrame.HEADER].toInt() == 0x08 && p[AaFrame.HEADER + 1].toInt() == EvenHubMsg.CMD_IMAGE + 1

/** A non-input repaint: [n] paints a small band of the content in its own grey and damages it, on the loop — the pump
 *  that follows is the message's, not a ring event's. Returns once the message has been handled. */
internal suspend fun nonInputRepaint(shell: Shell, n: Int) {
    val done = kotlinx.coroutines.CompletableDeferred<Unit>()
    shell.services.runOnShell {
        val c = shell.layout.content
        val r = wm.damage.core.geom.Rect(c.x + 8, c.y + 8 + n * 24, 96, 16)
        shell.comp.composed.fillRect(r, ((n % 12) + 3) * 17)
        shell.comp.damage(r)
        done.complete(Unit)
    }
    done.await()
}

/**
 * APK 0.65, P5 item 5 (the wait-no-escape sweep's finding 2, `scratchpad/sweeps/wait-no-escape-verified.md`): one
 * slot of the image window is kept for the response to input, so every pump that does not follow a ring event
 * stops one short of the window. With two image messages un-acked and nothing acked after them, that pump never
 * has room — the idle tick, a notice, a window's push all return at the room check — and the two can be released
 * only by a later image ack, whose only source is that pump: frames stop for everything but input, with no word,
 * until the msgId counter comes round (minutes on a paced link) or the link ends.
 */
class ReserveYieldTest {
    private val tmp: Path = Files.createTempDirectory("reserve-yield")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest fun clean() { scope.cancel(); tmp.toFile().deleteRecursively() }

    private fun lines(journal: Path): List<String> = if (Files.exists(journal)) Files.readAllLines(journal) else emptyList()
    private fun submits(journal: Path) = lines(journal).count { "\"ev\":\"submit\"" in it }

    /**
     * The content case: two non-input repaints go out and their acks are lost; a third meets the reserve. Fixed: two
     * idle ticks in a row with no completion between them, and that tick's pump takes the whole window (one `link`
     * note); its ack releases the two earlier ones ("acked first"). Unfixed: nothing goes out until the counter
     * comes round — on this paced link, not inside the wait. Then the yield is shown to be that tick's pump's only.
     */
    @Test
    fun theReserveYieldsAfterTwoIdleTicksWithNoCompletion(): Unit = runBlocking {
        val journal = tmp.resolve("journal.jsonl")
        val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(ackMs = 5, bytesPerSec = 5_000_000.0, instant = false))
        val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state.json")), journal, scope)
        shell.start()
        waitUntil(30_000, message = { "the session settles: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
        val drop = AtomicInteger(2)
        transport.notifyFilter = { _, p -> !(isImageAckOnly(p) && drop.getAndUpdate { maxOf(0, it - 1) } > 0) }
        for (n in 1..2) {
            nonInputRepaint(shell, n)
            waitUntil(10_000, message = { "repaint $n is on the wire, its ack lost: inFlight=${transport.state.value.inFlight} dropped ${2 - drop.get()}" }) {
                transport.state.value.inFlight == n && drop.get() == 2 - n
            }
        }
        val held = submits(journal)
        nonInputRepaint(shell, 3)
        assertTrue("pending" in shell.quiescenceReport() && submits(journal) == held,
            "the third repaint is held by the reserve: ${shell.quiescenceReport()} submits $held then ${submits(journal)}")
        shell.postIdleTick(); shell.postIdleTick()
        waitUntil(10_000, message = { "a frame went out past the reserve: submits $held then ${submits(journal)}; " +
            "inFlight=${transport.state.value.inFlight} ${shell.quiescenceReport()}" }) { submits(journal) > held }
        waitUntil(10_000, message = { "the two un-acked repaints are released by the later ack: ${lines(journal).filter { "\"ok\":false" in it }}" }) {
            lines(journal).count { "\"ev\":\"done\"" in it && "acked first" in it } >= 2
        }
        val all = lines(journal)
        assertTrue(all.none { "counter cycled" in it }, "released by the counter, not by a later ack")
        assertTrue(all.any { "\"kind\":\"link\"" in it && "reserve" in it }, "the yield is said: ${all.filter { "\"kind\":\"link\"" in it }}")
        waitUntil(30_000, message = { "the session settles after the repair: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
        // the yield was that tick's pump's only: two more acks lost, and the reserve holds again
        drop.set(2)
        for (n in 1..2) {
            nonInputRepaint(shell, n + 3)
            waitUntil(10_000, message = { "repaint ${n + 3} is on the wire, its ack lost: inFlight=${transport.state.value.inFlight}" }) {
                transport.state.value.inFlight == n && drop.get() == 2 - n
            }
        }
        val again = submits(journal)
        nonInputRepaint(shell, 6)
        assertTrue("pending" in shell.quiescenceReport() && submits(journal) == again,
            "the reserve holds again after a yield: ${shell.quiescenceReport()} submits $again then ${submits(journal)}")
        transport.notifyFilter = null
        shell.stop()
    }
}
