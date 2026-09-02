package wm.damage.core.windows.torrents

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import wm.damage.core.util.Log

/**
 * The PC side (TORRENTS.md §4): owns the qBittorrent client and the tracker
 * session, runs the poll loop (15 s idle; the focused pace while any window
 * — local or remote — is focused), turns consecutive snapshots into EVENTS
 * (done / error / added / removed) with a monotonic sequence and a
 * per-process epoch, and persists the ANNOUNCED set so a restart never
 * re-announces a finished torrent. Announcements are decided here, once, so
 * the phone shell and a PC standby shell agree.
 *
 * Every qBittorrent/tracker call is blocking I/O for OFF-loop callers; the
 * window applies results through `runOnShell`. Pacing, never timeouts.
 */
class LocalTorrentsProvider(
    private val qbt: QbtClient,
    private val tl: TorrentLeech?,
    private val dataDir: Path,
    private val scope: CoroutineScope,
    private val idlePaceMs: Long = 15_000,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : TorrentsProvider {

    @Serializable
    private data class Announced(val announced: Map<String, Long> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val announcedPath: Path = dataDir.resolve("torrents.json")
    private val listeners = CopyOnWriteArrayList<TorrentsProvider.Listener>()
    private val announced = HashMap<String, Long>()
    /** The announced file existed at start → this is not the first run, so a
     *  torrent that finished while the service was down still announces. */
    private var announcedLoaded = false

    val epoch: Long = clock()
    @Volatile private var snap: Snapshot? = null
    private var previous: Map<String, Transfer>? = null
    private val events = ArrayDeque<TorrentEvent>()
    @Volatile private var lastSeq = 0L
    private var version = 0L
    @Volatile private var stateLine = "connecting to qBittorrent"
    private var offlineSince = 0L
    private val focusedBy = ConcurrentHashMap<String, Long>()
    @Volatile private var wakeFlag = false
    @Volatile private var running = true
    private val pollMutex = Mutex()
    private var qbtVersion = ""

    init {
        if (Files.isRegularFile(announcedPath)) try {
            announced.putAll(json.decodeFromString(Announced.serializer(), Files.readString(announcedPath)).announced)
            announcedLoaded = true
        } catch (e: Exception) {
            Log.w("torrents", "announced set unreadable — treating this as a first run: ${e.message}")
        }
    }

    private val job: Job = scope.launch(Dispatchers.IO) { loop() }

    private suspend fun loop() {
        while (scope.isActive && running) {
            // clear BEFORE the poll (review 2026-09-01 P5): a wake that lands
            // while the poll is in flight must shorten the coming wait
            wakeFlag = false
            pollOnce()
            // paced wait, interruptible by a wake — a flag polled at a coarse
            // tick, not a timeout on any work
            val until = clock() + currentPace()
            while (scope.isActive && running && !wakeFlag && clock() < until) delay(200)
        }
    }

    private fun currentPace(): Long = focusedBy.values.minOrNull() ?: idlePaceMs

    /** Focus by WHO (the local shell, the phone through the channel): the
     *  fastest interested party sets the pace. */
    fun setFocusedBy(who: String, focused: Boolean, paceMs: Long) {
        val before = focusedBy[who]
        if (focused) focusedBy[who] = paceMs.coerceAtLeast(500) else focusedBy.remove(who)
        // wake only on a CHANGE (review 2026-09-01 P4): the phone's every
        // `snap` request re-asserts its focus, and waking each time made the
        // host poll twice per interval and answer one interval stale
        if (before != focusedBy[who]) wakeFlag = true
    }

    /** The oldest event still held, or 0 when the ring is empty — the host's
     *  `snap` says `truncated` when a driver asks from before it. */
    fun oldestSeq(): Long = synchronized(events) { events.firstOrNull()?.seq ?: 0L }

    override fun setFocused(focused: Boolean, paceMs: Long) = setFocusedBy("local", focused, paceMs)
    override fun refresh() { wakeFlag = true }
    override fun stateLine(): String = stateLine
    override fun snapshot(): Snapshot? = snap
    override fun addListener(l: TorrentsProvider.Listener) {
        listeners.add(l)
        snap?.let { s -> try { l.snapshot(s) } catch (e: Exception) { Log.e("torrents", "listener", e) } }
        try { l.state(stateLine) } catch (e: Exception) { Log.e("torrents", "listener", e) }
    }
    override fun removeListener(l: TorrentsProvider.Listener) { listeners.remove(l) }

    override fun eventsSince(seq: Long, epoch: Long): List<TorrentEvent> {
        if (epoch != this.epoch) return emptyList()
        synchronized(events) { return events.filter { it.seq > seq } }
    }

    /** One poll, serialized (tests call this directly; the loop calls it). */
    suspend fun pollOnce() = pollMutex.withLock { doPoll() }

    /** Blocking poll for tests and one-shot tools. */
    fun pollNow() = runBlocking { pollOnce() }

    private fun doPoll() {
        val (ts, sess0) = try {
            qbt.transfers(qbtVersion)
        } catch (e: Exception) {
            offline(e)
            return
        }
        if (qbtVersion.isEmpty()) {
            qbtVersion = try { qbt.version() } catch (e: Exception) { "" }
        }
        val sess = if (sess0.version.isEmpty() && qbtVersion.isNotEmpty()) sess0.copy(version = qbtVersion) else sess0
        val now = clock()
        val prev = previous
        val byHash = LinkedHashMap<String, Transfer>()
        for (t in ts) byHash[t.hash] = t
        val fresh = ArrayList<TorrentEvent>()
        var announcedChanged = false
        if (prev == null) {
            // the first poll of this process: baseline. A finished torrent we
            // never announced is announced only when the announced set
            // existed (it finished while the service was down); on a true
            // first run everything already finished is recorded silently
            for (t in ts) {
                if (t.finished && t.completedOn > 0 && !announced.containsKey(t.hash)) {
                    if (announcedLoaded) fresh.add(ev("done", t, now))
                    announced[t.hash] = t.completedOn
                    announcedChanged = true
                }
            }
        } else {
            for (t in ts) {
                val p = prev[t.hash]
                if (p == null) {
                    fresh.add(ev("added", t, now))
                    // a torrent that comes back finished announces only with
                    // a NEW completion stamp (review 2026-09-01 P2): a
                    // transient partial list (qBittorrent restarting) removes
                    // and re-adds the same torrents with the same stamp
                    if (t.finished && t.completedOn > 0 && announced[t.hash] != t.completedOn) {
                        fresh.add(ev("done", t, now))
                        announced[t.hash] = t.completedOn
                        announcedChanged = true
                    }
                    continue
                }
                // ONE rule for "done": finished with a completion stamp we have
                // not announced — a re-download that finishes within a poll
                // interval (the previous poll saw it finished too) carries a
                // NEW stamp and announces; a reload with the old stamp does not
                if (t.finished && t.completedOn > 0 && announced[t.hash] != t.completedOn) {
                    fresh.add(ev("done", t, now))
                    announced[t.hash] = t.completedOn
                    announcedChanged = true
                }
                if (t.error && !p.error) fresh.add(ev("error", t, now))
            }
            // a removal keeps its announced stamp (P2): a re-add with the same
            // completion is a reload, not a new finish — the set is bounded by
            // the torrents ever seen, a few bytes each
            for ((h, p) in prev) if (h !in byHash) fresh.add(ev("removed", p, now))
        }
        if (announcedChanged || (prev == null && !announcedLoaded)) saveAnnounced()
        val changed = prev == null || prev != byHash || snap?.session != sess
        if (changed) version++
        previous = byHash
        val s = Snapshot(version, epoch, now, ts, sess, lastSeq)
        snap = s
        online()          // reads snap for the first-contact narration
        for (l in listeners) {
            try {
                l.snapshot(s)
                for (e in fresh) l.event(e)
            } catch (e: Exception) {
                Log.e("torrents", "listener failed", e)
            }
        }
    }

    private fun ev(kind: String, t: Transfer, now: Long): TorrentEvent {
        val e = TorrentEvent(++lastSeq, kind, t.hash, t.name, now)
        synchronized(events) {
            events.addLast(e)
            while (events.size > EVENT_CAP) events.removeFirst()
        }
        return e
    }

    private fun saveAnnounced() {
        try {
            Files.createDirectories(dataDir)
            val tmp = announcedPath.resolveSibling("torrents.json.tmp")
            Files.writeString(tmp, json.encodeToString(Announced.serializer(), Announced(HashMap(announced))))
            Files.move(tmp, announcedPath, StandardCopyOption.REPLACE_EXISTING)
            announcedLoaded = true
        } catch (e: Exception) {
            Log.e("torrents", "announced set not saved — a restart may re-announce", e)
        }
    }

    private fun offline(e: Exception) {
        val now = clock()
        if (offlineSince == 0L) {
            offlineSince = now
            Log.w("torrents", "qBittorrent unreachable: ${e.message}")
        }
        setState("qBittorrent unreachable ${(now - offlineSince) / 1000}s")
    }

    private var narratedFirst = false

    private fun online() {
        if (offlineSince != 0L) {
            Log.i("torrents", "qBittorrent reachable again")
            offlineSince = 0L
        }
        if (!narratedFirst) {
            // one line per process for the service log (DAILY.md): the ops
            // answer to "did the torrents window find qBittorrent?"
            narratedFirst = true
            val s = snap
            Log.i("torrents", "qBittorrent ${qbtVersion.ifEmpty { "(version unknown)" }} on the loopback API — " +
                "${s?.transfers?.size ?: 0} transfers, ${s?.transfers?.count { it.underAWeek } ?: 0} under a week of seeding" +
                (if (tl == null) "; TorrentLeech not configured" else "; TorrentLeech configured"))
        }
        if (stateLine.isNotEmpty()) setState("")
    }

    private fun setState(s: String) {
        stateLine = s
        for (l in listeners) try { l.state(s) } catch (e: Exception) { Log.e("torrents", "state listener", e) }
    }

    // ------------------------------------------------------------ qBittorrent ops
    override fun detail(hash: String): TransferDetail = qbt.detail(hash)
    override fun start(hashes: List<String>) { qbt.start(hashes); refresh() }
    override fun stop(hashes: List<String>) { qbt.stop(hashes); refresh() }
    override fun recheck(hashes: List<String>) { qbt.recheck(hashes); refresh() }
    override fun delete(hashes: List<String>, withFiles: Boolean) { qbt.delete(hashes, withFiles); refresh() }

    // ------------------------------------------------------------ the tracker
    private fun tracker(): TorrentLeech = tl
        ?: throw IllegalStateException("TorrentLeech is not configured — torrentleechUser / torrentleechPass in ~/.damage/config.json")

    override fun tlCategories(): List<TlCategory> = TorrentLeech.CATEGORIES
    override fun tlBrowse(categoryId: Int?, page: Int, sort: String): TlPage = tracker().list(null, categoryId, page, sort)
    override fun tlSearch(query: String, page: Int, sort: String): TlPage = tracker().list(query, null, page, sort)
    override fun tlDetail(fid: String): TlDetail = tracker().detail(fid)
    override fun tlAccount(): TlAccount = tracker().account()

    override fun tlAdd(fid: String, stopped: Boolean): String {
        val (fileName, bytes) = tracker().download(fid)
        qbt.add(bytes, fileName, stopped)
        refresh()
        return fileName.removeSuffix(".torrent")
    }

    // ------------------------------------------------------------ open on PC
    override fun openOnPc(target: String) {
        val env = HashMap(System.getenv())
        if (env["DISPLAY"].isNullOrEmpty()) env["DISPLAY"] = ":0.0"
        val pb = ProcessBuilder("xdg-open", target)
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        val p = pb.start()
        Thread({
            try {
                val out = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
                val code = p.waitFor()
                if (code != 0) Log.w("torrents", "xdg-open '$target' exited $code: ${out.take(200)}")
            } catch (e: Exception) {
                Log.w("torrents", "xdg-open '$target': ${e.message}")
            }
        }, "xdg-open-drain").apply { isDaemon = true }.start()
    }

    override fun close() {
        running = false
        job.cancel()
    }

    companion object {
        const val EVENT_CAP = 200
    }
}
