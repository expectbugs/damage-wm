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
import kotlinx.serialization.json.booleanOrNull
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
 * test's output: it is neither a mismatch nor a pass. In the repo's own `firmware/vectors/` it fails the normal
 * path's test (build C1's ruling R1.21: the C answers a vector before it is committed); a set named by
 * `DAMAGE_VECTOR_DIR` keeps the report only. A vector none
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
 * have no self-test form (a step presents nothing, and mode 26 in a step is refused); mode 25 runs live there, as 12
 * and 19 do.
 *
 * **C2** (`FIRMWARE.md` §6.10, §9; the lead's decisions on the build spec B.2 and B.7): `features` may be a list of the
 * builds a vector answers alike on, and the model runs it at each, against the one set of expectations; the op
 * `{"input": [devType, eventId, value]}` (with `"only": "L"`, LEFT alone) runs the record through each lens's input
 * hook, and `{"page": on}` says whether the EvenHub page is in front; `N` carries notify-115 bodies too, in send order
 * with the 114s, fields 15 and 16 dropped; a step of a vector every one of whose builds has bit 10 carries `K` (`{L, R}`:
 * the stock dispatcher's calls in the step), and a missing one is a problem. Such a vector has no self-test form.
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
        return repoVectorDir() ?: fail("firmware/vectors not found above ${System.getProperty("user.dir")}")
    }

    /** The repo's own `firmware/vectors/`, found above the working directory; null when there is none. */
    private fun repoVectorDir(): Path? {
        var d: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (d != null) {
            val v = d.resolve("firmware/vectors")
            if (Files.isDirectory(v)) return v
            d = d.parent
        }
        return null
    }

    /** Build C1's ruling R1.21: a step with no expectation in the repo's own `firmware/vectors/` fails the test — the
     *  C answers a vector before it is committed there (the fork's `host/run_vectors.py --write`). Another set
     *  ([dir] named by `DAMAGE_VECTOR_DIR`: the fuzz, a stage's inputs) keeps the not-filled report only. Each
     *  not-filled vector of [out], with its steps, becomes a problem when [dir] is [repo]. */
    private fun notFilledFailsInTheRepo(dir: Path, repo: Path?, out: Outcome) {
        if (repo == null || !Files.isSameFile(dir, repo)) return
        // build C3's exceptions for inputs committed before the fork's runner filled them — the `v5-*` set (ended with
        // X2D's phase 2), stage X3D's `v5-ship-*` and `v5-x3-hint` (ended with X3D's comparison), stage X3T's Encode
        // and Matrix ship vectors (ended with X5T's comparison), APK 0.83 stage SV's scroll and notice ship vectors (ended
        // with SV's comparison), C3 stage SVR's ring-list ship vectors with the act the fast-path fit's merge wrote
        // again (ended with SVR's comparison, X8T), C3 stage SV2's ship acts of Crush, Flip and the new Matrix with the
        // acts MX2 and X1MX2 moved (ended with SV2's comparison, X9T) and C3 stage SV3's one exception for two stages —
        // the ship acts K1-2 stage 1 moved, C3 stage X2MX2's Warp, Flip and Encode acts and its new Crush act, and SV3's
        // new act (ended with SV3's comparison, X10T), Music's stage L's two lane shapes and K1-2 stage 4f's list-1 acts
        // with the four ring-tap acts it wrote again (both ended with K1-2 stage 4h's comparison), and C3 stage DW-3's for
        // the app switcher's acts with the acts DW-3 and DW-3h wrote again (ended with DW-3h's comparison), and C3 stage
        // OM-V's for every ship act written again after OM-1 (ended with OM-V's comparison) — are gone: every unfilled
        // file in the repo fails like any other.
        // C3 stage DM-2a, one exception for its two new files (decisions DW 6, 7) — `v5-dm-rain-fire` (`make_vectors.py`) and
        // Main's Matrix ship act — exactly the names in [DM2A_UNFILLED], which wait for the fork's fill (F1, after DM-2b):
        // reported "not filled" with a count and run through the model ([theUnfilledVectorsRunThroughTheModel]); a filled
        // one names the exception stale. The comparison after the fill removes this exception.
        val dm2a = out.notFilled.filter { it.substringBefore(" (") in DM2A_UNFILLED }
        if (dm2a.isNotEmpty()) println("ConformanceVectorTest: ${dm2a.size} DM-2a vector(s) not filled until the comparison: ${dm2a.joinToString("; ")}")
        for (n in out.notFilled - dm2a.toSet())
            out.problems += "$n: not filled — a step in firmware/vectors/ with no expectation fails the test (ruling R1.21)"
        for (m in DM2A_UNFILLED) if (Files.exists(dir.resolve("$m.json")) && out.notFilled.none { it.startsWith("$m ") })
            out.problems += "$m: filled — the stage DM-2a exception DM2A_UNFILLED is stale, remove it"
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
            val sets = featureSets(vec)
            if (featuresOverride != null && sets != null) continue      // the override is for vectors written without one
            out.ran += name
            val c2Vector = sets != null && sets.all { it and BIND_FEATURE != 0 }
            for (features in sets ?: listOf(featuresOverride)) for (arm in Arm.entries) {
                val lens = if (arm == Arm.LEFT) "L" else "R"
                // one name per run: a vector run at two builds says which one disagreed
                val name = if ((sets?.size ?: 0) > 1) "$name@0x${features!!.toString(16)}" else name
                val sim = newSim(contract, features)
                var now = 0L
                steps.forEachIndexed { i, stepEl ->
                    val step = stepEl.jsonObject
                    val rcs = ArrayList<Int>()
                    for (opEl in step["ops"]!!.jsonArray)
                        now = runOp(sim, arm, opEl.jsonObject, now, rcs) ?: run { out.problems += "$name step $i: unknown op $opEl"; return@forEachIndexed }
                    val sent = sim.conformanceSent(arm)              // taken every step, so a step's bodies are its own
                    val stockCalls = sim.conformanceStockCalls(arm)
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
                    if (sets != null) compareC1(name, i, arm, lens, expect, sim, now, sent, out)   // the vector's own key, not the override
                    // §6.10 (C2): the stock dispatcher's calls in the step
                    val wantK = expect["K"]?.jsonObject?.get(lens)?.jsonPrimitive?.int
                    if (wantK == null) { if (c2Vector) out.problems += "$name step $i lens $lens: no stock-call (K) expectation on a C2 vector" }
                    else if (wantK != stockCalls) out.problems += "$name step $i lens $lens: stock calls $stockCalls, the C gives $wantK"
                    out.compared++
                }
            }
        }
        return out
    }

    /** A vector's `features`: absent (null), one set, or (C2, §9) a list of the sets it answers alike on. */
    private fun featureSets(vec: JsonObject): List<Int>? = when (val f = vec["features"]) {
        null -> null
        is JsonArray -> f.map { it.jsonPrimitive.int }
        else -> listOf(f.jsonPrimitive.int)
    }

    /** One op of a step on [arm]'s lens; the clock after it, or null for an op this runner does not know. */
    private fun runOp(sim: GlassFirmwareSim, arm: Arm, op: JsonObject, now: Long, rcs: MutableList<Int>): Long? {
        when {
            "tick" in op -> return op["tick"]!!.jsonPrimitive.long
            "lease" in op -> sim.conformanceLease(arm, op["lease"]!!.jsonPrimitive.content == "acquire", now)
            "msg" in op -> rcs += if (sim.conformanceMessage(arm, hex(op["msg"]!!.jsonPrimitive.content), now)) 0 else -1
            "flags" in op -> sim.conformanceControl(arm, wm.damage.core.wire.DamageMsg.OP_FLAGS_SET, op["flags"]!!.jsonPrimitive.int, now)
            "cachesize" in op -> sim.conformanceControl(arm, wm.damage.core.wire.DamageMsg.OP_CACHE_SIZE, op["cachesize"]!!.jsonPrimitive.int, now)
            // `FIRMWARE.md` §9: the stock compositor's own repaint, the event each harness issues for itself after a
            // release point
            "stock" in op -> sim.stockRepaint(arm, now)
            "wake" in op -> sim.conformanceWake(arm, now)          // §5.10
            // §6.10 (C2): the record through the input hook — on LEFT alone with "only": "L" (RIGHT's answer dropped)
            "input" in op -> {
                val only = op["only"]?.jsonPrimitive?.content
                if (only != null && only != "L") return null
                val r = op["input"]!!.jsonArray.map { it.jsonPrimitive.long }
                if (only == null || arm == Arm.LEFT) sim.conformanceInput(arm, r[0].toInt(), r[1], r[2], now)
            }
            "page" in op -> sim.conformancePage(arm, pageOn(op["page"]!!.jsonPrimitive) ?: return null)
            else -> return null
        }
        return now
    }

    /** §6.10's `{"page": on}`: whether the EvenHub page is in front — true, or the build spec's spelling "evenhub";
     *  false, or "stock", a stock page. Null: neither. */
    private fun pageOn(p: JsonPrimitive): Boolean? = p.booleanOrNull ?: when (p.content) { "evenhub" -> true; "stock" -> false; else -> null }

    private fun newSim(contract: Int, features: Int?) = GlassFirmwareSim().also {
        it.damageContract = contract
        it.damageFeatures = features
        it.clockIsOsTick = true           // §5.10: the `tick` op's value is the OS tick
    }

    /** C1's three expectations (decision A.3) on a filled step of a vector that carries `features`. */
    private fun compareC1(name: String, i: Int, arm: Arm, lens: String, expect: JsonObject, sim: GlassFirmwareSim, now: Long,
                          sent: List<Pair<Int, ByteArray>>, out: Outcome) {
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
        val gotN = sent.map { (field, body) -> maskedHex(field, body) }
        if (wantN == null) out.problems += "$name step $i: no notify (N) expectation on a C1 vector"
        else if (wantN != gotN) out.problems += "$name step $i: notify bodies $gotN, the C gives $wantN"
    }

    /** Decision A.3's mask: fields 9 (µs) and 11 (uptime) dropped from a 114 body before hex; C2 (§6.10): fields 15
     *  (uptime) and 16 (µs) from a 115 body. READING: `N` lists both kinds as plain hex, in send order, with no mark of
     *  which field a body came in (§6.10 names none). */
    private fun maskedHex(field: Int, body: ByteArray): String {
        val drop = if (field == GlassFirmwareSim.APPLIED_FIELD) setOf(15, 16) else setOf(9, 11)
        return wm.damage.core.wire.Pb.fields(body).filter { it.field !in drop }
            .joinToString("") { f -> val b = if (f.bytes != null) wm.damage.core.wire.Pb.l(f.field, f.bytes!!) else wm.damage.core.wire.Pb.v(f.field, f.varint!!)
                                     b.joinToString("") { "%02x".format(it) } }
    }

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
        // a vector with a `stock` op has no self-test form whether or not it releases the lease (C2's meeting,
        // 2026-11-18: `v4-local` — a `stock` op and no release — was passed over below and then flagged by the closing
        // check, which had excused the earlier `stock` vectors only because each of them also releases)
        val noForm = HashSet<String>()
        for (f in files) {
            val vec = Json.parseToJsonElement(Files.readString(f)).jsonObject
            val name = vec["name"]!!.jsonPrimitive.content
            val steps = vec["steps"]!!.jsonArray
            val prefix = filledPrefix(name, steps, out)
            if (prefix == 0) { unfilled += name; continue }
            if (name == "v1-lease") continue      // the lapse inside it refuses the step itself: no self-test form
            val ops = steps.flatMap { it.jsonObject["ops"]!!.jsonArray.map { o -> o.jsonObject } }
            if (ops.any { "lease" in it && it["lease"]!!.jsonPrimitive.content != "acquire" }) continue
            if (ops.any { "stock" in it }) { noForm += name; continue }          // no self-test form: a step cannot repaint the panel
            // §5.10: an engine vector has no self-test form (a wake, or mode 26 alone or in a batch); §6.10 (C2): nor
            // one with an input or a page op
            if (ops.any { "wake" in it || "input" in it || "page" in it || ("msg" in it && carries26(hex(it["msg"]!!.jsonPrimitive.content))) }) { engine += name; continue }
            val contract = vec["contract"]?.jsonPrimitive?.int ?: 1
            val sets = featureSets(vec)
            for (features in sets ?: listOf(null)) for (arm in Arm.entries) {
                val lens = if (arm == Arm.LEFT) "L" else "R"
                val label = if ((sets?.size ?: 0) > 1) "$name@0x${features!!.toString(16)}" else name   // which build disagreed
                val sim = newSim(contract, features)
                var now = 1000L
                sim.conformanceLease(arm, true, now)
                assertTrue(sim.conformanceMessage(arm, wm.damage.core.wire.CfwModes.selfTestBegin(), now), "$label $lens: begin refused")
                steps.forEachIndexed { i, stepEl ->
                    val step = stepEl.jsonObject
                    val rcs = ArrayList<Int>()
                    for (opEl in step["ops"]!!.jsonArray) {
                        val op = opEl.jsonObject
                        when {
                            "tick" in op -> now = op["tick"]!!.jsonPrimitive.long
                            "msg" in op -> {
                                val m = hex(op["msg"]!!.jsonPrimitive.content)
                                val live = (m[0].toInt() and 0x7F).let { it == 12 || it == 19 || it == 25 }   // §5.10: mode 25 runs live
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
                            else -> { out.problems += "$label step $i: no self-test form for $op"; return@forEachIndexed }
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
                    if (expect[lens]!!.jsonPrimitive.content != got) out.problems += "$label step $i lens $lens: scratch crc $got, the normal path gives ${expect[lens]!!.jsonPrimitive.content}"
                    val wantRc = expect["rc"]!!.jsonObject[lens]!!.jsonArray.map { it.jsonPrimitive.int }
                    if (wantRc != rcs) out.problems += "$label step $i lens $lens: return codes $rcs, the normal path gives $wantRc"
                    // the refusal's copy sequence is the live count (0 here, nothing presents): not compared
                    expect["ref"]?.jsonObject?.get(lens)?.jsonArray?.map { it.jsonPrimitive.int }?.let { w ->
                        val g = sim.refusalRecord(arm)
                        if (listOf(w[0], w[1], w[3]) != listOf(g[0], g[1], g[3])) out.problems += "$label step $i lens $lens: refusal record $g, the normal path gives $w"
                    }
                    val liveCrc = "%08x".format(sim.shadowCrc32(arm))
                    if (liveCrc != zeroCrc) out.problems += "$label step $i lens $lens: the live shadow changed ($liveCrc)"
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
            val ran = out.ran.any { it == name || it.startsWith("$name@") }     // a vector run at several builds is named per build
            if (name != "v1-lease" && !releases && name !in noForm && name !in unfilled && name !in engine && !ran) out.problems += "$name: a drawing vector ran no self-test step"
        }
        return out
    }

    /** The normal path over the set in [dir] as [everyVectorMatchesOnBothLenses] takes it — the run, its report and
     *  ruling R1.21's not-filled rule — in one function, so the rule's own pin goes through the path the test takes
     *  (build C1's ruling B-N5, `C1-R5-decisions.md`). [repo]: the repo's own `firmware/vectors/`, or null. */
    private fun normalPath(dir: Path, repo: Path?): Outcome {
        val files = vectorFiles(dir)
        assertTrue(files.isNotEmpty(), "no vectors in $dir")
        val out = runNormal(files)
        report("normal path", out)
        notFilledFailsInTheRepo(dir, repo, out)
        return out
    }

    @Test
    fun everyVectorMatchesOnBothLenses() {
        val out = normalPath(vectorDir(), repoVectorDir())
        assertTrue(out.compared > 0, "no vector step was compared")
        // §7.13 "build-neutral" (decisions Y, C-4): the repo's every v3 and v4 vector lists the bit-11 build, so the fork's
        // runner runs it on the C3 build — a list that loses 0xdff would drop that comparison silently
        if (isRepo(vectorDir())) for (f in vectorFiles(vectorDir())) {
            val vec = Json.parseToJsonElement(Files.readString(f)).jsonObject
            val name = vec["name"]!!.jsonPrimitive.content
            if (name.startsWith("v3-") || name.startsWith("v4-"))
                assertTrue(featureSets(vec)?.contains(C3_FEATURES) == true, "$name: its features ${vec["features"]} do not list 0xdff (§7.13)")
        }
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

    /** The lead's decision B.2 on C2's build spec (option a): the v1 and v2 vectors answer alike on a C2 build (features
     *  0x5ff) too — a model change for C2 that leaks into today's behaviour shows here first. */
    @Test
    fun theV1AndV2VectorsAnswerTheSameOnAC2Build() {
        val out = runNormal(vectorFiles(vectorDir()), featuresOverride = C2_FEATURES)
        report("normal path, features 0x5ff", out)
        assertTrue(out.compared > 0, "no vector step was compared")
        assertEquals(emptyList(), out.problems, "a C2 build answers a v1/v2 vector differently:\n" + out.problems.joinToString("\n"))
    }

    /** Build C3 (`FIRMWARE.md` §7.13, "build-neutral"): the v1 and v2 vectors answer alike on a bit-11 build (features
     *  0xdff) with EFFECTS clear; the v3 and v4 vectors carry 0xdff in their own `features` lists (`make_vectors.py`) and
     *  are run there by [everyVectorMatchesOnBothLenses]. */
    @Test
    fun theV1AndV2VectorsAnswerTheSameOnAC3Build() {
        val out = runNormal(vectorFiles(vectorDir()), featuresOverride = C3_FEATURES)
        report("normal path, features 0xdff", out)
        assertTrue(out.compared > 0, "no vector step was compared")
        assertEquals(emptyList(), out.problems, "a C3 build answers a v1/v2 vector differently:\n" + out.problems.joinToString("\n"))
    }

    /** Build C3 (spec §5, the fold invariant; `FIRMWARE.md` §7.13): vectors that share a `"pair"` key hold the same
     *  messages under two wake schedules of an in-place program, and each harness compares their final `P`. The model's
     *  own final panels must be equal on each lens, filled or not; where both are filled, so must the C's; a key held by
     *  one vector or by more than two is a problem. */
    @Test
    fun everyPairOfVectorsEndsOnTheSamePanel() {
        val files = vectorFiles(vectorDir())
        // decisions Y, C-4: in the repo the four fold pairs must be there — with none the comparison below holds nothing
        if (isRepo(vectorDir())) {
            val keys = files.mapNotNull { Json.parseToJsonElement(Files.readString(it)).jsonObject["pair"]?.jsonPrimitive?.content }.toSet()
            assertTrue(keys.containsAll(FOLD_PAIRS), "the repo's fold pairs ${FOLD_PAIRS - keys} are missing (have $keys)")
        }
        val problems = pairProblems(files)
        assertEquals(emptyList(), problems, "the fold pairs:\n" + problems.joinToString("\n"))
    }

    /** Whether [dir] is the repo's own `firmware/vectors/` (not a set `DAMAGE_VECTOR_DIR` names). */
    private fun isRepo(dir: Path): Boolean = repoVectorDir()?.let { Files.isSameFile(dir, it) } == true

    private fun pairProblems(files: List<Path>): List<String> {
        val problems = ArrayList<String>()
        val pairs = files.map { Json.parseToJsonElement(Files.readString(it)).jsonObject }.filter { it["pair"] != null }
            .groupBy { it["pair"]!!.jsonPrimitive.content }
        for ((key, vecs) in pairs) {
            if (vecs.size != 2) { problems += "pair $key: ${vecs.size} vector(s), not two"; continue }
            val finals = vecs.map { v ->
                val answered = modelAnswers(v, problems)
                val last = answered["steps"]!!.jsonArray.last().jsonObject["expect"]!!.jsonObject["P"]!!.jsonObject
                val filled = v["steps"]!!.jsonArray.last().jsonObject.let { s -> if (filled(s)) s["expect"]!!.jsonObject["P"]?.jsonObject else null }
                Triple(v["name"]!!.jsonPrimitive.content, last, filled)
            }
            for (lens in listOf("L", "R")) {
                if (finals[0].second[lens] != finals[1].second[lens])
                    problems += "pair $key lens $lens: the model ends ${finals[0].first} on ${finals[0].second[lens]} and ${finals[1].first} on ${finals[1].second[lens]}"
                val c0 = finals[0].third?.get(lens); val c1 = finals[1].third?.get(lens)
                if (c0 != null && c1 != null && c0 != c1) problems += "pair $key lens $lens: the C ends them on $c0 and $c1"
            }
        }
        return problems
    }

    /** The pair rule itself on vectors built here (inputs this file's own, unfilled): one OWN SHRINK in both axes drawn
     *  under two wake schedules ends on one panel (no problem); a "pair" over two different programs is a problem, and
     *  so is a key held by one vector. */
    @Test
    fun aPairIsComparedOnItsFinalPanel() {
        fun h(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        fun u16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        // §7.2: version 2, OWN; one track (200, 100) 120 × 60, backdrop none; SHRINK 5 steps, w 120 → w1, h 60 → 15, centre
        fun shrink(w1: Int): ByteArray {
            val seg = byteArrayOf(5, 5, 0, 0) + u16(1) + ByteArray(12) + byteArrayOf(15, 9, 0, 0) + u16(0) + u16(60) + u16(15) +
                byteArrayOf(5, 0, 0, 0) + u16(120) + u16(w1) + u16(0) + u16(0)
            val track = byteArrayOf(1, 0xFF.toByte(), 0, 0) + u16(200) + u16(100) + u16(120) + u16(60) + seg
            return byteArrayOf(2, 1) + u16(8 + track.size) + byteArrayOf(1, 0, 0, 0) + track
        }
        fun op(k: String, v: Any) = JsonObject(mapOf(k to when (v) { is String -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v); else -> JsonPrimitive(v as Number) }))
        fun step(vararg ops: JsonObject) = JsonObject(mapOf("ops" to JsonArray(ops.toList()), "expect" to JsonObject(emptyMap())))
        val picture = ByteArray(320 * 480) { (it * 37 + it / 320 * 11).toByte() }
        fun vec(name: String, pair: String, w1: Int, wakes: List<Long>) = JsonObject(mapOf("name" to JsonPrimitive(name), "contract" to JsonPrimitive(2),
            "panel" to JsonPrimitive(true), "pair" to JsonPrimitive(pair), "features" to JsonArray(listOf(JsonPrimitive(C3_FEATURES))),
            "steps" to JsonArray(listOf(step(op("tick", 1000), op("lease", "acquire"), op("flags", 0x7C),
                op("msg", h(wm.damage.core.wire.CfwModes.keyframe(wm.damage.core.gfx.Zl.encodeCfw(picture)))),
                op("msg", h(byteArrayOf(19) + u16(64) + u16(shrink(w1).size) + shrink(w1))), op("tick", 5000),
                op("msg", h(byteArrayOf(26, 0, 0, 20) + u16(64) + u16(1) + u16(0) + ByteArray(8))))) +
                wakes.map { step(op("tick", it), op("wake", true)) })))
        val dir = Files.createTempDirectory("conformance-pair")
        try {
            val set = listOf(vec("a1", "a", 30, listOf(5020, 5040, 5060, 5080)), vec("a2", "a", 30, listOf(5040, 5080)),
                vec("b1", "b", 30, listOf(5020, 5080)), vec("b2", "b", 60, listOf(5080)), vec("c1", "c", 30, listOf(5080)))
            for (v in set) Files.writeString(dir.resolve("${v["name"]!!.jsonPrimitive.content}.json"), v.toString())
            val problems = pairProblems(vectorFiles(dir))
            assertTrue(problems.none { it.startsWith("pair a") }, "a fold pair ends on one panel: $problems")
            assertTrue(problems.any { it.startsWith("pair b lens L: the model ends") }, "$problems")
            assertTrue(problems.any { it == "pair c: 1 vector(s), not two" }, "$problems")
        } finally {
            Files.list(dir).use { s -> s.forEach { Files.delete(it) } }
            Files.delete(dir)
        }
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
        val sets = featureSets(vec)
        // C2: a vector run at several builds is answered at each; the builds' answers must agree, since the vector says
        // they answer alike (the first build's are the ones kept)
        val perSet = (sets ?: listOf(null)).map { features ->
            val answers = Array(steps.size) { HashMap<String, Any>() }
            for (arm in Arm.entries) {
                val lens = if (arm == Arm.LEFT) "L" else "R"
                val sim = newSim(vec["contract"]?.jsonPrimitive?.int ?: 1, features)
                var now = 0L
                try {
                    steps.forEachIndexed { i, stepEl ->
                        val rcs = ArrayList<Int>()
                        for (opEl in stepEl.jsonObject["ops"]!!.jsonArray)
                            now = runOp(sim, arm, opEl.jsonObject, now, rcs) ?: run { problems += "$name step $i: unknown op $opEl"; return@forEachIndexed }
                        val a = answers[i]
                        a[lens] = "%08x".format(sim.shadowCrc32(arm))
                        a["P$lens"] = "%08x".format(sim.panelCrc32(arm))
                        a["rc$lens"] = rcs
                        a["ref$lens"] = sim.refusalRecord(arm)
                        a["W$lens"] = sim.conformancePlan(arm, now)
                        a["G$lens"] = sim.conformanceGen(arm)
                        a["K$lens"] = sim.conformanceStockCalls(arm)
                        val sent = sim.conformanceSent(arm)
                        if (arm == Arm.RIGHT) a["N"] = sent.map { (f, b) -> maskedHex(f, b) }
                        else if (sent.isNotEmpty()) problems += "$name step $i: LEFT sent a notify"
                    }
                } catch (e: RuntimeException) {
                    problems += "$name lens $lens: the model failed: $e"
                }
            }
            features to answers
        }
        for ((features, answers) in perSet.drop(1)) answers.forEachIndexed { i, a ->
            if (a != perSet[0].second[i]) problems += "$name step $i: the model answers it differently at features 0x${features!!.toString(16)} " +
                "than at 0x${perSet[0].first!!.toString(16)}, where the vector says the builds answer alike"
        }
        return simAnswers(vec, perSet[0].second, withK = sets?.any { it and BIND_FEATURE != 0 } == true)
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
            val builds = featureSets(answered)!!.size                // C2: a features list runs at each of its builds
            assertEquals(5 * steps.size * 2 * builds, out.compared, "every lens-step of the five compared")
            assertTrue(out.problems.any { it.startsWith("b-noN") && "step $last: no notify (N)" in it }, "${out.problems}")
            assertTrue(out.problems.any { it.startsWith("c-noW") && "step $last lens" in it }, "${out.problems}")
            assertTrue(out.problems.any { it.startsWith("d-noG") && "step $last lens" in it }, "${out.problems}")
            assertTrue(out.problems.any { it.startsWith("e-wrongN") && "step $last: notify bodies" in it }, "${out.problems}")
            val self = runSelfTest(vectorFiles(dir))
            assertEquals(emptyList(), self.problems, "an engine vector has no self-test form, and that is not a skipped drawing vector")
        } finally {
            Files.list(dir).use { s -> s.forEach { Files.delete(it) } }
            Files.delete(dir)
        }
    }

    /** C2's runner (§6.10) on a vector built here — its inputs this file's own, its expectations the model's: the model
     *  agrees with itself through the runner (the ops `input`, with `"only": "L"`, and `page`; `K`; the 115 bodies in
     *  `N`), and a missing `K`, a wrong one and a wrong report are each a problem; it has no self-test form. And a C1
     *  vector given the features list `[0x1ff, 0x5ff]` (B.2) runs at both builds against one set of expectations. */
    @Test
    fun aC2VectorIsComparedOnKAndItsReportsAndAFeaturesListRunsAtEachBuild() {
        fun h(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        fun u16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        // §5.4: one track at (0, 100) 64×8, backdrop 2, one FILL of level 9 over 3 steps
        val prog = byteArrayOf(1, 1) + u16(48) + byteArrayOf(1, 2, 0, 0) + u16(0) + u16(100) + u16(64) + u16(8) +
            byteArrayOf(1, 3, 0, 9) + ByteArray(28)
        fun op(k: String, v: Any) = JsonObject(mapOf(k to when (v) { is String -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v); is List<*> -> JsonArray(v.map { JsonPrimitive(it as Number) }); else -> JsonPrimitive(v as Number) }))
        fun input(dev: Int, id: Int, only: Boolean = false) = JsonObject(op("input", listOf(dev, id, 0)) + (if (only) mapOf("only" to JsonPrimitive("L")) else emptyMap()))
        fun step(vararg ops: JsonObject) = JsonObject(mapOf("ops" to JsonArray(ops.toList()), "expect" to JsonObject(emptyMap())))
        val vec = JsonObject(mapOf("name" to JsonPrimitive("c2-runner"), "contract" to JsonPrimitive(2), "panel" to JsonPrimitive(true),
            "features" to JsonArray(listOf(JsonPrimitive(C2_FEATURES))), "steps" to JsonArray(listOf(
                step(op("tick", 1000), op("lease", "acquire"), op("flags", 0x1C),
                    op("msg", h(wm.damage.core.wire.CfwModes.keyframe(wm.damage.core.gfx.Zl.encodeCfw(ByteArray(320 * 480))))),
                    op("msg", h(byteArrayOf(19) + u16(64) + u16(prog.size) + prog)),
                    op("msg", h(byteArrayOf(27, 2, 0) + u16(0xFFFF) + u16(8) + u16(0) + u16(8) + byteArrayOf(1))),
                    op("msg", h(byteArrayOf(27, 0, 0, 0xFF.toByte(), 4, 0, 1, 0, 34) + u16(64) + ByteArray(12)))),
                step(op("tick", 1010), input(4, 4), op("wake", true)),
                step(op("tick", 1020), input(4, 4, only = true)),
                step(op("page", false), input(4, 4), op("page", true), input(1, 0))))))
        val problems = ArrayList<String>()
        val answered = modelAnswers(vec, problems)
        assertEquals(emptyList(), problems)
        val e = answered["steps"]!!.jsonArray.map { it.jsonObject["expect"]!!.jsonObject }
        fun k(i: Int) = e[i]["K"]!!.jsonObject.let { listOf(it["L"]!!.jsonPrimitive.int, it["R"]!!.jsonPrimitive.int) }
        assertEquals(listOf(listOf(0, 0), listOf(1, 1), listOf(1, 0), listOf(2, 2)), (0..3).map { k(it) }, "the stock calls: LEFT alone at step 2")
        assertEquals(1, e[1]["N"]!!.jsonArray.size, "the fire's report in N")
        assertTrue(e[2]["N"]!!.jsonArray.isEmpty() && e[3]["N"]!!.jsonArray.isEmpty(), "LEFT alone, a stock page, a temple tap: no report from RIGHT")
        fun variant(name: String, edit: (Int, JsonObject) -> JsonObject) = JsonObject(answered + mapOf("name" to JsonPrimitive(name),
            "steps" to JsonArray(answered["steps"]!!.jsonArray.mapIndexed { i, s -> JsonObject(s.jsonObject + ("expect" to edit(i, s.jsonObject["expect"]!!.jsonObject))) })))
        val dir = Files.createTempDirectory("conformance-c2")
        try {
            val v3 = Json.parseToJsonElement(Files.readString(vectorFiles(vectorDir()).first { it.fileName.toString() == "v3-play.json" })).jsonObject
            val set = mapOf("a-self" to variant("a-self") { _, x -> x },
                "b-noK" to variant("b-noK") { i, x -> if (i == 1) JsonObject(x - "K") else x },
                "c-wrongK" to variant("c-wrongK") { i, x -> if (i == 2) JsonObject(x + ("K" to JsonObject(mapOf("L" to JsonPrimitive(1), "R" to JsonPrimitive(1))))) else x },
                "d-wrongN" to variant("d-wrongN") { i, x -> if (i == 1) JsonObject(x + ("N" to JsonArray(listOf(JsonPrimitive("0801"))))) else x },
                "e-v3-both" to JsonObject(v3 + mapOf("name" to JsonPrimitive("e-v3-both"),
                    "features" to JsonArray(listOf(JsonPrimitive(C1_FEATURES), JsonPrimitive(C2_FEATURES))))))
            for ((n, v) in set) Files.writeString(dir.resolve("$n.json"), v.toString())
            val out = runNormal(vectorFiles(dir))
            assertTrue(out.problems.none { it.startsWith("a-self") || it.startsWith("e-v3-both") }, "the model agrees with itself; v3-play at both builds: ${out.problems}")
            assertTrue(out.problems.any { it.startsWith("b-noK step 1 lens L: no stock-call (K)") }, "${out.problems}")
            assertTrue(out.problems.any { it.startsWith("c-wrongK step 2 lens R: stock calls 0") }, "${out.problems}")
            assertTrue(out.problems.any { it.startsWith("d-wrongN step 1: notify bodies") }, "${out.problems}")
            val v3Steps = v3["steps"]!!.jsonArray.size
            assertEquals(4 * 4 * 2 + 2 * v3Steps * 2, out.compared, "every lens-step of the four, and v3-play at both builds")
            assertEquals(emptyList(), runSelfTest(vectorFiles(dir).filter { "v3" !in it.toString() }).problems, "a C2 vector has no self-test form")
        } finally {
            Files.list(dir).use { s -> s.forEach { Files.delete(it) } }
            Files.delete(dir)
        }
    }

    /** The model's answers in the vectors' own shape (`L`, `R`, `rc`, `ref`, `P`, `N`, `W`, `G`; `K` [withK]). */
    private fun simAnswers(vec: JsonObject, answers: Array<HashMap<String, Any>>, withK: Boolean = false): JsonObject {
        fun lr(k: String, a: HashMap<String, Any>, conv: (Any) -> kotlinx.serialization.json.JsonElement) =
            JsonObject(mapOf("L" to conv(a["${k}L"]!!), "R" to conv(a["${k}R"]!!)))
        fun ints(v: Any) = JsonArray((v as List<*>).map { JsonPrimitive((it as Number).toLong()) })
        val steps = vec["steps"]!!.jsonArray.mapIndexed { i, s ->
            val a = answers[i]
            val e = mapOf("L" to JsonPrimitive(a["L"] as String), "R" to JsonPrimitive(a["R"] as String),
                "rc" to lr("rc", a, ::ints), "ref" to lr("ref", a, ::ints), "P" to lr("P", a) { JsonPrimitive(it as String) },
                "W" to lr("W", a) { JsonPrimitive(it as Long) }, "G" to lr("G", a) { JsonPrimitive(it as Long) },
                "N" to JsonArray((a["N"] as List<*>).map { JsonPrimitive(it as String) })) +
                (if (withK) mapOf("K" to lr("K", a) { JsonPrimitive(it as Int) }) else emptyMap())
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

    /** Build C1's ruling R1.21, the rule itself: a set holding a vector filled but for one step fails when the set is
     *  the repo's own `firmware/vectors/` (the step is named), and keeps the report only when it is another set. Ruling
     *  B-N5 (`C1-R5-decisions.md`): through [normalPath], the function [everyVectorMatchesOnBothLenses] runs. */
    @Test
    fun aStepWithNoExpectationInTheReposVectorsFailsTheTest_R1_21() {
        val src = vectorFiles(vectorDir()).firstOrNull { it.fileName.toString() == "v1-keyframe.json" }
            ?: fail("v1-keyframe.json is not in ${vectorDir()}: the rule's test is built from it")
        val base = Json.parseToJsonElement(Files.readString(src)).jsonObject
        val steps = base["steps"]!!.jsonArray
        val last = steps.size - 1
        val unfilled = JsonObject(base + mapOf("name" to JsonPrimitive("a-one-unfilled"), "steps" to JsonArray(steps.mapIndexed { i, s ->
            if (i == last) JsonObject(s.jsonObject + ("expect" to JsonObject(emptyMap()))) else s })))
        val dir = Files.createTempDirectory("conformance-r21")
        val other = Files.createTempDirectory("conformance-r21-other")
        try {
            Files.writeString(dir.resolve("a-one-unfilled.json"), unfilled.toString())
            // a `v5-` name too: build C3's exception for the v5 inputs ended with X2D's phase 2, so it fails like any other
            Files.writeString(dir.resolve("v5-one-unfilled.json"), JsonObject(unfilled + ("name" to JsonPrimitive("v5-one-unfilled"))).toString())
            // stage X3D's exception for `v5-ship-*` and `v5-x3-hint` ended with its comparison: they fail like any other
            for (n in listOf("v5-ship-one-unfilled", X3_HINT, "${X3_HINT}2"))
                Files.writeString(dir.resolve("$n.json"), JsonObject(unfilled + ("name" to JsonPrimitive(n))).toString())
            // stage X3T's exception for Encode's and Matrix's ship vectors ended with X5T's comparison: one of their names
            // fails like any other, as does a name that only begins like it
            val x3t = "v5-ship-select-encode-352"
            for (n in listOf(x3t, "${x3t}2"))
                Files.writeString(dir.resolve("$n.json"), JsonObject(unfilled + ("name" to JsonPrimitive(n))).toString())
            // C3 stage SVR's exception for its ring vectors and the moved act ended with its comparison (X8T), and SV3's,
            // which held its name here again (K1-2 stage 1 moved it), with SV3's comparison (X10T): that name, a name that
            // only begins like it and a ring name no spec makes fail like any other
            val svr = "v5-ship-ring-depth-352"
            for (n in listOf(svr, "${svr}2", "v5-ship-ring-one-unfilled"))
                Files.writeString(dir.resolve("$n.json"), JsonObject(unfilled + ("name" to JsonPrimitive(n))).toString())
            // C3 stage SV2's exception for its new acts and the acts it wrote again ended with its comparison (X9T), and SV3's,
            // which held its new act here again (K1-2 stage 1 moved it), with SV3's comparison (X10T): that act, one it wrote
            // again, a name that only begins like one and a Crush name no spec makes fail like any other
            val sv2 = "v5-ship-ring-crush-352"; val sv2w = "v5-ship-select-matrix-352"
            for (n in listOf(sv2, "${sv2}2", sv2w, "v5-ship-ring-crush-one-unfilled"))
                Files.writeString(dir.resolve("$n.json"), JsonObject(unfilled + ("name" to JsonPrimitive(n))).toString())
            // C3 stage SV3's exception (K1-2 stage 1's moved acts and X2MX2's, one set) ended with its comparison (X10T):
            // X2MX2's act and a name that only begins like it fail like any other
            val x2 = "v5-ship-select-warp-352"
            for (n in listOf(x2, "${x2}2"))
                Files.writeString(dir.resolve("$n.json"), JsonObject(unfilled + ("name" to JsonPrimitive(n))).toString())
            // C3 stage DM-2a's exception holds exactly its two names: each is reported, not failed, and a name that only
            // begins like one fails like any other; the comparison after the fork's fill removes this block and the filter
            val dm2a = DM2A_UNFILLED.sorted()
            assertEquals(listOf("v5-dm-rain-fire", "v5-ship-ring-matrix-352"), dm2a, "stage DM-2a's names")
            for (n in dm2a + dm2a.map { "${it}2" })
                Files.writeString(dir.resolve("$n.json"), JsonObject(unfilled + ("name" to JsonPrimitive(n))).toString())
            // the only problems are the rule's own lines: the filled steps agree
            val asRepo = normalPath(dir, dir)
            assertEquals(listOf("a-one-unfilled", "v5-dm-rain-fire2", "v5-one-unfilled", "v5-ship-one-unfilled", sv2, "${sv2}2", "v5-ship-ring-crush-one-unfilled", svr, "${svr}2", "v5-ship-ring-matrix-3522", "v5-ship-ring-one-unfilled", x3t, "${x3t}2", sv2w, x2, "${x2}2", X3_HINT, "${X3_HINT}2").map { "$it (steps $last..$last of ${steps.size}): not filled — a step in " +
                "firmware/vectors/ with no expectation fails the test (ruling R1.21)" }, asRepo.problems, "the repo's own set: each unfilled step fails, by name")
            val asOther = normalPath(dir, other)
            assertEquals(emptyList(), asOther.problems, "another set (DAMAGE_VECTOR_DIR): reported, not failed")
            assertEquals(listOf("a-one-unfilled", "v5-dm-rain-fire", "v5-dm-rain-fire2", "v5-one-unfilled", "v5-ship-one-unfilled", sv2, "${sv2}2", "v5-ship-ring-crush-one-unfilled", svr, "${svr}2", "v5-ship-ring-matrix-352", "v5-ship-ring-matrix-3522", "v5-ship-ring-one-unfilled", x3t, "${x3t}2", sv2w, x2, "${x2}2", X3_HINT, "${X3_HINT}2").map { "$it (steps $last..$last of ${steps.size})" }, asOther.notFilled)
        } finally {
            Files.list(dir).use { s -> s.forEach { Files.delete(it) } }
            Files.delete(dir); Files.delete(other)
        }
    }

    /**
     * Build C3, stage X3D (`C3-decisions.md` AP (1)): the `v5-ship-*` vectors hold the programs the phone ships. Every act
     * of [ShipVectors.specs] is played again by the shell itself on the instant link, and its program bytes — each cache
     * write holding a version-2 program record, every PLAY and PLAY-AFTER of the act — must equal the committed file's,
     * so a changed effect cannot keep a stale vector; a spec without a file, or a `v5-ship-` file no spec makes, fails
     * too. With `DAMAGE_SHIP_DUMP=DIR` the regenerated files are written into DIR (a file's expectations kept for the
     * steps whose inputs did not change) and nothing is compared. Under `DAMAGE_VECTOR_DIR` (another set) it compares
     * nothing and says so.
     *
     * The committed files are the firmware's answered sequences (filled at X3D's comparison; Encode's and Matrix's at
     * X5T's): this row only reads them. APK 0.83's stage SV: Main's scroll effects and the notice's programs are acts of
     * the same row ([ShipVectors.acts]), compared on every record their SETs, PLAYs and COMMITs name
     * ([ShipVectors.namedPrograms]); filled at SV's comparison. C3 stage SVR: Main's list as a ring list
     * ([ShipVectors.ringSpecs]), compared on those records and on its ring form — every LIST and COMMIT of the act
     * ([ShipVectors.ringForms]); filled at SVR's comparison (X8T). C3 stage SV2: Crush's, Flip's and the new Matrix's acts
     * ([ShipVectors.sv2Acts]) and the acts MX2 and X1MX2 moved, compared like the rest; filled at SV2's comparison (X9T).
     * C3 stage X2MX2: the Warp, Flip and Encode acts its item 1 moved, written again, and its new Crush act ([ShipVectors.x2mx2Acts]),
     * compared like the rest. C3 stage SV3: every act that stages Main written again after K1-2 stage 1 (one strip at
     * 256 KiB, band slots per row), and its new act, the withdrawal at 256 KiB ([ShipVectors.sv3Acts]), compared like the
     * rest; filled at SV3's comparison. K1-2 stage 4f: the acts whose tap opens Settings (bound as list 1 since K1-2 stage
     * 3a) written again, and the list-1 acts ([ShipVectors.list1Acts], compared on their ring form too); filled at K1-2
     * stage 4h's comparison. C3 DW-3: the wheel's acts ([ShipVectors.wheelActs], compared on [ShipVectors.wheelForms]) and
     * the 64 acts whose session writes the wheel's block, written again; C3 DW-3h: the 37 stage-5 160 KiB acts that stage
     * Main, written again in today's form; filled at DW-3h's comparison. C3 OM-V: every act written again for OM-1's form
     * of its cache writes (mode 19 now mode 25, the same inflated bytes; their programs compare as before); filled at
     * OM-V's comparison, but Main's Matrix act, which waits under [DM2A_UNFILLED].
     * When a shipped program changes and it fails, regenerate that file's inputs (`DAMAGE_SHIP_DUMP`), have the fork's
     * runner fill them, and compare again before committing — never keep the old answers under new inputs, never hand-edit.
     */
    @Test
    fun theShipVectorsHoldTheProgramsTheShellSends_X3D(): Unit = kotlinx.coroutines.runBlocking {
        val dump = System.getenv("DAMAGE_SHIP_DUMP")?.let { Path.of(it).toAbsolutePath() }
        if (dump == null && !isRepo(vectorDir())) { println("ConformanceVectorTest: DAMAGE_VECTOR_DIR is set — the ship vectors are not compared"); return@runBlocking }
        if (dump != null) Files.createDirectories(dump)
        // `DAMAGE_SHIP_ONLY=a,b`: only the specs whose names hold one of these (one file regenerated or looked at)
        val only = System.getenv("DAMAGE_SHIP_ONLY")?.split(',')?.filter { it.isNotEmpty() }
        // SV-083: the transitions, then Main's scroll effects and the notice's programs ([ShipVectors.acts])
        val specs = ShipVectors.acts().filter { s -> only == null || only.any { it in s.name } }
        val problems = ArrayList<String>()
        if (dump == null && only == null) {
            val files = vectorFiles(vectorDir()).map { it.fileName.toString().removeSuffix(".json") }.filter { it.startsWith(ShipVectors.PREFIX) }.toSet()
            for (n in files - specs.map { it.name }.toSet()) problems += "$n: a ship vector no spec makes"
        }
        for (spec in specs) {
            // a capture that fails its own checks is a problem of its act; the rest are still played (SV-083)
            val got = try { ShipVectors.captureAct(spec) } catch (e: IllegalStateException) { problems += "${spec.name}: the capture failed — ${e.message}"; continue }
                catch (e: AssertionError) { problems += "${spec.name}: the capture did not end — ${e.message}"; continue }
            // a transition's program bytes as X3D compares them; a scroll or notice act's, every record its SETs, PLAYs and
            // COMMITs name with the naming messages (SV-083: version-1 records, named from the session's table too); a ring
            // act's, those and its ring form — every LIST and COMMIT of the act (C3 SVR)
            val project: (JsonObject) -> List<String> = when (spec) {
                is ShipVectors.Spec -> ShipVectors::programs
                is ShipVectors.RingSpec, is ShipVectors.ListSpec -> ShipVectors::ringForms
                is ShipVectors.WheelSpec -> ShipVectors::wheelForms
                else -> ShipVectors::namedPrograms
            }
            val steps = got.vector["steps"]!!.jsonArray.size
            if (dump != null) {
                val p = dump.resolve("${spec.name}.json")
                val merged = ShipVectors.merged(p, got.vector)
                Files.writeString(p, ShipVectors.format(merged))
                val kept = merged["steps"]!!.jsonArray.count { filled(it.jsonObject) }
                println("ShipVectors: wrote ${p.fileName}: $steps steps ($kept expectations kept), ${Files.size(p)} B; " +
                    "program records ${if (spec is ShipVectors.Spec) ShipVectors.programBytes(got.vector) else ShipVectors.namedProgramBytes(got.vector)} B; " +
                    "${got.owed.joinToString(" | ")}; the model's C: ${got.halves.joinToString(" | ")}")
                continue
            }
            val f = vectorDir().resolve("${spec.name}.json")
            if (!Files.exists(f)) { problems += "${spec.name}: no file — the act the shell plays is in no vector (DAMAGE_SHIP_DUMP writes it)"; continue }
            val want = project(Json.parseToJsonElement(Files.readString(f)).jsonObject)
            val now = project(got.vector)
            if (want.isEmpty()) problems += "${spec.name}: the file holds no program"
            // (C3 FF's exception for the act the fast-path fit moved went with C3 SVR's merge, C3 MX2's for the acts Matrix
            // and X1MX2 moved with C3 SV2, K1-2 stage 1's for the acts that stage Main with C3 SV3, and K1-2 stage 3a's for
            // the acts whose tap opens Settings with K1-2 stage 4f: their inputs are written again, so they are compared as
            // every act is)
            if (want != now) problems += "${spec.name}: the program bytes the shell sends today differ from the file's inputs " +
                "(${now.size} against ${want.size}; first difference at ${now.indices.firstOrNull { it >= want.size || now[it] != want[it] } ?: want.size}) — regenerate it"
        }
        println("ConformanceVectorTest: ${specs.size} shipped acts played again${dump?.let { ", written to $it" } ?: ", their programs compared with the files"}")
        assertEquals(emptyList(), problems, "the ship vectors:\n" + problems.joinToString("\n"))
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    private companion object {
        /** `FIRMWARE.md` §5.1: today's 0x7f plus bit 7 ENGINE and bit 8 the riders (bit 9 is not in C1). */
        const val C1_FEATURES = 0x1FF
        /** §6.8: C2 adds bit 10 (bit 9, the link item, still out). */
        const val BIND_FEATURE = 1 shl 10
        const val C2_FEATURES = 0x5FF
        /** §7.9: C3 adds bit 11. */
        const val C3_FEATURES = 0xDFF
        /** Stage X3D's model-follows-§7.3 file (`make_vectors.py`): a hinted present with an owner open (the R1.21 row's
         *  names). */
        const val X3_HINT = "v5-x3-hint"
        /** C3 stage DM-2a (`private/c3-design/C3-DM2a-built.md`; decisions DW 6, 7): its two new files, waiting for the fork's
         *  fill (F1, after DM-2b) — `v5-dm-rain-fire` (`make_vectors.py`) and Main's Matrix ship act. Exactly these names;
         *  the comparison after the fill removes the set. */
        val DM2A_UNFILLED = setOf("v5-dm-rain-fire", "v5-ship-ring-matrix-352")
        /** §7.13's four fold pairs (`make_vectors.py`'s `"pair"` keys). */
        val FOLD_PAIRS = setOf("v5-fold-dim", "v5-fold-pieces", "v5-fold-rain", "v5-fold-shrink")
    }
}
