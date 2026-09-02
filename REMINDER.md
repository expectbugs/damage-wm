# Where we are, and what to do next

**Updated 2026-09-01 (late night): TORRENTS + THE KEYBOARD ARE BUILT** — the second G2CC→DamageWM
conversion, on Adam's rule for every window from now on (**built whole to its best state before
the next; no v1/v1.5 staging**): `TORRENTS.md` (the verdicts, the verified qBittorrent 2.11 +
TorrentLeech facts, the design), `DESIGN.md` §4.8 (the ring-driven wireframe keyboard — row then
key, draft kept, QWERTY/abc — the fourth bespoke shell surface after the wheel, the notification
box and the context menu), `HANDOFF.md` §23 (the record). qBittorrent on beardos serves its Web
API on 127.0.0.1:8090 (rebuilt with USE `webui`; `DAILY.md`). The review loop ran FOUR rounds
(`HANDOFF.md` §23.1–§23.4, ~114 findings, every one verified before a fix) and is **PAUSED at
Adam's word, not converged** — the next review pass starts from `980d832..HEAD`.

Earlier the same day: **FILES**, the first conversion, with the whole EXPLOSION §16 machinery
under it (the LWW state substrate with per-item sub-records, the generic window channel, deep
links + the notification signature, the context-menu surface + the Draw kit, theme icons per
Adam's Papirus ruling) and an eight-round review loop run to convergence (79 → … → 0) —
`HANDOFF.md` §22. Reader / Tmux / Files / Torrents are the worked precedents (`WINDOWS.md`).

**State of the world:** the phone APK is the primary driver (`HANDOFF.md` §19 — radio + shell
while it is up); the OpenRC `damage` service is the data provider (content + tmux + sync + the
window channel on :7401, seam :7402, replica :7403) plus a standby that BLE-drives only while the
APK is away. Battery at HEAD (`390a25c`): core **221** · desktop 9 · selfcheck **89** ·
snapshots 26 · epub-check clean · lint 0. **APK 18/0.18 is STAGED** (the setup page +
`~/.damage/damage-wm.apk`; the jar and the service run the same round-4 build). **0.16 is the
last build observed INSTALLED** (2026-09-01 — the phone spoke the files channel); 0.17 was never
installed and 0.18 supersedes it (Files, the chrome tweaks, Torrents, the keyboard, every
2026-09-01 review fix). Deploys: `./gradlew :desktop:stageJar && sudo rc-service damage restart`
(never touches the display — the PC does not claim). ⚠ One central at a time: stop the service
before any `:desktop:run`; G2CC's Android bridge stays Disconnected.

📍 **Start here, in this order:** this file → `HANDOFF.md` §19–§23 (the topology contract, the
overnight record, the Torrents + keyboard record) → `DAILY.md` (ops crib) → `IMPLEMENTATION.md`
(what runs) → for the next conversion: `WINDOWS.md` (the checklist) + `EXPLOSION.md` (§16
contract, §20 refinery verdicts, the chosen window's section). Standing references: `overview.md`
(facts), `CLAIMS.md` (grades), `CLAUDE.md` (rules), `DESIGN.md` (the shell).

## 🚀 Next

1. **Install 0.18, then on-glass verdicts for Torrents + the keyboard**: the keyboard's feel
   (row pitch at 288, the highlight, the text-line pan, stay-in-row, the Tmux live rows), the
   transfers list and lens, a real done-notification, browse / search / add against the live
   tracker (the first real add is the first real download through the adapter). Then the
   resumed review pass from `980d832..HEAD` — round 4 still found real defects in round 3's
   fixes. Still owed on glass from before: Files (the menu grammar feel, viewers, the thumbnail
   lens, theme icons at 20/56 px), the night wave (the tmux flow view, fonts previewed in their
   own faces, per-app depth), and the live checks — the standby drill (stop the APK at the desk
   → the PC BLE-drives → restart → handback) and a book position following a driver swap.
2. **The Reader transitional cleanup** (UNBLOCKED — 0.16 is installed): remove the
   legacy-offsets dual-write in `ReaderWindow` — the fields are marked; `restoreStateLive`'s
   `liveMapApply` mechanics go with them (update `SubstrateTest`'s migration pin). A clean first
   task for a fresh session.
3. **MUSIC — BUILT overnight 2026-09-02/03** (`MUSIC.md` §1 = the 29 binding verdicts; M1–M6
   committed one milestone at a time with the battery green; `HANDOFF.md` §24 = the build record
   + the decisions made inside the plan). What waits: **install APK 0.19**, the one-time
   notification-access grant (`DAILY.md` → Music), the on-phone measured items (`MUSIC.md` §12:
   the limiter's real notice text, the Spotify cold start, the Bluetooth lyric offset, the
   visualizer rate on glass), then the review loop (`REVIEW.md`) from the M1 commit forward.
4. **The icon-quality pass** (front of the app wave): one drawn icon per app at 20 px + 56 px —
   the drawn set is the fallback and the release path (theme icons are personal-lane only).
5. **Watch-items:** the left-lens seam residue (a one-shot early-burst tear — if it recurs
   after a handover, harden session start); the ~20 s silent-loss window (tighten the seam
   heartbeat constants only if it feels long in practice).

## 🔴 Still unmeasured on glass

Closed items live in `HANDOFF.md` §11–§12 and `overview.md` §5 (ack curve, link-drop recovery,
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
| 10 | **Connected RSSI** — both paths read it from the RIGHT arm (`BleTransport` every 10th maintenance tick; `BlueZTransport` from BlueZ's `RSSI` property, which BlueZ fills only while a scan sees the device); no value has been checked on glass | the status bar's link cell |
| 14 | **The stall report** — force a lost image ack (RF shielding) | must show `stall!` with the link otherwise healthy |
| 15 | **Is the sid-0x01 prelude required** by the CFW before CREATE? (graded U) | sent because the reference sends it; `LaunchMsg` says where to change it |
| 19 | **The texture cache on glass** — mode-12 atlas up, 13/14 draws, pixel-compare vs the sim | the gate on adopting cached glyphs. ⚠ mode 14 adds one overlay rect per glyph (a >16-glyph string shows incomplete OUTLINES — the overlay, not a fault); a failed 64 KiB allocation shows only as the sticky `ALLOC` flag |
| 20 | **Atlas upload cost** at the measured rate; cache survives a lease *renewal*, is freed on a lapse | prices the whole mode-14 trade |
| 21 | **Temple long-press accident rate** (gloves, coat pocket) — either temple raises event 9 since a5d1c31 | §1.2's bare-long-press no-op guards it; confirm the default still feels right |

**Start BTSnoop BEFORE connecting** on any recapture — handle 65's connection setup is the one
gap in the corpus.

**Cheap probes nobody has run:** the CFW logger service (sid 0x0F — would turn silent
`decompress failed` into a visible error; highest-value untested lead) and the file-export
service (sid 198/199 — the only lead against "no firmware read-back"; its error enum has an
explicit `NOT_SUPPORT`, so the probe is safe).

## Open design questions (not hardware-blocked)

- **Where system-state detail lives** — live telemetry is in the status bar; the deeper view
  wants to be the Info window (`EXPLOSION.md` §9 + the §20 useful-stats steer).
- **Per-window typefaces for windows not yet designed** — Music, SMS, Notices, Feed inherit
  Clear Sans until their app earns an override (Files shipped on Clear Sans, Torrents on Fira
  Sans — the list face). Deliberately not invented in advance. The curated font-library
  expansion is a separate backlog item (`DESIGN.md` §Type; option-only, defaults untouched,
  B612 never a default).

## System changes made for this project

- **`/etc/portage/package.accept_keywords/damage-fonts`** — `~amd64` for eight font *data*
  packages (no code) so the typeface survey could run. Safe to remove; the fonts stay.
- **44 `media-fonts/*` packages installed** (450 families). `design/fonts.json` pins the 66
  evaluated candidates. The locked faces are Clear Sans, Fira Sans, Alegreya and JetBrains
  Mono — **`tools/lint.py` checks glyph coverage against exactly those**; its table must grow
  if a window ever claims a fifth face.
- **`net-p2p/qbittorrent-5.1.4` rebuilt with USE `webui`**
  (`/etc/portage/package.use/60-qbittorrent`) for the Torrents window; its config gained
  `WebUI\Enabled=true`, `Address=127.0.0.1`, `Port=8090`, `LocalHostAuth=false` and the Web-UI
  password qBittorrent insists on (`~/.config/qBittorrent/webui-credentials.txt`, 0600) —
  `DAILY.md`. The TorrentLeech credentials live only in `~/.damage/config.json`.
