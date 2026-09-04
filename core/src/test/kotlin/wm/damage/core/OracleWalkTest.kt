package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.comp.Compositor
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.shell.KeyboardSurface
import wm.damage.core.shell.MenuSurface
import wm.damage.core.shell.Notifications
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellServices
import wm.damage.core.shell.ShellSettings
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.EvenHubMsg
import wm.damage.core.windows.games.GamesWindow

/**
 * The RANDOM-GESTURE ORACLE WALK (review 2026-09-05).
 *
 * `LensOracleTest` proves the compositor against hand-built plane maps;
 * `DivergenceTest` proves one forced disagreement. Neither drives the SHELL:
 * every plane map the glasses actually see is produced by real surfaces — the
 * wheel, a context menu, the keyboard, exclusive mode, a notification stepping
 * forward, a window's own `contentPlanes` — in an order no scripted walk
 * enumerates. The 2026-09-03 review found its worst finding exactly this way
 * and the walk was never committed; this is it.
 *
 * A seeded random walk of the §1 grammar over a real shell, at every one of
 * the four heights, asserting after each settle that
 *
 *  1. the compositor's BELIEF equals the firmware model's panel (what the
 *     shell's own mirror check compares), and
 *  2. the belief equals the INDEPENDENT per-lens truth of `comp.composed`
 *     under `comp.planes` — recomputed here without reference to the
 *     compositor's own code, splitting the panel by plane PIECES (the §25
 *     note: raw plane rects report false pixels at the lens edges).
 *
 * (2) is what catches a defect that writes wrong pixels into the shadow AND
 * sends them: belief and glass then agree, and only truth disagrees.
 */
class OracleWalkTest {

    private class Rig(height: Int, seed: Long) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp: java.nio.file.Path = Files.createTempDirectory("damage-walk")
        val store = Persistence(tmp.resolve("state.json"))
        val sim = GlassFirmwareSim()
        val transport = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        val clock = Shell.LocalClock(12, 0, "12:00", "PM")
        val shell = Shell(FakeText(), transport, store, null, scope) { clock }
        val rnd = java.util.Random(seed)
        val flushFails = ArrayList<String>()
        val faults = ArrayList<String>()

        init {
            store.put("shell.settings", ShellSettings(heightMode = height).toJson())
            // Games is the one real window core can build with no host at all
            shell.register(GamesWindow(FakeText(), scope) { 1_757_000_000_000L })
            shell.register(SurfaceWindow())
            shell.register(WalkDocWindow())
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                transport.events.collect { e ->
                    when (e) {
                        is TransportEvent.FlushDone ->
                            if (!e.ok) synchronized(flushFails) { flushFails.add("${e.id}: ${e.error}") }
                        is TransportEvent.Fault ->
                            if (e.what != "warmup") synchronized(faults) { faults.add("${e.what}: ${e.detail}") }
                        else -> {}
                    }
                }
            }
        }

        /** Generous on purpose: a scroll while the Hold'em bots are acting sets
         *  §10.1's SKIP, and the table then plays a whole hand at
         *  [wm.damage.core.windows.games.holdem.Equity.LIVE_ROLLOUTS] with no
         *  pacing — real work that keeps a message on the loop the whole time.
         *  It is progress, not a hang; the bound is only here so a genuine
         *  hang fails loudly instead of parking the suite. */
        suspend fun settle(what: String) {
            val t0 = System.currentTimeMillis()
            while (!shell.isQuiescent() && System.currentTimeMillis() - t0 < 120_000) delay(5)
            assertTrue(shell.isQuiescent(), "$what: the shell did not settle — ${shell.quiescenceReport()}")
        }

        fun lens(left: Boolean): Gray8 {
            val ctx = if (left) sim.left else sim.right
            val g = Gray8(Geometry.PANEL_W, Geometry.PANEL_H)
            for (y in 0 until Geometry.PANEL_H) for (x in 0 until Geometry.PANEL_W) {
                val b = ctx.panel[y * ctx.stride + (x shr 1)].toInt() and 0xFF
                g[x, y] = (if (x and 1 == 0) b shr 4 else b and 0x0F) * 17
            }
            return g
        }

        /** Both assertions, both lenses, plus the loud-failure surfaces. */
        fun assertOracle(what: String) {
            synchronized(flushFails) { assertTrue(flushFails.isEmpty(), "$what: failed flushes $flushFails") }
            synchronized(faults) { assertTrue(faults.isEmpty(), "$what: transport faults $faults") }
            for (arm in Arm.entries) {
                val left = arm == Arm.LEFT
                val glass = lens(left)
                firstDiff(quantised(shell.comp.expectedLens(left)), glass)?.let {
                    throw AssertionError("$what: ${arm.name} BELIEF != glass at $it")
                }
                firstDiff(quantised(truthOf(shell.comp.composed, shell.comp.planes, left)), glass)?.let {
                    throw AssertionError("$what: ${arm.name} glass != TRUTH at $it — planes=" +
                        shell.comp.planes.joinToString { p -> "${p.rect}@${p.disparity}" })
                }
            }
            val flags = sim.flags(Arm.LEFT) + sim.flags(Arm.RIGHT)
            assertTrue(flags.none { it.value }, "$what: sticky diagnostic flags ${flags.filterValues { it }}")
        }

        /** What is on screen right now — the walk's own coverage report. */
        fun surface(): String = buildString {
            append(shell.currentWindowId() ?: "main")
            if (shell.menuIsOpen) append("+menu")
            if (shell.switcherIsOpen) append("+wheel")
            if (shell.keyboardIsOpen) append("+kbd")
            if (shell.exclusiveMode) append("+excl")
            if (shell.notifications.active) append(if (shell.notifications.focused) "+notice!" else "+notice")
        }

        suspend fun stop() {
            shell.stop()
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    /**
     * A list window whose rows open each SHELL SURFACE in turn — the context
     * menu, the §4.8 keyboard and §4.9 exclusive mode — so their plane maps
     * are reachable from a random walk. Row 0 is harmless (§1.7).
     */
    private class SurfaceWindow : wm.damage.core.shell.DamageWindow(
        "surfaces", "Surfaces", wm.damage.core.gfx.IconKind.FILES,
    ) {
        private val model = wm.damage.core.shell.ListModel()
        private var ctx: ShellServices? = null
        private var level = 0
        private val labels = listOf("Nothing", "Menu", "Keyboard", "Exclusive", "Deeper")

        override fun onRegistered(ctx: ShellServices) { this.ctx = ctx }
        override fun onActivate(ctx: ShellServices, from: wm.damage.core.shell.ActivationSource) { this.ctx = ctx }

        override fun view() = wm.damage.core.shell.WindowView.ListView(
            model, { labels.size },
            { g, i, r, _ -> g.fillRect(r.x + 8, r.y + 6, 60 + i * 24, 12, ((i % 12) + 2) * 17) },
            { g, r, i -> g.fillRect(r.x + 8, r.y + 8, 120, 16, ((i % 15) + 1) * 17) },
            { i ->
                when (labels[i]) {
                    "Menu" -> ctx?.openMenu(MenuSurface.Spec("walk", listOf(
                        MenuSurface.Item("Close"), MenuSurface.Item("Also close", "detail"),
                        MenuSurface.Item("Third row", "more detail here")), onCommit = { }), owner = this)
                    "Keyboard" -> ctx?.openKeyboard(KeyboardSurface.Spec("walk", "abc", onCommit = { }), owner = this)
                    "Exclusive" -> ctx?.enterExclusive(this)
                    "Deeper" -> level = (level + 1) % 3
                    else -> {}
                }
            },
        )

        override fun paintExclusive(g: Gray8, safe: Rect, full: Boolean): List<Rect> {
            if (!full) return emptyList()
            g.fillRect(safe.x + 32, safe.y + 32, safe.w - 64, 40, 9 * 17)
            return listOf(safe)
        }

        /** §16.1 deep links, so the walk can REACH each surface deliberately
         *  instead of hoping the dice land on the right row. */
        override fun open(target: String): Boolean = when (target) {
            "menu" -> ctx?.openMenu(MenuSurface.Spec("walk", listOf(
                MenuSurface.Item("Close"), MenuSurface.Item("Also close", "detail"),
                MenuSurface.Item("Third row", "more detail here")), onCommit = { }), owner = this) == true
            "kbd" -> ctx?.openKeyboard(KeyboardSurface.Spec("walk", "abc", onCommit = { }), owner = this) == true
            "excl" -> ctx?.enterExclusive(this) == true
            "deep" -> { level = (level + 1) % 3; true }
            else -> false
        }

        override fun summary() = Summary("surfaces")
        override fun levelDepth() = 1 + level
        override fun back(): Boolean = if (level > 0) { level--; true } else false
        override fun saveState() = kotlinx.serialization.json.buildJsonObject { }
        override fun restoreState(state: kotlinx.serialization.json.JsonObject) {}
    }

    /** A document window that also declares its own §3 depth region — the
     *  `contentPlanes` path, which only Games exercised before. */
    private class WalkDocWindow : wm.damage.core.shell.DamageWindow(
        "walkdoc", "WalkDoc", wm.damage.core.gfx.IconKind.READER,
    ) {
        private val doc = wm.damage.core.shell.DocModel()
        override fun view() = wm.damage.core.shell.WindowView.DocView(doc, { 120 }, 24,
            { g, i, r -> g.fillRect(r.x + 4, r.y + 4, 40 + (i * 17) % 300, 14, ((i % 10) + 3) * 17) },
            {}, stepLines = { 3 })

        override fun contentPlanes(content: Rect): List<Pair<Rect, Int>> {
            val h = Geometry.snapY(content.h / 3)
            return listOf(Rect(content.x + 16, Geometry.snapY(content.y + content.h / 3), content.w - 32, h) to 0)
        }

        override fun summary() = Summary("walk doc")
        override fun saveState() = kotlinx.serialization.json.buildJsonObject { }
        override fun restoreState(state: kotlinx.serialization.json.JsonObject) {}
    }

    @Test
    fun beliefEqualsGlassEqualsTruthAcrossARandomWalkAtEveryHeight(): Unit = runBlocking {
        for (h in ShellSettings.HEIGHTS) {
            val rig = Rig(h, seed = 0xDA_4A_6EL + h)
            val seen = LinkedHashSet<String>()
            try {
                rig.shell.start()
                rig.settle("h=$h boot")
                rig.assertOracle("h=$h boot")
                for (step in 1..STEPS) {
                    // every so often, HAND OFF to a named surface: a random
                    // walk of five rows reaches the far corners only by luck,
                    // and a walk that misses exclusive mode proves nothing
                    // about exclusive mode
                    if (step % HANDOFF_EVERY == 0) {
                        val (win, target) = HANDOFFS[(step / HANDOFF_EVERY - 1) % HANDOFFS.size]
                        rig.shell.services.runOnShell { rig.shell.services.openWindow(win, target) }
                        val where = "h=$h step $step (open $win${target?.let { ":$it" } ?: ""})"
                        rig.settle(where)
                        rig.assertOracle(where)
                        seen.add(rig.surface())
                        continue
                    }
                    val g = GESTURES[rig.rnd.nextInt(GESTURES.size)]
                    rig.shell.postGesture(g)
                    // a notice every so often: the box arrives at the content
                    // plane and steps FORWARD after its grace — two plane maps
                    // no gesture produces
                    if (rig.rnd.nextInt(19) == 0) {
                        rig.shell.postNotice(Notifications.Notice(
                            "SMS · WALK", "t$step", "step $step of the walk", "12:00",
                            emergency = rig.rnd.nextInt(9) == 0))
                    }
                    val where = "h=$h step $step (${EvenHubMsg.eventName(g)})"
                    rig.settle(where)
                    rig.assertOracle(where)
                    seen.add(rig.surface())
                }
                // …and the one plane map a settle always races past: the box
                // takes FOCUS after its grace and steps forward to plane 0.
                // The grace belongs to WINDOW mode (§4.5 rule 4 — the quiet
                // modes auto-dismiss instead), so land there first, whatever
                // the walk left on screen.
                rig.shell.services.runOnShell { rig.shell.services.openWindow("walkdoc", null) }
                rig.settle("h=$h back to a window")
                rig.shell.postNotice(Notifications.Notice(
                    "SMS · WALK", "focus", "the box takes focus and steps forward", "12:00"))
                rig.settle("h=$h notice shown")
                val t0 = System.currentTimeMillis()
                while (!rig.shell.notifications.focused && System.currentTimeMillis() - t0 < 8_000) delay(20)
                assertTrue(rig.shell.notifications.focused, "h=$h: the box never took focus")
                rig.settle("h=$h notice focused")
                rig.assertOracle("h=$h notice focused")
                seen.add(rig.surface())
                println("h=$h reached ${seen.size} surfaces: ${seen.sorted()}")
                // the walk is only worth its runtime if it actually gets
                // somewhere: every shell surface must have been on screen
                for (must in listOf("+menu", "+wheel", "+kbd", "+excl", "+notice!")) {
                    assertTrue(seen.any { it.contains(must) },
                        "h=$h: the walk never reached '$must' — it proves less than it claims (saw $seen)")
                }
            } finally {
                rig.stop()
            }
        }
    }

    private companion object {
        const val STEPS = 240
        const val HANDOFF_EVERY = 17

        /** The surfaces the walk visits deliberately, in rotation. */
        val HANDOFFS: List<Pair<String, String?>> = listOf(
            "surfaces" to "menu", "walkdoc" to null, "surfaces" to "kbd",
            "games" to null, "surfaces" to "excl", "surfaces" to "deep",
            "settings" to null, "surfaces" to null,
        )

        /** The §1 grammar as the ring delivers it, long-press pair included. */
        val GESTURES = intArrayOf(
            EvenHubMsg.EV_CLICK, EvenHubMsg.EV_CLICK, EvenHubMsg.EV_CLICK,
            EvenHubMsg.EV_DOUBLE_CLICK, EvenHubMsg.EV_DOUBLE_CLICK,
            EvenHubMsg.EV_SCROLL_TOP, EvenHubMsg.EV_SCROLL_TOP,
            EvenHubMsg.EV_SCROLL_BOTTOM, EvenHubMsg.EV_SCROLL_BOTTOM, EvenHubMsg.EV_SCROLL_BOTTOM,
            EvenHubMsg.EV_RING_LONG_PRESS, EvenHubMsg.EV_RING_LONG_PRESS_RELEASE,
        )

        /** 8-bit belief through the emitter's quantiser, so it compares with
         *  a panel that holds nibbles. */
        fun quantised(g: Gray8): Gray8 {
            val out = Gray8(g.w, g.h)
            for (i in g.pix.indices) {
                out.pix[i] = (wm.damage.core.gfx.Pack.level(g.pix[i].toInt() and 0xFF) * 17).toByte()
            }
            return out
        }

        fun firstDiff(a: Gray8, b: Gray8): String? {
            for (y in 0 until Geometry.PANEL_H) for (x in 0 until Geometry.PANEL_W) {
                if (a[x, y] != b[x, y]) return "($x,$y) expected ${a[x, y]} got ${b[x, y]}"
            }
            return null
        }

        /**
         * The independent per-lens truth: the nominal frame is the transparent
         * base, every region vacates its nominal area to black, and the region
         * PIECES render at their shift far to near with the nearest winning.
         * Written without reference to `Compositor.renderTruth`.
         */
        fun truthOf(composed: Gray8, planes: List<Compositor.PlaneRegion>, left: Boolean): Gray8 {
            val out = composed.copy()
            for (p in planes) out.fillRect(p.rect, 0)
            val xs = sortedSetOf(0, Geometry.PANEL_W)
            val ys = sortedSetOf(0, Geometry.PANEL_H)
            for (p in planes) { xs.add(p.rect.x); xs.add(p.rect.right); ys.add(p.rect.y); ys.add(p.rect.bottom) }
            val xa = xs.toIntArray()
            val ya = ys.toIntArray()
            class Piece(val r: Rect, val d: Int)
            val pieces = ArrayList<Piece>()
            for (i in 0 until xa.size - 1) for (j in 0 until ya.size - 1) {
                val r = Rect(xa[i], ya[j], xa[i + 1] - xa[i], ya[j + 1] - ya[j])
                if (r.w <= 0 || r.h <= 0) continue
                val owner = planes.lastOrNull { it.rect.contains(r) } ?: continue
                pieces.add(Piece(r, owner.disparity))
            }
            for (p in pieces.sortedByDescending { it.d }) {
                val shift = if (left) -p.d else p.d
                for (y in p.r.y until p.r.bottom) for (x in p.r.x until p.r.right) {
                    val tx = x + shift
                    if (tx in 0 until Geometry.PANEL_W) out[tx, y] = composed[x, y]
                }
            }
            return out
        }
    }
}
