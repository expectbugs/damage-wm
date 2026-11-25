package wm.damage.desktop

import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import wm.damage.core.app.LocalWindowSource
import wm.damage.core.app.MemoryRecord
import wm.damage.core.app.SourceListener
import wm.damage.core.app.WindowSource
import wm.damage.core.shell.Persistence
import wm.damage.core.windows.feed.FeedCore
import wm.damage.core.windows.feed.FeedProvider

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-5, stage S (the decisions' "Stage G accepted"): `DesktopStack` declared four of its window fields with
 * `= null` AFTER the `init` block that assigns them. Kotlin runs property initializers and `init` blocks in the order
 * written, so the fields were null again when the constructor ended and the stop's `detach()` calls acted on nothing:
 * a stopped stack's presenters stayed on the process-wide sources and providers. Pinned here on Feed (its presenter
 * joins its source in its constructor); watched to fail on the unfixed tree.
 */
class DesktopStackTest {

    /** A source that counts its listeners (a real source in memory underneath). */
    private class Counting(private val inner: WindowSource) : WindowSource by inner {
        val listeners = CopyOnWriteArrayList<SourceListener>()
        override fun addListener(l: SourceListener) { listeners += l; inner.addListener(l) }
        override fun removeListener(l: SourceListener) { listeners -= l; inner.removeListener(l) }
    }

    /** A feed provider that answers every call with an empty value (no source, no item); the presenter is never
     *  activated here, so no call needs a real answer. */
    private fun emptyFeed(): FeedProvider = java.lang.reflect.Proxy.newProxyInstance(FeedProvider::class.java.classLoader,
        arrayOf(FeedProvider::class.java)) { _, m, _ ->
        when (m.returnType) {
            String::class.java -> ""
            List::class.java -> emptyList<Any>()
            java.lang.Boolean.TYPE -> false
            Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            else -> null
        }
    } as FeedProvider

    @Test
    fun aStoppedStacksWindowsLeaveTheirSources(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-stack")
        try {
            val cfg = Config(booksDir = Files.createDirectories(tmp.resolve("books")).toString(), dataDir = tmp.toString())
            val feed = emptyFeed()
            val core = FeedCore()
            val source = Counting(LocalWindowSource(core, MemoryRecord(FeedCore.ACTIONS)))
            val stack = DesktopStack(cfg, "sim", null, AwtText(), onStatus = {}, onSwitch = {},
                sharedStore = Persistence(tmp.resolve("state.json")), feed = feed, feedCore = core to source)
            assertEquals(1, source.listeners.size, "Feed's presenter joined its source in its constructor")
            stack.stop()
            assertEquals(0, source.listeners.size, "a stopped stack's Feed presenter left the source, which outlives the stack")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
