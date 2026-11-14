# Where we are, and what to do next

**The entry point for a fresh session**: what is true now, what is next, where the records are; history lives in
`HANDOFF.md`. Read `CLAUDE.md` → this file → **`HANDOFF.md` §80** (S1-1, APK 0.67; the lead's calls he may reverse
at its head) → **§79** (0.66, the alert watcher W; what is owed on glass) → **`REPLAN.md`** (§14 his rulings, §15.1
his answers, §16 the sized plan) → **`CONTRACT.md`** (before any S1 work) → `FIRMWARE.md` / `FORK.md` for firmware →
§78 (step A) → §77 (0.65) → §76 (0.64) → §75 → §74 (upstream) → §73 (its addendum: the two silent modes) → earlier by
number (§65 Phase 2 and the protocol, §48 the fork). Firmware facts: `CLAIMS.md` → `research/fork-reads-2026-09-13.md`.

## Where we are (2026-11-14)

**S1-1 (APK 0.67, versionCode 67) is built and verified on the local branch `wip/067` — NOT merged, NOT staged, NOT
deployed; nothing of it has run on his glasses or phone, and nobody has looked at its pages in a browser** (§80). The PC
owns Reader's state (a record behind an operation log), the phone keeps a working copy with an on-disk queue that still
answers a gesture's first flush, a web listener on the PC (port 7405) serves a browser interface, and Reader is the
first window converted. The glasses are pixel-identical except one change he will feel: a place read on another device
never moves the open page; `Go there` is offered (§80, call 1). **APK 0.66 (`90342fe`) is STAGED since 11-13 22:54 —
installing is his; the PC service runs `e955a98`** (§79.12). **The order of work is `REPLAN.md` §16:** W deployed,
S1-1 built, S1-2 with S1-3 next.

| what | state | grade, source |
|---|---|---|
| glasses | the fork's Phase 2 build on both lenses since 2026-09-16: pin `48172b62…` = Phase 1 + modes 17–24, op 5, status 3–5, fields 23–26, the partial path, the three link edits; 32 sites; reports `2.2.6.10` — detect by `EVENCFW/` and DamageCaps (`contract 2 features 0x7f`), never the version; archived with its logs in `fws/2.2.6.10-cfw-48172b62/`; up 25.3 days at the 11-13 journal read (M). **Rollback:** Phase 1's `fws/2.2.6.10-cfw-c5e4f8b7/` (a5d1c31's `…-d4054ab1/` and stock behind it) | M, §65, §79.5 |
| phone | **APK 0.65** (`b833713`) installed 11-12 20:59:33 as of the last journal read; 0.66 staged. **A build is read from the journal's `process` note, never inferred** — he installs from the setup page without saying so | M, §79.5 |
| PC service | OpenRC `damage` runs `e955a98` since 11-13 23:55:32 — data host and the bus watcher (reads the hub every 10 s, read-only; 17 rules, none urgent, the four away-guarded ones held back; the nightly copy at 03:30). It never drives the glasses by itself (PC-direct BLE is a manual choice). `damage.phone` read `home` at the deploy; `away` not yet read | M, §79.12 |
| S1-1 | `wip/067` from `4ded994` (= `main`): `06948cc` … `e54b40c`, the records and the version commit; the whole battery green | simulator and PC, §80.5 |
| fork | `~/damage-cfw` at `f20bac9` plus the local commit `f9ba7c6` (host tooling, pin unchanged), not pushed — his push | as of §77.12 |

- **If 0.66 or 0.67 misbehaves:** Global `Cached text` off is the switch for the drawing path; the watcher's notices
  have their own rows. Going back is NOT a reinstall (a lower versionCode is refused): an uninstall (it clears the app's
  data and journal — `journal_pull.py --live` first), a higher versionCode or an adb downgrade — his call. **Never
  `:phone:stageApk` unless a build is meant to be staged.** After any install PRESENTED wants arming again (his word).
- **A journal tail with no flushes = the shell asleep in the firmware's Silent Mode, not a fault** (the both-temple
  press, worn; the case stays home). From 0.66 a start in the case rests (no CREATE, no notice); the push off rebuilds.
- **The current counts, S1-1's end gate (simulator and PC; §80.5):** core 1,355 in 184 classes (one background command,
  15 min; one plain command is past a ten-minute tool limit) · desktop 45 · `--selfcheck` 484 PASS lines ×3 (0.66's
  "485" counted the summary line) · `--snapshot` 58 renders + 11 in `phone/`, 0 px outside the mask in both
  arrangements · node 31 · lint 0 (37 + 234) · wiring 51 ok · tool self-tests 45/23/26/39/17 (mutate_core /
  journal_report / journal_pull / check_apk_wiring / snapdiff) · 15 mutation lists (`80-s1-1` 181: 176 caught, 5 NO PIN
  declared; `79-watcher` 114) · 34 vectors · 73 books, 2580/2604 images, every document equal to today's text; music,
  games, feed pass. The known rate misses: §54.5, §54.7, §49.6, §61, §66 (Feed alone 8 of 10: measure like with like),
  §76.7; §79.12's Games miss is explained and pinned (§80.6); `WorkingCopyTest.aRetryAndTheGaps…` (5 of 20
  alone) is closed by R3.1 (§80.8: 30 of 30).

## Next

1. **The lead:** §80.8's NOTEs N1–N7 and its unruled Found items are its to rule; then the merge of `wip/067`, the push,
   the PC deploy first, staging 0.67 (§80.13).
2. **Adam's first sitting of 0.67** (below): the PC deploy and his install together (`CONTRACT.md` §9.7 item 2).
3. **The first days of 0.66** (§79.9, read-only; 0.66 or later on the phone before the tick wrap of 2026-12-06): the
   in-case start; **`damage.phone` read `home`, not yet `away` — a day of his with both, then the four away-guarded
   rules go in**; Android's position rate; the channels, Acknowledge, `Watcher`; `heap3`; `input->done ms`. Still owed:
   the Music time tick's `handlerMs` like for like (Global `Cached text` off, then on, Music playing — his switch).
4. **Then S1-2 with S1-3** (deploy 2, `REPLAN.md` §16.1), re-priced from S1-1's measured size (§80.11).

## The first sitting of 0.67 (his; the install notes)

- **The PC first** (the lead's `./gradlew :desktop:stageJar && sudo rc-service damage restart`). Its first start writes
  into `~/.damage` `record.json`, `oplog.jsonl`, `record.seq`, `reader-index.json` (after the first walk of the shelf,
  ≈12 s of CPU, modeled) and `local.secret` (mode 600); `devices.json` comes with the first approval. Port 7405 opens
  behind the reach gate. Journal notes `record`, `migrate`, `web`.
- **Between the PC deploy and his install, Reader's places do not cross between the phone and the PC** (the new PC
  ignores the old sync keys); the install's migration then judges them: newer wins between the phone's and the PC's old
  records, the loser said once. A book read in the BROWSER between the two keeps the browser's place (a place written
  after the conversion is never replaced by an older build's record); the phone's is said once and offered with `Go
  there`.
- **His install**; then the `migrate` notes on both hosts and his current book's place unchanged on the glasses.
- **His desk browser:** `http://beardos:7405/` shows a code; `java -jar ~/.damage/damage.jar --devices` lists it, `…
  --approve CODE full` approves it (`DAILY.md`). Then the shelf and a book (chapters, emphasis, links, pictures); the
  place both ways (read at the desk → the glasses' next Reader entry lands there; read on the glasses → the page offers
  `Go there`); every visual item §80.10 lists.
- **The phone's browser** approved from the desk's devices page; **the PC out of reach once** (the phone reads on, the
  queue drains on return); one glassdrive pass on Reader if he asks.
- **The way back:** the previous jar and APK are in `~/.damage/backup/`; an older build ignores the new files and reads
  its own state (Reader keeps writing today's local keys); places read on it are judged at the next upgrade (R1 1.3).

**His calls** (list kept here once): installing an APK; granting location "Allow all the time"; every
`tools/glassdrive.py` line; the wear-detection switch; a phone-side Silent Mode write; any flash; rotating the token;
the fork's push; the three firmware calls at their flashes (`FIRMWARE.md`, the note above §5). Also his: the lead's
calls at §80's head and §79's head; the removal of the private claude.ai page (§79.8); the TV check (`REPLAN.md` §15
C1); the helper's token; re-adding the appliances to the hub; §77.10 items 2–10, §76.10's open calls; the Feed tapper.

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
| — | **0.66's in-case start and wake, `damage.phone` away, Android's position rate; 0.67's migration on his stores, `Go there` on glass, the pages in a browser** | §79.9, §80.10 |
| — | **Cheap probes nobody has run:** the CFW logger service (sid 0x0F — M0.5); the file-export service (sid 198/199) | `NOT_SUPPORT` is a safe answer |

## Standing rules (pointers)

The rules are `CLAUDE.md`'s. Not there: `handleMs` in the journal INCLUDES the assemble (§35.1); in a live walk never
scroll in Music's root, and the tmux pane's SECOND tap is the keys list (a third sends a key); the report's per-gesture
section judges every window (§42). **Not to be undone:** `IMPLEMENTATION.md` "Review hardening" (builds W's and S1-1's
lists included) and its cache's §73 list; `FORK.md` Phase 2's envelope (a)–(f); the phone's and the PC's own firmware
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
