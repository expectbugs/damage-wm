# Firmware reads for the fork — Phase 0, 2026-09-13

**Context for the reader.** Damage is a personal window manager for Adam's own Even Realities G2
glasses, which run a published community firmware build (`g2flash`). `FORK.md` plans a fork of that
build so the glasses can draw and animate on their own. These notes record how the stock 2.2.6.10 main
app handles display refresh, link settings, the link between the two lenses, memory and its log
channel — the facts the fork's patches will sit next to. Display-rendering and UI work on hardware
Adam owns. Plain wording throughout (`CLAUDE.md`).

**How these were read.** The openCFW decompile corpus is the index
(`reference/evenRealities-openCFW/g2/research/corpus/apollo-main/ghidra/decomp/`); every fact that a
plan or a patch leans on was checked in the instructions of `fws/2.2.6.10/ota_s200_firmware_ota.bin`
with `research/fwread.py` (run address = file offset + 0x437FE0). The corpus showed both async refresh
entries as taking no arguments while the instructions read two stack arguments, and it lists no
function at the log handler's address — decide at instruction level.

**Grades.** V = read in the instructions or the corpus; I = inferred from V facts; U = unknown offline.
The load-bearing rows are in `CLAIMS.md` ("Firmware internals read for the fork"); this file keeps the
working detail a next read starts from.

---

## R0.3 — the refresh path (what a present costs)

- The display manager task `FUN_00473C44` waits on its queue forever and switches on the message type
  (V). **Type 3:** `bl 0x46CA14` at `0x00473C8E` (the stock copier, redirected by a5d1c31 to
  `display_copy_hook`), `bl 0x47386A` at `0x00473C92` (gives the display gate), a power-state check, then
  — if the panel is on — the six queued words go to `FUN_004CA564` at `0x00473CE4` (V). Type 6 does the
  same copy + gate + refresh after a status check (`FUN_004CA5BE(1)`).
- `FUN_004CA564` calls the active operations record's `+0x28` (async refresh) with the arguments passed
  through (V). The active record pointer is RAM `0x20074530` (literal at `0x004CA664`).
- **Operations records** (V, read with `fwread.py word`): A6N-G `0x0070AFE4` (15 callbacks, type word 1)
  and JBD4010 `0x0070B024` (14 callbacks, type 0). Offsets: `+0x00` terminate · `+0x04` chip id ·
  `+0x08` MSPI init · `+0x0C` panel init · `+0x10/+0x14` power up/down · `+0x18` brightness · `+0x1C` clear ·
  `+0x20` current · `+0x24` display offset · `+0x28` async refresh · `+0x2C` blocking partial refresh ·
  `+0x30` status/recovery · `+0x34` set mode · `+0x38` serial id · `+0x3C` type.
- **JBD4010 async refresh `FUN_00592DEA`** (V, instructions): clamps stack args 5/6 to 639/479 (stores
  them back, never reads them again), copies the 28-byte request template from `0x0076B2B4`
  (`+0x0C` count = `0x25802` = 153,602 B), sets the framebuffer pointer and the MSPI handle, calls the async
  QSPI write `FUN_0059CE1E`, then sends latch command `0x97` (`FUN_005926EE`) and `osDelay(2)`.
- **A6N-G async refresh `FUN_005BCD44`** (V): the same clamp, template `0x0076B164` (count `0x25802`,
  instruction word `0x32002C00`), `FUN_0059CE1E`; returns 0 whatever the transfer returned.
- **`FUN_0059CE1E`** (V): switches the MSPI to quad, cleans the cache over the buffer, submits a
  non-blocking transfer, waits on the completion semaphore for up to 3,000 ms (`FUN_0044994E`), switches
  back to serial. It runs on the display manager task, so presents are serialized there.
- **Blocking partial refresh (`+0x2C`)** (V): JBD4010 `FUN_00592CB4(x_off, y_off, x0, y0, x1, y1)` writes
  one QSPI transfer per row (command `0x62`, a row address word, `(x1−x0)/2 + 2` bytes), then latch `0x97`
  and a 1 ms delay. A6N-G `FUN_005BCC18` loops over the rows but contains no transfer call (checked with
  `fwread.py dis 0x5bcc18 0x5bcd2c`: only byte saves and restores), then a 1 ms delay.
- **Panel selection** `FUN_004C9F32` (V): config key 1's first byte `== 0x06` → A6N-G, otherwise JBD4010.
- **Open:** the MSPI clock (the transfer time follows from it; the quad configuration pointers are RAM
  `0x20074538`/`0x2007453C`, set at init); which panel Adam's pair has.
- **The display gate** `FUN_0047381E` takes the semaphore with a 1,000 ms wait (`FUN_00441C44`) (V).
- **a5d1c31's timing** (V, `debug.c`/`zlib_glue.c`): `last_worker_us` times `image_worker`;
  `last_present_us` times `copy_panel` + the cache clean inside `display_copy_hook` — before the refresh.

## R0.4 — the link-parameter policy (`platform\ble\app_connect_params.c`, `[0x00476CBC,0x004787A4)`)

- Events: **0xA3 fast, 0xA4 slow/idle, 0xB9 application request** (V).
- Classifiers (V): `FUN_00476DB8` — interval < 25 units (31.25 ms) → 0xA3 else 0xA4; `FUN_00476FE2` —
  interval < 72 units (90 ms) → 0xA3 else 0xA4; which one applies flips on a "slow requested" flag read in
  `FUN_00477ADC`. The measured sets fit: 15 ms / latency 1 → 0xA3; 105 ms / latency 4 → 0xA4 (I).
- **The slow timer** (V, instructions): `FUN_00477774` (connect) ends with `bl FUN_0046C630` (the role
  check) and, when it returns 0, `movw r2,#0xEA60; movs r1,#0xA4; movs r0,r4; bl FUN_0047697E` at
  `0x00477A5A`–`0x00477A62` — a 60,000 ms one-shot carrying 0xA4. `FUN_00477ADC` (update finished)
  re-arms 0xA4/60,000 after every accepted update, with 2/4/10/30/60 s backoffs on transient states.
  `FUN_00478252` schedules 0xA4 after a caller's delay (13 external callers).
- Fast paths already in the firmware (V): `FUN_00478110` (optionally 0xA4/60,000, then 0xA3 at once),
  `FUN_004781F4` (the OTA fast mode), `FUN_00478160` (the ESS fast mode).
- **Every submit enters `FUN_00476CBC(event, connection)`** (V): called from the 0xA3/0xA4 branches of
  `FUN_004782DC` and from `FUN_0047720C`. Entry: `push {r4,r5,r6,lr}; sub sp,#0x20; movs r5,r0; movs
  r4,r1` = bytes **`70 b5 88 b0 05 00 0c 00`** (read from the image; resume at `0x00476CC4`).
  The candidate F1.6 site: an entry wrapper that turns 0xA4 into no request while the framebuffer lease
  is held, the shape of a5d1c31's `even_ai_display_ctrl` entry.
- **Open (U):** the parameter words the submit requests (built below this object, in the stack's submit
  path — the profile pointers bound in `FUN_004780DC`); the checks inside `FUN_00476CBC` that decide
  whether a fired 0xA4 actually requests the slow set (the link sat at 15/1 most measured hours);
  where latency 1 comes from (M0.3's capture).

## R0.2 — the link between the lenses

- Objects (V, openCFW docs + bodies): `uart_sync.c` `[0x00541790,0x00541AF8)` (a TinyFrame worker over a
  UART, a 24,576 B receive stream, one wake drains ≤ 32 chunks of ≤ 1,024 B); `sync_framework.c`
  `[0x0045A578,0x0045EC7C)`; `sync_interface_api.c` `[0x004646F0,0x00466010)`.
- **The image completion** (V): `FUN_004DA382` builds message id 0x0B with a 16-byte body and posts it
  locally; only on the RIGHT lens (`FUN_0045A570() == 1`) it forwards to the peer —
  `FUN_00464BB2(0xE0, body, len, 0)`, or `FUN_0045AACA(0xE0, body, len, 300)` for id 0x4C. So RIGHT emits
  and both lenses run the deferred handler (a5d1c31's comment confirmed).
- **`FUN_00464BB2`** → `FUN_00464772(app, body, len, tag, 3, 2, 0)` (V): checks the peer link is up,
  refuses a body of 10,241 B or more, copies it into the sync pool, queues it; never blocks. The send a
  Phase 3 "start program N at tick T" message can use from the EvenHub task.
- **Open (U):** the UART rate (`FUN_00584BC0` programs it from a config struct); a one-way time for a
  24-byte frame is sub-millisecond **modeled** only.

## R0.6 — heap arenas

- Arena 13: descriptor `0x20000354`, base `0x2013BE70`, 839,680 B = 0xCD000 (the texture cache's; the size
  `FUN_004842E6` passes to the arena init at `0x004842F6`, read 2026-09-14 — an earlier note said 839,808). Arena 20: gated
  by `*0x20074ABC == 0x20208E70`, 460,800 B. Arena 27 (primary): descriptor `0x20000338`, base
  `0x20279670`, 184,320 B less the 1 KiB a5d1c31 reserves (V, a5d1c31 `malloc.h`/`debug.c`).
- The EvenHub carrier's two 165,888 B buffers (A display, B reconstruction) cannot fit arena 27, so they
  sit in arena 13 or 20 (I); the allocation call is not isolated (U). The overlay's `f13/20/27` decides.
- No dead static buffer ≥ 32 KiB found (U — no full census).

## The log channel (sid 0x0F, `app\gui\logger\logger_setting.c`)

- Handler at **`0x004592DC`** (V, instructions; not in the corpus function list): decodes
  `logger_main_msg_ctx` into a struct (cmd at +0, magic at +1, the union's tag at +2, value at +4) and
  switches on cmd.
- **cmd 1 BLE_LOGGER_SWITCH_SET** (V): requires tag 3 (bleTransEn); `FUN_00458DF0(on)` sets RAM
  `0x20074FE2` = on, `0x200746B0` = 1000 (0 when off), and calls `FUN_00448CB8(on, 0x005BF973)` — which
  stores the flag and the per-line sender in the log module's context. Answers `{cmd 1, magic, tag 3}`
  through `FUN_00475B14(1, 0x0F, …)`. No storage write on this path.
- **The per-line sender `FUN_005BF972`** (V): `{cmd 3 DEVICE_SEND_LOGGER_DATA, magic 0, logStr}` with at
  most 128 bytes of the line, one notification per line through `FUN_00475C1A(1, 0x0F, …)`.
- **The stream ends** at `FUN_00458E48`, called from `FUN_0044227E`, which the display thread calls at
  `0x00443A2C` ("startup applicationID = %d") and `0x00443F70` ("display thread switch display upgrade")
  (V) — so a session CREATE ends it; Damage's probe re-sends the switch after each start.
- **cmd 2** sets the global log filter (`FUN_0043D3A6`, `FUN_0043D0D4`); **cmd 4 REQUEST_FILE_NAME** first
  calls `FUN_00478110(0)` (the link's fast path), then scans `/log` and exchanges file lists with the other
  lens; **cmds 5/6** remove log files (V). Damage sends none of them.
- Related lead (U): the file export service names `eEvenFileServiceType_LOGGER_FILE` — the glasses' own
  log files might be readable over that service; not probed.

## Read 2026-09-14 (the review of this session's work)

- **The boot counter** (R0.5, partial): `FUN_004D96D8` (the KV reset/migration path; `FUN_004D9A84`
  before it is a small KV getter) at `0x004D99DE`–`0x004D9A38`: `ldr r4 = "kvbooCount"` (`0x0078BF6C`),
  `FUN_0054116E(0, key, sp+0x14, 4)` reads the value into a stack temporary, `adds r0, #1`, then
  `FUN_005411F2(0, key, sp+0x14, 4)` writes it back (V). No RAM word holds the value: `fwread.py refs
  0x20074988` finds no literal in the image. `FUN_0054116E` takes and gives a lock (`FUN_004490CC`) around
  a FlashDB lookup; its callers are `FUN_004D956C/959C/96D8` and `FUN_005105F0/00510620/0051079C`
  (V) — none in the sid-0x09 settings path. So a boot count for telemetry means calling the KV get from
  the settings context, which has no stock precedent there; the Phase 1 build omits field 13.
- **The settings sender copies**: `FUN_00475B14` → `FUN_0047564E(type, sid, buf, len)`: `FUN_00474CD2`
  (the pool allocator) gets `len + 14` bytes, `FUN_00439BE4` copies the body into it, the block is queued
  (V, decompile). A caller's buffer is free on return.
- **Arena init** `FUN_004842E6` (V, `0x004842E8`–`0x0048430C`): `FUN_0048413C(0x20000338, base, 0x2D000)`
  (arena 27, the a5d1c31 site patches the size to 0x2CC00), `FUN_0048413C(0x20000354, 0x2013BE70,
  0xCD000)` (arena 13), `FUN_0048413C(0x20000370, 0x20378D9C, 0x400)` (a 1 KiB third arena).
- Re-read and held: the transfer count word `0x25802` at both templates (`0x0076B2C0`, `0x0076B170`);
  the display task's type-3 sequence (`0x00473C8E` hook, `0x00473C92` gate give, the panel-on check,
  `0x00473CE4` refresh); the gate take's 1,000 ms (`0x0047383C`); the slow-timer arm at
  `0x00477A5A`–`0x00477A62`; `FUN_00476CBC`'s entry bytes; `FUN_00458E48`'s single caller `0x00442284`
  in `FUN_0044227E`, itself called at `0x00443A2C` and `0x00443F70`; the panel-record literal at
  `0x004CA664`.

## Boot-time paths in the inherited patch set

`~/damage-cfw/tools/verify.py` lists every changed site. Two of a5d1c31's sit on paths that run from boot
(V): the primary heap arena's size at `0x004842E8` (inside `FUN_004842E6`, the arena init) and the display
copy redirects at `0x00473C8E`/`0x00473D68`, which pass straight through for every stock refresh. `FORK.md`
§3.1 reads "no NEW site on such a path" since 2026-09-13.

## Read 2026-09-14 (evening): the senders, for the Phase 1 candidate

- **Only RIGHT sends.** `FUN_00475B14` (`0x00475B14`–`0x00475C1A`): guard 1 `FUN_004487AC` (a blocked
  state: OTA or shutdown flags; non-zero → return 0 after a log line 0x1ac); at `0x00475B6A` a check Even
  disabled (`movs r0,#1; nop; cmp r0,#0; bne 0x00475BB6` — the fall-through block, log line 0x1b0 and
  return 8, is what Ghidra reported as unreachable); at `0x00475BB6` `bl FUN_0046F258`, and when it returns
  non-zero: log line 0x1b9 (level 4), `movs r0,#8`, return; the send `FUN_0047564E(0, 0, type, sid, buf,
  len)` at `0x00475C02` (V). `FUN_0046F258` (`0x0046F258`–`0x0046F2C6`): two log branches, then
  `bl FUN_0045A568; cmp r0,#2; bne → 0; else 1` — it is "this is the left lens" (V). `FUN_00475C1A` (the
  notify sender) repeats the shape: the disabled check at `0x00475C70`, `bl FUN_0046F258` at `0x00475CBA`,
  then `FUN_0046F1D0` (1 on RIGHT, or on LEFT when the byte at `*0x0046F41C + 0x1f` is 0), then
  `FUN_0047564E(0, 1, …)`. `FUN_0047564E` (decompile): pool `FUN_00474CD2(len + 14)`, the body copied
  behind a 3-byte header (`type, sid, …`), the block queued; on a queue past half full with param 1 == 1 a
  fast-link request (`FUN_00478160`).
- **Who uses which:** the image ack `FUN_004DA4A4` → `FUN_00475B14(1, 0xE0, buf, len)`; the settings
  responder (the site at `0x0049BB68`) → `FUN_00475B14`; a5d1c31: the wake event, the mic status notify,
  the Damage telemetry reply → `FUN_00475B14`; the wear event → `FUN_00475C1A`. So every one of these leaves
  RIGHT only. a5d1c31's mic comment ("each temple answers for itself") does not hold on 2.2.6.10.
- **The corpus and the image agree here:** the sha256 in each corpus function header equals the sha256 of
  the bytes at that address in `fws/2.2.6.10/ota_s200_firmware_ota.bin` for `FUN_00475B14` (262 B),
  `FUN_00475C1A` (324 B) and `FUN_0046F258` (110 B) — a check worth running for any function a patch leans
  on (`python3 research/fwread.py sha 475b14 …` does it, added the same evening: five functions checked SAME).
- **`FUN_004CA564`** (`0x004CA564`–`0x004CA5BC`): `push {r1,r2,r3,r4,r5,lr}`; `r5 = 0x20074530`; if the
  record is 0: two log branches, return −1; else `ldr r4,[sp,#0x1c]` → `[sp,#4]`, `ldr r4,[sp,#0x18]` →
  `[sp]` (the caller's two stack arguments re-staged for the callee), `ldr r4,[r5]; ldr r4,[r4,#0x28]; blx
  r4` (V). The type-3 caller (`0x00473CD4`–`0x00473CE8`) loads the six words from its queue message and,
  after the call, `b 0x00473E28` → `bl FUN_0046D826` (the end-of-message call) → back to the wait; the
  return is unused (V). Type 6 (`0x00473D70`–`0x00473D80`) makes the same call; Damage presents are type 3
  (`FUN_00474066` stores 3 at `[sp,#8]`), so the fork wraps the type-3 call only.
- **A stock crc32:** none found by literal. The tables at `0x006987AC`… (four copies of the standard
  table's second entry, 0x400 and 0x1000 apart — a slicing-by-4 layout or two libraries) and a CRC-32C
  table at `0x006983AC` are data no corpus function owns; `FUN_0048ED00` writes a "TPF1" header with the
  `0xEDB88320` literal as a field and `FUN_0048ECAE(buf, 0x1c)` as its checksum — not a general crc32.
  R0.5 stays open on this; the fork carries its own 16-entry table (64 B of rodata).

## Read 2026-09-15: R0.1 — the input path, and how each lens learns of the other's input

Context for the reader: a personal device, the published patch method, display-rendering work. These
are reads of the stock 2.2.6.10 image (run address = file offset + 0x437FE0) for `FORK.md` Phase 4
(local input) and the atlas option 2 of `HANDOFF.md` §51.4 (a LEFT→RIGHT report). Every function
below hashes SAME against the corpus header (`fwread.py sha`) unless marked "missed by the corpus".

**The display thread's input handler, `FUN_00442D86` = `inputEventDataHandler` in
`framework\sync\display_thread.c` (its log tag and path are in its pool, `0x00443758`/`0x0044375C`;
1,780 B, SAME).** Called from the display thread's message loop `FUN_004437E0` at `0x00443FB0` for a
queue message whose first byte is **7** (`ldrb r0,[r7]; cmp r0,#7` at `0x00443FA4`), with r0 = the
record at RAM `0x2034DC30` (the loop copies the message body there) and r1 = the message's length word.
The record: `u16 devType @0` (the input source: 0/1 = left/right temple touchpad, 4 = ring —
`gesture_fwd.c`'s `EVT_SRC`), `u32 eventId @2`, `u32 value @6` (read as two `i16` x/y for ids 4, 5
and 0xE). It looks up the foreground UI context (`FUN_0045F8E6(*0x200744D0)`; null → "current active
page is null, exit", return −1) and posts one UI event through `FUN_0045F8FC(ctx, code, &data)` per
event id (the compare chain `0x00442E1A`–`0x00442E40` read at instruction level; the rest from the
decompile of the same bytes):

| eventId | UI event code posted | what the log line calls it |
|---|---|---|
| 0 | 10 (data = the u16 at @0) | (no line) — `input 0 1 0` in the shell help is "inject double click", so 0 is the single click and 1 the double |
| 1 | 0x48 | (no line) — double click |
| 2 | 0x3F (data = value) | (no line) |
| 3 | — | the long press: gates `FUN_00442D64()` ("long press function is disabled!" when 1: a press is posted as code 8 instead) and `FUN_0045BBF4()` (a BLE-role flag: "terminal mode … send long press event to terminal global id" → code 8 when the page id is 0x30); otherwise, with the ctx byte `+0xB == 0`: page 0xE0 (EvenHub) → `FUN_0046AE9C(1, 0xE0)` (the stock force-quit dialog — `gesture_fwd.c`'s site 1), any other page → **on RIGHT only** (`FUN_0045A568() == 1`) `FUN_00464B2E(3, 0, 0, 0)`, a message "app 3, kind 1" to the peer; with `+0xB == 1` the page's own long-press handling (`FUN_0045FAA8`, `FUN_00460374`) |
| 4 | 0x44 (x, y) | "x_offset = %d, y_offset = %d" |
| 5 | 0x45 (x, y) | "x_offset = %d, y_offset = %d" |
| 6 | 0x4B (only when `FUN_00442D64() == 1`) | "onboarding is running, send imu lookup event to onboarding" |
| 7 | 0x49 | (no line) |
| 8 | 0x40 (value) | (no line) |
| 9 | 0x41 (value) | "IMU_COMPASS_DIRECTION, eventValue = %d" |
| 0xA | nothing | "JBD_CHANGE_BRIGHTNESS no use now" |
| 0xB | `FUN_0046C622(value); FUN_0046C984()` | "APP_CHANGE_Y_COORDINATE, eventValue = %d" |
| 0xC | `FUN_0046C600(value); FUN_0046C9AA()` | "APP_CHANGE_X_COORDINATE, eventValue = %d" |
| 0xD | nothing (falls through) | — (the input manager uses 0xD as "press" below) |
| 0xE | 0x4A (x, y) | "x_offset = %d, y_offset = %d" — the release (`gesture_fwd.c`'s site 2) |
| 0xF | 0x4F (value) | "SYSTEM_EXIT_NOTIFY, eventValue = %d" |
| 0x10 | 0x50 (value) | "IMU_COMPASS_CAL_STATE, eventValue = %d" |
| > 0x10 | nothing | "unknow input eventID = %d" |

So the scroll of a temple slide or a ring swipe is NOT one of these ids by name: the ids 2, 7, 8 (and
0/1 for the clicks) are the touch-side gestures; which of 2/7/8 the slider's SLIDE_L/SLIDE_R and the
ring's swipes become is decided by the touch processor and the ring service before the input manager
(below), and is **U** until those objects are read (the gesture processor `[0x00502D56,0x00503298)`
names its events PRESS/RELEASE/SINGLE/DOUBLE/LONG/SLIDE_L/SLIDE_R/ERROR in the table at `0x00503250`).

**The EvenHub page turns UI events into the messages the phone sees (`[0x004935CC,0x0049729C)`, read
at instruction level; the corpus has no entries for these two).** `0x004949C0(ctx, ?, container)`:
with a container record, `FUN_004DA16A(1, rec+0x1C, rec+0x20, 0, ctx, 0)` — **kind 1, the item CLICK
of a container** (its id word and 16-byte name). `0x00494A78(direction, container)`:
`FUN_004DA16A(2, rec+0x1C, rec+0x20, direction ? 1 : 2, 0, 0)` — **kind 2, a container SCROLL with
direction 1 (top) or 2 (bottom)**. Both are reached through the page's stored callback tables (their
Thumb addresses sit at `0x0049556C`/`0x0049559C` and `0x004963FC`/`0x0049642C`, two side-specific
tables), not by BL. The sender `FUN_004DA16A(kind, id, name, eventType, p5, rawSource)` (536 B, SAME):
kind 0 = a SysEvent (field 4 = 3, byte 8 = the type, byte 9 = the source **only for types 0 and 3**:
raw 0 → 3 GLASSES_L, 1 → 1 GLASSES_R, 4 → 2 RING — the rule `EvenHubMsg.kt` already states); kind 2 =
the list event (field 4 = 2, the id, the name, byte 0x1C = the direction); kind 1 = the item event
(field 4 = 1, the id, the name, byte 0x60 = eventType, word 0x5C = p5). So under Damage's carrier
layout the per-notch SCROLL is the dummy text container's kind-2 message and the CLICK is its kind-1
message — the container path, not a SysEvent; the SysEvents 4/5 (foreground enter/exit) come from
`FUN_004E0D3A` (its cases 0x42/0x43), and a third site at `0x00496C5C` sends type 6 from RIGHT only
(`FUN_0045A570() == 1`) and then `FUN_00464C36(0xE0, …)` to the peer — an exit path.

**Who injects input into a display thread — the sync framework, on both lenses, and nothing else.**
The display thread's five message posters follow its startup function: `FUN_004441EC` (type 2),
`FUN_004442D0` (3), `FUN_004443CC` (5), `FUN_004444B8` (8) and **`FUN_004445A4(u16 devType, u32
eventId, u32 value)` = type 7 with the 10-byte record above**, each a queue put with a 1,000 ms wait
that stops in `FUN_005FA0A4` and an unbounded loop when the queue is full (the same shape as the
sync sends below). `FUN_004445A4` has exactly two BL callers in the image (`fwread.py calls`):
`0x0045B9D2` in `FUN_0045B850` = **`SlaveInputEventReplyListener`** (the framework's handler for a
peer packet whose command byte is 7: logs "received input event command, input dev type / id / value",
answers through `FUN_0049225A`, then injects) and `0x0045DE5E` inside a function the corpus missed
whose pool names **`_MasterInputEventDataCmd_Listener`** (`0x0045E660`) — the slave's handler for the
master's input packets. So every input event reaches a display thread only through the framework's
listeners, on the master from the slave's packet and on the slave from the master's.

**The input manager `FUN_004C5DBC` (`platform\input\service_input_manager.c`, 907 B, SAME) runs on
the non-LEFT lens only:** `bl FUN_0045A568; cmp r0,#2; bne …; movs r0,#0; b return` at
`0x004C5DC4`–`0x004C5DCE` (instruction level). It copies a 12-byte record `{u16 devType, u32 eventId,
u32 value, u8, u8}`, logs "InputDevType = %d, eventID = %d, diffx = %d, speed = %d" (ids 4/5 carry
diffx/speed), runs `FUN_004C5A58` (the **both-temple long press**: devType 0 and 1 each with id 0xD
within 2,000 ms of each other arm a 1,000 ms timer `FUN_0047697E(…, 1000)`; id 0xE clears the arm's
stamp; "onboarding is running, not process both long press event" gates it) and `FUN_004C5C6E` (the
touch-release duration statistics: id 0xD stamps, 0xE stamps the release; past 5,000 ms held a record
is written through `FUN_0048EB32`), then a **cross-device lockout**: an event from a different
device than the last one within 1,000 ms (`0x3E9`) is dropped ("input event check failed, do not
inject to UI layer"), a release (0xE) clears the device latch; then, unless `FUN_004C5C30()` says a
both-long-press is in progress ("both long press event, reject other event") or `FUN_0046B0EC() == 0`
(a not-ready state), it **sends the event to the peers: `FUN_00465748(devType, eventId, value, 0)`**
("send input event to peers…"); id 0x1010 goes instead as `FUN_00464B2E(0x109, 0, 0, 0)`.

**The sync API's input send `FUN_00465748` (`sync_interface_api.c`, 962 B, SAME; the lens branches
read at instruction level, `0x004658EC`–`0x00465AAA`):** builds a 12-byte packet `{3, 7, u16 devType,
u32 eventId, u32 value}` (byte 1 = 7 = the input command); on RIGHT (`== 1`) it stamps the packet's
tag word 3 and posts to the queue at `*0x00465FC8` (a 2,000 ms wait; a full queue → the stop above); on LEFT
(`== 2`) the tag is 0, the post goes to `*0x00465D54` and the UART worker is woken with flag bit 2
(`FUN_004495E4(*0x00465FA4, 2)` — the "drain the sync-framework send queue" bit of openCFW's
`uart_sync.c` read). The general send `FUN_00464772(app, body, len, tag, kind, 2, 0)` has the same two
branches (`0x0046496A`–`0x00464A5C`): **both lenses can send over the inter-lens link; nothing in the
send path refuses by lens.** The slave→master direction is what the master's
`SlaveInputEventReplyListener` receives.

**What this settles for Phase 4 and the atlas.** (1) A hook in `inputEventDataHandler` (or on
`FUN_004445A4`'s callers) sees every input event on BOTH lenses, already de-bounced and cross-device
locked by RIGHT's input manager, with its source and id — the natural place for a local reaction
(`FORK.md` §6). (2) The both-temple long press is detected on RIGHT before any UI code; the 1,000 ms
timer's callback is the Silent-Mode path (U: not followed). (3) LEFT can send to RIGHT (V); a LEFT →
RIGHT cache report (option 2 of §51.4 item 6) is a new command id in the framework's listener on
RIGHT, which then answers the phone — Phase 4 work, not needed for the bounded skip. (4) The stock
input event ids for the slides/swipes (2, 7, 8 by elimination) and the ring's raw codes are **U** until
the touch processor and the ring service are read; Damage's per-notch scroll is the kind-2 container
message either way (V). (5) Which lens is the framework's "master": the input manager and every reply
run on lens 1 (RIGHT); the UART worker runs `FUN_00471528` only on lens 2 (`0x00541928`); the
TinyFrame role byte itself was not read — **I** that RIGHT is the master.

## Read 2026-09-15: R0.5 — the stock helpers the later phases call

- **RTC.** openCFW closes `driver\rtc\drv_rtc.c` at `[0x0047EE78,0x0047EEFA)` = `DRV_RtcSetTime`
  (130 B; the only caller `0x0044A20C`); it maps application fields into the 40-byte Ambiq structure
  and calls the HAL setter `am_hal_rtc_time_set` at `0x004D3ADC` (AmbiqSuite 5.1.0, exact) with
  `am_util_time_computeDayofWeek` at `0x004D3CF8`. The **getter** openCFW ships (`rtc_time_get.c`) reads
  the Apollo510 RTC registers directly (`0x40004820`/`0x40004824` counter low/up, control `0x40004800`);
  its stock counterpart is named in `tools/manifests/g2-drv-rtc-function-map.tsv` and was not decoded
  here — for the silent clock (Phase 5) the HAL's `am_hal_rtc_time_get` is the call to find
  (`fwread.py calls` on the setter's HAL sibling), **U** for now. The stock time-of-day the dashboard
  shows comes through `service_kvdb_time.c` and the pb settings; the phone sets it.
- **Fuel gauge (`driver\chg\drv_bq27427.c`, `[0x0053AFC0,0x0053C2A4)`).** The live record at RAM
  **`0x20073B18`** (`+4` state of charge %, `+8` mV, `+0xC` signed mA, `+0x10` centi-°C — openCFW's
  table, grade C for the offsets) is referenced by seven literal pools in the image (`fwread.py refs`:
  `0x004AD97C`, `0x004C6CA0`, `0x00512608`, `0x0053AF6C`, `0x0053C284`, `0x005709B0`, `0x00576A30`), so a
  read of it from the fork is the kind of RAM read a5d1c31 already does — the writer is the gauge
  object's periodic wrapper (`0x0053C0F4` → `FUN_0053C024`, called from `0x004C688A`). The stock
  battery percentage the phone sees (field 4.12) is the same record. Verify the `+4` offset at
  instruction level before a patch reads it (one `dis` of `FUN_0053C024`'s store).
- **KV store (`platform\service\flashDB\kv\service_kvdb.c`, FlashDB 2.1.1).** `FUN_0054116E(u8 db,
  const char *key, void *buf, u16 len)` → `FUN_0054454A(ctx + db·0x8AC, key, &blob)`
  (`fdb_kv_get_blob`; returns the size read; logs "…" at line 0xF1 when the blob's saved length is 0)
  and `FUN_005411F2(db, key, buf, len)` → `FUN_0054503A` (`fdb_kv_set_blob`, returns the negated
  FlashDB status). Both bracket the call with `FUN_004490CC()`, which is **not a lock** — it is the
  tick reader (`FUN_0044900E` then `FUN_00454EFE`; the input manager uses its return as "now") —
  correcting `HANDOFF.md` §50's "the KV get takes a lock"; FlashDB's own lock/unlock callbacks
  (installed with control commands 2 and 3 at init) serialize the store. Callers of the get: the KV
  service itself (`FUN_004D956C/959C/96D8`) and `service_kvdb_*` readers (`FUN_005105F0`,
  `FUN_00510620`, `FUN_0051079C`), all on the KV/settings-side paths — no caller on the BLE receive task,
  so the "cached read from the settings context" of F1.2 still has no precedent (**U**); a once-per-lease
  read is cheap (one FlashDB lookup) and the lock it waits on is the store's.
- **Dashboard launch and the module registry (openCFW's `ui_startup_app.c`, `ui_module_registry.c`,
  grade C: recovered ABI, not read here).** The startup policy at stock `0x00442BA8(system_status,
  input_type)`: input types 0/1/4 → operation 0/1/2 → the policy `0x0046651F` → application type 0 →
  **app id 0**, type 1 → **app id 3**; every other type logs and maps to 0. App 3 is the id the
  display thread's long-press branch sends to the peer and compares the page against, and the
  "SID_UI_FOREGROUND_MEUN_ID, start menu" line of the schedule manager is its sibling: **app 3 = the
  dashboard/home (I)**. The registry: `count` at `0x200744D4`, 16-byte entries from `0x20066230`
  `{service id, data handler, ui handler, state pointer}`, the state's byte `+0xB` is the mode the
  input handler tests, the display manager at `0x200744D0` (gesture_fwd's `UI_CTX`; its `+0x1C`/`+0x20`
  are the two UI contexts), the stage word at `0x200744DC`. An app start is the display thread's type-2
  message → `FUN_0044228A(app, 2, …)` walks the registry (`*0x00442CD4` entries of 16 B at
  `*0x00442CD8`) and calls the entry's handler `+8`, then `FUN_0045F3C8` activates it. Phase 7's offline
  home is a registry entry of its own or a redirect of app 3's — a design choice for then.

## Read 2026-09-15: M0.3 — packets per connection event, from the three APK captures

`research/perevent.py` (offline, stdlib): per arm, the controller's Number-of-Completed-Packets
reports (HCI event 0x13) against the outbound ACL records. **Measured (grade M):**

| capture | LEFT: completed per report while more were queued | gap between busy reports | RIGHT (control) |
|---|---|---|---|
| 17:05–17:12 (7 min) | 2 × 1,423, 1 × 63 | median 60.1 ms; 1,133 of 1,956 gaps are 4 intervals of 15 ms | 1 per report, gaps 4–13 intervals |
| 17:12–17:31 (quieter) | 2 × 348, 1 × 11 | median 25.0 ms; the mode is 2 intervals | 1 per report |
| 17:31–17:56 (24 min) | 2 × 1,458, 1 × 64 | median 60.1 ms; 1,159 of 2,184 gaps are 4 intervals | 1 per report |

So during a flush the phone's controller gets **two full 247-byte packets across per served
connection event, and LEFT is served every 60 ms (four 15 ms intervals) in the two busy sessions,
every ~25–30 ms in the quiet one** — 2 × 247 B / 60 ms = 8.2 KB/s, the daily-path figure the journal
measures (`REMINDER.md`: ~120 ms per KB). The host had more queued than the link took in 95 % of the
busy reports: the link paces, not the phone. The packets are full (779,833 B in 3,523 packets on LEFT
= 221 B average against the 247-byte DLE), so **a bigger ATT write cannot help; only more served events,
or more packets per served event, can** (`FORK.md` F1.8 rewritten). Why LEFT is served every fourth
event in two sessions and every second in the third is **U** — the candidates: the lens's own
controller sharing its radio time between the phone link and the ring link (which lens owns the ring
follows the dominant hand: `[ble.master][RingSwitch] dominant_hand=%u owner=%d`), the phone's
controller interleaving two 15 ms links plus the classic audio link (its packet counts per 30 s do not
follow the cadence, `perevent.py`'s table), the peripheral's own latency use, an Ambiq LL cap on
packets per event, the phone's LE scheduling window — none tested; a capture with the ring asleep or
unpaired would separate the first from the rest.
