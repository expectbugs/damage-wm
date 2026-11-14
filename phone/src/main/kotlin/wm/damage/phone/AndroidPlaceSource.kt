package wm.damage.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import wm.damage.core.bastion.Fix
import wm.damage.core.bastion.PlaceSource
import wm.damage.core.bastion.PlaceWatch
import wm.damage.core.util.Log

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build W, package F (W-decisions G.4, G.5): the phone's position for [PlaceWatch], through the platform's
 * `LocationManager` only — the PASSIVE and NETWORK providers at about ten minutes and 100 m ([PlaceWatch.INTERVAL_MS],
 * [PlaceWatch.DISTANCE_M]); no GPS provider is asked for, no Google Play services, no geofencing.
 *
 * The service's declared foreground types do not change (`connectedDevice|mediaPlayback`). The platform's documentation
 * (cited in W-F-built.md's design note) counts location read from a foreground service of another type as BACKGROUND
 * location access, which needs `ACCESS_BACKGROUND_LOCATION` ("Allow all the time"); [permitted] requires it and
 * precise location, and nothing starts without both. A refusal the platform makes anyway is a [SecurityException],
 * thrown to [PlaceWatch], which says it.
 *
 * Positions go to [PlaceWatch] only: no line here carries a coordinate, a distance or an accuracy
 * (`tools/check_apk_wiring.py` reads this file's log calls for it).
 */
class AndroidPlaceSource(private val context: Context) : PlaceSource {

    private val lm: LocationManager? = context.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null
    private var executor: ExecutorService? = null

    override fun permitted(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    override fun start(onFix: (Fix) -> Unit): String {
        val m = lm ?: throw IllegalStateException("this phone has no location service")
        stop()
        val ex = Executors.newSingleThreadExecutor { r -> Thread(r, "damage-place").apply { isDaemon = true } }
        val l = LocationListener { loc -> onFix(fixOf(loc)) }
        val started = ArrayList<String>()
        try {
            for (p in listOf(LocationManager.PASSIVE_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try {
                    m.requestLocationUpdates(p, PlaceWatch.INTERVAL_MS, PlaceWatch.DISTANCE_M, ex, l)
                    started += p
                } catch (e: IllegalArgumentException) {
                    // "if provider is null or doesn't exist" (the platform's reference): said, the other one runs
                    Log.w(TAG, "the $p location provider is not on this phone: ${e.message}")
                }
            }
            if (started.isEmpty()) throw IllegalStateException("neither the passive nor the network provider exists")
            listener = l
            executor = ex
            // the newest position the platform already holds (it never turns a sensor on), so a start does not wait
            // ten minutes for its first fix; [PlaceWatch] drops it when it is old or coarse
            m.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)?.let { onFix(fixOf(it)) }
        } catch (e: Exception) {
            // nothing is left running after a start that throws (a SecurityException goes on to PlaceWatch, which says it)
            try { m.removeUpdates(l) } catch (x: Exception) { Log.w(TAG, "removing the updates after a failed start: ${x::class.simpleName}") }
            ex.shutdown()
            listener = null
            executor = null
            throw e
        }
        return started.joinToString(" + ")
    }

    @Synchronized
    override fun stop() {
        val l = listener
        listener = null
        if (l != null) {
            try { lm?.removeUpdates(l) } catch (e: Exception) { Log.w(TAG, "removing the location updates: ${e::class.simpleName}: ${e.message}") }
        }
        executor?.shutdown()
        executor = null
    }

    /** The platform's fix as [PlaceWatch] takes it: [Fix.at] is when it was TAKEN, on this phone's wall clock (the
     *  fix's own age, from the monotonic clock, subtracted), so a cached fix is judged by its real age. */
    private fun fixOf(loc: Location): Fix {
        val ageMs = ((SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000).coerceAtLeast(0)
        return Fix(loc.latitude, loc.longitude, System.currentTimeMillis() - ageMs, if (loc.hasAccuracy()) loc.accuracy else null)
    }

    companion object {
        private const val TAG = "place"
    }
}
