# Claims register — what we actually know, and how well

**Purpose.** `overview.md` states hundreds of facts. Some are measured from our own captures, some
come from Even's own schemas, some are one person's prose that hardened into "fact" through
repetition. **Four times in two days a claim in the "fact" tier turned out to be wrong**, each time
because documentation disagreed with working code. This file grades every load-bearing claim so the
next phase knows which ground is solid.

Created 2026-08-17, at the end of the research phase, as the "passes for consistency and adherence
to the research/documentation" step.

## Grades

| | meaning | trust |
|---|---|---|
| **V** | **Vendor-authoritative** — Even's own protobuf schemas, or firmware source we can read | highest |
| **M** | **Measured** — our own BTSnoop captures or a reproducible local experiment | highest |
| **C** | **Corroborated** — two or more independent sources agreeing (esp. code, not prose) | high |
| **I** | **Inferred** — our reasoning from V/M/C facts. Sound but not observed | medium |
| **S** | **Single-source** — one author's prose, no code or capture behind it | ⚠ low |
| **U** | **Unverified / unknown** — open question, or blocked on hardware | none |

**Rule that produced every correction so far:** *prose describes, code runs.* Where an exercised
implementation exists, read it. Documentation is a summary written by someone who already knew what
they meant — including ours.

---

## Transport / link layer

| claim | grade | basis |
|---|---|---|
| AA envelope `AA·type·seq·len·pktTot·pktSer·sid·flag` + payload + CRC | **C** | our captures **and** g2-kit `envelope.ts` (its `.md` disagrees — wrong) |
| CRC-16/CCITT-FALSE over payload only, LE, final fragment only | **M** | computed: `080c104f7200` → `0xCC79`, wire `79 cc` |
| ~232 B payload per AA fragment (240 B on wire) | **C** | measured + g2-kit's `chunkSize ?? 232` |
| MTU 247 negotiated (glasses answer 517) | **M** | capture |
| MTU 247 is the *app's choice*, not a hardware ceiling | **I** | Faceclaw requests 512; AA `len` is 1 byte so per-frame gain is capped |
| 1M PHY only, 2M rejected | **M** | capture (no 2M PHY update events) |
| **Throughput 7–13 KB/s end-to-end** | **M** | `captures/imagestatus.log`, multi-fragment bursts |
| ~~16.6 KB/s~~ | ❌ | **withdrawn** — was the fast mode of a trimodal distribution |
| Fragment gaps are trimodal: 0–1 / 12–17 / 56–61 ms | **M** | capture, n=256 |
| Controller ACL pool = 12 packets; peak outstanding = 4 | **M** | capture; refutes buffer-credit exhaustion |
| Stalls are host-side (half resume at zero outstanding) | **M** | capture |
| **No `LE_Conn_Update` is ever issued for handle 65 (R lens)** | **M** | capture; 64 and 66 both get explicit ones |
| Cause of the ~10× shortfall | **U** | HCI can't separate stack / app cadence / BT-WiFi coexistence |
| Image ack latency median 176 ms | **M** | capture, stock 2.2.2 only |
| msgId (`MagicRandom`, pb field 2) is effectively 1 byte | **C** | our hardware finding + g2-kit, independently |
| ~1000 B wall applies to **layout frames only** | **M** | largest layout frame observed = 401 B; image chunks are 4096 B / 18 fragments |

## Protocol / message format

| claim | grade | basis |
|---|---|---|
| All EvenHub wrapper field numbers (`Cmd=1`, `MagicRandom=2`, `ImgRawMsg=5`, …) | **V** | Even's `EvenHub.proto`, decoded from `g2-kit/ble/gen` |
| Container property field numbers (text/list/image) | **V** | same |
| `OsEventTypeList` 0–8, `EventSourceType` 1=R/2=ring/3=L | **V** | same |
| `ImgRawMsg.f5 = CompressMode` (uint32, **no enum exists**) | **V** | same — the schema defines the field, not the values |
| CompressMode absent on every official-app image push | **M** | capture: 13/13 pushes, field absent |
| CFW path always sends `CompressMode = 0` | **C** | CFW author by email **and** Faceclaw `BleProtocol.java:175` |
| `CompressMode 2 = LZ4` | **C** | g2flash's exercised block encoder + Even SDK 0.0.12 regression |
| ~~`CompressMode 1 = RLE`~~ | **S** ⚠ | two prose comments by one author, cited file absent from repo, **never sent by anyone** — treat as unknown |
| SID map (0x03=MENU, 0x04=NOTIFICATION, 0x07=EVEN_AI, 0x09=SETTING, 0x0D=SYNC_INFO, 0x0F=LOGGER, 0x10=ONBOARDING, 0x20=MODULE_CONFIGURE, 0x80=DEV_CONFIG, 0x81=GLASSES_CASE, 0x91=RING_RELAY, 0xE0=EVENHUB) | **V** | `service_id_def.proto` |
| Init sequence decoded frame-by-frame (auth → pipe-role → time-sync → units → menu → sync → quicklist → EvenAI → onboarding → ring → case → language → notification) | **M+V** | capture decoded against vendor schemas |
| `80-20 cmd 5 = PIPE_ROLE_CHANGE{asCmdRole = RIGHT}` | **M+V** | capture — this is *how* "R is the command lens" is established, and it is settable |
| `81-00 {f1=78}` is the **case battery** (`caseInfo.soc`) | **M+V** | capture; our doc called it a "Display Trigger" response |
| `04-20` = `NotificationControl{notifEnable, autoDispEnable, dispTime=7, avoidDisturbEnable}` | **M+V** | capture |
| TIME_SYNC `timezone` = UTC offset in quarter-hours (−20 = −5 h) | **M+V** | capture: varint decodes to −20 |
| `e0-02` is an abort/error frame | **C** | our capture (empty frame 0.56 s **before** two image failures) + g2-kit's independent account |
| Real on-wire failures exist: `IMAGE_RAW_DATA_FAILED`, `TEXT_DATA_FAILED`, `SHUTDOWN_FAILED` | **M** | capture; retry with the **same** MapSessionId failed identically |
| MapSessionId is constant per image, varies per push, small ints (25–237) | **M** | capture, n=13 |
| MapSessionId has recovery semantics (bump ≥2 after abort) | **S→C** | g2-kit prose, but our capture's same-session retry failure is consistent with it |
| Image container max 288×144 | **M** | capture: `imgmax` container is exactly 288×144 and painted |
| **Images are NOT retained across an EvenHub layout change** | **M** | Adam's own hardware 2026-08-17: on a menu change the image vanishes until redrawn. Corroborated by g2-kit `containers.md` (Cmd=7 invalidates tile buffers). **This retires commit 709d18c's open "retention probe never run"** — the 4-tile re-push was mandatory, not conservative |
| Container name cap | **U** | longest the official app ever uses is 8 chars. Our "≤16" is unsupported by our own data; g2-kit's "14" is single-source |
| `30-XX` service | **U** | **not in Even's own SID enum.** Still unidentified |

## CFW / display

| claim | grade | basis |
|---|---|---|
| Mode table 3/5/6/7/8/9/10, `zlib(rle(px))` on 3+6 | **V** | `zlib_glue.c` source |
| Mode-3 quantization: left/width ×4, top/height ×2, 1 byte each | **V+C** | source **and** Faceclaw's aligner does exactly this |
| **Panel is 640×480 and all of it is visible** | **C** | CFW author by email + `PANEL_W/H` in source + Faceclaw's `G2_LENS_WIDTH/HEIGHT = 640/480` |
| Width headroom (64 cols) is the stereo-shift budget | **S** | author's prose; plausible and matches §7, but not independently confirmed |
| 640×288 is the sensible default | **C** | author's prose **and** Faceclaw's `MIN_WINDOW_HEIGHT = 288` in code |
| Direct-framebuffer lease required (sid 0x09 field 101 op 5/6, both arms, 45 s renew) | **V+C** | `settings_ext.c` + Faceclaw's Java implementing it |
| Carrier layout needs a dummy full-screen text container | **C** | `buildCreateMixedImagePage` + the lease comment naming it |
| ~6 rects per mode-8 batch (fid ring is 16 deep) | **C** | Faceclaw's `MULTI_RECT_MAX_RECTS = 6` + `CFW_FID_RING` in source |
| `fid` in `[1, 0xFFFE]`, +1 per delta | **C** | Faceclaw's `nextImageFrameId` + the 0xFFFF sentinel in source |
| Deflate **level 6** is the right setting | **M** (his) | Faceclaw: level 9 costs 18–109 ms/frame; level 1 pushes payloads past the 3800 B fragment boundary, adding a ~350 ms ack round trip |
| Splitting rects costs ~15 fixed bytes **plus** lost cross-rect zlib dictionary sharing | **S** (his code comment) | worth re-measuring ourselves |
| Keepalive self-sustains under image traffic | **V** | `FW_KEEPALIVE_RESET()` on every image message |
| Stale-compositing-base hazard (buffer two frames back) | **S** | Faceclaw comment; says fixed by the snapshot FIFO, but the flag comment is stale |
| Bulk pixels → LEFT arm, control → RIGHT | **I** ⚠ | inferred from Faceclaw's code (5 call sites, `sendImagesToLeft=true`); **no capture** |
| CFW ack latency / msgId-255 under CFW / mode-8 limits in practice | **U** | hardware-blocked |

## Input

| claim | grade | basis |
|---|---|---|
| CFW patches long-press (subtype 3) and ring release-long-press (0xe) only | **V** | `gesture_fwd.c` — **scroll handling is byte-for-byte stock** |
| Input source byte `0x2034dc30`: 0/1 = L/R temple, 4 = ring | **V** | `gesture_fwd.c` |
| `EventSourceType` (protobuf) 1=GLASSES_R, 2=RING, 3=GLASSES_L | **V** | vendor schema — ⚠ **different numbering from the firmware source byte above; do not conflate** |
| G2CC saw scroll only at content boundaries, `fullBleed` text mode only | **M** | our own `WINDOW_API.md` §3.4 — the firmware widget owned the scroll |
| **Per-notch scroll is available under the CFW carrier layout** | **C** | the capture container holds one space so it can never scroll internally ⇒ every notch is a boundary; Faceclaw consumes scroll as per-notch deltas (`file-browser.ts:274`, `launcher-app.ts:206`) and drives **pinball flippers** with it |
| Per-notch scroll comes from dropping firmware containers, **not from the CFW** | **I** | `gesture_fwd.c` does not touch scroll; it follows from the layout |
| Ring's own BLE link carries decodable gestures (`0x04` SWIPE_UP / `0x05` SWIPE_DOWN, + 32-bit tick) | **C** | `FaceclawRingEventDecoder.java`, two frame shapes |
| ~~Ring link is "battery/firmware/sensors only; navigation input does NOT come over it"~~ | ❌ | **withdrawn** — `G2_BLE_PROTOCOL.md` §11 is wrong or incomplete; Faceclaw decodes gestures from it |
| Ring notch coalescing / event-rate ceiling | **U** | first-light check |

## Firmware image / flashing

| claim | grade | basis |
|---|---|---|
| Local stock 2.2.6.10 == patch-set pinned base | **M** | `research/verify_cfw.py` |
| g2flash's 25 patches reproduce its pinned output | **M** | same |
| SybilSight's 28 patches reproduce the **archived** `g2-2.2.6.11.bin` byte-for-byte | **M** | same |
| SybilSight CFW = g2flash CFW + 15 bytes (3 ASCII version digits + CRC fixups) | **M** | same, byte-level diff |
| No Thumb-bit defect in either rebuilt blob | **M** | `thumb_branch_audit` on our own rebuild, 14 branches all Thumb |
| CFW image is **enlarged** by 20,127 B, preamble length bumped, ~403 KB headroom | **M** | patch set + measured image sizes |
| 2.2.2 → CFW cross-version flash is accepted | **I** | Faceclaw classifies ≤2.2.6.10 as `flashable-stock`; **nobody has documented doing it from 2.2.2** |
| Leaving 2.2.2 is irreversible | **M** | 2.2.2 absent from the 19-image archive (verified on disk: 2.2.0.24 → 2.2.4.34) |
| No firmware read-back path | **S→U** | asserted; but `UX_EVEN_FILE_SERVICE_CMD_EXPORT_ID=198` and OTA-export SIDs exist in the vendor enum, unexplored |

## Shell design — read from source or measured (added 2026-08-18)

Everything here backs a decision in [`DESIGN.md`](DESIGN.md).

| claim | grade | basis |
|---|---|---|
| **Only mode-3 deltas consume a `fid`** — mode 9 rect-copies are free against the ring | **V** | `zlib_glue.c`: the sole `cfw_diag()` call sites are the mode-6 keyframe and the mode-3 delta |
| **Only an EXACT hit in the 16-deep ring is skipped.** A stale fid that has aged out is flagged and then **APPLIED** | **V** | `cfw_diag()` body — the ring is a short-window filter, not a safety net |
| `f_skip` fires on any forward gap > 1; `f_reorder` on any backward step | **V** | same |
| The fid wrap `0xFFFE → 1` computes `d = 3` in uint16 ⇒ trips **`f_skip`**, once per 65 k rects | **V** | same, arithmetic checked |
| **Mode 8 accepts only shadow ops 3/6/9** — the buzzer cannot ride in a batch | **V** | `zlib_glue.c` mode-8 branch, explicit comment |
| Mode-8 size cap = `118 + 320×480` = **153,718 B** | **V** | `bmp_max` in source; matches `tools/geometry.py` |
| **No `inflateSetDictionary`** ⇒ every rect in a batch gets its own zlib stream, so splitting always loses cross-rect sharing | **V** | only `inflateInit2(strm, 15, …)` is imported |
| mode-3 stereo: boxes size-checked equal, `box_off = (FW_SIDE()==2) ? 1 : 5`, +4 B | **V** | source |
| mode-9 stereo: 4 rects / 32 B, right lens uses the 2nd set | **V** | source |
| **Ink coverage of every shell surface** (Main 8.7 % active / 4.6 % resting, silent 0.2 %, …) | **M** | `design/render_shots.py`, real renders at 1×, quantized to 4bpp |
| **Compression at 640×480 is 0.008–0.056×** of raw | **M** | same. ⚠ The 0.03–0.05× band used for modelling is sound but sits at the **pessimistic** end — do not plan against its low end |
| Per-face byte cost spans **0.98×–1.17×** at matched x-height | **M** | 10 faces, x-height normalised (comparing at nominal size is not a fair test) |
| `▸`/`▶` absent from **13 of 16** candidate faces, `⚙` from **15 of 16** | **M** | real font `cmap`s via fontTools. ⚠ `PIL.getmask().getbbox()` is **not** a coverage test — a tofu box has a bbox too |
| ~~"Condensed faces compress worse"~~ | ❌ | **withdrawn** — generalised from one measurement. DejaVu Condensed is 1.13× but Helvetica Narrow is 0.98×. The *face* is the variable, not the width class |
| **Rect budget = 5** = `floor(CFW_FID_RING / window)` at a 3-deep pipeline | **I** ⚠ | derived from the two facts above; **never observed on hardware** |
| A vertical icon rail costs **+7.4 %** on every frame vs a horizontal strip | **M** | rendered both; a 40 px vertical strip adds run boundaries to all 416 content rows against the top bar's 32 |
| A 640×288 band is **0.63×** the bytes of 640×480, and shows 4 dashboard rows against 11 | **M** | rendered both, with and without simulated occlusion |
| Usable panel extent under real optical occlusion | **U** | fit-dependent and personal; unknowable before first light. `DESIGN.md` §2.2b makes it a calibrated setting rather than a guess |

## Deployment topology (added 2026-08-20 — `DESIGN.md` §10)

| claim | grade | basis |
|---|---|---|
| A network gap > 90 s costs the screen if the lease is renewed across it | **I** | `settings_ext.c` lease semantics + the 45 s/90 s constants; fail-open is in the source, the *consequence* is inference |
| Transport must be stateful and liveness-critical in every configuration | **I** | follows from the above |
| The shell must run on Android **and** desktop ⇒ not Python | **I** | follows from the "app alone" configuration being required |
| G2CC's `ble/` layer survives a CFW switch largely intact | **I** | it is transport-layer; the CFW changes modes, not the AA envelope. Not yet ported |
| `Rasterizer.kt` proves arbitrary phone-side Canvas → 4bpp works | **M** | shipped and in daily use in G2CC |
| A vertical rail costs ~+7.4 % per frame vs a horizontal strip | **M** | rendered both at 1× |
| **Dual-band removes BT/WiFi coexistence contention** | **I** | 5 GHz WiFi + 2.4 GHz BLE do not share a radio. Sound in principle; **the effect on G2 throughput is UNMEASURED** |
| Coexistence is a cause of the ~10× shortfall | **U** | one of three surviving candidates (§5.1); the hat is the experiment that would isolate it |
| Hat bridge power budget (1260 mAh, WiFi 6 + BLE, one workday) | **U** | never measured |
| macOS cannot set BLE connection parameters | **C** | CoreBluetooth's documented model; not tested against the G2 |
| Bridge configurations cannot access phone APIs (SMS, notifications, media) | **V** | those are Android APIs; a Pi or laptop has no path to them |

---

## The five things most worth distrusting

1. **`CompressMode 1 = RLE`** — single-source, uncited, never exercised. Moot in practice (we always send 0) but do not build on it.
2. **The arm split (bulk → LEFT)** — inferred from someone else's code, not observed. It decides whether a flush is one message or two. **Verify with a two-arm capture at first light.**
3. **Container name cap** — 14 vs 16, and our own data supports neither.
4. **Width headroom = depth budget** — author's prose only; it constrains layout if true.
4b. **Per-notch scroll** — graded C from Faceclaw's code, never seen on our wire. **§12's
   "fixed cursor + panning list" decision depends on it entirely** and falls with it.
5. **"No firmware read-back path"** — the vendor's own service enum contains a file-export service we have never probed. If it works, the one irreversible thing about this project stops being irreversible.
6. 🆕 **The rect budget of 5** (`DESIGN.md` §8.2) — graded **I**, derived from reading `cfw_diag()`, never observed. It governs how much damage fits in one flush, and being wrong is *silent*: a retransmitted batch whose fids have aged out gets re-applied instead of skipped. The mitigation that does not depend on the number being right is **never putting the same fid on the wire twice**; verify the budget itself at first light.

## What cannot be resolved before flashing

CFW ack latency · msgId-255 under CFW · real mode-8 batch limits · stale-compositing-base behaviour ·
whether the arm split is right · whether 2.2.2 → CFW actually takes.

**Do not build a plan that quietly assumes any of these are knowable in advance.** The failure mode
is a schedule that stalls at the one-way door.
