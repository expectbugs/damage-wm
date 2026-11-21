package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import wm.damage.core.transport.SimTransport
import wm.damage.core.wire.EvenHubMsg

/**
 * C3 stage DW-0 (the DW spec §7.1 "stage 4 unchanged"): below stage 5 the switcher's paint and every byte the shell sends
 * for it are the tree's before DW. Context for the reader: Adam's own glasses on his own firmware fork; nothing here
 * contacts a device — the glasses are the simulator's model behind the sim transport.
 *
 * A shell on a bit-11 build (DamageCaps 0xdff) held at ladder stage 4, Global `Depth` 8, at heights 352 and 480, five
 * windows open: the wheel opened by the chord, a notch down, a notch up, three notches up (the third wraps), the commit,
 * the wheel opened again and cancelled — each act settled before the next. Every image message either lens took from the
 * open on (but the rules' idle SETTLE, whose count rides on how long an act waits) is compared, in order, with
 * `wheelwire/stage4-h<height>.txt`, recorded on the integration head before DW-0 (`b5a7bdfb`) by this test's first run (a
 * missing file is written under `build/wheelwire/` and the test fails), over a paced link so the flushes fall alike at
 * every run (with instant acks they did not: the preview's flush and a spin frame raced).
 */
class WheelStage4Test {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tmp: Path = Files.createTempDirectory("wheel-stage4")
    @AfterTest fun clean() { scope.cancel(); tmp.toFile().deleteRecursively() }

    private class Win(id: String, name: String, icon: IconKind) : DamageWindow(id, name, icon) {
        override fun view(): WindowView = WindowView.ListView(ListModel(), { 0 }, { _, _, _, _ -> }, { _, _, _ -> }, {})
        override fun summary() = Summary("summary of $name", detail = "detail")
        override fun saveState(): JsonObject = JsonObject(emptyMap())
        override fun restoreState(state: JsonObject) {}
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    /** The acts at [height]: each act's label line, then the image messages (lens, hex) it caused. */
    private suspend fun acts(height: Int): List<String> {
        val dir = Files.createTempDirectory(tmp, "s")
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0xDFF }
        val out = java.util.concurrent.CopyOnWriteArrayList<String>()
        val on = java.util.concurrent.atomic.AtomicBoolean(false)
        val bound = java.util.concurrent.atomic.AtomicBoolean(false)
        sim.imageObserver = { a, img ->
            if (subs(img).any { it.size > 1 && it[0].toInt() == 27 && it[1].toInt() == 0 }) bound.set(true)   // a SET: Main's list bound
            // the rules' idle SETTLE naming no list (mode 26 sub 5, list 0xFF) is left out: it goes on a pace after the shell
            // falls idle, so how many fall inside an act depends on how long the act waits (seen moving between runs on the
            // unchanged tree); it is the bound list's, not the switcher's
            val idleSettle = img.size == 10 && img[0].toInt() == 26 && img[1].toInt() == 5 && img[5] == 0xFF.toByte()
            if (on.get() && !idleSettle) out += "${a.name} ${img.hex()}"
        }
        // a paced link (100 ms a message's ack, 400 KB/s): the loop's work between two acks is far shorter than an ack, so the
        // flushes fall the same way at every run (with instant acks the preview's flush and a spin frame raced)
        val t = SimTransport(sim, scope, SimTransport.Timing(ackMs = 100, bytesPerSec = 400_000.0))
        t.devProbe("c1", "4")
        val p = Persistence(dir.resolve("s.json"))
        p.put("shell.settings", ShellSettings(heightMode = height, depth = 8, cachedText = "on").toJson())
        val shell = Shell(CachedText(FakeText()), t, p, null, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
        val kinds = listOf(IconKind.READER, IconKind.TERMINAL, IconKind.FILES, IconKind.MUSIC, IconKind.FEED)
        for (i in 0 until 5) shell.register(Win("w$i", "Window $i", kinds[i]))
        shell.start()
        // settled: quiescent, and on one loop sample no cache work in flight, no count unjudged, the atlas's extensions
        // all live, and no message more for 300 ms (each decided on one evaluation)
        suspend fun settle(what: String) {
            repeat(400) {
                waitUntil(30_000, 10, { "$what: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
                val n0 = out.size
                val rest = shell.sampleIdle { !shell.cacheWorkInFlight && shell.cacheCountUnjudged == 0L && shell.atlasExtensionsLive == shell.atlasExtensionsQueued }
                kotlinx.coroutines.delay(300)
                if (rest == true && shell.isQuiescent() && out.size == n0) return
            }
            fail("$what: the shell never came to rest: ${shell.quiescenceReport()}")
        }
        waitUntil(30_000, 10, { "Main's list bound at stage 4" }) { shell.isQuiescent() && bound.get() }
        for (i in 0 until 5) { shell.services.runOnShell { shell.services.openWindow("w$i", null) }; settle("open w$i") }
        on.set(true)
        suspend fun act(label: String, vararg gestures: Int) {
            out += "# $label"
            for (g in gestures) shell.postGesture(g)
            settle(label)
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
        on.set(false)
        shell.stop()
        return out.toList()
    }

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

    private fun file(name: String): List<String>? = javaClass.getResource("/wheelwire/$name")?.readText()?.lines()?.filter { it.isNotBlank() }

    @Test
    fun theSwitchersBytesBelowStage5AreTheTreesBeforeDw_DW0(): Unit = runBlocking {
        val missing = ArrayList<String>()
        for (h in listOf(352, 480)) {
            val got = acts(h)
            val name = "stage4-h$h.txt"
            val want = file(name) ?: run {
                val o = Paths.get("build", "wheelwire").also { Files.createDirectories(it) }.resolve(name)
                Files.write(o, got)
                missing += "no recording $name: this run's messages written to ${o.toAbsolutePath()}"
                got
            }
            assertTrue(got.count { !it.startsWith("#") } > 20, "$h: the acts sent images (${got.size} lines)")
            if (want != got) {
                val i = want.indices.firstOrNull { it >= got.size || want[it] != got[it] } ?: got.size
                fail("$h: the switcher's messages at stage 4 are not the tree's before DW: first difference at line ${i + 1} of ${want.size} " +
                    "(recorded ${want.getOrNull(i)?.take(120)}, now ${got.getOrNull(i)?.take(120)})")
            }
            assertEquals(want, got)
        }
        if (missing.isNotEmpty()) fail(missing.joinToString("\n"))
    }
}
