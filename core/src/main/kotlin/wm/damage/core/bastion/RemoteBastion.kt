package wm.damage.core.bastion

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wm.damage.core.net.RemoteWin
import wm.damage.core.util.Log

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build W adds a reader on his PC that only reads his own home hub and raises alerts; this is the PHONE's side of
 * the `bastion` window channel (R6 P7; the lead's decision B.3; the spec's §2(a), §2(b)). It keeps a copy of the
 * PC's alerts, hands every change to [AlertDelivery], and makes sure nothing is said twice.
 *
 * - The channel: a [RemoteWin] on the content port (`"bastion"`, 5 s pacing between connection attempts). On
 *   channel up it asks `table {since, epoch}` at once, then every [pingMs] (15 s: an idle channel otherwise sends no
 *   frame, and "home unreachable" reads [lastHeardMs]); a ping is skipped while one is in flight. Pushes (op `event`)
 *   are handed off from the read thread; one in sequence is applied, a gap or another epoch asks `table`. A push
 *   that is not applied is journaled once per reason per connection.
 * - Re-asks (a gap, an answer this copy cannot use, a change it could not handle) are PACED: one goes at once when
 *   the last re-ask went out at least [reaskPacingMs] ago, else the next ping carries it — never an immediate loop
 *   (review 1, finding 1: an answer that sets a re-ask whose next answer is the same one).
 * - The state: `cacheDir/delivery.json` `{epoch, lastSeq, alerts:{id:{…, saidEpisode, saidRepeat, heldSince?}}}`.
 *   "Said" means delivered (or adopted) for (epoch, id, episode). Every change is handled on ONE coroutine, which
 *   checks and sets the mark in one step and then writes the file (a temp file moved into place). A failed write is
 *   said once per run of failures, kept in memory and written again with the next change.
 * - Quiet hours (dec. B.2): a NORMAL raise or repeat inside the window is held ([Seen.heldSince]); at every tick and
 *   after every answer, outside the window, an alert still raised with the same episode is delivered once ("since
 *   HH:MM") and the mark dropped; an acknowledged, cleared or vanished alert drops it silently.
 *
 * - Package F (W-decisions G.1–G.3): every `table` ask carries [place]'s report (`presence {word, fixAgeMs?}`: the
 *   word and the fix's age, never a position), and every answer's `bus` flag goes back to [place] (false while the
 *   channel is down), so the phone learns where the bus is only while the PC sees it on the bus network.
 *
 * Nothing here waits on time: every wait is pacing between asks, and a request in flight ends with its answer or
 * with the link's end (`RemoteWin.failPending`).
 */
class RemoteBastion(
    host: String,
    port: Int,
    token: String,
    private val scope: CoroutineScope,
    cacheDir: Path?,
    private val delivery: AlertDelivery,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** The table ping's pacing (the spec's 15 s; the tests shorten it). */
    private val pingMs: Long = PING_MS,
    retryPacingMs: Long = RETRY_MS,
    /** The least spacing between two re-asks sent at once (pacing; inside it the next ping asks). */
    private val reaskPacingMs: Long = REASK_MS,
    /** The phone's position's side of the channel (package F's [PlaceWatch]); null: no report, `bus` unread. */
    private val place: PlaceLink? = null,
) : AutoCloseable {

    /** The copy of one alert, and what this phone has said about it. Touched only on the processing coroutine. */
    private class Seen(
        var episode: Long, var state: AlertState, var severity: Severity, var raisedAt: Long, var ackedAt: Long,
        var text: String, var value: String, var repeatNo: Int, var builtIn: Boolean,
        var saidEpisode: Long = 0, var saidRepeat: Int = 0, var heldSince: Long? = null,
    ) {
        fun record(id: String) = AlertRecord(id, episode, severity, raisedAt, state, ackedAt, text, value, repeatNo, builtIn)

        /** The PC's fields of [r]; a new episode starts with nothing said and nothing held. */
        fun take(r: AlertRecord) {
            if (r.episode != episode) { saidEpisode = 0; saidRepeat = 0; heldSince = null }
            episode = r.episode; state = r.state; severity = r.severity; raisedAt = r.raisedAt; ackedAt = r.ackedAt
            text = r.text; value = r.value; repeatNo = r.repeatNo; builtIn = r.builtIn
        }

        companion object {
            fun of(r: AlertRecord) = Seen(r.episode, r.state, r.severity, r.raisedAt, r.ackedAt, r.text, r.value,
                r.repeatNo, r.builtIn)
        }
    }

    @Serializable
    private data class SavedSeen(
        val episode: Long, val state: String, val severity: String, val raisedAt: Long, val ackedAt: Long = 0,
        val text: String = "", val value: String = "", val repeatNo: Int = 0, val builtIn: Boolean = false,
        val saidEpisode: Long = 0, val saidRepeat: Int = 0, val heldSince: Long? = null,
    )

    @Serializable
    private data class Saved(val epoch: Long, val lastSeq: Long, val alerts: Map<String, SavedSeen> = emptyMap())

    private sealed class Work {
        /** [sentAt]: when the ask it replies to was sent (verify 3: the place watch's confirm age is read there). */
        class Answer(val a: StoreAnswer, val sentAt: Long) : Work()
        class Push(val args: JsonObject) : Work()
        object Tick : Work()
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file: Path? = cacheDir?.resolve(FILE)

    // ---- the copy: the processing coroutine only ----------------------------------------------------------------
    private var epoch = 0L
    private var lastSeq = 0L
    private val alerts = LinkedHashMap<String, Seen>()
    /** An answer has been applied in this process: the copy says what the PC holds, so a held alert may be released
     *  (B.2's "still RAISED" is a fact about the PC's store, not about a file read at start). */
    private var fresh = false
    private var dirty = false
    private var writeFailing = false

    // ---- read from other threads ----------------------------------------------------------------------------------
    @Volatile private var cursorEpoch = 0L
    @Volatile private var cursorSeq = 0L
    @Volatile private var raisedView: List<AlertRecord> = emptyList()
    @Volatile private var heldView: Set<String> = emptySet()
    @Volatile private var hostLine = ""
    @Volatile private var chanUp = false
    /** [ch] once it is assigned: its state hook can run before its constructor returns (see the init after it). */
    @Volatile private var chRef: RemoteWin? = null
    @Volatile private var running = true
    private val asking = AtomicBoolean(false)
    @Volatile private var askAgain = false
    @Volatile private var askedAt = 0L
    @Volatile private var skipSaid = false
    /** When the last re-ask went out at once ([reaskPaced]); MIN_VALUE until one has. */
    @Volatile private var reaskedAt = Long.MIN_VALUE
    /** The reasons a push was not applied that this connection has journaled (cleared at every channel up). */
    private val dropSaid: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val handledCount = AtomicLong(0)
    private val tickCount = AtomicLong(0)

    init {
        load()?.let { s ->
            epoch = s.epoch
            lastSeq = s.lastSeq
            for ((id, v) in s.alerts) alerts[id] = Seen(v.episode,
                requireNotNull(AlertState.of(v.state)), requireNotNull(Severity.of(v.severity)), v.raisedAt, v.ackedAt,
                v.text, v.value, v.repeatNo, v.builtIn, v.saidEpisode, v.saidRepeat, v.heldSince)
            Log.i(TAG, "delivery.json: epoch $epoch, seq $lastSeq, ${alerts.size} alert(s) known")
        }
        publish()
    }

    private val inbox = Channel<Work>(Channel.UNLIMITED)

    private val processor: Job = scope.launch(Dispatchers.IO) {
        for (w in inbox) {
            try {
                when (w) {
                    is Work.Answer -> onAnswer(w.a, w.sentAt)
                    is Work.Push -> onPush(w.args)
                    Work.Tick -> { release(); tickCount.incrementAndGet() }
                }
            } catch (e: Exception) {
                // one change this build cannot handle must not end the loop: said, and the table recovers it as state
                Log.e(TAG, "an alert change from the PC was not handled (${w::class.simpleName}); asking for the table", e)
                askAgain = true
            }
            if (dirty) save()
            publish()
            handledCount.incrementAndGet()
            reaskPaced()
        }
    }

    private val pinger: Job = scope.launch(Dispatchers.IO) {
        while (isActive && running) {
            delay(pingMs)                              // pacing between pings, not a timeout
            if (chanUp) ask(gap = false)
            enqueue(Work.Tick)
        }
    }

    // declared AFTER every field it touches: RemoteWin launches its connect loop in its constructor and the state
    // hook can run at once (the Torrents provider's ordering, `TorrentsNet.kt`)
    private val ch = RemoteWin(host, port, token, BastionService.WIN_ID, scope, retryPacingMs = retryPacingMs,
        onState = { s ->
            chanUp = s.isEmpty()
            if (!chanUp) place?.onBus(false, clock())   // no answer to say so: not known to be on the bus network
            if (chanUp) {
                dropSaid.clear()                       // a new connection: each drop reason is said again once
                ask(gap = true)                        // the channel just came up (or back): the table at once
            }
        },
        onPush = { op, args, _ ->
            if (op == BastionService.OP_EVENT) enqueue(Work.Push(args))
            else dropped("unknown op", "an unknown push '$op' from the PC was ignored")
        })

    init {
        chRef = ch
        if (chanUp) ask(gap = true)                    // the channel came up before [ch] was assigned
    }

    // ---- the API ------------------------------------------------------------------------------------------------

    /** The alerts RAISED now, as this copy knows them, oldest raise first (the Acknowledge row's supplier). */
    fun raised(): List<AlertRecord> = raisedView

    /** When the PC last sent any frame on this channel (0 until one): the "home unreachable" judge's input. */
    fun lastHeardMs(): Long = ch.lastHeardMs

    /** The channel's own line while it is down ("PC unreachable Ns"), else the PC watcher's state line. */
    fun stateLine(): String = ch.stateLine.ifEmpty { hostLine }

    /** The Bastion category's `Watcher` row (fix round 1, R1.2(b)): the PC watcher's state line from its last
     *  answer, or [HOME_DOWN] while the channel is down (the line it last sent is no longer known to hold). */
    fun watcherLine(): String = when {
        !chanUp -> HOME_DOWN
        hostLine.isEmpty() -> "waiting for home's first answer"
        else -> hostLine
    }

    /**
     * The channel's `ack {id, episode}` (dec. B.3: acknowledge is an action on the alert). BLOCKING — call it off the
     * shell's loop. Throws when the channel is down or ends before the answer: nothing is queued and nothing is
     * retried (the caller says "not acknowledged — home not answering"). The PC's `acked` event then updates the
     * shade through the push.
     */
    fun ack(id: String, episode: Long): AckResult {
        val a = ch.request(BastionService.OP_ACK, buildJsonObject { put("id", id); put("episode", episode) })
        return AckResult.fromJson(a.data)
    }

    override fun close() {
        running = false
        pinger.cancel()
        ch.close()
        inbox.close()
        processor.cancel()
    }

    // ---- test hooks (same module) -------------------------------------------------------------------------------
    internal fun held(): Set<String> = heldView
    internal fun handled(): Long = handledCount.get()
    /** The pinger's ticks handled: the unit a test measures re-asks against. */
    internal fun ticked(): Long = tickCount.get()
    internal fun cursor(): Pair<Long, Long> = cursorEpoch to cursorSeq

    // ---- asking ---------------------------------------------------------------------------------------------------

    private fun enqueue(w: Work) {
        val r = inbox.trySend(w)
        if (!r.isSuccess && running) Log.e(TAG, "the alert queue refused ${w::class.simpleName}: ${r.exceptionOrNull()?.message ?: "closed"}")
    }

    /** One `table` ask. While one is in flight a ping is skipped (said once per stuck ask); a gap's ask is kept and
     *  sent after the answer in flight is handled. */
    private fun ask(gap: Boolean) {
        if (!running) return
        val c = chRef ?: return                        // the init after [ch] asks once it is assigned
        if (!asking.compareAndSet(false, true)) {
            if (gap) askAgain = true
            else if (!skipSaid) {
                skipSaid = true
                Log.i(TAG, "the table ask sent ${(clock() - askedAt) / 1000} s ago is still in flight: pings are skipped until it ends")
            }
            return
        }
        val sentAt = clock()
        askedAt = sentAt
        skipSaid = false
        askAgain = false                               // this ask carries the cursor: it answers every re-ask owed
        val since = cursorSeq
        val ep = cursorEpoch
        scope.launch(Dispatchers.IO) {
            var ans: StoreAnswer? = null
            // package F: the word and the fix's age ride on the ask; a report that fails is said, the ask goes without
            val rep = try { place?.report() } catch (e: Exception) {
                Log.e(TAG, "the phone's presence report failed; this table ask carries none", e)
                null
            }
            try {
                val a = c.request(BastionService.OP_TABLE, buildJsonObject {
                    put("since", since); put("epoch", ep)
                    if (rep != null) put("presence", rep.toJson())
                })
                ans = try { StoreAnswer.fromJson(a.data) } catch (e: Exception) {
                    Log.w(TAG, "the PC's table answer was not understood: ${e.message}")
                    hostLine = "the PC's answer was not understood"
                    null
                }
            } catch (e: Exception) {
                // the link ended under it or was down: the channel's own line says so ("PC unreachable Ns")
                Log.i(TAG, "a table ask ended without an answer: ${e.message}")
            } finally {
                asking.set(false)
            }
            if (ans != null) enqueue(Work.Answer(ans, sentAt))
            else reaskPaced()
        }
    }

    /** A re-ask owed ([askAgain]) goes out at once when the last one that did went out at least [reaskPacingMs] ago
     *  (or the clock went back); otherwise it stays owed and the next ping carries it. Pacing between asks: no path
     *  in this file can send asks back to back on answers that will not change. */
    private fun reaskPaced() {
        if (!askAgain || !chanUp || asking.get()) return
        val now = clock()
        val last = reaskedAt
        if (last != Long.MIN_VALUE && now >= last && now - last < reaskPacingMs) return
        reaskedAt = now
        ask(gap = true)
    }

    /** A change from the PC this copy does not apply: journaled once per [reason] per connection. */
    private fun dropped(reason: String, line: String, warn: Boolean = false) {
        if (!dropSaid.add(reason)) return
        val text = "$line (said once per connection)"
        if (warn) Log.w(TAG, text) else Log.i(TAG, text)
    }

    // ---- the processing coroutine ---------------------------------------------------------------------------------

    private fun onAnswer(a: StoreAnswer, sentAt: Long) {
        hostLine = a.stateLine
        place?.onBus(a.bus == true, sentAt)            // package F: the PC's path signal, whatever the table holds
        if (!a.truncated && epoch != 0L && a.epoch == epoch && a.seq < lastSeq) {
            // older than pushes already applied (an answer and a later push reach this coroutine by two paths): its
            // table is behind this copy, so it is not reconciled against; a fresh one is asked for (paced). A
            // TRUNCATED answer is a snapshot and is taken below (§2(b)): this copy ahead of the store (a save that
            // failed before a PC restart, or a restored alerts.json) is answered that way every time, so refusing
            // it would ask again for the same answer while every new push is dropped (review 1, finding 1)
            dropped("answer behind", "a table answer at seq ${a.seq} is behind pushes already applied (this copy " +
                "at $lastSeq); asked again")
            askAgain = true
            return
        }
        if (a.changed) {
            val table = a.table
            if (table == null) {
                Log.w(TAG, "a changed table answer came without its table; asked again")
                askAgain = true
                return
            }
            when {
                epoch == 0L -> firstContact(a, table)
                a.epoch != epoch -> newEpoch(a, table)
                a.truncated -> {
                    Log.i(TAG, "the PC's event ring no longer holds everything since $lastSeq: reconciled from its table")
                    reconcile(table)
                    lastSeq = a.seq
                }
                else -> {
                    for (ev in a.events.sortedBy { it.seq }) if (ev.seq > lastSeq) { applyEvent(ev); lastSeq = ev.seq }
                    reconcile(table)
                    lastSeq = a.seq
                }
            }
            dirty = true
        }
        fresh = true
        release()
    }

    private fun onPush(args: JsonObject) {
        val pe = args["epoch"]?.jsonPrimitive?.longOrNull
        val ev = try { AlertEvent.fromJson(args["event"]?.jsonObject ?: throw IllegalArgumentException("no event")) }
            catch (e: Exception) {
                dropped("not understood", "a push from the PC was not understood (${e.message}); asking for the table",
                    warn = true)
                askAgain = true
                return
            }
        when {
            epoch == 0L -> {
                dropped("no contact", "push ${ev.seq} dropped: no answer from the PC yet; asking for the table")
                askAgain = true
                return
            }
            pe != epoch -> {
                dropped("another epoch", "push ${ev.seq} dropped: it is another store's (epoch $pe, this copy " +
                    "$epoch); asking for the table")
                askAgain = true
                return
            }
            ev.seq <= lastSeq -> {
                // an answer already carried it, or this copy is ahead of the store until a truncated answer lands
                dropped("at or below", "push ${ev.seq} dropped: this copy is at seq $lastSeq (an answer already " +
                    "carried it)")
                return
            }
            ev.seq != lastSeq + 1 -> {
                dropped("gap", "push ${ev.seq} dropped: a gap after this copy's seq $lastSeq; asking for the table")
                askAgain = true                                           // the table recovers it as state
                return
            }
        }
        applyEvent(ev)
        lastSeq = ev.seq
        dirty = true
    }

    /** No file (or an unreadable one): adopt the PC's epoch and seq; every alert up is marked said and its shade
     *  notification posted silently (held inside quiet hours when it has none); ours with no record withdrawn. No
     *  glasses notice (`CONTRACT.md` §7.3). */
    private fun firstContact(a: StoreAnswer, table: List<AlertRecord>) {
        epoch = a.epoch
        lastSeq = a.seq
        alerts.clear()
        val shown = delivery.shownIds()
        for (r in table) {
            val s = Seen.of(r).apply { saidEpisode = r.episode; saidRepeat = r.repeatNo }
            if (delivery.adopt(r, shown) == AlertDelivery.Outcome.HELD) s.heldSince = r.raisedAt
            alerts[r.id] = s
        }
        val ids = table.map { it.id }.toSet()
        for (id in shown - ids) delivery.cancel(id)
        Log.i(TAG, "first contact with the PC's alert store (epoch ${a.epoch}, seq ${a.seq}): ${table.size} alert(s) " +
            "up, adopted without a glasses notice")
    }

    /** The PC's store is another one: an alert this copy held up is carried over (marked said under the new epoch,
     *  its shade notification re-posted silently, a held one kept held); the rest reconciled as a truncated answer. */
    private fun newEpoch(a: StoreAnswer, table: List<AlertRecord>) {
        Log.i(TAG, "the PC's alert store is a new one (epoch $epoch, now ${a.epoch}): carried over by id from its table")
        val old = LinkedHashMap(alerts)
        alerts.clear()
        epoch = a.epoch
        lastSeq = a.seq
        val shown = delivery.shownIds()
        for (r in table) {
            val prev = old[r.id]
            if (prev != null && prev.state != AlertState.CLEAR) {
                val s = Seen.of(r).apply { saidEpisode = r.episode; saidRepeat = r.repeatNo }
                if (prev.heldSince != null && r.state == AlertState.RAISED) s.heldSince = r.raisedAt
                else if (delivery.adopt(r, shown) == AlertDelivery.Outcome.HELD) s.heldSince = r.raisedAt
                alerts[r.id] = s
            } else reconcileOne(r)
        }
        val ids = table.map { it.id }.toSet()
        for (id in shown - ids) delivery.cancel(id)
    }

    /** The table reconcile: a RAISED alert not said is delivered (subject to quiet hours), an acknowledgement this
     *  copy missed updates the shade silently, and an id gone from the table is withdrawn silently. */
    private fun reconcile(table: List<AlertRecord>) {
        val ids = table.map { it.id }.toSet()
        for (r in table) reconcileOne(r)
        val gone = alerts.keys.filter { it !in ids }
        for (id in gone) alerts.remove(id)
        for (id in (gone + delivery.shownIds()).toSet() - ids) delivery.cancel(id)
    }

    private fun reconcileOne(r: AlertRecord) {
        val prev = alerts[r.id]
        val wasRaised = prev != null && prev.episode == r.episode && prev.state == AlertState.RAISED
        val known = prev != null && prev.episode == r.episode
        val s = prev ?: Seen.of(r).also { alerts[r.id] = it }
        s.take(r)
        when (r.state) {
            AlertState.RAISED -> if (s.saidEpisode < r.episode) say(r.id, s, AlertDelivery.Kind.RAISED)
            AlertState.ACKED -> if (wasRaised || !known) {
                s.heldSince = null
                s.saidEpisode = r.episode
                s.saidRepeat = r.repeatNo
                delivery.acked(s.record(r.id))
            }
            AlertState.CLEAR -> {}
        }
    }

    private fun applyEvent(ev: AlertEvent) {
        val prev = alerts[ev.id]
        if (prev != null && prev.episode > ev.episode) return            // an older episode's event: nothing to do
        when (ev.kind) {
            EventKind.RAISED -> {
                val s = entryFor(ev, prev)
                s.state = AlertState.RAISED
                if (s.saidEpisode < ev.episode) say(ev.id, s, AlertDelivery.Kind.RAISED)
            }
            EventKind.REPEAT -> {
                val s = entryFor(ev, prev)
                if (s.state == AlertState.RAISED && ev.repeatNo > s.saidRepeat) say(ev.id, s, AlertDelivery.Kind.REPEAT)
            }
            EventKind.ACKED -> {
                val s = entryFor(ev, prev)
                val wasAcked = prev != null && prev.episode == ev.episode && prev.state == AlertState.ACKED
                s.state = AlertState.ACKED
                s.ackedAt = ev.at
                s.heldSince = null
                if (s.saidEpisode < ev.episode) { s.saidEpisode = ev.episode; s.saidRepeat = ev.repeatNo }
                if (!wasAcked) delivery.acked(s.record(ev.id))
            }
            EventKind.CLEARED -> {
                alerts.remove(ev.id)
                val raiseSaid = prev != null && prev.episode == ev.episode && prev.saidEpisode == ev.episode
                delivery.cleared(AlertRecord(ev.id, ev.episode, ev.severity, ev.raisedAt, AlertState.CLEAR, 0,
                    ev.text, ev.value, ev.repeatNo, prev?.builtIn ?: false), ev.at, raiseSaid)
            }
        }
    }

    /** The entry for [ev]'s episode: [prev] brought up to the event, or a new one (nothing said) for a new episode. */
    private fun entryFor(ev: AlertEvent, prev: Seen?): Seen {
        if (prev != null && prev.episode == ev.episode) {
            prev.severity = ev.severity; prev.raisedAt = ev.raisedAt; prev.text = ev.text; prev.value = ev.value
            prev.repeatNo = maxOf(prev.repeatNo, ev.repeatNo)
            return prev
        }
        val s = Seen(ev.episode, AlertState.RAISED, ev.severity, ev.raisedAt, 0, ev.text, ev.value, ev.repeatNo,
            prev?.builtIn ?: false)
        alerts[ev.id] = s
        return s
    }

    /** Deliver a raise or a repeat, or hold it: the said mark is checked by the caller and set here, in one step. */
    private fun say(id: String, s: Seen, kind: AlertDelivery.Kind) {
        when (delivery.alert(s.record(id), kind)) {
            AlertDelivery.Outcome.HELD -> if (s.heldSince == null) s.heldSince = s.raisedAt
            AlertDelivery.Outcome.DELIVERED -> { s.saidEpisode = s.episode; s.saidRepeat = s.repeatNo; s.heldSince = null }
        }
        dirty = true
    }

    /** The end of quiet hours (every tick, every answer): an alert held and still RAISED is delivered ONCE ("since
     *  HH:MM") and marked said; any other mark is dropped silently. Only once an answer has refreshed the copy. */
    private fun release() {
        if (!fresh || alerts.values.none { it.heldSince != null }) return
        if (delivery.quietNow()) return
        for ((id, s) in alerts) {
            if (s.heldSince == null) continue
            if (s.state == AlertState.RAISED) {
                delivery.alert(s.record(id), AlertDelivery.Kind.RELEASE)
                s.saidEpisode = s.episode
                s.saidRepeat = s.repeatNo
                Log.i(TAG, "quiet hours over: $id (raised ${delivery.hhmm(s.raisedAt)}) delivered once")
            }
            s.heldSince = null
            dirty = true
        }
    }

    private fun publish() {
        raisedView = alerts.filter { it.value.state == AlertState.RAISED }.map { (id, s) -> s.record(id) }
            .sortedWith(compareBy({ it.raisedAt }, { it.id }))
        heldView = alerts.filter { it.value.heldSince != null }.keys.toSet()
        cursorEpoch = epoch
        cursorSeq = lastSeq
    }

    // ---- delivery.json ------------------------------------------------------------------------------------------

    private fun load(): Saved? {
        val path = file ?: return null
        if (!Files.isRegularFile(path)) return null
        return try {
            json.decodeFromString(Saved.serializer(), Files.readString(path, Charsets.UTF_8)).also { s ->
                for ((id, v) in s.alerts) {
                    requireNotNull(AlertState.of(v.state)) { "alert $id: state \"${v.state}\"" }
                    requireNotNull(Severity.of(v.severity)) { "alert $id: severity \"${v.severity}\"" }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "$path is unreadable (${e.message}): the next answer is a first contact (alerts adopted, " +
                "none said again)")
            null
        }
    }

    private fun save() {
        val path = file ?: run { dirty = false; return }
        try {
            val saved = Saved(epoch, lastSeq, alerts.mapValuesTo(LinkedHashMap()) { (_, s) ->
                SavedSeen(s.episode, s.state.wire, s.severity.wire, s.raisedAt, s.ackedAt, s.text, s.value, s.repeatNo,
                    s.builtIn, s.saidEpisode, s.saidRepeat, s.heldSince)
            })
            Files.createDirectories(path.parent)
            val tmp = path.resolveSibling("$FILE.tmp")
            Files.writeString(tmp, json.encodeToString(Saved.serializer(), saved), Charsets.UTF_8)
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            dirty = false
            if (writeFailing) { writeFailing = false; Log.i(TAG, "$path written again") }
        } catch (e: Exception) {
            // kept in memory (dirty stays set): written again after the next change this coroutine handles
            if (!writeFailing) {
                writeFailing = true
                Log.e(TAG, "$path was not written (${e.message}): the said marks are kept in memory and written " +
                    "again with the next change")
            }
        }
    }

    companion object {
        private const val TAG = "bastion-remote"
        const val FILE = "delivery.json"
        const val PING_MS = 15_000L
        const val RETRY_MS = 5_000L
        /** Re-asks sent at once are at least this far apart (the channel's own connection pacing). */
        const val REASK_MS = 5_000L
        const val HOME_DOWN = "home not answering"
    }
}
