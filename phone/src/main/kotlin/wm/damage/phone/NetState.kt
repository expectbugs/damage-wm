package wm.damage.phone

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.ConcurrentHashMap
import wm.damage.core.bastion.NetFacts
import wm.damage.core.util.Log

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * The phone's own network as "home unreachable" needs it (build W, package D; R6 §4, P10; the spec's D1): the judge
 * (`HomeWatch`) says "home unreachable" only while THIS phone has internet, so a phone that is itself offline never
 * blames home.
 *
 * - [validated]: a network with internet that the platform has validated, not a VPN (`INTERNET` + `VALIDATED` +
 *   `NOT_VPN`), is up. [validatedSince]: when that set last became non-empty (the wall clock; 0 = never in this stack).
 * - [blocked]: every such network is blocked for this app (Doze without the exemption, data saver) — counts as offline.
 * - [vpnPresent]: a VPN network is up (a second callback); null when that callback could not be registered. Only the
 *   notice's text reads it.
 *
 * The platform's callback thread writes; the judge's tick reads once per tick. Sets are concurrent and the clock a
 * volatile. A callback that cannot be registered is said at ERROR and leaves the facts at "offline" — the judge then
 * stays silent, which is said (never a notice built on a guess). [close] unregisters both; the stack's stop calls it.
 * No permission beyond `ACCESS_NETWORK_STATE`; nothing here waits.
 */
class NetState(context: Context) : NetFacts, AutoCloseable {

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val up = ConcurrentHashMap<Network, Boolean>()          // network → blocked
    private val vpns = ConcurrentHashMap.newKeySet<Network>()
    @Volatile private var since = 0L
    @Volatile private var vpnKnown = false

    override val validated: Boolean get() = up.isNotEmpty()
    override val blocked: Boolean get() = up.values.let { v -> v.isNotEmpty() && v.all { it } }
    override val validatedSince: Long get() = since
    override val vpnPresent: Boolean? get() = if (vpnKnown) vpns.isNotEmpty() else null

    private val main = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val was = up.isEmpty()
            up.putIfAbsent(network, false)
            if (was) {
                since = System.currentTimeMillis()
                Log.i(TAG, "the phone is online (a validated network is up)")
            }
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            if (up.containsKey(network)) up[network] = blocked
            Log.i(TAG, "network ${if (blocked) "blocked" else "not blocked"} for this app")
        }

        override fun onLost(network: Network) {
            up.remove(network)
            if (up.isEmpty()) Log.i(TAG, "the phone is offline (no validated network)")
        }
    }

    private val vpn = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { vpns.add(network) }
        override fun onLost(network: Network) { vpns.remove(network) }
    }

    init {
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(), main)
        } catch (e: Exception) {
            Log.e(TAG, "the phone's network cannot be followed (${e::class.simpleName}: ${e.message}) — " +
                "\"home unreachable\" stays silent in this stack", e)
        }
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(), vpn)
            vpnKnown = true
        } catch (e: Exception) {
            Log.w(TAG, "whether a VPN is up cannot be followed (${e::class.simpleName}: ${e.message}) — the notice leaves it out")
        }
    }

    override fun close() {
        for (cb in listOf(main, vpn)) {
            try { cm.unregisterNetworkCallback(cb) } catch (e: IllegalArgumentException) {
                // never registered (the registration's failure was said at ERROR above); said here too, so no
                // refusal of the platform's goes unsaid
                Log.i(TAG, "a network callback was not registered, so there is nothing to unregister: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "a network callback could not be unregistered: ${e.message}")
            }
        }
        up.clear(); vpns.clear()
    }

    private companion object { const val TAG = "net-state" }
}
