package wm.damage.core

import java.util.concurrent.CountDownLatch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import wm.damage.core.app.Levels
import wm.damage.core.windows.torrents.TorrentsCore

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q4 (the spec's §3.1, §3.3, §2.2–§2.4; S1-2 R0 §3): Torrents' PC-served parts as a page meets them
 * on the real lane — the description with typed fields in the contract's units and qBittorrent's own state word, the
 * tracker's paged listings and a search whose words travel encoded, the program ops at their levels, and the pace per
 * web viewer. Pages written by hand against a real web listener on loopback port 0 over a scripted provider
 * (`TestTorrents`): no request reaches qBittorrent, the tracker or any site.
 */
class TorrentsScopesTest {
    private val rig = TorrentsLaneRig()

    @AfterTest
    fun tearDown() = rig.close()

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content
    private fun desc(f: JsonObject) = f["desc"]!!.jsonObject

    @Test
    fun theDescriptionCarriesTheTableInTheContractsUnits() {
        val (page, _) = rig.page(Levels.VIEW)
        page.send(TorrentsLaneRig.sub("desc/torrents"))
        val d = desc(page.nextFor("snap", "desc/torrents"))
        assertEquals("live", d.s("availability"))
        assertEquals(4242L, d["epoch"]!!.jsonPrimitive.longOrNull)
        val rows = d["transfers"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("c0ffee01", "c0ffee02", "c0ffee03"), rows.map { it.s("id") })
        val dl = rows[0]["fields"]!!.jsonObject
        assertEquals("downloading", dl["state"]!!.jsonObject.s("raw"), "qBittorrent's own state beside the word (R0 §3)")
        assertEquals("1.2 MB/s", dl["state"]!!.jsonObject.s("word"))
        assertEquals(2_700_000L, dl["eta"]!!.jsonPrimitive.longOrNull, "an estimate in ms")
        assertEquals(1_799_990_000_000L, dl["added"]!!.jsonPrimitive.longOrNull, "an instant in epoch ms")
        assertEquals(JsonNull, dl["completed"], "incomplete: no completion")
        val seed = rows[1]["fields"]!!.jsonObject
        assertEquals(JsonNull, seed["eta"], "qBittorrent's infinite estimate is none")
        assertEquals(172_800_000L, seed["seedingTime"]!!.jsonPrimitive.longOrNull)
        assertEquals("stoppedDL", rows[2]["fields"]!!.jsonObject["state"]!!.jsonObject.s("raw"))
        val actions = d["actions"]!!.jsonArray.map { it.jsonObject }.associateBy { it.s("id") }
        assertEquals(Levels.FULL, actions.getValue("deleteFiles").s("level"))
        assertEquals(Levels.CONTROL, actions.getValue("delete").s("level"))
        assertEquals("true", actions.getValue("deleteFiles")["confirm"]!!.jsonObject.s("double"))
        val rowsSet = d["settings"]!!.jsonArray.map { it.jsonObject }.associateBy { it.s("id") }
        assertEquals("device", rowsSet.getValue("poll").s("scope"))
        assertEquals("0", rowsSet.getValue("glasses.size").s("value"), "global")
        assertEquals("1 downloading · 1.2 MB/s", d["summary"]!!.jsonObject.s("line"), "the glasses' Main row")
    }

    @Test
    fun theTrackersListingsAndASearchAreServedAPageAtATimeAtFull() {
        val (page, _) = rig.page(Levels.FULL)
        page.send(TorrentsLaneRig.sub("torrents/listing:newest/sort:added/page:1"))
        val p1 = desc(page.nextFor("snap", "torrents/listing:newest/sort:added/page:1"))
        assertEquals(35L, p1["from"]!!.jsonPrimitive.longOrNull)
        assertEquals("15", p1.s("size"), "the second page of fifty: fifteen rows")
        assertEquals("false", p1.s("more"))
        assertEquals("50", p1.s("count"), "the site's count when it gives one")
        assertTrue("browse:0:2:added" in rig.provider.ops, "page 1 is the tracker's page 2: ${rig.provider.ops}")
        val row = p1["rows"]!!.jsonArray[0].jsonObject
        assertEquals("tracker", row.s("kind"))
        assertEquals("240035", row.s("id"))
        // a search's words travel encoded: a slash and a space would split the scope
        page.send(TorrentsLaneRig.sub("torrents/search:AC%2FDC%20live+more/sort:seeders/page:0"))
        val s0 = desc(page.nextFor("snap", "torrents/search:AC%2FDC%20live+more/sort:seeders/page:0"))
        assertEquals("true", s0.s("more"))
        assertTrue("search:AC/DC live+more:1:seeders" in rig.provider.ops, "${rig.provider.ops}")
        page.send(TorrentsLaneRig.sub("torrents/tl:240007"))
        val tl = desc(page.nextFor("snap", "torrents/tl:240007"))
        assertEquals("true", tl.s("freeleech"))
        assertEquals("A made-up NFO line\n  and another", tl.s("nfo"))
        page.send(TorrentsLaneRig.sub("torrents/account"))
        assertEquals("Member", desc(page.nextFor("snap", "torrents/account")).s("klass"))
        page.send(TorrentsLaneRig.sub("torrents/categories"))
        assertTrue(desc(page.nextFor("snap", "torrents/categories"))["categories"]!!.jsonArray.size >= 40)
        // a sort the tracker does not take names nothing here
        page.send(TorrentsLaneRig.sub("torrents/listing:newest/sort:bogus/page:0"))
        assertEquals("unknown", page.nextFor("ans", "torrents/listing:newest/sort:bogus/page:0")["answer"]!!.jsonObject.s("code"))
    }

    /** Pin (the spec's Q4: "the tracker served below full"). */
    @Test
    fun theTrackerIsNotServedBelowFull() {
        val (page, _) = rig.page(Levels.CONTROL)
        for (scope in listOf("torrents/categories", "torrents/listing:newest/sort:added/page:0", "torrents/tl:240001",
                "torrents/account", "torrents/search:linux/sort:added/page:0")) {
            page.send(TorrentsLaneRig.sub(scope))
            val a = page.nextFor("ans", scope)["answer"]!!.jsonObject
            assertEquals("level", a.s("code"), "$scope at control: $a")
        }
        assertTrue(rig.provider.ops.none { it.startsWith("browse") || it.startsWith("search") || it.startsWith("page") },
            "nothing reached the tracker: ${rig.provider.ops}")
        page.send(TorrentsLaneRig.sub("torrents/t:c0ffee01"))
        assertEquals("c0ffee01", desc(page.nextFor("snap", "torrents/t:c0ffee01")).s("hash"), "a transfer's files at any level")
    }

    /** Pin (the spec's Q4: "delete with files at control"). */
    @Test
    fun deleteWithFilesIsRefusedOnThePcAtControl_deleteKeepingFilesRuns() {
        val (page, conn) = rig.page(Levels.CONTROL)
        page.send(TorrentsLaneRig.programOp(conn, 1, "deleteFiles", listOf("c0ffee02")))
        val a = page.answerOf(conn, 1)
        assertEquals("refused", a.s("a"), "$a")
        assertEquals("level", a.s("code"))
        page.send(TorrentsLaneRig.programOp(conn, 2, "delete", listOf("c0ffee02", "c0ffee03")))
        assertEquals("sent", page.answerOf(conn, 2).s("a"))
        assertEquals(listOf("delete:c0ffee02|c0ffee03:false"), rig.provider.ops.filter { it.startsWith("delete") })
    }

    @Test
    fun programOpsAreCheckedAgainstTheListAndAnsweredWithTheirResult() {
        val (page, conn) = rig.page(Levels.FULL)
        page.send(TorrentsLaneRig.programOp(conn, 1, "stop", listOf("c0ffee01", "gone0000")))
        val gone = page.answerOf(conn, 1)
        assertEquals("program", gone.s("code"), "$gone")
        assertTrue(gone.s("reason")!!.contains("gone0000"), "$gone")
        assertTrue(rig.provider.ops.none { it.startsWith("stop") }, "a stale hash stops nothing")
        page.send(TorrentsLaneRig.programOp(conn, 2, "addStopped", listOf("240009")))
        val add = page.answerOf(conn, 2)
        assertEquals("Made up release 240009", add["result"]!!.jsonObject.s("name"), "$add")
        assertTrue("add:240009:true" in rig.provider.ops)
        // one at a time: the calls run on the window's pool, so their answers may come in any order (`answerOf`
        // passes over the others)
        page.send(TorrentsLaneRig.programOp(conn, 3, "openOnPc", listOf("t:c0ffee02")))
        assertEquals("sent", page.answerOf(conn, 3).s("a"))
        page.send(TorrentsLaneRig.programOp(conn, 4, "openOnPc", listOf("tl:240009")))
        assertEquals("sent", page.answerOf(conn, 4).s("a"))
        page.send(TorrentsLaneRig.programOp(conn, 5, "openOnPc", listOf("t:not-in-the-list")))
        assertEquals("refused", page.answerOf(conn, 5).s("a"), "the PC opens only what qBittorrent or the tracker names")
        assertEquals(listOf("open:/srv/made-up/downloads/Another made-up release", "open:https://www.torrentleech.org/torrent/240009"),
            rig.provider.ops.filter { it.startsWith("open:") })
    }

    /** Pin (the spec's Q4: "a web viewer's end keeps the fast poll"). */
    @Test
    fun aWebViewerPollsFastWhileInFront_andItsEndReleasesIt() {
        val (page, conn) = rig.page(Levels.VIEW)
        page.send(TorrentsLaneRig.sub("desc/torrents", TorrentsLaneRig.focus(1_000)))
        page.nextFor("snap", "desc/torrents")
        assertEquals("web:$conn:true:1000", rig.focus.last(), "${rig.focus}")
        // hidden: the page's second sub replaces the need with none
        page.send(TorrentsLaneRig.sub("desc/torrents", TorrentsLaneRig.NONE))
        page.nextFor("snap", "desc/torrents")
        assertEquals("web:$conn:false:0", rig.focus.last(), "${rig.focus}")
        // in front again, then the connection ends
        page.send(TorrentsLaneRig.sub("desc/torrents", TorrentsLaneRig.focus(5_000)))
        page.nextFor("snap", "desc/torrents")
        assertEquals("web:$conn:true:5000", rig.focus.last())
        page.close()
        val until = System.currentTimeMillis() + 10_000
        while (rig.focus.last() != "web:$conn:false:0" && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals("web:$conn:false:0", rig.focus.last(), "the connection's end releases its fast poll: ${rig.focus}")
    }

    @Test
    fun aSlowTrackerHoldsNeitherTheTableNorMainsCards() {
        val gate = CountDownLatch(1)
        rig.provider.trackerGate = gate
        try {
            val (page, _) = rig.page(Levels.FULL)
            page.send(TorrentsLaneRig.sub("torrents/listing:newest/sort:added/page:0"))
            Thread.sleep(200)
            page.send(TorrentsLaneRig.sub("desc/torrents"))
            val d = desc(page.nextFor("snap", "desc/torrents"))
            assertEquals("live", d.s("availability"), "the table is described while the tracker takes its time")
            assertNull(page.frames.firstOrNull { it.s("scope")?.startsWith("torrents/listing") == true }, "the listing still waits")
        } finally {
            gate.countDown()
        }
    }
}
