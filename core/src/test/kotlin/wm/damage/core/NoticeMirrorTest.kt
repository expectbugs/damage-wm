package wm.damage.core

import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wm.damage.core.app.Answer
import wm.damage.core.app.CoreExecutor
import wm.damage.core.app.Cursor
import wm.damage.core.app.Draft
import wm.damage.core.app.Item
import wm.damage.core.app.NoticeMirror
import wm.damage.core.app.NoticeRecord
import wm.damage.core.app.NoticeRef
import wm.damage.core.app.NoticeSink
import wm.damage.core.app.Notices
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.app.RecordBackend
import wm.damage.core.app.SourceListener
import wm.damage.core.app.WindowState

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q3 (the spec's §2.1, §5 N2–N5; `CONTRACT.md` §7.3; S1-2 R0 §2): one host's notice mirror over a
 * made-up record — first contact and a new epoch adopt, a held record waits for its end, a move elsewhere is withdrawn,
 * nothing pops while the window is not shared, the phone's cursor survives a restart, a loss is let go unsaid, and the
 * shell's reading effects carry the record's value. A backend of the test's own stands for the record.
 */
class NoticeMirrorTest {
    /** The record as the mirror sees it: one window's items and the cursor, changed by the test. */
    private class Backend : RecordBackend {
        override val executor = CoreExecutor("damage-core-mirror-test")
        var items: Map<String, Item> = emptyMap()
        var cursor: Cursor? = Cursor(7, 1)
        val submitted = CopyOnWriteArrayList<Draft>()
        private val watchers = ArrayList<SourceListener>()
        override fun stateOf(window: String) = WindowState(window, items, cursor = cursor)
        override fun submit(window: String, draft: Draft) { submitted += draft }
        override fun watch(window: String, l: SourceListener) { watchers += l }
        override fun unwatch(window: String, l: SourceListener) { watchers -= l }

        /** The record moves: every watcher is told on the executor; returns once it was. */
        fun move(f: Backend.() -> Unit) = executor.call {
            f()
            val s = stateOf(Notices.WINDOW)
            for (l in watchers.toList()) l.changed(s, s.items.keys)
        }

        fun answer(op: Op, a: Answer) = executor.call { for (l in watchers.toList()) l.answered(op, a) }
    }

    private class Sink : NoticeSink {
        val popped = CopyOnWriteArrayList<String>()
        val withdrawn = CopyOnWriteArrayList<String>()
        override fun pop(rec: NoticeRecord) { popped += "${rec.id}@${rec.occ}" }
        override fun withdraw(ref: NoticeRef, queuedOnly: Boolean) { withdrawn += "${ref.id}@${ref.occ}${if (queuedOnly) " queued" else ""}" }
    }

    private val backends = ArrayList<Backend>()
    private val dir = Files.createTempDirectory("damage-notice-mirror")

    @AfterTest
    fun cleanUp() {
        for (b in backends) b.executor.shutdown()
        dir.toFile().deleteRecursively()
    }

    private fun backend() = Backend().also { backends += it }

    private fun value(occ: Long, state: String = Notices.RAISED, heldUntil: Long? = null): JsonObject = buildJsonObject {
        put("occ", occ.toString()); put("raisedAt", 1000L * occ); put("app", "torrents"); put("source", "torrent")
        put("body", "done · item $occ"); put("urgency", Notices.NORMAL); put("gatedBy", "Torrents"); put("target", "t:$occ")
        put("state", state)
        if (heldUntil != null) put("heldUntil", heldUntil)
    }

    private fun rec(vararg n: Pair<String, JsonObject>): Map<String, Item> =
        n.associate { (id, v) -> "rec.notices.$id" to Item(v, "pc", 0) }

    private fun settle(b: Backend) = repeat(2) { b.executor.call {} }

    @Test
    fun firstContactAdoptsAndPopsNothingThenANewRaisePops() {
        val b = backend()
        b.items = rec("torrent:a" to value(1), "torrent:b" to value(2))
        val m = NoticeMirror(b)
        val s = Sink()
        m.attach(s)
        settle(b)
        assertEquals(emptyList(), s.popped, "first contact adopts and lists, never pops old records (C§7.3)")
        assertEquals(NoticeMirror.Cursor(7, 2), m.cursor)
        b.move { items = items + rec("torrent:c" to value(3)) }
        assertEquals(listOf("torrent:c@3"), s.popped, "a record raised past the cursor pops")
        b.move { cursor = Cursor(8, 1); items = rec("torrent:a" to value(1), "torrent:b" to value(2), "torrent:c" to value(3), "torrent:d" to value(4)) }
        assertEquals(listOf("torrent:c@3"), s.popped, "a new epoch adopts again: nothing pops")
        assertEquals(NoticeMirror.Cursor(8, 4), m.cursor)
    }

    @Test
    fun aHeldRecordWaitsForItsEndAndTheHeldOnesPopTogether() {
        val b = backend()
        var now = 10_000L
        val m = NoticeMirror(b, clock = { now })
        val s = Sink()
        m.attach(s)
        settle(b)
        b.move { items = rec("torrent:a" to value(1, heldUntil = 50_000), "torrent:b" to value(2, heldUntil = 50_000), "torrent:c" to value(3)) }
        assertEquals(listOf("torrent:c@3"), s.popped, "quiet hours hold a NORMAL record (its heldUntil); the unheld one pops")
        assertEquals(0L, m.cursor!!.occ, "the cursor never passes a record still held")
        now = 50_000
        m.minute()
        settle(b)
        assertEquals(listOf("torrent:c@3", "torrent:a@1", "torrent:b@2"), s.popped, "released together at the end, in order")
        assertEquals(3L, m.cursor!!.occ)
    }

    @Test
    fun aRecordHandedOnThatMovesElsewhereIsWithdrawn() {
        val b = backend()
        val m = NoticeMirror(b)
        val s = Sink()
        m.attach(s)
        settle(b)
        b.move { items = rec("torrent:a" to value(1), "torrent:b" to value(2), "torrent:c" to value(3)) }
        assertEquals(3, s.popped.size)
        b.move { items = items + rec("torrent:a" to value(1, Notices.DISMISSED)) }
        assertEquals(listOf("torrent:a@1"), s.withdrawn, "dismissed elsewhere: the box is taken away")
        b.move { items = items + rec("torrent:b" to value(2, Notices.READ)) }
        assertEquals(listOf("torrent:a@1", "torrent:b@2 queued"), s.withdrawn, "read elsewhere: queued ones leave, the shown stays")
        b.move { items = items + rec("torrent:c" to value(4)) }
        assertEquals(listOf("torrent:a@1", "torrent:b@2 queued"), s.withdrawn, "a newer occurrence is a new box, not a withdraw")
        assertEquals("torrent:c@4", s.popped.last())
        b.move { items = items + rec("torrent:b" to value(2, Notices.DISMISSED)) }
        assertEquals("torrent:b@2", s.withdrawn.last(), "a read one dismissed later is taken away too")
    }

    @Test
    fun nothingIsAdoptedOrPoppedWhileTheWindowIsNotShared() {
        val b = backend()
        var shared = false
        val m = NoticeMirror(b, shared = { shared })
        val s = Sink()
        m.attach(s)
        settle(b)
        b.move { cursor = Cursor(7, 5) }                             // the PC's state, with no notices (not shared)
        assertEquals(null, m.cursor, "an older PC shares no notices: no contact yet")
        shared = true
        b.move { items = rec("torrent:a" to value(1), "torrent:b" to value(2)) }
        assertEquals(emptyList(), s.popped, "the first state that shares the window is the first contact")
        assertEquals(2L, m.cursor!!.occ)
    }

    @Test
    fun thePhonesCursorSurvivesARestart() {
        val file = dir.resolve("workcopy").resolve(NoticeMirror.CURSOR_FILE)
        val now = Executor { it.run() }
        val b1 = backend()
        b1.items = rec("torrent:a" to value(1))
        val m1 = NoticeMirror(b1, cursorFile = file, writer = now)
        m1.attach(Sink())
        settle(b1)
        b1.move { items = items + rec("torrent:b" to value(2)) }
        assertTrue(Files.exists(file), "the cursor is written beside the copy")
        val b2 = backend()
        b2.items = b1.items + rec("torrent:c" to value(3))
        val m2 = NoticeMirror(b2, cursorFile = file, writer = now)
        val s2 = Sink()
        m2.attach(s2)
        settle(b2)
        assertEquals(listOf("torrent:c@3"), s2.popped, "after a restart only what was raised past the saved cursor pops")
    }

    @Test
    fun theShellsReadingEffectsCarryTheRecordsValue() {
        val b = backend()
        b.items = rec("torrent:a" to value(1), "torrent:b" to value(2))
        val m = NoticeMirror(b)
        settle(b)
        m.dismiss(NoticeRef("torrent:a", "1"))
        m.read(NoticeRef("torrent:b", "2"))
        m.dismiss(NoticeRef("torrent:b", "1"))                      // an occurrence the record no longer holds
        settle(b)
        assertEquals(listOf(Notices.DISMISS, Notices.MARK_READ), b.submitted.map { it.action }, "a stale ref sends nothing")
        val d = b.submitted[0]
        assertEquals(Notices.DISMISSED, (d.result as JsonObject)["state"]!!.jsonPrimitive.content)
        assertEquals("done · item 1", (d.result as JsonObject)["body"]!!.jsonPrimitive.content, "the whole value, state changed")
        assertEquals("1", (d.result as JsonObject)["occ"]!!.jsonPrimitive.content, "the occurrence it acts on, in its value")
    }

    @Test
    fun restoredNoticesAreKeptOnlyWhileTheRecordHoldsThemRaised() {
        val b = backend()
        b.items = rec("torrent:a" to value(1), "torrent:b" to value(2, Notices.DISMISSED), "torrent:c" to value(4))
        val m = NoticeMirror(b)
        settle(b)
        val kept = m.restored(listOf(NoticeRef("torrent:a", "1"), NoticeRef("torrent:b", "2"), NoticeRef("torrent:c", "3")))
        assertEquals(setOf(NoticeRef("torrent:a", "1")), kept, "dismissed meanwhile, or a newer occurrence: not shown again")
        val s = Sink()
        m.attach(s)
        b.move { items = items + rec("torrent:a" to value(1, Notices.DISMISSED)) }
        assertEquals(listOf("torrent:a@1"), s.withdrawn, "a kept one is handed on: a later dismiss elsewhere takes it away")
    }

    @Test
    fun aValueWithNoOccurrenceAsTextIsSaidAndNotShown() {
        val b = backend()
        val said = CopyOnWriteArrayList<String>()
        val m = NoticeMirror(b, say = { said += it })
        val s = Sink()
        m.attach(s)
        settle(b)
        val numeric = JsonObject(value(1) + ("occ" to JsonPrimitive(1)))
        b.move { items = rec("torrent:n" to numeric) }
        assertEquals(emptyList(), s.popped)
        assertTrue(said.any { "torrent:n" in it && "occurrence" in it }, "said, never dropped unsaid: $said")
    }
}
