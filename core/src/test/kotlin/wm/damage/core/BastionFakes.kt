package wm.damage.core

import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import wm.damage.core.bastion.AlertDelivery
import wm.damage.core.bastion.AlertRecord
import wm.damage.core.bastion.AlertStore
import wm.damage.core.bastion.BastionService
import wm.damage.core.bastion.PhoneAlerts
import wm.damage.core.bastion.RemoteBastion
import wm.damage.core.content.ContentHostServer
import wm.damage.core.content.LocalContent
import wm.damage.core.net.WinService
import wm.damage.core.shell.Notifications
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellSettings
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport

/**
 * Build W, package C: stand-ins shared by the phone-side bus watcher tests — the phone's shade ([FakePhone]), the
 * glasses' notice entry (a counter), an [AlertDelivery] on an injected clock and zone ([DeliveryRig]), and a PC host
 * on loopback ([BastionHost]: a real `ContentHostServer` with the real `BastionService`, wrapped to count the ops it
 * serves and, on request, to drop one push). No phone, glasses or hub is contacted.
 */
internal class FakePhone : PhoneAlerts {
    data class Post(val id: String, val kind: AlertDelivery.Kind, val body: String, val silent: Boolean, val acked: Boolean)
    val posts = CopyOnWriteArrayList<Post>()
    val cancels = CopyOnWriteArrayList<String>()
    val shown: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val homeShows = CopyOnWriteArrayList<Pair<String, Boolean>>()
    val homeCancels = AtomicInteger()

    override fun show(rec: AlertRecord, kind: AlertDelivery.Kind, body: String, silent: Boolean) {
        posts += Post(rec.id, kind, body, silent, rec.acknowledged)
        shown += rec.id
    }
    override fun cancel(id: String) { cancels += id; shown -= id }
    override fun shownIds(): Set<String> = shown.toSet()
    override fun homeShow(text: String, silent: Boolean) { homeShows += text to silent }
    override fun homeCancel() { homeCancels.incrementAndGet() }

    /** The posts that make a sound (not silent): what "said" means on the phone. */
    fun loud(id: String? = null) = posts.filter { !it.silent && (id == null || it.id == id) }
}

internal data class Said(val source: String, val body: String, val thread: String, val gatedBy: String)

/** 2026-11-13 12:00 UTC: the tests' wall clock starts here, in UTC unless a test moves the zone. */
internal val NOON: Long = Instant.parse("2026-11-13T12:00:00Z").toEpochMilli()

internal class DeliveryRig(settings: ShellSettings = ShellSettings(), phoneOn: Boolean = true, start: Long = NOON) {
    @Volatile var settings = settings
    @Volatile var phoneOn = phoneOn
    @Volatile var zone: ZoneId = ZoneOffset.UTC
    val clock = AtomicLong(start)
    val glasses = CopyOnWriteArrayList<Said>()
    val phone = FakePhone()
    val delivery = AlertDelivery({ s, b, t, g -> glasses += Said(s, b, t, g) }, { this.settings }, phone,
        { this.phoneOn }, clock::get, { zone })

    fun said(thread: String) = glasses.filter { it.thread == thread }
}

/** The PC's side on loopback: the real service behind a wrapper that counts ops and can drop one push by seq. */
internal class BastionHost(dir: Path, val store: AlertStore, port: Int = ServerSocket(0).use { it.localPort }) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val port = port
    val service = BastionService(store, { "1 rule(s) · hub answered 12:00" }, scope)
    val ops = ConcurrentHashMap<String, AtomicInteger>()
    @Volatile var dropPushSeq = -1L
    /** A push sent twice (the test's duplicate: the second copy is at the client's seq). */
    @Volatile var dupPushSeq = -1L
    private val counting = object : WinService {
        override fun request(op: String, args: JsonObject): WinService.Answer {
            ops.computeIfAbsent(op) { AtomicInteger() }.incrementAndGet()
            return service.request(op, args)
        }
        override fun attached(push: WinService.Push) = service.attached(object : WinService.Push {
            override fun send(op: String, args: JsonObject, blob: ByteArray?) {
                val seq = (args["event"] as? JsonObject)?.get("seq")?.toString()?.toLongOrNull()
                if (seq != null && seq == dropPushSeq) return               // the test's lost push
                push.send(op, args, blob)
                if (seq != null && seq == dupPushSeq) push.send(op, args, blob)  // the test's duplicate
            }
            override fun equals(other: Any?) = other === this
            override fun hashCode() = System.identityHashCode(this)
        }.also { wrapped[push] = it })
        override fun detached(push: WinService.Push) { wrapped.remove(push)?.let { service.detached(it) } }
    }
    private val wrapped = ConcurrentHashMap<WinService.Push, WinService.Push>()
    val host = ContentHostServer(LocalContent(dir.resolve("books")), port, "tok", win = mapOf("bastion" to counting))

    init { host.start() }

    fun op(name: String): Int = ops[name]?.get() ?: 0

    override fun close() {
        host.close()
        service.close()
        scope.cancel()
    }
}

/** A phone-side client against [port], with a short ping and a short reconnect pacing. */
internal fun remote(port: Int, scope: CoroutineScope, cacheDir: Path?, d: AlertDelivery, pingMs: Long = 100,
                    reaskPacingMs: Long = RemoteBastion.REASK_MS) =
    RemoteBastion("127.0.0.1", port, "tok", scope, cacheDir, d, pingMs = pingMs, retryPacingMs = 50,
        reaskPacingMs = reaskPacingMs)

/** A real shell on the simulator (instant link), its store in a temp directory; [urgent] counts the host's urgent
 *  hook, which nothing of the bus watcher may call. */
internal class ShellRig(settings: ShellSettings? = null) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val tmp: Path = Files.createTempDirectory("damage-bastion-shell")
    val store = Persistence(tmp.resolve("store").resolve("state.json"))
        .also { p -> if (settings != null) p.put("shell.settings", settings.toJson()) }
    val shell = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true)), store,
        null, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
    val urgent = AtomicInteger()

    init { shell.onUrgent = { _, _ -> urgent.incrementAndGet() } }

    suspend fun settle(what: String) =
        waitUntil(10_000, 10, { "$what: did not settle — ${shell.quiescenceReport()}" }) { shell.isQuiescent() }

    /** The box shown and the queue behind it. */
    fun posted(): List<Notifications.Notice> = listOfNotNull(shell.notifications.current) + shell.notifications.queued()

    override fun close() {
        scope.cancel()
        tmp.toFile().deleteRecursively()
    }
}

/** The client has handled everything the store holds: its cursor is the store's (epoch, seq) — published after the
 *  step that wrote delivery.json. */
internal suspend fun synced(rb: RemoteBastion, store: AlertStore) =
    waitUntil(10_000, 5, { "client at ${rb.cursor()}, store at (${store.epoch}, ${store.seq})" }) {
        rb.cursor() == (store.epoch to store.seq) }

/** At least [n] more work items (pings' ticks among them) handled: "nothing more happened" is decided after them. */
internal suspend fun ticks(rb: RemoteBastion, n: Int) {
    val h0 = rb.handled()
    waitUntil(10_000, 5, { "$n more items handled after $h0 (at ${rb.handled()})" }) { rb.handled() >= h0 + n }
}
