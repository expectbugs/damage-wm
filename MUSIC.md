# Music on glass — design record (2026-09-02)

**Status: DESIGN IN DISCUSSION — verdicts below are Adam's and binding; §4 lists what is still
open; NOT BUILT.** The window is built whole on Adam's rule (no v1/v1.5 staging), as an overnight
autonomous build once §4 is closed, then the review loop (`REVIEW.md`). `EXPLOSION.md` §3 is
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
| 14 | Hearing-protection limiter | The phone lowers the volume after a long stretch at max and posts a notification. Detect it (the OS notification via the listener, and the large instant drop, which looks nothing like repeated volume-down taps) and **re-set the volume** to what Adam set. Setting "Hold my volume", default on. Pixel probe first. |
| 15 | Gain | **Not for undoing the limiter** (quality, and the accidental-loudness trap: max the volume while paused with gain active, then play). But a **separate "Volume boost" option** for rare quiet material, up to **200 % or more** of normal max, outside the normal volume control. |
| 16 | Music Mode | **Silent Mode with music décor**: the display shows only the configured music surfaces (card, lyrics, visualizer, …), **ring input is ignored except double-tap, which exits** Music Mode. No menu inside it. |
| 17 | Earbud taps | **The primary transport control, from anywhere**: single = play/pause, double = next, triple = previous (the buds' native gestures through the media session). Ring inputs are never remapped. |
| 18 | Codec | **Best sound quality by default**, alternatives configurable for a saturated link (§4 item 1 settles which "best" means). |
| 19 | Visualizer | Precomputed on the PC (no microphone permission), rendered in sync on the phone. **Only visuals that suit the G2** (4-bit gray, one flush per frame, the measured latency curve) — fast and responsive, never a lagging display. Several options plus **off**. Adam wants suitable open-source ideas adopted too. |
| 20 | Spotify switchback | **Deliberate, never automatic.** When Spotify was the *automatic* fallback, a **"Back to PC library" row appears in the app Menu as soon as the PC is reachable again**, and the player and Music Mode show **whether the PC is connected and, if not, for how long**. Spotify can be started cold. |
| 21 | Phone notifications | 🔴 **The APK stops sending errors to the phone.** Errors go to the glasses' notifications and the log. The one permanent foreground notification stays. Built with the Music build (a Settings toggle, default off). |
| 22 | Build shape | Overnight autonomous build, everything above, then the review loop. |

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
  (`TorrentsNet.kt`), the Files blob lane (`FilesNet.kt`). Still unbuilt on the channel and Music
  is their first customer: push frames, summaries-over-channel, per-backend `needs`.
- **`claude` CLI** exposes `--model`, `--effort` and `-p/--print` (checked 2026-09-02).

## 3. The design (settled parts)

### 3.1 Roles
- **Playback truth lives on the phone** (the shell runs next to ExoPlayer in the APK). **Library
  truth lives on the PC** (Postgres/Qdrant/cache/ffmpeg/yt-dlp/enrichment). The queue and the
  playback position are a §16 record synced both ways so the desktop shell shows the same state
  (it has no sink; it displays only). PC-only configuration: the window says playback needs the
  phone, exactly as G2CC did.
- **The phone caches the catalog** (a few thousand rows) so Browse works with the PC down, and
  **prefetches the next N queue tracks** (setting) so a Tailscale drop does not stop the music
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
- **Volume boost** (separate row, default off, 100–200 %+; the exact ceiling per §4 item 2): a gain
  stage on our own audio session with a limiter. Never touched by "Hold my volume". Shown on the
  card while active. Reset rule per §4 item 2.

### 3.6 Audio profiles (Settings → Music)
Codec/quality (§4 item 1) · Channels: mono (default now) / stereo · Normalization: on/off
(loudnorm at transcode) · Output device. A profile keys the transcode cache (the existing 8 GB
cache is `opus-96k-mono-loudnorm`); any other profile transcodes lazily on the PC (seconds a track),
with an optional background pre-transcode of the library.

### 3.7 Lyrics engine
Why G2CC drifts: the PC extrapolates the position, ticks at 1.5 s, and crosses two hops. Ours:
the scheduler runs on the phone from ExoPlayer's real position; a **per-output-device latency
offset** (Bluetooth adds ~100–250 ms), calibrated once on glass by nudging a line with the ring
(±50 ms notches) and remembered per device; each line is flushed **ahead by the known display
latency** (the measured `60 + bytes/50` curve) so it lands on the beat. Sources in order: the
`lyrics` table (LRCLIB), embedded tags, `.lrc` beside the file, the community fetchers (§4 item
4), manual keyboard search. Plain-text fallback pages. The texture cache's glyph strings (mode 14)
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
only on PC loss and only if allowed in Settings; switchback deliberate** — the "Back to PC
library" row appears the moment the PC is reachable again. Spotify is controlled through its
media session (notification-listener grant, one-time on the phone) and started cold through its
media browser service. Main's summary names the live backend (`▶ Spotify · phone`).

### 3.11 Notifications and Settings → Music
Sources: track change (on) · queue end (on) · route loss / paused (on) · PC unreachable (off) ·
YouTube grab done/failed (on) · playlist saved (off). Rows: Notify · … · Volume · Volume boost ·
Hold my volume · Output · Quality · Channels · Normalization · Mode default · Prefetch tracks ·
Lyrics offset · Visualizer · Visualizer rate · Music Mode surfaces · Spotify fallback (auto/never)
· Phone notifications (off, the APK-wide switch) · Size · Font/Size/Style/Depth.

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

1. **"Best quality" codec default.** The earbud hop is lossy regardless (A2DP re-encodes), so
   lossless to the phone buys nothing audible over the buds and costs ~8–10× the data on LTE.
   Proposal: default **Opus high** (128 k mono / 192 k stereo), options **Lossless passthrough**
   (FLAC as-is), **Standard** (96 k), **Saver** (48 k). Or lossless as the default regardless?
2. **Boost semantics**: ceiling (200 % = +6 dB; 400 % = +12 dB?), reset rule (off at the end of
   the track it was set on unless "keep", or remembered per track/album?), and the guard against
   the paused-then-max trap (boost shown on the card; a notice when both max volume and boost).
3. **Music Mode + notices**: temporary notices still show over Music Mode (default yes)?
4. **Lyrics community fetchers**: which ones are acceptable (Musixmatch-style unofficial APIs
   are grey; NetEase is public). LRCLIB + embedded + `.lrc` are certain.
5. **The Spotify auto-fallback setting** default: auto on PC loss (proposed) or never?
6. **Per-height numbers**: the card at 64 px band height at every size (art 56 px, two lines);
   queue rows above/below (2+2 at 288 … 6+6 at 480); lyrics 3/5/7/9 lines; Music Mode stacks.
   Confirm the principle; the numbers are set from real renders.
7. **G2CC's server**: keep it running for the setup page (proposed; its boot scan is idempotent)
   or move the page to a static Caddy directory and stop the server for good?
8. **The Postgres collation warning**: refresh it (`ALTER DATABASE g2cc REFRESH COLLATION
   VERSION`) before the build, or leave it?
9. **Sleep/stop options** (Stop after this track · Stop in 30/60 min) — cheap, wanted?

## 5. Build order (one pass, whole) and gates

1. Host: DB access + library queries + profile/transcode/cache + stream + scripted provider.
2. Host: queue/transport model, resolver lanes, playlists, YouTube ingest, lyrics fetch,
   visualizer precompute. Window channel service (`music`), push frames for state.
3. APK: sink (ExoPlayer, media session, output, volume/hold/boost), notification listener,
   catalog cache + prefetch, Spotify remote.
4. Core: `MusicWindow` (all levels at all four heights), Music Mode, the lyric scheduler, the
   visualizer renderers, Settings → Music, notifications, the phone-notification switch.
5. Tests: `MusicTest` (model/resolver/queue/lyrics timing/profiles/wire), `ScriptedMusic` for
   selfcheck + snapshot scenes (card, queue, lyrics, Music Mode at 288 and 480, visualizer frames),
   persistence + continuity, keyboard uses. Full battery green; lint 0; APK + jar staged.
6. Docs: this file becomes the build record; `IMPLEMENTATION.md`, `HANDOFF.md` §24,
   `REMINDER.md`, `DAILY.md` (the Pixel one-time grants: notification access, battery), memory.
