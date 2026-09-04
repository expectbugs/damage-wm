# The daily driver — G2 all day from the phone, fed by beardos

**The default configuration (`DESIGN.md` §10.1 row 1, corrected reading — `HANDOFF.md` §19):
the PHONE SHELL is the primary driver, always; the PC is the DATA PROVIDER.** Set up
2026-08-31, re-shaped the same night when §8.1's "best case = home PC" was clarified to mean
data availability, not the driver. This file is the ops crib.

## The moving parts

| piece | job | kept alive by |
|---|---|---|
| **phone APK** (Target = glasses) | **THE driver** — owns the BLE radio and runs the shell, always; serves the seam :7402 (status probe + explicit dev claims) + replica :7403 | foreground service + wakelock + Doze exemption + `BootReceiver` (reboot/update) |
| **beardos `damage` service** | **the data host + standby**: books + tmux + STATE SYNC + the window channel (Files: listings, ops, viewers, blobs; Torrents: qBittorrent + the TorrentLeech session; Music: the library — Postgres/Qdrant/cache/resolver/lyrics/yt-dlp — with the media endpoint on :7404) + theme icons on :7401, the PC replica :7403; probes the phone every 5 s and starts a PC-direct BLE stack ONLY while the APK is not available, handing the radio back the moment it returns | OpenRC `/etc/init.d/damage` (supervise-daemon, enabled at `default`, headless `--no-preview`, mode `auto` = standby) |

**Who drives when** (all automatic, event-driven, pacing not timeouts):

- APK up (Target = glasses) → **the phone shell drives, full stop** — at home, at work, on LTE.
  The PC being reachable decides how CAPABLE it is (live library, tmux, sync), never who drives.
  The network is OUT of the interaction loop: gestures and frames never leave the phone.
- PC unreachable → **nothing changes on glass**; content degrades to the caches, staleness is
  said (`PC gone/Nm`), sync resumes on reconnect (last-write-wins both ways).
- APK down or Target = sim for ~2 probes (~10 s) → the PC **starts its own BLE stack** and
  drives PC-direct; when the APK returns, the PC stops it (the handback — the phone reconnects
  with its normal choreography, one repaint).
- Reboot either machine → both sides restart themselves; the policy re-settles on its own.
- **State sync**: reading positions, settings and every window blob flow between phone and PC
  whenever both are up — most recent wins, per key. A position turned on the phone shows up in
  a PC dev session and back.

## One-time setup — ✅ COMPLETED 2026-08-31 (kept for a re-pair or a fresh phone)

Done that day: v0.4 sideloaded + all grants, phone first light passed on the first try. If it
ever needs redoing: (1) sideload from the setup page, grant Bluetooth ×2 + notifications + the
battery exemption; (2) 🔴 keep the G2CC bridge app Disconnected (a second central); (3) phone
first light with NOTHING on beardos holding the pair (`sudo rc-service damage stop`), flip
Target → glasses; (4) `sudo rc-service damage start` → the log says "standby up (§19)" and the
phone keeps driving. ⚠ Keep the PHONE APK current with the PC (setup page — **0.27 is the
staged build: Files + theme icons + the sync client + Torrents + the keyboard + every
2026-09-01 review fix + the chrome tweaks (the Silent-clock size row included) + Music + its
three reviews + the 2026-09-03 Now Playing root and the two player fixes + that day's
whole-codebase review (`HANDOFF.md` §25) + **Games · Hold'em** and the shell's
switcher-resume / Main-root rule and all three of its review cycles (`HANDOFF.md` §26); 0.16 is
the last build observed installed**). ⚠ The 2026-09-04-late review (`HANDOFF.md` §28) is NOT in
0.27 or in the running service — its next stage is 0.28 plus a PC redeploy. Why old
APKs matter: a pre-0.15 APK cannot be status-probed (the PC conservatively stays out — fine)
and a pre-0.10 one carries no sync client, so state does not flow until it is updated. 0.16
being installed also unblocks Reader's transitional legacy-offsets cleanup (`REMINDER.md` Next 2).

## Music (2026-09-02, `MUSIC.md`) — the phone plays, the PC serves

| piece | job |
|---|---|
| **beardos `damage` service** | the music LIBRARY: Postgres `g2cc` over the Unix socket (peer auth, no password), Qdrant `g2cc_music`, the transcode cache (`~/.g2cc/media-cache` read in place as the Standard profile, `~/.damage/media-cache/<profile>/` for the rest), art + viz blobs, the resolver lanes (a `claude -p` one-shot for Ask, `audio/enrich/embed_query.py` on G2CC's venv for the embedding lane), yt-dlp, the enrichment passes; the **media endpoint on :7404** (`GET /track/<id>?token=&profile=`, Range-capable) next to the window channel on :7401 |
| **phone APK** | the PLAYER: ExoPlayer + a media session (bud taps: single = play/pause, double = next, triple = previous, from anywhere), the catalog/art/viz/lyrics cached on disk, the next 3 tracks prefetched, hold-my-volume, boost, sleep, Spotify as the fallback |

**One-time phone grants (0.23):** open the app → **`music access`** on the strip → allow
*Damage music* notification access (Spotify's session + the OS volume-lowered notice ride on
it; the window says "grant notification access on the phone" until then). No RECORD_AUDIO
is asked, ever. Then in Settings → Music pick the Output (Auto follows the buds; the phone
speaker plays only when chosen).

🔊 **If it looks like it is playing but you hear nothing, check the phone's media volume first.**
That was the 2026-09-02 report and the whole chain was working — the level was at 8 %
(`HANDOFF.md` §24.4). Since 0.22 the **Now Playing root shows the level and turns it HOT at or
below 10 %**, playback starting that quiet raises a notice, and **scrolling on Now Playing IS the
volume**, so it is one gesture to fix. Damage never sets the system level itself.

**Checks owed on the phone (the measured items — `HANDOFF.md` §24.1 "Measured vs modeled"):** the first real
hearing-limiter trigger at work — every volume change is logged with its cause and every
system notification from the system packages is logged with its text, so the matcher in
`MusicListener.rules` can be corrected from evidence; the Spotify cold start (does Spotify
publish a media browser service on this phone?); the Bluetooth lyric offset (Lyrics → scroll
nudges ±50 ms per output, remembered); the achievable visualizer rate on glass; **the buds' taps
reaching the media3 session** (it lives in the shell service, not a `MediaSessionService` — if a
tap does nothing with the app in the background, that is the first thing to check in logcat).

**The venv:** the enrichment package runs on G2CC's `/home/user/G2CC/audio/venv` until Damage
owns one — `audio/requirements-frozen.txt` is its `pip freeze` (224 pins). `--music-check`
runs every read-only probe against the real database and computes one viz blob.

**Deploy is unchanged**: `./gradlew :desktop:stageJar && sudo rc-service damage restart`
(the media endpoint binds with the service); the APK by `:phone:stageApk` from the setup page.

## Ops crib

- `sudo rc-service damage start|stop|status` — the PC side. **Stop it before any
  `./gradlew :desktop:run` dev session** (one set of ports; and a dev session in `ble`/`remote`
  mode is a second central/driver); start it again after.
- Logs: `~/.damage/damage.log` (the service — the standby narration lives here),
  `~/.damage/journal.jsonl` (flush journal, only while a PC stack drives),
  phone `adb logcat -s damage` + the on-phone status line.
- Views while headless: **the phone screen or the phone replica are the live views now**
  (`http://aphone:7403/?token=…`); the PC replica (`http://beardos:7403/…`) shows the standby
  status, and a live mirror only while the PC's own BLE stack drives (the token is in
  `~/.damage/config.json`).
- **Deploying a new PC build:** `./gradlew :desktop:stageJar && sudo rc-service damage restart`
  — since §19 this **never touches the display**: the PC claims nothing, so a service restart
  is invisible on glass. (The stable path `~/.damage/damage.jar` is what the service runs — a
  broken tree never changes the daily driver until you stage it.)
- **Deploying a new APK:** bump versionCode/Name, `./gradlew :phone:stageApk`, download from
  the setup page, install over; `MY_PACKAGE_REPLACED` restarts the phone service by itself.
- Tmux/knobs: `~/.damage/config.json` (`tmuxHosts` — add slappy back when it is actually on —
  `tmuxQuickKeys`, `tmuxSnippets`, `tmuxWaitPatterns`); on-glass settings live in
  Settings → Tmux. Since §28 the wait alert works for ANY pane — before it, a session that had
  not filled its screen (one created from the glasses, a short command) never alerted and its
  sessions lens showed no last line.
- **Hand-editing `config.json`** is safe since §28: an unreadable file (a stray comma) is left
  exactly as it is and the service runs on defaults for that start with a loud log line — before
  that it was REPLACED with defaults, credentials and tmux hosts included. Fix the file and
  restart. Every HOST-need window on the phone (Tmux, Files, Torrents, Reader content,
  Music) rides this PC service (or `--host-only`) — content port :7401, plus the media endpoint
  :7404 for Music.
- If the pair "scans forever" while the phone says Connected: the stale-ACL recovery is still
  **toggle phone Bluetooth** (the scan now fails loudly and rides the ON edge back in).
- **Torrents (2026-09-01, `TORRENTS.md`):** the window reads qBittorrent's Web API on
  `http://127.0.0.1:8090` (loopback only; qBittorrent's `LocalHostAuth=false` means no
  credentials in our path) and the TorrentLeech account from `~/.damage/config.json`
  (`torrentleechUser` / `torrentleechPass`; `qbtUrl`, and `qbtUser`/`qbtPass` only if localhost
  auth is ever turned back on). The Web UI came with the `webui` USE flag rebuild
  (`/etc/portage/package.use/60-qbittorrent`); qBittorrent's own Web UI login (`admin` + the
  password in `~/.config/qBittorrent/webui-credentials.txt`) exists only because qBittorrent
  refuses to start the Web UI without one. ⚠ qBittorrent is the GUI app in the X session, not a
  service: if it is not running, the window says `qBittorrent unreachable Ns` and everything else
  keeps working. The tracker session cookie lives in `~/.damage/tl-cookies.json`; the
  announced-set (which downloads were already announced as done) in `~/.damage/torrents.json`.

## What was verified vs what awaits glass

The §13.2 hardware pass ran 2026-08-31 (phone first light + the three configurations). The §19
re-shape: test-pinned (`SyncTest` ×6) AND seen live — with the sync-carrying APK installed the
PC logged `sync-host: peer attached to the sync channel`, the store migrated to the stamped v2
schema, and a phone-side record (`window.tmux`) crossed and applied store-direct while the PC
shell was in standby. **Still awaiting a deliberate glass test**: a real standby engagement
(stop the APK at the desk → the PC BLE-drives within ~10 s → restart the APK → handback) and
the sync feel across a driver swap (a book position following the swap). Current staged APK:
**0.27** (Files, the chrome tweaks, Torrents, the keyboard, Music + its three reviews, the
2026-09-03 Now Playing root + the quiet-stream notice + the stable-identity output restore —
`HANDOFF.md` §24.4 — that day's whole-codebase review, §25, and **Games · Hold'em** with the
shell's switcher-resume / Main-root rule and all three review cycles, §26, **and the 2026-09-05
whole-codebase review, §27** — the Music Mode card, the chrome font ladder, the Games cash-out
and the truth-oracle gate); **0.16 is the last build observed INSTALLED** (2026-09-01 — the
phone speaks the files channel), so **0.27 is the one to install** — and the 2026-09-04-late
review (§28) still needs staging as 0.28.
✅ Both sides are deployed as of 2026-09-05: `:phone:stageApk` put 0.27 on the setup page and
`./gradlew :desktop:stageJar && sudo rc-service damage restart` put the service on the review
build (it never touches the display; the PC does not claim). The log came up `standby up (§19)`
with the phone reattached to the music, files, torrents and tmux channels.

**Games (2026-09-04, `HOLDEM.md`) needs nothing from ops.** ✅ Live on the PC service since
2026-09-04 13:07. It is pure Kotlin with no host, no
provider and no channel, so it works identically on the phone alone, on the PC alone and across
the seam; its whole state is in the shell's own store (`window.games`, `window.games.world`,
`window.games.bankroll`, `window.games.table`, `window.games.char.<id>`) and syncs like every
other window's. Nothing to configure in `config.json`. ⚠ Its four-height layouts and its card
art have **never been seen on the glasses** — only on the simulator at true 1×.
