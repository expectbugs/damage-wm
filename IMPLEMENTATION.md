# Damage — implementation notes

**First stage built 2026-08-24; finishing build 2026-08-25 (`HANDOFF.md` §8); LIVE ON HARDWARE
since first light 2026-08-30 (§11); the refinement wave landed 2026-08-31 (§12); the DAILY
DRIVER went live 2026-08-31 (§13.3b–§17, `DAILY.md`) and was RE-SHAPED the same night by the
§19 correction: the PHONE SHELL is the primary driver (it owns the radio AND the shell, always,
while the APK is up); the OpenRC `damage` service is the DATA PROVIDER — content + tmux +
last-write-wins state sync on the content port — plus a STANDBY that drives PC-direct BLE only
while the APK is unavailable and hands the radio back when it returns.** The shell core, the
byte-exact glass simulator, the desktop program, and the phone APK — Reader, Tmux, **Files
(2026-09-01, the first conversion)**, **Torrents (2026-09-01 evening, with the §4.8 keyboard)**,
Main and Settings at the app layer, the full shell underneath, everything on the **CFW display
contract** (modes 3/6/8/9 + the 11–15 texture-cache wire layer, the FB lease, the capability gate).

**2026-09-01 (later) — two chrome tweaks (Adam):** the battery cell shrank to its two gauges
(120 px, flush against the clock — the title gained 56 px), and the silent-mode clock size
became a Global setting (`Silent clock`: large / medium seven-segment, small = the title bar
clock's cell — `DESIGN.md` §1.5).

**2026-09-01 — the FILES build + the §16 machinery + an 8-round review loop run to
convergence (79→20→28→9→3→3→2→0 — HANDOFF.md §22):** the
Files window (locations with capacity bars · tap-=-context-menu grammar · text/image/PDF
viewers · clipboard Copy/Cut→Paste · trash with Restore and double-confirm purge · typed
rename/mkdir · Open-on-PC · EPUB→Reader hand-off) over the new shared machinery: the
**MenuSurface** floating context menu, the **WinNet** generic window channel
(`{"t":"win","win":…}` on the content port, blob answers), **theme icons everywhere**
(Papirus-Dark via desktop `ThemeIcons` + phone `RemoteIcons` over a content-port icon op;
drawn set = fallback and release path; Main's lens icon is band-height 56 px), the §16.4
**state substrate** (per-item sub-records with reported-guarded tombstones, merge-on-load,
post-start reconciliation, the continuity gates), `open(target)`/`openWindow` deep links with
back-to-caller, the grown notification signature (appId/thread/target), and `Draw.fit`/
`Draw.dynamic` (every cut advertised; external text '?'-substitutes instead of throwing).
Module map additions: `core.windows.files`, `core.net` (WinNet), `core.util.Exec`
(deadlock-proof subprocess runner), shell `MenuSurface`/`Draw`, `gfx.IconSource`, desktop
`ThemeIcons`, phone `RemoteIcons`.

## The two locked decisions

**Runtime: Kotlin/JVM** (the first build's open item #11, closed by it). One `:core` library holds the entire
shell — compositor, wire codecs, simulator, surfaces, Reader — and runs
unmodified inside the desktop JVM program and the Android APK. Core uses no
AWT and no android.*; platform text rasterization enters through
`wm.damage.core.text.TextRasterizer` (AWT on desktop, android.graphics on the
phone), with faces x-height-normalised to the §Type measurements on both.

**The transport ↔ shell seam** (open item #12, likewise closed) is
`wm.damage.core.transport.Transport`:

```
shell  ──FlushRequest{ops: Keyframe|Delta|Copy|StereoPair, epoch, wide}──▶  transport
       ◀──TransportEvent{Input, FlushDone, Lease, Link, DiagFlags, Fault}──
```

- Ops are NOMINAL coordinates + per-op disparity; the emitter builds the
  per-lens stereo boxes (§3.4). Payloads arrive pre-compressed (zlib(rle)) —
  compression is the shell's job (§10.1).
- **Fids are stamped by the transport at EMIT time** (§8.2 #5), via the shared
  `Emit` encoder every implementation uses.
- `submit()` enqueues in call order and returns; the backpressure signal
  §5.13's coalescing rides on is `LinkState.inFlight` against `window`, which
  the shell's pump gates on (the fragment window inside the transport backs
  it). A `wide` flush drains the window and runs at depth 1 — §8.2 #4's
  rects-for-depth trade.
- The same interface serializes over TCP (`RemoteTransportClient/Server`,
  length-prefixed JSON + binary): the shell can live on the PC while the
  transport lives on the phone, or the reverse. The server admits ONE driver;
  claim/yield callbacks let the phone's local shell hand over and take back —
  the "both able to take over" requirement.

Every transport shares `CfwTransportBase` — the full choreography (capability
gate → carrier CREATE → lease both arms + 45 s renewal → warmup splash → idle
keepalive → fragmenting ≤3800 B → msgId/session discipline) — so both live
BLE paths run the exact protocol brain the sim exercises on every selfcheck.

## Module map

```
core/       wm.damage.core.geom       panel constants, Rect, the runtime lint gate
                                      (same rule IDs as tools/geometry.py), Layout
                                      (safe-rect-relative, §2.2b), fid discipline
            wm.damage.core.gfx        Gray8 compose surface, firmware-exact nibble
                                      RLE (pinned to fbfeas.py vectors), 4bpp pack,
                                      level-6 deflate, drawn icons/shapes (§4.5b)
            wm.damage.core.wire       CRC-16, protobuf, AA envelope + reassembly,
                                      EvenHub carrier messages, sid-0x09 lease +
                                      capability, mode 3/6/8/9 builders (all byte
                                      layouts read from zlib_glue.c)
            wm.damage.core.sim        GlassFirmwareSim — the byte-exact model:
                                      per-lens shadows, cfw_diag fid ring + flags,
                                      warmup drop, msgId-255 silence, stuck sessions,
                                      lease fail-open, silent rejects made loud
            wm.damage.core.transport  the seam + Emit + CfwTransportBase +
                                      SimTransport + Remote client/server
            wm.damage.core.comp       the compositor (one mode-8 flush per frame,
                                      §5 rules), JSONL journal
            wm.damage.core.shell      Shell orchestrator, input grammar, chrome,
                                      Main, switcher, notifications, silent mode,
                                      ContentKit (lens/list/document), slides,
                                      persistence, settings, MenuSurface, the
                                      §4.8 KeyboardSurface (2026-09-01)
            wm.damage.core.windows.reader  Reader + EPUB extraction
            wm.damage.core.windows.files   FILES (2026-09-01): the window, the
                                      Local/Remote providers, FilesService on
                                      the win channel, trash manifest
            wm.damage.core.windows.tmux    Tmux window + providers + TmuxNet
            wm.damage.core.windows.torrents TORRENTS (2026-09-01): QbtClient (Web
                                      API 2.11), the TorrentLeech adapter + Html
                                      reader, Local/Remote providers, TorrentsNet
            wm.damage.core.net        WinNet — the §16.10 generic window
                                      channel (wreq/wres + raw blob answers)
            wm.damage.core.sync       SyncNet/RemoteSync — LWW state sync
            wm.damage.core.util       Log · Exec (deadlock-proof subprocess
                                      runner — stderr drains concurrently) · Http
                                      (HttpURLConnection, no timeouts, multipart)
            wm.damage.core.content    library providers: local dir, TCP host,
                                      remote client with copy-on-open caching;
                                      + the win/icon dispatch (2026-09-01)
desktop/    AWT rasterizer · Swing lens preview (integer-scaled, default 4x;
            keyboard + mouse = ring) · CLI · ThemeIcons (Papirus-Dark from
            xfconf, rsvg-convert, mem+disk cache, serves the phone)
phone/      Android app: foreground ShellService, on-screen lens view (touch =
            ring), AndroidText (bundled OFL/Apache fonts), BleTransport (LIVE
            on hardware since 2026-08-31), transport seam server, wakelock +
            Doze exemption + BootReceiver, §9.3 urgent phone notifications,
            RemoteIcons (theme bitmaps fetched + theme-keyed disk cache)
```

## The finishing build (2026-08-25)

Adam's target: *flash the firmware, install the app, and it works — usable from the app or the PC
with a mouse, on a pixel-exact replica.* `HANDOFF.md` §8 holds the decisions, the fixed design
and the item-by-item log; this is the map of what it added.

- **Every transport owns a mirror** (`Transport.mirror: LensPanels`): a `GlassFirmwareSim` fed
  the exact packets the transport writes (after each write succeeds); the sim transport's mirror
  is its sim. Its `decode`/`fid`/`session` events surface as `mirror/<kind>` faults — the model
  predicting a silent rejection. Every replica draws it. `Transport.injectInput` lets a replica's
  gesture enter the transport's event flow, so it reaches whichever shell drives.
- **The connect prelude** (`wire/LaunchMsg.kt`): one sid-0x01 app-launch request after both arms
  are up and an 800 ms settle, acked on its msgId, before the capability gate — the CFW
  reference's sequence. The 7-packet sid-0x80 sequence is never sent. The model treats the
  prelude as required (graded U; a missing prelude shows as a blank panel).
- **The divergence check** (`Shell.checkMirrorAgreement`): at rest, the compositor's belief per
  lens must equal the mirror through the emitter's quantiser; a disagreement is reported once per
  episode (status `DIVERGE`, journal, urgent notice) and answered with one keyframe.
- **The session keeper** (`shell/ShellKeeper.kt`): the reconnect loop both hosts use — a link end
  restarts the session after a 2 s pause, forever, no timeouts; a capability refusal is terminal
  (`onTerminal`); `pause`/`resume` for takeovers. **Since 2026-08-31 ("the session outlives the
  driver", HANDOFF §16): `pause` YIELDS — `shell.stop(stopTransport=false)` — so the glasses
  session (lease renewal included) keeps running with no driver, and `Shell.start()` ADOPTS a
  live session (skips the choreography, rebaselines with one wide keyframe). The seam server
  answers a claim of a live session with an adopt-grant, never re-choreographs, and NEVER stops
  the owner's session on driver loss or release — a WiFi edge is now two invisible repaints, not
  two teardowns (the G2CC decoupling: its BLE session's lifetime never depended on the server
  link). The transport's OWNING host still tears it down for real (stack teardown, target
  switch). `HandoverTest` ×4 pins it — `preludeAcks == 1` across claim/silent-death/release/
  pause/resume is the probe.**
- **The arbitration** (`transport/PathTransport.kt`): concurrent attempts over the candidate paths
  (the phone's seam first by a head start, PC-direct BLE after), the first to start wins and the
  rest are cancelled, a failed attempt is retried with backoff while the search is open, a
  refused path is disabled for the run; a working path is held until it ends.
  ⛔ **No longer the desktop's default (§19, 2026-08-31): `auto` is the STANDBY policy now** —
  see "Phone-primary + sync" below. The class stays (tested; `--transport remote` remains the
  explicit claim-and-drive dev override).
- **The phone**: `BleTransport` rebuilt on G2CC's driver + the reference's sequence (RIGHT then
  LEFT, `retry(10, 500)`, MTU 512 checked ≥ 245, priority HIGH, notify enable surfaced, cached
  pair addresses, RSSI poll); `ShellService` on the keeper (a seam claim pauses it, a release
  resumes it; a refusal falls back to the simulator with a persistent notification); the
  **Target** switch (strip button with confirm + a Settings row) persisted in `Prefs`; `LensView`
  draws the mirror, touch goes through `injectInput`; the browser replica is served on 7403.
- **PC-direct BLE** (`desktop/BlueZLink.kt`, `BlueZTransport.kt`): `bluez-dbus` 0.3.5 + `dbus-java`
  5.2.0 (both MIT) on the system bus; `Device1.Connect` called raw so a refusal keeps its reason;
  MTU from `Properties.Get`; notifications from `PropertiesChanged(Value)`; `Connected=false` ends
  the session. Unit-tested over a fake link whose far end is the firmware model; hardware-proven at first
  light 2026-08-30 (`HANDOFF.md` §11, the section below) and since §19 the STANDBY path — it
  drives only while the APK is away.
- **The seam carries the mirror**: `RemoteTransportServer` streams changed row ranges of both
  panels through one ordered outbox with events and state, so a panel update precedes the
  `done` of its flush; `RemoteTransportClient.mirror` applies them (display-only, `exact=false`).
- **The replicas**: the desktop `Preview` (mouse = ring: wheel notch, left tap, right double-tap,
  hold ≥ 600 ms long-press then release; Tab lens, B both; a status strip under the lens image;
  **integer-scaled, default 4×** since 2026-08-31 — Adam asked for "at least four times" the 1×
  window; nearest-neighbour only, `-`/`=` adjust, auto-clamped to the screen. Legibility judgment
  still belongs to true-1× renders or glass — `design/render_shots.py` stays 1×);
  the browser page (`replica/ReplicaServer.kt` — dependency-free HTTP + RFC 6455, token-gated,
  per-client dirty-row panel frames + 1 Hz status; `replica.html` — two 640×480 canvases,
  pixelated, the same mouse/keyboard mapping, reconnect with backoff). Served by the desktop
  (`replicaPort` 7403) and the phone.
- **Host-supplied Settings rows** (`HostSetting`): the display target on both hosts — staged on
  scroll, applied on tap, reverted on double-tap; applying rebuilds the stack.
- **Decision 6**: an ORDINARY notification arriving while the switcher wheel, the context menu
  or the keyboard is open waits behind it; an EMERGENCY cancels the surface (the keyboard's
  draft kept) and shows first (the 2026-09-01 review rounds).

**2026-08-30 — long-press defaults off (`DESIGN.md` §1.2/§1.3 revised).** A bare long-press is a
no-op everywhere — it is the most common accidental press, all day, gloves worst — and in silent
mode it does not even arm. The switcher opens by the chord: long-press, then double-tap within
800 ms of the release (event 9 arms, event 10 refreshes, any other gesture ends it; a mistimed
chord is plain back; the input echo's "hold" glyph is the armed indicator). The Settings row
**"Long-press": off / switcher** restores the direct open — which also restores the focused
notice's dismiss-unread long-press; by default that job belongs to the chord (the wheel parks the
box unread and returns it on close). `LongPressTest` pins the grammar; whether the real ring
delivers the release event was answered on hardware: event 10 fires after almost every
touch-end — see `HANDOFF.md` §11.

## Running it

Desktop (laptop-direct with the sim standing in for glass — §10.8's
development environment; also serves ~/books to the phone):

```
./gradlew :desktop:run                        # AUTO = STANDBY (§19): data host (content+tmux+sync) + replica; probes the phone, BLE-drives ONLY while the APK is away
./gradlew :desktop:run --args="--transport sim"   # the simulator in-process (development)
./gradlew :desktop:run --args="--transport ble"   # PC-direct BLE only (manual)
./gradlew :desktop:run --args="--remote HOST"     # claim the phone's transport and drive through it — the EXPLICIT dev override
./gradlew :desktop:run --args="--ble-info"    # adapter enumeration only (no discovery)
./gradlew :desktop:run --args="--selfcheck"   # the 89-check scripted gate (Files, Torrents and keyboard walks incl.)
./gradlew :desktop:run --args="--snapshot DIR"  # lens-truth PNGs of every surface
./gradlew :desktop:run --args="--epub-check"  # parse every book in ~/books
./gradlew :desktop:run --args="--host-only"   # content host alone (books + tmux + sync, no stack ever)
./gradlew :desktop:test                       # 9 tests: the BlueZ glue over the fake link
```

Preview: mouse wheel scroll · left click tap · right click double-tap · press-and-hold
long-press (release on let-go) · keys ↑/↓ Enter Backspace Space R · Tab lens · B both ·
-/= window scale (integer nearest-neighbour, default 4×, clamped to the screen).
The browser replica link is printed at start (`http://<host>:7403/?token=…`). Config in
`~/.damage/config.json` (books dir, ports, token — generated on first run and must match
`damage-secrets.properties` before building the APK — the phone host, the cached pair
addresses, the tmux hosts/quick keys/snippets/wait patterns, the qBittorrent URL and the
TorrentLeech credentials; the `Config` class in `desktop/Main.kt` is the key list).

Phone:

```
./gradlew :phone:assembleDebug
# -> phone/build/outputs/apk/debug/phone-debug.apk  (sideload on the Pixel 10a)
```

The APK's daily default is Target=glasses: it DRIVES the pair over BLE as the primary driver
(§19), fetches content from beardos over Tailscale, **copies each book locally on open**, and
falls back to its caches when the PC is unreachable. It serves the seam on :7402 (probe/claim)
and its replica on :7403. Distribution: `./gradlew :phone:stageApk` → `~/.damage/damage-wm.apk`
→ the G2CC `/setup` page. The SIM target remains the on-phone dev mode.

**The all-day daily driver (2026-08-31, `DAILY.md`):** `--no-preview` runs any mode headless
(no Swing/X — set before AWT loads); `:desktop:stageJar` copies the fat jar to the STABLE
`~/.damage/damage.jar`; the OpenRC service `/etc/init.d/damage` (supervise-daemon, `default`
runlevel, user `user`) runs it in `auto` forever. Deploy = `stageJar` + `rc-service damage
restart`; stop the service before any `:desktop:run` dev session (one central, one set of
ports). `DAILY.md` is the ops crib and the one-time phone sequencing.

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

- **`Persistence` is the sync substrate**: schema v2 with a per-key stamp, re-stamped only on
  real value change; `tryApplyRemote` is strict last-write-wins (equal values adopt the higher
  stamp silently and never re-apply); legacy stores migrate in place with mtime stamps.
- **`core/sync/SyncNet.kt`** rides the content port exactly like the tmux channel
  (`{"t":"sync"}` upgrade): handshake exchanges stamp maps + clocks (skew-normalized), newer
  records flow both ways, then live pushes; the client (`RemoteSync`, in the phone's
  `startStack`) reconnects keeper-style and re-handshakes every 5 min so a lost push always
  heals. `shell.settings` + `window.<id>` sync; `shell.state` (per-device UI) never does.
- **`Shell.postSync`** applies a record on the loop: freshen the key from the LIVE window
  first, then LWW, then live-apply (restyle for settings; restore + repaint for the focused
  window). The driving shell's state is newest by construction, so sync flows driver → idle.
- **The seam status probe** (`SeamProbe` / `Ctl t="status"`): a non-claiming question — "does
  the APK want the radio?" — answered by the phone from Target + liveness. An old APK answers
  `busy`, read as YES (never contend with an APK that cannot be asked).
- **Desktop `auto` = STANDBY**: one shared process-wide store feeding the sync channel and any
  stack; probe every 5 s; APK absent/idle ×2 → build a plain `ble` stack; APK back → stop it
  (the handback; the lease fails open and the phone re-choreographs). A BlueZ-less machine
  stands by as data host only, loudly. PC deploys no longer touch the display at all.
- Pinned by `SyncTest` ×6 (store LWW + migration, both-ways convergence + live push over a real
  loopback host, old-host refusal survived, freshen-beats-older in a live shell, probe answers
  without claiming).

## Confirmed on hardware — first light, 2026-08-30

The glasses run the CFW and the PC has driven them. `HANDOFF.md` §11 is the record.

- **`BlueZTransport` works** — scan by name, RIGHT then LEFT, MTU 247, notifications, write
  pacing, and **no bonding was needed**. It ran correctly the first time it ever saw a radio.
- **The whole choreography works**: prelude, capability gate, carrier CREATE, both leases, the
  warmup drop, then a painting shell.
- **Input works** over ring → glasses → `e0-01`: scroll, tap and double-tap all arrive.
- **The session keeper works** — two unplanned link ends, both recovered without help.
- **Ack latency measured at ~50 ms**, against the modeled 176 ms. ⚠ Idle shell: the floor, not the
  curve. See `HANDOFF.md` §11.1 before quoting it.

Three defects surfaced within minutes and are fixed: the ack **status enum** read as an error code,
the journal rewriting a closed stream, and inbound input never being logged. The first is the one
worth remembering — **the simulator modeled success as an absent field, so no offline test could
have caught it.** A model that errs toward permissive is worse than no model.

## The refinement wave (2026-08-31) — `HANDOFF.md` §12 is the full record

Chrome depth, coarse scroll, Reader folders/chapters/images, per-app height, the digital clock,
Settings directories, brightness + battery on the wire, the 4× preview — all live the same day.
The one trap worth restating here: **the switcher had not worked since first light because our source
filter discarded the unattributed events 9/10 (source 0)** — a test default that "helpfully"
supplies what the wire omits is a model erring permissive; inject what the firmware actually
sends. Do not reintroduce a source gate on events 9/10.

## The APK-mission prep (2026-08-31, HANDOFF.md §13 — before any phone-radio test)

What "the phone as the default driver" needs beyond shared core, mined from the G2CC Android app
(the heavily-tested reference) and from the seam's failure model. All built and battery-green,
and all of it has run on the radio daily since the phone's own first light later that day (§13.2):

- **The seam heartbeat** (`RemoteTransport.kt`, both ends): each side sends a bare `ping` every
  5 s; a side that has SEEN its peer speak the protocol treats 20 s of total silence as the link
  ending — loudly, into the existing hardened teardown, so a silent path death (Tailscale, the
  common case away from home) hands the glasses back to the phone shell in seconds instead of
  TCP retransmission's many minutes. A peer that never pings keeps the old TCP-event-only
  behaviour, so version skew cannot false-trip it. `SeamLivenessTest` (3 tests, raw-socket fake
  peers that go quiet WITHOUT closing) pins both directions and the skew guard.
- **The pocket-liveness trio** (each a G2CC factory finding): a PARTIAL_WAKE_LOCK while a
  GLASSES stack runs (the FGS type stops process termination, NOT Doze CPU throttling — G2CC measured
  delay() ticks gapping 13–28 s on a 10 s cadence; our lease renews every 45 s against the 90 s
  fail-open), the battery-optimization exemption (asked once at first run; its absence stays in
  the status line; a boot-time revocation raises a re-grant notification), and a `BootReceiver`
  (BOOT_COMPLETED + MY_PACKAGE_REPLACED → auto-start, only when Target=GLASSES and the exemption
  holds) so the default path survives reboots and sideload updates.
- **Scan hardening** (`BleTransport.scanForPair`): Bluetooth turning off mid-scan does not
  reliably reach `onScanFailed` — the await would park forever (G2CC's "scanning forever"
  class), and toggling phone BT is the documented at-work recovery, so a BT-state receiver now
  fails the scan loudly and the keeper rides the ON edge back in. A still-hunting scan is also
  re-issued every 20 min, under Android's ~30-min silent downgrade to opportunistic.
- **Distribution**: `./gradlew :phone:stageApk` stages the debug APK to `~/.damage/damage-wm.apk`;
  the G2CC server's `/setup` page grew a DamageWM box and a `/damage-apk` endpoint (additive
  twin of `/apk` — same Tailscale+token gate, mtime-stamped filename). (APK 3/0.3 at the
  time; versions have moved on — the gradle file is the authority.)

- ~~The phone's `BleTransport` has still never run on hardware~~ — **it passed its own first
  light later the same day (2026-08-31, first try) and owns the radio all day now.** It is
  written from G2CC's proven driver; the capability gate refuses any firmware without an
  `EVENCFW` string, so stock glasses cannot be painted even by mistake.
- Compass, IMU, wear detection: per `DESIGN.md` (§7) — compass cell draws a
  placeholder until the mode-10 feed exists; head tracking defaults OFF.
- ~~Texture caching (Babcock's in-progress firmware work)~~ — **it shipped**, see below.

## Torrents + the keyboard (2026-09-01, TORRENTS.md · DESIGN.md §4.8)

The second app-wave window, built whole on Adam's rule (*"every app … completely built to its
best state before we move on"*), and the fourth bespoke shell surface. `wm.damage.core.windows.torrents`:

- **`QbtClient`** — qBittorrent Web API **2.11** over `HttpURLConnection` (core runs in the APK;
  no `java.net.http`): `sync/maindata` (rid 0, always full — one request carries the list and
  the session line), `torrents/properties|files|trackers`, the 5.x verbs `torrents/stop|start|
  recheck|delete`, multipart `torrents/add`, `auth/login` only when credentials exist (beardos
  bypasses auth on loopback). Every key was read from the 5.1.4 source, never remembered.
- **`TorrentLeech`** — the tracker session (form login → cookie jar in `~/.damage/tl-cookies.json`;
  ONE re-login on a logged-out answer, paced to one login a minute inside `login()` itself on
  every path, and a refusal latch that fires only when the site answers with its login FORM —
  a maintenance page is paced, never latched; review round 4), the site's own JSON listing endpoint for browse AND
  search (`torrents/browse/list/…`, 35 rows a page), the torrent page parsed by a stdlib
  `Html` reader for its landmarks (info table · description · NFO · files · download link), the
  `.torrent` bytes fetched with the session and refused unless bencoded, the profile page read
  for five stats and nothing else. **Format drift is a loud `TlException("format changed: …")`**
  — never an empty list. The 40-category tree (9 groups) is a constant with its lineage.
- **`LocalTorrentsProvider`** (PC) — the poll loop (15 s idle; the fastest focused party sets
  the pace — the local shell and the phone are tracked separately), snapshot diffing into
  EVENTS (done / error / added / removed) with a monotonic sequence + a per-process epoch, the
  persisted announced set (`~/.damage/torrents.json`: a first run baselines silently, a restart
  announces what finished while the service was down, nothing announces twice), `xdg-open`.
- **`TorrentsNet`** — `TorrentsService` on the §16.10 window channel (`snap` with a version
  cursor: unchanged = no blob; events since a sequence within an epoch; every op) and
  `RemoteTorrentsProvider` (phone): its own paced poll (2 s focused / 15 s idle, woken by
  attach and focus), event replay, the channel's staleness first, the host's second.
- **`TorrentsWindow`** — TRANSFERS (activity sort: errors first, then downloads, checking,
  seeds, stopped; six filters incl. **seeding < 1 week** for TL's hit-and-run rule; rows =
  icon · name · 10-block bar · state word, the LENS carries the live numbers + a 12-block bar +
  an 8-column speed history; the cursor follows its row's identity across live snapshots) →
  the transfer MENU (Details — Refresh from the document · Start/Stop · Recheck · Open in
  Files (a `path:` deep link Files now accepts) · Open on PC · Delete · Delete + files behind
  a double confirm with the unrecoverable row at index 2) → DETAILS (a document: state, speeds, ratio, peers, dates, paths, the file
  list) · the wrap-end Torrents MENU (Browse · Search via the keyboard · recents · Filter · Sort ·
  Seeding < 1 week · Refresh · Stats) → CATEGORIES (Newest + 40 rows with group icons) →
  LISTING (endless pages, a loading pseudo-row, paced retry in place, FL mark, seeders/leechers
  with drawn arrows) → TORRENT (a document: info, description, NFO in mono, files) → the add
  MENU (Add / Add stopped behind a confirm, Open on PC). Notifications `torrent · done/error`
  deep-link to `t:<hash>` and are gated by Settings → Torrents (Notify · done / errors / Poll /
  Size). `open("t:<hash>")` and `open("tl:<fid>")` synthesize the level path. A replica-typed
  line searches. Face Fira Sans; icon theme `qbittorrent` with a drawn fallback.
- **`KeyboardSurface`** (`shell/`) — DESIGN.md §4.8: the wireframe keyboard, row-then-key with
  wrap on both axes, stay-in-row after typing, QWERTY/abc from Settings → Global → Keyboard,
  the symbol layer, Shift once/lock, caret editing (←/→/Del/Clear), a panning text line that
  marks its cut, up to two requester rows of LIVE keys (Tmux's non-character quick keys,
  harmless ones at each row's head), the draft handed back on cancel. `ShellServices.openKeyboard(spec, owner)` — the menu's refusal rules; the
  shell routes gestures to it, cancels it under the wheel / silent / relayout / an emergency
  (draft kept), defers ordinary notices behind it, commits a replica-typed line through it.
  Requesters: Torrents Search, Tmux "Type…", Files Rename / New folder (pre-filled).
- **Settings**: the unused Global rows `Notify · SMS/Mail/Music` are gone — each app's toggles
  live in its own category from now on (`WINDOWS.md` §1); Global keeps `Notify · Damage` and
  gained `Keyboard`.
- **Harnesses**: `ScriptedTorrents` (desktop) drives the selfcheck's torrents walk (transfers →
  menu → details → browse → listing → page → add-confirm → keyboard search → the done
  notification, plus the ink budgets: transfers 9.0 %, details 6.4 %) and the snapshot scenes
  15–22; `TorrentsTest` ×7 (the client against a fake Web API, the adapter against fixtures incl.
  drift and expiry, the provider's diff/baseline/persistence, the window grammar, persistence +
  continuity, the remote provider over a real loopback host) and `KeyboardTest` ×22.

## Tmux (2026-08-31, TMUX.md — all refinery verdicts locked, built in one pass)

The first app-layer window past Reader, and the first CANVAS window. The glasses are a
viewer/controller of the REAL tmux server via discrete commands (`=session:` exact targeting,
no `-C` attach — the G2CC Phase-5 safety shape); `wm.damage.core.windows.tmux`:

- **`Sgr`** parses `capture-pane -e` into styled cells AND styled runs: 16/256/truecolour by
  luminance onto the 16 grays (foregrounds floor at 3 — readable beats faithful),
  bold/dim/underline/reverse, unknown escapes counted never eaten. 🔴 **The GRID is RETIRED
  (2026-08-31 evening, Adam: "it's just text ... kill the grid entirely"): `FlowRender` is the
  view** — the provider captures NORMAL panes with `-J` (logical lines), flow wraps them at the
  content width through the per-app font/size/style (all three now really apply — on the grid,
  Font size compensated to zero by construction), rule lines collapse to drawn rules, a small
  tail marker stands in for the cursor, the tail anchors terminal-style. **`TermRender` (the
  grid, fractional pitch, inverted cursor, context rows) survives only as the alternate-screen
  fallback** — `#{alternate_on}` panes capture row-exact and render through it. History is the
  SAME flow over the frozen deep capture (5 display lines/notch, position rail, scroll-down at
  the live edge returns to live). Capture pacing defaults to 1 s, Settings → Tmux → Update
  (0.5/1/2/5 s) adjusts it live over the wire (`tpace`) and persists.
- **`LocalTmuxProvider`** execs `sh -c` scripts — ONE per host per tick (status 2.5 s, capture
  1 s pushed on change — the Update setting adjusts it live), so an ssh host (verdict 1:
  multi-host; hosts are opted in via `tmuxHosts` when actually alive — the default is empty
  since §15) costs one round trip per tick. Waiting-pattern EDGE alerts (verdict 3: on
  for all, per-session mute, GLASS only); `g2-N` auto-naming; literals cross ssh single-quote
  escaped. ssh gets BatchMode+ConnectTimeout=5 on connection ESTABLISHMENT only — the
  equivalent of a refused connect, retried next tick; every loop is pacing, no timeouts.
- **`TmuxNet`** rides the CONTENT port: `{"t":"tmux"}` after the hello turns the connection
  into a push channel (status/frames/alerts + id-correlated requests). `RemoteTmuxProvider`
  (the phone) reconnects keeper-style, re-asserts its subscription, and surfaces "PC
  unreachable Ns" as the §10.5 staleness line. Config (quick keys/snippets/patterns) is served
  WITH the session list, so `~/.damage/config.json` on the PC is the one tuning point.
- **`TmuxWindow`** — the grammar: SESSIONS (waiting pinned, lens shows the tail line) →
  LIVE (canvas; **scroll-up IS scrollback**, tap descends) → HISTORY (a FROZEN snapshot rendered
  through the SAME live fit — same face/size/width, colours kept, 5 rows/notch, a slim position
  rail; the notch that reaches the live edge RETURNS TO LIVE — revised same day: the old
  reading-size DocView read as "a different font at a different size", his words) → KEYS (verdict 4 + Left/Right,
  Adam 2026-08-31) → SNIPPETS / WINDOWS (viewing a window targets `=s:idx` — never `select-window`, which is
  an explicit Session… action) / SESSION_ACTIONS (mute · Fit pane 64×22 · select · rename ·
  the `kill-session` confirm). **Typed text always stages a TYPE_CONFIRM** (run = literal + Enter, the G2CC
  2026-06-18 semantics); every provider failure rides the title notice. Settings live in the
  Settings window's **Tmux category** (verdict 6): Context rows · Alerts · Size (default 480).
- **Shell additions**: `CanvasView.onScroll/onTap` (routed like DocView's),
  `DamageWindow.onTypedText` + LOUD shell refusal when nothing accepts,
  `Transport.injectText` → `TransportEvent.Text` across every transport and the seam
  (`"typed"`), `ShellServices.docContentHeight`. **Typed-text entry points**: the desktop
  preview (key T), the browser replica's text bar, the phone strip's `type` button — all ride
  the transport, so they reach whichever shell drives.
- **Harnesses**: `ScriptedTmux` (deterministic provider) drives the tmux selfcheck checks (part
  of the 89-check gate) and four snapshot scenes (09b–09e); `TmuxTest.kt` holds 27 core tests in
  six classes — `SgrTest` ×4, `TermRenderTest` ×4, `FlowRenderTest` ×6, `TmuxProviderTest` ×3,
  `TmuxNetTest` ×1, `TmuxWindowTest` ×9 (SGR, fit at both height modes, cursor inversion, the
  flow view, provider parse/edge-alerts/quoting, the wire round trip incl. pacing, the full
  window grammar, the alternate-screen fallback, persistence). Building the scenes also caught a LATENT snapshot-harness
  drift: the fixed two-double-tap walk back to Main broke silently when the shelf grew folders
  — the walk now ascends deterministically and the waits are labeled.

**On-glass verdicts still owed (`REMINDER.md` item 3):** the flow view's default size/wrap feel (the grid
retirement removed the fit-80/7.6 px question outright — Font size is a real knob now),
quick-key order, alert-pattern tuning against real Claude sessions, ssh-host latency feel.
The pixel path is the architecture; texture-cache glyphs stay a later optimization behind
`REMINDER.md`'s still-unmeasured items 19–20.

## User typography + per-app depth (2026-08-31 evening, Adam's ask — a §Type reversal)

Settings grew user-directed type (`core/text/Style.kt`): **Global → Font / Font size / Font
style** restyle CHROME AND MAIN (a recorded reversal of §Type's "the system face is not
negotiable" — his call), and **every app category gets Font / Font size / Font style / Depth**
rows. Each candidate option previews IN ITS OWN font while cycling (`HostSetting.optionFont`
returning `raw=true` specs the transforms cannot restyle). One mechanism carries all of it: a
`StyleTransform` rewrites every `FontSpec` at the rasterizer seam — the shell's chrome surfaces
draw through a `StyledText` wrapper with the global transform (SYSTEM-face swap + scale +
style force), and every window routes its measuring/drawing through its own per-app transform
(`DamageWindow.styleTransform` → the `styledText()` chokepoint; Reader and Tmux — including
`TermRender` — go through it), so wrap and render agree by construction. Content scaling MOVED
here from the platform adapters (their `contentScaleProvider` is retired to 1.0 — double-scaling
was the hazard). **Depth**: the focused app's content plane uses its own depth (default 8, the
0/4/8/12/16 ladder) while the bars and Main stay on the global setting — app content pops
forward of (or parks behind) the chrome. At every default the whole feature is render-neutral
(snapshot-verified byte-identical below the clock). `StyleTest` ×5 pins the transform edges,
clamps and the raw passthrough.

## The texture cache (2026-08-30, CFW `a5d1c31`)

The firmware grew a **64 KiB lease-scoped texture cache** and three draw modes. The wire and
model layers are built and green; the compositor has not adopted them yet, deliberately.

**Built:**

- `wire/CfwModes.kt` — `cleanup()` (11), `cacheUpdate()` (12), `drawImage()` (13),
  `drawCachedText()` (14), plus `options()` and `xAdjust()`. Every builder refuses what the
  firmware would reject in silence: mode 13's exact 7-byte body, mode 14's `8 + strlen`, byte 0
  and bytes > 127 in a string, writes past the 64 KiB end, an adjust outside −10…+20.
  `batch()` now accepts sub-modes 3/6/9/13/14/15.
- `wire/TextureCache.kt` — atlas layout: image encoding (`[w][h][RLE of exactly w*h pixels]`, no
  row pad), a deduplicating offset allocator, 96-entry font tables, and chunked mode-12 messages.
  Two invariants worth keeping: **offsets 0–1 stay zero** so an unfilled table entry points at a
  guaranteed-rejected image, and **every table entry is filled** (unmapped characters get a
  visible tofu box) because the firmware validates a whole string before drawing any of it — one
  unmapped character would otherwise drop the entire line.
- `gfx/Codec.kt` — `Rle.encodeLevels`/`decodeLevels`, the same token alphabet over a bare pixel
  run. The packed-row `Rle.encode`/`decode` are untouched, so `RleParityTest`'s pinning still holds.
- `sim/GlassFirmwareSim.kt` — modes 11/12/13/14 modeled byte-exactly: the lazily allocated cache,
  the lease gate, whole-list validation before any write, the LUT with its integer truncation,
  transparency tested pre-LUT, per-glyph advance by image width. Mode 15 is **refused loudly**.
  The cache is dropped on lease expiry and on mode 11.
- `TextureCacheTest` + `AckStatusTest` — 44 tests today.

**Two corrections that came out of reading the new source, both already applied:**

- 🔴 The sim used to attach an `EventSource` to long-press events. The firmware never does: the
  stock sender writes that field only for `EventType` 0 and 3, verified at instruction level. The
  model now omits it (`EvenHubMsg.reportsSource`), so nothing can come to depend on a field that
  is absent on glass.
- The capability gate now runs against the real `EVENCFW/16` string. `img576` and `compass10`
  vanished from it while their features live on, so `REQUIRED_CAPS` stays at the five that matter
  and version checks go through `SettingsMsg.contractVersion`.

**Not adopted yet, on purpose.** The compositor still emits pixel deltas only. Turning text into
mode-14 draws changes the emit strategy and the entire cost model. The ack curve it is priced
against is now **measured** (`overview.md` §5.2, 2026-08-31: `ms ≈ 60 + bytes/50`); what still
gates adoption is the on-glass check of modes 12/13/14 against the sim (`REMINDER.md` items
19–20). Mode 11 in `stop()` is held for the same build: its value is freeing the cache, and
`stop()` is a five-round-hardened path not worth disturbing for nothing.

## Review hardening (rounds 2–8, 2026-08-24)

After the first build, seven rounds of independent review (fresh reviewer
agents per subsystem, every candidate verified by trace, timing or pixel
simulation before a fix) found and fixed ~70 real defects. The mechanisms that came out
of them are load-bearing and easy to break by accident:

- **Compositor per-lens model.** The compositor reasons per lens, not in
  nominal rects. It keeps an expected shadow of what each lens shows, renders
  the per-lens TRUTH of the nominal frame under the plane map (the nominal
  frame is the transparent base every shift may spill over — §3.3's insets;
  each region vacates its nominal area to black, the seam; region pieces
  render at their shift far to near, the nearest wins), diffs shadow against
  truth on the 4×2 damage grid, merges the differences toward the pipelined
  rect budget (coarsened by row bands first so merging stays cheap; within a
  piece, then across pieces of one disparity but never across a pixel of
  another plane; a final priced pass merges neighbours whose compressed
  union is cheaper — §5.1, §8.2's "1–3 rects"), and emits whatever closes
  the gap: nominal deltas at their disparity (split when a delta's bytes would
  exceed a mode-8 sub-message's 16-bit length or the bytes left in the
  batch; a keyframe past the sub-message length ships bare), black stereo
  pairs for seam strips BOUNDED TO THE SCANNED AREA (the round-2 L2 fix —
  `L2ProbeTest`). Every planned op is applied to the shadows as
  it is planned, so its effect on the OTHER lens (a far piece spilling under
  a nearer one) is seen and repaired in the same flush, in later-wins order.
  What the 16-fid ring or the batch's byte cap (bmp_max) cannot carry stays
  dirty for the next flush, which continues at the wide aim. A lost flush
  marks the per-lens cells it touched UNKNOWN — transmitted again from the
  truth, with the marks following any copy applied since as a coalesced
  frontier — because no byte snapshot can say what the glass holds once
  other flushes have landed around it. Plane changes, seam cleanup, keyframe follow-ups and reclaims
  are not special cases — they are differences between shadow and truth.
  `LensOracleTest` pins it: after every flush the belief equals the firmware
  model's lens panels, and at rest each lens equals an independently written
  truth, across depth 8/12/16 and every shell transition; `Round5Test`,
  `Round6Test` and `Round7Test` add lost flushes, cell noise under a box,
  text-shaped damage economy, oversize payloads, rollback after many copies
  and the batch byte cap. A frame the firmware can never accept
  (three failed keyframes) halts the pump with one notice until the content
  changes.
- **Transport session lifecycle.** Queued work carries a session epoch;
  `stop()` and `onLinkDown()` bump it and SWEEP (a failed `start()` sweeps without bumping): pending
  acks fail, window permits return, both queues drain loudly, a start parked
  on the capability gate is answered with a sentinel and refuses. A flush
  never spans the 0xFFFE→1 fid wrap (pre-clear + restart); a failed encode
  hands its fids back; completions leave in submission order; msgId cycles
  1..249. The window-full-no-ack stall is REPORTED as a fault, never acted on.
- **Shell.** start/stop serialize on a mutex (a stop during start waits and
  never saves defaults over unread state). A notification box is LIFTED
  (its under-snapshot restored) before any slide steps beneath it and
  repainted after. Every dynamic chrome string is sanitised to the locked
  glyph set and fitted with the continuation mark.
- **Content.** Host reachability is decided in one place
  (`RemoteContent.withHost`) with attempt ordering; local disk failures never
  read as "PC gone"; the cache keeps the listing's real extension.

## Verification

- `./gradlew :core:test` — 221 unit/integration tests (2026-09-01 evening added
  `TorrentsTest` ×7 and `KeyboardTest` ×22 — the Torrents build and its four review rounds —
  and a `GeometryTest` pin for the chrome tweaks; 2026-09-01 added
  `SubstrateTest` ×10 — incl. the Reader CONTINUITY gate, the stamp-0 baseline pin, the
  poisoned-restore pin, the settings-echo pin — `FilesTest` ×8 incl. the pdfpage-restore and
  emergency-first pins, `ReviewRound1Test` ×6 incl. the 4-byte UTF-8 seam, `L2ProbeTest` (the
  compositor seam-clamp regression), and the R6 tmux pins: deep-park re-arm + the
  peer-left-session drop) (§19 added `SyncTest` ×6: the stamped
  store's LWW, migration, the sync channel over a real loopback host, the shell's
  freshen-then-apply, the seam status probe; the flow rework added `FlowRenderTest`
  ×6 plus the pacing/alternate-fallback window tests and the wire-pacing round trip —
  `TmuxTest.kt` holds 27 today across its six classes;
  2026-08-31 added `TmuxTest` ×16,
  `SeamLivenessTest` ×3, `HandoverTest` ×4, `StyleTest` ×5 and the tmux freeze/bleed regressions; the refinement wave added
  `BatteryBrightnessTest`, `EpubChaptersImagesTest`, and the wire-true source-0 injections in
  `LongPressTest`; the finishing build added
  `MirrorTeeTest`, `PreludeTest`, `DivergenceTest`, `ShellKeeperTest`,
  `WheelAndHostSettingsTest`, `SeamMirrorTest`, `SeamSessionTest`,
  `ReplicaServerTest`, `PathTransportTest`, the review rounds' regression
  tests inside them, and `LongPressTest` — the 2026-08-30 grammar); `./gradlew :desktop:test` — the BlueZ glue over a fake
  link (9). The first stage's 47: RLE parity against the
  Python reference implementation, CRC vectors, the geometry/fid rule fixtures
  shared with `tools/lint.py --selftest`, full pipeline round trips through
  the sim (stereo divergence per lens, mode-8 scroll batches, duplicate-fid
  skip, msgId-255 silence, lease expiry, warmup drop, out-of-order aborts),
  the shell behaviour/persistence gates, and `Round3Test` (a fid wrap inside
  a flush, a busy plane map's keyframe within the fid ring, a plane change
  with no pixel change, stop-during-start, same-instance transport restart).
- `--selfcheck` — the whole stack scripted end to end with real fonts,
  asserting ink budgets, input grammar, persistence byte-behaviour, and zero
  faults/failed flushes/sticky flags.
- `--snapshot` — renders what the LEFT LENS PANEL holds (post-wire truth,
  through pack → RLE → deflate → fragmenting → sim firmware → shadow), at
  true 1x. This harness caught the stereo vacated-strip ghost within minutes
  of existing.
- `tools/lint.py` still gates the repo at 0 findings; its geometry rules are
  mirrored 1:1 (same rule IDs) in `wm.damage.core.geom.Geometry`, and
  `GeometryTest` pins both to the same fixtures.
