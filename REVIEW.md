# Review archive — the finishing build (2026-08-25) + the texture-cache round (2026-08-30)

**This file is the 2026-08 review ARCHIVE**, folded 2026-09-01 from the finding-by-finding log
(git holds every entry). ⚠ **The 2026-09-01 Files/§16 loop — eight rounds to convergence, ~144
findings — is NOT here: `HANDOFF.md` §22.2 is its record**, with the detail in its round
commits (`8f0dfe2`, `ead19a3`/`5d7ba5e`, `d0a74aa`, `10db318`, `119d6dc`, `b33253b`,
`d2945eb`).

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
