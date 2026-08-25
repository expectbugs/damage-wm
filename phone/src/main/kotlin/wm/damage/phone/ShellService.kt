package wm.damage.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.content.RemoteContent
import wm.damage.core.replica.ReplicaServer
import wm.damage.core.shell.Chrome
import wm.damage.core.shell.HostSetting
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellKeeper
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.LensPanels
import wm.damage.core.transport.RemoteTransportServer
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.Transport
import wm.damage.core.util.Log
import wm.damage.core.windows.reader.ReaderWindow

/**
 * The phone's Damage process — DESIGN.md §10.1 rows 1 and 2: transport + shell
 * on the phone, content from the PC when reachable, cached when not. A
 * foreground service (the G2CC v0.7 lesson: backgrounded processes lose BLE
 * and network liveness, and the FB lease fails OPEN).
 *
 * The finishing build (HANDOFF.md §8.2 "Phone"):
 *  - the shell runs under a [ShellKeeper]: a link end restarts the session,
 *    forever, with no timeouts; a capability refusal (not the CFW) is terminal
 *    for the GLASSES target — the stack falls back to the simulator so the
 *    on-screen replica keeps working, and a persistent notification says so;
 *  - the transport SEAM SERVER lets a PC shell claim this transport ("both
 *    able to take over"): a claim PAUSES the keeper (blocking, so the remote
 *    start cannot overlap the local shell's stop) and a release RESUMES it;
 *  - the on-screen replica draws the transport's [mirror] — exact for both
 *    targets, and correct while a PC drives; touch enters through
 *    [Transport.injectInput] so it reaches whichever shell drives;
 *  - the display target (SIM / GLASSES) is switchable from the control strip
 *    and from a Settings row, persisted in [Prefs]; the default stays SIM
 *    until Adam flips it after flashing.
 */
class ShellService : Service() {

    inner class LocalBinder : Binder() {
        val service: ShellService get() = this@ShellService
    }

    private val binder = LocalBinder()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Bumps every time the stack rebuilds — the activity re-attaches its
     *  LensView when this changes (a stale view held a dead mirror before). */
    @Volatile var stackGeneration = 0
        private set

    /** What the phone screen draws: the running transport's mirror. */
    @Volatile var mirror: LensPanels? = null
        private set
    @Volatile var transport: Transport? = null
        private set
    @Volatile var shell: Shell? = null
        private set
    private var keeper: ShellKeeper? = null
    private var seamServer: RemoteTransportServer? = null
    private var replica: ReplicaServer? = null
    @Volatile var remoteDriving = false
        private set
    @Volatile var statusLine: String = "starting"
        private set

    /** The target the running stack was built for (may be SIM after a
     *  terminal refusal even though Prefs still say GLASSES). */
    @Volatile var runningTarget: Prefs.Target = Prefs.Target.SIM
        private set

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        // NO SILENT FAILURES needs a sink on this platform: everything reaches
        // logcat, and errors reach the person (§9.3), rate-limited per tag so
        // a repeating error is one notification, not a stream
        val sink = Log.Sink { level, tag, message ->
            val prio = when (level) {
                Log.Level.DEBUG -> android.util.Log.DEBUG
                Log.Level.INFO -> android.util.Log.INFO
                Log.Level.WARN -> android.util.Log.WARN
                Log.Level.ERROR -> android.util.Log.ERROR
            }
            android.util.Log.println(prio, "damage/$tag", message)
            if (level == Log.Level.ERROR) {
                // one notice per distinct error per 10 s: keyed on the message
                // with its numbers removed, so a repeat collapses but a
                // different error under the same tag still shows (round 3, b3-4)
                val key = tag + "|" + message.replace(Regex("[0-9]+"), "#")
                val now = System.currentTimeMillis()
                val last = errorShownAt[key] ?: 0L
                if (now - last > ERROR_NOTICE_GAP_MS) {
                    errorShownAt[key] = now
                    urgentNotification(tag, message)
                }
            }
        }
        logSink = sink
        Log.addSink(sink)      // removed at the END of this instance's shutdown (onDestroy)
        startForeground(NOTIF_ID, buildNotification("Damage shell starting"))
        startStack(Prefs(this).target)
    }

    private val errorShownAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var logSink: Log.Sink? = null

    /** The status the screen and the notification show: the keeper's last
     *  transition plus what the transport is doing right now ("scanning for
     *  the remembered pair", "connecting RIGHT", ...). */
    fun displayStatus(): String {
        val d = transport?.state?.value?.detail?.takeIf { it.isNotEmpty() }
        return if (d == null) statusLine else "$statusLine · $d"
    }

    private fun startStack(target: Prefs.Target) {
        val prefs = Prefs(this)
        val dataDir = filesDir.toPath()
        runningTarget = target

        val t: Transport = if (target == Prefs.Target.GLASSES) {
            // The capability gate inside the transport refuses firmware without
            // the EVENCFW string, so even a mistaken switch against stock
            // glasses reads settings and refuses to paint.
            BleTransport(this, scope,
                remembered = { BleTransport.Remembered(prefs.leftAddress, prefs.leftName, prefs.rightAddress, prefs.rightName) },
                remember = { r -> prefs.rememberPair(r) })
        } else {
            SimTransport(GlassFirmwareSim(), scope)
        }
        transport = t
        mirror = t.mirror

        var shellRef: Shell? = null
        val text = AndroidText(this) { shellRef?.settings?.fontScale ?: 1.0 }
        val persistence = Persistence(dataDir.resolve("state.json"))
        val sh = Shell(text, t, persistence, dataDir.resolve("journal.jsonl"), scope)
        shellRef = sh
        shell = sh

        val rc = RemoteContent(
            prefs.host, prefs.contentPort, prefs.token, dataDir.resolve("bookcache"),
            onState = { st -> sh.hostState = st },
            onNotice = { detail -> sh.services.notifyInternal("content", detail) },
        )
        sh.register(ReaderWindow(text, rc, scope))
        sh.onUrgent = { source, body -> urgentNotification(source, body) }
        sh.hostSettings = listOf(
            HostSetting("Target", listOf("sim", "glasses"),
                current = { if (prefs.target == Prefs.Target.GLASSES) "glasses" else "sim" },
                apply = { v -> switchTarget(if (v == "glasses") Prefs.Target.GLASSES else Prefs.Target.SIM) }),
        )

        val name = target.name.lowercase()
        val k = ShellKeeper(sh, t, scope,
            onStatus = { s ->
                statusLine = "$s · $name"
                updateNotification(statusLine)
            },
            onTerminal = { reason -> onTerminal(target, reason) })
        keeper = k
        stackGeneration++
        k.start()

        // the notification follows the transport's detail line
        scope.launch {
            var shown = ""
            while (isActive) {
                delay(2_000)
                val s = displayStatus()
                if (s != shown) { shown = s; updateNotification(s) }
            }
        }

        // battery into the top bar's P cell; the host-link banner re-read on
        // the same tick so "PC Nm" keeps counting
        scope.launch {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            while (isActive) {
                val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                if (pct in 1..100) sh.phoneBattery = Chrome.Battery(pct)
                sh.hostState = rc.state()
                delay(60_000)
            }
        }

        // the browser replica: the same mirror, from any browser on the tailnet
        try {
            val rs = ReplicaServer(prefs.replicaPort, prefs.token, { mirror }, { replicaStatus() }, { postGesture(it) })
            rs.start()
            replica = rs
        } catch (e: Exception) {
            Log.e("replica", "browser replica failed to start", e)   // the sink raises the notice
        }

        // the transport seam server: a PC shell can claim this transport
        if (prefs.seamServer) {
            try {
                val server = RemoteTransportServer(t, prefs.transportPort, prefs.token, scope,
                    onRemoteDriver = { driving -> onRemoteDriver(driving) })
                server.start()
                seamServer = server
            } catch (e: Exception) {
                Log.e("seam", "transport server failed to start", e)   // the sink raises the notice
            }
        }
    }

    /** True once onDestroy has run — a mid-flight rebuild must not resurrect
     *  the stack on a destroyed service. */
    @Volatile private var destroyed = false
    private val rebuilding = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Tear the whole stack down in order — server first (no new claims), then
     *  the keeper (stops the shell, saves state), then the scope. Synchronized:
     *  the shutdown thread, a rebuild and a takeover claim can all reach for
     *  the stack at once. */
    /** The status line the browser replica shows. */
    private fun replicaStatus(): ReplicaServer.Status {
        val st = transport?.state?.value
        return ReplicaServer.Status(
            transport = st?.transportName ?: "none",
            connected = st?.connected ?: false, started = st?.started ?: false, leaseHeld = st?.leaseHeld ?: false,
            ackMs = st?.ackMsEma?.toInt() ?: 0, bytesPerSec = st?.bytesPerSecEma?.toInt() ?: 0,
            driver = if (remoteDriving) "PC shell over the seam" else "phone shell",
            note = shell?.lastDivergence?.let { "DIVERGE: $it" } ?: displayStatus(),
        )
    }

    @Synchronized
    private fun stopStack() {
        // the keeper and shell are taken out FIRST: a seam session ending
        // under the server's close must not resume a keeper being stopped
        val k = keeper
        keeper = null
        shell = null
        replica?.close()
        replica = null
        seamServer?.close()
        seamServer = null
        if (k != null) {
            try {
                runBlocking { k.stop() }
            } catch (e: Exception) {
                Log.e("service", "keeper stop failed", e)
            }
        }
        scope.cancel()
        transport = null
        mirror = null
    }

    /** Rebuild the stack on [target] on a worker thread, one rebuild at a time. */
    /** A switch requested while a rebuild runs: applied when it finishes. */
    private val queuedTarget = java.util.concurrent.atomic.AtomicReference<Prefs.Target?>(null)

    private fun rebuild(target: Prefs.Target, why: String) {
        if (destroyed) return
        if (!rebuilding.compareAndSet(false, true)) {
            queuedTarget.set(target)
            Log.w("service", "rebuild already in progress — $why queued behind it")
            statusLine = "rebuilding… ($why queued)"
            updateNotification(statusLine)
            // the runner may have finished between our CAS and our set: drain
            // it ourselves in that case, so a queued switch is never lost
            if (!rebuilding.get()) queuedTarget.getAndSet(null)?.let { q -> if (q != runningTarget) rebuild(q, why) }
            return
        }
        statusLine = "rebuilding ($why)"
        updateNotification(statusLine)
        Thread({
            synchronized(this@ShellService) {
                try {
                    stopStack()
                    if (!destroyed) {
                        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                        startStack(target)
                    }
                } catch (e: Exception) {
                    statusLine = "REBUILD FAILED: ${e.message ?: e::class.simpleName}"
                    Log.e("service", statusLine)   // the sink raises the notice
                    updateNotification(statusLine)
                } finally {
                    rebuilding.set(false)
                }
            }
            // a switch that arrived meanwhile — for a target other than the
            // one now running — is applied now, not dropped
            queuedTarget.getAndSet(null)?.let { q -> if (q != runningTarget) rebuild(q, "queued switch") }
        }, "damage-stack-rebuild").start()
    }

    /** The display target changed (strip button or Settings row): persist and
     *  rebuild. Called on the shell loop or the UI thread — never blocks. */
    fun switchTarget(target: Prefs.Target) {
        Prefs(this).setTargetGlasses(target == Prefs.Target.GLASSES)
        val k = keeper
        // a failed build leaves no keeper: that target is NOT running, rebuild it
        if (target == runningTarget && k != null && k.state != ShellKeeper.State.TERMINAL) {
            Log.i("service", "target ${target.name} already running")
            return
        }
        rebuild(target, "target → ${target.name.lowercase()}")
    }

    /** The keeper gave up on this transport: for GLASSES that means the
     *  firmware refused the display (not the CFW). Fall back to the simulator
     *  so the phone screen keeps working, and say so persistently. */
    private fun onTerminal(target: Prefs.Target, reason: String) {
        statusLine = "${target.name.lowercase()} refused: $reason"
        updateNotification(statusLine)
        if (target == Prefs.Target.GLASSES) {
            urgentNotification("glasses",
                "The glasses refused the display: $reason. Showing the simulator instead; " +
                    "the Target setting still says glasses — restart the app after the firmware is right.")
            rebuild(Prefs.Target.SIM, "fallback after refusal")
        } else {
            urgentNotification("shell", "the simulator target ended: $reason")
        }
    }

    /**
     * A remote (PC) shell claimed or released our transport. Called on the seam
     * server's session thread and deliberately BLOCKING on the claim: the
     * server does not process the remote's "start" until the local shell has
     * fully stopped, so the two drivers can never overlap on the transport.
     */
    private fun onRemoteDriver(driving: Boolean) {
        remoteDriving = driving
        val k = keeper
        if (driving) {
            Log.i("service", "PC shell claimed the transport — local shell yielding")
            if (k != null) {
                try {
                    runBlocking { k.pause("PC shell driving") }
                } catch (e: Exception) {
                    Log.e("service", "local shell pause on takeover failed", e)
                }
            }
            statusLine = "PC shell driving"
            updateNotification(statusLine)
        } else {
            if (destroyed) return
            Log.i("service", "PC shell gone — local shell resuming")
            statusLine = "local shell resuming"
            updateNotification(statusLine)
            k?.resume()
        }
    }

    /** A gesture from the phone screen enters through the transport so it
     *  reaches whichever shell drives (the PC's during a takeover). */
    fun postGesture(type: Int) {
        transport?.injectInput(type) ?: Log.w("service", "gesture $type with no transport")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        // NEVER block the main thread on transport teardown (ANR): state saves
        // continuously (2 s debounce), so the async stop loses at most the last
        // moments — an ANR would lose the process mid-write instead.
        destroyed = true
        val sink = logSink
        logSink = null
        Thread({
            // the sink stays for the stop itself: a failure in the final save or
            // the disconnect must still reach the person (round 3, b3-1)
            try { stopStack() } catch (e: Exception) { Log.e("service", "shutdown failed", e) }
            finally { sink?.let { Log.removeSink(it) } }
        }, "damage-shutdown").start()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ notices
    private fun channels(): NotificationManager {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Damage", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_URGENT, "Damage errors", NotificationManager.IMPORTANCE_HIGH),
        )
        return nm
    }

    private fun buildNotification(text: String): Notification {
        channels()
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_damage)
            .setContentTitle("Damage")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        channels().notify(NOTIF_ID, buildNotification(text))
    }

    /** §9.3: serious display-path errors become PHONE notifications — the only
     *  alert path that works when the display itself is broken. */
    private fun urgentNotification(source: String, body: String) {
        channels().notify(
            (System.currentTimeMillis() and 0xFFFF).toInt(),
            NotificationCompat.Builder(this, CHANNEL_URGENT)
                .setSmallIcon(R.drawable.ic_damage)
                .setContentTitle("Damage: $source")
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build(),
        )
    }

    companion object {
        private const val NOTIF_ID = 1
        private const val CHANNEL = "damage"
        private const val CHANNEL_URGENT = "damage-urgent"
        private const val ERROR_NOTICE_GAP_MS = 10_000L
    }
}

/** Runtime configuration: baked BuildConfig defaults, overridable in prefs. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("damage", Context.MODE_PRIVATE)

    enum class Target { SIM, GLASSES }

    val host: String get() = p.getString("host", BuildConfig.SERVER_HOST)!!
    val token: String get() = p.getString("token", BuildConfig.DAMAGE_TOKEN)!!
    val contentPort: Int get() = p.getInt("contentPort", BuildConfig.CONTENT_PORT)
    val transportPort: Int get() = p.getInt("transportPort", BuildConfig.TRANSPORT_PORT)
    val replicaPort: Int get() = p.getInt("replicaPort", BuildConfig.REPLICA_PORT)

    /** SIM until flash day. GLASSES is the real BLE path. */
    val target: Target
        get() = if (p.getBoolean("targetGlasses", false)) Target.GLASSES else Target.SIM

    /** Serve the transport seam so a PC shell can take over. Default on. */
    val seamServer: Boolean get() = p.getBoolean("seamServer", true)

    /** The pair's addresses and advertised names from the last successful
     *  connect: the scan filter (works with the screen off), and accepted
     *  next to the advertised-name match. */
    val leftAddress: String? get() = p.getString("leftAddr", null)
    val rightAddress: String? get() = p.getString("rightAddr", null)
    val leftName: String? get() = p.getString("leftName", null)
    val rightName: String? get() = p.getString("rightName", null)

    fun rememberPair(r: BleTransport.Remembered) = p.edit()
        .putString("leftAddr", r.leftAddress).putString("rightAddr", r.rightAddress)
        .putString("leftName", r.leftName).putString("rightName", r.rightName).apply()

    fun setTargetGlasses(on: Boolean) = p.edit().putBoolean("targetGlasses", on).apply()
    fun set(key: String, value: String) = p.edit().putString(key, value).apply()
}
