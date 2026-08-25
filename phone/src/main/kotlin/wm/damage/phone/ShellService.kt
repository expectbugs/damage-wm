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
        startForeground(NOTIF_ID, buildNotification("Damage shell starting"))
        startStack(Prefs(this).target)
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
                cachedAddresses = { prefs.leftAddress to prefs.rightAddress },
                rememberAddresses = { l, r -> prefs.rememberPair(l, r) })
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

        // the transport seam server: a PC shell can claim this transport
        if (prefs.seamServer) {
            try {
                val server = RemoteTransportServer(t, prefs.transportPort, prefs.token, scope,
                    onRemoteDriver = { driving -> onRemoteDriver(driving) })
                server.start()
                seamServer = server
            } catch (e: Exception) {
                Log.e("service", "transport seam server failed to start", e)
                urgentNotification("seam", "transport server: ${e.message}")
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
    @Synchronized
    private fun stopStack() {
        seamServer?.close()
        seamServer = null
        val k = keeper
        keeper = null
        shell = null
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
    private fun rebuild(target: Prefs.Target, why: String) {
        if (destroyed) return
        if (!rebuilding.compareAndSet(false, true)) {
            Log.i("service", "rebuild already in progress — $why coalesced")
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
                    Log.e("service", "stack rebuild failed", e)
                    statusLine = "REBUILD FAILED: ${e.message}"
                    urgentNotification("service", statusLine)
                } finally {
                    rebuilding.set(false)
                }
            }
        }, "damage-stack-rebuild").start()
    }

    /** The display target changed (strip button or Settings row): persist and
     *  rebuild. Called on the shell loop or the UI thread — never blocks. */
    fun switchTarget(target: Prefs.Target) {
        Prefs(this).setTargetGlasses(target == Prefs.Target.GLASSES)
        if (target == runningTarget && keeper?.state != ShellKeeper.State.TERMINAL) {
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
        Thread({
            try { stopStack() } catch (e: Exception) { Log.e("service", "shutdown failed", e) }
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

    /** The pair's addresses from the last successful connect: the scanner
     *  accepts them next to the advertised-name match. */
    val leftAddress: String? get() = p.getString("leftAddr", null)
    val rightAddress: String? get() = p.getString("rightAddr", null)

    fun rememberPair(left: String, right: String) =
        p.edit().putString("leftAddr", left).putString("rightAddr", right).apply()

    fun setTargetGlasses(on: Boolean) = p.edit().putBoolean("targetGlasses", on).apply()
    fun set(key: String, value: String) = p.edit().putString(key, value).apply()
}
