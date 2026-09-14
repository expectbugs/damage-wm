# Damage (`damage-wm`)

A from-scratch **window manager and compositor for the Even Realities G2**, running against
custom firmware (`g2flash`) that replaces the vendor's container model with direct framebuffer
access. The PC composes complete scenes with real fonts and arbitrary layout; the glasses are
a plain framebuffer.

A personal project: it drives the author's own glasses from his own phone and PC. The
wire-format work is interoperability with a consumer device he bought, so he can render his
own interface on it.

**The name is the graphics term.** *Damage* is the set of screen regions that changed since the
last frame (the X11 DAMAGE extension). Batching a frame's damage into one Bluetooth message
instead of one per region is the whole thesis: the round-trip cost is per message, not per region.

**Live on hardware since 2026-08-30, the all-day daily driver since 2026-08-31.** The default
configuration runs for real: **the phone APK is the primary driver** (radio and shell; its BLE
glue passed first light on the first try), the PC is the data provider (content, tmux,
last-write-wins state sync over one port, from an OpenRC service) and a standby that drives
PC-direct BlueZ only while the APK is unavailable, handing the radio back on its return.

- **2026-08-24/25 — built:** Kotlin shell core, byte-exact glass simulator, desktop program,
  phone APK; hardened through rounds of independent review.
- **2026-08-30 — first light.**
- **2026-08-31 — refined with the wearer in the loop** (`REFINEMENT.md`): chrome behind the
  content plane, per-app height, Reader folders / chapters / in-book images, a categorized
  Settings tree with global + per-app typography and depth, a Tmux window (`TMUX.md`: terminal
  output as FLOWED text, the cell grid kept only as an alternate-screen fallback, typed text via
  the replicas, waiting-session alerts), a seven-segment silent clock, live brightness, wire-fed
  battery cells, and a measured latency curve that retired the modeled numbers
  (`ms ≈ 60 + bytes/50` on PC-direct; the phone path, the daily one, is ~70 ms + ~120 ms/KB —
  `REMINDER.md`).
- **2026-09-01 — FILES**, the first G2CC→DamageWM conversion: locations root with capacity bars,
  tap = floating context menu with Open first, in-app text/image/PDF viewers, clipboard slot,
  trash with restore, typed rename, EPUB→Reader deep links. New shared machinery: per-item
  last-write-wins state-sync substrate, generic window channel, notification signature, theme
  icons from the desktop theme. Eight-round adversarial review loop to convergence (79→…→0).
- **2026-09-01, same evening — TORRENTS and the KEYBOARD** (`TORRENTS.md`, `DESIGN.md` §4.8):
  qBittorrent transfers and the TorrentLeech account on glass — activity-sorted list with block
  bars and a live lens, details document, browse by category and search, torrent page with Add
  behind a confirm, done-notifications decided once host-side, seeding-under-a-week list for the
  tracker's rules — plus a ring-driven wireframe keyboard (row, then key) used by Torrents, Tmux
  and Files.
- **2026-09-01/02 — MUSIC** (`MUSIC.md`, `HANDOFF.md` §24): the G2CC music system taken over
  (Postgres, Qdrant, transcode cache, enrichment package, yt-dlp); the phone plays (ExoPlayer +
  media session — earbud taps drive the queue from anywhere, hold-my-volume, boost, sleep,
  prefetch, Spotify fallback); the PC is the library over the window channel + a Range-capable
  media endpoint; a **Now Playing** window (2026-09-03) with the queue one menu row down, browse,
  Ask through three resolver lanes, synced lyrics, YouTube grabs with full ingest, playlists
  edited on glass; **Music Mode**, a new EXCLUSIVE shell mode (`DESIGN.md` §4.9) stacking card,
  lyrics, visualizer, queue peek and clock per height.
- **2026-09-04 — GAMES · TEXAS HOLD'EM** (`HOLDEM.md`, `HANDOFF.md` §26): the first window with
  no host and no channel — pure Kotlin, identical on the phone alone, the PC alone or across the
  seam. A 6-max no-rebuy sit-and-go against a **persistent cast of characters** with their own
  money, moods and careers (they play their own tournaments in the background, go broke, take
  time off, retire); a shared bankroll with a visible entry fee and a Loser Count; card art drawn
  in code at four sizes; side pots to Robert's Rules, checked against a 3,000-scenario oracle; a
  table whose persisted state is an **action log** the engine replays. It changed the shell for
  every window: **the switcher resumes where you were, Main presents the window's root list**,
  and a window can name its own stereo regions (the hole cards sit forward of the table).
- **2026-09-09 — FEED** (`FEED.md`): Reddit, Slashdot and comics (xkcd, SMBC, an endless 8-Bit Theater
  archive) through one fetch engine that runs on either host.
- **2026-09-12 — the firmware fork decided** (`FORK.md`, `HANDOFF.md` §48): a Damage build of the custom
  firmware (`~/damage-cfw`, GPL-3.0, its own repo) so the glasses can animate and answer the ring
  themselves; every existing window is rebuilt on it, fully animated, before any new window.
- **2026-09-14 — Phase 0 reviewed and closed bar one read** (`HANDOFF.md` §50): the 13th's work corrected in four
  places, the first two-arm captures with the APK driving, no 2M PHY on the glasses, Adam's ruling that the arm drops
  are not the work; Phase 1 — the fork pipeline and the first flash — is next.
- **2026-09-13 — Phase 0 begun** (`HANDOFF.md` §49): the firmware's refresh, link-policy, lens-link,
  memory and log paths read at instruction level (`CLAIMS.md`, `research/fork-reads-2026-09-13.md`);
  measurement probes in the APK; the fork builds reproducibly and runs its drawing code on the PC, where
  Damage's simulator matches it on the conformance vectors; `MOTION.md` lists the animation candidates.

[`IMPLEMENTATION.md`](IMPLEMENTATION.md) is the how-to-run; `HANDOFF.md` the dated records (§10–§26
install / first light / refinement / the app wave; §27–§47 reviews and latency; §48–§49 the fork);
`DAILY.md` the ops crib; [`WINDOWS.md`](WINDOWS.md) the conversion checklist; `EXPLOSION.md` the graded
app backlog + refinery verdicts; `REFINEMENT.md` and `TMUX.md` the design logs.

## Start here

| | |
|---|---|
| **[`REMINDER.md`](REMINDER.md)** | orientation — project state, what is next, what is still unmeasured |
| **[`FORK.md`](FORK.md)** | the current work: the firmware fork and the Damage rebuild — phases, decisions, the flash ritual, the progress log (§11) |
| [`FIRMWARE.md`](FIRMWARE.md) | the firmware contract both the fork (C) and Damage's simulator (Kotlin) implement; the conformance vectors (§9) |
| [`MOTION.md`](MOTION.md) | the motion explosion: every animation candidate per surface and window, for Adam's refinery |
| [`HANDOFF.md`](HANDOFF.md) | the dated session records (§49 is the latest) |
| [`WINDOWS.md`](WINDOWS.md) | how a G2CC app becomes a DamageWM window — the checklist window conversion resumes with after the fork |
| [`EXPLOSION.md`](EXPLOSION.md) | the graded app backlog, the §16 contract record, the refinery verdicts |
| [`TORRENTS.md`](TORRENTS.md) | Torrents: verdicts, the verified qBittorrent and TorrentLeech facts, design, plan |
| [`MUSIC.md`](MUSIC.md) | Music: 29 verdicts, the verified library facts, the two-host design, the build plan with as-built notes |
| [`HOLDEM.md`](HOLDEM.md) | Games · Hold'em: 37 verdicts, format, ecology, card kit, the six-milestone plan; §17 = what the build did and where it departed |
| [`DAILY.md`](DAILY.md) | ops crib: services, ports, deploys, recovery |
| [`REFINEMENT.md`](REFINEMENT.md) | the post-first-light refinement log: each ask, its analysis, what shipped |
| [`IMPLEMENTATION.md`](IMPLEMENTATION.md) | the built first stage: modules, the transport seam, how to run and verify |
| [`overview.md`](overview.md) | the research record: hardware facts, the CFW display-mode contract, measured numbers, ecosystem, open unknowns |
| [`CLAIMS.md`](CLAIMS.md) | every load-bearing claim graded *vendor-authoritative / measured / corroborated / inferred / single-source / unknown* |
| [`CAPABILITIES.md`](CAPABILITIES.md) | what the hardware can do, graded |
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
./gradlew :core:test                                  # 540 tests, incl. the per-lens oracle and the conformance vectors
./gradlew :desktop:test                               # 15 tests: the BlueZ glue over a fake link, the config file, the xkcd PNG decoder
./gradlew :desktop:run --args="--selfcheck"           # the 230-check whole-stack gate (run it more than once)
./gradlew :desktop:run --args="--snapshot DIR"        # lens-truth PNGs of every surface
./gradlew :desktop:run --args="--epub-check"          # parse every book; chapters + image decode
./gradlew :desktop:run --args="--music-check"         # the real music library, read-only (counts, catalog, lanes, cache keys, Qdrant, viz)
./gradlew :desktop:run --args="--games-check"         # the Hold'em ecology over hundreds of simulated tournaments
./gradlew :desktop:run --args="--card-render"         # the card sheets at true 1x
./gradlew :desktop:run --args="--transport ble"       # PC-direct BLE (the at-the-desk fallback)
./gradlew :desktop:run                                # auto = the §19 standby (data host; claims nothing) + preview (4x)
./gradlew :phone:assembleDebug                        # the APK (deploy flow: :phone:stageApk → the setup page)
tools/lint.py                # design gate: 21 rules (SYM/GEO/BUD/FID); --selftest fires 16 of them in 18 cases
python3 design/render_shots.py   # design renders at true 1x, priced through the firmware's RLE
python3 research/verify_cfw.py   # rebuilds the CFW offline and checks every pinned hash
python3 research/fwread.py dis 0x473c44 0x473d70   # the stock image at instruction level (dis/fn/word/refs/calls/strings)
python3 firmware/make_vectors.py                   # the conformance vectors' inputs (FIRMWARE.md §9)
(cd ~/damage-cfw && python3 tools/verify.py)       # the fork's candidate image: pin, reproducibility, audit, size, sites
(cd ~/damage-cfw && python3 host/run_vectors.py)   # the fork's C on the PC against the same vectors
```

The linter exists because **this hardware reports its failures as silence** — an unaligned box is
refused without a word, a duplicate frame id is skipped, a stale delta composites onto the wrong
base. `tools/geometry.py` holds the rules as a library; `core`'s `Geometry.kt` mirrors them 1:1
(same rule IDs, pinned to the same fixtures by `GeometryTest`) and the compositor checks them on
every emit, so the design gate and the runtime assertions cannot drift apart.

## Not included

- `reference/` — third-party source trees (g2flash, faceclaw, openCFW, SybilSight, g2-kit and
  others). Some carry no licence, so they are not ours to redistribute. Every URL is in
  `overview.md` §9.
- `fws/` — Even Realities firmware images. The public archive lives in the SybilSight webflasher.
- `captures/` — BTSnoop HCI logs from the author's own devices.

## Status and licensing

Personal, first-party work on hardware the author owns. Licensing is deliberately undecided;
`DESIGN.md` and `overview.md` record protocol *facts* separately from any borrowed implementation,
because g2flash and faceclaw are GPL-3.0 and that boundary is worth keeping clean. See
`overview.md` §14.
