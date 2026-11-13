# INTERFACES.md — the web interface, the phone app's own screen and the XFCE4 piece: the explosion

**Context for the reader.** Damage is a personal window manager Adam wrote for his own Even Realities G2 glasses,
on firmware he installed with the published patch method. It runs on his own phone, PC and home networks. This
file is design work for user interfaces (documents only) for three of the interfaces he ruled beside the glasses:
the web interface (a desk browser and a phone browser; the kitchen TV uses the same pages, and its design is in
`BASTION.md`), the phone app's own screen, and the XFCE4 desktop piece. It lists what they could do so he can cut
the list. Plain wording throughout (`CLAUDE.md`).

**Status: an EXPLOSION written 2026-11-12/13 for Adam's refinery. Nothing in it is built or in force. The decisions
put to him are in `REPLAN.md` §15; his rulings are recorded there.** `REPLAN.md` §14 wins wherever this differs.
Grades: **read** (file:line or URL), **reported**, **inferred**, **unknown**. Nothing here was measured on his
devices. R1–R6 are step A's research reports, kept in the private folder (`private/stepA/`, out of the repository);
details of his home and of today's listeners are in its annex (`private/BASTION-annex.md`). §4 holds the rules
every interface shares; `BASTION.md` and `REMINDERS.md` refer to it.

## 1. Facts the design stands on

| fact | grade | source |
|---|---|---|
| Every window paints pixels for 640×480; no route serves a window's structure, so today another screen can only show the glasses picture | read | `REPLAN.md:34-37` (F1) |
| Already independent of the glasses: summary, settings rows, menu and keyboard specs, notice, deep links, sync records, the PC's content services, the engines; `POPOVER.md` has a block vocabulary (heading, text, bullets, kv, table, chart, image, rule, page) and row types | read | `REPLAN.md:51-54`; `POPOVER.md:82-83` |
| Deep-link targets exist per window: `t:<hash>`, `tl:<fid>`, `session:<host>:<name>`, `path:…`, `cat:<Name>` (opaque strings today) | read | `TORRENTS.md:180`; `TMUX.md:185-186`; `WINDOWS.md:199-200` |
| Positions: Reader keeps a character offset; Files, Torrents, Feed, Music and Games keep indices into lines wrapped at the glasses' width | read | `REPLAN.md:45-49` (F3) |
| Summaries over the window channel are still unbuilt | read | `WINDOWS.md:135` |
| The PC already runs a dependency-free HTTP/1.1 + WebSocket server (the replica), gated today by one shared token that §4.1 replaces; which addresses today's servers answer on, and how they check the token, is in the private annex ("Today's listeners") | read | R1 §0; R2 §C |
| One of today's servers (the setup page) already gates by network interface: the pattern §4.2's rule reuses per listener | read | R2 §C (`SetupServer.kt:21-33`) |
| The service (running as `user`) can ask tailscaled who a tailnet peer is (device + user) without being operator; a LAN peer gets 404; `tailscale serve` hides the peer (arrives from 127.0.0.1). The answer names the device, not the page that connects from it (§4.1) | read / inferred | R2 C rows 6-12 |
| HTTPS costs: the HTTPS feature on the tailnet, machine and tailnet names in public certificate logs, an operator or root step; `tailscale cert` renews by hand every 90 days; Caddy (already running on the PC) renews by itself | read | R1:114; R2:128-131; his system notes (Caddy) |
| Service workers, Notifications, Push and Wake Lock need HTTPS or localhost; Web Push goes through the browser vendor's server | read | R1:113, R1:116 |
| One client written to a Chromium 108 floor (the TV); container queries yes, CSS nesting no | read / inferred | R1:22-26 |
| Firefox has no app mode and no Linux web-app install; Vivaldi 8.1 and Opera 134 are installed, no Chromium | read | R2:15, R2:37; R1:119 |
| The service is headless (no DISPLAY, no session bus): the desktop piece needs a companion started by the XFCE session | read / inferred | R2:18-20, R2 A1 |
| The panel tray implements StatusNotifierItem with dbusmenu menus; dbus-java (MIT) is already a dependency | read | R2:21-26 |
| notifyd shows action buttons only while do-not-disturb is off; only critical urgency passes it (master source; 0.9.7 may differ) | read | R2:34 |
| The panel's sound plugin lists MPRIS players and sends media keys to all of them (`multimedia-keys-to-all` true) | read | `~/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-panel.xml:90-95`; strings in `libpulseaudio-plugin.so` |
| Installed: PipeWire 1.6.8, WirePlumber 0.5.15, mpv 0.41, Thunar, zenity, xdotool; not installed: xclip, xsel, playerctl, genmon | read | `qlist -Iv`, `ls /usr/bin` (2026-11-13); R2:14, R2:39 |
| tmux 3.5a has control mode (a text protocol, no terminal needed); a window takes its size from attached clients by `window-size`; no tmux config file exists, so the default applies (inferred: `latest`) | read / inferred | `man tmux` ~l.3898, ~l.2648 |
| The phone app: one activity locked to landscape, one process, a ReplicaServer of its own, no androidx.webkit; a WebView renderer loss ends the app process unless handled, and with it the glasses' session | read / inferred | R2 B rows 1-4, R2:84-85 |
| The WebView's asset loader serves in-app files on an https origin, with path handlers for assets, resources and internal storage; pages bundled in the APK change only with an APK update and drift from the server's | read | R2:78-79 |
| A page from the APK's https asset origin that opens `ws://` to the PC is blocked as mixed content | inferred | R2:81 |
| The phone's notifications today: the permanent one, urgent (behind Global `Phone notifications`), glasses battery, re-grant; none has an action button | read | `ShellService.kt:410-425`; no `addAction` in `phone/` |
| A media3 `MediaSession` exists for the phone player; whether the lock screen shows its controls today | read; unknown | `phone/…/music/AndroidMusicPlayer.kt:22,39` |
| The PC already streams transcoded tracks to the phone with Range requests (the media endpoint) | read | R2 C row 1; `MUSIC.md:53-57` |

## 2. Candidates

Size: S a day or less, M a few days, L a week or more (inferred, before any design pass). Lean is the writer's.
The kitchen TV uses these pages (`BASTION.md`). It goes into use with the bus step (S3), since most of what it
shows is the bus system; the interface step (S2) builds what its host needs — approval, the remote's keys, the
layout's frame — and its reminders zone is reserved from the start (`REPLAN.md` §15, "when the TV goes into use").

### 2.1 Web — the shell (every screen that shows the web interface)

| id | candidate | what it gives him | what it needs | size | lean | conflicts |
|---|---|---|---|---|---|---|
| W-01 | Home: Main's rows as cards (summary lines, progress, staleness with its age) | every window at a glance | summaries over the channel (unbuilt) | M | in — Main's summary is already independent of the glasses | — |
| W-02 | A window rail on the desk; a bottom bar with "more" on the phone; badges for unread and alerts | the wheel's job, one click | the window list, badge counts | S | in | — |
| W-03 | Two or three windows side by side at desk widths | Music beside Tmux at work | layout per interface | M | in, desk only | `DESIGN.md:38` excludes split view on the glasses only |
| W-04 | An address per window and place (`/w/torrents/t:<hash>`); back and forward; bookmarks | links between windows; history works | deep-link targets readable, not only opaque | S | in | targets are opaque per window today |
| W-05 | A search box (Ctrl-K): windows, settings rows, books, tracks, sessions, files, torrents, feed items, text inside the open book | reach anything by typing | a searchable index per window | M | in | — |
| W-06 | Keyboard shortcuts: digits for windows, `/` search, `?` help; per window (space play/pause, j/k) | hands stay on the keys | a shortcut table per window | S | in | `DESIGN.md` §1's "no per-window gestures" binds the ring only |
| W-07 | One focus model for arrows, Enter and Back | keyboard-only use; the TV remote runs the same code | a focus order from the description | M | in | shared with the TV's design |
| W-08 | Notice toasts while open; a notice tray with history | see what the glasses saw; tap through to the place | the PC's notice record (§4.3) with appId, thread, target | S | in | the future Notices window (`EXPLOSION.md:606`) is the phone's notification history; keep the two apart |
| W-09 | "Continue here": each interface's current place (window, item, age, last input), take one | pick up the book or session the glasses had | each interface's focus held on the PC | S | in | R4 rules it deliberate; this is its form |
| W-10 | "Send to": from the screen he is at, put a window and its place in front on another screen (the glasses, the TV, the desk); its own act, apart from "continue here" | line up a chapter on the glasses or a camera on the TV from the desk | a focus message per interface | S | in | the other screen's focus moves — by his deliberate act, made on the sending side (R4) |
| W-11 | Themes: dark, light, follow the system; a "glass" theme in the faces the glasses use | comfort at night; kinship with the glasses | fonts served by the PC | S | in | B612 never a default (memory) |
| W-12 | Text size and density per device; layouts from a 360 px phone to his 4096 px desk | one page everywhere | CSS grid, container queries | M | in | the Chromium 108 floor |
| W-13 | An installed web app on the desk (Vivaldi or Opera) | own window and icon | HTTPS, a manifest | S | later — X-12's `--app` window gives the same without HTTPS | needs W-15 |
| W-14 | An offline shell (service worker) for the browsers | the page opens with the PC down | HTTPS | M | out — the phone app (P-02) covers away; the desk is at home | needs W-15 |
| W-15 | HTTPS through `tailscale serve`, or `tailscale cert` with the Caddy already running | secure context (W-13, W-14, W-16, clipboard writes), Secure cookies | the tailnet's HTTPS feature; names in public logs; operator step; a bus-LAN DNS entry | M | later — not in the first build | reach stays tailnet and bus network either way |
| W-16 | Browser notifications and a screen wake lock (reading, now playing) while the tab is open | notices in a background tab; the phone browser stays lit | HTTPS | S | later, with W-15 | — |
| W-17 | Web Push | notices with the tab closed | the browser vendor's push server | S | out | leaves through a public server (R1:116), against §14.3 #3; the APK and the desktop carry alerts |
| W-18 | One confirm shape for guarded and irreversible actions; a double confirm where the glasses have one | the same safety everywhere | the action's kind in the description | S | in | — |
| W-19 | Undo for reversible actions (mark read, trash, queue remove) | a misclick costs nothing | an inverse per action | M | later | — |
| W-20 | Multi-select with batch actions | stop five torrents, trash ten files, drop six tracks | actions over several targets | M | in (Torrents, Files, Music queue) | — |
| W-21 | Diagnosis page: services, providers' state lines, connected interfaces, versions, journal tail | find a fault from any screen | the existing `/journal`, `/log` routes (`ReplicaServer.kt:48`) | S | in | — |
| W-22 | Devices page: approved devices and their levels (§4.1), pending codes, rename, revoke | approve the TV once; drop a lost phone | a device store on the PC (hashes only) | M | in | — |
| W-23 | Tailnet identity by whois on the peer address; no token in the URL | the tailnet names the device: nothing to paste, nothing in history or bookmarks | the connection reaching the JVM directly on the tailnet | M | in — identification only: who-is never authorizes alone (§4.1) | `tailscale serve` in front would hide the peer |
| W-24 | Approve-once pairing for every device, tailnet or bus network: the new screen shows a short code, a trusted interface approves it and sets its level, the PC sets an HttpOnly SameSite=Strict cookie (hashed at rest, refreshed within 400 days) | the TV, a new phone, a new browser approved once | the pairing flow; §4.1's checks on every request | M | in | over HTTP the cookie is readable on the bus Wi-Fi (R2:135); the level is the real limit |
| W-25 | Per-device level: view · control · full (full: Tmux, file writes, guarded bus actions, anything that reaches outside — the tracker, the Claude helper, YouTube) | a screen at view or control cannot type into a shell or spend his accounts | a level per action in the description | S | in | — |
| W-26 | Glasses settings preview: a true-1× render at the chosen face, size and height | set the glasses' type at the desk and see it | the desktop's snapshot renderer as a route | M | later | — |
| W-27 | A small live glasses mirror tile | watch the glasses from the desk | the replica | S | out | `REPLAN.md` §6, W8: the mirrors stay development views |
| W-28 | Reminders front and centre on the home | the lists in view wherever he opens the page | the reminders window (the first new window) | S | in — reserve the slot now | its design is `REMINDERS.md` |
| W-29 | Bus panel; PC load and temperatures on the home | the bus beside everything | the bus step (S3); the PC's figures, built there with the graph store (`BASTION.md` T-33) | S | later, at S3 | Info is a new window in his order (`EXPLOSION.md:605`) |

### 2.2 Web — the existing windows

| id | candidate | what it gives him | what it needs | size | lean | conflicts |
|---|---|---|---|---|---|---|
| W-30 | Reader: the book as typeset HTML from the EPUB's own XHTML, images in place, a chapter sidebar; the shelf as a cover grid with progress | read at the desk with the pictures | sanitized chapter documents, image and cover blobs, the character offset mapped to the page | M | in | — |
| W-31 | Reader: highlights and notes | marks that follow the book | new shared per-book state | M | out | a new feature, not an interface; never asked for |
| W-32 | Tmux: a real terminal (xterm.js, MIT, served by the PC) through tmux control mode; scrollback search | colour, mouse select and his own keyboard in his sessions | a control-mode client in the service; a size policy | L | in, devices at level full only | `window-size` would shrink his desk terminal to the browser's size |
| W-33 | Tmux: quick keys and snippets as buttons over the phone keyboard | answer `y` one-handed | the configured quick keys (`TMUX.md:138`) | S | in | — |
| W-34 | Files: a file manager — breadcrumbs, list or grid, thumbnails, sort, hidden; previews in colour (images, PDF in the browser's viewer, text) | the PC's files from any browser | listings, thumbnails (unbuilt on glasses, `EXPLOSION.md` 5.7), the blob lane | M | in | — |
| W-35 | Files: upload (drag and drop, several files) and download | move a file between phone and PC with nothing else | streamed upload to a temp file, renamed when complete | M | in | replaces the parked "fetch to phone" (5.16) |
| W-36 | Files: play a video or audio file in the browser | — | Range requests | S | his call | Feed's "we don't do video" (`FEED.md:36`); Files has a poster frame only (5.11) |
| W-37 | Torrents: transfers as a sortable table with speed graphs and the under-a-week list | the qBittorrent view, on his terms | the 2 s focused poll (`TORRENTS.md:29`) | M | in | — |
| W-38 | Torrents: TorrentLeech browse and search as a results table; the torrent page with NFO and files; add / add stopped behind confirm | browse at the desk | the existing adapter | S | in | — |
| W-39 | Torrents: drop a .torrent or paste a magnet | — | — | S | out | verdict 5 (`TORRENTS.md:27`) |
| W-40 | Music: the whole library — artists, albums, tracks, covers, search, sort | browse what the ring cannot | library queries over the channel | M | in | — |
| W-41 | Music: queue and playlists edited by drag; add next or last; now playing with large art, scrub, volume, full synced lyrics | build a queue in seconds | queue ops (exist); lyric lines with times | M | in | — |
| W-42 | Music: output picker — the PC's own output or the phone; a move resumes at the same position | the soundbar at home, earbuds when he leaves | W-43; a handover | M | in | — |
| W-43 | Music: the PC's own player — mpv driven over its JSON IPC, on PipeWire, with its own lyric offset | the TV and soundbar as an output | a separate player process; the device choice | M | in | `MUSIC.md` verdict 2 superseded for the interfaces (§14 R10) |
| W-44 | Music: the Ask box; YouTube search, pick, ingest with progress | type a mood; grab a missing song | the existing ClaudeOneShot and YouTube lanes | S | in | — |
| W-45 | Music: the browser tab as a third player | — | — | S | out | §14.3 #4: two players |
| W-46 | Games: the Hold'em table by pointer — seats, cards, buttons, a raise slider; standings and bankroll as tables | play at the desk | the table as a typed model; the pacer per viewer (F3) | M | in | — |
| W-47 | Feed: articles with pictures in colour; comics at source resolution, uninverted, with alt text; the binge as one long page | the comics as drawn | image blobs not quantized | M | in | — |
| W-48 | Feed: a link to the original page | — | the item's URL | S | his call | verdict 14 "Open on PC" out (`FEED.md:36`) |
| W-49 | Settings: forms by category, each row marked shared · this device · glasses only; searchable | any setting from anywhere | a scope tag per row | M | in | — |

### 2.3 The phone app

| id | candidate | what it gives him | what it needs | size | lean | conflicts |
|---|---|---|---|---|---|---|
| P-01 | The app's own screen: the web interface in a WebView, its own activity, portrait allowed | all of Damage on the phone by touch | androidx.webkit (new, Apache-2.0); the landscape lock lifted for that screen | M | in | — |
| P-02 | Pages served by the PC and cached in the app's storage (the asset loader's internal-storage handler, R2:78), with a set shipped in the APK as the fallback and a page/data version handshake in the contract; all data through the app (a message bridge): the PC's state when reachable, the working copy when not | one screen at home and away; a web change reaches the phone without an APK release | the cache and the handshake; a bridge in ShellService; no `ws://` from the page | M | in | the APK's fallback set ages between releases; the handshake says when it is older than the PC's |
| P-03 | Renderer loss handled (rebuild the WebView), or the WebView in its own process | a page fault never ends the glasses' session | `onRenderProcessGone` returning true, or a data-directory suffix | S | in | — |
| P-04 | An away line: which windows work here, the PC's age, changes waiting to go to the PC | knows what is live and what is old | the change queue (§14.2) | S | in | — |
| P-05 | The control strip kept native: target, type, release, music access | today's controls one tap away | existing | S | in | — |
| P-06 | A remote for the glasses' session: tap, double-tap and scroll pads, a type line | drive the glasses with the phone in hand | today's lens-view touch and type dialog | S | in | — |
| P-07 | Share sheet: a file to Files (a chosen folder), an EPUB to the shelf, a YouTube link to Music's ingest, text to reminders | send things to Damage from any app | ACTION_SEND filters; routes per window | M | in (text after reminders lands) | a magnet or torrent link stays out (verdict 5) |
| P-08 | Notification buttons: Open on every notice; Pause and Next on music | act from the shade | notification actions (none today) | S | in | — |
| P-09 | A Tmux alert showing the prompt's last line, with `y` and Enter as buttons | answer Claude Code from the shade | send-keys from a notification | S | his call | command execution from the lock screen |
| P-10 | Lock-screen and shade player for the phone output | standard controls | the media3 session (exists); its notification | S | in | — |
| P-11 | The same controls for the PC's player (a remote session with volume) | the soundbar from the lock screen | a remote-playback session fed by the PC | M | in | — |
| P-12 | Quick-settings tiles: play/pause on the current output; rest the glasses; target sim/glasses | one swipe | TileService | S | later | the target switch confirms today; a tile has no room for it |
| P-13 | Home-screen widgets: reminders, now playing, bus state | glance without opening | AppWidget; the reminders window | M | later | — |
| P-14 | Launcher shortcuts: Music, Tmux, Reader, Continue here | — | ShortcutManager | S | later | — |
| P-15 | Approve a bus-network device by scanning the code it shows | approve the TV in two seconds | the camera permission (new) | S | later — typing the code works | — |
| P-16 | Set-up and diagnosis: permissions (battery exemption, listener, Bluetooth, notifications), link state, PHY, journal tail, PC reachability | find what is off without the PC | existing reads | M | in | — |
| P-17 | The tailnet identifies the phone; approval and its per-device secret authorize it (§4.1); no token baked into the build | no secret in the APK or its prefs | W-23, W-24 | S | in | — |
| P-18 | Power-saving rows: close the WebView with the screen off; slower pushes | a day's battery when needed | settings | S | in | — |
| P-19 | Android's speech input in text fields | dictate a reminder | the system recognizer | S | out | voice runs on the PC's own stack (§14.4) |

### 2.4 XFCE4

| id | candidate | what it gives him | what it needs | size | lean | conflicts |
|---|---|---|---|---|---|---|
| X-01 | The desktop companion: the same jar in a second mode, started from XFCE autostart, talking to the service on loopback with the local secret (§4.1) | a host for everything on the desktop | an autostart entry; a secret file readable only by his user | M | in | loopback alone is not trusted (§4.1) |
| X-02 | Panel item (SNI): icon state (glasses via phone / PC-direct / no session), tooltip with Main's summaries, attention on an alert, a count overlay | status at a glance | dbus-java export of the item | M | in | — |
| X-03 | Panel menu (dbusmenu): windows, now playing with transport, output picker, recent notices, pending device approvals | control without opening the window | the dbusmenu half (the bulk, R2 A2) | M | in | — |
| X-04 | Click opens or raises the window; scroll on the icon sets the current output's volume | — | SNI Activate and Scroll | S | in | — |
| X-05 | A menu row "Drive the glasses from this PC" with a confirm, showing the standby's state | the manual PC-direct choice in one place | a manual switch on the standby | S | in | §14.4 rules it manual; this places it |
| X-06 | Live text in the panel (genmon): the track or the download speed | — | xfce4-genmon-plugin emerged (in the tree) | S | later | — |
| X-07 | A native C panel plugin with a popup under the button | a mini window at the icon | C against libxfce4panel; licence question open (R2:31) | L | out | a fifth codebase (`REPLAN.md` §6) |
| X-08 | Desktop notifications for Damage notices, updated in place (a download's progress, the track), with Open and one reversible action | notices at the desk | `org.freedesktop.Notifications` over D-Bus | S | in | actions vanish under do-not-disturb |
| X-09 | Urgent bus alerts as critical notifications | the one class that should interrupt | the watcher's `severity: urgent` (R6 as amended, `BASTION.md` §1 F13) | S | in | — |
| X-10 | An MPRIS player for the PC output | media keys and the panel's sound menu control Damage music | MPRIS on the session bus | S | in | — |
| X-11 | Global shortcuts: xfconf entries calling a `damage` command (open a window, continue here, quick note, send the clipboard) | keys for the frequent things | a CLI mode of the jar using the local secret (§4.1); the entries added by him | S | in | — |
| X-12 | The interface window: Vivaldi or Opera `--app` with its own profile and window class | Damage as a desktop app | an installed browser (Firefox cannot) | S | in | — |
| X-13 | A mini player or notice strip kept on top (xdotool ABOVE, STICKY) | now playing in a corner of the 4K screen | a mini layout; xdotool | S | later | — |
| X-14 | Quick note: a shortcut opens a one-line entry (zenity) that lands in reminders | capture a thing to buy in two seconds | the reminders window | S | in, with reminders | — |
| X-15 | Send the clipboard: text to reminders or a Tmux session; a link to Music's ingest | — | clipboard read in the companion (it has the display) | S | later | pasting into a shell is command execution: confirm |
| X-16 | Thunar right-click actions: "Open on the glasses" (EPUB in Reader, image in Files), "Send to the phone" | files to the glasses from the file manager | Thunar custom actions calling the CLI | S | later | — |
| X-17 | Presence: the desktop's idle and lock state tells the PC he is at the desk | notices go to the desk, not the phone, while he sits there | idle and lock signals on the session bus | M | later | no notice rule depends on knowing which interface he is using (§4.3) |
| X-18 | `damage notify "text"` from any script | a finished build tells the glasses | the CLI | S | later | `POPOVER.md` §5.2 `damage-show` is the specified form |

## 3. The existing windows on these screens

Shared = one copy on the PC (R3). Per interface = focus, cursor, layout, scroll inside the view (R4).

| window | what these screens show and do | shared | per interface |
|---|---|---|---|
| Main | the home cards (W-01), badges, reminders slot; the panel tooltip and menu | the summaries; the notice records and their read and acknowledged state (§4.3) | card order and which card has focus; theme |
| Settings | forms by category with search (W-49); the phone's set-up screen (P-16) | every behaviour row (notify toggles, polls, Music quality and outputs, Feed fetch and keep) | glasses-only rows (Font, Size, Depth, Cached text, Slide) belong to the glasses; theme and text size to each device |
| Reader | typeset book with images, cover shelf (W-30) | shelf, per-book character offset, the open book | which level is up, the font and column, the scroll inside a page |
| Tmux | a real terminal (W-32), sessions with alerts, quick keys (W-33); P-09 on the phone | sessions, alerts, mutes, quick keys and snippets | which session is viewed, history scroll, the terminal's font fit |
| Files | file manager, upload and download, previews (W-34, W-35) | the clipboard (Copy/Cut), trash, hidden and sort rows | the folder he is in (today's cwd per root, inferred to be focus under R4), the selection, view mode, thumbnails |
| Torrents | the table, TorrentLeech browse and search, stats (W-37, W-38) | filter recents, search recents, the announced set | sort, filter, columns, the open torrent |
| Music | library, queue, playlists, lyrics, output picker, Ask, YouTube (W-40…W-44); P-10/P-11; X-10 | queue, position, playlists, the current output, volume per output | which view is open (now playing, queue, library), the library's scroll |
| Games | the table by pointer, standings, bankroll (W-46) | the bankroll, the action log, the tournament | the view; the pacer's speed (F3: today one pacer on the showing shell) |
| Feed | articles and comics in colour, the binge as a page (W-47) | read marks (union), flags, binge place | the open source and item, the list scroll |

## 4. Rules every interface shares

Stated here once; `BASTION.md` and `REMINDERS.md` refer to them. Their mechanics are settled in `CONTRACT.md` (§2.4 the merge rule, §7 notices, §9 the wire, §10 access).

**4.1 Access: the tailnet identifies a device, approval authorizes it.** Every device, on the tailnet or on the
bus network, is approved once from a trusted interface (W-24) and given a level: **view**, **control** or
**full**. `full` is needed for Tmux, file writes, guarded bus actions and anything that reaches outside (a
tracker, the Claude helper, YouTube). Every HTTP and WebSocket request is checked for Origin and Host; every call
that changes state needs a per-device HttpOnly secret, tailnet devices included. The local command and the desktop
companion use a local secret readable only by his user; the helper's loopback endpoint checks Origin and a bearer.
Who-is alone never authorizes: it names the device, not the page that connects from it (any web page open in his
browser can open a WebSocket to the PC, and CORS does not cover WebSockets — inferred). The kitchen TV's host gets
a fixed set — read, acknowledge alerts, tick reminders, play and pause; no bus action, no Tmux — whatever network
it is on.

**4.2 Listeners.** Every listener is bound or gated to loopback, the tailnet and the bus network (the PC), and to
loopback and the tailnet (the phone); the first build brings today's code to that rule (`REPLAN.md` §14.3 answer
3; what today's code does is in the private annex, "Today's listeners").

**4.3 Notices: one record on the PC.** Every notice (a window's, a bus alert, a due reminder) is one record on the
PC — id, episode or occurrence, actions, read and acknowledged state — that every interface reflects; dismissed or
acknowledged once, it is gone everywhere. The phone raises a notice from its own copy only while the PC has not
confirmed it, and such a notice is marked "may repeat". No rule depends on knowing which interface he is using.

**4.4 Edits from several places: one rule.** Each device numbers its operations, the PC merges them, and a losing
edit is said on the item. Every window follows this one rule and none settles its own variant; it is settled in
`CONTRACT.md` §2.4.

## 5. What this asks of the app contract

| kind | a window's description must carry | why these screens need it |
|---|---|---|
| list items | a stable id per row; typed fields (text, size, speed, duration, date, count, percent, state word) with units; icon kind; badges; progress | tables sort and format by type (W-37, W-40); a cursor follows identity |
| documents | blocks: headings, paragraphs with emphasis, mono text (NFO), images by reference with size and alt text, links, a table of contents | Reader, Feed articles, torrent pages render as HTML, not strips |
| canvases | a typed model each: terminal (a control-mode stream or cells with SGR), card table (seats, cards, pot, legal actions), now playing (track, position, volume, timed lyric lines), comic (image + alt + bonus) | one renderer per interface (REPLAN principle 1) |
| images | the source image by reference, fetched at full resolution and colour | the glasses' 16-grey strips are the glasses' presentation |
| actions | id, label, enabled or a reason it is dim; kind (reversible, irreversible, outbound, guarded) with the confirm text and a double confirm (a guarded action carries its confirm); parameters (text, choice, number with range, file); one or several targets; the answer; an operation number per device (§4.4) | W-18, W-20, W-25, P-08, X-03 all draw actions without knowing the window |
| level | per action: view, control or full (§4.1) | W-25 limits a screen by its level |
| listeners | every listener bound or gated by network: the PC to loopback, the tailnet and the bus network; the phone to loopback and the tailnet (§4.2) | `REPLAN.md` §14.3 answer 3; today's code does not yet follow it |
| live values | value, unit, as-of time, source, and why it is stale; whether it is live only while viewed | ages shown everywhere; "live only while active" per viewer |
| anchors | item identity (hash, fid, path, session, guid), document anchor (character offset or block + offset), media time (track id + ms), episode + strip | "continue here", "send to", W-04's addresses, P-02's working copy |
| addresses | deep-link targets that can be built and read, not only opaque | W-04, P-14, notification taps |
| settings | each row's scope: shared, per device, glasses only | W-49 |
| notices | the record of §4.3 — id, episode or occurrence, appId, thread, target, urgency, read and acknowledged state — carrying its actions | W-08, X-08, X-09, P-08 |
| search | an index (titles, names, text) per window | W-05 |
| owner state | whether the value came from the PC or the phone's working copy, and the queue of unsent changes | P-02, P-04 |
| versions | the page bundle's version and the data's, compared at connect | P-02's cached pages |

## 6. Failure cases a design must answer

| case | what the design must say |
|---|---|
| The PC out of reach (browser) | what the page shows (last state with its age), which actions turn dim and why, how it says the PC is back |
| The PC out of reach (phone app) | the switch to the working copy for Reader, Music, Main, Settings, Games, and Reminders once built (a shopping list works in a shop); the others' last state with age; the change queue's count; on return, §4.4's merge (a queue reordered at the desk while the phone edited it) |
| A stale value | the age shown on every value; when a value turns dim; what a stale value may still be used for (never as the base of a guarded action) |
| A refused action | the PC refuses (the torrent was removed, the prompt moved on, the track ended): the answer goes to the interface that asked; operations are numbered per device (§4.4), so a resend after a reconnect does not apply twice |
| Two interfaces at once | pause on the TV and next on the phone: the PC orders them and every interface shows the result |
| A device never approved | the code screen; the pending row on trusted interfaces; how long a code stays valid (the no-timeouts rule covers the BLE, render, input and flash paths, not this — say so); denial |
| A device revoked while connected | the socket closes with a reason; the screen says it |
| A request from a page the device did not mean to trust | refused on its Origin or Host, or for lack of the device's secret (§4.1) |
| The glasses' session down | the web interface works on (the PC owns state); "send to glasses" turns dim with the reason; the panel icon says it |
| The desktop companion not running, or the service down under it | the icon goes grey or is absent; the companion's own restart (autostart runs at login only) |
| WebSocket dropped and back | a resync from a version cursor (the Torrents shape); no old notice shown again |
| The phone's WebView renderer lost | the screen rebuilds; the glasses' session continues (P-03) |
| The phone's cached pages older than the PC's | the version handshake says so; the app fetches the new set, or runs its fallback set and says which (P-02) |
| A playback move that fails | moving to the phone while the phone is unreachable: playback stays where it was and says why |
| A notification button after the state moved | a `y` pressed after the prompt changed is refused with the answer (P-09) |
| An upload cut off, or the disk full | a temp file, never a half file under the real name; the error on the screen that uploaded |
| Do-not-disturb on the desktop | buttons gone: every action also in the menu and the window |
| A web terminal attached | it must not resize his desk session (W-32) |

## 7. Decisions

**Put to Adam, and ruled 2026-11-13 as recommended (`REPLAN.md` §15.1):** the rows of `REPLAN.md` §15 that concern
this document: listener reach · device levels · the TV's
level · windows side by side · "send to" · a notice dismissed once · the browser terminal · links and video on big
screens.

**The lead's defaults** (taken as written unless he objects):
- How windows are reached: a rail on the desk, a bottom bar on the phone, the home cards and a Ctrl-K search box
  (W-01, W-02, W-05) · the wheel's job by pointer, touch and keyboard; all small.
- An interface's own state (focus, layout, theme) is kept on the PC by device (W-09) · it survives a cleared
  browser, and "continue here" reads it.
- No HTTPS in the first build (W-15 later) · it puts his machine name in public certificate logs and needs an
  operator step; the phone app relays instead (P-02).
- The phone app's screen is the web interface in a WebView, upright, its pages as P-02 has them (served by the PC,
  cached, the APK's set as the fallback); the lens view stays as it is (P-01, P-02) · one screen at home and away.
- Notification buttons only for reversible actions (open, pause, next); anything with a confirm opens the app
  (P-08) · a shade button cannot show the confirm a guarded action needs.
- The share sheet's first three targets: a file to Files, an EPUB to the shelf, a YouTube link to Music; text to
  reminders when it lands (P-07) · the cheapest way in from every other app.
- Tiles, widgets, launcher shortcuts and QR approval later, after reminders (P-12 to P-15) · none is needed for
  full access.
- The panel item is an SNI item with a menu, from the desktop companion; no C plugin (X-02, X-03, X-07) · the tray
  is installed and dbus-java is already a dependency; a C plugin is a new language and an open licence question.
- The Damage window is Vivaldi in app mode with its own profile, Opera as the fallback (X-12) · Firefox has no app
  mode on Linux.
- An MPRIS player for the PC output (X-10) · the panel already sends media keys to every MPRIS player.
- Desktop notifications as his per-app toggles allow, updated in place; only urgent bus alerts at critical urgency
  (X-08, X-09) · actions vanish under do-not-disturb, so each is also in the menu.
- The PC's player is mpv as a separate process over its JSON IPC, on PipeWire (W-43) · it plays every format in the
  library, and a separate program keeps Damage's code clean (the notifyd pattern).

**Deferred:**
- To the contract sitting, settled in `CONTRACT.md` §2.4: merge and idempotency (§4.4).
- To the contract sitting, settled in `CONTRACT.md` §7: how notices travel (§4.3).
- To the contract sitting, settled in `CONTRACT.md` §10: the access mechanics — the secret, its storage and renewal,
  the Origin and Host checks, the approval flow (§4.1, W-22 to W-24).
- To the contract sitting, settled in `CONTRACT.md` §9.5: page versioning — the handshake between the phone's cached
  pages and the PC's (P-02).

## 8. Facts only Adam or a test at a device can settle

- Which of the two session buses the panel and notifyd use (Adam: `echo $DBUS_SESSION_BUS_ADDRESS` in an XFCE
  terminal) — the companion must find it (R2).
- Whether `user` is the tailscaled operator, whether HTTPS is on for his tailnet, and whether he accepts his machine
  name in public certificate logs (`tailscale debug prefs`; the admin console; his call).
- Whether a host firewall already drops bus-network arrivals on today's ports (as root, `nft list ruleset`; the
  ports are in the private annex).
- The Pixel 10a's WebView version and the memory a WebView costs beside the shell (the §71 out-of-memory history says
  measure it at the phone).
- Which PipeWire device is "the TV and the soundbar", and whether the PC player follows the default device or a fixed
  one (Adam, at the desk).
- Whether he keeps a desk terminal attached to the sessions the glasses watch (decides W-32's size policy).
- Whether he wants the Damage window on a key.
- Whether notifyd 0.9.7 matches master on do-not-disturb and critical (read the 0.9.7 tag), and whether xfsettingsd
  hands shortcut commands the session's environment (one test entry).
- Whether his keyboard's media keys reach an MPRIS player today (play anything MPRIS, press the key).
- Whether the lock screen shows Damage's music controls today (look at the phone while it plays).
- Whether the bus router can hold a local DNS entry (only if HTTPS is taken up, W-15).
