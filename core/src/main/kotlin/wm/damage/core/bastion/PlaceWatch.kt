package wm.damage.core.bastion

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.util.Log

/**
 * One position fix, ON THE PHONE ONLY (W-decisions G.6: no coordinate and no distance ever reaches a journal, a log,
 * a notice, a channel message or the PC). [at] is the phone's wall-clock time when the fix was TAKEN (the platform
 * adapter subtracts the fix's own age), [accuracyM] its accuracy radius, null when the platform gave none.
 * [toString] prints no number of the position, so a fix written into a line by mistake says nothing of where.
 */
class Fix(val lat: Double, val lon: Double, val at: Long, val accuracyM: Float?) {
    override fun toString(): String = "Fix(taken at $at)"
}

/** What the phone tells the PC (G.2, G.6): the word and the age of the fix behind it — nothing else. */
data class PlaceReport(val word: String, val fixAgeMs: Long?) {
    fun toJson(): JsonObject = buildJsonObject {
        put("word", word)
        if (fixAgeMs != null) put("fixAgeMs", fixAgeMs)
    }
}

/** The bastion channel's side of the phone's position ([RemoteBastion]): what the next `table` ask carries, and what
 *  the PC's answer said of the connection path. */
interface PlaceLink {
    /** The report for the next `table` ask, or null for none. */
    fun report(): PlaceReport?
    /** The PC's last answer said the phone's link arrives over a private path on the bus network ([on]); false when
     *  it said not, said nothing, or the channel is down. [askedAt]: when the `table` ask this answer replies to was
     *  SENT (the phone's wall clock, as [PlaceWatch]'s); the channel's end passes the time of the end. */
    fun onBus(on: Boolean, askedAt: Long)
}

/** The platform's position updates (the APK's `AndroidPlaceSource`: `LocationManager`, passive and network providers;
 *  a stand-in in the tests). */
interface PlaceSource {
    /** The app may read the position in the background now (precise location and "Allow all the time"). */
    fun permitted(): Boolean
    /** Starts updates; [onFix] may run on any thread. Returns what started, in words. Throws [SecurityException] when
     *  the platform refuses, anything else when nothing could start; a start that throws leaves nothing running. */
    fun start(onFix: (Fix) -> Unit): String
    /** Stops every update [start] asked for. Idempotent. */
    fun stop()
}

/** Where the learned bus position is kept on the phone. */
interface PlaceStore {
    fun load(): Fix?
    fun save(f: Fix)
}

/**
 * The learned bus position as one line in a file of its own. The APK puts it in the app's no-backup directory
 * (`Context.getNoBackupFilesDir`, which Android's Auto Backup always excludes), not in its preferences file: the app
 * allows backup, and a preferences file is copied to the account's cloud backup — the position would leave the
 * phone (G.6). The file's text is never said: a parse failure names only the problem.
 */
class FilePlaceStore(private val path: Path) : PlaceStore {
    override fun load(): Fix? {
        if (!Files.exists(path)) return null
        val parts = Files.readString(path).trim().split(' ')
        val lat = parts.getOrNull(0)?.toDoubleOrNull()
        val lon = parts.getOrNull(1)?.toDoubleOrNull()
        val at = parts.getOrNull(2)?.toLongOrNull()
        if (parts.size != 3 || lat == null || lon == null || at == null || !PlaceWatch.valid(lat, lon))
            throw IOException("the stored bus position is not in its format")
        return Fix(lat, lon, at, null)
    }

    override fun save(f: Fix) {
        path.parent?.let { Files.createDirectories(it) }
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(tmp, "${f.lat} ${f.lon} ${f.at}\n")
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build W, package F (W-decisions G): presence from the phone's own position — the second signal of `damage.phone`
 * ([PhonePresence.combineSignals] on the PC).
 *
 * - **Learning (G.1):** while the PC's last answer says the phone's link arrives over a private path on the bus
 *   network ([onBus], package E's signal), a fix TAKEN since that run began, and accurate to [LEARN_ACCURACY_M], is
 *   a CANDIDATE for the bus's position (one at a time: a later fix as accurate or better replaces it, and its clock
 *   starts again). It is kept in [store] with its time only once a `bus:true` answer arrives to an ask SENT
 *   [LEARN_CONFIRM_MS] or more after the fix was taken (verify 3: measured at the ask, so an answer held back by a
 *   quiet channel confirms nothing it could not); a `bus:false` answer or the channel's end (both [onBus] false)
 *   drops it first (fix round 2, R2.1: the PC reads the daemon once a minute, so an answer can say `bus:true` for a
 *   while after the phone has left; a fix taken then was saved as the bus's position). The candidate is memory
 *   only: never stored, never in a line. It follows the bus when he moves it; nothing is typed in.
 * - **The word (G.2):** with a stored bus position and a fix younger than [FRESH_MS] accurate to
 *   [BAND_ACCURACY_M]: `home` within [HOME_M], `away` beyond [AWAY_M], the band between keeps the last word.
 *   `unknown` with the row off, no permission, a start that failed, no such fix, or no stored bus position.
 * - **What leaves (G.6):** [report] — the word and the fix's age. [stateLine] and [say] lines carry the word, the
 *   fix's age and why; never a coordinate, a distance or an accuracy.
 * - **Containment (G.5):** nothing starts until [PlaceSource.permitted] (checked every [tick]: a revocation stops
 *   the updates at the next minute); a [SecurityException] from the platform is caught and said (log, [say], the
 *   state line) and the start is tried again at the next minute. [close] stops the updates (the stack's stop).
 *
 * Nothing waits: [tick] is pacing (a minute), every clock is read, never waited on.
 */
class PlaceWatch(
    private val source: PlaceSource,
    private val store: PlaceStore,
    /** The phone-owned row `Presence · location` (on by default). Read once per tick. */
    private val enabled: () -> Boolean,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** One line per change of the word, per learning run and per failure (the APK writes it to its journal). */
    private val say: (String) -> Unit = {},
) : PlaceLink, AutoCloseable {

    private val lock = Any()
    // ---- under [lock] ----
    private var bus: Fix? = null
    private var last: Fix? = null
    private var onBusSince: Long? = null
    /** R2.1: the fix waiting for a later `bus:true` answer to confirm it. Memory only — never stored or said. */
    private var candidate: Fix? = null
    private var learnedThisRun = false
    private var on = false
    private var allowed = false
    private var listening = false
    private var failure: String? = null
    private var word = PhonePresence.UNKNOWN
    private var why = "not started"
    private val dropSaid = HashSet<String>()
    // ----
    /** Orders [source]'s start and stop against [close]: a tick that starts never runs after the stack's stop. */
    private val sourceLock = Any()
    @Volatile private var closed = false

    init {
        bus = try { store.load() } catch (e: Exception) {
            Log.w(TAG, "the stored bus position could not be read (${e::class.simpleName}); it is learned again on the bus network")
            null
        }
    }

    /** Starts the minute's [tick] (pacing, not a timeout). */
    fun start(scope: CoroutineScope, periodMs: Long = PERIOD_MS): Job = scope.launch(Dispatchers.IO) {
        while (isActive && !closed) {
            try { tick() } catch (e: Exception) { Log.e(TAG, "a location tick failed; the next minute's runs as usual", e) }
            delay(periodMs)
        }
    }

    /** One minute: the row and the permission read once; updates started or stopped to match; the word decided. */
    fun tick() {
        val wantOn = enabled()
        val ok = try { source.permitted() } catch (e: Exception) {
            Log.w(TAG, "the location permission could not be checked (${e::class.simpleName}): counted as not allowed")
            false
        }
        var start = false
        var stop: String? = null
        synchronized(lock) {
            on = wantOn
            allowed = ok
            if (!on || !allowed) {
                if (listening) stop = if (!on) "the Presence · location row is off" else "location is no longer allowed all the time"
                listening = false
                failure = null
            } else if (!listening) start = true
        }
        stop?.let { stopSource(it) }
        if (start) startSource()
        decide()
    }

    /** A fix from the platform (any thread). Out of range or too coarse: dropped (said once per kind). */
    fun fix(f: Fix) {
        if (!valid(f.lat, f.lon)) { drop("out of range", "a fix out of range was ignored"); return }
        val acc = f.accuracyM
        if (acc == null || !acc.isFinite() || acc > BAND_ACCURACY_M) {
            drop("coarse", "a fix with no accuracy or one coarser than the home band was ignored")
            return
        }
        synchronized(lock) {
            if (!listening) return                       // a late callback after the updates stopped
            if (last.let { it == null || f.at >= it.at }) last = f
            val since = onBusSince
            if (since != null && f.at >= since && acc <= LEARN_ACCURACY_M) {
                // R2.1: a candidate only; [onBus] commits it once a later answer confirms the bus network
                val c = candidate
                if (c == null || (f.at >= c.at && acc <= (c.accuracyM ?: Float.MAX_VALUE))) candidate = f
            }
        }
        decide()
    }

    override fun onBus(on: Boolean, askedAt: Long) {
        var learned = false
        var committed: Fix? = null
        synchronized(lock) {
            if (!on) {
                onBusSince = null
                candidate = null                         // R2.1: a `bus:false` answer or the channel's end drops it
            } else {
                if (onBusSince == null) {
                    onBusSince = clock()
                    learnedThisRun = false
                }
                val c = candidate
                // the age at the ASK this answer replies to (verify 3, the focused read's D1): an answer held back
                // while the channel was quiet but not ended says what the PC read before the ask, however late it
                // arrives; never later than now (a clock set back between the ask and its answer confirms nothing)
                if (c != null && minOf(askedAt, clock()) - c.at >= LEARN_CONFIRM_MS) {
                    candidate = null
                    bus = c
                    committed = c
                    learned = !learnedThisRun
                    learnedThisRun = true
                    save(c)
                }
            }
        }
        if (learned) {
            val line = "location: the bus's position is taken from the phone's fixes while it is on the bus network"
            Log.i(TAG, line)
            say(line)
        }
        if (committed != null) decide()
    }

    override fun report(): PlaceReport {
        decide()
        return synchronized(lock) {
            val f = last
            PlaceReport(word, if (word != PhonePresence.UNKNOWN && f != null) (clock() - f.at).coerceAtLeast(0) else null)
        }
    }

    /** `home`, `away` or `unknown`, as last decided. */
    val currentWord: String get() = synchronized(lock) { word }

    /** Whether the PC's last answer said the phone is on the bus network (tests). */
    internal val learning: Boolean get() = synchronized(lock) { onBusSince != null }

    /** The Bastion category's display row: `location: not allowed (…)`, `location: no fix …`, `location: home (fix
     *  3 min old)`. No coordinate, no distance. */
    fun stateLine(): String = synchronized(lock) {
        if (word == PhonePresence.UNKNOWN) "location: $why" else "location: $word ($why)"
    }

    /** Stops the updates for good (the stack's stop); a tick after it starts nothing. */
    override fun close() {
        synchronized(sourceLock) {
            closed = true
            synchronized(lock) { listening = false }
            try { source.stop() } catch (e: Exception) { Log.w(TAG, "stopping the location updates: ${e::class.simpleName}: ${e.message}") }
        }
    }

    // ---- inside ---------------------------------------------------------------------------------------------------

    private fun startSource() = synchronized(sourceLock) {
        if (closed) return@synchronized
        synchronized(lock) { listening = true }          // before the start: a fix it hands over at once is taken
        val what = try {
            source.start(::fix)
        } catch (e: SecurityException) {
            fail("refused by the platform (SecurityException: ${e.message ?: "no detail"})")
            return@synchronized
        } catch (e: Exception) {
            fail("could not start (${e::class.simpleName}: ${e.message ?: "no detail"})")
            return@synchronized
        }
        synchronized(lock) { failure = null }
        Log.i(TAG, "location updates on: $what (about every ${INTERVAL_MS / 60_000} min or ${DISTANCE_M.toInt()} m; no GPS asked for)")
    }

    private fun stopSource(why: String) = synchronized(sourceLock) {
        try { source.stop() } catch (e: Exception) { Log.w(TAG, "stopping the location updates: ${e::class.simpleName}: ${e.message}") }
        Log.i(TAG, "location updates off: $why")
    }

    /** A start that failed: said once per distinct reason; the next minute tries again. */
    private fun fail(reason: String) {
        val first: Boolean
        synchronized(lock) {
            listening = false
            first = failure != reason
            failure = reason
        }
        if (first) {
            val line = "location: $reason; tried again each minute"
            Log.w(TAG, line)
            say(line)
        }
    }

    private fun drop(kind: String, line: String) {
        if (synchronized(lock) { dropSaid.add(kind) }) Log.i(TAG, "$line (said once)")
    }

    /** Under [lock]. A failed write is said; the position is kept in memory and written again at the next fix. */
    private fun save(f: Fix) {
        try { store.save(f) } catch (e: Exception) {
            Log.w(TAG, "the bus position could not be stored (${e::class.simpleName}); kept in memory, stored again at the next fix on the bus network")
        }
    }

    private fun decide() {
        val now = clock()
        var line: String? = null
        synchronized(lock) {
            val (w, y) = judge(now)
            if (w != word) line = "location: $word -> $w ($y)"
            word = w
            why = y
        }
        line?.let { Log.i(TAG, it); say(it) }
    }

    /** Under [lock]: the word and why (G.2), from the last tick's row and permission and the fixes. */
    private fun judge(now: Long): Pair<String, String> {
        val unknown = PhonePresence.UNKNOWN
        if (!on) return unknown to "off (the Presence · location row)"
        if (!allowed) return unknown to "not allowed (it needs precise location, Allow all the time)"
        failure?.let { return unknown to it }
        if (!listening) return unknown to "starting"
        val f = last?.takeIf { now - it.at < FRESH_MS && it.at - now < FRESH_MS }
            ?: return unknown to "no fix younger than ${FRESH_MS / 60_000} min"
        val b = bus ?: return unknown to "no bus position yet (it is learned on the bus network)"
        val age = "fix ${(now - f.at) / 60_000} min old"
        val d = metres(f, b)
        return when {
            d <= HOME_M -> PhonePresence.HOME to age
            d > AWAY_M -> PhonePresence.AWAY to age
            word == unknown -> unknown to "$age, not decided yet"
            else -> word to age                         // the band between keeps the last word
        }
    }

    companion object {
        private const val TAG = "place"
        /** Home within this many metres of the bus (G.2). */
        const val HOME_M = 300.0
        /** Away beyond this many metres (G.2); the band between keeps the last word. */
        const val AWAY_M = 500.0
        /** A fix older than this is no fix (G.2). */
        const val FRESH_MS = 30 * 60_000L
        /** The updates' interval and distance (G.4). */
        const val INTERVAL_MS = 10 * 60_000L
        const val DISTANCE_M = 100f
        /** A fix coarser than this cannot place the phone inside the home band: not used (a call of package F). */
        const val BAND_ACCURACY_M = 200f
        /** A fix coarser than this is not taken as the bus's position. */
        const val LEARN_ACCURACY_M = 100f
        /** A candidate fix is the bus's position only once a `bus:true` answer arrives to an ask sent this long after
         *  it was taken (R2.1; measured at the ask since verify 3): two of the PC's daemon reads of 60 s, one 15 s
         *  ask, and a margin. The lead's number, inferred from those periods; not measured. */
        const val LEARN_CONFIRM_MS = 150_000L
        /** The tick's pacing. */
        const val PERIOD_MS = 60_000L
        private const val EARTH_M = 6_371_008.8

        fun valid(lat: Double, lon: Double): Boolean =
            lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

        /** The great-circle distance in metres (haversine). Computed on the phone and never written anywhere. */
        fun metres(a: Fix, b: Fix): Double {
            val p1 = Math.toRadians(a.lat)
            val p2 = Math.toRadians(b.lat)
            val dp = p2 - p1
            val dl = Math.toRadians(b.lon - a.lon)
            val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * EARTH_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
    }
}
