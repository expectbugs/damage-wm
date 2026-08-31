# Tmux on glass — design + plan (2026-08-31)

**Status: refinery PASSED same day — Adam's verdicts, all locked, build in one pass:**

1. **Scope = v1 core + typed text via replicas + multi-host (ssh) + session management.**
   Scrollback search stays out (clean to add once typing exists).
2. **Grid: fit + the explicit "Fit pane to glass" resize action.** No zoom setting.
   ⚠ Fit is computed against the LIVE layout rect — height mode runs 288/352/416/480
   (Adam mid-build: "resolution can be as low as 288px height or as high as 480px"), so
   fit = min(width-fit, height-fit), re-derived in `onLayoutChanged`, context rows only
   when spare height exists. 480 is the design point (`preferredHeight`), 288 must work.
3. **Alerts: on for all sessions + per-session mute. GLASS ONLY** — no phone fallback.
4. **Quick keys: the proposed dozen** (Enter y n 1 2 3 Esc Ctrl-C Up Down Tab q + Snippets…),
   config-overridable.
5. **Context rows: ON by default.**
6. **Every tmux setting lives in the Settings window's Tmux category** (`appSettings()` —
   Adam mid-build: "keep the settings inside the Tmux category of the settings window,
   same with all apps"). Per-session state (mute, viewed window) stays in the session's
   actions level — the Reader per-book precedent.

Defaults stated, not asked: typed text is always confirm-to-run (literal + Enter — G2CC's
settled 2026-06-18 semantics); new sessions auto-name `g2-N` from the ring, rename via typed
text; snippets default to G2CC's slash list; 500 ms capture pacing; 1000-line history;
waiting sessions pinned atop the list; window NAV is non-invasive (viewing a tmux window
targets `=session:idx` without `select-window` — selecting is an explicit action, same
class as the resize).

**The original proposal below stands as written except where the verdicts above override
it (§5's grades are superseded by verdict 1; §7's questions are answered).** This is the §4.6 app-layer design for
the Tmux window: the G2CC study, what dies with the old constraints, the Damage-native design,
costs priced against the MEASURED curve, the scope explosion, and a recommended v1 cut.

Sources: `G2CC/server/src/tmux.ts` + `windows/terminal.ts` (read in full; facts cited, no code
taken), `DESIGN.md` §0/§1/§4.6/§8/§Type/§10, `CAPABILITIES.md`, `overview.md` §5.2 (the measured
latency curve). Everything below is **modeled** unless marked measured.

---

## 1. What G2CC's Terminal got right (keep the facts)

The architecture: **the glasses are a viewer/controller of the real tmux server via DISCRETE
commands** — `list-sessions` / `capture-pane` / `send-keys` / `new-session`. Never a `-C`
control-mode attach, never a terminal emulator: **tmux IS the emulator**, `capture-pane` reads
its already-rendered grid, and a dropped link loses nothing because the session is durable and
was never attached. That safety shape carries over unchanged.

Wire facts worth their comments (lineage: `G2CC/server/src/tmux.ts`):

- Target **`=<name>:`** — the `=` forces an EXACT session-name match and the trailing `:` forces
  session interpretation (→ its active pane). A bare `-t name` can resolve to a same-named
  WINDOW — the claude/claude2 mix-up of 2026-06-14. Verified on tmux 3.5a.
- **"no server running" from `list-sessions` means zero sessions, not a failure.**
- `capture-pane -p` = the visible grid; `-S -N` = N lines of history (tmux clamps); `-e` adds the
  SGR colour/attribute escapes (G2CC never used this; we will — §3.3).
- `send-keys` takes KEY NAMES (`Enter`, `C-c`, `Up`, `Escape`); `-l` sends LITERAL text.
- Keys reach ONE explicitly-opened session only. Keep this rule verbatim.
- Big `maxBuffer` on history captures; scrollback can be MBs.
- Tests run against a throwaway `tmux -L <socket>` server, never the real one.

Behavioural lessons paid for on glass (lineage: `windows/terminal.ts` review comments):

- Poll lifecycle is generation-guarded: window parked ⇒ poll STOPPED, a stale in-flight capture
  must not restart it (an orphan capture loop every 500 ms was a real defect).
- After sending anything, return to the LIVE view — a lingering snapshot showed pre-command
  state and read as "it didn't work".
- Send failures must surface ON GLASS, not only in a log.
- The scrollback view is a FROZEN snapshot — history doesn't shift under the reader; "Live"
  returns to now.
- Adam's asks that shaped it: full lines wrapped, never column-cut (2026-06-14); quick keys
  centred on the Claude-approval loop (y/n/Enter/Esc/Ctrl-C).

## 2. What dies with the constraints (the archaeology)

Most of `terminal.ts` exists because stock firmware text containers owned rendering. None of it
carries over:

| G2CC machinery | constraint it served | Damage status |
|---|---|---|
| 6-row pages (`TERM_PAGE_ROWS`) | firmware overflow scrollbar was un-scrollable | dead — we own every pixel |
| 540 B page byte-cap | ~1000 B layout-frame wall | dead — image path, no wall |
| rule-collapse to 18 cols | firmware drew `─` at ~21 px and re-wrapped | dead — our rasterizer, our metrics |
| box-drawing width calibration | firmware font metrics unknowable | dead |
| tail (mangled text) vs grid (slow image) SPLIT | text was fast-but-mangled, images were seconds-slow | dead — one true grid, live, ~7 fps worst case (§4) |
| CC input-box stripping | 6 usable rows made chrome ruinous | dead for the live view (the grid IS the screen); optional filter in history reading mode only |
| on-screen tap keyboard | no other way to type at all | deferred — typed text arrives via the phone/browser replicas (§6), mic much later |

The one G2CC feature that was a workaround AND stays dead here: dictation (mic comes "way way
later" — Adam, 2026-08-31).

## 3. The Damage design

### 3.1 Placement — the provider triple, same as Reader

`TmuxProvider` interface in core; three implementations mirroring Content's (§10.1: content =
PC):

- **LocalTmuxProvider** — `ProcessBuilder` exec of `tmux` on this machine (PC shell,
  laptop-direct: zero hops).
- **HostTmuxServer** — serves the local provider over the EXISTING content port/token (new
  message types on the same length-prefixed JSON protocol; `ignoreUnknownKeys` keeps old peers
  decoding). One persistent subscription connection per driving shell: server polls
  `capture-pane` at 500 ms per subscribed session and **pushes only on change** — an idle
  session costs zero radio.
- **RemoteTmuxProvider** — the phone side of that channel.

Auto-detection is therefore free: the phone already knows beardos (host/port/token in Prefs);
the session list just appears. **The window declares `Need.HOST`** (§10.5): PC unreachable ⇒
Main marks it unavailable, the window shows the last frame with the staleness surface, honest.

### 3.2 The window — levels mapped to the §1 grammar

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
                     Tab · q · Snippets… · New session · (per-session rows)
```

- **Scroll IS scrollback** — the terminal's own mouse-wheel grammar, no mode button. Entering
  history captures a frozen snapshot (`-S -1000`), starts at the live edge, pages back; new
  output while reading sets the dirty tick, never yanks the view.
- **Tap descends** (§4.6): grid → keys level; a key sends and DROPS BACK TO LIVE to watch it
  run (G2CC's lesson). Double-tap walks back up. Depth feeds the bottom divider.
- Quick-keys list is config-driven (host-side config, served with the session list), default
  tuned for the Claude-approval loop — including `1`/`2`/`3` for CC's numbered choices, which
  G2CC never had.
- **New session** auto-names (`g2-1`, `g2-2`, …) — naming from glass needs typing; rename from
  the PC. Kill/rename/detach: explosion items (§5).

### 3.3 Rendering — the true grid, finally

- **`capture-pane -e`** + a bounded SGR subset → per-cell (char, fg, bg, bold/dim/reverse/
  underline). Map colours to the 16 grays by luminance; bold brightens, dim dims, reverse swaps
  cell fg/bg. htop bars, CC diffs and error reds become *legible structure* instead of being
  flattened. Unknown escapes are stripped and counted loudly.
- **Cursor**: `display-message '#{cursor_x} #{cursor_y} #{cursor_flag}'` → inverted cell.
  Static, not blinking (§6 motion discipline: a blink is 2 rects/s forever, for nothing).
- **JetBrains Mono** (locked, §Type: "alignment is functional here"). Per-cell placement at
  exact `cellW` multiples so 80 columns never shear; glyphs JBM lacks render as the visible
  tofu box (the texture-cache table rule's idiom — never dropped, never silent).
- **Fit math** (content 608×416 at height 480 — the window sets `preferredHeight = 480` like
  Reader): an 80-col pane ⇒ 7.6 px cells (~12.7 px em). Modeled-legible — G2CC's grid was 6 px
  cells and got used; ours is 27 % bigger with real AA. A 22-row pane at 13 px rows uses 286 px,
  so **the spare ~130 px shows ~10 rows of immediate history above the pane, dimmed** — the pane
  plus its recent past, same capture call (`-S -10`), zero extra cost. Disabled automatically
  when `#{alternate_on}` says a full-screen TUI owns the pane (blending history into htop would
  lie). Narrower panes (splits) get bigger cells for free — the pane size is the zoom lever.
  ⚠ **Whether 7.6 px cells read on glass is an open item of the §2.2b class** — the plan
  includes an on-glass calibration step, and a "Fit pane to glass" action (`resize-window
  -x 64`) as the opt-in legible fallback. At height 288 the grid scales down hard (10 px em);
  480 is the design point.
- **History mode is a DocView** — wrapped at Reader-class reading size, the WM's endless scroll
  (mode 9 shift + fill), 5-lines/notch default + the accel setting, NO TRUNCATION ever.

### 3.4 Costs, against the measured curve (`ms ≈ 60 + bytes/50`, `overview.md` §5.2)

| event | bytes (modeled) | latency | feel |
|---|---|---|---|
| typical CC churn (2–3 changed rows) | 0.3–0.8 KB | ~70–80 ms | live at the 500 ms poll |
| full grid repaint (TUI redraw) | 2–4 KB (text ink ~10–15 %) | ~100–140 ms | ~7 fps worst case |
| dense §4.6 canvas ceiling | 3.8–6.3 KB | ~1.5–2.5 fps | never hit by text |
| history scroll step | 292–486 B (§4.6, measured class) | ~66–70 ms | free |

The window repaints the whole grid into its canvas; **the compositor's shadow-vs-truth diff
already ships only changed pixels** (§5 — the review-hardened per-lens model), so cell-level
diffing window-side is a CPU nicety, not correctness. The texture cache (modes 12–14) would cut
these numbers further and a terminal is its poster child (96-glyph table ≈ ASCII), but it is
**unverified on glass (first-light items 19–20) and the compositor hasn't adopted it — v1 rides
the pixel path, cache adoption is a later optimization**, not a dependency.

### 3.5 Alerts — the glasses-native feature G2CC never had

The host provider (it's polling anyway) watches `#{window_bell_flag}` / `#{window_activity_flag}`
per session (protocol-clean) plus an optional last-lines pattern list (config; default tuned to
CC's permission prompt / "waiting" states) and pushes alert events. The shell surfaces them as
ordinary §4.5 notifications ("tmux · claude2 wants input"), sets the window's dirty tick, and
the sessions list marks ⚠ waiting. Per-session mute in that session's actions. **This turns the
window from "go look" into "it tells you"** — the actual workday loop is glance → tap → `y`.

### 3.6 Contract plumbing (all existing idioms)

- `summary()`: cached from the provider's last push, side-effect-free ("3 sessions · claude2 ⚠").
- State blob: session, mode, history offset — restorable, previewable (§9.1/§4.3); the switcher
  preview draws the last cached frame.
- `appSettings()`: Tmux directory — poll cadence, context rows on/off, alerts on/off, grid
  scale (if the calibration says we need a non-fit option).
- Icon: `IconKind.TERMINAL` already exists.
- Failures: capture/send errors → on-glass notice + status line, keeper-style retry of the
  subscription with pacing, never silent (the three absolute rules apply as usual).

### 3.7 Shell additions required (small, additive)

`CanvasView` today paints but ignores input (`Shell.kt` scroll/tap route `{}` for canvas — it
was "not used by stage 1"). Add optional `onScroll(delta)` / `onTap()` callbacks to
`CanvasView`, routed exactly like DocView's. Nothing else in the shell changes; paint, preview,
planes and persistence already handle canvas.

## 4. Security posture, stated plainly

`send-keys` is command execution on beardos by design — the same capability Adam's own hands
have at the keyboard, reachable only through the existing token-gated, Tailscale-only content
seam, targeting one explicitly-opened session. Same trust envelope as G2CC ran for months.
Tests never touch the real server (`tmux -L damage-test` throwaway, the G2CC pattern; unit
tests use a fake provider and no tmux at all).

## 5. The explosion (graded, for the refinery)

| idea | note | my grade |
|---|---|---|
| SGR colour → 16-gray mapping | §3.3; contained parser + tests | **v1** |
| context rows above the pane | §3.3; free, alternate-screen-aware | **v1** (default ON, his call) |
| alerts (bell/activity/pattern → notifications) | §3.5 | **v1** |
| quick keys incl. digits, config-driven | §3.2 | **v1** |
| scroll-is-scrollback grammar | §3.2 | **v1** |
| "Fit pane to glass" action (`resize-window`) | invasive to the real session — explicit action, never automatic | v1 if trivial, else v1.5 |
| **typed text from the replicas** (phone strip + browser page + desktop preview → focused window) | the REMINDER open item; confirm-to-run guard like G2CC's typed path; needs a small replica+seam extension | **v1.5, first follow-up** — the ring can't type, and this completes "control" |
| tmux WINDOW navigation inside a session (`select-window`) | sessions-only first; his CC sessions are single-window | v1.5 |
| multi-host via ssh (slappy :80, etc.) | host list in PC config; the provider fans out; sessions tagged `host:name` | v2 — design keeps room (session ids carry a host field from day one) |
| kill / rename / detach-others session actions | destructive → confirm level | v2 |
| scrollback SEARCH | needs typing first | v2, after typed text |
| pane navigation within a window | splits render fine already (the grid shows them); targeting a specific pane | v2 |
| texture-cache glyph path (modes 13/14) | after first-light items 19–20 and compositor adoption | future, priced separately |
| history reading filters (rule-line collapse etc.) | reading comfort only, default off — the live grid never filters | future |
| session output → Reader hand-off ("read this log as a book") | cute; the refinery can kill it | future |
| blinking cursor, bell sound | ❌ motion discipline; ❌ §0 no-buzzer | never |

## 6. Recommended v1 cut and build order

**v1 = watch + approve, slickly:** sessions list · live SGR grid with cursor and context rows ·
scroll-is-scrollback history · quick keys + snippets · new session · alerts · staleness/offline
honesty · full persistence. **Explicitly out of v1:** typing (v1.5 via replicas), multi-host,
window/pane targeting, search, texture cache.

Build order (each step battery-green before the next, per the standing process):

1. **Grid core** (pure, testable): SGR parser → cell model → JBM cell renderer → Gray8.
   `TmuxGridTest` pins bytes.
2. **Provider triple** + protocol on the content seam + fake provider; `TmuxProviderTest`.
3. **Window + shell canvas hooks**: levels, grammar, persistence, settings, alerts→notifications;
   grammar tests via the fake provider; a tmux scene in `--selfcheck`/`--snapshot`.
4. **Integration**: register on desktop + phone, config for snippets/patterns; optional
   `-L`-socket integration check.
5. **On-glass calibration** (Adam): fit-80 legibility, context-rows verdict, quick-key order,
   alert defaults — the §2.2b-class items only glass can answer.

## 7. Questions for the refinery

1. **Grid scale**: is fit-80 at ~7.6 px cells acceptable as the default, with "Fit pane to
   glass" as the escape hatch — or should v1 ship a zoom setting day one?
2. **Context rows** above the pane: on by default?
3. **Alerts**: on by default for every session, or opt-in per session?
4. **Quick keys**: the proposed default list (Enter y n 1 2 3 Esc Ctrl-C Up Down Tab q) — what's
   missing from your actual approval loop?
5. **Typed text via the replicas** as the immediate v1.5: agreed, or does it belong in v1?
6. **Multi-host**: is beardos-only v1 right, or is slappy day-one load-bearing?
