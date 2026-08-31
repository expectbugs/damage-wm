# Refinement — the backlog after first light (2026-08-30)

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

**Design question to settle first:** does the height change on window *switch* (so the switcher
wheel itself lives at one height and the incoming window re-lays-out), or is it a property applied
when a window takes focus? The second is simpler; the first may look better.

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

---

## 5. The silent-mode analog clock

**Asked for:** the graphic is **too basic for the size it occupies** — at that size it should look
considerably better. And it should sit **as far to the top and left as possible**.

Both are `DESIGN.md` §4 work. The position change is straightforward. The quality change is a real
piece of design: it is the surface with the largest single graphic and the smallest ink budget
(measured at 0.2% in `design/render_shots.py`), so there is a lot of room to spend before it costs
anything.

**Re-render at true 1× and look at it** — `design/render_shots.py`, never at 2×, which flattered
delicate work and misled several earlier passes.

---

## 6. Full-screen imagery

**Asked for, as anticipation rather than a request:** *"I can't wait to see how fast full-screen
imagery is."*

Nobody has pushed a full keyframe on the CFW yet. This is also the measurement that turns
`HANDOFF.md` §11.1's ack figure from a floor into a curve, so it is worth doing early and
deliberately: time a full 640×480 keyframe and a large delta, and write both numbers into
`overview.md` §5 with a "measured on CFW" mark.

---

## 7. Carried over, still open

Everything in `REMINDER.md`'s first-light checklist that first light did not close — in particular
**legibility on glass (8)**, **the safe area (1)**, **per-notch scroll (2, now with §3b behind it)**,
**comfortable disparity (3, now with §1 behind it)**, and **the texture cache on glass (19–20)**,
which remains the gate on adopting cached glyphs.

Also unconfirmed from the session: whether the content host ever delivered the book list. Main was
showing **"library loading"** for a while. If that was a real stall rather than a slow first fetch,
it is a content-path defect and §3a will touch the same code.

---

## 8. Suggested order

1. **§3b lines-per-notch** and **§1 chrome depth** — both small, both immediately improve daily use.
2. **§5 clock position**, then its redraw.
3. **§3a folders** — the largest Reader change and the one that makes the library usable.
4. **§6 full-screen timing** — cheap, and it unblocks re-pricing everything.
5. **§4 the event-9 experiment** — one hold on a temple pad, then decide.
6. **§2 per-app height** — the biggest contract change; do it once the rest has settled.
