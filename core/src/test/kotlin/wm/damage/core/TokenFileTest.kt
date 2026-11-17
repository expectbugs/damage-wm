package wm.damage.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import wm.damage.core.bastion.HaReader
import wm.damage.core.util.Log

/**
 * Build W, package A (T28): the hub's token is read from a file Adam already keeps (dec. A.6). Every case is
 * answered in plain words — missing, empty, white space only, unreadable, more than one token — the text is
 * trimmed, and no case's words carry it and nothing is logged. Temp directories and made-up tokens only.
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
        val said = ArrayList<String>()
        /** The case's words, or the token for a usable file. */
        fun read(p: Path): String = try {
            HaReader.readTokenFile(p)
        } catch (e: IOException) {
            e.message!!.also { said += it }
        }

        assertEquals("does not exist", read(dir.resolve("absent")))
        assertEquals("does not exist", read(dir.resolve("no-such-dir").resolve("token")))
        assertEquals("is empty", read(file("empty", "")))
        assertEquals("is empty", read(file("blank", "  \n\t \r\n ")))
        assertEquals(TOKEN, read(file("ok", "  $TOKEN  \n")), "the text is trimmed")
        assertEquals(TOKEN, read(file("bom", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "$TOKEN\n".toByteArray())),
            "a leading byte-order mark is not part of the token")
        // not one token: two lines, a pasted header line, a non-ASCII character
        for (text in listOf("$TOKEN\n$TOKEN-2\n", "Bearer $TOKEN", TOKEN + 'é')) {
            assertTrue(read(file("not-one", text)).startsWith("does not hold one token"), text)
        }
        // unreadable: a directory, bytes that are not UTF-8, and (when not run as root) a file nobody may read
        assertTrue(read(Files.createDirectory(dir.resolve("a-directory"))).startsWith("could not be read ("))
        assertTrue(read(file("not-utf8", byteArrayOf(0xC3.toByte(), 0x28, 0x41))).startsWith("could not be read ("))
        if (System.getProperty("user.name") != "root") {
            val locked = file("locked", TOKEN)
            assertTrue(locked.toFile().setReadable(false, false))
            assertEquals("could not be read (AccessDeniedException)", read(locked), "the exception's class name only")
        }

        for (a in said) assertFalse(TOKEN in a, "an answer's text carries the token: $a")
        for (l in logs) assertFalse(TOKEN in l, "a log line carries the token: $l")
        assertTrue(logs.none { "tokenfile" in it || "readTokenFile" in it }, "readTokenFile logged: $logs")
    }

    private companion object {
        const val TOKEN = "made-up.TEST_token-0123456789abcdef"
    }
}
