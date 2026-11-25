package wm.damage.desktop

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.floor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.app.AppHost
import wm.damage.core.app.AppNet
import wm.damage.core.app.HealthBand
import wm.damage.core.app.HealthCore
import wm.damage.core.app.HealthCore.Reading
import wm.damage.core.app.NoticeCore
import wm.damage.core.app.PcScopes
import wm.damage.core.app.Peer
import wm.damage.core.app.RecordAlerts
import wm.damage.core.bastion.AlertStore
import wm.damage.core.bastion.BastionWatcher
import wm.damage.core.util.Log
import wm.damage.core.windows.reader.ReaderCore

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * R-b stage H1 (`private/build085/RB-lean-spec.md` §3.2, §4.3, §4.7, §4.8): the PC's readers of the health record
 * ([HealthCore], `desc/health`). Each line is read where its truth already is; nothing is copied or kept twice:
 *  - on the record's 2 s tick, in memory (the core executor): the service (the record and its log), the phone attached
 *    (the app lane's paced ping), the hub (the watcher's typed read), the nightly copy (its last listing, a run, the
 *    catch-up, `watcher.copy` from the alert store) and the last error (a log sink);
 *  - on their own thread, never the executor: the data folder's disk every minute, the memory cap every 10 minutes,
 *    the copy folder's listing at the start and at each run's end, the windows' cards whenever Main's cards move;
 *  - once: the PC's build stamp.
 * A read that fails leaves its line `stale` with the exception's words and the other lines go on; nothing here ends
 * the host, and nothing here acts on what it reads ("shown only" says so where nothing else does).
 */

/** His question Q2 (open; `RB-lean-spec.md` §1, recommended): the data folder's disk is in ALARM when LESS THAN this
 *  percent of it is free (strict "less than"; about 72 GB of the 3.6 TB root). His answer changes this constant only. */
const val DISK_ALARM_FREE_PCT = 2.0
/** His question Q2 (open; recommended): the disk is in ATTENTION when LESS THAN this percent is free (strict "less
 *  than"; about 180 GB). His answer changes this constant only. */
const val DISK_ATTENTION_FREE_PCT = 5.0

/** R-b H1: the health record of this process, served on [appNet] before a client can ask, its readers started. Called
 *  once by each PC host builder at the PROCESS START, before the content host listens; never per stack or by a tool. */
fun startHealth(appNet: AppNet, appHost: AppHost, notices: NoticeCore, watcher: BastionWatcher, alerts: AlertStore,
                reader: ReaderCore, dataDir: Path, scope: CoroutineScope): HealthParts {
    val core = HealthCore(appHost, notices)
    appNet.register(core)
    val stamp = "desktop " + (HealthParts::class.java.getResource("/damage-build.txt")?.readText()?.trim() ?: "unstamped")
    return HealthParts(core, appHost, appNet, watcher, alerts, reader, dataDir).also { it.start(scope, stamp) }
}

class HealthParts(
    val core: HealthCore,
    private val host: AppHost,
    private val appNet: AppNet,
    private val watcher: BastionWatcher,
    private val alerts: AlertStore,
    private val reader: ReaderCore?,
    private val dataDir: Path,
    private val capDir: Path = CAP_DIR,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** This process's ERROR lines (a log sink, installed by [start]). */
    internal val errors = Errors()
    /** The nightly copy of this start, once [copy] has given it (null = off); the line is "not read yet" before. */
    @Volatile private var nightly: NightlyCopy? = null
    @Volatile private var copyGiven = false
    /** The copy folder's last listing; null until the first. */
    @Volatile private var copyRead: CopyRead? = null
    private class CopyRead(val last: NightlyCopy.Copy?, val failed: String?)

    /** The event-driven reads (the copy folder, the cards), one at a time, off the executor. */
    private val reads = Executors.newSingleThreadExecutor { r -> Thread(r, "health-read").apply { isDaemon = true } }
    private val sourcesDue = AtomicBoolean(false)

    /** Starts every reader; [stamp] is the PC's build. */
    fun start(scope: CoroutineScope, stamp: String) {
        Log.addSink(errors)
        core.set(HealthCore.BUILDS, Reading("PC $stamp", HealthBand.NORMAL, HealthCore.SHOWN_ONLY))
        host.addTickListener(::tick)
        appNet.addCardListener(::sourcesMoved)
        reader?.addShelfListener { _, _ -> sourcesMoved() }
        sourcesMoved()
        scope.launch(Dispatchers.IO) {
            var n = 0L
            while (isActive) {
                read(HealthCore.DISK) { diskOf(dataDir) }
                if (n++ % CAP_EVERY == 0L) read(HealthCore.CAP) { cap(capDir) }
                delay(DISK_EVERY_MS)        // pacing between two reads, not a bound on any read
            }
        }
    }

    /** The nightly copy of this start, made after the content host (null = off): its listing is read now and at each
     *  run's end. Called once by the host builder. */
    fun copy(c: NightlyCopy?) {
        nightly = c
        copyGiven = true
        c?.onRunEnd = ::readCopy
        reads.execute { readCopy() }
    }

    /** The lines read in memory, on the record's tick (the executor). */
    internal fun tick() {
        val now = host.clock()
        read(HealthCore.SERVICE) { service() }
        read(HealthCore.PHONE_LINE) { phone() }
        read(HealthCore.HUB) { hub(watcher.hub(), now, zone) }
        read(HealthCore.BACKUP) { backup(now) }
        read(HealthCore.ERROR) { errors.reading() }
    }

    /** [f]'s reading for line [id]; a read that throws leaves the line stale with the exception's words. */
    private inline fun read(id: String, f: () -> Reading?) {
        try {
            f()?.let { core.set(id, it) }
        } catch (e: Exception) {
            core.failed(id, "${e::class.simpleName}: ${e.message ?: "no detail"}")
        }
    }

    private fun service(): Reading = when {
        !host.isOpen -> Reading("the record did not open at this start: ops are answered \"retry\" and the app lane " +
            "is refused", HealthBand.ALARM, "shown only: nothing opens it before the next start")
        !host.logOpen -> Reading("the operation log is closed: changes from other devices are refused",
            HealthBand.ALARM, "the log is opened again every ${AppHost.TICK_MS / 1000} s", alert = RecordAlerts.LOG)
        else -> Reading("up; the record and its operation log are open", HealthBand.NORMAL, HealthCore.SHOWN_ONLY)
    }

    private fun phone(): Reading =
        if (appNet.connected(Peer.PHONE.device)) Reading("attached", HealthBand.NORMAL, HealthCore.SHOWN_ONLY)
        else Reading("not attached", HealthBand.ATTENTION, "the phone connects again by itself; nothing is done on the PC")

    /** The copy's line from the alert store, its run and catch-up (memory) and its last listing; null before that. */
    private fun backup(now: Long): Reading? {
        if (!copyGiven) return null
        val up = alerts.table().firstOrNull { it.id == BastionWatcher.COPY }
        val c = nightly ?: return if (up != null) Reading(up.text, HealthBand.ALARM,
            "shown only: nothing is copied before the config is fixed and Damage restarts", alert = BastionWatcher.COPY,
            since = up.raisedAt)
            else Reading("off: no backupDir in the config", HealthBand.ATTENTION, HealthCore.SHOWN_ONLY)
        if (up != null) return Reading(up.text, HealthBand.ALARM,
            if (c.running != null) "nothing is cancelled; a daily copy due while this one runs is skipped"
            else "the next daily copy at ${c.at} tries again", tag = COPY_TAG, alert = BastionWatcher.COPY, since = up.raisedAt)
        val read = copyRead ?: return null
        read.failed?.let {
            core.failed(HealthCore.BACKUP, "the copy folder could not be listed ($it)")
            return null
        }
        return copyLine(read.last, c.running, c.catchUpDue, c.at, now, zone)
    }

    /** The copy folder's listing (disk): at the start and at each run's end, never on a tick. */
    private fun readCopy() {
        val c = nightly ?: return
        copyRead = try {
            CopyRead(c.last(), null)
        } catch (e: Exception) {
            CopyRead(null, "${e::class.simpleName}: ${e.message ?: "no detail"}")
        }
    }

    /** Main's cards moved (executor, or a shelf scan's thread): the cards are read again on the reads thread, once
     *  for a burst. Reader's card is made here as `PcScopes` makes it, so the line follows it with no web listener. */
    private fun sourcesMoved() {
        if (!sourcesDue.compareAndSet(false, true)) return
        reads.execute {
            sourcesDue.set(false)
            read(HealthCore.SOURCES) {
                val shelf = reader?.let { r ->
                    val state = r.libraryState
                    buildJsonObject {
                        put("name", "Reader")
                        put("availability", PcScopes.availability(state))
                        if (state != "ok") put("reason", state)
                    }
                }
                sources(listOfNotNull(shelf) + appNet.cards())
            }
        }
    }

    /** This process's ERROR lines: the count and the last with its time of day. A [Log.Sink]: any thread, never logs. */
    internal class Errors : Log.Sink {
        private val count = AtomicLong()
        @Volatile private var last: String? = null

        init {
            // what this process logged before the sink: the log's ring holds this process's lines only
            val before = Log.recent().mapNotNull { RING_ERROR.matchEntire(it) }
            count.set(before.size.toLong())
            last = before.lastOrNull()?.let { "${it.groupValues[1]}, ${it.groupValues[2]}" }
        }

        override fun log(level: Log.Level, tag: String, message: String) {
            if (level != Log.Level.ERROR) return
            last = "${LocalTime.now().format(SECONDS)}, $tag: $message"
            count.incrementAndGet()
        }

        fun reading(): Reading {
            val n = count.get()
            val l = last ?: return Reading("none since the service's start", HealthBand.NORMAL, ERROR_DOING)
            return Reading("$n since the service's start; the last at $l", HealthBand.NORMAL, ERROR_DOING)
        }
    }

    companion object {
        /** The memory cap's group (made at the PC's start, `HANDOFF.md` §82). */
        val CAP_DIR: Path = Path.of("/sys/fs/cgroup/claude-work")
        const val DISK_EVERY_MS = 60_000L
        /** Disk reads per cap read: 10 minutes. */
        const val CAP_EVERY = 10
        /** The glasses' words for the two PC lines that carry one (the phone composes the tag, H2). */
        const val HUB_TAG = "hub"
        const val COPY_TAG = "copy"
        private const val DISK_DOING = "shown only: nothing is cleaned automatically; read every minute"
        private const val CAP_DOING = "shown only: the group is made at the PC's start; read every 10 minutes"
        private const val ERROR_DOING = "shown only: each error's own path says what is done about it"
        private const val SOURCES_DOING = "each window asks its own source again and says so in it"
        private val SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
        private val DAY = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ENGLISH)
        /** An ERROR line in `Log.recent()`: `HH:mm:ss.SSS E tag: message`. */
        private val RING_ERROR = Regex("^(\\d\\d:\\d\\d:\\d\\d)\\.\\d{3} E (.*)$", RegexOption.DOT_MATCHES_ALL)

        /** The data folder's disk. */
        fun diskOf(dir: Path): Reading = Files.getFileStore(dir).let { disk(it.usableSpace, it.totalSpace) }

        /** The disk line from its [usable] and [total] bytes, banded by Q2's two limits (strict "less than"). */
        fun disk(usable: Long, total: Long): Reading {
            if (total <= 0) throw IOException("the disk reports a total of $total B")
            val free = usable * 100.0 / total
            val of = "of ${size(total)}"
            return when {
                free < DISK_ALARM_FREE_PCT -> Reading("${tenths(free)} % free $of", HealthBand.ALARM, DISK_DOING,
                    tag = "disk ${floor(100 - free).toInt()}%")
                free < DISK_ATTENTION_FREE_PCT -> Reading("${tenths(free)} % free $of", HealthBand.ATTENTION, DISK_DOING)
                else -> Reading("${floor(free).toInt()} % free $of", HealthBand.NORMAL, DISK_DOING)
            }
        }

        /** The memory cap from the group's two files under [dir]. A group that is missing is a reading; a file that is
         *  missing or holds something else throws (the line goes stale with the words). */
        fun cap(dir: Path): Reading {
            val name = dir.fileName.toString()
            if (!Files.isDirectory(dir)) return Reading("$name: the group is missing, so nothing is capped",
                HealthBand.ATTENTION, CAP_DOING)
            val max = Files.readString(dir.resolve("memory.max")).trim()
            val swap = Files.readString(dir.resolve("memory.swap.max")).trim()
            val limit = if (max == "max") null
                else max.toLongOrNull() ?: throw IOException("memory.max holds \"$max\", neither bytes nor max")
            val swapAllowed = swap == "max" ||
                (swap.toLongOrNull() ?: throw IOException("memory.swap.max holds \"$swap\", neither bytes nor max")) > 0
            return when {
                limit == null -> Reading("$name: no limit (memory.max is max)", HealthBand.ATTENTION, CAP_DOING)
                swapAllowed -> Reading("$name: ${gib(limit)}, swap allowed (memory.swap.max is $swap)",
                    HealthBand.ATTENTION, CAP_DOING)
                else -> Reading("$name: ${gib(limit)}, no swap", HealthBand.NORMAL, CAP_DOING)
            }
        }

        /** The hub's line from the watcher's typed read ([BastionWatcher.Hub]). Nothing in it moves with a good answer,
         *  so a hub answering every poll changes nothing. */
        fun hub(h: BastionWatcher.Hub, now: Long, zone: ZoneId): Reading {
            val asking = "asking every ${h.pollMs / 1000} s"
            val stuck = h.inFlightSince?.takeIf { now - it >= BastionWatcher.STUCK_MS }
                ?.let { "a request has been in flight since ${hhmm(it, zone)}" }
            return when {
                !h.on && !h.configured -> Reading("off: ${h.offReason}", HealthBand.ATTENTION, HealthCore.SHOWN_ONLY)
                !h.on -> Reading("off: ${h.offReason}", HealthBand.ALARM,
                    if (h.rereads) "the token file is read again every minute"
                    else "shown only: a fix in the config and a restart start it",
                    tag = HUB_TAG, alert = BastionWatcher.TOKEN_FILE)
                h.refused != 0 -> Reading("the hub refused the token (HTTP ${h.refused})", HealthBand.ALARM,
                    "polling stopped; the token file is read again every minute", tag = HUB_TAG, alert = BastionWatcher.TOKEN)
                now - h.lastGoodAt >= h.silentMs -> Reading("not answering" + (h.problem?.let { ": $it" } ?: ""),
                    HealthBand.ALARM, "$asking; the alert is up", tag = HUB_TAG, alert = BastionWatcher.SILENT,
                    since = h.failingSince ?: h.lastGoodAt, stale = stuck)
                h.problem != null -> Reading("not answering: ${h.problem}", HealthBand.ATTENTION,
                    "$asking; the alert after ${h.silentMs / 60_000} min", since = h.failingSince, stale = stuck)
                !h.answeredOnce -> Reading("waiting for its first answer", HealthBand.NORMAL, asking, stale = stuck)
                else -> Reading("answering", HealthBand.NORMAL, asking, stale = stuck)
            }
        }

        /** The copy's line with no alert up: the newest copy against `NightlyCopy.STALE_MS`, a run or the catch-up
         *  that will replace an old one making it attention instead of alarm. */
        fun copyLine(last: NightlyCopy.Copy?, running: Long?, catchUp: Long?, daily: LocalTime, now: Long,
                     zone: ZoneId): Reading {
            val plan = "daily at $daily"
            val pending = running?.let { "a copy running since ${hhmm(it, zone)}" }
                ?: catchUp?.let { "a catch-up copy at ${hhmm(it, zone)}" }
            val newest = last?.let { "newest ${DAY.format(Instant.ofEpochMilli(it.at).atZone(zone))}" } ?: "no copy yet"
            val old = last == null || now - last.at > NightlyCopy.STALE_MS
            return when {
                !old -> Reading(newest, HealthBand.NORMAL, (pending?.let { "$it; " } ?: "") + plan)
                pending != null -> Reading(newest, HealthBand.ATTENTION, "$pending, then $plan")
                else -> Reading("$newest: older than ${NightlyCopy.STALE_MS / 3_600_000} h", HealthBand.ALARM,
                    "shown only until the next daily copy at $daily", tag = COPY_TAG, since = last?.at)
            }
        }

        /** The windows' sources from Main's cards: every card not `live`, with its reason. */
        fun sources(cards: List<JsonObject>): Reading {
            fun str(c: JsonObject, k: String) = (c[k] as? JsonPrimitive)?.content
            val off = cards.filter { str(it, "availability") != "live" }
            if (off.isEmpty()) return Reading("all ${cards.size} live", HealthBand.NORMAL, SOURCES_DOING)
            return Reading(off.joinToString("; ") { c ->
                val a = str(c, "availability")
                "${str(c, "name") ?: str(c, "window")}: $a" + (str(c, "reason")?.takeIf { it != a }?.let { " ($it)" } ?: "")
            }, HealthBand.ATTENTION, SOURCES_DOING)
        }

        private fun hhmm(ms: Long, zone: ZoneId): String = HHMM.format(Instant.ofEpochMilli(ms).atZone(zone))

        /** Rounded down to a tenth, so a value just under a limit never reads as the limit. */
        private fun tenths(x: Double): String = String.format(Locale.ROOT, "%.1f", floor(x * 10) / 10)

        private fun size(bytes: Long): String =
            if (bytes >= 1_000_000_000_000L) String.format(Locale.ROOT, "%.1f TB", bytes / 1e12)
            else String.format(Locale.ROOT, "%.0f GB", bytes / 1e9)

        private fun gib(bytes: Long): String =
            if (bytes % (1L shl 30) == 0L) "${bytes shr 30} GiB" else String.format(Locale.ROOT, "%.1f GiB", bytes / (1L shl 30).toDouble())
    }
}
