# WINDOWS.md — converting a G2CC app into a DamageWM window

The checklist a conversion follows, written 2026-09-01 from the session with Adam that settled `EXPLOSION.md`
§16 (that table holds the design detail and statuses). Precedence: `overview.md` on facts, `CLAUDE.md` on
rules, `DESIGN.md` on shell design, `EXPLOSION.md` §16 on the shared contract — this file distills.

**Inputs per window:** the window's `EXPLOSION.md` section (with Adam's refinery verdicts), `DESIGN.md` §4.6
(modes + what a window declares), `core/…/shell/WindowContract.kt` (the contract as code), and the G2CC
original (`/home/user/G2CC/server/src/windows/<app>.ts`) — read-only, interaction facts only, no code taken
(the clean-room rule) — when one exists (Torrents and Feed had none).

**The seven worked precedents** — read them before writing an eighth: `ReaderWindow` (List → Document →
Actions, async content, per-item sub-records, images) · `TmuxWindow` (Canvas, a live provider over the content
port, quick keys, typed text with confirm, alerts) · `FilesWindow` (2026-09-01: the tap-=-context-menu grammar
via `MenuSurface`, the §16.10 window channel, viewers as DocViews, paced-retry failure discipline,
`Draw.fit`/`dn()`) · `TorrentsWindow` (`TORRENTS.md`: no original, built whole; the keyboard as a requester,
version-cursor snapshots + event replay over the blob lane, a LIVE list whose cursor follows row identity) ·
`MusicWindow` (`MUSIC.md`: one window against two core contracts, `MusicLibrary` + `MusicPlayer`; the channel's
PUSH slice, a second endpoint (:7404) for media bytes, per-height layouts and the EXCLUSIVE mode, `DESIGN.md`
§4.9) · `GamesWindow` (`HOLDEM.md`: no host at all, pure Kotlin; a `CanvasView` table at four heights; its own
stereo planes via `contentPlanes`; a kit under `windows/games/kit/` that names no card game; the persisted
record is an ACTION LOG the engine replays) · `FeedWindow` (`FEED.md`: ONE engine class on both hosts, the
phone's parked and switching back only by hand; the Reader grammar over nine levels; synced reading state with
a union rule for read marks; an endless archive Document; strips on demand from `view()`).

---

## 1. The bar every window meets

- 🔴 **100 % cross-device continuity** (Adam, 2026-09-01: *"an always-active session that can be continued
  seamlessly from every device connected to DamageWM"*): the replica for the same session on any screen, LWW
  sync for separate shells. The window's part: per-item state in sub-records (`window.<id>.<item>`), content
  continuable (cache-on-open), the continuity test (§3 step 6) passing.
- **The input grammar is not negotiable** (`DESIGN.md` §1): tap descends, double-tap backs, scroll moves focus,
  long-press is a no-op by default. No per-window gestures. Actions are a LEVEL, not a region (§4.6).
- **NO TRUNCATION, worded honestly** (§2.4 r3): content wraps/scrolls; rows and titles are handles, elided only
  through the kit's fit helper (mark + a reachable full view). Titles are short by design (§4.1).
- **Misfire tolerance** (§1.7): the cursor rests on a harmless cell after every level change; irreversible rows
  never at cursor rest, never index 0/1; every navigation undoable by double-tap.
- **The latency standards of §6.**
- **Every irreversible or outbound act stages a confirm** — deletes, sends, ending a session, typed text
  (`onTypedText` always stages). Recorded exemption: the read-only Torrents search (`TORRENTS.md` §3.1).
- **LOUD failures:** provider errors ride the one-shot notice on the title; staleness is said with a duration
  (`PC unreachable 40s`); a missing need marks the window unavailable in Main and says why (§10.5). No timeouts.
- 🔴 **Staleness reaches EVERY level, and Main's row** (`HANDOFF.md` §30): the TITLE carries it wherever the
  level does not paint it, the summary carries it into Main (Tmux said it on the live pane only — a quiet host
  went unreported for half an hour on the live walk).
- 🔴 **A row that cannot succeed is DIM; a menu opens on a row that can act.** A dim row is a visible no-op
  (`Shell` refuses it and says so); a live row that does nothing is a silent failure.
- **Notification toggles live in the app's own Settings category** (Adam, 2026-09-01): the window gates its own
  `notifyInternal` calls on its rows (the Tmux `Alerts` shape); Global keeps only `Notify · Damage`.
- 🔴 **Every window works well at all four heights — 288 / 352 / 416 / 480 — and its size is adjustable in
  Settings** (Adam, 2026-09-02: *"This is important."*): each level's layout specified per height in the
  window's record, the per-app Size row not optional, snapshot scenes at 288 and 480 at least.
- **Nothing specific to Adam's current setup is baked in** (2026-09-02): a preference today (mono, one earbud, a
  work-day fit) is a Settings row with his value as the default.
- 🔴 **A lens line sits below the previous line's MEASURED ink, never at a constant** (`HANDOFF.md` §29):
  `Draw.lineBelow(tx, f1, line1Y, designY)` — the design offset at 100 %, lower as the face grows — and anything
  under a line the same way, dropped when it no longer fits. The row pitch and lens band the kit hands you grow
  with the face (`Layout.rowH` / `lensH`); paint into the rect you are given.
- **`summary()` is cheap and side-effect-free** — cached provider state, never spawned work (§4.6).
- **Preview is a render, never an activation** (§4.3 rule 1): hooks run on commit only, deep-link opens included.
- **Ink budgets:** List ≤ 15 %, Document ≤ 25 %, Canvas the window's call (linted as a warning). Structure from
  spacing and brightness, never boxes or fills (§4.2).
- **Clean room:** no g2flash/faceclaw code; no third-party assets in release builds; wire constructions cite
  their source file.

## 2. What a window declares (the contract, one screen)

`DamageWindow` (`WindowContract.kt` is the authority): `view()` (List/Doc/Canvas per level) · `title()`
(SHORT — §4.1) · `summary()` (cheap; `more`/`progress` flags) · `icon` (an `IconKind`; theme icon first, the
drawn kind as fallback and release path) · `dirty` · `needs` (HOST/PHONE_APIS/BLE; a per-BACKEND `needs` is
still unbuilt — Music declares per host) · `preferredHeight` (global-default pattern) · `appSettings()`
(STABLE instances) · `saveState()/restoreState()` (mode included, §9.1) · `restoreStateLive()` (a live-synced
main record — override when the boot restore assumes an activation follow-up) · `saveSubState()/
restoreSubState()` (per-item records, §16.4; empty object = removal tombstone) · `styleTransform`/`styledText()`
(route EVERY measure/draw through it) · `back()` / `levelDepth()` · `onTypedText(line)` (confirm-staged) ·
`open(target)` (§16.1: opaque per-window target, false = loud) · `contentPlanes(content)` (stereo regions
inside the content area, up to `Shell.MAX_WINDOW_PLANES` = 4, validated and capped by the shell; empty = the
content plane) · the hooks `onRegistered` / `onActivate(ctx, from)` / `onDeactivate` / `onLayoutChanged` /
`onFontScaleChanged`.

**`ActivationSource` (2026-09-04, Adam's general rule):** `SWITCHER` resumes exactly where the window was;
`MAIN` presents the root list; `DEEP_LINK` goes to the target; `RESTORE` changes nothing. Every window with
more than one base function implements it. Two traps: reset the cursor AND the container, not just the level
(assert `levelDepth() == 1` after a MAIN activation — Reader's left a subfolder open); a window whose root IS
its live surface has nothing to reset (Music's root is NOW PLAYING, `HANDOFF.md` §24.4).

`ShellServices`: `requestRender` · `setOperation` · `notifyInternal(source, body, urgent, appId, thread,
target)` (§16.5 — tap = commit + activate + open(target)) · `openMenu(spec, owner): Boolean` (pass `owner`
from ASYNC completions; false = deliver the answer as a notice) · `openKeyboard(spec, owner): Boolean` (§4.8;
`onCommit(text)` runs AFTER the keyboard closed, `onCancel(draft)` hands the draft back) · `openWindow(id,
target)` (back-to-caller recorded) · `icons()` · `runOnShell` (EVERY off-loop completion applies through it) ·
`docContentWidth/Height`. Plus, per window: its **notification sources** (a Settings toggle, a coalesce key, a
deep-link target — §16.5) and its **provider(s)** on the window channel (§16.10: Local on the PC, Remote on the
phone; backends in preference order with a switch policy if adaptive).

## 3. The conversion checklist

1. **Refinery verdicts in hand** — Adam has cut/reordered the window's `EXPLOSION.md` section and answered its
   questions. No verdicts, no build.
2. **Mine the G2CC original** (read-only): interaction shapes, lessons, failure handling worth keeping. Record
   the facts mined (a short block in the window's doc or commit message).
3. **Declare the contract** (§2) on paper first: levels and modes, state split (sub-records vs `shell.state` vs
   host-owned per 1.8), needs/backends, notification sources with targets, settings rows, icon, title forms
   (short!), typed-text and keyboard uses.
4. **Providers**: Local + Remote on the window channel; staleness surfaced; adaptive switch policy if any
   (window-defined condition, deliberate switchback).
5. **Build** — whole, to its best state (Adam, 2026-09-01: no v1/v1.5 staging, *"completely built to its best
   state before we move on"*) — against ContentKit + the shared kit. Async work computes off-loop and mutates
   through `runOnShell` only.
6. **Tests**, all four kinds: a **Scripted provider** (the `ScriptedTmux` precedent); core tests incl. a
   **persistence round-trip** (switch away/back → byte-identical frame, §9.1) and the **continuity test** (save
   on shell A → sync → restore on shell B → identical position/frame, §16.4c); a **selfcheck scene**; a
   **snapshot scene** (and look at the render).
7. **Register everywhere**: desktop `Main.kt` (a host-side provider goes into BOTH the auto/standby stack and
   `--host-only`'s service map), phone `ShellService.kt`, SelfCheck, Snapshot.
8. **Run the whole battery** (`CLAUDE.md`'s list) and keep it green. Regenerate `design/shots/` if anything
   design-visible changed, and read the numbers.
9. **Document**: a verdict block for a simple window; a `TMUX.md`-weight doc only if the window earned it.
   Record any reversal in the DESIGN §0 style so rejected ideas are not re-proposed.

## 4. The shared machinery — ✅ BUILT (2026-09-01, with the Files conversion)

A conversion consumes these; it does not build them:

1. **The state substrate** (§16.4): per-item sub-records with reported-guarded tombstones, stamp-0 baselines,
   merge-load + post-start reconciliation; the continuity-test harness (`SubstrateTest`/`FilesTest` shapes).
2. **The generic window channel** (§16.10): `{"t":"win","win":"<id>"}` on the content port, `WinService`
   host-side, `RemoteWin` client (id-correlated, blob lane, keeper reconnect, `stateLine`), push frames
   (`WinService.Push` → `RemoteWin(onPush)`, the `wpush` frame). Still open: summaries-over-channel, a
   per-backend `needs` contract.
3. **Deep links + the notification signature** (§16.1/§16.5) — §2's `notifyInternal`/`open`.
4. **The kit** (§16.11): `Draw.fit` (elide with the drawn ▸, always), `Draw.dynamic` ('?'-substitutes
   uncoverable glyphs), `MenuSurface`, open-on-PC via the channel. The confirm-level helper is NOT extracted —
   windows stage confirms by hand (the Tmux TYPE_CONFIRM / Files menu shapes).
5. **The keyboard** (`DESIGN.md` §4.8): `services.openKeyboard(spec, owner)` — ring-driven wireframe,
   row-then-key, QWERTY/abc from Settings; `onCommit(text)` / `onCancel(draft)`; a requester may supply a row
   of live keys. Torrents (search), Tmux (Type…), Files (rename / new folder) ride it.

6. **The popover family — SPEC, not built** (`POPOVER.md`, `DESIGN.md` §4.11, 2026-09-12): menus, one confirm
   shape, peeks, decks/asks with report-back. Until it lands, item 4 stands (confirms by hand).

Icons: theme icons resolve automatically; the drawn-set quality pass is queued (`REMINDER.md`). Independent
backlog: the curated font-library expansion (§16.6; B612 never a default).

## 5. Traps already paid for (do not re-learn)

- **View-facing state mutated off the shell loop** — compute on `bg`, apply via `runOnShell`.
- **Async completions without identity guards** — Reader's `openingId`/`layoutGen`: a late load must not replace
  what the user switched to; of two racing relayouts only the newest applies.
- **Stale wraps after a layout or font change** — implement `onLayoutChanged` / `onFontScaleChanged`.
- **Skipping `styledText()`** — measure and draw must share one transform or wrap and render disagree.
- **`summary()` doing work** — it runs for every window on every Main render.
- **A test default that supplies what the wire omits** (source-0, the ack enum) — model the wire truth.
- **Silent clipping without the mark** — the kit helper, never a hand-rolled `drawFit`.
- **Forgetting the cursor rest** after a level change (§1.7).
- **A phone-side need without its permission story** — a PHONE_APIS window declares its Android permissions; a
  missing grant is the window's unavailability line.
- **A cursor that is an index into a LIVE list** — a poll that adds a row moves the row under the cursor. Track
  row IDENTITY and re-resolve on every snapshot; an empty list's menu row is the rest.
- **A page demand from a painted row** — the panning list paints tail rows ABOVE the cursor, so "the loading row
  was painted" re-fires every repaint. Demand from the cursor's position on the loop's `view()`, never from a
  painter, never while unfocused (a switcher preview renders too).
- **A provider call from a paint** — a blocking channel request on the phone's loop; fetch off-loop.
- **A listener registered per `start()`** — the keeper's same-instance restart registers it again. Listeners are
  idempotent; a stack stop detaches them in a `finally`.
- **A retry loop that posts credentials** — pace and latch logins inside `login()` on every path; latch only on a
  definite refusal (the login FORM), never on a maintenance page.
- **Refusing after a surface is open** — validate a spec BEFORE `open = true`; close a surface before running its
  requester's callback (the keyboard commit closes and restores, then `onCommit`).
- **A RESTORED level below the top that never loads** (`HANDOFF.md` §25 #7) — `restoreState` reloads the TOP
  only; load on the way back (`MusicWindow.ensureLoaded`).
- **`saveSubState()` returning an EMPTY blob** (§25 #8) — an empty object IS the §16.4a removal TOMBSTONE and the
  sub-keys sync, so it fresh-stamps a deletion of the PEER's record. Report nothing.
- **A surface whose WRAP width differs from its DRAW bound** (§25 #3) — the 200 px silent notice box wrapped to
  the 248 px window box: an unmarked cut on glass, and undamaged ink a later keyframe reveals.
- **Dynamic text drawn without `Draw.dynamic`** (§25 #5–6) — a glyph the face lacks is silent tofu. Sanitise at
  WRAP time when the window wraps, at draw time otherwise; the real shelf and live panes carry such glyphs.
- **A once-per-run notice whose own remedy does not re-arm it** (§25 #9) — the quiet-stream latch.
- **A menu row that can never succeed** (`HANDOFF.md` §26.3) — Games' `Cash out` opened only mid-hand and the
  engine refused mid-hand; every unit test called it in a state the UI cannot produce. A row's REACHABILITY is
  part of its contract: walk the grammar to it and commit it — and **walk EVERY branch**: the fix still failed
  in the commonest spot ("nothing in the pot yet" short-circuited into the same refusal).
- **Trusting a generated test corpus you did not check** (§26.1) — four defects in the side-pot generator would
  each have "proved" the engine against nonsense (an empty board scored every all-in as a chop). Spot-check
  against hands you can rank by hand.
- **A small drawn shape beside a number reads as punctuation** (§26.3) — a one-bar chip stack was an em-dash, two
  bars an equals sign. Judge every glyph-scale mark at true 1×, in its real neighbours.
- **Lines stacked by a hand-picked pitch instead of measured ink** — 18/15/13 do not fit a 64 px lens: measured
  ink (ascent + descent) is 27/23/20 px. Size the ladder from `tx.metrics`; too tight, DROP the last line.
- **`onRegistered` is not the restore** — it runs before any sub-record arrives. Seed on activation.
- **`?: 1`, `?: first`, `?: the only one`** — a happy-path default is an assumption. `finishPlace(seat) ?: 1` paid
  EVERY seat of a table that stopped early the whole prize. Rank instead of defaulting.
- **A row's DETAIL is a promise** — "Settings · games" opened Settings wherever it was last left;
  `services.openWindow("settings", "cat:<Name>")` deep-links to a category and back returns to the caller.
- **A pending state needs a resting indicator, not just a notice** — the table's status tail reads **tap to
  leave** while a cash-out is pending. If a gesture's MEANING changed, say so at rest.
- **`Occupant.human` (or your contract's equivalent) decides the PERSON** — "You checks" straight off the glass.
- 🔴 **An asynchrony introduced to hide a cost nobody measured is a defect generator** — Games' play-out ran on a
  coroutine because a comment said "seconds"; `--games-check` says a whole 6-seat tournament is **13 ms**. It
  bought nothing and cost two defects. Measure the number the design rests on, then decide.
- **A completion that resets "the current thing" must check that what it settles IS current.**
- **A loud line for a NORMAL state teaches people to ignore loud lines** — `playOut` reported "did not resolve"
  for every table handed over already finished.
- 🔴 **A pin that passes with and without its fix is not a pin** — run every new test against the UNFIXED tree
  and watch it fail; if a return value did not change, assert what did (a log line, a transition, a pixel).
- 🔴 **An event notice a tap should answer carries `appId` and `target`, and coalesces per ITEM** (§29) — the
  tmux alert was app-less: its tap only dismissed it and every alerting session shared one box. Shape it like
  Torrents' `done` (`appId = id, thread = <item>, target = <deep link>`) with an `open(target)` behind it.
- 🔴 **Live-driving: one step per snap in any window with an irreversible row** (§29.2) — a blind run started a
  stopped torrent on the real qBittorrent and reached the first delete confirm.
- 🔴 **The list rhythm was the last constant of that class** (§29) — the kit's 32 px row / 64 px lens held the
  row face at 100 % exactly; at 115 % the row above the lens lost its descenders and every second lens line at
  `+34`/`+32` would have been drawn through the first. Measure ink at the SHELL (`Shell.listRhythm`) and in
  the window (`Draw.lineBelow`); floors, never constants.
- 🔴 **A pitch constant in a CHROME surface is the same defect** (§28.2) — the menu, the notification box and the
  wheel each put ink outside their damage rect at the top of the font ladder. Keep the design number as the
  FLOOR and test with a rasterizer whose ink FOLLOWS the size (`ScalingText` in `Review28Test`) — `FakeText`'s
  constant 12+4 metrics cannot see this class.
- **A generation bump and its in-flight flag go together** (§28.1) — `pacerGen++` without `thinking = false` left
  nobody able to re-arm the pacer. One `cancelPacer()`.
- **Main's lens must not describe a scan nobody started** (§28.1) — scan the root at registration, QUIETLY: no
  op-cell line, no notice, no `navSeq` bump that could cancel a restore's own open.
- **`capture-pane -p | tail -N` sees the blank rows at the bottom of a pane** (§28.2) — drop blanks before the tail.
- **Blind gesture scripts drift** (§28.2 changed five settings by accident) — snap, look, then act, or ask the
  shell (`menuLabels`, `rootRow`) rather than counting.
- **A harness script is one JVM method** (Feed) — `SelfCheck.script` and `Snapshot.script` hit the 64 KB "Method
  too large" limit. Give every window's walk and scenes a function of their own (`torrentsChecks`,
  `gamesChecks`, `feedChecks`, `feedScenes`).
- **A MAIN entry presents the root; it does not move the root cursor** — a harness clicking row 0 after
  `toWindow` opens whatever the cursor rested on last. Give the window a `rootRowId()`-class accessor.
- **A class's `init` runs where it is written** — a field declared below the `init` block is null inside it.
- **A test that reads the cursor right after posting notches reads the old cursor** — wait for the identity.
- 🔴 **A canvas paints its whole rect, background first** — `CanvasView` hands the window everything; the shell
  clears nothing under it (the Feed comic canvas drew over the list it replaced; only the snapshot saw it).
  Start every canvas paint with `g.fillRect(r, Level.BG)` and LOOK at a canvas scene.
- 🔴 **A harness clicks nothing before the rows landed** — a list opens INSTANTLY while its page is on its way; a
  click posted then lands on the loading row and every later step is one level off (the Feed selfcheck failed
  2 of 4 runs this way). Give the window an `itemsLoaded()`-class accessor and wait on it.

## 6. Latency standards — the bar for every window written after 2026-09-05 (`HANDOFF.md` §37)

The link is the phone's: ~70 ms per flush + ~120 ms per KB, one AA packet per usable connection event
(`REMINDER.md`, measured). A window is judged on **time to first visible change per gesture**, not on bytes
per screen.

1. **The first flush a gesture produces is small.** A notch is a translation (the kit's slides declare mode-9
   copies; a canvas repaint that translates is detected) plus the newly exposed strip. Heavy repaints go in a
   LATER flush; if a change cannot be translation + strip, say why in the record.
2. **Text and icons through the texture cache** (`HANDOFF.md` §40.6 → §41.4, behind the Global `Cached text`
   row): draw text through the rasterizer the host handed you (the recorder), icons through `IconPaint`; a
   string or icon on ANY plane then ships as draws with nothing for the window to do. Text on a box does not
   cache — the journal's `cacheMiss` says which. A row painted into a temp of your own is not recorded.
3. **Update what changed.** Repaint the rows or cells that changed; the compositor's diff sends only the
   difference, but the phone's CPU is paid for every row you repaint. Memoise per line / per row (`FlowRender`).
4. **Live only while active** (Adam's ruling: he is watching); parked windows hold no loop.
5. **No work on the loop that is not painting** — network, disk, decoding, wrapping go off the loop, applied
   through `runOnShell` with a generation guard.
6. **No animation of its own.** Frames per notch are the shell's setting.
7. **Ship with numbers.** Walk it with `tools/glassdrive.py` (snap before every tap), read `/journal` through
   `tools/journal_report.py` — its **"time to first visible change per gesture"** section is the judgment (§42)
   — and put the rows in the window's record. **The bar, measured on glass on 0.40 (2026-09-07 → 09,
   `HANDOFF.md` §42.0):** a window list notch 540 B / 108 ms median (p90 2.1 KB / 229 ms), a Main notch 716 B /
   117 ms (p90 247 ms). A notch whose first flush is over ~1 KB or whose first visible change is over ~250 ms
   at the median has a defect to find, not a link to blame.
8. **The shell owes you the rest** — the cache on every plane, the depth ladder, translation detection,
   first-flush ordering and `Slide fill`, telemetry off the first flush, the page traffic sleeping with the
   glasses, the keeper's rebuilds. Never re-implement one in a window; if a window seems to need one, the
   shell is where the change goes.
