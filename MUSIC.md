# Music on glass — design record (2026-09-02)

**Status: BUILT 2026-09-01/02 (M1–M6, a commit per milestone; `HANDOFF.md` §24 is the build
record); §4 closed; reviewed three ways the same day (§24.2, §24.3) and in the 2026-09-03 review
(§25).** What remains: the on-phone checks (`DAILY.md` → Music). `EXPLOSION.md` §3 is the idea
record; `/home/user/G2CC/docs/MUSIC_SPEC.md` the previous player's decisions (facts only — the
G2CC music *system* is Damage's now; code ported, never pasted). Where §3 (the design) and §7–§9
(as built) overlap, the as-built section is what runs.

## 1. Verdicts (Adam, 2026-09-02)

| # | question | verdict |
|---|---|---|
| 1 | Library source | **The G2CC music system is Damage's now** — Postgres `g2cc` tables, the Qdrant `g2cc_music` collection, the media cache, the Python enrichment + embedding pipeline, yt-dlp. G2CC itself is retired and never returns. |
| 2 | Where audio plays | **The phone.** Earbuds by default; an Output option for anything connected to the phone, the speaker included. **Never the PC.** |
| 3 | Backends | PC library primary; **Spotify on the phone** as the selectable fallback (automatic when the PC is unreachable). Other apps maybe later. Desktop Spotify: never. |
| 4 | Root view | 🔴 **REVERSED 2026-09-03 (Adam, after living with it): the root is a NOW PLAYING screen and the queue is a menu row.** *"Lets put the queue as a menu option rather than the main screen … the main screen should be a useful, really nice looking Now Playing screen. That way i can see what is playing and where in the song it is and what the volume level is at etc."* The original verdict — queue-as-list with the Now Playing card as the lens, Menu on the wrap-end row — is superseded; do not reinstate it. |
| 5 | Ask (fuzzy requests) | **The latest Claude Opus at low or medium effort** via the CLI one-shot; the deterministic and embedding lanes as the instant fallback. Same model for enriching new tracks. |
| 6 | Lyrics | **In, and much better synced than G2CC.** Sources: LRCLIB, embedded tags, `.lrc` beside tracks, the community fetchers; manual search on the keyboard. |
| 7 | YouTube | **In**: results listed for Adam to pick from (never the first hit), audio-only grab into `~/Music/YouTube/`, then **fully ingested** — indexed, transcoded, enriched, lyrics, embeddings. Offered when a search finds nothing in the library, and from Browse. |
| 8 | Playlists | **Full editing on glass** (create, save queue, add current, rename, reorder, remove, delete). |
| 9 | Playback modes | Default = **shuffle the current/most-recent queue**. **Radio** (nearest neighbours appended when the queue runs low) is selectable, not the default. **Library random** is a third mode. No auto-play on boot: a bud tap or Play starts it. |
| 10 | Track-change notices | **Default on**, toggle in Settings → Music. |
| 11 | Heights | 🔴 **Every window works well at all four sizes (288/352/416/480) and each app's size is adjustable in Settings** — a rule in `WINDOWS.md` §1. Music's card, queue, lyrics and Music Mode are each specified per height. |
| 12 | Adam-specific defaults | **Nothing is baked in**: mono/stereo, normalization, codec/bitrate, output, volume, lyrics, visuals are Settings rows. **Mono to start** (one work earbud today); release defaults chosen later in a global pass. |
| 13 | Volume | In Settings **and** in the app, **synced both ways with the phone's media volume**. |
| 14 | Hearing-protection limiter | The phone lowers the volume after a long stretch at max and posts a notification; **there is no confirmation dialog on Adam's phone** — volume-up restores it, and the phone sits on the cart out of reach. Detect the event (the OS notification via the listener, or the large instant drop, which looks nothing like repeated volume-down taps) and **re-set the stream to the held level**. "Hold my volume", default on. The APK logs every volume change with its cause. |
| 15 | Gain | **Not for undoing the limiter** (quality, and the accidental-loudness trap: max the volume while paused with gain active, then play). A **separate "Volume boost"** for rare quiet material, outside the normal volume control: **ceiling 400 % (+12 dB), off when the track ends, never remembered.** Shown on the card; a notice when max volume and boost are both on. |
| 16 | Music Mode | **Silent Mode with music décor**: only the configured music surfaces; **ring input ignored except double-tap, which exits.** No menu inside it. |
| 17 | Earbud taps | **The primary transport control, from anywhere**: single = play/pause, double = next, triple = previous (the buds' native gestures through the media session). Ring inputs are never remapped. |
| 18 | Codec | Adam: *"whatever quality the BLE forces"* — clarified: no audio crosses the glasses' BLE; the ceiling is the **phone → earbud A2DP link**. Default **Opus high (128 k mono / 192 k stereo)**; **Lossless** (FLAC passthrough — the phone on an AUX cable into a stereo), **Standard** (96 k) and **Saver** (48 k) as options. |
| 19 | Visualizer | Precomputed on the PC (no microphone permission), rendered in sync on the phone. **Only visuals that suit the G2** (4-bit gray, one flush per frame, the measured curve) — never a lagging display. Several options plus **off**; suitable open-source ideas adopted too. |
| 20 | Spotify switchback | **Deliberate, never automatic.** After an *automatic* fallback a **"Back to PC library" row appears in the Menu as soon as the PC is reachable again**; the player and Music Mode show **whether the PC is connected and, if not, for how long**. Spotify can be started cold. |
| 21 | Phone notifications | 🔴 **The APK stops sending errors to the phone** — the glasses' notifications and the log instead. The one permanent foreground notification stays. A Settings toggle, default off. |
| 22 | Build shape | Overnight autonomous build, everything above, then the review loop. |
| 23 | Notices in Music Mode | **Yes** — temporary notices still show over Music Mode, as in silent mode. |
| 24 | Lyrics sources | **Everything reachable without a new account**: LRCLIB, embedded tags, `.lrc` files, NetEase's public endpoint, the unofficial Musixmatch route (keyless; may stop working — behind a toggle), plus any key Adam already made for the G2CC player (read from his G2CC config). MusicBrainz needs no key. |
| 25 | Spotify auto-fallback | **Automatic on PC loss** (default on). Spotify is installed and signed in. |
| 26 | Per-height numbers | Principle confirmed; the numbers come from real renders and are adjusted on glass. |
| 27 | G2CC's server | ~~Keeps running for now (the APK setup page)~~ **RETIRED 2026-09-10**: the setup page and the adaptive-playlist refresh are Damage's (§9.8, `HANDOFF.md` §44). |
| 28 | Sleep | **Sleep options in Settings, default off**: after this track, or after any timer. |
| 29 | Prefetch | **Three tracks ahead**, adjustable in Settings. |

Recorded so it is not re-pitched: no PC audio output; no ring remapping in Music Mode; gain never
undoes the limiter; no auto-play on boot; Spotify only as the phone fallback (no desktop client);
radio is not the default.

## 2. Facts the design stands on (verified 2026-09-01/02, read-only)

- **The glasses have no speaker.** Audio is phone → Pixel Buds 2a over A2DP (one earbud at work);
  BLE + A2DP coexistence over a full shift was field-verified 2026-08-04 (G2CC `MUSIC_SPEC.md`).
- **G2CC's model** (`server/src/music-player.ts:1-19`): the PC owns queue and transport state, the
  phone's ExoPlayer is the sink; stream `GET /media/track/:id` with Range; transcode `ffmpeg … -ac
  1 -c:a libopus -b:a 96k -af loudnorm=I=-16:TP=-1.5:LRA=11 -f ogg` into `~/.g2cc/media-cache`
  (2,981 files, 8.1 GB, one profile); ExoPlayer 60–300 s buffer; gapless via a prestaged next
  item; never auto-play on boot.
- **Library:** 2,981 tracks / 49 GB under `/home/user/Music` (`Library/<Artist>/[<Album>/]`,
  `Collections/`, `Archive/Dupes/`, `Unsorted/`, `YouTube/`, `new/`); 1,754 FLAC · 631 MP3 · 553 OGG
  · 29 WAV · a few M4A/WMA/Opus; 68 folder JPEGs. Postgres `g2cc`: `tracks` 2,981, `track_meta`
  2,981, `playlists` 25 (all adaptive), `play_history` 388, `lyrics` 2,194 rows / **542 found (428
  synced)**, `player_state` 1. Qdrant `g2cc_music`: 2,981 points, 384-dim
  (`BAAI/bge-small-en-v1.5`). Peer auth over the Unix socket, no password (`store.ts:34`). The
  collation warning (2.42 vs 2.43): §4.
- **G2CC's server** (`node dist/index.js`, :7300) ran until 2026-09-10 for the APK setup page; it
  also re-derived the 25 adaptive playlists at every boot, and its speech daemon held 10 GB of
  VRAM. Both jobs are Damage's now (§9.8, `desktop/SetupServer.kt`); its boot scan runs nowhere.
- **Enrichment pipeline:** `/home/user/G2CC/audio/enrich/` (`run_enrichment.py`, `embed_query.py`,
  `passes/`, `db.py`) on `/home/user/G2CC/audio/venv`. yt-dlp 2026.06.09 at `~/.local/bin/yt-dlp`.
- **The Damage APK** had no media stack at design time; M4 (§7) added ExoPlayer, MediaSession, the
  notification listener and the media-playback foreground type. No RECORD_AUDIO, and that stays.
- **The ring** sends five events: tap, double-tap, scroll up/down, long-press + release
  (`EvenHubMsg.kt:213-222`). No triple-tap event exists.
- **Shell pieces already waiting:** `IconKind.MUSIC` + theme names `multimedia-audio-player` /
  `audio-x-generic`; `ShellSettings.notifyMusic` + `noticeAllowed("MUSIC")` (the toggle lives in
  Settings → Music); Silent Mode's input path (`DESIGN.md` §1.5) is the model for Music Mode.
- **The seam:** the §16.10 window channel (`WinNet.kt`), the Torrents Local/Remote split
  (`TorrentsNet.kt`), the Files blob lane (`FilesNet.kt`). Music built the channel's PUSH slice
  (`WinService.Push` + `RemoteWin(onPush)`, the `wpush` frame); summaries-over-channel and a
  per-backend `needs` contract stay unbuilt (Music declares `needs` per host).
- **`claude` CLI** exposes `--model`, `--effort` and `-p/--print` (checked 2026-09-02).

## 3. The design (settled parts)

### 3.1 Roles
**Playback truth lives on the phone** (the shell runs next to ExoPlayer in the APK); **library
truth lives on the PC**. Queue and position are a §16 record synced both ways, so the desktop
shell shows the same state (no sink; display only) and a PC-only configuration says playback needs
the phone. **The phone caches the catalog** (Browse works with the PC down) and **prefetches the
next 3 queue tracks** (setting) so a Tailscale drop does not stop the music. The card and Music
Mode show the PC link state and its staleness age.

### 3.2 The window (`MusicWindow`, id `music`)
**ROOT = NOW PLAYING (Canvas)** — reversed from the queue-with-card root 2026-09-03 (verdict 4):
one TOP-aligned screen in four bands — art (160/120/88 px by height) + title + artist — album +
badges · ▶ elapsed · progress bar · total · vol · level bar · % + queue position + mode (the level
drawn HOT at or below 10 %, `HANDOFF.md` §24.4) · the current synced lyric line when it fits.
**Scroll = volume, live. Tap = the Music menu. Double-tap = back to Main.** No cursor. The QUEUE
is a menu row one level down (cursor at rest on the current track; the current row never
removable). Everything else as built: §8.

### 3.3 Music Mode
Silent Mode with music décor (`DESIGN.md` §1.5's input path): **all ring input swallowed except
double-tap, which exits to the window**; long-press never arms; the chord cannot fire; no menu;
temporary notices still show (verdict 23). Surfaces and per-height numbers: §8.3.

### 3.4 Earbud transport
The APK holds a `MediaSession`; the buds' native gestures arrive as media-button events (single
play/pause · double next · triple previous) and act **from anywhere**, whichever window is up.
With Spotify as the backend the buds drive Spotify's own session.

### 3.5 Volume, hold, boost
Volume = the phone's media stream (absolute volume on the buds), in Settings → Music and Menu →
Volume… (scroll live, tap keeps); phone buttons move the rows. **Hold my volume** (default on)
re-sets the stream to the held level on the limiter's signature — the OS notice, or one large
instant drop, never a run of single steps; if the OS parks the raise behind a confirmation, a
glass notice says "phone lowered the volume — confirm on the phone". **Volume boost** (separate
row, default off, 100–400 %): a gain stage with a limiter on our own session, never touched by
Hold my volume, **off when the track ends, never remembered**. Mechanism: §7.

### 3.6 Audio profiles (Settings → Music)
Quality Opus high (default) / Lossless / Standard / Saver (verdict 18) · Channels mono (default
now) / stereo · Normalization on/off · Output device. A profile keys the transcode cache (§6.4);
other profiles transcode lazily on the PC, seconds a track, with an optional pre-transcode.

### 3.7 Lyrics engine
Why G2CC drifts: the PC extrapolates the position, ticks at 1.5 s, and crosses two hops. Ours: the
scheduler runs on the phone from ExoPlayer's real position; a **per-output-device latency offset**
(Bluetooth adds ~100–250 ms), calibrated once on glass by nudging a line with the ring (±50 ms
notches) and remembered per device; each line flushed **ahead by the known display latency** (the
phone path's ~70 ms + ~120 ms/KB, `REMINDER.md`). Sources: §9.4 (verdict 24). Plain-text fallback
pages. Mode-14 glyph strings were the later optimization (`REMINDER.md` items 19–20; shell-wide as
`Cached text` since 2026-09-06).

### 3.8 Visualizer
Data precomputed on the PC (ffmpeg/librosa: a low-rate spectrum envelope, a waveform envelope,
beat times — §6.4), sent with the track; the phone renders from data + position. Candidates that
fit the display (one small dirty region, one flush per frame, RLE-friendly): **Bars** (cava-style
spectrum, gravity/smoothing), **Scope** (a scrolling waveform strip via mode-9 shift + a thin new
column), **Pulse** (beat-synced size/depth of the card), **Meter** (VU bars). Frame rate is a
setting; the achievable rate is measured on glass, not assumed. Off is an option.

### 3.9 YouTube ingest
Search → results listed → pick → audio-only grab into `~/Music/YouTube/` (explicit request only,
never a silent fallback) → index → transcode → enrichment → lyrics fetch → a track like any other.
Progress rides the title notice; done/failed are notifications. Commands: §9.6.

### 3.10 Backends and Spotify
Per `EXPLOSION.md` §16.10: backends in preference order (PC library, Spotify-on-phone); the window
is available if any backend's needs are met; the channel's staleness clock drives the
sustained-loss threshold (a liveness decision, not a timeout). **Automatic switch to Spotify on PC
loss (Settings, default on); switchback deliberate** — the "Back to PC library" row appears the
moment the PC is reachable again. Spotify is driven through its media session
(notification-listener grant, one-time) and started cold through its media browser service. Main's
summary names the live backend (`▶ Spotify · phone`).

### 3.11 Notifications and Settings → Music
Sources: track change (on) · queue end (on) · route loss / paused (on) · PC unreachable (off) ·
YouTube grab done/failed (on) · playlist saved (off). Rows as built: §8.4.

### 3.12 The takeover
**Reused as-is**: the Postgres schema and data, the Qdrant collection, the media cache, the Python
enrichment + embedding scripts (copied into `damagewm/audio/`, run on the existing venv via a
config path), yt-dlp, the lyrics table. **Ported to Kotlin**: library queries, queue/transport
truth, the resolver lanes, playlists, YouTube, the lyric fetch side, transcode/profile/cache, the
Range-capable stream server, the visualizer precompute. **New**: the APK sink, the notification
listener (Spotify + the limiter notice), Music Mode, the visualizer renderers, the phone catalog
cache + prefetch. **Ownership**: Damage is the only writer of the music tables, without exception
since 2026-09-10; Postgres from Kotlin = peer auth over the Unix socket, no passwords.

## 4. Open before the build (Adam)

Closed. The one item was **the Postgres collation warning** on `g2cc` (created under glibc 2.42,
the system on 2.43 — text indexes could be ordered by the old rules): `REINDEX DATABASE g2cc;`
then `ALTER DATABASE g2cc REFRESH COLLATION VERSION;` (the second only clears the warning). Run
with Adam's go 2026-09-02.

## 5. Architecture and module map (the build plan, written 2026-09-02 at max effort)

**Library** = the PC; **Player** = the phone. The window is written once against two core
interfaces (§6) and never knows which host it runs on. `IMPLEMENTATION.md` → "Music" is the
running module map; the shape:

```
core/…/windows/music/   MusicModel (data + the two interfaces) · QueueEngine (PURE) · LyricsSync
                        (PURE) · Viz (PURE, one rect per frame) · MusicWindow · PlayerCore (logic
                        shared by the phone and SimMusicPlayer) · MusicNet (MusicService +
                        RemoteMusicLibrary with its disk caches) · MirrorMusicPlayer (desktop, no
                        sink, refuses transport LOUDLY) · LocalMusicLibrary + MusicDb · Db (the SQL
                        seam; PgDb in :desktop) · Qdrant · MediaCache (transcoder inside) ·
                        MediaServer · Art · LibraryScan · Resolver + Rules + ClaudeOneShot +
                        EmbedQuery · LyricsFetch · YouTube · Enrich · Plugins (AskResolver /
                        LyricsFetcher / YtClient / Ingester)
phone/…/music/          AndroidMusicPlayer (PlayerCore over ExoPlayer + media3 MediaSession; a
                        ForwardingPlayer routes the buds' next/previous to OUR queue) ·
                        MusicListener · SpotifyRemote · TrackCache
desktop/…/              ScriptedMusic (for --selfcheck / --snapshot) · Main (Config §9.7,
                        registration in the auto stack + --host-only, MediaServer)
audio/                  the enrichment package from G2CC (§9.5) + viz.py
```

## 6. Contracts

### 6.1 `MusicLibrary` (core; Local on the PC, Remote on the phone)
The interface is `MusicModel.kt`. Semantics that bind: `stateLine()` is "" or `PC unreachable 40s`
/ `library: <err>`; `catalog()` is cached and Remote serves its disk cache offline;
`refreshCatalog()` is paced on a version cursor; `ask()` never throws for a lane failure;
`setPlaylistTracks` on an adaptive playlist throws "adaptive"; `art(id, px)` is px×px 4-bit gray,
box-sampled on the PC; `Listener.vizReady` announces a viz blob. **Catalog** = tracks (id, title,
artist, album, durMs, trackNo, discNo, year, genres, moods, styles, energy, vocals, hasLyrics,
hasArt, dupeCluster) + artists + albums (case-insensitive grouping — the library has real
case-duplicates) + playlists (id, name, origin, adaptive, count) + vocab + version; ~3 k rows,
one JSON blob, cached on the phone.

### 6.2 `MusicPlayer` (core; Android on the phone, Mirror on the desktop, Scripted in tests)
`PlayerState` = backend, playing/paused/stopped, entry (qid, track), posMs, durMs, queue, index,
mode, volume 0–100, boost %, output, pcLink UP | DOWN(sinceMs), sleep, holdVolume. Queue edits
are by `qid`; `setVolume(pct, cause)` names its cause; `backToPc()` is the deliberate switchback;
`positionMs()` is EXACT, monotonic-extrapolated between ticks (lyrics use it); listeners get
state, a paced tick(posMs) and events TrackChange · QueueEnd · RouteLost · Error · LimiterUndone ·
LimiterKeeps · BoostOff · BoostLoud · SleepEnded · BackendChanged · PcUnreachable.
**Persistence** (`PlayerCore.persist()`): engine (queue / index / mode / label), the REAL play
state, `posMs` + `posAt`, backend, spotifyAuto, volume, holdVolume, profile, prefetch,
spotifyFallback, output → the sub-record `window.music.player`, no stamp of its own (LWW
value-equality), every 10 s through the tick and on every change. `restore()` never auto-plays
and never applies the record's volume — the phone's stream is the truth (review round 1).

### 6.3 Wire
- **Window channel `music`** (`MusicService` on the content port, the Torrents shape): ops
  `catalog` (version cursor → blob), `search`, `ask`, `similar`, `random`, `playlists`, `playlist`,
  `playlist.save/rename/delete/set`, `lyrics`, `lyrics.search`, `lyrics.set`, `art` (blob), `viz`
  (blob), `yt.search`, `yt.grab`, `yt.status`, `played`, `recent`, `lyrics.sources`,
  `pretranscode`, `rescan`. Every failure is an in-band error, never an empty answer.
- **Media endpoint** (`MediaServer`, a ServerSocket HTTP/1.1 server — core runs inside the APK, so
  no `com.sun.net.httpserver`; `mediaPort` default **7404**, bound like the content port, token as
  a query parameter): `GET /track/<id>?token=&profile=<name>` → 200/206, `Accept-Ranges: bytes`,
  `Content-Type: audio/ogg` (Opus) or the source's type (lossless passthrough); HEAD accepted; a
  0-byte file answers 200; `Cache-Control: no-store`; a cache miss transcodes to completion first
  (seconds; logged). Malformed Range → 200 full (ExoPlayer treats a 416 as fatal — G2CC lesson).
  The phone learns the port from `Prefs.mediaPort` (BuildConfig default). NO TIMEOUTS.
- **Push**: `catalog` version bumps, `yt` job progress, the `state` line and `viz` (blob ready) as
  unsolicited frames — the §16.10 push slice (`WinService.Push.send(op, args, blob?)` on the host,
  `RemoteWin(onPush = …)` on the phone, a `wpush` frame).

### 6.4 Data formats
- **AudioProfile** `name · codec (opus|passthrough) · kbps · channels (1|2) · loudnorm`. Presets:
  **High** (opus 128 k mono / 192 k stereo — default) · **Standard** (96 k) · **Saver** (48 k) ·
  **Lossless** (passthrough, no loudnorm). Cache key `<id>-<mtime>-<sha1(path)[:8]>.<ext>` under
  `~/.damage/media-cache/<profile>/`; the legacy G2CC cache (`~/.g2cc/media-cache`, same key,
  8.1 GB) IS the `standard-mono-loudnorm` profile and is read in place. A Settings action
  pre-transcodes the library for the current profile (paced, one ffmpeg at a time, resumable).
- **Lyrics** `source · lines[(tMs, text, words[(tMs, text)]?)] · plain?` from LRC text; the
  `lyrics` table (artist, track, duration_s, synced, plain, found) plus a `source` column and a
  `track_id` link (additive migration, `MusicDb` owns it).
- **VizData** `fps (20) · bands (24) · frames: 4-bit packed levels · rms: 4-bit per 20 ms · beats:
  ms[]`, computed by `audio/viz.py` (librosa) on the first `viz()` ask (in the background, a
  `vizReady` push) and during a YouTube ingest — never at transcode time; stored as
  `<musicCache>/viz/<key>.viz` (a `.miss` marker when the build produced nothing), sent as a blob,
  cached on the phone with the track.
- **Art**: embedded picture (ffprobe/ffmpeg) else a folder image (`folder|Folder|cover|Cover|front|
  album` .jpg/.png) else none; box-sampled to px×px 4-bit gray on the PC, cached as
  `<musicCache>/art/<key>-<px>.gray` (`.none` markers for misses).

## 7. The APK

- **Dependencies + manifest**: media3 `exoplayer` + `session` 1.5.1 (`gradle/libs.versions.toml`);
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK` — `ShellService` declares `connectedDevice|mediaPlayback` but
  STARTS as `connectedDevice` only and adds `mediaPlayback` when playback first engages (Android 15
  refuses that type for a service started at boot); `android:usesCleartextTraffic="true"` for the
  :7404 endpoint over the tailnet (targetSdk 35 refuses cleartext by default); `<queries><package
  android:name="com.spotify.music"/></queries>` so the cold start can see Spotify (Android 11+
  package visibility); `MusicListener` with `BIND_NOTIFICATION_LISTENER_SERVICE` (the one-time
  "notification access" grant — `DAILY.md`; the window says "grant notification access on the
  phone" while missing). **No RECORD_AUDIO.** `MODIFY_AUDIO_SETTINGS` not needed.
- **Sink**: ExoPlayer with `USAGE_MEDIA`/`CONTENT_TYPE_MUSIC`, audio focus GAIN (transient loss →
  pause, resume on regain), a 60–300 s LoadControl (Tailscale insurance), gapless via the prestaged
  next item, `setPreferredAudioDevice` for the Output row (`AudioManager.getDevices(
  GET_DEVICES_OUTPUTS)`; "Auto" = the current route; **Auto refuses to start with no external
  output present** — the speaker plays only when chosen), pause on route loss with the notice.
  Media buttons through the session, **from anywhere**. ExoPlayer errors → notice + log.
- **Volume**: `STREAM_MUSIC` index ↔ percent; a volume-change broadcast receiver plus a 1 s poll as
  backup keeps the rows in step with the phone buttons. Every change is logged with its cause (our
  sets `ring` / `settings`; observed `broadcast` / `poll`, "limiter suspected" appended when it is;
  the re-set logs "re-setting the volume to the held N% (drop of N | notice)").
- **HoldVolume**: `held` = the last level set by any user action. `new < held` with a drop ≥ max(3
  steps, 25 % of range) in one event and not ours → limiter suspected → re-set to `held`, log,
  glass notice "phone lowered the volume — restored". The listener's sighting of the system notice
  (packages `com.android.systemui`, `com.android.settings`, `com.google.android.settings`; text
  matching volume + hearing/protect/lower — verify on device) is the high-confidence signal.
  Pacing: at most 3 re-sets in 10 minutes, then a notice that the phone keeps lowering it.
- **Boost**: `LoudnessEnhancer(audioSessionId)`, 0…+1200 mB (100…400 %), the effect's own limiter;
  **off when the track changes**, never persisted; shown on the card; a notice when volume = 100 %
  and boost > 100 %.
- **Prefetch** (`TrackCache`): the next N (default 3) entries' files for the current profile over
  the media endpoint into the app's cache dir, LRU beyond N+2, current + next always kept;
  ExoPlayer plays the local file when present. With the PC down the queue plays from cache and the
  card shows `PC unreachable 2m` (channel staleness first, media second).
- **Sleep**: off · after this track · 15/30/60/90 min — a deadline checked on ticks; ends with
  pause and a notice.
- **Spotify** (`SpotifyRemote`): sessions via `MediaSessionManager.getActiveSessions(listener)`;
  cold start = a `MediaBrowser` connected to Spotify's media browser service (component through
  the package manager; loud log if absent) then `play()`; state from the controller's
  metadata/playback state + art bitmap → gray. Automatic switch on PC loss (default on) only when
  the library backend cannot continue (prefetch exhausted); switchback deliberate (`backToPc()`),
  the Menu row appearing when the channel is healthy again.
- **Registration**: `ShellService` builds `RemoteMusicLibrary` + `AndroidMusicPlayer` and registers
  `MusicWindow(library, player)`; `Prefs.mediaPort`; `stopStack` detaches both. **Phone
  notifications**: `urgentNotification` is gated by the Global `Phone notifications` (off by
  default; errors → glass notice + log only); the foreground notification is untouched.

## 8. The window (`MusicWindow`)

Declares: `needs = {}` on the phone (library cached, player local) and `{HOST}` on the desktop
mirror; face Clear Sans; icon `multimedia-audio-player` (drawn fallback); `preferredHeight` 480;
short title `Music`.

### 8.1 Levels (all at 288/352/416/480 — the list kit pans; rows above/below the 64 px lens band
are 2+2 · 3+3 · 4+4 · 5+5 (the as-built `Layout` at each height; the plan's 6+6 was off by
one); the card is designed for the band at every size)
```
NOW PLAYING (Canvas, root; scroll = volume) ──tap──▶ MENU (MenuSurface, §8.2)
  ├─ QUEUE (List) ──tap row──▶ ROW MENU  ──▶ back to QUEUE
  │    wrap-end row = MENU
  ├─ BROWSE (List) → ARTISTS → ARTIST (albums + all tracks) → TRACKS
  │                 → ALBUMS → ALBUM → tracks · MOODS & GENRES → vocab word → tracks
  │                 → PLAYLISTS → PLAYLIST (rows; row 0 = "Play at random") → row menu
  │                 → COLLECTIONS (folder tree) · RECENT (play_history) · YOUTUBE…
  ├─ LYRICS (a canvas: the current line bright with context; scroll = nudge offset ±50 ms per
  │          output / plain pages; tap = the lyrics menu; double-tap back)
  ├─ SEEK (List: −5 min · −30 s · −10 s · +10 s · +30 s · +5 min · Restart · Back)
  ├─ VOLUME (a canvas: the percent in the 36 px face + a 20-block bar; scroll live, tap keeps)
  ├─ ASK / SEARCH / YT SEARCH / RENAME / SAVE-AS → the keyboard (§4.8), draft kept
  └─ MUSIC MODE (exclusive, §8.3) ── double-tap ──▶ QUEUE
```
- **Card (the lens)**: art 56 px (or the drawn note) · title · artist — album · a 12-block bar +
  m:ss / m:ss on the current entry (queue position i/n and the mode word on others) · state glyph
  ▶ ❚❚ ■ · backend/link badge (`PC` · `PC ↓ 2m` · `Spotify`) · boost badge when active. Cursor
  rests on the current entry at every level change; row identity is `qid`.
- **Row menu**: Pause/Play (current) or Play from here · Track info (Document) · Play next (not the
  current) · Move up · Move down · Add to playlist… · Lyrics · Remove (not the current; LAST — the
  misfire rule). No Cancel row. **Empty queue**: one row "Nothing queued — tap for Browse" + Menu.
- **Confirms**: Clear queue (Cancel · Clear) · Delete playlist and Save-over (Cancel first, the
  unrecoverable row at index 2) · Save over an existing name (asked twice) · Replace queue while
  playing. Playlist rows: Play · Play at random · Add current · Rename · Edit · Delete; adaptive
  playlists refuse Edit and say why.
- **Ask**: keyboard → `ask()` → the lane line in the title notice → `playQueue`. **Search**: results;
  none → "Search YouTube…" row. **YouTube**: results (title · channel · m:ss) → pick → confirm "Grab
  and add?" → progress in the title notice (push frames) → done notification with `t:<id>`; the new
  track is offered Play now / Play next.
- **Deep links**: `t:<trackId>` (queue row if queued, else Track info), `pl:<id>`, `mode:music`,
  `yt:<job>`.
- **Summary** (cheap, from `player.state`; words, no glyphs): `playing · Title — Artist` · `paused ·
  Title` · `Spotify · phone[ · title]` · `25 queued · staged` · `idle` · `player: phone needed`
  (desktop); detail = album · `q i/n` · mode.

### 8.2 Menu (wrap-end row; the order is the cursor-rest order)
As built: Pause/Resume · Next · Previous · Volume… · Queue · Track info · Ask… · Browse · Playlists ·
Moods & genres · Search… · Mode: Shuffle/Queue/Radio/Library random · Lyrics · Seek… · Save queue
as playlist… · Music Mode · Output… · Sleep… · Shuffle the rest · Clear queue · Stop · [Back to PC
library] · [Switch to Spotify] (the last two when applicable). Every row wraps and elides through
`Draw.fit`; nothing is cut.

### 8.3 Music Mode (shell `Mode.EXCLUSIVE`)
- `ShellServices.enterExclusive(window): Boolean` / `exitExclusive()`; the shell paints the whole
  panel through `window.paintExclusive(g: Gray8, safe: Rect, full: Boolean): List<Rect>` (the
  damaged rects), `onExclusive(on)` at the edges; **input: everything swallowed except double-tap →
  exit** (the `Mode.SILENT` branch generalized; long-press never arms; the chord cannot fire);
  notices as in silent mode (verdict 23); the mode persists like SILENT and restores after a driver
  swap only if the window is still registered. `DESIGN.md` §4.9 is the shell-side record.
- Surfaces (each on/off + order in Settings → Music → Music Mode): **Card** (art 120 px at 416 and
  480 / 56 px below; 136 px tall at 416/480 and **a MEASURED height below it** — `2 + ink(head) +
  3 + ink(body) + 3 + ink(small) + 2`, 80 px at the default face and scale) · **Lyrics** (as many
  lines as fit between the card and the bottom surfaces at the 22 px face, capped at 9; the current
  line bright — in the 18 px face at HEAD level when it would not fit its one row) · **Visualizer**
  (608×48 below 416, 608×64 at 416/480) · **Queue peek** (next 2 at ≥ 352, **1 at 288**; the band
  is `rows × (ink(body) + 1) + 2`) · **Clock** · **PC link** (`ink(small) + 4` tall). Defaults:
  Card + Lyrics on, Visualizer off, Queue peek off, Clock on, PC link on.
- 🔴 **Every band is sized from MEASURED ink, and every row placed from it** (review 2026-09-05,
  `HANDOFF.md` §27.2; the rect-is-a-promise rule in `CLAUDE.md`): `paintExclusive`'s returned rects
  are the ONLY damage this window declares, so ink outside them is never sent and the next keyframe
  produces the difference from nowhere. Found: the card's progress row at `r.bottom - 14` under a
  20 px ink ran 4 px past the card at 288 and 352; the queue peek stacked two 23 px lines on a
  20 px pitch in a 44 px band. `--selfcheck` drives the queue forward INSIDE Music Mode so these
  surfaces repaint as deltas — the state that exposes it.
- Repaint policy: the card on track change and every 5 % of progress; lyrics on line change;
  visualizer at its rate; ONE dirty rect per surface (§12).

### 8.4 Settings → Music (HostSetting rows)
Notify · track change (on) · queue end (on) · route loss (on) · PC unreachable (off) · YouTube (on)
· playlist saved (off) · Volume (0–100 by 5) · Volume boost (100/150/200/300/400 %) · Hold my volume
(on) · Output (supplier: Auto + devices) · Quality (High/Standard/Saver/Lossless) · Channels (mono
default/stereo) · Normalization (on) · Default mode (Shuffle) · Prefetch (1/2/3/5/10) · Lyrics
offset (−500…+500 by 50, per output device) · Lyrics sources (LRCLIB+local / +NetEase /
+Musixmatch) · Visualizer (Off/Bars/Scope/Pulse/Meter) · Visualizer rate (4/8/12) · Music Mode:
Card/Lyrics/Visualizer/Queue peek/Clock/PC link (on/off each) · Spotify fallback (auto/never) ·
Sleep (off/after track/15/30/60/90) · Pre-transcode library (action) · Rescan library (action) ·
Size · Font/Font size/Font style/Depth (automatic). Global gains **Phone notifications** (off).

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
updated_at)`. Damage is the only writer; additive migrations only (a `damage_schema` row records
them). Qdrant `g2cc_music`: point id == track id, payload track_id/artist/title, 384-dim cosine.

### 9.2 Files, transcode, stream
`Transcoder` = ffmpeg per §6.4 profile (`-map 0:a:0 -vn -ac <ch> -c:a libopus -b:a <k>k [-af
loudnorm=I=-16:TP=-1.5:LRA=11] -f ogg`), one at a time, resumable pre-transcode job; `MediaCache`
keys/paths; `MediaServer` per §6.3; the library walk (`AUDIO_EXTS` .mp3 .flac .m4a .ogg .opus .wav
.aac .wma .aiff, ffprobe tags, incremental by mtime) ported from `music.ts` for new files (YouTube
grabs; the "Rescan library" action). Art extraction via ffmpeg.

### 9.3 Resolver lanes (`Resolver`)
Lane 1 deterministic (`resolver.ts` semantics: exact artist/album/playlist, vocab words, token
search; post-processing: 'sound effects' out unless named, 'spoken word' out of shuffle-class
lanes, dupe-cluster dedupe with the higher-fidelity file winning, mild artist-spread shuffle, cap
`queueSize` 25 except finite album/playlist sets). Lane 2: `claude -p --tools ""
--no-session-persistence --model <cfg> --effort <cfg> --system-prompt …` — **not `--bare`**
(measured 2026-09-02: `--bare` reads only `ANTHROPIC_API_KEY`; Adam's CLI is signed in over OAuth,
so it answers "Not logged in" with exit 0; `ClaudeOneShot` keeps `bare` off by default and judges
the lane by parseability, never the exit code), env scrubbed, a strict-JSON plan that lane 1
executes; any failure or non-JSON → lane 3 → an honest EMPTY answer (never a guess, never
YouTube; "random" or a request with no content tokens is lane 1's own random). Lane 3:
`embed_query` (stdin text → 384-dim JSON, ~3.5 s cold) → Qdrant search. Every result carries lane
+ label + detail. Radio = Qdrant `recommend` from up to the last 5 queue entries ending at the
current, excluding the queue, the last 50 history ids and their dupe clusters, in batches of 10.
Library random = lane-1 rules over the whole catalog.

### 9.4 Lyrics fetch (`LyricsFetch`), in order, first hit wins, all paced and cached (negatives too)
1. the `lyrics` table; 2. embedded tags (`LYRICS`, `UNSYNCEDLYRICS`, `lyrics-*` via ffprobe);
3. `<track>.lrc` beside the file; 4. LRCLIB `GET /api/get` (artist, track, album, duration) then
`/api/search`; 5. NetEase (public search + `song/lyric?lv=1` — endpoint shapes per the open-source
`syncedlyrics` project, MIT: facts only, our own code); 6. the unofficial Musixmatch desktop token
route (same reference; behind the Settings toggle; expect it to stop working someday and say so
loudly). Manual search = the same chain with a typed query, results as choices. AcoustID (the key
as `musicAcoustidKey` in `~/.damage/config.json`, `ACOUSTID_API_KEY` winning) stays with the
enrichment passes, not the fetch chain.

### 9.5 The Python package (`audio/`)
`/home/user/G2CC/audio/enrich/` copied into `damagewm/audio/enrich/` (Adam's code, his licence);
`g2cc_config.py` → `damage_config.py` reading `~/.damage/config.json` (music keys, §9.7) with the
same cache-key rule; run with `musicPython` (G2CC's venv for now, untouched) from `audio/` as
`-m enrich.<module>`; plus `viz.py` (§6.4). Ingest for a new track = `run_enrichment` passes `tags
musicbrainz lyrics audio profile embed dedupe` scoped `--track-id`, then viz, then the transcode
for the current profile. A Damage-owned venv is a later chore (`audio/requirements-frozen.txt` is
the `pip freeze` of G2CC's, 224 pins).

### 9.6 YouTube (`YouTube`)
`yt-dlp --no-download --flat-playlist --dump-json "ytsearch10:<q>"` → results; grab = `yt-dlp -f
bestaudio -x --audio-format opus --embed-metadata --no-playlist --max-filesize 300m --no-simulate
--newline --progress --print after_move:filepath -o "<YouTube dir>/%(title)s [%(id)s].%(ext)s" --
<url>` (`opus`, not `best` — the indexer's extension set has no webm; every flag verified against
`--help` 2026.06.09), explicit request only; then §9.5 ingest; job state pushed on the channel.
Never the first result unasked.

### 9.7 Config keys (`desktop/Main.kt` `Config`, `~/.damage/config.json`)
`musicDb` (g2cc) · `musicSocketDir` (/run/postgresql) · `musicQdrant` (http://127.0.0.1:6333) ·
`musicQdrantCollection` (g2cc_music) · `musicLibraryDirs` ([/home/user/Music]) ·
`musicLegacyCache` (~/.g2cc/media-cache) · `musicCache` (~/.damage/media-cache) · `musicPython`
(/home/user/G2CC/audio/venv/bin/python) · `musicYtDlp` (~/.local/bin/yt-dlp) · `musicYoutubeDir`
(YouTube) · `musicClaudeModel` (opus) · `musicClaudeEffort` (low) · `musicQueueSize` (25) ·
`mediaPort` (7404) · `musicAudioDir` (/home/user/damagewm/audio) · `musicAcoustidKey` (optional;
Adam copies it from his G2CC config).

### 9.8 Adaptive playlists (`AdaptivePlaylists`, 2026-09-10)

The 25 rule-managed playlists (`playlists.rule` jsonb — the plan filter shape of §9.3 lane 2:
genres / styles / moods AND across lists, OR within, over the union of the three tag columns;
energy and bpm ranges; vocals and artists exact; exclude) were re-derived by G2CC's server at
every boot. With that server retired the refresh is ours — own code from the facts in G2CC's
`playlists.ts` / `resolver.ts`:

- **Materialize** = `MusicDb.planCands` uncapped (100 000), sound-effect variants out, ONE member
  per dupe cluster (the higher-fidelity file — `Rules.dedupeClusters`), spoken word IN (a genre
  collection is not shuffle discovery), ordered artist → album → path, the nameless last.
- **Refresh** = retained members keep their relative order (first occurrence only — a converted
  manual playlist may carry duplicate appends), new matches append in target order, non-matches
  drop; ONE transaction per playlist with the `playlists` row locked FIRST (a delete locks in the
  same order), dense positions, **no write when nothing changed**. A changed playlist bumps
  `updated_at`, which moves the catalog fingerprint, so the phone's catalog follows.
- **When**: host start (`startMusic`), after a grab's enrichment, after a rescan (the window's
  Rescan row). Serialized; a corrupt rule is skipped loudly; one playlist's failure never stops the
  others. `--music-check` runs the same derivation read-only and prints what a refresh would do.
- **Not built**: creating a rule playlist from the glasses (G2CC had no window for it either); a
  new rule is an `UPDATE playlists SET rule = …` for now.

## 10. Tests, harnesses, gates

- **Core tests**: `MusicTest` ×8 (profiles + legacy cache mapping, the media endpoint —
  Range/token/0-byte, VizData round trip, queue rules, catalog codec, the SQL layer over a fake
  Db, the remote library over loopback, the scan's hidden-directory prune), `MusicWindowTest` ×7
  (`QueueEngine`, a Radio fill after a pick, PlayerCore transport / fill / sleep / boost / hold /
  Spotify, LRC + scheduler, the grammar at **480 and 288**, persistence + continuity),
  `MusicModeTest` ×2, `ResolverTest` ×19, `LyricsFetchTest` ×24, `YouTubeTest` ×13, `VizTest`
  ×12, `EnrichTest` ×11.
- **Desktop**: `ScriptedMusic` drives the selfcheck walk (empty queue → Browse → an artist → play →
  card → row menu → Music menu → Ask via the keyboard → Lyrics → Music Mode 480/Bars then
  288/Scope with a notice over it → the off-screen track-change notice); snapshot scenes 30–39.
  Since 2026-09-05 the walk **advances the queue inside Music Mode** (card, peek and badge repaint
  as DELTAS — the only state in which ink outside a declared rect shows) and repeats the window
  set at **130 %** and at the tallest face, the per-lens truth oracle on every settle.
- **`--music-check`**: a pass against the real Postgres/Qdrant/cache, read-only bar the additive
  schema migration (counts, a sample query per lane, the cache-key mapping for 20 random tracks,
  one viz blob); not part of `:core:test`. It builds its catalog through the LIBRARY, not
  `MusicDb.catalog` — the bare call's default art predicate answers false for every track, so the
  "with art" count was structurally 0 and art extraction was never asserted (review 2026-09-05).
- **Gates**: `:core:test` · `:desktop:test` · `--selfcheck` · `--snapshot` (look at every scene at
  1×) · `--epub-check` · `--music-check` · `tools/lint.py` · `:phone:assembleDebug` ·
  `:phone:stageApk` · `:desktop:stageJar`.

## 11. Build order — six milestones, a commit after each

Done 2026-09-01/02 (`HANDOFF.md` §24 names the commits): **M1** host foundation (Config, `MusicDb`,
`Qdrant`, `MediaCache`/`Transcoder`, `MediaServer`, `LocalMusicLibrary`, `MusicService` + push,
`RemoteMusicLibrary`, `--music-check`) · **M2** core model + window · **M3** shell
(`Mode.EXCLUSIVE`, `LyricsSync`, `Viz`, Music Mode, `DESIGN.md` §4.9) · **M4** APK · **M5** host
features (`Resolver`, playlists, `LyricsFetch`, `YouTube` + `audio/` + `viz.py`, `play_history`,
radio, pre-transcode) · **M6** docs + staging (APK 19/0.19). Then review round 1 (§24.2, 0.20) ·
two ultrareview runs (§24.3, 0.21) · §24.4 on 2026-09-03 (the silent-playback diagnosis, the NOW
PLAYING root, 0.22) · §25 the same day (0.23: a restored level below the top never loaded; the
mirror published an empty player record as a removal tombstone; the quiet-stream notice did not
re-arm) · §30 on 2026-09-05 (Resume / Next / Previous were live rows with an empty queue — DIM now,
and a menu opens its cursor on the first row that can act; the medium seven-segment clock had its
last minute digit at an 84 px offset where the pitch is 32).

## 12. Traps and rules for the builder

- **Loop-only mutation** (`runOnShell`) for every provider/player callback; seq guards on async
  completions; never call a provider or the player from a paint; demand work only from `view()` or
  completions (the Torrents lesson). Listener registration idempotent; `detach()` on stack stop.
- **Row identity**: queue rows keyed by `qid`, never by index — shuffle and radio reorder under the
  cursor. Restored cursors wait for content.
- **One dirty rect per surface per frame.** A visualizer painted bar-by-bar burns the mode-3 fid
  budget (~6 per batch) and is silently skipped; paint the strip as one rect. Cached draws (modes
  13/14) are the compositor's, never a window's; mode 15 never.
- **Pacing, not timeouts**: the lyric scheduler, sleep, prefetch, hold-volume all use scheduled loop
  ticks with generation stamps (the `SilentTick` shape). No `wait_for`, no time-bounded wrappers.
- **Loud failures**: ExoPlayer errors, transcode failures, a refused output, a stopped-working lyric
  source, a missing notification grant, a Spotify session that cannot be found — each a notice
  with a duration or a reason, plus the log.
- **No truncation**: titles through `Draw.fit`/`dn()`, full text reachable in the lens or a
  document; lyrics wrap.
- **Misfire tolerance**: cursor rests harmless; Remove/Delete/Clear never at index 0/1; Stop at the
  menu's end; Replace-queue-while-playing confirms.
- **Never auto-play on boot; never the speaker unless chosen; never write to G2CC's code; never
  store the AcoustID key or any credential in a tracked file.**
- **All four heights** for every level and every Music Mode stack; snapshot 288 and 480.
- **Measured vs modeled**: every latency number here is modeled until a glass measures it; the
  visualizer's achievable rate and the Bluetooth lyric offset are the measured items still owed.
- **Wording**: `CLAUDE.md`'s plain-engineering table in code comments, notices and docs.

## 13. Kickoff for the build session

The build happened (§11). A later session starts at `REMINDER.md`; Music's remaining items are the
on-phone checks in `DAILY.md` → Music and the measured items named in §12.
