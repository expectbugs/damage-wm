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
 * and [tryApplyRemote] applies a peer's record under strict LWW. On-disk
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
                // not be wiped by the disk image. The strictly-newer copy wins
                // per key, exactly LWW's rule.
                val merged = HashMap<String, Rec>(fromDisk)
                for ((k, r) in loaded) {
                    val d = merged[k]
                    if (d == null || r.stamp > d.stamp) merged[k] = r
                }
                loaded = merged
                true
            } catch (e: Exception) {
                // A corrupt store must not stop boot — but it must be LOUD, and the
                // bad file is kept for post-mortem rather than deleted.
                Log.e("persist", "state store unreadable — starting fresh, kept as .bad", e)
                try {
                    Files.move(file, file.resolveSibling(file.fileName.toString() + ".bad"),
                        StandardCopyOption.REPLACE_EXISTING)
                } catch (m: Exception) {
                    Log.e("persist", "could not preserve corrupt store", m)
                }
                loaded = HashMap()
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

    /** The stamp of [key], 0 when absent — the sync handshake's currency. */
    fun stamp(key: String): Long = synchronized(lock) { loaded[key]?.stamp ?: 0L }

    fun stamps(): Map<String, Long> = synchronized(lock) {
        loaded.entries.associate { it.key to it.value.stamp }
    }

    fun record(key: String): Pair<JsonObject, Long>? = synchronized(lock) {
        loaded[key]?.let { it.value to it.stamp }
    }

    /**
     * A peer's record under strict last-write-wins (§19.2). Strictly newer →
     * stored SILENTLY (no listener — an applied record must not echo back) and
     * saved (the standby PC has no shell calling save()). An equal value with
     * a different stamp adopts the higher stamp silently, so the two sides'
     * handshakes converge. Anything else is refused. Returns whether the VALUE
     * was applied.
     */
    fun tryApplyRemote(key: String, value: JsonObject, stamp: Long): Boolean {
        val applied = synchronized(lock) {
            val clamped = minOf(stamp, System.currentTimeMillis() + FUTURE_SLOP_MS)
            val old = loaded[key]
            when {
                // an equal value is never "applied" whatever its stamp — a live
                // re-apply of what is already shown would only cost a repaint
                old != null && old.value == value -> { old.stamp = maxOf(old.stamp, clamped); false }
                old == null || clamped > old.stamp -> { loaded[key] = Rec(value, clamped); true }
                else -> return false
            }
        }
        save()
        return applied
    }

    /** [l] fires with the key after every LOCAL value change (never for an
     *  applied remote record). Called on the writer's thread — keep it cheap. */
    fun addListener(l: (String) -> Unit) { listeners.add(l) }
    fun removeListener(l: (String) -> Unit) { listeners.remove(l) }

    /** Atomic write: temp file + move, so an interruption never half-writes the
     *  store. Synchronized + unique tmp: two savers must never interleave. */
    fun save() {
        synchronized(lock) {
            try {
                file.parent?.let { Files.createDirectories(it) }
                val root = buildJsonObject {
                    put("__v", 2L)
                    put("records", buildJsonObject {
                        for ((k, r) in loaded) put(k, buildJsonObject { put("v", r.value); put("t", r.stamp) })
                    })
                }
                val tmp = file.resolveSibling(file.fileName.toString() + ".${System.nanoTime()}.tmp")
                Files.writeString(tmp, json.encodeToString(JsonObject.serializer(), root))
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } finally {
                    Files.deleteIfExists(tmp)
                }
            } catch (e: Exception) {
                Log.e("persist", "state save FAILED — state will not survive restart", e)
            }
        }
    }

    companion object {
        /** A peer whose clock runs ahead must not permanently win every key:
         *  incoming stamps are clamped to now + this. */
        const val FUTURE_SLOP_MS = 60_000L

        /** Which keys travel between PC and phone (§19.2): settings and the
         *  window blobs. `shell.state` (focused window, mode, cursor, notices)
         *  is per-device UI and never syncs. */
        fun syncable(key: String): Boolean =
            key == "shell.settings" || key.startsWith("window.")
    }
}
