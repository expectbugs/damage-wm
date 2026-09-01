# Where we are, and what to do next

**Updated 2026-09-01 (overnight): FILES IS BUILT** — the first G2CC→DamageWM conversion, with
the whole EXPLOSION §16 machinery under it (the LWW state substrate with per-item sub-records,
the generic window channel, deep links + the notification signature, the context-menu surface +
the Draw kit, theme icons everywhere per Adam's Papirus ruling) — and an **eight-round review
loop run to convergence** (79 → 20 → 28 → 9 → 3 → 3 → 2 → 0 findings; round 8 found nothing).
`HANDOFF.md` §22 is the record; Reader / Tmux / Files are the three worked precedents.

**State of the world:** the phone APK is the primary driver (`HANDOFF.md` §19 — radio + shell
while it is up); the OpenRC `damage` service is the data provider (content + tmux + sync on
:7401, seam :7402, replica :7403) plus a standby that BLE-drives only while the APK is away.
Battery at HEAD: core **191** · desktop 9 · selfcheck 61 · snapshots 18 · epub 380/404 ·
lint 0. Jar + APK **16/0.16 staged**; the phone still runs **0.15** until Adam installs.
Deploys: `./gradlew :desktop:stageJar && sudo rc-service damage restart` (never touches the
display — the PC does not claim). ⚠ One central at a time: stop the service before any
`:desktop:run`; G2CC's Android bridge stays Disconnected.

📍 **Start here, in this order:** this file → `HANDOFF.md` §19–§22 (the topology contract and
the overnight record) → `DAILY.md` (ops crib) → `IMPLEMENTATION.md` (what runs) → for the next
conversion: `WINDOWS.md` (the checklist) + `EXPLOSION.md` (§16 contract, §20 refinery verdicts,
the chosen window's section). Standing references: `overview.md` (facts), `CLAIMS.md` (grades),
`CLAUDE.md` (rules), `DESIGN.md` (the shell).

---

## 🚀 Next

1. **Install APK 0.16** (the setup page's DamageWM box — Files, theme icons, the sync client,
   every 2026-09-01 review fix; 0.15 lacks them all). Then the live checks: watch the first
   sync exchange in both logs; the standby drill (stop the APK → the PC BLE-drives → restart →
   handback); a book position following a driver swap. **Once the installed APK is ≥ 0.16,
   remove Reader's transitional legacy-offsets dual-write** (`ReaderWindow` — the fields are
   marked; `restoreStateLive`/`liveMapApply` go with them).
2. **On-glass verdicts** — Files (the menu grammar feel, viewers, the thumbnail lens, theme
   icons at 20/56 px) and the still-unjudged night wave (the tmux flow view, fonts previewed in
   their own faces, per-app depth).
3. **The next conversion**, per `EXPLOSION.md` §20's wow order — Torrents was Adam's "Yes!".
   `WINDOWS.md` is the checklist; Adam's per-window refinery verdicts come first (no verdicts,
   no build).
4. **The icon-quality pass** (front of the app wave): one drawn icon per app at 20 px + 56 px —
   the drawn set is the fallback and the release path (theme icons are personal-lane only).
5. **Watch-items:** the left-lens seam residue (a one-shot early-burst tear — if it recurs
   after a handover, harden session start); the ~20 s silent-death window (tighten the seam
   heartbeat constants only if it feels long in practice).

## 🔴 Still unmeasured on glass

Closed items live in `HANDOFF.md` §11–§12 and `overview.md` §5 (ack curve, link-death recovery,
PC BLE, takeover/fallback, the switcher root cause, unsolicited frames). Still open:

| # | what | why it matters |
|---|---|---|
| 1 | **Safe area** — draw a border, shrink until fully visible, store it | `DESIGN.md` §2.2b: 480 vs 288 is a *calibrated setting* |
| 2 | Ring **fast-spin coalescing + event-rate ceiling** (per-notch delivery itself works daily) | the focus model's limits |
| 3 | **Comfortable disparity** — ramp 0/4/8/12/16 | and whether stock FAR already spends the budget |
| 4 | **The rect budget of 5** (graded I) | derived from `cfw_diag()`, never observed; failure is silent |
| 5 | **Two-arm BTSnoop capture** | settles the bulk-LEFT / control-RIGHT split (graded I) |
| 7 | **msgId-255 behaviour under CFW** | it ends the link on stock |
| 8 | **Chrome legibility** at the real faces on glass | renders cannot answer it |
| 9 | **WEA/CMAS visibility to a normal Android app** (Pixel 10a) | `DESIGN.md` §4.5's emergency promise rides on it |
| 10 | **Connected RSSI** — obtainable at all, and from which link | the status bar's link cell |
| 14 | **The stall report** — force a lost image ack (RF shielding) | must show `stall!` with the link otherwise healthy |
| 15 | **Is the sid-0x01 prelude required** by the CFW before CREATE? (graded U) | sent because the reference sends it; `LaunchMsg` says where to change it |
| 19 | **The texture cache on glass** — mode-12 atlas up, 13/14 draws, pixel-compare vs the sim | the gate on adopting cached glyphs. ⚠ mode 14 adds one overlay rect per glyph (a >16-glyph string shows incomplete OUTLINES — the overlay, not a fault); a failed 64 KiB allocation shows only as the sticky `ALLOC` flag |
| 20 | **Atlas upload cost** at the measured rate; cache survives a lease *renewal*, dies on a lapse | prices the whole mode-14 trade |
| 21 | **Temple long-press accident rate** (gloves, coat pocket) — either temple raises event 9 since a5d1c31 | §1.2's bare-long-press no-op guards it; confirm the default still feels right |

**Start BTSnoop BEFORE connecting** on any recapture — handle 65's connection setup is the one
gap in the corpus.

**Cheap probes nobody has run:** the CFW logger service (sid 0x0F — would turn silent
`decompress failed` into a visible error; highest-value untested lead) and the file-export
service (sid 198/199 — the only lead against "no firmware read-back"; its error enum has an
explicit `NOT_SUPPORT`, so the probe is safe).

## Open design questions (not hardware-blocked)

- **Where system-state detail lives** — live telemetry is in the status bar; the deeper view
  wants to be the Info window (`EXPLOSION.md` §14, Adam's "useful info" steer).
- **Per-window typefaces for windows not yet designed** — Music, SMS, Notices, Feed inherit
  Clear Sans until their app earns an override (Files shipped on Clear Sans). Deliberately not
  invented in advance. The curated font-library expansion is a separate backlog item
  (`DESIGN.md` §Type; option-only, defaults untouched, B612 never a default).

## System changes made for this project

- **`/etc/portage/package.accept_keywords/damage-fonts`** — `~amd64` for nine font *data*
  packages (no code) so the typeface survey could run. Safe to remove; the fonts stay.
- **44 `media-fonts/*` packages installed** (450 families). `design/fonts.json` pins the 66
  evaluated candidates. The locked faces are Clear Sans, Fira Sans, Alegreya and JetBrains
  Mono — **`tools/lint.py` checks glyph coverage against exactly those**; its table must grow
  if a window ever claims a fifth face.
