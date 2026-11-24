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

**What is live in this file.** State today: `REMINDER.md`.
1. **The order of work is `REPLAN.md`** (its §4 as amended by §14 and §17), not §5; the suspension of 2026-09-12 (no new
   windows, no Feed polish, no popover build outside the plan) carries over under it (`CLAUDE.md`).
2. **Binding:** §2's decisions (with the amendments its status line names), §3's principles — §3.8 is the protocol
   every build follows —, §7's flash ritual, §8's assumptions, §9 and §10.
3. **Design still to build:** §4 and Phases 5–8 of §5, and the Damage halves of Phases 3–4 — the glasses kit (K)
   builds those and Phase 6, step G Phases 7–8. Phase 3's firmware is C1's, Phase 4's C2's; `FIRMWARE.md` §5–§7 is
   C1's, C2's and C3's contract.
4. **History:** Phases 0–2 are done and flashed (`HANDOFF.md` §55, §65); then builds C1, C2 and the effects build C3
   (§88, §94, §99). Their sections keep what still binds or is cited. Phase 2's test stop T2 is still open.
5. **§11 points at the log:** the records are `HANDOFF.md` §48 onward.

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

Proposed 2026-09-12 and treated as in force; Adam's confirmation, owed at Phase 0's close, moved with Phase 0's
decisions to step A (`REPLAN.md` §4) and is given only where this line says so. Since: D1 stands after the 2026-11-10 survey (`HANDOFF.md` §74); D3's popover
family is built in step K and D5's new windows wait for `REPLAN.md`'s order (Reminders first, its §14.4; then Mail ·
SMS · Info · Notices); D6 and D7's phone-started target are confirmed (`MOTION.md` §6 C1, C2, 2026-11-13); D7's battery
margin is answered — no limit, with power-saving settings (`REPLAN.md` §14.1 R12); D7's local target is not ruled yet; D4's offline home moves last and is decided again (step G; R8, ruled yes in `REPLAN.md` §14.1; §4).

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
   message. (The inherited a5d1c31 set already has two such paths — the primary heap arena's size, at
   boot, and the display task's copy call, which passes straight through for every stock refresh from boot
   on; `tools/verify.py` lists every site for review, and its step 6 FAILs any site the installed build's
   patch set does not have, `FIRMWARE.md` §5.10.) Every new behaviour sits behind
   a per-feature flag the phone arms per session; flags
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
8. **The failure envelope is scope, not review work** (2026-09-16, `HANDOFF.md` §65). A phase's design pass
   answers six questions for every new piece of state, wait and guard it adds, BEFORE a line is written:
   (a) what clears the state at a session boundary, a lease lapse, a reset and a rebuild; (b) what releases the
   wait if the write never succeeds — a re-ask on the pacing tick, one deferred that latches on success; (c) what
   identity the guard is keyed on, and whether it survives the event it guards against (a repack re-origins
   every offset; a new object starts its counters at 0); (d) which other task or lens reaches the same memory
   meanwhile (the settings task, the input thread, the image worker; LEFT cannot report); (e) whether each
   decision reads a shared field once; (f) which sibling sites share the class (the v1 twin of a v2 op, the
   reader and the writer, both lenses, every second pass, every start gate). The build ships the answers and a
   **Not built** list; a review then finds what the answers missed. Phase 2 was built without this section
   and four reviews built it afterwards (§61–§64); its filled-in envelope below is the worked example.

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

**The ORDER below no longer governs (Adam, 2026-11-12): `REPLAN.md` as he ruled it (its §4 and §14) is the order
of work.** Phases 0–2 are history as recorded. Phases 3–8 are kept as the design of each piece — their gates,
stops and failure envelopes are reused by the steps that replace them (Phase 3's flash is C1's, Phase 4's is
C2's, their Damage halves and Phase 6 are K's, Phase 5 is measured first, Phase 7 is G's and decided again,
Phase 8 closes each arc). Principle 4 (one candidate flash per phase) gives way to the batched C1 / C2 / C3 of
`REPLAN.md` §5; D3's popover family is built in K. The flash ritual (§7), the protocol (§3.8) and every "never
flash without Adam's in-the-moment go" stand unchanged.

Size is relative (S/M/L/XL). "Flashes" counts candidate flashes; fix flashes are extra and cheap.
Every phase's design pass fills §3.8's envelope before any code; its build ends with a **Not built** list and
the two instruments' numbers — the differential fuzz (`firmware/fuzz_vectors.py`: the C and the simulator agree
on N random vectors) and the mutation sweep (`~/damage-cfw/tools/mutate.py`: N of M refusal guards caught by a
gate) — which is the record a review starts from (`CLAUDE.md` "Scope, build, review").

### Phase 0 — Measure, research, decide — **done** (`HANDOFF.md` §49–§50, §54; its refinery and decisions moved to step A, `REPLAN.md` §4)

**Measurements** (the ids are cited from code and records):

| id | what | outcome |
|---|---|---|
| M0.1 | the diagnostic overlay for one probe session (`glassdrive.py probe:diag=show`, then `diag=hide`; never the shell): free KiB of arenas 13/20/27, worker and copy µs — not the panel transfer, which F1.3 times | read on glass 2026-09-15 (`HANDOFF.md` §54.8); the probe blanked the a5d1c31 build until a rebuild and is not run on the fork's build without Adam |
| M0.2 | 240 fps phone video through a lens: a stock dashboard scroll and a Damage notch (ring press → first visible change) — the user-perceived numbers | open (`REMINDER.md`); the telemetry is the fallback |
| M0.3 | BTSnoop with the APK driving (the bug-report path) plus a Wi-Fi-off session: packets per connection event, PHY, the arm split, an arm drop from the radio's side | four two-arm captures (§50.8); the per-event read (§54.4, `research/perevent.py`): two full packets per served event on LEFT, served every 60 ms in two of three sessions, the host not the limit — F1.8 dropped; a capture spanning a drop still owed |
| M0.4 | the 2M PHY request (`probe:phy=2m`; `probe:phy=1m` returns) | the missing 2M was the host's disabled feature bit (§58), enabled by Phase 2's link edits; both arms on LE 2M since (§65); the probe stays as a check |
| M0.5 | the sid-0x0F log stream across an arm rebuild (`probe:logger=on`: RAM-only on the glasses, re-sent after each session start; `glasslog` notes) — reset vs stall, the hold-back rule's N | dropped as a pre-test (§50.9): F1.2's uptime answers reset vs stall; the probe stays available |
| M0.6 | the glasses' battery changes journaled (`battery` notes; `journal_report.py --since`) over a day on `Link = high` and a day on `balanced` (the §47 experiment) | the baseline every later soak compares against; day one on `high` begun 2026-09-14 (§50.9), day two optional |
| M0.7 | the phone-state experiment for the arm drops (§50.6: the variable is the phone's day/night state) | optional since §50.9 |

**Research** (the decompile corpus and openCFW's docs; findings in `CLAIMS.md` and `research/fork-reads-2026-09-13.md`):

| id | subject | outcome | where to look |
|---|---|---|---|
| R0.1 | the input path: the gesture mapper `FUN_00442d86`, how input is mirrored to the other lens, how the EvenHub UI handler turns it into SysEvents | read 2026-09-15 (notes "R0.1"): the mapper is the display thread's `inputEventDataHandler`; input reaches a display thread ONLY through the sync framework's listeners, on both lenses; the input manager runs on RIGHT only (both-temple detection, a 1 s cross-device lockout) and sends every event to the peers; both lenses can send over the inter-lens link; the per-notch scroll is the EvenHub page's kind-2 container message. The slide and swipe ids since (event ids 4 and 5, 2026-11-13, `CLAIMS.md`); the TinyFrame role byte U | corpus; `g2flash/patches/gesture_fwd.c` for the two known sites |
| R0.2 | the inter-lens link (`uart_sync.c`, the sync framework) | read 2026-09-13: RIGHT forwards the completion through `FUN_00464BB2`, a non-blocking queued send; the UART rate U | `g2-uart-sync-recovery.md`, `g2-sync-framework-recovery.md` |
| R0.3 | the refresh path: the display task `FUN_00473c44` and the ULED manager's async refresh | read 2026-09-13: the async path sends the whole panel on both drivers; JBD4010's partial path writes per row (Phase 2's mode 24); Adam's panel is the JBD4010 (§55) | `g2-uled-manager-recovery.md`, `g2-uled-jbd4010-recovery.md`, `g2-uled-a6ng-recovery.md` |
| R0.4 | the connection-parameter policy (`app_connect_params.c`, 14 functions mapped) | read 2026-09-13: a 60 s timer carrying the slow event is armed at connect and after every update; every submit enters `FUN_00476CBC` (the F1.6 site); superseded by Phase 2's link edits (§58) | `g2-app-connect-params-recovery.md`, `tools/manifests/g2-app-connect-params-*.tsv` |
| R0.5 | stock helpers: RTC getter, fuel-gauge getters, crc32, the KV store (Phase 7's persisted flag), the dashboard launch path and module registry (Phase 7) | read 2026-09-15 (notes "R0.5"): the KV get/set are `FUN_0054116E`/`FUN_005411F2` over FlashDB with no lock of their own; the fuel-gauge record `0x20073B18` is referenced from seven pools (offsets per openCFW, C); the RTC setter is `DRV_RtcSetTime` at `0x0047EE78`, the stock getter still to name; app 3 = the dashboard (I), the registry per openCFW's `ui_module_registry.c` (C); no callable crc32 | `g2-drv-rtc-recovery.md`, the chg_bq27427 overlay, `ui_module_registry.c`, `ui_startup_app.c` |
| R0.6 | heap: which arena holds the container buffers; the budget for cache growth, scratch and staged content | arena 13 or 20 (I); the budget as measured is §8's first row | M0.1 plus `patch_compress.py`'s arena notes |

**Design work** (done): `FIRMWARE.md` v2 (§3 and §4 built; §5 and §6 in contract form 2026-11-13); the
conformance-vector format (`FIRMWARE.md` §9); the motion explosion and refinery (`MOTION.md`, ruled 2026-11-13),
which also re-put `DESIGN.md` §0's cost-based exclusions; D1–D8 proposed (§2; confirmed only where its status line
says). **Adam's ruling (`HANDOFF.md` §50.9):** the arm
drops are recorded (§42.2, §49.1, §50.6, §50.8), cause U, and not this plan's work — he never sees one while wearing
the glasses.

### Phase 1 — The fork pipeline and the first flash — **FLASHED 2026-09-15 04:38 (`HANDOFF.md` §55): both lenses on pin `c5e4f8b7…` (the rollback image then), the self-test green on glass, the panel JBD4010, the transfer 1.2–6.6 ms per present, PROBE + PRESENTED armed**

**Built (`HANDOFF.md` §49–§55):** the fork pinned to our clang with `tools/verify.py`; the x86 host harness (`host/`:
the vectors, the self-test form, `test_damage_ext.py`); `patches/damage_ext.c` = F1.1–F1.5 and the self-test, one new
site (`0x00473CE4`, the display task's refresh call, a pass-through for every stock refresh). Damage: `DamageMsg`, the
simulator's mirror, the keeper's arm / hold-back protocol (`HOLD_BACK_MS` = 120 s, a placeholder), `present` records,
`glassdrive.py selftest:`, the bounded atlas skip (§54). Read at instruction level: only RIGHT can send (`CLAIMS.md`);
CACHE_KEEP stays unarmed until LEFT can be verified.

**Firmware features (flags, default off):**

| id | feature | state |
|---|---|---|
| F1.1 | capability: settings field 110 `DamageCaps {1 "DMG", 2 contract, 3 features}`, separate from field 100 | flashed |
| F1.2 | telemetry op: heap free (13/20/27), uptime (a 1.024-per-ms tick, `HANDOFF.md` §56), flags, the status register, the last frame's worker / copy / transfer µs, the panel record; the boot count withdrawn (stock keeps it only in the KV store) | flashed |
| F1.3 | the presented notify after each panel transfer (flag bit 0, field 113), RIGHT only; the stamp wraps the refresh call at `0x00473CE4` | flashed; measured 2.0 ms median / 12.2 max |
| F1.4 | flag op (field 112); flags clear at every release point | flashed |
| F1.5 | cache-keep across a lapse (flag bit 1, a once-per-lease latch; generation / size / CRC through op 4). Adam's ruling: the phone's atlas skip is the bounded one, no flag (§54); CACHE_KEEP unarmed until LEFT can be verified | flashed, unarmed |
| F1.6 | fast-link hold — dropped for Phase 1 (§50.9); superseded by upstream's link edits, built in Phase 2 (§58) | → Phase 2 |
| F1.7 | the JBD4010 per-row partial refresh timed against the full transfer | → Phase 2 (mode 24; Adam, 2026-09-15) |
| F1.8 | multi-packet ATT writes — dropped: the phone is not one-write-per-event (§54.4); the missing 2M PHY was a host-disabled bit (§58) | dropped |

**Exit, met before Phase 2's flash:** every telemetry field populated (the boot count withdrawn); the cache kept
across a rebuild without re-upload (seen on glass, §54.1); no hold-back event; the soak was ordinary wear during
Phase 2's build (Adam, §56) — 7.3 h then 10.2 h worn clean (§56, §65).

### Phase 2 — Drawing contract v2, phone-driven — **FLASHED 2026-09-16 (pin `48172b62…`, `HANDOFF.md` §65; built §60, reviewed §61–§64); Reader's page staging built the same evening (§66); test stop T2 open**

**Firmware (`FIRMWARE.md` §4):** per-lens cached image and string draws (modes 17/18), the v2 image record (u16
dims), the 224-entry table, cache write v2 (19), clip (20), fill (21), LUT over rect (22), save-under (23), the
partial-refresh present hint (24, F1.7), op 5 CACHE_SIZE, status 3–5 (the no-lease FLAGS_SET refusal, Adam's §53.1
ruling), refusal fields 23–25, DRAW2 flag bit 2, contract 2; **and the link (Adam, 2026-09-15, `HANDOFF.md` §58): LE
2M enabled in the startup feature command, both fast profile records 7.5 ms min/max latency 0, the slow request bound
to the fast record** — three in-place constants ported from upstream `c63710c`.

**Damage (built, §60, §66):** encoders, simulator and lint for every new op; the compositor's per-lens draws in place
of the base-delta + flat-draw + per-lens-copy shape (meant to make the `proof` and `edge` misses go away; `proof`
read 17 in Phase 2's first 2.5 h and 90 in 0.63's first 80 minutes — T2's exit below, `HANDOFF.md` §66, §75.2); kerning on; Reader's
page staging (the next and previous strips as cached records, a notch as one draw); back-to-Main and height switches
as a fill plus draws; the 2M PHY request at connect on a build whose DamageCaps says so, the grant journaled. The LUT
and save-under encoders exist at the wire level; no surface uses them yet (the popover family, step K). The link is
measured by ms/KB, a capture (`research/perevent.py`), the battery and the earbud's A2DP margin.

**The envelope (§3.8), filled by the reviews rather than the design pass — the worked example:** (a) DRAW2 and
the cache size drop with the lease (§61.2 item 6); `glassesSilent`, `recentlyReleased` and the atlas proof mark
are per session or per epoch (§64.2 items 2, 7, 11). (b) The prelude, the capability query, FLAGS_SET, op 5 and
the warmup each re-ask on the pacing tick with one deferred that latches on success (§63.3 item 4, §64.2 items
4–5). (c) The atlas read-back is keyed on an epoch, not a per-atlas counter that every new atlas restarts at 0
(§63.1, §64.2 item 2). (d) The save-under slots move under one state word — the cache pointer under none: its
release is still load / test / store / free (`HANDOFF.md` §76.8); every second
pass re-derives its pointer and records its refusal (§62.2 item 2, §63.2 items 2–3, §64.3 items 1–3). (e) One
read of the link state per decision — `pump()`, the compositor's `v2`, `CachedText`'s atlas (§62.3 item 9, §63.3
item 8, §64.2 item 6). (f) Mode 12 with 19, modes 14/15 with 18, the writers with the readers, both lenses of a
pair (§61.1 item 3, §63.2 items 1 and 3, §64.3 item 7). **Reader's page staging** was built after the flash, its
envelope answered BEFORE the code (`HANDOFF.md` §66 — the first build under §3.8).

**Test stop T2:** vectors · self-test · `--selfcheck` ×3 · snapshots · oracle walk · a soak day with the link's
ms/KB, a capture and the battery readout.
**Exit:** `cacheMiss` reasons `proof` and `planes` at zero; first-flush bytes down on every gesture
class; the page turn priced.

### Phase 3 — The motion engine, phone-started (size L; 1 flash) — **its firmware is build C1: FLASHED 2026-11-18 (pin `0dab2154…`, `HANDOFF.md` §88); the Damage half is K's, under way**

**Firmware:** the tick; programs (upload / play / stop / retarget / completion); the verbs from the
refined list; the start synchronized across lenses via the deferred path; a running-program region
guard. **Carried in this candidate (`HANDOFF.md` §67):** op 5's cap 160 → 256 KiB (`off4` already reaches it) and
a largest-allocatable-block figure per arena in telemetry, with arena 13 re-budgeted for the cache and the
programs together; **a deflated form of the cache write** (`HANDOFF.md` §68: cache writes were 61 % of the image
lane's bytes and go uncompressed; a compression context kept across flushes waits on a re-measure, upstream's
private SID on Phase 4) — after the glyph-subset packing has been read for a day and the three-point heap read
(fields 7–9 before CREATE, after it, after the first frame) is in.

**Damage:** the `Motion` DSL (integer easing tables, tracks, end state); `desktop --motion-check`
(renders a program tick by tick to a strip for eyeballing before it goes to glass); the scheduler
(input preempts and retargets; the phone waits for completion before flushing a guarded region); a
Global `Motion` row (off · subtle · full, and speed) replacing `Slide frames` and `Slide fill` (as built, the
`Animations` category with a sub menu per tier, `MOTION.md` §7; `Slide frames` and `Slide fill` remain as rows); first
programs: list slide with ease-out, bounce at the edges, Main's recede/pan/advance (phone-started for
now), wheel spin, popover reveal/cover, Hold'em deal slide and flip.
*Ruled 2026-11-14 (`HANDOFF.md` §81): the firmware holds no phone write back — the phone CLOSEs an instance, in the
same batch, before a write that meets its area (waiting for RIGHT's last-step notify stays an option); the firmware
contract is `FIRMWARE.md` §5.*

**Test stop T3:** vectors · self-test · motion-check strips · Adam's on-glass feel session (lens
match, comfort of the depth pop, speed) · a soak day with the battery readout. **Exit:** no visible
lens mismatch; motion starts within D7's phone-started target; battery within the margin.

### Phase 4 — Local input (size XL; 2 flashes) — **its firmware is build C2: FLASHED 2026-11-19 (pin `b3f04453…`, `HANDOFF.md` §94), armed at ladder stage 4 (stage 5 since 11-21, on C3, §101); the Damage half is K's**

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

### Phase 5 — Firmware-owned chrome and the silent clock (size M; 1 flash) — **not started (the chrome is a candidate for the next firmware build, `FIRMWARE.md` §8.1)**

**Firmware:** the clock/battery/link cells drawn after the frame copy at Global depth from cached
fonts; RTC and fuel-gauge reads; the silent clock as a firmware minute program (no radio while silent).

**Damage:** the phone declares those rects firmware-owned and stops painting them; the model treats
them as outside the shadow; the idle tick and silent-mode traffic shrink to nothing.

**Test stop T5:** vectors · self-test · a silent-mode night (a quiet journal) · a soak day. This is
the last shell-facing flash before the rebuild, so Phase 6 runs on stable firmware.

### Phase 6 — The Damage rebuild: the shell and every window, fully animated (size XL; fix flashes only) — **this is K (`REPLAN.md` §16.1); K1's motion part under way (APK 0.83 and 0.84, `HANDOFF.md` §100, §101), K2–K4 not started**

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

### Phase 7 — The offline home (size L; 2 flashes) — **not started (step G decides it again)**

**Research first** (R0.5 deepened): display ownership with no EvenHub page, input with no page, screen
auto-off and wake, the persisted flag in the stock KV store, what to keep on the glasses.

**Firmware:** double-tap with no phone opens Damage's home (time, date, battery, last notifications,
staged pages) drawn from cached fonts and saved content; every path falls back to stock on failure;
the both-temple long-press untouched; a stock-dashboard override gesture kept.

**Damage:** what is saved while connected and when; the home's own small design pass with Adam.

**Test stop T7:** vectors · self-test · phone-off sessions (case at home, phone in a drawer) · the
stock override · a soak day · a phone-gone workday.

### Phase 8 — Consistency, docs, readiness (size M; 0–1 flash) — **not started (step G)**

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
in-the-moment go.** 7. `probe c1=0` on the phone before any flash (forward, a fix, or back): the stored stage arms at
the first start after it (build C1-P); then the stages again by hand on his go — all at once since his ruling of
2026-11-18 ("enable all the stuff, we need to test this new stuff asap", `HANDOFF.md` §91; the transport's
hold-back still lowers one stage per reset). The probe changes the STORED stage,
not the session in progress: the flags already armed stay set on the glasses until the next session start or the
lease's lapse (87.9 s after the phone's last renewal) — so before a lens that already runs a build with local input
(a rollback, a fix flash) receives a flash, a session has started after the probe or the phone's Bluetooth has been
off for 90 s (the C2 flash review, reader B, 2026-11-19: a bound gesture in that first minute would still draw and
send a 115 beside the transfer). Dry-run staircase, per lens with `--lens`: `--stop-before heartbeat` (connect and discovery, nothing sent) →
`--stop-before file_check` (notifications and the stock authentication, nothing stored) → `--stop-before flash` only
directly before that lens's full flash (its begin step writes and removes a 4 KiB storage test file; its file check
empties `firmware/codec.bin` until the flash rewrites it). An interrupted flash, or this last rung not followed
through, is finished with a full flash of that lens in the same sitting (`HANDOFF.md` §87).
8. Both lenses, one at a time, the same image (the cross-lens deferred path runs the same code on
both) — **LEFT first, RIGHT last**: the phone learns the build from RIGHT alone, so with LEFT alone on the new image
it treats the pair as the old one and sends nothing new at any stage; **no ladder stage beyond what the old image
takes is set before both lenses' logs show END status 8 and op 4 status 9** (with RIGHT alone on a C2 image a stage 4
would send mode 27 and the answers to a C1 LEFT, which refuses them: its picture stops following until `c1=3` and a
new start). 9. Reconnect, capability check (`DamageCaps … features 0x1f` in the journal on the Phase 2 build; `0x1ff` on a C1 build; `0x5ff` on a C2 build; `0xdff` on a C3 build), the first touches at stage 0 with RIGHT's uptime rising (the new input site with local input not armed) **and, on a C3 build, one page change at stage 0 (the stock menu in and out): the container-destroy site with no owner open, on stock's own paths**, self-test on glass
(`tools/glassdrive.py … selftest:firmware/vectors/v1-keyframe.json` and the other drawing vectors — RIGHT
reports), telemetry read (`probe:telemetry=read`: uptime, the panel record, the heap figures). 10. Arm
features one at a time (`probe:flags=0x8000` PROBE first, then `0x0001` PRESENTED, then `0x0003`; the
keeper re-arms the wanted set after every rebuild under the hold-back rule). **A C2 build's own check, which the
self-test cannot make (mode 27 is refused in a self-test step; a fire needs real input): at stage 4, one notch and
one tap on Main — each brings a 115 (S 1, 2), no reset note and no end of LEFT's link follow, telemetry fields 35,
36, 38 and 39 read (38 stays 0, 35 counts the gestures).** **A C3 build's own checks** (the flash-readiness review of 2026-11-20, readers A and B): at stage 4, Main's scroll at the four heights looks as on C2, field 34 is not above C2's, telemetry 40 reads 0, and in the minutes with one lens on each image both lenses show the same picture at every notch; **stage 5 is not set before both lenses are on C3** (a C2 LEFT answers the flag UNSUPPORTED and keeps `0x3c`: RIGHT alone would animate) **and before the phone's build carries the C3 branch**; **a stored stage 5 is lowered (`probe c1=4`) before the phone reconnects between the two lenses of any sitting that changes the capability bit** (a session start arms the STORED stage, and the phone arms each flag on RIGHT's answer alone: from the ring-list build on, a LEFT without EFFECTS refuses every batch that carries Main's LIST, and the shell then sleeps and rebuilds the session again and again — the first review pass of the ring list, 2026-11-21); at stage 5: one transition with telemetry 40 showing the owning slot during it and 0 after, telemetry 41 at 132 or more, one page rebuild and one both-temple long press during a transition (the picture comes back whole each time); after any reset at stage 5 the journal shows the hold-back to stage 3 and the flags show bit 6 clear. 11. Soak. 12. Journal and
`/log` read (`journal_report.py`: the transfer section). Rollback = reflash the last-known-good image, kept built
in `fws/` (each candidate in its own `fws/<version>-cfw-<pin8>/` with its patch list, `SHA256SUMS` and the sitting's logs; `verify.py` is run in a tree at the candidate's branch — `~/damage-cfw`'s own checkout may be on another). **Before any flash run on a lens that is on C3 or later — a rollback, a fix flash, and its `--stop-before flash` rung — read telemetry fields 8 and 28 on that lens** (the general heap's free bytes and largest block): C3's context takes 14.7 KB of the heap the flash's BEGIN storage test allocates 2 × 4 KiB from, and BEGIN formats the storage chip when either allocation fails (≈ 62 KiB free expected, inferred; the largest block must be well above 4 KiB); and with a transition's owner possibly open, the session restart after `probe c1=0` comes first (a release point ends every owner). The site list of every candidate (`~/damage-cfw/tools/verify.py` step 6) is reviewed against §3.1 (no new boot-path site) before step 6.

## 8. Assumptions this plan rests on, and where each is checked

| assumption | grade now | checked in |
|---|---|---|
| free heap is enough for a bigger cache, scratch and staged content | **measured for today's set: arena 13 226 KiB free at cache 160 + the shadow; a 256 KiB cache leaves ≈ 130, ≈ 82 with save-under full (modeled); arena 20 has 71–75 (`HANDOFF.md` §67)**; Phase 3's programs and scratch unknown | Phase 3's budget |
| the panel can present at 30–60 Hz | **measured 2026-09-15: transfer 2.0 ms median / 12.2 max, worker 3.2 / 17.7 max (`HANDOFF.md` §56) — the link is the ceiling** | F1.3 (done) |
| the two lenses can start a program together with no visible mismatch | inferred (stock does it over the UART sync) | T3 |
| local input can be handled on both lenses via stock's mirror | inferred | R0.1, Phase 4; C2, in use since 2026-11-19 — LEFT cannot report (`HANDOFF.md` §94); his account of both lenses moving, at times a moment apart (§95, §96), fits it but is not a reading |
| the ack precedes render on 2.2.6.10 | verified (decompile) | — |
| the refresh queue carries a rect | verified (decompile); honoured by the async refresh: **no** (verified, instruction level, 2026-09-13); a JBD4010 partial path exists, untimed | F1.3 (done); F1.7 in Phase 2 (Adam, 2026-09-15) |
| the glasses' idle link request can be gated on the lease | inferred (policy object mapped) | R0.4; F1.6 superseded by Phase 2's link edits, which bind the slow request to the fast record (§58) |
| one packet per ATT write is the phone-path limit | **answered no** (M0.3's per-event read, `HANDOFF.md` §54.4: two full packets per served event; F1.8 dropped) | M0.3 (done) |
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
  the work — the ring never does it; parked unless he asks (no release is planned: `CLAUDE.md`, "Who it is for").
  Phase 4's design note (local input off the input send's queue) stands.

## 10. Repos, files, where things live

| what | where |
|---|---|
| the order of work | `damagewm/REPLAN.md` (its §4 as amended by §14 and §17) |
| this plan | `damagewm/FORK.md` |
| the firmware contract (v1 facts by pointer, v2 as built and designed) | `damagewm/FIRMWARE.md` |
| conformance vectors | `damagewm/firmware/vectors/` (data) — inputs written by `damagewm/firmware/make_vectors.py`; expectations by the fork's `host/run_vectors.py --write`; checked by `ConformanceVectorTest` and `host/run_vectors.py` |
| the motion explosion and its refinery | `damagewm/MOTION.md` (§6 ruled 2026-11-13) |
| the Phase 0 reads | `damagewm/CLAIMS.md` ("Firmware internals read for the fork") and `damagewm/research/fork-reads-2026-09-13.md`; the tool `damagewm/research/fwread.py` |
| the fork's checks | `~/damage-cfw/tools/verify.py` (the candidate image), `~/damage-cfw/host/` (the C on the PC: vectors, `test_damage_ext.py`) |
| the firmware fork | `~/damage-cfw` (GPL-3.0; branch `damage` for Phases 1–2, then one per build: `c1`, `c2`, `c3`; `github` = `https://github.com/expectbugs/damage-cfw`, public; `origin` = upstream g2flash, fetch only; `reference` = the pinned clone) |
| the pinned upstream clone (never moved) | `damagewm/reference/g2flash` at `a5d1c31` (`research/verify_cfw.py` pins it) |
| the decompile corpus and subsystem docs | `damagewm/reference/evenRealities-openCFW/g2/` |
| records | `HANDOFF.md` §48 onward (§49 = 2026-09-13); `CLAIMS.md` rows added per phase; memory `damage-cfw-fork.md` |

## 11. Progress log — an index (the records are `HANDOFF.md`)

The log is `HANDOFF.md` §48 onward, one dated section per session; its headings are the index (the flashes: Phase 1
§55, Phase 2 §65, C1 §88, C2 §94, C3 §99).
