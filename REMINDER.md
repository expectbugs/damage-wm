# Where we are, and what to do next

**The entry point for a fresh session**: what is true now, what is next, where the records are; history lives in
`HANDOFF.md`. Read `CLAUDE.md` → this file → **`HANDOFF.md` §94** (C2 flashed on both lenses and armed at stage 4;
the first trial and its three defects) → §93 (0.77 on his phone; Music's answers; work goes on while a build waits)
→ **§92** (build C2: built, reviewed, a candidate image,
nothing flashed; Main's scroll judged on glass; APK 0.77 staged) → **§91** (0.70 and 0.71 deployed, the ladder at
stage 3 on his word, build C1-Q, Reddit's feed) → §90 (C1-P) → **§89** (S1-3: Feed and Tmux on the PC's record, APK 0.70; its four
reversible calls, 89.5) → **§88** (C1 flashed) → §87 (the flash-readiness review) → **§84** (the
cleanup L0 and L1; the lead's calls he may reverse, 84.2) → §85 (build C1) → §86 (0.69) → §83 (his lean correction) →
§80 (S1-1, APK 0.67) → §79 (0.66, the alert watcher W; what is owed on glass) → **`REPLAN.md`** (§17 the order of work
as corrected; §14 his rulings) → **`CONTRACT.md`** (before any window or interface work) → `FIRMWARE.md` / `FORK.md`
for firmware → earlier by number (§65 Phase 2 and the protocol, §48 the fork). Firmware facts: `CLAIMS.md` →
`research/fork-reads-2026-09-13.md`.

## Where we are (2026-11-19)

- **The glasses run the fork's C1 build since 2026-11-18** (pin `0dab2154…`, `HANDOFF.md` §88; the image before it
  `48172b62…`). **The C1 ladder is at stage 3 since 15:43 on his word** (§91.3: ZWRITE, the 256 KiB cache, ENGINE;
  flags 0x2c; "enable all the stuff … asap" — one stage per soak is dropped). The first 19 minutes: no fault, no
  refusal, arena 13 at 130 KiB free with the 256 KiB cache. **He watched the engine's four test programs at 16:20
  (`probe motion=list|popover|recede|flip`): "They all looked perfect!"** (§91.7: every step drawn at period 34 ms).
- **The glasses run the fork's C2 build since 2026-11-19** (pin `b3f04453…`, `HANDOFF.md` §94: LEFT 11:35, RIGHT
  11:56; the image before it is C1's `0dab2154…`, kept in `fws/`). **The ladder is at stage 4 since 12:22 on his word**
  (flags 0x3c: DRAW2, ZWRITE, ENGINE, BIND; 256 KiB). The first trial (§94): local scroll and tap worked — 37 fires,
  32 COMMITs, no fault or reset — and showed three defects in the phone's staging of Main (it stopped fitting after
  app visits and Main fell back to the old scroll; a font change dropped the staging; the period had no margin).
  **Their fixes are APK 0.78, in build** (`private/build072/C2-decisions.md` part AU).
- **His phone runs APK 0.77 since 11-19 11:17 and the PC service since 06:10** (§92.12, §93.1: on the C1 image it
  armed stage 3 as before, the first session at 160 KiB, no fault). 0.72–0.74 carried the test motions he judged (§92.1); 0.77 = C2's whole phone half, which
  sends nothing of C2 below ladder stage 4 on a build that reports bit 10 (four readers traced it).
- **Main's scroll, his verdict on the glasses (§92.1):** only the whole list moving together looked good — "what
  moves, moves together" binds every later motion; the period is chosen so a step fits (P 42 ticks, T 5 at height
  352).
- **The session's cache size:** 256 KiB at stage 2 and above; since 0.77 the last session's size is kept across a
  restart of the phone's process (§92.6), so an install no longer drops a 256 KiB session to 160 (the first session
  after 0.77's own install still asks 160; the first start after the next night in the case asks 256).
- **Reddit in Feed is stale since 11-17 11:45** (§91.5): every subreddit feed answers "Not Found" to the PC, which is
  the only fetcher since 0.70. Open.
- No browser of his is approved yet; he has not said he has used the pages, or looked at Feed and Tmux on 0.70.
- Sessions run inside the memory-capped cgroup (`CLAUDE.md`, "Running builds on this machine"; §82); his lean
  correction of 11-17 governs what is built (§83).

| build | where | state |
|---|---|---|
| L1 (the cleanup; S1-2 inside it) + Q | `main` | deployed (0.69) |
| C1 (motion programs) | fork `c1` `561f8b7` (pin `0dab2154…`); its Damage half on `main` | **flashed 11-18; stage 3 in force since 15:43** |
| S1-3 (Feed, Tmux) + C1-P (the stored stage) | `main` | **deployed: APK 0.70 installed 11-18 14:29** |
| C1-Q (the session's cache size) | `main` | **deployed: APK 0.71 installed 11-18 16:18** |
| S1-4 (Music) | the old spec + his five answers ("All recommendations", 11-19); the lean spec in work (`private/build078/`) | not built |
| RA-1 (the shell loop's liveness supervisor, R-a) | `wip/078-ra` | in build since 11-19; nothing deployed |
| C2's staged records split across flushes | `wip/078-split` | in build; waits for the trial |
| the test motions (Main's rows staged, `hold`, `mainseq`) | `main` | deployed: APK 0.72 (18:54) and 0.74 (21:55) on 11-18; judged by him (§92.1) |
| C2 (the second flash: local input) | fork `c2` `5c728bb` (pin `b3f04453…`); its phone half on `main` | **flashed 11-19, stage 4 armed 12:22; first trial done (§94); the trial's fixes in build as APK 0.78** |

The counts of APK 0.77's tree (simulator; §92.11): core 230 classes / 1,762 tests · desktop 13 classes / 52 ·
`--selfcheck` 486 PASS ×2 · snapshot 94 renders + 66 in `phone/`, 0 px outside the mask · `--local-check` 56 cases ·
the 66 conformance vectors in both forms · node 200 · lint 0 · wiring 79 ok. The fork at `c2`: `tools/verify.py` 38
PASS, the self-test 78 of 78, host tests 310, its sweep 279 caught of 318. The phone half's mutation list:
`research/mutations-c2.json`, 165 rows — the whole list swept on the final tree on 11-19: 161 caught, 4 with no pin
by their own statement (three seen only at the simulator's own tests or expected not to fail, one whose cost is one
extra read), none not caught, none not judged (§92.11).
The judge's standing base is `private/build070/base-070/` (mask `base-070-mask/`). The briefs, rulings and reports of
every stage are in the gitignored `private/build068` … `build072` (C2: `build072/C2-decisions.md`, parts A–AS),
`lean`, `c1-design`, `c2-design`;
**`private/RESUME.md` is the working handoff**.

## Next

The order is `REPLAN.md` §17.3. **Every run: its size and what he gets for it first, both meters read before and
after** (§17.6). New firmware features are armed and tested at once on his go, not over days (§91.3).
1. **C2's trial goes on with APK 0.78** (§94's three fixes; then his second look at Main), and what the trial
   still owes: the feel of Main's scroll and tap; fields 16, 33, 34, 37, 38; `tapdrop`, `snapback`; the 114
   and 115 gaps; LEFT's first step on 240 fps video; a gesture's WAIT right after a session start (each staged strip
   is one flush of about 20 KB — two windows of about 1.3 s, modeled; the cure, a record split across flushes, is the
   first build after if it is felt). The `mainseq` probe leaves in the first build after stage 4 is armed.
   **Recommended to him, not built:** a liveness supervisor for the phone's shell loop (§92.10).
2. **C1's numbers as he uses the glasses** (the 114 gap counts, field 33 under a working shell, the round trip's
   p90) feed C2's constants (`FIRMWARE.md` §6.9).
3. **After C2: the motion kit's shell part** (`REPLAN.md` §17.3 item 6), before S2.
4. **Reddit in Feed** (§91.5): which network path still gets a feed; then the fix.
5. **S1-4** (Music: his five answers are in, `REPLAN.md` §17.3 item 7; the lean spec, then the build), **S1-5** (the alerts onto the notice record; the phone's caches), **R-b**
   (one health view on every surface, §17.4), then §16.1's order. His answer is owed on moving the motion kit's shell
   part ahead of S2.
6. **The first days of 0.66 and 0.67** (§79.9, §80.10) are still unread on his devices.

**What waits on him:** the 0.70 sitting (the PC deploy and his install together, `DAILY.md`); each C1 stage's arming;
**his go per lens for C2's flash** (prepared: §93.4). Also his, whenever he likes: the lead's
calls in §84.2 and S1-3's four in §89.5 (each reversible); a hardware watchdog for the PC (R-c); the fork's push and
its records' move to `c1`; "delete" for the stray private page (§79.8); which alerts are urgent; deleting the phone's
old `feed/` folder after 0.70.

**His calls** (list kept here once): installing an APK; granting location "Allow all the time"; every
`tools/glassdrive.py` line; the wear-detection switch; a phone-side Silent Mode write; any flash; rotating the token;
the fork's push; the three firmware calls at their flashes (`FIRMWARE.md`, the note above §5). Also his: the lead's
calls at §84.2, §80's head and §79's head; the removal of the private claude.ai page (§79.8); the TV check (`REPLAN.md`
§15 C1); the helper's token; re-adding the appliances to the hub; §77.10 items 2–10, §76.10's open calls; the Feed
tapper.

**Open reads, by the old item numbers** (cited elsewhere). **1** the status cell (§69): his eye on legibility at 140 px;
the first real notice. **2** page staging's second read (§66.1): arm PRESENTED (`probe:flags=0x8005`, his word; in
force 0x4), open a book, read `stage`, `count`, `page staging:`, the notch WAIT by shape, the worker µs of a staged notch,
the atlas churn; his eye: a notch that lands whole, no stale rows, no lens disagreement. **3** T2 (§79.5 read part of
it): the ack curve on 2M over a painted workday; cacheMiss (exit `proof` at zero); battery %/h on 2M against 7.4–7.8
(0.64: 8.0); in-case reboots against §65 (none in 25.3 days); **the §62.7 retention check** (into the case a minute and
out: is the first frame whole?). **10** rest mode (§75.4; read §74 first): build 2 waits on build 1's reads and **the
timed rest hour** (his; `DAILY.md`); `DESIGN.md` §0's "wear / unwear not wanted" carries the rest-mode exception of his
09-21 ruling — his to confirm. **12** the estimator's edges (65 / 35 ms/KB) held for days of regime notes; his eye on a
Reader page. **13** a Silent Mode the phone was never told of (§73 addendum, `CLAIMS.md`): on this build it can go
unreported (I); open: (a) the first press after the case at home; (b) why the push was missing (§74 A2; a probe, his
word); (c) a second cue — not built (§79.7); 0.66's `cue` notes read field 14 in unasked blocks and every sid-0x0d frame
while silent. (d) The slow link outside is CLOSED (§75.1). Closed: 4, 5–7, 9 (→ C1's and W's riders), 11. **Without
him:** the seam race (`SeamSessionTest.kt:82`); the RTC getter; the TinyFrame role byte; a capture with the ring asleep.
Parked: temple bursts (§54.8).

## Measured on glass (grade M)

First ACK per gesture, the phone's journal. **The ack is the link's share of the wait** (§64.4) and precedes the panel
refresh (§48.1, verified): price a window against the report's `first wait ms` (a notch: 230 / 1,004 ms median / p90;
ack 75 / 523). From 0.66 `input->done ms` beside it adds the wait in the shell behind a full window.

| gesture | first flush | first ack | source |
|---|---|---:|---|
| window notch, 0.40 → 0.42 (2026-09-07 → 12, n=5,257) | 437 B / 1.2 KB / 3.7 KB (median / p75 / p90) | 105 / 204 / 522 ms | §48.1 |
| Main notch, 0.40 | 716 B / 1.9 KB | 117 / 247 ms | §42.0 |
| whole window gesture (burst) | — | 342 ms median, 3.0 s p90 | §48.1 |
| window notch, 0.40 → 0.44 bursts (to 2026-09-13, n=10,367) | 391 B / 3.2 KB (median / p90) | 89 / 494 ms | §49.1 |
| Main notch, 0.40 → 0.44 bursts (n=7,346) | 106 B / 228 B | 68 / 96 ms | §49.1 |
| WINDOW first wait, 0.64 (11-12, n=509) / 0.65 (n=219); seven ≥ 0.8 s on 0.65, none on 0.64 | — | wait 108 / 293 · 128 / 395 ms | §79.5 |
| the bounded atlas skip (2026-09-15): a Bluetooth-toggle rebuild kept 9 fonts and 16 icons (60 KB), gaps 63.1 / 63.0 s inside the 80 s window | 0 B re-uploaded (was ~60 KB, ~7 s of link) | — | §54.1 |
| the link (M0.3, 2026-09-14 captures): two full 247 B packets per served connection event on LEFT, every 60 ms in two of three sessions | — | 8.2 KB/s while the phone has more queued | §54.4, `research/perevent.py` |
| Phase 2's first hour on LE 2M (2026-09-16): partial refresh 2.8 ms median vs full 4.4 (n=162/28); break-even ≈40 rows, `hintMaxRows` 240 too high | 0.5–1.5 KB / 1.5–3 KB / 3–6 KB | 98 / 193 / 276 ms (n=65/9/76) | §65 |
| the link per KB by place (2026-11-10): 60–72 ms/KB at work against 32–38 at home, floor 55–58 ms (n=21 / 10) — I: place, pocket and music not separated | — | — | §73's last block, §75.1 |

**To price with** — the PHONE's path, isolated flushes, LE 1M (§33.1, §35.2): under 100 B acks in ~60 ms, each KB adds
~140 ms, the tail is pixel bytes (§48.1); phone CPU per flush 17 ms median / 66 p90 (§42.0). Median ack < 500 B 72–77
ms · 0.5–1.5 KB 203–275 · 1.5–3 KB 329–358 · 3–6 KB 543–667 · 6 KB + 1,036–1,140 (p90 221–231 · 411–815 · 495–851 ·
708–838 · 1,295–1,543). **On Phase 2 (LE 2M; the first hour, n=270; §65): < 500 B 71 · 0.5–1.5 KB 98 · 1.5–3 KB 193 ·
3–6 KB 276 ms median** — the per-KB term about halved, the floor unchanged. `overview.md` §5.2's `ms ≈ 60 + bytes/50`
is PC-direct only — price nothing with it.

## 🔴 Still unmeasured on glass (row numbers are cited elsewhere)

| # | what | why it matters |
|---|---|---|
| 1 | **Safe area** — draw a border, shrink until fully visible, store it | `DESIGN.md` §2.2b: 480 vs 288 is a *calibrated setting* |
| 2 | Ring **fast-spin coalescing + event-rate ceiling** — a report under 100 ring ticks after the last is dropped (§74); the tick's unit and the daily effect are not read | the focus model's limits; local input (C2) |
| 3 | **Comfortable disparity** — ramp 0/4/8/12/16 | stock FAR does NOT stack (§48.1, verified); the ramp: `FORK.md` T3/T4 |
| 4 | **The rect budget of 5** (graded I) | derived from `cfw_diag()`, never observed; failure is silent |
| 5 | **A two-arm BTSnoop capture spanning a drop, and one with the ring asleep** | one drop from the radio's side (§42.2); the 60 ms cadence |
| 7 | **msgId-255 behaviour under CFW** | it ends the link on stock |
| 8 | **Chrome legibility** at the real faces on glass | renders cannot answer it |
| 9 | **WEA/CMAS visibility to a normal Android app** | `DESIGN.md` §4.5's emergency promise rides on it |
| 10 | **Connected RSSI** on glass | the status bar's link cell |
| 15 | **Is the sid-0x01 prelude required** before CREATE? (U) — a CREATE is dropped while app 0xE0 is still registered (§74); what clears it is unread | three eaten CREATEs per cold start ≈ 6 s (§34.3) |
| 21 | **Temple long-press accident rate** (gloves) | §1.2's bare-long-press no-op guards it |
| 24 | **Does the firmware enter Silent Mode by itself** (wear detection, idle)? | the journal will say |
| 25 | **The arm rebuilds' cause** (§42.2) — REBOOTS (§65: five on 09-16, all in the case); none in 25.3 days to 11-13 (§79.5) | the case's top-up cycle (I, §70) |
| 26 | **The wake loop's cause** (§42.3) | the `keeper: start failed: …` notes name it |
| 28 | **True user-perceived latency** (ring press → visible change; the ack is a lower bound) | `FORK.md` M0.2 |
| — | **0.66's in-case start and wake, Android's position rate; `Go there` on glass; 0.68 on his devices: the desk browser approving itself, the Reader page following, Torrents' and Files' pages, notices across surfaces; 0.70: Feed's and Tmux's pages, the terminal in Chromium, typing over ssh** | §79.9, §80.10, §84, §89 |
| — | **Cheap probes nobody has run:** the CFW logger service (sid 0x0F — M0.5); the file-export service (sid 198/199) | `NOT_SUPPORT` is a safe answer |

## Standing rules (pointers)

The rules are `CLAUDE.md`'s. Not there: `handleMs` in the journal INCLUDES the assemble (§35.1); in a live walk never
scroll in Music's root, and the tmux pane's SECOND tap is the keys list (a third sends a key); the report's per-gesture
section judges every window (§42). **Not to be undone:** `IMPLEMENTATION.md` "Review hardening" (builds W's, S1-1's
and L1's lists included) and its cache's §73 list; `FORK.md` Phase 2's envelope (a)–(f); the phone's and the PC's own firmware
model is told the build's contract (§62.3 item 1); the atlas recovers from the lapse itself, never from a status string
(§64.2 item 3); op 5's success from op 5's OWN reply, never the sticky status register (§63.3 item 3); only statuses 2
and 3 answer a flag arming (§62.3 item 2); a mode-24 partial refresh only while the panel shows the whole previous frame
(§62.1); `make_vectors.py`'s `arm` takes the lease and arms DRAW2 BEFORE the keyframe (§63.4). §73's traps: a pin that
WAITS lets an unrelated cue do the mutated code's job — decide by frames; the desktop has no kerning; **a `--transport
sim` instance is NOT isolated** (the service's ports, the real qBittorrent, music database, tmux, files): beside the
live service only by §73's recipe. §79.8's: a wait on a side effect written under a lock does not order a value decided
after it. §80.9's: a judge's base carries the command behind its shelf hash; a clock-relative age is a declared live
cell. §89.6's: a judge's world is pinned whole — a scene that reaches a real provider reads the machine. **The repository is public:** nothing of his home (the hub's address, entity ids, rules, devices, positions) or
his shelf's titles in a tracked file; tests use made-up ids such as `sensor.fresh_tank`. **Upstream** (§58, §67–§68, §74): **D1 stands, the fork stays on 2.2.6.10**;
**never update the R1 ring past 2.2.x while the glasses are on 2.2.6.10; never move `reference/g2flash` (a pull breaks
`verify_cfw.py`).**

## Suspended work (each resumes at its step in `REPLAN.md`)

- **On-glass verdicts owed**, taken once at K's stops (§16.1: K2 Files, Torrents; K3 Music, Tmux, Feed; K4 Games):
  Torrents + the keyboard; Files; Games (`HOLDEM.md` §17.4); Music (the grants, `DAILY.md`); Tmux. **Feed polish**
  (§43.6, `FEED.md` §8.3) → K3; never re-open `FEED.md` §1's verdicts (verdict 15 amended by `CONTRACT.md` §14 (a) row
  1). `POPOVER.md`: a spec, built in K1. **Next window:** Reminders, once for every interface (`REPLAN.md` §14.4, §16.5
  #3); then Mail (#6), SMS (#7), Info (#8), Notices (#9) (`EXPLOSION.md` §20); FF1 (§10.9) first if he says so.
- The Reader legacy-offsets dual-write · the icon-quality pass · the unused `Profiler` Global row · **watch-items:**
  the left-lens seam residue after a handover, the ~20 s seam silent-loss window, the media endpoint logging nothing on
  success, `slappy` (tmux host) offline. **Open design:** where system-state detail lives (Info, `EXPLOSION.md` §9; the PC's figures on the TV,
  `BASTION.md` T-33); undesigned windows inherit Clear Sans; B612 never a default.

## System changes made for this project

- Fonts: `/etc/portage/package.accept_keywords/damage-fonts` (`~amd64`, eight packages), 56 `media-fonts/*`; locked
  faces Clear Sans, Fira Sans, Alegreya, JetBrains Mono (`design/fonts.json`; `lint.py` checks coverage against them).
  `net-p2p/qbittorrent-5.1.4` USE `webui` (`/etc/portage/package.use/60-qbittorrent`), OpenRC `qbittorrent`.
- `/etc/init.d/damage` (§44.1): after `postgresql-17` / `qdrant` / `qbittorrent`, `~/.local/bin` on PATH,
  `--enable-native-access=ALL-UNNAMED`; since §72 `-XX:+ExitOnOutOfMemoryError -Xmx2g` (respawned after 5 s, no limit),
  `retry="SIGTERM/30/SIGKILL/5"`; the original is `~/.damage/backup/init.d-damage.pre-72` (never in `/etc/init.d`).
- `/etc/local.d/tailscale-bypass.start` (§47): Tailscale's marked traffic (fwmark 0x80000) via the LAN gateway (table
  100, pref 5200), not the VPN; after a change `sudo rc-service tailscale restart`. `~/damage-cfw` (`FORK.md` §10 and its
  remotes; `origin` = upstream, fetch only, never pushed to) is from `reference/g2flash` at `a5d1c31`, branch `damage`;
  its `g2_2.2.6.10.bin` links to `fws/2.2.6.10/e287…bin`. `~/.local/bin/yt-dlp` updates itself (`-U`).

## How to resume

**`aphone` does not resolve on beardos: use the phone's Tailscale address (`tailscale status | grep aphone`). Every
`glassdrive.py` line drives or writes to his glasses through his phone: only when he asked or is there to watch. The
`curl` reads are read-only.** "Scanning forever" while the OS shows Connected: toggle the phone's Bluetooth. Gates:
`CLAUDE.md`; the ops crib, the watcher, the web listener and the drive lines: `DAILY.md`. Kept builds: `~/.damage/backup/` (the 0.67 and 0.68 jars and
APKs; the data files as they stood before 0.68's first start in `pre-068/`; from the 0.70 sitting the 0.69 ones and
`pre-070/`).

```
ps -C java -o pid,lstart,rss,args | grep damage.jar   # the service's JVM — never `pgrep -f 'java.*damage.jar'` (it matches your shell)
TOKEN=$(python3 -c "import json;print(json.load(open('/home/user/.damage/config.json'))['token'])")
curl -s "http://aphone:7403/journal?token=$TOKEN&tail=2000000" | python3 tools/journal_report.py -   # --since DATE, --glasslog; keep &tail=
python3 tools/journal_pull.py --live                  # 0.64+: the archives and the live file → ~/.damage/phone-journal/
grep -E ' (bastion|presence|reach|record|web|migrate): ' ~/.damage/damage.log | tail -40   # 0.66+ the watcher, presence, refusals; 0.67+ the record, the web listener, the migration
java -jar ~/.damage/damage.jar --devices              # 0.67+: approved browsers and waiting codes (asks the running service)
python3 firmware/fuzz_vectors.py OUT 40 25          # §64's fuzz; its header gives the C's and the simulator's steps
(cd ~/damage-cfw && python3 tools/mutate.py --list)   # the refusal guards and which gates prove them (the whole sweep ~12 min)
python3 research/perevent.py  ·  python3 research/flushdecomp.py   # packets per event (§54.4) · flush decompression (§68)
```
