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

- **Nothing on glass has changed hands.** APK **26/0.26** is still the staged build and 0.16 is
  still the last one observed installed; this review's fixes want a fresh APK before they are on
  the phone. The PC service wants `:desktop:stageJar` + `rc-service damage restart`.
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
- **Nothing from §25, §26 or §27 has been seen on glass.** The installed APK is **0.16**; **0.26** is
  staged and predates all of §27. That is the largest untested surface in the project, and it is the
  first thing worth doing with the glasses in hand — the four heights, the 130 % ladder, Music Mode
  and a Hold'em hand, in that order.
- The open items are §26.6 and §27.5; nothing in this round is half-finished.
