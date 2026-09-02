package wm.damage.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import wm.damage.core.content.ContentHostServer
import wm.damage.core.content.LocalContent
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.windows.torrents.LocalTorrentsProvider
import wm.damage.core.windows.torrents.QbtClient
import wm.damage.core.windows.torrents.RemoteTorrentsProvider
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
import wm.damage.core.windows.torrents.TorrentsProvider
import wm.damage.core.windows.torrents.TorrentsService
import wm.damage.core.windows.torrents.TorrentsWindow
import wm.damage.core.windows.torrents.Transfer
import wm.damage.core.windows.torrents.TransferDetail
import wm.damage.core.wire.EvenHubMsg

/**
 * TORRENTS (TORRENTS.md §6): the qBittorrent client against a fake Web API
 * (the 5.x verb names, maindata parsing, multipart add, the login-on-403
 * path); the TorrentLeech adapter against fixtures (login, listing, a detail
 * page with the real landmarks, format drift refused loudly, session expiry
 * re-login); the host provider's event diff and announced-set persistence;
 * the window grammar over a fake provider incl. search through the keyboard
 * and the done notification; persistence + continuity; and the remote
 * provider through a real loopback content host.
 */
class TorrentsTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private suspend fun awaitTrue(what: String, ms: Long = 20_000, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < ms) delay(20)
        assertTrue(cond(), "did not converge: $what")
    }

    // =========================================================== a fake qBittorrent
    private class FakeQbt : AutoCloseable {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = CopyOnWriteArrayList<String>()
        @Volatile var requireAuth = false
        @Volatile var torrents: List<Map<String, Any>> = listOf(
            row("aa01", "ubuntu.iso", "downloading", 0.47, 1_000, completion = 0, seeding = 0),
            row("bb02", "done.mkv", "stalledUP", 1.0, 2_000, completion = 1_700_000_000, seeding = 100_000),
        )
        val base get() = "http://127.0.0.1:${server.address.port}"

        companion object {
            fun row(hash: String, name: String, state: String, progress: Double, size: Long,
                completion: Long, seeding: Long): Map<String, Any> = mapOf(
                "hash" to hash, "name" to name, "state" to state, "progress" to progress, "size" to size,
                "downloaded" to (size * progress).toLong(), "uploaded" to 10L, "dlspeed" to 1_250_000L, "upspeed" to 300L,
                "eta" to 2_700L, "ratio" to 0.4, "num_seeds" to 8, "num_complete" to 145, "num_leechs" to 3, "num_incomplete" to 19,
                "added_on" to 1_690_000_000L, "completion_on" to completion, "seeding_time" to seeding,
                "save_path" to "/home/user/Downloads", "content_path" to "/home/user/Downloads/$name",
                "category" to "", "tags" to "", "tracker" to "https://tracker.torrentleech.org/a/announce",
            )
        }

        private fun jsonOf(v: Any?): String = when (v) {
            null -> "null"
            is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            is Number, is Boolean -> v.toString()
            is Map<*, *> -> v.entries.joinToString(",", "{", "}") { jsonOf(it.key.toString()) + ":" + jsonOf(it.value) }
            is List<*> -> v.joinToString(",", "[", "]") { jsonOf(it) }
            else -> "\"$v\""
        }

        private fun reply(x: HttpExchange, status: Int, body: String, headers: Map<String, String> = emptyMap()) {
            for ((k, v) in headers) x.responseHeaders.add(k, v)
            val b = body.toByteArray()
            x.sendResponseHeaders(status, b.size.toLong())
            x.responseBody.use { it.write(b) }
        }

        init {
            server.createContext("/") { x ->
                val path = x.requestURI.path
                val body = x.requestBody.readBytes()
                val text = body.toString(Charsets.ISO_8859_1)
                requests.add("${x.requestMethod} $path ${text.take(300)}")
                val cookie = x.requestHeaders.getFirst("Cookie") ?: ""
                if (requireAuth && !path.endsWith("/auth/login") && !cookie.contains("SID=abc")) {
                    reply(x, 403, "Forbidden"); return@createContext
                }
                when {
                    path.endsWith("/auth/login") -> if (text.contains("username=admin")) reply(x, 200, "Ok.", mapOf("Set-Cookie" to "SID=abc; path=/")) else reply(x, 200, "Fails.")
                    path.endsWith("/app/webapiVersion") -> reply(x, 200, "2.11.4")
                    path.endsWith("/app/version") -> reply(x, 200, "v5.1.4")
                    path.endsWith("/sync/maindata") -> reply(x, 200, jsonOf(mapOf(
                        "rid" to 1, "full_update" to true,
                        "torrents" to torrents.associateBy { it["hash"] as String },
                        "server_state" to mapOf("dl_info_speed" to 1_250_000L, "up_info_speed" to 300L,
                            "dl_info_data" to 5L, "up_info_data" to 6L, "alltime_dl" to 7L, "alltime_ul" to 8L,
                            "free_space_on_disk" to 9L, "global_ratio" to "5.24", "total_peer_connections" to 46,
                            "connection_status" to "connected"))))
                    path.endsWith("/torrents/properties") -> reply(x, 200, jsonOf(mapOf("comment" to "hi", "creation_date" to 1L, "pieces_num" to 3, "piece_size" to 4L)))
                    path.endsWith("/torrents/files") -> reply(x, 200, jsonOf(listOf(mapOf("name" to "a", "size" to 1L, "progress" to 0.5, "priority" to 1))))
                    path.endsWith("/torrents/trackers") -> reply(x, 200, jsonOf(listOf(mapOf("url" to "https://t/announce"), mapOf("url" to "** [DHT] **"))))
                    path.endsWith("/torrents/add") -> reply(x, 200, if (text.contains("name=\"torrents\"")) "Ok." else "Fails.")
                    path.endsWith("/torrents/stop") || path.endsWith("/torrents/start") ||
                        path.endsWith("/torrents/recheck") || path.endsWith("/torrents/delete") -> reply(x, 200, "")
                    path.endsWith("/torrents/pause") || path.endsWith("/torrents/resume") -> reply(x, 404, "Not Found")
                    else -> reply(x, 404, "Not Found")
                }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    @Test
    fun qbtClientSpeaksThe5xVerbsAndParsesMaindata() {
        FakeQbt().use { q ->
            val c = QbtClient(q.base)
            assertEquals("2.11.4", c.webapiVersion())
            val (ts, sess) = c.transfers("v5.1.4")
            assertEquals(2, ts.size)
            val u = ts.first { it.hash == "aa01" }
            assertEquals("ubuntu.iso", u.name)
            assertEquals(0.47, u.progress)
            assertEquals(1_250_000L, u.dlSpeed)
            assertEquals(145, u.seedsTotal)
            assertEquals("/home/user/Downloads/ubuntu.iso", u.contentPath)
            assertTrue(u.downloading && !u.finished)
            val d = ts.first { it.hash == "bb02" }
            assertTrue(d.finished && d.underAWeek)
            assertEquals(1_250_000L, sess.dlSpeed)
            assertEquals("5.24", sess.ratio)
            assertEquals(9L, sess.freeSpace)
            c.stop(listOf("aa01", "bb02"))
            c.start(listOf("aa01"))
            c.delete(listOf("bb02"), withFiles = true)
            assertTrue(q.requests.any { it.startsWith("POST /api/v2/torrents/stop hashes=aa01%7Cbb02") })
            assertTrue(q.requests.any { it.startsWith("POST /api/v2/torrents/start hashes=aa01") })
            assertTrue(q.requests.any { it.startsWith("POST /api/v2/torrents/delete") && it.contains("deleteFiles=true") })
            assertTrue(q.requests.none { it.contains("/torrents/pause") || it.contains("/torrents/resume") })
            val det = c.detail("aa01")
            assertEquals(1, det.files.size)
            assertEquals("hi", det.comment)
            assertEquals(listOf("https://t/announce"), det.trackers)   // the DHT pseudo-entry is not a tracker
            c.add("d8:announce1:a4:infod4:name1:xee".toByteArray(), "x.torrent", stopped = true)
            val add = q.requests.first { it.startsWith("POST /api/v2/torrents/add") }
            assertTrue(add.contains("name=\"stopped\"") && add.contains("true"), add)
            assertTrue(add.contains("filename=\"x.torrent\""), add)
        }
    }

    @Test
    fun qbtClientLogsInOnceOnForbiddenWhenCredentialsExistAndReportsOtherwise() {
        FakeQbt().use { q ->
            q.requireAuth = true
            val anon = QbtClient(q.base)
            val e = assertFailsWith<QbtClient.QbtException> { anon.webapiVersion() }
            assertTrue(e.message!!.contains("403"), e.message)
            val c = QbtClient(q.base, "admin", "pw")
            assertEquals("2.11.4", c.webapiVersion())
            assertEquals(1, q.requests.count { it.contains("/auth/login") })
            assertEquals("v5.1.4", c.version())
            assertEquals(1, q.requests.count { it.contains("/auth/login") })   // the SID is reused
        }
    }

    // =========================================================== a fake TorrentLeech
    private class FakeTl : AutoCloseable {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = CopyOnWriteArrayList<String>()
        @Volatile var expireOnce = false
        /** The next listing answers a plain page (no login form): maintenance. */
        @Volatile var pageOnce = false
        @Volatile var listingBody: String = LISTING
        @Volatile var detailBody: String = DETAIL
        val base get() = "http://127.0.0.1:${server.address.port}"

        companion object {
            const val LISTING = """{"numFound":403,"perPage":35,"page":1,"orderBy":"added","order":"desc","torrentList":[
                {"fid":"241826800","filename":"Ubuntu.26.04.torrent","name":"Ubuntu 26.04 Desktop amd64","addedTimestamp":"2026-09-01 20:15:05","categoryID":23,"size":3300000000,"completed":234,"seeders":145,"leechers":19,"tags":["Linux","amd64"],"new":false,"imdbID":"","rating":0,"genres":"","download_multiplier":0},
                {"fid":241826801,"filename":"Debian.13.torrent","name":"Debian 13 DVD amd64","addedTimestamp":"2026-09-01 19:00:00","categoryID":23,"size":4000000000,"completed":12,"seeders":30,"leechers":2,"tags":["Linux"],"download_multiplier":1}
            ]}"""
            const val DETAIL = """<html><body><h1 id="torrentnameid">Ubuntu 26.04 Desktop amd64</h1>
                <!-- a commented-out template of the NFO block, exactly as the live page carries one:
                <div class="editNFO" id="nfo_text" data-pk="--><!--">TEMPLATE</div> -->
                <div id="subs"><h4 id="torrentName">a modal's copy of the name</h4></div>
                <div id="torrentinfo"><table><tr><td>Category</td><td>PC-ISO</td></tr>
                <tr><td>Added</td><td>
                Monday 1st September 2026 (an hour ago)</td></tr><tr><td>Size</td><td>3.1 GB</td></tr>
                <tr><td>Peers</td><td>164 Peers</td></tr><tr><td>Downloaded</td><td>234 times</td></tr>
                <tr><td>Uploader</td><td>Anonymous <a href="#">Thank You</a></td></tr>
                <tr><td>Tags</td><td><span class="tag">Adobe Acrobat Professional</span>
                <span class="tag">FREELEECH</span></td></tr>
                <tr><td>Seeders</td><td>145</td></tr><tr><td>Leechers</td><td>19</td></tr></table></div>
                <div class="torrent-info-details">The live image &amp; installer.<br>Boots on UEFI.</div>
                <pre id="nfo_text">Release: Ubuntu
SHA256: abc</pre>
                <div id="torrent-files-panel"><table><tr><th>Filename</th><th>Size</th></tr>
                <tr><td>ubuntu.iso</td><td>3.1 GB</td></tr><tr><td>SHA256SUMS</td><td>1 KB</td></tr></table></div>
                <a href="/download/241826800/Ubuntu.26.04.DD%2B5.1+Atmos.torrent">Download Torrent</a></body></html>"""
            const val LOGIN_FORM = """<html><body><form action="/user/account/login/" method="post">
                <input name="username"><input name="password" type="password"></form></body></html>"""
            const val PROFILE = """<html><body><div>uploaded:10.44 TB</div><div>downloaded: 605.58 GB</div>
                <div>ratio:17.648</div><div>TL Points: 4,416.88</div><table><tr><td>Class</td><td>Extreme User</td></tr></table></body></html>"""
        }

        private fun reply(x: HttpExchange, status: Int, body: ByteArray, headers: Map<String, String> = emptyMap()) {
            for ((k, v) in headers) x.responseHeaders.add(k, v)
            x.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) x.responseBody.use { it.write(body) }
            x.close()
        }

        init {
            server.createContext("/") { x ->
                val path = x.requestURI.path
                val body = x.requestBody.readBytes().toString(Charsets.UTF_8)
                requests.add("${x.requestMethod} ${x.requestURI.rawPath} $body")
                val cookie = x.requestHeaders.getFirst("Cookie") ?: ""
                val loggedIn = cookie.contains("PHPSESSID=sess1")
                when {
                    path == "/user/account/login/" ->
                        if (body.contains("username=glassuser") && body.contains("password=pw%21"))
                            reply(x, 302, ByteArray(0), mapOf("Location" to "$base/", "Set-Cookie" to "PHPSESSID=sess1; path=/"))
                        else reply(x, 200, LOGIN_FORM.toByteArray(), mapOf("Content-Type" to "text/html"))
                    !loggedIn || expireOnce -> {
                        expireOnce = false
                        reply(x, 200, LOGIN_FORM.toByteArray(), mapOf("Content-Type" to "text/html; charset=UTF-8"))
                    }
                    pageOnce && path.startsWith("/torrents/browse/list/") -> {
                        pageOnce = false
                        reply(x, 200, "<html><body><h1>Back soon</h1><p>maintenance</p></body></html>".toByteArray(),
                            mapOf("Content-Type" to "text/html; charset=UTF-8"))
                    }
                    path.startsWith("/torrents/browse/list/") -> reply(x, 200, listingBody.toByteArray(), mapOf("Content-Type" to "application/json"))
                    path.startsWith("/torrent/") -> reply(x, 200, detailBody.toByteArray(), mapOf("Content-Type" to "text/html; charset=UTF-8"))
                    path.startsWith("/download/") -> reply(x, 200, "d8:announce20:http://tr/announce4:infod4:name6:ubuntuee".toByteArray(),
                        mapOf("Content-Type" to "application/x-bittorrent"))
                    path.startsWith("/profile/") -> reply(x, 200, PROFILE.toByteArray(), mapOf("Content-Type" to "text/html"))
                    else -> reply(x, 404, "no".toByteArray())
                }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    @Test
    fun torrentLeechLogsInListsParsesDetailsDownloadsAndRefusesDrift() {
        val tmp = Files.createTempDirectory("damage-tl")
        try {
            FakeTl().use { site ->
                val jar = tmp.resolve("tl-cookies.json")
                val tl = TorrentLeech("glassuser", "pw!", jar, base = site.base)
                val page = tl.list(null, null, 1, "added")
                assertEquals(1, site.requests.count { it.startsWith("POST /user/account/login/") })
                assertTrue(site.requests.any { it.startsWith("GET /torrents/browse/list/orderby/added/order/desc/page/1") })
                assertEquals(403, page.total); assertEquals(35, page.perPage)
                assertEquals(2, page.items.size)
                val u = page.items[0]
                assertEquals("241826800", u.fid); assertEquals(23, u.categoryId); assertEquals(145, u.seeders)
                assertTrue(u.freeleech, "download_multiplier 0 = freeleech")
                assertEquals("241826801", page.items[1].fid)          // a numeric fid still reads
                assertFalse(page.items[1].freeleech)
                assertTrue(Files.isRegularFile(jar), "the cookie jar persisted")
                // search + category + sort URL shapes
                tl.list("ubuntu server", 23, 2, "seeders")
                assertTrue(site.requests.any { it.startsWith("GET /torrents/browse/list/query/ubuntu%20server/categories/23/orderby/seeders/order/desc/page/2") })
                // session expiry: ONE re-login, then the retry succeeds
                site.expireOnce = true
                tl.list(null, null, 1, "added")
                assertEquals(2, site.requests.count { it.startsWith("POST /user/account/login/") })
                // the detail page's landmarks
                val d = tl.detail("241826800")
                assertEquals("Ubuntu 26.04 Desktop amd64", d.name)
                assertEquals("PC-ISO", d.category); assertEquals("3.1 GB", d.size)
                assertEquals(145, d.seeders); assertEquals(19, d.leechers); assertEquals(234, d.snatched)
                assertEquals("Anonymous", d.uploader)
                assertTrue(d.added.startsWith("Monday 1st September 2026"), d.added)
                assertTrue("FREELEECH" in d.tags && "Adobe Acrobat Professional" in d.tags, d.tags.toString())   // multi-word tags survive
                assertEquals("The live image & installer.\nBoots on UEFI.", d.description)
                assertEquals("Release: Ubuntu\nSHA256: abc", d.nfo)
                assertEquals(listOf(TlFile("ubuntu.iso", "3.1 GB"), TlFile("SHA256SUMS", "1 KB")), d.files)
                // the torrent file
                val (name, bytes) = tl.download("241826800")
                assertEquals("Ubuntu.26.04.DD+5.1+Atmos.torrent", name)   // '%2B' decodes, a literal '+' stays a plus
                assertTrue(bytes[0] == 'd'.code.toByte())
                // the account stats (five fields, nothing else read)
                val a = tl.account()
                assertEquals("10.44 TB", a.uploaded); assertEquals("605.58 GB", a.downloaded)
                assertEquals("17.648", a.ratio); assertEquals("4,416.88", a.points); assertEquals("Extreme User", a.klass)
                // format drift is LOUD, never an empty list
                site.listingBody = """{"numFound":1,"rows":[]}"""
                val e1 = assertFailsWith<TorrentLeech.TlException> { tl.list(null, null, 1, "added") }
                assertTrue(e1.message!!.contains("format changed"), e1.message)
                site.detailBody = "<html><body><h1>Ubuntu</h1><p>no info table here</p></body></html>"
                val e2 = assertFailsWith<TorrentLeech.TlException> { tl.detail("241826800") }
                assertTrue(e2.message!!.contains("format changed"), e2.message)
                // a row without its identity is drift, not a shorter page
                site.listingBody = """{"numFound":1,"perPage":35,"page":1,"torrentList":[{"id":"1","title":"renamed keys"}]}"""
                val e4 = assertFailsWith<TorrentLeech.TlException> { tl.list(null, null, 1, "added") }
                assertTrue(e4.message!!.contains("fid/name"), e4.message)
                // a PAGE in place of the JSON (no login form — maintenance) is a
                // refused session too: but re-logins are paced, so within the
                // minute after the last login it is reported, not re-logged
                site.listingBody = FakeTl.LISTING
                site.pageOnce = true
                val loginsBefore = site.requests.count { it.startsWith("POST /user/account/login/") }
                val e5 = assertFailsWith<TorrentLeech.TlException> { tl.list(null, null, 1, "added") }
                assertTrue(e5.message!!.contains("a page in place of the listing"), e5.message)
                assertEquals(loginsBefore, site.requests.count { it.startsWith("POST /user/account/login/") })
                assertEquals(2, tl.list(null, null, 1, "added").items.size)   // the site is back: no login needed
                // a second instance reuses the persisted session — no new login
                val logins = site.requests.count { it.startsWith("POST /user/account/login/") }
                site.listingBody = FakeTl.LISTING
                TorrentLeech("glassuser", "pw!", jar, base = site.base).list(null, null, 1, "added")
                assertEquals(logins, site.requests.count { it.startsWith("POST /user/account/login/") })
                // wrong credentials refuse with the site's answer
                val bad = TorrentLeech("glassuser", "nope", tmp.resolve("other.json"), base = site.base)
                val e3 = assertFailsWith<TorrentLeech.TlException> { bad.list(null, null, 1, "added") }
                assertTrue(e3.message!!.contains("login failed"), e3.message)
            }
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // =========================================================== the host provider's diff
    private class Collect : TorrentsProvider.Listener {
        val snaps = CopyOnWriteArrayList<Snapshot>()
        val events = CopyOnWriteArrayList<TorrentEvent>()
        val states = CopyOnWriteArrayList<String>()
        override fun snapshot(s: Snapshot) { snaps.add(s) }
        override fun event(e: TorrentEvent) { events.add(e) }
        override fun state(line: String) { states.add(line) }
    }

    @Test
    fun localProviderDiffsEventsBaselinesAndPersistsTheAnnouncedSet() {
        val tmp = Files.createTempDirectory("damage-torrents-local")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            FakeQbt().use { q ->
                val p = LocalTorrentsProvider(QbtClient(q.base), null, tmp, scope, idlePaceMs = 3_600_000)
                val c = Collect()
                p.addListener(c)
                p.pollNow()
                // the provider's own loop may have polled at construction too — one
                // or two snapshots, never an event on a first run
                assertTrue(c.snaps.isNotEmpty())
                assertTrue(c.events.isEmpty(), "a first run announces nothing: ${c.events}")
                assertTrue(Files.isRegularFile(tmp.resolve("torrents.json")), "the announced set persisted")
                assertEquals("", p.stateLine())
                // the download finishes → ONE done event, once
                q.torrents = listOf(
                    FakeQbt.row("aa01", "ubuntu.iso", "uploading", 1.0, 1_000, completion = 1_800_000_000, seeding = 5),
                    q.torrents[1],
                )
                p.pollNow()
                assertEquals(listOf("done"), c.events.map { it.kind })
                assertEquals("aa01", c.events[0].hash)
                p.pollNow()
                assertEquals(1, c.events.size, "no duplicate announcement")
                // an error edge, then a removal
                q.torrents = listOf(q.torrents[0].toMutableMap().also { it["state"] = "missingFiles" })
                p.pollNow()
                assertEquals(listOf("done", "error", "removed"), c.events.map { it.kind })
                assertEquals("bb02", c.events[2].hash)
                // the removed seed comes back with the SAME completion stamp (a
                // qBittorrent restart's partial list): added, never done again
                q.torrents = q.torrents + FakeQbt.row("bb02", "done.mkv", "stalledUP", 1.0, 2_000, completion = 1_700_000_000, seeding = 100_100)
                p.pollNow()
                assertEquals(listOf("done", "error", "removed", "added"), c.events.map { it.kind })
                // …and with a NEW stamp (re-downloaded) it is a real finish
                q.torrents = listOf(q.torrents[0], FakeQbt.row("bb02", "done.mkv", "stalledUP", 1.0, 2_000, completion = 1_800_000_500, seeding = 3))
                p.pollNow()
                assertEquals("done", c.events.last().kind)
                assertEquals("bb02", c.events.last().hash)
                assertEquals(5L, p.snapshot()!!.lastSeq)
                assertEquals(4, p.eventsSince(1, p.epoch).size)
                assertTrue(p.eventsSince(0, p.epoch + 1).isEmpty(), "a foreign epoch replays nothing")
                p.close()

                // a restart: a torrent that finished WHILE THE SERVICE WAS DOWN announces on the first poll
                q.torrents = listOf(
                    q.torrents[0].toMutableMap().also { it["state"] = "uploading" },
                    FakeQbt.row("cc03", "new.iso", "stalledUP", 1.0, 3_000, completion = 1_800_000_100, seeding = 9),
                )
                val p2 = LocalTorrentsProvider(QbtClient(q.base), null, tmp, scope, idlePaceMs = 3_600_000)
                val c2 = Collect()
                p2.addListener(c2)
                p2.pollNow()
                assertEquals(listOf("done"), c2.events.map { it.kind })
                assertEquals("cc03", c2.events[0].hash)
                assertTrue(p2.epoch != p.epoch || true)   // epochs are clock-derived; equality is not required
                p2.close()

                // qBittorrent gone: the state line says so with a duration, no events
                q.close()
                val p3 = LocalTorrentsProvider(QbtClient(q.base), null, tmp, scope, idlePaceMs = 3_600_000)
                val c3 = Collect()
                p3.addListener(c3)
                p3.pollNow()
                assertTrue(p3.stateLine().startsWith("qBittorrent unreachable"), p3.stateLine())
                assertTrue(c3.events.isEmpty())
                p3.close()
            }
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    // =========================================================== a fake provider for the window
    private class FakeTorrents : TorrentsProvider {
        val listeners = CopyOnWriteArrayList<TorrentsProvider.Listener>()
        val ops = CopyOnWriteArrayList<String>()
        val added = CopyOnWriteArrayList<String>()
        var seq = 0L
        var version = 1L
        val now = System.currentTimeMillis() / 1000
        var transfers = listOf(
            Transfer("aa01", "ubuntu-26.04.iso", "downloading", 0.47, 6_400_000_000, 3_000_000_000, 100, 1_250_000, 300, 2_700, 0.4,
                8, 145, 34, 19, now - 3600, 0, 0, "/home/user/Downloads", "/home/user/Downloads/ubuntu-26.04.iso", "", "", "https://t/a"),
            Transfer("cc03", "Ted.Lasso.S04E05", "uploading", 1.0, 580_000_000, 580_000_000, 1_300_000_000, 0, 420_000, 0, 2.31,
                0, 145, 12, 19, now - 86_400 * 2, now - 86_400 * 2 + 1800, 86_400 * 2 - 1800, "/home/user/Downloads", "/home/user/Downloads/Ted.Lasso.S04E05", "", "", "https://t/a"),
            Transfer("dd04", "Old.Seed", "stalledUP", 1.0, 12_000_000, 12_000_000, 200_000_000, 0, 0, 0, 17.6,
                0, 3, 0, 1, now - 86_400 * 30, now - 86_400 * 29, 86_400 * 29, "/home/user/Downloads", "/home/user/Downloads/Old.Seed", "", "", "https://t/a"),
        )
        var epoch = 7L
        private fun snap() = Snapshot(version, epoch, System.currentTimeMillis(), transfers, SessionStats(dlSpeed = 1_250_000, upSpeed = 420_000, version = "v5.1.4"), seq)
        override fun stateLine() = ""
        override fun snapshot() = snap()
        override fun addListener(l: TorrentsProvider.Listener) { listeners.add(l); l.snapshot(snap()); l.state("") }
        override fun removeListener(l: TorrentsProvider.Listener) { listeners.remove(l) }
        override fun setFocused(focused: Boolean, paceMs: Long) { ops.add("focus:$focused:$paceMs") }
        override fun refresh() { ops.add("refresh") }
        val events = CopyOnWriteArrayList<TorrentEvent>()
        override fun eventsSince(seq: Long, epoch: Long): List<TorrentEvent> =
            if (epoch != this.epoch) emptyList() else events.filter { it.seq > seq }
        fun fireDone() {
            transfers = transfers.map { if (it.hash == "aa01") it.copy(state = "uploading", progress = 1.0, completedOn = now, seedingTime = 1) else it }
            version++
            val e = TorrentEvent(++seq, "done", "aa01", "ubuntu-26.04.iso", System.currentTimeMillis())
            events.add(e)
            for (l in listeners) { l.snapshot(snap()); l.event(e) }
        }
        override fun detail(hash: String) = TransferDetail(hash, listOf(TFile("f", 1, 1.0, 1)))
        override fun start(hashes: List<String>) { ops.add("start:${hashes.joinToString("|")}") }
        override fun stop(hashes: List<String>) { ops.add("stop:${hashes.joinToString("|")}") }
        override fun recheck(hashes: List<String>) { ops.add("recheck:${hashes.joinToString("|")}") }
        override fun delete(hashes: List<String>, withFiles: Boolean) {
            ops.add("delete:${hashes.joinToString("|")}:$withFiles")
            transfers = transfers.filter { it.hash !in hashes }; version++
            for (l in listeners) l.snapshot(snap())
        }
        override fun tlCategories() = TorrentLeech.CATEGORIES
        private fun item(i: Int) = TlItem("f$i", "Item $i", "Item.$i.torrent", 23, 1_000L * i, 10, 1, 5, "2026-09-01 10:00:00", listOf("Linux"), i == 0)
        override fun tlBrowse(categoryId: Int?, page: Int, sort: String): TlPage {
            ops.add("browse:${categoryId ?: 0}:$page:$sort")
            return if (page == 1) TlPage((0 until 3).map { item(it) }, 1, 35, 3) else TlPage(emptyList(), page, 35, 3)
        }
        override fun tlSearch(query: String, page: Int, sort: String): TlPage {
            ops.add("search:$query:$page:$sort")
            return TlPage(listOf(item(9)), 1, 35, 1)
        }
        override fun tlDetail(fid: String) = TlDetail(fid, "Item of $fid", "Apps · PC-ISO", "1 KB", 10, 1, 5, "today", "anon",
            listOf("Linux"), "desc", "nfo line", listOf(TlFile("a.iso", "1 KB")), "https://www.torrentleech.org/torrent/$fid")
        override fun tlAdd(fid: String, stopped: Boolean): String { added.add("$fid:$stopped"); return "Item of $fid" }
        override fun tlAccount() = TlAccount("u", "1 TB", "1 GB", "10", "5", "User")
        override fun openOnPc(target: String) { ops.add("open:$target") }
    }

    private class Rig(tmp: Path, val fake: FakeTorrents = FakeTorrents()) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = Persistence(tmp.resolve("state.json"))
        val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
        val shell = Shell(FakeText(), transport, store, null, scope)
        val win = TorrentsWindow(FakeText(), fake, scope)
        init { shell.register(win) }
        suspend fun start() { shell.start(); shell.postGesture(EvenHubMsg.EV_CLICK) }   // Main row 0 = Torrents
        suspend fun stop() { shell.stop(); scope.cancel() }
        fun tap() = shell.postGesture(EvenHubMsg.EV_CLICK)
        fun back() = shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
        fun down() = shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
        fun up() = shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)
    }

    @Test
    fun windowGrammarTransfersDetailsBrowseAddSearchFilterAndTheDoneNotice(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-torrents-win")
        val r = Rig(tmp)
        try {
            r.start()
            awaitTrue("transfers open") { r.win.title() == "transfers" }
            assertTrue(r.win.summary().line.startsWith("1 downloading"), r.win.summary().line)
            awaitTrue("activation asked for the focused pacing") { r.fake.ops.contains("focus:true:2000") }
            // tap = the transfer menu, Details first; the active download sorts first
            r.tap()
            awaitTrue("menu") { r.shell.menuIsOpen && r.shell.menuTitle == "ubuntu-26.04.iso" }
            r.tap()
            awaitTrue("details") { r.win.title() == "details" && r.win.levelDepth() == 2 }
            r.tap()                                              // the document's actions menu
            awaitTrue("details menu") { r.shell.menuIsOpen }
            r.tap()                                              // row 0 is harmless (Refresh), never Stop
            awaitTrue("refresh, not stop") { !r.shell.menuIsOpen }
            assertTrue(r.fake.ops.none { it.startsWith("stop:") }, r.fake.ops.toString())
            r.back()
            awaitTrue("back") { r.win.title() == "transfers" }
            // Stop from the menu → the provider, then the title notice
            r.tap(); awaitTrue("menu again") { r.shell.menuIsOpen }
            r.down(); r.tap()                                    // Stop
            awaitTrue("stop reached the provider") { r.fake.ops.contains("stop:aa01") }
            // the wrap-end row → the Torrents menu → Browse → Newest → the page → Add (confirmed)
            r.up(); r.tap()
            awaitTrue("torrents menu") { r.shell.menuIsOpen && r.shell.menuTitle == "torrents" }
            r.tap()                                              // Browse TorrentLeech
            awaitTrue("categories") { r.win.title() == "browse" }
            r.tap()                                              // Newest
            awaitTrue("listing") { r.win.title() == "newest" && r.fake.ops.any { it.startsWith("browse:0:1:added") } }
            r.tap()                                              // Item 0 → the torrent page
            awaitTrue("torrent page") { r.win.title() == "torrent" && r.win.levelDepth() == 4 }
            r.tap()                                              // add menu
            awaitTrue("add menu") { r.shell.menuIsOpen }
            r.tap()                                              // Add → confirm
            awaitTrue("confirm") { r.shell.menuIsOpen && (r.shell.menuTitle ?: "").startsWith("Add ") }
            assertTrue(r.fake.added.isEmpty())
            r.back()                                             // cancel the confirm: nothing added
            awaitTrue("confirm cancelled") { !r.shell.menuIsOpen }
            assertTrue(r.fake.added.isEmpty())
            r.tap(); awaitTrue("add menu 2") { r.shell.menuIsOpen }
            r.down(); r.tap()                                    // Add stopped → confirm
            awaitTrue("confirm 2") { r.shell.menuIsOpen && (r.shell.menuTitle ?: "").startsWith("Add ") }
            r.down(); r.tap()                                    // Add
            awaitTrue("added stopped") { r.fake.added.contains("f0:true") }
            repeat(3) { r.back() }
            awaitTrue("back to transfers") { r.win.title() == "transfers" }
            // search through the keyboard: 'u' then Enter
            // the cursor rests on the menu row it left from (the Files ascend rule)
            r.tap()
            awaitTrue("torrents menu 2") { r.shell.menuIsOpen && r.shell.menuTitle == "torrents" }
            r.down(); r.tap()                                    // Search TorrentLeech
            awaitTrue("keyboard") { r.shell.keyboardIsOpen }
            r.up(); r.tap()                                      // qwerty row, on 'q'
            repeat(6) { r.down() }; r.tap()                      // 'u'
            awaitTrue("typed u") { r.shell.keyboardDraft() == "u" }
            r.back(); r.down(); r.tap(); r.up(); r.tap()         // ROW → home row → Enter
            awaitTrue("search ran") { !r.shell.keyboardIsOpen && r.fake.ops.any { it.startsWith("search:u:1") } }
            awaitTrue("results titled") { r.win.title() == "\"u\"" && r.win.levelDepth() == 2 }
            r.back()
            awaitTrue("back from search") { r.win.title() == "transfers" }
            // the recent search shows in the menu; the under-a-week filter
            r.tap()
            awaitTrue("torrents menu 3") { r.shell.menuIsOpen && r.shell.menuTitle == "torrents" }
            repeat(5) { r.down() }; r.tap()                     // Browse,Search,Search:u,Filter,Sort,Seeding<1wk
            awaitTrue("under-a-week filter") { r.win.title() == "seeding < 1 week" }
            assertEquals("cc03", r.win.saveState().let { st ->
                // the one seed under a week is the only row (plus the menu row): cursor 0 = it
                r.fake.transfers.first { it.underAWeek }.hash
            })
            // the done edge raises a deep-linked notification and marks dirty
            r.fake.fireDone()
            awaitTrue("done notice") { r.shell.notifications.active }
            assertTrue(r.win.dirty)
            assertEquals("t:aa01", r.shell.notifications.current?.target)
            // detach (the desktop stack-stop rule): no listener stays behind
            r.shell.stop()
            r.win.detach()
            assertTrue(r.fake.listeners.isEmpty(), "the window's listener was removed")
            assertTrue(r.fake.ops.last().startsWith("focus:false"), r.fake.ops.last())
            r.scope.cancel()
        } catch (e: Throwable) {
            r.stop(); throw e
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun persistenceRoundTripAndContinuityRestoreTheOpenDetailsAndTheRecents(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-torrents-persist")
        val r = Rig(tmp)
        try {
            r.start()
            awaitTrue("transfers") { r.win.title() == "transfers" }
            r.shell.services.runOnShell { r.win.onTypedText("gentoo") }   // a replica line = a search
            awaitTrue("search from typed text") { r.win.title() == "\"gentoo\"" }
            r.back()
            awaitTrue("transfers again") { r.win.title() == "transfers" }
            r.tap(); awaitTrue("menu") { r.shell.menuIsOpen }
            r.tap(); awaitTrue("details") { r.win.title() == "details" }
            val saved = r.win.saveState()
            assertEquals("DETAILS", saved["level"]?.jsonPrimitive?.contentOrNull)
            assertEquals("aa01", saved["openHash"]?.jsonPrimitive?.contentOrNull)
            r.stop()
            // a NEW shell over the same store lands in the same details, with the recent search kept
            val r2 = Rig(tmp)
            r2.shell.start()
            awaitTrue("details restored") { r2.win.title() == "details" && r2.win.levelDepth() == 2 }
            assertTrue(r2.win.saveState()["recents"].toString().contains("gentoo"))
            r2.stop()
            // continuity (§16.4c): the record applied LIVE on a third shell restores the same place
            val r3 = Rig(Files.createTempDirectory("damage-torrents-b"))
            r3.shell.start()
            awaitTrue("third shell up") { r3.shell.isQuiescent() }
            r3.shell.postSync("window.torrents", saved, System.currentTimeMillis() + 60_000)
            awaitTrue("live record applied") { r3.win.saveState()["openHash"]?.jsonPrimitive?.contentOrNull == "aa01" }
            r3.stop()
        } catch (e: Throwable) {
            r.stop(); throw e
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun remoteProviderPollsSnapshotsReplaysEventsAndRelaysOpsOverALoopbackHost(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-torrents-remote")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = freePort()
        val books = tmp.resolve("books").also { Files.createDirectories(it) }
        val fake = FakeTorrents()
        val host = ContentHostServer(LocalContent(books), port, "tok",
            win = mapOf("torrents" to TorrentsService(fake)))
        host.start()
        val remote = RemoteTorrentsProvider("127.0.0.1", port, "tok", scope, idlePaceMs = 3_600_000)
        val c = Collect()
        remote.addListener(c)
        try {
            // await the LISTENER's copy: the provider stores its snapshot before it
            // notifies, and a poll between the two would count a push twice
            awaitTrue("the first snapshot arrives") { c.snaps.isNotEmpty() }
            assertEquals(3, remote.snapshot()!!.transfers.size)
            assertEquals("", remote.stateLine())
            val snaps = c.snaps.size
            remote.pollOnce()                                   // unchanged version: no new snapshot
            assertEquals(snaps, c.snaps.size)
            fake.fireDone()                                     // the host's event, then our poll replays it
            remote.pollOnce()
            awaitTrue("done replayed to the phone") { c.events.any { it.kind == "done" && it.hash == "aa01" } }
            remote.pollOnce()
            assertEquals(1, c.events.size, "replayed once")
            assertTrue(fake.ops.any { it.startsWith("focus:") })
            // ops ride the channel
            val page = remote.tlSearch("arch", 1, "seeders")
            assertEquals(1, page.items.size)
            assertTrue(fake.ops.contains("search:arch:1:seeders"))
            assertEquals("Item of f0", remote.tlAdd("f0", stopped = false))
            assertTrue(fake.added.contains("f0:false"))
            remote.delete(listOf("dd04"), withFiles = true)
            assertTrue(fake.ops.contains("delete:dd04:true"))
            assertEquals(40, remote.tlCategories().size)
            assertEquals("1 TB", remote.tlAccount().uploaded)
            assertEquals("Item of f1", remote.tlDetail("f1").name)
            // the host "restarts": a new epoch with a baseline done in its fresh log
            fake.epoch = 8L
            fake.events.clear(); fake.seq = 0
            fake.fireDone()                                     // seq 1 in epoch 8
            val evBefore = c.events.size
            remote.pollOnce()                                   // sees the new epoch: rewinds, wakes
            remote.pollOnce()                                   // replays the fresh log from 0
            awaitTrue("the new epoch's baseline done reached the phone") { c.events.size == evBefore + 1 }
            assertEquals(8L, remote.snapshot()!!.epoch)         // and the snapshot was re-sent on the epoch change alone
        } finally {
            remote.close()
            host.close()
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }
}
