package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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

    @Test
    fun everyVectorMatchesOnBothLenses() {
        val files = Files.list(vectorDir()).use { s -> s.filter { it.toString().endsWith(".json") }.sorted().toList() }
        assertTrue(files.isNotEmpty(), "no vectors in ${vectorDir()}")
        val problems = ArrayList<String>()
        for (f in files) {
            val vec = Json.parseToJsonElement(Files.readString(f)).jsonObject
            val name = vec["name"]!!.jsonPrimitive.content
            val steps = vec["steps"]!!.jsonArray
            val contract = vec["contract"]?.jsonPrimitive?.int ?: 1
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
                            else -> fail("$name step $i: unknown op $op")
                        }
                    }
                    val expect = step["expect"] as? JsonObject
                    if (expect == null || expect.isEmpty()) {
                        fail("$name step $i has no expectation — run the fork's host/run_vectors.py --write")
                    }
                    val want = expect[lens]!!.jsonPrimitive.content
                    val got = "%08x".format(sim.shadowCrc32(arm))
                    if (want != got) problems += "$name step $i lens $lens: shadow crc $got, the C gives $want"
                    val wantRc = expect["rc"]!!.jsonObject[lens]!!.jsonArray.map { it.jsonPrimitive.int }
                    if (wantRc != rcs) problems += "$name step $i lens $lens: return codes $rcs, the C gives $wantRc"
                    // contract 2 vectors carry the refusal record on every step: a missing one is a
                    // comparison silently skipped, not a pass (2026-09-15 review)
                    val wantRef = expect["ref"]?.jsonObject?.get(lens)?.jsonArray?.map { it.jsonPrimitive.int }
                    if (wantRef == null && contract >= 2) problems += "$name step $i lens $lens: no refusal record expectation on a contract-2 vector"
                    if (vec["panel"] != null && expect["P"] == null) problems += "$name step $i lens $lens: the vector is marked panel but carries no panel expectation"
                    wantRef?.let {
                        val gotRef = sim.refusalRecord(arm)
                        if (it != gotRef) problems += "$name step $i lens $lens: refusal record $gotRef, the C gives $it"
                    }
                    // `FIRMWARE.md` §9: a vector marked "panel" is compared on what the LENS SHOWS as
                    // well — a partial refresh (mode 24) transfers only its own rows, so a hint that
                    // misses a changed row differs here while the shadow agrees (2026-09-15, the second
                    // Phase 2 review)
                    if (vec["panel"] != null) {
                        // this lens's key, not just the object: a missing one compared nothing and
                        // said nothing, the shape `ref` was already guarded against (the third review)
                        val wantPanel = expect["P"]?.jsonObject?.get(lens)?.jsonPrimitive?.content
                        if (wantPanel == null) problems += "$name step $i lens $lens: no panel expectation for this lens"
                        else {
                            val gotPanel = "%08x".format(sim.panelCrc32(arm))
                            if (wantPanel != gotPanel) problems += "$name step $i lens $lens: panel crc $gotPanel, the C gives $wantPanel"
                        }
                    }
                }
            }
        }
        assertEquals(emptyList(), problems, "the simulator disagrees with the firmware C:\n" + problems.joinToString("\n"))
    }

    /** `FIRMWARE.md` §3, the self-test: the drawing vectors through mode 16 give the normal
     *  path's CRC, return code and refusal record at every step, with the live shadow untouched —
     *  the fork's `host/run_self_test.py` proves the same of the C, so all four paths agree. As
     *  there, a cache write (mode 12 or 19) goes to the live cache the steps read, the control
     *  ops run before the begin, and a vector that releases the lease has no self-test form. */
    @Test
    fun theSelfTestPathMatchesTheNormalPath() {
        val files = Files.list(vectorDir()).use { s -> s.filter { it.toString().endsWith(".json") }.sorted().toList() }
        val zeroCrc = "%08x".format(java.util.zip.CRC32().also { it.update(ByteArray(320 * 480)) }.value)
        val problems = ArrayList<String>()
        var ran = 0
        val vectorsRan = HashSet<String>()
        for (f in files) {
            val vec = Json.parseToJsonElement(Files.readString(f)).jsonObject
            val name = vec["name"]!!.jsonPrimitive.content
            if (name == "v1-lease") continue      // the lapse inside it refuses the step itself: no self-test form
            val steps = vec["steps"]!!.jsonArray
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
                                val step = byteArrayOf(wm.damage.core.wire.CfwModes.SELF_TEST_MODE.toByte(), 1) + m
                                rcs += if (sim.conformanceMessage(arm, if (live) m else step, now)) 0 else -1
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
                            else -> fail("$name step $i: no self-test form for $op")
                        }
                    }
                    val expect = step["expect"]!!.jsonObject
                    // Fields 21/22 are sent "both after a step" (`FIRMWARE.md` §3), so before the
                    // first one the record carries no CRC and the scratch is simply the zeroed
                    // shadow the begin allocated — which is what the normal path's all-zero shadow
                    // hashes to. Every vector in the repo draws in its first step, so this only
                    // shows on a sequence that does not (2026-09-16, the differential fuzz).
                    val got = if (sim.selfTestSteps(arm) == 0L) zeroCrc else "%08x".format(sim.selfTestCrc(arm))
                    if (expect[lens]!!.jsonPrimitive.content != got) problems += "$name step $i lens $lens: scratch crc $got, the normal path gives ${expect[lens]!!.jsonPrimitive.content}"
                    val wantRc = expect["rc"]!!.jsonObject[lens]!!.jsonArray.map { it.jsonPrimitive.int }
                    if (wantRc != rcs) problems += "$name step $i lens $lens: return codes $rcs, the normal path gives $wantRc"
                    // the refusal's copy sequence is the live count (0 here, nothing presents): not compared
                    expect["ref"]?.jsonObject?.get(lens)?.jsonArray?.map { it.jsonPrimitive.int }?.let { w ->
                        val g = sim.refusalRecord(arm)
                        if (listOf(w[0], w[1], w[3]) != listOf(g[0], g[1], g[3])) problems += "$name step $i lens $lens: refusal record $g, the normal path gives $w"
                    }
                    val live = "%08x".format(sim.shadowCrc32(arm))
                    if (live != zeroCrc) problems += "$name step $i lens $lens: the live shadow changed ($live)"
                    ran++
                    vectorsRan += name
                }
            }
        }
        assertTrue(ran > 0, "no drawing vectors ran")
        // every vector without a lease release has a self-test form: one that ran no step is a skip,
        // not a pass (the fork's run_self_test.py printed PASS for skipped vectors until 2026-09-15)
        for (f in files) {
            val vec = Json.parseToJsonElement(Files.readString(f)).jsonObject
            val name = vec["name"]!!.jsonPrimitive.content
            val releases = vec["steps"]!!.jsonArray.any { st -> st.jsonObject["ops"]!!.jsonArray.any { o -> o.jsonObject["lease"]?.jsonPrimitive?.content == "release" } }
            if (name != "v1-lease" && !releases && name !in vectorsRan) problems += "$name: a drawing vector ran no self-test step"
        }
        assertEquals(emptyList(), problems, "the self-test path disagrees with the normal path:\n" + problems.joinToString("\n"))
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
