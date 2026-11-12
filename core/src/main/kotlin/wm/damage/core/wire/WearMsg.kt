package wm.damage.core.wire

/**
 * Service 0x10 (`UI_ONBOARDING_APP_ID = 16`, Even's `service_id_def.proto` — the descriptor embedded in g2-kit
 * `gen/service_id_def_pb.ts`): the glasses' wear event. Read-only here: Damage journals it and acts on nothing
 * (`DESIGN.md` §0; APK 0.64 build 1 of rest mode, `HANDOFF.md` §75.4).
 *
 * Lineage:
 *  - The shape is Even's `OnboardingDataPackage` (the descriptor embedded in g2-kit `gen/onboarding_pb.ts`,
 *    decoded 2026-11-10): `1 commandId` (enum: 1 CONFIG, 2 HEARTBEAT, 3 EVENT), `2 magicRandom`, `3 config`,
 *    `4 heartbeat`, `5 event` = `OnboardingEvent{1 event (1 = GLS_WEAR_STATUS), 2 eventParam, 3 errorCode}`.
 *  - The bytes the installed firmware sends: `~/damage-cfw patches/settings_ext.c :: faceclaw_send_wear_event`
 *    — ten bytes, `08 03 10 00 2a 04 08 01 10 <01 worn / 00 not worn>`, through the generic notify sender on
 *    service 0x10. Read for the fact; the code here is ours.
 *  - The frame flag is not part of the recognition: the stock sender uses flag 0x00 where most device-initiated
 *    traffic uses 0x01 (faceclaw `g2protocol/BleProtocol.java :: parseWearState`, its comment; read for the
 *    fact). Which flag the fork's sender puts on our wire is unread — the journal note carries it.
 */
object WearMsg {
    const val SID = 0x10
    const val CMD_EVENT = 3
    const val EVENT_FIELD = 5
    const val EVENT_WEAR_STATUS = 1

    /** Root field 1, for a note about a service-0x10 frame that is not a wear event; null when the bytes are
     *  not protobuf or carry no command id. */
    fun commandId(payload: ByteArray): Int? = try {
        Pb.varintField(payload, 1)?.toInt()
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Worn / not worn, or null when [payload] is not a wear event. A param the sender left out is 0 (proto3:
     *  a zero is not encoded by a schema-driven sender; the fork writes it out). A param other than 0 or 1 is
     *  no state this parser knows — null, so the frame is journaled whole instead of being read as either. */
    fun parse(payload: ByteArray): Boolean? = try {
        if ((Pb.varintField(payload, 1) ?: -1L).toInt() != CMD_EVENT) null
        else Pb.bytesField(payload, EVENT_FIELD)?.let { ev ->
            if ((Pb.varintField(ev, 1) ?: -1L).toInt() != EVENT_WEAR_STATUS) null
            else when (Pb.varintField(ev, 2) ?: 0L) {
                0L -> false
                1L -> true
                else -> null
            }
        }
    } catch (e: IllegalArgumentException) {
        null
    }

    /** The ten bytes as the fork writes them (`settings_ext.c :: faceclaw_send_wear_event`) — the simulator's
     *  copy. The zero magic and the zero param are written out, as the C does. */
    fun event(worn: Boolean): ByteArray =
        byteArrayOf(0x08, 0x03, 0x10, 0x00, 0x2a, 0x04, 0x08, 0x01, 0x10, if (worn) 1 else 0)
}

/**
 * Service 0x81 (`UX_GLASSES_CASE_APP_ID = 129`, g2-kit `gen/service_id_def_pb.ts`): the case reporting itself
 * through the glasses. Read-only: every frame becomes a `case` journal note; nothing is asked for and nothing
 * keys on it.
 *
 * Lineage:
 *  - The shape is Even's `GlassesCaseDataPackage` (the descriptor embedded in g2-kit `gen/glasses_case_pb.ts`,
 *    decoded 2026-11-10): `1 commandId` (1 = CASE_INFO), `2 magicRandom`, `3 caseInfo` =
 *    `GlassesCaseInfo{1 soc, 2 chargeStatus, 3 lidStatus, 4 inCaseStatus, 5 errorCode}`.
 *  - Our own capture: the official app's `81-20` request at connect was answered `81-00` with `soc = 78`
 *    (`CLAIMS.md`, `overview.md`). No unasked push is in any capture of ours; that the right lens pushes one
 *    on docking, lid, USB and each 1 % is upstream's measurement on 2.3.0.24 (faceclaw at `8b70585` — fetched,
 *    not the checked-out `c1d70ab`: `native/kotlin/shared/src/commonMain/kotlin/com/faceclaw/app/g2protocol/
 *    BleProtocol.kt` lines 1636–1642, the KDoc of `CaseInfoSnapshot`; read for the fact) and unread on our
 *    base — as are the meanings of the status values.
 */
object CaseMsg {
    const val SID = 0x81
    const val CMD_CASE_INFO = 1
    const val CASE_INFO_FIELD = 3

    /** One frame's fields; a null is a field the frame did not carry. [hasInfo]: field 3 was present. */
    data class Info(val cmd: Int?, val magic: Int?, val soc: Int?, val charge: Int?, val lid: Int?,
                    val inCase: Int?, val error: Int?, val hasInfo: Boolean)

    /** The frame's fields, or null only when the bytes are not protobuf. */
    fun parse(payload: ByteArray): Info? = try {
        val info = Pb.bytesField(payload, CASE_INFO_FIELD)
        fun f(n: Int) = info?.let { Pb.varintField(it, n)?.toInt() }
        Info(cmd = Pb.varintField(payload, 1)?.toInt(), magic = Pb.varintField(payload, 2)?.toInt(),
            soc = f(1), charge = f(2), lid = f(3), inCase = f(4), error = f(5), hasInfo = info != null)
    } catch (e: IllegalArgumentException) {
        null
    }

    /** A CASE_INFO frame as the schema lays it out — the simulator's copy. A null field is left out. */
    fun push(magic: Int, soc: Int?, charge: Int?, lid: Int?, inCase: Int?): ByteArray = Pb.cat(
        Pb.v(1, CMD_CASE_INFO),
        Pb.v(2, magic),
        Pb.l(CASE_INFO_FIELD, Pb.cat(listOfNotNull(
            soc?.let { Pb.v(1, it) }, charge?.let { Pb.v(2, it) }, lid?.let { Pb.v(3, it) }, inCase?.let { Pb.v(4, it) }))),
    )
}
