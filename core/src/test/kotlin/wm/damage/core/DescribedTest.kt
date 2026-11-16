package wm.damage.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wm.damage.core.app.AppNet
import wm.damage.core.app.AppWire
import wm.damage.core.app.Change
import wm.damage.core.app.Levels

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q2 (the spec's §2.2, §2.5): several providers of PC-served scopes on one lane, each scope
 * served by the first provider whose gate names it; a provider that names its own describe executor never holds
 * another's descriptions (a tracker page that takes seconds never holds Reader's part); a provider's source moves
 * describe again its own scopes only, and Main where its window has a card; Main's cards come from the windows served
 * with one. A page written by hand against a real web listener (`WebRig`), made-up providers of the window `t`.
 */
class DescribedTest {
    /** A made-up provider: [prefix] its scopes, its description counting its describes, [moved] firing its source. */
    private open class Fake(private val prefix: String, private val window: String? = "t") : AppNet.Described {
        val described = AtomicInteger()
        private val listeners = CopyOnWriteArrayList<() -> Unit>()
        override fun gate(scope: String) = if (scope.startsWith(prefix)) AppNet.Gate(window, Levels.VIEW) else null
        override fun describe(scope: String): JsonObject? = buildJsonObject { put("from", prefix); put("n", described.incrementAndGet()) }
        override fun follows(scope: String, c: Change?) = c == null
        override fun addListener(l: () -> Unit) { listeners += l }
        fun moved() = listeners.forEach { it() }
    }

    private fun WebRig.page(): Page {
        val (_, secret) = approved(Levels.VIEW, "fixture page")
        val p = Page(port, secret)
        p.hello()
        p.next("welcome")
        return p
    }

    private fun Page.sub(scope: String) = send(buildJsonObject { put("t", "sub"); put("scope", scope) })

    private fun Page.desc(scope: String, within: Long = 10_000): JsonObject? {
        val until = System.currentTimeMillis() + within
        while (System.currentTimeMillis() < until) {
            val f = frames.poll(50, TimeUnit.MILLISECONDS) ?: continue
            if (AppWire.type(f) == "snap" && f["scope"]?.jsonPrimitive?.content == scope) return f["desc"]!!.jsonObject
        }
        return null
    }

    @Test
    fun eachScopeIsServedByTheProviderWhoseGateNamesIt() = WebRig().use { r ->
        val x = Fake("x/")
        val y = Fake("y/")
        r.net.register(y)
        r.net.serve("t", scopes = x)
        val p = r.page()
        p.sub("x/one")
        assertEquals("x/", p.desc("x/one")?.get("from")?.jsonPrimitive?.content)
        p.sub("y/one")
        assertEquals("y/", p.desc("y/one")?.get("from")?.jsonPrimitive?.content)
        // x's source moves: x's scope only is described again
        x.moved()
        assertEquals(2, p.desc("x/one")?.get("n")?.jsonPrimitive?.content?.toInt())
        assertEquals(null, p.desc("y/one", within = 500), "y's scope follows y's source only")
        assertEquals(1, y.described.get())
        p.close()
    }

    /** Pin (the spec's Q2: "a slow describe holds Reader's part (one shared describer)"): a provider on its own
     *  describe executor that takes its time never holds a scope of the shared describer. */
    @Test
    fun aSlowProviderOnItsOwnExecutorNeverHoldsAnother() = WebRig().use { r ->
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val own = Executors.newSingleThreadExecutor { t -> Thread(t, "test-slow-describer").apply { isDaemon = true } }
        val slow = object : Fake("slow/") {
            override val describeOn = own
            override fun describe(scope: String): JsonObject? {
                started.countDown()
                release.await(30, TimeUnit.SECONDS)
                return super.describe(scope)
            }
        }
        val reader = Fake("reader/", window = null)
        r.net.register(reader)                      // the shared describer, as Reader's scopes are
        r.net.serve("t", scopes = slow)
        val p = r.page()
        try {
            p.sub("slow/listing")
            assertTrue(started.await(10, TimeUnit.SECONDS), "the slow description started")
            p.sub("reader/book:1/part:0")
            assertEquals("reader/", p.desc("reader/book:1/part:0", within = 5_000)?.get("from")?.jsonPrimitive?.content,
                "Reader's part is described while the slow one is still being made")
        } finally {
            release.countDown()
        }
        assertEquals("slow/", p.desc("slow/listing")?.get("from")?.jsonPrimitive?.content)
        p.close()
        own.shutdown()
    }

    @Test
    fun mainsCardsComeFromTheWindowsServedWithOneAndFollowTheirSources() = WebRig().use { r ->
        val net = r.net
        // Main's provider, as PcScopes builds it: its cards from the lane's registry
        val main = object : AppNet.Described {
            override fun gate(scope: String) = if (scope == wm.damage.core.app.PcScopes.MAIN) AppNet.Gate(null, Levels.VIEW) else null
            override fun describe(scope: String) = buildJsonObject { put("cards", kotlinx.serialization.json.JsonArray(net.cards())) }
            override fun follows(scope: String, c: Change?) = false
            override fun addListener(l: () -> Unit) {}
        }
        net.register(main)
        val count = AtomicInteger(3)
        val src = Fake("t/")
        net.serve("t", scopes = src, card = { buildJsonObject { put("window", "t"); put("line", "${count.get()} made-up things") } })
        net.serve("u", card = { throw IllegalStateException("a made-up card that fails") })
        val p = r.page()
        p.sub(wm.damage.core.app.PcScopes.MAIN)
        val first = p.desc(wm.damage.core.app.PcScopes.MAIN)!!["cards"]!!.jsonArray
        assertEquals(listOf("3 made-up things"), first.map { it.jsonObject["line"]!!.jsonPrimitive.content },
            "a card that fails is left out of this description, said")
        assertTrue(recentLog().any { "Main's card of u could not be made" in it })
        count.set(4)
        src.moved()
        val again = assertNotNull(p.desc(wm.damage.core.app.PcScopes.MAIN), "Main is described again when the window's source moves")["cards"]!!.jsonArray
        assertEquals(JsonPrimitive("4 made-up things"), again.single().jsonObject["line"], "Main follows the window's source")
        p.close()
    }
}
