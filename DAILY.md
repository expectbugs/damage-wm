# The daily driver — G2 all day from the phone, powered by beardos

**The default configuration (`DESIGN.md` §10.1 row 1 + the §8.1 arbitration), running the way
G2CC ran: a long-lived PC process + the phone APK.** Set up 2026-08-31; this file is the ops
crib for it.

## The moving parts

| piece | job | kept alive by |
|---|---|---|
| **phone APK** (Target = glasses) | owns the BLE radio, always; runs the FALLBACK shell; serves the seam :7402 + replica :7403 | foreground service + wakelock + Doze exemption + `BootReceiver` (reboot/update) |
| **beardos `damage` service** | the PC shell in `auto` — claims the phone's transport over the seam and DRIVES THROUGH IT ("powered by this PC"); PC-direct BLE when the phone app is down; serves books + tmux :7401 and the PC replica :7403 | OpenRC `/etc/init.d/damage` (supervise-daemon, enabled at `default`, headless `--no-preview`) |

**Who drives when** (all automatic, no timeouts, event-driven):

- Both up, anywhere the phone has internet → **PC shell through the phone** (the full-power row —
  even away from home, over Tailscale/LTE).
- PC unreachable or its internet path dies silently → the seam heartbeat notices in **~20 s** and
  the **phone shell resumes on its own**; the PC re-claims when it is back. **Since the
  session-outlives-the-driver rework (both ends ≥ 0.6): every one of these transitions is a
  REPAINT, not a teardown — the glasses session and its lease run continuously; drivers come
  and go with one keyframe each.** PC deploys are the same: the phone adopts during the gap.
- Phone app down at the desk → the PC falls to **PC-direct BLE**.
- Reboot either machine → both sides restart themselves and re-arbitrate.

## One-time setup — ✅ COMPLETED 2026-08-31 (kept for a re-pair or a fresh phone)

Done that day: v0.4 sideloaded + all grants, phone first light passed on the first try, the
service started and claimed over the seam. If it ever needs redoing: (1) sideload from the
setup page, grant Bluetooth ×2 + notifications + the battery exemption; (2) 🔴 keep the G2CC
bridge app Disconnected (a second central); (3) phone first light with NOTHING on beardos
holding the pair (`sudo rc-service damage stop`), flip Target → glasses; (4) `sudo rc-service
damage start` → "PC shell driving". ⚠ Keep the PHONE APK current with the PC (setup page —
0.7 now): mixed versions still work but old phones tear the session on handover instead of
adopting it.

## Ops crib

- `sudo rc-service damage start|stop|status` — the PC side. **Stop it before any
  `./gradlew :desktop:run` dev session** (one central, one set of ports); start it again after.
- Logs: `~/.damage/damage.log` (the service), `~/.damage/journal.jsonl` (flush journal),
  phone `adb logcat -s damage` + the on-phone status line.
- Views while headless: the phone screen, or the browser replica —
  PC `http://beardos:7403/?token=…` · phone `http://aphone:7403/?token=…` (the token is in
  `~/.damage/config.json`; the desktop prints the full URL at start).
- **Deploying a new PC build:** `./gradlew :desktop:stageJar && sudo rc-service damage restart`
  (the stable path `~/.damage/damage.jar` is what the service runs — a broken tree never
  changes the daily driver until you stage it).
- **Deploying a new APK:** bump versionCode/Name, `./gradlew :phone:stageApk`, download from
  the setup page, install over; `MY_PACKAGE_REPLACED` restarts the phone service by itself.
- Tmux/knobs: `~/.damage/config.json` (`tmuxHosts` — add slappy back when it is actually on —
  `tmuxQuickKeys`, `tmuxSnippets`, `tmuxWaitPatterns`); on-glass settings live in
  Settings → Tmux. The phone's tmux window needs this PC service (or `--host-only`) up.
- If the pair "scans forever" while the phone says Connected: the stale-ACL recovery is still
  **toggle phone Bluetooth** (the scan now fails loudly and rides the ON edge back in).

## What was verified vs what awaits glass

Headless service mode ran the whole stack on the staged jar (sim transport, replica answering,
zero errors) — **measured on this machine**. The all-day arbitration transitions are
sim/fake-verified (§8) plus the seam heartbeat's 3 tests; the §13.2 hardware pass — phone-radio
first light and the three configurations on the real pair — is still the open mission step and
step 3 above IS it.
