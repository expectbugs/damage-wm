# The daily driver — G2 all day from the phone, fed by beardos

The default configuration (`DESIGN.md` §10.1 row 1 as read by `HANDOFF.md` §19, 2026-08-31): **the PHONE
SHELL drives, always; the PC is the DATA PROVIDER.** This file is the ops crib. **`aphone` has not resolved on
beardos since 2026-09-21 (DNS moved from Tailscale to the VPN): wherever a line below says `aphone`, use the
phone's Tailscale address (`tailscale status | grep aphone`).**

**As built.** `REPLAN.md` §14 (2026-11-12) rules the PC the owner of every window's state, with the phone keeping a
working copy, and PC-direct BLE a manual choice, never automatic. The second is in the code since the alert watcher, W
(APK 0.66 and its PC deploy, `HANDOFF.md` §79): the service never drives by itself. The first arrives at step S1; until
then the two-way sync below is what runs.

## The moving parts

| piece | job | kept alive by |
|---|---|---|
| **phone APK** (Target = glasses) | THE driver — owns the BLE radio and runs the shell; serves the seam :7402 (status probe + explicit dev claims) and the replica :7403 | foreground service + wakelock + Doze exemption + `BootReceiver` (reboot/update) |
| **beardos `damage` service** | data host: books, tmux, state sync, the window channel :7401 (Files · Torrents · Music · Feed · `bastion`, theme icons), the media endpoint :7404, the PC replica :7403, the setup page :7300 (`/setup` + `/damage-apk`, G2CC's URL and token kept); since 0.66's deploy also the bus alert watcher (the hub read every 10 s when `haUrl` is set), `damage.phone` once a minute, the nightly copy at `backupAt` into `backupDir`, and the reach gate on every listener; probes the phone's seam every 5 s for its status line and never drives on its own — PC-direct BLE only by hand (`drive=ble` / `drive=auto`, below); a busy port is retried every 5 s; the JVM exits on its first OutOfMemoryError and supervise-daemon restarts it (`-Xmx2g`), and a stop that hangs is ended with SIGKILL after 30 s (`retry`, §72) | OpenRC `/etc/init.d/damage` (supervise-daemon, `default` runlevel, `--no-preview`, `auto` = standby; after `postgresql-17` / `qdrant` / `qbittorrent`, `~/.local/bin` on PATH for the Ask lane's `claude`, `--enable-native-access` for the Postgres socket driver — 2026-09-10) |
| **beardos `qbittorrent` service** | the Torrents backend: `qbittorrent-nox` on Adam's own profile (`~/.config/qBittorrent`), Web API `127.0.0.1:8090` | OpenRC `qbittorrent` (Gentoo's script + `/etc/conf.d/qbittorrent`, `default` runlevel, 2026-09-10) |

**Who drives when** (automatic, event-driven, pacing not timeouts): APK up → the phone shell drives — home,
work, LTE; PC reachability decides how CAPABLE it is (live library, tmux, sync), never who drives, and
gestures and frames never leave the phone. PC unreachable → nothing changes on glass; content degrades to
the caches, staleness is said (`PC gone/Nm`), sync resumes on reconnect (last-write-wins both ways); the bus
alerts say "home unreachable" after the phone's set minutes. APK unreachable → **since 0.66's PC deploy nothing takes
the glasses by itself**: the status line says "phone unreachable … PC-direct BLE is manual (drive=ble)". (Before it,
`b833713`'s standby claimed after six probes with both arms seen; beardos's hci0 being rfkill-blocked held it.) The
phone's shelf answers from its cache at once while
the PC is out of reach. Reboot either machine → both restart themselves. **State sync:** reading
positions, settings and every window blob flow between phone and PC whenever both are up — most recent wins,
per key.

## One-time setup (for a re-pair or a fresh phone; done 2026-08-31)

(1) Sideload from the setup page; grant Bluetooth ×2 + notifications + the battery exemption. (2) Keep the
G2CC bridge app Disconnected (a second central). (3) Phone first light with NOTHING on beardos holding the
pair (`sudo rc-service damage stop`), flip Target → glasses. (4) `sudo rc-service damage start` → the log
says `data host up (auto: the phone drives; …)` (0.66's jar; `standby up (§19)` before it). Keep the APK current with the PC (`REMINDER.md` says which build is installed and
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
  `phone engine` in Main's row; it does NOT switch back by itself — the root menu's `Back to PC` row does. (As built;
  from step S1 the phone shows the PC's last state with its age instead — `CONTRACT.md` §14 (a) row 1.)
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
  From 0.64's jar a shell or `--host-only` start while another process holds the data directory exits **4** with
  one stderr line naming `~/.damage/instance.lock`; under the supervisor a refused start repeats every 5 s (one
  `damage.log` line per 5 s) for as long as the other process lives. The start banner prints `?token=…` and names
  `config.json`. With the service stopped, a dev `:desktop:run` on the real data directory is a start all the same:
  it renames the service's journal past 64 MiB and picks up the service's pending uncaught-error record.
  `qbittorrent` is the other Damage-side service; both in `default` (`rc-update show default`). **G2CC's
  server is RETIRED** (`HANDOFF.md` §44) — never start it by hand: it would take :7300 from the setup page
  and rewrite the adaptive playlists. ⚠ :7401 is a framed channel, not HTTP — an HTTP probe ends a session
  loudly (`frame length … out of range`); probe :7403 for liveness.
- Logs: `~/.damage/damage.log` (the service: the data host's narration; from 0.66 the watcher under the tag
  `bastion`, `damage.phone` under `presence`, refused connections under `reach`), `~/.damage/journal.jsonl` (the PC's
  flush journal while a PC stack drives; from 0.66 also the `reach` and `presence` notes), phone `adb logcat | grep ' damage/'` (the tags are `damage/<tag>`) + the on-phone status line.
  **From 0.64 (both hosts) the journal is renamed at a process start once past 64 MiB** to
  `journal-YYYYMMDD-HHMMSS.jsonl` beside it, and the fresh live file starts with a `journal` note naming the archive;
  nothing is deleted. The phone serves the archives (`/journals`, and `/journal?…&name=` for one);
  `python3 tools/journal_pull.py --live` copies them and the live file to `~/.damage/phone-journal/` (it keeps no
  `journal-live-…` copy whose lines an archive there holds, and names the archive) and stops at a `/journals` 404
  (0.63). **Pull before an uninstall.** `journal_report.py` reads a pipe or `<(…)` alone or as `-` (refused among
  several, exit 2); read a journal that spans the upgrade to 0.64 with `--since` — its `cached text:` line reads
  "refusals by reason" and the `cache count:` line names "short on staged strips alone".
- **The phone's journal, no adb** (`HANDOFF.md` §32): `curl -s 'http://aphone:7403/journal?token=…&tail=2000000' | python3
  tools/journal_report.py -` (the lines in the last ~2 MB). **An APK before 0.61 reads the whole file into memory
  without `&tail=` and its process ends (`HANDOFF.md` §71): drop `&tail=` only on 0.61+.** It shows the ack curve by hour (with the connection
  parameters in force per hour since §47: `105/4` is the slow set, `15/1` the fast) and radio path, CPU
  per flush, the cache's account, **time to first visible change per gesture** (the number a window is judged
  by), the `link`/`keeper` notes. The daily driver's real curve; the PC journal's is a manual PC stack's. From 0.64 it also
  prints the glasses' battery by state (lit, the shell's silent, rest, the firmware's Silent Mode) and a candidate
  block, "panel transfers against frames counted"; the notes list shows `wear`, `case`, `sid`, `rest`, `lowbatt`.
- **The phone's log, no adb** (APK 0.41+, §42): `curl -s 'http://aphone:7403/log?token=…&tail=400'` (the last
  400 of the 4,000 lines kept; without `&tail=`, all of them). The keeper's transitions (`start failed: …`, `link ended: …`) are also `keeper` journal notes.
- **The glasses show NOTHING and the temples do nothing** (`HANDOFF.md` §36): the firmware's Silent Mode is
  probably on; the shell handles it (a phone notice, frames stop, the lease is dropped, the page traffic stops)
  and the both-temple long-press wakes everything as a session REBUILD (§38: ~20 s of blank, then the
  keyframe). Not back within a minute (2026-09-09 15:06, §42.3 — cause not yet named): Target → SIM in the APK,
  then Target → glasses; temples dead: phone Bluetooth off, both-temple long-press ("Silent Mode Off"),
  Bluetooth on. Then read the journal's `silent`, `restart`, `keeper`, `event` notes and `/log`.
- **Global rows** (`HANDOFF.md` §40–§41, §66): `Page staging` (default `on` — with cached text on and a Damage build, the
  focused document's next strips go to the cache's reserve and a notch is one draw; `stage` notes say what was staged,
  proven or dropped; off for the A/B); `Slide frames` (default `auto`); `Cached text` (default `off` — on, it
  uploads fonts and icons in idle chunks (`atlas` notes) and ships text and icons on EVERY plane as cached
  draws; a refused cache write switches it off for the session; every flush line carries `cached`/`cacheMiss`;
  since 0.63 (`HANDOFF.md` §73) a face is uploaded with the characters it has drawn and gains the rest as they
  appear — a first-seen character is pixels for one flush, the `atlas` notes say "N face(s) gained glyphs", the
  flush line's `unpacked` counts what the host drew meanwhile — and the row also sets the layout metric, so
  switching it re-wraps every page);
  `Depth` moves everything, the selection bar one notch nearer, each app's `Depth` row (`global` by default)
  only that app's content (§41.2); `Slide fill` `auto` (default) blanks-then-fills a big strip only while the
  cache is off, `whole` never, `split` always (§41.9). The watchdog rebuilds a session quiet for ~22 s
  (`watchdog` notes); every session start writes a `build` note, every link edge a `link` note.
- **Drive the glasses from the PC** (`HANDOFF.md` §33): `python3 tools/glassdrive.py aphone TOKEN --pace 2.5
  double wait:3 snap:/tmp/a.png down down tap …` sends ring gestures through the phone's replica and saves
  both lenses at 1× — snap before every tap (§29.2: one step per snap near an irreversible row; never scroll
  in Music's root).
- **Phase 0 probes** (APK 0.45+, `HANDOFF.md` §49; dev only — the shell never sends them): `python3
  tools/glassdrive.py aphone TOKEN probe:NAME=VALUE`. `diag=show` (blanked the display on a5d1c31 until a rebuild, `HANDOFF.md` §54.8, and has not been run on the fork's build — not without Adam) puts the firmware's diagnostic line at the
  top-left of both lenses (`OK w…us p…us f13/20/27=…k`: flags, the last message's worker and copy time, free KiB
  per heap arena) — `diag=hide` removes it. `logger=on` streams the glasses' own log lines into the journal as
  `glasslog` notes (RAM-only on the glasses; re-sent after every session start; `logger=off` stops it) — read
  with `journal_report.py - --since … --glasslog`. On a Damage build (`FIRMWARE.md` §3/§4): `telemetry=read`,
  `cache=info`, `flags=clear|probe|0xNNNN`, `selftest=begin|end|step:HEX`, and since 0.51 `cachesize=KiB` (op 5) and
  `selftest=live:HEX` (a vector's cache write to the live cache; `glassdrive.py selftest:FILE` drives a whole vector:
  its live writes drop the shell's atlas for the session and nothing presents during a step, so a quiet minute; it
  exits non-zero on a step that differs — read the status, not only the lines; `HANDOFF.md` §64.4, §65).
  From 0.64: `cache=miscount`, `wear=query` and `rest=on|off` (each his word only on glass), and on the SIM target
  only `sim=battery=N|charging=on|off|worn=yes|no|wearswitch=on|off|case=docked|out`. The new notes: `wear` (every
  sid-0x10 frame; op 7's answer; the READ's field 10 and field 14's presence), `case` (sid 0x81), `sid` (a service
  with no handler, once a session), `lowbatt` (where the low-battery notice went), `rest` and `power`.
  `phy=2m` asks the radio for 2M PHY (`link` notes carry the
  answer; `phy=1m` goes back — on the upstream build, 2026-09-14, the glasses refused 2M; the fork's Phase 2 build offers it and the APK asks at every session start, `PHY after the link request: 2M/2M`). `telemetry=read` and `flags=probe|clear` answer only on a Damage firmware build
  (the installed one since 2026-09-16; an upstream build ignores them and the `probe` note says so). The glasses' battery changes are
  `battery` notes (the report's battery section).
- **The timed rest hour** (APK 0.64; his word on glass): start below the gauge's top band (it reads 100 from raw 97
  up, `HANDOFF.md` §74 A3: an hour begun at 100 % reads low); note whether the pair is worn (with the lease released
  the stock head-up dashboard and the temples are stock's); rest from the host that owns the radio; `probe:rest=on`;
  one hour with no ring touch (any input ends it — the `power` notes show an early end); `probe:rest=off`; then
  `python3 tools/journal_report.py … --since <start>` and read the `rest` row (both figures; a rest under half an
  hour or 2 % prints `-`).
- **A BTSnoop capture** (the recipe verified from the stack's source, `captures/README.md`): Bluetooth off → Developer
  options → HCI snoop log = Enabled → ten seconds → Bluetooth on → the session → a full bug report with Bluetooth on;
  the zip must hold `btsnoop_hci.log`, not only `btsnooz_hci.log`. Mailed reports are read from the phone's Sent folder
  on beardos (`~/Mail/marzello.net/Sent`) when the inbox copy does not arrive.
- Remote tmux hosts ride one multiplexed ssh connection (`~/.damage/ssh-*` control sockets, 60 s persist) — a
  stale socket there is safe to delete.
- Views while headless: the phone screen or replica (`http://aphone:7403/?token=…`); the PC replica
  (`http://beardos:7403/…`) shows the data host's status, a live mirror only while a manual PC stack drives.
- **Deploying a new PC build:** `./gradlew :desktop:stageJar && sudo rc-service damage restart` — never touches
  the display (the PC claims nothing). The service runs the stable `~/.damage/damage.jar`; never replace the
  jar under a running instance.
- **Deploying a new APK:** bump versionCode/Name, `./gradlew :phone:stageApk`, download from the setup page
  (`http://beardos:7300/setup` on the tailnet, `SetupServer.kt`), install over; `MY_PACKAGE_REPLACED` restarts
  the phone service by itself. Before any uninstall, pull the archives first (`python3 tools/journal_pull.py
  --live`; 0.64+).
- **The mutation runner** (`research/mutate_core.py`): `?? <file>.kt.mutate-orig` in `git status` = that file holds
  a mutant; any start of the runner puts it back and says so; `<file>.kt.mutate-left` is what the file held — look,
  then remove (none is written when the source already held the original's bytes). One run per tree: a second
  start, a `--check` included, is refused (exit 2) while one lives (`--selftest` beside a live run runs on its
  scratch root). Exit 143 / 129 = ended by SIGTERM / SIGHUP with the source put back; a signal that arrives as the
  list ends stops nothing and the report is printed; a stop before the first entry says so. 29 entries print
  `NO PIN (declared)`; a `NOT RUN` makes the run exit 1. **The wiring gate** (`tools/check_apk_wiring.py`, after
  `:phone:assembleDebug`): 17 lines at 0.64, judged by content — an output older than the manifest that agrees with
  it is a `note` line; exit 2 only for a missing output or one that is older and differs.
- Tmux knobs: `~/.damage/config.json` (`tmuxHosts` — add slappy back when it is actually on — `tmuxQuickKeys`,
  `tmuxSnippets`, `tmuxWaitPatterns`); on-glass settings in Settings → Tmux. The wait alert works for ANY pane,
  a TAP on it opens the session's live view, a quiet host says so on EVERY level and in Main's row (§28–§30).
- **Hand-editing `config.json`** is safe (§28): an unreadable file (a stray comma) is left as it is and the
  service runs on defaults for that start with a loud log line; fix and restart. The 0.66 keys default off
  (`haUrl` and `backupDir` empty; the bus watcher section below). Every HOST-need window on the
  phone (Tmux, Files, Torrents, Reader content, Music, Feed) rides this service — :7401, plus :7404 for Music.
- "Scans forever" while the phone says Connected → the stale-ACL recovery is still **toggle phone Bluetooth**
  (the scan fails loudly and rides the ON edge back in).
- **The status cell says a STATE, never an error** (`DESIGN.md` §4.4, `HANDOFF.md` §69): `no link · silent ·
  rebuild · no lease · halted · fault · link slow · ok`, the first that holds, and it clears itself. Every error
  is a NOTICE with its whole text (tap or double-tap dismisses it; it then stays gone until that class of fault
  has been quiet for a minute); `fault` in the cell = a fault episode is open — read the notice, or the journal's
  `fault` / `error` notes if it could not be shown.
- **`link slow` in the status cell** (§47): the glasses moved the link to their idle parameters (105 ms /
  latency 4); the APK is re-asking for the fast set every 5 s per arm — the `link` notes count the re-asks.
  If it never clears, the firmware keeps winning: that is the ask to Babcock (gate the idle request on the
  lease). Global `Link` = `balanced` is the experiment for the ~50-minute rebuilds. **An `atlas` journal note `full`**: a face
  stayed pixels even after evicting what the window is not drawing — the cache is genuinely full (a latency
  condition: no box, no word in the cell).
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

## The bus watcher (APK 0.66, `HANDOFF.md` §79) — it reads the hub, never writes to it

- **Config keys** (`~/.damage/config.json`, read at start; a restart picks up a change; all off until set):
  `haUrl` (the hub's base URL, `http://<hub>:<port>`; empty = the watcher is off, said at start) · `haTokenFile` (the
  PATH of a file holding the hub's token, read once, trimmed, never logged — no token goes into `config.json`) ·
  `haRulesFile` (default `<dataDir>/bastion-rules.json`) · `haPollSec` (10) · `haSilentMin` (5: "hub not answering"
  after this many minutes) · `backupDir` (the nightly copy's folder; empty = off; it must already exist) ·
  `backupKeep` (14) · `backupAt` (`03:30`) · `reachOpen` (false; `true` turns the listeners' reach gate off — the way
  back if it ever refuses his own network; said at start).
- **The rule file** (`bastion-rules.json`, gitignored data, never under a tracked path; read once at start; a missing
  file = no rules, said; a bad rule refuses only itself and raises `watcher.rule.<id>`). With made-up ids:

  ```json
  {"rules": [
    {"id": "fresh-low", "entity": "sensor.fresh_tank", "kind": "below", "at": 20, "clearAt": 25, "forMin": 5,
     "text": "Fresh tank low", "repeatMin": 120},
    {"id": "fresh-change-away", "entity": "sensor.fresh_tank", "kind": "change", "at": 3, "forMin": 10,
     "when": {"entity": "damage.phone", "equals": "away"}, "text": "Fresh tank changed while away"},
    {"id": "level-quiet", "entity": "sensor.example_level", "kind": "unavailable", "forMin": 30,
     "text": "Level sensor not reporting", "severity": "urgent"}
  ]}
  ```

  Every rule: `id` (lower-case letters, digits, `_ . -`; not `watcher.…`), `entity`, `kind`, `text`, `forMin`,
  `repeatMin` (0 = said once), `severity` (`normal` · `urgent`: every 10 min until acknowledged, passes quiet hours),
  `note`, `when` (`{entity, equals | notEquals}`: a hub entity, or `damage.phone` = `home` / `away`). By kind: `above` /
  `below` take `at` and `clearAt` (the band); `equals` / `notEquals` take `value`; `unavailable` and `stale` need
  `forMin` above 0; `change` takes `at` (the size, in the entity's units), `direction` (`any` · `up` · `down`) and
  optionally `withinMin` (1–1440). An edit applies at the next start; a severity edit from the alert's next episode.
  **Rules guarded on `damage.phone` stay out until one `damage.phone unknown -> home (by the connection path: direct,
  on the network of …)` or `(by the connection path: direct, to a private address routed from this PC)` line has been
  read with him at the bus** (§79.9; the second form is ruling R3.1's, for the bus's second network).
- **Reading the state:** Settings → Bastion → `Watcher` on the glasses or the phone (the PC watcher's state line, or
  "home not answering"); `grep -E ' (bastion|presence|reach): ' ~/.damage/damage.log`; the alerts themselves in
  `~/.damage/alerts.json`. The phone: `Position` (the location state, words only), `place` notes in its journal.
- **The glasses from the PC by hand** (his call, like every `glassdrive.py` line): set the phone's Target to Sim
  first (nothing warns when both want the radio); beardos's adapter is rfkill-blocked on purpose — unblock it for the
  session and block it again after; then `python3 tools/glassdrive.py localhost TOKEN probe:drive=ble` against the PC
  replica (:7403); back with `probe:drive=auto`. Each use is a `keeper` note and a log line.
- **The nightly copy:** into `backupDir` as `damage-YYYYMMDD-HHMMSS/` (state files only: `state.json`, `torrents.json`,
  `alerts.json`, the rules file, the feed's sources and binge lists, the pending uncaught record); 14 kept; a failed
  run raises a notice on the glasses and phone. A restore is a manual copy back with the service stopped.
- **The phone (0.66):** channels "Bus alerts", "Bus alerts · urgent", "Home unreachable" (Android settings → Damage →
  notifications); the Acknowledge button on a bus alert; the app's "location" button (precise location, then "Allow
  all the time") turns on presence from the phone; `Presence · location` turns it off. Global `Quiet hours` (default
  off) holds normal bus alerts and releases them at the end.
- **Journal notes (0.66):** PC `reach` (a refused connection, once per source per minute) and `presence` (each change
  of `damage.phone` and which signal decided); phone `place`, `heap3` (free memory at four points of a start), `cue`,
  `slow`; a line marked `late` was written a second or more after its event.

## What was verified vs what awaits glass

The §13 hardware pass ran 2026-08-31 (`HANDOFF.md` §13); the §19 re-shape is
test-pinned (`SyncTest` ×6; since 0.64 `SyncToleranceTest` for the 2 s tolerance) and seen live (`sync-host: peer attached to the sync channel`; a phone-side
`window.tmux` record applied store-direct while the PC shell was in standby). **Still awaiting a deliberate
glass test:** the sync feel across a driver swap (a book position following the swap); the standby engagement test is
moot since W made PC-direct BLE manual (`drive=ble` by hand is its replacement, below). **Games
(`HOLDEM.md`) needs nothing from ops** — no host, no provider, no channel; its state is the shell's own store
(`window.games`, `window.games.world`, `window.games.bankroll`, `window.games.table`,
`window.games.char.<id>`) and syncs like every other window's. ⚠ Its layouts and card art have never been seen
on the glasses — only on the simulator at true 1×.
