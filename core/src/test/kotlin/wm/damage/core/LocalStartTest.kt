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
import wm.damage.core.local.BoundList
import wm.damage.core.local.BoundSurface
import wm.damage.core.local.Taps
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellSettings
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.SimTransport
import wm.damage.core.wire.CfwModes

/**
 * Build C2, the start's bind batch at the shell level (`FIRMWARE.md` §6.7 P1; spec §6.2; decisions N.2): a shell on a
 * bit-10 build at stage 4, with a test [BoundList] in front, sends — in stream order after its first keyframe — one batch
 * of CLEAR 0xFF, the list's SETs by `Taps` (no tap entry under `phone`, the report-only entry under `report`, the press
 * under `glasses`), its LIST with a fresh mark, and the idle batch's SETTLE. One file per `Taps` value
 * (`bind-start-c2-{phone,report,glasses}.txt`), written by this test's first run and read by hand once against stage 3's
 * start (`C2-DP2-built.md`). And a `Taps` switch mid-session sends only the tap entry's SET or CLEAR.
 */
class LocalStartTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tmp: Path = Files.createTempDirectory("local-start")
    @AfterTest fun clean() { scope.cancel(); tmp.toFile().deleteRecursively() }

    private class Surface(val l: BoundList) : BoundSurface {
        var r = 3
        override fun bound() = l
        override fun row() = r
        override fun setRow(row: Int) { r = row }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private class Run(val shell: Shell, val images: List<String>)

    private suspend fun start(taps: Taps): Run {
        val dir = Files.createTempDirectory(tmp, "s")
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x5FF }
        val images = java.util.concurrent.CopyOnWriteArrayList<String>()
        // the test list's records, programs and item table go into each lens's cache before the shell's first image (the
        // staging owner writes them first, P9), so the start's SETs and LIST are taken
        val seeded = HashSet<Arm>()
        sim.imageObserver = { a, img ->
            if (seeded.add(a)) for (w in cacheWrites()) sim.conformanceMessage(a, w, System.currentTimeMillis())
            else if (a == Arm.RIGHT) images += img.hex()
        }
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        t.devProbe("c1", "4")
        val p = Persistence(dir.resolve("s.json"))
        p.put("shell.settings", ShellSettings(taps = taps, cachedText = "off").toJson())
        val shell = Shell(wm.damage.core.comp.CachedText(GlyphyText()), t, p, null, scope)
        val surface = Surface(LocalRulesTest.list())
        shell.boundSurface = { surface }
        shell.start()
        waitUntil(10_000, 10, { "start" }) { shell.isQuiescent() && images.any { CfwModes.carriesBind(unhex(it)) } }
        return Run(shell, images)
    }

    private fun cacheWrites(): List<ByteArray> {
        fun w19(off4: Int, b: ByteArray) = byteArrayOf(19, (off4 and 0xFF).toByte(), (off4 shr 8).toByte(), (b.size and 0xFF).toByte(), (b.size shr 8).toByte()) + b
        val l = LocalRulesTest.list()
        return LocalRulesTest.recs.mapIndexed { i, o -> w19(o, wm.damage.core.wire.TextureCache.Image2(32, 8, ByteArray(256) { (2 + i).toByte() }).encode()) } +
            listOf(w19(LocalRulesTest.scroll.at, LocalRulesTest.scroll.bytes), w19(LocalRulesTest.press.at, LocalRulesTest.press.bytes), w19(l.items4, l.itemTable()!!))
    }

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun file(name: String): List<String>? = javaClass.getResource("/startwire/$name")?.readText()?.lines()?.filter { it.isNotBlank() }

    @Test
    fun theStartsBindBatchPerTapsValue_P1(): Unit = runBlocking {
        val missing = ArrayList<String>()
        for (taps in Taps.entries) {
            val r = start(taps)
            val imgs = r.images.toList()
            r.shell.stop()
            val at = imgs.indexOfFirst { CfwModes.carriesBind(unhex(it)) }
            val kf = imgs.indexOfFirst { it.startsWith("06") || (it.startsWith("08") && it.substring(8, 10) == "06") }
            val inKf = subs(unhex(imgs[at]))
            val first27 = inKf.indexOfFirst { it[0].toInt() == 27 }
            assertTrue(kf in 0 until at || (kf == at && inKf.take(first27).any { it[0].toInt() == 6 }),
                "$taps: the bind batch in stream order after the first keyframe (keyframe $kf, bind $at)")
            // the tail as it rides (every sub from the first mode 27 on), one sub per line: the keyframe's own bytes are
            // the start pins' and the renderer's, not this file's
            val got = inKf.drop(first27).map { it.hex() }
            val name = "bind-start-c2-${taps.word}.txt"
            val want = file(name) ?: run {
                val out = Paths.get("build", "startwire").also { Files.createDirectories(it) }.resolve(name)
                Files.write(out, got)
                missing += "no expectation for $name: this run's batch written to ${out.toAbsolutePath()}"
                got
            }
            assertEquals(want, got, "$taps: the start's bind batch against $name")
            assertEquals(1, imgs.count { CfwModes.carriesBind(unhex(it)) && unhex(it).let { b -> subs(b).any { s -> s[0].toInt() == 27 && s[1].toInt() == 1 && s[2].toInt() == 0xFF.toByte().toInt() } } },
                "$taps: one CLEAR 0xFF in the session")
        }
        if (missing.isNotEmpty()) fail(missing.joinToString("\n"))
    }

    @Test
    fun aTapsSwitchSendsOnlyTheTapEntrysSetOrClear(): Unit = runBlocking {
        val r = start(Taps.REPORT)
        val before = r.images.size
        r.shell.updateSettings { it.copy(taps = Taps.PHONE) }
        waitUntil(10_000, 10, { "the switch's batch" }) { r.images.drop(before).any { CfwModes.carriesBind(unhex(it)) } }
        val b = unhex(r.images.drop(before).first { CfwModes.carriesBind(unhex(it)) })
        val t27 = subs(b).filter { it[0].toInt() == 27 }
        assertEquals(1, t27.size, "one mode-27 message")
        assertTrue(t27[0].contentEquals(CfwModes.bindClear(2)), "the tap entry's CLEAR")
        r.shell.stop()
    }

    private fun subs(img: ByteArray): List<ByteArray> {
        if (img[0].toInt() != 8) return listOf(img)
        val out = ArrayList<ByteArray>()
        var i = 2
        repeat(img[1].toInt() and 0xFF) {
            val n = (img[i].toInt() and 0xFF) or ((img[i + 1].toInt() and 0xFF) shl 8)
            out += img.copyOfRange(i + 2, i + 2 + n); i += 2 + n
        }
        return out
    }
}
