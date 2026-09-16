# FORK.md — the Damage CFW fork and the Damage rebuild

**Context for the reader.** Damage is a personal window manager for Adam's own Even Realities G2
glasses. The glasses run a community custom firmware (`g2flash`, published with its build method and
its sources) that gives an app direct access to the display; Damage draws whole scenes on the phone
or PC and sends pixels over Bluetooth. This document plans a fork of that firmware, built the same
way it is built today (a small appended code block and a short list of redirected call sites in the
stock image, compiled with clang, applied by a reviewed patch list), so that the glasses can draw,
animate and answer the ring themselves. Everything here runs on hardware Adam owns, on his own home
network, for his own use. It is display-rendering and UI work. Keep the wording plain (`CLAUDE.md`,
"Plain engineering wording") in every file, comment and commit this plan produces.

**Status.** Plan written 2026-09-12 (`HANDOFF.md` §48). **All other Damage work is suspended until
Phase 8 closes** (Adam, 2026-09-12): no new windows, no Feed polish, no popover build outside this
plan. New windows (Mail, SMS, Info, Notices, FF1, chess, …) resume afterwards. Progress is logged in
§11; a fresh session reads `REMINDER.md`, then this file, then `FIRMWARE.md`.

**Grading.** Every number is measured, modeled or inferred and says which. Every firmware fact
cites the decompile corpus (`reference/evenRealities-openCFW/g2/research/corpus/apollo-main/ghidra/decomp/`)
or a patch source, and lands in `CLAIMS.md`.

---

## 1. Goal and scope

Fork the firmware and extend it with: a drawing contract v2, a motion engine, ring-local input,
firmware-owned chrome, and an offline home. Rebuild Damage's compositor, transport, simulator, lint,
tests and **every existing surface and window** (Main, Settings, Reader, Tmux, Files, Torrents, Music,
Games/Hold'em, Feed; the switcher, notifications, the keyboard, silent mode, the popover family) to
use it, fully animated, each built whole to its best state with its latency profile and Adam's
on-glass verdict. Done means: tested, in daily use, docs and memory current, gates green.

**Why it is possible (measured / verified):** stock scroll and its edge bounce already run entirely
on the glasses (the glasses render their own UI locally, on both lenses, from mirrored input); the
installed CFW already draws cached images and glyphs on the glasses; the phone path's cost is
~60 ms per flush plus ~140 ms per KB (journal, 61,292 flushes); a window notch today is
105 / 204 / 522 ms median / p75 / p90 to ack, and the ack precedes the panel refresh, so the eye
waits longer than the journal says.

## 2. Decisions

Proposed 2026-09-12; treated as in force; Adam confirms or changes them at the close of Phase 0.

| # | decision | choice | why |
|---|---|---|---|
| D1 | stock base | **2.2.6.10** (current), g2flash `a5d1c31` patches kept | the decompile corpus (7,449 functions), openCFW's subsystem recovery docs, `verify_cfw.py`'s pins and every address in our records are 2.2.6.10; ack-before-render was verified on it; 2.2.9's gains (attributed long-press, light sensor, event 11) can be ported later |
| D2 | licence and repo | **fork g2flash as its own repo `~/damage-cfw`, GPL-3.0**; Damage stays clean-room (facts and the contract only, no firmware code) | distribution follows g2flash's model: stock fetched from Even's CDN, patches applied locally, no vendor bytes redistributed; a public release ships the app plus the firmware build script, never an image; openCFW's MIT overlay sources may be read and reused in the fork |
| D3 | popover family | **built in Phase 6** per `POPOVER.md` | its surfaces replace MenuSurface and the notification surface, which must be animated anyway; building them twice breaks "built whole" |
| D4 | offline home | **in scope, last firmware phase (7)** | the double-tap-with-no-phone screen; fall back to stock on any failure |
| D5 | new windows and games (FF1, chess, …) | **after Phase 8** | they are new windows; Hold'em (existing) is fully animated in Phase 6; FF1 first afterwards if Adam says so |
| D6 | motion policy | **motion answers input only**; games while playing are the exception; nothing moves on its own; the silent clock stays a minute step unless the refinery rules otherwise | the HUD stays ignorable at FAR, at work and while driving |
| D7 | what "instant" means (targets to measure) | local response starts within one motion tick (≤ ~20 ms) of the ring event; phone-started motion within ~100 ms; no visible lens mismatch; a workday costs no more battery than a measured margin Adam accepts | the numbers the stops judge against |
| D8 | compatibility | **Damage-only**; a5d1c31's features stay so the pipeline diffs against upstream; nothing new is designed for Faceclaw | one consumer, one contract |

## 3. Principles that bind every phase

1. **Small, inert by default, fail-open.** The fork keeps g2flash's shape: one appended code block,
   a short list of redirected call sites, and no NEW site on a path that runs before the first radio
   message. (Corrected 2026-09-13: the inherited a5d1c31 set already has two such paths — the
   primary heap arena's size, at boot, and the display task's copy call, which passes straight
   through for every stock refresh from boot on; `tools/verify.py` lists every site for review.) Every new behaviour sits behind a per-feature flag the phone arms per session; flags
   clear when the lease lapses (Phase 7's persisted flag is the designed exception). A phone that is
   gone leaves stock behaviour behind within 90 s.
2. **Hold-back after a reset.** The firmware exposes uptime (and a boot counter once F1.2 sources one). The keeper never
   re-arms a feature automatically when the glasses reset within N seconds of it being armed; it
   disarms, journals it and tells Adam. This keeps a faulty feature from repeating on every reconnect.
3. **Byte-exact by construction.** Every new op and motion verb is defined in integer arithmetic in
   `FIRMWARE.md`. The firmware implements it in C; Damage's simulator implements it in Kotlin from
   the same text, never from the C. A shared conformance-vector set (op sequences → expected shadow
   CRC per lens per step) runs on the host C build, in the Kotlin simulator, and on the glasses
   through a self-test op that reports CRCs. The truth oracle extends to the glass itself.
4. **One candidate flash per phase; fix flashes are cheap.** Host vectors and the simulator catch
   everything they can before a flash; a soak day and the journal catch the rest. A second
   *candidate* flash in a phase means the phase was mis-scoped: re-plan before continuing.
5. **Measured before modeled.** Each lever ships only after the measurement that says it pays.
6. **Adam's order.** Research → contract → the motion explosion → refinery → plan → build. The
   explosion and refinery happen in Phase 0 so the verb set is derived from what survives.
7. **Plain wording**, everywhere, every time.

## 4. Target architecture

**Firmware (contract v2, `FIRMWARE.md`), all behind flags:**
- *Telemetry:* heap free per arena, uptime, boot count, flags in force, last status code, per-frame
  worker, copy and panel-transfer microseconds (DWT), a per-frame "presented" notify (sequence, timings)
  on request.
- *Drawing:* per-lens (depth-aware) cached image and glyph draws; images with 16-bit dimensions and
  a clip rect; font tables up to 224 entries with kerning adjust bytes; unquantized fill; LUT over a
  rect (dim or brighten in place); invert; save-under scratch for popovers; a larger texture cache
  with a generation id and CRC that survives lease lapses.
- *Motion engine:* a fixed tick (rate set from the measured panel-transfer time — every present sends the
  whole panel — 30–60 Hz if that allows); programs
  uploaded as data — tracks of move-per-lens, fill, cached blit with a LUT ramp, vertical-scale
  blit, progressive blit with an offset (slide-in of a cached page) — on integer easing tables;
  play / stop / retarget; a declared end state; a completion event; the start synchronized across
  lenses through the existing cross-lens deferred path.
- *Local input:* a binding table armed by the phone: (event type, source) → program + parameter;
  the glasses run it at once and report the applied state (viewport index, edge hit, sequence
  number) to the phone; a region under a running program holds phone flushes until it ends.
- *Link:* hold the fast link while the lease is held (gate the glasses' idle-parameter request); a
  latency-0 fast profile if the measurement says so; multi-packet ATT writes if the capture says
  the phone is one-write-per-event.
- *Chrome:* clock, battery and link cells drawn by the glasses after the frame copy, at Global
  depth, from the cached fonts; the silent clock as a firmware minute program.
- *Offline home:* display ownership outside the EvenHub page, local input, RTC, fuel gauge,
  content saved while connected; a persisted flag; stock fallback on any failure; the both-temple
  long-press untouched.

**Damage:** `wire/CfwModes` v2, `TextureCache` v2, a `Motion` DSL, program and binding encoders;
`GlassFirmwareSim` v2 with the motion engine and local input; the per-lens truth/shadow model
extended with resident images, scratch and program state; the transport's arm / hold-back protocol
and `glass` journal notes; the scheduler (input preempts and retargets; the phone waits for local
programs before flushing their region); lint rules for programs and budgets;
`tools/journal_report.py` with the on-glass local-latency metric; every surface and window rebuilt.

## 5. Phases

Size is relative (S/M/L/XL). "Flashes" counts candidate flashes; fix flashes are extra and cheap.

### Phase 0 — Measure, research, decide (size S; no firmware; a few sessions plus a week of use)

**Measurements (read-only or APK-only):**

| id | what | answers |
|---|---|---|
| M0.1 | diagnostic overlay on for one probe session (`glassdrive.py probe:diag=show`, APK 0.45; never the shell), read on glass by eye or photo, then `probe:diag=hide` | free KiB of arenas 13/20/27 and which arena holds the container buffers; worker µs and the shadow → framebuffer copy µs → the heap budget. **Not the tick ceiling:** the overlay's `p` excludes the panel transfer, which nothing times today (`CLAIMS.md` 2026-09-13) — F1.3 measures it |
| M0.2 | 240 fps phone video through a lens: a stock dashboard scroll (cadence, the bounce) and a Damage notch (ring press → first visible change) | the real user-perceived numbers; if filming fails, Phase 1 telemetry is the fallback |
| M0.3 | BTSnoop with the APK driving (bug-report mail path) plus one Wi-Fi-off session | packets per connection event, PHY, the arm split, one arm drop from the radio's side → the link levers |
| M0.4 | 2M PHY request from the APK (`probe:phy=2m`, APK 0.45; `probe:phy=1m` returns) — **answered 2026-09-14 without the request:** the phone's record of the glasses' link-layer feature set has the LE 2M PHY bit clear (`CLAIMS.md`); the probe stays as a check | measured, not assumed |
| M0.5 | the sid-0x0F log stream across an arm rebuild (`probe:logger=on`, APK 0.45: RAM-only on the glasses, re-sent after each session start; lines land in the journal as `glasslog` notes) — **dropped as a pre-test 2026-09-14 (`HANDOFF.md` §50.9):** F1.2's uptime answers reset-vs-stall after the flash; the probe stays available | what the surviving arm logs as the other drops → reset vs stall, the hold-back rule's N |
| M0.6 | the glasses' battery changes journaled (`battery` notes, APK 0.45; `journal_report.py --since`) over a day on `Link = high` and a day on `balanced` (the §47 experiment) | the battery baseline every later soak compares against |
| M0.7 | **the phone-state experiment** (added 2026-09-14, redesigned the same evening, `HANDOFF.md` §50.6 — the glasses sat in the charging case through the 13th's fourteen daytime drops AND its two quiet nights, so the variable is the phone's day/night state): glasses in the case in the bedroom, session up, `probe:logger=on`, the snoop Enabled — ~2 h with the phone beside the case, screen off; ~2 h with the phone in another room in normal use; the journal's `link` notes say which condition drops, the snoop's vendor quality events what the radio saw before a timeout | whether distance/walls, the phone's activity or a periodic phone-side event gates the drops; with M0.5's log lines across a drop, reset vs stall — the hold-back rule's N and §42.2's ten narrowed — **optional since §50.9** |

**Research (decompile corpus + openCFW docs; findings into `CLAIMS.md`):**

| id | subject | where to look |
|---|---|---|
| R0.1 | the input path: the gesture mapper `FUN_00442d86` (subtypes 0/2/4/6/8/10/0xc/0xe/0x10), which subtypes are scroll, how input is mirrored to the other lens, how the EvenHub UI handler turns them into SysEvents — **read 2026-09-15** (`CLAIMS.md`, `research/fork-reads-2026-09-13.md` "R0.1"): the mapper is the display thread's `inputEventDataHandler` (one UI code per event id, table in the notes); input reaches a display thread ONLY through the sync framework's listeners, on both lenses (the type-7 poster has two callers, the master's and the slave's listener); the input manager runs on RIGHT only (both-temple detection, a 1 s cross-device lockout) and sends every event to the peers; both lenses can send over the inter-lens link; the per-notch scroll is the EvenHub page's kind-2 container message. Still U: which ids the slides/swipes carry (the touch processor and ring service), the TinyFrame role byte | corpus; `g2flash/patches/gesture_fwd.c` for the two known sites |
| R0.2 | the inter-lens link: `uart_sync.c` + the sync framework (master/slave TinyFrame over a UART); how a display step runs on both lenses together — **read 2026-09-13** (`CLAIMS.md`): RIGHT forwards the completion through `FUN_00464BB2`, a non-blocking queued send; the UART rate is U | `g2-uart-sync-recovery.md`, `g2-sync-framework-recovery.md` |
| R0.3 | the refresh path: the display task `FUN_00473c44` passes the queued rect to the ULED manager's async refresh; whether either panel driver honours the rect (JBD4010 and Hongshi A6N-G both have partial-refresh entries); which panel Adam's pair has (chip-id read) — **read 2026-09-13** (`CLAIMS.md`): the async path sends the whole panel on both drivers; JBD4010's partial path writes per row, A6N-G's moves nothing; the selector and the active-record word are known, Adam's panel is U; still open: the MSPI clock that sets the transfer time | `g2-uled-manager-recovery.md`, `g2-uled-jbd4010-recovery.md`, `g2-uled-a6ng-recovery.md` |
| R0.4 | the connection-parameter policy (`app_connect_params.c`, 14 functions mapped): the fast and slow profiles (`_connectParamReq_impl` and its profile table), where the idle request is issued, the cleanest lease-gated redirect — **read 2026-09-13** (`CLAIMS.md`): a 60 s timer carrying the slow event is armed at connect and after every update; every submit enters `FUN_00476CBC` (the F1.6 site); the profile words and the checks inside the submit are still unread | `g2-app-connect-params-recovery.md`, `tools/manifests/g2-app-connect-params-*.tsv` |
| R0.5 | stock helpers to call: RTC getter, fuel-gauge getters, crc32, kvdb read/write (Phase 7's persisted flag), the dashboard launch path and module registry (Phase 7) — **read 2026-09-15** (`CLAIMS.md`, the notes "R0.5"): the KV get/set are `FUN_0054116E`/`FUN_005411F2` over FlashDB with no lock of their own (the bracketing call is the tick reader; §50's "takes a lock" corrected); the fuel-gauge record `0x20073B18` is referenced from seven pools (offsets per openCFW, C); the RTC setter is `DRV_RtcSetTime` at `0x0047EE78` over the HAL, the stock getter still to name; app 3 = the dashboard (I), the registry per openCFW's `ui_module_registry.c` (C); crc32: none callable (2026-09-14) | `g2-drv-rtc-recovery.md`, the chg_bq27427 overlay, `ui_module_registry.c`, `ui_startup_app.c` |
| R0.6 | heap: which arena holds the container buffers; a budget for cache growth, scratch and staged content — **read 2026-09-13**: arena 13 or 20 (I, sizes); M0.1's readout decides | M0.1 plus `patch_compress.py`'s arena notes |

**Design work:** `FIRMWARE.md` v2 filled in from a skeleton to a draft; the conformance-vector
format; **the motion explosion and refinery** (the explosion's draft list: `MOTION.md`, 2026-09-13) — every animation idea per surface and window (shell:
Main notch with recede/pan/advance, wheel spin, popover reveal/cover and depth step, notification
slide, the keyboard, height switch, back-to-Main, the wake; windows: Reader page slide, list panning
with bounce, Files/Torrents rows, Music card and Music Mode, Tmux history scroll, Feed strips and
comics, Hold'em deal/flip/chips/showdown; Settings previews), then Adam's verdicts. The surviving
list fixes the verb set. Revisit `DESIGN.md` §0's cost-based exclusions (fades, dim-behind,
banners): Adam's call now that cost is gone. Lock D1–D8.

**Status 2026-09-14 (`HANDOFF.md` §50.9, Adam's ruling):** M0.4 answered (no 2M PHY); M0.3 done bar a
drop (four full captures); M0.5 and M0.7 dropped as pre-tests (F1.2's uptime answers reset-vs-stall after
the flash; the keeper re-uploads on every start anyway); M0.6 day one on `high` running, day two optional;
M0.2, the refinery and D1–D8 at Adam's pace before Phase 3. **Only M0.1 (one minute) stands between here
and Phase 1's build and flash.** The arm drops are recorded (§42.2, §49.1, §50.6, §50.8), cause U, and not
this plan's work: Adam never sees one while wearing the glasses.

**Exit:** the numbers in a table; the verb set; the contract draft; the decisions. No flash.

### Phase 1 — The fork pipeline and the first flash (size M; 1 flash) — **the first flash DONE 2026-09-15 (`HANDOFF.md` §55): both lenses on pin `c5e4f8b7…`, the self-test green on glass, the panel JBD4010, the transfer 1.2–6.6 ms per present, PROBE + PRESENTED armed; F1.7 folded into Phase 2 (Adam, 2026-09-15); the noon read `HANDOFF.md` §56**

**Built and flashed (`HANDOFF.md` §49–§55):** the fork pinned to our clang with `tools/verify.py`; the x86 host
harness (`host/`: the vectors, the self-test form, `test_damage_ext.py`); `patches/damage_ext.c` = F1.1–F1.5 and the
self-test, one new site (`0x00473CE4`, the display task's refresh call, a pass-through for every stock refresh); pin
`c5e4f8b7…` on both lenses since 2026-09-15 04:38. Damage: `DamageMsg`, the simulator's mirror, the keeper's arm /
hold-back protocol (`HOLD_BACK_MS` = 120 s, a placeholder), `present` records, `glassdrive.py selftest:`, the bounded
atlas skip (§54). Read at instruction level: only RIGHT can send (`CLAIMS.md`); CACHE_KEEP stays unarmed until LEFT
can be verified (Phase 4).

**Firmware features (flags, default off):**

| id | feature | state |
|---|---|---|
| F1.1 | capability: settings field 110 `DamageCaps {1 "DMG", 2 contract, 3 features}`, separate from field 100 | flashed |
| F1.2 | telemetry op: heap free (13/20/27), uptime (a 1.024-per-ms tick, `HANDOFF.md` §56), flags, the status register, the last frame's worker / copy / transfer µs, the panel record; the boot count withdrawn (stock keeps it only in the KV store) | flashed |
| F1.3 | the presented notify after each panel transfer (flag bit 0, field 113), RIGHT only; the stamp wraps the refresh call at `0x00473CE4` | flashed; measured 2.0 ms median / 12.2 max |
| F1.4 | flag op (field 112); flags clear at every release point | flashed |
| F1.5 | cache-keep across a lapse (flag bit 1, a once-per-lease latch; generation / size / CRC through op 4). Adam's ruling: the phone's atlas skip is the bounded one, no flag (§54); CACHE_KEEP unarmed until LEFT can be verified | flashed, unarmed |
| F1.6 | fast-link hold — dropped for Phase 1 (§50.9); superseded by upstream's link edits, built in Phase 2 (§58) | → Phase 2 |
| F1.7 | the JBD4010 per-row partial refresh timed against the full transfer | → Phase 2 (mode 24) |
| F1.8 | multi-packet ATT writes — dropped: the phone is not one-write-per-event (§54.4); the missing 2M PHY was a host-disabled bit (§58) | dropped |

**Test stop T1:** host vectors and simulator green · the flash ritual (§7) · self-test on glass ·
features armed one at a time · a soak day · journal and `/log` read · fix flash if needed.
**Exit:** every telemetry field populated (the boot count withdrawn); the cache survives a rebuild without re-upload
(seen on glass, §54.1); no hold-back events; the soak = ordinary wear during Phase 2's build (Adam, §56).

### Phase 2 — Drawing contract v2, phone-driven (size L; 1 flash) — **BUILT on both sides 2026-09-15 (pin `55746389…` after the review, `HANDOFF.md` §60–§61) bar Reader's page staging; not flashed**

**Firmware (`FIRMWARE.md` §4, drafted 2026-09-15):** per-lens cached image and string draws (modes 17/18), the v2
image record (u16 dims), the 224-entry table, cache write v2 (19), clip (20), fill (21), LUT over rect (22),
save-under (23), the partial-refresh present hint (24, F1.7), op 5 CACHE_SIZE, status 3–5 (the no-lease FLAGS_SET
refusal, Adam's §53.1 ruling), refusal fields 23–25, DRAW2 flag bit 2, contract 2; **and the link (Adam, 2026-09-15,
`HANDOFF.md` §58): LE 2M enabled in the startup feature command, both fast profile records 7.5 ms min/max latency 0,
the slow request bound to the fast record** — three in-place constants ported from upstream `c63710c`.

**Damage:** encoders, simulator and lint for every new op; the compositor drops the base-delta +
flat-draw + per-lens-copy shape (the `proof` and `edge` misses go away); kerning on; Reader pre-renders the
next and previous page as cached images and turns pages by clip + draw; back-to-Main and height switches ship
as fill + draws; dim-in-place ready for popovers; the phone requests 2M PHY at connect on a build whose DamageCaps
says so and journals the grant; ms/KB, a capture (`research/perevent.py`), the battery and the earbud's A2DP margin
measure the link.

**Test stop T2:** vectors · self-test · `--selfcheck` ×3 · snapshots · oracle walk · a soak day with the link's
ms/KB, a capture and the battery readout.
**Exit:** `cacheMiss` reasons `proof` and `planes` at zero; first-flush bytes down on every gesture
class; the page turn priced.

### Phase 3 — The motion engine, phone-started (size L; 1 flash)

**Firmware:** the tick; programs (upload / play / stop / retarget / completion); the verbs from the
refined list; the start synchronized across lenses via the deferred path; a running-program region
guard.

**Damage:** the `Motion` DSL (integer easing tables, tracks, end state); `desktop --motion-check`
(renders a program tick by tick to a strip for eyeballing before it goes to glass); the scheduler
(input preempts and retargets; the phone waits for completion before flushing a guarded region); a
Global `Motion` row (off · subtle · full, and speed) replacing `Slide frames` and `Slide fill`; first
programs: list slide with ease-out, bounce at the edges, Main's recede/pan/advance (phone-started for
now), wheel spin, popover reveal/cover, Hold'em deal slide and flip.

**Test stop T3:** vectors · self-test · motion-check strips · Adam's on-glass feel session (lens
match, comfort of the depth pop, speed) · a soak day with the battery readout. **Exit:** no visible
lens mismatch; motion starts within D7's phone-started target; battery within the margin.

### Phase 4 — Local input (size XL; 2 flashes)

**Firmware:** the binding table; scroll and tap handling at the input path R0.1 found, on both
lenses through the stock mirror; the applied-state event to the phone; sequence numbers.

**Damage:** staging (rows above and below the viewport as cached images, both forms of Main's lens
neighbours, the wheel's items, Reader pages, N rows of tmux history); the belief model consumes
applied-state events; reconciliation, and the oracle walk with random local events in the
simulator; the "phone stays N ahead" refill rule and the behaviour at a staged edge before the
refill lands.

**Test stop T4 — the big one:** vectors · self-test · simulator soak (random local events,
thousands of steps, belief = glass at every settle) · Adam's feel sessions · a work week of daily
use · the local-latency metric from telemetry. **Exit:** D7's local target met on Main and lists;
zero divergence in a week; hold-back never fired.

### Phase 5 — Firmware-owned chrome and the silent clock (size M; 1 flash)

**Firmware:** the clock/battery/link cells drawn after the frame copy at Global depth from cached
fonts; RTC and fuel-gauge reads; the silent clock as a firmware minute program (no radio while silent).

**Damage:** the phone declares those rects firmware-owned and stops painting them; the model treats
them as outside the shadow; the idle tick and silent-mode traffic shrink to nothing.

**Test stop T5:** vectors · self-test · a silent-mode night (a quiet journal) · a soak day. This is
the last shell-facing flash before the rebuild, so Phase 6 runs on stable firmware.

### Phase 6 — The Damage rebuild: the shell and every window, fully animated (size XL; fix flashes only)

Built against the refined list from Phase 0, one surface or window at a time, each whole to its best
state, each with its latency profile and Adam's verdict.

- **6a the shell (stop T6a):** Main (local notch, recede/pan/advance, bounce), the switcher wheel
  (local spin, smooth fade), the popover family per `POPOVER.md` (reveal/cover, depth step,
  dim-in-place, save-under), notifications, the keyboard, silent mode, height switch, back-to-Main,
  the wake, the input echo, Settings (live-preview transitions). `DESIGN.md` §6/§8, `WINDOWS.md` §6
  and `CLAUDE.md`'s latency section rewritten here as the new bar (local latency, staged bytes,
  motion budget).
- **6b lists and documents (stop T6b):** Reader (local page turns, chapter picker), Files, Torrents
  (and the keyboard flows).
- **6c live windows and canvas (stop T6c):** Music and Music Mode, Tmux (local history scroll, live
  pane updates), Feed (posts, comic strips as staged images, the bar).
- **6d Games (stop T6d):** Hold'em — deal slide, turn/river flip, chips to the pot, showdown, the
  hole cards' depth; the kit gains reusable card programs.

Each stop: the full battery ×3 · `--motion-check` · `glassdrive` walks · a soak day · Adam's verdict.

### Phase 7 — The offline home (size L; 2 flashes)

**Research first** (R0.5 deepened): display ownership with no EvenHub page, input with no page, screen
auto-off and wake, the persisted flag in the stock KV store, what to keep on the glasses.

**Firmware:** double-tap with no phone opens Damage's home (time, date, battery, last notifications,
staged pages) drawn from cached fonts and saved content; every path falls back to stock on failure;
the both-temple long-press untouched; a stock-dashboard override gesture kept.

**Damage:** what is saved while connected and when; the home's own small design pass with Adam.

**Test stop T7:** vectors · self-test · phone-off sessions (case at home, phone in a drawer) · the
stock override · a soak day · a phone-gone workday.

### Phase 8 — Consistency, docs, readiness (size M; 0–1 flash)

`CLAIMS.md` regraded; `FIRMWARE.md` final; this file's log closed; the flashing guide; the verify
script pinned to the final image; memory rewritten; the battery of gates green ×3; one final full
live walk; the release build script proven on a clean checkout. Then new windows resume.

## 6. Why the stops sit where they sit

Firmware stops are flash boundaries: a flash is expensive (Adam's HUD down, his presence, ~171 s per
lens) and the natural batch. Between flashes the host vectors and the simulator run on every change
for free, so the expensive stops carry only what those cannot see: timing, feel, lens match, battery,
and days of real use. Phase 6 gets four stops because its risk is design drift and window-by-window
regressions, and a verdict session per group is the right grain. Fix flashes are always allowed
inside a phase.

## 7. The flash ritual (every time)

1. `verify` reproducible from stock; hashes pinned. 2. Size ceiling, preamble length, TOC and CRC
fixups. 3. Thumb-bit audit. 4. Host vectors green. 5. Simulator vectors green. 6. **Adam's
in-the-moment go.** 7. Dry-run staircase (`--stop-before heartbeat` → `file_check` → `flash`).
8. Both lenses, one at a time, the same image (the cross-lens deferred path runs the same code on
both). 9. Reconnect, capability check (`DamageCaps … features 0x1f` in the journal), self-test on glass
(`tools/glassdrive.py … selftest:firmware/vectors/v1-keyframe.json` and the other drawing vectors — RIGHT
reports), telemetry read (`probe:telemetry=read`: uptime, the panel record, the heap figures). 10. Arm
features one at a time (`probe:flags=0x8000` PROBE first, then `0x0001` PRESENTED, then `0x0003`; the
keeper re-arms the wanted set after every rebuild under the hold-back rule). 11. Soak. 12. Journal and
`/log` read (`journal_report.py`: the transfer section). Rollback = reflash the last-known-good image, kept built
in `fws/`. The site list of every candidate (`~/damage-cfw/tools/verify.py` step 6) is reviewed against §3.1 (no new boot-path site) before step 6.

## 8. Assumptions this plan rests on, and where each is checked

| assumption | grade now | checked in |
|---|---|---|
| free heap is enough for a bigger cache, scratch and staged content | unknown | M0.1, R0.6 |
| the panel can present at 30–60 Hz | **measured 2026-09-15: transfer 2.0 ms median / 12.2 max, worker 3.2 / 17.7 max (`HANDOFF.md` §56) — the link is the ceiling** | F1.3 (done) |
| the two lenses can start a program together with no visible mismatch | inferred (stock does it over the UART sync) | T3 |
| local input can be handled on both lenses via stock's mirror | inferred | R0.1, Phase 4 |
| the ack precedes render on 2.2.6.10 | verified (decompile) | — |
| the refresh queue carries a rect | verified (decompile); honoured by the async refresh: **no** (verified, instruction level, 2026-09-13); a JBD4010 partial path exists, untimed | F1.3 (done); F1.7 in Phase 2 (Adam, 2026-09-15) |
| the glasses' idle link request can be gated on the lease | inferred (policy object mapped) | R0.4, F1.6 |
| one packet per ATT write is the phone-path limit | inferred | M0.3 |
| depth changes per notch are comfortable all day | unknown | T3, T4 |
| battery cost of the fast link and local motion is acceptable | unknown | M0.6, every soak |
| stock's near/far setting does not stack on Damage's depth | verified (the copy the CFW replaces applies it) | — |
| an appended code block cannot affect boot | not by construction: a5d1c31 already changes the heap arena size at boot and routes the display copy through a pass-through from boot on (both in daily use since 2026-08-30); new sites stay off boot paths | every flash's site review (`tools/verify.py`) |

## 9. Out of scope, on purpose

New windows and games; the radio controller's firmware (2M acceptance, event length); the touch
controller's firmware; the voice chip; the bootloader (no second image slot); writing the external
flash (fonts live there; a later idea at most); Faceclaw compatibility; a rebase to 2.2.9.
- **Temple touch bursts restarting an arm** (`HANDOFF.md` §54.8, 2026-09-15): a dozen taps on either temple in a
  few seconds stops that arm (a beep, the stock launcher, the phone's timeout, a rebuild). Adam's ruling: noted, not
  the work — the ring never does it; studied at the public-release polish for users without the ring. Phase 4's
  design note (local input off the input send's queue) stands.

## 10. Repos, files, where things live

| what | where |
|---|---|
| this plan and its log | `damagewm/FORK.md` |
| the firmware contract (v1 facts by pointer, v2 by design) | `damagewm/FIRMWARE.md` |
| conformance vectors | `damagewm/firmware/vectors/` (data) — inputs written by `damagewm/firmware/make_vectors.py`; expectations by the fork's `host/run_vectors.py --write`; checked by `ConformanceVectorTest` and `host/run_vectors.py` |
| the motion explosion | `damagewm/MOTION.md` (draft, for Adam's refinery) |
| the Phase 0 reads | `damagewm/CLAIMS.md` ("Firmware internals read for the fork") and `damagewm/research/fork-reads-2026-09-13.md`; the tool `damagewm/research/fwread.py` |
| the fork's checks | `~/damage-cfw/tools/verify.py` (the candidate image), `~/damage-cfw/host/` (the C on the PC: vectors, `test_damage_ext.py`) |
| the firmware fork | `~/damage-cfw` (GPL-3.0; branch `damage`; `github` = `https://github.com/expectbugs/damage-cfw`, public; `origin` = upstream g2flash, fetch only; `reference` = the pinned clone) |
| the pinned upstream clone (never moved) | `damagewm/reference/g2flash` at `a5d1c31` (`research/verify_cfw.py` pins it) |
| the decompile corpus and subsystem docs | `damagewm/reference/evenRealities-openCFW/g2/` |
| records | `HANDOFF.md` §48 onward (§49 = 2026-09-13); `CLAIMS.md` rows added per phase; memory `damage-cfw-fork.md` |

## 11. Progress log

- **2026-09-15 (night, 2)** — A second review of Phase 2 on both sides (`HANDOFF.md` §62), seven fresh
  reviewers and my own read; nothing flashed. The largest finding is the partial refresh (mode 24): it only
  ever ADDS its rows, so it is correct only while the panel already shows the whole previous frame — a batch
  refused part-way, stock content in the framebuffer and a lease release point all broke that, and each left
  rows of an old frame on the lens. One rule now in the C, the simulator and `FIRMWARE.md` §4, and **the
  vectors compare what the lens shows** (`"panel": true`; the host harness models the panel). Also fixed: the
  live save-under slots guarded across tasks, a dropped image recorded (reason 14), mode 19's bounds re-read
  at the write, the overlay switch read once; on the Damage side the phone's own firmware model is told the
  build's contract (without it a Phase 2 session would have raised a mirror fault per flush and DIVERGE
  notices), an atlas upload is read back before anything draws from it, a repack's old chunks can no longer
  move the new layout's acked watermark, a control write that misses an arm is a failure, and
  `glassdrive.py selftest:` no longer prints false FAILs on a correct build. Fork pin **`f9ddf49f…`** (31
  entries, a 53,724-byte block, the same sites); 24 vectors, 206 steps, the simulator equal to the C on every one; APK 0.53. Nothing flashed, nothing staged; both trees committed and pushed on his word (Damage
  `2329ec8`, the fork `26404f7`).

- **2026-09-12** — Plan written after a discussion session (`HANDOFF.md` §48). Decisions D1–D8
  proposed. `FIRMWARE.md` skeleton written. `~/damage-cfw` created from `a5d1c31` on branch `damage`
  with the flasher fix carried over — first commit `b3bdd5c` (the Damage repo's record: `d16e5c1`).
  Nothing flashed. Phase 0 not started. Next: M0.1 and the R0.x reads.
- **2026-09-13** — Phase 0 started (`HANDOFF.md` §49). Read: R0.2, R0.3 (mostly), R0.4, R0.6, and the
  sid-0x0F logger handler — facts in `CLAIMS.md`. Two corrections to this plan: the async refresh
  sends the whole panel whatever the rect (F1.7 rewritten), and M0.1's overlay does not time the
  panel transfer (M0.1 and F1.3 amended). Built APK 0.45 (staged, not installed): the probes M0.1
  (`diag`), M0.4 (`phy`), M0.5 (`logger`) behind the replica port's `probe` message, and `battery`
  journal notes for M0.6; `journal_report.py --since`, the battery and glass-log sections.
  `MOTION.md` drafted (the explosion's candidate list). Our clang builds a different image from the
  same sources (`CLAIMS.md`). With Adam's go, Phase 1 groundwork too: the fork pinned to our clang with
  `tools/verify.py` (`6db86e2`), the x86 host harness and the v1 conformance vectors — **the simulator
  matches the fork's C on all 35 steps** (`cd802ec`) — and F1.1/F1.2/F1.4 in source (`a8f3610`, not
  flashed); Damage's side in `a04cb17`. Measured: 14 arm link ends today, none overnight. §3.1's boot-path
  rule corrected. A Feed test misses in some full core runs (`HANDOFF.md` §49.6). Both repos pushed at the
  end of the session. Nothing flashed. Next: Adam's side (`HANDOFF.md` §49.7) — install 0.45, the probe
  session (M0.1, M0.4, M0.5), the two battery days (M0.6), the capture and the video (M0.3, M0.2), where the
  glasses were in the quiet windows, the refinery on `MOTION.md`, D1–D8.
- **2026-09-14** — Review of the 2026-09-13 work (`HANDOFF.md` §50), nothing built beyond fixes: the
  load-bearing addresses re-read at instruction level (all held); the telemetry record's field 4 made
  the status register `FIRMWARE.md` §1.2 requires (the C, the simulator, both test sets; fork pin
  `f9211ea2…`, 26 entries, no new site); the boot-count read withdrawn — stock keeps `kvbooCount` only
  in the KV store (F1.2 amended); the transport reads DamageCaps from the capability answer only; arena
  13 is 839,680 B. Then Adam's day: the glasses were in the charging case, untouched, through the 13th's
  fourteen drops and its quiet nights (§50.6, corrected); the snoop recipe verified from the stack's source
  and Adam cleared of ever choosing Filtered (§50.7); M0.4 answered from a bug report's stack dump (no 2M
  PHY); four full two-arm captures with the APK driving, local in `captures/apk-20260914-*` (§50.8); the
  earbud measured — not the slow side, its audio fine to Adam's ear; today's journal: no drop in 4.5 h worn
  at work, battery ~7.5 %/h. **Adam's ruling (§50.9): the drops are not the work; Phase 1 proceeds in a
  fresh session** — M0.1 first, then F1.3, F1.5, F1.7, the self-test op and the keeper's arm/hold-back on
  uptime; F1.6 dropped unless slow-set episodes return. Nothing flashed.
- **2026-09-14 (evening)** — Phase 1's candidate built on both sides while Adam was at work (`HANDOFF.md` §51):
  F1.3, F1.5 and the self-test in the fork (pin `5ff9159b…`, one new site, all host checks green), the keeper's
  arm / hold-back protocol, the `present` journal record, the report's transfer section, the on-glass self-test
  runner and APK 0.46 in Damage. Read at instruction level: the stock senders refuse on the left lens — every
  reply and notify is RIGHT's; Adam chose the bounded atlas skip (no flag) and left CACHE_KEEP unarmed until LEFT
  can be verified (§51.8). F1.7 waits on the panel type. Nothing flashed; both trees committed and pushed on his
  word from work (Damage `ca8fe4f`, the fork `2aded36`). Next (`HANDOFF.md` §51.9, `REMINDER.md`): the queue
  before he is home — the bounded skip, R0.1, R0.5, the M0.3 read, the core suite ×20 — then M0.1 (one minute), the
  site review (`tools/verify.py` step 6: `0x00473CE4` in `FUN_00473C44`), his go, the ritual.
- **2026-09-14 (late evening)** — The evening's work reviewed (`HANDOFF.md` §52), Adam's instruction from work: four
  defects verified before a line changed, fixed, each pinned by a test watched to fail on the old code. The fork
  (pin `70e47938…`, 27 entries, the same one new site): a self-test step carrying a mode-3/6 message shorter than
  its header fell through to the BMP loader — it cleared the live direct frame and handed the stack-built state to
  the stock loader (now refused and counted); a direct copy whose refresh the panel-off skipped left the F1.3 mark
  set, so a later stock refresh was stamped and reported as a Damage transfer (the stock-copy path clears it);
  the self-test's cross-task fields made volatile. Damage: the keeper's reset detection compared two uptime
  readings and missed a reset once the uptime had grown past the previous reading — the post-flash case — and now
  compares against the elapsed phone time; a bit the build refuses as unsupported no longer blocks the bits above
  it and is dropped from the wish. Host harness: `panel`, `refresh`, a BMP-loader count. The full battery green
  (core 548, the fork's checks 41/41); APK 0.47 staged, 0.46 never installed. Both trees left modified and
  uncommitted for Adam. Nothing flashed. The §51.9 queue stands, the bounded atlas skip first.
- **2026-09-14 (night)** — A second review of the same work (`HANDOFF.md` §53). One contract deviation on both
  sides: the flags survived an FB_RELEASE that followed a lapse the glasses had already settled (the settled
  marker now keeps only the latch; fixed in the C and in the model, pinned on both — three host checks, two
  `DamageMsgTest` pins); the model also cleared the flags on a lease check with no lease, which the C never did.
  Guards: a begin of the self-test runs under its active mark (a release from another task during it is honoured
  after it), and a stale FLAGS_SET waiter can no longer drop a newer session's. Fork pin **`c5e4f8b7…`** (27
  entries, the same one new site, 46,480-byte block); the battery green (core 550, the fork's checks 44/44); APK
  0.48 staged, none of 0.46–0.48 installed. Open for Adam: whether a FLAGS_SET with no lease held should be
  refused. Both trees committed and pushed on Adam's word (Damage `ba70608`, the fork `2935a66`); nothing flashed.
  The §51.9 queue stands.
- **2026-09-15** — The §51.9 queue run while Adam was at work (`HANDOFF.md` §54), no firmware change. **The
  bounded atlas skip built** (Adam's ruling): the transport's per-arm lease log and carry decision at the
  rebuild's acquire (90 s less a 10 s margin; writes of the last 10 s before a link end struck; a release sticky;
  a writer tag against a takeover's cache), the shell's kept path (the acked fonts live from the first compose,
  the in-flight chunk re-sent), an `atlas` note either way; `AtlasCarryTest` (5) watched to fail with the keep
  disabled; APK 0.49 staged. **R0.1 read:** input reaches a display thread only through the sync framework's
  listeners on both lenses; the input manager runs on RIGHT only (both-temple detection, a 1 s cross-device
  lockout) and sends every event to the peers; both lenses can send over the inter-lens link; the per-notch
  scroll is the EvenHub page's kind-2 container message — the table of event ids and UI codes in the notes; the
  ids behind slides/swipes and the TinyFrame role byte stay U. **R0.5 read:** the KV get/set take no lock of their
  own (§50's "lock" was the tick reader), the fuel-gauge record's address is used by seven pools, the RTC setter
  named, app 3 = the dashboard (I), the registry per openCFW (C). **M0.3's per-event read** (`research/perevent.py`):
  two full packets per served connection event on LEFT, served every 60 ms in two of three sessions (8.2 KB/s), the
  host not the limit — F1.8 dropped in its ATT-write form; the cadence's cause U. The core suite ran 30 times
  (§54.5, §54.7: the Feed miss 5, a seam-test race 2, three untraced exceptions, 18 clean); a self-review pass
  made three small changes (a locale-safe gap, the lease log kept by count, a bound on re-sent chunks) and the
  battery ran again green. Damage committed and pushed on Adam's word (`940272e`); the fork untouched; nothing
  flashed. Next: his part (§51.9 B, APK 0.49), the
  §53.1 ruling; for a session without him: the touch processor and ring service, the RTC getter, a capture
  with the ring asleep.
- **2026-09-15 (noon)** — The soak read (`HANDOFF.md` §56): the Phase 1 build's first 7.3 h clean; 387 presents,
  transfer 2.0 ms median / 12.2 max (M) — the link is the tick ceiling; in the case the panel is off (copies counted,
  nothing transferred); the uptime tick runs 1.024 per ms. Adam's rulings: F1.7 folded into Phase 2; a FLAGS_SET with
  no lease refused (status 3) from Phase 2's candidate; the soak is ordinary wear during Phase 2's build. Docs only.
- **2026-09-15 (afternoon)** — Phase 2's design pass: `FIRMWARE.md` §4 drafted (modes 17–24, the v2 image record, the
  224 table, op 5, status 3–5, refusal fields 23–25, budget options, the v2 vectors) from two surveys and the journal
  baseline (`HANDOFF.md` §57); eleven decisions put to Adam. No code; nothing flashed.
- **2026-09-15 (afternoon, 2)** — Upstream's link reconfiguration read against our base (`HANDOFF.md` §58): three in-place
  edits (LE 2M feature bit, a 7.5 ms fast profile, the slow request forced to fast), ~41 KiB/s measured upstream; our
  three sites found at instruction level; M0.4 corrected. **Adam's ruling: inside Phase 2's candidate.** Phase 1's
  section and REMINDER trimmed to the plan and pointers (the records hold the history).
- **2026-09-15 (evening)** — Adam's word on §57's eleven decisions: as recommended. Phase 2 built on the firmware side
  (`HANDOFF.md` §60): modes 17–24, op 5, status 3–5, fields 23–26, the partial path, the three link edits — pin
  `aacdc63a…`, no new site, every host gate green; 11 v2 vectors; the simulator matches the C on all 18 vectors, both
  forms. Damage (the same evening): the v2 atlas, the compositor's per-lens draws, fills, the reseed, the hint,
  kerning, the transport's start (cache size, DRAW2 before the paint, the 2M request), the report and the on-glass
  runner — `Contract2Test` pins it, gated on DamageCaps bit 5 + DRAW2; Reader's page staging is the one Phase 2
  item left. A live defect found from Adam's report at work and fixed for APK 0.50 (`HANDOFF.md` §59: a lens
  reboot inside the atlas skip's window; a keeper race on the duplicate replies). Nothing flashed.
- **2026-09-15 (night)** — The review of Phase 2 on both sides (`HANDOFF.md` §61): 10 fork defects and 12 Damage ones
  fixed and pinned (among them a heap overrun behind a sub-64 KiB `CACHE_SIZE`, hints after a skipped refresh, the partial
  call's width, the v2 atlas upload's alignment, kerning on the v1 path, the hold-back and a lost lease vs DRAW2, the
  carry decision's evidence, the self-test's live cache writes); `FIRMWARE.md` §4 amended; 2 vectors added. Fork pin
  `55746389…`, no new site. Nothing flashed. Committed and pushed on Adam's word: Damage `1850fb6`, the fork `759f001`.
- **2026-09-15 (night, the third review — `HANDOFF.md` §63):** seven reviewers over the same surface again. The
  fork: mode 12's write pass re-checks the bounds it re-reads (the v1 twin of §62.2 item 4; three reviewers found
  it independently), mode 19's write loop bounds the source too, the deferred save-under free runs UNDER its busy
  mark and every slot claims its pointer with an atomic exchange, modes 14/15/18's second pass re-derive the table
  pointer and record their refusal, field 12 cannot wrap, the overlay switch is read once in truth.
  **ONE NEW SITE — the display task's other refresh call, `0x00473D80` (its type-6 branch), hooked like
  `0x00473CE4`:** both of that function's copy calls were already hooked and the copy hook does not know the event
  type, so a type-6 event carrying a Damage frame transferred it with no stamp and dropped its hint. §3.1's rule
  holds — `FUN_00473C44` is the display task's event loop, not a boot-time path, and is already patched at three
  points. Pin **`b0e42923…`**, 32 entries, a 54,068-byte block, 20 Thumb branches, 370 KB below the OTA flag; every
  host gate green. Nothing flashed. Committed and pushed on Adam's word: Damage `a4db479`, the fork `e331c3f`.
- **2026-09-16 — a fourth review of Phase 2 (`HANDOFF.md` §64), with two tools the first three rounds did not
  have.** A **differential fuzz** of the fork's C against the Kotlin simulator (46 random vectors × ~25 steps ×
  2 lenses, plus 60 corrupted): the two agree everywhere on well-formed traffic and disagreed on exactly one
  class — a mode-3/6 stream refusal, which v1 does NOT roll back (it decodes straight into the shadow) while the
  model kept the previous frame. A **mutation sweep** of the fork's 106 refusal sites against all three host
  gates: 39 caught, 55 reachable ones caught by nothing — largest, **the lease (3) and DRAW2 (9) checks of six of
  the eight v2 modes**, now `v2-gates`. Fixed in the fork: modes 12 and 19's write loops indexed
  `ctx->texture_cache` without testing it (the emitted Thumb reloads the pointer per byte, so a release point on
  another task sends the stores to low memory); the deferred-free handshake dropped a release that landed between
  the epilogue's last test of the pending flag and its clearing of the active mark (the two are ONE state word
  now, moved with a compare-exchange); a batch's and a step's sub-mode were validated and then re-read by the
  dispatcher (both lists applied again to the byte that dispatches); the failed-copy branch did not clear the
  F1.3 mark; mode 14's second pass could index 254 bytes past a 64 KiB cache; the DWT µs read 2.4 % HIGH, not
  low, and the calibration now scales for the 1.024-per-ms tick; telemetry fields 15/26 and 20/21/22 go up under
  their own write count. **No new site.** The image is 5,008 B smaller than §63's: the context's one-time
  creation is out of line, so a struct field no longer costs 5.7 KB of inlined zeroing. Pin **`48172b62…`**, 32
  entries, a 54,776-byte block, 21 Thumb branches, 369 KB below the OTA flag; `tools/verify.py`, 26 vectors /
  237 steps, 20 self-test, 76 host checks green. Nothing flashed.
