package wm.damage.core.wire

import wm.damage.core.geom.Geometry
import wm.damage.core.geom.LintError

/**
 * EvenHub (`e0-XX`) message builders and parsers for the CFW carrier path.
 *
 * Lineage — every layout below traces to a source:
 *  - Wrapper fields: Even's own EvenHub.proto (decoded from g2-kit/ble/gen,
 *    overview.md §9.1): Cmd=1, MagicRandom=2, CreateMessage=3, ImgRawMsg=5,
 *    TextUpgrade=9, ShutDownCmd=11, DevEvent=13, HeartPacketCmd=14.
 *  - Carrier layout: one 576x288 image container plus a FULL-SCREEN dummy text
 *    container (content " ", isEventCapture=true) — overview.md §4.1; image-only
 *    layouts ack but never paint, and the dummy widget is the event antenna the
 *    FB lease protects. (Structure read from faceclaw's buildCreateMixedImagePage
 *    as a protocol fact; implementation is our own.)
 *  - ImgRawMsg fields f1..f8 and CompressMode=0 always: overview.md §8 — the CFW
 *    signals its own compression with the mode byte inside the data.
 *  - msgId is protobuf field 2 and MUST stay one byte; >255 encodes as a 2-byte
 *    varint and the glasses silently drop the app slot (G2_BLE_PROTOCOL.md §3).
 */
object EvenHubMsg {
    const val SID = 0xE0
    const val FLAG_REQUEST = 0x20
    const val FLAG_ACK = 0x00
    const val FLAG_EVENT = 0x01
    const val FLAG_ABORT = 0x02   // e0-02: reassembly-failure abort (overview.md §9.2)

    // Cmd values (EvenHub.proto Cmd field 1)
    const val CMD_CREATE = 0
    const val CMD_IMAGE = 3
    const val CMD_TEXT_UPGRADE = 5
    const val CMD_REBUILD = 7
    const val CMD_SHUTDOWN = 9
    const val CMD_KEEPALIVE = 12

    /** Carrier container ids/names — ours; stable across the session. Container
     *  names are kept well under the disputed 14/16-char cap (CLAIMS.md). */
    const val TEXT_CONTAINER_ID = 1
    const val TEXT_CONTAINER_NAME = "damage"
    const val IMG_CONTAINER_ID = 10
    const val IMG_CONTAINER_NAME = "fb0"

    /** EvenHub app token for the launch wrapper f5 (g2cap demo used 10000;
     *  faceclaw uses 10000 — an unremarkable, known-accepted value). */
    const val APP_TOKEN = 10000

    private fun wrap(cmd: Int, msgId: Int, wrapperField: Int, inner: ByteArray): ByteArray {
        if (msgId !in 0..0xFF)
            throw LintError("msgId $msgId exceeds one byte — the glasses silently drop the app slot at >255")
        return Pb.cat(Pb.v(1, cmd), Pb.v(2, msgId), Pb.l(wrapperField, inner))
    }

    /**
     * The carrier CREATE (Cmd 0): ContainerTotalNum=2, one full-screen dummy
     * TEXT container (" ", isEventCapture) and one 576x288 IMAGE container whose
     * two 165,888 B firmware allocations the CFW reuses for its 640x480 shadow
     * (overview.md §4.1). MUST stay under the ~1000 B layout wall (BUD003).
     */
    fun carrierCreate(msgId: Int): ByteArray {
        val text = Pb.cat(
            Pb.v(1, 0), Pb.v(2, 0),            // x, y
            Pb.v(3, 576), Pb.v(4, 288),        // w, h (stock EvenHub carrier geometry)
            Pb.v(9, TEXT_CONTAINER_ID),
            Pb.s(10, TEXT_CONTAINER_NAME),
            Pb.v(11, 1),                        // isEventCapture — the antenna
            Pb.s(12, " "),                      // one space: can never scroll internally,
        )                                       // so every ring notch reaches us (§6.1)
        val image = Pb.cat(
            Pb.v(1, 0), Pb.v(2, 0),
            Pb.v(3, 576), Pb.v(4, 288),
            Pb.v(5, IMG_CONTAINER_ID),
            Pb.s(6, IMG_CONTAINER_NAME),
        )
        val inner = Pb.cat(
            Pb.v(1, 2),                         // ContainerTotalNum
            Pb.l(3, text),                      // TextObject wrapper field
            Pb.l(4, image),                     // ImageObject wrapper field
            Pb.v(5, APP_TOKEN),
        )
        val frame = wrap(CMD_CREATE, msgId, 3, inner)
        val errs = Geometry.checkFrameSize(frame.size, Geometry.FrameKind.LAYOUT)
        if (errs.isNotEmpty()) throw LintError(errs.joinToString())
        return frame
    }

    /** The periodic dummy-text refresh faceclaw sends against the carrier
     *  (TextContainerUpgrade{ContentOffset=0, ContentLength=1, Content=" "}) —
     *  overview.md §4.1. Keeps the stock widget warm without repainting us. */
    fun carrierTextUpgrade(msgId: Int): ByteArray = wrap(
        CMD_TEXT_UPGRADE, msgId, 9,
        Pb.cat(
            Pb.v(1, TEXT_CONTAINER_ID), Pb.s(2, TEXT_CONTAINER_NAME),
            Pb.v(3, 0), Pb.v(4, 1), Pb.s(5, " "),
        ),
    )

    /** One ImgRawMsg fragment (Cmd 3). [data] is a slice of the CFW image buffer
     *  (a mode payload); the CFW dispatches on its first byte after reassembly. */
    fun imageFragment(
        msgId: Int, sessionId: Int, totalBytes: Int,
        fragIndex: Int, data: ByteArray,
    ): ByteArray {
        val errs = Geometry.checkFrameSize(data.size, Geometry.FrameKind.IMAGE)
        if (errs.isNotEmpty()) throw LintError(errs.joinToString())
        return wrap(
            CMD_IMAGE, msgId, 5,
            Pb.cat(
                Pb.v(1, IMG_CONTAINER_ID),
                Pb.s(2, IMG_CONTAINER_NAME),
                Pb.v(3, sessionId),            // MapSessionId — real semantics, not a nonce
                Pb.v(4, totalBytes),
                Pb.v(5, 0),                    // CompressMode = 0 ALWAYS on the CFW path
                Pb.v(6, fragIndex),
                Pb.v(7, data.size),
                Pb.l(8, data),
            ),
        )
    }

    /** Session keepalive (Cmd 12) — G2CC's proven encoding `08 0c 10 <id> 72 00`.
     *  Needed only when idle: the CFW resets the EvenHub keepalive on every image
     *  message (zlib_glue.c FW_KEEPALIVE_RESET), so a rendering stream is
     *  self-sustaining. */
    fun keepalive(msgId: Int): ByteArray = wrap(CMD_KEEPALIVE, msgId, 14, ByteArray(0))

    // ------------------------------------------------------------------ parsing
    /**
     * `EvenHub_ErrorCode_List` — Even's own enum, decoded from the vendor
     * `FileDescriptorProto` embedded in `g2-kit/ble/gen/EvenHub_pb.ts`.
     *
     * 🔴 **This is a STATUS enum, not an error code.** Its success value is
     * different for every operation: 0 for a page CREATE, **4 for image raw
     * data**, 6 for a rebuild, 8 for text, 10 for shutdown, 12 for a heartbeat,
     * 13 for audio control. Only the odd-numbered members and 1/2/3/14 are
     * failures. Treating "non-zero" as an error refuses the glasses' own
     * success ack — which is exactly what happened at first light on
     * 2026-08-30: the pair acked our warmup image with 4 and the transport
     * called it a failed start, over and over, while the link itself was
     * perfect. The simulator had modeled success as an ABSENT field 8, so
     * nothing offline could catch it.
     */
    val STATUS_NAMES = mapOf(
        0L to "CREATE_PAGE_SUCCESS",
        1L to "CREATE_INVALID_CONTAINER",
        2L to "CREATE_OVERSIZE_RESPONSE_CONTAINER",
        3L to "CREATE_OUTOFMEMORY_CONTAINER",
        4L to "UPGRADE_IMAGE_RAW_DATA_SUCCESS",
        5L to "UPGRADE_IMAGE_RAW_DATA_FAILED",
        6L to "REBUILD_PAGE_SUCCESS",
        7L to "REBUILD_PAGE_FAILED",
        8L to "UPGRADE_TEXT_DATA_SUCCESS",
        9L to "UPGRADE_TEXT_DATA_FAILED",
        10L to "UPGRADE_SHUTDOWN_SUCCESS",
        11L to "UPGRADE_SHUTDOWN_FAILED",
        12L to "UPGRADE_HEARTBEAT_PACKET_SUCCESS",
        13L to "AUDIO_CTR_SUCCESS",
        14L to "AUDIO_CTR_FAILED",
    )

    val STATUS_SUCCESS = setOf(0L, 4L, 6L, 8L, 10L, 12L, 13L)

    /** The success status the firmware returns for a given request Cmd. */
    fun successStatusFor(cmd: Int): Long = when (cmd) {
        CMD_CREATE -> 0L
        CMD_IMAGE -> 4L
        CMD_REBUILD -> 6L
        CMD_TEXT_UPGRADE -> 8L
        CMD_SHUTDOWN -> 10L
        CMD_KEEPALIVE -> 12L
        else -> 0L
    }

    fun statusName(code: Long): String = STATUS_NAMES[code] ?: "unknown status $code"

    /** e0-00 ack: f1 = request Cmd + 1, f2 echoes msgId. [errorCode] is the status
     *  when it denotes a FAILURE, and null when the operation succeeded; [status]
     *  keeps the raw value either way so a log can name it. */
    data class Ack(val ackType: Int, val msgId: Int, val errorCode: Long?, val status: Long? = null) {
        val statusText: String get() = status?.let { statusName(it) } ?: "no status field"
    }

    fun parseAck(payload: ByteArray): Ack? = try {
        val f = Pb.fields(payload)
        val t = f.firstOrNull { it.field == 1 && it.varint != null }?.varint ?: return null
        val id = f.firstOrNull { it.field == 2 && it.varint != null }?.varint ?: -1
        // Image acks carry ImgResCmd in the wrapper; the status is its field 8.
        // Each sibling parses under its OWN try (review 2026-09-01 L6): one
        // plain-string bytes field in a future firmware's wrapper must not
        // void the whole ack — that stalls the lane until the msgId cycle.
        var status: Long? = null
        for (sub in f) if (sub.bytes != null) {
            val e = try { Pb.varintField(sub.bytes, 8) } catch (x: IllegalArgumentException) { null }
            if (e != null) status = e
        }
        val err = status?.takeIf { it !in STATUS_SUCCESS }
        Ack(t.toInt(), id.toInt(), err, status)
    } catch (e: IllegalArgumentException) {
        null
    }

    /** e0-01 input event (DevEvent field 13). Three sub-shapes per
     *  G2_BLE_PROTOCOL.md §6.6; Sys_ItemEvent f2 is EventSource
     *  (1=GLASSES_R, 2=RING, 3=GLASSES_L — Even's own schema). */
    sealed class Event {
        data class Gesture(val type: Int, val source: Int) : Event()
        data class ListSelect(val containerId: Int, val name: String, val index: Int) : Event()
        data class TextEvent(val containerId: Int, val name: String, val type: Int) : Event()
    }

    // OsEventTypeList (vendor schema) + CFW SysEvents 9/10 (gesture_fwd.c)
    const val EV_CLICK = 0
    const val EV_SCROLL_TOP = 1
    const val EV_SCROLL_BOTTOM = 2
    const val EV_DOUBLE_CLICK = 3
    const val EV_FOREGROUND_ENTER = 4
    const val EV_FOREGROUND_EXIT = 5
    const val EV_SYSTEM_EXIT = 7
    const val EV_IMU_REPORT = 8
    const val EV_RING_LONG_PRESS = 9
    const val EV_RING_LONG_PRESS_RELEASE = 10

    /** EventSource (Sys_ItemEvent field 2).
     *
     *  🔴 **Only event types 0 (CLICK) and 3 (DOUBLE_CLICK) ever carry one.** The
     *  stock sender FUN_004da16a writes `EventSource` inside a branch gated on
     *  `EventType == 0 || EventType == 3`; the whole message struct is memset to 0
     *  on entry, so for the CFW's long-press events 9 and 10 the field stays 0 and
     *  proto3 omits it from the encoding entirely. Verified at instruction level
     *  against our pinned 2.2.6.10 base and against 2.2.4.34 — it has never
     *  worked, and it did not change when g2flash a5d1c31 stopped filtering
     *  long-press by source (`patches/gesture_fwd.c`, commit 0754874).
     *
     *  So: a long-press is UNATTRIBUTED. Since a5d1c31 it can come from the ring
     *  OR either temple touchpad, and nothing on the wire says which. Do not build
     *  grammar on the source of events 9/10 — `DESIGN.md` §1.2's "a bare
     *  long-press is a no-op" is what makes the extra accidental source harmless.
     *  The only discrimination available is which arm's link the notify arrived on. */
    const val SRC_NONE = 0
    const val SRC_GLASSES_R = 1
    const val SRC_RING = 2
    const val SRC_GLASSES_L = 3

    /** Whether the firmware reports a source for [eventType] at all. */
    fun reportsSource(eventType: Int) = eventType == EV_CLICK || eventType == EV_DOUBLE_CLICK

    /** A gesture's name, for logs that a human reads while wearing the glasses. */
    fun eventName(type: Int): String = when (type) {
        EV_CLICK -> "TAP"
        EV_SCROLL_TOP -> "SCROLL_UP"
        EV_SCROLL_BOTTOM -> "SCROLL_DOWN"
        EV_DOUBLE_CLICK -> "DOUBLE_TAP"
        EV_FOREGROUND_ENTER -> "FOREGROUND_ENTER"
        EV_FOREGROUND_EXIT -> "FOREGROUND_EXIT"
        EV_SYSTEM_EXIT -> "SYSTEM_EXIT"
        EV_IMU_REPORT -> "IMU"
        EV_RING_LONG_PRESS -> "LONG_PRESS"
        EV_RING_LONG_PRESS_RELEASE -> "LONG_PRESS_RELEASE"
        else -> "event $type"
    }

    fun parseEvent(payload: ByteArray): Event? = try {
        val dev = Pb.bytesField(payload, 13) ?: return null
        val listEv = Pb.bytesField(dev, 1)
        val textEv = Pb.bytesField(dev, 2)
        val sysEv = Pb.bytesField(dev, 3)
        when {
            sysEv != null -> Event.Gesture(
                (Pb.varintField(sysEv, 1) ?: 0L).toInt(),
                (Pb.varintField(sysEv, 2) ?: 0L).toInt(),
            )
            listEv != null -> Event.ListSelect(
                (Pb.varintField(listEv, 1) ?: -1L).toInt(),
                Pb.bytesField(listEv, 2)?.toString(Charsets.UTF_8) ?: "",
                (Pb.varintField(listEv, 4) ?: 0L).toInt(),
            )
            textEv != null -> Event.TextEvent(
                (Pb.varintField(textEv, 1) ?: -1L).toInt(),
                Pb.bytesField(textEv, 2)?.toString(Charsets.UTF_8) ?: "",
                (Pb.varintField(textEv, 3) ?: 0L).toInt(),
            )
            else -> null
        }
    } catch (e: IllegalArgumentException) {
        null
    }
}
