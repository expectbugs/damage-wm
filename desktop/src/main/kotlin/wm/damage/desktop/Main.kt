package wm.damage.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import wm.damage.core.content.ContentHostServer
import wm.damage.core.content.LocalContent
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.RemoteTransportClient
import wm.damage.core.transport.SimTransport
import wm.damage.core.util.Log
import wm.damage.core.windows.reader.ReaderWindow

/**
 * The PC program — DESIGN.md §10.8: laptop-direct is the development
 * environment, so it is the default. Modes:
 *
 *   (default)      shell + byte-exact sim + 1x preview + content host, all in
 *                  one process — laptop-direct with the sim standing in for
 *                  the glasses until flash day
 *   --host-only    just the content host (the PC side of app + home PC)
 *   --remote HOST  the shell here, the TRANSPORT there (phone/bridge running a
 *                  transport server) — the PC-resident placement
 *   --selfcheck    headless scripted verification; exit 0 = healthy
 */
fun main(args: Array<String>) {
    val cfg = Config.load()
    when {
        "--selfcheck" in args -> SelfCheck.run(cfg)
        "--epub-check" in args -> epubCheck(cfg)
        "--host-only" in args -> runBlocking { hostOnly(cfg) }
        args.contains("--remote") -> runShell(cfg, remoteHost = args[args.indexOf("--remote") + 1])
        else -> runShell(cfg, remoteHost = null)
    }
}

@Serializable
data class Config(
    val booksDir: String = System.getProperty("user.home") + "/books",
    val contentPort: Int = 7401,
    val transportPort: Int = 7402,
    val token: String = "",
    val dataDir: String = System.getProperty("user.home") + "/.damage",
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
            if (fixed != cfg || !Files.exists(p)) {
                Files.createDirectories(p.parent)
                Files.writeString(p, json.encodeToString(serializer(), fixed))
                Log.i("config", "wrote $p")
            }
            return fixed
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

private suspend fun hostOnly(cfg: Config) {
    val host = ContentHostServer(LocalContent(Path.of(cfg.booksDir)), cfg.contentPort, cfg.token)
    host.start()
    Log.i("damage", "content host only — serving ${cfg.booksDir} on :${cfg.contentPort}; Ctrl-C to stop")
    kotlinx.coroutines.awaitCancellation()
}

private fun runShell(cfg: Config, remoteHost: String?): Unit = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var shellRef: Shell? = null
    val text = AwtText { shellRef?.settings?.fontScale ?: 1.0 }
    val dataDir = Path.of(cfg.dataDir)
    val persistence = Persistence(dataDir.resolve("state.json"))

    val sim = if (remoteHost == null) GlassFirmwareSim() else null
    val transport = if (sim != null) {
        SimTransport(sim, scope)
    } else {
        RemoteTransportClient(remoteHost!!, cfg.transportPort, cfg.token, scope)
    }

    val shell = Shell(text, transport, persistence, dataDir.resolve("journal.jsonl"), scope)
    shellRef = shell
    val content = LocalContent(Path.of(cfg.booksDir))
    shell.register(ReaderWindow(text, content, scope))

    // The PC always serves its library so a phone shell can feed from it.
    val host = ContentHostServer(content, cfg.contentPort, cfg.token)
    try {
        host.start()
    } catch (e: Exception) {
        Log.e("damage", "content host failed to bind :${cfg.contentPort} (another instance?)", e)
    }

    if (sim != null) {
        Preview.show(sim) { shell.postGesture(it) }
        Log.i("damage", "laptop-direct with sim — preview keys: arrows scroll, Enter tap, " +
            "Backspace double-tap, Space long-press, Tab lens")
    } else {
        Log.i("damage", "remote-shell mode: driving transport at $remoteHost:${cfg.transportPort}")
    }

    shell.start()
    Log.i("damage", "shell up — books from ${cfg.booksDir}, journal in ${cfg.dataDir}")
    kotlinx.coroutines.awaitCancellation()
}
