# Where we are, and what to do next

**The entry point for a fresh session**: what is true now, what is next, where the records are; history lives in
`HANDOFF.md`. Read `CLAUDE.md` → this file → **`HANDOFF.md` §84** (the cleanup L0 and L1; the lead's calls he may
reverse, 84.2) → **§85** (build C1, a firmware candidate, not flashed; what the flash conversation needs) → §83 (his
lean correction) → **§80** (S1-1, APK 0.67) → **§79** (0.66, the alert watcher W; what is owed on glass) →
**`REPLAN.md`** (§17 the order of work as corrected; §14 his rulings) → **`CONTRACT.md`** (before any window or interface
work) → `FIRMWARE.md` / `FORK.md` for firmware → §78 (step A) → §77 (0.65) → §76 (0.64) → §75 → §74 (upstream) → §73
(its addendum: the two silent modes) → earlier by number (§65 Phase 2 and the protocol, §48 the fork). Firmware facts:
`CLAIMS.md` → `research/fork-reads-2026-09-13.md`.

## Where we are (2026-11-17)

**Two things happened that change how work resumes, and one build is done:**
1. **The PC stopped responding for five hours on 11-16** because a build stage's test process allocated without bound
   (`HANDOFF.md` §82). Sessions now run inside a memory-capped cgroup; the rules are `CLAUDE.md`, "Running builds on
   this machine". Check `cat /proc/self/cgroup` reads `/claude-work` before anything heavy.
2. **Adam corrected the direction on 11-17** (`HANDOFF.md` §83; `CLAUDE.md`, "What is worth building"; `REPLAN.md`
   §17): a clean single-user system on several surfaces, the latest real input wins, robust before defended, an agent
   swarm on real usage as the end. Read those three before proposing or building anything.
3. **The cleanup of what was written is built** (L0 the audit, L1 the build; `HANDOFF.md` §84): the record ordered by
   input time, the conflict machinery, migrations, mixed-build handling and the app layer's mutation lists out; the
   Files supervisor and the file-safety fixes, the record's log reopened by itself, the desk browser approving itself,
   the pages recovering by themselves in. S1-2 (notices, Torrents, Files) is finished through it. `CONTRACT.md`,
   `IMPLEMENTATION.md` and `DAILY.md` describe the tree as it now is.

**On his devices:** APK 0.67 is installed (seen in the PC's log on 11-15: the phone on the `app` lane, its migration
taken). The PC service runs `main` with the page fix of §80.14 (`58110b0`, since 11-15 16:42; restarted by the 11-16
reboot). The glasses run the fork's installed image (pin `48172b62…`). No browser of his is approved yet; he has not
said he has used the pages. TODO-LEAD: the PC deploy of 0.68, if the lead makes it, and its time.

**Built and waiting, all on LOCAL branches (nothing of it pushed, installed or flashed):**

| build | where | state |
|---|---|---|
| L1 (the cleanup; S1-2 inside it) | `wip/l1` `bd39307` + FIX2 (TODO-LEAD: its commit) | the gate's first run green (§84.6); FIX2 builds the last rulings; then merged with C1's Damage half and gated once as APK 0.68 (TODO-LEAD: the merge commit and the gate's counts) |
| C1 (the first flash's build: motion programs) | fork `c1` `561f8b7` (candidate pin `0dab2154…`); Damage `wip/068` `f72f67f` | built, two review passes and a focused read ruled, the fork and the simulator agree on 51 vectors and both fuzz sets (§85); **next: stage DM1, the Kotlin mutation sweep** (TODO-LEAD: its numbers); NOT flashed |
| S1-3 (Feed, Tmux) | pages only: `wip/070-web` `12ae299` (Feed page; Tmux page as a colour grid) | Kotlin not started and to be specified again, lean; the pages now allow inline styles, so the terminal emulator can run (the lead's call, §84.2) |
| S1-4 (Music) | a spec only | five questions are his (below) |
| C2 (the second flash) | its design challenge only | 3 MUST-FIX in the design; the narrow shape is preferred (17.3 item 5) |

The counts on the L1 tree, **before the last gate** (the gate's first run, `c4b563b`, simulator): core 198 classes /
1,413 tests · desktop 48 · `--selfcheck` 484 PASS ×3 · snapshot 67 renders + 31 in `phone/`, 0 px outside the mask ·
node 113 · lint 0 · wiring 67 ok. TODO-LEAD: the counts of the last gate on the merged 0.68 tree.
The briefs, rulings and reports of every stage are in the gitignored `private/build068` … `build071`, `lean`,
`c1-design`, `c2-design`; **`private/RESUME.md` is the working handoff** (what each branch holds, the exact next stage
of each build, how lanes are started safely).

## Next

The order is `REPLAN.md` §17.3; its item 0 (L0, L1) is done. **Every run: its size and what he gets for it first, both
meters read before and after** (`REPLAN.md` §17.6).
1. **C1 to its end** under the full protocol: stage DM1 (the Kotlin mutation sweep on the glasses' wire, and
   `FidRingStartTest`'s one miss settled), then C1's Damage half merged with L1 and the whole battery once on the
   merged tree as APK 0.68 (TODO-LEAD).
2. **Deploy 2 and his use of it** (Reader, Torrents, Files and notices on the web; C1's phone half inert until its
   flash): the PC deploy and his install together. The four held-back away rules are armed by the lead at that deploy,
   with a copy of his rule file kept beside it. Nothing more is stacked until he has used it.
3. **S1-3** (its pages exist; its Kotlin specified again, lean), then **S1-4** (Music: five questions are his), **S1-5**
   (the alerts onto the notice record), then §16.1's order.
4. **C2's design** goes on from its challenge with the narrow shape preferred.
5. **The first days of 0.66 and 0.67** (§79.9, §80.10) are still unread on his devices.

**What waits on him:** installing APK 0.68 (TODO-LEAD: its path, once staged); C1's flash (his in-the-moment go, the
dry run first, `HANDOFF.md` §85's checklist, after 0.68 has run for a day); Music's five questions (where the PC plays;
away with the output on the PC; a service restart while the PC plays; earbud taps while the PC output is chosen; what
the PC plays and how loud). Also his, whenever he likes: the lead's calls in §84.2 (each reversible); a hardware
watchdog for the PC (R-c); one restart of the right lens on today's firmware before C1's flash, to read whether
firmware state survives it; the fork's push; "delete" for the stray private page (§79.8); which alerts are urgent.

## The first sitting of 0.68 (his; the install notes)

- **The PC deploy** (TODO-LEAD: done or not, and when). Its first start rewrites `record.json` in the new shape (its
  items kept: the gate's upgrade check on a copy of his data found 20 of 20 equal), leaves `record.seq` unread, and
  writes one `migrate` note naming the Torrents and Files rows it took from their old records.
- **His install** of the APK; then his current book's place unchanged on the glasses, and Files' Sort, Hidden and
  Size on the glasses as he left them.
- **His desk browser:** `http://localhost:<webPort>/` on the PC approves itself at full, with no command (`DAILY.md`).
  Then the shelf and a book; the place both ways (read at the desk → the glasses' next Reader entry lands there; read
  on the glasses → the open page goes there by itself); Torrents and Files on the page; a notice dismissed on one
  surface leaves the others.
- **The phone's or the TV's browser:** opened at the PC's name or tailnet address with the web port, approved from the
  desk browser's Devices page.
- **The PC out of reach once** (the phone reads on, the queue drains on return; a place read on the glasses meanwhile
  does not overwrite a later one set at the desk).
- **The way back:** the previous jar and APK are in `~/.damage/backup/` (TODO-LEAD: confirm both are kept at the
  deploy). Downgrades are not built: 0.67's decoder requires record fields that 0.68 no longer writes (the audit's
  reading of 0.67's `RecordStore.decode`; how 0.67 starts on 0.68's files is unchecked), so a way back would also need
  the nightly copy's `record.json` from before the deploy. Read that before using it.

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
| — | **0.66's in-case start and wake, Android's position rate; `Go there` on glass; 0.68 on his devices: the desk browser approving itself, the Reader page following, Torrents' and Files' pages, notices across surfaces** | §79.9, §80.10, §84 |
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
cell. **The repository is public:** nothing of his home (the hub's address, entity ids, rules, devices, positions) or
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
`CLAUDE.md`; the ops crib, the watcher, the web listener and the drive lines: `DAILY.md`. Kept builds: `~/.damage/backup/`.

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
