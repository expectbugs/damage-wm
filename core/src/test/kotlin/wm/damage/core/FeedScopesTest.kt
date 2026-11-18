package wm.damage.core

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wm.damage.core.app.AppHost
import wm.damage.core.app.CoreExecutor
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.app.Peer
import wm.damage.core.app.ProgramResult
import wm.damage.core.web.Blob
import wm.damage.core.windows.feed.FeedCore
import wm.damage.core.windows.feed.FeedScopes
import wm.damage.core.windows.feed.ItemKind
import wm.damage.core.windows.feed.ScriptedFeed
import wm.damage.core.windows.feed.SourceKind
import wm.damage.core.windows.feed.SourceStatus

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-3, piece B2 (`CONTRACT.md` §3, §9.3, §9.4): Feed's PC-served scopes over the scripted feed — the description
 * with its sources, counts and rows; a page of items at the source's version; an article as C§3.2's document with its
 * picture a blob the page may ask for; a comic's neighbours (xkcd's numbers, SMBC's list); the archive around an
 * episode; the program ops; a source's health as values. No site is reached.
 */
class FeedScopesTest {
    private val dir = Files.createTempDirectory("damage-feed-scopes")
    private val host = AppHost(Files.createDirectories(dir.resolve("pc")), AppHost.WINDOWS, CoreExecutor("damage-core-feed-scopes")).also { it.open() }
    private val feed = ScriptedFeed(nowMs = 1_763_000_000_000L)
    private val scopes = FeedScopes(host, feed, clock = { feed.nowMs })

    @AfterTest
    fun tearDown() {
        scopes.close()
        host.close()
        host.executor.shutdown()
        dir.toFile().deleteRecursively()
    }

    private fun d(scope: String): JsonObject? =
        (if (scopes.table.gate(scope) != null) scopes.table else assertNotNull(scopes.reading.gate(scope)).let { scopes.reading }).describe(scope)

    private fun op(action: String, targets: List<String>, params: JsonObject = JsonObject(emptyMap())) =
        Op("web-00feed", OpId.Conn(1, 1), FeedCore.WINDOW, action, targets, params = params, at = feed.nowMs)

    @Test
    fun theDescriptionAPageAndAnArticle() {
        val desc = assertNotNull(d(FeedScopes.DESC))
        assertEquals("live", desc["availability"]!!.jsonPrimitive.content)
        val srcs = desc["sources"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("popular", "slashdot", "xkcd", "smbc", "8bt"), srcs.map { it["id"]!!.jsonPrimitive.content })
        assertEquals(30, srcs[0]["newCount"]!!.jsonPrimitive.content.toInt(), "nothing seen, nothing read: every item of the first page")
        assertEquals("ok", srcs[0]["health"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        assertEquals(12, srcs[4]["bingeTotal"]!!.jsonPrimitive.content.toInt())
        assertTrue(desc["actions"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == FeedCore.A_MARK_ALL })
        val rows = desc["settings"]!!.jsonArray.map { it.jsonObject["label"]!!.jsonPrimitive.content }
        assertEquals(listOf("Size (glasses)", "Images", "xkcd art", "SMBC art", "8-Bit art", "Comic levels", "Fetch", "Keep",
            "Notify · Reddit", "Notify · Slashdot", "Notify · comics"), rows, "Settings → Feed without `PC loss`")
        // a page: 50 rows at a time, at the source's version
        val page = assertNotNull(d("feed/src:popular/page:0"))
        assertEquals(30, page["count"]!!.jsonPrimitive.content.toInt())
        assertEquals(30, page["size"]!!.jsonPrimitive.content.toInt())
        assertEquals("false", page["more"]!!.jsonPrimitive.content)
        assertEquals(1L, scopes.table.version("feed/src:popular/page:0"))
        feed.fireNew("popular")
        assertEquals(2L, scopes.table.version("feed/src:popular/page:0"), "a held page is described again when its source moves")
        assertNull(d("feed/src:nope/page:0"))
        // an article: Damage's own blocks as C§3.2's kinds, its picture a blob of this process
        val link = feed.items("popular", 0, 40).items.first { it.kind == ItemKind.LINK && !it.title.startsWith("Breaking") }
        val art = assertNotNull(d("feed/item:popular:${link.id}"))
        val doc = art["document"]!!.jsonObject
        assertEquals(listOf("heading", "para", "image", "para", "quote", "item", "item", "para"),
            doc["blocks"]!!.jsonArray.map { it.jsonObject["kind"]!!.jsonPrimitive.content })
        val para = doc["blocks"]!!.jsonArray[1].jsonObject
        assertEquals(1, para["spans"]!!.jsonArray.size, "a block with no spans is one plain span")
        val marked = doc["blocks"]!!.jsonArray[3].jsonObject["spans"]!!.jsonArray.map { it.jsonObject }
        assertTrue(marked.any { it["href"]?.jsonPrimitive?.content == "https://example.org/thought" &&
            it["marks"]!!.jsonArray.map { m -> m.jsonPrimitive.content } == listOf("link") })
        assertTrue(marked.any { it["marks"]!!.jsonArray.map { m -> m.jsonPrimitive.content } == listOf("strong") })
        assertEquals(link.target, doc["sourceUrl"]!!.jsonPrimitive.content)
        assertEquals("1", doc["extractor"]!!.jsonPrimitive.content)
        val ref = doc["blocks"]!!.jsonArray[2].jsonObject["ref"]!!.jsonPrimitive.content
        assertTrue(ref.startsWith("feed."))
        val blob = assertNotNull(scopes.blobs.resolve(ref)) as Blob
        assertEquals("image/png", blob.type, "the type from the bytes")
        assertNull(scopes.blobs.resolve("feed.0123456789abcdef0123"), "a key no scope handed out names nothing")
        assertEquals(true, art["item"]!!.jsonObject["hasComments"]!!.jsonPrimitive.content.toBoolean())
        // comments as a thread
        val cs = assertNotNull(d("feed/comments:popular:${link.id}"))
        assertEquals(6, cs["entries"]!!.jsonArray.size)
        assertNull(d("feed/item:popular:0000000000000000"), "an item the engine does not hold, and no flag, names nothing")
    }

    @Test
    fun comicsTheArchiveAndTheProgramOps() {
        // xkcd: numbered; the latest has no next
        val latest = feed.items("xkcd", 0, 1).items[0]
        val c = assertNotNull(d("feed/comic:xkcd:${latest.id}"))
        val nb = c["neighbours"]!!.jsonObject
        assertEquals("num:xkcd:3295", nb["prev"]!!.jsonObject["target"]!!.jsonPrimitive.content)
        assertEquals("num:xkcd:1", nb["first"]!!.jsonObject["target"]!!.jsonPrimitive.content)
        assertTrue(nb["next"]!!.jsonObject.containsKey("dim"))
        assertEquals(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(3296))), nb["random"]!!.jsonObject["range"])
        assertNotNull(d("feed/num:xkcd:1000"))
        assertNull(d("feed/num:xkcd:3290"), "a missing number names nothing")
        // SMBC: by list position, its bonus panel a blob
        val smbc = feed.items("smbc", 0, 2).items
        val s = assertNotNull(d("feed/comic:smbc:${smbc[1].id}"))
        assertEquals("item:smbc:${smbc[0].id}", s["neighbours"]!!.jsonObject["next"]!!.jsonObject["target"]!!.jsonPrimitive.content)
        assertTrue(s["bonus"]!!.jsonPrimitive.content.startsWith("feed."))
        // the archive: 3 before and 5 after, clamped at the start
        val b = assertNotNull(d("feed/binge:8bt/around:1"))
        assertEquals((1..6).toList(), b["entries"]!!.jsonArray.map { it.jsonObject["num"]!!.jsonPrimitive.content.toInt() })
        assertEquals(12, b["total"]!!.jsonPrimitive.content.toInt())
        assertEquals("ready", b["entries"]!!.jsonArray[0].jsonObject["state"]!!.jsonPrimitive.content)
        val mid = assertNotNull(d("feed/binge:8bt/around:7"))
        assertEquals((4..12).toList(), mid["entries"]!!.jsonArray.map { it.jsonObject["num"]!!.jsonPrimitive.content.toInt() })
        // program ops: refresh (sources known now), browse → the source id
        assertTrue(scopes.program.act(op(FeedCore.A_REFRESH, listOf("popular")), Peer.LOCAL) is ProgramResult.Sent)
        assertTrue("refresh:popular" in feed.ops)
        assertTrue(scopes.program.act(op(FeedCore.A_REFRESH, listOf("nope")), Peer.LOCAL) is ProgramResult.Refused)
        val br = scopes.program.act(op(FeedCore.A_BROWSE, emptyList(), buildJsonObject { put("kind", "reddit"); put("name", "linux") }), Peer.LOCAL)
        assertEquals(JsonPrimitive("r:linux"), (br as ProgramResult.Sent).result)
        assertTrue(scopes.program.act(op(FeedCore.A_BROWSE, emptyList(), buildJsonObject { put("kind", "nope") }), Peer.LOCAL) is ProgramResult.Refused)
        // a browsed source's id holds a ':': its items are still named
        val bid = feed.items("r:linux", 0, 1).items[0].id
        assertNotNull(d("feed/item:r:linux:$bid"))
    }

    @Test
    fun aSourcesHealthIsValuesNotText() {
        val now = 1_000_000L
        fun st(retryAt: Long = 0, host: String = "", since: Long = 0, line: String = "") =
            SourceStatus("x", SourceKind.REDDIT, "x", false, false, 0, 1, 0, 0, "", retryAtMs = retryAt, retryHost = host, failSinceMs = since, failLine = line)
        assertEquals(buildJsonObject { put("state", "ok") }, FeedCore.health(st(), now))
        assertEquals(buildJsonObject { put("state", "limited"); put("retryAtMs", now + 60_000); put("host", "reddit.com") },
            FeedCore.health(st(retryAt = now + 60_000, host = "reddit.com"), now))
        assertEquals(buildJsonObject { put("state", "failing"); put("failLine", "HTTP 503"); put("failSinceMs", now - 5_000) },
            FeedCore.health(st(since = now - 5_000, line = "HTTP 503"), now))
    }
}
