package wm.damage.core.wire

/**
 * sid 0x0F (LOGGER): the stock firmware's own log channel, used by Damage only
 * as a Phase 0 dev probe (`FORK.md` M0.5, `HANDOFF.md` §49) — never by the shell.
 *
 * Lineage:
 *  - Field numbers and the command enum are Even's own schema: `logger.proto`
 *    (`logger_main_msg_ctx`), decoded from g2-kit-unofficial `ble/gen/logger_pb.ts`
 *    by `research/decode/schema.py` (grade V): 1 cmd, 2 magicRandom, 3 bleTransEn,
 *    4 levelMsg{1 bleTransLevel}, 5 logStr, 6 requestMsg, 7 delMsg; cmd 0 heartbeat,
 *    1 BLE_LOGGER_SWITCH_SET, 2 BLE_LOGGER_LEVEL_SET, 3 DEVICE_SEND_LOGGER_DATA,
 *    4 REQUEST_FILE_NAME, 5 DELETE_FILE_NAME, 6 DELETE_ALL_LOGGER_FILE.
 *  - What the 2.2.6.10 handler does, read in the stock image (grade V, disassembly;
 *    `logger_setting.c`, the handler at 0x004592DC, which the corpus's Ghidra pass
 *    did not discover):
 *      cmd 1 checks the payload union's tag is 3 (bleTransEn), then FUN_00458DF0
 *      sets a RAM flag (0x20074FE2) and registers the per-line sender FUN_005BF972
 *      with the logger; nothing is written to storage. It answers {cmd 1, magic, 3}.
 *      Each log line then goes out as its own sid-0x0F notification
 *      {cmd 3, logStr = at most 128 bytes of the line}.
 *      The flag is cleared at every display-thread application start and display
 *      upgrade switch (FUN_0044227E), so a session CREATE ends the stream: the
 *      probe re-sends the switch after each session start while it is wanted.
 *  - Deliberately NOT built: cmd 2 changes the global log filter for every module;
 *    cmd 4 first switches the link to its fast profile (FUN_00478110) and scans
 *    the log directory — a side effect on the very link behaviour under study;
 *    cmds 5 and 6 remove log files.
 *  - Flag 0x20 on a request, as on sids 0x04, 0x09, 0x80 and 0xE0 (captures).
 *  - bleTransEn is a member of a oneof: the handler reads the union's tag, so
 *    "off" must still carry field 3 (value 0) explicitly.
 */
object LoggerMsg {
    const val SID = 0x0F
    const val FLAG_REQUEST = 0x20

    const val CMD_SWITCH_SET = 1
    const val CMD_DEVICE_SEND_DATA = 3

    /** BLE_LOGGER_SWITCH_SET: start (or stop) the per-line stream on the arm it
     *  is written to. [msgId] rides MagicRandom and comes back in the answer. */
    fun switchSet(msgId: Int, on: Boolean): ByteArray = Pb.cat(
        Pb.v(1, CMD_SWITCH_SET),
        Pb.v(2, msgId),
        Pb.v(3, if (on) 1 else 0),
    )

    /** One decoded sid-0x0F message. Absent fields stay null. */
    data class Parsed(val cmd: Int, val magic: Int?, val transEn: Int?, val logStr: String?)

    /** Decodes cmd, magic, bleTransEn and logStr; null for a payload that does
     *  not parse (the caller logs it — no silent drop). */
    fun parse(payload: ByteArray): Parsed? = try {
        val f = Pb.fields(payload)
        Parsed(
            cmd = (f.firstOrNull { it.field == 1 && it.varint != null }?.varint ?: 0L).toInt(),
            magic = f.firstOrNull { it.field == 2 && it.varint != null }?.varint?.toInt(),
            transEn = f.firstOrNull { it.field == 3 && it.varint != null }?.varint?.toInt(),
            logStr = f.firstOrNull { it.field == 5 && it.bytes != null }?.bytes?.toString(Charsets.UTF_8),
        )
    } catch (e: IllegalArgumentException) {
        null
    }
}
