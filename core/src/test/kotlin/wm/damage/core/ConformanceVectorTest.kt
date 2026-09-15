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
                    wantRef?.let {
                        val gotRef = sim.refusalRecord(arm)
                        if (it != gotRef) problems += "$name step $i lens $lens: refusal record $gotRef, the C gives $it"
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
            val contract = vec["contract"]?.jsonPrimitive?.int ?: 1
            for (arm in Arm.entries) {
                val lens = if (arm == Arm.LEFT) "L" else "R"
                val sim = GlassFirmwareSim().also { it.damageContract = contract }
                var now = 1000L
                sim.conformanceLease(arm, true, now)
                for (op in ops) when {
                    "flags" in op -> sim.conformanceControl(arm, wm.damage.core.wire.DamageMsg.OP_FLAGS_SET, op["flags"]!!.jsonPrimitive.int, now)
                    "cachesize" in op -> sim.conformanceControl(arm, wm.damage.core.wire.DamageMsg.OP_CACHE_SIZE, op["cachesize"]!!.jsonPrimitive.int, now)
                }
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
                            "lease" in op || "flags" in op || "cachesize" in op -> {}   // sent above
                            else -> fail("$name step $i: no self-test form for $op")
                        }
                    }
                    val expect = step["expect"]!!.jsonObject
                    val got = "%08x".format(sim.selfTestCrc(arm))
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
