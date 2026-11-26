package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Music's motion update, stage L: the held lanes' program shapes as conformance vector INPUTS (`CLAUDE.md`: a new program
 * shape gets a vector the fork fills; a disagreement is a finding) — written from the shell's own builder through
 * [LaneRig] (the Motion DSL's bytes, the chain's own PLAY and PLAY-AFTERs, fixed delays, both lenses at one tick):
 * `v5-mml-bar`, one stretch of the bar drawn to its end, and `v5-mml-chain`, a PLAY and two PLAY-AFTERs. Stage S (ruling
 * 14): Scope's lane in Music Mode — `v5-mml-scope`, one stretch (its area: the zero line, the column font, the program,
 * its cell list) drawn to its end, and `v5-mml-scope-chain`, a PLAY and two PLAY-AFTERs, a new area written at each link.
 * Their expectations are the fork's runner's (the bar's two filled at K1-2 stage 4h's comparison, Scope's two at stage SF's).
 *
 * `DAMAGE_MML_DUMP=DIR` writes them; otherwise each committed file's inputs must equal what the builder sends today.
 */
class MusicLaneVectorsTest {

    private fun bar(): JsonObject {
        val r = LaneRig(11, 34, 30, fixed = true, record = true)
        r.pump()
        while (r.sent.isEmpty() && r.nowMs < 5_000) r.runUntil(r.nowMs + 10)
        r.frozen = true                                       // the one stretch plays to its end; no PLAY-AFTER goes
        r.runUntil(r.nowMs + 30 * 34 * 1000L / 1024 + 400)
        // one stretch: the chain's first PLAY drawn to its end (the next is not sent: the walk stops before half of it)
        return r.vector("v5-mml-bar")
    }

    private fun chain(): JsonObject {
        val r = LaneRig(12, 34, 12, fixed = true, record = true)
        r.pump()
        val len = 12 * 34 * 1000L / 1024
        val stop = r.nowMs + 20 * len
        while (r.sent.size < 3 && r.nowMs < stop) r.runUntil(r.nowMs + 20)
        assertTrue(r.sent.size >= 3, "three links within twenty stretches: ${r.notes}")
        r.frozen = true                                       // the third link plays to its end; nothing more goes
        r.runUntil(r.nowMs + 2 * len + 300)
        return r.vector("v5-mml-chain").also { assertEquals(listOf(0L, 1L, 1L), r.sent.take(3).map { it[3] }, "a PLAY, then two PLAY-AFTERs") }
    }

    private fun scope(): JsonObject {
        val t = LaneRig.SCOPE_T
        val r = LaneRig(13, 34, t, fixed = true, record = true, scope = true)
        r.pump()
        while (r.sent.isEmpty() && r.nowMs < 5_000) r.runUntil(r.nowMs + 10)
        r.frozen = true                                       // the one stretch plays to its end; no PLAY-AFTER goes
        r.runUntil(r.nowMs + t * 34 * 1000L / 1024 + 400)
        assertEquals(1, r.areasWritten().size, "one area: ${r.notes}")
        return r.vector("v5-mml-scope")
    }

    private fun scopeChain(): JsonObject {
        val t = LaneRig.SCOPE_T
        val r = LaneRig(14, 34, t, fixed = true, record = true, scope = true)
        r.pump()
        val len = t * 34 * 1000L / 1024
        val stop = r.nowMs + 20 * len
        while (r.sent.size < 3 && r.nowMs < stop) r.runUntil(r.nowMs + 20)
        assertTrue(r.sent.size >= 3, "three links within twenty stretches: ${r.notes}")
        r.frozen = true                                       // the third link plays to its end; nothing more goes
        r.runUntil(r.nowMs + 2 * len + 300)
        assertEquals(listOf(0L, 1L, 1L), r.sent.take(3).map { it[3] }, "a PLAY, then two PLAY-AFTERs")
        assertEquals(3, r.areasWritten().size, "an area a link: ${r.areasWritten()}")
        return r.vector("v5-mml-scope-chain")
    }

    private fun ops(v: JsonObject): List<String> = v["steps"]!!.jsonArray.map { it.jsonObject["ops"].toString() }

    @Test
    fun theLanesShapesAreVectorInputsTheBuilderSendsToday() {
        val dump = System.getenv("DAMAGE_MML_DUMP")?.let { Path.of(it).toAbsolutePath() }
        val made = listOf(bar(), chain(), scope(), scopeChain())
        for (v in made) assertTrue(v["steps"]!!.jsonArray.size > 10, "${v["name"]}: a vector of its steps")
        if (dump != null) {
            Files.createDirectories(dump)
            for (v in made) {
                val f = dump.resolve("${(v["name"] as kotlinx.serialization.json.JsonPrimitive).content}.json")
                Files.writeString(f, v.toString() + "\n")
                println("MusicLaneVectorsTest: wrote $f (${v["steps"]!!.jsonArray.size} steps)")
            }
            return
        }
        var d: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (d != null && !Files.isDirectory(d.resolve("firmware/vectors"))) d = d.parent
        val dir = d?.resolve("firmware/vectors") ?: fail("firmware/vectors not found")
        for (v in made) {
            val name = (v["name"] as kotlinx.serialization.json.JsonPrimitive).content
            val f = dir.resolve("$name.json")
            assertTrue(Files.exists(f), "$name: no file — DAMAGE_MML_DUMP writes it")
            val file = Json.parseToJsonElement(Files.readString(f)).jsonObject
            assertEquals(ops(v), ops(file), "$name: the inputs the builder sends today differ from the file's — regenerate it and have the fork fill it again")
        }
    }
}
