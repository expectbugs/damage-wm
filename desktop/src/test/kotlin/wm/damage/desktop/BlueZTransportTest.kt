package wm.damage.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
 * address caching, the rollback after a per-arm failure, our own disconnect
 * not counting as a link loss, and a device disconnect ending the session.
 * No radio.
 */
class BlueZTransportTest {

    /** A fake BlueZ: two peers, each arm's write char feeds the sim, the sim's
     *  notifications come back on the RIGHT arm's notify char. */
    private class FakeBlueZ(val sim: GlassFirmwareSim = GlassFirmwareSim()) : BlueZLink {
        val calls = java.util.Collections.synchronizedList(ArrayList<String>())
        var mtuValue: Int? = 517
        var nameLeft = "Even G2_12_L_ABCD"
        var nameRight = "Even G2_12_R_ABCD"
        var powered = true
        var advertising = true
        var failCharacteristicsFor: String? = null
        var emitConnectedOnDisconnect = false
        /** RIGHT reports `Connected=false` while LEFT's connect is in progress. */
        var dropRightWhileConnectingLeft = false
        var discoveryOn = false
        val connected = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        @Volatile var listener: ((BlueZLink.Event) -> Unit)? = null
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
        override fun startDiscovery() { calls.add("startDiscovery"); discoveryOn = true }
        override fun stopDiscovery() { calls.add("stopDiscovery"); discoveryOn = false }
        override fun discovering() = discoveryOn
        override fun peers(): List<BlueZLink.Peer> {
            val rssi = if (advertising) -60 else null
            return listOf(
                BlueZLink.Peer(leftPath, leftAddr, nameLeft, leftPath in connected, leftPath in connected, rssi),
                BlueZLink.Peer(rightPath, rightAddr, nameRight, rightPath in connected, rightPath in connected, rssi),
                BlueZLink.Peer("/org/bluez/hci0/dev_other", "11:22:33:44:55:66", "Some Speaker", false, false, -80),
            )
        }
        override fun connect(devicePath: String) {
            calls.add("connect ${armOf(devicePath)}"); connected.add(devicePath)
            if (dropRightWhileConnectingLeft && devicePath == leftPath) {
                connected.remove(rightPath)
                listener?.invoke(BlueZLink.Event.Connected(rightPath, false))
            }
        }
        override fun disconnect(devicePath: String) {
            calls.add("disconnect ${armOf(devicePath)}")
            connected.remove(devicePath)
            if (emitConnectedOnDisconnect) listener?.invoke(BlueZLink.Event.Connected(devicePath, false))
        }
        override fun probe(devicePath: String) = BlueZLink.Probe(devicePath in connected, devicePath in connected)
        override fun characteristics(devicePath: String): BlueZLink.Chars {
            if (failCharacteristicsFor == devicePath) throw IllegalStateException("$devicePath: service 5450 not found — services: 1800, 1801")
            return BlueZLink.Chars("$devicePath/service0010/char0011", "$devicePath/service0010/char0012")
        }
        override fun startNotify(charPath: String) { calls.add("notify ${armOf(charPath.substringBefore("/service"))}") }
        override fun write(charPath: String, value: ByteArray) {
            val arm = armOf(charPath.substringBefore("/service"))
            sim.write(arm, value, System.currentTimeMillis())
        }
        override fun mtu(charPath: String): Int? = mtuValue
        override fun rssi(devicePath: String): Int? = -58
        override fun listen(l: (BlueZLink.Event) -> Unit) { listener = l }
        override fun close() { listener = null }
    }

    private fun warmup() = Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480)))

    private fun FakeBlueZ.connects() = calls.filter { it.startsWith("connect") || it.startsWith("notify") || it.startsWith("disconnect") }

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
            assertEquals(listOf("connect RIGHT", "notify RIGHT", "connect LEFT", "notify LEFT"), fake.connects())
            assertEquals(fake.leftAddr to fake.rightAddr, remembered)
            assertTrue(fake.calls.contains("stopDiscovery"), "discovery stops once the pair is found")
            t.stop()
            assertEquals(2, fake.calls.count { it.startsWith("disconnect") })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aSmallMtuIsRefusedAndTheConnectedArmReleased(): Unit = runBlocking {
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
            assertTrue(fake.calls.contains("disconnect RIGHT"), "the arm connected before the refusal is released: ${fake.calls}")
            assertTrue(fake.connected.isEmpty(), "no link is left up")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun anUnreadableMtuIsRefused(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fake = FakeBlueZ().apply { mtuValue = null }
            val t = BlueZTransport(fake, scope)
            val r = runCatching { t.start(warmup()) }
            assertTrue(r.isFailure && r.exceptionOrNull()!!.message!!.contains("MTU"), "an unreadable MTU is a refusal")
            assertTrue(fake.connected.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aFailureOnTheSecondArmReleasesBoth(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fake = FakeBlueZ()
            fake.failCharacteristicsFor = fake.leftPath
            val t = BlueZTransport(fake, scope)
            val r = runCatching { t.start(warmup()) }
            assertTrue(r.isFailure && r.exceptionOrNull()!!.message!!.contains("5450"), "the missing service is named")
            assertEquals(listOf("connect RIGHT", "notify RIGHT", "connect LEFT"), fake.connects().take(3))
            assertEquals(setOf("disconnect RIGHT", "disconnect LEFT"), fake.connects().drop(3).toSet(),
                "both arms are released after the failure: ${fake.connects()}")
            assertTrue(fake.connected.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    /** Round 2, c2-1: a drop of the FIRST arm while the second is being set
     *  up is only recorded (the session is not running yet) — the post-loop
     *  check must fail the start, naming the arm, and both arms are released. */
    @Test
    fun theFirstArmDroppingDuringTheSecondArmsSetupFailsTheStartNamingIt(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fake = FakeBlueZ().apply { dropRightWhileConnectingLeft = true }
            val t = BlueZTransport(fake, scope)
            val r = runCatching { t.start(warmup()) }
            assertTrue(r.isFailure, "start() must not succeed with RIGHT gone")
            assertTrue(r.exceptionOrNull()!!.message!!.contains("RIGHT"), "the dropped arm is named: ${r.exceptionOrNull()?.message}")
            assertEquals(listOf("connect RIGHT", "notify RIGHT", "connect LEFT", "notify LEFT"), fake.connects().take(4),
                "LEFT's setup ran to its end before the check: ${fake.connects()}")
            assertEquals(setOf("disconnect RIGHT", "disconnect LEFT"), fake.connects().drop(4).toSet(),
                "both arms are released: ${fake.connects()}")
            assertTrue(fake.connected.isEmpty(), "no link is left up")
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
    fun aRememberedPairThatIsNotAdvertisingIsNotConnectedTo(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fake = FakeBlueZ().apply { advertising = false }   // listed by name from an earlier session, in the case now
            val t = BlueZTransport(fake, scope)
            val starting = async { runCatching { t.start(warmup()) } }
            delay(1500)
            assertFalse(starting.isCompleted, "the scan keeps waiting for the pair to advertise")
            assertFalse(fake.calls.any { it.startsWith("connect") }, "no connect attempt to a device the scan does not see")
            fake.advertising = true
            starting.await().getOrThrow()
            assertTrue(t.state.value.started)
            t.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun ourOwnDisconnectIsNotALinkLoss(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fake = FakeBlueZ().apply { emitConnectedOnDisconnect = true }
            val t = BlueZTransport(fake, scope)
            val links = ArrayList<TransportEvent.Link>()
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                t.events.collect { if (it is TransportEvent.Link) synchronized(links) { links.add(it) } }
            }
            t.start(warmup())
            // a report for a device that is not one of our arms, while running: ignored
            fake.listener!!.invoke(BlueZLink.Event.Connected("/org/bluez/hci0/dev_other", false))
            delay(200)
            assertTrue(t.state.value.started, "a stranger's disconnect is not our link loss")
            t.stop()
            delay(200)
            assertFalse(synchronized(links) { links.any { it.detail.contains("Connected=false") } },
                "BlueZ's report of our own disconnect is not a link loss: $links")
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
            val t0 = System.currentTimeMillis()
            fun down() = synchronized(links) { links.any { !it.connected && it.detail.contains("RIGHT") } }
            while (!down() && System.currentTimeMillis() - t0 < 5_000) delay(10)
            assertTrue(down(), "a link-down event naming the arm was emitted")
            assertFalse(t.state.value.started, "the session ended")
            t.start(warmup())
            assertTrue(t.state.value.started, "a fresh start works on the same instance")
            t.stop()
        } finally {
            scope.cancel()
        }
    }
}
