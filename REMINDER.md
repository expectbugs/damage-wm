# Where we are, and what to do next

**The entry point for a fresh session**: what is true now, what comes next, where the records are. History lives
in `HANDOFF.md`; this file only points at it. Read `CLAUDE.md` → this file → **`FORK.md`** (the plan; §11 is the
progress log) → `FIRMWARE.md` (the contract) → **`HANDOFF.md` §49 (Phase 0 begun, the latest record)** → §48 (the fork
decided) → §47 → §46 → §44 → §43 → §42 → what they cite. For firmware facts: `CLAIMS.md` ("Firmware internals read
for the fork") → `research/fork-reads-2026-09-13.md` → the image itself through `research/fwread.py`.

## Where we are (2026-09-15, end of day)

- **🔴 The CFW fork and the Damage rebuild are the ONLY work (Adam, 2026-09-12; `HANDOFF.md` §48)** until `FORK.md`
  Phase 8 closes: no new windows, no Feed polish, no popover build outside the plan. `FORK.md` = the plan (§11 the
  log), `FIRMWARE.md` = the contract, `HANDOFF.md` §48–§58 = the records, `CLAIMS.md` = the firmware facts.
- **LIVE: the fork's Phase 1 build on both lenses since 2026-09-15 04:38 (`HANDOFF.md` §55)** — pin `c5e4f8b7…` =
  a5d1c31 + F1.1–F1.5 and the self-test (reports `2.2.6.10`; detect by `EVENCFW/` and the DamageCaps field, never
  the version); features `0x1f`, flags `0x8001` armed (PROBE + PRESENTED), CACHE_KEEP off; the rollback
  `fws/2.2.6.10-cfw-d4054ab1/`; provenance = the fork's `tools/verify.py` (`research/verify_cfw.py` pins the
  rollback). The phone APK drives (radio and shell); the OpenRC `damage` service is the data host and standby
  (`HANDOFF.md` §19, `DAILY.md`), still on 0.44's core; G2CC's server is retired (§44). **APK 0.49 installed**
  (2026-09-15 03:46: the Phase 1 keeper protocol, `present` records, the probes, the bounded atlas skip).
- **Phase 1 is done bar the soak (§55, §56):** the first 7.3 h clean (no link end, no reset, heap flat); 387
  presents: transfer 2.0 ms median / 12.2 max (M) — the link is the tick ceiling, not the panel; in the case the
  panel is off (copies counted, nothing transferred); the uptime tick runs 1.024 per ms. Rulings: F1.7 into Phase 2;
  a FLAGS_SET with no lease refused (status 3) from Phase 2's candidate; the soak = ordinary wear during Phase 2's
  build, and no Phase 2 flash before a worn day on this build is read.
- **🔴 Phase 2 is designed, not built (`FIRMWARE.md` §4; `HANDOFF.md` §57–§58):** the drawing contract v2 (modes
  17–24, the v2 image record, the 224 table, op 5 CACHE_SIZE, status 3–5, refusal fields 23–25, the budget
  options, the v2 vectors) **plus upstream's link edits (Adam, 2026-09-15: LE 2M enabled, the fast profile 7.5 ms /
  latency 0, the slow request bound to the fast record — ~41 KiB/s measured upstream, 5× our 8.2).** §57's eleven
  design decisions carry recommendations and are **not ruled yet**. §57 holds the journal baseline the exit is
  priced against.
- **App layer:** Main · Settings · Reader · Tmux · Files · Torrents · Music · Games · Feed (`WINDOWS.md` and the
  per-window records); `POPOVER.md` is a spec, built in Phase 6a. **The battery on the current tree (§54.5,
  §54.7):** core 555 · desktop 15 · `--selfcheck` ×3 · lint 0. Known rate misses: `FeedWindowTest.deepLinks…`
  (§49.6), a seam test race (`SeamSessionTest.kt:82`), an oracle-walk settle under parallel CPU load — run the core
  suite alone; a repeated `:core:test` answers FROM-CACHE (`:core:cleanTest :core:test --no-build-cache` for a rate).

## Measured on glass (grade M)

Time to first ack per gesture, the phone's journal (`tools/journal_report.py`):

| gesture | first flush | first ack | source |
|---|---|---:|---|
| window notch, 0.40 → 0.42 (2026-09-07 → 12, n=5,257) | 437 B / 1.2 KB / 3.7 KB (median / p75 / p90) | 105 / 204 / 522 ms | §48.1 |
| Main notch, 0.40 (§42.0) | 716 B / 1.9 KB | 117 / 247 ms | §42.0 |
| whole window gesture (burst) | — | 342 ms median, 3.0 s p90 | §48.1 |
| window notch, 0.40 → 0.44 bursts (to 2026-09-13, n=10,367) | 391 B / 3.2 KB (median / p90) | 89 / 494 ms | §49.1 |
| Main notch, 0.40 → 0.44 bursts (n=7,346) | 106 B / 228 B | 68 / 96 ms | §49.1 |
| **the bounded atlas skip on glass (2026-09-15 04:06, §54.1):** a Bluetooth-toggle rebuild kept 9 fonts and 16 icons (60 KB) — gaps 63.1 / 63.0 s inside the 80 s window; 16 cached-draw flushes after it, none refused, nothing re-sent | 0 B re-uploaded (was ~60 KB, ~7 s of link) | — | §54.1 |
| **the link itself (M0.3 per-event, 2026-09-14 captures):** two full 247 B packets per served connection event on LEFT, served every 60 ms in two of three sessions (every ~25–30 ms in the third) | — | 8.2 KB/s while the phone has more queued | §54.4, `research/perevent.py` |

The ack precedes the panel refresh (§48.1, verified): what the eye waits for is longer than these. A flush under
100 B acks in ~60 ms; each KB adds ~140 ms; the tail is pixel bytes. Phone CPU per flush: 17 ms median / 66 p90.

## 🔴 The next session — build Phase 2 (`FORK.md` Phase 2, `FIRMWARE.md` §4)

1. **Adam's rulings on `HANDOFF.md` §57's eleven decisions** (the recommended shapes are what §4 says). Nothing is
   coded before them.
2. **The fork (`~/damage-cfw`, branch `damage`):** modes 17–24, op 5, status 3–5, refusal fields 23–25, DRAW2 (flag
   bit 2), contract 2, DamageCaps bits 5 (v2 drawing) and 6 (the link); the three link edits as in-place entries in
   `patch_compress.py` (the sites: `HANDOFF.md` §58); the host shim gains a JBD4010 ops record and the partial
   entry; `host/test_damage_ext.py` covers the new ops. Then `./build_cfw.sh --skip-venv --update-patches`, the new
   hash into `build_cfw.sh` by hand, `tools/verify.py` (the link edits are in-place constants, the same class as
   a5d1c31's arena size; no new appended site on a boot path).
3. **The vectors:** `firmware/make_vectors.py` gains §4's v2 set; the fork's `run_vectors.py --write` fills the
   expectations; `run_self_test.py` runs the drawing ones. A disagreement is a finding, never an edited expectation.
4. **Damage:** `CfwModes`/`TextureCache` v2 + lint; `GlassFirmwareSim` v2 (`ConformanceVectorTest` green on every
   step); `DamageMsg` (fields 23–25, status 3–5, op 5); the compositor's per-lens draws replacing the widened delta +
   `CopyPair` + proof retry; fills for post-copy strips; kerning on; Reader's page staging and clip + draw page turn;
   back-to-Main and the height switch as fill + draws; the keeper's 2M request and its journal note;
   `journal_report.py` (refusal notes; first-flush bytes per gesture class against §57's baseline). APK 0.50 (bump
   both numbers; `:phone:stageApk` alone).
5. **The whole battery green** (`CLAUDE.md`), then **the ritual (`FORK.md` §7) with Adam's in-the-moment go**, after a
   worn day on the Phase 1 build has been read. On glass: the v2 vectors through `selftest:`, `probe:telemetry=read`
   (fields 23–25, the cache size), the link (`probe:phy=2m`, the journal's connection-parameter lines, ms/KB, a
   capture for `research/perevent.py`, the battery %/h against 7.4, the earbud with Music playing). T2's exit:
   `proof` and `edge` misses at zero, first-flush bytes down per gesture class, the page turn priced.

**Follow-ups for a session without Adam (his call where marked):** the seam test race one-liner and the Feed tapper
experiment (his call — Feed is suspended work); the ring service's and the touch processor's event ids; the stock RTC
getter's name; the TinyFrame role byte; a capture with the ring asleep (the 60 ms cadence). Parked by his ruling:
the temple-burst restarts (§54.8).

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
| 5 | **A two-arm BTSnoop capture spanning a drop, and one with the ring asleep** (the packets-per-event read is done, §54.4) | one drop from the radio's side (§42.2); the 60 ms cadence question |
| 7 | **msgId-255 behaviour under CFW** | it ends the link on stock |
| 8 | **Chrome legibility** at the real faces on glass | renders cannot answer it |
| 9 | **WEA/CMAS visibility to a normal Android app** (Pixel 10a) | `DESIGN.md` §4.5's emergency promise rides on it |
| 10 | **Connected RSSI** on glass | the status bar's link cell |
| 15 | **Is the sid-0x01 prelude required** by the CFW before CREATE? (graded U) | three eaten CREATEs per cold start ≈ 6 s (§34.3) |
| 21 | **Temple long-press accident rate** (gloves) | §1.2's bare-long-press no-op guards it |
| 24 | **Does the firmware enter Silent Mode by itself** (wear detection, idle)? | the journal will say |
| 25 | **The arm rebuilds' cause** (§42.2) | `FORK.md` M0.5 (a boot banner settles reset-or-stall), M0.6, and **M0.7** (the glasses sat in the case through drops and quiet nights alike: the phone's state is the variable — §50.6) |
| 26 | **The wake loop's cause** (§42.3) | the `keeper: start failed: …` notes name it |
| 28 | **True user-perceived latency** (ring press → visible change; the ack is only a lower bound) | `FORK.md` M0.2 |

**Cheap probes nobody has run:** the CFW logger service (sid 0x0F — M0.5) and the file-export service (sid
198/199 — `NOT_SUPPORT` is a safe answer).

## Upstream CFW (checked 2026-09-15 — `HANDOFF.md` §58)

Eleven g2flash commits past our pinned `a5d1c31`, all on stock base 2.2.9.22. **`c63710c` (2026-09-10) reconfigures the
link in three in-place edits — LE 2M enabled in the startup feature command, the fast profile 7.5 ms / latency 0, the
idle slow request forced to fast — ~41 KiB/s measured there (5× our 8.2); our three sites are read at instruction
level; **Adam's ruling: built into Phase 2's candidate** (he trusts the public release). The 08-31 lost-ACK fix is 2.2.9-only.
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
  `a5d1c31`, branch `damage`, the §10 flasher fix carried over; first commit `b3bdd5c`; 2026-09-13 commits
  `6db86e2` (our clang's pin + `tools/verify.py`), `cd802ec` (`host/`), `a8f3610` (the settings extension), pushed;
  `g2_2.2.6.10.bin` in its root is a symlink to `fws/2.2.6.10/e287…bin` (git-ignored); remotes: `github` =
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
python3 research/verify_cfw.py                        # before any flashing conversation: the INSTALLED image's provenance
python3 tools/glassdrive.py aphone $TOKEN probe:diag=show   # probes (APK 0.45+): diag=show|hide · logger=on|off · phy=2m|1m · telemetry=read · flags=clear|probe|0xNNNN; 0.46+: cache=info · selftest=begin|end|step:HEX
curl -s "http://aphone:7403/journal?token=$TOKEN" | python3 tools/journal_report.py - --since 2026-09-14 --glasslog
(cd ~/damage-cfw && python3 tools/verify.py)           # the fork's image: pin, reproducibility, Thumb-bit audit, size guard, site list
(cd ~/damage-cfw && python3 host/run_vectors.py && python3 host/run_self_test.py && python3 host/test_damage_ext.py)   # the fork's C on the PC: vectors, the self-test form, the §3 contract
python3 tools/glassdrive.py aphone $TOKEN probe:telemetry=read probe:cache=info   # a Damage build's record (RIGHT answers; the `glass` note / the /log line)
python3 tools/glassdrive.py aphone $TOKEN selftest:firmware/vectors/v1-keyframe.json   # the on-glass self-test of one vector (after the flash)
python3 firmware/make_vectors.py                      # rewrite the vector INPUTS; then the fork's run_vectors.py --write fills expectations
python3 research/fwread.py dis 0x473c44 0x473d70      # the stock image at instruction level: dis · fn · word · refs · calls · strings · owner · sha (our bytes vs the corpus header)
python3 research/perevent.py                          # M0.3: packets per served connection event per arm, from the captures (§54.4)
./gradlew :core:cleanTest :core:test --no-build-cache # a REAL re-run of the core suite (a plain re-run is answered FROM-CACHE)
```
