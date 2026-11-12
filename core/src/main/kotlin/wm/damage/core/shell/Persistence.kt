package wm.damage.core.shell

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import wm.damage.core.util.Log

/**
 * Full persistence, WM-owned and enforced — DESIGN.md §9.1, Adam's strongest
 * stated requirement. Windows declare state blobs; the SHELL saves and restores
 * them — not per-app opt-in — and the store survives WM restart (disk-backed,
 * atomic replace). §9.1 #4's regression gate lives in ShellPersistenceGateTest:
 * switch away, switch back, assert the composed frame is byte-identical.
 *
 * Since 2026-08-31 (HANDOFF.md §19) the store is also the SYNC substrate:
 * every key carries a millisecond stamp, re-stamped ONLY when the value
 * actually changes (saveAll rewrites every key every tick — stamping those
 * would degenerate last-write-wins into whoever-saved-last-wins-everything),
 * and [tryApplyRemote] applies a peer's record: a different value by strict
 * stamp order, an equal value as the same record unless its stamp is newer
 * by more than [STAMP_TOLERANCE_MS] (HANDOFF §75.1; the table is there). On-disk
 * schema v2 `{"__v":2,"records":{key:{"v":…,"t":…}}}`; a legacy file (a plain
 * key→blob map) migrates on load with every stamp = the file's mtime.
 */
class Persistence(private val file: Path) {
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    private class Rec(val value: JsonObject, var stamp: Long)

    private val lock = Any()
    private var loaded: MutableMap<String, Rec> = HashMap()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    fun load(): Boolean {
        synchronized(lock) {
            if (!Files.exists(file)) return false
            return try {
                val root = json.parseToJsonElement(Files.readString(file)).jsonObject
                val fromDisk: Map<String, Rec> = if (root["__v"]?.jsonPrimitive?.long == 2L) {
                    val recs = root["records"]?.jsonObject ?: JsonObject(emptyMap())
                    recs.mapValues { (_, e) ->
                        val o = e.jsonObject
                        Rec(o["v"]!!.jsonObject, o["t"]!!.jsonPrimitive.long)
                    }
                } else {
                    // legacy map: every value's honest "last modified" is the file's
                    val mtime = try { Files.getLastModifiedTime(file).toMillis() } catch (e: Exception) { 0L }
                    Log.i("persist", "migrating legacy state store (${root.size} keys, stamp=$mtime)")
                    root.mapValues { Rec(it.value.jsonObject, mtime) }
                }
                // MERGE, never replace (the §19.4 startup-race closure,
                // 2026-09-01): a record applied store-direct in the moments
                // before load() runs — a sync peer racing a shell start — must
                // not be overwritten by the disk image. The strictly-newer copy wins
                // per key, exactly LWW's rule. Strict on purpose, with no tolerance
                // (0.64 P3): both sides here are THIS store's own states, not a peer's
                // shifted copy. Every shell start loads again with the sync channel
                // already up (Shell.start), and that is safe for the same reason: an
                // apply that changes a record in memory also saves it, and an apply
                // of a record the store already holds changes neither memory nor disk.
                val merged = HashMap<String, Rec>(fromDisk)
                for ((k, r) in loaded) {
                    val d = merged[k]
                    if (d == null || r.stamp > d.stamp) merged[k] = r
                }
                loaded = merged
                true
            } catch (e: Exception) {
                // An unreadable store must not stop boot — but it must be LOUD, and the
                // bad file is kept for post-mortem rather than deleted. The
                // IN-MEMORY records stay: they are exactly what the merge
                // exists to protect (a pre-load sync apply must survive this
                // path too — review 2026-09-01 F12).
                Log.e("persist", "state store unreadable — kept as .bad; in-memory records retained", e)
                try {
                    Files.move(file, file.resolveSibling(file.fileName.toString() + ".bad"),
                        StandardCopyOption.REPLACE_EXISTING)
                } catch (m: Exception) {
                    Log.e("persist", "could not preserve corrupt store", m)
                }
                false
            }
        }
    }

    fun get(key: String): JsonObject? = synchronized(lock) { loaded[key]?.value }

    /** Keys currently held that start with [prefix] — sub-record enumeration
     *  (`window.<id>.<subKey>`, §16.4a). */
    fun keysWithPrefix(prefix: String): List<String> =
        synchronized(lock) { loaded.keys.filter { it.startsWith(prefix) } }

    /** Local write. Re-stamps and notifies ONLY when the value changed. */
    fun put(key: String, state: JsonObject) {
        val changed = synchronized(lock) {
            val old = loaded[key]
            if (old != null && old.value == state) return@synchronized false
            val stamp = maxOf(System.currentTimeMillis(), (old?.stamp ?: 0L) + 1)
            loaded[key] = Rec(state, stamp)
            true
        }
        if (changed) for (l in listeners) try { l(key) } catch (e: Exception) {
            Log.e("persist", "change listener for '$key' failed", e)
        }
    }

    /** Seed an ABSENT [key] at stamp 0: recorded for local restarts, but
     *  losing LWW to any real record (review 2026-09-01 R2#16 — a virgin
     *  store's first save tick stamped window DEFAULTS over the fleet's real
     *  positions). The first value-changing put() re-stamps for real, so a
     *  genuine local edit still wins normally. No listener fires — a baseline
     *  is not a local edit and must not sync-push. */
    fun putBaseline(key: String, state: JsonObject) {
        synchronized(lock) { if (loaded[key] == null) loaded[key] = Rec(state, 0L) }
    }

    /** The stamp of [key], 0 when absent — the sync handshake's currency. */
    fun stamp(key: String): Long = synchronized(lock) { loaded[key]?.stamp ?: 0L }

    fun stamps(): Map<String, Long> = synchronized(lock) {
        loaded.entries.associate { it.key to it.value.stamp }
    }

    fun record(key: String): Pair<JsonObject, Long>? = synchronized(lock) {
        loaded[key]?.let { it.value to it.stamp }
    }

    /** Value and stamp of every key [keep] accepts, from ONE locked read (the values are
     *  immutable): the sync handshake decides and sends from the same reading. */
    fun snapshot(keep: (String) -> Boolean): Map<String, Pair<JsonObject, Long>> = synchronized(lock) {
        val out = HashMap<String, Pair<JsonObject, Long>>()
        for ((k, r) in loaded) if (keep(k)) out[k] = r.value to r.stamp
        out
    }

    /** A peer's stamp as this store takes it: its clock may run ahead by [FUTURE_SLOP_MS], no more. */
    private fun clampStamp(stamp: Long) = minOf(stamp, System.currentTimeMillis() + FUTURE_SLOP_MS)

    /** The second row of [tryApplyRemote]'s table, in one place so the two callers cannot drift:
     *  [old] is the same record as the incoming one. Call under the lock. */
    private fun sameRecord(old: Rec?, value: JsonObject, clamped: Long): Boolean =
        old != null && old.value == value && !newer(clamped, old.stamp)

    /** Does the store already hold this record — the same value, and a stamp the incoming one is
     *  not newer than by more than the tolerance? [tryApplyRemote] would change nothing for it.
     *  The shell asks this on its loop before doing any work for a synced record. */
    fun holdsSame(key: String, value: JsonObject, stamp: Long): Boolean =
        synchronized(lock) { sameRecord(loaded[key], value, clampStamp(stamp)) }

    /** The keys whose refusal inside the tolerance was already said on this sync connection. */
    private val saidKept = HashSet<String>()

    /** A sync connection attached (either end): what was said for the last one may be said once
     *  more. Cleared by nothing else; it holds key names only and no decision reads it. */
    fun syncConnectionStarted() { synchronized(lock) { saidKept.clear() } }

    /**
     * A peer's record (§19.2; the tolerance is HANDOFF §75.1). `clamped` is the incoming
     * stamp, at most [FUTURE_SLOP_MS] ahead of this clock.
     *
     *     the store holds     | the incoming record               | result
     *     nothing             | anything                          | stored at clamped, saved, true
     *     an equal value      | not newer(clamped, held stamp)    | NOTHING changes, nothing is saved, false
     *     an equal value      | newer(clamped, held stamp)        | the stamp becomes clamped, saved, false
     *     a different value   | clamped > held stamp (strict)     | stored at clamped, saved, true
     *     a different value   | clamped <= held stamp             | refused, nothing saved, false
     *
     * An equal value inside the tolerance is the SAME record: adopting its stamp is what
     * made every stamp climb by one link delay per handshake (each side's skew estimate
     * errs by the one-way delay, the same sign both ways) and sent all 77 records across
     * every five minutes. A different value keeps the strict order: two edits of one key on
     * one device are routinely under two seconds apart, and the second must land. When a
     * different value is refused while its stamp is inside the tolerance, the order of the
     * two edits was not knowable from the stamps: one INFO line says ours was kept, once per
     * key per sync connection.
     *
     * A stored record is SILENT (no listener: an applied record must not echo back) and is
     * saved here (the standby PC has no shell calling save()). Returns whether the VALUE
     * was applied; an equal value never is, whatever its stamp (a live re-apply of what is
     * already shown would only cost a repaint). A pure function of (held value, held stamp,
     * incoming value, incoming stamp); runs on the caller's thread.
     */
    fun tryApplyRemote(key: String, value: JsonObject, stamp: Long): Boolean {
        var keptOverMs = -1L
        val applied = synchronized(lock) {
            val clamped = clampStamp(stamp)
            val old = loaded[key]
            when {
                old == null -> { loaded[key] = Rec(value, clamped); true }
                sameRecord(old, value, clamped) -> return false
                old.value == value -> { old.stamp = clamped; false }
                clamped > old.stamp -> { loaded[key] = Rec(value, clamped); true }
                else -> {
                    val older = old.stamp - clamped
                    if (older <= STAMP_TOLERANCE_MS && saidKept.add(key)) keptOverMs = older
                    null
                }
            }
        }
        if (applied == null) {
            // outside the lock: a log sink must never run under the store's lock
            if (keptOverMs >= 0) Log.i("sync", "'$key': the peer's record is $keptOverMs ms older than ours, " +
                "inside the tolerance — ours kept")
            return false
        }
        save()
        return applied
    }

    /** [l] fires with the key after every LOCAL value change (never for an
     *  applied remote record). Called on the writer's thread — keep it cheap. */
    fun addListener(l: (String) -> Unit) { listeners.add(l) }
    fun removeListener(l: (String) -> Unit) { listeners.remove(l) }

    /** Every write goes through ONE thread in submission order, so an
     *  asynchronous save and a later synchronous one land in order per
     *  submitting thread; every submitter today is the loop (2026-09-05,
     *  `HANDOFF.md` §32). Daemon: the sync shutdown save is
     *  what guarantees the last state reaches disk, and it waits its turn. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "damage-persist").apply { isDaemon = true }
    }

    /** P6 item 3 (the loop-cost sweep's LC-6, step 2): every record as it stands — the key, the value (immutable) and
     *  the stamp's value (`tryApplyRemote` moves a held record's stamp in place) — taken under the lock in one pass. */
    private fun copyRecords(): List<Triple<String, JsonObject, Long>> = synchronized(lock) {
        loaded.map { (k, r) -> Triple(k, r.value, r.stamp) }
    }

    /** [records] encoded as one JSON document — the one definition of the file's form. */
    private fun encode(records: List<Triple<String, JsonObject, Long>>): String {
        val root = buildJsonObject {
            put("__v", 2L)
            put("records", buildJsonObject {
                for ((k, v, t) in records) put(k, buildJsonObject { put("v", v); put("t", t) })
            })
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    /** The store encoded now, on the caller. */
    private fun encode(): String = encode(copyRecords())

    /** Atomic write: temp file + move, so an interruption never half-writes
     *  the store; a unique tmp so two writers could never interleave even if
     *  the executor were ever widened. */
    private fun writeNow(encoded: String): Boolean = try {
        file.parent?.let { Files.createDirectories(it) }
        val tmp = file.resolveSibling(file.fileName.toString() + ".${System.nanoTime()}.tmp")
        Files.writeString(tmp, encoded)
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
        true
    } catch (e: Exception) {
        Log.e("persist", "state save FAILED — state will not survive restart", e)
        false
    }

    /** SYNCHRONOUS save: encodes now, writes on the writer thread after every
     *  save queued before it, and returns whether the write LANDED (R3s#11) —
     *  a full disk repeating only in the log would surface at the next
     *  restart as lost state; saveAll raises it visibly once per failure
     *  streak. The shutdown path and the sync channel use this form.
     *  R4 item 2 (review 3A-3): the encode is under the `try` — a throw there is
     *  a save that did not land (P6's rule for [saveAsync]); outside it, the throw
     *  left the shutdown's final save as an exception. */
    fun save(): Boolean = try {
        val encoded = encode()
        try {
            writer.submit<Boolean> { writeNow(encoded) }.get()
        } catch (e: Exception) {
            Log.e("persist", "state save FAILED — the writer refused", e)
            false
        }
    } catch (e: Exception) {
        Log.e("persist", "state save FAILED — the store could not be encoded", e)
        false
    }

    /** ASYNCHRONOUS save (2026-09-05, §32): the file write on the writer thread.
     *  The loop used to block on the write itself every couple of seconds
     *  after any change: a few milliseconds on the PC, tens on the phone's
     *  flash, each one delaying the next frame. [onDone] gets the same
     *  LANDED verdict [save] returns, on the writer thread.
     *  P6 item 3 (LC-6, step 2): the ENCODE moved there too. On the caller it was the whole store (219 KB on the PC's
     *  copy, 2026-11-10) on the shell loop two seconds after every burst — the loop held about 100–150 ms there
     *  (APK 0.63's journal, measured indirectly), not the "microseconds" this comment said. The caller copies the
     *  records under the lock; the writer encodes them, in submission order (one thread). The encode sits inside the
     *  code that reports the verdict: a throw there is a save that did not land, said as one — a task of this executor
     *  that throws ends with no answer to [onDone] at all. */
    fun saveAsync(onDone: (Boolean) -> Unit) {
        val records = copyRecords()
        try {
            writer.execute {
                val landed = try { writeNow(encode(records)) } catch (t: Throwable) {
                    Log.e("persist", "state save FAILED — the store could not be encoded", t)
                    false
                }
                try { onDone(landed) } catch (e: Exception) { Log.e("persist", "save completion handler failed", e) }
            }
        } catch (e: Exception) {
            Log.e("persist", "state save FAILED — the writer refused", e)
            onDone(false)
        }
    }

    companion object {
        /** A peer whose clock runs ahead must not permanently win every key:
         *  incoming stamps are clamped to now + this. */
        const val FUTURE_SLOP_MS = 60_000L

        /** HANDOFF §75.1 (Adam's ruling; the figure is the lead's): two copies of one record
         *  whose stamps are within this are the same record. A stamp crosses the link shifted
         *  by the receiver's skew estimate, whose error is the one-way delay of the handshake
         *  that measured it (tenths of a second on 2026-11-10, measured). */
        const val STAMP_TOLERANCE_MS = 2_000L

        /** Is stamp [a] newer than [b] by more than the tolerance? A stamp of 0 is "never
         *  edited" (a baseline, an absent key): any real stamp is newer than it at once. */
        fun newer(a: Long, b: Long): Boolean = if (b <= 0L) a > 0L else a - b > STAMP_TOLERANCE_MS

        /** Which keys travel between PC and phone (§19.2): settings and the
         *  window blobs. `shell.state` (focused window, mode, cursor, notices)
         *  is per-device UI and never syncs. */
        fun syncable(key: String): Boolean =
            key == "shell.settings" || key.startsWith("window.")
    }
}
