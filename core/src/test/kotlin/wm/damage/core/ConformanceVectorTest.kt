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
 *
 * **C1** (`FIRMWARE.md` §5.10, build C1's decisions A.3): a vector's `features` key is the DamageCaps features of the
 * build it is for (0x1ff: the engine and the riders); `{"wake": true}` is one engine wake at the clock the last `tick`
 * op set, and that clock IS the OS tick the engine reads. Such a vector's filled steps carry three expectations more:
 * `N` (RIGHT's notify-114 bodies sent during the step, in send order, hex, fields 9 and 11 dropped — a varint's length
 * varies), `W` (each lens's planned wait after the step: OS ticks, −1 for none, 0 for "a step is due now") and `G`
 * (field 17 per lens). A missing one is a comparison silently skipped, so it is a problem, as `ref` is. Engine vectors
 * have no self-test form (a step presents nothing, and mode 26 in a step is refused).
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

    private fun runNormal(files: List<Path>, featuresOverride: Int? = null): Outcome {
        val out = Outcome()
        for (f in files) {
            val vec = Json.parseToJsonElement(Files.readString(f)).jsonObject
            val name = vec["name"]!!.jsonPrimitive.content
            val steps = vec["steps"]!!.jsonArray
            val prefix = filledPrefix(name, steps, out)
            if (prefix == 0) continue
            val contract = vec["contract"]?.jsonPrimitive?.int ?: 1
            val features = vec["features"]?.jsonPrimitive?.int
            if (featuresOverride != null && features != null) continue      // the override is for vectors written without one
            out.ran += name
            for (arm in Arm.entries) {
                val lens = if (arm == Arm.LEFT) "L" else "R"
                val sim = newSim(contract, features ?: featuresOverride)
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
                            "stock" in op -> sim.stockRepaint(arm, now)
                            "wake" in op -> sim.conformanceWake(arm, now)          // §5.10
                            else -> { out.problems += "$name step $i: unknown op $op"; return@forEachIndexed }
                        }
                    }
                    val sent = sim.conformanceNotifies(arm)          // taken every step, so a step's bodies are its own
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
                    if (features != null) compareC1(name, i, arm, lens, expect, sim, now, sent, out)
                    out.compared++
                }
            }
        }
        return out
    }

    private fun newSim(contract: Int, features: Int?) = GlassFirmwareSim().also {
        it.damageContract = contract
        it.damageFeatures = features
        it.clockIsOsTick = true           // §5.10: the `tick` op's value is the OS tick
    }

    /** C1's three expectations (decision A.3) on a filled step of a vector that carries `features`. */
    private fun compareC1(name: String, i: Int, arm: Arm, lens: String, expect: JsonObject, sim: GlassFirmwareSim, now: Long,
                          sent: List<ByteArray>, out: Outcome) {
        val gotW = sim.conformancePlan(arm, now)
        val wantW = expect["W"]?.jsonObject?.get(lens)?.jsonPrimitive?.long
        if (wantW == null) out.problems += "$name step $i lens $lens: no planned-wait (W) expectation on a C1 vector"
        else if (wantW != gotW) out.problems += "$name step $i lens $lens: planned wait $gotW, the C gives $wantW"
        val gotG = sim.conformanceGen(arm)
        val wantG = expect["G"]?.jsonObject?.get(lens)?.jsonPrimitive?.long
        if (wantG == null) out.problems += "$name step $i lens $lens: no field-17 (G) expectation on a C1 vector"
        else if (wantG != gotG) out.problems += "$name step $i lens $lens: field 17 is $gotG, the C gives $wantG"
        if (arm == Arm.LEFT) {
            if (sent.isNotEmpty()) out.problems += "$name step $i: LEFT sent ${sent.size} notify bodies (only RIGHT can send)"
            return
        }
        val wantN = (expect["N"] as? JsonArray)?.map { it.jsonPrimitive.content }
        val gotN = sent.map { maskedHex(it) }
        if (wantN == null) out.problems += "$name step $i: no notify (N) expectation on a C1 vector"
        else if (wantN != gotN) out.problems += "$name step $i: notify bodies $gotN, the C gives $wantN"
    }

    /** Decision A.3's mask: fields 9 (µs) and 11 (uptime) dropped from a body before hex. */
    private fun maskedHex(body: ByteArray): String = wm.damage.core.wire.Pb.fields(body).filter { it.field != 9 && it.field != 11 }
        .joinToString("") { f -> val b = if (f.bytes != null) wm.damage.core.wire.Pb.l(f.field, f.bytes!!) else wm.damage.core.wire.Pb.v(f.field, f.varint!!)
                                 b.joinToString("") { "%02x".format(it) } }

    /** Whether a message is or carries mode 26 (a batch's sub-message): an engine vector has no self-test form. */
    private fun carries26(m: ByteArray): Boolean {
        if (m.isEmpty()) return false
        if ((m[0].toInt() and 0x7F) == 26) return true
        if ((m[0].toInt() and 0x7F) != 8 || m.size < 2) return false
        var pos = 2
        repeat(m[1].toInt() and 0xFF) {
            if (pos + 3 > m.size) return false
            val len = (m[pos].toInt() and 0xFF) or ((m[pos + 1].toInt() and 0xFF) shl 8)
            if ((m[pos + 2].toInt() and 0x7F) == 26) return true
            pos += 2 + len
        }
        return false
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
        val engine = HashSet<String>()
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
            // §5.10: an engine vector has no self-test form (a wake, or mode 26 alone or in a batch)
            if (ops.any { "wake" in it || ("msg" in it && carries26(hex(it["msg"]!!.jsonPrimitive.content))) }) { engine += name; continue }
            val contract = vec["contract"]?.jsonPrimitive?.int ?: 1
            for (arm in Arm.entries) {
                val lens = if (arm == Arm.LEFT) "L" else "R"
                val sim = newSim(contract, vec["features"]?.jsonPrimitive?.int)
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
            if (name != "v1-lease" && !releases && name !in unfilled && name !in engine && name !in out.ran) out.problems += "$name: a drawing vector ran no self-test step"
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

    /** Build C1's decision A.1 (spec 9.1, option a): the v1 and v2 vectors are build-neutral, so a C1 build (features
     *  0x1ff) answers every one of them exactly as the installed image's 0x7f does. A model change that leaks into
     *  today's behaviour on a C1 build shows here before a C1 vector is ever filled. */
    @Test
    fun theV1AndV2VectorsAnswerTheSameOnAC1Build() {
        val out = runNormal(vectorFiles(vectorDir()), featuresOverride = C1_FEATURES)
        report("normal path, features 0x1ff", out)
        assertTrue(out.compared > 0, "no vector step was compared")
        assertEquals(emptyList(), out.problems, "a C1 build answers a v1/v2 vector differently:\n" + out.problems.joinToString("\n"))
    }

    /** The vectors nobody has answered yet still run through the model, every op of every step on both lenses: an op
     *  it does not know, or a fault inside it, is a problem now rather than at the meeting. With `DAMAGE_SIM_ANSWERS`
     *  set to a directory, the model's own answers are written there, one file per vector in the vectors' shape, for
     *  the meeting to set beside the C's — never into `firmware/vectors/` (the C fills those). */
    @Test
    fun theUnfilledVectorsRunThroughTheModel() {
        val dump = System.getenv("DAMAGE_SIM_ANSWERS")?.let { Path.of(it).toAbsolutePath() }
        if (dump != null) { assertTrue(Files.isDirectory(dump), "DAMAGE_SIM_ANSWERS=$dump is not a directory"); assertTrue(!dump.endsWith("vectors"), "never into the vectors") }
        val problems = ArrayList<String>()
        var ran = 0
        for (f in vectorFiles(vectorDir())) {
            val vec = Json.parseToJsonElement(Files.readString(f)).jsonObject
            if (vec["steps"]!!.jsonArray.all { filled(it.jsonObject) }) continue
            ran++
            val answered = modelAnswers(vec, problems)
            if (dump != null) Files.writeString(dump.resolve("${vec["name"]!!.jsonPrimitive.content}.json"), answered.toString())
        }
        println("ConformanceVectorTest (the model on unfilled vectors): $ran vector(s) run${dump?.let { ", answers in $it" } ?: ""}")
        assertEquals(emptyList(), problems, "the model on the unfilled vectors:\n" + problems.joinToString("\n"))
    }

    /** [vec] run through the model on both lenses, every op of every step; returns it with the model's answers as
     *  each step's `expect`. An unknown op or a fault inside the model is added to [problems]. */
    private fun modelAnswers(vec: JsonObject, problems: MutableList<String>): JsonObject {
        val name = vec["name"]!!.jsonPrimitive.content
        val steps = vec["steps"]!!.jsonArray
        val answers = Array(steps.size) { HashMap<String, Any>() }
        for (arm in Arm.entries) {
            val lens = if (arm == Arm.LEFT) "L" else "R"
            val sim = newSim(vec["contract"]?.jsonPrimitive?.int ?: 1, vec["features"]?.jsonPrimitive?.int)
            var now = 0L
            try {
                steps.forEachIndexed { i, stepEl ->
                    val rcs = ArrayList<Int>()
                    for (opEl in stepEl.jsonObject["ops"]!!.jsonArray) {
                        val op = opEl.jsonObject
                        when {
                            "tick" in op -> now = op["tick"]!!.jsonPrimitive.long
                            "lease" in op -> sim.conformanceLease(arm, op["lease"]!!.jsonPrimitive.content == "acquire", now)
                            "msg" in op -> rcs += if (sim.conformanceMessage(arm, hex(op["msg"]!!.jsonPrimitive.content), now)) 0 else -1
                            "flags" in op -> sim.conformanceControl(arm, wm.damage.core.wire.DamageMsg.OP_FLAGS_SET, op["flags"]!!.jsonPrimitive.int, now)
                            "cachesize" in op -> sim.conformanceControl(arm, wm.damage.core.wire.DamageMsg.OP_CACHE_SIZE, op["cachesize"]!!.jsonPrimitive.int, now)
                            "stock" in op -> sim.stockRepaint(arm, now)
                            "wake" in op -> sim.conformanceWake(arm, now)
                            else -> { problems += "$name step $i: unknown op $op"; return@forEachIndexed }
                        }
                    }
                    val a = answers[i]
                    a[lens] = "%08x".format(sim.shadowCrc32(arm))
                    a["P$lens"] = "%08x".format(sim.panelCrc32(arm))
                    a["rc$lens"] = rcs
                    a["ref$lens"] = sim.refusalRecord(arm)
                    a["W$lens"] = sim.conformancePlan(arm, now)
                    a["G$lens"] = sim.conformanceGen(arm)
                    val sent = sim.conformanceNotifies(arm)
                    if (arm == Arm.RIGHT) a["N"] = sent.map { maskedHex(it) }
                    else if (sent.isNotEmpty()) problems += "$name step $i: LEFT sent a notify"
                }
            } catch (e: RuntimeException) {
                problems += "$name lens $lens: the model failed: $e"
            }
        }
        return simAnswers(vec, answers)
    }

    /** The runner's C1 comparison on a C1 vector filled with the MODEL's own answers (so it must agree with itself
     *  through the runner — the ops, the mask, the per-lens keys), then with each of `N`, `W` and `G` taken out of one
     *  step (a problem, as a missing `ref` is) and with a body changed (a mismatch). The self-test form skips it. */
    @Test
    fun aC1VectorIsComparedOnNWAndGAndAMissingOneIsAProblem() {
        val src = vectorFiles(vectorDir()).firstOrNull { it.fileName.toString() == "v3-play.json" }
            ?: fail("v3-play.json is not in ${vectorDir()}")
        val problems = ArrayList<String>()
        val answered = modelAnswers(Json.parseToJsonElement(Files.readString(src)).jsonObject, problems)
        assertEquals(emptyList(), problems)
        val steps = answered["steps"]!!.jsonArray
        val last = steps.indexOfFirst { (it.jsonObject["expect"]!!.jsonObject["N"] as JsonArray).isNotEmpty() }
        assertTrue(last > 0, "v3-play notifies at its last step")
        fun variant(name: String, edit: (JsonObject) -> JsonObject) = JsonObject(answered + mapOf("name" to JsonPrimitive(name),
            "steps" to JsonArray(steps.mapIndexed { i, s -> if (i != last) s else JsonObject(s.jsonObject + ("expect" to edit(s.jsonObject["expect"]!!.jsonObject))) })))
        val dir = Files.createTempDirectory("conformance-c1")
        try {
            val set = mapOf("a-self" to variant("a-self") { it }, "b-noN" to variant("b-noN") { JsonObject(it - "N") },
                "c-noW" to variant("c-noW") { JsonObject(it - "W") }, "d-noG" to variant("d-noG") { JsonObject(it - "G") },
                "e-wrongN" to variant("e-wrongN") { JsonObject(it + ("N" to JsonArray(listOf(JsonPrimitive("0800"))))) })
            for ((n, v) in set) Files.writeString(dir.resolve("$n.json"), v.toString())
            val out = runNormal(vectorFiles(dir))
            assertTrue(out.problems.none { it.startsWith("a-self") }, "the model agrees with itself through the runner: ${out.problems}")
            assertEquals(5 * steps.size * 2, out.compared, "every lens-step of the five compared")
            assertTrue(out.problems.any { it.startsWith("b-noN step $last: no notify (N)") }, "${out.problems}")
            assertTrue(out.problems.any { it.startsWith("c-noW step $last lens") }, "${out.problems}")
            assertTrue(out.problems.any { it.startsWith("d-noG step $last lens") }, "${out.problems}")
            assertTrue(out.problems.any { it.startsWith("e-wrongN step $last: notify bodies") }, "${out.problems}")
            val self = runSelfTest(vectorFiles(dir))
            assertEquals(emptyList(), self.problems, "an engine vector has no self-test form, and that is not a skipped drawing vector")
        } finally {
            Files.list(dir).use { s -> s.forEach { Files.delete(it) } }
            Files.delete(dir)
        }
    }

    /** The model's answers in the vectors' own shape (`L`, `R`, `rc`, `ref`, `P`, `N`, `W`, `G`). */
    private fun simAnswers(vec: JsonObject, answers: Array<HashMap<String, Any>>): JsonObject {
        fun lr(k: String, a: HashMap<String, Any>, conv: (Any) -> kotlinx.serialization.json.JsonElement) =
            JsonObject(mapOf("L" to conv(a["${k}L"]!!), "R" to conv(a["${k}R"]!!)))
        fun ints(v: Any) = JsonArray((v as List<*>).map { JsonPrimitive((it as Number).toLong()) })
        val steps = vec["steps"]!!.jsonArray.mapIndexed { i, s ->
            val a = answers[i]
            val e = mapOf("L" to JsonPrimitive(a["L"] as String), "R" to JsonPrimitive(a["R"] as String),
                "rc" to lr("rc", a, ::ints), "ref" to lr("ref", a, ::ints), "P" to lr("P", a) { JsonPrimitive(it as String) },
                "W" to lr("W", a) { JsonPrimitive(it as Long) }, "G" to lr("G", a) { JsonPrimitive(it as Long) },
                "N" to JsonArray((a["N"] as List<*>).map { JsonPrimitive(it as String) }))
            JsonObject(s.jsonObject + ("expect" to JsonObject(e)))
        }
        return JsonObject(vec + ("steps" to JsonArray(steps)))
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

    private companion object {
        /** `FIRMWARE.md` §5.1: today's 0x7f plus bit 7 ENGINE and bit 8 the riders (bit 9 is not in C1). */
        const val C1_FEATURES = 0x1FF
    }
}
