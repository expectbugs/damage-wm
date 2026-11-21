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
import wm.damage.core.local.Taps
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.MainStaging
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellSettings
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.Arm
import wm.damage.core.wire.CfwModes

/**
 * Build C2, the start's bind batch at the shell level (`FIRMWARE.md` §6.7 P1; spec §6.2; decisions N.2), with MAIN's real
 * list (stage DI): a shell on a bit-10 build at stage 4 sends, in stream order after its first keyframe, the start's batch
 * — CLEAR 0xFF and the idle batch's SETTLE naming no list (Main's records are not on the glasses yet: a fresh acquire
 * holds no cache) — and, once Main's staging is written and counted and the list offered, the batch that binds it: the
 * list's SETs by `Taps` (no tap entry under `phone`, the report-only entry under `report`, the press under `glasses`), its
 * LIST with a fresh mark, and the SETTLE naming it. One file per `Taps` value (`bind-start-c2-{phone,report,glasses}.txt`:
 * the start batch's tail, then the binding batch's), written by this test's first run and read by hand once
 * (`C2-DI-built.md`). And a `Taps` switch mid-session sends only the tap entry's SET or CLEAR.
 */
class LocalStartTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tmp: Path = Files.createTempDirectory("local-start")
    @AfterTest fun clean() { scope.cancel(); tmp.toFile().deleteRecursively() }

    private class Row(id: String, name: String, val s: DamageWindow.Summary) : DamageWindow(id, name, IconKind.FILES) {
        override fun view(): WindowView = WindowView.ListView(ListModel(), { 0 }, { _, _, _, _ -> }, { _, _, _ -> }, {})
        override fun summary() = s
        override fun saveState(): JsonObject = JsonObject(emptyMap())
        override fun restoreState(state: JsonObject) {}
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private class Run(val shell: Shell, val images: List<String>)

    private suspend fun start(taps: Taps, features: Int = 0x5FF, stage: Int = 4, height: Int = 352, windows: Int = 5): Run {
        val dir = Files.createTempDirectory(tmp, "s")
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = features }
        val images = java.util.concurrent.CopyOnWriteArrayList<String>()
        sim.imageObserver = { a, img -> if (a == Arm.RIGHT) images += img.hex() }
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        t.devProbe("c1", "$stage")
        val p = Persistence(dir.resolve("s.json"))
        p.put("shell.settings", ShellSettings(taps = taps, heightMode = height, cachedText = "on").toJson())
        val shell = Shell(CachedText(FakeText()), t, p, null, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
        for (i in 0 until windows) shell.register(Row("w$i", "Win $i", DamageWindow.Summary("summary $i", detail = "detail of $i")))
        shell.start()
        waitUntil(30_000, 10, { "Main's list bound: ${images.size} images" }) { shell.isQuiescent() && images.any { binds(unhex(it)) } }
        return Run(shell, images)
    }

    /** A batch that SETs an entry (mode 27 sub 0). */
    private fun binds(img: ByteArray) = subs(img).any { it[0].toInt() == 27 && it[1].toInt() == 0 }

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
            val inStart = subs(unhex(imgs[at]))
            val first27 = inStart.indexOfFirst { it[0].toInt() == 27 }
            assertTrue(kf in 0 until at || (kf == at && inStart.take(first27).any { it[0].toInt() == 6 }),
                "$taps: the start's batch in stream order after the first keyframe (keyframe $kf, bind $at)")
            val bind = imgs.indexOfFirst { binds(unhex(it)) }
            assertTrue(bind > at, "$taps: Main's list is bound after the start's batch (start $at, bind $bind)")
            val inBind = subs(unhex(imgs[bind]))
            // the tails as they ride (every sub from the first mode 27 on), one sub per line: the drawing before them is the
            // start pins' and the renderer's, not this file's
            val got = inStart.drop(first27).map { it.hex() } + inBind.drop(inBind.indexOfFirst { it[0].toInt() == 27 }).map { it.hex() }
            val name = "bind-start-c2-${taps.word}.txt"
            val want = file(name) ?: run {
                val out = Paths.get("build", "startwire").also { Files.createDirectories(it) }.resolve(name)
                Files.write(out, got)
                missing += "no expectation for $name: this run's batches written to ${out.toAbsolutePath()}"
                got
            }
            assertEquals(want, got, "$taps: the start's batch and the binding batch against $name")
            assertEquals(1, imgs.count { subs(unhex(it)).any { s -> s[0].toInt() == 27 && s[1].toInt() == 1 && s[2] == 0xFF.toByte() } },
                "$taps: one CLEAR 0xFF in the session")
        }
        if (missing.isNotEmpty()) fail(missing.joinToString("\n"))
    }

    /**
     * C3 DM-0 (the DM spec §2, decisions AD's S2): on a bit-11 build at stage 5, Main's eight rows at 352 — its paint wraps
     * (it shows seven) — bind as a ring list: the binding batch carries LIST-RING with flags 1 and no plain LIST, the
     * staging is the ring form (margins of k + 1 rows, no wrap record). At 416 (all eight shown) the same build binds a
     * plain LIST with today's staging; at stage 4 the same rows bind today's plain LIST and wrap record — no C3 form
     * anywhere below stage 5.
     */
    @Test
    fun theStage5StartBindsMainAsARingListWhereItsPaintWraps_DM0(): Unit = runBlocking {
        for ((stage, height) in listOf(5 to 352, 5 to 416, 4 to 352)) {
            val r = start(Taps.REPORT, features = 0xDFF, stage = stage, height = height, windows = 7)
            val imgs = r.images.toList()
            val ms = r.shell.mainBinding.staging
            r.shell.stop()
            val what = "stage $stage at $height"
            val lists = imgs.flatMap { subs(unhex(it)) }.filter { it[0].toInt() == 27 && (it[1].toInt() == 2 || it[1].toInt() == CfwModes.BIND_LIST_RING) }
            val inBind = subs(unhex(imgs[imgs.indexOfFirst { binds(unhex(it)) }]))
            val bindList = inBind.firstOrNull { it[0].toInt() == 27 && (it[1].toInt() == 2 || it[1].toInt() == CfwModes.BIND_LIST_RING) }
            assertTrue(bindList != null && ms != null, "$what: Main's list bound with its LIST")
            val ring = stage == 5 && height == 352
            assertEquals(ring, ms!!.ring, "$what: the staging's form")
            assertEquals(!ring && height == 352, ms.wrap != null, "$what: a wrap record exactly where the form is today's and Main wraps")
            if (ring) {
                assertTrue(bindList!![1].toInt() == CfwModes.BIND_LIST_RING && bindList.size == 13 && bindList[12].toInt() == 1, "$what: the binding batch's LIST is LIST-RING flags 1")
                assertTrue(lists.all { it[1].toInt() == CfwModes.BIND_LIST_RING }, "$what: no plain LIST in the session (a plain LIST clears RING)")
            } else {
                assertTrue(lists.all { it[1].toInt() == 2 }, "$what: plain LISTs only")
                assertTrue(imgs.none { CfwModes.carriesEffects(unhex(it)) } || stage == 5, "$what: no C3 form below stage 5")
            }
        }
    }

    @Test
    fun aTapsSwitchSendsOnlyTheTapEntrysSetOrClear(): Unit = runBlocking {
        val r = start(Taps.REPORT)
        val before = r.images.size
        r.shell.updateSettings { it.copy(taps = Taps.PHONE) }
        waitUntil(10_000, 10, { "the switch's batch" }) { r.images.drop(before).any { CfwModes.carriesBind(unhex(it)) } }
        val b = unhex(r.images.drop(before).first { CfwModes.carriesBind(unhex(it)) })
        val t27 = subs(b).filter { it[0].toInt() == 27 && it[1].toInt() in 0..1 }    // the idle SETTLE's riding LIST aside (U.1)
        assertEquals(1, t27.size, "one SET or CLEAR")
        assertTrue(t27[0].contentEquals(CfwModes.bindClear(MainStaging.ENTRY_TAP)), "the tap entry's CLEAR")
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
