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
import java.nio.file.Path
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
 * the local shell yields the glasses; when it disconnects the local shell
 * resumes. Until flash day the transport is the byte-exact sim, displayed by
 * LensView; the banked BLE transport switches in via Settings when the glasses
 * actually run the CFW.
 */
class ShellService : Service() {

    inner class LocalBinder : Binder() {
        val service: ShellService get() = this@ShellService
    }

    private val binder = LocalBinder()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var sim: GlassFirmwareSim? = null
        private set
    var shell: Shell? = null
        private set
    private var transport: Transport? = null
    private var seamServer: RemoteTransportServer? = null
    private var content: RemoteContent? = null
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
        val cacheBooks = dataDir.resolve("bookcache")

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
            prefs.host, prefs.contentPort, prefs.token, cacheBooks,
            onState = { st -> sh.hostState = st },
        )
        content = rc
        sh.register(ReaderWindow(text, rc, scope))
        sh.onUrgent = { source, body -> urgentNotification(source, body) }

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

        // battery into the top bar's P cell
        scope.launch {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            while (isActive) {
                val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                if (pct in 1..100) sh.phoneBattery = Chrome.Battery(pct)
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

    /** A remote (PC) shell claimed or released our transport. Yield the local
     *  shell while it drives — state is saved so the takeover loses nothing —
     *  and take back over when it goes. */
    private fun onRemoteDriver(driving: Boolean) {
        remoteDriving = driving
        val sh = shell ?: return
        scope.launch {
            if (driving) {
                Log.i("service", "PC shell claimed the transport — local shell yielding")
                statusLine = "PC shell driving"
                sh.stop()
                shell = null
                updateNotification(statusLine)
            } else {
                Log.i("service", "PC shell gone — local shell taking back over")
                statusLine = "local shell resuming"
                updateNotification(statusLine)
                restartLocalShell()
            }
        }
    }

    private suspend fun restartLocalShell() {
        // the transport was stopped by the remote driver's session; rebuild the
        // local stack cleanly (fresh transport session, restored persistence)
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        startStack()
    }

    fun postGesture(type: Int) {
        shell?.postGesture(type)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        runBlocking {
            try {
                shell?.stop()
            } catch (e: Exception) {
                Log.e("service", "stop failed", e)
            }
        }
        seamServer?.close()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ notices
    private fun channel(): String {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Damage", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_URGENT, "Damage errors", NotificationManager.IMPORTANCE_HIGH),
        )
        return CHANNEL
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, channel())
            .setSmallIcon(R.drawable.ic_damage)
            .setContentTitle("Damage")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    /** §9.3: serious display-path errors become PHONE notifications — the only
     *  alert path that works when the display itself is broken. */
    private fun urgentNotification(source: String, body: String) {
        channel()
        getSystemService(NotificationManager::class.java).notify(
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
