package wm.damage.core.windows.music

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MUSIC — the data model and the two contracts (`MUSIC.md` §5–§6, settled
 * with Adam 2026-09-02, 29 verdicts). Two halves with one contract between
 * them: the LIBRARY lives on the PC (Postgres `g2cc`, Qdrant, the media
 * cache, ffmpeg, yt-dlp, the enrichment package) and the PLAYER lives on the
 * phone (ExoPlayer + a media session). The window is written once against
 * [MusicLibrary] + [MusicPlayer] and never knows which host it runs on.
 *
 * Everything on the wire is kotlinx-serializable with defaults, so an older
 * peer still decodes a newer blob (the additive rule every channel follows).
 */
internal val musicJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

// ====================================================================== catalog

/** The queue-shaped track — what the queue carries and the card displays. */
@Serializable
data class TrackRef(
    val id: Int,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durMs: Int = 0,
)

/** One catalog row: the `tracks` row joined with its `track_meta` profile
 *  (moods/genres/styles from the LLM pass) and two cheap existence bits. */
@Serializable
data class TrackMeta(
    val id: Int,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durMs: Int = 0,
    val trackNo: Int = 0,
    val discNo: Int = 0,
    val year: Int = 0,
    val genres: List<String> = emptyList(),
    val moods: List<String> = emptyList(),
    val styles: List<String> = emptyList(),
    val energy: Int = 0,
    val vocals: String = "",
    val hasLyrics: Boolean = false,
    val hasArt: Boolean = false,
    val dupeCluster: Int = 0,
    /** Library folder, relative to its root, '/'-separated ("" = the root) —
     *  the Collections browse is a folder tree (the Reader precedent). */
    val folder: String = "",
    /** `indexed_at` as unix seconds — Recent/newest ordering. */
    val addedAt: Long = 0,
    /** The source file's extension (`flac`, `mp3`) — the lossless badge and
     *  the dupe-cluster fidelity rule. */
    val ext: String = "",
) {
    fun ref(): TrackRef = TrackRef(id, title, artist, album, durMs)
}

@Serializable
data class Artist(val name: String, val tracks: Int, val albums: Int)

@Serializable
data class Album(val name: String, val artist: String = "", val tracks: Int = 0, val year: Int = 0)

@Serializable
data class Playlist(
    val id: Int,
    val name: String,
    val origin: String = "manual",
    /** `rule IS NOT NULL` — membership is rule-managed; Edit refuses and says why. */
    val adaptive: Boolean = false,
    val count: Int = 0,
    val updatedAt: Long = 0,
)

/** A genre / mood / style word with its track count (Moods & genres). */
@Serializable
data class VocabTerm(val term: String, val kind: String, val count: Int)

/** ~3 k rows: one JSON blob, versioned, cached on the phone so Browse works
 *  with the PC down (`MUSIC.md` §3.1). */
@Serializable
data class Catalog(
    val version: String = "",
    val generatedAt: Long = 0,
    val tracks: List<TrackMeta> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val vocab: List<VocabTerm> = emptyList(),
    /** Play history, newest first, capped (Recent). */
    val recent: List<Int> = emptyList(),
) {
    val byId: Map<Int, TrackMeta> by lazy { tracks.associateBy { it.id } }
    fun track(id: Int): TrackMeta? = byId[id]

    fun encode(): ByteArray = musicJson.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)

    companion object {
        fun decode(b: ByteArray): Catalog = musicJson.decodeFromString(serializer(), b.toString(Charsets.UTF_8))
        val EMPTY = Catalog()
    }
}

// ====================================================================== player

/** Playback modes (verdict 9): Shuffle is the default; Radio appends nearest
 *  neighbours when the queue runs low; Library random draws from everything. */
enum class Mode(val label: String) { QUEUE("queue"), SHUFFLE("shuffle"), RADIO("radio"), LIBRARY_RANDOM("library random") }

enum class Backend(val label: String) { LIBRARY("PC"), SPOTIFY("Spotify") }

enum class PlayState { STOPPED, PLAYING, PAUSED }

/** A queue row: [qid] is its IDENTITY (row identity, never an index — shuffle
 *  and radio reorder under the cursor). */
@Serializable
data class QueueEntry(val qid: Long, val track: TrackRef)

/** The PC link as the phone sees it: up, or down since a wall-clock instant
 *  (the card says `PC ↓ 2m` — staleness with duration, never silence). */
@Serializable
data class PcLink(val up: Boolean = true, val sinceMs: Long = 0) {
    fun ageS(nowMs: Long): Long = if (up || sinceMs <= 0) 0 else maxOf(0L, (nowMs - sinceMs) / 1000)
}

/** Sleep (verdict 28): off · after this track · a deadline the player checks
 *  on its ticks (pacing, never a timer wrapper). */
@Serializable
data class Sleep(val kind: Kind = Kind.OFF, val deadlineMs: Long = 0, val minutes: Int = 0) {
    enum class Kind { OFF, AFTER_TRACK, TIMER }
    fun label(nowMs: Long): String = when (kind) {
        Kind.OFF -> "off"
        Kind.AFTER_TRACK -> "after this track"
        Kind.TIMER -> Fmt.mmss(maxOf(0L, deadlineMs - nowMs)) + " left"
    }
    /** The Settings/menu choice this sleep came from (a running timer keeps its length). */
    fun choice(): String = when (kind) {
        Kind.OFF -> "off"
        Kind.AFTER_TRACK -> "after this track"
        Kind.TIMER -> "$minutes min"
    }
    companion object {
        val OFF = Sleep()
        /** The Settings/menu choices: off · after this track · minutes. */
        val CHOICES = listOf("off", "after this track", "15 min", "30 min", "60 min", "90 min")
        fun fromChoice(choice: String, nowMs: Long): Sleep = when (choice) {
            "off" -> OFF
            "after this track" -> Sleep(Kind.AFTER_TRACK)
            else -> {
                val m = choice.substringBefore(' ').toLongOrNull() ?: 0L
                if (m <= 0) OFF else Sleep(Kind.TIMER, nowMs + m * 60_000, m.toInt())
            }
        }
    }
}

/** An audio output the phone can route to (Output…): "auto" = the current
 *  route; the speaker plays only when chosen (verdict 2 + §7). */
@Serializable
data class Output(val id: String, val name: String, val kind: String = "") {
    companion object {
        const val AUTO = "auto"
        val AUTO_OUTPUT = Output(AUTO, "Auto", "auto")
    }
}

/** What Spotify is playing when it is the backend (from its media session). */
@Serializable
data class SpotifyNow(val title: String = "", val artist: String = "", val album: String = "", val durMs: Long = 0)

/**
 * The player's whole state (§6.2). Volatile fields (position, outputs, link
 * age) are refreshed on ticks; [persistable] strips them for the synced
 * sub-record `window.music.player`, so the desktop mirror and a re-installed
 * APK resume the same queue. Never auto-plays on boot (verdict 9).
 */
@Serializable
data class PlayerState(
    val backend: Backend = Backend.LIBRARY,
    val play: PlayState = PlayState.STOPPED,
    val queue: List<QueueEntry> = emptyList(),
    val index: Int = 0,
    val posMs: Long = 0,
    /** When [posMs] was sampled (wall clock): the mirror extrapolates from it. */
    val posAtMs: Long = 0,
    val durMs: Long = 0,
    val mode: Mode = Mode.SHUFFLE,
    /** The phone's media stream, 0–100 (verdict 13: synced both ways). */
    val volume: Int = 50,
    /** Volume boost 100–400 % (verdict 15): off when the track ends, never remembered. */
    val boost: Int = 100,
    val output: String = Output.AUTO,
    val outputs: List<Output> = listOf(Output.AUTO_OUTPUT),
    val pcLink: PcLink = PcLink(),
    val sleep: Sleep = Sleep.OFF,
    val holdVolume: Boolean = true,
    val profile: AudioProfile = AudioProfile.DEFAULT,
    /** The queue's label ("hard metal", "Pink Floyd") from the resolver. */
    val label: String = "",
    val spotify: SpotifyNow? = null,
    /** Spotify was entered AUTOMATICALLY on PC loss — the "Back to PC library"
     *  row appears the moment the link is healthy again (verdict 20). */
    val spotifyAuto: Boolean = false,
    /** The player's own problem line ("grant notification access on the
     *  phone", "no external output — choose one"), "" when healthy. */
    val problem: String = "",
    /** The phone's notification-listener grant (Spotify + the limiter notice). */
    val listenerGranted: Boolean = false,
) {
    val entry: QueueEntry? get() = queue.getOrNull(index)
    val track: TrackRef? get() = entry?.track

    /** The synced record: everything but the volatile fields. Position is
     *  kept coarse (every 10 s and on every change — the player's rule). */
    fun persistable(): JsonObject {
        val slim = copy(outputs = emptyList(), pcLink = PcLink(), spotify = null, problem = "", listenerGranted = false)
        return musicJson.encodeToJsonElement(serializer(), slim) as JsonObject
    }

    companion object {
        fun fromJson(o: JsonObject): PlayerState = try {
            musicJson.decodeFromJsonElement(serializer(), o)
        } catch (e: Exception) {
            wm.damage.core.util.Log.w("music", "player record unreadable — starting empty: ${e.message}")
            PlayerState()
        }
    }
}

/** Events the player raises (through [MusicPlayer.Listener.event]). */
sealed class PlayerEvent {
    data class TrackChange(val track: TrackRef, val index: Int, val total: Int) : PlayerEvent()
    object QueueEnd : PlayerEvent()
    data class RouteLost(val detail: String) : PlayerEvent()
    data class Error(val detail: String) : PlayerEvent()
    data class LimiterUndone(val restoredTo: Int) : PlayerEvent()
    data class LimiterKeeps(val detail: String) : PlayerEvent()
    object BoostOff : PlayerEvent()
    data class BoostLoud(val volume: Int, val boost: Int) : PlayerEvent()
    data class SleepEnded(val detail: String) : PlayerEvent()
    data class BackendChanged(val backend: Backend, val automatic: Boolean) : PlayerEvent()
    data class PcUnreachable(val sinceMs: Long) : PlayerEvent()
}

// ====================================================================== audio profiles

/**
 * An audio profile keys the transcode cache (§6.4). Presets: High (Opus
 * 128 k mono / 192 k stereo — the default, at or above what the earbud link
 * carries), Standard (96 k), Saver (48 k), Lossless (passthrough, no
 * loudnorm). The legacy G2CC cache (opus 96 k mono loudnorm) IS the
 * `standard-mono-loudnorm` profile and is read in place.
 */
@Serializable
data class AudioProfile(
    val quality: Quality = Quality.HIGH,
    val channels: Int = 1,
    val loudnorm: Boolean = true,
) {
    enum class Quality(val label: String) { HIGH("High"), STANDARD("Standard"), SAVER("Saver"), LOSSLESS("Lossless") }

    val codec: String get() = if (quality == Quality.LOSSLESS) "passthrough" else "opus"
    val kbps: Int get() = when (quality) {
        Quality.HIGH -> if (channels >= 2) 192 else 128
        Quality.STANDARD -> 96
        Quality.SAVER -> 48
        Quality.LOSSLESS -> 0
    }
    /** The cache directory name and the wire name. Lossless never loudnorms,
     *  so its name carries no loudnorm word. */
    val name: String get() = if (quality == Quality.LOSSLESS) "lossless"
        else "${quality.name.lowercase()}-${if (channels >= 2) "stereo" else "mono"}-${if (loudnorm) "loudnorm" else "flat"}"
    /** The cached file's extension. */
    fun ext(sourceExt: String): String = if (quality == Quality.LOSSLESS) sourceExt.ifEmpty { "bin" } else "opus"

    companion object {
        val DEFAULT = AudioProfile()
        /** The profile the legacy `~/.g2cc/media-cache` holds (2,981 files). */
        val LEGACY = AudioProfile(Quality.STANDARD, 1, true)

        fun parse(name: String): AudioProfile? {
            if (name == "lossless") return AudioProfile(Quality.LOSSLESS, 2, false)
            val p = name.split('-')
            if (p.size != 3) return null
            val q = Quality.entries.firstOrNull { it.name.lowercase() == p[0] } ?: return null
            if (q == Quality.LOSSLESS) return null
            val ch = when (p[1]) { "mono" -> 1; "stereo" -> 2; else -> return null }
            val ln = when (p[2]) { "loudnorm" -> true; "flat" -> false; else -> return null }
            return AudioProfile(q, ch, ln)
        }
    }
}

// ====================================================================== lyrics

/** Raw lyrics as the library hands them over: the LRC text (line or enhanced
 *  word stamps) and/or plain text; parsing is pure (`LyricsSync`). */
@Serializable
data class Lyrics(
    val source: String = "",
    val synced: String? = null,
    val plain: String? = null,
    /** Manual search only: what this candidate matched (artist — title · m:ss). */
    val label: String = "",
) {
    val found: Boolean get() = !synced.isNullOrBlank() || !plain.isNullOrBlank()
}

// ====================================================================== visualizer data

/**
 * Precomputed on the PC at transcode time (`audio/viz.py`, librosa): a
 * low-rate spectrum envelope (bands × 4-bit levels per frame), an RMS
 * envelope (4-bit per 20 ms) and beat times. Binary, little-endian:
 *
 *   "DVIZ" · u8 version(1) · u8 fps · u8 bands · u32 frames · u32 rmsCount ·
 *   u32 beats · frames×bands nibbles (packed high-first, row-major) ·
 *   rmsCount nibbles (packed) · beats × u32 ms
 */
class VizData(
    val fps: Int,
    val bands: Int,
    val frameCount: Int,
    /** Packed nibbles, frame-major: nibble i = frame (i / bands), band (i % bands). */
    val frames: ByteArray,
    val rmsCount: Int,
    /** Packed nibbles, one per 20 ms. */
    val rms: ByteArray,
    val beatsMs: IntArray,
) {
    /** Level 0–15 of [band] at [frame] (clamped into range). */
    fun level(frame: Int, band: Int): Int {
        if (frameCount == 0 || bands == 0) return 0
        val f = frame.coerceIn(0, frameCount - 1)
        val i = f * bands + band.coerceIn(0, bands - 1)
        return nib(frames, i)
    }

    fun frameAt(posMs: Long): Int = if (fps <= 0) 0 else (posMs * fps / 1000).toInt()

    /** RMS level 0–15 at [posMs] (20 ms slots). */
    fun rmsAt(posMs: Long): Int {
        if (rmsCount == 0) return 0
        return nib(rms, (posMs / 20).toInt().coerceIn(0, rmsCount - 1))
    }

    /** Milliseconds since the last beat at or before [posMs], or -1 before the first. */
    fun sinceBeat(posMs: Long): Long {
        var lo = 0
        var hi = beatsMs.size - 1
        var best = -1
        while (lo <= hi) {
            val m = (lo + hi) / 2
            if (beatsMs[m] <= posMs) { best = m; lo = m + 1 } else hi = m - 1
        }
        return if (best < 0) -1 else posMs - beatsMs[best]
    }

    fun encode(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("DVIZ".toByteArray(Charsets.US_ASCII))
        out.write(1); out.write(fps and 0xFF); out.write(bands and 0xFF)
        fun u32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        u32(frameCount); u32(rmsCount); u32(beatsMs.size)
        out.write(frames, 0, (frameCount * bands + 1) / 2)
        out.write(rms, 0, (rmsCount + 1) / 2)
        for (b in beatsMs) u32(b)
        return out.toByteArray()
    }

    companion object {
        private fun nib(b: ByteArray, i: Int): Int {
            val byte = b[i shr 1].toInt() and 0xFF
            return if (i and 1 == 0) byte shr 4 else byte and 0x0F
        }

        /** Throws on a malformed blob — the caller says so, never a silent
         *  blank visualizer. */
        fun decode(b: ByteArray): VizData {
            require(b.size >= 19 && b[0] == 'D'.code.toByte() && b[1] == 'V'.code.toByte() &&
                b[2] == 'I'.code.toByte() && b[3] == 'Z'.code.toByte()) { "not a DVIZ blob (${b.size} B)" }
            val version = b[4].toInt() and 0xFF
            require(version == 1) { "DVIZ version $version unsupported (this build reads 1)" }
            val fps = b[5].toInt() and 0xFF
            val bands = b[6].toInt() and 0xFF
            fun u32(at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
                ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
            val frames = u32(7); val rmsCount = u32(11); val beats = u32(15)
            require(frames >= 0 && rmsCount >= 0 && beats >= 0 && bands in 1..64 && fps in 1..60) {
                "DVIZ header out of range: fps=$fps bands=$bands frames=$frames rms=$rmsCount beats=$beats"
            }
            var at = 19
            val fBytes = (frames * bands + 1) / 2
            val rBytes = (rmsCount + 1) / 2
            require(b.size >= at + fBytes + rBytes + beats * 4) { "DVIZ blob short: ${b.size} B" }
            val fr = b.copyOfRange(at, at + fBytes); at += fBytes
            val rm = b.copyOfRange(at, at + rBytes); at += rBytes
            val bt = IntArray(beats) { i -> u32(at + i * 4) }
            return VizData(fps, bands, frames, fr, rmsCount, rm, bt)
        }
    }
}

// ====================================================================== YouTube

@Serializable
data class YtResult(val id: String, val title: String, val channel: String = "", val durS: Int = 0, val url: String = "")

/** A grab's progress: phase in { queued, downloading, indexing, transcoding,
 *  enriching, lyrics, done, failed }. */
@Serializable
data class YtJob(
    val job: String,
    val title: String = "",
    val phase: String = "queued",
    val percent: Int = 0,
    val trackId: Int = 0,
    val error: String = "",
) {
    val done: Boolean get() = phase == "done"
    val failed: Boolean get() = phase == "failed"
}

// ====================================================================== the resolver's answer

/** Every result carries the lane + label + the honest detail line (§9.3). */
@Serializable
data class ResolvedQueue(
    val tracks: List<TrackRef> = emptyList(),
    val lane: String = "empty",
    val label: String = "",
    val detail: String = "",
)

// ====================================================================== the contracts

/** The library (§6.1): Local on the PC, Remote on the phone. Every call
 *  blocks (call from background coroutines, never the shell loop); every
 *  failure throws with a reason — never an empty answer for a failure. */
interface MusicLibrary {
    /** "" healthy, else "PC unreachable 40s" / "library: <err>". */
    fun stateLine(): String
    /** Cached; the Remote serves its disk cache while offline. Never blocks
     *  on the network (a paint may ask for it — it is a field read). */
    fun catalog(): Catalog
    /** Paced version-cursor refresh; a changed catalog reaches listeners. */
    fun refreshCatalog()
    fun search(q: String): List<TrackRef>
    /** The three lanes (§9.3); never throws for a lane failure. */
    fun ask(request: String): ResolvedQueue
    /** Radio: nearest neighbours of [trackIds], minus [exclude]. */
    fun similar(trackIds: List<Int>, exclude: List<Int>, n: Int): List<TrackRef>
    /** Library-random mode: lane-1 rules over the whole catalog. */
    fun randomLibrary(n: Int, exclude: List<Int> = emptyList()): List<TrackRef>
    fun playlists(): List<Playlist>
    fun playlistTracks(id: Int): List<TrackRef>
    fun savePlaylist(name: String, trackIds: List<Int>, overwrite: Boolean): Playlist
    fun renamePlaylist(id: Int, name: String)
    fun deletePlaylist(id: Int)
    /** Adaptive → throws with "adaptive" in the message. */
    fun setPlaylistTracks(id: Int, trackIds: List<Int>)
    fun lyrics(trackId: Int): Lyrics?
    fun searchLyrics(trackId: Int, query: String): List<Lyrics>
    /** Settings → Music → Lyrics sources: "lrclib+local" · "+netease" ·
     *  "+musixmatch" (verdict 24). The host's fetch chain follows it. */
    fun setLyricsSources(sources: String) {}
    fun setLyrics(trackId: Int, choice: Lyrics)
    /** 4-bit gray, px×px, packed two pixels a byte (high nibble first), box-sampled on the PC; null = none. */
    fun art(trackId: Int, px: Int): ByteArray?
    /** The cached blob, or null while the host builds it (a [Listener.vizReady]
     *  follows) or when there is none. */
    fun viz(trackId: Int): VizData?
    /** The last [n] distinct played track ids, newest first — LIVE (the catalog's
     *  copy is the offline fallback). */
    fun recent(n: Int): List<Int> = catalog().recent.take(n)
    fun ytSearch(q: String): List<YtResult>
    /** Starts a grab; returns the job id. Explicit request only. */
    fun ytGrab(id: String): String
    fun ytStatus(job: String): YtJob
    fun played(trackId: Int, startedMs: Long, endedMs: Long, completed: Boolean, skipped: Boolean)
    /** The §6.3 media endpoint for the phone's player. */
    fun streamUrl(trackId: Int, profile: AudioProfile): String
    /** Ask the host to (re)build the cache for [profile] in the background. */
    fun pretranscode(profile: AudioProfile): String
    fun rescan(): String
    fun addListener(l: Listener)
    fun removeListener(l: Listener)
    fun setFocused(focused: Boolean, paceMs: Long)
    fun close() {}

    interface Listener {
        fun catalogChanged(c: Catalog) {}
        fun ytJob(j: YtJob) {}
        fun state(line: String) {}
        /** A visualizer blob finished building on the host: ask again. */
        fun vizReady(trackId: Int) {}
    }
}

/** The player (§6.2): Android on the phone, Mirror on the desktop, Scripted
 *  in tests. Commands are loop-safe (they post to the player's own thread);
 *  [state] is a snapshot field read. */
interface MusicPlayer {
    val state: PlayerState
    fun play(); fun pause(); fun toggle(); fun next(); fun prev(); fun stop()
    fun seekTo(ms: Long); fun seekBy(ms: Long)
    fun playQueue(tracks: List<TrackRef>, startIndex: Int, mode: Mode, label: String)
    fun playFrom(qid: Long)
    fun playNext(tracks: List<TrackRef>)
    fun append(tracks: List<TrackRef>)
    fun replace(tracks: List<TrackRef>, label: String)
    fun remove(qid: Long)
    fun move(qid: Long, delta: Int)
    fun clear()
    fun shuffleRest()
    fun setMode(mode: Mode)
    fun setVolume(pct: Int, cause: String)
    fun setBoost(pct: Int)
    fun setOutput(id: String)
    fun outputs(): List<Output>
    fun setBackend(b: Backend)
    /** The deliberate switchback (verdict 20). */
    fun backToPc()
    fun setSleep(s: Sleep)
    fun setHoldVolume(on: Boolean)
    fun setProfile(p: AudioProfile)
    fun setPrefetch(n: Int)
    fun setSpotifyFallback(auto: Boolean)
    /** EXACT, monotonic-extrapolated between ticks (lyrics use this). */
    fun positionMs(): Long
    fun addListener(l: Listener)
    fun removeListener(l: Listener)
    /** The synced sub-record: the queue, index, coarse position, mode, backend. */
    fun persist(): JsonObject
    fun restore(o: JsonObject)
    /** The window's focus (paced ticks only while someone looks). */
    fun setFocused(focused: Boolean)
    fun close() {}

    interface Listener {
        fun state(s: PlayerState) {}
        /** Paced position ticks while playing. */
        fun tick(posMs: Long) {}
        fun event(e: PlayerEvent) {}
    }
}

// ====================================================================== formatting

object Fmt {
    private val L = java.util.Locale.ROOT

    fun mmss(ms: Long): String {
        val s = maxOf(0L, ms / 1000)
        val h = s / 3600
        return if (h > 0) "%d:%02d:%02d".format(L, h, (s % 3600) / 60, s % 60)
        else "%d:%02d".format(L, s / 60, s % 60)
    }

    fun ageShort(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m"
        seconds < 86_400 -> "${seconds / 3600}h"
        else -> "${seconds / 86_400}d"
    }

    fun titleArtist(t: TrackRef): String = if (t.artist.isEmpty()) t.title else "${t.title} — ${t.artist}"

    fun artistAlbum(t: TrackRef): String = listOf(t.artist, t.album).filter { it.isNotEmpty() }.joinToString(" — ")

    /** A small JSON helper for records. */
    fun obj(vararg kv: Pair<String, Any?>): JsonObject = buildJsonObject {
        for ((k, v) in kv) when (v) {
            null -> {}
            is String -> put(k, v)
            is Int -> put(k, v)
            is Long -> put(k, v)
            is Boolean -> put(k, v)
            is Double -> put(k, v)
            else -> put(k, v.toString())
        }
    }
}
