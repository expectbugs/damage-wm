package wm.damage.core

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.app.AppHost
import wm.damage.core.app.CoreExecutor
import wm.damage.core.app.HostBackend
import wm.damage.core.app.LocalWindowSource
import wm.damage.core.app.Migrate
import wm.damage.core.app.NoticeCore
import wm.damage.core.app.NoticeRecord
import wm.damage.core.app.Notices
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.app.Record
import wm.damage.core.app.WindowState
import wm.damage.core.shell.Persistence
import wm.damage.core.windows.tmux.TmuxCore
import wm.damage.core.windows.tmux.TmuxMigration

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-3, pieces B9 and B15: Tmux's core on a real PC record — a waiting edge raised once into the notice record
 * with today's body, thread and deep link (the pane's whole line, never cut), none for a muted session or with Alerts
 * off — and Tmux's rows from APK 0.68's `window.tmux` blob written once. A scripted provider; temp directories.
 */
class TmuxCoreTest {
    private val dir = Files.createTempDirectory("damage-tmux-core")
    private val host = AppHost(Files.createDirectories(dir.resolve("pc")), AppHost.WINDOWS, CoreExecutor("damage-core-tmux-core")).also { it.open() }

    @AfterTest
    fun tearDown() {
        host.close()
        host.executor.shutdown()
        dir.toFile().deleteRecursively()
    }

    private fun state() = WindowState(TmuxCore.WINDOW, host.read(TmuxCore.WINDOW).third.items)

    private fun notice(id: String): NoticeRecord? {
        repeat(4) { host.executor.call {} }           // the raise, its answer and the record's publish
        return host.read(Notices.WINDOW).third.items["rec.notices.$id"]?.let { Notices.parse(id, it.value) }
    }

    private fun write(action: String, item: String, v: Boolean, n: Long) {
        host.executor.call {}
        val done = java.util.concurrent.CountDownLatch(1)
        host.act(Op("phone", OpId.Queued("s", n), TmuxCore.WINDOW, action, listOf(item), JsonPrimitive(v), at = System.currentTimeMillis())) { done.countDown() }
        done.await()
    }

    @Test
    fun aWaitingEdgeIsRaisedOnceWithTodaysBox_noneWhenMutedOrWithAlertsOff() {
        val p = TestTmux()
        val core = TmuxCore()
        core.attachAlerts(p, NoticeCore(host)) { state() }
        val long = (0 until 120).joinToString("") { ('a' + it % 26).toString() } + "😀"
        p.alert(p.sessions[0].copy(waiting = true, lastLine = long))
        val r = notice("tmux:beardos/claude")
        assertEquals("claude wants input: $long", r?.body, "today's body, the pane's whole line (one host: no suffix)")
        assertEquals("tmux", r?.app)
        assertEquals("session:beardos:claude", r?.target, "a tap opens that session's live view")
        assertEquals("Tmux", r?.gatedBy)
        assertEquals("1", r?.occ)
        p.alert(p.sessions[0].copy(waiting = true, lastLine = "again"))
        assertEquals("2", notice("tmux:beardos/claude")?.occ, "the next waiting edge is the next occurrence")
        // muted: nothing
        write(TmuxCore.MUTE_ACTION, TmuxCore.muteName("beardos", "build"), true, 1)
        p.alert(p.sessions[1].copy(waiting = true))
        assertNull(notice("tmux:beardos/build"), "a muted session raises nothing")
        // Alerts off: nothing
        write(TmuxCore.SET, TmuxCore.ALERTS, false, 2)
        p.alert(p.sessions[0].copy(waiting = true, lastLine = "third"))
        assertEquals("2", notice("tmux:beardos/claude")?.occ, "Alerts off raises nothing")
        // two hosts listed: the host joins the body, as today
        write(TmuxCore.SET, TmuxCore.ALERTS, true, 3)
        p.sessions = p.sessions + p.sessions[1].copy(host = "slappy")
        p.status()
        p.alert(p.sessions[2].copy(waiting = true, lastLine = "y/n"))
        assertEquals("build · slappy wants input: y/n", notice("tmux:slappy/build")?.body)
        core.detachAlerts()
        assertTrue(p.listeners.isEmpty())
    }

    @Test
    fun aPresenterRowChangeIsAnOpTheRecordTakes() {
        val core = TmuxCore()
        val src = LocalWindowSource(core, HostBackend(host, "local", "s-local"))
        src.act(TmuxCore.muteDraft("beardos", "claude", true))
        src.act(TmuxCore.setDraft(TmuxCore.SIZE, JsonPrimitive(288)))
        eventually("both rows in the record") { TmuxCore.muted(state(), "beardos", "claude") && TmuxCore.size(state()) == 288 }
        assertEquals(setOf("beardos/claude"), TmuxCore.mutedSet(state()))
        src.close()
    }

    /** B15: a fixture of each field in APK 0.68's shape, as `TmuxWindow.saveState` wrote it. */
    @Test
    fun theOld068BlobBecomesRecordRowsOnce_andARowTheRecordHoldsStands() {
        val stamp = 1_700_000_000_000L
        val store = Persistence(dir.resolve("pc.json"))
        store.tryApplyRemote("window.tmux", buildJsonObject {
            put("level", "live"); put("host", "beardos"); put("session", "claude"); put("window", -1)
            put("context", false); put("alerts", false); put("paceMs", 2_000); put("height", 352)
            put("muted", buildJsonArray { add(JsonPrimitive("beardos/claude")); add(JsonPrimitive("slappy/build")) })
        }, stamp)
        write(TmuxCore.SET, TmuxCore.CONTEXT, true, 1)          // the record already holds Context rows: it stands
        val notes = ArrayList<String>()
        TmuxMigration.onHost(host, store) { notes += it }
        val st = state()
        assertEquals(false, TmuxCore.alertsOn(st))
        assertEquals(true, TmuxCore.context(st), "a row the record holds stands")
        assertEquals(352, TmuxCore.size(st))
        assertEquals(setOf("beardos/claude", "slappy/build"), TmuxCore.mutedSet(st))
        assertEquals(Migrate.DEVICE, st.itemNamed(TmuxCore.SIZE)?.by)
        assertEquals(stamp, st.itemNamed(TmuxCore.SIZE)?.at, "stamped with the blob's time")
        assertEquals(1, notes.size)
        assertTrue(st.items.keys.none { it.endsWith("paceMs") || it.endsWith("level") }, "the view's own state stays local")
        val seq = host.read(TmuxCore.WINDOW).second
        TmuxMigration.onHost(host, store) { notes += it }
        assertEquals(seq, host.read(TmuxCore.WINDOW).second, "a second start writes nothing")
        assertEquals(1, notes.size)
        // a blob that chose Global wrote no height: Size becomes 0
        val global = Persistence(dir.resolve("pc2.json"))
        global.tryApplyRemote("window.tmux", buildJsonObject { put("alerts", true) }, stamp)
        assertEquals(listOf(TmuxCore.SIZE to JsonPrimitive(0)), TmuxMigration.derived(global.get("window.tmux")!!))
        // this build's own blob (the view's state alone) offers nothing: Size is never turned to Global by it
        val mine = buildJsonObject { put("level", "live"); put("host", "beardos"); put("session", "claude"); put("window", -1); put("paceMs", 1_000) }
        assertEquals(emptyList(), TmuxMigration.derived(mine))
        assertEquals(emptyList(), TmuxMigration.FIELDS.mapNotNull { f -> mine[f.name]?.let(f.read) })
        assertTrue(Record.key(TmuxCore.WINDOW, TmuxCore.SIZE).startsWith("rec.tmux."))
    }
}
