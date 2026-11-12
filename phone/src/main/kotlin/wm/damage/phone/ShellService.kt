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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.comp.Journal
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
import wm.damage.core.util.UncaughtRecord
import wm.damage.core.windows.reader.ReaderWindow
import wm.damage.core.windows.tmux.RemoteTmuxProvider
import wm.damage.core.windows.tmux.TmuxWindow

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
     *  LensView when this changes (a stale view held a stale mirror before). */
    @Volatile var stackGeneration = 0
        private set

    /** What the phone screen draws: the running transport's mirror. */
    @Volatile var mirror: LensPanels? = null
        private set
    @Volatile var transport: Transport? = null
        private set
    @Volatile var shell: Shell? = null
        private set
    @Volatile private var keeper: ShellKeeper? = null     // read by isRunning() off the rebuild thread
    private var seamServer: RemoteTransportServer? = null
    private var replica: ReplicaServer? = null
    private var tmuxProvider: RemoteTmuxProvider? = null
    private var filesProvider: wm.damage.core.windows.files.RemoteFilesProvider? = null
    private var torrentsProvider: wm.damage.core.windows.torrents.RemoteTorrentsProvider? = null
    private var feedProvider: wm.damage.core.windows.feed.FeedProvider? = null
    private var musicLibrary: wm.damage.core.windows.music.RemoteMusicLibrary? = null
    private var musicPlayer: wm.damage.phone.music.AndroidMusicPlayer? = null
    private var remoteIcons: RemoteIcons? = null
    private var remoteSync: wm.damage.core.sync.RemoteSync? = null
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
                // different error under the same tag still shows (round 3,
                // b3-4); entries older than the gap are dropped and a tag gets
                // at most ERROR_NOTICES_PER_TAG notices per gap — a burst of
                // distinct texts (paths, ids) stays in logcat (round 4, R4-6).
                // Only notices actually shown count toward the cap, so a
                // sustained burst frees a slot as each shown one ages out and
                // a new error is never hidden for the burst's whole length
                // (round 5, R5-5)
                val key = tag + "|" + message.replace(Regex("[0-9]+"), "#")
                val now = System.currentTimeMillis()
                errorsSeen.entries.removeIf { now - it.value.at > ERROR_NOTICE_GAP_MS }
                if (!errorsSeen.containsKey(key)) {
                    val shownForTag = errorsSeen.entries.count { it.key.startsWith("$tag|") && it.value.notified }
                    val notify = shownForTag < ERROR_NOTICES_PER_TAG
                    if (errorsSeen.putIfAbsent(key, ErrorSeen(now, notify)) == null) {
                        if (notify) errorNotice(tag, message)
                        else android.util.Log.w("damage/service",
                            "error notices for '$tag' capped at $ERROR_NOTICES_PER_TAG per ${ERROR_NOTICE_GAP_MS / 1000} s — the rest are in logcat")
                    }
                }
            }
        }
        logSink = sink
        Log.addSink(sink)      // removed at the END of this instance's shutdown (onDestroy)
        // the connectedDevice type ONLY at start: Android 15 refuses a service
        // started from BOOT_COMPLETED whose types include mediaPlayback; that
        // type is added when playback engages (review 2026-09-02)
        startForeground(NOTIF_ID, buildNotification("Damage shell starting"),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        // APK 0.64 (`HANDOFF.md` §75.1). The order is the design, and `tools/check_apk_wiring.py`
        // reads it: after the sink (a rotation that fails raises its notice), before the first
        // Shell and the replica (no writer and no fetch exists yet in this process) —
        //   1. the journal past 64 MiB is set aside; nothing is deleted
        //   2. whether the uncaught-error record is in place, said at every start
        //   3. the last run's record is journaled and renamed BEFORE the stack is built, so a
        //      start that fails again still leaves the earlier record said
        //   4. Android's own account of why earlier processes ended (memory, a native fault, a
        //      stall and a force stop raise no throwable: only this sees them)
        //   5. the stack; and only then the last run's line, so that it takes the existing error
        //      path (verdict 21): the phone when `Phone notifications` is on — and a `process`
        //      fault on the glasses through the running shell, whatever `Notify · Damage` says
        //      (0.65 P7 item 2; with that row off it was a log line and a journal note).
        //   R2 (review 1b RF-9; insurance — each step catches its own Exception and no throw is
        //   known): steps 1-4 sit in one catch of anything but a VM error, said at ERROR (the sink
        //   above raises the phone's error notice), and the stack starts all the same — a fault
        //   here would otherwise end every launch with nothing readable.
        val dir = filesDir.toPath()
        val journalFile = dir.resolve("journal.jsonl")
        var lastRun: String? = null
        try {
            Journal.rotateAtStart(journalFile)
            // the start's `process` note carries the archives' count and total (R2 item 26), in both arms
            val archives = Journal.archivesSaid(journalFile)
            val installed = UncaughtRecord.installedLine()
            if (installed != null) {
                Log.i("uncaught", installed)
                Journal.appendNote(journalFile, "process", "$installed; ${journalFile.fileName}: $archives")
            } else {
                val why = DamageApp.installFailure?.let { "its install failed: $it" } ?: "DamageApp did not run"
                val line = "the uncaught-error record is NOT installed in this process ($why) — an uncaught error would leave no record"
                Log.e("uncaught", line)
                Journal.appendNote(journalFile, "process", "$line; ${journalFile.fileName}: $archives")
            }
            lastRun = UncaughtRecord.takePending(dir, journalFile)
            journalEarlierExits(journalFile)
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            Log.e("process", "a start step before the stack did not finish (the journal's rotation, the record's " +
                "line, the last run's pickup or the earlier exits) — the stack starts all the same", t)
        }
        try {
            startStack(Prefs(this).target)
        } finally {
            // 0.65 P7 item 2: logged as before (the sink's path: the phone's notification when `Phone notifications`
            // is on, else the WM's courtesy notice), then raised on the glasses as a `process` fault — shown whatever
            // `Notify · Damage` says, like the shell's other failure reports. No shell (a stack that did not build):
            // the log line's phone notification is the one channel, as before.
            lastRun?.let { Log.e("uncaught", it); shell?.raiseHostFault("process", it) }
        }
    }

    /**
     * Android's own record of why this app's earlier processes ended, journaled once each as a
     * `process` note (APK 0.64). The uncaught-error record sees a throwable; the system ending the
     * process for memory, a native fault, a stall or a force stop raises none, and on 2026-11-04
     * nothing could say which of those it had been (`HANDOFF.md` §71). Read-only, no permission.
     * `lastExitSeen` (a preference) is the newest entry already journaled; it moves only past an
     * entry whose note landed, so one that could not be written is journaled by the next start.
     */
    private fun journalEarlierExits(journalFile: java.nio.file.Path) {
        try {
            val am = getSystemService(android.app.ActivityManager::class.java)
            val prefs = getSharedPreferences("damage", Context.MODE_PRIVATE)
            val seen = prefs.getLong("lastExitSeen", 0L)
            val stamp = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
            var newest = seen
            for (x in am.getHistoricalProcessExitReasons(packageName, 0, 5).filter { it.timestamp > seen }.sortedBy { it.timestamp }) {
                val at = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(x.timestamp), java.time.ZoneId.systemDefault())
                val line = ("an earlier process ended: ${exitReason(x.reason)} at ${at.format(stamp)} " +
                    "(status ${x.status}, importance ${x.importance}, rss ${x.rss} kB) ${x.description ?: ""}").trimEnd()
                Log.i("process", line)
                if (!Journal.appendNote(journalFile, "process", line)) break
                newest = x.timestamp
            }
            if (newest != seen) prefs.edit().putLong("lastExitSeen", newest).apply()
        } catch (e: Exception) {
            Log.w("process", "Android's record of earlier process ends could not be read: ${e::class.simpleName}: ${e.message}")
        }
    }

    /** `ApplicationExitInfo`'s reason by the SDK's own constant names. */
    private fun exitReason(n: Int): String = when (n) {
        android.app.ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        android.app.ApplicationExitInfo.REASON_OTHER -> "OTHER"
        android.app.ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        android.app.ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "reason $n"
    }

    /** An error text seen within the gap: when, and whether it raised a notice. */
    private class ErrorSeen(val at: Long, val notified: Boolean)
    private val errorsSeen = java.util.concurrent.ConcurrentHashMap<String, ErrorSeen>()
    private var logSink: Log.Sink? = null

    /** The status the screen and the notification show: the keeper's last
     *  transition plus what the transport is doing right now ("scanning for
     *  the remembered pair", "connecting RIGHT", ...). */
    fun displayStatus(): String {
        val d = transport?.state?.value?.detail?.takeIf { it.isNotEmpty() }
        return if (d == null) statusLine else "$statusLine · $d"
    }

    /** PARTIAL_WAKE_LOCK while a GLASSES stack runs (whoever the driver is —
     *  the lease renewal is OURS even when a PC shell drives over the seam).
     *  The FGS type stops process termination but NOT Doze CPU throttling: G2CC
     *  measured delay() ticks gapping 13-28 s on a 10 s cadence without one
     *  (their ConnectionService, the factory finding), and the FB lease
     *  renews every 45 s against a 90 s fail-open expiry — a throttled
     *  renewal loop hands the screen back to stock LVGL in a pocket. */
    private var wakeLock: PowerManager.WakeLock? = null

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "damage:transport")
                ?.apply { setReferenceCounted(false); acquire() }
            Log.i("service", "wake lock acquired (glasses stack): held=${wakeLock?.isHeld}")
        } catch (e: Exception) {
            Log.e("service", "wake lock acquire failed — Doze may throttle the lease renewals", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w("service", "wake lock release: ${e.message}")
        }
        wakeLock = null
    }

    private fun startStack(target: Prefs.Target) {
        val prefs = Prefs(this)
        val dataDir = filesDir.toPath()
        runningTarget = target
        if (target == Prefs.Target.GLASSES) acquireWakeLock()

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

        // content scaling moved INTO the style transforms (Style.kt, 2026-08-31)
        val text = wm.damage.core.comp.CachedText(AndroidText(this))   // §40: the texture-cache recorder
        val persistence = Persistence(dataDir.resolve("state.json"))
        // LOAD BEFORE ANY SYNC WRITER EXISTS (review 2026-09-01 F1): a sync
        // record applied to an unloaded store saves a map holding ONLY that
        // record, replacing state.json and discarding everything local —
        // including shell.state, which never syncs back. The shell's own
        // load() later merges harmlessly (strictly-newer in-memory wins).
        persistence.load()
        val sh = Shell(text, t, persistence, dataDir.resolve("journal.jsonl"), scope)
        sh.buildTag = "apk ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        shell = sh

        // §72: the shelf answers from its cache at once; a background refresh that finds a
        // changed listing asks the Reader to scan again (late-bound: the Reader takes `rc`)
        var onLibraryChanged: () -> Unit = {}
        val rc = RemoteContent(
            prefs.host, prefs.contentPort, prefs.token, dataDir.resolve("bookcache"),
            onState = { st -> sh.hostState = st },
            onNotice = { detail -> sh.services.notifyInternal("content", detail) },
            onLibraryChanged = { onLibraryChanged() },
        )
        val reader = ReaderWindow(text, rc, scope, AndroidImages())
        onLibraryChanged = { reader.libraryChanged() }
        sh.register(reader)
        // State sync (HANDOFF.md §19.2): the PC pushes/receives stamped state
        // records over the content port, most-recent wins. Records reach the
        // live shell (freshen → LWW → live-apply) or, when it is not running,
        // the store directly.
        remoteSync = wm.damage.core.sync.RemoteSync(prefs.host, prefs.contentPort, prefs.token, scope,
            wm.damage.core.sync.SyncPeer(persistence, applier = { k, v, t ->
                val liveShell = shell
                if (liveShell == null || !liveShell.postSync(k, v, t)) persistence.tryApplyRemote(k, v, t)
            }))
        // Tmux (TMUX.md): the provider lives on the content host — same
        // host/port/token as the library, so the sessions just appear (§3.1)
        val tp = RemoteTmuxProvider(prefs.host, prefs.contentPort, prefs.token, scope)
        tmuxProvider = tp
        sh.register(TmuxWindow(text, tp, scope))
        // Files (2026-09-01, the first §16.10 window-channel consumer): the
        // PC's filesystem browsed from the phone; theme icons fetched and
        // cached the same way. App-alone the window is honestly unavailable
        // (a filesystem does not cache) — the staleness line says so.
        val fp = wm.damage.core.windows.files.RemoteFilesProvider(
            prefs.host, prefs.contentPort, prefs.token, scope,
            cacheDir = dataDir.resolve("wincache/files"))          // §47: the last answers, at once
        filesProvider = fp
        sh.register(wm.damage.core.windows.files.FilesWindow(text, fp, scope, AndroidImages()))
        // Torrents (2026-09-01, TORRENTS.md): qBittorrent + TorrentLeech live on
        // the PC; the phone polls the host through the window channel at its
        // own pacing (2 s focused / 15 s idle) and shows the host's own
        // staleness when qBittorrent is down. App-alone: honestly unavailable.
        val torp = wm.damage.core.windows.torrents.RemoteTorrentsProvider(
            prefs.host, prefs.contentPort, prefs.token, scope,
            cacheDir = dataDir.resolve("wincache/torrents"))       // §47: the last snapshot, at once
        torrentsProvider = torp
        sh.register(wm.damage.core.windows.torrents.TorrentsWindow(text, torp, scope))
        // Music (2026-09-02, MUSIC.md): the library rides the window channel
        // with its catalog/art/viz/lyrics cached on disk; the PLAYER is here —
        // ExoPlayer + the media session the earbuds' taps route through
        val musicDir = dataDir.resolve("music")
        val mp = arrayOfNulls<wm.damage.phone.music.AndroidMusicPlayer>(1)
        val ml = wm.damage.core.windows.music.RemoteMusicLibrary(prefs.host, prefs.contentPort, prefs.token, prefs.mediaPort, musicDir, scope,
            onState = { line -> mp[0]?.onPcLinkLine(line) })
        musicLibrary = ml
        val player = wm.damage.phone.music.AndroidMusicPlayer(this, ml, scope, onEngaged = { promoteToMediaPlayback() })
        mp[0] = player
        musicPlayer = player
        sh.register(wm.damage.core.windows.music.MusicWindow(text, ml, player, scope))
        // Games is pure Kotlin — no provider, no channel, no `needs`. Its whole
        // world lives in its own synced records (HOLDEM.md §11.1).
        sh.register(wm.damage.core.windows.games.GamesWindow(text, scope))
        // Feed (2026-09-09, FEED.md §3.6, verdict 15): the PC's engine over the
        // window channel while it is reachable; the phone's OWN engine — the
        // same class, parked until needed — takes over after the PC-loss
        // threshold, and hands back only through the root menu's Back to PC row.
        // Every source is plain HTTP, so the fallback is complete; the phone
        // extracts articles on demand instead of ahead (battery).
        val feedLocal = wm.damage.core.windows.feed.FeedEngine(
            wm.damage.core.windows.feed.SourceCfg.DEFAULTS,
            wm.damage.core.windows.feed.FeedStore(dataDir.resolve("feed")),
            wm.damage.core.windows.feed.PacedHttp(wm.damage.core.windows.feed.RealFeedHttp("damage-wm/0.1 (personal glasses client)")),
            AndroidImages(), scope, prefetchArticles = false, engineLabel = "phone")
        feedLocal.pause(true)
        val feedRemote = wm.damage.core.windows.feed.RemoteFeedProvider(prefs.host, prefs.contentPort, prefs.token, scope)
        val feedSw = wm.damage.core.windows.feed.SwitchingFeedProvider(feedRemote, feedLocal, scope,
            onLocalActive = { on -> feedLocal.pause(!on) })
        feedProvider = feedSw
        sh.register(wm.damage.core.windows.feed.FeedWindow(text, feedSw, scope))
        val ri = RemoteIcons(prefs.host, prefs.contentPort, prefs.token,
            dataDir.resolve("icons"), scope, onLoaded = { sh.requestRepaint() })
        remoteIcons = ri
        sh.iconSource = ri
        // verdict 21: urgent internal notices reach the phone ONLY when the
        // Global "Phone notifications" switch is on; the shell's own box
        // already shows them on glass
        sh.onUrgent = { source, body -> if (sh.settings.phoneNotifications) urgentNotification(source, body) }
        // 0.64 (`HANDOFF.md` §75.4 calls 1-3, `DESIGN.md` §9.3): low glasses battery, once per discharge, on a
        // channel of its own — beside `onUrgent` and NOT through it: `Phone notifications` does not gate
        // this one (Android's own settings for the "Glasses battery" channel do). Withdrawn when the
        // shell's latch opens again (a charging reading, or 25 % and more). Called on the shell's loop
        // or, with no session running, on its event collector — `NotificationManager` is safe on either.
        // A new shell's latch starts open, so nothing on the phone is its: a notification left by the
        // stack before this one (a target switch, a process that ended while it was showing) has no
        // latch that could ever withdraw it. This shell says it again at its first low reading.
        channels().cancel(BATTERY_NOTIF_ID)
        sh.onGlassesBattery = { low, pct ->
            // R2 (RG-3): the answer is whether the phone showed it; a withdrawal's answer is not read
            if (low) batteryNotification(pct, simulated = target != Prefs.Target.GLASSES)
            else { channels().cancel(BATTERY_NOTIF_ID); true }
        }
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
            val rs = ReplicaServer(prefs.replicaPort, prefs.token, { mirror }, { replicaStatus() },
                onInput = { postGesture(it) }, onText = { postText(it) },
                // 0.64: a probe is offered to the SHELL first (`rest=on|off` is its own, `Shell.devProbe`),
                // then to the transport
                onProbe = { n, v ->
                    if (shell?.devProbe(n, v) != true)
                        transport?.devProbe(n, v) ?: Log.w("service", "probe $n=$v with no transport")
                },
                journalPath = { filesDir.toPath().resolve("journal.jsonl") })
            rs.start()
            replica = rs
        } catch (e: Exception) {
            Log.e("replica", "browser replica failed to start", e)   // the sink raises the notice
        }

        // the transport seam server: answers the PC's standby probe (§19.2 —
        // "does the APK want the radio?"), and still accepts an EXPLICIT
        // remote claim (`--transport remote`, the deliberate dev override)
        if (prefs.seamServer) {
            try {
                val server = RemoteTransportServer(t, prefs.transportPort, prefs.token, scope,
                    onRemoteDriver = { driving -> onRemoteDriver(driving) },
                    statusFor = {
                        wm.damage.core.transport.SeamStatus(
                            wantsRadio = Prefs(this).target == Prefs.Target.GLASSES && !destroyed,
                            driving = transport?.state?.value?.started == true,
                            line = displayStatus())
                    })
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
        // THIS host owns the radio session ("the session outlives the
        // driver", 2026-08-31): remote drivers only ever release their claim,
        // and a PAUSED shell's stop() skips the transport — so the stack
        // teardown stops it explicitly, exactly once, here. Idempotent when
        // the keeper's stop already did it.
        try {
            transport?.let { runBlocking { it.stop() } }
        } catch (e: Exception) {
            Log.w("service", "transport stop at stack teardown: ${e.message}")
        }
        try { tmuxProvider?.close() } catch (e: Exception) { Log.w("service", "tmux provider close: ${e.message}") }
        tmuxProvider = null
        try { filesProvider?.close() } catch (e: Exception) { Log.w("service", "files provider close: ${e.message}") }
        try { torrentsProvider?.close() } catch (e: Exception) { Log.w("service", "torrents provider close: ${e.message}") }
        try { feedProvider?.close() } catch (e: Exception) { Log.w("service", "feed provider close: ${e.message}") }
        feedProvider = null
        torrentsProvider = null
        try { musicPlayer?.close() } catch (e: Exception) { Log.w("service", "music player close: ${e.message}") }
        musicPlayer = null
        try { musicLibrary?.close() } catch (e: Exception) { Log.w("service", "music library close: ${e.message}") }
        musicLibrary = null
        filesProvider = null
        try { remoteIcons?.close() } catch (e: Exception) { Log.w("service", "icons close: ${e.message}") }
        remoteIcons = null
        try { remoteSync?.close() } catch (e: Exception) { Log.w("service", "sync close: ${e.message}") }
        remoteSync = null
        scope.cancel()
        transport = null
        mirror = null
        releaseWakeLock()
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
            if (!rebuilding.get()) queuedTarget.getAndSet(null)?.let { q -> if (!isRunning(q)) rebuild(q, why) }
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
                    // startStack acquires the wake lock FIRST — a failed build
                    // must not hold CPU forever while driving nothing (R3#4);
                    // the next rebuild trigger (a switch, BT, boot) re-acquires
                    releaseWakeLock()
                } finally {
                    rebuilding.set(false)
                }
            }
            // a switch that arrived meanwhile — for a target that is not up
            // now (a failed build counts as not up) — is applied, not dropped
            queuedTarget.getAndSet(null)?.let { q -> if (!isRunning(q)) rebuild(q, "queued switch") }
        }, "damage-stack-rebuild").start()
    }

    /** Is [target] the stack that is up now? A failed build leaves no keeper
     *  and a refused transport leaves a terminal one: neither counts, so a
     *  switch to that target rebuilds it (round 3 b3-2, round 4 R4-5). */
    private fun isRunning(target: Prefs.Target): Boolean {
        val k = keeper
        return target == runningTarget && k != null && k.state != ShellKeeper.State.TERMINAL
    }

    /** The display target changed (strip button or Settings row): persist and
     *  rebuild. Called on the shell loop or the UI thread — never blocks. */
    fun switchTarget(target: Prefs.Target) {
        Prefs(this).setTargetGlasses(target == Prefs.Target.GLASSES)
        if (isRunning(target)) {
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

    /** A typed line from the strip or the browser page — same routing rule as
     *  gestures: through the transport, to whichever shell drives. */
    fun postText(line: String) {
        transport?.injectText(line) ?: Log.w("service", "typed line with no transport")
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
        // 0.64: the glasses' low battery. DEFAULT importance (the lead's ruling on the P4 review's
        // finding 18): one sound, no heads-up over whatever the phone is showing; the channel's own
        // page in Android's settings is where that is changed.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BATTERY, "Glasses battery", NotificationManager.IMPORTANCE_DEFAULT),
        )
        return nm
    }

    /** The low-battery latch closed at [pct] (`Shell.onGlassesBattery`). One fixed id, so a later one
     *  replaces it and the latch opening again can withdraw it. A phone that will not show it — the
     *  app's notifications off, or this channel switched off — is said in the log, and the answer
     *  (false) makes the shell's `lowbatt` note say so (R2, RG-3; it used to claim the phone beside a
     *  journal line of ours that said the opposite): `notify()` does nothing there, with no error. */
    private fun batteryNotification(pct: Int, simulated: Boolean): Boolean {
        val nm = channels()
        val channelOff = nm.getNotificationChannel(CHANNEL_BATTERY)?.importance == NotificationManager.IMPORTANCE_NONE
        val shown = !channelOff && NotificationManagerCompat.from(this).areNotificationsEnabled()
        if (!shown)
            Log.w("service", "glasses battery $pct%: the phone will not show it — notifications are off for Damage or for its Glasses battery channel")
        val text = "Below 20% and not charging. Said once per discharge."
        nm.notify(BATTERY_NOTIF_ID,
            NotificationCompat.Builder(this, CHANNEL_BATTERY)
                .setSmallIcon(R.drawable.ic_damage)
                .setContentTitle("Glasses battery $pct%" + if (simulated) " (simulator)" else "")
                .setContentText(text)
                // R2 (RG-7): as `urgentNotification` does — collapsed, one line is cut and nothing expands
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build())
        return shown
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

    /** Playback engaged (a user action, never boot): the service takes the
     *  mediaPlayback foreground type too. A refusal is logged loudly — the
     *  connectedDevice type keeps the service alive either way. */
    @Volatile private var mediaTypeOn = false
    private fun promoteToMediaPlayback() {
        if (mediaTypeOn || destroyed) return
        try {
            startForeground(NOTIF_ID, buildNotification(displayStatus()),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            mediaTypeOn = true
            Log.i("service", "foreground types: connectedDevice | mediaPlayback")
        } catch (e: Exception) {
            Log.e("service", "the mediaPlayback foreground type was refused — playback continues under connectedDevice", e)
        }
    }

    /** §9.3: serious display-path errors become PHONE notifications — the only
     *  alert path that works when the display itself is broken. */
    private val urgentIds = java.util.concurrent.atomic.AtomicInteger(0)

    /** A logged ERROR (verdict 21): a glass notice through the running shell
     *  plus the log; the phone notification only when the Global switch is on. */
    private fun errorNotice(tag: String, message: String) {
        val sh = shell
        if (sh != null && sh.settings.phoneNotifications) { urgentNotification(tag, message); return }
        if (sh == null) { urgentNotification(tag, message); return }   // no shell to show it on glass: the phone is the only channel
        try { sh.services.notifyInternal(tag, message) } catch (e: Exception) { android.util.Log.w("damage/service", "glass notice failed: ${e.message}") }
    }

    private fun urgentNotification(source: String, body: String) {
        channels().notify(
            // a rolling id well clear of NOTIF_ID (R3 note: the millis-derived
            // id could collide with the foreground notification)
            1_000 + (urgentIds.incrementAndGet() % 64),
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
        /** 0.64: the low-battery notification. 1 is the foreground notification, 1000-1063 the urgent
         *  ones, 0xD02E the boot receiver's. */
        private const val BATTERY_NOTIF_ID = 2
        private const val CHANNEL = "damage"
        private const val CHANNEL_URGENT = "damage-urgent"
        private const val CHANNEL_BATTERY = "damage-battery"
        private const val ERROR_NOTICE_GAP_MS = 10_000L
        /** Distinct error notices one tag may raise per gap; the rest stay in logcat. */
        private const val ERROR_NOTICES_PER_TAG = 3
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
    /** The PC's media endpoint (MUSIC.md §6.3). */
    val mediaPort: Int get() = p.getInt("mediaPort", BuildConfig.MEDIA_PORT)

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

    /** The battery-exemption dialog was shown once; never nag again — the
     *  missing grant stays visible in the status line instead. */
    val dozeAsked: Boolean get() = p.getBoolean("dozeAsked", false)
    fun setDozeAsked() = p.edit().putBoolean("dozeAsked", true).apply()
}
