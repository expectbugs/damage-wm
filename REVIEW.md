# Review archive — the finishing build (2026-08-25) + the texture-cache round (2026-08-30)

**This file is the 2026-08 review ARCHIVE**, folded 2026-09-01 from the finding-by-finding log
(git holds every entry). ⚠ **The 2026-09-01 loops are NOT here.** The Files/§16 loop — eight
rounds to convergence, ~144 findings — is `HANDOFF.md` §22.2, with the detail in its round
commits (`8f0dfe2`, `ead19a3`/`5d7ba5e`, `d0a74aa`, `10db318`, `119d6dc`, `b33253b`,
`d2945eb`) and its accepted limits in §22.3. The Torrents + keyboard loop — four rounds, ~114
findings (~55 · ~30 · ~15 · ~14), **PAUSED at Adam's word, not converged** — is `HANDOFF.md`
§23.1–§23.4 (round commits `73fdf81`, `4f5e6e0`, `980d832`, `390a25c`; its accepted-not-fixed
items are recorded in §23.2 and §23.4); its round-4 diff is `980d832..390a25c`. The Music loop
(2026-09-02) — review round 1 (27 findings, `4565f35`) and two ultrareview runs (8 findings +
a hidden notice gate) — is `HANDOFF.md` §24.2–§24.3. The **whole-codebase review of 2026-09-03**
— ten verified defects, each reproduced before its fix and each pinned by a test confirmed to
FAIL against the unfixed tree, plus the belief-vs-truth oracle that found the invisible ones —
is `HANDOFF.md` §25 (commit `1f9fa4d`; pins in `Review20260903Test`). The **Games build's two
passes of 2026-09-04** — 11 + 8 verified defects, pinned in `GamesReview20260904Test` — and the
**live session** that followed them (13 more, pinned in `GamesLive20260904Test`) are
`HANDOFF.md` §26.2–§26.3, with the deviations list in `HOLDEM.md` §17. Adam then asked for the
whole cycle a SECOND time; that round — two more code passes and a second live session, **16 more
verified defects and two coverage gaps** — is `HANDOFF.md` §26.4 and `HOLDEM.md` §17.2b. And a
THIRD time: **11 more defects and two test-quality fixes**, `HANDOFF.md` §26.5 / `HOLDEM.md`
§17.2c. The **whole-codebase review of 2026-09-05** — one accounting defect found by reading, two
gates that measured nothing, and three drawing defects found by making the belief-vs-truth oracle
a STANDING gate (`--selfcheck` runs it on every settle; `OracleWalkTest` runs it over a seeded
random walk at all four heights) — is `HANDOFF.md` §27, with the pins in `Review20260905Test` and
the deviations in `HOLDEM.md` §17.2d and `MUSIC.md` §8.3. Its lesson: **a rect a paint returns is
a promise, and ink outside it is invisible to every check that compares belief to glass** — only
an independent truth catches it. The **third whole-codebase review of 2026-09-04 (late)** — six
defects by reading (the Hold'em pacer stall, the Reader refusing every book at 115/130 %, an
unreadable config replaced with defaults, Main's lenses before activation, a tmux line past its
rect, grammar) and five more by driving the whole program LIVE through the browser replica (the
menu, the notification box and the wheel all broke at the top of the font ladder; the Hold'em
status line was cut by the hole plane; tmux never alerted for a pane that had not filled its
screen) — is `HANDOFF.md` §28, pins in `Review28Test` (five classes) and the desktop
`ConfigTest`, every one run against the unfixed tree. Two of that round's findings were in the TESTS — a pin that passed with and without its
fix, and a pin whose comment claimed to reproduce a race it cannot — which is why every pin across
the three cycles was run against the unfixed tree before it was kept. Its lesson worth carrying:
two review passes over the code missed a menu row that could never succeed and a drawn mark that
read as an equals sign — **driving the live grammar and looking at true-1× pixels is a different
instrument, not a slower version of the same one.** The second cycle's lesson is narrower and
sharper: **fixing a reachability defect is not finishing with it.** The cash-out row was fixed,
pinned, and still failed in the commonest spot at the table, because the pin entered from a
different branch than the user does. The **fourth whole-codebase review of 2026-09-04 (evening)** — the list rhythm measured, the tmux
alert's deep link, the shell loop surviving an `Error`, brightness back to auto, the menu box
following the face — is `HANDOFF.md` §29, pins in `Review29Test`. The **fifth, of 2026-09-05** —
nineteen verified defects, `HANDOFF.md` §30, pins in `Review30Test` — is the round where two of
the findings were in the GATES themselves: the standing `--selfcheck` oracle had been failing one
run in ten since it existed because it read the shell's state field by field from another thread
(2/20 measured on the unchanged tree; `Shell.sampleIdle` takes the reading on the loop, 20/20
after), and its oracle kept watching the STOPPED shell through the whole restored session of its
restart scene. Its sharpest defect came out of a test BOUND firing: a 120 s "flake" in
`OracleWalkTest` that was a wheel closed mid-spin, spinning for ever, with the frame loop posting
empty Pumps behind it — the worst settle in a clean run is 46 ms, which is what turns a bound into
evidence. Its lesson: **the harness is part of the system under review, and twenty runs (not
three) is what answers a question about a RATE.** How a round runs: the Adam quote in
`HANDOFF.md` §21 and the §22.2 discipline — every finding verified against the code before a
fix, a test pin per fix, the whole battery green, the next round over the previous round's own diff.

What this archive keeps: the per-round summaries, and the register of **deliberate behavior a
future session must not "fix"** — every entry below was traced and the verdict recorded.

## The five rounds, summarized

- **Round 1** — 64 candidates across six reports; 58 confirmed and fixed (the arbitration's
  decision rule and the seam server's start were the two design-level ones), two design calls
  taken, two accepted, one already fixed by a sibling, one doc inconsistency.
- **Round 2** — 30 candidates: 26 fixed (largest: a race winner leaked when `start()` was
  cancelled during the losers' rollbacks; outstanding seam flushes never failed on a link loss;
  the desktop glue's missing both-arms check), two design calls, two smaller items.
- **Round 3** — 15 candidates: 12 fixed (the seam's `done` answered exactly once, the keeper's
  narration gate, one RSSI read in flight, the divergence count per session), four regression
  tests added, one accepted as theoretical with both candidate fixes traced and REJECTED (a3-8
  below).
- **Round 4** — 8 candidates on the round-3 diff: 6 fixed (one `isRunning` predicate shared by
  all three switch paths; the error limiter evicting and capping per tag), one coverage note,
  one cosmetic narration case retired by a sibling fix.
- **Round 5** — CLEAN on the code: one stale doc sentence, two comment tidies, four
  builder's-choice items (register below).

**Review loop closed.** 124 candidates; 104 confirmed and fixed; 5 design calls taken; 6
accepted as theoretical or test-only with the trace recorded; five regression tests added.

## The design-intent / accepted register (do not "fix" these)

- **R1.e9** — the second click of a double-click is deliberately NOT a second tap (right-click
  is the double-tap); documented in the replica page's help line.
- **R1.f8** — `isQuiescent()`/`quiescenceReport()` read loop-confined state from test threads:
  introspection only; a stale read is retried by the settle loops.
- **R1.f6 → R2.d2-10** — a wheel-commit's own app's notice is auto-read and not shown as new;
  tapping a box to OPEN its app is not "actively clearing", so the next box keeps its grace.
- **R3.a3-8** — over the seam the client's `started` can flip true→false→true within one
  message and a 250 ms poll landing inside the gap causes one spurious (self-healing)
  reconnect. Two fixes were traced and REJECTED: ordering `started` from the link event opens a
  stuck-driver window (worse), and posting from the collector can wait forever after a
  conflated loss. Left as is; the keeper's comment says what holds over the seam.
  *(2026-09-01 postscript: the related seam-start ordering flake was root-caused and closed —
  the server now posts a fresh state snapshot before every startok, `HANDOFF.md` §22.)*
- **R4-7** — the seam's unknown-id `done` guard has no direct test: no server path can produce
  one; verified by reading.
- **R5-3** — the watcher records a link-end reason only while the keeper is RUNNING, so the
  narrated reason is always an end seen while driving (taken as an invariant, not a bug fix).
- **R5-5** — only notices actually SHOWN count toward the per-tag error cap, so a burst yields
  a steady three per gap and a genuinely new error is never hidden indefinitely.
- **R5-6** — a reader descheduled across the whole of `stop()` could apply one stale state
  frame; nothing polls in that window and the next `start()` overwrites it.
- **R5-7** — no direct test for a superseded reader's buffered frames (cannot be provoked
  deterministically over a real socket); `SeamSessionTest`'s restart covers the positive path.

## The texture-cache round (2026-08-30, CFW `a5d1c31`)

Wire + byte-exact model review before any of it exists in the compositor. Kept verdicts:

- **T1.13 TAKEN** — the firmware imposes no keyframe requirement on modes 13/14, but on glass
  `present_shadow` pushes a display buffer holding whatever was there before, where the model
  starts at zeroes: it would look right in the sim and wrong on glass. The model now says so.
- **T1.14 RECORDED, not fixed** — mode 14 burns one overlay rect per glyph against
  `CFW_RECT_MAX = 16`, which drops silently past 16. Harmless to the pixels; with the
  diagnostic overlay on, any string over ~16 glyphs shows incomplete region OUTLINES. **Do not
  diagnose that as a firmware fault** (`REMINDER.md` item 19 carries the warning).
- **Clean, verified rather than assumed:** every mode 12/13/14 field offset and LE order; mode
  13's exact-8-byte total and mode 14's `8 + strlen`; the `[w][h][RLE]` no-row-pad format; the
  escape forms including zero-count rejection; the LUT's integer truncation and pre-LUT
  transparency; nibble packing; validate-all-before-draw-any; the duplicate-fid skip returning
  success so it cannot abort a batch; no Byte sign-extension defect anywhere in the new code.
