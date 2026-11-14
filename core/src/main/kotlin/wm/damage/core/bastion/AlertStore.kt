package wm.damage.core.bastion

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import wm.damage.core.util.Log

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on
 * his own network. This is the PC's record of the alerts its reader of his own home hub has raised
 * (build W, R6 §3, P4; the lead's decision B.3; the spec's §2(b)).
 *
 * States per alert id: CLEAR → RAISED (event `raised`, episode + 1) → ACKED (`acked`) → CLEAR
 * (`cleared`); RAISED → CLEAR directly too; a repeat of a RAISED alert emits `repeat`. An ACKED
 * alert never repeats (acknowledged ends the episode's repeats).
 *
 * `alerts.json` holds `{epoch, seq, alerts:{id:{…}}}` and is written after every change (a temp
 * file moved into place). **The epoch belongs to the store, not the process** (dec. B.3): it is
 * minted when the store is first created, or recreated after an unreadable file, and a restart
 * keeps it and continues `seq`. The last [RING_CAP] events are kept in memory only: after a
 * restart the ring is empty and [floor] = the loaded `seq`, so a client asking from before the
 * ring is answered `truncated` WITH the table — a snapshot instead of a silent gap ([answer]).
 *
 * A failed save is said once per run of failures (`Log.e`) and raises the built-in [STORE_ALERT]
 * in memory; the state stays in memory and the next change tries again, and a save that works
 * again clears [STORE_ALERT]. An unreadable file is moved aside to `alerts.json.bad`, said, and a
 * new store starts (a new epoch, seq 0) — [loadProblem].
 *
 * A `change` rule's baseline (package E, dec. F.2) is kept with its alert's entry —
 * `{baseline, baselineAt}`, the reading as read and the PC time — so a restart during an away period
 * goes on from the saved value. Setting it is no alert change: no event, no `seq` ([setBaseline]); an
 * entry that has never been raised holds it as CLEAR with episode 0, outside the table.
 *
 * One lock guards everything (the poll thread raises and clears, channel threads acknowledge and
 * ask for the table). Listeners are called UNDER the lock, in `seq` order, after the change is
 * saved; a listener must not call back into the store.
 */
class AlertStore(private val file: Path, private val clock: () -> Long = { System.currentTimeMillis() }) {

    private class Entry(
        var state: AlertState,
        var episode: Long,
        var severity: Severity,
        var raisedAt: Long,
        var ackedAt: Long,
        var lastNotifiedAt: Long,
        var repeatNo: Int,
        var text: String,
        var value: String,
        var builtIn: Boolean,
        var baseline: String? = null,
        var baselineAt: Long = 0,
    ) {
        fun record(id: String) = AlertRecord(id, episode, severity, raisedAt, state, ackedAt, text, value, repeatNo, builtIn)
    }

    @Serializable
    private data class SavedAlert(
        val state: String,
        val episode: Long,
        val severity: String = Severity.NORMAL.wire,
        val raisedAt: Long = 0,
        val ackedAt: Long = 0,
        val lastNotifiedAt: Long = 0,
        val repeatNo: Int = 0,
        val text: String = "",
        val value: String = "",
        val builtIn: Boolean = false,
        /** A `change` rule's baseline as read, and when it was taken (dec. F.2); absent = none. */
        val baseline: String? = null,
        val baselineAt: Long = 0,
    )

    @Serializable
    private data class Saved(val epoch: Long, val seq: Long, val alerts: Map<String, SavedAlert> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val alerts = LinkedHashMap<String, Entry>()
    private val ring = ArrayDeque<AlertEvent>()
    /** Events made by the operation in progress, handed to the listeners once it is saved. */
    private val pending = ArrayList<AlertEvent>()
    private val listeners = CopyOnWriteArrayList<(AlertEvent) -> Unit>()
    private var seqNo = 0L
    private var floorNo = 0L
    private var saveFailing = false

    /** The store's epoch: creation time in ms, never 0; kept across restarts (dec. B.3). */
    val epoch: Long

    /** Said at load: the file was unreadable and moved aside; null when it loaded or did not exist. */
    val loadProblem: String?

    init {
        var problem: String? = null
        var saved: Saved? = null
        try {
            saved = decode(String(Files.readAllBytes(file), Charsets.UTF_8))
        } catch (e: NoSuchFileException) {
            // the first run: a new store
        } catch (e: Exception) {
            val bad = file.resolveSibling(file.fileName.toString() + ".bad")
            problem = "${file.fileName} unreadable (${e::class.simpleName}: ${e.message}) - " +
                "moved to ${bad.fileName}; a new store starts with a new epoch"
            Log.e(TAG, problem)
            try {
                Files.move(file, bad, StandardCopyOption.REPLACE_EXISTING)
            } catch (m: Exception) {
                Log.e(TAG, "could not move the unreadable ${file.fileName} aside - the next save replaces it", m)
            }
        }
        loadProblem = problem
        if (saved != null) {
            epoch = saved.epoch
            seqNo = saved.seq
            for ((id, a) in saved.alerts) alerts[id] = Entry(
                state = AlertState.of(a.state)!!,
                episode = a.episode,
                severity = Severity.of(a.severity)!!,
                raisedAt = a.raisedAt,
                ackedAt = a.ackedAt,
                lastNotifiedAt = a.lastNotifiedAt,
                repeatNo = a.repeatNo,
                text = a.text,
                value = a.value,
                builtIn = a.builtIn,
                baseline = a.baseline,
                baselineAt = a.baselineAt,
            )
        } else {
            epoch = maxOf(1L, clock())
        }
        floorNo = seqNo
        if (saved == null) synchronized(lock) {
            // the epoch is minted now: written at once, so a restart before the first alert keeps it
            persistLocked()
            deliverLocked()
        }
    }

    /** The last event's number. */
    val seq: Long get() = synchronized(lock) { seqNo }

    /** The ring answers for every `since ≥ floor`; below it a client is answered `truncated`. */
    val floor: Long get() = synchronized(lock) { floorNo }

    fun addListener(l: (AlertEvent) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (AlertEvent) -> Unit) {
        listeners.remove(l)
    }

    /** CLEAR (or never seen) → RAISED with the next episode. Already up → no change, null. */
    fun raise(id: String, severity: Severity, text: String, value: String, builtIn: Boolean = false): AlertEvent? =
        synchronized(lock) {
            val ev = raiseLocked(id, severity, text, value, builtIn)
            if (ev != null) persistLocked()
            deliverLocked()
            ev
        }

    /** RAISED or ACKED → CLEAR. Already clear (or never seen) → null. [text], when given, replaces the alert's words
     *  first, so the `cleared` event says why (a guard that ended, a baseline re-taken; dec. F.1, F.2). */
    fun clear(id: String, text: String? = null): AlertEvent? = synchronized(lock) {
        if (text != null) alerts[id]?.takeIf { it.state != AlertState.CLEAR }?.text = text
        val ev = clearLocked(id)
        if (ev != null) persistLocked()
        deliverLocked()
        ev
    }

    /** The channel's `ack {id, episode}` (§2(b)): Damage's own store only, never a hub request. */
    fun ack(id: String, episode: Long): AckResult = synchronized(lock) {
        val e = alerts[id]
        val result = when {
            e != null && e.episode == episode && e.state == AlertState.RAISED -> {
                e.state = AlertState.ACKED
                e.ackedAt = clock()
                emitLocked(EventKind.ACKED, id, e, e.ackedAt)
                persistLocked()
                AckResult(true, AlertState.ACKED, null)
            }
            e != null && e.episode == episode && e.state == AlertState.ACKED -> AckResult(true, AlertState.ACKED, null)
            else -> AckResult(false, e?.state ?: AlertState.CLEAR, "that episode has ended")
        }
        deliverLocked()
        result
    }

    /**
     * The repeats due at [now]: RAISED alerts only (an ACKED one is acknowledged). URGENT every ten minutes
     * whatever its rule says (dec. B.1); NORMAL every `repeatMsById[id]` ms, 0 or absent = said once.
     */
    fun dueRepeats(now: Long, repeatMsById: Map<String, Long>): List<AlertEvent> = synchronized(lock) {
        val out = ArrayList<AlertEvent>()
        for ((id, e) in alerts) {
            if (e.state != AlertState.RAISED) continue
            val every = if (e.severity == Severity.URGENT) AlertRule.URGENT_REPEAT_MS else repeatMsById[id] ?: 0L
            if (every <= 0L || now - e.lastNotifiedAt < every) continue
            e.repeatNo += 1
            e.lastNotifiedAt = now
            out += emitLocked(EventKind.REPEAT, id, e, now)
        }
        if (out.isNotEmpty()) persistLocked()
        deliverLocked()
        out
    }

    /** At load: an alert that is up but whose rule is gone (and is not one of the watcher's own) is cleared, and a
     *  gone rule's baseline is dropped. */
    fun retire(liveIds: Set<String>): List<AlertEvent> = synchronized(lock) {
        val gone = alerts.filter { (id, e) ->
            e.state != AlertState.CLEAR && !id.startsWith(AlertRule.BUILT_IN_PREFIX) && id !in liveIds
        }.keys.toList()
        val out = gone.mapNotNull { clearLocked(it) }
        var dropped = false
        for ((id, e) in alerts) if (e.baseline != null && id !in liveIds) {
            e.baseline = null
            e.baselineAt = 0
            dropped = true
        }
        if (out.isNotEmpty() || dropped) persistLocked()
        deliverLocked()
        out
    }

    /** Every alert the store has seen, clear ones included. */
    fun records(): List<AlertRecord> = synchronized(lock) { alerts.map { (id, e) -> e.record(id) } }

    /** The table: the alerts that are up (RAISED or ACKED), oldest raise first. */
    fun table(): List<AlertRecord> = synchronized(lock) { tableLocked() }

    fun stateOf(id: String): AlertState = synchronized(lock) { alerts[id]?.state ?: AlertState.CLEAR }

    /** When the alert was acknowledged, while it is ACKED; 0 otherwise (a `change` rule re-takes an older baseline). */
    fun ackedAtOf(id: String): Long = synchronized(lock) {
        alerts[id]?.takeIf { it.state == AlertState.ACKED }?.ackedAt ?: 0L
    }

    /** A `change` rule's saved baseline and when it was taken (dec. F.2), or null. */
    fun baselineOf(id: String): Pair<String, Long>? = synchronized(lock) {
        alerts[id]?.let { e -> e.baseline?.let { it to e.baselineAt } }
    }

    /** Saves a `change` rule's baseline ([text] null = none). No event and no `seq`: the alert itself is unchanged.
     *  A rule never raised gets a CLEAR entry with episode 0 to hold it. */
    fun setBaseline(id: String, text: String?, at: Long) = synchronized(lock) {
        val e = alerts[id] ?: if (text == null) return@synchronized else
            Entry(AlertState.CLEAR, 0, Severity.NORMAL, 0, 0, 0, 0, "", "", false).also { alerts[id] = it }
        val atOrZero = if (text == null) 0L else at
        if (e.baseline == text && e.baselineAt == atOrZero) return@synchronized
        e.baseline = text
        e.baselineAt = atOrZero
        persistLocked()
        deliverLocked()
    }

    /**
     * The answer to `table {since, epoch}` (§2(b)), computed once under the lock. The host adds its state
     * line ([StoreAnswer.stateLine]) before sending it.
     */
    fun answer(since: Long, epoch: Long): StoreAnswer = synchronized(lock) {
        val e = this.epoch
        val s = seqNo
        fun snapshot(truncated: Boolean) = StoreAnswer(e, s, floorNo, true, truncated, emptyList(), tableLocked())
        when {
            epoch == 0L -> snapshot(truncated = false)               // first contact: adopt, no history
            epoch != e -> snapshot(truncated = true)                 // the store was recreated
            since == s -> StoreAnswer(e, s, floorNo, false, false, emptyList(), null)
            since > s -> {
                Log.w(TAG, "a client is ahead of the store: it has seq $since, the store $s (a save that failed " +
                    "before a restart, or a restored copy) - answered with the table")
                snapshot(truncated = true)
            }
            since >= floorNo -> StoreAnswer(e, s, floorNo, true, false, ring.filter { it.seq > since }, tableLocked())
            else -> snapshot(truncated = true)                       // older than the ring: every PC restart
        }
    }

    // ---- under the lock -------------------------------------------------------------------------------------

    private fun tableLocked(): List<AlertRecord> =
        alerts.filter { it.value.state != AlertState.CLEAR }
            .map { (id, e) -> e.record(id) }
            .sortedWith(compareBy({ it.raisedAt }, { it.id }))

    private fun raiseLocked(id: String, severity: Severity, text: String, value: String, builtIn: Boolean): AlertEvent? {
        val now = clock()
        val e = alerts.getOrPut(id) { Entry(AlertState.CLEAR, 0, severity, 0, 0, 0, 0, text, value, builtIn) }
        if (e.state != AlertState.CLEAR) return null
        e.state = AlertState.RAISED
        e.episode += 1
        e.severity = severity
        e.raisedAt = now
        e.ackedAt = 0
        e.lastNotifiedAt = now
        e.repeatNo = 0
        e.text = text
        e.value = value
        e.builtIn = builtIn
        return emitLocked(EventKind.RAISED, id, e, now)
    }

    private fun clearLocked(id: String): AlertEvent? {
        val e = alerts[id] ?: return null
        if (e.state == AlertState.CLEAR) return null
        e.state = AlertState.CLEAR
        return emitLocked(EventKind.CLEARED, id, e, clock())
    }

    private fun emitLocked(kind: EventKind, id: String, e: Entry, at: Long): AlertEvent {
        val ev = AlertEvent(++seqNo, kind, id, e.episode, e.severity, at, e.raisedAt, e.text, e.value, e.repeatNo)
        ring.addLast(ev)
        while (ring.size > RING_CAP) floorNo = ring.removeFirst().seq
        pending += ev
        return ev
    }

    private fun deliverLocked() {
        if (pending.isEmpty()) return
        val out = pending.toList()
        pending.clear()
        for (ev in out) for (l in listeners) {
            try {
                l(ev)
            } catch (x: Exception) {
                Log.e(TAG, "an alert listener failed on event ${ev.seq} (${ev.kind.wire} ${ev.id})", x)
            }
        }
    }

    /** Saves; on failure says so once per run of failures and raises [STORE_ALERT] in memory (never saved by
     *  this call: the next change tries again). A save that works after failures clears [STORE_ALERT]. */
    private fun persistLocked() {
        val err = saveLocked()
        if (err != null) {
            failedLocked(err)
            return
        }
        if (!saveFailing) return
        saveFailing = false
        Log.i(TAG, "${file.fileName} is saved again")
        if (alerts[STORE_ALERT]?.state.let { it != null && it != AlertState.CLEAR }) {
            clearLocked(STORE_ALERT)
            saveLocked()?.let { failedLocked(it) }
        }
    }

    private fun failedLocked(err: Exception) {
        if (!saveFailing) {
            saveFailing = true
            Log.e(TAG, "${file.fileName} not saved - the alerts stay in memory and the next change tries again; " +
                "a restart now may repeat or miss alerts", err)
        }
        if (alerts[STORE_ALERT]?.state.let { it == null || it == AlertState.CLEAR }) {
            raiseLocked(STORE_ALERT, Severity.NORMAL,
                "Alerts: ${file.fileName} could not be saved (${err::class.simpleName}). A restart may repeat or miss alerts.",
                "", builtIn = true)
        }
    }

    /** Null when saved; else the failure. */
    private fun saveLocked(): Exception? = try {
        val saved = Saved(epoch, seqNo, alerts.mapValuesTo(LinkedHashMap()) { (_, e) ->
            SavedAlert(e.state.wire, e.episode, e.severity.wire, e.raisedAt, e.ackedAt, e.lastNotifiedAt,
                e.repeatNo, e.text, e.value, e.builtIn, e.baseline, e.baselineAt)
        })
        file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.write(tmp, json.encodeToString(Saved.serializer(), saved).toByteArray(Charsets.UTF_8))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        null
    } catch (e: Exception) {
        e
    }

    private fun decode(text: String): Saved {
        val s = json.decodeFromString(Saved.serializer(), text)
        require(s.epoch > 0) { "epoch ${s.epoch} is not above 0" }
        require(s.seq >= 0) { "seq ${s.seq} is below 0" }
        for ((id, a) in s.alerts) {
            val st = requireNotNull(AlertState.of(a.state)) { "alert $id: state \"${a.state}\"" }
            requireNotNull(Severity.of(a.severity)) { "alert $id: severity \"${a.severity}\"" }
            require(a.episode >= 0 && (st == AlertState.CLEAR || a.episode > 0)) { "alert $id: episode ${a.episode}" }
        }
        return s
    }

    companion object {
        private const val TAG = "bastion"
        /** The events kept in memory for `table {since}` (the Torrents ring's size). */
        const val RING_CAP = 200
        /** The built-in alert a failed save raises. */
        const val STORE_ALERT = "watcher.store"
    }
}

enum class AlertState(val wire: String) {
    CLEAR("clear"), RAISED("raised"), ACKED("acked");

    companion object {
        fun of(wire: String): AlertState? = entries.firstOrNull { it.wire == wire }
    }
}

enum class EventKind(val wire: String) {
    RAISED("raised"), REPEAT("repeat"), ACKED("acked"), CLEARED("cleared");

    companion object {
        fun of(wire: String): EventKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** One alert as the channel carries it — `CONTRACT.md` §7.1's shape (id = the rule id, episode, severity,
 *  raisedAt, acknowledged) plus the watcher's own fields (§2(b) of the build's spec). */
data class AlertRecord(
    val id: String,
    val episode: Long,
    val severity: Severity,
    val raisedAt: Long,
    val state: AlertState,
    val ackedAt: Long,
    val text: String,
    val value: String,
    val repeatNo: Int,
    val builtIn: Boolean,
) {
    val acknowledged: Boolean get() = state == AlertState.ACKED

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id); put("episode", episode); put("severity", severity.wire); put("raisedAt", raisedAt)
        put("acknowledged", acknowledged); put("state", state.wire); put("ackedAt", ackedAt)
        put("text", text); put("value", value); put("repeatNo", repeatNo); put("builtIn", builtIn)
    }

    companion object {
        /** Throws on a shape it does not know (a missing field, a wrong type, an unknown state). */
        fun fromJson(o: JsonObject): AlertRecord = AlertRecord(
            o.str("id"), o.prim("episode").long, sev(o.str("severity")), o.prim("raisedAt").long,
            requireNotNull(AlertState.of(o.str("state"))) { "alert state \"${o.str("state")}\"" },
            o.prim("ackedAt").long, o.str("text"), o.str("value"), o.prim("repeatNo").int, o.prim("builtIn").boolean,
        )
    }
}

/** One change, in `seq` order: `{seq, kind, id, episode, severity, at, raisedAt, text, value, repeatNo}`. */
data class AlertEvent(
    val seq: Long,
    val kind: EventKind,
    val id: String,
    val episode: Long,
    val severity: Severity,
    val at: Long,
    val raisedAt: Long,
    val text: String,
    val value: String,
    val repeatNo: Int,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("seq", seq); put("kind", kind.wire); put("id", id); put("episode", episode)
        put("severity", severity.wire); put("at", at); put("raisedAt", raisedAt)
        put("text", text); put("value", value); put("repeatNo", repeatNo)
    }

    companion object {
        fun fromJson(o: JsonObject): AlertEvent = AlertEvent(
            o.prim("seq").long,
            requireNotNull(EventKind.of(o.str("kind"))) { "event kind \"${o.str("kind")}\"" },
            o.str("id"), o.prim("episode").long, sev(o.str("severity")), o.prim("at").long,
            o.prim("raisedAt").long, o.str("text"), o.str("value"), o.prim("repeatNo").int,
        )
    }
}

/** The answer to `ack {id, episode}`: `{ok, state, reason?}`. */
data class AckResult(val ok: Boolean, val state: AlertState, val reason: String?) {
    fun toJson(): JsonObject = buildJsonObject {
        put("ok", ok); put("state", state.wire); if (reason != null) put("reason", reason)
    }

    companion object {
        fun fromJson(o: JsonObject): AckResult = AckResult(
            o.prim("ok").boolean,
            requireNotNull(AlertState.of(o.str("state"))) { "ack state \"${o.str("state")}\"" },
            (o["reason"] as? JsonPrimitive)?.contentOrNull,
        )
    }
}

/** The answer to `table {since, epoch}`: `{epoch, seq, floor, changed, truncated, state, events, table?}` —
 *  `table` present iff [changed] (§2(b)). [stateLine] is the host's (the watcher's), empty from the store. */
data class StoreAnswer(
    val epoch: Long,
    val seq: Long,
    val floor: Long,
    val changed: Boolean,
    val truncated: Boolean,
    val events: List<AlertEvent>,
    val table: List<AlertRecord>?,
    val stateLine: String = "",
    /** Package F (W-decisions G.1): the PC's connection-path signal for the phone — true when the phone's link
     *  arrives over a private path on the bus network ([PhonePresence.onBusNetwork]); null from a host with no
     *  presence value (absent on the wire). A word, never an address. */
    val bus: Boolean? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("epoch", epoch); put("seq", seq); put("floor", floor)
        put("changed", changed); put("truncated", truncated); put("state", stateLine)
        put("events", buildJsonArray { for (e in events) add(e.toJson()) })
        if (table != null) put("table", buildJsonArray { for (r in table) add(r.toJson()) })
        if (bus != null) put("bus", bus)
    }

    companion object {
        fun fromJson(o: JsonObject): StoreAnswer = StoreAnswer(
            o.prim("epoch").long, o.prim("seq").long, o.prim("floor").long,
            o.prim("changed").boolean, o.prim("truncated").boolean,
            (o["events"] as? JsonArray ?: throw IllegalArgumentException("answer: no events list"))
                .map { AlertEvent.fromJson(it as? JsonObject ?: throw IllegalArgumentException("answer: an event is not an object")) },
            (o["table"] as? JsonArray)?.map {
                AlertRecord.fromJson(it as? JsonObject ?: throw IllegalArgumentException("answer: a record is not an object"))
            },
            (o["state"] as? JsonPrimitive)?.contentOrNull ?: "",
            (o["bus"] as? JsonPrimitive)?.booleanOrNull,
        )
    }
}

private fun JsonObject.prim(key: String): JsonPrimitive =
    this[key] as? JsonPrimitive ?: throw IllegalArgumentException("alert field \"$key\" missing")

private fun JsonObject.str(key: String): String =
    prim(key).takeIf { it.isString }?.content ?: throw IllegalArgumentException("alert field \"$key\" is not text")

private fun sev(wire: String): Severity = requireNotNull(Severity.of(wire)) { "severity \"$wire\"" }
