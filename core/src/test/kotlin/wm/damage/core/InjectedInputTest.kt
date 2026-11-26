package wm.damage.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.RemoteTransportClient
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.Transport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.EvenHubMsg

/**
 * B083 fix stage (item 3; the focused read of 0.83's scroll items, finding 3): a gesture made on a screen — the phone's
 * mirror, the PC's preview — enters through [Transport.injectInput] stamped as the ring's, and is marked `injected` so the
 * shell's local rules leave it alone (no lens fired for it; no report will come). The class: every `injectInput` — the
 * CFW transports' base and the seam's client.
 */
class InjectedInputTest {
    private fun injectedOf(t: Transport): TransportEvent.Input = runBlocking {
        val got = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { t.events.first { it is TransportEvent.Input } as TransportEvent.Input } }
        t.injectInput(EvenHubMsg.EV_SCROLL_BOTTOM)
        got.await()
    }

    @Test fun theCfwTransportsMarkAScreenGesture() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val t = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
            assertEquals(TransportEvent.Input(EvenHubMsg.EV_SCROLL_BOTTOM, EvenHubMsg.SRC_RING, injected = true), injectedOf(t))
        } finally { scope.cancel() }
    }

    @Test fun theSeamsClientMarksAScreenGesture() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val t = RemoteTransportClient("127.0.0.1", 1, "unused", scope)
            assertEquals(TransportEvent.Input(EvenHubMsg.EV_SCROLL_BOTTOM, EvenHubMsg.SRC_RING, injected = true), injectedOf(t))
        } finally { scope.cancel() }
    }
}
