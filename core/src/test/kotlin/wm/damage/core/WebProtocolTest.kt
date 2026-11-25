package wm.damage.core

import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wm.damage.core.app.AppHost
import wm.damage.core.app.AppNet
import wm.damage.core.app.AppWire
import wm.damage.core.app.Change
import wm.damage.core.app.DevicesCore
import wm.damage.core.app.Levels
import wm.damage.core.app.PcScopes
import wm.damage.core.content.LocalContent
import wm.damage.core.net.ReachGate
import wm.damage.core.net.Side
import wm.damage.core.web.Access
import wm.damage.core.web.Bundle
import wm.damage.core.web.Devices
import wm.damage.core.web.FontSource
import wm.damage.core.web.ReaderBlobs
import wm.damage.core.web.WebServer
import wm.damage.core.windows.reader.EpubDoc
import wm.damage.core.windows.reader.ReaderCore
import wm.damage.core.windows.reader.ReaderDocs
import wm.damage.core.windows.reader.ReaderIndex

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P7 (the spec's §2 P7 "Tests"; CONTRACT.md §9.2, §10.5; decisions A.2, E.6): a page's messages
 * played on loopback by a WebSocket written by hand ([Page]) against a real web listener over a real record, Reader's
 * core and the PC-served scopes (`PcScopes`): Main's cards, the shelf, a book's level and one part; a place as a
 * position op and Main following it; a moved place that loses once and then applies; the level and contract checks of
 * a PC-served scope at apply time; a slow description that never holds the record; addresses and the glasses' faces.
 * Port 0 on loopback, temp directories, made-up books; never a browser, never the service's port or data.
 */
class WebProtocolTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closing = ArrayList<AutoCloseable>()

    @AfterTest
    fun cleanUp() {
        for (c in closing.asReversed()) try { c.close() } catch (e: Exception) { /* the next closes all the same */ }
        scope.cancel()
    }

    private val png = ByteArray(64) { (it * 7).toByte() }

    /** A PC: the record, Reader's core and index over made-up books, the device table, the lane, the listener. */
    private inner class Rig(provider: ((Rig) -> AppNet.Described)? = null) : AutoCloseable {
        val reader = ReaderRig(scope).also { closing += it }
        val txt = reader.writeBooks()
        val epub = EpubFixture.write(reader.books.resolve("fixture.epub"), listOf(
            "c1.xhtml" to "<h1>One</h1><p>It was a <em>dark</em> night.</p><img src=\"img/a.png\"/>",
            "c2.xhtml" to "<p>Second chapter with <a href=\"https://example.org/x\">a link</a>.</p>"),
            images = mapOf("img/a.png" to png, "img/cover.png" to png), titles = listOf("First", "Second"), coverMeta = "img1")
        val book = LocalContent.idFor(epub)
        val host: AppHost = reader.pc()
        val index = ReaderIndex(null, scope)
        val core = ReaderCore(LocalContent(reader.books), scope, index)
        val docs = ReaderDocs(core::pathOf)
        val dir = Files.createTempDirectory("damage-web-p7")
        val devices = Devices(dir.resolve(Devices.FILE), clock = { reader.now.get() }).load()
        val net = AppNet(host, devices = DevicesCore(devices), clock = { reader.now.get() })
        val face = Files.write(dir.resolve("face.ttf"), byteArrayOf(0, 1, 0, 0, 4, 2))
        val server: WebServer

        init {
            net.attach(provider?.invoke(this) ?: PcScopes(host, core, docs,
                names = { devices.rows().associate { it.id to it.name } },
                diag = { buildJsonObject {
                    put("build", "fixture build")
                    put("connections", JsonArray(net.connectionLines().map { JsonPrimitive(it) }))
                } }))
            // the fixture's faces: one file for any name, so only the listener's own list decides a name
            server = WebServer(0, ReachGate(Side.PC), Access(listOf("localhost", "127.0.0.1")), devices, net, BUNDLE,
                ReaderBlobs(core, docs), fonts = FontSource { face }, bindAddress = InetAddress.getLoopbackAddress(),
                clock = { reader.now.get() }).also { it.start() }
            core.refreshLibrary()
            eventually("the shelf scanned and indexed") { core.library.size == 3 && core.library.all { index.entry(it.file, it.bytes) != null } }
        }

        val port: Int get() = server.boundPort

        fun approved(level: String, name: String): Pair<Devices.Row, String> {
            val w = WebRig.Waiting()
            val p = devices.request("127.0.0.1", null, name, "browser", null, w)
            assertEquals(null, devices.approve(p.code, level, "local"))
            val r = devices.redeem(w.claims.poll(10, TimeUnit.SECONDS)!!)!!
            return r.row to r.secret
        }

        /** A page at [level], past its welcome. */
        fun page(level: String, contracts: Map<String, Int> = mapOf("reader" to 1), stream: String = "s-$level"): Pair<Page, JsonObject> {
            val (_, secret) = approved(level, "fixture $level page")
            val p = Page(port, secret).also { closing += it }
            assertEquals(101, p.upgrade.code)
            p.send(buildJsonObject {
                put("t", "app"); put("proto", AppWire.PROTO); put("stream", stream)
                put("contracts", buildJsonObject { for ((w, v) in contracts) put(w, v) })
            })
            return p to p.next("welcome")
        }

        override fun close() {
            server.close()
            net.close()
            dir.toFile().deleteRecursively()
        }
    }

    private fun Rig.closeLater() = also { closing += it }

    /** The next frame [pred] takes, within 10 s; the frames it skips are kept for the failure's message. */
    private fun Page.await(what: String, pred: (JsonObject) -> Boolean): JsonObject {
        val skipped = ArrayList<String>()
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            val f = frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (pred(f)) return f
            skipped += f.toString().take(200)
        }
        throw AssertionError("no frame for '$what' within 10 s; skipped: $skipped")
    }

    private fun Page.desc(scope: String): JsonObject {
        send(buildJsonObject { put("t", "sub"); put("scope", scope) })
        return await("desc $scope") { it.str("t") == "snap" && it.str("scope") == scope && "desc" in it }["desc"]!!.jsonObject
    }

    private fun Page.refusal(scope: String): JsonObject {
        send(buildJsonObject { put("t", "sub"); put("scope", scope) })
        return await("refusal of $scope") { it.str("t") == "ans" && it.str("scope") == scope }["answer"]!!.jsonObject
    }

    private fun op(stream: String, n: Long, action: String, target: String, result: JsonElement, expect: JsonObject) = buildJsonObject {
        put("t", "op")
        put("op", buildJsonObject {
            put("device", ""); put("stream", stream); put("n", n); put("window", "reader"); put("action", action)
            put("targets", buildJsonArray { add(JsonPrimitive(target)) }); put("result", result); put("expect", expect)
        })
    }

    private fun Page.answer(stream: String, n: Long): JsonObject =
        await("answer $stream/$n") { it.str("t") == "ans" && it.str("stream") == stream && it["n"]?.jsonPrimitive?.content == "$n" }["answer"]!!.jsonObject

    private fun value(v: String?) = buildJsonObject { put("value", v?.let { JsonPrimitive(it) } ?: JsonNull) }

    /**
     * S1-5 W: `desc/settings` holds every setting — Settings' own rows by category, each window's own rows (its registry
     * entry's hook) first in its app's category with their window and item, a device's own row left out — and a page's
     * `set` reaches the record and comes back: in the description and in the PC's fold (what its notices read). A
     * change to an item no row reads (a Reader place, a Games world) does not describe it again; a row's item does.
     */
    @Test
    fun theSettingsDescriptionHoldsEveryRowAndAPagesSetComesBackInIt() {
        val rig = Rig().closeLater()
        val (page, _) = rig.page(Levels.CONTROL, contracts = mapOf("reader" to 1, "settings" to 1), stream = "s-set")
        val cats = page.desc(PcScopes.SETTINGS)["categories"]!!.jsonArray.map { it.jsonObject }
        fun rows(cat: String) = cats.first { it.str("name") == cat }["rows"]!!.jsonArray.map { it.jsonObject }
        assertTrue(cats.map { it.str("name") }.containsAll(listOf("Global", "Animations · Scroll", "Bastion", "Reader", "Tmux", "Files",
            "Torrents", "Feed", "Music", "Games")), "every category: ${cats.map { it.str("name") }}")
        val tmux = rows("Tmux")
        assertEquals(listOf(wm.damage.core.windows.tmux.TmuxCore.ALERTS, wm.damage.core.windows.tmux.TmuxCore.CONTEXT,
            wm.damage.core.windows.tmux.TmuxCore.SIZE), tmux.take(3).map { it.str("id") }, "Tmux's own rows first")
        assertTrue(tmux.take(3).all { it.str("window") == "tmux" && it.str("item") == it.str("id") })
        assertEquals(listOf("face", "scale", "style", "depth", "scrollEffect"), tmux.drop(3).map { it.str("field") }, "then its style rows")
        assertTrue(rows("Torrents").none { it.str("scope") == "device" }, "Torrents' Poll is each device's own")
        val music = rows("Music")
        val ownMusic = wm.damage.core.windows.music.MusicCore.ROWS.map { it.id }
        assertEquals(ownMusic, music.take(ownMusic.size).map { it.str("id") }, "Music's own rows first (S1-5 F)")
        assertTrue(music.take(ownMusic.size).all { it.str("window") == "music" && it.str("item") == it.str("id") && it["field"] == null })
        assertTrue(music.drop(ownMusic.size).let { it.isNotEmpty() && it.all { r -> r["field"] != null } }, "then its style rows")
        val reader = rows("Reader").filter { it["field"] == null }
        assertEquals(listOf("choice", "bool", "choice"), reader.map { it.str("type") }, "Reader's rows carry their type")
        assertEquals("8 lines", reader[0]["options"]!!.jsonArray.last().jsonObject.str("label"))
        assertEquals("games", rows("Games").first().str("window"))
        val quiet = rows("Global").first { it.str("id") == "quietHours" }
        assertEquals("off", quiet.str("value"))
        // a page's set: answered, described again with it, and the PC's fold holds it
        page.send(buildJsonObject {
            put("t", "op")
            put("op", buildJsonObject {
                put("device", ""); put("stream", "s-set"); put("n", 1); put("window", "settings"); put("action", "set")
                put("targets", buildJsonArray { add(JsonPrimitive("quietHours")) }); put("result", "22-07")
            })
        })
        assertEquals("seen", page.answer("s-set", 1).str("a"))
        val again = page.await("desc/settings with the page's Quiet hours") { f ->
            f.str("t") == "snap" && f.str("scope") == PcScopes.SETTINGS && f["desc"]!!.jsonObject["categories"]!!.jsonArray.any { c ->
                c.jsonObject["rows"]!!.jsonArray.any { r -> r.jsonObject.str("id") == "quietHours" && r.jsonObject.str("value") == "22-07" } }
        }
        assertNotNull(again)
        assertEquals("22-07", wm.damage.core.shell.SettingsCore.onHost(rig.host).quietHours)
        // what it follows: Settings' items and the items of the windows' rows, nothing else
        val scopes = PcScopes(rig.host, rig.core, rig.docs)
        fun moves(key: String) = scopes.follows(PcScopes.SETTINGS, Change(1, 1, wm.damage.core.app.Effect(mapOf(key to
            wm.damage.core.app.Item(JsonPrimitive(1), "web", 1)))))
        assertTrue(moves("rec.settings.depth") && moves("rec.settings.app.tmux") && moves("rec.tmux.alerts") && moves("rec.games.glasses.size")
            && moves("rec.music.glasses.viz"))
        assertFalse(moves("rec.reader.place.${rig.book}") || moves("rec.games.world") || moves("rec.tmux.mute.pc/build") || moves("rec.music.q"))
    }

    @Test
    fun aPageReadsMainsCardsTheShelfABookAndOnePart() {
        val rig = Rig().closeLater()
        val (page, welcome) = rig.page(Levels.VIEW)
        assertNotNull(welcome["bundle"], "a web page's welcome carries the bundle")
        val main = page.desc(PcScopes.MAIN)
        val card = main["cards"]!!.jsonArray.single().jsonObject
        assertEquals("reader", card.str("window"))
        assertEquals("live", card.str("availability"))
        assertEquals("3 books", card["summary"]!!.jsonObject.str("line"))
        assertTrue(main["who"]!!.jsonObject.values.any { it.jsonPrimitive.content == "fixture view page" }, "the device names: $main")

        val books = page.desc(PcScopes.READER)["shelf"]!!.jsonObject["books"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, books.size)
        val ep = books.single { it.str("id") == rig.book }
        val text = EpubDoc.load(rig.epub).text
        assertEquals(text.length, ep["length"]!!.jsonPrimitive.content.toInt(), "a card carries its text length")
        assertEquals(EpubDoc.blobId(rig.book, 1), ep.str("cover"))

        val level = page.desc("reader/book:${rig.book}")
        assertEquals(listOf("First", "Second"), level["chapters"]!!.jsonArray.map { it.jsonObject.str("title") })

        val part = page.desc("reader/book:${rig.book}/part:1")
        assertEquals("document", part.str("kind"))
        assertEquals(rig.book, part.str("book"))
        val runs = part["blocks"]!!.jsonArray.flatMap { it.jsonObject["spans"]!!.jsonArray.map { s -> s.jsonObject } }
        assertEquals(text.substring(part.int("charStart"), part.int("charEnd")), runs.joinToString("") { it.str("text") },
            "a part's runs are the book's own text")
        assertEquals("https://example.org/x", runs.single { "href" in it }.str("href"))

        val none = page.refusal("reader/book:${rig.book}/part:9")
        assertEquals("unknown", none.str("code"), "a part that is not there is answered, never left silent: $none")
        assertEquals(403, http(rig.port, "GET", "/blob/${ep.str("cover")}").code, "a blob without the secret")
    }

    @Test
    fun aPlaceFromThePageIsAPositionOpAndMainsCardFollowsIt() {
        val rig = Rig().closeLater()
        val (page, welcome) = rig.page(Levels.VIEW, stream = "s1")
        val device = welcome.str("device")
        page.send(buildJsonObject { put("t", "sub"); put("scope", "reader") })
        page.await("the record's snapshot") { it.str("t") == "snap" && it.str("scope") == "reader" }
        assertEquals("3 books", page.desc(PcScopes.MAIN)["cards"]!!.jsonArray[0].jsonObject["summary"]!!.jsonObject.str("line"))

        page.send(op("s1", 1, "open", "recent", JsonPrimitive(rig.book), buildJsonObject { put("any", true) }))
        assertEquals("seen", page.answer("s1", 1).str("a"))
        val opened = page.await("Main described again after the open") {
            it.str("scope") == PcScopes.MAIN && it["desc"]?.jsonObject?.get("cards")?.jsonArray?.get(0)?.jsonObject?.get("summary")?.jsonObject?.get("book") != null
        }
        assertEquals(rig.book, opened["desc"]!!.jsonObject["cards"]!!.jsonArray[0].jsonObject["summary"]!!.jsonObject.str("book"))

        val text = EpubDoc.load(rig.epub).text
        val at = "@c${text.length / 2}.1~${text.substring(text.length / 2).take(32)}"
        page.send(op("s1", 2, "place", "place.${rig.book}", JsonPrimitive(at), value(null)))
        assertEquals("seen", page.answer("s1", 2).str("a"))
        val chg = page.await("the place's change") { it.str("t") == "chg" }
        val set = chg["patches"]!!.jsonArray.map { it.jsonObject }.single { it.str("addr") == "rec.reader.place.${rig.book}" }["set"]!!.jsonObject
        assertEquals(at, set.str("v"))
        assertEquals(device, set.str("by"), "the writer is the connection's device, never the op's word for it")
        page.await("Main's summary with the progress") {
            it.str("scope") == PcScopes.MAIN && it["desc"]!!.jsonObject["cards"]!!.jsonArray[0].jsonObject["summary"]!!.jsonObject.str("line").endsWith("%")
        }
    }

    /** L1 C1: the latest input wins — a page's op without its input time takes its arrival, so the page's place after
     *  the glasses' applies, and nothing is said. */
    @Test
    fun aPlaceFromThePageAfterTheGlassesIsTheLatestInputAndApplies() {
        val rig = Rig().closeLater()
        val (page, _) = rig.page(Levels.VIEW, stream = "s2")
        val target = "place.${rig.book}"
        page.send(op("s2", 1, "place", target, JsonPrimitive("@c10.1~"), value(null)))
        assertEquals("seen", page.answer("s2", 1).str("a"))
        // the glasses read on (device `local`, its own stream)
        assertEquals("Seen", rig.reader.otherDevicePlace(rig.host, "local", "glasses", 1, rig.book, 40).toString())
        page.send(op("s2", 2, "place", target, JsonPrimitive("@c20.1~"), value("@c10.1~")))
        assertEquals("seen", page.answer("s2", 2).str("a"))
        assertEquals(20, rig.reader.placeOffset(rig.host, rig.book))
    }

    @Test
    fun aPcServedScopeIsCheckedForLevelAndContractAtApplyTime() {
        val rig = Rig().closeLater()
        val (viewer, _) = rig.page(Levels.VIEW)
        val refused = viewer.refusal(PcScopes.DIAG)
        assertEquals(wm.damage.core.app.Answer.LEVEL, refused.str("code"), "the diagnosis is level full: $refused")
        val (full, _) = rig.page(Levels.FULL)
        val diag = full.desc(PcScopes.DIAG)
        assertEquals("fixture build", diag.str("build"))
        assertTrue(diag["connections"]!!.jsonArray.size >= 2, "both pages are listed: $diag")
        assertTrue("seq" in diag && "epoch" in diag && diag["log"]!!.jsonArray.isNotEmpty())
        val (other, _) = rig.page(Levels.FULL, contracts = mapOf("reader" to 99), stream = "s-old")
        assertEquals(wm.damage.core.app.Answer.CONTRACT, other.refusal(PcScopes.READER).str("code"))
        assertEquals(wm.damage.core.app.Answer.CONTRACT, other.refusal("reader/book:${rig.book}").str("code"))
        assertEquals("reader", other.desc(PcScopes.MAIN)["cards"]!!.jsonArray[0].jsonObject.str("window"),
            "Main's cards are the PC's own page, outside the contracts")
    }

    /**
     * Fix round 1, ruling 2.5: the diagnosis is level full (a control page is refused it as a view page is), and the
     * PC log's tail it serves carries none of the four secrets — the config token, the local secret, a pairing code,
     * a device secret (and the claim) — after every path that handles one has run with the fixture's own random values,
     * right and wrong: the content port's token and local secret, the pairing, a used claim, a cookie the table lacks.
     */
    @Test
    fun theDiagnosisIsLevelFull_andItsLogTailCarriesNoSecret() {
        val rig = Rig().closeLater()
        val rnd = java.security.SecureRandom()
        fun hex(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val token = hex(24)
        wm.damage.core.web.LocalSecret.ensure(rig.dir)
        val local = wm.damage.core.web.LocalSecret.read(rig.dir)!!
        val content = wm.damage.core.content.ContentHostServer(LocalContent(rig.reader.books), 0, token, app = rig.net,
            localSecret = { wm.damage.core.web.LocalSecret.read(rig.dir) }, bindAddress = InetAddress.getLoopbackAddress())
            .also { it.start(); closing += it }
        fun contentHello(tok: String, loc: String): String =
            java.net.Socket(InetAddress.getLoopbackAddress(), content.boundPort).use { s ->
                s.soTimeout = 10_000                             // a test's own bound on a stalled answer
                val o = java.io.DataOutputStream(s.getOutputStream().buffered())
                AppWire.write(o, AppWire.encode(buildJsonObject { put("t", "hello"); put("token", tok); put("local", loc); put("proto", 1) }))
                AppWire.write(o, AppWire.encode(AppWire.hello("s-diag-${hex(4)}", mapOf("reader" to 1))))
                AppWire.type(AppWire.read(java.io.DataInputStream(s.getInputStream().buffered())))
            }
        assertEquals("welcome", contentHello(token, ""), "the token")
        assertEquals("err", contentHello(hex(24), ""), "another token")
        assertEquals("welcome", contentHello("", local), "the local secret")
        assertEquals("err", contentHello("", local.reversed()), "another local secret")
        // the pairing: the code, the claim, the device secret; a used claim; a cookie the table does not hold
        val waiting = Page(rig.port).also { closing += it }
        waiting.hello(name = "fixture pairing page")
        val code = waiting.next("pending")["code"]!!.jsonPrimitive.content
        assertEquals(null, rig.devices.approve(code, Levels.FULL, "local"))
        val claim = waiting.next("approved")["claim"]!!.jsonPrimitive.content
        val redeemed = http(rig.port, "POST", "/pair/claim", mapOf("Origin" to "http://127.0.0.1:${rig.port}"), claim.toByteArray())
        assertEquals(200, redeemed.code)
        val secret = redeemed.header("set-cookie")!!.substringAfter("${Access.COOKIE}=").substringBefore(';')
        assertEquals(403, http(rig.port, "POST", "/pair/claim", mapOf("Origin" to "http://127.0.0.1:${rig.port}"), claim.toByteArray()).code)
        assertEquals(403, http(rig.port, "GET", "/blob/x", mapOf("Cookie" to "${Access.COOKIE}=${secret.reversed()}")).code)
        // fix round 2, ruling 2.4: a request line with no space (a broken client) is said by its length, never its
        // text: the replica's link carries the config token in its target, and both listeners read it with HttpWs
        java.net.Socket(InetAddress.getLoopbackAddress(), rig.port).use { s ->
            s.getOutputStream().apply { write("GET/live?token=$token\r\n\r\n".toByteArray(Charsets.US_ASCII)); flush() }
            eventually("the listener said the bad request line") { recentLog().any { "bad request line" in it } }
        }
        val (full, _) = Page(rig.port, secret).also { closing += it }.let { p ->
            p.send(buildJsonObject {
                put("t", "app"); put("proto", AppWire.PROTO); put("stream", "s-diag")
                put("contracts", buildJsonObject { put("reader", 1) })
            })
            p to p.next("welcome")
        }
        val log = full.desc(PcScopes.DIAG)["log"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(log.any { "rejected" in it && "bad token" in it } && log.any { "waiting for approval" in it },
            "the tail holds the lines those paths wrote: ${log.takeLast(20)}")
        for ((what, v) in listOf("config token" to token, "local secret" to local, "pairing code" to code,
                "claim" to claim, "device secret" to secret)) {
            assertTrue(log.none { v in it }, "the $what is in the diagnosis' log tail: ${log.filter { v in it }}")
            assertTrue(recentLog().none { v in it }, "the $what is in the PC log")
        }
        val (control, _) = rig.page(Levels.CONTROL, stream = "s-control")
        assertEquals(wm.damage.core.app.Answer.LEVEL, control.refusal(PcScopes.DIAG).str("code"), "the diagnosis is level full")
    }

    @Test
    fun aSlowDescriptionNeverHoldsTheRecord() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val rig = Rig { _ ->
            object : AppNet.Described {
                override fun gate(scope: String) = if (scope == "slow/one") AppNet.Gate(null, Levels.VIEW) else null
                override fun describe(scope: String): JsonObject {
                    started.countDown()
                    release.await(30, TimeUnit.SECONDS)
                    return buildJsonObject { put("slow", true) }
                }
                override fun follows(scope: String, c: Change?) = false
                override fun addListener(l: () -> Unit) {}
            }
        }.closeLater()
        try {
            val (page, _) = rig.page(Levels.VIEW, stream = "s3")
            page.send(buildJsonObject { put("t", "sub"); put("scope", "slow/one") })
            assertTrue(started.await(10, TimeUnit.SECONDS), "the description started")
            page.send(op("s3", 1, "place", "place.${rig.book}", JsonPrimitive("@c5.1~"), value(null)))
            assertEquals("seen", page.answer("s3", 1).str("a"), "an op is answered while a description is still being made")
            release.countDown()
            page.await("the slow description") { it.str("scope") == "slow/one" && "desc" in it }
        } finally {
            release.countDown()
        }
    }

    @Test
    fun theShelfIsDescribedAgainWhenTheScanMovesIt() {
        val rig = Rig().closeLater()
        val (page, _) = rig.page(Levels.VIEW)
        assertEquals(3, page.desc(PcScopes.READER)["shelf"]!!.jsonObject["books"]!!.jsonArray.size)
        Files.writeString(rig.reader.books.resolve("gamma.txt"), "GAMMA\n\nA fourth book.\n")
        rig.core.refreshLibrary()
        page.await("the shelf with the fourth book") {
            it.str("scope") == PcScopes.READER && it["desc"]!!.jsonObject["shelf"]!!.jsonObject["books"]!!.jsonArray.size == 4
        }
    }

    /** Fix round 2, ruling 2.1: the always-on service scans its shelf only at its start (it builds no presenter that
     *  would scan again); a page's subscription to the shelf asks for a scan, so a book added later reaches the web. */
    @Test
    fun aBookAddedAfterTheStartReachesTheShelfAtThePagesNextVisit() {
        val rig = Rig().closeLater()
        val (page, _) = rig.page(Levels.VIEW)
        assertEquals(3, page.desc(PcScopes.READER)["shelf"]!!.jsonObject["books"]!!.jsonArray.size)
        eventually("no scan in flight") { !rig.core.scanRunning() }
        Files.writeString(rig.reader.books.resolve("gamma.txt"), "GAMMA\n\nA fourth book.\n")
        // the shelf visited again; nothing else in the rig scans
        page.send(buildJsonObject { put("t", "sub"); put("scope", PcScopes.READER) })
        page.await("the shelf with the book added after the start") {
            it.str("scope") == PcScopes.READER && it["desc"]!!.jsonObject["shelf"]!!.jsonObject["books"]!!.jsonArray.size == 4
        }
        // the class's other site: Main's card counts the shelf too, and a page on Main never visits the shelf
        val (home, _) = rig.page(Levels.VIEW, stream = "s-home")
        eventually("no scan in flight") { !rig.core.scanRunning() }
        Files.writeString(rig.reader.books.resolve("delta.txt"), "DELTA\n\nA fifth book.\n")
        home.send(buildJsonObject { put("t", "sub"); put("scope", PcScopes.MAIN) })
        home.await("Main's card counting the fifth book") {
            it.str("scope") == PcScopes.MAIN && it["desc"]!!.jsonObject["cards"]!!.jsonArray.single().jsonObject["summary"]!!.jsonObject.str("line") == "5 books"
        }
    }

    @Test
    fun addressesAnswerThePageAndTheFacesWantAnApprovedDevice() {
        val rig = Rig().closeLater()
        val page = http(rig.port, "GET", "/w/reader/book:${rig.book}@c12.1~it%20was")
        assertEquals(200, page.code)
        assertContentEquals(INDEX, page.body, "an address answers the bundle's page")
        assertEquals("text/html; charset=utf-8", page.header("content-type"))
        val csp = page.header("content-security-policy")!!
        assertTrue("script-src 'self';" in csp && "style-src 'self' 'unsafe-inline';" in csp, csp)   // L1 AC28
        assertEquals(200, http(rig.port, "GET", "/w").code)
        assertEquals(404, http(rig.port, "GET", "/wx/reader").code, "only /w and below are addresses")

        assertEquals(403, http(rig.port, "GET", "/font/reader-regular").code, "a face wants an approved device")
        val (_, secret) = rig.approved(Levels.VIEW, "fonts")
        val cookie = mapOf("Cookie" to "${Access.COOKIE}=$secret")
        val face = http(rig.port, "GET", "/font/reader-regular", cookie)
        assertEquals(200, face.code)
        assertEquals("font/ttf", face.header("content-type"))
        assertContentEquals(Files.readAllBytes(rig.face), face.body)
        assertEquals(404, http(rig.port, "GET", "/font/../face.ttf", cookie).code)
        assertEquals(404, http(rig.port, "GET", "/font/serif-regular", cookie).code, "only the glasses' faces are served")
        assertFalse(WebServer.FONTS.contains("serif-regular"))
        val cover = http(rig.port, "GET", "/blob/${EpubDoc.blobId(rig.book, 1)}", cookie)
        assertEquals(200, cover.code)
        assertContentEquals(png, cover.body)
    }

    private companion object {
        val INDEX = "<!doctype html><title>fixture</title><script type=\"module\" src=\"/js/app.mjs\"></script>".toByteArray()
        val BUNDLE = Bundle(mapOf("index.html" to INDEX, "js/app.mjs" to "export {}".toByteArray()))

        fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.content ?: ""
        fun JsonObject.int(k: String): Int = this[k]!!.jsonPrimitive.content.toInt()
    }
}
