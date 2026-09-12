# HANDOFF — the build record

Newest first. A fresh session starts at `REMINDER.md`; this file holds the decisions, lessons and
measured facts behind the current state. Precedence: `overview.md` on facts, `CLAUDE.md` on rules,
`DESIGN.md` on shell design; `IMPLEMENTATION.md` says what runs; `DAILY.md` is the ops crib.

| § | what |
|---|---|
| 42–44 | Journals read, page traffic asleep (§42) · Feed built (§43) · G2CC's server retired (§44) — 2026-09-07 → 09-10 |
| 37–41 | The latency plan (§37) · the Silent-Mode wake rebuild (§38) · the showdown line (§39) · the plan built (§40) · the cache on every plane + the depth ladder (§41) — 2026-09-05/06 |
| 31–36 | Canvas shift (§31) · the latency pass (§32) · live measurement from the PC (§33–§35) · the silent glasses (§36) — 2026-09-05 |
| 27–30 | Whole-codebase reviews with live walks; the truth oracle a standing gate — 2026-09-04/05 |
| 24–26 | Music (§24) · the 2026-09-03 review (§25) · Hold'em (§26) |
| 19–23 | **The topology contract (§19)** · §16 settled (§20) · Files (§21–§22) · Torrents + the keyboard (§23) — 2026-08-31 → 09-01 |
| 0–18 | Pre-flash era, install, first light, the launch-day arc — 2026-08-24 → 08-31; facts and traps only |

## 0–7. The pre-flash build era (2026-08-24/25) — compressed

Kotlin core/desktop/phone built against the byte-exact firmware model, five review rounds (~70
defects); `IMPLEMENTATION.md` (module map, "Review hardening": per-lens truth/shadow, session-epoch
sweeps, fid-wrap and msgId discipline, the lease as correctness) and the code are the record.
Adam's target: *"a fully working DamageWM that only needs me to flash the glasses firmware and
install the app and poof it works"* — with a pixel-perfect replica on PC and phone, never Even's
simulator. Achieved (the mirror-sim tee). G2CC (`/home/user/G2CC`) is read per `WINDOWS.md` (facts only, never edits);
its BLE docs remain wire-source #3.

## 8. The finishing build (2026-08-25) — decisions that still govern

1. The connect prelude is the CFW reference's single sid-0x01 app-launch (own implementation);
   G2CC's sid-0x80 `AuthSequence` is never used.
2. Arbitration, Adam (§8.1 d3+5): *"The default will be phone app + Home PC over internet to
   phone, if internet to phone is lost fall back to phone app only, if phone app is not up fall
   back to Home PC over BLE directly … Home PC is always the best case to constantly be trying
   for…"* The build read "best case" as "the PC SHELL drives"; corrected 2026-08-31 — best case =
   the PC AVAILABLE TO PROVIDE DATA. **§19 is the standing contract.**
3. Decision 6: a notification arriving while the wheel is open waits behind it; a shown box
   requeues unread. Extended 2026-09-01: the context menu and the keyboard defer the same way; an
   EMERGENCY cancels any of the three (§22, §23).
4. MIT dependencies are fine for PC-direct BLE (`bluez-dbus` + `dbus-java`); the clean-room rule is
   about GPL code.
5. Arbitration decides by ENGAGEMENT; capability refusal is the only terminal condition; everything
   else reconnects forever with pacing, never timeouts.

## 9. The first install plan (2026-08-30) ⛔ SUPERSEDED

Written against g2flash `877c8d9` and the archived 2.2.6.11 image; §10 replaced it. Its one lasting
artifact is the offline `verify_cfw.py` discipline.

## 10. The firmware install — DONE 2026-08-30

Stock 2.2.2 left the glasses 2026-08-30; it is not in the public archive. Every other version (the
CFW included) remains installable.

- **Installed:** g2flash `a5d1c31`'s own build (sha `d4054ab1…`), the image Faceclaw 0.6.1 ships
  (26 pinned patches), with the texture cache and a fix for a latent context-pointer defect in the
  older archived image. Both unrecoverable-failure classes (unbounded MRAM program, Thumb-bit
  interworking) checked clean; `research/verify_cfw.py` proves it reproducible offline — run it
  before any flashing conversation.
- 🔴 It reports `2.2.6.10`, indistinguishable from stock: detect the CFW by `EVENCFW/`, never the version.
- **The flasher was patched first** (Adam's call): `recover_session()` ran `authenticate()` before
  `_reset_seq()`, so a recovery magic ≥ 128 emitted a malformed varint (~a coin flip per recovery).
  One line moved; proven offline; reported upstream; never fired (zero resends).
- **Dry-run staircase** (`--stop-before heartbeat` → `file_check` → `flash`) ran clean first; it
  proved an unbonded Linux host connects and authenticates with no pairing prompt and the sid-0x80
  auth exchange works as captured.
- **The write:** both lenses, all six components, 4,339,457 B in 171 s per lens, zero resends, exit
  0; every END verify returned status 8 (UPDATING, in the OK set); OTA goodput ~25 KB/s PC-direct
  (`overview.md` §5.1).
- **Arm addresses (Adam's pair, serial 32):** left `D8:AE:E7:C1:FA:4D`, right `E4:87:77:65:CD:50`,
  both public. The `C4:…` addresses in `overview.md` §9.2 are a third-party user's.

**§10.13 The R1 ring updated the same day** to 2.2.6.0009 via `research/r1_dfu.py` (Nordic Secure
DFU; facts from SybilSight's MIT `r1Dfu.js`); zero retries. 🔴 The DFU cleared the ring↔glasses
BOND, which a re-flash would not restore; the Even app re-registered it (the `91-20` message) —
any future DFU on a paired device counts re-pairing as part of the cost, confirmed possible BEFORE
starting. The bootloader advertises `B210_DFU_<suffix+1>` at MAC+1 — match by UUID `0000fe59` + a
`DFU` substring, never a fixed name. The running ring version was never read back; the signed-image
acceptance is strong evidence, not a version read.

## 11. First light — 2026-08-30

The PC drove the glasses within an hour: scan → both arms → MTU 247 → prelude → capability gate
(`EVENCFW/16`) → carrier → lease → warmup → `driving via ble`. Closed on hardware: PC-direct BlueZ
first try; link-drop recovery (two unplanned LEFT drops, keeper resumed both); the
ring→glasses→`e0-01` event hop. **Measured:** CFW ack floor ≈ **50 ms** against the 176 ms stock
figure. (The next day's curve `ms ≈ 60 + bytes/50`, n=1,488, describes four hours of one PC-direct
session — price with §31.1 / §33.1.)

Three defects, all ours: (1) **the ack status enum** — `ImgResCmd` f8 is a per-operation STATUS
(4 = image success), not "non-zero = error"; the transport refused the glasses' own success ack;
the sim modeled success as an absent field; `AckStatusTest` pins both sides. (2) **The journal
write pileup** — a reopened-after-stop stream failed EVERY line; unrecoverable errors report once.
(3) **Input was unobservable** — no inbound gesture was logged; an input path you cannot observe is
a silent failure.

🔴 **Event semantics:** event 10 (`LONG_PRESS_RELEASE`) fires after almost every swipe; event 9 is
the actual long-press. `gesture_fwd.c`'s names describe hook sites. `DESIGN.md` §1.2's
bare-long-press no-op keeps stray 10s harmless.

## 12. The refinement wave — 2026-08-31, live deploys

The whole `REFINEMENT.md` queue deployed while Adam wore the glasses (verdicts there; rules in
`DESIGN.md`): chrome depth inset, coarse scroll (5 lines/notch, acceleration off — later reversed
for the Reader, `REFINEMENT.md` §3b), Reader folders/chapters/images/reset/descenders, per-app
height, the digital clock top-right (an analog/top-left attempt reversed), Settings as directories,
brightness + glasses battery on the wire, the 4× preview.

- **The eaten-gate class.** A single-send await in flight while the firmware tears down a PREVIOUS
  session's context is never answered — the capability query, then the carrier CREATE. Both re-ask
  on a 2 s pacing tick (`CfwTransportBase`); any gate that parks the same way gets the same
  treatment. The deeper cause (sessions ending without FB_RELEASE/mode-11 cleanup) is the standing
  argument for mode 11 in `stop()`, deliberately unadopted.
- **The switcher was inoperative from first light because of OUR source filter**: events 9/10
  arrive with `EventSource` ABSENT (source 0) and the ring-only check discarded them;
  `LongPressTest` passed because the harness supplied the source. Rule: **a test default that
  supplies what the wire omits is a model erring permissive — supply what the firmware sends.**

## 13. The APK mission (2026-08-31) — DONE

Adam: *"Make sure it can do everything the PC system can do, including connecting to the PC system
and using both, as well as falling back to phone-only and PC-only, just like in the design."* All
`DESIGN.md` §10 configurations run daily. Shipped: the seam heartbeat (a lost path hands back in
~20 s), the pocket-liveness trio (PARTIAL_WAKE_LOCK, battery-exemption ask, boot auto-start), scan
hardening (BT-off loud; re-issue under Android's 30-min downgrade), `./gradlew :phone:stageApk` →
`~/.damage/damage-wm.apk` → the `/setup` page's `/damage-apk` (G2CC's page then; Damage's own since §44).

**ONE CENTRAL AT A TIME.** Stop the `damage` service before any `:desktop:run` dev session
(`ble`/`remote` are a second central); G2CC's bridge stays Disconnected.
`le-connection-abort-by-local` on connect means another central holds an arm.

## 14. Tmux on glass — designed and built in one day (2026-08-31)

`TMUX.md` (scroll-up IS scrollback; typed text behind a confirm; multi-host over ssh). The GRID it
shipped was retired the same night — §18.

## 15. The daily driver prepped (2026-08-31) — `DAILY.md`

`--no-preview`; the staged `~/.damage/damage.jar` (`:desktop:stageJar`, temp + ATOMIC move — an
in-place truncation broke the running service's lazy class loads); OpenRC service **`damage`**
(supervise-daemon, default runlevel). Deploy = `stageJar` + `sudo rc-service damage restart`. Two
defects: a `polling` field declared after the init block racing it; a down ssh host serializing
every other host's status poll (parallel + skip-if-in-flight).

## 16. The session outlives the driver (2026-08-31) — zero-blink handovers

A WiFi→LTE edge cost two visible teardowns; Adam: *"the whole point of this hybrid adaptive system
is to stay connected."* The BLE session's lifetime is decoupled from the driver's (G2CC's
ConnectionManager did the same): `Shell.stop(stopTransport=false)` yields; `start()` ADOPTS a live
session (one wide flush); the seam server grants adoption, treats a driver's "stop" as claim
release, never ends the owner's session on driver loss. `HandoverTest` pins `preludeAcks == 1`
across claim → release → re-claim, silent driver loss and the WiFi edge. Mixed versions degrade to
tear-and-rebuild.

## 17. User typography + per-app depth (2026-08-31, late)

`core/text/Style.kt`'s `StyleTransform` rewrites every `FontSpec` at the rasterizer seam — chrome +
Main through the global transform (a recorded REVERSAL of §Type's fixed-system-face lock,
`DESIGN.md` §0/§Type), each window through its per-app transform (`styledText()`). Per-app depth
(revised §41.2). Two tmux fixes: a bottom-row AA bleed guard; a failed history capture no longer
strands `histLoading` (falls back to LIVE, loudly).

## 18. The tmux grid is retired — the FLOW view (2026-08-31, night)

Adam: *"The whole unpleasant way it looks is entirely because of the grid. Lets kill the grid
entirely."* Measured cause: `fitFor` compensated any Font-size change to exactly zero on a fixed
pitch. Built (`TMUX.md`): `FlowRender` + `Sgr.parseRuns` — panes captured `-J`, wrapped at content
width through the per-app transform, SGR as styled runs, rules as rules, tail anchoring;
`TermRender` survives ONLY for `#{alternate_on}` panes; history is the same flow over a frozen
capture; capture pacing 1 s (`tpace`, additive on the wire). Cost: column alignment survives only
for lines that fit unwrapped. The same night's deep-review build fixed keeper-restart loop
accumulation, a stale glyph-coverage cache, a reassembler malformed-fragment throw.

## 19. The arbitration correction + last-write-wins sync (2026-08-31, night)

Adam, correcting §8.1's reading: *"When I said the best path should always be home PC I meant
having it available to provide data to the apk so that DamageWM is fully functional with all its
apps."* The intent: *"the apk on the phone to be the primary driver, with the PC server pushing
data to it when available, with the ability to fall back to apk-only when PC unavailable, and in
the rare case the PC is available via BLE but the apk is not, the PC can drive the glasses
directly. … data that can differ between apk and PC should be automatically synced as soon as both
systems can communicate, with the most recent data having priority."* This restores `DESIGN.md`
§10.1 row 1 (transport=phone, shell=phone, content=PC); the build's inversion is retired.

### 19.1 The corrected contract

1. The phone shell is the PRIMARY driver, always, while the APK is up with Target=glasses.
2. The PC is the data provider (content host + sync); its reachability decides how CAPABLE the
   phone shell is, never who drives.
3. APK-only fallback when the PC is unreachable — cached content, staleness said (`DESIGN.md` §10.5).
4. PC-direct BLE only when the APK is not available (seam unreachable, or Target≠glasses); the PC
   hands the radio back when the APK returns.
5. State that can differ syncs automatically, most-recent wins (`EXPLOSION.md` §16.4).

### 19.2 Design (fixed — do not re-derive after a compaction)

All of it is code (`SyncTest`/`SubstrateTest`; `IMPLEMENTATION.md`).

- **Seam status probe (`RemoteTransport.kt`):** `Ctl(t="status", token)` on :7402 →
  `Ctl(t="status", ok=<wantsRadio>, connected=<driving>, detail)`, then close; no claim (`RemoteTransportServer.statusFor`).
  `SeamProbe.probe()` → `Unreachable` / `Reachable(wantsRadio, driving)`; an OLD server answers
  `busy "bad token"` → `wantsRadio=null`, treated as wants the radio. The probe's bounded
  connect/read is a liveness decision of the seam-heartbeat class, not a work-abandoning timeout.
- **Desktop standby (`Main.kt`):** `auto` builds no driving stack; probe the phone seam every 5 s;
  phone wants the radio (or unknown) → no PC stack (the lease fails open ≤90 s); phone absent or
  Target≠glasses for **2 consecutive probes** → start a plain `ble` DesktopStack. `--transport ble | remote | sim`
  stay explicit manual modes (`remote` keeps claim/adopt as the dev override).
- **Sync store (`Persistence.kt`):** schema v2 `{"__v":2, "records": {key:{"v":blob,"t":stampMs}}}`;
  a legacy file migrates with stamps = its mtime. `put()` re-stamps ONLY when the value changed
  (max(now, old+1), monotonic per key) — otherwise LWW degenerates to "whoever saved last wins
  everything". `tryApplyRemote(key, v, t)`: strictly-newer → store silently + save; equal value,
  different stamp → adopt the higher stamp silently; else refuse. Stamps compared after
  per-connection skew normalization (each handshake carries the sender's clock).
- **Sync wire (`core/sync/SyncNet.kt`):** on the CONTENT port; a connection sending
  `{"t":"sync","stamps":{…},"clock":…}` after the hello becomes the sync channel; the server
  (`SyncNet.serve`, `ContentHostServer(sync = SyncPeer)`) answers `syncok`; both sides live-push
  `syncrec`. The client (`RemoteSync`, phone) reconnects at 15 s pacing and re-handshakes every
  5 min. Synced: `shell.settings` + `window.<id>`; `shell.state` NEVER syncs. An old host closes on
  the unknown request — the client logs once and keeps its pacing.
- **Shell live-apply (`Shell.postSync`):** on the loop, freshen the key first (LWW compares against
  what the user sees), then `tryApplyRemote`; if accepted: settings → `applySettings`;
  `window.<id>` → `restoreState` (+ `syncLayout()` and repaint when focused with the wheel closed).
- **Hosts:** PC — ONE process-wide store (`~/.damage/state.json`); phone — `RemoteSync` joins
  `startStack`. A PC `sim` session shares the PC store by design; harnesses use scratch dirs.

### 19.3 Notes that outlived the build

All of §19.2 is CODE.

- An EQUAL value never reports "applied" whatever its stamp.
- The startup micro-race is CLOSED (2026-09-01, §22): `startLocked`'s tail posts a reconciliation
  pass (`SubstrateTest`); §22 added stamp-0 baselines, per-session `subReported`, per-item re-apply.
- **Ring battery: CLOSED (2026-08-31, Adam's call), probe reverted.** The stock sid-0x91 relay
  never fills RingRawData, the ring offers no standard Battery Service, its vendor link uses a
  custom checksum, Faceclaw does not read it anywhere — only the closed Even SDK does. The R cell
  stays blank (`CLAIMS.md`/`CAPABILITIES.md`). Lesson: cost/benefit a cosmetic gauge at the FIRST
  finding; check the reference implementation first.

## 20. The general-contract session (2026-09-01) — §16 SETTLED, recorded, no code

- **`EXPLOSION.md` §16 rewritten with statuses + the build order:** 16.1/16.2 the deep-link verb
  (`open(target)`, opaque per-window targets, push-on-hand-off, never on preview); 16.3 per-window
  user config in the SYNCED store; 16.4 **Adam's top priority** — *"an always-active session that
  can be continued seamlessly from every device … 100%"* — per-item sub-records, the §19.3 closure,
  a per-window continuity test, content continuability before the first conversion; 16.5 the
  notification signature (source, coalesce key, body, target, urgency); 16.10 ONE generic window
  channel with multi-backend providers (switch only if actively playing, switchback deliberate,
  summary names the live backend); 16.11 the shared kit (fit-with-▸); 16.12 Title short-by-design
  + the NO-TRUNCATION wording (content vs handles); 16.13 scheduled work (LWW `fired` stamp —
  duplicate notification possible, missed fire never).
- 🔴 **B612 is NEVER a default — final.** Adam: *"It looks like shit, let it go."* Advocacy notes
  neutralized (`DESIGN.md` §Type ×3, `EXPLOSION.md` ×4); a curated font-library expansion stays
  option-only (`DESIGN.md` §Type).
- Main's focused lens icon goes band-height (56 px class), `DESIGN.md` §4.5b; the icon-quality pass
  moves to the front of the app wave; watch Main-resting ≤ 5 % ink.
- `WINDOWS.md` created.

## 21. The live refinery + Files chosen (2026-09-01, later)

`EXPLOSION.md` §20 (supersedes §18's counts): 🪓 axed Deliveries · Calendar · Timers (§16.13 with
it) · Search · Weather (phone app preferred; the §4.5 emergency promise rides the WEA/CMAS probe
alone) · Health (aria retired). ✅ Added: the TORRENTS window (his "Yes!"), Feed comic sources
(11.12), caller ID as a §16.5 source, the Info useful-stats steer, the Games 10b block
(cards/Minesweeper/Chip's clone; emulation gated on a ROM pace-screener; the Balatro seam —
LÖVE/Steamodded state-export beats screencap vision). The rejected pile is in §20; the wow order stands.

🔴 **Adam chose FILES first.** Binding: G2CC-like + the graphical wave; a locations root list; **tap
= context menu with Open first** (two taps to enter a folder, uniform for every entry); viewers for
text, PDF, images; the popup **floating** (a hole in the content, not a card); the This-folder row
at the wrap end; clipboard Copy/Cut → Paste-here; **lens thumbnail AND per-row file-type icons**;
PDF dual-mode, auto default; trash with Restore + permanent delete behind a double confirm; typed
rename/mkdir; Open-on-PC; EPUB→Reader hand-off; locations include the damagewm dir. **Theme
icons:** his XFCE Papirus-Dark set, grayscale, *"for everything in DamageWM that uses icons"* —
third-party assets, personal-lane only (rendered locally at runtime, never in the repo, APK assets
or a release; the drawn set is fallback + release path). His standing instruction: *"run a heavy
review … double check and verify it is really a problem … then for every confirmed issue, fix it.
Do those review-then-verify-then-fix steps until a full review passes with no more issues found at
all, in a loop."*

## 22. The overnight build: §16 machinery + FILES + the review loop (2026-09-01, overnight)

Autonomous; zero radio/glasses/phone interaction; G2CC untouched.

### 22.1 What was built (commits `b93d7e0` docs · `fa80bdf` substrate · `b715a18` Files · `8f0dfe2` review round 1)

- **State substrate (§16.4):** sub-records `window.<id>.<subKey>` with
  `saveSubState()/restoreSubState()`; tombstones = empty objects, only for keys the window has
  ever reported; merge-on-load (strictly-newer in-memory wins; an unreadable store keeps memory);
  the post-start reconciliation Run; `freshen` skips absent keys; `applySettings(persist=false)`.
  `SubstrateTest` continuity.
- **Window channel (§16.10):** `WinNet.kt` — `{"t":"win","win":…}` on the content port,
  `WinService` / `RemoteWin` (keeper reconnect, id-correlated request/response, raw-blob answers,
  `stateLine`). Push frames and multi-backend arbitration came with Music.
- **Deep links + notifications (§16.1/§16.5):** `open(target)`; `services.openWindow(id, target)` records the
  CALLER so back returns to it; the signature grew source/thread/appId/target/urgency.
- **MenuSurface (§16.11):** the floating context menu — 248 px hole at plane 0, under-content captured and
  restored, pan-window for long menus, detail capped at half-box via `fitEnd`; decision-6
  deferral; emergencies cancel and requeue. `Draw.kt` (`fit` marks cuts with ▸, `right`, `dynamic`
  — '?' for uncoverable glyphs, warned once); `Exec.kt` (stderr drained on a daemon thread).
- **Theme icons:** core `IconSource/IconNames/IconPaint/IconRaster`; desktop `ThemeIcons.kt`
  (xfconf detect, Inherits-chain BFS, rsvg-convert / magick, cache keyed by theme); phone
  `RemoteIcons.kt` (content-port `icon` op, Semaphore(4)). Main's lens icon ink re-measured
  9.0 %/4.8 %. A missing tool or theme degrades loudly to drawn.
- **FilesWindow (~1,150 lines) + `LocalFilesProvider` + `FilesNet`:** the §21 design whole —
  locations (Root/Home/Downloads/Books/damagewm/live mounts with capacity bars/Trash when
  non-empty), text viewer (UTF-8 boundary-safe chunked reads), image viewer (strip DocView), PDF
  (pdftotext/pdftoppm, auto by extractable-text ratio), clipboard (NOFOLLOW copies, copyTree
  rollback), trash/Restore/purge, rename/mkdir, Open-on-PC (xdg-open), `open("book:<id>")`.
  Deviations in `EXPLOSION.md` §5's banner (per-row thumbnails not shipped; `appSettings()` empty).

### 22.2 The review loop

Findings per round **79 → 20 → 28 → 9 → 3 → 3 → 2 → 0** (converged at round 8); every finding
verified before any fix; the battery green after every round.

- **Round 1** (`8f0dfe2`, ~55 fixed): the seam-start ordering race (the one flaky core test from
  launch night); timed notices; paced viewer/thumbnail retries (5 s); `parseAck` per-subfield
  tolerance; `restampMsgId` loud refusal on fixed-width; inflate refusing needsDictionary. Verified
  as DESIGNED: dual-live-shell LWW alternation (inherent to LWW).
- **Round 2** (`ead19a3`, `5d7ba5e`): a compositor defect — an unpaired seam strip's fallback
  painted black on the opposite lens OUTSIDE the repair area, silently (1,920 px in the probe;
  `L2ProbeTest`) — seam strips bounded to the scanned area. LWW: **stamp-0 baselines** (a virgin
  device cannot stamp defaults over the fleet); the settings re-encode echo closed; `subReported`
  per-session (a failed restore after a keeper restart cannot tombstone real data).
- **Round 3** (`d0a74aa`): content-channel liveness (keepAlive everywhere, the tmux subscription
  re-assert, the win host greeting); the cross-version law on every lane (in-band `err`, never a
  closed session); undisturbed-guards on every async completion (openPdf, four tmux `busy()`
  sites); per-item LWW re-apply; `restoreStateLive`; an emergency cancels the wheel.
- **Rounds 4–8:** round 4 (`10db318`) the pdfpage restore cancelled ITSELF and a freshen mid-window
  re-stamped BROWSE, closing the peer's PDF (`pendingOpenView`); round 5 (`119d6dc`) the R4
  mechanism un-applied on one path; round 6 (`b33253b`) the subscription keys to the TARGET not the
  level; round 7 (`d2945eb`) the wrong-file class's last doors; round 8 nothing real.

Left un-taken (latent, unreachable today): `LocalFilesProvider.list("")` lists the working
directory rather than refusing; Files `nameArmed` survives restoreState. Take with the next Files change.

### 22.3 State at hand-off

Battery: core 191 · desktop 9 · selfcheck 61 · snapshots 18 · epub 380/404 · lint 0 · APK 16/0.16
(observed installed later that day, `37cf9b0`). Reader writes transitional legacy offsets beside
sub-records — removable now that the installed APK is ≥ 0.16 (with it `restoreStateLive`'s map-authority and
`liveMapApply`). Limits recorded as designed: the
reset-progress picker matches by TITLE; a Settings double-tap revert applies its whole snapshot;
the "+N" badge counts already-read entries; the content-port pre-auth hello read has no time bound
(tailnet-only); no app-level ping on the win channel (bounded by keepAlive); the L2 seam repair
rides the next flush via `residual` under exact budget exhaustion.

## 23. Torrents + the keyboard (2026-09-01, evening) — `TORRENTS.md`, `DESIGN.md` §4.8

Adam's rule from here on: **no v1/v1.5 staging — complete and polished before the next app.**

- **qBittorrent's Web API was not compiled in.** `net-p2p/qbittorrent-5.1.4` rebuilt with `webui`
  (`/etc/portage/package.use/60-qbittorrent`); config: `WebUI\Enabled=true`, `Address=127.0.0.1`,
  `Port=8090` (8080 is Caddy), `LocalHostAuth=false`; `admin` + a random PBKDF2 (format verified
  in `src/base/utils/password.cpp`), plaintext in `~/.config/qBittorrent/webui-credentials.txt`
  (0600), unused. Verified: API 2.11.4, 38 torrents, loopback only, no auth from localhost. (The
  OpenRC `qbittorrent` service since §44.)
- **TorrentLeech probed live, read-only, one login** (`TORRENTS.md` §2). ⚠ The profile page shows
  the passkey and e-mail in plain text; the dump was deleted; the adapter reads five stats.
  Credentials only in `~/.damage/config.json`.
- **Verdicts** (`TORRENTS.md`, `DESIGN.md` §4.8, `WINDOWS.md` §1): TorrentLeech only; browse and
  search; delete keep-files behind one confirm, with-data behind two; done = the finished edge,
  announced for every torrent, toggles in Settings → Torrents; no magnet/URL typing, no Files
  hand-off, no categories, no shelf glue, **no RSS ever**; `~/Downloads`; 2 s / 15 s polling; Stats
  + a seeding-under-a-week list. Keyboard: row-then-key, stay in the row after typing, QWERTY + an
  abc option, no history row, the draft kept on cancel, outlines — *"an image of an actual keyboard
  wireframe-style where I can move the highlight to select the key."* **Each app's notification
  toggles live in its own Settings category, never Global.**
- **Built:** `KeyboardSurface` + shell wiring (`openKeyboard`, planes, depth rail, cancels with the
  draft kept, replica commit), Settings → Global → Keyboard, Tmux "Type…", Files rename/new-folder;
  `QbtClient` (API 2.11, keys read from source), `TorrentLeech` (+ a stdlib `Html` reader; every
  parse refuses drift loudly), `LocalTorrentsProvider` (poll loop, event diff, persisted announced
  set, epoch + sequence), `TorrentsNet` (version-cursor snapshots, event replay), `TorrentsWindow`
  (five levels, three menus, two documents, six filters, speed history), `ScriptedTorrents`,
  `IconKind.TORRENTS`, the Files `path:` deep link. The unused Global notify rows (SMS/Mail/Music)
  are gone.
- **Harness catch:** the transfers cursor was a bare index into a LIVE list; it follows its row's
  identity (hash, or the menu row).
- **Measured (selfcheck):** transfers list 9.0 % ink, details 6.4 %, the open keyboard 9–11 %.
  Battery: core 220 · desktop 9 · selfcheck 89 · snapshots 26 · lint 0; APK 18/0.18.

### 23.1 The review loop — round 1 (2026-09-01, night)

Five reviewers, ~55 findings, all re-verified; fixed: the provider listener leaked on every desktop
stack swap (a stopped shell's queue fed one snapshot per poll for the life of the service →
`TorrentsWindow.detach()` from `DesktopStack.stop()`); the tracker's NFO landmark matched a
commented-out template (comments stripped first; a row without `fid`/`name` refused loudly; `+`
not decoded as a space; HTML on the JSON endpoint = a refused session); the announced stamp kept
across a removal (a qBittorrent restart would have announced all 38 finishes), "done" keyed on the
completion stamp, a host restart replays its log to a phone connected before, `truncated` when the
ring no longer reaches back; the categories list called the provider from a paint (a blocking
channel request on the phone's loop → static table); `restoreStateLive` reloads the open document;
the unrecoverable delete at index 2 behind a spacer; live keys wrap onto a second row up to twelve,
more refused loudly (Tmux's fourteen defaults silently lost Tab and q); uncovered glyphs display as
`?` per UTF-16 unit; a shell stop / keeper restart / window commit drops the keyboard with the
draft handed back; a refused qBittorrent login latches (five failures ban the address an hour);
`Fmt` locale-fixed; the cookie jar 0600; `Max-Age=0` deletes a cookie; `.kotlin/` untracked.
Exemption: a replica-typed line searches without a confirm (read-only). Pins:
`TorrentsTest` ×4, `KeyboardTest` ×18.

### 23.2 Round 2 — the round-1 fix diff, fresh eyes (2026-09-01, late)

Over `28997a8..73fdf81`. Two HIGH: **the listing fetched every page of a category while the cursor
rested on row 0** (the panning list wraps its tail rows above the cursor, so the Loading row's
paint-time demand re-fired on every repaint — paging follows the CURSOR now; the row list cached);
**a restored transfers cursor was steered by a stale details hash** (`cursorHash`, one-shot). Also:
a live-synced record reloads only while focused; the `tl:` deep link enters a clean listing; MenuSurface sanitizes with its own rasterizer;
the keyboard's pan bound accounts for the shifted text; a too-long live row is refused BEFORE the
surface opens; `pollOnce` serialized; a maintenance page reported, re-logins paced to one a minute.
Accepted: one "remote" focus key per channel; the keyboard's 576 px key field assumes the
full-width content area. Battery: core 219 · selfcheck 89.

### 23.3 Round 3 — the round-2 fix diff (2026-09-01, late)

Over `73fdf81..4f5e6e0`. One HIGH: **a listing whose only content row was the loading row could
never re-demand its page** (the list kit paints the cursor row through the lens, never the row
painter) — the demand runs from the window's `view()` on the loop and from the paced retry, never
unfocused (a switcher preview must never issue a tracker request). Also: a keeper's same-instance
restart registered the listener again (idempotent now); a pending cursor resolves against the
snapshot at hand; logins paced to one a minute on every branch, a refused login latches for the
process; a zero-result search says `no results`; the pan start never splits a surrogate pair. Battery: core 220 · selfcheck 89.

### 23.4 Round 4 — the round-3 fix diff; the loop's last round for now (2026-09-01, late)

Over `4f5e6e0..980d832`. Adam: *"this is the last review for now."* Fixed: the tracker's empty-jar
login path was the one unpaced way in (pacing lives in `login()`); the refusal latch fired on ANY
200 HTML answer (a maintenance page would have latched a healthy account) — only on the login form
now; a save before the first snapshot outranked the saved cursor index; a restored listing cursor
beyond page 1 never fetched its page; the phone rasterizer's coverage check iterated UTF-16 units
(every emoji counted uncovered) — code points now. **Accepted:** the status bar's op word has no
owner (a window's "idle" on a live apply can blank another's op word — a shell-side owner is a
later item); one "remote" focus key per channel; the 576 px key field.

Battery: core 221 · desktop 9 · selfcheck 89 · snapshots 26 · lint 0; APK 18/0.18. Rounds 1–4:
~55 · ~30 · ~15 · ~14; round 4 still found real defects in round 3's fixes — a PAUSED loop; resume
from the round-4 diff `980d832..390a25c` (then `..HEAD`). Next steps superseded by §24.

## 24. Music — designed, built and reviewed (2026-09-02)

Verdicts in `MUSIC.md` §1 (29 rows); facts verified read-only (§2: G2CC's music system taken over
whole — Postgres `g2cc`, Qdrant, the 8.1 GB cache, the enrichment package, yt-dlp; the phone plays,
the PC serves); the plan `MUSIC.md` §5–§13 (the `MusicLibrary`/`MusicPlayer` contracts, `mediaPort` 7404). Reversals: volume adjustable and synced; the phone
speaker an allowed output; every window works at all four heights (`WINDOWS.md` §1); the APK stops
posting errors to the phone (Global toggle, off). Run with Adam's go: `REINDEX DATABASE g2cc` +
`ALTER DATABASE g2cc REFRESH COLLATION VERSION` (2.42 → 2.43; other databases untouched).

### 24.1 The build (2026-09-01/02, overnight, autonomous — six commits, the battery green at each)

| milestone | commit | what landed |
|---|---|---|
| M1 host | `36343dc` | `MusicModel`, the `Db` seam + `PgDb` (pgjdbc 42.7.13 over the Unix socket via junixsocket 2.11.1, peer auth), `MusicDb` (+ the additive `lyrics.source/track_id` migration in `damage_schema`), `Qdrant`, `MediaCache` + transcoder, `MediaServer` (:7404, Range), `Art`, `LibraryScan`, `LocalMusicLibrary`, `MusicNet`, the `WinNet` PUSH slice |
| M2 window | `2acf432` | `MusicWindow` (four heights), `QueueEngine`, `PlayerCore` + `SimMusicPlayer` + `MirrorMusicPlayer`, `LyricsSync`; `ScriptedMusic`, scenes 30–37; six delegated leaf modules (Resolver + ClaudeOneShot + EmbedQuery, LyricsFetch, YouTube, Viz, `audio/` + viz.py + Enrich, MusicListener + SpotifyRemote) |
| M3 shell | `fc2fa99` | `Mode.EXCLUSIVE` (`DESIGN.md` §4.9) + Music Mode's per-height stack; `MusicModeTest`; scenes 38–39 |
| M4 APK | `67d65b8` | `AndroidMusicPlayer` (ExoPlayer + media3 1.5.1 over a ForwardingPlayer), `TrackCache`, `Prefs.mediaPort`, the Global **Phone notifications** switch, the `music access` grant; APK 0.19 (→ 0.23 through §25) |
| M5 host features | `72cae3d` | the lyric-sources choice on the host (a source FAULT throws, a MISS stands until the sources widen), `Enrich` + `LyricsFetch` wired, `musicAudioDir` |
| M6 docs | `178603f` | records, `IMPLEMENTATION.md`, `DAILY.md`, `REMINDER.md`, `WINDOWS.md`; jar + APK staged |

Battery at M5: core 315 (221 before Music) · desktop 9 · selfcheck 134 · snapshots 36 · lint 0 ·
`--music-check` against the real `g2cc` (2,981 tracks, catalog 1,440 KB in ~70 ms, legacy cache
20/20, Qdrant 2,981 points).

**Delegation:** six Opus agents in isolated worktrees against fixed interfaces (`Plugins.kt`,
`MusicModel.kt`); findings that changed the plan: `claude --bare` cannot authenticate an OAuth login
(measured); LRCLIB's search returns whole rows with lyrics inline; NetEase's search is rate-limited
per address and needs its three cookies; the Musixmatch route answers a captcha 401 (behind the
toggle, off); `PlaybackState.getLastPositionUpdateTime()` is an `elapsedRealtime` instant.

**Decisions inside the plan (not to be re-litigated):** Postgres through a `Db` seam, the JDBC
driver :desktop-only; `MediaServer` a ServerSocket HTTP/1.1 server in core (no `com.sun.net.httpserver`; a malformed
Range answers 200 whole); profiles High = Opus 128 k mono / 192 k stereo, Standard 96 k, Saver 48 k,
Lossless passthrough, cache dirs `<quality>-<mono|stereo>-<loudnorm|flat>`, the legacy G2CC cache
IS `standard-mono-loudnorm`; `--music-check` applies the one additive migration, otherwise
read-only plus one viz blob; the catalog is one JSON blob cached on the phone, versioned by a SHA-1
over its SHAPE (counts, newest stamps, the count of FOUND lyrics, which flips `hasLyrics` — never play history); the card
repaints on a 5 s pace while focused (1 Hz would be ~10 % link duty), Music Mode's card every 5 %
of progress, lyrics on line change, every surface one rect; track-change notices only while Music
is NOT on screen; Seek rests on "+10 s", Replace-queue-while-playing confirms, Save-over asks
twice, Delete playlist is Cancel-first with the unrecoverable row at index 2, a replica-typed line
is an Ask behind a confirm; yt-dlp grabs `opus` with
`--embed-metadata --max-filesize 300m --newline --progress --print after_move:filepath`, the URL
after `--`; media3 next/previous route
to OUR queue (ExoPlayer holds one item); Auto output refuses to start with no external output, the
speaker only when chosen; Boost = `LoudnessEnhancer` 2000·log10(pct/100) mB capped at 1200, off on
every open and stop; the desktop mirror hands the phone's record back byte-equal and refuses every
transport command ("playback needs the phone"); exclusive mode restores only when its window is
registered on the restoring host; the scripted viz data spans seven minutes.

**Measured vs modeled:** Music Mode ink 10.9 % at 480 (Bars), 6.3 % at 288 (Scope) and every
latency are from the sim. The Bluetooth lyric offset, the visualizer rate, the limiter's notice and
the Spotify cold start are the phone's measured items (`MUSIC.md` §12, `DAILY.md`).

### 24.2 The review loop — round 1 (2026-09-02, morning, autonomous)

Docs sweep (`17a9a9b`), then `/code-review high` over `8d5e30b..HEAD`: 10 ranked findings + 17
one-liners, re-verified, 2 declined; nine of my own first (the `runOp(verb, then, op)` signature
binding a trailing lambda to the wrong parameter; a code-set cursor re-read as the user's; a deep
link clearing the stack before validating). By weight:

- **The APK could not have played at all**: targetSdk 35 refuses cleartext HTTP
  (`http://beardos:7404`). `usesCleartextTraffic` declared (token-gated, tailnet).
- **Boot**: Android 15 refuses a foreground service started from `BOOT_COMPLETED` with
  `mediaPlayback` in its types. Starts as `connectedDevice`; adds `mediaPlayback` when playback
  engages (`AndroidMusicPlayer(onEngaged)`).
- **The player record**: `persist()` forced `play = STOPPED` and wrote a `stamp`, so the truth never
  travelled; it writes the real state + `posAt`, no stamp; `restore()` never auto-plays, never
  overwrites the sink's volume.
- **Lyrics**: a track change in Music Mode never reloaded them; the scheduler armed a flush
  `LYRIC_DISPLAY_MS` early but the painter chose by the raw position (old line painted, re-armed);
  plain lyrics paged as 12 raw lines left wrapped lines unreachable.
- **The catalog**: `hasArt` keyed with mtime 0; the version fingerprint included lyrics and
  play_history max timestamps (a 1.4 MB re-download per play) — shape-only now; RECENT a loaded
  frame (`MusicLibrary.recent(n)`); an EMPTY host catalog throws; the viz blob async
  (`Listener.vizReady`); remote caches bounded (`evict()`: viz 150 / art 3,000 / lyrics 3,000).
- **Phone**: the listener rule `com.google.android.` matched EVERY Google app; route loss fired on
  ANY external audio device removal (checks 500 ms later whether playback stopped);
  `preferredHeight ?: 480` made the Size row's "global" unreachable (global is stored as 0, `MUSIC.md` §8).
- **Small**: `requestRepaint` in exclusive mode flushed the full canvas; `PgDb.tx` skipped rollback
  on an `Error`; a 0-byte file answered 206 `bytes 0--1/0`; `MediaCache` could lose a transcode
  thread (`CountDownLatch` per output); `LyricsFetch.kt` carried NUL bytes; `viz.py` normalized
  silence to 15 instead of 0.

Battery: core 315 · selfcheck 134 · APK 0.20.

### 24.3 Ultrareview — two cloud runs over the whole build (2026-09-02, afternoon)

`/code-review ultra` caps the diff at 8,000 lines; the build is 128 files / 17,149 lines from
`pre-music` (`8d5e30b`). Two synthetic pairs (a base commit holding everything EXCEPT the files
under review; a review branch byte-identical to main): run 1 (31 files / 6,854 lines: window,
player, exclusive mode, phone, desktop wiring, viz.py), run 2 (24 files / 6,396 lines: DB, net,
library, cache, media server, leaf modules, PgDb). Branches deleted.

**Run 1 — 3 findings (`d6bb08b`):** Spotify cold start could never work (no `<queries>` for
`com.spotify.music`; Android 12+ package visibility); a Play-from during a pending Radio /
Library-random fill was stepped past when the fill landed carrying "advance when you land"
(`PlayerCore.pickGen`; `SimMusicPlayer.deferAsync`/`flushAsync`); a constant-true `takeIf` in
`applyBoost`. **Run 2 — 5 nits:** `viz.py` re-spawned forever for a permanently failing track (a
`.miss` marker, the `Art` pattern); `LibraryScan` descended into hidden directories (`pruned()`
checks every segment); `setLyrics`'s `(unknown)` vs the legacy raw empty string (both accepted);
`Rules.exclusionNote` blamed "sound effects" for the spoken-word filter; the remote's cache writes
not atomic (`atomicWrite`).

**Docs audit:** `noticeAllowed` still gated `music` on the Global `notifyMusic` field, rowless since
Torrents — APKs ≤ 0.17 carried it, so a persisted "off" would have silenced every Music notice
with nothing to turn it back on. Gate removed; the window owns its six Notify rows (`WINDOWS.md`
§1). Every Music commit is stamped 2026-09-02 (built 03:08–04:44); "09-03" datings fixed.

Verdict: fresh eyes found what the author's review had not; none of the eight touched a design
decision; three passes converged on nits. It lists findings only (fixes opt-in via `--fix`, not
used); it reads `CLAUDE.md` and `REVIEW.md`. Battery: core 317 · APK 0.21.

---

## §24.4 The silent-playback session, and the NOW PLAYING root (2026-09-03)

Adam: Music *"did not play music. It seemed to think it was playing, but no sound came out of my
earbud."* Measured: `~/.damage/media-cache/high-mono-loudnorm/` held ten tracks transcoded
18:22–18:38, the last three 3.5 and 5 minutes apart (song-length gaps); the synced
`window.music.player` record (18:50:30) read `play: PAUSED`, `engine.index: 3` of a 55-track
queue, `posMs: 146651`, `output: 'auto'`, `profile: high-mono-loudnorm`, `holdVolume: true`, **`volume: 8`**. The chain worked;
**the phone's media stream was at 8 %** (~step 1 of 15) against −16 LUFS content. Damage never
sets that level (`PlayerCore.restore` never applies a persisted volume). The defect was that
nothing said so. Fixes: (1) `PlayerEvent.QuietStream` — playback at or below `PlayerCore.QUIET_PCT`
(10 %) raises a notice once per RUN; the latch clears on stop, queue end, or the level rising.
(2) Output restored by STABLE identity — `onRestored()` matched by `AudioDeviceInfo.getId()` (a
per-connection handle, reused) and ignored `setOutput`'s `false`; the record carries `outputName`
+ `outputKind`; a miss raises `PlayerEvent.OutputGone`, falls back to Auto. (3) The media endpoint
logs nothing on success (had to be inferred from cache mtimes). Noted, not fixed.

### The root is NOW PLAYING (verdict 4 reversed)

Adam: *"lets put the queue as a menu option rather than the main screen … the main screen should
be a useful, really nice looking Now Playing screen."* `Kind.NOWPLAYING` is the root; `Kind.QUEUE`
a pushed level from a **Queue** menu row (a **Track info** row joined it). The root is a Canvas:
scroll = volume live, tap = the Music menu, no cursor. Four TOP-aligned bands: identity with art
at 160/120/88 px by height · elapsed/progress/total · the level with the queue position, **drawn
HOT at or below 10 %** · the current lyric line when it fits. The queue is addressed by KIND
(`queueFrame`); a pre-2026-09-03 record with `QUEUE` at position 0 maps onto the new root;
`t:<id>` opens the queue level. **Harness lesson:** five tests and the selfcheck Music section
broke because they selected menu rows by COUNTING notches; `Shell.menuLabels` / `menuCursor` are
exposed and every harness picks rows **by name**; every harness wait is bounded, loudly.

Battery: core 319 · desktop 9 · selfcheck 139 · lint 0 · APK 22/0.22 (superseded by 23/0.23 with
§25). Snapshots renamed `30-music-nowplaying-480` / `36-music-nowplaying-288`.

### The review pass on the new code (same session)

1. The output restore matched ANY same-kind device as its second fallback (earbud vs glasses, both
   "bluetooth"). Now exact name+kind; a same-kind device only when the saved name WAS the kind
   label and exactly one exists; otherwise Auto, loudly, clearing `preferredDevice`.
2. The queue level opened on row 0 instead of the current track (`push()` had no QUEUE branch);
   fixed, seeding `cursorSetByMe`.
3. The Queue menu row could stack a second QUEUE frame. Guarded.
4. `QuietStream` / `OutputGone` were title notices only — invisible when playback starts from an
   earbud tap. Both follow the `Error` idiom (notice on screen, notification when not).
5. A doc comment displaced `LIMITER_DROP`'s. Restored.
6. The queue position was an unbounded right-align (the F2 class). Fitted.
7. An unreachable "stopped" empty-state arm; `npArtPx` started at 96 where 480 wants 160.

⚠ Now Playing measures **14.0 % ink** at 480 with synthetic art against the 15 % list budget; if
real art trips it: smaller art, or reclassify as a canvas (30 %).

## 25. The whole-codebase review (2026-09-03, late)

Adam: *"run a full, deep, thorough code review of this entire project top to bottom … then verify
each one is really an issue … then fix it … then double check each and every fix."* Every finding
reproduced before its fix; every pin in
`core/src/test/kotlin/wm/damage/core/Review20260903Test.kt` FAILS against the unfixed tree.
Commits `1f9fa4d`, `c400d0b` (APK 23/0.23).

### 25.1 The oracle — how the invisible ones were found

The mirror/divergence check (`DESIGN.md` §8.2) compares BELIEF to the glass; a defect that writes
wrong pixels into the shadow and sends them makes the two agree. The oracle recomputes the per-lens
**truth** of `comp.composed` under `comp.planes` — split the panel by every plane, keep the pieces
in a region, paint each at its own shift, far first, as `Compositor.renderTruth` does — and asserts
it equals `comp.expectedLens(left)` after every settle. A 900-step random walk found #2 below.
⚠ **Split by plane PIECES, not raw plane rects** — shifting whole regions reports ~32 false pixels
at the lens-band edges. Do not re-introduce it.

### 25.2 The ten findings

1–2. **A plane-0 delta could carry another plane's pixels** (`Compositor`): `partition()` step 1
   merged the two plane-0 GUTTER rects with a `{ true }` predicate into one full-width `d=0` box
   across the content plane; `coarsen()` unioned REMAINDER rects by row band with no plane map.
   Those pixels land at the wrong shift on both lenses outside the scanned `area`, and belief and
   glass agree on the wrong thing. `remainderPieces()`; rect economy 8 bytes cheaper over boot +
   open + 25 notches.
3. **The silent notice body was wrapped to the WINDOW box's width** (`Notifications`): the silent
   box is 200 px, `bodyLines` wrapped to 232 px, `paint` drew unbounded — up to 40 px past the box,
   cut with no mark, and undamaged ink in `composed` (240 lit px outside the box; the sim glass
   showed 110 of the 230). `Notifications.SILENT_W`, `bodyLines(n, l, silent)`, a drawn mark.
4. **The Reader shipped tofu for 14,315 characters of Adam's real shelf** (`Epub`): numeric
   references in 0x80–0x9F skipped the Windows-1252 remap HTML5 mandates, AND the files literally
   hold those code points (`C2 97` where an em dash belongs, beside correct `E2 80 99` quotes —
   transcoded cp1252-as-latin-1). `Epub.fold` maps C1 through `CP1252`, folds U+2011 (284×, missing
   from all four locked faces) to `-`, drops zero-width formatters (40×). Offsets unaffected;
   Adam's saved position lands on the same sentence.
5. **The Reader was the last window drawing dynamic text raw** (`ReaderWindow`): titles, authors,
   toc names and prose through `Draw.dynamic`; `paintBookLine` fitted. Measured on the 58-book
   shelf: 14,365 undrawable code points → 50 (2 Hebrew letters, 48 U+FFFD in the source).
6. **The flow renderer drew terminal output raw** (`FlowRender`): live panes carry U+23BF / U+23F5
   / U+2722 / U+273B from Claude Code's TUI; JetBrains Mono has none. Sanitized at LAYOUT time.
7. **A restored level below the top never loaded** (`MusicWindow`): `restoreState` set `needsReload`
   from `top.kind` only and `back()` loaded nothing (a restored `[NOWPLAYING, PLAYLIST, INFO]` backed into a bare menu
   row forever). `ensureLoaded()` on the way back.
8. **The desktop mirror published a removal TOMBSTONE for the player record**:
   `MirrorMusicPlayer.persist()` is `{}` until the phone's first record, and an empty blob IS the
   §16.4a tombstone on a syncable key. Fixed at the window and as a class in the shell (an empty
   sub-record is refused loudly, once per key).
9. **The quiet-stream latch ignored its own remedy** (`PlayerCore`): `setVolume` did not clear `quietWarned`.
10. **Two catch-and-swallow blocks** — `QueueEngine.fromJson`'s torn row and `PlayerCore.stop`'s boost reset.

### 25.3 What the review checked and found clean

Read in depth: geometry, compositor, `CfwTransportBase`, `Emit`, wire, gfx/codec, text/style, every
shell surface, Reader, Music, the phone's `ShellService` / `AndroidMusicPlayer`. Verified live: the
58-book shelf, the real tmux (4 sessions), the filesystem (9 locations), qBittorrent (39
transfers), the Postgres library. Not defects: no timeouts in the tree; every subprocess via
`ProcessBuilder(list)`; all `MusicDb` SQL parameterized (`$col`/`$order` from fixed lists); every
network surface token-gated; the `wide`-flush pipeline depth matches §8.2's intent; the 24 EPUB images failing to decode are SVG; `TermRender`'s tofu box
deliberate. Retracted after testing: a walk's transient belief/`composed` disagreement is chrome
waiting for the 5 s idle flush (§8.3). ⚠ Boundary: seam / replica / `RemoteTransport`, the
simulator and most of the phone module read at a SCANNING level (their own suites:
`SeamMirrorTest`, `SeamSessionTest`, `HandoverTest`, `PathTransportTest`, `ReplicaServerTest`,
`LensOracleTest`). Nothing tested on the glasses.

Battery: core 329 (+10 pins) · desktop 9 · selfcheck 139 · snapshots 36 · epub 58/58 · lint 0 ·
APK 23/0.23 staged; 0.16 the last observed installed.

---

## 26. Games · Hold'em — built, reviewed twice, live-tested (2026-09-04, overnight)

Adam: *"build it! This is an automated overnight build … build the whole thing completely and
correctly and polished all the way … run a complete code review … then do that a second time …
then test the full system in the live environment thoroughly, exactly as a user would."* Design
in `HOLDEM.md`; **`HOLDEM.md` §17 is the deviations list.**

### 26.1 What landed, milestone by milestone

- **M1 · the shell rule** (`d3da21d`). Verdict 35 is general: *"Going to Games from the switcher
  should auto-resume … from Main should present the Games List … this should be true of any window
  that has multiple base functions."* `ActivationSource` (`SWITCHER` / `MAIN` / `DEEP_LINK` /
  `RESTORE`) on `DamageWindow.onActivate`, `Shell.commitWindow(w, from)`, all six windows implement
  root-vs-resume. Trap: Reader's Main entry left a subfolder open (depth 2) — `goRoot` resets folder
  AND cursor; the test asserts `levelDepth() == 1`. Music is the exception (NOW PLAYING is its root).
- **M2 · the card kit** (`fdab57a`). `Rng` (counter-based splitmix64), `Cards`, `HandEval`, `Pots`,
  `Money`, `CardArt`, `HandFan`, `TableLayout`, `Seats`, `ActionLevel`, `Bankroll` — poker-agnostic.
  The side-pot oracle: `pokerkit` (MIT) in a scratch venv generated 3,000 side-pot scenarios and
  2,000 hand-ranking cases; the CORPUS (`core/src/test/resources/holdem/`, JSON we own) is what the
  repo holds. Four generator defects fixed first (`str(card)` vs `repr(card)`; sampling `st.bets`
  after the reset (the peak of `start − stacks` is the real contribution); a missing show-hole-cards step that scored every all-in hand as a chop; an
  `exactPayout` invariant 1 in 3,000 disproved → a strict rule plus an odd-chip drift bound).
- **M3 · the engine** (`14299c7`). The live table stores only `(seed, handNo, start stacks, busted order, button, actions)` and re-derives everything by replay — no second copy to drift.
- **M4 + M5** (`b9eb6b6`). The four-height table, action/sizing/confirm levels, the §4.8 keyboard
  for a custom raise, hand history; `Character` (nine traits), `Mood`, `Roster`, `Background`,
  standings, shared bankroll, Loser Count. `--games-check` found Unlimited's `canAfford` refusing
  the player his own table, fraction-of-roll stakes inflating the money supply 236 %, and an
  `ambition()` call inside a comparator (non-transitive; `sortedWith` may throw).
- **M6.** Registration in desktop `Main.kt` and phone `ShellService.kt`, Settings → Games, 20 selfcheck checks, 13 snapshot scenes,
  the GAMES icon, `--games-check`, `--card-render`. `gamesChecks()` extracted — the selfcheck
  method passed the JVM's 64 KB limit.

### 26.2 The two review passes (`8aa9910`, `20f01a8`)

Nineteen verified defects (`GamesReview20260904Test`). Classes: Monte-Carlo decisions returning
through `runOnShell` must be generation-stamped (`pacerGen`); **registration is not restore**
(`onRegistered` populating the roster minted 35 characters against a fresh seed — free money every
start); a finishing place is an ORDER not a flag (`bustedAt` 1-based, start-of-hand stack the
tie-break); the verb keys on `currentBet`, not the street.

### 26.3 The live session — the part that found what nothing else could

The desktop program under the sim in a scratch `$HOME`, driven over the browser replica, lens
panels decoded to PNGs. Thirteen findings (`HOLDEM.md` §17.2). Two general: (1) **cash out was
unreachable** — only on the action level (mid-hand), and the engine refused mid-hand; every unit
test called `cashOut` in a state the UI cannot produce. **A menu row's reachability is part of its
contract.** (2) **A drawn stack of horizontal bars beside a number is punctuation** (`$198 = $2`);
round overlapping chips instead — judge at true 1×.

⚠ Process: `HOME=… java …` does NOT change the JVM's `user.home` — a stray instance briefly shared
Adam's live `~/.damage` (three pristine `window.games.*` records, self-correcting); use
`JAVA_OPTS="-Duser.home=$SCRATCH"`. `pgrep -f` matches your own wrapper shell, which orphaned three
instances sharing one state file while only the oldest held the replica port.

### 26.4 The second cycle (2026-09-04, later) — eighteen more

Sixteen verified defects + two coverage gaps (no test walked cash-out to completion; no check
measured a type ladder against the real rasterizer), pinned in `GamesLive20260904Test`,
`GamesWindowTest`, the selfcheck; `HOLDEM.md` §17.2b. To carry past this window: 🔴 **the same
defect, one branch over** — §26.3's cash-out fix short-circuited on `contributed == 0` into the engine
call that refuses a live hand, and that is first to act, preflop, out of the blinds: four hands in
six (**walk every branch of the row**); 🔴 **`?: 1` as "no finishing order means first"** paid every
survivor of an early-stopped table the whole prize (`playOut` has two loud paths that stop one) — settlements RANK survivors by chips (**a
default right on the happy path is an assumption**); 🔴 **the build gate had a blind spot** —
`tools/lint.py`'s string walker did not know char literals, so `'"'` (three in `Journal.kt`)
flipped its parity for the rest of the line. Live re-drive: cash-out from the broken spot (bankroll
$790 → $802), tap to leave, the `Settings · games` deep link, a full restart resuming the identical
hand, the switcher resuming the table; "You checks" / "You wins $412" now in the right person.
Battery: core 418 · selfcheck 162 · snapshots 49 · APK 25/0.25.

### 26.5 The third cycle (2026-09-04, later still) — a measurement that deleted a window

Eleven verified defects + two test-quality fixes (`HOLDEM.md` §17.2c). 🔴 **`playOutWithoutMe`
handed the play-out to a background coroutine because of a number nobody measured** — its comment
said "takes seconds"; `--games-check` prints **13 ms for a whole 6-seat tournament** at
`CHEAP_ROLLOUTS`. The coroutine cost two defects (a new table's cast cleared by the old table's
settlement; a restart inside the window losing the prize pool). On the loop now (`maybeBackground`
already spends 16–80 ms there). **An asynchrony introduced to hide a cost nobody measured is a
defect generator.** Test fixes: a VACUOUS pin (identical with and without its fix) rewritten to
capture the log line that changes; a pin claiming a race it cannot reproduce now states the
invariant it locks. Live: cash-out, sitting back down, 20 hands with a bust drawn as `out`, the deep
link's back path landing on GAMES; one error line in the log (the scratch instance failing to bind
the media port the service holds). Battery: core 419 · selfcheck 162 · APK 26/0.26.

### 26.6 What is owed

On-glass verdicts (card art, hole-card plane depth, arc stagger, pacing). ✅ PC side deployed
2026-09-04 13:07 (`standby up (§19)`; the phone reattached to files, tmux, music, torrents, sync;
catalog 2,981 tracks, qBittorrent 39 transfers). APK 26/0.26 staged and verified (versionName
read from the manifest, md5 matches the freshly built `phone-debug.apk`); 0.16 still the last observed installed. The next window is
Adam's pick (`EXPLOSION.md` §20 has Feed at #5). Battery: core 419 · desktop 9 · selfcheck 162 ·
snapshots 49 (13 Games) · epub 58/58 · lint 0.

## 27. The whole-codebase review (2026-09-05) — the truth oracle, made a gate

The full cycle at Adam's ask: read, verify, fix, review the fixes, drive live at all four sizes,
repeat until clean.

### 27.1 What the reading found

- **A cash-out was booked as a total loss.** `GamesWindow.finishTournament` recorded net as
  `prize − myStake`; a cash-out (`HOLDEM.md` §10.2, verdict 11) has already moved the chips into the bankroll and `winner != seat`,
  so leaving with your stack read like busting; the entry FEE was left out too (every bot's net carried it since `castStake`). Now `prize +
  cashedOut − stake − fee`; `myCashedOut`/`myFee` persist with the table.
  `Review20260905Test.cashingOutCreditsTheChipsItTookOffTheTable` (−$200 where the truth is −$10).
- **Two gates measured nothing.** `--music-check` built its catalog through `MusicDb.catalog(v)`,
  whose default art predicate answers false for every track ("N likely have art" structurally 0);
  it builds through the library (which wires `Art.likelyHas`) and ASSERTS a flagged track extracts art. `--games-check` printed a
  money-supply "drift" as a head-to-tail ratio (large for any monotone series) and asserted
  nothing; it reports the growth RATE early vs late and FAILS when rising. Measured over 10,000
  tournaments: +387 % in total, the per-bucket increment falling ~$130 k → ~$95 k — the fee sink is
  winning slowly (`HOLDEM.md` §5.3).

### 27.2 What the LIVE run found — the truth oracle as a standing gate

The §25.1 oracle now runs in **`OracleWalkTest`** (a seeded random walk of the §1 grammar over a
real shell at all four heights, 240 steps each, belief = glass = truth after every settle, 12–18
distinct surfaces per height, asserting its own coverage) and in **`--selfcheck` on EVERY settle**
(279). Three defects no test had:

1. 🔴 **The Music Mode card inked past its own rect** (288 and 352): the progress row at
   `r.bottom − 14` under a face whose MEASURED ink is 20 — descenders 4 px below the card, outside
   the only rect `paintExclusive` reports; shipped on the full paint, never on a delta, so a later
   keyframe would produce a fragment of an older track. Card height and rows from measured ink (`music-mode-advance-288` reproduces it on revert).
2. 🔴 **Chrome text left its bar at the top of the font ladder.** §4.2's scale reaches 130 % and
   scales chrome, but §2.3's bars are fixed 32/28 px: at 130 % the title ran into the divider; at
   Alegreya (35 px of ink) and a reduced height the status descenders landed BELOW the safe rect.
   Every chrome line placed from measured ink (`Chrome.fitY`); the chrome's effective scale capped
   to what its bar holds (`Shell.chromeScale`) while CONTENT keeps the ladder.
3. **The status bar overflowed its bar at 100 %**: 24 px of ink at a 6 px inset in a 28 px bar ran
   2 px past since the bar was drawn. Same fix.

Hardened without a reproduction: the Music Mode queue peek stacked two 23 px lines on a 20 px
pitch in a 44 px band; the PC badge's 20 px rect held 20 px of ink drawn 2 px down. At 288 the
peek shows one row.

### 27.3 What the walk now covers

`--selfcheck` 162 → 189 checks: Music Mode with the queue ADVANCING (deltas), the window set at
130 %, and at the tallest face — at 480 AND 288 (at 480 an overflowing chrome line is clipped by
the panel edge and looks fine).

### 27.4 Battery

core 421 · desktop 9 · selfcheck 189 (oracle 279) · snapshots 49 · games · music · epub 58/58 ·
card-render · lint 0 · `verify_cfw.py` · APK builds.

### 27.5 What is owed

APK 27/0.27 staged; service restarted onto the build (`standby up (§19)`); 0.16 still the last
observed installed. Depth of reading: seam, replica, sync, window channels, simulator, transports,
shell line by line; the provider and desktop-harness leaves for their risk surfaces only. The
oracle sees settled surfaces only and the walk uses a fake rasterizer — font-metric defects are the
selfcheck's job.

### 27.6 The snapshot harness — three defects behind one intermittent failure

`--snapshot` run repeatedly failed about one run in four, never in the same place ("wait 'the
hand finishes' never became true", "did not settle at 'commit-games'" / "'back-to-main'" / "'?'
[in=torrents]"):

1. 🔴 **The settle re-tested its condition after the wait loop had passed it** — `while
   (!isQuiescent() && !expired) delay(20); if (!isQuiescent()) fail`: the second call races every
   periodic tick, and its diagnostic printed an EMPTY pending list (which identified it). **One
   evaluation decides** (the shape `SelfCheck.settle` always had); same fix in `waitFor`.
2. **The showdown scene assumed one action ends a hand**: Check and Fold are ONE contextual row
   (verdict 12), so with no bet to call the script CHECKED and the table waited forever. The scene
   acts every time it is Adam's turn, picking the row BY NAME, and says so after twelve actions.
3. **The world was seeded from the wall clock.** `--snapshot` and `--selfcheck` pin
   `gamesWin.roster.worldSeed = 20260905L` as `GamesCheck` already did (`Roster(worldSeed = …)`).

Harness bounds raised (settle 15 s → 60 s, waitFor 30 s → 120 s; both print anything over 5 s) —
backstops against a state that can never arrive, not budgets (every bot runs
`Equity.LIVE_ROLLOUTS` = 2000 rollouts per decision). Eight consecutive clean runs after. ⚠ PNGs
are not byte-identical run to run — the chrome clock is live (48 of 49 differ in that cell);
pinning the clock would freeze the pacer.

### 27.7 Where the next session picks up

1. Read `CLAUDE.md` → `REMINDER.md` → `HANDOFF.md` §19–§27.
2. The battery is the entry check: `./gradlew :core:test :desktop:test`, `desktop --selfcheck`, `desktop --snapshot DIR`,
   `desktop --epub-check ~/books`, `desktop --music-check`, `desktop --games-check`,
   `python3 tools/lint.py`, `./gradlew :phone:assembleDebug`.
3. **Run the harnesses more than once** — every §27.6 defect was invisible in a single run.
4. Nothing from §25–§27 has been seen on glass; 0.27 carries it all — the four heights, the 130 %
   ladder, Music Mode, a Hold'em hand, in that order.
5. Open items: §26.6, §27.5.

## 28. The third whole-codebase review, and the first full LIVE walk (2026-09-04, late)

Eleven verified defects; every pin run against the UNFIXED tree and watched to fail
(`Review28Test.kt`, five classes, and the desktop `ConfigTest`).

### 28.1 What the reading found (and what it did not)

Covered `core/`, `desktop/` (harnesses included), `phone/`, the Python tooling end to end.

1. 🔴 **The Hold'em pacer STALLED after a back-and-return inside one bot's pace** (and after a
   window switch, and after a peer's table record replaced the live one): every invalidation
   bumped `pacerGen` and left the stale completion to clear `thinking`; that completion saw the
   bumped generation and returned without pumping, and the re-entry's `pump()` was refused by the
   still-set flag. `cancelPacer()` bumps AND clears together; a superseded completion never touches
   the flag; a replaced table re-pumps.
2. 🔴 **The Reader could not open a book at 115 % or 130 %**: a constant 30 px line box with a
   guard REFUSING any layout whose ink exceeded it. Alegreya 17 inks 28 rows at 100 %, **34 at
   115 %, 36 at 130 %** — two rungs threw `LintError` off-loop ("could not open …"). `lineBox()`
   follows the face, carried in the loaded layout.
3. **Tmux's staleness line inked past its content rect**: `rect.bottom - 20` under JetBrains Mono
   14 (ink 20 / 22 / 25 at 100 / 115 / 130 %). Placed from measured ink.
4. 🔴 **An unreadable `config.json` was REPLACED with defaults**: one stray comma → `Config()`, a
   minted token, STORED — TorrentLeech credentials, tmux hosts and the phone's token gone. Defaults
   run for that start only; the file is left for the person (`audio/enrich/damage_config.py`
   already refused outright).
5. **Main described scans nobody had started** ("library loading", "0 locations" until each window
   was first opened). Both scan at registration, quietly; the activation re-scan skipped while the
   registration scan is in flight.
6. "1 hands", "1 tournaments", "1 lines" — the "You checks" class, on four surfaces.

Left alone: Music's scroll-up = quieter (the shell's grammar); the transport's narrow
late-FlushDone race (harmless); the silent small clock (already `fitY`).

### 28.2 The live walk — the instrument, and what only it found

**The instrument.** The desktop program in `--transport sim --no-preview` under a SCRATCH home
(`java -Duser.home=<scratch> -jar desktop/build/libs/damage.jar`, ports 7501–7504, a throwaway
token, `tmuxHosts` with a deliberately unreachable host), the real shelf, tmux server and library
behind it. A 150-line dependency-free Python driver on the replica's WebSocket (`/ws?token=…`):
`{"t":"input","ev":"tap|double|up|down|hold|release"}`, typed lines as `{"t":"text"}`; the binary
panel frames (`[arm][y0][rows][rows×320]`) decoded to true-1× PNGs, every surface LOOKED AT.
Lessons: **snap between steps** (a script that assumes where a cursor rests drifts; blind runs
changed five settings); **the scale ladder is where the constants hide** — every drawing defect
below was invisible at 100 %.

7. 🔴 **Every chrome surface with a hand-picked vertical rhythm broke at the top of the font ladder
   — the MENU, the NOTIFICATION BOX and the SWITCHER.** The menu's 18 px title band and 24 px row
   pitch put the title's baseline below its rule and the last row's ink past the box (220 px
   outside the damage rect at 120 % chrome); the notification's 16 px source band and 24 px pitch
   did the same (three body lines no longer fit the 104 px ceiling); the wheel's 88 px centre band
   dropped the name below the lower rule and the neighbour's descenders past the panel (440 px
   outside). All three measure the chrome face (`MenuSurface.titleH()/rowH()`,
   `Notifications.srcH()/pitch()/roomFor()`, the wheel's `bandH`), the design numbers as the floor
   — 100 % pixel-identical. ⚠ **All three measured the ASCENT, half the promise** — §30 #1, #2, #11
   found each still striking its own text with its rule at 100 %. Ink is `ascent + descent`.
8. 🔴 **Tmux never alerted for a pane that had not filled its screen.** The status script took
   `capture-pane -p | tail -5`, and `capture-pane -p` prints the whole visible pane, trailing empty
   rows included — five blank lines for any short session; only full-screen Claude sessions ever
   worked. Blank rows dropped before the tail.
9. 🔴 **The Hold'em status line was CUT by the hole-card plane under a per-app scale**: a 24 px
   constant band; at 130 % its 33 px line ran into the `holePlane` region (plane 0, unshifted) — a
   horizontal cut at 480/416/352. `TableLayout(statusH, lineH)` from measured ink (30 at 100 % —
   Clear Sans 17 bold inks 25; the seat strip gives up six rows); a seat line that would run past
   its cell is dropped.
10. The notification box at 130 % — item 7's third member.
11. The switcher at 130 % — item 7's second member.

Covered clean: Main, Reader (480/288, 130 %), Tmux (a session created from the glasses, a typed
line behind its confirm), Files, Torrents, Music, Games (a full hand through the sizing ladder and
the Custom keyboard, showdown, cash-out, play-out), Settings, silent mode with both clocks, the
switcher, the keyboard, a notification taking focus, 130 % and Alegreya as chrome — zero
divergence reports across four instance logs.

### 28.3 Pins, and the vacuity check

Every pin run twice (fixed → PASS, fix stashed → FAIL). `Review28Test` (the pacer stalls),
`Review28ScaleTest` (the book at 130 %, the tmux line — on a `ScalingText` whose ink follows the
size, the blind spot `FakeText`'s constant 12+4 metrics leave), `Review28MainLensTest`,
`Review28PaintTest`, `Review28SwitcherTest`, desktop `ConfigTest`.

### 28.4 Battery

core 430 · desktop 11 · selfcheck 189 (oracle 282) · snapshots 49 × three clean runs · games ·
music · epub 58/58 · card-render · lint 0 · APK builds.

### 28.5 Where the next session picks up

✅ Deployed 2026-09-04 18:16 (`standby up (§19)`, nothing on glass touched); APK 28/0.28 staged;
0.16 still the last observed installed. The live-walk driver is a standing instrument — the only
one that runs the REAL providers under the real grammar; rebuild it in ten minutes rather than
script blind (§28.2). Open: §26.6, §27.5, the install.

## 29. The fourth whole-codebase review, and the second full LIVE walk (2026-09-04, evening)

Eleven verified defects, each pinned against the UNFIXED tree (`Review29Test.kt`, one pin each in
`TmuxTest.kt` and `MusicWindowTest.kt`).

### 29.1 What the reading found

1. 🔴 **The list rhythm was the last constant of the §27/§28 class — already cutting ink at 115 %.**
   Clear Sans 18 (every list's row face) inks **27 px at 100 %, 32 at 115 %, 34 at 130 %**; the
   design's 32 px row held the 27 under the rows' 5 px offset EXACTLY, so one step up the row above
   the lens lost its descenders (the lens band clears itself over the rows painted first — live:
   the `$` and comma of "$1,000" cut flat). The oracle is blind to it (the ink lands inside the
   damaged rect). Now `Layout` carries `rowH` / `lensH` (floors 32 / 64); `Shell.listRhythm()`
   derives them from the row face's measured ink through the transform on screen (rows `5 + ink`,
   lens `2 × ink + 10`); `ContentKit` hangs the rows above FROM the lens; every second lens line is
   placed by `Draw.lineBelow` (Main, Settings, Reader ×3, Tmux ×3, Files ×4, Torrents, Music,
   Games). Consequence: a window whose per-app scale differs from the chrome's has a different
   rhythm — switching to it is a relayout with a keyframe.
2. The Font size row read "114%" for 115 % (`(1.15 * 100).toInt()`); `ShellSettings.scaleLabel` rounds.
3. **A failed transport start released the lease into a race**: the rollback enqueued the FB RELEASE
   and called `disconnectLink()` at once (the write never reached the wire, or hit a link being torn
   down and logged a "control lane error"). The rollback awaits its release through one shared
   `awaitReleaseWrite`.
4. The seam client's `stop()` closed the socket outside the try, skipping the state update and the
   outstanding-flush sweep on a throw. Guarded.

Left alone: `commitWindow` under EXCLUSIVE is unreachable; the throughput cell's idle repaints are
paced by the 5 s idle tick (§8.3); the Torrents "not configured" paced retry is the R4-P5 design;
Tmux keeping 480 under a global 288 is its own Size row. ⛔ "The wheel's centre-name descenders
cross the lower rule by design" — **WRONG, reversed by §30 #11.**

### 29.2 The live walk — what only it found

The §28.2 instrument (unreachable `ghost.invalid` tmux host, a copy of the shelf, the real tmux
server, qBittorrent, the music library). Lessons: **the replica's pings are not frames** (a
quiet-detector counting them waits 20 s a step); **a blind gesture run in a window with
irreversible rows is a real risk** — one assumed cursor rest started a stopped torrent on the real
qBittorrent (stopped again via its API) and reached the first of two delete confirms; **never
rebuild the jar in place under a running instance** (lazy class loads fail — how #6 was found).

5. 🔴 **The tmux alert was app-less: a tap on "g2-1 wants input" only dismissed it** — no `appId`,
   no `target`, no `open(target)` — `TMUX.md` §3.5's "glance → tap → `y`" ended at the glance. The notice names window and session (`session:<host>:<name>`),
   coalesces per SESSION; the deep link opens that session's live view.
6. 🔴 **The shell loop caught `Exception` only.** An `Error` out of a handler ended the loop: the
   display froze while the transport kept renewing the lease and the keeper said "running"; the rebuilt-jar `NoClassDefFoundError` is not the only
   way (an OOM in a paint is another). The loop survives an `Error` loudly (log, journal, status cell ERROR).
7. **Brightness could never go back to auto**: a notch left auto at the stored level and the manual
   ladder ended at 0 %. One ladder, auto at its foot.
8. The custom-amount keyboard said "raise to" over a checked-through flop. The verb follows the bet.
9. Music's idle root at 130 %: the caption sat inside the descenders of "nothing queued" (a
   constant 48 px under a 36 px face). Placed below measured ink.
10. **The context menu under a bigger chrome face**: a fixed 248 px box read "Fold and ▸" at the
    120 % cap — it follows the row face (308 at 120 %, never wider than the content); a detail cut
    at its HEAD by the tail-keeping fit carried no mark — it gets the drawn mark.
11. (Item 1's crop is the walk's evidence.)

Covered clean: Main at 100/115/130 % and Alegreya; Reader at all heights and 130 %; Tmux with the
alert's deep link; Files; Torrents (browse without a tracker refused loudly, the search keyboard);
Music at 100/130 %; Games at 480 and 130 %; Settings incl. the brightness ladder; silent mode; the
wheel; 288/352/416/480 for Main, Settings, Reader, Games — zero divergence reports across three logs.

### 29.3 Pins, and the vacuity check

`Review29Test` (the row above the lens keeps its ink at 130 % — inked rows counted against Clear
Sans's measured metrics; a grown rhythm grid-legal at every height; the ladder labels; the
failed-start release reaching both arms before the disconnect; brightness returning to auto; the
keyboard's verb; the loop surviving an `Error`; the menu box following the face and a head-cut
mark), `TmuxTest.anAlertNoticeDeepLinksIntoItsSession`,
`MusicWindowTest.theIdleCaptionSitsBelowTheBigLinesInk`. Every one failed against the unfixed tree.

### 29.4 Battery

core 440 · desktop 11 · selfcheck 189 · snapshots 49 × three · games · music · epub 58/58 · lint 0
· APK builds.

### 29.5 Where the next session picks up

✅ Deployed 2026-09-04 22:00 (Adam's word; `standby up (§19)`); APK 29/0.29 staged
(byte-identical to the build output); 0.16 still the last observed installed. The live-walk driver:
snap between steps; one step per snap in any window with an irreversible row; count only panel
frames as activity. Open: §26.6, §27.5, the install.

---

## 30. The fifth whole-codebase review, and the third full LIVE walk (2026-09-05)

Nineteen verified defects, each pinned against the UNFIXED tree (`Review30Test.kt`, plus one each
in `TorrentsTest.kt`, `MusicWindowTest.kt`, `Review28Test.kt`).

### 30.1 What the reading found

Five of seven are §27's rule one layer down:

1. 🔴 **The notification box's source band was a 16 px constant under a face that INKS 20** (Clear
   Sans 13 bold: ascent 16, descent 4): the rule at 16–17 struck through the source line, the `+N`
   badge and the timestamp — visible in `snapshots/08-notification-focused.png` all along. Band
   measured; each line placed from its own ascent.
2. 🔴 **The context menu's title band, the same defect** — and the rule was painted LAST, overwriting
   whatever it crossed. Rule first, title over it.
3. 🔴 **The chrome clock's AM/PM marker sat at a constant `cell.x + 52`** — a ZERO gap at 100 %
   ("10:20PM"), INSIDE the last digit at 115 % (53 px). `Chrome.clockWidths()` measures the widest
   `h:mm` (22 measures, checked against all 720 real times); `Layout.clockW` with §2.3's 80 as the floor.
4. The medium seven-segment readout's minute pair sat at 56 and 84 (a 28 px pitch where every other
   pair is 24 — "10:2 1"). 80 now; every gap 6.
5. 🔴 **The Games documents sized their line box from `metrics().lineHeight`, which is not the ink**
   — one to two rows SHORTER than ascent+descent for Clear Sans; a 17 px bold heading in a 13/16 px
   body drew 3–8 px past its line rect, and `Shell.paintDocSlice` renders each line into a buffer
   one line box tall, chopping descenders on the first scroll. `docLineH(vararg faces)`.
6. **The Files locations and trash lenses drew NOTHING for an empty list**: a one-row list is drawn
   ONLY by the lens, so the row painter's placeholder was unreachable. Both lenses say what is empty
   and why; a tap asks the host again.
7. **`TableLayout`'s "the bottom bands give way" claim was false** — it floored the seat strip and
   pushed the bottom band past `content` — an escape only `check()` would have caught, and nothing
   on the paint path calls it. The optional bands give way, bottom first, loudly;
   `showsYourLine` / `showsHistory` answer from the BAND.

### 30.2 What the second reading found — in the fixes, and in the gates

8. 🔴 **A line box is the LARGER of the face's line height and its MEASURED ink.** AWT ceils ascent
   and descent separately and the height once: JetBrains Mono 16 inks 25 rows against a 24 px line
   at 115 %, 29 against 28 at 130 %. Fixed in `FlowRender.lineH`, `TermRender`'s cell, the
   keyboard's centring and caret, the Hold'em history's pitch.
9. 🔴 **The standing `--selfcheck` truth oracle failed about one run in ten, and had for as long as
   it existed** — MEASURED 2 in 20 on the unchanged tree. A whole-surface difference (16,963 px at
   `scale130-reader-in`) with the plane map one region short: the SAMPLE was torn — `isQuiescent()`
   answers about one instant from another thread, then the oracle read `comp.composed`,
   `comp.planes` and both panels across a window the shell can repaint inside. `Shell.sampleIdle`
   takes the whole reading ON the loop with nothing queued; `SelfCheck.runOracle` and
   `OracleWalkTest.assertOracle` go through it. **20/20 clean after.**
10. **The selfcheck's oracle kept pointing at the STOPPED shell after the restart scene** (sim2 /
    shell2 never re-registered) — a free pass. It follows the live pair (282 → 283 oracle runs).

### 30.3 The live walk — what only it found

The §28.2/§29.2 instrument, unchanged.

11. 🔴 **The switcher's centre band was sized from the name's ASCENT**: the name at `bandTop + 64`
    inks `ascent + descent`, the lower rule at row 288 while "Settings" inked at 290–292 — at 100 %
    as well as 130 %. §29 recorded the opposite after measuring the wrong half. Band = measured ink
    plus two rows; the upper neighbour clamped off the rule.
12. 🔴 **The tmux staleness surface (§10.5) reached the live pane and nowhere else**: `ghost` failed
    its poll for half an hour and the sessions list read clean (`provState` painted only by
    `paintLive`). It rides the TITLE on every level and the summary carries it into Main's row.
    **Torrents had the same shape** and takes the same fix.
13. 🔴 **The Hold'em seat strip painted its stack line through the board**: at 288 with the global
    scale at 130 % the status band grows to 38 and leaves the strip a 120×34 cell while the 15 px
    name and 14 px stack want 52 rows. The seat FACES step down until two rows fit; failing that
    the strip goes COMPACT (one row per seat, money right-aligned and placed first). ⚠ The first
    fix DROPPED the stack row — money gone from a poker screen; the walk's second look caught it.
14. A STAGED settings row said "scroll adjusts live". Staged rows say "scroll picks · tap applies ·
    double-tap reverts".
15. Music offered Resume / Next / Previous with an empty queue (the §26 trap). Dim now; **a menu
    opens its cursor on the first row that can act.**
16. The notification box was anchored to its design box's TOP edge. Centred on its own height; 100 % untouched.

### 30.4 What the ORACLE WALK found — the one no reading and no walk would have

17. 🔴 **A wheel closed mid-spin never stopped spinning.** `Switcher.spinning` is `spinPos !=
    cursor`, the drum steps only while OPEN, and `close()` left `spinPos` short — a scroll then a
    commit or cancel inside the four animation frames left the flag true for ever; the frame loop
    posts `Msg.Pump` while it is true and `isQuiescent()` reads the same flag: an unbounded loop of
    empty frames on the glasses and a shell that never reports idle. Caught as `h=288 step 180
    (LONG_PRESS_RELEASE): queued=1 reports=0` after a 120 s bound — looks like load until the worst
    clean settle is measured at **46 ms**. `close()` stops the drum; `spinning` is `open &&`-gated.
    Pinned twice (the shell-level pin posted from the loop — from the test thread it races and
    passes either way).

### 30.5 Pins, and the vacuity check

`Review30Test`: the notification rule does not strike its source line; the menu rule does not strike
its title (an order-independent DIM-pixel count — the naive pin passed against the unfixed tree);
the Games documents hold their ink; Files says why an empty list is empty; the clock marker never
touches the time (time and marker rendered into SEPARATE surfaces, compared by lit-column extent —
the first attempt passed with the old constant because the fake's uniform advance made the time
narrower); the medium clock's spacing; the switcher band; the seat strip inside its band AND still
drawing the money; tmux quiet on every level; a staged row's wording; the flow line box; a wheel
closed mid-spin stops; the shell settles after it.
`TorrentsTest.aDetailsPageSaysTheHostHasStoppedAnswering`,
`MusicWindowTest.theTransportRowsAreDimWhenNothingIsQueued`. Two caught vacuous and rewritten.

### 30.6 Battery

core 456 · desktop 11 · selfcheck 189 (oracle 283, **20 consecutive clean runs** where the
unchanged tree failed 2 in 20) · snapshots 49 × three · games · music · epub 58/58 · card-render ·
lint 0 · APK builds. What legitimately varies between two `--snapshot` runs: the wall clock, the
throughput/ack readouts, Files' free-space figures, Music Mode's visualiser.

### 30.5b The pass after that — sweeping §30's own classes across the tree

18. 🔴 **The Reader's library level drew nothing at all for an empty shelf** (§30.1 #6 one window
    over): `ContentKit.paintList` returns on ZERO rows and the library's count is `folders.size +
    books.size` — before the first scan, for an empty library, and for a FAILED scan the window
    cleared its band and drew nothing while Main's row said why. It has the row that says so
    (`libraryState`'s words under it) and a tap that scans again (op cell "rescanning" — "reading
    the shelf again" arrived as "reading the ▸"; the cell is 128 px). The sweep: every
    `ListView`/`DocView` checked for a zero count — Files coerces to one row; Torrents' lists carry
    a tail row (R3-P11); Music's end in `Row.Menu`; the chapter picker carries "From the beginning".
19. The snapshot harness took its picture the way the oracle used to — §30.6a.

**Rejected on verification:** the two three-line lens guards (`GamesWindow.lensThirdFits`, the
Torrents listing lens) admit their third line by ASCENT; tightening to whole ink DROPPED the line in
five scenes at 100 %, and the render measured says the guards are right (in `42-games-tables` the
band rules sit at 210 and 272, the third line's ink at 262–271 — one row clear). Reverted. A fifth
pass found nothing further.

### 30.6a The snapshot harness takes its picture the same way

`--snapshot`'s `save()` read the sim panel from the script thread too; it goes through
`Shell.sampleIdle` now. Measured over consecutive-run pairs: six scenes differed before, four after;
the one that stopped differing was `18-torrents-categories` (the theme's arrow in two runs of
three, the drawn fallback in the other). What still differs is live data only.

### 30.6b One thing left open, honestly

`OracleWalkTest` failed ONCE more after the wheel fix — `h=288 step 198 (SCROLL_UP): queued=1
reports=0` — and not in eight consecutive full-suite runs since. `queued=1` with everything idle
means a handler running for the whole 120 s or a message the loop never took; a clean run's worst
settle is 46 ms, so "load" does not explain it. Not closed. `settle` prints the stack of every
thread inside `wm.damage` when it gives up; `GamesWindowTest` and `ActivationTest` print
`quiescenceReport()`. Read the stacks first (continued §31.8).

### 30.7 Where the next session picks up

**Not deployed**: the service runs the §29 build; APK 29/0.29 staged; deploying is Adam's call.
**The harness is part of the system under review**: two of the nineteen were in the gates, and the
sharpest defect came out of a test bound firing — when a bound fires, measure the normal case
before calling it load.

---

## 31. Canvas scrolling ships the translation (2026-09-05, Adam's report)

Adam, on glass: *"scrolling text in apps like Tmux is really slow, like 1-1.5 full seconds between
swiping on the ring and seeing the text actually jump to the new position."*

### 31.1 What it measured, at both ends

**What a tmux scroll sent** (MEASURED, the sim's encoded bytes): one mode-3 delta covering the WHOLE
content area — 7,395 / 8,380 / 9,942 / 10,225 B on four consecutive scrolls. A list scroll in the
same session: mode-9 copies plus small deltas, 72–2,215 B.

**What that costs on the glasses** (MEASURED, 11,210 flush pairs in the production journal, flushes
with nothing queued ahead):

| flush size | n | median submit→ack |
|---|---:|---:|
| < 500 B | 3,137 | **65 ms** |
| 0.5–1.5 KB | 456 | 152 ms |
| 1.5–3 KB | 220 | 196 ms |
| 3–6 KB | 240 | 526 ms |
| **6–12 KB** | **277** | **1,193 ms** |

10 KB ÷ the ~6.9 KB/s those flushes imply ≈ 1.45 s — Adam's number from the other direction.

### 31.2 The mechanism

`Shell.scrollFocused`: `ListView` → `startListSlide`, `DocView` → `startDocSlide` (mode-9 copy +
the exposed strip); `CanvasView` → `h(delta); composeContent()`, and `paintContentOf`'s canvas arm
was `paint(); damage(content)` — every row moves, so the truth diff correctly found the whole area
changed. Tmux's pane and scrollback, Music's lyrics/volume/Now Playing and the Hold'em table are
canvases. The damage rect is a scan hint, not a payload (a live pane's own updates cost ~2 KB); the
translation simply was not declared.

### 31.3 The fix, and why it is a DETECTOR

`CanvasShift.detect` compares the frame before the repaint with the frame after, finds the vertical
translation, and the shell declares it — not a field on `CanvasView`: every canvas window gets it
(including a pane the terminal itself scrolled, and windows not written yet; exclusive mode goes
through the same helper), and the block is verified byte for byte before declaring. And
`Compositor.declareShift` replays the copy onto the per-lens SHADOWS and diffs them against the
truth, so a wrong shift costs bytes to repair, never correctness.

Load-bearing details: `declareShift` gained `movePending` (the slide path records damage THEN
shifts, so its pending rects travel; a canvas repaint's damage is already the new frame's — canvas
and exclusive pass false); uniform rows get no vote in choosing the offset (a blank half-pane would
elect a shift that saves nothing); the detector refuses a region off the compositor's 4×2 CELL grid
— ⚠ NOT a firmware rule: mode 9 takes full uint16 coords (`zlib_glue.c`) and `rect_copy_4bpp` has
a nibble path for an odd left/width; the constraint is OURS, because `moveCells` moves the per-lens
`unknown` marks cell-quantised (`CW`/`CH` are `X_STEP`/`Y_STEP`).

### 31.4 The bug inside the first draft of it

The byte verification advanced its cursors AND indexed by the loop variable (`pix[a + 2i]`), so it
declined every shift there is (a probe showed it finding real translations — `dy=-110`, 278 rows
matching — then failing verification on the first row); the first live measurement after the "fix" was unchanged. It failed
SAFE — only a measurement found it; the positive case of the unit pin is the pin for it.

**A second on a re-read:** the offset sweep runs `dy = -h + 2` stepping by 2, so for an ODD region
height every candidate offset is odd and the copy lands on an odd row. `detect` requires an even
height. 🔴 The first write-up (and the code comment) said the firmware refuses an unaligned mode-9
rect in silence — **false, asserted without reading `zlib_glue.c`**; mode 9 has no alignment
requirement. The real consequence is confined to the `unknown` cell map (matters only after a lost
flush); bookkeeping hygiene, not a wrong frame. Unreachable today (`layout.content.h` is even; no
window returns an odd exclusive rect). Pin lessons: the failing combination is odd height AND an
odd shift (the first pin used the 40 px shift and passed with the guard removed; watched to fail
only with an odd shift, `src=(16,81 608x368)`); the pin holds the guard to PARITY, not size.

### 31.5 What it costs now

| | before | after |
|---|---:|---:|
| entering history (not a translation — correct) | 8,405 B | 7,962 B |
| steady-state history scrolls | 9,756 / 11,086 / 11,216 B | 3,815 / 5,583 / 5,401 B |

**~11.1 KB → ~5.4 KB**; through the table above ~1,193 ms → ~526 ms (MEASURED bytes, MODELED ms).
What remains is the exposed strip: `HIST_STEP` is 5 lines, the strip 106 px, five dense lines
encode to 3–5 KB; the only other lever is the step size (a design decision). Also `Gray8.blit`
copies whole rows with `System.arraycopy` when nothing clips (pinned in eight clipping shapes).

**How "no rendered surface changed" is checked:** the 49 snapshot scenes are NOT byte-comparable
between runs (the live throughput readout). Keep BOTH installs on disk (build, copy
`desktop/build/install/desktop` aside, `git stash`, build, copy, `git stash pop`) and run them
within a minute: **46 of 49 differ only inside the status readout** (x∈[240,400], ≤16 rows),
`10-silent.png` byte-identical, three are live data (`11-files-locations`,
`38-music-mode-480-bars`, `39-music-mode-288-scope`). ⚠ A plain `diff -rq` always reports all 49.

### 31.6 🔴 A separate finding: the documented latency curve describes four hours

`overview.md` §5.2's `ms ≈ 60 + bytes/50` (n=1,488; 6–15 KB at a median 201 ms) is one session:

| when | n | < 500 B | 2–6 KB | 6–12 KB |
|---|---:|---:|---:|---:|
| 08-30 | 1,130 | 60 ms | 142 ms | **196 ms** |
| 08-31 00:00–03:00 | 3,038 | 55–74 ms | 113–142 ms | **198–265 ms** |
| 08-31 13:00–19:00 | 7,025 | 73–78 ms | 465–812 ms | **1,087–1,286 ms** |

A step change between 03:00 and 13:00 on 2026-08-31: the FLOOR barely moves (55–78 ms) while the
TRANSFER term collapses ~6× (~50 KB/s → ~7 KB/s, the §5.1 stock figure). 10,063 of 11,210 flushes
are on the slow side; Adam's 1–1.5 s agrees. Cause: the radio PATH (§32.2). `CLAIMS.md` regrades it.

### 31.7 Pins

`Review30Test`: `theCanvasShiftDetectorFindsATranslation` (right offset, grid-legal,
byte-identical; unchanged / unrelated / under-floor / unaligned declined; the sub-band form
exclusive mode uses); `aCanvasScrollShipsTheShiftNotTheScreen` (a real shell over the sim: a scroll
costs under a third of a full change of the same window — no absolute byte number; it also reads
both SIMULATED PANELS through `sampleIdle`, asserts the panel is not blank first, watched to fail on
one flipped pixel); `theWholeRowBlitAgreesWithThePerPixelOne`. All failed against the unfixed tree.

🔴 **A coverage gap closed:** `paintExclusiveDelta` had NO coverage — the walk's
`SurfaceWindow.paintExclusive` returned an empty list for `full = false`, and exclusive mode
SWALLOWS every ring input (§4.9), so nothing ever asked for a delta. The walk's window now paints a
translating band (a strict SUB-rect of `safe`) and asks for four renders on entering exclusive
mode: 52 exclusive deltas, ~50 declared translations, oracle green. **A paint arm that returns
nothing, or a mode that never invalidates, is a function the oracle cannot see.**

### 31.8 A second, unfinished thread: the intermittent `queued=1` settle

`:core:test` failed roughly one run in four with `shell did not settle: queued=1 reports=0
status='ok'` across five classes (`OracleWalkTest`, `GamesWindowTest`, `ActivationTest`,
`LongPressTest`, `MusicModeTest`), never the same twice; reproduced on the unmodified tree.
Established: `OracleWalkTest.busyThreads()` reported **no thread with a `wm.damage` frame** — the
loop is parked or ended, not busy; no `Dispatchers.Default` starvation by our own code explains it; `msgs` is `Channel.UNLIMITED` and
never closed; `loopLaunched` is never reset but `startLocked` launches the loop unconditionally. Instrumented: `post()` undoes its own count on a refused
`trySend`, loudly; `loop()`'s `finally` drains what is left and decrements for it;
`quiescenceReport()` says `LOOP-ENDED` or `in=<Msg>/<ms>ms`. ⚠ Eight clean runs after is roughly a
1-in-10 outcome if nothing changed — **not fixed.** The next failure names which case it is.

### 31.9 Battery

core 459 · desktop 11 · selfcheck 189 (ALL PASS ×11) · snapshots 49 × two builds back to back ·
epub 58/58 · music · games · lint 0 · APK builds. `:core:test --rerun-tasks` ×14 with no failure
(still not proof for §31.8).

## 32. The latency pass (2026-09-05, Adam's ask) — the survey, twelve changes, and the joint plan

Adam: *"tell me all of the things that can be done across the whole scope plus each individual
window to optimize it for latency and quick response and quick loading with minimal impact on
design aesthetic or good looks or features and usefulness"* — *"do all the things you can do
without me."* The phone APK driving the glasses is the priority.

### 32.1 What the survey found

Where link time goes (MEASURED, 11,206 acked flushes):

| flush size | share of flushes | share of ack time |
|---|---:|---:|
| < 500 B | 67 % | 35 % |
| 0.5–3 KB | 24 % | 24 % |
| ≥ 3 KB | 9 % | 41 % |

Op mix: 25,863 deltas, 4,708 copies, 420 stereo pairs, 94 keyframes. Wheel flushes (`+switcher` labels): median
0.8–0.9 KB, p90 2.5–3 KB, median ack 119–133 ms.

Levers, ranked: (1) the radio path (§32.2); (2) the texture cache for text — a list row ~1–2 KB of
pixels today, 8 bytes plus the string as mode 14, no fid (built §40.6/§41.4); (3) a reserved window
slot for input — the pump filled all three slots, so a tap's first flush could wait behind three
150–1,200 ms frames (shipped, §32.3); (4) motion adapted to the link — the wheel's 4 repaint frames at
~0.9 KB are ~0.8 s of link per notch through the phone (modeled; shipped for the wheel); (5) host
CPU on the loop — `price()` recompressed every candidate per pair, every drawn string a fresh
bitmap, the state file written on the loop (shipped, pixel-identical; the numbers in §32.4); (6) wrap cost — the Reader
measured one platform measure per WORD: 13–80 ms per book on the PC (MEASURED, three books, AWT),
seconds on Android (MODELED) (shipped); (7) per window: Reader extraction 24–226 ms per book
(MEASURED, all 58), no layout cache across height/scale, a book opened first on the replica is a
full EPUB transfer; tmux 1 s polling averages 500 ms of lag (control mode `tmux -C` is the
redesign), a fresh `ssh` per poll (shipped: multiplexing); Torrents' `Http` `disconnect()` after
every request (shipped: keep-alive); Music Mode's visualizer (off by default, 8 fps on) a flush per
frame; the rest nothing significant.

### 32.2 🔴 The slow regime is the phone's radio path — and the captures said more than we read

§31.6's step change is a change of RADIO PATH, not time (grade **C**): the fast hours are the
PC-direct sessions of §11–§12; the slow hours carry stall notes naming `aphone` and `damage.log`
reads `driving via remote:aphone` — the PC shell through the PHONE's BLE (the pre-§19 mode). Not
**M** because the journal carried no transport field (it does now, `via`); confirmed **M** in §33.1.
The ~6× is inside the phone's BLE stack, not the glasses.

**The captures:** `overview.md` §5.1, `CLAIMS.md` row 44 and `captures/README.md` all said handle
65's connection setup was outside both windows and no `LE_Connection_Update` was ever issued.
`research/linkparams.py` (new, offline, stdlib) says otherwise (grade **M**): both captures hold the
Enhanced Connection Complete for all three handles; handle 65's peer address equals the RIGHT lens;
it runs at **30 ms** while active and the **glasses** move it to **90 ms / slave latency 4** when
idle (three L2CAP 0x12 requests in `allbutimages.log`, each granted with an
`LE_Connection_Update`); DLE on (247 B); no PHY updates. What stays true: the official app never
asks on its own initiative for 65. All three documents corrected. At 30 ms and ~1.6 packets per
event you get 7–13 KB/s; whether `CONNECTION_PRIORITY_HIGH` (11.25–15 ms) is granted is answered in §33.2.

### 32.3 What shipped — twelve changes, each self-contained, no rendered surface changed

1. The journal carries `via`, `handleMs`, `assembleMs` per submit (`Journal.flushSubmitted`,
   `Shell.pump`); `tools/journal_report.py` reads by hour, `via`, size band, and prints every
   link/fault/panic note.
2. `Compositor.compressCache` memoises `compress(rect)` for one assemble.
3. Both rasterizers cache measures and coverage (`core/text/GlyphCaches.kt`, `AwtText`,
   `AndroidText`): keyed by text + RESOLVED font; wholesale clearing at 16 k measures / 8 MB of masks.
4. `Wrap.wrap` decides from an additive estimate outside a ±24 px band and measures exactly inside
   it; each distinct word measured once. `WrapEstimateTest` pins equality with the every-candidate
   loop for an additive rasterizer and one kerning a full pixel tighter at every space.
5. One window slot reserved for input (`Shell.pumpPriority`): the pump after a ring event or typed
   line may fill the window; every other pump stops one short.
6. `LinkState.floorMsEma` (flushes < 400 B) and `transferMsPerKbEma` (≥ 1 KB) tell the regimes
   apart (~20 and ~125 ms/KB measured); **the wheel spins in 2 frames instead of 4 above
   50 ms/KB**; nothing adapts until a ≥ 1 KB flush has been timed. `DESIGN.md` §6.3.
7. The state file is written off the loop (`Persistence.saveAsync`; the shutdown save synchronous).
8. `Http.request` keeps connections alive (`disconnect()` only on failure; POSTs never retried).
9. Remote tmux hosts multiplex one ssh connection (`ControlMaster=auto`, `ControlPersist=60`,
   `ControlPath=~/.damage/ssh-%C`; falls back to plain with a warning).
10. The APK reports its connection parameters and PHY (`BleTransport`, Nordic 2.7.5's
    `setConnectionParametersListener`, the priority request's `.with` callback, `readPhy()`) into
    `LinkState.linkParams`; journaled as `kind: "link"`.
11. Every host serves `GET /journal?token=T[&tail=N]` on the replica server (200 with the token, 403 without).
12. `research/linkparams.py`.

Not touched on purpose: scroll step sizes, list/doc slide frame counts, the 5 s idle chrome tick,
Main's rest repaint, the visualizer rate.

### 32.4 What it measured

No rendered surface changed (the §31.5 method with a Java comparer): 49 scenes — 2 byte-identical,
45 differ only inside the status readout, 2 live-data. First compose/assemble numbers, PC, sim: the
Main keyframe handle 30 ms / assemble 21 ms; a delta 13 / 9 ms; over a 22-flush scratch session
median 4 / 2 ms, p90 13 / 9 ms. Reader, PC: extraction 24–226 ms per book (all 58); the wrap
13–80 ms (three books, 3k–33k lines). The scratch sim journaled `transfer 61 ms/KB — SLOW regime`
(the sim models the stock curve), so the wheel spins in 2 frames under `--transport sim`; the
harnesses run instant timing and stay at 4.

### 32.5 Battery

core 462 · desktop 11 · selfcheck ALL PASS (283 oracle runs; ×10) · snapshots 49 × three · epub
58/58 · music · games · lint 0 · APK builds · `/journal` live.

### 32.6 What is owed — the joint plan, in order

Superseded by §33.7 → §37.3 → §40. For the record: deploy + APK 30/0.30; read the phone's journal
after a day (`curl -s 'http://aphone:7403/journal?token=…' | python3 tools/journal_report.py -`);
the radio work (if HIGH is refused, re-request; if granted and still ~125 ms/KB, a phone BTSnoop via
the bug-report mail path); the texture cache on glass then adopted; verdicts on the 2-frame wheel
and the reserved slot; per window: tmux control mode, the Reader's phone-side wrap + layout cache,
prefetching the open book, a persistent content channel, cold-start timing (parallel arm connects,
the 800 ms prelude settle, the 2 s capability re-ask).

## 33. The live measurement, driven from the PC (2026-09-05, Adam: "I don't have a day")

APK 30/0.30 installed and driving; the PC drove the phone's shell through its replica WebSocket
(`tools/glassdrive.py`, a mirror snapshot between phases) and read `/journal` back. Ten minutes,
456 acked flushes, gestures 2.5 s apart so every flush is ISOLATED. Walk: wake → Main ×6 → Reader
→ Classics → Frankenstein → 6 notches and back → Tmux live pane → 4 notches of history → the
switcher chord, 3 notches, cancel → Torrents ×8 → Main → silent. No irreversible row tapped.

### 33.1 The daily path, measured on the phone itself (grade **M**)

| flush size | n | median ack | p90 ack | median handle | median assemble | p90 assemble |
|---|---:|---:|---:|---:|---:|---:|
| < 500 B | 283 | **72 ms** | 231 | 74 ms | 53 ms | 79 |
| 0.5–1.5 KB | 93 | 203 ms | 815 | 81 | 29 | 90 |
| 1.5–3 KB | 38 | 358 ms | 851 | 107 | 78 | 112 |
| 3–6 KB | 33 | 667 ms | 838 | 114 | 78 | 111 |
| 6 KB + | 9 | **1,036 ms** | 1,543 | 127 | 84 | 105 |

Five days of the phone's journal (28,657 flushes, 08-31 → 09-05): 68 · 200 · 390 · 641 ·
**1,237 ms** by the same bands. **The phone path IS the slow regime**: ~120 ms/KB above a ~70 ms
floor, ~8 KB/s on big flushes. Largest of the walk: the Torrents list opening, 11,050 B, five
deltas, 1,543 ms; the Main keyframe 9,226 B, 1,276 ms; a Reader notch through the cover image
8,427 B, 1,170 ms. (⚠ handle/assemble here double-count — §35.1.)

### 33.2 🔴 HIGH priority IS granted — and it does not help

The `link` notes at 15:48:11: **`L 15.00ms/1/5000ms phy 1M/1M · R 15.00ms/1/5000ms phy 1M/1M`** —
15 ms interval, slave latency 1, 1M PHY, both arms — `requestConnectionPriority(HIGH)` is honoured;
the transfer term still ~120 ms/KB. The
interval is not the wall. Candidates left: packets per event (the phone's write path, one write per
callback), BT/Wi-Fi coexistence, the glasses' receive path. The regime EMA flipped to SLOW at
15:51:52 (69 ms/KB); the wheel spun in 2 frames from then.

### 33.3 🔴 The phone's CPU is now a term of its own

PC: ~4 ms handling + ~2 ms assembling. Phone: a median 74–127 ms handling and 53–84 ms assembling
per flush (⚠ §35.1: `handleMs` INCLUDES the assemble). Splitting the loop time is the next instrumentation.

### 33.4 Lost acks, and what they cost

At 15:54:05 three fragment acks were lost together (`msgId 3/4/5 pending across a full counter
cycle`) and the shell raised the fault notice over the book. A lost ack held its window slot until
the msgId cycle came round (249 messages). Five days: 49 lost acks (31 on 08-31), 2 full-window
stalls of 25 s and 48 s, 55 control-lane "write characteristic gone" edges, 5 failed flushes
(supervision timeouts). ⚠ §34.3: 45 of the 53 were session-start control pendings, not image
losses. The references slide the window through up to ~3 missed acks (`overview.md` §5);
releasing an earlier pending image ack on a LATER msgId's ack was built in §34.1.

### 33.5 What the walk also showed

The switcher from Main centred on the most recent window (§1.3, as designed); Frankenstein's first
open went to the chapter picker; the tmux quick-keys list sits one tap below the pane; every
snapshot matched the mirror.

### 33.6 Instruments

`tools/glassdrive.py HOST TOKEN [--pace S] STEP…` — gestures, `wait:`, `pace:`, `snap:PATH.png`
(both lenses at 1×), `status`; the chord is `pace:0.3 hold release double pace:2.5`.
`tools/journal_report.py` from a file or `-`.

### 33.7 What is owed, re-ordered by what this measured

1. Release pending image acks on a later ack (built §34.1). 2. Split the phone's loop time (built
§34.1/§34.4). 3. The radio wall above the interval: a BTSnoop with the APK driving (bug-report mail
path) to count packets per event; a Wi-Fi-off session for coexistence — still open. 4. Mode 14 text
(built §40.6/§41.4).

## 34. The pending-ack release, the CPU split, and the re-walk (2026-09-05, evening)

Adam: *"Build the pending-ack release and the CPU split, then re-walk."* APK 0.31 installed; the
§33 walk repeated, 341 isolated flushes; a second cut staged as 0.32.

### 34.1 What was built

- **A later image fragment's ack releases every earlier image pending as lost**
  (`CfwTransportBase.releaseEarlierImagePendings`): the permit comes back at once, the flush fails
  with a named reason and the compositor re-sends from the truth; control-lane pendings never
  compared; a late ack that does arrive is reported as late. `PendingAckReleaseTest` drops one ack
  (`SimTransport.notifyFilter`) and pins the prompt failure, the freed slot, the fault, the repair;
  a second case pins nothing released without a loss.
- The journal's split: `handlerMs` and `mirrorMs` inside `handleMs`; `truthMs`, `compressMs`,
  `compressN` inside `assembleMs`.

### 34.2 What the re-walk measured (APK 0.31, isolated, grade M)

| flush size | n | ack med / p90 | handle | handler | assemble | truth | compress (misses) | diff + plan |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| < 500 B | 205 | 77 / 221 | 43 | 0 | 34 | 9 | 0 (4) | 22 |
| 0.5–1.5 KB | 91 | 206 / 411 | 98 | 9 | 27 | 6 | 1 (6) | 20 |
| 1.5–3 KB | 25 | 329 / 495 | 102 | 10 | 73 | 10 | 8 (7) | 49 |
| 3–6 KB | 17 | 543 / 708 | 126 | 27 | 75 | 8 | 8 (5) | 55 |
| 6 KB + | 3 | 1,140 / 1,295 | 176 | 103 | 83 | 10 | 18 (5) | 45 |

The wire unchanged from §33. Host totals: handle 26.4 s = handler 8.9 + mirror 0.0 +
pump-before-assemble 17.5; assemble 15.4 s = truth 3.3 + compression 0.8 + diff and plan 11.4;
against 67.5 s of ack. Compression ~2 % (the memo works); truth render 8 %; diff and plan 27 %
(20–55 ms per flush); "painting ~63 %" — ⚠ overstated, §35.1 corrects. The mirror scan is zero (it
skips while anything is in flight). The wake from silent: 7,454 B, 194 ms painting + 107 ms
assembling before a 1,140 ms ack.

### 34.3 🔴 §33.4's "lost acks" were mostly not image losses — a false alarm fixed

The re-walk's four "lost ack" faults were msgIds 3–6 at session start, reported only when the
counter cycled; the release rule never fired because they are not image pendings. Five days
recounted: **45 of 53 faults are msgIds 3–8 at session start** in 16 episodes; the four real image
losses are the clusters 56–58, 114–117, 198–202, 245–248. The session-start ones are the carrier
CREATE's re-sends of the eaten-message class (§12): the first copy lands in the previous session's
teardown, the 2 s re-ask is answered, and the eaten copy's pending sits until its msgId comes round
— no slot held, nothing lost, but a `Log.e` the phone turned into an error notice on the glasses
("msgId 3 reused while its ack is still pending") six minutes into every session. Fixed: a control
pending reaching the cycle is a **fact** (`Log.w`, `TransportEvent.Note` journaled as
`kind: "control"`, saying whether a re-send was answered); an image pending reaching the cycle is still a
fault. Also: three eaten CREATEs per start ≈ 6 s of cold start per link edge, of which the phone
had 55 in five days. `CAPABILITY_REASK_MS` is a radio constant — an experiment with Adam, not a
blind change.

### 34.4 The second cut, staged as 0.32

Inside the pump per flush: `slidesMs`, `chromeMs`, `overlaysMs`; across the message `textMs` (time
inside the rasterizer's `draw`, both platforms feed `TextProfile`).

### 34.5 What is owed

Read the paint split on 0.32 (§35); the release rule has not yet met a real loss on hardware (it
will show as `ack lost — msgId N (sent after it) acked first`, or `late ack` if it fired early);
§33.7's radio item.

## 35. The paint split read (0.32), and what it corrected (2026-09-05, evening)

0.32 walked (368 isolated flushes), then a WARM second pass without reinstalling (32 flushes).

### 35.1 🔴 `handleMs` includes the assemble — §33.3 and §34.2 counted it twice

The split's "unaccounted" remainder matched `assembleMs` band for band (49/46, 83/83, 81/79,
91/90): `handleMs` is measured at SUBMIT, after the assemble. Corrected totals over the 0.32 walk:

| term | total ms | share of host time |
|---|---:|---:|
| the compositor's **diff and plan** | 13,232 | **50 %** |
| the message handler's paints (window opens, wakes, pane frames) | 6,165 | 24 % |
| the per-lens truth render | 3,498 | 13 % |
| chrome sync | 1,203 | 5 % |
| compression (memo misses) | 844 | 3 % |
| slide steps | 539 | 2 % |
| overlays (wheel, notice) | 193 | 1 % |
| text drawing, inside all of the above | 954 | 4 % |

Host time 26.2 s against 88.6 s of ack time. `Journal.kt`, the report and `CLAIMS.md` carry it.

### 35.2 What the two passes said

- **The diff scan**: `dirtyCells` visited every cell of the scan area with a boxed hash lookup per
  cell (~31k cells per lens per iteration, up to six iterations).
- **The tmux pane, live: ~130 ms of handler per pushed frame**, cold OR warm (handler 104–163 ms,
  text 1–2 ms): a pushed frame is a new list, so `FlowRender`'s slot cache missed on every one.
- **The 631 ms Torrents open was cold code**: 41 ms warm for the same 10,945 B list; Reader open
  94–119 ms cold, 72 warm; Main 20–56 either way — ART's first execution of each paint path.
- The wire unchanged (73 · 275 · 352 · 660 · 1,100 ms by band).

### 35.3 What was built (APK 0.33 staged)

`Compositor.dirtyCells` is a tight mismatch scan (each cell-row's two pixel rows as one loop over
the raw arrays; the map touched only for cells that differ or are unknown; `DirtyCellsTest` pins it
cell for cell against the old loop). `FlowRender` memoises the wrap per line text (width and epoch
the identity; 4,096 lines bound it; `FlowRenderMemoTest`). The `handleMs` definition corrected.

### 35.4 What is owed

Walk 0.33 (the diff term should fall by most of its 13 s; the pane frame from ~130 ms to its
changed lines); then the truth render (13 % — both lenses re-rendered every assemble). §34.5, §33.7.

## 36. The silent glasses (2026-09-05, 19:53) — the incident, its cause, and the fix

Adam, wearing the glasses for the first time all day: *"no display whatsoever … I tried touching
both touchpads to activate or deactivate the firmware-level Silent Mode … that doesn't do anything
either … is this something you did?"*

### 36.1 What happened, from the phone's journal (grade M)

Until 19:52:00 every frame acked (the shell in its own silent mode, one clock flush a minute). At
19:53:00 the glasses answered a 37-byte clock delta with `ImgResCmd UPGRADE_IMAGE_RAW_DATA_FAILED
(5)`, and every frame after it — the 860-byte silent keyframe accepted thousands of times, then
20 KB window keyframes once Adam woke the shell. The recovery rule (round 6: three failures → panic
→ keyframe; three failed keyframes → halt until content changes) met live content and became a
loop: **266 keyframes, 105 panics, 40 halts in fifteen minutes, one 20 KB refusal every 3.5 s.** A
session restart at ~19:55 (six eaten CREATEs) did not clear it; the link never dropped; the
both-temple long-press did nothing while the lease was held. Recovery (Adam's hands): Target →
SIM (20:12:37, the repeats stop), phone Bluetooth off, both-temple long-press → **"Silent Mode
Off"** (so it had been ON), a double-tap brought the firmware menu, another dismissed it, Bluetooth
on, Target → glasses, first accepted frame 20:17:07.

### 36.2 The cause

**The firmware's Silent Mode refuses every image** (status 5, measured). The glasses push the state
on change: sid-0x09 `commandId = DeviceSendToAPP (3)` with `DeviceSendInfoToAPP` in root field 5,
`silentModeSwitch = 2` (Even's schema, V; Faceclaw's `parseSilentModePush`, C), and the settings
READ response restores it in the device-info block's field 14 (V). Our transport parsed neither
(the push logged at debug as "a settings frame outside the capability gate"). The shell did not
know the glasses were asleep; its reaction was the loop — the likeliest reason the temples could
not get through — and the lease we kept renewing is why the stock firmware could not paint. What
put the glasses into Silent Mode at 19:53 is not in the journal (the push is journaled now). Was it
the software? The refusal is the firmware's; the reaction was ours and predates the day — but 0.30
was the first APK on glass since 0.16: a shell that cannot recognise a sleeping display is the
defect, whichever build shipped it.

### 36.3 The fix (APK 0.34 staged; the service on the same core)

- Wire: `SettingsMsg.parseSilentModePush` / `parseSilentRestored`, `TransportEvent.SilentMode`,
  `LinkState.glassesSilent`; the seam carries it.
- **The shell sleeps with the glasses** (`Shell.enterSilentGlasses`): nothing is sent; the **lease
  is released on purpose** (`Transport.setLeaseWanted(false)`) so the stock firmware owns the
  display and the temples; one phone notice names the both-temple gesture. Fallback for a missed
  push: three consecutive ImgResCmd refusals; the 60 s settings poll's READ carries the state. (The
  black-keyframe probe built here was retired in §38.)
- Rule amended (`CLAUDE.md`, `DESIGN.md` §1.6): the lease is held while the glasses are awake and
  dropped on purpose while they are silent.
- The simulator models it (`GlassFirmwareSim.setSilent`: refusals, the push, field 14; a knob
  withholds the field). `SilentGlassesTest` ×3, run eight times clean — after its own settle was
  corrected to decide on ONE evaluation (§27.6's trap fired again in a new test).

### 36.4 What is owed

1. On glass: toggle Silent Mode with the APK connected; read `/journal`'s `silent` notes; check the
   temples respond while the shell is asleep (the wake measured in §42.0; the temples question stands).
2. Whether the firmware enters Silent Mode on its own (wear detection, idle) — the journal will say.
3. §33.7, §34.5, §35.4 stand.

## 37. The latency plan for the next session (2026-09-05, evening) — Adam's rulings and the order

Adam, after 0.33 on glass: *"scrolling the list of apps in Main takes one full second between the
scroll action and a visible response … while scrolling within Reader is twice as fast. I wonder
why. Similarly, Tmux scrolling once within History mode is a little faster than before, but not
much."* Rulings: slide frames become a Global setting with the default where it is; the texture
cache is adopted as far as it goes; a live window may keep the link busy while it is active.

### 37.0 🔴 FIRST THING IN THE NEW SESSION: the wake from Silent Mode must REBUILD the session

> **Built in §38.** Kept as the design record.

**Measured on glass with 0.34 (2026-09-05 22:04):** both halves of §36's wire fact work (the READ's
restored state put the shell to sleep at 22:04:02; the push OFF parsed at 22:04:28 and the shell
woke) — then the glasses **kept refusing every image** for more than a minute after saying they
were awake (three 136-byte frames refused at 22:04:28; probes refused at 22:04:43, 22:04:44,
22:05:28). **Leaving Silent Mode ends the firmware's EvenHub page; nothing paints until it is built
again.** Faceclaw resets its layout on the foreground-exit events; G2CC (*"remarkably robust with
its connection"* — Adam) recovered by a response-gap watchdog that RECONNECTED. Grade: the refusals
M; the cause I, corroborated by both references.

The design (built in §38): wake = rebuild the session (`Transport.restartSession` + the keeper);
retire the raw-image probe (no image is accepted until the rebuild), the 60 s READ as the fallback
for a missed push, a refusal-streak sleep whose READ says "not silent" retrying a restart per pacing
tick (60 s) and on the first ring event; journal system events 4/5/7 (`FOREGROUND_ENTER`/`EXIT`,
`SYSTEM_EXIT`, dropped unseen in `handleInput` until then); the simulator models the teardown
(`carrierLost`); skip the mirror-agreement check while asleep (the release painted the mirror's
stock pattern: `DIVERGE: RIGHT: 5468 px differ` at 22:04:02, a false alarm). Recovery on 0.34 =
§36.1's (Target → SIM → glasses).

### 37.1 The two defects the walks measured but did not fix

- **Chrome-only flushes** (`DESIGN.md` §8.3 violated): the throughput readout changes after every
  ack, and every content-neutral repaint set the chrome dirty and shipped the digits alone — **149
  of the 0.32 walk's 320 flushes** (11.4 s of ack, 23 KB), each a 70 ms floor and a window slot,
  sometimes between a gesture and its first frame. Built §40.1.
- **The first flush of a list notch is the heavy one**: `Shell.startListSlide` damaged the whole
  lens band in the same flush as the first slide step, so the first visible change waited for
  3–6 KB; Reader notches send the copy and a 1–4 KB strip first — why they feel twice as fast. Built §40.2.

### 37.2 What the 0.32 walk measured per notch (grade M, phone path)

| surface | flushes | first flush | first visible change | total bytes | wall |
|---|---:|---:|---:|---:|---:|
| Reader page notch | 3–5 | 1.2–4.3 KB | 221–625 ms | 1.8–8.8 KB | 234–1,209 ms |
| tmux history notch | 1 | 2.4–4.3 KB | 352–645 ms | same | same |
| Torrents list notch (Main is the same shape) | 3 | 5.9–6.0 KB | 830–860 ms | 7.4–7.7 KB | 1,020–1,221 ms |

**Time to first visible change is the metric** (`DESIGN.md` §8.6); bytes in the first flush decide it.

### 37.3 The order of work

> **Items 1–4 and the watchdog were built in §40; the walk is §41.0.** Adam's order:

1. The chrome-only flush defect (§37.1).
2. First-visible-change ordering for list notches: first flush = the two band copies + the strip
   (< 500 B); the lens repaint one pump later; a canvas notch copy first, fill second when the fill
   is over ~1 KB. Target: Main/Torrents ~850 → ~150 ms; tmux history ~500 → ~100 ms with the strip
   ~400 ms later.
3. The `Slide frames` Global setting (`DESIGN.md` §4.2, §6.3): `off · 2 · 4 · auto · 8 · 12`, `auto` the default.
4. The texture cache on glass, then adopted behind a setting — chrome and list rows (mode 14 text,
   mode 13 icons), then tmux lines; lease-scoped.
5. Walk 0.33's rewrites (the diff scan, the tmux memo); then the truth render (13 %).
6. §33–§36's remaining items: the cold-start re-ask (`CAPABILITY_REASK_MS`), the radio's slave
   latency (a firmware-side ask), the wheel as a translation, the silent-mode push on glass.

Every step: build → battery → `installDist` → selfcheck ×3 → walk with `tools/glassdrive.py` →
`/journal` through `tools/journal_report.py` → record the per-gesture first-flush numbers.

### 37.4 What is settled and must not be re-argued

Slide frame DEFAULTS stay; only the setting is new (Adam). Live windows (tmux, Music, Torrents)
keep their cadence while active (Adam); parked windows hold no loop (`DESIGN.md` §4.6). The wheel
spins in 2 frames on a measured slow link (§32) — an on-glass verdict still owed. The lease is
dropped on purpose while the glasses are silent (§36). New windows meet `WINDOWS.md` §6 and ship
with their measured latency profile.

## 38. The wake from Silent Mode is a session rebuild (2026-09-05, late) — §37.0 built

Adam: *"fix the handling so that DamageWM can recover from a firmware silent mode turning on and
then off … G2CC handles this well, so DamageWM should recover from it in a similar way. G2CC's
connection to the glasses was remarkably robust all the way around, so we should definitely use it
as an example to follow where-ever it makes sense (and does not increase latency or cause other
problems)."*

### 38.1 What the phone's journal said (grade M, read through `/journal` at 22:23)

| when | what |
|---|---|
| 22:04:02.617 | the new session's first flush (SILENT, 136 B) submitted |
| 22:04:02.685 | `silent: glasses asleep` — the READ at session start (field 4.14) |
| 22:04:02.689 | that first flush **acked OK** (67 ms) — the fresh session's warmup had passed too |
| 22:04:02.789 | `divergence: RIGHT: 5468 px differ` — the released lease painted the mirror's stock pattern; false alarm |
| 22:04:03.764 | the probe (msgId 10) refused, status 5 — one second after the same session accepted two images |
| 22:04:28 | the push OFF parsed, the shell woke, took the lease back — three 136-byte frames refused (msgIds 18–20); asleep again on the streak |
| 22:04:43 → 22:08:28 | every probe refused, on input and on the 60 s pacing |
| 22:09:16 → 22:09:25 | Adam's Target → SIM → glasses: a fresh session; first flush acked at 22:09:25.461 (42 ms), then a 5 KB Main frame at 745 ms |

Two facts: **a fresh session opened while the glasses are silent gets its warmup and first flush
accepted, then everything refused** (n=1); after the push OFF nothing is accepted until a fresh
CREATE. Reading (grade I, consistent with Faceclaw's *"silent mode blocks app launches"*): **Silent
Mode ends the firmware's EvenHub page**; a page created while silent lives about a second; a page
ended under Silent Mode stays ended after it. No exit event (5/7) was in the journal because none
was journaled.

### 38.2 What G2CC does — the pattern followed, and what was not taken

G2CC's `ConnectionService` (read-only): a response-gap watchdog (no response for 3 s, six bad ticks,
then a cheap acked probe write to tell an ended slot from a keepalive-ack wedge — the 2026-07-21
lesson, 1,639 reconnects in a day before it) → `recoverSession()` = teardown + reconnect (direct to
cached lens addresses, no rescan) + cold-launch, one recovery per 10 s; a lens dropping while live
is re-launched (`COLD_INIT`) when autoConnect brings it back. It never knew Silent Mode by name.

- **Taken: the rebuild** — a fresh session through the keeper (connect, prelude, READ, CREATE,
  lease, warmup, keyframe). Faceclaw's lighter in-session re-CREATE is unmeasured on our glass; the
  full rebuild is measured to work (22:09:25). Cost: the reconnect's seconds, once per wake.
- **Taken: paced recovery** — a rebuild at most once per pacing (60 s), only when the glasses say
  they are awake.
- **Not taken then (built §40.5): the response-gap watchdog** — our refusals are explicit (status 5).
- **Not taken: the direct reconnect to cached addresses.** Our phone transport always scans
  (filtered on the remembered pair); against a pair not advertising a direct connect would sit in
  the platform's own connect wait. Unmeasured either way.

### 38.3 What was built

- **`Transport.restartSession(reason): Boolean`** — in `CfwTransportBase`: `running`/`started`
  false FIRST (so the disconnect is ours and reported once), `disconnectLink()` under
  `NonCancellable`, then `onLinkDown("restart: …")`. Refused (false, logged) when no session is
  started or one is mid-start. A `restart` journal note. `PathTransport` delegates; the seam client
  forwards `t="restart"` (unverified over the seam — the dev override only).
- **`Transport.probe` is gone**; `ImgWork.Raw` stays for the warmup.
- The wake (`wakeGlasses` → `requestRebuild`) calls `restartSession`; this instance stays asleep
  until the keeper restarts it. `silentTick` (every 60 s and on a ring event): if
  `LinkState.glassesSilent` says silent, wait; if awake and the shell still sleeps (a refusal streak
  with no push — the 22:04:28 shape), ask for the rebuild once per pacing.
- **`Shell.start` adopts the glasses' state** from the new session's READ before its first frame (a
  same-instance restart carried the old sleep over before).
- No mirror-agreement check while asleep (`checkMirrorAgreementInner`).
- System events 4/5/7 reach the journal as `event` notes (`CfwTransportBase.routeEvent`); nothing keyed on them yet.
- The simulator: `GlassFirmwareSim.carrierLost` set by `setSilent(true)` (with `layoutCreated`
  cleared), cleared only by a CREATE; images refused while silent or lost — except a fresh CREATE's
  warmup (the stricter side of the n=1 observation). Against it the §36 wake is refused, which makes
  the test non-vacuous.
- Tests: `SilentGlassesTest` on a keeper rig (the push OFF rebuilds exactly once — attempts 2, a
  second prelude and CREATE, `carrierLost` cleared, lease back, belief = glass, zero refused
  flushes; three refusals with no push and no field 14 sleep the shell and the next check rebuilds;
  the system events are journaled and not inputs); `ShellKeeperTest.aRestartRequestRebuildsTheSessionOnce`. Both ×3 clean.
- Docs: `CLAUDE.md`, `DESIGN.md` §1.5b, `IMPLEMENTATION.md`, `DAILY.md`, `REMINDER.md`. APK 0.35.

### 38.4 What is owed — on glass, with 0.35

1. Toggle Silent Mode with the APK connected; read `/journal` (`silent: glasses asleep`, the lease
   release, `silent: glasses awake … rebuilding`, `restart: …`, the keeper's `link ended: restart: …`).
   **Record the blank stretch** from the
   push OFF to the first `done ok` (scan + connect + 800 ms settle + READ + CREATE + any
   eaten-CREATE re-asks + lease + warmup + keyframe); if long, the direct reconnect (§38.2) and
   `CAPABILITY_REASK_MS` are the levers. (Measured §42.0: 19 s on 0.38.)
2. The `event` notes: do 5/7 arrive when Silent Mode begins, ends, or when a CREATE-while-silent's
   page ends? If an exit event reliably precedes the refusals, the rebuild can key on it.
3. The temples while asleep (lease released) — §36.4.
4. The refusal-streak path with the glasses AWAKE (a page that ends for another reason): the
   rebuild should follow within a pacing or on the next ring event. Unobserved on glass.

### 38.5 A proposal, not built — G2CC's response-gap watchdog

(Built in §40.5 at Adam's word.) A session whose acks simply stop was reported (`stall`, after 10 s)
and not acted on — round 4's *report, never act* under the no-timeouts principle. It is the first
place the transport ends a session on elapsed time rather than evidence from the glasses; the
`restart` machinery is the action side, only the trigger new; §34's lost-ack release already covers the
commonest single-ack loss.

## 39. The showdown sentence named the top earner and described the best hand (2026-09-06)

Adam, of his last hand on the Regular table: *"it said I won the hand with a pair of 9s and a pair
of 4s. But my pocket was a pair of 8s, there was only one 9 on the board, and my opponent had a 9
in their pocket … Am I wrong, or did they fold or something? Or is that some kind of bug?"* Right
on every card; a bug in the sentence, not the money.

### 39.1 The hand, replayed from the record (grade M)

The table record persists `(seed, handNo, stacks, button, log)` and the deck is
`Deck.shuffled(Rng.hash(seed, handNo))`, so hand 8 reproduces exactly (a Python re-implementation
of `Rng.mix`/`hash` and the Fisher–Yates against the synced `window.games.table` record): You (3)
8c 8s · Bea L. (5) 10c 9d · board 9s 7s 3h 4h 4s. Bea: nines and fours; Adam: eights and fours.
Actions are not persisted; the betting is inferred: Bea all-in short, a third seat in for more and
folded, Bea took the main pot, Adam the larger side pot.

### 39.2 The defect (grade M, from the code)

`HoldemTable.resultLine` chose `best = s.won.entries.maxByOrNull { it.value }` (the seat that
COLLECTED THE MOST) and described `bestScore = live.maxOf { scores }` (the best hand) — one player
only when there is one pot. `Pots.build`/`settle` paid correctly; the only test on the line checked
its grammatical person.

### 39.3 The fix

The best hand leads; every other paid seat follows as a side-pot clause in the right person ("Ann R.
wins $90 with 9s and 4s · You take the $140 side pot"). One pot reads as before. The side-pot
winner's hand is not spelled out (one `Draw.fit` line, `fStatusBig` 17 px bold, ~550 px at 100 %;
under ~60 characters where the old worst case was ~48). **Width at 130 % unverified on glass.**
`HoldemEngineTest.theShowdownLineDescribesEachPaidSeatsOwnHand` scripts the shape and searches the
seeds for a deal where the short seat is best and one where You are. APK 0.36.

### 39.4 Left as it is

The record holds no hand history; persisting the last hand's result (line + scores + shown cards)
beside the table record would make this a ten-second check. Adam's call.

## 40. The latency plan built — §37.3 items 1–4 and the watchdog, against the simulator (2026-09-06)

Adam: *"the order is fine, the 70ms longer but start quicker is fine, watchdog thing is fine. go
ahead and build it all up front, using the sim where needed. and we will do the tests and walk and
such at the end."* — *"make sure to double check your work carefully when done."*

### 40.1 Item 1 — the chrome-only flush defect (§37.1)

The pump's chrome gate let chrome sync whenever content had pending damage, and a content-neutral
repaint counts as pending. `Chrome.sync(allowTelemetry)`: the readout and the link cell repaint only
on a gesture's own flush, an animation frame or the idle tick; otherwise they keep their last
painted text, which survives `invalidate()`. The sync also runs on every flush telemetry may ride
(a repeated gesture with the same echo used to leave the readout stale). `ChromeFlushTest`.

### 40.2 Item 2 — time to first visible change

- **List notch**: the optimistic lens repaint (3–6 KB measured) is posted one message on
  (`paintOptimisticLens`), so the first flush is the two band copies and a half-row strip; the Run
  paints from the model as it is then (a fast spin repaints the newest cursor); overlays skip it.
- **Document and canvas notches — copy first, fill second.** `Slide.step` blanks a strip worth a
  flush of its own (`SPLIT_FILL_PX` = 12,000 px, ~1 KB of text) and damages the blank (a uniform
  run, a dozen bytes); the content lands on the next pump (`fillDeferred`, at the band's CURRENT
  displacement). The canvas path (`paintCanvasOf`) does the same with the strip the detector exposed
  (`applyCanvasFill`). A list row's first half-step (16 px) stays under the threshold.
- **Measured in the simulator** (`FirstFlushTest`): list notch first flush < 500 B with no delta
  touching the lens; document notch 55 B then a 265 B fill; canvas the same; belief = glass after
  every notch, spin and reverse.

### 40.3 🔴 A planner defect the split uncovered — proportional shares starved a plane

The first document notch after a window opens shipped **1,590 B for 24 B of change**: the
partitioner gave each plane a share of the rect budget PROPORTIONAL to its rect count, and the
status bar's input echo (eleven glyph-sized pieces on the chrome plane) left the content plane a
share of ONE — its scrollbar thumb and the blank strip forced into one union, the whole band.
`Compositor.partition` takes the globally cheapest within-owner merge first. Pre-existing; plausibly
part of why a list notch carried 6 KB where the lens is 1–2 KB. `FirstFlushTest` pins the first AND
second notch after an open.

### 40.4 Item 3 — the `Slide frames` Global setting

`ShellSettings.slideFrames`: `off · 2 · 4 · auto · 8 · 12`; `auto` (default) = the halving rule with
its 8 px floor; N = the ease-out on the 2 px grid resampled to at most N steps (`Slide.frames`, reset per
retarget); `off` = one copy and
one strip. Measured: 32 px auto `16, 8, 8`; off `32`; 2 `16, 16`; 4 `16, 8, 4, 4`; 100 px auto
`50, 26, 12, 8, 4`. Persisted and synced; the Global row after `Silent clock`.

### 40.5 Item 5 — the response-gap watchdog (G2CC's), and the prelude re-ask it needed

`CfwTransportBase.watchdogTick` (the 1 s tick): every inbound packet on either arm stamps
`lastInboundAtMs`; ten seconds without one, held for three ticks, sends a PROBE (the carrier text
refresh, acked); ten ticks more without any packet, `restartSession`. Never while the glasses say
they are silent, never before a session is up. **The premise, measured:** a healthy session never
goes quiet — 34 unacked-control notes over five and a half days against ~120,000 keepalives. Its
test found a real gap: **a session rebuilt into the same silence sent its prelude, lost the ack, and
parked forever** — the two gates below it re-asked every 2 s, the prelude did not; it does now
(`CAPABILITY_REASK_MS`). Grade: a repeated sid-0x01 launch is what G2CC's `COLD_INIT` sent all day
on stock — C for the mechanism, the second launch's exact answer unobserved on 2.2.6.10.
`WatchdogTest` ×2. The instant transport's quiet bound is 500 ms (a 40 ms bound restarted healthy
sessions under the battery's load — §30's lesson).

### 40.6 Item 4 — the texture cache, adopted behind a setting (`Cached text: off · on`)

**The firmware fact** (`zlib_glue.c`, `texture_cache.c`): modes 13/14 ignore the "lenses differ"
bit — **a cached draw is always at disparity 0.** At this stage cached text applied only on plane 0
(the lens band, menus, notifications, the switcher; everything at Depth 0); §41.4 extends it. A
per-lens variant is a firmware-side ask worth making.

Built (`core/comp/CachedText.kt`, `Compositor.emitCached`, the shell's atlas lifecycle):

- **`GlyphAtlas`** renders glyphs 32..126 of a resolved font through the host's rasterizer into an
  advance-width box the face's ink height tall (mode 14 advances by image width), 4-bit, packed
  with a 96-entry table by `TextureCache.Builder` (tofu for what the face lacks), first come first
  served until the 64 KiB is full.
- **`CachedText`** wraps the platform rasterizer on every host. A string in a font the glasses HOLD
  is blitted from the same glyph images the firmware draws from — source 0 skipped, every other
  level through the LUT `s × top / 15` (`cfw_texture_render` and `GlassFirmwareSim.renderCached`
  agree) — and recorded as a `TextDraw`; `measure` follows the blit.
- **`Compositor.emitCached`**: for a dirty rect at disparity 0, grow it to the glyph boxes it
  touches, refuse over 24 draws, re-render black plus those draws and ship one clear delta (one
  fid) plus the mode-14 draws (no fid, 9 B + the characters each) ONLY when the re-render equals
  the composed pixels byte for byte — otherwise pixels.
- **The upload** rides its own flushes: `DisplayOp.CacheWrite` (mode 12 is not a batch sub-mode;
  the seam carries it as `cw`), one ≤ 3 KB chunk at a time, ON the loop, only when nothing is
  pending or in flight — after the session's keyframe, yielding to every gesture. Fonts go live on
  the batch's last ack. A lease lapse forgets the upload; a refused write switches the feature off
  for the session, loudly.
- **Measured in the simulator** (`CachedTextTest`): the lens flush of a notch as mode-14 draws under
  400 B; text on a grey box stays pixels; a cache write can never ride a batch.
- Visible change: integer advances with no pair kerning; a level's ramp is the LUT's integer one.
  OFF by default pending the eye.

### 40.7 What the walk has to measure (with 0.37 installed)

Measured in §41.0: a Main/Torrents notch's first visible change (~150 ms modeled, was 830–860),
a Reader and tmux notch (the trailing blank edge), the chrome-only flush count (was 149 of 320);
with `Cached text` on the `atlas` notes, the `drawtext` flushes, the look at 100 % and 130 %;
`Slide frames` at each value (Adam's feel); the watchdog silent; the §38 wake on glass.

### 40.8 Battery and builds

`:core:test` 484 · `:desktop:test` 11 · selfcheck ×3 · snapshot ×2 · epub · music · games · lint 0
· APK 0.37 staged. New pins: `ChromeFlushTest`, `FirstFlushTest` ×5, `WatchdogTest` ×2,
`CachedTextTest` ×4. Two review passes over the diff found three defects in the atlas lifecycle:
the reset ran after the first compose and dropped the fonts it had just seen; a lease lapse
re-queued chunks before the lease was back; the plane lookup took the outermost region instead of
the innermost.

## 41. The cache on every plane, the ladder as Adam sees it, and the walk's numbers (2026-09-06, evening)

Adam walked 0.37 for two hours (16:20–18:16): *"definitely a bit faster and more responsive"*;
`Slide frames` 4 as the middle ground (auto stays the default — his call); *"I can't see any
difference"* with `Cached text` on or off. Then: *"go ahead with the atlas fixes and journal notes,
and everything else that might improve latency across the board and use the cache better and more
usefully … While you're at it, fix the depth option."*

### 41.0 What the 0.37 walk measured (phone path, grade M)

Time to first visible change, 133 gesture bursts over 1,225 flushes, median / p90:

| gesture | first flush | first visible | 0.32 (§37.2) |
|---|---:|---:|---:|
| list notch in a window | 292 B | 155 / 227 ms | 221–645 |
| Main notch | 1.1 KB | 257 / 409 ms | 830–860 |
| back to Main from a window | 5.7 KB | 704 / 984 ms | — |
| window switch with a height change | 7.8–19 KB | 1.1 / 2.7 s | — |

Chrome-only flushes 122 of 1,225 (was 149 of 320). The watchdog never fired. A Main notch's first
flush was 1.1 KB, not the sim's 500 B: the two 16 px strips are real row text at 240–650 B each, and
the status-bar rect (40–316 B) rode the first flush too.

**Why `Cached text` showed nothing:** (1) **off → on left the cache dark** — `atlasDisable` cleared
the live set and detached the compositor; `atlasGrow` on re-enable found nothing to upload and
returned before re-attaching (journal: on 18:00:57 — 11 fonts, 58 KB, 20 chunks in 12 s; off
18:01:20; on again with no note and no draws; twenty flushes carried cached draws all session).
(2) **A font that did not fit left its glyphs behind** — the twelfth font's `addFont` wrote every
glyph that fit before the table check threw: 7 KB of orphan images in three flushes. (3) **Only the
lens band was ever eligible** (content at depth 8, chrome at 12, draws flat): the cache touched the
SECOND flush's lens rect (300–900 B → 12 B + draws), never the first flush Adam was feeling; and for
12 s after each flip-on the link carried the upload.

**Also in the journal:** one arm's link dropped about every 50 minutes, alternating LEFT and RIGHT,
13 times that day, each a session rebuild with a keyframe (140 B on the silent clock; 7.8–19 KB in
a window), reason unjournaled (§42.2); one `submit before start()` at 07:51:06; the regime
classifier flipping fast/SLOW 22 times in 70 s at 17:59; a keyframe on every height change carrying
the whole nominal frame AND the depth planes again as stereo deltas.

### 41.1 The atlas, fixed

`TextureCache.Builder.addFont` sizes the whole font before a byte is written (`TextureCacheTest`);
`GlyphAtlas` keeps an ACKED watermark (`ackedBytes`) and `Shell.atlasGrow` puts every acked font
live at once (`atlasLive`) — the off → on flip resumes draws with nothing uploaded; fonts pack
heaviest-first by the pixels they draw (`CachedText.usageOf`).

### 41.2 The depth ladder as Adam sees it (`DESIGN.md` §3.1 revised)

Global `Depth` D moves EVERYTHING: both bars with their dividers at D, Main at D, every app's content
at D unless its own row says otherwise, and the selection bar one notch (4 px) nearer than the plane
it selects on — never nearer than the screen plane. The per-app `Depth` row is `global · 0 · 4 · 8 · 12 · 16`, default `global`, and moves only that app's content. Before: the bars rode one step
behind content capped at 16 and app content parked at 8 whatever the row said — on glass 8, 12 and
16 moved only the bars and 16 changed nothing. `Shell.updatePlanes`, `ShellSettings.appDepthOf`,
`DepthLadderTest`. Overlays stay on the screen plane (popups forward).

### 41.3 The keyframe seeds the screen plane only

`Compositor.seedFrame`: the mode-6 carries the composed frame with every depth plane's area black;
the diff then paints each plane once, per lens, from the truth. Before: 7.8–19 KB and 1.1–2.7 s per
window switch between Reader (288) and Main or tmux (352). Modeled: 3–8 KB less per switch.
`PlaneCacheTest` pins a rows window's keyframe under 400 B at depth 8; `Round6Test`'s
oversize-keyframe pin puts its grain on the screen plane. The vacated-strip cleanup a nominal
keyframe needed (`Planned.Black`) finds nothing to do.

### 41.4 Cached draws on every plane

A cached draw is flat, but mode 9's stereo form carries two rect-sets and `draw.c` `rect_copy_4bpp`
copies overlapping rects correctly (reverse iteration when the destination is past the source). So
a text rect `r` on a plane at disparity `d` ships as: (1) **one flat delta over `w`** = `r` widened
by |d| on both sides, whose payload is the BASE — the composed pixels with every draw's box black
(a rule, a divider, a margin ride it), one fid; (2) **the draws at nominal x** (mode 14, no fid);
(3) **one per-lens copy** (`DisplayOp.CopyPair` → `CfwModes.copyStereo`): left `(r.x, r.w+|d|) →
r.x−d`, right `(r.x−|d|, r.w+|d|) → r.x`.

**The proof is in lens space.** `Compositor.emitCached` builds the firmware's result per lens
(base, flat draws, the staged copy) and compares it byte for byte with that lens's truth over `w`;
anything else (a neighbouring plane, a grey box, a font no longer held, a box off the panel) ships
as pixels. No plane-0 requirement remains.

**What feeds it:** every draw everywhere; a slide's strips painted into a temp are recorded through
a RELAY (`CachedText.via` / `viaInto`); a translation moves the records (`moveRecords`); a string
with a character the cache cannot hold (the status line's "·") is drawn as its cacheable RUNS plus
the odd character by the host. `MAX_CACHED_DRAWS` is 96 (a Reader page ~30 lines, a pane ~40).

**Measured in the simulator** (`PlaneCacheTest`): rows at depth 8 ship as draws plus one copy,
belief = glass at every rung and for an app on its own plane; a settled notch's pixel bytes are
bases and blanks (under 600 B). On glass every flush carries `cached` and `cacheMiss`
(`reason=count`: no-records, no-draws, growing, too-many-draws, edge, planes, font-not-live,
image-not-live, layout, proof, budget); `tools/journal_report.py` totals them.

### 41.5 Icons as cached images (mode 13)

`IconPaint.blit` is the one seam every icon crosses (the drawn set renders once per kind and size
and blits through it — `IconPaint.drawKind`; eight direct `Icons.draw` call sites moved).
`CachedText` implements `IconRecorder`: an icon is quantised once, packed once it has been drawn
twice, after the fonts, within `GlyphAtlas.IMAGE_BUDGET` (16 KiB of the 64); held by the glasses it
blits through the firmware's LUT (`CachedText.blitImage`) and ships as a mode-13 draw in the same
base + draws + copy shape. `PlaneCacheTest` pins it.

### 41.6 Two more latency levers, and a journal that answers

Telemetry never rides a gesture's FIRST flush (`Shell.inputFlushPending`); the input echo still does
(§1.7). The link regime has hysteresis and a dwell (`Shell.linkSlow`: slow above 1.3× the 50 ms/KB
line, fast below 0.7×, never twice within ten flushes). The journal carries the build (`build` note
per session start: `apk 0.38 (38)` or the desktop's git stamp, `damage-build.txt` from gradle), the
link's edges (`link` notes: `up:`/`DOWN: <arm> disconnected: <reason>`), and a submit into a stopped
transport is a `link` note with a rollback, not an error.

### 41.7 What 0.38 has to show on glass

Measured in §41.9 / §42.0: a Main notch's first flush with the cache (modeled ~150–300 B, was
1.1 KB); a Reader notch (no kerning now — Adam's eye); a tmux history notch; `cached`/`cacheMiss`
per flush; the depth ladder at 0/4/8/12/16 and the per-app row; a window switch between heights
(modeled 3–8 KB less); icons; the `link` notes at the next arm rebuild; still owed from §40.7: the §38 wake, the watchdog silent.

### 41.8 Battery and builds

`:core:test` 494 (new: `DepthLadderTest`, `PlaneCacheTest` ×6, atlas pins in `TextureCacheTest` and
`CachedTextTest`; `ChromeFlushTest`, `Round6Test`, `StyleTest` re-pinned) · `:desktop:test` 11 · selfcheck ×3 with a §41 phase (`cachedTextChecks`, its
own function — the script method hit the JVM's 64 KB limit) turning the cache on with the REAL
faces across Main, Files, Torrents, tmux, Settings and a book at every rung: **180 rects served as
cached draws, belief = glass = truth on every settle, three runs of three** · snapshot ×3 · epub ·
music · games · lint 0 · APK builds. ⚠ One battery run of three reported `OracleWalkTest` "did not
settle" at h=480 step 145 under parallel load; standalone passed — a rate to keep watching (§27.6).
A 30-hour-old sim instance from a previous live drive (ports 174xx) was found running and ended.

### 41.9 0.38 on glass (2026-09-06, 20:44–20:55) — the cache's first account, and the black strip

Adam installed 0.38 with `Cached text` on (grade M, 795 flushes):

- **The atlas:** 11 fonts and 18 icons, 63 KB, up in 20 s of idle chunks; off→on at
  20:46:13/20:46:27 resumed at once (`held — 11 font(s) and 6 icon(s) live`) — the §41.1 defect is gone.
- **457 rects shipped as cached draws** (1,010 `drawtext`, 451 `copypair`, 36 `drawimage`), the
  status bar's echo and readout among them (108 flushes). Refusals: `no-draws` 521, `proof` 491,
  `planes` 280, `no-records` 260, `growing` 8.
- **Per gesture:** a window list notch 171 ms median / 264 p90 to first visible change (first flush
  485 B); a window delta gesture 126 / 579; all flushes median 433 B, ack 124 ms.
- **The keyframe seed is 17 B** (was 4–9.7 KB); a list window's switch 4.3–5.4 KB in all; a page
  window's 10.6 KB because the page went as PIXELS.
- **Depth 16 is the cache's worst case, and he was at 16.** The copy leaves |d| columns on each far
  side that must be shift-invariant; at 16 that is 32 px, and the Reader's rail sits 20 px past a
  line's end — every page strip failed the proof; the 280 `planes` refusals were full-width rects
  whose widened box left the region's 16 px inset.
- **The black strip (Adam's question).** The §40.2 split on 35 true copy-then-fill notches (the
  Reader at 288): first flush 62 B / 60 ms, the fill 2.4–4.2 KB 430–680 ms later, **the black band
  visible ~610 ms median**. It buys motion ~0.4–0.6 s earlier when the fill is pixels and nothing
  when the fill is draws (~200 B). Ruling → §41.10.

**Built on it (0.39):** the `planes` pre-check is gone (the lens-space proof is the judge); a
depth-plane rect whose proof fails on its context is **retried once at its row's full width inside
its region** — the rail rides the base delta (`PlaneCacheTest`: rows beside a rail at Depth 16 ship
as draws + copy); font and icon refusals are `atlas` notes; fonts go live mid-upload only once.

### 41.10 `Slide fill = auto`, and Files gets its Size row (2026-09-06, late)

Adam: *"Auto."* Built for 0.40: **`Slide fill`** Global row, `auto · split · whole`
(`ShellSettings.slideFill`, `Slide.splitFills`, `Shell.splitFills()`); `split` is §40.2; `whole`
paints the strip in the translation's flush; **`auto` (default) splits only while the cache is not
serving**. `FirstFlushTest` and `PlaneCacheTest` pin both directions. **Files** has the per-app
`Size` row (`global · 288 · 352 · 416 · 480`, persisted as `height`) and a `preferredHeight`.

### 41.11 Upstream check: g2flash and faceclaw since our pinned build (2026-09-06, late)

`reference/g2flash` pinned at `a5d1c31`. Fetched, NOT moved: five commits upstream (to `b20bfb1`,
2026-09-03), all on a **new stock base, 2.2.9.22**:

1. **Rebase on 2.2.9.22** (`baf6bc4`, `317081f`): every address re-derived; the contract string is
   **`EVENCFW/18`**, exactly 127 bytes (`… micctl taplong11`). Our five `REQUIRED_CAPS` are still in it.
2. **A lost-ACK fix 2.2.9 NEEDS** (`784846b`): stock 2.2.9 sends the image ACK from a deferred
   callback through ONE unguarded slot, so pipelined images (window ≥ 2) lose an ACK. **Our base
   2.2.6.10 acks before the deferred path: our measured lost acks (§33.4) are NOT this mechanism.**
3. Compass mode 10 gains `[10][2][interval16][min-change16]` — additive; Damage sends no mode 10.
4. **Ambient light sensor, mode 16 + settings field 105** (`als_sensor.c`): a QUERY and a PASSIVE
   mode where the stock auto-brightness never steps the panel; reports only when asked. ⚠ Faceclaw
   gates its demo on a caps token `als16` the 127-byte string does not carry — the `img576` lesson.
5. **2.2.9's tap-then-long gesture is forwarded as private event type 11**; our decoder would echo
   `ev11` and act nowhere. The rewritten `gesture_fwd.c` passes the RAW SOURCE (0/1 temple, 4 ring)
   for 9/10/11 — if it holds on hardware, a long-press is ATTRIBUTED on the 2.2.9 CFW (grade I,
   unverified), which `CLAUDE.md`'s rule and §1.2's no-op were built around.
6. The flasher performs the stock control-channel auth before BEGIN (2.2.9 closes an idle GATT
   connection after ~30 s without it), gains `--reconnect-attempts` / `--reconnect-delay`, restarts
   a component from FILE_CHECK after a lost ACK instead of replaying a block.

**Faceclaw** (`c1d70ab` → `9b70880`, 49 commits, 0.6.4/0.6.5): app-level only; nothing in its
protocol layer changes a constant we share.

**For us:** nothing on the installed build. Moving to the 2.2.9 CFW = a re-flash through the NEW
flasher (dry-run first, `--stop-before flash`), `research/verify_cfw.py` re-pinned to 2.2.9.22 + `b20bfb1`, every
address-bearing claim re-derived; it would bring the attributed long-press (if real), event 11, the
light sensor, the OTA fixes. Not owed; `reference/g2flash` stays at `a5d1c31` (a pull would break
`verify_cfw.py`).

## 42. Two journals read, the page traffic put to sleep, the keeper in the journal (2026-09-07 → 2026-09-09)

Adam installed 0.40 on 2026-09-09: *"significantly faster and more responsive"* — and after turning
Silent Mode off *"it took me some doing"* to get the display back.

### 42.0 What the phone journal said (grade M, phone path)

**0.38, 2026-09-06 20:44 → 09-07 04:00.** The §38 wake worked on glass at 20:45:25 → 20:45:44
(asleep on the push, awake + `restart` on the push OFF, a new session 19 s after the sleep note,
mostly the reconnect). The watchdog never fired. Six arm rebuilds overnight (22:08 L, 23:47 R,
00:34 L, 01:30 R, 02:21 L, 03:14 R), every one `<arm> disconnected: supervision timeout` (Nordic's
`REASON_TIMEOUT`: nothing heard from that arm for 5 s). A LEFT drop is preceded by ~1 s by
SYSTEM_EXIT (type 7) on RIGHT; a RIGHT drop shows FOREGROUND_ENTER/EXIT 8 s after the rebuild. Both
arms reconnect at every rebuild (`connectLink` ends the survivor first), so the alternation is not
connection age. Each rebuild: ~9 s down, a keyframe, the atlas uploaded again (16 KB in 6 chunks on
the silent clock; 63 KB in a window), a `fault: control: <arm> write characteristic gone`.

Adam's evening at Depth 16 with `Cached text` on (20:44–22:08, 2,157 flushes), median / p90:

| first flush of the gesture | bursts | first bytes | first ack | burst total |
|---|---:|---:|---:|---:|
| window notch | 469 | 359 B / 2.3 KB | 92 / 358 ms | 2.4 KB |
| Main notch | 17 | 1.0 / 5.7 KB | 195 / 816 ms | 5.5 KB |
| window switch | 22 | 1.7 / 2.9 KB | 282 / 471 ms | 9.2 KB |
| back to Main | 1 | 3.3 KB | 526 ms | 6.9 KB |

Only 170 of 511 bursts had cached draws in their FIRST flush; `proof` 1,372, `planes` 1,038 —
Depth 16 before the 0.39 retry.

**0.40, 2026-09-07 04:00 → 09-09 15:46 (Adam's use):**

| first flush of the gesture | bursts | first bytes | first ack | burst total |
|---|---:|---:|---:|---:|
| window notch | 151 | 540 B / 2.1 KB | 108 / 229 ms | 1.6 KB |
| Main notch | 59 | 716 B / 1.9 KB | 117 / 247 ms | 1.2 KB |

The cache served 1,075 rects over 2,235 flushes; refusals `no-records` 1,587, `no-draws` 464,
**`proof` 126** (was 1,372 on 0.38: the §41.9 retry works), `planes` gone. Phone CPU per flush:
handle 17 ms median / 66 p90, assemble 11 / 43. 40 session starts in 2.5 days, 41 supervision
timeouts (20 LEFT, 21 RIGHT), `Request failed with status 8` once.

**The Silent Mode stretch, 09-08 12:41 → 09-09 15:06.** The glasses slept (SYSTEM_EXIT a second
after the push), and for ten hours the transport sent an EvenHub keepalive every 4 s into a page
that no longer existed — **8,641 `control … never acked` notes** — and the shell wrote an identical
`still silent (pacing)` line every minute (676). Nothing broke; the radio and the journal paid.

**The wake, 09-09 15:06:41 → 15:09:30 (Adam's "some doing").** The previous session had ended
09-08 22:25 (RIGHT, supervision timeout); NO session started for 16.7 hours (the pair in its case).
At 15:06:41 a session started whose READ said Silent Mode was on (the shell slept, one `build`
note). Then for three minutes: `up: ble link up` every ~1.5 s (121 times), a `control … never
acked` note per cycle with msgIds stepping by 3 (140, 143, 146 …), a burst of five `silent
(pacing)` notes per cycle for the first 15 s, **no `build`, no `DOWN`, no `restart`, no `fault`** —
then a normal start at 15:09:30 that painted. `Link(true)` is emitted in exactly one place
(`CfwTransportBase.start` after `connectLink`), so the transport's start ran ~120 times without the
shell reaching its `build` note; the keeper narrated each failure to logcat, unreadable here. **The
mechanism is NOT in the journal**; §42.1 items 5–6 add what would say it; §42.3 the candidates.

### 42.1 Fixed and built (0.41; the service on the same core)

1. **The page traffic sleeps with the glasses** (`CfwTransportBase.pageTrafficWanted`): the 4 s
   keepalive and the 30 s carrier-text refresh go out only while the shell wants the lease and the
   glasses do not say they are silent; the 60 s device-info READ stays (the wake poll).
   `SilentGlassesTest.noKeepaliveWhileTheShellSleepsWithTheGlasses`.
2. **No lease release after a link loss** (`stop()` reads `connected` before the sweep): the write
   into the dropped arm was the `control` fault at every rebuild, and the release reaching the
   surviving arm freed that lens's texture cache for nothing — the keeper re-acquires within
   seconds, which the firmware treats as a RENEWAL and keeps the cache (`settings_ext.c`
   FB_ACQUIRE: released only when the deadline is 0 or passed; RELEASE, expiry and mode 11 free it;
   Damage never sends mode 11). A deliberate stop still releases both arms, best effort per arm.
   `ShellKeeperTest.aStopAfterALinkLossReleasesNothing…` (0 releases across a link-loss rebuild, 2
   at the stop).
3. Atlas chunks journaled as flushes (`ATLAS`, with `via`) — the report had them as a 32 KB `via ?` "gesture".
4. Silent checks counted, not journaled one each (`Shell.silentChecks`): the first, a ring event,
   every 60th; the wake note carries the count.
5. **The keeper's transitions are `keeper` journal notes** (`Shell.journalNote`): `starting`,
   `reconnecting (attempt N)`, `start failed: …` with the exception's text, `link ended: …`,
   `retrying in N s`, `driving via …`, `stopped`.
6. **`GET /log?token=T[&tail=N]` on every host's replica** (`Log.recent`, a 4,000-line ring at INFO
   and above, `HH:mm:ss.SSS L tag: message`). `ReplicaServerTest.theLogRouteServesRecentLines`.
7. **`tools/journal_report.py` prints time to first visible change per gesture**: bursts of flushes
   less than 1.2 s apart keyed by the first flush's label at SUBMIT time; silent clock and atlas
   chunks excluded; `keeper` notes listed. `WINDOWS.md` §6 item 7 points at it. (An earlier cut keyed
   bursts on completion time and read a window notch as 1.3 KB / 213 ms.)

### 42.2 The arm rebuilds — the pattern, and ten explanations (none tested)

Every explanation must fit (grade M): the phone's controller classifies each as a supervision
timeout (5 s without a PDU from that arm); the arms alternate; 47–98 min apart (median ~52); both
arms reconnect at every rebuild, so both connections are the same age; a LEFT drop is preceded ~1 s
by SYSTEM_EXIT on RIGHT; drops happen idle on the silent clock and during use alike; parameters
15 ms / latency 1 / 5,000 ms on both.

1. A per-arm firmware **reboot** on its own ~100-min clock (watchdog, scheduled reset): offset uptimes take turns — the cleanest fit; the other lens sees the inter-lens link go and ends the page first.
2. A per-arm firmware **stall** longer than 5 s without a reboot (a flash write of settings/logs, a sensor calibration, the wear-detect self-test).
3. **The inter-lens link**: a periodic resync that parks one arm's phone-facing radio; the leading lens alternates.
4. **The phone's controller**: two 15 ms / latency-1 links plus Wi-Fi coexistence leave one anchor unserved for 5 s. Fits SYSTEM_EXIT-first poorly.
5. A **connection-parameter update** the glasses request periodically that the phone applies at the wrong instant — the `updated` lines now reach `/log`.
6. A **deep sleep** on a latency-1 link with almost no traffic on LEFT — weak, daytime drops happen under traffic.
7. **RF**: attenuation by the case or the head — weak overnight, arms inches apart on a desk.
8. The glasses' own **battery-saver duty cycle**, per arm.
9. A **CFW-side timer or buffer** (the snapshot FIFO, `seq_timer`, the wake lease) faulting an arm periodically — G2CC's stock-era logs would show whether the cadence predates the CFW.
10. A **BLE-stack buffer leak** per arm (unacked notifications, lease responses) that stalls the arm after ~100 min and clears on reconnect.

Cheapest discriminators, in order: `/log` and the `link` notes around the next drop (item 5); G2CC's
stock-era logs for the cadence (9 vs hardware); the sid-0x0F logger for a boot banner (1 vs 2); the
battery READ's per-arm level across a drop; one bug-report BTSnoop across a drop (`REMINDER.md` row 5).

### 42.3 The wake loop — ten explanations (none tested; 0.41's notes decide)

Fits: ~120 transport starts at ~1.5 s, each past `connectLink`, none reaching the shell's `build`
note; msgIds advancing by 3 per cycle; a never-acked control message per cycle; the five-note
`silent (pacing)` bursts in the first 15 s; no `DOWN`/`restart`/`fault` notes; a normal start ended
it after Adam's intervention (Target → SIM → glasses, or Bluetooth).

1. The attempt failed INSIDE `Shell.start` after `transport.start()` and before the `build` note (a window's RESTORE activation or `setBrightness` throwing), the keeper retrying with a fast filtered reconnect.
2. `transport.start()` failed at the prelude or the READ with the write refused ("write characteristic gone" on an arm the OS still called connected — a stale ACL, the `DAILY.md` class); the one `Request failed with status 8` is a cousin.
3. Two stacks alive after a Target switch (`stopStack` under `synchronized`, but a keeper stop that returned early would leave the old
   loop running): two ~3 s cycles interleaved read as one 1.5 s cycle; the five-note bursts as several shells' ticks.
4. The warmup frame refused under Silent Mode (status 5) failing `start()` each time — the 15:06:41 start got through because a fresh CREATE's warmup is accepted once (§38.1).
5. The capability READ eaten in the previous session's teardown (§12's class) with the 2 s re-ask and a failing something else — three msgIds per cycle fit prelude + READ + one more.
6. The Nordic manager's `connect()` resolving at once on a cached connection whose peer had dropped, `linkUp` true, the first write failing.
7. Android's stack rejecting a second GATT client on the same address while the previous one was still closing — a connect-refuse-retry cycle.
8. The keeper's `retryPauseMs` (2 s) never reached: an exception in `stopShell` between attempts throwing straight into the next.
9. The sleeping shell's own `restartSession` calls (the READ said silent, the glasses in fact awake) with the `restart` note lost to a full event buffer — weak, every other note arrived.
10. The phone's seam server or the PC's standby probe driving a second transport start — weak, the PC's log shows only channel attaches.

With 0.41 `keeper: start failed: <exception>` is written per attempt and the transport's lines are
one `curl` away (`/log`). Until then the manual recovery stands (`DAILY.md`).

### 42.4 Kept for the next optimization: the atlas across a rebuild (grade: read from the source)

With §42.1 item 2 the firmware keeps the texture cache on BOTH lenses across a supervision-timeout
rebuild (the re-acquire is a renewal). The shell still throws its atlas away at every session start
(`atlasReset`) and uploads it again — 16 KB on the silent clock, up to 63 KB (~20 s of link) in a
window, a dozen times a day. Skipping the re-upload needs one fact: **what a mode-13/14 draw into a
released cache returns** (`texture_cache.c` returns −1 into `image_dispatch`, which fails the
batch; whether the ImgResCmd status is distinguishable from Silent Mode's 5 is unknown). Three such
refusals put the shell to sleep (§36), so it cannot be guessed; measure it once on glass (release
the lease on purpose, send one cached draw, read the status), then keep the atlas when the previous
session ended by a link loss and the re-acquire was written within 35 s of the last renewal.

### 42.5 Battery and builds

`:core:test` 497 · `:desktop:test` 11 · selfcheck ×3 (200 checks) · snapshot ×2 (49) · epub
(380/404 images decode) · music · games · lint 0 · APK 0.41 staged; the service restarted on the
same core. Docs: `REMINDER.md` rewritten, `DAILY.md` (the `/log` line, the recovery),
`IMPLEMENTATION.md`, `WINDOWS.md` §6 (the 0.40 bar), `CLAUDE.md`.

## 43. Feed + comics designed and built in one session (2026-09-09)

Adam picked Feed + comics (`EXPLOSION.md` §20 #5) and answered fifteen verdicts (`FEED.md` §1):
Reddit popular anonymously, Slashdot, xkcd, the 8-Bit Theater archive and SMBC; the source list as
the root; the phone fetching for itself when the PC is unreachable; OUT: Hacker News, YouTube, Open
on PC, a Reddit login, manga, a 1:1 zoom. Every source probed live and every strip priced through
the firmware's RLE (`FEED.md` §2); M1–M5 with the battery green after each.

### 43.1 What the probes settled (grade M unless said)

- Reddit's JSON listings refuse non-browser clients; the Atom feeds work but three anonymous fetches
  in a burst drew two 429s (recovered in ~2 min) — the engine holds Reddit to one request a minute.
  Headless Chromium (`~/aria/fetch_page.py`) is refused by Reddit ("blocked by network security")
  and challenged by Slashdot's Cloudflare check — it solves neither.
- Slashdot's comments load client-side (`D2.ajaxFetchComments` → `POST /ajax.pl op=comments_fetch`,
  per-comment HTML for a `cids` list), but the anonymous page carries no id list, `fetch_all=1`
  answers empty, `comments.pl` answers a challenge page. (Revised §43.4: the story page renders the
  thread server-side.)
- 8-Bit Theater is WordPress: the REST category lists 1,313 posts, 1,218 episodes (`Episode NNN: …`), one image per
  page; early years JPEG (about twice the PNG years on the wire). xkcd: a JSON record per strip, 2×
  images for recent ones. SMBC's RSS carries the strip and hovertext; the bonus panel is in a hidden div.
- One Punch Man: Viz holds the English licence, MangaDex has none; a manga page prices at 57 KB per
  screen, three screens a page. Out on both counts.

### 43.2 What was built, by milestone

- **M1 the engine** (`a66f2c8`): fetchers for the five kinds + a generic RSS/Atom adapter, the paced
  http seam, our own jsoup scorer (Readability4J dropped for its Jackson 2.9 pull), strips, a file
  store with retention, `--feed-check` over fixtures and live; 14 tests on captured bytes.
- **M2 the window** (`95fe6c8`): nine levels in the Reader grammar, synced records with the union
  rule, the endless archive with the keyboard jump, Browse a subreddit / a section with recents and
  Pin, Mark all read behind a confirm, the Flagged list, deep links, twelve Settings rows, notices
  default off with a first-sight baseline; `IconKind.FEED`; six window tests; the selfcheck walk and
  eight snapshot scenes. Both harness scripts crossed the JVM's 64 KB method limit and were split.
- **M3+M4 the channel and the phone** (`3ecba02`): `FeedService`/`RemoteFeedProvider` with strips
  as deflated 4bpp rows, `changed`/`state` pushes, a status cache; `SwitchingFeedProvider` — the
  phone engine after the `PC loss` threshold, back only by hand; the phone adopts the PC's source
  list; APK 42/0.42.
- **M5 the record**: `FEED.md` §8, `IMPLEMENTATION.md`, `REMINDER.md`, `WINDOWS.md` (a seventh
  precedent), `EXPLOSION.md`, `DAILY.md`, `CLAUDE.md`, memory.

### 43.3 Decisions made inside the plan

- Strips fit the shell's document column (564 at full width), not the design's 596.
- Three per-comic `art` rows instead of a global `Line art` row plus overrides.
- The article's images are demanded from the loop's `view()` (the Torrents page-demand rule) and
  re-demanded after a relayout; a restored actions/comments level is restored after the list
  re-opens the item; a restored reading position waits for the article.
- Harnesses walk to source rows by identity (`rootRowId()`); the MAIN entry keeps the root cursor
  on the last source.
- The oracle walk's one failure came under a concurrent APK build; twice green alone. **Batteries
  and the APK build run separately from now on.**
- **The harnesses raced the page load** (found by running each more than once): "the xkcd list" is
  true the instant the level opens, the click landed on the loading row, the walk one level off —
  selfcheck failed 2 of 4 runs, snapshot 3 of 5. Both wait on `FeedWindow.itemsLoaded()` before any
  click on a list (`WINDOWS.md` §5).

### 43.4 The first evening on glass — five findings, fixed the same night (`fa3747d`)

Adam installed 0.42 (`FEED.md` §8.2 has each mechanism): Reddit posts with a body showed none of it
(the builder kept it only for TEXT posts — the poster's words lead every kind now); a Slashdot story
showed the summary and nothing of the article (the source link lives in the story page's prose —
summary, then the source under `from <domain>`); Slashdot comments (the story page renders the
thread server-side after all — 100 of 176 on a big story, the rest by id through the `comments_fetch` call §43.1 had found;
why the morning's page had none is unexplained, grade S); the reading text at 20 px where the
Reader uses 17 (17 now; the per-app `Font size` row scales it); comics wanted xkcd's own bar —
`next · prev · random · first · latest · menu` under the strip on a canvas, a tap pressing the
highlighted button, one notch up from the top wrapping onto the bar, xkcd flipping by number. The
first canvas painted over the list it replaced (the snapshot showed it, no check did) — a canvas
clears its rect first now; `WINDOWS.md` §5 carries the trap. Live that night: a 176-comment thread
as 100 threaded comments; a story behind the NYT's 403 falling back to the summary with the reason.

### 43.5 The state at the end of the session (2026-09-09, ~23:00)

- Tree: `main` at `fa3747d`, pushed; clean.
- Service: beardos on the same core (`~/.damage/damage.jar` staged 22:51); fetches the five sources
  into `~/.damage/feed/`, the 1,218-page archive indexed, `feed` served on the content port.
- Phone: **0.42 installed**; **0.43 staged** with the window-side fixes (the font size and the comic
  bar need it; the post text, source articles and Slashdot threads reach 0.42 from the PC's engine).
- Battery: core 521 · desktop 12 · selfcheck 230 (3 of 3) · snapshot 57 scenes (2 of 2) ·
  `--feed-check` fixtures + live · epub · music · games · lint 0.
- Not measured: everything in `FEED.md` §2.6 and §3.8 is modeled; no journal read with Feed in it.

### 43.6 Where the next session picks up (the polish session)

1. Read `REMINDER.md` → this section → `FEED.md` §1 (the verdicts stand), §8 (§8.3 numbered) →
   `WINDOWS.md` §5–§6 → `IMPLEMENTATION.md` "Feed".
2. Confirm what is installed (the journal's `build` field): 0.43 is the first build with the bar
   and the 17 px text.
3. **The measured walk first, before any change** (`FEED.md` §8.4): `tools/glassdrive.py` through
   every level, one step per snap around Mark all read; `tools/journal_report.py`'s per-gesture rows
   into `FEED.md` §3.8; the comic canvas is the case to watch. Then Adam's verdicts on 16 vs 4 gray
   levels for 8-Bit Theater, the bar's feel, the text size.
4. Then `FEED.md` §8.3 in order, each item priced first: the Reddit comments pace (1), the honest
   line for a Slashdot page without its tree (3), the by-number restore (6); ask Adam before an SMBC
   archive (5) or a bar on the 8-Bit archive (7); one deliberate fallback try with the service stopped (8).
5. The battery after every change; the APK build in its own gradle call; every harness more than
   once; look at every canvas scene.
6. **Do not** re-open `FEED.md` §1's verdicts, re-probe `comments.pl`, re-pitch a headless browser,
   HN, YouTube, Open on PC, a Reddit login, or manga.

After Feed, the next window is Adam's pick from `EXPLOSION.md` §20: Mail (#6), SMS (#7, with the
caller-ID source), Info (#8), Notices (#9).

## 44. G2CC's server retired: the setup page and the adaptive playlists are Damage's (2026-09-10)

The PC rebooted at 04:06 after the 2026-09-09 world upgrade (kernel 6.18.48; JDK 25.0.3 → 25.0.4
replaced under the running JVM at 23:37, whose spawn helper then refused every `sh` until the
reboot — the tmux status poll errors in the log). Adam asked for every part of Damage up and coming
back at boot, then ruled: *"G2CC is replaced by DamageWM"* — off boot, stopped, the adaptive
playlists kept adaptive under Damage, the setup page served from Damage with the same URL and token.

### 44.1 What the reboot showed (measured, `DAILY.md` updated)

- **A boot-order race.** `damage` started in the same second as Postgres and five seconds before
  Qdrant; `startMusic` returns null for the whole run when the Postgres open fails. The init script
  says `use net bluetooth tailscale postgresql-17 qdrant qbittorrent`; resolved order dbus →
  bluetooth → tailscale → postgresql-17 → qdrant → qbittorrent → damage.
- **The service's PATH lacked `~/.local/bin`**, so the Ask lane's `claude` (`ClaudeOneShot`) was
  unfindable under the service (0 log hits). `export PATH` in the script;
  `--enable-native-access=ALL-UNNAMED` added (junixsocket's restricted call is a warning on JDK 25,
  a refusal later).
- **qBittorrent was a GUI app in the X session** — no X after a reboot, no Torrents backend. It is
  the OpenRC `qbittorrent` service (`qbittorrent-nox` on Adam's profile via
  `/etc/conf.d/qbittorrent`; the window logs `40 transfers`). The GUI shares the profile.
- **G2CC's server** got a service (`/etc/init.d/g2cc`) for an hour, then was retired at Adam's
  call. What it cost: its Parakeet speech daemon held **10.2 GB of the 3090's VRAM and 6.2 GB of
  RAM** (measured, freed on stop); it rewrote the 25 adaptive playlists at every boot and ran its
  own migrations on the shared `g2cc` database — against `MUSIC.md`'s "Damage is the only writer".
  🔴 **Never start it by hand again**: it would take :7300 from the setup page and write the
  playlists under Damage. `gaming-mode-on/off` used to do exactly that; both now stop and start the
  `damage` service (ON stops it with the other dependents — the phone keeps driving on its caches
  and says `PC gone` until OFF; delete `damage` from ON's list to keep the data host up while gaming).

### 44.2 The setup page — `desktop/SetupServer.kt`

Same URL (`http://beardos:7300/setup` on the tailnet), same token (G2CC's `authToken` copied into
`~/.damage/config.json` as `setupToken`; `setupPort` 7300; an empty `setupToken` means the replica
token), same gate (Tailscale interface or loopback, a loud 403 elsewhere; `/damage-apk` wants
`?token=` or `Authorization: Bearer`), the same mtime-stamped download name. Only the DamageWM box
remains. Started next to the content host in `runShell` and `--host-only`; a bind failure is loud,
never fatal. `SetupServerTest` ×3. Verified: loopback and Tailscale 200, `HEAD /damage-apk` 200 as
`damage-wm-20260909-2251.apk` (26,798,092 B — the staged 0.43), no token 401, the LAN address 403.

### 44.3 Adaptive playlists — `core/.../music/AdaptivePlaylists.kt` (`MUSIC.md` §9.8)

The G2CC mechanism re-stated as our own code from the facts in its `playlists.ts` and `resolver.ts materializeRule`: materialize = `MusicDb.planCands` uncapped, sound effects out, one member per dupe
cluster, spoken word in, ordered artist → album → path; refresh = retained members keep their
order, new ones append, non-matches drop, duplicates collapse, ONE transaction per playlist with the
`playlists` row locked first, **no write when nothing changed**; at host start, after a grab's
enrichment, after a rescan; an unreadable rule skipped loudly. `--music-check` runs the derivation
read-only. **Measured: the Kotlin derivation reproduces all 25 memberships (6,637 rows) exactly —
`0 would change` in 614 ms — and the service's first refresh wrote nothing** (`25 adaptive · 0 changed`). `AdaptivePlaylistsTest` ×4.

### 44.4 Also

- `bin/damage` pins the versionless `/opt/openjdk-bin-17` link (the upgrade removed 17.0.19_p10);
  `local.properties` (gitignored) names the Android SDK so the APK builds from a non-login shell
  (`ANDROID_HOME` lives in `/etc/profile.d/g2cc-android.sh`).
- The content port :7401 is a framed channel: an HTTP probe ends a session loudly (`frame length
  1195725856 out of range` is the bytes `GET `). Probe :7403.
- Battery: core 525 · desktop 15 · selfcheck ALL PASS ×3 · snapshots 57 ×2 (differ only by the live
  throughput readout) · epub 380/404 images · games 400 tournaments · feed fixtures · lint 0 · the
  APK builds. Service deployed 16:46 (invisible on glass, §19); the phone reattached to every
  channel. APK 0.42 installed, 0.43 staged — the same file, now served by Damage.

### 44.5 Next

1. Install 0.43 from the same bookmark (it is Damage's page now), then §43.6 as planned.
2. A new adaptive rule is an `UPDATE playlists SET rule = …` — a window row to create one is not
   built (G2CC had none either); price it only if Adam asks.
3. `slappy` (tmux host) has been offline 22 days.

## 45. The documentation de-bloat pass (2026-09-11)

Adam: the docs and the memory index were "ridiculous" (`MEMORY.md` sat at its 24.4 KB cap; the
project `CLAUDE.md` was 44 KB, loaded every session). One pass over every `.md` and the memory
directory, seven parallel agents plus the project `CLAUDE.md` by hand, with one rule set: keep every
number, grade, date, decision, rule, trap, path and section number; cut narrative, repetition,
restated rules, superseded next-step lists.

- **Repo docs 1,174 KB → 866 KB.** CLAUDE.md 44 → 24 KB · HANDOFF 290 → 177 · IMPLEMENTATION 93 → 56
  · HOLDEM 82 → 58 · MUSIC 62 → 47 · EXPLOSION 56 → 43 · DESIGN 172 → 151 · overview 125 → 105.
  Zero headings lost against HEAD, zero qualified `§` references newly dangling (a checker diffed
  both), lint 0, core 525 / desktop 15 green. DESIGN's §2.3 and §4.2 tables (lint reads them) byte-exact.
- **Memory 35 files / 211 KB → 15 files / 61 KB; `MEMORY.md` 23 KB → 2.9 KB** (fourteen one-line
  pointers). Five review memories → one lessons file; the per-window design memories → one verdicts
  file; the latency series → one record. Backup: `~/.claude/projects/-home-user-damagewm/memory-backup-20260911.tar.gz`.
- **Facts corrected in passing:** battery counts everywhere = the measured 525 / 15 / 230; overview
  no longer says Damage needs its own quit path (§1.6 superseded it); MUSIC §12 no longer says modes
  12–15 are unused; overview §13's "next window" is a pointer to `REMINDER.md`; HOLDEM §10.3's
  sizing ladder reads `1/3 1/2 3/4` (what the code draws, SYM002).
- **Left for Adam:** overview §3 / `CLAIMS.md` say the two CFW images differ by 15 bytes; today's
  `verify_cfw.py` reports 19,489 differing bytes from different fork points — true at different
  g2flash revisions, wants a look. Several DESIGN quotes were trimmed to their operative phrase.
- Files stopped short of their percentage targets on purpose: what remains is tables, verdict
  quotes, numbers and rules.

## 46. The popover family and the Claude path — designed, not built (2026-09-11 → 12)

Adam proposed a way for any Claude Code session to put content on the glasses; over two sessions it
became a shell surface. **`POPOVER.md` is the spec; `DESIGN.md` §4.11 the design summary; §0 records
the reversal.** The rulings, in order:

1. His first shape — a results window fed by an image-generation pipeline — was priced and dropped
   (`POPOVER.md` §2.3: a photo 49–76 KB per screen, an image of text 8.4 KB against ~1.5 KB as
   cached text; a generated chart draws plausible glyphs, not the numbers). A structured deck
   rendered by the shell replaced it; this is G2CC Scout's return, display half only
   (`EXPLOSION.md` §8).
2. **Not a window: a popover** over the active window (tmux), scrollable, double-tap dismisses, tap
   toggles check rows, Done on the wrap, saved to a directory Files lists.
3. **No dimming behind it** — a dim-in-place op does not exist; the repaint costs ≈ +0.5 s each way.
   *"We want less latency, not more."*
4. **A general tool for every window; notifications are one type; stacking with proper depth; banners
   OUT; width per app (default, Settings row, spawn value); built all at once.**

What the spec fixes: one modal stack replacing the ~30 three-flag gates in `Shell.kt`; the focused
popover on plane 0 with the one beneath receding by a per-lens copy; at most two visible; height
always from measured ink; the migration order MENU → NOTICE → CONFIRM → PEEK → DECK/ASK inside one
build; the `deck.v1` format, `damage-show`, the user-level skill, `~/.damage/decks/`; six milestones
(`POPOVER.md` §8). Open on glass: the disparity step, the default width, the per-type latency.
Queue position: Adam's call (`REMINDER.md`).

## 47. Latency hardening without touching the firmware (2026-09-12)

**The day's finding (measured, the phone's journal and `/log`):** at 12:00:02 the glasses moved BOTH arms
from 15 ms / latency 1 to **105 ms / latency 4 / 6 s supervision** — 23 s after a stock-UI foreground
excursion (`FOREGROUND_ENTER` 11:59:31, `EXIT` 11:59:39) that followed no rebuild. The APK asks for
`CONNECTION_PRIORITY_HIGH` once, at connect, and the platform applies a peripheral's parameter request
without asking the app, so the link stayed there for three hours: the 0-byte flush median went from
55–76 ms to **531 / 537 ms** (12:00 / 13:00), a 500 B–1 KB flush from 130–240 to ~590 ms, a 6 KB+
flush from 868 to ~3,150 ms — every gesture ~470 ms slower, the modeled worst case of five 105 ms
intervals. The captures already describe the firmware moving the link between an active 30 ms and an
idle 90–105 ms / latency 4 (`overview.md` §5.1). One data point for §42.2, n=1: **zero
supervision-timeout rebuilds in the 3 h on the slow set** against one every 50–80 min on the fast set
that morning (06:20, 07:40, 08:30, 09:22, 10:14).

**The second finding (measured):** the phone reached beardos through a Tailscale relay in Chicago —
62 / 147 / 420 / 1,108 ms per ping, no direct path — because ProtonVPN pushes a gateway redirect and
beardos's tailscaled reached every DERP through France (172–300 ms to each). The PC's standby missed the
phone for two consecutive 5 s probes nine times in two days and started its BLE stack each time, handing
back within a minute. Adam's mosh sessions come from the phone over the same path. Also found: the
texture cache full from 12:59 (62–64 KB of 64 KB; the tmux mono faces "stay pixels" with only a journal
line to say so); Music's YouTube grab failing with a 403 (yt-dlp 2026.06.09, no JavaScript runtime).

Adam's ruling: everything on our end, no firmware change; qBittorrent MUST keep the VPN; Tailscale need
not; no router access. Eleven changes, built and verified together:

1. **The radio asks for its priority again** (`phone/BleTransport.kt`): every parameter update on a
   live arm above the target's ceiling (high: 30 ms; balanced: 60 ms) schedules ONE paced re-ask per
   arm (`PRIORITY_REASK_MS` = 5 s), sent only if the link is still slow when the pacing is up (the
   connect passes through 48.75 ms on its way to 15 and must not be answered). Each re-ask is logged
   and journaled with its count. The parameters also reach `LinkState` as numbers
   (`linkIntervalMs`, `linkLatency`).
2. **Global `Link` row** (`high · balanced`, high the default; `ShellSettings.linkPriority`,
   `Transport.setLinkPriority`, over the seam as `Ctl t="linkpriority"`): pushed to the transport
   before every start and on change. `balanced` is the on-glass experiment for the §42.2 rebuilds.
3. **The regime flips on the parameters at once** (`Shell.linkSlow`: interval × (latency + 1) ≥ 100 ms
   is slow, no EMA dwell), **`LINK SLOW` in the status cell and one notice per episode**, both cleared
   on recovery; re-evaluated on every flush, on `link` notes and on the minute.
4. **The floor watch**: two minute ticks with the ack floor above 250 ms raise one notice with the
   parameters in force; the fall below it another. A pacing decision on a measurement, never a timeout.
5. **Settings writes are answered** (`CfwTransportBase`): the brightness write registers its msgId;
   the firmware's `09-00` (G2CC §3 row 15; faceclaw awaits the same ack) completes it; a LATER
   sid-0x09 answer releases an earlier unanswered write as lost (the §34 rule on the control lane) and
   it is **re-sent once**; a second loss is a `settings` fault. The session-start push is the eaten
   class of §34. `GlassFirmwareSim` answers writes and has an `eatSettingsWrites` knob. A push with no
   session is logged, not dropped.
6. **The standby claims only when both arms advertise to the PC's adapter** (`desktop/Main.kt`
   `StandbyScan`): six probes (30 s) of APK absence, then BlueZ discovery — a phone that is merely
   unreachable over the network still holds the arms (they do not advertise while connected). Every
   claim, refusal and return is logged and journaled as a `keeper` note. If BlueZ cannot scan the
   debounce alone decides, said once.
7. **Atlas eviction and repack** (`GlyphAtlas.repack`, `Shell.atlasEvictFor`): a full cache evicts
   every resident face not drawn in the last 12 frames (`ATLAS_RECENT_FRAMES`) when together they free
   the new face's price, repacks the survivors and the recently drawn icons, and rewrites the glasses
   from the guard up with every font off the live set meanwhile (the lapse path's discipline). Paced
   45 s, never with a chunk in flight; a refused face books a retry a window of frames ahead; a stale
   face never re-enters over a recent one. `atlas full` in the status cell only when a face is still
   pixels after that. `AtlasRepackTest` pins it with belief = glass throughout.
8. **Files shows the cached folder first** (`ListingCache`, `RemoteFilesProvider(cacheDir)`,
   `FilesWindow`): the phone keeps the last locations and listings as JSON under
   `wincache/files` (200 listings, atomic writes, a torn file dropped); the window shows them under
   `cached · refreshing` and the live answer replaces them — or stays under the error line.
9. **Torrents keeps its last snapshot on disk** (`wincache/torrents/snapshot.json`): the list is up
   the moment the window opens after an APK restart and the first poll sends the disk's version.
10. **`tools/journal_report.py` prints the parameters in force per hour** (from the `link` notes) next
    to the ack medians they explain.
11. **Tailscale around the VPN** (`/etc/local.d/tailscale-bypass.start`, OpenRC `local`): tailscaled
    marks its sockets with fwmark 0x80000; one rule ahead of its own sends that traffic to table 100,
    whose default is the LAN gateway. Applied and measured the same afternoon: the phone answered
    **direct in 32 ms** (was 62–1,108 ms via the relay), beardos's relay connections leave from the LAN
    address, qBittorrent's 256 connections still leave from the tunnel, the default route unchanged.
    The one transient: the relay connections opened from the tunnel address were black-holed by the
    new rule until `rc-service tailscale restart` re-dialled them. Also: yt-dlp updated to 2026.08.19
    and `--js-runtimes node` on both yt-dlp calls.

Not done, by design: the atlas skip across a rebuild (§42.4 — one glass measurement gates it), glyph
subsetting (the recorder's all-or-nothing rule would send more strings to pixels), anything on the
firmware. The `balanced` experiment and the re-ask's behaviour against a firmware that keeps asking are
what the next `/log` answers.

## 48. The CFW fork decided: all other work suspended (2026-09-12, evening)

A discussion-only session (nothing built, nothing flashed) on what a fork of the custom firmware
could give Damage. Adam's ruling at the end: **fork it, rebuild all of Damage on it, every existing
window fully animated, all other work suspended until that is complete, tested and in daily use;
new windows after.** The plan is `FORK.md`; the contract is `FIRMWARE.md`; both written this session.

### 48.1 What was read, and what it settled (grades in `CLAIMS.md`)

Every g2flash patch source, openCFW's subsystem recovery docs and its 2.2.6.10 decompile corpus
(7,449 functions), the phone's journal (61,292 flushes, read-only through `/journal`), AOSP's GATT
priority table, and the repo's own records.

- **The image ack precedes the panel refresh** (V): in the chunk-complete branch of the EvenHub
  dispatcher `FUN_004da834`, the status-4 sender `FUN_004da4a4` runs before `FUN_004da382` queues the
  deferred decode/present. The journal's ack times are a lower bound on what the eye waits for.
- **One AA packet per ATT write** (C): openCFW's reconstruction of `TPL_ReceivePacket` reads one
  packet's length and ignores the rest of the write.
- **The panel-refresh queue carries a rect** (V): stock callers pass 576×288, the CFW passes 640×480
  (`FUN_00474066(0,0,0,0,w,h)`; the display task `FUN_00473c44` hands the four words to the ULED
  manager's async refresh). Whether the panel driver refreshes only that rect: U.
- **Stock's display-position / near-far offsets live in the copy the CFW replaces** (V): `FUN_0046ca14`
  positions the 576×288 buffer inside 640×480 with offsets capped at 64 and 192 and calls the GPU
  preprocess; both of its call sites are redirected to `display_copy_hook`. Nothing stacks on Damage's
  depth (closes `DESIGN.md` §3.3's (U)).
- **The two arms sync over a UART carrying TinyFrame, master/slave** (I): openCFW `uart_sync.c` and
  the sync-framework recovery. Latency unmeasured.
- **The ring's own link ends at the glasses** (V): `app_ble_central.c`'s RingLink states; the phone
  sees ring input only as the glasses' SysEvents (RIGHT).
- **The connection-parameter policy is a mapped first-party object** (V): `app_connect_params.c`,
  14 functions, fast/slow classification at 25 and 72 units, profile table via `_connectParamReq_impl`.
- **Android HIGH priority asks 11.25–15 ms at latency 0** (V, AOSP config). Where the measured
  latency 1 comes from is unknown — an earlier "the glasses' choice" was a guess and is withdrawn.
- **Journal, 0.40 onward (M):** window notch first ack 105 / 204 / 522 ms median / p75 / p90; whole
  gesture 342 ms median, 3.0 s p90; the tail is pixel bytes (large content boxes); a flush under
  100 B acks in ~60 ms and each KB adds ~140 ms; 5,949 rects served as cached draws over 7,960
  flushes; `proof` refusals 179.

### 48.2 Corrections to earlier statements in the same session

The ring already reaches the glasses directly (the earlier "glasses handle the ring themselves"
meant: act on the event locally, as stock's own UI does). The atlas re-upload after an arm rebuild
needs no firmware (§42.4 already plans the skip); only persistence across sessions does. Headroom
for appended code is ≈337 KB under g2flash's conservative ceiling, not 350–400.

### 48.3 The decisions (proposed, in force until the Phase 0 close)

D1 base 2.2.6.10 + `a5d1c31` · D2 the fork is its own GPL-3.0 repo, Damage stays clean-room ·
D3 popovers built in Phase 6 · D4 offline home in scope, last · D5 new windows after · D6 motion
answers input only · D7 the "instant" targets · D8 Damage-only. `FORK.md` §2 has the reasons.

### 48.4 What was written

`FORK.md` (the plan: phases 0–8, the flash ritual, the assumptions table, the log), `FIRMWARE.md`
(the contract skeleton), this section, `REMINDER.md` rewritten to start here, `CLAUDE.md`'s read
order and status, `CLAIMS.md` rows, memory (`damage-cfw-fork.md`, the state file, the index, and a
pointer in the global memory). `~/damage-cfw` created from `reference/g2flash` at `a5d1c31` on
branch `damage` with the §10 flasher fix carried over — a working tree, not committed. The repo's
own working tree (these docs) is not committed either.

### 48.5 Wording

Adam asked twice this session for plain wording: the model's safety checks tripped repeatedly on
firmware, radio and memory phrasing, and the build will be worse. `FORK.md` and `FIRMWARE.md` open
with a "Context for the reader" block for that reason; keep it in every new file.
