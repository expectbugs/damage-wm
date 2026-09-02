package wm.damage.core

import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.shell.Notifications
import wm.damage.core.windows.music.MediaServer

/**
 * Review round 9 (2026-09-02, the second whole-codebase pass).
 *
 * [notificationSourceNeverOverprintsTheQueueBadge] is a REGRESSION test: it
 * fails against the code as it stood (504 px of the badge/clock cell changed
 * with a longer source), because the box drew `n.source` unbounded and every
 * internal source — "DAMAGE · compositor" and friends — is longer than the
 * room before the `+N` badge.
 *
 * [mediaServerServesTheExactRequestedRange] pins a CONTRACT rather than
 * catching a live defect: the old implementation seeked with a `skip()` loop
 * that breaks on a short skip and would then have streamed from the wrong
 * offset under its own `Content-Range` header. `ChannelInputStream.skip`
 * happens to seek fully for a regular file, so the old code passed this test
 * too — the defect was latent. The test stays because "the bytes you asked
 * for are the bytes you get" is worth pinning under whichever implementation
 * sits beneath it.
 */
class Round9Test {

    /** Everything but the source held identical, so any pixel difference in
     *  the badge/clock cell is the source overprinting it. */
    private fun renderNoticeBox(source: String): Pair<Gray8, Rect> {
        val n = Notifications(FakeText())
        val l = Layout()
        n.post(Notifications.Notice(source, "t1", "the body", "14:32"), l)
        n.post(Notifications.Notice("SMS · SECOND", "t2", "the body", "14:32"), l)  // one queued: the badge draws
        repeat(6) { n.stepUnfurl(l, silent = false) }                                // unfurl to full
        val g = Gray8(Geometry.PANEL_W, Geometry.PANEL_H)
        val box = n.paint(g, l, silent = false)!!
        return g to box
    }

    @Test
    fun notificationSourceNeverOverprintsTheQueueBadge() {
        val (long, box) = renderNoticeBox("DAMAGE · compositor · a source far longer than its cell")
        val (short, _) = renderNoticeBox("SMS · A")
        assertTrue(box.w > 0 && box.h > 0)
        // the badge cell: from where "+N" is drawn to the box's right edge
        val x0 = box.x + box.w / 2 - 12
        var diff = 0
        for (y in box.y until minOf(box.y + 20, box.bottom)) {
            for (x in x0 until box.right) if (long[x, y] != short[x, y]) diff++
        }
        assertEquals(0, diff, "$diff px of the queue badge / clock cell changed with a longer " +
            "source — the source line is not being fitted to the room before them")
    }

    @Test
    fun mediaServerServesTheExactRequestedRange() {
        val tmp = Files.createTempDirectory("damage-r9-media")
        try {
            val body = ByteArray(300_000) { (it * 31 % 251).toByte() }
            val f = tmp.resolve("track.opus")
            Files.write(f, body)
            val port = ServerSocket(0).use { it.localPort }
            val srv = MediaServer(port, "tok") { _, _ -> f to "audio/ogg" }
            srv.start()
            try {
                val first = 123_457L                       // deliberately not a block boundary
                val last = 250_000L
                val (code, got) = httpRange(port, "/track/7?token=tok", first, last)
                assertEquals("206", code, "a Range request must answer 206")
                assertEquals((last - first + 1).toInt(), got.size, "wrong body length")
                assertTrue(got.contentEquals(body.copyOfRange(first.toInt(), (last + 1).toInt())),
                    "the served bytes are not the requested range — the endpoint streamed from " +
                        "the wrong offset under its own Content-Range header")
            } finally {
                srv.close()
            }
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /** Minimal HTTP/1.1 client: the status code and the raw body. */
    private fun httpRange(port: Int, target: String, first: Long, last: Long): Pair<String, ByteArray> =
        java.net.Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write("GET $target HTTP/1.1\r\nHost: x\r\nRange: bytes=$first-$last\r\n\r\n"
                .toByteArray(Charsets.ISO_8859_1))
            s.getOutputStream().flush()
            val all = s.getInputStream().readBytes()
            val sep = indexOfCrLfCrLf(all)
            assertTrue(sep >= 0, "no header/body separator in the answer")
            String(all, 0, sep, Charsets.ISO_8859_1).split(' ')[1] to all.copyOfRange(sep + 4, all.size)
        }

    private fun indexOfCrLfCrLf(b: ByteArray): Int {
        for (i in 0..b.size - 4) {
            if (b[i] == 13.toByte() && b[i + 1] == 10.toByte() &&
                b[i + 2] == 13.toByte() && b[i + 3] == 10.toByte()) return i
        }
        return -1
    }
}
