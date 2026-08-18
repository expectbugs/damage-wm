# Capability inventory — everything the G2 can actually do

**Purpose.** This is the input to the feature-creep phase. A scope explosion run off scattered
notes fails in both directions: it invents capabilities that do not exist, and it misses ones that
do. Everything below is one place, with **how you reach it on the wire** and **how well we know it**
(grades from [`CLAIMS.md`](CLAIMS.md): **V**endor-authoritative · **M**easured · **C**orroborated ·
**I**nferred · **S**ingle-source ⚠ · **U**nknown).

Assumes the CFW (`g2flash` / SybilSight 2.2.6.11) and the direct-framebuffer path. Written
2026-08-17 at the close of the research phase.

**Explode against the ✅ and 🟡 rows. Treat ⚠ and ❓ as ideas that need a probe first.**

> 🎨 **Status update 2026-08-18: the SHELL layer is now designed — see [`DESIGN.md`](DESIGN.md).**
> The window manager's own surfaces (input grammar, geometry, Main, switcher, notifications, status
> bar, content area, typography) are specified and measured. **What has NOT been exploded is the
> APP layer** — what the windows actually do. Explode against that, using this inventory for what
> the hardware allows and `DESIGN.md` for what the shell already provides. In particular
> `DESIGN.md` §0 (deliberately excluded) and §4.6 (the three content modes and what a window
> declares) bound the space: an app idea that needs the piezo, per-app gestures, or off-panel
> scratch is already ruled out.

---

## 1. Display

| capability | how | grade |
|---|---|---|
| **640×480 canvas, 16 gray levels, 4bpp** | CFW modes 3/6/8/9 on the full physical framebuffer | ✅ C |
| Sensible default **640×288** | full height only when an app earns it (FoV) | ✅ C |
| **Full-screen keyframe** | mode 6 · `zlib(rle(px))` · ~5.7 KB, ~700 ms | ✅ M |
| **Dirty-rect delta** | mode 3 · quantised left/w ×4, top/h ×2 · cursor move ~294 B, ~203 ms | ✅ V+M |
| **Atomic multi-op frame** | mode 8 batch, ~6 rects · **the project's core thesis** | ✅ V+C |
| **On-device rect copy (0 pixels on the wire)** | mode 9 · full uint16 coords, may overlap | ✅ V |
| **Unbounded scroll** | mode 8 { mode 9 shift + mode 3 fill } | ✅ V |
| **Per-lens stereo shift** | high bit of mode byte · same pixels, different box per eye | ✅ V |
| Anti-aliased text at 16 levels | host-side rasterisation; cheaper than we modelled | ✅ M |
| **Texture cache — multiple screens of on-device memory** | in development by the CFW author, ~1 week out | 🟡 S |
| Diagnostic overlay (`f_reorder`/`f_skip`/`f_dup`/`f_snap_of`) | mode 7 sub 2 | ✅ V |
| Off-panel scratch / save-under | ❌ **does not exist** — the full 640×480 is visible | ❌ |

**Constraints that shape every design:** ~7–13 KB/s and ~176 ms ack ⇒ **cost is ack-dominated, so
batch aggressively and scroll coarsely**; deflate **level 6** (level 9 costs 18–109 ms/frame, level 1
pushes payloads past the 3800 B fragment boundary and adds a ~350 ms round trip); **no dithering**;
FB lease must be renewed every 45 s or stock LVGL repaints over you.

## 2. Input

| capability | how | grade |
|---|---|---|
| Tap / double-tap / long-press / long-press-release | EvenHub `Sys_ItemEvent`; CFW adds SysEvent 9/10 for ring long-press | ✅ V |
| **Per-notch scroll** | every notch delivered (the 1-space capture container can't scroll internally) | 🟡 C |
| **Per-source discrimination** (L temple / R temple / ring) | firmware source byte `0x2034dc30`; protobuf `EventSourceType` | ✅ V |
| **Direct ring gestures, bypassing the glasses** | ring's own BLE link · `0x04` SWIPE_UP / `0x05` SWIPE_DOWN + **32-bit tick** ⇒ velocity possible | 🟡 C |
| Both-temple long-press → stock Silent Mode | unpatched by anyone — **keep as the hardware escape hatch** | ✅ M |
| Wake-word "Hey Even" as an app event | sid 0x07; CFW can suppress the stock handler | 🟡 S |
| Typed-text input path | G2CC had `onTypedText`; unported | ❓ U |

## 3. Sensors & feedback

| capability | how | grade |
|---|---|---|
| **IMU x/y/z** (head tracking!) | EvenHub `Cmd 19/20` · `IMU_CtrlCmd{IMUReportEn, reportFrq}` · Faceclaw ships `buildImuControl()` | 🟡 V |
| **Wear / unwear detection** | CFW `wearnotify` → sid 0x10 `GLS_WEAR_STATUS`; op 7 queries current state | ✅ V |
| **Magnetometer compass** | CFW mode 10 · heading via stock sid-0x08 notifier | ✅ V |
| **Piezo buzzer** — presets, notes, **arbitrary 1–20000 Hz tones, and 48-step sequences** | mode 5 kinds 0–4 · the only audio output on a device with no speaker | ✅ V |
| **Ring biometrics**: hr, spo2, hrv, temp, steps, kcal, battery — all with timestamps | `RingDataPackage.RingRawData` on sid 0x91 relay | 🟡 V |
| Ambient light / auto-brightness, head-up angle, anti-shake | sid 0x80 `DeviceInfo` · sid 0x09 settings | 🟡 V |
| Glasses battery / charging / case SoC / lid / in-case | sid 0x09 field 12–13; sid 0x81 `GlassesCaseInfo` | ✅ M |
| Glasses microphone | ⚠ disconnects >25 s; audio is on service `6450` | ⚠ S |

## 4. System / plumbing

| capability | how | grade |
|---|---|---|
| Settings: brightness, silent mode, head-up, wear, lens x/y, dominant hand, units | sid 0x09 `G2SettingPackage` | ✅ V |
| **Gesture remapping** (`APP_Send_Gesture_Control{screenOn, operationType, apptype}`) | sid 0x09 — possibly relevant to the gloves problem *without* CFW | ❓ V |
| **On-device logger over BLE** — `logStr` streamed to host, file list/delete | sid 0x0F `logger.proto` · **would make CFW decompress failures visible** | 🟡 V |
| **File export from device** (`EXPORT_START/DATA/RESULT_CHECK`) | sid 198/199 · **never probed. If it works, "no firmware read-back" stops being true** | ❓ V |
| Foreground/background app awareness | sid 0x0D `sync_info{backgroundAppID, foregroundAppID}` | 🟡 V |
| Connection-parameter control (`MTU`, `connInterval`, `SLOW\|FAST`) | sid 0x80 cmd 7 — ⚠ **same sid as `UNPAIR`(9) and `RESTORE_FACTORY`(13)** | ❓ V |
| Command-lens role (`BOTH` / `RIGHT` / `LEFT`) | sid 0x80 cmd 5 `PIPE_ROLE_CHANGE` — settable, not fixed | 🟡 M |
| Notification control incl. **display time** | sid 0x04 `NotificationControl{dispTime}` | ✅ M |
| Dashboard auto-close timeout, system language | sid 0x20 `module_configure` | 🟡 V |

## 5. What is NOT available

- ❌ **Off-panel scratch space** — full 640×480 is visible. Overlays repaint with mode 3.
- ❌ **Image retention across a layout change** (stock path) — hardware-confirmed 2026-08-17.
- ❌ **Per-pixel depth** — stereo granularity is the rect; each element is a flat card.
- ❌ **True per-eye content** — one payload, two positions. Different content = binocular rivalry.
- ❌ **Colour** — 16 levels of green, and that is the whole gamut.
- ❌ **2M PHY** — rejected by the glasses. 1M only, and we only reach ~10% of it.
- ❌ **Firmware read-back** — *probably*; but see the file-export row above.

---

## Where the leverage is

**Highest ceiling, already ours:** mode-8 batched damage with real fonts. Everything G2CC avoided
because images cost seconds — thumbnails, embedded images, game frames, an ebook reader with real
typography — is now bounded by ~176 ms + compressed damage bytes, once per frame.

**Highest ceiling, not yet ours:** the **texture cache**. It is what makes anti-aliased text cheap
at scale, and it is the difference between "AA text is affordable" and "AA text is free." Design so
it can be adopted without rework.

**Most under-explored:** the **logger** (turns silent failure into visible failure — directly serves
the project's own rule) and the **file-export service** (attacks the one genuinely irreversible
thing about this project). Both are cheap probes and neither has been touched.

**Most likely to be wrong:** per-notch scroll and the arm split, both graded from someone else's
code rather than our wire. §12's fixed-cursor design falls if the first one does.
