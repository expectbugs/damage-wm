# Damage — a framebuffer window manager for the Even Realities G2

**What this is:** a personal, first-party project on hardware Adam owns — his own G2 glasses,
phone, PC and home network. The custom firmware is installed on a consumer wearable he bought;
the Bluetooth wire format below was worked out from captures of his own pair. **The name is the
graphics term** — *damage* is the set of screen regions that changed since the last frame (the
X11 DAMAGE extension); batching a frame's damage into one message is the thesis (§5).

**Status (2026-09-02): BUILT, FLASHED, LIVE as the all-day daily driver, and into the app
wave.** Research closed 2026-08-17; shell built 2026-08-24/25; CFW installed 2026-08-30
(`HANDOFF.md` §10), first light the same day (§11); refinement wave 2026-08-31 (§12); phone APK
primary driver with the PC as data provider + standby the same night (§19); Files 2026-09-01
(§22), Torrents + the on-glass keyboard (§23), Music 2026-09-01/02 (§24, `MUSIC.md`), Games ·
Hold'em 2026-09-04 (§26, `HOLDEM.md`). App layer: Main · Settings · Reader · Tmux · Files ·
Torrents · Music · Games. This file is **the fact record** — read it with
[`CLAIMS.md`](CLAIMS.md) for grades; the app wave's records are `EXPLOSION.md`, `WINDOWS.md`,
`TORRENTS.md`. ⚠ Facts below marked "our firmware is 2.2.2.20" are historical: **the pair now
runs the CFW** (g2flash `a5d1c31`, reporting `2.2.6.10`; detect by `EVENCFW/`, never the version).

What closed the phase: both BTSnoop captures recovered and re-decoded against Even's own 27
protobuf schemas; the CFW image verified reproducible offline; Faceclaw read for prior art;
every load-bearing claim graded in `CLAIMS.md`; the image-retention probe run on hardware (§1).
What is still unmeasured on hardware is the table in `REMINDER.md` ("Still unmeasured on glass").

This document is the complete carry-over from the research that produced the decision (2026-08-15).

> 📋 **Read [`CLAIMS.md`](CLAIMS.md) alongside this** — it grades every claim here (V/M/C/I/S/U)
> and lists the things most worth distrusting. Four claims stated here as fact were wrong within
> two days, each time because prose disagreed with working code.

> 🎨 **The shell is specified in [`DESIGN.md`](DESIGN.md)** (locked 2026-08-17/18, grown with the
> shipped revisions): input grammar, 640×480 cell geometry on the mode-3 grid, depth order,
> motion/persistence/failure policy, the shell surfaces, the locked typefaces, per-frame costs
> **measured from real renders**. Successor to G2CC's `docs/DE_DESIGN.md`; **wins on shell
> design**; this file wins on facts. Alongside it: **`tools/lint.py` + `tools/geometry.py`** (the
> `DESIGN.md` §9.2b build gate — SYM/GEO/BUD/FID, 21 rules; `--selftest` fires 16 in 18 cases;
> the repo run exits 0; run after any geometry or layout change) and **`design/render_shots.py`**
> (every surface at **true 1× 640×480**, 4bpp, priced through the firmware's RLE, output in
> `design/shots/`; regenerate after any design change).

**Repo/package identifier:** `damage-wm` ("Damage" is the project name; the qualified form keeps
logs and issues greppable).

---

## 1. What we are building, and why

**Damage** is a from-scratch window manager and compositor for Adam's own Even Realities G2.
It treats the glasses as a **plain framebuffer**: the PC composes complete scenes with real fonts
and arbitrary layout and pushes pixels. This requires **custom firmware** (`g2flash`), which
replaces the vendor's EvenHub container model with direct framebuffer access.

It succeeds **G2CC** (`/home/user/G2CC`), a *working, shipped* system that Damage does not
replace on disk or edit. Damage has been the daily driver since 2026-08-31; G2CC is the
historical fallback (its stock-firmware display path no longer applies to this pair).

### Why this, and why now

G2CC originally *was* a framebuffer design. It pivoted away in commit **`709d18c`** (2026-06-10,
"tiles nixed for sessions — Adam's hardware verdict"):

> 15-20s taps with no feedback: every menu state change is an f1=7 rebuild and the renderer
> conservatively re-pushes ALL FOUR tiles per rebuild **(retention probe never run)** — a
> multi-second ack-gated tile storm per interaction.

The pivot to firmware text/menus bought "single ~62-86ms text/list writes" at the cost of every
design ambition; images were avoided everywhere because they cost seconds. The CFW removes every
named cause:

| what ended the original design | what the CFW does about it |
|---|---|
| `f1=7` rebuild on every menu state change | EvenHub layout system removed entirely — rebuilds do not exist |
| re-push all four tiles per rebuild | **dirty rects** — send only changed pixels |
| *(retention probe never run)* | ✅ **PROBE RUN 2026-08-17 — see below.** The re-push was required, not conservatism |
| 288×144 image-container cap forcing a 2×2 grid | one 640×480 framebuffer (the 576×288 container survives only as the carrier — §4.1) |
| no wire compression, ~13 KB/tile | zlib+RLE on the wire |
| ~1000 B multi-packet wall on layout frames | gone — not an EvenHub message |
| 12-container / 8-text / 4-image budget | gone |

### ✅ The retention probe, finally run (2026-08-17) — and it vindicates the whole decision

**Adam ran it on hardware, 2026-08-17: on a menu change the image disappears entirely and does
not come back until it is redrawn.** ⇒ **Images are NOT retained across an EvenHub layout
change; the four-tile re-push was mandatory.** Corroborated by g2-kit's `containers.md`: *"A
Cmd=7 UpdateContainer whose inner container has multiple ImageObjects atomically tears down the
old object list and rebuilds. For images, this invalidates the tile buffers — you have to re-push
Cmd=3 UpdateImageRawData for every tile id before the frame will render,"* and a REBUILD *"can
silently blow away sibling containers in the plugin task."* **Graded M.**

Consequences: (1) the pivot away from framebuffer-on-stock was correct; (2) **the CFW is the only
path, not an optimisation** — under stock EvenHub any layout change discards image content, so
damage tracking cannot help while firmware containers own the layout; (3) this is a
*hardware-confirmed* ceiling on G2CC, not a defect awaiting a fix *(and since 2026-08-30 a return
to it would also mean flashing stock back)*.

g2flash's restriction — *"you cannot mix this mode with EvenHub list or text containers"* — costs
the framebuffer design nothing: it uses no firmware containers.

### What this unlocks that G2CC could never have

- **Custom fonts** — real TrueType/bitmap faces, kerning, **anti-aliased text at 16 gray levels**
  (the stock LVGL font is effectively 1-bit). Biggest single visual win.
- **Overlays / z-order** — notifications on top of content, real modal dialogs (G2CC crammed
  notifications into the title bar).
- **Arbitrary layout** — no region ids, no container budget.
- **Images everywhere** — thumbnails, embedded images, game frames, an ebook reader.
- **Endless scroll** (§5); **more input** — long-press and release as app events (§6).
- **Piezo buzzer** *(available; excluded by Adam — `DESIGN.md` §0, mode 5 is never sent)*.
- **Wear/unwear detection, magnetometer compass, per-lens (stereo) output.**

### Adam's stated build methodology (follow this order)

> Heavy research → full documentation → clean repo → the main plan → a couple hundred
> ridiculous feature-creep scope explosions → heavy refinery to bring it back to reality →
> passes for consistency and adherence to the research/documentation → a final plan of the
> actual implementation via real code → **then** slowly and carefully start executing.

Bank research and documentation **before** the scope explosion. "Feature creep is my RELIGION" —
the explosion is deliberate; the refinery keeps it shippable.

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
| **Image-push ack latency** | **median 176 ms** (range 117–180) on stock — the dominant cost term. 🆕 **CFW direct-FB path measured 2026-08-31: `ms ≈ 60 + bytes/50` (§5.2)** |
| Framing | AA envelope; **CRC-16/CCITT-FALSE** over the *entire reassembled* payload, final packet only |
| msgId | **1 byte** — the glasses stop acking at 255 (~223 acked ops), then silence |
| Multi-packet wall | ⚠ **LAYOUT FRAMES ONLY** — see the correction directly below. **Does not apply to image data.** |
| Keepalive | `f1=12` every ~4–5 s. **`f1=9` = exit — never send it** |
| No | glasses mic/audio over BLE (disconnects >25 s), stock wear detection |
| ⚠ IMU | **NOT out of scope after all** — EvenHub has `Cmd 19/20`, `IMU_CtrlCmd{IMUReportEn, reportFrq}`, `IMU_Report_Data{double x,y,z}`, `OsEventTypeList.IMU_DATA_REPORT=8`. From Even's own schema (§9.1). Never enabled by Damage (`DESIGN.md` §7.1: head tracking defaults OFF); untested on the CFW |
| Physical | **the glasses have no power switch.** Case is the only power control |

**Our firmware — HISTORICAL (superseded 2026-08-30):** the captures in this section were taken
on stock **`2.2.2.20`** (ring `2.2.0.0014`). The pair now runs the CFW (g2flash `a5d1c31`,
reporting `2.2.6.10`) and the ring `2.2.6.0009` — `HANDOFF.md` §10/§10.13.

### 🔴🔴 THE CANVAS IS 640×480, NOT 576×288 (2026-08-17, from the CFW author)

**Source: James Babcock, by email, answering our question directly** — the largest factual
correction in this document; every layout number written before it is scoped to the wrong surface.

> "The full 640x480 area is visible. You can lose part of the top or bottom to optical occlusion
> depending how the glasses sit on your face, but if they're centered right a full-screen UI can
> usefully use the whole thing."

We had assumed a 576×288 *panel* inside a 640×480 *shadow*. In fact **the panel is 640×480 and
stock does not use all of it.** The CFW source agrees: `FW_DISPLAY_COPY` is "the stock **576x288 →
640x480** packed copy," `FW_DISPLAY_FB` "the stock copier's **640x480** destination," and
`PANEL_W/PANEL_H` are 640/480 with `copy_panel()` moving all 153,600 bytes. 576×288 is the
**EvenHub container** geometry — a carrier, not the display.

| | old assumption | actual |
|---|---|---|
| usable surface | 576 × 288 | **640 × 480** |
| pixels | 165,888 | **307,200 (1.85×)** |
| raw 4bpp full frame | 82,944 B | **153,600 B** |

**Consequences:** 1.85× the canvas; ❌ **off-panel scratch does not exist** (old unknown #2,
resolved by deleting its premise — nowhere to pre-render-then-flip via mode 9; §12); full-screen
keyframes cost ~1.85× more area (measured: a dense ~10 KB keyframe lands ~200–270 ms on the CFW
curve, §5.2) while **dirty-rect costs are unaffected**; and **usable extent is fit-dependent** —
keep load-bearing UI centred, outer rows are bonus.

#### Refinement, same day — what the headroom is actually *for*

> "**The width headroom is used for a depth effect** (shift the eyes left/right independently to
> a[ppear] closer/farther). The height headroom has some bad reasons, but mainly it's just that
> **covering up too much FoV is annoying. Most Faceclaw UI is 640x288 for this reason**, but the
> Terminal app is full height so that it can fit a verbose Fable end of turn summary."

1. **The 64 columns of width headroom (640 − 576) are the stereo-shift budget** — what §7's
   "lenses differ" flag spends. Full-640-wide spends the depth allowance on pixels (not a hard
   rule — he uses full width himself).
2. **His default is 640×288**, for a reason Adam holds independently: covering too much field of
   view is annoying (the same instinct as display distance FAR, §7). Full height is a per-app
   choice (his Terminal).

⇒ **Working guidance: 640×288 default; 640×480 when a specific app earns it; leave width margin if
that app wants depth.** *(Built: Damage ships **480 as the Global default** — Adam's fit loses the
bottom, so the four sizes 288/352/416/480 are TOP-aligned — with a per-app `preferredHeight`:
Reader, Tmux and Music prefer 480; Files, Torrents and Music offer global + all four —
`DESIGN.md` §2.2b/§2.4 rule 4/§4.2, `REFINEMENT.md` §2.)*

### 🔴 Two corrections to the table above (2026-08-17)

Both were assumptions that had hardened into "hardware facts."

**1. The ~1000 B multi-packet wall applies to LAYOUT frames, not image data.** The original claim
(`G2CC/docs/DE_DESIGN.md:175`) is scoped: an **`e0-20` layout/launch frame** (`f1=0` / `f1=7`)
past ~4–5 AA packets (~1000 B) gets no ack and no error. **Image `f1=3` chunks are 4096 B across
~18 AA packets and work** — the official app's own behaviour in our captures; Faceclaw ships
3800-byte fragments continuously. Read literally, the old row would have capped a mode-8 batch
at 1000 B where the CFW permits ~153 KB. Keep the ~1000 B limit *only* for CREATE/REBUILD frames.

**2. "Right lens is the one you drive" is wrong for CFW image traffic — the reference
implementation drives LEFT.** In Faceclaw `ConnectionOptions.sendImagesToLeft = true` is a
hardcoded final; **all five image call sites pass it**, `writeMessage()` resolves
`writeAddress = isLeftArmMessage ? leftAddress : rightAddress` to one address, and **no image
message is constructed with `leftArm = false`.** Control traffic (heartbeat, settings, shutdown,
audio, IMU) goes to Right. So the reference split is **bulk pixels → LEFT, control + events →
RIGHT** — keeping the ack/event link clear of image traffic. It fits `zlib_glue.c`, which moved
image handling to the deferred path because "the sync-completion path (`image_complete`) runs on
only the RECEIVING lens, so doing the work there leaves the other lens blank" — the firmware
propagates cross-lens, so **either** arm may receive.

**Grade: the split WORKS (M — daily since 2026-08-30); its optimality is still inferred** from
Babcock's code, not from a capture of our own. **The two-arm capture is still owed**
(`REMINDER.md` item 5; start BTSnoop before connecting). Unchanged: **subscribe to RIGHT for async
events**; Left is silent.

---

## 3. Firmware landscape and version timeline

| version | what landed |
|---|---|
| **2.2.2.20** | what our glasses ran until 2026-08-30 (now the CFW). Pre-compression |
| 2.2.4.34 | (archived; seen in CFW detect output) |
| **2.2.6.10** | **"Improved Even Hub graphics rendering and image delivery."** Paired with Even App 2.2.6 + **SDK 0.0.12: "Improve Image compression algorithm"** (LZ4). **This is the CFW base.** |
| 2.2.7.14 | the version the community performance numbers were measured on |
| 2.2.8.x | **localization/stability only — no graphics work.** Current stock as of 2026-08 |

**The ~3.5× platform jump happened in 2.2.6.10 + SDK 0.0.12 (July 2026):** 200×100 image
updates went from ~2 fps to ~7 fps. Our 2.2.2 predated it.

### Reversibility (verified)

- **`g2flash` is write-only.** No firmware read-back path is known (the vendor file-export
  service in §11 is the one unprobed lead). *(So the running 2.2.2 could not be imaged before it
  was replaced 2026-08-30.)*
- Even's CDN is content-addressed (`https://cdn.evenreal.co/firmware/<md5>.bin`) — not
  version-enumerable.
- **A public archive exists**: `SybilSight-webflasher` `public/firmware-updates/source-files/`
  holds **19 G2 images** — 2.0.1.14, 2.0.3.20, 2.0.5.12, 2.0.6.14, 2.0.7.16, 2.0.8.20, 2.0.9.20,
  2.1.1.8, 2.1.1.12, **2.2.0.24**, **2.2.4.34**, 2.2.6.10, 2.2.6.11(CFW), 2.2.7.14, 2.2.8.4,
  2.2.8.9, 2.2.8.10, 2.2.8.11, 2.2.8.11-runtime-fix — plus 11 R1 images including
  `r1/2.2.0.0014` (**our ring's exact version**).
- ⚠ **2.2.2 is NOT in that archive** (it jumps 2.2.0.24 → 2.2.4.34). **Leaving 2.2.2 was the one
  irreversible step — taken 2026-08-30.** Every *other* version is one SHA-pinned download away.
- **CFW itself is easily reversible**: an official-app OTA "will fully remove the custom
  firmware and restore stock behavior"; or flash an unmodified image with g2flash; or Faceclaw's
  "Uninstall firmware".
- **Flash 2.2.2 → CFW directly** — `g2flash` writes a whole EVENOTA container. *(Verified
  2026-08-30 on our own pair: 2.2.2.20 → CFW, both lenses, six components, zero resends —
  `HANDOFF.md` §10.)*

### Unrecoverable-image risk (researched precisely — judged acceptable, decision made)

The bootloader programs the main app to `dst = preamble[0x14]`, `len = preamble[0]&0xFFFFFF`,
**with no bounds check**. Reaching the OTA flag `0x7FE000` overwrites it and the BLE-bond/KV NV
band; reaching MRAM end `0x800000` faults mid-erase → a boot loop **not restorable over the
radio, SWD-only recovery**. `check_mainapp_fits_mram()` in `g2flash.py` is "the ONLY guard."

**This fires only on an ENLARGED image — and the CFW IS enlarged.** ⚠ *Corrected 2026-08-16; the
earlier claim that the patch set is length-preserving was wrong and was load-bearing for this
assessment.* Patch 19 of 25 appends **20,127 bytes** at offset 4301227; patches 20–25 clean up
after it: main-app subheader payload size, TOC entry size, **preamble length (low 24 bits)**,
preamble CRC32, component CRC32c ×2. Measured from the archived images: stock 2.2.6.10 main image
**3,523,364 B** → CFW **3,543,491 B** (+20,127 — exactly the blob).

Safety rests on three things, not length-preservation: (1) the patch set **does** bump the
preamble length; (2) `check_mainapp_fits_mram()` validates it; (3) **headroom** — CFW image end
`0x007991C3` vs the OTA flag at `0x7FE000` = **~403 KB**. The SHA-256 pins on stock download and
patched output prove you got the reviewed image. **The remaining hazard: our own patches that grow
the main app without bumping the preamble length.**

Second hazard: the c0/c1 OTA path has **no block index and no dedup** — re-sending an
already-written block double-advances the flash offset and leaves it inconsistent. Hence
`--block-nak-retries` / `--component-retries`.

### ✅ The image is reproducible from sources we hold — verified offline (2026-08-17)

`research/verify_cfw.py` rebuilds the CFW from our local stock image and checks every pinned
hash — no network, no glasses. **All checks pass:**

- local stock 2.2.6.10 **==** the base SHA-256 both patch sets pin
- g2flash's 25 patches **==** g2flash's own pinned output
- SybilSight's 28 patches **==** their pinned output **and** the archived `g2-2.2.6.11.bin`,
  byte for byte
- **no Thumb-bit defect** in either rebuilt blob (14 constant interworking branches, all Thumb —
  run against *our own rebuild*)
- delta between the two CFW images: **15 bytes in 6 runs** — three ASCII version digits
  (`s200_v2.2.6.1`**0**→**1**, settings-reported version, product-test 0x24) plus CRC fixups

**Re-run this before any flashing conversation.** Mitigations: `--stop-before flash` (full dry
run, no writes), `--lens left|right|both`, an interactive "my warranty is void" prompt, and the
webflasher's stricter transfer protocol (explicit ACK per 4 KiB block, END verification per
component, an ambiguous ACK restarts the whole component rather than replaying a block). *(The
webflasher has since dropped CFW support upstream; our flashes go through `g2flash.py` PC-direct
— `HANDOFF.md` §10.)*

---

## 4. The CFW display-mode contract (**the most important technical artifact here**)

Source: `g2flash` `patches/zlib_glue.c` header comment (and, for modes 12–15,
`patches/texture_cache.c`). Dispatch is on the image's own leading bytes: `'BM'` = BMP, otherwise
a small u8 mode. Custom modes **3/6/8/9/13/14/15 operate on the full 640×480 physical image**
(packed 4bpp shadow); they go around LVGL, serialize on the stock display semaphore, and
`display_copy_hook` copies straight into the physical framebuffer before panel refresh.

**⚠ Updated 2026-08-30 for g2flash `a5d1c31`** (was `877c8d9`): purely additive — modes 3/6/8/9
**unchanged**, mode 8's accepted sub-mode set grew, modes 11–15 new.

| mode | payload | meaning |
|---|---|---|
| **6** | `[6][zlib(rle)]` | keyframe: seeds the persistent 640×480 shadow, then direct FB refresh |
| **3** | `[3][l/4][t/2][w/4][h/2][fid16][zlib(rle)]` | **dirty-rect delta** onto the shadow + refresh. **Requires a prior mode 6.** The only mode that burns a `fid` |
| **9** | `[9][srcrect][dstrect]` | **rect-copy INSIDE the shadow** (uint16 coords). "Pairs with a delta (usually via mode 8) to scroll." **Moves pixels on-device, transmits none** |
| **8** | `[8][count][len16][submsg]…` | **multiple ops in ONE atomic message** — "e.g. scroll = rect-copy + delta". No nesting. Sub-modes **3/6/9/13/14/15** (was 3/6/9) |
| **7** | `[7][sub]` | diagnostic overlay control: 0 = clear sticky flags, 1 = hide, 2 = show. Hidden by default |
| **5** | sub-dispatch on `src[1]` | kind 4 = buzzer tone sequencer (≤48 steps) |
| **10** | `[10][0\|1]` | compass/heading BLE forwarding. **No collision in practice — see below** |
| 🆕 **11** | `[11]` | **session cleanup** before disconnect: releases the FB lease and direct-FB ownership, frees the texture cache, stops CFW timers/buzzer/compass, drops snapshots, hides the overlay. Extra bytes ignored |
| 🆕 **12** | `[12]([off16][len16][data])…` | **write the texture cache.** The whole entry list is validated before a single byte lands |
| 🆕 **13** | `[13][off16][x16][y16][opt8]` | **draw a cached image.** Payload after the mode byte must be **exactly 7 bytes** |
| 🆕 **14** | `[14][font16][x16][y16][opt8][len8][bytes]` | **draw cached glyphs** through a 96-entry offset table |
| 🆕 **15** | `[15][x16][y16][opt8][len8][UTF-8]` | draw with the firmware's own 20 px font + pair kerning. ❌ **Damage never emits this** — see below |
| `'B'` | BMP | `load_bmp_fast`, direct 4bpp-nibble→8bpp (`nibble*17`) expand |

### 🆕 The texture cache (modes 11–15, g2flash `a5d1c31`, 2026-08-30)

A **64 KiB, lease-scoped, phone-owned** cache, allocated and zeroed from firmware heap 13 on the
first mode-12 write.

**A cached image is `[width:u8][height:u8][4bpp RLE]` covering exactly `width*height` pixels —
NO pad nibble**, unlike modes 3/6, whose RLE runs over packed rows including the odd-row pad.
Same token alphabet, different pixel stream. Max 255×255. The scanner walks tokens until the
pixel count is met, so an image carries no explicit byte length.

**A "font" is a table of 96 little-endian uint16 offsets** for characters 32…127, each pointing
at a cached image; fonts can share a cache and glyphs. Mode 14 advances x by each glyph's **image
width**, applies **no kerning**; string bytes **1…31** are inline x adjustments of `b - 11`
(**−10…+20 px**), the only fit channel. Byte 0 and bytes > 127 make the firmware reject the
**whole string**; it validates every character before drawing any.

**The options byte** (modes 13/14/15): low nibble = top output colour; `lut[i] = (i * top) / 15`;
bit 4 (`0x10`) makes source colour 0 transparent, tested on the **original** level before the LUT;
bit 5 (`0x20`) reverses the ramp (`source = 15 - i`).

**Lifetime:** freed on lease expiry, on FB_RELEASE, on a *fresh* acquire after a lapse, and on
mode 11 — **kept across a renewal**, so an atlas is uploaded once per lease. Modes 12/13/14/15 all
require an active FB lease.

**Why it matters:** text and chrome become per-frame *references* instead of pixels — a line of
cached glyphs costs `9 + len` bytes regardless of ink, rides in the same mode-8 flush as the
deltas, and (only mode 3 burns a fid) is free of the 16-deep ring that caps deltas at ~6 per batch.

**❌ Mode 15 is deliberately unused.** Its glyphs come from an LVGL font chain inside the firmware,
so no offline model can predict its pixels and the compositor's per-lens belief (`LensOracleTest`)
would stop being exact; modes 13/14 draw from a cache *we* wrote, so the model reproduces them bit
for bit. The simulator refuses mode 15 loudly. (It is also the wrong typeface: `DESIGN.md` locks
four faces; the stock 20 px font is none of them.)

**The capability string changed shape** — `EVENCFW/8 img576 img640 imgz rle wakelease directfb
fbguard wearnotify compass10` (`patches/settings_ext.c:325` at the 2026-08-16 review; `/6` in
earlier notes) became `EVENCFW/16 img640 imgz rle wakelease directfb fbguard wearnotify cleanup11
texcache12 teximg13 texstr14 font15 micctl`. ⚠ `img576` and `compass10` were deleted **purely to
fit under 127 bytes**; both features still work. Never gate on tokens beyond
`SettingsMsg.REQUIRED_CAPS`.

**Mode-3 addressing is QUANTIZED** (answers old unknown #6): `left`/`width` ×4, `top`/`height`
×2, each a **single byte** — left ∈ 0…636 step 4, top ∈ 0…478 step 2, bounds-checked against
640×480. Deliberate: multiples of 4 make `left>>1` and `bw>>1` whole byte offsets, so each box row
is a plain byte run. **`fid` is a uint16 frame counter**; a fid still in the last-16 ring is
**silently skipped, not rejected**. Mode 9 uses full uint16 coords, no quantization.

**Mode 8's rect limit — corrected 2026-08-17.** An earlier version said "mode 8 has no practical
rect limit; `CFW_RECT_MAX = 16` is a debug-overlay constant." Half right: `count` is a u8, the
size cap is `118 + 320*480` ≈ 153 KB, and `CFW_RECT_MAX` is only the overlay-outline array — **but
a second, real 16-shaped limit exists by another mechanism.** Faceclaw:

```java
MULTI_RECT_MAX_RECTS = 6;
// Each rect consumes a distinct CFW frame id, and the firmware's duplicate-fid
// ring holds 16, so keep several batches of history within it.
```

**Every mode-3 sub-message in a batch burns its own `fid`**, and `recent_fids[]` is
`CFW_FID_RING = 16` deep. Overfill it across successive batches and a new delta can collide with
a remembered fid, which the firmware **silently skips**. Treat 6 as the known-good value and 16 as
the ceiling on *outstanding fid history*, not rects per message.

⚠ **The budget prices mode-3 deltas ONLY.** Verified in `debug.c`: `cfw_diag()` is called from
exactly two places in `zlib_glue.c` — the mode-6 keyframe (rebaseline, `has_fid = 0`) and the
mode-3 delta. Modes 9/13/14/15 never touch the ring, so a batch may carry six deltas *plus* as
many rect-copies and cached draws as fit under `bmp_max`.

Two more Faceclaw thresholds: `MULTI_RECT_MIN_PAYLOAD = 900` (do not split when the single
bounding box already compresses below this), and multi-rect falls back to one bounding box
whenever the split is not smaller.

### ⚠ The stale-compositing-base hazard — the failure mode that eats mode-3 deltas

Faceclaw's `INCREMENTAL_FRAMES` flag is now `true`, but its comment records why it was once off:

> *"the firmware-side display buffer is not always the previous frame (occasionally two frames
> back, apparently display-driver buffer swapping), so partial updates composite onto stale
> content per-lens."*

A delta onto an N−2 base diverges the screen **per lens**. `zlib_glue.c` states this is fixed at
the source: the worker runs on a per-frame **snapshot** drained in order by `image_deferred`, "so
successive deltas compose onto the shadow in the right order." The re-enabled flag is consistent
with that.

⇒ The CFW's diagnostic flags (`f_reorder`, `f_skip`, `f_dup`, `f_snap_of`, surfaced by **mode 7
sub 2**) detect this class. *As built:* the shell treats any set flag as a hard error — `PANIC`
status, urgent notice, forced keyframe, mode-7 sub-0 clear (`Shell.kt`, `CfwTransportBase`) — but
only the byte-exact simulator reports flags to it; on hardware they live in the on-panel overlay,
which Damage never switches on (no mode-7 sub-2 is sent). Hardware divergence is guarded by the
per-lens shadow/truth model, `LensOracleTest` and a keyframe at every session start
(`IMPLEMENTATION.md` → "Review hardening"); the only on-glass anomaly on record is the one-shot
left-lens seam residue after a handover (`REMINDER.md` watch-items). Switching the overlay on is a
one-line probe if divergence is ever suspected.

**Keepalive is handled for us** (old unknown #4): `image_worker()` calls `FW_KEEPALIVE_RESET()` on
**every** top-level image message — the same leaf the stock sid-0x0c heartbeat uses — so a steady
mode-3/6/8/9 stream needs no interleaved heartbeat.

- **RLE applies to modes 3 and 6 ONLY**, and the **RLE stream** is what gets deflated
  (`zlib(rle(px))`, not `zlib(px)`).
- **HIGH BIT of the mode byte = "lenses differ."** Mode 3: two boxes (L then R, **same size**)
  **sharing one zlib payload** — a stereo *shift* without duplicating pixels. Mode 9: two
  rect-sets. See §7.

### 🔑 The architectural consequence — mode 8 batching

**The ack floor — 176 ms on stock, ~60 ms measured on the CFW (§5.2) — is per *message*, not per
*rect*.** A damage-tracking compositor — clock tick + status icon + content line + notification —
flushes **all of it in one round trip**: **one ack floor + total compressed damage bytes, once per
frame** (`ms ≈ 60 + bytes/50`, measured). A single flush per frame is the design the firmware is
asking for, and the project's name.

### 🔴 4.1 The direct-framebuffer LEASE is mandatory (discovered 2026-08-16)

`directfb` is not free — `display_copy_hook` only preserves our frame while a **volatile
90-second lease** is held. Source: `g2flash/patches/settings_ext.c`, confirmed against Faceclaw's
`FaceclawBleCommunicator.java`.

```c
if (ctx && ctx->direct_active) {
    if (deadline != 0 && (int32_t)(deadline - FW_MS_TICK) > 0) return;  /* keep our frame */
    ctx->direct_active = 0;                       /* lease expired -> fail open to stock */
}
FW_DISPLAY_COPY();                                /* stock LVGL repaint paints over us */
```

The lease is armed over a **private control channel on sid 0x09**, as protobuf **field 101**:

```
field 101, bytes = ['F','C', version=1, op, nonceLo, nonceHi]
  op 1 ACQUIRE/RENEW     op 2 RELEASE        op 3 WAKE_CLAIM     op 4 WAKE_READY
  op 5 FB_ACQUIRE        op 6 FB_RELEASE     op 7 WEAR_QUERY
```

- **Lifetime 90 s; Faceclaw renews every 45 s** (`FACECLAW_WAKE_LEASE_RENEW_MS = 45_000`).
- **Sent to BOTH arms** — `display_copy_hook` runs per-lens. Faceclaw enqueues right then left
  and waits for both.
- The framebuffer lease (5/6) is **independent** of the wake lease (1/2). Damage needs 5/6.
- ⚠ **Fail-open is the design**: stop renewing and stock repaints silently overwrite our screen.
  A *correctness* requirement.

**Capability detection** is the same channel, **field 100** on the sid-0x09 settings READ
response: a string starting `EVENCFW/`. Tag 100 is above the stock fields (1..19), so stock
decoders skip it — detection needs no timeout-based probing. Faceclaw requires `img640`,
`fbguard`, `wearnotify`; Damage requires the five in `SettingsMsg.REQUIRED_CAPS` (`img640 directfb
fbguard imgz rle`), reads the contract version from `EVENCFW/<n>`, and treats every other token
as optional (a5d1c31 dropped two for space — see the texture-cache section).

**Frame-id discipline:** Faceclaw advances the mode-3 `fid` by 1 per delta within **`[1,
0xFFFE]`**, avoiding the CFW's `0xFFFF` "empty" ring sentinel. Damage does the same.

### 🔴 The carrier layout still needs a dummy TEXT container

g2flash's README says "create a layout with a single 576×288 image container." Taken literally
that is wrong. `BleProtocol.buildCreateMixedImagePage` emits:

```
ContainerTotalNum = 1 + tiles.length
field 3 = TextObject  "dashboard", 0,0, 576x288, content " ", isEventCapture = TRUE
field 4 = ImageObject(s)          e.g. "img00", id 10, 0,0, 576x288
field 5 = widgetId 10000
```

A **full-screen invisible text container holding a single space** — G2CC's rule *"every page
needs a text region; image-only layouts ack but never paint"* survives under the CFW, and the
dummy widget is the page's one `isEventCapture` antenna. It is also the widget the lease exists
for: `settings_ext.c` says the lease is needed "while its EvenHub layout contains
**swipe-capturing stock widgets**" — its repaints are what the FB lease suppresses. Neither
requirement can be dropped independently.

The image container is `576×288` **on purpose**: that carrier gives the firmware two
165,888-byte allocations, and the CFW reuses buffer A for its 640×480 packed-4bpp shadow (§4).
Faceclaw also sends a periodic `TextContainerUpgrade` (`ContentOffset=0, ContentLength=1,
Content=" "`) against that container.

### canvas480 — the openCFW/SybilSight alternative

A **576×480 packed-4bpp virtual canvas (138,240 B) held on-device**, stock scanout deliberately
left at 576×288 (touching the display config would read past the proven buffer). The phone
**pans** the 288-row viewport through `viewportY = 0…192`.

```
keyframe  [10][0][viewportY LE16][576 LE16][480 LE16][zlib(rle(packed4bpp))]
pan       [10][1][viewportY LE16]          <- "A pan retransmits no pixels."
release   [10][2]
```

Top-down, high nibble first, **288-byte row stride**. The keyframe is decoded into a fresh
allocation and swapped only after full zlib/RLE validation; a malformed frame preserves the
previous canvas. **Trade-off vs mode 8/9:** pans are ~5 bytes but bounded to 192 rows before a
full re-seed (~850 ms hitch); mode 8/9 shift-and-fill is **unbounded**.

### ⚠ The mode-10 "collision" is not real, and canvas480 is not an option for us

*Corrected 2026-08-16.* **No shipped or offered CFW contains canvas480.** The only CFW the
SybilSight webflasher installs is **2.2.6.11** = g2flash commit `877c8d9`'s output
**byte-identical** plus **three version-string patches** (30 bytes of ASCII: package identity
`s200_v2.2.6.10`→`.11`, the settings-reported version, the product-test `0x24` version). Its
`cfw_patches-2.2.6.11.json` pins `g2flash_output_sha256` equal to g2flash main's own output hash;
its capability marker ends **`compass10`** — g2flash's semantics for mode 10.

canvas480 exists only in openCFW's **unreleased** `g2/releases/g2-2.2.6.12/`, forked from the
**older** g2flash commit `d5eb48dd` ("the last pinned revision before Faceclaw's wake-lease
patches"), advertising `EVENCFW/4 img576 imgz rle xordelta stereo canvas480` — no `img640`,
`directfb`, `fbguard` or `wearnotify`. It predates the direct-framebuffer work.

⇒ **canvas480 is a fork off an older base, not a superset.** Choosing it would give up the
640×480 shadow and `display_copy_hook` — Damage's architecture. **Decision: the 2.2.6.11 /
g2flash-main line; canvas480 is closed**, and the "pick a branch or merge them" item is retired.

---

## 5. Measured and modeled performance numbers

**Pricing formula used throughout:** `ms ≈ bytes / 11000 × 1000 + 176` — our measured stock
throughput + ack latency, both from stock 2.2.2 captures *(corrected 2026-08-17 — was `/16571`;
see §5.1)*.

🆕 **Superseded for the CFW direct-framebuffer path (measured 2026-08-31, §5.2):**
`ms ≈ 60 + bytes / 50` — a ~60 ms floor and ~50–75 KB/s of transfer from 1,488 journalled
flushes on the real pair, PC-direct. **Every stock-formula table below is conservative by roughly
3–5× on the CFW path.** The old formula stays because it still describes the stock-app path.

### What G2CC's design costs today (the baseline we're beating)

G2CC tiles are 240×111 (content pane 480×222, 2×2). Packed 4bpp = **13,438 B/tile** = 4 chunks
at `MAX_IMAGE_CHUNK=4096`, each ack-gated: per tile ≈ **1,515 ms** · **4-tile screen ≈ 6.1 s**
(0.16 fps).

### What the CFW does to the same screen

A representative full-screen 576×288 G2CC-style scene composed with real DejaVu TrueType, by
`research/fbfeas.py` (checked in alongside `research/lz4bench.py` and `research/rlinks.py`; an
earlier note calling it lost was wrong, corrected 2026-08-16). **Re-measured 2026-08-17** with
the firmware's **real** RLE token format (see below) and throughput repriced at the measured
11 KB/s (§5.1).

| | bytes (old model) | **bytes (real RLE)** | time |
|---|---|---|---|
| raw 4bpp full screen | 82,944 | 82,944 | — (exceeds stock caps) |
| **CFW full-screen keyframe**, `zlib(rle(4bpp))` | 6,704 (0.081×) | **5,702 (0.069×)** | **694 ms** |
| **CFW dirty rect — menu cursor move** | 334 | **294** | **203 ms** |
| CFW dirty rect — one text line repaint | 2,726 | **2,038** | **361 ms** |
| stock LZ4 full screen (no CFW), for comparison | 9,253 | 9,253 | 1,017 ms |

203 ms is ~176 ms ack + transfer ⇒ **at the protocol floor, not bandwidth-bound.** A cursor move
costs ~294 B against a 4-tile screen's 13,438 B/tile.

### ✅ Antialiased text is cheaper than we modelled — and the RLE fix is why

The old `fbfeas.py` used a **byte-level** RLE (always 2 bytes per token). The firmware's encoder
(`zlib_glue.c`) is **nibble-level** with a **1-byte short token** `[cnt4|color4]` for runs of
1–15, escaping to 2- and 4-byte forms. Fixed 2026-08-17, **verified by round-tripping 301 cases
through a faithful port of the firmware's own decoder**, including the 16-bit escape path.

| | old model | real RLE | gain |
|---|---|---|---|
| full screen | 6,704 B | 5,702 B | 1.18× |
| cursor rect | 334 B | 294 B | 1.14× |
| **one text line** | 2,726 B | **2,038 B** | **1.34×** |

**The largest gain is on the text line — the antialiased case**: AA turns glyph edges into short
runs, and the firmware spends 1 byte where our model spent 2. Babcock's warning that zlib+RLE
"doesn't play nice with antialiased fonts" is real, but **we had over-estimated its cost.** These
numbers *are* AA-inclusive: `fbfeas.py` renders with PIL TrueType onto an `"L"` image and maps
0–255 → 0–15. Texture caching is still what makes AA text cheap at *scale* (§11 #2).

## 5.1 🔴 Throughput is ~10× under spec — analysed from the recovered captures (2026-08-17)

Babcock, by email, independently and without our numbers:

> "(There is something wrong with the BLE link config or something slow in the firmware that I
> haven't found yet; the spec sheets say 1Mbit but actual throughput is more like a tenth that.
> **Debugging that would make a much bigger difference than compression tuning.**)"

1 Mbit = 125 KB/s; a tenth is ~12.5 KB/s. Our measured band and g2-kit's ~8.8 KB/s both sit
there — three independent parties at the same wall, cause unknown to the firmware author. Unlike
ack latency (§11 #1, a constant that changes no decision), this is a suspected **defect** —
potentially fixable, and fixing it improves every number here at once.

### What the captures show (`captures/imagestatus.log`, handle 65 = R lens)

**Image-fragment spacing is trimodal**, not the single ~14 ms our docs assumed:

| gap | count | meaning |
|---|---|---|
| **0–1 ms** | 60 | back-to-back — multiple full-size packets in ONE connection event |
| **12–17 ms** | 85 | one connection interval apart |
| **56–61 ms** | 45 | ~4 intervals, radio idle |

End-to-end across multi-fragment bursts: **7–13 KB/s**; best instantaneous 240 B / 13 ms ≈
18.5 KB/s. The old 16.6 KB/s took only the fast mode (~30–50 % rosy).

### 🆕 A second measurement, from the flash itself (2026-08-30) — **the radio is not the wall**

Installing the CFW moved **4,339,457 B per lens in 171 s**, twice, **zero block resends** across
all twelve component transfers: **~25 KB/s effective goodput** including ack round trips and
FILE_CHECK/END overhead; the main image alone 3,562,570 B / 870 blocks / 134 s (**~26 KB/s**).
Both lenses agreed to within a second per component.

⚠ **Not "throughput is 25 KB/s"** — a different path three ways: the OTA characteristic
(`c0`/`c1`, 4096 B blocks) not the EvenHub image path; PC-direct BlueZ not the official app on a
phone; an otherwise idle host. It does not retire the 7–13 KB/s figure. What it establishes is a
lower bound the radio sustains — **~2× the EvenHub band, same hardware, same room** — so at least
half the missing 10× is above the physical layer, in the message path. It says nothing about
ack latency (§5.2).

### 🆕 5.2 The direct-framebuffer path measured on hardware (2026-08-31)

**Adam's pair, CFW `a5d1c31`, PC-direct over BlueZ**, from the shell's journal: every successful
flush records wire bytes and submit→ack time (fragmentation included). n = 1,488 across the
first-light and first-refinement sessions.

| flush size | n | median submit→ack |
|---|---:|---:|
| < 400 B (chrome cells, clock ticks) | 899 | **60 ms** (p10 41, min 33) |
| 400 B – 2 KB (scroll steps, boxes) | 410 | **83 ms** |
| 2 – 6 KB (content repaints, Main keyframe ~4.2 KB) | 132 | **138 ms** |
| 6 – 15 KB (dense window paints) | 46 | **201 ms** |
| 24.6 KB (the heaviest stereo paint seen) | 1 | 540 ms |

⇒ **`ms ≈ 60 + bytes/50`** (~60 ms floor + ~50–75 KB/s). Against the stock model
(`176 + bytes/11`) the floor is ~3× lower, the transfer term ~4–7× faster. A dense 640×480
keyframe (~10 KB) lands in ~200–270 ms ⇒ **~4 fps full-frame**; the heaviest frame (24.6 KB) ⇒
~2 fps. That answers `REFINEMENT.md` §6: 2–4 fps for dense frames, small-damage batches ride the
60 ms floor.

🔴 **CORRECTION (2026-09-05, `HANDOFF.md` §31.6): this table is FOUR HOURS of one session.** The
journal now holds 11,210 flushes; a step change falls between 03:00 and 13:00 on 08-31: the floor
holds (55–78 ms throughout) while the transfer term collapses ~6× — 6–12 KB goes from a 196 ms
median (n=25) to **1,193 ms** (n=277), ~50 KB/s down to ~7 KB/s, the §5.1 stock-path figure.
10,063 of 11,210 flushes are after the step; Adam's on-glass report of 1–1.5 s for a ~10 KB
scroll agrees. **Price work with the measured table in `HANDOFF.md` §31.1, not the `/50` slope.**

⚠ **Scope:** one host (beardos, BlueZ, idle), PC-direct — since §19 the standby path. 🆕
**2026-09-05 (`HANDOFF.md` §32): the slow regime IS the phone path** — the service log for those
hours reads `driving via remote:aphone`, the journal's stall notes name `aphone` (graded C; the
journal carries `via` now). **Measured on the phone itself the same afternoon (`HANDOFF.md` §33,
grade M):** < 500 B **72 ms**, 3–6 KB **667 ms**, 6 KB+ **1,036 ms** median, isolated flushes,
HIGH priority GRANTED (15 ms / latency 1 / 1M) — the interval is not the wall.

🔑 **Why the phone path is ~8 KB/s (2026-09-05, `HANDOFF.md` §37; grade I, consistent within
noise):** Android's GATT allows one outstanding write, so the APK sends **one 242 B AA packet per
usable connection event**; at 15 ms / **slave latency 1** the glasses listen every 30 ms;
242 B / 30 ms ≈ 8 KB/s — the measured transfer term. BlueZ queues freely (~6 packets per event),
which is the whole ~6× gap between the two radio paths. Levers, in order: slave latency 0 (a
firmware-side preference — Babcock's), then bytes (the texture cache). §5.1's "the host is not
feeding the radio" was right about the phone; the PC path never had the problem. None of this
retires the 7–13 KB/s row (stock EvenHub path on stock firmware).

### Four hypotheses tested and REFUTED

- ❌ **One write per connection event** — 60 gaps at 0–1 ms prove multiple packets per event.
- ❌ **Controller buffer-credit exhaustion** — `HCI Read Buffer Size` reports **ACL max 1021 B,
  12 packets**; peak outstanding on handle 65 was **4**.
- ❌ **Radio contention between the three links** — across 90 stalls, handles 64 (L lens) and 66
  (ring) produced **13 events total**.
- ❌ **Ack-gating** — real, and it *is* the 45–75 ms band for small control messages (§9). But
  inside runs of 240-byte image fragments the slow gaps contain **nothing**: 78 NOCP bookkeeping
  events, one gap with nothing at all.

### What it actually is

**Half of the ≥40 ms stalls resumed with ZERO packets outstanding** — everything acked, buffers
free, radio idle, and the host still waited ~60 ms. ⇒ **The bottleneck sits above the controller
and below the app's ack logic: the host is not feeding the radio.**

### 🔑 The lead worth sending upstream

🔴 **CORRECTED 2026-09-05** (`research/linkparams.py`, `captures/README.md`, `HANDOFF.md` §32).
This paragraph used to say the official app never issues an `LE Connection Update` for the
display link and handle 65 "runs on whatever it landed on". **The captures say otherwise:** both
hold handle 65's connection setup (peer = the RIGHT lens, address checked); it connects at
**30 ms** (`imagestatus.log`, unchanged for the window) or 48.75 → 30 ms (`allbutimages.log`),
where the **glasses** ask three times (L2CAP 0x12: 15–30 ms, then 90–105 ms/latency 4, then
15–30 ms) and the phone's stack **grants each with an `LE_Connection_Update` for 65**. Handles 64
and 66: 30–50 ms and 90–105/4. The narrower truth: the *app* never asks on its own for 65; the
peripheral drives it between an active 30 ms and an idle 90 ms/lat 4. At 30 ms and ~1.6 packets
per event, 7–13 KB/s is what you get — so the phone-side question is whether
`CONNECTION_PRIORITY_HIGH` (11.25–15 ms) is granted; the APK now logs the answer (§32).

Faceclaw *does* call `requestConnectionPriority(CONNECTION_PRIORITY_HIGH)` on every connection,
but `FaceclawBleCommunicator.java:1309` says:

> `// requestConnectionPriority has no callback in this Android compile target, so there is`
> *(…no way to confirm it was granted.)*

**He cannot verify it took effect; a BTSnoop capture answers exactly that** — the
`LE Connection Update` and resulting interval are in the HCI log.

### ⚠ Honest limits

- ~~**Handle 65's actual connection interval is NOT in this corpus.**~~ **It is — 30 ms active,
  90 ms/lat 4 idle (correction above, 2026-09-05).** The recapture that still matters is one with
  the APK driving, to see whether the priority request moves it.
- HCI alone cannot separate the ~60 ms host stall into Android-stack scheduling, app write
  cadence, or **BT/Wi-Fi coexistence** on the phone's combo radio — coexistence is invisible at
  this layer.
- Removing the stalls alone gets ~1.5–2×; the 12–17 ms per-fragment floor is the larger gap to
  125 KB/s.

### LZ4 ratios on our real content

Measured on 14 real FF1 NES frames from `/home/user/G2CC/games/ff1/bridge/spike_out/`, scaled to
240×111 packed 4bpp:

- **13 of 14 compressed to a single chunk**; ratios **0.13–0.24×** (one boot screen at 0.39×)
- flat black 0.01× · flat-shaded UI 0.01–0.16× · photographic screenshot **0.64×** · random
  noise **1.00× (expands)**
- ⚠ **Floyd-Steinberg dithering roughly halves the benefit** (0.24× → 0.34×). Do not dither.
  G2's own guidance agrees.

### Scroll cost — and the design rule it forces

576 wide at 4bpp = **288 B/row**. RLE+deflate on text ≈ 0.08–0.15×:

| scroll step | new pixels | time |
|---|---|---|
| 1 row | ~25–45 B | ~178 ms |
| 20 rows | ~460–860 B | ~210 ms |
| 40 rows | ~920–1,700 B | ~250 ms |

**Ack-dominated, not bandwidth-dominated** — a 40-row jump costs barely more than a 1-row nudge.
⇒ **Scroll in COARSE steps**: one ring notch = one text line or several, never pixels. *(Stock
times; on the CFW curve each step is ~65–95 ms, same shape. Shipped: Reader defaults to 5 lines
per notch, configurable, acceleration ramp present but off — `REFINEMENT.md` §3b; lists stay one
item per notch.)*

### Endless scroll — solved primitive

`mode 8 { mode 9 shift + mode 3 fill }`: shift existing pixels on-device, transmit only the newly
exposed strip, atomically. Content length is irrelevant — a viewport over server-side content,
unbounded, no re-seed.

### The community's numbers, for calibration

| source | claim | verdict |
|---|---|---|
| `nickustinov/even-g2-notes` `docs/performance.md` (fw 2.2.7.14, SDK 0.0.13) | `ms ≈ 104 + 0.0039 × gray4Bytes`; **200×100 = 7.0 fps**, 288×144 = 5.4, 20×20 = 9.5 ceiling; multi-container is serial+additive (1=9.0, 2=4.5, 4=2.3 fps) | best available post-LZ4 data; explicitly **call rates, not verified on-glass paint rates** |
| G2oom (u/Obliviux, real hardware, pre-LZ4) | **~0.5 s/frame at 200×100 = 2 fps.** "No amount of clever coding changes this." | true at the time; pre-compression |
| `even-g2-matrix` (real hardware) | **1–2 fps** effective at 200×200, with an explicit warning its demo GIF is the **simulator** | corroborates |
| `flappy-g2` README "~10 FPS at 200×100" | **a hardcoded `FRAME_MS = 100` constant**, `setTimeout` *after* `await sendFrame()`, silent error swallowing, SDK v0.0.7, simulator-only run instructions | **not a measurement** |

**⚠ Lesson: every high-fps claim traced back to the simulator. Always ask "sim or glass?"**

### Two speedups available on STOCK firmware (no CFW)

1. **Sliding-window pipelining.** `g2-kit`'s `ImageStreamer` keeps **4 fragments in flight**;
   `g2flash`'s `video-bench.ts` exposes `G2_WINDOW` (default 2, 1=serial). G2CC's `G2Renderer` is
   strictly serial. Their tolerance rule: slide through up to ~3 consecutive missed acks, reset
   beyond. *(Their throughput table — 1 tile ~20 fps, 8 tiles ~5 fps, "~8.8 KB/s ceiling" — is
   **internally inconsistent**: 8 × 288×144 at 4bpp and 5 fps is ~830 KB/s. Technique proven,
   numbers unverified.)* **Third data point (2026-08-17):** Faceclaw ships `WINDOW_SIZE = 3` *on
   CFW*, noting it "requires firmware that accepts pipelined image messages (the CFW
   snapshot/deferred FIFO)" — proven on our target path; 3 is the known-good depth. ⚠ Its
   ack-miss tolerance is `MAX_CONSECUTIVE_ACK_TIMEOUTS = 8` versus g2-kit's ~3 — unexplained; do
   not treat either as authoritative.
2. **Stock LZ4** (`CompressMode=2`) — see §8.

---

## 6. Input

Source: `g2flash` `patches/gesture_fwd.c`. Input dispatcher `FUN_004424a2` turns each gesture
into a UI event code posted via `FUN_0045fc80(ctx, code, data)`.

- **Source byte at `0x2034dc30`: 0/1 = left/right temple touchpad, 4 = R1 ring.** ⚠ For events
  9/10 it never reaches the host: a5d1c31 sends them with `EventSource` ABSENT (proto3
  zero-omission; verified at instruction level, `CLAIMS.md`), so **a long-press is unattributed
  on the wire** and per-source long-press grammar cannot be built. Tap/scroll carry a real source.
- **long-press = subtype 3.** In EvenHub it calls `FUN_0046a644`, the **"End this feature?"
  dialog**. g2flash **replaces that call** ⇒ SysEvent **9**. ⚠ The `source == ring` gate was the
  PRE-a5d1c31 build: **the installed firmware raises event 9 from either temple as well as the
  ring** (the §1.2 bare-long-press no-op keeps the extra accidental sources harmless).
- **ring release-long-press = subtype 0xe** → `FUN_0045fc80(ctx, 0x4a, coords)`, which the
  EvenHub UI handler **drops** (no 0x4a case). g2flash intercepts ⇒ SysEvent **10**
  `RING_LONG_PRESS_RELEASE_EVENT`. True press-and-hold / hold-to-confirm. **Proven.**
- Scoped to **EvenHub foreground** (`app == 0xe0`); outside it, stock behavior is byte-for-byte
  unchanged.
- **Both-temple long-press → stock Silent Mode: NOT patched by anyone.** A system-level gesture
  outside the EvenHub branch. Adam says it never fires accidentally — **kept as the hardware
  escape hatch**.
- ⚠ **Removing the dialog removes the only stock way to quit an app** (Faceclaw provides its own
  quit path plus a 10 s keepalive; Damage's design has no quit path — `DESIGN.md` §1.6). Since
  a5d1c31 the dialog is suppressed only while the FB lease is held — lose the lease and it
  returns, a safety net.

### 🔑 6.1 Per-notch scroll — G2CC's boundary-only limitation does not carry over

**G2CC could only see scroll at the ends.** `WINDOW_API.md`: `onContentScroll?(dir)` is a
*"scroll-notch **boundary** event"*, fires only in `fullBleed` + `scrollContent` text mode, and
**"never fires outside fullBleed."** Elsewhere the firmware list widget owned selection and you
got `List_ItemEvent{CurrentSelectItemIndex}` on *selection*, never per notch.

**The cause was the widget, not the gesture.** A firmware text container with content taller
than its viewport scrolls internally and notifies the host only at the ends. Under the CFW carrier
layout the event-capture container holds a **single space** (§4.1), which can never scroll
internally ⇒ **every notch is immediately at the boundary and is delivered.**

⚠ **The CFW does not patch scroll at all.** `gesture_fwd.c` touches only long-press (subtype 3)
and ring release-long-press (subtype 0xe); scroll handling is byte-for-byte stock. The win is a
consequence of the framebuffer layout, not the firmware — arguably available to G2CC's original
framebuffer design too.

**Evidence — now M: per-notch delivery observed in daily use since 2026-08-30 (`CLAIMS.md`).**
Original corroboration: Faceclaw's `DashboardInputEvent` carries `{type:"scroll-up"}` /
`{type:"scroll-down"}` and its apps consume them per notch — `file-browser.ts:274` and
`launcher-app.ts:206` both do `const delta = event.type === "scroll-down" ? 1 : -1`; **pinball**
(*"click flips both flippers, scroll-up/down flips left/right individually"*) is unplayable on
boundary-only events.

### 🆕 A second, independent input path: the ring's own BLE link

Faceclaw also connects to the R1 ring over **its own link** and decodes gestures host-side
(`FaceclawRingEventDecoder.java`). Two frame shapes:

```
11-byte "charger" gesture:  00 09 61 00 <code> <param:16LE> <tick:32LE>
    0x00 LONG_PRESS · 0x01 TAP · 0x02 DOUBLE_TAP
    0x04 SWIPE_UP   · 0x05 SWIPE_DOWN · 0x08 LONG_PRESS_RELEASE

3-byte "direct" gesture:    ff <type> <param>
    03/20 HOLD · 04/01 TAP · 04/02 DOUBLE_TAP
    05 param<=1 SWIPE_FORWARD, else SWIPE_BACKWARD
```

Raw swipes with no container and nothing to reach a boundary of — plus a 32-bit tick, so
velocity is available.

❌ **This corrects our own doc.** `G2CC/docs/G2_BLE_PROTOCOL.md` §11 says of the ring link:
*"Navigation input does NOT come over this link (it goes ring→glasses→`e0-01`). This link is
battery/firmware/sensors only."* Faceclaw demonstrably decodes gestures from it; the claim is
wrong or incomplete. (G2CC is read-only — the correction lives here and in `CLAIMS.md`.)

**Still open (`REMINDER.md` item 2):** whether the ring coalesces very fast spins, and its
event-rate ceiling. Damage holds no ring link of its own (the 2026-08-31 ring-battery probe was
reverted — `HANDOFF.md` §19.3); §5.1 shows the ring nearly silent during transfers anyway.

### 🔴 The single most concrete win — Adam's #1 daily annoyance, already fixed

At work Adam wears gloves. The failure chain on stock G2CC (historical since 2026-08-30):

1. double-tap → G2CC mini-silent-mode (tiny clock, ignores all input but another double-tap)
2. gloves force a **ring long_press** → **"End Feature?" menu** overrides mini-silent-mode
3. then either a glove **tap** → End Feature → **ends G2CC** until the phone refreshes, or
   another glove **long_press** → **Firmware Menu**, first item **Silent Mode**, selected →
   **firmware Silent Mode, no clock, no notifications**

**The CFW patch removes the call that opens that dialog.** Ring long-press becomes SysEvent 9,
which our app ignores in silent mode; the chain stops at step 2. A specific shipped patch, not a
model. *(The pre-a5d1c31 build gated it on `source == ring`; the installed a5d1c31 raises event 9
from either temple too, and the shell's bare-long-press no-op keeps every source harmless —
confirmed in daily use since 2026-08-31, `DESIGN.md` §0/§1.2.)*

---

## 7. Stereo / depth — what it actually is

The "lenses differ" flag carries **the same pixels, one payload, at a different position per
lens** — **binocular disparity control**, *not* independent per-eye content.

- **Per-object depth: yes** — each mode-3 op has its own L/R box pair; batch several in a mode 8.
- **Per-pixel depth: no** — granularity is the rect; each element is a flat card.
- **True stereoscopic 3D: not via this flag.** Technically possible via `G2_IMAGE_ARM` (image
  data to a chosen arm; arms are separate BLE links) at ~2× payload and 2× writes — but the ack
  returns on R either way, so the pipelines are not independent.
- **Why it won't look volumetric:** fixed accommodation (one focal distance — vergence cue, no
  focus cue; small offsets read as depth, large ones as strain), no motion parallax, monochrome.

**Best uses:** IPD/convergence calibration (misaligned images cause eye strain) and subtle depth
separation for overlays/modals. **Rules:** never different content per eye (binocular rivalry);
**horizontal offsets only, never vertical**; consistent disparity per object; small magnitudes.

⚠ **Stock already owns a global depth setting** ("near/mid/far"; 2.2.8 notes mention *"Fixed
display distance settings not applying correctly on G2"*; openCFW's canvas480 *"deliberately
do[es] not reuse the stock 0…12 optical screen-height setting"*). **Adam sets it to FAR** so the
display is easy to ignore at work and while driving.

🔴 **Corrected 2026-08-17 by Adam directly.** An earlier version inferred from the FAR setting
that we should "push background elements farther, never foreground nearer." **That was our
inference, not his preference, and it is backwards.** His stated preference:

> "the main window as far back as depth comfortably allows, and notifications and the like to pop
> over it in front of it."

⇒ **Main content parked at the far end of the comfortable range; popups, notifications and
modals come FORWARD toward the screen plane.** FAR stays his baseline for ignorability; the
*relative* separation does the perceptual work. Ladder and calibration plan: `DESIGN.md` §3.

Built and in daily use: the `DESIGN.md` §3 ladder, chrome on the back plane (`REFINEMENT.md` §1),
per-app content depth (`HANDOFF.md` §17); Adam on glass: *"Depth reads well."* Still
uncalibrated: the comfortable-disparity ramp (`REMINDER.md` item 3). Depth was never a reason to
choose this architecture.

---

## 8. The stock LZ4 path (works without CFW — useful for measurement)

### ⚠ What `CompressMode` values actually mean — evidence graded (corrected 2026-08-17)

Earlier versions of this document stated **"1 = RLE, 2 = LZ4"** as settled fact. It is not.
`ImageRawDataUpdate.CompressMode = 5` is a **bare `uint32`** in Even's own FileDescriptorProto —
**no `ImageCompressFormat` enum exists in any of the 27 vendor schemas** (grepped, 2026-08-17).
The schema pins the *field*, not the *values*.

| value | evidence | grade |
|---|---|---|
| **0** = uncompressed | three independent working implementations send 0 and render correctly: faceclaw (`BleProtocol.java:175` "CompressMode stays 0"), g2-kit (`compressMode ?? 0`), g2flash's own CFW path | ✅ **confirmed** |
| **2** = LZ4 raw block | (a) g2flash wrote a purpose-built raw-LZ4-*block* encoder for it and benchmarks it against stock 2.2.6.10 — `COMPRESS_MODE = LZ4 ? 2 : 0`, working exercised code, ~10 fps; (b) **independently**, `even-g2-notes/docs/page-lifecycle.md` documents Even's **own SDK 0.0.12** stamping `compressMode: 2` on every image while passing bytes through uncompressed, producing garbled small images and `sendFailed` on large ones on fw 2.2.6.10 | ✅ **strong, two independent sources** |
| **1** = "RLE" | **two prose comments by one author** — `patch_compress.py:31` and `demos/video-bench.ts:23`. No firmware address, no decompiled dispatch, no capture. Both cite `notes/fw-2.2.6.10-lz4-images.md`, **which is not in the repo**. And **nothing anywhere ever sends 1** — not g2flash, faceclaw, g2-kit, or Even's SDK | ❌ **single-source, uncorroborated, never exercised — TREAT AS UNKNOWN** |

**G2CC's June 2026 probe is not evidence either way.** It guessed `f5 ∈ {1,2,3}` and always sent
**RLE4** bytes — on **2.2.2**, which predates the feature and has no decoder. Inconclusive by
construction.

**On 2.2.2 the field most likely had no stock meaning.** g2flash's *older* CFW repurposed it:
`patches/decompress.c`'s `frag_write` treated **any nonzero CompressMode as "the payload is 1bpp,
expand it 1→4"** — only possible if stock ignored the field. `patches_main.c` says the expander
"was dropped in the 2.2.6.10 rebase — stock now uses CompressMode itself" — establishing *that*
the field became meaningful in 2.2.6.10, not *which value maps to what*.

### ✅ Answered by the author, 2026-08-17 — and the practical answer is "always send 0"

Babcock, by email:

> "CompressMode is **unused (no effect) in older firmwares**. In newer firmwares it's Even's
> first-party compression mode. The g2flash/faceclaw firmware supports this for compatibility with
> the stock Android app, but **signals its own compression method with CompressMode=0 and some
> header bytes in the data field**."

The grading table survives intact: "unused in older firmwares" confirms no stock meaning on 2.2.2
(why the old `frag_write` could repurpose it); "Even's first-party compression mode" confirms it
became meaningful at 2.2.6.10. ⚠ **He did not confirm `1 = RLE`** — that row stays ungraded, and
is now **moot in practice**.

🔑 **The operative rule: the CFW path always sends `CompressMode = 0`.** The "header bytes in the
data field" are the mode byte itself (3/6/8/9 — §4); Faceclaw's `BleProtocol.java:175`:
*"CompressMode stays 0: the CFW's zlib path is detected from the buffer's..."* ⇒ Damage never sets
a nonzero CompressMode, and the `1`-vs-`2` question never has to be answered.
- `CompressMode=2` feeds the payload to `LZ4_decompress_safe(src, dst, srcLen, dstCapacity)` at
  **`0x0054f338`**.
- ⚠ It expects a raw **LZ4 BLOCK, not an LZ4 frame.** Most npm/py LZ4 libraries emit frames
  (magic `0x184D2204`), which the decoder **rejects**. `g2flash` `demos/lz4.ts` is a
  dependency-free block compressor for exactly this. Decoder rules: last 5 bytes always literals
  (LASTLITERALS), no match may start in the last 12 bytes (MFLIMIT).
- Firmware decompresses into `malloc(W*H)` sized from the container ⇒ **payload must inflate to
  ≤ W×H bytes.**
- **An unknown CompressMode is silently "treated as raw"** — garbage on the lens, not an error.
  *This explains G2CC's June "garbled underline" on 2.2.2: wrong codec, firmware with no decoder.*
- Failure logs `evenhub_ui: decompress failed, mode=%u raw_len=%u`.

---

## 9. Ecosystem — who built what, and how much to trust it

### The projects (all links)

| project | what it is |
|---|---|
| **[jimrandomh/g2flash](https://github.com/jimrandomh/g2flash)** | **THE custom firmware.** GPL-3.0. Author **James Babcock**. Patches stock 2.2.6.10. `patches/zlib_glue.c` = the mode table; `patches/gesture_fwd.c` = input; `patches/patch_compress.py` = the patcher; `demos/lz4.ts` = LZ4 block encoder; `demos/video-bench.ts` = the benchmark; `demos/detect-cfw.ts` = capability probe; `g2flash.py` = the flasher |
| **[jimrandomh/faceclaw](https://github.com/jimrandomh/faceclaw)** | the reference UI on that CFW. **Ships `app/fonts/terminus/*.bdf`** and renders its own framebuffer UI — proof the architecture works |
| **[Commute773/g2-kit-unofficial](https://github.com/Commute773/g2-kit-unofficial)** | ★31. **An independent from-scratch RE of the BLE stack.** `ble/gen/*_pb.ts` = **generated protobuf schemas** for ~20 message families, each embedding the **vendor's own `FileDescriptorProto`** — the most valuable artifact in the ecosystem (§9.1). ⚠ **`ble/docs/`'s 11 prose docs are materially unreliable — read the `.ts`, not the `.md`.** *(jimrandomh's copy is a byte-identical fork pinning a dependency SHA.)* |
| **[kalanihelekunihi/evenRealities-openCFW](https://github.com/kalanihelekunihi/evenRealities-openCFW)** | Author **Kalani Helekunihi** (AM Guru). (a) a byte-exact **reconstruction** of stock 2.2.6.10 — ~5% source-owned, an analysis project; (b) an **unreleased** `g2-2.2.6.12` build = older g2flash `d5eb48dd` + **canvas480** (§4 — not the CFW anyone installs); (c) **the independent review of g2flash** — the valuable part: `tools/thumb_branch_audit.py`, the evenai_thumb HardFault writeup, a regression test |
| **[AM-Guru/SybilSight-webflasher](https://github.com/AM-Guru/SybilSight-webflasher)** | MIT. Deployed at **webflasher.sybilsight.com**. Browser flasher over Web Bluetooth **and the charging case's CH340 USB serial** (`1A86:7523`). Backup set (512 KiB case flash + option block + temple identity snapshots + matching official glasses bundle). **Hosts the 19-image firmware archive.** Case-USB pogo bridge pushes to a *responsive* temple — **not an unresponsive-device rescue** |
| **[i-soxi/even-g2-protocol](https://github.com/i-soxi/even-g2-protocol)** | the original community BLE RE reference (G2CC's original upstream) |
| **[nickustinov/even-g2-notes](https://github.com/nickustinov/even-g2-notes)** | **`docs/performance.md`** (the fps/cost model) and **`docs/display.md`** (container rules, glyph inventory, fullwidth-CJK monospace trick) |
| **[pangoleen/awesome-even-realities-g2](https://github.com/pangoleen/awesome-even-realities-g2)** | the curated index of ~150 G2 projects — start here for prior art on any app idea |
| [opinsky/even-img-benchmark](https://github.com/opinsky/even-img-benchmark) | image-pipeline benchmark harness |
| [200even/flappy-g2](https://github.com/200even/flappy-g2) | source of the debunked "10 fps @ 200×100" claim |
| [wmoto-ai/even-g2-matrix](https://github.com/wmoto-ai/even-g2-matrix) | carries the explicit "the demo GIF is the simulator, real hardware is 1–2 fps" warning |
| [G2oom Reddit writeup](https://www.reddit.com/r/EvenRealities/comments/1sdcvkj/i_ported_doom_on_the_even_g2_sort_of_heres_what_i/) | the best real-hardware developer account. Sobel + Bayer dither pipeline, and the 0.5 s/frame verdict |
| [Even Hub docs](https://hub.evenrealities.com/docs) | official SDK documentation |

### Gotchas from g2-kit worth borrowing (`ble/docs/gotchas.md`)

- **The first Cmd=3 burst after CREATE is silently dropped** — fragments ack, render fires, lens
  stays blank. Push a sacrificial warmup frame.
- Fragment `seq` is a **group key, not a counter** — incrementing per fragment makes the firmware
  drop everything.
- `magic` is **effectively uint8** (firmware compares only the low byte) — our msgId-255 wall.
- **`sid=0x80` (dev_config) disabled a pair** — non-terminally; needed power-cycle + re-pair.
  **Stay on `sid=0x09`.**
- Multi-fragment messages must not interleave — one reassembly buffer keyed by transport `seq`.
- Audio is on service `6450`, not `7450`.
- **Stuck-session trap:** after an aborted image stream, re-creating the container with an
  *adjacent* `MapSessionId` inherits the aborted session's buffers. Bump the session counter by
  **≥2** on reset. Symptom: a fresh CREATE succeeds but the first Cmd=3 burst never acks or renders.
- **1×1 transparent image = soft sleep.** Lens dark, plugin task and event pipeline alive, taps
  still fire. Cheaper than a Cmd=9 teardown for an idle mode.
- Container **name cap is 14 chars**, hard and silent (our doc says ≤16 — untested).
- TextContainer `capture_events` **defaults false**; lists and images capture by default.

---

## 9.1 Cross-check of our BLE RE against g2-kit (done 2026-08-16)

### 🔑 The rule that came out of it — and the author's own ordering

> **g2-kit's prose docs (`ble/docs/*.md`) are materially wrong. Its code (`ble/*.ts`) is right.
> Read their `.ts`, never their `.md`.**

✅ **Independently confirmed by Babcock 2026-08-17**, unprompted:

> "g2-kit-unofficial is **older reverse engineering work** by nebulani/Commute773; **the best
> source of truth for how to format messages is faceclaw.**"

⇒ **Source-of-truth ordering for wire format, highest first:**
1. **Even's own protobuf schemas** (`g2-kit/ble/gen/*_pb.ts` — vendor `FileDescriptorProto`s)
2. **Faceclaw + g2flash source** — the exercised CFW implementation
3. **Our own BTSnoop captures** — authoritative for stock 2.2.2 and the official app
4. g2-kit's `ble/*.ts`
5. ❌ **g2-kit's `ble/docs/*.md` — do not use.** He did *not* dispute the `is_last` contradiction
   we raised; he routed around it (§8: always send `CompressMode = 0`).

Our capture-derived spec agrees with their **code** and disagrees with their **docs**, every time:

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
payload `080c104f7200` = `0xCC79`, on-wire LE `79 cc` — as `G2_BLE_PROTOCOL.md` §2 states.

⚠ Their `is_last` claim is a real hazard: field 5 is `CompressMode`, so writing `is_last=true`
emits **`CompressMode = 1`** over *uncompressed* bytes, and an unknown CompressMode is **silently
"treated as raw"**. Even's own SDK 0.0.12 regression is the proof on hardware: a wrong nonzero
`compressMode` over plain bytes → garbled small images, `sendFailed` on large ones.

### The artifact that settles it: Even's own schema

`ble/gen/EvenHub_pb.ts` embeds a base64 `FileDescriptorProto` — **the vendor's actual
`EvenHub.proto`** (package `g2.evenhub`, vendor CamelCase names). Decode with a plain varint
walker; the base64 ships **unpadded**, so pad it first.

**Every wrapper field number in `G2_BLE_PROTOCOL.md` is confirmed exactly:** `Cmd=1`,
`MagicRandom=2`, `CreateMessage=3`, `ImgRawMsg=5`, `RebuildContainer=7`, `TextUpgrade=9`,
`ShutDownCmd=11`, `DevEvent=13`, `HeartPacketCmd=14`; all three container property tables,
`TextContainerUpgrade{ContentOffset=3, ContentLength=4}`, and `OsEventTypeList` 0–8 match too.

**Four things the schema gave us that we did not have:**

1. **`ImgRawMsg.f3` is `MapSessionId`, not a nonce.** `G2_BLE_PROTOCOL.md` §6.5 calls it a
   "per-push nonce… set it to anything per push." It is a **session id with real semantics** —
   the stuck-session trap above. The one place our doc is genuinely wrong *(G2CC is not edited;
   the correction lives here)*.
2. **`ImgRawMsg.f5 = CompressMode`** — the field G2CC's June probe hunted. ⚠ The schema gives the
   **field only** (bare `uint32`, **no `ImageCompressFormat` enum in any of the 27 schemas**);
   values rest on evidence: `0` confirmed, `2` strong, **`1` unverified**. See §8.
3. **EvenHub has IMU** — `Cmd 19/20`, `IMU_CtrlCmd{IMUReportEn, reportFrq}`,
   `IMU_Report_Data{double x, y, z}`, `OsEventTypeList.IMU_DATA_REPORT = 8`. See §2.
4. **`Sys_ItemEvent.f2 = EventSource`**: `1 = GLASSES_R`, `2 = RING`, `3 = GLASSES_L`. Our
   capture's unlabeled `f13.f3={f1=3 f2=2}` decodes as **double-tap from the ring** — closing
   `G2_BLE_PROTOCOL.md` §14's "input source byte" item. ⚠ **Different numbering from the CFW's
   internal source byte** at `0x2034dc30` (0/1 = L/R temple, 4 = ring). Do not conflate.

Also resolved: **`e0-02` is the firmware's abort frame** on reassembly failure — g2-kit
triggered it by incrementing `seq` per fragment. `G2_BLE_PROTOCOL.md` §5 had it as "observed
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

The official app sends `sid 0x80, commandId 5 = PIPE_ROLE_CHANGE` with `asCmdRole = 1 = RIGHT`
at connect. `eGlassesLR` is `{BOTH=0, RIGHT=1, LEFT=2}`. So "R is the command lens" is **a runtime
setting the host chooses, not a hardware property**, and `BOTH` is legal. Consistent with Faceclaw
sending bulk pixels LEFT while RIGHT stays the command lens (§2). Damage never sends it — the
connect prelude is the sid-0x01 launch only (`HANDOFF.md` §8) — and the split runs daily without
it; still unprobed.

### What the image path actually does

- **`CompressMode` is absent on all 13 official-app image pushes.** ✅ Definitive.
- **`MapSessionId` is constant across one image's fragments and changes per push** — small ints
  (25–237, n=13, 7 unique), erratic deltas: **random per push, not a counter.** Faceclaw uses
  large random uint32s. Both work.
- **Longest container name the official app uses is 8 chars** (`imgsolid`). Our "≤16" claim is
  unsupported by our own data; g2-kit's "14 hard cap" remains single-source. Unresolved.
- **`imgmax` is exactly 288×144 and painted** ✅ — the SDK cap is real; G2CC's 288×129 was
  over-cautious.
- Largest **layout** frame observed: **401 B** — under the ~1000 B layout wall (§2).

### 🔴 What failure looks like on the wire (this is what NO SILENT FAILURES needs)

```
[+47.19s] e0-02  empty frame                    <- abort signal
[+47.75s] ImgResCmd.ErrorCode = APP_REQUEST_UPGRADE_IMAGE_RAW_DATA_FAILED
          container=mximg session=25 total=10118 fragIdx=1   <- 4125 B Cmd=3 request
[+48.38s] ImgResCmd.ErrorCode = APP_REQUEST_UPGRADE_IMAGE_RAW_DATA_FAILED
          container=mximg session=25 total=10118 fragIdx=1   <- retried, SAME session, failed again
```

1. **`e0-02` lands 0.56 s before the first failure** — with g2-kit's independent account of an
   abort frame on `sid=0xe0 flag=02`, now **corroborated**.
2. **Failure is reported in `ImgResCmd.ErrorCode` (field 8)** with container name, session id,
   total size and fragment index. Damage must read that field. ⚠ *`ImgResCmd` field 1 is
   `ContainerID`, not an error code; an earlier pass conflated them.*
3. **Retrying with the same `MapSessionId` failed identically** — the stuck-session trap on our
   own wire. **Bump the session id on retry.**

Also seen: `TEXT_DATA_FAILED` and `SHUTDOWN_FAILED` (the latter twice, on `exitMode` shutdowns —
possibly how the firmware reports "already gone"; noted, not explained).

### How much to trust all this — honest assessment

Adoption is **tiny** (GitHub API, 2026-08-16): g2flash ★22 / 3 forks / **0 issues ever** /
1 contributor, created 2026-06-23; faceclaw ★16 / 3 / 0; openCFW ★18 / 2 / 0; webflasher ★2 /
0 forks / 2 issues **both self-filed by the author**. **Zero mentions of "g2flash" or "faceclaw"
anywhere on Reddit.** The *protocol* layer has real adoption — even-g2-protocol ★178/31 forks,
even-g2-notes ★111/9, g2-kit ★31/5 — the *firmware* layer almost none: people consume the RE and
avoid the flashing.

### There is exactly one third-party CFW user, and they are active

`Danxtream/faceclaw` (+4 commits) and `Danxtream/g2flash` (+3), pushed 2026-08-16: **H.264
playback on the CFW** — 2,551–2,635-line `zlib_glue.c` variants against upstream's 1,512, a
`patches/h264/` tree, committed Android logcat from real glasses (MACs `C4:AF:F2:54:38:29` R /
`C4:60:45:13:B3:36` L, `fwside=1`/`2` matching `FW_SIDE()`). Modified CFW flashed by a third
party, and it boots. ⚠ Their **"13fps" is not a measurement** — `present=0 frames=0` throughout,
~1.55 s inter-ack gaps are host-side timer pacing. The logs *do* establish on-glass H.264
`decode=39–42 µs` (compute is not the bottleneck) and a working `sid=0x09` wear-detect ACK.
`Mojashi/g2flash` is +2 from July; the remaining forks are mirrors.

**It is not n=1.** Two independent authors cross-reference, and Helekunihi **audited Babcock's
patches at instruction level**:

- **Scope rejections** (`release.json` `removed_features`): `faceclaw-wake-lease`,
  `faceclaw-idle-double-tap-takeover`, `faceclaw-even-ai-interception`, plus the settings
  field-101 decoder; `"native_even_ai_behavior": "stock"`. Product-scope choices (SybilSight
  wanted stock "Hey Even" to keep working), **not safety judgments.**
- 🔴 **A real HardFault defect he found in an early CFW 2.2.6.11**
  (`components/apollo_main/evenai_thumb/`): the `even_ai_display_ctrl` trampoline resumed the
  stock body via `bx r12` to `0x004E1FD6` — **bit 0 clear ⇒ requests ARM state**; Apollo510
  (Cortex-M55) has none ⇒ UsageFault/INVSTATE; the stock vector table installs **no**
  MemManage/BusFault/UsageFault handler (all three vectors zero) ⇒ **HardFault**, which logs
  `/log/hardfault.txt` then loops (`b .`) until the **external watchdog resets the temple,
  dropping the BLE link.** Fix = one byte, `0xD6`→`0xD7`. **It survived the author's own review
  because the one path that avoids the `bx` (`op==START` with a valid lease) is the path his app
  exercises — it reproduces most reliably when the host app is NOT connected.** He also shipped
  `tools/thumb_branch_audit.py` (scans a Thumb-2 blob for `movw/movt → bx|blx` with bit 0 clear)
  plus a regression test.

  ### ✅ It is fixed, and we verified that ourselves

  The defect was in a build from g2flash `6d5c5859`, also called "2.2.6.11" — why the archive
  keys images by SHA prefix (`2.2.6.11-105032302d02`). **g2flash fixed it upstream 2026-08-07**
  (*"Fix a crash that occurred when using the custom firmware with stock Even AI"*);
  `patches/settings_ext.c:300` now reads `movw r12, #0x1fd7` with the comment *"0x004e1fd6 |
  Thumb bit; BX needs bit 0 set"*. **Verified 2026-08-16 with Helekunihi's own auditor against
  the exact image on disk** (extract the appended blob first — `g2-2.2.6.11.bin[4301227:]`,
  20,127 bytes):

  ```
  python3 reference/evenRealities-openCFW/g2/tools/thumb_branch_audit.py <blob> --base 0x00794324
  → 14 constant interworking branches, ALL Thumb.  0x00796e8a = 0x004e1fd7.  ZERO defects.
  ```

  (14 branches vs the 9 in his writeup: HEAD's blob added compass, wear-notify and the buzzer
  sequencer since `6d5c5859`.) **Re-run this audit before any flash.**

⇒ **Corroboration is n=2 with instruction-level independent review, a defect found, a fix that
flowed upstream and that we confirmed.** We are the third serious consumer of this ecosystem
(live on it since 2026-08-30). Read patches before flashing them.

---

## 10. G2CC assets to mine (do not modify G2CC)

> ✅ **The BTSnoop corpus lives in `captures/`** (recovered 2026-08-17 from Adam's mailbox, where
> both sessions had been sent from the phone as Android **bugreport** zips, after being lost from
> `/tmp/g2cap-cap/`). All four segments verify `orig_len == incl_len` (unfiltered), record counts
> match §0 of `G2_BLE_PROTOCOL.md`, `SHA256SUMS` pins them. **The only ground truth for stock
> 2.2.2, which is not in the public archive.** See `captures/README.md` (its old "known gap" —
> handle 65's connect outside both windows — was corrected 2026-09-05, §5.1).

`/home/user/G2CC` is a working, shipped system. **Read from it; never edit it.** The pre-pivot
framebuffer pipeline is still in the tree:

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

`HAT_BRIDGE_SPEC.md` was design-locked 2026-06-08; **the entire BOM was bought but never built**
because the v0.7 software fix (foreground service + wake lock + faster recovery) made the
connection problems vanish (its §1 says so). It is the bridge appliance `DESIGN.md` §10 needs for
the no-phone configurations: **Seeed XIAO ESP32-C5** (dual-band WiFi 6 + BLE 5), 3 × 420 mAh, a
dual-band u.FL FPC antenna **outside the hat, right side, 1–2″ from the glasses' temple-tip
antenna**, WSS home to the PC's cloudflared tunnel.

Easier now than when specced: under the CFW the framing is simpler than the `f1=0/3/5/7` port it
planned (`CompressMode = 0` plus a mode byte), and if the host pre-deflates **the bridge needs
neither zlib nor the 153 KB shadow** — it forwards bytes and owns the lease.

🔑 **It is also a controlled experiment on §5.1.** The board was chosen because *"dual-band dodges
WiFi/BLE coexistence"* — one of the **three surviving candidates** for the ~10× shortfall, and the
one invisible at the HCI layer. WiFi on 5 GHz + BLE on 2.4 removes the contention; a throughput
jump on the hat would isolate a cause nobody has named, including the CFW author.

⚠ The number that decides wearability: **1260 mAh running WiFi 6 plus BLE for a full workday.**
The spec has a hybrid power policy and no measured budget.

*(Status 2026-09-02: no build since this note — the phone APK is the daily bridge (`HANDOFF.md`
§19); the coexistence experiment is still unrun; `DESIGN.md` §11 items 13–14 carry it.)*

---

## 11. Open unknowns — resolve these before/while building

1. ✅ **CFW ack latency — the whole CURVE measured 2026-08-31: `ms ≈ 60 + bytes/50` (§5.2,
   n=1,488 flushes).** First light's ~50 ms EMA gave the floor a day earlier; §5.2 supersedes it.
   *Re-graded 2026-08-17 from 🔴 "the single most load-bearing unmeasured value" — that was
   wrong.* **It is a tuning constant, not a design input**: flash-or-not is unchanged across any
   plausible value (§1's margin is large); batching is **latency-monotonic** (higher ack latency
   makes it *more* correct); coarse scroll holds anywhere above ~50 ms. What it moves — scroll
   step, window depth, game frame budget — is tuned after first light on our own content.
2. ~~Whether **off-screen scratch** is usable~~ — **RESOLVED 2026-08-17 by deleting its premise.**
   The **full 640×480 is visible** (§2); pre-render-then-flip-in and save-under via mode 9 (§12)
   are gone. The replacement is better. Babcock, same email:

   > "Pre-sending stuff to move in is what I was talking about with **texture caching**. There are
   > **multiple screens' worth of available or reclaimable memory**, but there's a bit of reverse
   > engineering and plumbing work left."

   ✅ **It LANDED in the installed a5d1c31 (2026-08-30)**: modes 12/13/14 (+11 teardown, 15
   refused), lease-scoped 64 KiB, documented in §4. **Adopted 2026-09-06 behind the Global `Cached
   text` row (`HANDOFF.md` §40.6)** — plane-0 text as mode-14 draws under a byte-exact proof; off
   until the on-glass checks (REMINDER items 19–20) have run. ⚠ Modes 13/14 draw one x into both
   lenses (they ignore the lens bit, `zlib_glue.c`), so depth planes stay pixels.

   🔴 **The author's own reason for building it:**

   > "Deflate (zlib, 32kb window)+RLE. This is almost as good as PNG and mostly good enough
   > (**but it doesn't play nice with antialiased fonts, hence the texture cache**)."

   AA text at 16 levels is §1's biggest visual win and a §12 decision; AA turns glyph edges into
   short high-entropy runs (the same mechanism as the dithering finding, §5). Our re-measurement
   shows the cost **lower than modelled** (§5), so AA text is viable today — **texture caching is
   what makes it cheap at scale.** *(The "32kb window" checks out: `FW_INIT2(strm, 15, …)`,
   windowBits 15 = 32768.)*
3. Whether the **msgId-255 wall** persists under CFW — **unprobed by design**: the transport cycles
   msgId so the wall is never approached (`CLAIMS.md`).
4. ~~**Keepalive/heartbeat contract** in CFW mode~~ — **RESOLVED 2026-08-16** (§4:
   `FW_KEEPALIVE_RESET()` on every top-level image message). The stock counter at `0x200745AC`
   fires teardown past 899 ticks; we never get there while rendering. Built: the transport's
   control lane sends an idle keepalive beside the 45 s lease renewal (`CfwTransportBase`).
5. ~~**Typed-text input** path~~ — **BUILT 2026-08-31**: `DamageWindow.onTypedText` +
   `Transport.injectText`; a line typed on the phone strip, the browser replica or the desktop
   preview reaches the focused window, staged behind the window's own confirm (Tmux, Files,
   Torrents and Music consume it).
6. ~~**Dirty-rect addressing constraints**~~ — **RESOLVED 2026-08-16**, §4: mode 3 quantized
   (left/width ×4, top/height ×2, one byte each, bounds-checked to 640×480); mode 8 size-capped
   (~153 KB), not count-capped; `CFW_RECT_MAX=16` is a debug-overlay limit only.
7. ~~Whether **2.2.2 → CFW direct flash** is accepted~~ — **RESOLVED 2026-08-30: done on our own
   pair** (2.2.2.20 → CFW, both lenses, six components, zero resends — `HANDOFF.md` §10). The
   prior de-risking held: Faceclaw's `app/g2/firmware-compat.ts` sets `FLASHABLE_STOCK_VERSION =
   [2,2,6,10]`, so 2.2.2.20 classified as `flashable-stock`, and the patch set targets the *stock
   2.2.6.10 image it ships* — g2flash writes a whole EVENOTA container.

### New leads opened 2026-08-16 (from Even's own schemas — see §9.1)

- 🟡 **A logger service exists on sid 0x0F** (`UI_LOGGER_APP_ID`). `logger.proto` defines
  `BLE_LOGGER_SWITCH_SET`, `BLE_LOGGER_LEVEL_SET`, `DEVICE_SEND_LOGGER_DATA` (device → phone
  `logStr`), `REQUEST_FILE_NAME`, `DELETE_FILE_NAME`. **If `bleTransEn` works on our firmware we
  get live on-glass logs** — including the CFW's `evenhub_ui: decompress failed, mode=%u
  raw_len=%u` — turning silent garbage on the lens into a visible error. **Highest-value untested
  lead.**
- 🟡 **A file EXPORT service exists** — `UX_EVEN_FILE_SERVICE_CMD_EXPORT_ID = 198` /
  `RAW_EXPORT_DATA = 199`, with `eEvenFileExportServiceCID {EXPORT_START, EXPORT_DATA,
  EXPORT_RESULT_CHECK}`; also `UX_OTA_EXPORT_FILE_CMD_ID = 194` / `195` with **no schema in this
  corpus**. ⚠ **NOT evidence of a firmware dump path** — a *file* service, and the main app image
  is not necessarily a file in that namespace — but the first real lead against "no read-back
  path", worth a read-only probe. `common.proto` `eErrorCode` has explicit `NOT_SUPPORT = 8` /
  `SUPPORT = 9`.
- 🟡 **`BleConnectParam { MTU, connInterval, setSpeed: SLOW|FAST }`** on sid 0x80, `commandId =
  BLE_CONNECT_PARAM (7)`. Our link ramps 15 → 30 → 90 ms because the *firmware* requests the
  low-power state ~2 s after connect; `setSpeed = FAST` may pin it — a lever on the ~60 ms floor
  (§5.2); unprobed. ⚠ On the dangerous sid; see below.
- ✅ **Why sid 0x80 is dangerous.** `dev_config_protocol.proto`: `UNPAIR_INFO = 9` and
  **`RESTORE_TO_FACTORY_SETTINGS = 13`** — the state g2-kit hit. The rule sharpens to
  **"commandIds 9 and 13 are irreversible; 4 (AUTHENTICATION) and 128 (TIME_SYNC) are what the
  official app already sends."**
- ⚠ **Ring biometrics: the schema exists but the glasses never send it — CORRECTED 2026-08-31.**
  `ring.proto` on `UX_RING_DATA_RELAY_ID = 145` (our `91-XX`) defines `RingRawData{battery=1,
  chargeStates=2, hr, spo2, hrv, temp, actKcal, allKcal, steps + timestamps}` — but the stock
  firmware's 0x91 service only registers the ring MAC (`EVENT`) and never fills `RawData`
  (verified against firmware source + both captures; `CLAIMS.md`). An earlier note here called
  ring battery "solved" via the relay — wrong. The only source is the closed Even SDK over the
  ring's own link; not pursued (cosmetic). See `CAPABILITIES.md` and `EXPLOSION.md` §13.
- ✅ **sid 0x0D is `sync_info`, not "configuration query"** — `sync_info_data_msg
  {backgroundAppID, foregroundAppID}`: when EvenHub is fore/backgrounded, i.e. whether our
  surface is visible.
- ✅ **The conflicting init-frame labels were re-decoded 2026-08-17** — the settled table is §9.2
  (`81-20` = case battery, `04-20` = `NotificationControl` with a 7 s display time, `07-20` =
  EVEN_AI, `0c-20` = QUICKLIST, `10-20` = ONBOARDING, `20-20` = MODULE_CONFIGURE; `0e-20` =
  `UI_HEALTH_APP_ID` is the one not in that table).
8. ~~**Transport for flashing**~~ — **RESOLVED, then EXERCISED.** beardos's Intel AX201 /
   BlueZ 5.86 ran the whole flash PC-direct 2026-08-30 (`HANDOFF.md` §10: ~25 KB/s OTA goodput,
   unbonded host, both arms) and drove the glasses within the hour — the 2026-08-15 caveats (link
   params, advertising with the phone off) are answered on hardware.
9. ~~The webflasher "recovery set" question~~ — **MOOT since 2026-08-30**: it existed to protect
   the 2.2.2 fallback, and 2.2.2 is gone (the webflasher also dropped CFW support upstream).

### On the "cheapest high-value experiment"

*(Historical — pre-flash reasoning; kept for the lesson.)* `g2flash` `demos/video-bench.ts` has
an **`lz4` mode that runs on STOCK 2.2.6.10**, reporting framerate and byte counts. Earlier text
called it the cheapest high-value experiment. Two corrections: *(2026-08-16)* **not cheap** —
2.2.2 → 2.2.6.10 **is** the irreversible step; *(2026-08-17)* **not high-value** — it measures
ack latency, a tuning constant (#1). After first light it is a fine way to price the ack
round-trip (sweep `G2_WINDOW` 1, 2, 4) — calibration, not a checkpoint.

**The general lesson:** a number can be load-bearing for a decision *already made* and carry
zero weight for every decision still open. **Before elevating any unknown, ask which open
decision changes if the answer flips. If none does, it is not a blocker.**

---

## 12. Design decisions already made

- **Damage tracking with a single mode-8 flush per frame** is the core architecture (§4).
- **Coarse scroll steps**, never per-pixel (§5).
- **Fixed cursor + panning list**: pin the selection to a screen row and pan content under it —
  recovers the free scrolling the firmware list widget gave G2CC, the one genuine regression of
  leaving firmware containers. 🔑 **Dependency (2026-08-17): requires PER-NOTCH scroll input
  (§6.1)** — under G2CC's containers scroll was a *boundary* event, so this list could not have
  been driven. ✅ **M** since 2026-08-30: every ring notch arrives as its own SCROLL event in
  daily use; the page-flip fallback was never needed. Open: coalescing under very fast spins
  (`REMINDER.md` item 2).
- ~~**Save-under for overlays** via mode 9 (pending unknown #2)~~ — **retired 2026-08-17**; its
  premise (off-panel scratch) does not exist (§2). **The fallback is the build:** overlays repaint
  the covered region with mode 3 (~215 ms stock-priced, ~65–100 ms on the CFW curve for a 300×80
  box); the context menu captures and restores the under-content the same way (`DESIGN.md`
  §4.7). Texture caching — the real version of what save-under reached for — has landed and is
  adopted for plane-0 text behind a setting (`HANDOFF.md` §40.6; §11 #2).
- **No dithering** — it halves compression; the host does better 4-bit downsampling anyway.
- **Anti-aliased text at 16 levels** — the biggest visual upgrade available.
- **Keep the both-temple silent-mode gesture** as a hardware escape hatch.
- ~~**Damage must provide its own quit path** (the CFW removes the stock one)~~ — **superseded by
  the locked design: no quit path** (`DESIGN.md` §1.6; the both-temple gesture is the escape).
- Depth: **main content parked far, popups come forward toward the screen plane** (Adam direct,
  2026-08-17 — *reverses* an earlier inferred rule; §7 and `DESIGN.md` §3).

**Added 2026-08-17 — hard-won tuning from Faceclaw's damage tracker** (`BleImageOptimizer.java`,
the closest existing analogue). Results, not preferences:

- 🔑 **Deflate level 6, not 9 and not 1.** His comment: level 9 (BEST_COMPRESSION) costs
  **18–109 ms per frame** on the BLE worker; level 1 (BEST_SPEED) **inflated typical payloads from
  ~2.7 KB past the 3800 B fragment boundary, adding an extra ack round trip (~350 ms).** Level 6
  keeps payloads inside one fragment at about half the CPU. The tuning target is the **fragment
  boundary**, not the ratio. *(`research/fbfeas.py` uses level 6.)*
- **One long-lived `Deflater` per thread** — constructing one allocates a native zlib stream,
  measurable at per-frame rates.
- **Splitting into multiple rects costs more than the byte count:** ~15 fixed bytes per rect
  (seglen + mode-3 header + fid + zlib framing) **plus lost cross-rect zlib dictionary sharing**.
  Split only across gaps big enough to pay for both; keep gap thresholds above the 4px/2px box
  alignment so aligned rects never overlap. His splitter: **vertical bands** across changed rows
  (gaps < V_GAP), then **horizontal clusters** per band (column gaps < H_GAP), then tighten rows;
  the diff scan runs in **packed-byte coordinates** (1 byte = 2 px).
- **Window geometry:** `min` = 288 px (the stock band, "leaving most of the field of view clear")
  and `max` = 480 px (terminal-style views), plus a **vertical position setting**
  (top/upper/centre/lower/bottom) sliding the 288 band within 480 — **placement rather than more
  content** (§7). *(Damage's answer, 2026-08-31: per-app height with four TOP-aligned sizes
  288/352/416/480; a vertical-position setting was built and retired the same day because Adam's
  fit always shows the top and loses the bottom — `DESIGN.md` §2.2b/§2.5.)*
- **Colour-key compositing:** pixel 0 = transparent, **1 = intentional opaque black** (identical
  to 0 after 4bpp quantisation). Shell layers need this to composite at all.
- **Layer stack with lazy `paintBelow`:** a layer pays to paint what is underneath only if it
  samples it; `paintOverBase` composites against the base directly — the practical substitute
  for save-under (§11 #2).

**Added 2026-08-17, from the reference implementation (§2, §4, §4.1):**

- **Carrier layout = one 576×288 image container + one full-screen dummy text container**
  (`content=" "`, `isEventCapture=true`). Not optional; §4.1.
- **Hold the direct-framebuffer lease** — sid 0x09 field 101 op 5, **both arms, renew every 45 s**
  against a 90 s expiry. Fail-open: forgetting it silently loses the screen.
- **Arm split: bulk pixels → LEFT, control + events → RIGHT.** Subscribe on Right regardless.
  Runs daily; optimality unproven — the two-arm capture is owed (§2, `REMINDER.md` item 5).
- **≈6 rects per mode-8 batch**, not 16; `fid` in `[1,0xFFFE]`, +1 per delta (§4).
- **Window 3 in-flight image messages.** Pipelining is proven on CFW; do not ship strictly serial.
- **Treat any sticky mode-7 flag** (`f_reorder`/`f_skip`/`f_dup`/`f_snap_of`) **as a hard
  error** — built (panic keyframe + clear); the on-panel overlay was never switched on, the flags
  reach the shell only from the simulator (§4).
- **Image fragments ≤ 3800 B** (the 4096 cap with envelope headroom) — two independent sources.
- ⚠ **Layout/CREATE frames must stay under ~1000 B.** Real, and applies *only* here.

## 13. What we want to build on top

Everything G2CC does, rebuilt without the image tax, plus what was never possible:

- **FF1** rendered properly instead of avoided
- **Far more games** — real frames at interactive rates *(`EXPLOSION.md` §10; not started — dense
  full-frame is a measured 2–4 fps, §5.2)*
- **A real ebook reader** — typography, AA text, endless scroll *(BUILT — Reader)*
- **A file manager with real icons and thumbnails** *(BUILT 2026-09-01 — Files)*
- **Mail and MMS with embedded images** *(designed in `EXPLOSION.md` §1/§2; not started)*
- **A Reddit-style feed** with endless scroll *(`EXPLOSION.md` §11; not started)*
- Real **modal dialogs**, **toast notifications**, **z-order**, **overlapping windows** *(the
  notification box, switcher wheel, context menu and keyboard are bespoke overlays — `DESIGN.md`
  §4.3/§4.5/§4.7/§4.8)*
- Access to the whole PC library *(the content host + the window channel, `HANDOFF.md` §19/§22)*

Shipped beyond the list: Tmux (2026-08-31), Torrents (2026-09-01), Music (2026-09-01/02,
`MUSIC.md`), Games (2026-09-04, `HOLDEM.md`), Feed + comics (2026-09-09, `FEED.md`). What is next
lives in `REMINDER.md`, never here.

---

## 14. What this is, legally and practically

A **personal, first-party project** for hardware Adam owns, on his own home network, with his own
accounts. Licensing was explicitly **deferred** — "this is a personal project. If I ever consider
making a version for release, I will figure out the best way to handle licensing." For reference:
g2flash is GPL-3.0, openCFW's canvas480 is GPL-3.0-only, the webflasher is MIT, and openCFW's top
level has **no license at all**.

### ⚠ Licensing is no longer fully open (2026-08-17)

Adam asked Babcock directly whether he could borrow heavily from Faceclaw and g2flash: yes —
**"subject to GPL."** So: **personal use unchanged** (GPL-3.0 imposes nothing on software you do
not distribute); **public release constrained, not deferred** — if Damage derives substantially
from Faceclaw or g2flash, **GPL-3.0 attaches on distribution**. The cheapest way to keep the
option open is a clean boundary *as we build*: our own compositor, layout engine and rasteriser,
with borrowed CFW-protocol knowledge (wire formats, constants, the lease protocol) kept separate
from borrowed *code*. Facts about a wire protocol are not copyrightable; his implementation is.
Not a reason to avoid reading his code — a reason to know which is which.

### 🔴 DECIDED 2026-08-20 — clean room, and it is no longer hypothetical

**Damage contains no `faceclaw` or `g2flash` code, and never will.** Protocol knowledge only. An
off-the-cuff public comment saying Damage would use *"some borrowed code from FaceClaw"* is
**retracted**.

What Damage *does* borrow heavily is **G2CC** — Adam's own, licence his to set, shipped and in
daily use. Anything he does not control stays out of any release — with the 2026-09-02 refinement
(`CLAUDE.md`): the line is **redistribution, not implementation**, so the *window* that drives a
third party's game may ship while the game never does (Universal Paperclips fetched from the
author's site at run time and hash-pinned; FF1 on a ROM the user rips from their own cartridge).
**Even's SDK and third-party fonts with unclear terms have no such path and stay out
unconditionally.**

**Why the timing matters:** a public, cross-platform release is intended (`DESIGN.md` §10) and
compensation is a live possibility; GPL-3.0 attaching to the whole derived work would foreclose
options open today. The boundary costs nothing to keep before the compositor exists and is close
to unfixable afterwards. The rule is in `CLAUDE.md`.

## 15. Collaboration — the Babcock thread (opened 2026-08-16)

He reached out first, having noticed the overlap between G2CC and Faceclaw (he found the address
by grepping `adam` in the public g2cc repo — hygiene note below).

- **His offer:** pull changes and add credits, **or work in a shared repo** — he prefers the
  latter: "the natural scope for a G2 UI replacement project is a bit larger than the scope of all
  the stock firmware and apps combined."
- **His current work:** (a) **EvenHub compatibility** — EvenHub apps in a webview, the layout
  rasterised to an image and sent, plus extensions over the stock API; (b) **font
  configurability** (he prefers small fonts, ships Terminus 6×12); (c) 🔑 **a texture cache in the
  firmware protocol** — §11 #2; LANDED in a5d1c31 (2026-08-30), the installed firmware.
- **Adam's stated position:** personal use, no Hub compatibility wanted, **PC-powered with the
  phone as a Tailscale bridge** — flagged to Babcock as possibly incompatible with Faceclaw's
  phone-resident design. The seam: **Faceclaw runs on the phone, Damage on the PC.** Collaborate
  below the UI (firmware, wire format, capability negotiation); stay independent above it.
- **Given back so far:** the g2-kit docs-vs-code discrepancies, including the `is_last` /
  `CompressMode` hazard, raised because `faceclaw/CLAUDE.md` still points contributors at
  `g2-kit/ble/docs/`.

### Hygiene: contact details are exposed via the public G2CC repo

Babcock found Adam's address in `github.com/expectbugs/G2CC`, which is public and carries it in
tracked files and commit metadata. The specific inventory has been redacted from this document —
enumerating addresses and their file locations in a public repo is the mistake it described. The
load-bearing part: **a working-tree scrub does not remove anything from git history.** Deciding
what to expose has to happen *before* the first push. **Adam's call; G2CC is not ours to edit**
(§10).
