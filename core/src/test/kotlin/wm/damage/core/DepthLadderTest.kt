package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.IconKind
import wm.damage.core.gfx.Level
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellSettings
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.transport.SimTransport

/**
 * `DESIGN.md` §3.1 as Adam re-ruled it on glass (2026-09-06, `HANDOFF.md`
 * §41): the Global `Depth` moves EVERYTHING — both bars, Main, and every
 * app's content unless that app's own row says otherwise — and the selection
 * bar sits one notch (4 px) nearer than the plane it selects on, never nearer
 * than the screen plane. The per-app row moves only that app's content; the
 * bars stay on the Global row. Before: the bars rode one step behind content
 * capped at 16 and app content parked at 8 whatever the row said, so 8, 12
 * and 16 moved only the bars and 16 changed nothing at all.
 */
class DepthLadderTest {

    private class RowsWindow(private val tx: TextRasterizer) : DamageWindow("rows", "Rows", IconKind.FILES) {
        private val model = ListModel()
        private val f = FontSpec(Face.SYSTEM, 18)
        override fun view(): WindowView = WindowView.ListView(model, { 30 },
            paintRow = { g, i, r, _ -> tx.draw(g, r.x + 40, r.y + 5, "Row $i", f, Level.BODY) },
            paintLens = { g, r, i -> tx.draw(g, r.x + 44, r.y + 8, "Row $i", f, Level.HEAD) },
            onCommit = {})
        override fun summary() = Summary("30 rows")
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) {}
    }

    private class Rig(scope: CoroutineScope) {
        val tmp = Files.createTempDirectory("damage-depth")
        val text = GlyphyText()
        val shell = Shell(text, SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true)),
            Persistence(tmp.resolve("state.json")), null, scope)
        init { shell.register(RowsWindow(text)) }

        suspend fun settle(what: String) {
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 10_000) {
                if (shell.isQuiescent()) return
                delay(10)
            }
            throw AssertionError("$what: did not settle — ${shell.quiescenceReport()}")
        }

        /** The disparity of the region whose rect is exactly [r], or null when
         *  no region claims it (the remainder — the screen plane). */
        fun planeOf(r: Rect): Int? = shell.comp.planes.lastOrNull { it.rect == r }?.disparity

        fun topBar(): Rect = shell.layout.let { Rect(it.topBar.x, it.topBar.y, it.topBar.w, Layout.TOP_H + Layout.DIV_H) }
        fun statusBar(): Rect = shell.layout.let { Rect(it.statusBar.x, it.bottomDivider.y, it.statusBar.w, Layout.DIV_H + Layout.STATUS_H) }

        fun assertLadder(where: String, chrome: Int, content: Int) {
            val expectChrome = chrome.takeIf { it != 0 }
            val expectContent = content.takeIf { it != 0 }
            val lens = maxOf(0, content - 4)
            val expectLens = lens.takeIf { it != content }
            assertEquals(expectChrome, planeOf(topBar()), "$where: the top bar's plane")
            assertEquals(expectChrome, planeOf(statusBar()), "$where: the status bar's plane")
            assertEquals(expectContent, planeOf(shell.layout.content), "$where: the content plane")
            assertEquals(expectLens, planeOf(shell.layout.lens), "$where: the lens one notch nearer than its content")
        }
    }

    @Test
    fun theGlobalDepthMovesEverythingAndTheSelectionSitsOneNotchNearer(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = Rig(scope)
            rig.shell.start(); rig.settle("start")
            for (d in ShellSettings.DEPTHS) {
                rig.shell.updateSettings { it.copy(depth = d) }; rig.settle("depth $d")
                rig.assertLadder("Main at depth $d", chrome = d, content = d)
            }
            // an app whose row follows the Global setting (the default) moves with it
            rig.shell.services.runOnShell { rig.shell.services.openWindow("rows", null) }
            rig.settle("open rows")
            assertEquals("rows", rig.shell.currentWindowId())
            for (d in ShellSettings.DEPTHS) {
                rig.shell.updateSettings { it.copy(depth = d) }; rig.settle("rows at depth $d")
                rig.assertLadder("Rows following the Global row at $d", chrome = d, content = d)
            }
            // the app's own row moves only its content; the bars stay on the Global row
            rig.shell.updateSettings { s -> s.copy(depth = 16).withAppStyle("rows") { it.copy(depth = 8) } }
            rig.settle("rows at 8 under 16")
            rig.assertLadder("Rows at its own 8 under a Global 16", chrome = 16, content = 8)
            rig.shell.updateSettings { s -> s.copy(depth = 0) }
            rig.settle("rows at 8 under 0")
            rig.assertLadder("Rows at its own 8 under a Global 0", chrome = 0, content = 8)
            rig.shell.updateSettings { s -> s.copy(depth = 12).withAppStyle("rows") { it.copy(depth = ShellSettings.GLOBAL_DEPTH) } }
            rig.settle("rows back to global under 12")
            rig.assertLadder("Rows back on the Global row at 12", chrome = 12, content = 12)
            rig.shell.stop()
        } finally {
            scope.cancel()
        }
    }
}
