# Damage — a framebuffer window manager for the Even Realities G2

**Status: research phase CLOSED 2026-08-17. Nothing built yet. Next stage: the feature-creep
scope explosion** — start from [`CAPABILITIES.md`](CAPABILITIES.md), which is the inventory of
what the hardware can actually do, written for exactly that purpose.

What closed the phase: both BTSnoop captures recovered and re-decoded against Even's own 27
protobuf schemas; the CFW image verified reproducible offline byte-for-byte; Faceclaw's window
manager read for prior art; every load-bearing claim graded in `CLAIMS.md`; and the
two-month-old image-retention probe finally run on hardware (§1). What remains unknown is
concentrated on the far side of the flash and is listed explicitly at the end of `CLAIMS.md`.

This document is the complete carry-over from the research that produced the decision (2026-08-15).

> 📋 **Read [`CLAIMS.md`](CLAIMS.md) alongside this.** It grades every load-bearing claim here as
> vendor-authoritative / measured / corroborated / inferred / single-source / unknown. Four claims
> that sat in this document stated as fact turned out to be wrong within two days, each time
> because prose disagreed with working code. `CLAIMS.md` says which ground is solid, lists the five
> things most worth distrusting, and names what cannot be resolved before flashing.

> 🎨 **The shell is fully specified: [`DESIGN.md`](DESIGN.md)** (2026-08-17/18, ~1,850 lines).
> Ring-only input grammar · exact 640×480 cell geometry on the mode-3 quantization grid · the
> corrected depth layer order · motion, persistence and failure policy · the six shell surfaces
> (top bar, Main + settings, switcher wheel, status bar, notifications, content area) · the locked
> typeface assignments · and per-frame costs, now **measured from real renders** rather than
> modeled. It is the successor to G2CC's `docs/DE_DESIGN.md` and **wins on shell design**; this
> file still wins on facts.
>
> Two things exist alongside it that a fresh session must know about:
> - **`tools/lint.py` + `tools/geometry.py`** — the build gate from `DESIGN.md` §9.2b. 18 rules
>   (SYM/GEO/BUD/FID). `tools/lint.py --selftest` proves each one fires; `tools/lint.py` gates the
>   repo and currently exits 0. **Run it after any geometry or layout change.**
> - **`design/render_shots.py`** — renders every shell surface at **true 1× 640×480**, 4bpp
>   quantized, priced through the firmware's own RLE. Output in `design/shots/`. This is how ink
>   budgets and byte costs got measured; regenerate after any design change.

**Repo/package identifier:** `damage-wm` (the bare word "Damage" is the project name; the
qualified form keeps logs and issues greppable).

---

## 1. What we are building, and why

**Damage** is a from-scratch window manager and compositor for Adam's own Even Realities G2
smart glasses. It treats the glasses as a **dumb framebuffer**: the PC composes complete
scenes with real fonts and arbitrary layout, and pushes pixels. This requires **custom
firmware** (`g2flash`), which replaces the vendor's EvenHub container model with direct
framebuffer access.

It is the successor to **G2CC** (`/home/user/G2CC`), which is a *working, shipped* system —
Damage does not replace it on disk and G2CC keeps running until Damage earns the slot.

### Why this, and why now

G2CC originally *was* a framebuffer design. It pivoted away in commit **`709d18c`**
(2026-06-10, "tiles nixed for sessions — Adam's hardware verdict"). The commit message is the
whole reason:

> 15-20s taps with no feedback: every menu state change is an f1=7 rebuild and the renderer
> conservatively re-pushes ALL FOUR tiles per rebuild **(retention probe never run)** — a
> multi-second ack-gated tile storm per interaction.

The pivot to firmware text/menus bought "single ~62-86ms text/list writes" and made the system
usable — at the cost of every design ambition. Images were avoided everywhere because they cost
seconds. The custom firmware removes **every named cause of that failure**:

| what killed the original design | what the CFW does about it |
|---|---|
| `f1=7` rebuild on every menu state change | EvenHub layout system bypassed entirely — rebuilds do not exist |
| re-push all four tiles per rebuild | **dirty rects** — send only changed pixels |
| *(retention probe never run)* | ✅ **PROBE RUN 2026-08-17 — see below.** The re-push was not conservatism, it was required |
| 288×144 image-container cap forcing a 2×2 grid | one 576×288 surface (640×480 shadow) |
| no wire compression, ~13 KB/tile | zlib+RLE on the wire |
| ~1000 B multi-packet wall on layout frames | gone — not an EvenHub message |
| 12-container / 8-text / 4-image budget | gone |

### ✅ The retention probe, finally run (2026-08-17) — and it vindicates the whole decision

Commit `709d18c` killed the original framebuffer design partly on a *suspicion*: the renderer
re-pushed all four tiles on every rebuild, and the parenthetical **"(retention probe never run)"**
left open the possibility that this was needless conservatism — that images survived a menu change
and G2CC was simply throwing away throughput.

**Adam ran it on hardware, 2026-08-17. Result: on a menu change the image disappears entirely and
does not come back until it is redrawn.**

⇒ **Images are NOT retained across an EvenHub layout change. The re-push was mandatory, not
conservative.** The 15–20 s taps were not an implementation failure that better code could have
fixed — they were the firmware's container model working as designed.

This is independently corroborated by g2-kit's `containers.md`, which documents the same behaviour
from the other side: *"A Cmd=7 UpdateContainer whose inner container has multiple ImageObjects
atomically tears down the old object list and rebuilds. For images, this invalidates the tile
buffers — you have to re-push Cmd=3 UpdateImageRawData for every tile id before the frame will
render."* It further warns that a REBUILD *"can silently blow away sibling containers in the plugin
task."* Two independent sources, one of them our own hardware. **Graded M.**

Three consequences worth carrying forward:

1. **The pivot away from framebuffer-on-stock was correct**, and for a better reason than we knew.
2. **The CFW is not an optimisation, it is the only path.** Under stock EvenHub *any* layout change
   destroys image content, so no amount of clever damage tracking helps while firmware containers
   own the layout. Under CFW there are no containers to rebuild and the entire failure class is
   gone — which is precisely why the mode-8 batching thesis is viable there and was not here.
3. ⚠ **It also prices the fallback honestly.** If Damage stalls and we return to G2CC, this is a
   *hardware-confirmed* ceiling on that codebase, not a bug waiting to be fixed.

The restriction that makes `g2flash` **wrong for today's G2CC** — *"you cannot mix this mode
with EvenHub list or text containers"* — costs the framebuffer design **nothing**, because it
uses no firmware containers by definition.

### What this unlocks that G2CC could never have

- **Custom fonts** — real TrueType/bitmap faces, any size, real kerning, and **anti-aliased
  text at 16 gray levels** (the stock LVGL font is effectively 1-bit). Biggest single visual win.
- **Overlays / z-order** — the official app can pop a notification *on top of* content; G2CC
  could not, which is why notifications were crammed into the title bar. Real modal dialogs.
- **Arbitrary layout** — divide the canvas however you like; no region ids, no container budget.
- **Images everywhere** — thumbnails in a file manager, embedded images in mail/MMS, real game
  frames, a proper ebook reader. Every G2CC app was shaped by avoiding images.
- **Endless scroll** — see §5.
- **More input** — long-press and long-press-release become app events (§6).
- **Piezo buzzer** — audio feedback on a device with no speaker.
- **Wear/unwear detection, magnetometer compass, per-lens (stereo) output.**

### Adam's stated build methodology (follow this order)

> Heavy research → full documentation → clean repo → the main plan → a couple hundred
> ridiculous feature-creep scope explosions → heavy refinery to bring it back to reality →
> passes for consistency and adherence to the research/documentation → a final plan of the
> actual implementation via real code → **then** slowly and carefully start executing.

Bank the research and documentation **before** the scope explosion, so there is something solid
to refine back down toward. "Feature creep is my RELIGION" — the explosion phase is deliberate
and wanted; the refinery phase is what keeps it shippable.

---

## 2. Hard hardware facts (from our own reverse engineering — trust these)

Measured from Adam's own BTSnoop captures of his own glasses. Canonical source:
`/home/user/G2CC/docs/G2_BLE_PROTOCOL.md`.

| | |
|---|---|
| Display | 4 bpp, **16 levels of green** micro-LED. ⚠ **576×288 is the STOCK logical area, not the panel.** The physical panel is **640×480** and the CFW uses all of it — see the correction below |
| Lenses | **Right lens is where you SUBSCRIBE** (L is silent on async events). ⚠ But "drive Right" is wrong for image data under CFW — see the correction below |
| MTU | phone requests 247 / glasses answer 517 → **effective 247** (244 B ATT payload) |
| PHY | **1M only** — glasses reject 2M. No BLE 5.0 2M PHY |
| Connection interval | 15 → 30 → 90 ms; latency 0→4; supervision 5000–6000 ms |
| AA packet payload | ~**232 B** per fragment; median inter-fragment gap **~14 ms** |
| **Effective throughput** | ⚠ **7–13 KB/s measured end-to-end** (corrected 2026-08-17). The old **~16.6 KB/s** was 232 B ÷ 14 ms median gap — the *fast mode* of a trimodal distribution. See §5.1 |
| Image app-chunk | **≤ 4096 B** per `f1=3`; inter-chunk gap ~190–300 ms (official app) |
| **Image-push ack latency** | **median 176 ms** (range 117–180) — the dominant cost term |
| Framing | AA envelope; **CRC-16/CCITT-FALSE** over the *entire reassembled* payload, final packet only |
| msgId | **1 byte** — the glasses stop acking at 255 (~223 acked ops), then silence |
| Multi-packet wall | ⚠ **LAYOUT FRAMES ONLY** — see the correction directly below. **Does not apply to image data.** |
| Keepalive | `f1=12` every ~4–5 s. **`f1=9` = exit — never send it** |
| No | glasses mic/audio over BLE (disconnects >25 s), stock wear detection |
| ⚠ IMU | **NOT out of scope after all** — EvenHub has `Cmd 19/20`, `IMU_CtrlCmd{IMUReportEn, reportFrq}`, `IMU_Report_Data{double x,y,z}`, `OsEventTypeList.IMU_DATA_REPORT=8`. From Even's own schema (§9.1). Untested on our firmware |
| Physical | **the glasses have no power switch.** Case is the only power control |

**Our firmware:** glasses report **`2.2.2.20`** (`f5`/`f6` of the `09-20` type-2 response,
8-char field — `docs/G2_BLE_PROTOCOL.md:458`). Ring reports **`2.2.0.0014`**.

### 🔴🔴 THE CANVAS IS 640×480, NOT 576×288 (2026-08-17, from the CFW author)

**Source: James Babcock, by email, answering our question directly.** This is the single largest
factual correction in this document, and every layout number written before today is scoped to the
wrong surface.

> "The full 640x480 area is visible. You can lose part of the top or bottom to optical occlusion
> depending how the glasses sit on your face, but if they're centered right a full-screen UI can
> usefully use the whole thing."

We had it backwards. We assumed a 576×288 *panel* sitting inside a 640×480 *shadow*, with the
difference being off-screen margin. In fact **the panel is 640×480 and stock simply does not use
all of it.** The CFW source agrees and always did: `FW_DISPLAY_COPY` is described as "the stock
**576x288 → 640x480** packed copy," `FW_DISPLAY_FB` is "the stock copier's **640x480** destination,"
and `PANEL_W/PANEL_H` are 640/480 with `copy_panel()` moving all 153,600 bytes. 576×288 is the
**EvenHub container** geometry — a carrier, not the display.

| | old assumption | actual |
|---|---|---|
| usable surface | 576 × 288 | **640 × 480** |
| pixels | 165,888 | **307,200 (1.85×)** |
| raw 4bpp full frame | 82,944 B | **153,600 B** |

**Consequences, in both directions:**

- ✅ **We have 1.85× the canvas we were designing for.** Re-scope every layout.
- ❌ **Off-panel scratch does not exist** (old unknown #2 — resolved by having its premise deleted).
  Pre-render-then-flip-in via a zero-pixel mode-9 copy has nowhere to hide. See §12.
- ⚠ **Full-screen keyframes cost ~1.85× more.** §5's 6,704 B / 581 ms keyframe was measured on a
  576×288 scene; re-measure at 640×480. **Dirty-rect costs are unaffected**, which is the case that
  actually matters — one more reason the architecture is right.
- 🆕 **Safe-area rule:** usable extent is **fit-dependent** (his occlusion caveat). Keep load-bearing
  UI inside a centred safe area and treat the outer rows as bonus, never as required.

#### Refinement, same day — what the headroom is actually *for*

> "**The width headroom is used for a depth effect** (shift the eyes left/right independently to
> a[ppear] closer/farther). The height headroom has some bad reasons, but mainly it's just that
> **covering up too much FoV is annoying. Most Faceclaw UI is 640x288 for this reason**, but the
> Terminal app is full height so that it can fit a verbose Fable end of turn summary."

Two things that change how we should use the canvas:

1. **The 64 columns of width headroom (640 − 576) are the stereo-shift budget.** That is what §7's
   "lenses differ" flag spends. Render full-640-wide and you have spent your depth allowance on
   pixels. Not a hard rule — he uses full width himself — but it is a trade we did not know we
   were making.
2. **His own default is 640×288, not 640×480**, and the reason is one Adam holds independently:
   covering too much field of view is annoying. That is the same instinct behind setting display
   distance to FAR so the HUD stays ignorable at work and while driving (§7). Full height is a
   deliberate per-app choice (his Terminal), not the baseline.

⇒ **Working guidance: 640×288 default; 640×480 available when a specific app earns it; leave width
margin if that app wants depth.**

### 🔴 Two corrections to the table above (2026-08-17)

Both were assumptions that had hardened into "hardware facts." Both are load-bearing.

**1. The ~1000 B multi-packet wall applies to LAYOUT frames, not image data.** The original claim
(`G2CC/docs/DE_DESIGN.md:175`) is scoped: an **`e0-20` layout/launch frame** (`f1=0` / `f1=7`) past
~4–5 AA packets (~1000 B) gets no ack and no error; layout frames are ack-gated and hard-reject
above ~1000 B. Generalising that to "a single message" was wrong. **Image `f1=3` chunks are 4096 B
across ~18 AA packets and work** — that is the official app's own behaviour in our captures, and
Faceclaw ships 3800-byte fragments continuously.

⇒ Read literally, the old row would cap a mode-8 batch at 1000 B. The CFW permits ~153 KB.
**That single over-generalisation would have crippled the batching thesis this project is named
after.** Keep the ~1000 B limit in mind *only* when building CREATE/REBUILD layout frames.

**2. "Right lens is the one you drive" is wrong for CFW image traffic — the reference
implementation drives LEFT.** In Faceclaw, `ConnectionOptions.sendImagesToLeft = true` is a
hardcoded final; **all five image call sites pass it**, `writeMessage()` resolves
`writeAddress = isLeftArmMessage ? leftAddress : rightAddress` to exactly one address, and **no
image message anywhere is constructed with `leftArm = false`.** Control traffic — heartbeat,
settings, shutdown, audio, IMU — uses builders without that parameter and goes to Right.

The reference arm split is therefore **bulk pixels → LEFT, control + events → RIGHT**, which reads
as a deliberate optimisation: keep the ack/event link clear of image traffic. It fits
`zlib_glue.c`, which moved image handling into the deferred path precisely because "the
sync-completion path (`image_complete`) runs on only the RECEIVING lens, so doing the work there
leaves the other lens blank" — the firmware propagates cross-lens, so **either** arm may receive.

**Grade: strong, not proven.** This is inference from reading Babcock's code, not from a capture of
our own. It is genuinely decision-relevant (it decides whether a frame flush is one message or two,
and which link carries it), so **verify it with a two-arm capture at first light** — or ask him.
What survives unchanged: **subscribe to RIGHT for async events**; Left is silent.

---

## 3. Firmware landscape and version timeline

| version | what landed |
|---|---|
| **2.2.2.20** | **what our glasses run today.** Pre-compression |
| 2.2.4.34 | (archived; seen in CFW detect output) |
| **2.2.6.10** | **"Improved Even Hub graphics rendering and image delivery."** Paired with Even App 2.2.6 + **SDK 0.0.12: "Improve Image compression algorithm"** (LZ4). **This is the CFW base.** |
| 2.2.7.14 | the version the community performance numbers were measured on |
| 2.2.8.x | **localization/stability only — no graphics work.** Current stock as of 2026-08 |

**The ~3.5× platform jump happened in 2.2.6.10 + SDK 0.0.12 (July 2026):** 200×100 image
updates went from ~2 fps to ~7 fps. Our 2.2.2 predates it entirely.

### Reversibility (verified)

- **`g2flash` is write-only.** No firmware read-back/dump path exists. **We cannot image our
  running 2.2.2.**
- Even's CDN is content-addressed (`https://cdn.evenreal.co/firmware/<md5>.bin`) — not
  version-enumerable.
- **A public archive exists**: `SybilSight-webflasher` `public/firmware-updates/source-files/`
  holds **19 G2 images** — 2.0.1.14, 2.0.3.20, 2.0.5.12, 2.0.6.14, 2.0.7.16, 2.0.8.20, 2.0.9.20,
  2.1.1.8, 2.1.1.12, **2.2.0.24**, **2.2.4.34**, 2.2.6.10, 2.2.6.11(CFW), 2.2.7.14, 2.2.8.4,
  2.2.8.9, 2.2.8.10, 2.2.8.11, 2.2.8.11-runtime-fix — plus 11 R1 images including
  `r1/2.2.0.0014` (**our ring's exact version**).
- ⚠ **2.2.2 is NOT in that archive** (it jumps 2.2.0.24 → 2.2.4.34). **Leaving 2.2.2 is the one
  genuinely irreversible step.** Every *other* version is one SHA-pinned download away.
- **CFW itself is easily reversible**: an official-app OTA "will fully remove the custom
  firmware and restore stock behavior"; or flash an unmodified image with g2flash; or Faceclaw's
  "Uninstall firmware".
- **Flash 2.2.2 → CFW directly.** `g2flash` writes a whole EVENOTA container; no need to update
  to official latest first. *(Unverified: whether a cross-version jump from 2.2.2 is accepted —
  nothing in the flasher gates on version, but nobody has documented doing it.)*

### Brick risk (researched precisely — judged acceptable, decision made)

The bootloader programs the main app to `dst = preamble[0x14]`, `len = preamble[0]&0xFFFFFF`,
**with no bounds check**. Reaching the OTA flag `0x7FE000` clobbers it and the BLE-bond/KV NV
band; reaching MRAM end `0x800000` faults mid-erase → **permanent BLE-unrecoverable bootloop,
SWD-only recovery** (open the glasses, attach a debugger — practically dead).
`check_mainapp_fits_mram()` in `g2flash.py` is "the ONLY guard."

**This fires only on an ENLARGED image — and the CFW IS enlarged.** ⚠ *Corrected 2026-08-16; the
earlier claim that the patch set is length-preserving was wrong, and it was load-bearing for this
entire risk assessment.* Patch 19 of 25 appends **20,127 bytes** of injected blobs at offset
4301227, and patches 20–25 exist purely to clean up after it: main-app subheader payload size, TOC
entry size, **preamble length (low 24 bits)**, preamble CRC32, and component CRC32c ×2. Measured
from the archived images: stock 2.2.6.10 installed main image **3,523,364 B** → CFW **3,543,491 B**
(+20,127 — exactly the blob).

Safety therefore rests on three other things, not on length-preservation:
1. the patch set **does** bump the preamble length, so the bootloader programs the right count;
2. `check_mainapp_fits_mram()` validates it; and
3. **headroom** — CFW image end `0x007991C3` vs the OTA flag at `0x7FE000` = **~403 KB of slack**.

The SHA-256 pins on the stock download and the patched output still do their job: they prove you
got exactly the reviewed image. **Danger zone remains: writing our own patches that grow the main
app without bumping the preamble length.**

Second hazard: the c0/c1 OTA path has **no block index and no dedup** — re-sending an
already-written block double-advances the flash offset and corrupts. Hence
`--block-nak-retries` / `--component-retries`.

### ✅ The image is reproducible from sources we hold — verified offline (2026-08-17)

`research/verify_cfw.py` rebuilds the CFW from our local stock image and checks every pinned hash.
It needs no network and no glasses. **All checks pass:**

- local stock 2.2.6.10 **==** the base SHA-256 both patch sets pin
- g2flash's 25 patches **==** g2flash's own pinned output
- SybilSight's 28 patches **==** their pinned output **and** the archived `g2-2.2.6.11.bin`,
  byte for byte
- **no Thumb-bit defect** in either rebuilt blob (14 constant interworking branches, all Thumb —
  run against *our own rebuild*, not just the archived file)
- delta between the two CFW images: **15 bytes in 6 runs** — three ASCII version digits
  (`s200_v2.2.6.1`**0**→**1**, settings-reported version, product-test 0x24) plus their CRC fixups

⇒ The thing we would flash is **not a binary we downloaded and trusted**; it is one we can
regenerate and diff. **Re-run this before any flashing conversation** — it is free.

Mitigations that exist: `--stop-before flash` (full dry run, no writes), `--lens left|right|both`
(arms flash one at a time), an interactive "my warranty is void" prompt, and the webflasher's
stricter transfer protocol (explicit ACK per 4 KiB block, END verification per component,
ambiguous ACK timeout restarts the whole component rather than replaying a block).

---

## 4. The CFW display-mode contract (**the most important technical artifact here**)

Source: `g2flash` `patches/zlib_glue.c` header comment. Dispatch is on the image's own leading
bytes: `'BM'` = BMP, otherwise a small u8 mode. Custom modes **3/6/8/9 operate on the full
640×480 physical image** (packed 4bpp shadow); they bypass LVGL, serialize on the stock display
semaphore, and `display_copy_hook` copies straight into the physical framebuffer before panel
refresh.

| mode | payload | meaning |
|---|---|---|
| **6** | `[6][zlib(rle)]` | keyframe: seeds the persistent 640×480 shadow, then direct FB refresh |
| **3** | `[3][l/4][t/2][w/4][h/2][fid16][zlib(rle)]` | **dirty-rect delta** onto the shadow + refresh. **Requires a prior mode 6** |
| **9** | `[9][srcrect][dstrect]` | **rect-copy INSIDE the shadow** (uint16 coords). "Pairs with a delta (usually via mode 8) to scroll." **Moves pixels on-device, transmits none** |
| **8** | `[8][count][len16][submsg]…` | **multiple ops in ONE atomic message** — "e.g. scroll = rect-copy + delta". No nesting. For shadow ops 3/6/9 |
| **7** | `[7][sub]` | diagnostic overlay control: 0 = clear sticky flags, 1 = hide, 2 = show. Hidden by default |
| **5** | sub-dispatch on `src[1]` | kind 4 = buzzer tone sequencer (≤48 steps) |
| **10** | `[10][0\|1]` | compass/heading BLE forwarding. **No collision in practice — see below** |
| `'B'` | BMP | `load_bmp_fast`, direct 4bpp-nibble→8bpp (`nibble*17`) expand |

**Mode-3 addressing is QUANTIZED** — this answers old unknown #6. `left`/`width` are ×4 and
`top`/`height` are ×2, each a **single byte**, so: left ∈ 0…636 step 4, top ∈ 0…478 step 2, and
the box is bounds-checked against 640×480. The quantization is deliberate: multiples of 4 make
`left>>1` and `bw>>1` whole byte offsets, so each box row lands as a plain byte run with no nibble
shifting. **`fid` is a uint16 frame counter** and a fid still present in the last-16 ring is
**silently skipped, not rejected** — re-applying a delta out of order would corrupt the shadow.
Mode 9 uses full uint16 coords with no quantization.

**Mode 8's rect limit — corrected 2026-08-17.** An earlier version of this section said "mode 8 has
no practical rect limit; `CFW_RECT_MAX = 16` is a debug-overlay constant, *not* a batch limit."
The first half of that is right and the conclusion is wrong. `count` is a u8 and the size cap is
`118 + 320*480` ≈ 153 KB, and `CFW_RECT_MAX` really is only the overlay-outline array — **but there
is a second, real 16-shaped limit via a different mechanism.** Faceclaw:

```java
MULTI_RECT_MAX_RECTS = 6;
// Each rect consumes a distinct CFW frame id, and the firmware's duplicate-fid
// ring holds 16, so keep several batches of history within it.
```

**Every mode-3 sub-message inside a mode-8 batch burns its own `fid`**, and `recent_fids[]` is
`CFW_FID_RING = 16` deep. Overfill it across successive batches and a legitimate new delta can
collide with a still-remembered fid — which the firmware **silently skips**, not rejects (§4 mode
table). The reference implementation runs **6 rects per batch** to keep several batches of history
inside the ring. Treat 6 as the known-good working value and 16 as the hard ceiling on
*outstanding fid history*, not on rects per message.

Two more Faceclaw thresholds worth inheriting: `MULTI_RECT_MIN_PAYLOAD = 900` (don't bother
splitting when the single bounding box already compresses below this) and the rule that multi-rect
falls back to one bounding box whenever the split isn't actually smaller.

### ⚠ The stale-compositing-base hazard — the failure mode that eats mode-3 deltas

Undocumented here until 2026-08-17, and it is the exact way a damage-tracking compositor dies
silently. Faceclaw's `INCREMENTAL_FRAMES` flag is now set `true`, but its comment still records why
it was once disabled:

> *"the firmware-side display buffer is not always the previous frame (occasionally two frames
> back, apparently display-driver buffer swapping), so partial updates composite onto stale
> content per-lens."*

A mode-3 delta assumes the shadow holds frame N−1. If the driver hands back a buffer from N−2, the
delta composites onto the wrong base and the screen quietly diverges — **per lens**, so the two eyes
can disagree. `zlib_glue.c` states this is fixed at the source: the worker now runs on a per-frame
**snapshot** drained in order by `image_deferred` rather than the live reconstruction buffer, "so
successive deltas compose onto the shadow in the right order." The flag being re-enabled is
consistent with that fix having worked.

⇒ **Do not treat delta correctness as free.** The CFW's diagnostic flags (`f_reorder`, `f_skip`,
`f_dup`, `f_snap_of`, surfaced by **mode 7 sub 2**) exist precisely to detect this class of
divergence, and they cost nothing to leave on during bring-up. **Damage should keep the mode-7
overlay enabled until the delta path is trusted**, and treat any set flag as a hard error rather
than a curiosity — that is what NO SILENT FAILURES means here.

**Keepalive is handled for us** (answers old unknown #4): `image_worker()` calls
`FW_KEEPALIVE_RESET()` on **every** top-level image message, hitting the same leaf the stock
sid-0x0c heartbeat handler uses. A steady mode-3/6/8/9 stream keeps the EvenHub context alive on
its own, with no interleaved heartbeat needed.

- **RLE applies to modes 3 and 6 ONLY** — pixels are RLE'd first and the **RLE stream** is what
  gets deflated (`zlib(rle(px))`, not `zlib(px)`).
- **HIGH BIT of the mode byte = "lenses differ."** Mode 3: two boxes (L then R, **same size**)
  **sharing one zlib payload** — a stereo *shift* without duplicating pixels; each lens draws at
  its own box. Mode 9: two rect-sets. See §7.
- Capability string advertised by current g2flash (`patches/settings_ext.c:325`, verified
  2026-08-16 — was `/6` in earlier notes):
  **`EVENCFW/8 img576 img640 imgz rle wakelease directfb fbguard wearnotify compass10`**

### 🔑 The architectural consequence — mode 8 batching

**The ~176 ms ack floor is per *message*, not per *rect*.** Mode 8 batches multiple ops into one
atomic message. A compositor with proper damage tracking — clock tick + status icon + a content
line + a notification — flushes **all of it in one round trip**, not four.

That inverts the cost model: it is not N regions × 176 ms, it is **176 ms + total compressed
damage bytes, once per frame**. **A damage-tracking compositor with a single flush per frame is
the design the firmware is asking for**, and it is what makes a real multi-window WM viable.
This is the single most important design insight in this document — and the project's name.

### 🔴 4.1 The direct-framebuffer LEASE is mandatory (discovered 2026-08-16)

**This was missing from every earlier version of this document and it is a hard requirement.**
`directfb` is not free — `display_copy_hook` only preserves our frame while a **volatile
90-second lease** is held. Source: `g2flash/patches/settings_ext.c`, confirmed against Faceclaw's
`FaceclawBleCommunicator.java`.

```c
if (ctx && ctx->direct_active) {
    if (deadline != 0 && (int32_t)(deadline - FW_MS_TICK) > 0) return;  /* keep our frame */
    ctx->direct_active = 0;                       /* lease expired -> fail open to stock */
}
FW_DISPLAY_COPY();                                /* stock LVGL repaint clobbers us */
```

The lease is armed over a **private control channel on sid 0x09**, as protobuf **field 101**:

```
field 101, bytes = ['F','C', version=1, op, nonceLo, nonceHi]
  op 1 ACQUIRE/RENEW     op 2 RELEASE        op 3 WAKE_CLAIM     op 4 WAKE_READY
  op 5 FB_ACQUIRE        op 6 FB_RELEASE     op 7 WEAR_QUERY
```

- **Lease lifetime is 90 s; Faceclaw renews every 45 s** (`FACECLAW_WAKE_LEASE_RENEW_MS = 45_000`).
- **Must be sent to BOTH arms** — `display_copy_hook` runs per-lens. Faceclaw enqueues right then
  left and waits for both.
- The framebuffer lease (5/6) is **independent** of the wake lease (1/2). Damage needs 5/6.
- ⚠ **Fail-open is the design**: if we stop renewing, stock repaints resume and our screen is
  silently overwritten. This is a *correctness* requirement, not an optimization.

**Capability detection** is the same channel, **field 100** on the sid-0x09 settings READ
response: a string starting `EVENCFW/`. Tag 100 is above the stock fields (1..19), so stock
decoders skip it — meaning **detection needs no timeout-based probing** (it satisfies our
NO TIMEOUTS rule by construction). Faceclaw requires the tokens `img640`, `fbguard`, `wearnotify`.

**Frame-id discipline:** Faceclaw advances the mode-3 `fid` by 1 per delta and keeps it in
**`[1, 0xFFFE]`**, avoiding the CFW's `0xFFFF` "empty" ring sentinel. Damage must do the same.

### 🔴 The carrier layout still needs a dummy TEXT container

g2flash's README says to "create a layout with a single 576×288 image container." **Taken literally
that is wrong and would cost days.** `BleProtocol.buildCreateMixedImagePage` actually emits:

```
ContainerTotalNum = 1 + tiles.length
field 3 = TextObject  "dashboard", 0,0, 576x288, content " ", isEventCapture = TRUE
field 4 = ImageObject(s)          e.g. "img00", id 10, 0,0, 576x288
field 5 = widgetId 10000
```

A **full-screen invisible text container holding a single space.** So G2CC's hard-won rule —
*"every page needs a text region; image-only layouts ack but never paint"* (§7) — **survives intact
under the CFW**, and that dummy widget doubles as the page's one required `isEventCapture` antenna.

This also closes a loop with the framebuffer lease above. `settings_ext.c` says the lease is needed
"while its EvenHub layout contains **swipe-capturing stock widgets**" — *this dummy text container
is that widget.* The layout requirement and the lease requirement are **the same fact seen from two
ends**: you must have a stock text widget present, and its repaints are exactly what the FB lease
suppresses. Neither can be dropped independently.

The image container is `576×288` **on purpose**, not because that is the surface size — that
carrier gives the firmware two 165,888-byte allocations, and the CFW reuses buffer A for its
640×480 packed-4bpp shadow (§4). Faceclaw also sends a periodic `TextContainerUpgrade`
(`ContentOffset=0, ContentLength=1, Content=" "`) against that container.

### canvas480 — the openCFW/SybilSight alternative

A **576×480 packed-4bpp virtual canvas (138,240 B) held on-device**, while stock scanout
deliberately stays 576×288 (they refused to touch the display config — that would read past the
proven buffer). The phone **pans** the 288-row viewport through `viewportY = 0…192`.

```
keyframe  [10][0][viewportY LE16][576 LE16][480 LE16][zlib(rle(packed4bpp))]
pan       [10][1][viewportY LE16]          <- "A pan retransmits no pixels."
release   [10][2]
```

Top-down, high nibble first, **288-byte row stride**. The keyframe is decoded into a fresh
allocation and swapped only after full zlib/RLE validation; a malformed frame preserves the
previous canvas.

**Trade-off vs mode 8/9:** canvas480 pans are ~5 bytes and buttery, but bounded to 192 rows
before a full re-seed (~850 ms hitch). Mode 8/9 shift-and-fill is **unbounded** — true endless
scroll.

### ⚠ The mode-10 "collision" is not real, and canvas480 is not an option for us

*Corrected 2026-08-16.* **There is no shipped or offered CFW containing canvas480.** The only CFW
the SybilSight webflasher installs is **2.2.6.11**, and it is g2flash commit `877c8d9`'s output
**byte-identical** plus exactly **three version-string patches** (30 bytes of ASCII: package
identity `s200_v2.2.6.10`→`.11`, the settings-reported version, and the product-test `0x24`
version). Its `cfw_patches-2.2.6.11.json` pins `g2flash_output_sha256` equal to g2flash main's own
output hash. Its capability marker ends **`compass10`** — i.e. g2flash's semantics for mode 10.

canvas480 exists only in openCFW's **unreleased** `g2/releases/g2-2.2.6.12/`, which forks from the
**older** g2flash commit `d5eb48dd` ("the last pinned revision before Faceclaw's wake-lease
patches") and advertises `EVENCFW/4 img576 imgz rle xordelta stereo canvas480` — note what is
**missing**: no `img640`, no `directfb`, no `fbguard`, no `wearnotify`. It predates the
direct-framebuffer work entirely.

⇒ **canvas480 is not a superset of the 2.2.6.11 line, it is a fork off an older base.** Choosing it
would mean giving up the 640×480 shadow and the `display_copy_hook` direct-framebuffer path — which
*is* Damage's architecture. **Decision: we are on the 2.2.6.11 / g2flash-main line. canvas480 is
closed as an option**, and the "pick a branch or merge them ourselves" item is retired.

---

## 5. Measured and modeled performance numbers

**Pricing formula used throughout:** `ms ≈ bytes / 11000 × 1000 + 176`
*(corrected 2026-08-17 — was `/16571`; see §5.1. Numbers below the correction line use 11000.)*
(our measured throughput + our measured ack latency, both from stock 2.2.2 captures).

### What G2CC's design costs today (the baseline we're beating)

G2CC tiles are 240×111 (content pane 480×222, 2×2). Packed 4bpp = **13,438 B/tile** = 4 chunks
at `MAX_IMAGE_CHUNK=4096`, each ack-gated:

- per tile ≈ **1,515 ms** · **4-tile screen ≈ 6.1 s** (0.16 fps)

### What the CFW does to the same screen

Measured with a representative full-screen 576×288 G2CC-style scene composed with real DejaVu
TrueType. ✅ *Corrected 2026-08-16: the script was **not** lost — it is checked in at
`research/fbfeas.py`, alongside `research/lz4bench.py` and `research/rlinks.py`.*

**Re-measured 2026-08-17** after two corrections: `research/fbfeas.py` now implements the
firmware's **real** RLE token format (it previously modelled a byte-level RLE — see below), and
throughput is repriced at the measured 11 KB/s rather than the optimistic 16.6 (§5.1).

| | bytes (old model) | **bytes (real RLE)** | time |
|---|---|---|---|
| raw 4bpp full screen | 82,944 | 82,944 | — (exceeds stock caps) |
| **CFW full-screen keyframe**, `zlib(rle(4bpp))` | 6,704 (0.081×) | **5,702 (0.069×)** | **694 ms** |
| **CFW dirty rect — menu cursor move** | 334 | **294** | **203 ms** |
| CFW dirty rect — one text line repaint | 2,726 | **2,038** | **361 ms** |
| stock LZ4 full screen (no CFW), for comparison | 9,253 | 9,253 | 1,017 ms |

203 ms is ~176 ms ack + transfer ⇒ **still at the protocol floor, not bandwidth-bound.** The
headline result survives both corrections: a cursor move costs ~294 B against a 4-tile screen's
13,438 B/tile.

### ✅ Antialiased text is cheaper than we modelled — and the RLE fix is why

The old `fbfeas.py` used a **byte-level** RLE (runs of identical *packed bytes*, always 2 bytes per
token). The firmware's actual encoder (`zlib_glue.c`) is **nibble-level** with a **1-byte short
token** `[cnt4|color4]` for runs of 1–15, escaping to 2- and 4-byte forms. Fixed 2026-08-17 and
**verified by round-tripping 301 cases through a faithful port of the firmware's own decoder**,
including the 16-bit escape path.

| | old model | real RLE | gain |
|---|---|---|---|
| full screen | 6,704 B | 5,702 B | 1.18× |
| cursor rect | 334 B | 294 B | 1.14× |
| **one text line** | 2,726 B | **2,038 B** | **1.34×** |

**The largest gain is on the text line — which is the antialiased case.** That is the expected
direction: AA turns glyph edges into short runs, and the firmware spends 1 byte where our model
spent 2. So Babcock's warning that zlib+RLE "doesn't play nice with antialiased fonts" is real but
**we had been over-estimating its cost, not under-estimating it.**

⚠ Note these numbers *are* AA-inclusive and always were: `fbfeas.py` renders with PIL TrueType onto
an `"L"` image (antialiased by default) and maps 0–255 → 0–15, so AA survives as real gray levels
into the 4bpp pack. We are not about to be surprised by AA cost — but see §11 #2, because texture
caching is still the thing that makes AA text cheap at *scale*.

## 5.1 🔴 Throughput is ~10× under spec — analysed from the recovered captures (2026-08-17)

Babcock, by email, independently and without our numbers:

> "(There is something wrong with the BLE link config or something slow in the firmware that I
> haven't found yet; the spec sheets say 1Mbit but actual throughput is more like a tenth that.
> **Debugging that would make a much bigger difference than compression tuning.**)"

1 Mbit = 125 KB/s. A tenth is ~12.5 KB/s. Our measured band and g2-kit's ~8.8 KB/s both sit there —
**three independent parties hitting the same wall, and the firmware author does not know why.**

⚪ Note this is a **different kind of problem from ack latency** (§11 #1), which is a constant to be
measured and doesn't change any decision. A 10× throughput shortfall is a suspected **defect** —
potentially *fixable*, and fixing it improves every number in this document at once. That is what
makes it worth chasing when latency wasn't.

### What the captures show (`captures/imagestatus.log`, handle 65 = R lens)

**Image-fragment spacing is trimodal**, not the single ~14 ms our docs assumed:

| gap | count | meaning |
|---|---|---|
| **0–1 ms** | 60 | back-to-back — multiple full-size packets in ONE connection event |
| **12–17 ms** | 85 | one connection interval apart |
| **56–61 ms** | 45 | ~4 intervals, radio idle |

Measured end-to-end across multi-fragment bursts: **7–13 KB/s**; best instantaneous 240 B / 13 ms
≈ 18.5 KB/s. The old 16.6 KB/s figure took only the fast mode and is ~30–50 % rosy.

### Four hypotheses tested and REFUTED

- ❌ **One write per connection event** — 60 gaps at 0–1 ms prove multiple packets per event happen.
- ❌ **Controller buffer-credit exhaustion** — `HCI Read Buffer Size` reports **ACL max 1021 B,
  12 packets**; peak outstanding on handle 65 was **4**. Never within 8 buffers of the ceiling.
- ❌ **Radio contention between the three links** — across 90 stalls, handles 64 (L lens) and 66
  (ring) produced **13 events total**. They are silent while the R lens stalls.
- ❌ **Ack-gating** — real, and it *is* what the 45–75 ms band looks like for small control messages
  (as §9 documents). But inside runs of 240-byte image fragments the slow gaps contain **nothing**:
  78 NOCP bookkeeping events, and one gap with literally nothing at all.

### What it actually is

**Half of the ≥40 ms stalls resumed with ZERO packets outstanding.** Everything acked, buffers
free, radio idle — and the host still waited ~60 ms before handing over the next fragment.

⇒ **The bottleneck sits above the controller and below the app's ack logic. The radio is ready and
the host is not feeding it.**

### 🔑 The lead worth sending upstream

**The official app never issues an `LE Connection Update` for the display link.** In both capture
segments, handle 64 (L lens) gets `min=30 max=50 ms latency=0` and handle 66 (ring) gets
`min=90 max=105 ms latency=4` — explicit, deliberate. **Handle 65, which carries every display
byte, gets none.** It runs on whatever it landed on at connect and nobody ever asks it to go faster.

And this meets Faceclaw where it is stuck. It *does* call
`requestConnectionPriority(CONNECTION_PRIORITY_HIGH)` on every connection — so the obvious fix is
already in — but `FaceclawBleCommunicator.java:1309` says:

> `// requestConnectionPriority has no callback in this Android compile target, so there is`
> *(…no way to confirm it was granted.)*

**He requests high priority and cannot verify it took effect. A BTSnoop capture answers exactly
that** — the `LE Connection Update` and its resulting interval are right there in the HCI log.
That is a verification path he does not currently have, and it is precisely "something wrong with
the BLE link config."

### ⚠ Honest limits

- **Handle 65's actual connection interval is NOT in this corpus.** Both captures start mid-session
  for it. This is the one gap that matters and it has a cheap fix: **start BTSnoop before
  connecting the glasses** so the R-lens connect lands in the window (`captures/README.md`).
- HCI alone cannot separate the ~60 ms host stall into Android-stack scheduling, app write
  cadence, or **BT/Wi-Fi coexistence** on the phone's combo radio — all three produce this
  signature, and coexistence is invisible at this layer.
- Removing the stalls alone gets ~1.5–2×; the 12–17 ms per-fragment floor is the larger gap to
  125 KB/s. Both would need to move.

### LZ4 ratios on our real content

Measured on 14 real FF1 NES frames from `/home/user/G2CC/games/ff1/bridge/spike_out/`, scaled to
240×111 packed 4bpp:

- **13 of 14 compressed to a single chunk**; ratios **0.13–0.24×** (one boot screen at 0.39×)
- flat black 0.01× · flat-shaded UI 0.01–0.16× · photographic screenshot **0.64×** · random
  noise **1.00× (expands)**
- ⚠ **Floyd-Steinberg dithering roughly halves the benefit** (0.24× → 0.34×). Do not dither;
  the extra entropy costs more than the visual gain. G2's own guidance agrees.

### Scroll cost — and the design rule it forces

576 wide at 4bpp = **288 B/row**. RLE+deflate on text ≈ 0.08–0.15×:

| scroll step | new pixels | time |
|---|---|---|
| 1 row | ~25–45 B | ~178 ms |
| 20 rows | ~460–860 B | ~210 ms |
| 40 rows | ~920–1,700 B | ~250 ms |

**It is ack-dominated, not bandwidth-dominated.** A 40-row jump costs barely more than a 1-row
nudge. ⇒ **Scroll in COARSE steps.** One ring notch = one text line or several, never pixels.
Fine-grained smooth scrolling buys nothing and costs a round trip per row.

### Endless scroll — solved primitive

`mode 8 { mode 9 shift + mode 3 fill }` is the classic terminal scroll: shift existing pixels
on-device, transmit only the newly exposed strip, atomically. **Content length is irrelevant** —
you are a viewport over server-side content. Unbounded, no re-seed. Good for a Reddit-style
feed, a terminal, and an ebook reader.

### The community's numbers, for calibration

| source | claim | verdict |
|---|---|---|
| `nickustinov/even-g2-notes` `docs/performance.md` (fw 2.2.7.14, SDK 0.0.13) | `ms ≈ 104 + 0.0039 × gray4Bytes`; **200×100 = 7.0 fps**, 288×144 = 5.4, 20×20 = 9.5 ceiling; multi-container is serial+additive (1=9.0, 2=4.5, 4=2.3 fps) | best available post-LZ4 data; explicitly **call rates, not verified on-glass paint rates** |
| G2oom (u/Obliviux, real hardware, pre-LZ4) | **~0.5 s/frame at 200×100 = 2 fps.** "No amount of clever coding changes this." | true at the time; pre-compression |
| `even-g2-matrix` (real hardware) | **1–2 fps** effective at 200×200, with an explicit warning its demo GIF is the **simulator** | corroborates |
| `flappy-g2` README "~10 FPS at 200×100" | **a hardcoded `FRAME_MS = 100` constant**, `setTimeout` *after* `await sendFrame()`, silent error swallowing, SDK v0.0.7, simulator-only run instructions | **not a measurement** |

**⚠ Lesson: every high-fps claim traced back to the simulator. Always ask "sim or glass?"**

### Two speedups available on STOCK firmware (no CFW)

1. **Sliding-window pipelining.** `g2-kit`'s `ImageStreamer` keeps **4 fragments in flight**
   instead of ack-gating each; `g2flash`'s `video-bench.ts` exposes `G2_WINDOW` (default 2,
   1=serial). G2CC's `G2Renderer` is strictly serial — this is free throughput. Their tolerance
   rule: slide forward through up to ~3 consecutive missed acks; rebuild/reset beyond that.
   *(Their published throughput table — 1 tile ~20 fps, 8 tiles ~5 fps, "~8.8 KB/s ceiling" — is
   **internally inconsistent**: 8 × 288×144 at 4bpp and 5 fps is ~830 KB/s, not 8.8. Treat the
   technique as proven, the numbers as unverified.)*
   **Third data point (2026-08-17):** Faceclaw ships `WINDOW_SIZE = 3` *on CFW*, noting it
   "requires firmware that accepts pipelined image messages (the CFW snapshot/deferred FIFO)."
   So pipelining is proven on exactly our target path, and 3 is the known-good depth.
   ⚠ Its ack-miss tolerance is `MAX_CONSECUTIVE_ACK_TIMEOUTS = 8`, versus g2-kit's ~3 —
   unexplained divergence, low stakes, but do not treat either number as authoritative.
2. **Stock LZ4** (`CompressMode=2`) — see §8.

---

## 6. Input

Source: `g2flash` `patches/gesture_fwd.c`. Input dispatcher `FUN_004424a2` turns each gesture
into a UI event code posted via `FUN_0045fc80(ctx, code, data)`.

- **Source byte at `0x2034dc30`: 0/1 = left/right temple touchpad, 4 = R1 ring.**
  ⇒ **per-source gesture discrimination is available** — long-press-on-left-temple,
  -on-right-temple, and -on-ring can be three distinct events. Input vocabulary can go well
  past five gestures.
- **long-press = subtype 3.** In EvenHub it calls `FUN_0046a644`, the **"End this feature?"
  dialog**. g2flash **replaces that call** ⇒ emits SysEvent **9** `RING_LONG_PRESS_EVENT`.
  Gated on `source == ring`. A touchpad long-press in EvenHub now does nothing. **DONE/proven.**
- **ring release-long-press = subtype 0xe** → `FUN_0045fc80(ctx, 0x4a, coords)`, which the
  EvenHub UI handler **drops** (no 0x4a case). g2flash intercepts ⇒ SysEvent **10**
  `RING_LONG_PRESS_RELEASE_EVENT`. Enables true press-and-hold / hold-to-confirm. **DONE/proven.**
- Scoped to **EvenHub foreground** (`app == 0xe0`); outside it, stock behavior is byte-for-byte
  unchanged.
- **double / both-temple long-press → stock Silent Mode: NOT patched by anyone.** A system-level
  gesture outside the EvenHub branch. Ours to write if we want it. Adam says it never fires
  accidentally — **recommend leaving it as a hardware escape hatch** in case our own software wedges.
- ⚠ **Killing the dialog kills the only stock way to quit an app.** Damage must provide its own
  quit path (Faceclaw does exactly this, plus a 10 s keepalive).

### 🔑 6.1 Per-notch scroll — G2CC's boundary-only limitation does not carry over

**G2CC could only see scroll at the ends.** `WINDOW_API.md` is explicit: `onContentScroll?(dir)` is
a *"scroll-notch **boundary** event"*, fires only in `fullBleed` + `scrollContent` text mode, and
**"never fires outside fullBleed."** Everywhere else the firmware list widget owned selection and
you got `List_ItemEvent{CurrentSelectItemIndex}` on *selection*, never per notch.

**The cause was the widget, not the gesture.** A firmware text container with pre-paginated content
taller than its viewport *is a scrolling widget*: it scrolls internally and only notifies the host
when it cannot scroll further. G2CC was reading the ends of a scroll the firmware was performing.

**Under the CFW carrier layout that inverts.** The event-capture container is a full-screen text
container whose content is a **single space** (§4.1). One space in a 576×288 region can never
scroll internally ⇒ **every scroll gesture is immediately at the boundary, so every notch is
delivered.** The limitation is not lifted; it becomes universal and therefore useless as a limit.

⚠ **Be precise about where the win comes from: the CFW does not patch scroll at all.**
`gesture_fwd.c` touches exactly two things — long-press (subtype 3) and ring release-long-press
(subtype 0xe). Scroll handling is byte-for-byte stock. What changed is that we stopped using a
scrollable firmware widget. **This was a consequence of the framebuffer layout, not of the
firmware** — which means it was arguably available to the original G2CC framebuffer design too.

**Evidence (graded C — corroborated, not measured by us):** Faceclaw's `DashboardInputEvent`
carries `{type:"scroll-up"}` / `{type:"scroll-down"}`, and its apps consume them as per-notch
deltas — `file-browser.ts:274` and `launcher-app.ts:206` both do
`const delta = event.type === "scroll-down" ? 1 : -1` against a list index. The clincher is
**pinball**: *"click flips both flippers, scroll-up/down flips left/right individually."* That is
unplayable on boundary-only events.

### 🆕 A second, independent input path: the ring's own BLE link

Faceclaw also connects to the R1 ring over **its own link** and decodes gestures host-side,
bypassing the glasses' UI layer entirely (`FaceclawRingEventDecoder.java`). Two frame shapes:

```
11-byte "charger" gesture:  00 09 61 00 <code> <param:16LE> <tick:32LE>
    0x00 LONG_PRESS · 0x01 TAP · 0x02 DOUBLE_TAP
    0x04 SWIPE_UP   · 0x05 SWIPE_DOWN · 0x08 LONG_PRESS_RELEASE

3-byte "direct" gesture:    ff <type> <param>
    03/20 HOLD · 04/01 TAP · 04/02 DOUBLE_TAP
    05 param<=1 SWIPE_FORWARD, else SWIPE_BACKWARD
```

Raw swipes with **no container involved and nothing to reach a boundary of** — plus a 32-bit tick,
so velocity/acceleration is available if we ever want it.

❌ **This corrects our own doc.** `G2CC/docs/G2_BLE_PROTOCOL.md` §11 states of the ring link:
*"Navigation input does NOT come over this link (it goes ring→glasses→`e0-01`). This link is
battery/firmware/sensors only."* **Faceclaw demonstrably decodes gestures from it.** That claim is
wrong or incomplete. (G2CC is read-only — the correction lives here and in `CLAIMS.md`.)

**Open, for first light:** whether the ring coalesces fast notches or reports every one, and what
the event-rate ceiling is. Also whether holding the ring link costs anything against the throughput
problem (§5.1 shows the ring is nearly silent during transfers, so probably not).

### 🔴 The single most concrete win — Adam's #1 daily annoyance, already fixed

At work Adam wears gloves. The failure chain today:

1. double-tap → G2CC mini-silent-mode (tiny clock, ignores all input but another double-tap)
2. gloves force a **ring long_press** → **"End Feature?" menu** overrides mini-silent-mode
3. then either a glove **tap** → selects End Feature → **kills G2CC** until the phone notices
   and refreshes, or another glove **long_press** → **Firmware Menu**, whose first item is
   **Silent Mode**, which then gets selected → **firmware Silent Mode, no clock, no notifications**

**The CFW patch removes the call that opens that dialog.** Ring long-press becomes SysEvent 9,
delivered to our app, which in silent mode simply ignores it. **The chain dies at step 2 and
every downstream step becomes unreachable.** This is not modeled or estimated — it is a specific
shipped patch that happens to gate on `source == ring`, which is exactly the case.

---

## 7. Stereo / depth — what it actually is

The "lenses differ" flag carries **the same pixels, one payload, at a different position per
lens**. It is **binocular disparity control**, *not* independent per-eye content.

- **Per-object depth: yes** — each mode-3 op has its own L/R box pair; batch several in a mode 8
  for multiple elements at different apparent depths.
- **Per-pixel depth: no** — no depth buffer. Granularity is the rect; each element is a flat card.
- **True stereoscopic 3D: not via this flag** (one shared payload can't be two different views).
  Technically possible via `G2_IMAGE_ARM` (writes image data to a chosen arm; arms are separate
  BLE links) at ~2× payload and 2× writes — but the ack returns on R either way, so the pipelines
  aren't independent.
- **Why it won't look volumetric:** fixed accommodation (the optics present one focal distance,
  so disparity gives vergence cues with no focus cue — small offsets read as depth, large ones as
  strain), no motion parallax, monochrome, 16 levels.

**Best uses:** IPD/convergence calibration (a genuine comfort win — misaligned images are what
cause eye strain), and subtle depth separation for overlays/modals.

**Rules:** never different content per eye (binocular rivalry — genuinely unpleasant);
**horizontal offsets only, never vertical**; consistent disparity per object; small magnitudes.

⚠ **Stock already owns a global depth setting** ("near/mid/far" in the official app; 2.2.8 notes
mention *"Fixed display distance settings not applying correctly on G2"*, and openCFW's canvas480
says its calls *"deliberately do not reuse the stock 0…12 optical screen-height setting"*).
**Adam deliberately sets it to FAR** so the display is easy to ignore at work and while driving.

🔴 **Corrected 2026-08-17 by Adam directly.** An earlier version of this section inferred from the
FAR setting that we should "push background elements farther, never foreground nearer." **That was
our inference, not his preference, and it is backwards.** His actual stated preference:

> "the main window as far back as depth comfortably allows, and notifications and the like to pop
> over it in front of it."

⇒ **The layer order is: main content parked at the far end of the comfortable range; popups,
notifications and modals come FORWARD toward the screen plane.** The FAR display-distance setting
remains his baseline for ignorability; the *relative* separation between content and popup is what
does the perceptual work, so a modest content push plus popups at plane 0 delivers the effect
without approaching the divergence limit. See `DESIGN.md` §3 for the ladder and the calibration
plan.

Priority: a nice polish layer once the WM exists. **Not a reason to choose this architecture.**

---

## 8. The stock LZ4 path (works without CFW — useful for measurement)

### ⚠ What `CompressMode` values actually mean — evidence graded (corrected 2026-08-17)

Earlier versions of this document stated **"1 = RLE, 2 = LZ4"** as settled fact. It is not.
`ImageRawDataUpdate.CompressMode = 5` is a **bare `uint32`** in Even's own FileDescriptorProto —
**there is no `ImageCompressFormat` enum anywhere in the 27 vendor schemas** (grepped, 2026-08-17).
The schema pins the *field*; it says nothing about the *values*.

| value | evidence | grade |
|---|---|---|
| **0** = uncompressed | three independent working implementations send 0 and render correctly: faceclaw (`BleProtocol.java:175` "CompressMode stays 0"), g2-kit (`compressMode ?? 0`), g2flash's own CFW path | ✅ **confirmed** |
| **2** = LZ4 raw block | (a) g2flash wrote a purpose-built raw-LZ4-*block* encoder for it and benchmarks it against stock 2.2.6.10 — `COMPRESS_MODE = LZ4 ? 2 : 0`, working exercised code, ~10 fps; (b) **independently**, `even-g2-notes/docs/page-lifecycle.md` documents Even's **own SDK 0.0.12** stamping `compressMode: 2` on every image while passing bytes through uncompressed, producing garbled small images and `sendFailed` on large ones on fw 2.2.6.10 | ✅ **strong, two independent sources** |
| **1** = "RLE" | **two prose comments by one author** — `patch_compress.py:31` and `demos/video-bench.ts:23`. No firmware address, no decompiled dispatch, no capture. Both cite `notes/fw-2.2.6.10-lz4-images.md`, **which is not in the repo**. And **nothing anywhere ever sends 1** — not g2flash, faceclaw, g2-kit, or Even's SDK | ❌ **single-source, uncorroborated, never exercised — TREAT AS UNKNOWN** |

**G2CC's June 2026 probe is not evidence either way.** It guessed `f5 ∈ {1,2,3}` and always sent
**RLE4** bytes — but it ran on **2.2.2**, which predates the feature entirely and has no decoder.
Inconclusive by construction; do not read it as confirming *or* refuting the mapping.

**On our 2.2.2 the field most likely has no stock meaning at all.** g2flash's *older* CFW hijacked
it outright: `patches/decompress.c`'s `frag_write` treated **any nonzero CompressMode as "the
payload is 1bpp, expand it 1→4."** That only works if stock ignored the field. `patches_main.c`
says the expander "was dropped in the 2.2.6.10 rebase — stock now uses CompressMode itself" — which
establishes *that* the field became meaningful in 2.2.6.10, not *which value maps to what*.

### ✅ Answered by the author, 2026-08-17 — and the practical answer is "always send 0"

Babcock, by email:

> "CompressMode is **unused (no effect) in older firmwares**. In newer firmwares it's Even's
> first-party compression mode. The g2flash/faceclaw firmware supports this for compatibility with
> the stock Android app, but **signals its own compression method with CompressMode=0 and some
> header bytes in the data field**."

Everything in the grading table above survives. "Unused in older firmwares" confirms the inference
that the field has **no stock meaning on our 2.2.2** — which is exactly why g2flash's old
`frag_write` could hijack any nonzero value for its 1bpp expander. "Even's first-party compression
mode" in newer firmwares confirms it became meaningful at 2.2.6.10.

⚠ **He did not confirm `1 = RLE`.** He described the field's role without giving values, so that
row stays ungraded — and it is now **moot in practice**.

🔑 **The operative rule: the CFW path always sends `CompressMode = 0`.** Our "header bytes in the
data field" are the mode byte itself (3/6/8/9 — §4). This matches Faceclaw's
`BleProtocol.java:175` verbatim: *"CompressMode stays 0: the CFW's zlib path is detected from the
buffer's..."* ⇒ Damage never sets a nonzero CompressMode, and the `1`-vs-`2` question never has to
be answered.
- `CompressMode=2` feeds the payload to `LZ4_decompress_safe(src, dst, srcLen, dstCapacity)` at
  **`0x0054f338`**.
- ⚠ It expects a raw **LZ4 BLOCK, not an LZ4 frame.** Most npm/py LZ4 libraries emit frames
  (magic `0x184D2204`), which the decoder **rejects**. `g2flash` `demos/lz4.ts` is a
  dependency-free block compressor written for exactly this. Decoder rules: last 5 bytes always
  literals (LASTLITERALS), no match may start in the last 12 bytes (MFLIMIT).
- Firmware decompresses into `malloc(W*H)` sized from the container ⇒ **payload must inflate to
  ≤ W×H bytes.**
- **An unknown CompressMode is silently "treated as raw"** — garbage on the lens, not an error.
  *This exactly explains G2CC's June "garbled underline" result on 2.2.2: wrong codec, and
  firmware with no decoder at all.*
- Failure logs `evenhub_ui: decompress failed, mode=%u raw_len=%u`.

---

## 9. Ecosystem — who built what, and how much to trust it

### The projects (all links)

| project | what it is |
|---|---|
| **[jimrandomh/g2flash](https://github.com/jimrandomh/g2flash)** | **THE custom firmware.** GPL-3.0. Author **James Babcock**. Patches stock 2.2.6.10. `patches/zlib_glue.c` = the mode table; `patches/gesture_fwd.c` = input; `patches/patch_compress.py` = the patcher; `demos/lz4.ts` = LZ4 block encoder; `demos/video-bench.ts` = the benchmark; `demos/detect-cfw.ts` = capability probe; `g2flash.py` = the flasher |
| **[jimrandomh/faceclaw](https://github.com/jimrandomh/faceclaw)** | the reference UI on that CFW. **Ships `app/fonts/terminus/*.bdf`** and renders its own framebuffer UI — proof the architecture works |
| **[Commute773/g2-kit-unofficial](https://github.com/Commute773/g2-kit-unofficial)** | ★31. **An independent from-scratch RE of the BLE stack — the same category of work as ours.** `ble/gen/*_pb.ts` = **generated protobuf schemas** for ~20 message families, each embedding the **vendor's own `FileDescriptorProto`** — the single most valuable artifact in the ecosystem (§9.1). ⚠ **`ble/docs/`'s 11 prose docs are materially unreliable — read the `.ts`, not the `.md`** (§9.1). *(jimrandomh's copy is a byte-identical fork existing only to pin a dependency SHA.)* |
| **[kalanihelekunihi/evenRealities-openCFW](https://github.com/kalanihelekunihi/evenRealities-openCFW)** | Author **Kalani Helekunihi** (company: AM Guru). Three things: (a) a byte-exact **reconstruction** of stock 2.2.6.10 — ~5% source-owned, an analysis project, nothing to adopt; (b) an **unreleased** `g2-2.2.6.12` build = older g2flash `d5eb48dd` + **canvas480** (see §4 — not the CFW anyone installs); (c) **the adversarial review of g2flash**, which is the genuinely valuable part: `tools/thumb_branch_audit.py`, the evenai_thumb HardFault writeup, and a regression test |
| **[AM-Guru/SybilSight-webflasher](https://github.com/AM-Guru/SybilSight-webflasher)** | MIT. Deployed at **webflasher.sybilsight.com**. Browser flasher over Web Bluetooth **and the charging case's CH340 USB serial** (`1A86:7523`). Backup set (512 KiB case flash + option block + temple identity snapshots + matching official glasses bundle). **Hosts the 19-image firmware archive.** Case-USB pogo bridge pushes to a *responsive* temple — **not a dead-device rescue** |
| **[i-soxi/even-g2-protocol](https://github.com/i-soxi/even-g2-protocol)** | the original community BLE RE reference (G2CC's original upstream) |
| **[nickustinov/even-g2-notes](https://github.com/nickustinov/even-g2-notes)** | **`docs/performance.md`** (the fps/cost model) and **`docs/display.md`** (container rules, glyph inventory, fullwidth-CJK monospace trick) |
| **[pangoleen/awesome-even-realities-g2](https://github.com/pangoleen/awesome-even-realities-g2)** | the curated index of ~150 G2 projects — start here for prior art on any app idea |
| [opinsky/even-img-benchmark](https://github.com/opinsky/even-img-benchmark) | image-pipeline benchmark harness |
| [200even/flappy-g2](https://github.com/200even/flappy-g2) | source of the debunked "10 fps @ 200×100" claim |
| [wmoto-ai/even-g2-matrix](https://github.com/wmoto-ai/even-g2-matrix) | carries the explicit "the demo GIF is the simulator, real hardware is 1–2 fps" warning |
| [G2oom Reddit writeup](https://www.reddit.com/r/EvenRealities/comments/1sdcvkj/i_ported_doom_on_the_even_g2_sort_of_heres_what_i/) | the best real-hardware developer account. Sobel + Bayer dither pipeline, and the 0.5 s/frame verdict |
| [Even Hub docs](https://hub.evenrealities.com/docs) | official SDK documentation |

### Gotchas from g2-kit worth stealing (`ble/docs/gotchas.md`)

- **The first Cmd=3 burst after CREATE is silently dropped** — fragments ack, render fires, lens
  stays blank. Push a sacrificial warmup frame; treat frame 2 as the first real one.
- Fragment `seq` is a **group key, not a counter** — incrementing per fragment makes the firmware
  drop everything.
- `magic` is **effectively uint8** (firmware compares only the low byte) — the same wall as our
  msgId-255 finding, hit from the other side.
- Container name cap is **14 chars**, hard, silent rejection.
- **`sid=0x80` (dev_config) bricked a pair** — non-terminally; needed power-cycle + re-pair.
  **Stay on `sid=0x09`.**
- Multi-fragment messages must not interleave on the characteristic — one reassembly buffer keyed
  by transport `seq`. Serialize writes.
- Audio is on service `6450`, not `7450`.
- **Stuck-session trap:** after an aborted image stream, re-creating the container with an
  *adjacent* `MapSessionId` inherits the dead session's buffers — the "new" session gets the old
  one's broken state. Bump the session counter by **≥2** on reset. Symptom: a fresh CREATE
  succeeds but the first Cmd=3 burst never acks and never renders.
- **1×1 transparent image = soft sleep.** Lens goes dark but the plugin task and event pipeline
  stay alive, so taps still fire. Much cheaper than a Cmd=9 teardown for a silent/idle mode.
- Container **name cap is 14 chars**, hard and silent (our doc says ≤16 — untested, cheap to check).
- TextContainer `capture_events` **defaults false**; lists and images capture by default.

---

## 9.1 Cross-check of our BLE RE against g2-kit (done 2026-08-16)

### 🔑 The rule that came out of it — and the author's own ordering

> **g2-kit's prose docs (`ble/docs/*.md`) are materially wrong. Its code (`ble/*.ts`) is right.
> Read their `.ts`, never their `.md`.**

✅ **Independently confirmed by Babcock 2026-08-17**, unprompted, in the same words we'd reached:

> "g2-kit-unofficial is **older reverse engineering work** by nebulani/Commute773; **the best
> source of truth for how to format messages is faceclaw.**"

⇒ **Source-of-truth ordering for wire format, highest first:**
1. **Even's own protobuf schemas** (`g2-kit/ble/gen/*_pb.ts` — vendor `FileDescriptorProto`s; §9.1)
2. **Faceclaw + g2flash source** — the exercised CFW implementation
3. **Our own BTSnoop captures** — authoritative for stock 2.2.2 and the official app specifically
4. g2-kit's `ble/*.ts`
5. ❌ **g2-kit's `ble/docs/*.md` — do not use.** Notably he did *not* dispute the `is_last`
   contradiction we raised; he routed around it (see §8: always send `CompressMode = 0`).

Same repo, same author, and they contradict each other everywhere checked. Our capture-derived
spec agrees with their **code** and disagrees with their **docs**, every time:

| claim | their `.md` | their `.ts` | ours | correct |
|---|---|---|---|---|
| envelope header | `aa 21 LL LL SS FF II MM×4` (11 B) | `aa 21 seq len totFrag fragIdx sid flag` (8 B) | identical to their `.ts` | **us + their code** |
| CRC scope | whole frame from `0xaa` | payload only, last fragment only | payload only | **us** |
| CRC byte order | big-endian | little-endian | little-endian | **us** |
| fragment size | `mtu-3` = 241 | `chunkSize ?? 232` "matches Mirai's" | ~232 measured | **us + their code** |
| `magic` / `msgId` | 4-byte **header** field | protobuf **field 2** | protobuf field 2 | **us** — same field, two names |
| Cmd=3 field 5 | `is_last` (bool) | **`CompressMode` (uint32)** | absent on 2.2.2 | **us** |
| image bytes | "no padding, no stride, top-down" | full BMP, 4-byte row pad, **bottom-up**, 16×4 palette | same | **us + their code** |
| GATT UUIDs | `6E40FFF0/fff1/fff2` (a Nordic UART base!) | `…0e8ac72e5401/5402` | same | **us + their code + g2flash** |

Our CRC claim was verified computationally: CRC-16/CCITT-FALSE over the documented keepalive
payload `080c104f7200` = `0xCC79`, on-wire LE `79 cc`. Exactly as `G2_BLE_PROTOCOL.md` §2 states.

⚠ Their `is_last` claim is **actively dangerous**: field 5 is `CompressMode`, so following their
doc and writing `is_last=true` emits **`CompressMode = 1`** — a nonzero compression mode over
*uncompressed* bytes. Whatever 1 means (see §8: unverified), that is a misinterpretation, and an
unknown CompressMode is **silently "treated as raw"** rather than rejected. The empirical proof of
this exact failure mode on real hardware is Even's own SDK 0.0.12 regression: a wrong nonzero
`compressMode` over plain bytes → garbled small images, `sendFailed` on large ones.

### The artifact that settles it: Even's own schema

`ble/gen/EvenHub_pb.ts` embeds a base64 `FileDescriptorProto` — **the vendor's actual
`EvenHub.proto`** (package `g2.evenhub`, vendor CamelCase names). Decode it with a plain varint
walker; the base64 ships **unpadded**, so pad it first.

**Every wrapper field number in `G2_BLE_PROTOCOL.md` is confirmed exactly:** `Cmd=1`,
`MagicRandom=2`, `CreateMessage=3`, `ImgRawMsg=5`, `RebuildContainer=7`, `TextUpgrade=9`,
`ShutDownCmd=11`, `DevEvent=13`, `HeartPacketCmd=14`. All three container property tables,
`TextContainerUpgrade{ContentOffset=3, ContentLength=4}`, and `OsEventTypeList` 0–8 match too.
Our RE stands up 1:1 against the vendor's own definitions.

**Four things the schema gave us that we did not have:**

1. **`ImgRawMsg.f3` is `MapSessionId`, not a nonce.** `G2_BLE_PROTOCOL.md` §6.5 calls it a
   "per-push nonce… set it to anything per push." It is a **session id with real semantics** —
   see the stuck-session trap above. This is the one place our doc is genuinely wrong.
   *(G2CC is not to be edited; record the correction here.)*
2. **`ImgRawMsg.f5 = CompressMode`** — the field G2CC's June probe hunted and never recovered.
   ⚠ The schema gives the **field only**: it is a bare `uint32`, and **no `ImageCompressFormat`
   enum exists in any of the 27 vendor schemas**. Value semantics rest on evidence, not the
   schema: `0` confirmed, `2` strong, **`1` unverified**. See §8.
3. **EvenHub has IMU** — `Cmd 19/20`, `IMU_CtrlCmd{IMUReportEn, reportFrq}`,
   `IMU_Report_Data{double x, y, z}`, `OsEventTypeList.IMU_DATA_REPORT = 8`. See §2.
4. **`Sys_ItemEvent.f2 = EventSource`**: `1 = GLASSES_R`, `2 = RING`, `3 = GLASSES_L`. Our
   capture's unlabeled `f13.f3={f1=3 f2=2}` therefore decodes as **double-tap from the ring** —
   closing the "input source byte" open item in `G2_BLE_PROTOCOL.md` §14.
   ⚠ **Different numbering from the CFW's internal source byte** at `0x2034dc30` (0/1 = L/R
   temple, 4 = ring). Do not conflate the two.

Also resolved: **`e0-02` is the firmware's abort frame** emitted on reassembly failure — g2-kit
triggered it by incrementing `seq` per fragment. `G2_BLE_PROTOCOL.md` §5 lists it as "observed
once, empty… low confidence — do not rely."

## 9.2 Schema-validated re-decode of both captures (2026-08-17)

Tooling: `research/decode/schema.py` (builds a field registry from all 27 vendor
`FileDescriptorProto`s) + `research/decode/decode_capture.py` (BTSnoop → HCI → ATT → AA
reassembly → protobuf, annotated with Even's own field names). 282 messages reassembled from
`imagestatus.log`, 260 EvenHub messages from `allbutimages.log`.

**Every init-frame label we flagged as conflicting is now settled**, against the vendor's own
schemas rather than inference. `G2_BLE_PROTOCOL.md` §4 is right about *positions* and wrong about
several *names* (it predates the schemas; do not edit G2CC — corrections live here):

| frame | our old label | **actual, decoded** |
|---|---|---|
| `80-00` t4 | "Auth/capability query" | `AUTHENTICATION` · `AuthMgr{secAuth=1, phoneType=PHONE_ANDROID}` ✅ |
| `80-20` t5 | "Capability-response request" | 🔑 **`PIPE_ROLE_CHANGE{asCmdRole = RIGHT}`** — see below |
| `80-20` t128 | "Time-sync, f2 = UTC quarter-hours" | `TIME_SYNC{timestamp, timezone=−20}` ✅ exactly right |
| `09-20` t1 | "Device-info query `{f9={1,1,1,1,2}}`" | `appSendUniverseSetting{unitFormat, distanceUnit, timeFormat, dateFormat, temperatureUnit}` — a **push**, not a query |
| `03-20` | "App enumeration + tokens" | `MENU` · `sendData{itemTotalNum=10, item{itemType, itemAppId}}` ✅ (`itemAppId` = the token) |
| `0d-20` | "Configuration query" | `SYNC_INFO` · `APP_REQUEST_SYNC_INFO` |
| `0c-20` | "Tasks one-shot" | `QUICKLIST` · `MULT_ITEMS{dataType=FULL_UPDATE}` |
| `07-20` | "Dashboard one-shot" | `EVEN_AI` · `CONFIG{voiceSwitch=1, streamSpeed=32, duplexMode=0}` |
| `10-20` | "Unknown small init `{f1=4}`" | `ONBOARDING` · `CONFIG{processId = FINISH}` |
| `20-20` | "Commit (finalize init)" | `MODULE_CONFIGURE` · `SYSTEM_GENERAL_SETTING{languageIndex=0}` |
| `81-20` | "Display Trigger; ack `{f1=78}`" | ❌ **`GLASSES_CASE` · `CASE_INFO` — the 78 is `caseInfo.soc`, the CASE BATTERY** |
| `04-20` | "Display Wake `{f1=1 f2=1 f3=7 f5=1}`" | `NOTIFICATION_CTRL{notifEnable=1, autoDispEnable=1, **dispTime=7**, avoidDisturbEnable=1}` |
| `91-20` | "R1 registration (ring MAC)" | `RING_DATA_RELAY` · `EVENT{ringMac, eventId=BLE_ADV}` ✅ |
| `30-XX` | "Unknown small init" | **still unknown — and it is not in Even's own SID enum at all** |

### 🔑 `PIPE_ROLE_CHANGE` — the mechanism behind "the right lens is the one you drive"

The official app explicitly sends `sid 0x80, commandId 5 = PIPE_ROLE_CHANGE` with
`asCmdRole = 1 = RIGHT` at connect. `eGlassesLR` is `{BOTH=0, RIGHT=1, LEFT=2}`.

So "R is the command lens" is **not a hardware property — it is a runtime setting the host
chooses**, and `BOTH` is a legal value. That reframes the arm-split question (§2): the *command
role* governs control and events, which is consistent with Faceclaw sending bulk pixels to LEFT
while keeping RIGHT as the command lens. Worth probing once we are on hardware.

### What the image path actually does

- **`CompressMode` is absent on all 13 official-app image pushes.** ✅ Our claim, now definitive.
- **`MapSessionId` is constant across the fragments of one image and changes per push**, using
  small ints (25–237, n=13, 7 unique) with erratic deltas — **random per push, not a counter.**
  Faceclaw instead uses large random uint32s. Both work.
- **Longest container name the official app ever uses is 8 chars** (`imgsolid`). Our "≤16 char"
  claim is *unsupported by our own data*; g2-kit's "14 hard cap" remains single-source. Unresolved.
- **`imgmax` is exactly 288×144 and painted** ✅ — the SDK cap is real, and G2CC's conservative
  288×129 was over-cautious.
- Largest **layout** frame observed: **401 B** — comfortably under the ~1000 B layout wall (§2).

### 🔴 What failure looks like on the wire (this is what NO SILENT FAILURES needs)

Real failures occur in normal operation, and until now we had never seen one decoded:

```
[+47.19s] e0-02  empty frame                    <- abort signal
[+47.75s] ImgResCmd.ErrorCode = APP_REQUEST_UPGRADE_IMAGE_RAW_DATA_FAILED
          container=mximg session=25 total=10118 fragIdx=1   <- 4125 B Cmd=3 request
[+48.38s] ImgResCmd.ErrorCode = APP_REQUEST_UPGRADE_IMAGE_RAW_DATA_FAILED
          container=mximg session=25 total=10118 fragIdx=1   <- retried, SAME session, failed again
```

Three things fall out:

1. **`e0-02` lands 0.56 s before the first failure.** Our doc had it as "observed once, empty… low
   confidence — do not rely." Combined with g2-kit's independent account of an abort frame on
   `sid=0xe0 flag=02`, this is now **corroborated**, not a curiosity.
2. **Failure is reported in `ImgResCmd.ErrorCode` (field 8)**, carrying container name, session id,
   total size and fragment index. Damage must read that field — it is the difference between a
   loud failure and a blank lens. ⚠ *`ImgResCmd` field 1 is `ContainerID`, not an error code; an
   earlier pass of this analysis conflated them.*
3. **Retrying with the same `MapSessionId` failed identically.** That is the stuck-session trap
   g2-kit warns about, observed on our own wire. **Bump the session id on retry.**

Also seen: `TEXT_DATA_FAILED` and `SHUTDOWN_FAILED` (the latter twice, on `exitMode` shutdowns —
possibly just how the firmware reports "already gone"; noted, not explained).

### How much to trust all this — honest assessment

Adoption is **tiny** (pulled live from the GitHub API 2026-08-16): g2flash ★22 / 3 forks /
**0 issues ever** / 1 contributor, created 2026-06-23; faceclaw ★16 / 3 / 0; openCFW ★18 / 2 / 0;
webflasher ★2 / 0 forks / 2 issues **both self-filed by the author**. **Zero mentions of "g2flash"
or "faceclaw" anywhere on Reddit** — searched all of Reddit, not just r/EvenRealities.

**The gradient is the real story.** The *protocol* layer has genuine adoption —
even-g2-protocol ★178/31 forks, even-g2-notes ★111/9, g2-kit ★31/5 — while the *firmware* layer
has almost none. People consume the RE and avoid the flashing.

### There is exactly one third-party CFW user, and they are active

`Danxtream/faceclaw` (+4 commits) and `Danxtream/g2flash` (+3), both pushed 2026-08-16, are
building **H.264 video playback on the CFW**: their own 2,551–2,635-line `zlib_glue.c` variants
against upstream's 1,512, a `patches/h264/` tree, and committed Android logcat from real glasses
(MACs `C4:AF:F2:54:38:29` R / `C4:60:45:13:B3:36` L, `fwside=1`/`2` matching the CFW's `FW_SIDE()`).
Someone other than the two authors is building and flashing modified CFW, and it boots.

⚠ Their commit message says **"13fps." It is not a measurement** — `present=0 frames=0` throughout
both logs (the pipeline never presents), and the ~1.55 s inter-ack gaps are host-side timer pacing.
**Sim-or-glass discipline applies to fork commit messages too.** What the logs *do* establish:
on-glass H.264 `decode=39–42 µs` (compute is not the bottleneck — our thesis, confirmed from the
other side) and a working `sid=0x09` wear-detect ACK. `Mojashi/g2flash` is +2 from July; the
remaining forks are pure mirrors.

**But it is not n=1.** Two independent authors cross-reference, and Helekunihi **audited Babcock's
patches at instruction level**:

- **Scope rejections** (`release.json` `removed_features`): `faceclaw-wake-lease`,
  `faceclaw-idle-double-tap-takeover`, `faceclaw-even-ai-interception`, plus the settings
  field-101 decoder; `"native_even_ai_behavior": "stock"`. These are product-scope choices —
  Faceclaw hijacks the wake word and idle double-tap for its own UI; SybilSight wanted stock
  "Hey Even" to keep working. **Not safety judgments.**
- 🔴 **A real HardFault bug he found in an early CFW 2.2.6.11**
  (`components/apollo_main/evenai_thumb/`): the `even_ai_display_ctrl` trampoline resumed the
  stock body via `bx r12` to `0x004E1FD6` — **bit 0 clear ⇒ requests ARM state**; Apollo510
  (Cortex-M55) has no ARM state ⇒ UsageFault/INVSTATE; the stock vector table installs **no**
  MemManage/BusFault/UsageFault handler (all three vectors zero) ⇒ escalates to **HardFault**,
  which logs `/log/hardfault.txt` then spins (`b .`) until the **external watchdog resets the
  temple, dropping the BLE link.** Fix = one byte, `0xD6`→`0xD7`. **It survived the author's own
  review because the one path that avoids the `bx` (`op==START` with a valid lease) is exactly
  the path his app exercises — it reproduces most reliably when the host app is NOT connected.**
  He also shipped `tools/thumb_branch_audit.py` (scans a Thumb-2 blob for `movw/movt → bx|blx`
  with bit 0 clear) plus a regression test.

  ### ✅ It is fixed, and we verified that ourselves

  The bug was in a build from g2flash `6d5c5859`, also called "2.2.6.11" — which is why the
  archive keys images by SHA prefix (`2.2.6.11-105032302d02`). **g2flash fixed it upstream on
  2026-08-07**, commit message *"Fix a crash that occurred when using the custom firmware with
  stock Even AI"*; `patches/settings_ext.c:300` now reads `movw r12, #0x1fd7` with the comment
  *"0x004e1fd6 | Thumb bit; BX needs bit 0 set"*. **Verified 2026-08-16 by running Helekunihi's
  own auditor against the exact image on disk** (extract the injected blob first — it is
  `g2-2.2.6.11.bin[4301227:]`, 20,127 bytes):

  ```
  python3 reference/evenRealities-openCFW/g2/tools/thumb_branch_audit.py <blob> --base 0x00794324
  → 14 constant interworking branches, ALL Thumb.  0x00796e8a = 0x004e1fd7.  ZERO defects.
  ```

  (14 branches vs the 9 in his writeup because HEAD's blob added compass, wear-notify and the
  buzzer sequencer since `6d5c5859`.) **Re-run this audit before any flash** — it is free.

⇒ **Corroboration is n=2 with instruction-level adversarial review, a defect found, and a fix that
flowed upstream and that we independently confirmed.** That is a working review loop, and a
materially better trust story than "one author's say-so." Expect to be the third serious consumer
of this ecosystem. Read patches before flashing them.

---

## 10. G2CC assets to mine (do not modify G2CC)

> ✅ **The BTSnoop corpus is recovered and now lives in `captures/`** (2026-08-17). It had been lost
> from `/tmp/g2cap-cap/`; both sessions were recovered from Adam's mailbox, where they had been
> sent from the phone as Android **bugreport** zips. All four segments verify `orig_len ==
> incl_len` (unfiltered), record counts match §0 of `G2_BLE_PROTOCOL.md` exactly, and `SHA256SUMS`
> pins them. **This is the only ground truth for stock 2.2.2, and 2.2.2 is not in the public
> archive — it is unreproducible once we leave it.** See `captures/README.md`, including the known
> gap (handle 65's connect is outside both windows).

`/home/user/G2CC` is a working, shipped system. **Read from it; never edit it.** The pre-pivot
framebuffer pipeline is all still in the tree:

| path | what |
|---|---|
| `docs/G2_BLE_PROTOCOL.md` | **our authoritative BLE wire spec** — per-capability frames, timings, chunking, pacing, ack latencies, link params |
| `docs/PROTOCOL_NOTES.md` | the deeper capture-derived notes (54 KB), incl. the image format decode and the settings channel |
| `docs/SDK_CAPABILITY_MAP.md` | **the compression-probe record** (CMP1–6) — why the June RLE4 test was inconclusive |
| `docs/DE_DESIGN.md` | the G2CC DE geometry contract — useful as a *reference layout*, not a constraint |
| `docs/WINDOW_API.md` | the `OsWindow` / `WmContext` / `WinView` contracts — 20 windows' worth of interaction design |
| `docs/SIM_TOOLING.md` | the EvenHub simulator setup that works on this box |
| `android/app/src/main/kotlin/com/g2cc/g2cc/render/` | `Rasterizer.kt`, `Quantize.kt`, `Gray4Bmp.kt`, `Scene.kt`, `DisplayProto.kt`, `G2Renderer.kt`, `BleDisplaySink.kt` |
| `server/src/os-compose.ts` | 1149 lines of scene composition |
| `server/src/windows/` | 20+ window implementations (mail, music, files, reader, terminal, games, ff1, scout, calendar, sms, search…) |
| `scripts/render_content.py` | PIL rendering with **DejaVu Sans / Sans-Bold / SansMono at six sizes**, wrapping, headings, stat lines |
| `scripts/render_menu.py` | menu rendering with **URW Chancery cursive** |
| `scripts/btsnoop_parse.py`, `decode_display.py`, `decode_deep.py` | the capture-decoding toolchain |
| `games/ff1/bridge/spike_out/*.png` | 109 real rendered frames — the compression test corpus |
| **`docs/HAT_BRIDGE_SPEC.md`** | 🆕 **the bridge appliance, design-locked and BOM-purchased** — see below |

### 🆕 The hat bridge is being resurrected (2026-08-20)

`HAT_BRIDGE_SPEC.md` was design-locked 2026-06-08 and **the entire BOM was bought but never built**,
because the v0.7 software fix (foreground service + wake lock + faster recovery) made the connection
problems vanish. §1 of that spec says so outright.

It is exactly the bridge appliance `DESIGN.md` §10 needs for the no-phone configurations:
**Seeed XIAO ESP32-C5** (dual-band WiFi 6 + BLE 5), 3 × 420 mAh, a dual-band u.FL FPC antenna
mounted **outside the hat, right side, 1–2″ from the glasses' temple-tip antenna**, WSS home to the
PC's cloudflared tunnel.

Two things now make it easier than when it was specced: under the CFW the framing is **simpler**
than the `f1=0/3/5/7` port it planned (`CompressMode = 0` plus a mode byte), and if the host
pre-deflates, **the bridge needs neither zlib nor the 153 KB shadow** — it forwards bytes and owns
the lease.

🔑 **And it is a controlled experiment on §5.1.** Its board was chosen because *"dual-band dodges
WiFi/BLE coexistence"* — and coexistence on the phone's combo radio is one of the **three surviving
candidates** for the ~10× throughput shortfall, specifically the one that is invisible at the HCI
layer and therefore unresolvable from the captures alone. WiFi on 5 GHz and BLE on 2.4 removes the
contention outright. **If throughput jumps on the hat, that isolates a cause nobody has been able to
name** — including the CFW author, who says debugging it *"would make a much bigger difference than
compression tuning."*

⚠ The number that decides whether it is wearable: **1260 mAh running WiFi 6 plus BLE for a full
workday.** The spec has a hybrid power policy and no measured budget.

---

## 11. Open unknowns — resolve these before/while building

1. ⚪ **CFW ack latency on the direct-framebuffer path.** Every number in §5 is priced with 176 ms
   from stock 2.2.2 captures. *Re-graded 2026-08-17 — this was previously marked 🔴 "the single
   most load-bearing unmeasured value," and that was wrong.* **It is a tuning constant, not a
   design input.** Apply the decision-relevance test:
   - *Flash or not* — unchanged across any plausible value. The CFW removes every named cause of
     G2CC's 6.1 s screen (§1); the margin is large enough that refining the input changes nothing.
   - *Damage tracking + one mode-8 flush per frame* — **latency-monotonic.** Higher ack latency
     makes batching **more** correct, not less. The architecture never flips.
   - *Coarse scroll* — holds anywhere above ~50 ms of ack, i.e. the entire plausible range.

   What it actually moves: scroll step size, sliding-window depth, game frame budget. All tuned
   **after** first light, on our own content, where our own measurement beats any third-party
   number we would have to translate anyway. **Measure it on the far side; do not gate on it.**
2. ~~Whether **off-screen scratch** is usable~~ — **RESOLVED 2026-08-17 by deleting its premise.**
   There is no off-panel margin: the **full 640×480 is visible** (§2). Pre-render-off-panel-then-
   flip-in is dead, and so is save-under via mode 9 (§12).

   **The replacement is better than what we were asking for.** Babcock, same email:

   > "Pre-sending stuff to move in is what I was talking about with **texture caching**. There are
   > **multiple screens' worth of available or reclaimable memory**, but there's a bit of reverse
   > engineering and plumbing work left."

   Multiple screens of on-device cache beats a 640×480 margin by a wide margin, and it is *being
   built* — see §15. ⚠ It is therefore a **moving target**: anything Damage builds against today's
   CFW must be able to absorb texture caching landing later.

   **Timeline and why it matters to us specifically** (same thread, 2026-08-17):

   > "It's somewhat difficult to hand off in its current state but **probably done by end of next
   > weekend**. The SoC has a bunch of different allocation pools/heaps; the RE task is figuring out
   > **which heaps are safe to fill how much and which mutexes need locking**."

   Adam offered to help; the answer was effectively *not yet* — the remaining work is firmware RE on
   heap safety and mutex analysis, his strongest ground and not easily parallelised from outside.

   🔴 **It is not a nice-to-have for us — it is the enabler for our headline feature.** He gave the
   reason he is building it:

   > "Deflate (zlib, 32kb window)+RLE. This is almost as good as PNG and mostly good enough
   > (**but it doesn't play nice with antialiased fonts, hence the texture cache**)."

   §1 calls anti-aliased text at 16 levels "the biggest single visual win" and §12 has it as a
   settled decision. The author of the compression path is saying AA fonts are exactly what it
   handles worst — the same mechanism as our dithering finding (§5), since AA turns glyph edges
   into short high-entropy runs. Our own re-measurement shows the cost is **lower than we modelled**
   (§5), so AA text is viable today — but **texture caching is what makes it cheap at scale**, and
   that makes it the single highest-value thing to track in his work.

   *(The "32kb window" checks out against the code: `FW_INIT2(strm, 15, …)`, windowBits 15 = 32768.)*
3. Whether the **msgId-255 wall** persists under CFW.
4. ~~**Keepalive/heartbeat contract** in CFW mode~~ — **RESOLVED 2026-08-16.** `image_worker()`
   calls `FW_KEEPALIVE_RESET()` on every top-level image message (the same leaf the stock sid-0x0c
   handler uses), so a steady mode-3/6/8/9 stream is self-sustaining. The stock counter at
   `0x200745AC` fires teardown past 899 ticks; we simply never get there while rendering. Still
   need a heartbeat when idle.
5. **Typed-text input** path (G2CC has an `input event 'text'` → `onTypedText` flow).
6. ~~**Dirty-rect addressing constraints**~~ — **RESOLVED 2026-08-16**, see §4: mode 3 is
   quantized (left/width ×4, top/height ×2, one byte each, bounds-checked to 640×480); mode 8 is
   size-capped (~153 KB), not count-capped; `CFW_RECT_MAX=16` is a debug-overlay limit only.
7. ~~Whether **2.2.2 → CFW direct flash** is accepted~~ — **largely de-risked 2026-08-16.**
   Faceclaw's own onboarding classifier (`app/g2/firmware-compat.ts`) sets
   `FLASHABLE_STOCK_VERSION = [2,2,6,10]` with the comment *"Stock at or below this can be flashed
   with our patched image; a newer stock version is unrecognized."* Our **2.2.2.20 ≤ 2.2.6.10**, so
   the reference client classifies us **`flashable-stock`** and will flash without an override.
   The patch set targets the *stock 2.2.6.10 image it ships*, not the version already on the
   device — g2flash writes a whole EVENOTA container. ⚠ Still an inference from the author's
   intent, **not** evidence that anyone has flashed from 2.2.2 specifically.

### New leads opened 2026-08-16 (from Even's own schemas — see §9.1)

- 🟡 **A logger service exists on sid 0x0F** (`UI_LOGGER_APP_ID`). `logger.proto` defines
  `BLE_LOGGER_SWITCH_SET`, `BLE_LOGGER_LEVEL_SET`, `DEVICE_SEND_LOGGER_DATA` (device → phone
  `logStr`), `REQUEST_FILE_NAME`, `DELETE_FILE_NAME`. **If `bleTransEn` works on our firmware we
  get live on-glass logs** — including the CFW's `evenhub_ui: decompress failed, mode=%u
  raw_len=%u`. That converts our worst failure mode (silent garbage on the lens) into a visible
  error, which is exactly what the NO SILENT FAILURES rule wants. **Highest-value untested lead.**
- 🟡 **A file EXPORT service exists** — `UX_EVEN_FILE_SERVICE_CMD_EXPORT_ID = 198` /
  `RAW_EXPORT_DATA = 199`, with `eEvenFileExportServiceCID {EXPORT_START, EXPORT_DATA,
  EXPORT_RESULT_CHECK}`. There are also `UX_OTA_EXPORT_FILE_CMD_ID = 194` / `195` with **no
  schema in this corpus**. ⚠ **This is NOT evidence of a firmware dump path** — it is a *file*
  service, and the main app image is not necessarily a file in that namespace. But it is the first
  real lead against "no read-back path exists," and it is worth a read-only probe. `common.proto`
  gives us a safe probe: `eErrorCode` has explicit `NOT_SUPPORT = 8` / `SUPPORT = 9`.
- 🟡 **`BleConnectParam { MTU, connInterval, setSpeed: SLOW|FAST }`** exists on sid 0x80,
  `commandId = BLE_CONNECT_PARAM (7)`. Our link ramps 15 → 30 → 90 ms because the *firmware*
  requests the low-power state ~2 s after connect. A `setSpeed = FAST` may pin it — a direct lever
  on the ack latency that prices this entire project. ⚠ On the dangerous sid; see below.
- ✅ **We now know exactly why sid 0x80 is dangerous.** `dev_config_protocol.proto`:
  `UNPAIR_INFO = 9` and **`RESTORE_TO_FACTORY_SETTINGS = 13`**. That is the brick g2-kit hit.
  The rule sharpens from "never touch sid 0x80" to **"commandIds 9 and 13 are destructive;
  4 (AUTHENTICATION) and 128 (TIME_SYNC) are what the official app already sends."**
- ✅ **Ring battery is solved without decoding the ring's own link.** `ring.proto` on
  `UX_RING_DATA_RELAY_ID = 145` (our `91-XX`): `RingRawData{battery=1, chargeStates=2, hr, spo2,
  hrv, temp, actKcal, allKcal, steps + timestamps}`. Retires the residual RE item in
  `G2_BLE_PROTOCOL.md` §10/§11.
- ✅ **sid 0x0D is `sync_info`, not "configuration query"** — `sync_info_data_msg
  {backgroundAppID, foregroundAppID}`. It tells us when EvenHub is fore/backgrounded, which
  Damage needs in order to know whether its surface is actually visible.
- ⚠ **Several §4 init-frame labels conflict with the vendor schemas and need a re-decode pass
  against the captures**: `81-20` is `UX_GLASSES_CASE_APP_ID` (case SoC/lid/charge), not "Display
  Trigger"; `04-20` is `notification.proto` `NotificationControl{notifEnable, autoDispEnable,
  dispTime, avoidDisturbEnable}` — our `{f1=1 f2=1 f3=7 f5=1}` reads cleanly as *notifications on,
  auto-display on, **7-second display time**, avoid-disturb on* — not "Display Wake"; `07-20` is
  `UI_FOREGROUND_EVEN_AI_ID`, not "Dashboard"; `0e-20` is `UI_HEALTH_APP_ID`, not "widget config";
  `0c-20` is `UI_QUICKLIST_APP_ID`, not "Tasks"; `10-20` is `UI_ONBOARDING_APP_ID`; `20-20` is
  `SERVICE_MODULE_CONFIGURE_APP_ID` (language + dashboard auto-close), not "Commit".
8. ~~**Transport for flashing**~~ — **RESOLVED 2026-08-15.** beardos has a working BLE radio:
   **Intel AX201** (`8087:0026`), BlueZ 5.86, `hci0`, controller `C4:BD:E5:2E:C9:75`, bluetooth
   service started. `g2flash`'s `g2://local` transport via `bleak` should work directly from the
   PC — no DroidBridge needed. *(Still unverified: that the AX201 negotiates the link params the
   glasses want, and that the glasses advertise for a direct connection while the phone is off.)*
   Fallbacks if it misbehaves: DroidBridge (Android GATT-over-WebSocket, **public availability
   unconfirmed**) or the SybilSight webflasher's Web Bluetooth path.
9. Whether the webflasher's "recovery set" captures a **restorable image of our glasses** or just
   hands us the matching *archived official* bundle. If the latter, it does not help for 2.2.2.

### On the "cheapest high-value experiment"

`g2flash` `demos/video-bench.ts` has an **`lz4` mode that runs on STOCK 2.2.6.10**, reporting
achieved framerate and byte counts with **no custom firmware**. Earlier text called this the
cheapest high-value experiment. Two corrections, in order:

1. *2026-08-16:* it is **not cheap** — going 2.2.2 → 2.2.6.10 **is** the irreversible step, since
   2.2.2 is not in the archive. The "no CFW required" framing hid that it costs the same one-way
   door as flashing the CFW itself.
2. *2026-08-17:* **and it is not high-value either.** What it measures is ack latency, which
   unknown #1 above re-grades as a tuning constant. Paying an irreversible cost to refine a number
   that changes no decision is the worst trade in the project.

⇒ **Do not run this as a gating pre-flash experiment.** After first light it is a perfectly good
way to price the ack round-trip — sweep `G2_WINDOW` (1, 2, 4) — but as *calibration for tuning*,
not as a checkpoint anything waits on.

**The general lesson, worth more than the specific finding:** a number can be load-bearing for a
decision *already made* and carry zero weight for every decision still open. §5's estimates
answered "is this architecture viable at all," and they answered it with enormous margin. Once the
margin is that large, refining the input is wasted motion. **Before elevating any unknown, ask
which open decision changes if the answer flips. If none does, it is not a blocker.**

---

## 12. Design decisions already made

- **Damage tracking with a single mode-8 flush per frame** is the core architecture (§4).
- **Coarse scroll steps**, never per-pixel (§5).
- **Fixed cursor + panning list**: pin the selection to a fixed screen row and pan content under
  it. This recovers the near-zero-latency scrolling that the firmware list widget gave G2CC for
  free — and improves on it with smooth pixel scrolling. **This is the answer to the one genuine
  regression of leaving firmware containers.**
  🔑 **Dependency, identified 2026-08-17: this design requires PER-NOTCH scroll input, and it is
  only buildable because we get it (§6.1).** Under G2CC's firmware containers, scroll was a
  *boundary* event — the widget owned the scroll and only reported the ends — so a fixed-cursor
  panning list could not have been driven at all. Dropping firmware containers is what supplies
  the input this decision rests on. ⚠ The evidence is graded **C**, not **M** (read from
  Faceclaw's code, not our own wire) — **if per-notch scroll turns out not to work, this decision
  falls with it**, and the fallback is coarse page-flips on boundary events.
- ~~**Save-under for overlays** via mode 9 (pending unknown #2)~~ — **dead as of 2026-08-17**; its
  premise (off-panel scratch) does not exist, the full 640×480 is visible (§2). **The fallback is
  now the plan:** a mode-3 repaint of the covered region (~215 ms for a 300×80 toast), which we
  already judged acceptable. Revisit once **texture caching** lands (§11 #2) — that is the real
  version of what save-under was reaching for.
- **No dithering** — it halves compression and the host does better 4-bit downsampling anyway.
- **Anti-aliased text at 16 levels** — the biggest visual upgrade available.
- **Keep the both-temple silent-mode gesture** as a hardware escape hatch.
- **Damage must provide its own quit path** (the CFW removes the stock one).
- Depth: **main content parked far, popups come forward toward the screen plane** (Adam direct,
  2026-08-17 — this *reverses* an earlier inferred rule; see §7 and `DESIGN.md` §3).

**Added 2026-08-17 — hard-won tuning from Faceclaw's damage tracker** (`BleImageOptimizer.java`,
the closest existing analogue to what Damage does). These are results, not preferences:

- 🔑 **Deflate level 6, not 9 and not 1.** His comment: level 9 (BEST_COMPRESSION) costs
  **18–109 ms per frame** on the BLE worker; level 1 (BEST_SPEED) **inflated typical payloads from
  ~2.7 KB past the 3800 B fragment boundary, adding a whole extra ack round trip (~350 ms).**
  Level 6 keeps payloads inside one fragment at about half the CPU. The tuning target is the
  **fragment boundary**, not the compression ratio — non-obvious, and we'd have got it wrong.
  *(`research/fbfeas.py` already uses level 6.)*
- **One long-lived `Deflater` per thread** — constructing one allocates a native zlib stream, which
  is measurable at per-frame rates.
- **Splitting into multiple rects is not free beyond the byte count:** ~15 fixed bytes per rect
  (seglen + mode-3 header + fid + zlib framing) **plus the loss of cross-rect zlib dictionary
  sharing**. Only split across gaps big enough to pay for both. Keep gap thresholds above the
  4px/2px box alignment so aligned rects can never overlap.
- His splitter is two-pass: extend **vertical bands** across changed rows hopping gaps < V_GAP,
  then split each band into **horizontal clusters** hopping column gaps < H_GAP, then tighten each
  cluster's rows. Diff scan runs in **packed-byte coordinates** (1 byte = 2 px) and reports both
  the bounding box and how many disjoint column-clusters the change splits into.
- **Window geometry worth stealing:** two height modes — `min` = 288 px (the stock band, "leaving
  most of the field of view clear") and `max` = 480 px (terminal-style views) — plus a **vertical
  position setting** (top/upper/centre/lower/bottom) that slides the 288 band within the 480 px
  screen. That is a genuinely good use of the extra height: **placement rather than more content**,
  which is exactly the FAR/ignorable tradeoff (§7).
- **Colour-key compositing:** pixel 0 = transparent, **1 = intentional opaque black** (identical to
  0 after 4bpp quantisation). Shell layers need this to composite at all.
- **Layer stack with lazy `paintBelow`:** a layer only pays to paint what's underneath if it
  actually samples it, and `paintOverBase` lets a layer composite against the base directly,
  skipping intermediates. **This is the practical substitute for save-under** now that off-panel
  scratch is gone (§11 #2).

**Added 2026-08-17, from the reference implementation (§2, §4, §4.1):**

- **Carrier layout = one 576×288 image container + one full-screen dummy text container**
  (`content=" "`, `isEventCapture=true`). Not optional; see §4.1.
- **Hold the direct-framebuffer lease** — sid 0x09 field 101 op 5, **both arms, renew every 45 s**
  against a 90 s expiry. Fail-open means forgetting it silently loses the screen.
- **Arm split: bulk pixels → LEFT, control + events → RIGHT.** Subscribe on Right regardless.
  Verify at first light (§2) — this is the one new claim graded *strong, not proven*.
- **≈6 rects per mode-8 batch**, not 16; `fid` in `[1,0xFFFE]`, +1 per delta (§4).
- **Window 3 in-flight image messages.** Pipelining is proven on CFW; do not ship strictly serial.
- **Leave the mode-7 diagnostic overlay ON during bring-up** and treat any sticky flag
  (`f_reorder`/`f_skip`/`f_dup`/`f_snap_of`) as a hard error (§4).
- **Image fragments ≤ 3800 B** (the 4096 cap with envelope headroom) — two independent sources.
- ⚠ **Layout/CREATE frames must stay under ~1000 B.** That limit is real, and applies *only* here.

## 13. What we want to build on top

Everything G2CC does, rebuilt without the image tax, plus what was never possible:

- **FF1** rendered properly instead of avoided
- **Far more games** — real frames at interactive rates
- **A real ebook reader** — proper typography, AA text, endless scroll
- **A file manager with real icons and thumbnails**
- **Mail and MMS with embedded images**
- **A Reddit-style feed** with endless scroll
- Real **modal dialogs**, **toast notifications**, **z-order**, **overlapping windows**
- Access to the whole PC library — books, data, imagery, everything

---

## 14. What this is, legally and practically

A **personal, first-party project** for hardware Adam owns, on his own home network, with his own
accounts. Licensing was explicitly **deferred** — "this is a personal project. If I ever consider
making a version for release, I will figure out the best way to handle licensing." For reference:
g2flash is GPL-3.0, openCFW's canvas480 is GPL-3.0-only, the webflasher is MIT, and openCFW's top
level has **no license at all**.

### ⚠ Licensing is no longer fully open (2026-08-17)

Adam asked Babcock directly whether he could borrow heavily from Faceclaw and g2flash. The answer
was yes — **"subject to GPL."** So:

- **Personal use: unchanged.** GPL-3.0 imposes no obligation on software you don't distribute, and
  personal use *is* the stated goal (§1). Borrow freely.
- **Public release: constrained, not deferred.** If Damage derives substantially from Faceclaw or
  g2flash, **GPL-3.0 attaches on distribution** — reciprocal source release for the whole derived
  work. That is now a known input to the release decision rather than an open question.
- **The cheapest way to keep the option open** is to keep the boundary clean *as we build*: our own
  compositor, layout engine and font rasteriser in our own tree, with borrowed CFW-protocol
  knowledge (wire formats, constants, the lease protocol) kept separate from borrowed *code*.
  Facts about a wire protocol are not copyrightable; his implementation of them is.
  **Not a reason to avoid reading his code — a reason to know which is which while doing it.**

### 🔴 DECIDED 2026-08-20 — clean room, and it is no longer hypothetical

**Damage contains no `faceclaw` or `g2flash` code, and never will.** Protocol knowledge only; the design does
not need the implementation. An off-the-cuff public comment saying Damage would use *"some borrowed
code from FaceClaw"* is **retracted**.

What Damage *does* borrow heavily is **G2CC** — Adam's own, licence his to set, and far more mature:
shipped, tested, and in daily use. Anything he does not control (Universal Paperclips, the FF1 ROM,
Even's SDK) stays out of any release exactly as it already does in G2CC.

**Why the timing matters:** a public, cross-platform release is now intended (§10 of `DESIGN.md`)
and compensation is a live possibility. GPL-3.0 attaching to the whole derived work would foreclose
options that are open today. The boundary costs nothing to keep before the compositor exists and is
close to unfixable afterwards. The rule is in `CLAUDE.md`.

## 15. Collaboration — the Babcock thread (opened 2026-08-16)

He reached out first, having noticed the overlap between G2CC and Faceclaw (he found the address by
grepping `adam` in the public g2cc repo — see the hygiene note below). Current state:

- **His offer:** pull changes and add credits, **or work in a shared repo** — he prefers the latter,
  noting "the natural scope for a G2 UI replacement project is a bit larger than the scope of all
  the stock firmware and apps combined."
- **His current work, all relevant to us:** (a) **EvenHub compatibility** — running EvenHub apps in
  a webview, rasterising the layout to an image and sending that, plus extensions over the stock API
  to give app devs a reason to switch firmware; (b) **font configurability** (he prefers small
  fonts, ships Terminus 6×12, doesn't expect it to suit everyone); (c) 🔑 **a texture cache in the
  firmware protocol** — see §11 #2. That third one is the highest-value thing to coordinate on,
  because it lands in the firmware we depend on.
- **Adam's stated position:** personal use, no Hub compatibility wanted, **PC-powered with the phone
  as a Tailscale bridge** — which he flagged to Babcock as possibly incompatible with Faceclaw's
  phone-resident design. That is the honest seam: **Faceclaw runs on the phone, Damage runs on the
  PC.** Collaborate below the UI (firmware, wire format, capability negotiation); stay independent
  above it (compositor, apps, fonts).
- **What we have already given back:** the g2-kit docs-vs-code discrepancies, including the
  `is_last` / `CompressMode` footgun, raised because `faceclaw/CLAUDE.md` still points contributors
  at `g2-kit/ble/docs/`.

### Hygiene: contact details are exposed via the public G2CC repo

Babcock made contact by finding Adam's address in `github.com/expectbugs/G2CC`, which is public and
carries it in both tracked files and commit metadata. **The specific inventory has been redacted
from this document** — it lived here only as a to-do, and enumerating addresses and their file
locations in a public repo is the same mistake it was describing.

⚠ The load-bearing part is unchanged and worth keeping: **a working-tree scrub does not remove
anything from git history.** Deciding what to expose has to happen *before* the first push, not
after. **Adam's call; G2CC is not ours to edit** (§10).
