# WINDOWS.md — converting a G2CC app into a DamageWM window

**The build-facing distillation of the app-conversion contract.** Written 2026-09-01 from the
general-topics session with Adam that settled `EXPLOSION.md` §16; that table holds the design
detail and statuses, this file holds the checklist a conversion actually follows. Precedence as
always: `overview.md` on facts, `CLAUDE.md` on rules, `DESIGN.md` on shell design, `EXPLOSION.md`
§16 on the shared contract — this file distills, it never overrides.

**Inputs per window:** the window's `EXPLOSION.md` section (with Adam's refinery verdicts),
`DESIGN.md` §4.6 (modes + what a window declares), `core/…/shell/WindowContract.kt` (the contract
as code), and the G2CC original (`/home/user/G2CC/server/src/windows/<app>.ts`) — **read-only,
interaction facts only, no code taken** (clean-room rule, `CLAUDE.md`).

**The two worked precedents:** `ReaderWindow` (List → Document → Actions, async content, per-item
state, images) and `TmuxWindow` (Canvas, a live provider over the content port, quick keys, typed
text with confirm, alerts). Read both before writing a third.

---

## 1. The bar every window meets

These are the non-negotiables, each with its authority:

- 🔴 **100 % cross-device continuity** (Adam, 2026-09-01: *"an always-active session that can be
  continued seamlessly from every device connected to DamageWM"*). Two layers serve it: the
  replica (same session, any screen — built) and LWW sync (separate shells converge). The window's
  part: per-item state in **sub-records** (`window.<id>.<item>`) wherever per-item state exists;
  content declared continuable (cache-on-open, the Reader precedent); the **continuity test**
  (§3.6 below) passes.
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
- **Every destructive or outbound act stages a confirm** — deletes, sends, kills, typed text
  (`onTypedText` always stages; the Tmux TYPE_CONFIRM shape).
- **LOUD failures** (the absolute rules): provider errors ride the one-shot notice on the title;
  staleness is said with duration (`PC unreachable 40s`); a missing need marks the window
  unavailable in Main and says why (§10.5). No timeouts anywhere — pacing loops and liveness
  decisions only.
- **`summary()` is cheap and side-effect-free** — it reads cached state pushed by the provider,
  never spawns work (the G2CC preview/view lesson, §4.6).
- **Preview is a render, never an activation** (§4.3 rule 1): lifecycle hooks run on commit only;
  deep-link opens included. Previewing Mail must not mark mail read.
- **Ink budgets**: List ≤ 15 %, Document ≤ 25 %, Canvas is the window's call (linted as a
  warning). Structure from spacing and brightness, never boxes or fills (§4.2).
- **Clean room**: no g2flash/faceclaw code; no third-party assets in release builds; wire
  constructions cite their source file.

## 2. What a window declares (the contract, one screen)

`DamageWindow` (`WindowContract.kt`): `view()` (List/Doc/Canvas per level) · `title()` (SHORT —
§4.1 contract) · `summary()` (cheap; `more`/`progress` flags) · `icon` (drawn `IconKind`, designed
once, rendered 56 px + 20 px) · `dirty` · `needs` (per-backend once §16.10 lands) ·
`preferredHeight` (global-default pattern) · `appSettings()` (its Settings category; STABLE
instances) · `saveState()/restoreState()` (mode included, §9.1; per-item data in sub-records,
§16.4) · `styleTransform`/`styledText()` (route EVERY measure/draw through it) · `back()` /
`levelDepth()` · `onTypedText(line)` (confirm-staged) · **`open(target)`** (§16.1, once built:
opaque per-window target, false = loud).

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
   with targets, settings rows, icon, title forms (short!), typed-text uses.
4. **Providers**: Local + Remote on the window channel; staleness surfaced; adaptive switch
   policy if any (window-defined condition, deliberate switchback).
5. **Build** against ContentKit + the shared kit (fit-with-mark, confirm level, notice, open-on-PC
   where relevant). Async work computes off-loop and mutates through `runOnShell` only.
6. **Tests**, all four kinds:
   - a **Scripted provider** (deterministic — the `ScriptedTmux` precedent);
   - core tests incl. a **persistence round-trip** (switch away/back → byte-identical frame,
     §9.1) and the **continuity test** (save on shell A → sync → restore on shell B → identical
     position/frame — §16.4c);
   - a **selfcheck scene**;
   - a **snapshot scene** (and look at the render).
7. **Register everywhere**: desktop `Main.kt`, phone `ShellService.kt`, SelfCheck, Snapshot.
8. **Run the whole battery** (`CLAUDE.md` list: `:core:test`, `:desktop:test`, `--selfcheck`,
   `--snapshot`, `--epub-check`, `tools/lint.py`, `:phone:assembleDebug`) and keep it green.
   Regenerate `design/shots/` if anything design-visible changed, and read the numbers.
9. **Document**: a verdict block for a simple window; a `TMUX.md`-weight doc only if the window
   earned it. Record any reversal in the DESIGN §0 style — rejected ideas get written down so
   they are not re-proposed.

## 4. The shared machinery (built once, in this order)

The agreed build order (`EXPLOSION.md` §16, 2026-09-01) — each row's design lives in that table:

1. **The state substrate** (§16.4): per-item sub-records · the §19.4 startup-race closure
   (post-start reconciliation) · the continuity-test harness · content-continuability
   conventions.
2. **The generic window channel** (§16.10): `{"t":"win","id":…}` on the content port, providers,
   summaries/badges, multi-backend arbitration.
3. **Deep links + the notification signature** (§16.1/§16.5), designed together.
4. **The kit** (§16.11): fit-with-mark, confirm level, title notice, open-on-PC — extracted
   alongside the first converted window.
- **Before any new icon is drawn**: the icon-quality pass (§16.7 — one drawn icon per app, 56 px
  switcher/lens + 20 px rows; Main's lens takes the band-height icon).
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
  §18.1 debt happened.
- **Forgetting the cursor rest** after a level change (§1.7) — the "$5 turn" lesson.
- **A phone-side need without its permission story** — a PHONE_APIS window declares which Android
  permissions it needs; a missing grant surfaces as the window's unavailability line, loudly.
