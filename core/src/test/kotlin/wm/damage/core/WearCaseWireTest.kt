package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.CfwTransportBase
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.Transport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.AaFrame
import wm.damage.core.wire.CaseMsg
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.Pb
import wm.damage.core.wire.SettingsMsg
import wm.damage.core.wire.WearMsg

/**
 * APK 0.64, rest mode's build 1 (`HANDOFF.md` §75.4): what the transport READS and journals — the wear
 * event on service 0x10, the case's frames on 0x81, device-info fields 10 and 14, a service with no
 * handler — the one WEAR_QUERY per session start, the poll's telemetry read, and the simulator's models of
 * each. Nothing here acts on a report and nothing reaches a radio: every frame is the firmware model's.
 *
 * Each pin was watched to fail on the mutation `research/mutations-76-p4.json` names for it.
 *
 * Waits decide on ONE evaluation ([until]). An ABSENCE is asserted only behind a sync point: the control
 * lane is FIFO and the model answers inside the write, so "two more READs were counted" means every
 * reply to the READ before them has been turned into its notes; [Seen.sync] then puts a mark through the
 * same event flow, and when the mark has arrived everything emitted before it has too.
 */
class WearCaseWireTest {

    // ------------------------------------------------------------------ the bytes, pure
    @Test
    fun theWearEventsBytesAreTheForksAndParseByShape() {
        // ~/damage-cfw patches/settings_ext.c :: faceclaw_send_wear_event — 08 03 10 00 2a 04 08 01 10 <param>
        assertEquals("080310002a0408011001", WearMsg.event(true).hex())
        assertEquals("080310002a0408011000", WearMsg.event(false).hex())
        assertEquals(true, WearMsg.parse(WearMsg.event(true)))
        assertEquals(false, WearMsg.parse(WearMsg.event(false)))
        // a schema-driven sender leaves a zero out: no magic, no param — still "not worn"
        assertEquals(false, WearMsg.parse(unhex("08032a020801")), "a missing param is 0")
        assertEquals(true, WearMsg.parse(unhex("08032a0408011001")), "the magic is not part of the shape")
        // not a wear event: another command, no event block, another event, a param that is no state
        assertNull(WearMsg.parse(unhex("080210002a0408011001")), "command 2 (HEARTBEAT)")
        assertNull(WearMsg.parse(unhex("08031000")), "no field 5")
        assertNull(WearMsg.parse(unhex("080310002a0408021001")), "event 2")
        assertNull(WearMsg.parse(unhex("080310002a0408011002")), "a param other than 0 or 1 is not read as either")
        assertNull(WearMsg.parse(unhex("0a7f")), "an overrunning length does not parse")
        assertEquals(2, WearMsg.commandId(unhex("08021000")))
        assertNull(WearMsg.commandId(unhex("0a7f")))
    }

    @Test
    fun theCaseBodyFollowsEvensSchema() {
        // GlassesCaseDataPackage{1 commandId = 1 CASE_INFO, 2 magicRandom, 3 caseInfo{1 soc, 2 chargeStatus, 3 lidStatus, 4 inCaseStatus}}
        val body = CaseMsg.push(7, 78, 0, 1, 1)
        assertEquals("080110071a08084e100018012001", body.hex())
        val info = assertNotNull(CaseMsg.parse(body))
        assertEquals(CaseMsg.Info(cmd = 1, magic = 7, soc = 78, charge = 0, lid = 1, inCase = 1, error = null, hasInfo = true), info)
        // a null field is left out of the frame, and reads back as absent
        assertEquals("080110071a022001", CaseMsg.push(7, null, null, null, 1).hex())
        assertEquals(CaseMsg.Info(1, 7, null, null, null, 1, null, true), CaseMsg.parse(CaseMsg.push(7, null, null, null, 1)))
        val bare = assertNotNull(CaseMsg.parse(unhex("08011007")))
        assertFalse(bare.hasInfo, "a body with no field 3")
        assertEquals(3, CaseMsg.parse(unhex("080110071a0408052803"))?.error, "errorCode is field 5 of the block")
        assertNull(CaseMsg.parse(unhex("1a7f08")), "a length that overruns is not protobuf")
    }

    @Test
    fun field10AndField14AreReadFromTheDeviceInfoBlockAndTheQueryIsOp7() {
        // DeviceReceiveRequestFromAPP: 10 wearDetectionSwitchRestored, 12 battery, 13 chargingStatus, 14 silentModeSwitchRestored
        val with = Pb.cat(Pb.v(1, 2), Pb.v(2, 9), Pb.l(4, Pb.cat(Pb.v(10, 1), Pb.v(12, 61), Pb.v(13, 0), Pb.v(14, 0))))
        val without = Pb.cat(Pb.v(1, 2), Pb.v(2, 9), Pb.l(4, Pb.cat(Pb.v(2, 3), Pb.s(5, "2.2.6"), Pb.v(12, 61), Pb.v(18, 1))))
        assertEquals(SettingsMsg.DeviceInfo(listOf(10, 12, 13, 14), wearSwitch = 1L, silentRestored = 0L), SettingsMsg.parseDeviceInfo(with))
        assertEquals(SettingsMsg.DeviceInfo(listOf(2, 5, 12, 18), wearSwitch = null, silentRestored = null), SettingsMsg.parseDeviceInfo(without))
        assertNull(SettingsMsg.parseDeviceInfo(Pb.cat(Pb.v(1, 2), Pb.v(2, 9))), "a frame with no field 4")
        assertNull(SettingsMsg.parseDeviceInfo(unhex("227f08")), "not protobuf")
        // the parsers beside it read the same bytes as before
        assertEquals(61 to false, SettingsMsg.parseBattery(with))
        assertEquals(61 to false, SettingsMsg.parseBattery(without))
        assertEquals(false, SettingsMsg.parseSilentRestored(with))
        assertNull(SettingsMsg.parseSilentRestored(without), "an absent field 14 is no reading — a READ wakes only on an explicit 0")
        // ~/damage-cfw patches/settings_ext.c op 7: field 101 = 'F' 'C' 1 7 lo hi, MagicRandom 0 — thirteen bytes
        assertEquals("08011000" + "aa0606" + "464301" + "07" + "3412", SettingsMsg.wearQuery(0x1234).hex())
    }

    /** R2 (RG-4): a level outside 0–100 is no level. It was pressed into range — 255 read as 100 (which
     *  re-opens a closed low-battery latch), a varint of 2^31 or more as 0 (which says "time to charge" on a
     *  pair that is not low). The reading that answers "is it in range" is the router's; the parser that
     *  hands a level on hands only a level. The ring's relay is the same class. */
    @Test
    fun aLevelOutsideZeroToHundredIsNoLevel() {
        assertNull(SettingsMsg.parseBattery(Pb.cat(Pb.v(1, 2), Pb.l(4, Pb.v(12, 255)))), "255 is not 100")
        assertNull(SettingsMsg.parseBattery(Pb.cat(Pb.v(1, 2), Pb.l(4, Pb.cat(Pb.v(12, 1L shl 31), Pb.v(13, 0))))), "2^31 is not 0")
        assertEquals(100 to true, SettingsMsg.parseBattery(Pb.cat(Pb.v(1, 2), Pb.l(4, Pb.cat(Pb.v(12, 100), Pb.v(13, 1))))), "100 is a level")
        assertEquals(0 to false, SettingsMsg.parseBattery(Pb.cat(Pb.v(1, 2), Pb.l(4, Pb.cat(Pb.v(12, 0), Pb.v(13, 0))))), "an explicit 0 is a level")
        assertNull(wm.damage.core.wire.RingMsg.parseBattery(Pb.cat(Pb.v(1, 1), Pb.l(4, Pb.v(1, 255)))), "the ring's 255 is not 100")
        assertEquals(40, wm.damage.core.wire.RingMsg.parseBattery(Pb.cat(Pb.v(1, 1), Pb.l(4, Pb.v(1, 40)))))
    }

    // ------------------------------------------------------------------ the simulator's models
    /** Every packet the model sends back, decoded. */
    private class Tap(val sim: GlassFirmwareSim) {
        val frames = ArrayList<Pair<Arm, AaFrame.Frame>>()
        private val re = Arm.entries.associateWith { AaFrame.Reassembler { throw AssertionError("model sent a bad frame: $it") } }
        private var seq = 0
        init {
            sim.attachListener(object : GlassFirmwareSim.SimDiag {
                override fun event(kind: String, detail: String) {}
                override fun notify(arm: Arm, packet: ByteArray) { synchronized(frames) { re.getValue(arm).offer(packet)?.let { frames.add(arm to it) } } }
                override fun panelChanged(arm: Arm) {}
            })
        }
        fun on(sid: Int) = synchronized(frames) { frames.filter { it.second.sid == sid } }
        fun clear() = synchronized(frames) { frames.clear() }
        /** The host writes one sid-0x09 request to [arm]. */
        fun write(arm: Arm, payload: ByteArray) {
            seq = (seq + 1) and 0xFF
            sim.write(arm, AaFrame.frame(seq, SettingsMsg.SID, SettingsMsg.FLAG_REQUEST, payload).single(), 1_000L)
        }
    }

    @Test
    fun aScriptedWearChangeNotifiesOnlyWithTheSwitchOn() {
        val sim = GlassFirmwareSim(); val tap = Tap(sim)
        assertEquals(0, sim.wearCached); assertFalse(sim.wearDetectionSwitch, "nothing in Damage turns the switch on")
        sim.sensorReportsWear(false)
        assertEquals(1, sim.wearCached, "the state is stored whatever the switch says")
        assertTrue(tap.on(WearMsg.SID).isEmpty(), "with the switch off the stock notify step sends nothing: ${tap.frames}")
        sim.setWearDetectionSwitch(true); tap.clear()
        sim.sensorReportsWear(true)
        assertEquals(2, sim.wearCached)
        val sent = tap.on(WearMsg.SID)
        assertEquals(1, sent.size, "one event for one change")
        assertEquals(Arm.RIGHT, sent[0].first, "only RIGHT can send")
        assertEquals("080310002a0408011001", sent[0].second.payload.hex(), "exactly the fork's ten bytes")
        assertEquals(0x00, sent[0].second.flag, "under the stock sender's flag")
        sim.sensorReportsWear(true)
        assertEquals(1, tap.on(WearMsg.SID).size, "a repeat of the cached state is no change")
    }

    @Test
    fun turningTheSwitchOnReportsTheCachedState() {
        val sim = GlassFirmwareSim(); val tap = Tap(sim)
        sim.setWearDetectionSwitch(true)
        assertTrue(tap.on(WearMsg.SID).isEmpty(), "no state cached yet: nothing to report")
        sim.setWearDetectionSwitch(false)
        sim.sensorReportsWear(false)                       // cached 1, switch off: silent
        assertTrue(tap.on(WearMsg.SID).isEmpty())
        sim.setWearDetectionSwitch(true)
        assertEquals(listOf("080310002a0408011000"), tap.on(WearMsg.SID).map { it.second.payload.hex() }, "the setter runs the notify step with the cached state")
        sim.setWearDetectionSwitch(true)
        assertEquals(1, tap.on(WearMsg.SID).size, "the same value again changes no byte")
        sim.setWearDetectionSwitch(false)
        assertEquals(1, tap.on(WearMsg.SID).size, "turned off: the step runs and the switch gates the send")
        // RIGHT's RAM holds the cached word: a reboot clears it, a link end does not; the switch is a stored setting
        sim.setWearDetectionSwitch(true); sim.linkReset()
        assertEquals(1, sim.wearCached)
        sim.rebootForTest(Arm.RIGHT, 5_000L)
        assertEquals(0, sim.wearCached); assertTrue(sim.wearDetectionSwitch)
    }

    @Test
    fun op7AnswersFromTheCachedStateWhateverTheSwitchSays() {
        val sim = GlassFirmwareSim(); val tap = Tap(sim)
        tap.write(Arm.RIGHT, SettingsMsg.wearQuery(1))
        assertTrue(tap.on(WearMsg.SID).isEmpty(), "cached 0: no reply is the answer")
        sim.sensorReportsWear(true)                        // cached 2 with the switch OFF: stored, not sent
        assertTrue(tap.on(WearMsg.SID).isEmpty())
        tap.write(Arm.LEFT, SettingsMsg.wearQuery(2))
        assertTrue(tap.on(WearMsg.SID).isEmpty(), "LEFT runs the op and cannot send")
        tap.write(Arm.RIGHT, SettingsMsg.wearQuery(3))
        assertEquals(listOf(Arm.RIGHT to "080310002a0408011001"), tap.on(WearMsg.SID).map { it.first to it.second.payload.hex() },
            "the fork's op 7 has no test of the switch")
        assertEquals(3, sim.wearQueriesSeen, "both arms' copies are counted")
        assertFalse(sim.wearDetectionSwitch)
        // the model of RIGHT answering the copy LEFT forwards (seen on glass for field 112; inferred for
        // field 101): off by default, and when on, one more answer a few ms later
        tap.clear()
        sim.duplicateControlReplies = true
        tap.write(Arm.LEFT, SettingsMsg.wearQuery(4))
        val t0 = System.currentTimeMillis()
        while (tap.on(WearMsg.SID).isEmpty() && System.currentTimeMillis() - t0 < 5_000) Thread.sleep(2)
        assertEquals(listOf(Arm.RIGHT to "080310002a0408011001"), tap.on(WearMsg.SID).map { it.first to it.second.payload.hex() })
    }

    @Test
    fun theReadAnswerCarriesField10OnlyWhenTheSwitchIsOn() {
        val sim = GlassFirmwareSim(); val tap = Tap(sim)
        fun read(q: ByteArray): ByteArray { tap.clear(); tap.write(Arm.RIGHT, q); return tap.on(SettingsMsg.SID).single().second.payload }
        // the default answer: field 4 = {12: 87, 13: 0, 14: 0} — the six bytes this model sent before 0.64 —
        // then the capability string and the mic read-back, in that order
        val before = read(SettingsMsg.deviceInfoQuery(5))
        assertEquals(listOf(1, 2, 4, SettingsMsg.CAPABILITY_FIELD, SettingsMsg.MIC_STATUS_FIELD), Pb.fields(before).map { it.field })
        assertEquals("605768007000", Pb.bytesField(before, 4)!!.hex(), "the default device-info block is byte-identical")
        assertEquals(before.hex(), read(SettingsMsg.deviceInfoQuery(5)).hex())
        assertEquals("605768007000", Pb.bytesField(read(SettingsMsg.settingsQuery(5)), 4)!!.hex(), "the capability READ's form carries it too, by default")
        sim.setWearDetectionSwitch(true)
        assertEquals("5001" + "605768007000", Pb.bytesField(read(SettingsMsg.deviceInfoQuery(5)), 4)!!.hex(), "field 10 = 1, ahead of 12")
        sim.setWearDetectionSwitch(false); sim.charging = true; sim.batteryPct = 19
        assertEquals("601368017000", Pb.bytesField(read(SettingsMsg.deviceInfoQuery(5)), 4)!!.hex(), "charging is field 13")
        // the glasses leave the block out of the f4-sub-request form; the bare form still carries it
        sim.capabilityReadCarriesDeviceInfo = false
        val gate = read(SettingsMsg.settingsQuery(6))
        assertNull(Pb.bytesField(gate, 4)); assertNotNull(SettingsMsg.parseCapability(gate))
        assertNotNull(Pb.bytesField(read(SettingsMsg.deviceInfoQuery(7)), 4))
        assertEquals(7, sim.deviceInfoReadsSeen, "every command-2 request is counted")
    }

    // ------------------------------------------------------------------ the transport
    private fun warmup() = Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480)))

    /** Everything a transport emits, in order. */
    private class Seen(scope: CoroutineScope, private val t: Transport) {
        private val all = ArrayList<TransportEvent>()
        private var marks = 0
        init { scope.launch(start = CoroutineStart.UNDISPATCHED) { t.events.collect { synchronized(all) { all.add(it) } } } }
        fun notes(kind: String): List<String> = synchronized(all) { all.filterIsInstance<TransportEvent.Note>().filter { it.kind == kind }.map { it.detail } }
        fun wears(): List<TransportEvent.Wear> = synchronized(all) { all.filterIsInstance<TransportEvent.Wear>() }
        fun batteries(): List<TransportEvent.Battery> = synchronized(all) { all.filterIsInstance<TransportEvent.Battery>() }
        fun faults(): List<String> = synchronized(all) { all.filterIsInstance<TransportEvent.Fault>().map { "${it.what}: ${it.detail}" } }
        /** When this returns, everything the transport emitted before the call has been collected: the probe's
         *  own note is emitted inside the call and the flow keeps order. */
        suspend fun sync() {
            val tag = "mark${++marks}"
            t.devProbe("mark", tag)
            until("the mark $tag came through") { notes("probe").contains("mark=$tag") }
        }
    }

    /** [n] more READs have been ANSWERED: one past [n] counted means the n-th's write — and the reply the
     *  model sends inside it — is done. Then a mark. */
    private suspend fun readsAnswered(sim: GlassFirmwareSim, seen: Seen, n: Int = 2) {
        val r0 = sim.deviceInfoReadsSeen
        until("$n more READs answered") { sim.deviceInfoReadsSeen > r0 + n }
        seen.sync()
    }

    /** The router of [CfwTransportBase] over the model, with a way to hand it one frame as the glasses would
     *  send it — any service, any flag ([SimTransport] is final and keeps `onNotifyPacket` to itself). */
    private class RawLab(val glass: GlassFirmwareSim, scope: CoroutineScope) : CfwTransportBase(scope, "rawlab", mirrorSim = glass) {
        override val instant: Boolean get() = true
        private var seq = 0
        init {
            glass.attachListener(object : GlassFirmwareSim.SimDiag {
                override fun event(kind: String, detail: String) {}
                override fun notify(arm: Arm, packet: ByteArray) { onNotifyPacket(arm, packet) }
                override fun panelChanged(arm: Arm) {}
            })
        }
        override suspend fun connectLink() {}
        override suspend fun disconnectLink() { glass.linkReset() }
        /** R2: hold back the device-info READs (a sid-0x09 request with command 2) — none reaches the model,
         *  so no READ's answer can land between a frame the test hands in and the look that follows it. The
         *  lane is FIFO and the model answers inside the write: once [readsDropped] has moved, every READ
         *  before the dropped one has been answered and routed. */
        @Volatile var dropReads = false
        @Volatile var readsDropped = 0
        override suspend fun writeArm(arm: Arm, packet: ByteArray) {
            if (dropReads && isDeviceInfoRead(packet)) { readsDropped++; return }
            glass.write(arm, packet, nowMs())
        }
        private fun isDeviceInfoRead(packet: ByteArray): Boolean {
            if (packet.size < AaFrame.HEADER + AaFrame.CRC || (packet[6].toInt() and 0xFF) != SettingsMsg.SID) return false
            val len = packet[3].toInt() and 0xFF
            return try {
                Pb.varintField(packet.copyOfRange(AaFrame.HEADER, AaFrame.HEADER + len - AaFrame.CRC), 1) == 2L
            } catch (e: IllegalArgumentException) { false }
        }
        override fun onMaintenanceTick() {
            glass.tick(nowMs())
            val held = glass.leaseHeld(Arm.LEFT, nowMs()) && glass.leaseHeld(Arm.RIGHT, nowMs())
            if (!held && state.value.started) setLease(false, "lease lost (model)") else if (held) setLease(true, "lease held")
        }
        @Synchronized fun inject(arm: Arm, sid: Int, flag: Int, payload: ByteArray) {
            seq = (seq + 1) and 0xFF
            for (packet in AaFrame.frame(seq, sid, flag, payload, AaFrame.TYPE_RESPONSE)) onNotifyPacket(arm, packet)
        }
    }

    @Test
    fun theTransportTurnsAWearFrameIntoStateAnEventAndANote(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim()
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            val seen = Seen(scope, t)
            t.start(warmup())
            until("the session's query went out on both arms") { sim.wearQueriesSeen == 2 }
            seen.sync()
            assertNull(t.state.value.worn, "no report this session: the model had nothing cached to answer with")
            assertTrue(seen.wears().isEmpty())

            sim.setWearDetectionSwitch(true)
            sim.sensorReportsWear(false)
            seen.sync()
            assertEquals(false, t.state.value.worn)
            assertEquals(listOf(TransportEvent.Wear(worn = false, first = true)), seen.wears())
            val first = seen.notes("wear").single { "report" in it }
            assertTrue(first.startsWith("R first report: not worn (flag 0x00, 080310002a0408011000)"), "the note carries arm, flag and bytes: $first")

            sim.sensorReportsWear(true)
            seen.sync()
            assertEquals(true, t.state.value.worn)
            assertEquals(TransportEvent.Wear(worn = true, first = false), seen.wears().last())
            assertTrue(seen.notes("wear").any { it.startsWith("R now worn (flag 0x00, 080310002a0408011001)") }, "${seen.notes("wear")}")

            // an answer to a query says where the glasses are: the same state again, never a transition
            assertTrue(t.queryWear())
            until("the query's answer is a report of the same state") { seen.notes("wear").any { it.startsWith("R worn again (") } }
            assertEquals(TransportEvent.Wear(worn = true, first = false), seen.wears().last())
            assertTrue(seen.faults().isEmpty(), "reading a wear report raised a fault: ${seen.faults()}")
            t.stop()

            // recognised by service and shape, never by the flag: the same ten bytes under the notify flag
            val lab = RawLab(GlassFirmwareSim(), scope)
            val seen2 = Seen(scope, lab)
            lab.start(warmup())
            lab.inject(Arm.RIGHT, WearMsg.SID, 0x01, WearMsg.event(true))
            seen2.sync()
            assertEquals(listOf(TransportEvent.Wear(worn = true, first = true)), seen2.wears())
            assertTrue(seen2.notes("wear").any { it.startsWith("R first report: worn (flag 0x01, ") }, "${seen2.notes("wear")}")
            // LEFT cannot notify on the glasses; if a report ever came from it the note would name the arm
            lab.inject(Arm.LEFT, WearMsg.SID, 0x00, WearMsg.event(false))
            seen2.sync()
            assertTrue(seen2.notes("wear").any { it.startsWith("L now not worn (flag 0x00, ") }, "${seen2.notes("wear")}")
            lab.stop()
        } finally { scope.cancel() }
    }

    @Test
    fun aFrameOnTheWearServiceThatIsNotAWearEventIsNamedOncePerCommand(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val lab = RawLab(GlassFirmwareSim(), scope)
            val seen = Seen(scope, lab)
            lab.start(warmup())
            val heartbeat = unhex("0802100022020800")            // OnboardingDataPackage{commandId 2 HEARTBEAT, heartbeat{errorCode 0}}
            repeat(5) { lab.inject(Arm.RIGHT, WearMsg.SID, 0x01, heartbeat) }
            lab.inject(Arm.RIGHT, WearMsg.SID, 0x01, unhex("08011a020801"))     // commandId 1 CONFIG
            lab.inject(Arm.RIGHT, WearMsg.SID, 0x01, unhex("08011a020802"))
            seen.sync()
            val other = seen.notes("wear").filter { "not a wear event" in it }
            assertEquals(2, other.size, "one note per command id per session, not one per frame: $other")
            assertTrue(other[0].startsWith("R service 0x10 frame that is not a wear event (command 2, flag 0x01, 8 B): 0802100022020800"), other[0])
            assertTrue(other[1].contains("(command 1, "), other[1])
            assertTrue(seen.wears().isEmpty(), "and none of them is a wear report")
            assertNull(lab.state.value.worn)
            lab.stop()
        } finally { scope.cancel() }
    }

    @Test
    fun aSessionStartForgetsTheWearStateAndAsksOnce(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim()
            // as the glasses do it: the capability READ carries no device-info block, the poll's bare READ does
            sim.capabilityReadCarriesDeviceInfo = false
            sim.setWearDetectionSwitch(true)
            sim.sensorReportsWear(true)                        // cached 2 before any session
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            val seen = Seen(scope, t)
            t.start(warmup())
            until("one query per arm at the start") { sim.wearQueriesSeen == 2 }
            until("its answer is this session's first report") { seen.wears() == listOf(TransportEvent.Wear(worn = true, first = true)) }
            until("the poll read the switch") { t.state.value.wearSwitch == true }
            assertEquals(1, seen.notes("wear").count { it.startsWith("query sent after the lease (op 7, both arms)") })

            // from here no device-info block reaches the transport: what the state says next is what the start left
            t.notifyFilter = { _, packet -> !isDeviceInfoAnswer(packet) }
            readsAnswered(sim, seen)                           // a block already on its way has landed or been dropped
            assertTrue(t.restartSession("test: a second session"))
            t.start(warmup())
            assertNull(t.state.value.wearSwitch, "the switch's reading is the session's: cleared at the start")
            until("the second session asked once per arm") { sim.wearQueriesSeen == 4 }
            until("and the answer is a FIRST report again — the state was forgotten") { seen.wears().size == 2 }
            assertEquals(TransportEvent.Wear(worn = true, first = true), seen.wears()[1])
            readsAnswered(sim, seen)                           // two more polls went through the same lane after the query
            assertEquals(4, sim.wearQueriesSeen, "asked once per session start, never by the poll")
            assertEquals(2, seen.notes("wear").count { it.startsWith("query sent after the lease (op 7, both arms)") })
            assertEquals(2, seen.wears().size)
            t.stop()
        } finally { scope.cancel() }
    }

    @Test
    fun field10AndItsAbsenceAreSaidOncePerSession(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim()
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            val seen = Seen(scope, t)
            fun said() = seen.notes("wear").filter { "device-info field 10" in it }
            t.start(warmup())
            readsAnswered(sim, seen, n = 4)
            assertEquals(1, said().size, "said once, not on every READ: ${said()}")
            assertEquals("device-info field 10 absent — the switch is off or the field is not sent (09-00 cmd 2, the start's READ; field 14 present = 0; fields present 12,13,14)", said()[0])
            assertNull(t.state.value.wearSwitch)

            sim.setWearDetectionSwitch(true)
            readsAnswered(sim, seen, n = 4)
            assertEquals(2, said().size, "a change of the reading is said, once: ${said()}")
            assertEquals("switch on (09-00 cmd 2; device-info field 10 = 1; field 14 present = 0; fields present 10,12,13,14)", said()[1])
            assertEquals(true, t.state.value.wearSwitch)

            // a new session says it once more (the journal of a day reads each session by itself)
            assertTrue(t.restartSession("test: a second session"))
            t.start(warmup())
            readsAnswered(sim, seen, n = 4)
            assertEquals(3, said().size, "once per session: ${said()}")
            assertTrue(said()[2].startsWith("switch on (09-00 cmd 2, the start's READ; device-info field 10 = 1;"), said()[2])
            t.stop()
        } finally { scope.cancel() }
    }

    /** The decisions' addition (the class sweep's finding 5): a READ wakes the shell only on an explicit
     *  field-14 zero, and whether the glasses send one is unread — so the note names the field's presence,
     *  and says it again when the PRESENCE changes inside a session. A change of its value alone is the
     *  Silent Mode state itself, which has its own event. */
    @Test
    fun field14sPresenceIsNamedAndSaidAgainWhenItChanges(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim()
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            val seen = Seen(scope, t)
            fun said() = seen.notes("wear").filter { "field 14" in it }
            t.start(warmup())
            readsAnswered(sim, seen)
            assertEquals(1, said().size, "${said()}")
            assertTrue("field 14 present = 0" in said()[0], said()[0])

            sim.setSilent(true, push = false)                  // the READ now carries 14 = 1: present, as before
            until("the READ put the state on") { t.state.value.glassesSilent }
            readsAnswered(sim, seen)
            assertEquals(1, said().size, "the value changed, the presence did not: ${said()}")

            sim.reportSilentRestored = false                   // the block without the field
            readsAnswered(sim, seen)
            assertEquals(2, said().size, "${said()}")
            assertTrue("field 14 absent" in said()[1] && "fields present 12,13)" in said()[1], said()[1])
            assertTrue(t.state.value.glassesSilent, "an absent field is no reading: nothing was cleared")

            sim.reportSilentRestored = true
            readsAnswered(sim, seen)
            assertEquals(3, said().size, "${said()}")
            assertTrue("field 14 present = 1" in said()[2], said()[2])
            t.stop()
        } finally { scope.cancel() }
    }

    /** R2 (RG-1): frames with a root field 4 arrive unasked on glass (a level change 33 s apart under a 60 s
     *  poll, measured), so the device-info note names the frame it read, and a READ's answer and another
     *  frame are said apart — each by its own rule — so neither can stand in for the other. */
    private fun unaskedBlock(vararg f: ByteArray) = Pb.cat(Pb.v(1, 2), Pb.v(2, 7), Pb.l(4, Pb.cat(*f)))

    @Test
    fun theDeviceInfoNoteNamesItsFrame(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val lab = RawLab(GlassFirmwareSim().also { it.reportSilentRestored = false }, scope)
            val seen = Seen(scope, lab)
            fun said() = seen.notes("wear").filter { "device-info field 10" in it }
            lab.start(warmup())
            lab.inject(Arm.RIGHT, SettingsMsg.SID, 0x01, unaskedBlock(Pb.v(12, 61)))
            readsAnswered(lab.glass, seen)
            assertEquals(2, said().size, "one note for the READ's answer, one for the other frame: ${said()}")
            assertEquals(1, said().count { "(09-01 cmd 2" in it && it.endsWith("fields present 12)") }, "${said()}")
            assertEquals(1, said().count { "(09-00 cmd 2" in it && it.endsWith("fields present 12,13)") }, "${said()}")
            lab.stop()
        } finally { scope.cancel() }
    }

    @Test
    fun anUnaskedBlockDoesNotMakeTheReadsNoteRepeat(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val lab = RawLab(GlassFirmwareSim(), scope)        // the READ's block carries field 14; the other frame does not
            val seen = Seen(scope, lab)
            fun said() = seen.notes("wear").filter { "device-info field 10" in it }
            lab.start(warmup())
            repeat(5) {
                readsAnswered(lab.glass, seen, n = 1)
                lab.inject(Arm.RIGHT, SettingsMsg.SID, 0x01, unaskedBlock(Pb.v(12, 61)))
            }
            readsAnswered(lab.glass, seen)
            assertEquals(2, said().size, "the two sources do not take turns at the note: ${said()}")
            lab.stop()
        } finally { scope.cancel() }
    }

    /** `LinkState.wearSwitch` is the READ's reading: a frame we did not ask for, which may be another
     *  encoder's output, never moves it. The READs are held back first, so no answer can land between the
     *  frame handed in and the look. */
    @Test
    fun anUnaskedBlockDoesNotMoveTheSwitchState(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val lab = RawLab(GlassFirmwareSim().also { it.setWearDetectionSwitch(true) }, scope)
            lab.start(warmup())
            until("a READ's answer set the switch") { lab.state.value.wearSwitch == true }
            lab.dropReads = true
            val d0 = lab.readsDropped
            until("a READ was held back, so every READ before it has been answered") { lab.readsDropped > d0 }
            lab.inject(Arm.RIGHT, SettingsMsg.SID, 0x01, unaskedBlock(Pb.v(12, 61)))
            assertEquals(true, lab.state.value.wearSwitch, "a block without field 10 under flag 0x01")
            lab.inject(Arm.RIGHT, SettingsMsg.SID, 0x00, Pb.cat(Pb.v(1, 1), Pb.v(2, 7), Pb.l(4, Pb.v(12, 61))))
            assertEquals(true, lab.state.value.wearSwitch, "a WRITE's answer (command 1) carrying a block")
            lab.stop()
        } finally { scope.cancel() }
    }

    /** A READ's answer keeps compare-with-the-last-said, and the field list is part of what was said: a block
     *  that gains or loses a field is said again, the same block twice is not. The READs are held back, so
     *  the answers below are the only ones. */
    @Test
    fun aReadsAnswerIsSaidAgainWhenItsFieldListChanges(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val lab = RawLab(GlassFirmwareSim(), scope)
            val seen = Seen(scope, lab)
            fun said() = seen.notes("wear").filter { "device-info field 10" in it && "(09-00 cmd 2" in it }
            lab.start(warmup())
            until("the start's READ was said") { said().size == 1 }
            lab.dropReads = true
            val d0 = lab.readsDropped
            until("a READ was held back, so every READ before it has been answered") { lab.readsDropped > d0 }
            fun answer(vararg f: ByteArray) = lab.inject(Arm.RIGHT, SettingsMsg.SID, 0x00,
                Pb.cat(Pb.v(1, 2), Pb.v(2, 250), Pb.l(4, Pb.cat(*f))))
            answer(Pb.v(12, 61), Pb.v(13, 0), Pb.v(14, 0), Pb.v(18, 1))
            answer(Pb.v(12, 61), Pb.v(13, 0), Pb.v(14, 0), Pb.v(18, 1))
            answer(Pb.v(12, 61), Pb.v(13, 0), Pb.v(14, 0))
            seen.sync()
            assertEquals(3, said().size, "${said()}")
            assertTrue(said()[1].endsWith("fields present 12,13,14,18)") && said()[2].endsWith("fields present 12,13,14)"), "${said()}")
            lab.stop()
        } finally { scope.cancel() }
    }

    /** The other frames' shapes are bounded per session (at most 16, then one line), and a session start
     *  makes them news again. */
    @Test
    fun unaskedShapesAreBoundedPerSessionAndForgottenAtAStart(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val lab = RawLab(GlassFirmwareSim(), scope)
            val seen = Seen(scope, lab)
            fun unasked() = seen.notes("wear").filter { "device-info field 10" in it && "(09-01" in it }
            fun more() = seen.notes("wear").filter { "more than 16 shapes" in it }
            lab.start(warmup())
            for (v in 1..20) lab.inject(Arm.RIGHT, SettingsMsg.SID, 0x01, unaskedBlock(Pb.v(10, v), Pb.v(12, 61)))
            repeat(3) { lab.inject(Arm.RIGHT, SettingsMsg.SID, 0x01, unaskedBlock(Pb.v(10, 20), Pb.v(12, 61))) }
            seen.sync()
            assertEquals(16, unasked().size, "${unasked()}")
            assertEquals(1, more().size, "one line for the rest: ${seen.notes("wear")}")
            lab.inject(Arm.RIGHT, SettingsMsg.SID, 0x01, unaskedBlock(Pb.v(10, 1), Pb.v(12, 61)))
            seen.sync()
            assertEquals(16, unasked().size, "a shape already said is not said again")

            assertTrue(lab.restartSession("test: a second session"))
            lab.start(warmup())
            lab.inject(Arm.RIGHT, SettingsMsg.SID, 0x01, unaskedBlock(Pb.v(10, 1), Pb.v(12, 61)))
            seen.sync()
            assertEquals(17, unasked().size, "the new session says it once more")
            lab.stop()
        } finally { scope.cancel() }
    }

    @Test
    fun aCasePushIsACaseNote(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val lab = RawLab(GlassFirmwareSim(), scope)
            val seen = Seen(scope, lab)
            lab.start(warmup())
            lab.glass.pushCase(soc = 78, lid = 1, inCase = 1)
            lab.glass.pushCase()
            seen.sync()
            val notes = seen.notes("case")
            assertEquals(2, notes.size, "one note per frame: $notes")
            assertEquals("R flag 0x01: level 78%, charging -, lid 1, in case 1, error - (cmd 1, 080110011a06084e18012001)", notes[0])
            assertEquals("R flag 0x01: level -%, charging -, lid -, in case -, error - (cmd 1, 080110021a00)", notes[1])
            // the reply our capture holds came under flag 0x00, with no block when there is nothing to say
            lab.inject(Arm.RIGHT, CaseMsg.SID, 0x00, unhex("08011005"))
            // not protobuf: named once per session, not once per frame
            repeat(3) { lab.inject(Arm.RIGHT, CaseMsg.SID, 0x01, unhex("1a7f08")) }
            seen.sync()
            assertEquals(listOf("R flag 0x00: no case info (cmd 1, 08011005)", "R flag 0x01, 3 B did not parse: 1a7f08 — said once per session"),
                seen.notes("case").drop(2))
            assertTrue(seen.notes("sid").isEmpty(), "the case service has a handler now: ${seen.notes("sid")}")
            assertTrue(seen.faults().isEmpty(), "${seen.faults()}")
            lab.stop()
        } finally { scope.cancel() }
    }

    @Test
    fun aServiceWithNoHandlerIsNamedOncePerSession(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val lab = RawLab(GlassFirmwareSim(), scope)
            val seen = Seen(scope, lab)
            lab.start(warmup())
            val long = ByteArray(120) { it.toByte() }
            lab.inject(Arm.RIGHT, 0x30, 0x01, unhex("08011002"))
            lab.inject(Arm.RIGHT, 0x30, 0x00, unhex("08021003"))      // the same service again, another flag: not news
            lab.inject(Arm.LEFT, 0x41, 0x01, long)
            lab.inject(Arm.RIGHT, 0xE0, 0x07, unhex("0809"))          // the EvenHub service under a flag with no branch
            lab.inject(Arm.RIGHT, 0xE0, 0x07, unhex("0809"))
            lab.inject(Arm.RIGHT, 0xE0, 0x08, unhex("0809"))
            seen.sync()
            val sid = seen.notes("sid")
            assertEquals(4, sid.size, "one note per service, and per flag for the EvenHub service: $sid")
            assertEquals("0x30 from R, flag 0x01, 4 B: 08011002 — no handler; said once per session", sid[0])
            assertEquals("0x41 from L, flag 0x01, 120 B: " + long.hex() + " — no handler; said once per session", sid[1], "whole in the journal (R2, item 7)")
            assertEquals("0xe0/flag 0x07 from R, flag 0x07, 2 B: 0809 — no handler; said once per session", sid[2])
            assertTrue(sid[3].startsWith("0xe0/flag 0x08 from R"), sid[3])

            // a session rebuild makes each news once more
            assertTrue(lab.restartSession("test: a second session"))
            lab.start(warmup())
            lab.inject(Arm.RIGHT, 0x30, 0x01, unhex("08011002"))
            lab.inject(Arm.RIGHT, 0x30, 0x01, unhex("08011002"))
            seen.sync()
            assertEquals(5, seen.notes("sid").size, "named once more in the new session: ${seen.notes("sid")}")
            assertTrue(seen.faults().isEmpty(), "${seen.faults()}")
            lab.stop()
        } finally { scope.cancel() }
    }

    /** R2 (RG-2): a length varint with bit 63 set is negative as a Long, and the reader's bound did not see
     *  it — `copyOfRange` raised `IndexOutOfBoundsException`, which no parser catches. Every parser that reads
     *  a frame from the glasses reads the shared reader, so each one says "not protobuf" for it. */
    @Test
    fun aLengthPastTheLongRangeIsNotProtobuf() {
        val body = unhex("1ae887808080808080800108")
        assertNull(CaseMsg.parse(body), "service 0x81")
        assertNull(WearMsg.parse(body), "service 0x10")
        assertNull(WearMsg.commandId(body))
        assertNull(SettingsMsg.parseDeviceInfo(body), "service 0x09")
        assertNull(SettingsMsg.parseBattery(body))
        assertNull(SettingsMsg.parseSilentRestored(body))
        assertNull(SettingsMsg.parseSilentModePush(body))
        assertNull(SettingsMsg.parseCapability(body))
        assertNull(wm.damage.core.wire.RingMsg.parseBattery(body), "service 0x91")
        assertNull(DamageMsg.parseTelemetry(body))
        assertNull(DamageMsg.parsePresented(body))
        assertNull(wm.damage.core.wire.EvenHubMsg.parseAck(body), "service 0xe0")
    }

    /** R2 (RG-2): a frame no parser can read leaves the router as a note, on both hosts, never as an exception
     *  out of the notify callback (on the phone the BLE library takes it and writes it to logcat alone). The
     *  sid-0x09 frame is the older site: a flag-0x00 body with wire type 3 passes the three parsers above the
     *  response check as null and the bare `Pb.varintField` there threw. One note per service per session. */
    @Test
    fun aFrameThatDoesNotParseIsNamedNotThrown(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val lab = RawLab(GlassFirmwareSim(), scope)
            val seen = Seen(scope, lab)
            lab.start(warmup())
            lab.inject(Arm.RIGHT, CaseMsg.SID, 0x01, unhex("1ae887808080808080800108"))
            lab.inject(Arm.RIGHT, SettingsMsg.SID, 0x00, unhex("0b"))
            lab.inject(Arm.RIGHT, SettingsMsg.SID, 0x00, unhex("0b08"))  // the same service again: not news
            seen.sync()
            assertEquals(listOf("R flag 0x01, 12 B did not parse: 1ae887808080808080800108 — said once per session"),
                seen.notes("case").filter { "did not parse" in it })
            val threw = seen.notes("sid").filter { "threw" in it }
            assertEquals(1, threw.size, "one note per service per session: ${seen.notes("sid")}")
            assertTrue(threw[0].startsWith("0x09 from R, flag 0x00, 1 B: 0b — the handler threw IllegalArgumentException"), threw[0])
            lab.stop()
        } finally { scope.cancel() }
    }

    /** R2 (RG-4): an out-of-range level reaches the router, which takes no reading from it and says so once
     *  per session — so the journal shows the value, and the latch is not moved by it. */
    @Test
    fun aLevelOutsideZeroToHundredIsNotTaken(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val lab = RawLab(GlassFirmwareSim(), scope)
            val seen = Seen(scope, lab)
            lab.start(warmup())
            repeat(2) { lab.inject(Arm.RIGHT, SettingsMsg.SID, 0x01, Pb.cat(Pb.v(1, 2), Pb.v(2, 7), Pb.l(4, Pb.v(12, 255)))) }
            repeat(2) { lab.inject(Arm.RIGHT, wm.damage.core.wire.RingMsg.SID, 0x01, Pb.cat(Pb.v(1, 1), Pb.l(4, Pb.v(1, 255)))) }
            seen.sync()
            assertTrue(seen.batteries().none { it.glassesPct == 100 || it.ringPct == 100 }, "no reading of 100 from a 255: ${seen.batteries()}")
            assertTrue("glasses 100%" !in seen.notes("battery"), "${seen.notes("battery")}")
            val said = seen.notes("battery").filter { "not a level, not taken" in it }
            assertEquals(2, said.size, "one note per source per session: ${seen.notes("battery")}")
            assertTrue(said.any { it.startsWith("glasses: device-info field 12 = 255 — not a level, not taken") }, "$said")
            assertTrue(said.any { it.startsWith("ring: relay field 1 = 255 — not a level, not taken") }, "$said")
            lab.stop()
        } finally { scope.cancel() }
    }

    /** R2 (item 7, the lead's ruling on whole or bounded): the said-once notes are the frames nobody can
     *  describe in advance, and their count is bounded by construction — the journal holds them WHOLE; the
     *  `Log` line beside each (a 4,000-line ring held in memory) keeps the bound and says the size. */
    @Test
    fun aSaidOnceNoteCarriesTheFrameWholeAndItsLogLineTheBound(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val logged = java.util.concurrent.CopyOnWriteArrayList<String>()
        val sink = wm.damage.core.util.Log.Sink { _, tag, msg -> if (tag == "rawlab") logged.add(msg) }
        wm.damage.core.util.Log.addSink(sink)
        try {
            val lab = RawLab(GlassFirmwareSim(), scope)
            val seen = Seen(scope, lab)
            lab.start(warmup())
            val big = ByteArray(300) { (it * 7).toByte() }
            lab.inject(Arm.RIGHT, 0x42, 0x01, big)
            seen.sync()
            assertEquals(listOf("0x42 from R, flag 0x01, 300 B: " + big.hex() + " — no handler; said once per session"), seen.notes("sid"))
            assertEquals("sid 0x42 from R, flag 0x01, 300 B: " + big.take(96).toByteArray().hex() + "… (the first 96 of 300 B) — no handler; said once per session",
                logged.single { it.startsWith("sid 0x42 from R") })
            // the other said-once notes of the class: a service-0x10 frame that is no wear event, a service-0x81
            // frame that is not protobuf, a frame whose handler threw (the router's net)
            val notWear = Pb.cat(Pb.v(1, 2), Pb.l(15, ByteArray(140) { it.toByte() }))
            val notProto = unhex("0b") + ByteArray(149) { it.toByte() }
            lab.inject(Arm.RIGHT, WearMsg.SID, 0x01, notWear)
            lab.inject(Arm.RIGHT, CaseMsg.SID, 0x01, notProto)
            lab.inject(Arm.RIGHT, SettingsMsg.SID, 0x00, notProto)
            seen.sync()
            assertTrue(seen.notes("wear").single { "not a wear event" in it }.contains(notWear.hex()), "${seen.notes("wear")}")
            assertTrue(seen.notes("case").single { "did not parse" in it }.contains(notProto.hex()), "${seen.notes("case")}")
            assertTrue(seen.notes("sid").single { "threw" in it }.contains(notProto.hex()), "${seen.notes("sid")}")
            assertTrue(logged.filter { "not a wear event" in it || "did not parse" in it || "threw" in it }.let { l ->
                l.size == 3 && l.all { "… (the first 96 of 145 B)" in it || "… (the first 96 of 150 B)" in it } }, "the three Log lines keep the bound: $logged")
            lab.stop()
        } finally { wm.damage.core.util.Log.removeSink(sink); scope.cancel() }
    }

    /** ...and the per-frame notes stay bounded at 96 B, except that a session's FIRST frame past the bound is
     *  written whole — through the same said-once set, so once per kind per session. */
    @Test
    fun theFirstLongCaseFrameOfASessionIsWrittenWholeAndTheNextBounded(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val lab = RawLab(GlassFirmwareSim(), scope)
            val seen = Seen(scope, lab)
            lab.start(warmup())
            // GlassesCaseDataPackage{1 cmd 1, 2 magic 7, 3 caseInfo{1 soc 78}} and an unknown field 15 of 110 B: 120 B
            val long = Pb.cat(Pb.v(1, 1), Pb.v(2, 7), Pb.l(3, Pb.v(1, 78)), Pb.l(15, ByteArray(110) { it.toByte() }))
            assertEquals(120, long.size)
            repeat(2) { lab.inject(Arm.RIGHT, CaseMsg.SID, 0x01, long) }
            seen.sync()
            val notes = seen.notes("case")
            assertEquals(2, notes.size, "$notes")
            assertTrue(long.hex() in notes[0], "the first whole: ${notes[0]}")
            assertTrue((long.take(96).toByteArray().hex() + "… (the first 96 of 120 B)") in notes[1] && long.hex() !in notes[1], "the second bounded: ${notes[1]}")
            // the wear report's note is the other per-frame note: its own first long frame is written whole
            val wearLong = Pb.cat(WearMsg.event(true), Pb.l(15, ByteArray(100) { it.toByte() }))
            repeat(2) { lab.inject(Arm.RIGHT, WearMsg.SID, 0x01, wearLong) }
            seen.sync()
            val reports = seen.notes("wear").filter { "worn" in it }
            assertEquals(2, reports.size, "$reports")
            assertTrue(wearLong.hex() in reports[0] && wearLong.hex() !in reports[1], "$reports")
            lab.stop()
        } finally { scope.cancel() }
    }

    @Test
    fun theChargingFlipIsJournaledAtAnUnchangedLevel(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim().also { it.batteryPct = 61 }
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            val seen = Seen(scope, t)
            t.start(warmup())
            readsAnswered(sim, seen)
            assertEquals(listOf("glasses 61%"), seen.notes("battery"), "one note for one level, however many READs")
            sim.charging = true
            readsAnswered(sim, seen)
            assertEquals(listOf("glasses 61%", "glasses 61% charging"), seen.notes("battery"), "the flip itself is a change")
            sim.charging = false
            readsAnswered(sim, seen)
            assertEquals(listOf("glasses 61%", "glasses 61% charging", "glasses 61%"), seen.notes("battery"))
            sim.batteryPct = 60
            readsAnswered(sim, seen)
            assertEquals("glasses 60%", seen.notes("battery").last())
            assertEquals(4, seen.notes("battery").size)
            t.stop()
        } finally { scope.cancel() }
    }

    @Test
    fun thePollReadsTelemetryWhileThePageTrafficIsWanted(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim().also { it.damageContract = 2 }
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            t.pollTelemetry = true                             // off on an instant transport unless a test turns it on
            val seen = Seen(scope, t)
            t.start(warmup())
            val atStart = sim.telemetryReadsSeen
            until("the poll reads telemetry while the session paints") { sim.telemetryReadsSeen >= atStart + 3 }
            assertTrue(seen.faults().isEmpty(), "the poll's read raised a fault: ${seen.faults()}")

            // the shell goes to sleep: the lease is released on purpose and the page traffic stops. One poll
            // may have passed its test before the release; the lane is FIFO, so after three more READs its
            // telemetry read (queued right behind its READ) has been written.
            t.setLeaseWanted(false)
            val r0 = sim.deviceInfoReadsSeen
            until("three more READs after the release") { sim.deviceInfoReadsSeen >= r0 + 3 }
            val asleep = sim.telemetryReadsSeen
            val r1 = sim.deviceInfoReadsSeen
            until("the device-info READ goes on through the sleep") { sim.deviceInfoReadsSeen >= r1 + 6 }
            assertEquals(asleep, sim.telemetryReadsSeen, "no telemetry read while the page traffic sleeps")
            t.stop()

            // an upstream build: nothing answers field 112, so nothing is asked. A field-112 body there falls
            // through to the settings WRITE answer — the one counter that sees it is taken before the build test.
            val up = GlassFirmwareSim()
            val tu = SimTransport(up, scope, SimTransport.Timing(instant = true))
            tu.pollTelemetry = true
            tu.start(warmup())
            val u0 = up.deviceInfoReadsSeen
            until("six polls on the upstream build") { up.deviceInfoReadsSeen >= u0 + 6 }
            assertEquals(0, up.damageControlWritesSeen, "no field-112 write reaches a build without DamageCaps")
            tu.stop()

            // and the default on an instant transport is off: the pins that wait for a telemetry reply by name
            // are not answered by the poll's
            val quiet = GlassFirmwareSim().also { it.damageContract = 2 }
            val tq = SimTransport(quiet, scope, SimTransport.Timing(instant = true))
            tq.start(warmup())
            val q0 = quiet.telemetryReadsSeen
            val qr = quiet.deviceInfoReadsSeen
            until("six polls with the knob left alone") { quiet.deviceInfoReadsSeen >= qr + 6 }
            assertEquals(q0, quiet.telemetryReadsSeen)
            tq.stop()
        } finally { scope.cancel() }
    }

    /** R2 (RG-5): RIGHT answers each control request twice — its own copy and the one LEFT forwards
     *  (`HANDOFF.md` §59; measured on glass, the copies 0–144 ms apart) — and the poll's reply was logged and
     *  journaled twice. The second copy of a non-zero id among the session's last eight is neither; the
     *  session's first such copy is one `glass` note, so the journal still shows that replies arrive twice. */
    @Test
    fun eachTelemetryReplyIsJournaledOnce(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim().also { it.damageContract = 2; it.duplicateControlReplies = true }
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            t.pollTelemetry = true
            val seen = Seen(scope, t)
            t.start(warmup())
            until("six telemetry reads answered on RIGHT, the forwarded copies counted") { sim.telemetryReadsSeen >= 6 }
            seen.sync()
            val ids = seen.notes("glass").mapNotNull { Regex("""\bid=(\d+)""").find(it)?.groupValues?.get(1)?.toLong() }.filter { it != 0L }
            assertTrue(ids.isNotEmpty(), "the poll's replies are journaled: ${seen.notes("glass")}")
            assertEquals(ids.distinct(), ids, "each non-zero id once: ${seen.notes("glass")}")
            val said = seen.notes("glass").filter { "second copy" in it }
            assertEquals(1, said.size, "the first dropped copy is said, once: ${seen.notes("glass")}")
            assertTrue(said[0].startsWith("R a second copy of reply id "), said[0])
            t.stop()
        } finally { scope.cancel() }
    }

    /** The rule keeps the last eight ids, not the last one: the next request goes out as soon as the first copy
     *  answers, so the copies interleave (A, B, A′, B′). A second copy takes its id out (a third copy is news),
     *  and a session start forgets the ids and the note. */
    @Test
    fun interleavedSecondCopiesAreDroppedAndASessionStartForgetsThem(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val lab = RawLab(GlassFirmwareSim(), scope)
            val seen = Seen(scope, lab)
            fun record(id: Int) = lab.inject(Arm.RIGHT, SettingsMsg.SID, 0x00,
                Pb.cat(Pb.v(1, 3), Pb.v(2, 0), Pb.l(DamageMsg.TELEMETRY_FIELD, Pb.cat(Pb.v(1, id), Pb.v(3, 0)))))
            fun journaled() = seen.notes("glass").filter { it.startsWith("R id=") }.map { it.split(' ')[1] }
            lab.start(warmup())
            seen.sync()
            val base = journaled().size
            for (id in listOf(41, 42, 41, 42, 43, 41)) record(id)
            seen.sync()
            assertEquals(listOf("id=41", "id=42", "id=43", "id=41"), journaled().drop(base), "${seen.notes("glass")}")
            assertEquals(1, seen.notes("glass").count { "second copy" in it }, "${seen.notes("glass")}")

            assertTrue(lab.restartSession("test: a second session"))
            lab.start(warmup())
            seen.sync()
            val base2 = journaled().size
            record(43); record(43)
            seen.sync()
            assertEquals(listOf("id=43"), journaled().drop(base2), "the new session journals 43 once more: ${seen.notes("glass")}")
            assertEquals(2, seen.notes("glass").count { "second copy" in it }, "and says its first second copy: ${seen.notes("glass")}")
            lab.stop()
        } finally { scope.cancel() }
    }

    /** The default where it counts (the P4 verify pass: only the instant default had a pin). On a transport
     *  that runs at its real pace — both radios, the modeled-timing simulator — the poll's telemetry read is
     *  ON with the knob left alone: it is the reading `HANDOFF.md` §75.4 call 5 asks the journal for, and a
     *  default flipped to off would lose it on glass with every other pin green. The poll's first pass comes
     *  5 s after the start (its real pace), so this pin takes that long; the count is taken once the start
     *  has returned, so the start's own reads are not the poll's.
     *  Mutation watched: the default off on every transport. */
    @Test
    fun thePollsTelemetryReadIsOnByDefaultAtTheRealPace(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim().also { it.damageContract = 2 }
            val t = SimTransport(sim, scope)                   // modeled timing: not instant, the poll at its real pace
            assertNull(t.pollTelemetry, "the knob is left alone")
            t.start(warmup())
            val atStart = sim.telemetryReadsSeen
            val reads = sim.deviceInfoReadsSeen
            until("the poll's first pass reads telemetry, the knob left alone", limitMs = 30_000) { sim.telemetryReadsSeen > atStart }
            assertTrue(sim.deviceInfoReadsSeen > reads, "beside the poll's device-info READ")
            t.stop()
        } finally { scope.cancel() }
    }

    /** The P4 review's finding 7: a note never says "sent" for a query that did not leave. */
    @Test
    fun theWearQueryProbeSaysWhetherItWasSent(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim()
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            val seen = Seen(scope, t)
            assertFalse(t.queryWear(), "no session: nothing went out")
            t.devProbe("wear", "query")
            seen.sync()
            assertEquals(listOf("query not sent (probe): no session"), seen.notes("wear"))
            assertEquals(0, sim.wearQueriesSeen)

            t.start(warmup())
            until("the start's own query") { sim.wearQueriesSeen == 2 }
            t.devProbe("wear", "query")
            until("the probe's query reached both arms") { sim.wearQueriesSeen == 4 }
            seen.sync()
            assertEquals(1, seen.notes("wear").count { it.startsWith("query sent (probe)") })
            t.devProbe("wear", "sideways")                     // refused by value: journaled as the ask, nothing sent
            readsAnswered(sim, seen)
            assertEquals(4, sim.wearQueriesSeen)
            assertTrue(seen.notes("probe").contains("wear=sideways"))
            t.stop()
        } finally { scope.cancel() }
    }

    /**
     * R3 (review 2, 2B-1). A session that starts while the glasses are in Silent Mode sends no wear query
     * (op 7): the glasses say they are silent in the start's own READ, before the query, and the shell
     * goes to sleep at once (§36/§42: nothing of ours goes to glasses in Silent Mode beyond the wake
     * path). One note says so; the session the wake rebuilds asks, as every start does.
     * Watched on d10f612: the start wrote op 7 to both arms before the shell slept (2, not 0).
     */
    @Test
    fun aSessionThatStartsInSilentModeSendsNoWearQueryAndTheWakesSessionDoes(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp = java.nio.file.Files.createTempDirectory("damage-wear-silent")
        try {
            val sim = GlassFirmwareSim()
            sim.setSilent(true)                                // the both-temple press came before the start
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            val seen = Seen(scope, t)
            val shell = wm.damage.core.shell.Shell(FakeText(), t, wm.damage.core.shell.Persistence(tmp.resolve("state.json")), null, scope)
            val keeper = wm.damage.core.shell.ShellKeeper(shell, t, scope, retryPauseMs = 100)
            keeper.start()
            until("the session starts and the shell goes to sleep") { t.state.value.started && shell.glassesAsleep }
            until("the start's wear note") { seen.notes("wear").isNotEmpty() }
            readsAnswered(sim, seen)                           // the lane is past anything the start queued
            assertEquals(0, sim.wearQueriesSeen, "no wear query into Silent Mode: ${seen.notes("wear")}")
            assertEquals(listOf("query not sent: the glasses say Silent Mode is on — the session the wake rebuilds asks"),
                seen.notes("wear").filter { it.startsWith("query ") }, "said once")
            sim.setSilent(false)                               // the press again: the push OFF wakes the shell
            until("the wake's session asks, once per arm") { keeper.attempts == 2 && sim.wearQueriesSeen == 2 }
            readsAnswered(sim, seen)
            assertEquals(2, sim.wearQueriesSeen, "one query per arm, in the rebuilt session")
            assertEquals(1, seen.notes("wear").count { it.startsWith("query sent after the lease (op 7, both arms)") }, "${seen.notes("wear")}")
            keeper.stop()
        } finally { scope.cancel(); tmp.toFile().deleteRecursively() }
    }

    /** The arbitration transport forwards the query to the path that drives, carries that path's state
     *  whole, and (the review's finding 8) forgets what the path's session said — the wear report, the
     *  switch, the firmware's Silent Mode — at its own stop. */
    @Test
    fun thePathTransportForwardsTheQueryAndForgetsTheSessionAtItsStop(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim()
            sim.setWearDetectionSwitch(true)
            val ble = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            val path = wm.damage.core.transport.PathTransport(listOf(wm.damage.core.transport.PathTransport.Candidate("ble", ble)), scope, headStartMs = 0)
            assertFalse(path.queryWear(), "no path drives: nothing went out")
            path.start(warmup())
            until("the start's own query") { sim.wearQueriesSeen == 2 }
            assertTrue(path.queryWear(), "forwarded to the driving path")
            until("which wrote it to both arms") { sim.wearQueriesSeen == 4 }
            sim.sensorReportsWear(true)
            sim.setSilent(true)
            until("the path's state is the driving transport's, whole") {
                path.state.value.let { it.worn == true && it.wearSwitch == true && it.glassesSilent } }
            path.stop()
            assertEquals(Triple(null, null, false), path.state.value.let { Triple(it.worn, it.wearSwitch, it.glassesSilent) },
                "the stop forgets what the path's session said")
            assertFalse(path.queryWear())
        } finally { scope.cancel() }
    }

    /** `sim = …` on the MODELED-timing simulator (the desktop's and the phone's SIM target): there the poll's
     *  next READ is up to a minute away, so a knob the READ reports queues one READ itself (the review's
     *  finding 17). The first poll comes 5 s after the start and the next 60 s later; every wait below is
     *  ten seconds at most, so a reading that shows was the probe's own READ. */
    @Test
    fun theSimProbeMovesTheModelAndReadsAtOnce(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim()
            val t = SimTransport(sim, scope)                   // modeled timing
            val seen = Seen(scope, t)
            t.devProbe("sim", "battery=15")
            seen.sync()
            assertTrue(seen.notes("probe").any { it == "sim: the model's battery reads 15%; no session — the next start's READ reports it" }, "${seen.notes("probe")}")

            t.start(warmup())
            // R2 (RG-9 a): counted from after the start returns — the capability query and its 2 s re-asks
            // are command-2 requests too, and a re-ask could meet a ">= 2" before the poll's first READ
            val s0 = sim.deviceInfoReadsSeen
            until("the session's first poll has gone (5 s after the start)") { sim.deviceInfoReadsSeen > s0 }
            assertEquals(listOf("glasses 15%"), seen.notes("battery"))
            val r0 = sim.deviceInfoReadsSeen

            t.devProbe("sim", "battery=14")
            until("the level shows without waiting for the poll") { seen.notes("battery").lastOrNull() == "glasses 14%" }
            t.devProbe("sim", "charging=on")
            until("and the charging flip") { seen.notes("battery").lastOrNull() == "glasses 14% charging" }
            t.devProbe("sim", "wearswitch=on")
            until("the switch is read") { seen.notes("wear").any { it.startsWith("switch on (09-00 cmd 2; device-info field 10 = 1;") } }
            assertEquals(r0 + 3, sim.deviceInfoReadsSeen, "one READ per probe that needs one")
            t.devProbe("sim", "worn=no")
            until("a wear report") { seen.notes("wear").any { it.startsWith("R first report: not worn") } }
            t.devProbe("sim", "case=docked")
            until("two case frames") { seen.notes("case").size == 2 }
            assertTrue(seen.notes("case")[0].contains("in case 1") && seen.notes("case")[1].contains("level 78%"), "${seen.notes("case")}")
            t.devProbe("sim", "case=out")
            until("one more") { seen.notes("case").size == 3 }
            t.devProbe("sim", "battery=150")                   // refused by value, and said
            t.devProbe("sim", "warp=9")
            seen.sync()
            assertEquals(2, seen.notes("probe").count { it.startsWith("sim=") && "not taken — expected" in it }, "${seen.notes("probe")}")
            assertEquals(14, sim.batteryPct)
            assertEquals(r0 + 3, sim.deviceInfoReadsSeen, "`worn`, `case` and a refused value queue no READ")
            assertTrue(seen.faults().isEmpty(), "${seen.faults()}")
            t.stop()
        } finally { scope.cancel() }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun unhex(h: String) = ByteArray(h.length / 2) { i -> h.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}

/** One evaluation decides: the wait returns on the look that was true, or fails. */
private suspend fun until(what: String, limitMs: Long = 10_000, cond: () -> Boolean) {
    val t0 = System.currentTimeMillis()
    while (true) {
        if (cond()) return
        if (System.currentTimeMillis() - t0 >= limitMs) throw AssertionError("never true: $what")
        delay(5)
    }
}

/** A single-packet sid-0x09 frame from the glasses whose body carries the device-info block (root field 4). */
internal fun isDeviceInfoAnswer(packet: ByteArray): Boolean {
    if (packet.size < AaFrame.HEADER + AaFrame.CRC || (packet[6].toInt() and 0xFF) != SettingsMsg.SID) return false
    if ((packet[4].toInt() and 0xFF) > 1) return false
    val len = packet[3].toInt() and 0xFF
    return try {
        Pb.bytesField(packet.copyOfRange(AaFrame.HEADER, AaFrame.HEADER + len - AaFrame.CRC), SettingsMsg.DEVICE_INFO_FIELD) != null
    } catch (e: IllegalArgumentException) { false }
}
