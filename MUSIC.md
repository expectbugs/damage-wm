# Music on glass — design record (2026-09-02)

**Status: BUILT 2026-09-02/03 in the overnight autonomous session (M1–M6, a commit per
milestone with the battery green — `HANDOFF.md` §24 is the build record with the as-built
numbers and the decisions made inside this plan); §4 is closed (the collation refresh ran
with Adam's go on 2026-09-02).** The window was built whole on Adam's rule (no v1/v1.5
staging); next is the review loop (`REVIEW.md`) and the on-phone checks in §12. `EXPLOSION.md` §3 is
the idea record; `/home/user/G2CC/docs/MUSIC_SPEC.md` is the previous player's decision record
(read for facts and lessons, never for code — the G2CC music *system* is now Damage's, its code
is ported, not pasted).

## 1. Verdicts (Adam, 2026-09-02)

| # | question | verdict |
|---|---|---|
| 1 | Library source | **The G2CC music system is Damage's now** — Postgres `g2cc` tables, the Qdrant `g2cc_music` collection, the media cache, the Python enrichment + embedding pipeline, yt-dlp. Taken over entirely, improved where we can. G2CC itself is retired and never returns. |
| 2 | Where audio plays | **The phone.** Earbuds by default, with an Output option for anything connected to the phone, including the phone speaker. **Never the PC.** |
| 3 | Backends | PC library primary; **Spotify on the phone** as the selectable fallback (also the automatic fallback when the PC is unreachable). Other apps maybe later. Desktop Spotify: never. |
| 4 | Root view | **Queue-with-card**: the queue is the list, the lens is the Now Playing card; **Menu is the last row** so one scroll up from the top wraps to it. |
| 5 | Ask (fuzzy requests) | Keep the language-model lane: **the latest Claude Opus at low or medium effort** via the CLI one-shot, with the deterministic and embedding lanes as the instant fallback. Same model for enriching new tracks. |
| 6 | Lyrics | **In, and much better synced than G2CC** (learn from the open-source players). Sources: LRCLIB, embedded tags, `.lrc` beside tracks, the community fetchers; manual search on the keyboard. |
| 7 | YouTube | **In**: a search whose results are listed for Adam to pick from (never the first hit), audio-only grab into `~/Music/YouTube/`, then **fully ingested** — indexed, transcoded, enriched, lyrics, embeddings — like any track. Offered when a search finds nothing in the library, and from Browse. |
| 8 | Playlists | **Full editing on glass** (create, save queue, add current, rename, reorder, remove, delete). |
| 9 | Playback modes | Default = **shuffle the current/most-recent queue**. **Radio** (nearest neighbours appended when the queue runs low) is a selectable mode, not the default. **Library random** (whole library) is a third mode. No auto-play on boot: a bud tap or Play starts it. |
| 10 | Track-change notices | **Default on**, toggle in Settings → Music. |
| 11 | Heights | 🔴 **Every window works well at all four sizes (288/352/416/480) and each app's size is adjustable in Settings.** Music's card, queue, lyrics and Music Mode are each specified per height. Now a rule in `WINDOWS.md` §1. |
| 12 | Adam-specific defaults | **Nothing is baked in**: mono/stereo, normalization, codec/bitrate, output, volume, lyrics, visuals are Settings rows. **Mono to start** (one work earbud today); release defaults are chosen later in a global pass. |
| 13 | Volume | In Settings **and** in the app, **synced both ways with the phone's media volume**. |
| 14 | Hearing-protection limiter | The phone lowers the volume after a long stretch at max and posts a notification; **there is no confirmation dialog on Adam's phone** — volume-up simply restores it, and the problem is the phone sitting on the cart out of reach. Detect the event (the OS notification via the listener, and the large instant drop, which looks nothing like repeated volume-down taps) and **re-set the stream to the held level**. Setting "Hold my volume", default on. The APK logs every volume change with its cause so the first real trigger at work is captured for verification. |
| 15 | Gain | **Not for undoing the limiter** (quality, and the accidental-loudness trap: max the volume while paused with gain active, then play). But a **separate "Volume boost" option** for rare quiet material, outside the normal volume control: **ceiling 400 % (+12 dB), off when the track ends, never remembered** — a rare one-off. Shown on the card while active; a notice when both max volume and boost are on. |
| 16 | Music Mode | **Silent Mode with music décor**: the display shows only the configured music surfaces (card, lyrics, visualizer, …), **ring input is ignored except double-tap, which exits** Music Mode. No menu inside it. |
| 17 | Earbud taps | **The primary transport control, from anywhere**: single = play/pause, double = next, triple = previous (the buds' native gestures through the media session). Ring inputs are never remapped. |
| 18 | Codec | Adam: *"whatever quality the BLE forces"* — clarified: no audio ever crosses the glasses' BLE; the ceiling is the **phone → earbud Bluetooth link** (A2DP, lossy). So the default is **Opus high (128 k mono / 192 k stereo)**, at or above what that link carries, with **Lossless** (FLAC passthrough — for the phone on an AUX cable into a stereo), **Standard** (96 k) and **Saver** (48 k) as options. |
| 19 | Visualizer | Precomputed on the PC (no microphone permission), rendered in sync on the phone. **Only visuals that suit the G2** (4-bit gray, one flush per frame, the measured latency curve) — fast and responsive, never a lagging display. Several options plus **off**. Adam wants suitable open-source ideas adopted too. |
| 20 | Spotify switchback | **Deliberate, never automatic.** When Spotify was the *automatic* fallback, a **"Back to PC library" row appears in the app Menu as soon as the PC is reachable again**, and the player and Music Mode show **whether the PC is connected and, if not, for how long**. Spotify can be started cold. |
| 21 | Phone notifications | 🔴 **The APK stops sending errors to the phone.** Errors go to the glasses' notifications and the log. The one permanent foreground notification stays. Built with the Music build (a Settings toggle, default off). |
| 22 | Build shape | Overnight autonomous build, everything above, then the review loop. |
| 23 | Notices in Music Mode | **Yes** — temporary notices still show over Music Mode, as in silent mode. |
| 24 | Lyrics sources | **Everything reachable without a new account**: LRCLIB, embedded tags, `.lrc` files, NetEase's public endpoint, the unofficial Musixmatch route (keyless; may stop working — behind a toggle), plus any key Adam already made for the G2CC player (read from his G2CC config at build time). MusicBrainz needs no key. |
| 25 | Spotify auto-fallback | **Automatic on PC loss** (default on). Spotify is installed and signed in. |
| 26 | Per-height numbers | Principle confirmed; the numbers come from real renders and are adjusted on glass. |
| 27 | G2CC's server | **Keeps running for now** (the APK setup page); retired when DamageWM is complete. |
| 28 | Sleep | **Sleep options in Settings, default off**: stop after this track, or after any timer. |
| 29 | Prefetch | **Three tracks ahead**, adjustable in Settings. |

Recorded so it is not re-pitched: no PC audio output; no ring remapping in Music Mode; gain never
undoes the limiter; no auto-play on boot; Spotify only as the phone fallback (no desktop client);
radio is not the default.

## 2. Facts the design stands on (verified 2026-09-01/02, read-only)

- **The glasses have no speaker.** Audio is phone → Pixel Buds 2a over A2DP (one earbud at work).
  BLE + A2DP coexistence over a full shift was field-verified 2026-08-04 (G2CC `MUSIC_SPEC.md`).
- **G2CC's model** (`server/src/music-player.ts:1-19`): the PC owns the queue and transport state,
  the phone's ExoPlayer is the sink; `media_open/media_ctl/media_event` over its WebSocket; stream
  `GET /media/track/:id` with Range support; transcode `ffmpeg … -ac 1 -c:a libopus -b:a 96k -af
  loudnorm=I=-16:TP=-1.5:LRA=11 -f ogg` into `~/.g2cc/media-cache` (2,981 files, 8.1 GB — the
  whole library, one profile); ExoPlayer with a 60–300 s buffer; gapless via a prestaged next item;
  never auto-play on boot; the phone speaker refused (reversed by verdict 2).
- **Library:** 2,981 tracks / 49 GB under `/home/user/Music` (`Library/<Artist>/[<Album>/]`,
  `Collections/`, `Archive/Dupes/`, `Unsorted/`, `YouTube/`, `new/`); 1,754 FLAC · 631 MP3 · 553
  OGG · 29 WAV · a few M4A/WMA/Opus; 68 folder JPEGs. Postgres `g2cc`: `tracks` 2,981, `track_meta`
  2,981 (moods/genres from the LLM profile pass), `playlists` 25 (all adaptive), `play_history`
  388, `lyrics` 2,194 rows / **542 with synced lyrics**, `player_state` 1. Qdrant `g2cc_music`:
  2,981 points, 384-dim (`BAAI/bge-small-en-v1.5`). Connection: `pg.Pool({host: <socket dir>,
  database: 'g2cc'})` — peer auth over the Unix socket, no password (`store.ts:34`). ⚠ `psql`
  reports a collation-version mismatch (2.42 vs 2.43) — a one-line refresh, Adam's call.
- **G2CC's server** (`node dist/index.js`, :7300) keeps running for the APK setup page. It scans
  the library **once at start** (`index.ts:718`) — incremental by path/mtime, so a restart's
  re-scan is idempotent — and its music player persists only when used. No timer re-scans.
- **Enrichment pipeline:** `/home/user/G2CC/audio/enrich/` (`run_enrichment.py`, `embed_query.py`,
  `passes/`, `db.py`) on the venv `/home/user/G2CC/audio/venv` (librosa, embeddings, the LLM
  profile pass). yt-dlp 2026.06.09 at `~/.local/bin/yt-dlp`.
- **The Damage APK** has no media stack: no ExoPlayer, no MediaSession, no notification listener,
  no media-playback foreground type, no RECORD_AUDIO (and it stays that way). G2CC used media3
  1.5.1 (`libs.media3.exoplayer`).
- **The ring** sends five events: tap, double-tap, scroll up/down, long-press + release
  (`EvenHubMsg.kt:213-222`). No triple-tap event exists.
- **Shell pieces already waiting:** `IconKind.MUSIC` + theme names `multimedia-audio-player` /
  `audio-x-generic`; `ShellSettings.notifyMusic` + `noticeAllowed("MUSIC")` (the Global row was
  removed — the toggle moves to Settings → Music); Silent Mode's input path (`DESIGN.md` §1.5:
  everything swallowed except double-tap) is the model for Music Mode.
- **The seam:** the §16.10 window channel (`WinNet.kt`), the Torrents Local/Remote provider split
  (`TorrentsNet.kt`), the Files blob lane (`FilesNet.kt`). Music built the channel's PUSH slice
  (as built: `WinService.Push` + `RemoteWin(onPush)`, the `wpush` frame); summaries-over-channel
  and a per-backend `needs` contract stay unbuilt (Music declares `needs` per host).
- **`claude` CLI** exposes `--model`, `--effort` and `-p/--print` (checked 2026-09-02).

## 3. The design (settled parts)

### 3.1 Roles
- **Playback truth lives on the phone** (the shell runs next to ExoPlayer in the APK). **Library
  truth lives on the PC** (Postgres/Qdrant/cache/ffmpeg/yt-dlp/enrichment). The queue and the
  playback position are a §16 record synced both ways so the desktop shell shows the same state
  (it has no sink; it displays only). PC-only configuration: the window says playback needs the
  phone, exactly as G2CC did.
- **The phone caches the catalog** (a few thousand rows) so Browse works with the PC down, and
  **prefetches the next 3 queue tracks** (setting) so a Tailscale drop does not stop the music
  before the queue ends. The card and Music Mode show the PC link state and its staleness age.

### 3.2 The window (`MusicWindow`, id `music`)
- **ROOT = the queue (List)**: rows = queue tracks (`▶` on the current); at rest the cursor sits on
  the current track; the **lens is the Now Playing card** (art, title, artist, album, a coarse bar,
  m:ss/m:ss, queue position, mode, PC link). Scrolling previews other rows in the same card.
  Tap on a row → row menu (Play from here · Play next · Remove · Move up/down · Add to playlist…);
  the current row is never removable. **The wrap-end row is Menu.**
- **Menu**: Pause/Resume · Next · Previous · Volume… · Ask… (keyboard) · Browse · Playlists ·
  Moods & genres · Search… (keyboard) · Mode (Queue / Shuffle / Radio / Library random) · Lyrics ·
  Seek… · Save queue as playlist · Music Mode · Output… · Stop · (when applicable) Back to PC
  library · Switch to Spotify.
- **Browse**: Artists → Albums → Tracks · Albums · Moods & genres · Playlists · Collections ·
  Recent · YouTube…; a tap on any track/album/playlist offers Play now · Play next · Append ·
  Replace queue.
- **Ask**: a typed request → the three resolver lanes (deterministic · language model · embedding)
  → plays, with the honest which-lane line. **Search**: token search → results; no hits → "Search
  YouTube…".
- **Playlists**: list → open (rows) → Play · Play at random · Add current · Rename · Edit
  (reorder/remove) · Delete (Cancel-first double confirm) · Save-over needs saying twice.
  Adaptive playlists refuse Edit and say why.
- **Lyrics (Document)**: current line highlighted with context, paced line advance from the
  phone's real position (§3.6). **Seek**: ±10 s per notch on a row, ±5 min rows.
- **YouTube…**: keyboard search → up to 10 results (title · channel · duration) → pick → grab →
  ingest → "added; playing / queued" (§3.9).
- Faces/icon: Clear Sans until earned; icon `multimedia-audio-player` with the drawn fallback.
  `preferredHeight` 480; every level specified at 288/352/416/480 (§4 item 6 fixes the numbers).

### 3.3 Music Mode
Silent Mode with music décor (`DESIGN.md` §1.5's input path): **all ring input swallowed except
double-tap, which exits to the window**; long-press never arms; the chord cannot fire. No menu.
Temporary notices still show (verdict 23).
Surfaces, each on/off and ordered in Settings → Music → Music Mode: Now Playing card · Lyrics ·
Visualizer (type) · Queue peek (next 2–3) · Clock · PC link state. Layout per height: stacked at
480, fewer/shorter surfaces at 288. Temporary notices still show (the shell's, as in silent mode).

### 3.4 Earbud transport
The APK holds a `MediaSession`; the buds' native gestures arrive as media-button events (single
play/pause · double next · triple previous) and act **from anywhere**, in or out of Music Mode,
whichever window is up. With Spotify as the backend the buds drive Spotify's own session.

### 3.5 Volume, hold, boost
- **Volume** = the phone's media stream (absolute volume on the buds). Settings → Music → Volume
  and Menu → Volume… (scroll adjusts live, tap keeps); phone buttons change it and the rows follow.
- **Hold my volume** (default on): the notification listener sees the OS "volume lowered" notice
  and/or the volume-change broadcast shows a large instant drop from the held level (not a
  sequence of single steps) → re-set the stream to the held level; if the OS parks the raise behind
  its confirmation dialog, a glass notice says so ("phone lowered the volume — confirm on the
  phone"). Probe on the Pixel before the semantics are final.
- **Volume boost** (separate row, default off, 100–400 %): a gain stage on our own audio session
  with a limiter. Never touched by "Hold my volume". Shown on the card while active; a notice when
  max volume and boost coincide. **Off when the track ends, never remembered.**

### 3.6 Audio profiles (Settings → Music)
Quality: Opus high (default) / Lossless / Standard / Saver (verdict 18) · Channels: mono (default now) / stereo · Normalization: on/off
(loudnorm at transcode) · Output device. A profile keys the transcode cache (the existing 8 GB
cache is `opus-96k-mono-loudnorm`); any other profile transcodes lazily on the PC (seconds a track),
with an optional background pre-transcode of the library.

### 3.7 Lyrics engine
Why G2CC drifts: the PC extrapolates the position, ticks at 1.5 s, and crosses two hops. Ours:
the scheduler runs on the phone from ExoPlayer's real position; a **per-output-device latency
offset** (Bluetooth adds ~100–250 ms), calibrated once on glass by nudging a line with the ring
(±50 ms notches) and remembered per device; each line is flushed **ahead by the known display
latency** (the measured `60 + bytes/50` curve) so it lands on the beat. Sources in order: the
`lyrics` table (LRCLIB), embedded tags, `.lrc` beside the file, NetEase, the unofficial
Musixmatch route (toggle), manual keyboard search (verdict 24). Plain-text fallback pages. The texture cache's glyph strings (mode 14)
are the later optimization behind the on-glass check (`REMINDER.md` items 19–20).

### 3.8 Visualizer
Data precomputed on the PC at transcode time (ffmpeg/librosa): a low-rate spectrum envelope
(bands × 4-bit levels), a waveform envelope, and beat times; stored beside the cache entry and
sent with the track. The phone renders from data + position. Candidates that fit the display
(each a small dirty region, one flush per frame, RLE-friendly): **Bars** (cava-style spectrum,
gravity/smoothing), **Scope** (a scrolling waveform strip via mode-9 shift + a thin new column —
the endless-scroll idiom), **Pulse** (beat-synced size/depth of the card), **Meter** (VU bars).
Frame rate is a setting; the achievable rate is measured on glass, not assumed. Off is an option.

### 3.9 YouTube ingest
`yt-dlp "ytsearch10:<query>"` → results listed → pick → audio-only grab into
`~/Music/YouTube/` (explicit request only, never a silent fallback) → index → transcode →
enrichment pass (moods/genres profile, embeddings) → lyrics fetch → playable, browsable, in the
knowledge base like any track. Progress rides the title notice; done/failed are notifications.

### 3.10 Backends and Spotify
Per `EXPLOSION.md` §16.10: backends in preference order (PC library, Spotify-on-phone); the
window is available if any backend's needs are met; the channel's staleness clock drives the
sustained-loss threshold (a liveness decision, not a timeout). **Automatic switch to Spotify
on PC loss (Settings, default on); switchback deliberate** — the "Back to PC
library" row appears the moment the PC is reachable again. Spotify is controlled through its
media session (notification-listener grant, one-time on the phone) and started cold through its
media browser service. Main's summary names the live backend (`▶ Spotify · phone`).

### 3.11 Notifications and Settings → Music
Sources: track change (on) · queue end (on) · route loss / paused (on) · PC unreachable (off) ·
YouTube grab done/failed (on) · playlist saved (off). Rows: Notify · … · Volume · Volume boost ·
Hold my volume · Output · Quality · Channels · Normalization · Mode default · Prefetch tracks ·
Lyrics offset · Visualizer · Visualizer rate · Music Mode surfaces · Spotify fallback (auto,
default / never) · Sleep (off / after this track / a timer) · Phone notifications (off, the
APK-wide switch) · Size · Font/Size/Style/Depth.

### 3.12 The takeover
- **Reused as-is**: the Postgres schema and data, the Qdrant collection, the media cache, the
  Python enrichment + embedding scripts (copied into `damagewm/audio/`, run on the existing venv
  via a config path until Damage owns a venv), yt-dlp, the lyrics table.
- **Ported to Kotlin in the Damage host**: library queries, queue/transport truth, the resolver
  lanes (the LLM lane via `claude -p --model <latest opus> --effort low|medium`, env-scrubbed,
  deterministic fallback on any failure), playlists, the YouTube flow, the lyric engine's fetch
  side, the transcode/profile/cache, the stream server (Range-capable), the visualizer precompute.
- **New**: the APK sink (media3 ExoPlayer + MediaSession + media-playback foreground type +
  output routing + volume/hold/boost), the notification listener (Spotify + the limiter notice),
  Music Mode, the visualizer renderers, the phone catalog cache + prefetch.
- **Ownership**: Damage is the only writer of the music tables from now on; G2CC's server keeps
  serving the setup page (its one-shot boot scan is idempotent). Postgres access from Kotlin:
  peer auth over the Unix socket (an Apache-licensed socket library + pgjdbc; no passwords).

## 4. Open before the build (Adam)

All of the first round's items are settled in §1 (verdicts 14–15, 18, 23–29). One remains:

1. **The Postgres collation warning** on `g2cc` (created under glibc 2.42, the system now has
   2.43). Postgres sorts text with the C library's rules; when the library changes, indexes over
   text columns *could* be ordered by the old rules. The safe fix is `REINDEX DATABASE g2cc;`
   then `ALTER DATABASE g2cc REFRESH COLLATION VERSION;` (the second line only clears the
   warning). Adam's go, since it touches his database; the build does not depend on it.

## 5. Architecture and module map (the build plan, written 2026-09-02 at max effort)

Two halves with one contract between them. **Library** = the PC (Postgres/Qdrant/files/ffmpeg/
yt-dlp/Python); **Player** = the phone (ExoPlayer + media session). The window is written once
against two core interfaces and never knows which host it runs on.

```
core/src/main/kotlin/wm/damage/core/windows/music/
  MusicModel.kt        data classes: TrackRef, TrackMeta, Album, Artist, Playlist, QueueEntry,
                       PlayerState, Lyrics/LyricLine, VizData, AudioProfile, YtResult, ResolvedQueue,
                       Backend, PcLink; the MusicLibrary + MusicPlayer interfaces; Fmt helpers
  QueueEngine.kt       PURE queue logic: modes (Queue · Shuffle · Radio · Library random), next/prev,
                       "shuffle keeps the current entry first", radio low-water append request,
                       remove/move/insert, entry ids (qid) — unit-tested without a host
  LyricsSync.kt        PURE: LRC parse (line + enhanced word stamps), the scheduler math
                       (position + device offset → current line; next flush time = line time −
                       offset − display latency estimate), seek/pause re-sync — unit-tested
  Viz.kt               PURE renderers over VizData: BarsViz · ScopeViz · PulseViz · MeterViz,
                       each paints ONE rect per frame (fid budget, §12)
  MusicWindow.kt       the window (§8): all levels at 288/352/416/480, Music Mode surfaces,
                       Settings rows, notifications, summary, deep links, persistence
  LocalMusicLibrary.kt PC: MusicDb (Postgres over the Unix socket) + Qdrant + files
                       (`MusicDb.kt`, `Qdrant.kt`, `Transcoder.kt`, `MediaCache.kt`,
                       `MediaServer.kt`, `Resolver.kt`, `LyricsFetch.kt`, `YouTube.kt`,
                       `Enrich.kt`, `VizPrecompute.kt` — one class each, all in this package)
  MusicNet.kt          MusicService (host side of the window channel) + RemoteMusicLibrary
                       (phone side: catalog/art/viz/lyrics caches on disk, version cursors)
  MirrorMusicPlayer.kt desktop: a MusicPlayer with no sink — shows the synced record, refuses
                       transport commands LOUDLY ("playback needs the phone")
  (as built, 2026-09-02, also:) Db.kt (the SQL seam core talks to; PgDb in :desktop) ·
                       Rules.kt (the queue post-processing every lane applies) · Plugins.kt
                       (AskResolver / LyricsFetcher / YtClient / Ingester — the leaf modules'
                       fixed interfaces) · LibraryScan.kt · PlayerCore.kt (the player LOGIC
                       shared by the phone and the in-memory SimMusicPlayer) · ClaudeOneShot.kt
                       + EmbedQuery.kt (the lane-2/3 subprocesses)
phone/src/main/kotlin/wm/damage/phone/music/
  AndroidMusicPlayer.kt  PlayerCore over an ExoPlayer sink + media3 MediaSession (a
                         ForwardingPlayer routes the buds' next/previous to OUR queue) + audio
                         focus + output routing + volume sync + HoldVolume + Boost
                         (LoudnessEnhancer) + sleep + prefetch (as built)
  MusicListener.kt       NotificationListenerService: active media sessions (Spotify) + the
                         system "volume lowered" notice
  SpotifyRemote.kt       MediaController over Spotify's session; cold start via its media
                         browser service; state → PlayerState(backend = SPOTIFY)
  TrackCache.kt          prefetch store (next N tracks, LRU by queue distance), served to
                         ExoPlayer from disk; falls back to the PC stream
desktop/src/main/kotlin/wm/damage/desktop/
  ScriptedMusic.kt     deterministic library + player for --selfcheck / --snapshot
  Main.kt              Config keys (§9.7), registration (auto stack + --host-only), MediaServer
audio/                 the enrichment package taken over from G2CC (§9.5) + `viz.py`
```

## 6. Contracts

### 6.1 `MusicLibrary` (core; Local on the PC, Remote on the phone)
```
interface MusicLibrary {
  fun stateLine(): String                       // "" or "PC unreachable 40s" / "library: <err>"
  fun catalog(): Catalog                        // cached; Remote serves the disk cache when offline
  fun refreshCatalog()                          // paced; version cursor over the channel
  fun search(q: String): List<TrackRef>
  fun ask(request: String): ResolvedQueue       // §9.3 lanes; never throws for a lane failure
  fun similar(trackIds: List<Int>, n: Int): List<TrackRef>     // radio (Qdrant recommend)
  fun randomLibrary(n: Int): List<TrackRef>     // library-random mode (dedupe + SFX/spoken rules)
  fun playlists(): List<Playlist>; fun playlistTracks(id: Int): List<TrackRef>
  fun savePlaylist(name: String, trackIds: List<Int>, overwrite: Boolean): Playlist
  fun renamePlaylist(id: Int, name: String); fun deletePlaylist(id: Int)
  fun setPlaylistTracks(id: Int, trackIds: List<Int>)          // adaptive → throws "adaptive"
  fun lyrics(trackId: Int): Lyrics?; fun searchLyrics(trackId: Int, query: String): List<Lyrics>
  fun setLyrics(trackId: Int, choice: Lyrics)
  fun art(trackId: Int, px: Int): ByteArray?    // 4-bit gray, px×px, box-sampled on the PC
  fun viz(trackId: Int): VizData?
  fun ytSearch(q: String): List<YtResult>; fun ytGrab(id: String): String   // job id
  fun ytStatus(job: String): YtJob              // phase, percent, trackId when done, error
  fun played(trackId: Int, startedMs: Long, endedMs: Long, completed: Boolean, skipped: Boolean)
  fun streamUrl(trackId: Int, profile: AudioProfile): String   // the §6.3 media endpoint
  fun addListener(l: Listener); fun removeListener(l: Listener)   // catalog changed, yt job, state
  fun setFocused(focused: Boolean, paceMs: Long)
}
```
Catalog = tracks (id, title, artist, album, durMs, trackNo, discNo, year, genres, moods, styles,
energy, vocals, hasLyrics, hasArt, dupeCluster) + artists + albums (case-insensitive grouping,
the library has real case-duplicates) + playlists (id, name, origin, adaptive, count) + vocab
(genres/moods/styles with counts) + version. ~3 k rows: one JSON blob, cached on the phone.

### 6.2 `MusicPlayer` (core; Android on the phone, Mirror on the desktop, Scripted in tests)
```
interface MusicPlayer {
  val state: PlayerState        // backend, playing/paused/stopped, entry (qid, track), posMs,
                                // durMs, queue, index, mode, volume 0–100, boost %, output,
                                // pcLink (UP | DOWN(sinceMs)), sleep, holdVolume
  fun play(); fun pause(); fun toggle(); fun next(); fun prev(); fun stop()
  fun seekTo(ms: Long); fun seekBy(ms: Long)
  fun playQueue(tracks: List<TrackRef>, startIndex: Int, mode: Mode, label: String)
  fun playFrom(qid: Long); fun playNext(tracks); fun append(tracks); fun replace(tracks)
  fun remove(qid: Long); fun move(qid: Long, delta: Int); fun clear()
  fun setMode(mode: Mode); fun setVolume(pct: Int); fun setBoost(pct: Int)
  fun setOutput(id: String); fun outputs(): List<Output>
  fun setBackend(b: Backend); fun backToPc()    // deliberate switchback
  fun setSleep(s: Sleep); fun setHoldVolume(on: Boolean); fun setProfile(p: AudioProfile)
  fun positionMs(): Long        // EXACT, monotonic-extrapolated between ticks (lyrics use this)
  fun addListener(l: Listener)  // state(PlayerState), tick(posMs) paced, event(TrackChange|
                                // QueueEnd|RouteLost|Error|LimiterUndone|BoostOff|Sleep)
}
```
The player persists `PlayerState` minus volatile fields into the window's sub-record
`window.music.player` (queue, index, posMs every 10 s and on every change, mode, backend) so
the desktop mirror and a re-installed APK resume the same queue. Never auto-plays on boot.

### 6.3 Wire
- **Window channel `music`** (`MusicService` on the content port, the Torrents shape): ops
  `catalog` (version cursor → blob), `search`, `ask`, `similar`, `random`, `playlists`,
  `playlist`, `playlist.save/rename/delete/set`, `lyrics`, `lyrics.search`, `lyrics.set`,
  `art` (blob), `viz` (blob), `yt.search`, `yt.grab`, `yt.status`, `played`. Every failure is an
  in-band error (`TlException`-style message), never an empty answer.
- **Media endpoint** (`MediaServer`, JDK `HttpServer`, new `mediaPort` default **7404**, bound like
  the content port, token as a query parameter): `GET /track/<id>?token=&profile=<name>` →
  200/206, `Accept-Ranges: bytes`, `Content-Type: audio/ogg` (Opus) or the source's type
  (lossless passthrough); a cache miss transcodes to completion first (seconds; logged), then
  serves. Malformed Range → 200 full (ExoPlayer treats a 416 as fatal — G2CC lesson). The phone
  learns the port from `Prefs.mediaPort` (BuildConfig default). NO TIMEOUTS.
- **Push**: the host pushes `catalog` version bumps and `yt` job progress as unsolicited frames
  on the channel — the first use of the §16.10 push slice (as built: `WinService.Push.send(op,
  args, blob?)` on the host, `RemoteWin(onPush = …)` on the phone, a `wpush` frame).

### 6.4 Data formats
- **AudioProfile** `name · codec (opus|passthrough) · kbps · channels (1|2) · loudnorm`.
  Presets: **High** (opus 128 k mono / 192 k stereo — default) · **Standard** (96 k) · **Saver**
  (48 k) · **Lossless** (passthrough of the source file, no loudnorm). Cache key
  `<id>-<mtime>-<sha1(path)[:8]>.<ext>` under `~/.damage/media-cache/<profile>/`; the legacy
  G2CC cache (`~/.g2cc/media-cache`, same key, 8.1 GB) IS the `standard-mono-loudnorm` profile
  and is read in place. A Settings action pre-transcodes the library for the current profile in
  the background (paced, one ffmpeg at a time, resumable).
- **Lyrics** `source · lines[(tMs, text, words[(tMs, text)]?)] · plain?` from LRC text; stored
  in the `lyrics` table (artist, track, duration_s, synced, plain, found) — the build adds a
  `source` column and a `track_id` link (additive migration, `MusicDb` owns it).
- **VizData** `fps (20) · bands (24) · frames: 4-bit packed levels · rms: 4-bit per 20 ms ·
  beats: ms[]`, computed by `audio/viz.py` (librosa) at transcode time, stored as `<key>.viz`
  beside the cache entry, sent as a blob, cached on the phone with the track.
- **Art**: embedded picture (ffprobe/ffmpeg extract) else `folder.jpg|cover.jpg` else none;
  box-sampled to px×px 4-bit gray on the PC, cached as `<key>-<px>.gray`.

## 7. The APK

- **Dependencies**: `androidx.media3:media3-exoplayer` + `media3-session` (G2CC proved 1.5.1;
  as built 1.5.1 is pinned in `gradle/libs.versions.toml`). Manifest: `FOREGROUND_SERVICE_MEDIA_PLAYBACK`,
  `ShellService` type `connectedDevice|mediaPlayback`; `MusicListener` declared with
  `BIND_NOTIFICATION_LISTENER_SERVICE` (the one-time "notification access" grant — `DAILY.md`
  runbook; the window says "grant notification access on the phone" while missing).
  **No RECORD_AUDIO.** `MODIFY_AUDIO_SETTINGS` was not needed (as built: `setStreamVolume` and
  `setPreferredAudioDevice` work without it).
- **Sink**: ExoPlayer with `USAGE_MEDIA`/`CONTENT_TYPE_MUSIC`, audio focus GAIN (others pause;
  transient loss → pause, resume on regain), a 60–300 s LoadControl (Tailscale insurance), gapless
  via the prestaged next item, `setPreferredAudioDevice` for the Output row (devices from
  `AudioManager.getDevices(GET_DEVICES_OUTPUTS)`; "Auto" = the current route; **Auto refuses to
  start with no external output present** — the speaker plays only when chosen), pause on route
  loss (buds gone) with the notice. Media buttons through the session: play/pause · next ·
  previous (the buds' single/double/triple), **from anywhere**. ExoPlayer errors → notice + log.
- **Volume**: `STREAM_MUSIC` index ↔ percent; the player sets it; a receiver for the volume-change
  broadcast plus a 1 s poll as backup keeps the rows in step with the phone buttons. Every change
  is logged with its cause (`user-button`, `ring`, `settings`, `hold-reset`, `unknown-drop`).
- **HoldVolume**: `held` = the last level set by any user action. On a change to `new < held`
  where the drop is ≥ max(3 steps, 25 % of range) in one event (not a run of single steps) and not
  ours → limiter suspected → re-set to `held`, log it, glass notice "phone lowered the volume —
  restored". The listener's sighting of the system notice (package `com.android.systemui` or the
  Settings app, text matching volume + hearing/protect/lower — verify on device) is the high-
  confidence signal. Pacing rule: at most 3 re-sets in 10 minutes, then a notice that the phone
  keeps lowering it (never a loop).
- **Boost**: `LoudnessEnhancer(audioSessionId)`, target gain 0…+1200 mB (100…400 %), a limiter
  is inherent to the effect; **off when the track changes**, never persisted; shown on the card;
  a notice when volume = 100 % and boost > 100 %.
- **Prefetch** (`TrackCache`): the next N (default 3) queue entries' files for the current profile,
  fetched over the media endpoint into the app's cache dir, LRU beyond N+2; ExoPlayer plays the
  local file when present; the current + next are always kept. With the PC down the queue plays
  from cache and the card shows `PC unreachable 2m` (channel staleness first, media second).
- **Sleep**: off · after this track · 15/30/60/90 min — a deadline the player checks on ticks
  (pacing, not a timer wrapper); ends with pause and a notice.
- **Spotify** (`SpotifyRemote`): sessions via `MediaSessionManager.getActiveSessions(listener)`;
  cold start by connecting a `MediaBrowser` to Spotify's media browser service (discover the
  component through the package manager; log loudly if absent) then `play()`; state from the
  controller's metadata/playback state + art bitmap → gray. Automatic switch on PC loss (Settings
  default on) only when the library backend cannot continue (prefetch exhausted); switchback
  deliberate (`backToPc()`), the Menu row appearing when the channel is healthy again.
- **Registration**: `ShellService` builds `RemoteMusicLibrary` + `AndroidMusicPlayer` and
  registers `MusicWindow(library, player)`; `Prefs.mediaPort`; `stopStack` detaches both.
- **Phone notifications**: `urgentNotification` is gated by the new Global setting
  `Phone notifications` (off by default; errors → glass notice + log only). The foreground
  notification is untouched.

## 8. The window (`MusicWindow`)

Declares: `needs = {}` on the phone (the library is cached; the player is local) and `{HOST}` on
the desktop mirror; face Clear Sans; icon `multimedia-audio-player` (drawn fallback exists);
`preferredHeight` 480; short title `Music`.

### 8.1 Levels (all at 288/352/416/480 — the list kit pans; rows above/below the 64 px lens band
are 2+2 · 3+3 · 4+4 · 5+5 (the as-built `Layout` at each height; the plan's 6+6 was off by
one); the card is designed for the band at every size)
```
QUEUE (List, root) ──tap row──▶ ROW MENU (MenuSurface)  ──▶ back to QUEUE
  │ wrap-end row = MENU ──▶ Menu rows (§8.2)
  ├─ BROWSE (List) → ARTISTS → ARTIST (albums + all tracks) → TRACKS
  │                 → ALBUMS → ALBUM → tracks · MOODS & GENRES → vocab word → tracks
  │                 → PLAYLISTS → PLAYLIST (rows; row 0 = "Play at random") → row menu
  │                 → COLLECTIONS (folder tree) · RECENT (play_history) · YOUTUBE…
  ├─ LYRICS (Document; the scheduler advances the top line; scroll = nudge offset ±50 ms
  │          in calibration, tap keeps; double-tap back)
  ├─ SEEK (List: −5 min · −30 s · −10 s · +10 s · +30 s · +5 min · Restart · Back)
  ├─ VOLUME (List: the level with ▲/▼ rows; scroll adjusts live, tap keeps — the Depth-row shape)
  ├─ ASK / SEARCH / YT SEARCH / RENAME / SAVE-AS → the keyboard (§4.8), draft kept
  └─ MUSIC MODE (exclusive, §8.3) ── double-tap ──▶ QUEUE
```
- **Card (the lens)**: art 56 px (or the drawn note) · title · artist — album · a 12-block bar +
  m:ss / m:ss on the current entry (queue position i/n and the mode word on others) · state
  glyph ▶ ❚❚ ■ · backend/link badge (`PC` · `PC ↓ 2m` · `Spotify`) · boost badge when active.
  Cursor rests on the current entry at every level change; the row identity is `qid`.
- **Row menu**: Play from here · Play next · Remove (never for the current entry) · Move up ·
  Move down · Add to playlist… · Track info (Document). Cancel first.
- **Empty queue**: one row "Nothing queued — tap for Browse" + the Menu row.
- **Confirms**: Clear queue · Delete playlist (Cancel first, the unrecoverable row at index 2) ·
  Save over an existing playlist name (asked twice) · Replace queue while playing.
- **Ask**: keyboard → `ask()` → the honest lane line in the title notice → `playQueue`.
  **Search**: results list; none → "Search YouTube…" row. **YouTube**: results (title · channel ·
  m:ss) → pick → confirm "Grab and add?" → progress in the title notice (push frames) → done
  notification with `t:<id>`; the new track is offered Play now / Play next.
- **Deep links**: `t:<trackId>` (queue row if queued, else Track info), `pl:<id>`, `mode:music`
  (enter Music Mode), `yt:<job>`.
- **Summary** (cheap, from `player.state`): `▶ Title — Artist` + progress · `❚❚ Title` ·
  `▶ Spotify · phone` · `♪ 25 queued` · `♪ idle` · `player: phone needed` (desktop); detail =
  album · `q i/n` · mode.

### 8.2 Menu (wrap-end row; the order is the cursor-rest order)
Pause/Resume · Next · Previous · Volume… · Ask… · Browse · Playlists · Moods & genres · Search…
· Mode: Shuffle/Queue/Radio/Library random · Lyrics · Seek… · Save queue as playlist… · Music
Mode · Output… · Sleep… · Stop · [Back to PC library] · [Switch to Spotify]. Every row wraps and
elides through `Draw.fit`; nothing is cut.

### 8.3 Music Mode (shell `Mode.EXCLUSIVE`)
- `ShellServices.enterExclusive(window)` / `exitExclusive()`; the shell paints the whole panel
  through `window.paintExclusive(g, rect, height)`; **input: everything swallowed except
  double-tap → exit** (the `Mode.SILENT` branch generalized; long-press never arms; the chord
  cannot fire); notices show as in silent mode (verdict 23); the mode persists like SILENT and
  restores after a driver swap only if the window is still registered. `DESIGN.md` gains **§4.9
  Exclusive mode** (the build writes it) and §1.5 gains the sibling sentence.
- Surfaces (each on/off + order in Settings → Music → Music Mode): **Card** (large: art 120 px at
  480 / 56 px at 288, title/artist/album, bar) · **Lyrics** (3/5/7/9 lines by height, current
  line bright) · **Visualizer** (strip 608×48…96) · **Queue peek** (next 2–3) · **Clock** ·
  **PC link**. Defaults: Card + Lyrics on, Visualizer off, Clock on, PC link on.
- Repaint policy: the card on track change and every 5 % of progress; lyrics on line change;
  visualizer at its rate; each repaint is ONE dirty rect per surface (§12).

### 8.4 Settings → Music (HostSetting rows)
Notify · track change (on) · queue end (on) · route loss (on) · PC unreachable (off) · YouTube
(on) · playlist saved (off) · Volume (0–100 by 5) · Volume boost (100/150/200/300/400 %) · Hold my
volume (on) · Output (supplier: Auto + devices) · Quality (High/Standard/Saver/Lossless) ·
Channels (mono default/stereo) · Normalization (on) · Default mode (Shuffle) · Prefetch
(1/2/3/5/10) · Lyrics offset (−500…+500 by 50, per output device) · Lyrics sources (LRCLIB+local /
+NetEase / +Musixmatch) · Visualizer (Off/Bars/Scope/Pulse/Meter) · Visualizer rate (4/8/12) ·
Music Mode: Card/Lyrics/Visualizer/Queue peek/Clock/PC link (on/off each) · Spotify fallback
(auto/never) · Sleep (off/after track/15/30/60/90) · Pre-transcode library (action) · Size ·
Font/Font size/Font style/Depth (automatic). Global gains **Phone notifications** (off).

## 9. The host

### 9.1 Postgres (`MusicDb`)
pgjdbc (BSD) over the Unix socket `/run/postgresql` via junixsocket (Apache-2.0), peer auth,
database `g2cc`, no password. Tables (verified 2026-09-02): `tracks(id, path, title, artist,
album, dur_ms, mtime_ms, indexed_at, track_no, disc_no)`, `track_meta(track_id, genres[],
styles[], moods[], energy, bpm, year, vocals, language, themes[], description, dupe_cluster,
sources jsonb, pass_status jsonb, updated_at)`, `playlists(id, name, origin, request, created_at,
updated_at, rule jsonb)`, `playlist_tracks(playlist_id, position, track_id)`, `lyrics(id, artist,
track, duration_s, synced, plain, found, fetched_at)`, `play_history(id, track_id, started_at,
ended_at, completed, skipped, source)`, `player_state(id, queue jsonb, idx, pos_ms, radio,
updated_at)`. Damage is the only writer from now on; additive migrations only (a `damage_schema`
row records them). Qdrant `g2cc_music`: point id == track id, payload track_id/artist/title,
384-dim cosine.

### 9.2 Files, transcode, stream
`Transcoder` = ffmpeg per §6.4 profile (`-map 0:a:0 -vn -ac <ch> -c:a libopus -b:a <k>k [-af
loudnorm=I=-16:TP=-1.5:LRA=11] -f ogg`), one at a time, resumable pre-transcode job; `MediaCache`
keys/paths; `MediaServer` per §6.3; the library walk (`AUDIO_EXTS` .mp3 .flac .m4a .ogg .opus .wav
.aac .wma .aiff, ffprobe tags, incremental by mtime) ported from `music.ts` for new files
(YouTube grabs; a manual "Rescan library" action). Art extraction via ffmpeg.

### 9.3 Resolver lanes (`Resolver`)
Lane 1 deterministic (port of `resolver.ts` semantics: exact artist/album/playlist, vocab words,
token search; post-processing: 'sound effects' excluded unless named, 'spoken word' excluded from
shuffle-class lanes, dupe-cluster dedupe with the higher-fidelity file winning, mild artist-spread
shuffle, size cap `queueSize` 25 except finite album/playlist sets). Lane 2: `claude -p --tools ""
--no-session-persistence --model <cfg> --effort <cfg> --system-prompt …` — **not `--bare`**
(measured 2026-09-02: `--bare` reads only `ANTHROPIC_API_KEY`, and Adam's CLI is signed in over
OAuth, so it answers "Not logged in" with exit 0; `ClaudeOneShot` keeps `bare` as an off-by-default
flag and judges the lane by parseability, never by the exit code), env scrubbed, a strict-JSON prompt (artists/albums/genres/moods/
styles/energy/free text) that lane 1 then executes; any failure or non-JSON → lane 3 → lane 1
random. Lane 3: `embed_query` (stdin text → 384-dim JSON, ~3.5 s cold) → Qdrant search. Every
result carries the lane + label + detail line. Radio = Qdrant `recommend` from the last 3 played
ids, minus the queue, minus dupes. Library random = lane-1 rules over the whole catalog.

### 9.4 Lyrics fetch (`LyricsFetch`), in order, first hit wins, all paced and cached (negatives too)
1. the `lyrics` table; 2. embedded tags (`LYRICS`, `UNSYNCEDLYRICS`, `lyrics-*` via ffprobe);
3. `<track>.lrc` beside the file; 4. LRCLIB `GET /api/get` (artist, track, album, duration) then
`/api/search`; 5. NetEase (public search + `song/lyric?lv=1` — endpoint shapes per the open-source
`syncedlyrics` project, MIT: read for facts, write our own); 6. the unofficial Musixmatch desktop
token route (same reference; behind the Settings toggle; expect it to stop working someday and
say so loudly, never silently). Manual search = the same chain with a typed query, results as
choices. AcoustID (Adam's existing key in `~/.g2cc/config.json`) stays with the enrichment
passes, not the fetch chain.

### 9.5 The Python package (`audio/`)
Copy `/home/user/G2CC/audio/enrich/` into `damagewm/audio/enrich/` (Adam's code, his licence);
`g2cc_config.py` → `damage_config.py` reading `~/.damage/config.json` (music keys, §9.7) with the
same cache-key rule; run with `musicPython` (G2CC's venv for now, untouched) from `audio/` as
`-m enrich.<module>`; add `viz.py` (§6.4). Ingest for a new track = `run_enrichment` passes
`tags musicbrainz lyrics audio profile embed dedupe` scoped `--track-id`, then viz, then the
transcode for the current profile. A Damage-owned venv is a later chore (`DAILY.md` records the
`pip freeze` of G2CC's).

### 9.6 YouTube (`YouTube`)
`yt-dlp --no-download --flat-playlist --dump-json "ytsearch10:<q>"` → results; grab = `yt-dlp -f
bestaudio -x --audio-format opus --embed-metadata --no-playlist --max-filesize 300m --no-simulate
--newline --progress --print after_move:filepath -o "<YouTube dir>/%(title)s [%(id)s].%(ext)s" --
<url>` (as built: `opus`, not `best` — the indexer's extension set has no webm; every flag verified
against `--help` 2026.06.09), explicit request only; then §9.5 ingest; job state pushed on the
channel. Never the first result unasked.

### 9.7 Config keys (`desktop/Main.kt` `Config`, `~/.damage/config.json`)
`musicDb` (g2cc) · `musicSocketDir` (/run/postgresql) · `musicQdrant` (http://127.0.0.1:6333) ·
`musicQdrantCollection` (g2cc_music) · `musicLibraryDirs` ([/home/user/Music]) ·
`musicLegacyCache` (~/.g2cc/media-cache) · `musicCache` (~/.damage/media-cache) · `musicPython`
(/home/user/G2CC/audio/venv/bin/python) · `musicYtDlp` (~/.local/bin/yt-dlp) · `musicYoutubeDir`
(YouTube) · `musicClaudeModel` (opus) · `musicClaudeEffort` (low) · `musicQueueSize` (25) ·
`mediaPort` (7404) · `musicAcoustidKey` (optional; Adam copies it from his G2CC config).

## 10. Tests, harnesses, gates

- **`MusicTest` (core)**: LRC parsing (plain, line, enhanced), scheduler math (offset, send-ahead,
  seek/pause re-sync, no double flush), `QueueEngine` (shuffle keeps current first, remove/move,
  mode changes, radio low-water, library random, current entry never removable), profile keys
  and the legacy cache mapping, lane-1 resolver over a fixed catalog (artist/album/playlist/vocab/
  search + every post-processing rule), the LLM/embedding fallback contract (a failing lane →
  the next), `HoldVolume` classification (limiter drop vs button steps vs our own set; the pacing
  rule), boost reset on track change, sleep deadline, Spotify switch/switchback state machine,
  the window grammar at **288 and 480** (levels, wrap-end menu, cursor rests, confirms, empty
  queue, deep links), persistence round-trip + the continuity test (state and sub-records),
  Music Mode enter/exit through the shell (swallow test: tap/scroll/long-press ignored,
  double-tap exits, the chord cannot fire), the remote library over a loopback host (catalog
  version cursor, art/viz blobs, yt job push), viz renderers (determinism, one rect per frame,
  ink ≤ budget).
- **Desktop**: `ScriptedMusic` drives the selfcheck walk (queue → row menu → browse → playlist →
  lyrics → ask via the keyboard → Music Mode at 288 and 480 → the done/track-change notice) with
  the ink budgets stated; snapshot scenes: card 288/480, queue, browse, lyrics, Music Mode 288/480
  with Bars and Scope, YouTube results, settings rows. `--music-check`: a read-only pass against
  the real Postgres/Qdrant/cache (counts, a sample query per lane, the cache-key mapping for 20
  random tracks) — the `--epub-check` shape; not part of `:core:test`.
- **Gates**: `:core:test` · `:desktop:test` · `--selfcheck` · `--snapshot` (look at every new
  scene at 1×) · `--epub-check` · `--music-check` · `tools/lint.py` · `:phone:assembleDebug` ·
  `:phone:stageApk` · `:desktop:stageJar`; every milestone commit leaves all of them green.

## 11. Build order — six milestones, a commit after each

Each milestone is whole on its own and leaves the battery green, so a token cutoff at any point
leaves a coherent tree; the next session resumes at the first unfinished milestone (HANDOFF §24
records which).

1. **M1 host foundation**: Config keys, `MusicDb` (+ migration row), `Qdrant`, catalog queries,
   `MediaCache`/`Transcoder`/profiles (legacy cache read in place), `MediaServer`,
   `LocalMusicLibrary` (catalog/search/random/playlists/art), `MusicService` + push frames,
   `RemoteMusicLibrary` + disk caches, `--music-check`. Service restarted on it (`mediaPort`).
2. **M2 core model + window**: `MusicModel`, `QueueEngine`, `MusicWindow` (all levels, four
   heights), Settings rows, notifications, summary, deep links, persistence; `ScriptedMusic`;
   selfcheck walk; snapshot scenes; `MirrorMusicPlayer`; desktop registration.
3. **M3 shell**: `Mode.EXCLUSIVE` + `enterExclusive/exitExclusive`, `LyricsSync`, `Viz`
   renderers, Music Mode surfaces + their settings, `DESIGN.md` §4.9; tests.
4. **M4 APK**: media3 deps + manifest, `AndroidMusicPlayer` (sink, session, focus, output,
   volume sync, HoldVolume, Boost, sleep, `TrackCache` prefetch), `MusicListener`, `SpotifyRemote`,
   `ShellService` registration + `mediaPort`, the Global phone-notifications switch.
5. **M5 host features**: `Resolver` (three lanes), playlists CRUD, `LyricsFetch` chain + manual
   search + `lyrics` migration, `YouTube` + the `audio/` package takeover + ingest pipeline +
   `viz.py`, `played` → `play_history`, radio via recommend, pre-transcode action.
6. **M6 docs + staging**: `MUSIC.md` → build record (as-built numbers), `IMPLEMENTATION.md`
   "Music" + module map, `HANDOFF.md` §24, `REMINDER.md`, `DAILY.md` (one-time phone grants:
   notification access; the volume probe; Spotify cold-start check; the mediaPort; the venv
   freeze), `WINDOWS.md` (five precedents), memory; APK 19/0.19 staged; jar staged; service
   restarted.

Delegation guide (token budget): M1's DAO/transcode/stream, M5's `LyricsFetch`, `YouTube`,
`viz.py` and the lane-1 `Resolver` port, M3's `Viz` renderers and M4's `SpotifyRemote` are leaf
modules with their interfaces fixed above — they can go to Opus 5 agents in parallel with a
copy of §6/§9 and the file list. The window, the queue engine, the shell mode, the Android player
and every integration step stay with the orchestrating session.

## 12. Traps and rules for the builder

- **Loop-only mutation** (`runOnShell`) for every provider/player callback; seq guards on async
  completions; never call a provider or the player from a paint; demand work only from `view()`
  or completions (the Torrents lesson). Listener registration idempotent; `detach()` on stack stop.
- **Row identity**: queue rows are keyed by `qid`, never by index — shuffle and radio reorder
  under the cursor. Restored cursors wait for content.
- **One dirty rect per surface per frame.** A visualizer painted bar-by-bar burns the mode-3 fid
  budget (~6 per batch) and gets silently skipped; paint the strip as one rect. Modes 12–15 are
  not used (the on-glass check is still owed); mode 15 never.
- **Pacing, not timeouts**: the lyric scheduler, sleep, prefetch, hold-volume all use scheduled
  loop ticks with generation stamps (the `SilentTick` shape). No `wait_for`, no time-bounded
  wrappers around I/O.
- **Loud failures**: ExoPlayer errors, transcode failures, a refused output, a stopped-working
  lyric source, a missing notification grant, a Spotify session that cannot be found — each is a
  notice with a duration or a reason, plus the log. Nothing is swallowed.
- **No truncation**: titles through `Draw.fit`/`dn()`, full text reachable in the lens or a
  document; lyrics wrap.
- **Misfire tolerance**: cursor rests harmless; Remove/Delete/Clear never at index 0/1; Stop at
  the menu's end; Replace-queue-while-playing confirms.
- **Never auto-play on boot; never the speaker unless chosen; never write to G2CC's code or its
  running server; never store the AcoustID key or any credential in a tracked file.**
- **All four heights** for every level and every Music Mode stack; snapshot 288 and 480.
- **Measured vs modeled**: every latency number in the record is modeled until a glass measures
  it; the visualizer's achievable rate and the Bluetooth lyric offset are measured items.
- **Wording**: `CLAUDE.md`'s plain-engineering table in code comments, notices and docs.

## 13. Kickoff for the build session

Read, in order: `CLAUDE.md` (loaded), this file whole, `WINDOWS.md`, `TORRENTS.md` §3–§4 and
`core/.../windows/torrents/{TorrentsNet,LocalTorrentsProvider,TorrentsWindow}.kt` as the wire and
window precedent, `DESIGN.md` §1.5 + §4.6 + §4.8, `Shell.kt`'s `Mode.SILENT` sites, and the G2CC
sources named in §2/§9 for facts. Then build M1→M6 in order, committing after each milestone with
the battery green, and end with the closing summary (what stands, what is measured vs modeled,
what waits on the phone). If the session's budget ends early, commit the finished milestones and
write the resume point into `HANDOFF.md` §24 first.
