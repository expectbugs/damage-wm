# MOTION.md — the motion explosion (Phase 0 draft)

**Context for the reader.** Damage is a personal window manager for Adam's own Even Realities G2
glasses. The glasses run a community custom firmware (`g2flash`, published with its build method and
its sources) that lets an app draw whole scenes; Damage composes on the phone or PC and sends pixels
over Bluetooth. `FORK.md` plans a fork of that firmware that can draw, animate and answer the ring on
the glasses themselves. This file is the "explosion" step of that plan (`FORK.md` §5, Phase 0
"Design work"): every animation idea, per surface and per window, listed so Adam can cut the list
down to a verb set the firmware will implement. It is display-rendering and UI work on hardware Adam
owns, on his own home network. Plain wording throughout (`CLAUDE.md`).

**Status: DRAFT written 2026-09-13 for the Phase 0 explosion. Nothing here is decided.** This is the
wide list on purpose; Adam's refinery cuts it, and the surviving rows fix the motion verb set
(`FIRMWARE.md` §5). No candidate is a proposal, a default, or a winner — each is a thing that *could*
move, priced where a doc gives a number and marked "modeled" otherwise. Read `DESIGN.md` §6 (the
motion contract) and §0 (what is excluded) first; this file does not restate them.

---

## 0. How to read the tables

**The draft verb set** (`FORK.md` §4, `FIRMWARE.md` §5 — the motion engine's track verbs, integer
easing tables, one step per tick):

| short | verb | what it does on the glasses |
|---|---|---|
| **MV** | move rect, per lens | translate a rect by an integer step each tick; a per-lens x carries depth during the move |
| **FILL** | fill rect | paint a rect one level (the newly exposed strip after a move) |
| **CB** | cached blit + LUT ramp | draw a cached image/glyph, its LUT top nibble stepped per tick — a brightness rise/fall or dim in place. Flat in v1 (today's modes 13/14); Phase 2's per-lens cached draws (`FIRMWARE.md` §4) give it a per-lens form, so a CB candidate can carry depth once that lands |
| **VS** | vertical-scale blit | draw a cached image scaled vertically (fixed-point step, nearest row) — vertical squash/stretch |
| **PB** | progressive blit + offset | reveal a cached page from an edge, more of it each tick — the unfurl / slide-in |
| **WAIT** | wait | hold N ticks (stagger, dwell) |

Existing primitives the shell already uses, tick-free, named where a candidate leans on them: **COPY**
= a mode-9 rect copy (translation with no pixels — today's scroll); **STRIP** = the mode-3 fill of a
newly exposed row. The motion engine's MV/PB are the tick-driven, on-glass forms of what the shell
does one-flush-per-frame today.

**NEW** marks a verb the draft set does not contain; the refinery decides whether the candidate that
needs it is worth adding it.

**Phase column.** `P3` = phone-started (the phone sends one play message, the glasses run the tick —
`FORK.md` Phase 3). `P4` = local (a ring event runs a bound program on the glasses with no phone round
trip — Phase 4; needs the content staged on the glasses first). Per D6 motion answers input; a
candidate is P4 only where the trigger is a ring gesture and the content it moves can be staged ahead.

**Cost.** Measured numbers cite their section; everything else is "modeled" or blank. The daily path
is the phone's radio (`REMINDER.md`: ~70 ms/flush + ~120 ms/KB); the point of the motion engine is to
take the per-frame flushes off that path, so a candidate's cost after Phase 3 is one play message plus
whatever content had to be staged, not N frames on the wire.

---

## 1. The shell

### 1.1 Main — the dashboard (`DESIGN.md` §4.2)

| # | trigger | candidate motion | verbs | staged on glass | phase | notes |
|---|---|---|---|---|---|---|
| M1 | scroll one notch | the list pans one row through the fixed lens band, ease-out | MV + FILL | the neighbour rows as cached rows | P4 | today: COPY+STRIP per frame (§4.2, 880–1,460 B/notch measured). The list-pan is the flagship local case |
| M2 | notch settles on a new row | the lens icon grows from row-size (20 px) to band-size (56 px) as the row enters the band | VS or CB | both icon scales cached | P4 | the focused-row icon is band-height (§4.5b); a grow reads the focus |
| M3 | notch settles | the entering row's summary brightens from DIM to BODY as it reaches the lens; the leaving row dims | CB | the row text cached at two levels | P4 | brightness is the primary focus cue (§4.2); a ramp instead of a snap |
| M4 | resting → active (input arrives) | the dropped rows rise from level 2–3 back to full ink | CB | the resting and active row sets | P3 | §4.2 resting state; a fade-up on wake-from-idle |
| M5 | active → resting (input goes quiet) | the reverse: non-lens rows fade toward level 0 | CB | — | P3 | "input has gone quiet" is scheduler priority, not a timer (§4.2) |
| M6 | wrap from top row to Settings | the list wraps; the pan could continue in the same direction rather than jumping | MV + FILL | the Settings row cached | P4 | §4.2 "one scroll up lands on Settings"; a wrap that animates vs snaps |
| M7 | the ▸ continuation mark on the focused row | the lens second line marquees horizontally to show the clipped tail | COPY + STRIP | the full line cached | P4 | §4.2 "the lens can marquee its second line" — held in reserve today |
| M8 | Main lens depth | the lens band rises from the content plane to plane 0 on becoming focal | MV per lens | — | P3 | +4 B stereo box pair (§3.1); a depth step vs an instant plane change |

### 1.2 The switcher wheel (`DESIGN.md` §4.3)

| # | trigger | candidate motion | verbs | staged on glass | phase | notes |
|---|---|---|---|---|---|---|
| W1 | scroll one notch | the drum rotates one detent (60°): vertical scale `cos θ`, offset `sin θ`, brightness `cos θ` | VS + MV + CB | the adjacent windows' cells cached | P4 | the wheel is the design's showcase motion; sub-pixel inside a fixed 200,154,240,176 rect (§4.3). Today 4 frames/detent, 2 on a slow link |
| W2 | fast scroll through several | the detents coalesce into one continuous rotation (retarget, never queue) | VS + MV + CB | the whole visible window set cached | P4 | §4.3 "retargeting, not queueing"; the motion engine's `retarget` control (`FIRMWARE.md` §5) |
| W3 | wheel opens (the chord) | the drum spins up from the current window, or the panel grows from the centre | PB or VS | the wheel frame + centre cell | P3 | §4.3 opens the wheel; an entrance vs a snap |
| W4 | tap commits | the panel shrinks away and the selected window fills in | PB reverse | the committed window frame | P3 | commit is the cheap path (§4.3); the exit motion |
| W5 | rotating item disparity | an item's depth interpolates 12→8→4→0 across the four spin frames as it comes forward | MV per lens | — | P3/P4 | §4.3 "stretch"; perspective and disparity agree here |
| W6 | preview settle (scroll stops) | the window behind the wheel is repainted to the selected one — could cross-fade | — | the target window frame | P3 | §4.3: a RENDER not an activation; a fade here is a §0 exclusion (re-put §6) |

### 1.3 Notifications / the NOTICE popover (`DESIGN.md` §4.5, `POPOVER.md`)

| # | trigger | candidate motion | verbs | staged on glass | phase | notes |
|---|---|---|---|---|---|---|
| N1 | a notice arrives | the box unfurls downward from a 2 px rule at its final position, ease-out 3–4 frames | PB | the box content cached | P3 | §4.5 "it unfurls from a rule" — already the design; PB is its on-glass form (≤ 645 B measured) |
| N2 | the box takes focus (after the grace) | it steps from plane −1 to plane 0 and brightens dim→full | MV per lens + CB | — | P3 | §4.5 "the focus transition is the notification stepping toward you"; +4 B |
| N3 | an emergency alert | the wide band steps to plane +1 (crossed) instead — a bigger jump | MV per lens + CB | — | P3 | §4.5; the depth ladder encodes urgency |
| N4 | double-tap dismiss | the box furls in reverse, then the covered content restores | PB reverse + FILL | the content under it cached | P4 | §4.5 "furl in reverse to dismiss" |
| N5 | scroll a long body | the body pans one line through the box | COPY + STRIP | the body lines cached | P4 | §4.5 scroll one line 150–300 B measured |
| N6 | queue advance (dismiss reveals next) | the next box slides up from the queue as the `+N` badge decrements | PB + CB | the next box cached | P3 | §4.5 queueing; a transition between items |
| N7 | silent-mode notice | the small box appears and, at 5 s, the bottom rule depletes as a dwell track | FILL over ticks | — | P3 | §4.5 "optional depleting dwell track", off by default; a scheduled UI transition, not a timeout |

### 1.4 The popover family — stack motion (`DESIGN.md` §4.11, `POPOVER.md` §3.4)

| # | trigger | candidate motion | verbs | staged on glass | phase | notes |
|---|---|---|---|---|---|---|
| P1 | a MENU/CONFIRM/etc. opens | the hole clears and its marks appear — could grow from the focal axis | PB | the popover content cached | P3 | `POPOVER.md` §3.9 open ≤ 250 ms target |
| P2 | a second popover opens over the first | the first recedes by `Popover step` (per-lens copy); the new one takes plane 0 | MV per lens | — | P3 | `POPOVER.md` §3.4 "recedes… rather than the new one advancing"; +4 B/rect |
| P3 | dismiss the top | the one beneath returns to plane 0; the top furls away | MV per lens + PB reverse | the under content cached | P4 | §3.4 "brings the one beneath back" |
| P4 | an arrival over an interactive popover | it shows DIM above and takes focus (brightens, steps forward) only when the top is dismissed | CB + MV per lens | — | P3 | §3.5 "an arrival never steals focus"; the DIM→focus ramp |
| P5 | MENU cursor moves | the highlight moves between rows (brightness), the box does not move | CB | rows cached | P4 | menu scroll; the row brightens rather than a moving bar |
| P6 | a DECK image block | the image arrives as strips in a later flush behind a placeholder line | PB or STRIP | the strips | P3 | §3.6 image "painted in a LATER flush"; the first-flush rule |

### 1.5 The keyboard (`DESIGN.md` §4.8)

| # | trigger | candidate motion | verbs | staged on glass | phase | notes |
|---|---|---|---|---|---|---|
| K1 | keyboard opens | the wireframe box appears — could grow from the focal axis or unfurl | PB | the whole board cached (paid once on open) | P3 | §4.8 "the outlines' cost is paid once when the keyboard opens" |
| K2 | scroll at ROW stage | the row highlight moves down/up, wrapping (brightness rise on the focused row, `▸` at its edge) | CB | the row outlines cached | P4 | §4.8 repaints two key cells today |
| K3 | scroll at KEY stage | the key highlight moves along the row, wrapping | CB | the key cells cached | P4 | §4.8 |
| K4 | enter/leave a row (tap at ROW / double-tap at KEY) | the focused row's outlines rise to BODY/HEAD and the rest fall to FAINT | CB | — | P4 | §4.8 brightness carries the two stages |
| K5 | a key is typed | the pressed key flashes; the text line updates | CB + STRIP | — | P4 | §4.8 "a keypress repaints the text line" |
| K6 | caret / draft pans | a long draft pans in the text line, never cut | COPY + STRIP | the draft line | P4 | §4.8 "pans, never cuts" |

### 1.6 Silent mode and the silent clock (`DESIGN.md` §1.5)

| # | trigger | candidate motion | verbs | staged on glass | phase | notes |
|---|---|---|---|---|---|---|
| S1 | enter silent mode (double-tap at Main) | the dashboard clears to the clock — could recede/fade rather than snap | CB or PB reverse | the clock box cached | P3 | §1.5 "silent mode is a paint, not a layout change" |
| S2 | the minute steps | the seven-segment digits change — an animated segment change over frames | CB or VS | the digit images cached | P3 | ⚠ §1.5 **rejected** an animated minute change (4 frames = 240 flushes/hour, "unnecessary motion"). Listed only because D6 re-opens it; the refinery should confirm the rejection stands |
| S3 | wake (double-tap) | the clock gives way to the restored screen | PB / CB | — | P3 | §1.5b the wake rebuilds the session; motion is after the rebuild |

### 1.7 Height switch, back-to-Main, the wake, input echo (`DESIGN.md` §4.2, §1.4, §1.5b, §9.2)

| # | trigger | candidate motion | verbs | staged on glass | phase | notes |
|---|---|---|---|---|---|---|
| H1 | Size changes (Settings, tap applies) | the whole shell re-lays out — a transition rather than a keyframe jump | — | the two layouts | P3 | ⚠ a relayout is a keyframe (~1.1 s modeled / ~0.3 s measured, §8.4); staged while scrolling, applied on tap. Animating it is expensive — a re-put question |
| H2 | back-to-Main (double-tap at a window root) | the window recedes / slides out and Main slides in | MV + FILL, or PB | Main's frame cached | P3 | measured 704 ms first-visible on 0.37 (§8.6); the heavy case |
| H3 | window switch (commit from the wheel) | the outgoing window slides off and the incoming slides in (chrome stays, §2.4 r6) | MV + FILL | the incoming content frame | P3 | cross-window delta (§5 rule 10); chrome does not repaint |
| H4 | wake from firmware Silent Mode | the boot logo / first frame arrives after the session rebuild | PB | — | P3 | §1.5b, §38: a rebuild, never a keyframe into the old page; the warmup frame is the splash (§5 rule 17) |
| H5 | input echo (a gesture lands) | the status cell's gesture glyph flashes / pulses | CB | the glyphs cached | P3 | §9.2 input echo; the armed-chord "hold" glyph could pulse while the chord window is open |

### 1.8 Settings live preview (`DESIGN.md` §4.2)

| # | trigger | candidate motion | verbs | staged on glass | phase | notes |
|---|---|---|---|---|---|---|
| G1 | scroll Brightness / Depth / Presence | the whole screen previews the value live as you scroll | MV per lens (Depth) / CB (Presence) | — | P4 | §4.2 "every appearance setting previews LIVE"; Depth is a per-lens shift, Presence an ink ramp |
| G2 | scroll a value row | the value column slides to the next value (a tiny local slide) | MV + FILL | the value labels cached | P4 | the row's own motion, distinct from the previewed effect |

### 1.9 Chrome (top bar, status bar, dividers) (`DESIGN.md` §4.1, §4.4, §4.6)

| # | trigger | candidate motion | verbs | staged on glass | phase | notes |
|---|---|---|---|---|---|---|
| C1 | the divider's attention marks change | a mark appears/brightens when a window wants attention | CB | — | P3 | §4.1 the divider carries attention; chrome never justifies its own flush (§8.3) — rides a content flush |
| C2 | battery ≤ 20 % | the battery bar pulses `15·8·3·8·15·8·3` | CB | — | P3 | §4.1 already a brightness pulse (settable) |
| C3 | the Title changes on a window switch | the old title gives way to the new — snap or a short slide | MV + FILL | — | P3 | §4.1 Title is short by design; a slide here is chrome motion, low value |
| C4 | back-stack depth changes | the bottom divider's bright segment count steps | CB | — | P3 | §4.6 the divider encodes depth; free, rides a flush |

---

## 2. Windows

### 2.1 Reader (`damage-window-verdicts`, `EXPLOSION.md`; Level_ LIBRARY/CHAPTERS/BOOK/ACTIONS)

| # | trigger | candidate motion | verbs | staged on glass | phase | notes |
|---|---|---|---|---|---|---|
| R1 | scroll in the book (BOOK) | the page pans by N lines (5/notch default), ease-out | COPY + STRIP → MV + FILL | the next lines cached | P4 | the WM's endless document scroll (§4.6); Reader is why scroll accel exists (§0). Measured 221–625 ms first-visible on the phone path (§8.6) |
| R2 | page-turn feel | a page could slide off as the next slides in, rather than panning | PB | the next page cached | P3/P4 | Adam asked for page turns to "slide… then stop"; the Reader precedent for a page-slide vs a line-pan |
| R3 | chapter jump (Chapters picker → a chapter) | the book slides to the chapter's offset | MV + FILL | the target page cached | P3 | Level_ CHAPTERS → BOOK; a long jump is a fresh page, not a pan |
| R4 | Jump forward/back (±10%) | the same, a longer slide or a snap | MV + FILL | — | P3 | the ACTIONS rows |
| R5 | open a book (LIBRARY → BOOK) | the shelf gives way to the first page | PB | the first page cached | P3 | one tap opens; a transition into reading |
| R6 | shelf/chapter list scroll | the list pans through the lens (the list kit) | MV + FILL | rows cached | P4 | the kit's list pan, shared with every list window |

### 2.2 Tmux (`TMUX.md`)

| # | trigger | candidate motion | verbs | staged on glass | phase | notes |
|---|---|---|---|---|---|---|
| T1 | scroll into history | the flow pans by 5 lines, ease-out | COPY + STRIP → MV + FILL | the history lines cached | P4 | measured 352–645 ms first-visible (§8.6); the frozen scrollback |
| T2 | new output (live, pushed on change) | the newest lines rise from the bottom, older lines pushed up | MV + FILL | — | P3 | §3.2 tail anchored newest-at-bottom; a scroll-up of live output. "Live only while active" (§6) |
| T3 | sessions list scroll | the list pans through the lens | MV + FILL | rows cached | P4 | the kit's list pan |
| T4 | live → keys → send → back to live | the keys menu opens over the pane and drops back after a send | PB / MV | the keys cached | P3/P4 | §3.2 "a key sends and DROPS BACK TO LIVE"; the menu's own motion |
| T5 | an alert (session wants input) | the sessions-list ⚠ mark and the notice appear | CB + N1 | — | P3 | §3.5 alerts; the notice is N1, the list mark is C1's shape |
| T6 | alternate-screen TUI (grid) | a full-screen redraw; the cursor cell could blink | — | — | — | ⚠ §0/§6 blink is excluded (2 rects/s forever); the grid is the fallback path, mostly a full repaint, little to animate |

### 2.3 Files (`EXPLOSION.md` §5; Level_ LOCATIONS/BROWSE/TRASH/VIEW)

| # | trigger | candidate motion | verbs | staged on glass | phase | notes |
|---|---|---|---|---|---|---|
| F1 | browse list scroll | the list pans through the lens | MV + FILL | rows cached | P4 | the kit's list pan |
| F2 | descend into a folder (tap Open in the menu) | the current directory slides out, the child slides in | PB / MV + FILL | the child listing cached | P3 | tap = context menu with Open first; the descend transition |
| F3 | ascend (double-tap) | the reverse | PB reverse | the parent cached | P4 | back one level |
| F4 | the context menu (tap) | the menu hole opens over the content | PB (P1) | menu rows cached | P3 | Files' whole grammar rides MenuSurface; the popover open motion |
| F5 | image viewer (VIEW) scroll | the image strips pan through the viewport | COPY + STRIP | the strips | P4 | the strip DocView; box-sampled 16-level image |
| F6 | open a viewer (BROWSE → VIEW) | the listing gives way to the image/text/PDF | PB | the first strips cached | P3 | a transition into the viewer |
| F7 | capacity bars (LOCATIONS) | a volume's fill bar could animate to its level on refresh | CB | — | P3 | low value; the bar is chrome-like |

### 2.4 Torrents (`TORRENTS.md`)

| # | trigger | candidate motion | verbs | staged on glass | phase | notes |
|---|---|---|---|---|---|---|
| Z1 | transfers list scroll | the list pans through the lens | MV + FILL | rows cached | P4 | the kit's list pan; measured list-notch class (§8.6) |
| Z2 | a progress bar advances (poll) | a torrent's 10/12-block bar grows a block | CB | — | P3 | §3.1 "a poll repaints the rows whose block or state changed"; a block step, live while focused |
| Z3 | the lens speed history | the 8-column speed history scrolls left one column per poll | COPY + STRIP | — | P3 | §3.1 "8-column speed history (last 8 polls, 2 px steps)"; a scrolling strip |
| Z4 | listing paging (endless) | the next page's rows append as the cursor nears the end | MV + FILL | the next page cached | P4 | §3.1 endless paging; the pan continues into new rows |
| Z5 | open the torrent page / details | the listing gives way to the Document | PB | the page cached | P3 | the descend transition |
| Z6 | add confirm result | the title notice reports `added · <name>` and the transfer appears in the list | N-class | — | P3 | the new row's entrance |

### 2.5 Music and Music Mode (`MUSIC.md`)

| # | trigger | candidate motion | verbs | staged on glass | phase | notes |
|---|---|---|---|---|---|---|
| U1 | track change (NOW PLAYING) | the old card slides/fades out, the new card in; art swaps | PB / MV / CB | the next card cached | P3 | root is NOW PLAYING (verdict 4); a card transition |
| U2 | progress bar (every 5%) | the 12/20-block bar grows a block | CB | — | P3 | §8.1 "repaints on track change and every 5 % of progress" |
| U3 | scroll = volume (live) | the level bar and % update live as you scroll | CB / FILL | — | P4 | root scroll is volume; a live bar |
| U4 | queue scroll | the list pans through the lens | MV + FILL | rows cached | P4 | the QUEUE menu row |
| U5 | lyrics advance | the current line rises to HEAD brightness and the block scrolls one line, flushed a line ahead of its stamp | CB + COPY + STRIP | the lyric lines cached | P3 | §3.7 the scheduler flushes ahead; a synced line change |
| U6 | seek (SEEK level) | the progress bar jumps and the position animates to it | MV / CB | — | P3 | the SEEK rows |
| U7 | Music Mode visualizer (Bars/Scope/Pulse/Meter) | one dirty rect per frame at 4/8/12 Hz | CB / COPY (Scope) / VS (Pulse) | the viz data | P3 | §8.3 ONE rect per surface; Scope is a mode-9 shift + a new column; Pulse is the card scaling. D6 names only games as the exception; the visualizer already ships and answers the audio while it plays — whether it sits in D6's exception is a question for the refinery |
| U8 | enter/exit Music Mode | the window surfaces stack in / the panel clears | PB | the surfaces cached | P3 | exclusive mode entrance |
| U9 | boost/limiter badge | the boost badge appears; a notice on max+boost | CB + N | — | P3 | low value |

### 2.6 Feed (`FEED.md`)

| # | trigger | candidate motion | verbs | staged on glass | phase | notes |
|---|---|---|---|---|---|---|
| D1 | source/item list scroll | the list pans through the lens | MV + FILL | rows cached | P4 | the kit's list pan; target ≤ 540 B first flush (§3.8) |
| D2 | open an article (ITEMS → ARTICLE) | the list gives way to the Document; body arrives ahead | PB | the text screen cached | P3 | one tap opens (Reader shape); §3.8 open ≈ 2 KB |
| D3 | article scroll | the Document pans one line | COPY + STRIP | the lines cached | P4 | §3.8 article notch ≤ 500 B |
| D4 | comic canvas (COMIC) | the strip pans if taller than the viewport; the button bar highlight moves | COPY + STRIP + CB | the strips + bar cached | P4 | §8.2 item 5 canvas + bar; a bar-highlight change is small, a pan is a detected translation |
| D5 | comic flip (next/prev/random on the bar) | the current strip slides off, the next in | PB | the next comic strips | P3 | §8.2 flips by number/list; the heaviest first flush (5–30 KB) — a slide may not be worth it at that size (a re-put) |
| D6 | binge scroll (8-Bit archive, endless) | the virtual document pans; the next episode inserts below without a jump | MV + FILL | the next two episodes pre-scaled | P4 | §3.1 the engine pre-scales the next two; endless insert |
| D7 | comments open | the article gives way to the flat thread | PB | the thread cached | P3 | the descend transition |

### 2.7 Games · Hold'em (`HOLDEM.md`)

| # | trigger | candidate motion | verbs | staged on glass | phase | notes |
|---|---|---|---|---|---|---|
| P-1 | deal (tap to deal) | the hole cards slide in from the deck position, ease-out | MV + PB | the card images cached (kit's `CardArt`) | P3 | §9.2 "designed as a deal slide"; built as a staged reveal today. The kit's reusable card programs (`FORK.md` Phase 6d) |
| P-2 | flop/turn/river reveal | the board cards flip or fade up one at a time, on the pacer | VS / CB / **NEW horizontal-scale blit** | the board cards cached | P3 | a card FLIP about a vertical axis foreshortens HORIZONTALLY — the draft has vertical-scale only. Either add a horizontal-scale blit (NEW) or stage the flip as a cached-image swap (back→sliver→front). Today: a card-by-card reveal |
| P-3 | a bet / call / raise | chips move from a seat to the pot | MV | a chip image cached (`Money.chipStack`) | P3 | a chip-to-pot slide; small cached images translating |
| P-4 | showdown | the winning hand highlights; the pot slides to the winner | MV + CB | — | P3 | §10.1 the showdown stays up until you act; the pot-award motion |
| P-5 | your hole cards' depth | the two hole cards sit at plane 0, the table at −1 | MV per lens | — | P3 | §9.2 `contentPlanes`; a depth pop on deal, +4 B/rect |
| P-6 | a seat folds / busts | the folded seat's cards dim; a busted seat clears | CB | — | P3 | §7 the seat strip; a fold-dim |
| P-7 | bot action pacing | each bot action draws as it happens, ~600 ms apart, skippable | CB / MV | — | P3 | §10.1 a pacing loop, not a timeout; the per-action draw is ~100 ms |
| P-8 | action / sizing / confirm menus | the menu opens over the table (MenuSurface) | PB (P1) | menu rows cached | P3 | §9.3 the levels ride the popover |
| P-9 | Games/Standings/Bankroll list scroll | the list pans through the lens | MV + FILL | rows cached | P4 | the kit's list pan |
| P-10 | the scoreboard digits change | the seven-segment `$847 · W12 · L3` steps | CB | the digit images cached | P3 | §4 the seven-segment scoreboard; a digit change |

---

## 3. Re-put to Adam (the `DESIGN.md` §0 cost-based exclusions, now that per-frame cost drops)

`FORK.md` Phase 0 says to revisit §0's cost-based exclusions once the motion engine takes per-frame
flushes off the wire. These are **questions**, not proposals; each says what it would cost in the new
verb set and where the doc stands today.

| # | excluded today | why it was excluded | what it would cost now | note |
|---|---|---|---|---|
| X1 | **Fades / dissolves / cross-fades** | "not expressible as translation + fill ⇒ too expensive" (§0, §6.2) | a fade of a *cached* region is CB (LUT ramp per tick), flat — cheap on the glasses. A fade of *non-cached* content needs a LUT-over-rect op per tick (`FIRMWARE.md` §4, a drawing op) or a NEW "fill-ramp" verb | the cost objection was per-frame wire flushes; the motion engine removes it for cached content. Candidates that want it: W6 (preview cross-fade), U1 (card fade), S1 (silent-mode recede) |
| X2 | **Dimming the content behind a popover** | "no dim-in-place op exists… ≈ +0.5 s each way" (§0, `POPOVER.md` §2, verdict 3) | a LUT-over-rect on the covered region, one tick, on the glasses — no repaint on the wire | verdict 3 rejected it for *latency*; a firmware LUT-over-rect changes the price. Still Adam's call — he said "less latency, not more" |
| X3 | **Banners / timed popovers** | a no-focus banner needs the window to paint under an overlay — a layered repaint the shell does not have (§0, verdict 4) | a firmware layer that composites a banner over a live window without the window repainting | this is a compositor capability question, not just a verb; larger scope |
| X4 | **An animated silent-clock minute change** | "unnecessary motion" (§1.5, rejected) | CB/VS over 4 ticks, on the glasses, no wire cost | D6 explicitly re-opens the silent clock "unless the refinery rules otherwise"; §1.5 already rejected it once. Confirm or reverse |
| X5 | **A blinking terminal cursor** | "2 rects/s forever, for nothing" (§0, `TMUX.md` §3.3) | CB on the glasses, no wire cost, but still constant motion at FAR | the FAR/ignorable-HUD argument (D6) may still exclude it even at zero wire cost |

---

## 4. Verb tally (how many candidates lean on each — the refinery's cut list)

Counted across §1–§2 (a candidate that lists two verbs counts for each; "or" alternatives counted once
for the primary):

| verb | candidates | the load-bearing ones |
|---|---:|---|
| **MV** (move rect per lens) | ~34 | every list pan, every window/page slide, chips, depth steps, the wheel offset |
| **FILL** (fill rect) | ~22 | the newly exposed strip after every MV; pairs with MV almost everywhere |
| **CB** (cached blit + LUT ramp) | ~28 | every brightness focus cue, dim/undim, progress blocks, viz, scoreboard digits, lyric lines |
| **PB** (progressive blit + offset) | ~17 | every unfurl/reveal/cover: notices, popovers, menus, viewer/window entrances, page turns |
| **COPY** (mode-9, existing) | ~12 | scroll strips, marquees, the Scope visualizer, speed history — the shell already does these |
| **STRIP** (mode-3 fill, existing) | ~12 | pairs with COPY on every scroll |
| **VS** (vertical-scale blit) | ~6 | the wheel drum (W1/W2/W5), icon grow (M2), Pulse viz, board reveal |
| **WAIT** | (implicit) | every staggered/paced sequence (deal, bot pacing, dwell tracks) |
| **NEW: horizontal-scale blit** | 1 | card flips (P-2) only — the single candidate the draft set cannot express; refinery decides vs a cached-swap fake |
| **NEW: fill-ramp / LUT-over-rect in a track** | 0 required | only if X1/X2 (fades, dim-behind) come back in |

Reading: **MV + FILL + CB + PB** cover essentially the whole list; VS earns its place through the
wheel and a few grows; COPY/STRIP already exist. The only genuinely new verb any *current* candidate
needs is a horizontal-scale blit for one effect (card flips), and even that has a cached-swap
alternative. The fade/dim verbs are needed only if Adam reverses a §0 exclusion (§3).

---

## 5. Not here, on purpose

Per **D6** (motion answers input; nothing moves on its own; games while playing are the exception):

- **No idle/ambient animation** — nothing loops, drifts, breathes or pulses without an input, except
  the two established exceptions that are *content*, not decoration: the ≤20 % battery pulse (§4.1,
  already shipped) and the Music Mode visualizer (§8.3, answers the audio while playing). The HUD
  stays ignorable at FAR and while driving.
- **No motion a window invents** — frames-per-notch is the shell's setting (`WINDOWS.md` §6 r6); a
  window never adds frames. Every candidate above is the *shell's* motion on the window's behalf, or
  Hold'em's kit programs (the games exception).
- **No per-eye content motion** — depth is horizontal offset only, same content both eyes (§3.4);
  every "per lens" verb above is a shift, never a different image.
- **No motion that is not translation + fill + a cached ramp** unless a §3 re-put brings one back.
- **The wake and the relayout are transitions, not free animations** — they follow a session rebuild
  or a keyframe and are priced as such (H1, H4); listed so the refinery prices them, not to add motion
  to a slow path.

**Open for the refinery** (the numbers Phase 0 still owes, `REMINDER.md`): the tick rate the panel can
present at sets how many steps any of these get — every present is a full 153,602-byte panel transfer
on both panel drivers (`CLAIMS.md`, 2026-09-13), and that transfer is timed by nothing yet (M0.1's
overlay times only the shadow copy; F1.3 adds the stamp); comfortable disparity (T3/T4) sets the
depth-step candidates; the battery cost of local motion over a workday (M0.6) gates the ambient-ish
ones. No candidate here is buildable until those land — this is the list to cut, not a plan to run.
