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
import wm.damage.core.comp.Journal
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
import wm.damage.core.util.InstanceLock
import wm.damage.core.util.Log
import wm.damage.core.util.UncaughtRecord
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
 * session after every link end. Other entry points, one at a time:
 *
 *   --selfcheck · --snapshot [DIR] · --epub-check [DIR] · --music-check · --games-check [deep]
 *   --feed-check [DIR] [live] · --card-render [DIR]
 *   --host-only · --ble-info
 *   --help | -h
 *
 * `--no-preview` runs any transport mode headless (no Swing, no X) — the
 * all-day OpenRC service mode (DAILY.md); views are the replicas.
 *
 * The argument list is read whole by [parseArgs] before anything starts
 * (0.64 T0, 2026-11-11). Until then a tool was picked by `"--flag" in args`
 * and every other list went to the shell, so `--help`, a mistyped tool flag
 * or `--version` started a second shell in `auto` with the preview on the
 * real data directory, beside the running service. Now a list that is not
 * understood prints one line naming the argument and [USAGE], and exits 2.
 * The flags are [CLI_FLAGS]. `CliTest` fails on a flag in that list with no
 * branch in [parseArgs] or no line in [USAGE], and on a flag in [USAGE] that
 * is not in the list; a branch for a flag the list does not name is refused
 * as unknown, so it cannot be reached before the other two exist.
 */
fun main(args: Array<String>) {
    val code = runCli(args, System.out, System.err) { cli ->
        // --no-preview: the all-day service mode (DAILY.md) — no Swing window, no
        // X needed; the phone screen and the browser replica are the views. Set
        // BEFORE any AWT class loads so the JVM commits to headless.
        if (cli.headless) System.setProperty("java.awt.headless", "true")
        val cfg = Config.load()
        when (cli) {
            is Cli.SelfCheck -> SelfCheck.run(cfg)
            // 0.65 P7 item 9 (green-gate GG-8): DIR is read; the config's booksDir only when none is given
            is Cli.EpubCheck -> epubCheck(cli.dir?.let { Path.of(it) } ?: Path.of(cfg.booksDir))
            is Cli.MusicCheck -> MusicCheck.run(cfg)
            is Cli.GamesCheck -> GamesCheck.run(cfg, deep = cli.deep)
            is Cli.FeedCheck -> FeedCheck.run(cfg, live = cli.live, fixtures = Path.of(cli.fixtures))
            is Cli.CardRender -> CardSheet.run(cfg, Path.of(cli.dir))
            is Cli.Snapshot -> Snapshot.run(cfg, Path.of(cli.dir))
            is Cli.BleInfo -> bleInfo()
            is Cli.HostOnly -> runBlocking { hostOnly(cfg) }
            is Cli.Shell -> runShell(cfg, cli.mode, cli.remoteHost, preview = cli.preview)
        }
    }
    if (code != 0) kotlin.system.exitProcess(code)
}

/** What an argument list asks of the program — [parseArgs]'s answer. */
internal sealed interface Cli {
    /** `--help` / `-h`: [USAGE] on stdout, exit 0, nothing else. */
    data object Usage : Cli

    /** A list that is not understood. [what] is the one line that names the argument; [USAGE]
     *  follows it on stderr and the exit status is [CLI_REFUSED]. Nothing else starts. */
    data class Refused(val what: String) : Cli

    /** An entry point that runs. [headless]: `--no-preview` was given, so `main` sets
     *  `java.awt.headless` before the config is read or an AWT class loads. */
    sealed interface Run : Cli { val headless: Boolean }

    /** The shell (`runShell`): [mode] is one of [DesktopStack.MODES]; [remoteHost] is the phone's
     *  host for `remote` and for the standby probe, null = the config's `phoneHost`. */
    data class Shell(val mode: String, val remoteHost: String?, val preview: Boolean) : Run {
        override val headless: Boolean get() = !preview
    }
    data class SelfCheck(override val headless: Boolean = false) : Run
    data class Snapshot(val dir: String, override val headless: Boolean = false) : Run
    /** [dir] null = no directory was given (the config's `booksDir`). */
    data class EpubCheck(val dir: String?, override val headless: Boolean = false) : Run
    data class MusicCheck(override val headless: Boolean = false) : Run
    data class GamesCheck(val deep: Boolean, override val headless: Boolean = false) : Run
    data class FeedCheck(val fixtures: String, val live: Boolean, override val headless: Boolean = false) : Run
    data class CardRender(val dir: String, override val headless: Boolean = false) : Run
    data class BleInfo(override val headless: Boolean = false) : Run
    data class HostOnly(override val headless: Boolean = false) : Run

    companion object {
        /** Where a tool writes or reads when no directory follows its flag — the values `main`
         *  held inline before [parseArgs]. */
        const val SNAPSHOT_DIR = "snapshots"
        const val CARD_DIR = "design/shots/cards"
        const val FEED_FIXTURES = "core/src/test/resources/feed"
    }
}

/** The exit status of a refused argument list (the common "usage" status). */
internal const val CLI_REFUSED = 2

/** Every flag [parseArgs] knows. Anything else on the command line is the value of one of these or
 *  is refused. `CliTest` holds this list, [parseArgs] and [USAGE] to each other. */
internal val CLI_FLAGS: List<String> = listOf(
    "--transport", "--remote", "--sim", "--ble", "--no-preview",
    "--selfcheck", "--snapshot", "--epub-check", "--music-check", "--games-check", "--feed-check", "--card-render",
    "--host-only", "--ble-info",
    "--help", "-h")

/** What `--help` prints, and what follows the line that names a refused argument. */
internal val USAGE: String = """
    usage: desktop [--transport MODE [--remote HOST] | --remote HOST | --sim | --ble] [--no-preview]
           desktop ENTRY [--no-preview]
           desktop --help | -h

    Without an ENTRY the shell starts; without any argument, in auto with the preview window.
      --transport MODE    auto    standby (the default): the phone drives, this serves the data
                          sim     the firmware model in-process (development)
                          ble     PC-direct BLE
                          remote  drive through the phone's transport
      --sim, --ble        short for --transport sim, --transport ble
      --remote HOST       the phone at HOST; without --transport it means --transport remote
      --no-preview        headless, no window (the service runs --transport auto --no-preview)

    ENTRY, one at a time:
      --selfcheck                the scripted whole-stack check on the simulator
      --snapshot [DIR]           lens-truth PNGs of every surface (default: ${Cli.SNAPSHOT_DIR})
      --epub-check [DIR]         parse every book in DIR (default: the config's booksDir); exit 1 on a
                                 failure, on zero books or on a DIR that is not a directory
      --music-check              the real music library, read-only
      --games-check [deep]       the Hold'em ecology in memory; deep is the long run
      --feed-check [DIR] [live]  the feed engine over fixtures (default: ${Cli.FEED_FIXTURES});
                                 live fetches each source once
      --card-render [DIR]        the card sheets at 1x (default: ${Cli.CARD_DIR})
      --host-only                the content host alone, no shell
      --ble-info                 the BlueZ adapter and the devices it knows, no discovery

    An argument that is not listed here is refused with exit $CLI_REFUSED, and nothing starts.
    The shell and --host-only exit ${InstanceLock.EXIT_HELD} when another process holds the data directory
    (its ${InstanceLock.FILE}), and nothing starts.
""".trimIndent()

/**
 * Reads an argument list into what `main` will do. PURE: no I/O, no AWT, no `Config.load()` — `main`
 * calls it before anything else, and `CliTest` calls it with every list it can build.
 *
 * The rule: an accepted list means what it meant to the `"--flag" in args` chain this replaced
 * (`CliTest.anAcceptedLineMeansWhatItMeantBefore` holds that against a transcription of the chain),
 * and a list that chain would have run while dropping part of it is refused —
 *  - an argument that is no flag and no flag's value (the old chain started the shell);
 *  - two entry points, or a shell flag beside one (it ran the first in its own order);
 *  - two statements of the mode (`--transport` won over `--remote` over `--sim` over `--ble`);
 *  - `--transport` with no mode or `--remote` with no host (it went on as if the flag were absent);
 *  - a mode that is not one of [DesktopStack.MODES] (it bound the ports, then the stack threw
 *    [unknownStackMode]; the refusal is that sentence);
 *  - a flag given twice (it read the first).
 * One reading changes on purpose: a flag is never another flag's value. `--snapshot --no-preview`
 * wrote into a directory named `--no-preview`; it now writes headless into the default.
 * A value follows its flag directly; `deep` and `live` used to be read anywhere in the list.
 */
internal fun parseArgs(args: Array<String>): Cli {
    if (args.any { it == "--help" || it == "-h" }) return Cli.Usage
    // a flag is never taken as a value (value(), below), so this is exactly "the flag was given"
    val headless = "--no-preview" in args
    val modes = DesktopStack.MODES.joinToString(" | ")
    val seen = HashSet<String>()
    var entry: Pair<String, Cli.Run>? = null     // the entry point asked for, under the flag that named it
    var shellOnly: String? = null                // the first flag only the shell reads
    var transport: String? = null
    var remote: String? = null
    var i = 0
    // the argument after the flag at i when it is that flag's value: there, not blank, not a flag
    fun value(): String? = args.getOrNull(i + 1)?.takeIf { it.isNotBlank() && !it.startsWith("-") }?.also { i++ }
    fun word(w: String): Boolean = (args.getOrNull(i + 1) == w).also { if (it) i++ }
    while (i < args.size) {
        val a = args[i]
        if (a !in CLI_FLAGS) return Cli.Refused("unknown argument '$a'")
        if (!seen.add(a)) return Cli.Refused("$a is given twice")
        val run: Cli.Run? = when (a) {
            "--selfcheck" -> Cli.SelfCheck(headless)
            "--snapshot" -> Cli.Snapshot(value() ?: Cli.SNAPSHOT_DIR, headless)
            "--epub-check" -> Cli.EpubCheck(value(), headless)
            "--music-check" -> Cli.MusicCheck(headless)
            "--games-check" -> Cli.GamesCheck(deep = word("deep"), headless)
            "--feed-check" -> {
                // [DIR] [live]: `live` first is the word, never a directory of that name (as before)
                val liveFirst = word("live")
                val dir = if (liveFirst) null else value()
                Cli.FeedCheck(dir ?: Cli.FEED_FIXTURES, live = liveFirst || (dir != null && word("live")), headless)
            }
            "--card-render" -> Cli.CardRender(value() ?: Cli.CARD_DIR, headless)
            "--host-only" -> Cli.HostOnly(headless)
            "--ble-info" -> Cli.BleInfo(headless)
            "--transport" -> {
                val m = value() ?: return Cli.Refused("--transport needs a mode ($modes)")
                // the stack's own sentence, said here before anything starts: runShell said it
                // only from DesktopStack's constructor, after the ports were bound
                if (m !in DesktopStack.MODES) return Cli.Refused(unknownStackMode(m))
                transport = m
                null
            }
            "--remote" -> { remote = value() ?: return Cli.Refused("--remote needs a host"); null }
            "--sim", "--ble", "--no-preview" -> null
            // CLI_FLAGS names a flag this function has no branch for
            else -> return Cli.Refused("unknown argument '$a'")
        }
        if (run != null) {
            entry?.let { return Cli.Refused("${it.first} and $a: one entry point at a time") }
            entry = a to run
        } else if (a != "--no-preview" && shellOnly == null) shellOnly = a
        i++
    }
    entry?.let { (flag, run) ->
        shellOnly?.let { return Cli.Refused("$it does not apply to $flag") }
        return run
    }
    // the mode is said once: --transport MODE, or one short form. --remote HOST alone is a short
    // form (remote); beside --transport it only names the host, as it always did
    val said = listOfNotNull("--transport".takeIf { transport != null },
        "--remote".takeIf { remote != null && transport == null },
        "--sim".takeIf { it in seen }, "--ble".takeIf { it in seen })
    if (said.size > 1) return Cli.Refused("${said[0]} and ${said[1]}: the mode is said once " +
        "(--transport MODE beside --remote HOST keeps the host)")
    val mode = transport ?: when (said.firstOrNull()) {
        "--remote" -> "remote"
        "--sim" -> "sim"
        "--ble" -> "ble"
        else -> "auto"
    }
    return Cli.Shell(mode, remote, preview = !headless)
}

/** The one sentence for a transport mode nothing here can run. [DesktopStack] throws it for a mode
 *  its `when` does not build; [parseArgs] refuses `--transport MODE` with it for a MODE outside
 *  [DesktopStack.MODES], so the command line never reaches that throw. */
internal fun unknownStackMode(mode: String): String =
    "unknown stack mode '$mode' (sim | ble | remote; auto is the standby policy, not a stack)"

/**
 * Acts on an argument list: [USAGE] for `--help`, the refusal and [USAGE] for a list [parseArgs]
 * does not understand, [start] for everything else. Returns the process's exit status. [start] is
 * not called for the first two, so when they return nothing was read, bound or drawn.
 */
internal fun runCli(args: Array<String>, out: java.io.PrintStream, err: java.io.PrintStream,
                    start: (Cli.Run) -> Unit): Int =
    when (val cli = parseArgs(args)) {
        Cli.Usage -> { out.println(USAGE); 0 }
        is Cli.Refused -> { err.println("desktop: ${cli.what}"); err.println(USAGE); CLI_REFUSED }
        is Cli.Run -> { start(cli); 0 }
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
     *  — loopback with the localhost auth exemption, so the credentials stay
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
    /** Feed (FEED.md §3.7, 2026-09-09): the sources (the day-one list when
     *  absent), the one user agent every fetch carries, and the engine's
     *  files (empty = `<dataDir>/feed`). Nothing here is a credential. */
    val feedSources: List<wm.damage.core.windows.feed.SourceCfg> = wm.damage.core.windows.feed.SourceCfg.DEFAULTS,
    val feedUserAgent: String = "damage-wm/0.1 (personal glasses client)",
    val feedDataDir: String = "",
    /** The setup page (`HANDOFF.md` §44, 2026-09-10): `/setup` + `/damage-apk`
     *  on the port and under the token G2CC's server used until it was
     *  retired, so the phone's bookmark and its link keep working. An empty
     *  `setupToken` means the replica token. */
    val setupPort: Int = 7300,
    val setupToken: String = "",
    /** Dev only (`HANDOFF.md` §73): the firmware model `--transport sim` runs against. 0 = the
     *  upstream build (contract 1, the 64 KiB cache, flat draws) — what a sim session has always
     *  been; 2 = a Damage contract-2 build (per-lens draws, 160 KiB, page staging), the build on
     *  the glasses, so a `glassdrive` walk of the simulator exercises the paths the phone runs.
     *  Nothing but the sim stack reads it; the service's standby and the BLE stack never do. */
    val simContract: Int = 0,
) {
    /** The PC-side feed engine (FEED.md §3.6): the configured sources on
     *  their pacer, articles extracted ahead, strips through AWT. */
    fun feedEngine(scope: CoroutineScope): wm.damage.core.windows.feed.FeedEngine =
        wm.damage.core.windows.feed.FeedEngine(
            feedSources,
            wm.damage.core.windows.feed.FeedStore(Path.of(feedDataDir.ifEmpty { "$dataDir/feed" })),
            wm.damage.core.windows.feed.PacedHttp(wm.damage.core.windows.feed.RealFeedHttp(feedUserAgent)),
            AwtImages(), scope, prefetchArticles = true, engineLabel = "PC")

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

    /** The setup page + the APK route on [setupPort] (SetupServer). */
    fun setupServer(): SetupServer = SetupServer(setupPort, setupToken.ifEmpty { token }, Path.of(dataDir).resolve("damage-wm.apk"))

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
            if (Files.exists(p)) {
                val cfg = try {
                    json.decodeFromString(serializer(), Files.readString(p))
                } catch (e: Exception) {
                    // 🔴 NEVER written back. An earlier version fell back to
                    // `Config()` and then stored it because the fresh token
                    // differed — so one stray comma in a hand edit replaced
                    // the whole file, tracker credentials and tmux hosts
                    // included, with defaults on the next start (review §28
                    // #4). The defaults run for THIS start only, under a
                    // token nothing else knows, and the file is left for the
                    // person to fix; the Python side (audio/enrich/
                    // damage_config.py) refuses outright for the same reason.
                    Log.e("config", "unreadable $p — running on DEFAULTS for this start and " +
                        "leaving the file untouched: fix it and restart", e)
                    return Config(token = newToken()).also { warnBadMusicRoots(it) }
                }
                val fixed = if (cfg.token.isEmpty()) cfg.copy(token = newToken()) else cfg
                if (fixed != cfg) store(fixed)
                warnBadMusicRoots(fixed)
                return fixed
            }
            val fresh = Config(token = newToken())
            store(fresh)
            warnBadMusicRoots(fresh)
            return fresh
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
private fun epubCheck(dir: Path) { kotlin.system.exitProcess(epubCheckStatus(dir) { println(it) }) }

/** `--epub-check`'s work and its exit status, without the exit (a desktop test calls it). A DIR that is not a
 *  directory and a shelf of zero books are not a pass (0.65 P7 item 9, GG-8): `LocalContent` lists either as
 *  empty, and the step said 0 over a shelf it never read. */
internal fun epubCheckStatus(dir: Path, say: (String) -> Unit): Int {
    if (!Files.isDirectory(dir)) { say("$dir is not a directory — no books read"); return 1 }
    val lib = LocalContent(dir).library()
    say("${lib.size} books in $dir")
    if (lib.isEmpty()) { say("no books in $dir — nothing was checked"); return 1 }
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
            say("  OK   ${b.title} — ${book.text.length / 1024}K chars, " +
                "${book.chapters.size} chapters, " +
                "${System.currentTimeMillis() - t0}ms" +
                (if (book.author.isNotEmpty()) " · ${book.author}" else "") + imgNote)
        } catch (e: Exception) {
            bad++
            say("  FAIL ${b.file}: ${e.message}")
        }
    }
    if (imgTotal > 0) say("images across the shelf: $imgDecoded/$imgTotal decode")
    return if (bad > 0) 1 else 0
}

/** Adapter enumeration only — a D-Bus read, no discovery, no connection: the
 *  one BlueZ check that is allowed before first light (HANDOFF.md §8.1 #2). */
/** The setup page next to the content host — loud, never fatal, on a bind
 *  failure (another instance, or G2CC's retired server started by hand). */
private fun startSetup(cfg: Config, scope: CoroutineScope): SetupServer? = try {
    cfg.setupServer().also { s -> bindOrRetry(scope, "setup page", cfg.setupPort) { s.start() } }
} catch (e: Exception) {
    Log.e("damage", "setup page failed to start on :${cfg.setupPort} (G2CC's server still up?)", e)
    null
}

/** §72 (2026-11-08): start a server, or, when its port is taken (another instance still holding
 *  it, as the 10-20 JVM stuck in its own SIGTERM handler did on 2026-11-07), say so once and
 *  retry on pacing until it binds. The alternative was to run "started" while serving nothing.
 *  Pacing, not a timeout: the wait ends when the port is free, and the init script's KILL step
 *  frees it. A failure that is not a busy port propagates to the caller's handler, as before.
 *  Returns true when bound now; [onBound] runs when a later retry binds. */
internal fun bindOrRetry(scope: CoroutineScope, what: String, port: Int, onBound: () -> Unit = {},
                         retryMs: Long = BIND_RETRY_MS, start: () -> Unit): Boolean {
    try {
        start()
        return true
    } catch (e: java.net.BindException) {
        Log.e("damage", "$what could not bind :$port (${e.message}) — another instance? retrying every ${retryMs / 1000.0} s until the port is free")
    }
    scope.launch(Dispatchers.IO) {
        while (isActive) {
            delay(retryMs)             // pacing between attempts
            try {
                start()
                Log.i("damage", "$what bound :$port")
                onBound()
                return@launch
            } catch (e: java.net.BindException) {
                // still taken: said once above
            } catch (e: Exception) {
                Log.e("damage", "$what: retrying the bind failed for another reason — no further retries", e)
                return@launch
            }
        }
    }
    return false
}

/** The music library + its media endpoint, or null (loudly) when Postgres
 *  is not reachable at start — the rest of the host keeps serving. The
 *  catalog builds on the first request (or the first driver's cursor). */
private fun startMusic(cfg: Config, scope: CoroutineScope): wm.damage.core.windows.music.LocalMusicLibrary? = try {
    val lib = cfg.musicLibrary(scope)
    lib.db.migrate()
    cfg.mediaServer(lib).also { m -> bindOrRetry(scope, "media endpoint", cfg.mediaPort) { m.start() } }
    scope.launch(Dispatchers.IO) {
        // the rule-managed playlists first (MUSIC.md §9.8 — G2CC's boot refresh,
        // ours since 2026-09-10), then the catalog picks up what moved
        try { lib.adaptive.refreshAll("host start") }
        catch (e: Exception) { Log.e("damage", "adaptive playlists refresh at start failed (the library is served as it is)", e) }
        try { lib.refreshCatalog(force = true) } catch (e: Exception) { /* the state line says */ }
    }
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
    // R2 (RE-3): first, the data directory — the same store, ports and engines as the service's; a
    // directory another process holds ends this start here (exit 4, one stderr line)
    InstanceLock.claimOrExit(Path.of(cfg.dataDir))
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
    val feed = cfg.feedEngine(scope)
    val host = ContentHostServer(LocalContent(Path.of(cfg.booksDir)), cfg.contentPort, cfg.token,
        tmux = tmux, sync = wm.damage.core.sync.SyncPeer(store),
        win = mapOf("files" to wm.damage.core.windows.files.FilesService(filesProvider),
            "torrents" to wm.damage.core.windows.torrents.TorrentsService(torrents),
            "feed" to wm.damage.core.windows.feed.FeedService(feed)) +
            (music?.let { mapOf("music" to wm.damage.core.windows.music.MusicService(it)) } ?: emptyMap()),
        icons = themeIcons)
    bindOrRetry(scope, "content host", cfg.contentPort) { host.start() }   // §72, as in the standby host
    startSetup(cfg, scope)
    Log.i("damage", "content host only — serving ${cfg.booksDir} + tmux + sync + files + torrents + music + feed + icons on :${cfg.contentPort}; Ctrl-C to stop")
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
    private val text: wm.damage.core.text.TextRasterizer,
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
    /** The process-wide feed engine (FEED.md §3.6) — the local window reads
     *  it directly; the phone reads it over the window channel. Null in tools. */
    private val feed: wm.damage.core.windows.feed.FeedProvider? = null,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private fun ble(): Transport = BlueZTransport(BlueZDbus(), scope,
        cachedAddresses = { Config.load().let { it.leftAddress.ifEmpty { null } to it.rightAddress.ifEmpty { null } } },
        rememberAddresses = { l, r -> Config.store(Config.load().copy(leftAddress = l, rightAddress = r)) })

    private fun remote(): Transport =
        RemoteTransportClient(remoteHost ?: cfg.phoneHost, cfg.transportPort, cfg.token, scope)

    val transport: Transport = when (mode) {
        "sim" -> SimTransport(GlassFirmwareSim().also { if (cfg.simContract >= 2) it.damageContract = cfg.simContract }, scope)
        "ble" -> ble()
        "remote" -> remote()
        // "auto" builds NO stack: it is the standby policy in runShell (§19.2)
        // — a stack only exists while the APK is not available
        else -> throw IllegalArgumentException(unknownStackMode(mode))
    }
    val shell: Shell
    val keeper: ShellKeeper

    init {
        val dataDir = Path.of(cfg.dataDir)
        val persistence = sharedStore ?: Persistence(dataDir.resolve("state.json"))
        shell = Shell(text, transport, persistence, dataDir.resolve("journal.jsonl"), scope)
        shell.buildTag = "desktop " + (DesktopStack::class.java.getResource("/damage-build.txt")?.readText()?.trim() ?: "unstamped")
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
        // Games (HOLDEM.md): pure Kotlin, no host — the first window in this
        // system that needs nothing outside itself
        shell.register(wm.damage.core.windows.games.GamesWindow(text, scope))
        // Feed (FEED.md): the PC's engine, read directly
        feed?.let { feedWindow = wm.damage.core.windows.feed.FeedWindow(text, it, scope).also(shell::register) }
        shell.hostSettings = listOf(
            HostSetting("Target", MODES, current = { mode }, apply = { v -> onSwitch(v) }),
        )
        keeper = ShellKeeper(shell, transport, scope, onStatus = onStatus,
            onTerminal = { reason -> Log.e("damage", "the $mode transport ended for good: $reason") })
    }

    private var tmuxWindow: wm.damage.core.windows.tmux.TmuxWindow? = null

    private var torrentsWindow: wm.damage.core.windows.torrents.TorrentsWindow? = null

    private var musicWindow: wm.damage.core.windows.music.MusicWindow? = null

    private var feedWindow: wm.damage.core.windows.feed.FeedWindow? = null

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
                feedWindow?.detach()
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
    // 0.64 (`HANDOFF.md` §75.1), FIRST, before the store, the host and any stack — no journal writer
    // and no server exists yet in this process:
    //   0. the data directory is claimed (R2, RE-3): another process holding it (the service, when
    //      this is a dev session or a stray start) ends this start here with one stderr line and
    //      exit 4 — before anything below appends to its journal, renames it or takes its record
    //   1. the uncaught-error record (on this JVM an uncaught throwable ends its thread, not the
    //      process, and an OutOfMemoryError never reaches a handler: -XX:+ExitOnOutOfMemoryError)
    //   2. the journal past 64 MiB is set aside; nothing is deleted
    //   3. the last run's record, journaled and renamed, then said once
    // Only here and in the phone's ShellService.onCreate: `--host-only` has no journal writer, and a
    // tool (`--selfcheck`, `--snapshot`, the `--*-check` entries) must never rename the service's
    // file (`tools/check_apk_wiring.py` holds the list of callers). A dev session on the real data
    // directory while the service runs is refused at step 0; with the service stopped it IS a start:
    // it rotates the journal and picks up the last run's record.
    val dataDir = Path.of(cfg.dataDir)
    InstanceLock.claimOrExit(dataDir)
    val journalFile = dataDir.resolve("journal.jsonl")
    UncaughtRecord.install(dataDir, "desktop " + (DesktopStack::class.java.getResource("/damage-build.txt")?.readText()?.trim() ?: "unstamped"))
    Journal.rotateAtStart(journalFile)
    // the start's `process` note carries the archives' count and total (R2 item 26), in both arms
    val archives = Journal.archivesSaid(journalFile)
    val installed = UncaughtRecord.installedLine()
    if (installed != null) {
        Log.i("uncaught", installed)
        Journal.appendNote(journalFile, "process", "$installed; ${journalFile.fileName}: $archives")
    } else {
        val line = "the uncaught-error record is NOT installed in this process — an uncaught error would leave no record"
        Log.e("uncaught", line)
        Journal.appendNote(journalFile, "process", "$line; ${journalFile.fileName}: $archives")
    }
    UncaughtRecord.takePending(dataDir, journalFile)?.let { Log.e("uncaught", it) }

    // the current stack: written by the switch thread, read by the EDT, the
    // replica's threads and every client — a reference with visibility.
    // NULL is a legal steady state now (§19): standby with the phone driving.
    val stackRef = java.util.concurrent.atomic.AtomicReference<DesktopStack?>(null)
    fun stack() = stackRef.get()
    // content scaling moved INTO the style transforms (Style.kt, 2026-08-31)
    // — the adapter must not scale a second time
    val text = wm.damage.core.comp.CachedText(AwtText())   // §40: every draw passes through the recorder
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
    // The process-wide feed engine (FEED.md §3.6): the configured sources on
    // their pacer, articles ahead, strips through AWT; the local window reads
    // it directly and the phone through the window channel
    val feedEngine = cfg.feedEngine(tmuxScope)

    val host = ContentHostServer(LocalContent(Path.of(cfg.booksDir)), cfg.contentPort, cfg.token,
        tmux = tmuxProvider, sync = syncPeer,
        win = mapOf("files" to wm.damage.core.windows.files.FilesService(filesProvider),
            "torrents" to wm.damage.core.windows.torrents.TorrentsService(torrentsProvider),
            "feed" to wm.damage.core.windows.feed.FeedService(feedEngine)) +
            (musicLibrary?.let { mapOf("music" to wm.damage.core.windows.music.MusicService(it)) } ?: emptyMap()),
        icons = themeIcons)
    // §72: a busy port is retried on pacing until it frees (the stuck 10-20 JVM held all four
    // on 2026-11-07, and the instance started beside it served nothing while "started")
    val hostBound = java.util.concurrent.atomic.AtomicBoolean(false)
    try {
        if (bindOrRetry(this, "content host", cfg.contentPort, onBound = { hostBound.set(true) }) { host.start() })
            hostBound.set(true)
    } catch (e: Exception) {
        Log.e("damage", "content host failed to start on :${cfg.contentPort}", e)
    }
    startSetup(cfg, this)

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
        files = filesProvider, themeIcons = themeIcons, torrents = torrentsProvider, music = musicLibrary, feed = feedEngine) }

    if (mode != "auto") {
        val first = build(mode)
        stackRef.set(first)
        first.start()
    }

    // THE STANDBY LOOP (§19.2). Probe the phone's seam on pacing: the APK
    // wanting the radio (or an APK too old to be asked) keeps the PC out;
    // absent-or-idle for STANDBY_DEBOUNCE consecutive probes AND both arms
    // advertising to this adapter (§47) starts a plain BLE stack; the APK's
    // return stops it — the handback.
    val standbyScan = StandbyScan(cfg)
    // Never closed: it holds the file open for the life of the process. Were a second process on
    // this data directory to set the file aside at its start, these notes would follow the bytes
    // into the archive until this process ends (`Journal.rotateAtStart`). The daily setup never
    // runs two (the service is stopped before a dev session).
    val standbyJournal = Journal(Path.of(cfg.dataDir).resolve("journal.jsonl"))
    launch(Dispatchers.IO) {
        var absentStreak = 0
        var bleBroken = false
        var notAdvertisingSaid = false
        var choseNotToSaid = false
        var giveBacks = 0          // §72: give-backs in a row, for the claim's backoff
        while (isActive) {
            if (standbyOn.get()) {
                if (!hostBound.get()) {
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
                val phone = StandbyPolicy.phoneOf(r)
                if (phone == StandbyPolicy.Phone.DRIVES) {
                    if (absentStreak > 0) {
                        // §47: the return is a fact for the journal too — the
                        // relay dropped the phone for this long
                        val gone = absentStreak * STANDBY_PROBE_MS / 1000
                        Log.i("damage", "standby: the phone is back after $absentStreak missed probe(s) (~$gone s)")
                        standbyJournal.note("keeper", "standby: the phone is back after $absentStreak missed probe(s) (~$gone s)")
                    }
                    absentStreak = 0
                    notAdvertisingSaid = false
                    choseNotToSaid = false
                    giveBacks = 0
                    standbyScan.stop()
                    val d = (r as wm.damage.core.transport.SeamProbe.Result.Reachable).detail
                    standbyNote.set("standby: phone drives" + (d.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""))
                    if (stack() != null && standbyOn.get())
                        swapStack(null, "handback — the APK wants the radio")
                } else if (phone == StandbyPolicy.Phone.CHOSE_NOT_TO) {
                    // §72, Adam's ruling D1 (2026-11-07): a REACHABLE APK on Target≠glasses chose
                    // to leave the glasses alone (Sim: reading on the phone without them,
                    // 2026-11-06). A choice is not an absence: never a claim.
                    absentStreak = 0
                    notAdvertisingSaid = false
                    standbyScan.stop()
                    standbyNote.set(if (stack() != null) "standby: the APK chose Target≠glasses — the PC keeps the claim it already holds"
                        else "standby: the APK chose Target≠glasses — the glasses are left alone")
                    if (!choseNotToSaid) {
                        choseNotToSaid = true
                        Log.i("damage", "standby: the APK is reachable and says Target≠glasses — a choice, not an absence: not claiming (§72)")
                        standbyJournal.note("keeper", "standby: the APK says Target≠glasses — not claiming (§72)")
                    }
                } else if (!bleBroken) {
                    choseNotToSaid = false
                    absentStreak++
                    val why = "the APK is unreachable"
                    // §72: the debounce doubles per give-back in a row (StandbyPolicy.claimDebounce)
                    val debounce = StandbyPolicy.claimDebounce(giveBacks, STANDBY_DEBOUNCE, STANDBY_DEBOUNCE_CAP)
                    if (stack() == null) {
                        if (absentStreak < debounce) {
                            standbyNote.set("standby: $why ($absentStreak/$debounce)")
                        } else {
                            // §47 (2026-09-12): a phone that is only unreachable over
                            // the network is still driving the glasses — nine such
                            // claims in two days, every one handed straight back. The
                            // arms advertise only once the phone has let go of them.
                            // §72: and only an adapter that can SEE them may claim — a
                            // scan that cannot run (null) never claims (StandbyPolicy).
                            val seen = standbyScan.armsAdvertising()
                            if (StandbyPolicy.shouldClaim(phone, absentStreak, debounce, seen)) {
                                standbyNote.set("standby → PC-direct BLE ($why, both arms advertise)")
                                Log.i("damage", "standby: $why for $absentStreak probes and both arms advertise — starting the PC BLE stack")
                                standbyJournal.note("keeper", "standby claim: $why for $absentStreak probes, both arms advertising")
                                standbyScan.stop()
                                swapStack("ble", "standby: $why")
                            } else if (seen == null) {
                                // StandbyScan said why, once per cause
                                standbyNote.set("standby: $why ($absentStreak probes) — this adapter cannot scan; not claiming")
                            } else {
                                standbyNote.set("standby: $why ($absentStreak probes) — the arms are not advertising here; not claiming")
                                if (!notAdvertisingSaid) {
                                    notAdvertisingSaid = true
                                    Log.i("damage", "standby: $why for $absentStreak probes, but the arms are not advertising to this adapter — the phone still holds them; not claiming")
                                    standbyJournal.note("keeper", "standby: $why for $absentStreak probes — arms not advertising, not claiming")
                                }
                            }
                        }
                    }
                }
                // §72 fix 2: a standby claim whose starts keep failing goes back to standby
                // (which claims again only on sight) instead of retrying for the life of the
                // process — 2026-11-06's 876 attempts on a powered-off adapter
                val held = stack()
                if (held != null && held.keeper.state == wm.damage.core.shell.ShellKeeper.State.RUNNING) giveBacks = 0
                if (held != null && !switching.get() &&
                    StandbyPolicy.shouldGiveBack(held.keeper.failedStarts, STANDBY_GIVEBACK_FAILS)) {
                    val n = held.keeper.failedStarts
                    val last = held.keeper.lastReason
                    giveBacks++
                    absentStreak = 0       // the next claim's debounce counts from here
                    val next = StandbyPolicy.claimDebounce(giveBacks, STANDBY_DEBOUNCE, STANDBY_DEBOUNCE_CAP)
                    Log.w("damage", "standby: the claim failed $n starts in a row (last: $last) — giving it back; the next claim " +
                        "needs both arms seen again after $next absent probes (give-back $giveBacks in a row)")
                    standbyJournal.note("keeper", "standby give-back $giveBacks: $n failed starts in a row (last: $last); next claim after $next probes")
                    standbyNote.set("standby: the claim could not drive ($last) — standing by")
                    swapStack(null, "standby give-back: $n failed starts in a row")
                }
            }
            delay(STANDBY_PROBE_MS)      // pacing between probes, not a timeout
        }
    }
    // an orderly end — the window's close button, Ctrl-C, a signal: the shell
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
        onText = { line -> stack()?.transport?.injectText(line) },
        // 0.64: a probe is offered to the SHELL first (`rest=on|off` is its own, `Shell.devProbe`), then
        // to the transport; with no stack (standby, the phone drives) neither exists to take it
        onProbe = { n, v -> stack()?.let { if (!it.shell.devProbe(n, v)) it.transport.devProbe(n, v) }
            ?: Log.w("damage", "probe $n=$v: no shell stack on this host (the phone drives)") },
        journalPath = { Path.of(cfg.dataDir).resolve("journal.jsonl") })
    try {
        bindOrRetry(this, "browser replica", cfg.replicaPort) { replica.start() }   // §72
    } catch (e: Exception) {
        Log.e("damage", "browser replica failed to start on :${cfg.replicaPort}", e)
    }

    Log.i("damage", if (mode == "auto")
        "standby up (§19: the phone shell is primary) — data host on :${cfg.contentPort}, probing ${remoteHost ?: cfg.phoneHost}:${cfg.transportPort}"
    else
        "shell up in $mode mode — books from ${cfg.booksDir}, journal in ${cfg.dataDir}")
    Log.i("damage", "preview: wheel/click/right-click/hold = ring · Tab lens · B both")
    // the token's value is never written here (R2, RE-8: a config secret is never displayed; damage.log
    // is 0644 and the log ring is served) — the line says where it is; tools/lint.py SEC001 holds it
    Log.i("damage", "browser replica: http://<this-host>:${cfg.replicaPort}/?token=… (the token is in ${Config.path()})")
    kotlinx.coroutines.awaitCancellation()
}

/** Standby pacing (§19.2): probe cadence, and how many consecutive
 *  absent-or-idle probes it takes before the PC starts its own BLE stack —
 *  the debounce that rides out an APK restart or update. Six since §47
 *  (30 s): two rode out nothing on a relayed tailnet that dropped the
 *  phone for 10 s at a time; the arms' advertising is the real gate. */
private const val STANDBY_PROBE_MS = 5_000L
private const val STANDBY_DEBOUNCE = 6
/** §72: failed starts in a row after which a standby claim goes back to standby. */
private const val STANDBY_GIVEBACK_FAILS = 6
/** §72: the longest a claim's debounce grows after give-backs in a row: 60 probes ≈ 5 min. */
private const val STANDBY_DEBOUNCE_CAP = 60
/** §72: pacing between attempts to bind a busy port. */
private const val BIND_RETRY_MS = 5_000L

