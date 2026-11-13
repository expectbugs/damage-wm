# Damage (`damage-wm`)

A from-scratch **window manager and compositor for the Even Realities G2**, running against
custom firmware (`g2flash`) that replaces the vendor's container model with direct framebuffer
access. The phone (or the PC) composes complete scenes with real fonts and arbitrary layout; the
glasses are a plain framebuffer.

A personal project: it drives the author's own glasses from his own phone and PC. The
wire-format work is interoperability with a consumer device he bought, so he can render his
own interface on it.

**The name is the graphics term.** *Damage* is the set of screen regions that changed since the
last frame (the X11 DAMAGE extension). Batching a frame's damage into one Bluetooth message
instead of one per region is the whole thesis: the round-trip cost is per message, not per region.

## Status (2026-11-13)

**Built and in daily use since 2026-08-31** (on the author's glasses since first light, 2026-08-30):
- **The phone APK (0.65)** drives the glasses: the radio and the shell. **The PC service** (OpenRC, headless)
  serves the content, tmux, the state sync and the window channel, and stands by to drive the glasses over
  PC-direct BlueZ while the APK is away. How it runs: [`IMPLEMENTATION.md`](IMPLEMENTATION.md); operations:
  `DAILY.md`.
- **Nine windows:** Main, Settings, Reader, Tmux, Files, Torrents, Music (with Music Mode), Games (Texas
  Hold'em against a persistent cast) and Feed (Reddit, Slashdot, webcomics). Per-window records: `TMUX.md`,
  `TORRENTS.md`, `MUSIC.md`, `HOLDEM.md`, `FEED.md`.
- **A Damage build of the custom firmware** (`~/damage-cfw`, GPL-3.0, its own repository; `FORK.md`) on both
  lenses since 2026-09-16: telemetry, a self-test, and the drawing contract v2 — per-lens cached draws, fills,
  clips, a partial panel refresh, LE 2M (`FIRMWARE.md` §3, §4).

**Designed, not built** — the order of work is [`REPLAN.md`](REPLAN.md): the PC becomes the one owner of every
window's state; four more interfaces present the same windows (the desktop, the phone app's own screen, a TV,
any browser — `INTERFACES.md`), beside a dashboard and control system (`BASTION.md`) and a reminders window
(`REMINDERS.md`); [`CONTRACT.md`](CONTRACT.md) is the app contract they share. Two batched firmware
flashes bring motion and local ring input to the glasses (`FIRMWARE.md` §5, §6; `MOTION.md`). New windows
follow.

[`HANDOFF.md`](HANDOFF.md) holds the dated records (§10–§26 install, first light and the app wave; §27–§47
reviews and latency; §48 onward the firmware fork; §78 the re-plan).

## Start here

| | |
|---|---|
| **[`REMINDER.md`](REMINDER.md)** | orientation — project state, what is next, what is still unmeasured |
| **[`REPLAN.md`](REPLAN.md)** | the order of work as the author ruled it (2026-11-12), and his rulings since |
| **[`CONTRACT.md`](CONTRACT.md)** | the app contract: one description per window, every interface a presenter of it (designed, not built) |
| [`FORK.md`](FORK.md) | the firmware fork: its protocol (§3), decisions, the design of each firmware piece, the flash ritual (§7) |
| [`FIRMWARE.md`](FIRMWARE.md) | the firmware contract both the fork (C) and Damage's simulator (Kotlin) implement; the conformance vectors (§9) |
| [`MOTION.md`](MOTION.md) | the motion explosion (every animation candidate per surface and window) and its refinery, ruled 2026-11-13 (§6) |
| `INTERFACES.md`, `BASTION.md`, `REMINDERS.md` | the explosions of the new interfaces, the dashboard and control system, and the reminders window (ruled in `REPLAN.md` §15) |
| [`HANDOFF.md`](HANDOFF.md) | the dated session records (§78 is the latest; `REMINDER.md` is the entry point) |
| [`WINDOWS.md`](WINDOWS.md) | how a G2CC app becomes a DamageWM window — the checklist new windows are built with, after `REPLAN.md`'s steps |
| [`EXPLOSION.md`](EXPLOSION.md) | the graded app backlog, the §16 contract record, the refinery verdicts |
| [`TORRENTS.md`](TORRENTS.md) | Torrents: verdicts, the verified qBittorrent and TorrentLeech facts, design, plan |
| [`MUSIC.md`](MUSIC.md) | Music: 29 verdicts, the verified library facts, the two-host design, the build plan with as-built notes |
| [`HOLDEM.md`](HOLDEM.md) | Games · Hold'em: 37 verdicts, format, ecology, card kit, the six-milestone plan; §17 = what the build did and where it departed |
| [`DAILY.md`](DAILY.md) | ops crib: services, ports, deploys, recovery |
| [`archive/REFINEMENT.md`](archive/REFINEMENT.md) | archived: the post-first-light refinement log, each ask, its analysis, what shipped (`archive/README.md` lists the folder) |
| [`IMPLEMENTATION.md`](IMPLEMENTATION.md) | what runs and how: modules, the transport seam, how to run and verify |
| [`overview.md`](overview.md) | the research record: hardware facts, the CFW display-mode contract, measured numbers, ecosystem, open unknowns |
| [`CLAIMS.md`](CLAIMS.md) | every load-bearing claim graded *vendor-authoritative / measured / corroborated / inferred / single-source / unknown* |
| [`archive/CAPABILITIES.md`](archive/CAPABILITIES.md) | archived: the 2026-08 hardware inventory, graded (its facts live in `overview.md` and `CLAIMS.md`) |
| [`DESIGN.md`](DESIGN.md) | the shell contract — input grammar, geometry, depth, motion, persistence, every shell surface (§4, context menu and keyboard included), typography, costs |

## Why it looks the way it does

Three facts about this display shape most of the design:

- **The ack floor is per *message*, not per rect.** So the compositor batches a frame's damage into
  one atomic flush — the thesis and the name.
- **Level 0 emits nothing — it is transparent.** So ink coverage *is* opacity, distraction and
  transmit cost, one number. The prettiest, least distracting and cheapest screen are the same screen.
- **RLE runs horizontally.** So wide-and-short compresses better than tall-and-narrow, a vertical
  drum foreshortens the cheap way, and horizontal rules cost almost nothing while vertical bars do.

## Building and verifying

```
./gradlew :core:test                                  # 1023 tests, incl. the per-lens oracle and the conformance vectors
./gradlew :desktop:test                               # 35 tests: the BlueZ glue over a fake link, the standby policy, the setup server, the config file, the xkcd PNG decoder, the status cell's fit
./gradlew :desktop:run --args="--selfcheck"           # the 485-check whole-stack gate, run twice (both contracts); run it more than once
./gradlew :desktop:run --args="--snapshot DIR"        # lens-truth PNGs of every surface
./gradlew :desktop:run --args="--epub-check"          # parse every book; chapters + image decode
./gradlew :desktop:run --args="--music-check"         # the real music library, read-only (counts, catalog, lanes, cache keys, Qdrant, viz)
./gradlew :desktop:run --args="--games-check"         # the Hold'em ecology over hundreds of simulated tournaments
./gradlew :desktop:run --args="--card-render"         # the card sheets at true 1x
./gradlew :desktop:run --args="--help"                # the usage text; an argument the program does not know is refused (exit 2) and nothing starts
./gradlew :desktop:run --args="--transport ble"       # PC-direct BLE (the at-the-desk fallback); stop the service first (exit 4 otherwise)
./gradlew :desktop:run                                # auto = the standby (the data host; drives only while the APK is away) + preview (4x); stop the service first (exit 4 otherwise)
./gradlew :phone:assembleDebug                        # the APK (deploy flow: :phone:stageApk → the setup page)
tools/lint.py                # design gate: SYM (every string literal), GEO (DESIGN.md's declared cells), BUD (ink from the renders); --selftest names what it covers
python3 design/render_shots.py   # design renders at true 1x, priced through the firmware's RLE
python3 research/verify_cfw.py   # rebuilds the CFW offline and checks every pinned hash
python3 research/fwread.py dis 0x473c44 0x473d70   # the stock image at instruction level (dis/fn/word/refs/calls/strings)
python3 firmware/make_vectors.py                   # the conformance vectors' inputs (FIRMWARE.md §9)
(cd ~/damage-cfw && python3 tools/verify.py)       # the fork's candidate image: pin, reproducibility, audit, size, sites
(cd ~/damage-cfw && python3 host/run_vectors.py)   # the fork's C on the PC against the same vectors
```

The linter exists because **this hardware reports its failures as silence** — an unaligned box is
refused without a word, a duplicate frame id is skipped, a stale delta composites onto the wrong
base. The design gate checks what `DESIGN.md` declares and what the renders show; the runtime rules
(stereo pairs, the rect budget, fid order, the frame walls) are `core`'s `geom` package (`Geometry.kt`, `FidTracker.kt`),
checked by the compositor on every emit and pinned by `GeometryTest`.

## Not included

- `reference/` — third-party source trees (g2flash, faceclaw, openCFW, SybilSight, g2-kit and
  others). Some carry no licence, so they are not ours to redistribute. Every URL is in
  `overview.md` §9.
- `fws/` — Even Realities firmware images. The public archive lives in the SybilSight webflasher.
- `captures/` — BTSnoop HCI logs from the author's own devices.
- `private/` — the private annexes the design documents point at.

## Status and licensing

Personal, first-party work on hardware the author owns. Licensing is deliberately undecided;
`DESIGN.md` and `overview.md` record protocol *facts* separately from any borrowed implementation,
because g2flash and faceclaw are GPL-3.0 and that boundary is worth keeping clean. See
`overview.md` §14.
