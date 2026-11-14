package wm.damage.core.util

import java.security.MessageDigest

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P6 (`CONTRACT.md` §10.2): every credential compare in one place, in constant time — no early exit
 * on the first byte that differs. The class swept at P6: the content host's hello (P2), the replica's token, the media
 * endpoint's token, the transport seam's two hellos, the setup page (its own `same`, unchanged), the web listener's
 * device secrets and claims (as hashes, `core/web/Devices.kt`), and the local secret.
 */
object Secrets {
    /** True when [given] is present and holds exactly [expected]'s bytes. */
    fun same(given: String?, expected: String): Boolean =
        given != null && MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))

    /** Two hashes compared in constant time. */
    fun same(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
}
