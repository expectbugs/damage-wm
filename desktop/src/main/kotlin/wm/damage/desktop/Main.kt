package wm.damage.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import wm.damage.core.content.ContentHostServer
import wm.damage.core.content.LocalContent
import wm.damage.core.replica.ReplicaServer
import wm.damage.core.shell.HostSetting
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellKeeper
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.LensPanels
import wm.damage.core.transport.RemoteTransportClient
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.Transport
import wm.damage.core.util.Log
import wm.damage.core.windows.reader.ReaderWindow

/**
 * The PC program. Modes (`--transport`):
 *
 *   sim      the byte-exact firmware model in-process — the development
 *            environment (DESIGN.md §10.8), and the default until `auto` lands
 *   ble      PC-direct BLE over BlueZ (laptop-direct with real glasses)
 *   remote   the shell here, the transport on the phone (`--remote HOST`
 *            or `phoneHost` in the config) — the "app + home PC" placement
 *
 * Whatever the mode: the 1x preview draws the transport's mirror with mouse
 * input, the browser replica is served on `replicaPort`, the content host
 * serves `booksDir`, and a session keeper restarts the session after every
 * link end. Other entry points:
 *
 *   --selfcheck · --snapshot DIR · --epub-check · --host-only · --ble-info
 */
fun main(args: Array<String>) {
    val cfg = Config.load()
    when {
        "--selfcheck" in args -> SelfCheck.run(cfg)
        "--epub-check" in args -> epubCheck(cfg)
        "--snapshot" in args -> Snapshot.run(cfg,
            Path.of(args.getOrNull(args.indexOf("--snapshot") + 1) ?: "snapshots"))
        "--ble-info" in args -> bleInfo()
        "--host-only" in args -> runBlocking { hostOnly(cfg) }
        else -> {
            val remoteHost = if ("--remote" in args) args.getOrNull(args.indexOf("--remote") + 1) else null
            val mode = when {
                "--transport" in args -> args.getOrNull(args.indexOf("--transport") + 1) ?: "sim"
                remoteHost != null -> "remote"
                "--sim" in args -> "sim"
                "--ble" in args -> "ble"
                else -> "sim"
            }
            runShell(cfg, mode, remoteHost)
        }
    }
}

@Serializable
data class Config(
    val booksDir: String = System.getProperty("user.home") + "/books",
    val contentPort: Int = 7401,
    val transportPort: Int = 7402,
    val replicaPort: Int = 7403,
    val token: String = "",
    val dataDir: String = System.getProperty("user.home") + "/.damage",
    /** The phone's tailnet name or address for the seam (remote / auto modes). */
    val phoneHost: String = "aphone",
    /** The pair's addresses from the last successful PC-direct connect. */
    val leftAddress: String = "",
    val rightAddress: String = "",
) {
    companion object {
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

        fun path(): Path = Path.of(System.getProperty("user.home"), ".damage", "config.json")

        fun load(): Config {
            val p = path()
            val cfg = if (Files.exists(p)) {
                try {
                    json.decodeFromString(serializer(), Files.readString(p))
                } catch (e: Exception) {
                    Log.e("config", "unreadable ${p} — using defaults", e)
                    Config()
                }
            } else Config()
            val fixed = if (cfg.token.isEmpty()) cfg.copy(token = newToken()) else cfg
            if (fixed != cfg || !Files.exists(p)) store(fixed)
            return fixed
        }

        fun store(cfg: Config) {
            val p = path()
            Files.createDirectories(p.parent)
            Files.writeString(p, json.encodeToString(serializer(), cfg))
            Log.i("config", "wrote $p")
        }

        private fun newToken(): String {
            val b = ByteArray(24)
            SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }
    }
}

/** Parse every book in the library and report — the extractor must survive the
 *  REAL shelf, not just fixtures. Exit 1 if any book fails. */
private fun epubCheck(cfg: Config) {
    val lib = LocalContent(Path.of(cfg.booksDir)).library()
    println("${lib.size} books in ${cfg.booksDir}")
    var bad = 0
    for (b in lib) {
        try {
            val t0 = System.currentTimeMillis()
            val book = wm.damage.core.windows.reader.Epub.load(Path.of(b.file))
            println("  OK   ${b.title} — ${book.text.length / 1024}K chars, " +
                "${System.currentTimeMillis() - t0}ms" +
                (if (book.author.isNotEmpty()) " · ${book.author}" else ""))
        } catch (e: Exception) {
            bad++
            println("  FAIL ${b.file}: ${e.message}")
        }
    }
    kotlin.system.exitProcess(if (bad > 0) 1 else 0)
}

/** Adapter enumeration only — a D-Bus read, no discovery, no connection: the
 *  one BlueZ check that is allowed before first light (HANDOFF.md §8.1 #2). */
private fun bleInfo() {
    try {
        val link = BlueZDbus()
        val a = link.adapter()
        println("BlueZ adapter ${a.name} ${a.address} powered=${a.powered} (${a.path})")
        val known = link.peers()
        println("${known.size} device(s) already known to BlueZ (no discovery was run):")
        for (p in known) println("  ${p.address}  '${p.name}'  connected=${p.connected}")
        link.close()
        kotlin.system.exitProcess(0)
    } catch (e: Exception) {
        println("BlueZ not usable: ${e.message}")
        e.printStackTrace()
        kotlin.system.exitProcess(1)
    }
}

private suspend fun hostOnly(cfg: Config) {
    val host = ContentHostServer(LocalContent(Path.of(cfg.booksDir)), cfg.contentPort, cfg.token)
    host.start()
    Log.i("damage", "content host only — serving ${cfg.booksDir} on :${cfg.contentPort}; Ctrl-C to stop")
    kotlinx.coroutines.awaitCancellation()
}

/**
 * One shell + transport + keeper for a mode. Rebuilt whole when the mode
 * changes (the Settings "Target" row), like the phone's stack: the shell is
 * bound to one transport for its lifetime, and persisted state carries over.
 */
class DesktopStack(
    private val cfg: Config,
    val mode: String,
    private val remoteHost: String?,
    private val text: AwtText,
    private val onStatus: (String) -> Unit,
    private val onSwitch: (String) -> Unit,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val transport: Transport = when (mode) {
        "sim" -> SimTransport(GlassFirmwareSim(), scope)
        "ble" -> BlueZTransport(BlueZDbus(), scope,
            cachedAddresses = { Config.load().let { it.leftAddress.ifEmpty { null } to it.rightAddress.ifEmpty { null } } },
            rememberAddresses = { l, r -> Config.store(Config.load().copy(leftAddress = l, rightAddress = r)) })
        "remote" -> RemoteTransportClient(remoteHost ?: cfg.phoneHost, cfg.transportPort, cfg.token, scope)
        else -> throw IllegalArgumentException("unknown transport mode '$mode' (sim | ble | remote)")
    }
    val shell: Shell
    val keeper: ShellKeeper

    init {
        val dataDir = Path.of(cfg.dataDir)
        val persistence = Persistence(dataDir.resolve("state.json"))
        shell = Shell(text, transport, persistence, dataDir.resolve("journal.jsonl"), scope)
        val content = LocalContent(Path.of(cfg.booksDir))
        shell.register(ReaderWindow(text, content, scope))
        shell.hostSettings = listOf(
            HostSetting("Target", MODES, current = { mode }, apply = { v -> onSwitch(v) }),
        )
        keeper = ShellKeeper(shell, transport, scope, onStatus = onStatus,
            onTerminal = { reason -> Log.e("damage", "the $mode transport ended for good: $reason") })
    }

    fun start() = keeper.start()

    suspend fun stop() {
        keeper.stop()
        scope.cancel()
    }

    fun statusLine(): String {
        val st = transport.state.value
        val link = if (!st.connected) "no link" else if (st.leaseHeld) "link + lease" else "link, no lease"
        val div = shell.lastDivergence?.let { " · DIVERGE $it" } ?: ""
        return "${st.transportName} · $link · ${st.ackMsEma.toInt()} ms · ${(st.bytesPerSecEma / 1000).toInt()} K/s · " +
            "${keeper.state.name.lowercase()}: ${keeper.lastReason}$div"
    }

    companion object {
        val MODES = listOf("sim", "ble", "remote")
    }
}

private fun runShell(cfg: Config, mode: String, remoteHost: String?): Unit = runBlocking {
    var stack: DesktopStack? = null
    var shellRef: Shell? = null
    val text = AwtText { shellRef?.settings?.fontScale ?: 1.0 }
    val keeperStatus = java.util.concurrent.atomic.AtomicReference("starting")
    val switching = java.util.concurrent.atomic.AtomicBoolean(false)

    // The PC always serves its library so a phone shell can feed from it.
    val host = ContentHostServer(LocalContent(Path.of(cfg.booksDir)), cfg.contentPort, cfg.token)
    try {
        host.start()
    } catch (e: Exception) {
        Log.e("damage", "content host failed to bind :${cfg.contentPort} (another instance?)", e)
    }

    lateinit var build: (String) -> DesktopStack
    fun switchTo(next: String) {
        if (next == stack?.mode) return
        if (!switching.compareAndSet(false, true)) return
        Log.i("damage", "switching the transport to $next")
        Thread({
            try {
                runBlocking { stack?.stop() }
                val s = build(next)
                stack = s
                shellRef = s.shell
                s.start()
            } catch (e: Exception) {
                Log.e("damage", "switch to $next failed", e)
                keeperStatus.set("switch to $next failed: ${e.message}")
            } finally {
                switching.set(false)
            }
        }, "damage-switch").start()
    }
    build = { m -> DesktopStack(cfg, m, remoteHost, text, onStatus = { keeperStatus.set(it) }, onSwitch = { switchTo(it) }) }

    val first = build(mode)
    stack = first
    shellRef = first.shell

    // the 1x preview with mouse, on whatever the current stack mirrors
    Preview.show({ stack?.transport?.mirror }, { t -> stack?.transport?.injectInput(t) }) {
        stack?.statusLine() ?: keeperStatus.get()
    }
    // the browser replica on the tailnet
    val replica = ReplicaServer(cfg.replicaPort, cfg.token, { stack?.transport?.mirror }, {
        val st = stack?.transport?.state?.value
        ReplicaServer.Status(
            transport = st?.transportName ?: "none",
            connected = st?.connected ?: false, started = st?.started ?: false, leaseHeld = st?.leaseHeld ?: false,
            ackMs = st?.ackMsEma?.toInt() ?: 0, bytesPerSec = st?.bytesPerSecEma?.toInt() ?: 0,
            driver = "PC shell (${stack?.mode ?: mode})", note = stack?.shell?.lastDivergence?.let { "DIVERGE $it" } ?: keeperStatus.get(),
        )
    }) { t -> stack?.transport?.injectInput(t) }
    try {
        replica.start()
    } catch (e: Exception) {
        Log.e("damage", "browser replica failed to bind :${cfg.replicaPort}", e)
    }

    first.start()
    Log.i("damage", "shell up in $mode mode — books from ${cfg.booksDir}, journal in ${cfg.dataDir}")
    Log.i("damage", "preview: wheel/click/right-click/hold = ring · Tab lens · B both")
    Log.i("damage", "browser replica: http://<this-host>:${cfg.replicaPort}/?token=${cfg.token}")
    kotlinx.coroutines.awaitCancellation()
}
