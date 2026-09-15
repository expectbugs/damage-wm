# FIRMWARE.md — the Damage firmware contract

**Context for the reader.** This is the interface between Damage (the phone or PC compositor) and
the Damage build of the G2 custom firmware (`~/damage-cfw`, a fork of the published `g2flash`
firmware for Adam's own glasses). It describes messages, data formats and behaviour in words and
integer arithmetic. Two implementations follow it independently: the fork (C) and Damage's
simulator (`core/.../sim/GlassFirmwareSim.kt`, Kotlin, written from this text and never from the
C — `CLAUDE.md`, clean room). A conformance-vector set proves both agree, on the host and on the
glasses. Plain wording throughout.

**Status 2026-09-15:** v1 is the installed base (g2flash `a5d1c31`), pointed at in §2. §0 and §3 (the Phase 1
extension: DamageCaps, telemetry, flags, the presented notify, cache-keep, the self-test) are **installed on Adam's
pair since 2026-09-15** (fork pin `c5e4f8b7…`, `HANDOFF.md` §55); the boot count stays withdrawn (§11). §9's vectors
pass on the host C, the simulator and the glasses. **§4 is drafted for Phase 2** (`HANDOFF.md` §57, its decisions
open); §5–§8 are the decided shape only. **Only the RIGHT lens can send** (`CLAIMS.md`): every reply and notify is
RIGHT's; the left lens's state is inferred from the same writes. CACHE_KEEP stays unarmed (the atlas skip is the
phone's bounded one, §3). A section marked *draft* may change until its phase's test stop passes; after that it
changes only with a contract-version bump.

---

## 0. Versioning and detection

- The installed firmware advertises `EVENCFW/16 …` in settings field 100. Damage keeps requiring
  `SettingsMsg.REQUIRED_CAPS` from it (`img640 directfb fbguard imgz rle`).
- **v2 (draft, Phase 1; shape fixed 2026-09-13):** every sid-0x09 READ response carries field **110
  `DamageCaps`**, a nested message appended after upstream's fields 100 and 104:
  `{ 1: string "DMG", 2: uint32 contract, 3: uint32 features }` — e.g. `0a 03 44 4d 47 10 01 18 00`
  (contract 1, no features; the Phase 1 candidate ends `18 1f`).
  Damage requires the contract version it was built for and reads `features` for what is present
  (bit 0 telemetry, bit 1 flags, bit 2 the presented notify, bit 3 cache-keep, bit 4 the self-test;
  later bits are added with their features). A response without
  field 110 is upstream g2flash. Nothing is ever gated on a token that could be dropped for space
  (the `img576` lesson): the field is a few bytes and has its own number.
- Contract versions are integers. A bump means an existing op changed shape or meaning; adding an
  op or a flag bit does not bump it.

## 1. Design rules (bind every v2 op)

1. **Integer arithmetic only.** No floating point anywhere in the contract: easing tables are
   integers, scaling is fixed-point with a stated rounding rule, coordinates are signed 16-bit.
2. **Validate everything, then write.** An op checks its whole message before it changes any byte
   of the shadow, a layer or the cache. A refused op leaves the previous state and records a status
   code the telemetry op returns as `last status`.
3. **Per-lens forms.** Any op that places pixels has a per-lens variant (two x positions under the
   high bit of the mode byte, as modes 3 and 9 do today). Damage's depth ladder is horizontal only.
4. **Nothing presents unless asked.** Ops inside a mode-8 batch mutate; the batch presents once.
   Programs present once per tick.
5. **Flags.** Every v2 behaviour is off until the phone arms it for the session; all flags clear on
   lease lapse (the persisted offline-home flag excepted, Phase 7).
6. **Hold-back.** Uptime is readable (and a boot count once F1.2 sources one); the phone never
   re-arms a feature within N seconds of a reset that followed its arming (N from `FORK.md` M0.5).
7. **Both lenses run the same code.** A candidate is flashed to both arms before use.
8. **Budgets are declared.** Cache size, scratch size, program table size and tick rate are
   numbers in this file once measured, and lint checks Damage against them.

## 2. v1 — the installed contract (pointer only)

Modes 3/5/6/7/8/9/10/11/12/13/14/15, the lenses-differ high bit, mode-3 quantization, the fid ring,
the texture cache, the options byte, the lease (sid 0x09 field 101), the capability string, events
9/10: `overview.md` §4, memory `g2-cfw-mode-table`, encoders `core/.../wire/CfwModes.kt` and
`TextureCache.kt`, model `GlassFirmwareSim.kt`. Facts verified this session and recorded in
`CLAIMS.md`: the success ack for an image message is sent before the decode/present step is
queued; the panel-refresh queue carries a rect; the stock display-position offsets live in the copy
the CFW replaces.

## 3. v2 telemetry, flags and self-test (Phase 1) — *draft*

- **Telemetry request** (sid 0x09 control field, new op): reply carries heap free per arena
  (13/20/27, KiB), uptime (the OS tick `FW_MS_TICK`: 1.024 per wall-clock ms, measured 2026-09-15 — the 90,000-tick
  lease is 87.9 s), flags in force, the status register, the last frame's worker,
  copy and (F1.3) panel-transfer microseconds, the direct-present count, the cache's generation, size
  and (on CACHE_INFO, F1.5) CRC, the self-test's state, panel type, and a
  boot count once it has a source. Sources (read 2026-09-13/14, `CLAIMS.md`): panel type = the
  active operations record at RAM `0x20074530` (`0x0070AFE4` A6N-G, `0x0070B024` JBD4010); the
  copy time is today's `last_present_us`; the transfer time is new — every present is a full
  153,602-byte panel transfer on both drivers, run by the display task after the frame copy, so its
  stamp wraps the refresh call (`0x00473CE4`); the boot count — stock keeps `kvbooCount` only in the
  KV store (each start reads it into a stack temporary through the KV get `FUN_0054116E`, adds one
  and writes it back through `FUN_005411F2`; no RAM word holds it), so the record omits field 13
  until a cached read through that API has a checked precedent from the settings context.
  The heap figures come from the overlay's own walk (bounded, every size word range-checked
  before it is read; an arena that does not validate is omitted).
- **Presented notify** (flag bit 0 PRESENTED, F1.3): after the panel transfer of a Damage frame,
  `(sequence, worker_us, copy_us, transfer_us, lens)` to the phone as field 113; from RIGHT only —
  the left lens cannot send (its stock senders refuse; `CLAIMS.md`), so "from LEFT if its notify
  path works" is answered: there is no such path. The stamp wraps the display task's refresh call
  (`0x00473CE4`), the one new patch site of the candidate; every stock refresh passes through it
  unchanged. The worker figure may lag one frame (the display task can run before the worker stores
  its time); the telemetry record's field 5 is exact after the fact. A direct copy whose refresh
  the display task skips (the panel off) is counted and its transfer is timed by the first refresh
  after the panel is back, which sends the preserved frame; a stock copy in between clears the
  mark, so a stock refresh is never stamped or reported as a Damage frame's (2026-09-14 review).
- **Cache-keep** (flag bit 1 CACHE_KEEP, F1.5): the texture cache is released at four points — a lapse noticed,
  FB_RELEASE, a fresh acquire after a lapse, mode 11. Under the flag, read at the moment the flags clear and
  **latched** for the fresh acquire that follows, the cache is kept through both; the latch is spent by that
  acquire. Mode 11 frees the cache regardless. Fields 17 (generation: mode-12 writes since boot), 18 (size, when
  allocated) and 19 (CRC-32, zlib polynomial, over the whole cache; CACHE_INFO only) let the phone tell whether
  what it uploaded is still there — on RIGHT only. **Adam's ruling (`HANDOFF.md` §51.8; built §54):** the phone's
  atlas skip is the bounded one — after a rebuild that completed inside the lease's remaining time on both arms,
  no flag; CACHE_KEEP stays **unarmed** until LEFT's cache can be verified (an inter-lens report, Phase 4).
- **Flag op:** arm / disarm by bit; a reply echoes the flags in force.
- **Wire shapes (draft, fixed 2026-09-13):**
  - *Request* (phone → each arm): sid 0x09 `G2SettingPackage{ 1: commandId 1, 2: magic 0, 112: body }`,
    body = 6 bytes `['D','M', version 1, op, argLo, argHi]` (the shape of upstream's field-101 lease
    control). Ops: **1 TELEMETRY** (arg = a request id the reply echoes) · **2 FLAGS_SET** (arg = the
    complete flag set wanted, low 16 bits; bit 0 PRESENTED, bit 1 CACHE_KEEP, bit 15 PROBE) · **3
    FLAGS_CLEAR** (arg ignored) · **4 CACHE_INFO** (arg = a request id; the record adds field 19). Every op
    replies with the telemetry record; an unknown op replies with status 1. A body of the wrong length,
    marker or version gets no reply.
  - *Reply* (RIGHT only — the left lens runs the op and sends nothing): sid 0x09 `G2SettingPackage{
    1: commandId 3, 2: magic 0, 111: DamageTelemetry }`, fields in this order, a field omitted when
    its value is not known: `1 request id · 2 uptime (OS ticks; 1.024 per ms, measured 2026-09-15) · 3 flags in force · 4 the status register ·
    5 last worker µs · 6 last copy µs · 7/8/9 free KiB in arenas 13/20/27 · 10 the active panel record
    address · 11 sticky diagnostics (bit 0 reorder, 1 skip, 2 dup, 3 snapshot overflow, 4 allocation) ·
    12 lease ms left · 13 boot count (not sent by the Phase 1 build, above) · 14 lens (1 right, 2 left) ·
    15 the last Damage frame's panel-transfer µs · 16 direct presents since boot · 17 cache generation ·
    18 cache size in bytes (when allocated) · 19 cache CRC-32 (CACHE_INFO, when allocated) · 20
    self-test steps since the last begin · 21 the last step refused (0/1) and 22 the scratch CRC-32
    after it (both after a step)` — all uint32 varints. **The status register (field 4)** holds the
    status recorded by the last op that records one: FLAGS_SET (0, or 2 for a bit this build lacks),
    FLAGS_CLEAR (0), a malformed or unknown request (1 — recorded even when, for a bad body, nothing is
    answered). TELEMETRY and CACHE_INFO record nothing, so a refusal the phone did not see is still
    readable afterwards (§1.2); a lease lapse clears the flags but not the register.
  - *Presented notify* (RIGHT only): sid 0x09 `G2SettingPackage{ 1: commandId 3, 2: magic 0, 113:
    DamagePresented }` with `1 sequence · 2 worker µs · 3 copy µs · 4 transfer µs · 5 lens`, one per
    panel transfer of a Damage frame while PRESENTED is armed.
  - *Flags:* a FLAGS_SET naming a bit this build does not implement changes nothing and sets last
    status 2 (unsupported); bit 15 PROBE has no behaviour and exists so arming, the echo and the
    clear-on-lapse can be proven on glass before any feature relies on them. Flags clear on lease
    expiry, FB_RELEASE, a fresh acquire and mode 11 — the texture cache's release points (where
    CACHE_KEEP is read before the clear) — every time, whether or not a lapse was settled before
    the release (2026-09-14 second review: the C and the model both let a FLAGS_SET taken after a
    settled lapse survive the FB_RELEASE). A FLAGS_SET is taken whenever it arrives, with a lease
    held or not; one taken with no lease is in force until the next release point (both sides do
    this). **Ruled 2026-09-15 (Adam): refused with status 3 (no lease) from Phase 2's candidate; the
    installed build takes it until then.**
  - *Status codes:* 0 ok · 1 malformed request · 2 unsupported flag.
- **Self-test (image mode 16):** rides the image lane, so the unchanged receive path delivers it and
  both lenses run it. `[16][0]` **begin** — the lease must be held; a scratch shadow (the packed
  640×480 panel, 153,600 B from heap 13) is allocated and zeroed, the step count, the last result and
  the self-test's own frame-order diagnostics reset; it runs under the same active mark a step does,
  so a release point reached from another task meanwhile frees the scratch after it, and that begin
  is refused (2026-09-14 second review). `[16][1][message]` **step** — the message runs
  through the same dispatcher as live traffic against the scratch shadow with nothing presented and
  the self-test's fid ring and sticky flags swapped in for the step; it may be any shadow message a
  batch could carry (3/6/8/9/13/14/15, with mode 8's own rules inside a batch; 13/14 read the live
  texture cache, 15 the built-in font); a cache write or any non-drawing mode is refused, and so is
  a mode-3/6 message too short for its own header, alone or inside a batch — the normal path's BMP
  fallback is closed while a step runs, so nothing a step carries can reach the live frame or the
  stock loader (2026-09-14 review). After the
  step the scratch's CRC-32 (zlib polynomial, over the packed rows), the count and whether the message
  was refused land in telemetry fields 20–22. A step needs the lease (the check settles a lapse, which
  frees the scratch) and a begin; one that cannot run is refused and not counted. `[16][2]` **end**
  frees the scratch; so does every lease release point. The phone drives a vector's messages as
  steps and compares RIGHT's fields 20–22 with the vector's expectations (`tools/glassdrive.py
  selftest:`); LEFT runs the same steps and, by the senders' lens rule, cannot report — its
  verification waits for an inter-lens report (Phase 4, R0.1). The lease and cache vectors have no
  self-test form (a tick cannot be set on the glasses; a live cache write is refused).
- **Status codes:** one byte; the table grows with each op that can refuse (above).

## 4. v2 drawing ops (Phase 2) — *draft for Adam's refinery, 2026-09-15*

Design pass `HANDOFF.md` §57. Every op below rides the image lane (both lenses run it), needs the lease, needs
flag bit 2 **DRAW2** armed, and inside a mode-8 batch mutates without presenting. The mode byte's high bit means
"lenses differ" exactly as modes 3 and 9: the left lens takes the first rect or x, the right the second, one
payload. v1 modes 3–15 stay untouched (D8: the pipeline still diffs against upstream). Numbers are little-endian;
a rect is `l u16 · t u16 · w u16 · h u16` in pixels, unquantized, inside the panel; a per-lens pair has one size.
Cache offsets are **u16 in 4-byte units** (`off4`, reach 256 KiB); the phone keeps records 4-byte aligned.

| mode | payload after the mode byte | semantics |
|---|---|---|
| **17** image draw (`0x91` per-lens) | `off4 u16 · x s16 (or xL s16 · xR s16) · y s16 · options u8` | draws a **v2 image record** at (x, y); anything outside the panel or the clip is dropped, negative x and y allowed — a progressive blit or a scroll inside a tall record is a clip plus an offset; options as v1 (top nibble, bit 4 transparent, bit 5 inverse) |
| **18** string draw (`0x92`) | `font off4 u16 · x s16 (or xL · xR) · y s16 · options u8 · len u8 · bytes` | mode 14 with the **224-entry table** (codes 32..255, Latin-1, entry `code − 32`); glyphs are v1 records, advance = glyph width; bytes 1..31 = x adjust `b − 11` (the phone computes pair kerning and emits adjusts), 0 refused; the whole string validated before a pixel |
| **19** cache write v2 | `{ off4 u16 · len u16 · data }…` | mode 12 with `off4` offsets over the session's cache size (op 5); the whole list validated first; bumps the generation |
| **20** clip (`0x94`) | `rect` (or `rectL · rectR`) | only inside a batch: the ops after it in the same batch draw inside the clip only; the batch's end resets it (the clip is batch context on the worker's stack, so it cannot leak); outside a batch refused |
| **21** fill (`0x95`) | `rect` (or pair) `· level u8` | nibble-exact fill 0..15, no fid, no zlib — the strip after a copy, a popover's hole, a background |
| **22** LUT over rect (`0x96`) | `rect` (or pair) `· lut 8 B` (16 packed nibbles, entry i in nibble i) | every pixel p becomes lut[p]: dim, brighten, invert in place — the dim behind a deck that `POPOVER.md` verdict 3 priced at +0.5 s is ~20 B in the deck's own batch |
| **23** save-under (`0x97`) | `sub u8 · slot u8 · rect` (or pair) | sub 0 **capture** copies the shadow's rect into the slot (each lens keeps its own scratch by construction); sub 1 **restore** writes it back at the captured rect; sub 2 **free**; the pool is lazily allocated from arena 13 and freed at every release point |
| **24** present hint | `y0 u16 · y1 u16` | inside a batch: the batch's present transfers rows y0..y1 through the JBD4010's per-row partial entry (`+0x2C`) instead of the full async refresh (F1.7); on an A6N-G pair or bad rows the full refresh runs; the F1.3 stamp times whichever ran, so the phone A/Bs it per flush |

**v2 image record:** `[w u16][h u16][RLE of w·h pixels]`, w 1..640, h 1..2048, the v1 token format, no row pad,
validated at draw. A glyph stays a v1 record (`[w u8][h u8]…`). **Font table v2:** 224 × `off4 u16` = 448 B.

**Control ops (sid 0x09, §3's body):** **5 CACHE_SIZE** (arg = KiB ≤ the budget) sets the session's cache size
before its first write, status 4 once allocated, status 5 over budget; field 18 reports it. **FLAGS_SET with no
lease → status 3** (Adam's ruling). Status codes: 0 ok · 1 malformed · 2 unsupported flag · 3 no lease · 4 cache
allocated · 5 over budget. **Contract version 2** (FLAGS_SET's meaning changed); DamageCaps features bit 5 = v2 drawing.

**Refusals become readable (§1.2 for the image lane):** the dispatcher records every image-lane refusal, v1 modes
included, as telemetry fields **23** mode byte · **24** reason · **25** the copy sequence at the time. Reasons: 1 length ·
2 out of bounds (rect, clip, x/y) · 3 no lease · 4 no keyframe · 5 bad record or offset · 6 bad code (string) · 7 scratch
(slot, pool full, empty restore) · 8 unknown mode · 9 DRAW2 not armed · 10 not in a batch. A duplicate fid stays a
silent skip (v1). The ack still precedes the decode, so the shell reads them at its next telemetry read and journals a
`glass` refusal note. The DWT µs figures are calibrated against one OS tick and read ~2.4 % low (§3's tick note).

**Budget (arena 13, measured 2026-09-15: 322 KiB free with the 64 KiB cache and the shadow up):** A cache 160 KiB +
save-under 48 KiB → 178 KiB free in daily use, the self-test's 150 KiB scratch fits with both allocated; B 192 + 48 →
146 free, the self-test fits only if `begin` frees the pool; C 256 + 32 → 98 free, the self-test cannot run with the
cache up. Sizing input: a Reader page as a v2 record ≈ 22 KB raw RLE (modeled from the 1× render), two resident pages +
nine fonts (~50 KB) + icons (16 KB) ≈ 110 KB; a 640×150 band under a deck is 48,000 B. Phases 3 and 7 re-budget
from the same 322 KiB.

**The phone side (Phase 2, Damage):** `CfwModes`/`TextureCache` v2 encoders and lint (rect, clip and x/y bounds,
per-lens pairs one size and one y, 4-byte alignment, the 224 table, the cache and pool budgets); `GlassFirmwareSim` v2
and the vectors below; `DamageMsg` fields 23–25, status 3–5, op 5; the compositor's cached rect becomes base delta
(when needed) + per-lens draws — no widening, no `CopyPair`, no per-lens proof retry, so the `edge` and
context-caused `proof` misses go; fills replace post-copy strips (no fid); kerning on (Android measures pairs; AWT has
none, so the desktop proves with zero kerns); Reader stages the next and previous page as v2 records off the gesture
path and turns a page with clip + draw; back-to-Main and the height switch ship as fill + draws instead of a mode-6
keyframe; `LensOracleTest`/`Round6Test`/`Round7Test`/`Review20260903Test` extended to per-lens draws;
`journal_report.py` prices first-flush bytes per gesture class against §57's baseline.

**Vectors (v2 set, expectations written by the C):** `v2-perlens` (17/18 with two x's, a negative x) · `v2-image16`
(300×300 and 640×600 records drawn clipped) · `v2-clip` (clip, draws, reset at batch end, refused outside a batch) ·
`v2-fill` · `v2-lut` (dim, invert, brighten) · `v2-saveunder` (capture, draw over, restore, free, per-lens, refusals) ·
`v2-font224` (Latin-1 codes, adjusts, a refused 0) · `v2-cachesize` (op 5 before and after allocation) · `v2-refusals`
(each reason in fields 23–25) · `v2-flags` (DRAW2 unarmed refused; FLAGS_SET with no lease → status 3) · `v2-hint` (host:
a JBD4010 ops record in the shim; glass: the stamp). The self-test form runs every drawing vector.

**The link (in Phase 2's candidate — Adam, 2026-09-15, trusting upstream's public release; `HANDOFF.md` §58):**
three in-place edits ported from g2flash `c63710c`: the startup "Set Local Feature" command's byte 1 `0x7c` → `0x7d`
(link-layer feature bit 8, LE 2M PHY; our site `0x004B4C92`), both fast profile records to min = max = 6 (7.5 ms),
latency 0 (`0x00784EB4`, `0x00784EC4`), and the 0xA4 slow request bound to the fast record (pool word `0x004786C8` →
`0x00784EB0`). DamageCaps features bit 6 says so; the phone then requests 2M at connect and journals the grant and
the parameters. Not a wire op. Measured by ms/KB, a capture (`research/perevent.py`), the battery and the earbud's
A2DP margin; upstream measured ~41 KiB/s on his phone.

## 5. v2 motion programs (Phase 3) — *draft*

- **Tick:** a fixed period in ms, chosen from the measured panel-transfer time (a present sends the whole
  panel on both drivers — `CLAIMS.md` 2026-09-13; 30–60 Hz if that allows). A program
  advances one step per tick; each step mutates the shadow and presents once.
- **Program:** an id, a duration in ticks, a list of tracks, and a **declared end state** (the
  ops that, applied to the pre-program shadow, give the post-program shadow — the phone's model
  uses exactly these).
- **Track verbs (draft):** move rect per lens; fill rect; cached blit with an options ramp (LUT top
  nibble per step); vertical-scale blit (fixed-point 16.16 step, nearest row); progressive blit with
  an offset (slide-in of a cached page); wait.
- **Easing:** integer tables of per-step displacements (the sum equals the total move); a few named
  tables live on the glasses (linear, ease-out, bounce); the phone may upload its own.
- **Control:** upload program (chunked) · play (id, parameters) · stop · retarget (id, new
  parameters; the remaining steps re-aim) · completion event to the phone (id, sequence).
- **Lens start:** a play message reaches both lenses through the existing cross-lens deferred path
  and starts on the tick that follows on each lens.
- **Region guard:** while a program runs, a phone flush touching its rect is held until the program
  ends, then applied; the phone's scheduler avoids sending one.

## 6. v2 local input and bindings (Phase 4) — *draft*

- **Binding table:** entries of (event type, source, parameter) → (program id, parameter mapping);
  armed by the phone; cleared on lease lapse.
- **Behaviour:** a bound event runs its program at once on both lenses (through stock's input
  mirror) and reports an **applied-state event** to the phone: event, sequence number, the
  program's end-state parameter (viewport index, edge hit). Unbound events reach the phone as today.
- **Where the hook sits (R0.1, read 2026-09-15 — `CLAIMS.md`, `research/fork-reads-2026-09-13.md`):**
  every input event reaches a lens's display thread only through the sync framework's listeners
  (`FUN_004445A4`'s two callers), already de-bounced and cross-device locked by RIGHT's input manager, as
  `{u16 devType, u32 eventId, u32 value}` — so a hook on the display thread's `inputEventDataHandler`
  (`FUN_00442D86`, the function `gesture_fwd.c` already patches) sees the same event on both lenses, and
  the binding table needs no mirror of its own. The ids behind the slides and the ring's swipes are still
  to be read (the touch processor, the ring service).
- **Reconciliation:** the phone applies the same program in its model when the event arrives;
  sequence numbers order local and phone-started changes; a mismatch is a `divergence` note and a
  keyframe, never silent.
- **Staged content:** the phone keeps N rows or pages ahead as cached images; at a staged edge
  before the refill lands the program stops at the edge (no bounce, which means end of content).

## 7. v2 firmware-owned chrome (Phase 5) — *draft*

Rects the phone declares firmware-owned (the status bar's clock, battery and link cells; the
silent-mode clock): the glasses draw them after the frame copy from cached fonts at the Global depth
the phone sets; they are outside the shadow and the phone never paints them. RTC and fuel-gauge
sources are stock functions (`FORK.md` R0.5).

## 8. v2 offline home (Phase 7) — *draft*

Placeholder: display ownership outside the EvenHub page, local input, the persisted flag, saved
content, the stock override gesture, stock fallback on any failure. Written after the Phase 7 research.

## 9. Conformance vectors (Phase 1)

- **Location:** `damagewm/firmware/vectors/*.json` (data, no code). Inputs are written by
  `firmware/make_vectors.py` (Damage, clean-room, from the documented message formats).
- **Shape (as built 2026-09-13):** `{ "name", "contract": <version>, "start": "zero", "steps":
  [ { "ops": [ {"tick": ms} | {"lease": "acquire"|"release"} | {"msg": "<hex>"} … ],
  "expect": { "L": "<crc32 hex>", "R": "<crc32 hex>", "rc": { "L": [..], "R": [..] } } } ] }`. A
  `msg` is one completed image message as the deferred handler receives it; `rc` lists each
  message's return (0 accepted, −1 refused) in order; `tick` sets the firmware millisecond clock
  (the lease deadlines run on it). Later phases add ops (program upload/play, self-test) and
  `"scratch"` expectations.
- **CRC:** CRC-32, zlib polynomial `0xEDB88320`, over the packed 4bpp shadow rows in order (320
  bytes per row, 480 rows), per lens; scratch and layers the same way over their own bytes.
- **Who runs them:** the fork's host build (`~/damage-cfw/host/run_vectors.py`: the unchanged patch
  sources compiled for 32-bit x86 with the firmware's addresses mapped; `--write` fills the
  expectations), the Kotlin simulator (`ConformanceVectorTest` in `core`), and — from Phase 1's
  flash — the glasses through the self-test (mode 16). All three must agree before a flash is called
  good. For v1 (the installed firmware) the C wrote the expectations; a disagreement is a finding about
  the simulator or the docs, never a reason to edit an expectation by hand.
- **The self-test form (2026-09-14):** a drawing vector (every v1 vector but `v1-lease` and `v1-cache`)
  also runs as `[16][0]`, then each step's messages as `[16][1][message]`; the scratch CRC after each
  step must equal the step's `L`/`R` expectation and the refusal its last `rc`. The host C runs it
  (`host/run_self_test.py`), the simulator runs it (`ConformanceVectorTest`, the self-test form), and
  the glasses run it through `tools/glassdrive.py selftest:FILE` — RIGHT reported, LEFT blind.
- **v1 set (2026-09-13):** 7 vectors, 35 steps — keyframes, mono and stereo deltas at the panel's
  edges, overlapping and odd-coordinate copies, a batch, refusals (out of bounds, a repeated fid, a
  batch with a non-shadow sub-message that has already changed the shadow), the texture cache
  (options, clipping, an x-adjust byte, a refused string), the lease (renewal keeps the cache, a
  lapse refuses and frees it). **The simulator matches the C on every step, both lenses.**

## 10. Lifecycle

Session start: capability read → flags armed one at a time (hold-back rule: the transport's
`armFeatures` reads RIGHT's uptime first and compares it with what the last reading plus the phone
time since predicts — a shortfall past the slack is a reset; a reset within `HOLD_BACK_MS` of the
last arming disarms the wanted set, journals it and raises a `holdback` fault; a bit the build
refuses as unsupported is dropped from the wish with a fault and the rest are still armed) → self-test when a new image is flashed →
normal traffic. Lease lapse: flags clear, programs stop, bindings clear, the cache is kept if
`CACHE_KEEP` was armed (its generation and CRC say whether it is still good; the latch carries the
decision to the fresh acquire). Reset: uptime restarts (a boot count once it has a source); the
phone's keeper applies the hold-back rule before re-arming.

## 11. Change log

- 2026-09-12 — skeleton written with `FORK.md`.
- 2026-09-13 — §3: telemetry sources named from the Phase 0 reads (boot count, panel type, the
  panel-transfer stamp); the presented notify carries the transfer time. §9: the vector shape as
  built, the two runners, the v1 set (the simulator matches the C). §0/§3: the DamageCaps field and
  the control/telemetry wire shapes fixed for Phase 1.
- 2026-09-14 — the review of the Phase 0/1 work (`HANDOFF.md` §50). §3 field 4 is the status
  register §1.2 already required (TELEMETRY records nothing; a malformed body records 1 without a
  reply); the C, the simulator and both test sets follow (fork pin `f9211ea2…`). Field 13 (boot
  count) withdrawn from the Phase 1 build: stock keeps the counter only in the KV store, and the RAM
  word openCFW names is referenced by nothing in the image. The record carries the last frame's
  timings, not the last N.
- 2026-09-14 (evening) — Phase 1's candidate built on both sides (`HANDOFF.md` §51). §3: the transfer
  stamp and the presented notify (field 113, flag bit 0), cache-keep (flag bit 1, the latch, fields
  17–19, op 4 CACHE_INFO), the self-test as image mode 16 (begin / step / end, fields 20–22; replaces
  the "uploaded in chunks like cache writes" sketch — the image lane already delivers to both lenses
  and needs no upload buffer); §0 features `18 1f`. **The senders' lens rule** read at instruction
  level: only RIGHT can answer or notify, so LEFT is blind everywhere a reply was hoped for; Adam chose
  the bounded skip for the atlas (no flag; CACHE_KEEP unarmed until LEFT can be verified). §9: the
  self-test form of the vectors and its three runners. §10: the keeper's arm / hold-back protocol as built.
- 2026-09-14 (late evening) — the review of the evening's work (`HANDOFF.md` §52). §3: a self-test step
  carrying a mode-3/6 message shorter than its header is refused and counted (the C fell through to the
  BMP loader; fixed, fork pin `70e47938…`); the F1.3 mark is cleared by a stock copy, so a stock refresh is
  never reported as a Damage frame's transfer. §10: the keeper's reset detection compares the uptime read
  with the one the elapsed phone time predicts (the two readings alone missed a reset after a short
  uptime — the post-flash case); a bit the build refuses as unsupported is dropped from the wish and the
  bits above it are still armed. No wire shape changed.
- 2026-09-14 (night) — the second review (`HANDOFF.md` §53). §3: the flags clear at every release point
  whether or not a lapse was settled before it (the C's `damage_lease_ended` returned early once settled and
  the model's `leaseEnded` did the same, so a FLAGS_SET taken after a settled lapse survived an FB_RELEASE;
  the model also cleared the flags on a lease check that had no lease, which the C never did — fixed on
  both sides, fork pin `c5e4f8b7…`); a FLAGS_SET with no lease held is taken and in force until the next
  release point on both sides, refusing it instead left open for Adam; a begin runs under the self-test's
  active mark. No wire shape changed.
- 2026-09-15, 04:38 — **the Phase 1 candidate (pin `c5e4f8b7…`) is the installed firmware** (`HANDOFF.md` §55): §0's
  DamageCaps answered on glass (`features 0x1f`), §3's telemetry, flags and self-test all exercised (21 vector
  steps equal on the glasses), the panel JBD4010, a present's transfer 1.2–6.6 ms. §2's "installed contract" is
  now v1 plus §3; CACHE_KEEP unarmed.
- 2026-09-15 — the bounded atlas skip built on the Damage side (`HANDOFF.md` §54; §3's F1.5 paragraph):
  a per-arm lease log in the transport, the decision at the rebuild's acquire, the shell's kept path.
  No firmware change, no wire shape changed; CACHE_KEEP stays unarmed.
- 2026-09-15 (noon) — Adam's ruling: a FLAGS_SET with no lease is refused (status 3) from Phase 2's candidate
  (`HANDOFF.md` §56); §3's uptime is a 1.024-per-ms tick (annotated). No wire shape changed yet.
- 2026-09-15 (afternoon) — §4 drafted for the Phase 2 refinery (`HANDOFF.md` §57): modes 17–24, the v2 image record,
  the 224 table, op 5 CACHE_SIZE, status 3–5, refusal fields 23–25, the budget options, the v2 vectors. Contract 2.
- 2026-09-15 (afternoon, 2) — Adam's ruling: upstream's three link edits join Phase 2's candidate (§4, the link
  paragraph; `HANDOFF.md` §58); the status head and §3's cache-keep paragraph trimmed to the contract.
