package wm.damage.core

import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wm.damage.core.app.AppHost
import wm.damage.core.app.AppNet
import wm.damage.core.app.AppWire
import wm.damage.core.app.Change
import wm.damage.core.app.DevicesCore
import wm.damage.core.app.Effect
import wm.damage.core.app.Item
import wm.damage.core.app.Levels
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.app.Record
import wm.damage.core.net.ReachGate
import wm.damage.core.net.Side
import wm.damage.core.web.Access
import wm.damage.core.web.BlobSources
import wm.damage.core.web.Bundle
import wm.damage.core.web.Devices
import wm.damage.core.web.Uploads
import wm.damage.core.web.WebServer
import wm.damage.core.windows.files.FilesBlobs
import wm.damage.core.windows.files.FilesCore
import wm.damage.core.windows.files.FilesJobs
import wm.damage.core.windows.files.FilesPc
import wm.damage.core.windows.files.FilesScopes
import wm.damage.core.windows.files.LocalFilesProvider

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q5 (the spec's §4.1 "Levels" and "Blobs", §2.2, §2.4; `CONTRACT.md` §9.4, §10.4): Files' scopes,
 * blobs and uploads on the PC. A folder is served a page at a time, sorted and filtered by the shared rows; a sort
 * change describes the pages again and a job's end moves the version of a folder it changed; the trash, a text chunk
 * and a PDF's facts; and, against a real web listener on loopback port 0, Files is full as a whole (below it nothing
 * is served — scopes, ops and blobs alike), a file streams with Range and `?dl=1`, its renditions are made on the PC,
 * and an upload that lands and one that is cut off each end the job that holds the slot. Every file is under the
 * test's own temp root; the uploads' folder is in it.
 */
class FilesScopesTest {
    private val tree = FilesTree()
    private val closing = ArrayList<AutoCloseable>()

    @AfterTest
    fun tearDown() {
        for (c in closing.asReversed()) try { c.close() } catch (e: Exception) { /* the next closes all the same */ }
        tree.close()
    }

    private fun host(): AppHost = filesRecord(tree.root.resolve("record-${closing.size}")).also { h ->
        closing += AutoCloseable { h.close(); h.executor.shutdown() }
    }

    private fun pc(h: AppHost = host(), uploads: Uploads? = null) =
        FilesPc(h, tree.files, tree.books.toString(), uploads) { bytes, px -> "jpeg:$px:${bytes.size}".toByteArray() }

    private var n = 0L

    private fun row(h: AppHost, action: String, target: String, value: JsonPrimitive) {
        val got = CopyOnWriteArrayList<wm.damage.core.app.Answer>()
        h.act(Op("web-q5", OpId.Queued("q5", ++n), FilesCore.WINDOW, action, listOf(target), value, at = System.currentTimeMillis())) { got += it }
        h.executor.call {}
        assertTrue(got.single() is wm.damage.core.app.Answer.Seen, "${got.single()}")
    }

    private fun names(page: JsonObject) = (page["rows"] as JsonArray).map { it.jsonObject["name"]!!.jsonPrimitive.content }

    @Test
    fun aFolderIsServedAPageAtATime_sortedAndFilteredByTheSharedRows() {
        val h = host()
        val pc = pc(h)
        for (i in 0 until 1_203) tree.file("many/f${"%04d".format(i)}.txt", "x".repeat(i % 7))
        tree.dir("many/sub")
        tree.file("many/.hidden", "h")
        val dir = tree.home.resolve("many").toString()
        val p0 = pc.scopes.describe(FilesScopes.folderScope(dir, 0))!!
        assertEquals(1_204, p0["count"]!!.jsonPrimitive.content.toInt(), "the dotfile is left out while hidden is off")
        assertEquals(500, p0["size"]!!.jsonPrimitive.content.toInt())
        assertEquals(true, p0["more"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("sub", names(p0).first(), "folders first")
        assertEquals(listOf("id", "name", "dir", "size", "mtime", "inline", "thumb"), (p0["rows"] as JsonArray)[1].jsonObject.keys.toList())
        val p2 = pc.scopes.describe(FilesScopes.folderScope(dir, 2))!!
        assertEquals(1_000L, p2["from"]!!.jsonPrimitive.content.toLong())
        assertEquals(204, p2["size"]!!.jsonPrimitive.content.toInt())
        assertEquals(false, p2["more"]!!.jsonPrimitive.content.toBoolean())
        // the shared rows decide: size first, then hidden on
        row(h, "sort", "sort", JsonPrimitive("size"))
        row(h, "hidden", "hidden", JsonPrimitive(true))
        eventually("the core's source holds the rows") { FilesCore.hiddenOf(pc.coreSource.state()) == true }
        val again = pc.scopes.describe(FilesScopes.folderScope(dir, 0))!!
        assertEquals(1_205, again["count"]!!.jsonPrimitive.content.toInt())
        assertEquals("f0006.txt", names(again)[1], "the biggest files after the folder, ties by name: ${names(again).take(3)}")
        assertTrue(again["version"]!!.jsonPrimitive.content.toLong() > p0["version"]!!.jsonPrimitive.content.toLong(),
            "the listing read differently: its version moved")
        // a page of a book under the books folder names its Reader id
        Files.writeString(tree.books.resolve("A Made-up Book.epub"), "not really an epub")
        val books = pc.scopes.describe(FilesScopes.folderScope(tree.books.toString(), 0))!!
        assertNotNull((books["rows"] as JsonArray).single().jsonObject["reader"])
    }

    @Test
    fun aSortChangeFollowsEveryHeldPage_andAJobsEndMovesTheVersionOfAFolderItChanged() {
        val h = host()
        val pc = pc(h)
        val a = tree.file("w/a.txt")
        tree.file("w/b.txt")
        val dir = tree.home.resolve("w").toString()
        val scope = FilesScopes.folderScope(dir, 0)
        val sortKey = Record.key(FilesCore.WINDOW, FilesCore.SORT)
        val change = Change(1, 1, Effect(mapOf(sortKey to Item(JsonPrimitive("size"), "web", 1))))
        assertTrue(pc.scopes.follows(scope, change))
        assertTrue(pc.scopes.follows("desc/files", change))
        assertFalse(pc.scopes.follows("files/text:$a/at:0", change))
        val v0 = pc.scopes.describe(scope)!!["version"]!!.jsonPrimitive.content.toLong()
        assertEquals(v0, pc.scopes.version(scope))
        val fired = java.util.concurrent.atomic.AtomicInteger()
        pc.scopes.addListener { fired.incrementAndGet() }
        pc.slotted.trash(a)
        eventually("the folder was read again after the job and its version moved") { (pc.scopes.version(scope) ?: 0) > v0 }
        eventually("the lane was told") { fired.get() >= 2 }
        assertEquals(listOf("b.txt"), names(pc.scopes.describe(scope)!!))
    }

    @Test
    fun theTrashATextChunkAndAPdfsFacts() {
        val pc = pc()
        val t = tree.file("t/notes.txt", "line one\nline two\n")
        pc.slotted.trash(tree.file("t/gone.txt"))
        val trash = (pc.scopes.describe("files/trash")!!["rows"] as JsonArray).single().jsonObject
        assertEquals(setOf("id", "name", "origPath", "dir", "size", "at"), trash.keys)
        assertEquals("gone.txt", trash["name"]!!.jsonPrimitive.content)
        assertTrue(trash["origPath"]!!.jsonPrimitive.content.startsWith(tree.root.toString()))
        val c = pc.scopes.describe("files/text:$t/at:0")!!
        assertEquals("line one\nline two\n", c["text"]!!.jsonPrimitive.content)
        assertEquals(18L, c["bytesRead"]!!.jsonPrimitive.content.toLong())
        assertNull(pc.scopes.describe("files/text:relative/at:0"), "an address that is not an absolute path names nothing")
        // the PDF's facts through the provider's own runner (a stand-in: no program is run)
        val pdfProvider = LocalFilesProvider(tree.books, tree.trash, mountsFile = tree.root.resolve("no-mounts")) { cmd ->
            LocalFilesProvider.ExecResult(0, if (cmd[0] == "pdfinfo") "Pages: 3\n".toByteArray() else "some text".toByteArray(), "")
        }
        val pdfPc = FilesPc(host(), SafeFiles(tree.root, pdfProvider, emptyList()), tree.books.toString(), null, null)
        val pdf = pdfPc.scopes.describe("files/pdf:${tree.file("t/a.pdf")}")!!
        assertEquals(3, pdf["pages"]!!.jsonPrimitive.content.toInt())
        assertEquals(9L, pdf["textChars"]!!.jsonPrimitive.content.toLong())
    }

    // ---- against a real web listener (loopback port 0, a device table in the temp tree) ----

    private inner class Web(uploads: Uploads? = null) {
        val h = host()
        val pc = pc(h, uploads)
        val devices = Devices(tree.root.resolve("devices-${closing.size}.json")).load()
        val net = AppNet(h, devices = DevicesCore(devices)).also { pc.serveOn(it); closing += it }
        val server = WebServer(0, ReachGate(Side.PC), Access(listOf("localhost", "127.0.0.1")), devices, net,
            Bundle(mapOf("index.html" to "<!doctype html><p>fixture</p>".toByteArray())),
            BlobSources(mapOf(FilesBlobs.PREFIX to pc.blobs)), bindAddress = InetAddress.getLoopbackAddress(),
            uploads = uploads).also { it.start(); closing += AutoCloseable { it.close() } }
        val port get() = server.boundPort

        fun secret(level: String): String {
            val w = WebRig.Waiting()
            val p = devices.request("127.0.0.1", null, "made-up $level page", "browser", null, w)
            assertNull(devices.approve(p.code, level, "local"))
            return devices.redeem(w.claims.poll(10, TimeUnit.SECONDS)!!)!!.secret
        }

        /** The secret of the last page made (its uploads carry it). */
        var lastSecret = ""

        /** A page at [level], past its welcome: the page and its connection. */
        fun page(level: String): Pair<Page, Long> {
            lastSecret = secret(level)
            val p = Page(port, lastSecret).also { closing += it }
            assertEquals(101, p.upgrade.code)
            p.send(buildJsonObject {
                put("t", "app"); put("proto", AppWire.PROTO); put("stream", "q5-$level-${System.nanoTime()}")
                put("contracts", buildJsonObject { put("reader", 1); put("notices", 1); put("files", 1) })
            })
            return p to p.next("welcome")["conn"]!!.jsonPrimitive.content.toLong()
        }

        fun cookie(level: String) = mapOf("Cookie" to "${Access.COOKIE}=${secret(level)}")

        /** The last page's own cookie (an upload is its device's). */
        fun pageCookie() = mapOf("Cookie" to "${Access.COOKIE}=$lastSecret")
    }

    private fun Page.answer(scope: String): JsonObject {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            val f = frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (f["scope"]?.jsonPrimitive?.content == scope) return f
        }
        throw AssertionError("no answer for $scope within 10 s")
    }

    private fun Page.answerTo(conn: Long, k: Long): JsonObject {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            val f = frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (AppWire.type(f) == "ans" && f["conn"]?.jsonPrimitive?.content == "$conn" && f["k"]?.jsonPrimitive?.content == "$k")
                return f["answer"]!!.jsonObject
        }
        throw AssertionError("no answer to ($conn, $k) within 10 s")
    }

    private fun programOp(conn: Long, k: Long, action: String, targets: List<String>, params: JsonObject? = null) = buildJsonObject {
        put("t", "op")
        put("op", buildJsonObject {
            put("device", ""); put("conn", conn); put("k", k); put("window", FilesCore.WINDOW); put("action", action)
            put("targets", buildJsonArray { for (t in targets) add(JsonPrimitive(t)) }); put("result", JsonNull)
            put("expect", buildJsonObject { put("any", true) })
            params?.let { put("params", it) }
        })
    }

    /** Pin (the spec's Q5: "below full anything is served"): Files' scopes, ops and blobs need level full. */
    @Test
    fun belowLevelFullNothingOfFilesIsServed() {
        val web = Web()
        val f = tree.file("seen.txt", "made-up bytes")
        val dir = tree.home.toString()
        val (low, lowConn) = web.page(Levels.CONTROL)
        for (scope in listOf("desc/files", FilesScopes.folderScope(dir, 0), "files/trash", "files/text:$f/at:0")) {
            low.send(AppWire.sub(scope, null))
            val a = low.answer(scope)
            assertEquals("ans", AppWire.type(a), "$scope is refused below full: $a")
            assertTrue(a["answer"]!!.jsonObject["code"]!!.jsonPrimitive.content in setOf("level", "contract"), "$a")
        }
        low.send(programOp(lowConn, 1, FilesCore.TRASH, listOf(f)))
        assertEquals("refused", low.answerTo(lowConn, 1)["a"]!!.jsonPrimitive.content)
        assertTrue(Files.exists(Path(f)), "nothing was trashed")
        assertEquals(403, http(web.port, "GET", "/blob/${FilesBlobs.idOf(f)}", web.cookie(Levels.CONTROL)).code)
        // at full, all of it
        val (full, _) = web.page(Levels.FULL)
        full.send(AppWire.sub("desc/files", null))
        assertEquals("snap", AppWire.type(full.answer("desc/files")))
        full.send(AppWire.sub(FilesScopes.folderScope(dir, 0), null))
        val page = full.answer(FilesScopes.folderScope(dir, 0))
        assertEquals(listOf("seen.txt"), names(page["desc"]!!.jsonObject))
        val got = http(web.port, "GET", "/blob/${FilesBlobs.idOf(f)}", web.cookie(Levels.FULL))
        assertEquals(200, got.code)
        assertEquals("made-up bytes", got.text)
    }

    @Test
    fun aFileStreamsWithRangeAndDownload_andItsRenditionsAreMadeOnThePc() {
        val web = Web()
        val f = tree.file("pic.png", "0123456789")
        val c = web.cookie(Levels.FULL)
        val part = http(web.port, "GET", "/blob/${FilesBlobs.idOf(f)}", c + ("Range" to "bytes=2-5"))
        assertEquals(206, part.code)
        assertEquals("2345", part.text)
        assertNull(part.header("content-disposition"), "a picture is inline")
        val dl = http(web.port, "GET", "/blob/${FilesBlobs.idOf(f)}?dl=1", c)
        assertTrue(dl.header("content-disposition")!!.startsWith("attachment"), "?dl=1 is a download")
        val thumb = http(web.port, "GET", "/blob/${FilesBlobs.idOf(f)}?r=thumb%3A320", c)
        assertEquals(200, thumb.code)
        assertEquals("image/jpeg", thumb.header("content-type"))
        assertEquals("jpeg:320:10", thumb.text, "the injected scaler made it")
        assertEquals(404, http(web.port, "GET", "/blob/${FilesBlobs.idOf(f)}?r=thumb%3A9999", c).code)
        assertEquals(404, http(web.port, "GET", "/blob/${FilesBlobs.idOf(tree.home.toString())}", c).code, "a folder is no blob")
        assertEquals(tree.home.resolve("pic.png").toString(), FilesBlobs.pathOf(FilesBlobs.idOf(f)))
    }

    /** An upload that lands and one that is cut off: each ends the job that held the slot from its op. */
    @Test
    fun anUploadHoldsTheSlotFromItsOpToTheEndOfItsBody() {
        val uploads = Uploads(null)
        val web = Web(uploads)
        val (page, conn) = web.page(Levels.FULL)
        val dir = tree.dir("in")
        page.send(programOp(conn, 1, FilesCore.UPLOAD, listOf(dir), buildJsonObject {
            put("file", buildJsonObject { put("name", "made-up.bin"); put("size", 8) })
        }))
        val a = page.answerTo(conn, 1)
        assertEquals("sent", a["a"]!!.jsonPrimitive.content, "$a")
        val id = a["result"]!!.jsonObject["upload"]!!.jsonPrimitive.content
        val job = a["result"]!!.jsonObject["job"]!!.jsonPrimitive.content
        assertTrue(web.pc.jobs.current()!!.running, "the slot is held from the op")
        page.send(programOp(conn, 2, FilesCore.MKDIR, listOf(dir), buildJsonObject { put("name", "x") }))
        assertTrue(page.answerTo(conn, 2)["reason"]!!.jsonPrimitive.content.startsWith("busy — one operation at a time"))
        val put = http(web.port, "PUT", "/blob/upload/$id", web.pageCookie(), "12345678".toByteArray())
        assertEquals(201, put.code, put.text)
        eventually("the upload's job ended") { web.pc.jobs.current()?.let { it.id == job && it.state == FilesJobs.DONE } == true }
        assertEquals("12345678", Files.readString(tree.home.resolve("in").resolve("made-up.bin")))
        // a second one, its body cut off: nothing under the name, the job failed, the slot free
        page.send(programOp(conn, 3, FilesCore.UPLOAD, listOf(dir), buildJsonObject {
            put("file", buildJsonObject { put("name", "cut.bin"); put("size", 1_000) })
        }))
        val b = page.answerTo(conn, 3)["result"]!!.jsonObject
        java.net.Socket(InetAddress.getLoopbackAddress(), web.port).use { s ->
            s.soTimeout = 10_000                                   // a test's own bound on a stalled answer
            s.getOutputStream().write(("PUT /blob/upload/${b["upload"]!!.jsonPrimitive.content} HTTP/1.1\r\nHost: 127.0.0.1:${web.port}\r\n" +
                "Cookie: ${web.pageCookie().values.single()}\r\nContent-Length: 1000\r\n\r\n0123").toByteArray())
            s.getOutputStream().flush()
            s.shutdownOutput()
            try { s.getInputStream().readBytes() } catch (e: java.net.SocketTimeoutException) { /* the answer came; the socket stayed */ }
        }
        eventually("the cut-off upload's job failed") {
            web.pc.jobs.current()?.let { it.id == b["job"]!!.jsonPrimitive.content && it.state == FilesJobs.FAILED } == true
        }
        assertFalse(Files.exists(tree.home.resolve("in").resolve("cut.bin")))
        web.pc.slotted.mkdir(dir, "free again")
    }

    private fun Path(s: String) = java.nio.file.Path.of(s)
}
