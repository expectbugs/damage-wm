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
- **v2 (draft, Phase 1):** a second settings field, number ≥ 110 (upstream g2flash uses 100–105),
  `DMG/<contract-version>` followed by a little-endian u32 **feature bitmask**. Damage requires the
  contract version it was built for and reads the bitmask for what is present. Nothing is ever
  gated on a token that could be dropped for space (the `img576` lesson).
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
  frames' worker and present microseconds, cache generation and CRC, panel type.
- **Presented notify** (when enabled): after the frame copy, `(sequence, worker_us, present_us)`
  to the phone; from RIGHT; from LEFT too if its notify path is shown to work.
- **Flag op:** arm / disarm by bit; a reply echoes the flags in force.
- **Self-test op:** runs the conformance vectors held on the glasses' side (uploaded by the phone in
  chunks, like cache writes) against scratch memory, never the visible shadow, and replies with one
  CRC per vector step per lens. The phone compares against the simulator.
- **Status codes:** one byte; `0` ok; the table is written with the first op that can refuse.

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

- **Location:** `damagewm/firmware/vectors/*.json` (data, no code).
- **Shape:** `{ "name", "contract": <version>, "start": <shadow seed or "black">, "steps":
  [ { "ops": [<hex messages>], "tick": <n or 0>, "expect": { "L": "<crc32 hex>", "R": "<crc32 hex>",
  "scratch": [...] } } ] }`.
- **CRC:** CRC-32, zlib polynomial `0xEDB88320`, over the packed 4bpp shadow rows in order (320
  bytes per row, 480 rows), per lens; scratch and layers the same way over their own bytes.
- **Who runs them:** the fork's host test build (x86, stubbed entry points), the Kotlin simulator
  (`core` tests), and the glasses through the self-test op. All three must agree before a flash is
  called good.

## 10. Lifecycle

Session start: capability read → flags armed one at a time (hold-back rule) → self-test when a new
image is flashed → normal traffic. Lease lapse: flags clear, programs stop, bindings clear, the
cache is kept if `cache-keep` was armed (its generation and CRC say whether it is still good).
Reset: boot count increments; the phone's keeper applies the hold-back rule before re-arming.

## 11. Change log

- 2026-09-12 — skeleton written with `FORK.md`.
