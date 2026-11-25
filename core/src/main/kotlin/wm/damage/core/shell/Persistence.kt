package wm.damage.core.shell

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import wm.damage.core.util.Log

/**
 * Full persistence, WM-owned and enforced — DESIGN.md §9.1, Adam's strongest
 * stated requirement. Windows declare state blobs; the SHELL saves and restores
 * them — not per-app opt-in — and the store survives WM restart (disk-backed,
 * atomic replace). §9.1 #4's regression gate lives in ShellPersistenceGateTest:
 * switch away, switch back, assert the composed frame is byte-identical.
 *
 * Since S1's close (S1-5, `CONTRACT.md` §2) this is the device's own view store — cursors, levels, local caches and
 * the shell's copy of its settings — and nothing in it travels between devices: shared state is the PC's record. On
 * disk `{"__v":2,"records":{key:{"v":…,"t":…}}}`, the shape every build since 2026-08-31 writes; `t` is when the value
 * last changed on this device, written and read by nothing.
 */
class Persistence(private val file: Path) {
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    private class Rec(val value: JsonObject, val t: Long)

    private val lock = Any()
    private var loaded: MutableMap<String, Rec> = HashMap()
    /** The first [load]'s verdict; null until it ran. */
    private var loadVerdict: Boolean? = null
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    /**
     * Reads the file into memory, at the first call only: the hosts load at their start, before anything writes. A
     * later call (the shell loads at every session start, over the same store) keeps memory — it holds every write,
     * landed on disk or not — and answers the first call's verdict. No file: false, memory empty. A file that does not
     * parse is kept beside it as `.bad`, loudly, and memory starts empty; the next save writes a new file.
     */
    fun load(): Boolean = synchronized(lock) {
        loadVerdict ?: readFile().also { loadVerdict = it }
    }

    /** The first [load]: the file's records into memory. Under the lock. */
    private fun readFile(): Boolean {
        val verdict = if (!Files.exists(file)) false else try {
            val root = json.parseToJsonElement(Files.readString(file)).jsonObject
            check(root["__v"]?.jsonPrimitive?.longOrNull == 2L) { "not a version-2 state store" }
            val recs = root["records"]?.jsonObject ?: JsonObject(emptyMap())
            loaded = recs.mapValuesTo(HashMap()) { (_, e) ->
                val o = e.jsonObject
                Rec(o["v"]!!.jsonObject, o["t"]?.jsonPrimitive?.longOrNull ?: 0L)
            }
            true
        } catch (e: Exception) {
            // an unreadable store must not stop boot — but it must be LOUD, and the bad file is kept for post-mortem
            Log.e("persist", "state store unreadable — kept as .bad; this start begins from defaults", e)
            try {
                Files.move(file, file.resolveSibling(file.fileName.toString() + ".bad"),
                    StandardCopyOption.REPLACE_EXISTING)
            } catch (m: Exception) {
                Log.e("persist", "could not preserve corrupt store", m)
            }
            false
        }
        return verdict
    }

    fun get(key: String): JsonObject? = synchronized(lock) { loaded[key]?.value }

    /** Keys currently held that start with [prefix] — sub-record enumeration
     *  (`window.<id>.<subKey>`, §16.4a). */
    fun keysWithPrefix(prefix: String): List<String> =
        synchronized(lock) { loaded.keys.filter { it.startsWith(prefix) } }

    /** Local write. Notifies ONLY when the value changed. */
    fun put(key: String, state: JsonObject) {
        val changed = synchronized(lock) {
            val old = loaded[key]
            if (old != null && old.value == state) return@synchronized false
            loaded[key] = Rec(state, System.currentTimeMillis())
            true
        }
        if (changed) notify(key)
    }

    /** Removes [key] (a sub-record its window no longer holds, S1-5 §4.8); notifies when it was held. */
    fun remove(key: String) {
        val removed = synchronized(lock) { loaded.remove(key) != null }
        if (removed) notify(key)
    }

    private fun notify(key: String) {
        for (l in listeners) try { l(key) } catch (e: Exception) {
            Log.e("persist", "change listener for '$key' failed", e)
        }
    }

    /** [l] fires with the key after every value change and removal. Called on the writer's thread — keep it cheap. */
    fun addListener(l: (String) -> Unit) { listeners.add(l) }
    fun removeListener(l: (String) -> Unit) { listeners.remove(l) }

    /** Every write goes through ONE thread in submission order, so an
     *  asynchronous save and a later synchronous one land in order per
     *  submitting thread; every submitter today is the loop (2026-09-05,
     *  `HANDOFF.md` §32). Daemon: the synchronous shutdown save is
     *  what guarantees the last state reaches disk, and it waits its turn. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "damage-persist").apply { isDaemon = true }
    }

    /** P6 item 3 (the loop-cost sweep's LC-6, step 2): every record as it stands — the key, the value (immutable) and
     *  its time — taken under the lock in one pass. */
    private fun copyRecords(): List<Triple<String, JsonObject, Long>> = synchronized(lock) {
        loaded.map { (k, r) -> Triple(k, r.value, r.t) }
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
        writeAtomic(file, encoded)
        true
    } catch (e: Exception) {
        Log.e("persist", "state save FAILED — state will not survive restart", e)
        false
    }

    /** SYNCHRONOUS save: encodes now, writes on the writer thread after every
     *  save queued before it, and returns whether the write LANDED (R3s#11) —
     *  a full disk repeating only in the log would surface at the next
     *  restart as lost state; saveAll raises it visibly once per failure
     *  streak. The shutdown path uses this form.
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
        /** The one atomic write of a whole file: a unique temp file beside [file], moved over it in one step, so an
         *  interruption never leaves half a file under the real name. [sync] forces the temp file's bytes to disk
         *  before the move (the PC record's saver, S1-1 P1: a log set aside after a save must not outlive a
         *  record.json a crash emptied). Throws what the write throws; the temp file never outlives the call. */
        fun writeAtomic(file: Path, text: String, sync: Boolean = false) {
            file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            val tmp = file.resolveSibling(file.fileName.toString() + ".${System.nanoTime()}.tmp")
            try {
                if (sync) {
                    java.nio.channels.FileChannel.open(tmp, java.nio.file.StandardOpenOption.CREATE_NEW,
                        java.nio.file.StandardOpenOption.WRITE).use { ch ->
                        val buf = java.nio.ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
                        while (buf.hasRemaining()) ch.write(buf)
                        ch.force(true)
                    }
                } else {
                    Files.writeString(tmp, text)
                }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(tmp)
            }
        }
    }
}
