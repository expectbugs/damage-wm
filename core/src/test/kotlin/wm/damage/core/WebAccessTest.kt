package wm.damage.core

import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import wm.damage.core.app.Answer
import wm.damage.core.app.AppWire
import wm.damage.core.app.Levels
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.app.OpJson
import wm.damage.core.net.Reach
import wm.damage.core.net.ReachGate
import wm.damage.core.net.Side
import wm.damage.core.web.Access

/*
 * Build S1-1, package P6 (`CONTRACT.md` §10.5, §10.9; the spec's §2 P6 "WebAccessTest"): the web interface listener's
 * checks, each on a real listener bound to loopback port 0 — the reach gate before a byte, `Host` and `Origin`, the
 * device secret on every data path (only the bundle and the pairing socket without it), and the level checked by the
 * PC at apply time.
 */
class WebAccessTest {

    /** C§10.5: a `Host` that names another site or port, or an `Origin` that is not the PC's own page, is refused —
     *  pages and the WebSocket upgrade alike; the PC's own names and origin pass. */
    @Test
    fun aForeignHostOrOriginIsRefused() = WebRig().use { r ->
        assertEquals(200, http(r.port, "GET", "/").code, "the PC's own address and port: the bundle")
        assertEquals(200, http(r.port, "GET", "/", host = "localhost:${r.port}").code, "a configured name")
        assertEquals(200, http(r.port, "GET", "/", mapOf("Origin" to r.origin)).code, "the PC's own page origin")
        val foreignHost = http(r.port, "GET", "/", host = "rebound.example:${r.port}")
        assertEquals(403, foreignHost.code, "a Host naming another site: ${foreignHost.text}")
        assertEquals(403, http(r.port, "GET", "/", host = "127.0.0.1:${r.port + 1}").code, "a Host naming another port")
        assertEquals(403, http(r.port, "GET", "/", host = "127.0.0.1").code, "a Host with no port names port 80")
        val foreignOrigin = http(r.port, "GET", "/", mapOf("Origin" to "http://rebound.example"))
        assertEquals(403, foreignOrigin.code, "a page of another site: ${foreignOrigin.text}")
        assertEquals(403, http(r.port, "GET", "/", mapOf("Origin" to "https://127.0.0.1:${r.port}")).code, "another scheme")
        // the upgrade: a page of another site open in his browser cannot reach the lane, even with his cookie
        val (_, secret) = r.approved(Levels.FULL)
        Page(r.port, cookie = secret, origin = "http://rebound.example").use { p ->
            assertEquals(403, p.upgrade.code, "a foreign Origin's upgrade is refused before the socket opens")
        }
        Page(r.port, cookie = secret, host = "rebound.example:${r.port}").use { p ->
            assertEquals(403, p.upgrade.code, "a foreign Host's upgrade is refused before the socket opens")
        }
        Page(r.port, cookie = secret).use { p ->
            assertEquals(101, p.upgrade.code, "the PC's own page opens the socket")
            p.hello()
            assertEquals("welcome", AppWire.type(p.next()))
        }
    }

    /** Fix round 1, ruling 2.4: a `Host` that is an address literal passes only when it is the very address the
     *  request arrived on (`Access.arrivedOn`) — here the configured names leave the loopback literal out, so that check
     *  alone decides: the arriving 127.0.0.1 passes, another loopback literal and the v6 one do not. */
    @Test
    fun aHostLiteralPassesOnlyAsTheAddressTheRequestArrivedOn() = WebRig(hosts = listOf("localhost")).use { r ->
        assertEquals(200, http(r.port, "GET", "/").code, "the arriving address, written as the Host")
        val other = http(r.port, "GET", "/", host = "127.0.0.2:${r.port}")
        assertEquals(403, other.code, "another address literal: ${other.text}")
        assertTrue("another site" in other.text, other.text)
        assertEquals(403, http(r.port, "GET", "/", host = "[::1]:${r.port}").code, "the v6 loopback, not the arriving address")
        assertEquals(200, http(r.port, "GET", "/", host = "localhost:${r.port}").code, "a configured name")
    }

    /** Dec. A.5, C§10.5: with no device secret the listener answers the bundle and the pairing socket only — a blob is
     *  refused, a lane hello gets `pending`, never `welcome`; a secret the table does not hold is no secret. */
    @Test
    fun withoutASecretOnlyTheBundleAndThePairingSocketAnswer() = WebRig(blobs = { id ->
        if (id == "b1") WebRig.blob("image/png", byteArrayOf(1, 2, 3)) else null
    }).use { r ->
        assertEquals(200, http(r.port, "GET", "/").code)
        assertEquals(200, http(r.port, "GET", "/app.js").code)
        assertEquals(403, http(r.port, "GET", "/blob/b1").code, "a blob without a secret")
        assertEquals(403, http(r.port, "GET", "/blob/b1", mapOf("Cookie" to "${Access.COOKIE}=not-a-device")).code,
            "a blob with a secret the table does not hold")
        for (cookie in listOf(null, "not-a-device")) Page(r.port, cookie = cookie).use { p ->
            p.hello(name = "fixture page")
            val f = p.next()
            assertEquals("pending", AppWire.type(f), "no device: the waiting state only ($cookie): $f")
            assertEquals(1, r.devices.pending().size)
        }
        eventually("each waiting row went with its socket") { r.devices.pending().isEmpty() }
        val (_, secret) = r.approved(Levels.VIEW)
        assertEquals(200, http(r.port, "GET", "/blob/b1", mapOf("Cookie" to "${Access.COOKIE}=$secret")).code, "with the secret")
    }

    /** C§10.4/§10.5: a view device's control op is refused BY THE PC at apply time with the reason — its number taken,
     *  so its stream goes on — and nothing changes; its view op applies; a subscription above its level is refused. */
    @Test
    fun aViewDevicesControlOpIsRefusedAtApplyTime() = WebRig().use { r ->
        val (row, secret) = r.approved(Levels.VIEW)
        Page(r.port, cookie = secret).use { p ->
            p.hello(stream = "w1")
            val w = AppWire.parseWelcome(p.next("welcome"))
            assertEquals(row.id, w.device)
            assertEquals(Levels.VIEW, w.level)
            p.send(AppWire.op(Op("someone-else", OpId.Queued("w1", 1), "t", "set", listOf("a"), JsonPrimitive("mine"), at = System.currentTimeMillis())))
            val a1 = OpJson.answer(p.next("ans")["answer"] as JsonObject)
            assertTrue(a1 is Answer.Refused && a1.code == Answer.LEVEL, "the PC refuses a view device's control op: $a1")
            assertTrue("control" in (a1 as Answer.Refused).reason && "view" in a1.reason, "the reason names both levels: $a1")
            assertNull(r.rec.item(r.host, "a"), "nothing changed")
            p.send(AppWire.op(Op(row.id, OpId.Queued("w1", 2), "t", "place", listOf("p"), JsonPrimitive("5"), at = System.currentTimeMillis())))
            val a2 = OpJson.answer(p.next("ans")["answer"] as JsonObject)
            assertEquals(Answer.Seen(), a2, "the next number applies: the refusal took its number")
            p.send(AppWire.sub("devices", null))
            val s = p.next("ans")
            assertEquals("devices", s["scope"]!!.jsonPrimitive.content)
            val a3 = OpJson.answer(s["answer"] as JsonObject)
            assertTrue(a3 is Answer.Refused && a3.code == Answer.LEVEL, "the device table needs full: $a3")
            val pending = r.devices.request("127.0.0.1", null, "another page", "browser", null, WebRig.Waiting())
            p.send(AppWire.op(Op(row.id, OpId.Conn(w.conn, 1), "devices", "approve", emptyList(),
                params = JsonObject(mapOf("code" to JsonPrimitive(pending.code), "level" to JsonPrimitive("full"))), at = System.currentTimeMillis())))
            val a4 = OpJson.answer(p.next("ans")["answer"] as JsonObject)
            assertTrue(a4 is Answer.Refused && a4.code == Answer.LEVEL, "a device-table action needs full: $a4")
            assertEquals(null, pending.approvedLevel, "nothing was approved")
        }
    }

    /** C§10.9: the reach gate judges the accepted socket before a byte is read — a refused arrival is closed at once,
     *  unread (a client that sends nothing sees the end), and said by the gate. */
    @Test
    fun theGateRunsBeforeAByte() {
        val said = CopyOnWriteArrayList<String>()
        val refuseAll = ReachGate(Side.PC, sink = { said += it },
            classify = { _, _, _ -> Reach.Verdict(false, "test", "every arrival refused (test)") })
        WebRig(reach = refuseAll).use { r ->
            Socket(InetAddress.getLoopbackAddress(), r.port).use { s ->
                s.soTimeout = 5_000                              // a test's bound: the gate must not wait for a request
                val got = try { s.getInputStream().read() } catch (e: SocketTimeoutException) { -2 }
                assertEquals(-1, got, "closed unread, before the client sent a byte (-2: the listener waited to read)")
            }
            eventually("the gate said the refusal") { said.any { "web interface refused" in it } }
            assertFalse(said.any { "GET" in it }, "nothing of a request was read: $said")
        }
    }
}
