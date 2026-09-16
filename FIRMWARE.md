# FIRMWARE.md — the Damage firmware contract

**Context for the reader.** This is the interface between Damage (the phone or PC compositor) and
the Damage build of the G2 custom firmware (`~/damage-cfw`, a fork of the published `g2flash`
firmware for Adam's own glasses). It describes messages, data formats and behaviour in words and
integer arithmetic. Two implementations follow it independently: the fork (C) and Damage's
simulator (`core/.../sim/GlassFirmwareSim.kt`, Kotlin, written from this text and never from the
C — `CLAUDE.md`, clean room). A conformance-vector set proves both agree, on the host and on the
glasses. Plain wording throughout.

**Status 2026-09-15 (evening):** v1 is the installed base (g2flash `a5d1c31`), pointed at in §2. §0 and §3 (the Phase 1
extension: DamageCaps, telemetry, flags, the presented notify, cache-keep, the self-test) are **installed on Adam's
pair since 2026-09-15** (fork pin `c5e4f8b7…`, `HANDOFF.md` §55); the boot count stays withdrawn (§11). **§4 is built
on both sides (Adam's word on `HANDOFF.md` §57's decisions; fork pin `55746389…` after the review, `HANDOFF.md` §60–§61), not flashed:** the
host C, the simulator and the v2 vectors agree; the glasses' turn comes with the Phase 2 flash. §5–§8 are the decided
shape only. **Only the RIGHT lens can send** (`CLAIMS.md`): every reply and notify is
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
  verification waits for an inter-lens report (Phase 4, R0.1). A vector that releases the lease has no
  self-test form (a tick cannot be set on the glasses); a step carrying a cache write is refused, so since
  Phase 2 the runners send a vector's cache writes LIVE (§4), and the phone's shell drops its atlas for the
  session at the first such write.
- **Status codes:** one byte; the table grows with each op that can refuse (above).

## 4. v2 drawing ops (Phase 2) — *built 2026-09-15 evening (`HANDOFF.md` §60); changes only with a contract bump once T2 passes*

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
| **19** cache write v2 | `{ off4 u16 · len u16 · data }…` | mode 12 with `off4` offsets over the session's cache size (op 5); the whole list validated first, in this order: every entry's shape inside the message (1), the lease (3), DRAW2 (9), every entry inside the cache (5); a list with no data is then accepted and writes nothing; the first write allocates the cache (11); bumps the generation |
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
a value outside its range (a fill's level, a sub-op) · 13 a zlib or RLE stream that does not decode to its size (an inflate whose state the heap would not give it reads the same way) · 14 the display was still busy with the previous frame when the gate came free: the message is dropped whole and nothing of it reaches the shadow. The
v1 modes' order as built (upstream's checks, each now recording its reason): mode 12 checks each entry's shape (1) and
the 64 KiB window (5) entry by entry, accepts a list with no data, then the lease (3) and the allocation (11); modes 13,
14 and 15: no shadow (4) → the header's length (1) → the lease (3) → the string's own length (1; 14 and 15) → the table
or font (5) → byte by byte, a code (6) or a glyph record (5) — mode 15 reads every code of the string first and only then
every glyph, so a glyph refusal there follows all of its code refusals; modes 3, 6 and 9 need no lease — 3: length (1; under 3 bytes it is the same check mode 6 fails, before anything else) → the
boxes (2) → a duplicate fid skipped unrecorded → no shadow (4) → the stream (13); 6: under 3 bytes, recorded
as length (1) on the way to the BMP path and before the fid ring is touched → no shadow (4) → the stream (13); 9: length (1) → sizes and bounds (2) → no
shadow (4). Mode 16: alone (1) · a begin without the lease (3), whose scratch allocation fails (11) or that a release
point ended (7) · an unknown sub (12) · a step shorter than 3 (1), without the lease (3), without a begin (7), carrying
what a step may not (8), then its message's own reason. A batch is not all-or-nothing: it records its own structural
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
mode-19 chunks); the compositor ships a rect of cached text on a depth plane as one stereo base delta at each
lens's box plus the draws placed per lens by modes 17/18 — no widening, no `CopyPair`, no context retry, so the
`edge` and context-caused `proof` misses go; a black box is a mode-21 fill (no fid); a reseed (the height switch,
the exclusive exit, a font-scale relayout) is a whole-panel fill plus the diff's draws, never a keyframe; a flush
whose changed rows span at most 240 carries a mode-24 hint (the model keeps only the hinted rows on its panel, so
a short hint fails the oracle); kerning is the platform's pair measure as adjust bytes (Android; AWT none); the
`present` records carry the path and `tools/journal_report.py` splits the transfer by it. DRAW2 is the session's, not
the wish's: a hold-back that catches it keeps it off at later starts until a hand-set flag set asks for bit 2 (the
session then draws with v1 shapes over a 64 KiB atlas), a lease that ends drops it from the link state at once and, on a
Damage build, the shell rebuilds the session to arm it again, and an atlas of the other contract is never drawn from
(2026-09-15 review, `HANDOFF.md` §61). Still to build: Reader's page staging as v2 records with a clip + draw page turn.

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
sub 0 clearing the record).
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
- **The panel (2026-09-15, the second Phase 2 review):** a vector may also carry `"panel": true`, and
  then every step's expectation adds `"P"` — the same CRC over **what the lens shows**, which is not the
  shadow: a full refresh transfers the whole frame to it, a partial refresh (mode 24) only its own rows,
  and a stock repaint replaces it. Both implementations model it as 153,600 zero bytes at the start, a
  full refresh copying the frame whole, and a partial copying rows y0..y1 (inclusive). A lease release
  point marks the panel STALE and leaves its pixels alone on both sides: the firmware paints nothing
  there, and the stock compositor's own repaint is a later event each harness issues for itself (a stock
  copy plus a refresh in the C's; `stockRepaint` in the model). Read the other way round — the model
  repainting at the release itself — the two would have disagreed on the panel CRC the moment a released
  vector carried the key (2026-09-15, the third review). This is where a hint that misses a row the batch
  changed becomes visible, so the C and the model are compared on it rather than on the shadow alone. A
  vector without the key is compared on the shadow as before. The diagnostic overlay is drawn by the
  firmware's own font, which no offline model predicts: a `"panel": true` vector never shows it.
- **Who runs them:** the fork's host build (`~/damage-cfw/host/run_vectors.py`: the unchanged patch
  sources compiled for 32-bit x86 with the firmware's addresses mapped; `--write` fills the
  expectations), the Kotlin simulator (`ConformanceVectorTest` in `core`), and — from Phase 1's
  flash — the glasses through the self-test (mode 16). All three must agree before a flash is called
  good. For v1 (the installed firmware) the C wrote the expectations; a disagreement is a finding about
  the simulator or the docs, never a reason to edit an expectation by hand.
- **The self-test form (2026-09-14; cache writes sent live since Phase 2, §4):** a drawing vector (every vector but
  `v1-lease` and those that release the lease)
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
- 2026-09-15 (night) — the review of Phase 2 (`HANDOFF.md` §61), fork and simulator changed together, the vectors
  regenerated from the C (every existing expectation unchanged) plus `v2-edges` and `v2-lifecycle`: op 5 takes 64..160 KiB
  (a smaller cache put the v1 modes' bounds past its end) and a request no longer shares the allocated size's field; a
  per-lens pair is checked whole on both lenses; mode 19's order is length → lease → DRAW2 → record; mode 16's refusals
  are recorded; mode 24's partial call is a Damage job's own (0, 0, 0, y0, 640, y1), and the refresh is full after a frame
  the panel-off path never transferred or while the overlay shows; §4 states each op's order, the v1 modes' order as
  built, the ignored high bit, and that a batch is not all-or-nothing. Contract version unchanged (not flashed).
