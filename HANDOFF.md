# HANDOFF — the build record

**Map, newest first. A fresh session starts at §13 (the NEXT mission), then §12 for what just
landed.**

| § | what | status |
|---|---|---|
| **19** | **The arbitration correction + sync (2026-08-31, night)** — §8.1's "PC is the best case" meant DATA, not the driver: the phone shell is PRIMARY, the PC is the data provider + rare BLE stand-in; last-write-wins state sync | **current** |
| **13** | **NEXT: perfect the APK** — the phone must do everything the PC does, in every §10 configuration | **the mission** |
| **12** | **The refinement wave, 2026-08-31** — the whole `REFINEMENT.md` queue built and driven live; the latency curve measured; switcher/brightness/battery fixed | done |
| **11** | **First light, 2026-08-30** — the PC drove the glasses, the ring drove them, ack latency measured, three defects found | done |
| **10** | The firmware install: what changed upstream, the image chosen, the dry-run staircase, the result (§10.11), the ring update (§10.13) | **current** |
| 9 | The earlier install plan | ⛔ **SUPERSEDED by §10** — written against the older g2flash and the older image. Do not follow it |
| 8 | The finishing build (2026-08-25) | history; its whole gap list is closed |
| 0–7 | The first build and its plan | history |

**The work queue is now `REFINEMENT.md`.** `REMINDER.md` holds what is still unmeasured.


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
  ring and flags, warmup drop, msgId-255 silence, stuck sessions, lease fail-open), the transport
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
   ⛔ **The reading below was a MISREADING — corrected by Adam 2026-08-31, see §19.** "Best
   case" meant the PC being available to PROVIDE DATA so every app is fully functional — not
   the PC shell driving. Kept verbatim as the record of what the finishing build believed:
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

## 9. Firmware install — resume point (2026-08-30) ⛔ SUPERSEDED

> **Do not follow this section.** It was written against g2flash `877c8d9` and the archived
> 2.2.6.11 image. The reference repos moved the same evening and the plan changed with them:
> a different image, a new authentication step in the flasher, and a staged dry run in place of a
> single one. **§10 replaces it in full**, and §10.11 records what actually happened. Kept only
> because §10 refers back to it.


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
6. Do **not** skip the tool's guards. The hardware-safety facts are already coded into it: the
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

---

## 10. The CFW moved. Re-planned install (2026-08-30, later the same day)

**Read this instead of §9 where they disagree.** §9 was written against g2flash `877c8d9`. All
four reference repos were pulled to their latest that evening, and g2flash had moved a long way.
Nothing has been written to the glasses; they are still stock **2.2.2.20**.

### 10.1 The irreversible fact has not changed

Leaving stock **2.2.2** is the one step that cannot be undone. There is no firmware read-back
path and 2.2.2 is **not** in the public 19-image archive. Every other version can be re-installed;
the factory image on Adam's glasses cannot. The CFW itself stays revertible to any archived
version and G2CC keeps working against it. Say this out loud before the write, every time.

### 10.2 What changed upstream

| repo | was (cloned 2026-08-15) | now | what it means |
|---|---|---|---|
| `g2flash` | `877c8d9` | `a5d1c31` | 15 commits: **texture cache**, builtin-font text, mic control, a 2.2.9 fix in the flasher, a CFW-context relocation |
| `faceclaw` | `6df7e9b` | `c1d70ab` | 0.6.1; EvenHub compat layer, Wear OS app, 2.2.9 security-auth. Phone-resident, not our seam |
| `evenRealities-openCFW` | `201bb80` | `799b286` | more recovered bootloader source; still an analysis project |
| `SybilSight-webflasher` | `4329a56` | `77690c9` | ⚠ **removed custom-firmware support**; added 2.2.9.28 recovery. It is no longer a CFW install path |

**The base image did not change.** Both patch sets still pin stock `g2_2.2.6.10.bin`
(`f4dfb0b4…`), which we hold and which matches byte for byte.

### 10.3 The two candidate images

`research/verify_cfw.py` now builds and checks BOTH, and passes (exit 0).

| | archived 2.2.6.11 (§9's image) | **new g2flash a5d1c31** |
|---|---|---|
| sha256 | `10503230…` | `d4054ab1…` |
| built from | g2flash `877c8d9` + SybilSight's 28 patches | g2flash `a5d1c31`'s 26 patches |
| size over stock | +20,127 B | +39,174 B |
| preamble length bumped | yes, matches payload | yes, matches payload |
| MRAM headroom below the OTA flag | 404 KB | **385 KB** |
| Thumb-bit audit | clean (14 branches) | clean (19 branches) |
| capability string | `EVENCFW/8 … compass10` | `EVENCFW/16 … texcache12 teximg13 texstr14 font15 micctl` |
| Damage's capability gate | passes | **passes** (tested against the real string) |

**Recommended: the new one.** Not only because it is what Adam asked for — it also fixes a real
latent defect in the archived image. The old CFW anchors its context pointer at `0x20003ffc` on
the stated assumption that the word is "spare"; a5d1c31's own source says that address is
**the +0 callback of the BLE-RX lifecycle object, which stock code can BLX through**, and moves
the anchor into 1 KiB explicitly carved out of the primary TLSF arena (`[0x202a6270,0x202a6670)`,
arena shrunk to `0x2cc00`). That is the same class of bug as the Thumb-bit HardFault this
ecosystem already shipped once.

✅ **And it is not an unreleased HEAD build — it is what Faceclaw ships.** `faceclaw`'s
`app/g2/firmware/cfw-patches.ts` (auto-generated from g2flash's patch JSON) pins
`baseSha256 f4dfb0b4…` → `outputSha256 d4054ab1…` across the same **26 patches**: byte-identical to
the image `verify_cfw.py` builds here. So the candidate is the firmware **Faceclaw 0.6.1 installs
on its users' glasses**, not something only we would be running.

**Argue the other way honestly:** the new image carries roughly twice the injected code and the
release is days old. Most of the new bulk is `texture_cache.c` (514 lines) and `mic_control.c`
(446), and the author marks the mic path's stock audio entry points as **ABI-inferred and not yet
validated on hardware** — which is why bringing up the capture hardware sits behind an explicit
arm flag. We never set that flag, and nothing in Damage touches field 103. Both *known* unrecoverable-failure
classes (unbounded MRAM program, Thumb-bit interworking) check clean on it.

### 10.4 Flasher changes that affect the procedure

Only `7c6d3c1` touched `g2flash.py`, but it changed the shape of a run.

- **New mandatory `authenticate()` before FILE_CHECK**, unconditional, no version gate: sid `0x80`
  protobuf `08 04 10 <magic> 1a 04 08 01 10 04` on the CTRL channel, requiring the reply to be
  `08 04 10 <magic>` + exactly `1a 00`.
  ✅ **Resolved offline against our own captures.** Our stock 2.2.2.20 glasses answer that exact
  message correctly — four exchanges across `captures/allbutimages.log` and `imagestatus.log`,
  both arms, 43–92 ms, replies byte-identical to what the tool demands, and the request framing
  and CRC reproduce from g2flash's own `crc16()`. This step will not block us.
- **The CTRL heartbeat thread during transfer is gone**, matching the official app's own capture.
- **New `recover_session()`** on a bare block-ack timeout: disconnect → settle → reconnect →
  rediscover → re-auth → fresh BEGIN, `--reconnect-attempts` (3) times.
- `EXPECTED_SEGMENTS` relaxed in a comment only; the live check always accepted 5 **or 6**, and
  our images have **6**. §9.3's "5 segments" was wrong about the tool's output, harmlessly.
- Unchanged and verified byte-identical: `validate_firmware`, `check_mainapp_fits_mram`,
  `recompute_checksums`, `parse_connection_string`, `match_scanned_device`, `confirm_warranty`,
  the typed phrase, the block NAK/dedup policy, and every flag §9.5 used.

### 10.5 Two residual risks, neither a blocker

1. ⚠ **beardos has never bonded with these glasses** — confirmed 2026-08-30 by `--ble-info`:
   hci0 `C4:BD:E5:2E:C9:75` is powered and BlueZ knows exactly two devices, a controller and a
   pair of earbuds. No G2. The captures prove the app-level auth works,
   but the phone had already bonded and encrypted the link (LE Secure Connections, Rand=0/EDIV=0,
   16-byte key) before touching GATT — so they **cannot** say whether the lenses *demand*
   encryption from an unbonded host. The commit message predicts an OS pairing prompt at the start
   and a second at the halfway mark for the second lens; on Linux that means BlueZ needs a pairing
   agent, which the tool does not provide. This surfaces during connect/discover, long before any
   firmware byte. **The dry-run staircase in 10.6 is designed to hit it first.**
2. ⚠ **`recover_session()` can send a malformed auth.** `auth_frames()` writes the magic as one
   raw byte, but `_nextseq()` returns 1–255 and `recover_session` calls `authenticate()` *before*
   `_reset_seq()`. Any magic ≥ 128 makes `10 <magic>` a continued varint that swallows the
   following `0x1a`, so the request is malformed and every recovery attempt fails. Verified by two
   independent reads plus capture arithmetic (the firmware itself encodes magics ≥ 128 as proper
   multi-byte varints, so it understands them — the tool just cannot emit them).
   - It only fires on a **bare ack timeout during the real transfer**; a `--stop-before flash` run
     never gets the counter past ~3.
   - It **aborts, it does not leave anything inconsistent** — and an aborted transfer leaves the prior firmware.
   - Fix if Adam wants it: swap two lines so `_reset_seq()` runs before `authenticate()` in
     `recover_session`. **His call — do not edit the flashing tool without his word.** Worth
     reporting upstream either way.

### 10.6 The dry-run staircase (replaces §9.5 steps 3–4)

`--stop-before heartbeat` is now the last stage that writes **nothing at all**; `--stop-before
flash` was never literally inert (it always sent BEGIN + FILE_CHECK) and now also sends the auth.
So climb it one rung at a time and read every ack:

```
CONN="g2://local?left=<L>&right=<R>&addressType=public"
IMG=<the chosen image>
./venv/bin/python reference/g2flash/g2flash.py -c "$CONN" -f "$IMG" --stop-before heartbeat
./venv/bin/python reference/g2flash/g2flash.py -c "$CONN" -f "$IMG" --stop-before file_check
./venv/bin/python reference/g2flash/g2flash.py -c "$CONN" -f "$IMG" --stop-before flash
```

1. **`heartbeat`** — connect + GATT discovery, zero writes. Proves beardos can reach both arms and
   the characteristics resolve. **This is where an unbonded-pairing problem shows up.**
2. **`file_check`** — adds the auth exchange only. Proves risk 10.5.1 empty on real hardware.
3. **`flash`** — adds BEGIN + FILE_CHECK. Still **zero firmware bytes**; the gate is intact.

Anything unexpected at any rung stops the procedure. Then show Adam the output and get his
explicit in-the-moment word before the write.

### 10.7 The arm addresses (unchanged from §9.4)

Still not recorded anywhere; `parse_connection_string` is byte-identical and still requires both
`left=` and `right=` regardless of `--lens`. Get them from a scan — the arms advertise as
`Even G2_<serial>_L_<tail>` / `_R_<tail>`. ⚠ The two addresses at `overview.md:1164` are a THIRD
PARTY's glasses; do not use them.

### 10.8 The write

Same command without `--stop-before`, default `--lens both`. Let the tool print its banner and let
**Adam** type the phrase; do not pass `--my-warranty-is-void`. Do not hand-retry a block — the OTA
path has no dedup and a resend double-advances the offset.

### 10.9 Damage-side state at this point

The core already speaks the new firmware: modes 11/12/13/14 encode
(`wire/CfwModes.kt`, `wire/TextureCache.kt`), the simulator models them byte-exactly, the
capability gate is tested against the real `EVENCFW/16` string, and the whole battery is green
(109 tests, selfcheck all-pass, lint 0, APK builds). **Mode 15 is deliberately not implemented**
and the model refuses it: its glyphs come from the firmware's own font, so no offline model can
predict its pixels and the per-lens oracle would stop being exact.

**Not yet adopted, on purpose:** the compositor still emits pixel deltas only. Turning text into
mode-14 cached-glyph draws changes the emit strategy and the whole cost model, and it should be
priced against **measured** ack latency on the CFW path rather than the modeled 176 ms we have
now — which is one flash away. Mode 11 in `stop()` is held back for the same build: its value is
freeing the cache, and `stop()` is a five-round-hardened path not worth disturbing for nothing.

### 10.10 Dry-run results — 2026-08-30, on the real pair, nothing written

Adam's decisions: install the **new** image (the one Faceclaw ships), and **patch**
`recover_session()` first. Both done. The glasses were powered and disconnected from the phone.

**The tool patch.** One line added in `reference/g2flash/g2flash.py`, in `recover_session`:
a `_reset_seq()` *before* `authenticate(tp)`, so recovery reproduces the initial connect's
deterministic `magic == 1`. Without it the magic is whatever the block counter has reached, and
`auth_frames()` writes it as ONE RAW BYTE — a value ≥ 128 is a continued varint that swallows the
following `0x1a`. The three retries would not have saved it: a failed attempt raises inside
`authenticate` *before* the existing reset two lines below, so the retries use **consecutive**
magics (200, 201, 202) and fail together. Roughly a coin flip per recovery event.
Proven offline before use (`scratchpad/authcheck.py`): magics 128–255 fail to parse, magic 1
produces a request identical to the captured one but for its magic, and the framed packet is
`aa21010c01018000080410011a0408011004cc56` — matching what the capture analysis predicted.
`git -C reference/g2flash diff` shows the change; `git checkout` reverts it. It touches no
firmware byte, so image provenance is unaffected. **Report upstream to Babcock.**

**The image.** Staged at `fws/2.2.6.10-cfw-d4054ab1/g2-cfw-a5d1c31.bin` (with its patch JSON
beside it), rebuilt from our local stock base by `apply_patches.py` and hash-matched to
`d4054ab1…`. `verify_cfw.py` exit 0. ⚠ **It reports firmware version `2.2.6.10`, not `2.2.6.11`** —
the `.11` in the archived image was SybilSight's three version-string patches, which g2flash's own
build does not carry. So after the install the version string is indistinguishable from stock
2.2.6.10; **CFW detection must go through the `EVENCFW/` capability string**, which is what
Damage's gate already does. Do not read the version and conclude the flash failed.

**Arm addresses (Adam's own pair, serial 32), found by a passive scan:**
`left = D8:AE:E7:C1:FA:4D`, `right = E4:87:77:65:CD:50`, both `public`, RSSI −62 / −67 dBm.

**The staircase, all three rungs, both arms:**

| rung | result |
|---|---|
| `--stop-before heartbeat` | `discovery: ok` on both. Zero writes |
| `--stop-before file_check` | `authentication: ok` on both; reply `pb=080410011a00` — exactly the strict `08 04 10 <magic>` + `1a 00` the tool demands |
| `--stop-before flash` | `begin ack 0 (SUCCESS)` and `FILE_CHECK acked` for component 0 on both. Zero firmware bytes |

🔴 **Two risks from §10.5 are now CLOSED, on hardware:**

1. **The unbonded-host question is answered.** beardos has never bonded with this pair, and it
   connected, discovered, wrote to the CTRL characteristic and got a valid authenticated reply
   from both arms. No OS pairing prompt, no encryption demand, no ATT insufficient-authentication
   error. The commit message's predicted pairing prompts did not appear.
2. **The auth exchange works on stock 2.2.2.20** — previously inferred from captures, now measured.

**Operational notes from the runs.** The typed-phrase prompt fires **before** the transport is
created, so `--stop-before` does not exempt it; the dry runs used `--my-warranty-is-void` and every
command run by the assistant carried a `--stop-before`, so none of them could write. The write
itself was handed to Adam to run, keeping the typed phrase as a real human gate. Harmless
`[BLE disconnected unexpectedly]` lines appear as the previous lens's client closes; discovery
succeeded straight after both times.

**What is still unknown** is only the data phase itself: ~4.2 MB per lens over a link whose OTA
throughput we have never measured (our 7–13 KB/s is the EvenHub image path, not c0/c1). If a bare
block-ack timeout occurs, the patched recovery path now has a deterministic magic. `main` catches
per lens and continues to the other, then prints `FAILED lenses: [...]`; re-run with `--lens <side>`.

### 10.11 ✅ THE INSTALL IS DONE — 2026-08-30

Adam gave the word to run it. Both lenses, all six components, **exit 0, zero block resends
anywhere**, no retries, no reconnects, no failure markers in the log
(`scratchpad/flash.log`). **The glasses are no longer on stock 2.2.2.20.** That is the
irreversible step, and it is behind us.

| component | bytes | left | right | resends |
|---|---:|---:|---:|---:|
| `firmware/codec.bin` | 326,092 | 14.9 s | 15.1 s | 0 |
| `firmware/ble_em9305.bin` | 211,948 | 9.4 s | 9.5 s | 0 |
| `firmware/touch.bin` | 34,464 | 1.5 s | 1.5 s | 0 |
| `firmware/box.bin` | 55,784 | 2.5 s | 2.5 s | 0 |
| `ota/s200_bootloader.bin` | 148,599 | 6.6 s | 6.6 s | 0 |
| `ota/s200_firmware_ota.bin` | 3,562,570 | 134.9 s | 134.3 s | 0 (870 blocks) |
| **total** | **4,339,457** | **171 s** | **171 s** | **0** |

Every `END verify` returned **status 8 (UPDATING)**, which is in the tool's `END_OK = {0, 8, 9}` —
a clean pass, not a warning. Both lenses agreed to within a second on every component.

**Post-install liveness, immediately after:** a passive scan found both arms advertising again —
`Even G2_32_L_C1FA4D` at −61 dBm and `Even G2_32_R_65CD50` at −59 dBm, *better* than the −62/−67
before the flash. They rebooted. `desktop --selfcheck` still ALL CHECKS PASS; `--ble-info` now
lists both arms (and the R1 ring) as known to BlueZ, all disconnected.

**The patched recovery path never fired** — zero timeouts meant `recover_session()` was never
entered. The patch was still the right call; it just did not get tested by this run.

**New measured number** — see `overview.md` §5.1: ~25 KB/s effective goodput on the OTA path,
PC-direct. Recorded there with the caveats that keep it from being misread as a correction to the
7–13 KB/s EvenHub-path figure.

### 10.12 What is still ahead

The install is done; **first light is not**. Nothing has driven the display yet. Next is
`REMINDER.md`'s runbook from step 5 and the first-light checklist, and the very first thing to
confirm is that the capability gate sees `EVENCFW/16` — **not** the version string, which reads
`2.2.6.10` on this build and is indistinguishable from stock.

### 10.13 ✅ The R1 ring updated too — 2026-08-30

Adam's call, and the evidence supported it: R1 release **2.2.4.0003**'s own note reads *"Update both
Even G2 and R1 to 2.2.4 to prevent Bluetooth issues."* His ring was on **2.2.0.0014**, below that
line. Before today the glasses were on 2.2.2.20, also below it, so the pair was consistently
un-updated and worked. Moving the glasses to a 2.2.6.10 base put the pair **straddling that line
for the first time** — untested by anyone. That is what justified acting rather than waiting.

**Target: 2.2.6.0009**, not the newest. The glasses run a 2.2.6.10 base and 2.2.6.0009 is the ring
firmware Even shipped alongside that generation; going to 2.2.8.0002 (or the 2.2.9.0003 SybilSight
pins, which we do not hold) would only re-create a mismatch pointing the other way.

**No declared dependency exists either way.** Every R1 package declares exactly one requirement,
`minAppVersion` — the **Even phone app**, not glasses firmware. Hardware version 52 and SoftDevice
id 256 are constant across all eleven archived releases, and `applicationVersion` is **3 on every
one of them**, so there is no rollback counter: a downgrade back to 2.2.0.0014 should be accepted,
and we hold that image exactly. The ring was never in the irreversible position the glasses were.

**Result:** 646,408 B in 158 objects, **zero retries, zero CRC mismatches**, exit 0. The ring
rebooted into its application and re-advertises as `EVEN R1_35F0B8` on its original address —
which is itself the confirmation: an invalid image would have left the bootloader advertising
`B210_DFU_35F0B9` instead of jumping to the app.

**The tool is ours** — `research/r1_dfu.py`, our own implementation of standard Nordic Secure DFU.
The R1-specific facts (buttonless entry on `8ec90003`, PRN = 12, the 400 ms first-object settle,
the bounded object rewrite) were read from SybilSight's `r1Dfu.js`, which is **MIT**, so the
GPL clean-room rule does not apply here — but no code was copied regardless. It has three stages:
`verify` (offline hashes only), `probe` (connect, confirm the DFU service and buttonless
characteristic, write nothing) and `flash`.

**Two things worth keeping from the run:**

- 🔴 **The bootloader names itself `B210_DFU_<suffix+1>`, not `R1 DFU_…`,** and sits at
  **MAC + 1** (`DB:D9:68:35:F0:B9`). Match it by service UUID `0000fe59` plus a `DFU` substring;
  a fixed-name match would fail.
- **A defect caught before it could bite:** bleak 3.0.2 has **no `set_disconnected_callback`** — it
  is a constructor argument. The first draft called it on the line immediately *after* writing the
  enter-bootloader byte, so it would have failed with the ring already rebooting and the tool gone.
  Verify the library surface before a write, not after.

**⚠ A correction to something said during the run.** The ring was reported as having "dropped to
−83 dBm from −62". That −62 was the **left lens**, not the ring; the ring's application-mode RSSI
was never measured before the flash, so there is no baseline and nothing dropped. It reads −83 both
as a bootloader and as an application, which is simply where it is.

**Not yet verified:** the running version string. The ring's own link (GATT `0x0015`/`0x0017`, a
separate non-AA protocol, only partially decoded in `G2CC/docs/G2_BLE_PROTOCOL.md` §11) carries it,
and we have not implemented that query. The bootloader accepting a *signed* 2.2.6.0009 init packet
and then jumping to the application is strong evidence, but it is not a version read.

---

## 11. FIRST LIGHT — 2026-08-30

**The PC drove the glasses.** Scan, both arms, MTU 247, prelude acked, capability gate passed,
carrier created, leases held, warmup dropped as expected, `driving via ble`, and the shell painted
Main with its chrome. Then the ring drove it. Everything below happened within about an hour of
the flash finishing.

### 11.1 🎉 The measurement that matters — CFW ack latency ≈ 50 ms

The shell's own `ackMsEma` cell read a steady **50 ms** while driving PC-direct, against the
**176 ms** measured on stock 2.2.2 that priced *every estimate in the project*. Roughly **3.5×
better than the design assumed**, and in the direction that makes everything cheaper.

⚠ **Recorded, not yet acted on.** It is an EMA over a *mostly idle shell* — clock ticks and small
chrome cells, the light-payload end. Latency may grow with payload and nobody has measured it under
a full keyframe or a heavy scroll. It establishes the **floor**, not the **curve**. `overview.md`
§11 item 1 and `CLAIMS.md` both carry that caveat; do not re-price §5 until it is measured under
real content.

### 11.2 Items closed on hardware

| # | item | result |
|---|---|---|
| 6 | CFW ack latency | **~50 ms** (see 11.1) |
| 12 | link-death behaviour | closed by accident: two unplanned LEFT drops, keeper restarted and resumed both times unaided |
| 16 | PC-direct BLE over BlueZ | **works** — scan by name, RIGHT then LEFT, MTU 247, notifications, write pacing. First exercise of `BlueZDbus`, passed first try |
| 13 | stray sid-0x0x frames | the glasses **do** send unsolicited sid-0x01 frames (codes 1000 / 2000). Logged and ignored exactly as designed |
| 18 | the switcher chord | **partly, and it needs re-thinking** — see 11.4 |
| — | ring → glasses → `e0-01` | **works.** `SCROLL_UP`, `SCROLL_DOWN`, `TAP`, `DOUBLE_TAP` all arrive; taps carry a real source byte (2 = ring). This hop is invisible to every capture we own and had never been observed by anyone here |

### 11.3 🔴 Three defects, all ours, all found in minutes

1. **The ack status enum.** `ImgResCmd` field 8 is a **status**, not an error code: its success
   value differs per operation (0 create, **4 image raw data**, 6 rebuild, 8 text, 10 shutdown,
   12 heartbeat, 13 audio). The transport read "non-zero means error" and so refused the glasses'
   own success ack, failing every session start against a perfect link. **Nothing offline could
   catch it: the simulator modeled success as an ABSENT field 8.** Fixed both sides; `AckStatusTest`
   pins them together.
2. **The journal write pileup.** A keeper restart wrote into the stream a previous `stop()` had closed,
   failing and logging *every line* — burying the log at exactly the moment it existed to explain.
   It now reopens in append mode (journalling across a reconnect is when it is most useful) and an
   unrecoverable failure is reported **once**. Repeating an unrecoverable error per line is not
   "loud and proud"; it is a denial of service against reading the errors that matter.
3. **Input was unobservable.** No inbound gesture was ever logged, so when input did not work there
   was no way to distinguish "arriving and mishandled" from "never arriving" — two completely
   different causes. Every gesture now logs by name. **An input path you cannot observe is a silent
   failure even when every component in it is loud.**

### 11.4 🔴 Event 10 is "a touch ended", not "a long press ended"

`LONG_PRESS_RELEASE` (event 10) fires after **almost every swipe**, interleaved with the scroll
stream. Event **9 never fired once** in a whole session of normal use.

This does not break the current grammar — §1.3's chord has event **9** arm the window and event 10
merely refresh it, so stray 10s cannot arm anything. But the chord was designed believing 10 meant
what its name says, and it does not. **Re-validate the chord against this behaviour before anyone
builds on it**, and treat the name in `gesture_fwd.c` as describing the hook site, not the
semantics. First-light item 18 stays open with this note attached.

### 11.5 The ring — cause, effect, and the lesson

After the ring DFU the ring **stopped driving the glasses entirely**, including the stock offline
menu, which ruled Damage out as the cause. The glasses fired a "Ring Disconnected" notification
with a beep; the ring sat advertising, connected to nothing. **Those notifications also took the
display** — Adam saw both lenses go dark, then the notice, then the display return, which matches
our two LEFT disconnects at flush 126 and 145 exactly. One fault, two symptoms.

**Cause: the DFU almost certainly cleared the ring's bond with the glasses.** A charger power-cycle
did nothing. **Opening the Even app fixed it** — the vendor path re-registers the ring (the `91-20`
message that tells the glasses the ring's MAC), and input has worked since.

🔴 **The lesson, recorded because the risk analysis missed it.** The ring update was defensible on
the evidence and the firmware was reversible — we hold 2.2.0.0014 and there is no rollback counter.
**But "reversible firmware" is not "reversible outcome": the bond is separate state, and reverting
the image would not have restored it.** Any future DFU on a *paired* device must count re-pairing
as part of the cost, and must confirm a re-pairing path exists before starting.

Also worth keeping: **the Even app did not revert the CFW.** We reached `driving via ble` straight
afterwards, so the capability gate still saw `EVENCFW`. That was a real risk and it did not bite —
but it was luck rather than design, and Faceclaw remains the safer app for this pair.

### 11.6 Smaller observations

- `le-connection-abort-by-local` on the RIGHT arm when the Even app still held it. The keeper got
  in on attempt 2. Expect it whenever another central has the pair.
- The status bar reads correctly on glass: `idle` → `ok` → `NK/s · 50ms` → compass placeholder →
  link indicator at 4 bars (connected + lease held). G/R/P battery cells are blank for want of data.
- Main showed **"library loading"** and may never have finished; whether the content host delivered
  the book list is unconfirmed and is the next thing to check.

### 11.7 Still open

Legibility on real glass (item 8), the safe area (item 1), per-notch scroll (item 2), comfortable
disparity (item 3), the rect budget (item 4), the two-arm capture (item 5), msgId-255 under CFW
(item 7), and the texture cache on glass (items 19–20). **None of the display-quality questions
have been answered** — the session proved the pipe works, not that the design reads well.

---

## 12. The refinement batch — 2026-08-31, same-day live deploys

Adam wore the glasses while the whole `REFINEMENT.md` queue was built, verified and deployed in
two batches (plus a third for the clock revision), each behind a full green battery. The desktop
drove the pair PC-direct throughout; he used every change within minutes of it landing.

**What shipped** (details in `REFINEMENT.md`'s status block and the revised `DESIGN.md` sections):

- **§9 first: the desktop preview at 4×** (integer nearest-neighbour, `-`/`=`, screen-clamped).
- **§1 chrome depth:** bars inset to the content extent (x 16–624 — a full-width rect cannot
  stereo-shift) and pushed to plane −2 at `min(d+4, 16)`. `DESIGN.md` §2.2/§2.3/§3.1 revised;
  the §2.3 table, `GeometryTest`, `render_shots.py` and the lint gate all moved together.
- **§3b scroll:** `DocView.stepLines/accel` + a shell-side direction-gated ramp (notches ≤250 ms
  apart multiply the step to 6×; 250–500 ms holds; a pause or reversal resets). Reader actions:
  "Scroll step" (1–8, default 3) and "Scroll acceleration" (default on). Lists stay 1/notch.
- **§3a folders:** `BookMeta.folder` (additive, old peers/caches decode), folder rows + counts,
  descend on tap / ascend on back, per-folder cursor rest, state persisted.
- **§2 per-app height:** `DamageWindow.preferredHeight`, applied on focus COMMIT (never a
  switcher preview — §4.3 rule 1), via the same size-change path as Settings (`syncLayout`).
  Reader prefers the full panel, with a "Height" action row to hand back to the global setting.
- **§5 clock:** moved top-left + analog redraw — then **reversed by Adam the same session**: now
  top-RIGHT flush, **digital**, quality rendering (the batch-3 change). Analog kept unused.
- **§6 measured** — `overview.md` §5.2: from 1,488 journalled flushes, `ms ≈ 60 + bytes/50`
  (floor median 60 ms, min 33; transfer ~50–75 KB/s; dense full-frame 2–4 fps). The stock-path
  formula stays for the stock path only.

**Also recorded:** Main-row icons are "very basic" per Adam and want an eventual quality pass
(future backlog). The §4 switcher/event-9 temple experiment was still pending when this section
was written — one temple hold while a session runs, watch the log for event 9 vs 10.

**A fourth hardware-found defect, found and fixed during the deploys.** Three successive session
starts parked at the **capability gate**: the settings READ was sent once and waited forever,
and a query that lands while the firmware is still settling a *previous* session's context (its
`SYSTEM_EXIT` event and sid-0x01 status chatter, codes 1016/2006, arrive right then) is simply
never answered. The first two restarts of the day survived by timing luck; the next three did
not — and a parked gate holds the links, so the lease from the dead session expires and stock
repaints while the new session waits on a question the firmware never heard. **Fix:** the gate
now RE-ASKS on a 2 s pacing tick until an answer or the sweep arrives (`CfwTransportBase`,
`CAPABILITY_REASK_MS`) — pacing like the keeper's 250 ms poll, not a timeout; the gate still
never gives up on its own. Verified live: the very next start logged one
`capability query unanswered after 2000 ms — asking again` and walked straight through to
`driving via ble`. ⚠ No offline regression test pins this yet — the simulator always answers the
first query, so the eaten-query case needs a fake link that drops it; noted as follow-up. The
deeper cause (sessions ending with no FB_RELEASE / mode-11 cleanup, leaving the firmware a
context to tear down at our next arrival) is the standing argument for adopting **mode 11 in
`stop()`**, which `IMPLEMENTATION.md` holds for a deliberate build.

**The same class, one gate further down, later the same day:** with the capability re-ask in
place, the next start parked at the **carrier CREATE** — its query got through first try, and
the CREATE's ack was the message the teardown chatter ate instead. Whichever single-send await
is in flight when the firmware resets its context is the one that parks. The CREATE now re-sends
on the same 2 s pacing tick (safe pre-warmup: a duplicate CREATE recreates the empty carrier,
and the sacrificial warmup follows the last one either way; the same deferred rides every send).
The prelude has survived 8/8 starts and the lease/warmup awaits complete on write/ack paths that
kept working — left alone deliberately; if either ever shows the same park, same treatment.
**Also recorded:** the settings-by-category ask (Global + one per app) and the scroll verdict
(ramp too uneven on glass → default 5 lines/notch, acceleration off, both configurable) landed
in this same batch; the event-9 experiment result lives in `REFINEMENT.md` §4.

**Defect #6 — the biggest of the day: the switcher was unreachable because of OUR §1 source
filter.** Events 9/10 arrive with `EventSource` ABSENT (source 0) by firmware design — the fact
was documented in `CLAUDE.md` all along ("a long-press is UNATTRIBUTED") — and
`Shell.handleInput`'s ring-only check (source ≠ 2 → drop) discarded every real long-press before
the grammar ran. Both switcher routes were dead since first light. **`LongPressTest` passed the
entire time because `postGesture` defaults to the ring source** — the harness fed the grammar an
attribution the wire never carries. Fixed: events 9/10 skip the source check (§1.2's
bare-long-press no-op is what keeps the temple harmless, exactly as the rule said); the suite
now injects 9/10 with source 0; a bare release no longer overwrites the input echo (it follows
almost every swipe). Lesson, same family as the ack-status enum: **a test default that
"helpfully" supplies what the wire omits is a model erring permissive — inject what the firmware
actually sends, byte for byte.**

**Same batch, from Adam's next round of use:** Size is now four TOP-aligned heights
(288/352/416/480; vpos retired — his fit occludes the bottom, never the top), per-app shadows of
global settings default to **"global"** with explicit overrides (Reader's Size row is the first),
and the Settings categories became **directories** (tap in, double-tap out) after inline headers
proved too slow to scroll past.

**The Reader batch (REFINEMENT.md §11), same day:** descenders fixed at the root (the 24 px line
box could not hold the normalised 20 px Alegreya's 28 rows; box → 30 px, metrics-derived
baseline, a loud fit guard — face and size untouched), Reset progress (Settings → Reader, via
the now-supplier `HostSetting.options`), the first-open **chapter picker** (chapters = spine
boundaries titled from the book's own nav; row 0 = "From the beginning"; **double-tap always
backsteps** — Adam's explicit caveat), a "Chapters" action row, and **ebook images rendered in
place** (token paragraphs + an `ImageDecoder` seam (AWT/BitmapFactory), box-sampled to the
column, 16-level quantized, no dithering, whole-line strips riding all existing machinery;
404 images on the real shelf, 380 decode, covers render through the full pipeline).
`EpubChaptersImagesTest` + the extended `--epub-check` pin it; the snapshot harness now walks
folders and the picker.

**And the last two dead ends of the day, both "wired to nothing" (REFINEMENT.md §10):**
brightness now transmits (faceclaw's exercised sid-0x09 write; pushed per Settings step, per
session start, and across the seam) and the glasses-battery cell fills (the BARE device-info
READ G2CC live-confirmed — ⚠ the f4-sub-request form returns NO device-info block on the real
CFW — polled 5 s + 60 s, plus unsolicited 09-01 updates; f4.12/13 → `TransportEvent.Battery`).
Verified on glass the same hour: brightness lands, **glasses 79 %** read from the real pair.
Ring relay decode is wired but passive (whether the glasses push RingRawData is an open probe);
phone battery remains phone-path-only. `BatteryBrightnessTest` pins both round trips through the
sim, which now models f4.12/13 and the brightness write.

---

## 13. NEXT MISSION: perfect the APK (written 2026-08-31 for a fresh context)

**Adam's ask, verbatim:** *"Make sure it can do everything the PC system can do, including
connecting to the PC system and using both, as well as falling back to phone-only and PC-only,
just like in the design."*

The design it must satisfy is **`DESIGN.md` §10** (three roles, four configurations) and the
**arbitration contract in §8.1 decision 3+5** (his words, quoted there): phone app + home PC is
the default; lose the PC → phone alone; no phone → PC-direct; everything reconnects aggressively
with no timeouts, and the PC is always preferred when reachable. The seam, keeper, arbitration,
claim/yield and replica plumbing for ALL of this **already exist and are sim/fake-verified**
(§8) — what has never happened is any of it running on the REAL phone against the REAL glasses.

### 13.1 Where the phone stands (verified in-repo 2026-08-31)

- **`:core` is shared**, so the APK already contains today's entire refinement wave: the
  switcher source-0 fix, sizes/directories, folders/chapters/images (with `AndroidImages` —
  BitmapFactory, compiles, never run), scroll settings, brightness pushes, battery events, the
  capability/CREATE re-asks. Nothing phone-side re-implements shell logic.
- **`BleTransport` (Nordic glue, §8.2 "Phone") has NEVER run on hardware.** Written from G2CC's
  proven driver + the reference connect sequence; unit-verified over the firmware model only.
  The PC-direct BlueZ glue passed its own first light untouched — the protocol brain
  (`CfwTransportBase`) is shared and hardware-proven; only this glue layer is unproven.
- **The seam server** (`:7402`) and the phone's replica page (`:7403`) exist; the PC's `auto`
  mode already races `remote:<phone>` first. End-to-end on hardware: never.
- `ShellService` sets `phoneBattery` (line ~215) — the P cell works on the phone path.
- Config: `Prefs` with `BuildConfig` defaults from `damage-secrets.properties`
  (`SERVER_HOST`/`DAMAGE_TOKEN`/ports). Target persists (sim default); capability gate refuses
  stock firmware loudly and falls back to SIM with a persistent notification.
- Manifest carries `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` (S+) and
  `FOREGROUND_SERVICE_CONNECTED_DEVICE`. ⚠ Verify at first run that the RUNTIME grants are
  actually requested/held on the Pixel 10a — a missing grant surfaces as scan silence.
- `versionCode 2 / versionName 0.2` — **bump BOTH on every build Adam installs.**

### 13.2 The order of work

1. **Bump version, build, sideload** (`phone/build/outputs/apk/debug/phone-debug.apk`), confirm
   the SIM target still runs on-device with today's core (Reader images/chapters on screen —
   BitmapFactory's first real exercise; the categorized Settings; the digital clock).
2. **Phone-target first light.** ⚠ **Stop the desktop session first** (`pkill -f
   wm.damage.desktop`) — one central at a time; the pair cannot serve two. Flip Target →
   glasses, watch the status line walk scanning → connecting → prelude → capability → carrier →
   lease → warmup → driving. The §12 lessons likely to matter here: the start-gate re-asks (the
   firmware eats requests during its previous-session teardown), source-0 events 9/10, the ack
   STATUS enum. Journal + logcat are the witnesses; the phone Log sink raises urgent
   notifications on errors.
3. **app + home PC** (the default config): with the phone driving, start the desktop in `auto`;
   it should claim the phone's transport over the seam, the phone shell yields, the PC drives
   THROUGH the phone, both replicas stay correct. Then stop the desktop: the phone resumes by
   itself (keeper `pause`/`resume`). Then bring the PC back: it claims again. Every transition
   narrated in both status lines.
4. **app alone:** PC unreachable (stop the desktop + content host) — the phone falls back to
   cached listing + cached books; the link cell says "PC gone/Nm"; reading keeps working
   (copy-on-open cache). Verify staleness is SAID, not hidden (§10.5).
5. **PC-only** already works daily — re-verify it still wins the race when the phone app is down.
6. **Parity audit against the PC feature set**, fixing gaps as found: brightness push from the
   phone path, battery cells (G from the wire, P from the phone, R passive), Settings
   directories incl. host Target row, Reader end-to-end (folders/chapters/images/reset/heights),
   silent clock, switcher both routes, notifications (§9.3 urgent + the §4.5 sources are
   phone-side by design — SMS/Music integration is APP-LAYER, not this mission), replica page
   served BY the phone, input from LensView touch + the browser page while the phone drives.
7. **The §10 transitions under adversity:** phone BT toggle mid-session (the documented at-work
   recovery), walking out of BLE range and back, PC reachable but its shell stopped.

### 13.3 Constraints and traps for this mission

- **One central at a time.** The desktop session usually holds the pair; every phone-radio test
  starts by stopping it deliberately, and says so. `le-connection-abort-by-local` on connect
  means another central still holds an arm (§11.6).
- The keeper treats ONLY a typed `CapabilityRefused` as terminal; everything else retries
  forever. If the phone parks mid-start, the §12 eaten-gate class is the first suspect — the
  re-asks should already ride through it; if a THIRD gate parks (lease write, warmup ack), give
  it the same paced re-ask treatment, matching the two precedents in `CfwTransportBase`.
- Deploys while Adam wears the glasses are routine: stop, relaunch, the lease fails open to
  stock for the gap and the session repaints. Narrate what he will see.
- **No G2CC edits** (read from it freely), **no reference/ code** (protocol facts only), plain
  engineering wording everywhere, measured-vs-modeled marked, links/paths LAST in messages.
- After ANY code change: the full battery (`CLAUDE.md` — 121 core / 9 desktop / selfcheck 32 /
  snapshots eyeballed / epub-check / lint 0 / APK), plus on-device checks for phone changes.

### 13.3b Progress (2026-08-31, later): the pre-radio hardening is DONE and the APK is staged

Adam re-stated the mission's shape in his own words — *"Most of my usage of the G2 will be PC to
Phone then Phone to G2. That should be the default path, with the Phone APK driving the glasses
and supplemented by the PC. If the PC connection drops, the phone should gracefully fall back to
running the glasses on its own."* — which is §10.1 row 1 + the §8.1 contract, already the built
arbitration. What was added before any phone-radio test, mined from the G2CC Android app (the
heavily-tested reference) and the seam's failure model (`IMPLEMENTATION.md` → "The APK-mission
prep" for the details):

1. **The seam heartbeat** — a silent path death of the PC link (the Tailscale case his default
   usage lives on) now hands the glasses back to the phone shell in ~20 s, not TCP
   retransmission's minutes. Additive `ping` control, enforced only against a peer that has
   spoken it (skew-safe), 3 new tests (`SeamLivenessTest`).
2. **The pocket-liveness trio** on the phone — PARTIAL_WAKE_LOCK for GLASSES stacks, the
   battery-exemption ask, boot/update auto-start (Target=GLASSES + exemption-gated). Doze was
   the one thing that could stall the 45 s lease renewals in a pocket.
3. **Scan hardening** — BT-off-mid-scan fails loudly (the at-work BT-toggle recovery would have
   stranded the old scan forever), and a long hunt re-issues under Android's 30-min downgrade.
4. **Distribution** — `./gradlew :phone:stageApk` → `~/.damage/damage-wm.apk`; the G2CC `/setup`
   page (his ask: same page as the G2CC APK) grew a DamageWM box + `/damage-apk` endpoint,
   deployed and verified serving byte-identical bytes. **APK now 3 / 0.3.**

Battery green after all of it: core **124**, desktop 9, selfcheck 32, snapshots eyeballed,
epub-check 380/404 images, lint 0, APK built. New first-run grants on the phone: the two
Bluetooth prompts + notifications as before, **plus the battery-exemption dialog (asked once)**.
The §13.2 runbook is unchanged and still the next step — nothing here has met the radio.

### 13.4 Resume protocol (fresh context)

1. Read this §13, then §12, then `IMPLEMENTATION.md` (the wave summary + "Review hardening"),
   then `CLAUDE.md`'s rules. Memory's index points here.
2. `git log --oneline -5` — everything through the doc sweep is committed and pushed
   (`c48eb60`); the tree should be clean.
3. The desktop session may still be RUNNING and driving the glasses (check
   `ps aux | grep wm.damage.desktop`; its log is in the session scratchpad, but a fresh context
   just checks the process). Leave it as the daily driver until step 2 of §13.2 needs the radio.
4. Ask Adam to sideload when the APK is ready; he flips the Target himself. The phone's own
   radio work needs no extra permission — the flash-era no-radio rule is long retired.

## 14. Tmux on glass — designed, refined and built in one day (2026-08-31) ⚠ the GRID here is SUPERSEDED by §18 (flow view)

Adam asked for the Tmux window ("look at the G2CC implementation carefully, but don't copy it
verbatim… a much better implementation"), answered every up-front question in one refinery pass
(the verdicts are pinned at the top of `TMUX.md`), and the whole thing was built the same day —
scope INCLUDING typed text via the replicas, multi-host over ssh, and session management;
excluding search (wants typing first) and the mic (much later).

- **`TMUX.md`** — the design: the G2CC study and constraint archaeology, the Damage-native
  grammar (scroll-up IS scrollback), costs against the measured curve, the explosion table,
  the locked verdicts.
- **`IMPLEMENTATION.md` → "Tmux"** — what runs and where: the SGR grid core, the provider
  triple on the content port, the window, the shell/transport seams (first canvas window,
  typed text end to end), the harness scenes, and the on-glass items only hardware can answer.
- Battery green at HEAD: core **140** · desktop 9 · selfcheck **48** · snapshots (09b–09e are
  the tmux scenes) · epub-check · lint 0 · **APK 4/0.4 staged** on the /setup page.
- ⚠ Two latent defects surfaced and fixed on the way: the snapshot harness's walk back to Main
  silently broke when the shelf grew folders (its waits are labeled now), and a seam test was
  sampling `started` at the one instant the documented a3-8 state-frame race can flip it.
- **The §13.2 phone-radio runbook is UNCHANGED and still next** — nothing here touched the
  radio. When the phone drives, Tmux needs the desktop (or `--host-only`) up on beardos: the
  provider rides the content host. PC-shell modes exec tmux directly.

## 15. The daily driver prepped (2026-08-31) — `DAILY.md`

Adam: *"prep whatever needs to be prepped for me to run the G2 all day from my phone, powered
by this PC, using the APK, the same way I do with G2CC."* That is §10.1 row 1 under the §8.1
arbitration, and it now has an ops shape: `--no-preview` headless mode, the staged
`~/.damage/damage.jar` (`:desktop:stageJar` — a broken tree never changes the daily driver),
and the OpenRC service **`damage`** (supervise-daemon, enabled at `default`, installed and
deliberately LEFT STOPPED until the phone's §13.2 first light — the one-central rule).
`DAILY.md` holds the moving parts, the ordered one-time setup (including 🔴 *disconnect the
G2CC bridge app* — it boots itself and would fight for the pair), and the ops crib. Two
defects found by the prep's own smoke run, both fixed: the tmux provider's `polling` field was
declared after the init block that races it, and a down ssh host's 5 s connect refusal used to
delay every other host's status serially (polls are parallel + skip-if-in-flight now; the
slappy default was withdrawn — hosts are opted in when actually alive). Battery green: core
140 · desktop 9 · selfcheck 48 · lint 0. The §13.2 hardware pass remains the open step and
DAILY.md step 3 is exactly it.

## 16. The session outlives the driver (2026-08-31, evening) — zero-blink handovers

Adam's first day out measured the gap: a WiFi→LTE edge cost TWO visible session teardowns
(seam heartbeat caught the silent path death at 21 s; the phone tore the glasses session to
resume; the PC tore it again to re-claim — "link reset: page and prelude state cleared" in the
log). His verdict: *"the whole point of this hybrid adaptive system is to stay connected"*,
with G2CC's bridge as the reference — and G2CC's ConnectionManager/Service make the reason
explicit: **the BLE session's lifetime was decoupled from the server link's** (five reconnect
defences on the WS; a drop froze the display, never tore the session).

Damage now has the same decoupling, built and deployed the same evening:

- `Shell.stop(stopTransport=false)` is the YIELD form; `ShellKeeper.pause` uses it — the
  transport session (lease renewal included, transport-owned) runs on with no driver.
- `Shell.start()` ADOPTS a live session: skips the whole choreography, marks nothing torn,
  rebaselines the panels with the existing composeFullSurface + requestKeyframe — one wide
  flush (~100–200 ms) instead of capability→CREATE→lease→warmup.
- The seam server answers a claim of a live session with an adopt-grant ("started" at once),
  treats a driver's "stop" as a CLAIM RELEASE, and never stops the owner's session on driver
  loss — only a fresh session still mid-start is aborted through its own rollback. The phone's
  stack teardown stops its transport explicitly (the owner's right, and now its duty).
- Mixed versions degrade to the old behaviour exactly (an old phone still tears on yield), so
  the rollout is additive; full zero-blink needs both ends ≥ 0.6.
- `HandoverTest` ×4: claim→release→re-claim, silent driver death, keeper pause/resume, and the
  full WiFi-edge loop — `glass.preludeAcks == 1` throughout is the pinned invariant, with the
  lease held across every transition. Battery: core **144** · desktop 9 · selfcheck 48 ·
  snapshots · lint 0. Also this evening: tmux quick keys grew Left/Right (his ask); the tmux
  grid went fractional-pitch full-width with history-through-the-live-fit (§15's on-glass
  fixes). APK **6/0.6** staged; the PC service runs the rework now.

## 17. User typography + per-app depth, and the CURRENT RESUME POINT (2026-08-31, late)

**The last feature of launch day** (Adam: global font/size/style previewed in each option's own
font, per-app font/size/style, per-app depth against global-depth chrome): built as ONE
mechanism — `core/text/Style.kt`'s `StyleTransform` rewriting every `FontSpec` at the
rasterizer seam. Chrome + Main draw through the shell's global transform (a recorded REVERSAL
of §Type's fixed-system-face lock — see DESIGN.md §0/§Type/§3.1 amendments); each window draws
through its per-app transform (`DamageWindow.styleTransform` → `styledText()`; Reader, Tmux and
TermRender are routed). `HostSetting.optionFont` + `FontSpec.raw` carry the preview-in-its-own-
face behaviour; adapter-level content scaling is retired (the transforms own scale now —
double-scaling was the hazard). Per-app depth (default 8) drives the focused app's content
plane in `updatePlanes`; bars + Main stay on the global depth. Defaults verified
render-neutral (snapshot pixel-compare below the clock). `StyleTest` ×5. Also: `stageJar` now
stages via temp + ATOMIC move (an in-place truncation broke the running service's lazy class
loads — six NoClassDefFoundError shutdowns in the log were that, not a code defect).

### 17.1 RESUME PROTOCOL for a fresh context (supersedes §13.4)

1. Read `CLAUDE.md` (status header is current), then this §17 back through §13 for the day's
   arc: §13 the APK mission (runbook DONE) · §14 Tmux · §15 the daily driver + on-glass tmux
   fixes · §16 the session-outlives-the-driver handovers · §17 typography/depth. `DAILY.md` is
   the ops crib; `TMUX.md` the tmux design; `IMPLEMENTATION.md` what runs.
2. **The state of the world:** the OpenRC service `damage` runs the PC shell headless in auto
   and is probably DRIVING through the phone right now (`rc-service damage status`, log
   `~/.damage/damage.log`). The phone runs the APK with Target=glasses and owns the radio.
   Deploys: `./gradlew :desktop:stageJar && sudo rc-service damage restart` (adopt makes it
   blink-free once the phone APK ≥ 0.6). **Stop the service before any `:desktop:run`.**
3. **Versions:** APK staged = 7 / 0.7 (setup page `/damage-apk`); Adam last INSTALLED 0.4 —
   confirm what his phone runs before reasoning about mixed-version behaviour (old phones tear
   on handover instead of adopting; everything still recovers).
4. Battery at HEAD, all green: core **150** · desktop 9 · selfcheck **48** · snapshots (15
   scenes; the clock/telemetry differ run-to-run, bodies are stable) · epub-check · lint 0 ·
   APK builds. Run the whole battery after ANY change (`CLAUDE.md`).
5. **Open on-glass items:** his verdicts on the evening wave (tmux full-width grid + grid
   history + arrows, fonts-in-their-own-face, per-app depth); the left-lens seam residue
   (one-shot early-burst tear — if it recurs after a handover, harden session start with a
   post-adopt seam re-black); the ~20 s silent-death freeze (tighten `SEAM_PING_MS`/
   `SEAM_QUIET_MS` if he asks). Then the app-layer scope explosion (`CAPABILITIES.md`,
   `DESIGN.md` §0/§4.6) and the icon-quality pass.
6. G2CC coexistence: its SERVER runs (music etc., :7300, serves our APK page); its ANDROID
   bridge stays Disconnected (second central). The G2CC repo carries someone's in-flight FF1
   work — never sweep it into a commit (our two setup-page files went in as dbbab11).

### 17.2 Two live tmux defects from Adam's evening use, fixed the same hour

1. **Garbage on/below the status bar after scrolling history to the bottom**: the bottom row's
   glyph AA bled 1–2 px past `rect.bottom` onto the divider — pixels nothing owns, shipped by
   the next chrome repaint and then lingering. Both render paths now carry a hard bottom-row
   guard (a partial bottom row is dropped, never bled).
2. **Tmux "frozen — can't scroll, switching still works"**: a FAILED history capture (a
   connectivity blip) stranded `histLoading=true` forever, and every scroll early-returns on
   that flag. The failure path now clears it and falls back to LIVE with the notice; a
   regression test pins it. Related: a (re)subscriber now always gets its first frame within a
   tick — the push-on-change cache is cleared per subscribe, so a re-activated window can't sit
   on "capturing…" until the pane happens to change.

## 18. The tmux grid is retired — the FLOW view (2026-08-31, night) + the review build

Two moves after §17, in order:

**The deep review + fix pass** (`67a1d41`): a full-codebase review found and fixed four
verified defects — the TermRender cursor missing the §17.2 bottom-row guard (an orphan
inverted cell whose glyph AA could still reach the divider), the Shell's minute/idle
maintenance loops surviving keeper restarts and accumulating one live loop per reconnect
forever, TermRender's glyph-coverage cache going stale across a per-app font swap, and the
AA reassembler throwing on a malformed final fragment in the notify callback — plus five
stale doc claims. APK bumped 8/0.8, both artifacts staged.

**The grid retirement.** Adam tested the §17 typography against the tmux grid and called it:
*"With a grid, font changes are subtle, because the spacing is kept identical … The whole
unpleasant way it looks is entirely because of the grid. Lets kill the grid entirely."*
Probes had already proven the mechanism (measured, fake AND real fonts): face/style reached
the grid (~40–50 k px differ) but **Font size compensated to exactly zero** — `fitFor` picks
the size whose TRANSFORMED metrics fit the fixed pitch, so any scale lands on the same
physical glyphs. His decisions: retire the grid, keep it only as the alternate-screen fallback,
stay JBM as the default face, capture at 1 s configurable.

Built the same night (TMUX.md top block is the design record):

- `FlowRender` + `Sgr.parseRuns`: normal panes captured with `-J` (logical lines), wrapped at
  the content width through the per-app transform — font/size/style all real now — SGR as
  styled runs, rule lines drawn as rules, a tail cursor marker, terminal tail anchoring, the
  §17.2 bleed guard baked into the line count.
- `TermRender` survives solely for `#{alternate_on}` panes (htop/vim), captured row-exact by
  the same one-script branch; history is the SAME flow over the frozen capture (5 display
  lines/notch, rail, edge-returns-to-live — grammar unchanged).
- Pacing: provider default 1 s (was 500 ms), `setCapturePacing` on the provider seam, `tpace`
  on the wire (additive; old hosts log-and-ignore), Settings → Tmux → Update (0.5/1/2/5 s),
  persisted and re-asserted on restore and reconnect.
- Retired: the fit-80/7.6 px on-glass item; "Fit pane to glass" stays but only matters for
  alternate-screen TUIs. Cost stated plainly: column alignment survives only for lines that
  fit unwrapped.

Battery green: core **158** (6 new `FlowRenderTest` + pacing/fallback window tests + the
wire-pacing round trip) · desktop 9 · selfcheck 48 · snapshots (09c/09d now show the flow;
eyeballed) · epub-check · lint 0 · **APK 9/0.9 staged, jar staged**.

### 18.1 RESUME PROTOCOL for a fresh context (supersedes §17.1's numbers; its shape stands)

1. Read `CLAUDE.md` (status header current), then this §18, then §17.1 back through §13 for
   the launch-day arc. `DAILY.md` = ops crib · `TMUX.md` top block = the flow design ·
   `IMPLEMENTATION.md` = what runs · `REMINDER.md` = the on-glass checklist.
2. **The state of the world:** the OpenRC service `damage` was RESTARTED onto the flow build
   (`43f2f15`) at 2026-08-31 18:05 and went straight back to driving through the phone
   (`driving via remote:aphone`, log `~/.damage/damage.log`). **The phone runs APK 0.9 —
   OBSERVED, not assumed: its `tpace` control arrived on the tmux channel** (the pre-restart
   PC process logged it as an unknown control, additively, exactly as designed). Both ends
   ≥ 0.6, so handovers adopt. Deploys: `./gradlew :desktop:stageJar && sudo rc-service damage
   restart`; **stop the service before any `:desktop:run`** (one central, one set of ports).
3. **Versions:** repo HEAD = the doc-freshness sweep on top of `43f2f15` (flow) · `92a98aa`
   (8/0.8) · `67a1d41` (review fixes). APK staged AND installed: 9/0.9.
4. Battery at HEAD, all green: core **158** · desktop 9 · selfcheck **48** · snapshots
   (09c/09d show the flow) · epub-check 380/404 images · lint 0 · APK builds. Run the whole
   battery after ANY code change (`CLAUDE.md`). One flaky one-off was seen mid-session
   (a single core-test failure that never reproduced across four reruns on a loaded box) —
   note it if it recurs.
5. **Open, in order:** Adam's on-glass verdicts on the FLOW view (wrap feel, default 16-px
   base size, rule collapse, the 1 s cadence, alternate-screen fallback) and the rest of the
   night wave (per-app depth, fonts-in-their-own-face); the left-lens seam-residue watch; the
   ~20 s silent-death window (tighten `SEAM_PING_MS`/`SEAM_QUIET_MS` only if he asks); then
   the **app-layer scope explosion** (`CAPABILITIES.md`, `DESIGN.md` §0/§4.6) and the
   icon-quality pass.
6. Known minor debts, deliberately not taken (reported 2026-08-31): tmux list rows cut long
   strings without the ▸ continuation mark (reachable content, soft NO-TRUNCATION gap);
   `LocalTmuxProvider.captureLoop` blocks on the scope dispatcher (statusLoop got the
   parallel-IO treatment, captures did not — one subscriber in practice).
7. G2CC coexistence unchanged: its SERVER runs (:7300, serves our APK page); its ANDROID
   bridge stays Disconnected (second central); never sweep its in-flight FF1 work into a
   commit.

## 19. The arbitration correction + last-write-wins sync (2026-08-31, night)

**Adam, correcting §8.1's reading:** *"When I said the best path should always be home PC I
meant having it available to provide data to the apk so that DamageWM is fully functional with
all its apps. I assumed you'd read my statement WITHIN my documented intent, not as a reversal
of it."* And the intent, restated in full: *"The intent is for the apk on the phone to be the
primary driver, with the PC server pushing data to it when available, with the ability to fall
back to apk-only when PC unavailable, and in the rare case the PC is available via BLE but the
apk is not, the PC can drive the glasses directly. … data that can differ between apk and PC
should be automatically synced as soon as both systems can communicate, with the most recent
data having priority."*

So this is **not a reversal of §8.1 — it is a correction of the build's READING of §8.1**, and
it **restores `DESIGN.md` §10.1 row 1 exactly as written** (transport=phone, **shell=phone**,
content=PC). The finishing build inverted the shell placement ("the PC shell drives whenever it
can reach the glasses by any path"); that inversion is retired.

### 19.1 The corrected contract

1. **The phone shell is the PRIMARY driver, always, while the APK is up** with Target=glasses.
2. **The PC is the data provider**: content host (books, tmux, config) + the sync service. Its
   reachability decides how CAPABLE the phone shell is, never who drives.
3. **APK-only fallback** when the PC is unreachable — cached content, staleness said (§10.5).
   Unchanged; already built.
4. **PC-direct BLE only when the APK is not available** (seam unreachable, or reachable and
   saying Target≠glasses) — and the PC **hands the radio back** when the APK returns.
5. **State that can differ syncs automatically, most-recent wins**, as soon as PC and phone can
   talk. This also answers `EXPLOSION.md` §16.4.

What this buys beyond correctness: away from home the network LEAVES the interaction loop
(today every gesture round-trips phone→PC over Tailscale and the frame comes back); PC deploys
stop touching the display entirely; driver-failover machinery becomes the rare path instead of
the daily one.

### 19.2 Design (fixed — do not re-derive after a compaction)

**Seam status probe (`RemoteTransport.kt`).** New first-frame type on the seam (:7402):
`Ctl(t="status", token)` → the server answers `Ctl(t="status", ok=<wantsRadio>,
connected=<driving>, detail=<status line>)` and closes — no claim, no driver slot touched.
`RemoteTransportServer` gains `statusFor: (() -> SeamStatus)?` (phone supplies
wantsRadio = Target==GLASSES && !destroyed, driving = transport started). `SeamProbe.probe()`
is the client: `Unreachable` / `Reachable(wantsRadio, driving)`; an OLD server answers the
probe with `busy "bad token"` → `Reachable(wantsRadio=null)`, treated as **wants the radio**
(conservative — never contend with an APK we cannot ask). The probe's bounded connect/read
window is a liveness DECISION of the seam-heartbeat class (SEAM_QUIET_MS precedent), not a
work-abandoning timeout.

**Desktop standby (`Main.kt`).** `auto` mode no longer builds a driving stack. It runs content
host + sync + replica, and a **standby loop**: probe the phone seam every 5 s (pacing);
phone wants the radio (or unknown/old) → ensure no PC stack runs (stop = the handback; the
lease fails open ≤90 s and the phone's keeper reconnects with its normal choreography);
phone absent or Target≠glasses for **2 consecutive probes** (debounce over APK restarts) →
build and start a plain `ble` DesktopStack. `--transport ble | remote | sim` stay as explicit
manual modes (`remote` keeps the hardened claim/adopt machinery as the deliberate override);
the Target row switches between manual modes and back to `auto`(=standby). PathTransport loses
its default consumer and stays (tested, harmless).

**Sync store (`Persistence.kt`, evolved in place).** Schema v2: `{"__v":2, "records":
{key:{"v":blob,"t":stampMs}}}`; a legacy file (no `__v`) migrates on load with every stamp =
the file's mtime. `put()` re-stamps **only when the value actually changed** (max(now, old+1),
monotonic per key) and then notifies listeners — otherwise LWW would degenerate to
"whoever saved last wins everything" (saveAll rewrites every key every tick).
`tryApplyRemote(key, v, t)`: strictly-newer stamp → store silently (no listener echo) + save;
equal value with a different stamp → adopt the higher stamp silently; else refuse. Stamps are
compared after per-connection skew normalization (each handshake carries the sender's clock;
incoming stamps shift by the measured offset, future-clamped) — cheap insurance, NTP does the
real work. get/put/save/load keep their signatures: Shell and every test compile unchanged.

**Sync wire (`core/sync/SyncNet.kt`).** Rides the CONTENT port exactly like the tmux channel:
a connection that sends `{"t":"sync","stamps":{…},"clock":…}` after the hello becomes the
persistent sync channel. Server (`SyncNet.serve`, hosted by `ContentHostServer(sync = SyncPeer)`)
answers `syncok` (its strictly-newer records + the keys the client holds newer + its clock),
the client pushes those, then BOTH sides live-push `syncrec` on every local change (store
listener → outbox). The client (`RemoteSync`, phone) reconnects keeper-style (15 s pacing) and
**re-handshakes every 5 min on a live connection** — convergence never depends on no push ever
being lost. Synced keys: `shell.settings` + `window.<id>`; `shell.state` (focused window, mode,
cursor, notices) is per-device UI and NEVER syncs. An old host closes the session on the
unknown request — the client logs once and keeps its pacing (version-skew-safe both ways).

**Shell live-apply (`Shell.postSync`).** `postSync(key, value, stamp): Boolean` — false when
the shell is not running (the host applies to the store directly). On the loop: **freshen the
key first** (put the LIVE window state / settings, so LWW compares against what the user sees,
not the last debounced save), then `tryApplyRemote`, and only if accepted: settings →
`applySettings` (full restyle path; its own put is value-equal so no echo); `window.<id>` →
`restoreState`, and when that window is focused in WINDOW mode with the wheel closed →
`syncLayout()` + full repaint. The driving shell's state is by construction the newest, so in
practice sync flows driver → idle; LWW covers the switchover races.

**Hosts.** PC: ONE process-wide store (`~/.damage/state.json`) shared by the content host's
SyncPeer and any stack the standby (or a manual mode) builds; applier routes through the live
shell when one runs. Phone: `RemoteSync` joins `startStack` (same lifecycle as the tmux
provider); applier routes through `Shell.postSync` with store-direct fallback. Dev note: a PC
`sim` session shares the PC store — state touched in the preview syncs like any PC-side state,
by design (LWW as asked); test harnesses use scratch dirs and never touch it.

### 19.3 Checklist

- [x] S1 Persistence v2: stamps, change-detection, listeners, tryApplyRemote, migration; store tests in `SyncTest`
- [x] S2 `SyncNet` + `SyncPeer` + `RemoteSync`; ContentHostServer dispatch; loopback tests (initial convergence both ways, live push, old-host refusal survived)
- [x] S3 `Shell.postSync` (freshen → apply → live-apply); test: newer applies + repaints, older refused after freshen
- [x] S4 Seam `status` + `SeamProbe` (+ phone `statusFor`); probe test incl. the slot-untouched guard
- [x] S5 Desktop standby loop replacing auto's race; handback narrated; BlueZ-less machine stays host+sync loudly
- [x] S6 Phone wiring (`RemoteSync` in startStack/stopStack); APK bump 10/0.10
- [x] S7 Full battery green (core **164** · desktop 9 · selfcheck 48 · snapshots eyeballed · epub 380/404 · lint 0 · APK); jar + APK staged
- [x] S8 Docs sweep (DAILY, IMPLEMENTATION, CLAUDE status, README, REMINDER, DESIGN §10/§11, EXPLOSION §16.4); memory; commit

### 19.4 Progress log

- 2026-08-31 — §19 written; §8.1's reading annotated as corrected; DESIGN §10.1 confirmed as the intent.
- S1–S7 built and green the same night. One store-semantics call made during testing: an EQUAL
  value never reports "applied" whatever its stamp (it adopts the higher stamp silently) — a
  live re-apply of what is already shown would only cost a repaint.
- **Deployed**: the service restarted onto the standby build and behaved exactly as designed
  against the OLD 0.9 APK — the un-probeable seam answers `busy`, the probe reads it as "the
  phone owns the radio", no BLE stack was started, the phone shell kept driving; the legacy
  state store migrated in place (5 keys, mtime-stamped). Sync stays dormant until APK 0.10 is
  installed (the 0.9 APK has no sync client) — `REMINDER.md` §Next carries the live checks.
- ⚠ **Recorded debt (micro-race, deliberately accepted):** a record arriving in the moments a
  shell is BETWEEN store-load and running (start-up) is applied store-direct but not
  live-applied, and the shell's first save of the stale live state can out-stamp it — a remote
  edit made in exactly that window can lose to older data. The window is microseconds-per-key
  at stack start, the peer's copy survives until the next local change, and under phone-primary
  the PC side rarely writes at all. The 5-min re-handshake does NOT heal this case (the stale
  overwrite carries the newer stamp). Revisit if a deep review wants it closed; the fix shape
  is a post-start reconciliation pass over syncable keys.
- **The ring-battery question, settled from source (same night):** the R cell never filled
  because it CANNOT — the stock sid-0x91 service (openCFW `pb_service_ring.c`, recovered
  against our 2.2.6.10 base) accepts only the EVENT registration and never fills RingRawData;
  both captures corroborate (zero rawData frames), and G2CC §10 already said the official app
  reads ring battery on the ring's OWN link. Our passive relay listener was correct and the
  answer is negative — banked in `CLAIMS.md`/`CAPABILITIES.md`. Built the same hour (Adam away
  from the PC, so it rides the phone): **the APK's read-only ring probe** — the strip's "ring"
  button scans for `EVEN R1`, connects with no pairing, logs the full GATT table, reads
  standard Battery (0x2A19) + Firmware Revision (0x2A26) once each if offered (never retried —
  a retry is where Android auto-pairs, and §11.5 says leave this ring's bond state alone), and
  a successful read fills the chrome R cell + re-reads on a 15 min pacing for the run. The
  firmware-revision read doubles as the §10.13 "ring version never verified" check. APK
  **11/0.11** staged (0.10 was never installed); battery green (core 164 · desktop 9 ·
  selfcheck 48 · lint 0); jar restaged, service restarted (standby — a restart touches
  nothing on glass now).
- **Ring battery — CLOSED and the probe REVERTED (2026-08-31, Adam's call).** The probe chase
  (0.11 enumerate → 0.13 device-log → 0.14 listen) ran to the end on hardware and confirmed:
  the ring has no standard Battery Service, no advertised battery, and a request/response
  vendor link (`bae80001-…`) with a custom checksum (no standard CRC-16 matched 47 clean
  frames). Then the decisive check I should have run FIRST — **Faceclaw does not read ring
  battery anywhere** (state is `{headset, headsetCharging}`; chrome draws Phone+G2 only; the
  ring decoder is gestures-only; device-info probe hits the glasses). Only the closed Even SDK
  reads it. ⚠ My earlier "Faceclaw-proven second connection" framing was wrong and created a
  false "just copy Faceclaw" premise — corrected in `CLAIMS.md`/`CAPABILITIES.md`. Reverted:
  `RingProbe.kt`, phone `DeviceLog.kt`, desktop `DeviceLogFile.kt`, the content-port `devlog`
  dispatch, the strip "ring" button, the ShellService probe methods; `Shell.ringBattery` back
  to a private wire-fed field (the 0x91 relay listener stays — harmless, documents the finding).
  The R cell stays blank (cosmetic; ring battery is in the Even app). The biometrics, if ever
  wanted, are the `EXPLOSION.md` §13 Health window as a deliberate protocol-RE sub-project.
  **Process lesson: a cosmetic gauge became a multi-round side-quest; it should have been
  cost/benefit-checked at the first finding and verified against Faceclaw before any building.**
  APK **15/0.15** (probe removed); battery green (core 164 · desktop 9 · selfcheck 48 · lint 0).
