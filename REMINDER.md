# Where we are, and what to do next

**The entry point for a fresh session**: what is true now, what comes next, where the records are. History lives
in `HANDOFF.md`; this file only points at it. Read `CLAUDE.md` → this file → **`FORK.md`** (the plan; §11 the
log) → `FIRMWARE.md` (the contract) → `HANDOFF.md` **§77** (2026-11-12: APK 0.65 built, pushed as `b833713`, STAGED and not installed; the PC service deployed from it, §77.12; P5–P7, the fork's vector pass, review pass 3 and fix round R4; everything in it measured on the simulator; its "For Adam" list, his calls first) → **§76** (2026-11-11/12: APK 0.64 built and staged, not installed; the PC service deployed, §76.13 — §75.6's builds, two review rounds and their fixes; everything in it measured on the simulator; its "For Adam" list, his calls first) → **§75** (2026-11-10 evening, documents only: Adam's rulings on §71–§73's calls; the menu's first paint traced to two causes; item 10's design pass; the fork's mutation sweep) → **§74** (upstream and official firmware read 2026-11-10, nothing built: facts for rest mode, the case, the ring and the link; small candidates for Phase 3's flash; D1 stands) → **§73** (the atlas packs what a face draws and grows in place; the link estimator on isolated flushes; APK 0.63, committed `6bba881`, INSTALLED 2026-11-10 17:16; Adam's calls in its "For Adam" block; its addendum = the 11-09 slow stretches and the two silent modes) → **§72** (the PC's OOM while Adam read in Sim; what a shell holds while down; the standby claims only on sight) → **§71** (the phone's `/journal` ended the APK; it streams in 0.61) → **§70** (the glasses ran flat: the case's battery; two rulings for the next APK) → **§69** (the status cell as states, every event a notice) → §67–§68 (upstream's cache and
transport read against ours) → **§66** (Reader's page staging: the design pass, T2's day read, the build) → **§65** (what four reviews of Phase 2 measured, and the protocol), §64–§61 (the four reviews), §60 (Phase 2 built), §59 (the day's live defect), §55 (Phase 1 flashed),
§48 (the fork decided). Firmware facts: `CLAIMS.md` → `research/fork-reads-2026-09-13.md` → the image through
`research/fwread.py`.

## Where we are (2026-11-12)

- **2026-11-12 (evening) — the order of work is under review: `REPLAN.md` is a PROPOSAL awaiting Adam's rulings
  (its §11).** He set the end goal that day: after the firmware phases come four more interfaces (XFCE4, the phone
  app, the kitchen TV, any browser) and a bus control system, before any new window. Until he rules, `FORK.md`
  stands, `MOTION.md` §6's sheet is on hold, and nothing of Phase 3 is started.

- **2026-11-12 — APK 0.65 BUILT, pushed as `b833713`, and STAGED at 16:58 (versionCode 65; 0.64 kept in
  `~/.damage/backup/`); **Adam said at ~17:05 he was installing it, and that 0.64 WAS installed and in use (the records said otherwise — §77.12's correction)**; the PC service runs `b833713` since 16:58 (`HANDOFF.md` §77,
  §77.12). Everything in it is measured on the simulator; nothing of 0.65 or of 0.64 has been read on the glasses.** P5, the class-sweep fixes (session boundaries; the start gates; a wide flush behind one lost
  image ack written at depth 2; the fid-ring clear, `07 00`, at every session start); P6, loop cost (a `loop` note a
  minute; parked windows compose nothing; the mirror check over changed rows); P7, notices, windows and tools (with
  `Notify · Damage` off the apps' own notices, the faults and the answers to his gestures show); the fork's vector pass
  (34 vectors; the fork's local commit `f9ba7c6`, host tooling only, not pushed); review pass 3 (no defect of daily
  use) and its fix round R4. 0.65 holds everything of 0.64 and is staged in its place.
  §77.10 lists his calls first. The late sync-handshake pair §76.11 item 3 named is gone with the PC deploy alone (0
  lines in seven hours against 545 before; M, the PC's log, §77.6).

- **2026-11-11 → 12 — APK 0.64 BUILT and STAGED (INSTALLED by Adam on 11-12 — his word at ~17:05, the time unread; replaced on the setup page by 0.65 at 16:58); pushed as `26ed20a`; the PC service deployed from it
  11-12 02:48 (`HANDOFF.md` §76, §76.13). Everything in it is measured on the
  simulator; nothing of it has been read on the glasses, and its phone half has not run on the device.** §75.6's
  scope in four packages: the menu's first paint (the base blacks only what a draw writes, every inked rect priced
  against its pixels; `Shell.retire`; Main's lens), the cache count read everywhere with a notice and one re-send
  for a write the glasses did not count, the sync's 2 s tolerance, the journal renamed past 64 MiB at a process
  start, a record of an uncaught error, the low-battery notice on its own phone channel, and rest mode's build 1
  (the reads, op 7 at each session start, a telemetry read a minute while painting, a `rest` probe for a timed
  hour); with T0 (the desktop program refuses an argument it does not know). Two review rounds (62 findings, 55
  confirmed) and their fixes, among them `instance.lock` (a second process on one data directory exits 4) and a
  start banner without the token value. **Adam installed 0.64 on 11-12 (his word; the time unread — §77.12's correction; 0.63 kept in
  `~/.damage/backup/`); the PC service ran `26ed20a` from 11-12 02:48 to 16:58** (0.65's jar since, §77.12; the old jars kept beside the APKs) — its
  first start set the 79.7 MB journal aside as `journal-20261112-024805.jsonl`, took `instance.lock`, and the 0.63
  phone attached on every channel (M, §76.13). Installing is Adam's word; §76.10 lists his calls first.

- **2026-11-10, evening (`HANDOFF.md` §75; documents only — no code changed, nothing built, staged, deployed or
  flashed, no probe sent; the tree's code is `6bba881`'s):**
  - **Adam ruled items 11 and 12 as recommended** (§75.1): no proof gate on a table patch — the re-read
    everywhere, a shortfall notice and one re-send instead; the sync's 2 s tolerance; the journal rotated, never
    deleted; a record of an uncaught error (2 s and the rotation's 64 MB are the lead's figures). All of it is the
    next APK's. The four statements that described the code before §73
    are rewritten.
  - **He closed the slow link outside as minor** ("no more tests"): still slow outside with the phone's Wi-Fi
    off (n=64). At the lunch table, the phone in front of him, the floor read 39 ms against his station's 60 and
    the per-KB term 46 against 56 (I: n=10 and 21, and place, pocket and music are not separated). Cause not
    established, not being looked for.
  - **The box that goes out as pixels is the shell's context menu** (§75.3): every first paint, 52 of 52 in 80
    minutes at ~2.6 KB, from two causes older than §73 — the title's box covers its own rule, and the content's
    records stay under the box. Reproduced on the simulator; two fixes proposed then — built in APK 0.64 since
    (`HANDOFF.md` §76.1, P1: the base's rule and `Shell.retire`; the simulator's menu box 301 B → a 54 B base and
    10 draws; on glass unread).
  - **Item 10 has its design pass** (§75.4): the low-battery notice as ruled, on its own phone channel; rest
    mode in two builds — the first reads (stock sends the wear event only while a settings byte is set — V; that
    the byte is its wear-detection switch — I; whether it is on, what the sensor reads in the case and what a rest
    saves are all unread), the second rests by itself.
  - **The fork's mutation sweep ran** (§75.5): 96 guards, 43 caught, 52 by nothing, 1 leaves the harness not
    returning; 11.5 minutes.
  - Evidence (git-ignored): `captures/2026-11-10-menu-proof/`, `captures/2026-11-10-wifi-lunch/`,
    `captures/2026-11-10-mutate/`.

- **2026-11-10, 17:40 — where a fresh session starts (M unless said; `HANDOFF.md` §73's last blocks, §74):**
  - **Committed and pushed** (`origin/main`): `6bba881` is the §73 build and the only commit with code;
    `de67f99` and the commits after it are documents. **The phone's APK 0.63 is the 2026-11-09 21:15 build of
    the tree committed as `6bba881`; the PC's jar is stamped `de67f99` — the same code. A documents-only commit
    after the stamp owes no deploy.**
  - **APK 0.63 is INSTALLED** (Adam; the process started 17:16:22, the session's `build` note is 17:16:41).
    **Its first 22 minutes on glass (read to 17:38):** 1,070 flushes, none refused; 23 cache writes, 25,771 B,
    every one counted by the glasses (cacheGen 3601 → 3624); 24 glyph gains in 16 promotions; no repack, no
    "stays pixels", no shortfall, no fault. The first eight fonts were live 1.4 s after the build note from
    10.7 KB (the start before it had re-sent the carrier CREATE five times, 10 s: unmeasured row 15). 0.57 at
    that day's 14:19 wake: 127 KB by +16 s, 190 KB by +31 s — a different window mix (its three faces left as
    pixels were MONO, which this session has not asked for). **A first look, n=1: the read proper, and the four
    things it leaves to watch, are item 0.** 0.62 is kept as `~/.damage/backup/damage-wm-0.62.apk`; the setup
    page (`http://beardos:7300/setup`) serves 0.63.
  - **The PC service runs this code:** deployed 17:18:52 (jar stamp `de67f99`), all four ports, the phone's
    channels attached, no claim, a clean log.
  - **The in-place table patch is read on glass:** `v2-tablepatch` 11 of 11 steps on RIGHT at 15:05, the
    generation 3597 → 3601 — the one firmware behaviour 0.63 leans on that had never run there. The run dropped
    the 0.57 session's atlas for the two hours until the install.
  - **The glasses:** in the firmware's Silent Mode 11-09 22:32:30 → 11-10 14:19:38 with no flush in between;
    the wake is §38's rebuild, 9.7 s from the OFF push to the first frame. **A journal tail with no flushes
    means the shell is asleep in Silent Mode, not a fault.** Adam puts them in Silent Mode for every drive (the
    both-temple press, worn; the case never leaves home); his next press is about 22:30 tonight (item 13).
  - **Evidence kept (git-ignored, local):** `captures/2026-11-09-journal/` (journal tails, the whole log of
    11-09), `captures/2026-11-09-session/` (battery summaries, mutation logs, simulator walks),
    `captures/2026-11-10-upstream-survey/`, `captures/2026-11-10-selftest/`, `captures/2026-11-10-apk063/` (0.63's
    first tails, the report on them and the last checkers' findings).

- **2026-11-09 → 10 (§73) — the latency items §66 left are BUILT, phone-side only, nothing flashed:** a face goes
  into the atlas with the characters it has drawn (≈3 KB against 16 KB whole, M on AWT) and gains the rest in
  place; the link estimator is medians over isolated flushes; with Cached text on, text is laid out by the cache's
  metric (a wrap made before a face was uploaded was cut when it went live). One unattended session built it:
  every design call in §73 is the lead's, listed there for Adam (item 12). Two reviews, 89 mutations, the battery
  twice; the records were then checked by two rounds of readers (§73's 2026-11-10 note).

- **2026-11-09 (§72):** **APK 0.62 staged that day (0.63 replaced it on the setup page 11-10; it carries all of this)** = 0.61 + a shell that holds nothing unbounded while down (keyed pushes,
  transport events by kind, the Reader's restore one at a time) + the cache-first shelf. **The PC service is
  deployed from `0dd6ba6`** (its first deploy since 09-12; `de67f99` replaced it 11-10): the standby claims only on sight, gives a failing claim
  back, retries busy ports. The init script now has `-XX:+ExitOnOutOfMemoryError -Xmx2g` and
  `retry=SIGTERM/30/SIGKILL/5`. Adam's rulings D1–D6 (11-07) as recommended; D2's mechanism was built differently,
  for the reconnect's sake (§72).

- **2026-11-04, read from the outside (M):** the glasses up 16.1 days on Phase 2 (`48172b62`), both arms on LE 2M,
  228k presents; **the phone still on APK 0.57** (0.60 staged 09-21, never installed); the PC service up since 10-20
  (the `phoneHost` pin held). Between 09-21 and 11-04 nothing changed in either repo. 0.61 (§71, superseded by 0.62)
  = 0.60 + the journal route streamed: on 0.57–0.60 a `/journal` fetch without `&tail=` ends the APK's process.

- **🔴 The CFW fork and the Damage rebuild are the ONLY work (Adam, 2026-09-12; `HANDOFF.md` §48)** until
  `FORK.md` Phase 8 closes. `FORK.md` = the plan (§11 the log), `FIRMWARE.md` = the contract, `HANDOFF.md`
  §48–§75 = the records, `CLAIMS.md` = the firmware facts. **The build and review protocol is `CLAUDE.md`
  "Scope, build, review" and `FORK.md` §3.8**: a phase's design pass fills the failure envelope before any code;
  a build ends with a Not-built list and the two instruments' numbers; a fix sweeps its class; two general
  review passes, then class sweeps and instruments.
- **LIVE: the fork's Phase 2 build on both lenses since 2026-09-16 13:27 (§65)** — pin `48172b62…` = Phase 1 +
  modes 17–24, op 5, status 3–5, fields 23–26, the partial path, the three link edits (reports `2.2.6.10`; detect by
  `EVENCFW/` and DamageCaps `contract 2 features 0x7f`, never the version); 32 sites; archived with its logs in
  `fws/2.2.6.10-cfw-48172b62/`. **Rollback: Phase 1's `fws/2.2.6.10-cfw-c5e4f8b7/`** (a5d1c31's `…-d4054ab1/` and
  stock behind it; `research/verify_cfw.py` pins the a5d1c31 one). **First light 13:32, green (§65):** `contract 2 features 0x7f`, op 5 160 KiB, DRAW2, both arms on
  LE 2M, the partial refresh running; Adam's verdict "large images significantly faster, no problems"; the on-glass
  self-test 19 of 20 vectors matching every step as its driver judged it then — the twentieth's difference was the driver's own expectation (item 4, closed in §73; a re-run would show 20 of 20). Phase 1's record (§55, §56, §65): 7.3 h then 10.2 h worn clean, transfer 2.0 ms median, the uptime tick
  1.024 per ms, the arm drops = in-case reboots. The phone APK drives; the OpenRC `damage` service is the data host
  and standby (§19, `DAILY.md`), deployed 2026-11-12 16:58 from `b833713` (§77.12; `26ed20a` from 02:48 that day, §76.13; `de67f99` from 11-10 17:18 before that, §73's last block).
- **The APK. 0.49 installed** (2026-09-15 03:46; every `build` note through 09-16 12:17). **0.55 INSTALLED 2026-09-16 ~13:10 (Adam)** and driving Phase 2 since 13:32 (§65). **0.57 INSTALLED 19:09 (Adam)**, its first read `HANDOFF.md` §66.1. **0.60 STAGED (§69: the status cell as states, every event a notice; it supersedes 0.59, never installed)** with 0.59's staging fixes (§66.1) — the ledger's settle
  window, the 64 KiB reserve, and a shortfall that can never switch the atlas off (0.57's ledger took cached text down at
  19:51 on three lagged readings); Adam installs whenever (~30 s blank, a 13 s atlas re-upload), then PRESENTED wants arming again.
  **0.60 was never installed; 0.61 (§71) superseded it; 0.62 (staged 2026-11-09) superseded both and was never installed either; **0.63 INSTALLED 2026-11-10 17:16 (Adam)** carries all three** (§72; it carries
  0.61's streamed journal route and 0.59's staging fixes). The phone was on 0.57 until then.
- **🔴 Phase 2: FLASHED 2026-09-16 13:19/13:24 (§65) after four reviews** (`HANDOFF.md` §60–§64; `FIRMWARE.md`
  §4 as built; Damage `67fdfa1`, the fork `f20bac9`) — **first light green, the self-test done (§65); T2 open for the day's reads (item 3 below)**. Fork pin **`48172b62…`** = Phase 1 + modes 17–24, op 5, status 3–5, fields
  23–26, the partial path, the three link edits; **32 entries — §63 added ONE new site, `0x00473D80`** (the
  display task's other refresh call): read §63.2 item 4 against `FORK.md` §3.1 before the flash. `tools/verify.py`,
  26 vectors / 237 steps, 20 self-test, 76 host checks green (28 / 259 / 22 since §73's two table-patch vectors; 34 / 342 / 26 since the vector pass, 2026-11-12, §77.4); the simulator equals the C on every step, both
  lenses, both forms. Damage: the v2 atlas, per-lens draws, fills, the reseed, the hint, kerning, the 2M request,
  the report (`Contract2Test`; gated on DamageCaps bit 5 + DRAW2, so the APK runs as before on the contract-1
  build). **Reader's page staging built 2026-09-16 evening (§66; APK 0.57)** — the first build under the §3.8 protocol,
  read once on glass (§66.1: a staged notch 12 B / 79 ms); the second read is item 2. §57 holds the journal baseline. Adam ruled §57's eleven decisions as recommended.
- **What the reviews changed that a fresh session must not undo** (the why is in the cited item):
  - a mode-24 partial refresh is taken only while the panel already shows the whole previous frame — a refused
    batch, stock content, a release point and the overlay each mark it stale, in the C, the model and §4; the
    vectors compare what the LENS shows (`"panel": true`) (§62.1)
  - the phone's own firmware model is told the build's contract (§62.3 item 1); an atlas upload is read back
    before its fonts go live, owed by EPOCH, by bytes and by acked MESSAGES — a repack keeps the `GlyphAtlas`
    and moves every offset (§62.3 item 4, §63.1, §64.2 item 2), a table patch moves no byte mark (§73); the lapse itself, not a status string, is the recovery (§64.2 item 3)
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
  - since §73 a face's table names real glyphs only for what the face has drawn: the recorder splits a string on
    the ENTRY (`Entry.packs`), never on the contract's range; a live face's entry changes at `promote` — on the
    loop, after the ack and (where the build can answer) a read-back — or at once when the new glyphs are bytes
    its table already names, and is folded at a lapse or a repack while nothing is live; `measure` follows the Cached text ROW (`cacheMetric`), never what is live,
    and `draw` places everything by the same walk (`CachedText.layout`) — never give the two separate walks;
    a strip is staged after its face has gained what it shows; a pin that WAITS lets an unrelated cue (the
    clock's new digit) do the mutated code's job — decide by frames; the desktop has no kerning (AWT's is 0):
    a kern defect shows only on a kerning test rasterizer (`KernedText`, `Contract2Test.KernText`) and on the
    phone; a `--transport sim` instance is NOT isolated: it takes the service's own ports, the real
    qBittorrent and music database, and the machine's REAL tmux and files — beside the live service run it only
    by §73's recipe (a copy of the install, a scratch `-Duser.home` whose `config.json` moves the four ports
    the desktop binds and the data paths and names the real shelf, `TMUX` unset, `TMUX_TMPDIR` at an empty
    directory; the config the walks used: `captures/2026-11-09-session/sim-walks/config.example.json`), and
    never tap inside Tmux or Files
- **A live defect fixed for APK 0.50 (§59):** the right lens rebooted inside the atlas skip's window and refused
  every cached draw (Main on the left lens only); the start reads RIGHT's uptime before the carry decision.
  Workaround on 0.49: phone Bluetooth off for 90 s.
- **App layer:** Main · Settings · Reader · Tmux · Files · Torrents · Music · Games · Feed (`WINDOWS.md` and the
  per-window records); `POPOVER.md` is a spec, not built (planned for Phase 6a). **The battery at 0.65's R4, the final battery (2026-11-12, simulator; `HANDOFF.md` §77.3):** core 1023 of 1023,
  run as four class groups (one command is ~600 s of gradle, past a ten-minute tool limit) · desktop 35 ·
  `--selfcheck` 485 PASS ×3 · 57 renders · lint 0 (`--selftest` 7 of 12 rules covered) · `:phone:assembleDebug`, then
  `tools/check_apk_wiring.py` 17 lines ok (the APK carries 65 / 0.65) · the tools' self-tests `research/mutate_core.py` 45,
  `tools/journal_report.py` 19, `tools/journal_pull.py` 26, `tools/check_apk_wiring.py` 26 · mutation lists `--check`
  89 / 73 / 76 / 56 / 100 / 48 / 59 / 10 / 73 / 27 / 20 / 4 / 10 (thirteen, `-77-p5` … `-77-r4`) · rates 20 of 20 for
  `SessionBoundaryTest`, the Tmux classes, `PreludeTest`, `FidRingStartTest`, `PendingAckReleaseTest`,
  `QueueWhileDownTest`, `SilentGlassesTest`; `MirrorSpanTest` and `GlyphSubsetShellTest` 5 of 5; `ParkedRenderTest` 18
  of 20 at R4 and 40 of 40 after the lead's fix to its slide test (§77.3) · the fork's 34 vectors / 342 steps, the self-test form on 26 (at the vector pass). Not run in 0.65's
  build stages (the lead's before the push; last at 0.64's R3, §76.13): `--epub-check ~/books` (every book OK, 2,580
  of 2,604 images decode) · music/games/feed — all four run again by the lead before 0.65's push (73 books OK, 2,580 of 2,604; §77.12). The phone ran 0.64 on 11-12 (Adam's word; the lead's "0.63" was an assumption) and 0.65 from his install that evening (to be read from the journal); the PC runs `b833713`.
  Known rate misses (§54.5, §54.7; and `MusicWindowTest.windowGrammarAt480`, 1 of 4 full runs on 0.64's P4
  trees, cause not proven — its failure now prints its state, §76.7): `FeedWindowTest.deepLinks…` and
  `…browseThroughTheKeyboard…` (§49.6), a seam test race (`SeamSessionTest.kt:82`),
  `Review20260905Test.cashingOut…` once (§61, cause not found) — run the core suite alone; a repeated
  `:core:test` answers FROM-CACHE (`:core:cleanTest :core:test --no-build-cache` for a rate). The Feed miss run BY
  ITSELF fails 8 of 10 on the tree before and after §66 (worse than in the suite): measure a rate like with like.

## Measured on glass (grade M)

Time to first ACK per gesture, the phone's journal (`tools/journal_report.py`). **The ack is the link's own
share of the wait, not the wait** (§64.4): the report's `first wait ms` column — submit to done — is what the
gesture actually took, and on the reference journal a WINDOW notch is 230 ms median / 1,004 p90 there against
75 / 523 as its ack (§64.4). Price a window against the wait; these are kept because the history is in them.

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

## 🔴 The next session — Adam installs APK 0.65 (staged 11-12 16:58; his word; `HANDOFF.md` §77, §76), then its first day's journal is read

0. **APK 0.65 (`HANDOFF.md` §77) is BUILT, pushed (`b833713`) and STAGED (11-12 16:58, versionCode 65); Adam said at
   ~17:05 he was installing it (not yet read from the phone). APK 0.64 (installed by Adam on 11-12, §77.12's correction) is kept as `~/.damage/backup/damage-wm-0.64.apk`. Installing is Adam's
   word.** 0.65 is 0.63 plus §75.6's builds, two review rounds'
   fixes (0.64), P5–P7, the fork's vector pass and review pass 3's fixes, all measured on the simulator. **First, after the install:** `/log` shows
   "uncaught-error record installed (previous handler …)" at the first start and the journal a `process` note with
   the same words — the only read of the phone half; the phone's first start renames its journal (grown since
   August, size unread), so `&tail=2000000` returns only the new process's lines, starting with the `journal` note;
   pull the archive in a quiet minute (`python3 tools/journal_pull.py --live`; it shares the radio). PRESENTED wants
   arming after any install (his word: it is a probe). **If it misbehaves:** Global `Cached text` off is the
   switch. Going back to 0.63's code is NOT a reinstall — the phone refuses a lower versionCode (63 under 64 or 65,
   `phone/build.gradle.kts`): it needs an uninstall first (which clears the app's data, the journal with it:
   `python3 tools/journal_pull.py --live` first), a rebuild of the older tree under a higher versionCode, or an adb
   downgrade — Adam's call. **Never run `:phone:stageApk` unless a new build is meant to be staged.** **What the
   first day's journal answers (§76.11):** the wear event's arm and flag; op 7's answer with the switch off; field 10
   and a field-14 zero in a READ; sid-0x81 pushes; the frame that names the start's level (`CLAIMS.md` row 283); the
   menu box's first flush against 0.63's 2.6 KB; `proof`, `two-planes`, `dearer`; the first `count` notes and any
   `cache/*` notice; **and 0.65's own (§77.11):** the fid-ring clear's `control` notes at each start, and from the
   next telemetry read on the refusal record an earlier session left (`refMode=19 refReason=5` on 0.63) gone with
   `diag=` 0x0 (P5c's reading of the start order, not measured); the `loop` note against 0.63's loop cost (§76.5);
   any `stall` "depth 2" or "queued again" line. **The PC deploy is DONE** (11-12 02:48, §76.13; the old jar kept):
   the journal was set aside and `instance.lock` taken; the late-handshake pair ("77 newer there" / "77 wanted") is
   READ on the PC — 0 in seven hours against 545 before (M, §77.6); the flush gap at each handshake waits on the
   phone's journal. The PC deploy of 0.65's jar is the lead's, after the push. **The timed rest hour** is his
   (`DAILY.md`'s recipe).
   **0.63's read (2026-11-10, `HANDOFF.md` §73–§75; kept for its history).** What to read on 0.63: the
   `atlas` notes ("N face(s) gained glyphs", no "repacking" where 0.57 had two a day), `journal_report.py`'s
   atlas account (messages, bytes, repacks, `unpacked`), the regime notes (one "measured —" line a session, flips
   only when the link changes — and how long a SLOW regime outlasts a recovered link through a quiet stretch:
   0.57 held one 48 minutes, and one morning over three hours, §73 addendum), a book opened right after a
   session start (no line cut with the mark), and **the wake after a drive** — on 0.57 each wake re-sent the
   atlas, 190 KB over 31 s with faces left as pixels (§73 addendum): the same minute on 0.63 is the comparison.
   The in-place table patch on the glass itself is read (`v2-tablepatch`, 11 of 11 on RIGHT, 2026-11-10).
   **Four things the first 22 minutes leave to watch** (`HANDOFF.md` §73's last block;
   `captures/2026-11-10-apk063/final-check-findings.md`):
   (i) *a box that goes out as pixels.* Music's forward-plane box (248×246) shipped whole, 2.5–2.8 KB, on each of
   the three paints that held host-drawn characters — once for two characters, twice as a gesture's first
   flush (12 proof misses in the 22 minutes). Cause not established. The read that splits it: the same box opened
   with `unpacked` 0 — a few hundred bytes of draws means the host characters cost it (0.63's packing), about
   2.5 KB with `proof` means the box's own. **TRACED (`HANDOFF.md` §75.3): the box's own — it is the shell's
   context menu, in any window; 37 of 52 paints had `unpacked` 0; two causes older than §73, reproduced on the
   simulator; two fixes proposed — built in APK 0.64 (`HANDOFF.md` §76.1, P1; the simulator's box 301 B → a 54 B
   base and 10 draws; on glass unread);**
   (ii) *the read-back runs ahead of the count.* Entries went live on a read-back that showed the generation
   one or two short 13 times in the 22 minutes (4 of the first 7 read-backs; the note says "pending, asked
   again in 500 ms"); every write was counted later (all 23).
   That is the measured side of item 12's question (prove a patch by the count before it draws) — **ruled
   2026-11-10: no gate; the re-read everywhere, a notice and one re-send (§75.1) — built in APK 0.64 (§76.1, P2: one
   reader of the count, `pumpLedger`; the read-back judges presence, size and flags); by 18:37 it
   was 30 short readings in 35 read-backs over 42 writes; the 42nd was not counted until the next write's
   read-back, eleven minutes later.** On 0.63, outside the
   Reader the "asked again" re-read does not run, so there a write that never landed would leave only an
   unresolved `pending` note;
   (iii) *the phone's CPU.* On the Music time tick `handlerMs` reads 25 / 31 ms (median / p90) on 0.63 against
   14 / 19 on 0.57 the same afternoon — not like for like (0.57's atlas had been dropped by the self-test; a
   freshly installed APK is one of ten candidates). The like-for-like is 0.63 itself with Global `Cached text`
   off, then on, Music playing — his switch. `textMs` times the host's draws only, not the layout walk. **The
   number to watch over a day: the first flush's `handleMs` + wait for input-started bursts, per window,
   against the ~250 ms bar** (210 ms median, n=5, in the first minutes);
   (iv) *the link per KB at work.* 60–72 ms/KB that afternoon on both builds (0.57's regime flipped seven
   times in two hours; 0.63 went SLOW at 17:25:11 on 72 ms/KB, n=9) against 32–38 at home and the day before,
   the floor unchanged at 55–58 ms. **Read 2026-11-10 (§75.1): at his station, the phone in his pocket, 60 ms and
   56 ms/KB over 18:10–18:30; at the lunch table, the phone in front of him, 39 ms and 46 ms/KB (n=10 for the
   per-KB term) — the floor differs clearly, the per-KB term inside the spread (I: place, pocket and music are
   not separated).** The edges (65 / 35) are held for a few days of regime notes (his ruling).

Phase 2 is on the glasses and first light is green (§65). Reader's page staging is built and read once on glass
(§66, §66.1). **The status cell is built (§69); APK 0.63 (it carries 0.62's §72 work, 0.61's streamed journal route
and 0.59's staging fixes) is INSTALLED since 2026-11-10 17:16 (0.57 until then).** After installing: the session start's
`queue` journal note says what was held or dropped while down; the shelf should come up at once with the PC away. The glasses ran flat in an unplugged case on 09-21 (§70): Adam ruled a
low-battery phone notification and a rest mode in the case — the notice and rest mode's build 1 are built in APK
0.64, not installed (item 10). Nothing needs
flashing; the protocol (`CLAUDE.md` "Scope, build, review", `FORK.md` §3.8) binds every build.

1. **The status cell as states, every event a notice — BUILT (`HANDOFF.md` §69), Adam's eye owed on 0.63 (the first install since 0.57):** the
   cell says one derived word (`no link · silent · rebuild · no lease · halted · fault · link slow · ok`) with the
   echo at its right (`tap · dbl · up · down · hold · rel`); an error is a notice with its whole text, one per class
   per episode. Ruled 2026-09-21: a full atlas is a journal note; a lost DRAW2 drops the session to v1 shapes. The
   journal's three weeks say no fault class is chatty in daily use (§69). To read on glass: the cell's legibility at
   140 px, and the first real notice.
2. **Page staging on glass, second read (0.63, which carries 0.59's fixes; §66.1) — Adam's read.** The first read (0.57): a staged notch 12 B / 79 ms ack
   against 2.7 KB / 307 ms for pixels — and the ledger crying wolf (bumps land up to ~1 s after the ack), strips of
   12–34 KB against a 32 KiB reserve (still crying on 0.57 on 2026-11-04: "the generation 2 short … never landed — 1
   record(s) dropped"). With 0.63 installed, arm PRESENTED (`probe:flags=0x8005` — a probe: Adam's word first; flags in force read 0x4), open the book, read: the
   `stage` notes (proven · pending · shortfall · off — a shortfall now means it), the `page staging:` line, the
   notches' WAIT column by shape, the worker µs of a staged notch (`present` records, the record's decode cost), the
   atlas churn now at 96 KiB (on 0.63 a face is packed with what it draws: §73). Adam's eye: a notch that lands
   whole (no strip filling in), no stale rows, no lens disagreement. A shortfall note ("never landed") or "off for the
   session" is a finding to read at once — it names a cache write the glasses took the ack for and did not keep.
   On 0.64 a `stage` note means a strip was involved, and the count notes of an atlas-only reading are kind `atlas`
   (`count ok · pending · short (settled reading N) · short · short again · over · re-based · unread · off`,
   `re-send counted · landed late`; `HANDOFF.md` §76.1, P2).
   The two quiet-pass items §66 left — the atlas's glyph-subset packing (the churn) and the link estimator's
   regime flips — are BUILT (§73, APK 0.63). Optional beside it: `lint.py` rules for the v2 budgets; a Global row
   for the hint's A/B.
3. **Adam's reads for T2:** 0.63 installed (item 2 first); the day's journal
   (`journal_report.py --since 2026-09-16`) — the ack curve on 2M over a workday, the cacheMiss breakdown (`proof` and
   `planes` at zero is the exit — on 0.64 `planes` cannot occur and `two-planes` and `dearer` are new: the exit's
   wording is his call, `HANDOFF.md` §76.10 item 2), battery %/h on 2M against 7.4–7.8, the in-case reboots against §65's baseline (five
   in 11 h, three of them 5–7 min after docking); **the §62.7 retention check** (shell up, glasses into the case for a
   minute and out — is the first frame after the wake whole?).
4. **The one-byte finding (§65) — CLOSED (§73, `CLAIMS.md`):** it was the self-test driver's expectation. Both
   cache-write handlers return before the generation bump when a list carries no data; the glass did what the C
   does. `glassdrive.py selftest:` is corrected; a re-run of `v2-order` on glass would show 20 of 20 (optional).
5. **One focused read, not a fifth review:** the two review-invented mechanisms — the atlas read-back epoch
   (`Shell.kt` `atlasCheckOwed`, `AtlasCarryTest`, `AtlasRepackTest`) and the fork's busy state word (`damage_draw.c`
   `damage_slots_enter`/`leave`, `cfw_context.h` `DMG_BUSY_*`) — ~200 lines, the highest prior in either repo.
   **DONE in 0.64's build (`HANDOFF.md` §76.4):** the Damage half in review 1a on 0.64's code (RC-4, RC-5 fixed in
   R1); the fork's half in sweeps 1 (FBW-1…4, findings only — a change needs a flash; FBW-2 corrects `FORK.md`).
6. **Class sweeps across the whole codebase** (`CLAUDE.md` protocol 3 and 5), one grep-driven pass per class: a shared
   field read twice in one decision; a wait with no escape from a write that keeps failing; state with no clear at a
   session, lease or epoch boundary; a guard keyed on an identity that does not survive its event; a fix applied to
   one sibling; a gate green over code it never runs. **RUN 2026-11-10/11 (`HANDOFF.md` §76.4), with loop cost: 10
   confirmed in the first three classes, 24 in the rest; none covered by 0.64 — they are APK 0.65's P5–P7, built
   (`HANDOFF.md` §77; on the simulator, not installed).**
7. **The full mutation sweep — RUN 2026-11-10 (`HANDOFF.md` §75.5): 96 single-line guards, 43 caught, 52 caught by
   nothing (9 of a kind the host cannot reach), 1 leaves the harness not returning; 11.5 minutes, not ~2 h** —
   `(cd ~/damage-cfw && python3 tools/mutate.py)`, which uses no gradle. Every DRAW2 check and 10 of 13 lease checks
   are caught now (§64.1 had 39 of 106 on a different base). **Left: a vector pass, smaller than the count
   suggests** — at least 13 of the uncaught are a loop's second read of a bound its first pass checks, which a
   fixed vector cannot reach; the clear cases are the v1 cache ops' lease checks; the rest wants a read per site.
   **The pass is designed (`HANDOFF.md` §76.4: six vectors, 83 steps; §75.5's "inseparable twins" corrected) and
   BUILT with APK 0.65 (§77.4): 34 vectors; the lead's sweep at the fork's local `f9ba7c6` reads 69 caught of 96, 26
   not, 1 not returning (caught with the call kept).**
8. **The instruments (§64.6) and the on-glass self-test:** `firmware/fuzz_vectors.py` and `~/damage-cfw/tools/mutate.py`
   print their own instructions; `glassdrive.py aphone TOKEN selftest:firmware/vectors/NAME.json` runs one vector on the
   glass — since §65 it synchronises on the firmware's counters (the begin's zero, each live write's generation bump
   against the C's expectation) rather than on sleeps; a vector's live writes drop the shell's atlas for the session
   and nothing presents during a step, so run it in a quiet minute.
9. **The cache's size (§67; the demand is measured, §66.1):** the glyph-subset packing is BUILT (§73: a face ≈3 KB
   against 16, M on AWT; on glass since 2026-11-10 17:16, first look only: nine faces in 25 KB after 22 minutes) — a day's read of the atlas churn on 0.63 comes first; then op 5's cap → 256 KiB and a largest-block telemetry
   figure ride in Phase 3's candidate (`FORK.md` Phase 3) — a flash of their own only if the read on 0.63 shows staging
   starved. Before that budget: fields 7–9 journaled before the carrier CREATE, after it and after the first frame
   (where the carrier's two buffers live is U again, `CLAIMS.md`). **With it in that candidate (§68): a deflated cache
   write** (cache writes were 61 % of the image lane's bytes, deflate takes 43 % off); the kept compression context only
   after `research/flushdecomp.py` has read a capture of Phase 2 traffic.
10. **BUILT in APK 0.64 (installed 11-12, Adam's word; `HANDOFF.md` §76.1, P4): the low-battery notice (the phone channel "Glasses
   battery") and rest mode's build 1 — the reads (`wear`, `case`, `sid` notes), op 7 at each session start, a
   telemetry read a minute while painting, the `rest=on|off` probe (his word on glass). Build 2 (resting by itself)
   waits on what build 1 reads: the first day's journal (item 0) and the timed hour (`DAILY.md`'s recipe).**
   **The design pass is DONE (2026-11-10, `HANDOFF.md` §75.4 — the facts, the lead's calls, the envelope; build
   from it, not from the text below, which is the brief it answered): the notice as ruled, on its own phone channel
   and behind neither `Phone notifications` nor `Battery alert`; rest mode in TWO builds — build 1 only reads (the
   wear event, the switch if the 60 s READ carries it, the case's pushes, the gate pattern, a `rest` probe for a
   timed hour), build 2 rests by itself on the trigger those reads allow. Stock sends the wear event only while a
   settings byte is set (V) that reads as its wear-detection switch (I; `CLAIMS.md`); whether it is on his pair is
   unread. The transport's watchdog has to stand down with a rest, or it rebuilds the session ~22 s in. `DESIGN.md`
   §0's "wear / unwear not wanted" now carries the rest-mode exception of his 09-21 ruling — his to confirm.**
   **A low-battery notification (Adam's ruling 2026-09-21, `HANDOFF.md` §70; designed in `DESIGN.md` §9.3; built in APK
   0.64):** the glasses below 20 % → a phone notification plus a notice on the glasses, not gated by
   `Notify · Damage`, once per discharge (re-armed by `charging` or ≥ 25 %). Under the protocol: what re-arms it at a
   session boundary, and a reading that flickers around 20 %. **And a rest mode in the case (his ruling, the same
   evening; `DESIGN.md` §9.3):** on the CFW's not-worn event the shell stops painting and releases the lease — the
   Silent-Mode path (§36/§38) under a new trigger — and a session rebuild on worn; `charging` is not the trigger (a
   dead case says nothing). The transport consumes neither the `wearnotify` event nor `WEAR_QUERY` today. Under the
   protocol: the wear event's shape read from the C (`settings_ext.c :: faceclaw_send_wear_event`) and pinned in the
   simulator before the shell keys on it; what a worn event during a rebuild does; a pair that flickers at the lid.
   **Read §74 first:** four cues that need no flash (the frozen `transferUs` under advancing `presents`, the case's
   sid 0x81 pushes, the wear event, a phone-side Silent Mode write) and upstream's three traps (a session's first
   wear report is not a transition; wear state does not cross a session; `charging` reads 0 when docked and full).
11. **§71–§72's calls — RULED 2026-11-10, as recommended (`HANDOFF.md` §75.1); (a), (b), (c) BUILT in APK 0.64
   (§76.1, P3), with one cut — no value sums: a different value inside 2 s is left and named by nothing until that
   key's next edit ("sums decide" is offered, §76.10 item 4):**
   (a) the sync handshake treats stamps within 2 s of each other as equal, on both sides (the 2 s is the lead's
   figure; it judged equal stamps
   "newer" by a per-handshake skew estimate with no tolerance and re-sent ~77 records a handshake — M, §72; the
   phone's log shows the stored stamps drifting a second at a time, §75.1); (b) the phone's journal rotates at
   process start once past 64 MB (the lead's figure), nothing is deleted, archives are served by name and beardos
   pulls copies; (c) a record of an uncaught error: its trace written to a file at the fault, journaled by the next
   start (the phone's log
   lives in memory, so an ended process leaves nothing behind today, §71).

12. **§73's calls — RULED 2026-11-10, as recommended (`HANDOFF.md` §75.1; the eight with their reasoning are §73's
   "For Adam" block, its "Not built" list the rest).** Everything stands as built: what a face is packed with (no
   row that packs whole faces), the eviction for a face in use, the cache-metric layout (his eye on a Reader page
   still owed), the estimator's ring, the settle-window re-ask, `simContract`. **A table patch is not held back
   until the generation count proves it:** the re-read runs everywhere, and a write the glasses did not count is a
   notice plus one re-send — built in APK 0.64 (§76.1, P2); the gate waits for a real shortfall in the journal. The estimator's
   edges (65 / 35 ms/KB) are held for a few days of regime notes; the learned character sets are not persisted.
   The four statements that described the code before §73 are REWRITTEN to what runs (`DESIGN.md` §6.3 and its
   Cached text row, `CLAUDE.md`'s texture-cache bullet, `overview.md` §4).

13. **2026-11-09/10: the slow stretches, the two silent modes, and a Silent Mode the phone was never told of
   (`HANDOFF.md` §73 addendum, `CLAIMS.md`; the tails and logs in `captures/2026-11-09-journal/`).**
   *Measured:* the small-flush ack about doubled in four stretches on 11-09 — the drive 13:33–14:19, and
   15:57, 18:33, 18:52 for 5–7 minutes — and in no other of 39 hours; the firmware's Silent Mode works at its
   entry and its exit on the Phase 2 build with 0.57 (22:32 the push, the shell asleep, the lease released, no
   flush for sixteen hours; 14:19:38 the OFF push, rebuilt in 9.7 s); the battery: 2.7 %/h in the firmware's
   Silent Mode, 7.4 %/h with the lease held, 7.2 %/h in the shell's OWN silent mode (the double-tap at Main),
   where he spends most of a shift.
   *Adam's word (11-10):* all four stretches were out of Wi-Fi (the drive; his break about 15:55 and his lunch at
   18:30, outside); for each drive he puts the glasses in the firmware's Silent Mode with the both-temple press,
   wearing them; the case never leaves home.
   *What follows (I):* (i) the slow link's leading candidate is the phone's own radio with Wi-Fi on and not
   associated, or weak; (ii) **on 11-09 13:30–14:16 the glasses were silent on his face and the phone was never
   told — no push, nothing from the 60 s READs, and 527 frames acked and counted: on this build a Silent Mode
   can go unreported and frames are not refused in it, so the shell paints into dark glasses with nothing to
   stop it.** The level reading 100 % until 14:27 was the gauge's top band (§74), not the charger.
   *This item was wrong three times before ("the shell did not sleep"; "the drive"; "on charge until 14:16") —
   each was the first reading that fit one file.*
   Open:
   (a) **his next presses, read from the tail** (he need not report them: a `silent` note within ~2 s of the
   last input, or frames going on): **the leaving-work press of 11-10 22:23 was reported on 0.63 as on 11-09 — the
   `silent` note 10 s after the last acked flush, no flush after (n=2 for that case; `HANDOFF.md` §76.5)**; the
   case that failed, the first press after the glasses come out of the case at home (it happens only on a day
   he cased them awake), is still unread. On 0.64 a `silent` note is the firmware's Silent Mode only: a rest writes
   `rest` notes, an awake shell's refused rebuild a `keeper` note;
   (b) **why the push was missing:** §74 A2 lists the stock branches (the reminder page showing, the
   lens-to-lens send failing, the notify's own checks, the first seconds out of the box); the glasses' own log
   stream would name the branch — a probe, his word first;
   (c) **a second cue for the shell,** so a missed push cannot leave it painting — design work for item 10
   (§74 A1's frozen `transferUs`, the READ's field, a refusal); which of them moves in Silent Mode is unread;
   (d) **the Wi-Fi test — DONE and CLOSED 2026-11-10 (`HANDOFF.md` §75.1): still slow outside with Wi-Fi off
   (n=64), which weakens (i) above; Adam: "no more tests, this is minor" — do not re-open it.** The brief: one
   outing with the phone's Wi-Fi switched off, the next as usual, both read from the tail; a BTSnoop capture across a slow stretch (the unmeasured table's row 5) only if that leaves it open;
   (e) for his design, not a defect: the shell's own silent mode saves no battery against the lit panel; the
   press before the case is rest mode's manual form (8,281 flushes into the case the night without it, none
   the night with it).

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

Why (M, §54.4, the 1M link): two full 247 B packets per served connection event on LEFT, served every 60 ms in
the busy sessions ≈ 8.2 KB/s. PC-direct BlueZ sends ~6 packets per event (~50 KB/s). The firmware's receive path parses
one packet per ATT write (§48.1, grade C). `overview.md` §5.2's `ms ≈ 60 + bytes/50` is PC-direct only — price
nothing with it. Of the fork's levers for this, F1.6 became Phase 2's link edits (LE 2M), F1.7 is mode 24, and
F1.8 is dropped (`FORK.md` Phase 1's table).

## Standing rules (pointers)

The rules are `CLAUDE.md`'s — the review rules, the compositor discipline, the latency standards, the protocol.
Three that are not there: `handleMs` in the journal INCLUDES the assemble (§35.1); in a live walk never scroll in
Music's root, and the tmux pane's SECOND tap is the keys list (a third sends a key); `tools/journal_report.py`'s
per-gesture section judges every window (§42).

## 🔴 Still unmeasured on glass (the fork's Phase 0 takes most of these)

| # | what | why it matters |
|---|---|---|
| 1 | **Safe area** — draw a border, shrink until fully visible, store it | `DESIGN.md` §2.2b: 480 vs 288 is a *calibrated setting* |
| 2 | Ring **fast-spin coalescing + event-rate ceiling** — **the ceiling is read (§74): the glasses drop a report under 100 ring ticks after the last one**; the tick's unit and the daily effect are unmeasured | the focus model's limits; `FORK.md` Phase 4 |
| 3 | **Comfortable disparity** — ramp 0/4/8/12/16 | stock FAR does NOT stack (§48.1, verified); the ramp itself: `FORK.md` T3/T4 |
| 4 | **The rect budget of 5** (graded I) | derived from `cfw_diag()`, never observed; failure is silent |
| 5 | **A two-arm BTSnoop capture spanning a drop, and one with the ring asleep** (the packets-per-event read is done, §54.4) | one drop from the radio's side (§42.2); the 60 ms cadence question |
| 7 | **msgId-255 behaviour under CFW** | it ends the link on stock |
| 8 | **Chrome legibility** at the real faces on glass | renders cannot answer it |
| 9 | **WEA/CMAS visibility to a normal Android app** (Pixel 10a) | `DESIGN.md` §4.5's emergency promise rides on it |
| 10 | **Connected RSSI** on glass | the status bar's link cell |
| 15 | **Is the sid-0x01 prelude required** by the CFW before CREATE? (graded U) — **the eaten CREATEs have another cause (§74): the parser drops a CREATE while app 0xE0 is still registered**; what clears it is unread (0.63's first start, 11-10 17:16: five re-sends, 10 s, acked 1.5 s after a SYSTEM_EXIT event — n=1) | three eaten CREATEs per cold start ≈ 6 s (§34.3) |
| 21 | **Temple long-press accident rate** (gloves) | §1.2's bare-long-press no-op guards it |
| 24 | **Does the firmware enter Silent Mode by itself** (wear detection, idle)? | the journal will say |
| 25 | **The arm rebuilds' cause** (§42.2) — **they are REBOOTS** (F1.2's uptime, §65: five on 2026-09-16, all in the case, three of them 5–7 min after docking, uptime ≈100–105 min at each; LEFT's uncounted) | **the case's top-up cycle (I, strong: §70 — every ~100 min the battery dipped to 98–99 %, a `charging` reading followed, the glasses rebooted; the cycle stopped the hour the case's own battery ran out, and so did the reboots)**; a stock in-case restart on charge start is the mechanism (U) |
| 26 | **The wake loop's cause** (§42.3) | the `keeper: start failed: …` notes name it |
| 28 | **True user-perceived latency** (ring press → visible change; the ack is only a lower bound) | `FORK.md` M0.2 |

**Cheap probes nobody has run:** the CFW logger service (sid 0x0F — M0.5) and the file-export service (sid
198/199 — `NOT_SUPPORT` is a safe answer).

## Upstream CFW (checked 2026-09-15 — `HANDOFF.md` §58; again 2026-09-19 and 2026-11-10 — §67, §74)

**Checked 2026-11-10 (`HANDOFF.md` §74; read-only, nothing built; the pinned clones are fetched to g2flash
`845f665`, faceclaw `8b70585`, SybilSight `6be5d70`, openCFW `55c7582ed` and NOT moved):** upstream rebased onto
stock 2.3.0.24 and gained on-glasses cache compaction, display lists and an animation timer; official firmware is at
2.3.2.14 in the archives (2.4.0.24 exists, unread). **D1 stands** (433 KB of room on our base against 34 KB on
2.3.2.14; the corpus is still 2.2.6.10 only; no official release touches an open item). What it gave us, by item:
rest mode (item 10) — the stock panel gate already drops refreshes in a powered case, `presents` advancing under a
frozen `transferUs` is the cue in our own telemetry, the case reports itself on sid 0x81, and Even's schema has a
phone-side Silent Mode setter; the case's own firmware stops charging at 98 % and resets a lens that stops
answering it, and the gauge reads 100 from raw 97 (unmeasured row 25); the glasses drop ring reports under 100 ring
ticks apart (row 2); a CREATE is dropped while the app is still registered (row 15: not the prelude). **For Phase 3's
candidate:** the stock "already fast" test that keeps our 7.5 ms profile from ever being asked for, with a
correction to our link edit (3); a context that survives a restart; the display gate's ownership test. **Do not
update the R1 ring past 2.2.x while the glasses are on 2.2.6.10.** Upstream's display lists and animation are design
input for Phases 3–5.


Eleven g2flash commits past our pinned `a5d1c31`, all on stock base 2.2.9.22. **`c63710c` (2026-09-10) reconfigures the
link in three in-place edits — LE 2M enabled in the startup feature command, the fast profile 7.5 ms / latency 0, the
idle slow request forced to fast — ~41 KiB/s measured there (5× our 8.2); our three sites are read at instruction
level; **Adam's ruling: built into Phase 2's candidate** (he trusts the public release). The 08-31 lost-ACK fix is 2.2.9-only.
`reference/g2flash` is fetched, not moved — a pull breaks `research/verify_cfw.py`'s 2.2.6.10 pins. **D1: the fork
stays on 2.2.6.10** (the decompile corpus is 2.2.6.10); 2.2.9's gains can be ported later.

**Checked again 2026-09-19 (`HANDOFF.md` §67):** `f017fab` grows upstream's cache to 256 KiB in the stock malloc's
arena with u32 offsets and **new mode numbers 18/19/20 that collide with ours** — nothing to port (`off4` reaches
256 KiB; that arena has 71–75 KiB free here). Its private message SID (`HANDOFF.md` §68, priced with
`research/flushdecomp.py`): no gain on the ack floor, parked until Phase 4; its kept compression context and a
deflated cache write are the parts worth having, neither needs the SID.

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
- **`/etc/init.d/damage`** (2026-11-09, `HANDOFF.md` §72): `-XX:+ExitOnOutOfMemoryError -Xmx2g` in `command_args`
  (supervise-daemon respawns after 5 s, without limit) and `retry="SIGTERM/30/SIGKILL/5"` (the default stop sent
  only SIGTERM; a JVM stuck in its handler stayed up holding every port). The original is kept as
  `~/.damage/backup/init.d-damage.pre-72` (not in `/etc/init.d`, where OpenRC would read it as a service).
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

**`aphone` no longer resolves on beardos (2026-09-21: Adam moved DNS from Tailscale to the VPN): use the phone's
Tailscale address — `tailscale status | grep aphone` (100.113.243.91 today) — wherever a line below says `aphone`.**
**Every `glassdrive.py` line below drives or writes to Adam's glasses through his phone (a probe, a self-test and
a gesture alike): run one when he has asked for it or is there to watch, never as a routine check. The `curl`
reads are read-only.**

```
sudo rc-service damage status                         # the data host / standby; deploy = ./gradlew :desktop:stageJar && sudo rc-service damage restart
ps -C java -o pid,lstart,rss,args | grep damage.jar     # the service's JVM and its flags (§72) — never `pgrep -f 'java.*damage.jar'`: it matches your own shell
./gradlew :core:test  ·  ./gradlew :desktop:test
desktop/build/install/desktop/bin/desktop --selfcheck # after ./gradlew :desktop:installDist; run it more than once.
                                                      # 485 checks (0.64): it runs the script TWICE since §63 — contract 1, then a
                                                      # Phase 2 build with cached text ON (the v2 ops had no oracle before)
desktop/build/install/desktop/bin/desktop --help      # the usage text since 0.64 T0; an argument it does not know is refused (exit 2) — before T0 any
                                                      # unknown argument started a second shell (§76.6). A shell start on a data directory another process holds exits 4
python3 tools/lint.py                                 # SYM/GEO/BUD, exits 0; --selftest names what it covers
TOKEN=$(python3 -c "import json;print(json.load(open('/home/user/.damage/config.json'))['token'])")
curl -s "http://aphone:7403/journal?token=$TOKEN&tail=2000000" | python3 tools/journal_report.py -   # the phone's journal (its last 2 MB: 6 h of a busy day, a day of a quiet one), per-gesture numbers included; since §66 a `page staging:` line
                                                      # the phone runs 0.63 since 2026-11-10, which streams the journal; keep &tail= anyway (the file is large), and ALWAYS on a build before 0.61 — it ends its process on a whole-journal fetch (§71)
                                                      # after the 0.64 install &tail=2000000 returns only the new process's lines, starting with the `journal` note
python3 tools/journal_pull.py --live                  # 0.64+: the archives (GET /journals; /journal?…&name= serves one) and the live file → ~/.damage/phone-journal/; before an uninstall; it stops at a /journals 404 (0.63)
curl -s "http://aphone:7403/log?token=$TOKEN&tail=400"                                 # the phone's log (0.41+), no adb
python3 tools/glassdrive.py aphone $TOKEN --pace 2.5 double wait:3 snap:/tmp/a.png …    # drive the glasses; snap before every tap
python3 research/verify_cfw.py                        # before any flashing conversation: the a5d1c31 image's provenance (the rollback behind Phase 1's); the INSTALLED image is the fork's — tools/verify.py below
python3 tools/glassdrive.py aphone $TOKEN probe:telemetry=read   # probes (APK 0.45+): diag=show|hide (blanked the display on a5d1c31, §54.8; not run on the fork's build — not without Adam) · logger=on|off · phy=2m|1m · telemetry=read · flags=clear|probe|0xNNNN; 0.46+: cache=info · selftest=begin|end|step:HEX; 0.51+: cachesize=KiB · selftest=live:HEX (a vector's cache write); 0.64+: cache=miscount · wear=query · rest=on|off (each his word on glass) · sim=… (SIM target only)
curl -s "http://aphone:7403/journal?token=$TOKEN&tail=2000000" | python3 tools/journal_report.py - --since 2026-09-14 --glasslog   # drop &tail= for the WHOLE journal on 0.61+ only (§71) — never on 0.57
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
(cd ~/damage-cfw && python3 tools/mutate.py --list)   # §64: the refusal guards, and which gates prove them (the whole sweep: about 12 minutes, §75.5)
python3 research/fwread.py dis 0x473c44 0x473d70      # the stock image at instruction level: dis · fn · word · refs · calls · strings · owner · sha (our bytes vs the corpus header)
python3 research/perevent.py                          # M0.3: packets per served connection event per arm, from the captures (§54.4)
python3 research/flushdecomp.py                       # §68: queue -> ack per flush at HCI level; bytes as sent vs deflated vs one kept context
./gradlew :core:cleanTest :core:test --no-build-cache # a REAL re-run of the core suite (a plain re-run is answered FROM-CACHE)
```
