# Damage (`damage-wm`) — Claude Code Rules

**What this is:** a personal, first-party framebuffer window manager and compositor for Adam's own
Even Realities G2 glasses. They run custom firmware (`g2flash`) that replaces the vendor's EvenHub
container model with direct framebuffer access, so a compositor renders whole scenes with real fonts
and pushes pixels. Everything runs on hardware Adam owns over his home network: his phone, glasses,
PC, auth token. Working out the glasses' Bluetooth wire format is ordinary device interoperability.
Outside accounts: his TorrentLeech login (Torrents window; credentials only in the gitignored
`~/.damage/config.json`), three keyless lyric services, YouTube via yt-dlp and the local `claude`
CLI under his own login (Music window). Nothing else. This is UI and display-rendering work.

System-wide rules in `~/.claude/CLAUDE.md` apply. This file holds Damage-specific rules.

**Read order and precedence.** `overview.md` (the research record; wins on FACTS) → `CLAIMS.md`
(grades every load-bearing claim V/M/C/I/S/U; **an `S` or `U` claim is not built on without
checking**) → `DESIGN.md` (the shell design contract, locked with Adam; wins on SHELL DESIGN; its §0
lists what is deliberately excluded — read it before proposing anything) → this file (wins on RULES)
→ `REMINDER.md` (the entry point: what is true now, what is next).

**Gates.**
- `python3 tools/lint.py` after any geometry, layout or drawn-string change: 21 rules for the failure
  modes this hardware reports as silence (unaligned rects, rect budgets, fid gaps/reuse, mismatched
  stereo pairs, deltas without a keyframe, ink budgets, glyphs the locked faces cannot render).
  SYM002 checks every Kotlin string literal. `--selftest` passes; the repo run exits 0. Keep it so.
- `python3 design/render_shots.py` after any design change; read the numbers. Everything renders at
  **true 1× 640×480**: a 2× view flatters delicate type and misled us for several passes.
- `python3 research/verify_cfw.py` before any flashing conversation (offline; proves the image is
  reproducible from held sources and carries no Thumb-bit defect).
- `DESIGN.md` §10 (three roles, four deployments) binds the runtime: **the shell runs on Android and
  desktop, so it is not Python.**

---

## Status and the order of work

**LIVE as the all-day daily driver.** CFW g2flash `a5d1c31` (reports `2.2.6.10`; detect by the
`EVENCFW/` capability string, never the version; first light 2026-08-30). Topology (`HANDOFF.md`
§19): the **phone APK is the primary driver** (radio and shell); the OpenRC `damage` service on
beardos is the **data provider** (content, tmux, last-write-wins state sync) and a **standby** that
drives PC-direct BLE only while the APK is away. The PC never claims in daily use (`--transport
remote` is the dev override). `HANDOFF.md` holds the dated records (§19–§44 current), `DAILY.md` the
ops crib, `IMPLEMENTATION.md` what runs and how. App layer: Main · Settings · Reader · Tmux · Files ·
Torrents · Music · Games · Feed. `WINDOWS.md` is the conversion checklist; `TMUX.md`, `TORRENTS.md`,
`MUSIC.md`, `HOLDEM.md`, `FEED.md` the per-window records; `POPOVER.md` the popover family + the
Claude path (spec, 2026-09-12, not built).

**Rules from the whole-codebase reviews (`HANDOFF.md` §25–§36) that bind every change:**
- **A rect a paint returns is a promise.** Size every band from the face's MEASURED ink
  (`ascent + descent`), never a line height or a constant; ink outside a declared damage rect is never
  sent and no check can see it (§27, §29). The truth oracle is a standing gate: `--selfcheck` runs it
  on every settle, `OracleWalkTest` over a seeded random walk at all four heights.
- **The harness is part of the system under review** (§30): a wait decides on ONE evaluation, a
  scripted scene pins its seed, a sample compared to the glass is taken ON the loop
  (`Shell.sampleIdle`), and a rate is measured twenty times, not three.
- **Live-drive the real program before calling a round done** (§28.2, §33): `tools/glassdrive.py`
  drives the phone's shell and snapshots the mirror; one step per snap in any window with an
  irreversible row; never rebuild the jar under a running instance.
- **Never answer a refused image with more images** (§36): in the firmware's Silent Mode every frame
  is refused; the shell sleeps with the glasses, drops the lease on purpose, and the page traffic
  sleeps with it (§42: no keepalive or carrier refresh into an ended page; no release into a link
  that is gone). A lost ack is released by a later ack, never held for a msgId cycle (§34).
- **Latency is a standard, not a pass** — the section below and `WINDOWS.md` §6 bind every window
  and surface. `REMINDER.md` carries the measured numbers to price with.

**Adam's methodology for the app layer:** heavy research → full documentation → clean repo → the main
plan → a couple hundred feature-creep scope explosions ("feature creep is my RELIGION") → heavy
refinery back to reality → consistency passes against the research → a final plan in real code →
then slowly, carefully, execute. Explosion and refinery are DONE (`EXPLOSION.md`; §20 = the verdicts
and the build order). **Current phase: converting windows one at a time** — Adam's per-window
verdicts first, then build against `DamageWindow` (`core/…/shell/WindowContract.kt`) per
`WINDOWS.md`, reading the G2CC original (`/home/user/G2CC/server/src/windows/`, read-only) for
interaction facts only. Precedents: Files and Torrents (MenuSurface, the window channel), Torrents
(the §4.8 keyboard), Music (two-host contract, push frames, the §4.9 exclusive mode), Games (no host:
pure Kotlin, a CanvasView, its own stereo planes, the kit under `windows/games/kit/`), Feed (one fetch
engine in core on both hosts; a fallback that switches one way by itself; a canvas with a button bar).
Two of Adam's rules bind every window: **built whole to its best state before the next — no v1/v1.5
staging**; **each app's notification toggles live in its own Settings category, never Global**
(Global keeps only `Notify · Damage` and the APK-wide `Phone notifications` switch; the shell never
gates an app's source on a hidden field).

**After ANY code change run the whole battery and keep it green:**
`./gradlew :core:test` (525) · `./gradlew :desktop:test` (15) · `desktop --selfcheck` (230 checks, the
truth oracle on every settle) · `desktop --snapshot DIR` (look at the renders) · `desktop --epub-check ~/books` · `desktop --music-check` · `desktop --games-check` · `desktop --feed-check` (`live` fetches
the real sites once, read-only) · `python3 tools/lint.py` · `./gradlew :phone:assembleDebug` **in its
own gradle invocation** (run with `:core:test` it loaded the box enough for the oracle walk to miss a
settle). After any card-art change also `desktop --card-render` and look at `design/shots/cards/`.
**Run `--snapshot` and any harness you suspect more than once; twenty times when the question is a
rate** (§27.6, §30). Deploy = `./gradlew :desktop:stageJar && sudo rc-service damage restart`
(`DAILY.md`); a PC deploy never touches the display. Stop the service before any `:desktop:run` dev
session (one set of ports; `ble`/`remote` dev modes are a second central). `IMPLEMENTATION.md` →
"Review hardening" lists the load-bearing mechanisms: the compositor's per-lens truth/shadow model
(a plane-0 delta may hold no other plane's pixels), the transport's session-epoch sweep, the shell's
start/stop mutex, the measured list rhythm. Never re-introduce nominal-only seam guessing in the
compositor: a pixel simulation against the firmware model is the only judge of stereo output, and
`LensOracleTest`, `Round6Test`, `Round7Test`, `Review20260903Test` encode what earlier reviews caught.

---

## Clean-room: no GPL code in Damage

Decided 2026-08-20. Damage borrows **protocol knowledge** from `g2flash` / `faceclaw` freely (wire
formats, constants, mode semantics, the lease protocol, tuning values). **It must not contain their
CODE** — facts about a wire protocol are not copyrightable; Babcock's implementation is GPL-3.0.
- A Reddit comment saying Damage would use "borrowed code from FaceClaw" is retracted; do not act on it.
- **G2CC is Adam's own** — borrow from it heavily.
- **Never redistribute someone else's work, but the WINDOW that drives it may ship** (Adam,
  2026-09-02; supersedes the older blanket ban). Distribution is the axis, not implementation — the
  browser posture. Two shapes: **Universal Paperclips** = fetch-not-vendor (engine pulled at run time,
  SHA-256-pinned, never committed — `G2CC games/paperclips/fetch.mjs`; Damage generates the DOM from a list of element ids rather than
  shipping Lantz's markup); **FF1** = the emulation posture (the user rips their own cartridge; only
  the bridge ships; ROM-derived output counts as the work — ship `gen_data.py`, gitignore
  `data/*.json`; check the vendored `reference/` disassembly's licence before republishing).
  Unconditionally out: Even's SDK, third-party fonts with unclear terms, any faceclaw/g2flash code.
- Why: a public release is intended and compensation is on the table; GPL-3.0 attaching to the
  derived work forecloses options. Reading `reference/faceclaw` or `reference/g2flash`: extract facts
  into our own words and implementation, never paste, cite the source file in a comment.

## Do NOT modify G2CC

`/home/user/G2CC` is a shipped system. Read from it freely (the BLE reverse engineering, the
pre-pivot rasterizer, 20+ windows, render scripts — `overview.md` §10). Never edit, refactor or fix
anything in it. Its server is retired (`HANDOFF.md` §44) — never start it by hand. Note the glasses
now run the CFW: G2CC's stock-firmware display path no longer applies to this pair; stock 2.2.2.20 is
gone and not in the public archive.

## Permission and irreversibility

"Investigating ≠ permission" is load-bearing here more than anywhere.
- **Never flash firmware without explicit, in-the-moment authorization from Adam.** Not on momentum,
  not because a plan said so.
- **Always dry-run first:** `g2flash.py --stop-before flash` (discover / heartbeat / file_check,
  writes nothing), before any real flash, every time.
- Leaving 2.2.2 happened 2026-08-30; that door is closed (no read-back path). The CFW remains
  revertible to any archived version; state the reversibility picture before any flashing conversation.
- **Read the patch source before flashing it** (g2flash has shipped one HardFault, `overview.md` §9).

## Project-specific verify-before-execute

- **Never guess a CFW display mode byte, rect encoding or batch layout.** `g2flash/patches/zlib_glue.c`'s
  header is the mode contract (modes 3/5/6/7/8/9/10/11/12/13/14/15 and the high-bit "lenses differ"
  flag); `patches/texture_cache.c` for modes 12–15. Guessing produces garbage on the lens, silently.
- **Never gate a feature on a capability token that could be dropped for space.** a5d1c31 dropped
  `img576` and `compass10` from the advertised string to fit 127 bytes; both features remain. Require
  only `SettingsMsg.REQUIRED_CAPS`; check `EVENCFW/<n>` for anything version-shaped. Parse field 100's
  length as a real varint (one build's whole settings response failed to decode past 127 bytes).
  Field 100 is no longer last: field 104 (mic read-back) trails every sid-0x09 READ response.
- **The CFW path always sends `CompressMode = 0`** (the CFW author, 2026-08-17: the mode byte in the
  data field is the compression signal). Never set it nonzero. `CompressMode` is `ImageRawDataUpdate`
  field 5, a bare uint32; no `ImageCompressFormat` enum exists in any vendor schema. Evidence
  (`overview.md` §8): `0` = uncompressed (confirmed); `2` = LZ4 (strong; wants a raw LZ4 BLOCK, not a
  frame); `1` = "RLE" (UNVERIFIED, single-source). An unknown value is silently treated as raw.
- **Never guess the BLE wire format from the vendor SDK or demo app.** Source order, highest first:
  (1) Even's own protobuf schemas — vendor `FileDescriptorProto`s embedded as base64 in `g2-kit/ble/gen/*_pb.ts` (pad before decoding); (2) `faceclaw/` +
  `g2flash/` source; (3) `/home/user/G2CC/docs/G2_BLE_PROTOCOL.md` (capture-derived; authoritative
  for stock 2.2.2); (4) `g2-kit-unofficial` `ble/*.ts` code; (5) ❌ `g2-kit-unofficial` `ble/docs/*.md`
  — DO NOT USE (wrong in ~8 places; contradicts its own code).
- **Prose describes; code runs.** Every significant error so far (the RLE value, the ~1000 B wall, the
  "single image container", the mode-8 rect cap) was documentation disagreeing with working code.
- **Never guess firmware addresses.** Every address traces to openCFW's Ghidra corpus; cite it.
- Claude CLI flags, library APIs, tool flags: `--help` or the source, never memory.

## The simulator lies — always ask "sim or glass?"

Every high-frame-rate claim we investigated traced to the EvenHub simulator (≈6 image containers
where hardware holds 4; no BLE bottleneck). Never cite a performance number without knowing whether it
came from hardware; preserve `overview.md` §5's measured-vs-modeled labels. The CFW direct path
measured `ms ≈ 60 + bytes/50` (PC-direct, 2026-08-31, n=1,488) — but that is four hours of one session
(`HANDOFF.md` §31.6): later flushes measure ~6× slower on the transfer term (a 6–12 KB flush 1,193 ms),
and the daily path is the phone's (~70 ms + ~120 ms/KB). **Price with `REMINDER.md`'s table.**

## The Three Absolute Rules

**NO TIMEOUTS ANYWHERE.** No `wait_for`, no `timeout=`, no time-bounded wrappers in BLE / render /
input / flashing paths. Supervise externally; pacing and liveness decisions only.
**NO SILENT FAILURES, EVER.** No bare `except: pass`, no catch-log-swallow. Write status, ack arrival,
decompress failures, dropped frames all surface visibly (`flappy-g2`'s "silently ignore frame send
errors" is how a bogus "10 fps" got into the world).
**NO TRUNCATION ANYWHERE.** Content scrolls; it is never cut. Strings that don't fit raise loudly.

## Forbidden patterns

- Sending **`f1=9`** on the EvenHub channel (shutDown/exit).
- Letting **msgId exceed 255** (1-byte field; the glasses stop acking). Cycle it.
- Writing to **`sid=0x80` (`dev_config`)** — one early session non-terminally disabled a pair. Stay on `sid=0x09`.
- **Dithering** of any kind — roughly halves compression; the 4-bit downsample looks better without it.
- **Per-pixel or per-row scroll steps** — cost is ack-dominated; scroll in coarse steps.
- **One message per damaged region** — batch a frame's damage into one mode-8 flush (the thesis).
- **Interleaving multi-fragment messages** on the characteristic (one reassembly buffer per `seq`).
- **Strictly serial ack-gating** where a sliding window exists (Faceclaw runs 3 in flight on the CFW path).
- **Capping a mode-8 batch at ~1000 B** — that wall is for layout/CREATE frames (`f1=0`/`f1=7`) only;
  `f1=3` image chunks run 4096 B. Keep CREATE/REBUILD under ~1000 B.
- **Firmware patches that change image length without bumping the preamble length** — the bootloader
  programs `preamble[0]&0xFFFFFF` bytes with no bounds check; past MRAM end is SWD-only recovery. The
  shipped CFW is enlarged (+20,127 B) and bumps it correctly; safety is that bump,
  `check_mainapp_fits_mram()` and ~403 KB of headroom (`overview.md` §3).
- **Re-sending an already-written flash block** (no block index, no dedup; the offset double-advances).
- Hard-coded wire constants or firmware addresses **without a source comment**.

## Compositor discipline

- **The canvas is 640×480**, not 576×288 (the EvenHub container). Safe area is fit-dependent: keep
  load-bearing UI centred; outer rows are bonus. **Height is a setting**: four TOP-aligned sizes
  288/352/416/480, Global default 480, per-app `preferredHeight`; Adam's fit loses the bottom. The 64
  columns of width headroom are the stereo-shift budget — full-640-wide spends depth on pixels.
- **No off-panel scratch space.** Overlays repaint the covered region with mode 3.
- **The texture cache** (a5d1c31): a lease-scoped 64 KiB cache; mode 12 writes, 13 draws a cached
  image, 14 draws a string through a 96-entry glyph table, 11 tears down. A cached image is
  `[w:u8][h:u8][4bpp RLE of w*h]`, no row pad nibble. Freed on expiry, FB_RELEASE, a fresh acquire and
  mode 11; kept across a renewal — upload the atlas once per lease. `core/.../wire/TextureCache.kt`,
  `CfwModes`. ❌ **Never emit mode 15** (the firmware's own font; no offline model predicts its pixels).
- **One mode-8 flush per frame**, capped at the fid budget: **5 mode-3 rects** at the 3-deep pipeline
  (`Geometry.rectBudget`; the duplicate-fid ring is 16 deep and a collision is silently skipped).
  `fid` in `[1,0xFFFE]`, +1 per delta. Only mode 3 burns a fid; 6/9/13/14 ride free. A batch accepts
  sub-modes 3/6/9/13/14/15. `CFW_RECT_MAX = 16` is diagnostic only.
- **The carrier layout is an image container plus a full-screen dummy TEXT container**
  (`content=" "`, `isEventCapture=true`); image-only layouts ack but never paint.
- **Hold the framebuffer lease or lose the screen**: sid 0x09 field 101 op 5, both arms, renew every
  45 s against the firmware's 90 s expiry; it fails open. **Exception (§36): in the firmware's Silent
  Mode the shell releases the lease on purpose** (every image is refused there, status 5; `Shell.enterSilentGlasses`). The wake
  **rebuilds the session** (§38): `Transport.restartSession`, the keeper starts the shell from the
  prelude up, the new start reads the glasses' state before its first frame. Since a5d1c31 `cfw_fb_lease_active()`
  also gates modes 12–15, long-press forwarding (events 9/10) and suppresses the stock quit dialog.
- **A mode-3 delta requires a prior mode-6 keyframe.** Never emit a delta against an unseeded shadow;
  the per-lens model + the oracle + the keyframe rule are the guard, and the simulator raises any
  sticky flag (`f_reorder`, `f_skip`, `f_dup`, `f_snap_of`) as a hard error. Nothing sends mode-7 sub-2.
- **Push a sacrificial warmup frame** after container creation (the first burst is dropped).
- **Endless scroll = mode 8 { mode 9 copy + mode 3 fill }, DETECTED** (§31): `CanvasShift.detect`
  compares the frame before and after a repaint and declares the translation. Never add a "shift by N"
  field to a window contract; if a surface is not getting the copy, find out why the detector declined.
- **Fixed cursor, panning content** for lists. **Anti-alias text** across the 16 levels.
- **There is no quit path** (`DESIGN.md` §1.6): the WM runs always; the both-temple long-press → Silent
  Mode is the hardware escape.
- **A long-press is UNATTRIBUTED — never build grammar on its source.** `Sys_ItemEvent.EventSource` is absent for
  event types 9/10 (verified at instruction level on 2.2.6.10 and 2.2.4.34); only the arm's link says
  anything. `DESIGN.md` §1.2's bare-long-press no-op keeps the accidental source harmless.
- **Depth:** horizontal offsets only, small, never different content per eye. **Main content sits as
  far back as comfortable; notifications, modals and popups come FORWARD** (Adam, 2026-08-17). The
  ladder (§41.2): Global `Depth` moves everything; the selection bar sits one notch (4) nearer than
  the plane it selects on; the per-app `Depth` row (default `global`) moves only that app's content.

## Latency standards (`HANDOFF.md` §37; `WINDOWS.md` §6)

The daily path is the phone's radio: ~70 ms per flush plus ~120 ms per KB.
- **A gesture is priced by its FIRST flush**: its bytes plus one floor. Make it small (a mode-9
  translation ~40 B, or a strip); the heavy fill follows. Measure time to first visible change with
  `tools/journal_report.py`; a list notch at 0.8 s is a defect.
- **Text and icons go through the texture cache when Global `Cached text` is on** (§41.4): every
  rasterizer is a `CachedText` recorder, every icon crosses `IconPaint.blit`, the atlas is per lease.
  Cached draws are FLAT in the firmware, so a depth-plane rect ships as a base delta widened by the
  disparity, the draws at nominal x, and one per-lens mode-9 copy (`DisplayOp.CopyPair`), proven per
  lens before it ships; the journal says why a rect went to pixels (`cacheMiss`). Never repaint a whole list because one cell changed.
- **Chrome never justifies its own flush** (`DESIGN.md` §8.3).
- **A live window may keep the link busy only while ACTIVE** (Adam, 2026-09-05); inactive, park the
  poll and drop the subscription. Each update costs what changed.
- **The phone's CPU is a term of its own**: never wrap, parse or lay out what did not change
  (`FlowRender`'s per-line memo, `Wrap`'s estimate, the rasterizers' caches are the precedents); never
  network or disk on the loop; read the journal's split (`handlerMs`, `slidesMs`, `chromeMs`,
  `textMs`, `truthMs`, `compressMs`).
- **Animation adapts to the link** (`Slide frames`, default `auto`). A window never adds frames.
- **Every new window ships with its latency profile** measured through `tools/glassdrive.py`.

## Hardware and environment

- **No power switch** on the glasses; the case is the only power control and lives at home. At work
  the recovery is phone-side: "scanning forever" while the OS shows Connected is a stale ACL — toggle
  phone Bluetooth.
- **Subscribe to RIGHT for async events** (Left is silent). **Bulk pixels → LEFT, control + events →
  RIGHT** (Faceclaw's split; runs daily; graded strong, not proven optimal — `overview.md` §2).
- **BLE:** MTU 247, **1M PHY only**, ~232 B per AA fragment, 7–13 KB/s measured end-to-end (~10× under
  spec, cause unknown — `overview.md` §5.1). The phone path is ~8 KB/s (one packet per 30 ms event).
- Phone: Pixel 10a. PC: beardos (Gentoo, OpenRC, Portage). PC-direct BLE is hardware-proven (BlueZ
  5.86 via `bluez-dbus` + `dbus-java`, both MIT — `desktop/BlueZLink.kt`). Flashing goes through
  `reference/g2flash/g2flash.py` PC-direct.

## Testing safety

- Anything touching the wire needs real glasses; mocks catch state-machine bugs only.
- Never trigger real outbound side effects from tests — no BLE writes, no flashing, no audio.
- **Verify bytes, not intentions:** decode what you actually sent.
- Disposable directories only; never pollute `/home/user/G2CC`.

## Wire-format source discipline

Every byte traces to a reference; comment the lineage on frame construction
(`// g2flash/patches/zlib_glue.c :: mode 8`, `// G2CC docs/G2_BLE_PROTOCOL.md §6.5`,
`// captures/<file>.btsnoop @ frame N`). When a known-good frame stops working after a firmware
update, suspect format drift first. The vendor demo app is not a protocol reference. G1 SDKs are
architectural references only — never copy G1 UUIDs or characteristic IDs.

## Communication

Adam's global rules apply. Two more: **distinguish measured from modeled every time**, unasked; and
put links, APK paths and key actions **last** (terminal scrolling is hard at his work machine).

### Plain engineering wording — this costs Adam real time

Dramatic phrasing about firmware, radios, memory and recovery has repeatedly tripped the model's own
safety checks, often enough that Adam has had to switch models mid-session. Standing constraint on
chat and every file we author: the facts stay sharp; choose the plain word.

| when you mean | write |
|---|---|
| a link, process or session ending | ends, stops responding, drops, is no longer up |
| an image that cannot be restored over the radio | not restorable over the radio, needs SWD recovery |
| a loop that repeats without bound | repeating, retrying, an unbounded loop of |
| data overwritten or left in a bad state | overwrite, leave inconsistent, clear |
| a defect, or code that gets past a check | defect, workaround, unchecked input, appended code |
| the bytes a message carries | message body, the field's bytes (`payload` where it names a code field) |
| a fault at run time | fault, ends early, stops |
| something unresponsive or not returning | unresponsive, stalled, not returning |

Describe what the code does, keep severity in the grading (`CLAIMS.md` grades, "verified / inferred /
unverified") rather than in adjectives, and where an established hazard has a real name use it once
and explain it plainly.
