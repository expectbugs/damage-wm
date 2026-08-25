package wm.damage.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.TransportEvent

/**
 * The BlueZ glue over a fake link whose far end is the firmware model
 * (HANDOFF.md §8.3 C3): connect order, MTU refusal, notification routing,
 * address caching, and a device disconnect ending the session. No radio.
 */
class BlueZTransportTest {

    /** A fake BlueZ: two peers, each arm's write char feeds the sim, the sim's
     *  notifications come back on the RIGHT arm's notify char. */
    private class FakeBlueZ(val sim: GlassFirmwareSim = GlassFirmwareSim()) : BlueZLink {
        val calls = ArrayList<String>()
        var mtuValue: Int? = 517
        var nameLeft = "Even G2_12_L_ABCD"
        var nameRight = "Even G2_12_R_ABCD"
        var powered = true
        val connected = HashSet<String>()
        var listener: ((BlueZLink.Event) -> Unit)? = null
        val leftPath = "/org/bluez/hci0/dev_AA_00_00_00_00_01"
        val rightPath = "/org/bluez/hci0/dev_AA_00_00_00_00_02"
        val leftAddr = "AA:00:00:00:00:01"
        val rightAddr = "AA:00:00:00:00:02"

        private fun armOf(devicePath: String) = if (devicePath == leftPath) Arm.LEFT else Arm.RIGHT

        init {
            sim.attachListener(object : GlassFirmwareSim.SimDiag {
                override fun event(kind: String, detail: String) {}
                override fun notify(arm: Arm, packet: ByteArray) {
                    listener?.invoke(BlueZLink.Event.Notification("$rightPath/service0010/char0012", packet))
                }
                override fun panelChanged(arm: Arm) {}
            })
        }

        override fun adapter() = BlueZLink.Adapter("/org/bluez/hci0", "hci0", "C4:BD:E5:2E:C9:75", powered)
        override fun startDiscovery() { calls.add("startDiscovery") }
        override fun stopDiscovery() { calls.add("stopDiscovery") }
        override fun peers() = listOf(
            BlueZLink.Peer(leftPath, leftAddr, nameLeft, leftPath in connected, leftPath in connected, -60),
            BlueZLink.Peer(rightPath, rightAddr, nameRight, rightPath in connected, rightPath in connected, -58),
            BlueZLink.Peer("/org/bluez/hci0/dev_other", "11:22:33:44:55:66", "Some Speaker", false, false, -80),
        )
        override fun connect(devicePath: String) { calls.add("connect ${armOf(devicePath)}"); connected.add(devicePath) }
        override fun disconnect(devicePath: String) { calls.add("disconnect ${armOf(devicePath)}"); connected.remove(devicePath) }
        override fun awaitServicesResolved(devicePath: String) { calls.add("resolved ${armOf(devicePath)}") }
        override fun characteristics(devicePath: String) =
            BlueZLink.Chars("$devicePath/service0010/char0011", "$devicePath/service0010/char0012")
        override fun startNotify(charPath: String) { calls.add("notify ${armOf(charPath.substringBefore("/service"))}") }
        override fun write(charPath: String, value: ByteArray) {
            val arm = armOf(charPath.substringBefore("/service"))
            sim.write(arm, value, System.currentTimeMillis())
        }
        override fun mtu(charPath: String): Int? = mtuValue
        override fun rssi(devicePath: String): Int? = -58
        override fun listen(l: (BlueZLink.Event) -> Unit) { listener = l }
        override fun close() {}
    }

    private fun warmup() = Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480)))

    @Test
    fun connectsRightThenLeftAndRunsTheSessionOverTheFake(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fake = FakeBlueZ()
            var remembered: Pair<String, String>? = null
            val t = BlueZTransport(fake, scope, rememberAddresses = { l, r -> remembered = l to r })
            t.start(warmup())
            assertTrue(t.state.value.started)
            assertEquals(1, fake.sim.preludeAcks, "the prelude reached the model through the fake")
            val order = fake.calls.filter { it.startsWith("connect") || it.startsWith("notify") }
            assertEquals(listOf("connect RIGHT", "notify RIGHT", "connect LEFT", "notify LEFT"), order)
            assertEquals(fake.leftAddr to fake.rightAddr, remembered)
            assertTrue(fake.calls.contains("stopDiscovery"), "discovery stops once the pair is found")
            t.stop()
            assertTrue(fake.calls.count { it.startsWith("disconnect") } == 2)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aSmallMtuIsRefusedBeforeAnythingIsWritten(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fake = FakeBlueZ().apply { mtuValue = 185 }
            val t = BlueZTransport(fake, scope)
            try {
                t.start(warmup())
                fail("start() should refuse a 185-byte MTU")
            } catch (e: Exception) {
                assertTrue(e.message!!.contains("MTU"), "got: ${e.message}")
            }
            assertEquals(0, fake.sim.preludeAcks, "nothing was written")
            assertFalse(t.state.value.started)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aCachedAddressMatchesAPeerWithNoName(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fake = FakeBlueZ().apply { nameLeft = ""; nameRight = "" }
            val t = BlueZTransport(fake, scope, cachedAddresses = { fake.leftAddr to fake.rightAddr })
            t.start(warmup())
            assertTrue(t.state.value.started)
            t.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aDeviceDisconnectEndsTheSessionLoudly(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fake = FakeBlueZ()
            val t = BlueZTransport(fake, scope)
            val links = ArrayList<TransportEvent.Link>()
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                t.events.collect { if (it is TransportEvent.Link) synchronized(links) { links.add(it) } }
            }
            t.start(warmup())
            assertTrue(t.state.value.started)
            fake.listener!!.invoke(BlueZLink.Event.Connected(fake.rightPath, false))
            // the state flips before the event is delivered on another thread:
            // wait for the event itself
            val t0 = System.currentTimeMillis()
            fun down() = synchronized(links) { links.any { !it.connected && it.detail.contains("RIGHT") } }
            while (!down() && System.currentTimeMillis() - t0 < 5_000) delay(10)
            assertTrue(down(), "a link-down event naming the arm was emitted")
            assertFalse(t.state.value.started, "the session ended")
            // and a fresh start works on the same instance
            t.start(warmup())
            assertTrue(t.state.value.started)
            t.stop()
        } finally {
            scope.cancel()
        }
    }
}
