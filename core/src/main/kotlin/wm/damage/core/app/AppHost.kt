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
 * B.1): the PC side of the record. Every op runs on the one core executor in arrival order: judge (L1 C1: the latest
 * input wins, `Merge`) → its log line lands → the record takes the effect → the answer → the change is published. The `app` lane hands it ops and
 * carries its changes; L1 C2: every subscription takes a snapshot, so no change is held for a resume.
 */

/** One published change: the record's [seq] in [epoch] and the entries it wrote (each item names its writer). */
data class Change(val epoch: Long, val seq: Long, val effect: Effect)

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
    /** The PC's clock: an op's input time is bounded by it (L1 C1), and the PC's own sources stamp their ops with it. */
    val clock: () -> Long = { System.currentTimeMillis() },
    private val alerts: RecordAlerts = RecordAlerts.NONE,
    private val journal: Path? = null,
    logSync: (FileChannel) -> Unit = { it.force(true) },
    storeWrite: ((Path, String) -> Unit)? = null,
    storeWriter: Executor? = null,
    private val setAsideBytes: Long = LOG_SET_ASIDE_BYTES,
    /** S1-2 Q3: each converted window's own apply-time rule ([Windows.RULES]; the notice record's). */
    private val rules: ApplyRule = Windows.RULES,
) {
    val log = OpLog(dir.resolve(LOG_FILE), logSync)
    val store = if (storeWrite == null) RecordStore(dir.resolve(RECORD_FILE), writer = storeWriter)
        else RecordStore(dir.resolve(RECORD_FILE), storeWrite, storeWriter)

    private val record = Record()
    private val listeners = CopyOnWriteArrayList<(Change) -> Unit>()
    /** S1-2 Q2: told on the executor at every pacing tick, before the record's own work (the lane's viewer sweep). */
    private val tickListeners = CopyOnWriteArrayList<() -> Unit>()

    /** The record store's epoch (C§9.3): minted at its creation or when `record.json` was unreadable (L1 C2); never
     *  at a process start. */
    @Volatile var epoch = 0L
        private set

    /** The last line's `seq`: every applied change takes the next. */
    @Volatile var seq = 0L
        private set

    /** The `seq` the last landed save covers. */
    private var savedThrough = 0L
    private var saving = false
    @Volatile private var opened = false
    /** The log opened and replayed whole (fix round 1, ruling 1.7): at this process's start, or on a later tick (L1
     *  R-a); the executor's. */
    private var logReady = false
    /** The `seq` `record.json` covered at this start: where the log's replay begins, at the start and on a retry. */
    private var covered = 0L

    /** True once [open] has run (the `app` lane refuses its hello before). */
    val isOpen: Boolean get() = opened
    /** Fix round 2, ruling 1.6: the log opened and replayed whole ([logReady]); read on the executor. */
    val logOpen: Boolean get() = logReady

    private val logFailures = Streak(RecordAlerts.LOG, "the operation log",
        "changes from other devices are refused until it can be written; a phone keeps them and sends them again, a " +
            "page says so and keeps none")    // S1-2 Q1, N3's class: a page keeps no queue
    private val saveFailures = Streak(RecordAlerts.SAVE, "record.json",
        "the log keeps every change it does not cover; the save is tried again on the next tick")

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

    /** S1-2 Q2: [l] runs on the executor at each pacing tick ([TICK_MS]), whether or not the record is open. */
    fun addTickListener(l: () -> Unit) {
        tickListeners += l
    }

    fun removeTickListener(l: () -> Unit) {
        tickListeners -= l
    }

    /** S1-2 Q2: one note of [kind] in the shell journal (none in host-only): a program op's `program` note. Any
     *  thread; file I/O, so never from the executor on a gesture's path. */
    fun noteAs(kind: String, detail: String) {
        journal?.let { Journal.appendNote(it, kind, detail) }
    }

    fun removeListener(l: (Change) -> Unit) {
        listeners.remove(l)
    }

    /** At a process start (never from a tool): loads, replays, decides the epoch, saves, and sets the log aside when
     *  due. Runs on the executor; returns when done. Internal: a host builder reaches it only through
     *  [atProcessStart] (dec. D.3; `tools/check_apk_wiring.py` holds who calls that). */
    internal fun open(now: LocalDateTime = LocalDateTime.now()): Opened = executor.call { openNow(now) }

    /** The pacing tick on [scope] every [everyMs] (2 s): `record.json` saved when it moved. */
    fun pace(scope: CoroutineScope, everyMs: Long = TICK_MS): Job = scope.launch {
        while (isActive) {
            delay(everyMs)                    // pacing between ticks, not a bound on any work
            tick()
        }
    }

    /** Hands [op] to the executor; [reply] gets its answer there, after its line has landed and before its change is
     *  published. Every op gets exactly one answer. [gate] (S1-1 P6: the device's level, a revoked device) is read on
     *  the executor at apply time: a refusal it returns is for good and changes nothing. */
    fun act(op: Op, gate: () -> Answer.Refused? = { null }, reply: (Answer) -> Unit) {
        executor.execute("op ${op.window}/${op.action}") { actNow(op, gate, reply) }
    }

    /** The pacing tick: saves `record.json` when the record moved. */
    fun tick() {
        executor.execute("record tick") { tickNow() }
    }

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
        covered = saved?.covered ?: 0L
        saved?.let { record.apply(Effect(it.items)) }
        var replayedTo = covered
        var replayed = 0
        fun replay(line: OpLog.Line) {
            record.apply(line.effect)                 // absolute: a 0.67 line's expect, hw and answer are not needed
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
        // opened AND replayed whole: only then is the log open for this process
        val logState = openLog(::replay)
        if (logState != null) {
            if (logState.incomplete) problems += "the log's incomplete last line was cut (it was never answered)"
            problems += logState.bad
        } else problems += "the operation log could not be opened; it is tried again on each tick"
        // L1 C2: every subscription takes a snapshot, so the epoch names the store and moves only with a new one
        val why = when {
            saved == null && loaded.problem != null -> "record.json was unreadable"
            saved == null -> "a new store"
            else -> null
        }
        epoch = if (why == null) saved!!.epoch else maxOf(clock(), (saved?.epoch ?: 0L) + 1, 1L)
        seq = replayedTo
        opened = true
        logReady = logState != null
        // fix round 1, ruling 1.7: a log that did not open or replay is never covered by a save (its `covered` would pass
        // over the lines this start did not replay, for good); the next good start replays it whole
        val err = if (logReady) store.saveNow(snapshot()) else null
        var setAside: java.nio.file.Path? = null
        if (!logReady) {
            problems += "record.json is not saved while the log is closed; changes are refused with \"retry\" until it opens"
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
        if (op.queued == null) {
            reply(Answer.Refused("live, outbound and guarded actions are not built in this version", Answer.UNSUPPORTED))
            return
        }
        val refused = gate()
        val spec = if (refused == null) actions.spec(op.window, op.action) else null
        val bad = spec?.let { Merge.check(it, op) }
        // L1 C1: the input's time, never past the PC's own clock (a device whose clock runs ahead would otherwise hold
        // its items against every later input until its clock is reached)
        val at = minOf(op.at, clock())
        // S1-2 Q3: the window's own rule, asked once the shape passed; it stands in for the merge rule (the notice
        // record: ordered by its occurrence; raise and clear are the PC's own)
        val ruled = if (spec != null && bad == null) rules.admit(record, op) else null
        val effect = when {
            spec == null || bad != null || ruled is Admit.Refuse || ruled == Admit.Keep -> Effect.NONE
            else -> Merge.judge(record, spec, op, at, always = ruled == Admit.Apply)
        }
        val answer: Answer = when {
            refused != null -> refused
            spec == null -> Answer.Refused("this PC has no action ${op.action} in ${op.window}", Answer.UNKNOWN)
            bad != null -> Answer.Refused(bad, Answer.INVALID)
            ruled is Admit.Refuse -> ruled.answer
            else -> Answer.Seen
        }
        val line = OpLog.Line(seq + 1, op, effect, answer)
        try {
            log.append(line)
        } catch (e: Exception) {
            logFailures.failed("could not be written", e)
            reply(Answer.Refused("the PC could not write this change to its log; it is kept and sent again", Answer.RETRY))
            return
        }
        logFailures.ok()
        record.apply(effect)
        seq = line.seq
        reply(answer)
        publish(Change(epoch, seq, effect))
    }

    /** The log opened from [covered] with each line past it replayed, or null: said once per run of failures (the
     *  log, a note, the `record.log` alert), and again when it opens. */
    private fun openLog(replay: (OpLog.Line) -> Unit): OpLog.Opened? = try {
        log.open(covered).also { it.lines.forEach(replay) }
    } catch (e: Exception) {
        logFailures.failed("could not be opened", e, "changes from other devices are refused until it opens (tried " +
            "again every ${TICK_MS / 1000} s); a phone keeps them and sends them again, a page says so and keeps none")
        null
    }

    private fun tickNow() {
        for (l in tickListeners) {
            try {
                l()
            } catch (e: Exception) {
                Log.e(TAG, "a tick listener failed", e)
            }
        }
        // L1 R-a: a log that did not open at the start is opened again here until it opens (no op was applied
        // meanwhile, so its replay is the one the start would have made); the save below then covers it
        if (opened && !logReady) {
            val o = openLog { record.apply(it.effect); seq = it.seq } ?: return
            logReady = true
            logFailures.ok()
            note("the operation log opened on a later tick: ${o.lines.size} line(s) replayed past $covered" +
                (if (o.incomplete) "; its incomplete last line was cut (it was never answered)" else "") +
                (if (o.bad.isEmpty()) "" else "; " + o.bad.joinToString("; ")))
        }
        if (!opened || !logReady) return                // ruling 1.7: no save covers a log this start did not replay
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
    private fun snapshot() = RecordStore.Saved(epoch, seq, record.copyItems())

    private fun publish(c: Change) {
        for (l in listeners) {
            try {
                l(c)
            } catch (e: Exception) {
                Log.e(TAG, "a record listener failed on change ${c.seq}", e)
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
        /** Dec. A.12: the log is set aside past the journal's size at a process start (64 MiB), never deleted. */
        const val LOG_SET_ASIDE_BYTES = Journal.ROTATE_BYTES
        /** Fix round 2, ruling 1.6: why the lane serves no record while the log is closed. Build S1-2 Q1 (N3): the PC's
         *  words only, for every interface — a page shows them as given and keeps no copy; the phone logs them. */
        const val LOG_CLOSED = "the PC could not open its operation log: its record is not served until it opens " +
            "(tried again every 2 s)"
        /** The record's pacing tick (the spec's P1: 2 s). */
        const val TICK_MS = 2_000L

        /** The actions of this build's converted windows, by window (S1-1 P4; S1-2 Q2: read from [Windows]). */
        val WINDOWS: ActionTable = Windows.ACTIONS

        /** The level each of those actions needs (S1-1 P6, C§10.4; S1-2 Q2: [Windows]): Reader's `place` and `open`
         *  view; `bookmark`, `set`, `reset` and `migrate` control. */
        val LEVELS: ActionLevels = Windows.LEVELS

        /** The level a window's subscription needs (its description's `min`, C§3.1): Reader view; a window this build
         *  does not know, full ([Windows.minLevel]). */
        fun minLevel(window: String): String = Windows.minLevel(window)

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
         * tool flag: opening may set the log aside and writes `record.json`. A start that throws
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
