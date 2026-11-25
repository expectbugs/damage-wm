package wm.damage.core

import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import wm.damage.core.windows.files.FilesProgress
import wm.damage.core.windows.files.LocalFilesProvider

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build L1, the rulings on the review of the paths that move or delete his files (L1 R1 B2, B3, B4; full protocol).
 * B2: a file moved across volumes is copied by Damage to a hidden temporary name beside its destination, forced to
 * disk, named only where no file has that name, and only then is its source deleted; a failure deletes only what the
 * move made. B3: a folder copy makes its destination itself, so its cleanup deletes only what it made. B4: a manifest
 * the trash cannot stat is never read as "absent". "Across volumes" is the provider's injected volume test; every file
 * is under the test's own temp root ([FilesTree]).
 */
class FilesMoveTest {
    private val tree = FilesTree()

    @AfterTest
    fun tearDown() = tree.close()

    private fun provider(sameVolume: (Path, Path) -> Boolean) =
        LocalFilesProvider(tree.books, tree.trash, opener = tree.pc, decoder = null, mountsFile = tree.root.resolve("no-mounts"), sameVolume = sameVolume)

    private fun temps(dir: String): List<String> =
        Files.list(Path.of(dir)).use { s -> s.map { it.fileName.toString() }.filter { it.startsWith(".damage-move-") }.toList() }

    /** The file modes bind this test's user (they do not bind root). */
    private fun modesBind() = assumeTrue("file modes do not bind root", System.getProperty("user.name") != "root")

    /** Pin (B2): across volumes a file arrives whole under its name, its source goes, and no temporary name stays — by
     *  a move, a trash and a restore. */
    @Test
    fun aFileMovedAcrossVolumesArrivesWhole_andItsSourceGoes() {
        val p = provider { _, _ -> false }
        val text = "x".repeat(70_000)
        val a = tree.file("a/one.txt", text)
        val dest = tree.dir("b")
        assertTrue(Path.of(a).startsWith(tree.root) && Path.of(dest).startsWith(tree.root), "the test's own root")
        val moved = p.move(a, dest)
        assertEquals(text, Files.readString(Path.of(moved)))
        assertFalse(Files.exists(Path.of(a)), "the source is gone")
        assertEquals(emptyList(), temps(dest))
        val id = p.trash(moved)
        assertEquals(emptyList(), temps(tree.trash.resolve(id).toString()))
        assertEquals(moved, p.restore(id))
        assertEquals(text, Files.readString(Path.of(moved)))
        assertEquals(emptyList(), temps(dest))
    }

    /** Pin (B2): a name that appears at the destination after the move's checks is never replaced, and the source is
     *  deleted only once its copy is named — so both files stay, and no temporary name. */
    @Test
    fun aNameThatAppearsBeforeThePlacementIsNeverReplaced_andTheSourceStays() {
        val a = tree.file("a/one.txt", "mine")
        val dest = tree.dir("b")
        val his = Path.of(dest).resolve("one.txt")
        val p = provider { _, _ -> Files.writeString(his, "his"); false }  // the name is made just after the checks
        assertFailsWith<FileAlreadyExistsException> { p.move(a, dest) }
        assertEquals("his", Files.readString(his), "his file is untouched")
        assertEquals("mine", Files.readString(Path.of(a)), "the source stays")
        assertEquals(emptyList(), temps(dest))
    }

    /** Pin (B2): a source that cannot be deleted once its copy is named fails the move, and the copy — the move made
     *  it — is deleted: the file is in one place, its own. */
    @Test
    fun aSourceThatCannotBeDeletedLeavesNoCopy() {
        modesBind()
        val p = provider { _, _ -> false }
        val a = tree.file("ro/one.txt", "mine")
        val dest = tree.dir("b")
        val ro = Path.of(a).parent
        Files.setPosixFilePermissions(ro, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            assertFailsWith<AccessDeniedException> { p.move(a, dest) }
        } finally {
            Files.setPosixFilePermissions(ro, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
        assertEquals("mine", Files.readString(Path.of(a)))
        assertFalse(Files.exists(Path.of(dest).resolve("one.txt"), LinkOption.NOFOLLOW_LINKS), "the move's copy is gone")
        assertEquals(emptyList(), temps(dest))
    }

    /** Pin (B2): a symbolic link moves across volumes AS a link (Fi#14), even one whose target is gone: nothing
     *  follows it, and nothing forces what it names. */
    @Test
    fun aLinkMovesAcrossVolumesAsALink() {
        val p = provider { _, _ -> false }
        val l = Files.createDirectories(tree.home.resolve("l")).resolve("link")
        Files.createSymbolicLink(l, Path.of("no-such-target"))
        val dest = tree.dir("b")
        val moved = Path.of(p.move(l.toString(), dest))
        assertTrue(Files.isSymbolicLink(moved))
        assertEquals(Path.of("no-such-target"), Files.readSymbolicLink(moved))
        assertFalse(Files.exists(l, LinkOption.NOFOLLOW_LINKS))
        assertEquals(emptyList(), temps(dest))
    }

    /** Pin (B3): a folder that appears at a copy's destination while the copy counts its source is neither written nor
     *  deleted: the copy makes its destination itself and refuses a name that exists. */
    @Test
    fun aFolderThatAppearsDuringTheCountIsNeverDeleted() {
        tree.file("src/a.txt", "theirs")
        tree.file("src/z.txt")
        val src = tree.home.resolve("src").toString()
        val into = tree.dir("into")
        val his = Path.of(into).resolve("src")
        var made = false
        assertFailsWith<FileAlreadyExistsException> {
            tree.local.copy(src, into, FilesProgress { _, _, phase ->
                if (phase == "counting" && !made) {                   // made by someone else meanwhile
                    made = true
                    Files.createDirectories(his)
                    Files.writeString(his.resolve("a.txt"), "his")
                }
            })
        }
        assertEquals("his", Files.readString(his.resolve("a.txt")), "his folder and his file are untouched")
        assertEquals(listOf("a.txt"), Files.list(his).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    /** Pin (L1 R2 A5, and B2's temporary name): a copy of one file, and a file moved across volumes, never show a
     *  partial file under the real name — the bytes go to a hidden temporary name, and the name appears once they are
     *  whole (a stop mid-copy leaves the temporary name, never a cut-off file he would take for his). The source is a
     *  named pipe the test feeds, so each is seen with half its bytes in; the pipe is opened read-write, so neither
     *  side's open waits for the other. */
    @Test
    fun aSingleFileCopyShowsNoPartialFileUnderItsName() = kotlinx.coroutines.runBlocking {
        val across = provider { _, _ -> false }
        for ((what, act) in listOf<Pair<String, (String, String) -> String>>(
                "copy" to { s, d -> tree.files.copy(s, d) }, "move" to { s, d -> across.move(s, d) })) {
            val src = Files.createDirectories(tree.home.resolve("src-$what")).resolve("slow.bin")
            assumeTrue("mkfifo made the pipe", ProcessBuilder("mkfifo", src.toString()).start().waitFor() == 0)
            val into = Path.of(tree.dir("into-$what"))
            val dest = into.resolve("slow.bin")
            val first = ByteArray(4096) { 1 }
            val rest = ByteArray(4096) { 2 }
            val result = java.util.concurrent.CompletableFuture<String>()
            java.io.RandomAccessFile(src.toFile(), "rw").use { pipe ->
                Thread({ try { result.complete(act(src.toString(), into.toString())) } catch (e: Throwable) { result.completeExceptionally(e) } },
                    "a5-$what").apply { isDaemon = true; start() }
                pipe.write(first)
                fun inFolder() = Files.list(into).use { s -> s.toList() }
                waitUntil(10_000, 5, { "the $what holds the first bytes somewhere in ${inFolder()} ($result)" }) {
                    inFolder().any { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && Files.size(it) >= first.size } }
                assertFalse(Files.exists(dest, LinkOption.NOFOLLOW_LINKS), "$what: no file under its real name while its bytes are still coming")
                pipe.write(rest)
            }                                                      // the only writer closes: the copy reads its end
            val made = Path.of(result.get(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(dest, made)
            assertContentEquals(first + rest, Files.readAllBytes(made), what)
            assertEquals(listOf("slow.bin"), Files.list(into).use { s -> s.map { it.fileName.toString() }.toList() }, "$what: no temporary name stays")
        }
    }

    /** Pin (B4): a manifest the trash cannot stat (its folder's search permission gone) is not read as empty: trash is
     *  refused with the reason, the listing says why instead of showing an empty trash, and the manifest is as it was. */
    @Test
    fun aManifestThatCannotBeStatedIsNotReadAsEmpty() {
        modesBind()
        val id = tree.files.trash(tree.file("a.txt"))
        val b = tree.file("b.txt")
        val manifest = tree.trash.resolve("manifest.json")
        val bytes = Files.readAllBytes(manifest)
        val p = provider { _, _ -> true }
        Files.setPosixFilePermissions(tree.trash, PosixFilePermissions.fromString("rw-------"))
        try {
            val e = assertFailsWith<IllegalStateException> { p.trash(b) }
            assertTrue("cannot be read" in e.message!!, e.message)
            val l = assertFailsWith<IllegalStateException> { p.trashList() }
            assertTrue("cannot be read" in l.message!!, l.message)
        } finally {
            Files.setPosixFilePermissions(tree.trash, PosixFilePermissions.fromString("rwx------"))
        }
        assertTrue(Files.exists(Path.of(b)), "b is in its place")
        assertContentEquals(bytes, Files.readAllBytes(manifest), "the manifest is as it was")
        assertEquals(listOf(id), p.trashList().map { it.id })
    }
}
