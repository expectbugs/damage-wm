package wm.damage.core.windows.files

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import wm.damage.core.gfx.Gray8
import wm.damage.core.net.RemoteWin
import wm.damage.core.net.WinService

/**
 * The Files provider over the §16.10 window channel: [FilesService] adapts a
 * host-side [FilesProvider] (Local) to the wire; [RemoteFilesProvider] is the
 * phone side. Text/bytes/thumbnails/pages ride as raw BLOBS — never base64.
 */
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

class FilesService(private val p: FilesProvider) : WinService {

    override fun request(op: String, args: JsonObject): WinService.Answer {
        fun s(k: String): String = args[k]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("missing '$k'")
        fun i(k: String, d: Int) = args[k]?.jsonPrimitive?.intOrNull ?: d
        fun l(k: String, d: Long) = args[k]?.jsonPrimitive?.longOrNull ?: d
        return when (op) {
            "locations" -> WinService.Answer(buildJsonObject {
                put("locs", json.encodeToJsonElement(ListSerializer(FLocation.serializer()), p.locations()))
            })
            "list" -> WinService.Answer(buildJsonObject {
                put("entries", json.encodeToJsonElement(ListSerializer(FEntry.serializer()),
                    p.list(s("dir"), args["hidden"]?.jsonPrimitive?.booleanOrNull ?: false)))
            })
            "stat" -> WinService.Answer(json.encodeToJsonElement(FStat.serializer(), p.stat(s("path"))).jsonObject)
            "du" -> WinService.Answer(buildJsonObject { put("bytes", p.du(s("path"))) })
            "text" -> {
                val c = p.readText(s("path"), l("off", 0), i("max", 256 * 1024))
                WinService.Answer(buildJsonObject { put("more", c.more); put("total", c.totalBytes) },
                    c.text.toByteArray(Charsets.UTF_8))
            }
            "bytes" -> WinService.Answer(blob = p.readBytes(s("path"), i("max", 24 shl 20)))
            "thumb" -> {
                val t = p.thumb(s("path"), i("size", 56))
                if (t == null) WinService.Answer(buildJsonObject { put("w", 0); put("h", 0) })
                else WinService.Answer(buildJsonObject { put("w", t.w); put("h", t.h) }, t.pix)
            }
            "pdfinfo" -> WinService.Answer(json.encodeToJsonElement(PdfInfo.serializer(), p.pdfInfo(s("path"))).jsonObject)
            "pdftext" -> WinService.Answer(blob = p.pdfText(s("path")).toByteArray(Charsets.UTF_8))
            "pdfpage" -> WinService.Answer(blob = p.pdfPage(s("path"), i("page", 1), i("width", 1216)))
            "trash" -> WinService.Answer(buildJsonObject { put("id", p.trash(s("path"))) })
            "trashlist" -> WinService.Answer(buildJsonObject {
                put("entries", json.encodeToJsonElement(ListSerializer(FTrashEntry.serializer()), p.trashList()))
            })
            "restore" -> WinService.Answer(buildJsonObject { put("path", p.restore(s("id"))) })
            "purge" -> { p.purge(s("id")); WinService.Answer() }
            "rename" -> WinService.Answer(buildJsonObject { put("path", p.rename(s("path"), s("name"))) })
            "mkdir" -> WinService.Answer(buildJsonObject { put("path", p.mkdir(s("dir"), s("name"))) })
            "copy" -> WinService.Answer(buildJsonObject { put("path", p.copy(s("src"), s("dest"))) })
            "move" -> WinService.Answer(buildJsonObject { put("path", p.move(s("src"), s("dest"))) })
            "open" -> { p.openOnPc(s("path")); WinService.Answer() }
            else -> throw IllegalArgumentException("unknown files op '$op'")
        }
    }
}

class RemoteFilesProvider(
    host: String, port: Int, token: String, scope: CoroutineScope,
    private val onState: (String) -> Unit = {},
) : FilesProvider, AutoCloseable {

    private val ch = RemoteWin(host, port, token, "files", scope, onState = onState)

    override fun stateLine(): String = ch.stateLine

    private fun args(vararg kv: Pair<String, Any?>): JsonObject = buildJsonObject {
        for ((k, v) in kv) when (v) {
            null -> {}
            is String -> put(k, v)
            is Int -> put(k, v)
            is Long -> put(k, v)
            is Boolean -> put(k, v)
            else -> throw IllegalArgumentException("arg $k: ${v::class}")
        }
    }

    override fun locations(): List<FLocation> =
        json.decodeFromJsonElement(ListSerializer(FLocation.serializer()),
            ch.request("locations").data["locs"]!!.jsonArray)

    override fun list(dir: String, showHidden: Boolean): List<FEntry> =
        json.decodeFromJsonElement(ListSerializer(FEntry.serializer()),
            ch.request("list", args("dir" to dir, "hidden" to showHidden)).data["entries"]!!.jsonArray)

    override fun stat(path: String): FStat =
        json.decodeFromJsonElement(FStat.serializer(), ch.request("stat", args("path" to path)).data)

    override fun du(path: String): Long =
        ch.request("du", args("path" to path)).data["bytes"]!!.jsonPrimitive.long

    override fun readText(path: String, offset: Long, maxBytes: Int): TextChunk {
        val a = ch.request("text", args("path" to path, "off" to offset, "max" to maxBytes))
        return TextChunk(
            (a.blob ?: ByteArray(0)).toString(Charsets.UTF_8),
            a.data["more"]?.jsonPrimitive?.booleanOrNull ?: false,
            a.data["total"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }

    override fun readBytes(path: String, maxBytes: Int): ByteArray =
        ch.request("bytes", args("path" to path, "max" to maxBytes)).blob
            ?: throw IllegalStateException("no bytes came back for $path")

    override fun thumb(path: String, sizePx: Int): Gray8? {
        val a = ch.request("thumb", args("path" to path, "size" to sizePx))
        val w = a.data["w"]?.jsonPrimitive?.intOrNull ?: 0
        val h = a.data["h"]?.jsonPrimitive?.intOrNull ?: 0
        val b = a.blob ?: return null
        if (w <= 0 || h <= 0 || b.size != w * h) return null
        val g = Gray8(w, h)
        System.arraycopy(b, 0, g.pix, 0, b.size)
        return g
    }

    override fun pdfInfo(path: String): PdfInfo =
        json.decodeFromJsonElement(PdfInfo.serializer(), ch.request("pdfinfo", args("path" to path)).data)

    override fun pdfText(path: String): String =
        (ch.request("pdftext", args("path" to path)).blob ?: ByteArray(0)).toString(Charsets.UTF_8)

    override fun pdfPage(path: String, page: Int, widthPx: Int): ByteArray =
        ch.request("pdfpage", args("path" to path, "page" to page, "width" to widthPx)).blob
            ?: throw IllegalStateException("no page raster came back")

    override fun trash(path: String): String =
        ch.request("trash", args("path" to path)).data["id"]!!.jsonPrimitive.contentOrNull!!

    override fun trashList(): List<FTrashEntry> =
        json.decodeFromJsonElement(ListSerializer(FTrashEntry.serializer()),
            ch.request("trashlist").data["entries"]!!.jsonArray)

    override fun restore(id: String): String =
        ch.request("restore", args("id" to id)).data["path"]!!.jsonPrimitive.contentOrNull!!

    override fun purge(id: String) { ch.request("purge", args("id" to id)) }

    override fun rename(path: String, newName: String): String =
        ch.request("rename", args("path" to path, "name" to newName)).data["path"]!!.jsonPrimitive.contentOrNull!!

    override fun mkdir(dir: String, name: String): String =
        ch.request("mkdir", args("dir" to dir, "name" to name)).data["path"]!!.jsonPrimitive.contentOrNull!!

    override fun copy(src: String, destDir: String): String =
        ch.request("copy", args("src" to src, "dest" to destDir)).data["path"]!!.jsonPrimitive.contentOrNull!!

    override fun move(src: String, destDir: String): String =
        ch.request("move", args("src" to src, "dest" to destDir)).data["path"]!!.jsonPrimitive.contentOrNull!!

    override fun openOnPc(path: String) { ch.request("open", args("path" to path)) }

    override fun close() = ch.close()
}
