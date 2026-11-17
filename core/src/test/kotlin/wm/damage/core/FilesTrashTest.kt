package wm.damage.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.app.Levels
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.app.Peer
import wm.damage.core.app.ProgramResult
import wm.damage.core.shell.Persistence
import wm.damage.core.web.Uploads
import wm.damage.core.windows.files.FilesCore
import wm.damage.core.windows.files.FilesJobs
import wm.damage.core.windows.files.FilesProgram
import wm.damage.core.windows.files.FilesProgress
import wm.damage.core.windows.files.LocalFilesProvider
import wm.damage.core.windows.files.SlottedFiles

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build L1 (the ruling's "Files, uploads, thumbnails", full protocol): the trash's manifest. B-S6 — a manifest that
 * cannot be read refuses trash, restore and delete forever with the reason and is never overwritten; a manifest write
 * that fails moves the file back and says where it is. B-M2 — nothing renames, moves, copies into, makes a folder in or
 * uploads into the trash's own folder. Every file is under the test's own temp root ([FilesTree]); its trash is there.
 */
class FilesTrashTest {
    private val tree = FilesTree()

    @AfterTest
    fun tearDown() = tree.close()

    private val manifest: Path get() = tree.trash.resolve("manifest.json")

    /** A provider on the test's own tree, with no manifest read yet, and [write] as its manifest writer. */
    private fun provider(write: (Path, String) -> Unit = { f, t -> Persistence.writeAtomic(f, t, sync = true) }) =
        LocalFilesProvider(tree.books, tree.trash, decoder = null, mountsFile = tree.root.resolve("no-mounts"), manifestWriter = write)

    /** Pin (B-S6): an unreadable manifest refuses trash, restore and delete forever with the reason; nothing moves and
     *  the manifest stays as it was. */
    @Test
    fun anUnreadableManifestRefusesTrashRestoreAndPurge_andIsNeverOverwritten() {
        val a = tree.file("a.txt")
        val b = tree.file("b.txt")
        val id = tree.files.trash(a)
        val bytes = "{ \"entries\": [ made-up damage".toByteArray()
        Files.write(manifest, bytes)
        val p = provider()
        assertTrue(Path.of(b).startsWith(tree.root) && tree.trash.startsWith(tree.root), "the test's own root")
        val e = assertFailsWith<IllegalStateException> { p.trash(b) }
        assertTrue("cannot be read" in e.message!! && "refused" in e.message!!, e.message)
        assertTrue(Files.exists(Path.of(b)), "nothing moved")
        assertTrue("cannot be read" in assertFailsWith<IllegalStateException> { p.restore(id) }.message!!)
        assertTrue("cannot be read" in assertFailsWith<IllegalStateException> { p.purge(id) }.message!!)
        assertContentEquals(bytes, Files.readAllBytes(manifest), "the manifest is as it was")
        assertTrue(Files.exists(tree.trash.resolve(id).resolve("a.txt")), "the trashed file is where it was")
        assertEquals(listOf(id), Files.list(tree.trash).use { s -> s.filter { Files.isDirectory(it) }.map { it.fileName.toString() }.toList() })
        assertTrue(p.locations().any { it.kind == "trash" }, "the Trash shows, so opening it says why")
        assertFailsWith<IllegalStateException> { p.trashList() }
    }

    /** Pin (B-S6): a manifest write that fails moves the file back — a trashed one to its place, a restored one into
     *  the trash where the manifest still names it — and says so. */
    @Test
    fun aFailedManifestWriteMovesTheFileBack() {
        var fail = false
        val p = provider { f, t -> if (fail) throw IOException("made-up full disk") else Persistence.writeAtomic(f, t, sync = true) }
        val a = tree.file("a.txt", "kept")
        val b = tree.file("b.txt")
        assertTrue(Path.of(a).startsWith(tree.root) && Path.of(b).startsWith(tree.root), "the test's own root")
        val id = p.trash(a)
        fail = true
        val e = assertFailsWith<IllegalStateException> { p.trash(b) }
        assertTrue("made-up full disk" in e.message!! && "moved back to $b" in e.message!!, e.message)
        assertTrue(Files.exists(Path.of(b)), "b is back in its place")
        assertEquals(listOf(id), Files.list(tree.trash).use { s -> s.filter { Files.isDirectory(it) }.map { it.fileName.toString() }.toList() },
            "no slot is left for b")
        val r = assertFailsWith<IllegalStateException> { p.restore(id) }
        assertTrue("moved back" in r.message!!, r.message)
        assertTrue(Files.exists(tree.trash.resolve(id).resolve("a.txt")) && !Files.exists(Path.of(a)), "a is in the trash, as its manifest says")
        fail = false
        assertEquals(a, p.restore(id))
        assertEquals("kept", Files.readString(Path.of(a)))
        assertEquals(emptyList(), p.trashList())
    }

    /** Pin (B-M2, the PC half): no rename, move (in or out), copy into, new folder or upload at or under the trash's own
     *  folder — each refused with the reason — and the trash restores as before. */
    @Test
    fun theTrashFolderIsNeverWrittenByAnotherOp() {
        val a = tree.file("a.txt")
        val c = tree.file("c.txt")
        val id = tree.files.trash(a)
        val slot = tree.trash.resolve(id).toString()
        val payload = tree.trash.resolve(id).resolve("a.txt").toString()
        fun refused(what: String, f: () -> Unit) {
            val e = assertFailsWith<IllegalArgumentException>(what) { f() }
            assertTrue("inside the trash's own folder" in e.message!!, "$what: ${e.message}")
        }
        refused("rename the manifest") { tree.files.rename(manifest.toString(), "m.json") }
        refused("rename a trashed file") { tree.files.rename(payload, "b.txt") }
        refused("rename the trash itself") { tree.files.rename(tree.trash.toString(), "t") }
        refused("a new folder") { tree.files.mkdir(slot, "x") }
        refused("a new folder named through ..") { tree.files.mkdir("${tree.home}/../trash", "x") }
        refused("copy into") { tree.files.copy(c, tree.trash.toString()) }
        refused("counted copy into") { tree.local.copy(c, slot, FilesProgress.NONE) }
        refused("move into") { tree.files.move(c, slot) }
        refused("move a trashed file out") { tree.files.move(payload, tree.home.toString()) }
        refused("trash a trashed file") { tree.files.trash(payload) }
        val jobs = FilesJobs()
        val up = FilesProgram(SlottedFiles(tree.files, jobs), Uploads(null)).act(Op("web-l1", OpId.Conn(7, 1), FilesCore.WINDOW,
            FilesCore.UPLOAD, listOf(slot), params = buildJsonObject {
                put("file", buildJsonObject { put("name", "in.bin"); put("size", 1) })
            }, at = 1), Peer("web-l1", Levels.FULL))
        assertTrue(up is ProgramResult.Refused && "inside the trash's own folder" in up.reason, "an upload: $up")
        assertNull(jobs.current(), "refused before the slot was taken")
        assertTrue(Files.exists(Path.of(c)) && Files.exists(Path.of(payload)) && Files.exists(manifest))
        assertEquals(listOf(id), tree.files.trashList().map { it.id })
        assertEquals(a, tree.files.restore(id))
    }
}
