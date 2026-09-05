# WINDOWS.md — converting a G2CC app into a DamageWM window

**The build-facing distillation of the app-conversion contract.** Written 2026-09-01 from the
general-topics session with Adam that settled `EXPLOSION.md` §16; that table holds the design
detail and statuses, this file holds the checklist a conversion actually follows. Precedence as
always: `overview.md` on facts, `CLAUDE.md` on rules, `DESIGN.md` on shell design, `EXPLOSION.md`
§16 on the shared contract — this file distills, it never overrides.

**Inputs per window:** the window's `EXPLOSION.md` section (with Adam's refinery verdicts),
`DESIGN.md` §4.6 (modes + what a window declares), `core/…/shell/WindowContract.kt` (the contract
as code), and the G2CC original (`/home/user/G2CC/server/src/windows/<app>.ts`) — **read-only,
interaction facts only, no code taken** (clean-room rule, `CLAUDE.md`) — when one exists
(Torrents had none; step 2 then has nothing to mine).

**The six worked precedents:** `ReaderWindow` (List → Document → Actions, async content,
per-item sub-records, images), `TmuxWindow` (Canvas, a live provider over the content port,
quick keys, typed text with confirm, alerts), **`FilesWindow`** (2026-09-01 — the
tap-=-context-menu grammar via `MenuSurface`, the §16.10 window channel via
`FilesService`/`RemoteFilesProvider`, viewers as strip/wrapped DocViews, paced-retry failure
discipline, `Draw.fit`/`dn()` display hygiene), and **`TorrentsWindow`** (2026-09-01 evening,
`TORRENTS.md` — no G2CC original, built whole; the keyboard as a requester, version-cursor
snapshots + event replay over the channel's blob lane, a LIVE list whose cursor follows row
identity, announcements decided host-side once), and **`MusicWindow`** (2026-09-02,
`MUSIC.md` — a window written once against two core contracts, `MusicLibrary` + `MusicPlayer`,
so the same code runs with the phone's ExoPlayer or the desktop's read-only mirror; the
window channel's PUSH slice, a second endpoint (:7404) for media bytes, per-height layouts
for every level and for a whole-panel EXCLUSIVE mode — `DESIGN.md` §4.9 — and a per-app
Settings category of thirty rows), and **`GamesWindow`** (2026-09-04, `HOLDEM.md` — the first
window with **no host at all**: pure Kotlin, nothing outside itself, so it runs identically in
every `DESIGN.md` §10 configuration; a `CanvasView` table at four heights; its own **stereo
planes** via the new `contentPlanes`; a reusable game kit under `windows/games/kit/` that names
no card game; a world that advances only while you are looking at it; and a determinism contract
where the persisted record is an ACTION LOG the engine replays). Read them before writing a
seventh — Files and Torrents are the worked examples of MenuSurface and WinNet, Torrents of the
keyboard, Music of a two-host contract, push frames and the exclusive mode, Games of a
host-free window and of a canvas that owns its own depth.

---

## 1. The bar every window meets

These are the non-negotiables, each with its authority:

- 🔴 **100 % cross-device continuity** (Adam, 2026-09-01: *"an always-active session that can be
  continued seamlessly from every device connected to DamageWM"*). Two layers serve it: the
  replica (same session, any screen — built) and LWW sync (separate shells converge). The window's
  part: per-item state in **sub-records** (`window.<id>.<item>`) wherever per-item state exists;
  content declared continuable (cache-on-open, the Reader precedent); the **continuity test**
  (checklist step 6 below) passes.
- **The input grammar is not negotiable** (`DESIGN.md` §1): tap descends, double-tap backs,
  scroll moves focus, long-press is a no-op by default. No per-window gestures, ever. Actions are
  a LEVEL reached by tap/wrap, not a region (§4.6).
- **NO TRUNCATION, worded honestly** (§2.4 r3, 2026-09-01): content wraps/scrolls and is never
  cut; rows/titles are handles, elided only through the kit's fit helper (mark + reachable full
  view in the lens). **Titles are short by design** (§4.1) — variable content goes to the body or
  a notification, never chrome.
- **Misfire tolerance** (§1.7): cursor rests on a harmless cell after every level change;
  destructive rows never at cursor rest, never index 0/1; every navigation undoable by
  double-tap.
- **Every destructive or outbound act stages a confirm** — deletes, sends, ending a session,
  typed text (`onTypedText` always stages; the Tmux TYPE_CONFIRM shape). Recorded exemption: a
  read-only query — the Torrents search — commits without one (`TORRENTS.md` §3.1).
- **LOUD failures** (the absolute rules): provider errors ride the one-shot notice on the title;
  staleness is said with duration (`PC unreachable 40s`); a missing need marks the window
  unavailable in Main and says why (§10.5). No timeouts anywhere — pacing loops and liveness
  decisions only.
- **Notification toggles live in the app's own Settings category** (Adam, 2026-09-01: *"each
  app's notification settings [go] within that app's category in the settings app, rather than
  within Global"*). The window gates its own `notifyInternal` calls on its rows (the Tmux
  `Alerts` shape); Global keeps only `Notify · Damage`, the WM's own events.
- 🔴 **Every window works well at all four heights — 288 / 352 / 416 / 480 — and its size is
  adjustable in Settings** (Adam, 2026-09-02: *"ALL of the apps should work well at each of those
  four sizes, and the size of each app should be adjustable in the settings. This is important."*).
  Each level's layout is specified per height in the window's design record (rows above/below the
  lens, document lines, canvas stacks); the per-app Size row is not optional; the snapshot scenes
  cover 288 and 480 at least.
- **Nothing specific to Adam's current setup is baked in** (2026-09-02, the Music verdicts):
  what is a preference today (mono, one earbud, a work-day fit) is a Settings row with his value
  as the default; release defaults are chosen later in a global pass.
- 🔴 **A lens line sits below the previous line's MEASURED ink, never at a constant** (2026-09-04,
  `HANDOFF.md` §29): `Draw.lineBelow(tx, f1, line1Y, designY)` — the design offset at 100 %,
  lower as the face grows — and anything under a line (a progress bar, a third line) the same
  way, dropped rather than drawn through the band's bottom rule when it no longer fits. The row
  pitch and the lens band the kit hands a window grow with its face (`Layout.rowH` / `lensH`,
  measured by the shell); paint into the rect you are given. A constant that fits at 100 % is a
  cut at 115 %.
- **`summary()` is cheap and side-effect-free** — it reads cached state pushed by the provider,
  never spawns work (the G2CC preview/view lesson, §4.6).
- **Preview is a render, never an activation** (§4.3 rule 1): lifecycle hooks run on commit only;
  deep-link opens included. Previewing Mail must not mark mail read.
- **Ink budgets**: List ≤ 15 %, Document ≤ 25 %, Canvas is the window's call (linted as a
  warning). Structure from spacing and brightness, never boxes or fills (§4.2).
- **Clean room**: no g2flash/faceclaw code; no third-party assets in release builds; wire
  constructions cite their source file.

## 2. What a window declares (the contract, one screen)

`DamageWindow` (`WindowContract.kt` is the authority): `view()` (List/Doc/Canvas per level) ·
`title()` (SHORT — §4.1 contract) · `summary()` (cheap; `more`/`progress` flags) · `icon`
(an `IconKind` — the theme-icon system resolves the desktop theme first, the drawn kind is the
fallback and release path) · `dirty` · `needs` (HOST/PHONE_APIS/BLE; a per-BACKEND `needs`
contract is still unbuilt — Music declares them per host: none on the phone, HOST on the desktop
mirror — and keeps its backend fallback inside the player) · `preferredHeight` (global-default pattern) ·
`appSettings()` (its Settings category; STABLE instances) · `saveState()/restoreState()` (mode
included, §9.1) · **`restoreStateLive()`** (a live-synced main record — override when the boot
restore assumes an activation follow-up; Files/Tmux/Reader are the precedents) ·
**`saveSubState()/restoreSubState()`** (per-item records, §16.4; empty object = removal
tombstone) · `styleTransform`/`styledText()` (route EVERY measure/draw through it) · `back()` /
`levelDepth()` · `onTypedText(line)` (confirm-staged) · **`open(target)`** (§16.1, BUILT:
opaque per-window target, false = loud; Files→Reader ships on it) ·
🆕 **`contentPlanes(content)`** (2026-09-04 — the stereo regions this window wants inside the
content area, up to `Shell.MAX_WINDOW_PLANES` = 4; the shell validates and caps them, and an
empty list means "the content plane, as always". Games brings its hole cards forward with it) ·
the lifecycle hooks
(`onRegistered`/**`onActivate(ctx, from)`**/`onDeactivate`/`onLayoutChanged`/`onFontScaleChanged`).

🆕 **`ActivationSource` (2026-09-04) — Adam's general rule, not a Games detail.** `onActivate`
now carries where the focus came from: `SWITCHER` **resumes** exactly where the window was;
`MAIN` presents the window's **root list**; `DEEP_LINK` goes to the target; `RESTORE` is the boot
path and changes nothing. Every window with more than one base function implements it. Two traps
the retrofit paid for:
- **Reset the cursor AND the container**, not just the level. Reader's Main entry left a
  *subfolder* open — depth 2 — so one double-tap ascended inside the window instead of leaving
  it. Assert `levelDepth() == 1` after a MAIN activation.
- **A window whose root IS its live surface has nothing to reset.** Music's root is NOW PLAYING
  (`HANDOFF.md` §24.4); do not turn its Main entry into a browse list.

`ShellServices`: `requestRender` · `setOperation` · `notifyInternal(source, body, urgent,
appId, thread, target)` (§16.5 — tap = commit + activate + open(target)) ·
**`openMenu(spec, owner): Boolean`** (the floating context menu; pass `owner` from ASYNC
completions — a false return means deliver the answer as a notice instead) ·
**`openKeyboard(spec, owner): Boolean`** (§4.8 — the same refusal rules; `onCommit(text)` runs
AFTER the keyboard closed, `onCancel(draft)` hands the draft back — the requester owns it) ·
`openWindow(id, target)` (the hand-off; the shell records the caller for back-to-caller) ·
`icons()` · `runOnShell` (EVERY off-loop completion applies through it) ·
`docContentWidth/Height`.

Plus, per window: its **notification sources** (each with a Settings toggle, a coalesce key and a
deep-link target — §16.5) and its **provider(s)** on the window channel (§16.10: Local on the PC,
Remote on the phone; backends in preference order with a switch policy if adaptive).

## 3. The conversion checklist

1. **Refinery verdicts in hand** — Adam has cut/reordered the window's `EXPLOSION.md` section and
   answered its questions. No verdicts, no build.
2. **Mine the G2CC original** (read-only): interaction shapes, lessons, failure handling worth
   keeping. Record the facts mined (a short block in the window's doc or commit message).
3. **Declare the contract** (§2 above) on paper first: levels and their modes, state split
   (sub-records vs `shell.state` vs host-owned per 1.8), needs/backends, notification sources
   with targets, settings rows, icon, title forms (short!), typed-text and keyboard uses (what
   the window asks the keyboard for, and whether it supplies live keys).
4. **Providers**: Local + Remote on the window channel; staleness surfaced; adaptive switch
   policy if any (window-defined condition, deliberate switchback).
5. **Build** — whole, to its best state (Adam, 2026-09-01: no v1/v1.5 staging, *"completely
   built to its best state before we move on"*) — against ContentKit + the shared kit
   (fit-with-mark, confirm level, notice, the keyboard, open-on-PC where relevant). Async work
   computes off-loop and mutates through `runOnShell` only.
6. **Tests**, all four kinds:
   - a **Scripted provider** (deterministic — the `ScriptedTmux` precedent);
   - core tests incl. a **persistence round-trip** (switch away/back → byte-identical frame,
     §9.1) and the **continuity test** (save on shell A → sync → restore on shell B → identical
     position/frame — §16.4c);
   - a **selfcheck scene**;
   - a **snapshot scene** (and look at the render).
7. **Register everywhere**: desktop `Main.kt` (a host-side provider goes into BOTH the auto/standby
   stack and `--host-only`'s service map), phone `ShellService.kt`, SelfCheck, Snapshot.
8. **Run the whole battery** (`CLAUDE.md` list: `:core:test`, `:desktop:test`, `--selfcheck`,
   `--snapshot`, `--epub-check`, `--music-check`, `tools/lint.py`, `:phone:assembleDebug`) and keep it green.
   Regenerate `design/shots/` if anything design-visible changed, and read the numbers.
9. **Document**: a verdict block for a simple window; a `TMUX.md`-weight doc only if the window
   earned it. Record any reversal in the DESIGN §0 style — rejected ideas get written down so
   they are not re-proposed.

## 4. The shared machinery — ✅ BUILT (2026-09-01, with the Files conversion)

All four rows of the agreed build order are CODE, and so is the keyboard that followed them
(row 5); a conversion consumes them, it does not build them:

1. **The state substrate** (§16.4): per-item sub-records with reported-guarded tombstones,
   stamp-0 baselines, merge-load + post-start reconciliation, per-item re-apply after a
   main-record live apply; the continuity-test harness (`SubstrateTest`/`FilesTest` shapes).
2. **The generic window channel** (§16.10): `{"t":"win","win":"<id>"}` on the content port
   (note the field name), `WinService` host-side, `RemoteWin` client (id-correlated, blob lane,
   keeper reconnect, `stateLine`), and since Music (2026-09-02) **push frames**
   (`WinService.Push` → `RemoteWin(onPush)`, the `wpush` frame). Still open on it:
   summaries-over-channel and a per-backend `needs` contract.
3. **Deep links + the notification signature** (§16.1/§16.5) — see §2's `notifyInternal`/`open`.
4. **The kit** (§16.11): `Draw.fit` (elide with the drawn ▸, always), `Draw.dynamic`
   ('?'-substitutes uncoverable glyphs), `MenuSurface`, open-on-PC via the channel. The
   confirm-level helper is NOT yet extracted — windows stage confirms by hand (the Tmux
   TYPE_CONFIRM / Files menu shapes).
5. **The keyboard** (`DESIGN.md` §4.8, 2026-09-01): `services.openKeyboard(spec, owner)` — a
   ring-driven wireframe keyboard, row-then-key, QWERTY/abc from Settings; the requester gets
   `onCommit(text)` / `onCancel(draft)` and may supply a row of live keys. Torrents (search),
   Tmux (Type…) and Files (rename / new folder) ride it; typed replica lines still work.
- **Icons**: theme icons resolve automatically; the drawn-set quality pass (fallback + release
  path) is queued at the front of the app wave.
- **Independent backlog**: the curated font-library expansion (§16.6; B612 never a default).

## 5. Traps already paid for (do not re-learn)

- **Mutating view-facing state off the shell loop** — round 1's biggest reader race. Compute on
  `bg`, apply via `runOnShell`.
- **Async completions without identity guards** — Reader's `openingId`/`layoutGen` patterns: a
  late load must not replace what the user switched to; of two racing relayouts only the newest
  applies.
- **Stale wraps after a layout or font change** — implement `onLayoutChanged` /
  `onFontScaleChanged` or long lines overrun their rects (NO TRUNCATION, §2.2b).
- **Skipping `styledText()`** — measure and draw must go through the same transform or wrap and
  render disagree.
- **`summary()` doing work** — the G2CC lesson; it is called for every window on every Main
  render.
- **A test default that supplies what the wire omits** — the source-0 and ack-enum lessons: model
  the wire truth, not the flattering version.
- **Silent clipping without the mark** — use the kit helper; hand-rolled `drawFit` is how the
  launch-night tmux bare-clip debt happened.
- **Forgetting the cursor rest** after a level change (§1.7) — the "$5 turn" lesson.
- **A phone-side need without its permission story** — a PHONE_APIS window declares which Android
  permissions it needs; a missing grant surfaces as the window's unavailability line, loudly.
- **A cursor that is an index into a LIVE list** — a poll that adds a row moves the row under the
  cursor (the Torrents wrap-end menu row walked away; an empty list's menu row is the rest).
  Track the row's IDENTITY (hash, or the menu row) and re-resolve on every snapshot.
- **A page demand from a painted row** — the panning list paints tail rows ABOVE the cursor, so
  a demand keyed on "the loading row was painted" re-fires every repaint (an unbounded chain of
  tracker requests). Demand from the cursor's position on the loop's `view()`, never from a
  painter, and never while unfocused — a switcher preview renders the window too.
- **A provider call from a paint** — a blocking channel request on the phone's loop (the L1
  class). Static tables stay constants; everything else is fetched off-loop.
- **A listener registered per `start()`** — the keeper's same-instance restart registers it again
  (a defect class, not a one-off: the Files loop met it too). Listeners are idempotent, and a
  stack stop detaches them in a `finally`.
- **A retry loop that posts credentials** — pace and latch logins inside `login()` itself, on
  every path; latch only on a definite refusal (the site's login FORM), never on a maintenance
  page.
- **Refusing after a surface is open** — validate a spec BEFORE `open = true` (the half-open
  keyboard); and close a surface before running its requester's callback (the keyboard commit
  closes and restores first, then `onCommit`, so the callback may open the next level or menu).
- **A RESTORED level below the top that never loads** (review 2026-09-03, `HANDOFF.md` §25 #7).
  `restoreState` reloads the TOP only; a deeper level restored with no rows shows one bare menu
  row forever. Load on the way back (`MusicWindow.ensureLoaded`); Files and Torrents each carry
  their own version of this.
- **`saveSubState()` returning an EMPTY blob** (§25 #8). An empty object IS the §16.4a removal
  TOMBSTONE and the sub-keys are syncable, so reporting one fresh-stamps a deletion of the
  PEER's real record. Report nothing instead. (The shell now refuses one loudly as a backstop.)
- **A surface whose WRAP width differs from its DRAW bound** (§25 #3). The silent notice box is
  200 px; its body was wrapped to the 248 px window box and drawn unbounded — cut on the glass
  with no mark, and undamaged ink in `composed` that a later keyframe reveals. Wrap and bound
  to the same number, and fit.
- **Dynamic text drawn without `Draw.dynamic`** (§25 #5–6). A glyph the face lacks is silent
  tofu. Sanitise at WRAP time when the window wraps (so measure and draw agree), at draw time
  otherwise. Measured reality: the real book shelf and the live tmux panes both carry glyphs
  the locked faces cannot draw.
- **A once-per-run notice whose own remedy does not re-arm it** (§25 #9). The quiet-stream
  notice told the user to raise the volume; raising it left the latch set, so the next silent
  start said nothing.
- **A menu row that can never succeed** (`HANDOFF.md` §26.3, found only by driving the grammar).
  Games' `Cash out` lived on a level that opens mid-hand, and the engine refused mid-hand: the
  row existed, was drawn, and could not work. Every unit test called the method in a state the
  UI cannot produce. **A row's REACHABILITY is part of its contract** — walk the grammar to the
  row and commit it, in a test or in a live session.
- **Trusting a generated test corpus you did not check** (§26.1). Four defects in the side-pot
  oracle's generator would each have "proved" the engine correct against nonsense — the worst
  left the board empty, so every all-in hand scored as a chop. Spot-check a corpus against hands
  you can rank by hand before you trust 3,000 of them.
- **A small drawn shape beside a number reads as punctuation** (§26.3). A one-bar chip stack was
  an em-dash in front of the word "pot"; two bars were an equals sign between two amounts. Judge
  every drawn glyph-scale mark at true 1×, in its real neighbours — never in isolation and never
  scaled up.
- **Stacking lines by a hand-picked pitch instead of by measured ink.** Three lines at 18/15/13
  do not fit a 64 px lens: measured on the real rasterizer, ink (ascent + descent) is 27/23/20 px.
  Size the ladder from `tx.metrics`, and if a font scale makes it too tight, DROP the last line
  rather than draw it through the one above.
- **`onRegistered` is not the restore.** It runs before any sub-record arrives; anything it
  creates will be duplicated or orphaned by the restore that follows. Seed on activation.
- **Fixing an unreachable row is not finishing with it — walk EVERY branch.** Games' `Cash out`
  was fixed once (the row only opened mid-hand and the engine refused mid-hand), and the fix
  still failed in the commonest spot of all because the code short-circuited on "nothing in the
  pot yet" into the same refusal. The pin now enters from that exact spot. A row that works
  *sometimes* is the same defect.
- **`?: 1`, `?: first`, `?: the only one` — a default that is right on the happy path is an
  assumption, not a default.** `finishPlace(seat) ?: 1` means "no finishing order = the winner",
  which is true of one seat normally and of EVERY seat at a table that stopped early: each was
  paid the whole prize and recorded a win. Rank instead of defaulting.
- **A row's DETAIL is a promise.** "Settings · games" opened Settings wherever it was last left.
  `SettingsWindow.open("cat:<name>")` is the general answer — any window can deep-link to its own
  category with `services.openWindow("settings", "cat:<Name>")`, and back returns to the caller.
- **A pending state needs a resting indicator, not just a notice.** A confirmed cash-out showed a
  four-second notice and then nothing; the table's status tail now reads **tap to leave** for as
  long as it is pending. If a gesture's MEANING has changed, the surface has to say so at rest.
- **`Occupant.human` (or whatever your contract's equivalent is) decides the PERSON.** Sentences
  built from a name read "You checks" and "You wins $412" straight off the glass.
- 🔴 **An asynchrony introduced to hide a cost nobody measured is a defect generator, not an
  optimisation.** Games' play-out hand-off ran on a background coroutine because its own comment
  said the work took "seconds"; `--games-check` says a WHOLE 6-seat tournament is **13 ms**. The
  coroutine bought nothing and cost two defects — a new table having its state cleared by the old
  one's settlement, and a restart inside the window destroying the prize pool. **Measure the
  number the design rests on, then decide.** This project already says "measured vs modeled"
  about hardware; it applies to our own code.
- **A completion that resets "the current thing" must check that what it settles IS current.**
  Anything that finishes off-loop, or later than the gesture that started it, can land after the
  user has moved on.
- **A loud line for a NORMAL state teaches people to ignore loud lines.** `playOut` reported "did
  not resolve" for a table handed over already finished — every cash-out with one opponent left.
  NO SILENT FAILURES does not license crying wolf.
- 🔴 **A pin that passes with and without its fix is not a pin.** Run every new test against the
  UNFIXED tree and watch it fail; if the assertion is a return value that did not change, assert
  the thing that did (a log line, a state transition, a drawn pixel). And do not let a comment
  claim a pin reproduces a race it cannot — say what it actually locks.
- 🔴 **An event notice a tap should answer carries `appId` and `target`, and coalesces per
  ITEM** (`HANDOFF.md` §29). The tmux alert — the one notice whose whole point is "go there" —
  was app-less, so its tap only dismissed it, and every alerting session shared one box. Shape
  every event notice like Torrents' `done` (`appId = id, thread = <item>, target = <deep link>`)
  and give the window an `open(target)` for it; a notice that only informs (a failure line) may
  stay app-less.
- 🔴 **Live-driving: one step per snap in any window with a destructive row** (`HANDOFF.md`
  §29.2). A blind gesture run that assumed where the cursor rested started a stopped torrent on
  the real qBittorrent and reached the first of the two delete confirms. The confirms held; the
  method was the defect.
- 🔴 **The list rhythm was the last constant of that class** (`HANDOFF.md` §29). The kit's 32 px
  row and 64 px lens held the row face at 100 % exactly; at 115 % the row directly above the lens
  lost its descenders to the lens fill, and every window's second lens line at `+34`/`+32` would
  have been drawn through the first. Measure the ink at the SHELL (`Shell.listRhythm`) and in
  the window (`Draw.lineBelow`); floors, never constants.
- 🔴 **A pitch constant in a CHROME surface is the same defect as one in a window** (`HANDOFF.md`
  §28.2). The menu, the notification box and the wheel each carried a title band, a row pitch or a
  centre band sized for the chrome face at 100 %, and every one of them put ink outside its own
  damage rect at the top of the font ladder. Measure the face; keep the design number as the
  FLOOR so 100 % does not move. And test with a rasterizer whose ink FOLLOWS the size
  (`ScalingText` in `Review28Test`) — `FakeText`'s constant 12+4 metrics cannot see this class.
- **A generation bump and its in-flight flag go together** (§28.1). `pacerGen++` without
  `thinking = false` left nobody able to re-arm the pacer: the re-entry's pump was refused by the
  flag, and the stale completion, seeing the bumped generation, returned without pumping. One
  `cancelPacer()`; a superseded completion never touches the flag.
- **Main's lens must not describe a scan nobody started** (§28.1). "library loading" and
  "0 locations" from boot until the window was first opened. Scan the root at registration —
  QUIETLY: no op-cell line, no notice, no `navSeq` bump that could cancel a restore's own open —
  and skip the activation re-scan while it is in flight.
- **`capture-pane -p | tail -N` sees the blank rows at the bottom of a pane** (§28.2). Drop
  blanks before the tail, or every short pane reads as empty — no wait alert, no last line.
- **Blind gesture scripts drift.** A live walk that assumes where the Main or Settings cursor
  rests changes the wrong rows within a dozen steps (§28.2 changed five settings by accident).
  Snap, look, then act — or ask the shell (`menuLabels`, `rootRow`) rather than counting.
