package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import wm.damage.core.gfx.IconKind
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.wire.EvenHubMsg

/**
 * APK 0.65, P6 item 3 (the loop-cost sweep's LC-6, step 2): `saveAsync` encoded the whole store (219 KB on the PC's
 * copy, 2026-11-10) on the caller — the shell loop, two seconds after every burst of gestures — and wrote it on the
 * writer thread; the loop was held about 100–150 ms there (measured indirectly from the gaps in message starts).
 * Fixed: the records are copied under the store's lock (key, value, stamp) and encoded on the writer thread, inside
 * the code that reports whether the save landed — an encode that throws there is a save that did not land and is
 * said as one (`CLAUDE.md`: no silent failures), not a task that ends with no word. `save()` (the shutdown form)
 * encodes on its caller, under its `try` since R4 item 2. Every store here is in a temporary directory.
 */
class SaveCostTest {
    private val tmp: Path = Files.createTempDirectory("save-cost")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest fun clean() { scope.cancel(); tmp.toFile().deleteRecursively() }

    /** A record whose entries say which thread read them, and throw when read on the store's writer thread while
     *  [failOnWriter] is set — the encoder walks them; nothing else of the store does on that thread. */
    private class Watched(private val failOnWriter: Boolean = false) : AbstractMap<String, JsonElement>() {
        val readers = CopyOnWriteArrayList<String>()
        private val inner = mapOf<String, JsonElement>("k" to JsonPrimitive("v"))
        override val entries: Set<Map.Entry<String, JsonElement>> get() {
            val t = Thread.currentThread().name
            readers.add(t)
            if (failOnWriter && t == "damage-persist") throw IllegalStateException("the encoder met a record it cannot walk")
            return inner.entries
        }
    }

    /** A record that throws whenever the encoder walks it, on any thread, once [armed]. */
    private class Unwalkable : AbstractMap<String, JsonElement>() {
        @Volatile var armed = false
        override val entries: Set<Map.Entry<String, JsonElement>> get() {
            if (armed) throw IllegalStateException("the encoder met a record it cannot walk")
            return mapOf<String, JsonElement>("k" to JsonPrimitive("v")).entries
        }
    }

    /** R4 item 2 (b) (review 3A-3): `save()` — the shutdown's form — encoded outside its `try`, so an encode that
     *  threw left the save as a throw, out of the final save and the `Shutdown` arm, where `saveAsync` (P6 item 3)
     *  answers "did not land". Fixed: the encode is under the `try`; the save returns false and the log says so.
     *  Unfixed: `save()` throws. */
    @Test
    fun aSynchronousSaveWhoseEncodeThrowsReturnsFalseAndSaysSo() {
        val store = Persistence(tmp.resolve("state.json"))
        val rec = Unwalkable()
        store.put("window.unwalkable", JsonObject(rec))
        rec.armed = true
        val said = CopyOnWriteArrayList<String>()
        val sink = wm.damage.core.util.Log.Sink { _, tag, message -> said.add("$tag: $message") }
        wm.damage.core.util.Log.addSink(sink)
        val landed = try { store.save() } finally { wm.damage.core.util.Log.removeSink(sink) }
        assertEquals(false, landed, "an encode that throws is a save that did not land")
        assertTrue(said.any { it.startsWith("persist: state save FAILED") }, "the log says so: $said")
    }

    /** The finding's second pin: the store is encoded on its writer thread, not on the caller. */
    @Test
    fun anAsynchronousSaveEncodesOnTheWritersThread(): Unit = runBlocking {
        val store = Persistence(tmp.resolve("state.json"))
        val w = Watched()
        store.put("window.watched", JsonObject(w))
        w.readers.clear()
        val landed = CompletableDeferred<Boolean>()
        store.saveAsync { landed.complete(it) }
        assertTrue(landed.await(), "the save landed")
        assertTrue(w.readers.isNotEmpty() && w.readers.all { it == "damage-persist" },
            "the record was walked on the writer's thread only: ${w.readers}")
        assertTrue("\"window.watched\"" in Files.readString(tmp.resolve("state.json")), "the record is in the file")
    }

    /** The challenge's addition: an encode that throws on the writer thread is a save that did not land — the shell
     *  raises "state save FAILED". Unfixed (the encode on the loop) nothing throws and nothing is said; with the
     *  encode on the writer thread outside the reporting code, the task ends with no verdict and no notice. */
    @Test
    fun anEncodeThatThrowsOnTheWriterIsASaveThatDidNotLand(): Unit = runBlocking {
        val w = Watched(failOnWriter = true)
        val win = object : DamageWindow("watched", "Watched", IconKind.FILES) {
            override fun view(): WindowView = WindowView.CanvasView({ _, _ -> })
            override fun summary() = Summary("watched")
            override fun saveState(): JsonObject = JsonObject(w)
            override fun restoreState(state: JsonObject) {}
        }
        val shell = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true)),
            Persistence(tmp.resolve("state.json")), tmp.resolve("journal.jsonl"), scope)
        shell.register(win)
        val urgent = CopyOnWriteArrayList<String>()
        shell.onUrgent = { source, body -> urgent.add("$source: $body") }
        shell.start()
        waitUntil(30_000, message = { "the session settles: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
        shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)          // a gesture: its save follows two seconds later
        waitUntil(15_000, message = { "the failed save is said: $urgent; read on ${w.readers.distinct()}" }) {
            urgent.any { it.startsWith("state: state save FAILED") }
        }
        assertEquals(0, urgent.count { it.startsWith("loop:") }, "the loop handled the save without an error: $urgent")
        shell.stop()
    }
}
