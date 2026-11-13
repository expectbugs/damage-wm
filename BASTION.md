# BASTION.md — the kitchen TV and the bus system ("Bastion"): the explosion

**Context for the reader.** Damage is a personal window manager Adam wrote for his own Even Realities G2
glasses, on firmware he installed with the published patch method; it runs on his own phone, PC and home
network. This file is design work for two user interfaces on hardware he owns: a dashboard on a consumer smart TV
in his kitchen, and a control system for his bus ("Bastion") that reads his own home-automation hub and, through
confirmed actions, asks it to act. It builds nothing and touches no device. Plain wording throughout (`CLAUDE.md`).

**Status: an EXPLOSION written 2026-11-12/13 for Adam's refinery. Nothing in it is built or in force. The decisions
put to him are in `REPLAN.md` §15; his rulings are recorded there.** It is the wide list, written to be cut.
`REPLAN.md` §14 wins wherever this differs. The alert watcher (R6) is the first build and the B rows extend it; one
amendment comes before it is built: each rule gains `severity` (`normal` | `urgent`), and an urgent alert repeats
until acknowledged and passes quiet hours (R6 is amended so). What is specific to his bus — the set itself, which
value feeds which tile, the cameras, his thresholds, what is in place today, today's listeners — is in the private
annex (`private/BASTION-annex.md`, kept out of the repository). **Nothing in this file was measured on his
hardware**: spec-sheet and bench figures say so where they appear.

Columns: **size** S/M/L, relative as `FORK.md` uses them · **lean** in / later / out, the lead's first opinion for the
refinery to override · **conflicts** names a ruling, an exclusion or another row. `DESIGN.md` §0's exclusions bind
the glasses only; a row that would be excluded there says so and stays a candidate here. Report keys: R1 web and
TV · R2 desktop, phone, identity · R4 the bus's systems · R6 the watcher design (`private/stepA/`). Access,
listeners, notices and edits made in several places follow `INTERFACES.md` §4.

## 1. Facts the design stands on

| # | fact | grade | source |
|---|---|---|---|
| F1 | The TV's browser engine as sold is Chromium 108: WebSocket, grid, container queries and import maps present, CSS nesting absent; web content renders at 1920×1080 at most. An online update may move it to a newer engine | read; reported (the update) | R1:21-26,32 |
| F2 | The vendor: the screensaver "always works except while playing a video in full screen", and no page or app can hold it off (vendor staff, 2026). Its timed types are documented for the maker's OLED sets only; what this set's LCD panel does with a static page is not documented | read; unknown (this set) | R1:34-36,138 |
| F3 | No system rotation on a non-rotating consumer set; portrait means rotating the page in CSS, and video inside a rotated page failed for one developer | read; reported | R1:46-48 |
| F4 | The TV plays one video element at a time; WebRTC is for contracted partner apps only. With stills on the TV (B-17) the one-video limit is moot | read; inferred (moot) | R1:28-29 |
| F5 | No setting opens an app at power-on; the power-on screen setting offers the home screen or the last input | read | R1:38 |
| F6 | A developer-mode app needs a vendor account and internet, lapses after at most 1000 h unless extended, and is removed when it lapses | read | R1:59-61 |
| F7 | A small computer on HDMI: kiosk Chromium, rotation by its compositor, the TV remote's keys arriving over HDMI-CEC as input events; sets of this maker pass arrows and OK, Back often not | read; reported | R1:69-77 |
| F8 | The TV's typical draw is on its spec sheet (the figure is in the private annex); a Pi 5-class computer idles at 2.4–3.3 W (bench, reported) | read; reported | R1:49,78 |
| F9 | Every listener is bound or gated to loopback, the tailnet and the bus network (the PC), and to loopback and the tailnet (the phone); the first build brings today's code to that rule. Today's listeners: the private annex | the rule (`INTERFACES.md` §4.2) | R1:12; R2 §C |
| F10 | A device on the bus LAN cannot be named by the tailnet lookup: it needs a pairing code and a cookie (every device is approved once anyway, `INTERFACES.md` §4.1), and over plain HTTP that cookie is readable on the LAN | read; inferred | R2 §C items 4-5 |
| F11 | The hub's recorder keeps ten days by default; hourly long-term statistics exist only for values declared as measurements or totals | read | R4:22-27 |
| F12 | A non-admin hub user can still call services, so "read-only" is enforced in Damage's code (one GET path, a lint rule), not by the hub | doc; read | R6:13-15,72-76 |
| F13 | The watcher as designed: rule kinds above/below/equals/notEquals/unavailable/stale; states CLEAR→RAISED→ACKED→CLEAR in `alerts.json`; a `bastion` channel; Android notifications reconciled; a Settings category before the window. Amended before it is built: each rule carries `severity` (`normal` \| `urgent`) | read; the amendment (lead) | R6 §2-3 |
| F14 | "Home unreachable" is the phone's to raise: the PC cannot report its own silence | read | `REPLAN.md`:316-317; R6 §4 |
| F15 | Camera pictures can come from the hub's camera proxy (a still per request, bearer token), or a restreamer (WebRTC, MSE, HLS, MJPEG); a recorder can composite every camera into one stream | read | R1:91-98 |
| F16 | HTTP/1.1 allows six connections per host: four MJPEG tiles nearly fill it; a WebSocket is outside that pool | read; inferred | R1:100 |
| F17 | No code in `core/` or `desktop/` reads load, temperatures or the GPU today (search for hwmon, sensors, loadavg, nvidia-smi, 2026-11-13); disk fill per location exists in Files | read | `FilesWindow.kt:42` |
| F18 | The contract hands a window pixels; a summary is a line, a detail, a "more" flag and a progress | read | `WindowContract.kt:193-198`, `REPLAN.md` F1 |
| F19 | Web Push goes through the browser vendor's server, so phone alerts stay in the APK | read; inferred | R1:116 |

## 2. The TV as a host

| id | candidate | what it gives him | what it needs | size | lean | conflicts |
|---|---|---|---|---|---|---|
| T-01 | The TV's own browser opens the PC's page | no hardware to buy | the TV on the bus network; pairing (T-08); CSS rotation | S | decided after the visit (§11) | T-03 |
| T-02 | A hosted app in the maker's developer mode (a local stub redirecting to the PC); or an Android TV stick | no browser bars; Back handled | a vendor account, internet, renewal (F6); the stick's portrait is reported broken (R1:83) | M | out | needs the TV on the internet |
| T-03 | A small computer (Pi-class) on HDMI, Chromium in kiosk mode, always on | screensaver, rotation and one-video limits gone; boots to the page | about $110–160 (reported, not a quote) and 2.5–3.3 W (bench, reported); one more always-on device to update and supervise; an image and a start script the lead writes | M | decided after the visit (§11) | a purchase, his call |
| T-04 | The PC drives the TV over a long HDMI cable as a second, rotated output | no second computer | the PC within cable reach; a kiosk window inside his X session; his desktop layout changes | M | later | the service is headless (R2 §A) |
| T-05 | Portrait by the host's compositor rotation | a true 1080×1920 page; video rotates with it | T-03; rotation reported broken on one board (R1:71) | S | in with T-03 | — |
| T-06 | The TV kept off every network | no vendor account, no browser updates under us, nothing of the TV's to secure | T-03 or T-04 | S | in with T-03 | T-01, T-02 |
| T-07 | The box joins the tailnet | the tailnet identifies it; it is still approved once and keeps the TV's fixed set (`INTERFACES.md` §4.1) | Tailscale on the box; HTTPS later, not in the first build (a tailnet certificate puts names in public certificate logs, R1:114) | S | in with T-03 | — |
| T-08 | Approve-once pairing: a short code on the TV, approved from the phone or desk; a hashed, revocable cookie; a device list in Settings | the ruled approve-once, which every device goes through (`INTERFACES.md` §4.1, W-24) | HttpOnly, SameSite=Strict cookie renewed inside 400 days (R2 §C) | M | in | — |
| T-09 | The remote as the only input: arrows between zones, OK opens a zone full screen, Back returns, number keys jump to pages | a ten-foot focus model, written once in the web client | key codes (R1:40-42); CEC keys mapped, Back remapped if not passed | M | in | — |
| T-10 | Power-on brings the page back: box always on, the TV's power-on screen set to the last input, the box sends "Image View On" over CEC | the TV comes up on the dashboard untouched | T-03; check 4 of §11 | S | in | — |
| T-11 | The system switches the TV: off at a night hour and in away mode, on at a morning hour, by CEC; the remote always wins | the TV's typical draw (spec sheet, figure in the annex) saved over the off hours — not measured | CEC standby and on; the mode (B-32) | S | in, as a setting, off by default (`REPLAN.md` §14.1 R12: nice by default, power saving as settings) | — |
| T-12 | Day and night: a dim night palette by sun time or a schedule; the backlight left at one TV setting | no glare at night | sun times (hub or computed); CEC carries no backlight command (inferred) | S | in | — |
| T-13 | A kiosk watchdog on the box restarts the browser after a crash or a low-memory close; the PC notes the TV's connection gaps | the screen recovers by itself; other interfaces can say "TV not connected 20 min" | a supervisor on the box | S | in with T-03 | — |

**The host is decided after the ten-minute visit (§11), not here.** Vendor-stated: a page or app cannot hold off
the screensaver, which "always works except while playing a video in full screen" (R1:34); the set does not
rotate (F3); one video plays at a time (F4). Unknown for this set: whether its LCD panel blanks a static page, and
after how long (R1:36, R1:138); whether its browser passes the remote's keys to a page; what power-on brings back.
Stills on the TV (B-17, B-18) make the one-video limit moot. A small computer (T-03) costs about $110–160 (reported,
not a quote) and adds one more always-on device to update and supervise; if the visit shows the TV's own browser
keeps a page lit and passes the keys, T-01 is the cheaper start.

## 3. The TV's screen

| id | candidate | what it gives him | what it needs | size | lean | conflicts |
|---|---|---|---|---|---|---|
| T-14 | One long portrait screen, every zone visible at once, no timed paging | "a big infodump" read in one glance | a zone layout in a file on the PC | M | in | — |
| T-15 | Drill-down pages by remote: one system, one camera full screen, a graph's 30 days | detail without crowding | T-09 | M | in | — |
| T-16 | Reminders zone, top third: overdue and today's to-dos, the shopping list, the next calendar events | his "front and centre" | the Reminders window (`REPLAN.md`:338-342) | M | in; place reserved from the start | when the TV goes into use (§10) |
| T-17 | Ticking a reminder with OK | done at the counter | a reversible tick action | S | in | — |
| T-18 | Bus strip: one tile per kind of system — value, state, age | every system at a glance | B-02, B-03 | M | in | — |
| T-19 | Camera block: four tiles; the two wide cameras as full-width strips, not quarter tiles | the four camera feeds | B-16–B-19 | M | in | glasses: images only, never dithered |
| T-20 | Graph block: energy 48 h; the PC's load, memory and temperatures 24 h; one bus graph of his choice | his "graphs of stuff like energy usage and PC stats" | B-08 store; T-33 | M | in | — |
| T-21 | Clock and date, large | the time across the room | — | S | in | — |
| T-22 | Weather today and tomorrow, from the hub's weather entity or a public forecast | a glance before going out | a location; one outside request for the public service | S | later | `EXPLOSION.md` §20 axed Weather as a glasses window |
| T-23 | Now-playing footer: cover, track, progress, which output plays | what plays, and where | Music's state on the PC | S | in | — |
| T-24 | Remote media keys drive the PC's queue (play/pause, next) | music control from the kitchen | T-09; Music's actions | S | in | next is outside the TV's fixed set (`INTERFACES.md` §4.1) |
| T-25 | An urgent alert takes the screen over: top half shows it, its value and age; OK acknowledges; it leaves on clear or acknowledgement | an alarm seen from anywhere in the room | the watcher's states and `severity` (F13); B-36 | M | in | glasses exclude banners (`DESIGN.md` §0) |
| T-26 | A camera comes forward on a detection: half the screen for a dwell (pacing, not a timeout) or until Back | who is outside | the recorder's or hub's detections; mode (B-32); quiet hours | M | in, parked and away only — detectors fire constantly while driving | glasses: motion answers input only (`MOTION.md` §5) |
| T-27 | Damage health zone: phone reachable, glasses connected, the watcher's state, the service's last error | the system's own state unasked | the service's status lines | S | in | — |
| T-28 | A strip of the last few Damage notices (a session wants input, a download finished) | the glasses' notices in the kitchen | the PC's notice records (`INTERFACES.md` §4.3) | S | later | `EXPLOSION.md` §20 rejected "tickers" for the glasses |
| T-29 | Night layout: after a set hour only clock, alerts and tomorrow's reminders stay lit | a dark kitchen | T-12 | S | in | — |
| T-30 | Type sized for the room, a TV text-size row | readable from the stove | the §11 visit; 1080 CSS px over ≈54 cm is ≈0.5 mm a pixel, close to a 50-inch landscape set (inferred) | S | in | — |
| T-31 | Week calendar page | the week ahead | Reminders' events | S | later | — |
| T-32 | "Send to" the TV from the phone or desk: a camera, a graph, a picture, a window | a deliberate act from the sending side, steering a screen that has only a remote | per-interface focus (ruled); the "send to" action (`INTERFACES.md` W-10) | S | with W-10 (put to Adam) | — |

**When the TV goes into use:** with the bus step (S3), since most of what it shows is the bus system. The interface
step (S2) builds what its host needs — approval, the remote's keys, the layout's frame — and the reminders zone
(T-16) is reserved from the start. `INTERFACES.md` §2 gives the same answer; `REPLAN.md` §15 puts it to him.

## 4. The PC's own figures (a data source Damage does not have, F17)

| id | candidate | what it gives him | what it needs | size | lean | conflicts |
|---|---|---|---|---|---|---|
| T-33 | A figures source in the PC service: CPU load, memory and swap, temperatures (CPU, drives, GPU), disk fill per mount, GPU use, memory and power, network throughput | the PC's "load, usage and temperatures" on every interface | /proc, hwmon, `nvidia-smi` (installed); sampled into B-08 | M | in, with the graph store at S3 | builds the data half of the queued Info window (`EXPLOSION.md` §9.11) early |
| T-34 | Top processes by CPU and memory | what is loading the PC | /proc | S | in | — |
| T-35 | Damage's own figures: heap, uptime, flushes, journal errors | the service's health beside the PC's | the journal, the JVM | S | later | — |
| T-36 | PC rules on the watcher's engine: a disk nearly full, a temperature high, swap heavy | a warning before it slows | T-33; R6's rule kinds | S | later | — |

## 5. The bus system on every interface

**5.1 Pages and live values**

| id | candidate | what it gives him | what it needs | size | lean | conflicts |
|---|---|---|---|---|---|---|
| B-01 | An overview page: one line per kind of system, worst first, each with its age | the bus's own "Main", on every interface | B-02 | S | in | — |
| B-02 | A page per kind of system — power, water, climate, lighting, cameras, doors, network, safety, vehicle, appliances; a kind with nothing behind it is not shown | one place per system | the entity map (B-06) | M | in | — |
| B-03 | Every value says how old it is; past a per-kind limit it greys and says "14 min old" | never a frozen number taken as live | the reading's time and the PC's receive time (R6 §2) | S | in | — |
| B-04 | Source health: per integration, local or cloud, last update, drop-outs today | which numbers to trust now | state objects; counts on the PC | S | in | — |
| B-05 | Derived values: hours of battery at the present draw, time to full, days of fresh water at the recent rate, hours until a waste tank is full | answers, not raw numbers | rates from B-08 | M | in | — |
| B-06 | One entity map on the PC: friendly name, unit, kind, staleness limit, and corrections (scale, offset, unit relabel) shown as "corrected" | sensors that report in the wrong scale fixed once, visibly | a gitignored file beside the watcher's rules (R6 Q7) | S | in | — |
| B-07 | Site profile: level offsets and thresholds per parking site, picked on arrival; a short median for sloshing levels while driving | tank readings that stay meaningful off level and in motion | B-06; B-32 | S | in | — |

**5.2 Graphs and the store**

| id | candidate | what it gives him | what it needs | size | lean | conflicts |
|---|---|---|---|---|---|---|
| B-08 | A sampled store on the PC: chosen values every 30 s into day files in the PC's data directory, inside the nightly copy — not the music database, which the copy leaves out (R6 §6) | graphs past the hub's ten days, owned by the PC, surviving a hub rebuild | the watcher's reader extended; disk | M | in | B-09 |
| B-09 | A recorder change on the hub (longer keep for chosen values, noisy ones excluded), written as a file for him | history kept on the hub | his hand on the hub's configuration | S | later — only if B-08 is not chosen | B-08 |
| B-10 | Back-fill on first run from the hub's history and long-term statistics, read-only | graphs not empty on day one | history REST; statistics through the hub's socket API (inferred) | S | later | — |
| B-11 | Energy by day, in and out per source (solar, shore, alternator, battery), stacked, 30 days | where the energy came from | B-08 | S | in | — |
| B-12 | Battery charge over 48 h with the charge band shaded | the night's drain at a glance | B-08 | S | in | — |
| B-13 | Tank levels over 14 days, fills and drains marked from the action log | water use; when to drain | B-08; B-29 | S | in | — |
| B-14 | Temperatures over 7 days: inside, the coldest spot, outside | heating decisions | B-08 | S | in | — |
| B-15 | A morning card: yesterday's energy used and made, water used, coldest reading | a daily digest | B-08 | S | later | — |

**5.3 Cameras**

| id | candidate | what it gives him | what it needs | size | lean | conflicts |
|---|---|---|---|---|---|---|
| B-16 | Camera pictures from recording software on the PC, not through the hub | tiles that work while the hub is down; no hub load or token use | a restreamer reachable by the service (private annex) | S | in | — |
| B-17 | Stills refreshed every 1–2 s per tile | four cameras on any browser, the TV's included | B-16; four requests a tick (F16) | S | in | — |
| B-18 | Live video on desk and phone (MSE or WebRTC); stills on the TV | real motion where the screen can play it | B-16 | M | in | — |
| B-19 | One composite stream of all cameras | one video for a one-video host | a recorder's composite output, always running, PC CPU (R1:98) | S | later | — |
| B-20 | Recent detections: thumbnail, camera, time, kind | what happened while away | the recorder's or hub's events | M | later | — |
| B-21 | A camera still on the glasses: one frame as a 16-grey image in a Document | a look from the glasses | Files' image viewer path | S | later | glasses: no dithering (`DESIGN.md` §0) |
| B-22 | Hide cameras and reminders on the TV on demand | visitors in the kitchen | a TV toggle | S | later | one user is ruled; the room is not |

**5.4 Guarded actions and the log**

| id | candidate | what it gives him | what it needs | size | lean | conflicts |
|---|---|---|---|---|---|---|
| B-23 | An allowlist on the PC: only listed hub scripts exist as actions; anything else is refused and said; a test that every offered action is on it | "never a raw relay" (ruled), made checkable | a gitignored allowlist file | S | in | — |
| B-24 | Three tiers — A one confirm; B filling and draining the tanks; C never. Every action of every tier calls its allowlisted hub script and is offered only once a script of that name exists there: the PC's check is a name check, not a read of what the script holds | danger sorted once | B-23; §6 | S | in | the tiers are Adam's ruling (§10, "bus action tiers"); the list is in the private annex |
| B-25 | Every action, the lightest included, calls a hub script, never a raw switch; a pump or heater "off" carries its interlock in the script | the limit holds even if Damage stops mid-way | §6 | S | in | — |
| B-26 | A writer apart from the watcher's GET-only reader: its own class in its own package, one POST path for allowlisted script calls, its own lint rule; the reader's rule unchanged | the first build's read-only guarantee survives | R6 §2 (RO001 forbids a POST under the reader's package) | M | in | — |
| B-27 | Read-back: "sent · waiting for the state 8 s" until the hub's state shows it, no time limit, the age shown; the action is numbered like every operation (`INTERFACES.md` §4.4), so a resend does nothing twice | never "done" on a send alone; no double toggle | B-03 | S | in | — |
| B-28 | Confirm per interface: the web names the action and its limit; the phone a system dialog; the glasses' form comes with the glasses kit (K); never on the TV, which has no bus action | one confirm everywhere, fitted to each input | §8's guarded action | M | in | the glasses' confirm is deferred to K (§10); `WINDOWS.md:35` forbids a per-window gesture |
| B-29 | Action log: every action, which device and interface, the confirm, the hub's answer, the state seen after; append-only, in day files in the data directory, inside the nightly copy | who did what, when | the PC store; R6 §6 | S | in | — |
| B-30 | Changes made outside Damage (wall switches, the hub's own screens, automations) in the same log, labelled | one history | state changes with their context | M | later | a lead default (§10) |
| B-31 | Scenes as one action ("leaving", "arriving") calling one hub script each | one tap instead of six | §6 | S | later | — |

**5.5 Modes**

| id | candidate | what it gives him | what it needs | size | lean | conflicts |
|---|---|---|---|---|---|---|
| B-32 | Modes — parked, travelling, away — set by hand from any interface, one copy on the PC | the system behaves for the situation | a mode record read by rules and layouts | S | in | — |
| B-33 | Travelling: detection pops muted, tank alerts held, charging-while-driving figures promoted | no false alarms on the road | B-32 | S | in | — |
| B-34 | Away: freeze and power alerts escalate sooner, cameras come forward on the phone, tier A only | the bus watched while gone | B-32; B-36 | S | in | — |
| B-35 | Automatic mode from a signal where one exists (vehicle motion, charging from the engine, his phone on the bus network) | no forgetting to switch | a signal source | M | later | — |

**5.6 Alerts beyond the first list**

| id | candidate | what it gives him | what it needs | size | lean | conflicts |
|---|---|---|---|---|---|---|
| B-36 | An escalation ladder per rule: notice → phone notification → repeated phone alert → TV takeover; the repeat and the takeover for `urgent` rules | serious things are not missed | R6 delivery + `repeatMin`; `severity` (F13) | M | in | — |
| B-37 | Quiet hours hold `normal` rules; `urgent` rules pass them | sleep | the `severity` field (F13); Settings rows | S | in | — |
| B-38 | Acknowledge from any interface (TV OK, web, glasses notice, phone action); one acknowledgement clears everywhere | ack once | the one notice record (`INTERFACES.md` §4.3); R6's ack on the PC; an on-glass ack callback (R6 §11, not built) | S | in | — |
| B-39 | More rule kinds: falling fast, on for too long, A while B, expected by schedule | conditions a threshold misses | R6's evaluator extended | M | later | — |
| B-40 | Snooze for N hours, apart from acknowledge | known conditions left waiting | an alert state | S | later | — |
| B-41 | Alert history: episodes, durations, where acknowledged | patterns over weeks | `alerts.json` events + B-29 | S | in | — |
| B-42 | Rule editing on the web interface with a preview against the store's last day | thresholds changed without a restart | B-08; hot reload (R6 §11, not built) | M | later | — |
| B-43 | A test alert per path (glasses, phone, TV, desk) | each path known to work | R6 delivery | S | in | — |
| B-44 | Upkeep: items with a date or usage interval (filters, servicing) and a sweep of low sensor batteries and integrations that lose their login, raised as reminders when due | upkeep remembered | the Reminders window; battery and filter values | M | later | a lead default (§10) |

**5.7 The glasses, and when the hub or the PC is out of reach**

| id | candidate | what it gives him | what it needs | size | lean | conflicts |
|---|---|---|---|---|---|---|
| B-45 | The bus window's summary, which Main shows as it shows every window's: line 1 the worst condition and its age ("all normal · 1 min", "a tank 82 % · 4 min"), line 2 battery and water; progress = battery | the bus in one glance | `summary()` from the PC table | S | in, with the bus window at S3 — not with the watcher, whose scope is R6's | — |
| B-46 | A Bastion window on the glasses: systems → a Document of values with ages → controls at guarded levels | read and act from the glasses | the glasses kit (step K) | L | later — on the kit, never on today's list code | `WINDOWS.md` §1 (no per-window gestures) |
| B-47 | A status-bar mark while any alert is raised | an alert visible from every window | a chrome cell | S | later | chrome never justifies its own flush (`CLAUDE.md`) |
| B-48 | The PC keeps the last table with timestamps; every interface shows it with its age; actions are refused with the reason | honest staleness | B-03 | S | in | — |
| B-49 | The phone keeps the last bus table (R6's `wincache`) and shows it with its age away from home; no actions | the bus picture with the PC out of reach | R6 P7 | S | in | — |
| B-50 | A direct read path from the phone or the TV's box to the hub when the PC is down | values while the PC is down | a hub token on a second device; a second reader | M | out | the PC is the system (`REPLAN.md`:260) |

## 6. What belongs in the hub, not in Damage — scripts the lead writes as files for him to review and apply

Each script carries its own limit, holds across a hub restart, and writes a logbook line naming the caller
("Damage, from the desk"). Every action Damage offers, the lightest included, is one of these scripts; Damage never
toggles what a script guards. An "off" is a script too: switching a pump or a heater off can matter as much as
switching it on, so its interlock lives in the script. Which entity each one uses is in the private annex.

| # | script | what it holds in the hub |
|---|---|---|
| H-1 | Drain a waste tank | opens the drain valve; closes it at a low level or after a maximum time, whichever comes first; closes it after a hub restart |
| H-2 | Fill the fresh tanks | a level stop and a time cap; refuses while a drain is open |
| H-3 | Water heater on | an off-timer |
| H-4 | Tank heating pads on | an off-timer, or a temperature condition once a sensor exists |
| H-5 | Pump on / off | an optional night schedule; refuses while the pump has been running past a set time |
| H-6 | Sump or bilge pump | a maximum run time; an alert flag when it is exceeded |
| H-7 | Restore after a relay board restarts | re-applies the last chosen water mode and outputs after a relay board or hub restart |
| H-8 | Interlock | valves that must not be open together are refused by a condition, not by Damage |
| H-9 | Floodlight on | an off-timer |
| H-10 | Switched outlet on | an off-timer (a charger, a heater) |
| H-11 | Lock | locking only; there is no unlock script |
| H-12 | Modes and scenes | "leaving", "arriving", "travelling" as one script each (B-31) |
| H-13 | A hub user for Damage with its own token | not a script: a step for him, so Damage's calls are told apart in the logbook (R6 Q6) |

## 7. The existing windows on the TV

One copy on the PC is window state (ruled); the TV's own is focus, cursor and layout (ruled R4). Where a field falls
is the lead's first line; S1 fixes it per field (`REPLAN.md`:278-280). The TV's fixed set (`INTERFACES.md` §4.1)
bounds what it does: the entries beyond it (a Hold'em hand, next track) wait on his ruling on the TV's level.

| window | the TV shows | the TV does | one copy on the PC | the TV's own |
|---|---|---|---|---|
| Main | each window's summary as a small tile in the health zone, staleness as the summary says it | opens a window's drill-down page | every summary | which tiles appear, their order |
| Settings | nothing on the main screen; a TV category edited from desk or phone (zones, text size, night hours, takeovers) | nothing in daily use | every row, the TV's stored under its device | none |
| Reader | the book in progress: title, chapter, percent | opens the current page full screen (later) | position as a character offset, bookmarks, shelf | type size and page view |
| Tmux | sessions waiting for input; hosts out of reach | a session's last lines, read-only (later); never sends keys | sessions, alert state, mutes | open session, scroll |
| Files | disk fill per location in the PC zone | none; "send to" the TV from elsewhere (T-32) | locations, trash | browse path, cursor |
| Torrents | active transfers: name, progress, speed | none (adding and deleting need typing and a confirm) | transfers, search recents | filter, sort, cursor |
| Music | now playing: cover, track, progress, output; lyrics large (later) | play/pause and next by media keys | queue, position, the one chosen output | open level, lyrics on or off |
| Games | the scoreboard (cash · tournaments · Loser Count) | a Hold'em table by arrows and OK (later; the table as a model with a TV renderer) | bankroll, action log, standings | which view |
| Feed | the newest comic strip; unread counts | next and previous strip by remote (later) | read marks (union), flags, binge position | open item, scroll |

The two new ones: **Reminders** owns the TV's top third (T-16); **Bastion** owns the strip, the camera block and
the bus graphs (T-18-T-20).

## 8. What this asks of the app contract

For the TV to draw a window it does not know, a description must express:
- **Content kinds:** a value (number or text, unit, kind of system, a state band — normal, attention, alarm —
  the reading's time, the PC's receive time, source health); an item list with a check state; a time series by
  store key and span (the interface draws it); an image by reference with its age and refresh cadence; a video by
  reference with a fallback chain (live → composite → still); an event with a start time (calendar, detection);
  a progress; the summary line and detail with staleness as a field, not baked into the text (today's
  `Summary`, F18).
- **Actions:** reversible (tick, play/pause); guarded (tier, the hub-side limit in words, the target's present
  state; a guarded action carries its confirm); acknowledge (alert id + episode, R6); "send to" another interface.
  Each carries the level it needs (`INTERFACES.md` §4.1; the TV's host has its fixed set), answers sent / seen in
  the state / refused with a reason, and carries the sending device, the interface and its operation number
  (`INTERFACES.md` §4.4) for the log.
- **Notices:** the one record of `INTERFACES.md` §4.3; a notice carries its actions (acknowledge, open).
- **Live values:** pushed on change, age carried, a staleness limit, a "last known" flag when the source is out
  of reach; the interface greys by that rule, not the window.
- **Position anchors:** a zone claim (front and centre, glance strip, detail page), a size class (number tile,
  list, graph, image, wide image), an order within the zone, whether and when it may take the screen (an urgent
  alert raised; a detection in a given mode), and mode conditions (shown while parked, hidden while travelling). No
  pixel geometry: the TV lays out, the glasses keep their own layout.

## 9. Failure cases a design must answer

| case | the TV | elsewhere | must not happen |
|---|---|---|---|
| The PC out of reach from the TV (service stopped, PC off, network down) | last values greyed under a full-width "PC not answering since 14:02"; paced reconnect; state resent from epoch and sequence | — | old values shown as current |
| The hub out of reach from the PC | every bus value greyed with its age; actions refused "hub not answering 6 min" | the watcher's built-in alert (R6 §2) | an action queued for later |
| Hub, network and PC all out of reach at once | dark | only the phone's "home unreachable" can say it (F14) | anything claiming the bus is fine |
| One stale value | that tile greys; the rest stay live | a `stale` rule may raise | a stale value crossing a threshold |
| A refused action (allowlist, hub error, a script's interlock, no script of that name in the hub) | the TV offers none | the confirm shows the reason; logged | a refusal that only reaches a log |
| An action whose state never changes | "sent · no change 4 min" stays on the tile | past a set wait an "action not confirmed" alert | a silent give-up or an automatic repeat |
| A device never approved, or revoked | its pairing code and the clock only | the approval request on trusted interfaces | any bus value on an unapproved screen |
| The session drops mid-confirm | — | the confirm is void and says "not sent" | an action sent on half a confirm |
| The TV's browser closes (memory) or crashes | the watchdog restarts it (T-13) | "TV not connected 3 min" in health | — |
| A camera source down | "picture 5 min old" on the tile | the same | a still passed off as live |
| A gap in the store | the graph shows the gap | the same | a line drawn across it |
| An alert acknowledged elsewhere during a takeover | the takeover leaves on the pushed state | — | a second acknowledgement needed |
| Hub and PC clocks disagree | ages counted on the PC's clock from first sight (R6 §2) | the same | ages that jump |

## 10. Decisions

**Put to Adam:** the rows of `REPLAN.md` §15 that concern this document: the TV's level · "send to" · a notice
dismissed once · the TV's host (after the visit) · when the TV goes into use · what may take the TV's screen · bus
action tiers · camera pictures and the recorder's settings · graph history on the PC · alert severity.

**The lead's defaults** (taken as written unless he objects):
- One long TV screen with every zone on it, detail pages opened by the remote, never pages on a timer (T-14,
  T-15) · an infodump is read at a glance; a timed page hides what he looks for.
- The TV's power schedule exists as a setting, off by default (T-11) · his ruling: nice by default, power saving
  as settings.
- Modes (parked, travelling, away) set by hand from any interface; automatic detection later (B-32, B-35) · a
  wrong automatic mode mutes the wrong alerts.
- The PC's own figures (load, memory, temperatures, disks, GPU) built with the graph store in the bus step (T-33)
  · the TV needs them, and the queued Info window reuses them.
- The action log also records changes made outside Damage — wall switches, the hub's own screens, automations —
  labelled (B-30) · one history; the graphs' event marks need it.
- Upkeep (filters, sensor batteries, integrations that lose their login) becomes reminders once Reminders exists
  (B-44) · it lands in the lists he already reads, instead of a second place.

**Deferred:**
- To the glasses kit (K): how the glasses confirm a bus action (B-28, B-46) — the draft's options were a chord on
  Bastion's control screens, the same at its top level, or a confirm row like every other irreversible act.
- To the contract sitting: merge and idempotency (B-27; `INTERFACES.md` §4.4).
- To the contract sitting: how notices travel (B-38; `INTERFACES.md` §4.3).
- To the contract sitting: the access mechanics (T-07, T-08; `INTERFACES.md` §4.1).

## 11. Facts only Adam, or a test at a device, can settle

**The ten-minute TV visit** (the lead puts up a one-page test beforehand — a key logger, a rotated mock of the
layout, a seconds clock — and removes it after). Turn automatic updates off before the TV joins a network; if it
offers a software update, note it and decline for now: an update changes the browser engine. This set's menu
names are in the private annex.
1. **(1 min) Version.** The TV's software version, on its About page (F1 assumes the version it was sold with).
2. **(2 min) Settings that exist.** Note the screensaver, automatic power-off (turn it off), the power-on screen
   and HDMI-CEC settings. No network needed.
3. **(3 min) Remote keys.** Join the bus network, open the browser, load the test page; press the arrows, OK,
   Back, numbers, colour keys, play/pause: does the page log them, or does the browser move its own cursor?
4. **(2 min) Power-on.** Power off and on with the remote: the page, the home screen, or the last input?
5. **(2 min) Legibility, then walk away.** Read the rotated mock from the stove and the table; leave it open and
   note the time; the seconds clock shows on return whether the screen blanked and when.
With a small computer (T-03), checks 3-4 move to it (which keys the TV passes over CEC; whether the last-input
setting returns to its input) and the screensaver should not apply to an HDMI input (R1:129, inferred).

**Other facts:** the cable distance from the PC to the TV (T-04) · whether he wants a box bought (T-03, after the
visit) · the hub's live entity list (the annex's map comes from an older backup) · which values to graph and at what
interval · quiet hours · the camera software's configuration (it holds camera credentials: his read, or a reader he
clears) · the remote he has (a basic IR remote has no pointer) · four tiles' decode load on a box (measured on the
box with the cameras' low-resolution streams) · whether the TV's browser keeps cookies across power cycles (T-01).
