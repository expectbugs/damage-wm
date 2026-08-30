# HANDOFF — from the first build to the finishing build

**Written 2026-08-25 for a fresh session.** ✅ **The finishing build is COMPLETE (2026-08-25, five
review rounds, battery green) — §8 is its record** (decisions, fixed design, checklist, resume
protocol, progress log); what comes next is in `REMINDER.md`. 📍 **Firmware install is IN PROGRESS — paused before any radio use; a fresh context resumes at §9** (2026-08-30). Read the rest, then the
reading list in §3, only as §8 points you to it. Everything below is verified against the repo at `main`
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
- Session lifecycle: `stop()` and `onLinkDown()` bump the epoch and sweep (a failed `start()` sweeps without bumping)
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


## 8. The finishing build — decisions, design, checklist, resume protocol (2026-08-25)

**This section is the working state of the build.** It is written so that a session whose
context has been compacted or lost can resume from here alone: §8.1 holds Adam's decisions
(verbatim where it matters), §8.2 the design of every new piece (fixed, not to be re-derived),
§8.3 the checklist (checked the moment an item is done, committed with the item id), §8.4 the
resume protocol, §8.5 the progress log. Wording stays neutral engineering prose everywhere —
prose, comments, commit messages, reviewer prompts.

### 8.1 Decisions (Adam, 2026-08-25, answering the seven pre-build questions)

1. **Connect prelude:** adopt the CFW reference's single **sid-0x01 app-launch** prelude (own
   implementation, protocol fact cited to faceclaw) and keep the 7-packet sid-0x80 sequence out
   entirely. Study finding behind it: faceclaw connects RIGHT then LEFT, requests MTU 512 and
   high connection priority, enables 5402 notifications, settles ~800 ms, sends ONE sid-0x01
   request `{f1=2, f2=<msgId>, f4={f3={f2={f2={f1=0,f2=0}}}}}` and waits for the ack (a sid-0x01
   non-notify frame echoing f2), then the FB lease and the settings/capability query. G2CC's
   `AuthSequence` (sid 0x80) is the official app's stock prelude and is not used on the CFW.
2. **No radio use on beardos during the build** — not even a listen-only scan. BlueZ is
   validated by adapter enumeration (a D-Bus read) and by a fake D-Bus layer under the glue.
3. + 5. **Driver arbitration — the configuration contract, in Adam's words:** *"The default
   will be phone app + Home PC over internet to phone, if internet to phone is lost fall back
   to phone app only, if phone app is not up fall back to Home PC over BLE directly. Once
   running, system should always be trying to find a way to connect and drive the glasses
   with robust aggressive reconnect for any method that falls off (keep checking to see if
   Home PC to phone app to glasses or just Home PC over BLE to glasses is possible, stay
   fallen back to phone app only until it is, and switch to Home PC driving it as soon as
   that option is available again). Home PC is always the best case to constantly be trying
   for as it is the most powerful and capable implementation, phone-app-only is the weakest
   implementation and last resort, but one of those should ALWAYS be running."*
   Reading adopted for the build (§8.2 "Arbitration"): **who drives** = the PC shell whenever
   it can reach the glasses by any path, else the phone shell; **PC path order** = the phone's
   transport over the seam first, PC-direct BLE when the phone is not reachable; **a working
   path is held until it drops** (no proactive handover from a working PC path — a handover
   would blank the display and can fail); every link reconnects aggressively with no timeouts.
4. **Browser replica** served by both the desktop program and the phone, port 7403, token-gated
   like the seam.
6. **A notification arriving while the switcher wheel is open waits behind the wheel** — it is
   queued unshown and unfurls (with its normal grace) when the wheel closes; a box already on
   screen when the wheel opens goes back to the queue unread and returns after.
7. **Permissive third-party dependencies (MIT) are acceptable** for PC-direct BLE. The
   clean-room rule is about GPL code.

Standing decisions from §0 still hold: both PC paths, both replicas, Damage replaces G2CC, no
glasses contact of any kind before Adam flashes; phone target defaults to SIM until he flips it.

### 8.2 Design of the new pieces (fixed — do not re-derive after a compaction)

**Mirror (`LensPanels`).** `core/transport/LensPanels.kt`: `interface LensPanels { val exact:
Boolean; fun stride(): Int; fun panel(arm: Arm): ByteArray /* packed 4bpp, live buffer */; fun
addListener((Arm) -> Unit); fun removeListener(...) }`. `GlassFirmwareSim` implements it
(`exact = true`; `panelChanged` feeds the listeners). `Transport` gains `val mirror:
LensPanels`. `CfwTransportBase(scope, name, mirror: GlassFirmwareSim?)` tees every packet it
writes into `mirror.write(arm, packet, nowMs())` (the mirror's own notifications are discarded;
its `decode`/`fid`/`session` diag events surface as `Fault("mirror", …)` — the model predicting a
silent rejection; the mirror's clock is driven from the maintenance tick). `SimTransport`'s
mirror is its sim (no tee). `RemoteTransportClient.mirror` is a `RemoteMirror` (`exact =
false`) fed by seam `panel` messages. Every replica draws `transport.mirror`.

**Input injection.** `Transport.injectInput(type: Int)` emits `TransportEvent.Input(type,
SRC_RING)` into the transport's own event flow, so a gesture from a replica reaches whichever
shell currently drives (phone touch during a PC takeover included). `RemoteTransportClient`
emits locally. Hosts route replica gestures through it instead of `shell.postGesture`.

**Prelude.** `core/wire/LaunchMsg.kt`: `SID = 0x01`, `prelude(msgId)` builds the payload above.
`CfwTransportBase.start()`: after `connectLink()` and an 800 ms settle, write the prelude on the
RIGHT arm through the control lane and wait for its ack (`onNotifyPacket`: a sid-0x01 frame
whose flag is not an event and whose f2 equals the pending msgId completes it; a sweep answers
it with the sentinel like the capability gate). `GlassFirmwareSim` models it: a sid-0x01 request
is acked on RIGHT with `{f1=2, f2=msgId}`. Selfcheck asserts the prelude was acked.

**Divergence check.** In `Shell.completeFlush`, after a successful `FlushDone` with nothing
in flight and `transport.mirror.exact`, compare `comp.expectedLens(L/R)` (values n·17) with the
mirror panels (nibble·17). On the first mismatch per compositor epoch: status `DIVERGE`, journal
note with the first differing pixel and the count, one urgent notice, one `requestKeyframe()`.
Skipped while a flush is in flight or after a failed flush until the next clean one.

**Session keeper.** `core/shell/ShellKeeper.kt` — the one reconnect loop both hosts use:
`start()` runs `shell.start()`; when the transport's `started` goes false (polled every 250 ms —
pacing, not a timeout; the loop narrates the link end from that same poll with the reason the
last `Link(false)` carried, see amendment 9) or a start fails it
saves (stop) and restarts after a 2 s pause, forever (scans have no timeout); a `Fault("capability")` or a
start failure naming the capability gate is TERMINAL for that transport: the loop stops, an
urgent notice is raised, `onTerminal` fires (the phone falls back to the SIM target so the
on-screen replica keeps working, and says so in the status line). `pause(reason)`/`resume()` for
takeovers. Every transition reaches the status bar op cell ("reconnecting", "scanning",
"LINK DOWN") and the host status callback.

**Arbitration (`PathTransport`).** `core/transport/PathTransport.kt`: a `Transport` whose
`start()` races its candidates (priority order: `remote:phone` seam client, then `ble`) — all
attempts run concurrently; the first to complete `start()` wins, the others are cancelled
(cancellation of a `CfwTransportBase.start()` sweeps and disconnects; the seam client connects
through an interruptible NIO channel so an unreachable phone never blocks the BLE attempt).
Events/state/mirror delegate to the winner; `transportName` says which path. A winner's
`Link(false)` propagates and the keeper restarts `start()` → a new race, phone first. A
candidate that fails at the capability gate is disabled for the process lifetime; when every
candidate is disabled the keeper goes terminal. The desktop's default mode is `auto` (this
transport with `phoneHost` from `~/.damage/config.json`, default `aphone`); `--transport
sim|ble|remote` select one path explicitly; `sim` is the development environment.

**Amendments from review round 1 (2026-08-25).** (1) The arbitration decides by ENGAGEMENT,
not speed: `Transport.engaged` is true for the seam client from the server's grant (the phone
has yielded its shell) until its start completes or fails; a lower-ranked path holds off
entirely while a higher-ranked one is engaged and otherwise only gives it a head start — so the
phone path wins by construction when the phone is reachable. Known limit: a reachable phone that
cannot itself see the glasses keeps the radio waiting (the status says so). (2) The seam server
runs the inner start as a job and keeps reading; a driver that leaves mid-start is seen and its
attempt cancelled. Every rollback/stop path in the base and both glues runs under
`NonCancellable`, so a cancelled attempt always disconnects. (3) A capability refusal is a typed
`CapabilityRefused`; only that is terminal for the keeper / disabling for the arbitration
(`PathTransport` throws it when every path is disabled). (4) Hardware transports derive
`leaseHeld` from their mirror (the model's fail-open) on the maintenance tick; the initial lease
write is awaited; maintenance traffic waits for `started`. (5) `LinkState.detail` narrates
what a transport is doing (scanning / connecting / prelude / capability / carrier / lease /
warmup) and travels over the seam; every status line shows it. (6) The seam streams panels as
per-arm marks built at send time and deflated, so a slow link carries the latest content with
bounded memory; the replica server does the same. (7) The phone scans FILTERED on the remembered
pair (addresses + names) so a pocket-time loss recovers with the screen off; unfiltered only for
a pair never seen. (8) The phone installs a `Log` sink (logcat + rate-limited urgent
notifications for errors). See `REVIEW.md` for every finding and its verdict.

**Amendments from review rounds 2–4.** (9) The keeper restarts from the transport's `started`
(polled every 250 ms — pacing, not a timeout) and narrates the link end from that same
decision, with the reason the last `Link(false)` event carried; the watcher only records that
reason. The keeper's own stops never reach that line (they cancel the loop or leave it), so
they are never narrated as a loss. (10) The
seam answers each flush exactly once: on a link end or a stop every outstanding flush is failed
loudly (`FlushDone(ok=false)`) before the link-down event, and a later `done` for an id no
longer outstanding is ignored with a line. (11) The desktop glue reads RSSI one request at a
time; a read the bus has not answered by the next due tick is skipped with a line. (12) The
seam client ignores every frame a reader from a superseded session still routes (a stale
state, panel or event after `stop()`). (13) The browser replica leaves modifier chords to the
browser and never keeps a wheel step it did not send (a gated notch-sized step, or a residue
of the other direction).

**Phone.** `ShellService` keeps its transport (sim or BLE per target) under a `ShellKeeper`;
the seam server's claim pauses the keeper and the release resumes it (the existing rebuild
stays for the release path). `BleTransport` glue: RIGHT then LEFT, `retry(10, 500)`, MTU 512
requested and the negotiated value checked ≥ 245, `CONNECTION_PRIORITY_HIGH`, notification
enable with failure surfaced, `useAutoConnect(false)` (the keeper owns reconnect), RSSI poll on
RIGHT, unexpected disconnect → `onLinkDown`, cached pair addresses accepted by the scanner next
to the name match. Target switch: a control-strip button (confirm on tap) and a Settings row
supplied by the host (see below); switching restarts the stack; `Prefs` persists it.
`LensView` and the phone's replica page draw `transport.mirror`; touch goes through
`injectInput`. Phone `versionCode`/`versionName` bump on every build Adam installs.

**Host-supplied Settings rows.** `Shell.hostSettings: List<HostSetting(name, value: () ->
String, options: List<String>, apply: (String) -> Unit)>` appended to the Settings list after
the §4.2 rows. Phone: `Target: sim / glasses`. Desktop: `Target: auto / sim / ble / remote`.
Applying restarts the stack through the host.

**PC-direct BLE (`desktop/BlueZTransport.kt`).** `bluez-dbus 0.3.5` (MIT) + `dbus-java-core
5.2.0` + `dbus-java-transport-native-unixsocket 5.2.0` + an slf4j binding. System bus;
default adapter; LE discovery filter; devices matched by advertised name (`Even G2` + `_L_`/`_R_`)
or cached address; connect RIGHT then LEFT; wait for `ServicesResolved` (property change, no
timeout); service `…5450`, chars `…5401` (write) / `…5402` (notify); `StartNotify`; the
characteristic `MTU` property checked ≥ 245 (loud refusal otherwise); writes =
`WriteValue(type=command)`; `Connected=false` → `onLinkDown`; RSSI from the device property when
BlueZ reports it (only while advertising — say "n/a" otherwise). Verified without radio use:
adapter enumeration on beardos + unit tests over a fake of the four D-Bus calls the glue makes.

**Replica page (`core/replica/ReplicaServer.kt`).** Dependency-free HTTP/1.1 + WebSocket
(RFC 6455) server: `GET /?token=…` serves one self-contained page; `GET /ws` upgrades; the
first client frame is `{"t":"auth","token":…}`. Server → client: binary panel frames
`[arm u8][y0 u16][rows u16][rows·stride bytes]` for dirty row-ranges (diffed against what that
client last received; a fresh client gets both full panels), and JSON `{"t":"status", link,
lease, driver, transport, ackMs, bps, faults[]}`. Client → server: `{"t":"input","ev":
"tap"|"double"|"up"|"down"|"hold"|"release"}` → `transport.injectInput`. Page: two 640×480
canvases (toggle / side-by-side, `image-rendering: pixelated`, no upscaling by default), the
same mouse mapping as the desktop (wheel notch with accumulation, left = tap, right =
double-tap, press-and-hold = long-press then release; no browser `dblclick` — it fires `click`
first), keyboard as the desktop, a status line, reconnect with backoff. Served by the desktop
and by the phone on `replicaPort` (7403).

**Desktop preview.** Draws any transport's mirror; mouse as above (hold ≥ 600 ms — the ring's
own threshold is unmeasured); Tab = lens toggle, B = side by side; a status strip under the
1× panel (path, link, lease, ack ms, B/s, last fault) — outside the 640×480 image so the true-1×
rule holds.

**Notification vs wheel (decision 6).** `Shell.handleNotice` queues while `switcher.open`;
`openSwitcher` requeues a shown box unread; `commitSwitcher`/`cancelSwitcher` call
`showNextIfIdle()` and schedule the grace.

### 8.3 Checklist

"battery" = `:core:test` · `--selfcheck` · `--snapshot DIR` (look at the PNGs) · `--epub-check`
· `tools/lint.py` · `:phone:assembleDebug`. Each item ends with the tree compiling, the item
checked here, and one commit `§8 <id>: <what>`.

**F — foundations (core)**
- [x] F1 phone `versionCode`/`versionName` bumped; `REVIEW.md` created for the review phase
- [x] F2 `LensPanels` + `Transport.mirror` + the `CfwTransportBase` tee; SimTransport mirror = its sim; `Transport.injectInput`; test: a tee'd transport's mirror equals an independent sim fed the same bytes
- [x] F3 prelude: `LaunchMsg`, the start-time handshake, the sim's ack, a sweep answers a parked prelude; test in `SimRoundTripTest`; selfcheck asserts it
- [x] F4 divergence check in `Shell`; test with a forced mismatch (write into the sim's panel between flushes)
- [x] F5 `ShellKeeper` in core; test: a link death restarts the session, a capability refusal goes terminal
- [x] F6 notification waits behind the wheel (decision 6); `ShellBehaviorTest` case
- [x] F7 host-supplied Settings rows; test that the row appears and applies
- [x] F8 battery green; commit

**A — the phone drives real glasses**
- [x] A1 `BleTransport` glue rebuilt per §8.2 (RIGHT then LEFT, retry, MTU check, priority, notify enable surfaced, settle, RSSI, disconnect → `onLinkDown`, cached addresses)
- [x] A2 `ShellService` on the keeper; claim pauses / release resumes; terminal → SIM fallback with a persistent notification
- [x] A3 target switch: strip button + Settings row + `Prefs`; stack restart on switch
- [x] A4 `LensView` draws `transport.mirror`, both lenses, touch via `injectInput`
- [x] A5 battery green + APK builds; commit

**B — the seam carries the mirror**
- [x] B1 seam `panel` messages through one ordered sender coroutine (events, state, panels); `RemoteMirror` on the client
- [x] B2 test: loopback seam round trip — client mirror == server mirror after flushes; ordering panel-before-done
- [x] B3 battery green; commit

**C — PC-direct BLE**
- [x] C1 dependencies in `desktop/build.gradle.kts` (+ fat jar); licences noted in `IMPLEMENTATION.md`
- [x] C2 `BlueZTransport` per §8.2 behind a small `BlueZLink` seam so the glue is unit-testable with a fake
- [x] C3 tests over the fake (connect order, MTU refusal, notify routing, disconnect → link down); adapter enumeration run once on beardos (no discovery)
- [x] C4 battery green; commit

**D — replicas**
- [x] D1 desktop Preview per §8.2 (mouse, side-by-side, status strip, mirror source)
- [x] D2 `ReplicaServer` + page in core; served by the desktop; unit tests for the WS handshake key and frame codec; selfcheck opens a loopback WS client and receives a panel frame
- [x] D3 phone serves the page (`replicaPort` in `Prefs`/BuildConfig)
- [x] D4 battery green; commit

**E — arbitration**
- [x] E1 `PathTransport` in core; test: a race where the first candidate stalls and the second wins, the stalled attempt is cancelled cleanly; a capability refusal disables a candidate
- [x] E2 desktop `auto` default, `--transport sim|ble|remote`, `phoneHost` in config; `bin/damage` unchanged
- [x] E3 desktop on the keeper: link death → new race; status strip narrates
- [x] E4 battery green; commit

**F/G — docs**
- [x] DOC1 `REMINDER.md` flash-day runbook (one screen) + the first-light items each path adds
- [x] DOC2 `IMPLEMENTATION.md`, `README.md`, `CLAUDE.md` current (configurations, transports, replicas, target switch, keeper, arbitration, licences)
- [x] DOC3 note where §5 rules 5/10/18 attach; `DESIGN.md` §4.3/§4.5 record decision 6
- [x] DOC4 commit

**H — review rounds**
- [x] H1 fresh reviewer agents per subsystem (transport base + prelude, BLE glue, BlueZ glue, mirror + seam, replica server + page, shell changes, phone service, arbitration + keeper), each told to verify every candidate with a concrete trace, timing or sim run; findings logged in `REVIEW.md` with verdicts
- [x] H2 every finding re-verified by the builder before a fix; fixes; repeat until a round is clean (five rounds; round 5 clean on the code — `REVIEW.md`)
- [x] H3 final battery; memory + handoff updated; commit (2026-08-25 05:15 CDT — the finishing build is complete)

### 8.4 Resume protocol (after a compaction or a fresh session)

1. Read this §8 top to bottom, then `IMPLEMENTATION.md`, then the `CLAUDE.md` rules. Do not
   re-read the whole research corpus; §8.2 is the design and §8.1 the decisions.
2. `git status` and `git log --oneline -15`: the last `§8 <id>` commit is the last finished item.
   The checklist and the git log must agree; where they disagree, trust git + the battery and
   fix the checklist.
3. If the working tree is dirty, `git diff` shows an item in progress. Finish it if it is
   small and its intent is clear from §8.2 and the diff; otherwise `git stash` it, note that in
   §8.5, and redo the item from the design.
4. Run `./gradlew :core:test` (and `:desktop:compileKotlin :phone:assembleDebug` if the item
   touched those). Green before continuing.
5. Continue at the first unchecked item. One item at a time; check it off and commit the moment
   it is done; append one line to §8.5.
6. New decisions made mid-build (anything a resumed session could re-derive differently) go
   into §8.2 immediately, not only into code comments.
7. In the review phase, every finding goes into `REVIEW.md` as it is found (candidate →
   verification → verdict → fix commit), so a compaction cannot lose an unverified finding.

### 8.5 Progress log

- 2026-08-25 — §8 written; Adam's decisions recorded.
- F1 done (b031568): plan committed, phone 0.2 (code 2), REVIEW.md.
- H review round 2 committed (66ed069); round 3 launched (two compact reviewers on the round-2 diff).
- H review round 5 done — CLEAN on the code (7 candidates: one stale doc sentence, two comment tidies, four builder's-choice items; taken: the link-end reason is recorded only while driving, `@Volatile keeper`, the error-notice cap counts only shown notices). Review loop closed: 124 candidates over five rounds, 104 fixed. **H3 done: final battery green on the final state** (core 70 tests, desktop 9, phone compiles, selfcheck 28, 10 snapshots, epub 57/57, lint 0, APK + fat jar). §8.3 fully checked; memory updated; final commit. The finishing build is complete; the glasses remain untouched (stock 2.2.2.20); next is Adam's flash + the `REMINDER.md` runbook.
- H review round 4 done: 8 candidates on the round-3 diff, 6 fixed (`REVIEW.md` R4.*): the keeper narrates the link end from its own loop (deterministic; the STARTING-phase overlaps retired), the seam client ignores frames from a superseded session, the page leaves modifier chords to the browser and never keeps a wheel step it did not send, the phone's queued switch and `switchTarget` share one `isRunning`, the error limiter prunes and caps per tag; one coverage note accepted. Core 70 + desktop 9 green; battery running; round 5 (final compact pass on the round-4 diff) running in parallel.
- H review round 3 done: 15 candidates, 12 fixed, four regression tests added (`REVIEW.md` R3.*); the seam answers each flush once, the keeper narrates by state, one RSSI read in flight, the closing text re-packs, the divergence count resets, the phone's sink outlives the stop, the page's wheel gate covers every branch. Core 70 tests + desktop 9, selfcheck 28, snapshots, epub 57/57, lint 0, APK, fat jar — green. Doc counts corrected (70/9). Round 4 (one compact reviewer on the round-3 diff) next, then H3.
- H review round 2 done: 30 candidates, 26 fixed (`REVIEW.md` R2.*) — the strip re-pack, a second close waiting on the first, divergence state per session, read notices leaving the queue, the winner-never-leaked race guard, the keeper deciding restarts from the transport's state, outstanding flushes failed on a seam loss, the desktop glue's both-arms check and `close()`, one log sink per service; two tests added. Core 73 tests + desktop 8 green; battery running; commit next, then round 3 on the round-2 diff.
- H1/H2 round 1 done: six reviewers, 64 candidates verified (`REVIEW.md`): 58 confirmed and fixed, 2 design calls taken (e9 kept, f6 decided), 2 accepted (d8 safe, f8 test-only), 1 already fixed (f4 by a4), 1 doc. Core 69 tests + desktop 8 green; battery running; round 2 next on the changed areas.
- E4 + DOC1–DOC4 done: battery green (core 63, desktop 4, selfcheck 28, snapshots, epub, lint 0, APK, fat jar); REMINDER.md (finishing-build summary, flash-day runbook, first-light items 15–17, decision 6 closed), IMPLEMENTATION.md ("The finishing build" section, commands, configurations, verification counts), README.md, CLAUDE.md (status, battery incl. `:desktop:test`, no-radio rule, beardos BLE reachable), DESIGN.md (§4.3 decision 6 note, §5 attach points for rules 5/10/18 and rule 16, §11 items 4 and 7).
- E1–E3 done: `PathTransport` (concurrent attempts, priority = a 1.5 s head start per rank, failed attempts retried with backoff while the race is open, capability refusal disables a path, a stable mirror proxy, events/state forwarded from the winner); the seam client's connect is interruptible (NIO channel + `runInterruptible`); desktop `auto` is the default (`remote:<phoneHost>` then `ble`; BLE absent → phone only, loudly); `PathTransportTest` (first path wins + loser cancelled + submit/mirror/input through the winner + re-race after stop; refusal disables + a failed attempt is retried). The desktop's keeper + status strip existed since D1.
- D4 done: battery green (core 61 tests, desktop 4, selfcheck 28 checks incl. the replica page + token gate, snapshots, epub 57/57, lint 0, APK); the page's script passes `node --check`.
- D1–D3 done: `Preview` draws any mirror through a provider (wheel notch, left tap, right double-tap, hold ≥600 ms → long-press then release, middle/Tab lens, B both, status strip under the 1× image); `ReplicaServer` (HTTP + RFC 6455, token-gated, per-client dirty-row panel frames + 1 Hz status, input frames) with `replica.html` (two 640×480 canvases, pixelated, 1×/2× toggle labeled, same mouse/keyboard mapping, reconnect with backoff); `ReplicaServerTest` (RFC accept key, 403/200, panel frames after a flush, inputs); the desktop rebuilt around `DesktopStack` (sim | ble | remote, `ShellKeeper`, `Target` host row switches by rebuilding the stack, replica on `replicaPort` 7403, `Config.phoneHost` default `aphone`, cached pair addresses in the config); the phone serves the page on `Prefs.replicaPort`.
- C1–C4 done: `BlueZLink` seam + `BlueZDbus` (bluez-dbus 0.3.5 / dbus-java 5.2.0 / native unix-socket transport / slf4j-simple; raw `Device1.Connect` so a refusal keeps its reason; MTU via `Properties.Get`; notifications via `PropertiesChanged(Value)`), `BlueZTransport` (RIGHT then LEFT, MTU ≥ 245 checked, cached addresses, `Connected=false` → link down), `BlueZTransportTest` (4 tests over a fake link whose far end is the firmware model), `--ble-info`. **Measured on beardos, no discovery:** the JVM reaches bluetoothd on the system bus; hci0 C4:BD:E5:2E:C9:75 powered; two previously known devices listed. The radio path itself remains unexercised until first light.
- B1–B3 done: seam `panel` messages (arm, y0, rows + packed rows) through ONE ordered outbox with events/state/done; full panels on session start; `RemoteMirror` applies them; `SeamMirrorTest` (equality after every flush, panel-before-done, far-end input). Core 59 tests green, desktop compiles.
- A1–A5 done: `BleTransport` rebuilt (RIGHT then LEFT, retry 10×500, MTU 512 checked ≥245, priority HIGH, notify enable surfaced, cached addresses, RSSI poll); `ShellService` on `ShellKeeper` (claim pauses / release resumes; capability refusal → SIM fallback + persistent notification); target switch (strip button with confirm + Settings host row + `Prefs`); `LensView` draws `transport.mirror`, touch via `injectInput`; `Prefs.replicaPort`/`BuildConfig.REPLICA_PORT` (7403); APK builds. Note for review: a takeover still stops and restarts the transport (lease release + reconnect + keyframe) — the session model, not a defect.
- F8 done: battery green (58 core tests, selfcheck 26 checks, snapshots, epub 57/57, lint 0, APK).
- F7 done: `HostSetting` rows in `SettingsWindow` (stage on scroll, apply on tap, revert on double-tap), `Shell.hostSettings`.
- F6 done: a notice arriving while the wheel is open is queued unshown (`Notifications.post(show=false)`); a shown box requeues unread on open (`abandonFurl` for a mid-furl box); `showNextIfIdle` + grace on commit/cancel.
- F5 done: `ShellKeeper` (start/stop/pause/resume, restart after `Link(false)` with a 2 s pause, capability refusal terminal), `ShellKeeperTest`.
- F4 amended: the belief is compared through `Pack.level` (the shadow keeps 8-bit levels), one report + one keyframe per disagreement EPISODE (the per-epoch guard did not bound keyframes); `Shell.quiescenceReport()` for failed settles and status lines.
- F4 done: `Shell.checkMirrorAgreement()` at rest (status DIVERGE, journal, urgent notice, one keyframe per epoch; `lastDivergence`, `divergencesReported`), `DivergenceTest`.
- F3 done: `LaunchMsg` (sid 0x01), the base's prelude gate (800 ms settle, ack on msgId, session-end marker), the sim's strict model (`preludeSeen`/`preludeAcks`), `PreludeTest`, harness + selfcheck updated.
- F2 done: shared `transport.Arm`, `LensPanels`, `Transport.mirror` + `injectInput`, the base's tee (after a successful write; mirror faults as `mirror/<kind>`), `RemoteMirror` stub, `MirrorTeeTest`.

---

## 9. Firmware install — resume point (2026-08-30)

**Status: paused before any radio use, at Adam's request. Nothing has been written to the
glasses. They remain on stock 2.2.2.20.** This section is written so a fresh context resumes the
install with no re-derivation. Read it top to bottom; do not skip to a command.

### 9.1 The one irreversible fact, stated plainly

Leaving stock **2.2.2** is the single step that cannot be undone. There is no firmware read-back
path, and 2.2.2 is **not** in the public 19-image archive — every *other* version can be
re-installed later, but the factory image now on Adam's glasses cannot be recovered once replaced.
The CFW itself stays revertible to any archived version, and G2CC keeps working against it. Say
this out loud to Adam before the write step, every time.

### 9.2 What is already done this session (all safe, no radio)

- **Offline image check PASSED** — `python3 research/verify_cfw.py` (exit 0): the CFW image is
  reproducible from sources we hold, the archived `g2-2.2.6.11.bin` equals the rebuild byte for
  byte, and there is no Thumb-bit defect. Re-run it at resume; it is free and offline.
- **Runbook read** — `REMINDER.md` flash-day runbook + the first-light checklist (items 1–18).
- **Tool read** — `reference/g2flash/g2flash.py`. For this PC the transport is **`g2://local`**,
  which uses this machine's own radio through `bleak`. Stages, in order:
  `discover → heartbeat → file_check → flash → done`. `--stop-before flash` runs
  discover/heartbeat/file_check and writes **no firmware data** (it connects, enables
  notifications, sends the keepalive and the FILE_CHECK request, reads the acks). The tool has an
  interactive confirmation prompt (a typed phrase) unless `--my-warranty-is-void` is passed.
- **Adapter check** — `desktop --ble-info`: hci0 `C4:BD:E5:2E:C9:75` powered, the user is on the
  system bus. (That address is **beardos's own adapter**, not the glasses.)
- **venv ready** — `./venv` created; `bleak` imports (`./venv/bin/python -c "from bleak import
  BleakScanner, BleakClient"` returns ok).
- **Adam has disconnected the phone from the glasses** so they advertise for a direct PC pairing.

### 9.3 The image to install

`fws/2.2.6.11-105032302d02/g2-2.2.6.11.bin` — the CFW. `verify_cfw.py` confirms it equals
SybilSight's reproduced output and the archived image. The tool's own `validate_firmware` prints
`firmware ok: … 5 segments` before the confirmation prompt; that is the go/no-go on the file.

### 9.4 The one item to resolve before connecting — the arm addresses

`g2://local?left=<L>&right=<R>` needs both arm addresses. **They are NOT recorded in our docs.**
⚠ The two addresses at `overview.md:1164` (`C4:AF:F2:54:38:29`, `C4:60:45:13:B3:36`) are a THIRD
PARTY's glasses (Danxtream's committed logcat) — **do not use them.** Adam's own addresses come
from a scan: the arms advertise as `Even G2_<serial>_L_<tail>` / `_R_<tail>`, and the tool matches
by that name. Get them one of two ways at resume:

- a short scan — `./venv/bin/python -c "import asyncio; from bleak import BleakScanner;
  print(asyncio.run(BleakScanner.discover(timeout=15, return_adv=True)))"` — and read the two
  `Even G2_…_L_…` / `_R_…` names and their addresses; or
- let the tool find them: it scans in `connect()` and matches by side, but the connection string
  still needs `left=`/`right=`, so the scan above is the way to fill them in.

The arms must be powered and NOT connected to the phone (they are, per 9.2). `addressType=public`
(a normal Linux MAC).

### 9.5 The exact next steps (only with Adam present and giving the in-the-moment word)

1. `python3 research/verify_cfw.py` — re-run, offline. State the 9.1 fact out loud.
2. Fill in the two arm addresses (9.4).
3. **Non-writing dry run** (writes no firmware):
   `./venv/bin/python reference/g2flash/g2flash.py -c "g2://local?left=<L>&right=<R>&addressType=public" -f fws/2.2.6.11-105032302d02/g2-2.2.6.11.bin --stop-before flash`
   Expected: `firmware ok: … 5 segments`, both arms found, `discovery: ok`, the FILE_CHECK acked.
   Read every ack. Anything unexpected stops the procedure — do not proceed to step 5.
4. Show Adam the dry-run output and get his explicit in-the-moment go for the write.
5. **The write** — the same command **without** `--stop-before flash` (default `--lens both`). The
   tool prints the confirmation banner and waits for the typed phrase. Do not pass
   `--my-warranty-is-void`; let Adam type it. This is the irreversible step.
6. Do **not** bypass the tool's guards. The hardware-safety facts are already coded into it: the
   image must fit under MRAM (`validate_firmware` + the size ceiling), and an already-written block
   must never be re-sent (the OTA path has no dedup and would double-advance). The CFW is +20,127 B
   and bumps the preamble correctly — `verify_cfw` confirmed it. Let the tool's retry logic handle
   any per-block re-send; do not hand-retry.

### 9.6 After the install

`REMINDER.md` runbook steps 4–9, then first-light items 1–18. In short: `desktop --selfcheck`
still green and `--ble-info` still shows hci0 powered; install the APK and switch its Target from
sim to glasses, watching the status line walk `starting → scanning → driving via ble`; leave the
Diag overlay ON for the first session (any sticky flag is a hard error); then the PC `auto` path
(phone-seam first, PC-direct BLE otherwise); then the browser replica; then write each measured
number into `overview.md` §5 with a "measured on CFW" mark. Item 18 is the new one — whether the
ring delivers the release event for the switcher chord.

### 9.7 Rules still active

No radio use returns to normal now (the build's no-radio rule was for the build; the install is
the radio work). Still absolute: the offline check first, the non-writing dry run first, and **no
write without Adam's explicit in-the-moment word** — not on momentum, not because this section
says so. Neutral wording throughout. No timeouts, no silent failures.
