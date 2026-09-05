# HANDOFF — the build record

**Map, newest first. A fresh session starts at `REMINDER.md`; this file is the decisions,
lessons and measured facts behind the current state.**

| § | what | status |
|---|---|---|
| **25** | **The whole-codebase review (2026-09-03, late): ten verified defects, each reproduced before its fix and each pinned by a test that fails without it — plus the belief-vs-truth oracle that found the invisible ones** | **current** |
| **24** | **Music (2026-09-02): the design verdicts + plan, the overnight build — M1–M6, six commits, the shell's EXCLUSIVE mode (§24.1) — then review round 1 (§24.2) and two ultrareview runs (§24.3), all the same day; §24.4 the silent-playback session and the NOW PLAYING root (2026-09-03)** | **current** |
| 23 | Torrents + the keyboard (2026-09-01, evening): the second conversion, built whole, + a 4-round review loop — PAUSED at Adam's word, not converged; its round-4 diff is `980d832..390a25c` | current |
| 22 | The overnight build (2026-09-01): §16 machinery + FILES + the 8-round review loop, run to convergence | current |
| **21** | The live refinery + Files chosen + the settled Files design (2026-09-01) | current |
| **20** | The general-contract session — EXPLOSION §16 settled (2026-09-01) | current |
| **19** | **The arbitration correction + LWW sync (2026-08-31)** — the phone shell is PRIMARY; the PC is the data provider + standby; per-key last-write-wins state sync | **the topology contract** |
| 13–18 | The launch-day arc (2026-08-31): APK hardening → Tmux → the daily driver → zero-blink handovers → typography → the flow view | done; lessons kept |
| 10–12 | The install (2026-08-30), first light, the refinement wave — the measured numbers and the hardware lessons | done; facts kept |
| 0–9 | The pre-flash build era (2026-08-24/25) | compressed to decisions + traps |

Precedence as always: `overview.md` on facts, `CLAUDE.md` on rules, `DESIGN.md` on shell design.
`IMPLEMENTATION.md` says what runs; `DAILY.md` is the ops crib.

## 0–7. The pre-flash build era (2026-08-24/25) — compressed

The first build stage produced the Kotlin core/desktop/phone tree against the byte-exact
firmware model, hardened through five review rounds (~70 defects). Everything it planned was
built, its whole gap list closed, and `IMPLEMENTATION.md` (module map, "Review hardening") +
the code are the record now. What still matters from the era:

- **Adam's target, verbatim:** *"a fully working DamageWM that only needs me to flash the
  glasses firmware and install the app and poof it works"* — with a pixel-perfect replica on
  PC and phone, never Even's simulator. Achieved; the replica is the mirror-sim tee.
- **The era's traps live in `IMPLEMENTATION.md` → "Review hardening"** (per-lens compositor
  truth/shadow, session-epoch sweeps, fid-wrap and msgId discipline, the lease as correctness).
- **G2CC study:** the mining table moved with the work — `WINDOWS.md` governs how conversions
  read `/home/user/G2CC` (facts only, never edits). G2CC's BLE docs remain wire-source #3.

## 8. The finishing build (2026-08-25) — decisions that still govern

Built and closed the same week (five review rounds; the checklist, design prose and progress
log are retired — the code and `IMPLEMENTATION.md` describe what exists). Kept here because
later sections cite them:

1. **The connect prelude** is the CFW reference's single sid-0x01 app-launch (own
   implementation); G2CC's sid-0x80 `AuthSequence` is the stock app's and is never used.
2. **The arbitration contract, Adam verbatim (§8.1 d3+5):** *"The default will be phone app +
   Home PC over internet to phone, if internet to phone is lost fall back to phone app only,
   if phone app is not up fall back to Home PC over BLE directly. Once running, system should
   always be trying to find a way to connect and drive the glasses… Home PC is always the best
   case to constantly be trying for…"* ⛔ The finishing build read "best case" as "the PC SHELL
   drives whenever it can". **Adam corrected that 2026-08-31 — see §19**: best case = the PC
   AVAILABLE TO PROVIDE DATA. §19 is the standing contract.
3. **Decision 6:** a notification arriving while the switcher wheel is open waits behind it,
   queued unshown; a shown box requeues unread. (Extended 2026-09-01: the context menu and the
   keyboard defer the same way, and an EMERGENCY cancels any of the three surfaces instead of
   waiting — §22, §23.)
4. **MIT dependencies are fine** for PC-direct BLE (`bluez-dbus` + `dbus-java`); the clean-room
   rule is about GPL code.
5. Arbitration decides by ENGAGEMENT (the phone path wins by construction when reachable);
   capability refusal is the only terminal condition; everything else reconnects forever with
   pacing, never timeouts.

## 9. The first install plan (2026-08-30) ⛔ SUPERSEDED

Written against g2flash `877c8d9` and the archived 2.2.6.11 image; the reference repos moved
the same evening and §10 replaced it in full. Nothing from it should be followed. (Its one
lasting artifact: the offline `verify_cfw.py` discipline, which §10 kept.)

## 10. The firmware install — DONE 2026-08-30

**The irreversible step is behind us.** Stock 2.2.2 left the glasses 2026-08-30; it is not in
the public archive and cannot be re-installed. Every OTHER version (the CFW included) remains
installable; G2CC's display path no longer applies to this pair.

**What was installed and why:** g2flash `a5d1c31`'s own build (sha `d4054ab1…`) — the image
**Faceclaw 0.6.1 ships** (byte-identical across its pinned 26 patches), carrying the texture
cache and a fix for a latent context-pointer defect in the older archived image. Both known
unrecoverable-failure classes (unbounded MRAM program, Thumb-bit interworking) checked clean;
`research/verify_cfw.py` proves the image reproducible offline — run it before any flashing
conversation, still.

- 🔴 **It reports version `2.2.6.10` — indistinguishable from stock.** CFW detection goes
  through the `EVENCFW/` capability string, never the version. Do not read the version and
  conclude a flash failed.
- **The flasher was patched first** (Adam's call): `recover_session()` ran `authenticate()`
  before `_reset_seq()`, so a recovery magic ≥ 128 emitted a malformed varint — roughly a coin
  flip per recovery event. One line moved; proven offline; reported upstream. The patched path
  never fired (zero resends in the real run).
- **The dry-run staircase** (`--stop-before heartbeat` → `file_check` → `flash`) ran clean on
  the real pair first, and closed two risks on hardware: an unbonded Linux host connects and
  authenticates with no pairing prompt, and the sid-0x80 auth exchange works as captured.
- **The write:** both lenses, all six components, 4,339,457 B in 171 s per lens, **zero
  resends, exit 0**; every END verify returned status 8 (UPDATING) — inside the tool's OK set.
  Effective OTA goodput ~25 KB/s PC-direct (`overview.md` §5.1, with its caveats).
- **Arm addresses (Adam's pair, serial 32):** left `D8:AE:E7:C1:FA:4D`, right
  `E4:87:77:65:CD:50`, both public. ⚠ The `C4:…` addresses in `overview.md` §9.2 are the
  third-party CFW user's, not this pair's.

**§10.13 The R1 ring updated the same day** — to 2.2.6.0009 (the release paired with the
2.2.6.10 base), via our own `research/r1_dfu.py` (Nordic Secure DFU; the R1 facts read from
SybilSight's MIT `r1Dfu.js`). Zero retries, clean reboot to the application. Lessons kept:

- 🔴 **"Reversible firmware" is not "reversible outcome":** the DFU cleared the ring↔glasses
  BOND — separate state a re-flash would not restore. The Even app re-registered the ring
  (the `91-20` message) and input returned. Any future DFU on a paired device counts
  re-pairing as part of the cost, confirmed possible BEFORE starting.
- The ring's bootloader advertises `B210_DFU_<suffix+1>` at MAC+1 — match by service UUID
  `0000fe59` + a `DFU` substring, never a fixed name.
- The running ring version was never read back (its vendor link is only partially decoded);
  the signed-image acceptance is strong evidence, not a version read.

## 11. First light — 2026-08-30

The PC drove the glasses within an hour of the flash: scan → both arms → MTU 247 → prelude →
capability gate (`EVENCFW/16`) → carrier → lease → warmup → `driving via ble`, then the ring
drove the shell. Closed on hardware: PC-direct BlueZ works first try; link-drop recovery
(two unplanned LEFT drops, keeper resumed both unaided); the ring→glasses→`e0-01` event hop.

**The measurement that re-priced the project:** CFW ack latency ≈ **50 ms** floor against the
176 ms stock figure every estimate had used. (The full curve was measured the next day —
`overview.md` §5.2: `ms ≈ 60 + bytes/50`, n=1,488 flushes. Quote that, not the floor.)

**Three defects, all ours, all lessons:**

1. **The ack status enum** — `ImgResCmd` f8 is a per-operation STATUS (4 = image success), not
   "non-zero = error"; the transport refused the glasses' own success ack. Nothing offline
   could catch it: the sim modeled success as an absent field. `AckStatusTest` pins both sides.
2. **The journal write pileup** — a reopened-after-stop stream failed EVERY line, burying the
   log at the moment it existed to explain. Unrecoverable errors report once.
3. **Input was unobservable** — no inbound gesture was logged, so "arriving and mishandled"
   vs "never arriving" could not be distinguished. An input path you cannot observe is a
   silent failure even when every component in it is loud.

🔴 **Event semantics fact (load-bearing):** event 10 (`LONG_PRESS_RELEASE`) fires after almost
every swipe; event 9 is the actual long-press. The names in `gesture_fwd.c` describe hook
sites, not semantics. §1.2's bare-long-press no-op is what keeps stray 10s harmless.

## 12. The refinement wave — 2026-08-31, live deploys

The whole `REFINEMENT.md` queue built, verified and deployed while Adam wore the glasses
(verdicts in `REFINEMENT.md`; revised rules in `DESIGN.md`): chrome depth inset to the content
extent, coarse scroll with configurable step (his verdict: 5 lines/notch, acceleration off),
Reader folders/chapters/images/reset/descender fix, per-app height on focus commit, the
digital clock top-right (an analog/top-left attempt reversed same-session), Settings as
directories, brightness + glasses-battery wired to the wire, the 4× preview. Plus the measured
latency curve (§11 note above).

**Hardware lessons kept:**

- **The eaten-gate class.** A single-send await in flight while the firmware tears down a
  PREVIOUS session's context is simply never answered — first the capability query parked,
  then (with that fixed) the carrier CREATE. Both now re-ask on a 2 s pacing tick
  (`CfwTransportBase`); any future gate that parks the same way gets the same treatment. The
  deeper cause (sessions ending without FB_RELEASE/mode-11 cleanup) is the standing argument
  for mode 11 in `stop()`, still deliberately unadopted.
- **The switcher was inoperative from first light because of OUR source filter** — events 9/10
  arrive with `EventSource` ABSENT (source 0, documented all along) and the ring-only check
  discarded them. `LongPressTest` passed throughout because the harness supplied the source
  the wire omits. Lesson, same family as the ack enum: **a test default that "helpfully"
  supplies what the wire omits is a model erring permissive — inject what the firmware
  actually sends.**

## 13. The APK mission (2026-08-31) — DONE

Adam's ask, verbatim: *"Make sure it can do everything the PC system can do, including
connecting to the PC system and using both, as well as falling back to phone-only and
PC-only, just like in the design."* All of `DESIGN.md` §10's configurations now run on
hardware daily. Pre-radio hardening that shipped with it: the **seam heartbeat** (a silently
lost path hands back in ~20 s, not TCP's minutes), the **pocket-liveness trio**
(PARTIAL_WAKE_LOCK, battery-exemption ask, boot auto-start), **scan hardening** (BT-off fails
loudly; re-issue under Android's 30-min downgrade), and APK distribution via
`./gradlew :phone:stageApk` → `~/.damage/damage-wm.apk` → the G2CC `/setup` page's
`/damage-apk` endpoint.

**Standing trap: ONE CENTRAL AT A TIME.** The pair cannot serve two masters — stop the
`damage` service before any `:desktop:run` dev session (`ble`/`remote` modes are a second
central); G2CC's Android bridge stays Disconnected. `le-connection-abort-by-local` on connect
means another central holds an arm.

## 14. Tmux on glass — designed and built in one day (2026-08-31)

`TMUX.md` is the design record (G2CC studied, not copied; scroll-up IS scrollback; typed text
always staged behind a confirm; multi-host over ssh). ⚠ The GRID this section shipped was
retired the same night — §18; the flow view is the terminal now.

## 15. The daily driver prepped (2026-08-31) — `DAILY.md`

`--no-preview` headless mode, the staged `~/.damage/damage.jar` (`:desktop:stageJar` — a
broken tree never changes the daily driver; staged via temp + ATOMIC move after an in-place
truncation broke the running service's lazy class loads), and the OpenRC service **`damage`**
(supervise-daemon, default runlevel). Deploys = `stageJar` + `sudo rc-service damage restart`.
`DAILY.md` is the ops crib. Two prep-smoke defects fixed: a `polling` field declared after the
init block racing it; a down ssh host serializing every other host's status poll (parallel +
skip-if-in-flight now; tmux hosts are opted in when actually alive).

## 16. The session outlives the driver (2026-08-31) — zero-blink handovers

A WiFi→LTE edge cost two visible teardowns; Adam: *"the whole point of this hybrid adaptive
system is to stay connected."* The BLE session's lifetime is now decoupled from the driver's
(G2CC's ConnectionManager made the same move): `Shell.stop(stopTransport=false)` is the yield
form; `start()` ADOPTS a live session (one wide flush instead of the whole choreography); the
seam server grants adoption, treats a driver's "stop" as claim release, and never ends the
owner's session on driver loss. `HandoverTest` pins `preludeAcks == 1` across claim → release
→ re-claim, silent driver loss, and the full WiFi-edge loop. Mixed versions degrade to the
old tear-and-rebuild exactly.

## 17. User typography + per-app depth (2026-08-31, late)

One mechanism: `core/text/Style.kt`'s `StyleTransform` rewrites every `FontSpec` at the
rasterizer seam — chrome + Main through the shell's global transform (a recorded REVERSAL of
§Type's fixed-system-face lock; `DESIGN.md` §0/§Type carry it), each window through its
per-app transform (`styledText()`). Font options preview in their own face. Per-app depth
drives the focused content plane; bars + Main stay global. Two same-hour tmux fixes from
Adam's evening use: a bottom-row AA bleed guard in both render paths, and a failed history
capture no longer strands `histLoading` (falls back to LIVE, loudly).

## 18. The tmux grid is retired — the FLOW view (2026-08-31, night)

Adam, after testing typography against the grid: *"The whole unpleasant way it looks is
entirely because of the grid. Lets kill the grid entirely."* Probes had measured the cause:
`fitFor` compensated any Font-size change to exactly zero on a fixed pitch. Built the same
night (`TMUX.md` top block): `FlowRender` + `Sgr.parseRuns` — normal panes captured `-J`
(logical lines), wrapped at content width through the per-app transform, SGR as styled runs,
rules drawn as rules, tail anchoring; `TermRender` survives ONLY for `#{alternate_on}` panes
(htop/vim); history is the same flow over a frozen capture; capture pacing 1 s configurable
(`tpace`, additive on the wire). Cost stated plainly: column alignment survives only for
lines that fit unwrapped. The same night's deep-review build fixed keeper-restart loop
accumulation, a stale glyph-coverage cache, and a reassembler malformed-fragment throw.

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
3. **APK-only fallback** when the PC is unreachable — cached content, staleness said (`DESIGN.md` §10.5).
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

### 19.3 Notes that outlived the build

All of §19.2 is CODE (`SyncTest`/`SubstrateTest` pin it; `IMPLEMENTATION.md` describes it).
Kept from the build log:

- **Store semantics call:** an EQUAL value never reports "applied" whatever its stamp (it
  adopts the higher stamp silently) — a live re-apply of what is already shown would only cost
  a repaint.
- **The startup micro-race is CLOSED** (2026-09-01, §22): a record applied store-direct while a
  shell sat between store-load and running could be out-stamped by the shell's first stale
  save; `startLocked`'s tail now posts a reconciliation pass that re-applies any store record
  newer than the live state (`SubstrateTest`). §22's review rounds added the rest of the LWW
  hardening: stamp-0 baselines, per-session `subReported`, per-item re-apply after a
  main-record live apply.
- **Ring battery: CLOSED (2026-08-31, Adam's call), probe reverted.** Settled from source +
  hardware: the stock sid-0x91 relay never fills RingRawData, the ring itself offers no standard
  Battery Service, its vendor link uses a custom checksum, and Faceclaw does not read ring
  battery anywhere — only the closed Even SDK does. The 0.11–0.14 probe chase was reverted
  whole; the R chrome cell stays blank (the Even app shows ring battery). Conclusions banked in
  `CLAIMS.md`/`CAPABILITIES.md`. Process lesson kept: cost/benefit-check a cosmetic gauge at the
  FIRST finding, and check the reference implementation before building.

## 20. The general-contract session (2026-09-01) — §16 SETTLED, recorded, no code

The pre-refinery "general topics" pass with Adam. Decisions only; nothing coded. The records:

- **`EXPLOSION.md` §16 rewritten with statuses + the agreed build order.** Highlights: 16.1/16.2
  the deep-link verb designed (`open(target)`, opaque per-window targets, push-on-hand-off, never
  on preview); 16.3 per-window user config moves to the SYNCED store (app-alone quick-replies);
  16.4 **raised to Adam's top priority** — *"an always-active session that can be continued
  seamlessly from every device … 100%"* — with four must-dos before the first conversion
  (per-item sub-records, the §19.3 startup-race closure, a per-window continuity test in the
  battery, content continuability); 16.5 the notification signature grows once (source, coalesce
  key, body, deep-link target, urgency); 16.10 **ONE generic window channel with multi-backend
  providers** (Music's PC-library→Spotify fallback is the archetype: switch only if actively
  playing, switchback deliberate, summary names the live backend); 16.11 the shared kit
  (fit-with-▸ closes the tmux bare-clip debt structurally); 16.12 the Title short-by-design contract +
  the honest NO-TRUNCATION wording (content vs handles); 16.13 the scheduled-work substrate
  sketch (LWW `fired` stamp — duplicate notification possible, missed fire never).
- 🔴 **B612 is NEVER a default — final.** Adam, after repeated cross-session re-proposals
  (seeded by the docs' own "revisit candidate" notes): *"It looks like shit, let it go."*
  All advocacy notes neutralized in place (`DESIGN.md` §Type ×3, `EXPLOSION.md` ×4); the ruling
  is in session memory. The surviving real item: a **curated font-library expansion**
  (`DESIGN.md` §Type carries the plan; option-only, defaults untouched).
- **Main's lens icon goes band-height** (56 px class) for the focused row — `DESIGN.md` §4.5b —
  and the **icon-quality pass moves to the front of the app wave** (one drawn icon per app, two
  scales). Watch Main-resting ≤ 5 % ink when it lands.
- **`WINDOWS.md` created** — the per-window conversion checklist, the bar every window meets,
  and the traps already paid for.

**What followed:** the refinery ran the same day (§21); the shared machinery in §16's build
order (state substrate → window channel → deep links + notify signature → kit) and the first
conversion were built that night (§22).

## 21. The live refinery + Files chosen (2026-09-01, later)

The refinery ran in session and is recorded in **`EXPLOSION.md` §20** (which supersedes §18's
counts): 🪓 axed Deliveries · Calendar · Timers (§16.13 goes with it) · Search · **Weather**
(phone app preferred; the NWS hedge 14.4 goes with it — the §4.5 emergency promise rides the
WEA/CMAS probe alone) · **Health** (no longer viable in either direction; aria is retired).
✅ Added: the **TORRENTS window** (§19 — his "Yes!": private-tracker browse +
add-to-qBittorrent + progress + done-notifications),
Feed comic sources (11.12), **caller ID as a §16.5 notification source**, the Info useful-stats
steer, and the Games 10b block (roster adds: cards/Minesweeper/Chip's clone; the emulation lane
gated on a ROM pace-screener; the Balatro real-game seam — LÖVE/Steamodded state-export beats
screencap vision). 🚫 The full rejected-ideas pile is recorded in §20 so none of it is
re-pitched. The wow order stands (§20).

🔴 **Adam chose FILES as the first conversion.** His design intent: G2CC-like + the graphical
wave; a locations root list (root, home, mounts); **tap = context menu with Open as the first
row** (two taps to enter a folder — uniform for every entry type); in-app viewers for text, PDF
and images "in nice ways". The design discussion ran live; the settled design got its own record
before code, per `WINDOWS.md` step 3.

The design was settled the same evening (his answers, binding): the context-menu popup is
**floating** (a hole in the content, not a card); the This-folder row wraps to the end of the
list; clipboard-slot Copy/Cut → Paste-here; **lens thumbnail AND per-row file-type icons "like
a real file manager"**; PDF dual-mode with an auto default; trash carries Restore + an on-glass
permanent delete behind a double confirm; typed rename/mkdir; Open-on-PC; EPUB→Reader hand-off;
locations include the damagewm project dir. Then his theme-icons ruling: **use his XFCE
Papirus-Dark icon set, grayscale-converted, "for everything in DamageWM that uses icons"** —
third-party assets, so personal-lane only (rendered locally at runtime, never in the repo, APK
assets, or a release; the drawn set stays as fallback + release path). His last words before
bed: build it all autonomously, then *"run a heavy review … for each problem or issue found,
double check and verify it is really a problem … then for every confirmed issue, fix it. Do
those review-then-verify-then-fix steps until a full review passes with no more issues found
at all, in a loop. We want to eliminate ALL bugs."* §22 is the record of that night.

## 22. The overnight build: §16 machinery + FILES + the review loop (2026-09-01, overnight)

Executed alone on the standing instruction above. Zero radio/glasses/phone interaction all
night; G2CC untouched; the `damage` service restart is safe by §19 (the PC never claims).

### 22.1 What was built (commits `b93d7e0` docs · `fa80bdf` substrate · `b715a18` Files · `8f0dfe2` review round 1)

**The state substrate (§16.4):** Persistence v2 sub-records (`window.<id>.<subKey>`) with
`saveSubState()/restoreSubState()` on the contract; tombstones = empty objects, written only
for keys the window has ever reported (the `reported` guard); merge-on-load (strictly-newer
in-memory wins, an unreadable store keeps memory); the **post-start reconciliation Run** (closes the
§19.3 debt); `freshen` skips absent keys (virgin-shell guard); live-apply is sub-aware and
routes `applySettings(persist=false)`. Continuity tests in `SubstrateTest` (A-save → sync →
B-restore → identical position).

**The window channel (§16.10, first slice):** `WinNet.kt` — `{"t":"win","win":…}` on the
content port, `WinService` host side, `RemoteWin` client (keeper reconnect, id-correlated
request/response, raw-blob answers for bulk, `stateLine` for staleness). Built generic;
Files is its first consumer. NOT yet: push frames, summaries-over-channel, multi-backend
arbitration, per-backend `needs` — Music is their first real customer.

**Deep links + notifications (§16.1/§16.5):** `open(target)` on the contract;
`services.openWindow(id, target)` — the shell records the CALLER itself, and the switcher's
back gesture returns to it after a hand-off; the notification signature grew source/thread/appId/target/
urgency and internal notices deep-link.

**MenuSurface (§16.11's biggest piece):** the floating context menu — 248 px hole at plane 0
(nearest), under-content captured and restored, pan-window for long menus, detail column capped
at half-box via `fitEnd`. Decision-6 notice deferral honored; emergencies cancel the menu and
requeue. Plus `Draw.kt` (`fit` — always marks cuts with ▸, `right`, `dynamic` — '?' for
uncoverable glyphs, warned once) and `Exec.kt` (subprocess runner whose stderr drains on a
daemon thread — no pipe-full stalls).

**Theme icons (Adam's ruling):** core `IconSource/IconNames/IconPaint/IconRaster`; desktop
`ThemeIcons.kt` (xfconf theme detect, Inherits-chain BFS, both dir layouts, rsvg-convert /
magick rasterize, mem+disk cache keyed by theme, clean-miss vs paced-retry-failure); phone
`RemoteIcons.kt` (content-port `icon` op, theme-keyed cache cleared on theme change via a marker,
Semaphore(4), closable). Main's focused lens icon is the 56 px band-height class (`DESIGN.md`
§4.5b; ink re-measured 9.0 %/4.8 %, tables updated). Icons are a RENDER-TIME lookup with the
drawn set as fallback — a missing tool or theme degrades loudly to drawn, never blocks.

**FilesWindow (~1,150 lines) + `LocalFilesProvider` + `FilesNet`:** the whole settled design —
locations root (Root/Home/Downloads/Books/damagewm/live mounts with capacity bars/Trash when
non-empty), tap = context menu with Open first, This-folder row at the wrap end, text viewer
(wrapped, UTF-8 boundary-safe chunked reads), image viewer (strip DocView), PDF dual-mode
(pdftotext/pdftoppm, auto default by extractable-text ratio), clipboard Copy/Cut → Paste-here
(NOFOLLOW copies, copyTree rollback), trash/Restore/purge (whole-op lock, double-confirm
purge), typed rename/mkdir (sanitized, blank cancels), Open-on-PC (xdg-open on the host),
EPUB→Reader hand-off via `open("book:<id>")`, per-row theme icons + lens thumbnail. Deviations
from the graded table are recorded in `EXPLOSION.md` §5's banner (per-row thumbnails not
shipped — the lens shows one — left as a later item before the no-staging rule; `appSettings()`
empty — hidden/sort live in the This-folder menu).

### 22.2 The review loop

Round 1: six fresh reviewer subagents (compositor+wire · shell · substrate+sync · Files ·
transport+seam · phone+desktop glue) returned **79 findings; each was verified before any fix;
~55 confirmed and fixed** in `8f0dfe2` (the rest: agent misreadings, working-as-designed, or
duplicates). The fixes that matter: the seam-start ordering race (the one flaky core test from
launch night — root
cause found and closed), timed notices, paced viewer/thumbnail retries (5 s), navigation
clearing stale entries, restore-position preserved through relayout, `parseAck` per-subfield
tolerance, `restampMsgId` loud refusal on fixed-width, inflate refusing needsDictionary,
sim strictness ×3. Boundaries verified as DESIGNED and documented rather than "fixed":
dual-live-shell LWW alternation (two shells both actively writing the same key trade wins —
inherent to LWW), activity-beats-remote-reset, and the startup micro-window now covered by
reconciliation.

**Round 2** (`ead19a3`, `5d7ba5e`): the pairBlacks probe CONFIRMED a real compositor defect —
an unpaired seam strip's fallback painted real black on the opposite lens OUTSIDE the repair
area, permanently and silently (1,920 px in the probe; `L2ProbeTest` stays as the regression
gate) — fixed by bounding seam strips to the scanned area. The fix-diff reviewer's 20 findings
all verified and addressed; the LWW ones matter most: **stamp-0 baselines** (a virgin device's
save can no longer stamp defaults over the fleet's real positions), the settings **re-encode
echo** closed at both saveAll and freshen, `subReported` made per-session (a failed restore
after a keeper restart can no longer tombstone real data), and the transitional Reader map
made live-authoritative. Plus: emergencies jump the notification queue, the sync sender
lifecycle, paced retries that actually repaint, code-point-safe fits, and Exec hardening.

**Round 3** (`d0a74aa`): three fresh reviewers over the whole project again (fix-diff /
glue+net / shell+windows) — ~28 findings, all verified, all real ones fixed. Standouts:
content-channel **liveness** (keepAlive everywhere, the tmux subscription re-assert, the win
host greeting) so a silently lost path reconnects instead of freezing a healthy-looking window;
the **cross-version law** enforced on every lane (in-band `err`, never a closed session, no
2 s flap against an old host); apply-only-if-undisturbed guards on every async completion that
was still missing one (openPdf, four tmux busy() sites); **per-item LWW re-apply** after a
main-record live apply (a newer sub-record wins its item back); `restoreStateLive` overrides so
a live-synced record gets the refresh/resubscribe boot gets; an emergency now cancels the
wheel too; and the notice queue no longer duplicates across keeper restarts.

**Rounds 4–8 — the convergence tail**, each round a fresh adversarial review of the previous
round's own fix diff: round 4 (`10db318`, 9 findings — the HIGH: the pdfpage restore
deterministically cancelled ITSELF, and a freshen mid-window re-stamped BROWSE and closed the
peer's open PDF; fixed with `pendingOpenView`, pinned); round 5 (`119d6dc`, 3 — each the R4
mechanism un-applied on one path); round 6 (`b33253b`, 3 — gate-coverage: the subscription
keys to the TARGET not the level, pinned twice); round 7 (`d2945eb`, 2 — the wrong-file
class's last doors); **round 8: NOTHING REAL FOUND — the loop converged.** Findings per
round: 79 → 20 → 28 → 9 → 3 → 3 → 2 → 0. Every layer had fresh eyes at least twice; the
final diff reviewed clean; the full battery ran green after every round.

Hardening candidates round 8 named, deliberately left un-taken after convergence (both
latent, neither reachable today): `LocalFilesProvider.list("")` lists the working directory
rather than refusing (matters only if a Files deep link ever creates a viewer without
browsing — none exists); Files `nameArmed` survives restoreState (materially safe — the
confirm shows both names). Take them with the next Files change.

### 22.3 State at hand-off

Battery: core **191** · desktop 9 · selfcheck **61** · snapshots 18 (eyeballed) · epub
380/404 · lint 0 · APK **16/0.16** staged (the phone still ran 0.15 at that hour; 0.16 was
observed installed later that day — `37cf9b0`). Jar staged; service restarted onto the build
(kept driving via the phone, untouched on glass). Reader writes transitional legacy offsets
alongside sub-records — **removable now that the installed APK is ≥ 0.16** (`REMINDER.md`
Next 2; with it: `restoreStateLive`'s map-authority and `liveMapApply`).

Recorded limits, verified as designed or accepted (round 3): the Reader reset-progress picker
matches by TITLE (two same-titled books are indistinguishable in that list either way — a
disambiguation is a design item); a Settings double-tap revert applies its whole captured
snapshot (a peer sync landing mid-adjust rolls back — dual-active esoterica); the "+N" badge
counts already-read queue entries; the content-port pre-auth hello read has no time bound
(tailnet-only, tracked and closed on stop); the win channel has no app-level ping (a silent
path loss is bounded by keepAlive and the write path's own retransmission); the L2 seam
repair is same-batch in the USUAL case — under exact budget exhaustion it rides the next
flush via `residual`, a one-flush transient.

## 23. Torrents + the keyboard (2026-09-01, evening) — `TORRENTS.md`, `DESIGN.md` §4.8

The second app-wave window, on Adam's new rule for every window after Files: **no v1/v1.5
staging — complete and polished before the next app.** The session, in order:

- **qBittorrent's Web API was not compiled in.** Rebuilt `net-p2p/qbittorrent-5.1.4` with the
  `webui` USE flag (`/etc/portage/package.use/60-qbittorrent`; the Gentoo ebuild builds the GUI
  variant with `-DWEBUI=ON` when both flags are set, plus an unused `qbittorrent-nox` and an init
  script left out of every runlevel). His GUI (36 days up) was stopped with SIGTERM twice, the
  config edited while it was down, and it was relaunched detached in his X session:
  `WebUI\Enabled=true`, `Address=127.0.0.1`, `Port=8090` (8080 is Caddy), `LocalHostAuth=false`.
  qBittorrent refuses to start the Web UI without a password, so `admin` + a random PBKDF2
  (its own format, verified in `src/base/utils/password.cpp`) went in; the plaintext sits in
  `~/.config/qBittorrent/webui-credentials.txt` (0600) and nothing on beardos needs it. Verified:
  API 2.11.4, 38 torrents, loopback only, no auth from localhost.
- **TorrentLeech probed live, read-only, one login** — the facts in `TORRENTS.md` §2: a JSON
  listing endpoint for browse AND search, the 40-category tree from the site's JS bundle, the
  torrent page's landmarks, the profile stats. ⚠ The profile page shows the passkey and e-mail
  in plain text; the probe's dump was deleted and the adapter reads five stats and stores
  nothing else. Credentials went into `~/.damage/config.json` only.
- **The design record first** (`TORRENTS.md`, `DESIGN.md` §4.8, the `EXPLOSION.md` §19 banner,
  `WINDOWS.md` §1's new rule): his verdicts — TorrentLeech only; browse and search; delete
  keep-files behind one confirm, with-data behind two; done = the finished edge, announced for
  every torrent, toggles in Settings → Torrents; no magnet/URL typing, no Files hand-off, no
  categories, no shelf glue, **no RSS ever**; everything lands in `~/Downloads`; 2 s / 15 s
  polling; Stats plus a **seeding-under-a-week list** (TL's hit-and-run window). The keyboard:
  row-then-key, **stay in the row after typing**, QWERTY + an abc Settings option, **no history
  row**, **the draft kept on cancel**, **outlines** — *"an image of an actual keyboard
  wireframe-style where I can move the highlight to select the key."* And the general rule:
  **each app's notification toggles live in its own Settings category, never in Global.**
- **Built**: `KeyboardSurface` + the shell wiring (`openKeyboard`, gesture routing, planes,
  depth rail, wheel/silent/relayout/emergency cancels with the draft kept, a replica line
  commits through it) + Settings → Global → Keyboard + Tmux "Type…" (its quick keys as the
  live row) + Files rename/new-folder pre-filled; `QbtClient` (API 2.11 — the 5.x verbs, keys
  read from source), `TorrentLeech` (+ a stdlib `Html` reader; every parse refuses drift
  loudly), `LocalTorrentsProvider` (poll loop, event diff, the persisted announced set, epoch +
  sequence), `TorrentsNet` (the win channel: version-cursor snapshots, event replay),
  `TorrentsWindow` (five levels, three menus, two documents, six filters, the speed history),
  `ScriptedTorrents`, `IconKind.TORRENTS`, the Files `path:` deep link, registration on both
  hosts, SelfCheck + Snapshot walks. The unused Global notify rows (SMS/Mail/Music) are gone.
- **What the harnesses caught**: the transfers cursor was a bare index into a LIVE list — an
  add under the wrap-end menu row moved the menu away from the cursor (the selfcheck's second
  menu visit), and the first fix chased an empty list's menu row to the end (the core test).
  The cursor now follows its row's identity (hash, or the menu row) across snapshots.
- **Measured (selfcheck)**: transfers list 9.0 % ink, details 6.4 %, the open keyboard 9–11 %.
  Battery at hand-off: core **220** · desktop 9 · selfcheck **89** · snapshots 26
  (8 new, looked at) · lint 0 · design shots byte-identical. APK **18/0.18** staged.

### 23.1 The review loop — round 1 (2026-09-01, night)

Five fresh reviewers over the whole build (keyboard + wiring · the two HTTP clients against
the qBittorrent 5.1.4 source and the live TorrentLeech fixtures · provider + channel · the
window · glue/harnesses/docs). ~55 findings; every one re-verified against the code before a
fix; all real ones fixed, the doc mismatches corrected, one recorded as a design exemption.
The ones that mattered:

- **The provider listener leaked on every desktop stack swap** (standby → BLE → handback): a
  stopped shell's queue was fed one snapshot per poll for the life of the service.
  `TorrentsWindow.detach()`, called from `DesktopStack.stop()` like tmux's, plus the focus
  release.
- **The tracker's NFO landmark matched a commented-out template** that precedes the real
  element on the live page (found by running a port of the reader over the real page): comments
  are stripped before any landmark search; a listing row without `fid`/`name` is now refused
  loudly instead of silently dropped; `+` in a release name is no longer decoded as a space;
  multi-word tags survive; an HTML answer on the JSON endpoint counts as a refused session.
- **Announcements**: the announced stamp is kept across a removal (a qBittorrent restart's
  partial list re-added everything and would have announced all 38 finishes at once); "done" keys on
  the completion stamp alone; a host restart's fresh log is replayed to a phone that was
  connected before, while a phone's first contact still adopts the current sequence; the host
  says `truncated` when its ring no longer reaches back; a `snap` counts a new epoch as changed.
- **The window**: the categories list called the provider from a paint (a blocking channel
  request on the phone's loop — the L1 class) → the static table; a live-synced record now
  reloads the open document at once (`restoreStateLive`) and bumps the sequences so an
  in-flight answer cannot land on the restored item; restored cursors/tops wait for their
  content instead of being clamped by the first paint; the Poll row no longer re-arms the
  focused pace from Settings; an empty listing retries on its pacing; the unrecoverable
  delete sits at index 2 behind a spacer; from DETAILS the actions menu rests on Refresh.
- **The keyboard**: live keys wrap onto a second row up to twelve and more is refused loudly
  (Tmux's fourteen defaults silently lost Tab and q); Tmux sends only its non-character quick
  keys, harmless ones first; every label is sized to its cell; the prompt is fitted; uncovered
  glyphs display as `?` one per UTF-16 unit so the caret never drifts; the Tab key left the
  symbol layer; a shell stop, a keeper restart and a window commit drop the keyboard with the
  draft handed back.
- **Smaller**: a mid-body HTTP failure is a failure (never an empty answer → "format changed");
  a refused qBittorrent login latches (five failures ban the address for an hour); `Fmt` is
  locale-fixed; hashes/fids are encoded in URLs; the cookie jar is created 0600; `Max-Age=0`
  deletes a cookie; the private-tracker username left the fixtures; the scripted listing's
  ages are relative so the snapshot scene does not drift by the day; `.kotlin/` untracked.
- **Recorded exemption**: a replica-typed line searches without a confirm — a read-only query.

New pins: `TorrentsTest` (re-add with the same stamp is silent, a new stamp announces; the
host-restart replay; the details menu's harmless row 0; detach removes the listener) and
`KeyboardTest` ×18 (live rows chunk 8 → 4+4 and 13 is refused; a seven-row board fits every
Size; uncovered glyphs display as `?` without moving the caret; a long prompt stays in the
box; a shell stop hands the draft back and the restart has no keyboard).

### 23.2 Round 2 — the round-1 fix diff, fresh eyes (2026-09-01, late)

Three reviewers over `28997a8..73fdf81` only. Two HIGH findings, both in the window, both
real: **the listing fetched every page of a category while the cursor rested on row 0** —
the panning list wraps its tail rows above the cursor, so the paint-time page demand from the
Loading row re-fired on every repaint (an unbounded chain of tracker requests; paging now
follows the CURSOR, never a painted row, and the row list is cached); and **a restored
transfers cursor was steered by a stale details hash** (or lost when the snapshot came late) —
the row's hash is saved as `cursorHash`, restored one-shot with the index as the fallback.
The rest, all verified and fixed: a live-synced record now reloads only while focused (an
unfocused window was refetching a tracker page per peer save and painting its op word onto
whoever was focused) and keeps an unchanged document's content; a restored document top waits
for both the transfer and its file list; `back()` clears an abandoned page's flags and the op
word; the `tl:` deep link enters a clean listing; MenuSurface sanitizes its own strings with
its own rasterizer and specs (the caller's face is not the chrome's); Sort/Refresh only inside
a listing; the shell's shutdown deactivates the focused window; the stack stop detaches in a
`finally`. Keyboard: the pan bound accounts for the shifted text (glyphs before the caret were
cut and the caret drawn past the mark); a too-long live row is refused BEFORE the surface
opens (the throw after `open = true` left a half-open keyboard); each live row is headed by a
requester-marked harmless key; Tmux says which quick keys stayed off the keyboard; the cover
cache is cleared per open. Files: the deep link clears its pending select on every ascend,
accepts `/` and a trailing-slash folder. Providers: `pollOnce` is serialized; a refused login
has its own state line and its reason is logged when it changes; a maintenance page in place
of the listing is reported, with re-logins paced to one a minute (a genuine login form still
gets one login per request); the baseline uses the stamp rule; the percent-decoder wants two
hex digits; PHP's hyphenated `Expires` is understood; the rating widget leaves the
description. Doc mismatches corrected in `TORRENTS.md` / `DESIGN.md` §4.8 /
`IMPLEMENTATION.md`. Accepted, recorded: one "remote" focus key for all channel connections
(a stale connection's end drops the live driver's pace for at most one poll interval); the
keyboard's 576 px key field assumes the full-width content area (a narrower calibrated safe
rect is not configured today).
Battery after round 2: core **219** · desktop 9 · selfcheck 89 · snapshots 26 (refreshed) ·
lint 0; the service ran the round-2 jar, APK 18/0.18 re-staged.

### 23.3 Round 3 — the round-2 fix diff (2026-09-01, late)

Two reviewers over `73fdf81..4f5e6e0`. One HIGH, real: **a listing whose only content row
was the loading row could never re-demand its page** — the list kit paints the cursor row
through the lens, never through the row painter, so a failed first page (or a `tl:` deep
link's back target) sat forever; the demand now runs from the window's `view()` on the loop
and from the paced retry itself, and never while unfocused (a switcher preview renders the
window too — it must never issue a tracker request). The rest, verified and fixed: a keeper's
same-instance restart registered the provider listener again (the round-1 leak through the
restart path — listeners are idempotent now, on both providers and in the window); the Tmux
"keys off the keyboard" notice named the wrong keys; the stack stop's `finally` did half the
job; a restore's sequence bumps orphaned an in-flight load's op word; a live record's
pending cursor waited for a NEW snapshot object (minutes on an idle phone) instead of resolving
against the one at hand; a cursor saved on the menu row restores to the menu row; the first
snapshot (empty or not) consumes the pending; tracker logins are paced to one a minute on
every branch and a refused login latches for the process; a failed page no longer applies a
restored top against the placeholder; recents/search from the browse side set their back
target; a zero-result search says `no results`; the pan start never splits a surrogate pair;
the Files deep link clears an abandoned ascend's cursor. Pins added for the refusal-before-open,
the harmless row heads, the failed-first-page retry, the login latch and the paced re-login.
Battery after round 3: core **220** · desktop 9 · selfcheck 89 · snapshots 26 (refreshed) ·
lint 0; the service runs the round-3 jar, APK 18/0.18 re-staged.

### 23.4 Round 4 — the round-3 fix diff; the loop's last round for now (2026-09-01, late)

Two reviewers over `4f5e6e0..980d832`. Adam, mid-round: *"this is the last review for now"* —
so this round closes the loop rather than a clean sheet. Nothing HIGH; the real ones, all
fixed: the tracker's empty-jar login path was the one unpaced way in (a failed POST left the
jar empty and the 5 s listing retry posted the credentials again — the pacing now lives in
`login()` itself, on every path), and the refusal latch fired on ANY 200 HTML answer to the
login POST (a maintenance page would have latched a healthy account for the life of the
service — it latches only on the login form now); a save made before the first snapshot
marked the transfers cursor as "on the menu row" and that outranked the saved index on the
next restore; the Tmux "keys off the keyboard" notice put the names where the title cell cuts
them; a live-synced Files record could inherit a stale ascend's cursor steer; a search launched
from inside a transfers-side listing lost its back target; the paced retry was gated on the
wall clock; a restart resolved its restored transfers cursor only on the next changed push; a
restored listing cursor beyond page 1 never fetched its page. Pins added for the surrogate pan
(a rasterizer that refuses malformed UTF-16), the refusal of a second ask while a keyboard is
open, and the retried page landing. Adjacent, fixed the same way: the phone rasterizer's
coverage check iterated UTF-16 units, so every emoji counted as uncovered on the phone — it
checks code points now.

**Accepted, recorded (not fixed):** the status bar's op word has no owner — a window's
"idle" on a live apply can blank another window's running op word (a shell-side owner is a
later item); one "remote" focus key per channel (a stale connection's end drops the live
driver's pace for at most one poll interval); the keyboard's 576 px key field assumes the
full-width content area (a narrower calibrated safe rect is not configured today).

**Battery after round 4:** core 221 · desktop 9 · selfcheck 89 · snapshots 26 · epub-check
clean · lint 0; APK 18/0.18 and the jar restaged, the service restarted on it.

**The loop's record:** rounds 1–4 → ~55 · ~30 · ~15 · ~14 findings, every one re-verified
against the code before a fix, the real ones fixed and pinned, the doc mismatches corrected.
Round 4 still found real defects in round 3's fixes, so this is a PAUSED loop, not a converged
one — Adam's call ("the last review for now"); the next session's first review pass should
start from the round-4 diff (`980d832..HEAD`).

**Next as written then (superseded by §24 — Music was built the same night):** on-glass verdicts — install 0.18, then the keyboard's feel (row
pitch at 288, the highlight, the text-line pan, stay-in-row), the transfers list, a real
done-notification, browse/search/add against the live tracker; the resumed review pass; the
Reader transitional cleanup (unblocked); then the next window — Music, design discussion and
verdicts before code.

## 24. Music — designed, built and reviewed (2026-09-02)

Two rounds of verdicts with Adam (`MUSIC.md` §1, 29 rows), the facts verified read-only
(§2: G2CC's music system is taken over whole — Postgres `g2cc`, Qdrant, the 8.1 GB cache,
the enrichment package, yt-dlp; the phone plays, the PC serves), and the full build plan
written at max effort (§5–§13: module map, the `MusicLibrary`/`MusicPlayer` contracts, the
window channel + a Range-capable media endpoint on a new `mediaPort` 7404, the APK sink with
hold-my-volume and boost, Music Mode as a shell `Mode.EXCLUSIVE`, the lyric scheduler on the
phone's real position, precomputed visualizer data, six milestones with a commit after each).
Decisions that reverse older records: volume is adjustable and synced (not "max + phone-
owned"); the phone speaker is an allowed output; every window works at all four heights
(`WINDOWS.md` §1); the APK stops posting errors to the phone (Global toggle, off). Also run
today with Adam's go: `REINDEX DATABASE g2cc` + `ALTER DATABASE g2cc REFRESH COLLATION VERSION`
(2.42 → 2.43; the other databases still carry their old versions — other projects' call).

### 24.1 The build (2026-09-01/02, overnight, autonomous — six commits, the battery green at each)

| milestone | commit | what landed |
|---|---|---|
| M1 host foundation | `36343dc` | `MusicModel` (types + the two contracts), the `Db` seam + `PgDb` (pgjdbc 42.7.13 over the Unix socket via junixsocket 2.11.1, peer auth), `MusicDb` (every query + the additive `lyrics.source/track_id` migration recorded in `damage_schema`), `Qdrant`, `MediaCache` + transcoder, `MediaServer` (:7404, Range), `Art`, `LibraryScan`, `LocalMusicLibrary`, `MusicNet` (service + remote with disk caches), the `WinNet` PUSH slice; `--music-check` passed against the real DB |
| M2 window | `2acf432` | `MusicWindow` (every level at four heights), `QueueEngine`, `PlayerCore` + `SimMusicPlayer` + `MirrorMusicPlayer`, `LyricsSync`; `ScriptedMusic`, the selfcheck walk, snapshot scenes 30–37; the six delegated leaf modules (Resolver + ClaudeOneShot + EmbedQuery, LyricsFetch, YouTube, Viz, `audio/` + viz.py + Enrich, MusicListener + SpotifyRemote) |
| M3 shell | `fc2fa99` | `Mode.EXCLUSIVE` (`DESIGN.md` §4.9) and Music Mode's per-height surface stack; `MusicModeTest`; selfcheck at 480/Bars + 288/Scope; scenes 38–39 |
| M4 APK | `67d65b8` | `AndroidMusicPlayer` (ExoPlayer + media3 session over a ForwardingPlayer), `TrackCache`, media3 1.5.1, the manifest's mediaPlayback type, `Prefs.mediaPort`, the service registration, the Global **Phone notifications** switch, the strip's `music access` grant; APK 19/0.19 (20/0.20 after §24.2, 21/0.21 after §24.3, 22/0.22 after §24.4, 23/0.23 after §25) |
| M5 host features | `72cae3d` | the lyric-sources choice pushed to the host (one fetch chain per choice; a source FAULT throws, a MISS stands until the sources widen), `Enrich` + `LyricsFetch` wired, `musicAudioDir`; `--music-check` runs the deterministic lanes and one real viz precompute |
| M6 docs + staging | `178603f` | this record, `MUSIC.md` corrections, `IMPLEMENTATION.md` Music, `DAILY.md` Music, `REMINDER.md`, `WINDOWS.md` (five precedents), memory; jar + APK staged, the service restarted |

**The battery at M5 (all green):** core **315** tests (was 221 before Music) · desktop 9 ·
selfcheck **134** checks · snapshots **36** · epub-check clean · lint 0 · `--music-check` all
pass against the real `g2cc` (2,981 tracks, catalog 1,440 KB in ~70 ms, legacy cache 20/20,
Qdrant 2,981 points, lanes 1 answer, one viz blob) · `:phone:assembleDebug` 0.19 (0.20 after §24.2, 0.21 after §24.3, 0.22 after §24.4, 0.23 after §25).

**Delegation:** six Opus agents in isolated worktrees, each with the fixed interfaces
(`Plugins.kt`, `MusicModel.kt`) and its own tests: LyricsFetch ×24 · YouTube ×13 · Resolver ×19 ·
Viz ×12 · Enrich ×11 · SpotifyRemote (compile-gated, no JVM test possible). Their findings that
changed the plan: `--bare` cannot authenticate an OAuth login (measured — the one-shot runs
without it); LRCLIB's search returns whole rows with lyrics inline; NetEase's public search is
rate-limited per address and needs its three cookies; the Musixmatch route answers a captcha
401 from here (behind the toggle, off); the hearing-limiter notice text is modeled on AOSP's
strings and logged verbatim on first sight; `PlaybackState.getLastPositionUpdateTime()` is an
`elapsedRealtime` instant.

**Decisions made inside the plan (recorded so they are not re-litigated):**
- Postgres from core through a `Db` seam; the JDBC driver is :desktop-only — the APK never carries it.
- `MediaServer` is a ServerSocket HTTP/1.1 server in core (no `com.sun.net.httpserver` — Android-clean); a malformed Range answers 200 with the whole file.
- Profiles: High = Opus 128 k mono / 192 k stereo, Standard = 96 k, Saver = 48 k, Lossless = passthrough; cache dirs `<quality>-<mono|stereo>-<loudnorm|flat>`; the legacy G2CC cache IS `standard-mono-loudnorm`, read in place (20/20 sampled keys map).
- `--music-check` applies the one additive migration (the service does at every start) and says so; everything else it does is read-only, plus one viz blob into our own cache dir.
- The catalog is one JSON blob (1,440 KB for 2,981 tracks, built in ~70 ms), cached on the phone; its version is a SHA-1 over the catalog's SHAPE — track / artist / album / playlist counts and the newest track, playlist and membership stamps, never lyrics or play history, which change with every play (§24.2).
- The card repaints on a 5 s pace while focused (a lens repaint is a few hundred bytes; 1 Hz would be ~10 % link duty); Music Mode's card every 5 % of progress; lyrics on line change, the visualizer at its own rate — every surface one rect.
- Track-change notices fire only while the Music window is NOT on screen (the card shows the change there); verdict 10's default stays on.
- Seek rests on "+10 s"; Replace-queue-while-playing confirms; Save-over asks twice; Delete playlist is Cancel-first with the unrecoverable row at index 2; a replica-typed line is an Ask staged behind a confirm.
- yt-dlp grabs as `opus` (the indexer's extension set has no webm), with `--embed-metadata --max-filesize 300m --newline --progress --print after_move:filepath`, the URL after `--`.
- The media3 session sits over a `ForwardingPlayer`: next/previous route to OUR queue (ExoPlayer holds one item). Auto output refuses to start with no external output; the speaker plays only when chosen. Boost = `LoudnessEnhancer` gain 2000·log10(pct/100) mB, capped at 1200; off on every open and stop.
- The desktop mirror hands the phone's player record back byte-equal (no LWW re-stamp) and refuses every transport command with "playback needs the phone".
- Exclusive mode restores only when its window is registered on the restoring host.
- The scripted viz data spans seven minutes — a renderer past the end of its data paints the resting form by design (a 30 s blob left the first Music Mode scenes flat).

**Measured vs modeled:** everything about glass is still MODELED — the Music Mode ink (10.9 % at
480 with Bars, 6.3 % at 288 with Scope) and every latency come from the byte-exact sim. The
Bluetooth lyric offset, the achievable visualizer rate, the limiter's real notice and the Spotify
cold start are the phone's measured items (`MUSIC.md` §12, `DAILY.md` Music).

**Next:** install 0.21 + the grants (`DAILY.md`), the measured items — and the still-owed
Torrents/keyboard on-glass verdicts. The review record is §24.2 (round 1) and §24.3 (ultrareview).

### 24.2 The review loop — round 1 (2026-09-02, morning, autonomous)

Docs sweep first (`17a9a9b`, every doc current at the M6 commit), then `/code-review high` over
`8d5e30b..HEAD` (the whole Music build). **10 ranked findings (9 CONFIRMED, 1 PLAUSIBLE) + 17
one-liners; every one re-verified against the code before a fix; 2 declined as non-issues**
(the `notifyMusic` gate is the shell's pre-existing rule; a helper-duplication note is quality,
not a defect). Nine defects of my own from a read-through of the window went in first (the
`runOp(verb, then, op)` signature — a trailing lambda bound to the wrong parameter; the cursor
set by code re-read as the user's; a deep link cleared the stack before validating; nearest-option
rows; the play-next move index when the source sits above the cursor; demands gated on
`active || exclusive`). The round's fixes, by weight:

- **The APK could not have played at all**: targetSdk 35 refuses cleartext HTTP by default, so
  ExoPlayer and the prefetch store would have refused `http://beardos:7404`. `usesCleartextTraffic`
  is now declared, with the comment (token-gated endpoint, tailnet transport).
- **Boot**: Android 15 refuses a foreground service started from `BOOT_COMPLETED` whose types
  include `mediaPlayback`. The service now starts as `connectedDevice` only and adds
  `mediaPlayback` when playback first engages (`AndroidMusicPlayer(onEngaged)`); a refusal logs
  loudly and playback continues under the first type.
- **The player record**: `persist()` forced `play = STOPPED` and wrote a `stamp`, so the truth
  never travelled and value-equality never held; it now writes the real state + `posAt` and no
  stamp — `restore()` still never auto-plays, and it no longer overwrites the sink's volume /
  held level with the record's (the phone's real level is the truth). The our-own-echo marker
  now clears even when the level already matches (a later user move to the same value read as
  ours). Sleep's menu "current" is by the choice, not a label prefix.
- **Lyrics**: a track change while in Music Mode never reloaded the lyrics (only the LYRICS frame
  on top did); `paintExclusive` now demands them and `applyState` reloads when exclusive. The
  scheduler arms a flush `LYRIC_DISPLAY_MS` early but the painter chose the line by the raw
  position, so an early flush painted the OLD line and re-armed — the painter now uses the same
  lead. Plain lyrics were paged as 12 raw lines, so wrapped lines past the canvas were never
  reachable — pages are now made of the wrapped lines that fit (cached per width + face), in
  the window and in Music Mode alike. The Music Mode current line, when it overflows the large
  face, now draws whole in the smaller face at HEAD level instead of the continuation mark.
- **The catalog**: `hasArt` was keyed with mtime 0 (art never refreshed after a retag, or
  re-extracted every scan depending on the branch) — the catalog query selects the real mtime.
  The version fingerprint included lyrics and play_history max timestamps, so every play made
  the phone re-download a 1.4 MB catalog — it is shape-only now (counts, the newest track /
  playlist / membership stamps, and the count of FOUND lyrics, which flips `hasLyrics`; never a
  fetch stamp or play history). RECENT was built synchronously
  from the catalog's list (stale until the next catalog refresh) — it is a loaded frame through
  `MusicLibrary.recent(n)` (a `recent` op; the remote falls back to the cached list off-line).
  The scan's stat failure was silent (counted + logged now); an EMPTY host catalog is no longer
  served as an answer (throws). The viz blob was built inline on the channel thread — it is
  async, pushed as `viz` (`Listener.vizReady`), and Music Mode marks the viz due when it lands.
  The remote's disk caches were unbounded — `evict()` keeps viz 150 / art 3,000 / lyrics 3,000.
- **Phone**: the listener rule `com.google.android.` matched EVERY Google app's notifications
  (systemui / settings / `com.google.android.settings` now); route loss fired on ANY external
  audio device removal — a dongle unplugged elsewhere stopped the music — so it now looks 500 ms
  later at whether playback actually stopped; `preferredHeight ?: 480` made the Size row's
  "global" unreachable (480 is the default, global is stored as 0 — `MUSIC.md` §8).
- **Small**: `requestRepaint` in exclusive mode flushed the full canvas (delta now); `PgDb.tx`
  skipped the rollback on an `Error`; a 0-byte file answered 206 with `bytes 0--1/0` (200 now);
  `MediaCache` joined a transcode thread that could be lost (a `CountDownLatch` per output,
  counted down in `finally`); `LyricsFetch.kt` carried NUL bytes (git saw a binary file);
  `viz.py` normalized silence to 15 instead of 0; `Enrich`'s header claimed a cap that
  `profile.py`'s own pass has.

Pins added: the record carries the play state and never the sink's volume; the echo/limiter
sequence; play-next's move; route loss while paused; the 0-byte Range; persist round trip.
**Battery after the round (all green):** core 315 · desktop 9 · selfcheck 134 · snapshots 36 ·
epub-check clean · lint 0 · `:phone:assembleDebug` **0.20**; jar + APK staged, the service
restarted on the round-1 build. The next pass was ultrareview (§24.3), the same afternoon.

### 24.3 Ultrareview — two cloud runs over the whole build (2026-09-02, afternoon)

Adam wanted the cloud multi-agent review (`/code-review ultra`) tried on Music. It reviews the
current branch against a base branch and caps the diff at 8,000 lines; the whole build is 128
files / 17,149 lines from `pre-music` (= `8d5e30b`). So two synthetic pairs were built: a base
commit holding everything on main EXCEPT the files under review, with a review branch on top
whose tree is byte-identical to main — the reviewer sees the full code, the diff is only the
chosen files. Run 1 (`music-review1` → `base-music1`, 31 files / 6,854 lines): the window,
player, shell exclusive mode, phone, desktop wiring, viz.py. Run 2 (`music-review2` →
`base-music2`, 24 files / 6,396 lines): the DB, net, library, cache, media server, the leaf
modules, PgDb and their tests. Left out: the docs, snapshots, the copied G2CC enrich package, and
three window test files. The branches were temporary and are deleted. Every finding was
re-verified against the code before a fix, with a pin each.

**Run 1 — 3 findings, all real (`d6bb08b`):**
- **Spotify cold start could never work**: the APK declared no package queries, and on Android 12+
  package visibility hides Spotify from both lookups `coldStart` uses, so it always logged "not
  installed". The passive session path hid it (the listener grant exempts active sessions only).
  `<queries>` for `com.spotify.music`.
- **A Play-from during a pending Radio / Library-random fill was stepped past** when the fill
  landed carrying "advance when you land". `PlayerCore.pickGen`: the fill keeps its rows and drops
  the stale advance. `SimMusicPlayer.deferAsync`/`flushAsync` make the gap testable; the pin
  fails on the unfixed code (cursor 1, expected 0).
- A constant-true `takeIf` in `applyBoost` that read as a check and checked nothing.

**Run 2 — 5 findings, all real, all rated nits by the reviewer (the closing commit):** the host
re-spawned `viz.py` on every ask for a track whose build permanently fails (a `.miss` marker +
an in-memory set now, keyed like the blob so a re-encoded file is probed afresh — the `Art`
pattern); `LibraryScan` skipped only a hidden directory's own entry while `Files.walk` still
descended into it (`.Trashes/x.mp3` got indexed — `pruned()` checks every segment below the
root); `setLyrics` writes an empty artist as `(unknown)` but the legacy-key reads bound the raw
empty string (both forms accepted now); `Rules.exclusionNote` blamed "sound effects" when the
spoken-word filter was the cause; the remote's art/viz/lyrics cache writes were not atomic (a
short art file renders as black pixels with no throw — `atomicWrite` now, the catalog's own
pattern).

**Found by the closing docs audit, fixed in the same commit:** the shell's `noticeAllowed` still
gated the `music` source on the Global `notifyMusic` field, which has had no row since Torrents
(2026-09-01) — but APKs up to 0.17 (0.16 is the one installed) carried the Global "Notify · Music"
row, so a persisted "off" from it would have silenced every Music notice with nothing to turn it
back on. The gate is removed; the window owns its six Notify rows (`WINDOWS.md` §1). The audit
also corrected a systematic dating error: every Music commit is stamped 2026-09-02 (the build
ran 03:08–04:44), so "2026-09-03" and "09-02/03" in docs, comments and memory were one day
ahead and are fixed everywhere.

**Verdict on the tool:** fresh eyes found what the author's review had not — two of run 1's
three were worth the run alone — and none of the eight touched a design decision. It lists
findings only (fixes are opt-in via `--fix`, not used); it reads `CLAUDE.md` and `REVIEW.md`.
Three passes in one day converged on nits: a further pass is optional, not owed.

**Battery at close (all green, measured):** core **317** · desktop 9 · selfcheck 134 · snapshots
36 · epub-check clean · lint 0 · `:phone:assembleDebug` **0.21**; jar + APK staged, the service
restarted on the closing build.

---

## §24.4 The silent-playback session, and the NOW PLAYING root (2026-09-03)

**What Adam reported:** Music "did not play music. It seemed to think it was playing, but no
sound came out of my earbud." Tmux, Files and Torrents all worked.

**What actually happened — measured from his own machine, not inferred:**

- `~/.damage/media-cache/high-mono-loudnorm/` held **ten tracks transcoded 18:22–18:38**, the
  last three **3.5 and 5 minutes apart** — song-length gaps, so the queue advanced in real time.
- The synced player record (`window.music.player`, stamped 18:50:30) read `play: PAUSED`,
  `engine.index: 3` of a 55-track "Power Metal" queue, `posMs: 146651`, `output: 'auto'`,
  `profile: high-mono-loudnorm`, `holdVolume: true` — and **`volume: 8`**.

So the whole chain worked: the PC served, the phone downloaded and decoded, four tracks played
end to end, and the state was accurate. **The phone's media stream was at 8 %** — on Android's
curve roughly step 1 of 15, against loudnorm'd −16 LUFS content. Inaudible.

**Damage did not set it there.** Only three paths write the system volume (the ring's Volume
canvas, the Settings row, the limiter's restore) and all are user-driven or upward-only;
`PlayerCore.restore` deliberately never applies a persisted volume — the level is the phone's
own truth, read from the sink at start. It read 8 % and played into it.

**The defect was that nothing said so**, on a device whose whole premise is that you are not
looking at your phone. Three fixes:

1. **`PlayerEvent.QuietStream`** — playback starting at or below `PlayerCore.QUIET_PCT` (10 %)
   raises a notice on glass, once per playback RUN (a per-track notice would nag every four
   minutes); the latch clears on stop, on queue end, and when the level comes back up.
2. **The output is restored by STABLE identity.** `onRestored()` matched a saved output by
   `AudioDeviceInfo.getId()` — a per-connection handle that changes on every reconnect and gets
   reused for other devices — and ignored `setOutput`'s `false` return, so a stale id silently
   selected nothing while the UI kept naming the device. The record now carries `outputName` +
   `outputKind`, matching is name+kind first, and a miss raises `PlayerEvent.OutputGone` and
   falls back to Auto. (It did not cause this session — his record said `auto`.)
3. **The media endpoint logs nothing on success**, so "did the phone ever fetch audio?" was not
   answerable from the PC log; it had to be inferred from cache mtimes. Noted, not yet fixed.

### The root is NOW PLAYING (verdict 4 reversed)

Adam, the same session: *"lets put the queue as a menu option rather than the main screen …
the main screen should be a useful, really nice looking Now Playing screen. That way i can see
what is playing and where in the song it is and what the volume level is at etc."*

`Kind.NOWPLAYING` is the root frame; `Kind.QUEUE` became a pushed level reached from a **Queue**
menu row (a **Track info** row joined it, since the current track's info used to be one tap away
on the queue row menu). The root is a Canvas: **scroll = volume live, tap = the Music menu**,
no cursor. Four TOP-aligned bands (Adam's fit loses the bottom): identity with art at
160/120/88 px by height · elapsed/progress/total · the level with the queue position, **drawn
HOT at or below 10 %** so the 8 % session announces itself without a notice at all · the current
synced lyric line when one is loaded and it fits.

Everything that addressed the queue by POSITION now addresses it by KIND (`queueFrame`), a
pre-2026-09-03 record with `QUEUE` at position 0 maps onto the new root, and the deep link
`t:<id>` opens the queue level rather than moving a cursor the root no longer has.

**Harness lesson:** five tests and the whole selfcheck Music section broke because they selected
menu rows by COUNTING notches; one new row moved everything. `Shell.menuLabels` / `menuCursor`
are now exposed and every harness picks rows **by name**. An unbounded wait added to the test rig
hung the suite once — every wait in a harness is bounded, loudly.

**Battery (all green, measured):** core **319** · desktop 9 · selfcheck **139** (the count is
PASS lines — 57 `check(` assertions plus the `awaitTrue` convergence waits, several of each
inside the two-height Music Mode loop; it was 134 before this change, which added one `check`,
the queue-level ink, and moved which waits announce themselves when the harness started picking
menu rows by name) ·
epub-check clean · `--music-check` all pass · lint 0 · **APK 22/0.22 staged** — the first build
carrying the Now Playing root and both player fixes; 0.21 superseded, and 0.22 in turn by
23/0.23 with §25. Snapshots renamed
`30-music-nowplaying-480` / `36-music-nowplaying-288`.

### The review pass on the new code (same session)

Seven issues in what had just been written; all verified, all fixed:

1. **The output restore matched ANY same-kind device** as its second fallback —
   with the earbud and the glasses both "bluetooth" that is a coin flip, which is
   the very defect the change replaced. It now takes an exact product-name+kind
   match, accepts a same-kind device ONLY when the saved name *was* the kind label
   (no product name was available) and exactly one exists, and otherwise refuses
   loudly to Auto, clearing `preferredDevice` on the way.
2. **The queue level opened on row 0** instead of resting on the current track —
   `push()`'s cursor table had no QUEUE branch, so §8.1's "at rest the cursor sits
   on the current track" was lost when the queue stopped being the root. It rests
   again, and seeds `cursorSetByMe` so the follow-the-identity logic does not read
   the fresh frame as "the user moved".
3. **The Queue menu row could stack a second QUEUE frame** on top of the one
   already showing (the queue level's own wrap-end row reaches that menu). Guarded.
4. **`QuietStream` and `OutputGone` were title notices only** — invisible when
   playback starts from an earbud tap with Music off screen, which is exactly when
   a silent stream is hardest to explain. Both now follow the `Error` idiom: the
   notice on screen, a notification when not.
5. **My `QUIET_PCT` doc comment displaced `LIMITER_DROP`'s**, leaving one constant
   documented as the other. Restored.
6. **The queue position was an unbounded right-align** (the F2 class, twice fixed
   elsewhere): a long mode label with a big queue walks left over the level
   readout. Measured, then fitted.
7. **A dead branch and a wasted fetch:** the empty-state's "stopped" arm is
   unreachable (`entry` is `queue[index]`, so a stopped player with rows paints the
   full surface), and `npArtPx` started at 96 while the shipped 480 height wants
   160 — one wasted art request per session. Both corrected.

Also dispelled by reading rather than assumed: the desktop mirror cannot emit the
new events (`MirrorMusicPlayer` does not extend `PlayerCore`), player events already
marshal to the shell loop, and `AndroidMusicPlayer.positionMs()` is off-thread safe,
so the painter may call it.

⚠ **Watch:** Now Playing measures **14.0 % ink** at 480 with the harness's synthetic art, against
the 15 % list budget. Real album art may trip it; the answers if it does are smaller art or
reclassifying the surface as a canvas (Music Mode's note allows 30 %).


## 25. The whole-codebase review (2026-09-03, late)

Adam: *"run a full, deep, thorough code review of this entire project top to bottom … then
verify each one is really an issue … then fix it … then double check each and every fix."*
Every finding below was **reproduced before it was fixed**, and every pin in
`core/src/test/kotlin/wm/damage/core/Review20260903Test.kt` was **confirmed to FAIL against
the unfixed tree** and pass against the fixed one. Commits `1f9fa4d` (fixes) and `c400d0b`
(APK 23/0.23), pushed; the service was restarted onto the build.

### 25.1 The oracle — how the invisible ones were found

The mirror/divergence check (§8.2) compares the compositor's **belief** to the **glass**. A
bug that writes wrong pixels into the shadow *and sends them* makes the two agree, and the
check is blind to it. The review's oracle instead recomputes the per-lens **truth** of
`comp.composed` under `comp.planes` — split the panel by every plane, keep the pieces that lie
in a region, paint each at its own shift, far first, exactly as `Compositor.renderTruth` does —
and asserts it equals `comp.expectedLens(left)` after every settle. A 900-step random-gesture
walk with that assertion found #2 below.

⚠ **Split by plane PIECES, not raw plane rects.** A naive oracle that shifts whole regions
reports ~32 false pixels at the lens-band edges, because the lens rows belong to the lens
plane and are never painted at the content plane's shift. That false positive cost a pass;
do not re-introduce it.

Reusable as-is: the walk drove Reader and Music through ~1,600 random gestures with the
invariant, plus quiescence and a no-ERROR-status check, and both came back clean after the
fixes.

### 25.2 The ten findings

1–2. **A plane-0 delta could carry another plane's pixels** — `Compositor`. Two unguarded
   paths: `partition()` step 1 merged the two plane-0 GUTTER rects with a `{ true }` predicate
   into one full-width `d=0` box across the content plane, and `coarsen()` unioned REMAINDER
   rects by row band with no knowledge of the plane map at all. Those pixels land at the wrong
   shift on **both** lenses, outside the scanned `area`, so the repair loop never sees them —
   and belief and glass agree on the wrong thing, so nothing reports it. This is exactly what
   `partition()`'s own round-6 comment says must never happen. Fixed with `remainderPieces()`
   (split by planes, keep the plane-0 parts) and the same predicate step 2 and `price()`
   already used. **Rect economy unchanged** — measured 8 bytes *cheaper* over boot + open +
   25 notches. Reachable but rare in the daily shell (the remainder seldom has >1 rect); a
   latent hole in the mechanism, closed.

3. **The silent notice body was wrapped to the WINDOW box's width** — `Notifications`. The
   silent/exclusive box is 200 px; `bodyLines` always wrapped to `notificationMax.w − 16`
   (232 px) and `paint` drew the body unbounded at `full.x + 8`. Result: up to 40 px past the
   box — cut on the glass with no continuation mark (NO TRUNCATION), and the part outside the
   damaged rect was ink in `composed` nothing would ever send, which surfaces later at a
   keyframe (the "undamaged composed ink" class already fixed twice elsewhere). Measured end
   to end: 240 lit px outside the box; the sim glass showed 110 of the 230 px `composed` held.
   Fixed: `Notifications.SILENT_W`, `bodyLines(n, l, silent)`, fitted draws, and a drawn mark
   when the silent form's one line is not the whole body.

4. **The Reader shipped tofu for 14,315 characters of Adam's real shelf** — `Epub`. Numeric
   references in 0x80–0x9F skipped the Windows-1252 remap HTML5 mandates, *and* the files
   literally hold those code points: a byte check found `C2 97` (UTF-8 for U+0097) exactly
   where an em dash belongs, beside correctly-encoded `E2 80 99` quotes in the same sentence —
   the books were transcoded cp1252-as-latin-1 long before we saw them. `Epub.fold` now maps
   the C1 range through `CP1252`, folds **U+2011** (284×, missing from all four locked faces)
   to `-`, and drops the zero-width formatters (40×, invisible by definition, so a `?` would
   add junk). Character offsets are unaffected by the remap; the drops are ≤40 shelf-wide and
   Adam's one saved position still lands on the same sentence (checked).

5. **The Reader was the last window drawing dynamic text raw** — `ReaderWindow`. Book titles,
   authors, EPUB toc chapter names and the prose itself bypassed `Draw.dynamic`, so an
   uncoverable glyph was silent tofu with no log. Now routed like every other surface, and
   `paintBookLine` is fitted (the wrap measured the raw string, so a substituted glyph of a
   different width must be marked, not pushed past the line rect). **Measured on the real
   58-book shelf: 14,365 undrawable code points → 50** (2 Hebrew letters, 48 U+FFFD already in
   the source — all now a visible `?` plus one log line).

6. **The flow renderer drew terminal output raw** — `FlowRender`. The live tmux panes on
   beardos carry U+23BF / U+23F5 / U+2722 / U+273B from Claude Code's own TUI; JetBrains Mono
   has none of them. Sanitized at **layout** time (the Files viewer's shape) so measure and
   draw stay on the same string and the wrap stays exact. `TermRender` was already correct —
   it draws a deliberate hollow box for an uncovered glyph.

7. **A restored level below the top never loaded** — `MusicWindow`. `restoreState` set
   `needsReload` from `top.kind` only, and `back()` loaded nothing, so a restored stack of
   `[NOWPLAYING, PLAYLIST, INFO]` backed into a playlist level showing one bare menu row —
   forever. `ensureLoaded()` now runs on the way back. Files and Torrents already handled this
   class explicitly; Music was the outlier.

8. **The desktop mirror published a removal TOMBSTONE for the player record** — `MusicWindow`
   + `Shell`. `MirrorMusicPlayer.persist()` is `{}` until the phone's first record arrives,
   and `saveSubState` reported it. An empty blob **is** the shell's §16.4a tombstone, and
   `window.music.player` is syncable — so on a store without the key the desktop wrote `{}` at
   a fresh stamp and LWW would push a deletion of the phone's real queue. Verified end to end
   into the store. Fixed at the window (never report an empty record) and closed as a class in
   the shell (an empty sub-record is refused loudly, once per key per session).

9. **The quiet-stream latch ignored its own remedy** — `PlayerCore`. The notice says *"scroll
   here to raise it"*, but `setVolume` did not clear `quietWarned`, so after raising and later
   dropping back under 10 % a new track played silently with nothing said — contradicting the
   documented "cleared when playback stops or the level comes back up".

10. **Two catch-and-swallow blocks** — `QueueEngine.fromJson`'s torn row (dropped with no log)
   and `PlayerCore.stop`'s boost reset, whose comment claimed it was "logged above" when it
   was not. Both made loud.

### 25.3 What the review checked and found clean

Read in depth: geometry, the compositor, the transport brain (`CfwTransportBase`, `Emit`), the
wire layer, gfx/codec, text/style, every shell surface, Reader, Music (window, player core,
queue engine, mirror), and the phone's `ShellService` / `AndroidMusicPlayer`. Verified live on
this machine, not just in fixtures: the real 58-book shelf, the real tmux (4 sessions, a
58-line capture flow-rendered), the real filesystem (9 locations), the live qBittorrent (39
transfers), and the real Postgres music library.

Explicitly checked and **not** defects: no timeouts anywhere in the tree; every subprocess goes
through `ProcessBuilder(list)` with no shell; all `MusicDb` SQL is parameterized (`$col` and
`$order` come from fixed literal lists); every network surface is token-gated; the 24 EPUB
images that fail to decode are all SVG, which `--epub-check` already reports honestly; the
`wide`-flush pipeline depth matches §8.2's intent; `TermRender`'s tofu box is deliberate.

One earlier hypothesis was **retracted after testing it**: the transient belief/`composed`
disagreement a walk sees is chrome legitimately waiting for the 5 s idle flush (§8.3), not a
defect — only divergence that survives that window counts.

⚠ **The honest boundary of this pass.** The seam / replica / `RemoteTransport` plumbing, the
firmware simulator, and most of the phone module were read at a SCANNING level, not line by
line — they carry their own suites (`SeamMirrorTest`, `SeamSessionTest`, `HandoverTest`,
`PathTransportTest`, `ReplicaServerTest`, `LensOracleTest`) and prior review rounds. Nothing
here was tested on the actual glasses: the pass ran against the byte-exact firmware model and
this machine's real services. A future round should start there rather than re-reading the
core.

**Battery after the fixes:** core **329** (+10 pins) · desktop 9 · selfcheck **139** ·
snapshots 36 · epub-check 58/58 · `--music-check` all pass · lint 0 + selftest · APK builds and
carries the fixes. **APK 23/0.23 staged**; 0.16 is still the last observed installed.

---

## 26. Games · Hold'em — built, reviewed twice, live-tested (2026-09-04, overnight)

Adam, going to bed: *"build it! This is an automated overnight build … build the whole thing
completely and correctly and polished all the way … run a complete code review … then do that a
second time … then test the full system in the live environment thoroughly, exactly as a user
would … then update all relevant documentation."* This is that session's record. The design and
its verdicts are `HOLDEM.md`; **`HOLDEM.md` §17 is the deviations list** and is the thing to read
if you only read one part.

### 26.1 What landed, milestone by milestone

**M1 · the shell rule and its retrofit** (`d3da21d`). Verdict 35 is general, not a Games detail:
*"Going to Games from the switcher should auto-resume … from Main should present the Games
List … this should be true of any window that has multiple base functions."* `ActivationSource`
(`SWITCHER` / `MAIN` / `DEEP_LINK` / `RESTORE`) joined `DamageWindow.onActivate`, `Shell` carries
it through `commitWindow(w, from)`, and **all six existing windows implement root-vs-resume**.
The retrofit's trap, found by the pins: Reader's Main entry left a *subfolder* open, which is
depth 2, so one double-tap ascended inside the window instead of leaving it — `goRoot` has to
reset the folder AND the cursor, and the test asserts `levelDepth() == 1` after a MAIN
activation. Music is the exception: NOW PLAYING is its root (§24.4), so its MAIN entry is not a
browse list.

**M2 · the card kit** (`fdab57a`). `Rng` (counter-based splitmix64), `Cards`, `HandEval`, `Pots`,
`Money`, `CardArt`, `HandFan`, `TableLayout`, `Seats`, `ActionLevel`, `Bankroll` — none of which
mentions poker except by example, because blackjack and hearts are meant to reuse them. The
**side-pot oracle** landed here: `pokerkit` (MIT) in a scratch venv generated 3,000 side-pot
scenarios and 2,000 hand-ranking cases, and the *corpus* — JSON we own — is what the repo holds
(`core/src/test/resources/holdem/`). Nothing third-party is vendored.

Four generator defects had to be fixed before the corpus was worth anything, each of which would
have "proved" our engine correct against nonsense: `str(card)` instead of `repr(card)`; sampling
`st.bets` after each round had already reset it (the peak of `start − stacks` is the real
contribution); a missing "show hole cards" step that left the board empty, so **every all-in hand
scored as a chop**; and an `exactPayout` invariant that 1 scenario in 3,000 disproved, which
became a strict rule plus an odd-chip drift bound.

**M3 · the engine** (`14299c7`). Streets, blinds, button, min-raise, side pots, showdown,
elimination — no UI at all. The persistence contract is the interesting part: the live table
stores only `(seed, handNo, start stacks, busted order, button, actions)` and **re-derives** the
deck, the holdings, the board, the pot and the turn by replaying the action log. A hand cannot
drift from its own record because there is no second copy of it.

**M4 + M5 · the table and the ecology** (`b9eb6b6`). The four-height table, the action / sizing /
confirm levels, the §4.8 keyboard for a custom raise, tap-to-deal, the hand history; then
`Character` (nine traits), `Mood`, `Roster`, `Background`, the standings, the shared bankroll and
the Loser Count. `--games-check` landed with them and immediately paid: it found Unlimited's
`canAfford` refusing the player his own table, fraction-of-roll Unlimited stakes inflating the
money supply 236 %, and an `ambition()` call *inside a comparator* (a non-transitive ordering —
`sortedWith` may throw on it).

**M6 · integration.** Registration in desktop `Main.kt` and phone `ShellService.kt`, Settings →
Games, 20 selfcheck checks, 13 snapshot scenes, the GAMES icon, `--games-check`, `--card-render`.
`--selfcheck` had to have its Games checks extracted into `gamesChecks()` — the method passed the
JVM's 64 KB limit and would not compile.

### 26.2 The two review passes (`8aa9910`, `20f01a8`)

Nineteen verified defects, each reproduced before it was fixed and each pinned in
`GamesReview20260904Test`. The classes worth carrying forward:

- **Concurrency around a loop-owned world.** Monte-Carlo decisions run off the shell loop and come
  back through `runOnShell`; anything they touch has to be generation-stamped (`pacerGen`) or an
  answer for a table you have left applies to the one you are looking at.
- **Registration is not restore.** `onRegistered` runs before any sub-record arrives; populating
  the roster there minted 35 characters against a fresh seed, and the restore then left them as
  strangers with full bankrolls. Free money on every start.
- **A finishing place is an ORDER, not a flag.** Two seats busting on one hand shared a place
  until `bustedAt` became a 1-based order with the start-of-hand stack as the tie-break.
- **The verb keys on `currentBet`, not on the street.** The big blind facing limpers was offered
  "Bet".

### 26.3 The live session — the part that found what nothing else could

The real desktop program under the byte-exact simulator, in a scratch `$HOME`, driven over the
browser replica: real gestures in, real 4bpp lens panels out, decoded to PNGs and looked at.
Thirteen findings, all fixed and re-verified on screen; **`HOLDEM.md` §17.2 lists them.** The two
that matter beyond their own fix:

1. **Cash out was unreachable.** It lived only on the action level, which opens only mid-hand, and
   the engine refused mid-hand — so the row existed and could never succeed. Two layers, and every
   unit test missed both because they called `cashOut` in a state the UI cannot produce. This is
   the general trap: **a menu row's reachability is part of its contract**, and only driving the
   grammar proves it.
2. **A drawn stack of horizontal bars beside a number is punctuation.** One bar read as an em-dash
   in front of the word "pot"; two read as an equals sign between two amounts (`$198 = $2`). Three
   geometries later the answer was round overlapping chips. Nothing in a unit test can see this,
   and a 2× render flatters it — this is exactly what "always judge at true 1×" is for.

⚠ **A process mistake worth not repeating.** `HOME=… java …` does **not** change the JVM's
`user.home`; a stray instance briefly shared Adam's live `~/.damage`. It was stopped, the damage
was three pristine `window.games.*` records (self-correcting), and the rest of the session ran
with `JAVA_OPTS="-Duser.home=$SCRATCH"` on ports 7501/7503. Second mistake: `pgrep -f` matches
**your own wrapper shell**, so a pattern that appears in your command line kills the shell that
runs it — and three orphaned instances then shared one state file while only the oldest held the
replica port, so the screens under test were a stale build.

### 26.4 The second cycle (2026-09-04, later) — eighteen more

Adam asked for the whole thing again: *"run a complete code review … then do that a second time …
then test the full system in the live environment thoroughly."* Two more code passes over
everything the build touched, then a second live session. **Sixteen more verified defects, plus
two coverage gaps** — the cash-out row had no test that walked it to a completion, and no check
measured a type ladder against the real rasterizer — pinned in `GamesLive20260904Test`,
`GamesWindowTest` and the selfcheck. `HOLDEM.md` §17.2b is the
list; three of them are worth carrying past this window:

- 🔴 **The same defect, one branch over.** §26.3's cash-out fix made a live hand fold first — but
  the code short-circuited on `contributed == 0` into the engine call that refuses a live hand,
  and `contributed == 0` is *first to act, preflop, out of the blinds*, which is four hands in
  six. Fixing a reachability bug is not finishing with it: **walk every branch of the row**, and
  the pin now enters from exactly that spot.
- 🔴 **`?: 1` as "no finishing order means first".** True of the winner; also true of every seat
  at a table that stopped early, and `playOut` has two loud paths that stop one. Each survivor
  was credited the whole prize and recorded a win — money printed, careers corrupted, on the
  error path of an error path. Both settlements now RANK the survivors by chips, so there is one
  first place in either case. The general rule: **a default that is right on the happy path is
  not a default, it is an assumption.**
- 🔴 **The build gate had a blind spot.** `tools/lint.py`'s Kotlin string walker did not know
  about char literals, so `'"'` — three of them in `Journal.kt` — flipped the string/code parity
  for the rest of its line. A gate nobody has seen fail is a gate nobody trusts; a gate that
  cannot fail on a construct the repo actually contains is worse. Pinned with a case the old
  walker passes.

The live session re-drove everything on the new build: the cash-out from the broken spot (bankroll
$790 → $802, the table played on without me), **tap to leave** on the status tail while a
cash-out is pending, the `Settings · games` deep link landing in the Games category, the history
band's three measured lines, the four rungs again, a Custom raise through the §4.8 keyboard, a
full process restart resuming the identical hand, and the switcher **resuming the table** where
Main presents the root list. It also found the last one on the glass: the history read
*"You checks"* and *"You wins $412"* — the engine now writes each sentence in the right person.

**Battery:** core **418** · desktop **9** · selfcheck **162** · snapshots 49 · `--games-check` ·
`--epub-check` · `--music-check` · lint 21 rules / 0 + selftest · APK **25 / 0.25** staged.

### 26.5 The third cycle (2026-09-04, later still) — a measurement that deleted a window

A third full cycle at Adam's word: two more code passes and a third live session. **Eleven
verified defects and two test-quality fixes**; `HOLDEM.md` §17.2c is the list. One of them is the
kind worth remembering:

🔴 **`playOutWithoutMe` handed the play-out to a background coroutine because of a number nobody
had measured.** Its own comment said the work "takes seconds"; `--games-check` prints **13 ms for
a whole 6-seat tournament** at `CHEAP_ROLLOUTS`. The coroutine bought nothing and cost two real
defects — a new table having its cast cleared out from under it by the old table's settlement, and
a shell restart inside the window losing the entire prize pool. Moving it onto the loop (where
`maybeBackground` already spends 16–80 ms) deleted the window, both defects, and the code that
created them. **The lesson generalises: an asynchrony introduced to hide a cost nobody measured is
a defect generator, not an optimisation.** This project already says "measured vs modeled" about
hardware; it applies to our own code.

Two of the cycle's findings were in the TESTS, and they are recorded because the discipline is the
same: a pin added in the previous pass asserted a return value that was identical with and without
its fix — **vacuous** — and was rewritten to capture the log line that actually changed, then
confirmed to fail against the unfixed tree. And one pin's comment claimed to reproduce a race it
cannot (the window is milliseconds wide); it now states the invariant it actually locks. Every
other pin added across these three cycles was confirmed to fail against the code it replaces.

The live session drove it all again on the new build: the cash-out from the spot the first cycle
could not reach, **sitting straight back down and playing on** (the defect above, on the glass),
20 hands with a bust and a busted seat drawn as `out`, the Bankroll document's corrected wording,
the four rungs, and the `Settings · games` deep link's back path landing on GAMES rather than Main.
The whole run produced **one** error line in the log, and it was the scratch instance failing to
bind the media port the real service already holds.

**Battery:** core **419** · desktop **9** · selfcheck **162** · snapshots 49 · `--games-check` ·
`--epub-check` · `--music-check` · lint 21 rules / 0 + selftest · APK **26 / 0.26** staged.

### 26.6 What is owed

- **On-glass verdicts.** Everything was judged on the simulator and the replica at true 1×. The
  card art, the hole-card plane depth, the arc stagger and the pacing want Adam's eye.
- ✅ **The PC side is DEPLOYED** (2026-09-04 13:07): `:desktop:stageJar` + `rc-service damage
  restart` put the Games build on the daily driver. It came up as `standby up (§19)` — the PC
  claimed nothing, so the display was never touched — and the phone reattached to all five
  channels (files, tmux, music, torrents, sync) with the music catalog (2,981 tracks) and
  qBittorrent (39 transfers) both live. No errors in the log.
- **APK 26/0.26 is staged and verified** (`~/.damage/damage-wm.apk`, the setup page's
  `/damage-apk`): the versionName was read back out of the APK's own manifest and its md5 matches
  the freshly built `phone-debug.apk`. 0.25 is superseded; the last version observed INSTALLED is
  still 0.16, so installing it is the one step between here and Hold'em on glass.
- **The next window** is Adam's pick — `EXPLOSION.md` §20's order has Feed at #5 with Games now
  struck through as built.

**Battery after this session:** core **419** · desktop **9** · selfcheck **162** · snapshots 49
(13 Games) · `--games-check` all pass · `--epub-check` 58/58 · `--music-check` all pass · lint 21
rules / 0 findings · `:phone:assembleDebug` green.

## 27. The whole-codebase review (2026-09-05) — the truth oracle, made a gate

Adam asked for the full cycle again: read everything, verify every finding, fix it, review the
fixes, then drive the live system hard at all four sizes, fix what that shows, and repeat until
both come up clean.

### 27.1 What the reading found

**A cash-out was booked as a total loss.** `GamesWindow.finishTournament` recorded Adam's
lifetime net as `prize − myStake`. A cash-out (§10.2, verdict 11) has already moved his chips
into the bankroll and `winner != seat`, so leaving a table with your stack read on the character
page exactly like busting out with nothing. The same line also left out the entry FEE, which
every bot's net has carried since `castStake` was introduced — so Adam's was the one figure in
the standings that ignored verdict 24. Both fixed: the settlement is now
`prize + cashedOut − stake − fee`, `myCashedOut`/`myFee` persist with the rest of the table so a
restart between the cash-out and the settlement keeps the credit, and
`Review20260905Test.cashingOutCreditsTheChipsItTookOffTheTable` fails against the unfixed tree
(−$200 where the truth is −$10).

**Two gates measured nothing.** `--music-check` built its catalog through `MusicDb.catalog(v)`,
whose default art predicate answers *false* for every track: "N likely have art" was structurally
0 on any shelf, and the art extraction always fell back to track 0 and was printed rather than
asserted. It now builds through the library (which wires `Art.likelyHas`), says which kind of art
it is counting, and ASSERTS that a track the catalog flags actually extracts one.
`--games-check` printed a money-supply "drift" as a head-to-tail ratio — a statistic that reports
a large number for any monotone series and so cannot tell §5.3's *flattening* from its named
failure, *compounding* — and asserted nothing at all. It now reports the growth RATE early
against late and FAILS the run when the rate is rising. Measured over 10,000 tournaments the
supply grows +387 % in total but its per-bucket increment falls from ~$130 k to ~$95 k: the fee
sink is winning slowly, which is what §5.3 claims and what the old number could not say.

### 27.2 What the LIVE run found — the truth oracle as a standing gate

The 2026-09-03 review's best instrument was never committed: an oracle that recomputes the
per-lens TRUTH of `comp.composed` under `comp.planes` and compares THAT to the firmware model.
The shell's own divergence check compares its BELIEF to the glass, so a defect that writes wrong
pixels into the shadow and then sends them is invisible to it — and so is ink painted into
`composed` that no damage rect ever carried. Two things now run it:

- **`OracleWalkTest`** — a seeded random walk of the §1 grammar over a real shell at every one of
  the four heights, 240 steps each, asserting belief = glass = truth after every settle. It
  reaches 12–18 distinct surfaces per height (menu, wheel, keyboard, exclusive mode, a focused
  notice, a window's own `contentPlanes`) and asserts its own coverage, so it cannot quietly stop
  proving anything.
- **`--selfcheck` runs the oracle on EVERY settle** — 279 of them, over every real window with
  the real faces.

That found three defects no test had:

1. 🔴 **The Music Mode card inked past its own rect** (288 and 352). The progress row was placed
   at `r.bottom − 14` under a face whose MEASURED ink is 20, so its descenders landed 4 px below
   the card — outside the only rect `paintExclusive` reports as damaged. On the full paint it
   shipped; on every delta after it did not, so `composed` held ink the glass never got and the
   next keyframe would have produced a fragment of an older track out of nowhere. The card's
   height and every row in it are now derived from measured ink. Reverting the fix reproduces it
   (`music-mode-advance-288`: level 3 against a black glass).
2. 🔴 **Chrome text left its bar at the top of the font ladder.** §4.2's scale reaches 130 % and
   scales chrome too, but §2.3's bars are a fixed 32 px and 28 px. At 130 % the title's ink ran
   into the divider; at the tallest face (Alegreya, 35 px of ink) and a reduced height the status
   line's descenders landed BELOW the safe rect, on panel nothing ever repaints. Two fixes, both
   proven load-bearing by reverting them: every chrome line is now placed from its measured ink
   inside its cell (`Chrome.fitY`), and the chrome's effective scale is capped to what its bar can
   hold (`Shell.chromeScale`) while CONTENT keeps the full ladder.
3. **The status bar overflowed its own bar at 100 %.** The same `fitY` fix: 24 px of ink at a
   6 px inset in a 28 px bar had been running 2 px past since the bar was drawn. At full height
   the panel edge clipped it, so it read as slightly cut descenders; at a reduced height those
   rows are real panel.

Two more of the class were hardened without a reproduction, because the rule is the same and the
measurement is free: the Music Mode queue peek stacked two 23 px lines on a 20 px pitch inside a
44 px band (they overlapped by 3 px and the second ran 1 px past the band), and the PC badge's
20 px rect held 20 px of ink drawn 2 px down. Both bands are measured now; at 288 the peek shows
one row, the same "extra height buys information density" ladder §8.3 applies everywhere else.

### 27.3 What the walk now covers

`--selfcheck` grew from 162 checks to 189 and gained three passes it did not have: Music Mode
driven with the queue ADVANCING (so its surfaces repaint as deltas, which is what exposed #1),
the whole window set walked again at 130 %, and again at the tallest face — at 480 and at 288,
because at 480 an overflowing chrome line is clipped away by the panel edge and looks fine.

### 27.4 Battery

core **421** · desktop **9** · selfcheck **189** (oracle 279 runs) · snapshots 49 ·
`--games-check` · `--music-check` · `--epub-check` 58/58 · `--card-render` ·
`python3 tools/lint.py` 21 rules / 0 findings + selftest · `research/verify_cfw.py` ·
`:phone:assembleDebug`.

### 27.5 What is owed

- **Nothing on glass has changed hands**, though both sides are deployed: APK **27/0.27** carries
  this review and is staged on the setup page, and the `damage` service was restarted onto the
  review build (2026-09-05, `standby up (§19)`, the phone reattached to its channels). **0.16 is
  still the last build observed INSTALLED** — installing 0.27 is the one manual step left.
- **The reading did not cover everything at the same depth.** The seam, replica, sync and window
  channels, the firmware simulator, the transports and the whole shell were read line by line;
  the music/torrents/tmux provider and desktop-harness leaves were read for their risk surfaces
  (command construction, SQL, credential handling, HTTP framing) rather than end to end.
- **The oracle cannot see what the harness does not visit.** It runs on settled surfaces only,
  and the random walk uses a fake rasterizer, so font-metric defects are the selfcheck's job.

### 27.6 The snapshot harness — three defects behind one intermittent failure

Running `--snapshot` repeatedly (not once) at the end of the round showed a failure about one run
in four, never in the same place twice: *"wait 'the hand finishes' never became true"*, *"shell did
not settle at 'commit-games'"*, *"…at 'back-to-main'"*, *"…at '?' [in=torrents]"*. Three separate
causes, found by pinning one variable at a time.

1. 🔴 **The settle re-tested its own condition after the wait loop had already passed it.**
   `while (!isQuiescent() && !expired) delay(20); if (!isQuiescent()) fail` — the second call is a
   race against every periodic tick (the clock posts a message each second; the Hold'em pacer posts
   its own), so a settle that had genuinely succeeded could still report failure, and its diagnostic
   printed an EMPTY pending list because by then nothing was pending. That empty list is what
   identified it. One evaluation now decides it, which is the shape `SelfCheck.settle` always had.
   Same fix in `waitFor` — several of its conditions include `isQuiescent()`.
2. **The showdown scene assumed one action ends a hand.** It took a single row and waited for a
   result, which only worked when that row happened to be *Fold*: Check and Fold are ONE contextual
   row (verdict 12), so with no bet to call the script CHECKED, the flop came out, and the table
   waited for Adam again — forever, correctly. The scene now acts every time it is Adam's turn,
   picking the contextual row BY NAME, exactly as `GamesWindowTest` does, and says so plainly if the
   hand still has no result after twelve actions.
3. **The world was seeded from the wall clock**, so the games scenes were a different tournament
   every run and the script's assumptions held or failed by luck. `GamesCheck` already pinned its
   roster (`Roster(worldSeed = …)`); `--snapshot` and `--selfcheck` now pin theirs the same way
   (`gamesWin.roster.worldSeed = 20260905L`), so a scene is the same scene twice.

The two harness bounds were also raised — settle 15 s → 60 s, waitFor 30 s → 120 s — and both now
print anything that took over 5 s. They are backstops against a state that can never arrive, not
budgets: between Adam's actions every remaining bot runs `Equity.LIVE_ROLLOUTS` (2000) rollouts per
decision and the board reveal is paced, which on a loaded machine had been reported as a wrong state
while the table was still making progress. Eight consecutive clean runs after the three fixes.

⚠ The PNGs are still not byte-identical run to run — the chrome clock is live, so 48 of 49 differ in
that cell alone. Pinning the clock would freeze the pacer with it; that trade has not been made.

### 27.7 Where the next session picks up

Read `CLAUDE.md` → `REMINDER.md` → `HANDOFF.md` §19–§27 in that order, then:

- The battery is the entry check, not a formality: `./gradlew :core:test :desktop:test`,
  `desktop --selfcheck`, `desktop --snapshot DIR`, `desktop --epub-check ~/books`,
  `desktop --music-check`, `desktop --games-check`, `python3 tools/lint.py`,
  `./gradlew :phone:assembleDebug`. All green at `27.4`'s numbers as of this commit.
- **Run the harnesses more than once.** Every defect in §27.6 was invisible in a single run. Three
  runs of `--snapshot` is the cheap version of that lesson.
- **Nothing from §25, §26 or §27 has been seen on glass.** The installed APK is **0.16**; **0.27**
  is staged and carries all of it. That is the largest untested surface in the project, and it is the
  first thing worth doing with the glasses in hand — the four heights, the 130 % ladder, Music Mode
  and a Hold'em hand, in that order.
- The open items are §26.6 and §27.5; nothing in this round is half-finished.

## 28. The third whole-codebase review, and the first full LIVE walk (2026-09-04, late)

Adam's ask, verbatim in spirit: read everything, verify every candidate before touching it, fix
what is real, review the fixes, then **test the whole system live exactly as a user would** — every
window, the edge cases, all four heights — fix what that finds, review those fixes, and only then
update every document and push. This section is that record. Eleven verified defects; every fix
carries a pin that was run against the UNFIXED tree and watched to fail (`Review28Test.kt` — five
classes — and the desktop `ConfigTest`).

### 28.1 What the reading found (and what it did not)

The reading covered `core/` end to end (shell, compositor, wire, sim, transports, every window
including the 25 music files, the Hold'em engine, bot, roster and kit), `desktop/` end to end
(the harnesses included), `phone/` end to end, and the Python tooling (`tools/`, `design/`,
`audio/`). Six candidates survived verification; the rest were intended design or
misunderstandings, recorded in the session notes and not here. The verified ones:

1. 🔴 **The Hold'em pacer STALLED after a back-and-return inside one bot's pace** — and after
   leaving for another window and coming back, and after a peer's table record replaced the live
   one. Every invalidation bumped `pacerGen` and left the stale completion to clear `thinking`;
   that completion, seeing the bumped generation, returned without pumping, and the re-entry's
   own `pump()` had already been refused by the still-set flag. The table sat on "Roy G. …"
   until a tap — and that tap SKIPPED the pacing for the rest of the street. `cancelPacer()` now
   bumps the generation AND clears the flag together, a superseded completion never touches the
   flag, and a replaced table re-pumps. Two pins reproduce the stall through the real shell
   (`Review28Test`), both failing on the old tree in 15 s of nothing happening.
2. 🔴 **The Reader could not open a book at 115 % or 130 %.** The line box was a constant 30 with a
   guard that REFUSED any layout whose measured ink exceeded it. Measured on the real rasterizer:
   Alegreya 17 inks 28 rows at 100 %, **34 at 115 %, 36 at 130 %** — two of the four rungs of the
   size ladder threw `LintError` off-loop and every title said "could not open …". The box now
   follows the face (`lineBox()`, carried in the loaded layout so a rescale mid-read cannot draw a
   new box over an old layout); 30 at 100 % exactly.
3. **Tmux's staleness line inked past its content rect.** `rect.bottom - 20` under JetBrains Mono
   14, whose ink is exactly 20 at 100 % — 22 at 115 %, 25 at 130 %: the §27 class, on a line that
   only appears when a host is down. Placed from the measured ink.
4. 🔴 **An unreadable `config.json` was REPLACED with defaults.** One stray comma in a hand edit →
   the desktop fell back to `Config()`, minted a token, saw the result differ, and STORED it: the
   TorrentLeech credentials, the tmux hosts and the phone's token gone on the next start. The
   Python side (`audio/enrich/damage_config.py`) already refused outright for this reason. The
   defaults now run for that start only and the file is left for the person to fix.
5. **Main described scans nobody had started.** The Reader's lens read "library loading" and Files'
   "0 locations" from boot until each window was first opened — neither scanned at registration.
   Both do now, quietly (no op-cell narration, no notice, no `navSeq` bump), and the activation
   re-scan is skipped while the registration scan is in flight.
6. "1 hands", "1 tournaments", "1 lines" — the "You checks" class, on the seat inspect line, the
   standings lens, the character page and the action menu.

Also verified and left alone: Music's scroll-up = quieter (the same direction as every Settings
row — the shell's grammar, not a defect); the transport's narrow late-FlushDone race (harmless);
the silent small clock (already placed by `fitY`).

### 28.2 The live walk — the instrument, and what only it found

**How it was driven.** The desktop program in `--transport sim --no-preview` under a SCRATCH home
(`java -Duser.home=<scratch> -jar desktop/build/libs/damage.jar`, ports 7501–7504, a throwaway
token, `tmuxHosts` carrying a deliberately dead host so the staleness line is exercised), the real
shelf and the real tmux server and library behind it. A 150-line Python driver with no
dependencies speaks the browser replica's WebSocket (`/ws?token=…`), sends the ring grammar as
`{"t":"input","ev":"tap|double|up|down|hold|release"}` and typed lines as `{"t":"text"}`, decodes the
binary panel frames (`[arm][y0][rows][rows×320]`) into 4-bit rows and writes true-1× PNGs. Every
surface was then LOOKED AT, at 1×. Two lessons of the method: **snap between steps** — a script
that assumes where the Main or Settings cursor rests drifts (the rest rows are not where a
counter says), and blind runs of it changed five settings by accident; and **the scale ladder is
where the constants hide** — every drawing defect below was invisible at 100 %.

**What it found**, each verified by reading the code, then fixed, then re-driven on a rebuilt jar:

7. 🔴 **Every chrome surface with a hand-picked vertical rhythm broke at the top of the font
   ladder — the MENU, the NOTIFICATION BOX and the SWITCHER.** The menu's 18 px title band and
   24 px row pitch put the title's baseline below its own rule and the last row's ink past the box
   (220 px outside the damage rect at 120 % chrome); the notification's 16 px source band and
   24 px pitch did the same, with three body lines that no longer fit the 104 px ceiling; the
   wheel's 88 px centre band dropped the name's baseline below the lower rule and the lower
   neighbour's descenders past the panel (440 px outside). All three now measure the chrome face
   — `MenuSurface.titleH()/rowH()`, `Notifications.srcH()/pitch()/roomFor()`, the wheel's
   `bandH` — with the design numbers as the floor, so **100 % is pixel-identical** and the chrome
   scale cap (§27) is no longer the only thing standing between a scaled face and undamaged ink.
   ⚠ **All three measured the ASCENT, which is half the promise** — §30 #1, #2 and #11 found each
   of them still striking its own text with its own rule, at 100 % as well as up the ladder. Ink is
   `ascent + descent`; nothing here is a floor for anything but the design's own numbers.
   The §27 rule, restated for chrome: *a rect a paint returns is a promise* applies to the shell's
   own surfaces exactly as it does to a window's.
8. 🔴 **Tmux never alerted for a pane that had not filled its screen.** The per-host status
   script took `capture-pane -p | tail -5`, and `capture-pane -p` prints the whole visible pane —
   trailing empty rows included — so a session just created from the glasses, or any short
   command, produced five blank lines: the wait patterns never matched and the sessions lens showed
   no last line. Only the full-screen Claude sessions on beardos ever worked, which is why nothing
   noticed. Blank rows are dropped before the tail now; the live re-test raised the notification
   ("g2-1 wants input") that the first attempt had silently not.
9. 🔴 **The Hold'em status line was CUT by the hole-card plane under a per-app scale.** The status
   band was a 24 px constant; at 130 % its 33 px line ran into the region the window declares as
   plane 0 (`holePlane`), whose pixels render unshifted while the rest shift with the content
   plane — a horizontal cut through the text on every frame, at 480, 416 and 352. The band and
   your line are sized from the measured ink (`TableLayout(statusH, lineH)`; the status band is
   30 at 100 % — Clear Sans 17 bold inks 25, and the design's 24 held it only by lending its
   descent to the gap; the seat strip gives up the six rows), the seat rows' pitches follow the
   ink, a seat line that would run past its cell is dropped rather than drawn over the band below,
   and the holding mark sits under the name's ink wherever the scale puts it.
10. **The notification box at 130 %** — item 7's third member, listed separately because it was
    found last: two lines now fit where three no longer do, and the bottom rule carries the
    scroll position as before.
11. **The switcher at 130 %** — item 7's second member, verified by measuring the rows of the
    PNG: the name's ink ends two rows above the band rule at both scales.

**What the walk covered, clean:** Main (every lens, wrap, resting state), Reader (shelf, folders,
the chapter picker, a cover image, pages, scroll, actions, at 480/288 and at 130 %), Tmux (sessions,
the live pane with the staleness line, history, keys, a new session created from the glasses, a
typed line behind its confirm), Files (locations, browse, the context menu, at 480/288), Torrents
(transfers, the transfer menu, details, the window menu, browse), Music (Now Playing idle, the
whole menu, browse, artists, an artist, the set menu, volume, Music Mode idle, lyrics idle),
Games (root, the scoreboard lens, bankroll and its menu, tables, the buy-in confirm, a full hand
with call/raise through the sizing ladder and the Custom keyboard, showdown, inspect, standings, a
character, hand history, a cash-out through "fold and leave", the play-out settlement), Settings
(categories, every Global row, live adjust with staging), silent mode with the large and medium
clocks, the switcher spun and cancelled, the keyboard, a notification arriving and taking focus,
the 130 % ladder and Alegreya as the chrome face — with **zero divergence reports across four
instance logs**.

### 28.3 Pins, and the vacuity check

Every pin was run twice: once against the fixed tree (PASS) and once with its fix stashed (FAIL),
because §26.5 found a pin that passed both ways. `Review28Test` (the two pacer stalls),
`Review28ScaleTest` (the book at 130 %, the tmux line inside its rect — both on a `ScalingText`
whose ink follows the size, the blind spot `FakeText`'s constant 12+4 metrics leave), `Review28MainLensTest`
(both lenses count before the windows open), `Review28PaintTest` (the scaled menu inside its box
with the title above the rule; the scaled status line out of the hole plane; the tmux script's
blank filter), `Review28SwitcherTest` (the scaled wheel inside its panel), and the desktop
`ConfigTest` (an unreadable file left untouched; a tokenless one completed).

### 28.4 Battery

core **430** · desktop **11** · selfcheck **189** (the oracle on every settle, 282 runs) ·
snapshots 49 × three consecutive clean runs · `--games-check` · `--music-check` ·
`--epub-check` 58/58 · `--card-render` · `python3 tools/lint.py` 21 rules / 0 findings +
selftest · `:phone:assembleDebug`.

### 28.5 Where the next session picks up

Read `CLAUDE.md` → `REMINDER.md` → `HANDOFF.md` §19–§28, then:

- ✅ **Both sides are deployed (2026-09-04, 18:16):** `stageJar && rc-service damage restart`
  put the service on this build — it came up `standby up (§19)` with the phone reattached to the
  sync, tmux, files, torrents and music channels, and touched nothing on glass — and APK
  **28/0.28** is staged on the setup page (`~/.damage/damage-wm.apk`). **0.16 is still the last
  APK observed INSTALLED** — installing 0.28 is the one manual step left.
- The live-walk driver is worth keeping as a standing instrument next to the harnesses: it is the
  only one that runs the REAL providers (the tmux server, the shelf, qBittorrent, Postgres) under
  the real grammar, and it is what found items 7–11. Its shape is in §28.2; rebuild it in ten
  minutes rather than script blind.
- Open items are §26.6, §27.5 and the deploy above; nothing in this round is half-finished.

## 29. The fourth whole-codebase review, and the second full LIVE walk (2026-09-04, evening)

The same ask as §28, one build later: read everything, verify every candidate before touching it,
fix what is real, review the fixes, then drive the whole system live exactly as a user would —
every window, every height, the font ladder — fix what that finds, review those fixes, then bring
every document up to date and push. This section is that record. **Eleven verified defects**,
every fix carrying a pin that was run against the UNFIXED tree and watched to fail
(`Review29Test.kt`, one pin each in `TmuxTest.kt` and `MusicWindowTest.kt`).

### 29.1 What the reading found

The reading covered `core/` end to end (shell, compositor, wire, sim, transports, every window,
the Hold'em engine, bot, roster and kit), `desktop/` (the harnesses included), `phone/`, and the
Python tooling (`tools/`, `design/`, `research/`, `audio/`). Four candidates survived
verification:

1. 🔴 **The list rhythm was the last constant of the §27/§28 class — and it was already cutting
   ink at 115 %.** Measured on the real rasterizer: Clear Sans 18, the row face of every list,
   inks **27 px at 100 %, 32 at 115 %, 34 at 130 %**; the design's 32 px row held the 27 under the
   rows' 5 px offset EXACTLY, so one step up the ladder the row directly above the lens lost its
   descenders — the rows above are painted first and the lens band then clears itself over them.
   Seen live before the fix: the `$` and the comma of "$1,000" cut flat at the lens rule. The oracle
   is blind to this (the ink lands inside the content rect it damages), which is why three
   reviews walked past it. Now: `Layout` carries `rowH` / `lensH` (defaults 32 / 64, the floors);
   `Shell.listRhythm()` derives them from the row face's measured ink through the transform on
   screen (Main's chrome transform, or the focused window's per-app one — rows `5 + ink`, lens
   `2 × ink + 10`), `ContentKit` hangs the rows above FROM the lens (any remainder sits under the
   top pad; at 32 every height mode divides exactly, so 100 % is the drawing to the pixel), the
   slides use `layout.rowH`, and every window's second lens line is placed by `Draw.lineBelow`
   from the first line's ink — Main, Settings, Reader (three lenses), Tmux (three), Files (four),
   Torrents (transfers, categories, the three-line listing lens), Music (the lens and the card),
   Games (the root lens and the three-line ladder, whose third line now yields to the band's
   bottom rule). One consequence to know: a window whose per-app scale differs from the chrome's
   has a different rhythm, so switching to it is a relayout with a keyframe — the same cost a
   height change already has.
2. **The Font size row read "114%" for the ladder's 115 % step** (and "84%" for 85 %): `(1.15 *
   100).toInt()` is 114 in binary. `ShellSettings.scaleLabel` rounds; all three rows use it.
3. **A failed transport start released the lease into a race.** The rollback enqueued the FB
   RELEASE and called `disconnectLink()` at once, so the write either never reached the wire (the
   link was gone when the lane got to it) or reached a link being torn down and logged a "control
   lane error" fault for a write nobody expected to work — a loud line with no action behind it.
   `stop()` already awaited its own release; the rollback now does the same through one shared
   `awaitReleaseWrite`. Pinned with a transport that refuses the warmup and marks its link down
   before its first suspension.
4. **The seam client's `stop()` closed the socket outside the try**: a close that threw skipped
   the state update and the outstanding-flush sweep. Guarded.

Verified and left alone, so nobody re-litigates them: `commitWindow` under EXCLUSIVE mode is
unreachable (every commit path is swallowed or closed by exclusive mode first); the wheel's
centre-name descenders cross the lower rule by design (the band holds the ascent — §28 #11)
⛔ **WRONG, reversed by §30 #11**: the band was sized from the ASCENT and the rule was painted
through the name at every scale, 100 % included; the
throughput cell's idle repaints converge on the sim and are paced by the 5 s idle tick (§8.3);
the Torrents listing's paced retry of a "not configured" refusal is the R4-P5 design; Tmux
keeping 480 under a global 288 is its own Size row.

### 29.2 The live walk — what only it found

**The instrument** (§28.2, rebuilt in ten minutes): the desktop program in `--transport sim
--no-preview` under a scratch home (ports 7501–7504, a throwaway token, a dead `ghost.invalid`
tmux host for the staleness line, a copy of the shelf, the real tmux server, qBittorrent and the
music library behind it) and a 150-line Python driver on the replica WebSocket that writes a
true-1× PNG after every step. Three lessons the driver paid for this time, on top of §28's
"snap between steps": **the replica's pings are not frames** — a quiet-detector that counts them
waits 20 s a step; **a blind gesture run in a window with destructive rows is a real risk** — one
assumed cursor rest started a stopped torrent on the real qBittorrent (stopped again through its
API) and reached the first of the two delete confirms before the cancel; **and never rebuild the
jar in place under a running instance** — the JVM's lazy class loads then fail (the DAILY.md
staging lesson), which is how #6 below was found.

**What it found**, each verified in the code, fixed, pinned, and re-driven on a rebuilt jar:

5. 🔴 **The tmux alert was app-less: a tap on "g2-1 wants input" only dismissed it.** Every
   other window's event notice carries `appId` and a `target`; the alert carried neither, and the
   window had no `open(target)` at all, so `TMUX.md` §3.5's "glance → tap → `y`" ended at the
   glance. The notice now names the window and the session (`session:<host>:<name>`), coalesces
   per SESSION (two sessions waiting were one box), and the window's deep link opens that
   session's live view — re-driven: arrival, focus after the grace, tap, the pane.
6. 🔴 **The shell loop caught `Exception` only.** An `Error` out of a handler ended the loop: the
   display froze on its last frame while the transport kept the lease renewed and the keeper's
   status read "running" — the exact silent failure this project bans, and the rebuilt-jar
   `NoClassDefFoundError` is not the only way to get one (an OOM in a paint is another). The loop
   now survives an `Error` loudly (log, journal, the status cell says ERROR) and keeps serving.
7. **Brightness could never go back to auto.** A notch left auto at the stored level and the
   manual ladder ended at 0 % with no way back, so a brightness touched once on the glasses stayed
   manual for good. One ladder now, auto at its foot: a notch up from auto leaves it, a notch
   down from 0 % is auto again, nothing sits below auto.
8. **The custom-amount keyboard said "raise to" over a checked-through flop**, where the row it
   came from says "Bet →" and the confirm it leads to says "Bet". The verb follows the bet on the
   table, as the sizing rows already did (§28's own note).
9. **Music's idle root at 130 %: the caption sat inside the descenders of "nothing queued"** — a
   constant 48 px under a 36 px face. Placed below the big line's measured ink.
10. **The context menu under a bigger chrome face** — two defects in one box. The box was a fixed
    248 px, so at the 120 % chrome cap "Fold and leave" read "Fold and ▸"; it now follows the row
    face (the design's width grown by the same ratio the row pitch grew — 308 at 120 %, never
    wider than the content). And a detail cut at its HEAD by the tail-keeping fit ("nothing
    queued" → "othing queued") carried no mark at all; a head cut now gets the drawn mark on that
    edge, the NO TRUNCATION rule applied to the fit that keeps the tail.
11. (Counted above: the rows above the lens cut at 115 % was the reading's finding, but the
    walk's first render is what proved it — item 1's crop is the evidence.)

**What the walk covered, clean:** Main (every lens, wrap, resting) at 100 / 115 / 130 % and in
Alegreya; Reader (shelf, folders, the chapter picker, a cover, pages, scroll, actions) at all four
heights and 130 %; Tmux (sessions, the live pane with the staleness line, history, keys, a session
created from the glasses, a typed line behind its confirm, the alert's deep link); Files
(locations, the root listing, the context menu, a folder); Torrents (the real transfers, the
transfer menu, details, the window menu, browse without a tracker — refused loudly — and the
search keyboard); Music (Now Playing idle, the whole menu at 100 and 130 %, browse, artists, an
artist, the set menu's refusal on the mirror, volume, lyrics idle); Games (root, the scoreboard
lens, tables, the buy-in confirm, a hand with a raise through the sizing ladder, the custom
keyboard, checks and a fold, the terminal state, hand history, standings, a character, a
cash-out through "fold and leave" and the play-out, at 480 and 130 %); Settings (categories, every
Global row, the Games category, the staged Size / Font / Font size rows, the brightness ladder);
silent mode; the wheel; 288 / 352 / 416 / 480 for Main, Settings, Reader and Games — with **zero
divergence reports** across three instance logs.

### 29.3 Pins, and the vacuity check

`Review29Test`: the row above the lens keeps all its ink at 130 % (a rasterizer whose ink follows
the size, with Clear Sans's measured metrics at the chrome sizes; the pin counts the inked rows of
the row directly above the lens and of the two lens lines); a grown rhythm is grid-legal at every
height; the ladder labels; the failed-start release reaches both arms before the disconnect with no
control-lane fault; brightness returns to auto below 0 % and stays there; the custom-amount
keyboard names the action on the table; the loop survives an `Error`; the menu box follows the
face and a head-cut detail carries the mark. `TmuxTest.anAlertNoticeDeepLinksIntoItsSession` and
`MusicWindowTest.theIdleCaptionSitsBelowTheBigLinesInk`. Every one was run against the unfixed
tree (the fix reverted in place, the test run, the fix restored) and failed there.

### 29.4 Battery

core **440** · desktop **11** · selfcheck **189** (the oracle on every settle) · snapshots 49 ×
three consecutive clean runs · `--games-check` · `--music-check` · `--epub-check` 58/58 ·
`python3 tools/lint.py` 21 rules / 0 findings + selftest · `:phone:assembleDebug`.

### 29.5 Where the next session picks up

Read `CLAUDE.md` → `REMINDER.md` → `HANDOFF.md` §19–§29, then:

- ✅ **Deployed 2026-09-04 22:00** (Adam's word, right after the push): `stageJar && rc-service
  damage restart` put the service on this build — it came up `standby up (§19)` and the phone
  reattached to the sync, files, music, tmux and torrents channels within seconds, nothing on
  glass touched — and APK **29/0.29** is staged on the setup page (`~/.damage/damage-wm.apk`,
  byte-identical to the build output). **0.16 is still the last APK observed INSTALLED** —
  installing 0.29 is the one manual step left.
- The live-walk driver (§28.2, §29.2) remains the instrument that runs the real providers under
  the real grammar. Snap between steps; treat every window with a destructive row as one step per
  snap; count only panel frames as activity.
- Open items are §26.6, §27.5 and the deploy above; nothing in this round is half-finished.

---

## 30. The fifth whole-codebase review, and the third full LIVE walk (2026-09-05)

The same ask as §28 and §29, one build later: read everything, verify every candidate before
touching it, fix what is real, review the fixes, then drive the whole system live exactly as a user
would, fix what that finds, review those fixes, repeat until a full pass comes back clean, and then
bring every document up to date. This section is that record. **Nineteen verified defects**, each
fix carrying a pin that was run against the UNFIXED tree and watched to fail (`Review30Test.kt`,
plus one each in `TorrentsTest.kt`, `MusicWindowTest.kt` and `Review28Test.kt`).

Three of them are the kind this project exists to catch: a wheel that never stopped spinning and
left the shell posting empty frames for ever; a standing gate that failed one run in ten because
its own sample was torn; and the seat strip drawing every opponent's money straight through the
board at the shortest height under the biggest face.

### 30.1 What the reading found

The reading covered `core/` end to end, `desktop/` (the harnesses included), `phone/`, and the
tooling. Seven candidates survived verification, and five of them are the SAME defect — §27's
standing rule, one more layer down:

1. 🔴 **The notification box's source band was a 16 px constant under a face that INKS 20.** Clear
   Sans 13 bold: ascent 16, descent 4. Drawn at a constant `+2` the caps ran to row 17 and the rule
   at 16-17 struck straight through the source line, the `+N` queue badge and the timestamp —
   visible in `snapshots/08-notification-focused.png` for as long as that snapshot has existed. The
   band is measured now and each line is placed from its own ascent, so the baseline lands on the
   band's last row at every step of the ladder.
2. 🔴 **The context menu's title band, the same defect** — and worse, the rule was painted LAST, so
   it silently overwrote whatever it crossed and the collision could not be seen in the render it
   produced. The rule is painted FIRST now and the title over it.
3. 🔴 **The chrome clock's AM/PM marker sat at a constant `cell.x + 52`.** That is `4 + 48` at
   100 % — a ZERO gap for a five-character time, "10:20PM" on the glass — and at 115 % the time is
   53 px wide, so the marker landed INSIDE the last digit. `Chrome.clockWidths()` measures the
   widest `h:mm` the face can print (exactly, from 22 measures, checked against all 720 real times),
   `Layout` carries a measured `clockW` with §2.3's 80 as the floor, and the title cell takes what
   is left.
4. **The medium seven-segment readout's minute pair sat at 56 and 84** — a 28 px pitch where every
   other pair is 24, so it printed "10:2 1" with a visible gap before the last digit. 80 now; every
   gap is 6.
5. 🔴 **The Games documents sized their line box from `metrics().lineHeight`, which is not the
   ink.** For Clear Sans the reported line height is one to two rows SHORTER than ascent+descent,
   and these documents mix a 17 px bold heading into a 13/16 px body — so every line drew 3-8 px
   past its own line rect, and `Shell.paintDocSlice` renders each line into a buffer exactly one
   line box tall, which chopped the descenders off every row on the first scroll and baked the chop
   in on the settle. `docLineH(vararg faces)` takes the tallest ink; each line is centred in it.
6. **The Files locations and trash lenses drew NOTHING for an empty list.** An empty list still has
   one row and a one-row list is drawn ONLY by the lens — the slots above and below resolve to no
   index — so the placeholder in the row painter was unreachable and the content band was simply
   blank while a failed or unanswered listing sat behind it. Both lenses say what is empty and why,
   and a tap on the placeholder asks the host again rather than doing nothing.
7. **`TableLayout`'s "the bottom bands give way" claim was false.** It floored the seat strip and
   carried on, which pushed the bottom band past `content` — an escape only `check()` would have
   caught, and nothing on the paint path calls it. The optional bands now actually give way, bottom
   first, loudly; and `showsYourLine` / `showsHistory` answer from the BAND rather than the tier, so
   a painter asking "do I draw here" gets the answer the allocator gave.

### 30.2 What the second reading found — in the fixes, and in the gates

8. 🔴 **A line box is the LARGER of the face's line height and its MEASURED ink, never the line
   height alone.** AWT ceils ascent and descent separately and the height once, so the ink is a row
   TALLER than the line height at several scales — measured against the real rasterizer, JetBrains
   Mono 16 inks 25 rows against a 24 px line at 115 % and 29 against 28 at 130 %. A box a row short
   of its own text puts every line's descenders in the tops of the next (the §29 rhythm defect) and
   presses the bottom line against the rect it is drawn in. Fixed in `FlowRender.lineH`,
   `TermRender`'s cell, the keyboard's label/prompt/draft centring and its caret, and the Hold'em
   history's pitch.
9. 🔴 **The standing `--selfcheck` truth oracle failed about one run in ten, and had for as long as
   it has existed.** MEASURED on the unchanged tree: 2 failures in 20 runs. The failure was a
   whole-surface difference — 16,963 px at `scale130-reader-in` — that a SECOND LOOK agreed with,
   and the plane map printed with it was one region short of the map the glass had been drawn under.
   The scan is not wrong; the SAMPLE was torn: `isQuiescent()` answers about one instant from
   another thread, and the oracle then read `comp.composed`, `comp.planes` and both sim panels one
   after another, across a window the shell can start and finish a whole repaint inside.
   `Shell.sampleIdle` takes the whole reading ON the loop with no other message queued and nothing
   else pending; `SelfCheck.runOracle` and `OracleWalkTest.assertOracle` both go through it.
   **20/20 clean after.**
10. **The selfcheck's oracle kept pointing at the STOPPED shell after the restart scene.** The scene
    builds `sim2`/`shell2` and never re-registered them, so every settle of the whole restored
    session was skipped or compared a stopped shell against its own frozen glass — a free pass. It
    follows the live pair now (the oracle count went 282 → 283).

### 30.3 The live walk — what only it found

**The instrument** is §28.2 / §29.2 unchanged: the desktop program in `--transport sim
--no-preview` under a scratch home (ports 7501–7504, a throwaway token, a dead `ghost.invalid` tmux
host for the staleness line, the real tmux server, qBittorrent and the music library behind it) and
a Python driver on the replica WebSocket that writes a true-1× PNG after every step. The §29
lessons all held: snap between steps, one step per snap near a destructive row, count only panel
frames as activity, and never rebuild the jar under a running instance.

11. 🔴 **The switcher's centre band was sized from the name's ASCENT** — the top half of the
    promise. The name is drawn at `bandTop + 64` and inks `ascent + descent`, so the lower rule was
    painted at row 288 while "Settings" still had ink at 290-292 and the rule cut straight through
    the word. Measured on the live wheel, and true at 100 % as well as at 130 %; §29 had recorded
    the opposite ("the wheel's centre-name descenders cross the lower rule by design") after
    measuring the wrong half. The band takes the measured ink plus two rows, and the upper
    neighbour is clamped off the rule the way the lower one already was.
12. 🔴 **The tmux staleness surface (§10.5) reached the live pane and nowhere else.** `ghost` had
    been failing its status poll for half an hour and the sessions list read clean, because
    `provState` was painted only by `paintLive` and the summary used it only when there were no
    sessions at all. It rides the TITLE on every other level now and the summary carries it into
    Main's row. **Torrents had the same shape** — the transfers list says it, a details page or a
    listing said nothing while the frozen figures read as current ones — and takes the same fix.
13. 🔴 **The Hold'em seat strip painted its stack line through the board.** At the 288 rung with the
    global scale at 130 % the status band grows to 38 and leaves the strip a 120×34 cell, while the
    15 px name and the 14 px stack want 52 rows there — so every opponent's stack was drawn across
    the top edge of the board's card slots. The card art does not scale with the face and 288 is
    already the smallest rung, so the seat FACES step down until the two rows fit; when even the
    smallest pair will not fit the strip goes COMPACT, one row per seat with the money right-aligned
    and placed first and the name fitted into what is left at the largest face whose name-plus-money
    measures inside the cell. Every line is guarded against the cell besides.
    ⚠ The first fix DROPPED the stack row instead, which is how the walk's own second look found
    that the money had gone off a poker screen entirely; the second put it back on one line.
14. **A STAGED settings row said "scroll adjusts live".** Size and every host row (Font, Font size,
    Font style, the display target, every per-app row) stage their value and apply it on the tap —
    scrolling Size three notches left the panel at 480 under a line claiming otherwise, which is the
    shell describing a program that is not running. Staged rows say "scroll picks · tap applies ·
    double-tap reverts"; the live rows keep their wording.
15. **Music offered Resume / Next / Previous with an empty queue.** Each said "nothing queued" in
    its detail and then did nothing at all when tapped — the §26 trap, a row that can never succeed.
    They are dim now, and **a menu opens its cursor on the first row that can act** rather than on a
    tap that does nothing.
16. **The notification box hung from its design box's TOP edge.** At 100 % it is the design's 104 px
    and centred either way, so that render is untouched; a one-liner, or the two lines a 130 % face
    leaves room for, sat above the axis every other surface shares. Centred on its own height.

### 30.4 What the ORACLE WALK found — the one no reading and no walk would have

17. 🔴 **A wheel closed mid-spin never stopped spinning.** `Switcher.spinning` is
    `spinPos != cursor`, the drum is stepped only while the wheel is OPEN, and `close()` left
    `spinPos` short of the cursor — so a scroll followed by a commit or a cancel inside the four
    animation frames left the flag true for ever. The shell's frame loop posts another `Msg.Pump`
    for as long as that flag is true, and `isQuiescent()` reads the same flag: an unbounded loop of
    empty frames, on the glasses as much as in the harness, and a shell that never reports itself
    idle again. `OracleWalkTest` caught it as `h=288 step 180 (LONG_PRESS_RELEASE): queued=1
    reports=0` after a 120 s bound — a shape that looks exactly like load until you measure the
    worst settle in a clean run and find it is **46 ms**. `close()` stops the drum, and `spinning`
    is `open &&`-gated as a second line. Pinned twice: the property itself, and a shell that must
    reach quiescence after a wheel is cancelled mid-spin (the shell-level pin is posted from the
    loop, because posted from the test thread it races and passes either way — the §26 vacuous-pin
    lesson).

### 30.5 Pins, and the vacuity check

`Review30Test`: the notification rule does not strike its source line; the menu rule does not
strike its title (an order-independent DIM-pixel count, because the rule painted last simply
overwrote the title and the naive pin passed against the unfixed tree); the Games documents hold
the ink they draw; Files says why an empty list is empty; the clock marker never touches the time
(the time and the marker rendered into SEPARATE surfaces and compared by lit-column extent — the
first attempt passed with the old constant because the fake's uniform advance made the time
narrower than the real font); the medium clock's digits are evenly spaced; the switcher band holds
the name it draws; the Hold'em seat strip stays inside its band AND still draws the money; tmux says
a host is quiet on every level; a staged settings row says it applies on the tap; the flow line box
holds the ink it draws; a wheel closed mid-spin stops spinning; the shell settles after a wheel is
cancelled mid-spin. `TorrentsTest.aDetailsPageSaysTheHostHasStoppedAnswering` and
`MusicWindowTest.theTransportRowsAreDimWhenNothingIsQueued`. `Review28PaintTest`'s menu pin was
rewritten for the new paint order.

**Every one was run against the unfixed tree** (the fix reverted in place, the test run, the fix
restored) and failed there. Two were caught being vacuous first and rewritten until they were not.

### 30.6 Battery

core **456** · desktop **11** · selfcheck **189** (the oracle on every settle, **283** runs, and
**20 consecutive clean runs** where the unchanged tree failed 2 in 20) · snapshots 49 × three runs
· `--games-check` · `--music-check` ·
`--epub-check` 58/58 · `--card-render` · `python3 tools/lint.py` 21 rules / 0 findings + selftest ·
`:phone:assembleDebug`.

What legitimately varies between two `--snapshot` runs, measured this round so the next reader does
not chase it: the wall clock, the status bar's throughput and ack readouts, Files' free-space
figures, and Music Mode's visualiser. Everything else is stable (§30.6a).

### 30.5b The pass after that — sweeping §30's own classes across the tree

A fourth reading, this time applying the classes THIS round established rather than looking for new
ones. Two more came out of it, both in code the earlier passes had walked straight past:

18. 🔴 **The Reader's library level drew nothing at all for an empty shelf** — the Files defect of
    §30.1 #6, one window over and by a different route. `ContentKit.paintList` returns immediately
    on a row count of ZERO, and the library's count is `folders.size + books.size`; so before the
    first scan lands, for a library with no books, and for a scan that FAILED, the window cleared
    its content band and drew nothing — no message, no lens. Main's row said why the whole time
    (`libraryState` carries "loading", "no books found", "library error: …"); the window did not.
    It now has the row that says so, with the scan's own words under it, and a tap that scans again
    — or, in a folder that has emptied under you, one that climbs back to the shelf. Driven live
    with the shelf pointed at an empty directory: "No books · no books found · tap to scan again",
    and the tap says "rescanning" in the op cell (the first wording, "reading the shelf again",
    arrived there as "reading the ▸" — the cell is 128 px).
    The sweep behind it: every `ListView` and `DocView` in the tree, checked for a count that can
    reach zero. Files coerces to one row and now says why; Torrents' three lists always carry a
    tail row (the R3-P11 rule); Music's always end in `Row.Menu`; the Reader's chapter picker always
    carries "From the beginning"; Settings' categories are built only from windows that contributed
    rows; Main's list is the window list; Games' are fixed. The Reader's shelf was the only one
    left, and every document painter handles its own empty case.
19. The snapshot harness took its picture the way the oracle used to — §30.6a.

**And one candidate that verification REJECTED, recorded so nobody re-opens it.** The two
three-line lens guards — `GamesWindow.lensThirdFits` and the Torrents listing lens — admit their
third line by its ASCENT, which is the shape §30 #11 was. Tightening them to the whole ink DROPPED
the line in five scenes at 100 %, so the render was measured instead of judged from a scaled crop:
in `42-games-tables` the band rules sit at 210 and 272, and the third line's ink runs 262-271 — one
row clear. The guards are right as they are; the crop that suggested otherwise was displayed at a
size where a rule and a descender touch. The tightening was reverted, and the snapshots confirm the
renders came back byte-identical.

A fifth pass over the product code after that found nothing further.

### 30.6a The snapshot harness takes its picture the same way

`--snapshot`'s `save()` read the sim panel from the script thread too, and these PNGs are the
evidence a person judges the design by — a torn one is a design decision made against a frame that
never existed. It goes through `Shell.sampleIdle` now as well. Measured before and after over pairs
of consecutive runs: six scenes differed before, four after, and the one that stopped differing was
`18-torrents-categories`, which had been drawing the theme's arrow in two runs of three and the
drawn fallback in the other. What still differs between two runs is live data and nothing else —
the wall clock, the throughput and ack readouts, Files' free-space figures, and Music Mode's
visualiser.

### 30.6b One thing left open, honestly

`OracleWalkTest` failed ONCE more after the wheel fix — `h=288 step 198 (SCROLL_UP): queued=1
reports=0` — and has not repeated in **eight consecutive full-suite runs** since, nor in any of the
dozen runs of that test alone. `queued=1` with everything else idle means either a handler that has
been running for the whole 120 s or a message the loop never took, and the walk's own numbers say a
clean run's worst settle is 46 ms, so "load" does not explain it on its own. It is not closed. What
this round could do about it is make the next occurrence say something: `settle` now prints the
stack of every thread inside `wm.damage` when it gives up, and `GamesWindowTest` and
`ActivationTest` print `quiescenceReport()` with theirs. If it fires again, read the stacks first.

### 30.7 Where the next session picks up

Read `CLAUDE.md` → `REMINDER.md` → `HANDOFF.md` §19–§30.

- **Not deployed.** This round was committed and pushed; the `damage` service still runs the §29
  build and APK 29/0.29 is still what is staged. Deploying is `./gradlew :desktop:stageJar && sudo
  rc-service damage restart` (`DAILY.md`) — Adam's call, in the moment.
- The live-walk driver (§28.2, §29.2) remains the instrument that runs the real providers under the
  real grammar. One thing this round adds to its lessons: **the harness is part of the system under
  review.** Two of the nineteen were in the gates themselves, and the sharpest defect of the round
  came out of a test bound firing — so when a bound fires, measure the normal case before calling it
  load. `OracleWalkTest`'s settle now prints the stacks of every thread inside `wm.damage` when it
  gives up, so the next one says where.

---

## 31. Canvas scrolling ships the translation (2026-09-05, Adam's report)

Adam, testing on glass: *"scrolling text in apps like Tmux is really slow, like 1-1.5 full seconds
between swiping on the ring and seeing the text actually jump to the new position."*

### 31.1 What it measured, at both ends

**What a tmux scroll sent** (MEASURED, the sim's exact encoded bytes, one live instance under a
scratch home): one mode-3 delta covering the WHOLE content area — 7,395 / 8,380 / 9,942 / 10,225 B
on four consecutive scrolls. A list scroll in the same session: mode-9 copies plus small deltas,
72–2,215 B.

**What that costs on the glasses** (MEASURED, 11,210 flush pairs in the production journal,
restricted to flushes with nothing queued ahead of them so this is not queueing):

| flush size | n | median submit→ack |
|---|---:|---:|
| < 500 B | 3,137 | **65 ms** |
| 0.5–1.5 KB | 456 | 152 ms |
| 1.5–3 KB | 220 | 196 ms |
| 3–6 KB | 240 | 526 ms |
| **6–12 KB** | **277** | **1,193 ms** |

10 KB ÷ the ~6.9 KB/s those big flushes imply ≈ 1.45 s. That is Adam's number, from the other
direction.

### 31.2 The mechanism

`Shell.scrollFocused` has three arms. `ListView` → `startListSlide`, `DocView` → `startDocSlide`:
both do the move on the device with a mode-9 rect-copy and send only the newly exposed strip —
`CLAUDE.md`'s endless-scroll rule, `DESIGN.md` §5.2/§5.3. `CanvasView` → `h(delta);
composeContent()`, and `paintContentOf`'s canvas arm was `paint(); damage(content)`. Every row moves
in a scroll, so the truth diff correctly found the whole area changed and shipped it. Tmux's live
pane and its scrollback are canvases; so are Music's lyrics, volume and Now Playing, and the Hold'em
table.

Note what was NOT wrong: the damage rect is a scan hint, not a payload. The compositor already
sends only what actually differs — that is why a live pane's own updates cost ~2 KB while a scroll
costs 10. Nothing was being sent carelessly; the translation simply was not being declared.

### 31.3 The fix, and why it is a DETECTOR

`CanvasShift.detect` compares the frame before the repaint with the frame after, finds the vertical
translation, and the shell declares it. Not a new field on `CanvasView` for each window to fill in,
for two reasons:

- **Coverage.** Every canvas window gets the cheap path without knowing the file exists — the tmux
  pane Adam scrolls, the same pane when the terminal itself scrolls a line in (which no `onScroll`
  would ever see), Music's lyrics, and windows not written yet. Exclusive mode, the other surface
  that owns its damage, goes through the same helper for any band big enough to be worth it.
- **It cannot be got wrong.** A window that reported a translation it did not make would put wrong
  pixels on the glass. A detector that VERIFIES the block byte for byte before declaring it cannot.

**And it cannot draw a wrong frame even if it were wrong.** `Compositor.declareShift` replays the
copy onto the per-lens SHADOWS and then diffs them against the truth of `composed` under the plane
map. A shift that is not real costs bytes to repair, never correctness. That property is what made
it safe to detect rather than declare — and it is worth remembering the next time something wants
to ride the copy path.

Three details that are load-bearing:

- `declareShift` gained `movePending`. The slide path records damage and THEN shifts, so its pending
  rects must travel with the content. A canvas repaint is the other order — the damage recorded is
  already the new frame's — and translating it would widen the scan into rows that never moved, off
  the panel at a large offset. Canvas and exclusive pass false.
- A row of one value matches at every offset, so uniform rows get no vote in choosing the offset
  (they are still copied). Without that, a pane's blank half elects a shift that saves nothing.
- The detector refuses a region off the compositor's 4×2 CELL grid. ⚠ Not a firmware rule — mode 9
  takes **full uint16 coords** (`zlib_glue.c`), validated for same-size and in-bounds only, and
  `rect_copy_4bpp` has a nibble path for an odd left/width. The constraint is OURS: `declareShift`
  moves the per-lens `unknown` marks with the copy through `moveCells`, which is cell-quantised
  (`CW`/`CH` **are** `X_STEP`/`Y_STEP`). The canvas path always passes the content rect, legal by
  construction; exclusive mode passes rects a WINDOW chose.

### 31.4 The bug inside the first draft of it

The byte verification advanced its cursors AND indexed by the loop variable — `pix[a + 2i]` — so it
compared the wrong bytes and declined every shift there is. The first live measurement after the
"fix" was unchanged from before it, and a probe in the detector showed it finding real translations
(`dy=-110`, 278 rows matching) and then failing verification on the first row of every one.

It failed SAFE: declining a shift is the old behaviour. That is why only a measurement found it, and
why the positive case of the unit pin is the pin for it.

**And a SECOND one, found on a re-read after the battery was already green** — a gap in the same
guard. The offset sweep runs `dy = -h + 2` stepping by 2, so for an **odd** region height every
candidate offset is odd and `region.y + s0 + bestDy` lands on an odd row however carefully `s0` and
`len` are snapped. `detect` now requires an even height alongside the other three conditions.

🔴 **Read the correction in the bullet above before quoting a severity for this.** The first write-up
of it here — and the code comment it came from — said the firmware refuses an unaligned mode-9 rect
in silence, so an odd row would leave a wrong frame on the glass. **That is false, and it was
asserted without reading `zlib_glue.c`, which is the one thing `CLAUDE.md` says never to do for a
mode contract.** Mode 9 takes full uint16 coords and has no alignment requirement at all. The real
consequence is confined to the per-lens `unknown` cell map that `moveCells` carries with the copy,
which can only matter after a lost flush. The guard is still worth holding — the grid costs nothing
and the diff scan reasons in the same cells — but it is bookkeeping hygiene, not a wrong frame.

Unreachable today either way: `layout.content.h` is even and no window returns an odd-height
exclusive rect. It is guarded in `detect` rather than in the callers because exclusive mode passes
rects a WINDOW chose. Two things about the pin are worth carrying:

- **The failing combination is odd height AND an odd shift.** An odd-height sweep proposes only odd
  offsets, so it declines an *even* translation harmlessly — the first draft of the pin used the
  40 px shift from the positive case and **passed with the guard removed**. Watched to fail only
  after the shift was made odd, and the value it then produced was `src=(16,81 608x368)`: row 81,
  one cell-row off the grid.
- The even heights either side still find their translation, so the pin holds the guard to PARITY
  rather than to size; and an even region asked for an odd translation declines, which is the
  harmless half of the same arithmetic.

### 31.5 What it costs now

Same instrument, same session, the same four scrolls:

| | before | after |
|---|---:|---:|
| entering history (not a translation — correct) | 8,405 B | 7,962 B |
| steady-state history scrolls | 9,756 / 11,086 / 11,216 B | 3,815 / 5,583 / 5,401 B |

**~11.1 KB → ~5.4 KB, about half.** Through the measured table above that is ~1,193 ms → ~526 ms.
MEASURED bytes; MODELED milliseconds — nothing here has been on the glasses yet.

What remains is the newly exposed strip, and it is real new content: `HIST_STEP` is 5 lines, the
strip measures 106 px, and five lines of dense terminal text encode to 3–5 KB. The only other lever
is the step size, which is a design decision and not one to take on a measurement's say-so.

Two supporting changes: `Gray8.blit` copies whole rows with `System.arraycopy` when nothing clips
(the shell's hottest copy — the slides, the menu's under-snapshot and this detector's previous
frame all move full-width bands), pinned against the per-pixel path in eight clipping shapes; and
no rendered surface changed.

**How that last claim was actually checked** — because the obvious way does not work. The 49
snapshot scenes are NOT byte-comparable between runs: the status line carries a live throughput
readout (`785K/s · 1ms` in one run, `1664K/s · 1ms` in the next), so two runs of the SAME build
differ in every scene that draws chrome. The comparison that means something keeps BOTH installs on
disk — build the change, copy `desktop/build/install/desktop` aside, `git stash`, build again, copy
aside, `git stash pop` — and runs them back to back inside one minute. Measured that way: **46 of
the 49 scenes differ only inside the status readout** (all differences within x∈[240,400] and a
band ≤16 rows tall), `10-silent.png` — which draws no status line — is byte-identical, and the
remaining three are the known live-data scenes: the real filesystem in `11-files-locations` and the
audio visualisers in `38-music-mode-480-bars` and `39-music-mode-288-scope`, all three of which
differ run-to-run on an unchanged build as well. ⚠ An earlier note in this section said the scenes
were byte-identical; they never were, and a plain `diff -rq` between two snapshot directories will
always report all 49.

### 31.6 🔴 A separate finding: the documented latency curve describes four hours

`overview.md` §5.2 records `ms ≈ 60 + bytes/50` from n=1,488 flushes, with 6–15 KB landing at a
median 201 ms. The same journal, cut by day and hour, says that is one session:

| when | n | < 500 B | 2–6 KB | 6–12 KB |
|---|---:|---:|---:|---:|
| 08-30 | 1,130 | 60 ms | 142 ms | **196 ms** |
| 08-31 00:00–03:00 | 3,038 | 55–74 ms | 113–142 ms | **198–265 ms** |
| 08-31 13:00–19:00 | 7,025 | 73–78 ms | 465–812 ms | **1,087–1,286 ms** |

A step change between 03:00 and 13:00 on 2026-08-31: the FLOOR barely moves (55–78 ms throughout)
while the TRANSFER term collapses about 6×, from ~50 KB/s to ~7 KB/s — which is the §5.1 figure for
the stock path. 10,063 of the 11,210 flushes are on the slow side of it, and **Adam's own 1–1.5 s
on the phone agrees with the slow regime**, so that is what daily use is priced at, not the curve.

Candidates, none tested: a second BLE central (the APK mission began that morning, and `CLAUDE.md`'s
"one central at a time" says exactly this); distance and interference (he left for work); a
connection-interval renegotiation; a change in the write path between the sessions; the 08-30 sample
being n=25 in that band. Settling it needs a radio experiment on hardware, which is Adam's call —
recorded here so the next person does not price anything with the old slope. `CLAIMS.md` regrades it.

### 31.7 Pins

`Review30Test`: `theCanvasShiftDetectorFindsATranslation` (a real translation is found with the
right offset, grid-legal and byte-identical; an unchanged frame, an unrelated frame, a block under
the floor and an unaligned region are all declined; and the sub-band form exclusive mode uses, whose
`was` origin the canvas call site never exercises); `aCanvasScrollShipsTheShiftNotTheScreen` (a real
shell over the sim: a scroll must cost under a third of what a full change of the same window costs
— no absolute byte number, so no chrome subtraction and no tuning); `theWholeRowBlitAgreesWithThePerPixelOne`.
All three were run against the unfixed tree and watched to fail.

The correctness guard is the standing one: `--selfcheck`'s per-lens truth oracle on every settle and
`OracleWalkTest`'s random walk both compare belief, truth and glass, and a declared copy is replayed
onto the shadows before that diff runs. `aCanvasScrollShipsTheShiftNotTheScreen` also reads the two
SIMULATED PANELS after each scroll and compares them to the compositor's belief, on the loop through
`sampleIdle` — a copy the firmware placed anywhere but where the shadow put it, or refused for
alignment (which it does in silence), is a wrong frame, and a byte count would call it a win. The
check asserts the panel is not blank first, so it cannot pass on nothing; it was watched to fail on
a single flipped pixel.

🔴 **And a coverage gap the change exposed, now closed.** `paintExclusiveDelta` had **no coverage at
all** — not the new shift path, the whole function. Two reasons, both in the walk: its
`SurfaceWindow.paintExclusive` returned an empty list for `full = false`, so a delta drew nothing;
and exclusive mode SWALLOWS every ring input (§4.9), so nothing ever asked for a delta in the first
place — a window there repaints only because it called `requestRender` itself, the way Music does
from its channel. The walk's window now paints a band that translates (a strict SUB-rect of `safe`,
so the non-zero snapshot origin is exercised — the canvas call site always passes the whole content
area) and asks for four renders on entering exclusive mode. Measured after: 52 exclusive deltas
across the four heights, ~50 declared translations, and the truth oracle green on all of them.
**Check this the next time a shell surface is added: a paint arm that returns nothing, or a mode
that never invalidates, is a function the oracle cannot see.**

### 31.8 A second, unfinished thread: the intermittent `queued=1` settle

While verifying the above, `:core:test` failed roughly one run in four with

```
shell did not settle: queued=1 reports=0 status='ok'
```

in five different classes (`OracleWalkTest`, `GamesWindowTest`, `ActivationTest`, `LongPressTest`,
`MusicModeTest`) — never the same one twice. It is NOT this change: it reproduced on the unmodified
tree, and it is what has been calling the suites "flaky" for a while.

What was established:

- `OracleWalkTest.busyThreads()` was widened to dump every worker when no thread is inside our code.
  It reported **no thread anywhere with a `wm.damage` frame** — the whole stack, not the top frames.
  So the loop is **parked or ended, not busy**, and no amount of `Dispatchers.Default` starvation by
  our own code explains it.
- `msgs` is `Channel.UNLIMITED` and is never closed, so `trySend` cannot refuse and a lost message
  is not the mechanism either.
- `loopLaunched` is set at start and never reset, but `startLocked` launches the loop
  unconditionally, so a same-instance restart always has one. That hypothesis is dead.

Three things went in so the next occurrence names itself rather than needing another investigation:

- **`post()` undoes its own count on a refused `trySend`, loudly.** `queued` is what `isQuiescent()`
  reads; a message counted but never delivered would leave the shell permanently "busy" to every
  harness and every gate — a silent failure in the one counter whose whole job is to be trusted.
- **`loop()`'s `finally` drains what is left and decrements for it**, and says how many. A count
  must not outlive the loop that would have cleared it.
- **`quiescenceReport()` distinguishes the two cases**: `LOOP-ENDED` when the loop is gone, and
  `in=<Msg>/<ms>ms` when it is parked inside a handler — which message, and for how long.

⚠ **Unfinished.** Eight consecutive `:core:test --rerun-tasks` runs after those three changes came
back clean, which is suggestive and is not proof: the prior rate was about one in four, so eight
clean runs is roughly a 1-in-10 outcome if nothing had changed. Do not record this as fixed. The
next failure will print which of the two it is, and that is the thing to act on.

### 31.9 Battery

core **459** · desktop **11** · selfcheck **189** (the oracle on every settle) — **ALL CHECKS PASS
×11 explicit**, plus five earlier runs that exited clean · snapshots 49 × two builds back to back
(see §31.5 for how to read them) · `--epub-check` 58/58 · `--music-check` ALL PASS ·
`--games-check` ALL PASS · lint 21 rules / 0 findings · `:phone:assembleDebug`.
`:core:test --rerun-tasks` run fourteen times with no failure — which is also the eight-run
evidence §31.8 leans on, and it is still not proof.

## 32. The latency pass (2026-09-05, Adam's ask) — the survey, twelve changes, and the joint plan

Adam: *"tell me all of the things that can be done across the whole scope plus each individual
window to optimize it for latency and quick response and quick loading with minimal impact on
design aesthetic or good looks or features and usefulness"* — then *"do all the things you can do
without me … anything that would be best done AFTER we work together to measure things … will come
later."* The phone APK driving the glasses is the primary use case and the priority. This section
is the record: what the reading found, what shipped, what it measured, and the plan for the rest.

### 32.1 What the survey found

Where the link time goes, from the production journal (MEASURED, 11,206 acked flushes):

| flush size | share of flushes | share of ack time |
|---|---:|---:|
| < 500 B | 67 % | 35 % |
| 0.5–3 KB | 24 % | 24 % |
| ≥ 3 KB | 9 % | 41 % |

The op mix over the same journal: 25,863 deltas, 4,708 copies, 420 stereo pairs, 94 keyframes.
Wheel flushes (`+switcher` labels): median 0.8–0.9 KB, p90 2.5–3 KB, median ack 119–133 ms.

The levers, ranked (each with its grade; the modeled ones say so):

1. **The radio path** — see §32.2. The slow regime IS the daily path.
2. **The texture cache for text (modes 12/14)** — built, modeled, not adopted; gated on the
   on-glass check (`IMPLEMENTATION.md` → The texture cache). A list row is ~1–2 KB of pixels
   today and 8 bytes plus the string as a mode-14 draw, and it burns no fid. This converts most
   ≥ 3 KB flushes into sub-500 B ones. The largest byte-side lever there is; a joint item.
3. **A reserved window slot for input** — shipped (§32.3). The pump filled all three slots with
   whatever was pending, so a tap's first flush could wait behind three 150–1,200 ms frames.
4. **Motion adapted to the measured link** — shipped for the wheel only (§32.3). A list notch is 3
   slide frames and a doc notch 5 (derived from `Slide.step`'s arithmetic — modeled) but their
   strips are small and pipelined; the wheel's 4 repaint frames at ~0.9 KB each are ~0.8 s of link
   time per notch through the phone (modeled from §31.1's table).
5. **Host CPU on the loop, unmeasured until now** — `price()` recompressed every candidate rect
   per pair, `emitDelta` again; every drawn string was a fresh bitmap and a per-pixel blend; the
   state file was written on the loop every 2 s after a change. All shipped (§32.3), all
   pixel-identical, and the journal now carries the numbers (§32.4).
6. **Wrap cost on the phone** — the Reader measured every candidate line, one platform measure
   per WORD of a book. On the PC that is 13–80 ms per book (MEASURED, three real books, AWT);
   on Android each is a shaping pass over a string nothing has seen before, so seconds per open
   (MODELED — no adb, no measurement). Shipped (§32.3).
7. **Per window.** Reader: extraction 24–226 ms per book (MEASURED, all 58) — small; the wrap
   above; the layout is redone on every height and scale change with no cache; a book opened
   first on the PC replica is a full EPUB transfer at work (prefetch is a joint item). Tmux: 1 s
   polling averages 500 ms of lag before the pane even repaints — control mode (`tmux -C`)
   pushes output as it happens (joint item, a redesign of the provider); remote hosts spawned a
   fresh `ssh` per poll (shipped: multiplexing). Torrents: `Http` called `disconnect()` after
   every request, a TLS handshake per TorrentLeech page (shipped: keep-alive). Music: NOW PLAYING
   is already paced at 5 s; Music Mode's visualizer (off by default, 8 fps when on) is a flush
   per frame and the reserved slot is what keeps it from monopolising the window. Games: pure
   CPU, paced by design. Files, Main, Settings, Silent, Notifications, Keyboard: nothing
   significant — small deltas, cached icons, one flush a minute.

### 32.2 🔴 The slow regime is the phone's radio path — and the captures said more than we read

§31.6 recorded a step change on 08-31 between 03:00 and 13:00 and called its cause unknown. It is
a change of RADIO PATH, not of time (grade **C**): the fast hours (08-30 22:00 → 08-31 03:00) are
the PC-direct first-light and refinement sessions of §11–§12; the slow hours (08-31 13:00 →) carry
the journal's own stall notes naming `aphone`, and `damage.log` for that period reads `driving via
remote:aphone` — the PC shell driving through the PHONE's BLE (the pre-§19 daily mode). Not **M**,
because the journal carried no transport field; it does now (`via`, §32.3). Adam's on-glass 1–1.5 s
agrees with the slow side and he is on the APK, so **the daily driver is priced by §31.1's slow
rows**, and the ~6× between the paths is inside the phone's BLE stack, not the glasses.

Then the captures. `overview.md` §5.1, `CLAIMS.md` row 44 and `captures/README.md` all said handle
65's connection setup was outside both windows and that no `LE_Connection_Update` was ever issued
for it. **`research/linkparams.py` (new, offline, stdlib) says otherwise, and the events are in the
files** (grade **M**): both captures hold the Enhanced Connection Complete for all three handles;
handle 65's peer address equals the configured RIGHT lens (checked against the config without
printing it); it runs at **30 ms** while active and the **glasses** move it to **90 ms / slave
latency 4** when idle (three L2CAP 0x12 requests in `allbutimages.log`, each granted by the phone's
stack with an `LE_Connection_Update` for 65); DLE is on (247 B); no PHY updates. What stays true is
narrower: the official app never asks on its own initiative for 65. All three documents carry the
dated correction. The consequence for us: at 30 ms and ~1.6 packets per event you get 7–13 KB/s,
so the phone-side question is whether `CONNECTION_PRIORITY_HIGH` (11.25–15 ms) is granted at all —
and the APK now reports the answer itself (§32.3, item 10).

### 32.3 What shipped — twelve changes, each self-contained, no rendered surface changed

1. **The journal says which radio path, and what the host cost** (`Journal.flushSubmitted`,
   `Shell.pump`): every submit line carries `via` (the transport's name), `handleMs` (the loop's
   time from taking the message to the flush — every paint it caused) and `assembleMs` (the
   compositor's diff, partition and compress). `tools/journal_report.py` reads a journal by hour,
   by `via`, by size band, and prints the shell's CPU per flush and every link/fault/panic note.
2. **The compositor memoises `compress(rect)` for one assemble** (`Compositor.compressCache`):
   cleared at both ends of `assembleFlush`; `composed` does not change inside one, so the bytes
   are the same bytes. Pixel-identical by construction.
3. **Both rasterizers cache measures and rendered coverage** (`core/text/GlyphCaches.kt`,
   `AwtText`, `AndroidText`): keyed by the text and the RESOLVED font, bounded by wholesale
   clearing (16 k measures, 8 MB of masks). The uncached path is byte-for-byte the old one; the
   blend reads the mask it used to read from the bitmap.
4. **`Wrap.wrap` decides from an additive estimate outside a ±24 px band and measures exactly
   inside it**; each distinct word is measured once. `WrapEstimateTest` pins equality with the
   every-candidate loop over random text for an additive rasterizer (AWT applies no kerning) and
   for one that kerns a full pixel tighter at every space (the Android direction, exaggerated),
   plus the once-per-word count. The hard-break path for oversize words is untouched.
5. **One window slot is reserved for input** (`Shell.pumpPriority`): the pump that follows a ring
   event or a typed line may fill the window; every other pump — ticks, pushes, animation
   continuations, completions — stops one short. Instant transports never see it.
6. **The transport measures the link in a way that tells the two regimes apart**
   (`LinkState.floorMsEma` over flushes < 400 B, `transferMsPerKbEma` over flushes ≥ 1 KB): the
   all-sizes EMAs could not, because two thirds of flushes sit on the same ~60 ms floor on either
   path. The seam carries both (with defaults, so an older peer still decodes). **The wheel spins
   in 2 frames instead of 4 when the measured transfer term is above 50 ms/KB** (the regimes
   measured ~20 and ~125); nothing adapts until a flush of 1 KB or more has been timed, and the
   shell journals the regime when it flips. `DESIGN.md` §6.3 records the rule.
7. **The state file is written off the loop** (`Persistence.saveAsync`): the encode stays on the
   loop (microseconds), the write goes to one daemon thread in submission order; the shutdown save
   is synchronous and waits its turn, so the last state is on disk before `stop()` returns; a
   failed write reports back through the loop as before.
8. **`Http.request` keeps connections alive**: `disconnect()` only on the failure path. POSTs
   stream a fixed length, which the JDK never retries, so nothing can double.
9. **Remote tmux hosts multiplex one ssh connection** (`ControlMaster=auto`, `ControlPersist=60`,
   `ControlPath=~/.damage/ssh-%C`); a socket that cannot be created falls back to a plain
   connection with a warning.
10. **The APK reports its connection parameters and PHY** (`BleTransport`): Nordic 2.7.5's
    `setConnectionParametersListener` fires on every update, ours or the glasses'; the priority
    request's `.with` callback reports what was granted; `readPhy()` records the PHY. They land in
    `LinkState.linkParams`, the shell journals each change (`kind: "link"`), and the log line
    carries the same text — so "was HIGH granted, and did the glasses move it back" is in the
    phone's journal without adb.
11. **Every host serves its journal**: `GET /journal?token=T[&tail=N]` on the replica server (both
    hosts). Verified live on a scratch-home sim instance: 200 with the token, 403 without, the tail
    cut to whole lines, the new fields present.
12. **`research/linkparams.py`** — the capture parse of §32.2, address-attributed, so the claim is
    reproducible rather than argued.

Not touched, on purpose: the scroll step sizes (a design decision), the list and document slide
frame counts (their strips are small and the first frame is as fast as a snap), the 5 s idle chrome
tick, Main's rest repaint, Music Mode's visualizer rate.

### 32.4 What it measured

- **No rendered surface changed.** Two installs on disk (the untouched tree and this one),
  `--snapshot` back to back: **49 scenes — 2 byte-identical, 45 differ only inside the status
  readout** (the live `K/s · ms` cell, x∈[240,400], ≤16 rows), and the 2 that differ elsewhere are
  the known live-data scenes (`11-files-locations`, the real filesystem; `38-music-mode-480-bars`,
  the visualiser). The §31.5 method, with a Java comparer in place of `diff -rq`.
- **The first compose/assemble numbers ever, PC, sim:** the Main keyframe — handle 30 ms,
  assemble 21 ms; a delta frame — 13 / 9 ms; over a 22-flush scratch session median 4 / 2 ms,
  p90 13 / 9 ms. The phone's will be higher; that is what the field is for.
- **Reader, PC:** extraction 24–226 ms per book over the shelf (`--epub-check`, all 58); the wrap
  13–80 ms per book (three real books, 3k–33k lines, AWT). The phone's numbers are owed.
- **The scratch sim instance journaled `transfer 61 ms/KB — SLOW regime`** — the simulator models
  the stock curve, which is slow by the design's own numbers, so the wheel spins in 2 frames under
  `--transport sim`. The harnesses run instant timing and stay at 4.

### 32.5 Battery

core **462** (459 + `WrapEstimateTest` ×3) · desktop **11** · `--selfcheck` ALL CHECKS PASS
(283 oracle runs; run ten times) · snapshots 49 × three runs · `--epub-check` 58/58 ·
`--music-check` ALL PASS · `--games-check` ALL PASS · lint 21 rules / 0 · `:phone:assembleDebug`
· the `/journal` route exercised live.

### 32.6 What is owed — the joint plan, in order

1. **Deploy** (Adam's call): `:desktop:stageJar && sudo rc-service damage restart`; install APK
   **30/0.30** (staged). Nothing here changes what the glasses show.
2. **Read the phone's journal after a day on the APK** — no adb: from beardos,
   `curl -s 'http://aphone:7403/journal?token=…' | python3 tools/journal_report.py -`. The
   `link` notes answer whether HIGH was granted and what the glasses renegotiate; the by-hour
   table gives the daily path's real curve (home WiFi vs work vs WiFi off is the coexistence
   experiment, and it needs nothing but the hours).
3. **Then decide the radio work**: if HIGH is refused or reverted, re-request on a cadence or on
   activity; if it is granted and the transfer term is still ~125 ms/KB, the wall is the write
   path or coexistence, and the next instrument is a phone BTSnoop with the APK driving — via the
   Android bug-report mail path already used for `captures/` (no adb needed).
4. **The texture cache for text** — step 1 is the on-glass check of modes 12/13/14 against the
   simulator (`REMINDER.md` items 19–20), on a dev build; step 2 the emit strategy for chrome and
   list rows behind a setting; step 3 tmux lines. The largest byte-side lever, and the one that
   makes the slow path liveable if the radio cannot be fixed.
5. **On-glass verdicts** on the two behaviour changes: the 2-frame wheel on a slow link (a
   constant if he wants 3), and the reserved slot's feel under a visualizer or a live pane.
6. **Per-window, each after its measurement**: tmux control mode (pushed output, no 1 s poll);
   the Reader's phone-side wrap and a layout cache per (book, width, scale, face); prefetching the
   open book to the phone (data policy is his); a persistent content channel in place of a socket
   per request; cold-start timing (parallel arm connects, the 800 ms prelude settle, the 2 s
   capability re-ask) once a phone journal shows what start costs.

## 33. The live measurement, driven from the PC (2026-09-05, Adam: "I don't have a day")

APK **30/0.30** installed and driving the glasses; the PC drove the phone's shell through its
replica WebSocket (`tools/glassdrive.py`, the §28.2 instrument rebuilt, with a snapshot of the
mirror between phases so every tap landed where it was meant to) and read the phone's journal back
through `/journal`. Ten minutes, 456 acked flushes, every gesture paced 2.5 s apart so every flush
is ISOLATED (nothing queued ahead — §31.1's method). The walk: wake from silent → Main list ×6 →
Reader → Classics → Frankenstein (first open, the chapter picker) → 6 notches down and back →
back to Main → Tmux → the DamageWM session's live pane → 4 notches of history and back → the
switcher chord from Main, 3 notches, cancel → Torrents transfers ×8 → Main → silent. No
destructive row was tapped; the Reader position was returned to the top; the shell was left in
silent mode where it was found.

### 33.1 The daily path, measured on the phone itself (grade **M**)

This session, APK-driven, isolated flushes:

| flush size | n | median ack | p90 ack | median handle | median assemble | p90 assemble |
|---|---:|---:|---:|---:|---:|---:|
| < 500 B | 283 | **72 ms** | 231 | 74 ms | 53 ms | 79 |
| 0.5–1.5 KB | 93 | 203 ms | 815 | 81 | 29 | 90 |
| 1.5–3 KB | 38 | 358 ms | 851 | 107 | 78 | 112 |
| 3–6 KB | 33 | 667 ms | 838 | 114 | 78 | 111 |
| 6 KB + | 9 | **1,036 ms** | 1,543 | 127 | 84 | 105 |

Five days of the phone's own journal (28,657 flushes, 08-31 → 09-05) say the same: 68 · 200 ·
390 · 641 · **1,237 ms** by the same bands. So §32.2's attribution is no longer inferred: **the
phone path IS the slow regime**, ~120 ms/KB above a ~70 ms floor, ~8 KB/s on big flushes. The
largest flush of the walk — the Torrents transfers list opening, 11,050 B, five deltas — took
1,543 ms; the Main keyframe (9,226 B) 1,276 ms; a Reader notch through the cover image's strips
(8,427 B — image rows are dense) 1,170 ms.

### 33.2 🔴 HIGH priority IS granted — and it does not help

The first thing the new `link` notes said, at 15:48:11: **`L 15.00ms/1/5000ms phy 1M/1M ·
R 15.00ms/1/5000ms phy 1M/1M`** — both arms at a 15 ms interval, **slave latency 1**, 1M PHY. So
`requestConnectionPriority(HIGH)` is honoured by the phone's stack, the glasses accept it, and the
transfer term is still ~120 ms/KB. The interval is not the wall. Latency 1 lets the glasses skip
every other event (an effective 30 ms when they choose to), and ~1.6 packets per event was the
capture's figure at 30 ms — so the candidates left are the packets-per-event count (the phone's
write path, one write per callback), BT/Wi-Fi coexistence on the phone, and the glasses' own
receive path. The regime EMA flipped to SLOW at 15:51:52 (69 ms/KB) and the wheel spun in 2
frames from then on; on-glass verdict still Adam's.

### 33.3 🔴 The phone's CPU is now a term of its own

On the PC the loop spends ~4 ms handling a message and ~2 ms assembling (§32.4). On the phone,
with §32's caches in the build: **a median 74–127 ms handling and 53–84 ms assembling**, per
flush, on the loop, before the radio sees a byte. A small flush therefore costs ~125 ms of phone
CPU and then a 72 ms ack — the CPU is the larger half. The journal cannot yet say WHERE inside
the loop (paint, the per-lens truth render, the diff, the priced compressions, the
mirror-agreement scan on every completion); splitting `handleMs`/`assembleMs` into those parts is
the next instrumentation, and it is one APK build away.

### 33.4 Lost acks, and what they cost

At 15:54:05, right after the Reader scroll burst, three fragment acks were lost together
(`msgId 3/4/5 pending across a full counter cycle`) and the shell raised the fault notice over the
book (a real event; a double-tap dismissed it, which is why one of the walk's backs went to the
notice). A lost ack holds its window slot until the msgId cycle comes round — 249 messages — so
the pipeline runs one or two deep for the rest of the walk. Over the five days: **49 lost acks**
(31 on 08-31), **2 full-window stalls** of 25 s and 48 s (the display frozen for that long), 55
control-lane "write characteristic gone" edges (session restarts, each a keyframe), 5 failed
flushes (supervision timeouts). The reference implementations slide the window forward through
up to ~3 consecutive missed acks (`overview.md` §5); ours reports the stall and waits for the
cycle. Releasing an earlier pending image ack when a LATER msgId's ack arrives (the firmware
completes in submission order) would remove both the shrink and the two stalls; the compositor's
lost-flush path (cells marked unknown, re-sent from the truth) already handles the pixels.

### 33.5 What the walk also showed

The switcher opened from Main centred on the most recent window (§1.3's "from MAIN" rule, as
designed); Frankenstein's first open went to the chapter picker; the tmux quick-keys list sits one
tap below the pane (the first pass scrolled the keys list instead of history — no key was sent —
and the second pass scrolled history); every snapshot matched the mirror the glasses had.

### 33.6 Instruments

`tools/glassdrive.py HOST TOKEN [--pace S] STEP…` — gestures, `wait:`, `pace:`, `snap:PATH.png`
(both lenses at 1×), `status`; the chord is `pace:0.3 hold release double pace:2.5`.
`tools/journal_report.py` for the journal, from a file or `-` (a curl). Both committed.

### 33.7 What is owed, re-ordered by what this measured

1. **Release pending image acks on a later ack** (§33.4) — a transport change with tests; the
   two multi-second stalls and the window shrink go with it.
2. **Split the phone's loop time** into paint / truth render / diff / compress / mirror check in
   the journal (§33.3), rebuild the APK, repeat this walk (two minutes). Then optimise the largest
   part; on the PC the whole thing is 6 ms, so the phone has a 20× to recover.
3. **The radio wall above the interval** (§33.2): a BTSnoop with the APK driving, via the
   bug-report mail path, to count packets per event; a Wi-Fi-off session for coexistence.
4. **Mode 14 text** (§32.6 item 4) — with 6 KB+ at 1–1.5 s and 3–6 KB at 0.7 s on the daily path,
   the byte-side lever is worth more than any other.

## 34. The pending-ack release, the CPU split, and the re-walk (2026-09-05, evening)

Adam: *"Build the pending-ack release and the CPU split, then re-walk."* Both built, APK 0.31
installed by Adam, the §33 walk repeated from the PC in the same ten minutes (wake → Main → Reader
→ Frankenstein six each way → tmux pane and history → the wheel → Torrents → silent), 341 isolated
flushes. Then a second cut from what it measured (below), APK **0.32** staged.

### 34.1 What was built

- **A later image fragment's ack releases every earlier image pending as lost**
  (`CfwTransportBase.releaseEarlierImagePendings`): the permit comes back at once, the flush whose
  final fragment it was fails with a named reason and the compositor re-sends from the truth, a
  non-final fragment's pending has nothing to complete, control-lane pendings are never compared,
  and a late ack that does arrive is reported as late. `PendingAckReleaseTest` drops one ack in the
  simulator (`SimTransport.notifyFilter`, a test hook) and pins the prompt failure, the freed slot,
  the named fault and the repair; a second case pins that nothing is released without a loss.
- **The journal's split**: `handlerMs` and `mirrorMs` inside `handleMs`; `truthMs`, `compressMs`
  and `compressN` (memo misses) inside `assembleMs`. `tools/journal_report.py` prints it.

### 34.2 What the re-walk measured (APK 0.31, isolated, grade M)

| flush size | n | ack med / p90 | handle | handler | assemble | truth | compress (misses) | diff + plan |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| < 500 B | 205 | 77 / 221 | 43 | 0 | 34 | 9 | 0 (4) | 22 |
| 0.5–1.5 KB | 91 | 206 / 411 | 98 | 9 | 27 | 6 | 1 (6) | 20 |
| 1.5–3 KB | 25 | 329 / 495 | 102 | 10 | 73 | 10 | 8 (7) | 49 |
| 3–6 KB | 17 | 543 / 708 | 126 | 27 | 75 | 8 | 8 (5) | 55 |
| 6 KB + | 3 | 1,140 / 1,295 | 176 | 103 | 83 | 10 | 18 (5) | 45 |

The wire is unchanged from §33 (nothing here touched it) and says so: the same medians by band.
The host's side, totalled over the walk: **handle 26.4 s = handler 8.9 + mirror 0.0 + the pump
before the assemble 17.5**; **assemble 15.4 s = truth render 3.3 + compression 0.8 + diff and plan
11.4**. Against 67.5 s of ack time, 41.8 s of phone CPU. So:

- **Compression is nearly free** (0.8 s, ~2 %) — the memo works and deflate was never the cost.
- **The per-lens truth render is 8 %**, the **diff and plan 27 %** (20–55 ms per flush).
- **Painting is ~63 %** — the handler's paints (a window switch, a wake: 103–194 ms) plus what the
  pump paints before the assemble: the slide steps, the chrome sync, the wheel and the notice
  (32 ms median on a WINDOW flush, 49 on a MAIN one, 76 inside the wheel). The mirror scan is
  zero: it skips while anything is in flight, which during a walk is always.
- The one shape that adds up: the wake from silent — 7,454 B, 194 ms painting + 107 ms assembling
  before a 1,140 ms ack.

The split could not yet say WHAT inside the painting; the second cut (§34.4) can.

### 34.3 🔴 §33.4's "lost acks" were mostly not image losses — a false alarm fixed

The re-walk's four "lost ack" faults were **msgIds 3–6 at session start, reported only when the
counter cycled** — and the release rule never fired on them, because they are not image pendings.
Recounting the five days: **45 of the 53 faults are msgIds 3–8 at session start**, in 16 episodes;
the four remaining clusters (56–58, 114–117, 198–202, 245–248) are the real image losses. The
session-start ones are the carrier CREATE's re-sends of the eaten-message class (§12): the first
copy lands in the firmware's previous-session teardown and is never answered, the 2 s re-ask is,
and the eaten copy's pending sits in the map until its msgId comes round — no window slot held,
nothing lost, but a `Log.e` that the phone turns into an error notice on the glasses ("msgId 3
reused while its ack is still pending") six minutes into every session. Both walks saw that box.

Fixed: a control pending that reaches the counter cycle is now a **fact** — `Log.w`, a new
`TransportEvent.Note` the shell journals (`kind: "control"`, saying whether a re-send was answered)
— never a fault, never a notice. An image pending reaching the cycle is still the fault it was
(with the release rule in front of it, that now means no later ack came at all). `Note` rides the
seam like `Fault` does.

**Also seen:** each eaten CREATE costs the 2 s re-ask before the session can start; with three
eaten per start (msgIds 3–5 in most episodes) that is ~6 s of cold start on every link edge, of
which the phone had 55 in five days. `CAPABILITY_REASK_MS` is shared with the capability gate and
is a radio-behaviour constant — a shorter re-ask is an experiment to run with Adam, not a change
to make blind.

### 34.4 The second cut, staged as 0.32

Inside the pump, per flush: `slidesMs`, `chromeMs`, `overlaysMs`; and across the message,
`textMs` — the time inside the rasterizer's `draw` (both platforms feed `TextProfile`). Between
them and the handler they cover everything the split called "painting". Battery green; installed
by nobody yet.

### 34.5 What is owed

1. Install 0.32, walk once more, read the paint split. Then the largest part goes first —
   candidates the numbers point at: the chrome sync's two changed cells per gesture (the input
   echo and the readout are fresh strings, cache misses every time); the slide step's band
   allocations; the diff's scan of the widened area on both lenses.
2. The release rule has not yet met a real loss on hardware (none occurred in either walk); the
   next real one will show as `ack lost — msgId N (sent after it) acked first` with the flush
   repaired, or as a `late ack` if the rule fired early.
3. §33.7's radio item and mode 14, unchanged.
