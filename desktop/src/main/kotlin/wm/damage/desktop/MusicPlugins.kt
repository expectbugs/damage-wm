package wm.damage.desktop

import wm.damage.core.util.Log
import wm.damage.core.windows.music.LocalMusicLibrary

/**
 * Wires the host-side music collaborators (`MUSIC.md` §5: the resolver
 * lanes, the lyric fetch chain, yt-dlp, the enrichment package + viz) onto
 * the library. Each is optional at this milestone: an absent one is
 * reported by the library's own loud "not wired" refusal, never silence.
 */
object MusicPlugins {
    fun wire(cfg: Config, lib: LocalMusicLibrary) {
        val audioDir = java.nio.file.Path.of(System.getProperty("user.dir")).resolve("audio").takeIf { java.nio.file.Files.isDirectory(it) }
            ?: java.nio.file.Path.of("/home/user/damagewm/audio")
        val python = java.nio.file.Path.of(cfg.musicPython)
        val embed: ((String) -> List<Double>)? = if (java.nio.file.Files.isExecutable(python) && java.nio.file.Files.isDirectory(audioDir.resolve("enrich"))) {
            val eq = wm.damage.core.windows.music.EmbedQuery(cfg.musicPython, audioDir)
            ({ text: String -> eq.embed(text) })
        } else { Log.w("music", "embedding lane off: python ${cfg.musicPython} or ${audioDir}/enrich missing"); null }
        val claude = wm.damage.core.windows.music.ClaudeOneShot(model = cfg.musicClaudeModel, effort = cfg.musicClaudeEffort)
        val llm: (String, String) -> String = { sys, payload -> claude.run(sys, payload) }
        lib.resolver = wm.damage.core.windows.music.Resolver(lib.db,
            wm.damage.core.windows.music.Qdrant(cfg.musicQdrant, cfg.musicQdrantCollection), embed, llm, cfg.musicQueueSize, cfg.musicClaudeModel)
        val ytDir = lib.youtubeDir ?: java.nio.file.Path.of(cfg.musicLibraryDirs.first()).resolve(cfg.musicYoutubeDir)
        lib.youtube = wm.damage.core.windows.music.YouTube(cfg.musicYtDlp, ytDir)
        Log.i("music", "music library: db=${cfg.musicDb} roots=${cfg.musicLibraryDirs} cache=${cfg.musicCache} media=:${cfg.mediaPort} " +
            "resolver=lanes 1${if (llm != null) "+2" else ""}${if (embed != null) "+3" else ""} yt-dlp=${cfg.musicYtDlp}")
    }
}
