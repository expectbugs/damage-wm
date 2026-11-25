package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue
import wm.damage.core.app.AppHost
import wm.damage.core.app.CoreExecutor
import wm.damage.core.windows.files.CountedFiles
import wm.damage.core.windows.files.FLocation
import wm.damage.core.windows.files.FilesProgress
import wm.damage.core.windows.files.FilesProvider
import wm.damage.core.windows.files.LocalFilesProvider

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q5: what Files' tests share. Every provider is rooted in a directory the test creates under the
 * system temp directory, its trash inside that root; its locations are that root's folders only (never a real one);
 * and every call that moves, trashes, deletes or writes proves first that each path it is about to touch lies under
 * the test's own root ([SafeFiles], asserted). Nothing here reaches a real path, the real trash or the network.
 */
internal class FilesTree : AutoCloseable {
    val root: Path = Files.createTempDirectory("damage-files-q5").toRealPath()
    val home: Path = Files.createDirectories(root.resolve("home"))
    val books: Path = Files.createDirectories(root.resolve("books"))
    val trash: Path = root.resolve("trash")
    /** Stage OP: the providers' "Open on PC" — a test opens nothing on the PC; [close] fails the test that reached it. */
    val pc = NoPcOpen()
    val local = LocalFilesProvider(books, trash, opener = pc, decoder = null, mountsFile = root.resolve("no-mounts"))
    val files = SafeFiles(root, local, listOf(FLocation("Home", home.toString(), "home", 1_000_000, 400_000),
        FLocation("Books", books.toString(), "books", 1_000_000, 400_000)))

    /** A made-up file under the home folder: its path. */
    fun file(rel: String, text: String = "made-up text\n"): String {
        val p = home.resolve(rel)
        Files.createDirectories(p.parent)
        Files.writeString(p, text)
        return p.toString()
    }

    fun dir(rel: String): String = Files.createDirectories(home.resolve(rel)).toString()

    override fun close() {
        root.toFile().deleteRecursively()
        pc.assertNone()
    }
}

/** [local] behind a guard: the locations are [locs] alone, and every destructive call asserts its paths are under
 *  [root] before it runs (the hard stop of build S1-2 Q5: a test proves its root before a destructive step). */
internal class SafeFiles(private val root: Path, private val local: LocalFilesProvider, private val locs: List<FLocation>) :
    FilesProvider by local, CountedFiles {
    private fun under(p: String): String {
        assertTrue(Path.of(p).toAbsolutePath().normalize().startsWith(root), "a destructive step outside the test's own root: $p")
        return p
    }

    override fun locations(): List<FLocation> = locs + local.locations().filter { it.kind == "trash" && it.path.startsWith(root.toString()) }
    override fun copy(src: String, destDir: String) = local.copy(under(src), under(destDir))
    override fun move(src: String, destDir: String) = local.move(under(src), under(destDir))
    override fun trash(path: String) = local.trash(under(path))
    override fun restore(id: String) = local.restore(id.also { i -> under(local.trashList().first { it.id == i }.origPath) })
    override fun purge(id: String) = local.purge(id.also { assertTrue(local.trashList().any { e -> e.id == id && e.origPath.startsWith(root.toString()) }, "purge of a trash entry the test did not make: $id") })
    override fun rename(path: String, newName: String) = local.rename(under(path), newName)
    override fun mkdir(dir: String, name: String) = local.mkdir(under(dir), name)
    override fun copy(src: String, destDir: String, progress: FilesProgress) = local.copy(under(src), under(destDir), progress)
    override fun move(src: String, destDir: String, progress: FilesProgress) = local.move(under(src), under(destDir), progress)
    override fun du(path: String, progress: FilesProgress) = local.du(path, progress)
}

/** A PC record of Files' test in [dir] (a new directory), opened. */
internal fun filesRecord(dir: Path): AppHost {
    val h = AppHost(Files.createDirectories(dir), AppHost.WINDOWS, CoreExecutor("damage-core-files-test"))
    h.open()
    return h
}
