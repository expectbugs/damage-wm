package wm.damage.desktop

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.comp.CachedText
import wm.damage.core.geom.Geometry
import wm.damage.core.shell.Notifications
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.DisplayOp
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.Transport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.EvenHubMsg

/**
 * Build NT's look and price (`DESIGN.md` §4.5, Adam 2026-11-19): the notice box's focus timer through the REAL
 * rasterizer, the shell and the glasses' model (contract 2), its steps on the harness's clock. For a one-, two- and
 * three-line box, with Cached text on and off: the bytes of the arrival, of each of the eight steps (the eighth is the
 * focus step with the box's step forward in depth) and of an input's reset. With `NT_RENDERS=<dir>` in the environment
 * the left lens is written at true 1× for the arrival, steps 1–7 and the focused box of the one- and three-line boxes
 * (Cached text on), the snapshot judge's colours; without it the pictures go to a temporary directory that is removed.
 *
 *     NT_RENDERS=<dir> ./gradlew :desktop:test --tests wm.damage.desktop.NoticeTimerRenderTest
 */
class NoticeTimerRenderTest {

    /** Every flush the shell submits, with its id, so the transport's answer prices it. */
    private class Spy(private val inner: Transport) : Transport by inner {
        val submitted = java.util.concurrent.ConcurrentHashMap<Long, FlushRequest>()
        override suspend fun submit(flush: FlushRequest): Long = inner.submit(flush).also { submitted[it] = flush }
    }

    private class Priced(val label: String, val bytes: Int, val atlas: Boolean, val shape: String)

    private companion object {
        fun shape(f: FlushRequest?): String = f?.ops?.joinToString(" ") {
            when (it) {
                is DisplayOp.Delta -> "D${it.box}(${it.payload.size},d${it.disparity})"
                is DisplayOp.StereoPair -> "SP(${it.payload.size})"
                is DisplayOp.DrawText -> "T(${it.text.size})"
                is DisplayOp.DrawText2 -> "T2(${it.text.size})"
                is DisplayOp.Fill -> "F"
                is DisplayOp.PresentHint -> "H${it.y0}-${it.y1}"
                is DisplayOp.CacheWrite, is DisplayOp.CacheWrite2 -> "CW"
                else -> it::class.simpleName ?: "?"
            }
        } ?: "?"
    }

    @Test
    fun theTimersStepsRenderedAndPriced(): Unit = runBlocking {
        val env = System.getenv("NT_RENDERS")
        val out = env?.let { Path.of(it).also { p -> Files.createDirectories(p) } } ?: Files.createTempDirectory("nt-renders")
        val bodies = listOf(
            1 to "on my way",
            2 to "on my way, be there in twenty minutes",
            3 to "running late, the bridge is closed so I am taking the long way round",
        )
        val report = StringBuilder()
        try {
            for (cached in listOf(true, false)) for ((lines, body) in bodies) {
                report.append(run(cached, lines, body, out, pictures = cached && lines != 2))
            }
        } finally {
            println(report)
            if (env == null) out.toFile().deleteRecursively()
        }
    }

    private suspend fun run(cached: Boolean, lines: Int, body: String, out: Path, pictures: Boolean): String {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp = Files.createTempDirectory("nt-render")
        val sim = GlassFirmwareSim().also { it.damageContract = 2 }
        val spy = Spy(SimTransport(sim, scope, SimTransport.Timing(instant = true)))
        val tag = "${lines}line"
        val journal = out.resolve("journal-$tag-${if (cached) "cached" else "pixels"}.jsonl").also { Files.deleteIfExists(it) }
        val shell = Shell(CachedText(AwtText()), spy, Persistence(tmp.resolve("state.json")), journal, scope) {
            Shell.LocalClock(14, 32, "2:32", "PM")
        }
        shell.graceByHand = true
        val done = java.util.concurrent.ConcurrentLinkedQueue<TransportEvent.FlushDone>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            spy.events.collect { if (it is TransportEvent.FlushDone) done.add(it) }
        }
        suspend fun settle(what: String) {
            val t0 = System.currentTimeMillis()
            while (!shell.isQuiescent()) {
                if (System.currentTimeMillis() - t0 > 20_000) throw AssertionError("$what: did not settle — ${shell.quiescenceReport()}")
                delay(5)
            }
        }
        /** Settled, and the atlas owes nothing (its glyph gains are cache writes of their own). */
        suspend fun calm(what: String) {
            repeat(200) {
                settle(what)
                val a = done.size
                delay(30)
                settle(what)
                if (done.size == a && !shell.cacheWorkInFlight && shell.cacheCountUnjudged == 0L &&
                    shell.atlasExtensionsLive == shell.atlasExtensionsQueued) return
            }
            throw AssertionError("$what: never came to rest — ${shell.quiescenceReport()}")
        }
        /** What [act] sent, priced by the transport's answers. */
        suspend fun priced(what: String, act: suspend () -> Unit): List<Priced> {
            val before = done.map { it.id }.toSet()
            act(); calm(what)
            return done.filter { it.id !in before }.map { d ->
                val f = spy.submitted[d.id]
                assertTrue(d.ok, "$what: flush ${d.id} failed: ${d.error}")
                Priced(f?.label ?: "?", d.bytes, f != null && f.ops.isNotEmpty() &&
                    f.ops.all { it is DisplayOp.CacheWrite || it is DisplayOp.CacheWrite2 }, shape(f))
            }
        }
        fun say(p: List<Priced>): String {
            val draws = p.filter { !it.atlas }
            val atlas = p.filter { it.atlas }
            return draws.joinToString("+") { "${it.bytes}" }.ifEmpty { "0" } +
                (if (atlas.isNotEmpty()) " (atlas ${atlas.sumOf { it.bytes }} B)" else "")
        }
        suspend fun <T : Any> idle(what: String, block: () -> T): T {
            repeat(500) { settle(what); shell.sampleIdle(block)?.let { return it } }
            throw AssertionError("$what: never idle — ${shell.quiescenceReport()}")
        }
        suspend fun picture(name: String) {
            // copied ON the loop with nothing pending (`Snapshot.save`'s rule): an idle tick between the settle and
            // the copy answers "not idle", and the next try takes it
            var pan: ByteArray? = null
            repeat(500) { if (pan == null) { settle(name); pan = shell.sampleIdle { sim.left.panel.copyOf() } } }
            val panel = pan ?: error("$name: the shell never sat idle for the picture — ${shell.quiescenceReport()}")
            val img = BufferedImage(Geometry.PANEL_W, Geometry.PANEL_H, BufferedImage.TYPE_INT_RGB)
            val stride = sim.left.stride
            for (y in 0 until Geometry.PANEL_H) for (x in 0 until Geometry.PANEL_W) {
                val b = panel[y * stride + (x shr 1)].toInt() and 0xFF
                val v = (if (x and 1 == 0) b shr 4 else b and 0x0F) * 17
                img.setRGB(x, y, ((v * 0.16).toInt() shl 16) or (minOf(255, (v * 1.05).toInt()) shl 8) or (v * 0.34).toInt())
            }
            ImageIO.write(img, "png", out.resolve("$name.png").toFile())
        }
        val sb = StringBuilder()
        try {
            shell.start(); calm("start")
            if (cached) {
                shell.updateSettings { it.copy(cachedText = "on") }
                val t0 = System.currentTimeMillis()
                while (!(shell.cachedTextActive && shell.cachedFontsLive.isNotEmpty())) {
                    if (System.currentTimeMillis() - t0 > 20_000) throw AssertionError("the atlas never came up")
                    delay(5)
                }
                calm("atlas up")
            }
            fun notice() = Notifications.Notice("SMS · MOM", "mom", body, "2:32")
            // a first showing, to the end, puts the box's characters into the atlas (a face gains what it draws); the
            // second showing is the one measured
            shell.postNotice(notice()); calm("warm: arrived")
            repeat(Notifications.GRACE_STEPS) { shell.graceStepByHand(); calm("warm: step") }
            shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK); calm("warm: dismissed")
            val n = notice()
            val wrapped = idle("wrap") { shell.notifications.bodyLines(n, shell.layout) }
            assertEquals(lines, wrapped.size, "the $tag body wraps to $lines line(s): $wrapped")
            val arrival = priced("arrival") { shell.postNotice(n) }
            if (pictures) picture("nt-$tag-s0")
            val steps = ArrayList<List<Priced>>()
            for (s in 1..Notifications.GRACE_STEPS) {
                steps.add(priced("step $s") { shell.graceStepByHand() })
                val (step, owed) = idle("step $s") { shell.graceState() }
                assertEquals(s to (s < Notifications.GRACE_STEPS), step to owed, "$tag step $s")
                if (pictures) picture(if (s == Notifications.GRACE_STEPS) "nt-$tag-focused" else "nt-$tag-s$s")
            }
            assertTrue(shell.notifications.focused, "$tag: focused at the eighth step")
            // the reset: a second box of another thread is shown unfocused only after this one goes; the same box is
            // shown again (a dismissal, then the next notice of its thread) to five steps and then given a bare release
            shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK); calm("dismissed")
            shell.postNotice(notice()); calm("again")
            repeat(5) { shell.graceStepByHand(); calm("again: step") }
            val reset = priced("reset") { shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS_RELEASE, 0) }
            assertEquals(0 to true, idle("reset") { shell.graceState() }, "$tag: the release reset the timer")
            sb.append("NT bytes, ${if (cached) "Cached text on" else "pixels"}, $tag box: arrival ${say(arrival)}; ")
            sb.append("steps 1-7 ${steps.take(7).joinToString(" ") { say(it) }}; focus ${say(steps.last())}; ")
            sb.append("the input's reset at step 5 ${say(reset)}\n")
            sb.append("  shapes: step 1 ${steps[0].joinToString { it.shape }} | step 2 ${steps[1].joinToString { it.shape }} | " +
                "focus ${steps.last().joinToString { it.shape }} | reset ${reset.joinToString { it.shape }}\n")
        } finally {
            try { shell.stop() } finally { scope.cancel(); tmp.toFile().deleteRecursively() }
        }
        return sb.toString()
    }
}
