package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import wm.damage.core.app.AppHost
import wm.damage.core.app.Draft
import wm.damage.core.app.HostBackend
import wm.damage.core.app.Item
import wm.damage.core.app.LocalWindowSource
import wm.damage.core.app.MemoryRecord
import wm.damage.core.app.Peer
import wm.damage.core.app.RecordBackend
import wm.damage.core.app.SourceListener
import wm.damage.core.app.WindowState
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.windows.games.GamesCore
import wm.damage.core.windows.games.GamesGlasses
import wm.damage.core.windows.games.GamesMigration
import wm.damage.core.windows.games.holdem.HoldemRules
import wm.damage.core.windows.games.holdem.HoldemTable
import wm.damage.core.windows.games.kit.Seats
import wm.damage.core.windows.games.roster.Roster
import wm.damage.core.wire.EvenHubMsg

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-5, stage G (the lean spec's §5, Games): Games on the PC record — its rows described once, the world written
 * at the settle points and nothing on a tick, a host resumed after a stop mid-hand playing the same next actions, no
 * world minted before the source has heard Games, the migration once, the card equal to Main's row, and one hand's
 * op-log bytes measured on a real record.
 */
class GamesCoreTest {

    /** A backend over a record in memory that counts what is written and hears Games only when told ([hear]). */
    private class Gated(val inner: MemoryRecord = MemoryRecord(GamesCore.ACTIONS), @Volatile var on: Boolean = true) :
        RecordBackend by inner {
        val drafts = CopyOnWriteArrayList<Draft>()
        private val listeners = CopyOnWriteArrayList<SourceListener>()
        override fun heard(window: String) = on
        override fun watch(window: String, l: SourceListener) { listeners += l; inner.watch(window, l) }
        override fun unwatch(window: String, l: SourceListener) { listeners -= l; inner.unwatch(window, l) }
        override fun submit(window: String, draft: Draft) { drafts += draft; inner.submit(window, draft) }

        /** The first snapshot that shares Games: heard, and its state told (as the working copy's refresh does). */
        fun hear() = executor.execute("hear") {
            on = true
            for (l in listeners) l.changed(inner.stateOf(GamesCore.WINDOW), emptySet())
        }

        fun world(): List<Draft> = drafts.filter { it.action != GamesCore.A_SET }
    }

    private class Rig(tmp: Path, val backend: RecordBackend) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val shell = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true)),
            Persistence(Files.createDirectories(tmp).resolve("state.json")), null, scope)
        val core = GamesCore()
        val source = LocalWindowSource(core, backend)
        val win = GamesGlasses(FakeText(), scope, core, source, worldSeed = SEED)

        init {
            shell.register(win)
        }

        suspend fun start() {
            shell.start()
            settle()
            shell.postGesture(EvenHubMsg.EV_CLICK)          // Main row 0 = Games
            settle()
        }

        suspend fun settle() = waitUntil(20_000, 10, { "the shell did not settle — ${shell.quiescenceReport()}" }) { shell.isQuiescent() }

        suspend fun await(what: String, cond: () -> Boolean) = waitUntil(30_000, 10, { "did not converge: $what (title=${win.title()})" }, cond)

        /** A row set on the loop, as Settings does. */
        suspend fun row(label: String, value: String) {
            shell.services.runOnShell { win.appSettings().first { it.name == label }.apply(value) }
            settle()
        }

        suspend fun pick(label: String) {
            val i = shell.menuLabels.indexOfFirst { it.startsWith(label) }
            assertTrue(i >= 0, "menu row '$label' not in ${shell.menuLabels}")
            repeat((i - shell.menuCursor).mod(shell.menuLabels.size)) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); settle() }
            shell.postGesture(EvenHubMsg.EV_CLICK)
            settle()
        }

        /** From the root: the table list, the Regular table, the buy-in confirmed; at the table. */
        suspend fun sitDown() {
            await("the games root") { win.title() == "games" }
            shell.postGesture(EvenHubMsg.EV_CLICK); settle()
            await("the table list") { win.title() == "hold'em" }
            shell.postGesture(EvenHubMsg.EV_CLICK); settle()
            await("the buy-in confirm") { shell.menuIsOpen }
            pick("Sit down")
            await("the table") { win.tableRunning && win.levelDepth() == 2 }
        }

        /** One step of his: at a finished hand the tap that deals, at his turn the contextual row (Check or Fold, no
         *  confirm under `Money only`). Returns the hand and its history at his turn, or null at a deal. */
        suspend fun step(): Pair<Int, List<String>>? {
            await("his turn or a result") { (win.isMyTurn || win.handIsComplete || !win.tableRunning) && shell.isQuiescent() }
            if (!win.tableRunning) return null
            if (win.handIsComplete) {
                shell.postGesture(EvenHubMsg.EV_CLICK); settle()
                return null
            }
            val at = win.handNumber to win.historyDocLines
            shell.postGesture(EvenHubMsg.EV_CLICK)
            await("the action level") { shell.menuIsOpen }
            pick(shell.menuLabels.first { it.startsWith("Check") || it.startsWith("Fold") })
            return at
        }

        suspend fun stop() {
            shell.stop()
            scope.cancel()
        }
    }

    private fun recordOf(b: RecordBackend): WindowState = b.executor.call { b.stateOf(GamesCore.WINDOW) }

    @Test
    fun everyRowIsDescribedOnce_andTheGlassesRowsCarryItsWordsAndOptions() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val core = GamesCore()
            val w = GamesGlasses(FakeText(), scope, core, LocalWindowSource(core, MemoryRecord(GamesCore.ACTIONS)))
            assertEquals(GamesCore.ROWS.map { it.label }, w.appSettings().map { it.name }, "one glasses row per described row, in order")
            for ((r, h) in GamesCore.ROWS.zip(w.appSettings())) {
                assertEquals(r.options.map { it.second }, h.options(), "${r.label}: the options")
                assertEquals(r.labelOf(null), h.current(), "${r.label}: the default as shown")
            }
            assertEquals(GamesCore.ROWS.size, GamesCore.ROWS.map { it.id }.toSet().size, "one item per row")
            val desc = GamesCore.settings(WindowState.empty(GamesCore.WINDOW))
            assertEquals(GamesCore.ROWS.map { it.id }, desc.map { (it as JsonObject)["id"].toString().trim('"') })
            assertEquals(setOf("shared"), GamesCore.ROWS.filter { it.id.startsWith("notify.") }.map { it.scope }.toSet())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theWorldIsWrittenAtTheSettlePoints_andNothingOnATick(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-games-core")
        val b = Gated()
        val r = Rig(tmp, b)
        try {
            r.start()
            r.await("the world minted and written") { b.world().any { it.targets == listOf(GamesCore.WORLD) } }
            r.row("Bot pace", "instant")
            r.row("Confirm", "Money only")
            assertEquals(listOf(GamesCore.PACE, GamesCore.CONFIRM), b.drafts.filter { it.action == GamesCore.A_SET }.map { it.targets.single() },
                "a row is one set op")
            val minted = b.world().size
            assertEquals(2 + r.win.roster.characters.size, minted, "the bankroll, the world and every character, once")
            r.sitDown()
            r.await("his turn") { r.win.isMyTurn && r.shell.isQuiescent() }
            val atTurn = b.world().drop(minted)
            assertTrue(atTurn.any { it.targets == listOf(GamesCore.TABLE) }, "sitting down writes the table")
            assertTrue(atTurn.any { it.targets == listOf(GamesCore.BANKROLL) }, "and the bankroll the buy-in left")
            // the card reads what Main's row says, from the record (the bots' moves since the sit-down move no line)
            r.await("the record in step") { recordOf(b).itemNamed(GamesCore.TABLE) != null }
            assertEquals(r.win.summary(), r.core.summary(recordOf(b)), "the card's line is Main's row")
            // a tick: nothing moves while he thinks
            val idle = b.drafts.size
            delay(1_500)
            assertEquals(idle, b.drafts.size, "nothing is written while the table waits for him")
            // his action writes the table, the bots' replies write nothing until the hand settles or he acts again
            r.step()
            r.await("his action written") { b.drafts.drop(idle).any { it.targets == listOf(GamesCore.TABLE) } }
            r.settle()
            assertTrue(b.drafts.drop(idle).all { it.action == GamesCore.A_PLAY }, "only the engine's writes: ${b.drafts.drop(idle)}")
        } finally {
            r.stop()
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun aHostResumedAfterAStopMidHand_playsTheSameNextActions(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-games-resume")
        val a = Gated()
        val ra = Rig(tmp.resolve("a"), a)
        var captured: WindowState? = null
        val seenA = ArrayList<Pair<Int, List<String>>>()
        try {
            ra.start()
            ra.row("Bot pace", "instant")
            ra.row("Confirm", "Money only")
            ra.sitDown()
            assertNotNull(ra.step(), "his first turn")
            // the record as the phone's app ending here would leave it: read on the loop, after his action's writes
            ra.shell.services.runOnShell { captured = recordOf(a) }
            ra.settle()
            var guard = 0
            while (seenA.sumOf { it.second.size } < 24 && ra.win.tableRunning && guard++ < 80) ra.step()?.let { seenA += it }
        } finally {
            ra.stop()
        }
        val held = assertNotNull(captured)
        assertNotNull(held.itemNamed(GamesCore.TABLE), "the table was in the record at his action")
        // a new host over a record holding only what the first one wrote
        val b = Gated()
        for ((k, i) in held.items) {
            val name = k.removePrefix("rec.${GamesCore.WINDOW}.")
            b.inner.executor.call { b.inner.submit(GamesCore.WINDOW, Draft(if (i.gone) GamesCore.A_DROP else GamesCore.A_PLAY, listOf(name), i.value, at = i.at)) }
        }
        val rb = Rig(tmp.resolve("b"), b)
        val seenB = ArrayList<Pair<Int, List<String>>>()
        try {
            rb.start()
            rb.row("Bot pace", "instant")
            rb.row("Confirm", "Money only")
            rb.await("the root with the table running") { rb.win.title() == "games" && rb.win.tableRunning }
            rb.shell.postGesture(EvenHubMsg.EV_CLICK)           // Hold'em → the live table
            rb.settle()
            var guard = 0
            while (seenB.size < seenA.size && rb.win.tableRunning && guard++ < 80) rb.step()?.let { seenB += it }
        } finally {
            rb.stop()
            tmp.toFile().deleteRecursively()
        }
        val lines = seenA.sumOf { it.second.size }
        assertTrue(lines >= 20, "at least 20 lines of hand history compared ($lines over ${seenA.size} turns)")
        assertEquals(seenA, seenB, "the resumed host played the same hands")
    }

    @Test
    fun noWorldIsMintedOrWrittenBeforeTheSourceHasHeardGames(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-games-heard")
        val b = Gated(on = false)
        // his migrated world is on the PC; the copy has not had Games in a snapshot yet
        b.inner.executor.call {
            b.inner.submit(GamesCore.WINDOW, Draft(GamesCore.MIGRATE, listOf(GamesCore.WORLD), Roster(worldSeed = 4242, gameNo = 7, born = 1).toJson(), at = 1L))
            b.inner.submit(GamesCore.WINDOW, Draft(GamesCore.MIGRATE, listOf("char.c0"), Roster.birth(4242, 0).toJson(), at = 1L))
        }
        b.drafts.clear()
        val r = Rig(tmp, b)
        try {
            r.start()
            assertEquals(GamesGlasses.WAITING, r.win.title(), "the window says it waits")
            assertEquals(GamesGlasses.WAITING, r.win.summary().line, "and Main's row")
            r.shell.postGesture(EvenHubMsg.EV_CLICK)          // Hold'em: refused while waiting
            r.settle()
            assertEquals("GAMES", r.win.levelName)
            delay(500)
            assertEquals(0, r.win.roster.characters.size, "nothing minted")
            assertEquals(emptyList(), b.world(), "nothing written")
            b.hear()
            r.await("the world read and seeded") { r.win.roster.characters.size > 1 && r.shell.isQuiescent() }
            assertEquals(4242L, r.win.roster.worldSeed, "his world, not a new one")
            assertTrue(r.win.roster.get("c0") != null)
            r.await("the seeding written") { b.world().any { it.targets.single().startsWith(GamesCore.CHAR) } }
            assertTrue(b.world().none { it.targets == listOf(GamesCore.WORLD) && (it.result as JsonObject)["seed"] != JsonPrimitive(4242L) },
                "no world written over his")
            assertTrue(r.win.summary().line != GamesGlasses.WAITING, "Main's row reads the world again")
        } finally {
            r.stop()
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun theMigrationCarriesTheBlobTheSubKeysAndTheFigures_once() {
        val tmp = Files.createTempDirectory("damage-games-migrate")
        try {
            val store = Persistence(tmp.resolve("state.json"))
            val who = (0 until 3).map { Seats.Occupant(if (it == 1) GamesGlasses.ME else "c$it", "P$it", human = it == 1) }
            val table = HoldemTable.start(HoldemRules.Table.REGULAR, 11, who, intArrayOf(300, 300, 300), button = 0)
            store.put("window.games", buildJsonObject {
                put("level", "TABLE"); put("mySeat", 1); put("myStake", 300); put("myFee", 30); put("bgOwed", 2)
                putJsonObject("castStake") { put("0", 330); put("2", 330) }
                put("confirm", "MONEY"); put("paceMs", 300); put("dealAnim", false); put("notifyWin", true); put("height", 352)
                put("archetypes", "yes")                       // not a value of the row: not carried
            })
            store.put("window.games.bankroll", buildJsonObject { put("cash", 640); put("loser", 2) })
            store.put("window.games.world", Roster(worldSeed = 99, gameNo = 4, born = 2).toJson())
            store.put("window.games.char.c0", Roster.birth(99, 0).toJson())
            store.put("window.games.table", table.toJson())
            val dir = Files.createDirectories(tmp.resolve("pc"))
            val host = AppHost.inScratchDir(dir)
            try {
                val notes = ArrayList<String>()
                GamesMigration.onHost(host, store) { notes += it }
                val st = WindowState(GamesCore.WINDOW, host.read(GamesCore.WINDOW).third.items)
                fun v(name: String) = st.itemNamed(name)?.value
                assertEquals(JsonPrimitive("MONEY"), v(GamesCore.CONFIRM))
                assertEquals(JsonPrimitive(300), v(GamesCore.PACE))
                assertEquals(JsonPrimitive(false), v(GamesCore.DEAL))
                assertEquals(JsonPrimitive(true), v(GamesCore.NOTIFY_WIN))
                assertEquals(JsonPrimitive(352), v(GamesCore.SIZE))
                assertEquals(null, v(GamesCore.ARCHETYPES), "a value no row offers is not carried")
                assertEquals(JsonPrimitive(640), (v(GamesCore.BANKROLL) as JsonObject)["cash"])
                assertEquals(JsonPrimitive(99L), (v(GamesCore.WORLD) as JsonObject)["seed"])
                assertNotNull(v("char.c0"))
                val tour = assertNotNull(GamesCore.tournamentOf(v(GamesCore.TABLE) as JsonObject), "the table replays")
                assertEquals(1, tour.mySeat)
                assertEquals(300, tour.myStake)
                assertEquals(30, tour.myFee)
                assertEquals(2, tour.bgOwed)
                assertEquals(mapOf(0 to 330, 2 to 330), tour.castStake)
                assertEquals(table.toJson(), tour.table.toJson())
                assertTrue(st.items.values.all { it.by == "migration" })
                assertEquals(1, notes.size)
                val seq = host.seq
                GamesMigration.onHost(host, store) { notes += it }
                assertEquals(seq, host.seq, "a second start writes nothing")
                assertEquals(1, notes.size)
            } finally {
                host.close()
            }
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /** The spec's §4.4 weight: one hand's op-log bytes on a real record (the PC's form: the presenter over its record). */
    @Test
    fun oneHandsOpLogBytes(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-games-oplog")
        val dir = Files.createDirectories(tmp.resolve("pc"))
        val host = AppHost.inScratchDir(dir)
        val backend = HostBackend(host, Peer.LOCAL.device, "games-test")
        val r = Rig(tmp.resolve("shell"), backend)
        try {
            r.start()
            r.row("Bot pace", "instant")
            r.row("Confirm", "Money only")
            r.sitDown()
            r.await("his turn") { r.win.isMyTurn && r.shell.isQuiescent() }
            val log = dir.resolve(AppHost.LOG_FILE)
            val hand = r.win.handNumber
            val before = Files.size(log)
            var guard = 0
            while (guard++ < 40) {
                r.await("his turn or a result") { (r.win.isMyTurn || r.win.handIsComplete || !r.win.tableRunning) && r.shell.isQuiescent() }
                if (!r.win.tableRunning || (r.win.handNumber == hand + 1 && r.win.isMyTurn)) break
                r.step()
            }
            r.settle()
            val bytes = Files.size(log) - before
            println("one hand's op-log bytes (measured, hand ${hand + 1}, the PC's record): $bytes")
            assertTrue(bytes in 1..64_000, "one hand wrote $bytes bytes of op log")
        } finally {
            r.stop()
            backend.close()
            host.close()
            tmp.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val SEED = 1L
    }
}
