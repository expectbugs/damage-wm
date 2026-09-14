# FIRMWARE.md — the Damage firmware contract

**Context for the reader.** This is the interface between Damage (the phone or PC compositor) and
the Damage build of the G2 custom firmware (`~/damage-cfw`, a fork of the published `g2flash`
firmware for Adam's own glasses). It describes messages, data formats and behaviour in words and
integer arithmetic. Two implementations follow it independently: the fork (C) and Damage's
simulator (`core/.../sim/GlassFirmwareSim.kt`, Kotlin, written from this text and never from the
C — `CLAUDE.md`, clean room). A conformance-vector set proves both agree, on the host and on the
glasses. Plain wording throughout.

**Status 2026-09-12: skeleton.** v1 is the installed contract (g2flash `a5d1c31`) and is only
pointed at. v2 sections carry the decided shape and are filled in phase by phase (`FORK.md` §5).
A section marked *draft* may change until its phase's test stop passes; after that it changes only
with a contract-version bump.

---

## 0. Versioning and detection

- The installed firmware advertises `EVENCFW/16 …` in settings field 100. Damage keeps requiring
  `SettingsMsg.REQUIRED_CAPS` from it (`img640 directfb fbguard imgz rle`).
- **v2 (draft, Phase 1; shape fixed 2026-09-13):** every sid-0x09 READ response carries field **110
  `DamageCaps`**, a nested message appended after upstream's fields 100 and 104:
  `{ 1: string "DMG", 2: uint32 contract, 3: uint32 features }` — e.g. `0a 03 44 4d 47 10 01 18 00`.
  Damage requires the contract version it was built for and reads `features` for what is present
  (bit 0 telemetry, bit 1 flags; later bits are added with their features). A response without
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
6. **Hold-back.** Boot count and uptime are readable; the phone never re-arms a feature within N
   seconds of a reset that followed its arming (N from `FORK.md` M0.5).
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
  (13/20/27, KiB), uptime (ms tick), boot count, flags in force, last status code, the last N
  frames' worker, copy and panel-transfer microseconds, cache generation and CRC, panel type.
  Sources (read 2026-09-13, `CLAIMS.md`): boot count = the stock KV `kvbooCount`, which every start
  already increments (no new write); panel type = the active operations record at RAM `0x20074530`
  (`0x0070AFE4` A6N-G, `0x0070B024` JBD4010); the copy time is today's `last_present_us`; the
  transfer time is new — every present is a full 153,602-byte panel transfer on both drivers, run by
  the display task after the frame copy, so its stamp wraps the refresh call (`0x00473CE4`).
- **Presented notify** (when enabled): after the panel transfer, `(sequence, worker_us, copy_us,
  transfer_us)` to the phone; from RIGHT; from LEFT too if its notify path is shown to work.
- **Flag op:** arm / disarm by bit; a reply echoes the flags in force.
- **Wire shapes (draft, fixed 2026-09-13):**
  - *Request* (phone → each arm): sid 0x09 `G2SettingPackage{ 1: commandId 1, 2: magic 0, 112: body }`,
    body = 6 bytes `['D','M', version 1, op, argLo, argHi]` (the shape of upstream's field-101 lease
    control). Ops: **1 TELEMETRY** (arg = a request id the reply echoes) · **2 FLAGS_SET** (arg = the
    complete flag set wanted, low 16 bits; bit 15 = PROBE) · **3 FLAGS_CLEAR** (arg ignored). Every op
    replies with the telemetry record; an unknown op replies with status 1. A body of the wrong length,
    marker or version gets no reply.
  - *Reply* (each arm that can send; RIGHT for certain): sid 0x09 `G2SettingPackage{ 1: commandId 3,
    2: magic 0, 111: DamageTelemetry }`, fields in this order, a field omitted when its value is not
    known: `1 request id · 2 uptime ms · 3 flags in force · 4 this op's status · 5 last worker µs · 6 last
    copy µs · 7/8/9 free KiB in arenas 13/20/27 · 10 the active panel record address · 11 sticky
    diagnostics (bit 0 reorder, 1 skip, 2 dup, 3 snapshot overflow, 4 allocation) · 12 lease ms left ·
    13 boot count · 14 lens (1 right, 2 left)` — all uint32 varints.
  - *Flags:* a FLAGS_SET naming a bit this build does not implement changes nothing and sets last
    status 2 (unsupported); bit 15 PROBE has no behaviour and exists so arming, the echo and the
    clear-on-lapse can be proven on glass before any feature relies on them. Flags clear on lease
    expiry, FB_RELEASE, a fresh acquire and mode 11 — the texture cache's release points.
  - *Status codes:* 0 ok · 1 malformed request · 2 unsupported flag.
- **Self-test op:** runs the conformance vectors held on the glasses' side (uploaded by the phone in
  chunks, like cache writes) against scratch memory, never the visible shadow, and replies with one
  CRC per vector step per lens. The phone compares against the simulator.
- **Status codes:** one byte; the table grows with each op that can refuse (above).

## 4. v2 drawing ops (Phase 2) — *draft*

| op | shape (draft) | notes |
|---|---|---|
| cached draw, per-lens | image offset u16 · xL s16 · xR s16 · y s16 · options u8 | the per-lens form of mode 13 |
| cached string, per-lens | font offset u16 · xL s16 · xR s16 · y s16 · options u8 · len u8 · bytes | the per-lens form of mode 14; bytes 1..31 remain x adjusts (kerning) |
| image v2 | `[w:u16][h:u16][4bpp RLE of w*h]` | replaces the u8-dimension image where referenced by v2 ops; v1 ops keep the u8 form |
| clip | l u16 · t u16 · w u16 · h u16 | sets the clip rect for following ops in the batch; reset at batch end |
| fill | l u16 · t u16 · w u16 · h u16 · level u8 (0..15) | unquantized; nibble-exact |
| lut over rect | rect · 16-entry LUT | dim, brighten, invert in place |
| save-under | capture rect → scratch slot u8 · restore slot u8 | for popovers; scratch is a small pool sized in Phase 2 |
| font table v2 | up to 224 entries (codes 32..255), u16 offsets | codes above 127 index the Latin-1 range |
| cache size | a settable size up to the measured budget | generation id increments on every write; CRC-32 (zlib polynomial, over the whole cache) on request |

## 5. v2 motion programs (Phase 3) — *draft*

- **Tick:** a fixed period in ms, chosen from the measured present time (30–60 Hz). A program
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
  flash — the glasses through the self-test op. All three must agree before a flash is called good.
  For v1 (the installed firmware) the C wrote the expectations; a disagreement is a finding about the
  simulator or the docs, never a reason to edit an expectation by hand.
- **v1 set (2026-09-13):** 7 vectors, 35 steps — keyframes, mono and stereo deltas at the panel's
  edges, overlapping and odd-coordinate copies, a batch, refusals (out of bounds, a repeated fid, a
  batch with a non-shadow sub-message that has already changed the shadow), the texture cache
  (options, clipping, an x-adjust byte, a refused string), the lease (renewal keeps the cache, a
  lapse refuses and frees it). **The simulator matches the C on every step, both lenses.**

## 10. Lifecycle

Session start: capability read → flags armed one at a time (hold-back rule) → self-test when a new
image is flashed → normal traffic. Lease lapse: flags clear, programs stop, bindings clear, the
cache is kept if `cache-keep` was armed (its generation and CRC say whether it is still good).
Reset: boot count increments; the phone's keeper applies the hold-back rule before re-arming.

## 11. Change log

- 2026-09-12 — skeleton written with `FORK.md`.
- 2026-09-13 — §3: telemetry sources named from the Phase 0 reads (boot count, panel type, the
  panel-transfer stamp); the presented notify carries the transfer time. §9: the vector shape as
  built, the two runners, the v1 set (the simulator matches the C). §0/§3: the DamageCaps field and
  the control/telemetry wire shapes fixed for Phase 1.
