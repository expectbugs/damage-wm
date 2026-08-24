# Damage — implementation notes (first stage)

**Built 2026-08-24.** The first executable stage of the plan: the shell core, the
byte-exact glass simulator, the desktop program, and the phone APK — Reader and
Main only at the app layer, the full shell underneath, everything targeting the
**CFW display contract** (modes 3/6/8/9, the FB lease, the capability gate).
Nothing here touches the real glasses: they stay on stock 2.2.2.20 and G2CC
until flash day, and the two decisions that could not wait (`DESIGN.md` §11
items 11–12) are now made and coded.

## The two locked decisions

**Runtime: Kotlin/JVM** (open item #11). One `:core` library holds the entire
shell — compositor, wire codecs, simulator, surfaces, Reader — and runs
unmodified inside the desktop JVM program and the Android APK. Core uses no
AWT and no android.*; platform text rasterization enters through
`wm.damage.core.text.TextRasterizer` (AWT on desktop, android.graphics on the
phone), with faces x-height-normalised to the §Type measurements on both.

**The transport ↔ shell seam** (open item #12) is
`wm.damage.core.transport.Transport`:

```
shell  ──FlushRequest{ops: Keyframe|Delta|Copy|StereoPair, epoch, wide}──▶  transport
       ◀──TransportEvent{Input, FlushDone, Lease, Link, DiagFlags, Fault}──
```

- Ops are NOMINAL coordinates + per-op disparity; the emitter builds the
  per-lens stereo boxes (§3.4). Payloads arrive pre-compressed (zlib(rle)) —
  compression is the shell's job (§10.1).
- **Fids are stamped by the transport at EMIT time** (§8.2 #5), via the shared
  `Emit` encoder every implementation uses.
- `submit()` suspending until a window slot frees IS the backpressure signal
  §5.13's coalescing rides on. A `wide` flush drains the window and runs at
  depth 1 — §8.2 #4's rects-for-depth trade.
- The same interface serializes over TCP (`RemoteTransportClient/Server`,
  length-prefixed JSON + binary): the shell can live on the PC while the
  transport lives on the phone, or the reverse. The server admits ONE driver;
  claim/yield callbacks let the phone's local shell hand over and take back —
  the "both able to take over" requirement.

Every transport shares `CfwTransportBase` — the full choreography (capability
gate → carrier CREATE → lease both arms + 45 s renewal → warmup splash → idle
keepalive → fragmenting ≤3800 B → msgId/session discipline) — so the banked
BLE path runs the exact protocol brain the sim exercises on every selfcheck.

## Module map

```
core/       wm.damage.core.geom       panel constants, Rect, the runtime lint gate
                                      (same rule IDs as tools/geometry.py), Layout
                                      (safe-rect-relative, §2.2b), fid discipline
            wm.damage.core.gfx        Gray8 compose surface, firmware-exact nibble
                                      RLE (pinned to fbfeas.py vectors), 4bpp pack,
                                      level-6 deflate, drawn icons/shapes (§4.5b)
            wm.damage.core.wire       CRC-16, protobuf, AA envelope + reassembly,
                                      EvenHub carrier messages, sid-0x09 lease +
                                      capability, mode 3/6/8/9 builders (all byte
                                      layouts read from zlib_glue.c)
            wm.damage.core.sim        GlassFirmwareSim — the byte-exact model:
                                      per-lens shadows, cfw_diag fid ring + flags,
                                      warmup drop, msgId-255 kill, stuck sessions,
                                      lease fail-open, silent rejects made loud
            wm.damage.core.transport  the seam + Emit + CfwTransportBase +
                                      SimTransport + Remote client/server
            wm.damage.core.comp       the compositor (one mode-8 flush per frame,
                                      §5 rules), JSONL journal
            wm.damage.core.shell      Shell orchestrator, input grammar, chrome,
                                      Main, switcher, notifications, silent mode,
                                      ContentKit (lens/list/document), slides,
                                      persistence, settings
            wm.damage.core.windows.reader  Reader + EPUB extraction
            wm.damage.core.content    library providers: local dir, TCP host,
                                      remote client with copy-on-open caching
desktop/    AWT rasterizer · Swing 1x lens preview (keyboard = ring) · CLI
phone/      Android app: foreground ShellService, on-screen lens view (touch =
            ring), AndroidText (bundled OFL/Apache fonts), banked BleTransport,
            transport seam server, §9.3 urgent phone notifications
```

## Running it

Desktop (laptop-direct with the sim standing in for glass — §10.8's
development environment; also serves ~/books to the phone):

```
./gradlew :desktop:run                        # preview window + content host
./gradlew :desktop:run --args="--selfcheck"   # the 25-check scripted gate
./gradlew :desktop:run --args="--snapshot DIR"  # lens-truth PNGs of every surface
./gradlew :desktop:run --args="--epub-check"  # parse every book in ~/books
./gradlew :desktop:run --args="--host-only"   # content host alone
./gradlew :desktop:run --args="--remote HOST" # PC shell driving a remote transport
```

Preview keys: ↑/↓ scroll · Enter tap · Backspace double-tap · Space long-press
· Tab lens toggle. Config in `~/.damage/config.json` (books dir, ports, token —
token is generated on first run and must match `damage-secrets.properties`
before building the APK).

Phone:

```
./gradlew :phone:assembleDebug
# -> phone/build/outputs/apk/debug/phone-debug.apk  (sideload on the Pixel 10a)
```

The APK runs the same shell against the sim, rendered on screen (integer-scaled,
labeled — legibility calls stay with the 1x desktop view or glass). It fetches
the library from beardos over Tailscale, **copies each book locally on open**,
and falls back to the cache when the PC is unreachable. It also serves the
transport seam on :7402 so `--remote` from the PC can take over its display,
local shell yielding and resuming automatically.

## Configurations wired today

| configuration | how |
|---|---|
| laptop-direct (sim) | `:desktop:run` — one process, the dev environment |
| app alone | APK with no PC: cached library + cached books |
| app + home PC | APK + `--host-only` (or full desktop run) on beardos |
| PC shell → phone transport | desktop `--remote <phone-ip>` against the APK's seam server |

## Banked, deliberately

- **`BleTransport`** compiles and ships in the APK but has NEVER touched
  hardware. Settings default to the sim target; the capability gate refuses
  any firmware without an `EVENCFW` string, so stock glasses cannot be painted
  even by mistake. First light follows `REMINDER.md`'s checklist after Adam
  flashes the CFW.
- Compass, IMU, wear detection: per `DESIGN.md` (§7) — compass cell draws a
  placeholder until the mode-10 feed exists; head tracking defaults OFF.
- Texture caching (Babcock's in-progress firmware work): rects are already
  content-addressed at the emit boundary in spirit — adopting the cache is a
  transport-level change by design (§5.18).

## Review hardening (rounds 2–8, 2026-08-24)

After the first build, seven rounds of independent review (fresh reviewer
agents per subsystem, every candidate verified by trace, timing or pixel
simulation before a fix) found and fixed ~70 real defects. The mechanisms that came out
of them are load-bearing and easy to break by accident:

- **Compositor per-lens model.** The compositor reasons per lens, not in
  nominal rects. It keeps an expected shadow of what each lens shows, renders
  the per-lens TRUTH of the nominal frame under the plane map (the nominal
  frame is the transparent base every shift may spill over — §3.3's insets;
  each region vacates its nominal area to black, the seam; region pieces
  render at their shift far to near, the nearest wins), diffs shadow against
  truth on the 4×2 damage grid, merges the differences toward the pipelined
  rect budget (coarsened by row bands first so merging stays cheap; within a
  piece, then across pieces of one disparity but never across a pixel of
  another plane; a final priced pass merges neighbours whose compressed
  union is cheaper — §5.1, §8.2's "1–3 rects"), and emits whatever closes
  the gap: nominal deltas at their disparity (split when a payload would
  exceed a mode-8 sub-message's 16-bit length or the bytes left in the
  batch; a keyframe past the sub-message length ships bare), black stereo
  pairs for whole seam strips. Every planned op is applied to the shadows as
  it is planned, so its effect on the OTHER lens (a far piece spilling under
  a nearer one) is seen and repaired in the same flush, in later-wins order.
  What the 16-fid ring or the batch's byte cap (bmp_max) cannot carry stays
  dirty for the next flush, which continues at the wide aim. A lost flush
  marks the per-lens cells it touched UNKNOWN — transmitted again from the
  truth, with the marks following any copy applied since as a coalesced
  frontier — because no byte snapshot can say what the glass holds once
  other flushes have landed around it. Plane changes, seam cleanup, keyframe follow-ups and reclaims
  are not special cases — they are differences between shadow and truth.
  `LensOracleTest` pins it: after every flush the belief equals the firmware
  model's lens panels, and at rest each lens equals an independently written
  truth, across depth 8/12/16 and every shell transition; `Round5Test`,
  `Round6Test` and `Round7Test` add lost flushes, cell noise under a box,
  text-shaped damage economy, oversize payloads, rollback after many copies
  and the batch byte cap. A frame the firmware can never accept
  (three failed keyframes) halts the pump with one notice until the content
  changes.
- **Transport session lifecycle.** Queued work carries a session epoch;
  `stop()`, a failed `start()` and `onLinkDown()` bump it and SWEEP: pending
  acks fail, window permits return, both queues drain loudly, a start parked
  on the capability gate is answered with a sentinel and refuses. A flush
  never spans the 0xFFFE→1 fid wrap (pre-clear + restart); a failed encode
  hands its fids back; completions leave in submission order; msgId cycles
  1..249. The window-full-no-ack stall is REPORTED as a fault, never acted on.
- **Shell.** start/stop serialize on a mutex (a stop during start waits and
  never saves defaults over unread state). A notification box is LIFTED
  (its under-snapshot restored) before any slide steps beneath it and
  repainted after. Every dynamic chrome string is sanitised to the locked
  glyph set and fitted with the continuation mark.
- **Content.** Host reachability is decided in one place
  (`RemoteContent.withHost`) with attempt ordering; local disk failures never
  read as "PC gone"; the cache keeps the listing's real extension.

## Verification

- `./gradlew :core:test` — 47 unit/integration tests: RLE parity against the
  Python reference implementation, CRC vectors, the geometry/fid rule fixtures
  shared with `tools/lint.py --selftest`, full pipeline round trips through
  the sim (stereo divergence per lens, mode-8 scroll batches, duplicate-fid
  skip, msgId-255 silence, lease expiry, warmup drop, out-of-order aborts),
  the shell behaviour/persistence gates, and `Round3Test` (a fid wrap inside
  a flush, a busy plane map's keyframe within the fid ring, a plane change
  with no pixel change, stop-during-start, same-instance transport restart).
- `--selfcheck` — the whole stack scripted end to end with real fonts,
  asserting ink budgets, input grammar, persistence byte-behaviour, and zero
  faults/failed flushes/sticky flags.
- `--snapshot` — renders what the LEFT LENS PANEL holds (post-wire truth,
  through pack → RLE → deflate → fragmenting → sim firmware → shadow), at
  true 1x. This harness caught the stereo vacated-strip ghost within minutes
  of existing.
- `tools/lint.py` still gates the repo at 0 findings; its geometry rules are
  mirrored 1:1 (same rule IDs) in `wm.damage.core.geom.Geometry`, and
  `GeometryTest` pins both to the same fixtures.
