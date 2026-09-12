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
   a short list of redirected call sites, no change to any code that runs before the first radio
   message. Every new behaviour sits behind a per-feature flag the phone arms per session; flags
   clear when the lease lapses (Phase 7's persisted flag is the designed exception). A phone that is
   gone leaves stock behaviour behind within 90 s.
2. **Hold-back after a reset.** The firmware exposes a boot counter and uptime. The keeper never
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
  worker/present microseconds (DWT), a per-frame "presented" notify (sequence, timings) on request.
- *Drawing:* per-lens (depth-aware) cached image and glyph draws; images with 16-bit dimensions and
  a clip rect; font tables up to 224 entries with kerning adjust bytes; unquantized fill; LUT over a
  rect (dim or brighten in place); invert; save-under scratch for popovers; a larger texture cache
  with a generation id and CRC that survives lease lapses.
- *Motion engine:* a fixed tick (rate set from the measured present time, 30–60 Hz); programs
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
| M0.1 | diagnostic overlay on for one probe session (a dev tool sends mode 7 sub 2, never the shell), read on glass by eye or photo, then sub 1 | free KiB of arenas 13/20/27; worker and present microseconds → heap budget, first tick-rate estimate |
| M0.2 | 240 fps phone video through a lens: a stock dashboard scroll (cadence, the bounce) and a Damage notch (ring press → first visible change) | the real user-perceived numbers; if filming fails, Phase 1 telemetry is the fallback |
| M0.3 | BTSnoop with the APK driving (bug-report mail path) plus one Wi-Fi-off session | packets per connection event, PHY, the arm split, one arm drop from the radio's side → the link levers |
| M0.4 | 2M PHY request from the APK (`setPreferredPhy`, Nordic 2.7.5) | measured, not assumed |
| M0.5 | the sid-0x0F logger probe across an arm rebuild | reset vs stall → the hold-back rule's N |
| M0.6 | the hourly battery READ journaled over a day on `Link = high` and a day on `balanced` (the §47 experiment) | the battery baseline every later soak compares against |

**Research (decompile corpus + openCFW docs; findings into `CLAIMS.md`):**

| id | subject | where to look |
|---|---|---|
| R0.1 | the input path: the gesture mapper `FUN_00442d86` (subtypes 0/2/4/6/8/10/0xc/0xe/0x10), which subtypes are scroll, how input is mirrored to the other lens, how the EvenHub UI handler turns them into SysEvents | corpus; `g2flash/patches/gesture_fwd.c` for the two known sites |
| R0.2 | the inter-lens link: `uart_sync.c` + the sync framework (master/slave TinyFrame over a UART); how a display step runs on both lenses together | `g2-uart-sync-recovery.md`, `g2-sync-framework-recovery.md` |
| R0.3 | the refresh path: the display task `FUN_00473c44` passes the queued rect to the ULED manager's async refresh; whether either panel driver honours the rect (JBD4010 and Hongshi A6N-G both have partial-refresh entries); which panel Adam's pair has (chip-id read) | `g2-uled-manager-recovery.md`, `g2-uled-jbd4010-recovery.md`, `g2-uled-a6ng-recovery.md` |
| R0.4 | the connection-parameter policy (`app_connect_params.c`, 14 functions mapped): the fast and slow profiles (`_connectParamReq_impl` and its profile table), where the idle request is issued, the cleanest lease-gated redirect | `g2-app-connect-params-recovery.md`, `tools/manifests/g2-app-connect-params-*.tsv` |
| R0.5 | stock helpers to call: RTC getter, fuel-gauge getters, crc32, kvdb read/write (Phase 7's persisted flag), the dashboard launch path and module registry (Phase 7) | `g2-drv-rtc-recovery.md`, the chg_bq27427 overlay, `ui_module_registry.c`, `ui_startup_app.c` |
| R0.6 | heap: which arena holds the container buffers; a budget for cache growth, scratch and staged content | M0.1 plus `patch_compress.py`'s arena notes |

**Design work:** `FIRMWARE.md` v2 filled in from a skeleton to a draft; the conformance-vector
format; **the motion explosion and refinery** — every animation idea per surface and window (shell:
Main notch with recede/pan/advance, wheel spin, popover reveal/cover and depth step, notification
slide, the keyboard, height switch, back-to-Main, the wake; windows: Reader page slide, list panning
with bounce, Files/Torrents rows, Music card and Music Mode, Tmux history scroll, Feed strips and
comics, Hold'em deal/flip/chips/showdown; Settings previews), then Adam's verdicts. The surviving
list fixes the verb set. Revisit `DESIGN.md` §0's cost-based exclusions (fades, dim-behind,
banners): Adam's call now that cost is gone. Lock D1–D8.

**Exit:** the numbers in a table; the verb set; the contract draft; the decisions. No flash.

### Phase 1 — The fork pipeline and the first flash (size M; 1 flash)

**Repo `~/damage-cfw`** (created 2026-09-12 from `reference/g2flash` at `a5d1c31`, branch `damage`,
with our one-line flasher fix carried over): clang cross-compile on beardos (checked: works),
`build_cfw.sh` regenerating the patch JSON, a verify script (stock hash → output hash,
reproducible), the Thumb-bit audit, the size ceiling (≈337 KB of headroom under g2flash's
conservative ceiling today), preamble/TOC/checksum fixups, **a host build of the patch sources**
(x86, stubbed firmware entry points) that runs the conformance vectors, and the on-glass self-test op.

**Firmware features (flags, default off):**

| id | feature |
|---|---|
| F1.1 | capability: a new settings field `DMG/<contract-version>` plus a feature bitmask, separate from field 100 (`SettingsMsg.REQUIRED_CAPS` unchanged) |
| F1.2 | telemetry op: heap free (13/20/27), uptime, boot count, flags, last status code, last N frames' worker/present microseconds |
| F1.3 | presented-notify: after the frame copy, (sequence, timings) to the phone when enabled; from RIGHT, and from LEFT if R0.2 shows its notify path works |
| F1.4 | flag op: arm/disarm per feature; all cleared on lease lapse |
| F1.5 | cache-keep across a lease lapse with a generation id and CRC the phone can query; cache size configurable up to the R0.6 budget |
| F1.6 | fast-link hold: the glasses' idle-parameter request is skipped while the lease is held (if R0.4 finds a clean site); a latency-0 profile only if M0.3 says the phone would use it |
| F1.7 | refresh-rect experiment: present with the batch's bounding rect instead of the full panel; F1.3 measures the difference |
| F1.8 | multi-packet ATT writes (walk concatenated AA packets in one write) — only if M0.3 says the phone is one-write-per-event; MTU 517 on the phone side |

**Damage:** capability parsing; the arm / hold-back protocol in the keeper; `glass` journal notes;
`journal_report.py` columns for present time and local latency; cache-keep (skip the atlas upload
when generation and CRC match); MTU and packing if F1.8 ships.

**Test stop T1:** host vectors and simulator green · the flash ritual (§7) · self-test on glass ·
features armed one at a time · a soak day · journal and `/log` read · fix flash if needed.
**Exit:** every telemetry field populated; the cache survives a rebuild without re-upload; the link
stays fast through a day if F1.6 shipped; no hold-back events.

### Phase 2 — Drawing contract v2, phone-driven (size L; 1 flash)

**Firmware:** per-lens cached draws (13/14 with two x's under the high bit); 16-bit image dimensions
and a clip rect; 224-entry fonts with kerning bytes; fill / LUT-over-rect / invert; save-under
scratch (capture, restore); cache growth to the budget; region refresh through the panel manager's
partial-refresh entry if F1.7 showed the async path ignores the rect.

**Damage:** encoders, simulator and lint for every new op; the compositor drops the base-delta +
flat-draw + per-lens-copy shape (the `proof` refusals go away); kerning on; Reader pre-renders the
next and previous page as cached images and turns pages by a phone-started progressive blit;
back-to-Main and height switches ship as cached blits; dim-in-place ready for popovers.

**Test stop T2:** vectors · self-test · `--selfcheck` ×3 · snapshots · oracle walk · a soak day.
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
both). 9. Reconnect, capability check, self-test on glass, telemetry read. 10. Arm features one at a
time. 11. Soak. 12. Journal and `/log` read. Rollback = reflash the last-known-good image, kept built
in `fws/`. The site list of every candidate is reviewed against §3.1 (no boot-path code) before step 6.

## 8. Assumptions this plan rests on, and where each is checked

| assumption | grade now | checked in |
|---|---|---|
| free heap is enough for a bigger cache, scratch and staged content | unknown | M0.1, R0.6 |
| the panel can present at 30–60 Hz | unknown (present time unmeasured) | M0.1, F1.3 |
| the two lenses can start a program together with no visible mismatch | inferred (stock does it over the UART sync) | T3 |
| local input can be handled on both lenses via stock's mirror | inferred | R0.1, Phase 4 |
| the ack precedes render on 2.2.6.10 | verified (decompile) | — |
| the refresh queue carries a rect | verified (decompile); honoured by the panel: unknown | F1.7 |
| the glasses' idle link request can be gated on the lease | inferred (policy object mapped) | R0.4, F1.6 |
| one packet per ATT write is the phone-path limit | inferred | M0.3 |
| depth changes per notch are comfortable all day | unknown | T3, T4 |
| battery cost of the fast link and local motion is acceptable | unknown | M0.6, every soak |
| stock's near/far setting does not stack on Damage's depth | verified (the copy the CFW replaces applies it) | — |
| an appended code block cannot affect boot | by construction (no boot-path sites) | every flash's site review |

## 9. Out of scope, on purpose

New windows and games; the radio controller's firmware (2M acceptance, event length); the touch
controller's firmware; the voice chip; the bootloader (no second image slot); writing the external
flash (fonts live there; a later idea at most); Faceclaw compatibility; a rebase to 2.2.9.

## 10. Repos, files, where things live

| what | where |
|---|---|
| this plan and its log | `damagewm/FORK.md` |
| the firmware contract (v1 facts by pointer, v2 by design) | `damagewm/FIRMWARE.md` |
| conformance vectors (Phase 1) | `damagewm/firmware/vectors/` (data; consumed by the fork's host tests and the Kotlin simulator) |
| the firmware fork | `~/damage-cfw` (GPL-3.0; branch `damage`; `origin` = upstream g2flash; `reference` = the pinned clone) |
| the pinned upstream clone (never moved) | `damagewm/reference/g2flash` at `a5d1c31` (`research/verify_cfw.py` pins it) |
| the decompile corpus and subsystem docs | `damagewm/reference/evenRealities-openCFW/g2/` |
| records | `HANDOFF.md` §48 onward; `CLAIMS.md` rows added per phase; memory `damage-cfw-fork.md` |

## 11. Progress log

- **2026-09-12** — Plan written after a discussion session (`HANDOFF.md` §48). Decisions D1–D8
  proposed. `FIRMWARE.md` skeleton written. `~/damage-cfw` created from `a5d1c31` on branch `damage`
  with the flasher fix carried over (working tree, not yet committed). Nothing flashed. Phase 0 not
  started. Next: M0.1 and the R0.x reads.
