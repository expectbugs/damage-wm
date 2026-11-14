# Damage — implementation notes

What runs and how, as built for APK 0.66 (`HANDOFF.md` §79; built on `wip/066` — the deploy is recorded in §79.12).
Each build's record is `HANDOFF.md`; the current battery counts are `REMINDER.md`'s; operations are `DAILY.md`.

**The topology as built** (`HANDOFF.md` §19, §79): **the phone shell drives (radio and shell, while the APK is up); the
OpenRC `damage` service is the data provider — content + tmux + last-write-wins state sync on the content port, the
bus alert watcher — and since W it never drives by itself: PC-direct BLE is a manual choice** (`--transport ble`, the
replica probe `drive=ble`, the PC shell's `Target` row). **Ruled to change (`REPLAN.md` §14, 2026-11-12):** the PC
owns every window's state and the phone keeps a working copy (the two-way stamped sync is replaced — `CONTRACT.md`
§9.7); the code reaches that at step S1 (`REPLAN.md` §16.1); until then it does what this file says.

One `:core` library holds the shell, the byte-exact glass simulator, the compositor and every window (Main · Settings
· Reader · Tmux · Files · Torrents · Music · Games · Feed) on the CFW display contract (modes 3/6/8/9, the 11–15
texture-cache layer, the FB lease, the capability gate; on the fork's build contract 2's modes 17–24, `FIRMWARE.md`
§4). Per-window records: `TMUX.md`, `TORRENTS.md`, `MUSIC.md`, `HOLDEM.md`, `FEED.md`; Files is `HANDOFF.md` §22 — it
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
                         Note, SilentMode, Wear, Presented}──
```
`DisplayOp` (`transport/Transport.kt`): Keyframe · Delta · Copy · CopyPair · StereoPair · CacheWrite · DrawText ·
DrawImage, and contract 2's DrawText2 · DrawImage2 · Fill · Clip · PresentHint · CacheWrite2.

- Ops are NOMINAL coordinates + per-op disparity; the emitter builds the per-lens stereo boxes (§3.4).
  Payloads arrive pre-compressed (zlib(rle)) — compression is the shell's job (§10.1).
- **Fids are stamped by the transport at EMIT time** (§8.2 #5), via the shared `Emit` encoder.
- `submit()` enqueues in call order and returns; the backpressure signal is `LinkState.inFlight` against
  `window`, which the shell's pump gates on. A `wide` flush drains the window and runs at depth 1; behind a lost ack
  it is nudged after the stall bound and runs at the depth the held slots leave (2 behind one), its budget stated at
  that depth (APK 0.65, `HANDOFF.md` §77).
- The same interface serializes over TCP (`RemoteTransportClient/Server`, length-prefixed JSON + binary):
  the shell can live on the PC while the transport lives on the phone, or the reverse. The server admits
  ONE driver; claim/yield callbacks let the phone's local shell hand over and take back.

`SimTransport`, `BleTransport` and `BlueZTransport` share `CfwTransportBase` (the seam client relays) — the full choreography (capability gate → carrier CREATE → lease
both arms + 45 s renewal → warmup splash → the fid-ring clear (mode 7 sub 0, awaited) → idle keepalive → fragmenting ≤ 3800 B → msgId/session
discipline) — so both live BLE paths run the protocol brain the sim exercises on every selfcheck.

## Module map

```
core/       wm.damage.core.geom       panel constants, Rect, the runtime lint gate (the one implementation
                                      since 2026-09-16, HANDOFF §65), Layout (safe-rect-relative, §2.2b), fid discipline
            wm.damage.core.gfx        Gray8 compose surface, firmware-exact nibble RLE (pinned to fbfeas.py),
                                      4bpp pack, level-6 deflate, drawn icons/shapes (§4.5b)
            wm.damage.core.wire       CRC-16, protobuf, AA envelope + reassembly, EvenHub carrier messages,
                                      sid-0x09 lease + capability, mode 3/6/8/9 builders, CfwModes (11–15),
                                      TextureCache (byte layouts from zlib_glue.c / texture_cache.c),
                                      LoggerMsg (sid 0x0F, the Phase 0 log probe), DamageMsg (FIRMWARE.md
                                      §0/§3: DamageCaps, the control ops, the telemetry record), LaunchMsg
                                      (the connect prelude), WearMsg + CaseMsg (sid 0x10 / 0x81)
            wm.damage.core.sim        GlassFirmwareSim — the byte-exact model: per-lens shadows, cfw_diag fid
                                      ring + flags, warmup drop, msgId-255 silence, lease fail-open, the
                                      texture cache, silent rejects made loud; the overlay toggle and the
                                      log switch; a Damage build behind `damageContract`; the conformance
                                      doors (`conformanceMessage`, `conformanceLease`, `shadowCrc32`)
            wm.damage.core.transport  the seam + Emit + CfwTransportBase + SimTransport + Remote client/server
                                      + LensPanels (the mirror) + LinkEstimator + PathTransport (unused, below)
            wm.damage.core.comp       the compositor (one mode-8 flush per frame, §5), CachedText, CanvasShift,
                                      the JSONL journal
            wm.damage.core.shell      Shell, input grammar, chrome, Main, switcher, notifications, silent mode,
                                      ContentKit (lens/list/document), slides, persistence, settings,
                                      MenuSurface, KeyboardSurface (§4.8), ShellKeeper, Staging, Status,
                                      BatteryLatch, WindowContract (`DamageWindow`)
            wm.damage.core.windows.reader   Reader + EPUB extraction
            wm.damage.core.windows.files    the window, Local/Remote providers, FilesService, trash manifest,
                                      ListingCache (the phone's copy of the last listings, §47)
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
            wm.damage.core.net        WinNet — the §16.10 window channel (wreq/wres + blob answers + wpush);
                                      Reach + ReachGate — the listeners' reach gate (build W)
            wm.damage.core.bastion    the bus alert watcher (build W): HaReader, AlertRule, RuleEval,
                                      AlertStore, BastionWatcher + BastionService + PhonePresence (the PC),
                                      RemoteBastion, AlertDelivery, HomeWatch, BastionRows, PlaceWatch (the phone)
            wm.damage.core.sync       SyncNet/RemoteSync — LWW state sync
            wm.damage.core.util       Log · Exec (subprocess runner, stderr drained concurrently) · Http
                                      (HttpURLConnection, no timeouts, multipart) · InstanceLock ·
                                      UncaughtRecord
            wm.damage.core.replica    ReplicaServer — the browser replica, the journal, the log, the probes
            wm.damage.core.content    library providers: local dir, TCP host, remote client with copy-on-open
                                      caching; the win/icon dispatch
            wm.damage.core.text       TextRasterizer, Style/StyleTransform, GlyphCaches, Wrap
desktop/    AWT rasterizer · Swing lens preview (integer-scaled, default 4×; keyboard + mouse = ring) · CLI ·
            ThemeIcons (Papirus-Dark from xfconf, rsvg-convert, mem+disk cache, serves the phone) · PgDb ·
            MusicPlugins · MusicCheck · ScriptedMusic · ScriptedTmux · ScriptedTorrents · GamesCheck ·
            CardSheet · FeedCheck · SelfCheck · Snapshot · SetupServer (/setup + /damage-apk on :7300) ·
            BlueZLink/BlueZTransport (PC-direct BLE, manual) · StandbyPolicy (the data host's probe of
            the phone; never drives) · NightlyCopy (build W)
audio/      the enrichment package taken over from G2CC (Adam's code) + viz.py — run on G2CC's venv for now
phone/      foreground ShellService, on-screen lens view (touch = ring), AndroidText (bundled OFL/Apache
            fonts), BleTransport (LIVE since 2026-08-31), transport seam server, wakelock + Doze exemption +
            BootReceiver, DamageApp (the uncaught-error record), §9.3 urgent phone notifications, RemoteIcons,
            AndroidMusicPlayer, MusicListener, SpotifyRemote, TrackCache · build W: the three bus-alert
            channels + Acknowledge, NetState, AndroidPlaceSource, the "location" button (MainActivity)
```

## The mirror, the keeper and the replicas (the finishing build, `HANDOFF.md` §8)

Adam's target for it: *flash the firmware, install the app, and it works — usable from the app or the PC with a
mouse, on a pixel-exact replica.*

- **Every transport owns a mirror** (`Transport.mirror: LensPanels`): a `GlassFirmwareSim` fed the exact packets
  the transport writes; its `decode`/`fid`/`session` events surface as `mirror/<kind>` faults (the seven kinds,
  `image` included, on the simulator transport too); a packet of a message whose session ended is written but not
  applied to the mirror, counted in one `control` note at the stop (APK 0.65). Every replica
  draws it; `Transport.injectInput` lets a replica's gesture reach whichever shell drives.
- **The connect prelude** (`wire/LaunchMsg.kt`): one sid-0x01 app-launch request after both arms are up and an
  800 ms settle, acked on its msgId, before the capability gate; the 7-packet sid-0x80 sequence is never sent.
  The model treats the prelude as required (graded U).
- **The divergence check** (`Shell.checkMirrorAgreement`): at rest the compositor's belief per lens must equal
  the mirror through the emitter's quantiser over the rows the model's panel was written in and the rows our flushes
  touched since the last agreeing check (every row on a session's first check, after a failed or rolled-back flush,
  while a disagreement stands, and once a minute; `GlassFirmwareSim.takeChangedRows`, read in place under the model's
  monitor — APK 0.65); a disagreement is reported once per episode (the cell's `fault`,
  journal, an urgent notice on the `mirror` thread) and answered with one keyframe.
- **The session keeper** (`shell/ShellKeeper.kt`): a link end restarts the session after a 2 s pause, forever,
  no timeouts; a capability refusal is terminal (`onTerminal`). Since §16 **`pause` YIELDS**
  (`shell.stop(stopTransport=false)`): the glasses session, lease renewal included, keeps running with no
  driver, and `Shell.start()` ADOPTS a live session (one wide keyframe). The seam server answers a claim of a
  live session with an adopt-grant and never stops the owner's session on driver loss; only the OWNING host
  tears it down. `HandoverTest` ×4 (`preludeAcks == 1` across claim / silent death / release / pause / resume).
- **The arbitration** (`transport/PathTransport.kt`: concurrent attempts over the candidate paths, first to start
  wins, a refused path disabled for the run) is built by no host since §19 (`DesktopStack.transport` in `desktop/Main.kt`: `auto` builds
  no stack — it is the data host, which since W never starts one; `sim` / `ble` / `remote` build one transport each); the class and
  `PathTransportTest` remain. `--transport remote` is the explicit claim-and-drive dev override.
- **The phone**: `BleTransport` on G2CC's driver + the reference's sequence (RIGHT then LEFT, `retry(10, 500)`,
  MTU 512 checked ≥ 245, priority HIGH, cached pair addresses, RSSI poll); `ShellService` on the keeper (a seam
  claim pauses it, a release resumes it); the **Target** switch in `Prefs`; `LensView` draws the mirror.
- **PC-direct BLE** (`desktop/BlueZLink.kt`, `BlueZTransport.kt`): `bluez-dbus` 0.3.5 + `dbus-java` 5.2.0 (both
  MIT) on the system bus; `Device1.Connect` called raw so a refusal keeps its reason; MTU from
  `Properties.Get`; notifications from `PropertiesChanged(Value)`; `Connected=false` ends the session.
  Unit-tested over a fake link whose far end is the firmware model; manual only since W (`--transport ble`, the
  replica probe `drive=ble`, the PC shell's `Target` row; `drive=auto` hands back to the data host).
- **The seam carries the mirror**: `RemoteTransportServer` streams changed row ranges of both panels through
  one ordered outbox, so a panel update precedes the `done` of its flush (`RemoteTransportClient.mirror`,
  display-only).
- **The replicas**: the desktop `Preview` (**integer-scaled, default 4×** at Adam's ask — legibility judgment
  still belongs to true-1× renders or glass) and the browser page (`replica/ReplicaServer.kt` — dependency-free
  HTTP + RFC 6455, token-gated, dirty-row panel frames + 1 Hz status; `replica.html`), served by the desktop
  (`replicaPort` 7403) and the phone.
- **The reach gate** (`core/…/net/ReachGate.kt` over `Reach.classify`, build W; `CONTRACT.md` §10.9): every listener
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
  on tap, reverted on double-tap; applying rebuilds the stack. **Host categories** (`Shell.hostCategories`, build W):
  a category a host supplies before its window exists — the phone's "Bastion" (`bastion/BastionRows.kt`); a window
  built later returns the same row ids and wins by name. Shared rows in it go through `Shell.sharedRow`
  (`ShellSettings`, synced); the phone's own rows live in its preferences, never synced.
- **Decision 6**: an ORDINARY notification arriving while the wheel, the context menu or the keyboard is open
  waits behind it; an EMERGENCY cancels the surface (the keyboard's draft kept) and shows first.
- **Long-press defaults off** (2026-08-30, `DESIGN.md` §1.2/§1.3): a bare long-press is a no-op everywhere. The
  switcher opens by the chord: long-press, then double-tap within 800 ms of the release (event 9 arms, event 10
  refreshes; a mistimed chord is plain back). The Settings row **"Long-press": off / switcher** restores the
  direct open. `LongPressTest`; on hardware event 10 fires after almost every touch-end (§11).

## Running it

Desktop (the sim standing in for glass; also serves `~/books` to the phone):

```
./gradlew :desktop:run                        # AUTO (§19, §79): data host (content+tmux+sync+the bus watcher) + replica; probes the phone for its status line and never drives by itself — PC-direct BLE by hand with the replica probe drive=ble — stop the service first (exit 4 otherwise)
./gradlew :desktop:run --args="--help"   # the usage text; an argument the program does not know is refused (exit 2) and nothing starts; one entry point per line
./gradlew :desktop:run --args="--transport sim"   # the simulator in-process (development) — stop the service first (exit 4 otherwise)
./gradlew :desktop:run --args="--transport ble"   # PC-direct BLE only (manual) — stop the service first (exit 4 otherwise)
./gradlew :desktop:run --args="--remote HOST"     # = --transport remote: claim the phone's transport and drive through it — the EXPLICIT dev override; stop the service first (exit 4 otherwise)
./gradlew :desktop:run --args="--ble-info"    # the BlueZ adapter and the devices it knows (no discovery)
./gradlew :desktop:run --args="--selfcheck"   # the 485-check scripted gate: two passes (contract 1 with a gain-in-place step at boot, then contract 2 with cached text on), every window's walk, the truth oracle on every settle
./gradlew :desktop:run --args="--snapshot DIR"  # lens-truth PNGs of every surface (57 scenes)
./gradlew :desktop:run --args="--epub-check [DIR]"  # parse every book in DIR (default: the config's booksDir); exit 1 on zero books, a DIR that is not a directory or a failing book
./gradlew :desktop:run --args="--music-check" # the real g2cc library, read-only bar the additive schema migration: counts, catalog, lanes, cache keys, Qdrant, one viz blob, the adaptive-playlist derivation
./gradlew :desktop:run --args="--games-check" # the Hold'em ecology over hundreds of simulated tournaments (pure in-memory; add `deep` for a longer run)
./gradlew :desktop:run --args="--card-render" # the card sheets into design/shots/cards/ at true 1x
./gradlew :desktop:run --args="--feed-check [DIR] [live]"  # the feed engine over the captured fixtures; `live` = one paced fetch per source, read-only, temp dir
./gradlew :desktop:run --args="--host-only"   # content host alone (books + tmux + sync + setup page, no stack ever) — stop the service first (exit 4 otherwise)
./gradlew :desktop:test                       # 44 tests: the BlueZ glue over the fake link, the argument parser (CliTest), the config file's safety, SetupServer, the standby policy, the nightly copy, the status cell's fit, the epub check, theme icons, the xkcd PNG through the decoder
```

Preview: mouse wheel scroll · left click tap · right click double-tap · press-and-hold long-press · keys
↑/↓ Enter Backspace Space R T(type) · Tab lens · B both · -/= window scale (integer, default 4×). The browser
replica link is printed at start (`http://<host>:7403/?token=…`); the same server answers
`GET /journal?token=…[&tail=N]` (that host's flush journal, streamed from disk in 64 KiB chunks; `tail` = the
whole lines that start inside the last N bytes — `tools/journal_report.py -` reads it from stdin; before APK 0.61
the route read the whole file into memory and ended the phone's process, `HANDOFF.md` §71), since 0.64 `GET /journals`
(the archives) and `&name=journal-YYYYMMDD-HHMMSS.jsonl` on `/journal` (one archive; `tools/journal_pull.py` copies
them), and `GET /log?token=…[&tail=N]` (the process's last 4,000 log lines, `Log.recent`).

**The journal and the record of an uncaught error (APK 0.64, `HANDOFF.md` §76.1).** At a process start a journal past
64 MiB is renamed to `journal-YYYYMMDD-HHMMSS.jsonl` and the fresh file starts with a `journal` note naming it; nothing
is deleted. `UncaughtRecord` writes a fault's trace to `uncaught-pending.txt` at the fault; the next start journals an
`uncaught` note and moves the file to `uncaught-<time>.txt` — eight whole records per process, then one counter line
rewritten in place; 256 KiB, a record cut there still names its root cause; the same fault again (the first 32
frames compared) is appended and said short. The start order: on the PC `InstanceLock.claimOrExit` (exit 4 while
another process holds the data directory) ahead of the record's install, the journal's rotation, the `process` note
(with the archives' count and total) and the pickup; on the phone `DamageApp.onCreate` installs the record and
`ShellService.onCreate` runs its start steps ahead of `startStack`, caught as one (a fault there is one ERROR line and
the stack starts). The phone half has not been read on the device.

**The journal from APK 0.66 (`HANDOFF.md` §79, package G).** A transport event carries its own time (`t`), and a line
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
`backupDir`/`backupKeep`/`backupAt`, and `reachOpen` (true turns the listeners' reach gate off) — each defaults off
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
| **app + home PC** (the default — §10.1 row 1 as INTENDED, §19) | APK (Target = glasses) DRIVES; the `damage` service is the data host: live library, tmux, state sync, the bus watcher. The PC never claims |
| app alone | the APK with no PC reachable: its own shell, cached library + cached books, staleness said; sync catches up on reconnect; the bus alerts say "home unreachable" after the phone's set minutes |
| PC-direct BLE (the rare case; manual only since W, `REPLAN.md` §14.4, `HANDOFF.md` §79) | by hand: the replica probe `drive=ble` on the service (back with `drive=auto`), the PC shell's `Target` row, or `--transport ble` in a dev run. Nothing starts it by itself |
| PC drives through the phone | `--transport remote` ONLY — the explicit dev override (the old daily mode, kept for development) |
| laptop-direct with the simulator | `:desktop:run --args="--transport sim"` — the development environment |
| browser replica | `http://<desktop-or-phone>:7403/?token=…` from any machine on the tailnet — the PHONE's is the live view in the default configuration |

## Phone-primary + sync (`HANDOFF.md` §19; replaced at step S1 — `CONTRACT.md` §9.7, `REPLAN.md` §14.4)

- **`Persistence` is the sync substrate**: schema v2 with a per-key stamp, re-stamped only on real value change;
  `tryApplyRemote` applies a different value by strict stamp order; an equal value within 2 s
  (`STAMP_TOLERANCE_MS`) is the same record: no stamp moves, nothing is saved; legacy stores migrate in place with
  mtime stamps.
- **`core/sync/SyncNet.kt`** rides the content port (`{"t":"sync"}` upgrade): the handshake exchanges stamp maps
  + clocks (skew-normalized), records newer by more than 2 s flow both ways (two copies inside it are left; a
  different value inside it stays until that key's next edit), then live pushes; a handshake frame's clock is
  stamped at its write, the receive time taken at its length prefix; the client (`RemoteSync`, in the
  phone's `startStack`) reconnects keeper-style and re-handshakes every 5 min. `shell.settings` + `window.<id>`
  sync; `shell.state` (per-device UI) never does.
- **`Shell.postSync`** applies a record on the loop: a record the store already holds ends at the first statement;
  otherwise freshen the key from the LIVE window first, then apply, then live-apply. The driving shell's state is newest by construction, so sync flows driver → idle.
- **The seam status probe** (`SeamProbe` / `Ctl t="status"`): a non-claiming "does the APK want the radio?"; an
  old APK answers `busy`, read as YES.
- **Desktop `auto` = the data host** (since W; `StandbyPolicy`, R6 §5): one process-wide store feeding the sync
  channel and any stack; it probes the phone's seam every 5 s for the status line ("data host · phone drives",
  "· the APK chose Target≠glasses", "· phone unreachable 2m · PC-direct BLE is manual (drive=ble)") and nothing else:
  `StandbyPolicy.Action` has no member that starts a stack, so no probe answer can. A manual stack (`drive=ble`, the
  `Target` row) runs until `drive=auto`; a stack found up in `auto` (its stop dropped by a switch in progress) is
  stopped. Every `drive=` use is a `keeper` journal note. The automatic claim, its scan (`StandbyScan`), its debounce
  and its give-back were deleted (on 2026-11-06 the claim took the glasses from a phone set to Target=Sim, §72). Every
  server retries a busy port every 5 s (`bindOrRetry`). `SyncTest` ×6, `SyncToleranceTest` and `StandbyPolicyTest`
  pin it.

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
- **Distribution**: `./gradlew :phone:stageApk` → `~/.damage/damage-wm.apk`; since 2026-09-10
  `desktop/SetupServer.kt` serves `/setup` + `/damage-apk` on :7300 with G2CC's URL, token and Tailscale-only
  gate (`setupPort` / `setupToken` in `config.json`, `HANDOFF.md` §44) — behind the listeners' reach gate first
  (build W), then its own Tailscale-or-loopback check.
- The capability gate refuses any firmware without an `EVENCFW` string. Compass, IMU, wear detection: per
  `DESIGN.md` §7 — a placeholder compass cell until the mode-10 feed exists; head tracking defaults OFF.

## The bus alert watcher, presence and the nightly copy (build W, APK 0.66, `HANDOFF.md` §79)

A reader on the PC that only READS his own home hub and raises notices on his glasses and phone (R6 as amended by the
lead's decisions; `CONTRACT.md` §7, §11.2; `BASTION.md` F13). Rules and the config's keys: `DAILY.md`.

- **The reader** (`HaReader`): one `Http.request("GET", …)` site in the package (lint RO001), paths limited to the
  health check and the rules' own entities (a closed set). The token is read once at start from the file `haTokenFile`
  names (five answers: ok, missing, empty, unreadable, malformed; trimmed, never logged). `haUrl` empty or the token
  unusable → the watcher is off, says why at start, and raises its built-in alert if `haUrl` is set.
- **The watcher** (`BastionWatcher`, both host builders): a round every `haPollSec` (10 s); liveness without timeouts —
  a round still in flight after 60 s is said and the next starts beside it, never more than two; a late answer for an
  older round is dropped and said. Rules from `bastion-rules.json` (`haRulesFile`), read once at start; a bad rule
  refuses only itself. Kinds (`RuleEval`, one pure step): `above` / `below` with `clearAt` hysteresis, `equals` /
  `notEquals`, `unavailable`, `stale` (the report stamp unchanged for `forMin`, on the PC's clock), `change` (a
  baseline at arming, re-taken on acknowledge and kept in `alerts.json`, or with `withinMin` the value from a ring of
  one sample a minute; blind for `withinMin` after a start). A `when` guard on any rule: judged only while it holds;
  its end resets the clocks and baseline and clears the alert; no reading (unreadable, `unavailable`, `unknown`) holds
  everything. Built-in alerts `watcher.*`: the hub not answering for `haSilentMin`, a refused token (polling stops
  until a restart), the token file, the rules file, `watcher.rule.<id>` (a refused rule), `watcher.entity.<entity>`
  (a 404, or `haSilentMin` of failed reads while the hub answers), the store, the nightly copy. **The watcher's own
  trouble never ends an alert:** a rules file with a problem retires nothing, a refused rule's alert is held, and a
  repeat-only tick runs whenever the store holds a raised alert, the watcher on or off. The state line
  (`stateLine()`) goes to the `table` answer (the phone's `Watcher` row) and the PC log (tag `bastion`).
- **The store** (`AlertStore`, `alerts.json`): CLEAR → RAISED → ACKED → CLEAR with an episode per raise; `severity`
  `normal` (said once, or every `repeatMin`) or `urgent` (every ten minutes until acknowledged, passes quiet hours; a
  severity edit applies from the next episode). The epoch (minted when the file is created or recreated after an
  unreadable one) and the last event `seq` live in the file; the event ring (200) is memory, so a `table {since,
  epoch}` older than the ring answers `truncated` with the whole table.
- **The channel** (`BastionService`): the `bastion` window service on the content port, always up: `table {since,
  epoch, presence}` → the table, the state line and `bus`; `ack {id, episode}` (an action on the alert, the store's
  alone: no hub request); pushed `event`s from ONE sender coroutine.
- **The phone** (`RemoteBastion`, `AlertDelivery`, `HomeWatch`; wired in `ShellService`): a first-contact reconcile
  from the table (adopt and list, never pop old ones); a `table` ask every 15 s keeps the last-heard clock moving; a
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
  the PC's non-point-to-point networks, `away` after 15 minutes relayed or public, `unknown` otherwise (an idle peer
  included). The phone's report rides each `table` ask (`presence {word, fixAgeMs}`; none, `unknown` or older than 2 min
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
  the feed's `sources/` and `binge/`; never journals, `phone-journal/`, `trash/`, `config.json`, caches or the music
  database. Into `<backupDir>/damage-YYYYMMDD-HHMMSS.partial/`, renamed when whole; an unchanged file is hard-linked to
  the previous copy. The configured folder is never created (an unmounted disk must not take the copy onto the root
  disk). After a good copy, folders named exactly `damage-YYYYMMDD-HHMMSS` beyond `backupKeep` (14) are deleted,
  oldest first; nothing else is touched. At `backupAt` (03:30) daily, plus one catch-up after a start when the newest
  copy is stale; a file that vanishes during the walk is skipped and said in the run's line. A failed run is a log
  error and the built-in alert; the next good copy clears it.
- **`heap3`** (the first flash's rider, `FIRMWARE.md` §5.7): at every session start the free-memory fields of the
  telemetry record at four points — `pre-create` (the reset check's record, no extra read), `lease`, `atlas` (after
  the read-back), `first-frame` — journaled as `heap3` notes; the three reads are the existing telemetry read, none in
  Silent Mode, an unanswered one said and never waited on; off on instant test transports.

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
  in the root menu parks it. Nothing switches back on its own. (As built; from step S1 the phone shows the PC's last
  state with its age instead — `CONTRACT.md` §14 (a) row 1, `FEED.md` verdict 15.)
- **Reading state is the shell's:** `feed.src.<id>` (read ids capped at 600, flags, `seen`) and `feed.binge.<id>`
  (episode, strip); read marks UNION on a live apply, flags are LWW. Harness accessors: `rootRowId()`,
  `levelName`, `itemsLoaded()`, `comicFocusLabel()`.
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
  `TorrentsTest` ×9, `KeyboardTest` ×22.

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
- **Harnesses**: `ScriptedTmux` (the tmux selfcheck checks, snapshot scenes 09b–09e); `TmuxTest.kt` (32, six classes).

On-glass verdicts still owed (`REMINDER.md` → Suspended work): the flow view's default size/wrap feel,
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

The firmware's **64 KiB lease-scoped texture cache** and three draw modes (on contract 2 the cache is sized by op 5,
160 KiB a session, and modes 17–19 draw and write it — `FIRMWARE.md` §4); wire and model layers built 2026-08-30,
adopted by the compositor behind the Global `Cached text` row 2026-09-06.

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
- Two corrections from reading the source: the sim no longer attaches an `EventSource` to long-press events
  (`EvenHubMsg.reportsSource`); the capability gate runs against the real `EVENCFW/16` string (`REQUIRED_CAPS`
  stays at five, version checks through `SettingsMsg.contractVersion`).

**Full-cache eviction (`HANDOFF.md` §47, 2026-09-12).** A face that does not fit evicts every resident face not
drawn in the last 12 frames (`Shell.ATLAS_RECENT_FRAMES`) when together they free its price; `GlyphAtlas.repack`
rebuilds the packed bytes from the survivors (heaviest first) and the recently drawn icons and resets the upload
watermarks, so the glasses are rewritten from the guard up with every font off the live set until the last chunk
is acked — the lapse path's discipline. Paced 45 s (`ATLAS_REPACK_PACE_MS`), never with a chunk in flight; a
refused face books a retry a window of frames ahead; a stale face never re-enters over a recent one. An
`atlas` journal note says `full` only when a face is still pixels after all that (no box, no cell word: §69). `AtlasRepackTest` pins it with belief
= glass throughout.

**Adopted (`HANDOFF.md` §40.6).** `core/comp/CachedText.kt`: `GlyphAtlas` renders a font's glyphs 32..126
(since §73 only the ones the face has drawn — below)
through the host's rasterizer and packs them with `TextureCache.Builder`; `CachedText` wraps every host's
rasterizer, blits strings from those same images (the firmware's TRANSPARENT LUT draw) and records them per
frame; the compositor ships a rect as one clear plus mode-14 draws only when black plus the records equals the
composed pixels byte for byte. The upload (`DisplayOp.CacheWrite`, one ≤ 3 KB chunk per idle pump after the
keyframe) is `Shell.pumpAtlas`; fonts go live on the batch's last ack; a lease lapse forgets the upload.

**Kept across a rebuild (`HANDOFF.md` §54, 2026-09-15 — Adam's bounded skip, §51.8).** A session start no
longer throws the atlas away when the glasses still hold it. `CfwTransportBase` keeps a per-arm lease log
(the newest `LEASE_LOG_DEPTH` = 8 ACQUIRE writes taken as arrived — never pruned by age; whether a RELEASE went
out since): a write of the last `LINK_SETTLE_MS` (10 s) before a link end is struck (the platform's write callback means its stack took
the packet, the last exchange can predate a supervision timeout by 5 s, and a lease write can queue behind
a flush on the same arm), a release is never struck. At the session's first ACQUIRE the transport decides
whether both arms were inside `LEASE_CARRY_WINDOW_MS` (the firmware's 87.9 s expiry less a 10 s margin) and puts the answer and
the per-arm gaps in `LinkState.leaseCarried` / `leaseCarry` (over the seam too). Since §59 (2026-09-15, the
right lens rebooted inside the window and refused every cached draw): the start reads RIGHT's uptime before
the lease (`noteUptime`; since 0.66 a reading one wrap of the 32-bit tick counter on from the prediction, ±10 s, is
the wrap — a `keeper` note, no reset; the counter wraps about 2026-12-06), a reboot since that arm's last acquire
refuses the carry ("R reset N s ago"), and so
does a LEFT supervision timeout (a reboot there cannot be read) or a RIGHT one on a build without telemetry. `Shell.atlasAtSessionStart`
keeps the atlas when the transport says so, cached text is on, the session is not adopted and the last
cache write through the transport was this shell's (`FlushRequest.writer` = the shell's tag →
`LinkState.cacheWriter`): the acked fonts are live from the first compose, the chunk in flight at the link
end goes again (`GlyphAtlas.rewindToAcked`), the keyframe follows. Every other case resets. An `atlas` note
either way ("kept across the rebuild (L gap 12.3 s, R gap 12.3 s): …" / "reset — …"); `journal_report.py`
lists them under "atlas at session start". A chunk whose ack a link end took is no longer a refusal
(`atlasDone`): it goes again from the acked mark, at most `ATLAS_UNDELIVERED_LIMIT` (3) times in a row before
cached text is switched off with its own note. `AtlasCarryTest` pins all of it against the model.

**Every plane, and icons (§41).** On contract 1 (on contract 2 `Compositor.emitCachedV2` places each draw per
lens with modes 17/18 — no widening, no `CopyPair`) cached draws are flat in the firmware, so a rect on a depth
plane ships as a BASE delta over the rect widened by the disparity (the composed pixels with every pixel a draw writes
black — until 0.63 every draw's whole box), the draws at nominal x, and one
per-lens mode-9 copy (`DisplayOp.CopyPair` → `CfwModes.copyStereo`) that slides each lens's copy to its own x;
`Compositor.emitCached` builds the firmware's result per lens and compares it with that lens's truth before
anything ships — else pixels, the reason counted into `cacheMiss`; since 0.64 an inked rect is also priced against
its pixels (`dearer`). `CachedText` records draws into a slide's
temp through a relay (`via`/`viaInto`), moves records with a translation (`moveRecords`) — and a shell painter that
fills a region and paints it again drops the frame's records under it first (`Shell.retire`, 0.64: the menu,
keyboard, wheel, notice box, content, lens, exclusive and silent paints, every under-snapshot restore) — and draws a string
with a character its face's entry does not pack (§73: `GlyphAtlas.Entry.packs`, positions from
`CachedText.layout`) as its cached RUNS plus the host's character. Icons cross `IconPaint.blit`
(`CachedText` is the `IconRecorder`; mode-13 draws). Fonts pack heaviest first (`usageOf`); the atlas keeps an
ACKED watermark; the keyframe seeds the screen plane only (`Compositor.seedFrame`).

**Packed with what a face draws; grows in place (`HANDOFF.md` §73, 2026-11-09).** A face goes into the atlas
with the characters it has drawn — `CachedText.codesFor`: its own, all ten digits once it drew one, the space,
every capital once it reads as running text — and every other table entry names one shared tofu record; a face
packed with exactly what it drew is ≈3 KB where its whole Latin-1 table is 16 (M, AWT; the digits, space and
capitals the rule adds were not priced on that walk — `CLAIMS.md`). What follows from that:
- **A code the entry does not pack is the host's.** The recorder splits a string on `GlyphAtlas.Entry.packs`
  (never on the contract's range), the run after a host-drawn character records its first columns
  (`TextDraw.keep`: since 0.64 the base is black only where a draw writes, and the columns are read by the price,
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
`GlyphSubsetTest` (its `KernedText` carries the layout walk's pins; before §73 no gate compared `measure`
with a kerned draw, though `Contract2Test.KernText` already kerned the wire's adjust bytes),
`GlyphSubsetShellTest`, `AtlasRepackTest`, `StagingTest`.

## Page staging (2026-09-16, `HANDOFF.md` §66 — contract 2 only)

The focused document's next and previous strips as v2 image records in the cache's reserve, a notch as one draw.
- `shell/Staging.kt` — `StageKey` (the layout object + lines + line box + width), `StagedRecord` (RAW pixels for the
  paint and the proof, the encoded record, WANTED → SENT → ACKED → PROVEN), `StageRing` (the reserve as a ring: a bump
  pointer, eviction of what a placement overlaps unless it is pinned), `Staging` (the registry), `CacheLedger` (the
  proof by counting telemetry field 17 against the acked non-empty writes; Base / Proven / Short / Shortfall / Foreign).
- `Shell` "page staging": `pumpStage` runs on the idle pump after `pumpAtlas` — a reading when one is owed, else one
  strip rendered through the recorder's relay with nothing recorded (`CachedText.via` with an empty keep), placed in
  the ring and written in 3,064 B mode-19 messages (`STAGE` flushes, the atlas's writer tag); `stageDone` feeds the
  ledger; `launchLedgerRead` (op 1; since 0.64 `pumpLedger` is the count's one reader, for atlas writes and strips
  alike) and `ledgerVerdict` prove or drop; `stagePlacements` before every assemble;
  `paintDocSlice` blits a PROVEN record's lines. Both write lanes hold while a reading is on its way; a short reading
  inside `CacheLedger.SETTLE_MS` (2 s) of the last ack is pending, re-read paced 500 ms, and only two settled short
  readings make a shortfall (§66.1: bumps land up to ~1 s after the ack); an extra bump re-bases, drops nothing; a
  shortfall costs staging only, never the atlas (its own read-back judges it). Clears:
  `stageSessionStart`, `atlasReset`/`atlasLapsed`/`atlasDisable`/`atlasLive`, Silent Mode, the row off, `stage.retain`
  on a relayout. Bounded: `STAGE_FAILURES_MAX` = 3, then off for the session (a `stage` note).
- `Compositor.emitStaged` — before the cached path in `emitDelta`, no fid: the planned rect's overlap with a placement
  ships as a clip pair + one mode-17 pair + a clip reset when each lens's truth equals the record there (quantised);
  the shadows take the truth; a partly covered rect leaves the rest to the next planner pass.
- `LinkState.atlasCapacity` = the cache less `CfwModes.STAGE_RESERVE` on DRAW2; the read-back expects the glasses' cache
  to be the atlas plus the reserve. `Transport.telemetry()` = op 1. `ShellSettings.pageStaging`, the Global row `Page
  staging`, the journal's `staged` field and `stage` notes, `journal_report.py`'s page-staging line.
- Gates: `StagingTest` (21: §66's ten, §73's four — the two re-read paces on a paced link, a stopped shell, a strip
  after its glyphs — 0.64's six and 0.65's one), the contract-2 oracle walk (the document keyed), `--selfcheck`'s contract-2 pass (a Reader
  notch that shipped a staged draw, counted where the flush is assembled).

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
  every file through `GlassFirmwareSim` per lens (shadow CRC-32 and every return code): 34 vectors, 342 steps
  since the vector pass (2026-11-12, `HANDOFF.md` §77.4), matching on both lenses.
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
- Tests: `DamageMsgTest` (16; among them §52's reset seen past a grown uptime, the dropped unsupported bit and the
  truncated self-test step, and §53's FLAGS_SET with no lease and FB_RELEASE after a settled lapse),
  `ConformanceVectorTest` (2 — the normal path and the self-test form over the drawing vectors),
  `DevProbeTest` (4). The fork's side: `host/test_damage_ext.py` (76 checks), `host/run_self_test.py`.

## Review hardening (rounds 2–8, 2026-08-24)

Seven rounds of independent review after the first build (every candidate verified by trace, timing or pixel
simulation before a fix) found ~70 real defects; the reviews since (`HANDOFF.md` §25–§42) added to the list.
These mechanisms are load-bearing and easy to break by accident:

- **Build W's (APK 0.66, `HANDOFF.md` §79; `research/mutations-79-watcher.json`, 106 entries).** The hub is only
  read: one GET site and RO001. The watcher's own trouble never ends an alert (a rules file with a problem retires
  nothing; a refused rule's alert is held; the repeat-only tick runs on or off). The alert store owns its epoch and
  `seq`; a truncated answer is reconciled, never a silent gap; every phone re-ask is paced. The reach gate sits on the
  accepted socket before a byte is read, on every listener of both hosts (`check_apk_wiring.py` holds the phone's);
  `reachOpen` is the way back. The nightly copy never creates its folder and deletes only by its exact name pattern.
  `StandbyPolicy.Action` has no member that starts a stack. `PlaceWatch` stores a position only on a confirmation from
  an ask sent 150 s or more after the fix, and no coordinate or distance leaves it; presence lines carry no address.
  The start that rests: `carrierUp` defaults true; the pump's `carrierUp` guard and the shell's start read are one
  pair; the rest says no notice, enables no atlas and commits no lease carry; both start sites test the session again
  after `started = true`; the awake start's `startwire/` expectations are written by the test from an older tree,
  never by hand. `noteUptime` reads the tick counter's wrap as a wrap.
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
  one; the seam client and the path transport forget a session's Silent Mode and wear state at its end; a probe
  runs only in the session it was taken in; the lane drops an ACQUIRE once the lease is not wanted; a shell that
  adopts its session back without a lease rebuilds it; the loop's drain answers a queued `Run`.
- **What a shell holds while down (§72, 2026-11-09).** A window push whose newest instance supersedes the older
  ones goes through the KEYED `runOnShell(key, …)`: while no session is live only the latest per key is held,
  posted under `heldLock` just before the loop launches. A transport event with no session running is decided by
  kind in `Shell.keepWhileDown`: `FlushDone` must stay queued (the in-flight, atlas and staging bookkeeping) and
  `Text` too. `holdPushes()` belongs at EVERY `running = false`. The Reader's restore and scan are one job each. Do
  not drop `FlushDone`, do not post a held push outside the lock, do not add a plain `runOnShell` to a listener that
  keeps pushing. Music Mode's visualizer frame tick is keyed (`music.vizframe`, 0.65 P7 item 3). Since build W the PC's `auto`
  never claims: `StandbyPolicy.Action` has no member that starts a stack (`shouldClaim` and `StandbyScan` are gone).

- **The latency build (§40)** — chrome's telemetry cells repaint only when `allowTelemetry` (`Chrome.sync`); a
  notch's first flush is the translation — the lens repaint is posted one message on
  (`Shell.paintOptimisticLens`) and a strip past `Slide.SPLIT_FILL_PX` goes out blank and is filled on the next
  pump (`Slide.fillDeferred`, `Shell.applyCanvasFill`); `Compositor.partition` merges the globally cheapest
  within-owner pair first (proportional shares starved a plane); `CfwTransportBase.watchdogTick` rebuilds a
  quiet session — never while the page traffic sleeps (`pageTrafficWanted`: Silent Mode, a refusal-streak sleep, a
  rest; 0.64) — and the connect prelude re-asks every 2 s; `Compositor.emitCached` ships text as mode-14 draws
  only under a byte-exact proof in LENS space, on every plane, with a per-lens copy behind the flat draw. Do not
  put the proportional shares back, do not let telemetry ride a content-neutral repaint or a gesture's first
  flush, never emit a cached draw on a depth plane WITHOUT its `CopyPair`.
- **The contract-2 mechanisms (§60–§64), and the three that were quietly dead.** The atlas read-back
  (`Shell.atlasCheckThenLive`) is owed by BYTES and, since §73, by acked MESSAGES (`GlyphAtlas.ackedMsgs`: a
  patch-only extension moves no byte mark), keyed on the atlas's IDENTITY **and on an EPOCH**: every
  `GlyphAtlas` starts at upload generation 0, so a generation key read the second atlas of a process as
  already checked — the session a lens reset builds, which is the one §59 lost — and identity plus a byte
  mark read a REPACKED layout as already checked too, because a repack keeps the object, moves every record
  and rewinds the watermark (§64.2 item 2). `atlasCheckOwed()` bumps the epoch at a repack, a lapse, a reset
  and a session carried across a rebuild, and an answer whose epoch is stale is dropped rather than applied.
  One check is in flight at a time and a batch landing under it waits for its own. `CfwTransportBase.requestCacheSize` takes op 5's
  success from op 5's OWN id-0 reply, never from the status register (TELEMETRY records none, so it is
  sticky) and re-asks on the pacing tick. `CtlWork.BothArms.written` latches on SUCCESS — "some attempt
  reached both arms" — and the gate waits for it against the reply (`settleWrite`); reading the failure
  deferred instead latched on the first attempt that threw. Telemetry fields 2 and 12 are OS TICKS
  (1.024 per ms): `uptimeTicks` is the wire and `uptimeMs` converts. `--selfcheck` runs TWICE, the
  second pass on a contract-2 build with cached text ON — before that the standing oracle gate had never
  executed a v2 op; since §64 each pass counts its OWN oracle runs, both simulators and both arms are read
  for sticky flags, the restarted session's transport events reach the same counters, and a contract-2 pass
  must see a partial transfer (arming DRAW2 proves nothing about what was emitted). **Three start gates have
  an escape from a write that keeps failing** (§64.2 items 4–5): the warmup re-sends on the stall threshold
  with the same deferred; a re-sent copy (the warmup's, the fid-ring clear's) whose wait has ended by the time it
  holds a window slot is not written (R4 item 5; one copy already waiting for the slot an ack frees is still
  written), and the re-ask's note says "queued again" while the window is full. The fid-ring clear re-sends on the
  same threshold with one deferred; any end but the session's is a `control` note and the start goes on. RIGHT's
  write failing on any attempt ends a gate (P5 item 9). The prelude and the capability query pass theirs to every
  attempt, not just the first. `LinkState.glassesSilent` is cleared at session entry — the READ path can only ever set it, so a
  `true` used to cross a session boundary and blank an awake pair. Do not key a "have I checked this" on a
  per-object counter or on bytes alone, do not infer an op's success from a sticky register, do not let the
  selfcheck run one contract again, and do not leave a start gate awaiting something only one attempt could
  complete.
- **The glasses can be asleep (§36, §38).** The firmware's Silent Mode refuses every image; the glasses push the
  state and the READ response restores it (`SettingsMsg.parseSilentModePush` / `parseSilentRestored` →
  `TransportEvent.SilentMode`). `Shell.enterSleep(SleepReason, why)` (0.64; `enterSilentGlasses` before) stops
  SENDING (the pump returns before the flush),
  releases the lease on purpose (`Transport.setLeaseWanted(false)`), notifies once. **The wake is a session
  REBUILD**: `Shell.wakeGlasses` → `Transport.restartSession` → `onLinkDown`; the keeper restarts the shell from
  the prelude up and `Shell.start` adopts the glasses' state from that session's READ (`LinkState.glassesSilent`)
  before its first frame — leaving Silent Mode ends the firmware's EvenHub page (measured: four minutes of
  refusals after the push OFF on 0.34), so no keyframe into the old session can wake it. Three consecutive
  ImgResCmd refusals are the fallback for a missed push; `Shell.silentTick` (every 60 s and on a ring event)
  asks for the rebuild when the glasses say they are awake, once per pacing. System events 4/5/7 are journaled
  as `event` notes; the simulator's `carrierLost` models the page ending. Do not put the old "panic → keyframe"
  reaction back in front of a refusal (a 20 KB keyframe every 3.5 s for fifteen minutes, 2026-09-05).
  `SilentGlassesTest` ×8, `ShellKeeperTest.aRestartRequestRebuildsTheSessionOnce`. **Since 0.64 the sleep has a
  reason** (`SILENT` / `REST`): a rest is the same path, entered by `probe:rest=on`, ended by `rest=off` or any input
  event (consumed); a cache write in flight and a read-back on its way are dropped at the entry with no keyframe;
  `decode` / `mirror/decode` are expected while asleep. Notes: `rest` and `power`; a rebuild refused while the shell
  is awake is a `keeper` note ("session rebuild refused by the transport (…) — not asleep, so no check asks again:
  the session goes on as it is"), asleep a `rest` or `silent` note by the reason. `RestModeTest`. **A start that rests
  (0.66, `HANDOFF.md` §79 package G):** a start whose own READ says Silent Mode (`CfwTransportBase.start`, step 1c)
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
  give that tick's pump the whole window (one `link` note; APK 0.65); `LinkState.transferMsPerKbEma` (flushes ≥ 1 KB, against `floorMsEma` from
  flushes < 400 B) is the regime discriminator — the wheel spins in 2 frames above 50 ms/KB. **Since §73 the
  two fields hold medians, not EMAs** (the names stay: the seam carries them): `transport/LinkEstimator.kt`
  keeps the last nine ISOLATED flushes per term (every window slot free and no ack clock open when the lane
  took the flush — `CfwTransportBase.laneFlush`'s `alone`), prices a big flush against the measured floor,
  never takes a cache write as a floor reading, and starts over with each session; a term reads its default
  until three readings (`LinkState.floorSamples` / `transferSamples`, `floorMeasured` / `transferMeasured`),
  and the regime note says how many it stands on (`LinkEstimatorTest`, `LinkEstimatorWiringTest`);
  `Persistence.saveAsync` copies the records under the lock and encodes and writes on one daemon thread in
  submission order; an encode that throws is a save that did not land (APK 0.65). Do not put the write back on the
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
  `Msg.Run` reaching a stopped loop runs its `dropped` arm. `Shell.cacheWorkInFlight` (0.64) is loop-only, read
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
- **Events 9/10 are UNATTRIBUTED** (`EventSource` absent, source 0; `HANDOFF.md` §11–§12): the switcher did not work
  from first light until the source filter that dropped them went. Do not reintroduce a source gate on events 9/10,
  and never let a test supply what the wire omits (`LongPressTest`).
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
  The window-full-no-ack stall is REPORTED as a fault and otherwise not acted on, with one exception (APK 0.65, P5
  item 10): a wide flush's drain parked behind a lost image ack is nudged by the stall tick and written at depth 2,
  and its ack releases the earlier message.
- **Shell.** start/stop serialize on a mutex (a stop during start waits and never saves defaults over unread
  state); since 0.64 a stop joins the loop, so the drain has run when `stop()` returns, and the drain decides each
  transport event by `keepWhileDown` and puts the kept ones (a flush's answer, a typed line) back once. Since 0.65
  a failed start's rollback covers the whole start (the prelude and the stretch after `transport.start()`), and the
  Shutdown arm clears `running` and completes `done` on every exit (R4 items 2–3); `setLeaseWanted` refuses a call
  during a start (`control` note) and `requestRebuild` runs only in the session it was asked in (`keeper` note; P5
  item 3). A notification box is LIFTED before any slide steps beneath it and repainted after. Every dynamic
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
  silent (one night of Silent Mode was 8,641 unacked keepalives). The 60 s device-info READ stays: it can put the
  shell to sleep, and wakes it only on an explicit field-14 zero — whether the glasses send one is unread, so the push
  OFF is the wake the code counts on (0.64).
- **A stop after a link loss sends no lease release** (`stop()` reads `connected` before the sweep, §42): the
  write into the dropped arm was a fault at every rebuild, and the release that reached the survivor freed that
  lens's texture cache (a renewal keeps it — `settings_ext.c`). A deliberate stop releases both arms, best
  effort per arm. `ShellKeeperTest`, `SilentGlassesTest`.
- **The keeper narrates into the journal** (`Shell.journalNote`, `keeper` notes, §42) and every host serves
  `/log`; silent checks are counted (`silentChecks`); atlas chunks are `ATLAS` submits with their `via`.

## Verification

The battery is `CLAUDE.md`'s list; **the current counts are `REMINDER.md`'s** (at 0.65's R4, simulator: core 1023 ·
desktop 35 · `--selfcheck` 485 ×3 · 57 snapshot renders · lint 0). The phone build runs in its own gradle call
(`:phone:assembleDebug` — `:phone:stageApk` STAGES the APK for install and is never a battery step). The fork:
`tools/verify.py` · `host/run_vectors.py` · `host/run_self_test.py` · `host/test_damage_ext.py`.
Every review's pins were run against the unfixed tree and watched to fail before they counted.

- `./gradlew :core:test` — RLE parity against the Python reference, CRC vectors, the runtime geometry and fid rules
  each pinned to a fixture that must fire (`GeometryTest`; the emit's refusals `EmitRulesTest`), pipeline round
  trips through the sim, `Round3Test`;
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
  the §42 pins; `TextureCacheTest`, `AckStatusTest`; `FeedTest`, `FeedWindowTest`, `FeedNetTest`; `DevProbeTest`,
  `ConformanceVectorTest`, `DamageMsgTest` (§49). ⚠ `FeedWindowTest.deepLinksResolveEveryForm` misses in some
  full runs (§49.6: the window flips to comic #1, which `ScriptedFeed` cannot serve; the trigger is unknown).
- `./gradlew :desktop:test` — the BlueZ glue over a fake link (`BlueZTransportTest`), `CliTest` (the argument list),
  `ConfigTest` (an unreadable `config.json` is left untouched, a tokenless one completed), `SetupServerTest`,
  `StandbyPolicyTest`, `NightlyCopyTest` (build W), `StatusCellFitTest`, `EpubCheckTest`, `ThemeIconsTest`,
  `FeedStripsTest` (the real xkcd PNG through the decoder).
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
- `tools/lint.py` gates the repo at 0 findings: SYM001/SYM002 (drawn strings and every Kotlin string literal against
  the locked faces), SEC001 (no token in a log line), RO001 (the bus watcher's package, `core/…/bastion/`, reads the
  hub through exactly one `Http.request("GET", …)` site, with no write-method literal and no other HTTP client),
  GEO (the cells `DESIGN.md` §2.3 declares, through
  `tools/geometry.py`) and BUD (the ink of the renders); `--selftest` names the rules it covers. The runtime rules
  (stereo pairs, the rect budget, fid order, the frame walls) are not lint's: `core`'s `geom` package checks them on
  every emit (the Python copy retired at `HANDOFF.md` §65).
