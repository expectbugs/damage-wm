# Where we are, and what to do next

**The entry point for a fresh session**: what is true now, what comes next, where the records are. History lives
in `HANDOFF.md`; this file only points at it. Read `CLAUDE.md` → this file → **`FORK.md`** (the plan; §11 is the
progress log) → `FIRMWARE.md` (the contract) → **`HANDOFF.md` §49 (Phase 0 begun, the latest record)** → §48 (the fork
decided) → §47 → §46 → §44 → §43 → §42 → what they cite. For firmware facts: `CLAIMS.md` ("Firmware internals read
for the fork") → `research/fork-reads-2026-09-13.md` → the image itself through `research/fwread.py`.

## Where we are (2026-09-15, the queue run while Adam was at work)

- **🔴 The §51.9 queue ran (`HANDOFF.md` §54): the bounded atlas skip is BUILT (APK 0.49 staged), R0.1 and
  R0.5 are read, M0.3's per-event read is done, the core suite ran ×20 (plus a self-review pass with three
  small changes and a final loop, §54.7); **committed and pushed on Adam's word (Damage `940272e` on
  `origin/main`)**, the fork untouched (pin `c5e4f8b7…`), nothing flashed.** The skip: the
  transport keeps a per-arm lease log (writes of the last 10 s before a link end struck; a release sticky)
  and decides at the rebuild's acquire whether both arms were inside 90 s less a 10 s margin; the shell keeps
  the atlas when it was, cached text is on, the session is not adopted and the last cache writer was itself
  (`FlushRequest.writer` → `LinkState.cacheWriter`); an `atlas` note either way, listed by
  `journal_report.py` under "atlas at session start". R0.1: input reaches a display thread ONLY through the
  sync framework's listeners, on both lenses; the input manager runs on RIGHT only and sends every event to
  the peers; both lenses can send over the inter-lens link; the per-notch scroll is the EvenHub page's kind-2
  container message. M0.3: two full packets per served connection event on LEFT, served every 60 ms in two of
  three sessions — 8.2 KB/s; F1.8 (bigger ATT writes) dropped in that form. **Adam's part is unchanged
  (§51.9 part B, with 0.49 in place of 0.48).**
- **A second review of the same work (`HANDOFF.md` §53): one contract deviation fixed on both sides, two
  guards; both trees committed and pushed on Adam's word (Damage `ba70608`, the fork `2935a66`).** The flags
  survived an FB_RELEASE that followed a lapse the glasses had already settled — in the fork's C and in the
  model alike (fork pin **`c5e4f8b7…`** now); the model also cleared them on a lease check with no lease, which
  the C never did. A self-test begin runs under its active mark; a stale FLAGS_SET waiter cannot drop a newer
  session's. Pinned on both sides, watched to fail on the old code; the whole battery green; **APK 0.48
  staged** (0.46 and 0.47 never installed). Nothing flashed. **One ruling for Adam:** a FLAGS_SET with no lease
  held is taken and stays in force until the next release point on both sides — refuse it instead?
- **The evening's work was reviewed late in the evening (`HANDOFF.md` §52): four defects verified and fixed
  (committed with §53's work, above).** In the fork a self-test step carrying a truncated mode-3/6
  message reached the BMP loader and dropped the live frame, and a stale F1.3 mark could stamp a stock refresh
  (pin **`70e47938…`** now); in Damage the keeper's reset detection missed a reset after a short uptime (the
  post-flash case) and a refused bit blocked the bits above it. Each pinned by a test watched to fail on the old
  code; the whole battery green; **APK 0.47 staged** (0.46 never installed). Nothing flashed.
- **🔴 Phase 1's candidate is BUILT on both sides, not flashed (`HANDOFF.md` §51; committed and pushed on
  Adam's word from work, §51.8).** The fork (pin `5ff9159b…` then, `70e47938…` after §52, **`c5e4f8b7…`** after §53; 27 entries, one new site — `0x00473CE4` in `FUN_00473C44`,
  the display task's refresh call, a pass-through for every stock refresh): F1.3 the transfer stamp and the
  presented notify (flag bit 0, field 113), F1.5 cache-keep (flag bit 1, a once-per-lease latch, generation /
  size / CRC through op 4), the self-test as image mode 16 (begin / step / end against a scratch shadow).
  Damage: `DamageMsg` + `CfwModes` + the simulator mirror it; the keeper's arm / hold-back protocol
  (`armFeatures`, `HOLD_BACK_MS` = 120 s placeholder); `present` journal records and the report's transfer
  section; `glassdrive.py selftest:FILE`; APK 0.46, **0.47** after §52 (see the builds line). Read at instruction level the
  same evening: **only the RIGHT lens can send** (`CLAIMS.md`) — every reply and notify is RIGHT's, LEFT runs
  every op blind. **Adam's ruling on the atlas (§51.8): the bounded skip** — re-upload skipped only after a
  rebuild inside the lease's remaining time on the dropped arm, no flag (to build); CACHE_KEEP stays unarmed
  until LEFT can be verified. The design calls made without him are listed in §51.4. F1.7 waits on the panel type.
  **The queue for the next session is the section below** (§51.9): the bounded skip first, then R0.1, R0.5, the
  M0.3 read, the core suite ×20; Adam's part when home.
- **🔴 The CFW fork and the Damage rebuild are the ONLY work (Adam, 2026-09-12; `HANDOFF.md` §48).** All other
  Damage work is suspended until `FORK.md` Phase 8 closes: no new windows, no Feed polish, no popover build outside
  the plan. `FORK.md` has the phases (0 measure/research/decide → 1 pipeline + first flash → 2 drawing v2 → 3 motion
  engine → 4 local input → 5 firmware chrome → 6 the shell and every window, animated → 7 offline home → 8 docs and
  readiness), the decisions D1–D8, the flash ritual and the assumptions table. `FIRMWARE.md` is the contract both
  the fork (`~/damage-cfw`, branch `damage`) and the simulator implement. **Nothing flashed.**
- **Phase 0 started 2026-09-13 (`HANDOFF.md` §49, `FORK.md` §11):** R0.2/R0.3/R0.4/R0.6 and the sid-0x0F handler
  read (`CLAIMS.md` "Firmware internals read for the fork"); two plan corrections (every present sends the whole
  panel — F1.7 rewritten; the overlay's `p` is the copy only — M0.1/F1.3 amended; §3.1's boot-path rule corrected);
  the probes built (APK 0.45, installed 2026-09-14 13:25); **the v1 conformance vectors pass: the simulator matches the firmware C
  on all 35 steps, both lenses**; `MOTION.md` drafted for the refinery. The fork (pushed to `github/damage`):
  the toolchain pin and `tools/verify.py` (`6db86e2`, the no-feature baseline `1920dda6…`), the x86 host harness
  (`cd802ec`), the Phase 1 settings extension — DamageCaps, telemetry, flags, no new patch site (`a8f3610`;
  reviewed and re-pinned 2026-09-14, `f9211ea2…`, not flashed).
- **Reviewed 2026-09-14 (`HANDOFF.md` §50):** the 09-13 work re-read against the stock image, four corrections —
  the telemetry record's field 4 is the status register the contract's §1.2 requires (the C, the simulator, both
  test sets); the boot-count read withdrawn (stock keeps `kvbooCount` only in the KV store, the RAM word it was
  read from is referenced by nothing); DamageCaps read from the capability answer only; arena 13 = 839,680 B.
  Committed and pushed (`c1c1017`; the fork `7377e0c`). **Adam's answer (§50.6, corrected): the glasses were in the charging case, untouched, from the
  night of the 12th to 13:15 on the 14th — through the 13th's fourteen daytime drops AND its quiet nights.** So the
  phone's day/night state is the variable, not the glasses'; `FORK.md` M0.7 now varies the phone with the glasses fixed
  in the case (beside it screen-off vs another room in use). Still to ask: the phone's whereabouts by day on the 13th,
  what the audio device `98:3A:1F:EE:84:77` is and whether it was connected that day, and where the phone was on
  the night of the 7th/8th. **Today's journal: no drop in 4.5 h worn at work** (§50.8) — the drops follow the phone's
  distance and surroundings. Four full captures kept locally (`captures/apk-20260914-*`), none spanning a drop yet. **The bug reports (§50.7):** the stack reads the snoop mode once at start (its source);
  `btsnooz_hci.log` in a zip proves it started in Disabled mode — today's two and June 5 12:15 did, the selection
  having landed after the stack came up; Adam chose Enabled every time. Recipe: Bluetooth off → Enabled → ten
  seconds → Bluetooth on → session → report; the check is `btsnoop_hci.log` present and no `btsnooz_hci.log`. The
  first report's stack dump answered M0.4 anyway: the glasses' feature set has no LE 2M PHY.
- **Latency hardened without the firmware (2026-09-12, `HANDOFF.md` §47):** the APK re-asks for its priority
  (Global `Link` row, `high` default) whenever the link slows; slow parameters flip the regime at once (`LINK SLOW`,
  one notice); the brightness write is answered and re-sent once; the standby claims only when both arms advertise
  to the PC; a full atlas evicts and repacks; Files and Torrents serve their last answer first; Tailscale leaves
  beardos around the VPN (the phone answered direct in 32 ms). **2026-09-13's `/log` (§49.1):** no slow-set
  episode since 09-12 15:00 (so the re-ask question has not come up again); the ~50-minute arm drops continue on
  `high` — 14 on 09-13, alternating arms, none from 22:35 to 08:04 with the session up. Whether `balanced` changes
  them is the second M0.6 day.
- **`POPOVER.md` is a complete spec, not built** (`HANDOFF.md` §46) — built in `FORK.md` Phase 6a (D3).
- **LIVE as the all-day daily driver — on the FORK's Phase 1 build since 2026-09-15 04:38 (`HANDOFF.md` §55):**
  pin `c5e4f8b7…` (a5d1c31 + F1.1–F1.5 and the self-test; reports `2.2.6.10`; detect by `EVENCFW/` and the
  DamageCaps field, never the version); features `0x1f`, flags `0x8001` armed (PROBE + PRESENTED), CACHE_KEEP off;
  the rollback is `fws/2.2.6.10-cfw-d4054ab1/`. The installed image's provenance is the fork's `tools/verify.py`
  now; `research/verify_cfw.py` pins the rollback image. The phone APK drives — radio and shell; the OpenRC `damage` service on beardos is the data host
  and standby (`HANDOFF.md` §19, `DAILY.md`). **G2CC's server is RETIRED (`HANDOFF.md` §44) — never start it by
  hand:** the setup page is Damage's (`desktop/SetupServer.kt`), the playlists refresh under Damage, qBittorrent
  is the `qbittorrent` service.
- **App layer:** Main · Settings · Reader · Tmux · Files · Torrents · Music · Games · Feed. **Builds:** APK
  **0.45 installed** (2026-09-14 13:25, its journal says so) = 0.44 + the §49 probes, the `battery` notes and the
  DamageCaps/telemetry parser; **0.49 STAGED** (`~/.damage/damage-wm.apk`, 2026-09-15 01:40 — the re-stage after the self-review, §54.7; the setup page serves it; not
  installed; 0.46–0.48 were staged earlier and never installed) = 0.45 + Phase 1's keeper protocol, the
  `present` records, the `cache`/`selftest` probes (`HANDOFF.md` §51.3) + the §52 fixes to the keeper (the reset
  seen past a grown uptime; a refused bit dropped, the rest armed) + the §53 waiter fix + **the bounded atlas
  skip (§54.1)** — harmless against the installed upstream build (the probes say "without DamageCaps"; the skip
  needs no firmware). The service runs 0.44's core and was **not** restarted (Adam at work on it;
  the seam's new `present` control is logged as unknown by an older PC, nothing more).
- **Battery on the 2026-09-15 tree (`HANDOFF.md` §54.5, §54.7):** core **555** — 30 genuine full runs today: the
  Feed miss 5, the seam miss 2 (`SeamSessionTest.kt:82` reads `started` before the seam forwards it — a test
  race, one-line fix for Adam), three untraced `NoSuchElementException`s, a Music-mode miss, an oracle-walk
  settle miss under load; 18 clean · desktop **15** · `--selfcheck` ×3 (+ ×2 on the final tree), ALL CHECKS PASS
  each (it is a rate) · snapshots 57 ·
  `--epub-check` 380/404 · `--music-check` · `--games-check` · `--feed-check` · lint 21 rules / 0 · `:phone:stageApk`
  in its OWN gradle call. The fork (unchanged since §53): `tools/verify.py` all pass (pin `c5e4f8b7…`) ·
  `host/run_vectors.py` 7/7 · `host/run_self_test.py` · `host/test_damage_ext.py` 44/44. ⚠ `HANDOFF.md` §49.6:
  `FeedWindowTest.deepLinks…` misses in some full core runs; §54.6 has the ×20 tally. ⚠ A full core run under
  parallel CPU load can miss an oracle-walk settle (§54.5); run it alone. ⚠ Gradle answers a repeated `:core:test`
  FROM-CACHE in 0.4 s: a rate needs `:core:cleanTest :core:test --no-build-cache`.

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

## 🔴 The next session — the queue before Adam is home, then his part (`HANDOFF.md` §51.9, run 2026-09-15: §54)

**State to start from (2026-09-15, after the queue run):** Damage `940272e` committed and pushed on Adam's word
(`HANDOFF.md` §54 — the skip, the reads, the tools, the docs); the fork is unchanged at pin `c5e4f8b7…` (nothing
below needed firmware); APK **0.49** is staged,
not installed; nothing flashed; the OpenRC `damage` service runs 0.44's core and must **not** be restarted
while Adam is at work on it. Gradle invocations one at a time (the oracle walk misses settles under parallel
load — §54.5 saw one); the full battery after any code change (`CLAUDE.md`); a core change that should reach
the phone means APK **0.50** (bump versionCode and versionName together, `:phone:stageApk` alone). Plain
wording everywhere.

**A. The queue (no glasses needed; Adam's order of preference, 2026-09-14 evening) — items 1–5 DONE 2026-09-15
(§54), item 6 still optional:**

1. ✅ **DONE (§54.1). The bounded atlas skip** (Adam's ruling, §51.8; option 3 of §51.4 item 6; §42.4's plan). After a session
   rebuild, keep the atlas belief instead of resetting it when the rebuild completed inside the lease's remaining
   time on BOTH arms — each arm judged by its own last successful lease write (the `CtlWork.Lease` lane knows per
   arm which writes went out; a write into a dropped arm fails as a `control` fault, so it does not count). The
   installed renewal rule then keeps both caches and no firmware flag is involved, which is why LEFT needs no
   report. Where: `Shell.kt` ≈ line 680 (`atlasReset()` at session start, after the transport's `start()` has
   awaited the lease); add a transport predicate (e.g. `leaseCarriedOver()`: for each arm, now − last successful
   lease write < `SettingsMsg.LEASE_EXPIRY_MS`, measured at the new session's acquire) and, when it holds, skip
   `atlasReset()` and the re-upload but keep the keyframe. Journal an `atlas` note either way ("kept across the
   rebuild: L gap 12 s, R gap 12 s" / "reset: L gap 131 s") so the saving is measured; the Silent-Mode wake (the
   lease dropped on purpose) resets by construction. Tests: a `SimTransport` restart inside the window keeps
   `sim.cacheAllocated(arm)` on both arms and the shell's cached fonts stay live (see `DamageMsgTest`'s restart
   pattern and the existing atlas tests: `grep -rn atlas core/src/test`); a restart after a lapse resets;
   `--selfcheck` ×3. Then APK 0.49 (0.48 is staged, not installed; bump both numbers).
2. ✅ **DONE (§54.2; the ids behind the slides/swipes and the TinyFrame role byte remain U). R0.1, the input path** (offline, `research/fwread.py`): the gesture mapper `FUN_00442d86` (subtypes
   0/2/4/6/8/10/0xc/0xe/0x10 — which are scroll), how input is mirrored to the other lens, how the EvenHub UI
   handler turns them into SysEvents (`gesture_fwd.c` has the two known sites); AND the slave→master send on the
   inter-lens link (`sync_interface_api.c` `[0x004646F0,0x00466010)`, `FUN_00464BB2` → `FUN_00464772`; the UART
   worker `[0x00541790,0x00541AF8)`; openCFW `g2-uart-sync-recovery.md`, `g2-sync-framework-recovery.md`) — Phase
   4 needs the first, and option 2 for the atlas (LEFT reporting its cache through RIGHT) rides the second. Facts
   into `CLAIMS.md` and `research/fork-reads-2026-09-13.md` (append a dated section); decide at instruction level.
3. ✅ **DONE (§54.3; the stock RTC getter still to name, the gauge offsets to read at instruction level). R0.5** (offline): the RTC getter (`g2-drv-rtc-recovery.md`), the fuel-gauge getters (the chg_bq27427
   overlay), the KV store's read/write (`FUN_0054116E` / `FUN_005411F2`, and whether a cached read from the
   settings context has a precedent — the boot count's blocker), the dashboard launch path and module registry
   (`ui_module_registry.c`, `ui_startup_app.c`) — for Phases 5 and 7.
4. ✅ **DONE (§54.4, `research/perevent.py`; the 60 ms cadence's cause is U). The per-event M0.3 read** of the four captures (`captures/apk-20260914-*.log`, local; `captures/README.md`,
   `SHA256SUMS`; the first-pass tool `research/linkparams.py`): packets per connection event on LEFT from the
   Number-of-Completed-Packets timestamps (§50.8's first pass: ~2 per 15 ms window, the host feeding faster) —
   F1.8's question (multi-packet ATT writes pay only if the phone is one-write-per-event).
5. ✅ **DONE (§54.5–54.6). The core suite ×20** in the background, one run at a time, for the Feed miss (§49.6: `FeedWindowTest.
   deepLinksResolveEveryForm`, 4 misses in 9 full runs so far, 0 in targeted runs): the rate and, from the failing
   runs' logs, what presses `first` (the candidates are listed in §49.6). No Feed fix without Adam (Feed is
   suspended work); the finding goes to `HANDOFF.md`.
6. **Optional:** a DWT stamp around the CACHE_INFO CRC in the fork's `damage_apply_control`, reported as a
   telemetry field, so the CRC's cost is measured on glass rather than modeled (~2–3 ms). A fork change: the
   host tests, `gen_patches.py`, the pin in `build_cfw.sh`/`DAMAGE.md`/here, `tools/verify.py`.

**C. Follow-ups found on 2026-09-15 (`HANDOFF.md` §54.5–54.7), for a session without Adam unless marked:**
the Feed miss is a tap on the comic bar's "first" button in a transient one-item-list state, the tapper still
unidentified — the cheap experiment (a rig that waits for its start click, or a WARN line in `comicTap`) is a
Feed test change, **Adam's call**; five one-off full-suite failures (three `NoSuchElementException`s in unrelated
tests, a Music-mode miss, a seam miss) did not reproduce in 125 targeted class-runs — the final loop's kept XMLs
(§54.7) are where a trace would be; the ring service for the ring's event ids (the touch processor's temple ids can wait — see below); the stock RTC
getter's name; one fuel-gauge store at instruction level; the TinyFrame role byte; a capture with the ring asleep
for the 60 ms cadence question. **Noted and parked by Adam's ruling (2026-09-15, §54.8): a burst of taps on either
temple restarts that arm (a beep, the stock launcher, the phone's timeout, a rebuild) — the ring never does it;
studied at the public-release polish for ring-less users. M0.1 is measured (item 27 below); the `diag` probe blanks
the display on this build and is retired.**

**B. Adam's part — DONE 2026-09-15 (§55) bar the soak:** M0.1 read, 0.49 installed, both lenses flashed after clean
dry runs, the capability read, the self-test, the telemetry, PROBE and PRESENTED armed. **Now:** wear them a day;
`journal_report.py`'s transfer section and the `keeper` notes after any drop (the uptime says reset or stall) are
the day's read. Still his: the §53.1 ruling; the two test-only waits (C above). The original list follows for
the record.

**B (as written before the flash). Adam's part, when home:** `patches/damage_ext.c` read whole and the site list (`tools/verify.py` step 6:
`0x00473CE4` in `FUN_00473C44`) reviewed against `FORK.md` §3.1; **M0.1**, one minute: `probe:diag=show`, read
the overlay line (`f13/20/27` free KiB, `w`, `p`), `probe:diag=hide` — it sizes the self-test's scratch (150 KB of
arena 13, transient) and any cache growth; **install 0.49** from the setup page (0.48 was never installed); **the flash ritual**
(`FORK.md` §7) with his in-the-moment go — dry-run staircase first, both lenses; then the capability read
(`features 0x1f`), the on-glass self-test (`glassdrive.py … selftest:firmware/vectors/v1-keyframe.json`, then
delta / copy / batch / refusals — RIGHT reports), `probe:telemetry=read` (uptime, the panel record → F1.7, the
heap figures), features armed one at a time (`probe:flags=0x8000`, then `0x0001`), a soak day with PRESENTED on
(`journal_report.py`'s transfer section is the tick's ceiling). CACHE_KEEP (`0x0002`) stays unarmed.

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
| 5 | **Two-arm BTSnoop capture with the APK driving** — via the bug-report mail path (no adb) — **done bar a drop (§50.8, §54.4):** two packets per served event, LEFT served every 60 ms; still owed: a capture spanning a drop, and one with the ring asleep (the 60 ms question) | `FORK.md` M0.3: packets per event (M now); the arm split (M); one drop from the radio's side (§42.2) |
| 7 | **msgId-255 behaviour under CFW** | it ends the link on stock |
| 8 | **Chrome legibility** at the real faces on glass | renders cannot answer it |
| 9 | **WEA/CMAS visibility to a normal Android app** (Pixel 10a) | `DESIGN.md` §4.5's emergency promise rides on it |
| 10 | **Connected RSSI** on glass | the status bar's link cell |
| 15 | **Is the sid-0x01 prelude required** by the CFW before CREATE? (graded U) | three eaten CREATEs per cold start ≈ 6 s (§34.3) |
| 19 | **Cached text by default?** Adam's eye on kerning-free text | moot after `FORK.md` Phase 2 (kerning on) |
| 20 | **A draw into a released cache** — the ImgResCmd status it returns | replaced by `FORK.md` F1.5 (a queryable generation and CRC) |
| 21 | **Temple long-press accident rate** (gloves) | §1.2's bare-long-press no-op guards it |
| 24 | **Does the firmware enter Silent Mode by itself** (wear detection, idle)? | the journal will say |
| 25 | **The arm rebuilds' cause** (§42.2) | `FORK.md` M0.5 (a boot banner settles reset-or-stall), M0.6, and **M0.7** (the glasses sat in the case through drops and quiet nights alike: the phone's state is the variable — §50.6) |
| 26 | **The wake loop's cause** (§42.3) | the `keeper: start failed: …` notes name it |
| 27 | ~~**Free heap and worker/copy time on glass**~~ **measured 2026-09-15 (§54.8):** free 306 / 75 / 145 KiB in arenas 13 / 20 / 27, worker 1,719 µs, copy 1,173 µs — the self-test's 150 KB scratch fits with ~156 KiB to spare | `FORK.md` M0.1 done; the same session showed a Silent-Mode round trip and a RIGHT link end while the glasses were handled (§54.8, causes U) |
| 27b | ~~The panel transfer time per present~~ **measured 2026-09-15 (§55): 1.2–6.6 ms per present, n=8** — the tick ceiling is the link, not the panel; the soak's `present` records give the distribution | `FORK.md` F1.3 |
| 27c | ~~Which panel Adam's pair has~~ **JBD4010 (§55)** — F1.7's per-row partial refresh exists | `probe:telemetry=read` field 10 |
| 27d | ~~The self-test on glass~~ **all five drawing vectors match the simulator on RIGHT, 21 steps (§55)** | `glassdrive.py selftest:` |
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
