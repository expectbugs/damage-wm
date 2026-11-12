package wm.damage.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `--epub-check DIR` (0.65 P7 item 9, green-gate GG-8): the battery line's step read the config's booksDir
 * whatever DIR said, and passed over zero books — a step green over a shelf it never read. It reads DIR now
 * and says no over zero books and over a DIR that is not a directory. Every directory here is a temporary
 * one; nothing reads the real shelf, and the program is never started (the status comes from
 * [epubCheckStatus], which `main` hands to `exitProcess`).
 */
class EpubCheckTest {

    @Test
    fun zeroBooksAndAPathThatIsNotADirectoryAreNotAPass() {
        val tmp = Files.createTempDirectory("epub-check")
        try {
            val empty = Files.createDirectories(tmp.resolve("empty"))
            val said = ArrayList<String>()
            assertNotEquals(0, epubCheckStatus(empty) { said.add(it) }, "zero books is not a pass: $said")
            assertNotEquals(0, epubCheckStatus(tmp.resolve("absent")) { said.add(it) }, "an absent DIR is not a pass: $said")
            val file = Files.writeString(tmp.resolve("a-file"), "x")
            assertNotEquals(0, epubCheckStatus(file) { said.add(it) }, "a file is not a DIR: $said")
        } finally { tmp.toFile().deleteRecursively() }
    }

    @Test
    fun aShelfInDirIsReadAndPasses() {
        val tmp = Files.createTempDirectory("epub-check-shelf")
        try {
            Files.writeString(tmp.resolve("book.txt"), buildString {
                appendLine("CHAPTER I"); appendLine()
                for (i in 1..40) { appendLine("Paragraph $i of a book in a temporary directory."); appendLine() }
            })
            val said = ArrayList<String>()
            assertEquals(0, epubCheckStatus(tmp) { said.add(it) }, "one good book passes: $said")
            assertTrue(said.first() == "1 books in $tmp", "the count names DIR: $said")
        } finally { tmp.toFile().deleteRecursively() }
    }
}
