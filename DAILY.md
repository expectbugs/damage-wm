# The daily driver — G2 all day from the phone, fed by beardos

The default configuration (`DESIGN.md` §10.1 row 1 as read by `HANDOFF.md` §19, 2026-08-31): **the PHONE
SHELL drives, always; the PC is the DATA PROVIDER.** This file is the ops crib.

## The moving parts

| piece | job | kept alive by |
|---|---|---|
| **phone APK** (Target = glasses) | THE driver — owns the BLE radio and runs the shell; serves the seam :7402 (status probe + explicit dev claims) and the replica :7403 | foreground service + wakelock + Doze exemption + `BootReceiver` (reboot/update) |
| **beardos `damage` service** | data host + standby: books, tmux, state sync, the window channel :7401 (Files · Torrents · Music · Feed, theme icons), the media endpoint :7404, the PC replica :7403, the setup page :7300 (`/setup` + `/damage-apk`, G2CC's URL and token kept); probes the phone every 5 s and drives PC-direct BLE ONLY while the APK is away for 30 s AND both arms advertise to its adapter (`HANDOFF.md` §47) | OpenRC `/etc/init.d/damage` (supervise-daemon, `default` runlevel, `--no-preview`, `auto` = standby; after `postgresql-17` / `qdrant` / `qbittorrent`, `~/.local/bin` on PATH for the Ask lane's `claude`, `--enable-native-access` for the Postgres socket driver — 2026-09-10) |
| **beardos `qbittorrent` service** | the Torrents backend: `qbittorrent-nox` on Adam's own profile (`~/.config/qBittorrent`), Web API `127.0.0.1:8090` | OpenRC `qbittorrent` (Gentoo's script + `/etc/conf.d/qbittorrent`, `default` runlevel, 2026-09-10) |

**Who drives when** (automatic, event-driven, pacing not timeouts): APK up → the phone shell drives — home,
work, LTE; PC reachability decides how CAPABLE it is (live library, tmux, sync), never who drives, and
gestures and frames never leave the phone. PC unreachable → nothing changes on glass; content degrades to
the caches, staleness is said (`PC gone/Nm`), sync resumes on reconnect (last-write-wins both ways). APK down
or Target = sim for ~2 probes (~10 s) → the PC starts its own BLE stack; when the APK returns the PC stops it
(the handback: one repaint). Reboot either machine → both restart themselves. **State sync:** reading
positions, settings and every window blob flow between phone and PC whenever both are up — most recent wins,
per key.

## One-time setup — ✅ COMPLETED 2026-08-31 (kept for a re-pair or a fresh phone)

(1) Sideload from the setup page; grant Bluetooth ×2 + notifications + the battery exemption. (2) Keep the
G2CC bridge app Disconnected (a second central). (3) Phone first light with NOTHING on beardos holding the
pair (`sudo rc-service damage stop`), flip Target → glasses. (4) `sudo rc-service damage start` → the log
says `standby up (§19)`. Keep the APK current with the PC (`REMINDER.md` says which build is installed and
staged): a pre-0.15 APK cannot be status-probed and a pre-0.10 one carries no sync client.

## Music (2026-09-02, `MUSIC.md`) — the phone plays, the PC serves

| piece | job |
|---|---|
| **beardos `damage` service** | the LIBRARY: Postgres `g2cc` over the Unix socket (peer auth), Qdrant `g2cc_music`, the transcode cache (`~/.g2cc/media-cache` read in place as the Standard profile, `~/.damage/media-cache/<profile>/` for the rest), art + viz, the resolver lanes (`claude -p` for Ask, `audio/enrich/embed_query.py` on G2CC's venv), yt-dlp, enrichment; the **media endpoint :7404** (`GET /track/<id>?token=&profile=`, Range-capable) |
| **phone APK** | the PLAYER: ExoPlayer + a media session (bud taps: single = play/pause, double = next, triple = previous, from anywhere), catalog/art/viz/lyrics cached on disk, the next 3 tracks prefetched, hold-my-volume, boost, sleep, Spotify as the fallback |

**One-time phone grants (0.23):** open the app → `music access` on the strip → allow *Damage music*
notification access (Spotify's session and the OS volume-lowered notice ride on it). No RECORD_AUDIO is
asked, ever. Then Settings → Music → Output (Auto follows the buds; the speaker plays only when chosen).

🔊 **Playing but silent? Check the phone's media volume first** — the 2026-09-02 report was the level at 8 %
with the whole chain working (`HANDOFF.md` §24.4). Now Playing shows the level HOT at or below 10 %, a start
that quiet raises a notice, and **scrolling on Now Playing IS the volume**. Damage never sets the system level.

**Checks owed on the phone** (`HANDOFF.md` §24.1, `MUSIC.md` §12): the first real hearing-limiter trigger
(every volume change and system notification is logged, so `MusicListener.rules` can be corrected from
evidence); the Spotify cold start; the Bluetooth lyric offset (Lyrics → scroll nudges ±50 ms per output); the
visualizer rate on glass; the buds' taps reaching the media3 session (it lives in the shell service, not a
`MediaSessionService` — check logcat first).

**The venv:** enrichment runs on G2CC's `/home/user/G2CC/audio/venv` until Damage owns one;
`audio/requirements-frozen.txt` is its `pip freeze` (224 pins). `--music-check` runs every read-only probe
against the real database and computes one viz blob. **Adaptive playlists (2026-09-10, `MUSIC.md` §9.8):**
the 25 rule-managed playlists refresh at service start, after a grab's enrichment and after a rescan (the
window's Rescan row); the log's `music-adaptive` lines say what moved; `--music-check` shows a refresh
without writing a row.

## Feed + comics (2026-09-09, `FEED.md`) — one engine on both hosts

- **Nothing to configure for day one.** The service fetches the five sources (Reddit popular, Slashdot, xkcd,
  SMBC, the 8-Bit Theater archive) into `~/.damage/feed/` and serves the phone over the content port. Own
  sources: `feedSources` in `~/.damage/config.json` (copy `SourceCfg.DEFAULTS` and add; `kind: "rss"` + `url`
  [+ `"image": true` for a webcomic]); `feedUserAgent` is the one string every fetch carries. No credentials.
- **The phone fetches for itself** after the `PC loss` threshold (Settings → Feed, default 1 min) and says
  `phone engine` in Main's row; it does NOT switch back by itself — the root menu's `Back to PC` row does.
- **Reddit is paced to one request a minute** (bursts get 429s): `reddit.com rate-limited · retry N s` on the
  lens; comments opened within a minute of the feed's own fetch wait out the pace and say so on the title
  (`FEED.md` §8.3 item 1 — the first polish item).
- **Slashdot comments come from the story page** (the rest by id through `ajax.pl`). A story showing `no
  comments yet` while the feed counts some came without its tree — not understood (`FEED.md` §8.2 item 3,
  grade S); note the time and the story.
- **Comics:** the bar under a strip (`next · prev · random · first · latest · menu`) — a notch up from the top
  lands on it, a tap presses; xkcd flips through its whole archive by number. `Settings → Feed → Comic
  levels` (16 / 8 / 4) is the byte knob for 8-Bit Theater.
- **Checks:** `bin/damage --feed-check` (offline fixtures); `bin/damage --feed-check live` (one paced fetch
  per configured source, read-only, a temp directory deleted after — about a minute).

## Ops crib

- `sudo rc-service damage start|stop|status` — the PC side. **Stop it before any `./gradlew :desktop:run` dev
  session** (one set of ports; `ble`/`remote` dev modes are a second central/driver); start it again after.
  `qbittorrent` is the other Damage-side service; both in `default` (`rc-update show default`). **G2CC's
  server is RETIRED** (`HANDOFF.md` §44) — never start it by hand: it would take :7300 from the setup page
  and rewrite the adaptive playlists. ⚠ :7401 is a framed channel, not HTTP — an HTTP probe ends a session
  loudly (`frame length … out of range`); probe :7403 for liveness.
- Logs: `~/.damage/damage.log` (the service; the standby narration), `~/.damage/journal.jsonl` (the PC's flush
  journal, only while a PC stack drives), phone `adb logcat -s damage` + the on-phone status line.
- **The phone's journal, no adb** (`HANDOFF.md` §32): `curl -s 'http://aphone:7403/journal?token=…' | python3
  tools/journal_report.py -` (`&tail=2000000` for the last ~2 MB) — the ack curve by hour (with the connection
  parameters in force per hour since §47: `105/4` is the slow set, `15/1` the fast) and radio path, CPU
  per flush, the cache's account, **time to first visible change per gesture** (the number a window is judged
  by), the `link`/`keeper` notes. The daily driver's real curve; the PC journal's is the standby's.
- **The phone's log, no adb** (APK 0.41+, §42): `curl -s 'http://aphone:7403/log?token=…&tail=400'` (the last
  4,000 lines). The keeper's transitions (`start failed: …`, `link ended: …`) are also `keeper` journal notes.
- **The glasses show NOTHING and the temples do nothing** (`HANDOFF.md` §36): the firmware's Silent Mode is
  probably on; the shell handles it (a phone notice, frames stop, the lease is dropped, the page traffic stops)
  and the both-temple long-press wakes everything as a session REBUILD (§38: ~20 s of blank, then the
  keyframe). Not back within a minute (2026-09-09 15:06, §42.3 — cause not yet named): Target → SIM in the APK,
  then Target → glasses; temples dead: phone Bluetooth off, both-temple long-press ("Silent Mode Off"),
  Bluetooth on. Then read the journal's `silent`, `restart`, `keeper`, `event` notes and `/log`.
- **Global rows** (`HANDOFF.md` §40–§41): `Slide frames` (default `auto`); `Cached text` (default `off` — on, it
  uploads fonts and icons in idle chunks (`atlas` notes) and ships text and icons on EVERY plane as cached
  draws; a refused cache write switches it off for the session; every flush line carries `cached`/`cacheMiss`);
  `Depth` moves everything, the selection bar one notch nearer, each app's `Depth` row (`global` by default)
  only that app's content (§41.2); `Slide fill` `auto` (default) blanks-then-fills a big strip only while the
  cache is off, `whole` never, `split` always (§41.9). The watchdog rebuilds a session quiet for ~22 s
  (`watchdog` notes); every session start writes a `build` note, every link edge a `link` note.
- **Drive the glasses from the PC** (`HANDOFF.md` §33): `python3 tools/glassdrive.py aphone TOKEN --pace 2.5
  double wait:3 snap:/tmp/a.png down down tap …` sends ring gestures through the phone's replica and saves
  both lenses at 1× — snap before every tap (§29.2: one step per snap near an irreversible row; never scroll
  in Music's root).
- Remote tmux hosts ride one multiplexed ssh connection (`~/.damage/ssh-*` control sockets, 60 s persist) — a
  stale socket there is safe to delete.
- Views while headless: the phone screen or replica (`http://aphone:7403/?token=…`); the PC replica
  (`http://beardos:7403/…`) shows the standby status, a live mirror only while the PC's own BLE stack drives.
- **Deploying a new PC build:** `./gradlew :desktop:stageJar && sudo rc-service damage restart` — never touches
  the display (the PC claims nothing). The service runs the stable `~/.damage/damage.jar`; never replace the
  jar under a running instance.
- **Deploying a new APK:** bump versionCode/Name, `./gradlew :phone:stageApk`, download from the setup page
  (`http://beardos:7300/setup` on the tailnet, `SetupServer.kt`), install over; `MY_PACKAGE_REPLACED` restarts
  the phone service by itself.
- Tmux knobs: `~/.damage/config.json` (`tmuxHosts` — add slappy back when it is actually on — `tmuxQuickKeys`,
  `tmuxSnippets`, `tmuxWaitPatterns`); on-glass settings in Settings → Tmux. The wait alert works for ANY pane,
  a TAP on it opens the session's live view, a quiet host says so on EVERY level and in Main's row (§28–§30).
- **Hand-editing `config.json`** is safe (§28): an unreadable file (a stray comma) is left as it is and the
  service runs on defaults for that start with a loud log line; fix and restart. Every HOST-need window on the
  phone (Tmux, Files, Torrents, Reader content, Music, Feed) rides this service — :7401, plus :7404 for Music.
- "Scans forever" while the phone says Connected → the stale-ACL recovery is still **toggle phone Bluetooth**
  (the scan fails loudly and rides the ON edge back in).
- **`LINK SLOW` in the status cell** (§47): the glasses moved the link to their idle parameters (105 ms /
  latency 4); the APK is re-asking for the fast set every 5 s per arm — the `link` notes count the re-asks.
  If it never clears, the firmware keeps winning: that is the ask to Babcock (gate the idle request on the
  lease). Global `Link` = `balanced` is the experiment for the ~50-minute rebuilds. **`atlas full`**: a face
  stayed pixels even after evicting what the window is not drawing — the cache is genuinely full.
- **The phone ↔ PC path** should be DIRECT (`tailscale ping aphone` → "via <ip:port>", ~30 ms). "via DERP"
  means the bypass is not in force (`sudo /etc/local.d/tailscale-bypass.start`, then `sudo rc-service
  tailscale restart`) or the phone's network blocks it; either way the windows still work, slower.
- **Torrents (`TORRENTS.md`):** qBittorrent's Web API at `http://127.0.0.1:8090` (loopback only;
  `LocalHostAuth=false` = no credentials in our path) and the TorrentLeech account from `~/.damage/config.json`
  (`torrentleechUser` / `torrentleechPass`; `qbtUrl`, and `qbtUser`/`qbtPass` only if localhost auth is ever
  turned back on). qBittorrent's own Web UI login is `admin` + `~/.config/qBittorrent/webui-credentials.txt`.
  Down, the window says `qBittorrent unreachable Ns`. ⚠ The GUI shares the profile: launching `qbittorrent`
  while the service runs hands over to it and exits — `sudo rc-service qbittorrent stop` first, or use the Web
  UI. The tracker cookie is `~/.damage/tl-cookies.json`; the announced set `~/.damage/torrents.json`.

## What was verified vs what awaits glass

The §13.2 hardware pass ran 2026-08-31 (phone first light + the three configurations); the §19 re-shape is
test-pinned (`SyncTest` ×6) and seen live (`sync-host: peer attached to the sync channel`; a phone-side
`window.tmux` record applied store-direct while the PC shell was in standby). **Still awaiting a deliberate
glass test:** a real standby engagement (stop the APK at the desk → the PC BLE-drives within ~10 s → restart
the APK → handback) and the sync feel across a driver swap (a book position following the swap). **Games
(`HOLDEM.md`) needs nothing from ops** — no host, no provider, no channel; its state is the shell's own store
(`window.games`, `window.games.world`, `window.games.bankroll`, `window.games.table`,
`window.games.char.<id>`) and syncs like every other window's. ⚠ Its layouts and card art have never been seen
on the glasses — only on the simulator at true 1×.
