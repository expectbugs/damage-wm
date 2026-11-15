package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm

/**
 * `FIRMWARE.md` §9: the conformance vectors in `firmware/vectors/` through the Kotlin
 * simulator. The same files run through the firmware's C on the PC (the fork's
 * `host/run_vectors.py`), which wrote their expectations; v1 is the installed firmware,
 * so a mismatch here is a finding about the simulator or the docs — never "update the
 * expectation to match". Inputs come from `firmware/make_vectors.py`. The v2 set (§4,
 * contract 2) adds the control ops `flags` and `cachesize` and compares the refusal
 * record (fields 23–25) and the status register after every step.
 *
 * **Not filled** (build C1's decisions, §B): a step whose `expect` is absent or empty has not been
 * answered by the C yet — `make_vectors.py` writes inputs only, and keeps the C's answers only for a
 * prefix of steps whose inputs did not change. Such a step is reported by name, with a count, in the
 * test's output: it is neither a mismatch nor a pass, and the vectors' meeting closes it. A vector none
 * of whose steps is filled is not run at all (its ops may be ones this simulator does not model yet); a
 * vector filled in part runs every op and compares its filled steps, which must come first — a filled
 * step after a not-filled one is an answer to a different input, and that is a problem.
 */
class ConformanceVectorTest {

    private fun vectorDir(): Path {
        // `DAMAGE_VECTOR_DIR` points this whole class at another set — the differential fuzz
        // (`firmware/fuzz_vectors.py`, `HANDOFF.md` §64.1). It is the same comparison, panel CRCs
        // and refusal records included, over sequences nobody wrote down. Loud, so a set left in
        // the environment can never be mistaken for the repo's own.
        System.getenv("DAMAGE_VECTOR_DIR")?.let { override ->
            val d = Path.of(override).toAbsolutePath()
            if (!Files.isDirectory(d)) fail("DAMAGE_VECTOR_DIR=$override is not a directory")
            println("ConformanceVectorTest: DAMAGE_VECTOR_DIR=$d — the repo's own vectors are NOT being checked")
            return d
        }
        var d: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (d != null) {
            val v = d.resolve("firmware/vectors")
            if (Files.isDirectory(v)) return v
            d = d.parent
        }
        fail("firmware/vectors not found above ${System.getProperty("user.dir")}")
    }

    private fun vectorFiles(dir: Path): List<Path> =
        Files.list(dir).use { s -> s.filter { it.toString().endsWith(".json") }.sorted().toList() }

    /** What one runner made of a set: the disagreements, the steps not filled yet (one entry per vector),
     *  the vectors it ran a step of, and how many lens-steps it compared. */
    private class Outcome {
        val problems = ArrayList<String>()
        val notFilled = ArrayList<String>()
        val ran = HashSet<String>()
        var compared = 0
    }

    private fun filled(step: JsonObject) = (step["expect"] as? JsonObject)?.isNotEmpty() == true

    /** The number of leading filled steps. Records the vector in [Outcome.notFilled] when any step is not
     *  filled, and a problem for every filled step that comes after a not-filled one. */
    private fun filledPrefix(name: String, steps: JsonArray, out: Outcome): Int {
        val isFilled = steps.map { filled(it.jsonObject) }
        val prefix = isFilled.indexOf(false).let { if (it < 0) isFilled.size else it }
        if (prefix < isFilled.size) {
            out.notFilled += if (isFilled.none { it }) "$name (all ${isFilled.size} steps)"
                             else "$name (steps $prefix..${isFilled.size - 1} of ${isFilled.size})"
            val late = (prefix until isFilled.size).filter { isFilled[it] }
            if (late.isNotEmpty()) out.problems += "$name: step(s) $late carry an expectation after not-filled step $prefix — an answer to a different input"
        }
        return prefix
    }

    private fun report(form: String, out: Outcome) {
        if (out.notFilled.isNotEmpty())
            println("ConformanceVectorTest ($form): ${out.notFilled.size} vector(s) NOT FILLED — not compared, not a pass " +
                    "(the fork's host/run_vectors.py --write answers them): ${out.notFilled.joinToString("; ")}")
        println("ConformanceVectorTest ($form): ${out.ran.size} vector(s) run, ${out.compared} lens-step(s) compared, ${out.notFilled.size} not filled")
    }

    private fun runNormal(files: List<Path>): Outcome {
        val out = Outcome()
        for (f in files) {
            val vec = Json.parseToJsonElement(Files.readString(f)).jsonObject
            val name = vec["name"]!!.jsonPrimitive.content
            val steps = vec["steps"]!!.jsonArray
            val prefix = filledPrefix(name, steps, out)
            if (prefix == 0) continue
            val contract = vec["contract"]?.jsonPrimitive?.int ?: 1
            out.ran += name
            for (arm in Arm.entries) {
                val lens = if (arm == Arm.LEFT) "L" else "R"
                val sim = GlassFirmwareSim().also { it.damageContract = contract }
                var now = 0L
                steps.forEachIndexed { i, stepEl ->
                    val step = stepEl.jsonObject
                    val rcs = ArrayList<Int>()
                    for (opEl in step["ops"]!!.jsonArray) {
                        val op = opEl.jsonObject
                        when {
                            "tick" in op -> now = op["tick"]!!.jsonPrimitive.long
                            "lease" in op -> sim.conformanceLease(arm, op["lease"]!!.jsonPrimitive.content == "acquire", now)
                            "msg" in op -> rcs += if (sim.conformanceMessage(arm, hex(op["msg"]!!.jsonPrimitive.content), now)) 0 else -1
                            "flags" in op -> sim.conformanceControl(arm, wm.damage.core.wire.DamageMsg.OP_FLAGS_SET, op["flags"]!!.jsonPrimitive.int, now)
                            "cachesize" in op -> sim.conformanceControl(arm, wm.damage.core.wire.DamageMsg.OP_CACHE_SIZE, op["cachesize"]!!.jsonPrimitive.int, now)
                            // `FIRMWARE.md` §9: the stock compositor's own repaint, the event each
                            // harness issues for itself after a release point
                            "stock" in op -> sim.stockRepaint(arm)
                            else -> { out.problems += "$name step $i: unknown op $op"; return@forEachIndexed }
                        }
                    }
                    if (i >= prefix) return@forEachIndexed       // not filled: its ops ran (the state carries on), nothing compared
                    val expect = step["expect"]!!.jsonObject
                    val want = expect[lens]!!.jsonPrimitive.content
                    val got = "%08x".format(sim.shadowCrc32(arm))
                    if (want != got) out.problems += "$name step $i lens $lens: shadow crc $got, the C gives $want"
                    val wantRc = expect["rc"]!!.jsonObject[lens]!!.jsonArray.map { it.jsonPrimitive.int }
                    if (wantRc != rcs) out.problems += "$name step $i lens $lens: return codes $rcs, the C gives $wantRc"
                    // contract 2 vectors carry the refusal record on every step: a missing one is a
                    // comparison silently skipped, not a pass (2026-09-15 review)
                    val wantRef = expect["ref"]?.jsonObject?.get(lens)?.jsonArray?.map { it.jsonPrimitive.int }
                    if (wantRef == null && contract >= 2) out.problems += "$name step $i lens $lens: no refusal record expectation on a contract-2 vector"
                    if (vec["panel"] != null && expect["P"] == null) out.problems += "$name step $i lens $lens: the vector is marked panel but carries no panel expectation"
                    wantRef?.let {
                        val gotRef = sim.refusalRecord(arm)
                        if (it != gotRef) out.problems += "$name step $i lens $lens: refusal record $gotRef, the C gives $it"
                    }
                    // `FIRMWARE.md` §9: a vector marked "panel" is compared on what the LENS SHOWS as
                    // well — a partial refresh (mode 24) transfers only its own rows, so a hint that
                    // misses a changed row differs here while the shadow agrees (2026-09-15, the second
                    // Phase 2 review)
                    if (vec["panel"] != null) {
                        // this lens's key, not just the object: a missing one compared nothing and
                        // said nothing, the shape `ref` was already guarded against (the third review)
                        val wantPanel = expect["P"]?.jsonObject?.get(lens)?.jsonPrimitive?.content
                        if (wantPanel == null) out.problems += "$name step $i lens $lens: no panel expectation for this lens"
                        else {
                            val gotPanel = "%08x".format(sim.panelCrc32(arm))
                            if (wantPanel != gotPanel) out.problems += "$name step $i lens $lens: panel crc $gotPanel, the C gives $wantPanel"
                        }
                    }
                    out.compared++
                }
            }
        }
        return out
    }

    /** `FIRMWARE.md` §3, the self-test: the drawing vectors through mode 16 give the normal
     *  path's CRC, return code and refusal record at every step, with the live shadow untouched —
     *  the fork's `host/run_self_test.py` proves the same of the C, so all four paths agree. As
     *  there, a cache write (mode 12 or 19) goes to the live cache the steps read, the control
     *  ops run before the begin, and a vector that releases the lease has no self-test form. */
    private fun runSelfTest(files: List<Path>): Outcome {
        val zeroCrc = "%08x".format(java.util.zip.CRC32().also { it.update(ByteArray(320 * 480)) }.value)
        val out = Outcome()
        val unfilled = HashSet<String>()
        for (f in files) {
            val vec = Json.parseToJsonElement(Files.readString(f)).jsonObject
            val name = vec["name"]!!.jsonPrimitive.content
            val steps = vec["steps"]!!.jsonArray
            val prefix = filledPrefix(name, steps, out)
            if (prefix == 0) { unfilled += name; continue }
            if (name == "v1-lease") continue      // the lapse inside it refuses the step itself: no self-test form
            val ops = steps.flatMap { it.jsonObject["ops"]!!.jsonArray.map { o -> o.jsonObject } }
            if (ops.any { "lease" in it && it["lease"]!!.jsonPrimitive.content != "acquire" }) continue
            if (ops.any { "stock" in it }) continue          // no self-test form: a step cannot repaint the panel
            val contract = vec["contract"]?.jsonPrimitive?.int ?: 1
            for (arm in Arm.entries) {
                val lens = if (arm == Arm.LEFT) "L" else "R"
                val sim = GlassFirmwareSim().also { it.damageContract = contract }
                var now = 1000L
                sim.conformanceLease(arm, true, now)
                assertTrue(sim.conformanceMessage(arm, wm.damage.core.wire.CfwModes.selfTestBegin(), now), "$name $lens: begin refused")
                steps.forEachIndexed { i, stepEl ->
                    val step = stepEl.jsonObject
                    val rcs = ArrayList<Int>()
                    for (opEl in step["ops"]!!.jsonArray) {
                        val op = opEl.jsonObject
                        when {
                            "tick" in op -> now = op["tick"]!!.jsonPrimitive.long
                            "msg" in op -> {
                                val m = hex(op["msg"]!!.jsonPrimitive.content)
                                val live = (m[0].toInt() and 0x7F).let { it == 12 || it == 19 }
                                // the step is built raw, not through CfwModes.selfTestStep: a refusal vector
                                // carries messages the encoder's lint would refuse (an unknown mode)
                                val st = byteArrayOf(wm.damage.core.wire.CfwModes.SELF_TEST_MODE.toByte(), 1) + m
                                rcs += if (sim.conformanceMessage(arm, if (live) m else st, now)) 0 else -1
                            }
                            // the control ops go to the lens AT THEIR OWN POSITION, as they do on the
                            // normal path (2026-09-16, the fourth review — found by the differential
                            // fuzz): hoisting them all before the begin, as both runners did, is the
                            // same sequence only while they all precede the first message, and a
                            // mid-vector FLAGS_SET was then armed for steps the normal path ran without
                            // it. Every vector in the repo that has one also releases the lease, so the
                            // difference had never shown.
                            "flags" in op -> sim.conformanceControl(arm, wm.damage.core.wire.DamageMsg.OP_FLAGS_SET, op["flags"]!!.jsonPrimitive.int, now)
                            "cachesize" in op -> sim.conformanceControl(arm, wm.damage.core.wire.DamageMsg.OP_CACHE_SIZE, op["cachesize"]!!.jsonPrimitive.int, now)
                            "lease" in op -> {}        // an acquire: the runner holds the lease throughout
                            else -> { out.problems += "$name step $i: no self-test form for $op"; return@forEachIndexed }
                        }
                    }
                    out.ran += name
                    if (i >= prefix) return@forEachIndexed       // not filled: run, not compared
                    val expect = step["expect"]!!.jsonObject
                    // Fields 21/22 are sent "both after a step" (`FIRMWARE.md` §3), so before the
                    // first one the record carries no CRC and the scratch is simply the zeroed
                    // shadow the begin allocated — which is what the normal path's all-zero shadow
                    // hashes to. Every vector in the repo draws in its first step, so this only
                    // shows on a sequence that does not (2026-09-16, the differential fuzz).
                    val got = if (sim.selfTestSteps(arm) == 0L) zeroCrc else "%08x".format(sim.selfTestCrc(arm))
                    if (expect[lens]!!.jsonPrimitive.content != got) out.problems += "$name step $i lens $lens: scratch crc $got, the normal path gives ${expect[lens]!!.jsonPrimitive.content}"
                    val wantRc = expect["rc"]!!.jsonObject[lens]!!.jsonArray.map { it.jsonPrimitive.int }
                    if (wantRc != rcs) out.problems += "$name step $i lens $lens: return codes $rcs, the normal path gives $wantRc"
                    // the refusal's copy sequence is the live count (0 here, nothing presents): not compared
                    expect["ref"]?.jsonObject?.get(lens)?.jsonArray?.map { it.jsonPrimitive.int }?.let { w ->
                        val g = sim.refusalRecord(arm)
                        if (listOf(w[0], w[1], w[3]) != listOf(g[0], g[1], g[3])) out.problems += "$name step $i lens $lens: refusal record $g, the normal path gives $w"
                    }
                    val liveCrc = "%08x".format(sim.shadowCrc32(arm))
                    if (liveCrc != zeroCrc) out.problems += "$name step $i lens $lens: the live shadow changed ($liveCrc)"
                    out.compared++
                }
            }
        }
        // every vector without a lease release has a self-test form: one that ran no step is a skip,
        // not a pass (the fork's run_self_test.py printed PASS for skipped vectors until 2026-09-15).
        // A vector not filled at all is reported as such above, not here.
        for (f in files) {
            val vec = Json.parseToJsonElement(Files.readString(f)).jsonObject
            val name = vec["name"]!!.jsonPrimitive.content
            val releases = vec["steps"]!!.jsonArray.any { st -> st.jsonObject["ops"]!!.jsonArray.any { o -> o.jsonObject["lease"]?.jsonPrimitive?.content == "release" } }
            if (name != "v1-lease" && !releases && name !in unfilled && name !in out.ran) out.problems += "$name: a drawing vector ran no self-test step"
        }
        return out
    }

    @Test
    fun everyVectorMatchesOnBothLenses() {
        val files = vectorFiles(vectorDir())
        assertTrue(files.isNotEmpty(), "no vectors in ${vectorDir()}")
        val out = runNormal(files)
        report("normal path", out)
        assertTrue(out.compared > 0, "no vector step was compared")
        assertEquals(emptyList(), out.problems, "the simulator disagrees with the firmware C:\n" + out.problems.joinToString("\n"))
    }

    @Test
    fun theSelfTestPathMatchesTheNormalPath() {
        val out = runSelfTest(vectorFiles(vectorDir()))
        report("self-test form", out)
        assertTrue(out.compared > 0, "no drawing vectors ran")
        assertEquals(emptyList(), out.problems, "the self-test path disagrees with the normal path:\n" + out.problems.joinToString("\n"))
    }

    /** The not-filled rule itself (build C1's decisions, §B), on a set built for it from a real filled
     *  vector: a vector with no expectation is reported and not run — not a mismatch, not a pass; one
     *  filled in part is compared on its filled prefix and reported for the rest; a filled step after a
     *  not-filled one is a problem; and a wrong expectation is still a mismatch. */
    @Test
    fun aVectorWithNoExpectationIsReportedNotFilledNeitherMismatchNorPass() {
        val src = vectorFiles(vectorDir()).firstOrNull { it.fileName.toString() == "v1-keyframe.json" }
            ?: fail("v1-keyframe.json is not in ${vectorDir()}: the rule's test is built from it")
        val base = Json.parseToJsonElement(Files.readString(src)).jsonObject
        val baseSteps = base["steps"]!!.jsonArray
        assertTrue(baseSteps.size >= 2 && baseSteps.all { filled(it.jsonObject) }, "v1-keyframe must hold two or more filled steps")
        fun variant(name: String, edit: (Int, JsonObject) -> JsonObject): JsonObject = JsonObject(base + mapOf(
            "name" to JsonPrimitive(name),
            "steps" to JsonArray(baseSteps.mapIndexed { i, s -> edit(i, s.jsonObject) })))
        fun withExpect(s: JsonObject, e: JsonObject) = JsonObject(s + ("expect" to e))
        val empty = JsonObject(emptyMap())
        val unknownOp = buildJsonObject { put("wake", true) }
        val dir = Files.createTempDirectory("conformance-rule")
        try {
            val set = mapOf(
                "a-filled" to variant("a-filled") { _, s -> s },
                // not filled at all, and carrying an op this simulator does not model: never run
                "b-empty" to variant("b-empty") { i, s ->
                    withExpect(JsonObject(s + ("ops" to JsonArray(s["ops"]!!.jsonArray + (if (i == 0) listOf(unknownOp) else emptyList())))), empty) },
                // filled for its first step only: compared there, reported for the rest
                "c-suffix" to variant("c-suffix") { i, s -> if (i == 0) s else withExpect(s, empty) },
                // a filled step after a not-filled one: a problem
                "d-gap" to variant("d-gap") { i, s -> if (i == 0) withExpect(s, empty) else s },
                // a wrong expectation in a filled vector: still a mismatch
                "e-wrong" to variant("e-wrong") { i, s ->
                    if (i == 0) withExpect(s, JsonObject(s["expect"]!!.jsonObject + ("L" to JsonPrimitive("00000000")))) else s },
            )
            for ((n, v) in set) Files.writeString(dir.resolve("$n.json"), v.toString())
            val files = vectorFiles(dir)

            val normal = runNormal(files)
            val n = baseSteps.size
            assertEquals(listOf("b-empty (all $n steps)", "c-suffix (steps 1..${n - 1} of $n)", "d-gap (steps 0..${n - 1} of $n)"),
                         normal.notFilled, "the not-filled vectors, by name, with their steps")
            assertTrue("b-empty" !in normal.ran && "d-gap" !in normal.ran, "a vector with no filled first step is not run: ${normal.ran}")
            assertTrue("c-suffix" in normal.ran && "a-filled" in normal.ran, "the filled ones are run: ${normal.ran}")
            assertTrue(normal.problems.none { it.startsWith("b-empty") || it.startsWith("c-suffix") || it.startsWith("a-filled") },
                       "not filled is not a mismatch: ${normal.problems}")
            assertTrue(normal.problems.any { it.startsWith("d-gap: step(s)") }, "a filled step after a not-filled one is a problem: ${normal.problems}")
            assertTrue(normal.problems.any { it.startsWith("e-wrong step 0 lens L: shadow crc") }, "a wrong expectation is still a mismatch: ${normal.problems}")
            // compared: a-filled and e-wrong whole, c-suffix's first step — on both lenses
            assertEquals(2 * (2 * baseSteps.size + 1), normal.compared, "the lens-steps compared")

            val self = runSelfTest(files)
            assertEquals(normal.notFilled, self.notFilled, "the self-test form reports the same not-filled vectors")
            assertTrue(self.problems.none { it.startsWith("b-empty") || it.startsWith("c-suffix") || it.startsWith("a-filled") },
                       "the self-test form: not filled is neither a mismatch nor a skipped drawing vector: ${self.problems}")
            assertTrue("b-empty" !in self.ran && "c-suffix" in self.ran, "the self-test form runs only filled vectors: ${self.ran}")
            assertEquals(2 * (2 * baseSteps.size + 1), self.compared, "the self-test form's lens-steps compared")
        } finally {
            Files.list(dir).use { s -> s.forEach { Files.delete(it) } }
            Files.delete(dir)
        }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
