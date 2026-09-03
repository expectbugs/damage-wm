# Damage (`damage-wm`)

A from-scratch **window manager and compositor for the Even Realities G2**, running against
custom firmware (`g2flash`) that replaces the vendor's container model with direct framebuffer
access. The PC composes complete scenes with real fonts and arbitrary layout; the glasses are
a dumb framebuffer.

**Live on hardware since 2026-08-30, the all-day daily driver since 2026-08-31** — the CFW is
installed and the DEFAULT configuration runs for real: **the phone APK is the primary driver**
(radio and shell both, its BLE glue passed first light on the first try), the PC is the data
provider — content, tmux and last-write-wins state sync over one port, from an OpenRC service —
and a standby policy drives PC-direct BlueZ only while the APK is unavailable, handing the
radio back the moment it returns. Built 2026-08-24/25 (the
Kotlin shell core, the byte-exact glass simulator, the desktop program and the phone APK,
hardened through rounds of independent review), lit 2026-08-30, refined 2026-08-31 with the
wearer in the loop: chrome behind the content plane, per-app height, Reader folders / chapters
/ in-book images, a categorized Settings tree with global + per-app typography and depth, a
Tmux window (terminal output as FLOWED text — the cell grid is retired to an alternate-screen
fallback — with typed text via the replicas and waiting-session alerts), a seven-segment
silent clock, live brightness, wire-fed battery cells, and a measured latency curve
(`ms ≈ 60 + bytes/50`) that retired the modeled numbers.

**2026-09-01 — the app wave opened with FILES**, the first G2CC→DamageWM conversion: a
locations root with capacity bars, tap = a floating context menu with Open first, in-app
text/image/PDF viewers, a clipboard slot, trash with restore, typed rename, EPUB→Reader deep
links — riding new shared machinery (a per-item last-write-wins state-sync substrate, a
generic window channel, the notification signature, theme icons resolved from the desktop
theme) and an **eight-round adversarial review loop run to convergence** (79→…→0 findings).
**Then the same evening, TORRENTS and the KEYBOARD** (`TORRENTS.md`, `DESIGN.md` §4.8): his
qBittorrent transfers and TorrentLeech account on glass — an activity-sorted list with block
bars and a live lens, a details document, browse by category and search, a torrent page with
Add behind a confirm, done-notifications decided once host-side, a seeding-under-a-week list
for the tracker's rules — and a ring-driven wireframe keyboard (row, then key) that Torrents,
Tmux and Files all ask for.
[`IMPLEMENTATION.md`](IMPLEMENTATION.md) is the how-to-run; `HANDOFF.md` §10–§24 are the
install / first-light / refinement / launch-day / app-wave records; `DAILY.md` is the ops
crib; [`WINDOWS.md`](WINDOWS.md) the conversion checklist; `EXPLOSION.md` the graded app
backlog + refinery verdicts; `REFINEMENT.md` and `TMUX.md` the design logs.

**2026-09-01/02 — MUSIC**, built whole overnight (`MUSIC.md`, `HANDOFF.md` §24): the G2CC music
system taken over (Postgres, Qdrant, the transcode cache, the enrichment package, yt-dlp), the
phone as the player (ExoPlayer + a media session — earbud taps drive the queue from anywhere,
hold-my-volume, boost, sleep, prefetch, Spotify as the fallback), the PC as the library over the
window channel + a Range-capable media endpoint, a **Now Playing** window (2026-09-03) with the
queue one menu row down, browse, Ask through
three resolver lanes, synced lyrics, YouTube grabs with full ingest, playlists edited on glass,
and **Music Mode** — a new EXCLUSIVE shell mode (`DESIGN.md` §4.9) stacking card, lyrics,
visualizer, queue peek and clock per height.

## Start here

| | |
|---|---|
| **[`REMINDER.md`](REMINDER.md)** | orientation — project state, what is next, and what is still unmeasured |
| [`WINDOWS.md`](WINDOWS.md) | how a G2CC app becomes a DamageWM window — the current phase's build checklist |
| [`EXPLOSION.md`](EXPLOSION.md) | the graded app backlog, the §16 contract record, the refinery verdicts |
| [`TORRENTS.md`](TORRENTS.md) | the Torrents window: verdicts, the verified qBittorrent and TorrentLeech facts, the design, the plan |
| [`MUSIC.md`](MUSIC.md) | the Music window: 29 verdicts, the verified library facts, the two-host design, the build plan with its as-built notes |
| [`DAILY.md`](DAILY.md) | the daily-driver ops crib: services, ports, deploys, recovery |
| [`REFINEMENT.md`](REFINEMENT.md) | the post-first-light refinement log: every ask, its analysis, and what shipped |
| [`IMPLEMENTATION.md`](IMPLEMENTATION.md) | the built first stage: modules, the transport seam, how to run and verify |
| [`overview.md`](overview.md) | the research record: hardware facts, the CFW display-mode contract, measured numbers, the ecosystem, open unknowns |
| [`CLAIMS.md`](CLAIMS.md) | every load-bearing claim graded *vendor-authoritative / measured / corroborated / inferred / single-source / unknown* |
| [`CAPABILITIES.md`](CAPABILITIES.md) | what the hardware can actually do, graded |
| [`DESIGN.md`](DESIGN.md) | the shell contract — input grammar, geometry, depth, motion, persistence, every shell surface (§4, the context menu and the keyboard included), typography, costs |

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
./gradlew :core:test                                  # 329 tests, incl. the per-lens oracle
./gradlew :desktop:test                               # 9 tests: the BlueZ glue over a fake link
./gradlew :desktop:run --args="--selfcheck"           # the 134-check whole-stack gate
./gradlew :desktop:run --args="--snapshot DIR"        # lens-truth PNGs of every surface
./gradlew :desktop:run --args="--epub-check"          # parse every book; chapters + image decode
./gradlew :desktop:run --args="--music-check"         # the real music library, read-only (counts, catalog, lanes, cache keys, Qdrant, viz)
./gradlew :desktop:run --args="--transport ble"       # PC-direct BLE (the at-the-desk fallback)
./gradlew :desktop:run                                # auto = the §19 standby (data host; claims nothing) + preview (4x)
./gradlew :phone:assembleDebug                        # the APK (deploy flow: :phone:stageApk → the setup page)
tools/lint.py                # design gate: 20 rules (SYM/GEO/BUD/FID); --selftest fires 15 of them in 16 cases
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
