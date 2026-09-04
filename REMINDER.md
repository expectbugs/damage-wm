# Where we are, and what to do next

**Updated 2026-09-03 (late): a WHOLE-CODEBASE REVIEW shipped — ten verified defects, each
reproduced before it was fixed and each pinned by a test that fails without its fix**
(`core/.../Review20260903Test.kt`, `HANDOFF.md` §25). Commits `1f9fa4d` (the fixes) and
`c400d0b` (APK 23/0.23), both pushed; the `damage` service was restarted onto the review
build and the phone reattached to all five channels.

The technique worth keeping: the mirror/divergence check compares the compositor's BELIEF to
the GLASS, so a bug that writes wrong pixels into the shadow **and sends them** is invisible
to it. The review's oracle recomputes the per-lens **truth** of `comp.composed` under
`comp.planes` and compares that to `comp.expectedLens()` after every settle; a 900-step
random-gesture walk with that assertion is what found the notification overrun. ⚠ Split the
panel by plane **pieces**, not raw plane rects, or the oracle reports false pixels at the
lens edges.

What it changed, in one line each — the detail is `HANDOFF.md` §25:

1. **Compositor** — a plane-0 delta could carry another plane's pixels (two unguarded merge
   paths). Wrong shift on both lenses, outside the scanned area, belief and glass agreeing on
   the wrong thing. Guarded; rect economy unchanged.
2. **Notifications** — the silent/exclusive box is 200 px but its body was wrapped to the
   248 px window form: 240 px painted outside the damaged rect. `SILENT_W` + per-form
   `bodyLines` + fitted draws.
3. **Reader** — `Epub.fold` maps the C1 mojibake range through Windows-1252, folds U+2011 and
   drops zero-width formatters; every dynamic string now goes through `Draw.dynamic`. Measured
   on the real 58-book shelf: **14,365 undrawable code points → 50**.
4. **Tmux** — `FlowRender` sanitizes at layout time (the live panes carry Claude Code's own
   TUI glyphs, which JetBrains Mono lacks).
5. **Music** — a restored level below the top now loads on the way back; the desktop mirror no
   longer publishes an empty player record (that is the shell's removal TOMBSTONE, on a
   syncable key); the quiet-stream notice re-arms when you raise the volume it told you to.

**Before it, the same day: the Music window's root became NOW PLAYING, and the "it played but I
heard nothing" bug was understood.** Adam drove the new windows for real: Tmux, Files and
Torrents all worked; Music played four tracks end to end and made no sound. The cause was
measured, not guessed — the transcode cache showed song-length gaps (so the queue really was
advancing) and the synced player record read `volume: 8`. **The phone's media stream was at
8 %.** Damage never sets that level (only the ring's Volume canvas, the Settings row and the
limiter's restore write it, all upward or user-driven); it read 8 % and played into it. The
defect was that *nothing said so*. `HANDOFF.md` §24.4 is the full record.

Three things came out of it:

1. **A quiet-stream notice** — `PlayerEvent.QuietStream` fires when playback starts at or below
   `PlayerCore.QUIET_PCT` (10 %), once per playback RUN, on glass and as a notification when the
   window is off screen.
2. **The output is restored by STABLE identity** — `AudioDeviceInfo.getId()` is a per-connection
   handle that changes on reconnect and gets reused, and the old code ignored the sink's refusal.
   The record now carries `outputName` + `outputKind`; an ambiguous or absent match refuses
   loudly to Auto instead of routing to whatever inherited the number.
3. 🔴 **Verdict 4 REVERSED (Adam): NOW PLAYING is the root, the queue is a menu row.** *"the main
   screen should be a useful, really nice looking Now Playing screen … what is playing and where
   in the song it is and what the volume level is at."* A canvas, TOP-aligned (his fit loses the
   bottom): art 160/120/88 px by height + title + artist — album + badges · the state glyph,
   elapsed, progress, total · the media level (drawn **HOT at or below 10 %**) with the queue
   position · the current synced lyric line when one is loaded. **Scroll = volume live, tap = the
   Music menu**, no cursor. `MUSIC.md` verdict 4 records the reversal — do not reinstate the
   queue root. A review pass over that new code found and fixed seven more issues
   (`HANDOFF.md` §24.4).

Before it: **MUSIC** built whole overnight 2026-09-01/02 and reviewed three ways (`MUSIC.md`,
`HANDOFF.md` §24.1–§24.3); **TORRENTS + the §4.8 keyboard** and **FILES** with the whole
EXPLOSION §16 machinery on 2026-09-01 (`TORRENTS.md`, `HANDOFF.md` §22–§23). Reader / Tmux /
Files / Torrents / Music are the worked precedents (`WINDOWS.md`).

**State of the world:** the phone APK is the primary driver (`HANDOFF.md` §19 — radio + shell
while it is up); the OpenRC `damage` service is the data provider (content + tmux + sync + the
window channel on :7401, seam :7402, replica :7403, the media endpoint :7404) plus a standby that
BLE-drives only while the APK is away. Battery at HEAD: core **329** · desktop 9 ·
selfcheck **139** · snapshots 36 · epub-check 58/58 · lint 0 · `--music-check` all pass against
the real library. **APK 23/0.23 is STAGED and is the one to install** — the first APK carrying
the review fixes; 0.22 and everything before it are superseded. **0.16 is still the last build
observed INSTALLED** (2026-09-01). The jar and the service **run the review build** (restarted
2026-09-03 16:14, phone reattached to files/torrents/music/tmux/sync); redeploy with
`./gradlew :desktop:stageJar && sudo rc-service damage restart` (never touches the display — the
PC does not claim). ⚠ One central at a time: stop the service before any `:desktop:run` dev
session; G2CC's Android bridge stays Disconnected.

📍 **Start here, in this order:** this file → `HANDOFF.md` §19–§25 (the topology contract, the
build records, §24.4 the silent-playback diagnosis + the Now Playing root, §25 the review) →
`DAILY.md` (ops crib) → `IMPLEMENTATION.md` (what runs) → for the next conversion: `WINDOWS.md` (the checklist) +
`EXPLOSION.md` (§16 contract, §20 refinery verdicts, the chosen window's section). Standing
references: `overview.md` (facts), `CLAIMS.md` (grades), `CLAUDE.md` (rules), `DESIGN.md` (the
shell).

## 🚀 Next

1. **Install APK 0.23 and use Music.** It carries the Now Playing root, both phone-side fixes
   and the whole-codebase review. Then the one-time grants (`DAILY.md` → Music: `music access`
   on the strip, notification access) and the on-phone measured items (the limiter's real notice text, the Spotify cold start, the Bluetooth
   lyric offset, the visualizer rate on glass). Judge Now Playing on glass — it measures **14.0 %
   ink** at 480 against the 15 % list budget with the harness's synthetic art, so a real album
   cover may trip it; the answers then are smaller art or reclassifying the surface as a canvas
   (Music Mode's note allows 30 %).
2. 🎴 **GAMES · HOLD'EM — designed, not built. `HOLDEM.md` is the plan.** Adam picked Games
   (`EXPLOSION.md` §20 #1) on 2026-09-03 and the whole refinery pass ran in session 2026-09-03/04:
   **36 verdicts**, the format (6-max sit-and-go, no rebuys), three tables, the shared bankroll
   with the visible entry fee and the Loser Count, the persistent bot ecology, the card look, the
   four-height ladder, the input grammar, the tests and a six-milestone build order. **Start at
   `HOLDEM.md` §16 (Kickoff), and M1 first** — it is a *shell* change Adam ruled generally
   (switcher resumes / Main shows the window's root list) plus a retrofit across Reader, Tmux,
   Files, Torrents and Music. ⚠ Games' licensing rule was revised 2026-09-02 (`CLAUDE.md`
   clean-room): the work never ships, the WINDOW that drives it may — Paperclips fetched from the
   author's site at run time (and Damage generates the DOM from element ids rather than shipping
   his markup), FF1 on a ROM the user rips themselves. Hold'em is entirely ours and needs none of
   that. **Feed + comics** (§20 #5) is the next candidate after it.
3. **On-glass verdicts still owed** for Torrents and the keyboard (the transfers list and lens, a
   real done-notification, browse / search / add against the live tracker; the keyboard's row
   pitch at 288, the highlight, stay-in-row), for Files (the menu grammar, viewers, the thumbnail
   lens, theme icons at 20/56 px), and the live checks — the standby drill (stop the APK at the
   desk → the PC BLE-drives → restart → handback) and a book position following a driver swap.
   Then the resumed Torrents review pass from `980d832..HEAD` — round 4 still found real defects
   in round 3's fixes.
4. **The Reader transitional cleanup** (UNBLOCKED — 0.16 is installed): remove the
   legacy-offsets dual-write in `ReaderWindow` — the fields are marked; `restoreStateLive`'s
   `liveMapApply` mechanics go with them (update `SubstrateTest`'s migration pin). A clean first
   task for a fresh session.
5. **The icon-quality pass** (front of the app wave): one drawn icon per app at 20 px + 56 px —
   the drawn set is the fallback and the release path (theme icons are personal-lane only).
6. **What the 2026-09-03 review did NOT cover** (`HANDOFF.md` §25.3): the seam / replica /
   `RemoteTransport` plumbing, the firmware simulator and most of the phone module were read at
   a scanning level only, and nothing was tested on the actual glasses. A next review round
   starts there, not by re-reading the core.
7. **Watch-items:** the left-lens seam residue (a one-shot early-burst tear — if it recurs
   after a handover, harden session start); the ~20 s silent-loss window (tighten the seam
   heartbeat constants only if it feels long in practice); and the media endpoint logs nothing on
   a successful request, so "did the phone fetch audio?" is only answerable from cache mtimes.

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
- **Per-window typefaces for windows not yet designed** — SMS, Notices, Feed inherit
  Clear Sans until their app earns an override (Files and Music shipped on Clear Sans, Torrents on Fira
  Sans — the list face). Deliberately not invented in advance. The curated font-library
  expansion is a separate backlog item (`DESIGN.md` §Type; option-only, defaults untouched,
  B612 never a default).

## System changes made for this project

- **`/etc/portage/package.accept_keywords/damage-fonts`** — `~amd64` for eight font *data*
  packages (no code) so the typeface survey could run. Safe to remove; the fonts stay.
- **56 `media-fonts/*` packages installed** (453 families, 2026-09-02 count). `design/fonts.json` pins the 66
  evaluated candidates. The locked faces are Clear Sans, Fira Sans, Alegreya and JetBrains
  Mono — **`tools/lint.py` checks glyph coverage against exactly those**; its table must grow
  if a window ever claims a fifth face.
- **`net-p2p/qbittorrent-5.1.4` rebuilt with USE `webui`**
  (`/etc/portage/package.use/60-qbittorrent`) for the Torrents window; its config gained
  `WebUI\Enabled=true`, `Address=127.0.0.1`, `Port=8090`, `LocalHostAuth=false` and the Web-UI
  password qBittorrent insists on (`~/.config/qBittorrent/webui-credentials.txt`, 0600) —
  `DAILY.md`. The TorrentLeech credentials live only in `~/.damage/config.json`.
