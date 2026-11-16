package wm.damage.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import wm.damage.core.app.AppNet
import wm.damage.core.app.AppWire
import wm.damage.core.app.Change
import wm.damage.core.app.Levels
import wm.damage.core.app.Paged

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q2 (the spec's §2.2 and §5 V2; `CONTRACT.md` §9.3 "paged collections"): a page is its own scope
 * `<window>/<listing>/page:<n>`, answered `{count | null, version, from, size, rows, more}`; a listing's version moves
 * when a new read of a page differs from the last read of it, and every page of it a connection holds at an older
 * version is described again — whichever connection's read moved it. On the lane: pages written by hand against a
 * real web listener (`WebRig`), a made-up listing of the window `t`, two rows a page.
 */
class PagedTest {
    private fun row(s: String) = buildJsonObject { put("id", s); put("name", s) }

    @Test
    fun aPageScopeParsesAndAnswersItsShape() {
        assertEquals(Triple("files", "folder:/srv/a b/x", 3), Paged.parse("files/folder:/srv/a b/x/page:3"))
        assertEquals(Triple("torrents", "search:salt/sort:added", 0), Paged.parse(Paged.scope("torrents", "search:salt/sort:added", 0)))
        assertNull(Paged.parse("files/folder:/srv"))
        assertNull(Paged.parse("files/page:2"), "a listing is named")
        val p = Paged(pageRows = 2).describe("list:a", 1) { Paged.Read(listOf(row("c")), null, true) }
        assertEquals(JsonNull, p["count"], "an endless listing: count null")
        assertEquals(2L, p["from"]!!.jsonPrimitive.long)
        assertEquals(1L, p["size"]!!.jsonPrimitive.long)
        assertEquals(true, p["more"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(1L, p["version"]!!.jsonPrimitive.long)
        assertEquals("c", p["rows"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun aListingsVersionMovesOnlyWhenAReadOfAPageDiffers() {
        val paged = Paged(pageRows = 2)
        val moved = CopyOnWriteArrayList<String>()
        paged.addListener { moved += it }
        fun v(n: Int, vararg rows: String) =
            paged.describe("list:a", n) { Paged.Read(rows.map { row(it) }, 9, true) }["version"]!!.jsonPrimitive.long
        assertEquals(1, v(0, "a", "b"), "a first read")
        assertEquals(1, v(1, "c", "d"), "another page's first read moves nothing")
        assertEquals(1, v(0, "a", "b"), "the same read again")
        assertEquals(2, v(0, "a", "x"), "a read that differs")
        assertEquals(listOf("list:a"), moved)
        assertEquals(2L, paged.version("list:a"))
        assertNull(paged.version("list:b"))
    }

    // ---- on the lane ----

    /** A made-up listing of `t`, two rows a page, read whole (its signature the whole listing). */
    private class Listing : AppNet.Described {
        @Volatile var rows = listOf("a", "b", "c", "d")
        val paged = Paged(pageRows = 2)
        override fun gate(scope: String) = Paged.parse(scope)?.takeIf { it.first == "t" }?.let { AppNet.Gate("t", Levels.VIEW) }
        override fun describe(scope: String): JsonObject? {
            val (_, listing, n) = Paged.parse(scope) ?: return null
            val all = rows
            val page = all.drop(n * 2).take(2).map { s -> buildJsonObject { put("id", s) } }
            return paged.describe(listing, n) { Paged.Read(page, all.size, all.size > (n + 1) * 2, signature = all.joinToString()) }
        }
        override fun follows(scope: String, c: Change?) = false
        override fun addListener(l: () -> Unit) = paged.addListener { l() }
        override fun version(scope: String): Long? = Paged.parse(scope)?.let { paged.version(it.second) }
    }

    private fun WebRig.page(name: String): Page {
        val (_, secret) = approved(Levels.VIEW, name)
        val p = Page(port, secret)
        p.hello(stream = name)
        p.next("welcome")
        return p
    }

    private fun Page.sub(scope: String) = send(buildJsonObject { put("t", "sub"); put("scope", scope) })

    /** The next description of [scope] at or past [version]. */
    private fun Page.page(scope: String, version: Long = 1): JsonObject {
        val until = System.currentTimeMillis() + 10_000
        val seen = ArrayList<String>()
        while (System.currentTimeMillis() < until) {
            val f = frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (AppWire.type(f) == "snap" && f["scope"]?.jsonPrimitive?.content == scope) {
                val d = f["desc"]!!.jsonObject
                if (d["version"]!!.jsonPrimitive.long >= version) return d
            }
            seen += f.toString().take(120)
        }
        throw AssertionError("no description of $scope at version $version or later within 10 s; seen $seen")
    }

    private fun JsonObject.ids() = this["rows"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    /** Pin (the spec's Q2: "a moved listing re-sends no held page"): a read on ANOTHER connection moves the listing,
     *  and both pages this connection holds come again at the new version. */
    @Test
    fun aMovedListingSendsEveryHeldPageAgain() = WebRig().use { r ->
        val src = Listing()
        r.net.serve("t", scopes = src)
        val a = r.page("page-a")
        a.sub("t/list:a/page:0")
        assertEquals(listOf("a", "b"), a.page("t/list:a/page:0").ids())
        a.sub("t/list:a/page:1")
        assertEquals(listOf("c", "d"), a.page("t/list:a/page:1").ids())
        src.rows = listOf("a", "x", "c", "d", "e")
        val b = r.page("page-b")
        b.sub("t/list:a/page:0")
        assertEquals(listOf("a", "x"), b.page("t/list:a/page:0", 2).ids(), "the other connection's read moves the version")
        assertEquals(listOf("a", "x"), a.page("t/list:a/page:0", 2).ids(), "the held page 0 comes again")
        val p1 = a.page("t/list:a/page:1", 2)
        assertEquals(listOf("c", "d"), p1.ids(), "the held page 1 comes again at the new version")
        assertEquals(5L, p1["count"]!!.jsonPrimitive.long)
        assertTrue(p1["more"]!!.jsonPrimitive.content.toBoolean())
        a.close(); b.close()
    }
}
