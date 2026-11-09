package wm.damage.desktop

import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import wm.damage.core.transport.SeamProbe
import wm.damage.core.util.Log
import wm.damage.desktop.StandbyPolicy.Phone

/**
 * `HANDOFF.md` §72 (2026-11-08): the PC standby must never claim glasses it cannot see, never
 * claim them from a phone that chose Target≠glasses, never keep retrying a claim that cannot
 * drive, and never run "started" on a port another instance holds. On 2026-11-06 it did the
 * first two on a powered-off adapter, then 876 failed starts ran its heap out.
 *
 * The decisions are new functions, so these pins are watched to fail under mutations that put
 * the old behaviour back (the record lists them).
 */
class StandbyPolicyTest {

    @Test
    fun aClaimNeedsAnAbsentPhoneAndBothArmsSeen() {
        val d = 6
        // the only claim: unreachable for the debounce AND both arms seen advertising here
        assertTrue(StandbyPolicy.shouldClaim(Phone.UNREACHABLE, 6, d, armsAdvertising = true))
        assertTrue(StandbyPolicy.shouldClaim(Phone.UNREACHABLE, 40, d, armsAdvertising = true))
        // 2026-11-06: an adapter that cannot scan used to claim on the debounce alone
        assertFalse(StandbyPolicy.shouldClaim(Phone.UNREACHABLE, 40, d, armsAdvertising = null),
            "an adapter that cannot scan sees nothing and must not claim")
        assertFalse(StandbyPolicy.shouldClaim(Phone.UNREACHABLE, 40, d, armsAdvertising = false),
            "arms that do not advertise are still held by the phone")
        assertFalse(StandbyPolicy.shouldClaim(Phone.UNREACHABLE, 5, d, armsAdvertising = true), "inside the debounce")
        // Adam's ruling D1: a reachable phone on Target≠glasses is a choice, never a claim
        for (seen in listOf(true, false, null))
            assertFalse(StandbyPolicy.shouldClaim(Phone.CHOSE_NOT_TO, 40, d, seen),
                "a phone reading in Sim mode must never lose its glasses to the PC (seen=$seen)")
        for (seen in listOf(true, false, null))
            assertFalse(StandbyPolicy.shouldClaim(Phone.DRIVES, 40, d, seen))
    }

    @Test
    fun theProbeAnswerMapsToThePhonesIntent() {
        assertEquals(Phone.DRIVES, StandbyPolicy.phoneOf(SeamProbe.Result.Reachable(true, true, "")))
        assertEquals(Phone.DRIVES, StandbyPolicy.phoneOf(SeamProbe.Result.Reachable(null, false, "")),
            "an APK too old to be asked is read conservatively, as before")
        assertEquals(Phone.CHOSE_NOT_TO, StandbyPolicy.phoneOf(SeamProbe.Result.Reachable(false, false, "")))
        assertEquals(Phone.UNREACHABLE, StandbyPolicy.phoneOf(SeamProbe.Result.Unreachable))
    }

    @Test
    fun theClaimBacksOffAfterGiveBacks() {
        assertEquals(6, StandbyPolicy.claimDebounce(0, 6, 60), "no give-back: the plain 30 s debounce")
        assertEquals(12, StandbyPolicy.claimDebounce(1, 6, 60))
        assertEquals(24, StandbyPolicy.claimDebounce(2, 6, 60))
        assertEquals(60, StandbyPolicy.claimDebounce(4, 6, 60), "capped at 5 min of probes")
        assertEquals(60, StandbyPolicy.claimDebounce(1_000, 6, 60), "never overflows")
    }

    @Test
    fun aClaimThatCannotDriveIsGivenBack() {
        assertFalse(StandbyPolicy.shouldGiveBack(0, 6))
        assertFalse(StandbyPolicy.shouldGiveBack(5, 6))
        assertTrue(StandbyPolicy.shouldGiveBack(6, 6), "six failed starts in a row: back to standby")
        assertTrue(StandbyPolicy.shouldGiveBack(876, 6))
    }

    /** A BlueZ link whose adapter can be switched off and on, and whose peers can advertise. */
    private class FakeLink(val adapterName: String = "hci-test-${System.nanoTime()}") : BlueZLink {
        @Volatile var powered = false
        @Volatile var advertising = false
        var discovery = false
        var startCalls = 0
        var closed = 0
        override fun adapter() = BlueZLink.Adapter("/org/bluez/hci0", adapterName, "C4:BD:E5:2E:C9:75", powered)
        override fun startDiscovery() {
            startCalls++
            if (!powered) throw IllegalStateException("StartDiscovery refused on /org/bluez/hci0: Resource Not Ready")
            discovery = true
        }
        override fun stopDiscovery() { discovery = false }
        override fun discovering() = discovery
        override fun peers(): List<BlueZLink.Peer> = if (!advertising) emptyList() else listOf(
            BlueZLink.Peer("/org/bluez/hci0/dev_1", "D8:AE:E7:C1:FA:4D", "Even G2_32_L_C1FA4D", connected = false, servicesResolved = false, rssi = -60),
            BlueZLink.Peer("/org/bluez/hci0/dev_2", "E4:87:77:65:CD:50", "Even G2_32_R_65CD50", connected = false, servicesResolved = false, rssi = -61))
        override fun connect(devicePath: String) = throw UnsupportedOperationException()
        override fun disconnect(devicePath: String) = throw UnsupportedOperationException()
        override fun probe(devicePath: String) = throw UnsupportedOperationException()
        override fun characteristics(devicePath: String) = throw UnsupportedOperationException()
        override fun startNotify(charPath: String) = throw UnsupportedOperationException()
        override fun write(charPath: String, value: ByteArray) = throw UnsupportedOperationException()
        override fun mtu(charPath: String): Int? = null
        override fun rssi(devicePath: String): Int? = null
        override fun listen(l: (BlueZLink.Event) -> Unit) {}
        override fun close() { closed++ }
    }

    @Test
    fun aPoweredOffAdapterNeverClaimsAndScansAgainWhenSwitchedOn() {
        val link = FakeLink()
        val scan = StandbyScan(Config(), newLink = { link })
        fun said() = Log.recent().count { "cannot scan for the arms" in it && link.adapterName in it }
        val before = said()
        // powered off (beardos's hci0 is rfkill-blocked): every probe answers null, said ONCE
        repeat(5) { assertNull(scan.armsAdvertising(), "a powered-off adapter cannot scan") }
        assertEquals(0, link.startCalls, "the powered state is read, not tested by failing a discovery start")
        assertEquals(1, said() - before, "said once, not every 5 s")
        // switched on: not sticky — the next probe starts a scan, the one after judges what it sees
        link.powered = true
        assertEquals(false, scan.armsAdvertising(), "the first probe after power-on starts the scan")
        assertEquals(false, scan.armsAdvertising(), "nothing advertising yet")
        link.advertising = true
        assertEquals(true, scan.armsAdvertising(), "both arms seen: now a claim may happen")
    }

    @Test
    fun aBusyPortIsBoundOnceItIsFree() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val holder = ServerSocket(0)
            val port = holder.localPort
            var server: ServerSocket? = null
            val bound = java.util.concurrent.CountDownLatch(1)
            val now = bindOrRetry(scope, "test server", port, onBound = { bound.countDown() }, retryMs = 100) {
                server = ServerSocket(port)
            }
            assertFalse(now, "the port is held: not bound yet")
            holder.close()                       // the stray instance lets go
            assertTrue(bound.await(10, java.util.concurrent.TimeUnit.SECONDS), "the retry bound the freed port")
            assertTrue(server?.isBound == true)
            server?.close()
        } finally { scope.cancel() }
    }
}
