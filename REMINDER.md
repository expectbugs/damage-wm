# Where we are, and what to do next

**The entry point for a fresh session**: what is true now, what is next, where the records are; history lives in
`HANDOFF.md`. Read `CLAUDE.md` → this file → **`HANDOFF.md` §101** (stage 5 on his glasses; APK 0.84 staged) →
**§100** (APK 0.83: the scroll rules, Main's scroll effects, the notice on the engine and the transitions in one
build; the ring list built and read once) → **§99** (C3 flashed on both lenses, the first sessions and the stage-4
trial) → **§98** (C3's final candidate and its flash-readiness review; the transitions; his fast-scroll session read
against the rules: late echoes, not lost reports) → **§97** (APK 0.82, his look at 0.81, C3's fork package with its
model and vectors, Music's first half complete) → earlier by number (§84.2 and §89.5 the lead's calls he may reverse,
§83 his lean correction, §65 Phase 2 and the protocol, §48 the fork) → **`REPLAN.md`** (§17 the order of work as
corrected; §14 his rulings) → **`CONTRACT.md`** (before any window or interface work) → `FIRMWARE.md` / `FORK.md`
for firmware. Firmware facts: `CLAIMS.md` → `research/fork-reads-2026-09-13.md`.

## Where we are

- **The glasses run the fork's C3 build at ladder stage 5.** C3 (pin `58741c4a…`) is on both lenses since
  2026-11-20 12:33 (`HANDOFF.md` §99); the rollback is C2's `b3f04453…`, kept in `fws/`. Stage 5 (the transitions)
  is armed since 2026-11-21 12:41 on his word (§101.1). The way back from stage 5 without a session: `python3
  tools/ladder.py 4` (`DAILY.md`).
- **His phone runs APK 0.83; 0.84 is staged (11-22, `main` `f9ddf1b4`) and the PC runs it** (§101): the ring list
  for Main's wrap, the fast-path fit (Depth 19 steps a notch), the transitions' end rule counted from RIGHT's own
  report, the mirror's cheaper loops, `Animations` as one row per tier with sub menus, Matrix as rain that wipes,
  Warp's and Flip's fit raised to the glass, Crush and Flip as scroll effects, the wheel's stage-5 margins. Its new
  effects (Crush, Flip, the new Matrix, Warp and Flip at the raised fit) have their 34 ship vectors filled by the
  fork's C, and the model agrees on all 246 files (`main` `d4eeaaba`; 0.84's product code is unchanged by it).
  **Told to him at the staging, and still true:** a scroll effect stepped through several values at once comes
  back as the first for a moment; the wheel is still phone-drawn.
- **In work, on branches** (nothing new goes onto his devices before he installs 0.84): `wip/084-x3b` (that scroll
  effect's fix; Encode's plan computed once) and `wip/084-k12s1` (Main on one strip, per-row band slots), stopped
  at the week's usage on 11-22 and resumed on 11-23; Matrix's fix stage (Warp and Flip to fill the tier's length,
  the rain's plane against Global `Depth`) — its second and last general pass found nothing that stops it; the
  wheel's spec amendment for DW-1.
- **Owed before more is built on them** (§101.9): the focused reads of Crush's and Flip's fix stages; the four
  tests whose rigs decide on a race; the APK's `release` variant tried with him there (the phone runs the mirror's
  loops 70–93 times slower than the PC — the debug build is suspected, not measured); a sweep of the whole mutation
  list (`research/mutations-c2.json`) on the current tree, machine time only (the last whole sweep: 0.77's tree,
  §92.11).
- **Owed on glass from his earlier looks:** his answer to §97.1's question (in very fast scrolling, are the lenses
  a whole row apart, or the same row at different positions); at his next fast-scroll session the phone's `/log`
  pulled early (the late echoes' cause, §98.6, is inferred until a stock message's arrival is read there); C2's
  trial reads (§94–§97): the feel of Main's scroll and tap, fields 16, 33, 34, 37, 38, `tapdrop`, `snapback`, the
  114 and 115 gaps, LEFT's first step on 240 fps video; C1's numbers as he uses the glasses (the 114 gap counts,
  field 33 under a working shell, the round trip's p90) for C2's constants (`FIRMWARE.md` §6.9); of `FORK.md` §7's
  stage-5 checks, a page rebuild and a both-temple long press during a transition are in no record yet (§101.1
  read telemetry 40 and 41; no reset).
- **For his eye on 0.84** (each modeled): Warp and Flip play 3 even steps a half at his Select (they were about 4
  uneven ones); Matrix's drops gather for a few frames, hold one, then fall; in his fastest bursts (about 120 ms
  between notches) Crush's far rows jump about 23 px at each notch — the installed firmware starts every notch
  from the rest before it; at stage 5 the switcher's panel has a black margin of its depth. **Said at 0.83's
  staging for stage 5** (each modeled, none seen on glass): LEFT may lag RIGHT by more than a step; with `Popup ·
  Fade` or `Slide` checked a notice arriving right at a transition's end may cut LEFT's last step or two; Encode's
  boxes are black on light backgrounds, text in a face the glasses do not hold and the default clock's bars do not
  scramble, an Encode Select's first image comes about 69 ms later on a dense page; the period clamp (600 or 800 ms
  at 4 frames plays 500; 800 at 6 plays 750; 225 at 16 plays 250) and the Frames row showing the set count where a
  dense page plays fewer; Slide and Fall at 5 frames against Depth's 6, Fall's overshoot, the band travelling 64 px
  while rows travel 32; Warp, Flip and Explode centred on the larger height; a notice arriving within about 2 s
  after a fast burst can still double-move Main.
- **No browser of his is approved yet;** he has not said he has used the pages, or looked at Feed and Tmux on 0.70.
- **The counts** (simulator): on `main` `d4eeaaba` the whole core suite 2,090 / 0 and the conformance vectors 246
  files, 15,230 lens-steps, 0 disagreements. The last full battery, on 0.83's tree (`f81afd38`, §100.7): core 243
  classes / 2,032 tests, desktop 14 / 53; `--selfcheck` twice (486 checks at 0.82); `--motion-check` 8 + 3 + 28;
  `--local-check` 128 cases; the snapshot 94 + 66 scenes, 0 px outside the mask; node 200; lint 0; the mutation
  list 438 rows; the sequence fuzz 8,000 + 2,000 sequences, 0 failures (one declared class of one sequence).

## Open from the documents' audit (2026-11-23)

Where the code does not do what a ruled design or the contract says; each document says so at the place.
- **His to decide** (put to him 11-23): `Slide frames` and `Slide fill` still sit in Global beside `Animations`
  (they drive the phone-drawn slides); `DESIGN.md` §6.2's rule on what a transition may cost reads as general while
  the engine's effects exist. **Answered the same day** ("All your recommendations above are good"; `REPLAN.md`
  §17.3 item 10): of the four Global rows with no reader (`DESIGN.md` §4.2), `Profiler`, `Diag overlay` and `Head
  tracking` are removed and `Battery alert` is hidden until the battery cell's pulse at 20 % or below (§4.1, never
  driven; the lead would build it when the status cells go to the firmware) exists — S1-5's Settings stage does it.
- **The lead's to place:** `Quiet hours` holds only the notices raised on the PC (Torrents, Feed, Tmux, the
  watcher, "home unreachable") — it closes as Music, Games, Reader and the shell's own notices move (S1-4, S1-5) ·
  Tmux and Torrents do not dim their keys and actions while the PC is out of reach (`CONTRACT.md` §12, `REPLAN.md`
  §14.3 #2), a press is sent and fails — with S1-5's cross-window pieces unless he wants it sooner · `CONTRACT.md`
  §3.1's vocabulary against what the cores send (`min`; `availability` loading · waiting · away; no window sends
  `stale{since}` or `attention`) — ruled at S1's close · the moved-book alias (`CONTRACT.md` §5, §14 (b)) was never
  built: a moved book gets a new id — placed or dropped at S1's close · the switcher's centre title is cut with the
  drawn mark where the design says marquee, its icon is 56 px where §4.3 says 64, the notice's source icon is not
  drawn — with DW-1 and K1's notice.
- **Small, for whoever next touches the code:** the `mainseq` probe (refused at stages 4 and 5) · the comment in
  `LadderRecord.kt` that says stage 0..4 · the comment in the service's init script that describes the old standby.

## Next

The order is `REPLAN.md` §17.3 (every run sized first and both meters read, §17.6).
1. **The motion kit's shell part (K1) goes on, ahead of S2** (`REPLAN.md` §17.3 items 6 and 8): after 0.84, the
   stages in work above, then window lists (K1-2), the wheel bound (DW-1), Scramble and Matrix as scroll effects and
   scroll effects in other windows.
2. **S1-4** (Music: his five answers are in, `REPLAN.md` §17.3 item 7; half A built, reviewed and complete on
   `wip/s14-k`, §97.5, merged forward when a build slot frees; then his sitting), **S1-5** (his five answers of 11-23
   and its lean spec, `REPLAN.md` §17.3 item 10), **R-b** (one health view on every surface, §17.4), then §16.1's
   order. **Music's motion update** (his ask of 11-22, planned, no code: `MUSIC.md` §14) is placed at K3
   (`REPLAN.md` §17.3 item 9).
3. **Reddit in Feed** (§91.5, §100.9): stale since 11-17 11:45, and not his network — Reddit ended its public RSS
   feeds on 2026-11-13; measured 11-21 on both of the PC's network paths: HTTP 404. The service still asks every 15
   minutes and logs the refusal. His word on what replaces the source, then the change.

**What waits on him:** his install of APK 0.84; any change of the ladder's stage and any flash, per lens. Also his,
whenever he likes: S1-3's four calls in §89.5 (each reversible); a hardware watchdog for the PC (R-c); the fork's
records' move to `c1`; which alerts are urgent; deleting the phone's old `feed/` folder.

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
ack 75 / 523). `input->done ms` beside it adds the wait in the shell behind a full window.

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
- The Reader legacy-offsets dual-write · the icon-quality pass · **watch-items:**
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
  remotes; `origin` = upstream, fetch only, never pushed to) is from `reference/g2flash` at `a5d1c31`, branch `damage`
  (then one per build: `c1`, `c2`, `c3`); its `g2_2.2.6.10.bin` links to `fws/2.2.6.10/e287…bin`. `~/.local/bin/yt-dlp` updates itself (`-U`).

## How to resume

**`aphone` does not resolve on beardos: use the phone's Tailscale address (`tailscale status | grep aphone`). Every
`glassdrive.py` line drives or writes to his glasses through his phone: only when he asked or is there to watch. The
`curl` reads are read-only.** "Scanning forever" while the OS shows Connected: toggle the phone's Bluetooth. Gates:
`CLAUDE.md`; the ops crib, the watcher, the web listener and the drive lines: `DAILY.md`. Kept builds: `~/.damage/backup/`
(the jar and APK of each deploy since 0.62–0.63; the data files as they stood before 0.68's first start in `pre-068/`;
from the 0.70 sitting the 0.69 ones and `pre-070/`). **`private/RESUME.md` is the working handoff;** the briefs,
rulings and reports of every stage are in the gitignored `private/` folders (`build066` … `build084` — C2:
`build072/C2-decisions.md`, S1-4: `build078/`, K1: `build081/K1-spec.md`, S1-5: `build084/` —, `lean`, `c1-design`,
`c2-design`, `c3-design/C3-decisions.md`).

```
ps -C java -o pid,lstart,rss,args | grep damage.jar   # the service's JVM — never `pgrep -f 'java.*damage.jar'` (it matches your shell)
TOKEN=$(python3 -c "import json;print(json.load(open('/home/user/.damage/config.json'))['token'])")
curl -s "http://aphone:7403/journal?token=$TOKEN&tail=2000000" | python3 tools/journal_report.py -   # --since DATE, --glasslog; keep &tail=
python3 tools/journal_pull.py --live                  # the archives and the live file → ~/.damage/phone-journal/
grep -E ' (bastion|presence|reach|record|web|migrate): ' ~/.damage/damage.log | tail -40   # the watcher, presence, refusals, the record, the web listener, the migration
java -jar ~/.damage/damage.jar --devices              # approved browsers and waiting codes (asks the running service)
python3 firmware/fuzz_vectors.py OUT 40 25          # §64's fuzz; its header gives the C's and the simulator's steps
(cd ~/damage-cfw && python3 tools/mutate.py --list)   # the refusal guards and which gates prove them (the whole sweep ~12 min)
python3 research/perevent.py  ·  python3 research/flushdecomp.py   # packets per event (§54.4) · flush decompression (§68)
```
