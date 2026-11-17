package wm.damage.core

import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.app.AppHost
import wm.damage.core.app.AppNet
import wm.damage.core.app.DevicesCore
import wm.damage.core.app.HostBackend
import wm.damage.core.app.Levels
import wm.damage.core.app.NoticeCore
import wm.damage.core.app.Notices
import wm.damage.core.app.PcScopes
import wm.damage.core.content.LocalContent
import wm.damage.core.net.ReachGate
import wm.damage.core.net.Side
import wm.damage.core.web.Access
import wm.damage.core.web.BlobSources
import wm.damage.core.web.Bundle
import wm.damage.core.web.Devices
import wm.damage.core.web.ReaderBlobs
import wm.damage.core.web.Uploads
import wm.damage.core.web.WebServer
import wm.damage.core.windows.reader.ReaderCore
import wm.damage.core.windows.reader.ReaderDocs
import wm.damage.core.windows.reader.ReaderIndex
import wm.damage.core.windows.torrents.TorrentsCore
import wm.damage.core.windows.torrents.TorrentsScopes

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q2 (S1-2 R0 §1, "the real-server look"): TEST CODE ONLY, never a test the suite runs. The real
 * web listener, the `app` lane, the record and the PC-served scopes, assembled in process as `startWeb` assembles them,
 * over a NEW directory under the work directory given as the one argument (made-up books, a fresh record and device
 * table; nothing under `~/.damage` is read or written), on a loopback port the OS picks. One made-up device is approved
 * at full in THIS instance (its secret in a file only its user reads, for the look harness to send) and one made-up
 * page waits for approval, so the devices page has an approval to make. It prints one `LOOK {…}` line with the address,
 * then a `LOOK` line for every record change and device-table event, and waits to be ended (the stage ends its JVM).
 * Run: `core/src/test/js/look.mjs --real` against the printed address.
 * S1-2 Q3: the notice record's raiser too, and a control listener on another loopback port the OS picks (`ctl` in the
 * `LOOK` line; the look's front relays `/__ctl?<word>` to it): `raise` raises a made-up Torrents notice, `dismiss-other`
 * dismisses it as another made-up device, `state` prints what the record holds of it.
 * S1-2 Q4: Torrents over a scripted provider (`TestTorrents`: made-up transfers and tracker rows, polled every second so
 * the graphs have points; nothing reaches qBittorrent, the tracker or any site), served as the host builders serve it
 * (`TorrentsScopes` through `AppNet.serve`, its notices raised by its core); `slow` makes every tracker call wait until
 * `release` (a tracker that takes its time), `done` finishes a made-up download.
 */
fun main(args: Array<String>) {
    val work = Path.of(args.firstOrNull() ?: error("usage: LookServer WORK_DIR (a directory of the stage's own)"))
    val root = Files.createTempDirectory(Files.createDirectories(work), "look-")
    val books = Files.createDirectories(root.resolve("books"))
    val recordDir = Files.createDirectories(root.resolve("record"))
    val data = Files.createDirectories(root.resolve("data"))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // made-up books: two texts and an EPUB with chapters and a picture
    for (name in listOf("harbour notes", "ferry log")) Files.writeString(books.resolve("$name.txt"), buildString {
        appendLine(name.uppercase()); appendLine()
        for (i in 1..120) { appendLine("Paragraph $i of the $name. The tide came in over the made-up quay."); appendLine() }
    })
    val png = ByteArray(64) { (it * 7).toByte() }
    val epub = EpubFixture.write(books.resolve("the-made-up-voyage.epub"), listOf(
        "c1.xhtml" to "<h1>Departure</h1>" + (1..40).joinToString("") { "<p>Line $it of the first chapter, made up for the look.</p>" },
        "c2.xhtml" to "<h1>The Crossing</h1>" + (1..40).joinToString("") { "<p>Line $it of the second chapter, with <em>a word</em> in it.</p>" },
        "c3.xhtml" to "<h1>Arrival</h1>" + (1..40).joinToString("") { "<p>Line $it of the third chapter.</p>" }),
        images = mapOf("img/cover.png" to png), titles = listOf("Departure", "The Crossing", "Arrival"), coverMeta = "img1")
    val book = LocalContent.idFor(epub)

    val host = AppHost.inScratchDir(recordDir)
    host.pace(scope)
    val devices = Devices(data.resolve(Devices.FILE)).load()
    val core = DevicesCore(devices)
    val net = AppNet(host, devices = core)
    val reader = ReaderCore(LocalContent(books), scope, ReaderIndex(null, scope))
    val docs = ReaderDocs(reader::pathOf)
    net.attach(PcScopes(host, reader, docs,
        names = { devices.rows().filter { !it.revoked }.associate { it.id to it.name } },
        diag = { buildJsonObject {
            put("build", "the real-server look (test code)")
            put("connections", JsonArray(net.connectionLines().map { JsonPrimitive(it) }))
        } }, cards = net::cards))
    // S1-2 Q4: Torrents, as `Main.torrentsParts` serves it, over made-up transfers
    val torrents = TestTorrents()
    val kinds = listOf("uploading", "stalledUP", "downloading", "stoppedUP", "error", "stalledDL", "queuedDL", "uploading")
    torrents.transfers = torrents.transfers + (4..15).map { i ->
        val state = kinds[i % kinds.size]
        val done = state.endsWith("UP") || state == "uploading"
        torrents.t("c0ffee%02d".format(i), "Made-up transfer $i ${listOf("(2026) 1080p", "FLAC", "EPUB", "720p")[i % 4]}", state,
            if (done) 1.0 else (i * 7 % 10) / 10.0, 300_000_000L * i, dl = if (state == "downloading") 400_000L * i else 0,
            up = if (state == "uploading") 60_000L * i else 0, eta = if (state == "downloading") 600L * i else 8_640_000,
            added = 1_763_000_000L - i * 50_000, completed = if (done) 1_763_100_000L - i * 40_000 else 0, seeding = if (done) i * 40_000L else 0)
    }
    val tscopes = TorrentsScopes(host, torrents).also { it.start() }
    net.serve(TorrentsCore.WINDOW, tscopes.table, tscopes.program, tscopes::card)
    net.serve(TorrentsCore.WINDOW, tscopes.tracker)
    tscopes.watch(net.viewers) { who, f, p -> println("LOOK torrents pace $who ${if (f) "focus $p ms" else "released"}") }
    Thread({
        var k = 0
        while (true) {
            Thread.sleep(1_000)
            k++
            val w = 1.0 + 0.6 * Math.sin(k / 3.0)
            torrents.transfers = torrents.transfers.map { t ->
                if (t.dlSpeed > 0 || t.upSpeed > 0) t.copy(
                    dlSpeed = (t.dlSpeed * (0.85 + 0.3 * Math.sin(k + (t.size % 7).toDouble()))).toLong().coerceAtLeast(if (t.dlSpeed > 0) 1 else 0),
                    upSpeed = (t.upSpeed * (0.85 + 0.3 * Math.cos(k + (t.size % 5).toDouble()))).toLong().coerceAtLeast(if (t.upSpeed > 0) 1 else 0)) else t
            }
            torrents.session = torrents.session.copy(dlSpeed = (2_400_000 * w).toLong(), upSpeed = (420_000 * (2 - w)).toLong())
            torrents.poll()
        }
    }, "look-torrents-poll").apply { isDaemon = true }.start()
    val uploads = Uploads(data)
    net.addEndListener(uploads::connectionEnded)
    // S1-2 Q5: Files over a made-up tree in this run's own directory ONLY (LookFiles refuses any other path, reads
    // included): a folder of many entries, a text, a picture, a PDF, a drop folder, two entries in its own trash, and a
    // folder whose `du` waits for the control word `release` (the job slot with a running job's age)
    val look = LookTree(root.resolve("files-tree"))
    val filesPc = wm.damage.core.windows.files.FilesPc(host, look.files, look.books.toString(), uploads, ::lookThumb)
    filesPc.serveOn(net)
    look.trashTwo(filesPc.slotted)
    val server = WebServer(0, ReachGate(Side.PC), Access(Access.LOOPBACK_NAMES), devices, net, Bundle.fromResources(),
        BlobSources(mapOf(ReaderBlobs.PREFIX to ReaderBlobs(reader, docs), wm.damage.core.windows.files.FilesBlobs.PREFIX to filesPc.blobs)),
        bindAddress = InetAddress.getLoopbackAddress(), uploads = uploads)
    server.start()
    reader.refreshLibrary()
    host.addListener { c ->
        val states = c.effect.items.filterKeys { it.startsWith("rec.notices.") }.map { (k, i) ->
            Notices.parse(k.removePrefix("rec.notices."), i.value)?.let { "${it.id} occ ${it.occ} ${it.state}" } ?: k }
        println("LOOK change ${c.seq}: ${c.effect.items.keys.sorted()}" +
            if (states.isEmpty()) "" else " $states")
    }
    // S1-2 Q3: the raiser, and the made-up other device that dismisses elsewhere
    val notices = NoticeCore(host)
    tscopes.core.attachNotices(torrents, torrents.epoch, notices)
    var slow: CountDownLatch? = null
    val other = HostBackend(host, "made-up-phone", "look-phone")
    val noticeId = "torrent:c0ffee01"
    fun recordOf() = host.read(Notices.WINDOW).third.items["rec.notices.$noticeId"]?.let { Notices.parse(noticeId, it.value) }
    val ctl = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    ctl.createContext("/") { ex ->
        val word = ex.requestURI.rawQuery ?: ex.requestURI.path.trim('/')
        val said = when (word) {
            "raise" -> {
                notices.raise("torrent", "c0ffee01", "done · A made-up download (1080p)", "torrents", "t:c0ffee01", "Torrents")
                repeat(4) { host.executor.call {} }
                "raised: ${recordOf()?.let { "occ ${it.occ} ${it.state}" }}"
            }
            "dismiss-other" -> {
                val r = recordOf()
                if (r != null) host.executor.execute("look dismiss") { other.submit(Notices.WINDOW, Notices.dismissDraft(r)) }
                repeat(4) { host.executor.call {} }
                "dismissed by made-up-phone: ${recordOf()?.let { "occ ${it.occ} ${it.state}" }}"
            }
            "state" -> "record: ${recordOf()?.let { "occ ${it.occ} ${it.state} by ${host.read(Notices.WINDOW).third.items["rec.notices.$noticeId"]?.by}" }}"
            "slow" -> CountDownLatch(1).also { slow = it; torrents.trackerGate = it }.let { "the tracker waits" }
            // the merge of Q4 and Q5: one `release` ends both waits, the tracker's (Q4) and a stalled `du`'s (Q5)
            "release" -> { slow?.countDown(); torrents.trackerGate = null; look.gate.countDown(); Thread.sleep(300)
                "the tracker answers · files: ${filesPc.jobs.current()}" }
            "done" -> { torrents.event("done", "c0ffee01", torrents.transfers.first { it.hash == "c0ffee01" }.name); "c0ffee01 done" }
            "ops" -> "torrents ops: ${torrents.ops.filter { it != "refresh" }}"
            // S1-2 Q5: a `du` that waits (the slot held, its age on the page), and its end
            "stall" -> { look.gate = CountDownLatch(1); Thread { try { filesPc.slotted.du(look.slow.toString()) } catch (e: Exception) { println("LOOK files du: ${e.message}") } }.start()
                Thread.sleep(300); "files: ${filesPc.jobs.current()}" }
            "files" -> "files: ${filesPc.jobs.current()} · drop holds ${Files.list(look.drop).use { it.map { f -> f.fileName.toString() }.toList() }}" +
                " · trash ${look.files.trashList().map { it.name }}"
            else -> "no such word: $word"
        }
        println("LOOK notice $said")
        System.out.flush()
        val b = said.toByteArray()
        ex.sendResponseHeaders(200, b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }
    ctl.start()
    core.addListener { e -> println("LOOK devices $e") }

    // the one approved device, at full, in this instance
    val claims = LinkedBlockingQueue<String>()
    val p = devices.request("127.0.0.1", null, "the look's browser", "browser", null, object : Devices.Waiting {
        override fun approved(claim: String) { claims.put(claim) }
        override fun ended(reason: String) {}
    })
    check(devices.approve(p.code, Levels.FULL, "look") == null) { "the look's device was not approved" }
    val r = devices.redeem(claims.poll(10, TimeUnit.SECONDS) ?: error("no claim")) ?: error("the claim was refused")
    val secretFile = root.resolve("secret")
    Files.writeString(secretFile, r.secret)
    Files.setPosixFilePermissions(secretFile, PosixFilePermissions.fromString("rw-------"))
    // S1-2 Q5: a second made-up device at control, for the page that is told Files needs full and shows nothing of it
    val lowClaims = LinkedBlockingQueue<String>()
    val low = devices.request("127.0.0.1", null, "the look's control browser", "browser", null, object : Devices.Waiting {
        override fun approved(claim: String) { lowClaims.put(claim) }
        override fun ended(reason: String) {}
    })
    check(devices.approve(low.code, Levels.CONTROL, "look") == null) { "the look's control device was not approved" }
    val lowSecret = root.resolve("secret-control")
    Files.writeString(lowSecret, (devices.redeem(lowClaims.poll(10, TimeUnit.SECONDS) ?: error("no claim")) ?: error("refused")).secret)
    Files.setPosixFilePermissions(lowSecret, PosixFilePermissions.fromString("rw-------"))
    // a made-up page waiting for approval: the devices page's approval lands here and is redeemed as a page would
    val tablet = devices.request("127.0.0.1", null, "made-up tablet", "browser", null, object : Devices.Waiting {
        override fun approved(claim: String) {
            // redeemed off the approving call, as a page's own request would be
            Thread {
                val got = devices.redeem(claim)
                println("LOOK tablet approved: ${got?.row?.id} at ${got?.row?.level}")
            }.start()
        }
        override fun ended(reason: String) = println("LOOK tablet ended: $reason")
    })
    println("LOOK {\"url\":\"http://127.0.0.1:${server.boundPort}\",\"secretFile\":\"$secretFile\",\"book\":\"$book\"," +
        "\"device\":\"${r.row.id}\",\"waiting\":\"${tablet.code}\",\"root\":\"$root\"," +
        "\"ctl\":\"http://127.0.0.1:${ctl.address.port}\",\"filesHome\":\"${look.home}\",\"controlSecret\":\"$lowSecret\"}")
    System.out.flush()
    CountDownLatch(1).await()
}

/** S1-2 Q5: the look's made-up Files tree (every name made up), under the run's own directory. */
private class LookTree(val root: Path) {
    val home: Path = Files.createDirectories(root.resolve("home"))
    val books: Path = Files.createDirectories(home.resolve("books"))
    val drop: Path = Files.createDirectories(home.resolve("drop"))
    val slow: Path = Files.createDirectories(home.resolve("slow"))
    @Volatile var gate = CountDownLatch(1)
    private val words = listOf("salt", "lantern", "copper", "ferry", "river", "harbour", "quiet", "northern", "glass", "orchard", "signal", "tide")
    val files: wm.damage.core.windows.files.FilesProvider

    init {
        val many = Files.createDirectories(home.resolve("many"))
        for (i in 0 until 240) {
            val stem = if (i % 9 == 4) "${words[i % 12]}-${words[(i + 5) % 12]}-and-the-${words[(i + 7) % 12]}-notes-from-the-coast-road-volume-$i"
                else "${words[i % 12]} ${words[(i * 5) % 12]} $i"
            if (i % 13 == 0) Files.createDirectories(many.resolve(stem))
            else Files.writeString(many.resolve("$stem.${listOf("txt", "md", "log", "csv", "zip")[i % 5]}"), "made-up entry $i\n".repeat(1 + i % 40))
        }
        Files.writeString(many.resolve(".hiddenrc"), "a made-up dotfile\n")
        Files.writeString(home.resolve("notes.txt"), buildString {
            appendLine("Trip notes (made up for the look)"); appendLine()
            for (i in 1..80) appendLine("Line $i: leave early, fill the water tank, check the tyres at the first stop.")
        })
        val pics = Files.createDirectories(home.resolve("pictures"))
        for ((n, hue) in listOf("harbour.png" to 0.55f, "orchard.png" to 0.25f, "tide.png" to 0.65f)) {
            val img = java.awt.image.BufferedImage(640, 400, java.awt.image.BufferedImage.TYPE_INT_RGB)
            for (y in 0 until 400) for (x in 0 until 640) img.setRGB(x, y, java.awt.Color.HSBtoRGB(hue + x / 3200f, 0.5f + y / 1600f, 0.4f + y / 800f))
            javax.imageio.ImageIO.write(img, "png", pics.resolve(n).toFile())
        }
        Files.write(home.resolve("plan.pdf"), madeUpPdf())
        Files.writeString(slow.resolve("waits.txt"), "a made-up file in the folder whose stats wait\n")
        for (n in listOf("old draft.txt", "screenshots-from-the-made-up-trip.zip")) Files.writeString(home.resolve(n), "to the trash: $n\n")
        val local = wm.damage.core.windows.files.LocalFilesProvider(books, root.resolve("trash"), null, mountsFile = root.resolve("no-mounts"))
        files = LookFiles(root, local, listOf(
            wm.damage.core.windows.files.FLocation("Home", home.toString(), "home", 4_000_000_000_000, 1_250_000_000_000),
            wm.damage.core.windows.files.FLocation("Pictures", pics.toString(), "mount", 4_000_000_000_000, 1_250_000_000_000),
            wm.damage.core.windows.files.FLocation("Drop", drop.toString(), "downloads", 4_000_000_000_000, 1_250_000_000_000)),
            slow) { gate }
    }

    fun trashTwo(p: wm.damage.core.windows.files.FilesProvider) {
        for (n in listOf("old draft.txt", "screenshots-from-the-made-up-trip.zip")) p.trash(home.resolve(n).toString())
    }

    /** A one-page PDF written by hand (made-up words), so `pdftoppm` has a real page to draw. */
    private fun madeUpPdf(): ByteArray {
        val text = "BT /F1 28 Tf 72 700 Td (A made-up plan for the look) Tj 0 -40 Td /F1 16 Tf (Day one: the coast road.) Tj ET"
        val objs = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Length ${text.length} >>\nstream\n$text\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        val out = StringBuilder("%PDF-1.4\n")
        val at = ArrayList<Int>()
        for ((i, o) in objs.withIndex()) { at += out.length; out.append("${i + 1} 0 obj\n$o\nendobj\n") }
        val xref = out.length
        out.append("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n")
        for (a in at) out.append("%010d 00000 n \n".format(a))
        out.append("trailer\n<< /Size ${objs.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toString().toByteArray(Charsets.ISO_8859_1)
    }
}

/** S1-2 Q5: the look's provider — [local] for paths under [root] only (every call, reads included: anything else is
 *  refused with the reason), its locations the made-up ones; `du` of [slow] waits for [gate]. Opening on the PC is
 *  refused (the look starts no program). */
private class LookFiles(private val root: Path, private val local: wm.damage.core.windows.files.LocalFilesProvider,
                        private val locs: List<wm.damage.core.windows.files.FLocation>, private val slow: Path,
                        private val gate: () -> CountDownLatch) : wm.damage.core.windows.files.FilesProvider by local {
    private fun inside(p: String): String {
        require(Path.of(p).toAbsolutePath().normalize().startsWith(root)) { "outside the look's made-up tree: $p" }
        return p
    }

    override fun locations() = locs + local.locations().filter { it.kind == "trash" && it.path.startsWith(root.toString()) }
    override fun list(dir: String, showHidden: Boolean) = local.list(inside(dir), showHidden)
    override fun stat(path: String) = local.stat(inside(path))
    override fun du(path: String): Long {
        if (Path.of(path) == slow) gate().await()
        return local.du(inside(path))
    }
    override fun readText(path: String, offset: Long, maxBytes: Int) = local.readText(inside(path), offset, maxBytes)
    override fun readBytes(path: String, maxBytes: Int) = local.readBytes(inside(path), maxBytes)
    override fun thumb(path: String, sizePx: Int) = local.thumb(inside(path), sizePx)
    override fun pdfInfo(path: String) = local.pdfInfo(inside(path))
    override fun pdfText(path: String) = local.pdfText(inside(path))
    override fun pdfPage(path: String, page: Int, widthPx: Int) = local.pdfPage(inside(path), page, widthPx)
    override fun trash(path: String) = local.trash(inside(path))
    override fun restore(id: String) = local.restore(id.also { i -> inside(local.trashList().first { it.id == i }.origPath) })
    override fun purge(id: String) = local.purge(id.also { i -> inside(local.trashList().first { it.id == i }.origPath) })
    override fun rename(path: String, newName: String) = local.rename(inside(path), newName)
    override fun mkdir(dir: String, name: String) = local.mkdir(inside(dir), name)
    override fun copy(src: String, destDir: String) = local.copy(inside(src), inside(destDir))
    override fun move(src: String, destDir: String) = local.move(inside(src), inside(destDir))
    override fun openOnPc(path: String) = throw IllegalArgumentException("the look opens nothing on the PC")
}

/** S1-2 Q5: the look's thumbnails (the desktop's are AWT too: `FilesThumbs`), a picture fitted inside [px], as JPEG. */
private fun lookThumb(bytes: ByteArray, px: Int): ByteArray? {
    val src = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes)) ?: return null
    val k = minOf(1.0, px.toDouble() / maxOf(src.width, src.height))
    val img = java.awt.image.BufferedImage(maxOf(1, (src.width * k).toInt()), maxOf(1, (src.height * k).toInt()), java.awt.image.BufferedImage.TYPE_INT_RGB)
    img.createGraphics().apply { drawImage(src, 0, 0, img.width, img.height, null); dispose() }
    return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "jpg", it) }.toByteArray()
}
