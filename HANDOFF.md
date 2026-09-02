# HANDOFF — the build record

**Map, newest first. A fresh session starts at `REMINDER.md`; this file is the decisions,
lessons and measured facts behind the current state.**

| § | what | status |
|---|---|---|
| **22** | **The overnight build (2026-09-01): §16 machinery + FILES + the 8-round review loop, run to convergence** | **current** |
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
   queued unshown; a shown box requeues unread. (Extended 2026-09-01: the context menu defers
   the same way, and an EMERGENCY cancels either surface instead of waiting — §22.)
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
  `E4:87:77:65:CD:50`, both public. ⚠ The addresses at `overview.md:1164` are a third party's.

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
drove the shell. Closed on hardware: PC-direct BlueZ works first try; link-death recovery
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
- **The switcher was dead since first light because of OUR source filter** — events 9/10
  arrive with `EventSource` ABSENT (source 0, documented all along) and the ring-only check
  discarded them. `LongPressTest` passed throughout because the harness supplied the source
  the wire omits. Lesson, same family as the ack enum: **a test default that "helpfully"
  supplies what the wire omits is a model erring permissive — inject what the firmware
  actually sends.**

## 13. The APK mission (2026-08-31) — DONE

Adam's ask, verbatim: *"Make sure it can do everything the PC system can do, including
connecting to the PC system and using both, as well as falling back to phone-only and
PC-only, just like in the design."* All of `DESIGN.md` §10's configurations now run on
hardware daily. Pre-radio hardening that shipped with it: the **seam heartbeat** (a silent
path death hands back in ~20 s, not TCP's minutes), the **pocket-liveness trio**
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
→ re-claim, silent driver death, and the full WiFi-edge loop. Mixed versions degrade to the
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
  (per-item sub-records, the §19.4 startup-race closure, a per-window continuity test in the
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

**Next:** Adam's per-window refinery pass on `EXPLOSION.md`, then the shared machinery in §16's
build order (state substrate → window channel → deep links + notify signature → kit), then the
first conversion.

## 21. The live refinery + Files chosen (2026-09-01, later)

The refinery ran in session and is recorded in **`EXPLOSION.md` §20** (which supersedes §18's
counts): 🪓 axed Deliveries · Calendar · Timers (§16.13 dies with it) · Search · **Weather**
(phone app preferred; NWS hedge 14.4 dies — the §4.5 emergency promise rides the WEA/CMAS probe
alone) · **Health** (dead both directions; aria is retired). ✅ Added: the **TORRENTS window**
(§19 — his "Yes!": private-tracker browse + add-to-qBittorrent + progress + done-notifications),
Feed comic sources (11.12), **caller ID as a §16.5 notification source**, the Info useful-stats
steer, and the Games 10b block (roster adds: cards/Minesweeper/Chip's clone; the emulation lane
gated on a ROM pace-screener; the Balatro real-game seam — LÖVE/Steamodded state-export beats
screencap vision). 🚫 The full rejected-ideas pile is recorded in §20 so none of it is
re-pitched. The wow order stands (§20).

🔴 **Adam chose FILES as the first conversion.** His design intent: G2CC-like + the graphical
wave; a locations root list (root, home, mounts); **tap = context menu with Open as the first
row** (two taps to enter a folder — uniform for every entry type); in-app viewers for text, PDF
and images "in nice ways". Design discussion live; the settled design gets its own record before
code, per `WINDOWS.md` step 3.

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
in-memory wins, corrupt store keeps memory); the **post-start reconciliation Run** (closes the
§19.4 debt); `freshen` skips absent keys (virgin-shell guard); live-apply is sub-aware and
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
`RemoteIcons.kt` (content-port `icon` op, theme-keyed cache with a wipe marker on theme change,
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
from the graded §5 table are recorded in its banner (row thumbnails v1.5; `appSettings()`
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
host greeting) so a silent path death reconnects instead of freezing a healthy-looking window;
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
380/404 · lint 0 · APK **16/0.16** staged (bump per install; the phone still runs 0.15 until
Adam installs). Jar staged; service restarted onto the build (kept driving via the phone,
untouched on glass). Reader writes transitional legacy offsets alongside sub-records —
**remove when the installed APK is ≥ 0.16** (with it: `restoreStateLive`'s map-authority and
`liveMapApply`).

Recorded limits, verified as designed or accepted (round 3): the Reader reset-progress picker
matches by TITLE (two same-titled books are indistinguishable in that list either way — a
disambiguation is a design item); a Settings double-tap revert applies its whole captured
snapshot (a peer sync landing mid-adjust rolls back — dual-active esoterica); the "+N" badge
counts already-read queue entries; the content-port pre-auth hello read has no time bound
(tailnet-only, tracked and closed on stop); the win channel has no app-level ping (a silent
path death is bounded by keepAlive and the write path's own retransmission); the L2 seam
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
  hosts, SelfCheck + Snapshot walks. The dead Global notify rows are gone.
- **What the harnesses caught**: the transfers cursor was a bare index into a LIVE list — an
  add under the wrap-end menu row moved the menu away from the cursor (the selfcheck's second
  menu visit), and the first fix chased an empty list's menu row to the end (the core test).
  The cursor now follows its row's identity (hash, or the menu row) across snapshots.
- **Measured (selfcheck)**: transfers list 9.0 % ink, details 6.4 %, the open keyboard 9–11 %.
  Battery at hand-off: core **219** · desktop 9 · selfcheck **89** · snapshots 26
  (8 new, looked at) · lint 0 · design shots byte-identical. APK **18/0.18** staged.

### 23.1 The review loop — round 1 (2026-09-01, night)

Five fresh reviewers over the whole build (keyboard + wiring · the two HTTP clients against
the qBittorrent 5.1.4 source and the live TorrentLeech fixtures · provider + channel · the
window · glue/harnesses/docs). ~55 findings; every one re-verified against the code before a
fix; all real ones fixed, the doc mismatches corrected, one recorded as a design exemption.
The ones that mattered:

- **The provider listener leaked on every desktop stack swap** (standby → BLE → handback): a
  dead shell's queue was fed one snapshot per poll for the life of the service. `TorrentsWindow.detach()`,
  called from `DesktopStack.stop()` like tmux's, plus the focus release.
- **The tracker's NFO landmark matched a commented-out template** that precedes the real
  element on the live page (found by running a port of the reader over the real page): comments
  are stripped before any landmark search; a listing row without `fid`/`name` is now refused
  loudly instead of silently dropped; `+` in a release name is no longer decoded as a space;
  multi-word tags survive; an HTML answer on the JSON endpoint counts as a refused session.
- **Announcements**: the announced stamp is kept across a removal (a qBittorrent restart's
  partial list re-added everything and would have storm-announced 38 finishes); "done" keys on
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
lint 0; the service runs the round-2 jar, APK 18/0.18 re-staged.

**Next (`REMINDER.md`):** round 3 — fresh eyes on the round-2 fix diff — until a round finds
nothing real; then on-glass verdicts for the keyboard's feel (row pitch at 288, the highlight,
the text-line pan) and the transfers list.
