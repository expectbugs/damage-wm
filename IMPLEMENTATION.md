# Damage — implementation notes

What runs and how. First stage 2026-08-24; finishing build 2026-08-25 (`HANDOFF.md` §8); live on hardware
since 2026-08-30 (§11); the daily driver since 2026-08-31 (`DAILY.md`), re-shaped the same night by §19: **the
phone shell is the primary driver (radio and shell, while the APK is up); the OpenRC `damage` service is the
data provider — content + tmux + last-write-wins state sync on the content port — and a standby that drives
PC-direct BLE only while the APK is away.** One `:core` library holds the shell, the byte-exact glass
simulator, the compositor and every window (Main · Settings · Reader · Tmux · Files · Torrents · Music · Games
· Feed) on the CFW display contract (modes 3/6/8/9, the 11–15 texture-cache layer, the FB lease, the capability
gate). Per-window records: `TMUX.md`, `TORRENTS.md`, `MUSIC.md`, `HOLDEM.md`, `FEED.md`; Files is `HANDOFF.md`
§22 — it brought the shared machinery `WINDOWS.md` §4 lists (`MenuSurface`, the WinNet channel, theme icons via
desktop `ThemeIcons` + phone `RemoteIcons` with the drawn set as fallback and release path, the §16.4 state
substrate, deep links, the notification signature, `Draw.fit` / `Draw.dynamic`). Two chrome facts of
2026-09-01 (Adam): the battery cell is its two gauges (120 px, flush against the clock; the title gained 56 px)
and the silent-mode clock size is the Global `Silent clock` row (`DESIGN.md` §1.5).

## The two locked decisions

**Runtime: Kotlin/JVM** (open item #11). One `:core` library holds the entire shell — compositor, wire
codecs, simulator, surfaces, windows — and runs unmodified inside the desktop JVM program and the Android
APK. Core uses no AWT and no `android.*`; platform text rasterization enters through
`wm.damage.core.text.TextRasterizer` (AWT on desktop, `android.graphics` on the phone), faces
x-height-normalised to the §Type measurements on both.

**The transport ↔ shell seam** (open item #12) is `wm.damage.core.transport.Transport`:

```
shell  ──FlushRequest{ops: Keyframe|Delta|Copy|StereoPair, epoch, wide}──▶  transport
       ◀──TransportEvent{Input, FlushDone, Lease, Link, DiagFlags, Fault}──
```

- Ops are NOMINAL coordinates + per-op disparity; the emitter builds the per-lens stereo boxes (§3.4).
  Payloads arrive pre-compressed (zlib(rle)) — compression is the shell's job (§10.1).
- **Fids are stamped by the transport at EMIT time** (§8.2 #5), via the shared `Emit` encoder.
- `submit()` enqueues in call order and returns; the backpressure signal is `LinkState.inFlight` against
  `window`, which the shell's pump gates on. A `wide` flush drains the window and runs at depth 1.
- The same interface serializes over TCP (`RemoteTransportClient/Server`, length-prefixed JSON + binary):
  the shell can live on the PC while the transport lives on the phone, or the reverse. The server admits
  ONE driver; claim/yield callbacks let the phone's local shell hand over and take back.

Every transport shares `CfwTransportBase` — the full choreography (capability gate → carrier CREATE → lease
both arms + 45 s renewal → warmup splash → idle keepalive → fragmenting ≤ 3800 B → msgId/session
discipline) — so both live BLE paths run the protocol brain the sim exercises on every selfcheck.

## Module map

```
core/       wm.damage.core.geom       panel constants, Rect, the runtime lint gate (same rule IDs as
                                      tools/geometry.py), Layout (safe-rect-relative, §2.2b), fid discipline
            wm.damage.core.gfx        Gray8 compose surface, firmware-exact nibble RLE (pinned to fbfeas.py),
                                      4bpp pack, level-6 deflate, drawn icons/shapes (§4.5b)
            wm.damage.core.wire       CRC-16, protobuf, AA envelope + reassembly, EvenHub carrier messages,
                                      sid-0x09 lease + capability, mode 3/6/8/9 builders, CfwModes (11–15),
                                      TextureCache (byte layouts from zlib_glue.c / texture_cache.c)
            wm.damage.core.sim        GlassFirmwareSim — the byte-exact model: per-lens shadows, cfw_diag fid
                                      ring + flags, warmup drop, msgId-255 silence, lease fail-open, the
                                      texture cache, silent rejects made loud
            wm.damage.core.transport  the seam + Emit + CfwTransportBase + SimTransport + Remote client/server
                                      + PathTransport
            wm.damage.core.comp       the compositor (one mode-8 flush per frame, §5), CachedText, CanvasShift,
                                      the JSONL journal
            wm.damage.core.shell      Shell, input grammar, chrome, Main, switcher, notifications, silent mode,
                                      ContentKit (lens/list/document), slides, persistence, settings,
                                      MenuSurface, KeyboardSurface (§4.8), ShellKeeper
            wm.damage.core.windows.reader   Reader + EPUB extraction
            wm.damage.core.windows.files    the window, Local/Remote providers, FilesService, trash manifest
            wm.damage.core.windows.tmux     Tmux window + providers + TmuxNet
            wm.damage.core.windows.torrents QbtClient (Web API 2.11), TorrentLeech + Html, providers, TorrentsNet
            wm.damage.core.windows.music    MusicModel (the MusicLibrary/MusicPlayer contracts), Db + MusicDb,
                                      Qdrant, MediaCache + MediaServer, Art, LibraryScan, AdaptivePlaylists,
                                      LocalMusicLibrary + MusicNet, QueueEngine, PlayerCore + Sim/Mirror
                                      players, LyricsSync, Viz, Resolver + ClaudeOneShot + EmbedQuery,
                                      LyricsFetch, YouTube, Enrich, MusicWindow (+ Music Mode)
            wm.damage.core.windows.games    GamesWindow · kit/ — Rng (counter-based splitmix64), Cards,
                                      HandEval, Pots, Money, CardArt, HandFan, TableLayout, Seats,
                                      ActionLevel, Bankroll (none names a card game) · holdem/ — HoldemRules,
                                      HoldemTable (an action LOG replayed), Equity, HoldemBot, HoldemView ·
                                      roster/ — Character, Mood, Roster, Background
            wm.damage.core.windows.feed     FeedModel, FeedHttp (+ PacedHttp), FeedXml, Fetchers, Extract,
                                      Strips, FeedStore, FeedEngine (both hosts), FeedProvider, FeedNet,
                                      FeedWindow, ScriptedFeed
            wm.damage.core.net        WinNet — the §16.10 window channel (wreq/wres + blob answers + wpush)
            wm.damage.core.sync       SyncNet/RemoteSync — LWW state sync
            wm.damage.core.util       Log · Exec (subprocess runner, stderr drained concurrently) · Http
                                      (HttpURLConnection, no timeouts, multipart)
            wm.damage.core.content    library providers: local dir, TCP host, remote client with copy-on-open
                                      caching; the win/icon dispatch
            wm.damage.core.text       TextRasterizer, Style/StyleTransform, GlyphCaches, Wrap
desktop/    AWT rasterizer · Swing lens preview (integer-scaled, default 4×; keyboard + mouse = ring) · CLI ·
            ThemeIcons (Papirus-Dark from xfconf, rsvg-convert, mem+disk cache, serves the phone) · PgDb ·
            MusicPlugins · MusicCheck · ScriptedMusic · ScriptedTmux · ScriptedTorrents · GamesCheck ·
            CardSheet · FeedCheck · SelfCheck · Snapshot · SetupServer (/setup + /damage-apk on :7300) ·
            BlueZLink/BlueZTransport (PC-direct BLE)
audio/      the enrichment package taken over from G2CC (Adam's code) + viz.py — run on G2CC's venv for now
phone/      foreground ShellService, on-screen lens view (touch = ring), AndroidText (bundled OFL/Apache
            fonts), BleTransport (LIVE since 2026-08-31), transport seam server, wakelock + Doze exemption +
            BootReceiver, §9.3 urgent phone notifications, RemoteIcons, AndroidMusicPlayer, MusicListener,
            SpotifyRemote
```

## The finishing build (2026-08-25)

Adam's target: *flash the firmware, install the app, and it works — usable from the app or the PC with a
mouse, on a pixel-exact replica.* `HANDOFF.md` §8 is the record; what it added:

- **Every transport owns a mirror** (`Transport.mirror: LensPanels`): a `GlassFirmwareSim` fed the exact packets
  the transport writes; its `decode`/`fid`/`session` events surface as `mirror/<kind>` faults. Every replica
  draws it; `Transport.injectInput` lets a replica's gesture reach whichever shell drives.
- **The connect prelude** (`wire/LaunchMsg.kt`): one sid-0x01 app-launch request after both arms are up and an
  800 ms settle, acked on its msgId, before the capability gate; the 7-packet sid-0x80 sequence is never sent.
  The model treats the prelude as required (graded U).
- **The divergence check** (`Shell.checkMirrorAgreement`): at rest the compositor's belief per lens must equal
  the mirror through the emitter's quantiser; a disagreement is reported once per episode (status `DIVERGE`,
  journal, urgent notice) and answered with one keyframe.
- **The session keeper** (`shell/ShellKeeper.kt`): a link end restarts the session after a 2 s pause, forever,
  no timeouts; a capability refusal is terminal (`onTerminal`). Since §16 **`pause` YIELDS**
  (`shell.stop(stopTransport=false)`): the glasses session, lease renewal included, keeps running with no
  driver, and `Shell.start()` ADOPTS a live session (one wide keyframe). The seam server answers a claim of a
  live session with an adopt-grant and never stops the owner's session on driver loss; only the OWNING host
  tears it down. `HandoverTest` ×4 (`preludeAcks == 1` across claim / silent death / release / pause / resume).
- **The arbitration** (`transport/PathTransport.kt`): concurrent attempts over the candidate paths, first to
  start wins, a refused path disabled for the run. ⛔ Not the desktop's default since §19 — `auto` is the
  STANDBY policy; `--transport remote` is the explicit claim-and-drive dev override.
- **The phone**: `BleTransport` on G2CC's driver + the reference's sequence (RIGHT then LEFT, `retry(10, 500)`,
  MTU 512 checked ≥ 245, priority HIGH, cached pair addresses, RSSI poll); `ShellService` on the keeper (a seam
  claim pauses it, a release resumes it); the **Target** switch in `Prefs`; `LensView` draws the mirror.
- **PC-direct BLE** (`desktop/BlueZLink.kt`, `BlueZTransport.kt`): `bluez-dbus` 0.3.5 + `dbus-java` 5.2.0 (both
  MIT) on the system bus; `Device1.Connect` called raw so a refusal keeps its reason; MTU from
  `Properties.Get`; notifications from `PropertiesChanged(Value)`; `Connected=false` ends the session.
  Unit-tested over a fake link whose far end is the firmware model; the STANDBY path since §19.
- **The seam carries the mirror**: `RemoteTransportServer` streams changed row ranges of both panels through
  one ordered outbox, so a panel update precedes the `done` of its flush (`RemoteTransportClient.mirror`,
  display-only).
- **The replicas**: the desktop `Preview` (**integer-scaled, default 4×** at Adam's ask — legibility judgment
  still belongs to true-1× renders or glass) and the browser page (`replica/ReplicaServer.kt` — dependency-free
  HTTP + RFC 6455, token-gated, dirty-row panel frames + 1 Hz status; `replica.html`), served by the desktop
  (`replicaPort` 7403) and the phone.
- **Host-supplied Settings rows** (`HostSetting`): the display target on both hosts — staged on scroll, applied
  on tap, reverted on double-tap; applying rebuilds the stack.
- **Decision 6**: an ORDINARY notification arriving while the wheel, the context menu or the keyboard is open
  waits behind it; an EMERGENCY cancels the surface (the keyboard's draft kept) and shows first.
- **Long-press defaults off** (2026-08-30, `DESIGN.md` §1.2/§1.3): a bare long-press is a no-op everywhere. The
  switcher opens by the chord: long-press, then double-tap within 800 ms of the release (event 9 arms, event 10
  refreshes; a mistimed chord is plain back). The Settings row **"Long-press": off / switcher** restores the
  direct open. `LongPressTest`; on hardware event 10 fires after almost every touch-end (§11).

## Running it

Desktop (the sim standing in for glass; also serves `~/books` to the phone):

```
./gradlew :desktop:run                        # AUTO = STANDBY (§19): data host (content+tmux+sync) + replica; probes the phone, BLE-drives ONLY while the APK is away
./gradlew :desktop:run --args="--transport sim"   # the simulator in-process (development)
./gradlew :desktop:run --args="--transport ble"   # PC-direct BLE only (manual)
./gradlew :desktop:run --args="--remote HOST"     # claim the phone's transport and drive through it — the EXPLICIT dev override
./gradlew :desktop:run --args="--ble-info"    # adapter enumeration only (no discovery)
./gradlew :desktop:run --args="--selfcheck"   # the 230-check scripted gate (every window's walk, the truth oracle on every settle)
./gradlew :desktop:run --args="--snapshot DIR"  # lens-truth PNGs of every surface (57 scenes)
./gradlew :desktop:run --args="--epub-check"  # parse every book in ~/books
./gradlew :desktop:run --args="--music-check" # the real g2cc library, read-only bar the additive schema migration: counts, catalog, lanes, cache keys, Qdrant, one viz blob, the adaptive-playlist derivation
./gradlew :desktop:run --args="--games-check" # the Hold'em ecology over hundreds of simulated tournaments (pure in-memory; add `deep` for a longer run)
./gradlew :desktop:run --args="--card-render" # the card sheets into design/shots/cards/ at true 1x
./gradlew :desktop:run --args="--feed-check [DIR] [live]"  # the feed engine over the captured fixtures; `live` = one paced fetch per source, read-only, temp dir
./gradlew :desktop:run --args="--host-only"   # content host alone (books + tmux + sync + setup page, no stack ever)
./gradlew :desktop:test                       # 15 tests: the BlueZ glue over the fake link, the config file's safety, SetupServer, the xkcd PNG through the decoder
```

Preview: mouse wheel scroll · left click tap · right click double-tap · press-and-hold long-press · keys
↑/↓ Enter Backspace Space R T(type) · Tab lens · B both · -/= window scale (integer, default 4×). The browser
replica link is printed at start (`http://<host>:7403/?token=…`); the same server answers
`GET /journal?token=…[&tail=N]` (that host's flush journal — `tools/journal_report.py -` reads it from
stdin) and `GET /log?token=…[&tail=N]` (the process's last 4,000 log lines, `Log.recent`).

Config in `~/.damage/config.json` — books dir, ports, token (generated on first run; must match
`damage-secrets.properties` before building the APK), the phone host, the cached pair addresses, the tmux
hosts/quick keys/snippets/wait patterns, `qbtUrl` + the TorrentLeech credentials, the `music*` keys of
`MUSIC.md` §9.7 and `mediaPort`, `feedSources`/`feedUserAgent`/`feedDataDir`, `setupPort`/`setupToken`; the
`Config` class in `desktop/Main.kt` is the key list. An UNREADABLE file runs defaults for that start, loudly,
and is never rewritten.

Phone:

```
./gradlew :phone:assembleDebug
# -> phone/build/outputs/apk/debug/phone-debug.apk  (sideload on the Pixel 10a)
./gradlew :phone:stageApk      # -> ~/.damage/damage-wm.apk, served by the service's /setup page on :7300
```

The APK's daily default is Target = glasses: it DRIVES the pair over BLE (§19), fetches content from beardos
over Tailscale, copies each book locally on open, falls back to its caches when the PC is unreachable, plays
music from :7404 or its prefetch cache, serves the seam on :7402 (probe/claim) and its replica on :7403. The
SIM target is the on-phone dev mode. Run the APK build in its OWN gradle invocation (`CLAUDE.md`).

**The all-day daily driver** (`DAILY.md`): `--no-preview` runs any mode headless; `:desktop:stageJar` copies the
fat jar to the STABLE `~/.damage/damage.jar`; the OpenRC service `/etc/init.d/damage` runs it in `auto`. Deploy
= `stageJar` + `rc-service damage restart`; stop the service before any `:desktop:run` dev session.

## Configurations wired today

| configuration | how |
|---|---|
| **app + home PC** (the default — §10.1 row 1 as INTENDED, §19) | APK (Target = glasses) DRIVES; the `damage` service is the data host: live library, tmux, state sync. The PC never claims |
| app alone | the APK with no PC reachable: its own shell, cached library + cached books, staleness said; sync catches up on reconnect |
| PC-direct BLE (the rare case) | auto's standby starts a BLE stack after ~2 probes of APK absence and hands back on its return; or `--transport ble` manually |
| PC drives through the phone | `--transport remote` ONLY — the explicit dev override (the old daily mode, kept for development) |
| laptop-direct with the simulator | `:desktop:run --args="--transport sim"` — the development environment |
| browser replica | `http://<desktop-or-phone>:7403/?token=…` from any machine on the tailnet — the PHONE's is the live view in the default configuration |

## Phone-primary + sync (2026-08-31 night, `HANDOFF.md` §19 — the corrected §8.1 reading)

- **`Persistence` is the sync substrate**: schema v2 with a per-key stamp, re-stamped only on real value change;
  `tryApplyRemote` is strict last-write-wins; legacy stores migrate in place with mtime stamps.
- **`core/sync/SyncNet.kt`** rides the content port (`{"t":"sync"}` upgrade): the handshake exchanges stamp maps
  + clocks (skew-normalized), newer records flow both ways, then live pushes; the client (`RemoteSync`, in the
  phone's `startStack`) reconnects keeper-style and re-handshakes every 5 min. `shell.settings` + `window.<id>`
  sync; `shell.state` (per-device UI) never does.
- **`Shell.postSync`** applies a record on the loop: freshen the key from the LIVE window first, then LWW, then
  live-apply. The driving shell's state is newest by construction, so sync flows driver → idle.
- **The seam status probe** (`SeamProbe` / `Ctl t="status"`): a non-claiming "does the APK want the radio?"; an
  old APK answers `busy`, read as YES.
- **Desktop `auto` = STANDBY**: one process-wide store feeding the sync channel and any stack; probe every 5 s;
  APK absent/idle ×2 → a plain `ble` stack; APK back → stop it (the lease fails open and the phone
  re-choreographs). A BlueZ-less machine stands by as data host only, loudly. `SyncTest` ×6 pins it.

## Confirmed on hardware — first light, 2026-08-30

`HANDOFF.md` §11 is the record. `BlueZTransport` worked the first time it saw a radio — scan by name, RIGHT
then LEFT, MTU 247, notifications, write pacing, **no bonding needed**; the whole choreography (prelude,
capability gate, carrier CREATE, both leases, the warmup drop) painted; ring input arrived over `e0-01`; the
keeper recovered two unplanned link ends. **Ack latency ~50 ms** against the modeled 176 — an idle-shell
floor, not the curve (§11.1). Three defects fixed within minutes; the one to remember: the ack **status
enum** read as an error code because **the simulator modeled success as an absent field** — a model that
errs toward permissive is worse than no model.

## The refinement wave (2026-08-31) — `HANDOFF.md` §12 is the full record

Chrome depth, coarse scroll, Reader folders/chapters/images, per-app height, the digital clock, Settings
directories, brightness + battery on the wire, the 4× preview. The one trap worth restating: **the switcher
had not worked since first light because our source filter discarded the unattributed events 9/10 (source
0)** — a test default that supplies what the wire omits. Do not reintroduce a source gate on events 9/10.

## The APK-mission prep (2026-08-31, HANDOFF.md §13 — before any phone-radio test)

Mined from G2CC's Android app and the seam's failure model; on the radio daily since the phone's first light
the same day (§13.2):

- **The seam heartbeat** (`RemoteTransport.kt`, both ends): a bare `ping` every 5 s; a side that has SEEN its
  peer speak the protocol treats 20 s of total silence as the link ending — loudly — so a silent path death
  (Tailscale) hands the glasses back to the phone shell in seconds. A peer that never pings keeps the
  TCP-event-only behaviour. `SeamLivenessTest` ×3.
- **The pocket-liveness trio**: a PARTIAL_WAKE_LOCK while a GLASSES stack runs (the FGS type stops process
  termination, not Doze CPU throttling — G2CC measured `delay()` ticks gapping 13–28 s on a 10 s cadence; the
  lease renews every 45 s against the 90 s fail-open); the battery-optimization exemption (asked once; its
  absence stays in the status line; a boot-time revocation raises a re-grant notification); a `BootReceiver`
  (BOOT_COMPLETED + MY_PACKAGE_REPLACED → auto-start when Target=GLASSES and the exemption holds).
- **Scan hardening** (`BleTransport.scanForPair`): Bluetooth turning off mid-scan does not reliably reach
  `onScanFailed`, so a BT-state receiver fails the scan loudly and the keeper rides the ON edge back in; a
  still-hunting scan is re-issued every 20 min, under Android's ~30-min silent downgrade to opportunistic.
- **Distribution**: `./gradlew :phone:stageApk` → `~/.damage/damage-wm.apk`; since 2026-09-10
  `desktop/SetupServer.kt` serves `/setup` + `/damage-apk` on :7300 with G2CC's URL, token and Tailscale-only
  gate (`setupPort` / `setupToken` in `config.json`, `HANDOFF.md` §44).
- The capability gate refuses any firmware without an `EVENCFW` string. Compass, IMU, wear detection: per
  `DESIGN.md` §7 — a placeholder compass cell until the mode-10 feed exists; head tracking defaults OFF.

## Feed + comics (2026-09-09, FEED.md — one engine on both hosts, a deliberate switchback)

Reddit popular, Slashdot, xkcd, SMBC, the 8-Bit Theater archive; `FEED.md` §8 is the record. `core/…/windows/feed/`:
`FeedModel` · `FeedHttp` (`RealFeedHttp`, `PacedHttp` — one request per minute per Reddit host, a second
elsewhere, `Retry-After` honoured, `RateLimited`) · `FeedXml` · `Fetchers` (`RedditAtom`, `SlashdotRss`,
`XkcdFetcher`, `SmbcFetcher`, `RssFetcher`, `EightBit`) · `Extract` (jsoup) · `Strips` (fit, the §3.4 decision,
16/8/4 levels, packed 4bpp) · `FeedStore` · `FeedEngine` (the engine = the local provider; `adopt`, `pause`,
`comicAt`) · `FeedProvider` · `FeedNet` (`FeedService`, `RemoteFeedProvider`, `SwitchingFeedProvider`) ·
`FeedWindow` (nine levels, the comic canvas, the records, the settings) · `ScriptedFeed`. Desktop: `FeedCheck`
(`--feed-check [DIR] [live]`), `feedChecks` in `SelfCheck`, `feedScenes` in `Snapshot`. Tests: `FeedTest`,
`FeedWindowTest`, `FeedNetTest`, `FeedStripsTest`; fixtures `core/src/test/resources/feed/`.

- **`FeedEngine` runs on both hosts** (`FEED.md` §3.6): the PC's is `Config.feedEngine` (files under
  `~/.damage/feed`) and serves the phone through `FeedService` on the window channel; the phone's own (the app's
  `files/feed`) starts PAUSED and `SwitchingFeedProvider` resumes it after the `PC loss` threshold; `Back to PC`
  in the root menu parks it. Nothing switches back on its own.
- **Reading state is the shell's:** `feed.src.<id>` (read ids capped at 600, flags, `seen`) and `feed.binge.<id>`
  (episode, strip); read marks UNION on a live apply, flags are LWW. Harness accessors: `rootRowId()`,
  `levelName`, `itemsLoaded()`, `comicFocusLabel()`.
- **Slashdot**: the story page's rendered thread (`SlashdotRss.parseThread`) plus `POST ajax.pl op=comments_fetch`
  for the ids it lists but does not render (`fetchMissing`) — the one POST in the engine.
- **Config:** `feedSources` (the day-one five when absent), `feedUserAgent`, `feedDataDir`. No credentials.
- ⚠ **Comics are the heaviest thing the shell ships** (`FEED.md` §2.6, modeled): an xkcd first screen 5–13 KB, an
  8-Bit Theater page 30–54 KB at 16 levels (17 KB at 4). On-glass numbers owed (§8.1).

## Music (2026-09-01/02, MUSIC.md · DESIGN.md §4.9) — the phone plays, the PC serves

The G2CC music SYSTEM is Damage's: Postgres `g2cc`, Qdrant `g2cc_music`, the 8.1 GB transcode cache read in
place, the enrichment package (`audio/`, Adam's own code), yt-dlp. Two contracts (`MusicLibrary` +
`MusicPlayer`), one window: `wm.damage.core.windows.music`. `MUSIC.md` is the record, `HANDOFF.md` §24 the build.

- **The library (PC)** — `MusicDb` (every query) against a `Db` seam; the driver (`PgDb`: pgjdbc + junixsocket,
  peer auth) lives in `:desktop` so the APK never carries JDBC. `Qdrant`; `MediaCache` (profiles
  `<quality>-<mono|stereo>-<loudnorm|flat>`, the legacy cache = `standard-mono-loudnorm`, one ffmpeg at a time);
  `MediaServer` (HTTP/1.1 on :7404: `GET /track/<id>?token=&profile=`, 200/206 with `Accept-Ranges`); `Art`;
  `LibraryScan`; `AdaptivePlaylists` (`MUSIC.md` §9.8: refreshed at start, after a grab's enrichment, after a
  rescan; no write when nothing changed); `LocalMusicLibrary` (the catalog is a cached field refreshed on a
  SHAPE-only fingerprint — never a lyric fetch stamp or play history); `MusicNet` (`MusicService` /
  `RemoteMusicLibrary`: every op on the `music` window channel, the catalog behind a version cursor, catalog
  bumps and grab progress as `wpush` frames).
- **The leaf modules** (`Plugins.kt`): `Resolver` (three lanes; `ClaudeOneShot`, `EmbedQuery`), `LyricsFetch`
  (tags · `.lrc` · LRCLIB · NetEase · Musixmatch behind the toggle; a FAULT throws, a MISS is null), `YouTube`,
  `Viz` (Bars · Scope · Pulse · Meter), `Enrich`; on the phone `MusicListener` + `SpotifyRemote`.
- **The player** — `QueueEngine` (pure; `qid` identity) and `PlayerCore` (hold-my-volume: a drop ≥ 25 points not
  ours is the limiter, re-set at most 3× in 10 min, then said; the Spotify fallback, switchback deliberate) over
  a `Sink`: `SimMusicPlayer`, `AndroidMusicPlayer` (ExoPlayer + a media3 `MediaSession` over a `ForwardingPlayer`
  so the buds' next/previous move OUR queue; `setPreferredAudioDevice`, Auto refusing to start without an
  external output; `LoudnessEnhancer`; `TrackCache` prefetch), `MirrorMusicPlayer` (desktop, read-only).
- **The window** — NOW PLAYING is the root (2026-09-03, verdict 4 reversed: a canvas; the level drawn HOT at or
  below `PlayerCore.QUIET_PCT` = 10 %; **scroll = volume, tap = the Music menu**) → QUEUE (follows `qid`) · TRACK
  INFO · BROWSE · LYRICS (scroll = ±50 ms per output) · SEEK · VOLUME · MUSIC MODE (the shell's EXCLUSIVE mode:
  `enterExclusive`/`exitExclusive`, `paintExclusive(g, safe, full)` returning one rect per surface). Delete
  playlist is Cancel-first with the unrecoverable row at index 2; Save over is asked twice. Global gained
  **Phone notifications** (off).
- 🔴 **Music Mode's bands hold what they draw** (§27.2): `paintExclusive` is the one place a window declares its
  own damage rects, so every band is sized from MEASURED ink — a row placed by a guessed offset (`r.bottom - 14`
  under a 20 px ink) painted into `composed` and never shipped at 288 and 352.
- **Harnesses**: `ScriptedMusic` (the selfcheck walk incl. Music Mode with the queue ADVANCING, then at 130 % and
  the tallest face; snapshot scenes 30–39); `--music-check` (read-only; asserts a track the catalog FLAGS as
  having art extracts one); `MusicTest`, `MusicWindowTest`, `MusicModeTest`, `ResolverTest`, `LyricsFetchTest`,
  `YouTubeTest`, `VizTest`, `EnrichTest`, `AdaptivePlaylistsTest`.

## Games · Hold'em (2026-09-04, HOLDEM.md — the first window with no host)

Everything it needs is in `:core` — no provider, no channel, no `needs`. `HOLDEM.md` is the record (§17 the
deviations). To work on it:

- **The persisted table is an ACTION LOG.** `HoldemTable` stores `(seed, handNo, start stacks, busted order,
  button, actions)` and re-derives everything by replaying it (`replay()`, behind a volatile `cache`);
  `nextHand()` alone advances the seed.
- **Randomness is counter-based:** `Rng` is splitmix64 keyed by `(tournamentSeed, handNo, seat, decisionNo)`; key
  ORDER matters; nothing persists an RNG cursor.
- **Decisions run off the loop and return through `runOnShell`,** stamped with `pacerGen`; bump it whenever the
  world the answer was for stops being the world on screen.
- **The pacer is a pacing loop, not a timeout:** bots wait for Adam forever; `Settings → Games → Bot pace`.
- 🔴 **The play-out hand-off runs ON THE LOOP, deliberately and measured:** `--games-check` prints **13 ms for a
  whole 6-seat tournament**. As a coroutine it cleared a NEW table's cast with the OLD table's settlement and
  lost the prize pool on a restart (`HOLDEM.md` §17.2c). Do not put it back without measuring first.
- **The world only advances while he is looking at it** (verdict 27): `Background.playTournament` runs from the
  pacer, never a schedule or the wall clock.
- **The kit names no card game** (`windows/games/kit/`); blackjack, hearts and gin are meant to reuse it whole.
- **Oracle-backed:** `core/src/test/resources/holdem/sidepots.json` (3,000 scenarios) and `hands.json` (2,000
  rankings), generated with `pokerkit` (MIT) in a scratch venv by `research/gen_sidepots.py`; only the corpus is
  in the repo.
- **Harnesses:** `--games-check` (FAILS on a rising money-supply growth rate), `--card-render` (true 1×), and the
  window's accessors (`tableRunning`, `isMyTurn`, `handIsComplete`, `myStack`, `levelName`, `rootRow`,
  `historyDocLines`, …) so a test asks the table instead of counting notches.

## Torrents + the keyboard (2026-09-01, TORRENTS.md · DESIGN.md §4.8)

`wm.damage.core.windows.torrents`; `TORRENTS.md` is the record:

- **`QbtClient`** — qBittorrent Web API **2.11** over `HttpURLConnection`: `sync/maindata` (rid 0, always full),
  `torrents/properties|files|trackers`, the 5.x verbs `torrents/stop|start|recheck|delete`, multipart
  `torrents/add`, `auth/login` only when credentials exist. Every key read from the 5.1.4 source.
- **`TorrentLeech`** — form login → cookie jar `~/.damage/tl-cookies.json`; ONE re-login on a logged-out answer,
  paced to one login a minute inside `login()`; the refusal latch fires only on the site's login FORM; the JSON
  listing endpoint for browse AND search (`torrents/browse/list/…`, 35 rows a page); a stdlib `Html` reader;
  `.torrent` bytes refused unless bencoded. **Format drift is a loud `TlException("format changed: …")`**.
- **`LocalTorrentsProvider`** (PC) — the poll loop (15 s idle; the fastest focused party sets the pace), snapshot
  diffing into EVENTS with a monotonic sequence + a per-process epoch, the announced set
  (`~/.damage/torrents.json`: a first run baselines silently, nothing announces twice), `xdg-open`.
- **`TorrentsNet`** — `TorrentsService` on the window channel (`snap` with a version cursor, events since a
  sequence within an epoch) and `RemoteTorrentsProvider` (phone: 2 s focused / 15 s idle, event replay).
- **`TorrentsWindow`** — TRANSFERS (six filters incl. **seeding < 1 week**; the cursor follows row identity) →
  the transfer MENU (… Delete + files behind a double confirm, the unrecoverable row at index 2) → DETAILS · the
  wrap-end MENU (Browse · Search via the keyboard · …) → CATEGORIES → LISTING → TORRENT → the add MENU.
  Notifications `torrent · done/error` deep-link to `t:<hash>`, gated by Settings → Torrents;
  `open("t:<hash>")` / `open("tl:<fid>")`. Face Fira Sans; icon theme `qbittorrent`.
- **`KeyboardSurface`** (`shell/`, `DESIGN.md` §4.8) — row-then-key with wrap on both axes, stay-in-row after
  typing, QWERTY/abc from Settings → Global → Keyboard, the symbol layer, Shift once/lock, caret editing, a
  panning text line that marks its cut, up to two requester rows of LIVE keys, the draft handed back on cancel;
  the shell cancels it under the wheel / silent / relayout / an emergency (draft kept) and commits a
  replica-typed line through it. Requesters: Torrents Search, Tmux "Type…", Files Rename / New folder.
- **Harnesses**: `ScriptedTorrents` (ink budgets: transfers 9.0 %, details 6.4 %; snapshot scenes 15–22);
  `TorrentsTest` ×7, `KeyboardTest` ×22.

## Tmux (2026-08-31, TMUX.md — all refinery verdicts locked, built in one pass)

The first CANVAS window: a viewer/controller of the REAL tmux server via discrete commands (`=session:` exact
targeting, no `-C` attach). `wm.damage.core.windows.tmux`; `TMUX.md` is the record:

- **`Sgr`** parses `capture-pane -e` (16/256/truecolour by luminance onto the 16 grays, foregrounds floor at 3).
  🔴 **The GRID is RETIRED** (2026-08-31, Adam: "it's just text ... kill the grid entirely"): **`FlowRender` is
  the view** — NORMAL panes captured with `-J`, wrapped at the content width through the per-app font/size/style;
  `TermRender` survives only for `#{alternate_on}` panes. History is the SAME flow over the frozen deep capture
  (5 display lines/notch; the notch that reaches the live edge returns to live). Capture pacing 1 s; Settings →
  Tmux → Update (0.5/1/2/5 s) adjusts it live over the wire (`tpace`).
- **`LocalTmuxProvider`** execs `sh -c` scripts — ONE per host per tick (status 2.5 s, capture 1 s pushed on
  change); hosts opt in via `tmuxHosts` (default empty); waiting-pattern EDGE alerts (per-session mute, GLASS
  only); ssh gets BatchMode+ConnectTimeout=5 on connection ESTABLISHMENT only, one multiplexed connection per
  host (`~/.damage/ssh-*`, 60 s persist).
- **`TmuxNet`** rides the CONTENT port (`{"t":"tmux"}` after the hello → a push channel); `RemoteTmuxProvider`
  (phone) reconnects keeper-style and surfaces `PC unreachable Ns`. Config (quick keys/snippets/patterns) is
  served WITH the session list — `~/.damage/config.json` on the PC is the one tuning point.
- **`TmuxWindow`** — SESSIONS → LIVE (canvas; **scroll-up IS scrollback**) → HISTORY → KEYS → SNIPPETS / WINDOWS
  (targets `=s:idx`, never `select-window`) / SESSION_ACTIONS (mute · Fit pane 64×22 · select · rename · the
  `kill-session` confirm). **Typed text always stages a TYPE_CONFIRM**; a quiet host says so on every level and
  in Main's row (§30); the alert's tap opens the session (§29). Settings → Tmux: Context rows · Alerts · Update
  · Size (default 480).
- **Shell additions**: `CanvasView.onScroll/onTap`, `DamageWindow.onTypedText` + LOUD refusal when nothing
  accepts, `Transport.injectText` → `TransportEvent.Text` across every transport and the seam (`"typed"`); entry
  points: the desktop preview (key T), the browser replica's text bar, the phone strip's `type` button.
- **Harnesses**: `ScriptedTmux` (the tmux selfcheck checks, snapshot scenes 09b–09e); `TmuxTest.kt` (27, six classes).

On-glass verdicts still owed (`REMINDER.md` → Other open work): the flow view's default size/wrap feel,
quick-key order, alert-pattern tuning against real Claude sessions, ssh-host latency feel.

## User typography + per-app depth (2026-08-31 evening, Adam's ask — a §Type reversal)

`core/text/Style.kt`: **Global → Font / Font size / Font style** restyle CHROME AND MAIN (a recorded reversal
of §Type's "the system face is not negotiable" — his call), and **every app category gets Font / Font size /
Font style / Depth** rows; each option previews IN ITS OWN font while cycling (`HostSetting.optionFont`,
`raw=true`). One mechanism: a `StyleTransform` rewrites every `FontSpec` at the rasterizer seam — chrome draws
through a `StyledText` wrapper with the global transform, every window routes measuring/drawing through its
own per-app transform (`DamageWindow.styleTransform` → `styledText()`), so wrap and render agree by
construction. Content scaling MOVED here from the platform adapters (their `contentScaleProvider` is retired
to 1.0 — double-scaling was the hazard). At every default the feature is render-neutral (snapshot-verified
byte-identical below the clock). `StyleTest` ×5 pins the edges. **Depth** has since moved to the §41.2
ladder (`DESIGN.md` §3): the Global row moves everything with the selection bar one notch nearer; the per-app
`Depth` row (default `global`) moves only that app's content.

## The texture cache (2026-08-30, CFW `a5d1c31`)

The firmware's **64 KiB lease-scoped texture cache** and three draw modes; wire and model layers built
2026-08-30, adopted by the compositor behind the Global `Cached text` row 2026-09-06.

- `wire/CfwModes.kt` — `cleanup()` (11), `cacheUpdate()` (12), `drawImage()` (13), `drawCachedText()` (14),
  `options()`, `xAdjust()`; every builder refuses what the firmware would reject in silence (mode 13's exact
  7-byte body, mode 14's `8 + strlen`, byte 0 and bytes > 127, writes past 64 KiB, an adjust outside −10…+20).
  `batch()` accepts sub-modes 3/6/9/13/14/15.
- `wire/TextureCache.kt` — the atlas layout: image encoding (`[w][h][RLE of exactly w*h pixels]`, no row pad), a
  deduplicating offset allocator, 96-entry font tables, chunked mode-12 messages. Two invariants: **offsets 0–1
  stay zero** (an unfilled table entry points at a guaranteed-rejected image) and **every table entry is
  filled** (the firmware validates a whole string before drawing any of it).
- `gfx/Codec.kt` — `Rle.encodeLevels`/`decodeLevels` over a bare pixel run (`RleParityTest` still holds).
- `sim/GlassFirmwareSim.kt` — modes 11/12/13/14 modeled byte-exactly (the lease gate, whole-list validation, the
  LUT with its integer truncation, transparency tested pre-LUT, per-glyph advance by image width). Mode 15 is
  **refused loudly**. The cache is dropped on lease expiry and on mode 11. `TextureCacheTest` + `AckStatusTest`: 44.
- Two corrections from reading the source: the sim no longer attaches an `EventSource` to long-press events
  (`EvenHubMsg.reportsSource`); the capability gate runs against the real `EVENCFW/16` string (`REQUIRED_CAPS`
  stays at five, version checks through `SettingsMsg.contractVersion`).

**Adopted (`HANDOFF.md` §40.6).** `core/comp/CachedText.kt`: `GlyphAtlas` renders a font's glyphs 32..126
through the host's rasterizer and packs them with `TextureCache.Builder`; `CachedText` wraps every host's
rasterizer, blits strings from those same images (the firmware's TRANSPARENT LUT draw) and records them per
frame; the compositor ships a rect as one clear plus mode-14 draws only when black plus the records equals the
composed pixels byte for byte. The upload (`DisplayOp.CacheWrite`, one ≤ 3 KB chunk per idle pump after the
keyframe) is `Shell.pumpAtlas`; fonts go live on the batch's last ack; a lease lapse forgets the upload.

**Every plane, and icons (§41).** Cached draws are flat in the firmware, so a rect on a depth plane ships as a
BASE delta over the rect widened by the disparity (every draw's box black), the draws at nominal x, and one
per-lens mode-9 copy (`DisplayOp.CopyPair` → `CfwModes.copyStereo`) that slides each lens's copy to its own x;
`Compositor.emitCached` builds the firmware's result per lens and compares it with that lens's truth before
anything ships — else pixels, the reason counted into `cacheMiss`. `CachedText` records draws into a slide's
temp through a relay (`via`/`viaInto`), moves records with a translation (`moveRecords`), and draws a string
with a character outside 32..126 as its cacheable RUNS plus the host's character. Icons cross `IconPaint.blit`
(`CachedText` is the `IconRecorder`; mode-13 draws). Fonts pack heaviest first (`usageOf`); the atlas keeps an
ACKED watermark; the keyframe seeds the screen plane only (`Compositor.seedFrame`).

## Review hardening (rounds 2–8, 2026-08-24)

Seven rounds of independent review after the first build (every candidate verified by trace, timing or pixel
simulation before a fix) found ~70 real defects; the reviews since (`HANDOFF.md` §25–§42) added to the list.
These mechanisms are load-bearing and easy to break by accident:

- **The latency build (§40)** — chrome's telemetry cells repaint only when `allowTelemetry` (`Chrome.sync`); a
  notch's first flush is the translation — the lens repaint is posted one message on
  (`Shell.paintOptimisticLens`) and a strip past `Slide.SPLIT_FILL_PX` goes out blank and is filled on the next
  pump (`Slide.fillDeferred`, `Shell.applyCanvasFill`); `Compositor.partition` merges the globally cheapest
  within-owner pair first (proportional shares starved a plane); `CfwTransportBase.watchdogTick` rebuilds a
  quiet session and the connect prelude re-asks every 2 s; `Compositor.emitCached` ships text as mode-14 draws
  only under a byte-exact proof in LENS space, on every plane, with a per-lens copy behind the flat draw. Do not
  put the proportional shares back, do not let telemetry ride a content-neutral repaint or a gesture's first
  flush, never emit a cached draw on a depth plane WITHOUT its `CopyPair`.
- **The glasses can be asleep (§36, §38).** The firmware's Silent Mode refuses every image; the glasses push the
  state and the READ response restores it (`SettingsMsg.parseSilentModePush` / `parseSilentRestored` →
  `TransportEvent.SilentMode`). `Shell.enterSilentGlasses` stops SENDING (the pump returns before the flush),
  releases the lease on purpose (`Transport.setLeaseWanted(false)`), notifies once. **The wake is a session
  REBUILD**: `Shell.wakeGlasses` → `Transport.restartSession` → `onLinkDown`; the keeper restarts the shell from
  the prelude up and `Shell.start` adopts the glasses' state from that session's READ (`LinkState.glassesSilent`)
  before its first frame — leaving Silent Mode ends the firmware's EvenHub page (measured: four minutes of
  refusals after the push OFF on 0.34), so no keyframe into the old session can wake it. Three consecutive
  ImgResCmd refusals are the fallback for a missed push; `Shell.silentTick` (every 60 s and on a ring event)
  asks for the rebuild when the glasses say they are awake, once per pacing. System events 4/5/7 are journaled
  as `event` notes; the simulator's `carrierLost` models the page ending. Do not put the old "panic → keyframe"
  reaction back in front of a refusal (a 20 KB keyframe every 3.5 s for fifteen minutes, 2026-09-05).
  `SilentGlassesTest` ×4, `ShellKeeperTest.aRestartRequestRebuildsTheSessionOnce`.
- **The latency pass (§32)** — pixel-identical: the journal's submit line carries `via` / `handleMs` /
  `assembleMs`; `Compositor.compress` is memoised for ONE assemble; both rasterizers cache measures and coverage
  masks (`core/text/GlyphCaches.kt`); `Wrap.wrap` decides from an additive estimate outside a ±24 px band and
  measures exactly inside it (`WrapEstimateTest`); `Shell.pump` reserves one window slot for the pump after a
  ring event (`pumpPriority`); `LinkState.transferMsPerKbEma` (flushes ≥ 1 KB, against `floorMsEma` from
  flushes < 400 B) is the regime discriminator — the wheel spins in 2 frames above 50 ms/KB;
  `Persistence.saveAsync` writes on one daemon thread in submission order. Do not put the write back on the
  loop, do not let the memo outlive an assemble, keep the estimate band.
- **The `queued` counter is honest** (`Shell.post` / `loop()`'s `finally` / `quiescenceReport`, §31.8):
  `isQuiescent()` reads `queued`; `post()` undoes its own count on a refused `trySend`; the loop's `finally`
  drains what is left; the report distinguishes `LOOP-ENDED` from `in=<Msg>/<ms>ms`. Do not make `post()` fire
  and forget again.
- **A canvas repaint that TRANSLATED is transmitted as the translation** (`CanvasShift` + `Shell.paintCanvasOf`,
  §31): `paint(); damage(content)` shipped the whole area on every scroll (measured: a tmux scroll 7.4–10.8 KB;
  6–12 KB is a median 1,193 ms). The shell DETECTS the shift from the frames before and after; it never asks the
  window. `declareShift(movePending = false)` is the canvas ORDER. Never a field on the window contract.
- **A CLOSED wheel is not spinning** (`Switcher.spinning` / `close`, §30): a commit or cancel inside the four
  animation frames left the flag true for ever — an unbounded loop of empty frames. `close()` stops the drum;
  the flag is `open &&`-gated.
- **A harness compares the shell to the glass through `Shell.sampleIdle`** (§30): a read from another thread
  crosses a window the shell can repaint inside (the standing oracle failed 2/20 on an unchanged tree, 20/20
  after). `SelfCheck.runOracle`, `OracleWalkTest.assertOracle` and `--snapshot`'s `save()` go through it; a
  `Msg.Run` reaching a stopped loop runs its `dropped` arm.
- **A line box is the LARGER of the face's line height and its measured ink** (§30): AWT ceils ascent and descent
  separately (JetBrains Mono 16 inks 25 against a 24 px line at 115 %). `FlowRender.lineH`, `TermRender`'s
  cell, the keyboard's centring and the Hold'em history all take the max.
- **The shell loop survives an `Error`, loudly** (`Shell.loop`, §29): the handler and the pump catch `Error`, log
  it, note the journal, set the status cell to ERROR. Do not narrow it back to `Exception`.
- **An event notice a tap should answer carries `appId` and a `target`** and coalesces per ITEM
  (`TmuxWindow.alert` → `open("session:<host>:<name>")`, §29).
- **The context menu's box follows the row face** (`MenuSurface.boxW`, §29): the design's 248 at 100 %, grown by
  the ratio the row pitch grew; a detail cut at its head carries the drawn mark on that edge.
- **The list rhythm is measured, with the design as the floor** (§29): `Layout.rowH` / `lensH` (defaults 32 /
  64), `Shell.listRhythm()` derives them from the row face's measured ink through the transform on screen on
  every `syncLayout`; `ContentKit` hangs the rows above from the lens; every window's second lens line is placed
  by `Draw.lineBelow`. `Review29Test`. Do not put `Layout.ROW_H` back into a paint path or `+34` into a lens.
- **The shell's own surfaces measure their rhythm from the chrome face** (§28.2): `MenuSurface.titleH()/rowH()`,
  `Notifications.srcH()/pitch()/roomFor()`, `Switcher.paint`'s `bandH`, `TableLayout(statusH, lineH)` +
  `HoldemView.paintSeat`. The design numbers are the FLOOR, so 100 % is pixel-identical. No pitch constants.
- **A generation bump and its in-flight flag are ONE operation** (`GamesWindow.cancelPacer`, §28.1).
- **Windows scan their root at registration, quietly** (§28.1): no op-cell narration, no notice, no `navSeq` bump.
- **`Config.load` never writes back an unreadable file** (§28.1): defaults for that start only, loudly.
- **Chrome text is placed from MEASURED ink and its scale is capped to its bar** (2026-09-05): `Chrome.fitY`
  keeps each line's ink inside its cell; `Shell.chromeScale()` caps the chrome's scale to what the SHORTEST bar
  (28 px) can hold while CONTENT keeps the full 130 % ladder (the status bar ran 2 px past its own bar from the
  day it was drawn, §27.2).
- **Compositor per-lens model.** The compositor reasons per lens: an expected shadow per lens; the per-lens
  TRUTH of the nominal frame under the plane map (each region vacates its nominal area to black — the seam;
  pieces render far to near, the nearest wins); a diff on the 4×2 damage grid; merging toward the pipelined rect
  budget (row bands first; within a piece, then across pieces of one disparity, **never across a pixel of
  another plane**; a final priced pass merges neighbours whose compressed union is cheaper — §5.1, §8.2).
  ⚠ **That rule binds the plane-0 REMAINDER group too** — not a rectangle, so `coarsen()` and `partition()` can
  both union across a region unless guarded (`remainderPieces()`; §25 #1–2): a flat delta carrying a region's
  pixels paints them at the wrong shift on BOTH lenses, belief and glass then agree on the wrong thing, and the
  divergence check cannot see it. The oracle that can recomputes the per-lens truth of `composed` under `planes`
  against `expectedLens()` — **a STANDING GATE since 2026-09-05** (every `--selfcheck` settle; `OracleWalkTest`
  at all four heights); it also catches ink painted into `composed` that no damage rect carried (§27.2). The
  emitter sends whatever closes the gap: nominal deltas at their disparity (split at a mode-8 sub-message's
  16-bit length or the batch's remaining bytes), black stereo pairs for seam strips BOUNDED TO THE SCANNED AREA
  (`L2ProbeTest`); every planned op is applied to the shadows as it is planned, so its effect on the OTHER lens
  is repaired in the same flush; what the 16-fid ring or the batch byte cap cannot carry stays dirty for the next
  flush; a lost flush marks the cells it touched UNKNOWN, transmitted again from the truth. `LensOracleTest`
  (belief = the firmware model's panels after every flush; at rest each lens = an independently written truth,
  depth 8/12/16, every transition); `Round5Test`–`Round7Test` (lost flushes, cell noise under a box, oversize
  payloads, rollback after many copies, the batch byte cap). Three failed keyframes halt the pump with one notice.
- **Transport session lifecycle.** Queued work carries a session epoch; `stop()` and `onLinkDown()` bump it and
  SWEEP (a failed `start()` sweeps without bumping): pending acks fail, window permits return, both queues drain
  loudly, a start parked on the capability gate is answered with a sentinel. A flush never spans the 0xFFFE→1
  fid wrap; a failed encode hands its fids back; completions leave in submission order; msgId cycles 1..249.
  The window-full-no-ack stall is REPORTED as a fault, never acted on.
- **Shell.** start/stop serialize on a mutex (a stop during start waits and never saves defaults over unread
  state). A notification box is LIFTED before any slide steps beneath it and repainted after. Every dynamic
  chrome string is sanitised to the locked glyph set and fitted with the continuation mark.
- **Content.** Host reachability is decided in one place (`RemoteContent.withHost`); local disk failures never
  read as "PC gone"; the cache keeps the listing's real extension.
- **From the 2026-09-03 review (§25):** a surface's WRAP width and its DRAW bound are the same number
  (`Notifications`: `SILENT_W` + `bodyLines(n, l, silent)`); dynamic text goes through `Draw.dynamic` everywhere
  (`Epub.fold` handles the cp1252 mojibake range, U+2011 and the zero-width formatters at extraction); a window
  with async levels loads a RESTORED level on `back()` (`MusicWindow.ensureLoaded`); `saveSubState()` never
  reports an EMPTY blob (the §16.4a tombstone — the shell refuses one loudly, once per key per session).
- **The page traffic sleeps with the shell** (`CfwTransportBase.pageTrafficWanted`, §42): the 4 s keepalive and
  the 30 s carrier refresh go out only while the shell wants the lease and the glasses do not say they are
  silent (one night of Silent Mode was 8,641 unacked keepalives). The 60 s device-info READ is the wake poll.
- **A stop after a link loss sends no lease release** (`stop()` reads `connected` before the sweep, §42): the
  write into the dropped arm was a fault at every rebuild, and the release that reached the survivor freed that
  lens's texture cache (a renewal keeps it — `settings_ext.c`). A deliberate stop releases both arms, best
  effort per arm. `ShellKeeperTest`, `SilentGlassesTest`.
- **The keeper narrates into the journal** (`Shell.journalNote`, `keeper` notes, §42) and every host serves
  `/log`; silent checks are counted (`silentChecks`); atlas chunks are `ATLAS` submits with their `via`.

## Verification

Battery at HEAD (2026-09-10, `HANDOFF.md` §44.4): `:core:test` **525** · `:desktop:test` **15** · `--selfcheck`
**230** checks · `--snapshot` **57** scenes · `--epub-check` (380/404 images) · `--music-check` · `--games-check`
(400 tournaments) · `--feed-check` · `tools/lint.py` **0** · `:phone:assembleDebug` in its own gradle call.
Every review's pins were run against the unfixed tree and watched to fail before they counted.

- `./gradlew :core:test` — RLE parity against the Python reference, CRC vectors, the geometry/fid fixtures shared
  with `tools/lint.py --selftest` (`GeometryTest`), pipeline round trips through the sim, `Round3Test`;
  `MirrorTeeTest`, `PreludeTest`, `DivergenceTest`, `ShellKeeperTest`, `WheelAndHostSettingsTest`,
  `SeamMirrorTest`, `SeamSessionTest`, `ReplicaServerTest`, `PathTransportTest`, `LongPressTest`;
  `LensOracleTest`, `Round5Test`–`Round7Test`, `L2ProbeTest`; `SeamLivenessTest`, `HandoverTest`, `SyncTest`,
  `StyleTest`, `BatteryBrightnessTest`, `EpubChaptersImagesTest`; `TmuxTest`; `SubstrateTest` (the Reader
  CONTINUITY gate), `FilesTest`, `ReviewRound1Test`; `TorrentsTest`, `KeyboardTest`; the Music set;
  `Round9Test`; `Review20260903Test`; `ActivationTest`, `GamesKitTest`, `HoldemEngineTest` (the 3,000-scenario
  side-pot oracle, the 2,000-hand ranking corpus), `HoldemBotTest`, `GamesWindowTest`,
  `GamesReview20260904Test`, `GamesLive20260904Test`; `Review20260905Test`; `OracleWalkTest` (a seeded random
  walk of the §1 grammar at all four heights, belief = glass = per-lens TRUTH after every settle);
  `Review28Test`, `Review29Test`, `Review30Test`; `WrapEstimateTest` and the §40 pins; `SilentGlassesTest` and
  the §42 pins; `TextureCacheTest`, `AckStatusTest`; `FeedTest`, `FeedWindowTest`, `FeedNetTest`.
- `./gradlew :desktop:test` — the BlueZ glue over a fake link, `ConfigTest` (an unreadable `config.json` is left
  untouched, a tokenless one completed), `SetupServerTest`, `FeedStripsTest` (the real xkcd PNG through the decoder).
- `--selfcheck` — the whole stack scripted end to end with real fonts: ink budgets, input grammar, persistence
  byte-behaviour, zero faults/failed flushes/sticky flags. **The per-lens TRUTH oracle runs on EVERY settle**
  (`oracleRuns >= 100` keeps it from quietly stopping); it found the Music Mode card's overrun and both chrome
  overruns (§27.2). **The font ladder's TOP END is walked** (`typeLadderTopEnd`: every window at 130 %, then
  Alegreya at 480 AND 288) with Music Mode's queue ADVANCING so its surfaces repaint as deltas; two type-ladder
  checks measure the three-line lens (2/28/48 in a 64 px box) and the 480 table's history band against the REAL
  rasterizer (`HOLDEM.md` §17.2). ⚠ Each window's checks live in their own method (`gamesChecks()`,
  `feedChecks()`, `typeLadderTopEnd()`) — inline, the script passed the JVM's 64 KB method limit. Run it more
  than once: it is a rate.
- `--games-check` — the ecology over hundreds of tournaments in memory: does skill separate from variance, can the
  tables fill, does the money supply's GROWTH RATE fall (it FAILS on a rising rate, early-against-late).
- `--card-render` — the card sheets at every ladder rung into `design/shots/cards/`, at true 1×. Never judge card
  art scaled up: at 2× a detached stem and a merged pip both look fine.
- `--snapshot` — what the LEFT LENS PANEL holds (post-wire truth through pack → RLE → deflate → fragmenting → sim
  firmware → shadow), at true 1×. **A wait decides on ONE evaluation** (`while (!cond) delay(); if (!cond) fail`
  re-tests a condition the loop already passed and a periodic tick can flip back — §27.6; the 60 s settle /
  120 s wait bounds are backstops; anything over 5 s prints its cost). **The games scenes pin their world**
  (`gamesWin.roster.worldSeed = …`). **Run it more than once**, and never compare two runs with `diff -rq`: the
  clock and the throughput readout (`785K/s · 1ms` vs `1664K/s · 1ms`) differ on an unchanged tree. **To compare
  two BUILDS** keep both installs on disk (build, copy `desktop/build/install/desktop` aside, `git stash`, build,
  copy aside, `git stash pop`), run them inside one minute, and judge by WHERE the pixels differ: the status
  readout sits inside `x∈[240,400]` in a band ≤ 16 rows tall; the live scenes are `11-files-locations`,
  `38-music-mode-480-bars`, `39-music-mode-288-scope`; `10-silent.png` draws no status line and IS
  byte-stable — the useful canary.
- `tools/lint.py` gates the repo at 0 findings; its geometry rules are mirrored 1:1 (same rule IDs) in
  `wm.damage.core.geom.Geometry`, and `GeometryTest` pins both to the same fixtures.
