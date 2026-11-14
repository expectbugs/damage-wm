package wm.damage.core.bastion

import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import wm.damage.core.util.Log

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on
 * his own network. This is the PC's reader of his own home hub (build W, R6 §2, P5): every
 * `haPollSec` it asks the hub whether it is up and reads each entity the rules name, through
 * [HaReader]'s one GET site, judges each rule ([RuleEval]) and raises or clears its alert in the
 * [AlertStore]. It never writes to the hub.
 *
 * **Liveness without timeouts** (`CLAUDE.md`; R6 §2): [Http][wm.damage.core.util.Http] sets none.
 * - A round is `health()` then each entity's `state()`, sequential, on one coroutine. A tick SKIPS
 *   while a round is in flight; once the oldest has been in flight [STUCK_MS] the state line says
 *   "hub request in flight Ns" and ONE more round starts beside it — two at most, nothing
 *   cancelled (the tmux capture's skip, `LocalTmuxProvider.captureLoop`).
 * - Every round carries a monotonic attempt number; a reading applies only when its attempt is
 *   newer than the last applied for that entity, so a late answer never overwrites a newer one
 *   (`RemoteContent`'s attempts).
 * - No good answer for `haSilentMin` raises [SILENT]; the next good answer clears it.
 *
 * **Built-in alerts** (normal severity, `builtIn`): [SILENT]; [TOKEN] (a 401/403: the reader
 * latches, polling stops, the state line says why — until a restart); [TOKEN_FILE], [RULES] and
 * `watcher.rule.<id>` (decided once at start by [build]); `watcher.entity.<entity>` (the hub has no
 * such entity, 404 — or the entity's reads have failed for `haSilentMin` while the hub answers; cleared
 * by its next good read); [COPY] (the nightly copy's, `desktop/…/NightlyCopy`);
 * [AlertStore.STORE_ALERT] (the store's). Their texts are plain words, never cut, and never carry
 * the token or a hub answer's body ([HaReader]'s exceptions name the path and status only).
 *
 * Repeats ([AlertStore.dueRepeats]) are asked every tick: urgent every ten minutes, a normal rule
 * every `repeatMin` (0 = said once), built-ins once. An OFF watcher runs a repeat-only tick: the
 * watcher's own trouble (a token file it cannot use, a rules file with a problem) never ends an
 * alert, and that includes its repeats (fix round 1, R1.3).
 *
 * **Guards and change rules** (package E, dec. F.1–F.3). A guard's hub entity is read like a rule's
 * (it joins the reader's path set and is read first in every round, so a round judges its rules on
 * that round's guard); `damage.phone` comes from [presence]. The guard is judged at each of its
 * rule's evaluations ([RuleEval.step]); when it ends, the rule's alert clears with words saying so.
 * A `change` rule's baseline is saved with its alert in the store and seeded from it at start (a
 * restart during an away period goes on from the saved value; a guard found false drops it). A
 * `withinMin` rule keeps one sample a minute in memory ([SAMPLE_MS]); it is blind for `withinMin`
 * after a start, after its guard ends and after a gap in its readings, and the state line says so.
 */
class BastionWatcher(
    /** Null = the watcher is off this start ([offReason] says why): no loop runs. */
    private val reader: HaReader?,
    val rules: List<AlertRule>,
    private val store: AlertStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    pollSec: Int = 10,
    silentMin: Int = 5,
    private val offReason: String? = null,
    /** `damage.phone` (dec. F.3): null on a host that does not read it — a guard on it then has no reading. */
    private val presence: PhonePresence? = null,
) {
    init {
        require(reader != null || offReason != null) { "an off watcher says why" }
    }

    private val pollMs = pollSec.coerceAtLeast(1) * 1000L
    private val silentMs = silentMin.coerceAtLeast(1) * 60_000L
    /** Guard entities first: a round reads a guard before the rules it guards. */
    private val guardEntities: Set<String> = rules.mapNotNullTo(LinkedHashSet()) { r -> r.guard?.takeUnless { it.builtIn }?.entity }
    private val entities: List<String> = (guardEntities + rules.map { it.entity }).distinct()
    private val rulesByEntity: Map<String, List<AlertRule>> = rules.groupBy { it.entity }
    /** Every rule that needs an entity read (its own or its guard's): named in that entity's alert. */
    private val rulesNeeding: Map<String, List<AlertRule>> =
        entities.associateWith { e -> rules.filter { e in it.hubEntities } }
    private val repeatMs: Map<String, Long> = rules.associate { it.id to it.repeatMs }
    private val startedAt = clock()

    private val attempts = AtomicLong(0)
    /** Rounds in flight: attempt → when it started. Written by the tick (put) and the round (remove). */
    private val inFlight = ConcurrentHashMap<Long, Long>()

    private val lock = Any()
    // ---- under [lock] ----
    private val evals = HashMap<String, EvalState>()
    /** Per entity: the newest attempt whose outcome was applied. */
    private val applied = HashMap<String, Long>()
    /** Per entity: what is wrong with its reads now (for the state line); absent = fine. */
    private val entityProblem = LinkedHashMap<String, String>()
    /** Per entity: when its current run of failed reads began (an HTTP error other than 404, an answer not
     *  understood, a connection that drops after `health()` answered); absent = its last outcome was not one. */
    private val entityFailingSince = HashMap<String, Long>()
    /** The newest attempt whose `health()` outcome was applied, and what it said. */
    private var hubAttempt = 0L
    private var hubFailingSince: Long? = null
    private var hubProblem: String? = null
    private var lastGoodAt: Long = startedAt
    private var answeredOnce = false
    /** Per guard entity: its last state read, null = no reading (not read yet, or its read failed). */
    private val guardStates = HashMap<String, String?>()
    /** Per `withinMin` rule: one sample a minute, back to the newest at or before `withinMin` ago. */
    private val rings = HashMap<String, ArrayDeque<Sample>>()
    // ----

    /** 0 = no refusal; else the status of the 401/403 that stopped the polling. */
    @Volatile private var refusedStatus = 0
    private var loop: Job? = null

    init {
        for (r in rules) {
            val saved = if (r.kind == RuleKind.CHANGE && r.withinMin == 0) store.baselineOf(r.id) else null
            evals[r.id] = EvalState.seeded(store.stateOf(r.id) != AlertState.CLEAR, saved?.first, saved?.second ?: 0L)
        }
    }

    /** How many hub rounds are in flight now (0, 1 or 2): the state line's and the tests' read. */
    val roundsInFlight: Int get() = inFlight.size

    /** Whether this start's watcher runs (false: [stateLine] says why). */
    val on: Boolean get() = reader != null

    /** Starts the tick loop (pacing every `haPollSec`). An off watcher's ticks only ask the repeats. */
    fun start() {
        if (loop != null) return
        loop = scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (e: Exception) {
                    Log.e(TAG, "a watcher tick failed; the next tick runs as usual", e)
                }
                delay(pollMs)               // pacing between ticks, not a timeout
            }
        }
    }

    /** One tick: the repeats, then (on) start a round unless one is in flight (one more beside a stuck one) and
     *  the silent check. Called by the loop; tests call it directly with an injected clock. */
    fun tick() {
        val now = clock()
        // repeats first: an urgent alert repeats until acknowledged, whether or not the hub is being read, and
        // whether or not this start's watcher is on (a no-op while nothing is RAISED)
        store.dueRepeats(now, repeatMs)
        if (reader == null) return         // off: repeat-only
        if (refusedStatus != 0) return     // polling stopped (a 401/403); the state line and TOKEN say why
        val oldest = inFlight.values.minOrNull()
        when {
            oldest == null -> launchRound()
            inFlight.size < MAX_IN_FLIGHT && now - oldest >= STUCK_MS -> {
                Log.w(TAG, "hub request in flight ${(now - oldest) / 1000}s - one more round starts beside it " +
                    "(nothing is cancelled; the stuck one ends when its connection does)")
                launchRound()
            }
            else -> {}                     // still asking: skip this tick
        }
        // decided and raised under the one lock a good answer takes to clear it: an answer landing between
        // the decision and the raise would otherwise leave the alert up until the next round
        synchronized(lock) {
            val silentFor = now - lastGoodAt
            if (silentFor >= silentMs) {
                val why = hubProblem
                    ?: inFlight.values.minOrNull()?.let { "a request has been in flight ${(now - it) / 1000}s" }
                    ?: "no answer yet"
                store.raise(SILENT, Severity.NORMAL,
                    "Bus watcher: the hub has not answered for ${silentFor / 60_000} min ($why).", "", builtIn = true)
            }
        }
    }

    private fun launchRound() {
        val attempt = attempts.incrementAndGet()
        inFlight[attempt] = clock()
        scope.launch(Dispatchers.IO) {
            try {
                round(attempt)
            } catch (e: Exception) {
                Log.e(TAG, "hub round $attempt ended early", e)
            } finally {
                inFlight.remove(attempt)
            }
        }
    }

    private sealed class Outcome {
        class Read(val reading: Reading.State) : Outcome()
        object Missing : Outcome()
        class Failed(val problem: String) : Outcome()
        /** The hub itself did not answer this round: no reading, no problem of the entity's own. */
        object NoHub : Outcome()
    }

    private fun round(attempt: Long) {
        val r = reader ?: return
        try {
            r.health()
        } catch (e: HaReader.TokenRefused) {
            tokenRefused(e.status)
            return
        } catch (e: Exception) {
            hubOutcome(attempt, problemOf(e))
            for (entity in entities) apply(attempt, entity, Outcome.NoHub)
            return
        }
        hubOutcome(attempt, null)
        for (entity in entities) {
            val outcome = try {
                val s = r.state(entity)
                Outcome.Read(Reading.State(s.state, s.lastReported, s.lastUpdated))
            } catch (e: HaReader.TokenRefused) {
                tokenRefused(e.status)
                return
            } catch (e: HaReader.NotFound) {
                Outcome.Missing
            } catch (e: Exception) {
                Outcome.Failed(problemOf(e))
            }
            apply(attempt, entity, outcome)
        }
    }

    /** The hub's answer to `health()` ([problem] null = it answered). A good answer is liveness whatever its
     *  attempt; a failure applies only when no newer attempt has applied. */
    private fun hubOutcome(attempt: Long, problem: String?) {
        val now = clock()
        synchronized(lock) {
            if (problem == null) {
                lastGoodAt = maxOf(lastGoodAt, now)
                answeredOnce = true
                if (attempt >= hubAttempt) {
                    hubAttempt = attempt
                    hubProblem = null
                    hubFailingSince = null
                }
                if (store.stateOf(SILENT) != AlertState.CLEAR) {
                    Log.i(TAG, "the hub answers again")
                    store.clear(SILENT)
                }
            } else if (attempt >= hubAttempt) {
                hubAttempt = attempt
                if (hubProblem == null) Log.w(TAG, "the hub is not answering: $problem")
                hubProblem = problem
                if (hubFailingSince == null) hubFailingSince = now
            }
        }
    }

    private fun apply(attempt: Long, entity: String, outcome: Outcome) = synchronized(lock) {
        val last = applied[entity] ?: 0L
        if (attempt <= last) {
            Log.i(TAG, "a late answer for $entity (round $attempt) dropped: round $last has applied since")
            return@synchronized
        }
        applied[entity] = attempt
        val entityAlert = ENTITY_PREFIX + entity
        val now = clock()
        when (outcome) {
            is Outcome.Read -> {
                entityProblem.remove(entity)
                entityFailingSince.remove(entity)
                if (store.stateOf(entityAlert) != AlertState.CLEAR) store.clear(entityAlert)
            }
            Outcome.Missing -> {
                if (entityProblem[entity] != MISSING) Log.e(TAG, "the hub has no entity $entity (404)")
                entityProblem[entity] = MISSING
                entityFailingSince.remove(entity)          // its own alert below says it
                val ids = rulesNeeding[entity].orEmpty().joinToString { it.id }
                store.raise(entityAlert, Severity.NORMAL,
                    "Bus watcher: the hub has no entity $entity (renamed or removed?). Rule $ids cannot be judged.",
                    "", builtIn = true)
            }
            is Outcome.Failed -> {
                if (entityProblem[entity] != outcome.problem) Log.w(TAG, "$entity not read: ${outcome.problem}")
                entityProblem[entity] = outcome.problem
                // the hub answers but this entity's reads keep failing: its rules read nothing and hold, so after
                // haSilentMin it is said as an alert (the 404 case's id and shape; review 1, finding 2)
                val since = entityFailingSince.getOrPut(entity) { now }
                if (now - since >= silentMs) {
                    val ids = rulesNeeding[entity].orEmpty().joinToString { it.id }
                    store.raise(entityAlert, Severity.NORMAL,
                        "Bus watcher: $entity has not been read for ${(now - since) / 60_000} min " +
                            "(${outcome.problem}). Rule $ids cannot be judged.", "", builtIn = true)
                }
            }
            Outcome.NoHub -> {}                            // the silent alert covers the hub; the run is kept
        }
        val reading: Reading = (outcome as? Outcome.Read)?.reading ?: Reading.None
        if (entity in guardEntities) guardStates[entity] = (outcome as? Outcome.Read)?.reading?.state
        for (rule in rulesByEntity[entity].orEmpty()) evaluate(rule, reading, now)
    }

    /** The rule's guard now (dec. F.1), and the state it read. Under [lock]. */
    private class GuardNow(val read: GuardRead, val state: String?)

    private fun guardOf(rule: AlertRule): GuardNow {
        val g = rule.guard ?: return GuardNow(GuardRead.HOLDS, null)
        val state = if (g.builtIn) builtInValue(g.entity) else guardStates[g.entity]
        return when {
            state == null || state in NO_READING -> GuardNow(GuardRead.NO_READING, state)
            g.holds(state) -> GuardNow(GuardRead.HOLDS, state)
            else -> GuardNow(GuardRead.ENDED, state)
        }
    }

    /** A built-in value's word now; null = not read on this host. */
    private fun builtInValue(name: String): String? = when (name) {
        AlertRule.PHONE -> presence?.word
        else -> null
    }

    /** One rule, one reading, under [lock]: the guard, the change rule's baseline or past sample, the decision. */
    private fun evaluate(rule: AlertRule, reading: Reading, now: Long) {
        val prior = evals.getValue(rule.id)
        val guard = guardOf(rule)
        val within = rule.kind == RuleKind.CHANGE && rule.withinMin > 0
        val arming = rule.kind == RuleKind.CHANGE && rule.withinMin == 0
        val past = if (within) pastSample(rule, now) else null
        val ackedAt = if (arming) store.ackedAtOf(rule.id) else 0L
        val (next, decision) = RuleEval.step(rule, prior, reading, now, guard.read, past, ackedAt)
        evals[rule.id] = next
        if (within) {
            if (guard.read == GuardRead.ENDED) rings.remove(rule.id) else sample(rule, reading, now)
        }
        if (arming && (next.baseline != prior.baseline || next.baselineAt != prior.baselineAt)) {
            // kept with the alert, so a restart goes on from it (dec. F.2); dropped when the guard ends
            store.setBaseline(rule.id, next.baseline, next.baselineAt)
        }
        val value = (reading as? Reading.State)?.state ?: ""
        when (decision) {
            Decision.RAISE -> {
                Log.i(TAG, "rule ${rule.id} raised (${rule.kind.wire} on ${rule.entity}, reading $value)")
                store.raise(rule.id, rule.severity, raiseText(rule, next, past, value), value)
            }
            Decision.CLEAR -> {
                val g = rule.guard
                val text = when {
                    guard.read == GuardRead.ENDED && g != null ->
                        "${rule.text} - check ended: ${g.entity} is ${guard.state} (the rule runs while ${g.words()})"
                    arming && ackedAt > 0L && prior.baselineAt < ackedAt ->
                        "${rule.text} - acknowledged; changes are now measured from ${next.baseline}"
                    else -> null
                }
                Log.i(TAG, "rule ${rule.id} cleared${text?.let { ": $it" } ?: ""}")
                store.clear(rule.id, text)
            }
            Decision.HOLD -> {}
        }
    }

    /** A change rule's words carry the baseline, the value and since when (dec. F.2): "Fresh tank 100 → 94 since
     *  08:12". Other kinds: the rule's text. */
    private fun raiseText(rule: AlertRule, next: EvalState, past: Sample?, value: String): String {
        if (rule.kind != RuleKind.CHANGE) return rule.text
        val base = past?.text ?: next.baseline
        val at = past?.at ?: next.baselineAt
        return "${rule.text} $base → $value since ${hhmm(at)}"
    }

    /** A `withinMin` rule's sample ring, under [lock]: one usable reading a minute, kept back to the newest sample
     *  at or before `withinMin` ago. */
    private fun sample(rule: AlertRule, reading: Reading, now: Long) {
        val text = (reading as? Reading.State)?.state ?: return
        val v = RuleEval.number(text) ?: return
        val ring = rings.getOrPut(rule.id) { ArrayDeque() }
        val last = ring.lastOrNull()
        if (last != null && now - last.at < SAMPLE_MS) return
        ring.addLast(Sample(v, text, now))
        val target = now - rule.withinMin * 60_000L
        while (ring.size >= 2 && ring[1].at <= target) ring.removeFirst()
    }

    /** The reading from `withinMin` ago: the newest sample at or before then, if it is no more than
     *  [SAMPLE_GAP_MS] older; null = blind (just started, the guard just turned true, or a gap in the readings). */
    private fun pastSample(rule: AlertRule, now: Long): Sample? {
        val target = now - rule.withinMin * 60_000L
        val s = rings[rule.id]?.lastOrNull { it.at <= target } ?: return null
        return if (target - s.at <= SAMPLE_GAP_MS) s else null
    }

    private fun tokenRefused(status: Int) {
        if (refusedStatus != 0) return
        refusedStatus = status
        Log.e(TAG, "the hub refused the token (HTTP $status): polling stops until Damage restarts with a good token file")
        // a refusal is an answer: the hub is up
        synchronized(lock) {
            lastGoodAt = maxOf(lastGoodAt, clock())
            hubProblem = null
            hubFailingSince = null
            if (store.stateOf(SILENT) != AlertState.CLEAR) store.clear(SILENT)
        }
        store.raise(TOKEN, Severity.NORMAL,
            "Bus watcher stopped: the hub refused its token (HTTP $status). Check the token file and restart Damage.",
            "", builtIn = true)
    }

    /** One line for the `table` answer and the PC's own surfaces. Never carries the token or a hub body. */
    fun stateLine(): String {
        if (reader == null) return "bus watcher off: $offReason" + (presence?.let { " · ${it.stateLine()}" } ?: "")
        val status = refusedStatus
        if (status != 0) return "bus watcher stopped: the hub refused its token (HTTP $status) - check the token file and restart"
        val now = clock()
        val parts = ArrayList<String>()
        parts += "${rules.size} rule(s)"
        inFlight.values.minOrNull()?.let { if (now - it >= STUCK_MS) parts += "hub request in flight ${(now - it) / 1000}s" }
        synchronized(lock) {
            val problem = hubProblem
            val since = hubFailingSince
            when {
                problem != null && since != null -> parts += "hub not answering since ${hhmm(since)} ($problem)"
                answeredOnce -> parts += "hub answered ${hhmm(lastGoodAt)}"
                else -> parts += "waiting for the hub's first answer"
            }
            for ((e, p) in entityProblem) parts += "$e: $p"
            for (r in rules) {
                evals[r.id]?.guardNoReadingSince?.let { since ->
                    val g = r.guard
                    parts += "rule ${r.id}: no reading of its guard ${g?.entity} since ${hhmm(since)}"
                }
                if (r.kind == RuleKind.CHANGE && r.withinMin > 0 && pastSample(r, now) == null) {
                    val target = now - r.withinMin * 60_000L
                    val from = rings[r.id]?.firstOrNull { it.at >= target - SAMPLE_GAP_MS }?.at ?: now
                    parts += "rule ${r.id}: blind until ${hhmm(from + r.withinMin * 60_000L)} " +
                        "(no reading from ${r.withinMin} min ago yet)"
                }
            }
        }
        presence?.let { parts += it.stateLine() }
        return parts.joinToString(" · ")
    }

    companion object {
        private const val TAG = "bastion"
        /** A round in flight this long is said, and one more starts beside it. */
        const val STUCK_MS = 60_000L
        const val MAX_IN_FLIGHT = 2

        const val SILENT = "watcher.silent"
        const val TOKEN = "watcher.token"
        const val TOKEN_FILE = "watcher.tokenfile"
        const val RULES = "watcher.rules"
        const val RULE_PREFIX = "watcher.rule."
        const val ENTITY_PREFIX = "watcher.entity."
        const val COPY = "watcher.copy"

        private const val MISSING = "not on the hub (404)"
        /** A guard's state that is no reading (dec. F.1; `damage.phone`'s `unknown` included). */
        private val NO_READING = setOf("unavailable", PhonePresence.UNKNOWN)
        /** A `withinMin` rule keeps one sample a minute (dec. F.2). */
        const val SAMPLE_MS = 60_000L
        /** The sample from `withinMin` ago may be this much older than that; older = a gap in the readings, blind. */
        const val SAMPLE_GAP_MS = 3 * 60_000L
        private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

        private fun hhmm(ms: Long): String = HHMM.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

        /** A failure in plain words: the exception's class and message. [HaReader]'s own name the path and
         *  status only; the JDK's name the address or the socket's state. */
        private fun problemOf(e: Exception): String = "${e::class.simpleName}: ${e.message ?: "no detail"}"

        /** The watcher's own alerts: the store's ([AlertStore.STORE_ALERT]) and the copy's ([COPY]) are not. */
        fun isOwnBuiltIn(id: String): Boolean =
            id.startsWith(AlertRule.BUILT_IN_PREFIX) && id != AlertStore.STORE_ALERT && id != COPY

        /**
         * At start: the watcher's own built-in alerts as this start's reads decide them. Each in [raise] is raised
         * (cleared first when it is up with another text, so the new reason is said); every other one of the
         * watcher's that is up is cleared unless [keep] says the running watcher will settle it (the silent
         * alert, an entity alert whose entity a rule still names).
         */
        fun settleAtStart(store: AlertStore, raise: Map<String, String>, keep: (String) -> Boolean) {
            for (r in store.table()) {
                if (!isOwnBuiltIn(r.id)) continue
                val text = raise[r.id]
                if ((text == null && !keep(r.id)) || (text != null && text != r.text)) store.clear(r.id)
            }
            for ((id, text) in raise) store.raise(id, Severity.NORMAL, text, "", builtIn = true)
        }

        /**
         * The watcher for this start (dec. A.1, A.6; the spec's §2(f)), from the config's `haUrl`, `haTokenFile`
         * and the rules file, each read ONCE (a change needs a restart: rule hot-reload is not built). Says at start
         * whether it runs and why not, and how many rules were kept and refused; raises [TOKEN_FILE], [RULES] and
         * `watcher.rule.<id>` as the reads decide, and retires the alerts of rules that are gone.
         *
         * - `haUrl` empty: off, said; no rules are read and every alert of the watcher's is cleared.
         * - `haUrl` set, the token file not set, missing, empty, unreadable or not one token: off, said with the
         *   PATH and the case (the class name only for an unreadable file), [TOKEN_FILE] raised. The rules are
         *   still read, so their refusals are said; the alerts of rules that still exist are left as they are.
         * - Otherwise on. The token's text goes from the file to [HaReader]'s header only: never logged, never
         *   in an alert, a state line or an exception text.
         */
        fun build(
            haUrl: String, tokenFile: String, rulesPath: Path, pollSec: Int, silentMin: Int,
            store: AlertStore, scope: CoroutineScope, clock: () -> Long = { System.currentTimeMillis() },
            presence: PhonePresence? = null,
        ): BastionWatcher {
            if (haUrl.isEmpty()) {
                store.retire(emptySet())
                settleAtStart(store, emptyMap()) { false }
                Log.i(TAG, "bus watcher off: no haUrl in the config")
                return BastionWatcher(null, emptyList(), store, scope, clock, offReason = "no haUrl in the config",
                    presence = presence)
            }
            if (pollSec < 1) Log.e(TAG, "haPollSec $pollSec is below 1 - polling every second")
            if (silentMin < 1) Log.e(TAG, "haSilentMin $silentMin is below 1 - one minute is used")
            val raise = LinkedHashMap<String, String>()
            val load = RulesFile.load(rulesPath)
            val problem = load.fileProblem
            when {
                load.missing -> Log.w(TAG, "no rules file at $rulesPath - the watcher runs no rules")
                problem != null -> {
                    Log.e(TAG, "$problem ($rulesPath) - no rules run until it is fixed and Damage restarts")
                    raise[RULES] = "Bus watcher: $problem ($rulesPath). No rules run until it is fixed and Damage restarts."
                }
            }
            for ((id, refusals) in load.refused.groupBy { it.id }) {
                val why = refusals.joinToString("; ") { it.why }
                Log.e(TAG, "rule $id refused: $why")
                raise[RULE_PREFIX + id] = "Bus watcher: rule $id in $rulesPath is refused ($why). The other rules run."
            }
            Log.i(TAG, "rules from $rulesPath: ${load.rules.size} kept, ${load.refused.size} refused")
            // the watcher's own trouble must not end an alert (fix round 1, R1.3): a rules file with a problem retires
            // nothing, and a refused rule's id stays live (its alert held as it is, `watcher.rule.<id>` raised
            // above). "Gone" means absent from a readable file: what removing a rule means
            if (problem == null) store.retire(load.rules.mapTo(HashSet()) { it.id } + load.refused.map { it.id })
            else Log.i(TAG, "the rules file has a problem: every rule alert up is kept as it is")

            val read = if (tokenFile.isEmpty()) null else HaReader.readTokenFile(Path.of(tokenFile))
            var off: String? = when (read) {
                null -> "no haTokenFile is set in the config"
                is HaReader.TokenRead.Ok -> null
                HaReader.TokenRead.Missing -> "the token file $tokenFile does not exist"
                HaReader.TokenRead.Empty -> "the token file $tokenFile is empty"
                is HaReader.TokenRead.Unreadable -> "the token file $tokenFile could not be read (${read.cls})"
                HaReader.TokenRead.Malformed -> "the token file $tokenFile does not hold one token " +
                    "(a space, a line break or a character outside plain ASCII inside it)"
            }
            val reader: HaReader? = if (read !is HaReader.TokenRead.Ok) null else try {
                // the rules' entities and their guards' (dec. F.1): the reader's closed path set
                HaReader(haUrl, read.token, load.rules.flatMap { it.hubEntities }.distinct())
            } catch (e: IllegalArgumentException) {
                // HaReader's refusals carry no token text (its constructor's messages are fixed words)
                off = "the hub reader refused its setup (${e.message})"
                null
            }
            if (reader == null) {
                val why = off ?: "the hub reader was not set up"
                Log.e(TAG, "bus watcher off: $why")
                raise[TOKEN_FILE] = "Bus watcher off: $why. Fix it and restart Damage."
                settleAtStart(store, raise) { false }
                return BastionWatcher(null, load.rules, store, scope, clock, pollSec, offReason = why, presence = presence)
            }
            val watched = load.rules.flatMap { it.hubEntities }.mapTo(HashSet()) { ENTITY_PREFIX + it }
            settleAtStart(store, raise) { id -> id == SILENT || id in watched }
            Log.i(TAG, "bus watcher on: every ${pollSec.coerceAtLeast(1)} s, ${load.rules.size} rule(s), " +
                "silent after ${silentMin.coerceAtLeast(1)} min")
            return BastionWatcher(reader, load.rules, store, scope, clock, pollSec, silentMin, presence = presence)
        }
    }
}
