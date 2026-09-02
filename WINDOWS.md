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

**The five worked precedents:** `ReaderWindow` (List → Document → Actions, async content,
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
Settings category of thirty rows). Read them before writing a sixth — Files and Torrents are
the worked examples of MenuSurface and WinNet, Torrents of the keyboard, Music of a
two-host contract, push frames and the exclusive mode.

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
opaque per-window target, false = loud; Files→Reader ships on it) · the lifecycle hooks
(`onRegistered`/`onActivate`/`onDeactivate`/`onLayoutChanged`/`onFontScaleChanged`).

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
   `--snapshot`, `--epub-check`, `tools/lint.py`, `:phone:assembleDebug`) and keep it green.
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
