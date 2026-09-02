package wm.damage.core.windows.music

import java.nio.file.Path

/**
 * The host-side collaborators [LocalMusicLibrary] composes (`MUSIC.md` §5,
 * one class each): the interfaces are fixed here so the leaf modules build
 * against them in parallel and the library wires whichever exist. Every
 * method blocks and throws with a reason on failure.
 */

/** §9.3 — the three resolver lanes behind one call. */
interface AskResolver {
    fun ask(request: String): ResolvedQueue
}

/** §9.4 — the lyric fetch chain and the manual search. */
interface LyricsFetcher {
    /** First hit wins across the sources; null = nothing anywhere (a durable
     *  negative the caller may cache). */
    fun fetch(t: MusicDb.TrackFile): Lyrics?
    /** The same chain with a typed query; every candidate labelled. */
    fun search(t: MusicDb.TrackFile, query: String): List<Lyrics>
}

/** §9.6 — yt-dlp: search, and the audio-only grab into the YouTube dir. */
interface YtClient {
    fun search(q: String, n: Int = 10): List<YtResult>
    /** Downloads ONE picked video's audio; [progress] gets 0–100. Returns
     *  the file it wrote. Explicit request only — never a fallback. */
    fun grab(id: String, progress: (Int) -> Unit): Path
}

/** §9.5 — the enrichment passes for one track (tags · musicbrainz · lyrics
 *  · audio · profile · embed · dedupe), then the visualizer data. */
interface Ingester {
    /** Runs the passes for [trackId]; [phase] is told each pass's name. */
    fun enrich(trackId: Int, phase: (String) -> Unit)
    /** Computes the `.viz` blob for [t] (librosa) — returns it, or null when
     *  the tool is unavailable (said loudly by the caller). */
    fun viz(t: MusicDb.TrackFile): ByteArray?
}
