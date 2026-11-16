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
    val uploads = Uploads(data)
    net.addEndListener(uploads::connectionEnded)
    val server = WebServer(0, ReachGate(Side.PC), Access(Access.LOOPBACK_NAMES), devices, net, Bundle.fromResources(),
        BlobSources(mapOf(ReaderBlobs.PREFIX to ReaderBlobs(reader, docs))), bindAddress = InetAddress.getLoopbackAddress(),
        uploads = uploads)
    server.start()
    reader.refreshLibrary()
    host.addListener { c ->
        val states = c.effect.items.filterKeys { it.startsWith("rec.notices.") }.map { (k, i) ->
            Notices.parse(k.removePrefix("rec.notices."), i.value)?.let { "${it.id} occ ${it.occ} ${it.state}" } ?: k }
        println("LOOK change ${c.seq} by ${c.origin?.device}: ${c.effect.items.keys.sorted()}" +
            if (states.isEmpty()) "" else " $states")
    }
    // S1-2 Q3: the raiser, and the made-up other device that dismisses elsewhere
    val notices = NoticeCore(host)
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
        "\"ctl\":\"http://127.0.0.1:${ctl.address.port}\"}")
    System.out.flush()
    CountDownLatch(1).await()
}
