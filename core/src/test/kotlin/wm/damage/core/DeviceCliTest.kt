package wm.damage.core

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import wm.damage.core.app.AppNet
import wm.damage.core.app.DeviceCli
import wm.damage.core.app.DevicesCore
import wm.damage.core.app.Levels
import wm.damage.core.content.ContentHostServer
import wm.damage.core.content.LocalContent
import wm.damage.core.web.Devices
import wm.damage.core.web.LocalSecret

/*
 * Build S1-1, package P6 (dec. A.6; `CONTRACT.md` §10.3, §10.6): the device commands as a client of a RUNNING service —
 * here a stand-in: a content host on loopback port 0 over a test record, its `app` lane serving a device table in a temp
 * directory, the local secret in that directory. In process; never the service's port or data directory.
 */
class DeviceCliTest {

    private class Stand : AutoCloseable {
        val rec = RecordRig()
        val host = rec.opened()
        val dir: Path = Files.createTempDirectory("damage-cli").also { LocalSecret.ensure(it) }
        val devices = Devices(dir.resolve(Devices.FILE), clock = { rec.now }).load()
        val net = AppNet(host, mapOf("t" to 1), clock = { rec.now }, devices = DevicesCore(devices))
        val server = ContentHostServer(LocalContent(Files.createDirectories(dir.resolve("books"))), 0, "shared-token", app = net,
            localSecret = { LocalSecret.read(dir) }, bindAddress = InetAddress.getLoopbackAddress()).also { it.start() }

        fun cli(cmd: DeviceCli.Command, secretDir: Path = dir): Triple<Int, String, String> {
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            val code = DeviceCli.run(cmd, server.boundPort, secretDir, PrintStream(out, true), PrintStream(err, true))
            return Triple(code, out.toString(), err.toString())
        }

        override fun close() {
            server.close()
            net.close()
            rec.cleanUp()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun listApproveDenyAndRevokeAnswerAsTheServiceDoes() = Stand().use { s ->
        val (c0, o0, _) = s.cli(DeviceCli.Command.List)
        assertEquals(DeviceCli.OK, c0)
        assertTrue("none approved yet" in o0 && "no page is waiting" in o0, o0)
        val desk = WebRig.Waiting()
        val p = s.devices.request("100.64.0.9", "deskpc", "Desk browser", "browser", null, desk)
        val (c1, o1, _) = s.cli(DeviceCli.Command.List)
        assertEquals(DeviceCli.OK, c1)
        assertTrue(p.code in o1 && "deskpc" in o1 && "Desk browser" in o1, "the waiting row with its code and tailnet name: $o1")
        val (c2, o2, e2) = s.cli(DeviceCli.Command.Approve(p.code, Levels.FULL))
        assertEquals(DeviceCli.OK, c2, e2)
        assertTrue("approved at full" in o2, o2)
        val claim = desk.claims.poll(10, TimeUnit.SECONDS)
        assertNotNull(claim, "the waiting page was sent its claim")
        assertTrue(claim !in o2 && claim !in e2, "the claim is never printed")
        val (c3, _, e3) = s.cli(DeviceCli.Command.Approve("ACDEFG", Levels.VIEW))
        assertEquals(DeviceCli.REFUSED, c3)
        assertTrue("no page is waiting under that code" in e3, e3)
        val other = WebRig.Waiting()
        val q = s.devices.request("100.64.0.10", null, "Phone browser", "browser", null, other)
        assertEquals(DeviceCli.OK, s.cli(DeviceCli.Command.Deny(q.code)).first)
        assertEquals(wm.damage.core.app.AppWire.END_DENIED, other.ends.poll(10, TimeUnit.SECONDS))
        val row = s.devices.redeem(claim)!!.row
        val (c4, o4, _) = s.cli(DeviceCli.Command.List)
        assertEquals(DeviceCli.OK, c4)
        assertTrue(row.id in o4 && "full" in o4, o4)
        assertEquals(DeviceCli.OK, s.cli(DeviceCli.Command.Revoke(row.id)).first)
        assertTrue(s.devices.rows().single().revoked)
        val (c5, _, e5) = s.cli(DeviceCli.Command.Revoke("web-000000"))
        assertEquals(DeviceCli.REFUSED, c5)
        assertTrue("no device" in e5, e5)
    }

    @Test
    fun withoutTheLocalSecretOrTheServiceNothingIsReached() = Stand().use { s ->
        val empty = Files.createTempDirectory("damage-cli-none")
        try {
            val (c1, _, e1) = s.cli(DeviceCli.Command.List, secretDir = empty)
            assertEquals(DeviceCli.UNREACHABLE, c1)
            assertTrue("no usable local secret" in e1, e1)
            LocalSecret.ensure(empty)                             // another secret than the service's
            val (c2, _, e2) = s.cli(DeviceCli.Command.List, secretDir = empty)
            assertEquals(DeviceCli.UNREACHABLE, c2)
            assertTrue("refused this command line's credential" in e2, e2)
            val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
            val err = ByteArrayOutputStream()
            assertEquals(DeviceCli.UNREACHABLE, DeviceCli.run(DeviceCli.Command.List, closed, s.dir, PrintStream(ByteArrayOutputStream()), PrintStream(err, true)))
            assertTrue("not answering" in err.toString(), err.toString())
        } finally {
            empty.toFile().deleteRecursively()
        }
    }
}
