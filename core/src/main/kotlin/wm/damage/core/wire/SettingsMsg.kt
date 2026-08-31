package wm.damage.core.wire

/**
 * sid 0x09 (SETTING) messages: the CFW's private control channel and the stock
 * settings we use.
 *
 * Lineage:
 *  - The lease/control channel is protobuf field 101 on G2SettingPackage, bytes
 *    ['F','C', version=1, op, nonceLo, nonceHi] — g2flash/patches/settings_ext.c
 *    (overview.md §4.1). Ops: 1 ACQUIRE/RENEW (wake), 2 RELEASE, 3 WAKE_CLAIM,
 *    4 WAKE_READY, 5 FB_ACQUIRE, 6 FB_RELEASE, 7 WEAR_QUERY. Damage needs 5/6.
 *    MagicRandom stays 0: fire-and-forget, consumed before the stock nanopb
 *    decoder discards the unknown field.
 *  - The FB lease lasts 90 s, is renewed every 45 s, must be sent to BOTH arms,
 *    and FAILS OPEN — stop renewing and stock LVGL silently repaints (§4.1).
 *  - Capability detection: field 100 of the settings READ response is a string
 *    starting "EVENCFW/" (settings_ext.c). Tag 100 sits above the stock fields,
 *    so stock decoders skip it — detection needs no timeout by construction.
 *  - ⚠ Field 100 is NOT the last field any more: g2flash a5d1c31 appends field
 *    104 (a 21-byte microphone-configuration read-back) to EVERY sid-0x09 READ
 *    response. Walk the message; never assume the capability field terminates it.
 *  - ⚠ Read field 100's length as a real VARINT. The firmware wrote it as a bare
 *    byte until a5d1c31, which broke the whole response the one time the string
 *    passed 127 bytes; it is a proper varint now and the string is 117 bytes, one
 *    growth spurt from crossing that line again. `Pb.fields` already does this.
 *  - The FB lease now also governs the texture cache (modes 12/13/14): the
 *    firmware frees the 64 KiB cache on lease expiry, on FB_RELEASE, on a FRESH
 *    acquire after a lapse, and on mode 11 — but keeps it across a renewal. So an
 *    atlas is uploaded once per lease, not once per session.
 *  - Holding the lease also keeps the stock "End this feature?" quit dialog
 *    suppressed and long-press forwarding alive; a5d1c31 made both conditional on
 *    it, where the CFW used to remove the dialog unconditionally.
 */
object SettingsMsg {
    const val SID = 0x09
    const val FLAG_REQUEST = 0x20
    const val FLAG_RESPONSE = 0x00

    const val CONTROL_FIELD = 101
    const val EVENT_FIELD = 102
    const val CAPABILITY_FIELD = 100

    /** Microphone control (phone -> glasses) and its read-back (glasses -> phone).
     *  Damage uses neither, but 104 trails EVERY sid-0x09 read response since
     *  a5d1c31, so anything parsing that response has to expect it. */
    const val MIC_CONTROL_FIELD = 103
    const val MIC_STATUS_FIELD = 104

    const val OP_WAKE_ACQUIRE = 1
    const val OP_WAKE_RELEASE = 2
    const val OP_FB_ACQUIRE = 5
    const val OP_FB_RELEASE = 6
    const val OP_WEAR_QUERY = 7

    /** Lease timing constants (settings_ext.c / FACECLAW_WAKE_LEASE_RENEW_MS). */
    const val LEASE_EXPIRY_MS = 90_000L
    const val LEASE_RENEW_MS = 45_000L

    /** The capability tokens Damage requires before painting anything
     *  (DESIGN.md §9.2b startup capability gate).
     *
     *  ⚠ Keep this list minimal and never add a token the firmware might drop for
     *  reasons of its own. g2flash a5d1c31 deleted `img576` and `compass10` from
     *  the advertised string purely to get it back under 127 bytes — **both
     *  features are still fully implemented**. A gate that demanded either would
     *  have refused a firmware that supports them. Gate on [contractVersion] for
     *  anything version-shaped, and on an OPTIONAL token only where its absence
     *  really should disable a feature. */
    val REQUIRED_CAPS = listOf("img640", "directfb", "fbguard", "imgz", "rle")

    /** Optional tokens, added by g2flash a5d1c31 (contract version 16). Absence
     *  means the firmware predates the feature, so we fall back rather than refuse. */
    const val CAP_CLEANUP = "cleanup11"       // mode 11 session cleanup
    const val CAP_TEXTURE_CACHE = "texcache12" // mode 12 cache writes
    const val CAP_TEXTURE_IMAGE = "teximg13"   // mode 13 cached-image draw
    const val CAP_TEXTURE_STRING = "texstr14"  // mode 14 cached-glyph draw
    const val CAP_BUILTIN_FONT = "font15"      // mode 15 — known, deliberately unused

    /** Tokens present in [capability], for optional-feature checks. */
    fun caps(capability: String): Set<String> =
        capability.substringAfter(' ', "").split(' ').filter { it.isNotEmpty() }.toSet()

    /** The `<n>` of `EVENCFW/<n>` — the CFW contract version, or null if malformed.
     *  History: 8 (877c8d9) · 12 · 13 · 15 · 16 (a5d1c31). Version 16 covers both a
     *  broken intermediate build and the fixed one, but the broken build's settings
     *  response does not decode at all, so it cannot reach this function. */
    fun contractVersion(capability: String): Int? =
        capability.removePrefix("EVENCFW/").substringBefore(' ').toIntOrNull()

    /** Whether this firmware has the lease-scoped texture cache and its draw modes. */
    fun hasTextureCache(capability: String): Boolean {
        val t = caps(capability)
        return CAP_TEXTURE_CACHE in t && CAP_TEXTURE_IMAGE in t && CAP_TEXTURE_STRING in t
    }

    fun control(op: Int, nonce: Int): ByteArray = Pb.cat(
        Pb.v(1, 1),                    // commandId
        Pb.v(2, 0),                    // MagicRandom 0 — fire-and-forget
        Pb.l(
            CONTROL_FIELD,
            byteArrayOf('F'.code.toByte(), 'C'.code.toByte(), 1,
                op.toByte(), (nonce and 0xFF).toByte(), ((nonce shr 8) and 0xFF).toByte()),
        ),
    )

    fun fbAcquire(nonce: Int): ByteArray = control(OP_FB_ACQUIRE, nonce)
    fun fbRelease(nonce: Int): ByteArray = control(OP_FB_RELEASE, nonce)

    /** Settings READ (commandId 2 + request wrapper) — its response carries the
     *  EVENCFW capability string in field 100 on a CFW, nothing extra on stock. */
    fun settingsQuery(msgId: Int): ByteArray = Pb.cat(
        Pb.v(1, 2),
        Pb.v(2, msgId),
        Pb.l(4, Pb.v(1, 1)),
    )

    /** Extract the capability string from a settings response payload, or null. */
    fun parseCapability(payload: ByteArray): String? = try {
        Pb.bytesField(payload, CAPABILITY_FIELD)?.toString(Charsets.UTF_8)
            ?.takeIf { it.startsWith("EVENCFW/") }
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Which required capability tokens are missing from an EVENCFW string. */
    fun missingCaps(capability: String): List<String> {
        val tokens = caps(capability)
        return REQUIRED_CAPS.filter { it !in tokens }
    }
}
