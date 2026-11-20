package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.local.BoundList
import wm.damage.core.motion.Motion
import wm.damage.core.motion.Motion.Backdrop
import wm.damage.core.motion.Motion.Ease
import wm.damage.core.motion.Motion.Source
import wm.damage.core.motion.MotionProgram
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.sim.MotionSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.CfwTransportBase
import wm.damage.core.transport.LadderRecord
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.transport.bind
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.CfwModes.BindParam
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.EvenHubMsg
import wm.damage.core.wire.Pb

/**
 * Build C2, stage DP1 (`C2-spec.md` §3 DP1, §6.1, §6.2; `FIRMWARE.md` §6.3, §6.5, §6.6, §6.8): the phone's wire for local
 * input, the ladder's stage 4 with its hold-back and stored record, the start pins at every stage, the probes. Written
 * from §6 alone; the encoders are compared byte for byte with the vector inputs `make_vectors.py` wrote (two readings of
 * one text), and END of a scaled DRAW with the simulator's (another stage's reading). Nothing here reaches a device.
 */
class BindTransportTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tmp: Path = Files.createTempDirectory("bind-transport")
    @AfterTest fun clean() {
        scope.cancel()
        tmp.toFile().walkTopDown().forEach { it.setWritable(true) }
        tmp.toFile().deleteRecursively()
    }

    private val full = Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480)))
    private val notes = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
    private val events = java.util.concurrent.CopyOnWriteArrayList<TransportEvent>()
    private fun has(kind: String, text: String) = notes.any { it.first == kind && text in it.second }
    private fun count(kind: String, text: String) = notes.count { it.first == kind && text in it.second }
    private fun collect(t: CfwTransportBase) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            t.events.collect {
                events.add(it)
                if (it is TransportEvent.Note) notes.add(it.kind to it.detail)
                if (it is TransportEvent.Fault) notes.add("fault" to "${it.what}: ${it.detail}")
            }
        }
    }
    private fun said(kind: String = "keeper") = notes.filter { it.first == kind }.joinToString(" | ") { it.second }
    /** A note's arrival: the transport's events reach this test's collector on another thread. */
    private suspend fun saw(kind: String, text: String) = waitUntil(10_000, message = { "a `$kind` note with '$text': ${said(kind)}" }) { has(kind, text) }
    private fun hex(s: String) = ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun s16(b: ByteArray, i: Int) = u16(b, i).toShort().toInt()

    // =============================================================================================== the wire (§6.5, §6.8)

    private fun report(vararg f: Pair<Int, Long>) = Pb.cat(Pb.v(1, 3), Pb.v(2, 0), Pb.l(DamageMsg.APPLIED_FIELD, Pb.cat(f.map { Pb.v(it.first, it.second) })))

    /** §6.5's bodies: a drawing scroll (fields 1–17, P as their 16 bits), a refusal 3 (1, 2, 3, 5, 6, 15 only), a
     *  report-only tap (no 10–14); §6.8's telemetry 35–39 named, 39 in hex; reasons 17, 18; 114's outcome 3. */
    @Test
    fun theReportAndTheNewTelemetryParse_6_5_6_8() {
        val drew = DamageMsg.parseApplied(report(1 to 7L, 2 to 4L, 3 to 4L, 4 to 0L, 5 to 0L, 6 to 0L, 7 to 0L, 8 to 3L, 9 to 1L,
            10 to 300L, 11 to 0xFFFEL, 12 to 0L, 13 to 0x8000L, 14 to 0x127L, 15 to 123456L, 16 to 88L, 17 to 9L))
        assertNotNull(drew)
        assertEquals(7, drew.s); assertEquals(4, drew.eventId); assertEquals(0, drew.outcome); assertEquals(3, drew.cursor)
        assertEquals(1, drew.slot); assertEquals(9, drew.mark); assertEquals(0x127, drew.prog)
        assertContentEquals(intArrayOf(300, -2, 0, -32768), drew.params, "P0..P3 are their 16 bits, read as s16")
        assertTrue("S 7" in drew.describe() && "drew" in drew.describe() && "P 300,-2,0,-32768" in drew.describe(), drew.describe())
        val refused3 = DamageMsg.parseApplied(report(1 to 8L, 2 to 4L, 3 to 0L, 5 to 2L, 6 to 3L, 15 to 9L))!!
        assertNull(refused3.list); assertNull(refused3.cursor); assertNull(refused3.params); assertNull(refused3.mark)
        assertTrue("refused (no-lease)" in refused3.describe(), refused3.describe())
        val tap = DamageMsg.parseApplied(report(1 to 9L, 2 to 4L, 3 to 0L, 4 to 2L, 5 to 0L, 6 to 0L, 7 to 0L, 8 to 0xFFFFL, 9 to 0L, 15 to 1L, 16 to 0L, 17 to 1L))!!
        assertTrue(tap.isTap); assertNull(tap.params); assertEquals(DamageMsg.CURSOR_LOST, tap.cursor)
        assertTrue("report-only" in tap.describe() && "cursor lost" in tap.describe(), tap.describe())
        assertNull(DamageMsg.parseApplied(Pb.cat(Pb.v(1, 3), Pb.v(2, 0), Pb.l(DamageMsg.MOTION_FIELD, Pb.v(1, 1)))), "a 114 is not a 115")

        val t = DamageMsg.Telemetry(mapOf(35L to 12L, 36L to 11L, 37L to 640L, 38L to 0L, 39L to 0x31L).mapKeys { it.key.toInt() })
        assertEquals(12L, t.matchedEvents); assertEquals(11L, t.lastS); assertEquals(640L, t.fireUs); assertEquals(0L, t.reportSendFails)
        assertEquals(0x31L, t.boundIds)
        assertEquals("matched=12 lastS=11 fireUs=640 reportFails=0 boundIds=0x31", t.describe())
        assertEquals("tap-over-local", DamageMsg.REASONS[17]); assertEquals("cursor-lost", DamageMsg.REASONS[18])
        assertTrue("committed" in DamageMsg.Motion(mapOf(1 to 4L, 2 to 0L, 3 to 7L, 4 to 3L, 5 to 0L, 8 to 0xABCL)).describe())
        assertEquals(1 shl 10, DamageMsg.FEATURE_BIND); assertEquals(1 shl 4, DamageMsg.FLAG_BIND)
    }

    // ---------------------------------------------------------------------------------- the encoders against the vectors

    /** The v4 inputs: `DAMAGE_VECTOR_DIR` (a stage's frozen copy) or the repo's `firmware/vectors`; until the meeting
     *  puts them in the repo, a run without the variable has none and this check is SKIPPED, never passed. */
    private fun v4Vectors(): List<Pair<String, JsonObject>> {
        val dir = System.getenv("DAMAGE_VECTOR_DIR")?.let { Paths.get(it) } ?: run {
            var d: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
            while (d != null && !Files.isDirectory(d.resolve("firmware/vectors"))) d = d.parent
            d?.resolve("firmware/vectors") ?: fail("firmware/vectors not found")
        }
        val files = Files.list(dir).use { s -> s.filter { it.fileName.toString().matches(Regex("v4-.*\\.json")) }.sorted().toList() }
        org.junit.Assume.assumeTrue("no v4-* vectors in $dir (they join firmware/vectors at the meeting; DAMAGE_VECTOR_DIR " +
            "names a frozen copy) — NOT compared", files.isNotEmpty())
        return files.map { it.fileName.toString() to Json.parseToJsonElement(Files.readString(it)).jsonObject }
    }

    /** Every message a vector sends, with each mode-8 batch's sub-messages and each self-test step's message. */
    private fun messagesOf(v: JsonObject): List<ByteArray> = v["steps"]!!.jsonArray.flatMap { st ->
        st.jsonObject["ops"]!!.jsonArray.mapNotNull { it.jsonObject["msg"]?.jsonPrimitive?.content?.let(::hex) }
    }.flatMap { m ->
        when {
            m.size >= 2 && (m[0].toInt() and 0x7F) == 8 -> {
                val out = ArrayList<ByteArray>(); var i = 2
                repeat(m[1].toInt() and 0xFF) { if (i + 2 <= m.size) { val n = u16(m, i); out += m.copyOfRange(i + 2, minOf(m.size, i + 2 + n)); i += 2 + n } }
                out
            }
            m.size >= 3 && m[0].toInt() == CfwModes.SELF_TEST_MODE && m[1].toInt() == 1 -> listOf(m.copyOfRange(2, m.size))
            else -> listOf(m)
        }
    }

    /** One C2 message decoded by §6's layout and written again by this encoder; null when the encoder refuses its
     *  fields offline (a message the vector sends for the glasses to refuse), "" when it is not one of C2's forms. */
    private fun reencode(m: ByteArray): ByteArray? {
        fun p4(at: Int) = IntArray(4) { s16(m, at + 2 * it) }
        return try {
            when {
                m[0].toInt() == 27 && m.size == 23 && m[1].toInt() == 0 -> CfwModes.bindSet(m[2].toInt() and 0xFF, m[3].toInt() and 0xFF,
                    m[4].toInt() and 0xFF, m[5].toInt() and 0xFF, m[6].toInt(), m[7].toInt() and 0xFF, m[8].toInt() and 0xFF, u16(m, 9),
                    List(4) { BindParam(m[11 + 3 * it].toInt() and 0xFF, s16(m, 12 + 3 * it)) })
                m[0].toInt() == 27 && m.size == 3 && m[1].toInt() == 1 -> CfwModes.bindClear(m[2].toInt() and 0xFF)
                m[0].toInt() == 27 && m.size == 12 && m[1].toInt() == 2 -> CfwModes.bindList(m[2].toInt() and 0xFF, u16(m, 3), u16(m, 5),
                    s16(m, 7), s16(m, 9), m[11].toInt() and 0xFF)
                m[0].toInt() == 26 && m.size == 18 && m[1].toInt() == 3 -> CfwModes.motionCommit(m[2].toInt() and 0xFF, u16(m, 3),
                    m[5].toInt() and 0xFF, u16(m, 6), u16(m, 8), p4(10))
                m[0].toInt() == 26 && m.size == 8 && m[1].toInt() == 4 -> CfwModes.motionDiscard(m[2].toInt() and 0xFF, u16(m, 3), m[5].toInt() and 0xFF, u16(m, 6))
                m[0].toInt() == 26 && m.size == 10 && m[1].toInt() == 5 -> CfwModes.motionSettle(m[2].toInt() and 0xFF, u16(m, 3),
                    m[5].toInt() and 0xFF, u16(m, 6), u16(m, 8))
                // the record's own width is not in the message: the widest a v2 record is, so only the fields decide
                m[0].toInt() == 28 && m.size == 10 -> CfwModes.drawScaled(u16(m, 1), s16(m, 3), s16(m, 5), m[7].toInt() and 0xFF, u16(m, 8), CfwModes.MAX_IMAGE2_W)
                (m[0].toInt() and 0xFF) == 0x9C && m.size == 12 -> CfwModes.drawScaledPair(u16(m, 1), s16(m, 3), s16(m, 5), s16(m, 7),
                    m[9].toInt() and 0xFF, u16(m, 10), CfwModes.MAX_IMAGE2_W)
                else -> ByteArray(0)
            }
        } catch (e: LintError) { null }
    }

    /** Spec DP1's pin: the encoders write every v4 message whose fields they take byte for byte as `make_vectors.py`
     *  does (two independent encoders of §6.3, §6.5, §6.6), and each one they refuse is a message the vector sends for
     *  the glasses to refuse. */
    @Test
    fun theEncodersWriteEveryV4MessageAsTheVectorInputsDo_6_3_6_5_6_6() {
        val counts = java.util.TreeMap<String, IntArray>()           // form → [same, refused offline]
        val refused = ArrayList<String>()
        for ((name, v) in v4Vectors()) for (m in messagesOf(v)) {
            if (m.size < 2) continue
            val form = "${m[0].toInt() and 0xFF}/" + (if ((m[0].toInt() and 0x7F) == 28) "${m.size}B" else "${m[1].toInt() and 0xFF}")
            val again = reencode(m)
            when {
                again == null -> { counts.getOrPut(form) { IntArray(2) }[1]++; refused += "$name ${m.hex()}" }
                again.isEmpty() -> {}
                else -> { assertEquals(m.hex(), again.hex(), "$name: $form written again"); counts.getOrPut(form) { IntArray(2) }[0]++ }
            }
        }
        println("BindTransportTest: v4 messages the encoders write alike, by form (same / refused offline): " +
            counts.entries.joinToString { "${it.key} ${it.value[0]}/${it.value[1]}" })
        println("BindTransportTest: refused offline: ${refused.joinToString(" · ")}")
        for (f in listOf("27/0", "27/1", "27/2", "26/3", "26/4", "26/5", "28/10B", "156/12B"))
            assertTrue((counts[f]?.get(0) ?: 0) > 0, "the vectors hold at least one $f the encoder writes: $counts")
    }

    private fun cacheOf(v: JsonObject): ByteArray {
        val cache = ByteArray(160 * 1024)
        for (op in v["steps"]!!.jsonArray[0].jsonObject["ops"]!!.jsonArray) {
            val m = op.jsonObject["msg"]?.jsonPrimitive?.content?.let(::hex) ?: continue
            if (m[0].toInt() != 19) continue
            var i = 1
            while (i < m.size) { val off = u16(m, i) * 4; val n = u16(m, i + 2); m.copyInto(cache, off, i + 4, i + 4 + n); i += 4 + n }
        }
        return cache
    }
    private fun plays(v: JsonObject): List<Int> = v["steps"]!!.jsonArray.flatMap { st ->
        st.jsonObject["ops"]!!.jsonArray.mapNotNull { o -> o.jsonObject["msg"]?.jsonPrimitive?.content?.let(::hex) }
            .filter { it.size == 18 && it[0].toInt() == 26 && it[1].toInt() == 0 }.map { u16(it, 4) }
    }
    private fun recordAt(cache: ByteArray, prog4: Int) = cache.copyOfRange(prog4 * 4, prog4 * 4 + u16(cache, prog4 * 4 + 2))

    /** The scaled programs of `v4-vscale-draw` as the DSL writes them (anchors 0, 1, 2 eased 10 → 60; ease-out 30 → 1;
     *  an unscaled DRAW with anchor 2; `v3-order`'s anchor 1; h0/h1 from P0/P1). [at]: their places, in PLAY order. */
    private fun scaledPrograms(pic: Source.Record, at: List<Int>): List<Pair<Int, MotionProgram>> {
        fun r(x: Int, y: Int, w: Int, h: Int) = Rect(x, y, w, h)
        val p = ArrayList<Pair<Int, MotionProgram>>()
        for (k in 0..2) p += at[k] to Motion.program(at[k], bit10 = true) {
            track(r(20 + 150 * k, 40, 120, 120), Backdrop.Level(2)) { draw(pic, x = 30 + 150 * k, y = 60, n = 4, h0 = 10, h1 = 60, anchor = k) } }
        p += at[3] to Motion.program(at[3], bit10 = true) {
            track(r(470, 40, 120, 120), Backdrop.Level(2)) { draw(pic, x = 480, y = 60, ease = Ease.easeOut, n = 6, h0 = 30, h1 = 1, anchor = 1) } }
        p += at[4] to Motion.program(at[4], bit10 = true) { track(r(20, 200, 120, 60), Backdrop.Level(2)) { draw(pic, x = 30, y = 210, n = 4, anchor = 2) } }
        p += at[5] to Motion.program(at[5], bit10 = true) {
            track(r(0, 280, 300, 60), Backdrop.Level(8)) { draw(pic, x = 10, y = 290, dx = 50, n = 4,
                prefs = listOf(Motion.Pref(0, Motion.Field.H0), Motion.Pref(1, Motion.Field.H1))) } }
        p += at[6] to Motion.program(at[6], bit10 = true) { track(r(170, 200, 100, 40), Backdrop.Level(2)) { draw(pic, x = 180, y = 205, n = 4, anchor = 1) } }
        return p
    }

    /** §6.6 in the DSL: the scaled DRAWs of `v4-vscale-draw` byte for byte as the vector inputs place them. */
    @Test
    fun theDslWritesTheScaledProgramsAsTheVectorInputsDo_6_6() {
        val v = v4Vectors().firstOrNull { it.first == "v4-vscale-draw.json" }?.second ?: fail("v4-vscale-draw.json is not among the v4 vectors")
        val cache = cacheOf(v)
        val pl = plays(v)
        val pic = Source.record(16, recordAt16(cache))
        for ((at, prog) in scaledPrograms(pic, pl)) assertContentEquals(recordAt(cache, at), prog.bytes, "the program at off4 $at, DSL against make_vectors.py")
        println("BindTransportTest: ${scaledPrograms(pic, pl).size} scaled programs of v4-vscale-draw encoded byte for byte")
    }
    private fun recordAt16(cache: ByteArray): ByteArray { val (_, end) = MotionSim.record(cache, 16 * 4) ?: fail("no v2 record at off4 16"); return cache.copyOfRange(64, end) }

    /** §6.6, §5.4's closing paragraph: END of a scaled DRAW is mode 28 under the area's clip. One model plays it (END
     *  into its shadow); another takes the DSL's END ops as one batch; the two shadows are one, per lens. */
    @Test
    fun endOfAScaledDrawLeavesTheShadowThePlayLeaves_6_6() {
        val v = v4Vectors().firstOrNull { it.first == "v4-vscale-draw.json" }?.second ?: fail("v4-vscale-draw.json is not among the v4 vectors")
        val cache = cacheOf(v)
        val pl = plays(v)
        val progs = scaledPrograms(Source.record(16, recordAt16(cache)), pl)
        val step0 = v["steps"]!!.jsonArray[0].jsonObject["ops"]!!.jsonArray.map { it.jsonObject }
        var scaledEnds = 0
        for ((k, pair) in progs.withIndex()) {
            val (at, prog) = pair
            val p = if (k == 5) intArrayOf(20, 40, 0, 0) else IntArray(4)
            for (arm in Arm.entries) {
                fun sim() = GlassFirmwareSim().apply { damageContract = 2; damageFeatures = 0x5ff; clockIsOsTick = true }.also { s ->
                    for (op in step0) when {
                        "lease" in op -> s.conformanceLease(arm, true, 1000)
                        "flags" in op -> s.conformanceControl(arm, DamageMsg.OP_FLAGS_SET, op["flags"]!!.jsonPrimitive.content.toInt(), 1000)
                        "msg" in op -> assertTrue(s.conformanceMessage(arm, hex(op["msg"]!!.jsonPrimitive.content), 1000), "step 0 accepted")
                    }
                }
                val a = sim()
                assertTrue(a.conformanceMessage(arm, Motion.play(prog, 0, Motion.PERIOD_30HZ, 1, 0, p), 2000), "program $at: the PLAY accepted")
                val b = sim()
                val ops = Motion.end(prog, p).map { (it as Motion.EndOp.Sub).bytes }
                if (ops.any { (it[0].toInt() and 0x7F) == CfwModes.VSCALE_MODE }) scaledEnds++
                assertTrue(b.conformanceMessage(arm, CfwModes.batch(ops, bind = true), 2000), "program $at: END's batch accepted")
                assertEquals(a.shadowCrc32(arm), b.shadowCrc32(arm), "program $at $arm: END as §4 ops against the model's END")
            }
        }
        assertEquals(2 * 5, scaledEnds, "five of the programs end scaled (mode 28), on each lens")
    }

    /** What §6.3, §6.5 and §6.6 refuse that the encoders can decide offline: each a loud refusal, never a message. */
    @Test
    fun theEncodersRefuseLoudlyWhatTheGlassesWouldRefuse_6_3() {
        val z = List(4) { BindParam(0, 0) }
        fun refused(what: String, block: () -> Unit) { assertFailsWith<LintError>(what) { block() } }
        refused("a scroll entry with step 0") { CfwModes.bindSet(0, 0xFF, 4, 0, 0, 0, 34, 0x100, z) }
        refused("a scroll entry from one source") { CfwModes.bindSet(0, 4, 5, 0, -1, 0, 34, 0x100, z) }
        refused("a tap entry from a temple") { CfwModes.bindSet(0, 1, 0, 0, 0, 0, 34, 0xFFFF, z) }
        refused("a double tap") { CfwModes.bindSet(0, 4, 1, 0, 0, 0, 34, 0xFFFF, z) }
        refused("a scroll entry naming no program") { CfwModes.bindSet(0, 0xFF, 4, 0, 1, 0, 34, 0xFFFF, z) }
        refused("entry 8") { CfwModes.bindSet(8, 0xFF, 4, 0, 1, 0, 34, 0x100, z) }
        refused("period 15") { CfwModes.bindSet(0, 0xFF, 4, 0, 1, 0, 15, 0x100, z) }
        refused("kind 3") { CfwModes.bindSet(0, 0xFF, 4, 0, 1, 0, 34, 0x100, listOf(BindParam(3, 0))) }
        refused("CLEAR 8") { CfwModes.bindClear(8) }
        refused("LIST count 0") { CfwModes.bindList(0, 0xFFFF, 0, 0, 10, 1) }
        refused("LIST count 1025") { CfwModes.bindList(0, 0xFFFF, 1025, 0, 10, 1) }
        refused("COMMIT of no list") { CfwModes.motionCommit(0, 1, 0xFF, 0, 0x100, IntArray(4)) }
        refused("COMMIT of every slot") { CfwModes.motionCommit(0xFF, 1, 0, 0, 0x100, IntArray(4)) }
        refused("DISCARD of slot 4") { CfwModes.motionDiscard(4, 1, 0, 0) }
        refused("H 0") { CfwModes.drawScaled(16, 0, 0, 15, 0, 40) }
        refused("H 2049") { CfwModes.drawScaled(16, 0, 0, 15, 2049, 40) }
        val pic = Source.Record(16, 40, 30, 600)
        refused("h0/h1 on a C1 program") { Motion.program(100) { track(Rect(0, 0, 100, 100), Backdrop.Level(1)) { draw(pic, 0, 0, n = 4, h0 = 10, h1 = 20) } } }
        refused("an anchor on a C1 program") { Motion.program(100) { track(Rect(0, 0, 100, 100), Backdrop.Level(1)) { draw(pic, 0, 0, n = 4, anchor = 1) } } }
        refused("anchor 3") { Motion.program(100, bit10 = true) { track(Rect(0, 0, 100, 100), Backdrop.Level(1)) { draw(pic, 0, 0, n = 4, anchor = 3) } } }
        refused("h0 0, h1 3 (ruled: both or neither)") { Motion.program(100, bit10 = true) { track(Rect(0, 0, 100, 100), Backdrop.Level(1)) { draw(pic, 0, 0, n = 4, h0 = 0, h1 = 3) } } }
        refused("h1 2049") { Motion.program(100, bit10 = true) { track(Rect(0, 0, 100, 100), Backdrop.Level(1)) { draw(pic, 0, 0, n = 4, h0 = 10, h1 = 2049) } } }
        refused("an eased H past 2048 (an overshoot table)") { Motion.program(100, bit10 = true) {
            track(Rect(0, 0, 100, 100), Backdrop.Level(1)) { draw(pic, 0, 0, n = 2, h0 = 2000, h1 = 2040, ease = Ease.table(400, intArrayOf(6000, 4096))) } } }
        refused("a scaled self") { Motion.program(100, bit10 = true) { track(Rect(0, 0, 100, 100), Backdrop.Level(1)) { draw(Source.self, 0, 0, n = 4, h0 = 10, h1 = 20) } } }
        val hp = Motion.program(100, bit10 = true) { track(Rect(0, 0, 300, 100), Backdrop.Level(1)) {
            draw(pic, 0, 0, n = 4, prefs = listOf(Motion.Pref(0, Motion.Field.H0), Motion.Pref(1, Motion.Field.H1))) } }
        refused("a PLAY whose P make H 3000") { Motion.play(hp, 0, 34, 1, 0, intArrayOf(3000, 3000, 0, 0)) }
        refused("a COMMIT whose P make H 0 and 5") { Motion.commit(hp, 0, 1, 0, 0, intArrayOf(0, 5, 0, 0)) }
        assertEquals(18, Motion.commit(hp, 0, 1, 0, 0, intArrayOf(30, 100, 0, 0)).size, "and the same COMMIT with H 30 → 100 is written")
        refused("mode 27 in a batch without bind") { CfwModes.batch(listOf(CfwModes.bindClear(0xFF))) }
    }

    /** §6.2 steps 8–9 on the phone's own staging, and the mapping §6.1 reads for the stock messages: id 4 (UPROLL) sends
     *  direction 2, which the shell reads as EV_SCROLL_BOTTOM (+1), id 5 direction 1, EV_SCROLL_TOP (−1). */
    @Test
    fun aBoundListPredictsWhatAFireDraws_6_2() {
        val prog = Motion.program(0x400) { track(Rect(0, 100, 576, 200), Backdrop.Level(1)) { fill(5, 4) } }
        val down = BoundList.Entry(0, CfwModes.EV_UPROLL, 1, 34, 0x400, listOf(BindParam(2, 7), BindParam(1, 0), BindParam(1, -1), BindParam(0, 9)), prog)
        val up = BoundList.Entry(1, CfwModes.EV_DOWNROLL, -1, 34, 0x400, listOf(BindParam(2, 0), BindParam(0, 0), BindParam(0, 0), BindParam(0, 0)), prog)
        val tap = BoundList.Entry(2, CfwModes.EV_TAP, 0, 34, CfwModes.PROG_NONE, List(4) { BindParam(0, 0) }, null)
        val items = IntArray(8) { 0x500 + 10 * it }
        val list = BoundList(list = 1, slot = 2, count = 8, pitch = 32, mark = 5, items4 = 0x480, items = items, entries = listOf(down, up, tap))
        val d = list.predict(3, down) as BoundList.Fire.Drew
        assertEquals(4, d.cursor); assertContentEquals(intArrayOf(4 * 32 + 7, items[4], items[3], 9), d.p, "kinds 2, 1, 1 (c' − 1), 0")
        assertTrue(list.predict(7, down) is BoundList.Fire.Edge, "past the last row: the edge")
        assertTrue(list.predict(0, up) is BoundList.Fire.Edge, "before the first row: the edge")
        assertTrue(list.predict(0, down).let { it is BoundList.Fire.Edge }.not(), "row 0 down is row 1")
        assertTrue(BoundList(1, 2, 8, 32, 5, 0x480, items, listOf(down)).predict(6, down) is BoundList.Fire.Drew)
        assertEquals(18, (list.predict(BoundList.LOST, down) as BoundList.Fire.Refused).reason)
        val wide = BoundList(1, 2, 1024, 0x4000, 5, CfwModes.ITEMS_NONE, null, listOf(BoundList.Entry(0, 4, 1, 34, 0x400, List(4) { BindParam(2, 0) }, prog)))
        assertEquals(12, (wide.predict(1, wide.entries[0]) as BoundList.Fire.Refused).reason, "c' × pitch outside s16: 12")
        val noTable = BoundList(1, 2, 8, 32, 5, CfwModes.ITEMS_NONE, null, listOf(down))
        assertTrue(noTable.predict(1, down) is BoundList.Fire.Edge, "kind 1 with no item table: the edge")
        val t = list.predict(5, tap) as BoundList.Fire.Drew
        assertEquals(5, t.cursor); assertNull(t.p, "the report-only entry computes nothing")
        assertEquals(listOf(list.set(down), list.set(up), list.set(tap)).map { it.hex() }, list.sets().map { it.hex() })
        assertEquals("1b0000ff0401010222000402070001000001ffff000900", list.set(down).hex())
        assertEquals("1b0201800408000000200005", list.listMessage().hex())
        assertEquals(16, list.itemTable()!!.size, "eight u16 off4 entries")
        assertEquals(1, list.maxStep)
        assertEquals(EvenHubMsg.EV_SCROLL_BOTTOM, BoundList.stockEvent(CfwModes.EV_UPROLL)); assertEquals(1, BoundList.rowStep(CfwModes.EV_UPROLL))
        assertEquals(EvenHubMsg.EV_SCROLL_TOP, BoundList.stockEvent(CfwModes.EV_DOWNROLL)); assertEquals(-1, BoundList.rowStep(CfwModes.EV_DOWNROLL))
        assertEquals(EvenHubMsg.EV_CLICK, BoundList.stockEvent(CfwModes.EV_TAP)); assertEquals(0, BoundList.rowStep(CfwModes.EV_TAP))
        assertFailsWith<LintError>("two entries for one id") { BoundList(1, 2, 8, 32, 5, 0x480, items, listOf(down, BoundList.Entry(3, 4, 2, 34, 0x400, down.params, prog))) }
    }

    // =========================================================================================== the start pins (§6.2)

    /** One awake start's packets, as `SilentStartTest`'s two start pins take them: stage 0 a transport's first start
     *  (`awake-start-contract2.txt`); stages 1–4 the start after a stage-0 first start and a lapse (C1-Q), the shape the
     *  stage files were written from. [holdBack] puts a stage-4 hold-back in force the way its evidence would
     *  ([CfwTransportBase.ladderRecordForTest]). `Taps` is not the transport's (stage DP2, decisions N.1): its start bytes
     *  are one file per stage, and the bind batch per `Taps` value is the shell's (`LocalStartTest`). */
    private suspend fun startWire(features: Int, stage: Int, holdBack: Boolean = false): List<String> {
        val glass = GlassFirmwareSim().apply { damageContract = 2; damageFeatures = features }
        var now = 1_763_000_000_000L
        val t = StartRig(glass, scope, clock = { now }, instantPacing = false)
        t.heapReads = false
        if (stage > 0) {
            scope.async { t.start(full) }.await()
            delay(300)
            assertTrue(t.restartSession("test: the session ends"))
            now += 110_000L
            t.devProbe("c1", "$stage")
        }
        if (holdBack) t.ladderRecordForTest { it.copy(bindArmedAt = now - 3_600_000L, holdBackAt = now - 600_000L, holdBackWhy = "a lens restarted at 09:00 (test)") }
        val sent = java.util.Collections.synchronizedList(ArrayList<String>())
        t.onWrite = { a, p -> sent.add("${a.name.first()} " + p.joinToString("") { "%02x".format(it) }); p }
        scope.async { t.start(full) }.await()
        delay(300)
        val got = synchronized(sent) { sent.toList() }
        t.stop()
        return got
    }

    private fun file(name: String): List<String>? = javaClass.getResource("/startwire/$name")?.readText()?.lines()?.filter { it.isNotBlank() }
    private fun assertWire(name: String, got: List<String>, what: String) {
        val want = file(name) ?: run {
            val out = Paths.get("build", "startwire").also { Files.createDirectories(it) }.resolve(name)
            Files.write(out, got)
            fail("no expectation for $name: this run's ${got.size} packets written to ${out.toAbsolutePath()}")
        }
        assertEquals(want.size, got.size, "$what: packet count against $name")
        for (i in want.indices) assertEquals(want[i], got[i], "$what: packet $i differs from $name")
    }

    /** Spec §6.2: a C1 build (0x1ff) at stage 4 writes exactly stage 3's file — stage 4 sends nothing without bit 10. */
    @Test
    fun aC1BuildAtStage4WritesStage3sStartUnderEachTaps_6_2(): Unit = runBlocking {
        assertWire("awake-start-c1-stage3.txt", startWire(0x1ff, 4), "0x1ff stage 4")
    }

    /** Spec §6.2: a C2 build (0x5ff) at stages 0, 1, 2, 3 writes exactly today's files, under each `Taps` value — bit 10
     *  does not leak below stage 4. */
    @Test
    fun aC2BuildBelowStage4WritesTodaysStartsUnderEachTaps_6_2(): Unit = runBlocking {
        for (stage in 0..3) {
            val name = if (stage == 0) "awake-start-contract2.txt" else "awake-start-c1-stage$stage.txt"
            assertWire(name, startWire(0x5ff, stage), "0x5ff stage $stage")
        }
    }

    /** Spec §6.2: a C2 build at stage 4 writes stage 3's start plus its BIND rung — one FLAGS_SET 0x3c to both arms after
     *  the 0x2c — the same under each `Taps` value (`Taps` is the shell's bind batch, not the transport's start). The
     *  file was written by this test's first run and read by hand once against stage 3's (`C2-DP1-built.md`). And the
     *  stage-4 hold-back leaves stage 3's bytes exactly. */
    @Test
    fun aC2BuildAtStage4WritesStage3sStartPlusItsBindRung_6_2(): Unit = runBlocking {
        assertWire("awake-start-c2-stage4.txt", startWire(0x5ff, 4), "0x5ff stage 4")
        val s3 = file("awake-start-c1-stage3.txt")!!
        val s4 = file("awake-start-c2-stage4.txt")!!
        assertEquals(s3.size + 2, s4.size, "stage 4 is stage 3 and one FLAGS_SET to each arm")
        assertWire("awake-start-c1-stage3.txt", startWire(0x5ff, 4, holdBack = true), "0x5ff stage 4 held back")
    }

    /** Build C3 (spec DP1, `FIRMWARE.md` §7.9): on a bit-11 build (0xdff) the starts at stages 0–4 write exactly the
     *  files APK 0.81 writes on a C2 build — nothing of C3 leaves the phone below stage 5; and a stage-4 hold-back leaves
     *  stage 3's bytes. The files are the C2 pins' own (written by 0.80's transport, unchanged through 0.81); this test
     *  was first run on 0.81's unchanged transport at 0xdff and passed there before stage 5 was built. */
    @Test
    fun aC3BuildAtStages0To4WritesTheC2Starts_7_9(): Unit = runBlocking {
        for (stage in 0..4) {
            val name = when (stage) { 0 -> "awake-start-contract2.txt"; 4 -> "awake-start-c2-stage4.txt"; else -> "awake-start-c1-stage$stage.txt" }
            assertWire(name, startWire(0xdff, stage), "0xdff stage $stage")
        }
        assertWire("awake-start-c1-stage3.txt", startWire(0xdff, 4, holdBack = true), "0xdff stage 4 held back")
    }

    /** Spec §6.2: below stage 4 — a C1 build (0x1ff) at stages 3 and 4, a C2 build (0x5ff) at 0..3 — a seeded walk of the
     *  shell, and the transport's raw doors (a self-test live write, a self-test step, the motion API) offered every C2
     *  form, put no mode 27, 28 or mode-26 sub 3..5 on the wire, top level, in a batch or in a step; each offer is a
     *  `bind` fault. At stage 4 on the C2 build the same offers do reach the glasses (the gate is not a wall). */
    @Test
    fun noBindByteReachesTheWireBelowStage4_6_2(): Unit = runBlocking {
        val offers = listOf(
            "live:" + CfwModes.bindClear(0xFF).hex(),
            "live:" + CfwModes.motionSettle(0xFF, 1, 0xFF, 0, 256).hex(),
            "live:" + CfwModes.batch(listOf(CfwModes.fill(Rect(0, 470, 640, 10), 3), CfwModes.drawScaled(16, 0, 470, 15, 5, 40)), bind = true).hex(),
            "step:" + CfwModes.drawScaled(16, 0, 0, 15, 5, 40).hex())
        for ((features, stage) in listOf(0x1ff to 3, 0x1ff to 4, 0x5ff to 0, 0x5ff to 1, 0x5ff to 2, 0x5ff to 3, 0x5ff to 4)) {
            val dir = Files.createTempDirectory(tmp, "walk")
            val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = features }
            val bad = java.util.concurrent.CopyOnWriteArrayList<String>()
            val images = java.util.concurrent.atomic.AtomicInteger()
            sim.imageObserver = { _, img -> images.incrementAndGet(); if (CfwModes.carriesBind(img)) bad += img.hex().take(40) }
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            notes.clear(); collect(t)
            t.devProbe("c1", "$stage")
            val shell = Shell(wm.damage.core.comp.CachedText(GlyphyText()), t, Persistence(dir.resolve("s.json")), null, scope)
            shell.start()
            waitUntil(10_000, 10, { "start" }) { shell.isQuiescent() }
            shell.updateSettings { it.copy(cachedText = "on") }
            waitUntil(20_000, 10, { "the atlas uploads" }) { shell.cachedTextActive && shell.isQuiescent() }
            val rnd = java.util.Random(4_072)
            val moves = intArrayOf(EvenHubMsg.EV_SCROLL_BOTTOM, EvenHubMsg.EV_SCROLL_TOP, EvenHubMsg.EV_CLICK, EvenHubMsg.EV_DOUBLE_CLICK)
            repeat(16) {
                shell.postGesture(moves[rnd.nextInt(moves.size)])
                waitUntil(10_000, 10, { "a step of the walk" }) { shell.isQuiescent() }
            }
            for (o in offers) t.devProbe("selftest", o)
            assertTrue(!t.sendMotion(listOf(CfwModes.motionDiscard(0xFF, 1, 0xFF, 0))) || stage >= 3, "the motion API's own ENGINE test below stage 3")
            val armed = features == 0x5ff && stage == 4
            waitUntil(10_000, 10, { "the offers are decided: ${said("fault")} bad=$bad" }) {
                if (armed) bad.size >= offers.size + 1 else count("fault", "bind: ") >= offers.size + (if (stage >= 3) 1 else 0) }
            waitUntil(10_000, 10, { "settles" }) { shell.isQuiescent() }
            val what = "features 0x${features.toString(16)} stage $stage"
            assertTrue(images.get() > 16, "$what: the walk put images on the wire: ${images.get()}")
            if (armed) {
                assertTrue(t.state.value.bind, "$what: BIND in force")
                assertEquals(0, count("fault", "bind: "), "$what: no offer refused: ${said("fault")}")
            } else {
                assertEquals(emptyList(), bad.toList(), "$what: no mode 27, 28 or 26 sub 3..5 on the wire")
                assertTrue(!t.state.value.bind, "$what: BIND not in force (flags 0x${t.state.value.flagsInForce.toString(16)})")
            }
            shell.stop()
        }
    }

    // ========================================================================================== stage 4's hold-back (§6.8)

    private val recordFile: Path get() = tmp.resolve(CfwTransportBase.C1_STAGE_FILE)
    private fun stored(): LadderRecord = LadderRecord.decode(Files.readString(recordFile), CfwTransportBase.LADDER_TOP_STAGE).record

    /** A transport over [recordFile] on a glasses model, its clock the test's, the zone UTC. */
    private fun rig(sim: GlassFirmwareSim, clock: () -> Long) =
        StartRig(sim, scope, clock = clock, c1StageFile = recordFile).also { it.localZone = java.time.ZoneOffset.UTC; collect(it) }
    private fun c2() = GlassFirmwareSim().apply { damageContract = 2; damageFeatures = 0x5ff }

    /** 2026-11-18 14:00 UTC, the tests' first clock. */
    private val day0 = 1_763_474_400_000L

    /** §6.8: a reset during wear — RIGHT's uptime short of the prediction after a stage-4 arming, here an hour later
     *  (outside C1's 120 s) — holds stage 4 back at the next start: it arms stage 3 (0x2c) and says so once, a `keeper`
     *  note and a `holdback` fault; the stored stage stays 4; the start after it says nothing again. The arming's time is
     *  on the phone's storage before the BIND FLAGS_SET's first byte. */
    @Test
    fun aResetDuringWearHoldsStage4BackAndSaysSoOnce_6_8(): Unit = runBlocking {
        var clock = day0
        val sim = c2()
        val t = rig(sim) { clock }
        t.devProbe("c1", "4")
        var armedBefore: Boolean? = null
        t.onWrite = { _, p ->
            if (StartRig.damageOp(p) == DamageMsg.OP_FLAGS_SET && controlArg(p) and DamageMsg.FLAG_BIND != 0 && armedBefore == null)
                armedBefore = stored().bindArmedAt != null
            p
        }
        t.start(full)
        assertEquals(0x3c, t.state.value.flagsInForce, "stage 4 armed: ${said()}")
        assertEquals(true, armedBefore, "the record held the arming before the BIND FLAGS_SET's first byte")
        assertEquals("on", t.state.value.localInput)
        clock += 3_600_000L; sim.uptimeOffsetMs = clock; clock += 30_000L        // RIGHT restarts an hour later
        assertTrue(t.restartSession("test: after a reset"))
        t.start(full)
        waitUntil(10_000, message = { "held back: ${said()}" }) { has("keeper", "local input held back until tomorrow's first start") }
        assertEquals(0x2c, t.state.value.flagsInForce, "stage 3 armed: ENGINE, ZWRITE, DRAW2 — not BIND")
        assertEquals(4, stored().stage, "the stored stage stays 4")
        assertNotNull(stored().holdBackAt)
        assertTrue("a lens restarted at 15:00" in stored().holdBackWhy, stored().holdBackWhy)
        saw("fault", "holdback: local input held back until tomorrow's first start: a lens restarted at 15:00")
        assertTrue(t.state.value.localInput.startsWith("held back until tomorrow: a lens restarted at 15:00"), t.state.value.localInput)
        assertTrue(t.restartSession("test: the next session")); clock += 60_000L
        t.start(full)
        assertEquals(0x2c, t.state.value.flagsInForce, "still held back the same day")
        assertEquals(1, count("keeper", "local input held back until tomorrow's first start"), "said once: ${said()}")
        t.stop()
    }

    /** §6.8, "it costs one stage (4 → 3), never more": a reset 10 s after the stage-4 arming — inside C1's 120 s window,
     *  whose rule lowers the stored stage — holds stage 4 back and leaves the stored stage at 4. */
    @Test
    fun aResetSoonAfterTheArmingCostsOneStageAndKeepsTheStoredStage_6_8(): Unit = runBlocking {
        var clock = day0
        val sim = c2()
        val t = rig(sim) { clock }
        t.devProbe("c1", "4")
        t.start(full)
        assertEquals(0x3c, t.state.value.flagsInForce, "stage 4 armed: ${said()}")
        clock += 10_000L; sim.uptimeOffsetMs = clock; clock += 20_000L
        assertTrue(t.restartSession("test: a reset 10 s after the arming"))
        t.start(full)
        assertEquals(0x2c, t.state.value.flagsInForce, "stage 3 armed: ${said()}")
        assertEquals(4, stored().stage, "the stored stage stays 4 (C1's rule would store 3): ${said()}")
        assertTrue(!has("keeper", "hold-back: the glasses reset"), "C1's hold-back did not run: ${said()}")
        saw("keeper", "local input held back until tomorrow's first start")
        t.stop()
    }

    /** §6.8: a reset that follows a case-in holds nothing back — the evening case-in, then a restart in the case. */
    @Test
    fun aResetAfterACaseInHoldsNothingBack_6_8(): Unit = runBlocking {
        var clock = day0
        val sim = c2()
        val t = rig(sim) { clock }
        t.devProbe("c1", "4")
        t.start(full)
        assertEquals(0x3c, t.state.value.flagsInForce)
        clock += 7 * 3_600_000L
        sim.pushCase(inCase = 1)
        waitUntil(10_000, message = { "the case-in is kept: ${stored()}" }) { stored().caseInAt == clock }
        clock += 600_000L; sim.uptimeOffsetMs = clock; clock += 30_000L
        assertTrue(t.restartSession("test: after a reset in the case"))
        t.start(full)
        assertEquals(0x3c, t.state.value.flagsInForce, "nothing held back: ${said()}")
        assertNull(stored().holdBackAt)
        saw("keeper", "after a case-in at 21:00 — nothing held back")
        t.stop()
    }

    /** §6.8's lifts: a case-in received after the hold-back was set (at the next start), the first start on a later day
     *  (04:00 UTC here), and `probe c1=4` by hand — each re-arms stage 4 (0x3c) and says so; a case-in before the
     *  hold-back lifts nothing. And LEFT's reboot-like link end (not RIGHT's uptime) sets the hold-back too. */
    @Test
    fun theHoldBackLiftsAtACaseInTheNextDayAndByHand_6_8(): Unit = runBlocking {
        var clock = day0
        val sim = c2()
        val t = rig(sim) { clock }
        t.devProbe("c1", "4")
        suspend fun holdBackByLeft() {
            t.start(full)
            assertEquals(0x3c, t.state.value.flagsInForce, "stage 4: ${said()}")
            clock += 1_800_000L
            sim.rebootForTest(Arm.LEFT, clock)
            t.endLink("LEFT disconnected: supervision timeout")
            waitUntil(10_000, message = { "LEFT's end is evidence: ${said()}" }) { stored().holdBackAt != null }
            assertTrue("LEFT's link ended as a restart would" in stored().holdBackWhy, stored().holdBackWhy)
            clock += 30_000L
            t.start(full)
            assertEquals(0x2c, t.state.value.flagsInForce, "held back to stage 3: ${said()}")
        }
        // (1) the case-in after it, at the next start
        holdBackByLeft()
        clock += 60_000L; sim.pushCase(inCase = 1)
        waitUntil(10_000, message = { "the case-in after the hold-back is kept" }) { stored().caseInAt == clock }
        assertTrue(t.restartSession("test: the evening start")); clock += 60_000L
        t.start(full)
        assertEquals(0x3c, t.state.value.flagsInForce, "the case-in lifted it: ${said()}")
        saw("keeper", "local input re-armed"); saw("keeper", "a case-in at")
        // (2) the next day's first start (04:00 UTC)
        assertTrue(t.restartSession("test: before the next hold-back")); clock += 60_000L
        holdBackByLeft()
        val day1 = java.time.LocalDate.ofEpochDay((clock + 86_400_000L) / 86_400_000L).atTime(3, 59).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        assertTrue(t.restartSession("test: before 04:00")); clock = day1
        t.start(full)
        assertEquals(0x2c, t.state.value.flagsInForce, "03:59 is still the same day: ${said()}")
        assertTrue(t.restartSession("test: after 04:00")); clock = day1 + 120_000L
        t.start(full)
        assertEquals(0x3c, t.state.value.flagsInForce, "04:01 is a later day: ${said()}")
        saw("keeper", "this is the first start on a later day")
        // (3) by hand
        assertTrue(t.restartSession("test: before the third")); clock += 60_000L
        holdBackByLeft()
        t.devProbe("c1", "4")
        assertNull(stored().holdBackAt, "c1=4 lifts it at once")
        saw("keeper", "local input's hold-back (a lens restarted at")
        assertTrue(t.restartSession("test: after the hand lift")); clock += 60_000L
        t.start(full)
        assertEquals(0x3c, t.state.value.flagsInForce, "stage 4 again: ${said()}")
        t.stop()
    }

    /** §6.8: a restart of the phone's process keeps all of it — a new transport over the same file arms stage 3 while the
     *  hold-back stands, says it once more as the restore, and the case-in it then hears lifts it (`Taps` is the shell's
     *  setting since stage DP2, kept with every Settings row). */
    @Test
    fun aRestartOfTheProcessKeepsAllOfIt_6_8(): Unit = runBlocking {
        var clock = day0
        val sim = c2()
        val t = rig(sim) { clock }
        t.devProbe("c1", "4")
        t.start(full)
        clock += 3_600_000L; sim.uptimeOffsetMs = clock; clock += 30_000L
        assertTrue(t.restartSession("test: after a reset"))
        t.start(full)
        assertEquals(0x2c, t.state.value.flagsInForce)
        t.stop()
        val kept = stored()
        assertEquals(4, kept.stage); assertNotNull(kept.bindArmedAt); assertNotNull(kept.holdBackAt)
        notes.clear()
        clock += 60_000L
        val t2 = rig(sim) { clock }
        t2.start(full)
        assertEquals(0x2c, t2.state.value.flagsInForce, "the new process holds stage 4 back: ${said()}")
        saw("keeper", "stage 4 restored from the last run"); saw("keeper", "local input held back since")
        clock += 60_000L; sim.pushCase(inCase = 1)
        waitUntil(10_000, message = { "the case-in is kept by the new process" }) { stored().caseInAt == clock }
        assertTrue(t2.restartSession("test: the evening")); clock += 60_000L
        t2.start(full)
        assertEquals(0x3c, t2.state.value.flagsInForce, "lifted in the new process: ${said()}")
        t2.devProbe("local", "state")
        waitUntil(10_000, message = { "the state note" }) { has("local", "state: on; stage 4 (arms 4)") }
        t2.stop()
    }

    /** Spec DP1: the record grows C1-P's file in place — a C1-P file reads, a record with only a stage is written as C1-P
     *  wrote it, a line it cannot use is said and left at its default, and the step-down keeps the rest. */
    @Test
    fun theRecordGrowsC1PsFileInPlace_P12() {
        assertEquals(LadderRecord(stage = 3), LadderRecord.decode("3\n", 4).record)
        assertEquals("2\n", LadderRecord(stage = 2).encode())
        val r = LadderRecord(4, 11L, 12L, 13L, "a lens restarted at 09:00 (x=y)")
        assertEquals(r, LadderRecord.decode(r.encode(), 4).record)
        val odd = LadderRecord.decode("4\ntaps=sometimes\nbindArmedAt=7\nother=1\n", 4)
        assertEquals(LadderRecord(stage = 4, bindArmedAt = 7L), odd.record)
        assertEquals(listOf("taps=sometimes", "other=1"), odd.problems)
        assertEquals("5", LadderRecord.decode("5\ntaps=phone\n", 4).stageText)
    }

    // ===================================================================================== what the shell is handed

    private fun controlArg(p: ByteArray): Int { val b = StartRig.body(p); val i = StartRig.controlAt(b)
        return (b[i + 4].toInt() and 0xFF) or ((b[i + 5].toInt() and 0xFF) shl 8) }

    /** An armed stage-4 session on the model, with list 0 bound: a scroll entry (id 4, a FILL program at off4 0x8000)
     *  and the report-only tap entry. */
    private suspend fun armedSession(): Pair<GlassFirmwareSim, StartRig> {
        val sim = c2()
        val t = rig(sim) { System.currentTimeMillis() }
        t.devProbe("c1", "4")
        t.start(full)
        assertTrue(t.state.value.bind, "stage 4 armed: ${said()}")
        val prog = Motion.program(0x8000) { track(Rect(0, 100, 576, 64), Backdrop.Level(1)) { fill(6, 3) } }
        val list = BoundList(0, 0, 8, 32, 1, CfwModes.ITEMS_NONE, null, listOf(
            BoundList.Entry(0, CfwModes.EV_UPROLL, 1, 34, 0x8000, List(4) { BindParam(0, 0) }, prog),
            BoundList.Entry(1, CfwModes.EV_TAP, 0, 34, CfwModes.PROG_NONE, List(4) { BindParam(0, 0) }, null)))
        t.devProbe("selftest", "live:" + CfwModes.cacheUpdate2(prog.records.map { CfwModes.CacheWrite2(it.first, it.second) }, t.state.value.cacheSize).hex())
        for (m in list.sets() + list.listMessage()) t.devProbe("selftest", "live:" + m.hex())
        waitUntil(10_000, message = { "the table is up on RIGHT: ${sim.bindForTest(Arm.RIGHT)}" }) { sim.bindForTest(Arm.RIGHT).field39() == 0x11 }
        return sim to t
    }

    /** P2: a 115 enters the shell's event flow in arrival order, ahead of its gesture's stock message — a tap's before its
     *  click, a notch's before its EV_SCROLL_BOTTOM; each also an `applied` note. (The tap first: a tap after the notch
     *  would meet the notch's local instance in their shared slot and be refused 17.) */
    @Test
    fun aReportReachesTheShellAheadOfItsStockEvent_P2(): Unit = runBlocking {
        val (sim, t) = armedSession()
        events.clear()
        sim.injectGesture(EvenHubMsg.EV_CLICK)
        sim.injectGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
        waitUntil(10_000, message = { "two reports and two stock events: $events" }) {
            events.count { it is TransportEvent.Applied } == 2 && events.count { it is TransportEvent.Input } == 2 }
        val order = events.filter { it is TransportEvent.Applied || it is TransportEvent.Input }.map {
            if (it is TransportEvent.Applied) "115 id ${it.report.eventId} outcome ${it.report.outcome} cursor ${it.report.cursor}" else "input ${(it as TransportEvent.Input).type}" }
        assertEquals(listOf("115 id 0 outcome 0 cursor 0", "input ${EvenHubMsg.EV_CLICK}", "115 id 4 outcome 0 cursor 1", "input ${EvenHubMsg.EV_SCROLL_BOTTOM}"), order)
        saw("applied", "S 1 dev 4 id 0 entry 1 report-only list 0 cursor 0"); saw("applied", "S 2 dev 4 id 4 entry 0 drew list 0 cursor 1")
        t.stop()
    }

    /** P1 and the tap rule's `tapsLive`: each telemetry read hands the shell field 39; a new refusal naming mode 27 is
     *  handed once (and noted), the next read of the same sticky record hands none. */
    @Test
    fun field39AndANewMode27RefusalReachTheShell_P1(): Unit = runBlocking {
        val (_, t) = armedSession()
        events.clear()
        t.devProbe("telemetry", "read")
        waitUntil(10_000, message = { "a LocalRead: $events" }) { events.any { it is TransportEvent.LocalRead } }
        val first = events.filterIsInstance<TransportEvent.LocalRead>().last()
        assertEquals(0x11L, first.boundIds); assertNull(first.refusal27)
        // SET entry 9: refused 12 on both lenses, recorded in fields 23–25
        t.devProbe("selftest", "live:1b0009ff04000100222701000000000000000000000000")
        delay(200)
        events.clear()
        t.devProbe("telemetry", "read")
        waitUntil(10_000, message = { "the refusal is handed: $events" }) { events.any { it is TransportEvent.LocalRead } }
        val second = events.filterIsInstance<TransportEvent.LocalRead>().last()
        assertEquals(27, second.refusal27?.mode); assertEquals(12, second.refusal27?.reason)
        saw("local", "the glasses refused a mode 27")
        events.clear()
        t.devProbe("telemetry", "read")
        waitUntil(10_000, message = { "the third read: $events" }) { events.any { it is TransportEvent.LocalRead } }
        assertNull(events.filterIsInstance<TransportEvent.LocalRead>().last().refusal27, "the same sticky record is not handed again")
        t.stop()
    }

    /** P9: a mid-session disarm of BIND by hand (`flags=0x2c`) goes out only after a DISCARD(0xFF, S − 0x4000, 0xFF, 0) has its
     *  ack — the DISCARD on the image lane, then the FLAGS_SET. S is S_heard + 1 as it stands at the disarm (decisions
     *  AA.3): two notches heard (S 1 and 2), so 3 − 0x4000 — the session's first S (1) reached only its first 16,384. */
    @Test
    fun aMidSessionDisarmOfBindSendsItsDiscardFirst_P9(): Unit = runBlocking {
        val (sim, t) = armedSession()
        events.clear()
        sim.injectGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
        sim.injectGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
        waitUntil(10_000, message = { "two reports heard: $events" }) { events.count { it is TransportEvent.Applied } == 2 }
        val order = java.util.concurrent.CopyOnWriteArrayList<String>()
        sim.imageObserver = { arm, img -> if (arm == Arm.RIGHT && (img[0].toInt() and 0x7F) == CfwModes.MOTION_MODE) order += "image ${img.hex()}" }
        t.onWrite = { a, p -> if (a == Arm.RIGHT && StartRig.damageOp(p) == DamageMsg.OP_FLAGS_SET) order += "flags 0x${controlArg(p).toString(16)}"; p }
        t.devProbe("flags", "0x2c")
        waitUntil(10_000, message = { "the disarm: $order ${said("local")}" }) { order.any { it.startsWith("flags") } }
        val s = (2 + 1 - wm.damage.core.local.LocalRules.WIDE) and 0xFFFF
        assertEquals(listOf("image " + CfwModes.motionDiscard(0xFF, s, 0xFF, 0).hex(), "flags 0x2c"), order.toList())
        saw("local", "DISCARD of every local instance from S $s acked before the probe's flags clear BIND")
        t.stop()
    }

    /** The probes: `flags=` cannot arm BIND beyond the stage (`taps=` is the shell's since stage DP2, decisions N.1). */
    @Test
    fun theProbesKeepTapsAndCannotArmBindBeyondTheStage_6_8(): Unit = runBlocking {
        var clock = day0
        val sim = c2()
        val t = rig(sim) { clock }
        t.devProbe("c1", "3")
        t.start(full)
        assertEquals(0x2c, t.state.value.flagsInForce)
        t.devProbe("flags", "0x3c")
        saw("probe", "flag bit(s) 0x10 are the C1 ladder's (stage 3)")
        waitUntil(10_000, message = { "the hand set lands" }) { has("probe", "flags=0x3c") }
        delay(300)
        assertTrue(!t.state.value.bind, "BIND is not armed by hand below stage 4")
        t.stop()
    }

    // ============================================== build C2-FA: the transport's fixes after V1 reader A (decisions AG)

    /** AG.1 (V1Da-1, the reader's test made a pin): a hand `flags=0x3c` at stage 4 (the stage allows BIND) is the wish
     *  for the rest of the process; a reset an hour later holds stage 4 back, and the next start arms stage 3 (0x2c) —
     *  the start's withhold takes the ladder's bits, BIND included, as the probe's rule does. */
    @Test
    fun aHandWishWithBindIsHeldBackWithStage4_AG1(): Unit = runBlocking {
        var clock = day0
        val sim = c2()
        val t = rig(sim) { clock }
        t.devProbe("c1", "4")
        t.start(full)
        assertEquals(0x3c, t.state.value.flagsInForce, "stage 4 armed: ${said()}")
        t.devProbe("flags", "0x3c")
        delay(500)
        clock += 3_600_000L; sim.uptimeOffsetMs = clock; clock += 30_000L        // RIGHT restarts an hour later
        assertTrue(t.restartSession("test: after a reset"))
        t.start(full)
        waitUntil(10_000, message = { "held back: ${said()}" }) { has("keeper", "local input held back until tomorrow's first start") }
        assertEquals(0x2c, t.state.value.flagsInForce, "the hold-back arms stage 3 (localInput '${t.state.value.localInput}')")
        t.stop()
    }

    /** AG.1 (V1Da-2): the same hand wish, then `c1=3` by hand — the next start arms no BIND (below stage 4). */
    @Test
    fun aHandWishWithBindIsHeldToALoweredStage_AG1(): Unit = runBlocking {
        var clock = day0
        val sim = c2()
        val t = rig(sim) { clock }
        t.devProbe("c1", "4")
        t.start(full)
        t.devProbe("flags", "0x3c")
        delay(500)
        t.devProbe("c1", "3")
        assertTrue(t.restartSession("test: the next session")); clock += 60_000L
        t.start(full)
        assertEquals(0x2c, t.state.value.flagsInForce, "stage 3 arms no BIND: ${said()}")
        t.stop()
    }

    /** AG.2 (V1Da-3): after a mid-session hand disarm of BIND (P9's path: the DISCARD acked, then FLAGS_SET 0x2c), the
     *  state the gate and the shell read says BIND is no longer in force, and the `Local input` row follows. */
    @Test
    fun aHandDisarmTakesBindOutOfTheStateAsItIsSent_AG2(): Unit = runBlocking {
        val (_, t) = armedSession()
        val sets = java.util.concurrent.CopyOnWriteArrayList<Int>()
        var bindAtTheSet: Boolean? = null
        t.onWrite = { a, p ->
            if (a == Arm.RIGHT && StartRig.damageOp(p) == DamageMsg.OP_FLAGS_SET) { if (bindAtTheSet == null) bindAtTheSet = t.state.value.bind; sets += controlArg(p) }
            p
        }
        t.devProbe("flags", "0x2c")
        waitUntil(10_000, message = { "the disarm went: $sets ${said("local")}" }) { 0x2c in sets }
        assertEquals(false, bindAtTheSet, "BIND left the state before the FLAGS_SET's first byte")
        assertEquals(0x2c, t.state.value.flagsInForce, "flags in force after the disarm")
        assertTrue(!t.state.value.bind, "the state the gate and the shell read")
        assertEquals("off: stage 4", t.state.value.localInput, "the row follows")
        t.stop()
    }

    /** AG.3: a bare keyframe that carries a tail goes as two images (§6.5's order); the keyframe's ack carries a refusal
     *  (status 5) while the tail's is clean — the flush fails, as it would on the tail's refusal. */
    @Test
    fun aKeyframeWithATailIsJudgedByBothAcks_AG3(): Unit = runBlocking {
        val (_, t) = armedSession()
        val refuseNext = java.util.concurrent.atomic.AtomicBoolean(false)
        val images = java.util.concurrent.atomic.AtomicInteger()
        t.onNotify = { _, p ->
            val ack = if (StartRig.sid(p) == EvenHubMsg.SID && (p[7].toInt() and 0xFF) == EvenHubMsg.FLAG_ACK) EvenHubMsg.parseAck(StartRig.body(p)) else null
            if (ack != null && ack.ackType == EvenHubMsg.CMD_IMAGE + 1) images.incrementAndGet()
            if (ack != null && ack.ackType == EvenHubMsg.CMD_IMAGE + 1 && refuseNext.compareAndSet(true, false))
                StartRig.reframe(p, Pb.cat(Pb.v(1, (EvenHubMsg.CMD_IMAGE + 1).toLong()), Pb.v(2, ack.msgId.toLong()), Pb.l(5, Pb.v(8, 5))))
            else p
        }
        events.clear()
        images.set(0)
        refuseNext.set(true)
        val id = t.submit(wm.damage.core.transport.FlushRequest(listOf(wm.damage.core.transport.DisplayOp.Keyframe(full)), 0, "KEY",
            tail = listOf(CfwModes.motionSettle(CfwModes.MOTION_ALL, 1, CfwModes.BIND_ALL, 0, 256))))
        waitUntil(10_000, message = { "the flush is answered: $events" }) { events.any { it is TransportEvent.FlushDone && it.id == id } }
        val done = events.filterIsInstance<TransportEvent.FlushDone>().first { it.id == id }
        assertEquals(2, images.get(), "the shape guard: two images, the keyframe and its tail")
        assertTrue(!done.ok, "the keyframe's refusal fails the flush: ${done.error}")
        assertTrue("on the flush's first image" in "${done.error}", "and says which image: ${done.error}")
        t.stop()
    }

    /** AG.4: RIGHT answers each control request twice (`HANDOFF.md` §59); one telemetry read hands the shell one
     *  `LocalRead` (each was a `local` note and a pump). */
    @Test
    fun oneTelemetryReadIsOneLocalRead_AG4(): Unit = runBlocking {
        val (sim, t) = armedSession()
        sim.duplicateControlReplies = true
        events.clear()
        t.devProbe("telemetry", "read")
        waitUntil(10_000, message = { "the read's second copy came: ${said("glass")}" }) { has("glass", "a second copy of reply id") }
        delay(200)
        assertEquals(1, events.count { it is TransportEvent.LocalRead }, "one LocalRead for the read: $events")
        t.stop()
    }

    /** AG.4: a 115 (and a 114) whose body does not parse — a varint past ten bytes, which `Pb` refuses — is journaled
     *  with a count, under its own note kind, and nothing is handed to the shell; it fell to a debug line before. */
    @Test
    fun aReportWhoseBodyDoesNotParseIsJournaledAndCounted_AG4(): Unit = runBlocking {
        val (sim, t) = armedSession()
        val bad = byteArrayOf(0x08) + ByteArray(11) { 0xFF.toByte() }
        val replace = java.util.concurrent.atomic.AtomicInteger(0)
        t.onNotify = { _, p ->
            if (StartRig.sid(p) == wm.damage.core.wire.SettingsMsg.SID && DamageMsg.parseApplied(StartRig.body(p)) != null) {
                val field = when (replace.getAndIncrement()) { 0 -> DamageMsg.APPLIED_FIELD; 1 -> DamageMsg.MOTION_FIELD; else -> 0 }
                if (field == 0) p else StartRig.reframe(p, Pb.cat(Pb.v(1, 3), Pb.v(2, 0), Pb.l(field, bad)))
            } else p
        }
        events.clear()
        sim.injectGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
        saw("applied", "a 115 body from RIGHT does not parse (varint: malformed")
        assertTrue(has("applied", "dropped, 1 in this run"), said("applied"))
        sim.injectGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
        saw("motion", "a 114 body from RIGHT does not parse (varint: malformed")
        assertTrue(has("motion", "dropped, 2 in this run"), said("motion"))
        assertEquals(0, events.count { it is TransportEvent.Applied }, "nothing handed to the shell: $events")
        t.stop()
    }

    /** AG.4: a stored stage 4 restored on a build without local input (the installed C1 image) is said as what it arms
     *  there — stage 3 — and a stored 3 on a build without a C1 bit as arming nothing. */
    @Test
    fun aRestoredStageIsSaidAsWhatTheBuildArms_AG4(): Unit = runBlocking {
        for ((features, stored, words) in listOf(
                Triple(0x1ff, 4, "C1 ladder: stage 4 restored from the last run (ZWRITE, the 256 KiB cache and ENGINE: stage 3, as this build reports no local input (bit 10))"),
                Triple(0x7f, 3, "C1 ladder: stage 3 restored from the last run (ZWRITE, the 256 KiB cache and ENGINE — this build reports no C1 bit, so it arms nothing)"),
                Triple(0x5ff, 4, "C1 ladder: stage 4 restored from the last run (ZWRITE, the 256 KiB cache, ENGINE and BIND)"))) {
            notes.clear()
            Files.writeString(recordFile, "$stored\n")
            val t = rig(GlassFirmwareSim().apply { damageContract = 2; damageFeatures = features }) { day0 }
            t.start(full)
            saw("keeper", "C1 ladder: stage $stored restored")
            assertTrue(has("keeper", words), "features 0x${features.toString(16)}: ${said()}")
            t.stop()
        }
    }

    /** AG.5: the batch's count is one byte — a flush whose drawing subs and tail pass 255 is refused loudly (the flush
     *  fails, saying so) and nothing of it reaches the glasses; 245 and 10 go out. */
    @Test
    fun aBatchWhoseTailPassesTheCountIsRefusedLoudly_AG5(): Unit = runBlocking {
        val (sim, t) = armedSession()
        val reached = java.util.concurrent.atomic.AtomicInteger()
        sim.imageObserver = { arm, img -> if (arm == Arm.LEFT && img[0].toInt() == 8) reached.incrementAndGet() }
        val tail = List(10) { CfwModes.motionSettle(CfwModes.MOTION_ALL, 1, CfwModes.BIND_ALL, 0, 256) }
        fun fills(n: Int) = List(n) { wm.damage.core.transport.DisplayOp.Fill(Rect(0, 0, 8, 8), Rect(0, 0, 8, 8), 0) }
        events.clear()
        val over = t.submit(wm.damage.core.transport.FlushRequest(fills(250), 0, "OVER", tail = tail))
        waitUntil(10_000, message = { "the flush is answered: $events" }) { events.any { it is TransportEvent.FlushDone && it.id == over } }
        val refused = events.filterIsInstance<TransportEvent.FlushDone>().first { it.id == over }
        assertTrue(!refused.ok && "mode-8 count 260 exceeds the u8 field" in "${refused.error}", "refused, saying so: ${refused.error}")
        delay(200)
        assertEquals(0, reached.get(), "nothing of it reached the glasses")
        val fits = t.submit(wm.damage.core.transport.FlushRequest(fills(245), 0, "FITS", tail = tail))
        waitUntil(10_000, message = { "the second flush is answered: $events" }) { events.any { it is TransportEvent.FlushDone && it.id == fits } }
        assertTrue(events.filterIsInstance<TransportEvent.FlushDone>().first { it.id == fits }.ok, "the shape guard: 255 goes out")
        assertEquals(1, reached.get(), "and reached the glasses")
        t.stop()
    }

    // ===================================================== build C2-FD: the last fix round after V2 reader A (decisions AM)

    /** AM.1 (the reader's V2Da test made the pin): the stage-4 hold-back decided INSIDE the arming — a reset first seen by a
     *  reply after the start's own decision — withholds a hand-wished BIND as well as the ladder's. Stored stage 4 at
     *  160 KiB (no size rung at the next start, so the last arming's stage 4 is what the read blames), `flags=0x3c` by
     *  hand, then RIGHT's uptime restarts in the model from op 5's reply on. */
    @Test
    fun aHandWishedBindIsWithheldByTheHoldBackInsideTheArming_AM1(): Unit = runBlocking {
        var clock = day0
        val sim = c2()
        Files.writeString(recordFile, "4\nsessionKib=160\n")
        val t = rig(sim) { clock }
        t.start(full)
        assertEquals(0x3c, t.state.value.flagsInForce, "stage 4 at 160 KiB: ${said()}")
        assertEquals(160 * 1024, t.state.value.cacheSize)
        clock += 5_000L
        t.devProbe("flags", "0x3c")                         // a hand re-arm within the stage (P9's undo)
        clock += 5_000L
        assertTrue(t.restartSession("test: the session ends"))
        clock += 5_000L
        t.onWrite = { _, p -> if (StartRig.damageOp(p) == DamageMsg.OP_CACHE_SIZE) sim.uptimeOffsetMs = clock; p }
        t.start(full)
        saw("keeper", "after stage 4 was armed — this session arms stage 3 (flags 0x2c)")
        delay(300)
        assertEquals(0x2c, t.state.value.flagsInForce, "the session the note calls stage 3 arms no BIND: ${said()}")
        t.stop()
    }

    /** AM.2 (the reader's V2Da test, turned round): a flush built while BIND was in force — its tail a SETTLE — that
     *  reaches the lane after BIND left the state, (a) at the sleep's release, (b) after a hand disarm: no `bind` fault,
     *  the drawing goes without its tail (it reaches the model), one `local` note says so. A tail-only flush in that state
     *  sends nothing, completes, and says so. */
    @Test
    fun aTailBindNoLongerCoversIsDroppedAndTheDrawingGoes_AM2(): Unit = runBlocking {
        for (how in listOf("sleep", "disarm")) {
            notes.clear()
            val (sim, t) = armedSession()
            val reached = java.util.concurrent.CopyOnWriteArrayList<Int>()
            sim.imageObserver = { arm, img -> if (arm == Arm.LEFT) reached += img[0].toInt() and 0xFF }
            if (how == "sleep") t.setLeaseWanted(false)
            else {
                t.devProbe("flags", "0x2c")
                waitUntil(10_000, message = { "the disarm: ${said("local")}" }) { !t.state.value.bind }
            }
            assertTrue(!t.state.value.bind, "$how: BIND is out of the state")
            delay(200)
            reached.clear()
            events.clear()
            val tail = listOf(CfwModes.motionSettle(CfwModes.MOTION_ALL, 1, CfwModes.BIND_ALL, 0, 256))
            val id = t.submit(wm.damage.core.transport.FlushRequest(List(2) { wm.damage.core.transport.DisplayOp.Fill(Rect(0, 0, 8, 8), Rect(0, 0, 8, 8), 0) }, 0, "LATE", tail = tail))
            waitUntil(10_000, message = { "$how: the flush is answered: $events" }) { events.any { it is TransportEvent.FlushDone && it.id == id } }
            val done = events.filterIsInstance<TransportEvent.FlushDone>().first { it.id == id }
            assertTrue(done.ok, "$how: the flush went: ${done.error}")
            assertEquals(listOf(8), reached.toList(), "$how: its drawing reached the model, one batch, no tail image")
            assertTrue(!has("fault", "bind: "), "$how: no `bind` fault: ${notes.filter { it.first == "fault" }}")
            assertTrue(has("local", "a flush's 1 local-input message(s) not sent: BIND is no longer in force") &&
                has("local", "its drawing goes without them"), "$how: one `local` note: ${said("local")}")
            val alone = t.submit(wm.damage.core.transport.FlushRequest(emptyList(), 0, "SETTLE", tail = tail))
            waitUntil(10_000, message = { "$how: the tail-only flush is answered: $events" }) { events.any { it is TransportEvent.FlushDone && it.id == alone } }
            assertTrue(events.filterIsInstance<TransportEvent.FlushDone>().first { it.id == alone }.ok, "$how: the tail-only flush completes")
            delay(200)
            assertEquals(listOf(8), reached.toList(), "$how: and sends nothing")
            assertTrue(has("local", "nothing else was in the flush") && !has("fault", "bind: "), "$how: said, no fault: ${said("local")}")
            t.stop()
        }
    }

    /** AM.3: a hand `flags=` naming a bit `FIRMWARE.md` does not define is refused at the phone — no FLAGS_SET, the state
     *  as it was (AG.2 believes nothing of it), said by a `probe` note; the same set without that bit goes. */
    @Test
    fun aFlagsProbeNamingAnUndefinedBitIsRefused_AM3(): Unit = runBlocking {
        val (_, t) = armedSession()
        val sets = java.util.concurrent.CopyOnWriteArrayList<Int>()
        t.onWrite = { a, p -> if (a == Arm.RIGHT && StartRig.damageOp(p) == DamageMsg.OP_FLAGS_SET) sets += controlArg(p); p }
        t.devProbe("flags", "0x6c")                         // 0x2c and bit 6, a typing error
        saw("probe", "flags=0x6c not sent: bit(s) 0x40 are not flags")
        delay(300)
        assertEquals(emptyList(), sets.toList(), "nothing on the wire")
        assertEquals(0x3c, t.state.value.flagsInForce, "the state as it was")
        t.devProbe("flags", "0x2c")
        waitUntil(10_000, message = { "the shape guard: the defined set goes: $sets" }) { 0x2c in sets }
        t.stop()
    }

    /** AM.4: the `Local input` row names the reason when a 64 KiB session keeps DRAW2, and so BIND, off at stage 4 (a new
     *  process over a stored 64); an unusable `sessionKib=` line is said under its own subject, beside the other lines'. */
    @Test
    fun theRowAndTheRecordSayTheirOwnSubjects_AM4(): Unit = runBlocking {
        Files.writeString(recordFile, "4\nsessionKib=64\n")
        val t = rig(c2()) { day0 }
        t.start(full)
        assertEquals(64 * 1024, t.state.value.cacheSize, said())
        assertTrue(!t.state.value.bind)
        assertEquals("off: this session's cache is 64 KiB, too small for DRAW2, which local input needs — a start after a lapsed lease " +
            "asks for the size again", t.state.value.localInput)
        t.stop()
        notes.clear()
        Files.writeString(recordFile, "2\nsessionKib=999\nbogus=1\n")
        val t2 = rig(c2()) { day0 }
        t2.start(full)
        saw("keeper", "cache size: the stored record's line(s) 'sessionKib=999' could not be used — the last session's size reads as 160 KiB")
        saw("keeper", "local input: the stored record's line(s) 'bogus=1' could not be used")
        assertTrue(!has("keeper", "local input: the stored record's line(s) 'sessionKib"), said())
        t2.stop()
    }
}
