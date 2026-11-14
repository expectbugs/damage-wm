package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.After
import wm.damage.core.bastion.HaReader
import wm.damage.core.bastion.HaReader.TokenRead
import wm.damage.core.util.Log

/**
 * Build W, package A (T28): the hub's token is read from a file Adam already keeps (dec. A.6). Every case is
 * answered — missing, empty, white space only, unreadable, more than one token — the text is trimmed, and
 * nothing that comes back or is logged carries it. Temp directories and made-up tokens only.
 */
class TokenFileTest {

    private val dir: Path = Files.createTempDirectory("damage-tokenfile")
    private val logs = CopyOnWriteArrayList<String>()
    private val sink = Log.Sink { level, tag, message -> logs += "$level $tag $message" }

    @After
    fun tearDown() {
        Log.removeSink(sink)
        dir.toFile().setReadable(true)
        dir.toFile().walkBottomUp().forEach { it.setReadable(true); it.delete() }
    }

    private fun file(name: String, bytes: ByteArray): Path = dir.resolve(name).also { Files.write(it, bytes) }
    private fun file(name: String, text: String): Path = file(name, text.toByteArray(Charsets.UTF_8))

    @Test
    fun everyCaseIsAnsweredAndNothingCarriesTheText() {
        Log.addSink(sink)
        val answers = ArrayList<TokenRead>()
        fun read(p: Path): TokenRead = HaReader.readTokenFile(p).also { answers += it }

        assertIs<TokenRead.Missing>(read(dir.resolve("absent")))
        assertIs<TokenRead.Missing>(read(dir.resolve("no-such-dir").resolve("token")))
        assertIs<TokenRead.Empty>(read(file("empty", "")))
        assertIs<TokenRead.Empty>(read(file("blank", "  \n\t \r\n ")))

        val ok = read(file("ok", "  $TOKEN  \n"))
        assertIs<TokenRead.Ok>(ok)
        assertEquals(TOKEN, ok.token, "the text is trimmed")
        val bom = read(file("bom", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "$TOKEN\n".toByteArray()))
        assertIs<TokenRead.Ok>(bom)
        assertEquals(TOKEN, bom.token, "a leading byte-order mark is not part of the token")

        // not one token: two lines, a pasted header line, a non-ASCII character
        assertIs<TokenRead.Malformed>(read(file("two-lines", "$TOKEN\n$TOKEN-2\n")))
        assertIs<TokenRead.Malformed>(read(file("header", "Bearer $TOKEN")))
        assertIs<TokenRead.Malformed>(read(file("non-ascii", TOKEN + 'é')))

        // unreadable: a directory, bytes that are not UTF-8, and (when not run as root) a file nobody may read
        val asDir = read(Files.createDirectory(dir.resolve("a-directory")))
        assertIs<TokenRead.Unreadable>(asDir)
        assertTrue(asDir.cls.isNotEmpty())
        val notUtf8 = read(file("not-utf8", byteArrayOf(0xC3.toByte(), 0x28, 0x41)))
        assertIs<TokenRead.Unreadable>(notUtf8)
        if (System.getProperty("user.name") != "root") {
            val locked = file("locked", TOKEN)
            assertTrue(locked.toFile().setReadable(false, false))
            val r = read(locked)
            assertIs<TokenRead.Unreadable>(r)
            assertEquals("AccessDeniedException", r.cls, "the exception's class name only")
        }

        for (a in answers) assertFalse(TOKEN in a.toString(), "an answer's text carries the token: $a")
        for (l in logs) assertFalse(TOKEN in l, "a log line carries the token: $l")
        assertTrue(logs.none { "tokenfile" in it || "readTokenFile" in it }, "readTokenFile logged: $logs")
    }

    private companion object {
        const val TOKEN = "made-up.TEST_token-0123456789abcdef"
    }
}
