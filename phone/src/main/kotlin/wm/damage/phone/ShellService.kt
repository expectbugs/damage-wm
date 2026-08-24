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
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
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
 * Also runs the transport SEAM SERVER, so a PC-resident shell can claim this
 * phone's transport ("both able to take over"): while a remote shell drives,
 * the local shell yields the glasses — the claim callback BLOCKS on that stop,
 * so the remote's start cannot race it — and when the remote disconnects the
 * whole stack rebuilds cleanly (server included; a leaked server on a dead
 * scope was review round 1's takeover wedge). Until flash day the transport is
 * the byte-exact sim, displayed by LensView; the banked BLE transport switches
 * in via Settings once the glasses run the CFW.
 */
class ShellService : Service() {

    inner class LocalBinder : Binder() {
        val service: ShellService get() = this@ShellService
    }

    private val binder = LocalBinder()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Bumps every time the stack rebuilds — the activity re-attaches its
     *  LensView when this changes (a stale view held the DEAD sim before). */
    @Volatile var stackGeneration = 0
        private set
    var sim: GlassFirmwareSim? = null
        private set
    var shell: Shell? = null
        private set
    private var transport: Transport? = null
    private var seamServer: RemoteTransportServer? = null
    @Volatile var remoteDriving = false
        private set
    @Volatile var statusLine: String = "starting"
        private set

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification("Damage shell starting"))
        startStack()
    }

    private fun startStack() {
        val prefs = Prefs(this)
        val dataDir = filesDir.toPath()

        val s = GlassFirmwareSim()
        sim = s
        val t: Transport = if (prefs.target == Prefs.Target.GLASSES) {
            // The banked path. The capability gate inside the transport refuses
            // firmware without the EVENCFW string, so even a mistaken toggle
            // against stock glasses reads settings and refuses to paint.
            BleTransport(this, scope)
        } else {
            SimTransport(s, scope)
        }
        transport = t

        var shellRef: Shell? = null
        val text = AndroidText(this) { shellRef?.settings?.fontScale ?: 1.0 }
        val persistence = Persistence(dataDir.resolve("state.json"))
        val sh = Shell(text, t, persistence, dataDir.resolve("journal.jsonl"), scope)
        shellRef = sh
        shell = sh

        val rc = RemoteContent(
            prefs.host, prefs.contentPort, prefs.token, dataDir.resolve("bookcache"),
            onState = { st -> sh.hostState = st },
        )
        sh.register(ReaderWindow(text, rc, scope))
        sh.onUrgent = { source, body -> urgentNotification(source, body) }

        stackGeneration++

        scope.launch {
            try {
                sh.start()
                statusLine = "shell up (${if (prefs.target == Prefs.Target.GLASSES) "GLASSES" else "sim"})"
                updateNotification(statusLine)
            } catch (e: Exception) {
                Log.e("service", "shell start failed", e)
                statusLine = "START FAILED: ${e.message}"
                urgentNotification("shell", statusLine)
                updateNotification(statusLine)
            }
        }

        // battery into the top bar's P cell; the host-link banner re-read on
        // the same tick so "PC Nm" keeps counting (round 3, content R2)
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
     *  the stack on a destroyed service (review round 2 #B4). */
    @Volatile private var destroyed = false

    /** Tear the whole stack down in order — server first (no new claims), then
     *  the shell (saves state), then the scope. Synchronized: the shutdown
     *  thread, the rebuild thread and a takeover claim can all reach for the
     *  stack at once (#B4). */
    @Synchronized
    private fun stopStack() {
        seamServer?.close()
        seamServer = null
        val sh = shell
        shell = null
        if (sh != null) {
            try {
                runBlocking { sh.stop() }
            } catch (e: Exception) {
                Log.e("service", "shell stop failed", e)
            }
        }
        scope.cancel()
        transport = null
        sim = null
    }

    /**
     * A remote (PC) shell claimed or released our transport. Called on the seam
     * server's session thread and deliberately BLOCKING: the server does not
     * process the remote's "start" until the local shell has fully stopped, so
     * the two drivers can never overlap on the transport.
     */
    private fun onRemoteDriver(driving: Boolean) {
        remoteDriving = driving
        if (driving) {
            Log.i("service", "PC shell claimed the transport — local shell yielding")
            statusLine = "PC shell driving"
            synchronized(this) {
                val sh = shell
                shell = null
                if (sh != null) {
                    try {
                        runBlocking { sh.stop() }
                    } catch (e: Exception) {
                        Log.e("service", "local shell stop on takeover failed", e)
                    }
                }
            }
            updateNotification(statusLine)
        } else {
            if (destroyed) return
            // one rebuild at a time (round 3, phone D4): a burst of
            // disconnects must not tear down the fresh stack it just built.
            // The flag clears INSIDE the monitor, so a claim that was waiting
            // on it can never see a stale "in progress".
            if (!rebuilding.compareAndSet(false, true)) {
                Log.i("service", "rebuild already in progress — coalesced")
                return
            }
            Log.i("service", "PC shell gone — rebuilding the local stack")
            statusLine = "local shell resuming"
            updateNotification(statusLine)
            // full rebuild: fresh scope, transport, sim, shell AND seam server —
            // partial reuse left the old server on a cancelled scope. The lock
            // makes stop+start atomic against onDestroy's teardown (#B4).
            Thread({
                synchronized(this@ShellService) {
                    try {
                        stopStack()
                        if (!destroyed) {
                            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                            startStack()
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
    }

    private val rebuilding = java.util.concurrent.atomic.AtomicBoolean(false)

    fun postGesture(type: Int) {
        shell?.postGesture(type)
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

    /** SIM until flash day. The GLASSES target is the banked BLE path. */
    val target: Target
        get() = if (p.getBoolean("targetGlasses", false)) Target.GLASSES else Target.SIM

    /** Serve the transport seam so a PC shell can take over. Default on. */
    val seamServer: Boolean get() = p.getBoolean("seamServer", true)

    fun setTargetGlasses(on: Boolean) = p.edit().putBoolean("targetGlasses", on).apply()
    fun set(key: String, value: String) = p.edit().putString(key, value).apply()
}
