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
 * expectation to match". Inputs come from `firmware/make_vectors.py`.
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
            for (arm in Arm.entries) {
                val lens = if (arm == Arm.LEFT) "L" else "R"
                val sim = GlassFirmwareSim()
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
                }
            }
        }
        assertEquals(emptyList(), problems, "the simulator disagrees with the firmware C:\n" + problems.joinToString("\n"))
    }

    /** `FIRMWARE.md` §3, the self-test: the drawing vectors through mode 16 give the normal
     *  path's CRC and return code at every step, with the live shadow untouched — the fork's
     *  `host/run_self_test.py` proves the same of the C, so all four paths agree. */
    @Test
    fun theSelfTestPathMatchesTheNormalPath() {
        val files = Files.list(vectorDir()).use { s -> s.filter { it.toString().endsWith(".json") }.sorted().toList() }
        val zeroCrc = "%08x".format(java.util.zip.CRC32().also { it.update(ByteArray(320 * 480)) }.value)
        val problems = ArrayList<String>()
        var ran = 0
        for (f in files) {
            val vec = Json.parseToJsonElement(Files.readString(f)).jsonObject
            val name = vec["name"]!!.jsonPrimitive.content
            if (name == "v1-lease" || name == "v1-cache") continue      // the lease and the live cache: no self-test form
            val steps = vec["steps"]!!.jsonArray
            for (arm in Arm.entries) {
                val lens = if (arm == Arm.LEFT) "L" else "R"
                val sim = GlassFirmwareSim()
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
                            "msg" in op -> rcs += if (sim.conformanceMessage(arm, wm.damage.core.wire.CfwModes.selfTestStep(hex(op["msg"]!!.jsonPrimitive.content)), now)) 0 else -1
                            else -> fail("$name step $i: no self-test form for $op")
                        }
                    }
                    val expect = step["expect"]!!.jsonObject
                    val got = "%08x".format(sim.selfTestCrc(arm))
                    if (expect[lens]!!.jsonPrimitive.content != got) problems += "$name step $i lens $lens: scratch crc $got, the normal path gives ${expect[lens]!!.jsonPrimitive.content}"
                    val wantRc = expect["rc"]!!.jsonObject[lens]!!.jsonArray.map { it.jsonPrimitive.int }
                    if (wantRc != rcs) problems += "$name step $i lens $lens: return codes $rcs, the normal path gives $wantRc"
                    val live = "%08x".format(sim.shadowCrc32(arm))
                    if (live != zeroCrc) problems += "$name step $i lens $lens: the live shadow changed ($live)"
                    ran++
                }
            }
        }
        assertTrue(ran > 0, "no drawing vectors ran")
        assertEquals(emptyList(), problems, "the self-test path disagrees with the normal path:\n" + problems.joinToString("\n"))
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
