# Where we are, and what to do next

**This file is the entry point for a fresh session.** It says what is true now, what the next
session does, and where the records are. History lives in `HANDOFF.md` (§1–§36); this file only
points at it. Read in this order: `CLAUDE.md` → this file → `HANDOFF.md` §37 (the plan) → the
sections it cites.

## Where we are (2026-09-05, evening)

- **LIVE as the all-day daily driver.** CFW g2flash `a5d1c31` (reports `2.2.6.10`; detect by
  `EVENCFW/`, never the version). The **phone APK drives** — radio and shell — and the OpenRC
  `damage` service on beardos is the data host and standby (`HANDOFF.md` §19, `DAILY.md`).
- **App layer:** Main · Settings · Reader · Tmux · Files · Torrents · Music · Games.
- **Builds:** APK **0.33 installed** and driving; **0.34 staged** (`~/.damage/damage-wm.apk`,
  the setup page) with the silent-glasses fix; the service runs the same core (commit `4ac9091`).
  Until 2026-09-05 nothing newer than 0.16 had ever been on the glasses — every change since
  2026-09-01 arrived on glass this afternoon, and the walks below are the first measurements of
  it. Nothing in the tree is unverified against the battery; several things are still unverified
  **on glass** (the tables below).
- **Battery at HEAD:** core **469** · desktop **11** · `--selfcheck` **189** (the truth oracle on
  every settle; run ×3+ — it is a rate) · snapshots 49 · `--epub-check` 58/58 · `--music-check` ·
  `--games-check` · lint 21 rules / 0 · `:phone:assembleDebug`.

## The last day, in one paragraph each (records in `HANDOFF.md`)

- **§32 The latency pass.** The journal's slow regime is the PHONE's radio path; twelve
  pixel-identical changes (caches, memo, the wrap estimate, a reserved window slot, the adaptive
  wheel, the async state write, keep-alive, ssh multiplexing, the journal's `via`/CPU fields,
  `/journal` on every host, the APK's link-parameter logging).
- **§33 Live-measured from the PC** (`tools/glassdrive.py`): the phone path is 72 ms small,
  ~1.0 s at 6 KB+; HIGH priority is granted (15 ms / slave latency 1) and does not help; lost acks
  hold a slot for a msgId cycle.
- **§34** The pending-ack release; the eaten-CREATE false alarm (45 of 53 "lost acks" were the
  carrier CREATE's re-sends at session start).
- **§35** The split corrected: the phone's per-flush CPU is half the compositor's diff and plan;
  a live tmux pane cost ~130 ms a frame; both rewritten (0.33).
- **§36 The silent glasses.** The firmware's Silent Mode refuses every image and PUSHES the
  state; we dropped the push and stormed 20 KB keyframes for fifteen minutes. Fixed: the shell
  sleeps with the glasses, drops the lease on purpose, probes, wakes on the push.

## 🔴 The next session: the latency plan — `HANDOFF.md` §37 has the detail and the order

Adam's rulings (2026-09-05, evening) that bound it:

1. **Slide frames become a Global setting** — `off · 2 · 4 · auto · 8 · 12` frames per notch, with
   **`auto` = today's ease-out halving rule as the DEFAULT** (list 3, doc 5). He tests the feel
   himself; do not pick for him.
2. **The texture cache is to be adopted as far as it goes** — he thought it already was. Modes
   12/13/14: text through the glyph tables, icons as cached images. Step 1 is the on-glass check
   against the simulator (items 19–20 below); step 2 the emit strategy behind a setting.
3. **A live window keeping the link busy is FINE while it is the active window** — tmux, Music,
   Torrents: he is watching them. Not while inactive. Do not "optimise" their update cadence away;
   do make each update cost less (bytes).
4. **What he measures on glass right now (0.33):** a Main list notch takes **a full second** to
   show anything; a Reader notch about half that; a tmux history notch still about a second. The
   walk explains the first two (§37.2): a list notch's FIRST flush carries the lens repaint (icon +
   bold title + detail, 3–6 KB) while a Reader notch's first flush is the band copy plus a 1–4 KB
   strip. The lever is **time to first visible change**: send the translation first, the heavy
   fill second, and make the fill cheap (mode 14/13).

🔴 **FIRST (§37.0): the wake from the firmware's Silent Mode must REBUILD the session.** Tested on
glass with 0.34 at 22:04: the sleep worked (the READ's restored state and the push OFF both
parsed), but after the glasses said they were awake they refused every image for over a minute —
leaving Silent Mode tears the EvenHub session down. The design is settled in §37.0: wake =
`Transport.restartSession` (disconnect + `onLinkDown`, the keeper rebuilds everything — G2CC's
reconnect-and-relayout path); retire the black-keyframe probe (the 60 s READ is the fallback, plus
a paced restart attempt); journal system events 4/5/7; the simulator drops the carrier on silent
so the test forces the rebuild; no mirror check while asleep. Until then: Target → SIM → glasses.

The rest of the ordered work (§37.3): the chrome-only flush defect (149 of 320 flushes in a walk, a §8.3
violation) → first-visible-change ordering for list and canvas notches → the slide-frames setting
→ the texture cache on glass, then adopted → walk after each with `tools/glassdrive.py` and read
`/journal` → then the remaining §33–§35 items (truth render, cold-start re-ask, the radio's slave
latency, mode-9 for the wheel). And **write every new window to `WINDOWS.md` §6** (the latency
standards) so this is never needed again.

## Measured numbers to price with (grade M unless said)

The daily path is the PHONE's. Isolated flushes, APK-driven (§33.1, §35.2):

| flush size | median ack | p90 |
|---|---:|---:|
| < 500 B | 72–77 ms | 221–231 |
| 0.5–1.5 KB | 203–275 ms | 411–815 |
| 1.5–3 KB | 329–358 ms | 495–851 |
| 3–6 KB | 543–667 ms | 708–838 |
| 6 KB + | 1,036–1,140 ms | 1,295–1,543 |

Why (grade I, consistent to within noise): one AA packet per usable connection event; 15 ms
interval with slave latency 1 = every 30 ms; 242 B / 30 ms ≈ 8 KB/s. PC-direct BlueZ sends ~6
packets per event (~50 KB/s), which is the whole difference between the two regimes.

Time to first visible change per notch, from the 0.32 walk (§37.2): Reader 221–625 ms (first
flush 1.2–4.3 KB), tmux history 352–645 ms (one flush, 2.4–4.3 KB), Torrents/Main-style lists
830–860 ms (first flush 5.9–6.0 KB, the lens repaint). Phone CPU per flush after 0.33's rewrites:
unmeasured (0.33 was installed but not walked — walk it first).

`overview.md` §5.2's `ms ≈ 60 + bytes/50` is PC-direct only (four hours of it). Price nothing with it.

## Standing rules learnt the hard way (pointers)

- A rect a paint returns is a promise; measure ink, never a line height (§27).
- A wait decides on ONE evaluation; a scripted scene pins its seed; run a harness more than once,
  twenty when the question is a rate (§27.6, §30, §36.3 — the trap fired again in a new test).
- The harness is part of the system under review (§30). Live-drive the real program before
  calling a round done (§28.2, §33): snap between steps, one step per snap near a destructive row,
  never scroll in Music's root, the tmux pane's SECOND tap is the keys list (a third sends a key).
- Never rebuild the jar under a running instance (§29); `stageJar` replaces atomically.
- Never answer a refused image with more images (§36). Hold the lease while awake; drop it on
  purpose while the glasses are silent (`CLAUDE.md`).
- `handleMs` in the journal INCLUDES the assemble (§35.1).

## 🔴 Still unmeasured on glass

| # | what | why it matters |
|---|---|---|
| 1 | **Safe area** — draw a border, shrink until fully visible, store it | `DESIGN.md` §2.2b: 480 vs 288 is a *calibrated setting* |
| 2 | Ring **fast-spin coalescing + event-rate ceiling** | the focus model's limits |
| 3 | **Comfortable disparity** — ramp 0/4/8/12/16 | and whether stock FAR already spends the budget |
| 4 | **The rect budget of 5** (graded I) | derived from `cfw_diag()`, never observed; failure is silent |
| 5 | **Two-arm BTSnoop capture with the APK driving** — via the bug-report mail path (no adb) | packets per event (§37); the bulk-LEFT / control-RIGHT split (graded I). Handle 65's interval is KNOWN now (30 ms active / 90 ms idle on the official app; 15 ms / latency 1 under the APK) |
| 7 | **msgId-255 behaviour under CFW** | it ends the link on stock |
| 8 | **Chrome legibility** at the real faces on glass | renders cannot answer it |
| 9 | **WEA/CMAS visibility to a normal Android app** (Pixel 10a) | `DESIGN.md` §4.5's emergency promise rides on it |
| 10 | **Connected RSSI** on glass | the status bar's link cell |
| 15 | **Is the sid-0x01 prelude required** by the CFW before CREATE? (graded U) | and the 2 s re-ask: three eaten CREATEs per cold start ≈ 6 s (§34.3) |
| 19 | **The texture cache on glass** — mode-12 atlas up, 13/14 draws, pixel-compare vs the sim | the gate on adopting cached glyphs (§37). ⚠ mode 14 adds one overlay rect per glyph; a failed 64 KiB allocation shows only as the sticky `ALLOC` flag |
| 20 | **Atlas upload cost** at the measured rate; the cache survives a lease renewal, is freed on a lapse — and now on our own deliberate release while silent (§36) | prices the whole mode-14 trade |
| 21 | **Temple long-press accident rate** (gloves) | §1.2's bare-long-press no-op guards it |
| 22 | **The silent-mode push parsed on glass** — toggle with the APK connected, read the `silent` journal notes; do the temples respond while the shell is asleep (lease released)? | §36.4 |
| 23 | **The 2-frame wheel and the reserved slot** on a slow link — feel | §32 |
| 24 | **Does the firmware enter Silent Mode by itself** (wear detection, idle)? | the journal will say |

Closed since the last version of this table: the ack curve (both paths), the stall report (seen
live, §33.4), PC BLE, takeover/fallback, the switcher root cause, per-notch scroll, handle 65's
connection setup (it was in the captures all along — `research/linkparams.py`).

**Cheap probes nobody has run:** the CFW logger service (sid 0x0F) and the file-export service
(sid 198/199 — `NOT_SUPPORT` is a safe answer).

## Other open work (not the next session's)

- **On-glass verdicts** still owed for Torrents and the keyboard, Files (menus, viewers, the
  thumbnail lens, theme icons), Games (`HOLDEM.md` §17.4 — the card art at all four rungs, the
  hole-card plane, the arc stagger, the bot pace), Music (the one-time grants — `DAILY.md` — and
  the on-phone items).
- **The next window** is Adam's pick; Feed + comics (`EXPLOSION.md` §20 #5) is the standing
  candidate. The clean-room licensing rule (`CLAUDE.md`) binds any window that drives someone
  else's work.
- **The Reader transitional cleanup**: remove the legacy-offsets dual-write in `ReaderWindow`
  (fields marked; `SubstrateTest`'s migration pin goes with it).
- **The icon-quality pass**: one drawn icon per app at 20 px + 56 px (the release path).
- **Watch-items:** the left-lens seam residue after a handover; the ~20 s seam silent-loss
  window; the media endpoint logs nothing on success.

## Open design questions (not hardware-blocked)

- Where system-state detail lives (the status bar shows telemetry; the deeper view wants an Info
  window — `EXPLOSION.md` §9).
- Per-window typefaces for windows not yet designed inherit Clear Sans until earned; the curated
  font-library expansion is option-only (B612 never a default).

## System changes made for this project

- `/etc/portage/package.accept_keywords/damage-fonts` — `~amd64` for eight font data packages.
- 56 `media-fonts/*` packages installed; `design/fonts.json` pins the evaluated candidates; the
  locked faces are Clear Sans, Fira Sans, Alegreya, JetBrains Mono — `tools/lint.py` checks glyph
  coverage against exactly those.
- `net-p2p/qbittorrent-5.1.4` rebuilt with USE `webui` (`/etc/portage/package.use/60-qbittorrent`);
  Web UI on `127.0.0.1:8090`, `LocalHostAuth=false` — `DAILY.md`.

## How to resume

```
sudo rc-service damage status                         # the data host / standby; restart = stageJar + rc-service damage restart
./gradlew :core:test  ·  ./gradlew :desktop:test      # 469 · 11
desktop/build/install/desktop/bin/desktop --selfcheck # after ./gradlew :desktop:installDist; run it more than once
python3 tools/lint.py                                 # 21 rules, exits 0
python3 tools/glassdrive.py aphone TOKEN --pace 2.5 double wait:3 snap:/tmp/a.png …   # drive the glasses; snap before every tap
curl -s 'http://aphone:7403/journal?token=TOKEN' | python3 tools/journal_report.py -   # the phone's journal
```
