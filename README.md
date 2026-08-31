# Damage (`damage-wm`)

A from-scratch **window manager and compositor for the Even Realities G2**, running against
custom firmware (`g2flash`) that replaces the vendor's container model with direct framebuffer
access. The PC composes complete scenes with real fonts and arbitrary layout; the glasses are
a dumb framebuffer.

**Live on hardware since 2026-08-30, the all-day daily driver since 2026-08-31** — the CFW is
installed and the DEFAULT configuration runs for real: the phone APK owns the radio (its BLE
glue passed its own first light), the PC shell drives through it over the transport seam from
an OpenRC service, handovers ADOPT the live session (a driver change is one repaint, not a
teardown), and PC-direct BlueZ remains the at-the-desk fallback. Built 2026-08-24/25 (the
Kotlin shell core, the byte-exact glass simulator, the desktop program and the phone APK,
hardened through rounds of independent review), lit 2026-08-30, refined 2026-08-31 with the
wearer in the loop: chrome behind the content plane, per-app height, Reader folders / chapters
/ in-book images, a categorized Settings tree with global + per-app typography and depth, a
Tmux window (terminal output as FLOWED text — the cell grid is retired to an alternate-screen
fallback — with typed text via the replicas and waiting-session alerts), a seven-segment
silent clock, live brightness, wire-fed battery cells, and a measured latency curve
(`ms ≈ 60 + bytes/50`) that retired the modeled numbers. [`IMPLEMENTATION.md`](IMPLEMENTATION.md)
is the how-to-run; `HANDOFF.md` §10–18 are the install / first-light / refinement / launch-day
records; `DAILY.md` is the ops crib; `REFINEMENT.md` and `TMUX.md` the design logs.

## Start here

| | |
|---|---|
| **[`REMINDER.md`](REMINDER.md)** | orientation — project state, what is next, and what is still unmeasured |
| [`REFINEMENT.md`](REFINEMENT.md) | the post-first-light refinement log: every ask, its analysis, and what shipped |
| [`IMPLEMENTATION.md`](IMPLEMENTATION.md) | the built first stage: modules, the transport seam, how to run and verify |
| [`overview.md`](overview.md) | the research record: hardware facts, the CFW display-mode contract, measured numbers, the ecosystem, open unknowns |
| [`CLAIMS.md`](CLAIMS.md) | every load-bearing claim graded *vendor-authoritative / measured / corroborated / inferred / single-source / unknown*, and what cannot be resolved before flashing |
| [`CAPABILITIES.md`](CAPABILITIES.md) | what the hardware can actually do, graded |
| [`DESIGN.md`](DESIGN.md) | the shell contract — input grammar, geometry, depth, motion, persistence, all six surfaces, typography, costs |

## Why it looks the way it does

The design is shaped by three facts about this display, and most of it follows from them:

- **The ack floor is per *message*, not per rect.** So a compositor batches all damage for a frame
  into one atomic flush. That is the whole thesis, and the project's name.
- **Level 0 emits nothing — it is literally transparent.** So ink coverage *is* opacity,
  distraction and transmit cost, all one number. The prettiest screen, the least distracting screen
  and the cheapest screen are the same screen.
- **RLE runs horizontally.** So wide-and-short compresses better than tall-and-narrow, a vertical
  drum foreshortens the cheap way, and horizontal rules cost almost nothing while vertical bars do.

## Building and verifying

```
./gradlew :core:test                                  # 158 tests, incl. the per-lens oracle
./gradlew :desktop:test                               # 9 tests: the BlueZ glue over a fake link
./gradlew :desktop:run --args="--selfcheck"           # the 48-check whole-stack gate
./gradlew :desktop:run --args="--snapshot DIR"        # lens-truth PNGs of every surface
./gradlew :desktop:run --args="--epub-check"          # parse every book; chapters + image decode
./gradlew :desktop:run --args="--transport ble"       # PC-direct BLE (the at-the-desk fallback)
./gradlew :desktop:run                                # auto mode (phone seam first) + preview (4x)
./gradlew :phone:assembleDebug                        # the APK
tools/lint.py                # design gate: 20 rules (SYM/GEO/BUD/FID); --selftest proves each fires
python3 design/render_shots.py   # design renders at true 1x, priced through the firmware's RLE
python3 research/verify_cfw.py   # rebuilds the CFW offline and checks every pinned hash
```

The linter exists because **this hardware reports its failures as silence** — an unaligned box is
rejected without a word, a duplicate frame id is skipped, a stale delta composites onto the wrong
base. `tools/geometry.py` holds the rules as a library; `core`'s `Geometry.kt` mirrors them 1:1
(same rule IDs, pinned to the same fixtures by `GeometryTest`) and the compositor checks them on
every emit, so the design gate and the runtime assertions cannot drift apart.

## Not included

- `reference/` — third-party source trees (g2flash, faceclaw, openCFW, SybilSight, g2-kit and
  others). Some carry no licence at all, so they are not ours to redistribute. Every URL is in
  `overview.md` §9.
- `fws/` — Even Realities firmware images. The public archive lives in the SybilSight webflasher.
- `captures/` — BTSnoop HCI logs from the author's own devices.

## Status and licensing

Personal, first-party work on hardware the author owns. Licensing is deliberately undecided; note
that `DESIGN.md` and `overview.md` record protocol *facts* separately from any borrowed
implementation, because g2flash and faceclaw are GPL-3.0 and that boundary is worth keeping clean.
See `overview.md` §14.
