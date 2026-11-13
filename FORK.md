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

**What is live in this file (2026-11-13).**
1. **The order of work is `REPLAN.md`** (its §4 as amended by §14), not §5; the suspension of 2026-09-12 (no new
   windows, no Feed polish, no popover build outside the plan) carries over under it (`CLAUDE.md`).
2. **Binding:** §2's decisions (with the amendments its status line names), §3's principles — §3.8 is the protocol
   every build follows —, §7's flash ritual, §8's assumptions, §9 and §10.
3. **Design still to build:** §4 and Phases 3–8 of §5 — the first flash (C1) is Phase 3's, the second (C2) Phase
   4's, the glasses kit (K) their Damage halves and Phase 6, step G Phases 7–8; `FIRMWARE.md` §5–§6 is C1's and C2's contract.
4. **History:** Phases 0–2 are done and flashed (Phase 1 2026-09-15; Phase 2 2026-09-16, pin `48172b62…`, on both
   lenses since); their sections keep what still binds or is cited. Phase 2's test stop T2 is still open.
5. **§11 is an index** of the log; the records are `HANDOFF.md` §48 onward.

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

### Phase 1 — The fork pipeline and the first flash — **FLASHED 2026-09-15 04:38 (`HANDOFF.md` §55): both lenses on pin `c5e4f8b7…` (now the rollback image), the self-test green on glass, the panel JBD4010, the transfer 1.2–6.6 ms per present, PROBE + PRESENTED armed**

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
release is still load / test / store / free (corrected 2026-11-11, `HANDOFF.md` §76.8); every second
pass re-derives its pointer and records its refusal (§62.2 item 2, §63.2 items 2–3, §64.3 items 1–3). (e) One
read of the link state per decision — `pump()`, the compositor's `v2`, `CachedText`'s atlas (§62.3 item 9, §63.3
item 8, §64.2 item 6). (f) Mode 12 with 19, modes 14/15 with 18, the writers with the readers, both lenses of a
pair (§61.1 item 3, §63.2 items 1 and 3, §64.3 item 7). **Reader's page staging** was built after the flash, its
envelope answered BEFORE the code (`HANDOFF.md` §66 — the first build under §3.8).

**Test stop T2:** vectors · self-test · `--selfcheck` ×3 · snapshots · oracle walk · a soak day with the link's
ms/KB, a capture and the battery readout.
**Exit:** `cacheMiss` reasons `proof` and `planes` at zero; first-flush bytes down on every gesture
class; the page turn priced.

### Phase 3 — The motion engine, phone-started (size L; 1 flash)

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
| free heap is enough for a bigger cache, scratch and staged content | **measured for today's set: arena 13 226 KiB free at cache 160 + the shadow; a 256 KiB cache leaves ≈ 130, ≈ 82 with save-under full (modeled); arena 20 has 71–75 (`HANDOFF.md` §67)**; Phase 3's programs and scratch unknown | Phase 3's budget |
| the panel can present at 30–60 Hz | **measured 2026-09-15: transfer 2.0 ms median / 12.2 max, worker 3.2 / 17.7 max (`HANDOFF.md` §56) — the link is the ceiling** | F1.3 (done) |
| the two lenses can start a program together with no visible mismatch | inferred (stock does it over the UART sync) | T3 |
| local input can be handled on both lenses via stock's mirror | inferred | R0.1, Phase 4 |
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
  the work — the ring never does it; studied at the public-release polish for users without the ring. Phase 4's
  design note (local input off the input send's queue) stands.

## 10. Repos, files, where things live

| what | where |
|---|---|
| the order of work | `damagewm/REPLAN.md` (its §4 as amended by §14) |
| this plan and its log's index | `damagewm/FORK.md` |
| the firmware contract (v1 facts by pointer, v2 as built and designed) | `damagewm/FIRMWARE.md` |
| conformance vectors | `damagewm/firmware/vectors/` (data) — inputs written by `damagewm/firmware/make_vectors.py`; expectations by the fork's `host/run_vectors.py --write`; checked by `ConformanceVectorTest` and `host/run_vectors.py` |
| the motion explosion and its refinery | `damagewm/MOTION.md` (§6 ruled 2026-11-13) |
| the Phase 0 reads | `damagewm/CLAIMS.md` ("Firmware internals read for the fork") and `damagewm/research/fork-reads-2026-09-13.md`; the tool `damagewm/research/fwread.py` |
| the fork's checks | `~/damage-cfw/tools/verify.py` (the candidate image), `~/damage-cfw/host/` (the C on the PC: vectors, `test_damage_ext.py`) |
| the firmware fork | `~/damage-cfw` (GPL-3.0; branch `damage`; `github` = `https://github.com/expectbugs/damage-cfw`, public; `origin` = upstream g2flash, fetch only; `reference` = the pinned clone) |
| the pinned upstream clone (never moved) | `damagewm/reference/g2flash` at `a5d1c31` (`research/verify_cfw.py` pins it) |
| the decompile corpus and subsystem docs | `damagewm/reference/evenRealities-openCFW/g2/` |
| records | `HANDOFF.md` §48 onward (§49 = 2026-09-13); `CLAIMS.md` rows added per phase; memory `damage-cfw-fork.md` |

## 11. Progress log — an index (the records are `HANDOFF.md`)

- **2026-09-12** — the plan written; D1–D8 proposed; `FIRMWARE.md`'s skeleton; `~/damage-cfw` created from `a5d1c31`
  on branch `damage` with the flasher fix carried over (first commit `b3bdd5c`; the Damage record `d16e5c1`) — §48.
- **2026-09-13** — Phase 0 begun: R0.2–R0.4, R0.6 and the sid-0x0F logger read; APK 0.45's probes; `MOTION.md` drafted;
  the fork pinned to our clang, the host harness and the v1 vectors (the simulator matches the C on all 35 steps);
  F1.1/F1.2/F1.4 in source; §3.1's boot-path rule corrected — §49.
- **2026-09-14** — the 13th reviewed (field 4 the status register; the boot count withdrawn); four two-arm captures;
  M0.4 answered; **Adam's ruling: the drops are not the work** — §50.
- **2026-09-14, evening and night** — Phase 1's candidate built (§51; Adam chose the bounded atlas skip and left
  CACHE_KEEP unarmed, §51.8); two reviews (§52, §53), fork pin `c5e4f8b7…`.
- **2026-09-15** — the bounded atlas skip built; R0.1, R0.5 and M0.3's per-event read; F1.8 dropped — §54.
  **Phase 1 FLASHED** 04:38 (§55); the soak read (§56: transfer 2.0 ms median / 12.2 max, the tick 1.024 per ms;
  Adam's rulings: F1.7 into Phase 2, a FLAGS_SET with no lease refused, the soak is ordinary wear during Phase 2's
  build). Phase 2's design pass (§57, eleven decisions, as recommended); upstream's link edits into Phase 2's candidate
  (Adam, §58); a live defect fixed for APK 0.50 (§59); Phase 2 built (§60).
- **2026-09-15 night → 09-16** — four reviews of Phase 2 (§61–§64), the fourth with the differential fuzz and the
  mutation sweep; fork pin `48172b62…`; what they measured, and §3.8 (§65; Damage `b43a1bf`).
- **2026-09-16** — **Phase 2 FLASHED** (§65): LEFT 164 s, RIGHT 166 s, zero resends; first light 13:32 green
  (contract 2, the 160 KiB cache, DRAW2, LE 2M; `hintMaxRows` 240 → 40 on Adam's word, APK 0.56); the on-glass
  self-test 19 of 20 as its driver judged then — the twentieth's difference was the driver's own expectation (§73).
  Reader's page staging, the first build under §3.8 (§66, seven decisions as recommended); its first read and fixes
  (§66.1; Damage `ccf102d` before them); the status-cell ruling (§66.2).
- **2026-09-19** — upstream's 256 KiB cache and private transport priced (§67, §68); Adam: op 5's cap to 256 KiB, a
  largest-block figure and a deflated cache write ride the first flash, the private SID parked.
- **2026-09-20 → 21** — the status cell built (§69); the glasses ran flat in the case, two rulings (§70).
- **2026-11-04 → 09** — the phone's `/journal` route streams (§71, APK 0.61); the PC's out-of-memory and a shell that
  holds nothing unbounded while down (§72, APK 0.62; rulings D1–D6).
- **2026-11-09 → 10** — the atlas packs what a face draws and grows in place, the link estimator, the cache metric
  (§73, APK 0.63, installed 11-10; `v2-tablepatch` 11 of 11 on glass); upstream and official firmware surveyed,
  D1 stands (§74).
- **2026-11-10 → 12** — rulings on §71–§73's calls and the fork's mutation sweep (§75); APK 0.64 (§76) and 0.65 (§77),
  the vector pass (34 vectors).
- **2026-11-12 → 13** — the order of work re-planned and ruled (`REPLAN.md`, §78): §5's phase order replaced;
  `MOTION.md` §6 ruled; `FIRMWARE.md` §5 and §6 in contract form. Nothing flashed since 2026-09-16.
