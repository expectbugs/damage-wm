# FIRMWARE.md — the Damage firmware contract

**Context for the reader.** This is the interface between Damage (the phone or PC compositor) and
the Damage build of the G2 custom firmware (`~/damage-cfw`, a fork of the published `g2flash`
firmware for Adam's own glasses). It describes messages, data formats and behaviour in words and
integer arithmetic. Two implementations follow it independently: the fork (C) and Damage's
simulator (`core/.../sim/GlassFirmwareSim.kt`, Kotlin, written from this text and never from the
C — `CLAUDE.md`, clean room). A conformance-vector set proves both agree, on the host and on the
glasses. Plain wording throughout.

**Status 2026-11-13:** v1 is g2flash `a5d1c31`'s contract, the base the fork keeps, pointed at in §2. §0 and §3 (the Phase 1
extension: DamageCaps, telemetry, flags, the presented notify, cache-keep, the self-test) are **installed on Adam's
pair since 2026-09-15** (fork pin `c5e4f8b7…`, `HANDOFF.md` §55); the boot count stays withdrawn (§11). **§4 is built
on both sides (Adam's word on `HANDOFF.md` §57's decisions; fork pin `48172b62…` after the fourth review, `HANDOFF.md` §60–§64) and installed on Adam's pair since 2026-09-16 (`HANDOFF.md` §65):** the
host C, the simulator and the v2 vectors agree, and the on-glass self-test ran the same day (`CLAIMS.md`). §5 and §6 are in
contract form since 2026-11-13, not yet challenged (the note above §5); §7 and §8 are the decided shape only. **Only the RIGHT lens can send** (`CLAIMS.md`): every reply and notify is
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
a value outside its range (a fill's level, a sub-op) · 13 a zlib or RLE stream that does not decode to its size (an inflate whose state the heap would not give it reads the same way) · 14 the display was still busy with the previous frame when the gate came free: the message is dropped whole and nothing of it reaches the shadow. The
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

## 5. v2 motion programs (the first flash, C1) — *contract draft 2026-11-13, from `MOTION.md` §6 as ruled; built with C1*

Grades as `CLAIMS.md`: **V** read (instruction level or the decompile of bytes that hash SAME, `fwread.py sha`), **M**
measured on Adam's pair, **I** inferred, **U** unknown. "Read 11-13" = `research/fwread.py` this sitting.

### 5.1 What C1 carries, and what it does not

- **The engine** (§5.2–§5.8): image mode 26 (play, retarget, close), program records in the texture cache, the verbs
  FILL · DRAW (a cached record or the area's own pixels, moved per lens, its brightness ramped) · REVEAL (wipe or
  slide-in from an edge) · WAIT, two built-in eases plus uploaded tables, flag bit 3 ENGINE, the notify field 114,
  telemetry 30–34. "Move a rect per lens" (A1) is DRAW from the area's own pixels (`self`); no separate code.
- **The riders (§5.9):** op 5 up to 256 KiB · telemetry 27–29, the largest free block per arena · mode 25, the deflated
  cache write (flag bit 5 ZWRITE) · the fork's three owed items: the cache pointer released by one exchange, the
  context-survival check, the stock "already fast" link test replaced.
- **Not in C1:** the vertical-scale draw (A2 → C2, §6.6) · a horizontal-scale op (A3: a card flip is three cached
  images swapped) · any op for fading content that is not cached (B1; a cached fade is DRAW's ramp) · dimming behind
  a popover (B2: mode 22 as built) · banners (B3) · anything that moves on its own (B4, B5; C1 "motion answers input"
  is the phone's policy, not a firmware rule) · Phase 5's cells (only if W4/W5 says they pay, `REPLAN.md` R7).
- **Patch sites:** the engine adds **none** (it renders inside the inherited copy hook, `display_copy_hook`, and runs
  a thread of its own); the link test is one call retarget at `0x004774AE` (§5.9), on the connection path.
  Contract version stays 2 (§0: added ops and bits do not bump it); DamageCaps bit 7 ENGINE, bit 8 the riders, bit 9
  the link test.

### 5.2 Where the engine runs (decided at instruction level as far as the image allows)

| fact | grade, basis |
|---|---|
| Every osTimer callback runs on the FreeRTOS timer task "Tmr Svc", priority 54, stack depth 0x1000 | V: `FUN_0047E674` creates it at `0x0047E6B6` (priority `0x36` at `0x0047E6A6`, name `0x0078EBCC`, depth from `FUN_004D3610`) |
| The display task `FUN_00473C44` ("displaydrv_mgr_task") runs at priority 45, stack 0x800 | V: attr `0x0075381C`, created at `0x00473962` |
| The input handler and the image worker's thread "ui_task" (`FUN_004437E0`) runs at priority 44, stack 0xC000, queue 96 × 12 B | V: attr `0x0075378C`, created at `0x00444842`; queue `FUN_00449A32(0x60, 0xC)` in `FUN_00444720` |
| The image worker runs on the ui_task | I (strong): the deferred consumer (`0x00496A0E`, in `FUN_00496544`) is called only from `0x004E16EC`, kind 3 of `FUN_004E1490`, the UI handler of registry entry `0x006A4520` {0xE0, data `0x004E1193`, UI `0x004E1491`}; the ui_task runs the registry's UI handlers (`research/fork-reads-2026-09-13.md` R0.5) |
| The display gate is held by whoever queues a type-3 refresh and given by the display task after the copy hook; its takers are the worker, the LVGL flush `FUN_004736F4` and three offset setters — not the EvenHub container teardown | V for the five callers of `FUN_0047381E`; I for the teardown (none of them is on it) |
| `FUN_0047381E` takes the gate through `FUN_00441C44(*[0x0047390C], 1000)` and only logs a failure; `FUN_00474066` queues a type-3 job through `FUN_00449ABE` with a 1,000 ms put and returns −1 on failure | V (decompile, SAME) |
| CMSIS-RTOS2 wrappers: osThreadNew `0x004490E2` (static control block ≥ 0x70 B), osTimerNew `0x004493B0` (periodic iff type ≠ 0), osTimerStart `0x00449498` (no wait), osTimerStop `0x004494D8`, osEventFlagsNew `0x00449590` (static ≥ 0x20 B), Set `0x004495E4`, Wait `0x0044969C` | V (shapes, SAME); I (names). Upstream's `seq_tick` uses the timer four on glass |

**Decision.** The engine never draws on Tmr Svc (priority 54), and only the image worker writes the shadow, as today
(the container's buffer is freed and rebuilt without the gate: I, above). Per lens: one one-shot osTimer, one
event-flags object and one **engine thread** (priority 44; 2 KiB stack, the display task's own size for the same
calls; every control block and the stack inside the context, so nothing is allocated per tick), created by the first
accepted PLAY or bound fire of a boot, never deleted. The callback only sets the flag. The thread waits on the flag
(its idle wait); on a wake with an instance drawing, it takes the gate through `FUN_00441C44` and reads the result,
then queues a type-3 refresh **with no direct job**; the display task's copy hook draws each open instance's due
step into the framebuffer (§5.3). A take that fails or a put that fails (the gate given back if held) re-arms the
timer one period later and is counted. The drawing runs on the display task, whose whole stack is 0x800 B (V): its
depth is bounded and checked at build.

### 5.3 The tick

- **Time base:** the OS tick, 1.024 per ms (M, §3). Each instance carries its own period **P in OS ticks, 16..128**
  (≈ 64 Hz .. 8 Hz), from its PLAY; A4's 30 Hz is **P = 34** (33.2 ms), a phone constant moved at the feel session.
- **Steps:** an instance of T steps (1..255) has step k due at `t0 + (k−1)·P`, t0 = the OS tick at which this lens
  handled the PLAY. **Step 1 is drawn by the PLAY's own present.** At every present while an instance draws — an
  engine refresh or any phone frame — the copy hook draws `k = min(T, 1 + ⌊(now − t0) / P⌋)` (again when a phone
  frame has just copied the shadow over it); steps passed over are **folded** (not drawn, counted); step T is always
  drawn. Then it re-arms the timer for the earliest next due time over the open instances (at least one tick ahead),
  or leaves it stopped.
- **Overrun:** nothing queues. At most one engine refresh is outstanding (the flag coalesces; the thread queues only
  when none is pending; the copy hook clears the mark). A slow present folds the next step; the end time holds.
- **What a present costs (M):** a full transfer 2.0 ms median / 5.3 p90 (Phase 1 build, n = 2,996, `HANDOFF.md` §65)
  and 4.4 ms median / 8.5 max (Phase 2 first light, n = 28); the copy 0.55–0.63 ms; a partial ≈ 1.6 ms + 66 µs/row.
  **The step's own drawing time is U** (field 34): it, not the panel, sets the floor under P. At P = 34 the panel
  path uses ≈ 3–5 ms of 33 (from the measured parts).
- **Refresh rules:** a present that draws a step goes whole (a mode-24 hint is ignored for it); the present after an
  instance's last step marks the panel stale (§4), so the next one goes whole. Engine refreshes are not stamped as
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

- **kind / src:** FILL — kind is the level (0..15). DRAW — kind 0 a v2 image record at `src` (off4) · 2 `self`.
  REVEAL — 0 a record · 2 `self` · 3 a solid level `src` (0..15) of width dxL and height dy. `self` = this track's
  area as it was in the shadow when the PLAY ran (captured then, per lens). Non-per-lens tracks use xL/dxL on both
  lenses. **h0, h1, anchor must be 0 in C1** (§6.6 gives them meaning).
- **Eases:** E[0] = 0 always. Linear `E[j] = ⌊4096·j / n⌋`; ease-out `E[j] = 4096 − ⌊4096·(n−j)² / n²⌋` (u32
  arithmetic, no overflow at n ≤ 255); table: the record at `table` (off4), whose n must equal the segment's steps
  (else 5). An eased value: `v(j) = v0 + ((D · E[j] + 2048) >> 12)` on a signed 32-bit product, `>>` arithmetic, so
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
    2 left / 3 right; `opt` bits 0–1 edge, bit 2 slide). Wipe: the source at rest (x, y), visible only in the e rows
    or columns at that edge of its rect. Slide: the source moved in from the edge by F − e (top: drawn at
    y − (F − e), visible [y, y + e); bottom: at y + (F − e), visible [y + F − e, y + F); left and right alike).
  - FILL: the area filled with its level.
- **The declared end state END(prog, P)** = every track's state at step T painted in track order. It is defined by
  the record and the parameters; the phone does not send it. In §4's vocabulary it is a fill (mode 21) per level
  backdrop, then per DRAW a mode-17 draw of the record (or of the capture) at its final position and level under a
  mode-20 clip of the area (for `self`, the model's own copy of the captured pixels), per REVEAL the whole source at
  rest — so the phone's model applies exactly these ops.

### 5.5 Mode 26 — motion control (image lane; both lenses run it; takes the display gate)

`[26][sub]…`; allowed alone or as a mode-8 sub-message; refused inside a self-test step (8). The high bit is ignored.

| sub | payload after `[26][sub]` | what it does |
|---|---|---|
| 0 PLAY | `slot u8 (0..3) · period u8 (16..128) · prog u16 (off4) · tag u16 · P0..P3 s16` (14 B) | closes the slot's instance and every open instance whose areas meet this one's; captures the `self` areas; **writes END into the shadow**; keeps the CRC; opens the instance; step 1 drawn by this message's present |
| 1 RETARGET | `slot u8 · P0..P3 s16` (9 B) | for an open instance (drawing or done): writes END(prog, new P) into the shadow; restarts the instance at step 1 now, each moving segment from the state this lens last drew to the new final state (a leading WAIT restarts whole); END ordinal + 1. Only for programs whose tracks have at most one non-WAIT segment after an optional leading WAIT (else 12) |
| 2 CLOSE | `slot u8` (0xFF all) (1 B) | a drawing instance stops drawing (the next present shows the shadow, which holds END, whole); a done one frees its captures; an empty slot: nothing |

**Check order (the reason recorded is part of the contract):** length (1) → a self-test step (8) → the lease (3) →
DRAW2 (9) → ENGINE (15) → slot, period, P values (12) → for RETARGET an empty slot (16) → the program record and
every record and table it names, inside the cache and well formed (5) → areas inside the panel, not empty, a pair
one size and one row range — **every pair checked whole on both lenses** (2) → a `none` backdrop's area (both rects)
inside an earlier track's area whose backdrop is not `none` (2) → every step's values in range: levels 0..15,
extents 0..F, h0/h1 = 0 (12) → the scratch pool (7) → allocations: captures, and the thread, timer and flags at
first use (11). Only then is anything closed, captured or written. "Meets" = the bounding boxes of the two pairs
intersect, the same decision on both lenses.

### 5.6 The end-state rule, the region, and the cases

- **END is written into the shadow when the PLAY (or RETARGET) is handled**, in stream order on both lenses; steps
  draw only into the framebuffer, over the copied shadow. The phone's model applies exactly END at the same message
  — so the shadow and the model never depend on timing. RIGHT's CRC-32 of its shadow over the instance's areas
  (each area's tight packed rows, `(w+1)/2` bytes, high nibble first, in track order), taken right after that END
  write, is reported in the instance's notify (§5.8); the phone compares it with its model's: **a mismatch is a
  `divergence` note and a keyframe, never silent.**
- **A phone write inside a drawing instance's area** lands in the shadow at once (the model agrees); the lens shows
  the motion over it until the instance's last step, then goes whole and shows the shadow. That is the draft's
  "held until the program ends", with nothing held in a queue. To show it at once the phone CLOSEs first (same batch).
- **The cases:** a phone flush touching a running area — as above. A **keyframe** (mode 6) or **mode 11** closes
  every instance. **A lease lapse, FB_RELEASE, a fresh acquire** (and so Silent Mode, where the shell releases on
  purpose) are release points: every instance ends, nothing is drawn after, no notify is sent; the copy hook draws
  only over a Damage frame it copied or preserves (`direct_active` with a live lease), so stock content is never
  drawn over. **The link dropping:** the lease runs on (≤ 87.9 s), the instance ends by itself, its notify is lost;
  the next session's acquire ends what is left. **A retarget after the last step** re-aims from the final state (a
  new motion to the new END); after a CLOSE or a release point it is refused (16) — the phone tracks closes in stream
  order, so it sends none. **A second PLAY over a busy area** closes the first (its END is already the shadow the
  second captures from). **A record, table or program evicted:** a cache write (12, 19, 25) whose entries meet bytes
  an open instance reads closes that instance first (that write takes the gate to do so).

### 5.7 Memory (what must be measured first, and what happens when it is short)

- **Fixed:** the instance table (4 slots, ≈ 0.1 KiB each), the thread's 2 KiB stack and the three control blocks
  live in the context (allocated once, from the stock malloc's arena, our 20: I from upstream's labels, `HANDOFF.md`
  §67). Nothing per tick. **Per PLAY:** the `self` captures, priced against the save-under pool (48 KiB per lens, §4
  mode 23) together with the slots. **Per mode 25:** a transient inflate (§5.9).
- **Measured before C1's budget is fixed:** arena 13's free memory (fields 7–9) before the carrier CREATE, after it
  and after the first frame (an APK change, `REPLAN.md` §5), then fields 27–29 on the candidate with the cache at 160
  and at 256. Modeled today (`HANDOFF.md` §67): ≈ 130 KiB free at 256, ≈ 82 with the pool full, before a mode-25
  decode's ≈ 40–56 KiB; the self-test's 150 KiB scratch needs a session at ≤ 160.
- **Short:** a refusal and nothing written — 7 over the pool, 11 an allocation failed — with notify outcome 2 at
  once; the phone draws that motion as today's frames. No op ever writes past an allocation.

### 5.8 Notify, telemetry, flags, capabilities

- **Notify field 114 DamageMotion (RIGHT only)**, `G2SettingPackage{1: 3, 2: 0, 114: …}`: `1 sequence (since boot) ·
  2 slot · 3 tag · 4 outcome (0 drew its last step · 1 closed before it · 2 refused) · 5 END ordinal (0 the PLAY, n
  the n-th RETARGET) · 6 steps drawn · 7 steps folded · 8 the END CRC · 9 the largest step-drawing µs · 10 the reason
  (outcome 2) · 11 uptime`. Sent each time an instance stops drawing (its last step, or closed while drawing), with
  the last END written, and once per refusal; from the display task (the presented notify's precedent, in daily
  use) or the ui_task. **A lost notify** costs pacing only: END is already in the shadow; the phone's scheduler
  resumes on its own clock at the instance's nominal end and reads field 30 at its next telemetry read; a sequence
  gap is journaled.
- **Telemetry:** `30` open slots (bits 0–3), drawing (4–7), the engine thread exists (8) · `31` the last notify-114
  sequence · `32` engine frames since boot · `33` steps folded since boot (a failed take or put folds its step) ·
  `34` the last present's step-drawing µs (the DWT figure, §4's calibration).
- **Flags:** bit 3 **ENGINE** (needs DRAW2 armed too), bit 5 **ZWRITE**; both clear at every release point (§3) and
  are refused without the lease (status 3). The keeper arms one new bit per soak (ZWRITE, the 256 KiB size, then
  ENGINE) and its hold-back drops the bit armed last (§10; the per-bit record is a phone change).
- **Reasons:** 15 the op's own flag not armed (ENGINE for 26, ZWRITE for 25) · 16 no such instance.

### 5.9 The riders

- **Op 5 CACHE_SIZE: 64..256 KiB** on a bit-8 build (status 5 outside it); the v1 window stays the first 64 KiB.
- **Fields 27/28/29:** the largest free block of arenas 13/20/27 in KiB (the same bounded, range-checked walk as 7–9;
  omitted when an arena does not validate) — a cache must be one block.
- **Mode 25, the deflated cache write:** `[25][zlib stream]`, inflating to exactly a mode-19 entry list (no mode
  byte), at most 16,384 B. Order: length ≥ 3 (1) → not in a batch or a step (8) → the lease (3) → DRAW2 (9) → ZWRITE
  (15) → the inflate: its state and the 16 KiB output from arena 13 (11), a stream that does not end cleanly or runs
  past 16,384 B (13) → then mode 19's own checks and semantics on the list (an empty list writes nothing and does not
  bump; one bump per message). Cache writes were 61 % of the image lane's bytes and per-message deflate took them
  to 57 % (M, `HANDOFF.md` §68, v1-era traffic). The phone's ledger (field 17) proves each write; a refused mode 25
  is re-sent as mode 19.
- **The cache pointer's release:** claimed by one exchange like the slots and the scratch (FBW-2, `HANDOFF.md` §76),
  and deferred while the copy hook draws (the engine's busy word, §5.11). No wire change.
- **The context-survival check:** after a lens restarts, nothing of a previous boot's Damage state is used — flags,
  the cache, slots, captures, instances, bindings, the engine's handles; fields 16, 17, 31–33 restart at 0. A stale
  context would hand the engine a thread handle from the last boot, so this is **a precondition for ENGINE**. First
  the free check: a `keeper: the glasses reset` note followed by field 16 continuing rather than restarting means the
  context survived (`HANDOFF.md` §74 item 9). The mechanism is the fork's; one shape: a context whose recorded uptime
  is ahead of the OS tick is from an earlier boot and is rebuilt without freeing anything (I: it misses a restart in
  the previous boot's first seconds).
- **The link:** the stock test that counts any link under 31.25 ms as already fast (`0x00476F4E`, from the 0xA3
  branch at `0x004774AE`, V) is replaced by one that counts a link fast only at ≤ 11.25 ms with latency 0
  (upstream `845f665`'s rule), with the correction our edit (3) needs (the survey's reading of the slow mode byte,
  `HANDOFF.md` §74 item 7: not re-read). DamageCaps bit 9; the phone journals every parameter change.

### 5.10 Vectors (§9's form), and what no vector can prove

New ops: `{"wake": true}` = one engine wake (the thread's take and queue, then the display task's copy hook and
refresh, synchronously); expectations gain `"N"`: RIGHT's notify-114 bodies, hex, in order. Engine vectors carry
`"panel": true`: `L`/`R` prove END in the shadow, `P` the step on the lens. **No self-test form** (a step presents
nothing); mode 25 runs live. Inputs from `make_vectors.py`, expectations from the C (`--write`), the simulator equal.

`v3-play` (one FILL and one DRAW track: END in the shadow at the PLAY, step 1 on the panel, wakes at chosen ticks,
the last step, the stale mark after) · `v3-draw` (record and `self` sources, per-lens x and dx, the ramp, transparent
and inverse, clipping at the area, a late wake folding three steps) · `v3-reveal` (wipe and slide, four edges, a
level source, F = 1) · `v3-ease` (linear and ease-out at n = 1, 2, 7, 255; an uploaded overshoot table; E[n] ≠ 4096
and n ≠ steps refused) · `v3-params` (each pref field, a u8 field taking the low byte, a substituted value out of
range) · `v3-retarget` (drawing, done, after CLOSE → 16, a multi-segment program → 12) · `v3-close` (CLOSE one and
all, a PLAY into the slot and over an area, mode 6, mode 11, a cache write over a drawn record and beside it) ·
`v3-order` (one refusal per reason in the stated order, both rects of a pair checked on both lenses) ·
`v3-lifecycle` (ENGINE before and after DRAW2, release points ending instances, nothing drawn over stock content) ·
`v3-hint` (a phone frame with a mode-24 hint while an instance draws goes whole) · `v3-zwrite` (mode 25 equal to the
same list as mode 19, one bump, an empty list, 16,384 B and one more, a broken stream) · `v3-cache256` (op 5 at 256,
a record past 160 KiB, the v1 window unchanged).

**No vector can prove** what needs time, threads or two radios — the host harness has one thread and no timers:
the timer firing, the thread's wake, the gate shared with the ui_task and LVGL's flush, the copy hook's drawing
time, the lens skew, memory fragmentation, the notify reaching the phone. **What proves them:** the notify's END CRC
equal to the model's on glass (RIGHT), fields 32–34 over a soak day, `glassdrive.py` scripted plays, the feel
session (lens match by eye, the 240 fps video), the journal (no hold-back, the folded count).

### 5.11 The failure envelope (`FORK.md` §3.8)

| new state, wait or guard | (a) cleared at | (b) a write that never lands | (c) keyed on | (d) other tasks, the other lens | (e) one read | (f) siblings checked |
|---|---|---|---|---|---|---|
| instance slots (state word, prog, params, tag, t0, period, T, ordinal, last drawn values) | every release point, mode 6 and 11, CLOSE, a PLAY into the slot, a restart (survival check) | END is in the shadow whatever happens; a lost notify is pacing only (§5.8) | slot + tag + ordinal; bytes by off4 range, closed when rewritten | written on the ui_task under the gate; read and the drawn values written by the copy hook under the same gate; a release point only flips the state word | the copy hook reads each state word once per present | PLAY, RETARGET and C2's COMMIT share one validator; alone and in a batch; both rects of every pair on both lenses |
| `self` captures | CLOSE, a done instance closed, release points, restart | the PLAY is refused (7/11) before anything is captured | slot | freed on the ui_task under the gate, or at a release point through the busy word (deferred to the copy hook's epilogue) | each pointer claimed by one exchange | the save-under slots (same pool, same word pattern) |
| the busy word around the copy hook's drawing | the copy hook's epilogue | — | — | release points on the settings task or wherever a lapse is noticed | one exchange | the scratch's and the slots' words; the cache pointer (rider) |
| engine thread, timer, flags | never deleted; idle with nothing open | creation fails → that PLAY refused (11); tried again by the next | per lens, per boot | the timer only sets the flag; the thread only takes the gate and queues | — | upstream's `seq_tick` timer |
| the thread's gate take and queue put | — | a failed take (its result read, not inferred from `direct_pending`) or put: gate given back if held, timer re-armed one period on, counted (33) | — | the ui_task (worker, C2's hook) and the LVGL flush take the same gate | the take's own result | the worker's inference (`HANDOFF.md` §74 item 9) |
| one outstanding engine refresh | cleared by the copy hook | a refresh the display task drops (panel off: the stock gate at `0x00473C96`) still ran the hook, so the mark clears | — | set by the thread, cleared on the display task | — | — |
| close-on-meet guards | — | — | pair bounding boxes; off4 byte ranges | decided on the ui_task in stream order, so both lenses decide alike | the slot list read once per message | modes 12, 19, 25; modes 6, 11; PLAY over PLAY |
| drawing only over a Damage frame | — | — | `direct_active` and a live lease, as the copy hook already decides | display task | one read of the copy hook's own decision | the diagnostic overlay stays on top; a mode-24 hint is ignored |
| mode 25's inflate and output | the end of the message | refused (11/13); the ledger re-sends as mode 19 | — | ui_task only | — | mode 19's own checks reused, not copied |

### 5.12 Not in this contract yet

The default P (feel session) · a hint for small step areas (after field 34's numbers) · a string source for DRAW
(today: draw the text, then DRAW from `self`) · more than 4 instances, 8 tracks or 255 steps · LEFT's verification
(an inter-lens report) · the pool and cache numbers (after §5.7's readings) · a compression context kept across
messages (`HANDOFF.md` §68: re-measure first) · on-glasses compaction (§74 item 8).

## 6. v2 local input and bindings (the second flash, C2) — *contract draft 2026-11-13; built with C2*

### 6.1 What reaches the hook (read 11-13 unless cited)

| fact | grade, basis |
|---|---|
| Every input event reaches the ui_task as a type-7 queue message; the loop calls `FUN_00442D86(record 0x2034DC30, len)` at `0x00443FB0`, its only caller | V (`0x00443FA4`–`0x00443FB0`, `fwread.py calls`) |
| The record is `{u16 devType, u32 eventId, u32 value}`, delivered on both lenses by the sync listeners, after RIGHT's input manager's de-bounce and 1 s cross-device lockout | V (`CLAIMS.md`, R0.1) |
| **The temple touchpads' slides are event ids 4 and 5**: the gesture processor's dispatch (`0x00503172`–`0x00503226`) posts, per mask bit, id 0xD (bit 0), 0xE (1), 0 (2; ≥ 10 taps → 0x1010), 1 (3), 3 (4), **5 (5) and 4 (6)** with diffX and speed, devType 1 on RIGHT else 0 (`FUN_004C5874`) | V. The bit names (bit 5 SLIDE_L, 6 SLIDE_R) come from the name table at `0x00503250`: I |
| **The ring's swipes are ids 5 and 4 too**: `FUN_0047269E` maps ring report type 0 → 3, 1 → 0, 2 → 1, **4 → 5, 5 → 4**, 8 → 0xE, devType 4, after its 100-ring-tick filter | V |
| Stock's names: 0 SINGLE_CLICK · 1 DOUBLE_CLICK · 3 LONG_PRESS · **4 UPROLL · 5 DOWNROLL** · 0xD PRESS · 0xE RELEASE · 0x1010 DECA_CLICK; devType 0 TOUCH_BAR_LEFT · 1 TOUCH_BAR_RIGHT · 4 RING_TOUCH_SENSOR | V (`0x004C5892`, `0x004C58EE`, the strings) — corrects `CLAIMS.md`'s "2, 7, 8 by elimination" |
| Which of 4 and 5 the EvenHub page sends as the kind-2 scroll's direction 1 (top) or 2 (bottom) | U: the page's callback tables for UI codes 0x44/0x45 (`0x0049556C`…) are unread; the phone pairs the applied-state's id with the scroll it receives on the first gesture and journals the pairing |

### 6.2 The hook

- **Site:** `bl 0x00442D86` at `0x00443FB0` → `damage_input_hook(record, len)`, C2's one new site, on the ui_task's
  event loop. It runs on every touch from boot; before the first Damage message there is no context, so it is one
  validated read and the stock call (the copy hook's shape). `FORK.md` §3.1: an event loop, as `0x00473D80` was read.
- **It never consumes:** in every path it calls `FUN_00442D86` with the unchanged record and length and returns its
  result, after its own work — so the stock path and the phone's kind-2 or kind-1 message stay exactly as today.
- **Pass-through:** no context, BIND (flag bit 4) not armed, or no matching entry → nothing else (a flag test and at
  most 8 two-byte compares; the cost is modeled only). Never bindable: 3 (the long press is unattributed —
  `CLAUDE.md`), 0xD, 0xE; 0x1010 never reaches the ui_task (the input manager sends it as `0x109`, R0.1).
- **Fails toward today:** any check that fails in a fire — the lease (the check settles a lapse, which clears every
  binding), the program record, a parameter, the pool, an allocation — leaves no local draw, sends the applied-state
  with the reason when an entry matched, and passes through; the phone drives that gesture as it does today. The
  table lives in the context, validated whole at SET; the program is re-validated at every fire (the cache may
  change). A fire waits only on the display gate (`FUN_00441C44`, the take the worker makes on this thread); its
  time is field 37.

### 6.3 Lists and bindings (image mode 27; both lenses, in stream order; not gated)

| sub | payload after `[27][sub]` | meaning |
|---|---|---|
| 0 SET | `entry u8 (0..7) · devType u8 (0, 1, 4, 0xFF any) · eventId u8 (0, 1, 4, 5) · list u8 (0..3) · step s8 · slot u8 (0..3) · period u8 (16..128) · prog u16 (off4) · 4 × {kind u8, arg s16}` (21 B) | a binding. Pi = arg (kind 0) · the list's item off4 at (new cursor + arg) (kind 1) · new cursor × pitch + arg (kind 2, s32, refused outside s16) |
| 1 CLEAR | `entry u8` (0xFF all) | removes entries |
| 2 LIST | `list u8 · items u16 (off4 of a table of count off4 entries, or 0xFFFF none) · count u16 (1..1024) · cursor u16 (< count) · pitch s16` (9 B) | the staged range: positions 0..count−1 are on the glasses |

Order: length (1) → a self-test step (8) → the lease (3) → DRAW2 (9) → BIND (15) → values (12) → SET: the program
exists, qualifies as a PLAY and uses **record and level sources only — no `self`** (5/12), so a local draw depends
only on the cursor and the cache. Every accepted mode-27 message bumps the lens's **binding epoch**. Release points
clear every entry and list (§10).

### 6.4 A bound fire, and the staged edge

On a matching event (each lens, on its ui_task, inside the hook): the new cursor = cursor + step. **Outside 0..count−1,
or a kind-1 item outside the list: the staged edge** — nothing is drawn (no bounce: the end of what is staged), the
applied-state says `edge`, the stock path delivers the gesture and the phone refills or ends it. Otherwise: PLAY's
validation (§5.5) on (prog, P) → close the slot's instance and any instance whose areas meet → open a **local
instance**: it draws its steps like any other but **writes nothing into the shadow**; after its last step it keeps
drawing its final state at every present until COMMIT or DISCARD. Then the cursor moves, RIGHT sends the applied
state, and the hook calls the stock handler. Step 1 is drawn by an engine refresh the hook queues (it holds the gate
it took): the local response begins within one copy and transfer of the event reaching the ui_task (modeled
≈ 5–10 ms; `FORK.md` D7 asks ≤ ~20 ms).

### 6.5 The applied-state event and reconciliation

- **Notify field 115 DamageApplied (RIGHT only):** `1 S (the local sequence since BIND was armed) · 2 devType ·
  3 eventId · 4 entry · 5 outcome (0 drew · 1 edge · 2 refused) · 6 reason · 7 list · 8 cursor after · 9 slot ·
  10–13 P0..P3 (their 16 bits) · 14 the binding epoch used · 15 uptime`. It is queued before the hook calls the stock
  handler, so it precedes that gesture's kind-2/kind-1 message (I: both are sent from the ui_task through the stock
  senders' queue; that `FUN_004DA16A`'s path ends in the same queue as `FUN_00475B14`'s is unread). A gesture with no
  field 115 before it is unbound; one that arrives after the phone has already handled that gesture is DISCARDed.
- **Mode 26 gains (in stream order, so both lenses apply them at the same position):**
  `3 COMMIT · slot u8 · S u16 · list u8 · cursor u16 · prog u16 · P0..P3 s16` (16 B): validates as a PLAY, writes
  END(prog, P) into the shadow, sets the list's cursor, and makes the slot's local instance S an ordinary one (a newer
  local instance in the slot is left drawing; an older one is closed); one notify 114 follows with the END CRC.
  `4 DISCARD · slot u8 · S u16 · list u8 · cursor u16` (6 B): stops every local instance ≥ S in the slot (the next
  present shows the shadow, whole) and sets the cursor.
- **Reconciliation.** The phone treats a field-115 `drew` as an operation of its working copy: END(prog, P) and the
  cursor, committed by its next message to the glasses (COMMIT carries RIGHT's parameters, so a lens that used
  another epoch is corrected there); it DISCARDs when its own state has moved on. Until the COMMIT the lenses may
  differ only if a mode-27 message reached them on either side of the gesture (I: a window of one inter-lens hop);
  after it, both shadows equal the model by construction. **A COMMIT CRC that differs from the model's is a
  `divergence` note and a keyframe, never silent.**

### 6.6 The vertical-scale draw (A2, carried by C2)

- **Mode 28** (`0x9C` per lens): `off4 u16 · x s16 (or xL · xR) · y s16 · options u8 · H u16` — the v2 record drawn
  `H` rows tall (1..2048): output row r takes source row `⌊(2r + 1)·h / (2H)⌋`, columns as they are; clip, options and
  transparency as mode 17; it takes the gate as mode 17 does. Order: no shadow (4) → length (1) → the lease (3) →
  DRAW2 (9) → H (12) → the record (5). Allowed in a batch and in a self-test step.
- **DRAW's h0, h1, anchor** (zero in C1): the output height eased from h0 to h1 (both 1..2048, or both 0 = unscaled);
  anchor 0 top · 1 centre (`y + ((h − H) >> 1)`) · 2 bottom (`y + h − H`), h the source's height. The wheel's drum
  (`MOTION.md` W1/W2), the icon grow (M2) and Pulse use it.

### 6.7 Flags, telemetry, capabilities

Flag bit 4 **BIND** (needs ENGINE and DRAW2); clears at every release point with every entry, list and local
instance. Telemetry `35` bound fires since boot · `36` the last S · `37` the last fire's µs inside the hook. DamageCaps
bit 10: modes 27, 28, mode 26's subs 3 and 4, fields 115 and 35–37. Reason 15 covers BIND for mode 27.

### 6.8 What waits on C1's measured numbers

The period (feel session) · field 34's drawing time per area size → whether a full-height page slide or only bands
fit a step, so how strips are staged · fields 27–29 at 256 KiB → how many items a list stages · the lens-skew verdict
(if visible, C2 needs a start message between the lenses, not designed here) · the folded count under a working
shell · the ring filter's effect on fast spins (`CLAIMS.md`: the 100-tick filter; its unit unread).

### 6.9 Vectors

New op `{"input": [devType, eventId, value]}`: the record through the hook (the stock handler on the host only counts
calls); `"N"` gains field-115 bodies. `v4-bind` (unbound and unarmed pass-through with the stock call counted, a fire
drawing on the panel and leaving the shadow, ids 3/0xD/0xE refused at SET, `any` against a source) · `v4-edge` (both
edges, a kind-1 item past the list, the cursor unmoved) · `v4-commit` (COMMIT writing END, a newer local instance left
drawing, DISCARD ≥ S, the cursor set) · `v4-fail` (no context, the lease lapsed at the fire, a rewritten program,
the pool short: every one passes the record through unchanged) · `v4-vscale` (mode 28 at H = 1, h, 2h and 2048, per
lens, clipped; DRAW scaled with each anchor). The lens order of input against images, the notify order on the link
and the local latency need two radios and a clock: the journal's pairs of 115 and kind-2 messages, the COMMIT CRCs
over a work week (zero divergence, `REPLAN.md` §5's C2 stop) and the feel session prove them.

### 6.10 The failure envelope

| new state, wait or guard | (a) cleared at | (b) a write that never lands | (c) keyed on | (d) other tasks, the other lens | (e) one read | (f) siblings |
|---|---|---|---|---|---|---|
| binding entries, lists, cursors | release points, CLEAR, LIST, a restart | the glasses fall back to passing input through; the phone re-sends SET and LIST at each session start | entry and list index; programs by off4 (re-validated per fire) | changed only on the ui_task (I: the worker's thread); read by the hook on the same thread | the hook reads the entry once | SET reuses PLAY's validator; both lenses get the same messages |
| the binding epoch and S | release points (S restarts at 0 with BIND) | a lost field 115: the phone sees an unbound gesture, drives it, and DISCARDs the local instance its next telemetry shows open (field 30) | epoch per lens; S per lens | LEFT cannot report its S; COMMIT carries RIGHT's | one read per fire | — |
| local instances | COMMIT, DISCARD, a PLAY or fire over them, mode 6 and 11, release points | uncommitted, they keep drawing their final state; nothing reaches the shadow | slot + S | the copy hook draws them under the gate the hook or thread took | as §5.11 | phone instances (§5.11) |
| the hook's pass-through | — | — | devType + eventId | runs on both lenses' ui_tasks | one context read | the inherited long-press and release sites in `FUN_00442D86` stay as they are |

### 6.11 Not in this contract yet

Binding the long press (never) · per-source grammar beyond `any` or one source (the phone's design) · LEFT's report
(an inter-lens message) · a start message between the lenses (only if C1's verdict asks) · local input with the phone
away (the offline home, step G) · the kit's programs themselves (K).

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
  [ { "ops": [ {"tick": ms} | {"lease": "acquire"|"release"} | {"msg": "<hex>"} … ],
  "expect": { "L": "<crc32 hex>", "R": "<crc32 hex>", "rc": { "L": [..], "R": [..] } } } ] }`. A
  `msg` is one completed image message as the deferred handler receives it; `rc` lists each
  message's return (0 accepted, −1 refused) in order; `tick` sets the firmware millisecond clock
  (the lease deadlines run on it). Later phases add ops (program upload and play, §5.10) and
  `"scratch"` expectations; the self-test form is below.
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
