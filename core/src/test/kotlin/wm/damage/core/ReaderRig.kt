package wm.damage.core

import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import wm.damage.core.app.Answer
import wm.damage.core.app.AppClient
import wm.damage.core.app.AppHost
import wm.damage.core.app.AppNet
import wm.damage.core.app.CoreExecutor
import wm.damage.core.app.HostBackend
import wm.damage.core.app.Item
import wm.damage.core.app.LocalWindowSource
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.app.SourceListener
import wm.damage.core.app.WindowState
import wm.damage.core.app.WorkingCopy
import wm.damage.core.content.ContentHostServer
import wm.damage.core.content.LocalContent
import wm.damage.core.content.RemoteContent
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ShellServices
import wm.damage.core.shell.WindowView
import wm.damage.core.windows.reader.ReaderAnchor
import wm.damage.core.windows.reader.ReaderCore
import wm.damage.core.windows.reader.ReaderGlasses

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P4: the rig Reader's conversion tests share — a PC record in a temp directory with Reader's
 * actions, its `app` lane on loopback port 0, the phone's working copy over that lane, and Reader's glasses presenter
 * over either host's source with a stand-in for the shell's services. Nothing here leaves loopback or the temp tree.
 */

/** The shell's services as the glasses presenter uses them: runs on the caller's thread, records the notices. */
internal open class ReaderServices : ShellServices {
    val notices = CopyOnWriteArrayList<String>()
    override fun requestRender(window: DamageWindow) {}
    override fun setOperation(op: String) {}
    override fun notifyInternal(source: String, body: String, urgent: Boolean,
        appId: String?, thread: String, target: String?, answer: Boolean) { notices.add("$source: $body") }
    override fun openWindow(id: String, target: String?): Boolean = false
    override fun runOnShell(action: () -> Unit) = synchronized(this) { action() }

    /** Runs [f] as the shell loop would: never beside a source's change or answer being applied. */
    fun <T> onLoop(f: () -> T): T = synchronized(this) { f() }
    /** The text column's width: a test moves it to make the open book wrap again (a height or safe-area change). */
    @Volatile var width = 560
    override fun docContentWidth(): Int = width
    override fun docContentHeight(): Int = 384
}

/** The shell while no session is live (`Shell.runOnShell(key)`, §72): a keyed post is held, the latest per key, until
 *  [release] (the next session's start); an unkeyed post runs at once. So an answer can come before its change. */
internal class HoldingServices : ReaderServices() {
    private val held = LinkedHashMap<String, () -> Unit>()
    override fun runOnShell(key: String, action: () -> Unit) {
        synchronized(this) { held.remove(key); held[key] = action }
    }
    fun release() {
        synchronized(this) { val all = held.values.toList(); held.clear(); for (a in all) a() }
    }
}

/** Answers a source gave, by op. */
internal class ReaderHeard : SourceListener {
    val answers = CopyOnWriteArrayList<Pair<Op, Answer>>()
    override fun answered(op: Op, answer: Answer) { answers += op to answer }
}

internal class ReaderRig(val scope: CoroutineScope) : AutoCloseable {
    val dir: Path = Files.createTempDirectory("damage-reader-rig")
    val books: Path = dir.resolve("books")
    val now = AtomicLong(1_800_000_000_000L)
    private val closing = ArrayList<AutoCloseable>()

    fun <T : AutoCloseable> keep(t: T): T = t.also { closing += it }

    /** Two plain-text books (a.txt, b.txt) of 200 paragraphs each; returns their ids, in title order. */
    fun writeBooks(): List<String> {
        Files.createDirectories(books)
        for (name in listOf("alpha", "beta")) Files.writeString(books.resolve("$name.txt"), buildString {
            appendLine(name.uppercase())
            appendLine()
            for (i in 1..200) {
                appendLine("Paragraph $i of $name. The compositor batches all damage into one atomic flush.")
                appendLine()
            }
        })
        return LocalContent(books).library().sortedBy { it.title }.map { it.id }
    }

    /** A PC record over [sub] with Reader's actions, opened (a process start). */
    fun pc(sub: String = "pc"): AppHost {
        val h = AppHost(Files.createDirectories(dir.resolve(sub)), AppHost.WINDOWS, CoreExecutor("damage-core-reader-test"),
            clock = { now.get() })
        h.open()
        keep(AutoCloseable { h.close(); h.executor.shutdown() })
        return h
    }

    /** The PC's lane and book bytes on loopback: returns the bound port. [port] 0 = any. */
    fun serve(h: AppHost, port: Int = 0): ContentHostServer {
        val net = AppNet(h)
        val s = ContentHostServer(LocalContent(books), port, TOKEN, app = net, bindAddress = InetAddress.getLoopbackAddress())
        s.start()
        keep(AutoCloseable { s.close(); net.close() })
        return s
    }

    /** The phone's working copy in [sub], its lane to the PC on [port] (a port nothing serves yet is the PC away). */
    fun phone(port: Int, sub: String = "phone"): WorkingCopy {
        val wc = WorkingCopy(dir.resolve(sub).resolve("workcopy"), AppHost.WINDOWS, CoreExecutor("damage-core-phone-test"),
            clock = { now.get() })
        wc.start { stream ->
            AppClient("127.0.0.1", port, AppClient.Credential.Token(TOKEN), stream, wc, scope,
                retryPacingMs = 100, refusedPacingMs = 100, pingEveryMs = 200)
        }
        keep(AutoCloseable { wc.close(); wc.executor.shutdown() })
        return wc
    }

    /** The PC's glasses presenter over its own record (device `local`). */
    fun pcGlasses(h: AppHost, services: ReaderServices = ReaderServices()): ReaderGlasses {
        val core = ReaderCore(LocalContent(books), scope)
        val src = keep(LocalWindowSource(core, HostBackend(h, "local", "glasses")))
        return ReaderGlasses(FakeText(), core, src, scope).also { it.onRegistered(services) }
    }

    /** The phone's glasses presenter over its working copy (device `phone`), books through [port]. */
    fun phoneGlasses(wc: WorkingCopy, port: Int, services: ReaderServices = ReaderServices()): ReaderGlasses {
        val core = ReaderCore(RemoteContent("127.0.0.1", port, TOKEN, Files.createDirectories(dir.resolve("bookcache"))), scope)
        val src = keep(LocalWindowSource(core, wc))
        return ReaderGlasses(FakeText(), core, src, scope, device = wc.device).also { it.onRegistered(services) }
    }

    /** Another device writes [book]'s place on [h] directly, its input at [at] (the rig's clock by default). */
    fun otherDevicePlace(h: AppHost, device: String, stream: String, n: Long, book: String, offset: Int,
                         at: Long = now.get()): Answer {
        val o = Op(device, OpId.Queued(stream, n), ReaderCore.WINDOW, ReaderCore.PLACE, listOf(ReaderCore.placeItem(book)),
            JsonPrimitive(ReaderAnchor(offset).format()), at = at)
        val got = CopyOnWriteArrayList<Answer>()
        h.act(o) { got += it }
        h.executor.call {}
        return got.single()
    }

    fun place(h: AppHost, book: String): Item? =
        h.read(ReaderCore.WINDOW).third.items[wm.damage.core.app.Record.key(ReaderCore.WINDOW, ReaderCore.placeItem(book))]

    fun placeOffset(h: AppHost, book: String): Int? =
        place(h, book)?.takeIf { !it.gone }?.let { ReaderAnchor.parse((it.value as JsonPrimitive).content)?.offset }

    override fun close() {
        for (c in closing.asReversed()) try { c.close() } catch (e: Exception) { /* the next closes all the same */ }
        dir.toFile().deleteRecursively()
    }

    companion object {
        const val TOKEN = "reader-rig-token"
    }
}

/** The open book's document model (the presenter must be at its book level). */
internal fun ReaderGlasses.doc(): wm.damage.core.shell.DocModel = (view() as WindowView.DocView).model

internal fun anchorOffset(v: JsonElement?): Int? = (v as? JsonPrimitive)?.let { ReaderAnchor.parse(it.content)?.offset }

internal fun stateOffset(st: WindowState, book: String): Int? = ReaderCore.placeOf(st, book)?.offset

internal suspend fun readerAwait(what: String, ms: Long = 20_000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) {
        if (cond()) return
        delay(20)
    }
    if (!cond()) throw AssertionError("timed out waiting for: $what")
}
