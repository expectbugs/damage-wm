package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.app.AppHost
import wm.damage.core.app.HostBackend
import wm.damage.core.app.NoticeCore
import wm.damage.core.app.NoticeInput
import wm.damage.core.app.NoticeMirror
import wm.damage.core.app.NoticeRecord
import wm.damage.core.app.NoticeRef
import wm.damage.core.app.Notices
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.shell.ActivationSource
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.Notifications
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellServices
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.wire.EvenHubMsg

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q3 (the spec's §2.1, §5 N4–N5): the glasses' side of the notice record. A record notice is the box
 * the app's own raise showed (one box when both come in the switch-over minute); reading effects go out from input only
 * — a double-tap dismisses, the tap-through reads, an activation from input reads, and Silent Mode's drop and a RESTORE
 * send nothing; a move elsewhere takes the box away; a restored notice the record no longer holds is not shown again.
 * The last test runs the whole path on a record of its own. Made-up notices; a shell on the simulator.
 */
class ShellNoticeRecordTest {

    /** The shell's reading effects, as the mirror would get them. */
    private class Input(val live: (List<NoticeRef>) -> Set<NoticeRef> = { it.toSet() }) : NoticeInput {
        val reads = CopyOnWriteArrayList<NoticeRef>()
        val dismissed = CopyOnWriteArrayList<NoticeRef>()
        val asked = CopyOnWriteArrayList<NoticeRef>()
        override fun read(ref: NoticeRef) { reads += ref }
        override fun dismiss(ref: NoticeRef) { dismissed += ref }
        override fun restored(refs: List<NoticeRef>): Set<NoticeRef> { asked += refs; return live(refs) }
        override fun minute() {}
        val sent: Int get() = reads.size + dismissed.size
    }

    /** A window standing for Torrents: its id is the notices' app. */
    private class Probe : DamageWindow("torrents", "Torrents", IconKind.FILES) {
        val seen = CopyOnWriteArrayList<ActivationSource>()
        private val model = ListModel()
        override fun view(): WindowView = WindowView.ListView(model, { 1 },
            { _: Gray8, _: Int, _: Rect, _: Boolean -> }, { _: Gray8, _: Rect, _: Int -> }, {})
        override fun title(): String = "Torrents"
        override fun summary() = Summary("Torrents")
        override fun onActivate(ctx: ShellServices, from: ActivationSource) { seen += from }
        override fun open(target: String): Boolean = true
        override fun saveState(): JsonObject = JsonObject(emptyMap())
        override fun restoreState(state: JsonObject) {}
    }

    private class Rig(scope: CoroutineScope, tmp: Path, val input: NoticeInput?, prepare: (Persistence) -> Unit = {}) {
        val store = Persistence(tmp.resolve("s.json")).also(prepare)
        val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
        val shell = Shell(FakeText(), transport, store, null, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
        val probe = Probe()

        init {
            shell.register(probe)
            shell.noticeInput = input
        }
    }

    private suspend fun settle(shell: Shell) {
        waitUntil(10_000, 10, { "shell did not settle: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
    }

    private fun value(occ: String, body: String, raisedAt: Long, thread: String): JsonObject = buildJsonObject {
        put("occ", occ); put("raisedAt", raisedAt); put("app", "torrents"); put("source", "torrent"); put("body", body)
        put("urgency", Notices.NORMAL); put("gatedBy", "Torrents"); put("target", "t:$thread"); put("state", Notices.RAISED)
    }

    private fun rec(thread: String, occ: String = "1", body: String = "done · made-up $thread", raisedAt: Long = RAISED_AT): NoticeRecord =
        Notices.parse("torrent:$thread", value(occ, body, raisedAt, thread))!!

    /** H:MM of [ms] on this machine's clock, as the shell formats a time. */
    private fun hhmm(ms: Long): String {
        val t = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
        return "%d:%02d".format(if (t.hour % 12 == 0) 12 else t.hour % 12, t.minute)
    }

    private fun test(name: String, input: NoticeInput?, prepare: (Persistence) -> Unit = {}, body: suspend (Rig) -> Unit): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-notice-$name")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val r = Rig(scope, tmp, input, prepare)
            r.shell.start(); settle(r.shell)
            body(r)
            r.shell.stop()
        } finally {
            scope.cancel(); tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun aRecordNoticeIsTheBoxTheAppsOwnRaiseShowedAndBothAreOneBox() = test("same", Input()) { r ->
        val n = rec("h1")
        r.shell.services.notifyInternal("torrent", "done · made-up h1", appId = "torrents", thread = "h1", target = "t:h1")
        settle(r.shell)
        r.shell.noticeFromRecord(n)
        settle(r.shell)
        val cur = assertNotNull(r.shell.notifications.current)
        assertEquals(0, r.shell.notifications.queueDepth, "the app's own raise and the record's coalesce: one box")
        assertEquals(n.ref, cur.record)
        assertEquals(Notifications.Notice("DAMAGE · torrent", "h1", "done · made-up h1", hhmm(RAISED_AT),
            appId = "torrents", target = "t:h1"), cur.copy(record = null, read = false),
            "the box's source, thread, body, app, target and time are today's")
    }

    @Test
    fun aDoubleTapSendsDismiss() = test("dbl", Input()) { r ->
        val input = r.input as Input
        r.shell.noticeFromRecord(rec("h2"))
        settle(r.shell)
        delay(3_000); settle(r.shell)                              // the grace: the box takes the gestures
        assertTrue(r.shell.notifications.focused)
        r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
        settle(r.shell)
        assertEquals(listOf(NoticeRef("torrent:h2", "1")), input.dismissed, "a double-tap dismisses on every screen")
        assertEquals(emptyList(), input.reads)
    }

    @Test
    fun theTapThroughSendsReadAndOpensTheApp() = test("tap", Input()) { r ->
        val input = r.input as Input
        r.shell.noticeFromRecord(rec("h3"))
        settle(r.shell)
        delay(3_000); settle(r.shell)
        r.shell.postGesture(EvenHubMsg.EV_CLICK)
        settle(r.shell)
        assertEquals("torrents", r.shell.currentWindowId(), "tap = commit + open at the item")
        assertEquals(listOf(NoticeRef("torrent:h3", "1")), input.reads, "the tap-through reads, once")
        assertEquals(emptyList(), input.dismissed)
    }

    @Test
    fun silentModesDropSendsNothing() = test("silent", Input()) { r ->
        val input = r.input as Input
        r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)              // Main → Silent Mode (§1.4)
        settle(r.shell)
        r.shell.noticeFromRecord(rec("h4"))
        settle(r.shell)
        assertTrue(r.shell.notifications.active, "the small form shows it")
        waitUntil(10_000, 50, { "the small box did not go in 5 s" }) { !r.shell.notifications.active }
        settle(r.shell)
        assertEquals(0, input.sent, "Silent Mode's 5 s drop sends nothing (C§7.2)")
    }

    @Test
    fun anActivationFromInputReadsTheAppsRecordNotices() = test("act", Input()) { r ->
        val input = r.input as Input
        r.shell.noticeFromRecord(rec("h5"))
        r.shell.noticeFromRecord(rec("h6"))
        settle(r.shell)
        r.shell.services.runOnShell { r.shell.services.openWindow("torrents") }
        settle(r.shell)
        assertEquals("torrents", r.shell.currentWindowId())
        assertEquals(setOf(NoticeRef("torrent:h5", "1"), NoticeRef("torrent:h6", "1")), input.reads.toSet(),
            "an activation from input reads the app's unread notices on every screen")
        assertEquals(0, r.shell.notifications.queueDepth, "the queued one leaves, as today")
    }

    @Test
    fun aRestoreActivationReadsNothing() = test("restore", Input(), prepare = { s -> s.put("shell.state", savedState("h7")) }) { r ->
        val input = r.input as Input
        assertEquals("torrents", r.shell.currentWindowId(), "the window came back")
        assertEquals(ActivationSource.RESTORE, r.probe.seen.first())
        assertEquals(listOf(NoticeRef("torrent:h7", "7")), input.asked, "the restore asked the record about its notice")
        assertEquals(emptyList(), input.reads, "a RESTORE is no input: nothing read")
        val all = listOfNotNull(r.shell.notifications.current) + r.shell.notifications.queued()
        assertEquals(listOf(NoticeRef("torrent:h7", "7")), all.map { it.record }, "the restored notice is still there")
    }

    @Test
    fun aRestoredNoticeTheRecordNoLongerHoldsIsNotShownAgain() =
        test("gone", Input(live = { emptySet() }), prepare = { s -> s.put("shell.state", savedState("h8")) }) { r ->
            assertNull(r.shell.notifications.current, "dismissed elsewhere meanwhile: not shown again")
            assertEquals(0, r.shell.notifications.queueDepth)
        }

    @Test
    fun aMoveElsewhereTakesTheBoxAway() = test("withdraw", Input()) { r ->
        val input = r.input as Input
        val a = rec("h9")
        val b = rec("h10")
        val c = rec("h11")
        r.shell.noticeFromRecord(a)
        r.shell.noticeFromRecord(b)
        r.shell.noticeFromRecord(c)
        settle(r.shell)
        assertEquals(a.ref, r.shell.notifications.current?.record)
        assertEquals(2, r.shell.notifications.queueDepth)
        r.shell.withdrawNotice(b.ref, queuedOnly = false)
        settle(r.shell)
        assertEquals(listOf(c.ref), r.shell.notifications.queued().map { it.record }, "a queued entry leaves")
        r.shell.withdrawNotice(a.ref, queuedOnly = true)
        settle(r.shell)
        assertEquals(a.ref, r.shell.notifications.current?.record, "read elsewhere: the shown box stays")
        r.shell.withdrawNotice(a.ref, queuedOnly = false)
        settle(r.shell)
        waitUntil(5_000, 20, { "the box did not fold away" }) { r.shell.notifications.current?.record != a.ref }
        settle(r.shell)
        assertEquals(c.ref, r.shell.notifications.current?.record, "dismissed elsewhere: folded away, the next shows")
        assertEquals(0, input.sent, "a move elsewhere sends nothing back")
    }

    @Test
    fun aMoveElsewhereDropsSilentModesSmallBox() = test("withdraw-silent", Input()) { r ->
        r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)              // Main → Silent Mode
        settle(r.shell)
        val a = rec("h12")
        r.shell.noticeFromRecord(a)
        settle(r.shell)
        assertTrue(r.shell.notifications.active)
        // read on the loop in the turn right after the withdraw, before any frame of an animation: one evaluation
        val seen = CompletableDeferred<Pair<Boolean, Boolean>>()
        r.shell.withdrawNotice(a.ref, queuedOnly = false)
        r.shell.services.runOnShell { seen.complete(r.shell.notifications.active to r.shell.notifications.furlingOut) }
        val (active, furling) = seen.await()
        assertFalse(furling, "Silent Mode's small box is dropped, never furled (silent has no furl, §1.5)")
        assertFalse(active, "the small form is gone at once")
        settle(r.shell)
        assertFalse(r.shell.notifications.active)
    }

    @Test
    fun theWholePathOnAPcRecord(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-notice-path")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recDir = Files.createDirectories(tmp.resolve("record"))
        val host = AppHost.inScratchDir(recDir)
        try {
            val core = NoticeCore(host)
            val mirror = NoticeMirror(HostBackend(host, "local", "s-local"))
            val r = Rig(scope, tmp, mirror)
            mirror.attach(r.shell.noticeSink)
            r.shell.start(); settle(r.shell)
            core.raise("torrent", "h13", "done · made-up h13", "torrents", "t:h13", "Torrents")
            waitUntil(10_000, 20, { "the raise did not reach the glasses" }) { r.shell.notifications.current?.record != null }
            settle(r.shell)
            assertEquals("done · made-up h13", r.shell.notifications.current?.body)
            delay(3_000); settle(r.shell)
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            settle(r.shell)
            waitUntil(10_000, 20, { "the dismiss did not reach the record" }) {
                (host.read(Notices.WINDOW).third.items["rec.notices.torrent:h13"]?.value as? JsonObject)
                    ?.let { Notices.parse("torrent:h13", it)?.state } == Notices.DISMISSED
            }
            r.shell.stop()
        } finally {
            host.close(); host.executor.shutdown()
            scope.cancel(); tmp.toFile().deleteRecursively()
        }
    }

    private fun savedState(thread: String): JsonObject = buildJsonObject {
        put("current", "torrents")
        put("mode", "WINDOW")
        put("notices", buildJsonArray {
            add(buildJsonObject {
                put("source", "DAMAGE · torrent"); put("thread", thread); put("body", "done · made-up $thread")
                put("time", "12:00"); put("emergency", false); put("appId", "torrents"); put("target", "t:$thread")
                put("gatedBy", ""); put("read", false); put("recordId", "torrent:$thread"); put("recordOcc", "7")
            })
        })
    }

    private companion object {
        /** 2026-11-15 18:07 UTC, a made-up raise. */
        const val RAISED_AT = 1_763_230_020_000L
    }
}
