# Damage (`damage-wm`)

A from-scratch **window manager and compositor for the Even Realities G2**, running against
custom firmware (`g2flash`) that replaces the vendor's container model with direct framebuffer
access. The PC composes complete scenes with real fonts and arbitrary layout; the glasses are
a dumb framebuffer.

**The first stage is built (2026-08-24)** — the Kotlin shell core, the byte-exact glass
simulator, the desktop program and the phone APK, with Reader + Main as the first app layer.
[`IMPLEMENTATION.md`](IMPLEMENTATION.md) is the how-to-run; the research and design below remain
the ground truth it was built against. The real glasses stay on stock firmware until flash day.

## Start here

| | |
|---|---|
| **[`REMINDER.md`](REMINDER.md)** | orientation — project state, what is next, and the first-light checklist |
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

## Tooling

```
tools/lint.py                # build gate: 20 rules (SYM/GEO/BUD/FID)
tools/lint.py --selftest     # proves every rule FIRES, and valid geometry stays silent
python3 design/render_shots.py   # renders every surface at true 1x, priced through the
                                 # firmware's own RLE. Output in design/shots/
python3 research/verify_cfw.py   # rebuilds the CFW offline and checks every pinned hash
```

The linter exists because **this hardware reports its failures as silence** — an unaligned box is
rejected without a word, a duplicate frame id is skipped, a stale delta composites onto the wrong
base. `tools/geometry.py` holds the rules as a library the compositor will call on every emit, so
the build gate and the runtime assertions cannot drift apart.

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
