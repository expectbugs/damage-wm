# Where we are, and what to do next

**Updated 2026-08-30 — the glasses run the CFW and the PC has driven them.**

📍 **Start here, in this order:** `HANDOFF.md` §11 (first light — what it proved and the three
defects it found), then **`REFINEMENT.md`** (the backlog Adam asked for after wearing it), then
this file for the checklist of what is still unmeasured.

**History, if you need it:** `HANDOFF.md` §10 is the firmware install; §10.13 is the ring update;
§9 is **superseded** by §10 and should not be followed. §8 is the finishing build, whose whole gap
list (no PC BLE, no phone target switch, replica dark on real glasses, BLE glue never run) is now
closed. Then the standing references: `overview.md` (facts), `CLAIMS.md` (how well we know them),
`CLAUDE.md` (rules), `DESIGN.md` (the shell) and `IMPLEMENTATION.md` (what runs and how).

---

## ✅ Done

| phase | state |
|---|---|
| Heavy research | **CLOSED 2026-08-17.** `overview.md`, ~1,530 lines |
| Full documentation | **CLOSED.** `CLAIMS.md` grades every load-bearing claim; `CAPABILITIES.md` inventories what the hardware can do |
| Offline CFW verification | **`research/verify_cfw.py` passes** — the image we would flash is reproducible from sources we hold, with no Thumb-bit defect |
| Capture corpus | recovered, unfiltered, SHA-pinned in `captures/` |
| **The shell design** | **CLOSED 2026-08-18.** `DESIGN.md`. All six surfaces specified, typography locked, costs **measured** |
| **Deployment topology** | **CLOSED 2026-08-20.** `DESIGN.md` §10 — three roles, four configurations, and the runtime constraint they impose |
| **Licensing** | **DECIDED 2026-08-20.** Clean room: protocol knowledge from `g2flash`/`faceclaw`, **no code**. Borrow from G2CC freely. `CLAUDE.md` has the rule |
| **The build gate** | **`tools/lint.py` + `tools/geometry.py`** — 20 rules, `--selftest` passes, repo exits 0 |
| **The renderer** | **`design/render_shots.py`** — every surface at true 1× 640×480, priced through the firmware's own RLE |

## ✅ Built 2026-08-24 — the first stage runs (Adam's explicit go-ahead)

Open items 11–12 are DECIDED AND CODED: **Kotlin/JVM** shell (one `:core` for desktop + APK) and
the transport↔shell seam as a protocol (in-process, and serialized over TCP with single-driver
takeover). What exists: the shell core + compositor, the **byte-exact glass simulator**, the
desktop program (1x preview, selfcheck, snapshots, content host), the **phone APK** (on-screen
shell, copy-on-open book caching, PC-takeover seam, **banked** BLE transport), and the Reader +
Main app layer. Everything targets the CFW contract. *(Written pre-flash — the glasses have run
the CFW since 2026-08-30 and the PC drives them daily.)*
See `IMPLEMENTATION.md`. The repo is under git.

**Hardened through eight review rounds the same day, then five more in the finishing build** (2026-08-25, `REVIEW.md`) (`IMPLEMENTATION.md` → "Review hardening",
~70 real defects): the compositor's per-lens truth/shadow model (rewritten twice; pinned by
`LensOracleTest`, `Round6Test`, `Round7Test`), the transport's session-epoch sweep and fid
discipline, the shell's start/stop mutex and notification lift, the content path's reachability
rules. The battery (`:core:test`, `--selfcheck`, `--snapshot`, `--epub-check`, `tools/lint.py`,
`:phone:assembleDebug`) is green at HEAD — keep it that way after any change. Reviews that only
checked exactness missed a livelock and cubic-time merging; measure convergence, bytes and wall
time on real content (`design/shots/`) too.

## ✅ The finishing build (2026-08-25) — "flash + install and it works"

`HANDOFF.md` §8 is the record (decisions, design, checklist, log). What now exists, all verified
against the simulator and the fake links, none of it on a radio yet:

- **The phone drives real glasses** once its Target is switched to *glasses* (strip button or the
  Settings row): the BLE glue follows G2CC's proven driver and the CFW reference's connect
  sequence (RIGHT then LEFT, MTU 512 checked, the sid-0x01 prelude, then lease + capability +
  carrier + warmup). A session keeper restarts the session after every link end, forever, with no
  timeouts; a non-CFW firmware is refused and the phone falls back to the simulator with a
  persistent notification.
- **The PC drives by whichever path works** (`auto`, the default): the phone's transport over the
  seam first, PC-direct BLE over BlueZ otherwise, every path retried while the search is open, a
  working path held until it ends. The phone's own shell yields while the PC drives and resumes
  when it leaves.
- **The replica is exact everywhere**: every transport owns a mirror (the firmware model fed the
  bytes it wrote); the desktop window (mouse = ring), the phone screen (touch) and the browser page
  (`http://<host>:7403/?token=…`, served by both the desktop and the phone) all draw it, and input
  from any of them reaches whichever shell drives. The shell compares its belief with the mirror at
  rest and reports any disagreement.

## 🚀 Next — refinement

**`REFINEMENT.md` is the work queue — and its whole 2026-08-30 backlog SHIPPED 2026-08-31**
(`HANDOFF.md` §12): chrome depth, per-app height, folders, scroll step + acceleration, the clock
(revised to digital top-right on Adam's mid-session call), the 4× preview, and the measured
latency curve. Still open from it: the §4 event-9 temple experiment, and Adam's verdicts on glass.

Still wanted from the original plan, now unblocked: **the app-layer scope explosion**, starting
from `CAPABILITIES.md` for what the hardware allows and `DESIGN.md` §0 / §4.6 for what the shell
provides and what is ruled out. Also queued by Adam 2026-08-31: **a quality pass on the icon set**
(the current ones are "very basic").

## ✅ Flash day and first light — DONE 2026-08-30

Both are history; the runbook that used to live here has served its purpose and is removed so
nobody follows it again by mistake. The records are `HANDOFF.md` §10 (install, with the dry-run
staircase and the two risks it closed) and §11 (first light). `research/verify_cfw.py` still
passes and is still the right first step before any future firmware work; `research/r1_dfu.py`
is the ring updater.

## 🔴 First light — the consolidated checklist

Everything below is blocked on being on hardware. Scattered across `DESIGN.md` §10 and
`overview.md` §11; gathered here so nothing is lost.

**Status after 2026-08-30.** Items 6, 12, 13 and 16 are closed — see `HANDOFF.md` §11.2. Item 18
is closed as *diagnosed but not working*, with the cause identified in §11.4 and the follow-up in
`REFINEMENT.md` §4. Everything else below is still open, and **every question about how the design
actually looks on glass is among them.**

| # | what | why it matters |
|---|---|---|
| 1 | **Safe area** — draw a border, shrink until fully visible, store it | `DESIGN.md` §2.2b: 480 vs 288 is a *calibrated setting*, not a design choice |
| 2 | ⚠ **Per-notch scroll — WORKING in daily use** (2026-08-30/31: every notch arrives as its own SCROLL event and drives the shell). Still open: whether the ring coalesces very fast spins, and its event-rate ceiling | the focus model rests on it; now observed, not just read from Faceclaw |
| 3 | **Comfortable disparity `d`** — ramp 0/4/8/12/16 | and whether stock FAR already spends the budget |
| 4 | **The rect budget of 5** (graded **I**) | derived from `cfw_diag()`, never observed; failure is silent |
| 5 | **Two-arm BTSnoop capture** | settles the bulk-to-LEFT / control-to-RIGHT split (graded **I**) |
| ~~6~~ | ✅ **CFW ack latency — the whole CURVE measured 2026-08-31**: `ms ≈ 60 + bytes/50` from 1,488 journalled flushes (floor median 60 ms, min 33; ~50–75 KB/s transfer; dense full-frame 2–4 fps). `overview.md` §5.2 | `HANDOFF.md` §12 |
| 7 | **msgId-255 behaviour under CFW** | it kills the link on stock |
| 8 | **Chrome legibility** at 32/28 px bars, real faces on glass | the one thing renders cannot answer |
| 9 | **Whether a normal Android app can see WEA/CMAS alerts** (Pixel 10a) | `DESIGN.md` §4.5 promises emergency alerts; unverified |
| 10 | **Connected RSSI** — obtainable at all, and from which link | the status bar's link cell |
| 11 | **Transport** — PC-direct BLE vs phone-bridged | decides where the BLE stack lives; PC-direct only ever works at the desk |
| ~~12~~ | ✅ **Link-death behaviour** — pull the glasses out of range mid-session | the session ends (LINK DOWN), the keeper restarts it after 2 s and scans until the pair is back; the mirror should show the splash then the restored surface |
| ~~13~~ | ✅ **Settings-frame timing** — the glasses DO send unsolicited sid-0x01 frames (codes 1000/2000); logged and ignored as designed. Original question: — does the real CFW ever send a sid-0x09 frame outside the capability query? | the gate only listens while querying; a stray frame is logged, not acted on |
| 14 | **The stall report** — force a lost image ack (RF shielding) | must show as a `stall!` fault in the status bar with the link otherwise healthy |
| 15 | **The sid-0x01 connect prelude** — graded U: does the CFW require it before CREATE? | the transport sends it (the reference does); the model treats it as required. If the real firmware answers differently, `LaunchMsg` says where to change it |
| ~~16~~ | ✅ **PC-direct BLE over BlueZ** — works, first try, and **no bonding was required**. Original question: — the MTU the characteristic reports, notification delivery, write-without-response pacing, **and whether beardos must BOND with the pair first** | first exercise of `BlueZDbus`; the nine fake-link tests say what is expected. The bonding half is open because every capture we hold was taken from an already-bonded phone (`HANDOFF.md` §10.5) — it applies to the flash and to the runtime link alike |
| 17 | **Takeover and fallback** — PC appears → it drives via the phone; PC gone → the phone resumes; phone gone at the desk → PC-direct BLE | every transition narrated in the status strip / phone status line and the browser page |
| 18 | 🔴→✅ **The switcher — real cause FOUND AND FIXED 2026-08-31: our own §1 source filter.** Events 9/10 are unattributed (source 0, `EventSource` absent by firmware design) and the shell's ring-only check (source ≠ 2 → drop) discarded every real long-press before the grammar ran — both routes dead since first light while `LongPressTest` passed on its flattering ring-source default. Fixed: 9/10 bypass the source check; the suite injects them with source 0. The hardware half is also settled: five deliberate ~1 s holds produced five clean event-9s (accidental brushes end early — their event-10s mean "a touch ended"). **Awaiting Adam's on-glass confirmation of both routes** (`REFINEMENT.md` §4) | the grammar's premise (the arming event is rare in normal use) is OBSERVED: zero 9s across a day of use, five on demand |
| 19 | **The texture cache on glass** (new, CFW `a5d1c31`) — upload a small atlas with mode 12, draw it with 13 and 14, and compare the panel against the simulator's prediction pixel for pixel | modes 12/13/14 are modeled byte-exactly but have never run on hardware. This is the gate on adopting cached glyphs for text. ⚠ **Two things not to misdiagnose:** mode 14 adds one overlay rect per glyph against a 16-deep list, so with the diagnostic overlay on, a string over ~16 glyphs shows incomplete outlines — that is the overlay, not a firmware fault. And a failed 64 KiB allocation shows ONLY as the sticky `ALLOC` flag, so leave the overlay on for the first upload |
| 20 | **What an atlas upload actually costs** — time a 16/32/64 KiB mode-12 upload at the measured link rate, and confirm the cache really survives a lease *renewal* while dying on a lapse | decides atlas size and whether a session can afford a full font. Prices the whole mode-14 trade against item 6's real ack latency |
| 21 | **Temple-touchpad long-press** — since `a5d1c31` either temple raises `SysEvent 9` too, not just the ring. How often does it fire by accident, in gloves, in a coat pocket? | `DESIGN.md` §1.2's "a bare long-press is a no-op" was decided for the ring alone and now carries a second accidental source. Confirm the default still feels right before anyone proposes relaxing it |

**Start BTSnoop BEFORE connecting** on any recapture — handle 65's connection setup is the one gap
in the existing corpus.

**Cheap probes nobody has run**

- **The logger service (sid 0x0F)** — would surface the CFW's own `decompress failed` messages.
  Highest-value untested lead; turns silent garbage into a visible error.
- **The file-export service (sid 198/199)** — the only real lead against "no firmware read-back".
  `eErrorCode` has explicit `NOT_SUPPORT`/`SUPPORT`, so the probe is safe.

---

## 🆕 Decided 2026-08-19/20 — the adaptable architecture

`DESIGN.md` §10. Damage runs in four configurations: **app + home PC** (full power) · **app alone**
(PC unreachable, degraded but functional) · **bridge appliance + home PC** (no phone) ·
**laptop direct** (nothing else). Cross-platform: Windows, macOS, Linux.

- 🔴 **The shell relocates between them**, so it must be one implementation that runs on **Android
  and desktop** — which rules out Python for the shell specifically.
- **Build laptop-direct FIRST.** It is the development environment: one process, real glasses, a real
  debugger, no network to blame.
- **The bridge appliance already exists on paper and in a parts box** — `G2CC/docs/HAT_BRIDGE_SPEC.md`,
  design-locked 2026-06-08, BOM bought, never built. It is also a controlled experiment on the ~10 %
  -of-spec throughput mystery, because its dual-band board removes BT/WiFi coexistence.

## Open design questions (not hardware-blocked)

- ~~A notification box while the switcher wheel is open~~ — **decided 2026-08-25: it waits behind the
  wheel** (queued unshown; a shown box goes back to the queue unread) and unfurls after the wheel
  closes. Built (`HANDOFF.md` §8.1 decision 6).
- **Where system-state detail lives** — orphaned when the long-press info popup became the switcher.
  Live telemetry is in the status bar; the deeper view wants to be a window, i.e. app-layer work.
- **Per-window typeface for the windows not yet designed** — Files, Calendar, Music, SMS, Timers,
  Scout, Notices all inherit Clear Sans until their app earns an override. Deliberately not invented.
- **Typed-text input** — G2CC had an `onTypedText` path; unported, and the ring alone cannot type.

---

## System changes made for this project

- **`/etc/portage/package.accept_keywords/damage-fonts`** — `~amd64` for nine font *data* packages
  (no code) so the typeface survey could run. Safe to remove; the fonts stay installed.
- **44 `media-fonts/*` packages installed** (450 families, up from ~35). `design/fonts.json` pins
  the 66 candidates that were evaluated. The four locked faces are Clear Sans, Fira Sans, Alegreya
  and JetBrains Mono — **`tools/lint.py` checks glyph coverage against exactly those**, so the table
  at the top of that file must grow if a window ever claims a fifth.
