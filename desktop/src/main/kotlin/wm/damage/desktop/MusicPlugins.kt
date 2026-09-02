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
        Log.i("music", "music library: db=${cfg.musicDb} roots=${cfg.musicLibraryDirs} cache=${cfg.musicCache} media=:${cfg.mediaPort}")
    }
}
