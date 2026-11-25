# Damage (`damage-wm`) — Claude Code Rules

**What this is:** a personal, first-party framebuffer window manager and compositor for Adam's own Even Realities G2
glasses, which run his own fork (`~/damage-cfw`, `FORK.md`) of the published `g2flash` custom firmware: direct
framebuffer access in place of the vendor's EvenHub containers, so a compositor renders whole scenes with real fonts
and pushes pixels, and the glasses play motion programs, bound lists and transition effects themselves. Everything
runs on hardware Adam owns over his home network (phone, glasses, PC, auth token); working out the glasses' Bluetooth
wire format is ordinary device interoperability. Outside accounts: his TorrentLeech login (Torrents; credentials only
in the gitignored `~/.damage/config.json`), three keyless lyric services, YouTube via yt-dlp and the local `claude`
CLI under his own login (Music), the public sites Feed reads (no account), and his own Home Assistant hub, which the
bus alert watcher only reads. Nothing else. This is UI and display-rendering work.

**Who it is for (Adam, 2026-11-23; it wins over anything older, here or elsewhere):** himself. "This project is very
specifically for me and my unique use-cases." It is not for sale and not for release; no money is involved ("I don't
even take donations"), and it is not altered for anyone else's use. The repository is public "in case anyone is
inspired by it and wishes to fork the repo and make it their own", and so that he can show "the kinds of things I can
make in my spare time with no programming skills and a Claude Code subscription". Nothing is built, polished, worded
or held back for an audience, and no concern is raised on an audience's behalf.

System-wide rules in `~/.claude/CLAUDE.md` apply. This file holds Damage-specific rules.

**Read order and precedence.** `overview.md` (the research record; wins on FACTS) → `CLAIMS.md` (grades every
load-bearing claim V/M/C/I/S/U; **an `S` or `U` claim is not built on without checking**) → `DESIGN.md` (locked with
Adam; wins on SHELL DESIGN; read its §0, what is deliberately excluded, before proposing anything) → this file (wins
on RULES) → `REMINDER.md` (the entry point: what is true now, what is next) → **`REPLAN.md` (wins on the ORDER OF
WORK) → `CONTRACT.md` (wins on the APP CONTRACT; read it before any window or interface work) → `FORK.md` (the
fork's protocol, decisions and the design of each firmware piece; its §5 phase order no longer governs) →
`FIRMWARE.md` (wins on the FIRMWARE CONTRACT) → `MOTION.md` (its §7 as amended wins on MOTION)**. **Where two
statements clash, the later one wins** (Adam, 2026-11-23): a living document then says what is true now, with no note
of what it said before — the history is `HANDOFF.md`'s and git's. A ruling's, a quote's and a dated record's own text
is never rewritten.

## Gates

- `python3 tools/lint.py` after any geometry, layout or drawn-string change and any change under `core/…/bastion/`
  (RO001: that package only reads the hub): the repo run exits 0, `--selftest` passes (the rules: `DESIGN.md` §9.2b).
  The runtime rules (stereo pairs, the rect budget, fid order, the frame walls) are `core`'s `geom` package, checked
  on every emit (`GeometryTest`, `EmitRulesTest`).
- `python3 design/render_shots.py` after any design change; read the numbers. Render at **true 1× 640×480**: a 2×
  view flatters delicate type.
- `python3 research/verify_cfw.py` (offline) before any flashing conversation. **For the installed fork image**
  (`REMINDER.md` names it and its rollback in `fws/`) **and any fork candidate:** `python3 tools/verify.py` and its
  `--selftest`, **run in a tree at the candidate's branch** (a worktree; `~/damage-cfw`'s own checkout may sit on
  another — `HANDOFF.md` §98.4): the pin, reproducibility with our clang, the Thumb-bit audit, the size guard, and the
  changed sites reviewed against `FORK.md` §3.1 (no new site on a boot-time path).
- **The conformance vectors** (`FIRMWARE.md` §9): after any change to the fork's patch sources `(cd ~/damage-cfw &&
  python3 host/run_vectors.py && python3 host/run_self_test.py && python3 host/test_damage_ext.py)`; after any change
  to the simulator or the wire encoders, `ConformanceVectorTest` (both forms) green. **A disagreement is a finding,
  never a reason to edit an expectation by hand:** `make_vectors.py` writes inputs only, the fork's `run_vectors.py
  --write` fills expectations, and every vector not touched comes back byte-identical.
- **A firmware fact is decided at instruction level** (`python3 research/fwread.py dis|fn|word|refs|calls|strings`):
  the decompile corpus hides arguments and misses functions (`HANDOFF.md` §49.2).
- `DESIGN.md` §10 binds the runtime: **the shell runs on Android and desktop, so it is not Python.**
- **After ANY code change, the whole battery, green:** `./gradlew :core:test` (alone; a repeat answers FROM-CACHE —
  `:core:cleanTest :core:test --no-build-cache` for a rate) · `./gradlew :desktop:test` · `desktop --selfcheck` (the
  truth oracle on every settle, both contracts; more than once, twenty times for a rate) · `desktop --snapshot DIR`
  (look at the renders; run it, and any harness you suspect, more than once) · `--epub-check ~/books` ·
  `--music-check` · `--games-check` · `--feed-check` (`live` fetches the real sites once, read-only) ·
  `--motion-check DIR` and `--local-check DIR` (ALL HOLD) · `tools/lint.py` · `./gradlew :phone:assembleDebug` **in
  its own gradle invocation**, then `python3 tools/check_apk_wiring.py` (exit 0) · `node --test
  'core/src/test/js/*.test.mjs'` · the `--selftest` of `research/mutate_core.py`, `tools/journal_report.py`,
  `tools/journal_pull.py`, `tools/check_apk_wiring.py` and `tools/snapdiff.py`. After a card-art change also
  `--card-render`; after a change to the local rules or a list's holder also `LocalSequenceFuzzTest` at its three
  timings (the default, `LOCAL_FUZZ_TIMING=stage4`, `=stage5`). **Every merge into the integration branch is gated by
  the WHOLE core suite** (2026-11-21: a targeted gate left a class red). `REMINDER.md` carries the current counts.
- Deploy = `./gradlew :desktop:stageJar && sudo rc-service damage restart` (`DAILY.md`); a PC deploy never touches
  the display. Stop the service before any `:desktop:run` dev session; never rebuild the jar under a running instance.

## Scope, build, review — the protocol (2026-09-16, `HANDOFF.md` §65)

Why (§61–§65): of ~75 fixes from four reviews of one phase, half were in older code, a quarter a review fixing a
review, the rest failure handling the plan never scoped. Hence:

1. **Scope includes the failure envelope:** a phase's design pass answers `FORK.md` §3.8's six questions for every
   new piece of state, wait and guard BEFORE a line is written. A plan that lists only features gets only features;
   "be thorough" adds nothing a plan left out.
2. **A build ends with a "Not built" list and the two instruments' numbers:** the differential fuzz
   (`firmware/fuzz_vectors.py`) and the mutation sweep (`~/damage-cfw/tools/mutate.py`); on Damage's side
   `LocalSequenceFuzzTest` and the mutation list `research/mutations-c2.json` (run by `research/mutate_core.py`). A
   test written in the same pass as the code encodes the same model; the sweep says which guards a gate proves.
3. **A fix sweeps its class, not its site:** name the class (the v1 twin of a v2 op, the reader and the writer, both
   lenses, every second pass, every start gate) and list the sites checked.
4. **A gate is measured on the thing it names; a pin is watched to fail on the unfixed tree:** ask what observable
   would change if the feature were absent, and whether the gate reads it (§63.4, §64.4).
5. **Two general review passes, then stop reading.** Then: one focused read of any mechanism a review itself
   invented, one pass per defect class across the neighbourhood (a review's scope is the neighbourhood the diff
   touches, not the diff), the instruments, then hardware. The last pass is unreviewed by construction; only an
   instrument or the glass ends that regress.
6. **Records short:** one place per fact (`HANDOFF.md`), pointers elsewhere; a review's record does not restate its
   diff. The targeted gate during a session, the full battery once at its end. **State lives in `REMINDER.md` alone** — the
   installed image, what is deployed and staged, what is next, the counts; no other document states them (one pointer
   at most), this file included. At the close of every build, deploy or flash `REMINDER.md` is brought to date and a
   search of the other documents for the old build's name comes back empty (Adam, 2026-11-22 and 23: the documents
   were behind, then bloated).
7. **Where this applies in full (2026-11-17, `HANDOFF.md` §83):** the fork's firmware, the glasses' wire and
   compositor, any path that moves or deletes his files, any bus action. Elsewhere (window cores, pages, wiring, the
   PC's lanes): a lean spec, the build, ONE review pass whose readers apply the three tests below and propose
   removals as readily as fixes, fixes only for what passes them, the gate, and one look at the real thing (a
   browser, the glasses); no mutation list, and a pin only where a defect was found. A finding whose only fix is a
   new mechanism for a case real use does not produce is declined and listed. A build over its planned size says so,
   with what the extra lines are for, and goes on (Adam, 2026-11-18: "Absolute size is irrelevant to me (within
   reason), as long as it has purpose and isn't just useless bloat or abstractions that will never realistically be
   useful"). Size is reported; purpose is judged.

## What is worth building (Adam, 2026-11-17; `HANDOFF.md` §83)

His words: "This is a single user system we are building, that should be clean. Thousands of lines of invisible code
that wraps everything in abstraction for 'security' and edge case handling that will never actually come up in real
usage … is not our goal here." "Bloat that doesn't serve the end goal is bloat, but work that does actually improve
the end result is worthwhile." "It is important for the system to be robust and stable. I'm not too worried about
security specifically, but stability and redundancy to improve stability and reliability is also worthwhile."

**Who uses it and how.** One person, on several surfaces at once: the glasses, the phone, the PC, a kitchen TV that is
always on, a voice box later. He does different things on different surfaces at the same time, and one thing in one
place: "whichever page I'm really on is the one I'm really on" — **the latest real input wins**; nothing is built to
detect, report or arbitrate conflicts beyond that. **Where it is going:** a set of LLM agents running constantly on
real usage, updating what is shown, predicting needs and problems and acting on them. Not built yet; kept in mind.

**Three tests for every piece of state, guard, option, wait and test** — it is built if it passes one:
1. A surface he will use, or the later agents, need it in order to work.
2. Real use produces the failure it guards against, AND that failure costs more than the code: his files, a bus
   action, the firmware, the glasses' picture and latency — yes; a rare repeated notice or a stale row — no.
3. It makes state and actions legible to a machine (one description of what exists and what can be done, every action
   through one door, recorded with who did it and when), or it keeps usage data.
What passes none is not built. What is already built and passes none is not extended, and is removed when it is next
touched if removing it makes the code simpler and no less safe.

**Robust means it keeps running or comes back by itself** after what really happens: a process ends, the PC restarts,
the network drops, a disk fills, a surface goes away. Worth building: state that survives an unclean stop, reconnect and
catch-up without him, a surface that shows its last state with its age, a supervisor that ends a stuck job and says so,
one plain health view, redundancy that keeps the core up. **A state that only a manual restart clears is a defect.**
Outside the radio, render, input and flashing paths, a supervisor ending a stuck job is the "supervise externally" of
the first absolute rule, not a timeout on a path.

**Security in proportion.** Private networks, one user: no further work on defence against outsiders beyond what
stands (listeners gated by interface, the device approval as built). The limit that matters is on ACTORS — him, a
display, an agent — for irreversible actions and bus actions. **Not built:** handling for phone and PC on different
builds beyond saying so (they are deployed together), downgrades, both deploy orders, two tabs of one browser, any
pairing or policy ceremony that blocks a thing he would use.

## Status and the order of work

**The order of work is `REPLAN.md`:** its §4 as Adam ruled it on 2026-11-12 (§14 wins; `HANDOFF.md` §78), corrected
on 2026-11-17 (§17 wins over both; §83) and amended in §17.3 items 6–10 (item 6: new firmware features are armed and
tried at once). The end goal: four more interfaces beside the glasses (XFCE4, the phone app, the kitchen TV, any
browser) and a bus control system, then new windows. Until its steps close: no new windows, no Feed polish, no
popover build outside the plan (the suspension of 2026-09-12, `HANDOFF.md` §48). `REMINDER.md` holds the state and
the next steps; `HANDOFF.md` §48 on the dated records; `DAILY.md` the ops crib; `IMPLEMENTATION.md` what runs and
how (its "Review hardening" lists the load-bearing mechanisms); `README.md` the windows and their records.

**Topology (`REPLAN.md` §14; `CONTRACT.md` §2): the PC is the system** — it owns every window's state and every
interface connects to it; the phone keeps a working copy, draws the glasses and carries on when the PC is out of
reach; **PC-direct BLE is a manual choice, never automatic.** Until step S1 has put every window on the PC's record
(`REPLAN.md` §15, §16; progress: `REMINDER.md`; as built: `IMPLEMENTATION.md`) the **phone APK drives** (radio and
shell) and the OpenRC `damage` service on beardos holds the converted windows' state and serves the content, never
driving the glasses by itself: PC-direct BLE only by hand (the replica probe `drive=ble`, back with `drive=auto`; the
PC shell's `Target` row; `--transport ble`; `--transport remote` is the dev override). Detect the CFW by the
`EVENCFW/` string and the DamageCaps field, never the version. `WINDOWS.md` is the conversion checklist; `POPOVER.md`
a spec, not built.

**Rules from the whole-codebase reviews (`HANDOFF.md` §25–§36) that bind every change:**
- **A rect a paint returns is a promise.** Size every band from the face's MEASURED ink (`ascent + descent`), never
  a line height or a constant; ink outside a declared damage rect is never sent and no check can see it (§27). The
  truth oracle is a standing gate (`--selfcheck` on every settle; `OracleWalkTest`, a seeded walk at all four heights).
- **The harness is part of the system under review** (§30): a wait decides on ONE evaluation, a scripted scene pins
  its seed, a sample compared to the glass is taken ON the loop (`Shell.sampleIdle`), a rate is measured twenty
  times, not three.
- **Live-drive the real program before calling a round done** (§33): `tools/glassdrive.py` drives the phone's shell
  and snapshots the mirror; one step per snap in any window with an irreversible row.
- **Never answer a refused image with more images** (§36): in Silent Mode every frame is refused; the shell sleeps
  with the glasses, drops the lease on purpose, and the page traffic sleeps with it (§42: no keepalive or carrier
  refresh into an ended page, no release into a link that is gone). A lost ack is released by a later ack, never
  held for a msgId cycle (§34).
- **Latency is a standard, not a pass** — the section below and `WINDOWS.md` §6 bind every window and surface.
- Never re-introduce nominal-only seam guessing in the compositor: a pixel simulation against the firmware model is
  the only judge of stereo output (`LensOracleTest`, `Round6Test`, `Round7Test`, `Review20260903Test`).

**Adam's methodology for the app layer:** research → documentation → clean repo → plan → feature-creep explosion →
refinery → consistency passes → a final plan in real code → then slowly, carefully, execute. Explosion and refinery
are DONE (`EXPLOSION.md` §20: the verdicts and the build order). Window conversion resumes after the re-plan's steps,
and new windows are then built once for every interface: Adam's per-window verdicts first, then build against
`DamageWindow` (`core/…/shell/WindowContract.kt`; from S1 a window is a core plus a glasses presenter, `CONTRACT.md`
§11) per `WINDOWS.md` (its precedents name the window to copy), reading the G2CC original
(`/home/user/G2CC/server/src/windows/`, read-only) for interaction facts only. Two rules bind every window: **built
whole to its best state before the next — no v1/v1.5 staging**; **each app's notification toggles live in its own
Settings category, never Global** (Global keeps only `Notify · Damage`, the one shared `Quiet hours` row and the
APK-wide `Phone notifications` switch; the shell never gates an app's source on a hidden field).

## Running builds on this machine (2026-11-17; `HANDOFF.md` §82)

On 2026-11-16 a build stage's test process allocated without bound and the PC stopped responding for five hours.
Since then:
- **Agents are Claude Opus at xhigh effort, only** (Adam, 2026-11-11 and 11-17); never a smaller model. About six
  lanes at most, fewer when he is away from the PC.
- **Memory:** sessions run inside the cgroup `claude-work` (16 GiB, no swap); `cat /proc/self/cgroup` must say so
  before anything heavy starts (`/usr/local/bin/claude-cg-session` enters it). The cap is the backstop, not the rule.
- **A run with a guard disabled** (a pin watch, a mutation sweep, a fuzz case) goes ONE variant at a time, under
  `ulimit -v` in the command itself and an external bound; one ended by the limit, the bound or a signal is reported
  as "ran away", never as caught.
- **After any oddity a stage reports about the machine** (a file's time changed, swap in use, a slow run, a process
  ended with no reason): read `/var/log/kern.log` for `oom` before ruling anything.
- **Scratch, worktrees and briefs live under `private/`** (gitignored), never `/tmp` (wiped at boot); work in
  progress is committed at every stage boundary. One heavy JVM job at a time (`private/tools/jvm`).
- **No more than one build goes ONTO his devices ahead of what he has used** — what nobody has looked at has not
  passed. Building and reviewing ahead on branches is wanted (Adam, 2026-11-19: "you should be working on whatever
  future work is safe to start working on regardless of C2"; `REPLAN.md` §17.3 item 7): while a build waits on him,
  the next planned work that does not depend on it starts.
- **His subscription is a budget, watched all the time** (`REPLAN.md` §17.6). Before a run: its size in percent of
  his week and what he will see or be able to do for it; "nothing he would notice" goes to him first. Both meters
  are read before and after every run and the change reported; a build over its budget says so and goes on (his
  word, 2026-11-17: no stop for budget). **The aim is the least usage per task, never a week that lasts** (Adam,
  2026-11-24: "wasting time to stretch the usage until Monday is backwards … We want to both keep usage low AND get a
  lot done quickly, even if I run out of my allotment prior to Monday"): as many lanes run as the work's dependencies
  and the machine safely allow, each kept lean (below), and none is held back to pace the week. On his ask for a
  taper, lanes stop with their work committed, the records are written and the build is staged; work resumes on his
  word after the reset. The lead's own turns cost several times an Opus lane's: when the meter is tight, reading and editing go to
  a lane.
- **What is already written is cleaned up before more is added** (`REPLAN.md` §17.3 item 0; measured by the line
  count going down).
- **Usage is turns × context** (measured 2026-11-24 from the sessions' own transcripts: two thirds of the lanes' cost
  was re-reading their own context, a quarter re-writing it after a wait outlived the cache; the lead was a third to a
  half of everything, at a mean context of 543k tokens). So: the lead's window is capped (`autoCompactWindow`,
  `.claude/settings.local.json`) and `private/RESUME.md` is current before any long wait. Lanes are the project's
  agent types (`.claude/agents/`: `damage-builder`, `damage-reader`, `damage-packer`), which hold the standing lane
  rules. A builder gets a PACK (`private/packs/<stage>.md`, about 300 lines, every line cited), not a reading list,
  and a stage is cut so that its builder ends well under 400k tokens. Long runs go through `private/tools/gate` as
  ONE background job, and a lane ends its turn only with a result. A finished branch lands through
  `private/tools/land` (merge, rows, gate, `main`) as one job. The lead does not answer interim notices. Not used:
  a one-hour cache for lanes (measured: no net saving).

## Clean-room: no GPL code in Damage

Decided 2026-08-20. Damage borrows **protocol knowledge** from `g2flash` / `faceclaw` freely (wire formats,
constants, mode semantics, the lease protocol, tuning values). **It must not contain their CODE** — facts about a
wire protocol are not copyrightable; Babcock's implementation is GPL-3.0. The fork is its own GPL-3.0 repo
(`~/damage-cfw`); Damage stays clean-room and holds only the contract (`FIRMWARE.md`). Reading `reference/faceclaw`
or `reference/g2flash`: extract facts into our own words and implementation, never paste, cite the source file in a
comment. Why: the repository is public (above); keeping Babcock's GPL-3.0 code out keeps it his own work under his
own terms, and keeping other people's work out of it is what a public repository owes them.
- A Reddit comment saying Damage would use "borrowed code from FaceClaw" is retracted; do not act on it.
- **G2CC is Adam's own** — borrow from it heavily.
- **Never redistribute someone else's work, but the WINDOW that drives it may ship** (Adam, 2026-09-02).
  Distribution is the axis, not implementation — the browser posture. Two shapes: **Universal Paperclips** =
  fetch-not-vendor (the engine pulled at run time, SHA-256-pinned, never committed — `G2CC
  games/paperclips/fetch.mjs`; Damage generates the DOM from a list of element ids, not Lantz's markup); **FF1** =
  the emulation posture (the user rips their own cartridge; only the bridge ships; ROM-derived output counts as the
  work — ship `gen_data.py`, gitignore `data/*.json`; check the vendored `reference/` disassembly's licence before
  republishing). Unconditionally out: Even's SDK, third-party fonts with unclear terms, any faceclaw/g2flash code.

## Do NOT modify G2CC

`/home/user/G2CC` is a shipped system. Read from it freely (the BLE reverse engineering, the pre-pivot rasterizer, 20+
windows, render scripts — `overview.md` §10). Never edit, refactor or fix anything in it. Its server is retired
(`HANDOFF.md` §44) — never start it by hand. Its phone app stays disabled: enabled, it takes the glasses' Bluetooth
link (`HANDOFF.md` §99). The glasses run the CFW: G2CC's stock-firmware display path does not apply; stock 2.2.2.20
is gone and not in the public archive.

## Permission and irreversibility

"Investigating ≠ permission" is load-bearing here more than anywhere.
- **Never flash firmware without explicit, in-the-moment authorization from Adam** — not on momentum, not because a
  plan said so. **Read the patch source before flashing it** (g2flash has shipped one HardFault, `overview.md` §9).
- **Always dry-run first, one lens at a time (`--lens left|right`; the ritual is `FORK.md` §7).** `g2flash.py
  --stop-before heartbeat` (connect and discovery, nothing sent) and `--stop-before file_check` (notifications and
  the stock authentication, nothing stored) are safe at any time. **`--stop-before flash` WRITES on the glasses**
  (its begin step writes and removes a 4 KiB test file on the storage chip, formatting that filesystem if the test
  fails; its file check leaves `firmware/codec.bin` empty): run it only for the lens about to be flashed, directly
  before that lens's full flash. If the sitting ends after it, or a full flash is interrupted, finish a full flash of
  that lens (the installed image will do) before the glasses are restarted (`HANDOFF.md` §87).
- **Before any flash run on a lens on C3 or later** (a rollback, a fix flash, the last dry-run rung) read that lens's
  heap figures first (`FORK.md` §7): the flash tool's storage test allocates from the heap the larger context uses.
- There is no way back to stock 2.2.2 (left 2026-08-30; no read-back path); the CFW is revertible to any archived
  version. State the reversibility picture before any flashing conversation.
- **His, never done on momentum:** installing an APK; pushing the fork; any probe that changes the phone's or the
  glasses' state (the ladder's stage `c1=N`, `rest=on|off` — `tools/ladder.py` is his own tool for it);
  `tools/glassdrive.py` only when he has asked or is there to watch. A go for one of these is spent when it is used.
- **He never restarts the glasses by hand** (no button; he wears them all day and charges them at home): nothing may
  need a hand action after the overnight charge.

## Project-specific verify-before-execute

- **Never guess a CFW display mode byte, rect encoding or batch layout** — a guess draws garbage on the lens,
  silently. The mode contract: `g2flash/patches/zlib_glue.c`'s header (modes 3/5/6/7/8/9/10/11/12/13/14/15 and the
  high-bit "lenses differ" flag); `patches/texture_cache.c` for 12–15; `FIRMWARE.md` §3 for 16 (the self-test), §4
  for 17–24, §5 for 25 (the deflated cache write) and 26 (motion control), §6.3 for 27 (lists and bindings), §7 for
  the effects' verbs.
- **Never gate a feature on a capability token that could be dropped for space** (a5d1c31 dropped `img576` and
  `compass10` from the 127-byte string; both features remain). Require only `SettingsMsg.REQUIRED_CAPS`; check
  `EVENCFW/<n>` for anything version-shaped; parse field 100's length as a real varint; field 104 trails it.
- **The CFW path always sends `CompressMode = 0`, never nonzero** — the mode byte in the data field is the
  compression signal (the evidence on values 1 and 2: `overview.md` §8).
- **Never guess the BLE wire format from the vendor SDK or demo app.** Sources, highest first: (1) Even's own protobuf
  schemas — the vendor `FileDescriptorProto`s embedded as base64 in `g2-kit/ble/gen/*_pb.ts` (pad before decoding);
  (2) `faceclaw/` + `g2flash/` source; (3) `/home/user/G2CC/docs/G2_BLE_PROTOCOL.md` (capture-derived; authoritative
  for stock 2.2.2); (4) `g2-kit-unofficial` `ble/*.ts` code; (5) ❌ `g2-kit-unofficial` `ble/docs/*.md` — DO NOT USE
  (wrong in ~8 places; contradicts its own code).
- **Prose describes; code runs.** Every significant error so far (the RLE value, the ~1000 B wall, the "single image
  container", the mode-8 rect cap) was documentation disagreeing with working code.
- **Never guess firmware addresses:** every address traces to openCFW's Ghidra corpus; cite it.
- Claude CLI flags, library APIs, tool flags: `--help` or the source, never memory.

## The simulator lies — always ask "sim or glass?"

Every high-frame-rate claim we investigated traced to the EvenHub simulator (≈6 image containers where hardware
holds 4; no BLE bottleneck). Never cite a performance number without knowing whether it came from hardware; keep
`overview.md` §5's measured-vs-modeled labels. Its §5.2 `ms ≈ 60 + bytes/50` is PC-direct and four hours of one
session (`HANDOFF.md` §31.6); the daily path is the phone's (the latency standards below). **Price with
`REMINDER.md`'s table.** The model's agreement with the firmware's C on the conformance vectors is about bytes and
pixels, never time: a step's time on the glasses is what RIGHT reports; the simulator's is modeled.

## The Three Absolute Rules

**NO TIMEOUTS ANYWHERE.** No `wait_for`, no `timeout=`, no time-bounded wrappers in BLE / render / input / flashing paths.
Supervise externally; pacing and liveness decisions only.
**NO SILENT FAILURES, EVER.** No bare `except: pass`, no catch-log-swallow. Write status, ack arrival, decompress
failures, dropped frames all surface visibly (`flappy-g2`'s "silently ignore frame send errors" is how a bogus "10 fps"
got into the world).
**NO TRUNCATION ANYWHERE.** Content scrolls; it is never cut. Strings that don't fit raise loudly.

## Forbidden patterns

- Sending **`f1=9`** on the EvenHub channel (shutDown/exit).
- Letting **msgId exceed 255** (1-byte field; the glasses stop acking). Cycle it.
- Writing to **`sid=0x80` (`dev_config`)** — one early session non-terminally disabled a pair. Stay on `sid=0x09`.
- **Dithering** of any kind — roughly halves compression; the 4-bit downsample looks better without it.
- **Per-pixel or per-row scroll steps** — cost is ack-dominated; scroll in coarse steps.
- **One message per damaged region** — batch a frame's damage into one mode-8 flush (the thesis).
- **Interleaving multi-fragment messages** on the characteristic (one reassembly buffer per `seq`).
- **Strictly serial ack-gating** where a sliding window exists (Faceclaw runs 3 in flight on the CFW path).
- **Capping a mode-8 batch at ~1000 B** — that wall is for layout/CREATE frames (`f1=0`/`f1=7`) only; `f1=3` image
  chunks run 4096 B. Keep CREATE/REBUILD under ~1000 B.
- **Firmware patches that change image length without bumping the preamble length** — the bootloader programs
  `preamble[0]&0xFFFFFF` bytes with no bounds check; past MRAM end is SWD-only recovery. Safety is that bump,
  `check_mainapp_fits_mram()` and the headroom `tools/verify.py` prints (`overview.md` §3: the CFW is enlarged).
- **Re-sending an already-written flash block** (no block index, no dedup; the offset double-advances).
- Hard-coded wire constants or firmware addresses **without a source comment**.

## Compositor discipline

- **The canvas is 640×480**, not 576×288 (the EvenHub container). The safe area is fit-dependent: keep load-bearing
  UI centred; outer rows are bonus. **Height is a setting:** four TOP-aligned sizes 288/352/416/480, Global default
  480, per-app `preferredHeight`; Adam's fit loses the bottom. The 64 columns of width headroom are the stereo-shift
  budget — full-640-wide spends depth on pixels.
- **No off-panel scratch space.** Overlays repaint the covered region with mode 3.
- **The texture cache** (modes 11–14 and the v1 record: `overview.md` §4; modes 17–24 and u16 records: `FIRMWARE.md`
  §4) is lease-scoped: 64 KiB on contract 1; on contract 2 op 5 sets 64..160 KiB, up to 256 KiB from C1 on (the
  session's size at ladder stage 2 and above, `FIRMWARE.md` §5.9). Freed on expiry, FB_RELEASE, a fresh acquire and
  mode 11; kept across a renewal (the atlas is written through the lease, `HANDOFF.md` §73). READ IT BACK before
  drawing from a new upload: the ack precedes the decode, and the read-back shows the cache present with its size
  and flags, not that one write applied (§75.1). ❌ **Never emit mode 15** (the firmware's own font; no offline model
  predicts its pixels).
- **One mode-8 flush per frame**, capped at the fid budget: **5 mode-3 rects** at the 3-deep pipeline
  (`Geometry.rectBudget`; the duplicate-fid ring is 16 deep, a collision silently skipped). `fid` in `[1,0xFFFE]`,
  +1 per delta; only mode 3 burns one. A batch accepts sub-modes 3/6/9/13/14/15 (contract 2's list: `FIRMWARE.md`
  §4). The batch's count is one byte; the compositor gives out 254 and keeps one for the hint
  (`Compositor.subsLeft`). `CFW_RECT_MAX = 16` is diagnostic only.
- **The carrier layout is an image container plus a full-screen dummy TEXT container** (`content=" "`,
  `isEventCapture=true`); image-only layouts ack but never paint.
- **Hold the framebuffer lease or lose the screen:** sid 0x09 field 101 op 5, both arms, renew every 45 s against the
  firmware's 90,000-tick (87.9 s) expiry; it fails open. **Exception (§36): in Silent Mode the shell releases the
  lease on purpose** and the wake **rebuilds the session** (§38). `cfw_fb_lease_active()` also gates modes 12–15
  (12–24 on a contract-2 build; the later modes' gates: `FIRMWARE.md` §5.5, §6.3), long-press forwarding (events
  9/10) and suppresses the stock quit dialog.
- **A mode-3 delta requires a prior mode-6 keyframe.** Never emit a delta against an unseeded shadow; the simulator
  raises any sticky flag it models (`f_reorder`, `f_skip`, `f_dup`; never the firmware's `f_snap_of` — `HANDOFF.md`
  §77.8) as a hard error. Nothing sends mode-7 sub-2. **v1 modes 3/6 stream into the shadow** — a refused stream
  leaves the runs it wrote.
- **Push a sacrificial warmup frame** after container creation (the first burst is dropped); re-send it on the
  pacing tick like every other start gate.
- **Endless scroll = mode 8 { mode 9 copy + mode 3 fill }, DETECTED** (§31): `CanvasShift.detect` compares the frame
  before and after a repaint. Never add a "shift by N" field to a window contract.
- **Fixed cursor, panning content** for lists. **Anti-alias text** across the 16 levels.
- **There is no quit path** (`DESIGN.md` §1.6): the WM runs always; the both-temple long-press → Silent Mode is the
  hardware escape.
- **A long-press is UNATTRIBUTED — never build grammar on its source.** `Sys_ItemEvent.EventSource` is absent for
  event types 9/10 (verified at instruction level on 2.2.6.10 and 2.2.4.34).
- **Depth:** horizontal offsets only, small, never different content per eye. **Content far back; notifications,
  modals and popups FORWARD** (Adam, 2026-08-17). Global `Depth` moves everything, the selection bar one notch
  nearer, a per-app `Depth` row only that app (§41.2).
- **The glasses play motion themselves** (programs, mode 26; bound lists, mode 27: `FIRMWARE.md` §5–§7;
  `IMPLEMENTATION.md`, "The phone's halves of C1–C3"). **A program's bytes come only from the Motion DSL**
  (`motion/Motion.kt`, written from the contract's text); a new program shape gets a conformance vector the fork
  fills, and a disagreement is a finding. Four instances on fixed slots: 0 the bound list, 1 the wrap, 2 a
  transition's out half or the notice, 3 the in half.
- **The ladder** (stages 0–5: `DAILY.md`): the phone stores the stage and arms it at each session start; it changes
  only on his word.
- **His rules for motion** (`MOTION.md` §7 with its amendment): five tiers, each with None; one Scroll effect per app,
  never random; checkbox-random in the other tiers; **a transition always plays in full at the tier's length — the
  length holds, frames give**; what moves, moves together; the selected row comes forward in depth; he judges
  sequences, not single plays; a tier's options live in that tier's sub menu.

## Latency standards (`HANDOFF.md` §37; `WINDOWS.md` §6)

The daily path is the phone's radio: ~70 ms per flush plus ~120 ms per KB on 1M — the per-KB term about halved on
the Phase 2 build's LE 2M (`REMINDER.md`, measured).
- **A gesture is priced by its FIRST flush:** its bytes plus one floor. Make it small (a mode-9 translation ~40 B, or
  a strip); the heavy fill follows. Measure with `tools/journal_report.py` — **price against its WAIT column, not the
  ack** (§64.4); a list notch at 0.8 s is a defect.
- **Where a list is bound on the glasses the notch does not wait for the phone:** the glasses draw it at once and
  the phone's flush follows RIGHT's report, adopted by its rules (`FIRMWARE.md` §6.7, §7.11). The phone never draws
  over a local motion whose end it has not been told.
- **Text and icons go through the texture cache when Global `Cached text` is on** (§41.4): every rasterizer is a
  `CachedText` recorder, every icon crosses `IconPaint.blit`; the journal says why a rect went to pixels
  (`cacheMiss`). Never repaint a whole list because one cell changed.
- **Chrome never justifies its own flush** (`DESIGN.md` §8.3).
- **A live window may keep the link busy only while ACTIVE** (Adam, 2026-09-05); inactive, park the poll.
- **The phone's CPU is a term of its own:** never wrap, parse or lay out what did not change; never network or disk
  on the loop; read the journal's split (`handlerMs`, `slidesMs`, `chromeMs`, `textMs`, `truthMs`, `compressMs`).
- **Animation adapts to the link** (`Slide frames`, default `auto`). A window never adds frames. The engine's tiers
  have their own length and frames (`Animations`); `Slide frames` and `Slide fill` drive what the phone still draws.
- **Every new window ships with its latency profile** measured through `tools/glassdrive.py`.

## Hardware and environment

- **No power switch** on the glasses; the case is the only power control and lives at home. At work the recovery is
  phone-side: "scanning forever" while the OS shows Connected is a stale ACL — toggle phone Bluetooth.
- **Subscribe to RIGHT for async events** (Left is silent; only RIGHT can send). **Bulk pixels → LEFT, control +
  events → RIGHT** (Faceclaw's split; runs daily; graded strong, not proven optimal).
- **BLE:** MTU 247, ~232 B per AA fragment; **LE 2M on both arms since the fork's Phase 2 build** (the phone asks at
  every session start; 1M on stock and a5d1c31). Measured: 7–13 KB/s end-to-end PC-direct (~10× under spec, cause
  unknown — `overview.md` §5.1); the phone path ~8 KB/s on 1M (`HANDOFF.md` §54.4).
- Phone: Pixel 10a. PC: beardos (Gentoo, OpenRC, Portage). PC-direct BLE is hardware-proven (BlueZ 5.86 via
  `bluez-dbus` + `dbus-java`, both MIT — `desktop/BlueZLink.kt`). Flashing goes through `reference/g2flash/g2flash.py`
  PC-direct.

## Testing safety

- Anything touching the wire needs real glasses; mocks catch state-machine bugs only.
- Never trigger real outbound side effects from tests — no BLE writes, no flashing, no audio.
- **Verify bytes, not intentions:** decode what you actually sent.
- Disposable directories only; never pollute `/home/user/G2CC`.

## Wire-format source discipline

Every byte traces to a reference; comment the lineage on frame construction (`// g2flash/patches/zlib_glue.c ::
mode 8`, `// G2CC docs/G2_BLE_PROTOCOL.md §6.5`, `// captures/<file>.btsnoop @ frame N`). When a known-good frame
stops working after a firmware update, suspect format drift first. The vendor demo app is not a protocol reference.
G1 SDKs are architectural references only — never copy G1 UUIDs or characteristic IDs.

## Communication

Adam's global rules apply. Two more: **distinguish measured from modeled every time**, unasked; and put links, APK
paths and key actions **last** (terminal scrolling is hard at his work machine). A size is said in percent of his
week; a time written into a record is read from the clock, never estimated.

### Plain engineering wording — this costs Adam real time

Dramatic phrasing about firmware, radios, memory and recovery has tripped the model's own safety checks often enough
that Adam has had to switch models mid-session. On chat and every file we author: the facts stay sharp; choose the
plain word. Every new document opens with the "Context for the reader" block `FORK.md` and `FIRMWARE.md` use (a
personal device, the published patch method, display-rendering work); commit messages, comments and reviewer prompts
follow the table (`HANDOFF.md` §48.5).

| when you mean | write |
|---|---|
| a link, process or session ending | ends, stops responding, drops, is no longer up |
| an image that cannot be restored over the radio | not restorable over the radio, needs SWD recovery |
| a loop that repeats without bound | repeating, retrying, an unbounded loop of |
| data overwritten or left in a bad state | overwrite, leave inconsistent, clear |
| a defect, or code that gets past a check | defect, workaround, unchecked input, appended code |
| the bytes a message carries | message body, the field's bytes (`payload` where it names a code field) |
| a fault at run time | fault, ends early, stops |
| something unresponsive or not returning | unresponsive, stalled, not returning |

Describe what the code does, keep severity in the grading (`CLAIMS.md` grades, "verified / inferred / unverified")
rather than in adjectives, and where an established hazard has a real name use it once and explain it plainly.
