# Where we are, and what to do next

**The entry point for a fresh session**: what is true now, what comes next, where the records are. History lives
in `HANDOFF.md`; this file only points at it. Read `CLAUDE.md` → this file → **`FORK.md`** (the plan; §11 is the
progress log) → `FIRMWARE.md` (the contract) → `HANDOFF.md` §48 (the fork decided) → §47 → §46 → §44 → §43 → §42 →
what they cite.

## Where we are (2026-09-12, evening)

- **🔴 The CFW fork and the Damage rebuild are the ONLY work (Adam, 2026-09-12; `HANDOFF.md` §48).** All other
  Damage work is suspended until `FORK.md` Phase 8 closes: no new windows, no Feed polish, no popover build outside
  the plan. `FORK.md` has the phases (0 measure/research/decide → 1 pipeline + first flash → 2 drawing v2 → 3 motion
  engine → 4 local input → 5 firmware chrome → 6 the shell and every window, animated → 7 offline home → 8 docs and
  readiness), the decisions D1–D8, the flash ritual and the assumptions table. `FIRMWARE.md` is the contract both
  the fork (`~/damage-cfw`, branch `damage`, first commit `b3bdd5c`) and the simulator implement. **Nothing
  flashed; Phase 0 not started.**
- **Latency hardened without the firmware (2026-09-12, `HANDOFF.md` §47):** the APK re-asks for its priority
  (Global `Link` row, `high` default) whenever the link slows; slow parameters flip the regime at once (`LINK SLOW`,
  one notice); the brightness write is answered and re-sent once; the standby claims only when both arms advertise
  to the PC; a full atlas evicts and repacks; Files and Torrents serve their last answer first; Tailscale leaves
  beardos around the VPN (the phone answered direct in 32 ms). **What the next `/log` answers** (now `FORK.md`
  M0.6 and R0.4 inputs): whether the firmware keeps asking for its idle set against the re-asks, and whether
  `balanced` changes the ~50-minute rebuilds (§42.2: none in 3 h on the slow set, n=1).
- **`POPOVER.md` is a complete spec, not built** (`HANDOFF.md` §46) — built in `FORK.md` Phase 6a (D3).
- **LIVE as the all-day daily driver.** CFW g2flash `a5d1c31` (reports `2.2.6.10`; detect by `EVENCFW/`, never
  the version). The phone APK drives — radio and shell; the OpenRC `damage` service on beardos is the data host
  and standby (`HANDOFF.md` §19, `DAILY.md`). **G2CC's server is RETIRED (`HANDOFF.md` §44) — never start it by
  hand:** the setup page is Damage's (`desktop/SetupServer.kt`), the playlists refresh under Damage, qBittorrent
  is the `qbittorrent` service.
- **App layer:** Main · Settings · Reader · Tmux · Files · Torrents · Music · Games · Feed. **Builds:** APK
  **0.42 installed**; **0.44 staged** (`~/.damage/damage-wm.apk`, `http://beardos:7300/setup`) = 0.43's Feed
  fixes + §47's link, atlas and cache work. The service runs 0.44's core.
- **Battery at HEAD (measured 2026-09-12):** core **533** · desktop **15** · `--selfcheck` 230 checks (run ×3 —
  it is a rate) · snapshots 57 · `--epub-check` · `--music-check` · `--games-check` · `--feed-check` · lint 21
  rules / 0 · `:phone:assembleDebug` in its OWN gradle call.

## Measured on glass (grade M)

Time to first ack per gesture, the phone's journal (`tools/journal_report.py`):

| gesture | first flush | first ack | source |
|---|---|---:|---|
| window notch, 0.40 → 0.42 (2026-09-07 → 12, n=5,257) | 437 B / 1.2 KB / 3.7 KB (median / p75 / p90) | 105 / 204 / 522 ms | §48.1 |
| Main notch, 0.40 (§42.0) | 716 B / 1.9 KB | 117 / 247 ms | §42.0 |
| whole window gesture (burst) | — | 342 ms median, 3.0 s p90 | §48.1 |

The ack precedes the panel refresh (§48.1, verified): what the eye waits for is longer than these. A flush under
100 B acks in ~60 ms; each KB adds ~140 ms; the tail is pixel bytes. Phone CPU per flush: 17 ms median / 66 p90.

## 🔴 The next session — `FORK.md` Phase 0

0. **Read `FORK.md` §11** (the log) and do the next item. Phase 0 is measurement and research, no firmware, no
   Damage code beyond dev tools: M0.1 the diagnostic overlay probe (heap free, present time), M0.2 the 240 fps
   video, M0.3 the two-arm BTSnoop with the APK driving (+ Wi-Fi off), M0.4 the 2M PHY request, M0.5 the sid-0x0F
   logger probe, M0.6 the battery baseline on `high` and `balanced`; R0.1–R0.6 the decompile reads (input path,
   inter-lens sync, refresh path, connection-parameter policy, stock helpers, heap); `FIRMWARE.md` from skeleton to
   draft; **the motion explosion and refinery with Adam**; lock D1–D8. Exit: the numbers in a table, the verb set,
   the contract draft, the decisions.
1. **Read the 0.44 journal and `/log` after Adam's first day on it** — it is M0.6 and the R0.4 input: the re-asks
   (`link` notes "asking for high again (#n)"), the arm rebuilds (`supervision timeout` on alternating arms every
   ~50 min; §42.2's ten candidates; a day on `balanced` is the cheap test), the wake loop (§42.3; the
   `keeper: start failed: …` notes name it). Fix only what blocks daily use; everything else waits for the fork.
2. **The atlas across a rebuild** (§42.4) is now `FORK.md` F1.5 (cache-keep with a generation and CRC).

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
242 B / 30 ms ≈ 8 KB/s. PC-direct BlueZ sends ~6 packets per event (~50 KB/s). The firmware's receive path parses
one packet per ATT write (§48.1, grade C). `overview.md` §5.2's `ms ≈ 60 + bytes/50` is PC-direct only — price
nothing with it. The fork's levers for this are `FORK.md` F1.6–F1.8, each gated on M0.3.

## Standing rules learnt the hard way (pointers)

`CLAUDE.md`'s short list binds: a rect a paint returns is a promise — measure ink, never a line height (§27); a
wait decides on ONE evaluation, a scene pins its seed, a rate is measured twenty times, the harness is part of
the system under review (§27.6, §30, §36.3); live-drive before calling a round done (§28.2, §33); never rebuild
the jar under a running instance (§29); never answer a refused image with more images, and the page traffic
sleeps with the shell — no release after a link loss (§36, §42). Not in that list: `handleMs` in the journal
INCLUDES the assemble (§35.1); in a live walk never scroll in Music's root, and the tmux pane's SECOND tap is the
keys list (a third sends a key); `tools/journal_report.py`'s per-gesture section judges every window (§42).
**For the fork:** never flash without Adam's in-the-moment go; the dry-run staircase first; read every patch
source; plain wording in every file, comment and commit (`HANDOFF.md` §48.5).

## 🔴 Still unmeasured on glass (the fork's Phase 0 takes most of these)

| # | what | why it matters |
|---|---|---|
| 1 | **Safe area** — draw a border, shrink until fully visible, store it | `DESIGN.md` §2.2b: 480 vs 288 is a *calibrated setting* |
| 2 | Ring **fast-spin coalescing + event-rate ceiling** | the focus model's limits; `FORK.md` Phase 4 |
| 3 | **Comfortable disparity** — ramp 0/4/8/12/16 | stock FAR does NOT stack (§48.1, verified); the ramp itself: `FORK.md` T3/T4 |
| 4 | **The rect budget of 5** (graded I) | derived from `cfw_diag()`, never observed; failure is silent |
| 5 | **Two-arm BTSnoop capture with the APK driving** — via the bug-report mail path (no adb) | `FORK.md` M0.3: packets per event; the arm split (graded I); one drop from the radio's side (§42.2) |
| 7 | **msgId-255 behaviour under CFW** | it ends the link on stock |
| 8 | **Chrome legibility** at the real faces on glass | renders cannot answer it |
| 9 | **WEA/CMAS visibility to a normal Android app** (Pixel 10a) | `DESIGN.md` §4.5's emergency promise rides on it |
| 10 | **Connected RSSI** on glass | the status bar's link cell |
| 15 | **Is the sid-0x01 prelude required** by the CFW before CREATE? (graded U) | three eaten CREATEs per cold start ≈ 6 s (§34.3) |
| 19 | **Cached text by default?** Adam's eye on kerning-free text | moot after `FORK.md` Phase 2 (kerning on) |
| 20 | **A draw into a released cache** — the ImgResCmd status it returns | replaced by `FORK.md` F1.5 (a queryable generation and CRC) |
| 21 | **Temple long-press accident rate** (gloves) | §1.2's bare-long-press no-op guards it |
| 24 | **Does the firmware enter Silent Mode by itself** (wear detection, idle)? | the journal will say |
| 25 | **The arm rebuilds' cause** (§42.2) | `FORK.md` M0.5 (a boot banner settles reset-or-stall) and M0.6 |
| 26 | **The wake loop's cause** (§42.3) | the `keeper: start failed: …` notes name it |
| 27 | **Free heap and present/worker time on glass** | `FORK.md` M0.1 — sets the cache, scratch and tick budgets |
| 28 | **True user-perceived latency** (ring press → visible change; the ack is only a lower bound) | `FORK.md` M0.2 |

**Cheap probes nobody has run:** the CFW logger service (sid 0x0F — M0.5) and the file-export service (sid
198/199 — `NOT_SUPPORT` is a safe answer).

## Upstream CFW (checked 2026-09-06 — `HANDOFF.md` §41.11)

Five g2flash commits past our pinned `a5d1c31`, all on stock base 2.2.9.22 — nothing affects the installed build.
`reference/g2flash` is fetched, not moved — a pull breaks `research/verify_cfw.py`'s 2.2.6.10 pins. **D1: the fork
stays on 2.2.6.10** (the decompile corpus is 2.2.6.10); 2.2.9's gains can be ported later.

## Suspended work (resumes after `FORK.md` Phase 8)

- **On-glass verdicts owed:** Torrents + the keyboard; Files (menus, viewers, the thumbnail lens, theme icons);
  Games (`HOLDEM.md` §17.4); Music (the grants — `DAILY.md`); Tmux (flow size/wrap feel, quick-key order, alert
  patterns, ssh latency). They fold into Phase 6's per-window verdict sessions.
- **Feed polish** (`HANDOFF.md` §43.6, `FEED.md` §8.3) — folds into Phase 6c. Never re-open `FEED.md` §1's verdicts.
- **The next window** — Adam's pick from `EXPLOSION.md` §20: Mail (#6), SMS (#7), Info (#8), Notices (#9); FF1
  (§10.9) first if he says so; all after Phase 8.
- The Reader legacy-offsets dual-write · the icon-quality pass · the unused `Profiler` Global row · **watch-items:**
  the left-lens seam residue after a handover, the ~20 s seam silent-loss window, the media endpoint logging
  nothing on success, `slappy` (tmux host) offline.

## Open design questions (not hardware-blocked)

Where system-state detail lives (the status bar shows telemetry; the deeper view wants an Info window —
`EXPLOSION.md` §9). Undesigned windows inherit Clear Sans until earned; the curated font-library expansion is
option-only (B612 never a default). `DESIGN.md` §0's cost-based exclusions (fades, dim-behind, banners) are
re-put to Adam in the Phase 0 refinery.

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
  it, `sudo rc-service tailscale restart` (connections opened from the tunnel address stay unanswered until
  re-dialled). `~/.local/bin/yt-dlp` self-updated to 2026.08.19 (`yt-dlp -U`).
- **`~/damage-cfw`** (2026-09-12, `FORK.md` §10): the firmware fork, cloned from `reference/g2flash` at
  `a5d1c31`, branch `damage`, the §10 flasher fix carried over; first commit `b3bdd5c`; remotes: `github` =
  Adam's public fork `https://github.com/expectbugs/damage-cfw` (branch `damage` tracks it), `origin` = upstream
  g2flash (fetch only, never pushed to), `reference` = the pinned local clone.

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
python3 research/verify_cfw.py                        # before any flashing conversation (the fork's own verify script comes in Phase 1)
```
