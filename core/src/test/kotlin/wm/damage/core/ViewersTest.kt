package wm.damage.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wm.damage.core.app.AppNet
import wm.damage.core.app.AppWire
import wm.damage.core.app.Change
import wm.damage.core.app.Levels
import wm.damage.core.app.Viewers
import wm.damage.core.app.Viewers.Need

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q2 (the spec's §2.3 and §5 V1; `CONTRACT.md` §8; S1-2 R0 §3): viewers with a need. A source
 * reads the most demanding live viewer's need; a second `sub` of a held scope replaces the need (none ends it); a
 * viewer ends with its `unsub`, its connection's end, and when its connection has sent no ping for two of its
 * intervals. On the lane: a page written by hand against a real web listener (`WebRig`), the made-up window `t` with
 * a live scope `t/live`; the clock is the rig's own.
 */
class ViewersTest {
    private val focus = Need(Need.FOCUS, 2_000)

    /** The most demanding live viewer of [window], as a source reads it (`TorrentsScopes`: [Viewers.of], [Viewers.stronger]). */
    private fun Viewers.strongest(window: String, now: Long): Need? = of(window, now).fold(null as Need?) { a, x -> Viewers.stronger(a, x.need) }

    @Test
    fun theStrongestNeedWinsAndANeedOfNoneEndsAViewer() {
        val v = Viewers(pingEveryMs = 1_000)
        val heard = CopyOnWriteArrayList<String>()
        v.addListener { x, ended, why -> heard += "${x.conn} ${x.scope} ${if (ended) "ended" else x.need.kind} ($why)" }
        v.opened(1, 0); v.opened(2, 0)
        v.put(1, "t/live", "t", Need(Need.GLANCE))
        assertEquals(Need(Need.GLANCE), v.strongest("t", 0))
        v.put(2, "t/live", "t", Need(Need.FOCUS, 5_000))
        v.put(1, "t/live", "t", Need(Need.FOCUS, 2_000))   // a second sub replaces the need (R0 §3)
        assertEquals(Need(Need.FOCUS, 2_000), v.strongest("t", 0), "focus over glance, then the fastest pace")
        v.put(1, "t/live", "t", Need(Need.NONE))           // hidden
        assertEquals(Need(Need.FOCUS, 5_000), v.strongest("t", 0))
        v.remove(2, "t/live", "unsubscribed")
        assertNull(v.strongest("t", 0), "no viewer: the source parks")
        assertNull(v.strongest("other", 0))
        assertEquals(listOf("1 t/live glance (started)", "2 t/live focus (started)", "1 t/live focus (need changed from glance)",
            "1 t/live ended (its need is none)", "2 t/live ended (unsubscribed)"), heard)
    }

    @Test
    fun aConnectionQuietForTwoIntervalsLosesItsViewers() {
        val v = Viewers(pingEveryMs = 1_000)
        v.opened(1, 0)
        v.put(1, "t/live", "t", focus)
        v.pinged(1, 1_000)
        assertEquals(focus, v.strongest("t", 2_900), "within two intervals of its last ping")
        assertNull(v.strongest("t", 3_100), "the read applies the same rule as the sweep")
        assertEquals(0, v.sweep(2_900))
        assertEquals(1, v.sweep(3_100))
        assertEquals(emptyList(), v.of("t", 3_100))
        // a slower client's interval is its own: pings 5 s apart keep it for 10 s
        v.opened(2, 0)
        v.pinged(2, 5_000)
        v.put(2, "t/live", "t", focus)
        assertEquals(focus, v.strongest("t", 14_000))
        assertNull(v.strongest("t", 15_100))
    }

    @Test
    fun aNeedThatDoesNotDecodeIsRefusedLoudly() {
        assertFailsWith<IllegalArgumentException> { Need.parse(buildJsonObject { put("kind", "stare") }) }
        assertFailsWith<IllegalArgumentException> { Need.parse(buildJsonObject { put("kind", "focus"); put("paceMs", -1) }) }
        assertEquals(Need(Need.FOCUS, 2_000), Need.parse(buildJsonObject { put("kind", "focus"); put("paceMs", 2_000) }))
    }

    // ---- on the lane ----

    private class Live : AppNet.Described {
        override fun gate(scope: String) = if (scope == "t/live") AppNet.Gate("t", Levels.VIEW) else null
        override fun describe(scope: String): JsonObject = buildJsonObject { put("live", true) }
        override fun follows(scope: String, c: Change?) = false
        override fun addListener(l: () -> Unit) {}
    }

    private fun rig() = WebRig(pingEveryMs = 1_000).also { it.net.register(Live()) }

    private fun WebRig.page(): Pair<Page, Long> {
        val (_, secret) = approved(Levels.VIEW, "fixture viewer")
        val p = Page(port, secret)
        p.hello()
        return p to p.next("welcome")["conn"]!!.jsonPrimitive.content.toLong()
    }

    private fun Page.sub(scope: String, need: JsonObject?) = send(buildJsonObject {
        put("t", "sub"); put("scope", scope); need?.let { put("need", it) }
    })

    private fun Page.awaitDesc(scope: String) {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            val f = frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (AppWire.type(f) == "snap" && f["scope"]?.jsonPrimitive?.content == scope) return
        }
        throw AssertionError("no description of $scope within 10 s")
    }

    private fun needJson(kind: String, pace: Long? = null) = buildJsonObject { put("kind", kind); pace?.let { put("paceMs", it) } }

    private fun WebRig.need() = net.viewers.strongest("t", rec.now)

    @Test
    fun aSubWithANeedMakesAViewerAndASecondSubReplacesIt() = rig().use { r ->
        val (page, _) = r.page()
        page.sub("t/live", needJson("focus", 2_000))
        page.awaitDesc("t/live")
        assertEquals(focus, r.need())
        page.sub("t/live", needJson("none"))                      // the page went hidden
        page.awaitDesc("t/live")
        assertNull(r.need(), "a second sub replaces the need; none ends the viewer")
        page.sub("t/live", needJson("focus", 3_000))              // shown again
        page.awaitDesc("t/live")
        assertEquals(Need(Need.FOCUS, 3_000), r.need())
        page.send(buildJsonObject { put("t", "unsub"); put("scope", "t/live") })
        eventually("the unsub ends the viewer") { r.need() == null }
        page.close()
    }

    /** Pin (the spec's Q2: "a connection's end keeps its viewer's fast pace"). */
    @Test
    fun aConnectionsEndEndsItsViewers() = rig().use { r ->
        val (page, _) = r.page()
        page.sub("t/live", needJson("focus", 2_000))
        page.awaitDesc("t/live")
        assertEquals(focus, r.need())
        page.close()
        eventually("the connection's end ends its viewer") { r.net.connections() == 0 }
        // within the pings' rule still: only the end can have ended it
        assertNull(r.need(), "no viewer once its connection ended")
    }

    /** Pin (the spec's Q2: "a viewer with no ping for two intervals keeps it"). */
    @Test
    fun aViewerWithNoPingForTwoIntervalsEnds() = rig().use { r ->
        val heard = CopyOnWriteArrayList<String>()
        r.net.viewers.addListener { v, ended, why -> if (ended) heard += "${v.scope}: $why" }
        val (page, _) = r.page()
        page.sub("t/live", needJson("focus", 2_000))
        page.awaitDesc("t/live")
        page.send(buildJsonObject { put("t", "ping"); put("k", 1) })
        page.next("pong")
        r.rec.now += 1_900
        page.send(buildJsonObject { put("t", "ping"); put("k", 2) })
        page.next("pong")
        r.rec.now += 1_900
        assertEquals(1, r.net.connections())
        assertEquals(focus, r.need(), "a connection that pings keeps its viewer")
        assertEquals(0, r.net.sweepViewers())
        r.rec.now += 2_200                                        // 4.1 s since its last ping: past two of its 1.9 s intervals
        assertNull(r.need(), "quiet for two of its intervals: the source no longer counts it")
        assertEquals(1, r.net.sweepViewers(), "the sweep ends it")
        assertTrue(heard.single().startsWith("t/live: no ping for"), "$heard")
        assertEquals(1, r.net.connections(), "the connection itself is left to TCP keepalive")
        // the record's pacing tick runs the sweep too
        page.sub("t/live", needJson("focus", 2_000))
        page.awaitDesc("t/live")
        r.rec.now += 10_000
        r.rec.tick(r.host)
        assertEquals(2, heard.size, "the tick's sweep ended the viewer again: $heard")
        page.close()
    }
}
