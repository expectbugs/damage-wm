# Where we are, and what to do next

**The entry point for a fresh session**: what is true now, what comes next, where the records are. History lives
in `HANDOFF.md`; this file only points at it. Read `CLAUDE.md` → this file → **`FORK.md`** (the plan; §11 the
log) → `FIRMWARE.md` (the contract) → `HANDOFF.md` **§65** (what four reviews of Phase 2 measured, and the
protocol), §64–§61 (the four reviews), §60 (Phase 2 built), §59 (the day's live defect), §55 (Phase 1 flashed),
§48 (the fork decided). Firmware facts: `CLAIMS.md` → `research/fork-reads-2026-09-13.md` → the image through
`research/fwread.py`.

## Where we are (2026-09-16)

- **🔴 The CFW fork and the Damage rebuild are the ONLY work (Adam, 2026-09-12; `HANDOFF.md` §48)** until
  `FORK.md` Phase 8 closes. `FORK.md` = the plan (§11 the log), `FIRMWARE.md` = the contract, `HANDOFF.md`
  §48–§65 = the records, `CLAIMS.md` = the firmware facts. **The build and review protocol is `CLAUDE.md`
  "Scope, build, review" and `FORK.md` §3.8**: a phase's design pass fills the failure envelope before any code;
  a build ends with a Not-built list and the two instruments' numbers; a fix sweeps its class; two general
  review passes, then class sweeps and instruments.
- **LIVE: the fork's Phase 2 build on both lenses since 2026-09-16 13:27 (§65)** — pin `48172b62…` = Phase 1 +
  modes 17–24, op 5, status 3–5, fields 23–26, the partial path, the three link edits (reports `2.2.6.10`; detect by
  `EVENCFW/` and DamageCaps `contract 2 features 0x7f`, never the version); 32 sites; archived with its logs in
  `fws/2.2.6.10-cfw-48172b62/`. **Rollback: Phase 1's `fws/2.2.6.10-cfw-c5e4f8b7/`** (a5d1c31's `…-d4054ab1/` and
  stock behind it; `research/verify_cfw.py` pins the a5d1c31 one). **First light 13:32, green (§65):** `contract 2 features 0x7f`, op 5 160 KiB, DRAW2, both arms on
  LE 2M, the partial refresh running; Adam's verdict "large images significantly faster, no problems"; the on-glass
  self-test 19 of 20 vectors matching every step, the twentieth's one corner named (item 3 below). Phase 1's record (§55, §56, §65): 7.3 h then 10.2 h worn clean, transfer 2.0 ms median, the uptime tick
  1.024 per ms, the arm drops = in-case reboots. The phone APK drives; the OpenRC `damage` service is the data host
  and standby (§19, `DAILY.md`), still on 0.44's core.
- **The APK. 0.49 installed** (2026-09-15 03:46; every `build` note through 09-16 12:17). **0.55 INSTALLED 2026-09-16 ~13:10 (Adam)** and driving Phase 2 since 13:32 (§65). **0.56 STAGED 14:12** — `hintMaxRows`
  240 → 40 (the measured break-even, §65) and nothing else; Adam installs whenever (~30 s blank, a 13 s atlas re-upload).
- **🔴 Phase 2: FLASHED 2026-09-16 13:19/13:24 (§65) after four reviews** (`HANDOFF.md` §60–§64; `FIRMWARE.md`
  §4 as built; Damage `67fdfa1`, the fork `f20bac9`) — **first light green, the self-test done (§65); T2 open for the day's reads (item 2 below);
  Reader's page staging is the next coding part (item 1)**. Fork pin **`48172b62…`** = Phase 1 + modes 17–24, op 5, status 3–5, fields
  23–26, the partial path, the three link edits; **32 entries — §63 added ONE new site, `0x00473D80`** (the
  display task's other refresh call): read §63.2 item 4 against `FORK.md` §3.1 before the flash. `tools/verify.py`,
  26 vectors / 237 steps, 20 self-test, 76 host checks green; the simulator equals the C on every step, both
  lenses, both forms. Damage: the v2 atlas, per-lens draws, fills, the reseed, the hint, kerning, the 2M request,
  the report (`Contract2Test`; gated on DamageCaps bit 5 + DRAW2, so the APK runs as before on the contract-1
  build). **Not built:** Reader's page staging. §57 holds the journal baseline. Adam ruled §57's eleven decisions
  as recommended.
- **What the reviews changed that a fresh session must not undo** (the why is in the cited item):
  - a mode-24 partial refresh is taken only while the panel already shows the whole previous frame — a refused
    batch, stock content, a release point and the overlay each mark it stale, in the C, the model and §4; the
    vectors compare what the LENS shows (`"panel": true`) (§62.1)
  - the phone's own firmware model is told the build's contract (§62.3 item 1); an atlas upload is read back
    before its fonts go live, owed by EPOCH and by bytes — a repack keeps the `GlyphAtlas` and moves every
    offset (§62.3 item 4, §63.1, §64.2 item 2); the lapse itself, not a status string, is the recovery (§64.2 item 3)
  - op 5's success comes from op 5's OWN reply, never the sticky status register (§63.3 item 3); only statuses 2
    and 3 answer a flag arming (§62.3 item 2)
  - every start gate — the prelude, the capability query, FLAGS_SET, op 5, the warmup — re-asks on the pacing
    tick with one deferred that latches on SUCCESS (§63.3 item 4, §64.2 items 4–5)
  - telemetry fields 2 and 12 are OS TICKS, 1.024 per ms, `uptimeMs` converts, `LEASE_EXPIRY_MS` = 87,891; the
    DWT µs read HIGH and the firmware scales for it (§63.3 item 5, §64.3 item 6)
  - v1 modes 3/6 do NOT roll back a stream refusal; the model streams into the shadow too (§64.2 item 1)
  - `glassesSilent` and `recentlyReleased` do not cross a session boundary (§64.2 items 7, 11)
  - the fork's busy/pending handshakes are ONE state word each; every second pass re-derives its pointer and
    records its refusal; the cache write loops take a tested local; the live save-under slots are guarded across
    tasks (§62.2 item 2, §63.2, §64.3 items 1–3)
  - `--selfcheck` runs TWICE (contract 1, then a Phase 2 build with cached text on) and counts its own oracle
    runs per pass (§63.4, §64.4); `glassdrive.py selftest:` exits non-zero on a step that differs;
    `journal_report.py` prints the gesture's WAIT beside the ack — price a window against the wait (§64.4)
  - `make_vectors.py`'s `arm` acquires the lease and arms DRAW2 BEFORE the keyframe, or no hinted vector takes
    the partial path (§63.4)
- **A live defect fixed for APK 0.50 (§59):** the right lens rebooted inside the atlas skip's window and refused
  every cached draw (Main on the left lens only); the start reads RIGHT's uptime before the carry decision.
  Workaround on 0.49: phone Bluetooth off for 90 s.
- **App layer:** Main · Settings · Reader · Tmux · Files · Torrents · Music · Games · Feed (`WINDOWS.md` and the
  per-window records); `POPOVER.md` is a spec, built in Phase 6a. **The battery on `7997a4d` (§64):** core 591 ·
  desktop 15 · `--selfcheck` ×3 (463 checks each, both contracts) · 57 renders · epub/music/games/feed · lint 0 ·
  APK 0.55. §65 changed one vector, the lint gate and a test comment, and ran lint, the fork's three host gates,
  `ConformanceVectorTest` and `GeometryTest`. Known rate misses (§54.5, §54.7): `FeedWindowTest.deepLinks…` and
  `…browseThroughTheKeyboard…` (§49.6), a seam test race (`SeamSessionTest.kt:82`),
  `Review20260905Test.cashingOut…` once (§61, cause not found) — run the core suite alone; a repeated
  `:core:test` answers FROM-CACHE (`:core:cleanTest :core:test --no-build-cache` for a rate).

## Measured on glass (grade M)

Time to first ACK per gesture, the phone's journal (`tools/journal_report.py`). **The ack is the link's own
share of the wait, not the wait** (§64.4): the report's `first wait ms` column — submit to done — is what the
gesture actually took, and on the reference journal a WINDOW notch is 230 ms median / 1,004 p90 there against
the 75 / 523 below. Price a window against the wait; these are kept because the history is in them.

| gesture | first flush | first ack | source |
|---|---|---:|---|
| window notch, 0.40 → 0.42 (2026-09-07 → 12, n=5,257) | 437 B / 1.2 KB / 3.7 KB (median / p75 / p90) | 105 / 204 / 522 ms | §48.1 |
| Main notch, 0.40 (§42.0) | 716 B / 1.9 KB | 117 / 247 ms | §42.0 |
| whole window gesture (burst) | — | 342 ms median, 3.0 s p90 | §48.1 |
| window notch, 0.40 → 0.44 bursts (to 2026-09-13, n=10,367) | 391 B / 3.2 KB (median / p90) | 89 / 494 ms | §49.1 |
| Main notch, 0.40 → 0.44 bursts (n=7,346) | 106 B / 228 B | 68 / 96 ms | §49.1 |
| **the bounded atlas skip on glass (2026-09-15 04:06, §54.1):** a Bluetooth-toggle rebuild kept 9 fonts and 16 icons (60 KB) — gaps 63.1 / 63.0 s inside the 80 s window; 16 cached-draw flushes after it, none refused, nothing re-sent | 0 B re-uploaded (was ~60 KB, ~7 s of link) | — | §54.1 |
| **the link itself (M0.3 per-event, 2026-09-14 captures):** two full 247 B packets per served connection event on LEFT, served every 60 ms in two of three sessions (every ~25–30 ms in the third) | — | 8.2 KB/s while the phone has more queued | §54.4, `research/perevent.py` |
| **Phase 2, first hour on LE 2M (2026-09-16 13:32–13:41, §65):** the partial refresh 2.8 ms median vs the full 4.4 (n=162/28); ≈1.6 ms + 66 µs per row (rough join, n=58) → break-even ≈40 rows, `hintMaxRows` 240 too high | 0.5–1.5 KB / 1.5–3 KB / 3–6 KB | 98 / 193 / 276 ms (n=65/9/76) | §65 |

The ack precedes the panel refresh (§48.1, verified): what the eye waits for is longer than these. A flush under
100 B acks in ~60 ms; each KB adds ~140 ms; the tail is pixel bytes. Phone CPU per flush: 17 ms median / 66 p90.

## 🔴 The next session — the next big coding part is Reader's page staging, designed against §3.8 first

Phase 2 is on the glasses and first light is green (§65). Test stop T2 stays open only for the reads Adam's day and
tonight give. Nothing needs flashing; the protocol (`CLAUDE.md` "Scope, build, review", `FORK.md` §3.8) binds the build.

1. **Reader's page staging — the one Phase 2 item not built, and the largest single latency win.** The next and
   previous page as v2 image records (mode 19) uploaded off the gesture path — the 160 KiB cache holds ≈55 KB beside the
   ≈105 KB atlas; two pages ≈ 44 KB — and a page turn as clip + draw (modes 20 + 17 at the content plane's disparity);
   the compositor needs a staged-draw primitive that paints its shadows from the record so belief = glass.
   **The design pass answers `FORK.md` §3.8's six questions BEFORE any code** (the protocol's first use): what clears a
   staged page at a session boundary, a lapse, a reset, a rebuild; what releases the wait on a page upload that never
   acks; what identity a staged record keeps across a repack; which task or lens reaches it meanwhile; one read of a
   shared field per decision; the sibling sites (both lenses; the v1 twin). The build ends with a Not-built list and
   the two instruments' numbers, and the page turn's FIRST flush is the number T2 judges — price it with §65's curve
   (0.5–1.5 KB 98 ms, 3–6 KB 276) and the partial refresh's ≈66 µs/row. Facts: `FIRMWARE.md` §4 (the phone-side
   paragraph), `Compositor.emitCachedV2` / `hintMaxRows`, `TextureCache` v2, `Contract2Test`, `AtlasRepackTest`.
   Optional beside it: `lint.py` rules for the v2 budgets; a Global row for the hint's A/B.
2. **Adam's reads for T2 (his day, tonight):** 0.56 installed if not yet; the day's journal
   (`journal_report.py --since 2026-09-16`) — the ack curve on 2M over a workday, the cacheMiss breakdown (`proof` and
   `planes` at zero is the exit), battery %/h on 2M against 7.4–7.8, the in-case reboots against §65's baseline (five
   in 11 h, three of them 5–7 min after docking); **the §62.7 retention check** (shell up, glasses into the case for a
   minute and out — is the first frame after the wake whole?).
3. **The one-byte finding (§65, `CLAIMS.md`):** a mode-19 message with no entries is accepted and bumps the generation
   in the C and the simulator; on the glass it never moves it and nothing records a refusal — dropped between the
   phone's image lane and the firmware's handler; cause U; no real traffic sends one. Settle it at instruction level
   (the stock path ahead of `image_worker`) or state the minimum in `FIRMWARE.md` §4 and keep the vector for the host
   forms only. A quiet-pass item, not a blocker.
4. **One focused read, not a fifth review:** the two review-invented mechanisms — the atlas read-back epoch
   (`Shell.kt` `atlasCheckOwed`, `AtlasCarryTest`, `AtlasRepackTest`) and the fork's busy state word (`damage_draw.c`
   `damage_slots_enter`/`leave`, `cfw_context.h` `DMG_BUSY_*`) — ~200 lines, the highest prior in either repo.
5. **Class sweeps across the whole codebase** (`CLAUDE.md` protocol 3 and 5), one grep-driven pass per class: a shared
   field read twice in one decision; a wait with no escape from a write that keeps failing; state with no clear at a
   session, lease or epoch boundary; a guard keyed on an identity that does not survive its event; a fix applied to
   one sibling; a gate green over code it never runs.
6. **🔴 The full mutation sweep** (Adam's ask, deferred: ~2 h) — `(cd ~/damage-cfw && python3 tools/mutate.py)` in the
   background with the gradle daemon stopped first (`./gradlew --stop`; the low-memory guard kills long background
   tasks) — against §64.1's 39 of 106, the delta into `HANDOFF.md`; then the vector pass from its list (§64.5).
7. **The instruments (§64.6) and the on-glass self-test:** `firmware/fuzz_vectors.py` and `~/damage-cfw/tools/mutate.py`
   print their own instructions; `glassdrive.py aphone TOKEN selftest:firmware/vectors/NAME.json` runs one vector on the
   glass — since §65 it synchronises on the firmware's counters (the begin's zero, each live write's generation bump
   against the C's expectation) rather than on sleeps; a vector's live writes drop the shell's atlas for the session
   and nothing presents during a step, so run it in a quiet minute.

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

**On Phase 2 (LE 2M; the first hour, n=270; §65): < 500 B 71 · 0.5–1.5 KB 98 · 1.5–3 KB 193 · 3–6 KB 276 ms median** —
the KB term about halved, the floor unchanged; the interval stays 15 ms / latency 1 (the phone's request wins).

Why (grade I): one AA packet per usable connection event; 15 ms interval with slave latency 1 = every 30 ms;
242 B / 30 ms ≈ 8 KB/s. PC-direct BlueZ sends ~6 packets per event (~50 KB/s). The firmware's receive path parses
one packet per ATT write (§48.1, grade C). `overview.md` §5.2's `ms ≈ 60 + bytes/50` is PC-direct only — price
nothing with it. The fork's levers for this are `FORK.md` F1.6–F1.8, each gated on M0.3.

## Standing rules (pointers)

The rules are `CLAUDE.md`'s — the review rules, the compositor discipline, the latency standards, the protocol.
Three that are not there: `handleMs` in the journal INCLUDES the assemble (§35.1); in a live walk never scroll in
Music's root, and the tmux pane's SECOND tap is the keys list (a third sends a key); `tools/journal_report.py`'s
per-gesture section judges every window (§42).

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
| 25 | **The arm rebuilds' cause** (§42.2) — **they are REBOOTS** (F1.2's uptime, §65: five on 2026-09-16, all in the case, three of them 5–7 min after docking, uptime ≈100–105 min at each; LEFT's uncounted) | the cause: `FORK.md` M0.7's phone-state experiment, the case's charger, a stock in-case restart, a held lease with the panel off — all U; not the work (§50.9); the post-flash soak re-reads it |
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
desktop/build/install/desktop/bin/desktop --selfcheck # after ./gradlew :desktop:installDist; run it more than once.
                                                      # 463 checks: it runs the script TWICE since §63 — contract 1, then a
                                                      # Phase 2 build with cached text ON (the v2 ops had no oracle before)
python3 tools/lint.py                                 # SYM/GEO/BUD, exits 0; --selftest names what it covers
TOKEN=$(python3 -c "import json;print(json.load(open('/home/user/.damage/config.json'))['token'])")
curl -s "http://aphone:7403/journal?token=$TOKEN" | python3 tools/journal_report.py -   # the phone's journal, per-gesture numbers included
curl -s "http://aphone:7403/log?token=$TOKEN&tail=400"                                 # the phone's log (0.41+), no adb
python3 tools/glassdrive.py aphone $TOKEN --pace 2.5 double wait:3 snap:/tmp/a.png …    # drive the glasses; snap before every tap
python3 research/verify_cfw.py                        # before any flashing conversation: the INSTALLED image's provenance
python3 tools/glassdrive.py aphone $TOKEN probe:diag=show   # probes (APK 0.45+): diag=show|hide · logger=on|off · phy=2m|1m · telemetry=read · flags=clear|probe|0xNNNN; 0.46+: cache=info · selftest=begin|end|step:HEX; 0.51+: cachesize=KiB · selftest=live:HEX (a vector's cache write)
curl -s "http://aphone:7403/journal?token=$TOKEN" | python3 tools/journal_report.py - --since 2026-09-14 --glasslog
(cd ~/damage-cfw && python3 tools/verify.py)           # the fork's image: pin, reproducibility, Thumb-bit audit, size guard, site list
(cd ~/damage-cfw && python3 host/run_vectors.py && python3 host/run_self_test.py && python3 host/test_damage_ext.py)   # the fork's C on the PC: vectors, the self-test form, the §3 contract
python3 tools/glassdrive.py aphone $TOKEN probe:telemetry=read probe:cache=info   # a Damage build's record (RIGHT answers; the `glass` note / the /log line)
python3 tools/glassdrive.py aphone $TOKEN selftest:firmware/vectors/v1-keyframe.json   # the on-glass self-test of one vector (after the flash; v2-*.json on the Phase 2 build, APK 0.51+)
                                                      # since §63 it EXITS NON-ZERO on a step that differs, and compares the
                                                      # refusal record on v1 vectors too — read the status, not only the lines
python3 firmware/make_vectors.py                      # rewrite the vector INPUTS; then the fork's run_vectors.py --write fills expectations
python3 firmware/fuzz_vectors.py /tmp/fz 40 25        # §64: random sequences as vectors — the C writes their
(cd ~/damage-cfw && python3 host/run_vectors.py --dir /tmp/fz --write)   #   expectations, then the simulator answers:
DAMAGE_VECTOR_DIR=/tmp/fz ./gradlew :core:cleanTest :core:test --tests '*ConformanceVectorTest*' --no-build-cache
(cd ~/damage-cfw && python3 tools/mutate.py --list)   # §64: the refusal guards, and which gates prove them (hours)
python3 research/fwread.py dis 0x473c44 0x473d70      # the stock image at instruction level: dis · fn · word · refs · calls · strings · owner · sha (our bytes vs the corpus header)
python3 research/perevent.py                          # M0.3: packets per served connection event per arm, from the captures (§54.4)
./gradlew :core:cleanTest :core:test --no-build-cache # a REAL re-run of the core suite (a plain re-run is answered FROM-CACHE)
```
