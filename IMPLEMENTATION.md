# Damage — implementation notes

What runs and how. State today (builds, installs, counts): `REMINDER.md`; each build's record: `HANDOFF.md`;
operations: `DAILY.md`. The phone halves of C1–C3 (motion programs, local input, the scroll effects and transitions)
are the section "The phone's halves of C1–C3"; C2's guards also appear under "Review hardening".

**The topology** (`HANDOFF.md` §19, §79): **the phone shell drives (radio and shell, while the APK is up); the
OpenRC `damage` service is the data provider — content, tmux, the windows' lanes and the PC record on the content
port, the bus alert watcher — and never drives by itself: PC-direct BLE is a manual choice** (`--transport ble`, the
replica probe `drive=ble`, the PC shell's `Target` row). The PC owns every window's shared state (Reader, Torrents,
Files, Feed, Tmux, Music, Games, Settings, the notice record) and the phone keeps a working copy (the section "The
shared window core"); each device's own store holds only its view (`CONTRACT.md` §9.7).

One `:core` library holds the shell, the byte-exact glass simulator, the compositor and every window (Main · Settings
· Reader · Tmux · Files · Torrents · Music · Games · Feed) on the CFW display contract (modes 3/6/8/9, the 11–15
texture-cache layer, the FB lease, the capability gate; on the fork's builds contract 2's modes 17–24, `FIRMWARE.md`
§4, and C1–C3's 25–27, §5–§7). Per-window records: `TMUX.md`, `TORRENTS.md`, `MUSIC.md`, `HOLDEM.md`, `FEED.md`; Files is `HANDOFF.md` §22 — it
brought the shared machinery `WINDOWS.md` §4 lists (`MenuSurface`, the WinNet channel, theme icons via desktop
`ThemeIcons` + phone `RemoteIcons` with the drawn set as fallback and release path, the §16.4 state substrate, deep
links, the notification signature, `Draw.fit` / `Draw.dynamic`).

## The two locked decisions

**Runtime: Kotlin/JVM** (open item #11). One `:core` library holds the entire shell — compositor, wire
codecs, simulator, surfaces, windows — and runs unmodified inside the desktop JVM program and the Android
APK. Core uses no AWT and no `android.*`; platform text rasterization enters through
`wm.damage.core.text.TextRasterizer` (AWT on desktop, `android.graphics` on the phone), faces
x-height-normalised to the §Type measurements on both.

**The transport ↔ shell seam** (open item #12) is `wm.damage.core.transport.Transport`:

```
shell  ──FlushRequest{ops, epoch, label, wide, writer}──────────────────────────────▶  transport
       ◀──TransportEvent{Input, Text, FlushDone, Lease, Link, DiagFlags, Battery, Fault,
                         Note, SilentMode, Wear, Presented, Applied, LocalRead, LocalBody}──
```
`DisplayOp` (`transport/Transport.kt`): Keyframe · Delta · Copy · CopyPair · StereoPair · CacheWrite · DrawText ·
DrawImage, and contract 2's DrawText2 · DrawImage2 · Fill · Clip · PresentHint · CacheWrite2.

- Ops are NOMINAL coordinates + per-op disparity; the emitter builds the per-lens stereo boxes (§3.4).
  Payloads arrive pre-compressed (zlib(rle)) — compression is the shell's job (§10.1).
- **Fids are stamped by the transport at EMIT time** (§8.2 #5), via the shared `Emit` encoder.
- `submit()` enqueues in call order and returns; the backpressure signal is `LinkState.inFlight` against
  `window`, which the shell's pump gates on. A `wide` flush drains the window and runs at depth 1; behind a lost ack
  it is nudged after the stall bound and runs at the depth the held slots leave (2 behind one), its budget stated at
  that depth (`HANDOFF.md` §77).
- The same interface serializes over TCP (`RemoteTransportClient/Server`, length-prefixed JSON + binary):
  the shell can live on the PC while the transport lives on the phone, or the reverse. The server admits
  ONE driver; claim/yield callbacks let the phone's local shell hand over and take back.

`SimTransport`, `BleTransport` and `BlueZTransport` share `CfwTransportBase` (the seam client relays) — the full choreography (capability gate → carrier CREATE → lease
both arms + 45 s renewal → warmup splash → the fid-ring clear (mode 7 sub 0, awaited) → idle keepalive → fragmenting ≤ 3800 B → msgId/session
discipline) — so both live BLE paths run the protocol brain the sim exercises on every selfcheck.

## Module map

```
core/       wm.damage.core.geom       panel constants, Rect, the runtime lint gate (the one implementation,
                                      HANDOFF §65), Layout (safe-rect-relative, §2.2b), fid discipline
            wm.damage.core.gfx        Gray8 compose surface, firmware-exact nibble RLE (pinned to fbfeas.py),
                                      4bpp pack, level-6 deflate, drawn icons/shapes (§4.5b)
            wm.damage.core.wire       CRC-16, protobuf, AA envelope + reassembly, EvenHub carrier messages,
                                      sid-0x09 lease + capability, mode 3/6/8/9 builders, CfwModes (11–27),
                                      TextureCache (byte layouts from zlib_glue.c / texture_cache.c),
                                      LoggerMsg (sid 0x0F, the Phase 0 log probe), DamageMsg (FIRMWARE.md
                                      §0/§3: DamageCaps, the control ops, the telemetry record), LaunchMsg
                                      (the connect prelude), WearMsg + CaseMsg (sid 0x10 / 0x81)
            wm.damage.core.sim        GlassFirmwareSim — the byte-exact model: per-lens shadows, cfw_diag fid
                                      ring + flags, warmup drop, msgId-255 silence, lease fail-open, the
                                      texture cache, silent rejects made loud; the overlay toggle and the
                                      log switch; a Damage build behind `damageContract`; the conformance
                                      doors (`conformanceMessage`, `conformanceLease`, `shadowCrc32`); MotionSim,
                                      BindSim, EffectsSim (C1–C3's engine, local input and effects per lens),
                                      PixelLoops (the mirror's fast loops)
            wm.damage.core.transport  the seam + Emit + CfwTransportBase + SimTransport + Remote client/server
                                      + LensPanels (the mirror) + LinkEstimator
                                      + LadderRecord (the firmware ladder's stored record, C2)
            wm.damage.core.motion     (C1–C3) Motion (the DSL), TestPrograms, MainPan, ScrollEffects, PopupPrograms,
                                      Transitions, TransitionRehearsal, CellLists, Wheel, WheelCheck
            wm.damage.core.local      (C2) BoundList, LocalRules, TableRepair, EngineRefusals
            wm.damage.core.comp       the compositor (one mode-8 flush per frame, §5), CachedText, CanvasShift,
                                      the JSONL journal
            wm.damage.core.shell      Shell, input grammar, chrome, Main, switcher, notifications, silent mode,
                                      ContentKit (lens/list/document), slides, persistence, settings,
                                      MenuSurface, KeyboardSurface (§4.8), ShellKeeper, Staging, Status,
                                      BatteryLatch, WindowContract (`DamageWindow`), AnchorCursor,
                                      QuietHours, LoopWatch, MainSurface + ListStaging + ListBinding +
                                      MainLocalCheck (C2), TransitionRun (C3)
            wm.damage.core.app        the PC record, operation log, core executor, the merge rule
                                      (Merge: the latest input wins); + the `app` lane (AppWire, AppNet, AppClient,
                                      Contracts), served on the content port by both PC builders; the record opens at
                                      a process start only (`AppHost.atProcessStart`, held by `check_apk_wiring.py`);
                                      + the WindowSource seam (LocalWindowSource, HostBackend) and the phone's working
                                      copy and queue (WorkingCopy, OpQueue: `workcopy/copy.json`,
                                      `workcopy/queue.jsonl`), built once per service in ShellService; + Levels,
                                      DevicesCore, DeviceCli; + PcScopes · Notices and NoticeMirror (the
                                      notice record), Viewers, Paged, Program (ops into an outside program), Migrate
                                      (the one PC pass of Torrents' and Files' old rows), Windows (the registry)
            wm.damage.core.web        WebServer, Access, Devices, LocalSecret, Blobs, Bundle, WebAccess, Uploads,
                                      Vendor (the terminal library); the access state opens at a process start
                                      only; the web client in `core/src/main/resources/web/` (plain ES modules,
                                      `files.txt` lists them), `node --test` over its pure modules in
                                      `core/src/test/js/`
            wm.damage.core.windows.reader   ReaderCore (shelf, record items, actions, description) on the PC record
                                      and the phone's working copy; ReaderGlasses (the glasses' presenter;
                                      ReaderWindow kept as the compatibility factory over a MemoryRecord);
                                      ReaderAnchor; EpubDoc (blocks and spans over today's text, `Epub.extract`
                                      shared with `Epub.load`), ReaderIndex (the PC's per-book facts on disk),
                                      ReaderDocs (the last two parsed books); EPUB extraction
            wm.damage.core.windows.files    FilesCore, FilesGlasses, FilesPc, FilesScopes, FilesBlobs,
                                      FilesJobs (the PC's one job slot and its supervisor), SlottedFiles; the
                                      Local/Remote providers, FilesService, the trash manifest, ListingCache (the
                                      phone's copy of the last listings, §47)
            wm.damage.core.windows.tmux     TmuxCore, TmuxGlasses, TmuxScopes, TmuxStream;
                                      FlowRender, TermRender, Sgr, the providers, TmuxNet
            wm.damage.core.windows.torrents TorrentsCore, TorrentsGlasses, TorrentsScopes; QbtClient (Web API
                                      2.11), TorrentLeech + Html, providers, TorrentsNet
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
                                      Strips, FeedStore, FeedEngine (the PC only), FeedProvider, FeedNet,
                                      FeedCore, FeedGlasses (`FeedWindow(…)` the factory), FeedScopes,
                                      FeedBlobs, FeedAnchor; ScriptedFeed
            wm.damage.core.net        WinNet — the §16.10 window channel (wreq/wres + blob answers + wpush);
                                      Reach + ReachGate — the listeners' reach gate
            wm.damage.core.bastion    the bus alert watcher: HaReader, AlertRule, RuleEval,
                                      AlertStore, BastionWatcher + BastionService + PhonePresence (the PC),
                                      RemoteBastion, AlertDelivery, HomeWatch, BastionRows, PlaceWatch (the phone)
            wm.damage.core.util       Log · Exec (subprocess runner, stderr drained concurrently) · Http
                                      (HttpURLConnection, no timeouts, multipart) · InstanceLock ·
                                      UncaughtRecord · Secrets (constant-time compares) · DayLog (the phone's
                                      log kept 10 days)
            wm.damage.core.replica    ReplicaServer — the browser replica, the journal, the log, the probes;
                                      HttpWs (the HTTP/WebSocket codec it shares with the web listener)
            wm.damage.core.content    library providers: local dir, TCP host, remote client with copy-on-open
                                      caching; the win/icon dispatch
            wm.damage.core.text       TextRasterizer, Style/StyleTransform, GlyphCaches, Wrap
desktop/    AWT rasterizer · Swing lens preview (integer-scaled, default 4×; keyboard + mouse = ring) · CLI ·
            ThemeIcons (Papirus-Dark from xfconf, rsvg-convert, mem+disk cache, serves the phone) · PgDb ·
            MusicPlugins · MusicCheck · ScriptedMusic · ScriptedTmux · ScriptedTorrents · GamesCheck ·
            CardSheet · FeedCheck · SelfCheck · Snapshot · SetupServer (/setup + /damage-apk on :7300) ·
            BlueZLink/BlueZTransport (PC-direct BLE, manual) · StandbyPolicy (the data host's probe of
            the phone; never drives) · NightlyCopy · MotionCheck + LocalCheck (C1–C3) · FilesThumbs ·
            SceneFiles · SceneFeed · JvmLoopClock
audio/      the enrichment package taken over from G2CC (Adam's code) + viz.py — run on G2CC's venv for now
phone/      foreground ShellService, on-screen lens view (touch = ring), AndroidText (bundled OFL/Apache
            fonts), BleTransport, transport seam server, wakelock + Doze exemption +
            BootReceiver, DamageApp (the uncaught-error record), §9.3 urgent phone notifications, RemoteIcons,
            AndroidMusicPlayer, MusicListener, SpotifyRemote, TrackCache · the three bus-alert
            channels + Acknowledge, NetState, AndroidPlaceSource, the "location" button (MainActivity) ·
            AndroidLoopClock · AndroidImages
```

## The mirror, the keeper and the replicas (the finishing build, `HANDOFF.md` §8)

Adam's target for it: *flash the firmware, install the app, and it works — usable from the app or the PC with a
mouse, on a pixel-exact replica.*

- **Every transport owns a mirror** (`Transport.mirror: LensPanels`): a `GlassFirmwareSim` fed the exact packets
  the transport writes; its `decode`/`fid`/`session` events surface as `mirror/<kind>` faults (the seven kinds,
  `image` included, on the simulator transport too); a packet of a message whose session ended is written but not
  applied to the mirror, counted in one `control` note at the stop. Every replica
  draws it; `Transport.injectInput` lets a replica's gesture reach whichever shell drives.
- **The connect prelude** (`wire/LaunchMsg.kt`): one sid-0x01 app-launch request after both arms are up and an
  800 ms settle, acked on its msgId, before the capability gate; the 7-packet sid-0x80 sequence is never sent.
  The model treats the prelude as required (graded U).
- **The divergence check** (`Shell.checkMirrorAgreement`): at rest the compositor's belief per lens must equal
  the mirror through the emitter's quantiser over the rows the model's panel was written in and the rows our flushes
  touched since the last agreeing check (every row on a session's first check, after a failed or rolled-back flush,
  while a disagreement stands, and once a minute; `GlassFirmwareSim.takeChangedRows`, read in place under the model's
  monitor); a disagreement is reported once per episode (the cell's `fault`,
  journal, an urgent notice on the `mirror` thread) and answered with one keyframe.
- **The session keeper** (`shell/ShellKeeper.kt`): a link end restarts the session after a 2 s pause, forever,
  no timeouts; a capability refusal is terminal (`onTerminal`). **`pause` YIELDS** (§16;
  `shell.stop(stopTransport=false)`): the glasses session, lease renewal included, keeps running with no
  driver, and `Shell.start()` ADOPTS a live session (one wide keyframe). The seam server answers a claim of a
  live session with an adopt-grant and never stops the owner's session on driver loss; only the OWNING host
  tears it down. `HandoverTest` ×4 (`preludeAcks == 1` across claim / silent death / release / pause / resume).
- **The transports a host builds** (`DesktopStack.transport` in `desktop/Main.kt`): `auto` is the data host and builds no
  stack; `sim` / `ble` / `remote` build one transport each. `--transport remote` is the explicit claim-and-drive dev
  override.
- **The phone**: `BleTransport` on G2CC's driver + the reference's sequence (RIGHT then LEFT, `retry(10, 500)`,
  MTU 512 checked ≥ 245, priority HIGH, cached pair addresses, RSSI poll); `ShellService` on the keeper (a seam
  claim pauses it, a release resumes it); the **Target** switch in `Prefs`; `LensView` draws the mirror.
- **PC-direct BLE** (`desktop/BlueZLink.kt`, `BlueZTransport.kt`): `bluez-dbus` 0.3.5 + `dbus-java` 5.2.0 (both
  MIT) on the system bus; `Device1.Connect` called raw so a refusal keeps its reason; MTU from
  `Properties.Get`; notifications from `PropertiesChanged(Value)`; `Connected=false` ends the session.
  Unit-tested over a fake link whose far end is the firmware model; manual only (`--transport ble`, the
  replica probe `drive=ble`, the PC shell's `Target` row; `drive=auto` hands back to the data host).
- **The seam carries the mirror**: `RemoteTransportServer` streams changed row ranges of both panels through
  one ordered outbox, so a panel update precedes the `done` of its flush (`RemoteTransportClient.mirror`,
  display-only).
- **The replicas**: the desktop `Preview` (**integer-scaled, default 4×** at Adam's ask — legibility judgment
  still belongs to true-1× renders or glass) and the browser page (`replica/ReplicaServer.kt` — dependency-free
  HTTP + RFC 6455, token-gated, dirty-row panel frames + 1 Hz status; `replica.html`), served by the desktop
  (`replicaPort` 7403) and the phone.
- **The reach gate** (`core/…/net/ReachGate.kt` over `Reach.classify`; `CONTRACT.md` §10.9): every listener
  (content host, media endpoint, replica, setup page; on the phone the replica and the seam) closes, unread, a
  connection that did not arrive on loopback, the tailnet (100.64.0.0/10, fd7a:115c:a1e0::/48) or — PC only — a
  private or link-local address on an interface that is not point-to-point (the bus network; a tunnel's private
  address is refused). A gate on the accepted socket, not a bind, so an interface that comes up later works. A refusal
  is said once per source per minute (the PC journals it as a `reach` note under `runShell`; `hostOnly` logs it).
  `reachOpen: true` turns it off on the PC and says so at start. Three known gaps, in `Reach`'s KDoc: a peer of a
  tunnel reaching the LAN address; a mobile carrier's shared range equal to the tailnet's on the phone; a global IPv6
  address arriving on the bus network is refused as public. The shared token still guards every path.
  `tools/check_apk_wiring.py` fails a listener built in main code without a `reach` argument.
- **Host-supplied Settings rows** (`HostSetting`): the display target on both hosts — staged on scroll, applied
  on tap, reverted on double-tap; applying rebuilds the stack. **Host categories** (`Shell.hostCategories`):
  a category a host supplies before its window exists — the phone's "Bastion" (`bastion/BastionRows.kt`); a window
  built later returns the same row ids and wins by name. Shared rows in it go through `Shell.sharedRow`
  (`ShellSettings`, on the PC record); the phone's own rows live in its preferences.
- **Decision 6**: an ORDINARY notification arriving while the wheel, the context menu or the keyboard is open
  waits behind it; an EMERGENCY cancels the surface (the keyboard's draft kept) and shows first.
- **Long-press defaults off** (`DESIGN.md` §1.2/§1.3): a bare long-press is a no-op everywhere. The
  switcher opens by the chord: long-press, then double-tap within 800 ms of the release (event 9 arms, event 10
  refreshes; a mistimed chord is plain back). The Settings row **"Long-press": off / switcher** restores the
  direct open. `LongPressTest`; on hardware event 10 fires after almost every touch-end (§11).

## Running it

Desktop (the sim standing in for glass; also serves `~/books` to the phone):

```
./gradlew :desktop:run                        # AUTO (§19, §79): data host (content+tmux+the record+the bus watcher) + replica; probes the phone for its status line and never drives by itself — PC-direct BLE by hand with the replica probe drive=ble — stop the service first (exit 4 otherwise)
./gradlew :desktop:run --args="--help"   # the usage text; an argument the program does not know is refused (exit 2) and nothing starts; one entry point per line
./gradlew :desktop:run --args="--transport sim"   # the simulator in-process (development) — stop the service first (exit 4 otherwise)
./gradlew :desktop:run --args="--transport ble"   # PC-direct BLE only (manual) — stop the service first (exit 4 otherwise)
./gradlew :desktop:run --args="--remote HOST"     # = --transport remote: claim the phone's transport and drive through it — the EXPLICIT dev override; stop the service first (exit 4 otherwise)
./gradlew :desktop:run --args="--ble-info"    # the BlueZ adapter and the devices it knows (no discovery)
./gradlew :desktop:run --args="--selfcheck"   # the scripted gate (REMINDER.md carries the count): two passes (contract 1 with a gain-in-place step at boot, then contract 2 with cached text on), every window's walk, the truth oracle on every settle
./gradlew :desktop:run --args="--snapshot DIR"  # lens-truth PNGs of every surface, and the converted windows' again in DIR/phone/
./gradlew :desktop:run --args="--motion-check DIR"  # C1–C3: the test programs, the notice's programs, the transitions, Main's scroll effects, the wheel and Music's Scope on the simulator, a strip PNG each; exit 1 when one does not hold
./gradlew :desktop:run --args="--local-check DIR"   # C2: Main's bound list fired through the simulator's input hook at every cursor, both directions and the press, four heights, 160 and 256 KiB; C3 DW: the switcher's wheel as list 2
./gradlew :desktop:run --args="--wheel-icons DIR"   # the switcher wheel's cells painted with the theme's icons beside the drawn set's
./gradlew :desktop:run --args="--epub-check [DIR]"  # parse every book in DIR (default: the config's booksDir); exit 1 on zero books, a DIR that is not a directory or a failing book
./gradlew :desktop:run --args="--music-check" # the real g2cc library, read-only bar the additive schema migration: counts, catalog, lanes, cache keys, Qdrant, one viz blob, the adaptive-playlist derivation
./gradlew :desktop:run --args="--games-check" # the Hold'em ecology over hundreds of simulated tournaments (pure in-memory; add `deep` for a longer run)
./gradlew :desktop:run --args="--card-render" # the card sheets into design/shots/cards/ at true 1x
./gradlew :desktop:run --args="--feed-check [DIR] [live]"  # the feed engine over the captured fixtures; `live` = one paced fetch per source, read-only, temp dir
./gradlew :desktop:run --args="--host-only"   # content host alone (books + tmux + the record + setup page, no stack ever) — stop the service first (exit 4 otherwise)
./gradlew :desktop:run --args="--devices"     # also --approve CODE LEVEL · --deny CODE · --revoke ID — asks the running service on loopback; exit 0 / 1 / 3
./gradlew :desktop:test                       # the BlueZ glue over the fake link, the argument parser (CliTest), the config file's safety, SetupServer, the standby policy, the nightly copy, the status cell's fit, the epub check, theme icons, the xkcd PNG through the decoder, Files' thumbnails, the notice timer's render, the terminal library's pin, the motion check, the health readers, the link cell's tags
```

Preview: mouse wheel scroll · left click tap · right click double-tap · press-and-hold long-press · keys
↑/↓ Enter Backspace Space R T(type) · Tab lens · B both · -/= window scale (integer, default 4×). The browser
replica link is printed at start (`http://<host>:7403/?token=…`); the same server answers
`GET /journal?token=…[&tail=N]` (that host's flush journal, streamed from disk in 64 KiB chunks; `tail` = the
whole lines that start inside the last N bytes — `tools/journal_report.py -` reads it from stdin), `GET /journals`
(the archives) and `&name=journal-YYYYMMDD-HHMMSS.jsonl` on `/journal` (one archive; `tools/journal_pull.py` copies
them), and `GET /log?token=…[&tail=N]` (the process's last 4,000 log lines, `Log.recent`); on the phone `GET /logs`
(its day files, `util/DayLog.kt`: 10 days, 32 MiB a day) and `&name=log-YYYYMMDD.txt` on `/log` (one day).

**The journal and the record of an uncaught error (`HANDOFF.md` §76.1).** At a process start a journal past
64 MiB is renamed to `journal-YYYYMMDD-HHMMSS.jsonl` and the fresh file starts with a `journal` note naming it; nothing
is deleted. `UncaughtRecord` writes a fault's trace to `uncaught-pending.txt` at the fault; the next start journals an
`uncaught` note and moves the file to `uncaught-<time>.txt` — eight whole records per process, then one counter line
rewritten in place; 256 KiB, a record cut there still names its root cause; the same fault again (the first 32
frames compared) is appended and said short. The start order: on the PC `InstanceLock.claimOrExit` (exit 4 while
another process holds the data directory) ahead of the record's install, the journal's rotation, the `process` note
(with the archives' count and total) and the pickup; on the phone `DamageApp.onCreate` installs the record and
`ShellService.onCreate` runs its start steps ahead of `startStack`, caught as one (a fault there is one ERROR line and
the stack starts). The phone half has not been read on the device.

**The journal's event times and notes (`HANDOFF.md` §79).** A transport event carries its own time (`t`), and a line
written a second or more after its event says `late`; `journal_report.py` sorts by event time. Submit records gain
`inWaitMs` (a gesture's wait in the shell behind a full window), `win` (the window id) and `mode`; the report's
per-gesture table gains `input->done ms` beside `first wait ms`, and a battery line by state. New notes: `slow` (a loop
message of a second or more: thread CPU against wall and collector time), `cue` (a change of field 14 in an unasked
09-01 block, and every sid-0x0d frame while the state says silent), `heap3` (below), `place` (the phone's position
watch: words and ages only), and on the PC `reach` and `presence`.

Config in `~/.damage/config.json` — books dir, ports, token (generated on first run; must match
`damage-secrets.properties` before building the APK), the phone host, the cached pair addresses, the tmux
hosts/quick keys/snippets/wait patterns, `qbtUrl` + the TorrentLeech credentials, the `music*` keys of
`MUSIC.md` §9.7 and `mediaPort`, `feedSources`/`feedUserAgent`/`feedDataDir`, `setupPort`/`setupToken`; the bus
watcher's `haUrl`/`haTokenFile` (a path: the token is read from that file, never a value here)/`haRulesFile`
(default `<dataDir>/bastion-rules.json`)/`haPollSec`/`haSilentMin`, the nightly copy's
`backupDir`/`backupKeep`/`backupAt`, `reachOpen` (true turns the listeners' reach gate off), the web listener's
`webPort`/`webHosts` and `simContract` (the sim stack's firmware contract) — each defaults off
or to the value `DAILY.md` names; the `Config` class in `desktop/Main.kt` is the key list. An UNREADABLE file runs
defaults for that start, loudly, and is never rewritten.

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

**The all-day daily driver:** `--no-preview` runs any mode headless; `:desktop:stageJar` copies the fat jar to the
STABLE `~/.damage/damage.jar`, which the OpenRC service `/etc/init.d/damage` runs in `auto`. Deploying, and stopping
the service before any `:desktop:run` dev session, are `DAILY.md`'s.

## Configurations wired today (as built; `REPLAN.md` §14 changes the first two at step S1)

| configuration | how |
|---|---|
| **app + home PC** (the default — §10.1 row 1 as INTENDED, §19) | APK (Target = glasses) DRIVES; the `damage` service is the data host: live library, tmux, the record, the bus watcher. The PC never claims |
| app alone | the APK with no PC reachable: its own shell, cached library + cached books, staleness said; the working copy catches up on reconnect; the bus alerts say "home unreachable" after the phone's set minutes |
| PC-direct BLE (the rare case; manual only, `REPLAN.md` §14.4, `HANDOFF.md` §79) | by hand: the replica probe `drive=ble` on the service (back with `drive=auto`), the PC shell's `Target` row, or `--transport ble` in a dev run. Nothing starts it by itself |
| PC drives through the phone | `--transport remote` ONLY — the explicit dev override |
| laptop-direct with the simulator | `:desktop:run --args="--transport sim"` — the development environment |
| browser replica | `http://<desktop-or-phone>:7403/?token=…` from any machine on the tailnet — the PHONE's is the live view in the default configuration |

## Phone-primary (`HANDOFF.md` §19)

- **`Persistence` is each device's own view store** (cursors, levels, local caches, the shell's copy of its settings):
  schema v2 `{__v, records{k:{v,t}}}`, `t` written and read by nothing; `load` reads the file once per store (a session
  start over the same store keeps what memory holds); a file that does not parse is kept as `.bad` and the start
  begins from defaults; a sub-record its window reported this session and no longer holds is removed, one it did not
  load never is. Nothing in it travels between devices: shared state is the PC record (S1-5's close).
- **The seam status probe** (`SeamProbe` / `Ctl t="status"`): a non-claiming "does the APK want the radio?"; an
  old APK answers `busy`, read as YES.
- **Desktop `auto` = the data host** (`StandbyPolicy`, R6 §5): one process-wide store shared by any stack; it probes the phone's seam every 5 s for the status line ("data host · phone drives",
  "· the APK chose Target≠glasses", "· phone unreachable 2m · PC-direct BLE is manual (drive=ble)") and nothing else:
  `StandbyPolicy.Action` has no member that starts a stack, so no probe answer can. A manual stack (`drive=ble`, the
  `Target` row) runs until `drive=auto`; a stack found up in `auto` (its stop dropped by a switch in progress) is
  stopped. Every `drive=` use is a `keeper` journal note. There is no automatic claim (why: `HANDOFF.md` §72). Every
  server retries a busy port every 5 s (`bindOrRetry`). `StandbyPolicyTest` pins it.

## The phone's liveness and the seam heartbeat (`HANDOFF.md` §13)

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
- **Distribution**: `./gradlew :phone:stageApk` → `~/.damage/damage-wm.apk`;
  `desktop/SetupServer.kt` serves `/setup` + `/damage-apk` on :7300 with G2CC's URL, token and Tailscale-only
  gate (`setupPort` / `setupToken` in `config.json`, `HANDOFF.md` §44) — behind the listeners' reach gate first,
  then its own Tailscale-or-loopback check.
- The capability gate refuses any firmware without an `EVENCFW` string. Compass, IMU, wear detection: per
  `DESIGN.md` §7 — a placeholder compass cell until the mode-10 feed exists; head tracking defaults OFF.

## The bus alert watcher, presence and the nightly copy (build W, APK 0.66, `HANDOFF.md` §79)

A reader on the PC that only READS his own home hub and raises notices on his glasses and phone (R6 as amended by the
lead's decisions; `CONTRACT.md` §7, §11.2; `BASTION.md` F13). Rules and the config's keys: `DAILY.md`.

- **The reader** (`HaReader`): one `Http.request("GET", …)` site in the package (lint RO001); the paths are the health
  check and `/api/states/<entity>`, the entity id checked as `domain.object_id` before the request. The token is read
  from the file `haTokenFile` names (trimmed, never logged; a case it cannot use is said in plain words: does not exist,
  is empty, could not be read, does not hold one token). `haUrl` empty or no `haTokenFile` → the watcher is off and says
  why at start (a config change still needs a restart); a token file it cannot use → off, said, `watcher.tokenfile`
  raised, and the file is read again once a minute until it holds a usable token.
- **The watcher** (`BastionWatcher`, both host builders): a round every `haPollSec` (10 s); liveness without timeouts —
  a round still in flight after 60 s is said and the next starts beside it, never more than two; a late answer for an
  older round is dropped and said. Rules from `bastion-rules.json` (`haRulesFile`), read once at start; a bad rule
  refuses only itself. Kinds (`RuleEval`, one pure step): `above` / `below` with `clearAt` hysteresis, `equals` /
  `notEquals`, `unavailable`, `change` (the value differs by `at` or more from a baseline taken at arming — either way,
  or with `direction` only `up` or only `down` — re-taken on acknowledge and kept in `alerts.json`). A `when` guard on any rule: judged only while it
  holds; its end resets the clocks and baseline and clears the alert; no reading (unreadable, `unavailable`, `unknown`)
  holds everything. Built-in alerts `watcher.*`: the hub not answering for `haSilentMin`, a refused token (polling
  stops; the token file is read again once a minute and polling resumes when it holds another token — a refused token
  is never sent again), the token file, the rules file, `watcher.rule.<id>` (a refused rule), `watcher.entity.<entity>`
  (a 404, or `haSilentMin` of failed reads while the hub answers), the store, the nightly copy. **The watcher's own
  trouble never ends an alert:** a rules file with a problem retires nothing, a refused rule's alert is held, and a
  repeat-only tick runs whenever the store holds a raised alert, the watcher on or off. The state line
  (`stateLine()`) goes to the `table` answer (the phone's `Watcher` row) and the PC log (tag `bastion`); a rule with no
  usable reading for `haSilentMin` says so there (`rule <id>: no usable reading since HH:MM`).
- **The store** (`AlertStore`, `alerts.json`): CLEAR → RAISED → ACKED → CLEAR with an episode per raise; `severity`
  `normal` (said once, or every `repeatMin`) or `urgent` (every ten minutes until acknowledged, passes quiet hours; a
  severity edit applies from the next episode). The epoch (minted when the file is created or recreated after an
  unreadable one) and the last event `seq` live in the file; the event ring (200) is memory, so a `table {since,
  epoch}` older than the ring answers `truncated` with the whole table.
- **The channel** (`BastionService`): the `bastion` window service on the content port, always up: `table {since,
  epoch, presence}` → the table, the state line and `bus`; `ack {id, episode}` (an action on the alert, the store's
  alone: no hub request); pushed `event`s from ONE sender coroutine.
- **The phone** (`RemoteBastion`, `AlertDelivery`, `HomeWatch`; wired in `ShellService`): a first-contact reconcile
  from the table (adopt and list, never pop old ones); a `table` ask every 15 s keeps the last-heard clock moving; an
  ask with no answer for 60 s drops the connection, which is opened again (the same quick request as Torrents'
  `snap` poll, Feed's `sources` poll and Music's `catalog` refresh: `RemoteWin.request(quick = true)`, its 60 s counted
  from when no older request of the channel is pending; while down the row reads `home not answering · PC unreachable
  Ns`); a
  truncated answer is reconciled and sets `lastSeq`; every re-ask is paced (5 s, else the next ping); an unapplied push
  is journaled once per reason per connection. One record drives the glasses notice
  (`Shell.notifyFromHost(…, gatedBy = "Bastion")`, gated by the category's own rows, never by `Notify · Damage`) and
  the Android notification (channels "Bus alerts", "Bus alerts · urgent", "Home unreachable"; an Acknowledge button
  that sends `ack`; not behind Global `Phone notifications`). Quiet hours (Global, shared; default off) hold a NORMAL
  raise or repeat and release each still-raised alert once at the end, "since HH:MM"; a NORMAL clear inside them is
  dropped and its notification cancelled silently; a held alert is released at the first answer after a start.
  `Phone · alerts` off cancels the bus notifications at once; on re-posts every raised alert silently. "Home
  unreachable" after the phone's set minutes with no answer, said only while the phone itself is online (`NetState`, a
  network callback). The category's rows: `Watcher` · `Notify · alerts` · `Notify · cleared` · `Phone · alerts` ·
  `Home unreachable` · `Position` · `Presence · location` · `Acknowledge`.
- **Presence, `damage.phone`** (`home` · `away` · `unknown`, usable only in a `when`). On the PC (`PhonePresence`, both
  host builders): once a minute `tailscale status --json` (a local command through `Exec`, one at a time, never
  waited on) for the configured phone's peer — the path signal is `home` when direct to a private address on one of
  the PC's non-point-to-point networks, or (ruling R3.1) to any other private address (10/8, 172.16/12, 192.168/16,
  fc00::/7, fe80::/10) outside its point-to-point interfaces' networks: the bus's second network is routed from the PC,
  a tunnel's range is not the bus; `away` after 15 minutes relayed or public, `unknown` otherwise (an idle peer, a
  tunnel's range and the tailnet's own ranges included). The phone's report rides each `table` ask (`presence {word, fixAgeMs}`; none, `unknown` or older than 2 min
  counts as no report); each answer carries `bus` (the path signal alone). Combined: `home` if either says home; `away`
  if the phone says away, or with no report the path far for 15 minutes; else `unknown`. Each change is one log line
  and, under `runShell`, a `presence` note saying which signal decided; the lines carry no address or port.
- **The phone's position** (`PlaceWatch` over `AndroidPlaceSource`): the passive and network providers, about every 10
  minutes and 100 m (no GPS asked for, no Play services; the service's foreground types unchanged), only with precise
  location allowed "all the time" (the app's "location" button asks in two steps) and the `Presence · location` row
  on. Learning: a fix ≤ 100 m accurate taken while `bus` is true becomes the one CANDIDATE (a later fix as accurate or
  better replaces it); it is stored once a `bus: true` answer arrives to a table ask SENT 150 s or more after the fix
  (`LEARN_CONFIRM_MS`, inferred; measured at the ask); `bus: false` or the channel's end drops it. The bus position is
  `noBackupFilesDir/bus-place.txt` (left out of Android's backup), never sent. Judging: home within 300 m, away beyond
  500 m, the band between keeps the last word; a fix coarser than 200 m is ignored; no permission, no usable fix
  younger than 30 min or no stored position → `unknown`. **No coordinate or distance leaves `PlaceWatch`** — journal, log, channel, PC
  (`PlacePrivacyTest` refuses any number with a distance unit); `place` notes carry words and ages.
- **The nightly copy** (`desktop/NightlyCopy.kt`, both host builders): state files only, an allowlist — `state.json`,
  `state.json.bad`, `torrents.json`, `alerts.json`, `uncaught-pending.txt`, the rules file as `bastion-rules.json`,
  the feed's `sources/` and `binge/`, `record.json` and `devices.json` (never `oplog.jsonl` or
  `local.secret`); never journals, `phone-journal/`, `trash/`, `config.json`, caches or the music
  database. Into `<backupDir>/damage-YYYYMMDD-HHMMSS.partial/`, renamed when whole; an unchanged file is hard-linked to
  the previous copy. The configured folder is never created (an unmounted disk must not take the copy onto the root
  disk). After a good copy, folders named exactly `damage-YYYYMMDD-HHMMSS` beyond `backupKeep` (14) are deleted,
  oldest first; nothing else is touched. At `backupAt` (03:30) daily, plus one catch-up after a start when the newest
  copy is stale; a file that vanishes during the walk is skipped and said in the run's line. A failed run is a log
  error and the built-in alert; the next good copy clears it. A copy still running when the next is due raises the
  alert (the next is skipped); the running copy's good end clears it.
- **`heap3`** (`FIRMWARE.md` §5.7): at every session start the free-memory fields of the
  telemetry record at four points — `pre-create` (the reset check's record, no extra read), `lease`, `atlas` (after
  the read-back), `first-frame` — journaled as `heap3` notes; the three reads are the existing telemetry read, none in
  Silent Mode, an unanswered one said and never waited on; off on instant test transports.

## The shared window core, the PC record and the web listener (builds S1-1 and S1-2, as L1 left them, and S1-3; `HANDOFF.md` §80, §84, §89)

The PC owns every window's state (`core/…/app/Windows.kt`'s `ALL`, the notice record among them); the phone keeps a
working copy; a browser is a third presenter (`CONTRACT.md` §2, §9, §10, §12). Ops and keys: `DAILY.md`.

- **The record** (`core/…/app/`, both PC host builders): `record.json` (`{v, epoch, items, covered}`; items
  `rec.<window>.<name>` with `v` or `gone`, `by`, `at`), written only by its saver's own thread through
  `Persistence.writeAtomic` with the temp file forced before the move. `oplog.jsonl`: one line per op `{seq, op, eff,
  ans}`, appended and forced before the answer and the publish; a failed append answers `Refused(retry)`, leaves no
  line and raises `record.log`. One core executor thread (`damage-core`) runs the merge rule (`Merge.judge`, pure, per
  target: each target is written unless the item holds a later input time; an equal time, the later arrival; an equal
  value, nothing) after a window's own rule where it has one (`Windows.RULES`: the notice record by occurrence, Files'
  clear of a cut), and publishes each change at once; an op's `at` is bounded by the PC's clock (`AppHost.clock`). The
  PC keeps no stream numbers and no ring. Opened only by `AppHost.atProcessStart`, never by a tool flag
  (`check_apk_wiring.py`): the record, any logs set aside beside a missing record (oldest first, `record.replay`), the
  log past `covered` (a line's effect only), the epoch (new only for a new or unreadable `record.json`), a save, the log
  set aside past 64 MiB or with a bad line, a `record` journal note. A 0.67 `record.json` and `oplog.jsonl` are read as
  they stand; `record.seq` is left on disk, unread. A log that does not open or replay leaves `logReady` false: nothing
  is saved and every op is answered `retry`; the pacing tick opens it again until it opens (said when it
  opens), and the lane keeps each record or window subscription it refused meanwhile and serves it on the tick after.
  Alerts under `record.` survive a watcher start (`AlertRule.RESERVED_PREFIXES`).
- **The `app` lane** (`AppWire`, `AppNet`, `AppClient`, `Contracts`): on the content port, its hello `{t: app, proto,
  stream, contracts}` (`AppWire.PROTO`, equal to the pages' `proto.mjs`, tied by `BundleTest`) after the
  port's own `proto: 1` hello. Per connection one sender thread and an outbox of 4,096
  frames (a reader that stops reading is ended `slow`); every step that touches the record runs on the executor, and
  an op's answer goes out before its change; an op that changed nothing publishes no change. Every `sub` is answered
  with a snapshot. Reach is decided by the paced ping alone; reconnects on 2 s pacing. `Contracts.MINE` (from
  `Windows.VERSIONS`): a mismatched window is refused (`contract`, the op waits) and Main's row says "PC and phone on
  different builds for <window>". The phone holds the shared token (level full); `local` (the command line) is the
  local secret on loopback. A device action (approve, deny, rename, revoke; the PC's own browser's self-approval too)
  is a `devices` journal note with who, what and the answer; a web page taking or leaving a scope is a `usage` note
  (`<device> web <window> open|leave <scope>`).
- **The working copy and its queue** (the phone, built once per service in `ShellService.onCreate`, closed in
  `onDestroy`, never per stack): `WorkingCopy` (`workcopy/copy.json`, temp-and-move, forced) and `OpQueue`
  (`workcopy/queue.jsonl`, one writer thread, `force(true)`; an op carries `at`, the draft's input time; it is sent only
  once its line landed; coalescing into the last unsent op; every loaded op frozen; a 0.67 line without `at` takes the
  load time; a file that cannot be read is moved aside as `queue.jsonl.bad-<ms>` and said). A presenter reads the PC's
  state plus this device's unanswered ops, folded by `Windows.RULES` and the merge rule as the PC folds them
  (`WorkingCopy.fold` / `shown`); an op its state already shows leaves the waiting list. The seam is `WindowSource`
  (state, description, level, `act` returning at once, listeners, `now()` — the clock a source stamps with); on the PC
  `LocalWindowSource` over `HostBackend` (device `local`, a fresh stream per process).
- **Reader** (`core/…/windows/reader/`): `ReaderCore` (a `WindowCore`: the shelf and its scan, `describe`, `level`,
  `ACTIONS` — `place` position; `bookmark`, `set`, `open` register; `reset` removal — and the drafts; levels by
  `levelOf`: `open` and `place` view, the rest control) and `ReaderGlasses` (today's levels, paint, open, restore and
  layout, plus `placeFrom` / `take` / `seat`, `sendChanges` at today's save points, `Go there` for another device's
  newer place). A place carries the time its save point first saw it move, kept as `"at"` beside the place in
  `window.reader.book.<id>` so that it survives a restart (a place restored without one counts as older than any the
  record holds); a place the record holds as this device's own, equal to the local one, counts as sent; an own
  bookmark is taken from the record when the view holds none for that book. A place is `ReaderAnchor`
  `@c<offset>.<extractor>~<quote>` over `Epub.load`'s text (`EXTRACTOR = 1`). The glasses' row stays local
  (`window.reader`). `ReaderWindow(…)` is the compatibility factory over a
  `MemoryRecord` that tools, `SelfCheck` and tests build. An old `window.reader.conv` key stays in his stores, unread.
- **The notice record** (`Notices`, `NoticeCore`, `NoticeMirror`): items `rec.notices.<source>:<thread>`, raised
  on the PC only (Torrents' done and error notices among them), read and dismissed from input on any interface, ordered
  by occurrence (`Notices.RULE`). Each host's `NoticeMirror` keeps its notice cursor and pops only what is raised past
  it; with no shell on the PC its cursor follows the top. `heldUntil` carries Quiet hours' decision.
- **Torrents and Files**: `TorrentsCore` and `FilesCore` hold their own rows in the record (Torrents' search
  recents and Notify rows; Files' clipboard, sort and hidden), with `TorrentsGlasses` / `FilesGlasses` as the glasses'
  presenters and `TorrentsScopes` / `FilesScopes` serving the pages; ops into the torrent client and the filesystem are
  program ops (`Program`: `(device, conn, k)`, never queued, one `program` journal note each). On the glasses Files
  takes a row this device wrote unless this presenter itself wrote it since it started.
- **The PC's Files** (`FilesPc`, `FilesJobs`, `SlottedFiles`, `LocalFilesProvider`, `web/Uploads.kt`): one job slot
  for every PC caller (the PC's glasses, the phone's glasses through `FilesService`, the pages' program ops), each job
  on its own thread; the busy line names the holder and how long it has made no progress. The supervisor
  (`FilesJobs.supervise`, on the record's 2 s tick) ends a stats after 10 min and an upload ticket whose body has not
  begun after 2 min (its ticket cancelled with it) as failed, said, and frees the slot and, for a stats, the glasses or
  phone that waited on it (the caller waits on the job's end, not on the file-system call); it never ends a job that
  writes. Uploads place under the asked name or the first free `name (N).ext` (a link that refuses an existing
  name), never replacing; the upload's socket keepalive is 60 s idle, 15 s interval, 4 probes. The trash's
  `manifest.json` is written whole through a forced temp file; trash writes the row before it moves the file. The trash
  folder is refused to every other writer.
- **Feed** (`core/…/windows/feed/`): `FeedCore` (the record `rec.feed.…` — read lists per source, flags, seen
  stamps, the article `place`, `binge.<src>`, pins, recents, the shared and glasses rows; one engine listener per
  process raises the notices into the notice record, a first sight writing `seen`; the record's Fetch and Keep handed
  to `FeedEngine.configure`), `FeedScopes` (`desc/feed` and `feed/src:<id>/page:<n>` on the thread `feed-describe`;
  `item`, `comments`, `comic`, `num`, `binge` — each may fetch a site — on `feed-reading`; `refresh` and `browse`
  program ops), `FeedBlobs` (`feed.<key>`, only keys a scope handed out, the 4,096 newest), `FeedAnchor` (the glasses'
  wrapped line ↔ block and offset), `FeedGlasses` (the presenter; `FeedWindow(…)` the compatibility factory over a
  `MemoryRecord`). The fetch supervisor: `RealFeedHttp.sweep`, from the engine's loop, ends a fetch in flight for 5
  minutes, says so on the source's health and in the log; the next due time tries again. The phone builds
  `RemoteFeedProvider` alone (no engine, no switch); its old `feed/` folder's size is said once in the log.
- **Tmux** (`core/…/windows/tmux/`): `TmuxCore` (the record `rec.tmux.…`: `alerts`, `mute.<host>/<name>`,
  `glasses.size`, `glasses.context`; `desc/tmux`, Main's card; one provider listener per process raises the waiting
  edge into the notice record, "`<name>` wants input"), `TmuxScopes` (`tmux/session:`, `tmux/history:`, the frames
  as the live source `live/tmux/frame/<host>/<name>` with colour runs from `Sgr.colourRuns`; the program ops `key`,
  `snippet`, `line`, `raw`, `new`, `rename`, `end`, `select` on ONE single-thread pool, `AppNet.serve(pool = …)`, a
  task whose connection ended or was revoked first refused `ended`), `TmuxStream` (the desk's stream: one `tmux -C
  attach-session -t =<name>: -f ignore-size` client per (host, session) while a viewer holds it, locally or as one ssh
  exec; its init is a `display-message` and a `capture-pane -p -e` block, then the active pane's `%output`, re-init on
  a pane, window or layout change, `%session-renamed` → `end{to}`, `%exit` → `end`; the last viewer's leave closes its
  stdin, its exit waited for on its own thread and said if it lingers), `TmuxGlasses` (the presenter; `TmuxWindow(…)`
  the compatibility factory). `LocalTmuxProvider` has `health()`, `sendRaw` (printable runs `-l
  --`, the rest `-H`, joined in one tmux process; every argument's final `;` escaped once), the waiting edges sent
  right after the status push that carries them, and ssh `ServerAliveInterval=15 ServerAliveCountMax=4`.
- **The live seam** (`AppNet.Live`, `AppWire.live`): `sub live/<window>/<src>` checked on the executor at attach
  (the contract window, then the level), the viewer kept with its need; detached by `unsub`, a need of none, the
  viewers' sweep (which sends `live{end{again: true}}`; a live `sub` counts as the connection's ping), the
  connection's end, a revoke or the PC's stop. No numbering: a new connection gets a fresh `init`. A page that cannot
  keep up is ended `slow` by the outbox (4,096 frames).
- **The terminal library**: `@xterm/xterm` 6.0.0 (MIT) from the WebJar `org.webjars.npm:xterm__xterm:6.0.0`,
  one pin in the version catalog, `runtimeOnly` in the desktop module (and `testRuntimeOnly` in core for
  `LookServer`); `VendorSource` / `ClassPathVendor` read it from the class path (its version from the jar's
  `pom.properties`) and the listener serves exactly `GET /vendor/xterm/{xterm.mjs, xterm.css, LICENSE}` with the
  bundle's headers to any page; the phone passes none (404 with the reason). No copy in the tree.
- **The document and the index** (the PC only): `Epub.extract` behind `Epub.load` (today's text unchanged); `EpubDoc`
  (blocks and spans — em, strong, code, link — aligned onto today's text by a markup walk; http(s) links kept, a link
  to another spine document an in-book `jump`, the rest dropped and counted); `ReaderIndex` (`reader-index.json`:
  length, chapters, cover per book; one book at a time on `Dispatchers.IO`, no image bytes; a cache);
  `ReaderDocs` (the last two parsed books). The shelf is listed at each process start and when a page subscribes the
  shelf or Main's card (`PcScopes.gate`), never on the executor or the shell loop.
- **The web listener and access** (`core/…/web/`; both PC builders through `startWeb`): the port `webPort` names (0 =
  off) behind the reach gate before a byte; HTTP/1.1 and WebSocket on `replica/HttpWs.kt`, one request per connection, TCP
  keepalive on every socket. `Access`: `Host` one of `localhost`, `127.0.0.1`, `::1`, this machine's name, `webHosts`
  or the arriving address, with the listener's port; `Origin`, when present, exactly `http://` + that Host. `Devices`
  (`devices.json`: SHA-256 hashes compared in constant time; waiting pages with a code that live while their socket is
  open; single-use claims redeemed by `POST /pair/claim`, which sets the `HttpOnly; SameSite=Strict` cookie; approve,
  deny, rename, revoke, replace — a revoke or replace ends the device's live connections at once; a name kept printable
  and whole; a file that cannot be read or decoded set aside as `devices.json.bad-<ms>`, said, and the table starts
  empty). The PC's own browser is approved at full by itself (`Access.ownBrowser`: it arrived on the loopback
  interface, its `Host` is `localhost`, `127.0.0.1` or `::1`, its `Origin` that name with the listener's port; turned on
  by the service's listener only, `WebAccess`), recorded as `this PC's browser`. `local.secret` (0600, created once; a
  file others could read is refused). `Levels` (view, control, full) checked by the PC at apply time (`AppHost.act`'s
  gate). PC-served scopes (`PcScopes`: `desc/main`, `desc/reader`, `reader/<level>`, `reader/book:<id>/part:<n>`,
  `diag` at full, with the PC log's last 300 lines) on one describer thread. Blobs (`ReaderBlobs`: covers and images,
  Range, nosniff, sandbox, past 32 MiB refused 413) and fonts (`/font/<face>-<weight>`, the glasses' four faces through
  fontconfig, approved devices only). The command line (`DeviceCli`): `--devices`, `--approve CODE LEVEL`, `--deny
  CODE`, `--revoke ID`, client calls over loopback with the local secret, exit 0 / 1 / 3, nothing started.
- **The web client** (`core/src/main/resources/web/`, plain ES modules listed in `files.txt`, a Chromium 108 floor; the
  policy allows no inline script and allows inline styles, `style-src 'self' 'unsafe-inline'` — `BundleTest`): the
  shell (`app.mjs`: the connection, the waiting screen and the claim, addresses `/w/<window>/<target>[@<anchor>]` with
  back and forward, the rail or bottom bar, one focus model, dims with their reason, toasts), Reader (`views/reader.mjs`,
  `reader-model.mjs`: the shelf, a book one spine part at a time; a place op carries the time of his input and is sent
  at the save point and on leaving; the page follows another device's newer place), Torrents, Files (an upload says
  the name it was placed under; the slot's line shows the slot's job), notices, the devices page, Health (with the
  diagnosis; the section "The health view"), themes in the glasses' faces; Feed (`feed-model.mjs` decides,
  `views/feed.mjs` draws; `doc.mjs` the one document renderer, Reader's and Feed's; a place follows Reader's gesture
  rule) and Tmux (`tmux-model.mjs`, `views/tmux.mjs`;
  `term-render.mjs` the colour grid and flow; `terminal.mjs` the desk terminal: it loads the PC-served library, swallows
  the library's answers to terminal queries and drops focus reports, sends his bytes as `raw` ops, pastes through the
  paste rule; `ctx.live` the live seam). The stream id lives in local storage and a page numbers its ops in memory; every op carries
  `at` on the PC's clock (`pcNow()`, from `welcome.pcClock`); the page keeps no queue. Its three recoveries: a `proto`
  end reloads the page on the 2 s pacing; two pings unanswered in a row let the socket go and make a new one; the reach
  line names the day when it is not today.
- **The judge** (`tools/snapdiff.py`; `Snapshot.kt`'s phone arrangement renders the converted windows' scenes again into `DIR/phone/`
  over a `WorkingCopy` fed from a loopback content host): each scene compared pixel for pixel with a base rendered
  before a build's first product edit, masked by the base pair's union and the declared live cells; a difference outside
  the mask is named or is a defect. Files' scenes read no live machine: `SceneFiles` answers a pinned
  list of locations (totals and free space), a made-up root listing and folder and trash with pinned dates, over a
  provider that reads no mount table; the standing base is `private/build070/base-070/` (mask `base-070-mask/`). Still
  read from the machine: the books (hash checked around each render) and the desktop's icon theme.

## The health view (R-b)

One record of how the system stands, read where each truth already is and shown on the pages, the phone app and the
glasses' link cell (`REPLAN.md` §17.4; a line is `CONTRACT.md` §3.2's value). It only shows: nothing in it acts, and
nothing in it is saved — after a restart every line is read again from its source.

- **The record** (`HealthCore`, `core/…/app/Health.kt`; `startHealth` in `desktop/…/HealthParts.kt`, called once by
  each PC host builder at the process start, before the content host listens): the lines service, reach, phone, hub,
  backup, cap, glasses.link, glasses.battery, glasses.stage, builds, disk, error and sources, in that order, each with
  `label`, `text`, `band` (normal · attention · alarm), `since`, `asOf`, `source` (pc · phone), `state` (live · stale
  · lastKnown), `doing` (what the system does about it) and, where they apply, `reason`, `tag` and `alert`. Served on
  the app lane as `desc/health` at level view (`started`, `worst {id, band}`, `summary {line}` — "All well" or the
  worst line's "<label>: <text>" — and `lines`), described again only when a line changes; it lives on the core
  executor. At the start a PC line reads "not read yet" (`stale`) and a phone line "not reported" (`lastKnown`).
- **The PC's readers** (`HealthParts`): on the record's 2 s tick, in memory — the service (the record and its log
  open), the phone attached, the hub (`BastionWatcher.hub()`: not configured is attention; off for another reason, a
  refused token or `haSilentMin` with no answer is alarm, tag `hub`), the nightly copy (its last listing, a run, the
  catch-up and `watcher.copy`; a copy older than `NightlyCopy.STALE_MS` with nothing coming to replace it is alarm,
  tag `copy`) and the last ERROR line (a `Log.Sink` seeded from `Log.recent()`). Off the executor: the data folder's
  disk every minute, the memory cap (`claude-work`'s `memory.max` and `memory.swap.max`; no limit or swap allowed is
  attention) every 10 minutes, the copy folder's listing at the start and at each run's end, the windows' sources
  (Main's cards and Reader's shelf) when Main's cards move (`AppNet.addCardListener`). Once: the PC's build stamp. A
  read that throws leaves its own line `stale` with the words, said once per run of failures; the other lines go on.
- **The phone's lines** (`PhoneHealth`, `core/…/app/PhoneHealth.kt`, one per service in `ShellService`): reach and the
  three glasses lines, read every 15 s and at each `welcome`, `desc/health` and change of reach, from volatile fields
  only. Reach: refused is alarm, tag `PC no`; out of reach (`AppClient.Reach.Down`) alarm, tag `PC`, its alert the
  phone's "Home unreachable" (`AlertDelivery.HOME_THREAD`); a window whose contract differs attention; the text
  carries the waiting changes and the APK's build. The glasses' link: `ok` and silent are normal, any other word
  attention (never a tag or a notice); the battery attention below `BatteryLatch.LOW_PCT`; the stage attention only
  while a stage is held back. Sent as `health {lines}` (`AppWire.health`) on a change and at every new connection,
  again at each pass until a send on the live connection lands. `AppNet` takes it only from the phone's credential
  (`Peer.PHONE`) into `HealthCore.phone`; only the end of the connection whose `health` was taken last (`healthConn`)
  leaves the four lines `lastKnown`, "phone not connected since HH:MM". The phone subscribes `desc/health` while a
  `LaneWatcher` is set (`WorkingCopy.subs`); its snapshot reaches `PhoneHealth` through
  `AppClient.Listener.described`.
- **The glasses' link cell** (`Chrome.paintLink`; `HostTag` in `core/…/shell/Status.kt`): one dim mark while all is
  well, else `Shell.hostTag` — set only by the phone's `ShellService`, on a change, from `PhoneHealth`'s first alarm
  line with a tag in the order reach, hub, disk, backup. The tags: `PC <age>` (the PC out of reach), `PC no` (it
  refuses the phone: its credential, no lane this build speaks, or its record not open, `AppWire.END_NOT_OPEN`),
  `hub <age>`, `copy <age>`, `disk NN%` (two digits; a full disk reads 99). The shell composes the age at each chrome
  build from whole wall-clock minutes (`<1m`, `59m`, `23h`, `9d`). A new tag is carried by the 5 s idle tick's chrome
  flush, never a gesture's first, and the same tag again costs nothing (`HostTagFlushTest`, simulator). A tag is drawn
  whole or as its first word (`HostTag.short`), never cut: whole beside the poor-signal dBm figure, else short beside
  it, else whole and then short with the figure not drawn (the bars' colour still says a poor signal); one that fits
  none takes the counted fit path (`Chrome.hostFitRuns`), which `HostTagFitTest` finds unused at the four faces, every
  chrome size, good and poor signal, on the desktop's rasterizer — the phone's is not measured. The PC-direct shell
  shows no tag.
- **The phone app**: `ShellService.displayStatus()` appends the worst line's sentence ("<label>: <text> since <time> —
  <doing>") while one is not normal; it is the app's status line and the ongoing notification's text
  (`buildNotification`, `BigTextStyle`, never cut to one line).
- **The Health page** (`web/js/views/health.mjs`, `/w/health`, level view; "Health" in the rail with the `pulse` icon,
  where Diagnosis was): the page shell subscribes `desc/health` at every `welcome` beside `desc/main`. Two containers.
  The lines, drawn again on `desc/health` and at a welcome: the summary in the worst band's colour, a link
  (`health:summary`) that scrolls the worst line's row to the top when that band is not normal; then a row per line in
  the record's order — label, the band word when not normal, the reading, "since HH:MM" ("(the service's start)" when
  it has held since then), `doing`, and "last known: …" or "not current: …" with the reason when not live. The
  diagnosis below, at level full only, drawn again on `diag` and at a welcome (the PC build, epoch and seq, contracts,
  this page and device, the connections, the PC log's tail); a change of reach redraws only its head, so the log box
  keeps its scroll place and focus. "Read again" is `ctx.actionButton` (need full, `diag:again`): out of reach it is
  dim with its reason, and a press says why and sends nothing, so the last diagnosis stays. Out of reach the page keeps
  its last lines, greyed, until the next welcome replaces them. Home (`views/home.mjs`) has one line above the cards,
  the summary in its band's colour, linking to `/w/health`. `/w/diag` says the PC serves no such window; the `diag`
  scope stays (`PcScopes`).
- **The notice rule** (`HealthCore`, on the record's tick): a line in alarm for `NOTICE_AFTER_MS` (5 minutes, his
  answer) with no `alert` of its own raises one notice — source `health`, thread the line's id, "<label>: <text> since
  <time>. <Doing>." — which the shell shows only while `Notify · Damage` is on (`gatedBy = Notifications.GLOBAL`;
  `HealthNoticeGateTest`). Its episode ends when the line leaves alarm; the PC withdraws nothing, the surfaces show
  the line's return. A line that carries an alert is said by that alert. As built the rule serves the disk, an old
  copy with nothing coming to replace it, and a record that did not open at the start.
- **The disk** (`HealthParts.disk`, the data folder's file store, read every minute): alarm when less than
  `DISK_ALARM_FREE_PCT` (2 %) is free, with the tag `disk NN%` (the used share rounded down); attention when less than
  `DISK_ATTENTION_FREE_PCT` (5 %); both strict, his answer. The text rounds the free share down to a tenth, so a value
  just under a limit never reads as the limit. Nothing is cleaned automatically.

## Feed + comics (2026-09-09, FEED.md — one engine, on the PC since S1-3)

Reddit popular, Slashdot, xkcd, SMBC, the 8-Bit Theater archive; `FEED.md` §8 is the record. `core/…/windows/feed/`:
`FeedModel` · `FeedHttp` (`RealFeedHttp`, `PacedHttp` — one request per minute per Reddit host, a second
elsewhere, `Retry-After` honoured, `RateLimited`) · `FeedXml` · `Fetchers` (`RedditAtom`, `SlashdotRss`,
`XkcdFetcher`, `SmbcFetcher`, `RssFetcher`, `EightBit`) · `Extract` (jsoup) · `Strips` (fit, the §3.4 decision,
16/8/4 levels, packed 4bpp) · `FeedStore` · `FeedEngine` (the engine = the local provider; `comicAt`) ·
`FeedProvider` · `FeedNet` (`FeedService`, `RemoteFeedProvider`) · `FeedGlasses` (`FeedWindow(…)` the factory; nine
levels, the comic canvas, the settings; "The shared window core" above) · `ScriptedFeed`. Desktop: `FeedCheck`
(`--feed-check [DIR] [live]`), `feedChecks` in `SelfCheck`, `feedScenes` in `Snapshot`. Tests: `FeedTest`,
`FeedWindowTest`, `FeedNetTest`, `FeedStripsTest`; fixtures `core/src/test/resources/feed/`.

- **`FeedEngine` runs on the PC only**: `Config.feedEngine` (files under `~/.damage/feed`), serving the phone through
  `FeedService` on the window channel (articles without spans) and the pages through `FeedScopes`. The phone has no
  engine (`CONTRACT.md` §14 (a) row 1).
- **Reading state is the PC record's** (`rec.feed.…`, above); the local blob keeps the view's own state only. Harness
  accessors: `rootRowId()`, `levelName`, `itemsLoaded()`, `comicFocusLabel()`.
- **Slashdot**: the story page's rendered thread (`SlashdotRss.parseThread`) plus `POST ajax.pl op=comments_fetch`
  for the ids it lists but does not render (`fetchMissing`) — the one POST in the engine.
- **Config:** `feedSources` (the day-one five when absent), `feedUserAgent`, `feedDataDir`. No credentials.
- ⚠ **Comics are the heaviest thing the shell ships** (`FEED.md` §2.6, modeled): an xkcd first screen 5–13 KB, an
  8-Bit Theater page 30–54 KB at 16 levels (17 KB at 4). On-glass numbers owed (§8.1).

## Music (2026-09-01/02, MUSIC.md · DESIGN.md §4.9) — the phone plays, the PC serves (as built; from step S1 the PC holds the queue and one output at a time plays, the PC's own included — `REPLAN.md` §14.2, §14.3 answer 4)

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
  playlist is Cancel-first with the unrecoverable row at index 2; Save over is asked twice. Global holds
  **Phone notifications** (off).
- 🔴 **Music Mode's bands hold what they draw** (§27.2): `paintExclusive` is the one place a window declares its
  own damage rects, so every band is sized from MEASURED ink, never a guessed offset.
- **At ladder stage 5** the progress bar and Music Mode's Scope are held lanes the glasses play (`MUSIC.md` §14.2;
  "The phone's halves of C1–C3" below).
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
- **`TorrentsGlasses`** over `TorrentsCore` (`TorrentsWindow(…)` the factory; "The shared window core" above) —
  TRANSFERS (six filters incl. **seeding < 1 week**; the cursor follows row identity) →
  the transfer MENU (… Delete + files behind a double confirm, the unrecoverable row at index 2) → DETAILS · the
  wrap-end MENU (Browse · Search via the keyboard · …) → CATEGORIES → LISTING → TORRENT → the add MENU.
  Notifications `torrent · done/error`, raised on the PC, deep-link to `t:<hash>`, gated by Settings → Torrents;
  `open("t:<hash>")` / `open("tl:<fid>")`. Face Fira Sans; icon theme `qbittorrent`.
- **`KeyboardSurface`** (`shell/`, `DESIGN.md` §4.8) — row-then-key with wrap on both axes, stay-in-row after
  typing, QWERTY/abc from Settings → Global → Keyboard, the symbol layer, Shift once/lock, caret editing, a
  panning text line that marks its cut, up to two requester rows of LIVE keys, the draft handed back on cancel;
  the shell cancels it under the wheel / silent / relayout / an emergency (draft kept) and commits a
  replica-typed line through it. Requesters: Torrents Search, Tmux "Type…", Files Rename / New folder.
- **Harnesses**: `ScriptedTorrents` (ink budgets: transfers 9.0 %, details 6.4 %; snapshot scenes 15–22);
  `TorrentsTest` ×9, `KeyboardTest` ×22.

## Tmux (2026-08-31, TMUX.md — all refinery verdicts locked, built in one pass)

The first CANVAS window: a viewer/controller of the REAL tmux server. The glasses use discrete commands (`=session:`
exact targeting, no `-C` attach); the desk's terminal attaches one `tmux -C` client per viewed session (`TmuxStream`,
"The shared window core" above). `wm.damage.core.windows.tmux`; `TMUX.md` is the record:

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
  (phone) reconnects keeper-style and surfaces `PC unreachable since H:MM`; alerts are the PC's, raised into the
  notice record (`TmuxCore`, above). Config (quick keys/snippets/patterns) is
  served WITH the session list — `~/.damage/config.json` on the PC is the one tuning point.
- **`TmuxGlasses`** over `TmuxCore` (`TmuxWindow(…)` the factory) — SESSIONS → LIVE (canvas; **scroll-up IS scrollback**) → HISTORY → KEYS → SNIPPETS / WINDOWS
  (targets `=s:idx`, never `select-window`) / SESSION_ACTIONS (mute · Fit pane 64×22 · select · rename · the
  `kill-session` confirm). **Typed text always stages a TYPE_CONFIRM**; a quiet host says so on every level and
  in Main's row (§30); the alert's tap opens the session (§29). Settings → Tmux: Context rows · Alerts · Update
  · Size (default 480).
- **Shell additions**: `CanvasView.onScroll/onTap`, `DamageWindow.onTypedText` + LOUD refusal when nothing
  accepts, `Transport.injectText` → `TransportEvent.Text` across every transport and the seam (`"typed"`); entry
  points: the desktop preview (key T), the browser replica's text bar, the phone strip's `type` button.
- **Harnesses**: `ScriptedTmux` (the tmux selfcheck checks, snapshot scenes 09b–09e); `TmuxTest.kt` (with
  `TmuxScrollbackTapTest`).

On-glass verdicts still owed (`REMINDER.md` → Suspended work): the flow view's default size/wrap feel,
quick-key order, alert-pattern tuning against real Claude sessions, ssh-host latency feel.

## User typography + per-app depth (2026-08-31 evening, Adam's ask — a §Type reversal)

`core/text/Style.kt`: **Global → Font / Font size / Font style** restyle CHROME AND MAIN (a recorded reversal
of §Type's "the system face is not negotiable" — his call), and **every app category gets Font / Font size /
Font style / Depth** rows; each option previews IN ITS OWN font while cycling (`HostSetting.optionFont`,
`raw=true`). One mechanism: a `StyleTransform` rewrites every `FontSpec` at the rasterizer seam — chrome draws
through a `StyledText` wrapper with the global transform, every window routes measuring/drawing through its
own per-app transform (`DamageWindow.styleTransform` → `styledText()`), so wrap and render agree by
construction. Content scaling lives here, not in the platform adapters (their `contentScaleProvider` stays at
1.0 — double-scaling is the hazard). At every default the feature is render-neutral (snapshot-verified
byte-identical below the clock). `StyleTest` ×5 pins the edges. **Depth** is the §41.2
ladder (`DESIGN.md` §3): the Global row moves everything with the selection bar one notch nearer; the per-app
`Depth` row (default `global`) moves only that app's content.

## The texture cache (2026-08-30, CFW `a5d1c31`)

The firmware's **64 KiB lease-scoped texture cache** and three draw modes (on contract 2 the cache is sized by op 5,
160 KiB a session, and modes 17–19 draw and write it — `FIRMWARE.md` §4; on a C1 build or later the ladder's stage 2
and up asks 256 KiB, `CfwModes.CACHE2_MAX_RIDERS`, and what lies above the atlas there is `CfwModes.reserveAbove` —
the section "The phone's halves of C1–C3"); the compositor uses it behind the Global `Cached text` row.

- `wire/CfwModes.kt` — `cleanup()` (11), `cacheUpdate()` (12), `drawImage()` (13), `drawCachedText()` (14),
  `options()`, `xAdjust()`; every builder refuses what the firmware would reject in silence (mode 13's exact
  7-byte body, mode 14's `8 + strlen`, byte 0 and bytes > 127, writes past 64 KiB, an adjust outside −10…+20).
  `batch()` accepts sub-modes 3/6/9/13/14/15.
- `wire/TextureCache.kt` — the atlas layout: image encoding (`[w][h][RLE of exactly w*h pixels]`, no row pad), a
  deduplicating offset allocator, 96-entry font tables, chunked mode-12 messages. Two invariants: **offsets 0–1
  stay zero** (an unfilled table entry points at a guaranteed-rejected image) and **every table entry is
  filled** (the firmware validates a whole string before drawing any of it).
- `gfx/Codec.kt` — `Rle.encodeLevels`/`decodeLevels` over a bare pixel run (`CodecTest.rleMatchesPythonReference` still holds).
- `sim/GlassFirmwareSim.kt` — modes 11/12/13/14 modeled byte-exactly (the lease gate, whole-list validation, the
  LUT with its integer truncation, transparency tested pre-LUT, per-glyph advance by image width). Mode 15 is
  **refused loudly**. The cache is dropped on lease expiry and on mode 11. `TextureCacheTest` + `AckStatusTest`: 52.
- The sim attaches no `EventSource` to long-press events (`EvenHubMsg.reportsSource`); the capability gate runs
  against the real `EVENCFW/16` string (`REQUIRED_CAPS` is five, version checks through
  `SettingsMsg.contractVersion`).

**Full-cache eviction (`HANDOFF.md` §47).** A face that does not fit evicts every resident face not
drawn in the last 12 frames (`Shell.ATLAS_RECENT_FRAMES`) when together they free its price; `GlyphAtlas.repack`
rebuilds the packed bytes from the survivors (heaviest first) and the recently drawn icons and resets the upload
watermarks, so the glasses are rewritten from the guard up with every font off the live set until the last chunk
is acked — the lapse path's discipline. Paced 45 s (`ATLAS_REPACK_PACE_MS`), never with a chunk in flight; a
refused face books a retry a window of frames ahead; a stale face never re-enters over a recent one. An
`atlas` journal note says `full` only when a face is still pixels after all that (no box, no cell word: §69). `AtlasRepackTest` pins it with belief
= glass throughout.

**Adopted (`HANDOFF.md` §40.6).** `core/comp/CachedText.kt`: `GlyphAtlas` renders the glyphs a face has drawn
(below) through the host's rasterizer and packs them with `TextureCache.Builder`; `CachedText` wraps every host's
rasterizer, blits strings from those same images (the firmware's TRANSPARENT LUT draw) and records them per
frame; the compositor ships a rect as one clear plus mode-14 draws only when black plus the records equals the
composed pixels byte for byte. The upload (`DisplayOp.CacheWrite`, one ≤ 3 KB chunk per idle pump after the
keyframe) is `Shell.pumpAtlas`; fonts go live on the batch's last ack; a lease lapse forgets the upload.

**Kept across a rebuild (`HANDOFF.md` §54 — Adam's bounded skip, §51.8).** A session start keeps the atlas
when the glasses still hold it. `CfwTransportBase` keeps a per-arm lease log
(the newest `LEASE_LOG_DEPTH` = 8 ACQUIRE writes taken as arrived — never pruned by age; whether a RELEASE went
out since): a write of the last `LINK_SETTLE_MS` (10 s) before a link end is struck (the platform's write callback means its stack took
the packet, the last exchange can predate a supervision timeout by 5 s, and a lease write can queue behind
a flush on the same arm), a release is never struck. At the session's first ACQUIRE the transport decides
whether both arms were inside `LEASE_CARRY_WINDOW_MS` (the firmware's 87.9 s expiry less a 10 s margin) and puts the answer and
the per-arm gaps in `LinkState.leaseCarried` / `leaseCarry` (over the seam too). The start reads RIGHT's uptime
before the lease (`noteUptime`, §59; a reading one wrap of the 32-bit tick counter on from the prediction, ±10 s, is
the wrap — a `keeper` note, no reset; the counter wraps about 2026-12-06), a reboot since that arm's last acquire
refuses the carry ("R reset N s ago"), and so
does a LEFT supervision timeout (a reboot there cannot be read) or a RIGHT one on a build without telemetry. `Shell.atlasAtSessionStart`
keeps the atlas when the transport says so, cached text is on, the session is not adopted and the last
cache write through the transport was this shell's (`FlushRequest.writer` = the shell's tag →
`LinkState.cacheWriter`): the acked fonts are live from the first compose, the chunk in flight at the link
end goes again (`GlyphAtlas.rewindToAcked`), the keyframe follows. Every other case resets. An `atlas` note
either way ("kept across the rebuild (L gap 12.3 s, R gap 12.3 s): …" / "reset — …"); `journal_report.py`
lists them under "atlas at session start". A chunk whose ack a link end took is not a refusal
(`atlasDone`): it goes again from the acked mark, at most `ATLAS_UNDELIVERED_LIMIT` (3) times in a row before
cached text is switched off with its own note. `AtlasCarryTest` pins all of it against the model.

**Every plane, and icons (§41).** On contract 1 (on contract 2 `Compositor.emitCachedV2` places each draw per
lens with modes 17/18 — no widening, no `CopyPair`) cached draws are flat in the firmware, so a rect on a depth
plane ships as a BASE delta over the rect widened by the disparity (the composed pixels with every pixel a draw writes
black), the draws at nominal x, and one
per-lens mode-9 copy (`DisplayOp.CopyPair` → `CfwModes.copyStereo`) that slides each lens's copy to its own x;
`Compositor.emitCached` builds the firmware's result per lens and compares it with that lens's truth before
anything ships — else pixels, the reason counted into `cacheMiss`; an inked rect is also priced against
its pixels (`dearer`). `CachedText` records draws into a slide's
temp through a relay (`via`/`viaInto`), moves records with a translation (`moveRecords`) — and a shell painter that
fills a region and paints it again drops the frame's records under it first (`Shell.retire`: the menu,
keyboard, wheel, notice box, content, lens, exclusive and silent paints, every under-snapshot restore) — and draws a string
with a character its face's entry does not pack (§73: `GlyphAtlas.Entry.packs`, positions from
`CachedText.layout`) as its cached RUNS plus the host's character. Icons cross `IconPaint.blit`
(`CachedText` is the `IconRecorder`; mode-13 draws). Fonts pack heaviest first (`usageOf`); the atlas keeps an
ACKED watermark; the keyframe seeds the screen plane only (`Compositor.seedFrame`).

**Packed with what a face draws; grows in place (`HANDOFF.md` §73).** A face goes into the atlas
with the characters it has drawn — `CachedText.codesFor`: its own, all ten digits once it drew one, the space,
every capital once it reads as running text — and every other table entry names one shared tofu record; a face
packed with exactly what it drew is ≈3 KB where its whole Latin-1 table is 16 (M, AWT; the digits, space and
capitals the rule adds were not priced on that walk — `CLAIMS.md`). What follows from that:
- **A code the entry does not pack is the host's.** The recorder splits a string on `GlyphAtlas.Entry.packs`
  (never on the contract's range), the run after a host-drawn character records its first columns
  (`TextDraw.keep`: the base is black only where a draw writes, and the columns are read by the price,
  `Compositor.oldBox`, not by the base), and both emit paths refuse a run the entry in force does not pack (`glyph-not-packed`).
  Nothing downstream can tell a packed code from the tofu: these three and `Entry`'s constructor are the guards.
- **`GlyphAtlas.extend`** gives a resident face more glyphs: records appended at the end of the packed bytes,
  the face's table entries rewritten in place (`TextureCache.Builder.extendFont`), normally one cache-write
  message; ONE extension per face at a time; the entry the recorder reads changes at **`promote`** (on the
  loop, after the ack and, where the build can answer, a read-back) — and at once in `extend` when the new
  glyphs are byte-identical to records its table already names (nothing is sent). A lapse folds pending extensions in place, a repack folds them before it
  rebuilds, a rewind resets them and starts a new upload generation.
- **The read-back gate is keyed on bytes AND on `GlyphAtlas.ackedMsgs`** (a patch-only extension moves no byte
  mark); every path that puts NEWLY acked fonts live (the last chunk's ack, `atlasGrow`'s held path) goes
  through `atlasCheckThenLive`; `atlasKeep` lights what a carried session already held with no read-back, by
  design (§54).
- **A full cache:** a face in use whose new glyphs do not fit evicts the stale faces as a new face does
  (`atlasEvictFor`, same pace); `pumpAtlas` looks once per frame for a face in use that the atlas does not
  serve — absent, or with a refused extension — and books its retry (nothing else cues a face that draws no new
  character); a face nobody is drawing is not packed inside the pace window after a repack nor into the last
  `ATLAS_GROW_HEADROOM`. While the lease is gone `pumpAtlas` neither grows nor sends.
- **`CachedText.cacheMetric`**: with the Cached text row on, `measure` is the cache's per-glyph metric whether
  or not the face is live, set before the first layout of a session and re-derived (every window's
  `onFontScaleChanged`, the chrome's cells) when the row changes. A measure that followed the live state cut
  lines wrapped before their face was uploaded.
- **`CachedText.layout` is the ONE walk `measure` and `draw` share**: each code a run may hold at its own
  advance with the pair kern before it, everything else as a whole unit the host measures (a surrogate pair, a
  letter with its marks). `draw` places cached runs AND host-drawn units at its positions — a face that is not
  live is drawn unit by unit while the metric is on — so a string is as wide as it measures in every state and
  nothing moves when a face goes live. A run before or after a host-drawn unit keeps its edge columns as
  composed in the base (`TextDraw.keep` / `keepEnd`). The contract the walk lays out for is
  `CachedText.metricV2`, set in `Shell.atlasEnable` with the atlas's and re-derived there when it changes.
- **A strip waits for its glyphs** (`pumpStage`): a strip whose render moved the atlas's cues is rendered again
  after the atlas has had its turn, never written with the host's pixels for characters about to be promoted.
Do not split on the contract's range again, do not promote off the loop, do not key the gate on bytes alone,
do not let `measure` depend on what is live, and do not give `measure` and `draw` separate walks.
`GlyphSubsetTest` (its `KernedText` carries the layout walk's pins), `GlyphSubsetShellTest`, `AtlasRepackTest`,
`StagingTest`.

## Page staging (2026-09-16, `HANDOFF.md` §66 — contract 2 only)

The focused document's next and previous strips as v2 image records in the cache's reserve, a notch as one draw.
- `shell/Staging.kt` — `StageKey` (the layout object + lines + line box + width), `StagedRecord` (RAW pixels for the
  paint and the proof, the encoded record, WANTED → SENT → ACKED → PROVEN), `StageRing` (the reserve as a ring: a bump
  pointer, eviction of what a placement overlaps unless it is pinned), `Staging` (the registry), `CacheLedger` (the
  proof by counting telemetry field 17 against the acked non-empty writes; Base / Proven / Short / Shortfall / Foreign).
- `Shell` "page staging": `pumpStage` runs on the idle pump after `pumpAtlas` — a reading when one is owed, else one
  strip rendered through the recorder's relay with nothing recorded (`CachedText.via` with an empty keep), placed in
  the ring and written in pieces of up to 3,064 B, one mode-19 message each (`Shell.STAGE_CHUNK`; `STAGE` flushes, the
  atlas's writer tag); `stageDone` feeds the ledger; `launchLedgerRead` (op 1; `pumpLedger` is the count's one reader,
  for atlas writes and strips alike) and `ledgerVerdict` prove or drop; `stagePlacements` before every assemble;
  `paintDocSlice` blits a PROVEN record's lines. Both write lanes hold while a reading is on its way; a short reading
  inside `CacheLedger.SETTLE_MS` (2 s) of the last ack is pending, re-read paced 500 ms, and only two settled short
  readings make a shortfall (§66.1: bumps land up to ~1 s after the ack); an extra bump re-bases, drops nothing; a
  shortfall costs staging only, never the atlas (its own read-back judges it). Clears:
  `stageSessionStart`, `atlasReset`/`atlasLapsed`/`atlasDisable`/`atlasLive`, Silent Mode, the row off, `stage.retain`
  on a relayout. Bounded: `STAGE_FAILURES_MAX` = 3, then off for the session (a `stage` note).
- `Compositor.emitStaged` — before the cached path in `emitDelta`, no fid: the planned rect's overlap with a placement
  ships as a clip pair + one mode-17 pair + a clip reset when each lens's truth equals the record there (quantised);
  the shadows take the truth; a partly covered rect leaves the rest to the next planner pass.
- `LinkState.atlasCapacity` = the cache less `CfwModes.STAGE_RESERVE` on DRAW2 (on a 256 KiB session with BIND in
  force the reserve is 32 KiB, `CfwModes.stageReserve`, with Main's region below it); the read-back expects the
  glasses' cache to be the atlas plus the reserve. `Transport.telemetry()` = op 1. `ShellSettings.pageStaging`, the Global row `Page
  staging`, the journal's `staged` field and `stage` notes, `journal_report.py`'s page-staging line.
- Gates: `StagingTest` (among them §73's — the two re-read paces on a paced link, a stopped shell, a strip after its
  glyphs), `StagePiecesTest`, the contract-2 oracle walk (the document keyed), `--selfcheck`'s contract-2 pass (a
  Reader notch that shipped a staged draw, counted where the flush is assembled).

## The fork's Phase 0 pieces (`HANDOFF.md` §49, `FORK.md`)

Damage stays clean-room: it holds the contract (`FIRMWARE.md`), data and its own implementation, never the
fork's C (`~/damage-cfw`).

- **Probes** — `Transport.devProbe(name, value)`, reached only from the replica port's token-gated
  `{"t":"probe","name","value"}` (`ReplicaServer`, the phone's `ShellService`, the desktop's `Main`) and sent by
  `tools/glassdrive.py probe:NAME=VALUE`; the shell never calls it. `CfwTransportBase`: `diag=show|hide` queues
  `CfwModes.diag(2|1)` on the image lane (no fid; draws only into the physical framebuffer, so the mirror is
  unchanged); `logger=on|off` writes `LoggerMsg.switchSet` to BOTH arms through `CtlWork.BothArms` and, while
  wanted, again after every session start (a CREATE ends the stream on the glasses); `telemetry=read` and
  `flags=clear|probe|0xNNNN` write `DamageMsg.control` to both arms. `BleTransport` adds `phy=2m|1m`
  (`setPreferredPhy`, the answer journaled). Every probe is a `probe` journal note; log lines are `glasslog`
  notes; a Damage build's telemetry is a `glass` note, as is the DamageCaps line at every session start.
- **`battery` notes** — every change of the glasses' level (`emitBattery`), so a day's drain reads from
  `/journal` (`journal_report.py --since`, the battery section).
- **Conformance vectors** — `firmware/make_vectors.py` writes the inputs (the documented v1 message formats);
  the fork's `host/run_vectors.py --write` fills the expectations from its C; `ConformanceVectorTest` runs
  every file through `GlassFirmwareSim` per lens (shadow CRC-32 and every return code), C1–C3's vectors and the ship
  vectors (what the shell sends at stages 4 and 5, `ShipVectors.kt`) among them (`HANDOFF.md` §77.4, §101.3); the
  count and the last comparison are `REMINDER.md`'s.
- **`DamageMsg` + the simulator's `damageContract`** — the Phase 1 settings extension from the contract text:
  DamageCaps parsed at the capability gate, telemetry parsed on any sid-0x09 frame before the §47 answer
  matching, flags modeled with the texture cache's release points, field 4 a status register (the last
  recording op's status; TELEMETRY records nothing — `HANDOFF.md` §50). `DamageMsgTest` covers the bytes, a
  Damage build and an upstream build (which answers nothing).
- **`research/fwread.py`** — the stock image at instruction level (disassembly, the corpus decompile, stored
  words, literal references, call sites, strings); `research/fork-reads-2026-09-13.md` the notes it produced.

## The fork's Phase 1 pieces (`HANDOFF.md` §51, `FIRMWARE.md` §3; flashed 2026-09-15)

- **`DamageMsg`** — op 4 CACHE_INFO, flags bit 0 PRESENTED / bit 1 CACHE_KEEP / bit 15 PROBE
  (`FLAGS_IMPLEMENTED`), features 0x1f, telemetry fields 15–22 named in `describe()`, field 113
  `Presented` parsed; **`CfwModes.selfTestBegin/Step/End`** build image mode 16 (a step carries any shadow
  message, `SELF_TEST_MODES`).
- **`GlassFirmwareSim`** — `present()` counts direct presents and, under PRESENTED, notifies from RIGHT
  only; `leaseEnded` / `leaseFreshAcquire` at every release point (tick expiry, a lapse noticed, FB_RELEASE,
  the fresh acquire) with the CACHE_KEEP latch and the once-per-lease `lapseSettled` marker; mode 12 bumps
  `cacheGen`; mode 16 swaps the lens's `shadow` for the scratch and its fid diagnostics for `stDiag` around
  the dispatch; `damageControl` answers from RIGHT only; `uptimeOffsetMs` models a reset.
- **`CfwTransportBase`** — `TransportEvent.Presented` from field 113; `telemetryWaiters` keyed by request
  id, failed by the sweep; probes `cache=info`, `selftest=begin|end|step:HEX`; `wantedFlags` set by
  `flags=`; **`armFeatures`** after every start on a Damage build (read RIGHT's uptime → the hold-back rule
  → FLAGS_SET one bit at a time, each answer checked; `keeper` notes, `holdback`/`flags` faults);
  `HOLD_BACK_MS` = 120 s placeholder. A reset is a reading that falls short of what the last reading
  plus the phone time since predicts (`RESET_SLACK_MS` = 10 s; the readings alone missed a reset after a
  short uptime — `HANDOFF.md` §52); a bit the build refuses as unsupported is dropped from the wish with
  one fault and the bits above it are still armed. The FLAGS_SET waiter (request id 0) is removed only while
  it is still the current one, so a previous session's wait ending late cannot drop the new session's
  (`HANDOFF.md` §53). `RemoteTransport` carries `present` across the seam.
- **`Journal.present`** — `{"ev":"present","seq","workerUs","copyUs","transferUs"}`, one per transfer while
  armed; `journal_report.py` prints the distribution; `tools/glassdrive.py selftest:FILE` drives a vector
  through the glasses' self-test and compares RIGHT's report from `/log`.
- Tests: `DamageMsgTest` (among them §52's reset seen past a grown uptime, the dropped unsupported bit and the
  truncated self-test step, and §53's FLAGS_SET with no lease and FB_RELEASE after a settled lapse),
  `ConformanceVectorTest` (the normal path and the self-test form over the drawing vectors, the v1/v2 pairs at C1, C2
  and C3, the C1–C3 comparisons, the ship vectors), `DevProbeTest` (4). The fork's side: `host/test_damage_ext.py`,
  `host/run_self_test.py`.

## The phone's halves of C1–C3 (`HANDOFF.md` §85, §90–§92, §98–§101)

The contract is `FIRMWARE.md` §5 (C1, the engine), §6 (C2, local input) and §7 (C3, effects); the plan is `MOTION.md`
§7; operating the ladder is `DAILY.md`. Nothing here is sent unless its ladder stage is armed on a build that reports
its DamageCaps bit (7–8 C1, 10 C2, 11 C3). C2's guards are under "Review hardening"; what 0.84 leaves open, §101.9.

- **The ladder** (`CfwTransportBase`: `ladderFlags`, `ladderKib`, `buildTop`, the probe `c1=N`): stage 0 nothing ·
  1 ZWRITE (mode 25: from it every mode-19 cache write that shrinks goes deflated, `zwriteOrList`, `FIRMWARE.md` §5.9)
  · 2 and the 256 KiB cache · 3 and ENGINE · 4 and BIND (C2) · 5 and EFFECTS (C3; RIGHT's flags 0x7c). Set only by `c1=N`, armed at the next session start, never above the build's top
  stage; a reset within `HOLD_BACK_MS` of an arming (RIGHT's uptime, or LEFT's link ending) drops stages 1–3 one stage
  and arms a stored 4 or 5 as 3 without lowering it. `transport/LadderRecord.kt` keeps the stage, the last stage-4
  arming, the case-in, the hold-back and the session's cache size in the phone's files, so an install keeps them.
  `MotionTransportTest` (each stage adds only its own change; stage 0 writes the awake start byte for byte),
  `BindTransportTest`, `LocalStartTest`.
- **`tools/ladder.py state|4|5`** (0.84): `state` reads RIGHT's newest flags from the phone's `/log` (0x3c stage 4,
  0x7c stage 5) and sends nothing; `4` or `5` sends `c1=N`, `rest=on`, `rest=off` over the phone's replica WebSocket,
  so a session start arms it. Reads `token`, `phoneHost`, `replicaPort` from the config; the system Python's
  `websockets`; no self-test. Stage 4 is the way back from 5 without a Claude session.
- **The motion DSL and its encoders** (`motion/Motion.kt`, from §5's and §7's text, never the fork's C):
  `Motion.program` builds a version-1 program (tracks, segments, eases; FILL · DRAW · REVEAL · WAIT), `program2`
  version 2 (OWN, LAND; PIECES, SHRINK, GLYPHS) only through the gate's token `Motion.effects` (EFFECTS in force);
  `play` · `playAfter` · `retarget` · `close` · `commit` encode mode 26 (`CfwModes.motion*`), `CfwModes.bind*` mode 27,
  `CfwModes.zwrite` mode 25; `Motion.end` is the END applied to the shell's belief (§5.6); `MotionProgram.cost` is
  §7.12's cost. What §5.5 would refuse offline is refused loudly (`LintError`). `MotionDslTest`, `Version2MotionTest`,
  `C3WireTest` (the encoders against `make_vectors.py`'s messages, byte for byte), `ConformanceVectorTest`.
- **The model and the mirror** (`sim/MotionSim.kt`, `BindSim.kt`, `EffectsSim.kt`, from the contract's text alone):
  one lens's engine, binding table and effect verbs inside `GlassFirmwareSim`. The phone's mirror runs them on every
  message it writes, through `sim/PixelLoops.kt`'s row-span loops (ML1: the plain loops ran 70–93 times
  slower on his phone than on the PC; `MirrorLoopsReplayTest` compares the two on the same messages); an open
  instance's areas are left out of the agreement check, as spans (`MirrorScanMotionTest`). `MotionSimTest`,
  `BindSimTest`, `EffectsSimTest`.
- **Who plays what.** Each motion has its owner in the shell's pump, on fixed slots (`TransitionRun`'s companion: 0
  the bound list, 1 the wrap's PLAY, 2 a transition's out half or the notice, 3 the in half; Music's held lanes take 0
  and 1 in turn on levels with no bound list and no wrap); the period and steps are the fit below. C1's test
  programs (`motion/TestPrograms.kt`: list, popover, recede, flip; Main's pans from `MainPan.kt`) play only by hand
  (`probe motion=NAME`, `Transport.sendMotion`, ENGINE in force; their cache write is another writer's, so the shell
  drops its atlas for that session). `TestProgramsTest`, `MotionCheckTest`.
- **Main's list staged** (C2; `shell/ListStaging.kt`, `ListBinding.kt`, `Shell.pumpListBind`): the records from Main's
  own painters — the strip (every row's row form, margin rows past each end), each row's band form, the item table,
  the programs — in Main's own 128 KiB region on a 256 KiB session with BIND, else the shared stage reserve
  (`CfwModes.mainRegion`); written as pieces of one mode-19 message (`STAGE_CHUNK`, 0.82), proven by the cache count,
  staged behind the shell's clock screen too (the base records only). `local/BoundList.kt` is the list as bound: its LIST
  and mark, one SET per event id (the two scroll entries and the press), `predict` (§6.2 steps 8–9). One holder,
  `Shell.ListBind` (Main's list, and a window's own list as `WindowListBind`). `MainStagingTest`, `MainBindTest`, `MainBindShellTest`,
  `StagePiecesTest`, `desktop --local-check` (`MainLocalCheck`).
- **The local scroll** (C2, stage 4): a ring notch fires on the glasses from its SET; RIGHT's report (notify 115,
  `TransportEvent.Applied`) is answered by the phone's rules (`local/LocalRules.kt`: COMMIT · DISCARD · SETTLE, one
  answer per bound slot per batch; `Taps` phone · report (the default) · glasses); a mode 27 that did not land is
  repaired by evidence (`TableRepair`), a refused mode 26 with more of its batch behind it by the whole table and one
  keyframe (`EngineRefusals`, 0.83). `LocalRulesTest`, `LocalSequenceFuzzTest`, `LocalSkewRigTest`,
  `MainLocalShellTest`.
- **The scroll effects and the fit** (`motion/ScrollEffects.kt`, K1-1; the `Scroll effect` row): None · Depth (the
  default) · Slide · Fall in version 1 from stage 4; Crush, Flip's width turn and Matrix in version 2 at stage 5 (below
  it Crush plays Slide, Flip its height turn and Matrix Depth, and the row says so). **Matrix** (C3 DM-2a; Main's list
  only, a window's list plays Depth) is Depth's move with noise glyphs raining down through the rows, seeded from the
  entering row's band form so both lenses draw one picture; its records (the noise glyphs, their table, the alphabet,
  the rain record: `ScrollEffects.rainRecords`) are part of Main's staging, laid last and given up first (Matrix then
  plays Depth, said), so a change into or out of it stages the list again whole, with Main in front
  (`ScrollEffects.hasRecords`, `ListStaging.remake`). A notch's period is fitted on the effect's painted area:
  C2's fit `ListStaging.timing` (P0 with a margin, inside 16..128 ticks), at stage 5 the fast DRAW path's
  `ListStaging.fastStepMs` (§7.12; four modeled figures, the fourth for Flip's scaled turn), both through `timingOf`; Auto frames take the steps
  that fit `Scroll length`, and the glasses' measured period (notify 114 field 9) raises the period. `ScrollEffectsTest`,
  `MainLocalShellTest` (Matrix's re-stagings), the `v5-dm-rain-fire` and `v5-ship-ring-matrix-352` vectors.
- **The ring list** (C3 DM-0, 0.84): at stage 5, where Main's paint wraps (his eight rows at 288 and 352), the list is
  bound with LIST-RING (`CfwModes.bindListRing`, `BoundList.ring`); the strip carries the other end's rows as margins
  and no wrap record, and the phone measures the cursor modulo the count (`BoundList.distance`). `LocalRulesTest`,
  `MainLocalShellTest`, `LocalSequenceFuzzTest`, the ship vectors.
- **The transitions** (C3; `motion/Transitions.kt`, `shell/TransitionRun.kt`; stage 5 only): Select, Close / Back
  (Select's effects reversed in time) and Lock Screen (the silent clock entered and left); Depth · Warp · Flip · Fall ·
  Glitch · Explode · Fade · Build · Encode · Matrix, one picked at random among those checked at each act. Two halves
  over one area: the out half (an OWN program, slot 2) moves the leaving picture in place; the arriving frame goes in
  as many flushes as it takes, and the last carries the in half's PLAY-AFTER (slot 3, `after` 2; OWN + LAND, drawn
  from the shadow). Every flush from the out PLAY to the end is held by both owners, so it plays in full. **The end
  rule** (TR1, 0.84): RIGHT's in-half body ends it; else the phone CLOSEs both slots at RIGHT's out-half body plus T
  periods plus `BODY_ALLOWANCE_MS` (500), or, with no out-half body, the acks' rule plus `ACKS_ALLOWANCE_MS` (750).
  Records in two 2 KiB slots above the atlas (`CfwModes.TRANSITION_SLOT`); Encode and Matrix draw from the atlas's
  fonts (`motion/CellLists.kt`) and hold every other cache write while they run (`TransitionRun.holdsCache`).
  `TransitionsTest`, `TransitionShellTest`, `CellListsTest`, `TransitionRehearsal` (`--motion-check`'s frames).
- **The notice on the engine** (`motion/PopupPrograms.kt`, K1-4a; a 256 KiB session with BIND in force): the focus
  timer as one program in slot 2 (17 steps at 128 ticks, 2.0 s) and the Fade and Slide arrivals as their own PLAYs,
  written into the top 16 KiB of the stage reserve (`CfwModes.NOTICE_REGION`, `Shell.pumpNoticeStage`) and counted by
  the ledger; tags `0xE000 + n` (0.83). Elsewhere the timer is the phone's own steps; Unfurl is always the phone's.
  `NoticeEngineTest`, `NoticeTimerTest`, `NoticeTimerRenderTest` (desktop).
- **Held lanes** (Music's motion update, stages L and S; `shell/WindowLanes.kt`, `windows/music/MusicMotion.kt`; at
  stage 5 on a 256 KiB session with BIND, `ShellServices.lanesInForce`): a window in front declares rects the glasses
  animate while it keeps painting (`DamageWindow.lanes` → `LaneSpec`: rects, a key whose change plans again, period
  and steps, the records and font its programs read, a planner). The shell plays one version-2 OWN program a stretch:
  a PLAY in slot 0, each next a PLAY-AFTER into the other slot sent with half the stretch left, its area written
  first (one deflated message where it fits) and proven — three areas of 5,440 B in turn in `CfwModes.musicRegion`,
  the stage reserve's lower 16 KiB, Page staging's strips there dropped; tags from `0xA000`. While a chain is open
  the compositor holds its cells (`Compositor.laneHold`) and the flush names slots 0 and 1 held (`FlushRequest.held`);
  every end releases them (`laneRelease`), so the next frame writes the lane from the truth and the transport CLOSEs
  what the mirror holds; no chain starts in the pump that ended one. The lens's clock is the PLAY's landing
  (`WindowLanes.flushDone`); a PLAY-AFTER landing over 100 ms late restarts the chain; three refusals in a session
  turn the lanes off. Music: the bar (`MusicMotion.barLanes`), Scope's strip alone in Music Mode (`scopeLanes`), the
  time text each second (`MusicGlasses.scheduleTimeTick`); `MpvSink` ages its place by the time since its last
  `time-pos`; `ClockSkew` journals the PC–phone wall-clock difference (`clocks:`). `MusicLaneShellTest`,
  `MusicLaneFuzzTest`, `ScopeLaneTest`, `MusicLaneVectorsTest`, `--motion-check`'s `music-scope-*` strips.
- **`Animations`** (`shell/SettingsWindow.kt`; the fields in `ShellSettings`, on the PC record): a category on a BIND
  build, one row per tier opening its sub menu (0.84): Scroll (`Scroll effect`, `Scroll length` 300 ms, `Scroll
  frames` Auto); at stage 5 Select, Close / Back, Lock Screen (450 ms, 10 frames, a checkbox per effect; Depth, Depth,
  Fade by default); Popups (`Popup length`, `Popup frames`, `Popup · Unfurl` on, `· Fade`, `· Slide`). `Taps` is in
  Global on a BIND build. `SettingsLocalRowsTest`, `TransitionsTest`.
- **The wheel** (C3 DW; stage 5 on a 256 KiB session with Main's own region; `DESIGN.md` §4.3): `motion/Wheel.kt`
  (the cells from the switcher's own painters, the rule, the item table, the drum's two programs, their fit at the
  Scroll tier's length and frames), resident at the end of Main's region (`shell/WheelResident.kt`, at most 20 KiB;
  `Shell.pumpWheel` places and writes it through the stage lane in idle, keeps it current in place while the wheel
  is closed, and Main's staging laid over it drops it). While the switcher is open and the offer holds
  (`Shell.wheelNotOffered`: the block placed, written for this open's order, geometry and timing, and counted; three
  rows or more), the block's list is bound as list 2 on a ring in slot 0 (`Shell.slot0` takes it first; entries 6
  and 7, the report-only tap entry 0) and the local rules answer its reports as Main's; otherwise the phone draws
  the wheel as below stage 5. The `Switcher` row (Drum · None; `ShellSettings.switcher`); a black margin of the
  panel's disparity either side (`Shell.switcherMargin`); the `wheel` note at each close (`journal_report.py`'s
  wheel report). `WheelCheck.kt` (`--motion-check`'s strips), `desktop --wheel-icons`. `WheelTest`,
  `WheelStage4Test`, `WheelBindTest`, `WheelResidentTest`, `WheelAndHostSettingsTest`, `--local-check`'s wheel
  cases, `LocalSequenceFuzzTest`, the `v5-ship-wheel-*` and `v5-dw-fire` vectors.

## Review hardening (rounds 2–8, 2026-08-24)

The mechanisms the reviews found load-bearing and easy to break by accident (the records: `HANDOFF.md` §25–§42 and
the sections cited):

- **Build W's (`HANDOFF.md` §79).** The guards of the bus watcher, presence and the nightly copy are the ones that
  section states (the hub only read; the watcher's own trouble never ends an alert; the store's epoch and `seq`; the
  paced re-asks; `PlaceWatch`'s confirmation and privacy), with the reach gate (the mirror section; `reachOpen` the way
  back) and `StandbyPolicy.Action` (no member starts a stack). The start that rests ("The glasses can be asleep"
  below): the pump's `carrierUp` guard and the shell's start read are one pair. `noteUptime` reads the tick counter's
  wrap as a wrap.
- **Build S1-1's (`HANDOFF.md` §80).** The record: the log line lands before the answer and
  the publish; a failed append answers `retry`; a save never covers a line it lacks; a start whose log did not open
  saves nothing (`AppHost.logReady`, `logOpen`, `LOG_CLOSED`) and opens it again on the tick; a missing record replays
  the set-aside logs first (`RecordAlerts.REPLAY`) and never overwrites a `.bad`; alerts under `record.` survive a
  watcher start; the level is checked by the PC at apply time. The phone: an op leaves only once its queue line landed;
  coalescing only into an unsent op. Reader: an entry takes another device's newer place and writes nothing back;
  `placeFrom`'s own-write branch ends a `Go there` offer; a relayout makes no op. The web: `open` and a linked place
  saved only on input (`reader.mjs` `B.byInput`); `BlobTooLarge`; `dom.mjs`'s `keepFocus`, `toastsToDrop`, `outTitle`; a
  bad request line said by its length only (`HttpWs.readRequest`); the shelf listed on a page's visit to the shelf or
  Main (`PcScopes.gate`), never on the executor or the loop. The harness: `GamesWindow(worldSeed)` pins the Games
  scenes' world; `snapdiff.py`'s live cells are declared with a selftest case each; `reader-view.test.mjs` is the
  page's view harness.
- **Build L1's (`HANDOFF.md` §84).** The record: an op's `at` is bounded by the PC's clock
  (`AppHost`, `minOf(op.at, clock())`); a removal is written `gone` with its `by` and `at`, so an older edit does not
  bring it back; the phone folds its waiting ops by `Windows.RULES` before the merge rule, as the PC does
  (`WorkingCopy.fold` / `shown`), and lets go of an op its state already shows (`WorkingCopy.settle`); Reader's place
  keeps its input time beside it (`"at"` in `window.reader.book.<id>`) and a restored place without one counts as
  oldest; Files' glasses take an own-device row unless this presenter wrote it since it started (`FilesGlasses`'s
  `wrote`); Reader's glasses take an own bookmark when the view holds none for that book; the lane serves the
  subscriptions it refused while the log was closed (`AppNet` `closedSubs`). Decoders read 0.67's `record.json`,
  `oplog.jsonl`, `copy.json` and `queue.jsonl` (each pinned on a 0.67-shaped fixture). Files (full protocol, each guard
  pinned and watched to fail): the supervisor never ends a writing job (`FilesJobs.endAfter` set only for stats and an
  unbegun upload ticket); the ticket is cancelled with its job by the same atomic remove the PUT takes it with
  (`Uploads.cancel`); a thread per job; a stats caller waits on the job's end (`SlottedFiles.du` through `start`);
  an upload's bytes are its progress (`Ticket.onBytes`); an upload is placed by a link that refuses an existing name
  (`Uploads.placeNoReplace`; "cannot tell" = taken); the trash writes its row (forced) before the move, and a failed
  move takes it away; `trashList` marks a row `missing` and restore refuses it; an unreadable manifest refuses trash,
  restore and delete forever and is never overwritten; the trash folder is refused to every other writer, by a
  normalized path; `moveEntry` across volumes copies to `.damage-move-<ms>-<n>.part`, forces it, places it with the
  same link and only then deletes the source, deleting only what it made; a folder copy makes its destination itself
  (`Files.createDirectory`) before the work; `Files.notExists` wherever a stat that cannot tell would read as absent on
  a path that writes; the upload's socket keepalive 60 / 15 / 4. Access: `Access.ownBrowser`'s three conditions (each
  pinned by a variant that drops it); an unreadable or undecodable `devices.json` set aside with a date, never a state
  that waits on a restart. The watcher: the token file read again once a minute while unusable or refused, a refused
  token never sent again (`HaReader.sends`), the silent clock restarted when polling resumes, a late answer of a
  replaced reader never latches the new one; a stalled nightly copy raises its alert. The phone's channels: a quick
  request with no answer for 60 s drops the connection (`RemoteWin.request(quick = true)`). The pages: a `proto` end
  reloads, two unanswered pings remake the socket (`Conn.drop`), the reach line names the day; a `retry` said in the
  page's words (`proto.mjs` `pageWords`); a register value drawn until `seen` is dropped when the record shows it or a
  later input (`files-model.mjs` `settled`); a PUT ended before its body began remakes the connection
  (`views/files.mjs` `letGo`); a place op stamped with his input's time only, and one answered `ended` sent again at
  the next welcome; `BundleTest` pins the policy (only `style-src` carries `unsafe-inline`) and that the pages'
  `PROTO` equals `AppWire.PROTO`.
- **R-b's (the section "The health view").** The link cell's tag: a fixed vocabulary drawn whole or as its first word,
  never cut (`HostTagFitTest`; a tag that fits neither is counted in `Chrome.hostFitRuns`); the phone writes
  `Shell.hostTag` on a change only and the shell composes the age at the chrome build, so a change of tag rides the
  idle tick's chrome flush, never a gesture's first (`HostTagFlushTest`, simulator).
- **APK 0.64's (`HANDOFF.md` §76).** The compositor: the base's rule is made by the
  reference walk (`hole`); `settled` in the plan loop; one proof per grown box per assemble (`failedProofs`); a
  batch's sub-messages are budgeted, 254 given out and one kept for the hint (`Compositor.subsLeft`; `batch-count`).
  The shell: `Shell.retire` before any painter fills a region of `composed` it paints again (a new painter that
  fills over content painted in the same frame calls it); nothing splits a canvas's fill while a menu, the
  keyboard, the wheel or a furling notice floats (`Shell.surfaceFloats`). The cache count: one reader
  (`pumpLedger`); the base before the first cache write; the reading's epoch checked before its flag (the
  read-back's too); nothing asked of the cache asleep; no strip staged while a re-send is unjudged; an unanswered
  reading paced 2 s; four notice classes, `cache/short`, `cache/lost`, `cache/staging`, `cache/off` (source
  `DAMAGE · cache`). The transport and the sleep: the router names what it has no handler for once per session;
  the low-battery latch is judged at a reading's arrival and never replayed; a held level is dropped behind a live
  one; the seam client forgets a session's Silent Mode and wear state at its end; a probe
  runs only in the session it was taken in; the lane drops an ACQUIRE once the lease is not wanted; a shell that
  adopts its session back without a lease rebuilds it; the loop's drain answers a queued `Run`.
- **What a shell holds while down (§72).** A window push whose newest instance supersedes the older
  ones goes through the KEYED `runOnShell(key, …)`: while no session is live only the latest per key is held,
  posted under `heldLock` just before the loop launches. A transport event with no session running is decided by
  kind in `Shell.keepWhileDown`: `FlushDone` must stay queued (the in-flight, atlas and staging bookkeeping) and
  `Text` too. `holdPushes()` belongs at EVERY `running = false`. The Reader's restore and scan are one job each. Do
  not drop `FlushDone`, do not post a held push outside the lock, do not add a plain `runOnShell` to a listener that
  keeps pushing. Music Mode's visualizer frame tick is keyed (`music.vizframe`). The PC's `auto` never claims
  ("Phone-primary").

- **The latency build (§40)** — chrome's telemetry cells repaint only when `allowTelemetry` (`Chrome.sync`); a
  notch's first flush is the translation — the lens repaint is posted one message on
  (`Shell.paintOptimisticLens`) and a strip past `Slide.SPLIT_FILL_PX` goes out blank and is filled on the next
  pump (`Slide.fillDeferred`, `Shell.applyCanvasFill`); `Compositor.partition` merges the globally cheapest
  within-owner pair first (proportional shares starved a plane); `CfwTransportBase.watchdogTick` rebuilds a
  quiet session — never while the page traffic sleeps (`pageTrafficWanted`: Silent Mode, a refusal-streak sleep, a
  rest) — and the connect prelude re-asks every 2 s; `Compositor.emitCached` ships text as mode-14 draws
  only under a byte-exact proof in LENS space, on every plane, with a per-lens copy behind the flat draw. Do not
  put the proportional shares back, do not let telemetry ride a content-neutral repaint or a gesture's first
  flush, never emit a cached draw on a depth plane WITHOUT its `CopyPair`.
- **The contract-2 mechanisms (§60–§64).** The atlas read-back
  (`Shell.atlasCheckThenLive`) is owed by BYTES and by acked MESSAGES (§73; `GlyphAtlas.ackedMsgs`: a
  patch-only extension moves no byte mark), keyed on the atlas's IDENTITY **and on an EPOCH** (a generation key
  reads a process's second atlas — the session a lens reset builds — as already checked, and identity plus a byte
  mark reads a REPACKED layout so too: a repack keeps the object, moves every record and rewinds the watermark;
  §64.2 item 2). `atlasCheckOwed()` bumps the epoch at a repack, a lapse, a reset
  and a session carried across a rebuild, and an answer whose epoch is stale is dropped rather than applied.
  One check is in flight at a time and a batch landing under it waits for its own. `CfwTransportBase.requestCacheSize` takes op 5's
  success from op 5's OWN id-0 reply, never from the status register (TELEMETRY records none, so it is
  sticky) and re-asks on the pacing tick; the session's size is the smaller of RIGHT's field 18 and the ask,
  and the ask rises above the last session's size only at a start where the lease log says both lenses hold no cache
  (a lease lapsed on both arms — `leaseKnownFresh`, `LEASE_FRESH_AFTER_MS`, stamped at each ACQUIRE's attempt; a release
  is not counted), so a lens that carries a cache always
  holds the session's size; the atlas read-back judges a cache SMALLER than the layout, not a different one. `CtlWork.BothArms.written` latches on SUCCESS — "some attempt
  reached both arms" — and the gate waits for it against the reply (`settleWrite`). Telemetry fields 2 and 12 are OS
  TICKS (1.024 per ms): `uptimeTicks` is the wire and `uptimeMs` converts. `--selfcheck` runs TWICE, the
  second pass on a contract-2 build with cached text ON; each pass counts its OWN oracle runs (§64), both simulators and both arms are read
  for sticky flags, the restarted session's transport events reach the same counters, and a contract-2 pass
  must see a partial transfer (arming DRAW2 proves nothing about what was emitted). **Three start gates have
  an escape from a write that keeps failing** (§64.2 items 4–5): the warmup re-sends on the stall threshold
  with the same deferred; a re-sent copy (the warmup's, the fid-ring clear's) whose wait has ended by the time it
  holds a window slot is not written (one copy already waiting for the slot an ack frees is still
  written), and the re-ask's note says "queued again" while the window is full. The fid-ring clear re-sends on the
  same threshold with one deferred; any end but the session's is a `control` note and the start goes on. RIGHT's
  write failing on any attempt ends a gate. The prelude and the capability query pass theirs to every
  attempt, not just the first. `LinkState.glassesSilent` is cleared at session entry (the READ path can only set it;
  a `true` carried across a session blanks an awake pair). Do not key a "have I checked this" on a
  per-object counter or on bytes alone, do not infer an op's success from a sticky register, do not let the
  selfcheck run one contract again, and do not leave a start gate awaiting something only one attempt could
  complete.
- **The glasses can be asleep (§36, §38).** The firmware's Silent Mode refuses every image; the glasses push the
  state and the READ response restores it (`SettingsMsg.parseSilentModePush` / `parseSilentRestored` →
  `TransportEvent.SilentMode`). `Shell.enterSleep(SleepReason, why)` stops SENDING (the pump returns before the
  flush), releases the lease on purpose (`Transport.setLeaseWanted(false)`), notifies once. **The wake is a session
  REBUILD**: `Shell.wakeGlasses` → `Transport.restartSession` → `onLinkDown`; the keeper restarts the shell from
  the prelude up and `Shell.start` adopts the glasses' state from that session's READ (`LinkState.glassesSilent`)
  before its first frame — leaving Silent Mode ends the firmware's EvenHub page (measured: four minutes of
  refusals after the push OFF on 0.34), so no keyframe into the old session can wake it. Three consecutive
  ImgResCmd refusals are the fallback for a missed push; `Shell.silentTick` (every 60 s and on a ring event)
  asks for the rebuild when the glasses say they are awake, once per pacing. System events 4/5/7 are journaled
  as `event` notes; the simulator's `carrierLost` models the page ending. Do not put the old "panic → keyframe"
  reaction back in front of a refusal (a 20 KB keyframe every 3.5 s for fifteen minutes, 2026-09-05).
  `SilentGlassesTest` ×8, `ShellKeeperTest.aRestartRequestRebuildsTheSessionOnce`. **The sleep has a
  reason** (`SILENT` / `REST`): a rest is the same path, entered by `probe:rest=on`, ended by `rest=off` or any input
  event (consumed); a cache write in flight and a read-back on its way are dropped at the entry with no keyframe;
  `decode` / `mirror/decode` are expected while asleep. Notes: `rest` and `power`; a rebuild refused while the shell
  is awake is a `keeper` note ("session rebuild refused by the transport (…) — not asleep, so no check asks again:
  the session goes on as it is"), asleep a `rest` or `silent` note by the reason. `RestModeTest`. **A start that rests
  (`HANDOFF.md` §79):** a start whose own READ says Silent Mode (`CfwTransportBase.start`, step 1c)
  completes asleep with no carrier — no CREATE, lease, cache size, flags, warmup or fid clear — and writes
  `LinkState.carrierUp` false (default true; it crosses the seam in `WireState`); `Shell.startLocked` sleeps on it
  (not an adopted session, which the adopted branch rebuilds), says no notice, enables no atlas, and the transport
  commits no lease carry; the push off rebuilds. Both start sites test the session again after `started = true`. The
  simulator answers no CREATE in Silent Mode (`createAnsweredWhileSilent`, default false). `SilentStartTest` (its
  `anAwakeStartWritesWhatItWroteBefore` compares an awake start's messages with `src/test/resources/startwire/`,
  written from `930e6b3`; never edit those files by hand). A start whose READ leaves out field 14 while the glasses
  are silent still waits at the CREATE (never seen: n=13).
- **The latency pass (§32)** — pixel-identical: the journal's submit line carries `via` / `handleMs` /
  `assembleMs`; `Compositor.compress` is memoised for ONE assemble; both rasterizers cache measures and coverage
  masks (`core/text/GlyphCaches.kt`); `Wrap.wrap` decides from an additive estimate outside a ±24 px band and
  measures exactly inside it (`WrapEstimateTest`); `Shell.pump` reserves one window slot for the pump after a
  ring event (`pumpPriority`) — two idle ticks in a row with the reserve holding and no flush completion between them
  give that tick's pump the whole window (one `link` note); `LinkState.transferMsPerKbEma` (flushes ≥ 1 KB, against
  `floorMsEma` from flushes < 400 B) is the regime discriminator — the phone-drawn wheel spins in 2 frames above
  50 ms/KB. **The two fields hold medians, not EMAs** (§73; the names are the seam's): `transport/LinkEstimator.kt`
  keeps the last nine ISOLATED flushes per term (every window slot free and no ack clock open when the lane
  took the flush — `CfwTransportBase.laneFlush`'s `alone`), prices a big flush against the measured floor,
  never takes a cache write as a floor reading, and starts over with each session; a term reads its default
  until three readings (`LinkState.floorSamples` / `transferSamples`, `floorMeasured` / `transferMeasured`),
  and the regime note says how many it stands on (`LinkEstimatorTest`, `LinkEstimatorWiringTest`);
  `Persistence.saveAsync` copies the records under the lock and encodes and writes on one daemon thread in
  submission order; an encode that throws is a save that did not land. Do not put the write back on the
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
  `Msg.Run` reaching a stopped loop runs its `dropped` arm. `Shell.cacheWorkInFlight` is loop-only, read
  inside `sampleIdle`.
- **A line box is the LARGER of the face's line height and its measured ink** (§30): AWT ceils ascent and descent
  separately (JetBrains Mono 16 inks 25 against a 24 px line at 115 %). `FlowRender.lineH`, `TermRender`'s
  cell, the keyboard's centring and the Hold'em history all take the max.
- **The shell loop survives an `Error`, loudly** (`Shell.loop`, §29): the handler and the pump catch `Error`, log
  it, note the journal, raise an urgent `shell` notice (`Shell.raiseFault`). Do not narrow it back to `Exception`.
- **The status cell is derived, every event is a notice** (`shell/Status.kt`, `HANDOFF.md` §69): `ShellState.of`
  ranks conditions read from the pump's one link-state snapshot — there is no status string to set or clear;
  `Shell.raiseFault` is the only door for a fault (one notice per class per `FaultEpisodes` episode, ×2/×4/×8
  count updates, one ring of the phone, muted by a dismissal, cleared at every session start), it POSTS the notice
  and never paints from inside a catch block, and it never runs for a refused image or for a session that is
  ending. The cell's width is measured (`Chrome.statusCellWidth` → `Layout.statusW`); `StatusCellTest` and the
  desktop's `StatusCellFitTest` (the real faces) pin it. When the atlas check finds DRAW2 not armed on the
  glasses the compositor drops to v1 shapes for the session (`v2RefusedThisSession`) and keyframes.
- **An event notice a tap should answer carries `appId` and a `target`** and coalesces per ITEM
  (§29): `TmuxCore` raises it into the notice record with the target `session:<host>:<name>`
  (`TmuxCore.sessionTarget`), which `open` resolves.
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
- **Events 9/10 are UNATTRIBUTED** (`EventSource` absent, source 0; `HANDOFF.md` §11–§12). Do not reintroduce a
  source gate on events 9/10,
  and never let a test supply what the wire omits (`LongPressTest`).
- **Windows scan their root at registration, quietly** (§28.1): no op-cell narration, no notice, no `navSeq` bump.
- **`Config.load` never writes back an unreadable file** (§28.1): defaults for that start only, loudly.
- **Chrome text is placed from MEASURED ink and its scale is capped to its bar** (§27.2): `Chrome.fitY`
  keeps each line's ink inside its cell; `Shell.chromeScale()` caps the chrome's scale to what the SHORTEST bar
  (28 px) can hold while CONTENT keeps the full 130 % ladder.
- **Compositor per-lens model.** The compositor reasons per lens: an expected shadow per lens; the per-lens
  TRUTH of the nominal frame under the plane map (each region vacates its nominal area to black — the seam;
  pieces render far to near, the nearest wins); a diff on the 4×2 damage grid; merging toward the pipelined rect
  budget (row bands first; within a piece, then across pieces of one disparity, **never across a pixel of
  another plane**; a final priced pass merges neighbours whose compressed union is cheaper — §5.1, §8.2).
  ⚠ **That rule binds the plane-0 REMAINDER group too** — not a rectangle, so `coarsen()` and `partition()` can
  both union across a region unless guarded (`remainderPieces()`; §25 #1–2): a flat delta carrying a region's
  pixels paints them at the wrong shift on BOTH lenses, belief and glass then agree on the wrong thing, and the
  divergence check cannot see it. The oracle that can recomputes the per-lens truth of `composed` under `planes`
  against `expectedLens()` — **a STANDING GATE** (every `--selfcheck` settle; `OracleWalkTest`
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
  The window-full-no-ack stall is REPORTED as a fault and otherwise not acted on, with one exception (`HANDOFF.md`
  §77): a wide flush's drain parked behind a lost image ack is nudged by the stall tick and written at depth 2,
  and its ack releases the earlier message.
- **Shell.** start/stop serialize on a mutex (a stop during start waits and never saves defaults over unread
  state); a stop joins the loop, so the drain has run when `stop()` returns, and the drain decides each
  transport event by `keepWhileDown` and puts the kept ones (a flush's answer, a typed line) back once. A failed
  start's rollback covers the whole start (the prelude and the stretch after `transport.start()`), and the
  Shutdown arm clears `running` and completes `done` on every exit; `setLeaseWanted` refuses a call during a start
  (`control` note) and `requestRebuild` runs only in the session it was asked in (`keeper` note). A notification box
  is LIFTED before any slide steps beneath it and repainted after. Every dynamic chrome string is sanitised to the
  locked glyph set and fitted with the continuation mark.
- **Content.** A local disk failure inside a host exchange is `CacheWriteFailed`, never read as the link's; the
  cache keeps the listing's real extension. The phone's reach of the PC is decided by the app lane's paced ping
  (`AppClient.Reach`), which the health view reads, not here.
- **From the review of §25:** a surface's WRAP width and its DRAW bound are the same number
  (`Notifications`: `SILENT_W` + `bodyLines(n, l, silent)`); dynamic text goes through `Draw.dynamic` everywhere
  (`Epub.fold` handles the cp1252 mojibake range, U+2011 and the zero-width formatters at extraction); a window
  with async levels loads a RESTORED level on `back()` (`MusicWindow.ensureLoaded`); `saveSubState()` never
  reports an EMPTY blob (not a record; an older store holds one as a removal's mark — the shell refuses one loudly,
  once per key per session).
- **The page traffic sleeps with the shell** (`CfwTransportBase.pageTrafficWanted`, §42): the 4 s keepalive and
  the 30 s carrier refresh go out only while the shell wants the lease and the glasses do not say they are
  silent (one night of Silent Mode was 8,641 unacked keepalives). The 60 s device-info READ stays: it can put the
  shell to sleep, and wakes it only on an explicit field-14 zero — whether the glasses send one is unread, so the push
  OFF is the wake the code counts on.
- **A stop after a link loss sends no lease release** (`stop()` reads `connected` before the sweep, §42): the
  write into the dropped arm was a fault at every rebuild, and the release that reached the survivor freed that
  lens's texture cache (a renewal keeps it — `settings_ext.c`). A deliberate stop releases both arms, best
  effort per arm. `ShellKeeperTest`, `SilentGlassesTest`.
- **The keeper narrates into the journal** (`Shell.journalNote`, `keeper` notes, §42) and every host serves
  `/log`; silent checks are counted (`silentChecks`); atlas chunks are `ATLAS` submits with their `via`.
- **Build C2's phone half (`HANDOFF.md` §92; nothing of it runs below ladder stage 4 on a build that
  reports DamageCaps bit 10).** The one gate: `CfwTransportBase.writeImage` → `bindGate` holds back every C2
  message without BIND in force, and a tail built with BIND in force and written after it left the flag state is
  dropped with a `local` note (`tailStillBound`). **The session's cache size across a restart of the process**
  (`LadderRecord.sessionKib`, `storeSessionKib`): before op 5 the stored value becomes min(stored, ask), after the
  answer the session's size, written only when it changes — each lens that carries a cache holds at least the stored
  value at any instant the process can end; a new process's first start asks at most it. **A hand `flags=` wish
  never carries a ladder bit past the stage** (the probe's `beyond`, the start's `withhold` = `ladderBits`, the
  arming's stage-4 branch), and a set naming an undefined bit is not sent. **The rules** (`local/LocalRules.kt`): one
  answer per bound slot per batch; every return of a list to the front re-binds it with a DISCARD naming the phone's
  row; table repair by evidence, one re-send each. **The compositor's COMMIT path** (`assembleWithEnds`,
  `assembleHolding`): END is applied to the model; a drawing that meets a bound list's areas is held on BOTH lenses
  (`Touched.sub`, `addPair`) until the adopted motion has ended on the glasses (`deferUntilMs`, `releaseDeferred`,
  the shell's one wake; `isQuiescent` waits for it); the hold loop has a throwing backstop (`HOLD_ROUNDS_MAX`) that
  lands in the shell's assemble-error path (the one loop on the shell's thread whose end rests on an argument).
  **Main as the bound list**
  (`shell/ListStaging.kt`, `ListBinding.kt`): the strip covers every row; the row key is what the row DRAWS (never a
  value that moves without the picture — the Music row's progress as its lit blocks); one strip at every cache size — a
  row-form change withdraws the list, writes the row and binds it again; the band slots are sized per row, with two
  spares. The instruments: `LocalSequenceFuzzTest` (eight invariants), `desktop --local-check`,
  `research/mutations-c2.json`.

## Verification

The battery is `CLAUDE.md`'s list; **the current counts are `REMINDER.md`'s**. The phone build runs in its own gradle call
(`:phone:assembleDebug` — `:phone:stageApk` STAGES the APK for install and is never a battery step). The fork:
`tools/verify.py` · `host/run_vectors.py` · `host/run_self_test.py` · `host/test_damage_ext.py`.
Every review's pins were run against the unfixed tree and watched to fail before they counted.

- `./gradlew :core:test` — RLE parity against the Python reference, CRC vectors, the runtime geometry and fid rules
  each pinned to a fixture that must fire (`GeometryTest`; the emit's refusals `EmitRulesTest`), pipeline round
  trips through the sim, `Round3Test`;
  `MirrorTeeTest`, `PreludeTest`, `DivergenceTest`, `ShellKeeperTest`, `WheelAndHostSettingsTest`,
  `SeamMirrorTest`, `SeamSessionTest`, `ReplicaServerTest`, `LongPressTest`;
  `LensOracleTest`, `Round5Test`–`Round7Test`, `L2ProbeTest`; `SeamLivenessTest`, `HandoverTest`,
  `StyleTest`, `BatteryBrightnessTest`, `EpubChaptersImagesTest`; `TmuxTest`; `SubstrateTest`,
  `ReaderArrangementsTest`, `ReaderPositionTest`, `ReaderCoreTest`, the record's, the lane's, the working copy's and
  the web's tests; the health view's (`HealthCoreTest`, `HealthLaneTest`, `HealthNoticeGateTest`,
  `HostTagFlushTest`); `FilesTest`, `FilesTrashTest`, `FilesMoveTest`, `FilesJobsTest`,
  `UploadTest`, `ReviewRound1Test`; `TorrentsTest`, `KeyboardTest`; the Music set;
  `Round9Test`; `Review20260903Test`; `ActivationTest`, `GamesKitTest`, `HoldemEngineTest` (the 3,000-scenario
  side-pot oracle, the 2,000-hand ranking corpus), `HoldemBotTest`, `GamesWindowTest`,
  `GamesReview20260904Test`, `GamesLive20260904Test`; `Review20260905Test`; `OracleWalkTest` (a seeded random
  walk of the §1 grammar at all four heights, belief = glass = per-lens TRUTH after every settle);
  `Review28Test`, `Review29Test`, `Review30Test`; `WrapEstimateTest` and the §40 pins; `SilentGlassesTest` and
  the §42 pins; `TextureCacheTest`, `AckStatusTest`; `FeedTest`, `FeedWindowTest`, `FeedNetTest`; `DevProbeTest`,
  `ConformanceVectorTest`, `DamageMsgTest` (§49); C1–C3's sets (the section "The phone's halves of C1–C3").
  ⚠ `FeedWindowTest.deepLinksResolveEveryForm` misses in some
  full runs (§49.6: the window flips to comic #1, which `ScriptedFeed` cannot serve; the trigger is unknown).
- `./gradlew :desktop:test` — the BlueZ glue over a fake link (`BlueZTransportTest`), `CliTest` (the argument list),
  `ConfigTest` (an unreadable `config.json` is left untouched, a tokenless one completed), `SetupServerTest`,
  `StandbyPolicyTest`, `NightlyCopyTest`, `StatusCellFitTest`, `EpubCheckTest`, `ThemeIconsTest`,
  `FeedStripsTest` (the real xkcd PNG through the decoder), `FilesThumbsTest`, `NoticeTimerRenderTest`,
  `TerminalLibraryTest` (the library's pin), `MotionCheckTest` (C1), `HealthPartsTest` (the PC's health readers),
  `HostTagFitTest` (the link cell's tags on the real faces).
- `--motion-check DIR` and `--local-check DIR` (C1–C3; the section "The phone's halves of C1–C3") — part of a C build's
  gate (`HANDOFF.md` §100.7).
- `--selfcheck` — the whole stack scripted end to end with real fonts: ink budgets, input grammar, persistence
  byte-behaviour, zero faults/failed flushes/sticky flags. **The per-lens TRUTH oracle runs on EVERY settle**
  (`oracleRuns >= 100` keeps it from quietly stopping; §27.2). **The font ladder's TOP END is walked** (`typeLadderTopEnd`: every window at 130 %, then
  Alegreya at 480 AND 288) with Music Mode's queue ADVANCING so its surfaces repaint as deltas; two type-ladder
  checks measure the three-line lens (2/28/48 in a 64 px box) and the 480 table's history band against the REAL
  rasterizer (`HOLDEM.md` §17.2). ⚠ Each window's checks live in their own method (`gamesChecks()`,
  `feedChecks()`, `typeLadderTopEnd()`) — inline, the script passed the JVM's 64 KB method limit. Run it more
  than once: it is a rate.
- `--games-check` — the ecology over hundreds of tournaments in memory: does skill separate from variance, can the
  tables fill, does the money supply's GROWTH RATE fall (it FAILS on a rising rate, early-against-late).
- `--epub-check ~/books` — every book parsed, chapters and image decode; each book's document is checked
  against today's text (its "documents across the shelf" line: spans dropped, links kept and dropped, realignments).
- `node --test 'core/src/test/js/*.test.mjs'` — the web client's pure modules and a small view harness; no
  browser, no packages, no network. The JavaScript is outside `mutate_core.py`: its guards are watched by hand.
- `--card-render` — the card sheets at every ladder rung into `design/shots/cards/`, at true 1×. Never judge card
  art scaled up: at 2× a detached stem and a merged pip both look fine.
- `--snapshot` — what the LEFT LENS PANEL holds (post-wire truth through pack → RLE → deflate → fragmenting → sim
  firmware → shadow), at true 1×. **A wait decides on ONE evaluation** (`while (!cond) delay(); if (!cond) fail`
  re-tests a condition the loop already passed and a periodic tick can flip back — §27.6; the 60 s settle /
  120 s wait bounds are backstops; anything over 5 s prints its cost). **The games scenes pin their world**
  (`gamesWin.roster.worldSeed = …`). **Run it more than once**, and never compare two runs with `diff -rq`: the
  clock and the throughput readout (`785K/s · 1ms` vs `1664K/s · 1ms`) differ on an unchanged tree. **The judge:**
  `python3 tools/snapdiff.py BASE NEW --mask BASE2` and `… BASE NEW/phone --subset --mask BASE2` against two
  base renders of the parent commit, made before the first product edit; 0 px outside the mask or each difference
  named. **To compare two BUILDS** by eye keep both installs on disk (build, copy `desktop/build/install/desktop` aside, `git stash`, build,
  copy aside, `git stash pop`), run them inside one minute, and judge by WHERE the pixels differ: the status
  readout sits inside `x∈[240,400]` in a band ≤ 16 rows tall; the live scenes are
  `38-music-mode-480-bars`, `39-music-mode-288-scope`; `10-silent.png` draws no status line and IS
  byte-stable — the useful canary.
- `tools/lint.py` gates the repo at 0 findings: SYM001/SYM002 (drawn strings and every Kotlin string literal against
  the locked faces), SEC001 (no token in a log line), RO001 (the bus watcher's package, `core/…/bastion/`, reads the
  hub through exactly one `Http.request("GET", …)` site, with no write-method literal and no other HTTP client),
  GEO (the cells `DESIGN.md` §2.3 declares, through
  `tools/geometry.py`) and BUD (the ink of the renders); `--selftest` names the rules it covers. The runtime rules
  (stereo pairs, the rect budget, fid order, the frame walls) are not lint's: `core`'s `geom` package checks them on
  every emit (`HANDOFF.md` §65).
