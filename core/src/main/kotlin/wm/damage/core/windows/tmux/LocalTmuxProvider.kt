package wm.damage.core.windows.tmux

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import wm.damage.core.util.Log

/**
 * The exec-backed provider: runs `tmux` on this machine and, for configured
 * ssh hosts, on the far end of one ssh exec per tick (TMUX.md verdict 1 —
 * multi-host fans out from the content host; nothing deploys elsewhere).
 *
 * Every host operation is ONE `sh -c` script (local) or ONE `ssh … script`
 * (remote), so a remote host costs a single round trip per tick instead of
 * per-session chatter. Pacing, not timeouts: the status loop runs every
 * 2.5 s, a subscribed pane captures every 500 ms, both forever; a host that
 * stops answering shows its silence in the state line ("slappy quiet 42s")
 * rather than being timed out. The one bounded thing is ssh CONNECTION
 * ESTABLISHMENT (`-o ConnectTimeout=5 -o BatchMode=yes`) — equivalent to a
 * refused connect, surfaced and retried on the next tick; without it a down
 * host would wedge its loop in silence, which is the failure mode the
 * NO-SILENT-FAILURES rule exists to prevent.
 */
class LocalTmuxProvider(
    hosts: List<TmuxHostCfg>,
    private val cfg: TmuxConfig,
    private val scope: CoroutineScope,
    private val exec: TmuxExec = ProcessExec(),
    private val statusPacingMs: Long = 2_500,
    /** Default 1 s since the flow rework (2026-08-31): the terminal is text,
     *  it just needs to be current — pushes still happen only on change.
     *  Settings → Tmux → Update adjusts it live via [setCapturePacing]. */
    private val capturePacingMs: Long = 1_000,
    /** Context rows asked from every capture (`-S -n`); tmux clamps to what
     *  exists and an alternate-screen pane has none (TmuxModel.PaneFrame). */
    private val contextRows: Int = 12,
) : TmuxProvider {

    /** The exec seam — tests inject a fake; production runs processes. */
    fun interface TmuxExec {
        /** Run [script] under `sh -c` on [host] (locally, or via ssh for a
         *  remote host). Returns stdout; throws with stderr on failure. */
        fun run(host: TmuxHostCfg, script: String): String
    }

    private val hosts = hosts.ifEmpty { listOf(TmuxHostCfg("local")) }
    private val listeners = ConcurrentHashMap.newKeySet<TmuxProvider.Listener>()
    private val subs = ConcurrentHashMap<TmuxProvider.Listener, TmuxTarget>()
    private val jobs = ArrayList<Job>()
    @Volatile private var running = true

    /** Last status per host: sessions, or the failure detail. */
    private class HostState {
        @Volatile var sessions: List<TmuxSessionInfo> = emptyList()
        @Volatile var error: String? = null
        @Volatile var lastOkMs = 0L
        /** waiting-by-session-name from the previous tick — the alert EDGE. */
        @Volatile var wasWaiting: Set<String> = emptySet()
    }

    private val hostStates = ConcurrentHashMap<String, HostState>()

    /** Hosts with a poll already in flight — a slow host is SKIPPED next tick
     *  rather than piling execs behind itself. Declared BEFORE the init block
     *  that launches the loops: the coroutine can run before the constructor
     *  finishes, and a later-declared field would still be null there (found
     *  by the headless smoke run, 2026-08-31). */
    private val polling = ConcurrentHashMap.newKeySet<String>()

    private val waitRegexes = cfg.waitPatterns.mapNotNull {
        try { Regex(it) } catch (e: Exception) { Log.e("tmux", "bad wait pattern '$it': ${e.message}"); null }
    }

    init {
        for (h in this.hosts) hostStates[h.name] = HostState()
        jobs += scope.launch { statusLoop() }
        jobs += scope.launch { captureLoop() }
    }

    override fun addListener(l: TmuxProvider.Listener) {
        listeners.add(l)
        // a joining listener gets the current picture immediately
        l.status(mergedSessions(), cfg)
        l.state(stateLine())
    }

    override fun removeListener(l: TmuxProvider.Listener) {
        listeners.remove(l)
        subs.remove(l)
    }

    /** Live capture pacing; clamped so a bad setting can neither spin nor
     *  stall the loop into uselessness. Readable for the wire test. */
    @Volatile private var capturePace = capturePacingMs.coerceIn(250, 30_000)
    val capturePacing: Long get() = capturePace

    override fun setCapturePacing(ms: Long) {
        val v = ms.coerceIn(250, 30_000)
        if (v != capturePace) Log.i("tmux", "capture pacing -> $v ms")
        capturePace = v
    }

    override fun subscribe(l: TmuxProvider.Listener, target: TmuxTarget?) {
        if (target == null) subs.remove(l) else {
            subs[l] = target
            // force the next capture to PUSH even when the pane is unchanged:
            // a (re)subscriber (window re-activated, seam client reattached)
            // has no frame yet, and push-on-change alone would leave it on
            // "capturing…" until the pane happened to change (2026-08-31)
            lastRaw.remove(target)
        }
    }

    // ------------------------------------------------------------ one-shots
    override fun sendKeys(target: TmuxTarget, keys: List<String>) {
        for (k in keys) require(k.matches(Regex("[A-Za-z0-9!-~]{1,16}"))) { "key name '$k'" }
        run(target.host, "tmux send-keys -t ${shq(target.t)} ${keys.joinToString(" ") { shq(it) }}")
    }

    override fun sendLiteral(target: TmuxTarget, text: String) {
        // `-l --`: literal, and a leading '-' in the text is text, not an option
        run(target.host, "tmux send-keys -t ${shq(target.t)} -l -- ${shq(text)}")
    }

    override fun history(target: TmuxTarget, lines: Int): List<String> =
        // -J: logical lines for the flow view (scrollback is normal-screen
        // content; the flow renderer wraps it at the display's own width)
        run(target.host, "tmux capture-pane -p -e -J -S -${lines.coerceIn(1, 100_000)} -t ${shq(target.t)}")
            .split('\n').dropLastWhile { it.isEmpty() }

    override fun windows(target: TmuxTarget): List<TmuxWinInfo> =
        run(target.host,
            "tmux list-windows -t ${shq("=" + target.session + ":")} -F '#{window_index}\t#{window_name}\t#{?window_active,1,0}\t#{window_bell_flag}'")
            .split('\n').filter { it.isNotEmpty() }.map { line ->
                val f = line.split('\t')
                TmuxWinInfo(f[0].toInt(), f.getOrElse(1) { "" }, f.getOrElse(2) { "0" } == "1",
                    f.getOrElse(3) { "0" } == "1")
            }

    override fun newSession(host: String): String {
        val taken = hostStates[host]?.sessions?.map { it.name }?.toSet() ?: emptySet()
        var n = 1
        var lastErr: Exception? = null
        repeat(3) {
            while ("g2-$n" in taken) n++
            val name = "g2-$n"
            try {
                run(host, "tmux new-session -d -s ${shq(name)}")
                return name
            } catch (e: Exception) {
                lastErr = e            // likely a duplicate-name race: try the next number
                n++
            }
        }
        throw IllegalStateException("new session failed: ${lastErr?.message}")
    }

    override fun killSession(target: TmuxTarget) {
        run(target.host, "tmux kill-session -t ${shq("=" + target.session + ":")}")
    }

    override fun renameSession(target: TmuxTarget, newName: String) {
        val clean = TmuxNames.clean(newName)
            ?: throw IllegalArgumentException("name needs a letter or digit (letters, digits, - and _ only)")
        run(target.host, "tmux rename-session -t ${shq("=" + target.session + ":")} ${shq(clean)}")
    }

    override fun selectWindow(target: TmuxTarget, idx: Int) {
        run(target.host, "tmux select-window -t ${shq("=" + target.session + ":" + idx)}")
    }

    override fun resizeWindow(target: TmuxTarget, cols: Int, rows: Int) {
        run(target.host, "tmux resize-window -t ${shq(target.t)} -x ${cols.coerceIn(20, 500)} -y ${rows.coerceIn(5, 200)}")
    }

    override fun close() {
        running = false
        for (j in jobs) j.cancel()
        listeners.clear()
        subs.clear()
    }

    // ------------------------------------------------------------ the loops
    private suspend fun statusLoop() {
        while (scope.isActive && running) {
            // hosts poll in PARALLEL on IO and the push never waits for them:
            // a down ssh host's 5 s connect refusal must not delay the local
            // session list, so each tick pushes the last-known picture and
            // the polls land into the next one
            for (h in hosts) if (polling.add(h.name)) {
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try { pollHost(h) } finally { polling.remove(h.name) }
                }
            }
            // a short settle so a healthy local poll (~10 ms) lands in THIS
            // tick's push; a slow host simply lands in a later one
            delay(minOf(300, statusPacingMs / 4))
            val merged = mergedSessions()
            val line = stateLine()
            for (l in listeners) {
                try { l.status(merged, cfg) } catch (e: Exception) { Log.e("tmux", "status listener", e) }
                try { l.state(line) } catch (e: Exception) { Log.e("tmux", "state listener", e) }
            }
            delay(statusPacingMs)
        }
    }

    private fun pollHost(h: TmuxHostCfg) {
        val st = hostStates.getValue(h.name)
        val out = try {
            exec.run(h, STATUS_SCRIPT)
        } catch (e: Exception) {
            if (st.error != e.message) Log.e("tmux", "${h.name}: status poll failed: ${e.message}")
            st.error = e.message ?: "exec failed"
            return
        }
        st.error = null
        st.lastOkMs = System.currentTimeMillis()
        val sessions = parseStatus(h.name, out)
        val nowWaiting = sessions.filter { it.waiting }.map { it.name }.toSet()
        val newlyWaiting = nowWaiting - st.wasWaiting
        st.wasWaiting = nowWaiting
        st.sessions = sessions
        for (s in sessions) if (s.name in newlyWaiting) {
            for (l in listeners) try { l.alert(s) } catch (e: Exception) { Log.e("tmux", "alert listener", e) }
        }
    }

    /** One script answers everything the list needs: the session table, then
     *  each session's last tail lines behind a marker. `|| true` keeps "no
     *  server running" an EMPTY answer, not a failure (the G2CC fact). */
    private fun parseStatus(host: String, out: String): List<TmuxSessionInfo> {
        val lines = out.split('\n')
        val table = ArrayList<TmuxSessionInfo>()
        val tails = HashMap<String, MutableList<String>>()
        var tailOf: String? = null
        for (line in lines) {
            when {
                line.startsWith(TAIL_MARK) -> tailOf = line.removePrefix(TAIL_MARK)
                tailOf != null -> tails.getOrPut(tailOf) { ArrayList() }.add(line)
                line.isNotBlank() -> {
                    val f = line.split('\t')
                    if (f.size >= 4) table.add(TmuxSessionInfo(host, f[0], f[1].toIntOrNull() ?: 0,
                        f[2] == "1", f[3].toLongOrNull() ?: 0, waiting = false, lastLine = ""))
                }
            }
        }
        return table.map { s ->
            val tail = (tails[s.name] ?: emptyList()).map { Sgr.strip(it) }
            val nonBlank = tail.filter { it.isNotBlank() }
            val waiting = nonBlank.takeLast(4).any { t -> waitRegexes.any { it.containsMatchIn(t) } }
            s.copy(waiting = waiting, lastLine = nonBlank.lastOrNull()?.trim() ?: "")
        }
    }

    private fun mergedSessions(): List<TmuxSessionInfo> =
        hosts.flatMap { hostStates.getValue(it.name).sessions }
            .sortedWith(compareByDescending<TmuxSessionInfo> { it.waiting }.thenByDescending { it.activity })

    private fun stateLine(): String {
        val now = System.currentTimeMillis()
        val bad = hosts.mapNotNull { h ->
            val st = hostStates.getValue(h.name)
            when {
                st.error != null -> "${h.name}: ${st.error}"
                st.lastOkMs > 0 && now - st.lastOkMs > statusPacingMs * 4 ->
                    "${h.name} quiet ${(now - st.lastOkMs) / 1000}s"
                else -> null
            }
        }
        return bad.joinToString(" · ")
    }

    /** Last pushed capture per target — an unchanged pane pushes nothing;
     *  subscribe() clears an entry to force the joiner its first frame. */
    private val lastRaw = ConcurrentHashMap<TmuxTarget, String>()

    /** Targets with a capture already running (value = when it started) — the
     *  tick SKIPS them instead of stacking (the statusLoop pattern, applied
     *  here at last: review 2026-09-01 L4 / the §18.1 recorded debt — one
     *  lagging ssh host was serializing every other target's capture behind
     *  its 5 s connect). A capture in flight PAST the narration threshold is
     *  SAID on the pane each tick (R2#14): the skip must never turn a stuck
     *  host into a silently frozen view. Nothing is cancelled — the capture
     *  ends when its child does, and frames resume by themselves. */
    private val captureInFlight = ConcurrentHashMap<TmuxTarget, Long>()

    private suspend fun captureLoop() {
        while (scope.isActive && running) {
            val wanted = subs.entries.groupBy({ it.value }, { it.key })
            lastRaw.keys.retainAll(wanted.keys)
            for ((target, who) in wanted) {
                val h = hosts.firstOrNull { it.name == target.host } ?: continue
                val since = captureInFlight.putIfAbsent(target, System.currentTimeMillis())
                if (since != null) {
                    val s = (System.currentTimeMillis() - since) / 1000
                    if (s >= STUCK_NARRATE_S) {
                        Log.w("tmux", "capture of ${target.label} in flight ${s}s — saying so on the pane")
                        // and forget the last raw capture (R3d#3): a stalled
                        // host usually returns an UNCHANGED pane, whose
                        // dedup would suppress the recovery push and latch
                        // the stuck banner on a healthy link
                        lastRaw.remove(target)
                        for (l in who) try {
                            l.frame(target, PaneFrame(
                                listOf("(capture stuck ${s}s on ${target.host} — still waiting; frames resume when it returns)"),
                                80, 1, 0, 0, false, false, System.currentTimeMillis()))
                        } catch (x: Exception) { Log.e("tmux", "frame listener", x) }
                    }
                    continue                                   // still capturing: skip this tick
                }
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val out = try {
                            exec.run(h, captureScript(target))
                        } catch (e: Exception) {
                            for (l in who) try {
                                l.frame(target, PaneFrame(listOf("(capture failed: ${e.message})"),
                                    80, 1, 0, 0, false, false, System.currentTimeMillis()))
                            } catch (x: Exception) { Log.e("tmux", "frame listener", x) }
                            return@launch
                        }
                        if (out == lastRaw[target]) return@launch
                        lastRaw[target] = out
                        val frame = parseCapture(out) ?: return@launch
                        for (l in who) try { l.frame(target, frame) } catch (e: Exception) { Log.e("tmux", "frame listener", e) }
                    } finally {
                        captureInFlight.remove(target)
                    }
                }
            }
            delay(capturePace)
        }
    }

    /** Pane geometry/cursor line first, then the escaped capture. A NORMAL
     *  pane captures with `-J` (joined logical lines — the flow renderer
     *  wraps them its own way); an ALTERNATE-screen pane captures row-exact
     *  for the grid fallback. The branch runs remotely inside the one script,
     *  so an ssh host still costs a single round trip. */
    private fun captureScript(t: TmuxTarget): String =
        "tmux display-message -p -t ${shq(t.t)} " +
            "'#{pane_width}\t#{pane_height}\t#{cursor_x}\t#{cursor_y}\t#{cursor_flag}\t#{alternate_on}' && " +
            "if [ \"\$(tmux display-message -p -t ${shq(t.t)} '#{?alternate_on,1,0}')\" = 1 ]; then " +
            "tmux capture-pane -p -e -S -$contextRows -t ${shq(t.t)}; else " +
            "tmux capture-pane -p -e -J -S -$contextRows -t ${shq(t.t)}; fi"

    private fun parseCapture(out: String): PaneFrame? {
        val nl = out.indexOf('\n')
        if (nl < 0) return null
        val f = out.substring(0, nl).split('\t')
        if (f.size < 6) return null
        val lines = out.substring(nl + 1).split('\n').dropLastWhile { it.isEmpty() }
        return PaneFrame(lines, f[0].toIntOrNull() ?: 80, f[1].toIntOrNull() ?: 24,
            f[2].toIntOrNull() ?: 0, f[3].toIntOrNull() ?: 0, f[4] == "1", f[5] == "1",
            System.currentTimeMillis())
    }

    private fun run(hostName: String, script: String): String {
        val h = hosts.firstOrNull { it.name == hostName }
            ?: throw IllegalArgumentException("unknown tmux host '$hostName'")
        return exec.run(h, script)
    }

    companion object {
        const val TAIL_MARK = "@@TAIL@@ "

        /** A capture in flight this long is narrated on the pane each tick
         *  (R2#14) — a liveness DECISION, not a bound: nothing is cancelled. */
        const val STUCK_NARRATE_S = 15L

        /** The per-host status script: the session table, then per-session
         *  tails. `while IFS= read` keeps names with spaces whole. */
        val STATUS_SCRIPT = """
            tmux list-sessions -F '#{session_name}	#{session_windows}	#{?session_attached,1,0}	#{session_activity}' 2>/dev/null || true
            tmux list-sessions -F '#{session_name}' 2>/dev/null | while IFS= read -r s; do
              printf '$TAIL_MARK%s\n' "${'$'}s"
              tmux capture-pane -p -t "=${'$'}s:" 2>/dev/null | tail -5 || true
            done
        """.trimIndent()

        /** POSIX single-quote escaping — literal text crosses ssh safely. */
        fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }

    /** Production exec: `sh -c` locally, one `ssh` exec for a remote host.
     *  stderr rides the failure message; stdout is the answer. */
    class ProcessExec : TmuxExec {
        override fun run(host: TmuxHostCfg, script: String): String {
            val argv = if (host.ssh.isEmpty()) listOf("sh", "-c", script)
            else listOf("ssh", "-p", host.sshPort.toString(), "-o", "BatchMode=yes",
                "-o", "ConnectTimeout=5", host.ssh, script)
            // through the deadlock-proof runner (R2#14): the old
            // stdout-then-stderr read stalled once a chatty child filled the
            // 64 KiB stderr pipe mid-stream — the exact pattern Exec removes
            val r = wm.damage.core.util.Exec.run(argv)
            if (r.code != 0) throw IllegalStateException(
                "${argv[0]} exited ${r.code}${if (r.stderr.isNotBlank()) ": " + r.stderr.trim().take(200) else ""}")
            return r.stdout.toString(Charsets.UTF_8)
        }
    }
}
