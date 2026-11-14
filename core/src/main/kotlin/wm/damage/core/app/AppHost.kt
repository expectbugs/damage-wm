package wm.damage.core.app

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import wm.damage.core.bastion.AlertRule
import wm.damage.core.bastion.AlertStore
import wm.damage.core.bastion.Severity
import wm.damage.core.comp.Journal
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P1 (the spec's §2 P1 and §3; `CONTRACT.md` §2.2–§2.4, §9.3, §9.7 item 1, §13 (b); dec. A.12,
 * B.1): the PC side of the record. Every op runs on the one core executor in arrival order: judge → its log line
 * lands → the record takes the effect → the sequence file → the answer → the change is published. Wired nowhere in
 * P1: the `app` lane (P2) hands it ops and carries its changes.
 */

/** How the record says a failure beyond the log: a built-in alert (W's `AlertStore`, delivered to the phone). */
interface RecordAlerts {
    fun raise(id: String, text: String)
    fun clear(id: String)

    companion object {
        /** The log could not take an op's line: ops are refused "retry" until it can. Under the alert store's
         *  reserved `record.` prefix (dec. D.2): no rule takes the id and a watcher start keeps the alert. */
        const val LOG = AlertRule.RECORD_PREFIX + "log"
        /** `record.json` could not be saved: the log keeps what it does not cover; retried each tick. */
        const val SAVE = AlertRule.RECORD_PREFIX + "save"
        /** The sequence file could not be written: changes are not published until it can. */
        const val SEQ = AlertRule.RECORD_PREFIX + "seq"
        /** Fix round 2, ruling 1.5: a start found no readable `record.json` beside logs an earlier start set aside. Raised
         *  by that start and left for him to read (no start clears it): the record was rebuilt from the logs. */
        const val REPLAY = AlertRule.RECORD_PREFIX + "replay"

        val NONE = object : RecordAlerts {
            override fun raise(id: String, text: String) {}
            override fun clear(id: String) {}
        }

        /** Through W's alert store, as one of its built-in alerts. */
        fun of(store: AlertStore) = object : RecordAlerts {
            override fun raise(id: String, text: String) {
                store.raise(id, Severity.NORMAL, text, "", builtIn = true)
            }
            override fun clear(id: String) {
                store.clear(id)
            }
        }
    }
}

class AppHost(
    val dir: Path,
    private val actions: ActionTable,
    val executor: CoreExecutor,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val alerts: RecordAlerts = RecordAlerts.NONE,
    private val journal: Path? = null,
    logSync: (FileChannel) -> Unit = { it.force(true) },
    storeWrite: ((Path, String) -> Unit)? = null,
    storeWriter: Executor? = null,
    private val setAsideBytes: Long = LOG_SET_ASIDE_BYTES,
) {
    val log = OpLog(dir.resolve(LOG_FILE), logSync)
    val store = if (storeWrite == null) RecordStore(dir.resolve(RECORD_FILE), writer = storeWriter)
        else RecordStore(dir.resolve(RECORD_FILE), storeWrite, storeWriter)
    val seqFile = SeqFile(dir.resolve(SEQ_FILE))
    val ring = ChangeRing()

    private val record = Record()
    private val streams = Streams()
    private val listeners = CopyOnWriteArrayList<(Change) -> Unit>()

    /** The record store's epoch (C§9.3): minted at its creation, an incomplete last log line, or a store older than
     *  the sequence file; never at a process start. */
    @Volatile var epoch = 0L
        private set

    /** The last line's `seq`: every applied change takes the next. */
    @Volatile var seq = 0L
        private set

    /** The `seq` the last landed save covers. */
    private var savedThrough = 0L
    private var saving = false
    @Volatile private var opened = false
    /** The log opened and replayed whole at this process's start (fix round 1, ruling 1.7); the executor's. */
    private var logReady = false

    /** True once [open] has run (the `app` lane refuses its hello before). */
    val isOpen: Boolean get() = opened
    /** Fix round 2, ruling 1.6: the log opened and replayed whole at this start ([logReady]); read on the executor. */
    val logOpen: Boolean get() = logReady
    /** Changes committed and answered but not yet published: the sequence file has not taken their `seq`. */
    private val held = ArrayList<Change>()

    private val logFailures = Streak(RecordAlerts.LOG, "the operation log",
        "changes from other devices are refused until it can be written; they stay on the devices and are sent again")
    private val saveFailures = Streak(RecordAlerts.SAVE, "record.json",
        "the log keeps every change it does not cover; the save is tried again on the next tick")
    private val seqFailures = Streak(RecordAlerts.SEQ, "the sequence file",
        "changes are applied and answered but not sent to other screens until it can be written")

    /** What the start found and did. */
    data class Opened(
        val epoch: Long,
        val seq: Long,
        val replayed: Int,
        /** Why a new epoch was minted, or null when the store's own was kept. */
        val newEpoch: String?,
        val setAside: Path?,
        val problems: List<String>,
    )

    fun addListener(l: (Change) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (Change) -> Unit) {
        listeners.remove(l)
    }

    /** At a process start (never from a tool): loads, replays, decides the epoch, saves, and sets the log aside when
     *  due. Runs on the executor; returns when done. Internal: a host builder reaches it only through
     *  [atProcessStart] (dec. D.3; `tools/check_apk_wiring.py` holds who calls that). */
    internal fun open(now: LocalDateTime = LocalDateTime.now()): Opened = executor.call { openNow(now) }

    /** The pacing tick on [scope] every [everyMs] (2 s): held changes published, `record.json` saved when it moved. */
    fun pace(scope: CoroutineScope, everyMs: Long = TICK_MS): Job = scope.launch {
        while (isActive) {
            delay(everyMs)                    // pacing between ticks, not a bound on any work
            tick()
        }
    }

    /** Hands [op] to the executor; [reply] gets its answer there, after its line has landed and before its change is
     *  published. Every op gets exactly one answer. [gate] (S1-1 P6: the device's level, a revoked device) is read on
     *  the executor at apply time, after the stream's number check: a refusal it returns takes the op's number as a
     *  refusal for good and changes nothing. */
    fun act(op: Op, gate: () -> Answer.Refused? = { null }, reply: (Answer) -> Unit) {
        executor.execute("op ${op.window}/${op.action}") { actNow(op, gate, reply) }
    }

    /** The pacing tick: publishes changes the sequence file held back, and saves `record.json` when the record moved. */
    fun tick() {
        executor.execute("record tick") { tickNow() }
    }

    /** The stream's high-water (C§2.4 numbering; `welcome` carries them). */
    fun highWater(key: StreamKey): Long = executor.call { streams.highWater(key) }

    /** Every high-water [device] has, by stream (for its `welcome`). */
    fun highWaters(device: String): Map<String, Long> =
        executor.call { streams.table().filterKeys { it.device == device }.mapKeys { it.key.stream } }

    /** A copy of [window]'s entries, with the epoch and `seq` it was taken at. */
    fun read(window: String): Triple<Long, Long, Effect> = executor.call { Triple(epoch, seq, record.window(window)) }

    fun close() {
        executor.call { log.close() }
        store.close()
    }

    // ---- on the executor ----

    private fun openNow(now: LocalDateTime): Opened {
        check(!opened) { "the record is opened once per process" }
        val problems = ArrayList<String>()
        val loaded = store.load()
        loaded.problem?.let { problems += it }
        val saved = loaded.saved
        val held0 = seqFile.read()
        val covered = saved?.covered ?: 0L
        saved?.let {
            record.apply(Effect(it.items, it.colls))
            streams.load(it.streams)
        }
        var replayedTo = covered
        var replayed = 0
        fun replay(line: OpLog.Line) {
            record.apply(line.effect)
            line.op.queued?.let { q ->
                streams.commit(StreamKey(line.op.device, q.stream), line.hw, line.answer, Streams.identity(line.op))
            }
            replayedTo = line.seq
            replayed++
        }
        // fix round 2, ruling 1.5: no readable record beside logs an earlier start set aside (lines no save holds now):
        // those are replayed first, oldest first, and the start says what it replayed and what it could not
        val aside = if (saved != null || !Files.isDirectory(dir)) emptyList() else try {
            Files.list(dir).use { s -> s.map { it.fileName.toString() }.filter { Journal.isArchiveOf(log.file, it) }.sorted().toList() }
        } catch (e: Exception) {
            problems += "the logs set aside by earlier starts could not be listed (${e::class.simpleName}: ${e.message})"
            emptyList()
        }
        val asideSaid = aside.map { name ->
            try {
                val a = OpLog(dir.resolve(name)).let { l -> try { l.open(0) } finally { l.close() } }
                a.lines.forEach(::replay)
                problems += a.bad.map { "$name: $it" }
                "replayed $name"
            } catch (e: Exception) {
                "could not replay $name (${e::class.simpleName}: ${e.message})"
            }
        }
        var logState: OpLog.Opened? = null
        try {
            val o = log.open(covered)
            o.lines.forEach(::replay)
            logState = o                      // opened AND replayed whole: only then is the log open for this process
            o.partialKept?.let { problems += "the log's incomplete last line was kept as ${it.fileName}" }
            problems += o.bad
        } catch (e: Exception) {
            // fix round 2, ruling 1.8: nothing opens it again in this process, so the alert says what ends the refusals
            logFailures.failed("could not be opened", e, "changes from other devices are refused until the service is " +
                "restarted; they stay on the devices and are sent again")
            problems += "the operation log could not be opened (${e::class.simpleName}: ${e.message})"
        }
        val why = when {
            saved == null && loaded.problem != null -> "record.json was unreadable"
            saved == null -> "a new store"
            logState?.incomplete == true -> "the log's last line was incomplete"
            logState != null && logState.bad.isNotEmpty() -> "the log held ${logState.bad.size} line(s) that did not decode"
            held0 == null -> "the sequence file was missing"
            held0.epoch != saved.epoch -> "the sequence file names another epoch"
            held0.seq > replayedTo -> "the store is older than the sequence file ($replayedTo < ${held0.seq})"
            else -> null
        }
        epoch = if (why == null) saved!!.epoch
            else maxOf(clock(), (saved?.epoch ?: 0L) + 1, (held0?.epoch ?: 0L) + 1, 1L)
        seq = maxOf(replayedTo, held0?.seq ?: 0L)
        ring.reset(seq)
        opened = true
        logReady = logState != null
        val seqOk = writeSeqFile()
        // fix round 1, ruling 1.7: a log that did not open or replay is never covered by a save (its `covered` would pass
        // over the lines this start did not replay, for good); the next good start replays it whole
        val err = if (logReady) store.saveNow(snapshot()) else null
        var setAside: java.nio.file.Path? = null
        if (!logReady) {
            problems += "record.json is not saved while the log is closed; changes are refused with \"retry\" until a " +
                "start opens it"
        } else if (err == null) {
            savedThrough = seq
            saveFailures.ok()
            if (logState != null && (log.length > setAsideBytes || logState.bad.isNotEmpty())) {
                try {
                    setAside = log.setAside(now)
                } catch (e: Exception) {
                    problems += "the log could not be set aside (${e::class.simpleName}: ${e.message}); it keeps growing"
                    Log.e(TAG, "the operation log could not be set aside", e)
                }
            }
        } else {
            saveFailures.failed("could not be saved at the start", err)
        }
        // a run of failures ends with its process: an alert the last process left up is cleared when this start
        // finds the thing working (each clear of an alert that is not up does nothing)
        if (logState != null) logFailures.settledAtStart()
        if (seqOk) seqFailures.settledAtStart()
        if (logReady && err == null) saveFailures.settledAtStart()
        if (aside.isNotEmpty()) {
            val line = "no readable ${RECORD_FILE} beside ${aside.size} log(s) set aside by earlier starts: " +
                asideSaid.joinToString("; ") + ", before ${LOG_FILE}" +
                (if (asideSaid.any { it.startsWith("could not") }) "; the nightly copy holds the record as it was" else "")
            problems += line
            alerts.raise(RecordAlerts.REPLAY, "Record: $line.")
        }
        val said = "epoch $epoch (" + (why?.let { "new: $it" } ?: "kept") + "), seq $seq, $replayed line(s) replayed " +
            "past $covered, ${record.size} entries" + (setAside?.let { "; the log was set aside as ${it.fileName}" } ?: "") +
            (if (problems.isEmpty()) "" else "; " + problems.joinToString("; "))
        if (problems.isEmpty()) Log.i(TAG, "opened: $said") else Log.e(TAG, "opened: $said")
        note("opened: $said")
        return Opened(epoch, seq, replayed, why, setAside, problems)
    }

    private fun actNow(op: Op, gate: () -> Answer.Refused?, reply: (Answer) -> Unit) {
        if (!opened) {
            reply(Answer.Refused("the PC's record is not open yet; send it again", Answer.RETRY))
            return
        }
        if (!logReady) {
            // fix round 1, ruling 1.7: the log did not open or replay at this start (the `record.log` alert is up): no
            // line goes into it and nothing is judged against a record that lacks its lines
            reply(Answer.Refused("the PC could not open its operation log at its start; it is kept and sent again", Answer.RETRY))
            return
        }
        val q = op.queued
        if (q == null) {
            reply(Answer.Refused("live, outbound and guarded actions are not built in this version", Answer.UNSUPPORTED))
            return
        }
        val key = StreamKey(op.device, q.stream)
        val ident = Streams.identity(op)
        when (val c = streams.check(key, q.n, ident)) {
            is Streams.Check.Handled -> {
                reply(c.answer ?: Answer.Seen(again = true))
                return
            }
            is Streams.Check.Reused -> {
                // fix round 1, ruling 2.1: never the other op's answer taken as this one's; nothing is written
                reply(Answer.Refused("number ${c.n} of this stream was already used by another change (another tab " +
                    "of this browser); this one was not applied", Answer.INVALID))
                return
            }
            is Streams.Check.Gap -> {
                reply(Answer.Refused("expected ${c.expected}", Answer.GAP))
                return
            }
            Streams.Check.Next -> {}
        }
        val refused = gate()
        val spec = if (refused == null) actions.spec(op.window, op.action) else null
        val bad = spec?.let { Merge.check(it, op) }
        val verdict = if (spec != null && bad == null) Merge.judge(record, spec, op, clock()) else Verdict.Same
        val answer: Answer = when {
            refused != null -> refused
            spec == null -> Answer.Refused("this PC has no action ${op.action} in ${op.window}", Answer.UNKNOWN)
            bad != null -> Answer.Refused(bad, Answer.INVALID)
            verdict is Verdict.Lost -> Answer.Lost(verdict.losses)
            else -> Answer.Seen()
        }
        val line = OpLog.Line(seq + 1, q.n, op, verdict.effect, answer)
        try {
            log.append(line)
        } catch (e: Exception) {
            logFailures.failed("could not be written", e)
            reply(Answer.Refused("the PC could not write this change to its log; it is kept and sent again", Answer.RETRY))
            return
        }
        logFailures.ok()
        record.apply(verdict.effect)
        streams.commit(key, q.n, answer, ident)
        seq = line.seq
        held += Change(epoch, seq, Origin(op.device, op.id), verdict.effect)
        val publish = writeSeqFile()
        reply(answer)
        if (publish) publishHeld()
    }

    private fun tickNow() {
        if (!opened || !logReady) return                // ruling 1.7: no save covers a log this start did not replay
        if (held.isNotEmpty() && writeSeqFile()) publishHeld()
        if (saving || seq <= savedThrough) return
        saving = true
        val snap = snapshot()
        store.saveAsync(snap) { err ->
            executor.execute("record saved") {
                saving = false
                if (err == null) {
                    savedThrough = snap.covered
                    saveFailures.ok()
                } else {
                    saveFailures.failed("could not be saved", err)
                }
            }
        }
    }

    /** The record as it stands, taken in one step on the executor: the covered `seq` is the last line whose effect
     *  the copy holds. */
    private fun snapshot() = RecordStore.Saved(epoch, seq, record.copyItems(), record.copyColls(), streams.table())

    /** Writes the sequence file with the last `seq`; false (said) when it could not. */
    private fun writeSeqFile(): Boolean = try {
        seqFile.write(epoch, seq)
        seqFailures.ok()
        true
    } catch (e: Exception) {
        seqFailures.failed("could not be written", e)
        false
    }

    private fun publishHeld() {
        val out = ArrayList(held)
        held.clear()
        for (c in out) {
            ring.add(c)
            for (l in listeners) {
                try {
                    l(c)
                } catch (e: Exception) {
                    Log.e(TAG, "a record listener failed on change ${c.seq}", e)
                }
            }
        }
    }

    internal fun note(detail: String) {
        journal?.let { Journal.appendNote(it, "record", detail) }
    }

    /** One kind of failure, said once when a run of them starts (the log, a `record` journal note, the built-in
     *  alert) and again when it ends, with the count. */
    private inner class Streak(private val id: String, private val what: String, private val meaning: String) {
        private var count = 0

        fun failed(how: String, e: Throwable, meaning: String = this.meaning) {
            count++
            if (count > 1) return
            val detail = "$what $how (${e::class.simpleName}: ${e.message}); $meaning"
            Log.e(TAG, detail)
            note("FAILED: $detail")
            alerts.raise(id, "Record: $what $how (${e::class.simpleName}). ${meaning.replaceFirstChar { it.uppercase() }}.")
        }

        fun settledAtStart() {
            if (count == 0) alerts.clear(id)
        }

        fun ok() {
            if (count == 0) return
            val detail = "$what works again after $count failure(s)"
            Log.i(TAG, detail)
            note(detail)
            alerts.clear(id)
            count = 0
        }
    }

    companion object {
        private const val TAG = "record"
        const val RECORD_FILE = "record.json"
        const val LOG_FILE = "oplog.jsonl"
        const val SEQ_FILE = "record.seq"
        /** Dec. A.12: the log is set aside past the journal's size at a process start (64 MiB), never deleted. */
        const val LOG_SET_ASIDE_BYTES = Journal.ROTATE_BYTES
        /** Fix round 2, ruling 1.6: why the lane serves no record while the log is closed. */
        const val LOG_CLOSED = "the PC could not open its operation log at its start: its record is not served until " +
            "the service is restarted; this device keeps its own copy"
        /** The record's pacing tick (the spec's P1: 2 s). */
        const val TICK_MS = 2_000L

        /** The actions of this build's converted windows, by window: Reader's (S1-1 P4). */
        val WINDOWS = ActionTable { w, a -> wm.damage.core.windows.reader.ReaderCore.ACTIONS.spec(w, a) }

        /** The level each of those actions needs (S1-1 P6, C§10.4): Reader's `place` and `open` view; `bookmark`,
         *  `set`, `reset` and `migrate` control. */
        val LEVELS = ActionLevels { w, a ->
            if (w == wm.damage.core.windows.reader.ReaderCore.WINDOW) wm.damage.core.windows.reader.ReaderCore.levelOf(a) else null
        }

        /** The level a window's subscription needs (its description's `min`, C§3.1): Reader view; a window this build
         *  does not know, full. */
        fun minLevel(window: String): String =
            if (window == wm.damage.core.windows.reader.ReaderCore.WINDOW) Levels.VIEW else Levels.FULL

        /**
         * A record of a tool's or a test's own in [dir], opened: the snapshot judge's phone arrangement serves one over
         * loopback (S1-1 P4). Refuses a directory that holds anything — the data directory always does — so a tool
         * can never open the service's record this way (dec. D.3: that is [atProcessStart]'s, at a process start).
         */
        fun inScratchDir(dir: Path, actions: ActionTable = WINDOWS): AppHost {
            require(java.nio.file.Files.isDirectory(dir)) { "$dir is not a directory" }
            java.nio.file.Files.list(dir).use { require(!it.findAny().isPresent) { "$dir is not empty: a scratch record needs a new directory" } }
            return AppHost(dir, actions, CoreExecutor("damage-core-scratch")).also { it.open() }
        }

        /**
         * The PC's record at a PROCESS START (dec. D.3): built over the data directory [dir], opened (load, replay, the
         * epoch, a save, the log set aside when due) and paced on [scope]. Called only from the PC's two host builders
         * (`runShell` and `hostOnly` in `desktop/…/Main.kt`; `tools/check_apk_wiring.py` holds the list), never from a
         * tool flag: opening may set the log aside and writes `record.json` and the sequence file. A start that throws
         * is said (the log, a `record` journal note); the host then answers every op "retry" and the lane "not open".
         */
        fun atProcessStart(
            dir: Path, scope: CoroutineScope, alerts: RecordAlerts, journal: Path?, actions: ActionTable = WINDOWS,
        ): AppHost {
            val h = AppHost(dir, actions, CoreExecutor(), alerts = alerts, journal = journal)
            try {
                h.open()
            } catch (e: Exception) {
                val line = "the record could not be opened (${e::class.simpleName}: ${e.message}); ops are answered " +
                    "\"retry\" and the app lane is refused until a start opens it"
                Log.e(TAG, line, e)
                journal?.let { Journal.appendNote(it, "record", "FAILED: $line") }
            }
            h.pace(scope)
            return h
        }
    }
}
