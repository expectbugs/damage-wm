package wm.damage.core

import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wm.damage.core.app.Answer
import wm.damage.core.app.AppHost
import wm.damage.core.app.Levels
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.app.Peer
import wm.damage.core.app.Record
import wm.damage.core.app.Windows
import wm.damage.core.shell.Persistence
import wm.damage.core.windows.files.FilesCore
import wm.damage.core.windows.files.FilesJobs
import wm.damage.core.windows.files.FilesPc
import wm.damage.core.windows.files.FilesProvider
import wm.damage.core.windows.files.FilesService

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q5 (the spec's §4.1; `CONTRACT.md` §10.4, §12; S1-2 R0 §3): Files' core. Files is full as a
 * whole and its ops into the filesystem never queue; its rows take only their own values; the description carries
 * the locations, the slot, the actions and the Size row; the cut's clipboard is cleared in ONE place, the core, when
 * a paste's move lands — from the web's op, the PC's glasses and the phone's glasses alike — and only while the
 * clipboard still names the clip that paste took. A temp tree and a record in it; nothing else.
 */
class FilesCoreTest {
    private val tree = FilesTree()
    private val hosts = ArrayList<AppHost>()

    @AfterTest
    fun tearDown() {
        for (h in hosts) try { h.close(); h.executor.shutdown() } catch (e: Exception) { /* the next closes all the same */ }
        tree.close()
    }

    private fun host(): AppHost = filesRecord(tree.root.resolve("record-${hosts.size}")).also { hosts += it }

    private val n = HashMap<String, Long>()

    /** An op of a made-up device on [h], answered. */
    private fun act(h: AppHost, action: String, target: String, result: JsonElement, device: String = "web-q5"): Answer {
        val got = CopyOnWriteArrayList<Answer>()
        val k = (n[device] ?: 0L) + 1
        n[device] = k
        h.act(Op(device, OpId.Queued("s-$device", k), FilesCore.WINDOW, action, listOf(target), result, at = System.currentTimeMillis())) { got += it }
        h.executor.call {}
        return got.single()
    }

    private fun item(h: AppHost, name: String) = h.read(FilesCore.WINDOW).third.items[Record.key(FilesCore.WINDOW, name)]

    @Test
    fun filesIsFullAsAWhole_andItsFilesystemOpsNeverQueue() {
        assertEquals(Levels.FULL, Windows.minLevel(FilesCore.WINDOW))
        for (a in listOf("open", "openInReader", "download", "copy", "cut", "sort", "hidden", "set", "migrate") + FilesCore.PROGRAM)
            assertEquals(Levels.FULL, FilesCore.levelOf(a), a)
        assertNull(FilesCore.levelOf("nothing"))
        for (a in FilesCore.PROGRAM) {
            assertTrue(Windows.isProgram(FilesCore.WINDOW, a), a)
            assertNull(FilesCore.ACTIONS.spec(FilesCore.WINDOW, a), "$a is not judged by the record")
        }
        assertFalse(Windows.isProgram(FilesCore.WINDOW, FilesCore.COPY), "the clipboard is a record row")
        assertEquals(1, Windows.VERSIONS[FilesCore.WINDOW])
    }

    @Test
    fun theDescriptionCarriesLocationsTheSlotTheActionsAndTheSizeRow() {
        val pc = FilesPc(host(), tree.files, tree.books.toString(), null, null)
        val d = pc.scopes.describe("desc/files")!!
        assertEquals("files", d["id"]!!.jsonPrimitive.content)
        assertEquals("full", d["min"]!!.jsonPrimitive.content)
        assertEquals("live", d["availability"]!!.jsonPrimitive.content)
        assertEquals(tree.home.toString(), d["home"]!!.jsonPrimitive.content)
        val locs = (d["locations"] as JsonArray).map { it.jsonObject }
        assertEquals(listOf("Home", "Books"), locs.map { it["label"]!!.jsonPrimitive.content })
        assertEquals(setOf("path", "label", "kind", "total", "free"), locs[0].keys)
        val ids = (d["actions"] as JsonArray).map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("open", "openInReader", "download", "openOnPc", "copy", "cut", "sort", "hidden", "paste", "rename",
            "mkdir", "upload", "trash", "restore", "purge", "stats"), ids, "the page's action ids (Q5w's shape 4)")
        assertTrue((d["actions"] as JsonArray).all { it.jsonObject["level"]!!.jsonPrimitive.content == "full" })
        val size = (d["settings"] as JsonArray).single().jsonObject
        assertEquals("glasses.size", size["id"]!!.jsonPrimitive.content)
        assertEquals("set", size["action"]!!.jsonPrimitive.content)
        assertEquals(listOf("global", "288", "352", "416", "480"),
            (size["options"] as JsonArray).map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(JsonArray(emptyList()), d["jobs"])
        pc.slotted.mkdir(tree.home.toString(), "made")
        val job = (pc.scopes.describe("desc/files")!!["jobs"] as JsonArray).single().jsonObject
        assertEquals("done", job["state"]!!.jsonPrimitive.content)
        assertEquals("New folder 'made'", job["label"]!!.jsonPrimitive.content)
    }

    /** Pin (the spec's Q5: "a pasted cut clears a clip changed meanwhile"; S1-2 R0 §3: one place, the core). */
    @Test
    fun aPastedCutClearsTheClipboardOnlyWhileItStillNamesThatClip() {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val held = object : FilesProvider by tree.files {
            override fun move(src: String, destDir: String): String {
                entered.countDown()
                assertTrue(gate.await(10, TimeUnit.SECONDS), "the test's own bound on its held move")
                return tree.files.move(src, destDir)
            }
        }
        val h = host()
        val pc = FilesPc(h, held, tree.books.toString(), null, null)
        val a = tree.file("a.txt")
        val b = tree.file("b.txt")
        val dest = tree.dir("dest")
        assertTrue(act(h, "cut", "clip", FilesCore.clipJson("cut", a)) is Answer.Seen)
        val r = pc.program.act(Op("web-q5", OpId.Conn(3, 1), FilesCore.WINDOW, FilesCore.PASTE, listOf(dest),
            params = buildJsonObject { put("verb", "cut"); put("path", a) }, at = 1), Peer("web-q5", Levels.FULL))
        assertTrue(r is wm.damage.core.app.ProgramResult.Sent, "$r")
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        // the clipboard moves on while the move runs (another device copies b)
        assertTrue(act(h, "copy", "clip", FilesCore.clipJson("copy", b), device = "phone") is Answer.Seen)
        gate.countDown()
        eventually("the paste's job ended") { pc.jobs.current()?.state == FilesJobs.DONE }
        Thread.sleep(200)
        h.executor.call {}
        assertEquals(FilesCore.clipJson("copy", b), item(h, "clip")?.value, "a clip changed meanwhile wins, nothing said")
        assertTrue(Files.exists(tree.home.resolve("dest").resolve("a.txt")))
    }

    /** S1-2 R0 §3: the web's paste, the PC's glasses' call and the phone's glasses' call each leave the clear to the
     *  core, which clears a cut naming what moved — and never a copy. */
    @Test
    fun theCoreClearsACutAfterEveryCallersMove_neverACopy() {
        val h = host()
        val pc = FilesPc(h, tree.files, tree.books.toString(), null, null)
        val dest = tree.dir("dest")
        fun clip() = item(h, "clip")?.value
        // the PC's glasses: today's call on the slotted provider
        val a = tree.file("a.txt")
        act(h, "cut", "clip", FilesCore.clipJson("cut", a), device = "local")
        pc.slotted.move(a, dest)
        eventually("cleared after the glasses' move") { clip() == JsonNull }
        assertEquals(FilesPc.DEVICE, item(h, "clip")!!.by, "the core writes the clear")
        // the phone's glasses: through the window channel
        val b = tree.file("b.txt")
        act(h, "cut", "clip", FilesCore.clipJson("cut", b), device = "phone")
        FilesService(pc.slotted).request("move", buildJsonObject { put("src", b); put("dest", dest) })
        eventually("cleared after the phone's move") { clip() == JsonNull }
        // the web's paste: a program op, the job's move
        val w = tree.file("w.txt")
        act(h, "cut", "clip", FilesCore.clipJson("cut", w), device = "web-q5")
        val r = pc.program.act(Op("web-q5", OpId.Conn(9, 1), FilesCore.WINDOW, FilesCore.PASTE, listOf(dest),
            params = buildJsonObject { put("verb", "cut"); put("path", w) }, at = 1), Peer("web-q5", Levels.FULL))
        assertTrue(r is wm.damage.core.app.ProgramResult.Sent, "$r")
        eventually("cleared after the web's paste") { clip() == JsonNull }
        // a copy's paste leaves the clipboard as it is
        val c = tree.file("c.txt")
        act(h, "copy", "clip", FilesCore.clipJson("copy", c), device = "local")
        pc.slotted.copy(c, dest)
        Thread.sleep(200)
        assertEquals(FilesCore.clipJson("copy", c), clip())
    }

    @Test
    fun filesKeysLeaveTheSyncChannel() {
        assertFalse(Persistence.syncable("window.files"), "Files is converted: its local row never syncs")
        assertTrue(Persistence.syncable("window.settings"), "an unconverted window still syncs")   // S1-5 G converted Games
    }
}
