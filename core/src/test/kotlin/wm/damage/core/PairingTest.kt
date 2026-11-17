package wm.damage.core

import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import wm.damage.core.app.AppNet
import wm.damage.core.app.AppWire
import wm.damage.core.app.Levels
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.app.Peer
import wm.damage.core.web.Access

/*
 * Build S1-1, package P6 (`CONTRACT.md` §10.3, §10.8, §13 (b) "approval request", "claim value", "per-device secret";
 * dec. A.5): the first approval end to end on a loopback listener — the waiting row lives exactly as long as its
 * socket; approval sends a single-use claim whose one request sets an HttpOnly, SameSite=Strict cookie (no Secure);
 * deny ends the page; revoke ends the device's connections and refuses what they had not had applied; a re-approval
 * replaces the old row. No secret, claim or code reaches the log.
 */
class PairingTest {

    private fun claimOf(p: Page): String = p.next("approved")["claim"]!!.jsonPrimitive.content

    private fun redeem(r: WebRig, claim: String, origin: String? = r.origin): Resp =
        http(r.port, "POST", "/pair/claim", origin?.let { mapOf("Origin" to it) } ?: emptyMap(), claim.toByteArray())

    private fun cookieOf(resp: Resp): String =
        resp.header("set-cookie")!!.substringAfter("${Access.COOKIE}=").substringBefore(';')

    /** C§10.3: a waiting row appears with its code, its name and the arriving address, and goes when its socket ends —
     *  no timer, no cap. */
    @Test
    fun aWaitingRowLivesAndEndsWithItsConnection() = WebRig().use { r ->
        val p = Page(r.port)
        p.hello(name = "Desk browser")
        val code = p.next("pending")["code"]!!.jsonPrimitive.content
        assertEquals(6, code.length)
        assertTrue(code.all { it in wm.damage.core.web.Devices.ALPHABET }, "an unambiguous alphabet: $code")
        val row = r.devices.pending().single()
        assertEquals(code, row.code)
        assertEquals("Desk browser", row.name)
        assertEquals("127.0.0.1", row.address)
        r.rec.now += 3_600_000                                   // an hour on: nothing ends it but its socket
        assertEquals(1, r.devices.pending().size)
        p.close()
        eventually("the row went with its socket") { r.devices.pending().isEmpty() }
    }

    /** C§10.3: approval sends `approved{claim}`; the claim's one request sets the cookie `HttpOnly; SameSite=Strict`, no
     *  `Secure`, at its full age; a visit re-sets it; the socket with it is the lane at the approved level, with the
     *  bundle in its welcome. */
    @Test
    fun approvalSendsAClaimWhoseOneRequestSetsAnHttpOnlyStrictCookie() = WebRig().use { r ->
        Page(r.port).use { p ->
            p.hello(name = "Desk browser")
            val code = p.next("pending")["code"]!!.jsonPrimitive.content
            assertNull(r.devices.approve(code, Levels.CONTROL, "local"))
            val claim = claimOf(p)
            val got = redeem(r, claim)
            assertEquals(200, got.code, got.text)
            val set = got.header("set-cookie")!!
            val attrs = set.split(';').map { it.trim().lowercase() }
            assertTrue("httponly" in attrs, "HttpOnly: $set")
            assertTrue("samesite=strict" in attrs, "SameSite=Strict: $set")
            assertFalse(attrs.any { it == "secure" }, "no Secure over plain HTTP: $set")
            assertTrue("max-age=${Access.COOKIE_MAX_AGE_S}" in attrs, "at its full age: $set")
            val secret = cookieOf(got)
            val visit = http(r.port, "GET", "/", mapOf("Cookie" to "${Access.COOKIE}=$secret"))
            assertEquals(set, visit.header("set-cookie"), "a visit re-sets the same cookie")
            assertNull(http(r.port, "GET", "/").header("set-cookie"), "no cookie is set for a visit without one")
            Page(r.port, cookie = secret).use { q ->
                q.hello()
                val w = AppWire.parseWelcome(q.next("welcome"))
                assertEquals(Levels.CONTROL, w.level)
                assertEquals(r.devices.rows().single().id, w.device)
                assertEquals(r.bundle.hash, w.bundle!!["hash"]!!.jsonPrimitive.content, "the bundle in a page's welcome")
            }
        }
    }

    /** C§13 (b) "claim value": a claim works once; one whose waiting socket ended before it was taken is void (the
     *  approval dropped); a claim from no page of the PC's own (no Origin) is refused. */
    @Test
    fun aClaimWorksOnceAndIsVoidWhenItsConnectionEnds() = WebRig().use { r ->
        Page(r.port).use { p ->
            p.hello()
            assertNull(r.devices.approve(p.next("pending")["code"]!!.jsonPrimitive.content, Levels.VIEW, "local"))
            val claim = claimOf(p)
            assertEquals(403, redeem(r, claim, origin = null).code, "a claim is taken from the PC's own page only")
            assertEquals(200, redeem(r, claim).code, "the first request")
            assertEquals(403, redeem(r, claim).code, "the second request: single use")
            assertEquals(1, r.devices.rows().size)
        }
        val p = Page(r.port)
        p.hello()
        assertNull(r.devices.approve(p.next("pending")["code"]!!.jsonPrimitive.content, Levels.VIEW, "local"))
        val claim = claimOf(p)
        p.close()
        eventually("the waiting row went") { r.devices.pending().isEmpty() }
        assertEquals(403, redeem(r, claim).code, "its socket ended before the claim was taken: void")
        assertEquals(1, r.devices.rows().size, "no row for the dropped approval")
    }

    /** C§10.3, C§10.8: deny ends the waiting page with `end{denied}`; an unknown code is refused in words. */
    @Test
    fun denyEndsTheWaitingPage() = WebRig().use { r ->
        Page(r.port).use { p ->
            p.hello()
            val code = p.next("pending")["code"]!!.jsonPrimitive.content
            assertNotNull(r.devices.deny("QQQQQQ", "local"), "no page under that code")
            assertNull(r.devices.deny(code, "local"))
            assertEquals(AppWire.END_DENIED, p.next("end")["reason"]!!.jsonPrimitive.content)
            p.awaitClosed()
            assertTrue(r.devices.pending().isEmpty())
            assertTrue(r.devices.rows().isEmpty())
        }
    }

    /** A lane connection driven in process: [read] hands the serving loop one frame at a time and counts its reads,
     *  so a test knows when the loop has taken a frame and is waiting for the next. */
    private class HeldIo : AppNet.LaneIo {
        val toServer = LinkedBlockingQueue<JsonObject>()
        val fromServer = LinkedBlockingQueue<JsonObject>()
        val reads = AtomicInteger()
        override fun read(): JsonObject {
            reads.incrementAndGet()
            val f = toServer.take()
            if (AppWire.type(f) == "eof") throw java.io.EOFException("test end")
            return f
        }
        override fun write(frame: ByteArray) { fromServer.put(AppWire.parse(String(frame, Charsets.UTF_8))) }
        fun next(): JsonObject = fromServer.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("no frame within 10 s")
    }

    /** C§10.8: revoking a device ends its connections with `end{revoked}`, an op it sent that was not yet applied is
     *  refused (nothing changes), and its secret opens nothing any more. */
    @Test
    fun revokeEndsTheDevicesConnectionsAndRefusesItsUnsentOps() = WebRig().use { r ->
        val (row, secret) = r.approved(Levels.FULL)
        val io = HeldIo()
        val served = thread(isDaemon = true) { r.net.serve(Socket(), io, AppWire.encode(AppWire.hello("w1", mapOf("t" to 1))).decodeToString(), Peer(row.id, row.level)) }
        assertEquals("welcome", AppWire.type(io.next()))
        // hold the core: the revocation's end is queued first, then the op the device had already sent
        val held = CountDownLatch(1)
        r.host.executor.execute("test hold") { held.await() }
        assertNull(r.devices.revoke(row.id, "local"))
        val before = io.reads.get()
        io.toServer.put(AppWire.op(Op(row.id, OpId.Queued("w1", 1), "t", "set", listOf("a"), JsonPrimitive("late"), at = System.currentTimeMillis())))
        eventually("the loop took the op and waits for the next frame") { io.reads.get() >= before + 1 && io.toServer.isEmpty() }
        Thread.sleep(50)                                          // let the reading thread queue the op behind the end
        held.countDown()
        assertEquals(AppWire.END_REVOKED, io.next()["reason"]!!.jsonPrimitive.content)
        r.host.executor.call { }
        assertNull(r.rec.item(r.host, "a"), "the revoked device's unsent op changed nothing")
        io.toServer.put(kotlinx.serialization.json.JsonObject(mapOf("t" to JsonPrimitive("eof"))))
        served.join(10_000)
        // its secret opens nothing: the socket is the pairing one, the blob route refuses
        Page(r.port, cookie = secret).use { p ->
            p.hello()
            assertEquals("pending", AppWire.type(p.next()))
        }
        assertEquals(403, http(r.port, "GET", "/blob/x", mapOf("Cookie" to "${Access.COOKIE}=$secret")).code)
        assertTrue(r.devices.rows().single().revoked)
        assertEquals("", r.devices.rows().single().secretHash, "revocation deletes the hash")
    }

    /** Fix round 1, ruling 2.3 (4): every socket the web listener holds has TCP keepalive — a plain request that has
     *  not finished its headers, the pairing socket and the lane — so a peer gone without a FIN never keeps its thread
     *  or its waiting row for the life of the process. */
    @Test
    fun everySocketTheListenerHoldsHasKeepalive() = WebRig().use { r ->
        val (_, secret) = r.approved(Levels.VIEW)
        Socket(java.net.InetAddress.getLoopbackAddress(), r.port).use { silent ->
            Page(r.port).use { waiting ->
                waiting.hello(name = "fixture page")
                waiting.next("pending")
                Page(r.port, cookie = secret).use { lane ->
                    lane.hello()
                    lane.next("welcome")
                    eventually("the listener holds the three sockets") { r.server.openSockets().size == 3 }
                    val off = r.server.openSockets().filter { !it.keepAlive }
                    assertTrue(off.isEmpty(), "sockets without keepalive: ${off.map { it.remoteSocketAddress }}")
                }
            }
            assertFalse(silent.isClosed, "the silent request sent nothing all along")
        }
    }

    /** C§10.8: a page that names a listed device (it remembers its id) replaces that row when approved: the same id,
     *  the new level, the old secret void and the old connections ended with `end{replaced}`. */
    @Test
    fun aReApprovalReplacesTheOldRow() = WebRig().use { r ->
        val (old, oldSecret) = r.approved(Levels.VIEW)
        Page(r.port, cookie = oldSecret).use { live ->
            live.hello()
            assertEquals("welcome", AppWire.type(live.next()))
            Page(r.port).use { p ->
                p.hello(replaces = old.id)
                val code = p.next("pending")["code"]!!.jsonPrimitive.content
                assertEquals(old.id, r.devices.pending().single().replaces)
                assertNull(r.devices.approve(code, Levels.FULL, "local"))
                val got = redeem(r, claimOf(p))
                assertEquals(200, got.code)
                assertEquals(AppWire.END_REPLACED, live.next("end")["reason"]!!.jsonPrimitive.content, "the old row's page is told")
                val row = r.devices.rows().single()
                assertEquals(old.id, row.id, "the same row")
                assertEquals(Levels.FULL, row.level)
                assertNull(r.devices.authenticate(oldSecret), "the old secret is void")
                assertEquals(row.id, r.devices.authenticate(cookieOf(got))?.id)
            }
        }
    }

    /** No secret, claim, code or cookie value reaches a log line (the fixture's own random values are searched for). */
    @Test
    fun noSecretClaimOrCodeReachesTheLog() = WebRig().use { r ->
        val p = Page(r.port)
        p.hello(name = "Desk browser")
        val code = p.next("pending")["code"]!!.jsonPrimitive.content
        assertNull(r.devices.approve(code, Levels.FULL, "local"))
        val claim = claimOf(p)
        val got = redeem(r, claim)
        val secret = cookieOf(got)
        assertEquals(403, redeem(r, claim).code)
        http(r.port, "GET", "/", mapOf("Cookie" to "${Access.COOKIE}=$secret"))
        Page(r.port, cookie = secret).use { q -> q.hello(); q.next("welcome") }
        p.close()
        assertNull(r.devices.revoke(r.devices.rows().single().id, "local"))
        val file = String(java.nio.file.Files.readAllBytes(r.dir.resolve(wm.damage.core.web.Devices.FILE)))
        for ((what, v) in listOf("code" to code, "claim" to claim, "secret" to secret)) {
            val hits = recentLog().filter { v in it }
            assertTrue(hits.isEmpty(), "the $what reached the log: $hits")
            assertFalse(v in file, "the $what is not in the device table's file")
        }
    }

    /** L1 A1: a page of the PC's own browser (loopback, a loopback `Host`, the listener's own origin) is approved at
     *  once at level full: its code, then its claim; its row is "this PC's browser", approved by that rule. */
    @Test
    fun thePcsOwnBrowserIsApprovedAtOnceAtFull() = WebRig(ownBrowser = true).use { r ->
        Page(r.port).use { p ->
            p.hello(name = "browser on Linux")
            p.next("pending")
            val got = redeem(r, claimOf(p))
            assertEquals(200, got.code, got.text)
            val row = r.devices.rows().single()
            assertEquals(Levels.FULL, row.level)
            assertEquals(Access.OWN_BROWSER, row.name)
            assertEquals(Access.OWN_BROWSER, row.approvedBy)
        }
    }

    /** L1 A1's three conditions, each alone: a request from another interface, one with a non-loopback `Host`, and one
     *  whose `Origin` is not this listener's own (another port of this PC, or none) is not the PC's own browser. */
    @Test
    fun onlyThePcsOwnBrowserIsApprovedByItself() {
        val a = Access(listOf("localhost", "127.0.0.1", "desk.test"), ownBrowserFull = true)
        val lo = java.net.InetAddress.getLoopbackAddress()
        fun req(host: String, origin: String?) =
            wm.damage.core.replica.HttpRequest("GET", "/ws", listOfNotNull("host" to host, origin?.let { "origin" to it }).toMap())
        assertTrue(a.ownBrowser(req("127.0.0.1:7405", "http://127.0.0.1:7405"), lo, 7405))
        assertTrue(a.ownBrowser(req("localhost:7405", "http://localhost:7405"), lo, 7405))
        assertTrue(a.ownBrowser(req("[::1]:7405", "http://[::1]:7405"), lo, 7405))
        val lan = java.net.InetAddress.getByName("192.0.2.10")              // a documentation address
        assertFalse(a.ownBrowser(req("127.0.0.1:7405", "http://127.0.0.1:7405"), lan, 7405), "another interface")
        assertFalse(a.ownBrowser(req("desk.test:7405", "http://127.0.0.1:7405"), lo, 7405), "a Host not loopback")
        assertFalse(a.ownBrowser(req("127.0.0.1:7405", "http://127.0.0.1:8080"), lo, 7405), "another port's page")
        assertFalse(a.ownBrowser(req("127.0.0.1:7405", "http://desk.test:7405"), lo, 7405), "another origin")
        assertFalse(a.ownBrowser(req("127.0.0.1:7405", null), lo, 7405), "no Origin")
    }

    /** L1 R-e: a page taking and leaving a scope is the usage record — one `usage` note each, with the device. */
    @Test
    fun aPagesScopesAreTheUsageRecord() = WebRig().use { r ->
        val (row, secret) = r.approved(Levels.VIEW)
        Page(r.port, cookie = secret).use { p ->
            p.hello()
            p.next("welcome")
            p.send(AppWire.sub("t", null))
            p.next("snap")
            p.send(AppWire.unsub("t"))
        }
        fun usage() = java.nio.file.Files.readAllLines(r.rec.journal).filter { "\"kind\":\"usage\"" in it }
        eventually("the open and the leave are noted") { usage().size == 2 }
        assertTrue("${row.id} web t open t" in usage()[0] && "${row.id} web t leave t" in usage()[1], "${usage()}")
    }

    /** L1 AC16: a device table that cannot be read at a start is set aside whole under a dated name, said, and the
     *  table starts empty and carries on: the next approval is written (no state that only a restart clears). */
    @Test
    fun anUnreadableDeviceTableIsSetAsideSaidAndTheTableCarriesOn() {
        val dir = java.nio.file.Files.createTempDirectory("damage-devices-unreadable")
        try {
            val file = dir.resolve(wm.damage.core.web.Devices.FILE)
            val held = """{"v":1,"devices":[{"id":"web-0a0b0c","name":"Desk","kind":"browser","level":"full","secretHash":"00","approvedAt":1,"approvedBy":"local"}]}"""
            java.nio.file.Files.writeString(file, held)
            assertTrue(file.toFile().setReadable(false, false), "the fixture file made unreadable")
            val d = wm.damage.core.web.Devices(file, clock = { 1_700_000_000_000L }).load()
            assertTrue("could not be read" in d.problem!! && "set aside as devices.json.bad-1700000000000" in d.problem!!, d.problem)
            assertTrue(d.rows().isEmpty(), "the table starts empty")
            // L1 R2 A4: the Devices page is told, with when
            assertEquals("${d.problem}@1700000000000", wm.damage.core.app.DevicesCore(d).describe()["problem"]
                .let { (it as JsonObject).let { p -> "${p["why"]!!.jsonPrimitive.content}@${p["at"]}" } })
            val aside = dir.resolve("devices.json.bad-1700000000000")
            aside.toFile().setReadable(true, false)
            assertEquals(held, java.nio.file.Files.readString(aside), "the file that held his approvals is kept whole")
            val w = WebRig.Waiting()
            val p = d.request("127.0.0.1", null, "Phone browser", "browser", null, w)
            assertNull(d.approve(p.code, Levels.VIEW, "local"), "an approval is taken")
            assertNotNull(d.redeem(w.claims.poll(10, TimeUnit.SECONDS)), "and written")
            assertTrue("Phone browser" in java.nio.file.Files.readString(file), "a new table holds it")
        } finally {
            dir.toFile().listFiles()?.forEach { it.setReadable(true, false) }
            dir.toFile().deleteRecursively()
        }
    }
}
