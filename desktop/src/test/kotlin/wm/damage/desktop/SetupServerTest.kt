package wm.damage.desktop

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §44 — the setup page moved from G2CC's server into the `damage` service:
 * same URL, same token, same gate. Pinned: the page links the APK with the
 * token; the APK route refuses without it, serves the staged bytes under a
 * stamped filename with it, answers HEAD with the headers alone, says
 * "not staged" honestly; and an arrival on a non-Tailscale interface is a
 * 403, never the page.
 */
class SetupServerTest {

    private fun get(url: String, method: String = "GET", bearer: String? = null): Triple<Int, Map<String, String>, ByteArray> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
        val code = c.responseCode
        val headers = c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.joinToString(",") }
        val body = try { (if (code >= 400) c.errorStream else c.inputStream)?.readBytes() ?: ByteArray(0) } catch (e: Exception) { ByteArray(0) }
        c.disconnect()
        return Triple(code, headers, body)
    }

    private fun <T> serving(apk: Path, allowed: (InetAddress) -> Boolean = { true }, block: (SetupServer, String) -> T): T {
        val s = SetupServer(0, "tok-en", apk, allowed)
        s.start()
        try { return block(s, "http://127.0.0.1:${s.boundPort}") } finally { s.close() }
    }

    @Test
    fun thePageLinksTheApkWithTheTokenAndSaysWhatIsStaged() {
        val dir = Files.createTempDirectory("setup")
        val apk = dir.resolve("damage-wm.apk")
        Files.write(apk, ByteArray(3000) { (it % 251).toByte() })
        serving(apk) { s, base ->
            val (code, headers, body) = get("$base/setup")
            assertEquals(200, code)
            assertTrue(headers["content-type"]!!.startsWith("text/html"))
            val html = String(body)
            assertTrue(html.contains("href=\"/damage-apk?token=tok-en\""), html)
            assertTrue(html.contains("saves as damage-wm-${s.stamp()}.apk"), html)
            assertFalse(html.contains("G2CC"), "the G2CC boxes are gone")
            assertEquals(404, get("$base/somewhere").first)
        }
    }

    @Test
    fun theApkRouteWantsTheTokenServesTheStampedBytesAndAnswersHead() {
        val dir = Files.createTempDirectory("setup2")
        val apk = dir.resolve("damage-wm.apk")
        val bytes = ByteArray(5000) { (it * 7 % 256).toByte() }
        Files.write(apk, bytes)
        serving(apk) { s, base ->
            assertEquals(401, get("$base/damage-apk").first)
            assertEquals(401, get("$base/damage-apk?token=wrong").first)
            val (code, headers, body) = get("$base/damage-apk?token=tok-en")
            assertEquals(200, code)
            assertTrue(bytes.contentEquals(body), "the staged bytes, whole")
            assertEquals("application/vnd.android.package-archive", headers["content-type"])
            assertEquals("attachment; filename=\"damage-wm-${s.stamp()}.apk\"", headers["content-disposition"])
            val (bcode, _, bbody) = get("$base/damage-apk", bearer = "tok-en")
            assertEquals(200, bcode); assertEquals(bytes.size, bbody.size)
            val (hcode, hheaders, hbody) = get("$base/damage-apk?token=tok-en", method = "HEAD")
            assertEquals(200, hcode)
            assertEquals(bytes.size.toString(), hheaders["content-length"])
            assertEquals(0, hbody.size, "HEAD carries no body")
            Files.delete(apk)
            assertEquals(404, get("$base/damage-apk?token=tok-en").first, "nothing staged is said, not faked")
            assertTrue(s.stagedInfo().startsWith("NOT STAGED"))
        }
    }

    @Test
    fun aNonTailscaleArrivalIsRefusedBeforeAnything() {
        val dir = Files.createTempDirectory("setup3")
        val apk = dir.resolve("damage-wm.apk")
        Files.write(apk, ByteArray(10))
        serving(apk, allowed = { false }) { _, base ->
            assertEquals(403, get("$base/setup").first)
            assertEquals(403, get("$base/damage-apk?token=tok-en").first)
        }
        assertTrue(SetupServer.tailscaleOrLoopback(InetAddress.getLoopbackAddress()))
        assertFalse(SetupServer.tailscaleOrLoopback(InetAddress.getByName("192.0.2.1")), "a documentation address is on no interface here")
        assertTrue(SetupServer.isTailscaleInterface("tailscale0") && !SetupServer.isTailscaleInterface("enp7s0"))
        assertTrue(SetupServer.same("abc", "abc") && !SetupServer.same("abc", "abd") && !SetupServer.same("", "a"))
    }
}
