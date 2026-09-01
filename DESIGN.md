# Damage — shell design contract

**The WM's own surface: input grammar, geometry, depth, motion, persistence, typography, and what a
frame costs.** Locked with Adam 2026-08-17/18. Successor to G2CC's `docs/DE_DESIGN.md`, rewritten
for the CFW direct-framebuffer path.

**Precedence.** `overview.md` wins on *facts*; `CLAUDE.md` wins on *rules*; **this file wins on
shell design.**

**What is measured and what is modeled.** Ink coverage, per-face compression, glyph coverage and
full-screen keyframe cost are **measured** from real 1× renders (`design/render_shots.py`, output in
`design/shots/`). The per-interaction *delta* costs in §8 are still **modeled** from the 576×288
capture measurements — every table says which. Two tools enforce the rest:
**`tools/lint.py`** (the build gate, §9.2b) and **`tools/geometry.py`** (the rules it shares with
the compositor).

📍 **New here? Read `REMINDER.md` first** — project state, what comes next, and the consolidated
first-light checklist. **If you are deciding implementation, read §10 (deployment topology) before
anything else** — it is the only part of this document that constrains *how* the shell is written,
and it rules out one otherwise-obvious runtime choice.

> Grades from [`CLAIMS.md`](CLAIMS.md): **V** vendor-authoritative · **M** measured · **C**
> corroborated · **I** inferred · **S** single-source ⚠ · **U** unknown.

---

## 0. Excluded, deliberately

Recorded so they are not re-proposed. These are *decisions*, not oversights.

| excluded | why |
|---|---|
| ❌ **The piezo buzzer, entirely** | Adam, 2026-08-17: *"no tone/buzzer. i do not want my glasses to emit sound."* Mode 5 is never sent. Out-of-band alerting goes to the phone instead (§9.3) |
| ⚠ ~~❌ **Scroll acceleration / velocity**~~ **RE-OPENED 2026-08-30 · ✅ SHIPPED 2026-08-31** | The original reasoning: scroll and tap are hard to distinguish on the ring's small sensor, especially gloved, so every notch is one step, always. **Adam reversed this after using Reader on glass** — one line per notch inside a book is far too little, and he asked for accelerated scrolling "like firmware scroll", with a lines-per-notch setting as the fallback. Two things also changed underneath the original call: ack latency measured at ~50 ms rather than 176 ms, which makes motion much cheaper, and first light showed tap and scroll arriving as cleanly distinguished event types rather than as one ambiguous stream. **Shipped 2026-08-31: a per-notch step setting (1–8 lines) plus a direction-gated ramp — notches ≤250 ms apart multiply the step up to 6×. Verdict the same day, on glass: the ramp is too uneven — "lets just default the scrolling to 5 lines per notch - configurable." So the DEFAULT is 5 lines/notch with acceleration OFF; both configurable in the Settings window's Reader category. Lists stay strictly one item per notch: acceleration over selections would overshoot them.** |
| ⚠ ~~❌ **User-settable chrome/app typography**~~ **REVERSED 2026-08-31 · ✅ SHIPPED** | §Type locked "the system face is not negotiable per window" and one fixed chrome face. **Adam reversed it**: Settings → Global now sets font/size/style for chrome + Main, and every app category sets font/size/style for that app's content — each option previewed in its own face. The §Type MEASUREMENTS still stand (they price the choices); only the lock moved from "decided once" to "his to change at runtime". `core/text/Style.kt`; defaults render byte-identically |
| ❌ **Wear / unwear differentiation** | not wanted for now. `wearnotify` stays banked, unused |
| ❌ **Head tracking on by default** | available, **defaults OFF** — his head moves constantly at work (§7.1) |
| ❌ **Long-press as a live gesture by default** | reversed 2026-08-30: it is the most common accidental press by far, all day, gloves worst — **defaults OFF (a no-op)**; the §1.3 chord opens the switcher; a Settings row restores the direct open |
| ❌ **Dithering** | halves compression; the 4-bit downsample looks better without it |
| ❌ **A quit path** | the WM runs always (§1.6) |
| ❌ **Split view** | deferred — optional stretch feature, not the first design |
| ❌ **Fades / dissolves / cross-fades** | not expressible as translation + fill ⇒ too expensive (§6.2) |
| ❌ **Using the 16 px side gutters for content** | they stay **depth margin**. Adam, 2026-08-17 |
| ❌ **The ribbon** | retired 2026-08-18 — a third window list competing with Main and the switcher, both of which did it better (§4.1) |
| ❌ **Segmented battery icons** | measured worse than a plain fill bar at this width, and 20× the run boundaries (§4.1) |
| ❌ **A generic "overlay" abstraction** | the switcher and the notification surface are **bespoke**, designed independently (§4.3) |

---

## 1. Input grammar — ring only

**The R1 ring is the only input device.** Temple touchpads are deliberately unused. This aligns
with the CFW, which hard-gates both halves of the long-press pair on the ring
(`g2flash/patches/gesture_fwd.c`):

```c
#define SRC_RING 4                                   /* source byte @ 0x2034dc30 */
void evenhub_longpress(void) { if (EVT_SRC == SRC_RING) FW_SYSEVT(0,0,0, ET_LONG, 0,0); }
int  ring_release(void *ctx, int code, void *data)
                              { if (EVT_SRC == SRC_RING) { ...ET_REL... } ... }
```
> *"A touchpad long-press in EvenHub now does nothing (dialog removed, no forward)."*

🔴 **The ring-only rule's mechanics, corrected 2026-08-31 (HANDOFF.md §12).** "Ring only" is
enforced by source byte for the attributed gestures (tap / double-tap / scroll carry
`EventSource = 2`) — but **events 9/10 are UNATTRIBUTED by firmware design** (`EventSource` is
absent for them; they decode as source 0) and MUST skip that check. The first implementation
filtered them out with everything else, which made the switcher unreachable by both routes for
two days while `LongPressTest` passed — its harness injected 9/10 with the flattering ring
source. The suite now injects them with source 0, the wire truth. What keeps the temple (the
second unattributed source since `a5d1c31`) harmless is §1.2's bare-long-press-is-a-no-op
default, not the source filter.

### 1.1 The five events

| gesture | wire | grade |
|---|---|---|
| **tap** | EvenHub `Sys_ItemEvent`, `EventSource = 2 (RING)` — stock, unpatched | **V** |
| **double-tap** | same path, stock | **V** |
| **scroll ↑ / ↓, per notch** | stock; every notch is delivered because the capture container holds one space and can never scroll internally (§1.5) | **C** ⚠ |
| **long-press** | CFW `SysEvent 9` `RING_LONG_PRESS_EVENT` — fires when the hold threshold trips, *while still held* | **V** |
| **long-press release** | CFW `SysEvent 10` `RING_LONG_PRESS_RELEASE_EVENT` | **V** |

⚠ Do not conflate the two source numberings: protobuf `EventSourceType` is `1=GLASSES_R, 2=RING,
3=GLASSES_L`; the firmware's internal source byte is `0/1 = L/R temple, 4 = ring`.

⚠ The hold threshold is a stock firmware constant, **unmeasured (U)**.

### 1.2 Semantics

| gesture | meaning |
|---|---|
| **tap** | select / enter / activate the focused thing |
| **double-tap** | back one step |
| **long-press** | ⚪ **nothing, by default** (revised 2026-08-30, below) — it only ARMS the §1.3 chord. Inside the open wheel it still cancels. Settings ("Long-press · switcher") restores the direct open |
| **scroll** | move the focus cursor within whatever holds focus (including the switcher) |
| **long-press release** | refreshes the §1.3 chord window (so it runs from letting go); otherwise nothing |

🔴 **Revised 2026-08-30 (Adam): long-press defaults to a NO-OP, everywhere.** The accidental
long-press is the most common misfire by far — constant at work, all day, worst with gloves on —
and it is the documented gloves chain that founded this project (`overview.md` §6). A gesture that
fires by itself all day cannot carry a live meaning by default. The Settings row **"Long-press":
off (default) / switcher** restores the direct open for anyone it does not bother; with it off,
the switcher opens by the §1.3 chord, and the §4.5 focused-notice long-press is a no-op too.

### 1.3 The switcher — ALT+TAB

**Revised 2026-08-17.** The earlier design (hold to peek, release commits) was replaced because a
ring is *"rife with accidental presses"* and a gesture where letting go commits a navigation is
exactly the wrong shape for that.

```
long-press, double-tap → switcher opens — the CHORD (default; "Long-press · switcher" makes long-press alone open it)
scroll              → spin the wheel, and preview: the window behind the panel becomes the selected one (§4.3)
tap                 → commit: closes the panel; the window is already there
long-press          → cancel / close, and restore the window you came from (live inside the wheel even when off)
double-tap          → also cancels (it is one step on the back stack, §1.4)
```

**Revised again 2026-08-30 — the chord.** With long-press off (§1.2), the wheel opens by
**long-press, then double-tap immediately after letting go**: `SysEvent 9` arms an **800 ms
window**, the release (`SysEvent 10`) refreshes it — so the clock runs from letting go — and a
double-tap inside the window opens the wheel. Any other gesture ends the chord and keeps its own
meaning. The shape is deliberate: the ARMING event is the rare one, so no common gesture is ever
delayed or re-meant; a bare accidental long-press does nothing at all; the full accidental chord
needs an accidental double-tap inside the same 0.8 s — and even that only opens a wheel that
cancel restores, so §1.7 still holds. A mistimed deliberate chord degrades to plain back
(recoverable). Feedback is the §9.2 input echo: the "hold" glyph in the status cell IS the armed
indicator. This is a **sequence window**, the same species as §4.5's grace — nothing is held, so
§11's retirement of held gestures stands. In **silent mode the long-press never arms** (§1.5):
gloves-on is where accidental presses are the most common, and double-tap must always mean wake
there. Whether the real ring delivers `SysEvent 10` reliably (and whether 800 ms is comfortable)
is first-light item 18 in `REMINDER.md`; if the release never arrives, the window runs from
event 9 alone.

**Nothing commits on release.** A stray long-press opens a cancellable list and changes nothing.

**List order, and where the cursor lands:**

```
    Main                      ← up two
    Current window            ← up one
 ▸  Most recent inactive      ← CURSOR STARTS HERE
    2nd most recent
    3rd most recent
    …in order of recentness
```

⇒ **long-press · double-tap · tap = switch to the most recent inactive window.** That is ALT+TAB, in three
unambiguous gestures, with an explicit cancel and no timing dependency.

✅ **This also retires two open items**: the hold-threshold no longer prices the interaction, and
"is hold-plus-scroll physically comfortable?" no longer needs answering — nothing is held.

⚠ **It does move a cost around.** Under the old grammar release always navigated, so nothing was
ever restored. Now cancelling has to put back what was there, and there is no off-panel scratch on
this hardware. With live preview (§4.3) this settles into a good shape: **commit is the cheap path
(~250 ms, panel-sized) and cancel is the expensive one (~520–750 ms, full restore)** — the common
action is fast and the rare one pays. It is also why the panel is small and centred.

### 1.4 The back stack

**One unified stack.** Focus changes and content navigations both push; double-tap pops whatever
is on top, whichever kind it was.

```
  …content levels…  → window root → MAIN → SILENT MODE → MAIN
        ── double-tap at each step ──
```

### 1.5 Silent mode

Everything hidden but the clock.

#### 🔑 The clock is a small SEVEN-SEGMENT DIGITAL readout, flush top-right

**Third revision, Adam 2026-08-31 (mid-session, wearing it):** *"move the silent mode clock back
to the top right … all the way up and all the way right, and forget analog, make it good-looking
digital numbers … something quality."* History, so nobody re-litigates it: 2026-08-18 he rejected
a large centred digital clock (*"way too huge and centered and bold"*) for a small dim analog
face top-right; 2026-08-30 he asked for top-left and better; 2026-08-31 on glass he settled it —
**digital, top-right, flush to the corner, quality.** The 2026-08-18 objection was to *huge and
centered*, not to digits.

**`144×48` at the safe rect's top-right corner** (box edges touch it; digits 2–4 px inside).
Classic LED-clock **seven-segment digits, drawn, never typed** — the locked faces stay four —
with tapered hexagonal segments, softened edge rows, corner gaps, 12-hour, no leading zero.
Horizontal segments are single long RLE runs, so the whole readout prices like a few short
lines. **Minutes only — it repaints once a minute, 60 flushes/hour**, and deep idle (§5.15)
stays completely intact. (The analog face survives unused in `Icons.analogClock` in case it
returns as a setting.)

**Measured: 0.5 % ink, 174 B, 192 ms (stock-formula pricing)** — the seven-segment readout costs
FEWER bytes than the analog face did (174 vs 178 B): angled hands were RLE-expensive, horizontal
segments are runs. Compare the 1.6 % / 1,165 B the 2026-08-18 *centred* digital clock cost — the
objection then was huge-and-centered, and this one is 144×48 in a corner.

⚠ **On a sweeping second hand.** Bytes are not the constraint — the constraint is that a 1 Hz update
runs the radio continuously and contradicts deep idle (§5.15), on a device whose **only power
control is the case, which stays home during the workday**.

| option | flushes/hour | |
|---|---|---|
| ✅ **hands snap on the minute** | **60** | adopted |
| hands glide over 4 frames | 240 | rejected — unnecessary motion |
| smooth 1 Hz sweep second hand | 3,600 | rejected |

⇒ Bytes were never the constraint (a whole silent frame is 178 B). The constraint is that anything
sub-minute runs the radio continuously on a device whose **only power control is the case, which
stays home during the workday.** Temporary popup notifications still appear. **All input swallowed
except double-tap**, which returns to Main.

🔑 **This completes the gloves fix.** `overview.md` §6: glove-induced ring long-press → "End
Feature?" → app ended, or a second long-press → Firmware Menu → Silent Mode. The dialog no longer
exists (CFW patch), and in silent mode a stray long-press is swallowed by us — it does not even
arm the §1.3 chord (2026-08-30), so double-tap always means wake here. **The chain has no
first step left.**

✅ **A G2CC hazard that is structurally impossible here.** `DE_DESIGN.md` records a rule learned
twice: *"a scroll=true clock as the SOLE text region kills ALL input incl. double-tap; the v1.2
blank screen did exactly that (wake took many taps)."* Under Damage the event antenna is the
carrier layout's dummy full-screen text container (`content=" "`, `isEventCapture=true`), and
**silent mode is a paint, not a layout change** — the antenna cannot be lost.

⇒ **Rule: never tear down or rebuild the carrier layout to change what is on screen.** Keep
Faceclaw's periodic `TextContainerUpgrade{ContentOffset=0, ContentLength=1, Content=" "}`.

### 1.6 There is no quit

The WM runs always, like G2CC. ⚠ **Consequence: the framebuffer lease is the liveness contract** —
sid 0x09 field 101 op 5, **both arms, renewed every 45 s against a 90 s expiry.** It fails *open*:
stop renewing and stock LVGL silently repaints over us. With no quit path there is nothing to
distinguish "gone" from "idle", so a missed renewal is a hard error.

The stock **both-temple long-press → Silent Mode** stays as the hardware escape hatch. Ring-only
makes it un-triggerable by accident.

### 1.7 Misfire tolerance is a design requirement

Scroll-vs-tap ambiguity on a small sensor, gloved, is a **stated daily problem**. The design
answer is to make misfires cheap rather than to prevent them:

- **Cursor rest discipline** — after any menu/state change the cursor resets to a harmless cell.
  (G2CC, verbatim: *"a stray tap had aborted a $5 turn."*)
- **Destructive actions are never at a cursor rest position**, and never at index 0/1.
- **Every navigation is undoable** by double-tap; the back stack is the undo stack.
- **Input echo** — the status bar shows the last gesture actually received, so an ambiguous
  physical action becomes an observable one (§9.2).

---

## 2. Geometry — 640×480

### 2.1 The quantization grid

Mode-3 boxes encode as `[left/4][top/2][width/4][height/2]`, one byte each (`zlib_glue.c`):

> **Every x and width is a multiple of 4. Every y and height is a multiple of 2.** Out-of-bounds
> boxes are **rejected silently** — the old frame stays up.

### 2.2 The layout

🔴 **Revised 2026-08-31 (REFINEMENT.md §1): the bars are inset to the content extent, x 16–624.**
Chrome now sits BEHIND the content plane (§3.1), and a stereo shift needs the same 16 px side
budget the content has — a full-width rect cannot shift at all without leaving the panel. The
16 px strips beside the bars are shift gutters, level 0, exactly like §3.3's.

```
        0  16                              368              544  624  640
      ┌─┬───────────────────────────────────┬─────────────────┬─────┬─┐
  0   │░│  ▣ WINDOW · document              │    Battery      │Clock│░│  32
      │░│               352                 │       176       │  80 │░│
 32   │░├───────────────────────────────────┴─────────────────┴─────┤░│   2   divider
 34   │░│                                                           │░│
      │░│            content   608 × 416   (x 16 – 624)             │░│ 416   content
      │░│                                                           │░│
450   │░├──────────┬─────────┬──────────┬─────────┬─────────────────┤░│   2   divider
452   │░│  Cur Op  │  status │ ↑ B/s·ack│ tape ✱  │   link signal   │░│  28   status bar
      │░│   128    │   132   │   128    │   100   │       120       │░│
480   └─┴──────────┴─────────┴──────────┴─────────┴─────────────────┴─┘
         ░ = the 16 px stereo-shift gutters, level 0, both sides (§3.3)
```

`32 + 2 + 416 + 2 + 28 = 480` ✅ — **bars and content share the extent 16–624 and centre on
x = 320**, the same axis as the switcher wheel and Main's lens.

### 🔴 2.2b Express this layout RELATIVE to a calibrated safe rect, not absolute

Every number in this file is written against a full 640×480. **That is the assumption to build on
for now** (Adam, 2026-08-18) — but it is an assumption, because usable extent is fit-dependent and
**cannot be known before first light**:

> *"You can lose part of the top or bottom to optical occlusion depending how the glasses sit on
> your face."* — the CFW author

Designing for the worst case surrenders FoV we may not need to; designing for the best case risks a
UI that does not work at the desk it is meant for. **So make it a calibration, exactly like
disparity (§3.4):** a first-light ramp draws a border and shrinks it until it is fully visible, and
that safe rect is stored as a setting. `640×480` and Faceclaw's `640×288` then stop being two
designs and become two values of one parameter. *(A vertical-position setting once fell out of
this too — retired 2026-08-31: Adam's fit always shows the top and loses the bottom, so every
reduced band is TOP-aligned; see §4.2 Size and §2.5.)*

⚠ **Cheap now, expensive to retrofit.** It means no hardcoded `34` / `210` / `450` anywhere: the
bars, the lens and the content band are all positioned *from the safe rect*. Adopt the discipline
immediately even while the value stays 640×480.

✅ **The importance gradient is what makes it degrade gracefully**, and we already have it. Rendered
with 56 px occluded top and bottom (`design/shots/cmp-480-ribbon-occluded.png`), the current layout
loses only the top bar and the status bar — clock, battery, telemetry — and keeps **every dashboard
row and the lens**. Load-bearing content is centred; the outermost rows carry the things that are
right to lose. That is §2.5, demonstrated rather than asserted.

### 2.3 Cell table

*(Revised 2026-08-31: bars inset to x 16–624 — REFINEMENT.md §1 — and the status-bar split is
§4.5b's tape rebalance on the 608 px bar.)*

| zone | cell | x | w | y | h |
|---|---|---|---|---|---|
| top bar | Title — `▣ WINDOW · document` | 16 | 352 | 0 | 32 |
| | Battery ×2 (G glasses · P phone), 58 px pitch, 30 px body | 368 | 176 | 0 | 32 |
| | Clock | 544 | 80 | 0 | 32 |
| divider | | 16 | 608 | 32 | 2 |
| content | (nominal, ±d for depth) | 16 | 608 | 34 | 416 |
| divider | | 16 | 608 | 450 | 2 |
| status bar | Current operation | 16 | 128 | 452 | 28 |
| | Status | 144 | 132 | 452 | 28 |
| | Throughput ↑ · ack ms | 276 | 128 | 452 | 28 |
| | **Compass tape** | 404 | 100 | 452 | 28 |
| | Link signal | 504 | 120 | 452 | 28 |

Every value divides: x/w by 4, y/h by 2. ✅ Both bars tile 16 → 624. ✅

*(Optional variant: merging Current operation + Status into one 352 px marquee cell is cleaner and
handles long messages better under NO TRUNCATION. Kept separate by default, as specified.)*

### 2.4 Rules

1. **Chrome cell rects are fixed.** Contents change; boxes never move, resize or reflow.
2. **One rect per bar, never one per cell** — see the fid budget in §8.2.
3. **Nothing is ever silently cut. Two mechanisms, chosen by context.** *Marquee* where motion is
   wanted and the element is focused (Main's lens, the switcher's centre item) — mode 9 takes full
   uint16 coords, so a horizontal marquee is a shift plus a small fill; step by 4. *A `▸`
   continuation mark* on everything persistent and unfocused (unfocused Main rows, list rows) —
   it advertises that more exists without putting permanent motion in the periphery or costing a
   flush forever.
   🔴 **Worded honestly (Adam's push, 2026-09-01):** the mark does not make a cut acceptable —
   the guarantees behind it do. NO TRUNCATION's absolute half governs **content**: documents wrap
   and scroll, bodies scroll, the tmux flow wraps — nothing content-level is ever elided. Rows and
   titles are **handles** to content, and a handle may be elided only when the cut is
   **advertised** (the mark appears exactly when something was clipped) and the full text is
   **reachable in place** (focus it and the lens shows it whole; descend and the content is all
   there). The ellipsis ban is style, not principle — the drawn `▸` is a solid closed form that
   survives 1× where dot-triples grey out (§2.4 rule 9), and it points at the mechanism rather
   than stating an omission — but an ellipsis with the same guarantees would satisfy the same
   rule. The shared fit helper draws the mark whenever it clips, so an unadvertised cut is
   impossible by construction, not by discipline. The Title is not covered by the reachability
   rule — it is covered by a stronger one: §4.1's short-by-design contract.
4. **Full height 480 is the default.** Content is 416, bars are thin.
5. **Layout snaps to the damage grid** — every cell edge, text baseline and glyph origin lands on
   4 px x / 2 px y so a dirty rect never has to grow to cover a stray pixel.
6. **Chrome is identical across every window** ⇒ a window switch repaints the **content area only**,
   never the bars.
7. **Line height is a multiple of 2 px, baselines on the grid.** Otherwise scrolling by exactly N
   lines produces an unaligned mode-3 fill and the rect grows to cover. Trivial now, painful to
   retrofit.
8. **One 8 px design grid** — a multiple of both damage axes. Every inset, padding, icon size and
   cell edge sits on it, so alignment is guaranteed by construction rather than by discipline.
9. **Icon language for 16-level mono at FAR:** thick strokes, closed forms, no hairlines. A thin
   line plus AA at small angular size is mush.

### 2.5 Safe area

Usable extent is fit-dependent — *"You can lose part of the top or bottom to optical occlusion
depending how the glasses sit on your face."* Nothing load-bearing goes in the outermost rows. The
status bar is the most at-risk element here and its contents are telemetry, which is the right
thing to lose.

🔑 **Adam's own fit, measured by wearing it (2026-08-31): the TOP is always visible; it is the
BOTTOM that goes under occlusion when the glasses ride high.** That is why every reduced band is
top-aligned (§4.2 Size) and why the status bar being the sacrificial edge is the right ordering
for him specifically, not just in principle.

---

## 3. Depth

### 3.1 Layer order

> ⚠ **Amended 2026-08-31 (Adam):** the bars and Main keep the GLOBAL depth setting, but the
> focused APP's content plane uses a per-app depth (Settings → <app> → Depth, default 8 on the
> 0/4/8/12/16 ladder) — app content pops forward of, or parks behind, the chrome. The ladder,
> the horizontal-only rule and the forward-for-focus language below are unchanged.

🔴 **Adam, 2026-08-17, correcting an inference we had recorded as his preference:**

> "the main window as far back as depth comfortably allows, and notifications and the like to pop
> over it in front of it."

🔴 **Revised 2026-08-31 (REFINEMENT.md §1), after wearing it:** chrome was sharing plane 0 with
the selection and read as competing for attention. Adam: the bars belong **"as far back as depth
allows"** — behind the content plane, never with the selection. Chrome joins the back; the
direction of the ladder is unchanged.

| plane | contents | disparity |
|---|---|---|
| **+1** (nearest, crossed) | critical modals only — *off by default* | −4 / −8 |
| **0** (screen plane) | **popups / notifications / switcher overlay**, the focused lens | 0 |
| **−1** (far) | **main content**, parked permanently | +d (8 … 16) |
| **−2** (farthest) | **chrome** — both bars and their dividers | d + 4, capped at 16 (the bar inset) |

**Depth is a z-order *signal*, not decoration** — the eye reads the layering pre-attentively, so
you know something popped before you read it. **Modal depth = modal state**: a confirm dialog one
step forward, and the depth itself says "this is blocking."

### 3.2 Mechanism, from source

```
mode 3 stereo:  [3|80][Lbox 4][Rbox 4][fid 2][shared zlib]        +4 bytes
                 boxes size-checked equal: src[3]!=src[7] || src[4]!=src[8] -> reject
                 box_off = (FW_SIDE()==2) ? 1 : 5      /* left set / right set */
mode 9 stereo:  [9|80][Lsrc][Ldst][Rsrc][Rdst]                    +16 bytes
```

- **Disparity is quantized to 4 px** — `left` is one byte ×4. Ladder: 0, 4, 8, 12, 16. No fine
  adjustment exists.
- **Cost is negligible.** Depth is the cheapest visual feature on the device.
- **Scrolling stereo content works** — mode 9 has its own lenses-differ form.

⚠ **Nobody has ever sent one.** Faceclaw contains no stereo code at all. The mechanism is V-grade;
the comfort is untested by anyone.

### 3.3 Why 608 wide

Content inset 16 px each side gives the full ladder. At maximum disparity the left lens draws at
x=0 and the right at x=32 (32 + 608 = 640, exactly in bounds ✅). Reserving all 64 px of headroom
would buy ±32 px, and **the comfort ceiling binds long before the width ceiling does.**

⚠ **(U)** We do not know what the stock near/mid/far setting does mechanically. If FAR is itself a
horizontal offset, our disparity stacks on it and could exceed divergence.

### 3.4 Architecture

- **Depth is a per-layer property applied at the transport boundary.** The compositor renders in
  plain 608×416 coordinates; the emitter applies ±d when building the box pair.
- **A stereo rect makes the two lenses' shadows genuinely diverge** ⇒ the host-side shadow model is
  **per-lens**. Stereo is a property of a whole composited element, always painted as a unit;
  never partially update a stereo region with a non-stereo delta.
- ✅ Content parks far permanently, so a popup appearing does **not** force the content to repaint
  its stereo boxes. Only the popup rect is new.
- **IPD / convergence calibration is a first-class WM setting** — misaligned images are what
  actually cause eye strain, so this is a comfort win independent of any effect. Ship a ramp
  (0/4/8/12/16, hold to accept).
- Horizontal offsets only, never vertical (the wire format would allow vertical — the guard is
  ours). Never different *content* per eye.

---

## 4. Surfaces

### 4.1 The top bar

```
     0                                        384    422   460   498  560  640
   ┌──────────────────────────────────────────┬───────────────────────┬──────┐
 0 │ ▣ TERMINAL · build #482 · 4m12           │   G▓▓▓▒   P▓▓▒▒       │12:59 │ 32
   │                   384                    │          176          │  80  │
32 ├──── window position · attention marks ───────────────────────────────────┤ 2
```

**Ink budget ≤ 8 %.** This bar is permanent, so it is where ink discipline matters most.

#### 🔴 The ribbon is retired (2026-08-18)

Adam: *"forget the ribbon entirely, the switcher makes it redundant. Main already acts as a full
launcher/list while switcher acts as an ALT+TAB, we don't need to waste space putting a second
crappier switcher in the title bar."*

He is right, and the comparison renders make the case: a 3-cell ribbon showed **3** windows against
the switcher's whole wheel and Main's **11 rows**, and at the Main level it was a straight duplicate
of the list beneath it. It was a third window-list competing with two better ones.

**Nothing is lost, because its three jobs all had better homes:**

| the ribbon did | now |
|---|---|
| name the current window | the **Title**, merged (below) |
| show position in the window set | the **divider**, which already carried it |
| flag windows wanting attention | the **divider** too — see below |
| switching | the **switcher** (§4.3) and **Main** (§4.2), which both did it better |

⇒ **240 px of permanent chrome recovered**, and it went to the battery readout, which needed it.

#### Title — window and document, merged

With no ribbon there is no redundancy left to avoid, so the two halves rejoin into one line:
**`▣ WINDOW · document`** — icon, window name at head level, context dimmer. 296 px. Overflow takes
the `▸` continuation mark, never a marquee (§2.4 rule 3).

🔴 **The Title contract (Adam, 2026-09-01): the Title is SHORT BY DESIGN — its content must
never be long enough to cut.** The context half is terse and bounded; unbounded or variable
content (message bodies, senders, anything beyond a document name's head) belongs to the content
area or the notification surface, never to chrome — **which is exactly why notifications are a
popup here instead of G2CC's title-bar cram.** The `▸` fit guard stays as the tripwire, but a
Title that takes the mark is a window defect to fix at the source, not a feature. Naturally
unbounded names (a long book title) are the tolerated residue: rare, and the full name is always
one step away inside the window itself.

#### 🔑 The divider absorbs the ribbon's remaining job

The `640×2` rule is lit regardless, so it carries both axes of "where am I":

- a **dim track** the full width, one slot per window;
- a **bright segment** at your position;
- **medium ticks** at any window wanting attention.

Zero extra pixels, zero extra bytes — it is inside a rect that ships anyway. **The ribbon's
information survives; only its 240 px did not.**

#### 🔑 Battery — a plain fill bar, deliberately NOT segmented

`G▓▓▓▓▒  P▓▓▓▒▒` — glasses, phone. *(The **R** ring cell was removed 2026-08-31: ring battery
has no open-source source — the glasses can't relay it and the ring's own link needs protocol RE,
`CLAIMS.md`. A blank cell for an unreachable value is dead chrome. Faceclaw shows the same two,
Phone + G2, for the same reason.)* **30 px body** plus nub, **58 px pitch in a 176 px
cell** (`x 384–560`), with the letter **capitalised and set larger** (14 px bold) so the device is
identified at a glance. Halving the body from 60 px returned **88 px to the Title, which is now
384 px** — wide enough for `▣ WINDOW · document` without the continuation mark in most cases.

Adam asked for granularity: *"I want to visibly see the difference between 45% and 50%, if
possible."* **Three encodings were rendered and compared** (`design/shots/battery-granularity.png`):

| encoding | verdict |
|---|---|
| 10 segments + brightness on the partial | 40 % and 50 % nearly identical; the brightness cue is too subtle |
| 16 segments + brightness on the partial | better, still needs attention to read |
| ✅ **20 segments, pure length** | **clearly monotonic — and at this width the segments merge into a solid bar anyway** |

🔑 **So the answer is not to segment at all.** Segmentation solves a *small-width* problem, and
removing the ribbon removed the width constraint. A plain fill gives **~0.9 px per percent**, so 45 %
and 50 % differ by ~2.8 px — read instantly as length, which the eye does far better than
brightness. And it is the cheapest option available: **one run per row instead of twenty.** The
legible choice is again the cheap one.

**Brightness still tracks charge** (`level = clamp(2 + 8×pct/100, 2, 10)`), and **≤ 20 % pulses**
`15 · 8 · 3 · 8 · 15 · 8 · 3` — attention carried by change, not by steady level (§4.1 battery
rules, settable off / on / escalating).

⚠ **Halving the width costs the granularity target, and that is a deliberate trade.** Adam,
2026-08-18: *"I'd make the battery icons half that width though, that's a bit much."* At a 30 px body
the fill area is 24 px:

| body | px per % | 45 % vs 50 % |
|---|---|---|
| 60 px | 0.54 | **2.7 px** — clearly visible |
| **30 px** (adopted) | 0.24 | **1.2 px** — at the edge of readable |

⇒ The 5 % discrimination he originally asked for is **no longer reliable from the bar alone.** That
is fine because he named the fallback himself: **exact percentages live in the Info/Stats surface.**
The bar is for glancing — is it fine, getting low, or urgent — and the number is for checking. Worth
recording so nobody later "fixes" the bar by widening it again without knowing why it is narrow.

#### Clock

`12:59` at full weight with `PM` small and dim. Hierarchy as size and brightness rather than as
space, which is what makes 80 px enough.

### 4.2 Main — the dashboard, and the WM's settings

The rest state, reached by double-tap from any window root. With the ribbon retired (§4.1),
Main and the switcher are the *only* two window lists in the shell.

#### 🔑 The organizing principle: on this display, transparency IS ink coverage

The G2 is an additive see-through micro-LED panel. **Level 0 does not emit — it is literally
transparent.** So "enough transparency to not be distracting" is not an alpha channel and not a
compositing mode: it is simply *how few pixels you light*.

That collapses three separate goals into one number:

| goal | mechanism |
|---|---|
| see through it | fewer lit pixels |
| less distracting at FAR | fewer lit pixels, lower levels |
| cheaper on the wire | fewer lit pixels ⇒ longer level-0 RLE runs |
| more legible against a real-world background | high contrast between a sparse mark and true black |

🔑 **The prettiest Main, the least distracting Main, and the cheapest Main are the same Main.** No
tradeoff to manage. This is the single most G2-specific fact in the whole shell design and it
should drive every decision below.

⇒ **Adopt an INK BUDGET as a first-class, lintable design metric** — the fraction of pixels above
level 0. The layout linter (§9.2b) can compute it from a rendered surface and fail the build when a
surface exceeds its budget. Starting targets, to calibrate on the first real render:

| surface | budget | **measured** |
|---|---|---|
| Main, active | ≤ 15% | **9.0%** ✅ *(8.5% before the band-height lens icon, 2026-09-01)* |
| Main, resting | ≤ 5% | **4.8%** ✅ *(4.4% before)* |
| notification box | ≤ 25% | **5.2%** ✅ |
| emergency banner | ≤ 25% | **6.5%** ✅ |
| switcher | ≤ 25% | **5.3%** ✅ |
| silent mode | ≤ 2% | **0.5%** ✅ *(digital readout, 2026-08-31; was 0.2% analog)* |
| window, list mode | ≤ 15% | **8.3%** ✅ |
| window, document mode | ≤ 25% | **7.7%** ✅ |

✅ **Measured 2026-08-18 from real renders** — `design/render_shots.py` composes each screen at
true 1× 640×480 in the locked faces, quantises to 4 bpp, and runs the firmware's own RLE through
deflate level 6.

🔑 **`tools/lint.py` rule BUD007 compares this table against the actual renders**, so it cannot
drift silently. It already caught the table claiming 5.1 % for Main-resting when the render said
4.6 % — and 5.1 % would have been *over budget*. Regenerate the shots and re-run the gate after any
design change. **Every budget is met with wide margin**, which says the budgets were
set conservatively rather than that the design is sparse by luck. Shots in `design/shots/`.

⚠ **Those are full-screen KEYFRAME costs, not per-interaction deltas.** Cold entry to a screen:
**178 B (silent) → 8,487 B (a dense mail list).** Compression ratios land at **0.008–0.056×**, so the
0.03–0.05× band used throughout §8 was sound but slightly optimistic — the measured full-screen
keyframe sits at the **pessimistic end** of the modelled range. Do not plan against the low end.

**Design consequences, all in the same direction:** no filled panels, no boxes, no backgrounds —
background pixels are pure waste on an additive display. Structure comes from *spacing and
brightness*, not from rules and frames. Type does the work.

#### Geometry — a fixed cursor band with the list panning through it

`overview.md` §12 already settled **fixed cursor + panning list**. Applied here:

```
        ┌──────────────────────────────────────────────────────────┐ y 34
        │                                                          │  16 pad
        │  CALENDAR      Standup 09:30 · in 22m                    │  32
        │  MUSIC         ▶ Bowie — Blackstar                       │  32
        │  TIMERS        2 pending · next 14m                      │  32
        │  MAIL          3 unread · Jane Doe + 2                   │  32
        │  SMS           Mom · "on my way"                       ▸ │  32
        ├──────────────────────────────────────────────────────────┤ y 210
        │  TERMINAL                              build #482 · 4m12 │
        │  12 tests passed, 0 failed · linking damage-wm           │  64  ← LENS
        ├──────────────────────────────────────────────────────────┤ y 274
        │  READER        Dune · p.412/604 · 68%                    │  32
        │  FILES         ~/damagewm                                │  32
        │  NOTICES       4 unread                                  │  32
        │  SCOUT         idle                                      │  32
        │  ⚙ SETTINGS    brightness · size · depth · presence      │  32
        │                                                          │  16 pad
        └──────────────────────────────────────────────────────────┘ y 450
```

`16 + 5×32 + 64 + 5×32 + 16 = 416` ✅ · every y and height even ✅

🔑 **The lens band is at `y 210, h 64` — its centre is y = 242, the exact centre of the content
area.** The content area centres on x = 320, the switcher wheel centres on (320, 242), and this band
centres on 242 too. **Everything focal in the WM sits on one axis.** That is what will make it read
as designed rather than assembled.

**The list pans; the lens does not move.** So scrolling is `mode 8 { mode 9 shift + mode 3 fill of
the newly exposed row + mode 3 repaint of the lens }` — the shift moves every row on-device for
zero pixel bytes, and the highlight never has to be erased and redrawn because it never moves.

**The list wraps**, and **Settings is its last entry** — so from the top row, **one scroll up lands
on Settings**. Out of the way and one gesture away at the same time, with no pinned row eating
space.

#### Contents — and no truncation, honestly

**Three columns: icon, window name (small caps, dim), live summary (brighter).** Numerics
right-align into a column so the eye can scan them.

**In the lens, the focused window shows its full summary, wrapped over two lines** — 64 px of band
is exactly two 32 px lines.

⚠ **How a long summary is handled, because "no truncation" is a hard rule here.** Unfocused rows
show one line and, when there is more, a **right-edge continuation mark `▸`** — never an ellipsis,
never a silent cut. The mark says *"there is more here, focus me"*, which is the opposite of silent
mangling: the information is always **reachable** (scroll to it and the lens reveals it in full),
and its existence is always **advertised**. Wrapping every row to full length instead would make
the list unscannable and turn a glanceable dashboard into a wall.

🟡 If two lines still is not enough for some window, the lens can marquee its second line
horizontally — mode 9 makes that nearly free (§2.4 rule 3). Held in reserve; not the default.

#### Depth — one language, three places

- **plane −1** — the list at rest, with the content
- **plane 0** — the **lens band**, lifted forward
- **plane +1** — emergency only (§4.5)

The focused row literally comes off the page. Cost: **+4 bytes**, one extra stereo box pair on the
lens rect.

🔑 **"Focused comes forward" is now the WM's system-wide depth language** — the notification taking
focus (§4.5), the switcher wheel's centre item (§4.3), and Main's lens all use it. One idea, three
surfaces. ⚠ Brightness remains the primary cue everywhere, since a user calibrated to `d = 0` sees
no depth at all.

#### Resting state — the answer to "not horribly distracting"

Main has two appearances:

| state | what shows |
|---|---|
| **active** — you are interacting | full dashboard, full ramp, ≤ 15 % ink |
| **resting** — input has gone quiet | the lens row and the bars only; other rows drop to level 2–3 or away entirely. ≤ 5 % ink |

This is the direct answer to *"enough transparency to not be horribly distracting if I need to look
through it"* — at work you glance, then look **through** the glasses at real work, and a full-
brightness dashboard sitting there is exactly the distraction to avoid.

✅ **And the resting state is CHEAPER to transmit than the active one**, because less ink means
longer level-0 runs. The comfortable choice and the fast choice are again the same choice.

The transition uses the same "input has gone quiet" signal as live preview (§4.3) and the
notification focus grace (§4.5) — scheduler priority, not a timer.

#### ⚙ Settings — inside Main, as Adam asked

The last list entry opens the WM's global settings. **Organized as DIRECTORIES since 2026-08-31**
(Adam, same day, after inline headers proved too slow: *"the categories should be DIRECTORIES …
it takes way way too long to scroll through 50 different things"*): the top level lists the
categories — **Global** (the table below + the host's display-target rows), then **one per app**
contributing rows through `DamageWindow.appSettings()` (Reader: Scroll step · Scroll accel ·
Size) — tap descends into a category, double-tap climbs out, exactly the §4.6 level pattern.

🔑 **The global/override pattern** (Adam, 2026-08-31): any per-app shadow of a global setting
offers **"global" as its DEFAULT option** plus the global setting's own values; a non-global
choice overrides the global for that app only. Reader's Size row is the first instance
(global / 288 / 352 / 416 / 480, applied on focus per §2's per-app height).

| setting | notes |
|---|---|
| **Brightness** | the panel's own, sid 0x09 — distinct from our 16-level content ramp |
| **Size** | **four heights — 288 / 352 / 416 / 480 — always TOP-aligned** (revised 2026-08-31, Adam: *"I can always see the top, it's the lower areas that get cut off if I wear the glasses too high"* — so the vertical-position setting was useless and is retired). Per-app shadows follow the global/override pattern below |
| **Depth** | the disparity calibration ramp, 0/4/8/12/16 (§3.4) |
| **Presence** | the resting-state ink floor — one knob for "how much is it in my way" |
| **Font** | system face and size; per-window content override (§Type — defaults are locked, this is for tuning) |
| **Head tracking** | default OFF (§7.1) |
| **Long-press** | **off** (default — §1.2 revised 2026-08-30: a bare long-press is a no-op; the §1.3 chord opens the switcher) / switcher |
| **Notification sources** | §4.5 |
| **Battery alert** | off / on / escalating — the ≤ 20 % pulse (§4.1) |
| **Profiler / diagnostics** | status-bar profiler, mode-7 overlay (§9.2) |

🔑 **Every appearance setting previews LIVE as you scroll its value.** Brightness changes the panel
as you move; depth changes the disparity as you move; size re-lays out as you move. **This is the
only sane way to set a perceptual value on a HUD** — you cannot pick a comfortable disparity from a
number, you pick it by looking. It reuses the live-preview machinery from §4.3 rather than adding
any.

⚠ Size changes re-lay out the whole shell ⇒ a keyframe (~1.1 s). Fine for a setting, but it is the
one setting that cannot preview per notch; it previews on settle.

#### Cost

| | bytes | ms |
|---|---|---|
| **scroll one notch** (mode 9 shift + 2 fills) | 880–1,460 B | **256–309** |
| full Main paint (entry) | 3.8–6.3 KB | 521–751 |
| resting ⇄ active transition | well under the row costs | ~200 |

Scrolling stays under the 1,936 B the ack floor buys, so it is ack-bound — the list pans at the
protocol floor, not the bandwidth floor.

### 4.3 Switcher — the wheel

🔴 **Bespoke, not a generic overlay.** Adam, 2026-08-17: the switcher and the notification surface
are **designed independently** — their own geometry, motion, content and input handling. They share
only the compositor's layer/z-order plumbing, because there is exactly one compositor and one
damage tracker. There is deliberately **no generic "overlay" abstraction** pulling them toward
looking and behaving the same.

#### Form: a vertical drum, seen head-on

Not a flat list. A wheel *coming at you* — the centre window faces you square, its neighbours curve
away above and below, foreshortened and fading into the panel edge. Scrolling spins the drum to the
next detent.

```
        ┌────────────────────────┐  y 154
        │   ▁▁▁▁  Calendar  ▁▁▁▁ │        above  · half height · dim     44
        ├────────────────────────┤  y 198
        │        ┌──────┐        │
        │        │ ICON │        │        CENTRE · full size · full      88
        │        └──────┘        │        brightness · plane 0
        │        Terminal        │
        ├────────────────────────┤  y 286
        │   ▔▔▔▔   Mail    ▔▔▔▔  │        below  · half height · dim     44
        └────────────────────────┘  y 330
         x 200        240        x 440
```

| | value |
|---|---|
| panel | `x 200, y 154, w 240, h 176` — centred on x=320 and on the content band's centre y=242 |
| bands | above 44 · **centre 88** · below 44 |
| centre item | 64×64 icon + 4 gap + 20 title |
| grid | x/w ÷4 ✅ · y/h ÷2 ✅ |

**The drum model.** Slots sit on a cylinder rotating about a horizontal axis, detents 60° apart:
vertical scale = `cos θ`, screen offset ∝ `sin θ`, brightness falls with `cos θ`. At **θ = ±60°,
`cos 60° = 0.5` exactly** — so the neighbours land at precisely half height, which is Adam's *"half
the above-window and half the below-window"* falling straight out of the geometry rather than being
fudged. Their outer edges fade to background, so they read as curving out of view.

#### 🔑 Why a *vertical* drum is the cheap one

A cylinder rotating about a horizontal axis foreshortens **vertically only** — horizontal extent is
constant, so every scanline keeps its run structure. **RLE encodes horizontal runs.** A horizontal
carousel would resample across the run direction and shred compression; this one is compression-
friendly by construction. The fancy choice is also the cheap choice.

#### The cost, and the condition it depends on

Panel = 42,240 px = **21,120 B raw**. Every spin frame repaints the whole panel — as **one rect**
with depth off, or as **three** (one per band) once per-band depth is on, since a single rect carries
a single disparity. Same pixels either way; the split adds ~30 B of framing and 2 fids.

| compression | per frame | 4-frame spin |
|---|---|---|
| flat-shaded, quantised fade | ~420 B / 38 ms | ~1.7 KB / ~154 ms |
| **sparse panel (expected)** | **~1,060 B / 96 ms** | **~4.2 KB / ~384 ms** |
| dense AA, smooth gradients | ~2,530 B / 230 ms ✗ | ~10 KB / ~920 ms ✗ |

The ack floor buys **1,936 B of transfer** (176 ms × 11 KB/s). Stay under that and the pipeline is
ack-bound, so with three flushes in flight a frame lands every ~59 ms and a 4-frame spin is
**~240 ms** — snappy. Go over it and the wheel becomes bandwidth-bound and visibly slow.

🔴 **Therefore the fade MUST be quantised, not smooth.** Four discrete brightness tiers (centre
full, neighbours ~50 %, their outer edges ~25 %, background 0), not a per-pixel ramp. A smooth
gradient turns every row into unique values and defeats RLE — it is the difference between the
1,060 B row and the 2,530 B row. At 16 levels a smooth fade would band visibly anyway, so tiers
look *more* deliberate, not less.

Same discipline on the icons: solid shapes, thick strokes, few levels (§2.4 rule 9), pure black
background so each row is one long run.

#### Motion

- **4 frames per detent, ease-out** — decelerating angular steps, "quickly and then stop at each."
- 🔑 **The quantization grid constrains damage *rects*, not their contents.** The panel rect is
  fixed at `200,154,240,176` for every frame, so the drum can rotate with sub-pixel smoothness
  inside it. **The wheel is the one place in the WM where motion is free of the 4 px/2 px grid** —
  everything else scrolls coarsely, this glides.
- **Retargeting, not queueing** (§6.3). A second notch mid-spin re-aims the drum at the new target,
  so scrolling fast through five windows plays **one continuous rotation**, not five spins. Without
  this the wheel would be ~250 ms × N and feel terrible.
- The panel rect never moves ⇒ no alignment work and no rect growth. **1 fid per frame flat, 3 with
  per-band depth** — a 4-frame spin spends 4 or 12 fids, both inside the ≤5-rects-per-batch budget
  (§8.2).

#### Depth — the flagship use of stereo

The drum is where perspective and disparity can agree, which is exactly when stereo reads well
instead of reading as strain.

- **Centre band at plane 0; the two neighbour bands at −1**, matching the content depth behind
  them — so the drum visibly curves back *into* the scene.
- 🔴 **The wheel owns the depth story while open (corrected 2026-08-31, on glass).** The window
  behind it is a preview, so the shell must NOT keep lifting that window's lens band to plane 0 —
  the band spans the full content width and runs through the wheel's rows, so it dragged the
  entire width forward, background included (Adam's report). While the wheel is open, the only
  plane-0 region is the wheel's centre band.
- **The frame is four horizontal rules (2026-08-31):** a fixed one-tier-dimmer pair at the panel's
  top and bottom edges framing the upper and lower slots (dim edges support the curving-away
  read), and the brighter sliding pair bracketing the centre. Without the outer pair the drum read
  as one highlighted row, not a wheel. Horizontal rules only — one run each; a vertical border
  would split every panel row per frame (§4.5's cost).
- Costs 3 rects instead of 1 (+4 B each for the stereo box pair, ~30 B of extra framing). Well
  inside budget, negligible bytes, and the bands are contiguous so the pixel count is unchanged.
- 🟡 **Stretch:** interpolate an item's disparity as it rotates forward. The ladder is quantised to
  4 px and content parks around +12, giving **12 → 8 → 4 → 0 across exactly the four spin frames** —
  the depth steps and the animation steps line up on their own. Default is the simpler fixed-band
  version; this is a polish pass once the wheel is real.

#### Contents and behaviour

Rows are **windows only**. Each carries a real downscaled thumbnail of that window's composed frame
(the PC already has it, so this costs transmission only — free once the texture cache lands), its
name, and a **dirty tick** when it has new content (§4.1).

Long titles marquee inside the 240 px band; they are never truncated (§2.4 rule 3).

#### 🔑 Live preview — the wheel drives the window behind it

**Adam, 2026-08-17.** Scrolling the wheel repaints the window *behind* the panel to the selected
one, so the wheel previews rather than describes. Tapping then merely closes the panel — the window
is already there.

**The panel covers 42,240 of the content area's 252,928 px — 16.7%. 83 % of the target window is
visible around it**, so this is a real preview, not a peephole.

| | bytes | ms |
|---|---|---|
| settle on a window (content + top bar + panel frame) | 5.2–7.9 KB | **645–893** |
| **commit (tap)** — only the panel region still shows the old window | 0.6–1.1 KB | **234–272** |
| **cancel (long-press)** — restore the original window everywhere | 3.8–6.3 KB | **521–751** |

🔑 **This inverts the economics in the right direction: committing becomes the cheap path and
cancelling becomes the expensive one.** The common action is fast; the rare one pays.

**Three rules make it work.**

1. 🔴 **Preview is a RENDER, never an ACTIVATION.** The WM composes the target window's stored
   state and paints it; it runs **no lifecycle hooks**. Scrolling past five windows must not start
   five pollers, open five connections, or — G2CC's hard-won lesson — leak the mic to a window you
   never entered. Only **tap** commits and activates.
   ✅ **This costs no new machinery**, because full persistence (§9.1) already requires every window
   to hold a restorable composed state. The preview just paints what persistence already stores.
2. 🔴 **Preview is the lowest-priority flush.** At 645–893 ms a settle is far too slow to fire on
   every notch, and perfectly fine to fire once scrolling stops. It is scheduled *behind* the wheel
   spin and behind any pending input, so while you keep scrolling it never gets a slot, and the
   moment you pause it goes out. **That is scheduler priority, not a debounce timer** — it obeys
   NO TIMEOUTS by construction rather than by exception.
3. **Speculate while the wheel is open.** The switcher is idle between notches, so pre-render and
   pre-deflate the adjacent windows' previews (§5.5). The settle then costs transmission only.

**Felt behaviour:** the spin is immediate (~240 ms, §above), the preview settles in behind it a
beat later. Scroll fast and you get pure wheel motion with no preview churn; stop, and the window
resolves behind the drum.

**Details that follow:**

- The **top bar previews too** — its Title names the selected window, which reinforces where you
  are. It **snaps rather than animating** while the switcher is open: the wheel is already carrying
  the motion, and two competing animations would compete for both attention and bytes.
- The content-behind repaint goes as **one rect** covering the whole content area with the panel
  redrawn over it in the same mode-8 batch (sub-messages apply to the shadow in order, so the later
  panel wins). That wastes the 17 % under the panel but beats four frame-strips, which would cost
  three extra rects and lose cross-rect zlib sharing. The cost oracle (§9.2b) makes the final call.
- 🟡 **Optional, if it proves janky:** fall back to previewing only the *thumbnail* in the wheel and
  leaving the window behind untouched. That is the design as originally specified and costs nothing
  to keep as a setting.

⚠ **System state and notifications are no longer here.** Removing the info popup orphaned them —
notifications get their own bespoke surface; deeper system detail is a window, i.e. app-layer work.
Live telemetry stays in the status bar (§4.4). Tracked as open item #5.

**Notifications while the wheel is open (decided 2026-08-25).** A notice that arrives while the
switcher is open waits behind the wheel — queued, unshown — and unfurls with its normal grace when
the wheel closes; a box already on screen when the wheel opens goes back to the queue unread and
returns after. The wheel owns the screen; nothing repaints over it.

### 4.4 Status bar

| cell | shows |
|---|---|
| Current operation | the verb — rendering, fetching, flushing |
| Status | result / state — ok, warn, error — plus the **input echo** (§9.2) |
| Throughput ↑ · ack ms | uplink B/s and image ack latency. ⚠ downlink is *not* shown: the glasses send only acks and events, so it would read ~0 forever |
| **Compass ✱** | 8 sectors (N NE E SE S SW W NW) — **hysteresis required**, see §7.2 |
| Link signal | 🔴 **TWO links, one 120 px cell.** See below |

#### 🔴 There are two links and they fail completely differently

The status bar originally showed one. With §10's topology there are always two:

| link | what its failure means |
|---|---|
| **transport ↔ glasses (BLE)** | nothing works; the screen may be frozen or already lost to stock LVGL |
| **shell ↔ content host** (Tailscale / WSS) | **the shell works fully**; some windows are stale, a few are unavailable (§10.5) |

Collapsing those into one indicator makes "re-seat my glasses" indistinguishable from "wait for
signal," and the correct response differs. So the 120 px cell carries both: **four filled BLE bars
(~40 px) on the right, host state on the left**, with the dBm numeral appearing only when the radio
link is poor (≤ −75).

**Host state stays near the ink floor while healthy** — a single dim mark — and spends ink only when
the host is gone, exactly as the battery does (§4.1). And it reports **duration, not just state**:
G2CC's `ConnectionManager` already tracks `offlineSince`, so `PC 4m` is free and far more actionable
than `offline`.

⚠ *Which* BLE link, and whether connected RSSI is readable at all, remain **(U)** — and the answer
now differs per platform (§10.7).

**Per-window capability lives elsewhere and costs nothing new:** Main's existing summary line already
has a slot per window, so a window that is unavailable offline simply says so there (§10.5).

---

### 4.5 Notifications

🔴 **Bespoke, and deliberately not the switcher.** Different geometry, different motion, different
gestures. They share only the compositor's layer plumbing (§4.3).

#### Form

A box over the content that **takes focus**, sized to its content up to a cap, scrollable, and
**staying until dismissed**.

| | value |
|---|---|
| max | `x 196, y 190, w 248, h 104` — centred on x=320 and on the content centre y=242 |
| min | `w 248, h 56` — source line + one body line (see the layout below) |
| growth | in whole lines; line height is already ×2 (§2.4 rule 7) so the box quantizes for free |
| silent mode | `x 220, y 214, w 200, h 56` |
| depth | plane 0, over content at −1 (§3.1) |

⚠ **248, not 250** — `250 % 4 = 2` and box widths must be ×4 or the delta is silently rejected.

#### Visual design

##### 🔑 The box is a hole, not a card

The panel is additive: **level 0 emits nothing, so it is literally see-through.** There is no such
thing as an opaque dark panel — Faceclaw's colour-key note confirms it from the other side, where
"1 = intentional opaque black" is *"identical to 0 after 4bpp quantisation."* You cannot draw a
dark card. You can only add light.

So the notification does not *cover* content with a panel. **It clears its region to transparency
and floats marks in the gap** — the content beneath vanishes and the real world becomes the
notification's background.

That is unique to this display, it is the **least ink of any option**, and it is the most striking:
a small bright mark on true black, punched into a busy screen, is far more arresting than a large
dim panel would be. ⇒ **Assertive here means contrast, motion and depth — never area or fill.**

##### Rules, not borders — and the reason is RLE

RLE runs **horizontally**. A horizontal rule is *one long run per row*; a vertical accent bar splits
every single row into three tokens. For a 104-row box that is ~200 extra tokens for a decoration.

⇒ **Bracket the box with two horizontal rules.** Cheapest possible structure, clean editorial
treatment, and it is already the shell's motif — the top and bottom dividers are rules that carry
meaning (§4.1, §4.6).

##### Layout

```
┌─ 248 ──────────────────────────────┐
│ SMS · MOM            +2      14:32 │  16   source · queue · time
├────────────────────────────────────┤   2   rule
│                                    │   6
│ on my way, should be there in      │  24   body
│ about twenty minutes               │  24
│                                    │   6
├───────▰▰▰▰▰▰▰──────────────────────┤   2   rule = scroll position
└────────────────────────────────────┘
```

`16 + 2 + 6 + n×24 + 6 + 2` → **56 / 80 / 104** for 1 / 2 / 3 body lines. Every value even ✅.
~27 characters per line at 248 px.

| element | level | why |
|---|---|---|
| cleared background | **0** | transparent — the world shows through |
| rules, timestamp, source category | 3 | structure and metadata, low information |
| body text | 8 | the reading level |
| sender / subject | 12 | the thing the eye scans for |
| urgency marker | 15 | reserved; spent only when something is wrong |

Hierarchy is expressed as **brightness and spacing, never as boxes or fills** — the same discipline
as Main (§4.2) and the top bar (§4.1). The right-aligned timestamp echoes Main's right-aligned
numerics.

**The bottom rule carries scroll position** within the message; the queue depth rides in the source
line as `+2`. One meaning per element — both rules are already lit, so both are free information
(§4.6).

##### 🔑 It unfurls from a rule, and the animation is free

No fades — motion must be translation or reveal (§6.2). So the box does **not** slide in across the
content (that would repaint both its old and new position every frame, ~2× the box per frame).
Instead it **grows downward from a single 2 px rule at its final position.**

Damage per frame is only the newly revealed strip:

| | bytes |
|---|---|
| 4 unfurl frames × 26 px strip | ~97–161 B each |
| **total unfurl** | **≤ 645 B** |
| one static paint of the same box | 387–645 B |

✅ **The arrival animation costs the same as painting it once.** And it is native to the design —
the shell's signature is meaningful horizontal rules, so a notification unfurling *out of* a rule is
the language speaking rather than an effect applied. Ease-out over 3–4 frames; furl in reverse to
dismiss, then restore the content beneath.

##### The focus transition is the notification stepping toward you

The grace period (§4.5) needs a visible before/after. Depth supplies it: the box unfurls at **plane
−1**, with the content, and **steps to plane 0** when it takes focus. It literally moves toward you
at the moment it becomes actionable — the most physically legible "now I am listening" signal
available, for **+4 bytes**. Brightness steps with it (dim → full) and remains the primary cue, since
a user calibrated to `d = 0` sees no depth at all.

##### Silent mode

`200×56` — the same language with one body line and no queue indicator. Nothing animates beyond the
unfurl; the box is gone in 5 s and a fussy countdown is not worth the round trips.
🟡 *Optional:* the bottom rule as a depleting dwell track. Costs ~3 extra flushes for decoration —
off by default, and the one place in the shell where flushes would be spent on ornament.

##### Emergency alerts

Their own treatment, and the shape rule below is what makes it cheap: a **full-content-width band**
(608 px) with **doubled rules** top and bottom, at **plane +1**.

Wide, short and heavily bracketed reads instantly as *not a normal notification*, and at
**948–1,581 B** it is still inside the 1,936 B ack floor — one round trip, same as everything else.
Level 15 is finally spent here.

##### 🔑 The shape heuristic, which generalises past this surface

RLE token count scales with the number of **rows**; run length grows with **width**. So for a given
pixel count:

> **Wide-and-short compresses better than tall-and-narrow.**

`608×42` and `248×104` are the same area, but the wide one has 42 rows of long runs against 104 rows
of short ones. **Width is the cheap axis on this display.** Prefer banners to columns, rows to
sidebars, and horizontal structure to vertical — which is, conveniently, also the better shape for a
glanceable HUD.

#### Gestures

| gesture | action |
|---|---|
| **tap** | open it in its source app (Mail, SMS, …) |
| **double-tap** | dismiss **and mark read** |
| **long-press** | ⚪ nothing by default (§1.2, 2026-08-30); with "Long-press · switcher" set: dismiss **without** marking read |
| **scroll** | scroll the body (it holds focus, §1.4) |

✅ **This assignment is accident-optimal, and that matters more here than anywhere.** The most
accident-prone gesture on this hardware is the **ring long-press** — it is the entire documented
gloves failure chain and the founding problem of this project (`overview.md` §6). It is assigned to
the **most recoverable outcome**: nothing navigates, nothing is lost, the notification stays unread
and waiting. Tap is the consequential one and is still non-destructive and undoable by double-tap.
That is §1.7's misfire-tolerance rule satisfied without having to be applied.
**Revised 2026-08-30:** with long-press defaulting to a no-op (§1.2), even that recoverable
outcome no longer fires by accident — a stray long-press leaves the box exactly as it was. "Get
it off my screen unread" is now the §1.3 chord: opening the wheel parks the box back in the
queue UNREAD (the decision-6 mechanics), and it returns when the wheel closes.

⚠ **One real collision: while a notification holds focus, long-press means "dismiss unread", not
"open the switcher."** Since notifications stay until dismissed, an ignored notification blocks
ALT+TAB until it is cleared. Modal focus capturing a gesture is normal, and dismiss-then-switch is
two gestures — but it is a genuine cost of "stays until dismissed", so it is recorded rather than
buried. **2026-08-30: the collision is gone by default** — a bare long-press means nothing in
both places, and the chord opens the wheel over a focused box (which steps aside unread).

#### 🔑 Focus grace period

**Adam, 2026-08-18.** The box **appears immediately** but does **not take focus for 2–3 s**, so a
gesture aimed at the underlying window still lands there: *"to avoid accidentally tapping a
notification the moment it appears when my intent was to select an action in the underlying
window."*

✅ **Well-defined, because input here is a CURSOR model, not a pointer model.** The box covering
something on screen does not change what a tap hits — the underlying window's cursor is state, not
a screen position. So "route the gesture to the window underneath" is exact, not approximate.

✅ **Not a NO TIMEOUTS violation**, by the same reasoning as the 5 s silent dismissal above: this is
a scheduled UI state transition, not a time-bounded execution wrapper. Nothing is abandoned, no
work is cancelled, no failure is hidden.

**Rules:**

1. **The grace restarts on every input.** Focus transfers at the next lull, not on a fixed clock —
   so an interaction that is actively in progress is *never* interrupted. 2–3 s is the floor, not
   the whole rule. This is the same "settle when the input stream goes quiet" signal the live
   preview uses (§4.3 rule 2), and it should share that machinery.
2. 🔴 **The grace does NOT apply to the next item in a queue you are actively clearing.** If you
   double-tap to dismiss and the next box appears, a fixed grace would send your following gestures
   to the window underneath while you are trying to clear the backlog. The grace protects against
   *unexpected* focus theft; a queue you are working through is expected.
3. **Emergency alerts get the same grace, with no special case** (Adam, 2026-08-18 — correcting an
   earlier draft that had them take focus immediately and *swallow* input instead. That was wrong:
   swallowing ate gestures aimed at the underlying window, which is precisely the problem the grace
   period exists to fix, so the exception violated the rule it was nested inside). One grace rule,
   everything.
4. Moot in silent mode — notifications there never take focus at all.

##### The focus indicator is free, and it should be depth

The box must look different before and after it takes focus, or the delay just inverts the bug it
was added to prevent.

🔑 **Arrive at plane −1 (with the content), step forward to plane 0 on taking focus.** An
**emergency alert steps to plane +1 instead** — same mechanism, bigger jump, so the depth ladder
itself encodes urgency (§3.1's crossed-disparity slot, deliberately the uncomfortable direction for
the one case where discomfort is the point). The
notification literally *comes forward* when it becomes actionable — pre-attentive, needs no reading,
consistent with the layer rule that popups come forward (§3.1), and it costs **+4 bytes** for the
stereo box pair. It is also a small motion, which is what §6 asks for everywhere.

⚠ **Depth is the enhancement, not the primary cue** — if disparity is calibrated to `d = 0` the
depth signal vanishes entirely. **Brightness is the primary indicator**: dim on arrival, full on
focus, in quantised steps (§8.5). One box repaint, 390–650 B — one round trip.

#### Silent mode

Smaller box, **auto-dismisses after 5 s**, and **stays unread**.

✅ **The 5 s dismissal is NOT a NO TIMEOUTS violation, and the distinction is worth stating so the
rule is not weakened by accident.** The rule targets time-bounded *execution* — `wait_for`,
`timeout=`, wrappers that abandon work and hide failure. A scheduled UI transition abandons
nothing, cancels nothing, and hides no error; it is the same class as the clock's minute tick and
the 5 s idle chrome tick (§8.3). G2CC did exactly this: *"while BLANKED, every priority pops for
10 s then auto-re-blanks."*

⚠ **Deliberate divergence from G2CC:** G2CC *"marked seen at display"* for those pops. Damage keeps
them **unread** — silent mode means you are not reading, so it must not count as having read.

In silent mode notifications are **display-only**. All input except double-tap is swallowed (§1.5),
so acting on one means leaving silent mode and going to the app — and since it stayed unread,
nothing is lost.

#### Read state

**Activating an app auto-marks that app's unread notifications read.**

🔑 **This is the second consumer of the render-vs-activation rule (§4.3), and it validates it.**
Live preview scrolls the switcher through windows *without* activating them — so **previewing Mail
must not mark Mail's notifications read.** Only **tap** commits, activates, and clears. Had preview
been an activation, spinning the wheel past Mail would silently mark your unread mail as read.

#### Sources — filtered, unlike G2CC

**SMS/MMS · Mail · Music · Damage-specific**, plus emergency alerts. **Not** general phone
notifications.

🔑 **The filter is what makes focus-stealing tolerable, so it is load-bearing, not hygiene.** We
have no spare gesture to focus a passive notification, so an interactive notification *must* take
focus on arrival — which means every notification interrupts. G2CC forwarded everything, which is
exactly why its notifications had to be crammed into a title bar rather than given a real surface.
Filtering to a handful of sources is what buys the box its focus.

Origin splits usefully:

| origin | sources |
|---|---|
| **PC-side** (no phone involved) | Mail (Maildir + mbsync on beardos), Damage-specific events |
| **Phone-side** (via the bridge) | SMS/MMS, Music (MediaSessionManager), emergency alerts |

#### 🔴 Emergency alerts — the one genuinely unverified piece

Tornado warnings and the like must come through. **Whether a normal Android app can see WEA/CMAS
alerts at all is UNKNOWN (U) and must be tested on the Pixel 10a before this is promised.**
Candidate paths, none confirmed:

1. A `NotificationListenerService` seeing `com.android.cellbroadcastreceiver`'s notification.
2. The `Telephony.CellBroadcasts` provider — likely gated behind a privileged/signature permission.
3. Detecting the full-screen intent these alerts use instead of an ordinary notification.

Two rules regardless of which works:

- 🔴 **Damage is never the only path.** The phone still sounds and displays the alert. Damage is a
  redundant surface. A "must not miss" channel that can break silently is worse than no channel.
- 🔴 **Startup self-test.** Verify at launch that the emergency path is live and say so loudly if it
  is not — the failure must surface during setup, not during a tornado.

**Gestures — tap dismisses.** 🔑 Adam, 2026-08-18: *"a single tap when they DO have focus should
dismiss them same as a double-tap, because there's no app to switch to for those."* Tap's normal
meaning (open in the source app) has **no referent** for an emergency alert, so leaving it mapped
there would be a dead gesture. Mapping it to dismiss means every gesture does something:

| gesture | on an emergency alert |
|---|---|
| **tap** | dismiss + mark read — *same as double-tap* |
| **double-tap** | dismiss + mark read |
| **long-press** | nothing by default (§1.2); with long-press enabled: dismiss without marking read |

⚠ **Easy dismissal is correct here, not a risk.** The grace period already guarantees it cannot be
hit by an in-flight gesture, and the purpose of surfacing the alert on the glasses is *"to ensure I
don't miss it"* — once it has been displayed and the grace has elapsed, that purpose is served.
Hardening dismissal further would add annoyance, not safety. The alert also remains in the phone's
own alert history and in Damage's notification history (§4.5 is never the only path).

**Treatment:** its own presentation, not the standard box. This is finally the use for **plane +1**,
the crossed-disparity slot reserved in §3.1 and otherwise off by default. An emergency alert
**cancels any pending confirm rather than stacking on it** — losing a confirm is safe, a
mis-landed gesture is not.

#### Queueing — adopted 2026-08-18

- **One box at a time**, with a count badge; dismissing reveals the next.
- **Coalesce by source and thread** — three messages in one SMS thread are one notification.
- **Carry G2CC's `interruptible()` rule**: a notification must not repaint over a confirm or other
  destructive step. *"The 'nothing reaches CC unread' guarantee means a notification overlay must
  never repaint over a dictation-confirm card."*
- The next box in an actively-cleared queue skips the focus grace — see the grace rules above.

#### Cost — notifications are effectively free

| | bytes | ms |
|---|---|---|
| appear, max size 248×104 | 390–650 B | 211–235 |
| appear, min size 248×56 | 210–350 B | 195–208 |
| silent-mode box 200×56 | 170–280 B | 191–201 |
| scroll one line (mode 9 shift + one-line fill) | 150–300 B | 190–203 |
| dismiss (repaint covered region) | same as appear | same |

All well under the 1,936 B the ack floor buys, so every one of these is ack-bound, not
bandwidth-bound — a notification costs one round trip and nothing more.

---

### 4.5b 🎨 The icon system — one set, reused everywhere

Adam, 2026-08-18: the switcher *"is the best-looking part of our system so far"*, and he wants
**more graphics and eyecandy wherever it makes sense and does not remove information.** The
switcher's distinguishing feature is that it is the only surface using icons. So propagate them.

**One icon set, four places:**

| where | how |
|---|---|
| switcher wheel | 56 px, full brightness on the centre item (§4.3) |
| **Main's list rows** | 20 px, dim, ahead of the name — icon **and** name, so nothing is lost |
| **Main's lens** | ~~24 px~~ → **band-height (56 px class) — revised 2026-09-01, Adam:** *"when that app is selected and has two lines available, the icon should take up both lines, so it can be a better more visually appealing icon"* — the focused row's icon spans the 64 px lens band at the switcher-class size; summary text starts right of it |
| notification source line | 16 px ahead of `SMS · MOM` (§4.5) |

Learning the vocabulary is free: the switcher and Main both show icon *beside* name constantly, so
the icon becomes readable on its own without ever having replaced a label.

🆕 **One drawn icon per app, two scales** (2026-09-01): each `IconKind` is designed once at
quality and rendered at 56 px (switcher centre, Main's lens) and 20 px (rows) — which is why the
queued icon-quality pass moves to the **front** of the app wave: settle the language, then draw
the ~13 new window icons once, never twice. ⚠ Implementation watch-item: Main's **resting** state
keeps the lens visible at ≤ 5 % ink, so the render and BUD007 decide whether the band-height icon
rides at rest or dims there — the same gate that caught the row-icons-at-rest regression (§9.2b).
The measured ink table in §4.2 reflects current renders; regenerate when this lands.

**Drawing rules** (§2.4 rule 9, and they are also the compression rules): thick strokes, closed
forms, no hairlines, few levels, solid fills. Measured cost of adding icons to all eleven Main rows
plus the lens: **ink 7.1 % → 8.1 %, bytes +8 %** — well inside the 15 % budget.

✅ **Three more adopted 2026-08-18:**

- ⚪ ~~**Icon-only ribbon side cells.**~~ Adopted and then **made moot within the day** when the
  ribbon itself was retired (§4.1). Recorded rather than deleted because the *principle* survived
  and is used elsewhere: **the thing you are in is named, its neighbours are pictured** — which is
  exactly what the switcher wheel does with its centre item versus its foreshortened neighbours.
- **A compass TAPE** replaces the `NE` label: three sectors with the current one under a fixed
  centre mark. Wide-and-short is the cheap shape (§4.5), and a tape is unambiguous about which way
  it scrolls, which a rotating arrow would not be. Paid for by rebalancing the status bar to
  `op 160 · status 132 · thru 128 · tape 100 · link 120`.
- **Coarse block progress bars** wherever there is a genuine quantity — build progress in the lens,
  reading position on a row. **Discrete blocks, never a smooth bar**: solid blocks are long RLE
  runs, a gradient is not. Same rule as the switcher's quantised fade.

Measured after all three plus row icons: **ink 8.7 %, 7,861 B** — still inside the 15 % budget.

❌ **Not adopted: a gesture legend.** Faceclaw puts one on every screen (`▲▼ item · launch · ·· row`),
and it is genuinely smart *there* — because its gestures change per screen. **Ours do not.** Tap,
double-tap, long-press (a no-op by default, §1.2) and scroll mean the same thing in every
window; only notifications differ.
A legend would teach nothing and cost a permanent row of ink. ⇒ **The uniform grammar buys back the
screen space Faceclaw spends explaining itself.** That is the payoff for keeping §1.2 rigid.

---

### 4.6 The content area and window chrome

`x 16, y 34, w 608, h 416` — **82.3 % of the panel, against G2CC's 64.2 %, and 2.37× the pixels.**
G2CC also spent a permanent 96 px on its menu list; Damage spends **zero**, for the reason below.

#### 🔑 There is no per-window chrome, because tap already descends

G2CC needed a persistent menu region because the firmware owned interaction. We don't: **tap always
descends.** A window's actions are a *level*, not a *region* —

```
content list  ──tap an item──▶  that item's actions  ──tap──▶  done
              ◀──double-tap──                        ◀──double-tap──
```

which is exactly what G2CC's Files window already did (*"tapping a FILE opens the ACTIONS level —
Open / Move / Copy / Rename / Del / Stats"*), generalised to every window. **Window-level** actions
(Reader's Jump/Mark/Recent) live at the end of the list, reachable in **one notch up from the top by
wrapping** — the identical pattern that puts Settings at the end of Main's list (§4.2). Same gesture,
same place, at both levels.

⇒ The window gets the whole content area. That is where the 2.37× comes from — it is not extra
panel, it is mostly *chrome we stopped drawing*.

#### The three content modes — and what they really declare

The mode a window picks is a statement about **who owns damage**, which is the only thing that
matters to its cost:

| mode | WM provides | window owns | one scroll step |
|---|---|---|---|
| **List** | the lens + panning list + rail + `▸` marks | row content | mode 9 shift + 2 fills · **860–1,430 B** |
| **Document** | endless scroll (mode 8 { mode 9 shift + mode 3 fill }) + rail | flowed text | shift + one line · **292–486 B** |
| **Canvas** | nothing but the viewport | everything, including damage | its own |

**List and Document are nearly free because the WM tracks damage for them.** Canvas hands that back
to the window, and the honest number is: **a full-frame 608×416 canvas repaint is 3.8–6.3 KB and
~1.57 fps.** A canvas window that tracks its own damage runs as fast as its damage is small; one
that repaints everything runs at 1.5 fps. That is the whole story for games, stated up front rather
than discovered.

**List mode reuses Main's lens verbatim** — band at `y 210, h 64`, centre on **y = 242**, list panning
through it, cursor fixed (`overview.md` §12). So Main, the switcher wheel, and every list window all
put the focal element on the same axis. **The lens is a WM primitive, not a Main feature.**

#### The scroll rail

The WM reserves `x 612, w 12` and draws the rail itself as a layer above the window, so nothing has
to be negotiated. **List and Document content is therefore 596 wide**; Canvas gets the full 608 and
handles its own indication.

- A filled thumb, not an outline (§8.5) — 75–125 B, and it only repaints when it would move by ≥2 px,
  which drops most updates on a long list.
- This is ours now. G2CC kept fighting the firmware's overflow scrollbar; that widget no longer
  exists in our world.

#### 🔑 The dividers are the WM's status rails

The top divider already carries window position and attention marks (§4.1). The bottom one — `640×2` at `y 450` — is
lit anyway, so give it the other axis: **back-stack depth.** N bright segments = N levels deep.

| divider | axis | tells you |
|---|---|---|
| top, `y 32` | horizontal | **which window** — position in the full set |
| bottom, `y 450` | vertical | **how deep** — levels on the back stack |

Zero extra pixels, zero extra bytes, both inside rects that were being sent regardless. Together
they answer "where am I?" on both axes without a breadcrumb bar costing a row of ink.

And the **breadcrumb text is already free too**: the top bar's Title is *"what is inside this window
right now"* (§4.1), so `Mail · Jane Doe · Reply` encodes the depth in prose while the bottom divider
encodes it as a glance. Neither costs new chrome.

#### Depth

Content sits at **plane −1**; the **lens comes forward to plane 0**, the same "focused comes forward"
language as the notification taking focus and the switcher's centre item (§3.1). Document and Canvas
have no focused row, so they sit flat at −1.

#### Ink

Targets, to calibrate on the first real render (§4.2):

| mode | ink budget |
|---|---|
| List | ≤ 15 % |
| Document | ≤ 25 % — prose is legitimately denser |
| Canvas | the window's call; **linted with a warning, not an error** |

#### What a window declares to the shell

The chrome-facing half of the window contract. (The full app-side contract is app-layer work.)

| | used by |
|---|---|
| **mode** — list / document / canvas | picks the damage owner, above |
| **title** — what is inside it right now | top bar Title (§4.1) |
| **summary** — one line, plus optional detail lines | Main's dashboard rows and lens (§4.2) |
| **icon** | switcher wheel (§4.3), Main rows |
| **dirty** | the top divider's attention marks (§4.1) and switcher row ticks (§4.3) |
| **state blob** | full persistence — **mode included, not just position** (§9.1) |
| **actions** | the wrap-to-end actions level, above |

⚠ **`summary` must be cheap and side-effect-free** — it is called for every window on every Main
render. G2CC learned this the hard way and split `preview()` out of `view()` for exactly this reason:
*"MUST be cheap + side-effect-free… NEVER spawn a subprocess or ping the phone."* Carry that rule.

---

## 5. Compositor engine

All adopted 2026-08-17.

1. **Price damage partitions by actually compressing them.** Enumerate candidate splits (1 box,
   2-way, … up to the rect budget), deflate each, take the true minimum. Compute is free and the
   wire is scarce; Faceclaw uses fixed gap thresholds instead. ⚠ Each rect gets its **own zlib
   stream** (no `inflateSetDictionary` exists — verified), so splitting always loses cross-rect
   sharing. That loss must be part of the price.
2. **Mode 9 is a general primitive, not a scroll trick.** Any change expressible as *translation +
   small fill* is near-free. **Before emitting pixels, ask whether the delta is a translation.**
3. **Any list or strip that moves by a whole row/cell is a mode-9 shift plus one fill** — never a
   repaint. Main's panning list is the live case (§4.2); the retired ribbon was the first, and the
   rule outlived it. ⚠ It requires uniform row/cell extents: **a translation cannot resize**, which
   is precisely what ended the unequal-width ribbon (§4.1).
4. **Occlusion culling** — never transmit pixels a higher layer covers.
5. **Speculative pre-compression** — while idle, render and deflate the likely next frames (the
   next scroll position, the switcher's adjacent windows). The flush becomes a memcpy.
6. **Hash before send** — never emit a rect whose content did not actually change.
7. **A real fid allocator**, with hard assertions on outstanding depth. Collisions are **silently
   skipped** by firmware; this is the bug class that eats compositors invisibly. See §8.2.
8. **Never keyframe** — ~1.1 s. Cold start and detected divergence only.
9. **Per-lens shadow model**, once stereo is live.
10. **Cross-window deltas** — a switch computes the delta from the *current screen* to the target's
    composed frame, not from black. With chrome identical everywhere (§2.4 rule 6) and layouts
    often similar, this is far smaller than a repaint.
11. **Optimistic paint** — at this latency, "the tap selects the highlighted item" is right nearly
    always. Paint the result immediately, reconcile if wrong.
12. **Two shadows: composed and transmitted.** Damage is computed against what was *sent*, not what
    was last composed — advancing on send, rolling back on failure. Without this a pipeline
    transmits stale pixels.
13. **Backpressure coalescing.** When the pipe is full and new damage arrives, merge it into the
    pending flush rather than queueing. Always transmit latest state, never a backlog — this is
    what stops animation degrading into lag under load.
14. **Damage epochs.** Stamp damage with a counter so late-arriving state is discarded, not painted.
15. **Deep idle.** When nothing changes, stop flushing entirely except the clock tick. Frees the
    link and reduces contention with whatever causes the 10× throughput shortfall.
16. **Reconnect without a keyframe.** The lease tells you whether the shadow is still yours: held
    continuously ⇒ intact ⇒ resume with deltas; lapsed ⇒ stock repainted over us ⇒ keyframe
    required. Turns a ~1.1 s reconnect into ~0.
17. **The sacrificial warmup frame is the splash.** The firmware silently drops the first burst
    after CREATE, so a throwaway frame is required regardless — make it the boot logo.
18. **Texture-cache readiness now.** Content-hash every rect and make the hash the cache key, so
    adopting Babcock's cache is a transport swap rather than a rewrite.

**Implementation status (2026-08-24, `core/…/comp/Compositor.kt`, after eight review rounds).**
Rules 2, 3, 4, 6, 7, 8, 11, 13, 14, 15 and 17 are implemented as written. Rule 1 is implemented
as a merge toward the rect budget followed by a *priced* pass (neighbours merge when the
compressed union is cheaper than the parts). Rules 9 and 12 were superseded by one mechanism the
reviews forced: the compositor keeps an expected shadow **per lens** and renders the per-lens
**truth** of the nominal frame under the plane map (regions vacate to black — the seam; pieces
render at their shift, far to near, nearest wins; the 16 px insets are the transparent shift
budget of §3.3), then emits whatever makes shadow equal truth. Damage is still computed against
what was *sent*, but a lost flush no longer "rolls back" to a snapshot — it marks the per-lens
cells it touched **unknown**, because other flushes land around it and no snapshot can say what
the glass holds. Rule 16 is partial: a lease loss requests a keyframe; reconnect itself is still
host-driven. Rules 5 (speculative pre-compression), 10 (cross-window deltas from the current
screen) and 18 (content-hash cache keys) are **not built yet** — the seam is designed to take
them. `LensOracleTest` pins the per-lens model against the firmware simulator; see
`IMPLEMENTATION.md` → "Review hardening".

**Finishing build (2026-08-25).** Rule 16's reconnect is now the session keeper's: a link end
restarts the session (a keyframe follows, since the lease cannot survive the gap). The three
unbuilt rules attach at named seams: rule 5 (speculative pre-compression) inside
`Compositor.assembleFlush`'s payload compression, where a cache keyed by (rect, content hash)
would be consulted first; rule 10 (cross-window deltas) in `Shell.commitWindow`, which today
repaints the content area — the compositor's per-lens diff already sends only what changed, so
the rule reduces to composing the target's frame before the switch; rule 18 (content-hash cache
keys) at `Emit.encode`, the single place every payload passes through.

---

## 6. Motion — first-class

**Adam: graphical effects are first-class.** *"ribbon scrolling should slide the elements rather
than snap, just quickly and then stop at each… subtle and quick but awesome and pleasing"*, in
every facet and function. *(The ribbon he was describing has since been retired — §4.1 — but the
instruction was about motion everywhere, and it governs the switcher wheel, Main's panning list and
every page turn.)*

### 6.1 Why it is nearly free

With three flushes in flight a small delta completes every ~59 ms, so **a 4-frame slide is ~236 ms
— about the latency you would have eaten anyway.** Animation converts dead time into motion rather
than adding time. And because mode 9 makes translation free, **the damage during a slide is only
the newly exposed strip**, so an animated transition can cost *fewer* bytes than snapping to the
end state.

### 6.2 The motion vocabulary

> **Rule: any transition not expressible as translation + small fill is too expensive.**

That constraint produces a coherent physical language rather than a grab-bag:

- **Slide** — list panning, window switches, page turns.
- **Reveal / cover** — overlays entering from an edge.
- ❌ **Fade, dissolve, cross-fade** — full-rect repaints every frame. Excluded (§0).

### 6.3 Rules

- **Ease-out, quantized to the 4 px grid.** Mode 9 shifts are unquantized, but the mode-3 fill is
  not, so every step must be a multiple of 4. A natural decelerating slide: `32, 24, 16, 8, 4`.
- **Animations are interruptible and retargetable.** A second scroll notch mid-slide *retargets*;
  it never queues. Otherwise fast scrolling backs up into visible lag.
- **Motion yields to input.** The frame scheduler always preempts an in-progress animation for a
  response to new input.
- 🟡 **Progressive band painting** (spreading one big repaint across flushes) is experimental, to
  be tried only where it demonstrably helps. ⚠ Note a mode-8 batch **presents atomically**, so
  there is no banding effect *within* a flush — it only exists if we deliberately spread across
  flushes, and that costs more total bytes.

---

## 7. Sensors

### 7.1 IMU / head tracking — available, default OFF

`IMU_CtrlCmd{IMUReportEn, reportFrq}`, `IMU_Report_Data{x,y,z}`, `OsEventTypeList.IMU_DATA_REPORT
= 8` (**V**, vendor schema; untested on our firmware). Head gestures are the only input path that
needs no hands, which matters when gloved.

⚠ **Adam: "my head is all over the place as i work, that would get old FAST."** ⇒ **Off by
default**, opt-in per feature, never a required input path.

### 7.2 Compass — adopted

CFW mode 10, heading via the stock sid-0x08 notifier. **8 sectors** (N NE E SE S SW W NW) in the
status bar.

🔴 **Hysteresis is mandatory, not polish.** Heading changes constantly as his head moves; a naive
implementation would emit a flush every time the reading crosses a sector boundary and turn the
compass into a flush firehose. **Rules:** update only on the idle chrome tick (§8.3), only when
the 8-way sector actually changes, and with a deadband around each boundary so it cannot flicker
between N and NE.

### 7.3 Wear detection — excluded for now

`wearnotify` is in the CFW capability string and stays unused (§0).

---

## 8. Frame economics

### 8.1 Three flushes in flight

**Adopted.** The ~176 ms is *latency*, not service time; the CFW's snapshot/deferred FIFO exists
to make pipelined deltas safe, and Faceclaw ships `WINDOW_SIZE = 3` on exactly this path. Keeping
the window full gives **~15–17 fps on small damage**, not ~5.7. Graded **C/I** — read from his
code, not our wire.

### 8.2 🔴 Frame-id discipline — read from `cfw_diag()`, not inferred

The rect budget looked like a simple `floor(16/window)` cap. Reading the firmware's actual
duplicate detector changes the answer:

```c
static int cfw_diag(int has_fid, uint16_t fid) {
    if (!has_fid) { ctx->fid_resync = 1; return 0; }      /* mode-6 keyframe rebaselines */
    for (i = 0; i < CFW_FID_RING; i++)
        if (ctx->recent_fids[i] == fid) { ctx->f_dup = 1; return 1; }   /* skip */
    if (!ctx->fid_resync) {
        uint16_t d = (uint16_t)(fid - ctx->last_fid);
        if (d >= 0x8000u) ctx->f_reorder = 1;             /* backward — FLAGGED, NOT SKIPPED */
        else if (d > 1)   ctx->f_skip = 1;                /* forward gap */
    }
    ...
}
```

🔑 **Only an exact hit in the 16-deep ring causes a skip.** A stale fid that has *aged out* is
flagged and then **applied** — silently clobbering newer pixels. The ring is a short-window filter,
not a safety net, so "keep enough history in the ring" is the wrong thing to engineer toward.

**The method, in order:**

1. **Never put the same fid on the wire twice.** On a missed ack, do *not* retransmit — recompute
   damage for that region and send it with a **fresh** fid. Costs the same bytes or fewer (the
   region may have changed), and it eliminates the aged-out-stale-duplicate hazard outright. This
   is the only duplicate source that scales with rect count.
2. **The ring's remaining job is firmware-internal re-processing** (the snapshot FIFO / cross-lens
   completion path — what the code's *"re-processed message"* comment refers to). That duplicate
   arrives immediately, within a few fids, so 16 covers it at any sane rect count. **The ring
   therefore stops constraining the budget.**
3. **Keep `rects × window ≤ 16` as a free invariant anyway.** It costs nothing, because the
   compression-optimal partition is almost always 1–3 rects: every rect carries its own zlib stream
   (no `inflateSetDictionary` exists — verified) plus ~15 B of framing, so splitting usually loses.
   The cost oracle (§9.2) picks the split; this is just a ceiling it rarely touches.
4. **The budget is a product — trade depth for rects on demand.** `R×W ≤ 16` gives R=5 at W=3,
   R=8 at W=2, R=16 at W=1. A rare wide split drops the pipeline for one flush instead of being
   forced to merge.
5. **Fids are strictly +1 and allocated at EMIT time, never at plan time.** A gap sets `f_skip`.
5b. ✅ **Only mode-3 deltas consume a fid.** Verified: the sole `cfw_diag()` call sites are the
   mode-6 keyframe (`cfw_diag(0,0)`, rebaseline) and the mode-3 delta. **Mode 9 rect-copies are free
   against the fid budget**, so `rects × window ≤ 16` counts mode-3 sub-messages only. Every
   translation-based effect in this design — Main's list pan, the switcher spin's neighbours,
   marquees, endless scroll — is therefore cheap in *both* budgets at once.
6. 🔴 **Handle the 16-bit wrap deliberately.** fid lives in `[1, 0xFFFE]` (0xFFFF is the ring
   sentinel). At the wrap `d = (uint16_t)(1 - 0xFFFE) = 3`, which trips **`f_skip`**. Cross it
   during deep idle and clear the flags immediately after with **mode 7 sub 0** (which resets both
   the flags and the ring), or whitelist that one expected event — otherwise the panic frame
   (§9.3) fires for nothing.

**Default allocation** at W=3: top bar 1 · status bar 1 · content 3. The two bars cannot share a
rect — their bounding box is the whole screen. Chrome is usually clean (§8.3), so content typically
gets 4–5.

Grade **I** — derived from reading the decoder, not observed on hardware.

### 8.3 The flush rule

> **Chrome never justifies its own flush.** A dirty chrome cell is marked and rides along in the
> next content flush for free. Chrome-only changes flush on an idle tick, not on change.

Default idle tick **5 s**. A live 1 Hz telemetry mode is a debugging tool, not a default.

### 8.4 Modeled costs

⚠ **All modeled**, area-scaled from the 576×288 measurements via `ms ≈ bytes/11000 × 1000 + 176`.
🆕 **The real CFW path is measured (2026-08-31, `overview.md` §5.2): `ms ≈ 60 + bytes/50`** —
every row below is conservative by roughly 3–5× on hardware. Kept as the modeled baseline the
design was proven against; quote §5.2 for anything current.

| | bytes | ms |
|---|---|---|
| full keyframe 640×480 (mode 6) | ~10.5 KB | ~1,130 |
| full content repaint 608×416, dense | ~8.7 KB | ~965 |
| full content repaint, text dashboard | ~2.5–5 KB | ~400–650 |
| status-bar strip 640×28 | ~0.7–1.5 KB | ~240–310 |
| one chrome cell (clock 72×28) | ~150–250 B | ~190 |
| silent mode (flat black + clock) | ~1.5 KB | ~310 |
| stereo shift on an existing rect | +4 B | ~0 |

### 8.5 Rendering optimizations

- **Snap glyphs to the 4 px grid** — crisp vertical stems, AA only on curves. Prettier *and* fewer
  gray runs *and* fewer bytes. Directly addresses *"zlib+RLE doesn't play nice with antialiased
  fonts."*
- **A restrained gray ramp** — ~5 levels for UI (bg / dim chrome / text / bright / highlight), all
  16 reserved for imagery. Fewer distinct values = longer RLE runs. Restraint is a compression
  optimization here.
- **Filled highlights, never outlined.** A filled bar is one long run; an outline is many short
  ones. The cheap choice is also the more legible one at FAR.
- **Run-aware quantizer** — on near-ties when downsampling to 4bpp, prefer the neighbour's value to
  extend runs.

---

## 9. Persistence, observability, failure

### 9.1 Full persistence — WM-owned and enforced

🔴 **Adam's strongest stated requirement.** In G2CC he had to push for this repeatedly and it is
*still* wrong: *"if i enter Focus mode to scroll in Tmux and have to temporarily change windows, i
come back to the live tail and lose my place, i fuckin HATE that."*

The contract:

1. **Persist mode, not just position.** The Tmux failure is precisely that a *mode*
   (frozen/Focus) was lost while the *offset* was kept. Restoring a window restores everything the
   user could see or was doing — scroll offset, focus level, cursor, frozen-vs-live, open dialogs,
   partially entered input.
2. **The WM owns it, apps cannot forget it.** A window declares a state blob; the shell saves and
   restores it. Not per-app opt-in.
3. **Survives WM restart**, not just window switches. Disk-backed.
4. 🔑 **A regression gate makes it stick.** In the byte-exact simulator (§9.2): switch away, switch
   back, **assert the composed frame is byte-identical.** That turns "I had to push for this
   repeatedly" into a test that fails loudly instead of a discipline that erodes.

### 9.2 Observability

- 🔑 **A byte-exact offline simulator.** We already have a faithful port of the firmware's RLE
  decoder (round-tripped through 301 cases). Wrap it: apply our real mode-3/6/8/9 stream to a
  modeled per-lens shadow and render what the lens *would* show. **The whole WM becomes
  developable and regression-testable with no glasses**, and it catches the stale-base/divergence
  class of bug that is otherwise invisible until it is on your face. Nothing like the EvenHub
  simulator, which lies.
- **Deterministic frame journal** — every flush with its rects, bytes, fids, ack latency, replayable
  into the simulator. That is how you debug something you cannot attach a debugger to.
- 🔑 **The mode-7 flags are a free loss/ordering telemetry channel, not a debug toy.** Reading
  `cfw_diag()` shows each maps to a distinct real condition:

  | flag | means |
  |---|---|
  | `f_dup` | a delta was processed twice |
  | `f_skip` | **a delta never arrived** — a forward gap in a strictly-+1 fid sequence |
  | `f_reorder` | a delta arrived out of order |
  | `f_snap_of` | snapshot FIFO overflow — we outran the firmware |

  Those are exactly the compositor's failure modes, reported by the firmware for free. `f_skip` in
  particular is a **transmission-loss detector** we would otherwise have to build. Wire them to the
  status indicator and to automatic resync; leave the overlay ON during bring-up. ⚠ Whitelist the
  one expected `f_skip` at each fid wrap (§8.2 #6).
- **Status-bar profiler** — flush rate, damage bytes, rect count, fid depth, ack. Toggleable, off
  by default in normal use (it is itself a flush consumer).
- **Input echo** — the last gesture actually received, shown in the status cell. Turns the
  ambiguous scroll-vs-tap physical action into an observable one (§1.7).
- **The logger service (sid 0x0F)** — `logStr` streamed to host would surface the CFW's own
  `evenhub_ui: decompress failed, mode=%u raw_len=%u`. Our worst failure mode is silent garbage on
  the lens; this is what makes it loud. Untested lead (**V** schema, **U** on our firmware).

### 9.2b Build-time gates and runtime guards

- 🔑 **A thorough layout linter, as a build gate.** Every silent failure mode on this hardware
  becomes a compile error, because the hardware will never tell you: unaligned rect (x/w ×4,
  y/h ×2), rect count over budget, `rects × window > 16`, chrome cell content overflowing its box,
  layout/CREATE frame >1000 B, image fragment >3800 B, fid gap or reuse, stereo box pair with
  mismatched size, mode-3 delta with no prior keyframe, box out of 640×480 bounds, and a surface
  over its ink budget. **NO SILENT FAILURES, pushed left to build time.** Adam: *"minimizing the
  chances of a bug making it to the glasses as much as possible."*

  ✅ **`tools/lint.py` + `tools/geometry.py` exist and pass** (2026-08-18).

  ⚠ **Most of these are RUNTIME properties, not static ones** — a rect computed at frame time is
  invisible to a source linter. So the rules live in **`tools/geometry.py` as a library the
  compositor calls on every emit**, and the linter runs the same functions statically over the
  spec's declared geometry and over rendered surfaces. One definition, two callers.

  | rule | catches |
  |---|---|
  | **SYM001** | a string reaching a text-drawing call contains a codepoint the target face cannot render |
  | **GEO001** | x/w not a multiple of 4, or y/h not a multiple of 2 |
  | **GEO002** | box outside 640×480 — *rejected in silence, previous frame stays up* |
  | **GEO003** | zero or negative extent |
  | **GEO004** | stereo pair whose boxes differ in size — firmware rejects it |
  | **GEO005** | vertical disparity (forbidden by §3.4; the wire format would allow it) |
  | **GEO006** | disparity not on the 4 px ladder |
  | **GEO007** | chrome cells overlapping |
  | **GEO008** | cells that do not tile their bar — gap or overflow |
  | **BUD001** | `rects × window > 16` — a retransmit would age out of the ring and be **re-applied** |
  | **BUD002** | mode-8 batch over the firmware's 153,718 B cap |
  | **BUD003** | layout/CREATE frame over ~1000 B — *no ack, no error* |
  | **BUD004** | image fragment over 3800 B |
  | **BUD005** | a rendered surface over its ink budget |
  | **BUD006** | a surface with no render at all — unrendered is unchecked |
  | **BUD007** | `DESIGN.md`'s own stated *measured* ink disagrees with the actual render |
  | **FID001** | fid reuse — the same id must never reach the wire twice |
  | **FID002** | fid gap or reversal (sets `f_skip` / `f_reorder`) |
  | **FID003** | fid outside `[1, 0xFFFE]` |
  | **FID004** | a mode-3 delta with no prior keyframe |

  🔑 **`--selftest` proves every rule fires**, against known-bad inputs, and that valid geometry
  stays silent. *A gate nobody has seen fail is a gate nobody trusts.*

  ✅ **It caught a real regression on its first full run.** Adding row icons (§4.5b) pushed
  **Main's resting state from 4.1 % to 5.4 %, over its 5 % budget.** The fix was design, not a
  raised budget: icons are solid shapes and the costly part of that surface, and §4.2 already said
  the non-lens rows go "away entirely" at rest — so the resting state now keeps the dim names and
  drops the icons. **4.6 %, passing.** That is exactly the drift the gate exists to catch, and it
  found it within minutes of existing.

  It also lints **DESIGN.md's own §2.3 cell table**, which is machine-readable and is the current
  source of truth for the shell's layout. Both real layout bugs so far — the 96/128/96 ribbon and
  the 250 px notification width — were errors *in that table*, found by eye. They would now fail
  the build.

  It reads the real **`cmap`** of every locked face (§Type). ⚠ *`PIL.getmask().getbbox()` is not a
  coverage test — a tofu box has a bounding box too, and that false negative is exactly how U+25B8
  reached three separate renders before anyone noticed.*

  **Scope is strings passed to a drawing call, not every literal in the repo.** The first cut
  flagged 45 findings — newlines, docstrings, log lines — which is how a rule gets ignored. Narrowed
  to drawn strings it reports **2 real findings and nothing else**. Verified against a fixture: it
  catches a literal in `d.text(...)` *and* inside an f-string, while ignoring docstrings, `print()`
  calls, safe glyphs (`·`, `—` are in all four faces), and any line marked `# lint:allow-symbols`.

  ```
  tools/lint.py                      # gate the shell; exit 1 on any finding
  tools/lint.py --faces              # glyph counts for the locked faces
  tools/lint.py --codepoint ▸ ⚙      # check characters before using them
  ```

  Measured coverage of the symbols this design reached for: **`▸` and `▶` missing from 3 of 4
  locked faces, `⚙` from all 4, `⇒` from 3, `▓`/`▒` from 2.** Only `·` and `—` are universal.
- **Panic frame.** On any mode-7 divergence flag or decompress failure, immediately keyframe rather
  than keep compositing onto an inconsistent shadow.
- **Startup capability gate.** Read the `EVENCFW/` string (sid-0x09 settings READ response, field
  100) and require `img640 directfb fbguard imgz rle`; refuse loudly otherwise. Needs no timeout —
  tag 100 sits above the stock field range so stock decoders skip it. Also catches a future CFW
  that changes semantics, instead of painting garbage.
- **Cost oracle.** The compositor reports what each layout decision costs in bytes and flushes
  *while it is being made*. Compute is free — run it on every change, and let it pick rect splits
  (§8.2 #3).
- **"Why is it slow" attribution.** Journal plus profiler splits every millisecond into ack,
  transfer, compose and animation. The 10× throughput shortfall is the biggest unsolved problem in
  this ecosystem — its author says debugging it *"would make a much bigger difference than
  compression tuning"* — so a WM that measures it on real traffic is worth something upstream too.

### 9.3 Error surfacing — the phone is the out-of-band channel

With the buzzer excluded, **the phone bridge app raises a phone notification on serious errors.**
That is now the *only* alert path that works when the display itself is what is broken.

Severity worth escalating to the phone: framebuffer lease lost · decompress failure ·
`ImgResCmd.ErrorCode` · link down · fid collision detected · sustained ack-timeout streak.

### 9.4 Session hygiene

Bump `MapSessionId` by ≥2 on any reset (the stuck-session trap, seen on our own wire). Cycle msgId
well before 255. Serialize multi-fragment writes — one reassembly buffer keyed by transport `seq`.

---

### 🔤 Type — measured 2026-08-18, and one result is counterintuitive

Full Main rendered in six faces at two sizes (`design/render_shots.py`, shots in `design/shots/`):

| face @ scale | ink | bytes | vs baseline |
|---|---|---|---|
| DejaVu Sans @1.00 | 7.3 % | 7,630 B | 1.00× |
| **DejaVu Condensed @1.00** | **7.2 %** | **8,582 B** | **1.12×** ⚠ |
| DejaVu Light @1.00 | 6.0 % | 7,727 B | 1.01× |
| Liberation Sans @1.00 | 7.1 % | 7,725 B | 1.01× |
| Nimbus Sans Narrow @1.00 | 6.5 % | 7,403 B | 0.97× |
| URW Gothic @1.00 | 7.1 % | 7,976 B | 1.05× |
| *any face @0.85* | 5.0–5.9 % | 5,930–6,874 B | **0.78–0.90×** |

⚠ **A correction to an earlier version of this section.** It claimed *"condensed uses less ink and
costs more bytes — do not reach for a condensed face to save bandwidth."* That generalised from a
single measurement. Re-tested across the URW set: **DejaVu Condensed is 1.13×, but Helvetica Narrow
is 0.98× — the best of the whole set.** Condensing is not the variable; **the specific face is.**
Measure the face you intend to ship; do not reason from its width class.

**The full set, Main rendered in each at 1.0×:**

| face | ink | bytes |
|---|---|---|
| Helvetica Narrow (Nimbus Sans Narrow) | 8.2 % | **7,594 B · 0.98×** |
| DejaVu Sans | 8.9 % | 7,734 B · 1.00× |
| Helvetica (Nimbus Sans) | 8.8 % | 8,076 B · 1.04× |
| Times (Nimbus Roman) | 8.0 % | 8,025 B · 1.04× |
| Futura-ish (URW Gothic) | 8.8 % | 8,116 B · 1.05× |
| Palatino (P052) | 8.3 % | 8,466 B · 1.09× |
| Century Schoolbook (C059) | 8.8 % | 8,740 B · 1.13× |
| Bookman | 9.1 % | 9,039 B · 1.17× |

**Size remains the real lever** — 0.85× saves ~20 % across every face.

#### 🔴 All renders are TRUE 1× from 2026-08-18 — the 2× ones were flattering

Adam: *"lets only render them at actual 1x size, to keep this honest."* Every shot in
`design/shots/` is now native 640×480 with no upscaling. The earlier 2× views made delicate faces
look far better than they will on glass, and reordered the field when corrected:

- ✅ **Survive at 1×:** DejaVu Sans, Liberation Sans, URW Gothic, Bookman, Century Schoolbook —
  all sturdy-stroked.
- ❌ **Collapse at 1×:** Times, Palatino, Courier — the serifs and thin strokes grey out, which is
  exactly the hairline failure §2.4 rule 9 warns about. **Chancery is unreadable** at UI size
  (G2CC used it for menus at a larger size; that does not carry over).

⇒ **At this angular size, stroke weight decides legibility and letterform character is secondary.**

#### 🔤 The font library, expanded 2026-08-18

44 `media-fonts/*` packages installed from portage (**450 families, up from ~35**); eight needed
`~amd64`, recorded in `/etc/portage/package.accept_keywords/damage-fonts` and safe to remove. 66
candidates are resolved to files in `design/fonts.json` and rendered as five 1× specimen sheets:
`specimen-{sans,geometric,serif,mono,display}.png`.

**A find from the survey: `B612`**, the typeface Airbus commissioned for aircraft cockpit
displays — designed for legibility on an emissive screen read in a glance under load.
🔴 **Ruled out as a default by Adam — final, 2026-09-01, after repeated re-proposals across
sessions** (*"It looks like shit, let it go"*): **B612 is NEVER a default for anything.** It may
ride the font-library expansion as one user-selectable option among many; nothing more. Do not
re-propose it, and do not read this section's survey enthusiasm as standing advice — the
measurements below stay as records, the advocacy does not.

Also newly available and directly relevant: **Clear Sans** (Intel, legibility-designed), **Fira
Sans** (Mozilla, for small screens), **IBM Plex**, **Source Sans/Serif**, **EB Garamond** (the
actual Garamond Adam asked for), **Gentium** (legibility-designed serif), and a deep mono bench —
**JetBrains Mono, Hack, Iosevka, Intel One Mono, Cascadia, Source Code Pro**.

⚠ **Bitmap faces cannot be used the way we use type.** `Terminus` (what Faceclaw ships) and
`Glass TTY VT220` exist only at fixed pixel sizes and cannot be scaled or antialiased. They are
labelled as such on the specimen sheet rather than silently dropped. Our whole typographic argument
rests on **AA TrueType at arbitrary sizes**, so they are reference points, not candidates.

⚠ Of Adam's original wishlist, **Optima, Univers, Syntax, Lucida, Matrix and Peignot have no free
equivalent installed.** Helvetica/Times/Garamond/Futura-ish/Bookman/Century are all covered.

#### Main rendered in the top candidates — x-height normalised

⚠ **Comparing faces at the same nominal pt size is not a fair test** — a face with a big x-height
simply looks larger and wins on legibility for a reason that has nothing to do with its letterforms.
Every figure below is **normalised to DejaVu Sans's x-height** first, so the comparison is about the
type and not about nominal sizing. `design/shots/main-<face>.png`, plus a stacked A/B of the same
crop in `main-font-compare.png`.

| face | x-height | scale | ink | bytes | vs DejaVu |
|---|---|---|---|---|---|
| IBM Plex Sans | 0.520 | 1.06 | 8.5 % | 7,680 B | **1.00×** |
| DejaVu Sans (baseline) | 0.550 | 1.00 | 8.9 % | 7,675 B | 1.00× |
| Open Sans | 0.540 | 1.02 | 8.5 % | 7,915 B | 1.03× |
| Ubuntu | 0.520 | 1.06 | 8.7 % | 7,877 B | 1.03× |
| Clear Sans | 0.510 | 1.08 | 8.7 % | 7,869 B | 1.03× |
| Roboto | 0.530 | 1.04 | 8.9 % | 7,989 B | 1.04× |
| **B612** (cockpit) | 0.560 | 0.98 | 8.9 % | 8,528 B | **1.11×** |
| Fira Sans | 0.530 | 1.04 | 9.1 % | 8,529 B | 1.11× |
| Source Sans 3 | 0.490 | 1.12 | 9.0 % | 8,548 B | 1.11× |
| Cantarell | 0.480 | 1.15 | 9.0 % | 8,696 B | 1.13× |

**B612 costs ~11 % more than DejaVu at matched x-height** — recorded as measurement only; see the
🔴 ruling above (never a default).

#### 🔴 UI symbols must be DRAWN, never typed

The A/B sheet showed tofu boxes where the `▸` continuation mark should be. Verified against the
real font `cmap`s, not guessed:

| symbol | missing from |
|---|---|
| `▸` U+25B8 and `▶` U+25B6 | **13 of 16** candidate faces |
| `⚙` U+2699 | **15 of 16** |
| `⌘` U+2318 | 14 of 16 |

Only DejaVu Sans carries all of them; B612 and Source Sans have the triangles but not the gear.

#### Face comparison across surfaces, and the mixed-font demo

Rendered 2026-08-18 at true 1×: `compare-main.png`, `compare-list.png`, `compare-doc.png` stack
**Fira Sans · Alegreya Sans · Nunito · Humor Sans · B612 · Clear Sans** on the same crop of each
surface, and `mixed-fonts.png` shows four windows each in a face chosen for its job.

### 🔒 LOCKED — the typeface assignments

**Decided 2026-08-18 from the 1× comparison sheets.**

| | face | why |
|---|---|---|
| 🔑 **SYSTEM FACE — all chrome, everywhere, plus Main** | **Clear Sans** | Intel's legibility-designed UI face. Measured **cheapest and lowest-ink of every candidate on every surface** — 8,555 B vs Fira's 9,491 B on the mail list, ~10 % less. Chrome is permanent, so the permanently-cheapest face belongs there |
| **Mail** and other dense lists | **Fira Sans** | designed by Mozilla for small screens; humanist warmth that survives a dense list |
| **Reader** and long-form | **Alegreya** | an actual literature serif. In `mixed-fonts.png` the page stops reading as *a window with text in it* and starts reading as *a page* — which is the identity-cue claim made visible |
| **Terminal** and any column-aligned view | **JetBrains Mono** | alignment is functional here, not decorative |
| every other window | **Clear Sans** (system face) | until that app is designed and earns an override |

*(B612 was the earlier pick for Main and was swapped out on Adam's call. 🔴 **2026-09-01, final:
NEVER a default for anything** — the "revisit for digit-heavy surfaces" advice that used to live
here is retracted; it kept re-seeding a pitch Adam had already rejected repeatedly. Digit-heavy
surfaces use the system face or drawn digits — `Icons.sevenSegClock` is the quality precedent.
What Adam DOES want is **a lot more font options**: a curated expansion from `design/fonts.json`'s
66 surveyed candidates — sturdy-at-1× survivors only, OFL/Apache-clean for APK bundling, an
x-height normalisation and a lint coverage-table row per face, all selectable in the existing
typography rows, **defaults untouched**, per-face byte tax surfaced in the cost oracle per rule 3
below. B612 may ride along as one option among many.)*

⚠ ~~**The system face is not negotiable per window.**~~ **REVERSED 2026-08-31 by Adam** (see
§0): chrome + Main follow Settings → Global (font/size/style), and each app can override its
content's font/size/style — all choices drawn from the four LOADED faces below, previewed in
their own face. The table above remains the DEFAULT assignment and the measured price list.

**Cost across surfaces** (x-height normalised): Clear Sans is consistently the cheapest — on the
mail list it is **8,555 B against Fira's 9,491 B, ~10 % less** — while Nunito is consistently the
inkiest at 9.4–9.7 %. Humor Sans is legible only at display sizes: it has effectively one case, so
a dense list renders as all small-caps.

⚠ **The first cut of `mixed-fonts.png` accidentally varied the chrome along with the content, and
that is exactly why §4.1's rule exists** — stacked, the shifting bars read as four different
programs rather than one shell with four windows. The demo now holds chrome on the system face and
varies only content, which is the intended design.

#### 🔴 UI symbols must be DRAWN, never typed

⇒ **Rule: every UI symbol is a drawn shape, like the icons already are (§4.5b).** Typing them would
ship boxes on glass **and** would silently couple typeface choice to symbol coverage — which
directly breaks the per-window font freedom adopted above. Only plain text goes through the font.
Add it to the linter (§9.2b): flag any non-Latin-1 codepoint in a drawn string.

⚠ Still not legibility *on glass*, which is what actually decides it. Open item #8 stands.

#### 🔑 Per-window typefaces — free, and partly functional

Adam, 2026-08-18: *"varied fonts depending on the content/window, where it makes sense, since the PC
is rendering the content anyway so font variation is practically free."*

Correct, and it earns more than eyecandy:

- **With the ribbon retired (§4.1), typeface becomes a free identity cue.** You know you are in
  Reader because it *looks* like Reader — information carried at zero ink cost, the same class of
  trick as the divider.
- **Some of it is functional, not decorative.** Terminal *needs* mono for column alignment; a long-
  form reader genuinely wants a book face. This is not a skin.

**Rules:**

1. 🔴 **Chrome is ONE fixed face, always.** The bars are the constant frame; varying them would read
   as chaos and would defeat the identity cue by making everything variable.
2. Content faces are the window's choice, from the sturdy-at-1× list above.
3. ⚠ **A face choice is a permanent byte tax on that window** (0.98×–1.17× measured). A window that
   picks Bookman pays 17 % more on every frame, forever. Surface it in the cost oracle (§9.2b).
4. Global default plus per-window override, both in Settings (§4.2).

---

## 10. Deployment topology — the adaptable contract

**Decided 2026-08-19/20.** Adam: *fully powered when the app and the home PC are both present,
with no sacrifices; falling back to what the app can do alone when the PC drops; able to run from a
small bridge on any BLE+WiFi device with no phone at all; and able to be driven directly from a
laptop over Bluetooth with no app, bridge or PC.* Cross-platform, so Windows, macOS and Linux users
all get it.

> 📍 **If you are deciding implementation, read this section first.** It is the only part of this
> document that constrains *how* the shell is written rather than how it looks.

### 10.1 Three roles, four deployments

There are only three roles in the system:

| role | owns |
|---|---|
| **TRANSPORT** | the BLE links, the framebuffer lease, msgId/seq/fid discipline, fragment writes, event forwarding. Must be within Bluetooth range |
| **SHELL** | input grammar, focus, back stack, switcher, damage tracking, rasterization, compression — everything in §1–§9 of this document |
| **CONTENT** | mail, files, terminal, AI, the library — the things *inside* windows |

Every configuration Adam asked for is a placement of those three:

| configuration | transport | shell | content |
|---|---|---|---|
| **app + home PC** — full power | phone | phone | PC |
| **app alone** — PC unreachable | phone | phone | phone cache |
| **bridge + home PC** — no phone | bridge appliance | **PC** | PC |
| **laptop direct** — nothing else | laptop | laptop | laptop |

🔑 **The shell moves between rows.** So it must be *one implementation that relocates*, and the
seams between roles must be **protocols, not function calls** — neither side knowing whether its
peer is in-process or across a network. Given that, all four configurations are the same three
components with different link types, and no configuration is a special case.

> 🔴 **Row 1's shell placement is the INTENT, confirmed by Adam 2026-08-31** (`HANDOFF.md` §19):
> in the default configuration the **phone shell drives** and the PC's job is to *provide data*
> so every app is fully functional. The finishing build had inverted this (the PC shell claimed
> the seam and drove whenever reachable) by misreading §8.1's "home PC is always the best case"
> — that phrase graded the DATA path, not the driver. The PC drives only in the rare
> APK-unavailable case, over BLE, and hands the radio back when the APK returns. State that can
> differ between the two syncs automatically, most-recent-wins (§19.2).

### 10.2 What this forces on the runtime

- 🔴 **The shell must run on Android *and* desktop.** The "app alone" row requires it. That rules
  out a Python shell, which is otherwise the house language — **the single most expensive thing to
  discover late.** Kotlin/JVM and TypeScript both satisfy it.
- ✅ **But the protocol split means each role picks its own best tool.** Transport on Android is
  Kotlin (see §10.6); transport on desktop is most cheaply Python + `bleak`, the one library
  covering Linux, Windows and macOS through a single API. Content can be anything the host likes.
- ✅ **The offline simulator becomes just another transport.** `BleTransport` / `SimTransport` /
  `RemoteTransport` behind one interface — G2CC's own `DisplaySink` pattern, one level up.

### 10.3 Part of this is not optional

⚠ **The framebuffer lease already forces transport-side liveness.** sid 0x09 field 101 op 5, both
arms, 45 s renewal against a 90 s expiry, **failing open** (§1.6). If the PC drives it across the
network, **any gap over 90 seconds costs the screen** and recovery is a full keyframe. The same
applies to the EvenHub keepalive when idle and to the carrier layout's periodic text upgrade.

⇒ Transport is necessarily stateful and liveness-critical **in every configuration**, including the
bridge. This was never a question of whether the phone should hold state — only how much.

### 10.4 The offline seam is already drawn

🔑 **The shell/content split lands exactly on §4.6's content modes**, which is good evidence it is
the natural seam:

- **List** and **Document** — the WM already owns their damage tracking. If the bytes are local
  they work offline with **no new machinery**.
- **Canvas** — the window owns its own damage, so it is host-dependent by nature.

🔑 **And offline capability is a free consequence of a contract §9.1 already demands.** Full
persistence requires every window to declare a state blob the shell can restore, and §4.3's live
preview requires that blob be sufficient to *render* the window without activating it. **A window
that can be previewed is a window that can be read offline.** The change is "replicate those blobs
to wherever the shell is," not "build offline apps."

Reader is the easy case: declare the book as its cache — an epub is tiny — and page turns become
pure shell.

### 10.5 Capability is a function of what is present

Extend §4.6's window contract with one field: **what the window NEEDS** (BLE · host · phone APIs).
The shell marks a window unavailable when its needs are not met, using the same surface as staleness.

⚠ **The bridge and laptop-direct configurations cannot have phone integration at all.** SMS,
notifications, media state and phone battery come from Android APIs; a Pi or a laptop cannot see
them. That is not an implementation gap to close later — it is a limit of what that box can know,
and the shell should say so rather than pretend.

### 10.6 The bridge appliance — already designed and bought

`/home/user/G2CC/docs/HAT_BRIDGE_SPEC.md` (design locked 2026-06-08, **BOM purchased, never built**
because the v0.7 software fix made the connection problems vanish). It is exactly the config-3
device:

**Seeed XIAO ESP32-C5** · 3 × 420 mAh · dual-band 2.4/5 GHz u.FL FPC antenna mounted **outside the
hat, right side, 1–2″ from the glasses' temple-tip antenna** · WSS to the PC's cloudflared tunnel.

Two things changed in its favour since it was specced. Under the CFW the framing is **simpler** than
the `f1=0/3/5/7` port it planned — `CompressMode = 0` plus a mode byte — and if the host pre-deflates,
**the bridge needs no zlib and no 153 KB shadow at all**; it forwards bytes and owns liveness.

🔑 **It is also a controlled experiment on the throughput mystery.** Its board was chosen because
*"dual-band dodges WiFi/BLE coexistence"* — and BT/WiFi coexistence on the phone's combo radio is
**one of the three surviving candidate causes** of the ~10× shortfall in `overview.md` §5.1,
explicitly the one invisible at the HCI layer. Putting WiFi on 5 GHz and BLE on 2.4 removes the
contention entirely. If throughput jumps on the hat, that isolates the cause of a defect the
firmware's own author says *"would make a much bigger difference than compression tuning."*

⚠ Unmeasured: **1260 mAh running WiFi 6 plus BLE for a full workday.** The spec has a hybrid power
policy but no power budget, and that number decides whether it is wearable or a desk toy.

### 10.7 What this genuinely costs

Three real prices, none of them fatal, all of them worth knowing before committing:

1. **Damage coalescing depends on tight transport feedback.** §5.13's backpressure rule needs the
   pipe's depth; a network hop makes that signal stale. Mitigation: transport owns the sliding
   window and reports depth, shell targets a slightly conservative value. Costs a little throughput
   in bridge mode.
2. ⚠ **"No sacrifices" is not achievable across all platforms, because BLE stacks differ in what
   they permit.** macOS will not let an application set connection parameters at all and hides MAC
   addresses behind opaque UUIDs; Windows WinRT is limited; only Android exposes
   `requestConnectionPriority`. Since §5.1's shortfall may itself be connection-parameter related,
   **a Mac may simply be slower, with nothing to be done about it.**
3. **It multiplies the work** — three components, a protocol spec, two or three BLE backends, and an
   appliance, against "PC composes, phone displays." That is the refinery's problem, but it is not
   free.

### 10.8 Build order

**Laptop-direct first.** It sounds like the exotic configuration; it is actually the *development
environment* — one process, real glasses, a real debugger, no phone and no network to blame. Get it
working, then split out transport for the bridge, then relocate the shell to Android for the phone
rows. This inverts the phone-first assumption and is the cheaper path.

---

## 11. Open items

| # | item | grade | resolves |
|---|---|---|---|
| 1 | ✅ **Per-notch scroll** — **works, daily use since 2026-08-30** (was C ⚠); only fast-spin coalescing unprobed | **M** | done |
| 2 | **Comfortable disparity `d`**; whether stock FAR already spends budget | **U** | calibration ramp |
| 3 | **Frame-id discipline** (§8.2) — derived from reading the decoder, never observed | **I** | first light |
| 4 | **Link signal** — source, which link, dBm vs % | **U** | the phone reads RSSI on the RIGHT arm every 10 s; BlueZ exposes RSSI only while a device advertises, so the PC-direct cell shows bars without a numeral. Values unmeasured |
| 5 | **Where system-state detail lives** — orphaned when the info popup was removed | design | app-layer phase |
| 6 | **Can a normal Android app see WEA/CMAS emergency alerts?** Must be tested before it is promised (§4.5) | **U** | Pixel 10a test |
| 7 | ✅ **Transport** — resolved 2026-08-25, **corrected 2026-08-31 (`HANDOFF.md` §19)**: the phone shell is the PRIMARY driver whenever the APK is up; the PC provides data (content + sync) and drives PC-direct BLE only when the APK is unavailable, handing the radio back when it returns | decided | §19 build |
| 8 | **Type legibility ON GLASS** — the assignments are locked and priced (§Type), but no render can answer whether they read at real angular size | measured / unproven | eyes on glass |
| 9 | 🆕 **The safe area** (§2.2b) — how much of the panel is actually visible on Adam's face | **U** | first-light ramp; the layout is written relative to it so only the value changes |
| 10 | **Per-window typefaces for windows not yet designed** — Files, Calendar, Music, SMS, Timers, Scout, Notices inherit Clear Sans until their app earns an override | design | app-layer phase |
| 11 | ✅ **The shell runtime** (§10.2) — **RESOLVED 2026-08-24: Kotlin/JVM**, one `:core` library shared by the desktop program and the Android APK (`IMPLEMENTATION.md`) | decided | built |
| 12 | ✅ **The transport ↔ shell protocol** — **RESOLVED 2026-08-24:** `wm.damage.core.transport.Transport` (`FlushRequest` of nominal ops → `TransportEvent`), also serialized over TCP with single-driver takeover (`IMPLEMENTATION.md`) | decided | built |
| 13 | 🆕 **Hat bridge power budget** — 1260 mAh running WiFi 6 + BLE for a workday (§10.6) | **U** | bench measurement |
| 14 | 🆕 **Does dual-band actually lift throughput?** If it does, it isolates a cause of the ~10× shortfall | **U** | build the hat |
| 15 | 🆕 **Cross-platform BLE parity** — macOS cannot set connection parameters at all (§10.7) | **U** | per-platform test |
| 16 | ✅ **The switcher chord — RESOLVED 2026-08-31, and the fault was OURS**: events 9/10 arrive unattributed (source 0) and the shell's §1 ring-only filter discarded them; both routes work since the fix (Adam: "it all works"). Event 10 fires on every touch-end; a deliberate ~1 s hold raises event 9 from either source | **M** | done; the §1 grammar note records the mechanics |

*(Retired 2026-08-17 by the switcher redesign: "is hold-plus-scroll comfortable?" and "what is the
long-press hold threshold?" — nothing is held any more, and no interaction is timing-dependent.)*

📍 **`REMINDER.md` carries the consolidated first-light checklist** — these items plus the ones in
`overview.md` §11, in the order to run them, with the dry-run and verification steps that must come
first. Start a fresh session there.

**On #7.** Beardos has a working BLE radio, but beardos is at *home* and the glasses are at
*work*, so PC-direct only ever works at his desk. The deployment transport is **PC composes →
Tailscale → phone → BLE → glasses**, which decides where the BLE stack lives, where RSSI comes
from, and where phone-side error notifications originate. **"Ack latency" in the status bar means
the wire ack (phone↔glasses)** — the number that prices frames.
