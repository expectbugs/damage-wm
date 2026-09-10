# Where we are, and what to do next

**This file is the entry point for a fresh session.** It says what is true now, what the next
session does, and where the records are. History lives in `HANDOFF.md` (§1–§42); this file only
points at it. Read in this order: `CLAUDE.md` → this file → `HANDOFF.md` §43 (Feed: designed,
built, on glass and fixed in one day; §43.6 is the resume protocol for its polish) → §42 (the two
journals read, what 0.41 fixed, the two open link mysteries) → §41 (the cache on every plane, the
depth ladder) → the sections they cite.

## Where we are (2026-09-09)

- **LIVE as the all-day daily driver.** CFW g2flash `a5d1c31` (reports `2.2.6.10`; detect by
  `EVENCFW/`, never the version). The **phone APK drives** — radio and shell — and the OpenRC
  `damage` service on beardos is the data host and standby (`HANDOFF.md` §19, `DAILY.md`).
- **App layer:** Main · Settings · Reader · Tmux · Files · Torrents · Music · Games · **Feed**
  (2026-09-09, `FEED.md` — designed, built, walked by Adam on glass and its five findings fixed,
  all in one day; the polish session is next, `HANDOFF.md` §43.6).
- **Builds:** APK **0.42 installed** (2026-09-09 evening — the Feed build Adam walked; it
  carries 0.41's §42 fixes: the page traffic sleeps with the glasses, no lease release after a
  link loss, the keeper in the journal, `/log`); **0.43 staged** (`~/.damage/damage-wm.apk`, the
  setup page) = the evening's Feed fixes (`HANDOFF.md` §43.4: the comic bar and the 17 px text
  need it; the post text, source articles and Slashdot threads come from the PC and reach 0.42
  already). The service runs the same core as 0.43 and serves `feed` on the content port.
- **Battery at HEAD:** core **521** · desktop **12** · `--selfcheck`
  (230 checks, the truth oracle on every settle, the Feed walk included; run ×3 — it is a rate) ·
  snapshots (57, eight of them Feed) · `--epub-check` · `--music-check` · `--games-check` ·
  `--feed-check` (fixtures; `live` for the real sites) · lint 21 rules / 0 ·
  `:phone:assembleDebug` (run it SEPARATELY from the test batteries — a concurrent APK build
  made the oracle walk miss a settle once).

## Measured on glass, 0.40 (2026-09-07 → 09, Adam's use — `HANDOFF.md` §42.0)

Time to first visible change per gesture, from `tools/journal_report.py` over the phone's
journal (median / p90):

| gesture | first flush | first visible | burst total |
|---|---:|---:|---:|
| window list notch | 540 B / 2.1 KB | 108 / 229 ms | 1.6 KB |
| Main notch | 716 B / 1.9 KB | 117 / 247 ms | 1.2 KB |

Was, on 0.32 (§37.2): Main 830–860 ms, a window list 221–645 ms. The cache served 1,075 rects
over 2,235 flushes; `proof` refusals 126 (1,372 on 0.38 — the §41.9 retry works). Phone CPU per
flush: handle 17 ms median / 66 p90.

## 🔴 The next session

0. **Feed polish — `HANDOFF.md` §43.6 is the protocol, `FEED.md` §8.3 the numbered list.**
   In order: confirm 0.43 is installed; **the measured walk before any change** (`FEED.md`
   §8.4: `tools/glassdrive.py` through every level, one step per snap around Mark all read,
   `journal_report.py`'s per-gesture rows into `FEED.md` §3.8; the comic canvas is the case to
   watch); Adam's verdicts (16 vs 4 gray levels for 8-Bit Theater, the bar's feel, the text
   size); then §8.3 by number — the Reddit comments pace first (a comments fetch inside a
   minute of the listing's waits out the pace), the honest line for a Slashdot page that came
   without its tree, the by-number restore; ask before an SMBC archive or a bar on the 8-Bit
   archive; one deliberate fallback try with the service stopped; his `feedSources` beyond the
   day-one five. Never re-open `FEED.md` §1's verdicts.
1. **Read the 0.41 journal and `/log` after Adam's first day on it** — the two things §42 could
   not explain are instrumented now: the **arm rebuilds** (a `supervision timeout` on
   alternating arms every ~50 min, 41 in 2.5 days; §42.2 lists ten candidates and the cheap
   discriminators — start with the `link` notes and the `/log` lines around one drop, then
   G2CC's stock-era logs for the same cadence) and the **wake loop** (three minutes of
   session attempts after Silent Mode off on 2026-09-09 15:06; §42.3 — the `keeper: start
   failed: …` notes will name it). Fix what they name.
2. **The atlas across a rebuild** (§42.4): the firmware keeps the cache on both lenses now that
   a link loss sends no release; the shell still re-uploads 16–63 KB per rebuild. One glass
   measurement gates the skip (what status a draw into a released cache returns).
3. **Then the ranked latency list below**, and the next window (`WINDOWS.md` §6 is the bar).

## Where the remaining latency and jank live (ranked by expected gain)

1. **The radio itself.** The phone path moves one packet per usable connection event at 15 ms
   with slave latency 1 (~8 KB/s, measured); the PC gets ~50 KB/s from the same glasses.
   Latency 0 at 7.5 ms while a session is active is up to 4× (modeled) — a firmware-side ask
   to Babcock; a two-arm capture with the APK driving (row 5 below) settles whether the
   phone's write path is the other half of the wall.
2. **The arm rebuilds** (item 1 above): each is ~9 s of blank plus a keyframe and the atlas.
3. **Frame pacing is the jank.** A notch is up to four flushes gated on acks, and ack jitter
   runs 2–3× the median, so frames land unevenly. Tie the frame count to the measured link
   regime as the wheel does, and jump-cut to the final frame when three flushes are already
   in flight.
4. **Back to Main / a window switch between heights** (§42.0: 3.3 KB / 526 ms; 1.7 KB / 282
   ms): the seed is 17 B but the depth planes ship as deltas in the same first flush. Send the
   screen plane first, the depth planes a flush later — `Slide fill = auto`'s logic.
5. **The atlas across a rebuild** (item 2 above), then **cache persistence across sessions**
   — a firmware ask (a checksum the phone can verify).
6. **Kerning in cached text.** Mode 14 carries per-glyph x-adjust bytes and
   `TextureCache.layout` takes a kerning lambda: ~1 B per pair. If Adam's eye dislikes the
   flat advances.
7. **Cold start.** Three eaten CREATEs ≈ 6 s per link edge (§34.3): lower
   `CAPABILITY_REASK_MS`, or send the CREATE only after the prelude's ack.

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
`overview.md` §5.2's `ms ≈ 60 + bytes/50` is PC-direct only. Price nothing with it.

## Standing rules learnt the hard way (pointers)

- A rect a paint returns is a promise; measure ink, never a line height (§27).
- A wait decides on ONE evaluation; a scripted scene pins its seed; run a harness more than once,
  twenty when the question is a rate (§27.6, §30, §36.3).
- The harness is part of the system under review (§30). Live-drive the real program before
  calling a round done (§28.2, §33): snap between steps, one step per snap near an irreversible row,
  never scroll in Music's root, the tmux pane's SECOND tap is the keys list (a third sends a key).
- Never rebuild the jar under a running instance (§29); `stageJar` replaces atomically.
- Never answer a refused image with more images (§36). Hold the lease while awake; drop it on
  purpose while the glasses are silent; **and send nothing into a page that has ended** (§42:
  the keepalive and the carrier refresh sleep with the shell).
- A stop after a link loss releases nothing (§42): the write cannot reach the dropped arm, and
  the surviving lens's cache is worth keeping for the rebuild seconds away.
- `handleMs` in the journal INCLUDES the assemble (§35.1).
- Read the journal through `tools/journal_report.py` — its per-gesture section is the number
  every window is judged by; `/log` is the phone's log without adb (§42).

## 🔴 Still unmeasured on glass

| # | what | why it matters |
|---|---|---|
| 1 | **Safe area** — draw a border, shrink until fully visible, store it | `DESIGN.md` §2.2b: 480 vs 288 is a *calibrated setting* |
| 2 | Ring **fast-spin coalescing + event-rate ceiling** | the focus model's limits |
| 3 | **Comfortable disparity** — ramp 0/4/8/12/16 | and whether stock FAR already spends the budget |
| 4 | **The rect budget of 5** (graded I) | derived from `cfw_diag()`, never observed; failure is silent |
| 5 | **Two-arm BTSnoop capture with the APK driving** — via the bug-report mail path (no adb) | packets per event (§37); the bulk-LEFT / control-RIGHT split (graded I); one supervision-timeout drop from the radio's side (§42.2) |
| 7 | **msgId-255 behaviour under CFW** | it ends the link on stock |
| 8 | **Chrome legibility** at the real faces on glass | renders cannot answer it |
| 9 | **WEA/CMAS visibility to a normal Android app** (Pixel 10a) | `DESIGN.md` §4.5's emergency promise rides on it |
| 10 | **Connected RSSI** on glass | the status bar's link cell |
| 15 | **Is the sid-0x01 prelude required** by the CFW before CREATE? (graded U) | and the 2 s re-ask: three eaten CREATEs per cold start ≈ 6 s (§34.3) |
| 19 | **Cached text by default?** Adam's eye on kerning-free text at 100 %/130 % | the gate on leaving the row on by default; the numbers are in (§42.0) |
| 20 | **A draw into a released cache** — the ImgResCmd status it returns | gates the atlas surviving a rebuild (§42.4) |
| 21 | **Temple long-press accident rate** (gloves) | §1.2's bare-long-press no-op guards it |
| 24 | **Does the firmware enter Silent Mode by itself** (wear detection, idle)? | the journal will say |
| 25 | **The arm rebuilds' cause** (§42.2) | ~9 s of blank a dozen times a day |
| 26 | **The wake loop's cause** (§42.3) | Adam's "some doing" after Silent Mode off |

Closed since the last version of this table: the §38 wake (seen 2026-09-06 20:45, 19 s), the
watchdog silent on a healthy day, the atlas upload cost (20 s for 63 KB, idle chunks), the
keyframe seed on a height change (17 B), the 2-frame wheel (Adam took `Slide frames` 4).

**Cheap probes nobody has run:** the CFW logger service (sid 0x0F — a boot banner would settle
§42.2's reboot-or-stall) and the file-export service (sid 198/199 — `NOT_SUPPORT` is a safe
answer).

## Upstream CFW (checked 2026-09-06 — `HANDOFF.md` §41.11)

g2flash has five commits past our pinned `a5d1c31`, all on a **new stock base 2.2.9.22**
(`EVENCFW/18`, exactly 127 bytes): a lost-ACK fix stock 2.2.9 needs and our 2.2.6 base does not,
compass config options, an ambient-light mode 16, the tap-then-long gesture as event 11 with
the raw source passed to the sender (an ATTRIBUTED long-press, grade I), and a flasher that does
2.2.9's auth handshake before BEGIN. **Nothing affects the installed build.** `reference/g2flash`
is fetched, not moved — a pull breaks `research/verify_cfw.py`'s 2.2.6.10 pins.

## Other open work (not the next session's)

- **On-glass verdicts** still owed for Torrents and the keyboard, Files (menus, viewers, the
  thumbnail lens, theme icons), Games (`HOLDEM.md` §17.4), Music (the one-time grants —
  `DAILY.md` — and the on-phone items).
- **Feed + comics is BUILT and on glass (2026-09-09, `FEED.md` §8, `HANDOFF.md` §43) — its
  polish is item 0 above.** The next window after it is Adam's pick from `EXPLOSION.md` §20:
  Mail (#6), SMS (#7, with the caller-ID source), Info (#8), Notices (#9). `WINDOWS.md` is the
  checklist (seven precedents now) and §6 the latency bar.
- **The Reader transitional cleanup**: remove the legacy-offsets dual-write in `ReaderWindow`
  (fields marked; `SubstrateTest`'s migration pin goes with it).
- **The icon-quality pass**: one drawn icon per app at 20 px + 56 px (the release path).
- **The `Profiler` Global row is unused** (nothing reads it) — remove or wire it.
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
sudo rc-service damage status                         # the data host / standby; deploy = ./gradlew :desktop:stageJar && sudo rc-service damage restart
./gradlew :core:test  ·  ./gradlew :desktop:test
desktop/build/install/desktop/bin/desktop --selfcheck # after ./gradlew :desktop:installDist; run it more than once
python3 tools/lint.py                                 # 21 rules, exits 0
TOKEN=$(python3 -c "import json;print(json.load(open('/home/user/.damage/config.json'))['token'])")
curl -s "http://aphone:7403/journal?token=$TOKEN" | python3 tools/journal_report.py -   # the phone's journal, per-gesture numbers included
curl -s "http://aphone:7403/log?token=$TOKEN&tail=400"                                 # the phone's log (0.41+), no adb
python3 tools/glassdrive.py aphone $TOKEN --pace 2.5 double wait:3 snap:/tmp/a.png …    # drive the glasses; snap before every tap
```
