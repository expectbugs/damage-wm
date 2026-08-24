package wm.damage.core.shell

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import wm.damage.core.util.Log

/**
 * Full persistence, WM-owned and enforced — DESIGN.md §9.1, Adam's strongest
 * stated requirement. Windows declare state blobs; the SHELL saves and restores
 * them — not per-app opt-in — and the store survives WM restart (disk-backed,
 * atomic replace). §9.1 #4's regression gate lives in ShellPersistenceTest:
 * switch away, switch back, assert the composed frame is byte-identical.
 */
class Persistence(private val file: Path) {
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private var loaded: MutableMap<String, JsonObject> = HashMap()

    fun load(): Boolean {
        if (!Files.exists(file)) return false
        return try {
            val root = json.parseToJsonElement(Files.readString(file)).jsonObject
            loaded = HashMap(root.mapValues { it.value.jsonObject })
            true
        } catch (e: Exception) {
            // A corrupt store must not brick boot — but it must be LOUD, and the
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

    fun get(key: String): JsonObject? = loaded[key]

    fun put(key: String, state: JsonObject) {
        loaded[key] = state
    }

    /** Atomic write: temp file + move, so a crash never half-writes the store.
     *  Synchronized + unique tmp: two savers must never interleave one file. */
    @Synchronized
    fun save() {
        try {
            file.parent?.let { Files.createDirectories(it) }
            val tmp = file.resolveSibling(file.fileName.toString() + ".${System.nanoTime()}.tmp")
            Files.writeString(tmp, json.encodeToString(JsonObject.serializer(), JsonObject(loaded)))
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
