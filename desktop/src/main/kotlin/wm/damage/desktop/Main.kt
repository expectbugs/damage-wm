package wm.damage.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import wm.damage.core.transport.PathTransport
import wm.damage.core.transport.RemoteTransportClient
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.Transport
import wm.damage.core.util.Log
import wm.damage.core.windows.reader.ReaderWindow

/**
 * The PC program. Modes (`--transport`):
 *
 *   auto     THE DEFAULT — STANDBY (HANDOFF.md §19, the corrected §8.1
 *            reading): the PHONE SHELL is the primary driver; this process
 *            serves data (content host + state sync + replica) and probes the
 *            phone's seam on a 5 s pacing. Only when the APK is absent — or
 *            reachable and saying Target≠glasses — for two consecutive probes
 *            does it start a PC-direct BLE stack; the moment the APK wants
 *            the radio back, the stack stops (the handback) and the phone's
 *            keeper reconnects with its normal choreography
 *   sim      the byte-exact firmware model in-process — the development
 *            environment (DESIGN.md §10.8)
 *   ble      PC-direct BLE over BlueZ only (laptop-direct with real glasses)
 *   remote   the phone's transport over the seam, this shell driving THROUGH
 *            it (`--remote HOST`) — the EXPLICIT dev override; daily operation
 *            never claims (§19.1)
 *
 * Whatever the mode: the 1x preview draws the transport's mirror with mouse
 * input, the browser replica is served on `replicaPort`, the content host
 * serves `booksDir` + tmux + sync, and a session keeper restarts any running
 * session after every link end. Other entry points:
 *
 *   --selfcheck · --snapshot DIR · --epub-check · --music-check · --card-render DIR
 *   --host-only · --ble-info
 *
 * `--no-preview` runs any transport mode headless (no Swing, no X) — the
 * all-day OpenRC service mode (DAILY.md); views are the replicas.
 */
fun main(args: Array<String>) {
    // --no-preview: the all-day service mode (DAILY.md) — no Swing window, no
    // X needed; the phone screen and the browser replica are the views. Set
    // BEFORE any AWT class loads so the JVM commits to headless.
    if ("--no-preview" in args) System.setProperty("java.awt.headless", "true")
    val cfg = Config.load()
    when {
        "--selfcheck" in args -> SelfCheck.run(cfg)
        "--epub-check" in args -> epubCheck(cfg)
        "--music-check" in args -> MusicCheck.run(cfg)
        "--card-render" in args -> CardSheet.run(cfg,
            Path.of(args.getOrNull(args.indexOf("--card-render") + 1) ?: "design/shots/cards"))
        "--snapshot" in args -> Snapshot.run(cfg,
            Path.of(args.getOrNull(args.indexOf("--snapshot") + 1) ?: "snapshots"))
        "--ble-info" in args -> bleInfo()
        "--host-only" in args -> runBlocking { hostOnly(cfg) }
        else -> {
            val remoteHost = if ("--remote" in args) args.getOrNull(args.indexOf("--remote") + 1) else null
            val mode = when {
                "--transport" in args -> args.getOrNull(args.indexOf("--transport") + 1) ?: "auto"
                remoteHost != null -> "remote"
                "--sim" in args -> "sim"
                "--ble" in args -> "ble"
                else -> "auto"
            }
            runShell(cfg, mode, remoteHost, preview = "--no-preview" !in args)
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
    /** Tmux (TMUX.md verdict 1): ssh hosts the provider fans out to beyond
     *  this machine, e.g. {"name":"slappy","ssh":"slappy","sshPort":80}.
     *  Default EMPTY: a configured-but-down host shows in the staleness line
     *  every tick by design (no silent failures), so hosts are opted in when
     *  they are actually alive — slappy was 12 days offline when this shipped.
     *  A listed host without tmux simply contributes zero sessions. */
    val tmuxHosts: List<wm.damage.core.windows.tmux.TmuxHostCfg> = listOf(),
    /** Empty = the TmuxConfig defaults (the dozen keys · G2CC's slash
     *  snippets · the Claude-tuned wait patterns). Served to the phone with
     *  the session list, so this file is the ONE place to tune them. */
    val tmuxQuickKeys: List<String> = listOf(),
    val tmuxSnippets: List<String> = listOf(),
    val tmuxWaitPatterns: List<String> = listOf(),
    /** Torrents (TORRENTS.md, 2026-09-01): qBittorrent's Web API on this box
     *  — loopback with the localhost auth bypass, so the credentials stay
     *  empty; and the TorrentLeech account (the standing secrets rule: this
     *  file only, never the repo). */
    val qbtUrl: String = "http://127.0.0.1:8090",
    val qbtUser: String = "",
    val qbtPass: String = "",
    val torrentleechUser: String = "",
    val torrentleechPass: String = "",
    /** Music (MUSIC.md §9.7, 2026-09-02): the G2CC music system taken over
     *  whole — Postgres `g2cc` over the Unix socket (peer auth), Qdrant, the
     *  library roots, the legacy transcode cache read in place, our own cache,
     *  the enrichment package's Python (G2CC's venv until Damage owns one),
     *  yt-dlp, the Claude one-shot for the Ask lane, and the media endpoint's
     *  port. `musicAcoustidKey` is optional and lives ONLY in this file. */
    val musicDb: String = "g2cc",
    val musicSocketDir: String = "/run/postgresql",
    val musicQdrant: String = "http://127.0.0.1:6333",
    val musicQdrantCollection: String = "g2cc_music",
    val musicLibraryDirs: List<String> = listOf(System.getProperty("user.home") + "/Music"),
    val musicLegacyCache: String = System.getProperty("user.home") + "/.g2cc/media-cache",
    val musicCache: String = System.getProperty("user.home") + "/.damage/media-cache",
    val musicPython: String = "/home/user/G2CC/audio/venv/bin/python",
    val musicYtDlp: String = System.getProperty("user.home") + "/.local/bin/yt-dlp",
    val musicYoutubeDir: String = "YouTube",
    val musicClaudeModel: String = "opus",
    val musicClaudeEffort: String = "low",
    val musicQueueSize: Int = 25,
    val mediaPort: Int = 7404,
    val musicAcoustidKey: String = "",
    /** The enrichment package + viz.py (`audio/` in the repo, MUSIC.md §9.5). */
    val musicAudioDir: String = "/home/user/damagewm/audio",
) {
    /** The PC-side music library (MUSIC.md §5): Postgres + Qdrant + the
     *  caches + the media endpoint's resolver. The leaf collaborators
     *  (resolver lanes, lyric sources, yt-dlp, the enrichment package) are
     *  wired by [wireMusicPlugins] as they exist. */
    fun musicLibrary(scope: CoroutineScope): wm.damage.core.windows.music.LocalMusicLibrary {
        val db = wm.damage.core.windows.music.MusicDb(PgDb(musicDb, musicSocketDir), musicLibraryDirs)
        val cacheRoot = Path.of(musicCache)
        val legacy = Path.of(musicLegacyCache).takeIf { Files.isDirectory(it) }
        val cache = wm.damage.core.windows.music.MediaCache(cacheRoot, legacy)
        val art = wm.damage.core.windows.music.Art(cacheRoot.resolve("art"))
        val qdrant = wm.damage.core.windows.music.Qdrant(musicQdrant, musicQdrantCollection)
        val scan = wm.damage.core.windows.music.LibraryScan(db, musicLibraryDirs)
        val tok = java.net.URLEncoder.encode(token, "UTF-8")
        val lib = wm.damage.core.windows.music.LocalMusicLibrary(db, cache, art, cacheRoot.resolve("viz"), qdrant, scan, scope,
            mediaUrl = { id, p -> "http://127.0.0.1:$mediaPort/track/$id?token=$tok&profile=${p.name}" },
            queueSize = musicQueueSize)
        lib.youtubeDir = Path.of(musicLibraryDirs.firstOrNull() ?: (System.getProperty("user.home") + "/Music")).resolve(musicYoutubeDir)
        MusicPlugins.wire(this, lib)
        return lib
    }

    /** The media endpoint on [mediaPort], bound like the content port. */
    fun mediaServer(lib: wm.damage.core.windows.music.LocalMusicLibrary): wm.damage.core.windows.music.MediaServer =
        wm.damage.core.windows.music.MediaServer(mediaPort, token) { id, p -> lib.resolveMedia(id, p) }

    /** The PC-side torrents provider: qBittorrent over loopback + the tracker
     *  session (null tracker = browse/search say so loudly). */
    fun torrentsProvider(scope: CoroutineScope): wm.damage.core.windows.torrents.LocalTorrentsProvider =
        wm.damage.core.windows.torrents.LocalTorrentsProvider(
            wm.damage.core.windows.torrents.QbtClient(qbtUrl, qbtUser, qbtPass),
            if (torrentleechUser.isNotEmpty()) wm.damage.core.windows.torrents.TorrentLeech(
                torrentleechUser, torrentleechPass, Path.of(dataDir).resolve("tl-cookies.json")) else null,
            Path.of(dataDir), scope)

    fun tmuxHostList(): List<wm.damage.core.windows.tmux.TmuxHostCfg> =
        listOf(wm.damage.core.windows.tmux.TmuxHostCfg(localHostLabel())) + tmuxHosts

    fun tmuxConfig(): wm.damage.core.windows.tmux.TmuxConfig = wm.damage.core.windows.tmux.TmuxConfig(
        quickKeys = tmuxQuickKeys.ifEmpty { wm.damage.core.windows.tmux.TmuxConfig.DEFAULT_QUICK_KEYS },
        snippets = tmuxSnippets.ifEmpty { wm.damage.core.windows.tmux.TmuxConfig.DEFAULT_SNIPPETS },
        waitPatterns = tmuxWaitPatterns.ifEmpty { wm.damage.core.windows.tmux.TmuxConfig.DEFAULT_WAIT_PATTERNS },
    )
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
            warnBadMusicRoots(fixed)
            return fixed
        }

        /** `musicLibraryDirs` is used verbatim by LibraryScan (which walks it)
         *  and MusicDb (which makes folder names relative to it). An empty
         *  list or a relative entry is not an error either of them can report:
         *  the scan simply finds nothing and every folder column shows an
         *  absolute path. Say so at load — the value is NOT substituted and the
         *  file is NOT rewritten, so what the user wrote is what runs (review 2
         *  2026-09-02; the enrichment package's damage_config.py warns the same
         *  way on the Python side). */
        private fun warnBadMusicRoots(cfg: Config) {
            if (cfg.musicLibraryDirs.isEmpty()) {
                Log.e("config", "musicLibraryDirs is EMPTY in ${path()} — the library scan will " +
                    "walk nothing and folder names will show as absolute paths; the catalog still " +
                    "comes from the ${cfg.musicDb} database")
                return
            }
            val relative = cfg.musicLibraryDirs.filterNot { it.startsWith("/") }
            if (relative.isNotEmpty()) {
                Log.e("config", "musicLibraryDirs entries are not absolute paths: $relative — " +
                    "the scan skips what it cannot resolve and folder names stay absolute")
            }
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

        /** This machine's short hostname as the local tmux host label —
         *  sessions read "claude · beardos" next to "build · slappy". */
        fun localHostLabel(): String = try {
            java.net.InetAddress.getLocalHost().hostName.substringBefore('.').ifEmpty { "local" }
        } catch (e: Exception) {
            "local"
        }
    }
}

/** Parse every book in the library and report — the extractor must survive the
 *  REAL shelf, not just fixtures. Exit 1 if any book fails. */
private fun epubCheck(cfg: Config) {
    val lib = LocalContent(Path.of(cfg.booksDir)).library()
    println("${lib.size} books in ${cfg.booksDir}")
    var bad = 0
    var imgTotal = 0
    var imgDecoded = 0
    val dec = AwtImages()
    for (b in lib) {
        try {
            val t0 = System.currentTimeMillis()
            val book = wm.damage.core.windows.reader.Epub.load(Path.of(b.file))
            // decode every referenced image with the real decoder (2026-08-31):
            // an undecodable one shows as a placeholder, not a failure — but say so
            var ok = 0
            for ((_, bytes) in book.images) if (dec.decode(bytes) != null) ok++
            imgTotal += book.images.size
            imgDecoded += ok
            val imgNote = if (book.images.isEmpty()) ""
                else " · ${ok}/${book.images.size} images" +
                    (if (ok < book.images.size) " (rest undecodable — placeholders)" else "")
            println("  OK   ${b.title} — ${book.text.length / 1024}K chars, " +
                "${book.chapters.size} chapters, " +
                "${System.currentTimeMillis() - t0}ms" +
                (if (book.author.isNotEmpty()) " · ${book.author}" else "") + imgNote)
        } catch (e: Exception) {
            bad++
            println("  FAIL ${b.file}: ${e.message}")
        }
    }
    if (imgTotal > 0) println("images across the shelf: $imgDecoded/$imgTotal decode")
    kotlin.system.exitProcess(if (bad > 0) 1 else 0)
}

/** Adapter enumeration only — a D-Bus read, no discovery, no connection: the
 *  one BlueZ check that is allowed before first light (HANDOFF.md §8.1 #2). */
/** The music library + its media endpoint, or null (loudly) when Postgres
 *  is not reachable at start — the rest of the host keeps serving. The
 *  catalog builds on the first request (or the first driver's cursor). */
private fun startMusic(cfg: Config, scope: CoroutineScope): wm.damage.core.windows.music.LocalMusicLibrary? = try {
    val lib = cfg.musicLibrary(scope)
    lib.db.migrate()
    cfg.mediaServer(lib).start()
    scope.launch(Dispatchers.IO) { try { lib.refreshCatalog(force = true) } catch (e: Exception) { /* the state line says */ } }
    lib
} catch (e: Exception) {
    Log.e("damage", "music library unavailable — Postgres/Qdrant/the cache did not come up; the music channel is not served", e)
    null
}

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
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val tmux = wm.damage.core.windows.tmux.LocalTmuxProvider(cfg.tmuxHostList(), cfg.tmuxConfig(), scope)
    // host-only still syncs (§19.2): the store is the data, no shell needed
    val store = Persistence(Path.of(cfg.dataDir).resolve("state.json"))
    store.load()
    val filesProvider = wm.damage.core.windows.files.LocalFilesProvider(
        Path.of(cfg.booksDir), Path.of(cfg.dataDir).resolve("trash"), AwtImages())
    val themeIcons = ThemeIcons(AwtImages(), Path.of(cfg.dataDir).resolve("icons"))
    val torrents = cfg.torrentsProvider(scope)
    val music = startMusic(cfg, scope)
    val host = ContentHostServer(LocalContent(Path.of(cfg.booksDir)), cfg.contentPort, cfg.token,
        tmux = tmux, sync = wm.damage.core.sync.SyncPeer(store),
        win = mapOf("files" to wm.damage.core.windows.files.FilesService(filesProvider),
            "torrents" to wm.damage.core.windows.torrents.TorrentsService(torrents)) +
            (music?.let { mapOf("music" to wm.damage.core.windows.music.MusicService(it)) } ?: emptyMap()),
        icons = themeIcons)
    host.start()
    Log.i("damage", "content host only — serving ${cfg.booksDir} + tmux + sync + files + torrents + music + icons on :${cfg.contentPort}; Ctrl-C to stop")
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
    /** The process-wide tmux provider (outlives stack rebuilds; also serves
     *  the phone through the content host). Null only in tools that need no
     *  tmux window. */
    private val tmux: wm.damage.core.windows.tmux.TmuxProvider? = null,
    /** The ONE process-wide state store (§19.2): shared with the content
     *  host's sync channel, so a stack and the sync peer see the same
     *  records. Null builds a private store (tools). */
    private val sharedStore: Persistence? = null,
    /** The process-wide filesystem provider (Files window; also served to the
     *  phone through the content host). Null in tools. */
    private val files: wm.damage.core.windows.files.FilesProvider? = null,
    /** Theme icons (2026-09-01) — process-wide, also serves the phone. */
    private val themeIcons: ThemeIcons? = null,
    /** The process-wide torrents provider (TORRENTS.md) — qBittorrent + the
     *  tracker; also served to the phone through the content host. */
    private val torrents: wm.damage.core.windows.torrents.TorrentsProvider? = null,
    /** The process-wide music library (MUSIC.md) — the local window mirrors
     *  the phone's player over the synced record; null in tools. */
    private val music: wm.damage.core.windows.music.MusicLibrary? = null,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private fun ble(): Transport = BlueZTransport(BlueZDbus(), scope,
        cachedAddresses = { Config.load().let { it.leftAddress.ifEmpty { null } to it.rightAddress.ifEmpty { null } } },
        rememberAddresses = { l, r -> Config.store(Config.load().copy(leftAddress = l, rightAddress = r)) })

    private fun remote(): Transport =
        RemoteTransportClient(remoteHost ?: cfg.phoneHost, cfg.transportPort, cfg.token, scope)

    val transport: Transport = when (mode) {
        "sim" -> SimTransport(GlassFirmwareSim(), scope)
        "ble" -> ble()
        "remote" -> remote()
        // "auto" builds NO stack: it is the standby policy in runShell (§19.2)
        // — a stack only exists while the APK is not available
        else -> throw IllegalArgumentException("unknown stack mode '$mode' (sim | ble | remote; auto is the standby policy, not a stack)")
    }
    val shell: Shell
    val keeper: ShellKeeper

    init {
        val dataDir = Path.of(cfg.dataDir)
        val persistence = sharedStore ?: Persistence(dataDir.resolve("state.json"))
        shell = Shell(text, transport, persistence, dataDir.resolve("journal.jsonl"), scope)
        shell.iconSource = themeIcons
        val content = LocalContent(Path.of(cfg.booksDir))
        shell.register(ReaderWindow(text, content, scope, AwtImages()))
        tmux?.let { tmuxWindow = wm.damage.core.windows.tmux.TmuxWindow(text, it, scope).also(shell::register) }
        files?.let {
            shell.register(wm.damage.core.windows.files.FilesWindow(text, it, scope, AwtImages(),
                initialBooksDir = cfg.booksDir))
        }
        torrents?.let { torrentsWindow = wm.damage.core.windows.torrents.TorrentsWindow(text, it, scope).also(shell::register) }
        // Music (MUSIC.md §3.1): the desktop shell MIRRORS the phone's player
        // through the synced record and cannot play — it says so loudly
        music?.let { musicWindow = wm.damage.core.windows.music.MusicWindow(text, it,
            wm.damage.core.windows.music.MirrorMusicPlayer(), scope, mirror = true).also(shell::register) }
        shell.hostSettings = listOf(
            HostSetting("Target", MODES, current = { mode }, apply = { v -> onSwitch(v) }),
        )
        keeper = ShellKeeper(shell, transport, scope, onStatus = onStatus,
            onTerminal = { reason -> Log.e("damage", "the $mode transport ended for good: $reason") })
    }

    private var tmuxWindow: wm.damage.core.windows.tmux.TmuxWindow? = null

    private var torrentsWindow: wm.damage.core.windows.torrents.TorrentsWindow? = null

    private var musicWindow: wm.damage.core.windows.music.MusicWindow? = null

    fun start() = keeper.start()

    suspend fun stop() {
        try {
            keeper.stop()
        } finally {
            try {
                // the providers outlive this stack: detach even when the stop
                // threw, or the leak returns on the failure path (R2-W12)
                tmuxWindow?.detach()
                torrentsWindow?.detach()   // a leaked listener fed a dead shell's queue every poll (P1)
                musicWindow?.detach()
            } finally {
                // and the stack's own resources go the same way (R3-K3): its
                // coroutines, and the BlueZ handler on the process-wide bus
                scope.cancel()
                val all = (transport as? PathTransport)?.paths?.map { it.transport } ?: listOf(transport)
                for (t in all) (t as? BlueZTransport)?.close()
            }
        }
    }

    fun statusLine(): String {
        val st = transport.state.value
        val link = if (!st.connected) "no link" else if (st.leaseHeld) "link + lease" else "link, no lease"
        val doing = st.detail.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""
        val div = shell.lastDivergence?.let { " · DIVERGE $it" } ?: ""
        return "${st.transportName}$doing · $link · ${st.ackMsEma.toInt()} ms · ${(st.bytesPerSecEma / 1000).toInt()} K/s · " +
            "${keeper.state.name.lowercase()}: ${keeper.lastReason}$div"
    }

    companion object {
        val MODES = listOf("auto", "sim", "ble", "remote")
    }
}

private fun runShell(cfg: Config, mode: String, remoteHost: String?, preview: Boolean = true): Unit = runBlocking {
    // the current stack: written by the switch thread, read by the EDT, the
    // replica's threads and every client — a reference with visibility.
    // NULL is a legal steady state now (§19): standby with the phone driving.
    val stackRef = java.util.concurrent.atomic.AtomicReference<DesktopStack?>(null)
    fun stack() = stackRef.get()
    // content scaling moved INTO the style transforms (Style.kt, 2026-08-31)
    // — the adapter must not scale a second time
    val text = AwtText()
    val keeperStatus = java.util.concurrent.atomic.AtomicReference("starting")
    val switchNote = java.util.concurrent.atomic.AtomicReference("")
    val standbyNote = java.util.concurrent.atomic.AtomicReference("standby: probing the phone")
    val switching = java.util.concurrent.atomic.AtomicBoolean(false)
    // §19: `auto` IS the standby policy — the phone shell is primary; manual
    // modes (sim | ble | remote) disable it for the run of that mode
    val standbyOn = java.util.concurrent.atomic.AtomicBoolean(mode == "auto")

    // The ONE process-wide state store (§19.2): the sync channel and every
    // stack share it, so records agree by construction. Loaded now — standby
    // must serve real records with no shell running.
    val store = Persistence(Path.of(cfg.dataDir).resolve("state.json"))
    store.load()
    val syncPeer = wm.damage.core.sync.SyncPeer(store, applier = { k, v, t ->
        val sh = stackRef.get()?.shell
        if (sh == null || !sh.postSync(k, v, t)) store.tryApplyRemote(k, v, t)
    })

    // The process-wide tmux provider: the local shell's window and the phone
    // (through the content host) both feed from it; it outlives stack switches.
    val tmuxScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val tmuxProvider = wm.damage.core.windows.tmux.LocalTmuxProvider(
        cfg.tmuxHostList(), cfg.tmuxConfig(), tmuxScope)

    // Theme icons from the desktop's own theme (2026-09-01, Papirus-Dark on
    // beardos) — one instance feeds the local shell AND the phone; a resolve
    // completing repaints whatever shell currently runs.
    val themeIcons = ThemeIcons(AwtImages(), Path.of(cfg.dataDir).resolve("icons"),
        onLoaded = { stackRef.get()?.shell?.requestRepaint() })

    // The process-wide filesystem provider: the local Files window and the
    // phone's (over the win channel) share it — trash manifest included.
    val filesProvider = wm.damage.core.windows.files.LocalFilesProvider(
        Path.of(cfg.booksDir), Path.of(cfg.dataDir).resolve("trash"), AwtImages())

    // The PC always serves its library + tmux + sync + files + icons — the
    // DATA PROVIDER role (§19.1) — whoever is driving the glasses.
    // The process-wide torrents provider (TORRENTS.md): the local window and
    // the phone's (over the win channel) share its poll loop and event log,
    // so a done-announcement is decided once
    val torrentsProvider = cfg.torrentsProvider(tmuxScope)
    // The process-wide music library (MUSIC.md): Postgres/Qdrant/the caches
    // + the media endpoint on :mediaPort; the local shell's window mirrors
    // the phone's player, the phone's library rides the win channel
    val musicLibrary = startMusic(cfg, tmuxScope)

    val host = ContentHostServer(LocalContent(Path.of(cfg.booksDir)), cfg.contentPort, cfg.token,
        tmux = tmuxProvider, sync = syncPeer,
        win = mapOf("files" to wm.damage.core.windows.files.FilesService(filesProvider),
            "torrents" to wm.damage.core.windows.torrents.TorrentsService(torrentsProvider)) +
            (musicLibrary?.let { mapOf("music" to wm.damage.core.windows.music.MusicService(it)) } ?: emptyMap()),
        icons = themeIcons)
    var hostBound = true
    try {
        host.start()
    } catch (e: Exception) {
        hostBound = false
        Log.e("damage", "content host failed to bind :${cfg.contentPort} (another instance?)", e)
    }

    lateinit var build: (String) -> DesktopStack
    // ONE mutation path for the running stack, shared by manual switches and
    // the standby policy. next == null tears down with no replacement (the
    // handback: the lease fails open and the phone's keeper reconnects).
    fun swapStack(next: String?, why: String) {
        if (!switching.compareAndSet(false, true)) {
            // a concurrent switch owns the slot — SAY the drop (R3#7): the
            // standby loop re-probes and retries by itself; a manual mode
            // change needs the operator to see it was not taken
            Log.w("damage", "switch to ${next ?: "standby"} dropped — another switch is in progress ($why)")
            return
        }
        Thread({
            try {
                // build the new stack FIRST: a mode this machine cannot build
                // (no BlueZ, say) must leave the running one driving
                val s = next?.let { build(it) }
                val old = stackRef.get()
                try {
                    runBlocking { old?.stop() }
                } catch (e: Exception) {
                    // the old stack's stop failing must not leave the NEW one unstarted
                    Log.e("damage", "the ${old?.mode} stack did not stop cleanly: ${e.message}", e)
                }
                stackRef.set(s)
                switchNote.set("")
                if (old != null || s != null)
                    Log.i("damage", "stack: ${old?.mode ?: "none"} → ${s?.mode ?: "none"} ($why)")
                s?.start()
            } catch (e: Exception) {
                Log.e("damage", "switch to ${next ?: "standby"} failed — the current stack keeps driving", e)
                switchNote.set("switch to ${next ?: "standby"} failed: ${e.message}")
            } finally {
                switching.set(false)
            }
        }, "damage-switch").start()
    }
    fun switchTo(next: String) {
        if (next == "auto") {
            if (standbyOn.compareAndSet(false, true))
                swapStack(null, "auto = standby (§19): the phone shell is primary")
            return
        }
        standbyOn.set(false)
        if (next == stack()?.mode) return
        swapStack(next, "manual mode")
    }
    build = { m -> DesktopStack(cfg, m, remoteHost, text, onStatus = { keeperStatus.set(it) },
        onSwitch = { switchTo(it) }, tmux = tmuxProvider, sharedStore = store,
        files = filesProvider, themeIcons = themeIcons, torrents = torrentsProvider, music = musicLibrary) }

    if (mode != "auto") {
        val first = build(mode)
        stackRef.set(first)
        first.start()
    }

    // THE STANDBY LOOP (§19.2). Probe the phone's seam on pacing: the APK
    // wanting the radio (or an APK too old to be asked) keeps the PC out;
    // absent-or-idle for STANDBY_DEBOUNCE consecutive probes starts a plain
    // BLE stack; the APK's return stops it — the handback.
    launch(Dispatchers.IO) {
        var absentStreak = 0
        var bleBroken = false
        while (isActive) {
            if (standbyOn.get()) {
                if (!hostBound) {
                    // another instance holds the content port (R3#8): it is
                    // probably ALSO running this standby policy — two claims
                    // would be two centrals on one adapter. Data-host-only.
                    standbyNote.set("standby: content port busy — another instance? not claiming")
                    delay(STANDBY_PROBE_MS)
                    continue
                }
                if (bleBroken && switchNote.get().isEmpty()) bleBroken = false   // a manual round-trip cleared it
                if (switchNote.get().startsWith("switch to ble failed") && !bleBroken) {
                    bleBroken = true
                    Log.e("damage", "PC-direct BLE unavailable on this machine — standing by as the data host only")
                    standbyNote.set("standby: data host only (no usable BLE)")
                }
                val r = wm.damage.core.transport.SeamProbe.probe(
                    remoteHost ?: cfg.phoneHost, cfg.transportPort, cfg.token)
                val phoneOwns = r is wm.damage.core.transport.SeamProbe.Result.Reachable &&
                    r.wantsRadio != false        // null = an APK too old to ask: conservative
                if (phoneOwns) {
                    absentStreak = 0
                    val d = (r as wm.damage.core.transport.SeamProbe.Result.Reachable).detail
                    standbyNote.set("standby: phone drives" + (d.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""))
                    if (stack() != null && standbyOn.get())
                        swapStack(null, "handback — the APK wants the radio")
                } else if (!bleBroken) {
                    absentStreak++
                    val why = if (r is wm.damage.core.transport.SeamProbe.Result.Reachable)
                        "the APK says Target≠glasses" else "the APK is unreachable"
                    if (stack() == null) {
                        if (absentStreak < STANDBY_DEBOUNCE) {
                            standbyNote.set("standby: $why ($absentStreak/$STANDBY_DEBOUNCE)")
                        } else {
                            standbyNote.set("standby → PC-direct BLE ($why)")
                            Log.i("damage", "standby: $why for $absentStreak probes — starting the PC BLE stack")
                            swapStack("ble", "standby: $why")
                        }
                    }
                }
            }
            delay(STANDBY_PROBE_MS)      // pacing between probes, not a timeout
        }
    }
    // an orderly end — the window's close button, Ctrl-C, a kill: the shell
    // saves its state and the transport releases the display
    val ending = java.util.concurrent.atomic.AtomicBoolean(false)
    val ended = java.util.concurrent.CountDownLatch(1)
    fun endOrderly() {
        if (!ending.compareAndSet(false, true)) {
            ended.await()           // a second close / a signal during the stop waits for it
            return
        }
        try { runBlocking { stack()?.stop() } } catch (e: Exception) { Log.w("damage", "stop at exit: ${e.message}") }
        finally { ended.countDown() }
    }
    Runtime.getRuntime().addShutdownHook(Thread({ endOrderly() }, "damage-shutdown"))

    fun note(): String = listOfNotNull(
        switchNote.get().ifEmpty { null },
        stack()?.shell?.lastDivergence?.let { "DIVERGE $it" },
    ).joinToString(" · ")
    // in standby with no stack, the status IS the standby narration
    fun statusText(): String {
        val base = stack()?.statusLine()
            ?: if (standbyOn.get()) standbyNote.get() else keeperStatus.get()
        val n = switchNote.get()
        return if (n.isEmpty()) base else "$n · $base"
    }
    // the 1x preview with mouse, on whatever the current stack mirrors
    if (preview) {
        Preview.show({ stack()?.transport?.mirror }, { t -> stack()?.transport?.injectInput(t) },
            { statusText() },
            onClose = { endOrderly(); kotlin.system.exitProcess(0) },
            onText = { line -> stack()?.transport?.injectText(line) })
    } else {
        Log.i("damage", "running WITHOUT the preview window (--no-preview): the phone screen and " +
            "the browser replica are the views; stop with SIGTERM (the shutdown hook saves state)")
    }
    // the browser replica on the tailnet
    val replica = ReplicaServer(cfg.replicaPort, cfg.token, { stack()?.transport?.mirror }, {
        val st = stack()?.transport?.state?.value
        ReplicaServer.Status(
            transport = st?.transportName ?: "none",
            connected = st?.connected ?: false, started = st?.started ?: false, leaseHeld = st?.leaseHeld ?: false,
            ackMs = st?.ackMsEma?.toInt() ?: 0, bytesPerSec = st?.bytesPerSecEma?.toInt() ?: 0,
            driver = stack()?.let { "PC shell (${it.mode})" }
                ?: if (standbyOn.get()) "standby (phone drives)" else "none",
            note = note().ifEmpty { statusText() },
        )
    }, onInput = { t -> stack()?.transport?.injectInput(t) },
        onText = { line -> stack()?.transport?.injectText(line) })
    try {
        replica.start()
    } catch (e: Exception) {
        Log.e("damage", "browser replica failed to bind :${cfg.replicaPort}", e)
    }

    Log.i("damage", if (mode == "auto")
        "standby up (§19: the phone shell is primary) — data host on :${cfg.contentPort}, probing ${remoteHost ?: cfg.phoneHost}:${cfg.transportPort}"
    else
        "shell up in $mode mode — books from ${cfg.booksDir}, journal in ${cfg.dataDir}")
    Log.i("damage", "preview: wheel/click/right-click/hold = ring · Tab lens · B both")
    Log.i("damage", "browser replica: http://<this-host>:${cfg.replicaPort}/?token=${cfg.token}")
    kotlinx.coroutines.awaitCancellation()
}

/** Standby pacing (§19.2): probe cadence, and how many consecutive
 *  absent-or-idle probes it takes before the PC starts its own BLE stack —
 *  the debounce that rides out an APK restart or update. */
private const val STANDBY_PROBE_MS = 5_000L
private const val STANDBY_DEBOUNCE = 2
