# Where we are, and what to do next

**Updated 2026-09-05 (later): THE LATENCY PASS — the slow regime is the PHONE's radio path,
twelve self-contained changes shipped, and a joint plan for the rest.** `HANDOFF.md` §32 is the
record; §32.6 is the plan. Three things to carry:

- 🔴 **§31.6's step change is a change of radio path, not of time** (grade C): the fast hours were
  PC-direct BlueZ, the slow hours the PC driving through the PHONE (`damage.log`: `driving via
  remote:aphone`; the journal's stall notes name it). The daily driver is priced by §31.1's slow
  rows. The journal now says which path (`via`) and what the host cost (`handleMs`,
  `assembleMs`); `tools/journal_report.py` reads it.
- 🔴 **The captures held handle 65's whole story and three documents said they did not**
  (`research/linkparams.py`, grade M): the RIGHT lens connects at 30 ms and the GLASSES move it to
  90 ms/latency 4 when idle, the phone's stack granting each request. So the phone-side question
  is whether `CONNECTION_PRIORITY_HIGH` is granted at all — **the APK now reports its connection
  parameters into its journal, and every host serves its journal at `/journal?token=…`** (no adb).
- What shipped, all pixel-identical (49 scenes against the untouched build): the compositor's
  compress memo, string measure/raster caches in both rasterizers, the wrap estimate (pinned equal
  to the old loop), a reserved window slot for input, the wheel in 2 frames on a measured slow
  link, the state write off the loop, HTTP keep-alive, ssh multiplexing for tmux hosts. Battery:
  core **462** · selfcheck ×10 · snapshots ×3 · lint 0 · APK **30/0.30 staged**.

✅ **Deployed 2026-09-05 ~14:43** (commit `137f924`, pushed): the service on the §32 build, APK
0.30 installed and driving. **Then measured live the same hour — `HANDOFF.md` §33**, the PC
driving the phone's shell through `tools/glassdrive.py` and reading its journal back: the phone
path is the slow regime on its own journal (**72 ms** small, **1,036 ms** at 6 KB+, isolated);
**HIGH priority IS granted (15 ms/latency 1/1M) and changes nothing**, so the wall is above the
interval; **the phone's loop CPU is 74–127 ms handling + 53–84 ms assembling per flush** (PC 4 + 2)
— a term as large as the ack floor; lost fragment acks (49 in five days, two 25–48 s stalls) hold
a window slot for a whole msgId cycle. **Then §34 (evening): the pending-ack release and the CPU split BUILT, APK 0.31 installed, the
walk REPEATED** — the wire unchanged (same medians), the phone's CPU **~63 % painting / 27 % diff
and plan / 8 % truth / 2 % compression**; and §33.4's "49 lost acks" were mostly the carrier
CREATE's eaten re-sends at session start (a false alarm that raised a notice on the glasses every
session — fixed: a control pending's counter-cycle is a journal `Note`, not a fault). **APK 0.32
staged** with the paint split (slides / chrome / overlays / text draw). **Next: install 0.32, walk,
read the paint split, then cut the largest part; the eaten-CREATE 2 s re-ask (~6 s per cold start)
is a radio experiment for Adam.**

---

**Before it, 2026-09-05: a FIFTH whole-codebase review and a third full LIVE walk — nineteen
verified defects, all fixed and pinned.** `HANDOFF.md` §30 is the record. Three of them matter more
than the rest:

- 🔴 **A wheel closed mid-spin never stopped spinning.** `Switcher.spinning` stays true, the frame
  loop posts another Pump for as long as it is, and `isQuiescent()` reads the same flag — so a
  scroll then a tap inside the four animation frames left the shell looping empty frames for ever
  and never idle again, on the glasses as much as in the harness. `OracleWalkTest` found it; the
  worst settle in a clean run is 46 ms, which is how a 120 s bound firing turned out not to be load.
- 🔴 **The standing `--selfcheck` oracle failed one run in ten, and always had** — 2 in 20 measured
  on the unchanged tree. Its SAMPLE was torn: `isQuiescent()` from another thread, then composed,
  the plane map and both panels read one after another across a window the shell repaints inside.
  `Shell.sampleIdle` takes the reading on the loop now. 20/20 clean.
- 🔴 **The Hold'em seat strip drew every opponent's stack through the board** at 288 with the scale
  at 130 % (a 120×34 cell against 52 rows of want). The seat faces are measured, and where two rows
  will not fit the strip goes COMPACT — one row, the money placed first and the name fitted after.

The rest, in one breath: the notification box's source rule and the context menu's title rule both
struck the text they bracketed (constants under faces that ink more, §27's rule one layer down);
the chrome clock's AM/PM marker sat inside the last digit above 100 %; the medium seven-segment
minute pair was a 28 px pitch among 24s; the Games documents sized their lines from `lineHeight`,
which is SHORTER than the ink, and the scroll path chopped every descender; the Files locations and
trash lenses drew nothing at all for an empty list; `TableLayout`'s "the bottom bands give way" was
not true; a line box must be the LARGER of line height and ink (AWT ceils them separately — JBM 16
inks 25 in a 24 px line at 115 %); the selfcheck's oracle kept watching the STOPPED shell after its
restart scene; the tmux staleness line reached only the live pane and Torrents' only the transfers
list; a STAGED settings row claimed "scroll adjusts live"; Music offered Resume/Next/Previous with
an empty queue and did nothing when tapped; and the notification box hung from its box's top edge
instead of being centred on its own height; and — from a fourth pass that swept §30's OWN classes
across the tree rather than hunting new ones — the Reader's library level drew nothing at all for an
empty shelf, the same defect as Files by a different route (a row count of zero makes
`ContentKit.paintList` return before it draws anything).

⛔ **Not deployed.** The `damage` service still runs the §29 build and APK **29/0.29** is what is
staged; **0.16 is still the last APK observed installed**. Deploying is `stageJar && rc-service
damage restart` — Adam's call, in the moment.

⚠ **One thing left open** (`HANDOFF.md` §30.6b, carried forward to §31.8): `OracleWalkTest` failed
once more after the wheel fix — the same `queued=1 reports=0` shape at `h=288 step 198` — and it has
since been seen in four more classes at about one run in four. **Still open on 2026-09-05**, and the
investigation narrowed it: the widened thread dump finds NO thread anywhere with a `wm.damage` frame,
so the loop is parked or ended, not busy; `msgs` is `Channel.UNLIMITED` and never closed, so a lost
message is not it; `loopLaunched` is never reset but `startLocked` launches unconditionally, so a
same-instance restart is not it either. `quiescenceReport()` now prints `LOOP-ENDED` or
`in=<Msg>/<ms>ms` — the next failure names which. Eight clean runs followed the instrumentation;
that is suggestive, not proof. Read the report first when it fires.

**Battery at HEAD:** core **459** · desktop **11** · selfcheck **189** (oracle 283 runs, 20
consecutive clean) · snapshots 49 × three runs · `--games-check` · `--music-check` ·
`--epub-check` 58/58 · lint 21 rules / 0 + selftest · `:phone:assembleDebug`.

---

**2026-09-05, after the §30 round: canvas scrolling ships the TRANSLATION** (`HANDOFF.md` §31).
Adam on glass: *"scrolling text in apps like Tmux is really slow, like 1-1.5 full seconds."* It was.
`ListView` and `DocView` scrolls have always declared their shift (mode 9 on the device, only the
new strip on the wire); `CanvasView` — tmux's pane and scrollback, Music's lyrics, the Hold'em table
— never did, so a scroll shipped the whole content area: **7.4–10.8 KB measured**, and 6–12 KB
measures a **1,193 ms median** on the glasses. The shell now DETECTS the translation by comparing
the frame before a canvas repaint with the frame after and declares it — detected, not reported by
each window, so windows nobody has written yet get it too, and so does a pane the terminal itself
scrolled. **~11.1 KB → ~5.4 KB measured**; ~1,193 → ~526 ms modeled through the measured table.
No rendered surface changed — measured by keeping both installs on disk and snapshotting them
back to back in one minute: 46 of 49 scenes differ only inside the status line's live
throughput readout, `10-silent.png` (no status line) is byte-identical, and the other three are
the live-data scenes. ⚠ A plain `diff -rq` between two snapshot runs always reports all 49.

🔴 **And a separate finding from the same measurement** (§31.6): `overview.md` §5.2's
`ms ≈ 60 + bytes/50` describes FOUR HOURS. A step change on 08-31 leaves the floor intact and
collapses the transfer term ~6× — 6–12 KB goes 196 ms → 1,193 ms, ~50 KB/s → ~7 KB/s — and 10,063
of the journal's 11,210 flushes are on the slow side. Adam's own on-glass number agrees with it.
Price work with §31.1's table. WHY it changed is untested: a second BLE central, distance, a
connection-interval renegotiation and a write-path change are all open.

**Picking this up in a fresh session:** `CLAUDE.md` → this file → `HANDOFF.md` §19–§30, then
**§30.7**. The live-walk driver (§28.2 / §29.2) is the instrument to rebuild first — and the
§29.2 lessons bind: snap between steps, one step per snap in any window with a destructive row,
count only panel frames as activity, never rebuild the jar under a running instance. 🆕 And one
more from §30: **the harness is part of the system under review** — two of the nineteen were in
the gates themselves, so when a test bound fires, measure the normal case before calling it load.

---

**Before it, 2026-09-04 (evening): a FOURTH whole-codebase review and the second full LIVE walk —
eleven verified defects, deployed.** `HANDOFF.md` §29 is the record. The reading found the LIST
RHYTHM: Clear Sans 18, every list's row face, inks 27 px at 100 % and the 32 px row held it
exactly, so at 115 % the row directly above the lens lost its descenders to the lens fill — and the
oracle is blind to it, because the ink stays inside the damaged rect. `Layout` carries a measured
`rowH` / `lensH` (floors 32 / 64) and every second lens line goes through `Draw.lineBelow`. Also:
"114%" for the 115 % step, a failed transport start racing its lease release, an unguarded socket
close. The walk then found the tmux alert notice was app-less (a tap only dismissed it), the shell
loop caught `Exception` but not `Error` (a frozen display behind a healthy status), brightness could
never return to auto, and the context menu's fixed width cut labels at the chrome cap. Deployed
2026-09-04 22:00 — the service still runs that build.

---

**Before it, 2026-09-04 (late): a THIRD whole-codebase review, and the first full LIVE walk of
the system — eleven verified defects, all fixed and pinned.** `HANDOFF.md` §28 is the record. The
reading found the Hold'em pacer STALLING after a back-and-return inside one bot's pace (the table
sat until a tap, which then skipped the pacing), the Reader unable to open ANY book at 115 % or
130 % (a constant 30 px line box with a guard that refused the measured 34/36), an unreadable
`config.json` being REPLACED with defaults (credentials and tmux hosts gone on the next start),
Main describing scans nobody had started, and a tmux line inked past its rect. Then the program
was driven live through the browser replica — every window, all four heights, the 130 % ladder,
Alegreya chrome, menus, notices, the wheel, the keyboard, silent mode, a Hold'em hand through a
cash-out — and that found what the reading could not: **every chrome surface with a hand-picked
rhythm (the menu, the notification box, the switcher) broke at the top of the font ladder, the
Hold'em status line was cut by the hole-card plane under a per-app scale, and tmux never alerted
for a pane that had not filled its screen** (`capture-pane | tail -5` was five blank rows). All
now measure the face with the design numbers as the floor — 100 % is pixel-identical — and the
alert fires. ✅ **Deployed 2026-09-04 18:16**: the `damage` service runs this build (`standby up
(§19)`, the phone reattached to every channel) and APK **28/0.28** is staged on the setup page.
**0.16 is still the last APK observed installed** — installing 0.28 is the one manual step.

**Battery at that commit:** core 430 · desktop 11 · selfcheck 189 (oracle 282 runs) ·
snapshots 49 · `--games-check` · `--music-check` · `--epub-check` 58/58 · `--card-render` · lint
21 rules / 0 + selftest. (The current numbers are at the top of this file.)

---

**Before it, 2026-09-05: A WHOLE-CODEBASE REVIEW SHIPPED — six verified defects, and the
belief-vs-truth oracle is now a STANDING GATE.** `HANDOFF.md` §27 is the record. What it found:

1. **A Games cash-out was booked as a total loss** — `prize − myStake` with no credit for the
   chips the cash-out had already moved into the bankroll, and no entry fee either, so Adam's
   lifetime net was the one figure in the standings that ignored verdict 24. Pinned in
   `Review20260905Test`, which fails against the unfixed tree (`HOLDEM.md` §17.2d).
2. **Two gates measured nothing.** `--music-check` counted "tracks with art" through a catalog
   built with an always-false art predicate — structurally 0 on any shelf — and printed the art
   extraction rather than asserting it. `--games-check` printed a money-supply "drift" as a
   head-to-tail ratio, which reports a large number for any monotone series and so cannot tell
   §5.3's flattening from its named failure; it asserted nothing at all. Both now measure and
   gate. 📏 The real answer over 10,000 tournaments: **the growth RATE falls** (~$130 k → ~$95 k
   a bucket) while the total still climbs +387 %.
3. 🔴 **Three drawing defects the shell's own divergence check cannot see.** It compares its
   BELIEF to the glass, so ink painted into `composed` that no damage rect ever carried is
   invisible to it. The Music Mode card's progress row inked 4 px past the card at 288 and 352;
   chrome text ran into the divider at 130 % and below the safe rect at the tallest face and a
   reduced height; the status bar had been running 2 px past its own bar since it was drawn.

4. **Three defects in the snapshot harness itself, behind one failure that moved around**
   (`HANDOFF.md` §27.6). The settle re-tested `isQuiescent()` after its wait loop had already
   passed it — a race against the clock tick that reported a succeeded settle as failed, with an
   empty pending list to show for it; the showdown scene assumed one action ends a hand, when
   Check and Fold are one contextual row and a free check just brings the flop; and the games
   world was seeded from the wall clock, so those scenes were a different tournament every run.
   ⚠ **Run a harness more than once** — every one of these was invisible in a single run.

**The instrument, and the keeper:** the 2026-09-03 review's oracle — recompute the per-lens TRUTH
of `comp.composed` under `comp.planes` and compare THAT to the firmware model — was never
committed. It is now two things: `--selfcheck` runs it on **every settle** (279 of them, over
every real window with the real faces) and `OracleWalkTest` runs it over a **seeded random walk
of the §1 grammar at all four heights**, asserting its own surface coverage. The selfcheck also
grew three passes it did not have: Music Mode driven with the queue ADVANCING (so its surfaces
repaint as deltas — the only state that exposes ink outside a declared rect), the whole window
set again at 130 %, and again at the tallest face at 480 AND at 288, because at 480 an
overflowing chrome line is clipped away by the panel edge and looks fine.

⚠ **Nothing has changed hands on glass**, but both sides are DEPLOYED: APK **27/0.27** is staged
with this review's fixes (the setup page) and the `damage` service was restarted onto the review
build 2026-09-05, coming up `standby up (§19)` with the phone reattached to its channels.
**0.16 is still the last build observed installed — installing 0.27 is the one manual step.**

**Battery at that commit:** core 421 · desktop 9 · selfcheck 189 (oracle 279 runs) ·
snapshots 49 (eight consecutive clean runs) · `--games-check` · `--music-check` ·
`--epub-check` 58/58 · `--card-render` · lint 21 rules / 0 + selftest · `verify_cfw.py`.
(The current numbers are at the top of this file.)

---

**Before it, 2026-09-04: GAMES · HOLD'EM IS BUILT** — M1–M6 overnight and unattended, then **three
full review cycles** at Adam's word: 19 defects + a live session (13); 16 more + 2 coverage gaps;
then 11 more + 2 test-quality fixes. `HOLDEM.md` §17 is the deviations list and the shortest useful
read (§17.2b and §17.2c are the second and third cycles); `HANDOFF.md` §26 is the narrative.
The third cycle's keeper: **an asynchrony introduced to hide a cost nobody measured is a defect
generator** — the play-out hand-off was on a coroutine because a comment said "seconds"; it is
13 ms, and moving it onto the loop deleted two defects and the code that made them. It also changed the **shell**: `ActivationSource` on the window
contract — **switcher = resume, Main = the window's root list** — retrofitted across all six
existing windows, and `contentPlanes` so a window can name its own stereo regions. The app layer
is now **Main · Settings · Reader · Tmux · Files · Torrents · Music · Games**.

⚠ **Nothing about Games has been seen on the actual glasses.** Every judgment — the card art, the
hole-card plane, the arc stagger, the pacing — was made on the byte-exact simulator at true 1×.

**Before it, 2026-09-03 (late): a WHOLE-CODEBASE REVIEW shipped — ten verified defects, each
reproduced before it was fixed and each pinned by a test that fails without its fix**
(`core/.../Review20260903Test.kt`, `HANDOFF.md` §25). Commits `1f9fa4d` (the fixes) and
`c400d0b` (APK 23/0.23), both pushed; the `damage` service was restarted onto the review
build and the phone reattached to all five channels.

The technique worth keeping: the mirror/divergence check compares the compositor's BELIEF to
the GLASS, so a bug that writes wrong pixels into the shadow **and sends them** is invisible
to it. The review's oracle recomputes the per-lens **truth** of `comp.composed` under
`comp.planes` and compares that to `comp.expectedLens()` after every settle; a 900-step
random-gesture walk with that assertion is what found the notification overrun. ⚠ Split the
panel by plane **pieces**, not raw plane rects, or the oracle reports false pixels at the
lens edges.

What it changed, in one line each — the detail is `HANDOFF.md` §25:

1. **Compositor** — a plane-0 delta could carry another plane's pixels (two unguarded merge
   paths). Wrong shift on both lenses, outside the scanned area, belief and glass agreeing on
   the wrong thing. Guarded; rect economy unchanged.
2. **Notifications** — the silent/exclusive box is 200 px but its body was wrapped to the
   248 px window form: 240 px painted outside the damaged rect. `SILENT_W` + per-form
   `bodyLines` + fitted draws.
3. **Reader** — `Epub.fold` maps the C1 mojibake range through Windows-1252, folds U+2011 and
   drops zero-width formatters; every dynamic string now goes through `Draw.dynamic`. Measured
   on the real 58-book shelf: **14,365 undrawable code points → 50**.
4. **Tmux** — `FlowRender` sanitizes at layout time (the live panes carry Claude Code's own
   TUI glyphs, which JetBrains Mono lacks).
5. **Music** — a restored level below the top now loads on the way back; the desktop mirror no
   longer publishes an empty player record (that is the shell's removal TOMBSTONE, on a
   syncable key); the quiet-stream notice re-arms when you raise the volume it told you to.

**Before it, the same day: the Music window's root became NOW PLAYING, and the "it played but I
heard nothing" bug was understood.** Adam drove the new windows for real: Tmux, Files and
Torrents all worked; Music played four tracks end to end and made no sound. The cause was
measured, not guessed — the transcode cache showed song-length gaps (so the queue really was
advancing) and the synced player record read `volume: 8`. **The phone's media stream was at
8 %.** Damage never sets that level (only the ring's Volume canvas, the Settings row and the
limiter's restore write it, all upward or user-driven); it read 8 % and played into it. The
defect was that *nothing said so*. `HANDOFF.md` §24.4 is the full record.

Three things came out of it:

1. **A quiet-stream notice** — `PlayerEvent.QuietStream` fires when playback starts at or below
   `PlayerCore.QUIET_PCT` (10 %), once per playback RUN, on glass and as a notification when the
   window is off screen.
2. **The output is restored by STABLE identity** — `AudioDeviceInfo.getId()` is a per-connection
   handle that changes on reconnect and gets reused, and the old code ignored the sink's refusal.
   The record now carries `outputName` + `outputKind`; an ambiguous or absent match refuses
   loudly to Auto instead of routing to whatever inherited the number.
3. 🔴 **Verdict 4 REVERSED (Adam): NOW PLAYING is the root, the queue is a menu row.** *"the main
   screen should be a useful, really nice looking Now Playing screen … what is playing and where
   in the song it is and what the volume level is at."* A canvas, TOP-aligned (his fit loses the
   bottom): art 160/120/88 px by height + title + artist — album + badges · the state glyph,
   elapsed, progress, total · the media level (drawn **HOT at or below 10 %**) with the queue
   position · the current synced lyric line when one is loaded. **Scroll = volume live, tap = the
   Music menu**, no cursor. `MUSIC.md` verdict 4 records the reversal — do not reinstate the
   queue root. A review pass over that new code found and fixed seven more issues
   (`HANDOFF.md` §24.4).

Before it: **MUSIC** built whole overnight 2026-09-01/02 and reviewed three ways (`MUSIC.md`,
`HANDOFF.md` §24.1–§24.3); **TORRENTS + the §4.8 keyboard** and **FILES** with the whole
EXPLOSION §16 machinery on 2026-09-01 (`TORRENTS.md`, `HANDOFF.md` §22–§23). Reader / Tmux /
Files / Torrents / Music are the worked precedents (`WINDOWS.md`).

**State of the world:** the phone APK is the primary driver (`HANDOFF.md` §19 — radio + shell
while it is up); the OpenRC `damage` service is the data provider (content + tmux + sync + the
window channel on :7401, seam :7402, replica :7403, the media endpoint :7404) plus a standby that
BLE-drives only while the APK is away. Battery at HEAD: core **430** · desktop **11** ·
selfcheck **189** · snapshots 49 · epub-check 58/58 · lint 0 · `--music-check` and
`--games-check` all pass. ✅ **The jar and the `damage` service RUN THE GAMES BUILD**
(deployed 2026-09-04 13:07; `standby up (§19)` in the log, the phone reattached to all five
channels — files, tmux, music, torrents, sync — and the PC claimed nothing, so the display was
never touched). **APK 27/0.27 is STAGED and is the one to install** — Games plus all three
review cycles plus the whole 2026-09-05 review (§27); superseded in turn by **28/0.28**, staged
2026-09-04 18:16 with the §28 review, the same moment the service was restarted onto it. **0.16 is still the last build observed INSTALLED**
(2026-09-01). Redeploy the PC side with
`./gradlew :desktop:stageJar && sudo rc-service damage restart` (never touches the display — the
PC does not claim). ⚠ One central at a time: stop the service before any `:desktop:run` dev
session; G2CC's Android bridge stays Disconnected.

📍 **Start here, in this order:** this file → `HANDOFF.md` §19–§26 (the topology contract, the
build records, §24.4 the silent-playback diagnosis + the Now Playing root, §25 the review,
§26 Games) →
`DAILY.md` (ops crib) → `IMPLEMENTATION.md` (what runs) → for the next conversion: `WINDOWS.md` (the checklist) +
`EXPLOSION.md` (§16 contract, §20 refinery verdicts, the chosen window's section). Standing
references: `overview.md` (facts), `CLAIMS.md` (grades), `CLAUDE.md` (rules), `DESIGN.md` (the
shell).

## 🚀 Next

1. **Install the APK and play Hold'em on glass.** Both sides are deployed: the service runs the
   §28 build (restarted 2026-09-04 18:16, `standby up (§19)`, the phone reattached) and
   **28/0.28** — Games, all three review cycles, the 2026-09-05 review and the 2026-09-04-late
   review (the pacer stall, the Reader at 115/130 %, the chrome rhythm, the tmux alert) — is
   staged.
   Download it from the G2CC setup page's `/damage-apk` and install over the top;
   `MY_PACKAGE_REPLACED` restarts the phone service itself. What is
   owed on glass: the card art at all four rungs, the hole-card
   plane's depth, the arc stagger at 416/480, whether the bot pace of 600 ms feels right, and
   whether the ≈8–10 % ink target holds with a real board out. `HOLDEM.md` §17.4 is the list.
2. **Use Music once the review build is on the phone.** It carries the Now Playing root, both phone-side fixes
   and the whole-codebase review. Then the one-time grants (`DAILY.md` → Music: `music access`
   on the strip, notification access) and the on-phone measured items (the limiter's real notice text, the Spotify cold start, the Bluetooth
   lyric offset, the visualizer rate on glass). Judge Now Playing on glass — it measures **14.0 %
   ink** at 480 against the 15 % list budget with the harness's synthetic art, so a real album
   cover may trip it; the answers then are smaller art or reclassifying the surface as a canvas
   (Music Mode's note allows 30 %).
3. 🎴 **The NEXT window is Adam's pick.** Games is built (`HOLDEM.md`, `HANDOFF.md` §26) and
   struck off `EXPLOSION.md` §20's order; **Feed + comics** (§20 #5) is the standing next
   candidate. ⚠ Whichever it is, the licensing rule revised 2026-09-02 (`CLAUDE.md` clean-room)
   applies to any window that drives someone else's work: the work never ships, the WINDOW that
   drives it may — Paperclips fetched from the author's site at run time (and Damage generates the
   DOM from element ids rather than shipping his markup), FF1 on a ROM the user rips themselves.
   Hold'em is entirely ours and needed none of that; the card kit under `windows/games/kit/` is
   built for blackjack, hearts and gin, and none of them is built.
4. **On-glass verdicts still owed** for Torrents and the keyboard (the transfers list and lens, a
   real done-notification, browse / search / add against the live tracker; the keyboard's row
   pitch at 288, the highlight, stay-in-row), for Files (the menu grammar, viewers, the thumbnail
   lens, theme icons at 20/56 px), and the live checks — the standby drill (stop the APK at the
   desk → the PC BLE-drives → restart → handback) and a book position following a driver swap.
   Then the resumed Torrents review pass from `980d832..HEAD` — round 4 still found real defects
   in round 3's fixes.
5. **The Reader transitional cleanup** (UNBLOCKED — 0.16 is installed): remove the
   legacy-offsets dual-write in `ReaderWindow` — the fields are marked; `restoreStateLive`'s
   `liveMapApply` mechanics go with them (update `SubstrateTest`'s migration pin). A clean first
   task for a fresh session.
6. **The icon-quality pass** (front of the app wave): one drawn icon per app at 20 px + 56 px —
   the drawn set is the fallback and the release path (theme icons are personal-lane only).
7. **What the reviews have and have not covered.** ✅ 2026-09-05 closed §25.3's gap: the seam,
   the replica, the sync and window channels, the firmware simulator, all four transports, the
   whole shell and the compositor were read line by line, and the phone module too. What was read
   for its RISK SURFACES rather than end to end: the music / torrents / tmux provider leaves and
   the desktop harnesses — command construction (all through POSIX `shq`), SQL (every
   interpolation is from a literal list; all data is parameterised), credential handling, HTTP
   framing. ✅ 2026-09-04 (late) then read the leaves end to end as well — every window, the
   Hold'em engine and kit, the desktop harnesses, the phone module, the Python tooling — and
   DROVE the program live (`HANDOFF.md` §28). Still true: **nothing has been tested on the actual
   glasses.** A next round starts on glass, or with the live driver against a fresh build.
8. **Watch-items:** the left-lens seam residue (a one-shot early-burst tear — if it recurs
   after a handover, harden session start); the ~20 s silent-loss window (tighten the seam
   heartbeat constants only if it feels long in practice); and the media endpoint logs nothing on
   a successful request, so "did the phone fetch audio?" is only answerable from cache mtimes.

## 🔴 Still unmeasured on glass

Closed items live in `HANDOFF.md` §11–§12 and `overview.md` §5 (ack curve, link-drop recovery,
PC BLE, takeover/fallback, the switcher root cause, unsolicited frames). Still open:

| # | what | why it matters |
|---|---|---|
| 1 | **Safe area** — draw a border, shrink until fully visible, store it | `DESIGN.md` §2.2b: 480 vs 288 is a *calibrated setting* |
| 2 | Ring **fast-spin coalescing + event-rate ceiling** (per-notch delivery itself works daily) | the focus model's limits |
| 3 | **Comfortable disparity** — ramp 0/4/8/12/16 | and whether stock FAR already spends the budget |
| 4 | **The rect budget of 5** (graded I) | derived from `cfw_diag()`, never observed; failure is silent |
| 5 | **Two-arm BTSnoop capture** | settles the bulk-LEFT / control-RIGHT split (graded I) |
| 7 | **msgId-255 behaviour under CFW** | it ends the link on stock |
| 8 | **Chrome legibility** at the real faces on glass | renders cannot answer it |
| 9 | **WEA/CMAS visibility to a normal Android app** (Pixel 10a) | `DESIGN.md` §4.5's emergency promise rides on it |
| 10 | **Connected RSSI** — both paths read it from the RIGHT arm (`BleTransport` every 10th maintenance tick; `BlueZTransport` from BlueZ's `RSSI` property, which BlueZ fills only while a scan sees the device); no value has been checked on glass | the status bar's link cell |
| 14 | **The stall report** — force a lost image ack (RF shielding) | must show `stall!` with the link otherwise healthy |
| 15 | **Is the sid-0x01 prelude required** by the CFW before CREATE? (graded U) | sent because the reference sends it; `LaunchMsg` says where to change it |
| 19 | **The texture cache on glass** — mode-12 atlas up, 13/14 draws, pixel-compare vs the sim | the gate on adopting cached glyphs. ⚠ mode 14 adds one overlay rect per glyph (a >16-glyph string shows incomplete OUTLINES — the overlay, not a fault); a failed 64 KiB allocation shows only as the sticky `ALLOC` flag |
| 20 | **Atlas upload cost** at the measured rate; cache survives a lease *renewal*, is freed on a lapse | prices the whole mode-14 trade |
| 21 | **Temple long-press accident rate** (gloves, coat pocket) — either temple raises event 9 since a5d1c31 | §1.2's bare-long-press no-op guards it; confirm the default still feels right |

**Start BTSnoop BEFORE connecting** on any recapture — handle 65's connection setup is the one
gap in the corpus.

**Cheap probes nobody has run:** the CFW logger service (sid 0x0F — would turn silent
`decompress failed` into a visible error; highest-value untested lead) and the file-export
service (sid 198/199 — the only lead against "no firmware read-back"; its error enum has an
explicit `NOT_SUPPORT`, so the probe is safe).

## Open design questions (not hardware-blocked)

- **Where system-state detail lives** — live telemetry is in the status bar; the deeper view
  wants to be the Info window (`EXPLOSION.md` §9 + the §20 useful-stats steer).
- **Per-window typefaces for windows not yet designed** — SMS, Notices, Feed inherit
  Clear Sans until their app earns an override (Files and Music shipped on Clear Sans, Torrents on Fira
  Sans — the list face). Deliberately not invented in advance. The curated font-library
  expansion is a separate backlog item (`DESIGN.md` §Type; option-only, defaults untouched,
  B612 never a default).

## System changes made for this project

- **`/etc/portage/package.accept_keywords/damage-fonts`** — `~amd64` for eight font *data*
  packages (no code) so the typeface survey could run. Safe to remove; the fonts stay.
- **56 `media-fonts/*` packages installed** (453 families, 2026-09-02 count). `design/fonts.json` pins the 66
  evaluated candidates. The locked faces are Clear Sans, Fira Sans, Alegreya and JetBrains
  Mono — **`tools/lint.py` checks glyph coverage against exactly those**; its table must grow
  if a window ever claims a fifth face.
- **`net-p2p/qbittorrent-5.1.4` rebuilt with USE `webui`**
  (`/etc/portage/package.use/60-qbittorrent`) for the Torrents window; its config gained
  `WebUI\Enabled=true`, `Address=127.0.0.1`, `Port=8090`, `LocalHostAuth=false` and the Web-UI
  password qBittorrent insists on (`~/.config/qBittorrent/webui-credentials.txt`, 0600) —
  `DAILY.md`. The TorrentLeech credentials live only in `~/.damage/config.json`.
