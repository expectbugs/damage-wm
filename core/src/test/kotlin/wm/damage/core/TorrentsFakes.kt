package wm.damage.core

import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wm.damage.core.app.AppHost
import wm.damage.core.app.AppNet
import wm.damage.core.app.AppWire
import wm.damage.core.app.Contracts
import wm.damage.core.app.CoreExecutor
import wm.damage.core.app.DevicesCore
import wm.damage.core.net.ReachGate
import wm.damage.core.net.Side
import wm.damage.core.web.Access
import wm.damage.core.web.BlobSource
import wm.damage.core.web.Bundle
import wm.damage.core.web.Devices
import wm.damage.core.web.WebServer
import wm.damage.core.windows.torrents.SessionStats
import wm.damage.core.windows.torrents.Snapshot
import wm.damage.core.windows.torrents.TFile
import wm.damage.core.windows.torrents.TlAccount
import wm.damage.core.windows.torrents.TlCategory
import wm.damage.core.windows.torrents.TlDetail
import wm.damage.core.windows.torrents.TlFile
import wm.damage.core.windows.torrents.TlItem
import wm.damage.core.windows.torrents.TlPage
import wm.damage.core.windows.torrents.TorrentEvent
import wm.damage.core.windows.torrents.TorrentLeech
import wm.damage.core.windows.torrents.TorrentsCore
import wm.damage.core.windows.torrents.TorrentsProvider
import wm.damage.core.windows.torrents.TorrentsScopes
import wm.damage.core.windows.torrents.Transfer
import wm.damage.core.windows.torrents.TransferDetail

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q4: test code. [TestTorrents] is a scripted Torrents provider — made-up transfers, a tracker of
 * made-up listings, an event ring like the real provider's, every call written down — so no test reaches qBittorrent,
 * the tracker or any site and nothing reads a login. [TorrentsLaneRig] is the PC's lane as the host builders make it
 * for Torrents (the real window registry, `TorrentsScopes` served through `AppNet.serve`, the pace per viewer), behind a
 * real web listener on loopback port 0, with pages written by hand ([Page]). Temp directories only.
 */
internal class TestTorrents(val epoch: Long = 4242L) : TorrentsProvider {
    val listeners = CopyOnWriteArrayList<TorrentsProvider.Listener>()
    val ops = CopyOnWriteArrayList<String>()
    private val events = CopyOnWriteArrayList<TorrentEvent>()
    @Volatile private var seq = 0L
    @Volatile var version = 1L
    @Volatile var at = 1_800_000_000_000L
    @Volatile var line = ""
    /** While set, every tracker call waits for it (a tracker that takes its time). */
    @Volatile var trackerGate: CountDownLatch? = null
    /** Rows in the tracker's listing (35 a page, as the site's). */
    @Volatile var listingRows = 50

    @Volatile var transfers: List<Transfer> = listOf(
        t("c0ffee01", "A made-up download (1080p)", "downloading", 0.47, 6_400_000_000, dl = 1_250_000, eta = 2_700, added = 1_799_990_000),
        t("c0ffee02", "Another made-up release", "uploading", 1.0, 580_000_000, up = 420_000, eta = 8_640_000, added = 1_799_000_000,
            completed = 1_799_100_000, seeding = 172_800),
        t("c0ffee03", "A made-up stopped one", "stoppedDL", 0.12, 700_000_000, eta = 0, added = 1_798_000_000),
    )

    @Volatile var session = SessionStats(dlSpeed = 1_250_000, upSpeed = 420_000, dlSession = 4_100_000_000, upSession = 9_300_000_000,
        allDl = 2_100_000_000_000, allUl = 11_000_000_000_000, freeSpace = 2_900_000_000_000, ratio = "5.24",
        peers = 46, status = "connected", version = "v5.1.4")

    fun t(hash: String, name: String, state: String, progress: Double, size: Long, dl: Long = 0, up: Long = 0, eta: Long,
                  added: Long, completed: Long = 0, seeding: Long = 0) = Transfer(hash, name, state, progress, size,
        (size * progress).toLong(), size / 3, dl, up, eta, 0.4, 8, 100, 3, 20, added, completed, seeding,
        "/srv/made-up/downloads", "/srv/made-up/downloads/$name", "", "", "https://tracker.example.org/announce")

    fun snap(): Snapshot = Snapshot(version, epoch, at, transfers, session, seq)

    /** A poll: a new snapshot to every listener. */
    fun poll() {
        version++
        at += 2_000
        for (l in listeners) l.snapshot(snap())
    }

    /** An event in the ring; [deliver] = handed to the listeners as a poll hands it (after its snapshot). */
    fun event(kind: String, hash: String, name: String, deliver: Boolean = true): TorrentEvent {
        val e = TorrentEvent(++seq, kind, hash, name, at)
        events += e
        if (deliver) for (l in listeners) { l.snapshot(snap()); l.event(e) }
        return e
    }

    override fun stateLine(): String = line
    override fun snapshot(): Snapshot = snap()
    override fun addListener(l: TorrentsProvider.Listener) {
        if (!listeners.addIfAbsent(l)) return
        l.snapshot(snap())
        l.state(line)
    }
    override fun removeListener(l: TorrentsProvider.Listener) { listeners.remove(l) }
    override fun setFocused(focused: Boolean, paceMs: Long) { ops += "focus:$focused:$paceMs" }
    override fun refresh() { ops += "refresh" }
    override fun eventsSince(seq: Long, epoch: Long): List<TorrentEvent> =
        if (epoch != this.epoch) emptyList() else events.filter { it.seq > seq }

    override fun detail(hash: String) = TransferDetail(hash, listOf(TFile("$hash/a made-up file.mkv", 1_000_000, 0.5, 1)),
        comment = "made up", trackers = listOf("https://tracker.example.org/announce"))

    /** A call qBittorrent took: written down, and its effect in the next snapshot (as the client's list would show). */
    private fun took(what: String, hashes: List<String>, f: (Transfer) -> Transfer?) {
        ops += what
        transfers = transfers.mapNotNull { if (it.hash in hashes) f(it) else it }
        poll()
    }

    override fun start(hashes: List<String>) = took("start:${hashes.joinToString("|")}", hashes) {
        it.copy(state = if (it.progress >= 1.0) "uploading" else "downloading")
    }
    override fun stop(hashes: List<String>) = took("stop:${hashes.joinToString("|")}", hashes) {
        it.copy(state = if (it.progress >= 1.0) "stoppedUP" else "stoppedDL", dlSpeed = 0, upSpeed = 0)
    }
    override fun recheck(hashes: List<String>) = took("recheck:${hashes.joinToString("|")}", hashes) {
        it.copy(state = if (it.progress >= 1.0) "checkingUP" else "checkingDL")
    }
    override fun delete(hashes: List<String>, withFiles: Boolean) = took("delete:${hashes.joinToString("|")}:$withFiles", hashes) { null }

    private fun gate() {
        trackerGate?.await(30, TimeUnit.SECONDS)
    }

    override fun tlCategories(): List<TlCategory> = TorrentLeech.CATEGORIES

    private fun item(i: Int, q: String) = TlItem("2400${"%02d".format(i)}", "${q.ifEmpty { "Made up" }} release $i", "release-$i.torrent", 23,
        700_000_000L * (i + 1), 140 - i, 10 + i, 200 + i, "2026-11-15 09:00:00", listOf("made-up"), i % 5 == 0)

    private fun page(q: String, page: Int): TlPage {
        val from = (page - 1) * 35
        return TlPage((from until minOf(listingRows, from + 35)).map { item(it, q) }, page, 35, listingRows)
    }

    override fun tlBrowse(categoryId: Int?, page: Int, sort: String): TlPage {
        gate()
        ops += "browse:${categoryId ?: 0}:$page:$sort"
        return page("", page)
    }

    override fun tlSearch(query: String, page: Int, sort: String): TlPage {
        gate()
        ops += "search:$query:$page:$sort"
        return page(query, page)
    }

    override fun tlDetail(fid: String): TlDetail {
        gate()
        ops += "page:$fid"
        return TlDetail(fid, "Made up release $fid", "Apps · Made up", "1.4 GB", 140, 10, 200, "2026-11-15 09:00:00", "a-made-up-uploader",
            listOf("made-up", "FREELEECH"), "A made-up description.", "A made-up NFO line\n  and another", listOf(TlFile("release.iso", "1.4 GB")),
            "https://tracker.example.org/torrent/$fid")
    }

    override fun tlAdd(fid: String, stopped: Boolean): String {
        ops += "add:$fid:$stopped"
        return "Made up release $fid"
    }

    override fun tlAccount(): TlAccount {
        gate()
        return TlAccount("a-made-up-user", "1.23 TB", "456.7 GB", "2.71", "12 345", "Member")
    }

    override fun openOnPc(target: String) { ops += "open:$target" }
}

internal class TorrentsLaneRig(val provider: TestTorrents = TestTorrents()) : AutoCloseable {
    val dir: Path = Files.createTempDirectory("damage-torrents-lane")
    val host = AppHost(Files.createDirectories(dir.resolve("pc")), AppHost.WINDOWS, CoreExecutor("damage-core-torrents-test")).also { it.open() }
    val devices = Devices(dir.resolve(Devices.FILE)).load()
    val net = AppNet(host, devices = DevicesCore(devices))
    val scopes = TorrentsScopes(host, provider).also { it.start() }
    /** What the pace per viewer told the provider (`who:focused:pace`). */
    val focus = CopyOnWriteArrayList<String>()

    init {
        // as `Main.torrentsParts` serves them
        net.serve(TorrentsCore.WINDOW, scopes.table, scopes.program, scopes::card)
        net.serve(TorrentsCore.WINDOW, scopes.tracker)
        scopes.watch(net.viewers) { who, f, p -> focus += "$who:$f:$p" }
    }

    val server = WebServer(0, ReachGate(Side.PC), Access(listOf("localhost", "127.0.0.1")), devices, net,
        Bundle(mapOf("index.html" to "<!doctype html><p>fixture</p>".toByteArray())), BlobSource { null },
        bindAddress = InetAddress.getLoopbackAddress()).also { it.start() }

    /** A page at [level], past its welcome (this build's contracts in its hello): the page and its connection id. */
    fun page(level: String): Pair<Page, Long> {
        val w = WebRig.Waiting()
        val p = devices.request("127.0.0.1", null, "a made-up $level page", "browser", null, w)
        assertEquals(null, devices.approve(p.code, level, "local"))
        val r = devices.redeem(w.claims.poll(10, TimeUnit.SECONDS)!!)!!
        val page = Page(server.boundPort, r.secret)
        assertEquals(101, page.upgrade.code)
        page.send(AppWire.hello("s-$level", Contracts.MINE))
        return page to page.next("welcome")["conn"]!!.jsonPrimitive.content.toLong()
    }

    override fun close() {
        server.close()
        net.close()
        scopes.close()
        host.close()
        host.executor.shutdown()
        dir.toFile().deleteRecursively()
    }

    companion object {
        fun sub(scope: String, need: JsonObject? = null) = buildJsonObject {
            put("t", "sub"); put("scope", scope)
            need?.let { put("need", it) }
        }

        fun focus(paceMs: Long) = buildJsonObject { put("kind", "focus"); put("paceMs", paceMs) }
        val NONE = buildJsonObject { put("kind", "none") }

        fun programOp(conn: Long, k: Long, action: String, targets: List<String>) = buildJsonObject {
            put("t", "op")
            put("op", buildJsonObject {
                put("device", ""); put("conn", conn); put("k", k); put("window", TorrentsCore.WINDOW); put("action", action)
                put("targets", buildJsonArray { for (t in targets) add(JsonPrimitive(t)) }); put("result", JsonNull)
                put("expect", buildJsonObject { put("any", true) })
            })
        }
    }
}

/** The next frame of type [t] about [scope] (others skipped), within a test's bound. */
internal fun Page.nextFor(t: String, scope: String, ms: Long = 10_000): JsonObject {
    val until = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < until) {
        val f = frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
        if (AppWire.type(f) == t && f["scope"]?.jsonPrimitive?.content == scope) return f
    }
    throw AssertionError("no $t of $scope within $ms ms")
}

/** The answer to the program op ([conn], [k]); every other frame is passed over and dropped (await one op's answer
 *  before sending the next). */
internal fun Page.answerOf(conn: Long, k: Long, ms: Long = 10_000): JsonObject {
    val until = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < until) {
        val f = frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
        if (AppWire.type(f) == "ans" && f["conn"]?.jsonPrimitive?.content == "$conn" && f["k"]?.jsonPrimitive?.content == "$k")
            return f["answer"]!!.jsonObject
    }
    throw AssertionError("no answer to ($conn, $k) within $ms ms")
}
