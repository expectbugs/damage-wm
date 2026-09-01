# The daily driver — G2 all day from the phone, fed by beardos

**The default configuration (`DESIGN.md` §10.1 row 1, corrected reading — `HANDOFF.md` §19):
the PHONE SHELL is the primary driver, always; the PC is the DATA PROVIDER.** Set up
2026-08-31, re-shaped the same night when §8.1's "best case = home PC" was clarified to mean
data availability, not the driver. This file is the ops crib.

## The moving parts

| piece | job | kept alive by |
|---|---|---|
| **phone APK** (Target = glasses) | **THE driver** — owns the BLE radio and runs the shell, always; serves the seam :7402 (status probe + explicit dev claims) + replica :7403 | foreground service + wakelock + Doze exemption + `BootReceiver` (reboot/update) |
| **beardos `damage` service** | **the data host + standby**: books + tmux + STATE SYNC + the window channel (the phone's FILES window: listings, ops, viewers, blobs) + theme icons on :7401, the PC replica :7403; probes the phone every 5 s and starts a PC-direct BLE stack ONLY while the APK is not available, handing the radio back the moment it returns | OpenRC `/etc/init.d/damage` (supervise-daemon, enabled at `default`, headless `--no-preview`, mode `auto` = standby) |

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
phone keeps driving. ⚠ Keep the PHONE APK current with the PC (setup page — **0.17 is the
staged build: Files + theme icons + the sync client + the 2026-09-01 review fixes + the
chrome tweaks; 0.16 is installed**). Why old
APKs matter: a pre-0.15 APK cannot be status-probed (the PC conservatively stays out — fine)
and a pre-0.10 one carries no sync client, so state does not flow until it is updated.
**Once 0.16 is INSTALLED, remove Reader's transitional legacy-offsets dual-write**
(`REMINDER.md` step 1 — the fields are marked in `ReaderWindow`).

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
  Settings → Tmux. Every HOST-need window on the phone (Tmux, Files, Reader content) rides
  this PC service (or `--host-only`) — content port :7401.
- If the pair "scans forever" while the phone says Connected: the stale-ACL recovery is still
  **toggle phone Bluetooth** (the scan now fails loudly and rides the ON edge back in).

## What was verified vs what awaits glass

The §13.2 hardware pass ran 2026-08-31 (phone first light + the three configurations). The §19
re-shape: test-pinned (`SyncTest` ×6) AND seen live — with the sync-carrying APK installed the
PC logged `sync-host: peer attached to the sync channel`, the store migrated to the stamped v2
schema, and a phone-side record (`window.tmux`) crossed and applied store-direct while the PC
shell was in standby. **Still awaiting a deliberate glass test**: a real standby engagement
(stop the APK at the desk → the PC BLE-drives within ~10 s → restart the APK → handback) and
the sync feel across a driver swap (a book position following the swap). Current staged APK: **0.17** (0.16 + the chrome tweaks); **0.16 is INSTALLED** (observed
2026-09-01 — the phone speaks the files channel).
