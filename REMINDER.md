# Where we are, and what to do next

**The entry point for a fresh session**: what is true now, what comes next, where the records are. History lives
in `HANDOFF.md`; this file only points at it. Read `CLAUDE.md` → this file → `HANDOFF.md` §46 (the popover spec) → §44 → §43 (Feed; §43.6 the
polish protocol) → §42 → §41 → what they cite.

## Where we are (2026-09-12)

- **Latency hardened without the firmware (2026-09-12, `HANDOFF.md` §47)** after a day the glasses moved both
  arms to 105 ms / latency 4 at noon and every flush waited ~470 ms more for three hours: the APK now re-asks
  for its priority (Global `Link` row, `high` default) whenever the link slows; slow parameters flip the regime
  at once, show `LINK SLOW` and raise one notice; the floor is watched on the minute; the brightness write is
  answered and re-sent once; the standby claims only when both arms advertise to the PC; a full atlas evicts the
  faces the window is not drawing and repacks; Files shows its cached folder first and Torrents keeps its
  snapshot; Tailscale leaves beardos around the VPN (the phone answered direct in 32 ms, was 62–1,108 ms via
  a relay; qBittorrent stays on the tunnel). **What the next `/log` answers:** whether the firmware keeps asking
  for its idle set against the re-asks (the `link` notes count them), and whether `balanced` changes the
  ~50-minute rebuilds (§42.2: none in the 3 h on the slow set, n=1).
- **`POPOVER.md` is a complete spec, not built** (2026-09-12, `HANDOFF.md` §46): one popover family
  (menu · notice · confirm · peek · deck · ask) on one modal stack, and the Claude path (`damage-show`,
  a user-level skill, `~/.damage/decks/`). Built whole when Adam calls it; its place in the queue is his.
- **The docs were de-bloated 2026-09-11** (`HANDOFF.md` §45): repo docs 1,174 → 866 KB, memory 211 → 61 KB,
  nothing lost that a checker could see. Keep them lean.
- **LIVE as the all-day daily driver.** CFW g2flash `a5d1c31` (reports `2.2.6.10`; detect by `EVENCFW/`, never
  the version). The phone APK drives — radio and shell; the OpenRC `damage` service on beardos is the data host
  and standby (`HANDOFF.md` §19, `DAILY.md`). **G2CC's server is RETIRED (2026-09-10, `HANDOFF.md` §44) — never
  start it by hand:** the setup page is Damage's (`desktop/SetupServer.kt`, same URL and token), the 25 adaptive
  playlists refresh under Damage (`MUSIC.md` §9.8, measured identical), qBittorrent is the `qbittorrent` service.
- **App layer:** Main · Settings · Reader · Tmux · Files · Torrents · Music · Games · Feed (`FEED.md`).
  **Builds:** APK **0.42 installed** (0.43 was staged, never installed); **0.44 staged** (`~/.damage/damage-wm.apk`,
  `http://beardos:7300/setup`) = 0.43's Feed fixes + §47's link, atlas and cache work. The service runs 0.44's core.
- **Battery at HEAD (measured 2026-09-12):** core **533** · desktop **15** · `--selfcheck` 230 checks (the truth
  oracle on every settle; run ×3 — it is a rate) · snapshots 57 · `--epub-check` · `--music-check` ·
  `--games-check` · `--feed-check` (`live` = the real sites) · lint 21 rules / 0 · `:phone:assembleDebug` in its
  OWN gradle call (with `:core:test` it once made the oracle walk miss a settle).

## Measured on glass, 0.40 (2026-09-07 → 09, Adam's use — `HANDOFF.md` §42.0)

Time to first visible change per gesture (`tools/journal_report.py`, the phone's journal; median / p90):

| gesture | first flush | first visible | burst total |
|---|---:|---:|---:|
| window list notch | 540 B / 2.1 KB | 108 / 229 ms | 1.6 KB |
| Main notch | 716 B / 1.9 KB | 117 / 247 ms | 1.2 KB |

Was, on 0.32 (§37.2): Main 830–860 ms, a window list 221–645 ms. The cache served 1,075 rects over 2,235 flushes;
`proof` refusals 126 (1,372 on 0.38 — the §41.9 retry works). Phone CPU per flush: 17 ms median / 66 p90.

## 🔴 The next session

0. **Feed polish** — `HANDOFF.md` §43.6 is the protocol, `FEED.md` §8.3 the numbered list. Confirm 0.43 is
   installed; **the measured walk before any change** (`FEED.md` §8.4: `tools/glassdrive.py` through every level,
   one step per snap around Mark all read, the rows into `FEED.md` §3.8; watch the comic canvas); Adam's
   verdicts (16 vs 4 gray levels for 8-Bit Theater, the bar, the text size); then §8.3 by number. Never re-open
   `FEED.md` §1's verdicts.
1. **Read the 0.44 journal and `/log` after Adam's first day on it** — the **re-asks** (`link` notes "asking
   for high again (#n)": a firmware that keeps asking for its idle set shows as a count climbing every 5 s;
   then the ask to Babcock is to gate the idle request on the lease), the **arm rebuilds** (`supervision
   timeout` on alternating arms every ~50 min, 41 in 2.5 days; §42.2's ten candidates; §47's n=1: none in 3 h
   on the slow parameters — a day on `Link = balanced` is the cheap test) and the **wake loop** (three
   minutes of session attempts after Silent Mode off, 2026-09-09 15:06; §42.3 — the `keeper: start failed: …`
   notes name it). Fix what they name. `tools/journal_report.py` now prints the parameters per hour.
2. **The atlas across a rebuild** (§42.4): the firmware keeps the cache now that a link loss sends no release; the
   shell still re-uploads 16–63 KB per rebuild. One glass measurement (item 20 below) gates the skip.
3. **Then the ranked list below**, and the next window (`WINDOWS.md` §6 is the bar) — or the popover
   build (`POPOVER.md` §8), whichever Adam calls first.

## Where the remaining latency and jank live (ranked by expected gain)

1. **The radio** — one packet per usable connection event at 15 ms, slave latency 1 (~8 KB/s, measured; the PC
   gets ~50 KB/s). Latency 0 at 7.5 ms during a session is up to 4× (modeled): a firmware ask to Babcock; the
   two-arm capture (item 5 below) settles the phone's write path.
2. **The arm rebuilds** (item 1 above): each ~9 s of blank plus a keyframe and the atlas.
3. **Frame pacing is the jank** — up to four ack-gated flushes per notch, ack jitter 2–3× the median. Tie frames
   to the measured regime as the wheel does; jump-cut when three are in flight.
4. **Back to Main / a switch between heights** (§42.0: 3.3 KB / 526 ms; 1.7 KB / 282 ms): the 17 B seed and the
   depth planes ship in one first flush. Screen plane first — `Slide fill = auto`'s logic.
5. **The atlas across a rebuild** (item 2), then **cache persistence across sessions** (a firmware ask).
6. **Kerning in cached text** — mode 14's per-glyph x-adjust bytes via `TextureCache.layout`'s kerning lambda,
   ~1 B per pair; only if Adam's eye dislikes flat advances.
7. **Cold start** — three eaten CREATEs ≈ 6 s per link edge (§34.3): lower `CAPABILITY_REASK_MS`, or CREATE only
   after the prelude's ack.

## Measured numbers to price with (grade M unless said)

The daily path is the PHONE's. Isolated flushes, APK-driven (§33.1, §35.2):

| flush size | median ack | p90 |
|---|---:|---:|
| < 500 B | 72–77 ms | 221–231 |
| 0.5–1.5 KB | 203–275 ms | 411–815 |
| 1.5–3 KB | 329–358 ms | 495–851 |
| 3–6 KB | 543–667 ms | 708–838 |
| 6 KB + | 1,036–1,140 ms | 1,295–1,543 |

Why (grade I): one AA packet per usable connection event; 15 ms interval with slave latency 1 = every 30 ms;
242 B / 30 ms ≈ 8 KB/s. PC-direct BlueZ sends ~6 packets per event (~50 KB/s). `overview.md` §5.2's
`ms ≈ 60 + bytes/50` is PC-direct only — price nothing with it.

## Standing rules learnt the hard way (pointers)

`CLAUDE.md`'s short list binds: a rect a paint returns is a promise — measure ink, never a line height (§27); a
wait decides on ONE evaluation, a scene pins its seed, a rate is measured twenty times, the harness is part of
the system under review (§27.6, §30, §36.3); live-drive before calling a round done (§28.2, §33); never rebuild
the jar under a running instance (§29); never answer a refused image with more images, and the page traffic
sleeps with the shell — no release after a link loss (§36, §42). Not in that list: `handleMs` in the journal
INCLUDES the assemble (§35.1); in a live walk never scroll in Music's root, and the tmux pane's SECOND tap is the
keys list (a third sends a key); `tools/journal_report.py`'s per-gesture section judges every window (§42).

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

Closed since the last version: the §38 wake (2026-09-06 20:45, 19 s), the watchdog silent on a healthy day, the
atlas upload (20 s for 63 KB), the height-change seed (17 B), the 2-frame wheel (`Slide frames` 4).
**Cheap probes nobody has run:** the CFW logger service (sid 0x0F — a boot banner settles §42.2's
reboot-or-stall) and the file-export service (sid 198/199 — `NOT_SUPPORT` is a safe answer).

## Upstream CFW (checked 2026-09-06 — `HANDOFF.md` §41.11)

Five g2flash commits past our pinned `a5d1c31`, all on stock base 2.2.9.22 (`EVENCFW/18`, 127 bytes) — §41.11
lists them; nothing affects the installed build. `reference/g2flash` is fetched, not moved — a pull breaks
`research/verify_cfw.py`'s 2.2.6.10 pins.

## Other open work (not the next session's)

- **On-glass verdicts owed:** Torrents + the keyboard; Files (menus, viewers, the thumbnail lens, theme icons);
  Games (`HOLDEM.md` §17.4); Music (the grants — `DAILY.md` — and the on-phone items); Tmux (flow size/wrap
  feel, quick-key order, alert patterns, ssh latency). **The next window** is Adam's pick from `EXPLOSION.md`
  §20: Mail (#6), SMS (#7, with the caller-ID source), Info (#8), Notices (#9); `WINDOWS.md` is the checklist.
- The Reader legacy-offsets dual-write in `ReaderWindow` (`SubstrateTest`'s migration pin goes with it) · the
  icon-quality pass (one drawn icon per app at 20 px + 56 px) · the unused `Profiler` Global row (remove or wire)
  · **watch-items:** the left-lens seam residue after a handover, the ~20 s seam silent-loss window, the media
  endpoint logging nothing on success, `slappy` (tmux host) offline 22 days.

## Open design questions (not hardware-blocked)

Where system-state detail lives (the status bar shows telemetry; the deeper view wants an Info window —
`EXPLOSION.md` §9). Undesigned windows inherit Clear Sans until earned; the curated font-library expansion is
option-only (B612 never a default).

## System changes made for this project

- Fonts: `/etc/portage/package.accept_keywords/damage-fonts` (`~amd64`, eight packages); 56 `media-fonts/*`
  installed; `design/fonts.json` pins the candidates; the locked faces are Clear Sans, Fira Sans, Alegreya,
  JetBrains Mono (`tools/lint.py` checks coverage against exactly those).
- `net-p2p/qbittorrent-5.1.4` with USE `webui` (`/etc/portage/package.use/60-qbittorrent`); Web UI
  `127.0.0.1:8090`, `LocalHostAuth=false`; OpenRC `qbittorrent` — `DAILY.md`. OpenRC `damage`
  (`/etc/init.d/damage`): after `postgresql-17` / `qdrant` / `qbittorrent`, `~/.local/bin` on PATH,
  `--enable-native-access=ALL-UNNAMED` (`HANDOFF.md` §44.1).
- **`/etc/local.d/tailscale-bypass.start`** (2026-09-12, `HANDOFF.md` §47): Tailscale's marked traffic
  (fwmark 0x80000) routes via the LAN gateway (table 100, rule pref 5200) instead of ProtonVPN's redirected
  default; runs at boot and by hand. qBittorrent and every unmarked socket keep the tunnel. After a change to
  it, `sudo rc-service tailscale restart` (connections opened from the tunnel address are black-holed until
  re-dialled). `~/.local/bin/yt-dlp` self-updated to 2026.08.19 (`yt-dlp -U`).

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
