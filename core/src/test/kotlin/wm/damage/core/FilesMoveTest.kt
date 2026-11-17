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
        LocalFilesProvider(tree.books, tree.trash, decoder = null, mountsFile = tree.root.resolve("no-mounts"), sameVolume = sameVolume)

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
}
