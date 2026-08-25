# HANDOFF — from the first build to the finishing build

**Written 2026-08-25 for a fresh session.** Read this first, then the reading list in §3, in
order, before touching code. Everything below is verified against the repo at `main`
(`a138de7`, pushed to `origin`); "modeled" and "measured" are marked where it matters.

## 0. The target of the next build, in Adam's words

> The result of this next build should be a fully working DamageWM that only needs me to flash
> the glasses firmware and install the app and poof it works. Something I can also use from the
> app and/or the PC itself using my mouse and a pixel-perfect replica of what the glasses would
> be showing (not the Even Realities simulator, which is NOT accurate).

Decisions Adam made for this build (2026-08-25):

| question | answer |
|---|---|
| How does "use it from the PC" reach the glasses? | **Both**: a PC-direct BLE transport on beardos **and** the phone-bridge path. The PC shell can use either. |
| Where does the PC-side replica live? | **Both**: the native desktop window (mouse input) **and** a browser page like G2CC's setup page. |
| G2CC after the flash? | **Damage replaces it.** G2CC stays installed but is not expected to work on the CFW. |
| Any glasses contact before the flash? | **No.** Nothing touches the glasses until Adam flashes. The BLE layer is verified against G2CC's proven code and the simulator only; first light is after the flash. |

Standing rules that do not change (`CLAUDE.md`): never flash, never write `sid 0x80`, never send
`f1=9`; dry-run before any real flash; leaving 2.2.2 is irreversible; clean room (protocol
facts from g2flash/faceclaw, **no code**); **G2CC is Adam's own — borrow its code freely**, but
**never modify `/home/user/G2CC`**; NO TIMEOUTS / NO SILENT FAILURES / NO TRUNCATION; neutral
engineering wording in prose, comments and reviewer prompts (a safety filter tripped once on
dramatic verbs); measured vs modeled every time; links and key actions **last** in a message.

## 1. Where the project actually is

### 1.1 What exists and is verified (against the simulator)

- **`:core`** (Kotlin, shared unmodified by desktop and phone): compositor (per-lens
  truth/shadow model), wire codecs (CRC/protobuf/AA envelope/EvenHub/settings/CFW modes),
  `GlassFirmwareSim` (byte-exact model of the CFW display path: per-lens shadows, cfw_diag fid
  ring and flags, warmup drop, msgId-255 kill, stuck sessions, lease fail-open), the transport
  seam (`Transport`, `Emit`, `CfwTransportBase`, `SimTransport`, `RemoteTransportClient/Server`),
  the shell (input grammar, chrome, Main, switcher, notifications, silent mode, slides,
  persistence, settings), Reader + EPUB, content providers (local dir, TCP host, remote with
  copy-on-open caching).
- **`:desktop`**: AWT rasterizer, a Swing 1× preview (keyboard = ring; **no mouse yet**), the
  content host, `--selfcheck`, `--snapshot`, `--epub-check`, `--remote HOST` (PC shell driving
  a phone's transport — **opens no preview window**).
- **`:phone`**: foreground `ShellService`, `LensView` (on-screen lens, touch = ring; **draws the
  sim only**), `AndroidText`, the transport seam server (a PC shell can claim/yield), §9.3 urgent
  phone notifications, and `BleTransport` (Nordic BleManager glue; **never run on hardware**).
- **Verification battery, all green at HEAD**: `:core:test` (47 tests incl. `LensOracleTest`:
  compositor belief == simulator lens panels == an independent per-lens truth, across depth
  8/12/16 and every shell transition; `Round3/5/6/7Test`: fid wrap inside a flush, keyframe of a
  busy plane map within the 16-fid ring, plane change with no pixel change, stop-during-start
  keeps state.json, same-instance transport restart, diag-clear resync, lost flush between landed
  ones, cell noise under a box converges, text-shaped damage economy, oversize payloads, rollback
  after 30 copies, batch byte cap), `desktop --selfcheck` (25 checks), `--snapshot` (lens-truth
  PNGs, judged by eye), `--epub-check ~/books` (57/57), `tools/lint.py` (0), `:phone:assembleDebug`.
- **Eight review rounds** (independent reviewer agents per subsystem, every candidate verified
  by trace, timing or pixel simulation before a fix): ~70 real defects fixed; the compositor was
  rewritten twice; `IMPLEMENTATION.md` → "Review hardening" lists the load-bearing mechanisms.
  Lesson recorded there: an exactness oracle alone missed a livelock and cubic-time merging —
  reviewers must also measure convergence counts, bytes and wall time on real content.

### 1.2 What is missing for "flash + install → works" (the gap list, severity first)

1. **No PC-direct BLE transport.** The desktop has no Bluetooth code; the PC reaches the
   glasses only through the phone (`--remote`). Decided: build it (Linux/BlueZ on beardos).
2. **No way to select the GLASSES target in the phone UI.** `Prefs.setTargetGlasses` exists,
   nothing calls it; Settings has only Size/Depth/notification rows. Today it takes `adb` or a
   code change.
3. **The BLE glue has never executed on hardware** and was written from the Nordic library's
   API, not from a working driver. G2CC's Android BLE driver **works on real glasses today**
   (stock firmware) — port its GATT layer (§4) and keep `CfwTransportBase` as the protocol brain.
   Unknowns to settle by reading G2CC: whether the CFW needs G2CC's `AuthSequence`/pairing
   handshake, MTU/PHY negotiation details, write pacing, reconnect strategy.
4. **The replica goes dark against real glasses.** Phone `LensView` and desktop `Preview` draw
   the simulator's panel; with the GLASSES target nothing feeds that sim. Decided design (§5.B):
   tee every message the transport actually writes into a local mirror sim.
5. **No mouse on the PC preview; no browser replica page** (decided: build both, §5.D).
6. **`--remote` mode shows nothing on the PC** (needs the replica via the compositor's belief
   or the phone's mirror stream).
7. **Link death ends the session; reconnect is host-driven** (`onLinkDown` sweeps and shows
   LINK DOWN; the APK does not auto-rebuild). Needs an automatic reconnect loop with no timeouts.
8. Open design question for Adam: a notification box while the switcher wheel is open repaints
   on top of the wheel (`REMINDER.md`).
9. Not built from `DESIGN.md` §5: rule 5 (speculative pre-compression), rule 10 (cross-window
   deltas), rule 18 (content-hash cache keys). The seam is designed to take them.

### 1.3 Measured vs modeled — say which, every time

| number / behaviour | status |
|---|---|
| 176 ms image ack, 7–13 KB/s | **measured on stock 2.2.2** (captures); **modeled** for the CFW direct-FB path |
| CFW mode bytes, batch layout, fid ring semantics | **read from `zlib_glue.c`**, pinned in the sim; never observed on glass |
| bulk pixels → LEFT arm, control → RIGHT | **strong, not proven** (read from faceclaw code) |
| per-notch scroll events | graded **C** — the whole focus model rests on it |
| rect budget of 5 (16-deep ring / window 3) | **inferred**, never observed |
| everything the battery proves | **sim-measured** — the sim is a model written from source, unverified on hardware until first light |

## 2. Repo map (what lives where)

```
core/src/main/kotlin/wm/damage/core/
  geom/     Geometry.kt (runtime rules = tools/geometry.py), Layout.kt, FidTracker.kt
  gfx/      Gray8, Codec (RLE/zlib), Icons
  wire/     Proto, AaFrame, EvenHubMsg, SettingsMsg, CfwModes
  sim/      GlassFirmwareSim.kt        ← the byte-exact firmware model
  transport/ Transport.kt (the seam), Emit.kt, CfwTransportBase.kt (the protocol brain),
             SimTransport.kt, RemoteTransport.kt (seam over TCP)
  comp/     Compositor.kt (per-lens model), Journal.kt
  shell/    Shell.kt (loop, pump, lifecycle), Notifications, Switcher, Slide, Chrome,
            ContentKit, MainSurface, SettingsWindow, SilentMode, Persistence, ShellSettings
  windows/reader/  ReaderWindow.kt, Epub.kt
  content/  Content.kt (LocalContent, ContentHostServer, RemoteContent)
core/src/test/kotlin/wm/damage/core/   CodecTest, GeometryTest, SimRoundTripTest,
            ShellBehaviorTest, LensOracleTest, Round3Test, Round5Test, Round6Test, Round7Test
desktop/src/main/kotlin/wm/damage/desktop/  Main.kt, AwtText, Preview, SelfCheck, Snapshot
phone/src/main/kotlin/wm/damage/phone/  ShellService, MainActivity, LensView, AndroidText,
            BleTransport (banked)
tools/lint.py, tools/geometry.py, design/render_shots.py, research/verify_cfw.py
```

## 3. Reading list, in order

1. `REMINDER.md` — orientation, the first-light checklist (items 12–14 are new), open questions.
2. `IMPLEMENTATION.md` — the built stage, the seam, how to run/verify, **"Review hardening"**.
3. `CLAUDE.md` — the rules, including the battery to keep green after any change.
4. `overview.md` §2 (BLE facts), §3–4 (CFW contract), §5 (measured numbers), §8 (CompressMode),
   §9 (g2flash's shipped HardFault), §15 (collaboration with the CFW author).
5. `CLAIMS.md` — the grades; anything `S`/`U` must not be built on without checking.
6. `DESIGN.md` §1 (input grammar), §2 (geometry), §3 (depth), §5 (engine rules + the
   implementation-status note), §8 (fid discipline, wide flushes), §9 (persistence, journal),
   §10 (topology: three roles, four configurations, the seam), §11 (open items).
7. Source, in this order: `Transport.kt` → `CfwTransportBase.kt` → `Emit.kt` + `CfwModes.kt` →
   `GlassFirmwareSim.kt` → `Compositor.kt` (its class doc is the model) → `Shell.kt` (start/stop,
   pump) → `RemoteTransport.kt` → `ShellService.kt` + `MainActivity.kt` + `LensView.kt` →
   `BleTransport.kt` (what will be replaced) → `Main.kt` + `Preview.kt`.
8. Tests: `LensOracleTest.kt` (how the oracle drives the sim), `Round7Test.kt`.
9. Memory: `~/.claude/projects/-home-user-damagewm/memory/` — `damage-first-build.md`,
   `damage-next-build.md`, `adam-working-preferences.md`, `g2-cfw-mode-table.md`.

## 4. G2CC study — what to read there and what to take

G2CC (`/home/user/G2CC`) is Adam's working, shipped system for the **stock** firmware (EvenHub
containers, 576×288). Its transport layer runs on real glasses daily; its display path does
not apply to the CFW. **Read from it freely, port from it freely, never edit it.**

| path (under `/home/user/G2CC`) | take |
|---|---|
| `docs/G2_BLE_PROTOCOL.md`, `docs/PROTOCOL_NOTES.md` | the capture-derived wire spec (already our source #3); re-read §6 events, the AA envelope, ack/msgId behaviour |
| `android/app/src/main/kotlin/com/g2cc/g2cc/ble/G2BleClient.kt`, `BleScanner.kt`, `ConnectionState.kt`, `PairingState.kt`, `AuthSequence.kt` | **the proven GATT driver**: scanning by advertised name, connect order, MTU, notification enable, any auth/pairing handshake the glasses require, reconnect behaviour, error surfacing. This replaces the Nordic-based guesswork in `BleTransport.kt` |
| `ble/FrameReassembler.kt`, `G2Frame.kt`, `Crc16.kt`, `Varint.kt`, `EvenHub.kt`, `EventParser.kt` | already mirrored in `core/wire`; cross-check ours byte for byte |
| `render/BleDisplaySink.kt`, `render/DisplayProto.kt` | how G2CC writes image messages to the glasses on real hardware: fragment pacing, ack handling, which arm, write type — the **measured** behaviour our sim only models |
| `service/ConnectionService.kt`, `net/ConnectionManager.kt`, `net/WsProtocol.kt`, `BootReceiver.kt` | the foreground service that stays alive all day, its reconnect loop (no timeouts in the design sense — check), the phone↔PC WebSocket seam and its defences (auth window, malformed-frame handling) |
| `harness/ControlMirrorView.kt`, `harness/ExpectedMirror.kt`, `os/MirrorGeometry.kt`, `harness/HarnessActivity.kt`, `DisplayTestSequence.kt` | the phone mirror and the on-hardware display test harness — the shape of a first-light harness for Damage |
| `server/static/pc/{app,net,render,input,gray4bmp,geometry}.js`, `server/src/setup-page.ts` | the browser replica: canvas over WebSocket, wheel/arrows scroll, click select, Esc back, a text bar. Damage's page should look like this but render **our sim's per-lens panel** (exact), not G2CC's simulator-based frame |
| `HANDOFF.md`, `overhaul.md` §22–24, `docs/CODE_REVIEW_*.md` | the three absolute rules' origin; the failure classes G2CC already paid for |
| `android/app/build.gradle.kts`, `AndroidManifest.xml` | permissions and foreground-service types that are known to work on the Pixel 10a |

What NOT to take: anything that assumes EvenHub containers or 576×288 (G2CC's compositor,
`os-compose.ts`, `Scene`, `G2Renderer`), the simulator-based mirror content.

## 5. The work plan for the finishing build

Order matters: A and B make the phone usable on flash day; C–D make the PC usable; E–H make it
"poof it works". Every step ends with the battery green and, for anything touching the wire,
a review round (fresh reviewer, concrete traces, sim as the oracle — **no hardware**).

**A. The phone drives real glasses.**
- Port G2CC's GATT driver into `BleTransport` (keep `CfwTransportBase` unchanged as the brain;
  the glue implements `connectLink`/`disconnectLink`/`writeArm`/`onNotifyPacket`/`onLinkDown`).
  Resolve from G2CC: auth/pairing sequence (does the CFW require it? faceclaw's connection
  sequence is the CFW reference — compare), MTU 247 and 1M PHY, notification enable on the
  RIGHT arm, the arm split for image writes (bulk → LEFT is strong-not-proven: keep it a
  one-line switch), write pacing.
- Settings row **Target: SIM / GLASSES** (+ a phone-side toggle), persisted; default stays SIM
  until Adam flips it after flashing. The capability gate remains the guard: stock firmware
  is refused loudly.
- Automatic reconnect on LINK DOWN with no timeouts (event-driven: rescan/reconnect until the
  pair is back or the user stops), surfacing every state change in the status bar and the
  phone notification. Phone Bluetooth toggling remains the documented at-work recovery.

**B. The exact replica everywhere — the mirror tee.**
- In `CfwTransportBase`, tee every byte actually written to the glasses (image messages,
  control writes that affect the display, the warmup, mode-7 clears) into a local
  `GlassFirmwareSim` ("mirror sim"). The mirror shows what the firmware holds after applying
  our exact bytes — exact relative to the model, not to Even's simulator. Cross-check against
  `Compositor.expectedLens()` (the belief) and surface any divergence as a fault.
- Phone `LensView` draws the mirror sim in GLASSES mode (both lenses, tap toggles).
- Desktop `Preview` draws the mirror sim in laptop-direct mode and the belief/mirror stream in
  `--remote` mode.

**C. PC-direct BLE transport (Linux/BlueZ on beardos).**
- Same brain, new glue: a `BlueZTransport` in `:desktop` over BlueZ's D-Bus API (beardos: Intel
  AX201, `hci0`, BlueZ 5.86). Pick the JVM D-Bus/BlueZ library by reading its source and
  licence (`bluez-dbus` by hypfvieh is the known candidate; verify it handles MTU, notifications,
  write-without-response and connection parameters on 5.86 — and that macOS/Windows parity is
  deferred, per `DESIGN.md` §10.7).
- Scanning by advertised name, both arms, the same lease/capability/warmup choreography; the
  desktop chooses `--transport sim|ble|remote`.

**D. The PC replica surfaces with mouse input.**
- Native window: mouse wheel = ring scroll (one notch per event), left click = tap, right
  click (or double-click) = double-tap, press-and-hold = long-press; Tab/keys stay. Add a
  Right-lens toggle and a "both lenses side by side" view.
- Browser page served by the desktop program (token-gated on the tailnet like G2CC's setup
  page): canvas fed by a WebSocket stream of the mirror sim's per-lens panels (send dirty rects,
  not full frames), same mouse/keyboard mapping, status line with link/lease/fault state.
  Works in laptop-direct, PC-direct BLE and `--remote` modes.

**E. Takeover and fallback, seamless.** PC shell ↔ phone shell claim/yield already exists over
the seam; make the switch automatic where it can be (PC appears on the tailnet → PC drives;
PC gone → phone resumes) and always visible. Both replicas stay correct through a takeover.

**F. First-light readiness (no hardware contact until the flash).** A one-screen "flash day"
runbook in `REMINDER.md`: `research/verify_cfw.py`, `g2flash.py --stop-before flash`, the
irreversibility statement, BTSnoop enabled before connecting, the diag overlay flags treated as
hard errors, the checklist items 1–14 with what each looks like on the replica.

**G. The parts of `DESIGN.md` still unbuilt** that the finishing build should at least not
foreclose: §5 rules 5, 10, 18; §7 compass/IMU/wear (mode-10 feed); §4.5 emergency alerts
(needs the WEA test on the phone); the app layer beyond Reader + Main (the scope explosion
comes first — on paper).

**H. Review rounds on everything new**, the same method: fresh reviewers per subsystem,
verify each candidate with a concrete trace or a sim run, fix, repeat until clean; measure
convergence, bytes and time on real content, not only exactness.

## 6. Traps the reviews already paid for (do not re-learn these)

- The compositor reasons **per lens**; a nominal-rect model guessing per-lens consequences was
  wrong three times. Every stereo op is judged by `LensOracleTest` against the sim.
- A flush never spans the 0xFFFE→1 fid wrap; a mode-7 clear resets the firmware's baseline, so
  the host tracker/allocator resync with it; msgId cycles 1..249 (0 and 250+ avoided).
- Session lifecycle: `stop()`, a failed `start()` and `onLinkDown()` bump the epoch and sweep
  (fail every waiter loudly, restore permits, drain queues, abort a parked capability gate).
  Nothing may wait on a dead session — NO TIMEOUTS is only acceptable because of this.
- A lost flush marks per-lens cells UNKNOWN; never "restore a snapshot" — other flushes land
  around a lost one.
- Shell `start()`/`stop()` serialize; a stop during start once wrote defaults over state.json.
- A single mode-8 sub-message carries a 16-bit length (65,535 B including its header); the
  batch has bmp_max 153,718 B; a keyframe past the sub-message length ships bare.
- The lease fails OPEN: stop renewing and stock repaints. Renewal is correctness.
- The simulator lies about nothing we modeled, but it is a model: at first light, treat any
  divergence flag as a hard error and suspect the model before the design.

## 7. How to run and verify (commands last, per Adam's terminal)

```
cd /home/user/damagewm
./gradlew :core:test                                   # 47 tests
./gradlew :desktop:run --args="--selfcheck"            # 25-check gate
./gradlew :desktop:run --args="--snapshot DIR"         # lens-truth PNGs — look at them
./gradlew :desktop:run --args="--epub-check"           # 57 books
python3 tools/lint.py                                  # design gate
./gradlew :phone:assembleDebug                         # APK
./gradlew :desktop:run                                 # laptop-direct with the sim
./gradlew :desktop:run --args="--remote <phone-ip>"    # PC shell over the phone
python3 research/verify_cfw.py                         # before any flashing conversation
```

APK: `phone/build/outputs/apk/debug/phone-debug.apk` · secrets: `damage-secrets.properties`
(gitignored, never display) · config: `~/.damage/config.json` · git: `main` on
`https://github.com/expectbugs/damage-wm` · G2CC (read-only): `/home/user/G2CC`.
