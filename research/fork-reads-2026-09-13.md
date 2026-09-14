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
