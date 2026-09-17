package wm.damage.core.transport

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import wm.damage.core.geom.FidAllocator
import wm.damage.core.geom.FidTracker
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.LintError
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.util.Log
import wm.damage.core.wire.AaFrame
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.EvenHubMsg
import wm.damage.core.wire.LaunchMsg
import wm.damage.core.wire.LoggerMsg
import wm.damage.core.wire.Pb
import wm.damage.core.wire.RingMsg
import wm.damage.core.wire.SettingsMsg

/**
 * The CFW transport choreography, sans-IO: capability gate, carrier CREATE,
 * FB lease on both arms with 45 s renewal, the sacrificial warmup, idle
 * keepalive, msgId/MapSessionId/fid discipline, <=3800 B fragmenting, the
 * pipelined message window, and ack routing. Subclasses supply only the wire
 * ([writeArm], [connectLink]/[disconnectLink]) and feed notifications to
 * [onNotifyPacket] — SimTransport and the phone's BleTransport run the SAME
 * protocol brain the selfcheck exercises.
 *
 * Concurrency model (rebuilt after review round 1 found the original racing
 * every counter and ack-gating each fragment — the forbidden pattern):
 *
 *  - TWO worker lanes. The IMAGE lane owns fids and MapSessionId and writes
 *    flushes strictly in submission order (fid order == wire order by
 *    construction). The CONTROL lane carries lease renewal, keepalive and the
 *    carrier refresh — it never touches the ack window, so a stalled ACK
 *    stream can freeze pixels but cannot cost the lease. (A stalled WRITE is
 *    different: [wire] is held per message across writeArm, so a GATT write
 *    that never returns blocks renewals too — the 90 s fail-open is the
 *    backstop there; nothing in software can reach a link that will not
 *    take a byte.)
 *  - [wire] is a mutex held per MESSAGE write (all AA packets of one EvenHub
 *    message): the two lanes may interleave between messages — legal, each
 *    message reassembles independently — but never inside one. msgId and the
 *    AA seq are allocated under the same mutex, so counter order matches wire
 *    order.
 *  - Each image fragment message takes a window slot ([WINDOW]=3) and frees it
 *    when its ack arrives (any thread; the pending map is concurrent) — real
 *    pipelining per Faceclaw's exercised CFW path. Flush completion is the
 *    FINAL fragment's ack, handled asynchronously so the image lane moves
 *    straight on to the next flush.
 *
 * Reference arm split (overview.md §2, graded strong-not-proven — verify with
 * a two-arm capture at first light): bulk pixels -> LEFT, control -> RIGHT;
 * events and acks arrive on RIGHT.
 *
 * The mirror (HANDOFF.md §8.2): [mirrorSim] is a firmware model fed the exact
 * packets this transport writes — AFTER each write succeeds, so a failed write
 * leaves it untouched — and is what every replica draws. A subclass that IS a
 * firmware model (the sim transport) passes its own sim in and nothing is
 * teed; a hardware transport passes null and gets a private mirror. The
 * mirror's own notifications are discarded; a decode/fid/session event it
 * reports is the model predicting that the firmware would reject or skip our
 * bytes in silence, so it is surfaced as a `mirror/<kind>` fault.
 */
abstract class CfwTransportBase(
    protected val scope: CoroutineScope,
    private val name: String,
    mirrorSim: GlassFirmwareSim? = null,
) : Transport {

    /** The firmware model behind [mirror]. */
    protected val mirrorSim: GlassFirmwareSim = mirrorSim ?: GlassFirmwareSim()
    private val teeMirror = mirrorSim == null
    override val mirror: LensPanels get() = this.mirrorSim

    init {
        if (teeMirror) {
            this.mirrorSim.attachListener(object : GlassFirmwareSim.SimDiag {
                override fun event(kind: String, detail: String) {
                    when (kind) {
                        "decode", "fid", "session", "msgid", "compressmode", "abort", "image" ->
                            emitFault("mirror/$kind", detail)      // the model predicting a silent rejection
                        "lease", "warmup", "launch", "transport", "proto" ->
                            Log.i(name, "mirror/$kind: $detail")   // first-light narration, visible
                        else -> Log.d(name, "mirror/$kind: $detail")
                    }
                }
                override fun notify(arm: Arm, packet: ByteArray) {}   // the model's acks are not the glasses'
                override fun panelChanged(arm: Arm) {}
            })
        }
    }

    /** Write one AA packet and, once it went out, apply it to the mirror. Every
     *  packet this transport sends passes through here. */
    private suspend fun writePacket(arm: Arm, packet: ByteArray) {
        writeArm(arm, packet)
        if (teeMirror) mirrorSim.write(arm, packet, nowMs())
    }

    override fun injectInput(type: Int) = emitInput(type, EvenHubMsg.SRC_RING)

    override fun injectText(line: String) {
        if (!_events.tryEmit(TransportEvent.Text(line))) {
            Log.e(name, "typed-text event buffer overflow — a line was DROPPED")
        }
    }

    /** Phase 0 probe state (§49): the log stream is wanted until a `logger=off`
     *  probe; the firmware ends it at every session CREATE, so each start
     *  re-sends the switch while this holds. Not persisted: a process restart
     *  leaves it off. */
    @Volatile private var loggerWanted = false

    override fun devProbe(name: String, value: String) {
        Log.i(this.name, "probe: $name=$value")
        emitNote("probe", "$name=$value")
        val started = _state.value.started
        when (name) {
            "diag" -> {
                if (value == "clear") {
                    // mode 7 sub 0: the sticky diagnostics, the fid ring and — on contract 2 — the
                    // image-lane refusal record (fields 23-25). The transport resyncs its own fid
                    // tracker with it, so this is the one path that may send it.
                    if (!started) { Log.w(this.name, "probe diag=clear not sent: no session"); return }
                    scope.launch { clearDiagFlags() }
                    return
                }
                val sub = when (value) {
                    "show" -> 2
                    "hide" -> 1
                    else -> { Log.w(this.name, "probe diag=$value: expected show | hide | clear"); return }
                }
                if (!started) { Log.w(this.name, "probe diag=$value not sent: no session"); return }
                // CfwModes.diag: [7][sub] rides the image lane like every mode
                // message; it burns no fid and draws only into the physical
                // framebuffer, never the shadow the mirror models
                imageQueue.trySend(ImgWork.Raw(sessionEpoch.get(), CfwModes.diag(sub), null))
            }
            "logger" -> {
                val on = when (value) {
                    "on" -> true
                    "off" -> false
                    else -> { Log.w(this.name, "probe logger=$value: expected on | off"); return }
                }
                loggerWanted = on
                if (!started) { Log.w(this.name, "probe logger=$value: no session yet (the next start sends it)"); return }
                controlQueue.trySend(loggerSwitch(sessionEpoch.get(), on))
            }
            // FIRMWARE.md §3 (a Damage build only; an upstream build ignores field 112).
            // A flag set asked for here is WANTED: the keeper re-arms it after every session
            // start under the hold-back rule (armFeatures); `flags=clear` drops the wish.
            "telemetry", "flags", "cache", "cachesize" -> {
                val (op, arg) = when {
                    name == "telemetry" && value == "read" -> DamageMsg.OP_TELEMETRY to nextTelemetryId()
                    name == "cache" && value == "info" -> DamageMsg.OP_CACHE_INFO to nextTelemetryId()
                    name == "cachesize" && value.toIntOrNull() in DamageMsg.CACHE_MIN_KIB..DamageMsg.CACHE_BUDGET_KIB -> DamageMsg.OP_CACHE_SIZE to value.toInt()
                    name == "flags" && value == "clear" -> DamageMsg.OP_FLAGS_CLEAR to 0
                    name == "flags" && value == "probe" -> DamageMsg.OP_FLAGS_SET to DamageMsg.FLAG_PROBE
                    name == "flags" && value.startsWith("0x") && value.drop(2).toIntOrNull(16) in 0..0xFFFF ->
                        DamageMsg.OP_FLAGS_SET to value.drop(2).toInt(16)
                    else -> { Log.w(this.name, "probe $name=$value: expected telemetry=read, cache=info, cachesize=${DamageMsg.CACHE_MIN_KIB}..${DamageMsg.CACHE_BUDGET_KIB} or flags=clear|probe|0xNNNN"); return }
                }
                if (op == DamageMsg.OP_FLAGS_SET) wantedFlags = arg
                if (op == DamageMsg.OP_FLAGS_CLEAR) wantedFlags = 0
                // asking for DRAW2 by hand is the "try again" a hold-back names (FIRMWARE.md §1.6)
                if (op == DamageMsg.OP_FLAGS_SET && arg and DamageMsg.FLAG_DRAW2 != 0) draw2HeldBack = false
                if (!started) { Log.w(this.name, "probe $name=$value not sent: no session" + (if (op == DamageMsg.OP_FLAGS_SET) " (the next start arms it)" else "")); return }
                if (damageCaps == null) emitNote("probe", "$name=$value sent to a build without DamageCaps — expect no answer")
                if (op == DamageMsg.OP_FLAGS_SET) lastArmedAtMs = nowMs()
                // DRAW2 is the session's, not the wish's: while it is in force the shell draws with modes
                // 17–24 and its atlas is a v2 layout, so a hand-set flag set keeps bit 2 on the wire (a clear
                // included) and the session keeps drawing; the next start decides DRAW2 again
                var wireOp = op
                var wireArg = arg
                if (_state.value.flagsInForce and DamageMsg.FLAG_DRAW2 != 0 && (op == DamageMsg.OP_FLAGS_SET || op == DamageMsg.OP_FLAGS_CLEAR) &&
                    arg and DamageMsg.FLAG_DRAW2 == 0) {
                    wireOp = DamageMsg.OP_FLAGS_SET
                    wireArg = arg or DamageMsg.FLAG_DRAW2
                    emitNote("probe", "$name=$value: DRAW2 stays armed for this session (the shell draws with it) — sent flags 0x${wireArg.toString(16)}")
                }
                // op 5 and FLAGS_SET both answer on request id 0, and a probe's reply is
                // indistinguishable from the start's on the wire: a `cachesize=64` probe issued
                // while `requestCacheSize` awaits 160 could answer it with status 0, which is
                // §63.3 item 3's failure (a 160 KiB atlas over a 64 KiB cache) by another route.
                // The probes are `glassdrive.py`'s and run against a live session (2026-09-16
                // review), so one is refused while a start's id-0 waiter is registered.
                if ((wireOp == DamageMsg.OP_FLAGS_SET || wireOp == DamageMsg.OP_FLAGS_CLEAR ||
                     wireOp == DamageMsg.OP_CACHE_SIZE) && telemetryWaiters.containsKey(0)) {
                    emitNote("probe", "$name=$value not sent: the session start is waiting on an id-0 reply " +
                        "(op 5 and FLAGS_SET share it) and this one would answer it — try again once the start is done")
                    return
                }
                controlQueue.trySend(CtlWork.BothArms(sessionEpoch.get(), SettingsMsg.SID, "damage op $wireOp") { DamageMsg.control(wireOp, wireArg) })
            }
            // FIRMWARE.md §3, the self-test (mode 16) on the image lane: begin, a step carrying
            // one drawing message (hex), end. The glasses answer through telemetry fields
            // 20–22 (`telemetry=read`), RIGHT only; tools/glassdrive.py's `selftest:` step
            // drives a vector file through here and compares.
            "selftest" -> {
                fun unhex(h: String) = ByteArray(h.length / 2) { i -> h.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
                val image = when {
                    value == "begin" -> CfwModes.selfTestBegin()
                    value == "end" -> CfwModes.selfTestEnd()
                    // the step is built RAW, not through CfwModes.selfTestStep: a vector's refusal
                    // steps carry messages the encoder's own lint refuses (an unknown mode, a short
                    // header), and the firmware is what must refuse them — the runner counts every
                    // step it sent, so one the phone dropped shifted the whole vector by one
                    // (2026-09-15, second review)
                    value.startsWith("step:") -> try {
                        val m = unhex(value.removePrefix("step:"))
                        if (m.isEmpty()) { Log.w(this.name, "probe selftest step: empty message"); return }
                        byteArrayOf(CfwModes.SELF_TEST_MODE.toByte(), 1) + m
                    } catch (e: Exception) { Log.w(this.name, "probe selftest step: ${e.message}"); return }
                    // a vector's cache write goes to the live cache the steps read (the runners do the same)
                    value.startsWith("live:") -> try { unhex(value.removePrefix("live:")) } catch (e: Exception) { Log.w(this.name, "probe selftest live: ${e.message}"); return }
                    else -> { Log.w(this.name, "probe selftest=$value: expected begin | end | step:HEX | live:HEX"); return }
                }
                if (!started) { Log.w(this.name, "probe selftest=$value not sent: no session"); return }
                if (damageCaps?.has(DamageMsg.FEATURE_SELF_TEST) != true) emitNote("probe", "selftest=${value.take(12)} sent to a build without the self-test feature — the image will be treated as a BMP and refused")
                if (value.startsWith("live:")) {
                    // the vector's bytes land in the LIVE cache the shell's atlas sits in: from here that
                    // cache is not the shell's (each write its own writer, so the shell sees every one)
                    val w = "selftest#${liveCacheWrites.incrementAndGet()}@${Integer.toHexString(System.identityHashCode(this))}"
                    updateState { it.copy(cacheWriter = w) }
                    emitNote("probe", "selftest live cache write ($w): the shell's atlas is overwritten — cached text is pixels until the next session")
                }
                imageQueue.trySend(ImgWork.Raw(sessionEpoch.get(), image, null))
            }
            else -> Log.w(this.name, "probe $name=$value: not a probe this transport runs (diag, logger, telemetry, cache, flags, selftest)")
        }
    }

    private fun nextTelemetryId(): Int {
        var id: Int
        do { id = telemetryIds.incrementAndGet() and 0xFFFF } while (id == 0)   // 0 is the FLAGS ops' echo
        return id
    }

    /** One TELEMETRY (or CACHE_INFO) round trip to RIGHT in session [epoch]: the request is
     *  repeated on the pacing tick until the answer or the session's end (the sweep fails the
     *  waiter) — pacing, never a timeout. Null when the session ended first. */
    private suspend fun telemetryRead(epoch: Long, op: Int = DamageMsg.OP_TELEMETRY, label: String = "telemetry"): DamageMsg.Telemetry? {
        val id = nextTelemetryId()
        val waiter = CompletableDeferred<DamageMsg.Telemetry>()
        // the first attempt's write failing ends the wait, as it does for the prelude and the
        // capability query (2026-09-15, the third review: without it a characteristic that throws
        // on a link that is still up re-asked for ever, and the start — which the watchdog cannot
        // see until it completes — never returned). Pacing, never a clock.
        val firstFailed = CompletableDeferred<String>()
        telemetryWaiters[id] = waiter
        val reask = scope.launch {
            var first = true
            while (true) {
                if (epoch != sessionEpoch.get()) { waiter.completeExceptionally(LintError("session ended")); break }
                controlQueue.trySend(CtlWork.BothArms(epoch, SettingsMsg.SID, "damage $label $id",
                    failed = if (first) firstFailed else null) { DamageMsg.control(op, id) })
                first = false
                delay(CAPABILITY_REASK_MS)
                if (waiter.isCompleted) break
                Log.i(name, "$label $id unanswered after ${CAPABILITY_REASK_MS} ms — asking again")
            }
        }
        return try {
            kotlinx.coroutines.selects.select<DamageMsg.Telemetry?> {
                waiter.onAwait { it }
                firstFailed.onAwait { reason -> Log.w(name, "$label $id not written: $reason"); null }
            }
        } catch (e: Exception) { null } finally { reask.cancel(); telemetryWaiters.remove(id, waiter) }
    }


    /** The lane completes exactly one of [writtenBoth] / [firstFailed] for an attempt, and a reply
     *  can only follow an attempt's writes — so waiting here is what ORDERS the answer against the
     *  write. Reading `writtenBoth.isCompleted` straight after the reply raced the lane and read
     *  "no attempt reached both arms" while the lane was two statements from saying it did
     *  (2026-09-15, the third review: it faulted every arming on the instant transports). */
    private suspend fun settleWrite(writtenBoth: CompletableDeferred<Unit>, firstFailed: CompletableDeferred<String>) {
        if (writtenBoth.isCompleted || firstFailed.isCompleted) return
        try {
            kotlinx.coroutines.selects.select<Unit> {
                writtenBoth.onAwait { }
                firstFailed.onAwait { }
            }
        } catch (e: Exception) { /* either way the two checks below decide */ }
    }

    /** A FLAGS_SET of [set] to both arms, answered by RIGHT; the answer's register says whether
     *  the build took it. Null when the session ended first. Every FLAGS_SET reply carries
     *  request id 0, so the waiter is removed only while it is still ours: a previous
     *  session's wait, failed by the sweep and ending late, must not drop the new session's. */
    private suspend fun flagsSet(epoch: Long, set: Int): DamageMsg.Telemetry? {
        // the reply echoes request id 0 (FIRMWARE.md §3): await the next record on id 0 that
        // answers THIS set (flagsReplyAnswers)
        val waiter = CompletableDeferred<DamageMsg.Telemetry>()
        // `written` LATCHES ON SUCCESS: one attempt that reached every arm is what "both lenses
        // took it" means. Reading `failed` instead latched on the first attempt that threw, so a
        // transient failure condemned a set a later attempt armed and left the glasses with DRAW2
        // on while the session drew v1 shapes (2026-09-15, the third review).
        val writtenBoth = CompletableDeferred<Unit>()
        val firstFailed = CompletableDeferred<String>()
        flagsSetPending = set
        telemetryWaiters[0] = waiter
        lastArmedAtMs = nowMs()
        val reask = scope.launch {
            var first = true
            while (true) {
                if (epoch != sessionEpoch.get()) { waiter.completeExceptionally(LintError("session ended")); break }
                controlQueue.trySend(CtlWork.BothArms(epoch, SettingsMsg.SID, "damage flags 0x${set.toString(16)}",
                    failed = if (first) firstFailed else null, written = writtenBoth) { DamageMsg.control(DamageMsg.OP_FLAGS_SET, set) })
                first = false
                delay(CAPABILITY_REASK_MS)
                if (waiter.isCompleted) break
                Log.i(name, "flags 0x${set.toString(16)} unanswered after ${CAPABILITY_REASK_MS} ms — asking again")
            }
        }
        // the record, or the first attempt's write failing to leave at all — the shape the prelude
        // and the capability query use. `flagsSetPending` is deliberately NOT cleared afterwards: a
        // late coroutine from a previous session clearing it would leave the new session's own reply
        // unrecognised, and the predicate already requires the record's flags to BE the set armed
        val reply = try {
            kotlinx.coroutines.selects.select<DamageMsg.Telemetry?> {
                waiter.onAwait { it }
                firstFailed.onAwait { null }
            }
        } catch (e: Exception) { null } finally { reask.cancel(); telemetryWaiters.remove(0, waiter) }
        // only RIGHT answers, so RIGHT's reply says nothing about the other lens: unless some
        // attempt reached BOTH arms, the left lens has not armed the set and cannot report it
        // (2026-09-15, second review)
        if (reply != null) settleWrite(writtenBoth, firstFailed)
        if (reply != null && writtenBoth.isCompleted) return reply
        // a session that ended is not a fault: the sweep fails the waiter AND the queued write's own
        // deferred, so both look like a refusal from here
        if (epoch != sessionEpoch.get() || !running) return null
        if (reply == null && !firstFailed.isCompleted) return null          // the session ended: the sweep says so
        val why = if (firstFailed.isCompleted) firstFailed.getCompleted() else "no attempt reached both arms"
        emitFault("flags", "flags 0x${set.toString(16)} did not reach an arm ($why) — the lenses would differ, so the set is not taken as armed")
        return null
    }

    /** The set a FLAGS_SET is arming; its reply is the id-0 record whose flags are that set, or one
     *  carrying a status a FLAGS_SET itself records. Anything else on id 0 is a stale duplicate. */
    @Volatile private var flagsSetPending: Int? = null
    private fun flagsReplyAnswers(t: DamageMsg.Telemetry): Boolean =
        flagsReplyAnswers(t.lastStatus ?: 0L, t.flags, flagsSetPending)

    /** The glasses' uptime as a telemetry record reports it, against what the last reading plus
     *  the phone time since predicts: a shortfall past [RESET_SLACK_MS] is a reset, noted once
     *  and remembered (phone clock) for the carry decision and the hold-back rule. Comparing the
     *  two readings alone (2026-09-14, corrected the same day) misses a reset once the glasses
     *  have been up longer than they were at the previous read — the case right after a flash.
     *  (The tick wraps at 2^32, 49.7 days: a wrap reads as a reset; the phone time is the wall
     *  clock: a forward jump past the slack between two reads reads the same way.) */
    private fun noteUptime(uptime: Long) {
        val now = nowMs()
        val prev = lastUptime
        if (prev != null) {
            val predicted = prev.first + (now - prev.second)
            if (uptime < prev.first || uptime + RESET_SLACK_MS < predicted) {
                lastResetAtMs = now - uptime                       // the reset's time on the phone's clock
                emitNote("keeper", "the glasses reset: uptime ${uptime / 1000} s, was ${prev.first / 1000} s ${(now - prev.second) / 1000} s ago")
            }
        }
        lastUptime = uptime to now
    }

    /** `FORK.md` §3.2 / `FIRMWARE.md` §1.6 — after a session start on a Damage build: read
     *  RIGHT's uptime; if the glasses reset since the last read and the reset followed the
     *  last arming within [HOLD_BACK_MS], arm nothing, drop the wish and say so (a fault the
     *  wearer sees, a `keeper` note); otherwise arm the wanted bits one at a time, lowest
     *  first, each a FLAGS_SET of the set so far, and journal each answer. A bit this build
     *  refuses as unsupported (status 2) is dropped from the wish with one fault and the bits
     *  above it are still armed; any other refusal stops here and keeps the wish for the next
     *  start. LEFT takes the same writes blind: it cannot answer (`CLAIMS.md`, the senders'
     *  lens rule).
     *
     *  A reset is seen by comparing the uptime read now with what the last reading predicts:
     *  that reading plus the phone time elapsed since it. Comparing the two readings alone
     *  (2026-09-14, corrected the same day) misses a reset once the glasses have been up
     *  longer than they were at the previous read — the case right after a flash, where every
     *  uptime is short and the rule matters most. [RESET_SLACK_MS] covers the two clocks'
     *  drift and the reply's latency; a reset hides inside it only if the glasses reset within
     *  that long of booting. (The tick wraps at 2^32 ms, 49.7 days: a wrap reads as a reset —
     *  a note, and a hold-back only if an arming preceded it within [HOLD_BACK_MS]. The phone
     *  time is [nowMs], the wall clock: a forward jump of more than the slack between two
     *  reads reads the same way.) */
    private suspend fun armFeatures(epoch: Long, extra: Int = 0) {
        var extraLeft = extra
        val wanted = wantedFlags or extraLeft
        if (wanted == 0) return
        val armedAt = lastArmedAtMs
        // the reply handler notes the uptime (and a reset) itself; the start's own read already
        // ran on this session, so a reset since the last arming is on record either way
        telemetryRead(epoch) ?: return
        val resetAt = lastResetAtMs
        if (resetAt != null && resetAt != heldBackForResetAt && armedAt != null &&
            resetAt >= armedAt - CAPABILITY_REASK_MS && resetAt - armedAt < HOLD_BACK_MS) {
            wantedFlags = 0
            // the session's own DRAW2 is held back too: the start adds it to every session, and without
            // this latch the next start would arm it again whatever reset it may have caused (2026-09-15
            // review) — asking for it by hand (`probe flags=0x…` with bit 2) lifts the latch
            if (extraLeft and DamageMsg.FLAG_DRAW2 != 0) draw2HeldBack = true
            heldBackForResetAt = resetAt                       // decided on: the next reset is its own; the carry keeps its evidence
            val text = "hold-back: the glasses reset ${(resetAt - armedAt) / 1000} s after flags 0x${wanted.toString(16)} were armed — not re-armed (probe flags=… to try again)"
            emitNote("keeper", text)
            emitFault("holdback", text)
            return
        }
        var want = wanted
        var set = 0
        for (bit in 0 until 16) {
            val b = 1 shl bit
            if (want and b == 0) continue
            if (epoch != sessionEpoch.get() || (wantedFlags or extraLeft) != want) return   // the session ended, or the wish changed under us
            val r = flagsSet(epoch, set or b) ?: return
            val status = r.lastStatus ?: -1L
            val inForce = r.flags ?: 0L
            if (status == DamageMsg.STATUS_UNSUPPORTED.toLong()) {
                // not a fault of the glasses but a wish this build cannot grant: dropped, so the
                // next start does not ask again and the bits above it are not held up by it
                want = want and b.inv()
                wantedFlags = wantedFlags and b.inv()
                extraLeft = extraLeft and b.inv()
                emitFault("flags", "bit $bit is not implemented by this build (status 2) — dropped from the wanted set 0x${wanted.toString(16)}; the rest are still armed")
                // RIGHT answers every control request twice (§59), and a refusal's copy carries the same
                // unchanged flags as a refusal of the next set would: a read with its own id drains it
                // before the next set's waiter can take it for its own answer (2026-09-15 review)
                telemetryRead(epoch) ?: return
                continue
            }
            if (status != 0L || inForce != (set or b).toLong()) {
                emitFault("flags", "bit $bit (set 0x${(set or b).toString(16)}) refused: status $status, flags in force 0x${inForce.toString(16)} — the wish is kept for the next start")
                return
            }
            set = set or b
            updateState { it.copy(flagsInForce = set) }
            emitNote("keeper", "armed bit $bit — flags in force 0x${set.toString(16)} (RIGHT answered; LEFT took the same write)")
        }
    }

    /** A Damage build answered the capability read with [caps], and the session is now up: a
     *  transport with a radio asks for what the build offers (the phone requests LE 2M on a build
     *  carrying the link edits, `FIRMWARE.md` §4). Called off the start's own path, since such a
     *  request waits on the radio's own queue. Default: nothing to ask. */
    protected open fun onDamageBuild(caps: DamageMsg.Caps) {}

    /** `FIRMWARE.md` §4, op 5: ask a contract-2 build for a [kib] KiB cache before the first
     *  write, and take the answer from **op 5's own reply** — its arg is a size, not a request id,
     *  so like FLAGS_SET it answers on id 0 carrying the status it just recorded (0 taken, 3 no
     *  lease, 4 already allocated, 5 outside 64..160 KiB) and field 18 once the cache is up.
     *
     *  It used to send op 5 once and then read a separate TELEMETRY, whose status register is
     *  STICKY — TELEMETRY records none — so a 0 there was as likely to be a previous op's as op
     *  5's, and an op 5 the settings task ate (the §12 class this file re-asks everything else
     *  for) read as success: the session then laid a 160 KiB atlas over a 64 KiB cache and every
     *  record above the line was refused in silence (2026-09-15, the third review). Re-asked on
     *  the pacing tick like every other start message. Returns the size in bytes this session's
     *  cache has or will have; throws when the request could not be placed on both arms. */
    private suspend fun requestCacheSize(epoch: Long, kib: Int): Int {
        val waiter = CompletableDeferred<DamageMsg.Telemetry>()
        val writtenBoth = CompletableDeferred<Unit>()
        val firstFailed = CompletableDeferred<String>()
        cacheSizePending = kib
        telemetryWaiters[0] = waiter
        val reask = scope.launch {
            var first = true
            while (true) {
                if (epoch != sessionEpoch.get()) { waiter.completeExceptionally(LintError("session ended")); break }
                controlQueue.trySend(CtlWork.BothArms(epoch, SettingsMsg.SID, "damage cache size $kib",
                    failed = if (first) firstFailed else null, written = writtenBoth) { DamageMsg.control(DamageMsg.OP_CACHE_SIZE, kib) })
                first = false
                delay(CAPABILITY_REASK_MS)
                if (waiter.isCompleted) break
                Log.i(name, "cache size $kib KiB unanswered after ${CAPABILITY_REASK_MS} ms — asking again")
            }
        }
        val t = try {
            kotlinx.coroutines.selects.select<DamageMsg.Telemetry?> {
                waiter.onAwait { it }
                firstFailed.onAwait { null }
            }
        } catch (e: Exception) { null } finally {
            reask.cancel(); telemetryWaiters.remove(0, waiter)
            if (cacheSizePending == kib) cacheSizePending = null      // only ever clear our own
        }
        if (t == null) throw LintError("the cache size was not answered (" +
            (if (firstFailed.isCompleted) firstFailed.getCompleted() else "the session ended") +
            ") — the start fails and the keeper builds the session again")
        // both lenses lay their cache out at this size; an arm that did not take the request would
        // allocate 64 KiB and refuse every record above it, silently (2026-09-15, second review)
        settleWrite(writtenBoth, firstFailed)
        if (!writtenBoth.isCompleted) throw LintError("the cache size did not reach both arms — the start fails and the keeper builds the session again")
        val allocated = t.cacheSize?.toInt()
        val status = t.lastStatus
        val size = when {
            allocated != null -> allocated                                  // the cache is up: its own size is every bound
            status == DamageMsg.STATUS_OK.toLong() -> kib * 1024            // op 5's OWN status, from op 5's own reply
            else -> CfwModes.TEXTURE_CACHE_SIZE                             // refused: the first write allocates the v1 64 KiB
        }
        // a field 18 off the wire is not this shell's arithmetic: an atlas laid out over a size the
        // builder refuses would throw on the shell loop (2026-09-15, the third review)
        if (size !in CfwModes.TEXTURE_CACHE_SIZE..CfwModes.CACHE2_MAX) {
            emitNote("glass", "cache size: the glasses report $size B, outside 64..160 KiB — this session uses the v1 64 KiB")
            return CfwModes.TEXTURE_CACHE_SIZE
        }
        emitNote("glass", "cache size: asked $kib KiB, status $status, ${size / 1024} KiB this session" +
            (if (allocated != null) " (allocated already)" else ""))
        return size
    }

    /** The KiB an op 5 is asking for while its reply is awaited, or null. Op 5 and FLAGS_SET both
     *  answer on request id 0 and never overlap (the start asks for the size, then arms), so this
     *  says which of the two an id-0 record belongs to. */
    @Volatile private var cacheSizePending: Int? = null
    /** The statuses op 5 itself records (`FIRMWARE.md` §4). 4 and 5 are its alone; 0 and 3 it
     *  shares with FLAGS_SET, which is not outstanding while this is. */
    private fun cacheSizeReplyAnswers(t: DamageMsg.Telemetry): Boolean = (t.lastStatus ?: -1L).toInt() in
        listOf(DamageMsg.STATUS_OK, DamageMsg.STATUS_NO_LEASE, DamageMsg.STATUS_ALLOCATED, DamageMsg.STATUS_BUDGET)

    private fun loggerSwitch(epoch: Long, on: Boolean) =
        CtlWork.BothArms(epoch, LoggerMsg.SID, "logger switch (${if (on) "on" else "off"})") { id -> LoggerMsg.switchSet(id, on) }

    /** FIRMWARE.md §0: the last DamageCaps a READ response carried (null = none). */
    @Volatile private var damageCaps: DamageMsg.Caps? = null
    private val telemetryIds = java.util.concurrent.atomic.AtomicInteger(0)
    /** Telemetry answers awaited by request id (RIGHT answers; the sweep fails them). */
    private val telemetryWaiters = java.util.concurrent.ConcurrentHashMap<Int, CompletableDeferred<DamageMsg.Telemetry>>()
    /** `FORK.md` §3.2, the arm / hold-back protocol: the flag set the phone wants armed on a
     *  Damage build, re-armed after every session start (a `flags=` probe sets it; Phase 6
     *  gives it a Settings row); the glasses' uptime as last read, with the phone's clock
     *  then; when the last arming was sent. A reset that follows an arming within
     *  [HOLD_BACK_MS] disarms the set — the keeper never repeats a faulty feature on every
     *  reconnect — and says so as a fault. */
    @Volatile private var wantedFlags = 0
    @Volatile private var lastUptime: Pair<Long, Long>? = null      // (uptimeMs, phone clock ms)
    @Volatile private var lastArmedAtMs: Long? = null
    /** The last reset seen through the uptime rule ([noteUptime]), on the phone's clock. */
    @Volatile private var lastResetAtMs: Long? = null
    /** The reset a hold-back already decided on, so it holds back once (the carry still reads it). */
    @Volatile private var heldBackForResetAt: Long? = null
    /** A hold-back caught DRAW2 in the arming: no start arms it again until a hand-set flag set asks. */
    @Volatile private var draw2HeldBack = false
    /** The reset check's record (step 1b): whether RIGHT reported an allocated cache (field 18). */
    @Volatile private var resetCheckCache: Long? = null
    @Volatile private var resetCheckRead = false
    /** `selftest=live:` writes this transport has put into the live cache. */
    private val liveCacheWrites = java.util.concurrent.atomic.AtomicInteger()

    /** One sid-0x0F message from [arm] (LoggerMsg): a log line becomes a
     *  `glasslog` journal note, the switch's answer a log line. */
    private fun onLoggerFrame(arm: Arm, payload: ByteArray) {
        val m = LoggerMsg.parse(payload)
        val a = arm.name.first()
        when {
            m == null -> Log.w(name, "sid-0x0F frame from $arm did not parse: " +
                payload.take(48).joinToString("") { "%02x".format(it) })
            m.cmd == LoggerMsg.CMD_DEVICE_SEND_DATA && m.logStr != null -> emitNote("glasslog", "$a ${m.logStr}")
            m.cmd == LoggerMsg.CMD_SWITCH_SET -> {
                Log.i(name, "logger switch answered by $arm: bleTransEn=${m.transEn} msgId=${m.magic}")
                emitNote("probe", "logger switch answered by $arm (bleTransEn=${m.transEn})")
            }
            else -> Log.i(name, "sid-0x0F cmd ${m.cmd} from $arm: " +
                payload.take(48).joinToString("") { "%02x".format(it) })
        }
    }

    /** Panel brightness (2026-08-31): a sid-0x09 write on the control lane,
     *  fire-and-forget like the lease (msgId 0; the firmware's ack is not
     *  awaited — a lost write is corrected by the next push or session start). */
    override fun setBrightness(auto: Boolean, level: Int) {
        val v = if (auto) "auto" else "$level"
        if (!_state.value.started) { Log.w(name, "brightness -> $v not sent: no session (the next start pushes it)"); return }
        Log.i(name, "brightness -> $v")
        // §47: answered, and re-sent once if a later answer shows it was eaten
        controlQueue.trySend(CtlWork.Settings(sessionEpoch.get(), SettingsMsg.brightnessWrite(0, auto, level),
            ackWanted = true, label = "brightness"))
    }

    protected val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 1024)
    override val events = _events.asSharedFlow()

    private val _state = MutableStateFlow(LinkState(transportName = name))
    override val state = _state.asStateFlow()
    private val stateLock = Any()

    /** All read-copy-update on the state flow goes through here — plain
     *  `.value = .value.copy()` from two coroutines loses updates. */
    protected fun updateState(f: (LinkState) -> LinkState) {
        synchronized(stateLock) { _state.value = f(_state.value) }
    }

    // ------------------------------------------------------------------ queues
    /** Every queued item is stamped with the session epoch it belongs to.
     *  Sessions end (stop, failed start, link death) by bumping the epoch and
     *  SWEEPING — lanes drop stale work LOUDLY, never execute it into the next
     *  driver's session (review round 3 D1/D8). */
    private sealed class ImgWork(val epoch: Long) {
        class Flush(epoch: Long, val id: Long, val request: FlushRequest) : ImgWork(epoch)
        class Raw(epoch: Long, val image: ByteArray, val done: CompletableDeferred<Unit>?) : ImgWork(epoch)
        /** Mode-7 sub-0: clears the firmware's flags, fid ring AND fid
         *  baseline — so the host's tracker and allocator resync with it
         *  (round 5 F1: a bare raw clear left them a full sequence ahead and
         *  the next delta manufactured the f_skip it was meant to clear). */
        class ClearDiag(epoch: Long) : ImgWork(epoch)
    }

    private sealed class CtlWork(val epoch: Long) {
        class Hub(epoch: Long, val payload: ByteArray, val awaitAck: CompletableDeferred<EvenHubMsg.Ack>?) : CtlWork(epoch)
        /** [failed] completes with the reason if the write never goes out —
         *  a gate waiting for this query's answer must not park forever on a
         *  write failure that ends no link (round 7 D1). */
        class Settings(epoch: Long, val payload: ByteArray, val failed: CompletableDeferred<String>? = null,
            /** §47 (2026-09-12): a WRITE whose answer is awaited — the firmware
             *  answers a sid-0x09 write with a `09-00` carrying the msgId
             *  (G2CC docs/G2_BLE_PROTOCOL.md §3 row 15, capture; faceclaw
             *  awaits the same ack). A write a later answer shows was never
             *  answered is re-sent ONCE, then a fault. */
            val ackWanted: Boolean = false, val label: String = "settings", val retry: Int = 0) : CtlWork(epoch)
        class Lease(epoch: Long, val op: Int, val written: CompletableDeferred<Unit>? = null) : CtlWork(epoch)
        /** The sid-0x01 connect prelude (LaunchMsg); [failed] completes with the
         *  reason if the write never goes out, like [Settings]. */
        class Launch(epoch: Long, val failed: CompletableDeferred<String>? = null) : CtlWork(epoch)
        /** §49 probes: one fire-and-forget message written to BOTH arms (each arm
         *  answers for itself) — the log stream's switch (sid 0x0F) and the Damage
         *  control ops (sid 0x09 field 112). [payload] gets the msgId. */
        /** [failed] is completed with the arm and the reason when a write does not leave, so a
         *  caller that needs BOTH lenses to have taken it can say so (2026-09-15, second review:
         *  a LEFT write that failed while the link stayed up was a note, and RIGHT's answer made
         *  the session look armed — the left lens then refuses every v2 op and cannot report it). */
        /** [written] completes once an attempt reached EVERY arm — the positive form of [failed],
         *  which latches on the first arm that threw and so cannot be re-read across a re-ask
         *  (2026-09-15, the third review: one transient failure condemned a set a later attempt
         *  armed). A caller that needs both lenses waits for this, not for the absence of that. */
        class BothArms(epoch: Long, val sid: Int, val label: String, val failed: CompletableDeferred<String>? = null,
                       val written: CompletableDeferred<Unit>? = null,
                       val payload: (Int) -> ByteArray) : CtlWork(epoch)
    }

    private val imageQueue = Channel<ImgWork>(Channel.UNLIMITED)
    private val controlQueue = Channel<CtlWork>(Channel.UNLIMITED)
    private val flushIds = AtomicLong(0)

    /** In-flight un-acked image messages; slots free on ack. */
    private val window = Semaphore(WINDOW)

    /** Held per MESSAGE write; also guards msgId/aaSeq/session allocation. */
    private val wire = Mutex()

    // wire-mutex-confined counters
    private var msgId = 0
    private var aaSeq = 0
    private var session = 1
    @Volatile private var sessionPenalty = false

    // image-lane-confined
    private var fids = FidAllocator()
    private val tracker = FidTracker()

    /** Test hook: start the fid sequence near the wrap so a test can drive
     *  the 0xFFFE -> 1 boundary in a few flushes. Call before start(). */
    internal fun seedFidsForTest(start: Int) {
        fids = FidAllocator(start)
    }

    @Volatile protected var lastImageAtMs = 0L
    @Volatile private var lastImageAckAtMs = 0L
    @Volatile private var stallReported = false
    @Volatile protected var running = false
    @Volatile private var started = false
    /** Lanes and maintenance loops are launched exactly ONCE and survive
     *  stop/start cycles (they idle on `running`) — a second start() during a
     *  PC takeover must not double them: two image lanes racing Emit was
     *  review round 2's fid-corruption finding. */
    @Volatile private var workersLaunched = false

    /** The session epoch: bumped by start() and stop(). Work stamped with an
     *  older epoch is dropped loudly by the lanes (round 3 D1/D8). */
    private val sessionEpoch = AtomicLong(0)

    /** True only while start()'s capability gate is waiting — settings frames
     *  arriving at any other time must not disturb the CONFLATED rendezvous
     *  for a future gate (round 3 observation: uncorrelated capability). */
    @Volatile private var awaitingCapability = false

    /** True from session entry until start() returns or fails: the sweep's
     *  gate-abort sentinel is owed for that whole span (round 5 F2). */
    @Volatile private var startInProgress = false

    /** This session asked for the FB lease (start() step 3): only then does a
     *  rollback release it. */
    @Volatile private var leaseRequested = false

    // ------------------------------------------------- the lease log (the bounded atlas skip)
    /**
     * Per arm, what this transport knows about the lease on the glasses (2026-09-15,
     * `HANDOFF.md` §54 — Adam's ruling of §51.8): the [nowMs] times of the ACQUIRE writes
     * that left for the arm and are taken as having ARRIVED, newest last, and whether a
     * RELEASE left for it since the last of them. A write that left within
     * [LINK_SETTLE_MS] of the link's end is struck at the end ([leaseLinkEnded]): the
     * platform reports a write as done when its stack took it, and the packets of the
     * last seconds before a supervision timeout were never exchanged. A RELEASE is never
     * struck — one that may have arrived has freed the cache. Both are the conservative
     * direction: a doubt costs one atlas upload, never a lens drawing from a missing cache.
     */
    private class LeaseLog {
        val acquires = ArrayDeque<Long>()
        var released = false
        /** The arm's link ended the way a lens reboot looks from the phone since the last carry
         *  decision a completed start acted on (2026-09-15 12:54: the right lens rebooted, its cache
         *  emptied, and the skip kept the atlas on the lease timing alone): a supervision timeout, a
         *  link loss, a reason the platform could not name, or a host that reports none (BlueZ). */
        var timedOut = false
    }
    private val leaseLog = mapOf(Arm.LEFT to LeaseLog(), Arm.RIGHT to LeaseLog())   // guarded by itself
    /** The carry-over is decided once per session, at its first ACQUIRE. */
    @Volatile private var leaseCarryDecided = false
    /** A carry decision was made and the start that made it has not completed: the shell never
     *  acted on it, and that session's ACQUIRE may have been a fresh one on a rebooted lens — the
     *  next decision is "not carried" (2026-09-15 review), and the reboot marks stand until then. */
    @Volatile private var carryUncommitted = false

    private fun leaseWritten(arm: Arm, op: Int, now: Long) = synchronized(leaseLog) {
        val l = leaseLog.getValue(arm)
        when (op) {
            SettingsMsg.OP_FB_ACQUIRE -> {
                // a repeat of the same instant (the instant test clock) is one fact; the record
                // keeps the newest few (never by age: a renewal written just before a link end is
                // struck at the end, and the one before it must still be there — 2026-09-15, a
                // test race showed an age prune emptying the record that way)
                if (l.acquires.lastOrNull() != now) l.acquires.addLast(now)
                while (l.acquires.size > LEASE_LOG_DEPTH) l.acquires.removeFirst()
                l.released = false
            }
            SettingsMsg.OP_FB_RELEASE -> l.released = true
        }
    }

    /** The link ended at [now]: the acquires of the last [LINK_SETTLE_MS] may never have
     *  reached the glasses and are struck from the record; an arm the [reason] names as having
     *  timed out is marked, since a reboot there is what a timeout looks like from here. */
    private fun leaseLinkEnded(now: Long, reason: String = "") = synchronized(leaseLog) {
        for ((arm, l) in leaseLog) {
            while (l.acquires.isNotEmpty() && now - l.acquires.last() < LINK_SETTLE_MS) l.acquires.removeLast()
            if (reason.startsWith(arm.name) && REBOOT_LIKE_ENDS.any { it in reason }) l.timedOut = true
        }
    }

    /** An arm's link ended, whether or not a session is still running: the record is marked so the
     *  next carry decision sees it. The first arm's end stops the session, and the second arm's
     *  report — which may be the one naming the lens that rebooted — used to be dropped as "not in
     *  use" (2026-09-15, second review). Safe to call before [onLinkDown], which marks it too. */
    protected fun noteArmLinkEnd(reason: String) = leaseLinkEnded(nowMs(), reason)

    /** The start that decided the carry completed: the shell acts on the decision now, so its
     *  evidence is spent. */
    private fun commitLeaseCarry() = synchronized(leaseLog) {
        carryUncommitted = false
        for (l in leaseLog.values) l.timedOut = false
    }

    /**
     * At the session's first ACQUIRE: was the previous lease still inside its window on
     * both arms, so that this write is a RENEWAL on the glasses and the texture cache
     * stays (`CLAIMS.md`: freed on expiry, FB_RELEASE, a fresh acquire and mode 11; kept
     * on a renewal)? The window is the firmware's 90 s less [LEASE_CARRY_MARGIN_MS] for
     * the two writes' own delivery. The answer and its facts go to [LinkState] for the
     * shell, which journals them either way.
     */
    private fun decideLeaseCarry(now: Long) {
        if (leaseCarryDecided) return
        leaseCarryDecided = true
        var carried = true
        val parts = ArrayList<String>(4)
        val resetAt = lastResetAtMs
        val damage = damageCaps?.has(DamageMsg.FEATURE_TELEMETRY) == true
        synchronized(leaseLog) {
            if (carryUncommitted) { parts += "the last start did not complete"; carried = false }
            carryUncommitted = true
            for (arm in Arm.entries) {
                val l = leaseLog.getValue(arm)
                val tag = arm.name.first()
                val last = l.acquires.lastOrNull()
                when {
                    l.released -> { parts += "$tag released"; carried = false }
                    last == null -> { parts += "$tag no lease on record"; carried = false }
                    else -> {
                        val gap = now - last
                        parts += "$tag gap ${String.format(java.util.Locale.ROOT, "%.1f", gap / 1000.0)} s"   // a journal fact: one spelling on every locale
                        if (gap >= LEASE_CARRY_WINDOW_MS) carried = false
                        // a lens that rebooted since that acquire holds nothing, whatever the timing
                        // (2026-09-15 12:54): RIGHT says so through its uptime, read at this start;
                        // LEFT cannot report, so its link timing out is taken as a possible reboot —
                        // and so is RIGHT's on a build without telemetry
                        if (arm == Arm.RIGHT && damage && resetAt != null && resetAt > last) {
                            parts += "R reset ${(now - resetAt) / 1000} s ago"; carried = false
                        } else if (arm == Arm.RIGHT && damage && resetCheckRead && resetCheckCache == null) {
                            // the reset check's own record: RIGHT holds no cache at all, whatever the timing
                            parts += "R holds no cache"; carried = false
                        } else if (l.timedOut && (arm == Arm.LEFT || !damage)) {
                            parts += "$tag link ended as a reboot would (a reboot there cannot be read)"; carried = false
                        }
                    }
                }
            }
        }
        val detail = parts.joinToString(", ")
        Log.i(name, "lease carry-over at this session's acquire: ${if (carried) "a renewal on both arms" else "not a renewal"} ($detail; window ${LEASE_CARRY_WINDOW_MS / 1000} s)")
        updateState { it.copy(leaseCarried = carried, leaseCarry = detail) }
    }

    private class PendingAck(val flushId: Long, val windowed: Boolean) {
        val done = CompletableDeferred<EvenHubMsg.Ack>()
        /** Registration order (msgId wraps at 249; this never does): the
         *  release rule below compares image pendings by it. */
        var seq = 0L
        /** For a control message that may be RE-SENT (the carrier CREATE): the
         *  answer every copy shares. A copy the firmware ate never gets its own
         *  ack, but if a sibling's arrived nothing was lost (§34). */
        var shared: CompletableDeferred<EvenHubMsg.Ack>? = null
    }

    /** msgId -> pending. Written under [wire], completed by the notify thread. */
    private val pendingAcks = ConcurrentHashMap<Int, PendingAck>()
    private var pendingSeq = 0L        // wire-mutex-confined, like the msgId counter

    /** §47: a settings WRITE awaiting its `09-00` answer, by msgId. The
     *  firmware answers sid-0x09 requests in order on the RIGHT arm, so a
     *  later answer (a device-info READ's, 60 s apart at most) releases an
     *  earlier write as lost — the §34 rule, on the control lane. */
    private class PendingSettings(val payload: ByteArray, val label: String, val retry: Int, val seq: Long)
    private val pendingSettings = ConcurrentHashMap<Int, PendingSettings>()
    /** Issue order of EVERY sid-0x09 request this session, by msgId (reads
     *  included) — what "sent after it" means for the release rule. */
    private val settingsSeqByMsgId = ConcurrentHashMap<Int, Long>()
    private var settingsSeq = 0L       // wire-mutex-confined

    /** msgId -> when it was released as lost, so a late ack for it is named as
     *  late (the rule fired early) rather than "unknown". Cleared per session. */
    private val recentlyReleased = ConcurrentHashMap<Int, Long>()

    /**
     * 🔴 A LATER image fragment's ack releases every EARLIER image pending as
     * lost (2026-09-05, `HANDOFF.md` §34 — measured in §33.4: 49 lost acks
     * in five days on the phone path, each holding a window slot for a whole
     * msgId cycle of 249 messages, and twice the whole window until the
     * display sat frozen for 25 and 48 s). Image fragments go out in order on
     * one arm and the firmware completes them in order, so an ack for a
     * fragment registered AFTER a still-pending one means the earlier ack is
     * not coming. The reference implementations slide their window forward
     * through missed acks the same way (`overview.md` §5, g2-kit and
     * Faceclaw). What "lost" does: the permit comes back now; a flush whose
     * FINAL fragment it was completes as failed with a named reason, and the
     * compositor's lost-flush path re-sends from the truth (cells marked
     * unknown); a non-final fragment's pending has nothing to complete — the
     * flush's final ack still decides it. Control-lane pendings (the other
     * arm, another link) are never compared. A late ack that does arrive is
     * reported as such, so a rule firing early shows up in the journal.
     */
    private fun releaseEarlierImagePendings(laterId: Int, laterSeq: Long) {
        // a snapshot through toArray (ArrayList's copy): Kotlin's toList() reads size() first and then
        // the iterator, and an entry the notify thread removes in between throws NoSuchElementException
        // (seen in ShellKeeperTest's sweep, 2026-09-15 review)
        for ((id, p) in ArrayList(pendingAcks.entries)) {
            if (!p.windowed || p.seq >= laterSeq) continue
            if (!pendingAcks.remove(id, p)) continue
            window.release()
            updateState { it.copy(inFlight = WINDOW - window.availablePermits) }
            val now = nowMs()
            recentlyReleased.entries.removeIf { now - it.value > RELEASED_MEMORY_MS }
            recentlyReleased[id] = now
            Log.e(name, "msgId $id: ack LOST — msgId $laterId, sent after it, acked first; slot released" +
                (if (p.flushId >= 0) ", flush ${p.flushId} fails and is re-sent" else ""))
            emitFault("ack", "msgId $id ack lost — msgId $laterId (sent after it) acked first; slot released")
            p.done.completeExceptionally(LintError("ack for msgId $id lost — msgId $laterId, sent after it, acked first"))
        }
    }

    private val capabilityChannel = Channel<String>(Channel.CONFLATED)

    /** The prelude rendezvous: the ack ("ok"), or the session-end marker. Only
     *  open while [awaitingPrelude]; [preludeMsgId] is stamped by the control
     *  lane under the wire mutex BEFORE the request is written, so the ack can
     *  never arrive before the id it must match is known. */
    private val preludeChannel = Channel<String>(Channel.CONFLATED)
    @Volatile private var awaitingPrelude = false
    @Volatile private var preludeMsgId = -1
    private val reassemblers = mapOf(
        Arm.LEFT to AaFrame.Reassembler { Log.w(name, "L: $it") },
        Arm.RIGHT to AaFrame.Reassembler { Log.w(name, "R: $it") },
    )

    // ------------------------------------------------------------------ wire seam
    /** Write one AA packet to [arm]. Callers hold [wire]; implementations need
     *  no ordering of their own. */
    protected abstract suspend fun writeArm(arm: Arm, packet: ByteArray)

    /** Bring the physical link up; notifications must then reach [onNotifyPacket]. */
    protected abstract suspend fun connectLink()

    protected abstract suspend fun disconnectLink()

    /** Called on each maintenance tick — subclasses report lease/link extras. */
    protected open fun onMaintenanceTick() {}

    /** Hook awaiting/modeling one image message's ack round trip — runs in the
     *  async completion path, AFTER the final fragment ack arrives (or, for the
     *  sim, models the arrival delay itself). */
    protected open suspend fun onImageDelivered() {}

    protected open val instant: Boolean = false

    protected open fun nowMs(): Long = System.currentTimeMillis()

    /** When the glasses last said anything at all (any packet, either arm)
     *  — the watchdog's liveness signal (§40). */
    @Volatile private var lastInboundAtMs = 0L

    /** Subclasses feed every notification packet here. Thread-safe. */
    protected fun onNotifyPacket(arm: Arm, packet: ByteArray) {
        lastInboundAtMs = nowMs()
        val frame = synchronized(reassemblers) { reassemblers.getValue(arm).offer(packet) } ?: return
        when (frame.sid) {
            EvenHubMsg.SID -> when (frame.flag) {
                EvenHubMsg.FLAG_ACK -> {
                    val ack = EvenHubMsg.parseAck(frame.payload)
                    if (ack == null) {
                        // NO SILENT FAILURES: an unparseable ack means a pending
                        // message may now wait forever — say so.
                        Log.e(name, "unparseable e0 ack (${frame.payload.size} B) — a message may stall")
                        emitFault("ack", "unparseable ack payload")
                        return
                    }
                    val pending = pendingAcks.remove(ack.msgId)
                    if (pending == null) {
                        val releasedAt = recentlyReleased.remove(ack.msgId)
                        if (releasedAt != null) {
                            // the rule above fired early for this one: the
                            // ack came, out of order or late. Bytes were spent
                            // re-sending; the pixels are right either way.
                            Log.w(name, "LATE ack for msgId ${ack.msgId}, ${nowMs() - releasedAt} ms after it was released as lost")
                            emitFault("ack", "late ack for msgId ${ack.msgId} (${nowMs() - releasedAt} ms after release) — acks arrived out of order")
                        } else {
                            Log.w(name, "ack for unknown msgId ${ack.msgId} (late or duplicate)")
                        }
                        return
                    }
                    if (pending.windowed) {
                        window.release()
                        lastImageAckAtMs = nowMs()
                        stallReported = false
                        updateState { it.copy(inFlight = WINDOW - window.availablePermits) }
                        releaseEarlierImagePendings(ack.msgId, pending.seq)
                    }
                    if (ack.errorCode != null && !pending.done.isCompleted) {
                        emitFault("imgres", "${ack.statusText} (${ack.errorCode}) on msgId ${ack.msgId}")
                    }
                    pending.done.complete(ack)
                }
                EvenHubMsg.FLAG_EVENT -> routeEvent(frame.payload)
                EvenHubMsg.FLAG_ABORT ->
                    emitFault("abort", "e0-02 reassembly abort from glasses")
            }
            LaunchMsg.SID -> {
                val hex = frame.payload.joinToString("") { "%02x".format(it) }
                if (!awaitingPrelude) {
                    Log.i(name, "sid-0x01 frame outside the prelude wait (flag 0x%02x): %s".format(frame.flag, hex))
                    return
                }
                val id = LaunchMsg.msgIdOf(frame.payload)
                if (LaunchMsg.isEvent(frame.flag)) {
                    // the reference resolves acks only on non-event flags; a
                    // matching id under an event flag would mean the flag
                    // assumption (graded U) is wrong — say so at first light
                    Log.w(name, "sid-0x01 EVENT frame during the prelude wait (flag 0x%02x, msgId %s, pending %d): %s"
                        .format(frame.flag, id, preludeMsgId, hex))
                    return
                }
                if (id == preludeMsgId) preludeChannel.trySend("ok")
                else Log.w(name, "sid-0x01 response for msgId $id while waiting for $preludeMsgId — ignored (flag 0x%02x): %s"
                    .format(frame.flag, hex))
            }
            SettingsMsg.SID -> {
                // The firmware's Silent Mode (§36): a device-initiated push
                // says it changed; a READ response says what it restored.
                // Parsed before everything else — while it is on, every image
                // is refused, and the shell must know before it sends one.
                SettingsMsg.parseSilentModePush(frame.payload)?.let { on -> noteSilent(on, "pushed"); return }
                // FIRMWARE.md §3: a Damage build's presented notify (field 113, F1.3) — one
                // per panel transfer while the flag is armed: a journal record, never a note
                DamageMsg.parsePresented(frame.payload)?.let { p ->
                    if (!_events.tryEmit(TransportEvent.Presented(p.seq, p.workerUs, p.copyUs, p.transferUs, p.path)))
                        Log.w(name, "presented event dropped (buffer full): seq ${p.seq}")
                    return
                }
                // FIRMWARE.md §3: a Damage build's telemetry record (commandId 3, field 111)
                DamageMsg.parseTelemetry(frame.payload)?.let { t ->
                    Log.i(name, "glass telemetry from $arm: ${t.describe()}")
                    emitNote("glass", "${arm.name.first()} ${t.describe()}")
                    // Only RIGHT can answer (`FIRMWARE.md` §3, `CLAIMS.md`: the left lens's stock
                    // senders refuse), and the hold-back rule, the reset check and the carry
                    // decision all read these as RIGHT's. A record arriving on LEFT is therefore
                    // not a second opinion, it is a fact about the build we do not have — and
                    // feeding it to `noteUptime` would make the uptime alternate between two
                    // lenses' clocks, silently (2026-09-16 review).
                    if (arm != Arm.RIGHT) {
                        emitNote("glass", "a telemetry record arrived on LEFT, which cannot send — ignored")
                        return
                    }
                    t.uptimeMs?.let { noteUptime(it) }
                    t.requestId?.let { id ->
                        // a FLAGS_SET reply (id 0) is taken only when it answers the set being armed:
                        // RIGHT answers every control request twice (2026-09-15 12:54, `HANDOFF.md`
                        // §59), and the second copy of the previous reply used to complete the next
                        // set's waiter with the previous flags — a false "refused" fault
                        val w = telemetryWaiters[id.toInt()]
                        // id 0 is the echo of FLAGS_SET and of op 5; `cacheSizePending` says which
                        // one is outstanding (they never overlap — the start asks for the size,
                        // then arms), so each takes only a record that answers IT
                        val answers = id != 0L || (if (cacheSizePending != null) cacheSizeReplyAnswers(t) else flagsReplyAnswers(t))
                        if (w != null && answers) { telemetryWaiters.remove(id.toInt(), w); w.complete(t) }
                    }
                    return
                }
                // §47: an answer to one of OUR sid-0x09 requests (a READ's or
                // a WRITE's cmdId, never a device-initiated message's) — the
                // write it answers is done, and any write sent before it and
                // still unanswered is lost (re-sent once). Before the
                // capability gate's early return below, which must not hide it.
                if (frame.flag == SettingsMsg.FLAG_RESPONSE &&
                    Pb.varintField(frame.payload, 1)?.toInt() in setOf(1, 2))
                    Pb.varintField(frame.payload, 2)?.let { onSettingsResponse(it.toInt()) }
                SettingsMsg.parseSilentRestored(frame.payload)?.let { on -> noteSilent(on, "read") }
                // Battery rides EVERY device-info response (payload f4.12/13 —
                // G2CC §10, capture-confirmed), the capability-gate answer and
                // the periodic poll alike: parse it before any gate check.
                SettingsMsg.parseBattery(frame.payload)?.let { (pct, chg) ->
                    emitBattery(glassesPct = pct, glassesCharging = chg)
                }
                if (!awaitingCapability) {
                    Log.d(name, "settings frame outside the capability gate ignored")
                    return
                }
                val cap = SettingsMsg.parseCapability(frame.payload)
                if (cap != null) {
                    // FIRMWARE.md §0: the READ answer that carries field 100 is the
                    // one that says whether this is a Damage build (field 110) — read
                    // both from that frame, so no other settings frame arriving
                    // inside the gate window can reset the answer
                    damageCaps = DamageMsg.parseCaps(frame.payload)
                    capabilityChannel.trySend(cap)
                } else if (frame.flag == SettingsMsg.FLAG_RESPONSE) {
                    // A settings response WITHOUT the EVENCFW field IS the answer
                    // (stock firmware): forward emptiness so the gate refuses
                    // loudly instead of hanging in silence.
                    damageCaps = null
                    capabilityChannel.trySend("")
                }
            }
            LoggerMsg.SID -> onLoggerFrame(arm, frame.payload)
            RingMsg.SID -> {
                // The ring data relay. Whether the glasses push RingRawData
                // unprompted is an open probe (CAPABILITIES.md §3) — read what
                // arrives, log what does not parse, request nothing.
                val pct = RingMsg.parseBattery(frame.payload)
                if (pct != null) emitBattery(ringPct = pct)
                else Log.i(name, "sid-0x91 ring relay without rawData battery: " +
                    frame.payload.take(24).joinToString("") { "%02x".format(it) })
            }
        }
    }

    /** §47: the firmware answered msgId [id] on sid 0x09. Completes the
     *  write under it, if one waits, and releases every write issued BEFORE
     *  it that is still waiting — the firmware answers in order, so those
     *  answers are not coming. A released write is re-sent once; a second
     *  loss is a fault the wearer sees (the glasses then hold their own
     *  value for that setting). */
    private fun onSettingsResponse(id: Int) {
        val answered = pendingSettings.remove(id)
        if (answered != null) Log.i(name, "settings write '${answered.label}' msgId $id answered" +
            (if (answered.retry > 0) " (the re-send)" else ""))
        val seq = settingsSeqByMsgId[id] ?: return
        for ((pid, p) in ArrayList(pendingSettings.entries)) {
            if (p.seq >= seq || !pendingSettings.remove(pid, p)) continue
            if (p.retry == 0) {
                val text = "settings write '${p.label}' msgId $pid not answered before msgId $id (sent after it) was — re-sent once"
                Log.w(name, text)
                emitNote("control", text)
                if (!controlQueue.trySend(CtlWork.Settings(sessionEpoch.get(), p.payload,
                        ackWanted = true, label = p.label, retry = p.retry + 1)).isSuccess)
                    Log.e(name, "re-send of '${p.label}' could not be queued")
            } else {
                val text = "'${p.label}' write lost twice (msgId $pid, after a re-send) — the glasses may hold their own ${p.label}"
                Log.e(name, text)
                emitFault("settings", text)
            }
        }
    }

    private var lastLoggedGlassesPct = -1
    private var lastLoggedRingPct = -1

    private fun emitBattery(glassesPct: Int? = null, glassesCharging: Boolean? = null, ringPct: Int? = null) {
        // log CHANGES (the 60 s poll would otherwise repeat one line forever):
        // the first-light lesson — a telemetry path you cannot observe in the
        // log is undiagnosable when the cell stays blank
        if (glassesPct != null && glassesPct != lastLoggedGlassesPct) {
            lastLoggedGlassesPct = glassesPct
            Log.i(name, "battery: glasses $glassesPct%${if (glassesCharging == true) " (charging)" else ""}")
            // §49 (FORK.md M0.6): the journal keeps every change, so a day's
            // drain can be read from /journal — /log's tail holds under a day
            emitNote("battery", "glasses $glassesPct%${if (glassesCharging == true) " charging" else ""}")
        }
        if (ringPct != null && ringPct != lastLoggedRingPct) {
            lastLoggedRingPct = ringPct
            Log.i(name, "battery: ring $ringPct% (sid-0x91 relay)")
        }
        if (!_events.tryEmit(TransportEvent.Battery(glassesPct, glassesCharging, ringPct))) {
            Log.w(name, "battery event dropped (buffer full): g=$glassesPct r=$ringPct")
        }
    }

    private fun routeEvent(payload: ByteArray) {
        when (val ev = EvenHubMsg.parseEvent(payload)) {
            is EvenHubMsg.Event.Gesture ->
                if (ev.type == EvenHubMsg.EV_FOREGROUND_ENTER || ev.type == EvenHubMsg.EV_FOREGROUND_EXIT ||
                    ev.type == EvenHubMsg.EV_SYSTEM_EXIT) {
                    // The firmware's SYSTEM events are facts about the app
                    // slot, not gestures (`HANDOFF.md` §37.0): until 2026-09-05
                    // they reached the shell as gesture types and were dropped
                    // there by the ring-only filter, unseen. Faceclaw resets
                    // its layout on the exit events (FaceclawBleCommunicator
                    // .java, the sys-event branch) — the firmware ending the
                    // page — and the phone's journal on the night of §36 could
                    // not say whether Silent Mode sent them. Journaled now;
                    // nothing is keyed on them until the journal has shown
                    // when they arrive.
                    val text = "${EvenHubMsg.eventName(ev.type)} (type ${ev.type}, source ${ev.source})"
                    Log.i(name, "system event: $text")
                    emitNote("event", text)
                } else emitInput(ev.type, ev.source)
            is EvenHubMsg.Event.TextEvent -> {
                // On the CFW carrier, ring scroll arrives as Text_ItemEvents on
                // the capture container (G2_BLE_PROTOCOL.md §6.6: "scrollUp/
                // scrollDn @nav" are text-region events; taps are sys events).
                // The wire carries no per-source byte for these (§6.6 note).
                when (ev.type) {
                    EvenHubMsg.EV_SCROLL_TOP, EvenHubMsg.EV_SCROLL_BOTTOM ->
                        emitInput(ev.type, EvenHubMsg.SRC_RING)
                    else -> Log.d(name, "text event ${ev.type} on '${ev.name}' ignored")
                }
            }
            is EvenHubMsg.Event.ListSelect ->
                Log.w(name, "list event on the carrier (container '${ev.name}') — unexpected, ignored")
            null -> Log.w(name, "unparseable e0-01 event (${payload.size} B)")
        }
    }

    private fun emitInput(type: Int, source: Int) {
        // Log every inbound gesture. At first light (2026-08-30) input did not work
        // and NOTHING in the log could say whether events were arriving and being
        // mishandled or never arriving at all — the two have completely different
        // causes and we could not tell them apart. An input path you cannot observe
        // is a silent failure even when every individual component is loud.
        Log.i(name, "input: ${EvenHubMsg.eventName(type)} (type $type, source $source)")
        if (!_events.tryEmit(TransportEvent.Input(type, source))) {
            Log.e(name, "input event buffer overflow — a gesture was DROPPED")
        }
    }

    protected fun emitFault(what: String, detail: String) {
        if (!_events.tryEmit(TransportEvent.Fault(what, detail))) {
            Log.e(name, "FAULT DROPPED (buffer full): $what: $detail")
        }
    }

    /** The glasses' Silent Mode state changed, or was read: state + event,
     *  once per change. */
    private fun noteSilent(on: Boolean, how: String) {
        var changed = false
        synchronized(stateLock) {
            if (_state.value.glassesSilent != on) { _state.value = _state.value.copy(glassesSilent = on); changed = true }
        }
        if (!changed) return
        Log.w(name, "the glasses' Silent Mode is ${if (on) "ON" else "OFF"} ($how)" +
            (if (on) " — the firmware refuses every image while it is" else ""))
        if (!_events.tryEmit(TransportEvent.SilentMode(on))) Log.e(name, "SilentMode event DROPPED (buffer full)")
    }

    /** Whether the renewal loop keeps the lease (§36): dropped on demand while
     *  the glasses are silent, taken back on wake. */
    @Volatile private var leaseWanted = true

    /** Whether the EvenHub page is ours to keep alive (§42): not while the
     *  shell has dropped the lease on purpose, and not while the glasses say
     *  they are silent — the page has ended either way, and every message
     *  into it goes unanswered. */
    private fun pageTrafficWanted(): Boolean = leaseWanted && !_state.value.glassesSilent

    override suspend fun setLeaseWanted(wanted: Boolean) {
        if (leaseWanted == wanted) return
        leaseWanted = wanted
        if (!_state.value.started) return
        val written = CompletableDeferred<Unit>()
        controlQueue.trySend(CtlWork.Lease(sessionEpoch.get(),
            if (wanted) SettingsMsg.OP_FB_ACQUIRE else SettingsMsg.OP_FB_RELEASE, written))
        awaitReleaseWrite(written, if (wanted) "on wake" else "while the glasses are silent")
        if (wanted) setLease(true, "FB lease taken back — the glasses are awake")
        else setLease(false, "FB lease released on purpose — the glasses are silent, stock owns the display")
    }

    /**
     * The deliberate session end (`HANDOFF.md` §37.0, G2CC's recovery path):
     * quiet the link observers FIRST — `running` false is what makes the
     * subclasses' disconnect callbacks read the coming disconnect as ours
     * (the phone's observer checks `linkUp`, BlueZ's checks `running`), so
     * the link end is reported exactly once, below — then end the link, then
     * sweep and surface it the way a lost link is (`onLinkDown`): the keeper's
     * poll sees `started` false and rebuilds the session from the connect
     * up. The lease is not released first: while the glasses are silent the
     * shell has already dropped it, and otherwise the fail-open covers the
     * seconds until the new session's acquire. Refused, with a log line, when
     * no session is started or one is still being set up (a start in
     * progress rolls itself back on its own link end).
     */
    override suspend fun restartSession(reason: String): Boolean {
        if (!started || startInProgress) {
            Log.w(name, "session restart refused ($reason): ${if (startInProgress) "a start is in progress" else "no session is started"}")
            return false
        }
        Log.w(name, "session restart: $reason — ending the link so the keeper rebuilds the session")
        emitNote("restart", reason)
        running = false
        started = false
        withContext(NonCancellable) {
            try {
                disconnectLink()
            } catch (e: Exception) {
                Log.w(name, "disconnect for the restart: ${e.message}")
            }
        }
        onLinkDown("restart: $reason")
        return true
    }

    protected fun emitNote(kind: String, detail: String) {
        if (!_events.tryEmit(TransportEvent.Note(kind, detail))) Log.w(name, "note dropped (buffer full): $kind: $detail")
    }

    protected fun emitFlags(flags: Map<String, Boolean>) {
        if (flags.any { it.value } && !_events.tryEmit(TransportEvent.DiagFlags(flags))) {
            Log.e(name, "DiagFlags event DROPPED (buffer full): $flags")
        }
    }

    protected fun setLease(held: Boolean, detail: String) {
        var changed = false
        synchronized(stateLock) {
            if (_state.value.leaseHeld != held) {
                // a lease that ended (a lapse, or the release in Silent Mode) took the flags and the
                // cache's size with it on the glasses (`FIRMWARE.md` §3, §4): the state says so, and
                // nothing draws with DRAW2 until a start arms it again (2026-09-15 review)
                _state.value = if (held) _state.value.copy(leaseHeld = true)
                    else _state.value.copy(leaseHeld = false, flagsInForce = 0, cacheSize = CfwModes.TEXTURE_CACHE_SIZE)
                changed = true
            }
        }
        if (changed && !_events.tryEmit(TransportEvent.Lease(held, detail))) {
            Log.e(name, "Lease event DROPPED (buffer full): held=$held $detail")
        }
    }

    // ------------------------------------------------------------------ lifecycle
    override suspend fun start(warmupFrame: ByteArray) {
        check(!started) { "transport already started — a second driver must stop() first" }
        val epoch = sessionEpoch.incrementAndGet()
        started = true
        leaseWanted = true                // a new session holds its lease until the shell says otherwise
        // ...and it does not inherit the last one's Silent Mode (2026-09-16 review). `noteSilent`
        // is the only writer and the READ path can only ever SET it: `parseSilentRestored`
        // returns null when the device-info field is absent, and then nothing is called. A stale
        // `true` carried into a new session made `Shell.startLocked` enter Silent Mode on an awake
        // pair, with no recovery but a process restart or a real push. Cleared here, the READ or
        // the push below re-establishes it, and if the glasses really are silent the refusal
        // streak of §36 takes the shell back there — the path that is designed to.
        synchronized(stateLock) { _state.value = _state.value.copy(glassesSilent = false) }
        // the gate may be aborted by a sweep from the moment the session
        // begins (a link death DURING connect, round 5 F2) — drain any stale
        // residue first, then arm the abort path for the whole start
        capabilityChannel.tryReceive()
        preludeChannel.tryReceive()
        preludeMsgId = -1
        lastImageAtMs = nowMs()        // the keepalive counts from THIS session
        lastInboundAtMs = nowMs()      // and so does the watchdog's quiet
        quietTicks = 0
        watchdogProbed = false
        leaseRequested = false
        startInProgress = true
        leaseCarryDecided = false
        updateState { it.copy(leaseCarried = false, leaseCarry = "") }
        try {
            connectLink()
            // `running` only once a link exists (round 8): the maintenance
            // loops would otherwise queue keepalives and renewals into a link
            // still being scanned for, each a loud fault and a stale pending
            running = true
            updateState { it.copy(connected = true) }
            _events.emit(TransportEvent.Link(true, "$name link up"))

            if (!workersLaunched) {
                workersLaunched = true
                scope.launch { imageLane() }
                scope.launch { controlLane() }
                launchMaintenance()
            }

            // 0. Connect prelude (HANDOFF.md §8.2, LaunchMsg): the CFW reference
            //    settles ~800 ms after both arms are up, sends one sid-0x01
            //    app-launch request and waits for its ack before the lease and
            //    the settings query. Same gate shape as the capability query:
            //    the ack, the session-end marker, or the write failing — every
            //    way it can end is a completion here; nothing is time-bounded.
            updateState { it.copy(detail = "connect prelude") }
            if (!instant) delay(PRELUDE_SETTLE_MS)
            awaitingPrelude = true
            val pre = try {
                val writeFailed = CompletableDeferred<String>()
                controlQueue.trySend(CtlWork.Launch(epoch, writeFailed))
                // The same re-ask the two gates below run (§40, 2026-09-06):
                // a prelude whose ack is lost — a pair that has gone quiet, a
                // session rebuilt by the watchdog into the same silence —
                // used to park this start for good, because nothing below it
                // ever ran. The request is repeated on the pacing tick until
                // the ack, the sweep or a failed write ends the wait; a
                // repeated launch request is what G2CC's cold-launch retries
                // sent all day (its `COLD_INIT` re-launch), graded C on the
                // CFW — the answer to a second one is a second ack.
                val reask = scope.launch {
                    while (true) {
                        delay(CAPABILITY_REASK_MS)
                        if (!awaitingPrelude) break
                        Log.i(name, "connect prelude unacked after ${CAPABILITY_REASK_MS} ms — sending again")
                        // the SAME deferred the select waits on, not a throwaway: attempt 1's
                        // write can succeed and be eaten, and every attempt after it fail on a
                        // link that is still nominally up — the gate then had nothing left that
                        // could end it and the start parked for good, which the watchdog cannot
                        // see because it gates on a start that has completed (2026-09-16 review,
                        // the class §63.3 item 4 closed for the two gates below this one)
                        controlQueue.trySend(CtlWork.Launch(epoch, writeFailed))
                    }
                }
                try {
                    kotlinx.coroutines.selects.select<String> {
                        preludeChannel.onReceive { it }
                        writeFailed.onAwait { reason -> SWEPT + "prelude not written: $reason" }
                    }
                } finally {
                    reask.cancel()
                }
            } finally {
                awaitingPrelude = false
            }
            if (pre.startsWith(SWEPT)) {
                throw LintError("connect prelude ended early — ${pre.removePrefix(SWEPT)}")
            }

            // 1. Capability gate (§9.2b): field 100 rides the settings READ
            //    response itself — no timeout needed by construction; an ABSENT
            //    field is a loud refusal (see onNotifyPacket). The rendezvous
            //    was drained at session entry; open the gate window now.
            updateState { it.copy(detail = "capability query") }
            awaitingCapability = true
            val cap = try {
                val queryFailed = CompletableDeferred<String>()
                controlQueue.trySend(CtlWork.Settings(epoch, SettingsMsg.settingsQuery(0), queryFailed))
                // Seen live 2026-08-31 (HANDOFF.md §12): a query that lands
                // while the firmware is still settling a PREVIOUS session's
                // context (its SYSTEM_EXIT / sid-0x01 status chatter) can be
                // eaten — three successive starts parked here. The READ is
                // idempotent, so RE-ASK on a pacing tick until an answer or
                // the sweep arrives. Pacing, not a timeout (the keeper's
                // 250 ms poll is the precedent): the gate never gives up on
                // its own, it just repeats the question.
                val reask = scope.launch {
                    while (true) {
                        delay(CAPABILITY_REASK_MS)
                        if (!awaitingCapability) break
                        Log.i(name, "capability query unanswered after ${CAPABILITY_REASK_MS} ms — asking again")
                        controlQueue.trySend(CtlWork.Settings(epoch, SettingsMsg.settingsQuery(0), queryFailed))
                    }
                }
                try {
                    // the answer, the sweep's sentinel, or the query's own write
                    // failing — every way the gate can end is a completion here
                    kotlinx.coroutines.selects.select<String> {
                        capabilityChannel.onReceive { it }
                        queryFailed.onAwait { reason -> SWEPT + "capability query not written: $reason" }
                    }
                } finally {
                    reask.cancel()
                }
            } finally {
                awaitingCapability = false
            }
            if (cap.startsWith(SWEPT)) {
                // the session ended (link loss, stop) while the gate waited: the
                // session-end clear answered it so this start fails LOUDLY
                // instead of parking forever (round 4 D1). Not a refusal — the
                // firmware never answered — so it is retried like a link loss.
                throw LintError("capability query ended early — ${cap.removePrefix(SWEPT)}")
            }
            val missing = SettingsMsg.missingCaps(cap)
            if (cap.isEmpty() || missing.isNotEmpty()) {
                val msg = if (cap.isEmpty())
                    "capability gate FAILED: no EVENCFW string — this is NOT the CFW; refusing to paint"
                else "capability gate FAILED: '$cap' missing $missing — refusing to paint"
                _events.emit(TransportEvent.Fault("capability", msg))
                throw CapabilityRefused(msg)
            }
            updateState { it.copy(capability = cap, damageContract = damageCaps?.contract ?: 0, damageFeatures = damageCaps?.features ?: 0, flagsInForce = 0) }
            // The private mirror models the build this session talks to: without its contract it has
            // no handler for mode 16 or for the v2 ops, so every self-test message and every flush
            // carrying a fill, a per-lens draw or a hint would raise a `mirror/decode` fault and put
            // the mirror out of step with belief — an urgent DIVERGE notice and a keyframe per
            // episode, on a build whose lenses are drawing correctly (2026-09-15, second review).
            // Set before the control ops below, so the mirror follows the flags and the cache size.
            if (teeMirror) mirrorSim.damageContract = damageCaps?.contract

            // 1b. On a Damage build, the reset check (`FORK.md` §3.2): RIGHT's uptime, read here
            //     so that the carry decision at the lease below knows whether the right lens
            //     rebooted since the last acquire (2026-09-15 12:54: it had, its cache was gone,
            //     and the atlas was kept on the lease timing alone — the right lens then refused
            //     every cached draw until the next full upload). Answered within the capability
            //     read's own pacing; the session's end fails it like the gates above.
            resetCheckRead = false
            if (damageCaps?.has(DamageMsg.FEATURE_TELEMETRY) == true) {
                updateState { it.copy(detail = "reset check") }
                val t = telemetryRead(epoch, label = "reset check") ?: throw LintError("reset check ended early — the session ended")
                resetCheckCache = t.cacheSize
                resetCheckRead = true
            }

            // 2. Carrier CREATE — image container + the full-screen dummy text
            //    container that is the event antenna (overview.md §4.1).
            updateState { it.copy(detail = "carrier create") }
            val createAck = CompletableDeferred<EvenHubMsg.Ack>()
            controlQueue.trySend(CtlWork.Hub(epoch, EvenHubMsg.carrierCreate(0), createAck))
            // Same eaten-message class as the capability gate (HANDOFF.md §12,
            // seen live one gate further down): a CREATE that lands in the
            // firmware's previous-session teardown is never answered. RE-SEND
            // on the same pacing tick. A duplicate CREATE before the warmup is
            // safe — it recreates the empty carrier, and the sacrificial
            // warmup follows the LAST create either way. The same deferred
            // rides every send: whichever ack arrives first completes it, and
            // the sweep fails it on a session end, so the loop always ends.
            val createReask = scope.launch {
                while (true) {
                    delay(CAPABILITY_REASK_MS)
                    if (createAck.isCompleted) break
                    Log.i(name, "carrier CREATE unacked after ${CAPABILITY_REASK_MS} ms — sending again")
                    controlQueue.trySend(CtlWork.Hub(epoch, EvenHubMsg.carrierCreate(0), createAck))
                }
            }
            val created = try {
                createAck.await()
            } finally {
                createReask.cancel()
            }
            if (created.errorCode != null)
                throw LintError("carrier CREATE rejected: ${created.statusText} (${created.errorCode})")

            // 3. FB lease, BOTH arms (display_copy_hook runs per lens). The
            //    write is awaited: a lease that never reached the wire would
            //    otherwise read as held while the glasses fail open (review
            //    round 1, a4).
            updateState { it.copy(detail = "framebuffer lease") }
            val leaseWritten = CompletableDeferred<Unit>()
            leaseRequested = true
            controlQueue.trySend(CtlWork.Lease(epoch, SettingsMsg.OP_FB_ACQUIRE, leaseWritten))
            leaseWritten.await()

            // 3b. Contract 2 (`FIRMWARE.md` §4): the cache's size for the session and DRAW2 armed
            //     BEFORE the shell paints — its atlas is a v2 layout written with mode 19 and
            //     drawn with modes 17/18, none of which answer until the flag is in force. The
            //     hold-back rule stands (armFeatures); a refusal leaves the session on v1 shapes.
            val caps2 = damageCaps
            if (caps2 != null && caps2.has(DamageMsg.FEATURE_DRAW2)) {
                updateState { it.copy(detail = "cache size and flags") }
                // a session that will not arm DRAW2 draws with v1 shapes over the first 64 KiB, so
                // asking for 160 would spend 96 KiB of arena 13 against §4's budget for nothing
                val size = requestCacheSize(epoch, if (draw2HeldBack) DamageMsg.CACHE_MIN_KIB else DamageMsg.CACHE_BUDGET_KIB)
                updateState { it.copy(cacheSize = size) }
                // DRAW2 belongs to the session, not to the wish carried to other builds; a hold-back that
                // caught it keeps it off until asked for by hand
                if (draw2HeldBack) emitNote("keeper", "DRAW2 held back since a reset followed its arming — this session draws with v1 shapes (probe flags=0x… with bit 2 to try again)")
                armFeatures(epoch, extra = if (draw2HeldBack) 0 else DamageMsg.FLAG_DRAW2)
            } else {
                updateState { it.copy(cacheSize = CfwModes.TEXTURE_CACHE_SIZE) }
            }

            // 4. The sacrificial warmup frame — the firmware silently drops the
            //    first burst after CREATE (§5.17: make it the splash).
            updateState { it.copy(detail = "warmup frame") }
            val warmupDone = CompletableDeferred<Unit>()
            imageQueue.trySend(ImgWork.Raw(epoch, warmupFrame, warmupDone))
            // The CREATE's own shape, one gate up (2026-09-16 review): this was the last start
            // gate that was written once and awaited. Its ack rides the image lane, and an image
            // ack can be eaten at a session start (§12) or lost (§34) — and nothing else is on
            // the link yet, because every maintenance loop gates on `started`, which this gate
            // precedes. So no later ack could release it, no write could throw, and the start
            // parked for good with the keeper blocked inside it, where the watchdog cannot see it.
            // A repeated sacrificial frame is safe (it is a keyframe, and the firmware drops the
            // first burst by design), and §34's rule — a lost ack is released by a later one —
            // ends the wait loudly if the first is the one that went missing.
            //
            // The pacing is the STALL threshold, not the control gates' 2 s: a duplicate CREATE
            // costs a second ack, but a duplicate IMAGE costs a window slot AND declares the
            // first one lost, so re-sending a warmup that is merely SLOW would fail the start and
            // cost a rebuild — a whole atlas on a link already struggling. The splash compresses
            // to a couple of KB, which the measured table acks well inside a second even at p90,
            // so at this interval "no ack" is the lost case the shell already reports as a stall.
            val warmupReask = scope.launch {
                while (true) {
                    delay(if (instant) 200L else STALL_REPORT_MS)
                    if (warmupDone.isCompleted) break
                    Log.w(name, "warmup frame unacked after ${STALL_REPORT_MS} ms — sending again; " +
                        "a later ack releases the earlier write (§34)")
                    emitNote("control", "the warmup frame went unacked for ${STALL_REPORT_MS / 1000} s — sent again")
                    imageQueue.trySend(ImgWork.Raw(epoch, warmupFrame, warmupDone))
                }
            }
            try { warmupDone.await() } finally { warmupReask.cancel() }

            // the session must still be THIS one: a link that ended between the warmup's ack and
            // here has already cleared `running` and swept, and writing started = true over it
            // would leave the state saying "driving" on a link that is gone — the keeper polls a
            // dead session and the rollback below never runs (2026-09-15, second review)
            if (epoch != sessionEpoch.get() || !running) throw LintError("the session ended during the warmup — the start does not complete")
            updateState { it.copy(started = true, leaseHeld = true, detail = "") }
            startInProgress = false
            commitLeaseCarry()                // §54: the shell acts on this session's carry decision now
            // What this build offers the radio (the phone asks for LE 2M on a bit-6 build): asked
            // once the session is up, not inside the start. The request goes into the arm's own
            // request queue and completes when the controllers answer, and a request that never
            // completes would otherwise park the start itself part way (2026-09-15, second review;
            // the completion is unmeasured — `REMINDER.md` watches for the `link` note on the first
            // Phase 2 session). It still precedes the atlas upload, which is the session's big
            // transfer.
            damageCaps?.let { onDamageBuild(it) }
            // §49 probe: the CREATE above ended the firmware's log stream
            if (loggerWanted) controlQueue.trySend(loggerSwitch(epoch, true))
            // FIRMWARE.md §0: what the READ answered about a Damage build, once per session
            val caps = damageCaps
            emitNote("glass", if (caps == null) "no DamageCaps field: an upstream g2flash build"
                else "DamageCaps contract ${caps.contract} features 0x${caps.features.toString(16)}")
            // FORK.md §3.2: the wanted flags, re-armed one at a time under the hold-back rule;
            // off the start's own path (the shell paints meanwhile), ended by the session's sweep
            if (caps != null && wantedFlags != 0 && !caps.has(DamageMsg.FEATURE_DRAW2)) scope.launch { armFeatures(epoch) }
        } catch (e: Exception) {
            startInProgress = false
            // Roll back COMPLETELY (round 3 D4): a failed start must not leave
            // the lease renewing with no driver, or the instance refusing every
            // retry with "already started". The rollback runs even when this
            // coroutine was CANCELLED (a lost arbitration, a keeper pause):
            // the links must not outlive the attempt (review round 1, a2).
            // The lease is released only if this session asked for it — a
            // release from a session that never held it would take another
            // driver's lease away.
            running = false
            started = false
            withContext(NonCancellable) {
                sweepSession("start failed: ${e.message}")
                if (workersLaunched && leaseRequested) {
                    // AWAITED before the disconnect, as stop() awaits its own
                    // (review §29): enqueued and left to race the disconnect
                    // below, the release either never reached the wire or
                    // reached a link being torn down and logged a "control
                    // lane error" fault for a write nobody expected to work
                    val released = CompletableDeferred<Unit>()
                    controlQueue.trySend(CtlWork.Lease(epoch, SettingsMsg.OP_FB_RELEASE, released))
                    awaitReleaseWrite(released, "after the failed start")
                }
                leaseLinkEnded(nowMs())    // §54
                try {
                    disconnectLink()
                } catch (d: Exception) {
                    Log.w(name, "disconnect after failed start: ${d.message}")
                }
                if (teeMirror) mirrorSim.linkReset()
                updateState { it.copy(connected = false, started = false, leaseHeld = false, detail = "") }
            }
            throw e
        }
    }

    /** Maintenance: lease renewal (45 s against the 90 s fail-open expiry — a
     *  liveness requirement, not a timeout), idle keepalive, carrier text
     *  refresh, subclass tick. Launched ONCE; each loop idles while !running so
     *  the same set serves every start/stop cycle without duplication. All
     *  enqueue on the CONTROL lane, which never blocks on the ack window — a
     *  stalled flush cannot cost the lease. */
    private fun launchMaintenance() {
        // The three enqueuing loops run only once start() has completed:
        // nothing may reach the wire before the connect prelude (review
        // round 1, a3); start() enqueues the first lease itself.
        scope.launch {
            while (isActive) {
                delay(if (instant) 50 else SettingsMsg.LEASE_RENEW_MS)
                if (_state.value.started && leaseWanted) controlQueue.trySend(CtlWork.Lease(sessionEpoch.get(), SettingsMsg.OP_FB_ACQUIRE))
            }
        }
        scope.launch {
            while (isActive) {
                delay(if (instant) 50 else 4_000)
                // §42 (2026-09-09): the page traffic sleeps with the glasses. While
                // the shell has dropped the lease on purpose (Silent Mode, §36) the
                // firmware's EvenHub page is gone, and a keepalive into it is never
                // acked — the phone journal counted 8,641 of them in one day, one
                // every 4 s for ten hours. The 60 s device-info READ below stays: it
                // is the wake poll.
                if (_state.value.started && pageTrafficWanted() && (nowMs() - lastImageAtMs > 4_000 || instant)) {
                    controlQueue.trySend(CtlWork.Hub(sessionEpoch.get(), EvenHubMsg.keepalive(0), null))
                }
                // Stall REPORT (round 4 D5) — a diagnostic, not a timeout:
                // nothing is cancelled or retried. A lost image ack with the
                // link otherwise healthy leaves the window full forever and the
                // screen frozen while every indicator reads fine; say so.
                if (running && !instant && !stallReported && window.availablePermits == 0 &&
                    lastImageAckAtMs != 0L && nowMs() - lastImageAckAtMs > STALL_REPORT_MS) {
                    stallReported = true
                    emitFault("stall", "window full with no image ack for " +
                        "${(nowMs() - lastImageAckAtMs) / 1000} s — a fragment ack was lost; " +
                        "the msgId cycle cannot recover it without new writes")
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(if (instant) 50 else 30_000)
                if (_state.value.started && pageTrafficWanted()) controlQueue.trySend(CtlWork.Hub(sessionEpoch.get(), EvenHubMsg.carrierTextUpgrade(0), null))
            }
        }
        scope.launch {
            // Battery poll (2026-08-31): the BARE device-info READ (G2CC §10's
            // live-confirmed form — the f4-sub-request form comes back without
            // the device-info block on the real CFW). First ask ~5 s after
            // start, then once a minute; onNotifyPacket parses f4.12/13 from
            // every sid-0x09 response, unsolicited 09-01 updates included.
            var first = true
            while (isActive) {
                delay(if (instant) 60 else if (first) 5_000 else 60_000)
                if (_state.value.started) {
                    first = false
                    controlQueue.trySend(CtlWork.Settings(sessionEpoch.get(), SettingsMsg.deviceInfoQuery(0)))
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(if (instant) 20 else 1_000)
                watchdogTick()
                if (teeMirror) {
                    val now = nowMs()
                    mirrorSim.tick(now)
                    // the model is the one thing that knows when the lease
                    // would have failed open: derive the lease state from it,
                    // as the sim transport does (review round 1, a4)
                    if (_state.value.started) {
                        val held = mirrorSim.leaseHeld(Arm.LEFT, now) && mirrorSim.leaseHeld(Arm.RIGHT, now)
                        if (!held) setLease(false, "lease expired in the model — renewals did not reach the wire (fail-open)")
                        else setLease(true, "lease held (model)")
                    }
                }
                if (running) onMaintenanceTick()
            }
        }
    }

    // ------------------------------------------------------------------ watchdog (§40)
    private var quietTicks = 0
    private var watchdogProbed = false

    /**
     * G2CC's response-gap watchdog, in this transport's terms (Adam,
     * 2026-09-06: *"the watchdog thing is fine"* — `HANDOFF.md` §38.5, §40).
     * The glasses answer something every few seconds while a session is up
     * — the 4 s keepalive's ack at the least, image acks while painting, the
     * 60 s READ's response — so a run of [WATCHDOG_QUIET_TICKS] ticks with
     * NO inbound packet on either arm is a session that has stopped
     * answering with its link still up: G2CC saw exactly that (its EvenHub
     * slot ended in silence, 2026-07-21) and recovered every time by
     * rebuilding the session. Its shape is kept: a cheap acked PROBE write
     * first (the carrier text refresh — an ack proves the page is alive when
     * a keepalive ack alone would not), and the restart only when the probe
     * is not answered either, [WATCHDOG_PROBE_TICKS] later. Ticks, not
     * elapsed time, so the instant test transport runs the same rule.
     *
     * Never while the glasses say they are silent (`glassesSilent`): the
     * sleeping shell has its own wake, and a rebuild under Silent Mode makes
     * a page the firmware ends again. Never before a session is up. Each new
     * session starts its own count, so a pair that stays quiet is asked once
     * per session — the keeper's pacing, not a loop of its own.
     */
    private suspend fun watchdogTick() {
        if (!running || !_state.value.started || _state.value.glassesSilent) {
            quietTicks = 0; watchdogProbed = false
            return
        }
        val quietMs = nowMs() - lastInboundAtMs
        // the instant transport's keepalive runs every 50 ms: its quiet bound
        // is ten of those, well past a loaded test JVM's scheduling jitter (a
        // 40 ms bound restarted healthy sessions under the whole battery)
        if (quietMs < (if (instant) 500L else WATCHDOG_QUIET_MS)) {
            quietTicks = 0; watchdogProbed = false
            return
        }
        quietTicks++
        if (!watchdogProbed && quietTicks >= WATCHDOG_QUIET_TICKS) {
            watchdogProbed = true
            Log.w(name, "no packet from the glasses for ${quietMs / 1000} s — probing the page with a carrier refresh")
            emitNote("watchdog", "no response for ${quietMs / 1000} s — probe sent")
            controlQueue.trySend(CtlWork.Hub(sessionEpoch.get(), EvenHubMsg.carrierTextUpgrade(0), null))
            return
        }
        if (watchdogProbed && quietTicks >= WATCHDOG_QUIET_TICKS + WATCHDOG_PROBE_TICKS) {
            quietTicks = 0; watchdogProbed = false
            emitNote("watchdog", "no response for ${quietMs / 1000} s, probe unanswered — rebuilding the session")
            restartSession("no response from the glasses for ${quietMs / 1000} s (watchdog)")
        }
    }

    /** Non-blocking: the flush enters the image lane's queue in CALL order,
     *  which is what makes fid order == wire order. Backpressure is
     *  [LinkState.inFlight] against [LinkState.window] (the shell's pump gates
     *  on it) plus the window semaphore inside the lane. */
    override suspend fun submit(flush: FlushRequest): Long {
        check(_state.value.started) { "submit before start()" }
        val id = flushIds.incrementAndGet()
        imageQueue.trySend(ImgWork.Flush(sessionEpoch.get(), id, flush))
        return id
    }

    /** `FIRMWARE.md` §3, op 4 CACHE_INFO: the record RIGHT answers with, for the shell's check of
     *  an atlas upload. One control round trip; nothing is sent on a build that cannot answer. */
    override val cacheCheckSupported: Boolean get() = true

    override suspend fun cacheCheck(): DamageMsg.Telemetry? {
        if (!started) return null
        if (damageCaps?.has(DamageMsg.FEATURE_TELEMETRY) != true) return null
        return telemetryRead(sessionEpoch.get(), op = DamageMsg.OP_CACHE_INFO, label = "atlas check")
    }

    override suspend fun telemetry(label: String): DamageMsg.Telemetry? {
        if (!started) return null
        if (damageCaps?.has(DamageMsg.FEATURE_TELEMETRY) != true) return null
        return telemetryRead(sessionEpoch.get(), op = DamageMsg.OP_TELEMETRY, label = label)
    }

    override suspend fun clearDiagFlags() {
        imageQueue.trySend(ImgWork.ClearDiag(sessionEpoch.get()))
    }

    override suspend fun stop() {
        val epoch = sessionEpoch.incrementAndGet()   // everything older is stale
        // §42 (2026-09-09): a stop after a LINK LOSS has no link to release the
        // lease through — the write into the dropped arm failed as a `control`
        // fault at every one of the day's rebuilds, and the release that did
        // reach the surviving arm freed that lens's texture cache for nothing:
        // the keeper re-acquires within seconds (a renewal, which keeps the
        // cache — settings_ext.c FB_ACQUIRE) and the 90 s fail-open covers a
        // rebuild that never comes. The release goes out only while the link
        // is up: a deliberate stop (the app's shutdown, a target switch).
        val linkUp = _state.value.connected
        running = false
        started = false
        // Sweep FIRST (round 3 D1): fail every pending ack, restore the window
        // permits, and drain both queues loudly — a lane parked on a permit
        // that a link which is gone will never return would otherwise deadlock this
        // stop and every future start on the same instance.
        sweepSession("$name stopped")
        withContext(NonCancellable) {
        if (workersLaunched && linkUp) {
            // AWAIT the release actually reaching the wire — a fixed sleep lost
            // it behind a mid-flight keyframe's wire-mutex hold (round 2 #6),
            // leaving the glasses leased/frozen up to the 90 s fail-open. The
            // select also completes if the transport scope itself ends (the
            // lanes' finally-drains fail `released` on the way out; the onJoin
            // arm is the last-resort if death races the enqueue) — round 3 D3.
            val released = CompletableDeferred<Unit>()
            controlQueue.trySend(CtlWork.Lease(epoch, SettingsMsg.OP_FB_RELEASE, released))
            awaitReleaseWrite(released, "at stop")
        }
        leaseLinkEnded(nowMs())            // §54
        try {
            disconnectLink()
        } catch (e: Exception) {
            Log.w(name, "disconnect: ${e.message}")
        }
        if (teeMirror) mirrorSim.linkReset()
        updateState { it.copy(started = false, leaseHeld = false, connected = false, detail = "") }
        if (!_events.tryEmit(TransportEvent.Link(false, "$name stopped")))
            Log.e(name, "Link(false) after stop DROPPED (buffer full)")
        }
    }

    /** Wait for a queued lease RELEASE to reach the wire — or for the
     *  transport scope to end first (the lanes' finally-drains fail
     *  `released` on the way out; the onJoin arm is the last resort if death
     *  races the enqueue — round 3 D3). A failed write is reported, never
     *  thrown: the caller is tearing the session down either way and the
     *  90 s fail-open covers the lease. Shared by stop() and the failed-start
     *  rollback (review §29). */
    private suspend fun awaitReleaseWrite(released: CompletableDeferred<Unit>, where: String) {
        try {
            val job = scope.coroutineContext[kotlinx.coroutines.Job]
            if (job == null) {
                released.await()
            } else {
                kotlinx.coroutines.selects.select<Unit> {
                    released.onAwait { }
                    job.onJoin {
                        Log.w(name, "transport scope ended before the release write $where — " +
                            "the 90 s fail-open covers the lease")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(name, "lease release write $where failed: ${e.message}")
        }
    }

    /** End-of-session sweep: fail every in-flight ack (releasing its window
     *  permit — invariant: map membership <=> permit held), then drain both
     *  queues, completing everything exceptionally. LOUD by construction. */
    private fun sweepSession(why: String) {
        // a start() that is, or is about to be, parked on the capability gate
        // is a waiter too (round 4 D1, round 5 F2): the sentinel waits in the
        // CONFLATED rendezvous for it
        if (startInProgress) {
            capabilityChannel.trySend(SWEPT + why)
            preludeChannel.trySend(SWEPT + why)
        }
        // snapshots through toArray: a concurrent removal between toList()'s size() and its iterator threw here,
        // and a throw out of the sweep left the link end unreported (2026-09-15 review)
        for (id in ArrayList(pendingAcks.keys)) {
            val p = pendingAcks.remove(id) ?: continue
            if (p.windowed) window.release()
            p.done.completeExceptionally(LintError("$why (msgId $id un-acked)"))
        }
        for ((id, p) in ArrayList(pendingSettings.entries)) {
            if (pendingSettings.remove(id, p)) Log.w(name, "settings write '${p.label}' msgId $id unanswered: $why")
        }
        settingsSeqByMsgId.clear()
        // msgIds are per session: a stale id left here named a NEW session's unknown ack as a
        // "LATE ack, N ms after it was released" with an elapsed time from the session before it
        // (2026-09-16 review — the field's own doc already said "cleared per session")
        recentlyReleased.clear()
        for (id in ArrayList(telemetryWaiters.keys)) telemetryWaiters.remove(id)?.completeExceptionally(LintError("$why (telemetry $id unanswered)"))
        updateState { it.copy(inFlight = WINDOW - window.availablePermits) }
        while (true) {
            val w = imageQueue.tryReceive().getOrNull() ?: break
            failImgWork(w, why)
        }
        while (true) {
            val w = controlQueue.tryReceive().getOrNull() ?: break
            failCtlWork(w, why)
        }
    }

    private fun failImgWork(w: ImgWork, why: String) {
        when (w) {
            is ImgWork.Flush -> {
                if (!_events.tryEmit(TransportEvent.FlushDone(w.id, false, 0, 0, why)))
                    Log.e(name, "FlushDone for swept flush ${w.id} DROPPED (buffer full)")
            }
            is ImgWork.Raw -> w.done?.completeExceptionally(LintError(why))
            is ImgWork.ClearDiag -> Log.w(name, "diag clear dropped: $why")
        }
    }

    private fun failCtlWork(w: CtlWork, why: String) {
        when (w) {
            is CtlWork.Hub -> w.awaitAck?.completeExceptionally(LintError(why))
                ?: Log.w(name, "control message dropped: $why")
            is CtlWork.Lease -> w.written?.completeExceptionally(LintError(why))
                ?: Log.w(name, "lease op ${w.op} dropped: $why")
            is CtlWork.Settings -> w.failed?.complete(why)
                ?: Log.w(name, "settings write dropped: $why")
            is CtlWork.Launch -> w.failed?.complete(why)
                ?: Log.w(name, "prelude write dropped: $why")
            is CtlWork.BothArms -> {
                Log.w(name, "${w.label} dropped: $why")
                w.failed?.complete(why)          // the doc on BothArms.failed says so; a swept caller must not park
            }
        }
    }

    /** Subclasses call this when the physical link ends out from under a
     *  session (BLE disconnect): sweeps so nothing waits on acks that will
     *  never come, and surfaces the loss (round 3 D1). */
    protected fun onLinkDown(reason: String) {
        Log.e(name, "link down: $reason")
        // the session is over: clear the latches like stop() does, or the
        // maintenance loops keep writing into a link that is gone and the next
        // start() is refused as "already started" (round 4 D2). The lease
        // cannot be released through a link that is gone — the fail-open is the
        // backstop, and the shell is told so.
        running = false
        started = false
        sessionEpoch.incrementAndGet()     // the session is over: queued work is stale
        leaseLinkEnded(nowMs(), reason)    // §54: the last seconds' lease writes may not have arrived; a timeout is marked
        sweepSession("link down: $reason")
        if (teeMirror) mirrorSim.linkReset()
        updateState { it.copy(connected = false, started = false, leaseHeld = false, flagsInForce = 0) }
        if (!_events.tryEmit(TransportEvent.Link(false, reason))) {
            Log.e(name, "Link-down event DROPPED (buffer full)")
        }
    }

    // ------------------------------------------------------------------ image lane
    private suspend fun imageLane() {
        try {
            for (work in imageQueue) {
                if (work.epoch != sessionEpoch.get()) {
                    failImgWork(work, "stale session work dropped (epoch ${work.epoch}, now ${sessionEpoch.get()})")
                    continue
                }
                if (!running) {
                    failImgWork(work, "transport not running")
                    continue
                }
                try {
                    when (work) {
                        is ImgWork.Flush -> laneFlush(work)
                        is ImgWork.Raw -> {
                            val final = writeImage(work.image, work.epoch)
                            completeAsync(final, flushId = -1, bytes = work.image.size,
                                t0 = nowMs(), done = work.done)
                        }
                        is ImgWork.ClearDiag -> {
                            writeImage(byteArrayOf(7, 0), work.epoch)
                            tracker.resync()
                            fids.restart()
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    failImgWork(work, "image lane cancelled")
                    throw e
                } catch (e: Exception) {
                    Log.e(name, "image lane error", e)
                    when (work) {
                        is ImgWork.Flush -> _events.emit(TransportEvent.FlushDone(
                            work.id, false, 0, 0, e.message ?: e.toString()))
                        is ImgWork.Raw -> work.done?.completeExceptionally(e)
                        is ImgWork.ClearDiag -> emitFault("diag", "clear failed: ${e.message}")
                    }
                }
            }
        } finally {
            // the lane is ending (scope cancelled): nothing queued can ever run
            // — say so to every waiter rather than leave it parked (round 3 D3)
            while (true) {
                val w = imageQueue.tryReceive().getOrNull() ?: break
                failImgWork(w, "image lane terminated")
            }
        }
    }

    private suspend fun laneFlush(work: ImgWork.Flush) {
        val t0 = nowMs()
        if (work.request.ops.isNotEmpty() && work.request.ops.all { it is DisplayOp.CacheWrite || it is DisplayOp.CacheWrite2 }) {
            // §40: the texture atlas — each mode-12 (or, on contract 2, mode-19) message is
            // its own image (no fid, no batch); the flush completes on the LAST one's ack,
            // and a refusal anywhere fails the flush loudly like any other
            try {
                var final: PendingAck? = null
                var bytes = 0
                for (op in work.request.ops) {
                    val payload = if (op is DisplayOp.CacheWrite) op.payload else (op as DisplayOp.CacheWrite2).payload
                    final = writeImage(payload, work.epoch)
                    bytes += payload.size
                    // §54: the writer is on record once the bytes left, acked or not —
                    // a chunk that left may have landed
                    val w = work.request.writer
                    updateState { it.copy(cacheWriter = w) }
                }
                completeAsync(final, work.id, bytes, t0, done = null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.emit(TransportEvent.FlushDone(work.id, false, nowMs() - t0, 0, e.message ?: e.toString()))
            }
            return
        }
        // state to hand back if the WRITE fails after the encode consumed fids
        // (round 5 F3): the glasses never saw them
        var firstFid = fids.peek()
        var lastBefore = tracker.last
        var seededBefore = tracker.seeded
        var encodeReached = false
        var flushWritten = false
        try {
            if (work.request.wide) {
                // §8.2 #4: a wide flush drains the pipeline and runs at depth 1.
                repeat(WINDOW) { window.acquire() }
                repeat(WINDOW) { window.release() }
            }
            if (fids.wrapPending) {
                // a previous flush ended on 0xFFFE and its clear did not reach
                // the wire: the firmware still holds the old baseline — clear
                // before anything else goes out
                writeImage(byteArrayOf(7, 0), work.epoch)
                tracker.resync()
                fids.clearWrap()
            }
            // §8.2 #6 handled BEFORE encoding (round 3 D2): a flush must never
            // SPAN the 0xFFFE -> 1 wrap — a mode-7 clear cannot ride inside a
            // mode-8 batch, and a post-wrap fid issued without one FID001s on
            // the host (and would f_skip on the glasses). If the fids left
            // before the wrap cannot cover this flush, clear the firmware's
            // ring now, restart the sequence, and encode entirely post-wrap.
            val rects = work.request.ops.count { it is DisplayOp.Delta || it is DisplayOp.StereoPair }
            if (Geometry.FID_MAX - fids.peek() + 1 < rects) {
                writeImage(byteArrayOf(7, 0), work.epoch)
                tracker.resync()
                fids.restart()
            }
            firstFid = fids.peek(); lastBefore = tracker.last; seededBefore = tracker.seeded
            encodeReached = true
            val encoded = Emit.encode(work.request, fids, tracker,
                window = if (work.request.wide) 1 else WINDOW)
            val final = writeImage(encoded.image, work.epoch)
            flushWritten = true
            // the flush is on the wire: its completion is its own, whatever
            // happens to the wrap clear below (round 7 D4)
            completeAsync(final, work.id, encoded.image.size, t0, done = null)
            if (fids.wrapPending) {
                // the flush ended EXACTLY on 0xFFFE: the mode-7 sub-0 clear
                // resets the FIRMWARE's fid ring, flags and lastFid; the host
                // tracker resyncs with it so the next flush starts fresh at 1
                writeImage(byteArrayOf(7, 0), work.epoch)
                tracker.resync()
                fids.clearWrap()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (flushWritten) {
                // only the post-write wrap clear can fail here; the flush
                // itself landed. wrapPending stands, the next flush retries.
                emitFault("wrap", "fid wrap clear not written: ${e.message} — retried before the next flush")
                return
            }
            if (encodeReached) {
                // the encode may have consumed fids the glasses never saw. A
                // throw BEFORE the encode (a failed wrap clear) touched no fid
                // and must leave wrapPending standing for the next retry
                // (round 6): rewinding would drop it and the next delta would
                // present the wrong baseline in silence.
                tracker.rewind(lastBefore, firstFid, seededBefore)
                fids.rewind(firstFid)
            }
            sessionPenalty = true
            _events.emit(TransportEvent.FlushDone(work.id, false, nowMs() - t0, 0,
                e.message ?: e.toString()))
        }
    }

    /** The previous flush's completion job (image-lane confined): each
     *  completion joins it first, so FlushDone events leave in SUBMISSION
     *  order by construction — acks arrive in wire order, but the completion
     *  coroutines would otherwise race for a dispatcher thread (round 3: an
     *  order-sensitive test flaked under load). A failed or swept predecessor
     *  completes too, so the join can never outlive the session. */
    private var lastCompletion: kotlinx.coroutines.Job? = null

    /** Await the final ack off-lane so the lane pipelines the next flush. */
    private fun completeAsync(
        final: PendingAck?, flushId: Long, bytes: Int, t0: Long,
        done: CompletableDeferred<Unit>?,
    ) {
        val prev = lastCompletion
        if (final == null) {
            lastCompletion = scope.launch {
                prev?.join()
                done?.complete(Unit)
                if (flushId >= 0) _events.emit(TransportEvent.FlushDone(flushId, true, 0, bytes))
            }
            return
        }
        lastCompletion = scope.launch {
            try {
                prev?.join()          // inside the try: a cancelled join still
                val ack = final.done.await()   // fails `done` below, never parks it
                onImageDelivered()
                val ackMs = nowMs() - t0
                if (ack.errorCode != null) {
                    sessionPenalty = true
                    done?.completeExceptionally(LintError("ImgResCmd ${ack.statusText} (${ack.errorCode})"))
                    if (flushId >= 0) _events.emit(TransportEvent.FlushDone(flushId, false, ackMs,
                        bytes, "ImgResCmd ${ack.statusText} (${ack.errorCode}) — damage recomputes with a fresh fid"))
                } else {
                    updateEma(ackMs, bytes)
                    done?.complete(Unit)
                    if (flushId >= 0) _events.emit(TransportEvent.FlushDone(flushId, true, ackMs, bytes))
                }
            } catch (e: Exception) {
                done?.completeExceptionally(e)
                if (flushId >= 0) _events.emit(TransportEvent.FlushDone(flushId, false,
                    nowMs() - t0, bytes, e.message ?: e.toString()))
            }
        }
    }

    /**
     * One CFW image buffer -> sequential ImgRawMsg fragment messages (<=3800 B),
     * bulk to the LEFT arm. Fragments write back-to-back, each holding a window
     * slot until its ack arrives — up to WINDOW un-acked messages ride the link.
     * Returns the FINAL fragment's pending ack (flush completion).
     */
    private suspend fun writeImage(image: ByteArray, atEpoch: Long): PendingAck? {
        require(image.isNotEmpty()) { "empty image buffer" }
        lastImageAtMs = nowMs()
        if (sessionPenalty) {
            sessionPenalty = false
            wire.withLock { nextSessionLocked(afterFailure = true) }
        }
        val sess = wire.withLock { nextSessionLocked(afterFailure = false) }
        var index = 0
        var off = 0
        var finalPending: PendingAck? = null
        while (off < image.size) {
            val end = minOf(off + Geometry.MAX_IMAGE_FRAGMENT, image.size)
            window.acquire()
            if (atEpoch != sessionEpoch.get()) {
                // the session ended while we waited for a slot (the sweep is
                // what returned it): never write a stale message into the next
                // driver's session (round 3 D8)
                window.release()
                throw LintError("session ended while waiting for a window slot")
            }
            updateState { it.copy(inFlight = WINDOW - window.availablePermits) }
            var registered: Pair<Int, PendingAck>? = null
            try {
                wire.withLock {
                    val id = nextMsgIdLocked()
                    val msg = EvenHubMsg.imageFragment(id, sess, image.size, index,
                        image.copyOfRange(off, end))
                    val pending = PendingAck(-1, windowed = true)
                    registerPending(id, pending)
                    registered = id to pending
                    if (end == image.size) finalPending = pending
                    for (p in AaFrame.frame(nextSeqLocked(), EvenHubMsg.SID,
                            EvenHubMsg.FLAG_REQUEST, msg)) {
                        writePacket(Arm.LEFT, p)
                    }
                }
            } catch (e: Exception) {
                // The fragment never fully left, so its ack can never arrive:
                // the entry must go WITH the permit — invariant: map membership
                // <=> permit held (round 3 D7: a stale entry double-released
                // the permit a counter cycle later). Release only if we, not a
                // racing ack, removed it.
                val r = registered
                if (r == null || pendingAcks.remove(r.first, r.second)) window.release()
                updateState { it.copy(inFlight = WINDOW - window.availablePermits) }
                throw e
            }
            off = end
            index++
        }
        return finalPending
    }

    /** msgId cycles 1..249: a WEDGED pending (lost ack) whose id comes around
     *  again must fail LOUDLY and free its window slot, not be silently
     *  overwritten with the slot leaked and the late ack misrouted (review
     *  round 2 #2). Callers hold `wire`. */
    private fun registerPending(id: Int, pending: PendingAck) {
        pending.seq = ++pendingSeq
        val prior = pendingAcks.put(id, pending)
        if (prior != null && !prior.windowed) {
            // A CONTROL message never acked holds no window slot, so its
            // counter-cycle is a fact for the journal, not a fault for the
            // status bar and never an error notice on the glasses. Measured
            // 2026-09-05 (§34): 45 of the phone journal's 53 "lost ack" faults
            // over five days were msgIds 3–8 at session start — the carrier
            // CREATE re-sends of the eaten-message class (§12), whose SHARED
            // answer had long arrived through a sibling copy.
            val eaten = prior.shared?.isCompleted == true
            val text = if (eaten) "msgId $id: the control message under it was never acked itself; its re-send was (the eaten-message class)"
                else "msgId $id: a control message was never acked (no window slot held)"
            Log.w(name, text)
            emitNote("control", text)
            prior.done.completeExceptionally(LintError(text))
            return
        }
        if (prior != null) {
            Log.e(name, "msgId $id reused while its ack is still pending — the original " +
                "message's ack was LOST; failing it and freeing its slot")
            emitFault("ack", "msgId $id pending across a full counter cycle (lost ack)")
            if (prior.windowed) {
                window.release()
                updateState { it.copy(inFlight = WINDOW - window.availablePermits) }
            }
            prior.done.completeExceptionally(
                LintError("ack for msgId $id never arrived (counter cycled)"))
        }
    }

    // wire-mutex-confined counter helpers (callers hold `wire`). msgId never
    // takes the value 0: it is a 1-byte field that stops being acked past 255 and whether
    // real firmware accepts 0 is unverified — 1..249 dodges the question.
    private fun nextMsgIdLocked(): Int { msgId = msgId % 249 + 1; return msgId }
    private fun nextSeqLocked(): Int { aaSeq = (aaSeq + 1) and 0xFF; return aaSeq }
    private fun nextSessionLocked(afterFailure: Boolean): Int {
        session = (session + if (afterFailure) 3 else 1) % 250
        if (session == 0) session = 1
        return session
    }

    // ------------------------------------------------------------------ control lane
    private suspend fun controlLane() {
        try {
            for (work in controlQueue) {
                if (work.epoch != sessionEpoch.get()) {
                    failCtlWork(work, "stale session control work dropped")
                    continue
                }
                try {
                    when (work) {
                        is CtlWork.Hub -> {
                            val pending = PendingAck(-1, windowed = false).also { it.shared = work.awaitAck }
                            var registeredId = -1
                            try {
                                wire.withLock {
                                    val id = nextMsgIdLocked()
                                    registerPending(id, pending)
                                    registeredId = id
                                    val payload = restampMsgId(work.payload, id)
                                    for (p in AaFrame.frame(nextSeqLocked(), EvenHubMsg.SID,
                                            EvenHubMsg.FLAG_REQUEST, payload)) {
                                        writePacket(Arm.RIGHT, p)
                                    }
                                }
                            } catch (e: Exception) {
                                // a message that never fully left has no ack to
                                // wait for: its entry must not linger to resurface
                                // as a spurious lost-ack fault a cycle later
                                if (registeredId >= 0) pendingAcks.remove(registeredId, pending)
                                throw e
                            }
                            // NEVER await the ack inline (round 3 D3): a lost ack
                            // would park the lane and every lease renewal behind
                            // it — the one thing this lane exists to prevent. The
                            // watcher forwards to the caller off-lane, and a swept
                            // or counter-cycled pending is reported, never thrown
                            // into the scope (round 3 D5: that faults on Android).
                            val awaiting = work.awaitAck
                            scope.launch {
                                try {
                                    val ack = pending.done.await()
                                    if (awaiting != null) awaiting.complete(ack)
                                    else if (ack.errorCode != null)
                                        emitFault("control", "${ack.statusText} (${ack.errorCode})")
                                } catch (e: Exception) {
                                    if (awaiting != null) awaiting.completeExceptionally(e)
                                    else Log.w(name, "control ack never arrived: ${e.message}")
                                }
                            }
                        }
                        is CtlWork.Settings -> try {
                            wire.withLock {
                                val id = nextMsgIdLocked()
                                val seq = ++settingsSeq
                                settingsSeqByMsgId[id] = seq
                                if (work.ackWanted) {
                                    val prior = pendingSettings.put(id, PendingSettings(work.payload, work.label, work.retry, seq))
                                    if (prior != null) {
                                        // never answered across a whole msgId cycle: a fact,
                                        // never a stall (the slot it held is nothing)
                                        val text = "settings write '${prior.label}' msgId $id never answered (counter cycled)"
                                        Log.w(name, text)
                                        emitNote("control", text)
                                    }
                                }
                                val payload = restampMsgId(work.payload, id)
                                for (p in AaFrame.frame(nextSeqLocked(), SettingsMsg.SID,
                                        SettingsMsg.FLAG_REQUEST, payload)) {
                                    writePacket(Arm.RIGHT, p)
                                }
                            }
                        } catch (e: Exception) {
                            work.failed?.complete(e.message ?: e.toString())
                            throw e
                        }
                        is CtlWork.BothArms -> wire.withLock {
                            // each arm acts on (and answers) its own copy; each write is
                            // attempted on its own so an arm that is gone does not keep
                            // the other from its copy
                            val id = nextMsgIdLocked()
                            val payload = work.payload(id)
                            var everyArm = true
                            for (arm in Arm.entries) {
                                try {
                                    for (p in AaFrame.frame(nextSeqLocked(), work.sid, 0x20, payload)) writePacket(arm, p)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e                     // the lane is ending: not a write failure
                                } catch (e: Exception) {
                                    everyArm = false
                                    Log.w(name, "${work.label} not written to $arm: ${e.message}")
                                    emitNote("probe", "${work.label} not written to $arm: ${e.message}")
                                    work.failed?.complete("$arm: ${e.message}")
                                }
                            }
                            if (everyArm) work.written?.complete(Unit)
                        }
                        is CtlWork.Launch -> try {
                            wire.withLock {
                                val id = nextMsgIdLocked()
                                preludeMsgId = id          // known before the bytes leave
                                for (p in AaFrame.frame(nextSeqLocked(), LaunchMsg.SID,
                                        LaunchMsg.FLAG_REQUEST, LaunchMsg.prelude(id))) {
                                    writePacket(Arm.RIGHT, p)
                                }
                            }
                        } catch (e: Exception) {
                            work.failed?.complete(e.message ?: e.toString())
                            throw e
                        }
                        is CtlWork.Lease -> {
                            if (work.op == SettingsMsg.OP_FB_ACQUIRE && !running) {
                                // a renewal that slipped past stop()'s running=false
                                // must not re-lease glasses just released (round 3
                                // D10); the epoch guard above catches the rest
                                work.written?.complete(Unit)
                            } else {
                                // Fire-and-forget by design (settings_ext.c:
                                // MagicRandom 0 is consumed before the stock
                                // decoder discards the field) — pure writes, can
                                // never wedge behind a stuck ack.
                                try {
                                    wire.withLock {
                                        // §54: the session's first acquire decides the atlas
                                        // carry-over from the record BEFORE it joins the record
                                        if (work.op == SettingsMsg.OP_FB_ACQUIRE) decideLeaseCarry(nowMs())
                                        for (arm in Arm.entries) {
                                            val nonce = (nowMs() and 0xFFFF).toInt()
                                            try {
                                                for (p in AaFrame.frame(nextSeqLocked(), SettingsMsg.SID,
                                                        SettingsMsg.FLAG_REQUEST, SettingsMsg.control(work.op, nonce))) {
                                                    writePacket(arm, p)
                                                }
                                                leaseWritten(arm, work.op, nowMs())
                                            } catch (e: Exception) {
                                                // §42: a RELEASE is best effort per arm — an arm that
                                                // is gone cannot take it and needs none (its lease
                                                // fails open); the other arm still gets its own. An
                                                // ACQUIRE that cannot reach an arm is a real fault.
                                                if (work.op != SettingsMsg.OP_FB_RELEASE) throw e
                                                Log.w(name, "lease release not written to $arm: ${e.message} — its lease fails open")
                                            }
                                        }
                                    }
                                    work.written?.complete(Unit)
                                } catch (e: Exception) {
                                    work.written?.completeExceptionally(e)
                                    throw e
                                }
                                if (work.op == SettingsMsg.OP_FB_ACQUIRE)
                                    setLease(true, "FB lease acquired/renewed (both arms)")
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    failCtlWork(work, "control lane cancelled")
                    throw e
                } catch (e: Exception) {
                    Log.e(name, "control lane error", e)
                    failCtlWork(work, e.message ?: e.toString())
                    emitFault("control", e.message ?: e.toString())
                }
            }
        } finally {
            while (true) {
                val w = controlQueue.tryReceive().getOrNull() ?: break
                failCtlWork(w, "control lane terminated")
            }
        }
    }

    /** Rebuild an EvenHub/settings payload with field 2 = [id]. Payloads are
     *  built with msgId 0 by callers; the lanes own the real counter. */
    private fun restampMsgId(payload: ByteArray, id: Int): ByteArray {
        val fields = Pb.fields(payload)
        val parts = ArrayList<ByteArray>(fields.size)
        for (f in fields) {
            // fixed32/fixed64 land in the varint slot of Pb.fields and would
            // re-encode as WIRE TYPE 0 — silent corruption. No control payload
            // carries them today; the first that does must fail LOUDLY here,
            // not on the glass (review 2026-09-01 L8).
            require(f.wireType == 0 || f.bytes != null) {
                "restampMsgId cannot re-encode field ${f.field} (wire type ${f.wireType}) — extend the codec first"
            }
            parts += when {
                f.field == 2 && f.varint != null -> Pb.v(2, id)
                f.varint != null -> Pb.v(f.field, f.varint)
                else -> Pb.l(f.field, f.bytes!!)
            }
        }
        return Pb.cat(parts)
    }

    private fun updateEma(ackMs: Long, bytes: Int) {
        updateState { s ->
            val a = 0.3
            // the floor from small flushes, the transfer term from big ones:
            // the all-sizes EMAs below cannot tell a slow radio from a fast
            // one, because two thirds of all flushes are under 500 B and sit
            // on the same ~60 ms floor on either path (§31.6, §32)
            val floor = if (bytes < 400) s.floorMsEma * (1 - a) + ackMs * a else s.floorMsEma
            val transfer = if (bytes >= 1000)
                s.transferMsPerKbEma * (1 - a) + (maxOf(0.0, ackMs - floor) / (bytes / 1000.0)) * a
            else s.transferMsPerKbEma
            s.copy(
                ackMsEma = s.ackMsEma * (1 - a) + ackMs * a,
                bytesPerSecEma = if (ackMs > 0)
                    s.bytesPerSecEma * (1 - a) + (bytes * 1000.0 / ackMs) * a else s.bytesPerSecEma,
                floorMsEma = floor,
                transferMsPerKbEma = transfer,
            )
        }
    }

    companion object {
        /** Faceclaw ships WINDOW_SIZE = 3 on exactly the CFW path (§8.1). */
        const val WINDOW = 3

        /** Marker the session-end clear pushes into the capability and prelude
         *  rendezvous (a leading NUL: no real answer can start with it). */
        private const val SWEPT = "\u0000swept: "

        /** The reference settles this long after both arms are up before the
         *  prelude (faceclaw sleepDuringConnectSettling(800)) — pacing, not a
         *  timeout; skipped when [instant]. */
        private const val PRELUDE_SETTLE_MS = 800L
        /** Re-ask pacing for the capability gate (2026-08-31): the firmware
         *  can eat a settings READ sent while it settles a previous session's
         *  context. Pacing, not a timeout — the gate never exits on time. */
        private const val CAPABILITY_REASK_MS = 2_000L
        /** `FORK.md` §3.2: a reset this soon after an arming disarms the feature until asked again. A placeholder until measured (M0.5 was dropped); Adam's to set. */
        private const val HOLD_BACK_MS = 120_000L
        /** How far short of the predicted uptime a reading must fall to count as a reset
         *  (armFeatures): the two clocks' drift over a day and the reply's own latency are
         *  well inside it; a reset can hide inside it only if the glasses reset within this
         *  long of booting. */
        private const val RESET_SLACK_MS = 10_000L

        /** §54, the bounded atlas skip. A lease write that left within this long of
         *  the link's end is not taken as arrived: the platform's write callback means
         *  its stack took the packet, the supervision timeout (5,000 ms in every
         *  parameter set the phone has reported, `HANDOFF.md` §47) is how long the
         *  last exchange can predate the report, and a lease write can queue behind
         *  a flush's fragments on the same arm (a 6 KB flush is ~25 packets, up to
         *  ~2.6 s at the slow 105 ms interval). Derived, not measured. */
        const val LINK_SETTLE_MS = 10_000L
        /** The two writes' own delivery (the old lease's last acquire, the new one) and
         *  the two clocks, taken off the firmware's 90 s expiry: a rebuild's acquire
         *  counts as a renewal only when the last acquire taken as arrived is younger
         *  than this. Derived, not measured. */
        const val LEASE_CARRY_MARGIN_MS = 10_000L
        const val LEASE_CARRY_WINDOW_MS = SettingsMsg.LEASE_EXPIRY_MS - LEASE_CARRY_MARGIN_MS
        /** Acquire writes remembered per arm — renewals are 45 s apart, so two would do. */
        private const val LEASE_LOG_DEPTH = 8
        /** How a link end that may be a lens reboot reads in a transport's reason (§59): the phone's
         *  supervision timeout and link loss, a disconnect reason the platform could not name, and a
         *  host that reports no reason at all (BlueZ's property change). "ended by phone" and the
         *  deliberate restarts are not among them. */
        val REBOOT_LIKE_ENDS = listOf("supervision timeout", "link loss", ": reason ", "BlueZ Connected=false")

        /** Does an id-0 telemetry record answer the FLAGS_SET of [pending]? (`FIRMWARE.md` §3: the
         *  reply echoes request id 0 and carries the flags in force; RIGHT answers every control
         *  request twice, and op 5 CACHE_SIZE answers on id 0 as well.) Only the statuses a
         *  FLAGS_SET records are a refusal of one — 2 unsupported and 3 no lease; op 5's 4 (the
         *  cache is allocated) and 5 (outside the budget) used to read as "this arming was
         *  refused", so a carried cache's status-4 duplicate could drop DRAW2 for the session and
         *  cost a 60 KB re-upload (2026-09-15, second review). */
        fun flagsReplyAnswers(status: Long, flags: Long?, pending: Int?): Boolean =
            status == DamageMsg.STATUS_UNSUPPORTED.toLong() || status == DamageMsg.STATUS_NO_LEASE.toLong() ||
                (status == DamageMsg.STATUS_OK.toLong() && pending != null && flags == pending.toLong())

        /** Reporting threshold for the stall diagnostic — well past any
         *  measured ack (176 ms median, 7–13 KB/s); reports, never acts. */
        private const val STALL_REPORT_MS = 10_000L
        /** How long a released msgId is remembered so its late ack is named. */
        private const val RELEASED_MEMORY_MS = 60_000L

        /** The watchdog (§40): inbound silence longer than this on a tick
         *  counts; [WATCHDOG_QUIET_TICKS] such ticks send the probe, and
         *  [WATCHDOG_PROBE_TICKS] more without an answer rebuild the session.
         *  A healthy session never passes ~5 s (the 4 s keepalive is acked —
         *  §34.3's count of never-acked control messages says so). ~12 s of
         *  silence to the probe, ~22 s to the rebuild; G2CC's were ~9 s and
         *  ~10 s more. */
        private const val WATCHDOG_QUIET_MS = 10_000L
        private const val WATCHDOG_QUIET_TICKS = 3
        private const val WATCHDOG_PROBE_TICKS = 10
    }
}
