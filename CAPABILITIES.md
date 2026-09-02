# Capability inventory — everything the G2 can actually do

**Purpose.** The hardware inventory behind the app wave (it fed the 2026-08 scope explosion;
the explosion and refinery are done — `EXPLOSION.md` — and window conversions now build against
this). Everything below is one place, with **how you reach it on the wire** and **how well we know it**
(grades from [`CLAIMS.md`](CLAIMS.md): **V**endor-authoritative · **M**easured · **C**orroborated ·
**I**nferred · **S**ingle-source ⚠ · **U**nknown).

Assumes the CFW and the direct-framebuffer path — which, since 2026-08-30, is what the glasses
actually run (g2flash `a5d1c31`, reporting `2.2.6.10`). Written 2026-08-17 at the close of the
research phase; grades touched 2026-08-31 where the refinement wave measured things.

**Build against the ✅ and 🟡 rows. Treat ⚠ and ❓ as ideas that need a probe first.**
`DESIGN.md` §0 (deliberately excluded) and §4.6 (the content modes and the window contract)
bound the space; `WINDOWS.md` is the conversion checklist. An idea that needs the piezo,
per-app gestures, or off-panel scratch is already ruled out.

---

## 1. Display

| capability | how | grade |
|---|---|---|
| **640×480 canvas, 16 gray levels, 4bpp** | CFW modes 3/6/8/9 on the full physical framebuffer (full height in daily use; usable extent is fit-dependent) | ✅ M |
| Sensible default **640×288** (Faceclaw's) | Damage ships **480** as the Global default with four top-aligned sizes — Adam's fit loses the bottom (`DESIGN.md` §2.4 rule 4) | ✅ C |
| **Full-screen keyframe** | mode 6 · `zlib(rle(px))` · dense ~10 KB ≈ 200–270 ms on the measured curve | ✅ M |
| **Dirty-rect delta** | mode 3 · quantised left/w ×4, top/h ×2 · small flushes ride the ~60 ms floor | ✅ V+M |
| **Atomic multi-op frame** | mode 8 batch, ~6 rects · **the project's core thesis** | ✅ V+C |
| **On-device rect copy (0 pixels on the wire)** | mode 9 · full uint16 coords, may overlap | ✅ V |
| **Unbounded scroll** | mode 8 { mode 9 shift + mode 3 fill } | ✅ V |
| **Per-lens stereo shift** | high bit of mode byte · same pixels, different box per eye | ✅ V |
| Anti-aliased text at 16 levels | host-side rasterisation; cheaper than we modelled | ✅ M |
| **Texture cache — 64 KiB, lease-scoped** | ✅ LANDED (CFW `a5d1c31`, modes 11–15): wire + byte-exact model built, compositor adoption deliberately pending the on-glass checks (`REMINDER.md` items 19–20) | ✅ V |
| Diagnostic overlay (`f_reorder`/`f_skip`/`f_dup`/`f_snap_of`) | mode 7 sub 2 | ✅ V |
| Off-panel scratch / save-under | ❌ **does not exist** — the full 640×480 is visible | ❌ |

**Constraints that shape every design (the MEASURED CFW curve, 2026-08-31 — `overview.md`
§5.2): `ms ≈ 60 + bytes/50`** — a ~60 ms floor per flush, ~50–75 KB/s transfer ⇒ **cost is
still ack-floor-dominated, so batch aggressively and scroll coarsely** (the old stock numbers,
~176 ms / 7–13 KB/s, price only the retired stock path); deflate **level 6** (level 9 costs
18–109 ms/frame, level 1 pushes payloads past the 3800 B fragment boundary); **no dithering**;
FB lease must be renewed every 45 s or stock LVGL repaints over you.

## 2. Input

| capability | how | grade |
|---|---|---|
| Tap / double-tap / long-press / long-press-release | EvenHub `Sys_ItemEvent`; CFW adds SysEvent 9/10 — ⚠ since a5d1c31 EITHER TEMPLE raises 9 too, and 10 fires after almost every touch-end | ✅ V |
| **Per-notch scroll** | every notch delivered — **in daily use since 2026-08-30**; fast-spin coalescing still unprobed | ✅ M |
| **Per-source discrimination** (L temple / R temple / ring) | tap/scroll carry a real source byte — ⚠ but events 9/10 arrive with `EventSource` ABSENT (a long-press is UNATTRIBUTED; per-source long-press grammar cannot be built) | ✅ V (with that bound) |
| **Direct ring gestures, going around the glasses** | ring's own BLE link · `0x04` SWIPE_UP / `0x05` SWIPE_DOWN + **32-bit tick** ⇒ velocity possible | 🟡 C |
| Both-temple long-press → stock Silent Mode | unpatched by anyone — **keep as the hardware escape hatch** | ✅ M |
| Wake-word "Hey Even" as an app event | sid 0x07; CFW can suppress the stock handler | 🟡 S |
| Typed-text input path | ✅ BUILT 2026-08-31: `DamageWindow.onTypedText` via all three replicas, always confirm-staged (Tmux, Files, Torrents and Music consume it) — and since 2026-09-01 **typing from the ring alone** through the on-glass wireframe keyboard (`DESIGN.md` §4.8: tracker search, rename/new-folder, tmux Type…, Music's Ask / library search / YouTube / playlist names) | ✅ M |

## 3. Sensors & feedback

| capability | how | grade |
|---|---|---|
| **IMU x/y/z** (head tracking!) | EvenHub `Cmd 19/20` · `IMU_CtrlCmd{IMUReportEn, reportFrq}` · Faceclaw ships `buildImuControl()` | 🟡 V |
| **Wear / unwear detection** | CFW `wearnotify` → sid 0x10 `GLS_WEAR_STATUS`; op 7 queries current state | ✅ V |
| **Magnetometer compass** | CFW mode 10 · heading via stock sid-0x08 notifier | ✅ V |
| **Piezo buzzer** — presets, notes, **arbitrary 1–20000 Hz tones, and 48-step sequences** | mode 5 kinds 0–4 · the only audio output on a device with no speaker · ❌ **never used by Damage** — excluded by `DESIGN.md` §0 (Adam: no sound from the glasses; mode 5 is never sent) | ✅ V (hardware) / ❌ (policy) |
| **Ring biometrics**: hr, spo2, hrv, temp, steps, kcal, battery — all with timestamps | ❌ **Not available to any open-source path — NOT PURSUED (cosmetic).** Glasses can't relay it (firmware source); the ring has no standard Battery Service and no battery in its advertisement; its vendor link (`bae80001-…`) is request/response with a custom checksum (would need reverse-engineering + writes to a live input device); and **Faceclaw does not read it either** — only the closed Even SDK does. Full evidence + the retracted "Faceclaw does this" lead in `CLAIMS.md`. The chrome **R cell was removed** (dead chrome); ring battery is visible in the Even app | ❌ (all open paths) |
| Ambient light / auto-brightness, head-up angle, anti-shake | sid 0x80 `DeviceInfo` · sid 0x09 settings | 🟡 V |
| Glasses battery / charging / case SoC / lid / in-case | sid 0x09 f4.12–13 (⚠ the BARE `08 02 10 xx` READ — the f4-sub-request form returns no device-info on the CFW, measured 2026-08-31); sid 0x81 `GlassesCaseInfo`. **In daily use: the chrome G cell** | ✅ M (CFW) |
| Glasses microphone | ⚠ disconnects >25 s; audio is on service `6450` | ⚠ S |

## 4. System / plumbing

| capability | how | grade |
|---|---|---|
| Settings: brightness, silent mode, head-up, wear, lens x/y, dominant hand, units | sid 0x09 `G2SettingPackage` — **brightness write exercised on our own wire 2026-08-31** (faceclaw's form; the panel follows live) | ✅ M (brightness) / V |
| **Gesture remapping** (`APP_Send_Gesture_Control{screenOn, operationType, apptype}`) | sid 0x09 — never probed. *(Historical: it was a candidate for the gloves problem *without* the CFW; the CFW is installed and that chain is closed — `overview.md` §6)* | ❓ V |
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
- ❌ **2M PHY** — rejected by the glasses. 1M only; the stock path reached ~10% of it, the CFW path ~40–60%.
- ❌ **Firmware read-back** — *probably*; but see the file-export row above.

---

## Where the leverage is

**Highest ceiling, already ours:** mode-8 batched damage with real fonts. Everything G2CC avoided
because images cost seconds — thumbnails, embedded images, game frames, an ebook reader with real
typography — is now bounded by the measured `ms ≈ 60 + bytes/50` curve, once per frame.

**Highest ceiling, not yet ours:** the **texture cache** — in the firmware since `a5d1c31`, wire +
byte-exact model built (`IMPLEMENTATION.md`), compositor adoption still gated on the on-glass
checks (`REMINDER.md` items 19–20). It is what makes anti-aliased text cheap at scale, and it is
the difference between "AA text is affordable" and "AA text is free." Design so it can be adopted
without rework.

**Most under-explored:** the **logger** (turns silent failure into visible failure — directly serves
the project's own rule) and the **file-export service** (addresses the one genuinely irreversible
thing about this project). Both are cheap probes and neither has been touched.

**Most likely to be wrong** *(updated 2026-08-31)*: ~~per-notch scroll~~ (resolved — works, daily
use) and the arm split, which runs daily but is still unproven as the *optimal* split (the
two-arm capture has never been taken).
