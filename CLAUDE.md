# Damage (`damage-wm`) — Claude Code Rules

**What this project is:** a personal, first-party **framebuffer window manager and compositor**
for Adam's *own* Even Realities G2 smart glasses — a consumer wearable display he bought. Damage
runs **custom firmware** (`g2flash`) that replaces the vendor's EvenHub container model with
direct framebuffer access, so a PC-side compositor can render complete scenes with real fonts and
arbitrary layout and push pixels. Everything runs on hardware Adam owns over his home network —
his phone, his glasses, his PC, his auth token. Working out the glasses' Bluetooth wire format is
ordinary device-interoperability (an accessibility/customization effort for his own device); there
are **no third-party systems, networks, accounts, or credentials** involved anywhere. This is UI
and display-rendering work for a wearable.

System-wide rules in `~/.claude/CLAUDE.md` apply here too. This file holds Damage-specific rules.

**Read `overview.md` first.** It is the complete carry-over from the research that produced this
project: the decision and its evidence, the hard hardware facts, the CFW display-mode contract,
all measured numbers, every relevant external project, and the open unknowns. If this file
conflicts with `overview.md` on a fact, `overview.md` wins; on a *rule*, this file wins.

**Then read `CLAIMS.md`** — it grades every load-bearing claim (vendor-authoritative / measured /
corroborated / inferred / single-source / unknown) and lists the claims most worth distrusting. **If `overview.md` states a fact and `CLAIMS.md`
grades it `S` or `U`, do not build on it without checking.**

**Then read `DESIGN.md`** — the shell design contract, locked with Adam 2026-08-17/18: the
ring-only input grammar, the exact 640×480 cell geometry, the depth layer order, motion and
persistence policy, all six shell surfaces, the locked typeface assignments, and per-frame costs
**measured from real renders**. **It wins on shell design** the way `overview.md` wins on facts and
this file wins on rules. Its §0 lists what is *deliberately excluded* — read that before proposing
anything, so you do not re-suggest a rejected idea.

**Run `tools/lint.py` after any geometry, layout or drawn-string change.** It is the build gate from
`DESIGN.md` §9.2b — 20 rules covering the failure modes this hardware reports as **silence**
(unaligned rects, over-budget rect counts, fid gaps and reuse, mismatched stereo pairs, deltas with
no keyframe, ink budgets, and glyphs the locked faces cannot render). `--selftest` proves every rule
fires. It currently exits 0; keep it that way.

**`DESIGN.md` §10 is the deployment topology** — three roles (transport / shell / content) and four
configurations. It constrains the runtime: **the shell must run on Android and desktop, so it cannot
be Python.** Read it before proposing any implementation.

**Regenerate `design/shots/` with `python3 design/render_shots.py` after any design change**, and
read the numbers it prints. Everything renders at **true 1× 640×480** on purpose — a 2× view
flatters delicate type and misled us for several passes.

**Before any flashing conversation, run `python3 research/verify_cfw.py`.** Offline, no glasses,
proves the image is reproducible from sources we hold and carries no Thumb-bit defect.

---

## Project status and the order of work

**The system is LIVE as the all-day daily driver**: the CFW is installed (g2flash `a5d1c31`,
reports `2.2.6.10` — detect by the `EVENCFW/` capability string, never the version; first light
2026-08-30). **The topology is `HANDOFF.md` §19: the phone APK is the PRIMARY DRIVER — radio
and shell, always, while it is up; the OpenRC `damage` service is the DATA PROVIDER (content +
tmux + last-write-wins state sync) plus a STANDBY that drives PC-direct BLE only while the APK
is unavailable and hands back on its return. The PC never claims in daily use** (`--transport
remote` keeps the claim path as the explicit dev override). `REMINDER.md` is the orientation
file; `HANDOFF.md` §19–§22 the current records; `DAILY.md` the ops crib; `IMPLEMENTATION.md`
what runs and how. App layer: **Main · Settings · Reader · Tmux · Files · Torrents** (Files landed
2026-09-01 with the whole §16 shared machinery — `HANDOFF.md` §22; Torrents + the §4.8
keyboard the same evening — `TORRENTS.md`, `HANDOFF.md` §23).

Adam's stated methodology governs **the app layer**:

> Heavy research → full documentation → clean repo → the main plan → a couple hundred ridiculous
> feature-creep scope explosions → heavy refinery to bring it back to reality → passes for
> consistency and adherence to the research/documentation → a final plan of the actual
> implementation via real code → **then** slowly, carefully, start executing.

"Feature creep is my RELIGION" — the explosion phase is deliberate and wanted; the refinery
keeps it shippable. Both are DONE for the app wave (`EXPLOSION.md`: ~175 graded ideas; §20 =
the refinery verdicts and the wow order; six windows axed, Torrents added). **The current phase
is converting G2CC apps to DamageWM windows, one at a time**: Adam's per-window refinery
verdicts first, then build against the `DamageWindow` contract
(`core/…/shell/WindowContract.kt`) per the **`WINDOWS.md`** checklist, reading the G2CC
original for interaction facts only (`/home/user/G2CC/server/src/windows/`, read-only) and
`DESIGN.md` §4.6 for the mode contract. **Reader, Tmux and Files are the three worked
precedents** — Files is the only worked example of MenuSurface and the window channel.

**After ANY code change run the whole battery and keep it green:** `./gradlew :core:test`
(213 tests, including the per-lens oracle), `./gradlew :desktop:test` (9 tests: the BlueZ glue
over a fake link), `desktop --selfcheck` (89 checks), `desktop --snapshot DIR` (look at the lens
renders), `desktop --epub-check ~/books`, `python3 tools/lint.py`, `./gradlew :phone:assembleDebug`.
Radio use is normal now (post-flash); deploying = `./gradlew :desktop:stageJar && sudo
rc-service damage restart` (`DAILY.md`) — since §19 the PC never claims, so a PC deploy never
touches the display at all. Stop the service before any `:desktop:run` dev session (one set of
ports; and `ble`/`remote` dev modes are a second central/driver). `IMPLEMENTATION.md` → "Review
hardening" lists the mechanisms that are load-bearing and easy to break by accident — the
compositor's per-lens truth/shadow model, the transport's session-epoch sweep, the shell's
start/stop mutex. Do not re-introduce nominal-only seam guessing in the compositor: a pixel
simulation against the firmware model is the only judge of stereo output, and `LensOracleTest`,
`Round6Test`, `Round7Test` encode what earlier reviews caught.

---

## 🔴 Clean-room: no GPL code in Damage

**Decided 2026-08-20.** Damage may borrow **protocol knowledge** from `g2flash` / `faceclaw` freely
— wire formats, constants, mode semantics, the lease protocol, tuning values. **It must not contain
their CODE.** Facts about a wire protocol are not copyrightable; Babcock's implementation of them is,
and it is GPL-3.0.

- ⚠ An off-the-cuff Reddit comment said Damage would use *"some borrowed code from FaceClaw."*
  **That is retracted; the design does not need it.** Do not act on it.
- ✅ **G2CC is Adam's own and its licence is his** — borrow from it heavily. It is more mature,
  tested, and in daily use.
- ❌ Keep anything he does not control out of any release, as G2CC already does: Universal
  Paperclips, the FF1 ROM, Even's SDK, third-party fonts with unclear terms.
- **Why it matters now:** a public release is intended, and compensation is on the table. GPL-3.0
  attaching to the whole derived work would foreclose options that are currently open. The cost of
  keeping the boundary clean is zero *before* the compositor exists and near-unfixable after.

⇒ When reading `reference/faceclaw` or `reference/g2flash`, extract **facts into our own words and
our own implementation**. Never paste. Cite the source file in a comment, as the wire-format rule
already requires.

## Do NOT modify G2CC

`/home/user/G2CC` is a **working, shipped system** that Adam uses daily. Damage does not replace
it on disk and does not touch it.

- **Read from it freely** — it holds our authoritative BLE reverse engineering, the pre-pivot
  rasterizer, 20+ window implementations, and the render scripts. See `overview.md` §10.
- **Never edit, refactor, or "fix" anything in it** as part of Damage work.
- G2CC remains the historical fallback, but note (2026-08-31): **the glasses now run the CFW**
  and Damage drives them daily — G2CC's stock-firmware display path no longer applies to this
  pair. Stock 2.2.2.20 is gone and is not in the public archive; that door closed 2026-08-30.

## Permission and irreversibility

The global "investigating ≠ permission" rule is load-bearing here more than anywhere.

- **NEVER flash firmware without explicit, in-the-moment authorization from Adam.** Not on
  momentum, not because a plan said so, not because it "should be safe."
- **Always dry-run first:** `g2flash.py --stop-before flash` exercises discover / heartbeat /
  file_check without writing a byte. Do this before any real flash, every time.
- **Leaving firmware 2.2.2 HAPPENED 2026-08-30 — that door is closed** (no read-back path
  exists and 2.2.2 is not in the public archive). The CFW itself remains revertible to any
  archived version; state the reversibility picture out loud before any future flashing
  conversation all the same.
- **Read the patch source before flashing it.** g2flash has already shipped one HardFault
  (`overview.md` §9). We are the third serious consumer of this ecosystem, not the thousandth.

## Project-specific verify-before-execute

The global "verify before execute" applies. Project-specific extensions:

- **NEVER guess a CFW display mode byte, rect encoding, or batch layout.** Read
  `g2flash/patches/zlib_glue.c` — its header comment is the authoritative mode contract. Modes
  3/5/6/7/8/9/10/11/12/13/14/15 and the high-bit "lenses differ" flag all have exact semantics.
  Guessing here produces garbage on the lens, silently. The texture-cache modes (12–15) have a
  second authoritative file, `patches/texture_cache.c`.
- 🔴 **NEVER gate a feature on a capability token that could be dropped for space.** a5d1c31
  deleted `img576` and `compass10` from the advertised string purely to get it back under 127
  bytes — **both features are still fully implemented**. A gate demanding either would refuse a
  firmware that supports them. Require only the five in `SettingsMsg.REQUIRED_CAPS`; check
  `EVENCFW/<n>` for anything version-shaped. And parse field 100's length as a real **varint**:
  the firmware wrote it as a bare byte until a5d1c31 and shipped one build whose whole settings
  response failed to decode because the string passed 127 bytes. Field 100 is also **no longer
  last** — field 104 (mic read-back) now trails every sid-0x09 READ response.
- 🔑 **The CFW path always sends `CompressMode = 0`.** Confirmed by the CFW author 2026-08-17:
  g2flash/faceclaw "signals its own compression method with CompressMode=0 and some header bytes in
  the data field" — those header bytes are the mode byte (3/6/8/9). Nonzero CompressMode is Even's
  first-party path, kept only for stock-app compatibility. **Damage never sets it nonzero**, which
  makes the value mapping below moot in practice.
- **NEVER guess `CompressMode` values or LZ4 framing** — and note this rule was itself violated in
  an earlier version of this file, which asserted "1 = RLE, 2 = LZ4" as fact. Corrected 2026-08-17.
  `CompressMode` is `ImageRawDataUpdate` field 5, a **bare `uint32`** in Even's own schema; **no
  `ImageCompressFormat` enum exists in any of the 27 vendor schemas.** Graded evidence
  (`overview.md` §8): **`0` = uncompressed — confirmed** (three working implementations);
  **`2` = LZ4 — strong** (g2flash's exercised block encoder + Even's own SDK 0.0.12 regression),
  and it wants a raw **LZ4 BLOCK, not a frame** (most libraries emit frames, which are rejected);
  **`1` = "RLE" — UNVERIFIED**, single-source prose by one author, no citation, and never sent by
  any known implementation. Do not build on value 1 without new evidence.
  An **unknown CompressMode is silently treated as raw** — you get garbage, not an error. Our June
  2026 probe was inconclusive because it ran on **2.2.2**, which predates the feature and has no
  decoder — so it neither confirms nor refutes any mapping.
- **NEVER guess the BLE wire format from the vendor SDK or demo app.** We talk BLE directly.
  **Source-of-truth ordering, highest first** (settled 2026-08-17; the author independently gave
  the same ranking — *"g2-kit-unofficial is older reverse engineering work; the best source of
  truth for how to format messages is faceclaw"*):
  1. **Even's own protobuf schemas** — `g2-kit/ble/gen/*_pb.ts` embed vendor `FileDescriptorProto`s.
     Decode the base64 (pad it first). This is the vendor's actual `.proto`.
  2. **`faceclaw/` + `g2flash/` source** — the exercised CFW implementation.
  3. **`/home/user/G2CC/docs/G2_BLE_PROTOCOL.md`** — ours, capture-derived; authoritative for
     stock 2.2.2 and the official app specifically.
  4. `g2-kit-unofficial` `ble/*.ts` — its code.
  5. ❌ **`g2-kit-unofficial` `ble/docs/*.md` — DO NOT USE.** Materially wrong in ~8 places
     (header layout, CRC scope and endianness, fragment size, GATT UUIDs, and `is_last` where the
     schema says `CompressMode`). Contradicts its own code throughout.
- **Prose describes; code runs. When an exercised implementation exists, read it.** Every
  significant error found so far — the RLE value, the ~1000 B wall, the "single image container"
  layout, the mode-8 rect cap — was documentation disagreeing with working code. Documentation is
  a summary written by someone who already knew what they meant.
- **NEVER guess firmware addresses.** Every address in the patch set traces to openCFW's Ghidra
  corpus. If a patch depends on an address, cite where it came from in a comment.
- **Claude CLI flags, library APIs, tool flags** — run `--help`, read the source. No guessing.

## The simulator lies — always ask "sim or glass?"

Every high-frame-rate claim we investigated traced back to the EvenHub simulator, not hardware.
The simulator shows ~6 image containers where hardware holds 4, and has no BLE bottleneck at all.

- **Never cite a performance number without knowing whether it came from hardware.**
- Our own numbers in `overview.md` §5 are labeled **measured** vs **modeled** — preserve that
  distinction when quoting them. The CFW direct-framebuffer path is now MEASURED
  (`overview.md` §5.2, 2026-08-31, n=1,488 flushes): **`ms ≈ 60 + bytes/50`** — the old
  `176 + bytes/11` formula describes the stock path only, and every table still priced with it
  is conservative by ~3–5× on the CFW.

## The Three Absolute Rules

Carried over from G2CC and `/home/user/aria2/overhaul.md` §22/§23/§24. They apply to compositor,
transport, and tooling code alike.

**NO TIMEOUTS ANYWHERE.** No `wait_for`, no `timeout=`, no time-bounded execution wrappers in
BLE / render / input / flashing paths. Supervise externally. Confirmation steps wait as long as
the user needs.

**NO SILENT FAILURES, EVER. LOUD AND PROUD.** No bare `except: pass`, no catch-log-swallow. BLE
write status, ack arrival, decompress failures, dropped frames — all surface visibly. Note that
`flappy-g2`'s `// Silently ignore frame send errors` is precisely how a bogus "10 fps" claim got
into the world. Do not become that.

**NO TRUNCATION ANYWHERE.** Content scrolls; it does not get cut. Long text stays long. Strings
that don't fit raise loudly, never silently mangle.

## Forbidden patterns

- Sending **`f1=9`** on the EvenHub channel — it is `shutDown`/exit. Never send it.
- Letting **msgId exceed 255** — it is a 1-byte field; the glasses stop acking at 255 and go
  silent. Cycle it.
- Writing to **`sid=0x80` (`dev_config`)** — developer/debug fields; one early RE session
  non-terminally disabled a pair this way. Stay on `sid=0x09`.
- **Floyd-Steinberg (or any) dithering** in our renderer — it roughly halves compression by
  turning smooth runs into high-entropy noise, and the 4-bit downsample looks better without it.
- **Per-pixel or per-row scroll steps** — cost is ack-dominated, so a 40-row jump costs barely
  more than a 1-row nudge. Scroll in coarse steps.
- **One message per damaged region** — batch all damage for a frame into a single mode-8 flush.
  The ack floor is per *message*, not per *rect*. This is the project's whole thesis.
- **Interleaving multi-fragment messages** on the BLE characteristic — there is one reassembly
  buffer keyed by transport `seq`. Serialize writes.
- **Strictly serial ack-gating** of fragments where a sliding window is available — g2-kit runs 4
  in flight and **Faceclaw runs 3 on the CFW path specifically**; that is free throughput G2CC
  never took.
- **Capping a mode-8 batch at ~1000 B.** The ~1000 B multi-packet wall applies to **layout/CREATE
  frames only** (`f1=0`/`f1=7`), NOT to image data — `f1=3` chunks run 4096 B across ~18 AA packets
  in the official app's own traffic. Corrected 2026-08-17; the old blanket phrasing would have
  crippled the batching thesis. Do keep CREATE/REBUILD frames under ~1000 B.
- **Firmware patches that change image length** without bumping the preamble length — the
  bootloader programs `preamble[0]&0xFFFFFF` bytes with **no bounds check**; an overrun past MRAM
  end is an SWD-only recovery. ⚠ Note the shipped CFW **is** enlarged (+20,127 B) and bumps the
  preamble correctly; safety comes from that bump, `check_mainapp_fits_mram()`, and ~403 KB of
  headroom — not from length-preservation. See `overview.md` §3.
- **Re-sending an already-written flash block** — the c0/c1 OTA path has no block index and no
  dedup; a resend double-advances the offset and leaves it inconsistent.
- Hard-coded wire constants or firmware addresses **without a source comment** naming the file or
  capture they came from.

## Compositor discipline

- 🔴 **The canvas is 640×480, not 576×288.** Confirmed by the CFW author 2026-08-17: *"The full
  640x480 area is visible."* 576×288 is the **EvenHub container** geometry (a carrier), not the
  panel — the CFW's modes 3/6/8/9 operate on the full physical framebuffer. That is **1.85× the
  area** older notes assume, so treat any pre-2026-08-17 layout number as wrong.
  ⚠ **Safe area:** usable extent is fit-dependent ("you can lose part of the top or bottom to
  optical occlusion depending how the glasses sit on your face"). Keep load-bearing UI centred;
  treat outer rows as bonus, never as required.
- **Default to 640×288; go taller only when an app earns it.** Babcock: *"covering up too much FoV
  is annoying. Most Faceclaw UI is 640x288 for this reason"* — full height only for his Terminal.
  Same instinct as Adam's FAR display-distance setting. And **the 64 columns of width headroom are
  the stereo-shift budget** ("the width headroom is used for a depth effect"), so full-640-wide
  spends the depth allowance on pixels. Know which trade you are making.
- **There is no off-panel scratch space.** Pre-render-off-panel-then-flip-in and save-under-via-
  mode-9 are both dead — nowhere to hide. Overlays repaint the covered region with mode 3.
- 🆕 **The texture cache LANDED (g2flash a5d1c31, 2026-08-30).** It is no longer future work.
  A **lease-scoped 64 KiB** phone-owned cache: **mode 12** writes it, **mode 13** draws a cached
  image, **mode 14** draws a string through a 96-entry glyph table, **mode 11** tears the session
  down. A cached image is `[w:u8][h:u8][4bpp RLE of exactly w*h pixels]` — **no row pad nibble**,
  unlike modes 3/6. Read `patches/texture_cache.c`; `core/.../wire/TextureCache.kt` holds our
  layout and `CfwModes` the encoders.
  - **The cache dies with the lease** — freed on expiry, on FB_RELEASE, on a fresh acquire after a
    lapse, and on mode 11; kept across a *renewal*. Upload the atlas once per lease, not per frame.
  - ❌ **Never emit mode 15** (draw with the firmware's own 20 px font). Its pixels come from an
    LVGL font chain inside the firmware, so no offline model can predict them and the per-lens
    oracle stops being exact. Modes 13/14 draw from a cache *we* wrote, so the model reproduces
    them bit for bit. The simulator refuses mode 15 loudly on purpose.
- **Damage tracking with a single mode-8 flush per frame** is the architecture. Accumulate dirty
  rects across all windows; emit one atomic batch. **Cap the batch at ~6 mode-3 rects**, not 16:
  a mode-3 sub-message burns a `fid`, the firmware's duplicate-fid ring is 16 deep, and a
  collision is **silently skipped**, not rejected. Keep `fid` in `[1,0xFFFE]`, +1 per delta.
  ⚠ **Only mode 3 burns a fid** — modes 6/9/13/14 do not touch the ring, so the ~6 cap prices
  *deltas only*; cached draws ride the same batch for free. A mode-8 batch accepts sub-modes
  **3/6/9/13/14/15** as of a5d1c31 (was 3/6/9). The `CFW_RECT_MAX = 16` rect list is diagnostic
  only — it feeds the debug overlay's outlines, not the panel push, which is always full-screen.
- **The carrier layout is image container + a full-screen dummy TEXT container** (`content=" "`,
  `isEventCapture=true`). g2flash's README says "a single image container" — that is incomplete;
  image-only layouts ack but never paint. That dummy widget is also why the framebuffer lease
  exists (it is the "swipe-capturing stock widget" whose repaints the lease suppresses).
- **Hold the direct-framebuffer lease or lose the screen.** sid 0x09 field 101 op 5 (FB_ACQUIRE),
  **both arms**, renew every 45 s against a 90 s expiry. It fails OPEN: stop renewing and stock
  LVGL silently repaints over us. This is correctness, not optimization.
  🆕 **As of a5d1c31 the lease gates far more than repainting.** `cfw_fb_lease_active()` is now
  the CFW's general "Faceclaw owns this session" predicate: without it you also lose **modes
  12/13/14/15**, you lose **long-press forwarding** (events 9/10), and the stock **"End this
  feature?" quit dialog comes back**. The 45 s renewal is *our* policy — the firmware defines
  only the 90 s deadline.
- **A mode-3 delta requires a prior mode-6 keyframe.** Track that state; never emit a delta
  against an unseeded shadow. ⚠ **The compositing base is not free either** — the display driver
  has been observed handing back a buffer *two frames back*, so deltas composited onto a stale
  base and diverged per lens. The CFW's snapshot FIFO fixes this at the source, but **leave the
  mode-7 diagnostic overlay ON during bring-up** and treat any sticky flag (`f_reorder`, `f_skip`,
  `f_dup`, `f_snap_of`) as a hard error. That is what NO SILENT FAILURES means here.
- **Push a sacrificial warmup frame** after container creation — the first burst is silently
  dropped by firmware (g2-kit gotcha, confirmed independently).
- **Endless scroll = mode 8 { mode 9 rect-copy + mode 3 fill }** — shift on-device, transmit only
  the newly exposed strip.
- **Fixed cursor, panning content** for lists — pins the selection to a screen row and pans
  content under it. This recovers the free scrolling we lose by leaving firmware list containers.
- **Anti-alias text** across the 16 gray levels. Do not ship a 1-bit-looking font; that was the
  stock firmware's limitation, not ours.
- **Damage must provide its own quit path** — the CFW removes the stock "End this feature?"
  dialog, which was the only stock way out of an app. ⚠ **Conditional since a5d1c31:** the dialog
  is suppressed only while the FB lease is held. Lose the lease and it returns — which is a
  safety net, not a substitute for our own quit.
- 🔴 **A long-press is UNATTRIBUTED — never build grammar on its source.** Since a5d1c31 either
  temple touchpad raises event 9 as well as the ring, and `Sys_ItemEvent.EventSource` is **absent**
  for event types 9 and 10: the stock sender writes that field only inside a branch gated on
  `EventType == 0 || EventType == 3`, and the message struct is memset to 0, so proto3 omits it.
  Verified at instruction level on our pinned 2.2.6.10 base and on 2.2.4.34 — it has never worked,
  and Faceclaw's own decoder hides it by calling every unattributed press "ring". The only
  discrimination available is which arm's link the notify arrived on. `DESIGN.md` §1.2's "a bare
  long-press is a no-op" is what keeps the extra accidental source harmless — do not weaken it.
- **Depth (stereo):** horizontal offsets only, never vertical; never different *content* per eye;
  small magnitudes. Adam sets display distance to **far** on purpose so the HUD is ignorable at
  work and while driving. 🔴 **Layer order (Adam direct, 2026-08-17): main content sits as far
  back as depth comfortably allows; notifications, modals and popups come FORWARD in front of it.**
  This reverses an earlier *inferred* rule ("background farther, never foreground nearer") that was
  never his — do not reintroduce it. `DESIGN.md` §3 holds the ladder and calibration plan.

## Hardware and environment

- **The glasses have no power switch.** The case is the only power control, and it lives at home
  during Adam's workday. Any recovery procedure that assumes a power cycle is unavailable at work.
- **Phone-side recovery only, at work:** "scanning forever" while the OS shows Connected means a
  stale ACL — the lenses stop advertising. Toggling phone Bluetooth fixes it.
- **Subscribe to RIGHT for async events** — Left is silent on them. That part is solid.
  ⚠ **But "drive Right" is wrong for CFW image traffic.** Faceclaw hardcodes
  `sendImagesToLeft = true` and sends **every** image message to the LEFT arm, keeping Right for
  heartbeat/settings/shutdown/audio/IMU. The firmware propagates cross-lens, so either arm may
  receive. Reference split: **bulk pixels → LEFT, control + events → RIGHT.** Damage runs this
  split on hardware daily (it works), but it remains *strong, not proven* as the OPTIMAL split —
  the confirming two-arm capture has still not been taken. See `overview.md` §2.
- **BLE:** MTU 247, **1M PHY only** (2M is rejected), ~232 B per AA fragment, **7–13 KB/s measured
  end-to-end** (corrected 2026-08-17 — the old ~16.6 figure took only the fast mode of a trimodal
  gap distribution). ⚠ That is **~10× under the 1 Mbit spec and the cause is not yet known** —
  `overview.md` §5.1 has the capture analysis and what it rules out.
- Adam's phone is a **Pixel 10a**; the PC is **beardos** (Gentoo, OpenRC, Portage — see the global
  CLAUDE.md; never `systemctl`, never `apt`). Node 24, Python 3.13 via project venvs only.
- **PC-direct BLE is hardware-proven** (first light 2026-08-30, first try): BlueZ 5.86 via
  `bluez-dbus` + `dbus-java` (both MIT) — see `desktop/BlueZLink.kt`. Flashing goes through
  `reference/g2flash/g2flash.py` PC-direct (the webflasher dropped CFW support upstream).

## Testing safety

- **Real-glasses testing is required for anything touching the wire.** Mock transports catch
  state-machine bugs; only hardware catches protocol bugs — and the simulator actively misleads
  on performance and container limits.
- **Never trigger real outbound side effects from tests** — no BLE writes, no flashing, no audio.
  Mock the transport or write frames to disk and compare bytes.
- **Verify bytes, not intentions.** A G2CC lesson: an "evidence artifact" that was never actually
  rendered hid a real bug for days. Decode what you actually sent.
- Use disposable directories; never pollute `/home/user/G2CC`.

## Wire-format source discipline

Every byte traces to a reference. Firmware updates can change the wire format — when a known-good
frame stops working after an update, suspect format drift first.

- Comment the lineage on any frame construction: `// g2flash/patches/zlib_glue.c :: mode 8` or
  `// G2CC docs/G2_BLE_PROTOCOL.md §6.5` or `// captures/<file>.btsnoop @ frame N`.
- **The vendor's demo app is not a protocol reference** — it goes through an SDK that abstracts
  away the wire format we implement.
- **G1 SDKs are architectural references only.** G2 ≠ G1. Never copy G1 UUIDs or characteristic
  IDs.

## Communication

Adam's global rules apply: direct, casual, no padding, no headers on short answers. Two more that
came up repeatedly during the research phase:

- **Distinguish measured from modeled, every time.** He asked for "what we know vs guessing"
  explicitly and it changed his decisions. Say which is which without being asked.
- **Terminal scrolling is hard at his work machine** — put links, APK paths, and key actions
  **last** in a response.

### 🔴 Plain engineering wording — this costs Adam real time

This project talks about firmware, radios, memory and recovery all day, and dramatic phrasing
around those subjects has repeatedly caused false positives in the model's own safety checks —
often enough that Adam has had to switch models mid-session to get work done. **This is a standing
constraint on how we write, in chat and in every file we author.** It is not about softening the
engineering; the facts stay exactly as sharp. It is about choosing the plain word over the vivid
one when both say the same thing.

| instead of | write |
|---|---|
| kill / killed the link | ends, stops responding, drops |
| brick / bricked | unrecoverable, needs SWD recovery, not restorable over the radio |
| hammering / flooding / spamming | repeating, retrying, an unbounded loop of |
| corrupt / destroy / wipe | overwrite, leave inconsistent, clear |
| attack / exploit / bypass / injection | defect, workaround, unchecked input, appended code |
| shellcode / payload | routine, appended instructions, message body |
| crash | fault, ends early, stops |
| dead / suicide / hang | unresponsive, stalled, not returning |

Two more habits that help: describe **what the code does** rather than what it does *to* something
("the write is refused" beats "the write is rejected and everything after it is destroyed"), and
keep severity in the **grading** rather than the adjectives — this project already has `CLAIMS.md`
grades and words like "verified", "inferred" and "unverified" doing that job precisely.

Where an established term is genuinely the clearest (the bootloader's unbounded program is a real
hazard with a real name), keep it, use it once, and explain it plainly rather than repeating it for
emphasis.
