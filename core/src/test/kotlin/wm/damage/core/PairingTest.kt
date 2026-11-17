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

    /** Fix round 1, ruling 2.3 (9): a revocation whose end reaches the core between a page's upgrade (its secret
     *  checked) and its welcome finds no connection to end; the welcome asks the device table again and ends it with
     *  `end{revoked}`, so it never runs at the revoked device's level. */
    @Test
    fun aRevocationBetweenTheUpgradeAndTheWelcomeEndsTheConnection() = WebRig().use { r ->
        val (row, _) = r.approved(Levels.FULL)
        val held = CountDownLatch(1)
        r.host.executor.execute("test hold") { held.await() }
        // the upgrade passed (the peer below); the revocation's end is queued first, then this connection's welcome
        assertNull(r.devices.revoke(row.id, "local"))
        val io = HeldIo()
        val served = thread(isDaemon = true) { r.net.serve(Socket(), io, AppWire.encode(AppWire.hello("w1", mapOf("t" to 1))).decodeToString(), Peer(row.id, row.level)) }
        eventually("the loop sent its hello on and waits for the next frame") { io.reads.get() >= 1 }
        Thread.sleep(50)                                          // let the welcome step queue behind the end
        held.countDown()
        val f = io.next()
        assertEquals("end", AppWire.type(f), "the revoked device's late connection is ended, not welcomed: $f")
        assertEquals(AppWire.END_REVOKED, f["reason"]!!.jsonPrimitive.content)
        assertEquals(0, r.net.connections(), "nothing is registered")
        io.toServer.put(kotlinx.serialization.json.JsonObject(mapOf("t" to JsonPrimitive("eof"))))
        served.join(10_000)
    }

    /** Fix round 2, ruling 2.4: the replace sibling of the race above — a re-approval between a page's upgrade and its
     *  welcome keeps the row live (the same id, a new secret); the welcome compares the approval the upgrade read, so
     *  the connection never runs at the old secret's level. */
    @Test
    fun aReApprovalBetweenTheUpgradeAndTheWelcomeEndsTheConnection() = WebRig().use { r ->
        val (row, _) = r.approved(Levels.VIEW)
        val held = CountDownLatch(1)
        r.host.executor.execute("test hold") { held.await() }
        // the upgrade passed at the old approval (the peer below); the re-approval's end is queued first, then the welcome
        val (again, _) = r.approved(Levels.FULL, replaces = row.id)
        assertEquals(row.id, again.id, "the same row, approved again")
        val io = HeldIo()
        val served = thread(isDaemon = true) {
            r.net.serve(Socket(), io, AppWire.encode(AppWire.hello("w1", mapOf("t" to 1))).decodeToString(), Peer(row.id, row.level, row.secretHash))
        }
        eventually("the loop sent its hello on and waits for the next frame") { io.reads.get() >= 1 }
        Thread.sleep(50)                                          // let the welcome step queue behind the end
        held.countDown()
        val f = io.next()
        assertEquals("end", AppWire.type(f), "the connection of the replaced approval is ended, not welcomed: $f")
        assertEquals(AppWire.END_REPLACED, f["reason"]!!.jsonPrimitive.content)
        assertEquals(0, r.net.connections(), "nothing is registered")
        io.toServer.put(kotlinx.serialization.json.JsonObject(mapOf("t" to JsonPrimitive("eof"))))
        served.join(10_000)
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

    /** Fix round 1, ruling 2.3 (7): nothing is cut — a rename over the limit is refused with the reason and the row
     *  keeps its name; one at the limit is kept whole; a waiting page's own name over it is not used (the row waits as
     *  "a browser", said in the log), never a prefix of it. */
    @Test
    fun aNameOverTheLimitIsRefusedWithTheReason_neverCut() = WebRig().use { r ->
        val (row, _) = r.approved(Levels.VIEW, name = "Desk browser")
        val long = "A".repeat(wm.damage.core.web.Devices.NAME_MAX) + "B"
        val why = r.devices.rename(row.id, long)
        assertNotNull(why, "a name over the limit is refused")
        assertTrue("at most ${wm.damage.core.web.Devices.NAME_MAX}" in why && "41" in why, why)
        assertEquals("Desk browser", r.devices.rows().single().name, "the row keeps its name")
        val exact = "C".repeat(wm.damage.core.web.Devices.NAME_MAX)
        assertNull(r.devices.rename(row.id, exact))
        assertEquals(exact, r.devices.rows().single().name, "a name at the limit is kept whole")
        val p = r.devices.request("127.0.0.1", null, long, "browser", null, WebRig.Waiting())
        assertEquals(wm.damage.core.web.Devices.DEFAULT_NAME, p.name, "a page's own name over the limit is not used, never cut")
        assertTrue(recentLog().any { "its own name was not used" in it }, "and it is said")
    }

    /** Fix round 1, ruling 2.3 (11): a device table that cannot be READ at a start (not missing: a permission, an I/O
     *  error) is frozen as an undecodable one is — the next approval is refused and the file that held his approvals is
     *  never replaced by one holding only the new row. */
    @Test
    fun aDeviceTableThatCannotBeReadAtAStart_isNeverOverwritten() {
        val dir = java.nio.file.Files.createTempDirectory("damage-devices-unreadable")
        try {
            val file = dir.resolve(wm.damage.core.web.Devices.FILE)
            val held = """{"v":1,"devices":[{"id":"web-0a0b0c","name":"Desk","kind":"browser","level":"full","secretHash":"00","approvedAt":1,"approvedBy":"local"}]}"""
            java.nio.file.Files.writeString(file, held)
            assertTrue(file.toFile().setReadable(false, false), "the fixture file made unreadable")
            val d = wm.damage.core.web.Devices(file).load()
            assertNotNull(d.problem, "the start says so")
            assertTrue("could not be read" in d.problem!!, d.problem)
            val w = WebRig.Waiting()
            val p = d.request("127.0.0.1", null, "Phone browser", "browser", null, w)
            // fix round 2, ruling 2.3: the approval is answered with the freeze (the command line and the devices page
            // say why), never reported done while the page's redemption cannot be written
            val refused = d.approve(p.code, Levels.VIEW, "local")
            assertEquals(d.problem, refused, "an approval on a frozen table answers the freeze")
            assertNull(w.claims.poll(200, TimeUnit.MILLISECONDS), "the waiting page is sent no claim")
            file.toFile().setReadable(true, false)
            assertEquals(held, java.nio.file.Files.readString(file), "the file that held his approvals is untouched")
        } finally {
            dir.resolve(wm.damage.core.web.Devices.FILE).toFile().setReadable(true, false)
            dir.toFile().deleteRecursively()
        }
    }
}
