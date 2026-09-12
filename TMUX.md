# Tmux on glass — design + plan (2026-08-31)

**Status: BUILT 2026-08-31 in one pass; REWORKED THE SAME EVENING — the grid is RETIRED
(verdict 2 and the fractional-pitch revision below are superseded).** Adam, after testing the
per-app font settings against the grid: *"With a grid, font changes are subtle, because the
spacing is kept identical … The whole unpleasant way it looks is entirely because of the grid.
Lets kill the grid entirely, and embrace our ability to present the terminal better. We don't
need a terminal to be entirely live, it's just text."* The FLOW view replaced it:

- **Terminal output renders as TEXT** (`FlowRender`): captured with `-J` so tmux's wrap points
  dissolve into LOGICAL lines, wrapped at our width through the per-app font/size/style — all
  three settings do what they say (on the grid, Font size compensated to zero: the fit inverted
  the scale). Default face JBM.
- SGR becomes styled RUNS (§3.3); rule lines (`───`, `---`, `===`) collapse into DRAWN rules;
  the cursor is a small tail marker; the tail is anchored terminal-style (newest at the bottom).
- **The grid (`TermRender`) survives ONLY as the alternate-screen fallback** — when
  `#{alternate_on}` says a full-screen TUI (htop, vim) owns the pane. Context rows and the fit
  machinery matter only there.
- **History IS the flow**: same face/size/wrap as live, frozen (never yanks), 5 display lines
  per notch, scroll-down at the live edge returns to live — the grammar is unchanged.
- **Capture pacing: 1 s default, configurable** (Settings → Tmux → Update, 0.5/1/2/5 s; rides
  the wire to the host's poll loop as `tpace`, persists). Pushes only on change; an idle
  session costs zero radio.
- Retired with the grid: the fit-80-legibility open item and the "Fit pane to glass" MOTIVE
  (the resize action remains, for alternate-screen TUIs and attached PC clients).
- Cost, chosen with eyes open: columns line up only when a line fits unwrapped.

`IMPLEMENTATION.md` → "Tmux" is what runs; this file is the rationale. Superseded with the grid:
the first on-glass revision's FRACTIONAL column pitch (80 columns span the full width; integer
cells had floored to 560/608 — "narrow and centered") and history rendered THROUGH the live fit
(the reading-size DocView read as a font switch).

The refinery verdicts (2026-08-31):

1. **Scope = v1 core + typed text via replicas + multi-host (ssh) + session management.**
   Scrollback search stays out.
2. **Grid: fit + the explicit "Fit pane to glass" resize action.** No zoom setting.
   *(Superseded: the grid is the alternate-screen fallback only.)* Fit against the LIVE layout
   rect — heights 288/352/416/480 (Adam mid-build: "resolution can be as low as 288px height or
   as high as 480px"), min(width-fit, height-fit), re-derived in `onLayoutChanged`, context rows
   only when spare height exists; 480 the design point (`preferredHeight`), 288 must work.
3. **Alerts: on for all sessions + per-session mute. GLASS ONLY** — no phone fallback.
4. **Quick keys: the shipped fourteen** (Enter y n 1 2 3 Esc Ctrl-C Up Down Left Right Tab q +
   Snippets… — Left/Right added on Adam's ask 2026-08-31), config-overridable.
5. **Context rows: ON by default.**
6. **Every tmux setting lives in the Settings window's Tmux category** (`appSettings()` —
   Adam mid-build: "keep the settings inside the Tmux category of the settings window, same
   with all apps"). Per-session state (mute, viewed window) stays in the session's actions
   level — the Reader per-book precedent.

Defaults stated, not asked: typed text is always confirm-to-run (literal + Enter — G2CC's
2026-06-18 semantics); new sessions auto-name `g2-N`, rename via typed text; snippets default to
G2CC's slash list; ~~500 ms~~ **1 s** capture pacing; 1000-line history; waiting sessions pinned
atop the list; window NAV is non-invasive (`=session:idx` without `select-window` — selecting is
an explicit action, same class as the resize).

The proposal below stands where the verdicts do not override it. Sources: `G2CC/server/src/tmux.ts`
+ `windows/terminal.ts` (facts cited, no code taken), `DESIGN.md`, `CAPABILITIES.md`,
`overview.md` §5.2. Everything
below is **modeled** unless marked measured.

---

## 1. What G2CC's Terminal got right (keep the facts)

**The glasses are a viewer/controller of the real tmux server via DISCRETE commands** —
`list-sessions` / `capture-pane` / `send-keys` / `new-session`. Never a `-C` control-mode
attach, never a terminal emulator: tmux IS the emulator; a dropped link loses nothing.

Wire facts (lineage: `G2CC/server/src/tmux.ts`):

- Target **`=<name>:`** — `=` forces an EXACT session-name match, the trailing `:` forces
  session interpretation (→ its active pane). A bare `-t name` can resolve to a same-named
  WINDOW (the claude/claude2 mix-up of 2026-06-14). Verified on tmux 3.5a.
- **"no server running" from `list-sessions` means zero sessions, not a failure.**
- `capture-pane -p` = the visible grid; `-S -N` = N lines of history (tmux clamps); `-e` adds
  the SGR escapes (G2CC never used it; we do — §3.3).
- `send-keys` takes KEY NAMES (`Enter`, `C-c`, `Up`, `Escape`); `-l` sends LITERAL text.
- Keys reach ONE explicitly-opened TARGET only (`=session:idx` as shipped, pinned by review).
- Big `maxBuffer` on history captures; scrollback can be MBs.
- Tests run against a throwaway `tmux -L <socket>` server, never the real one.

Lessons paid for on glass (`windows/terminal.ts` review comments): poll lifecycle is
generation-guarded — window parked ⇒ poll STOPPED, and a stale in-flight capture must not
restart it (an orphan 500 ms capture loop was a real defect); after sending anything return to
LIVE (a lingering snapshot read as "it didn't work"); send failures surface ON GLASS; the
scrollback view is a FROZEN snapshot and "Live" returns to now; Adam's asks: full lines
wrapped, never column-cut (2026-06-14), quick keys centred on the Claude-approval loop
(y/n/Enter/Esc/Ctrl-C).

## 2. What goes away with the constraints (the archaeology)

Everything `terminal.ts` built around stock-firmware text containers is gone: 6-row pages
(`TERM_PAGE_ROWS`, the un-scrollable firmware scrollbar), the 540 B page byte-cap (the ~1000 B
layout-frame wall), rule-collapse to 18 cols (the firmware drew `─` at ~21 px) and
box-drawing width calibration (firmware metrics), the tail-text vs grid-image SPLIT (costs §3.4), CC input-box stripping (optional
filter in history reading only), and the on-screen tap keyboard — typed text arrives via the
three replicas (confirm-to-run) and, since 2026-09-01, the ring-driven §4.8 keyboard (Tmux's
"Type…" row). Dictation stays out: mic comes "way way later" (Adam, 2026-08-31).

## 3. The Damage design

### 3.1 Placement — the provider triple, same as Reader

`TmuxProvider` interface in core; three implementations mirroring Content's (`DESIGN.md`
§10.1: content = PC): **LocalTmuxProvider** (`ProcessBuilder` exec of `tmux`);
**HostTmuxServer** (the local provider over the EXISTING content port/token — new message types
on the length-prefixed JSON protocol, `ignoreUnknownKeys` keeps old peers decoding; one
persistent subscription per driving shell, `capture-pane` polled per subscribed target, **pushed
only on change**); **RemoteTmuxProvider** (the phone side).

The phone already knows beardos (host/port/token in Prefs). **The window declares `Need.HOST`**
(§10.5): PC unreachable ⇒ Main marks it unavailable, the window shows the last frame with the
staleness surface. **The staleness surface reaches EVERY level** (2026-09-05, `HANDOFF.md`
§30): the live pane paints it; the sessions list, history, keys and Main's row carry it in the
title as `· ! <what is wrong>`. Before that a stopped host said nothing while another host was
alive (`ghost` failed its status poll for half an hour with the sessions list reading clean).

### 3.2 The window — levels mapped to the §1 grammar *(⚠ grid-era diagram: LIVE renders as the FLOW view since 2026-08-31 — top matter; the grammar itself is unchanged)*

```
SESSIONS (ListView, free)          name · #windows · ● attached · ⚠ waiting · age
   │ tap                           lens: session name + last output line
   ▼
LIVE GRID (CanvasView)             the pane, true grid, JetBrains Mono, cursor cell inverted
   │ scroll-up = TIME              (scroll-down at live edge: no-op)
   ▼
HISTORY (DocView, free)            frozen scrollback, WRAPPED at reading size, 5-lines/notch
                                   + accel like Reader; scroll-down past the end → LIVE again
LIVE GRID ── tap ──▶ KEYS (ListView): Enter · y · n · 1 · 2 · 3 · Esc · Ctrl-C · Up · Down ·
                     Left · Right · Tab · q · Snippets… · Type… (§4.8 keyboard, 2026-09-01) · New session ·
                     (per-session rows)
```

- **Scroll IS scrollback** — no mode button. History is a frozen snapshot (`-S -1000`) from
  the live edge; new output sets the dirty tick, never yanks the view.
- **Tap descends** (§4.6): live → keys; a key sends and DROPS BACK TO LIVE. Double-tap backs.
- Quick keys are config-driven (host-side, served with the session list). Session
  end/rename/detach shipped with the §5 closure.

### 3.3 Rendering *(⚠ superseded for normal panes: the grid below survives ONLY for `#{alternate_on}` TUIs — the SGR mapping paragraph serves flow and grid alike)*

- **`capture-pane -e`** + a bounded SGR subset → (char, fg, bg, bold/dim/reverse/underline).
  Colours map to the 16 grays by luminance; bold brightens, dim dims, reverse swaps fg/bg.
  Unknown escapes are stripped and counted loudly.
- **Cursor**: `display-message '#{cursor_x} #{cursor_y} #{cursor_flag}'` → inverted grid cell.
  Static, not blinking (§6: a blink is 2 rects/s forever, for nothing).
- **JetBrains Mono** (locked, §Type). Grid: per-cell placement at exact `cellW` multiples;
  glyphs JBM lacks render as the visible tofu box, never dropped.
- **Grid fit math** (content 608×416 at 480): an 80-col pane ⇒ 7.6 px cells (~12.7 px em;
  27 % bigger than G2CC's 6 px, with real AA); a 22-row pane at 13 px rows uses 286 px, the spare ~130 px shows ~10 dimmed
  rows of history (`-S -10`), off under `#{alternate_on}`; "Fit pane to glass" =
  `resize-window -x 64`; at 288 the grid is 10 px em. *(Grid-only now.)*
- **History** was designed as a reading-size DocView; as built it is the flow itself on the
  WM's endless scroll (mode 9 shift + fill), 5 lines/notch + accel, NO TRUNCATION.

### 3.4 Costs, against the measured curve (⚠ the `ms ≈ 60 + bytes/50` below is PC-direct only — the daily path is the phone's, ~70 ms + ~120 ms/KB, `REMINDER.md`; a history notch measured 352–645 ms on it, `HANDOFF.md` §37)

**Both halves of this arithmetic moved on 2026-09-05** (`HANDOFF.md` §31): the curve covers four
hours of one session — a 6–12 KB flush measures a **1,193 ms** median across the journal (§31.6),
not the ~200 ms the slope predicts — and a scroll, which shipped 7.4–10.8 KB (measured) while canvases declared no translation, now
ships the mode-9 shift plus the exposed strip: ~5 KB measured for `HIST_STEP`'s five lines, the
floor for that step size.

| event | bytes (modeled) | latency | feel |
|---|---|---|---|
| typical CC churn (2–3 changed rows) | 0.3–0.8 KB | ~70–80 ms | live at the 1 s poll (configurable) |
| full grid repaint (TUI redraw) | 2–4 KB (text ink ~10–15 %) | ~100–140 ms | ~7 fps worst case |
| dense §4.6 canvas ceiling | 3.8–6.3 KB | ~1.5–2.5 fps | never hit by text |
| history scroll step | 292–486 B (§4.6, measured class) | ~66–70 ms | free |

The compositor's shadow-vs-truth diff ships only changed pixels, so window-side cell diffing is
a CPU nicety. The texture cache (96-glyph table ≈ ASCII) was not adopted at build time;
`CLAUDE.md` → "Latency standards" has the current state.

### 3.5 Alerts — the glasses-native feature G2CC never had

The host provider watches `#{window_bell_flag}` / `#{window_activity_flag}` per session plus
an optional last-lines pattern list (config; default tuned to CC's permission prompt /
"waiting" states) and pushes alert events; the shell surfaces them as §4.5 notifications
("tmux · claude2 wants input"), sets the dirty tick, and the sessions list marks ⚠ waiting.
Per-session mute in that session's actions. The workday loop is glance → tap → `y`.

- Until 2026-09-04 (`HANDOFF.md` §29) the notice was app-less and the tap only DISMISSED it. It
  carries `appId = "tmux"`, `target = session:<host>:<name>`, one thread per session, and
  `TmuxWindow.open(target)` lands in that session's live view — verified live.
- The pattern match runs over the last NON-BLANK lines of `capture-pane -p`. Until 2026-09-04
  (`HANDOFF.md` §28.2) the script took `tail -5` of the RAW pane — five empty rows for a pane
  that had not filled its screen, so a session created from the glasses never alerted and had
  no last line (`LocalTmuxProvider.STATUS_SCRIPT`).

### 3.6 Contract plumbing (all existing idioms)

- `summary()`: cached from the provider's last push, side-effect-free ("3 sessions · claude2 ⚠").
- State blob: session, mode, history offset (§9.1/§4.3); the switcher preview draws the last
  cached frame.
- `appSettings()`: Tmux directory — Update (0.5/1/2/5 s) beside the Font / Font size /
  Font style / Depth rows every app category carries (`HANDOFF.md` §17).
- Icon: `IconKind.TERMINAL`.
- Failures: capture/send errors → on-glass notice + status line, keeper-style paced retry of
  the subscription, never silent.

### 3.7 Shell additions required — ✅ BUILT

`CanvasView.onScroll(delta)` / `onTap()` exist and route like DocView's; Tmux was the first
consumer. Nothing else in the shell changed.

## 4. Security posture, stated plainly

`send-keys` is command execution on beardos by design — what Adam's own hands have at the
keyboard, reachable only through the token-gated, Tailscale-only content seam, targeting one
explicitly-opened session. Tests never touch the real server (`tmux -L damage-test` throwaway;
unit tests use a fake provider).

## 5. The explosion, the v1 cut and the refinery questions — CLOSED

Closed in one day (2026-08-31): every question answered (verdicts in the top matter), v1 built
and reworked into the flow view, and the v1.5/v2 rows shipped early — typed text via all three
replicas (confirm-to-run), multi-host over ssh, window targeting/select-window, session
end/rename, per-session mute. Still deliberately unbuilt: scrollback SEARCH (typing prerequisite
met since the §4.8 keyboard, 2026-09-01), the texture-cache glyph path, history reading filters,
session-output→Reader hand-off. ❌ never: blinking cursor, bell sound (§0).
