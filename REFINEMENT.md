# Refinement — the backlog after first light (2026-08-30)

> **Status 2026-08-31 — the queue is BUILT and live**, battery green at every step, deployed the
> same hour. **§1** chrome to the back of the ladder (bars inset to x 16–624, plane −2 at d+4
> capped 16 — `DESIGN.md` §2.2/§2.3/§3.1). **§2** per-app height (`DamageWindow.preferredHeight`,
> applied on focus commit, never preview). **§3a** folders (`BookMeta.folder`). **§3b** scroll
> (step 1–8, default 5; direction-gated acceleration ≤250 ms → up to 6×, off by default;
> `DESIGN.md` §0 reversal recorded). **§5** clock: top-RIGHT, digital seven-segment, 0.5 % ink,
> 174 B (`DESIGN.md` §1.5; sizes a Global setting since 2026-09-01). **§6** measured —
> `overview.md` §5.2: `ms ≈ 60 + bytes/50`, dense full-frame ≈ 2–4 fps (PC-direct; the phone
> path is ~70 ms + ~120 ms/KB — `REMINDER.md`, 2026-09-05). **§4** RESOLVED — the cause was our
> own source filter, confirmed on glass ("it all works!"). **§9** the 4× PC preview. **§10**
> brightness + glasses battery wired; ring battery CLOSED, probe reverted. **§11** the Reader batch.

Read `HANDOFF.md` §11 first for what first light established. This file is the record of the
2026-08-30/31 wave: Adam's list from wearing the glasses and driving them from the PC — each entry
something he asked for after using it, or a defect the session exposed. His summary: *"All the
limits I had to live with in G2CC are now removed."*

---

## 0. What he confirmed works, so nobody re-opens it

- Depth reads well on glass (one correction, §1).
- The height setting is right: 288 for regular use, 480 for reading.
- Reader works. Input works — scroll, tap and double-tap all arrive from the ring.
- Everything loads quickly; the type is *"pretty damn instant"*.

⚠ **Attribution:** the speed he praised is the **pixel path** — rasterise, RLE, deflate, mode-3
delta. The texture cache was NOT wired into the compositor at the time (`IMPLEMENTATION.md`).
Do not credit modes 13/14 for it; that would hide the cache's remaining headroom and misprice its
adoption. What the speed corroborates is the measured curve, `overview.md` §5.2.

---

## 1. Depth: the bars belong at the back

✅ Built 2026-08-31 — bars inset and on the back plane (`DESIGN.md` §2.2/§2.3/§3.1).

**Asked for:** title bar and status bar **as far back as depth allows**, not at the selection's
depth. Chrome had been sharing a plane with the selection and read as competing for attention;
Adam's standing rule (`DESIGN.md` §3, 2026-08-17) puts content far back and notifications/modals
forward, and chrome had never been placed. Correction: chrome joins the back; nothing moves
forward. The seam-geometry change along the bar edges was judged by `LensOracleTest`.

---

## 2. Height as a per-app setting

**Asked for:** the 288/480 choice **per app**, not global — Reader wants 480 and does not care
about the occluded bottom; regular use wants 288.

**Settled and built:** `DamageWindow.preferredHeight`, applied when a window takes FOCUS
(commit), never on a switcher preview. The safe-rect Layout stopped being a session constant, so
every surface survives it changing on a window change (`DESIGN.md` §2.2b/§2.4).

---

## 3. Reader

### 3a. Folders should be folders
✅ Built 2026-08-31 — `BookMeta.folder` (additive), folder rows with descend/ascend, the same
shape on all three providers (local, TCP host, cached remote).

**Asked for:** books in a subfolder **categorised within that folder** — flattening put the whole
Xanth series into the main list and made it unusable.

### 3b. Scroll is far too fine inside a book
**Asked for:** bigger notches; one line per scroll event is much too little. His preference
order: **smooth accelerated scrolling like the firmware's own**, else a **Reader setting for
lines-per-notch**.

⚠ **Reversal.** `DESIGN.md` §0 listed scroll acceleration as *deliberately excluded*; he asked
for it after using the thing, and **his use beats the earlier ruling** — recorded in `DESIGN.md`
§0 the way the long-press and silent-clock reversals were. Coarse steps (`CLAUDE.md`) are the
fix, not a conflict; the ~50 ms CFW ack also made acceleration cheaper than at 176 ms.

### ✅ Outcome (2026-08-31, on glass)

Both shipped. Adam tried the ramp and ruled: *"It's too jank for smooth scrolling, which is ok,
lets just default the scrolling to 5 lines per notch - configurable."* So: **default 5
lines/notch, acceleration OFF by default** (code and setting kept). Both rows live in the
Settings window's Reader category, not in the book's actions level.

### 🆕 Settings by category (asked + built 2026-08-31)

*"Organize the settings window by category. One category called Global for general/global
settings, then one additional category per app (which right now is just Reader so far)."*
Built: header landmarks ("GLOBAL", "READER"), non-interactive rest cells; apps contribute rows via
`DamageWindow.appSettings()` (the `HostSetting` contract); Reader contributes Scroll step /
Scroll accel / Size.

---

## 4. The switcher cannot be reached — diagnosed

**Reported:** long-press then double-tap does not open the switcher, nor with the "Long-press"
setting set to direct open. Adam's weighting: *"That's not a huge deal though."*

**Diagnosis (2026-08-30):** `HANDOFF.md` §11.4 — across a whole session event 9 (`LONG_PRESS`)
never arrived, while event 10 (`LONG_PRESS_RELEASE`) arrived after almost every swipe. Both
routes need event 9. Candidates: the ring's hold not reaching the patched dispatch site; a
different input subtype than the 3 the CFW hooks; a ring-side duration threshold; the press
consumed by the ring→glasses layer. Oddity recorded: the release hook checks lease AND UI mode
`0xE0`, the press hook only the lease — yet the looser-gated event was the missing one.
Discriminating experiment: hold a temple pad (raises event 9 since `a5d1c31`).

### ✅ The experiment ran (2026-08-31) — and event 9 WORKS on deliberate holds

Two temple holds plus "a few" ring long-presses → **five clean `LONG_PRESS (type 9)` events,
each with its release** (9/10 are unattributed, so the arithmetic, not the log, says the ring
fired). Surviving explanations for the zero-event-9 day: no qualifying ~1 s hold was made
(accidental presses are brushes that end early — the constant event-10s mean "a touch ended"), or
the ring's re-registration (§11.5) had not settled. Established: the arming event arrives on a
deliberate hold from either source, and is rare in normal use (zero across a whole day).

### 🔴 RESOLVED (2026-08-31, later): the real cause was OURS — the §1 source filter

Adam *"have yet to see the switcher at all"* in either mode. Cause, `Shell.handleInput`: the
ring-only rule discarded every gesture with source ≠ 2 — and **events 9/10 always arrive with
source 0, because `EventSource` is absent for them by firmware design.** Every real long-press
was dropped before the grammar ran; the transport log showing them arrive exposed the split.
`LongPressTest` had passed because its harness supplied 9/10 with the default ring source — it
now supplies **source 0, the wire truth**, and events 9/10 skip the source check (the
bare-long-press no-op keeps the temple harmless, as `CLAUDE.md` always said).

✅ **CONFIRMED on glass, 2026-08-31: "it all works!"** — both routes. Two polish items fixed the
same session (`DESIGN.md` §4.3): the wheel no longer drags the full screen width forward (the
list's full-width lens band had stayed in the plane map at 0; the wheel's centre band is now the
only forward region), and the drum gained an outer frame (a dimmer rule pair at the panel's top
and bottom — rules only around the centre read as a highlighted row).

---

## 5. The silent-mode clock

**Asked for (2026-08-30):** the graphic is **too basic for the size it occupies**, and should sit
**as far to the top and left as possible**.

**First pass, 2026-08-31:** top-left, redrawn analog (radial ticks, tapered hands, hub).

🔄 **Superseded the same day, Adam mid-session:** *"move the silent mode clock back to the top
right … all the way up and all the way right, and forget analog, make it good-looking digital
numbers … something quality, not like the very basic icons currently used on the main app."*
✅ Shipped: **top-right, flush to the corner, digital seven-segment** (`DESIGN.md` §1.5; 0.5 %
ink, 174 B); 2026-09-01 the SIZE became a Global setting (large / medium / small — small = the
title bar clock's cell). The analog drawing stays in `Icons.analogClock`, unused.

📌 From the same message: the Main-row **icons are "very basic" and will eventually be
upgraded** — the icon-quality pass moved to the front of the app wave (`HANDOFF.md` §20, one
drawn icon per app at 20 px + 56 px, still owed); 2026-09-01 theme icons landed as the personal
lane (Papirus-Dark at runtime, `DESIGN.md` §4.7), the drawn set the fallback and release path.

Judge it at true 1× (`design/render_shots.py`), never 2×.

---

## 6. Full-screen imagery

*"I can't wait to see how fast full-screen imagery is."* ✅ Measured 2026-08-31 from 1,488
journalled flushes — `overview.md` §5.2: `ms ≈ 60 + bytes/50`, dense keyframes ~200–270 ms,
2–4 fps (PC-direct; the phone path is slower — `REMINDER.md`).

---

## 7. Carried over, still open

`REMINDER.md`'s "still unmeasured on glass" table: chrome legibility on glass (8), the safe area
(1 — no calibrated safe rect; every reduced band is top-aligned for Adam's fit), ring fast-spin
coalescing (2 — per-notch delivery itself is resolved), comfortable disparity (3, with §1's chrome
plane and per-app depth behind it), the texture cache on glass (19–20). First light's "library
loading" question resolved itself: the library loads daily over that path.

---

## 8. Suggested order — executed as written (all six steps, 2026-08-31)

---

## 11. The Reader batch — asked + built 2026-08-31 (descenders · reset · chapters · images)

Four asks in one message, all shipped behind a green battery:

**Descenders no longer chop.** Measured cause: Alegreya's x-height normalisation lands the em at
20 px with ascent+descent = 28 rows, five more than the 24 px line box, and the scroll path
rendered each line into a buffer one box tall. Fix: face and size unchanged; line box 30 px,
baseline from real metrics, a loud layout-time guard refuses any document face that does not fit
its box. Verified through the scroll path in the lens-truth snapshots.

**Reset progress** — Settings → Reader → "Reset progress": tap resets one tracked book (double-tap
cancels). Resetting the OPEN book closes it so it counts as a first open again. Enabler:
`HostSetting.options` became a supplier (dynamic options), compat constructor for fixed lists.

**Chapter picker.** `Epub.Book` carries chapters — spine-document boundaries in reading-position
character space, titled from NCX/EPUB3 nav, then the document's first heading (never an image
token), then "Chapter N". First open of a book with ≥2 chapters lands on the picker; **row 0 is
"From the beginning" and double-tap ALWAYS backsteps** (Adam: no gesture ever means "start over").
A "Chapters" action row jumps there any time.

**Ebook images render in place.** `<img>`/SVG references become token paragraphs with bytes from
the archive (8 MB per-image cap, data: URIs skipped, all loud); an `ImageDecoder` seam
(AWT / BitmapFactory — core stays platform-free) decodes at layout; images are box-sampled to the
text column, quantized to the 16 levels (NO dithering), padded to whole line boxes and laid as
ordinary lines, so scrolling, slides and damage need no new machinery. Undecodable → a visible
`[image: name]` line. **Measured on the real shelf: 404 images across 57 books, 380 decode**; the
Frankenstein cover renders through the full pipeline in `03-reader-book`. Pinned by
`EpubChaptersImagesTest`; `--epub-check` reports chapters and image decode per book.

---

## 10. Brightness and the battery cells — asked + fixed 2026-08-31

*"The brightness setting and battery displays don't work."* Both were wired to nothing.

**Brightness:** `SettingsMsg.brightnessWrite` (faceclaw's exercised `buildSetBrightness` form —
sid 0x09, `f3={f1={auto[,level]}}`, the firmware's own nonlinear 0–100 scale), pushed on every
Settings step, once per session start (the firmware restores its own value otherwise), and across
the seam (`Ctl t="brightness"`). Verified on glass.

**Glasses battery:** the BARE device-info READ (`08 02 10 <msgId>` — G2CC §10's live-confirmed
form; ⚠ the f4-sub-request form returns no device-info block on the CFW) polled at start+5 s
then per minute, plus every unsolicited 09-01 update; f4: battery=12, charging=13 →
`TransportEvent.Battery` → the chrome G cell. Verified on glass: **79 %**, via an unsolicited
update before the first poll. Battery changes log.

**Ring battery:** ❌ CLOSED the same night (`HANDOFF.md` §19.3, `CLAIMS.md`) — the stock relay
never fills RingRawData, the ring offers no open battery path, Faceclaw does not read it either;
the probe apparatus REVERTED, the R chrome cell removed. **Phone battery:** only meaningful on the
phone path; blank PC-direct.

---

## 9. The PC preview window — asked 2026-08-31, ✅ DONE same day

**Asked for:** the desktop window *"should be way bigger, like four times its current size at
least."*

Done: `desktop/Preview.kt` draws integer-scaled, **default 4×** (640×480 → 2560×1920),
nearest-neighbour so every device pixel stays a crisp block; `-`/`=` adjust 1×–8×, auto-clamped
to the screen (both-lens 4× is wider than 4K, so that layout steps down and says so in the
title). This deliberately supersedes the preview's old strictly-1× rule, which was about *design
judgment* — that rule still governs `design/render_shots.py` and legibility calls.
