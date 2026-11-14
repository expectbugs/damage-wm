package wm.damage.core.web

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import wm.damage.core.app.Levels
import wm.damage.core.shell.Persistence
import wm.damage.core.util.Log
import wm.damage.core.util.Secrets

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P6 (`CONTRACT.md` §10.1–§10.3, §10.8, §13 (b) "approval request", "claim value", "per-device
 * secret"): the web interface's device table, `devices.json` in the data directory (in the nightly copy, dec. D.1 /
 * E.5), and the pages waiting for approval.
 *
 * - **A device is its secret**: 32 random bytes in its cookie, stored only as their SHA-256 and compared in constant
 *   time against every live row ([authenticate]). Revocation deletes the hash.
 * - **A waiting page** ([request]) gets a short code in an unambiguous alphabet; its row lives while its connection is
 *   open ([drop] at the end) — no cap, no queue, no timer (not a Bluetooth, render or input path).
 * - **Approval** ([approve]) from a full interface sends the waiting page a single-use claim (its hash alone is kept);
 *   the page redeems it with one request ([redeem]), which creates the row and returns the secret for the cookie. A
 *   claim is void once redeemed, and when its waiting connection ends unredeemed (the approval is then dropped and the
 *   page asks again). Deny ends the waiting page (`end{denied}`).
 * - **Replace**: a waiting page that names a listed device ([Pending.replaces], the device id it remembers) takes that
 *   row over when redeemed: the old secret stops working and the old row's connections end (`end{replaced}`).
 * - One lock over the rows and the waiting pages. The file is written atomically (`Persistence.writeAtomic`, forced)
 *   on every change but `lastSeen`, which is written at most once per [SEEN_WRITE_MS] per device; a failed write
 *   undoes the change in memory and the caller is answered with the failure, never left believing it landed.
 * - No secret, claim or code reaches a log line or an exception's text.
 */
class Devices(
    private val file: Path,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val random: SecureRandom = SecureRandom(),
    private val write: (Path, String) -> Unit = { p, t -> Persistence.writeAtomic(p, t, sync = true) },
) {
    /** One approved device (C§10.2's row; `set` arrives with the TV in S2). [secretHash]: hex SHA-256; empty once
     *  revoked. */
    @Serializable
    data class Row(
        val id: String,
        val name: String,
        val kind: String,
        val level: String,
        val tailnetName: String? = null,
        val address: String? = null,
        val secretHash: String,
        val approvedAt: Long,
        val approvedBy: String,
        val lastSeen: Long = 0,
        val revoked: Boolean = false,
    )

    @Serializable
    private data class Disk(val v: Int = 1, val devices: List<Row> = emptyList())

    /** The waiting page's side: told of its approval or its end. Called outside the lock; must not block. */
    interface Waiting {
        fun approved(claim: String)
        fun ended(reason: String)
    }

    /** One page waiting for approval. */
    class Pending internal constructor(
        val code: String,
        val address: String,
        val tailnetName: String?,
        val name: String,
        val kind: String,
        /** A listed device id the page remembers: approval replaces that row. */
        val replaces: String?,
        val since: Long,
        internal val waiting: Waiting,
    ) {
        /** Under the lock. */
        internal var claimHash: ByteArray? = null
        internal var level: String? = null
        internal var approvedBy: String? = null
        val approvedLevel: String? get() = level
    }

    /** What changed: the table or the waiting list ([Changed]), or a device whose connections must end. */
    sealed class Event {
        object Changed : Event()
        data class Ended(val device: String, val reason: String) : Event()
    }

    /** A redeemed claim: the row and the secret its cookie carries. */
    class Redeemed(val row: Row, val secret: String)

    private val lock = Any()
    private val rows = LinkedHashMap<String, Row>()
    private val waiting = LinkedHashMap<String, Pending>()
    private val seenWritten = HashMap<String, Long>()
    private val listeners = CopyOnWriteArrayList<(Event) -> Unit>()

    /** What [load] found wrong (said by the start), or null. */
    @Volatile var problem: String? = null
        private set

    /** Why the table must not be written (a file that did not decode and could not be set aside), or null. */
    @Volatile private var frozen: String? = null

    fun addListener(l: (Event) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Event) -> Unit) { listeners.remove(l) }

    /** Reads the file once (at the start). A file that does not decode is set aside whole beside it and said; the
     *  table then starts empty (each device is approved again), and nothing overwrites what was set aside. */
    fun load(): Devices {
        val text = try {
            String(Files.readAllBytes(file), Charsets.UTF_8)
        } catch (e: NoSuchFileException) {
            return this
        } catch (e: java.io.IOException) {
            // fix round 1, ruling 2.3 (11): a file there that could not be read (a permission, an I/O error) is never
            // replaced by a table holding only the next approval: frozen, as an undecodable file that cannot be set aside
            frozen = "${file.fileName} could not be read (${e::class.simpleName}); the device table is not written until " +
                "it is fixed - no device is approved this run"
            problem = frozen
            Log.e(TAG, problem!!)
            return this
        }
        try {
            val d = JSON.decodeFromString(Disk.serializer(), text)
            synchronized(lock) { for (r in d.devices) rows[r.id] = r }
        } catch (e: Exception) {
            val aside = file.resolveSibling("${file.fileName}.bad-${clock()}")
            problem = try {
                Files.move(file, aside, StandardCopyOption.ATOMIC_MOVE)
                "${file.fileName} did not decode (${e::class.simpleName}); set aside as ${aside.fileName} - every " +
                    "device is approved again"
            } catch (m: Exception) {
                frozen = "${file.fileName} did not decode and could not be set aside (${m::class.simpleName}); the device " +
                    "table is not written until it is fixed"
                frozen
            }
            Log.e(TAG, problem!!)
        }
        return this
    }

    // ---- reading ----

    fun rows(): List<Row> = synchronized(lock) { rows.values.toList() }

    fun pending(): List<Pending> = synchronized(lock) { waiting.values.toList() }

    /** Why a connection whose credential named row [id] at [approval] (the row's secret hash then; null = not read)
     *  ends at its welcome, or null: `revoked` when no live row holds [id] (fix round 1, ruling 2.3 (9)), `replaced`
     *  when the row was approved again since (fix round 2, ruling 2.4). */
    fun endedSince(id: String, approval: String?): String? = synchronized(lock) {
        val r = rows[id]
        when {
            r == null || r.revoked -> wm.damage.core.app.AppWire.END_REVOKED
            approval != null && r.secretHash != approval -> wm.damage.core.app.AppWire.END_REPLACED
            else -> null
        }
    }

    /** The live row whose secret is [secret], or null; each live row compared in constant time. Marks it seen. */
    fun authenticate(secret: String?): Row? {
        if (secret.isNullOrEmpty() || secret.length > 128) return null
        val h = Secrets.sha256(secret.toByteArray(Charsets.US_ASCII))
        var hit: Row? = null
        var save = false
        synchronized(lock) {
            for (r in rows.values) {
                if (r.revoked || r.secretHash.isEmpty()) continue
                if (Secrets.same(h, unhex(r.secretHash)) && hit == null) hit = r
            }
            val r = hit ?: return null
            val now = clock()
            rows[r.id] = r.copy(lastSeen = now)
            if (now - (seenWritten[r.id] ?: 0L) >= SEEN_WRITE_MS) {
                seenWritten[r.id] = now
                save = true
            }
        }
        // `lastSeen` is advisory: a failed write of it alone is said and the request goes on
        if (save) try { persist() } catch (e: Exception) { Log.e(TAG, "lastSeen could not be written (${e::class.simpleName}: ${e.message})") }
        return hit
    }

    // ---- the waiting pages ----

    /** A page with no device asks to be approved; its row lives until [drop]. */
    fun request(address: String, tailnetName: String?, name: String, kind: String, replaces: String?, w: Waiting): Pending {
        // ruling 2.3 (7): a name over the limit is refused with the reason, never cut
        val asked = clean(name)
        val tooLong = nameRefusal(asked)
        val p = synchronized(lock) {
            var code: String
            do code = code() while (code in waiting)
            val known = replaces?.takeIf { it in rows }
            Pending(code, address, tailnetName, asked.takeIf { tooLong == null } ?: DEFAULT_NAME, kind, known, clock(), w)
                .also { waiting[code] = it }
        }
        Log.i(TAG, "a page from ${p.tailnetName ?: p.address} is waiting for approval" +
            (p.replaces?.let { " (it names the listed device $it)" } ?: "") +
            (tooLong?.let { "; its own name was not used ($it): it waits as '$DEFAULT_NAME'" } ?: ""))
        fire(Event.Changed)
        return p
    }

    /** The waiting page's connection ended: its row goes, and an approval it did not redeem is dropped. */
    fun drop(p: Pending) {
        val gone = synchronized(lock) { waiting.remove(p.code, p) }
        if (gone) {
            if (p.claimHash != null) Log.i(TAG, "an approval for ${p.tailnetName ?: p.address} was dropped: its page left before taking it")
            fire(Event.Changed)
        }
    }

    /** Approves the page waiting under [code] at [level], by [by]; null = done (the page was sent its claim). */
    fun approve(code: String, level: String, by: String): String? {
        frozen?.let { return it }   // fix round 2, ruling 2.3: its redemption could not be written; say why, never "done"
        if (!Levels.valid(level)) return "the level '$level' is not one of ${Levels.ALL.joinToString(", ")}"
        val claim = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { random.nextBytes(it) })
        val p = synchronized(lock) {
            val p = waiting[code.uppercase()] ?: return "no page is waiting under that code (it may have closed)"
            p.claimHash = Secrets.sha256(claim.toByteArray(Charsets.US_ASCII))
            p.level = level
            p.approvedBy = by
            p
        }
        Log.i(TAG, "the page from ${p.tailnetName ?: p.address} approved at $level by $by; waiting for it to take the approval")
        p.waiting.approved(claim)
        fire(Event.Changed)
        return null
    }

    /** Denies the page waiting under [code]; null = done (the page was told `end{denied}`). */
    fun deny(code: String, by: String): String? {
        val p = synchronized(lock) { waiting.remove(code.uppercase()) ?: return "no page is waiting under that code (it may have closed)" }
        Log.i(TAG, "the page from ${p.tailnetName ?: p.address} was denied by $by")
        p.waiting.ended(wm.damage.core.app.AppWire.END_DENIED)
        fire(Event.Changed)
        return null
    }

    /** Redeems [claim] once: the row is written (or the remembered one replaced) and its secret returned; null when
     *  the claim is not a live one. Throws when the table cannot be written (nothing changed then). */
    fun redeem(claim: String?): Redeemed? {
        if (claim.isNullOrEmpty() || claim.length > 128) return null
        val h = Secrets.sha256(claim.toByteArray(Charsets.US_ASCII))
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { random.nextBytes(it) })
        val secretHash = hex(Secrets.sha256(secret.toByteArray(Charsets.US_ASCII)))
        var replaced: Row? = null
        val row = synchronized(lock) {
            var p: Pending? = null
            for (w in waiting.values) {
                val ch = w.claimHash ?: continue
                if (Secrets.same(h, ch) && p == null) p = w
            }
            val got = p ?: return null
            val now = clock()
            val old = got.replaces?.let { rows[it] }
            val r = if (old != null) old.copy(level = got.level!!, secretHash = secretHash, approvedAt = now,
                approvedBy = got.approvedBy!!, tailnetName = got.tailnetName, address = got.address, lastSeen = now, revoked = false)
            else Row(newId(), got.name, got.kind, got.level!!, got.tailnetName, got.address, secretHash, now, got.approvedBy!!, now)
            rows[r.id] = r
            try {
                persistLocked()
            } catch (e: Exception) {
                if (old != null) rows[old.id] = old else rows.remove(r.id)
                throw IllegalStateException("${file.fileName} could not be written (${e::class.simpleName}); the approval stands, take it again", e)
            }
            // single use: the claim and its waiting row go only once the row has landed
            waiting.remove(got.code)
            replaced = old?.takeIf { !it.revoked }
            seenWritten[r.id] = now
            r
        }
        Log.i(TAG, "device ${row.id} (${row.name}) approved at ${row.level} by ${row.approvedBy}" +
            (replaced?.let { "; it replaces its old row, whose connections end" } ?: ""))
        replaced?.let { fire(Event.Ended(it.id, wm.damage.core.app.AppWire.END_REPLACED)) }
        fire(Event.Changed)
        return Redeemed(row, secret)
    }

    /** Revokes [id] (C§10.8): its hash deleted, its connections ended with `end{revoked}`. Null = done. */
    fun revoke(id: String, by: String): String? {
        synchronized(lock) {
            val r = rows[id] ?: return "no device $id"
            if (r.revoked) return "device $id was already removed"
            rows[id] = r.copy(revoked = true, secretHash = "")
            try {
                persistLocked()
            } catch (e: Exception) {
                rows[id] = r
                return "${file.fileName} could not be written (${e::class.simpleName}: ${e.message}); device $id is NOT removed"
            }
        }
        Log.i(TAG, "device $id removed by $by")
        fire(Event.Ended(id, wm.damage.core.app.AppWire.END_REVOKED))
        fire(Event.Changed)
        return null
    }

    /** Renames [id]; null = done. */
    fun rename(id: String, name: String): String? {
        val c = clean(name)
        nameRefusal(c)?.let { return it }                    // ruling 2.3 (7): refused with the reason, never cut
        synchronized(lock) {
            val r = rows[id] ?: return "no device $id"
            rows[id] = r.copy(name = c)
            try {
                persistLocked()
            } catch (e: Exception) {
                rows[id] = r
                return "${file.fileName} could not be written (${e::class.simpleName}: ${e.message}); the name is unchanged"
            }
        }
        fire(Event.Changed)
        return null
    }

    // ---- inside ----

    private fun persist() = synchronized(lock) { persistLocked() }

    private fun persistLocked() {
        frozen?.let { throw IllegalStateException(it) }
        write(file, JSON.encodeToString(Disk.serializer(), Disk(devices = rows.values.toList())))
    }

    private fun fire(e: Event) {
        for (l in listeners) try {
            l(e)
        } catch (x: Exception) {
            Log.e(TAG, "a device-table listener failed on $e", x)
        }
    }

    private fun code(): String = String(CharArray(CODE_LEN) { ALPHABET[random.nextInt(ALPHABET.length)] })

    private fun newId(): String {
        var id: String
        do id = ID_PREFIX + hex(ByteArray(3).also { random.nextBytes(it) }) while (id in rows)
        return id
    }

    companion object {
        const val FILE = "devices.json"
        /** Every id this table gives out begins so (the phone and the PC's own device never do). */
        const val ID_PREFIX = "web-"

        /** Whether [device] is an id of this table's kind. */
        fun isTableId(device: String): Boolean = device.startsWith(ID_PREFIX)
        /** No 0/O, 1/I/L, 2/Z, 5/S, 8/B: a code read off one screen and typed on another. */
        const val ALPHABET = "ACDEFGHJKMNPQRTUVWXY34679"
        const val CODE_LEN = 6
        /** `lastSeen` reaches the file at most this often per device. */
        const val SEEN_WRITE_MS = 10 * 60_000L
        private const val TAG = "devices"
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** A device name's limit (it is shown on the approval row and the command line's listing). */
        const val NAME_MAX = 40
        /** A waiting page's name when it sent none, or one that could not be used. */
        const val DEFAULT_NAME = "a browser"

        /** A name kept printable; never cut ([nameRefusal] says why one is too long). */
        fun clean(name: String): String =
            name.filter { it >= ' ' && it != '\u007f' }.trim().ifEmpty { DEFAULT_NAME }

        /** Why [name] (cleaned) cannot be a device name, or null (fix round 1, ruling 2.3 (7)). */
        fun nameRefusal(name: String): String? =
            if (name.length > NAME_MAX) "a device name holds at most $NAME_MAX characters; this one has ${name.length}" else null

        fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

        fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}
