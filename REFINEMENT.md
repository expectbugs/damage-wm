# Refinement — the backlog after first light (2026-08-30)

> **Status 2026-08-31 — the queue is BUILT and live** (battery green at every step, deployed to
> the glasses the same hour): **§1** chrome to the back of the ladder (bars inset to x 16–624,
> plane −2 at d+4 capped 16 — `DESIGN.md` §2.2/§2.3/§3.1 revised). **§2** per-app height
> (`DamageWindow.preferredHeight`, applied on focus commit, never preview; Reader defaults to the
> full panel with a per-app "Size" row). **§3a** folders (BookMeta.folder, additive; folder rows
> + descend/ascend in Reader). **§3b** scroll (per-notch step 1–8 default 3, direction-gated
> acceleration ≤250 ms → up to 6×, both in Reader's actions; `DESIGN.md` §0 reversal recorded).
> **§5** clock: top-RIGHT, digital seven-segment, ✅ SHIPPED (0.5 % ink, 174 B — `DESIGN.md`
> §1.5; sizes became a Global setting 2026-09-01). **§6** measured — `overview.md` §5.2:
> `ms ≈ 60 + bytes/50`, dense full-frame ≈ 2–4 fps. **§4** ✅ RESOLVED — the experiment ran,
> the real cause was our own source filter, fixed and confirmed on glass ("it all works!").
> **§9** the 4× PC preview shipped first. **§10** brightness + glasses battery wired; ring
> battery later CLOSED with the probe reverted (see below). **§11** the Reader batch shipped.

**Read `HANDOFF.md` §11 first** for what first light established, then this. The flash, the
install and first light are all behind us; `REMINDER.md` §"Next" points here.

This is Adam's list, taken from his own account of wearing the glasses and driving them from the
PC, plus the analysis each item already has. **Nothing here is speculative feature work** — every
entry is either something he asked for after using it, or a defect the session exposed.

His summary, for the record: *"All the limits I had to live with in G2CC are now removed."* Depth,
the height setting, Reader, and the type all landed. What follows is the polish.

---

## 0. What he confirmed works, so nobody re-opens it

- **Depth reads well on glass.** The stereo ladder is right in principle — one correction in §1.
- **The height setting is right.** 288 for regular use, 480 for reading. Both useful, both wanted.
- **Reader works.**
- **Everything loads quickly**, and the type in particular looks *"pretty damn instant"*.
- **Input works** — scroll, tap and double-tap all arrive from the ring.

⚠ **One attribution to keep straight.** The type he is praising is the **existing pixel path** —
rasterise, RLE, deflate, mode-3 delta. **The texture cache is NOT wired into the compositor**
(`IMPLEMENTATION.md` says so, deliberately). Do not let anyone later credit modes 13/14 for speed
that the ordinary path is already delivering; that would hide how much headroom the cache has left
to give, and it would price the adoption work wrongly. What the speed *does* corroborate is the
~50 ms ack measurement in `HANDOFF.md` §11.1.

---

## 1. Depth: the bars belong at the back

**Asked for:** the title bar and the status bar should sit **as far back as depth allows**, not at
the same depth as the selection.

`DESIGN.md` §3's ladder is the authority and it needs revising: chrome is currently sharing a plane
with content selection, and on glass that reads as competing for attention. Adam's standing rule
(§3, his own words, 2026-08-17) is that main content sits as far back as comfortable and
notifications/modals come **forward**. Chrome is neither, and it was never separately placed.

Put the bars behind the content plane. Keep the direction of the ladder intact — the correction is
that chrome joins the back, not that anything moves forward.

**Watch:** the compositor reasons per lens and every plane change is a shadow-vs-truth difference,
so moving chrome to its own disparity changes the seam geometry along the bar edges. `LensOracleTest`
will judge it; expect the rect economy along the top and bottom bands to shift.

---

## 2. Height as a per-app setting

**Asked for:** eventually the 288/480 choice should be **per app**, not global. Reader wants 480 and
does not care if the bottom is occluded; regular use wants 288.

Today it is one calibrated setting (`DESIGN.md` §2.2b). Making it per-window means the safe-rect
Layout is no longer a session constant, so every surface must survive it changing when the window
changes — which is a real change to the layout contract, not a preference toggle.

**Settled and built:** the height is a property applied when a window takes FOCUS (commit),
never on a switcher preview — the simpler option won (`DamageWindow.preferredHeight`).

---

## 3. Reader

### 3a. Folders should be folders
**Asked for:** books in a subfolder should be **categorised within that folder**. Flattening
everything put the whole Xanth series into the main list and made it unusable.

The library provider currently returns a flat listing. This wants a real hierarchy: a folder is a
row, opening it descends, and the main list shows folders plus loose books. Applies to the local
provider, the TCP host and the cached remote listing alike, so the shape belongs in the provider
contract rather than in Reader.

### 3b. Scroll is far too fine inside a book
**Asked for:** bigger notches. One line per scroll event is much too little.

Preference order, his: **smooth accelerated scrolling like the firmware's own**, and if that does
not work well, a **Reader setting for lines-per-notch**.

⚠ **This runs into a standing decision.** `DESIGN.md` §0 lists scroll acceleration as
*deliberately excluded*, and `CLAUDE.md` says to scroll in coarse steps because cost is
ack-dominated. Neither of those forbids what he is asking for — coarse steps are exactly the fix —
but *acceleration* was previously rejected and he is now asking for it after using the thing. **His
use beats the earlier ruling.** Record the reversal explicitly in `DESIGN.md` §0 rather than
quietly contradicting it, the way the long-press and silent-clock reversals were recorded.

The measured ~50 ms ack latency also makes acceleration cheaper than it looked when the design said
no at 176 ms. Re-price before deciding.

**Fallback that should ship regardless:** the lines-per-notch setting. It is small, it is certain,
and it makes the book readable today.

### ✅ Outcome (2026-08-31, on glass)

Both shipped; Adam tried the ramp and ruled: *"It's too jank for smooth scrolling, which is ok,
lets just default the scrolling to 5 lines per notch - configurable."* So: **default 5
lines/notch, acceleration defaults OFF** (the code and its setting stay for anyone who wants the
ramp back). Exactly the §3b fallback prediction — the setting is the daily driver. Both rows
live in the **Settings window's Reader category** (his same-day ask, below), not in the book's
actions level.

### 🆕 Settings by category (asked + built 2026-08-31)

*"Organize the settings window by category. One category called Global for general/global
settings, then one additional category per app (which right now is just Reader so far)."*
Built: header landmarks ("GLOBAL", "READER") over the list; apps contribute rows via
`DamageWindow.appSettings()` (the `HostSetting` contract); Reader contributes Scroll step /
Scroll accel / Height. Headers are non-interactive rest cells.

---

## 4. The switcher cannot be reached — diagnosed

**Reported:** long-press then double-tap does not open the switcher. **And it still does not open
with the "Long-press" setting changed to enable the direct open.**

**This is already explained by the session log, and the explanation is not in the shell.**
`HANDOFF.md` §11.4: across a whole session of normal use, **event 9 (`LONG_PRESS`) never arrived
once**, while event 10 (`LONG_PRESS_RELEASE`) arrived after almost every swipe.

Both routes into the switcher need event 9 — the chord uses it to arm, and the Settings direct-open
uses it to fire. No event 9 means no switcher, by either path. **The shell is behaving correctly on
the events it is given.**

So the question is why the ring never produces event 9. Candidates, none yet tested:

1. The ring's long press does not reach the patched dispatch site at all, and what we see as event
   10 is the end of an ordinary touch rather than the end of a hold.
2. The ring reports a different input subtype than the 3 the CFW hooks.
3. A duration threshold on the ring side that a normal hold does not meet.
4. The press is consumed by the ring→glasses layer before the dispatcher sees it.

⚠ **One observation makes this genuinely odd and worth recording.** The *release* hook checks the
framebuffer lease **and** that the UI mode is `0xE0`; the *press* hook checks only the lease. Event
10 is arriving, so both of the stricter conditions hold — yet the looser-gated event 9 does not
appear. Whatever the cause, it is upstream of the lease and the mode.

**The cheap discriminating experiment:** hold a **temple touchpad**, which since `a5d1c31` also
raises event 9. If the temple produces event 9 and the ring does not, the cause is ring-side. If
neither does, it is the hook or the dispatch path. That single test splits the candidates in half
and costs nothing.

Adam's own weighting: *"That's not a huge deal though."* Treat it as a real defect with a low
priority, not as urgent.

### ✅ The experiment ran (2026-08-31) — and event 9 WORKS on deliberate holds

Adam held a temple pad twice and long-pressed the ring "a few times": **five clean
`LONG_PRESS (type 9)` events arrived, each with its release** — the count only adds up if the
ring fired too (events 9/10 are unattributed by design, so the log cannot name the source; the
arithmetic can). Against yesterday's zero-event-9 session, the surviving explanations:

1. **A qualifying hold was simply never made on 2026-08-30** — the stock threshold wants a real
   ~1 s hold, and accidental presses are brushes that end early (hence the constant event-10s,
   which mean "a touch ended"). Today he held deliberately, primed by the instruction.
2. The ring's re-registration (the §11.5 Even-app fix) had not fully settled during yesterday's
   session.

Neither is proven; what IS established: **the arming event arrives when a hold is deliberate,
from either source, and the chord grammar's premise holds** (the arming event is rare in normal
use — zero across a whole day of it).

### 🔴 RESOLVED (2026-08-31, later): the real cause was OURS — the §1 source filter

Adam kept trying and *"have yet to see the switcher at all"*, either mode. Cause, found in
`Shell.handleInput`: the ring-only rule discarded every gesture whose source ≠ 2 (ring) — and
**events 9/10 always arrive source 0, because `EventSource` is absent for them by firmware
design.** Every real long-press was thrown away before the grammar ran; the transport's log
(which shows them arriving) is what exposed the split. `LongPressTest` passed the whole time
because its harness injected 9/10 with the default ring source — the suite now injects them with
**source 0, the wire truth**, and events 9/10 skip the source check (the bare-long-press
no-op default is what keeps the temple harmless, as `CLAUDE.md` always said).

✅ **CONFIRMED on glass, 2026-08-31: "it all works!"** — both routes. Two polish items from the
same session, both fixed (`DESIGN.md` §4.3): the wheel no longer drags the full screen width
forward (the underlying list's full-width lens band stayed in the plane map at 0 while the wheel
was open — suppressed now; the wheel's centre band is the only forward region), and the drum
gained its outer frame (a dimmer fixed rule pair at the panel's top and bottom edges — with rules
only around the centre it read as a highlighted row, not a wheel).

---

## 5. The silent-mode clock

**Asked for (2026-08-30):** the graphic is **too basic for the size it occupies** — at that size it
should look considerably better. And it should sit **as far to the top and left as possible**.

**Built 2026-08-31 (first pass):** moved to the safe rect's top-left and redrawn (radial ticks,
tapered hands with tails, hub with pin).

🔄 **Superseded the same day, Adam mid-session:** *"move the silent mode clock back to the top
right … all the way up and all the way right, and forget analog, make it good-looking digital
numbers … something quality, not like the very basic icons currently used on the main app."*
So: **top-right, flush to the corner, digital, quality rendering** — ✅ shipped as the
seven-segment readout (`DESIGN.md` §1.5), and 2026-09-01 the SIZE became a Global setting
(large / medium / small — small = the title bar clock's cell). (The analog drawing stays in
`Icons.analogClock`, unused, in case it ever returns as an option.)

📌 **Recorded for later, from the same message:** the Main-row **icons are "very basic" and will
eventually be upgraded** — an icon-quality pass is future backlog, not this batch.

**Re-render at true 1× and look at it** — `design/render_shots.py`, never at 2×, which flattered
delicate work and misled several earlier passes.

---

## 6. Full-screen imagery

**Asked for, as anticipation rather than a request:** *"I can't wait to see how fast full-screen
imagery is."*

✅ Done 2026-08-31: the whole curve measured from 1,488 journalled flushes — `overview.md`
§5.2 (`ms ≈ 60 + bytes/50`; dense keyframes ~200–270 ms). The floor-to-curve step this section
asked for happened exactly as specified.

---

## 7. Carried over, still open

Everything in `REMINDER.md`'s first-light checklist that first light did not close — in particular
**legibility on glass (8)**, **the safe area (1)**, **per-notch scroll (2, now with §3b behind it)**,
**comfortable disparity (3, now with §1 behind it)**, and **the texture cache on glass (19–20)**,
which remains the gate on adopting cached glyphs.

*(The "library loading" question from first light resolved itself in practice: the library
loads daily over that path — folders, chapters and images all shipped through it the next day.)*

---

## 8. Suggested order — executed as written (all six steps, 2026-08-31)

---

## 11. The Reader batch — asked + built 2026-08-31 (descenders · reset · chapters · images)

Four asks in one message, all shipped behind a green battery:

**Descenders no longer chop.** Root cause, measured: Alegreya's x-height normalisation lands the
em at 20 px whose ascent+descent is 28 rows — five more than the 24 px line box held — and the
scroll path renders each line into a buffer exactly one box tall, so every scrolled line clipped.
Fix: the face and size he praised stay EXACTLY as they were; the line box grew to 30 px, the
baseline now comes from the real metrics, and a loud layout-time guard refuses any document face
that does not fit its box. Verified through the scroll path in the lens-truth snapshots.

**Reset progress** — Settings → Reader → "Reset progress": scroll the tracked books, tap resets
one (double-tap cancels, as everywhere). Resetting the OPEN book also closes it, so it counts as
a first open again (the chapter picker included). Enabler: `HostSetting.options` became a
supplier (dynamic options), with a compat constructor so every fixed-list row is untouched.

**Chapter picker.** `Epub.Book` now carries chapters — spine-document boundaries in the same
character space as reading positions, titled from the book's own NCX/EPUB3 nav, falling back to
the document's first heading (never an image token — caught in the first snapshot run), then
"Chapter N". First open of a book with ≥2 chapters lands on the picker; **row 0 is "From the
beginning" and double-tap ALWAYS backsteps** (Adam's caveat: no gesture ever means "start over").
A "Chapters" action row jumps there any time (back from that route resumes the page).

**Ebook images render in place.** `<img>`/SVG references become token paragraphs with bytes
captured from the archive (per-image 8 MB cap, data: URIs skipped, all loud); an `ImageDecoder`
seam (AWT / BitmapFactory — core stays platform-free) decodes at layout; images are box-sampled
to the text column, quantized straight onto the 16 levels (NO dithering), padded to whole line
boxes and laid as ordinary lines — so scrolling, slides, damage and offsets need zero new
machinery. No decoder / undecodable → a visible `[image: name]` line. **Measured on the real
shelf: 404 images across the 57 books, 380 decode** (the rest are placeholder-with-log formats);
the Frankenstein cover renders through the full pipeline in `03-reader-book`. Pinned by
`EpubChaptersImagesTest`; `--epub-check` now reports chapters and image decode per book.

---

## 10. Brightness and the battery cells — asked + fixed 2026-08-31

*"The brightness setting and battery displays don't work."* Both were wired to nothing.

**Brightness** now transmits: `SettingsMsg.brightnessWrite` (faceclaw's exercised
`buildSetBrightness` form — sid 0x09, f3={f1={auto[,level]}}; the firmware's own nonlinear 0–100
scale), pushed on every Settings step (§4.2's live preview, real now), once per session start
(the firmware restores its own value otherwise), and across the seam
(`Ctl t="brightness"`). Verified on glass: the configured value logs and lands.

**Glasses battery** now fills: the BARE device-info READ (`08 02 10 <msgId>` — G2CC §10's
live-confirmed form; ⚠ the f4-sub-request form comes back WITHOUT the device-info block on the
real CFW) polled at start+5 s then per minute, plus every unsolicited 09-01 update, parsed
(f4: battery=12, charging=13) → `TransportEvent.Battery` → the chrome G cell. Verified on glass:
**79 % read from the real pair**, arriving via an unsolicited update before the first poll even
fired. Battery changes log (the first-light observability rule).

**Ring battery**: ❌ CLOSED the same night (`HANDOFF.md` §19.3, `CLAIMS.md`) — the stock relay
never fills RingRawData, the ring offers no open battery path, and Faceclaw does not read it
either; the probe apparatus was REVERTED and the R chrome cell removed. Nothing fills anything.
**Phone battery**: only meaningful on the phone path; blank PC-direct, honest.

---

## 9. The PC preview window — asked 2026-08-31, ✅ DONE same day

**Asked for:** the desktop window showing what the glasses see *"should be way bigger, like four
times its current size at least."*

Done: `desktop/Preview.kt` now draws integer-scaled, **default 4×** (640×480 → 2560×1920),
strictly nearest-neighbour so every device pixel stays a crisp block — bigger, not smoothed.
`-`/`=` adjust 1×–8×; the scale auto-clamps to what fits the screen (both-lens 4× is wider than
4K, so that layout steps down and says so in the title). This deliberately supersedes the
preview's old strictly-1× rule, which was about *design judgment* — that rule still governs
`design/render_shots.py` and legibility calls, which stay at true 1× or on glass.
