# FIRMWARE.md — the Damage firmware contract

**Context for the reader.** This is the interface between Damage (the phone or PC compositor) and
the Damage build of the G2 custom firmware (`~/damage-cfw`, a fork of the published `g2flash`
firmware for Adam's own glasses). It describes messages, data formats and behaviour in words and
integer arithmetic. Two implementations follow it independently: the fork (C) and Damage's
simulator (`core/.../sim/GlassFirmwareSim.kt`, Kotlin, written from this text and never from the
C — `CLAUDE.md`, clean room). A conformance-vector set proves both agree, on the host and on the
glasses. Plain wording throughout.

**Status 2026-11-17:** v1 is g2flash `a5d1c31`'s contract, the base the fork keeps, pointed at in §2. §0 and §3 (the Phase 1
extension: DamageCaps, telemetry, flags, the presented notify, cache-keep, the self-test) are **installed on Adam's
pair since 2026-09-15** (fork pin `c5e4f8b7…`, `HANDOFF.md` §55); the boot count stays withdrawn (§11). **§4 is built
on both sides (Adam's word on `HANDOFF.md` §57's decisions; fork pin `48172b62…` after the fourth review, `HANDOFF.md` §60–§64) and installed on Adam's pair since 2026-09-16 (`HANDOFF.md` §65):** the
host C, the simulator and the v2 vectors agree, and the on-glass self-test ran the same day (`CLAIMS.md`). **§5 is built on
both sides by build C1 (2026-11-15 → 17): the host C, the simulator and the v3 vectors agree; the fork's build
`0dab2154…` is installed on Adam's pair since 2026-11-18 (`HANDOFF.md` §88), with nothing of §5 armed.** §6 is in contract form since 2026-11-13, not yet challenged (the note above §5); §7 and §8
are the decided shape only. **Only the RIGHT lens can send** (`CLAIMS.md`): every reply and notify is
RIGHT's; the left lens's state is inferred from the same writes. CACHE_KEEP stays unarmed (the atlas skip is the
phone's bounded one, §3). A section marked *draft* may change until its phase's test stop passes; after that it
changes only with a contract-version bump.

---

## 0. Versioning and detection

- The installed firmware advertises `EVENCFW/16 …` in settings field 100. Damage keeps requiring
  `SettingsMsg.REQUIRED_CAPS` from it (`img640 directfb fbguard imgz rle`).
- **v2 (Phase 1; shape fixed 2026-09-13, installed 2026-09-15):** every sid-0x09 READ response carries field **110
  `DamageCaps`**, a nested message appended after upstream's fields 100 and 104:
  `{ 1: string "DMG", 2: uint32 contract, 3: uint32 features }` — e.g. `0a 03 44 4d 47 10 01 18 00`
  (contract 1, no features; the Phase 1 candidate ends `18 1f`).
  Damage requires the contract version it was built for and reads `features` for what is present
  (bit 0 telemetry, bit 1 flags, bit 2 the presented notify, bit 3 cache-keep, bit 4 the self-test;
  later bits are added with their features; the C1 candidate reports `0x1ff`, §5.1). A response without
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

**Op 7 and the wear event** (v1, the fork's settings extension; `CLAIMS.md`, `HANDOFF.md` §75.4): field 101
op 7 (WEAR_QUERY) sends the wear event when the stock cached wear state — the word at `0x20074EDC`, read through
the getter at `0x0049EB8E` (three instructions, no side effect) — is 1 or 2, and nothing otherwise
(`settings_ext.c` 267–271); no reply is a legal answer. The event is a notify on sid 0x10, ten bytes
`08 03 10 00 2a 04 08 01 10 <1 worn / 0 not worn>` (EVENT, magic 0, field 5 { event 1 `GLS_WEAR_STATUS`, param }),
from RIGHT only (the senders' lens rule), sent by the stock wear handler's two transitions — which stock reaches
only while byte +0x14 of its live settings record is 1 (V; that the byte is its wear-detection switch: I). Damage
from APK 0.64 (installed 2026-11-12) sends one op 7 to both arms after each session start's lease, never waits
on it, and journals every sid-0x10 frame as a `wear` note; what op 7 answers with the switch off is unread.

## 3. v2 telemetry, flags and self-test (Phase 1) — *installed 2026-09-15 (`HANDOFF.md` §55)*

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
  (`0x00473CE4`), the first new patch site of the candidate; every stock refresh passes through it
  unchanged. **Since 2026-09-15 (the third review) the display task's OTHER refresh call is hooked
  the same way — `0x00473D80`, its type-6 branch, the candidate's second and last new site.** Both
  of that function's copy calls have always been hooked and `display_copy_hook` does not know the
  event type, so a type-6 event that runs while a Damage job is pending takes that frame into the
  framebuffer and latches its hint; with its refresh unhooked, those frames transferred with no
  stamp — fields 15 and 26 kept the PREVIOUS frame's microseconds and path while field 16
  advanced, and no presented notify went out for them — and their hint was dropped. Never a
  display defect either way (every clear of the direct-present mark clears the hint with it), but
  the record the phone prices with was mixing two frames. `FUN_00473C44` is the display task's
  event loop, not a boot-time path.
  The worker figure may lag one frame (the display task can run before the worker stores
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
- **Wire shapes (fixed 2026-09-13):**
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
    address · 11 sticky diagnostics (bit 0 reorder, 1 skip, 2 dup, 3 snapshot overflow, 4 allocation; on a C1 build
    5 a package — 111, 113 or 114 — that did not fit its buffer and was not sent; cleared by mode 7 sub 0 like 0–4) ·
    12 lease ticks left (the same tick as field 2, 1.024 per ms) · 13 boot count (not sent by the Phase 1 build, above) · 14 lens (1 right, 2 left) ·
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
    this). **Ruled 2026-09-15 (Adam): refused with status 3 (no lease) from Phase 2's candidate — the
    installed build since 2026-09-16 (§4).**
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
  verification waits for an inter-lens report (Phase 4, R0.1). A vector that releases the lease has no
  self-test form (a tick cannot be set on the glasses); a step carrying a cache write is refused, so since
  Phase 2 the runners send a vector's cache writes LIVE (§4), and the phone's shell drops its atlas for the
  session at the first such write.
- **Status codes:** one byte; the table grows with each op that can refuse (above).

## 4. v2 drawing ops (Phase 2) — *built 2026-09-15 (`HANDOFF.md` §60), installed 2026-09-16 (§65); T2 open; changes only with a contract bump once it passes*

Design pass `HANDOFF.md` §57, Adam's word on its eleven decisions as recommended. Every op below rides the image
lane (both lenses run it), needs the lease, needs flag bit 2 **DRAW2** armed, and inside a mode-8 batch mutates
without presenting. **The order of an op's checks is part of the contract, since the reason recorded (fields 23–25)
is:** length (1) → not in a batch (10, modes 20 and 24) → the lease (3; the check settles a lapse) → DRAW2 (9) →
the op's own (bounds 2, record 5, code 6, scratch 7, value 12, no memory 11, in the order each row below gives; the
draw ops 17/18/21/22/23 find no shadow buffer (4) before any of it). A per-lens form takes the first rect
or x on LEFT and the second on RIGHT (`FW_SIDE`). The mode byte's high bit means
"lenses differ" exactly as modes 3 and 9: the left lens takes the first rect or x, the right the second, one
payload. v1 modes 3–15 stay untouched (D8: the pipeline still diffs against upstream). Numbers are little-endian;
a rect is `l u16 · t u16 · w u16 · h u16` in pixels, unquantized, inside the panel and not empty (else 2); **a per-lens
pair is checked whole on both lenses** — both rects inside the panel and not empty, the two the same size — before either
lens takes its own, and a pair that fails is refused (2) on both, so the lenses cannot decide one message differently
(the left lens cannot report). On a mode with no per-lens form (19, 24, 23's subs 1 and 2) the high bit is ignored.
Cache offsets are **u16 in 4-byte units** (`off4`, reach 256 KiB); the phone keeps records 4-byte aligned.

| mode | payload after the mode byte | semantics |
|---|---|---|
| **17** image draw (`0x91` per-lens) | `off4 u16 · x s16 (or xL s16 · xR s16) · y s16 · options u8` | draws a **v2 image record** at (x, y); anything outside the panel or the clip is dropped, negative x and y allowed — a progressive blit or a scroll inside a tall record is a clip plus an offset; options as v1 (top nibble, bit 4 transparent, bit 5 inverse) |
| **18** string draw (`0x92`) | `font off4 u16 · x s16 (or xL · xR) · y s16 · options u8 · len u8 · bytes` | mode 14 with the **224-entry table** (codes 32..255, Latin-1, entry `code − 32`); glyphs are v1 records, advance = glyph width; bytes 1..31 = x adjust `b − 11` (the phone computes pair kerning and emits adjusts), 0 refused; the whole string validated before a pixel |
| **19** cache write v2 | `{ off4 u16 · len u16 · data }…` | mode 12 with `off4` offsets over the session's cache size (op 5); the whole list validated first, in this order: every entry's shape inside the message (1), the lease (3), DRAW2 (9), every entry inside the cache (5); a list with no data is then accepted, writes nothing and does NOT bump the generation (the handler returns before the bump — mode 12 the same; read 2026-11-09, `HANDOFF.md` §73); a list with data allocates the cache on its first write (11), is written in order as plain copies — a later entry, or a later message, may overwrite bytes written earlier, which is how a font table is patched in place — and bumps the generation once per message |
| **20** clip (`0x94`) | `rect` (or `rectL · rectR`) | only inside a batch: the v2 draws, fills and LUTs (17/18/21/22) after it in the same batch stay inside it — a later clip replaces it; the batch's end resets it (the clip is batch context on the worker's stack, so it cannot leak); outside a batch refused (10); the v1 ops and mode 23 ignore it |
| **21** fill (`0x95`) | `rect` (or pair) `· level u8` | nibble-exact fill 0..15, no fid, no zlib — the strip after a copy, a popover's hole, a background |
| **22** LUT over rect (`0x96`) | `rect` (or pair) `· lut 8 B` (16 nibbles, entry i in nibble i, the high nibble of a byte first — the packed rows' order) | every pixel p becomes lut[p]: dim, brighten, invert in place — the dim behind a deck that `POPOVER.md` verdict 3 priced at +0.5 s is ~20 B in the deck's own batch |
| **23** save-under (`0x97` for a capture) | sub 0 `[0][slot][rect (or pair)]` · sub 1 `[1][slot]` · sub 2 `[2][slot]` | four slots, 48 KiB between them; sub 0 **capture** copies the shadow's rect into the slot as tight packed rows ((w+1)/2 bytes per row, from arena 13; a capture into a used slot replaces it, the old bytes not counted against the budget); sub 1 **restore** writes it back at the captured rect (an empty slot refused, 7); sub 2 **free** (an empty slot: nothing). The length is sub 0's (11, or 19 under the high bit) or 3 for any other sub (1); then the lease (3), DRAW2 (9), sub > 2 (12), slot ≥ 4 (7), an empty slot on restore (7), the rect or pair (2), the budget (7, a pair priced from its one size), the allocation (11). Every slot is freed at every release point and by mode 11; each lens keeps its own by construction; a self-test step uses the self-test's own slots **and its own 48 KiB pool** (the live slots are not priced against a step's, and a step never frees them) |
| **24** present hint | `y0 u16 · y1 u16` | inside a batch only (10); rows inclusive, 0 ≤ y0 ≤ y1 ≤ 479 (else 2); a later hint replaces it. The batch's present transfers rows y0..y1 through the JBD4010's per-row partial entry (`+0x2C`, called from the Phase 1 refresh hook as `(0, 0, 0, y0, 640, y1)` — a Damage job's own arguments whatever job carries the refresh, since a stock job queues 576×288 and the entry sizes each row from x1; `CLAIMS.md`) instead of the full refresh (F1.7); on any other panel record the full refresh runs, and so it does **whenever the panel does not already show the whole previous frame** — the rule is that a partial refresh only ever adds its own rows. The panel counts as stale, and the next present goes whole, when: the previous Damage frame was never transferred (its refresh skipped with the panel off: its hinted rows are not this frame's); **a message that held the display gate ended without presenting** (a batch refused part-way has already changed the shadow, and a present the display queue would not take leaves the same gap); **stock content reached the framebuffer** (a stock copy, a framebuffer the copy could not use) **or a lease release point ran** (the stock compositor repaints after it); or the diagnostic overlay is drawn into the framebuffer — the frame that carries it goes whole, and so does the first one after it is hidden, whose rows still show it. Field 26 (and the presented notify's field 6) says which ran (0 full, 1 rows) and the F1.3 stamp times it, so the phone A/Bs per flush. **The rows must cover every pixel the batch changed: the panel shows nothing else until the next full refresh** — the simulator's panel keeps only the hinted rows too, so a short hint fails the oracle before it reaches glass |

**v2 image record:** `[w u16][h u16][RLE of w·h pixels]`, w 1..640, h 1..2048, the v1 token format, no row pad,
validated at draw. A glyph stays a v1 record (`[w u8][h u8]…`). **Font table v2:** 224 × `off4 u16` = 448 B.

**Control ops (sid 0x09, §3's body):** **5 CACHE_SIZE** (arg = KiB, 64..160 — modes 12/13/14 bound their records by the
first 64 KiB of whatever the cache is, so a smaller one would put those bounds past its end) sets the size the cache is allocated at
by its first write (mode 12 or 19); status 3 with no lease, 5 below 64 or over 160, 4 once the cache is
allocated (the size stands); field 18 reports the allocated size, field 19's CRC covers all of it; the size asked
for goes at every release point, whether the cache went or CACHE_KEEP kept it (the next session asks again). A
request is only that: the allocation keeps its own size, and every bound reads the allocated one. The v1 window addressed by modes
12/13/14 stays the first 64 KiB; a v2 record or table may lie anywhere in the allocated size. **FLAGS_SET with no
lease → status 3** (Adam's ruling), and so CACHE_SIZE. Status codes: 0 ok · 1 malformed · 2 unsupported flag · 3 no
lease · 4 cache allocated · 5 outside 64..160 KiB. **Contract version 2** (FLAGS_SET's meaning changed); DamageCaps features
bit 5 = the v2 drawing ops, bit 6 = the link edits below (the Phase 2 build answers `18 7f`).

**Refusals become readable (§1.2 for the image lane):** the dispatcher records every image-lane refusal, v1 modes
included, as telemetry fields **23** the mode byte as received · **24** reason · **25** the copy sequence at the time,
sticky until the next refusal or mode 7 sub 0. Reasons: 1 length (a message, entry or sub-message not its shape) ·
2 bounds (a rect, box, clip or row range outside its range, an empty rect, a per-lens pair not one size) · 3 no lease · 4 no shadow buffer · 5 a record,
offset or table outside the cache or malformed (a v1 write past the 64 KiB window too) · 6 a string byte that is
neither an adjust nor a glyph · 7 scratch (a slot out of range, empty on restore, over budget; the self-test's scratch
missing) · 8 a mode with no handler, or one not allowed where it sits (a nested batch, a cache write in a batch, a
non-drawing self-test step) · 9 DRAW2 not armed · 10 a clip or hint outside a batch · 11 an allocation failed · 12
a value outside its range (a fill's level, a sub-op) · 13 a zlib or RLE stream that does not decode to its size (an inflate whose state the heap would not give it reads the same way) · 14 the display was still busy with the previous frame when the gate came free: the message is dropped whole and nothing of it reaches the shadow; on a C1 build also a message that must claim the engine's busy word and cannot — a cache write (12, 19, 25), a mode 26, a live mode 6 over an open slot (§5.11; on a healthy boot it does not happen: the copy hook never blocks while it holds the word; a word left held is §5.9's survival case). The
v1 modes' order as built (upstream's checks, each now recording its reason): mode 12 checks each entry's shape (1) and
the 64 KiB window (5) entry by entry, accepts a list with no data, then the lease (3) and the allocation (11); modes 13,
14 and 15: no shadow (4) → the header's length (1) → the lease (3) → the string's own length (1; 14 and 15) → the table
or font (5) → byte by byte, a code (6) or a glyph record (5) — mode 15 reads every code of the string first and only then
every glyph, so a glyph refusal there follows all of its code refusals; modes 3, 6 and 9 need no lease — 3: length (1; under 3 bytes it is the same check mode 6 fails, before anything else) → the
boxes (2) → a duplicate fid skipped unrecorded → no shadow (4) → the stream (13); 6: under 3 bytes, recorded
as length (1) on the way to the BMP path and before the fid ring is touched → no shadow (4) → the stream (13); 9: length (1) → sizes and bounds (2) → no
shadow (4) — its length check is "at least": a message longer than its one or two rect sets is accepted and the bytes
after them are not read. Mode 16: alone (1) · a begin without the lease (3), whose scratch allocation fails (11) or that a release
point ended (7) · an unknown sub (12) · a step shorter than 3 (1), without the lease (3), without a begin (7), carrying
what a step may not (8), then its message's own reason. Mode 10 (upstream's compass) needs no lease: no argument (1),
then an argument other than 0 (stop) or 1 (start) (12). Mode 8's own checks each record length (1): a batch over
153,718 B (`Geometry.MODE8_MAX`), no count byte, and a sub-message whose 2-byte length is 0 or whose header or bytes run
past the message (the sub-messages before it have already applied); bytes after the last counted sub-message are not
read, and a batch counting 0 sub-messages is accepted and presents. A batch is not all-or-nothing: it records its own structural
refusals (1/8), a refused sub-message ends it and leaves that sub-message's record standing, the sub-messages before
it have already changed the shadow, and nothing presents until the next present; a mode this build has no handler for records 8, then the stock BMP path runs as before. A duplicate
fid stays a silent skip (v1). Field **26** is the last Damage transfer's path (0 full, 1 rows). The ack still precedes
the decode, so the shell reads them at its next telemetry read and journals a `glass` refusal note. The DWT µs figures
are calibrated against one OS tick and read ~2.4 % low (§3's tick note).

**Budget (arena 13, measured 2026-09-15: 322 KiB free with the 64 KiB cache and the shadow up).** The
self-test's OWN 48 KiB save-under pool is priced beside the live one (§4's mode-23 row says each set has
its own), so a build running a step with both pools full wants 48 KiB more than the rows below — a
refusal with reason 11, never a write past anything (2026-09-15, the third review). A cache 160 KiB +
save-under 48 KiB → 178 KiB free in daily use, the self-test's 150 KiB scratch fits with both allocated; B 192 + 48 →
146 free, the self-test fits only if `begin` frees the pool; C 256 + 32 → 98 free, the self-test cannot run with the
cache up. Sizing input: a Reader page as a v2 record ≈ 22 KB raw RLE (modeled from the 1× render), two resident pages +
nine fonts (~50 KB) + icons (16 KB) ≈ 110 KB; a 640×150 band under a deck is 48,000 B. Phases 3 and 7 re-budget
from the same 322 KiB.

**The phone side (built 2026-09-15 evening, `HANDOFF.md` §60; gated on DamageCaps bit 5 with DRAW2 in force):**
the transport's start takes the cache size (op 5, 160 KiB) and arms DRAW2 before the shell paints; the atlas is a
v2 layout over that size (records 4-byte aligned, 224-entry tables with Latin-1 glyphs, icons as v2 records,
mode-19 chunks); **since `HANDOFF.md` §73 (2026-11-09; APK 0.63, installed 2026-11-10 — the table patch itself read on glass that day: `v2-tablepatch`, 11 of 11 on RIGHT) a table names real glyphs only for the characters its face
has drawn, plus the digits, the space and — for running text — the capitals (`CachedText.codesFor`)** — every other entry names one shared tofu record, and the phone never sends a string holding an
unpacked code — **and a face gains glyphs in place**: one mode-19 message (more when the new records exceed a chunk; the table's units ride in the last) appends the new records at the end of the
packed bytes and rewrites the 4-byte units of the table that hold their entries (on contract 1, mode 12 rewrites
the 2-byte entries themselves, at byte offsets); the compositor ships a rect of cached text on a depth plane as one stereo base delta at each
lens's box plus the draws placed per lens by modes 17/18 — no widening, no `CopyPair`, no context retry, so the
`edge` and context-caused `proof` misses go; a black box is a mode-21 fill (no fid); a reseed (the height switch,
the exclusive exit, a font-scale relayout) is a whole-panel fill plus the diff's draws, never a keyframe; a flush
whose changed rows span at most 240 carries a mode-24 hint (the model keeps only the hinted rows on its panel, so
a short hint fails the oracle); kerning is the platform's pair measure as adjust bytes (Android; AWT none); the
`present` records carry the path and `tools/journal_report.py` splits the transfer by it. DRAW2 is the session's, not
the wish's: a hold-back that catches it keeps it off at later starts until a hand-set flag set asks for bit 2 (the
session then draws with v1 shapes over a 64 KiB atlas), a lease that ends drops it from the link state at once and, on a
Damage build, the shell rebuilds the session to arm it again, and an atlas of the other contract is never drawn from
(2026-09-15 review, `HANDOFF.md` §61). **Page staging (built 2026-09-16, `HANDOFF.md` §66; no wire change):** the top
64 KiB of the session's cache (32 until §66.1's read on glass) is a reserve the atlas never lays out over; the focused document's next and previous
strips go there as v2 image records (mode 19, 3,064 B messages, off the gesture path), are proven by field 17 — the
phone counts its acked non-empty writes and a reading must show exactly that many bumps — and a notch then ships
one mode-17 pair under a mode-20 clip (and a clip reset) instead of the strip's pixels.

**Vectors (the v2 set, built; expectations from the C, the simulator equal on every step):** `v2-perlens` (17/18
with two x's, negative x and y) · `v2-image16` (300×300 and 640×600 records across the 64 KiB line in a 160 KiB
cache, clipped four ways, one wholly off the panel) · `v2-clip` (a clip's scope, its end with the batch, refused
outside one, per-lens, past the panel, a later clip winning) · `v2-fill` (odd edges, per-lens, the last cell, a bad
level, past the panel, empty) · `v2-lut` (dim, invert, brighten, per-lens, past the bottom, under a clip) ·
`v2-saveunder` (19 steps: capture, draw over, restore, per-lens, free, every refusal, the budget's edges) · `v2-font224`
(both halves of the table, adjusts −10 and +20, a 0 byte, a zero record, a table past the cache, an empty string) ·
`v2-cachesize` (op 5's statuses, a write and draw past 64 KiB, the v1 window unchanged, the size gone with a fresh
lease) · `v2-refusals` (a reason per step, v1 modes among them, the record sticky) · `v2-flags` (no lease 3, unarmed 9,
armed, disarmed, with PROBE, released, a fresh lease) · `v2-hint` (rows, the whole panel, y0 > y1, past the panel, a
later hint winning; the partial path itself is `test_damage_ext.py`'s, against the shim's JBD4010 and A6N-G records) ·
`v2-edges` (the Phase 2 review, 2026-09-15: transparency tested on the source, records one column and one row too big, a
refused write leaving its good entry unwritten, per-lens pairs refused whole on both lenses, the save-under pool's exact
edge, a refused capture keeping its slot, a restore and a v1 copy ignoring the clip, length before batch, the high bit
ignored on 24/23/19) · `v2-lifecycle` (`[16]` alone, op 5 below 64 KiB, DRAW2 before bounds and records, mode 19's lease
and empty list, the size asked for gone under a latched CACHE_KEEP, the slots freed by a release and by mode 11, mode 7
sub 0 clearing the record) · `v2-tablepatch` (`HANDOFF.md` §73, 11 steps: a 224-entry table whose unpacked entries
all name one tofu record; one message that appends a record and rewrites the unit holding its entry and its
neighbour's; a patch with nothing appended, naming a record already there; the table's last entry, code 255; per-lens
draws; a two-entry message whose last entry leaves the cache refused whole, the table entry before it not applied) ·
`v2-shape` (2026-11-12, the vector pass: 29 steps — each v2 op's length a byte long or a byte short, the per-lens forms
under the high bit, mode 23's capture rect past the panel, empty and a pair of two sizes, mode 24's length before the
batch, a zero glyph record refusing a string before any glyph is drawn, mode 19's whole list checked before a byte is
written) · `v2-window` (the vector pass, 3 steps: mode 14's table at the last offset the v1 window admits in a 128 KiB
cache, and one byte later refused).
A step's expectation now carries `ref` = [mode, reason, sequence, status] beside the CRCs and return codes. The
self-test form runs every drawing vector (v1-cache too): a cache write is sent live, the control ops before the begin,
a vector that releases the lease has none, and the refusal's sequence (the live count) is not compared.

**The link (in Phase 2's candidate — Adam, 2026-09-15, trusting upstream's public release; `HANDOFF.md` §58):**
three in-place edits ported from g2flash `c63710c`: the startup "Set Local Feature" command's byte 1 `0x7c` → `0x7d`
(link-layer feature bit 8, LE 2M PHY; our site `0x004B4C92`), both fast profile records to min = max = 6 (7.5 ms),
latency 0 (`0x00784EB4`, `0x00784EC4`), and the 0xA4 slow request bound to the fast record (pool word `0x004786C8` →
`0x00784EB0`). DamageCaps features bit 6 says so; the phone then requests 2M at connect and journals the grant and
the parameters. Not a wire op. Measured by ms/KB, a capture (`research/perevent.py`), the battery and the earbud's
A2DP margin; upstream measured ~41 KiB/s on his phone.

> **Standing of §5 and §6 (2026-11-13).** Written to contract form by one writer from `MOTION.md` §6 as Adam ruled it
> that day, with new instruction-level reads; **not yet challenged by a second reader** — that challenge is the
> design pass of the flash each section belongs to (`CLAUDE.md`, the protocol), together with the reads each section
> marks as blocking. Three calls are Adam's before those flashes and are not taken here: the second flash's input
> hook runs on every touch from the start (`FORK.md` §3.1 read as "an event loop is not a boot path", as Phase 2 read
> it — or not); the first flash's link item is a new call site on the connection path (carried, or left out); a
> local gesture the phone rejects returns to its place about one round trip after it showed. The local-latency
> target the second flash is judged against (`FORK.md` D7) is not among his rulings yet.
> **§5 since then:** challenged 2026-11-14 (`HANDOFF.md` §81) and built by C1, whose rulings are written into it
> (2026-11-17). The link item is left out of C1 (the lead's recommendation in §81, which Adam's "continue" followed).

## 5. v2 motion programs (the first flash, C1) — *contract 2026-11-13, from `MOTION.md` §6 as ruled; built with C1, its rulings written in 2026-11-17; not flashed*

Grades as `CLAIMS.md`: **V** read (instruction level or the decompile of bytes that hash SAME, `fwread.py sha`), **M**
measured on Adam's pair, **I** inferred, **U** unknown. "Read 11-13" = `research/fwread.py` this sitting. A tag such as
(R1.4) or (A.9) names the build's ruling that filled the gap: `C1-R1-decisions.md` … `C1-R6-decisions.md` and
`C1-decisions.md` §A (A.n), with the stage reports, in the gitignored `private/build068/` ("the meeting's row n" is
`C1-meeting.md` §3, accepted by R1 §C). `REPLAN.md`'s own R-numbers are cited with its name.

### 5.1 What C1 carries, and what it does not

- **The engine** (§5.2–§5.8): image mode 26 (play, retarget, close), program records in the texture cache, the verbs
  FILL · DRAW (a cached record or the area's own pixels, moved per lens, its brightness ramped) · REVEAL (wipe or
  slide-in from an edge) · WAIT, two built-in eases plus uploaded tables, flag bit 3 ENGINE, the notify field 114,
  telemetry 30–34. "Move a rect per lens" (A1) is DRAW from the area's own pixels (`self`); no separate code.
- **The riders (§5.9):** op 5 up to 256 KiB · telemetry 27–29, the largest free block per arena · mode 25, the deflated
  cache write (flag bit 5 ZWRITE) · the fork's three owed items: the cache pointer released by one exchange, the
  context-survival check, the stock "already fast" link test replaced (the last is not in C1, below).
- **Not in C1:** the vertical-scale draw (A2 → C2, §6.6) · a horizontal-scale op (A3: a card flip is three cached
  images swapped) · any op for fading content that is not cached (B1; a cached fade is DRAW's ramp) · dimming behind
  a popover (B2: mode 22 as built) · banners (B3) · anything that moves on its own (B4, B5; C1 "motion answers input"
  is the phone's policy, not a firmware rule) · Phase 5's cells (only if W4/W5 says they pay, `REPLAN.md` R7).
- **Patch sites:** the engine adds **none** (it renders inside the inherited copy hook, `display_copy_hook`, and runs
  a thread of its own, created at the first accepted PLAY — not on a boot path). The copy hook itself runs from boot
  on every stock refresh: the engine's code in it is reached only past peek's checks with an instance open (which
  needs ENGINE armed), and the hook's stock path stays a tail call (§5.2). **C1 leaves the link item out**: its
  candidate's site list is the installed build's, with none new, and it reports features `0x1ff` (bit 9 unset). As
  designed, the link test is **one new site**: the call
  at `0x004774AE` (in `FUN_0047720C`'s 0xA3 branch) to `FUN_00476DB8`, whose test is at `0x00476F4E` (§5.9, V) — on
  the connection path, after the link is up and (I) before a session's first Damage message, and **unflagged**, like
  Phase 2's three link edits (`REPLAN.md` §5's C1 row, ruled with R6). Contract version stays 2 (§0: added ops and
  bits do not bump it); DamageCaps bit 7 ENGINE, bit 8 the riders, bit 9 the link test.

### 5.2 Where the engine runs (decided at instruction level as far as the image allows)

| fact | grade, basis |
|---|---|
| Every osTimer callback runs on the FreeRTOS timer task "Tmr Svc", priority 54, stack depth 0x1000 **words** (16 KiB). The engine uses no osTimer (Decision, below) | V: `FUN_0047E674` creates it at `0x0047E6B6` (`xTaskCreateStatic`; priority `0x36` at `0x0047E6A6`, name `0x0078EBCC`, depth from `FUN_004D3610`) |
| The display task `FUN_00473C44` ("displaydrv_mgr_task") runs at priority 45, stack 0x800 | V: attr `0x0075381C`, created at `0x00473962` |
| The input handler and the image worker's thread "ui_task" (`FUN_004437E0`) runs at priority 44, stack 0xC000, queue 96 × 12 B | V: attr `0x0075378C`, created at `0x00444842`; queue `FUN_00449A32(0x60, 0xC)` in `FUN_00444720` |
| The image worker runs on the ui_task | I (strong): the deferred consumer (`0x00496A0E`, in `FUN_00496544`) is called only from `0x004E16EC`, kind 3 of `FUN_004E1490`, the UI handler of registry entry `0x006A4520` {0xE0, data `0x004E1193`, UI `0x004E1491`}; the ui_task runs the registry's UI handlers (`research/fork-reads-2026-09-13.md` R0.5) |
| The display gate (`*0x0047390C` = `0x200746BC`, its only literal) is a binary semaphore — created by `0x00441636(1, 0, 3)` at `0x004738B2` and given once at `0x004738D8` — with no owner: any task may give it. It is held by whoever queues a type-3 refresh and given by the display task after the copy hook (`0x00473C92`, before the panel-on test at `0x00473C96`). Its takers are the worker (register call), the LVGL flush `FUN_004736F4` (`0x00473742`, paired with its own give at `0x00473752`; `0x00473762`, left held over a put whose failure it ignores) and three offset setters (`0x0046C98A`, `0x0046C9B0`, `0x0046C9F4`) — not the EvenHub container teardown. **The type-6 path gives it with no take:** a periodic osTimer (2,000 ticks while the panel is on: created `0x00473AE2`, started `0x00473B40`, stopped `0x00473B4E`) queues type 6 (`0x00473B54`); the display task runs the panel's status check (`FUN_004CA5BE(1)`, the record's `+0x30`) and, when it returns 0 (JBD4010: after "status is abnormal, recovery"; or no panel record), the copy hook and a give (`0x00473D68`, `0x00473D6C`) — the hook runs with nobody holding the gate. **The gate does not exclude the hook.** | V 2026-11-14 for every site named (the semaphore type by shape: I); I for the teardown (none of them is on it); how often the recovery path runs on his pair: U |
| `FUN_0047381E` takes the gate through `FUN_00441C44(*[0x0047390C], 1000)`, only logs a failure and returns no usable value (`pop {r0,pc}`); it logs and skips a null handle, but `FUN_00441C44` itself on a null handle raises BASEPRI to 0x30 (`FUN_005FA0A4`), stores to `0xFFFFFFFF` and branches to itself; it returns 1 when it took the gate, 0 otherwise (`0x00441D66`, `0x00441D6E`; V 2026-11-15). `FUN_00474066` queues a 36-B type-3 message through `FUN_00449ABE` with a 1,000-tick put and returns −1 on failure; the LVGL flush ignores that result (`0x0047377A`), so a full display queue can leave the gate held with nothing queued. The display queue is 96 × 36 B | V 2026-11-14 (`0x00473842`; `0x00441C52`–`0x00441C5E`; `0x0047409E`–`0x004740AA`; `0x00473A0A`) |
| CMSIS-RTOS2 wrappers: osThreadNew `0x004490E2` (static control block ≥ 0x70 B; `stack_size` in bytes, passed on as words), osTimerNew `0x004493B0` (periodic iff type ≠ 0; mallocs an 8-B callback record unless `cb_size` ≥ 0x34), osTimerStart `0x00449498` (no wait; −3 when the timer task's command queue is full, −4 for a zero period), osTimerStop `0x004494D8`, osEventFlagsNew `0x00449590` (static ≥ 0x20 B), Set `0x004495E4` (from a task it sets the bits directly, `0x00449638` → `0x0047ED76`; only the ISR form goes through the timer queue), Wait `0x0044969C` (the fourth argument is the wait in ticks; −2 when it expires with a non-zero wait, −3 with a wait of 0, −4 for a null id or a flag above bit 23: `0x004496A6`–`0x00449716`); the gate's give `FUN_0047386A` from a task is `xQueueGenericSend` with a wait of 0 (`0x0047389A`–`0x004738A2`) | V (shapes, SAME; the additions at instruction level 2026-11-14 and 11-15); I (names). Upstream's `seq_tick` uses the timer four on glass |
| The display task's frame at the copy call is 56 B (`push {r4,lr}; sub sp,#0x30`; the task entry is `FUN_00473C44` itself). The installed hook's frame is 152 B (9 registers pushed + `0x74`); its stock path is a tail call (`add sp,#0x74; pop.w {…,lr}; bx r0` to `0x0046CA15`); its one in-frame stock copy is the `fb == 0` path. On the C1 candidate the hook's frame is 40 B (`cfw_draw_flags` out of line, 144 B, counted on the direct path) | V (stock image; the installed image `48172b62…`, disassembled read-only, 2026-11-14); the candidate's by `tools/verify.py` step 7 |
| The stock copy's deepest static chain is ≈ 1,512 B (through an 832-B log formatter, `FUN_0044D25C`, on an error path); with ≈ 210 B of exception and context frames, today's worst on the display task is ≈ 1.78 KiB of its 2 KiB | I (a static walk of BL and tail branches; `blx` through registers not followed; the frames modeled) |

**Decision.** The engine draws only on the display task, inside the copy hook, and only the image worker writes the
shadow, as today (the container's buffer is freed and rebuilt without the gate: I, above). Per lens: one event-flags
object and one **engine thread** (priority 44; 2 KiB stack — its static worst with the stock code it calls walked (the
give's error-path logger) is 1,248 B, 1,458 B with the exception frame, of 2,040 usable (I; a build check, §5.10);
both control blocks and the stack inside the context, so nothing is allocated, and creation from
static memory cannot fail for memory; a null handle still refuses that PLAY, 11), created flags first, then the thread,
by the first accepted PLAY or bound fire of a boot, never deleted. **No osTimer: the thread is its own tick.** It waits
on its flag (`osEventFlagsWait(flags, bit, osFlagsWaitAny, w)`: the fourth argument is the wait in OS ticks, the tick
`FW_MS_TICK` reads — `xTickCount`, V) with w planned at every wake from each drawing instance's t0, P, T and **d, the
last step the hook drew** (written by the hook under the engine word, read here without it; a slot whose P reads outside
16..128 is skipped, and one with d ≥ T has nothing left to draw and gives no due time). With
`k = min(T, 1 + ⌊(now − t0) / P⌋)`, `now − t0` an unsigned 32-bit difference (the tick wraps
after ≈ 48.5 days), an instance with k > d is **due now**; otherwise its next due time is `t0 + d·P`, never past step T.
When one is due now and no engine refresh is outstanding, the thread tries the gate and queues (below), then plans
again and waits that plan's w — one refresh per wake (R1.13); when one is due now but a refresh is still outstanding,
or the try or the put failed, w is one period (the smallest P among the due instances); otherwise
`w = max(1, (int32_t)(due − now))` over the earliest due time — signed, so a time already passed gives one tick,
never a wait of 0 nor one of ≈ 2³² ticks (a guard only: k > d is tested first); with nothing drawing,
`osWaitForever`. A thread whose context the survival check has since refused also waits without limit (R1.13). The
wait's expiry is the scheduled step (pacing), not a bound on an operation: nothing is given up when it expires, and
step T stays due until the hook draws it. A set that lands between the plan and the wait stays in the
flag and ends the wait at once (the bits are tested before blocking, V `0x0047EC6A`). A PLAY, RETARGET or bound fire
sets the flag after it opens its instance (from a task the set cannot fail on a queue: V, above), so the thread replans
whether or not that message's own present was queued. On a wake with a step due, the thread checks the gate handle
(`*0x0047390C`) is not null (a null handle is a failed try), tries the gate with `FUN_00441C44(h, 0)` (wait 0: a
try) and reads the result, then queues
a type-3 refresh **with no direct job** through `FUN_00474066` (its 1,000-tick put is stock's, inherited as the worker
inherits it). A try that fails, or a put that fails (the gate given back), is a fold, counted (33), and the next wait is
one period. The copy hook draws each drawing instance's due step into the framebuffer (§5.3) **only when it wins the
engine's busy word** (§5.11). The display gate does not exclude the hook — the type-6 recovery redraw runs it with
nobody holding the gate (V, above) — so the ui_task and the hook claim that word by a compare-exchange from IDLE before
they write or read what the hook reads (the instance table, the cache pointer, cache bytes an open instance reads), and
a release point decides against it inside a short masked section (§5.11); the ui_task lets it down **before** it queues
any present (else that present's hook would fold and show END mid-motion) and on every exit; the hook, finding the word
held, folds the step (clearing the outstanding mark) and never waits, and it never blocks while it holds the word (no
send, no allocation, no free inside it). The engine's drawing, its claim and its deferred settlement are one `noinline`
function reached from the hook's direct and preserve paths, and for the flip (§5.3) from its stock path, returning
before the stock copy; the hook's stock path stays a tail call and its frame stays within the room its in-frame stock
copy leaves (V for today's 152 B; a build check, §5.10). The display task's whole stack is 0x800 B (V): the engine
path's depth is bounded and checked at build.

### 5.3 The tick

- **Time base:** the OS tick, 1.024 per ms (M, §3). Each instance carries its own period **P in OS ticks, 16..128**
  (≈ 64 Hz .. 8 Hz), from its PLAY; A4's 30 Hz is **P = 34** (33.2 ms), a phone constant moved at the feel session.
- **Steps:** an instance of T steps — T = its longest track's total of segment steps, WAIT included, 1..255 after
  substitution (a longer total is refused, 12) — has step k due at `t0 + (k−1)·P`, t0 = the OS tick at which this lens
  handled the PLAY. **Step 1 is drawn by the PLAY's own present.** At every present while an instance draws — an engine
  refresh or any phone frame — the copy hook draws `k = min(T, 1 + ⌊(now − t0) / P⌋)`: on the direct path whenever an
  instance draws (k ≤ d again, since the phone frame's copy covered it), on the preserve path only when k > d; steps
  passed over are **folded** (not drawn, counted); step T is always drawn (a fold of step T leaves it due). The hook
  arms nothing; it records d, the last step it drew, which the thread plans from (§5.2); a PLAY or RETARGET sets d = 0
  with t0. **A present on the hook's stock path** (no live lease, `direct_active` 0, a failed copy — and a phone frame on
  the direct path with the lease lapsed, whose refresh goes whole, R1.20) draws no step and turns every drawing instance
  done, with no notify, before the stock copy — the tail call, or on the `fb == 0` path the in-frame copy — so nothing
  plans a step that cannot be drawn. The flip claims the engine word; when the word is held it is left to the next
  present (the thread's next wake queues one) or to the release point; a phone frame whose hook loses the claim on the
  direct path goes whole (R1.13). With no context it costs nothing (the hook's existing `ctx` test); with a context and
  no PLAY accepted this boot, one load and compare; after the first, the outstanding mark's store and four loads per
  present (R1.13).
- **Overrun:** nothing queues. At most one engine refresh is outstanding (the flag coalesces; the thread queues only
  when none is pending; the copy hook clears the mark). A slow present folds the next step; so does a present whose hook
  finds the engine word held (that present still clears the outstanding mark); the end time holds.
- **What a present costs (M):** a full transfer 2.0 ms median / 5.3 p90 (Phase 1 build, n = 2,996, `HANDOFF.md` §65)
  and 4.4 ms median / 8.5 max (Phase 2 first light, n = 28); the copy 0.55–0.63 ms; a partial ≈ 1.6 ms + 66 µs/row.
  **The step's own drawing time is U** (field 34): it, not the panel, sets the floor under P. At P = 34 the panel
  path uses ≈ 3–5 ms of 33 (from the measured parts). **It has no bound in the firmware (R2 A-N4):** one nibble write
  per pixel of each track's area, two passes for a `self` backdrop under a `self` DRAW, on the display task at priority
  45; a step longer than P leaves the ui_task (44) only the transfer gaps, so phone messages wait, a CLOSE among them.
  The phone chooses P and the areas from field 34.
- **Refresh rules:** a present that draws a step goes whole (a mode-24 hint is ignored for it). The **panel-stale
  mark** (§4) is set by the present that draws an instance's last step (A.12), so the next present goes whole, and by
  every close of an open instance — a CLOSE, a PLAY's own closes, a live mode 6's closes, a cache write's close-on-meet
  (R1.1, R2 B-N13) — so the present of that message goes whole. `v3-play` pins the preserve path's last-step mark and
  `v3-rulings3` step 5 the direct path's. Engine refreshes are not stamped as
  Damage frames: fields 15/16 and the presented notify (113) ignore them; field 32 counts them.
- **Both lenses:** the PLAY rides the image lane; each lens runs it in its own deferred handler (RIGHT emits the
  completion both run on, `CLAIMS.md`), so t0 differs between the lenses by the deferred path's own inter-lens delay
  (U: the UART rate is unread; sub-millisecond is modeled only) plus each lens's scheduling. Nothing else is added:
  each lens then runs on its own clock (the drift over 255 steps is I, negligible). `MOTION.md` §6 row C2's "no visible
  mismatch" is judged by eye at the feel session and by the 240 fps video (`FORK.md` M0.2); LEFT cannot report.

### 5.4 Program records (in the texture cache; uploaded by mode 19 or 25)

A program is a record at a 4-byte-aligned offset: uploaded, chunked and proven (field 17) exactly as atlas records
are, sharing the cache's budget. Little-endian; s16 coordinates; a per-lens pair as §4.

| part | bytes | fields |
|---|---|---|
| header | 4 | `version u8 = 1 · tracks u8 (1..8) · length u16` (the whole record; parsing must end exactly there) |
| track | 12 or 20, then its segments | `segments u8 (1..8) · backdrop u8 (0..15 a level · 0x20 self · 0xFF none) · perLens u8 (0/1) · 0 · area rect (8) or pair (16)` |
| segment | 32 | `verb u8 (0 WAIT · 1 FILL · 2 DRAW · 3 REVEAL) · steps u8 (1..255) · ease u8 (0 linear · 1 ease-out · 2 table) · kind u8 · src u16 · xL s16 · xR s16 · y s16 · dxL s16 · dxR s16 · dy s16 · lvl0 u8 · lvl1 u8 · opt u8 · 0 · table u16 · h0 u16 · h1 u16 · anchor u8 · 0 · pref0 u8 · pref1 u8` |
| easing table | 2 + 2n | `n u8 (1..255) · 0 · E[1..n] s16`, E[n] = 4096 |

- **kind / src:** FILL — kind is the level (0..15). DRAW — kind 0 a v2 image record at `src` (off4) · 2 `self`. REVEAL —
  0 a record · 2 `self` · 3 a solid level `src` (0..15) of width dxL and height dy (dxL on both lenses, a per-lens
  track too: R1.20). `self` = this track's area as it was in the shadow when the PLAY ran, captured then, per lens, as
  tight packed rows ((w+1)/2 bytes per row, high nibble first) **into the cache at the PLAY's `cap` offset** — only a
  track whose backdrop or any segment is `self` has a capture; the captures lie back to back in track order, each
  starting 4-byte aligned, and the capture range is `[cap·4, the end of the last)` (A.5). Nothing is allocated for a
  capture; its size is bounded by the cache space the phone reserves for it (a 640-wide band is 320 B a row), and
  LEFT's capture bytes differ from RIGHT's wherever the two shadows do. Capture bytes are per lens: the phone never names a region a capture has written as a program, record or table until it
  rewrites that region (the lenses would walk different bytes and could decide one message apart, which LEFT cannot
  report). A capture is not counted in field 17 (the generation counts the phone's write messages) and is covered by
  RIGHT's field-19 CRC, which the model computes from its RIGHT copy. Non-per-lens tracks use xL/dxL on both lenses.
  **h0, h1, anchor must be 0 in C1** (§6.6 gives them meaning). **A field a verb does not use is ignored** (WAIT's
  kind, src and coordinates; FILL's src; lvl0/lvl1 off DRAW; xR/dxR on a track that is not per-lens; a pref without
  bit 7), but the zeros §5.5 requires still bind (the meeting's row 5).
- **Eases:** `ease` is checked on every segment (0..2, else 5); **ease 2 names a table whatever the verb** — walked (5)
  and in the first read range — though a FILL or WAIT uses no eased value (R1.3). E[0] = 0 always. Linear
  `E[j] = ⌊4096·j / n⌋`; ease-out `E[j] = 4096 − ⌊4096·(n−j)² / n²⌋` (u32 arithmetic, no overflow at n ≤ 255); table:
  the record at `table` (off4), whose n must equal the segment's steps (else 5). An eased value:
  `v(j) = v0 + ((D · E[j] + 2048) >> 12)` on a signed 32-bit product that wraps at 32 bits, `>>` arithmetic, so
  `v(n) = v0 + D` exactly. Bounces and overshoot are uploaded tables (E may leave 0..4096).
- **Parameters:** a PLAY carries P0..P3 (s16). `pref` bit 7 = used, bits 0–1 = which parameter, bits 2–6 = the field
  it replaces: 0 src · 1 xL · 2 xR · 3 xL and xR · 4 y · 5 dxL · 6 dxR · 7 dxL and dxR · 8 dy · 9 lvl0 · 10 lvl1 ·
  11 h0 · 12 h1 · 13 steps (u8 fields take the low byte; every check runs after substitution).
- **Per step, per track:** a track paints **its whole area at every step 1..T**: first its backdrop (a level fill;
  `self` = the capture; none = nothing), then its segment's state, clipped to the area. A track holds the state it
  reached: before its first non-WAIT segment, that segment's state at j = 0; after a segment, its final state; tracks
  shorter than T hold to the end. Tracks paint in order; instances in slot order (0..3).
  - DRAW at local step j: the source at `(x + off(dx), y + off(dy))` (per lens), options = `(opt & 0x30) | lvl(j)`
    with `lvl(j)` eased from lvl0 to lvl1 — the v1 LUT (§2: level in bits 0–3, bit 4 transparent tested on the
    source, bit 5 inverse) applies to records and `self` alike.
  - REVEAL: extent `e(j)` eased from 0 to F (F = the source's height for edge 0 top / 1 bottom, its width for
    2 left / 3 right; `opt` bits 0–1 edge, bit 2 slide; bits 3–7 must be 0, else 5). The source is drawn as stored —
    no level, no LUT; lvl0 and lvl1 are not read (the meeting's row 6). Wipe: the source at rest (x, y), visible only
    in the e rows or columns at that edge of its rect. Slide: the source moved in from the edge by F − e (top: drawn at
    y − (F − e), visible [y, y + e); bottom: at y + (F − e), visible [y + F − e, y + F); left and right alike).
  - FILL: the area filled with its level.
- **The declared end state END(prog, P)** = every track's state at step T painted in track order. It is defined by
  the record and the parameters; the phone does not send it. In §4's vocabulary it is a fill (mode 21) per level
  backdrop, then per DRAW a mode-17 draw of the record (or of the capture) at its final position and level under a
  mode-20 clip of the area (for `self`, the capture bytes, which the model writes into its per-lens copy of the cache
  at `cap` as the lens does), per REVEAL the whole source at rest — so the phone's model applies exactly these ops.

### 5.5 Mode 26 — motion control (image lane; both lenses run it; takes the display gate)

`[26][sub]…`; allowed alone or as a mode-8 sub-message; refused inside a self-test step (8), at the step's top level or
in a batch the step carries. The mode byte's high bit is ignored; a sub byte of 3..255 (0x80 included) is an unknown
sub (R1.8). **Inside a batch or a self-test step the container's own list decides first, whatever the message's length
(R1.4):** a message the batch does not allow (a nested batch, 12, 19, 25) is refused 8 with the batch's byte; one the
step does not allow (25, 26) 8 with the message's own byte — so is a 26 that a batch inside a step carries (R6 B6-1;
`v3-rulings3` step 1), while a 25 there meets the batch's list first (8, the batch's byte). The check orders below and
in §5.9 start at a message reached alone or admitted by its container.

| sub | payload after `[26][sub]` | what it does |
|---|---|---|
| 0 PLAY | `slot u8 (0..3) · period u8 (16..128) · prog u16 (off4) · tag u16 · cap u16 (off4; read only for a track whose backdrop or any segment is `self`) · P0..P3 s16` (16 B) | closes the slot's instance, every open instance whose areas meet this one's, and every open instance whose read ranges meet the capture range (one pass, in slot order: R1.20); writes the `self` captures into the cache at `cap`; **writes END into the shadow**; keeps the CRC; opens the instance and sets the engine's flag; step 1 drawn by this message's present |
| 1 RETARGET | `slot u8 · P0..P3 s16` (9 B) | for an open instance (drawing or done): writes END(prog, new P) into the shadow; restarts the instance at step 1 now, each moving segment from the state this lens last drew (with nothing drawn yet, each track's start state: its first non-WAIT segment at j = 0 under the old parameters, A.13) to the new final state; a track with a leading WAIT runs the WAIT's steps again holding that state, then moves from it (R1.2); on this restart path a level is clamped to 0..15 and an extent to 0..F, so an overshoot never wraps (R1.10; the extent's lower clamp is reached by no input the checks accept, R3.1, and stays a guard); END ordinal + 1. Only for programs every track of which has the shape [W], [X] or [W, X] — one optional leading WAIT, at most one other segment, no trailing WAIT (else 12; R1.9) |
| 2 CLOSE | `slot u8` (0xFF all) (1 B) | empties the slot whatever this lens was drawing (its captures are cache bytes: nothing is freed); a drawing instance's notify says outcome 1, a done one sends none — only the notify depends on timing; an accepted CLOSE presents like every gated message — alone its own present, in a batch the batch's — and a CLOSE that ends an open instance marks the panel stale, so that present shows the shadow, which holds END, whole (R1.1); an empty slot: nothing |

**Check order (the reason recorded is part of the contract):** no shadow (4; A.6) → length (1; an unknown sub must be
3 bytes, R1.8) → the lease (3) → the engine's busy word claimed (14 if it cannot be, R1.15; the flags are read under
it) → DRAW2 (9) → ENGINE (15) → slot and period (12; P0..P3 are not checked here, A.10; an unknown sub is refused 12
here, R1.8) → for RETARGET an empty slot (16), then the RETARGET row's shape rule on the instance's already validated
program (12; R1.9) → the program record and every record and table it names, inside the cache and well formed (5;
the walk yields the instance's first read range, §5.6) → areas inside the panel, not empty, a pair one size and one
row range — **every pair checked whole on both lenses** (2) → a `none` backdrop's area (both rects) inside one earlier
track's area whose backdrop is not `none` (2) → every step's values in range after substitution: levels 0..15, extents
0..F, h0/h1 = 0, each segment's steps 1..255, T ≤ 255, a level source's level 0..15 and its width and height above 0
(12) → for a PLAY with a track whose backdrop or any segment is `self`, the capture range — `cap` and the captures'
total size inside the cache, not meeting this PLAY's own first read range (5) → for a RETARGET, the new first range
not meeting the instance's capture range (5) → the thread and flags at first use (11: a null handle; both are made from
static memory). Only then is anything closed, captured or written. **Inside the walk (A.9):** structure, enumerations
and reserved bytes are 5 — every reserved byte, `anchor` and a verb's unused `opt` bits must be 0, `src` of `self` is
ignored, a table's E[n] ≠ 4096 or n ≠ steps is 5; numeric values are 12, at the values position. "Meets" = the
bounding box of any track's pair in one instance intersects that of any track's pair in the other, the same decision
on both lenses (the meeting's row 11).

### 5.6 The end-state rule, the region, and the cases

- **END is written into the shadow when the PLAY (or RETARGET) is handled**, in stream order on both lenses; steps
  draw only into the framebuffer, over the copied shadow. The phone's model applies exactly END at the same message
  — so the shadow and the model never depend on timing. RIGHT's CRC-32 of its shadow over the instance's areas
  (each area's tight packed rows, `(w+1)/2` bytes, high nibble first, in track order), taken right after that END
  write, is reported in the instance's notify (§5.8); the phone compares it with its model's, keyed on (slot, tag,
  ordinal): **a mismatch is a `divergence` note and a keyframe, never silent.** So is a refusal notify for a play the
  phone's mirror took — matched by that play's record (slot, tag; R5 B-N2), an untagged one by the slot being open in
  the mirror (R2 B-S2). One cap of two keyframes per instance, keyed by (slot, tag), covers refusals and CRC mismatches
  together, so a refusal is never answered by an unbounded run of images. The panel is not timing-free: whether a phone
  frame's present draws a step, which step, and whether a hinted present after a last step goes whole depend on
  timing. The simulator's panel treats a drawing instance's areas as unknown from its PLAY until RIGHT's notify for
  that (slot, tag, ordinal) or the nominal end (a signed 32-bit compare), whichever is later, and until the phone's own
  mirror has drawn the instance to its end (R2 B-S1); it applies hints as written (vectors, which carry the clock, stay
  exact).
- **A phone write inside a drawing instance's area** lands in the shadow at once (the model agrees), but the lens
  keeps painting the instance over it: after a last step drawn by an engine refresh the framebuffer holds step T, and
  nothing in the firmware presents the shadow again — the write reaches the lens at the first present after that
  lens's instance has stopped. **The phone's rule:** a write that meets an open instance's area CLOSEs that instance
  first, in the same batch, so the batch's own present shows it. (A phone that wants a write held to the motion's end
  sends it, or an empty batch, after RIGHT's last-step notify instead; the firmware is the same either way.) **As the
  phone builds it (C1):** "open" is the mirror's, drawing or done, compared per lens; a write the transport cannot
  bound (a mode-14/18 glyph run, a v1 cached image) counts from its origin to the panel's right and bottom edges; a
  flush that carries a keyframe adds no CLOSE (the keyframe closes every instance); more CLOSEs than the batch's count
  holds become one CLOSE 0xFF; a CLOSE is added only while ENGINE is in force, and the compositor keeps one sub back
  while an instance is open (R2 B-N5).
- **The cases:** a phone flush touching a running area — as above. A live **keyframe** (mode 6, not a self-test step's)
  closes every instance once it has passed its length and no-shadow checks, before the inflate — decided from the
  message's bytes alone, so both lenses decide alike (A.8) — and each close of a drawing instance notifies outcome 1
  (the meeting's row 19). **Mode 11 (A.7), a lease lapse, FB_RELEASE, a fresh acquire** (and so Silent Mode, where the
  shell releases on purpose) are release points: every instance ends, nothing is drawn after, no notify is sent; the
  copy hook draws only over a Damage frame it copied or preserves (`direct_active` with a live lease, on the direct
  path too, §5.3), so stock content is never drawn over. **A lapse nothing has noticed yet** (it becomes a release
  point only inside `cfw_fb_lease_active`, which only a message or a gesture calls) meets the hook's own deadline
  test: that present takes the stock path, draws nothing and turns every drawing instance done with no notify (§5.3), so nothing replans; the
  release point that follows settles the rest. **The link dropping:** the lease runs on (≤ 87.9 s), the instance ends by
  itself (T ≤ 255 at P ≤ 128 is ≤ 32.6 s, inside one lease period while renewals arrive), its notify is lost; the next
  session's acquire ends what is left. **A retarget after the last step** re-aims from the final state (a new motion to
  the new END); after a CLOSE or a release point it is refused (16) — the phone tracks closes in stream order, so it
  sends none. **A second PLAY over a busy area** closes the first (its END is already the shadow the second captures
  from). **A record, table, program or capture overwritten:** a cache write (12, 19, 25) whose entries meet an open
  instance's read ranges closes that instance first, under the engine word. An instance has two read ranges:
  `[lowest, highest)` over its program record (its `length`), every v2 image record it names (from `off` to the end of
  its RLE stream, as the validator's walk finds it) and every easing table (`2 + 2n`); and its capture range. The
  phone's model computes the same two and keeps a program's records and tables together, so the first stays tight; a
  write between two of its records closes it (deterministic; a planning cost only). A RETARGET's walk replaces the
  instance's first range (its parameters may substitute `src`), and a first range that meets the instance's capture
  range is refused (5), as a PLAY's is. **A cache write while an earlier present still waits** (R2 A-S1; not built): a
  cache write can hold the engine word while a present queued before it still waits for the display task; that
  present's copy hook loses the claim and folds, its refresh goes whole with the shadow's END in the instance's area,
  and the lens shows END for about one period mid-motion, until the next engine refresh draws the due step. The fold
  is counted (field 33). A gate take around the cache write would close it; it is a new wait on the upload path and is
  not built. The feel session reads field 33 while a test program plays with uploads in flight.

### 5.7 Memory (what must be measured first, and what happens when it is short)

- **Fixed:** the instance table (4 slots, ≈ 0.56 KiB each as built), the thread's 2 KiB stack and its two control
  blocks (the thread's, the flags') live in the context (allocated once, from the stock malloc's arena, our 20: I from
  upstream's labels, `HANDOFF.md` §67); the context was 6,152 B in all as the fork's stage F2a built it. Nothing per
  tick. **Per PLAY:** nothing allocated — the `self` captures are written into the cache at the PLAY's `cap`, in space
  the phone reserves (the save-under pool stays mode 23's alone). **Per mode 25:** a transient inflate (§5.9).
- **Measured before C1's budget is fixed:** arena 13's free memory (fields 7–9) before the carrier CREATE, after it
  and after the first frame (an APK change, `REPLAN.md` §5; built in APK 0.66 as the journal's `heap3` notes at four
  points of a start — `pre-create`, `lease`, `atlas`, `first-frame` — none read in Silent Mode, `HANDOFF.md` §79.2),
  then fields 27–29 on the candidate with the cache at 160 and at 256. Modeled today (`HANDOFF.md` §67): ≈ 130 KiB free at 256, ≈ 82 with the pool full, before a mode-25
  decode's ≈ 40–56 KiB; the self-test's 150 KiB scratch needs a session at ≤ 160.
- **The phone's layout on the 256 KiB rung (C1, R1.19):** the shell's atlas lays out over 192 KiB, and the top 64 KiB
  is kept for the test programs' records; a session in which the probe writes a program drops the shell's atlas for
  that session (the cache has another writer).
- **Short:** a refusal and nothing written, with notify outcome 2 at once; the phone draws that motion as today's
  frames. A PLAY has no memory refusal of its own (a capture region the cache cannot hold is 5, decided alike on both
  lenses); mode 25's inflate has one (11). No op ever writes past an allocation.
- **Refusals one lens alone can make (a class; I):** an allocation made separately on each lens can fail on one of
  them, and LEFT cannot report it — the v1 inflate of modes 3 and 6 (per message), the cache's first allocation (12,
  19; once per session), mode 23's capture, mode 16's scratch and mode 25's inflate (per message, new in C1). A
  LEFT-only refusal leaves LEFT's shadow or cache behind RIGHT's until the phone rewrites that region, unreported. C1
  adds no per-PLAY allocation. Ordering does the same (R2 A-N6): FLAGS_SET runs on each lens's settings task in no
  fixed order with that lens's image lane, so a PLAY (or a DRAW2 op) sent soon after the arming can be refused 15 (or
  9) on LEFT while RIGHT takes it, leaving LEFT's shadow without END, unreported, until the phone rewrites the area; the
  phone arms inside the session start, before any PLAY, as it does for DRAW2. Closing the class waits on an inter-lens
  report (§5.12).

### 5.8 Notify, telemetry, flags, capabilities

- **Notify field 114 DamageMotion (RIGHT only)**, `G2SettingPackage{1: 3, 2: 0, 114: …}`: `1 sequence (since boot) · 2
  slot · 3 tag · 4 outcome (0 drew its last step · 1 closed before it · 2 refused) · 5 END ordinal (0 the PLAY, n the
  n-th RETARGET) · 6 steps drawn · 7 steps folded · 8 the END CRC · 9 the largest step-drawing µs · 10 the reason
  (outcome 2) · 11 uptime`. Sent each time an instance stops drawing (its last step, or closed while drawing), with the
  last END written, and once per refusal — a mode 26 the step's list refuses, or one the busy gate drops (14), sends
  its outcome-2 body too (the meeting's row 22). **Bodies (A.2):** outcomes 0 and 1 carry fields 1–11; an outcome-2
  body carries 1, 2 and 3 when the message holds them (an unknown sub's carries no slot, R1.8), 4, 10 and 11, none of
  5–9. The sequence of the first body since boot is 1 (row 16). Fields 6 and 7 count per END ordinal, so a RETARGET
  restarts them (row 17): 6 the distinct steps drawn, 7 the steps passed over at drawing presents, so drawn + folded = T
  at outcome 0 (A.11); an instance played and closed within one message reports 0 drawn (row 18). Field 9 is the
  instance's own largest step-drawing µs, field 34 the last present's. **Where from:** a last step's notify from
  `damage_refresh_hook`, after the gate is given back and outside the engine word (field 113's precedent, in daily
  use) — the copy hook builds the body at the last step, under the engine word, into an owed record per slot (a slot played again before the next refresh
  cannot change it; a second owed body for one slot before a refresh replaces the first, which the phone sees as a
  sequence gap), because the stock sender can wait: on its branch that posts the 0xA3 link request (`FUN_0047564E` calls
  `FUN_00478160` at `0x00475754` when the message type is 1 and the link mode byte is not 0xA3), `FUN_0047697E` polls
  `osDelay(10)` while a global is 0 and posts with a wait of `0xFFFFFFFF` (`0x00476988`–`0x004769E2`); the rest of the
  path to the transmit queue is not read. The refresh hook today passes through unless a direct copy happened, so it
  reads the owed mark too; the refresh runs only with the panel on, so a last step drawn with the panel off is sent at
  the next refresh, and a body owed before a release point is still sent at the next refresh (row 23). A refusal's or
  a close's notify from the ui_task, after it lets the engine word down: it keeps one capture per slot plus one refusal
  per message (a second close of one slot in a message replaces the first — a sequence gap, as with the owed record),
  takes each sequence at capture by one atomic add, and sends at the end of `image_worker`, after the present is queued
  or the gate given back, slots in order, then the refusal (A.2); so the two tasks' notifies may reach the phone out of
  sequence order. **A lost notify** costs pacing only: END is already in the shadow; the
  phone's scheduler resumes on its own clock at the instance's nominal end and reads field 30 at its next telemetry
  read; a sequence that has not arrived by the next telemetry read (field 31 at or past it) is journaled as a gap (a
  notify still in flight then is journaled as a gap and again when it arrives: row 48).
- **Telemetry:** `30` open slots (bits 0–3), drawing (4–7), the engine thread exists (8) · `31` the last notify-114
  sequence · `32` engine frames since boot (the thread's successful puts, A.11) · `33` steps folded since boot (steps
  passed over + failed gate tries + failed puts + lost claims, A.11; a fold rhythm near 2 s would point at the stock
  panel status check — type 6, every 2,000 ticks while the panel is on, V `0x00473B40` — whose duration is U: expected,
  not shown; the rhythm is read on the glasses after the flash. It also counts folds that are not real ones (R2 A-N5):
  a PLAY sets the engine flag before its own present is queued — mid-batch in a batch — and a time slice that hands the
  CPU to the thread in that gap finds the gate held by the worker, counts a fold and waits one period, while the PLAY's
  present still draws step 1) · `34` the last present's step-drawing µs (the DWT figure, §4's calibration), omitted
  until a step is drawn this boot; 30–33 are always sent (A.11).
- **Flags:** bit 3 **ENGINE** (needs DRAW2 armed too: a FLAGS_SET of ENGINE alone is taken, status 0, and a PLAY
  is then refused 9 — the meeting's row 20), bit 5 **ZWRITE**; both clear at every release point (§3) and
  are refused without the lease (status 3). The keeper arms one new bit per soak (ZWRITE, the 256 KiB size, then
  ENGINE) and its hold-back drops the bit armed last (the ladder, below). **A disarm closes
  nothing:** FLAGS_SET runs on the settings task, unordered with the image lane, so clearing ENGINE (or DRAW2) leaves
  open instances to run to their end, and a PLAY, RETARGET or CLOSE handled after it is refused (15, or 9); the phone
  CLOSEs all (0xFF) and lets that reach both lenses before it disarms, as it arms DRAW2 inside the start today — built
  on the probe path, the only mid-session FLAGS_SET that can clear ENGINE: the CLOSE is waited on to its ack, and
  without one the disarm is not sent and a note says so. **The start's exception (R1.17):** at a session start on a
  carried lease, the start's first FLAGS_SET clears ENGINE with no CLOSE first — no PLAY is in flight, a disarm closes
  nothing, and the warmup keyframe (a live mode 6) closes every instance.
- **The phone's ladder (as built in C1; A.17):** a stage 0..3 (0 nothing · 1 ZWRITE · 2 and op 5 at 256 KiB · 3 and
  ENGINE), kept in the transport across sessions and in one small file in the app's data directory, so a restart of
  the process keeps it, a hold-back's lowering included (build C1-P; the hold-back's clocks are not kept: the first
  start after a restart cannot blame a reset from before it; an unreadable file reads as 0; a run that ended on an
  uncaught error steps it down to 0 at the phone's next process start, said as a `holdback` fault), set only by hand
  (`probe c1=N`), never advanced by itself; stage 0, the default, sends today's bytes, and a hand `flags=` probe cannot
  arm bit 3 or 5 beyond the stage. Every rung records itself — the time and the stage this build can arm (a build
  without the engine records 2 at stage 3) — before its first byte (R2 B-M1, B-N4): op 5's rung before it goes out, a
  FLAGS_SET at its first send after its session check, so a call whose session has ended records nothing (R6 B6-3).
  The hold-back is decided at a start, before op 5, and blames the ladder only if the last start armed some of it
  (otherwise §10's rule decides): RIGHT's reset within `HOLD_BACK_MS` (120 s) of the last arming, or — while a rung is
  armed — a reboot-like link end of LEFT (not one the phone made) in that window after the last arming (R5 B-S1),
  drops one stage and keeps the rest, said as a `keeper` note and a `holdback` fault with its reason. The start reads
  each end once (R6 B6-2), and one LEFT end holds back once. A reset seen only by the arming's own read drops one
  stage, and that session arms nothing. Each start re-arms and re-stamps, so the window runs from the last
  reconnect: a link that drops twice inside two minutes costs a stage each time, down to 0, each said, and a plain
  radio drop of LEFT reads like a restart — the feel session reads LEFT's end reasons before taking a drop for a lens
  restart (focused read B). A LEFT end that comes with a reboot-like end of RIGHT and no RIGHT reset counts as LEFT's
  restart; whether it should count as a radio drop instead is left for the glasses to show after the flash (R6 B6-4).
- **Reasons:** 14 the engine's busy word not claimed (§4) · 15 the op's own flag not armed (ENGINE for 26, ZWRITE for
  25) · 16 no such instance.

### 5.9 The riders

- **Op 5 CACHE_SIZE: 64..256 KiB** on a bit-8 build (status 5 outside it); the v1 window stays the first 64 KiB.
- **Fields 27/28/29:** the largest free block of arenas 13/20/27 in KiB (the same bounded, range-checked walk as 7–9;
  omitted when an arena does not validate) — a cache must be one block.
- **Mode 25, the deflated cache write:** `[25][zlib stream]`, inflating to exactly a mode-19 entry list (no mode
  byte), at most 16,384 B. In a batch or a self-test step the container's list refuses it first, whatever its length
  (8: in a batch with the batch's byte, in a step with its own byte, 25; R1.4, §5.5). Order for a message reached
  alone: length ≥ 3 (1) → the lease (3) → DRAW2 (9) → ZWRITE (15) → the inflate: its state and the 16 KiB output from arena 13 (11, also
  an `inflateInit2` that fails or a `Z_MEM_ERROR` mid-stream), a stream that does not end cleanly or runs past 16,384 B
  (13); bytes after the stream's end are not read (the meeting's row 21) → then mode 19's own checks and semantics on
  the list: the entries' shape (1), the lease again (3), DRAW2 again (9), ZWRITE again (15), the entries inside the
  cache (5) (R2 A-N1, R5 A-N8). A release point during the inflate is seen by the lease, and a release and a fresh
  acquire by the flags, until the new session arms DRAW2 and ZWRITE again: a release, a fresh acquire and its
  FLAGS_SET all inside one inflate write the old session's list into the new session (no known path does this;
  closing it needs a session identity, not built — R6 A6-3). An empty list writes nothing and does not bump; one bump
  per message. Cache writes were 61 % of the image lane's bytes and per-message deflate took them
  to 57 % (M, `HANDOFF.md` §68, v1-era traffic). **The phone (as built):** it sends a mode 25 in place of a mode-19
  message only while ZWRITE is in force and the deflated form is smaller, one waiting at a time, never from the motion
  API. Field 17's count is the only judge of a mode 25 (R1.18); a refusal record naming 25 turns mode 25 off for the
  session and the same list is re-sent once as mode 19 (R2 B-N6) — not while the glasses are in Silent Mode or no
  lease is held, where the wake's upload covers it (R5 B-N3); the image lane itself has no Silent gate, so an image
  queued as the Silent push arrives still goes, once (focused read B).
- **The cache pointer's release:** claimed by one exchange like the slots and the scratch (FBW-2, `HANDOFF.md` §76);
  a release point withdraws it inside its masked section or, finding the engine's busy word held (by the copy
  hook or the ui_task), leaves the withdrawal to the holder's settlement (§5.11). No wire change.
- **The context-survival check:** after a lens restarts, nothing of a previous boot's Damage state is used — flags, the
  cache, slots, instances, bindings, the engine's handles; fields 16, 17, 31–33 restart at 0. A stale context would hand
  the engine a thread handle from the last boot, so this is **a precondition for ENGINE**. First the free check: a
  `keeper: the glasses reset` note followed by field 16 continuing rather than restarting means the context survived
  (`HANDOFF.md` §74 item 9). **Constraints on any mechanism:** peek runs from boot on every stock refresh (the copy
  hook), and after a restart the old block may already belong to stock's allocator, so peek only refuses (returns 0) and
  writes nothing; get creates a new context — which gets this boot's tick as its record at creation (R1.12) — and
  writes nothing into the old block, nor frees it; the recorded value is read before the OS tick, so a later record
  written by another task in between cannot make a live context look stale (a live context judged stale would start a
  second engine thread and leave a cache of up to 256 KiB allocated). **The
  test:** a boot-unique value recorded at creation was looked for offline and none is held in RAM (`HANDOFF.md` §81), so
  the uptime-ahead shape stands, with its miss stated (R2 A-S2): it accepts a stale context once this boot's uptime
  passes the previous boot's last record, unless a get came first — peek keeps no record of a refusal, so a peek or get
  after that point accepts the old context, and get then writes this boot's tick into it. That needs RAM and the slot
  word to survive a warm restart (U) and no get for longer than the previous boot's uptime at its last message (the
  phone away; a wear event calls get). **Time (R3.2, R5 A-N4):** a live context reads as stale when more than 2³¹ ticks
  (about 24.3 days) pass with no get; the tick's 32-bit wrap itself changes nothing while gets keep arriving (a signed
  compare). Then peek refuses it, the next get makes a new context beside it and the phone rebuilds its session; the
  old context's cache (up to 256 KiB), slots, scratch and engine thread stay allocated until a restart, and the old
  cache and the new session's must each fit heap 13's arena as one block, or the new session's cache writes are
  refused 11 until a restart. With the phone attached a get arrives every minute, so this needs the glasses untouched
  by any phone for about 24 days (a host test pins today's behaviour at that point). **Open (R3.3, R5 A-N5): whether a
  context survives a restart at all.** The records on the PC lean to "not followed" for the one restart with a reading
  after it (RIGHT, 2026-09-15, an older build; one case, grade low to moderate). The settling read is informative only
  on the installed build — on the C1 candidate the telemetry read before the acquire is itself a get, which refuses a
  surviving block, so the counter reads 0 either way — so it belongs before C1's first flash: at a restart of RIGHT on
  today's firmware, the record before and the record after compared (uptime, cache generation). A restart made for it
  is Adam's call; the first flashing conversation names this point. **If it does survive (R5 A-S1):** a restart that
  lands while the engine's busy word is held (a fault inside a held section) leaves the adopted context's word held,
  and nothing in the new boot lets it down: every cache write (12, 19, 25), every keyframe over an open slot and every
  mode 26 (PLAY, RETARGET, CLOSE) is refused 14, every phone present goes whole, field 33 keeps rising, and every
  release point only marks the word PENDING, so the cache is never withdrawn and op 5 answers ALLOCATED; only a
  restart of the glasses clears it (R6 A6-4's wording). The phone's half: reason 14 on three cache writes in a row is
  said once per session as a notice in plain words (the glasses are refusing uploads and need a restart) and
  journaled; nothing is re-sent because of it. It reads RIGHT's record only (a word held on LEFT alone is not seen,
  §5.7's class), restarts its run at each session, counts two refusals with no present between them as one (a lower
  bound), journals a notice due during a link change and says it again only in the next session, does not count a
  keyframe or a mode 26 refused 14, and says nothing on a build without the engine bit (focused read B; R6 B6-5).
- **The link:** the stock test that counts any link under 31.25 ms as already fast, ignoring latency (the call at
  `0x004774AE`, in `FUN_0047720C`'s 0xA3 branch, to `FUN_00476DB8`, whose test is `ldrh r0,[r4,#0x18]; cmp r0,#0x19` at
  `0x00476F4E`; latency `[r4,#0x1A]` is only logged; V 2026-11-14) is replaced, by retargeting that one call, with a
  test that counts a link fast only at ≤ 11.25 ms with latency 0 (upstream `845f665`'s rule). On 0xA3 stock records the
  mode and asks nothing; otherwise it asks, or reschedules itself 10,000 ticks later while a request for the same mode
  is pending (`0x004775A8`), so a phone that keeps > 11.25 ms or a nonzero latency is asked again at each later fast
  request. It is a new site on the connection path and unflagged (§5.1). **Read offline 2026-11-14 (`HANDOFF.md` §81):**
  the stock update handler `FUN_00477ADC` asks again whenever the granted interval classes differently from the request
  (2,000 ticks after a grant for 0xA3, 4,000 for 0xA4; 10,000 after another error) and 30,000 ticks after a rejection
  (status 0x0C or 0x3B) (V `0x00477E04`–`0x00477FCE`). So, as read, edit (3) already asks again without bound on the
  installed build — its 0xA4 case binds the fast record but records 0xA4 as requested (`0x0047859C`), and a fast grant
  classes 0xA3 — and this item adds one case of its own: a phone that rejects the 7.5-ms request is asked again every
  30,000 ticks (≈ 29 s); a grant of 15–30 ms classes 0xA3 and ends it. Whether either runs on his pair (a rejection; an
  update event for an unchanged grant) is glass-only. **C1 does not carry this item** (§5.1); still not decided: the
  correction edit (3) needs. DamageCaps bit 9; the phone journals every parameter change.

### 5.10 Vectors (§9's form), and what no vector can prove

New ops: `{"wake": true}` = one engine wake at the clock the last `{"tick": n}` op set (§9; the hook reads that
clock as `now`, so every engine message and every wake follows a `tick` op): the thread's try and queue, then the
display task's copy hook and refresh, synchronously. A vector's clock never goes backward, nor forward by 2³¹ ticks
or more, after a get — the survival check would make a fresh context (R1.12); every vector and fuzz set complies.
`{"stock": true}` runs the copy hook too: with a live lease and a Damage frame up, a drawing instance's due step is
drawn and that refresh goes whole. Expectations gain `N`, `W` and `G`, and a vector the `features` key (§9's shape);
within one step `N` lists the refresh hook's bodies before the ui_task's — the host's order, a convention of the two
harnesses (R1.20; on glass they are two tasks, §5.8). Engine vectors carry `"panel": true`: `L`/`R` prove END in the
shadow, `P` the step on the lens. **No self-test form** (a step presents nothing, and a PLAY in a step is refused,
8); END on glass is proven by each notify's END CRC (RIGHT); mode 25 runs live. Inputs from `make_vectors.py`,
expectations from the C (`--write`), the simulator equal.

`v3-play` (one FILL and one DRAW track: END in the shadow at the PLAY, step 1 on the panel, wakes at chosen ticks,
the last step, the stale mark it sets (A.12); the thread's next due time from the table; a phone write into a
drawing area without a CLOSE — `P` keeps step T after a last step by a wake, until the next present) · `v3-draw` (record and
`self` sources — the captures written into the cache at `cap`, per lens — per-lens x and dx, the ramp, transparent
and inverse, clipping at the area, a late wake folding three steps) · `v3-reveal` (wipe and slide, four edges, a
level source, F = 1) · `v3-ease` (linear and ease-out at n = 1, 2, 7, 255; an uploaded overshoot table; E[n] ≠ 4096
and n ≠ steps refused) · `v3-params` (each pref field, a u8 field taking the low byte, a substituted value out of
range, substituted steps making T > 255) · `v3-retarget` (drawing, done, after CLOSE → 16, a multi-segment program →
12, a PLAY and a RETARGET in one batch, A.13) · `v3-close` (CLOSE one and all — of a drawing instance too, which
empties the slot — a PLAY into the slot and over an area, a live mode 6, a self-test step's mode 6 closing nothing,
mode 11, a cache write over a drawn record, over a capture, inside an instance's first range between two of its records (closes) and beside it (does not)) ·
`v3-order` (one refusal per reason in the stated order, both rects of a pair checked on both lenses, T over 255, a
capture range outside the cache or meeting the PLAY's own first range) · `v3-lifecycle` (ENGINE before and after
DRAW2, release points ending instances, nothing drawn over stock content, a lapse no message has noticed — the next
present takes the stock path, draws nothing, every drawing instance turns done with no notify, no wake follows —
ENGINE disarmed while an instance draws: it runs to its end, the next PLAY refused 15) · `v3-hint` (a phone frame
with a mode-24 hint while an instance draws goes whole) · `v3-zwrite` (mode 25 equal to the same list as mode 19, one
bump, an empty list, 16,384 B and one more, a broken stream) · `v3-cache256` (op 5 at 256, a record past 160 KiB, the
v1 window unchanged; 161 KiB → 0 moved here from `v2-cachesize`, whose over-budget request is now 257, and
`v2-refusals`' unknown mode is now 120, so the v1/v2 set answers alike on either build, A.1) · `v3-mech` (the clock's
wrap, a RETARGET replacing the first range, a backdrop-only capture, two last steps on one wake, per-slot ui_task
captures with a sequence gap, captures leaving `G` unchanged, the capture alignment at the cache's end) ·
`v3-rulings` (R1's latent rows: unknown subs of 3, 2 and 5 bytes and sub 0x80, a CLOSE in a hinted batch, short 25s
and 26s in a batch and in steps, ease checked on FILL and WAIT, RETARGET's shapes and an overshoot clamped, R1.20's
rows) · `v3-lease` (R1.7: steps 88,000, 89,999 and 90,000 ticks after an acquire, across the clock's wrap and away
from it) · `v3-rulings2` (R2: the stale mark at a close-on-meet and at a keyframe's close, a three-segment RETARGET,
a level below 0, a v1 frame 90,001 ticks or more after the acquire while an instance draws) · `v3-rulings3` (R5 B-N7 and R4.3: a mode 26 in
a batch inside a step, refused 8 by the step's list before its own length — step 1; the direct path's last-step stale
mark — step 5).

**The set at the end of C1 (2026-11-17):** 51 vectors (34 v1/v2, 17 v3) and 1,560 lens-steps; the self-test form 27
vectors and 522 lens-steps; v1/v2 also at features `0x1ff`, 34 and 684; two differential fuzz sets of 40 vectors
(`firmware/fuzz_vectors.py OUT 40 25 --c1`, seeds 1 and 1001; 2,344 and 2,348 lens-steps; filled by the C, not
committed). The simulator matches the C on every one of them.

**No vector can prove** what needs time, threads or two radios — the host harness has one thread and no real
clock: the thread's paced wake, the gate shared with the ui_task, LVGL's flush and the type-6 recovery redraw (which
runs the hook with nobody holding the gate), the engine word under preemption, the copy hook's drawing time, the
display task's stack (a build check, below), the lens skew, memory fragmentation, a context across a real restart (a
host test can plant a stale one), LEFT's END and LEFT's refusals (LEFT cannot report), the step pixels on the lens,
the notify reaching the phone. Nor do the vectors reach, though the checks exist, a release point during mode 25's
inflate (host pins through the shim's inflate door: R2 A-N1's lapse, R5 A-N8's release and acquire), the extent's
lower clamp on a RETARGET (R3.1; a host pin through a planted state) and a lapsed direct refresh going whole (R4.3;
host pins). **What proves them:** the notify's END CRC equal to the model's on glass (RIGHT's shadow only — never
LEFT, never a step); fields 32–34 over a soak day (they count; they do not judge);
`glassdrive.py` scripted plays (they snapshot the phone's mirror, its model, not a lens); the feel session — lens
match by eye and the 240 fps video, the only proof of the steps and of LEFT; the journal (no hold-back, the folded
count); and **the build's checks** (`~/damage-cfw/tools/verify.py`, whose `--selftest` keeps the tool's own planted
cases; R1 D.5–D.6, R2 A-N2–A-N3, R5 A-S2/A-N6/A-N7, R6 A6-1/A6-2 and notes 5a–5e). *Step 6, the sites:* every site
is the installed build's (a new one FAILs); a branch site lands on the function its description names and stays a
direct call or branch of the kind it was; a non-branch site whose bytes differ from the installed build's FAILs unless
the tool names it with its reason (none today); every call and branch in the built object has its target read.
*Step 7, the stacks:* the call graph is read with relocations, and a call through a register counts as resolved only
when every path gives it one constant — unresolved under the engine thread or either display-task hook, it FAILs. The
hook's frame (40 B on the candidate) against the room its in-frame stock copy leaves (≈ 270 B), its stock path ending
in `bx r0` to `0x0046CA15`; Damage's chain under the copy hook within the stock copy's ≈ 1,512 B (§5.2, an estimate);
each hook's walked depth within 0x800 − 56 − 210 = 1,782 B (the candidate: copy hook 1,472, refresh hook 948); the
engine thread's 1,248 B plus the 210-B exception frame within its 2,040 usable. *Step 8, the masked section:* raised
to 0x30 and restored to the saved value on every path, entered only through its raise, holding the load and the store
of the busy word and no call; the word named in the sources only in the claim, the let-down and the decision, its
address taken only as a compare-exchange's first argument and never left in an argument register a callee reads;
every store of the word's width resolved and listed; every claim and let-down a compare-exchange (an exclusive load, a
compare with IDLE or ACTIVE, the other value stored on the equal side).

### 5.11 The failure envelope (`FORK.md` §3.8)

| new state, wait or guard | (a) cleared at | (b) a write that never lands | (c) keyed on | (d) other tasks, the other lens | (e) one read | (f) siblings checked |
|---|---|---|---|---|---|---|
| instance slots (state word, prog, params, tag, t0, period, T, ordinal, last drawn values, two read ranges) | every release point, a live mode 6 and mode 11, CLOSE, a PLAY into the slot, a present on the hook's stock path (drawing → done, no notify), a restart (survival check) | END is in the shadow whatever happens; a lost notify is pacing only (§5.8) | slot + tag + ordinal; bytes by the instance's two off4 ranges (§5.6), closed when a cache write meets them | written on the ui_task while it holds the engine word, which it lets down before any present it queues; read, and the drawn values written, by the copy hook only when it wins the word (else it folds); the thread reads t0, P, T, d and the state word without the word (a torn read costs one early or late wake); a release point decides against the word in a masked section and, finding it held, leaves the settlement to the holder (row 3) | the copy hook reads each state word once per present; the thread once per plan | PLAY, RETARGET and C2's COMMIT share one validator; alone and in a batch; both rects of every pair on both lenses |
| `self` captures (cache bytes at the PLAY's `cap`) | with the cache (release points, mode 11), or overwritten by a cache write that meets them, which closes the instance first | none: nothing is allocated; a region the cache cannot hold is refused (5) before anything is written | the instance's capture range (off4) | written on the ui_task under the engine word; read by the copy hook under it; LEFT's bytes differ from RIGHT's (the model keeps both) | the hook reads the cache pointer once per present | the records and tables an instance reads (the same guard); mode 23's slots keep their own heap allocations (§5.7's class) |
| the engine's busy word (IDLE · ACTIVE · PENDING): held by the copy hook while it draws or flips (§5.3), and by the ui_task around a mode-26 message's handling (after its lease check), a live mode 6 or mode 11's closes, and a cache write's close-on-meet test and write | the holder's epilogue, on every exit (refusals included): a compare-exchange ACTIVE → IDLE; when it fails the word reads PENDING, and the holder settles while it still holds the word — ends every instance and, if a release point recorded that the cache goes, takes the cache pointer by exchange — then sets IDLE, then frees what it took; the settlement decides inside the same masked section as a release point's decision (one function, `damage_engine_decide`; R1.11), so a release point cannot land between its reading the owed record and its setting IDLE (heap 13's lock is stock's unbounded wait, V `0x004842A8`; never taken while the word is held) | — | the word's three values; a byte "cache release owed" written by a release point | **every claim is a compare-exchange IDLE → ACTIVE** (an exchange would overwrite PENDING); a holder reads the cache pointer only after its claim; the hook, losing the claim, folds the step — still clearing the outstanding-refresh mark — and never waits; the hook never blocks while it holds the word (no send, no allocation, no free), so the ui_task's claim cannot fail; the ui_task sends its notifies after its let-down and lets the word down before it queues any present. **A release point** (FB_RELEASE and a fresh acquire on the settings path, a lapse noticed by `cfw_fb_lease_active` on whatever task calls it, mode 11) decides inside one short section with interrupts masked to the RTOS level (BASEPRI 0x30, as `ulPortRaiseBASEPRI` at `0x005FA0A4` sets it; no call inside): the word IDLE → it ends every instance and, if it releases the cache, takes the pointer, then frees after the section; ACTIVE → it records whether the cache goes and sets PENDING; PENDING → it adds its record. The result does not depend on the task's priority or on what it waits on elsewhere | the word read once, inside the section; one compare-exchange to claim, one to let down | the scratch's and the slots' words (one claimer each; their release window, `cfw_context.h` 237–240, closes the same way if the section is applied to them — not applied in C1, A.14); the cache pointer (the rider, which becomes the withdrawal inside the section); today's modes 13/14/17/18 still read the cache without it |
| engine thread and flags (no osTimer) | never deleted; the thread waits forever with nothing drawing | made from static memory in the context (cannot fail for memory); a null handle refuses that PLAY (11) and the next tries again, flags first, then the thread | per lens, per boot | the flag is set by the PLAY, RETARGET or fire handler on the ui_task (from a task the set cannot fail on a queue: V); the thread only plans, tries the gate and queues | one plan per wake (each slot's t0, P, T, d read once) | upstream's `seq_tick` keeps its own osTimer |
| the thread's gate try and queue put | — | a failed try (`FUN_00441C44(h, 0)`, the handle checked non-null first, its result read) or put (the gate given back): a fold, counted (33), the next wait one period; a gate a stock caller left held (the LVGL flush ignores its own put's result, V `0x0047377A`) stalls the engine until the next phone frame's present, counted | — | the ui_task (worker, C2's hook), the LVGL flush and three offset setters take the same gate; the type-6 path gives it with no take (V) | the try's own result | the worker's inference (`HANDOFF.md` §74 item 9), unchanged in C1: an engine refresh never reads the shadow, and the engine word covers what it does read |
| one outstanding engine refresh | cleared by the copy hook | a refresh the display task drops (panel off: the stock gate at `0x00473C96`) still ran the hook, so the mark clears | — | set by the thread, cleared on the display task | — | — |
| close-on-meet guards | — | — | pair bounding boxes; each instance's two off4 ranges (§5.6) | decided on the ui_task in stream order, under the engine word, so both lenses decide alike | the slot list read once per message | modes 12, 19, 25; modes 6, 11; PLAY over PLAY; a PLAY's capture write |
| drawing only over a Damage frame | a present on the stock path turns every drawing instance done (no notify) | — | `direct_active` and a live lease, as the copy hook already decides | display task | one read of the copy hook's own decision | the diagnostic overlay stays on top; a mode-24 hint is ignored; an unnoticed lapse (§5.6) |
| mode 25's inflate and output | the end of the message | refused (11/13); the ledger re-sends as mode 19 — on RIGHT's refusal only: a LEFT-only refusal is §5.7's class | — | ui_task only | — | mode 19's own checks reused, not copied; the lease, DRAW2 and ZWRITE checked again after the inflate (R2 A-N1, R5 A-N8) |
| notify 114: two senders — the refresh hook (display task) for a last step, the ui_task for a refusal or a CLOSE while drawing | the owed mark, by the refresh hook's send | a lost or late notify is pacing only; a last step drawn with the panel off (no refresh) is sent at the next refresh | one sequence, taken by one atomic add | an owed record per slot for the refresh hook (§5.8), one body buffer for the ui_task, which sends after its let-down (the stock sender copies before it returns); the two may reach the phone out of sequence order | the owed mark read once per refresh | field 113's sender, the same refresh hook |
| the context-survival check | — | peek only refuses (returns 0) and writes nothing; get creates a new context and writes nothing into the old block, nor frees it | the uptime-ahead shape, its miss stated (§5.9; the offline read found no boot-unique value) | runs wherever peek or get runs — from boot on every stock refresh through the copy hook | the recorded value read once, before the OS tick | every reader of the context pointer (peek's callers, get's callers) |

**A cache with no lease (stated, not built; R3.4, R5 A-N3, R6 A6-4).** A release point between a cache write's lease
check and the publication of its cache pointer (modes 12, 19, 25) leaves a cache allocated with no lease until the
next fresh acquire, mode 11 or a restart; a release and a fresh acquire both inside that window publish the cache into
the new session (the old session's size and write; its op 5 answers ALLOCATED) until its next release point. The
window is, on a session's first (allocating) write, heap 13's malloc (its mutex taken with no time limit) and the
zeroing of the whole cache — about the inflate's length for 256 KiB (modeled) — plus the entries' check pass on every
write. Under CACHE_KEEP the next acquire carries that cache, holding exactly the write the phone sent. Closing it
takes the allocation under the engine word, which heap 13's lock forbids.

### 5.12 Not in this contract yet

The default P (feel session) · a hint for small step areas (after field 34's numbers) · a string source for DRAW
(today: draw the text, then DRAW from `self`) · more than 4 instances, 8 tracks or 255 steps · LEFT's verification
(an inter-lens report: LEFT's END, its cache, and §5.7's one-lens refusals) · the pool and cache numbers (after §5.7's
readings; the cache plan now includes the phone's capture reservation) · a compression context kept across messages
(`HANDOFF.md` §68: re-measure first; it would also take mode 25's per-message inflate out of §5.7's class) ·
on-glasses compaction (§74 item 8) · a session identity on the image lane (it would close §5.9's mode-25 case and
§5.11's release-and-acquire case; new state) · a gate take around a cache write (§5.6's case; a new wait) · the
masked section for the scratch's and the slots' release windows (A.14).

## 6. v2 local input and bindings (the second flash, C2) — *contract 2026-11-18, from the C2 design pass as ruled; not built: nothing of C2 is built before C1 is flashed and its numbers are read (§6.9)*

Grades as §5: **V** read at instruction level (an address given) or in the decompile of bytes that hash SAME, **V-src**
read in the fork's source (branch `c1`), **M** measured on Adam's pair or counted in his journal, **I** inferred, **U**
unknown. The design pass's records (the challenge, the prep, the focused read, the rulings) are in the gitignored
`private/c2-design/`.

**The amendment of 2026-11-18 from the stock input model and its focused read (`C2-rulings.md` §9) is in the text
below** (change log, 2026-11-18). A sentence marked (D0) filled a detail the rulings left open; the lead ruled each as written on 2026-11-18. The focused read of the report-only entry owed before any fork stage (`C2-rulings.md` §12, subject B) is in the text too (change log, stage D1); a sentence marked (D1) filled a detail its rulings left open.

**What C2 carries, in six lines.** (1) A scroll — ids 4 and 5, ring swipes and temple slides alike — runs a bound
program on both lenses at once; a ring single tap sends a report too when the phone's `Taps` setting says `report`,
and draws a press picture as well when it says `glasses` (`phone` binds no tap). (2) A local fire draws a **local
instance** on the panel and never writes the shadow; the shadow changes only by the phone's messages in stream order.
(3) The glasses' list cursor only predicts the next fire; the phone overwrites it. (4) The phone moves its own row only
by handling a report it received, and answers the reports per list, once per batch, with COMMIT (it agrees: END written
into both shadows) or DISCARD plus its own frame (it does not). (5) SETTLE, sent after every burst and inside every idle
batch, ends local pictures the phone never heard of — the only correction that reaches a gesture LEFT alone handled. (6) Nothing on the glasses decides what a tap acts on; the act is always the
phone's, on its own row. Plus the vertical-scale draw (A2, §6.6). One new patch site (`0x00443FB0`, §6.2); contract
version stays 2; DamageCaps bit 10 (§6.8).

### 6.1 What reaches the hook (read 11-13 to 11-18 unless cited)

| fact | grade, basis |
|---|---|
| Every input event reaches the ui_task as a type-7 queue message; the loop calls `FUN_00442D86(record 0x2034DC30, len)` at `0x00443FB0`, its only caller. The loop clears its 10 KiB body buffer and refills it for every message, so the hook copies the record's 10 bytes and keeps no pointer | V (`0x00443FA4`–`0x00443FB0`, `fwread.py calls`; the refill `0x00443942`–`0x00443974`) |
| The record is `{u16 devType @0, u32 eventId @2, u32 value @6}` (10 B), after RIGHT's input manager's de-bounce and 1 s cross-device lockout. **LEFT's sync listener `FUN_0045B850` (registered on the slave branch, `0x0045EA82`–`0x0045EA88`) answers (`0x0045B9C6`) and then injects (`0x0045B9D2`); RIGHT injects its own copy only from the response listener `0x0045DD68`, on an answer whose first byte is 8 (`0x0045DE08`–`0x0045DE0E`, inject `0x0045DE5E`), and when the answer does not come it releases the record and never injects (`0x0045DD6C`–`0x0045DD70`).** So LEFT handles every gesture about one inter-lens answer before RIGHT, and a lost answer leaves a gesture that LEFT alone handled | V (`FUN_004445A4`; the listeners 2026-11-16). The answer's wait before RIGHT drops: U. How often answers are lost on his pair: U |
| **The temple touchpads' slides are event ids 4 and 5**: the gesture processor's dispatch (`0x00503172`–`0x00503226`) posts, per mask bit, id 0xD (bit 0), 0xE (1), 0 (2; ≥ 10 taps → 0x1010), 1 (3), 3 (4), **5 (5) and 4 (6)** with diffX and speed, devType 1 on RIGHT else 0 (`FUN_004C5874`) | V (bit 5 → id 5 at `0x005031E6`–`0x005031FA`, bit 6 → id 4 at `0x005031FE`–`0x00503212`). The bit names (5 SLIDE_L, 6 SLIDE_R) from the name table at `0x00503250`: I |
| **The ring's swipes are ids 5 and 4 too**: `FUN_0047269E` maps ring report type 0 → 3, 1 → 0, 2 → 1, **4 → 5, 5 → 4**, 8 → 0xE, devType 4. It drops a report whose own stamp (+7) is within 100 units of the last accepted one, as an unsigned difference, a release (type 8) exempt | V (decompile, SAME). The filter's unit: U |
| Stock's names: 0 SINGLE_CLICK · 1 DOUBLE_CLICK · 3 LONG_PRESS · **4 UPROLL · 5 DOWNROLL** · 0xD PRESS · 0xE RELEASE · 0x1010 DECA_CLICK; devType 0 TOUCH_BAR_LEFT · 1 TOUCH_BAR_RIGHT · 4 RING_TOUCH_SENSOR. 0x1010 never reaches the ui_task (the input manager sends it as `0x109`, R0.1) | V (`0x004C5892`, `0x004C58EE`, the strings) |
| **Id 4 sends the scroll message's direction 2, id 5 direction 1.** The dispatcher posts LVGL 0x44 for id 4 and 0x45 for id 5; on RIGHT the text-container adapter `0x004961E4` forwards only these two to LEFT (`0x0049632A`–`0x00496330`; `FUN_004DA382` at `0x00496396`, `FUN_00464BB2`); both lenses' `FUN_00496544` map 0x44 → 0 and 0x45 → 1 for a text container (`0x004967FE`–`0x00496810`) into the callback `FUN_00494A78`, which sends kind 2 with direction 1 for 1 and 2 for 0 (`0x00494B12`–`0x00494B30`). The message carries id, name and direction — **no source**. It is built in a later ui_task turn, after the exchange with LEFT; it is not made on LEFT (the adapter returns, "slave role", `0x004961F0`–`0x004961F6`), while the adapter's context reads "animating" (`0x0049628E`–`0x004962DA`), or when its own put fails | V (2026-11-16). It reads "animating" after every delivered notch (next row) |
| **The carrier is stock's non-scrolling text capture, and its rubber band paces the scroll message and gates the single tap.** A notch that reaches the container while its byte +0x13 is clear (content not scrollable, +0x12 = 0) calls the callback (kind 2 sent) and starts a rubber band, `FUN_004DFE50`: an LVGL animation of the container's y by ±16 px, duration 100 and reverse duration 100, that sets +0x13 ("animating") until its ready callback clears it and re-injects one queued record. While +0x13 is set RIGHT's adapter forwards nothing — **that notch has no scroll message** — and a forwarded message that meets the band at the container is pushed into its FIFO (5-byte records) and sent at a band's end, one per band end. **A single tap on RIGHT inside the band sends no click — dropped, not queued:** the page handler sends the click only when `FUN_00494610` returns 1; that function returns the adapter's result, and the adapter tests +0x13 before the event code. The double tap and the long press are sent without the adapter, so the band does not gate them. **The hook runs before all of it:** it replaces the loop's only call to the dispatcher (`0x00443FA4`–`0x00443FB0`: the type-7 test, then `bl 0x00442D86`); a tap's event 10 is handled synchronously inside that call (`0x00443028`–`0x0044302E`, `0x0045F908`, `0x004516E4`: the row below), and stock's click gate (`0x004E0F3E`–`0x004E0F44`) and the click's send (`0x004E0F68`) come after it. So it sees every record RIGHT injects, a notch with no scroll message and a tap whose click stock then drops included. Under the lease the band is not seen; LVGL runs it | V: the hook ahead of the gate — the loop's only call and the synchronous chain to the gate, settled by the focused read before F1 (`C2-F-focus.md` B, answer 4) and re-read by stage D1, 2026-11-18; whichever handler event 10 reaches, it is reached through that call, so the hook's place does not rest on the I below. The band's fields (`lv_anim_init` `0x004503E2`–`0x004503F8`; duration `0x004DFF7A`–`0x004DFF7E`, reverse duration `0x004DFF96`–`0x004DFF9A`, reverse delay 0 `0x004DFF9E`–`0x004DFFA2`); the start for 0x45 `0x004E06F2`–`0x004E0754`, for 0x44 `0x004E0838`–`0x004E0896`; the set `0x004DFFC0`, the clear `0x004DFD66`, the re-inject `0x004DFD6C`–`0x004DFE32`; the push when the band cannot be extended (`0x004E056A`, `0x004E061C`); the adapter's test `0x0049628E`–`0x004962DA` and its first argument the container (`FUN_00494610` `0x004947C6`–`0x004947D0`, the bind `0x004943BE`–`0x004943C0`, `0x004952B8`–`0x004952C2`); the click's gate `0x004E0F3E`–`0x004E0F44` (re-read 2026-11-18); the double tap `0x004E0FF8`–`0x004E10BE`, 0x42 and 0x43 `0x004E0E14`, `0x004E0E38`. `FUN_00494610` returns the adapter's result (`0x004947D2`): for a tap on RIGHT 1 (`0x0049637A`), 0 while the band runs (`0x004962D8`), on LEFT 0 (`0x0049623C`). **I:** the band's length, ≈ 200 ms plus one LVGL timer period (the LVGL tick read as 1 ms, not read; not measured on any build); that the k-th queued record goes k bands later; that `FUN_004E0D3A` is the callback (as the row below). **M** (his phone log `private/journal/phone-log-masked.txt`, two days, APK 0.67; until 2026-11-18 the log line did not tell a gesture made on the phone app's own screen from one from the glasses, so these counts are of both): no scroll-to-scroll gap under 224 ms in 565 scroll messages — still a floor for the glasses' pacing, since a drag on the phone's screen gives notches 17–50 ms apart; 0 of 467 taps within 690 ms after a scroll message (the least 696 ms), so the tap gate has not been reached in his use so far. **No scroll message from the glasses has been timed on the C1 build yet.** Grades and the claim: `CLAIMS.md` Input |
| **A single tap's report is the page's SysEvent, sent inside the hook's own turn.** Id 0 posts LVGL event 10 synchronously: `add r2,sp,#0x14; movs r1,#0xa; bl 0x0045F8FC` at `0x00443028`–`0x0044302E`; `FUN_0045F8FC` calls `FUN_00451670` (`0x0045F906`–`0x0045F908`), which builds the event on its stack and calls `FUN_00451A6C` directly (`0x004516E4`) — no queue. The page handler `FUN_004E0D3A` answers code 10 with `FUN_004DA16A(0, 0, 0, 0, 0, src)` at `0x004E0F68`, the source a u16 read through the event data's `+0x10` (`0x004E0F46`–`0x004E0F5A`), gated on the page's `+0x34 == 1` and `FUN_00494610` returning 1 (`0x004E0F30`–`0x004E0F44`). The phone receives it as a gesture of type 0 with a source | V for the path and the handler (SAME). That `FUN_004E0D3A` is the callback event 10 reaches on the carrier: I (no BL caller, no literal reference). `FUN_00494610`'s answers, and the click it withholds inside a rubber band: the row above |
| **One transmit queue, one 500-tick put.** A Damage package (`FUN_00475B14(1, 9, …)` → `FUN_0047564E(0, 0, …)` at `0x00475C10`–`0x00475C14`), the scroll message and the click (`FUN_004DA16A` → `0x00475C1A` → `FUN_0047564E(0, 1, …)` at `0x00475D54`–`0x00475D58`) each allocate a block and put it into one queue with a 500-tick wait (`mov.w r3,#0x1F4` at `0x004759BA`, put `0x004759C4`). A failed put frees the block and returns −1 (`0x00475A18`–`0x00475A20`); success returns 0 (`0x004757C0`); `FUN_00475B14` returns that result (`0x00475C14`–`0x00475C18`), or 8 on its own early refusal (`0x00475BFE`). So a report put in the hook precedes its gesture's scroll message by at least one inter-lens exchange, and a tap's click by being put first in the same turn, whenever its own put succeeds (and when stock sends that message at all: the rubber band row above) | V (2026-11-16; the return values 2026-11-18). One early path returns 0 without reaching the put (`0x00475B66`, when `FUN_004487AC` returns non-zero; read in part): whether a task can take it, U |
| The EvenHub page is in front when `FW_MODE(UI_CTX)` reads 0xE0 (`APP_EVENHUB`) — the test the inherited `ring_release` makes inside the dispatcher | V-src (`gesture_fwd.c` 76–79). That the read is safe before the dispatcher runs: I (`FUN_0045F8E6` unread) |
| The hook, every image message (modes 26, 27, 28, the cache writes) and the stock dispatcher run on the ui_task, one message at a time (priority 44, §5.2), so a fire and a phone message can race in order and across the phone's round trip, never mid-instruction on one lens. Time slicing is on at 44 | V (`FUN_004437E0`'s loop: `0x00443934`, `0x00443F1C`, `0x00443FB0`; time slicing `0x0045516E`, `HANDOFF.md` §81) |
| The long press (3) and its release keep the inherited sites inside the dispatcher (`0x00442E92`, `0x004431C2`); the hook wraps the dispatcher and leaves them as they are | V |

### 6.2 The hook and the fire

- **Site:** `bl 0x00442D86` at `0x00443FB0` → `damage_input_hook(record, len)`, C2's one new site, on the ui_task's
  event loop. It runs on every touch from boot (accepted 2026-11-17; `FORK.md` §3.1: an event loop is not a boot path,
  as `0x00473D80` was read). It finds the context with **peek, never get** (get creates a context and writes it), so
  before the first Damage message it is one lookup and the stock call.
- **It never consumes:** every path ends in `FUN_00442D86` with the unchanged record and length, as a tail call
  returning its result, after its own work — so the stock path and the phone's scroll message and click stay exactly
  as today.
- **The fire, in order** (each lens, on its ui_task, inside the hook):
  1. `peek`; no context → the stock call.
  2. Copy the record's 10 bytes. A hint read without the engine word: BIND (flag bit 4) armed, `bind_live` set (§6.3),
     and an entry matching the record — id 4 or 5 against a scroll entry (devType any), or id 0 with devType 4 against
     a tap entry — the lowest-numbered matching entry (at most 8 compares). Otherwise the stock call, no report.
  3. The EvenHub page in front (§6.1). Otherwise the stock call, no report: the gesture belongs to a stock page.
  4. **S := S + 1**, one atomic add on the lens's u16 sequence (it wraps): every matched event on the EvenHub page
     takes one, whatever follows, so both lenses number every matched event alike while their tables agree.
  5. The lease (`cfw_fb_lease_active`; a lapse it notices is a release point on this task, §5.6). Lapsed → report
     refused **3**, then the stock call.
  6. Claim the engine word (compare-exchange IDLE → ACTIVE, §5.11 row 3). Fails → report refused **14**, then the stock
     call. A guard only: the display task never blocks while it holds the word and at priority 45 is never preempted
     by the ui_task, and the stock start-up zeroes the context's slot word at every reset (`HANDOFF.md` §87).
  7. Under the word, each read once: DRAW2, ENGINE, BIND and `bind_live` — any clear → refused **15**; the entry and
     its list (item table, count, cursor, pitch, mark, the S of its newest fire).
  8. The list's cursor lost (0xFFFF, §6.3) → refused **18**, nothing drawn.
  9. The new cursor c' = cursor + step (step 0 for a tap). Outside 0..count−1 → **edge** (§6.4). The parameters:
     Pi = arg (kind 0) · the item table's entry at c' + arg (kind 1; no table, or c' + arg outside 0..count−1 → edge)
     · c' × pitch + arg (kind 2, computed in s32; outside s16 → refused **12**).
  10. §5.5's checks from the walk onward, on (the entry's period, prog, P): the program record and every record and
      table it names inside the cache and well formed, no `self` source or backdrop (**5**) → areas (**2**) → a `none`
      backdrop's area (**2**) → every step's values after substitution (**12**; with §6.6's H rule) → the thread and
      flags at first use (**11**: a null handle; both are made from static memory, §5.2).
  11. The close set: the slot's instance and every open instance whose areas meet, in slot order. **A tap fire whose
      close set holds a local instance is refused 17, nothing closed** (closing an uncommitted scroll would show the
      old row around the press). A local instance here is any open slot flagged local — drawing, holding at d = T,
      or done: a present on the stock path turns it done and leaves the flag (§5.3), which only its slot's emptying
      or a COMMIT's adoption clears — so a done local refuses a tap fire 17 as a drawing one does, and the
      report-only fire's test below reads it the same way (D1). Otherwise each is closed — a drawing one's outcome-1 body captured for the ui_task's
      send (§5.8), a done one silently, **and a local instance of this fire's own slot silently whatever its state**
      (this fire's 115 names the slot; nothing waits on a local body, §6.5) — and each close sets the panel-stale mark
      (§5.3).
  12. Open the local instance (§6.4): tag S, t0 now, d 0, the entry's period, its first read range from step 10's walk,
      no capture range, the hook's entry stamp (field 37). The list's newest S := S; its cursor := c'.
  13. Let the word down, settling a PENDING release point as every holder does (§5.11 row 3).
  14. Set the engine flag (from a task the set cannot fail on a queue, §5.2): C1's thread draws step 1 by its own try
      of the gate (§5.2) — the fire never takes the display gate.
  15. Send (RIGHT only; LEFT's sender sends nothing): the captured 114 bodies, then the report (§6.5). A non-zero
      result of the report's send adds 1 to field 38; nothing is retried.
  16. The stock call (tail call) with the unchanged record and length.
  Every exit after step 6 lets the word down before its send (refusals 15, 18, 12, 5, 11, 17 and the edge alike); the
  word is down before the sends and before the stock call (a lease check inside the stock long-press site can run a
  release point on this task). No send, allocation or free while the word is held.
- **A report-only tap entry's fire** (§6.3, `Taps · report`) runs steps 1–8 as a tap fire's, so it has a tap's
  refusals before its program would be read (3, 14, 15, 18), and step 11's test: a local instance in its slot (a
  done one included) → refused 17. Otherwise it computes no parameters, walks nothing, closes and opens nothing, leaves the list as it is
  (cursor and newest S) and sets no engine flag (steps 9, 10, 12, 14 do nothing); it lets the word down and sends its
  report, outcome 0 with no fields 10–14 (D0).
- **Waits:** none of its own. Inherited: each send's 500-tick put (stock makes the same put for every gesture's scroll
  message and click); a release point's heap free at step 5 (stock's heap lock, an unbounded wait, `0x004842A8`, outside
  the word); the stock handler's own inter-lens send after the hook.
- **Cost:** a fire walks its program and every record it names to their ends (an RLE walk, proportional to the record;
  field 16 measures it); the phone keeps staged records small. Before BIND is armed the hook costs a lookup, a flag
  test and the stock call (modeled only).
- **What never runs on the glasses:** a draw for a double tap, a long press, 0xD, 0xE or a temple tap; a write of the
  shadow by a fire; a take of the display gate; an allocation; a `self` capture; a fire outside the EvenHub page, the
  lease, BIND, ENGINE and DRAW2; any decision about what a tap acts on.

### 6.3 Lists and bindings — image mode 27

`[27][sub]…` on the image lane; both lenses run it in stream order; alone or as a mode-8 sub-message; refused 8 inside
a self-test step (at the step's top level or in a batch the step carries) — the container's own list decides first,
whatever the message's length, as §5.5 states for mode 26. **Not gated:** it takes no display gate and presents nothing
(in a batch the batch's present is the batch's); it burns no fid. The mode byte's high bit is ignored.

| sub | message body after `[27][sub]` | what it does |
|---|---|---|
| 0 SET | `entry u8 (0..7) · devType u8 · eventId u8 · list u8 (0..3) · step s8 · slot u8 (0..3) · period u8 (16..128) · prog u16 (off4) · 4 × {kind u8 (0..2), arg s16}` (21 B) | writes one entry. **Two shapes only:** a scroll entry — eventId 4 or 5, devType 0xFF (any), step ≠ 0; a tap entry — eventId 0, devType 4 (the ring), step 0. Anything else is refused 12. A tap entry whose prog is 0xFFFF (LIST's "none") is **report-only**: it names no program and its fire draws nothing (§6.2); its period and parameters are checked as any SET's and not used (D0). **An entry's presence is its own mark, never `prog`:** a report-only entry is a live tap entry whose `prog` is 0xFFFF, read under the word. Parameters as §6.2 step 9. **One slot per list:** an entry whose list another entry binds to a different slot is refused 12 (a tap entry shares its list's slot) |
| 1 CLEAR | `entry u8` (0..7, 0xFF all) (1 B) | removes the entry (0xFF every entry); lists stay |
| 2 LIST | `list u8 (0..3) · items u16 (off4 of a table of count u16 off4 entries, or 0xFFFF none) · count u16 (1..1024) · shift s16 · pitch s16 · mark u8` (10 B) | the staged range, **always applied**: items, count, pitch and mark replaced; the cursor becomes old + shift when that is inside 0..count−1, else **lost (0xFFFF)** — old is the list's cursor (0 for a list that is not live, count 0); a lost cursor stays lost. A fire on a lost cursor is refused 18 (§6.2 step 8); DISCARD and SETTLE set an absolute cursor and clear it (§6.5). The newest S is not touched. There is no absolute form: a resync is DISCARD's job |

**Check order (the reason recorded is part of the contract):** the container's list (8) → length (1; SET 23 B, CLEAR
3 B, LIST 12 B with the two header bytes; an unknown sub, 3..255, must be 3 B) → the lease (3) → **the engine's busy
word claimed (14)** → DRAW2 (9) → BIND (15) → values (12: every range in the table above, the two entry shapes, one slot
per list; an unknown sub here) → LIST: the item table `[items·4, items·4 + 2·count)` inside the cache (5) → SET: the
program record's own structure — §5.4's header, tracks and segments, enumerations and reserved bytes — and no track's
backdrop or segment `self` (5); none for a report-only tap entry, while a scroll entry naming 0xFFFF fails this check
(5: no program fits there) (D0). What depends on the parameters (the records they name, areas, step values) is checked
at every fire (§6.2 step 10), so a local draw depends only on the cursor and the cache. Only then is anything written.

- **Mode 27 claims the engine word** and makes its writes under it; it lets the word down on every exit (refusals
  included). It joins §5.6's fold case (R2 A-S1) with COMMIT, DISCARD, SETTLE and the fire (§6.4).
- **The empty table.** `bind_live` (1 B in the context) is 0 when the context is made and after every release point:
  one store inside the masked section's IDLE branch and in the holder's settlement (`damage_engine_decide`, §5.11
  row 3), beside the store that sets S to 0. **The first mode 27 accepted while `bind_live` is 0 first empties every
  entry and every list** (count 0, cursor 0, mark 0, no newest S), then applies, then sets `bind_live` to 1. A refused
  mode 27 changes nothing. Arming or disarming BIND clears nothing (the table and S stay; with BIND clear every input
  passes through).
- **A refusal of mode 27 sends no notify:** it is recorded in telemetry 23–25 (§4), sticky until the next refusal; the
  phone re-sends the table when it reads it (§6.7 P1).
- The entries, lists, `bind_live` and S live in the context, per lens; each lens applies mode 27 in its own order
  against its input (§6.4, both lenses).

### 6.4 The local instance

- **What it is:** one slot of C1's instance table (§5.2–§5.3) flagged **local**, tag S, t0 the fire's OS tick, d 0, the
  entry's period P, the program's T, a first read range and no capture range. The thread plans it and the copy hook
  draws it under the engine word exactly as any instance; **it writes nothing into the shadow** — no END at the fire.
  Its 114 bodies carry tag S, END ordinal 0 and field 8 = 0 (no END was written for it) until a COMMIT adopts it.
- **HOLD.** At step T a local instance **stays drawing at d = T and owes no 114**: the thread's plan skips it (d ≥ T);
  the preserve path skips it (k ≤ d) and leaves the framebuffer holding it; the direct path redraws step T at every
  phone frame's present, so while one is open every phone present goes whole (a whole transfer, 2.0 ms median / 5.3
  p90, M §5.3, plus step T's drawing time, field 34). Field 30 counts it as drawing.
- **What ends a local instance:**
  - COMMIT with its S (§6.5): it becomes ordinary — a drawing one runs on to step T and sends its own outcome-0 body; a
    holding one turns done, no body;
  - DISCARD or SETTLE whose rule names it (§6.5): stopped, outcome 1;
  - the next fire in its slot (closed with no body, §6.2 step 11), or a fire from another slot, a PLAY or a COMMIT
    whose areas meet it (their close pass, outcome 1) — except that a tap fire never closes a local instance (refused
    17, §6.2 step 11) and a report-only fire closes nothing;
  - a phone PLAY or CLOSE of its slot, a live keyframe (mode 6), a cache write meeting its first read range: outcome 1,
    as for any drawing instance (§5.6);
  - mode 11 and every release point: emptied, no notify (the settlement);
  - a present on the hook's stock path: turned done, no notify, before the stock copy (§5.3) — never drawn over stock
    content.
  With no phone renewals the lease lapses within 87.9 s and the next present takes the stock path, so nothing needs a
  hand action.
- **The fold** (§5.6's R2 A-S1, wider): a present queued before a ui_task message that holds the word — a cache write,
  mode 27, COMMIT, DISCARD, SETTLE, a fire — and run while it holds it, folds on the direct path: the copy put the
  shadow there, so the lens shows the **pre-gesture row**, and since the plan skips a HOLD nothing redraws it until the
  next present that draws: COMMIT's (whose END is the same picture), the answer's frame, or SETTLE's — about one round
  trip, at most Q (§6.7). Counted in field 33. A gate take around the word's holders would close it; it is a new wait
  and is not built.
- **The staged edge:** a new cursor outside 0..count−1, or a kind-1 parameter naming an item outside the list (or a
  list with no item table): nothing is drawn (no bounce: the end of what is staged), the cursor stays, the report says
  `edge`, the stock path delivers the gesture and the phone refills the list or ends there. **Main's wrap is this
  edge:** Main draws blanks past its ends, so it is staged with no wrapped rows, and the move from its top row to
  Settings is drawn by the phone a round trip later, as today (§6.12).
- **The tap press.** A tap entry has step 0, so **the cursor never moves.** Its program draws a press over the row at
  the lens's cursor (kind 2 = cursor × pitch, the row's place in the staged strip; kind 1 its item), two or three steps,
  then HOLD until the phone's answer or a SETTLE ends it. **It depicts "pressed" only — never the act's result** (no
  opened, sent, deleted or toggled picture): the program is the phone's, and this is its rule. A report-only tap
  entry draws nothing and leaves the cursor and the list as they are (§6.2).
- **Both lenses.** LEFT fires about one inter-lens answer before RIGHT (§6.1; the onset skew, U in ms; t0 differs by
  that). A lost answer leaves a fire on LEFT that RIGHT and the phone never hear of: LEFT's cursor one ahead and its S
  one ahead for the rest of the lease, and a local picture RIGHT lacks — **from the dropped answer to the next SETTLE
  the two lenses can show different rows.** SETTLE ends it (§6.5); LEFT cannot report it, and a counter of RIGHT's
  dropped answers is not built (§6.12). **Decisions one lens alone can make in a fire** join §5.7's class: the lease
  deadline (each lens's own), the flags (FLAGS_SET runs on each lens's settings task, unordered with its ui_task), a
  mode 27 refused on one lens for a shared reason (1, 3, 14, 15), the order of input against image messages after a
  lost answer. The phone arms BIND inside the session start, before any mode 27, as it arms DRAW2 (§5.7).
- **Latency.** Step 1 is drawn by the copy hook at the engine refresh the thread queues after the fire's flag: the
  thread runs at the ui_task's priority, so it waits for a time slice (about one tick, I), and a try that finds the gate
  held folds and waits one period (§5.2). **The guide:** median ≤ 10 ms and p90 ≤ 20 ms from the hook's entry to step 1
  drawn (field 37), modeled until measured; the verdict is his use in the trial week, with the figures read beside it.

### 6.5 The applied-state report and the phone's answers

**Notify field 115 DamageApplied (RIGHT only)**, `G2SettingPackage{1: 3, 2: 0, 115: …}`, uint32 varints:

| field | meaning |
|---|---|
| 1 | S (§6.2 step 4) |
| 2, 3 | devType, eventId (the record's) |
| 4 | the entry |
| 5 | outcome: 0 drew · 1 edge · 2 refused (a report-only tap entry's fire that is not refused says 0, nothing drawn; D0) |
| 6 | reason (outcome 2): 3, 5, 11, 12, 14, 15, 17, 18 |
| 7 | the list |
| 8 | the cursor after the fire (unchanged unless drew on a scroll; 0xFFFF lost) |
| 9 | the slot |
| 10–13 | P0..P3 (their 16 bits) |
| 14 | prog (off4) |
| 15 | uptime (OS ticks) at the fire |
| 16 | the fire's µs inside the hook: from its entry to the let-down (the sends and the stock call excluded) |
| 17 | the list's mark (the last LIST this lens applied) |

**Bodies:** refused 3 or 14 (decided before the read under the word) carry fields 1, 2, 3, 5, 6 and 15 only. Every
other report carries 1–9 and 15–17; fields 10–14 only for outcome drew and reason 17 of an entry with a program (the
parameters were computed; a report-only entry's never carry them).
One report per S, sent after the let-down and after the fire's captured 114 bodies (§6.2 step 15). 115s come from one
task through one FIFO; 114 bodies from the display task may interleave with them — the phone keys 115s on S and 114s on
(slot, tag, ordinal). The vectors' `"N"` masks fields 15 and 16.

**Notify 114 gains outcome 3, committed** (§5.8's fields): one body at a COMMIT, from the ui_task after its let-down,
carrying fields 1–11 — 3 the tag = S, 5 END ordinal 0, 6 and 7 the adopted local instance's steps drawn and passed over
so far (0 and 0 when none was adopted), 8 the END CRC (§5.6), 9 its largest step µs, 10 = 0. An adopted instance with
6 + 7 below T sends its own outcome-0 body at step T, as any instance, carrying the COMMIT's CRC; the phone matches it
by (slot, S, 0) and waits for no body (a missing one is pacing only, §5.8).

**Mode 26 gains three subs** (a bit-10 build; an unknown sub is now 6..255, R1.8's rule). Check order, the prefix as
§5.5 for every mode-26 sub: the container's list (8) → no shadow (4) → length (1; COMMIT 18 B, DISCARD 8 B, SETTLE 10 B
with the two header bytes) → the lease (3) → the engine's busy word (14) → DRAW2 (9) → ENGINE (15; BIND is not
checked) → values (12: slot 0..3, also 0xFF for DISCARD and SETTLE; list 0..3, also 0xFF none for DISCARD and SETTLE;
a named list's cursor below its count) → COMMIT only: the program walk (5; `self` 5) → areas (2) → a `none` backdrop's
area (2) → step values (12). A refusal sends its outcome-2 114 body as every mode-26 refusal does (§5.8).

| sub | message body after `[26][sub]` | what it does |
|---|---|---|
| 3 COMMIT | `slot u8 · S u16 · list u8 · cursor u16 · prog u16 · P0..P3 s16` (16 B) | PLAY's close pass over the **other** slots (instances whose areas meet); **END(prog, P) into the shadow, always**, and RIGHT's CRC of the areas right after it; in its slot: the local instance tagged S becomes ordinary (§6.4), a local one tagged newer (`(int16_t)(tag − S) > 0`) is left drawing, anything else is closed (outcome 1 if drawing); **the list's cursor := COMMIT's cursor only if S is that list's newest fire**; one 114 outcome 3; presents; a close or an adoption sets the stale mark, so that present goes whole |
| 4 DISCARD | `slot u8 (0xFF all) · S u16 · list u8 (0xFF none) · cursor u16` (6 B) | in each named slot, **stops every local instance tagged at or after S** (`(int16_t)(tag − S) ≥ 0`), drawing or holding, outcome 1 each (a done one is emptied silently); ordinary instances are untouched; sets the named list's cursor (clearing lost); presents; a stop sets the stale mark |
| 5 SETTLE | `slot u8 (0xFF all) · S u16 · list u8 (0xFF none) · cursor u16 · age u16` (8 B) | in each named slot, stops each local instance tagged at or after S **that has held its last step for at least `age` OS ticks** — d = T and `now − (t0 + (T−1)·P)` ≥ age as an unsigned 32-bit difference — outcome 1 each; drawing ones, younger ones and ordinary ones are untouched; then sets the named list's cursor (clearing lost) **unless a local instance tagged at or after S is still drawing in a named slot after the pass**; it presents only when it stopped one (alone, a SETTLE that stopped nothing queues no present, so the image worker marks the panel stale, §4, and the next hinted present goes whole). It holds no state of its own: t0, P and T are the slot's |

**Batch order.** In every batch the phone sends, mode-26 subs 3–5 and mode 27 come **after every drawing
sub-message**: a refused sub-message ends its batch with no present (§4; V-src `zlib_glue.c` 626, 629), so a refusal of
theirs loses only the batch's present — which RIGHT's refusal body reports — and never the frame, which is then in
both shadows. A cache write (12, 19, 25) cannot ride a batch at all (mode 8 refuses it, 8), which fixes the stream
order of §6.7 P9. Because END lands after the frame, the phone sends a COMMIT only in a batch none of whose drawing
subs meets the slot's areas (§6.7 P3's per-batch answer). **This closes a defect of the text as first placed on
2026-11-18:** it answered each adopted report with a COMMIT in the next batch, so a COMMIT riding a batch whose drawing
subs met its areas wrote END over that frame — Main's list over a window just opened, or over a modal — on the lens and
in the phone's model alike.

**Why a lost, late, duplicated or reordered message cannot move what a tap acts on to a row the phone does not hold.**
The act is always the phone's, on its own row (§6.7 P11). Its row moves only when it handles a report it received or
acts on its own. A lost 115 leaves its row; a later 115 or the stock message, when stock sends one (§6.1's rubber band),
is handled by P3 and P5, and the answer's DISCARD or SETTLE ends the lens's orphan. A lost COMMIT or DISCARD is a
missing ack or a refusal body: a divergence and a keyframe (§5.6). A 115 cannot arrive after its own gesture's stock
message (§6.1). On the glasses COMMIT, DISCARD and SETTLE are keyed by S, so the two lenses applying one on different
sides of a fire reach the same shadow, and DISCARD's and SETTLE's absolute cursor realigns them. With the tap option,
a tap is acted on only when its report names the list in front and the mark the phone holds and its cursor is the
phone's row, or differs from it only by steps the reports the phone knows it missed explain (§6.7); a click acted on
as today (`Taps · phone`, or `report` with the tap's 115 lost) acts on the phone's row, and the residual is stated
there.

### 6.6 The vertical-scale draw (A2, carried by C2)

- **Mode 28** (`0x9C` per lens): `off4 u16 · x s16 (or xL · xR) · y s16 · options u8 · H u16` (9 B, or 11 B per lens)
  — the v2 record drawn `H` rows tall (1..2048): output row r takes source row `⌊(2r + 1)·h / (2H)⌋` (fits u32 for
  r < 2,048 and h < 65,536), columns as they are; clip, options and transparency as mode 17; it takes the gate as mode
  17 does (it joins the gated modes, the batch's list and the step's list). Order: no shadow (4) → length (1) → the
  lease (3) → DRAW2 (9) → H (12) → the record (5). Allowed in a batch and in a self-test step.
- **DRAW's h0, h1, anchor** (zero in C1, §5.4): the output height eased from h0 to h1 (both 1..2048, or both 0 =
  unscaled); anchor 0 top · 1 centre (`y + ((h − H) >> 1)`) · 2 bottom (`y + h − H`), h the source's height. On a
  bit-10 build §5.5's "h0/h1 = 0" becomes: h0 and h1 both 0, or every step's eased H (after substitution) inside
  1..2048, else 12 — at a PLAY, a COMMIT and a fire alike; on RETARGET's restart path H is clamped to 1..2048 as levels
  are (R1.10). The wheel's drum (`MOTION.md` W1/W2) and Pulse use it; the icon grow (M2) was cut on 2026-11-18 (Adam:
  no size change in Main's focus motion). `tools/verify.py` step 7 is run again on the grown engine function.

### 6.7 The phone's rules (built in its transport and shell; none of them is firmware)

**Scrolls.**
- **P1 start.** At a session start with stage 4 in force (§6.8), BIND is armed inside the start before any image-lane
  message that needs it (its rung recorded first). Then, in stream order after the warmup keyframe, one batch (a start
  gate like the warmup): CLEAR 0xFF, one SET per entry of the list in front, one LIST per list with a fresh mark. S_heard := 0 on a fresh acquire, := field
  36 on a carried lease (the start's own read); the arming uptime := field 2 of the reply to the FLAGS_SET that armed
  BIND. The warmup keyframe closes every instance, local ones too (§5.6). **A refusal record naming mode 27** (fields
  23–25, read once per telemetry record) makes the phone re-send the whole table — CLEAR 0xFF, the SETs, the LISTs with
  a fresh mark — in its next batch, journaled. Without DamageCaps bit 10, or below stage 4, the phone sends no mode 27,
  no COMMIT, DISCARD or SETTLE and applies scroll messages and clicks as today: byte for byte today's start. The start's
  bytes are pinned per stage and per `Taps` value.
- **P2 one ordered flow.** A 115 enters the shell through the same ordered event flow as the stock input events, in
  arrival order, so a stock message is handled after every 115 that preceded it on the link.
- **P3 a scroll that drew.** The phone adopts the 115 only if all hold: it is fresh (P8); field 7 is the list in front
  with no modal or popover over it; field 17 equals the phone's current mark for that list; fields 10–14 equal the
  phone's own prediction (the entry's program and P0..P3 from its own staged list at the 115's cursor); and the 115's
  cursor differs from the phone's row by at most (n + 1) × |step|, n the owes this 115 added for missing reports (P5).
  **Adopted:** row := the 115's cursor (one working-copy op). **Otherwise:** it applies the gesture itself as today.
  **The answer goes per bound slot, once per batch** (not per report). A slot is answered in a batch when a report of
  it was handled since its last answer or something else moved its row since (D0). The answer is **COMMIT(slot, S,
  list, cursor, prog, P) of the newest adopted report only when every report of the slot handled since its last answer
  was adopted, nothing else moved the phone's row since (P4's application, P5's unreported scroll message, a refused
  report applied as today), and no drawing sub of the batch meets the slot's areas once END is applied to the model;
  otherwise DISCARD(slot, A + 1, list, row) after the drawing subs**, A the last S the slot's previous answer covered
  (S_heard at the session start) — the frame that draws its row rides the same batch, so the lens goes from the local
  picture straight to the phone's, never back through the old one. **Every report handled before a batch is answered
  by that batch's answer.** DISCARD from A + 1 stops every local instance opened since the last answer on both lenses,
  whatever each lens did with the later fires — one whose report was lost included — and writes nothing into the
  shadow. A tap's report is never committed: a batch that answers one sends the slot's DISCARD (the tap rule below)
  (D0). A batch that also carries a LIST for the slot's list answers with DISCARD, placed after the LIST and naming
  the row in the LIST's staging (D0). This rule replaces P4's and P5's own DISCARDs for a bound slot.
- **P4 edge and refused.** The gesture is applied as today, so the slot's answer in that batch is a DISCARD (P3); a
  LIST rides the batch when the phone refills the list. A refusal 5 or 12 of a fire is journaled with its reason.
- **P5 the echo, counted.** Every 115 the phone handles owes one stock message of its kind (a scroll message for ids 4
  and 5, a click for id 0). **An owe of either kind may never be paid:** stock sends no scroll message for a notch
  inside its rubber band and sends a queued one at a band's end, and sends no click for a single tap inside it (§6.1).
  A 115 showing g missing S since S_heard adds max(0, g − u) owes of either kind, where u counts the stock messages
  applied with no owe since S_heard (each already stood for one missing report) — except a click from a tap that
  matched no entry, which stood for none (the click, below; D1); then u := 0 and S_heard := S. A stock
  message consumes an owe of its kind, else one of either kind. **With none it is unreported** while the phone holds an
  entry of its kind for the list in front (SET sent, no CLEAR since): a scroll message is applied as today and u += 1 —
  it moved the phone's row, so the bound slot's answer in its frame's batch is a DISCARD (P3); a click, see the `Taps`
  setting below. With no such entry it is unbound and applied as today, nothing more. Owes and u clear at every SETTLE
  sent and at a session start; until then an unpaid owe can swallow one later unowed stock message — an unreported
  scroll message, which the next report's cursor (P3) or the SETTLE repairs, or a click whose 115 was lost, which is
  then neither acted on nor journaled `no-report`. The phone never waits for a stock message after a 115.
- **P6 gaps** are P3's bound: a jump larger than the missing reports can explain is not adopted.
- **P7 SETTLE.** A burst is open from a report until the SETTLE sent alone **Q after the last report with every 115
  answered** (P3): SETTLE(0xFF, S_low, the bound list in front or 0xFF, its row, age). **SETTLE names the lowest S
  since the last SETTLE**, so a local instance left open below S_heard + 1 (its report lost, and a later fire that drew
  nothing or a report of another slot past it) is reached: S_low is S_heard + 1 as it stood when the phone sent its
  last SETTLE, and S_heard + 1 at a session start (D0). While BIND is in force and no burst is open, **every batch the
  phone sends carries one SETTLE** with the same fields — under a cover too, where it names no list (12 B with its
  length; the compositor keeps one sub back for it, as for a CLOSE while an instance is open, R2 B-N5) — so a gesture
  LEFT alone handled at idle is ended by the next frame. Constants: `age` ≥ the round trip's p90 plus one period; Q ≥
  `age` plus the longest bound program's (T − 1)·P; and **Q's floor** is the p99 of "a burst's last 115 → that burst's
  last scroll message" in the trial's journal (both are logged), so a scroll message the band held back still meets
  its owe instead of being applied after the SETTLE as an unreported notch — until it is measured, Q ≥ `age` +
  (T − 1)·P covers it (I) (values after §6.9). A fresh fire crossing a SETTLE has held for less than `age`, so it is
  untouched and gets its own P3 answer.
- **P8 stale reports.** A 115 whose S is not after S_heard (signed 16-bit), or whose uptime precedes this session's
  arming, is dropped and journaled.
- **P9 writes, in stream order.** No PLAY into a bound slot. Cache writes cannot share a batch (§6.5), so: records a LIST
  will name are written first, into free cache space; a record or program a live list or entry names is not overwritten
  in place while a burst is open; a row whose index or identity changes moves through fresh space and a LIST with a new
  mark, a program through fresh space and a SET, and the old space is reused only after that LIST or SET in stream order
  (both lenses apply it before the reuse); a row's picture updated in place while no burst is open keeps its mark.
  COMMIT, DISCARD and SETTLE only while the lease is held and BIND is in force — never in Silent Mode, never into a
  released lease. Before a mid-session disarm of BIND (the probe path): DISCARD(0xFF, S_low, 0xFF, 0) after every 115
  heard is answered, waited to its ack (S_low as P7's, so it reaches what a SETTLE would; D0); without one the disarm
  is not sent and a note says so (§5.8's rule for ENGINE). At a carried-lease start the start's FLAGS_SET may clear
  BIND with no DISCARD (the warmup keyframe closes every instance, R1.17).
- **P10 the mirror.** A bound list's areas are unknown on the panel from the first report of a burst to the SETTLE that
  ends it; the model applies COMMIT's END only, in stream order; **the batch's diff is taken with COMMIT's END
  applied**, so an adopting batch does not send the list's area again; the truth oracle on the shadow stays exact.
- **The bound list is the list in front.** The phone keeps entries bound only to the list in front: it CLEARs a list's
  entries in the batch that draws anything over it or in its place (another window, a modal, a popover, the keyboard)
  and SETs them again in the batch that brings it back. A fire that lands before the CLEAR fails P3's "in front" and is
  answered with DISCARD.

**Taps.**
- **P11 the act is the phone's,** on a row of its own staging, always. Every irreversible act opens a CONFIRM worded by
  the phone from its own data, Cancel at rest (`CONTRACT.md` §4.3, `DESIGN.md` §1.7); taps on a confirm go through the
  phone exactly as today.
- **The `Taps` setting:** a Settings row beside `Long-press` — `phone` · `report` · `glasses` — kept on the PC record
  with the phone's working copy; **its default is `report` (the lead's recommendation; Adam's call at the build's
  start)**, `phone` the other candidate. It has effect only on a bit-10 build at stage 4. **`phone`:** no tap entry is
  SET; a click acts on the phone's row as today. The residual: a tap inside the window between a lost scroll report and
  its correction acts on the phone's row, one before what the lens showed; an irreversible row still opens a confirm
  naming its target. **`report`:** one report-only tap entry (eventId 0, devType 4, step 0, the list's slot, prog
  0xFFFF; §6.3) beside the scroll entries: the lens draws nothing, the phone acts by the tap rule on the 115, and the
  click is its echo. **`glasses`:** one tap entry with the press program (eventId 0, devType 4, step 0, the list's slot,
  the press program): the press picture too. **Switching** mid-session sends, in stream order, SET of the tap entry in
  its new form (on, or between `report` and `glasses`) or CLEAR of it (off); nothing else changes — no FLAGS_SET, no
  re-arm, no keyframe. **A tap 115 whose form disagrees with `Taps`** — fields 10–14 carried under `report`, absent
  under `glasses` — re-sends the tap entry's SET in its current form in the next batch, journaled (P1's re-send path,
  here for a SET that did not land); when that batch covers the list, its CLEAR stands instead and the batch that
  brings the list back SETs the entry (the bound list, above) (D1). The form is read only on outcome 0 and refused 17, the two bodies that carry
  10–14 for an entry with a program (D1); under `phone` any tap 115 re-sends the tap entry's CLEAR (D1). The tap
  itself is judged by the tap rule as any tap 115: carried fields that the phone's own entry does not predict fail
  (5), so until the re-sent message lands a tap meeting the old press form is dropped (`item`), and one meeting the
  old report-only form under `glasses` is acted on without a press.
- **What a dropped tap shows.** Under `glasses` the press going out is the signal. Under `report` — and under `phone`,
  as today — a tap the phone does not act on shows nothing on the glasses; under `phone` a tap inside a rubber band
  does not reach the phone at all. No notice is built (§6.12): `tapdrop` carries the `Taps` value (D1), so the trial
  week counts the drops the phone sees per value. Whether a dropped tap may show nothing on `report` is Adam's, put
  to him with the default.
- **A tap inside a rubber band** (§6.1: stock sends no click; the hook still fires). Under `phone` it does nothing —
  no act, no note, as on stock. Under `report` and `glasses` its 115 carries it and the tap rule acts on it; its click
  owe is never paid (P5). Under C2 the highlight moves within the guide's 10–20 ms, so a tap about one band after a
  scroll becomes natural — the reason for the recommended default.
- **The tap rule.** A tap's 115 that carries fields 7, 8 and 17 (every outcome but refused 3 and 14) is acted on —
  the act on row R of list L, the phone's own — **only if all hold:** (1) it is fresh (P8); (2) field 7 is the list in
  front, with no modal or popover over it (the phone's focus, not the lens's); (3) field 17 equals the phone's current
  mark for L; (4) field 8 is not lost and equals the phone's row index in L, **or differs from it by at most n × |step|
  — n the owes this 115 added for missing reports (P5), |step| the largest of L's bound scroll entries — and then
  R := field 8 (one working-copy op) before the act**; (5) when fields 10–14 are carried, they equal the phone's own
  prediction of the tap entry's program and parameters at that cursor — a difference here with 1–4 holding is a
  divergence (a `divergence` note and a keyframe, §5.6). Then the phone acts on R, and the slot's answer in that batch
  is its DISCARD (P3), after the act's first frame (a keyframe needs none). **If any fails, the phone acts on
  nothing:** the slot's DISCARD, with the frame that draws its row when the lens's cursor differed (alone otherwise),
  one `tapdrop` note with its cause, and the press goes out — the tap is lost, said once, never applied to another row.
  A tap 115 refused 3 or 14 is dropped (`tapdrop`, `refused-3` / `refused-14`). Sequence gaps enter only through n: on
  RIGHT a gap is a lost 115, and a lost scroll that drew moved the lens by its step, which n allows; a scroll the phone
  heard and refused leaves no gap, so (4) fails and the tap is dropped; an unheard tap moved nothing; after a
  re-staging the same index names another row until the LIST lands, which 3 catches.
- **The click** is consumed as its tap 115's echo (P5, kind click), whether that 115 was acted on or dropped. A click
  with no owe is read against **`tapsLive`**: true when RIGHT has shown it holds the tap entry this session (a tap 115
  has arrived, or a telemetry read shows field 39 bit 0); false at each session start, at any release point the phone
  causes, when it sends CLEAR of the tap entry, and at a telemetry read without that bit. **While false**, the click
  came from a tap that matched no entry — `bind_live` 0 after a release point the phone has not yet answered, or the
  tap entry's SET not yet confirmed — so it stands for no missing report: it is acted on as today and is not counted
  in u (P5) (D1). **While true:** under `report` it is acted on as today, since the lens pressed nothing, and counts
  in u (P5); under `glasses` it is dropped (`tapdrop`, `no-report`: its 115 was lost and the lens may have pressed
  another row), and the slot's answer in that batch is a DISCARD (P3) (D0). A tap fired before a switch lands
  is judged by the tap rule if it produced a 115, whatever the setting is by then; a click with no 115, by the rule of
  the setting in force when it arrives (D0).
- **A scroll fired within a round trip after a tap** that the phone answers is stopped by that DISCARD (its tag is
  after the tap's) and drawn again by its own COMMIT: move, stop, land. Rare in the trial week, where only Main's list
  is bound and its taps open windows.

**Counting** (so the snap-back and drop rates are measured numbers; one note per event, a fixed `key=value` detail
that `tools/journal_report.py` counts by cause):
- **`snapback`** — every DISCARD the phone sends as a slot's answer for a scroll (P3, P4, P5), every SETTLE that RIGHT
  reports stopping something (a 114 outcome 1 for a local tag; a same-slot fire's close sends none, §6.2 step 11):
  `cause=` `lost-report` (P5, or an S gap) · `restaged` (mark or prediction differed) · `focus` (list not in front, a
  modal) · `link` (a start's keyframe closed local instances) · `burst-end` (SETTLE stopped one on RIGHT) · `refused`
  (COMMIT, DISCARD or SETTLE refused → keyframe) · `edge`; with `S`, `list`, `cursor`, `row`, `mark`/`markNow`, `ms`
  (115 receipt to answer), `frame` (yes/no).
- **`tapdrop`** — every tap not acted on: `cause=` `stale` (1) · `focus` (2) · `restaged` (3) · `moved` (4: a
  difference the missing reports do not explain) · `lost` (field 8 0xFFFF) · `item` (5, with its divergence note) ·
  `no-report` · `refused-3` / `refused-14`; with `S`, `list`, `cursor`, `row`, `mark`/`markNow`, `taps` (the setting
  in force, D1).
- **`local`** at each telemetry read: fields 35, 36, 38, 39 and the session's counts — taps acted, taps acted after a
  correction (4), taps acted without a press (17), drops and snap-backs by cause, SETTLEs sent, echo owes left at each
  SETTLE. LEFT's stops are not visible (LEFT cannot report); the count of SETTLEs sent bounds them.

### 6.8 Flags, telemetry, capabilities, the ladder

- **Flag bit 4 BIND** (needs ENGINE and DRAW2; checked at every fire and mode 27 under the word, so a FLAGS_SET of BIND
  alone is taken, status 0, and every fire and mode 27 is then refused 15, as §5.8 states for ENGINE). It clears at
  every release point (§3), with `bind_live` and S; it is refused without the lease (status 3). A disarm closes nothing
  and clears nothing.
- **Telemetry:** `35` matched events since boot (every S taken, whatever its outcome) · `36` the last S (0 after a
  release point) · `37` the last fire that drew: its µs from the hook's entry to the end of the copy hook's drawing
  of its step 1 (a DWT difference, §4's calibration; omitted until one this boot; a report-only fire draws nothing and
  leaves it) · `38` reports whose send returned non-zero since
  boot · `39` the event ids with a live entry on this lens, bit n for event id n (bit 0 the tap entry of either form,
  4 and 5 the scroll entries), 0 while `bind_live` is clear — computed at each read from the table without the word (a torn read
  costs one wrong `tapsLive`, set right at the next tap 115). 35, 36, 38 and 39 are always sent on a bit-10 build; the
  record's compile-time bound grows with them (R1.19).
- **Reasons:** 17 a tap fire whose close set holds a local instance (nothing closed) · 18 a fire on a list whose cursor
  is lost. 14 and 15 extend to mode 27, the fire and mode 26's subs 3–5 (15: BIND, and `bind_live` at a fire).
- **DamageCaps bit 10:** the hook, mode 27 (the report-only tap entry included), mode 26's subs 3–5, notify 115 and
  114's outcome 3, mode 28 and DRAW's h0, h1 and anchor, telemetry 35–39, reasons 17 and 18, flag bit 4. The phone
  sends none of them without it.
- **The ladder gains stage 4 = BIND** (with stage 3's ENGINE and the rest), set by hand as C1's stages are (`probe
  c1=N`), never advanced by itself; a hand `flags=` probe cannot arm bit 4 beyond the stage. Its rung is recorded before
  its FLAGS_SET (R2 B-M1's shape). The tap entry is SET at stage 4 only: report-only with `Taps · report`, with its
  press program with `Taps · glasses`.
- **What the phone persists** (on its own storage, written before the arming it records; not held in transport memory,
  so a restart of the phone's process — every APK install, M — loses nothing): the ladder stage (0..4), the `Taps`
  row, the time of the last session in which stage 4 was armed, the last `case` note with `in case 1`, and the
  hold-back (its time and reason). Each start re-arms what is persisted. **This extends §5.8's stored stage (build
  C1-P) with the rest of this list.**
- **The hold-back for stage 4** (a fault in the hook comes at a gesture, any time, so C1's 120-s window does not apply
  to this stage): at a start the phone holds stage 4 back — it arms stage 3 and says so once, as a `keeper` note and a
  `holdback` fault ("local input held back until tomorrow's first start: a lens restarted at HH:MM while it was on;
  Settings · Local input re-arms it") — when, since the last session in which stage 4 was armed, it has seen **RIGHT's
  reset** (the `the glasses reset` note; the reset placed at now − uptime on the phone's clock) or **LEFT's
  reboot-like link end** (not one the phone made), **and no `case` note with `in case 1` came between that arming and
  the reset.** It is lifted at the first start after a `case` note with `in case 1` received after the hold-back was
  set (the evening case-in), or failing that at the first start on a later calendar day (04:00 local), or by the
  Settings row `Local input · re-arm` on the glasses. A reset that follows a case-in holds nothing back; a flash
  restarts the glasses outside the case and holds stage 4 back for that day (or until the row). It costs one stage
  (4 → 3), never more; stages 1–3 keep §5.8's rule. A plain radio drop of LEFT reads like a restart and costs local
  input for the rest of that day (none in the five days of his journal, M). The overnight charge does not restart the
  glasses (708 h of unbroken uptime across five nights, M): the day's first start re-arms stage 4 with no hand action.

### 6.9 What waits on C1's measured numbers

C2's code and the phone's rules are built with P, Q and `age` as constants; their values and the staged strip's shape
wait for C1's flash, soaks and feel session (`HANDOFF.md` §85's checklist):

| number | what it decides |
|---|---|
| field 34, the step's drawing µs by area | the largest area one local step can repaint inside P → a list pan repaints the band or strips; the program's track count; how long the ui_task waits behind a step (R2 A-N4) |
| the period P after the feel session (starts at 34) | T × P of the list motion; the one-period cost when step 1's try folds (the guide's tail) |
| fields 27–29 at 256 KiB | how many rows a list stages; whether Main's focus-motion program fits beside the atlas (below) |
| field 33 under a working shell | how often step 1 folds at the thread's try → the guide's p90 |
| the 114 sequence gaps over the soak days | the loss rate of a notify sent from the ui_task on his link — the 115's sender path → the `no-report` and `lost-report` rates |
| the round trip's p90 (the journal's WAIT column, §5.8's notifies) | `age`, then Q |
| the lens-skew verdict for PLAY (eye, 240 fps video) | an upper bound on what he tolerates; a fire's skew (LEFT leads by one answer) is measured in C2's own feel session |
| the ladder on glass (hold-back, LEFT's end reasons) | how stage 4's hold-back reads them |
| the fast-spin probe (`REMINDER.md`) | the highest fire rate → the ui_task's load per fire and the COMMIT rate; scroll messages per notch at a fast spin — how the rubber band paces them on this build (§6.1; its length I) |

**Main's focus motion on the trial surface** (Adam, 2026-11-18): the selected row comes forward in depth — a per-lens x
step, which C1's DRAW expresses with dxL ≠ dxR over a segment — and takes the highlight and brightness it has in
today's design; the row it leaves goes back. **No size change of any kind** (a grow of the selected row was considered
and cut). The press continuing forward until the window's first frame replaces it is expressible (the tap entry's
program, holding until the slot's DISCARD after the act's first frame; his call E2). Main has 8 rows; the program —
the strip and one band form per row, today's highlight — was estimated at about 50–60 KB (I, `C2-amend-focus.md` §3,
"Arithmetic"), and fields 27–29 decide whether it fits beside the 192 KiB atlas; its size is read at the build. The look
itself is the phone's program, not this contract.

### 6.10 Vectors, and what only the glasses can show

New op `{"input": [devType, eventId, value]}` (after a `tick`, §5.10): the record through the hook on both lenses;
`"only": "L"` delivers it to LEFT alone (a dropped answer). The stock handler on the host only counts calls; a `wake`
after it draws step 1. `"N"` gains field-115 bodies (15 and 16 masked) and 114's outcome 3. Every v4 vector carries
`features` with bit 10, and the v1/v2/v3 sets answer alike on a bit-10 build. Inputs from `make_vectors.py`,
expectations from the C (`--write`), the simulator equal; the differential fuzz gains `--c2` (random inputs, LEFT-only
among them, between messages).

`v4-bind` (pass-through unarmed and unbound with the stock call counted; a fire drawing on the panel and leaving the
shadow; at SET: ids 1, 3, 0xD, 0xE refused, 4/5 with devType ≠ 0xFF or step 0 refused, id 0 with devType ≠ 4 or step ≠
0 refused; a tap entry with prog 0xFFFF taken, a scroll entry naming 0xFFFF refused 5 (D0), and one with prog
0xFFFF whose period is outside 16..128 or a parameter's kind outside 0..2 refused 12; one slot per list; the
lowest matching entry; a stock page in front: no fire, no 115) · `v4-edge` (both edges, a kind-1 item past the list,
the cursor unmoved; a kind-2 parameter outside s16 refused 12) · `v4-commit` (adopt;
END unconditional; a COMMIT older than the newest fire keeps the cursor; a newer local left drawing; outcome 3 then the
adopted instance's outcome 0; a holding local adopted turns done; the close pass over the other slots) · `v4-discard`
(≥ S across the 16-bit wrap; slot 0xFF; a holding local stopped with its body; a done local emptied silently; the
cursor set, clearing lost) · `v4-settle` (a held local older than `age` stopped, a younger one and a drawing one left
with the cursor kept; a LEFT-only input then SETTLE; no present when nothing stopped) · `v4-list` (the mark; a shift; a
new list from 0; out of range → lost; a fire then 18; DISCARD and SETTLE restore it; a lost cursor stays lost across a
LIST; the first mode 27 after a release point empties the lists too) · `v4-local` (`input` then `wake` draws step 1;
HOLD redrawn on a direct present, skipped on preserve; a stock-path present turns it done; a keyframe, a cache write
meeting its first range, a phone PLAY into its slot each close it; a fire closing a phone instance sends its 114 before
its 115; a fire closing its own slot's local instance sends none) · `v4-tap` (a ring tap presses with the cursor
unmoved; a temple tap passes through with no 115; 17 with an uncommitted scroll in the slot, nothing closed; a press
after COMMIT; field 17 carried; a scroll closing a press; a report-only entry's fire: outcome 0 with no fields 10–14,
nothing drawn, the cursor untouched, 17 with an uncommitted scroll in its slot — D0; it closes nothing: an ordinary
instance in its slot (a committed scroll still drawing) keeps drawing and sends no 114; 18 before 17 (a lost cursor
and an uncommitted scroll in its slot); the newest S untouched, read through a COMMIT of the preceding scroll sent
after the fire and naming a cursor other than the lens's: written, since the scroll is still the list's newest
fire, which the next fire's field 8 shows (D1)) ·
`v4-fail` (no context; lapsed at the fire → 3 carrying S; BIND without ENGINE → 15; a rewritten program → 5; the word
held → 14; mode 27 refused 14, 15, and 8 in a step) · `v4-lens` (a LEFT-only input, then SETTLE realigning LEFT's
cursor; both lenses numbering alike) · `v4-batch` (a DISCARD after the frame, refused: the frame in the shadow, no
present; mode 27 in a batch after the drawing subs) · `v4-vscale` (mode 28 at H = 1, h, 2h and 2048, per lens, clipped;
DRAW scaled with each anchor; H clamped on RETARGET and refused out of range).

**The phone's rules are phone code:** D3's unit tests carry the tap rule's condition 4 at n = 0, 1, 2 and after a
refused scroll; the per-batch answer (COMMIT of the newest only when every report since the slot's last answer was
adopted, nothing else moved the row and no drawing sub meets the areas; DISCARD from A + 1 otherwise; a tap's report
never committed); SETTLE from S_low; a delayed scroll message after a SETTLE.

**The sequence fuzz — a build condition on the phone's rules (D3).** Three passes each corrected the one before, so
the instrument ends the regress, not a further general read of §6. It drives random interleavings of notches, taps,
lost, duplicated and late reports, LEFT handling a gesture alone, re-stagings, covers, link drops and SETTLEs — and the
stock messages a rubber band drops or delays (§6.1) (D0), `Taps` switches between every pair of values, and lost
mode-27 messages (SET, CLEAR, LIST) — through the phone's rules against the simulator's two lenses, and checks after
every sequence six invariants: (1) no act on a row other than one the phone staged and RIGHT showed pressed (or its
open local, on 17) — on `report`, the row at RIGHT's cursor, and for a click acted on as today (`phone`, or `report`
with the tap's 115 lost) the stated residual, the phone's row (D0); (2) no picture on a lens that no later message
corrects; (3) both lenses on the phone's row after a SETTLE; (4) owes, u and S_heard back at rest; (5) no message
§6.2–§6.6 would refuse; (6) **a fresh tap whose 115 arrives is acted on** — liveness, which (1)–(5) cannot see (a
state in which no tap is ever acted on passes all five): after the sequence, with nothing lost from then on and the
list in front uncovered, of two ring taps a round trip apart the second is acted on (the first may meet a repair the
phone makes in its own batch, such as a re-sent tap entry, §6.7) (D1). **Each defect of the design pass's two focused
reads (`C2-focus.md`, `C2-amend-focus.md`) is a seeded case that must fail on the rule as it stood** — a pin watched
to fail on the old rule; so is the read before F1's switch whose SET is lost (`C2-F-focus.md` B1), which fails (6)
without the re-send (D1).

**No vector can prove** (one thread, one lens at a time, no UART): the inter-lens answer-and-drop order, a lost 115 or
stock message, LVGL holding the gate, a release point on another task during a fire, step 1 within a time slice, the
onset skew, the click's SysEvent order on the link and the callback it reaches, the fold rate with HOLD.

**What only the glasses can show,** and what reads it: the 115 loss rate (C1's soak gives the 114 gaps on the same
sender path; then field 38 and the S gaps the echo count records) · that a tap's click follows its 115 through
`FUN_004E0D3A` (the first armed session's journal: each tap 115 followed by its click, **except a tap inside a rubber
band, which sends none** (§6.1) — so the check expects no click for a tap 115 that comes within a band of the last
scroll message, and counts such taps apart rather than as missing clicks (D0)) · the rubber band's length on this
build and Q's floor (the fast-spin probe; the trial's journal: the p99 of a burst's last 115 → its last scroll
message) · the guide's figures (field 37, the 240 fps video) and the verdict (his use in the trial week) · the
snap-back rate and the tap-drop rate by cause (the
trial week's notes) · the onset skew and LEFT-only rows (the feel session by eye, the video; SETTLE bounds how long one
lasts) · whether the ring holds a single tap until its double-tap window has passed (the video, tap to press; the ring
reports types 1 and 2 itself, V `FUN_0047269E`) · the fold rate with HOLD (field 33 in the feel session) · the crossing
window's width (the trial's 115 receipt times against SETTLE and DISCARD acks) · that a `case` frame arrives at every
case-in (the journal; the calendar fallback covers a miss) · COMMIT CRCs equal to the model's over the trial week
(RIGHT's shadow only, never LEFT, never a step). `glassdrive.py` sees the phone's mirror, never a lens.

### 6.11 The failure envelope (`FORK.md` §3.8)

| new state, wait or guard | (a) cleared at | (b) a write that never lands | (c) keyed on | (d) other tasks, the other lens | (e) one read | (f) siblings checked |
|---|---|---|---|---|---|---|
| `bind_live`, 8 entries, 4 lists (items, count, cursor, pitch, mark, newest S) | a release point (`bind_live`, one store in the masked section); the next accepted mode 27 empties entries and lists first; CLEAR (entries); a restart. Not at arming or disarming | a lost SET or LIST: the lens fires with the old table (P3 or the tap rule finds the mark or prediction differs → DISCARD) or passes through; a refused one is re-sent on the refusal record; every start re-sends | entry, list; the program by off4, walked at every fire | written on the ui_task under the word (mode 27, the fire, COMMIT, DISCARD, SETTLE); `bind_live` cleared by a release point on any task inside the masked section. Each lens applies mode 27 in its own order against input: P3 judges RIGHT's result; SETTLE and DISCARD realign LEFT | the fire reads each once after its claim | SET's shapes and PLAY's walk; the three writers of the cursor (COMMIT, DISCARD/SETTLE, LIST) and the fire's edge test; the simulator's copy |
| S (u16) | a release point (one store beside `bind_live`), a restart | a lost 115 is a gap: P5's owes, P3's bound | per lens, taken at the entry match; LEFT ahead only after a dropped answer | one atomic add on the ui_task; the clear inside the masked section (an add crossing it gives old + 1, read as a gap) | one add per matched event | `eng_seq` (the same shape) |
| the local instance (C1 slot + local flag + HOLD at d = T; tag S, t0, P, T, d, first range, entry stamp) | §6.4's list | an unanswered one: SETTLE, a keyframe or the lease's lapse; the shadow never held it | slot + tag S; bytes by its first range | opened on the ui_task under the word; drawn by the copy hook under the word (display task, 45); planned by the thread without it (C1's torn-read rule); emptied by release points; LEFT opens it one answer earlier and may hold one RIGHT never had | as §5.11 | C1's instances; the plan's skip; the three present paths; a keyframe's close; a cache write's close-on-meet |
| the fire's claim and let-down | every exit lets down, refusals and the edge included | a failed claim → refused 14 → today's path | the word's three values | §5.11 row 3; the let-down precedes the sends and the stock call | one compare-exchange each | every ui_task holder: mode 26, a live mode 6, mode 11's closes, cache writes, mode 27 |
| the engine flag set by a fire | — | the thread replans whether or not anything follows (§5.2) | — | set on the ui_task, waited on by the thread | — | PLAY, RETARGET |
| the 115 send; field 38 | counters at a restart | a non-zero result is counted; no retry; P3, P5 and SETTLE recover | S | only the ui_task sends 115s (one FIFO); 114s from the display task may interleave | the send's result once | the two 114 senders; stock's scroll-message and click puts (the same queue, 500 ticks) |
| COMMIT | — | refused or unacked: a divergence and a keyframe (§5.6), capped per (slot, tag) | slot + S; the cursor by the list's newest S | ui_task under the word | the slot's state and the list's newest S once | PLAY's validator and close pass |
| DISCARD | — | lost or refused: a divergence and a keyframe; SETTLE also ends the instance | slot + S (signed 16-bit), list | ui_task under the word | each named slot once | CLOSE, SETTLE |
| SETTLE | — | lost: the next idle batch carries another; refused: a divergence and a keyframe | slot + S + the held age from t0, P, T | ui_task under the word | each named slot's state and t0 once | DISCARD, CLOSE |
| the lost cursor (0xFFFF) | DISCARD, SETTLE, the empty table | the next SETTLE or DISCARD sets it | list | ui_task only | once per fire | LIST, COMMIT's cursor write, the fire's edge test |
| the tap guards: SET's two shapes, a tap entry with a program or report-only (prog 0xFFFF); refused 17. `Taps` is no state on the glasses: the entry's prog carries it | as every entry | a lost SET: no tap entry → `tapsLive` false → today's path (on `report` a click with no 115 is acted on as today anyway). **A switch whose SET or CLEAR does not land** leaves the old form on the lens; RIGHT's next tap 115 shows it (§6.7: fields 10–14 carried under `report`, absent under `glasses`, any under `phone`), that tap is judged by the tap rule, and the next batch re-sends the entry, journaled — so at most the taps before the re-sent message lands meet the old form | entry; the close set's local flags (a done local counts, D1); for a report-only entry its slot's local flag | ui_task under the word; one lens may press while the other refuses 17 — the phone's DISCARD reaches both; a report-only entry draws on neither lens. A switch refused on one lens alone (§6.4's class) leaves the lenses on different forms, which RIGHT's 115 cannot show when LEFT is the one behind: each tap then presses on one eye only, ended by the slot's DISCARD — under `phone` (LEFT's CLEAR refused) by an idle batch's SETTLE, LEFT's extra fires putting its S ahead as a dropped answer does (§6.4) — until the list's next return from a cover SETs the entry again (`report`, `glasses`) or the next start's CLEAR 0xFF | once, in the close pass, before any close | the close pass of PLAY, COMMIT and scroll fires (a scroll may close a press); the report-only fire's skipped steps (§6.2) |
| a same-slot local close sends no 114 | — | the newer 115 lost: nothing waits on the closed local's body (§6.5) | closer a fire, closed instance local, same slot | ui_task under the word | one test in the close pass | every other close keeps its body; the simulator's close pass |
| BIND (flag bit 4) | every release point; refused without the lease | a FLAGS_SET that does not land: the reply shows BIND not armed → the phone binds nothing | — | settings task writes; read once under the word; unordered with the image lane per lens (§5.7's class) | once | DRAW2, ENGINE, ZWRITE |
| the hook's pass-through and page test | — | — | devType + eventId | runs on both lenses' ui_tasks from boot; peek only; `FW_MODE(UI_CTX)` read before the dispatcher (I) | one context read | the inherited long-press and release sites stay inside the dispatcher |
| telemetry 39 | computed per read | — | — | the settings worker reads the table without the word (a torn read costs one wrong `tapsLive`) | one pass per read | fields 35–38 |
| mode 28, the scaled DRAW | no state | — | — | worker, under the gate as mode 17 | — | mode 17; DRAW's eased fields; RETARGET's clamps |
| phone: S_heard, the arming uptime | each session start (P1) | — | session | the transport's reader thread only | once per 115 | C1's 114 sequence |
| phone: echo owes and u (P5) | every SETTLE sent, a session start | an owe of either kind may never be paid (stock sends no scroll message for a notch inside its rubber band and no click for a single tap inside it, §6.1) and a paid scroll owe may come a band late; until the next SETTLE an unpaid owe can swallow one later unowed stock message — an unreported scroll message (repaired by the next report's cursor, P3, or the SETTLE) or a click whose 115 was lost (that tap is then neither acted on nor journaled `no-report`) | kind (scroll, click) | reader thread | once per message | P3's bound |
| phone: the per-slot answer (P3): A, the reports handled since the slot's last answer and whether each was adopted, whether anything else moved its row | A at each answer (the last S it covered), := S_heard at a session start; the rest at each answer | a lost COMMIT or DISCARD: a missing ack or a refusal → a divergence and a keyframe; SETTLE ends what is left | slot; S (signed 16-bit) | the shell's loop composes it after the frame's drawing subs (and after a LIST of its list); the reader thread hands it the reports in order (P2) | once per slot per batch | P4's and P5's DISCARDs, the tap rule's answer and the unowed click's, now this one message; P10's diff with END applied |
| phone: S_low (P7, P9) | := S_heard + 1 at each SETTLE sent and at a session start | a SETTLE that does not land: a refusal → a divergence and a keyframe; a link drop → the start's keyframe closes every instance | — | the shell's loop | once per SETTLE | S_heard; P9's disarm DISCARD |
| phone: the entries and lists it holds, its marks, the staged records' places (P9) | each start; each change of the list in front | a lost SET or LIST: P3 or the tap rule; the refusal record → re-send | entry, list, mark | the shell's loop builds them; the transport sends | — | the atlas planner (never overwrites a bound record in place while a burst is open) |
| phone: SETTLE's pacing (the last report's time, a burst open) | each report; each SETTLE sent alone | — (pacing: nothing waits on it). Q's floor (P7): a scroll message the band held back past the SETTLE that cleared its owe is applied as an unreported notch, so Q stays above the p99 of a burst's last 115 → its last scroll message (the trial's journal; until measured Q ≥ `age` + (T − 1)·P, I) | — | the shell's loop | — | C1's motion scheduler's nominal-end resume |
| phone: `tapsLive` | session start; a release point it causes; CLEAR of the tap entry sent; a read without field 39 bit 0 | — | session | set by the reader thread; read by the shell once per unowed click on `report` and `glasses` (false: acted on, not counted in u, D1) | once per click | P8's session test |
| phone: the tap rule and its DISCARD answer | — | a lost DISCARD: a missing ack → keyframe; SETTLE also ends the press | S, list, mark, cursor, the owes n | the shell's loop | each field once | P3 |
| phone: the bound-list CLEAR/SET around covers (modals, popovers, other windows) | — | a lost CLEAR: a fire under the cover fails "in front" → DISCARD; a lost SET: no local input until the next start | entry | built in the cover's own batch | — | every cover: confirm, popover, keyboard, window switch |
| phone: batch order (26/27 after the drawing subs; a LIST before its slot's answer, D0) | — | a refused sub loses the present only | — | both lenses alike | — | every flush carrying COMMIT, DISCARD, SETTLE, SET, CLEAR or LIST |
| phone: the persisted stage, `Taps`, the last stage-4 arming, the last case-in, the hold-back | never by a restart of the process; the hold-back at its lifting | the record is written before the rung's first byte (R2 B-M1) | the restart reads: RIGHT's uptime, LEFT's end reasons | the transport | each end read once (R6 B6-2) | C1's stages 1–3 |
| phone: unknown bound areas (P10); the batch's diff taken with COMMIT's END applied | the SETTLE that ends the burst | — | slot | mirror lock (C1's) | — | C1's unknown drawing areas; the per-slot answer's "no drawing sub meets the areas" |

### 6.12 Not in this contract

- **Double-tap bindings** — back's result is a whole new picture; a local press buys nothing there.
- **The long press** (never) — it is unattributed (`CLAUDE.md`).
- **Temple taps** (devType 0 or 1) — the phone ignores them today (ring-only taps, `DESIGN.md` §1).
- **A local tap that depicts its act's result** (never) — the act is the phone's.
- **The glasses deciding what a tap acts on** (never) — the act is on a row of the phone's own staging; a tap's report
  moves the phone's row only by steps the reports it knows it missed explain (P3's bound), so a late report or a
  refused move never moves the act's target.
- **Per-source scroll bindings** — the scroll message carries no source, so "unbound" and "lost" would look alike.
- **The binding epoch** — the mark and P3's compare of the program and parameters do its work.
- **Field 30's "local" bits** — SETTLE is blind; field 36 gives RIGHT's last S.
- **Per-burst reports** — a clock on the input path, and a lost report would cost a burst.
- **A wrap motion on Main** (the whole list sliding from the wrapped cursor) — a feel question for after the trial
  week; until then Main's wrap is the staged edge (§6.4).
- **A local bounce at a real list end** — Main has no real end (it wraps); lists with ends are bound in K.
- **The hook consuming bound scrolls** — the dispatcher's other work for ids 4/5 is unread; field 33 first.
- **A counter of RIGHT's dropped inter-lens answers** — a new site on the sync listener's path.
- **LEFT's report** — an inter-lens message (it would also close §5.7's class).
- **A start message between the lenses** — only if the skew is visible.
- **The fire queuing step 1 itself** — a gate take on the input path (§6.2).
- **A gate take around the word's holders** (§6.4's fold, R2 A-S1 with HOLD) — a new wait.
- **A notice on the glasses for each dropped tap** — on `glasses` the press going out is the signal; on `report` a
  dropped tap shows nothing (§6.7, what a dropped tap shows); the journal counts both, per `Taps` value.
- **Local input with the phone away** — the offline home (step G, §8).
- **K's programs** beyond Main's pan and press (`MOTION.md`).
- **C1's carried items** — a session identity on the image lane; the masked section for the scratch's and slots'
  release windows (A.14).

## 7. v2 firmware-owned chrome (Phase 5; in the first flash only if its measurement says it pays, `REPLAN.md` §5) — *draft*

Rects the phone declares firmware-owned (the status bar's clock, battery and link cells; the
silent-mode clock): the glasses draw them after the frame copy from cached fonts at the Global depth
the phone sets; they are outside the shadow and the phone never paints them. RTC and fuel-gauge
sources are stock functions (`FORK.md` R0.5).

## 8. v2 offline home (Phase 7; step G, decided again, `REPLAN.md` §4) — *draft*

Placeholder: display ownership outside the EvenHub page, local input, the persisted flag, saved
content, the stock override gesture, stock fallback on any failure. Written after the Phase 7 research.

## 9. Conformance vectors (Phase 1)

- **Location:** `damagewm/firmware/vectors/*.json` (data, no code). Inputs are written by
  `firmware/make_vectors.py` (Damage, clean-room, from the documented message formats).
- **Shape (as built 2026-09-13):** `{ "name", "contract": <version>, "start": "zero", "steps":
  [ { "ops": [ {"tick": n} | {"lease": "acquire"|"release"} | {"msg": "<hex>"} … ],
  "expect": { "L": "<crc32 hex>", "R": "<crc32 hex>", "rc": { "L": [..], "R": [..] } } } ] }`. A
  `msg` is one completed image message as the deferred handler receives it; `rc` lists each
  message's return (0 accepted, −1 refused) in order; `tick` sets the firmware's clock in OS ticks (§3's uptime,
  1.024 per ms; a uint32 as the clock reads it, so a vector may cross the wrap) and the lease deadlines run on it:
  90,000 ticks, a signed 32-bit compare (R1.7). Later phases add ops (program upload and play, §5.10) and
  `"scratch"` expectations; the self-test form is below. **C1 added** (A.3): the op `{"wake": true}` (§5.10); a
  vector-level `"features"` key, the DamageCaps bits it was written for (the C runs it only on a build with exactly
  those bits, the model runs at them); and, only in vectors with that key, per step `"N"` (RIGHT's notify-114 bodies,
  hex, in send order, fields 9 and 11 removed — tag and value, not zeroed), `"W"` (`{L, R}`: the pure plan's wait at
  the step's last clock, −1 for ever, 0 when a step is due now with nothing outstanding) and `"G"` (`{L, R}`: field
  17). A step with no expectation in `firmware/vectors/` fails `ConformanceVectorTest` (R1.21); a directory given in
  its place may hold unfilled steps, which the model runs and reports.
- **CRC:** CRC-32, zlib polynomial `0xEDB88320`, over the packed 4bpp shadow rows in order (320
  bytes per row, 480 rows), per lens; scratch and layers the same way over their own bytes.
- **The panel (2026-09-15, the second Phase 2 review):** a vector may also carry `"panel": true`, and
  then every step's expectation adds `"P"` — the same CRC over **what the lens shows**, which is not the
  shadow: a full refresh transfers the whole frame to it, a partial refresh (mode 24) only its own rows,
  and a stock repaint replaces it. Both implementations model it as 153,600 zero bytes at the start, a
  full refresh copying the frame whole, and a partial copying rows y0..y1 (inclusive). A lease release
  point marks the panel STALE and leaves its pixels alone on both sides: the firmware paints nothing
  there, and the stock compositor's own repaint is a later event each harness issues for itself (a stock
  copy plus a refresh in the C's; `stockRepaint` in the model). Read the other way round — the model
  repainting at the release itself — the two would have disagreed on the panel CRC the moment a released
  vector carried the key (2026-09-15, the third review). **`{"stock": true}` is that event**
  (2026-09-16): the host harness answers it with a stock copy plus a refresh, the model with
  `stockRepaint`. It is not unconditional on either side — while the lease is held and a Damage frame is
  up, the copy hook preserves the direct frame and the panel does not change; only once the direct path is
  not active does stock content reach the framebuffer, which is §4's stale condition. What the stock
  compositor actually paints is its own widgets, which no offline model predicts, so the two harnesses
  follow a **convention** for it as they do for the all-zero panel they start from: **every byte 0x5A**.
  `v2-panel` compares it, and a vector carrying the op has no self-test form (a step cannot repaint the
  panel). Until then `stockRepaint` had no caller anywhere, so §4's "stock content reached the
  framebuffer" was the one stale condition the two implementations were never compared on.
  This is where a hint that misses a row the batch
  changed becomes visible, so the C and the model are compared on it rather than on the shadow alone. A
  vector without the key is compared on the shadow as before. The diagnostic overlay is drawn by the
  firmware's own font, which no offline model predicts: a `"panel": true` vector never shows it.
- **Who runs them:** the fork's host build (`~/damage-cfw/host/run_vectors.py`: the unchanged patch
  sources compiled for 32-bit x86 with the firmware's addresses mapped; `--write` fills the
  expectations), the Kotlin simulator (`ConformanceVectorTest` in `core`, which also runs the v1/v2 set at features
  `0x1ff`, A.1), and — from Phase 1's
  flash — the glasses through the self-test (mode 16). All three must agree before a flash is called
  good. For v1 (the installed firmware) the C wrote the expectations; a disagreement is a finding about
  the simulator or the docs, never a reason to edit an expectation by hand.
- **The self-test form (2026-09-14; cache writes sent live since Phase 2, §4, mode 25 too):** a drawing vector (every
  vector but `v1-lease`, those that release the lease and C1's engine vectors, §5.10)
  also runs as `[16][0]`, then each step's messages as `[16][1][message]`; the scratch CRC after each
  step must equal the step's `L`/`R` expectation and the refusal its last `rc`. The host C runs it
  (`host/run_self_test.py`), the simulator runs it (`ConformanceVectorTest`, the self-test form), and
  the glasses run it through `tools/glassdrive.py selftest:FILE` — RIGHT reported, LEFT blind.
- **v1 set (2026-09-13):** 7 vectors, 35 steps — keyframes, mono and stereo deltas at the panel's
  edges, overlapping and odd-coordinate copies, a batch, refusals (out of bounds, a repeated fid, a
  batch with a non-shadow sub-message that has already changed the shadow), the texture cache
  (options, clipping, an x-adjust byte, a refused string), the lease (renewal keeps the cache, a
  lapse refuses and frees it). **The simulator matches the C on every step, both lenses.** Since then
  `v1-stream` (§64) and `v1-tablepatch` (`HANDOFF.md` §73, 11 steps: the v1 twin of `v2-tablepatch` — a 96-entry
  table at an ODD byte offset, a record and its 2-byte entry in one mode-12 message, a patch-only entry, code 126,
  a two-entry message refused whole). The vector pass (2026-11-12) added four: `v1-shape` (26 steps: modes 13, 14 and 9
  a byte long and a byte short, mode 9's sizes, the per-lens mode 3 without its stream and with two box sizes, mode 8's own structure — no count, a zero or
  overlong sub-message length, a count past the bytes, bytes after the last sub-message, an empty batch — and mode 12
  checked whole before a byte is written), `v1-gates` (19 steps: every v1 cache op without the lease and the order of
  its length check against it, a draw after a fresh lease, mode 10, mode 16's sub and step checks), `v1-mode15` (3
  steps: only what mode 15 refuses before the font is read — its header's length, the lease, the string's own length;
  a well-formed mode 15 with the lease held is never in a vector, the PC's host has no font) and `v1-batchmax` (3
  steps: a batch of exactly 153,718 B accepted, one byte more refused).
- **v3 set (C1, 2026-11-15 → 17):** §5.10, with the counts at the build's end and the two fuzz sets.

## 10. Lifecycle

Session start: capability read → flags armed one at a time (hold-back rule: the transport's
`armFeatures` reads RIGHT's uptime first and compares it with what the last reading plus the phone
time since predicts — a shortfall past the slack is a reset; a reset within `HOLD_BACK_MS` of the
last arming disarms the wanted set, journals it and raises a `holdback` fault — on a C1 build the ladder drops one
stage instead when its own rungs are to blame, §5.8; a bit the build
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
- 2026-09-15 (evening) — §4 built on both sides and stated as built (`HANDOFF.md` §60): the checks' order per op; mode
  23's three shapes and its pool; mode 24's inclusive rows, the partial entry's call and field 26; the clip's scope;
  the LUT's nibble order; op 5's lease rule and the v1 window; reasons 11–13 and the v1 modes' order; features `18 7f`.
  §0's status: contract 2 not flashed. §9: the `ref` expectation and the self-test form's rules for the v2 set.
- 2026-09-15 (night, 2) — a second review of Phase 2 (`HANDOFF.md` §62), fork and simulator changed together:
  §4's mode 24 now states the whole rule for a partial refresh — it only ever ADDS its rows, so the frame goes
  whole whenever the panel does not already show the previous one (a message that held the display gate and
  presented nothing, stock content in the framebuffer, a release point, the overlay and the first frame after it
  is hidden); reason **14** added (the display was still busy: the message is dropped whole); reason 13 covers an
  inflate the heap could not give its state; the v1 orders state mode 3/6's under-3-byte check and mode 15's
  codes-then-glyphs order; mode 23's self-test slots have their own pool. §9: a vector may carry `"panel": true`
  and compare the CRC of what the LENS SHOWS, which is where a short hint is visible; three vectors added
  (`v2-panel`, `v2-reach`, `v2-order`), four corrected. Contract version unchanged (not flashed).
- 2026-09-16 — a fourth review of Phase 2 (`HANDOFF.md` §64), fork and simulator changed together.
  §4: a mode-3/6 stream refusal is **not a rollback** — v1 streams the decode into the shadow and a
  refused message leaves the complete runs it had already written (found by a differential fuzz of
  the C against the simulator; the model rolled back, the glasses cannot; `v1-stream` pins it, and
  the model now does the same). §4's DWT note had the calibration's direction inverted: the figures
  read 2.4 % HIGH, and the firmware scales for the 1.024-per-ms tick so they no longer do. §9: the
  `{"stock": true}` op, the 0x5A convention for stock content, and the rule that the stock repaint
  preserves a live direct frame — `stockRepaint` had no caller, so "stock content reached the
  framebuffer" was the one stale condition the two implementations were never compared on. Two
  vectors added (`v1-stream`, `v2-gates` — the lease and DRAW2 checks were dead for six of the
  eight v2 modes) and `v2-panel` extended to 16 steps. Contract version unchanged (not flashed).
- 2026-09-16 (evening) — page staging built on the phone side (`HANDOFF.md` §66): the cache's top 32 KiB as the
  staged-record reserve, the proof by counting field 17. No wire shape changed; contract version unchanged.
- 2026-09-15 (night) — the review of Phase 2 (`HANDOFF.md` §61), fork and simulator changed together, the vectors
  regenerated from the C (every existing expectation unchanged) plus `v2-edges` and `v2-lifecycle`: op 5 takes 64..160 KiB
  (a smaller cache put the v1 modes' bounds past its end) and a request no longer shares the allocated size's field; a
  per-lens pair is checked whole on both lenses; mode 19's order is length → lease → DRAW2 → record; mode 16's refusals
  are recorded; mode 24's partial call is a Damage job's own (0, 0, 0, y0, 640, y1), and the refresh is full after a frame
  the panel-off path never transferred or while the overlay shows; §4 states each op's order, the v1 modes' order as
  built, the ignored high bit, and that a batch is not all-or-nothing. Contract version unchanged (not flashed).
- 2026-11-09 — the phone code packs a face with the characters it has drawn and patches its table in place
  (`HANDOFF.md` §73; APK 0.63, installed 2026-11-10; `v2-tablepatch` ran on glass that day, 11 of 11 on RIGHT). No wire shape changed and nothing was flashed; contract version unchanged. §4 states what
  the handlers already did: a cache write is a plain copy that may overwrite, a list is refused whole, and a list
  with no data is accepted without a generation bump (§65 had read the last as a message lost on the glass; it
  was the self-test driver's expectation). Two vectors added (`v1-tablepatch`, `v2-tablepatch`; 28 in all), the
  other 26 byte-identical from the C.
- 2026-11-11 — §2 gains op 7 and the wear event, which had no line here (APK 0.64 sends one op 7 per session
  start, `HANDOFF.md` §76). No wire shape changed and nothing was flashed; contract version unchanged.
- 2026-11-13 — §5 (motion programs, the first flash) and §6 (local input and bindings, the second flash) taken
  from draft to contract form on `MOTION.md` §6 as ruled that day: one writer, new instruction-level reads (where the
  engine can run; the gesture ids 4 and 5), not yet challenged — see the note above §5. Nothing built, nothing
  flashed; contract version unchanged.
- 2026-11-17 — §5 built on both sides by build C1 and every gap its rounds ruled written in (tagged A.n and R1–R6 in
  §5; the build's record is its `HANDOFF.md` section): the container's list first for 25 and 26 (R1.4), CLOSE's
  present and the stale mark, RETARGET's restart state and shapes, the notify bodies and counters, the phone's
  ladder, mode 25's re-checks, the survival check's true miss, its time and its open point, the cache-with-no-lease
  window, the build's checks. §0 features `0x1ff` on the candidate; §3 field 11 bit 5; §4 reason 14's engine-word
  case; §9 the C1 keys and the OS-tick clock. The link item is left out of C1. Fork candidate `0dab2154…`, not
  flashed; contract version unchanged.
- 2026-11-18 — §6: a dated note of the amendment from the stock input model as ruled (the rubber band's pacing and the tap gate read at instruction level; the per-batch answer; the report-only tap entry; the sequence fuzz); the text itself is amended by C2's first documents stage.
- 2026-11-18 — §6 rewritten from C2's design pass as ruled (the challenge, the rulings prep, the focused read, Adam's
  answers of 2026-11-17): local scrolls, local taps as a setting (`Taps`, default through the phone), the
  applied-state report with COMMIT / DISCARD / SETTLE, LIST as a shift that always applies, what the phone persists,
  the failure envelope and the Not-built list. Not built: nothing of C2 is built before C1 is flashed.
- 2026-11-18 — §6: the amendment from the stock input model worked into the text (C2's first documents stage, D0; the
  dated note removed): the rubber band and the single-tap gate in §6.1 (V; the band's length I), CLAIMS rows beside;
  P3's answer per slot once per batch, which closes the text's COMMIT-over-the-frame defect; P5's owes that may never
  be paid; SETTLE from the lowest S since the last SETTLE; P10's diff with END applied; no 114 for a same-slot local
  close; the tap rule's n-step bound; the report-only tap entry and `Taps`' three values; Main's wrap as the staged
  edge; Q's floor; Main's focus motion as Adam ruled it the same day (depth and today's highlight, no size change; the
  icon grow M2 cut, §6.6); the sequence fuzz as a build condition; the envelope's rows. Sentences marked (D0) are this stage's fillings, ruled as written the same day. Nothing built; contract
  version unchanged.
- 2026-11-18 — §6: the focused read of the report-only tap entry worked in (C2's documents stage D1, `C2-rulings.md`
  §12 B): the hook ahead of stock's click gate settled at instruction level (§6.1); a tap 115 whose form disagrees
  with `Taps` re-sends the tap entry, the switch in §6.11, the fuzz's `Taps` switches and lost mode 27s and its sixth
  invariant, liveness; an entry's presence is its own mark; what a dropped tap shows per `Taps` value (§6.7, §6.12);
  field 37 the last fire that drew; the report-only vectors; a done local counts for 17; a click from a tap that
  matched no entry not counted in u. Sentences marked (D1) are this stage's fillings. Nothing built; contract version
  unchanged.
