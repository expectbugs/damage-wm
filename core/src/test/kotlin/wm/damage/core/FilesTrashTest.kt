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
 * that fails moves nothing (a trash: its row is written first, L1 R1 B1) or moves the file back (a restore) and says
 * so. B1 — a trash writes its row before it moves the file; a failed move takes the row away again; a row whose file is
 * not in the trash is listed as such, refuses Restore with the reason, and Delete forever clears it. B-M2 — nothing
 * renames, moves, copies into, makes a folder in or uploads into the trash's own folder. Every file is under the
 * test's own temp root ([FilesTree]); its trash is there.
 */
class FilesTrashTest {
    private val tree = FilesTree()

    @AfterTest
    fun tearDown() = tree.close()

    private val manifest: Path get() = tree.trash.resolve("manifest.json")

    /** A provider on the test's own tree, with no manifest read yet, [write] as its manifest writer and [sameVolume]
     *  as its volume test. */
    private fun provider(write: (Path, String) -> Unit = { f, t -> Persistence.writeAtomic(f, t, sync = true) },
                         sameVolume: (Path, Path) -> Boolean = { _, _ -> true }) =
        LocalFilesProvider(tree.books, tree.trash, opener = tree.pc, decoder = null, mountsFile = tree.root.resolve("no-mounts"),
            manifestWriter = write, sameVolume = sameVolume)

    private fun slots(): List<String> =
        Files.list(tree.trash).use { s -> s.filter { Files.isDirectory(it) }.map { it.fileName.toString() }.toList() }

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

    /** Pin (B-S6, L1 R1 B1): a manifest write that fails moves nothing — a trash's row is written before its file
     *  moves — or moves the file back — a restored one into the trash where the manifest still names it — and says so. */
    @Test
    fun aFailedManifestWriteMovesNothingOrMovesTheFileBack() {
        var fail = false
        val p = provider({ f, t -> if (fail) throw IOException("made-up full disk") else Persistence.writeAtomic(f, t, sync = true) })
        val a = tree.file("a.txt", "kept")
        val b = tree.file("b.txt")
        assertTrue(Path.of(a).startsWith(tree.root) && Path.of(b).startsWith(tree.root), "the test's own root")
        val id = p.trash(a)
        fail = true
        val e = assertFailsWith<IllegalStateException> { p.trash(b) }
        assertTrue("made-up full disk" in e.message!! && "nothing was moved" in e.message!!, e.message)
        assertTrue(Files.exists(Path.of(b)), "b is in its place")
        assertEquals(listOf(id), slots(), "no slot is made for b")
        val r = assertFailsWith<IllegalStateException> { p.restore(id) }
        assertTrue("moved back" in r.message!!, r.message)
        assertTrue(Files.exists(tree.trash.resolve(id).resolve("a.txt")) && !Files.exists(Path.of(a)), "a is in the trash, as its manifest says")
        fail = false
        assertEquals(a, p.restore(id))
        assertEquals("kept", Files.readString(Path.of(a)))
        assertEquals(emptyList(), p.trashList())
    }

    /** Pin (L1 R1 B1): the row is written while the file is still in its place, then the file moves; a move that fails
     *  takes the row away again and leaves no slot. */
    @Test
    fun theRowIsWrittenBeforeTheFileMoves_andAFailedMoveTakesItAway() {
        val seen = ArrayList<Pair<Boolean, String>>()                  // (b in its place at the write, the manifest)
        val b = tree.file("b.txt")
        val far = tree.file("far/x.txt")
        val p = provider({ f, t -> seen += Files.exists(Path.of(b)) to t; Persistence.writeAtomic(f, t, sync = true) },
            sameVolume = { a, _ -> if (a.endsWith("far")) throw IOException("made-up: that volume cannot be read") else false })
        val id = p.trash(b)                                            // across volumes (the seam): copied, named, deleted
        assertTrue(seen.first().first && "b.txt" in seen.first().second, "the row first, b still in its place: $seen")
        assertTrue(Files.exists(tree.trash.resolve(id).resolve("b.txt")) && !Files.exists(Path.of(b)))
        assertTrue(Path.of(far).startsWith(tree.root), "the test's own root")
        // the move fails after the row was written: the row goes again
        val e = assertFailsWith<IOException> { p.trash(far) }
        assertTrue("made-up" in e.message!!, e.message)
        assertTrue(Files.exists(Path.of(far)), "x.txt is in its place")
        assertEquals(listOf(id), p.trashList().map { it.id }, "its row was taken away")
        assertEquals(listOf(id), provider().trashList().map { it.id }, "on disk too")
        assertEquals(listOf(id), slots(), "no slot is left for it")
    }

    /** Pin (L1 R1 B1): a stop between the row and the move (here: the move fails and the row cannot be taken away)
     *  leaves a row whose file is not in the trash — a partial temporary copy beside it at most. It is listed as such,
     *  Restore refuses with the reason and moves nothing, and Delete forever clears the row and the slot. */
    @Test
    fun aRowWhoseFileIsNotInTheTrash_isListedAsSuch_andDeleteForeverClearsIt() {
        var fail = false
        val c = tree.file("c.txt", "his")
        val p = provider({ f, t -> if (fail) throw IOException("made-up stop") else Persistence.writeAtomic(f, t, sync = true) },
            sameVolume = { _, _ -> fail = true; throw IOException("made-up cut") })
        val e = assertFailsWith<IllegalStateException> { p.trash(c) }
        assertTrue("its row stays" in e.message!! && "made-up cut" in e.message!!, e.message)
        fail = false
        val row = p.trashList().single()
        assertTrue(row.missing, "listed as not in the trash: $row")
        val slot = tree.trash.resolve(row.id)
        Files.createDirectories(slot)
        Files.writeString(slot.resolve(".damage-move-1-1.part"), "h")  // what a cross-volume copy cut off leaves
        assertTrue(provider().trashList().single().missing, "a partial temporary copy is not the file")
        val r = assertFailsWith<IllegalArgumentException> { p.restore(row.id) }
        assertTrue("not in the trash" in r.message!!, r.message)
        assertEquals("his", Files.readString(Path.of(c)), "c is in its place, untouched")
        assertTrue(Files.exists(slot.resolve(".damage-move-1-1.part")), "Restore moved nothing")
        p.purge(row.id)
        assertEquals(emptyList(), p.trashList())
        assertTrue(!Files.exists(slot), "the slot and the partial copy are gone")
        assertEquals("his", Files.readString(Path.of(c)))
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
