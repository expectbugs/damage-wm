# REPLAN.md — the order of work, re-examined against where Damage is going

**Context for the reader.** Damage is a personal window manager Adam wrote for his own Even Realities G2
glasses, on firmware he installed with the published patch method; it runs on his own phone, PC and home
network. This file is planning for display-rendering and user-interface work on hardware he owns. Plain
wording throughout (`CLAUDE.md`).

**Status: RULED by Adam on 2026-11-12 (evening), with amendments — §14 holds his rulings and wins over §4–§11
wherever they differ.** All thirteen are ruled, and the nine follow-up
calls of §14.3 were answered the same evening. The order of work is §4's as ruled (`FORK.md` §5 points here and keeps the fork's protocol,
its decisions and the phase designs the steps reuse). Nothing of step A is started: its size is put to him
first. §1–§13 stand as written on 2026-11-12 (the proposal); the recommendations in them are the lead's, the
facts come from two read-only inventories of that day (rough where marked "≈") and a third reader's challenge
(§13). Nothing here was measured on the glasses unless it says so.

---

## 1. Where Damage is going (Adam, 2026-11-12)

After the firmware phases, and before any new window:

1. **Four more interfaces** beside the glasses — one built into XFCE4 on his PC, one in the phone app, one for
   the kitchen TV on his bus, one for any web browser — each giving "full access to all the features and apps
   and windows of DamageWM but optimized for those other use-cases and inputs and outputs".
2. **A full bus control system** ("Bastion", the bus's name) inside Damage: information and control over the
   bus's systems from every interface.

And three rules for getting there: no work that a later phase moves into the firmware, removes or puts out of
scope; as few sessions as possible without giving up quality, usefulness or final stability; plan the next
phases toward that end.

## 2. What that end changes — three findings

**F1. Every window paints pixels for the 640×480 panel.** The contract hands a window a pixel buffer for each
row, lens, line and canvas (`WindowContract.kt:207–250`). No route anywhere serves a window's structure — its
rows, levels or menus; the browser replica, the desktop preview and the phone's lens view all mirror the
glasses frame. On this contract another interface can only show the glasses picture.

**F2. One shell serves one screen.** Every window class runs in the phone's shell; a window's state is
restored at each session start and its pushes are held while no session is up; the PC builds no shell at all
while it stands by (`desktop/Main.kt:701`). The window objects do outlive a session, and the phone's Sim
target runs every window with no glasses — so the limit is not the glasses themselves but one shell with one
transport and one focus.

**F3. Shared state carries glasses geometry.** The records synced between instances hold document positions
as indices into lines wrapped at the glasses' width (Files, Torrents, Feed, Music, Games; Reader's reading
position is a character offset and is the one to copy). Five paints write state that logic later reads.
Several data paths assume one viewer (one focus slot in Feed's engine, one cursor for every replica input,
the Hold'em pacer on the shell that shows the table).

**What is already head-independent** (why this is a re-cut, not a rewrite): the summary, the settings rows,
the menu and keyboard specs, the notice, deep links, the per-window sync records, every content service on
the PC, the engines (Music, Feed, Hold'em). `POPOVER.md` §3.2 already specifies a block vocabulary (heading,
text, bullets, key–value, table, chart, image, rule, page) and row types (action, check, radio, text, done).
By a rough count ≈ 17 % of the windows' 29,000 lines is glasses painting; the rest is logic, data and engines
that carry over. In `Shell.kt` ≈ 66 % is glasses presentation and transport (it stays, as the glasses
interface), ≈ 20 % is window management any interface needs, ≈ 11 % is a start-up that mixes the two.

**The consequence.** A window has to say WHAT it shows and can do; each interface decides HOW. `FORK.md`
Phase 6 rewrites every window's glasses presentation anyway — so the split has to come before it, or every
window is written twice.

## 3. Work the plan as written would do twice, or for nothing

| # | work in `FORK.md` as written | why it is at risk |
|---|---|---|
| W1 | Phase 6 rebuilds all nine windows on the pixel contract | the interfaces then need them split: written twice (F1) |
| W2 | Phase 4 puts staging and local input into today's list kit and Main; Phase 6a rebuilds Main and the lists | the list kit and staging built twice, and each verdict taken twice — the latency bar itself changes when input goes local (`FORK.md` Phase 6a) |
| W3 | Phase 3's first programs on today's surfaces: Main's recede/pan/advance "phone-started for now", the wheel's spin, the popover reveal, Hold'em's deal | Phase 4 makes the first local, Phase 6 rebuilds the rest; `MenuSurface` and `Notifications` are replaced by the popover family |
| W4 | Phase 5's silent clock as a firmware program, "no radio while silent" | the lease still renews every 45 s whatever draws the clock. What the minute flushes cost is NOT known: the one battery sample (`CLAIMS.md` row 190) does not separate the panel, the held link, the lease and the flushes. Unmeasured, so not yet worth building (`FORK.md` principle 5) |
| W5 | Phase 5's battery and link cells drawn by the glasses | the glasses know their own level and the time; the phone's battery, its RSSI and the host's state still have to be sent |
| W6 | Phase 7's offline home designed now | what it should show with no phone is what the bus system will want there; it is the highest-risk firmware (it runs with no phone, so the phone's hold-back cannot act) and nothing depends on it |
| W7 | Further polish of `Slide.kt`, the `Slide frames` / `Slide fill` rows, `MenuSurface`, `Notifications`, `KeyboardSurface`, `SilentMode` | each is replaced by a named later step |
| W8 | New features in the three mirrors (the replica, the desktop preview, the phone's lens view) | they stay as development views |
| W9 | Any new synced position stored as a wrapped-line index | it must become an anchor every interface can resolve (F3) |

## 4. The re-plan

**Five principles.**

1. **Describe once, present per interface.** One description of each window — its state, levels, rows as data,
   actions, settings, notices. The glasses, a web page, the TV and the desktop are presentations of it. A
   canvas (the terminal, the card table, the now-playing card, a comic) is a typed model with one renderer per
   interface.
2. **Design every consumer before the contract is fixed.** His method is explosion, then refinery, then the
   plan. The four interfaces and the bus system get theirs BEFORE the description is written, so the contract
   is derived from what survives on all five screens — not fitted to the glasses and patched later.
3. **One owner for each window's live state.** Two programs editing one session with last-write-wins drop one
   side's edit without saying so, and with the phone driving the glasses while the PC serves a browser that
   would be the everyday case, not an edge. The code already leans the other way: Files, Torrents, Feed and
   Tmux are PC services, Music is the phone's, Feed runs one engine with the phone's copy parked. Each window
   gets one owner; every other interface is a remote view of it, and an owner that is away shows as stale —
   the rule windows with `needs` already follow.
4. **Each thing is built once, when its final shape is known.** A window's logic is converted once. The
   glasses kit — lists, staging, motion, local input, the popover family — is built once, after the second
   flash's numbers. A verdict is taken once.
5. **The software and the firmware do not wait for each other.** Converting the windows needs no flash.
   A flash needs no converted window: it is felt with test programs. Each of his sittings then closes work
   from both.

**The order.**

```
A  the design arc ──┬──► S1  the core; each window a description ──┬──► K  the glasses kit, once
                    │        (the web interface grows with it)     │        (needs C2's numbers)
                    └──► C1  first flash ──► C2  second flash ─────┘
                         S1 ──► S2  the other interface pieces ──► S3  the bus system
                         G  last: the offline home decided again; the closing consistency pass
```

| step | what | replaces in `FORK.md` | Adam is needed for |
|---|---|---|---|
| **A. The design arc** (documents) | An explosion for each of the four interfaces and for the bus system, each cut down by his refinery (`MOTION.md` has its own already). Then, from the survivors: the app contract (the description vocabulary, anchors for positions, the owners, what is per interface and what is shared, guarded actions, live values that say how old they are); `FIRMWARE.md` §5 and §6 from draft to contract; the build plan, each step sized | Phase 0's refinery and decisions | the refinery sittings and the contract's rulings |
| **S1. The core and the windows as descriptions** (no flash) | The app core split out of the shell. Then one window at a time, whole: its logic converted to the contract; on the glasses it is drawn by the paint code it has today, moved behind the description — nothing he sees there changes, and the snapshot renders hold that or name each difference; on the web it is built to its refined design. Two consumers from the first window: a description that cannot reproduce today's glasses picture, or cannot make the web page, is caught on window one | (new; it is the first half of Phase 6's rewrite, done once) | verdicts on the web interface at the PC; none on the glasses |
| **C1. The first flash** | the motion engine and the riders (§5), felt with test programs | Phase 3's flash | the flash and a feel session |
| **C2. The second flash** | local input and the vertical-scale draw, on test surfaces | Phase 4's flashes | the flash and a work week of use |
| **S2. The other interface pieces** | as refined in A: the TV's layout by remote, the phone app's own screen, the XFCE piece (§6) | (new) | verdicts at the phone, the TV and the desktop |
| **S3. The bus system** | as refined in A (§7) | (new) | its rulings and a verdict |
| **K. The glasses kit, once** | lists, documents, the popover family, notices and the keyboard drawn from descriptions through the text cache, staged, moved by programs, bound to local input; then each window's glasses presentation on it, with its latency profile and his verdict | Phase 3's and Phase 4's Damage halves, and Phase 6 | the verdict stops Phase 6 lists |
| **G. Last** | the offline home decided again with the bus system in view (C3, or dropped); the closing consistency pass (`FORK.md` Phase 8), run at the end of each arc and once over all | Phases 7, 8 | C3's flash if kept |

After that, new windows resume (Mail · SMS · Info · Notices, his order), each built once for every interface.

**What this order costs him, said plainly.** The first motion in daily use arrives with K's first window —
after C2 — not after the first flash as Phase 3 would have shown one slide. In between, C1's gains in daily
use are the larger cache and the compressed cache write. And S2, S3 and K do not depend on each other: which
goes first when more than one can start is his priority (R5).

## 5. Flashes: three defensible, down from six or seven

The fork read tested the idea that, since every feature is armed per session by a flag and the keeper holds
back after a reset, one image can carry several features armed and soaked one at a time.

| flash | carries | must be true first | its stop judges |
|---|---|---|---|
| **C1** | the motion engine; the cache limit 160 → 256 KiB; the largest-free-block figure; the deflated cache write; three small items the fork owes (the cache pointer's release, the context-survival check, the link's "already fast" test). Phase 5's cells and clock only if W4/W5's measurement says they pay | his `MOTION.md` rulings; a day on APK 0.65; the free-memory reading at three points of a start (a small APK change); the tick's task designed | lens match, the tick, battery; armed one step at a time, a soak each |
| **C2** | local input; the vertical-scale draw (`MOTION.md` §6 A2) | C1's numbers; the gesture-id and touch-processor reads | zero divergence over a work week; the local-latency target |
| **C3** (optional, last) | the offline home | its research; the ruling that it is still wanted | phone-off sessions |

**Where the idea is weaker than it sounds:** a flag isolates only code behind its check — the hook
pass-throughs, the telemetry walk and the link edits run from the moment of flashing. Local input's hook would
sit on the input path and run on every touch whether armed or not (inferred from `FIRMWARE.md` §6's planned
site; nothing of it is built), which is why it is not batched. The hold-back is coarse (it drops the whole
wish, and only for a restart within 120 s of arming), so soaking one feature at a time is a procedure on the
phone that C1's APK would have to carry. A restart is harder to attribute to one feature, and going back drops
everything C1 carries at once. `FORK.md` budgets two flashes for Phase 4 without recording why; C2 is planned
as one and may need a second. The host harness has no timers and one thread, so no vector proves the tick.

## 6. The interfaces

Their designs are step A's to make, with his refinery; what follows is only what the inventories support.

- **One web interface could serve three of the four.** Served by the owner's host, token-gated and reachable
  on his own networks only, as the replica is. A desk browser, a phone, and the kitchen TV — a webOS set with
  a browser and an IR remote according to his bus notes (an unchecked digest; to be confirmed at the TV).
  The phone app can embed the same pages.
- **XFCE4:** a panel item, desktop notifications, keyboard shortcuts, and the interface in its own window. A
  native rebuild of every window in a desktop toolkit would be a fifth codebase.
- **Rejected:** showing the composed glasses scene larger. It is nearly free — the mirrors do it — but it keeps
  the ring's three gestures, sixteen grey levels and the 640×480 layout, which is not "optimized for those
  other use-cases and inputs and outputs".
- The three mirrors stay as development views.

## 7. The bus system

- **Shape** (its explosion and refinery are step A's): live values that say how old they are; guarded actions
  with a confirm on every interface; alerts as notices on every interface and the phone. The bus's Home
  Assistant stays the control point; the remote path is the PC service, as found on 2026-10-19.
- **Not Damage's job:** timeouts, stops and interlocks belong in Home Assistant or on its relay board.
  Damage never exposes a raw relay.
- **An option, his call:** a watcher on the PC service raising Damage notices for a handful of bus
  conditions, so alerts reach his phone and glasses, could come before everything else. It needs its own
  Settings category for its toggles (`WINDOWS.md`'s rule), which the bus window later takes over.

## 8. His sittings, and the sizes

**Sittings, counted the same way for both.** `FORK.md` as written, then the interfaces and the bus: the
refinery; six or seven flashes with their stops; four verdict stops in Phase 6; the interfaces' and the bus's
refineries and verdicts — about seventeen. This re-plan: this ruling; two or three refinery and contract
sittings in A; C1; C2 (perhaps twice); two or three verdicts on the interfaces; the bus; Phase 6's verdict
stops; C3 if kept — about fourteen. **The saving in sittings is small, and most of it comes from R6–R8, which
would help the old order too. What this re-plan saves is the work between sittings** — the windows, the kit
and the verdicts each done once — and the calendar, because the software no longer queues behind flashes.

**Sizes** (relative, as `FORK.md` uses them; one calibration point: APK 0.65 was about 13 hours of unattended
build and about 66 weighted usage units for its agents): A is L, all documents. S1 is the largest step — the
core and nine windows, each on two screens. C1 is L, C2 XL, K XL, S2 L, S3 L or more. On this week's evidence
the whole is many builds the size of 0.65, and no total here would be worth trusting before step A's design
passes. Each step is sized (items, hours, usage) and put to him before it starts, and builds first what he
would see in daily use.

## 9. What stops now, and what is not lost

**Stops:** everything in §3 W7–W9; `MOTION.md` §6's sheet as written (its A2 moves to C2; its D1 falls away —
the first programs are the kit's, built in K).
**Kept as they are:** the transport, the compositor, the text cache, sync and persistence, the content
services, the journal and its tools, contract 2 on the glasses, the simulator and its 34 vectors, the engines.
**Already spent:** this week's hardening inside `MenuSurface`, `Notifications` and the shell's start-up is in
code that S1 and K replace; the rest of 0.64 and 0.65 is in layers that stay.

## 10. Risks of this re-plan

1. **A long design arc before any code.** It is his method, and it is where a wrong contract is cheapest to
   find; it is also several documents and sittings before anything visible changes.
2. **Remote views of an owner.** A window owned by the phone is stale on the TV when the phone is out of
   reach, and the reverse. Each window needs its owner, and where one exists its handover, ruled (as Feed's
   and Music's are today).
3. **The glasses presentation is touched twice in name:** moved behind the description in S1, rebuilt in K.
   The move is mechanical and is what proves the description complete; the rebuild is Phase 6's, which was
   always coming. If the move is not mechanical for a window, that window's S1 is bigger than sized.
4. **Bigger images.** §5's caveats; the image before C1 is kept as the way back.
5. **Motion comes later in daily use** (§4), and the glasses look the same for the whole of S1.
6. **Sizes are guesses** (§8).

## 11. Rulings asked

| # | ruling | the lead's recommendation |
|---|---|---|
| R1 | The split first: no window's glasses presentation is rebuilt before the app contract exists | yes |
| R2 | The four interfaces and the bus system get their explosion and refinery in step A, before the contract is fixed | yes — his method, and the only way "the windows are not touched again" can be true |
| R3 | One owner for each window's live state; every other interface a remote view | yes; the alternative is two live copies with last-write-wins, which loses an edit without saying so |
| R4 | Does each interface keep its own focus and cursor (the TV on the bus page while the glasses show Music), or do all follow one? Today the replica shares the glasses' session | his call; the lead leans to its own, with "continue here" as a deliberate action |
| R5 | The order of §4: S1 and the flashes side by side, the glasses kit once after C2 — and, where S2, S3 and K could all start, which first | yes to the order; the priority is his. The alternative for motion sooner: the kit's Main after C1, its verdict taken again after C2 |
| R6 | Flashes batched as C1 / C2 / C3 with step-at-a-time arming in the APK, accepting §5's weaker attribution | yes |
| R7 | Phase 5 (firmware-drawn cells, the silent clock): measure first — a day's chrome-only flushes from 0.65's journal, a silent hour without minute flushes — and build only what pays | yes |
| R8 | The offline home moves last and is decided again with the bus system in view | yes |
| R9 | Web first for the browser, the TV and the phone app; XFCE as a panel item, notifications, shortcuts and the web interface in a window | yes, subject to step A's refinery |
| R10 | Music on the other interfaces: the phone's remote everywhere (his verdict "the phone plays, never the PC" was made for the glasses window), or may the TV or the PC play? | his call |
| R11 | The early alert watcher for the bus (§7) | his call; it is small |
| R12 | Two numbers: the battery a workday of motion may cost (`FORK.md` D7; C1's stop judges against it), and the usage each step may take, set when it is sized | his numbers |
| R13 | After all of it, new windows resume in his recorded order | unchanged unless he says |

## 12. If he rules

`FORK.md` §5 is rewritten to the order he rules (its phases 3–8 mapped to the steps above), with §2's D3 (the
popover family built in Phase 6 — it becomes K's), principle 4 (one candidate flash per phase) and §6 (why the
stops sit where they do) amended to match; `CLAUDE.md`'s "Status and the order of work" paragraph is his to
change (the lines are proposed, not edited); `REMINDER.md`'s next-session list follows; `MOTION.md` §6 is
revised; `WINDOWS.md` and `DESIGN.md` §10 gain the app contract's pointer once step A writes it. Step A's
design passes are then sized and put to him before they start.

## 13. What the challenge changed

The first draft had the new glasses kit and a plain web page built before the first flash, the windows'
glasses verdicts taken before local input landed, the interfaces designed after the contract was fixed, and
two live cores left as an open risk. A reader set to find its faults showed that each of those repeated the
waste the re-plan exists to remove — a first web version rebuilt later, verdicts taken twice, a contract
fitted to one consumer, a kit built before the numbers that size it — and that the code already answers the
two-cores question (principle 3). It also corrected three statements: F2 (the limit is one shell with one
transport, not the glasses), W4 (the battery sample cannot say what the minute flushes cost), and the count of
sittings (§8, which the draft had as nine against sixteen).

## 14. Adam's rulings (2026-11-12, evening)

### 14.1 The rulings

| # | his ruling |
|---|---|
| R1, R2 | yes |
| R3 | yes, and further: **the owner is always the PC.** "I want one complete persistent system, with the various endpoints merely interacting with it" — a book read on the glasses continues on the phone and then on the PC from the same place, "from one central server, rather than three different apps being at three different places in the same book". "The PC is the main DamageWM system, and everything connects to it. My phone acts as a bridge to my glasses when away, and I do want to retain as much functionality as possible if I happen to lose connection to my home PC while away, or if my phone dies, but in optimal regular day to day usage, the PC should be the main system and control the state and serve it to the various endpoints." |
| R4 | as recommended: each interface keeps its own focus and cursor; "continue here" is a deliberate action |
| R5 | yes to the order; the priority is §14.3 answer 5 |
| R6, R7, R8 | yes |
| R10 | Music is wanted on any or all outputs: the PC's own (a TV and a soundbar), a pair of Bluetooth speakers, the phone and whatever it is connected to (earbuds, other devices). The kitchen TV is mounted portrait and has no audio of its own. A voice device comes back later, only once he has customized it |
| R11 | yes — the early alert watcher is built |
| R12 | the battery: **no limit.** "By default the system should be as nice as possible, but have various settings for power saving in case the default uses too much to get through a day." The usage figure was not answered; each step is still sized and put to him first |
| R9 | agreed (§14.3 answer 6) |
| R13 | "For now, yes" (§14.3 answer 7) |

### 14.2 What the rulings change in this document

- **Principle 3 becomes: the PC owns every window's state.** Not one owner chosen per window. This replaces, for
  the design to come, `HANDOFF.md` §19.1's rule 5 (two stamped stores, the most recent wins) and `MUSIC.md`
  §3.1's "playback truth lives on the phone"; today's code keeps both until S1 reaches them. §19.1's rules 1, 3
  and 4 (the phone drives the glasses; the phone alone when the PC is out of reach; the PC's own radio when the
  phone is away) are what his "bridge" and "retain as much functionality" sentences ask for; whether the phone
  keeps a working copy or becomes a relay is call 1.
- **R4 and his Reader example agree:** the place in a book, the queue, the open article are window state — one
  copy, on the PC, the same on every interface. What is per interface is which window is in front and where
  the cursor sits. Step A fixes that line for every field of every window.
- **§9's "kept as they are" loses sync:** the two-way stamped sync is replaced in S1 by changes sent to the PC
  and state sent back, with a queue on the phone for the time the PC is out of reach. Persistence, the
  transport, the compositor and the rest of that list stand.
- **Music:** `MUSIC.md` verdict "no PC audio output" was made for the glasses window and is superseded for the
  interfaces (R10). The PC holds the queue and the position; each output is a player.
- **`MOTION.md` §6:** its C3 (the battery number) is answered — no limit, with power-saving settings, which is
  what its C4 (a Global `Motion` row) provides. Its A and B groups are C1's firmware design and do not wait
  for step A.
- **The offline home (G)** now has a stated purpose: what the glasses still do when the phone is not there.

### 14.3 The follow-up calls, and his answers (2026-11-12, ~21:00)

1. **The phone keeps a working copy** — it runs the same windows, draws the glasses itself as today and follows
   the PC's record. Not a relay with the PC composing every frame (`--transport remote`, the arrangement his
   2026-08-31 correction retired). One mode: out of reach means the PC has not confirmed yet.
2. **Away from home with the PC out of reach:** Reader works fully (the whole shelf is kept on the phone — 73
   books, 50 MB); Music plays what is on the phone (the prefetched tracks, and playlists he pins); Main,
   Settings, Games and the phone's notifications work fully; Files, Torrents, Tmux and Feed show their last
   state with its age; changes made meanwhile go to the PC on return. His addition: when the tracks on the
   phone run out and home is still out of reach, Music goes to Spotify on the phone if the phone has internet —
   which is what is built (`PlayerCore.libraryStalled`; `MUSIC.md` verdicts 3, 20, 25; the way back is
   deliberate) and is carried over unchanged.
3. **Reach: his tailnet and the bus network only.** Never the public internet.
4. **Audio: one output at a time, chosen and moved.** "I will never intentionally output music to two sources
   at the same time in sync" — nothing is built to keep two outputs in step. Two players: the PC's own output,
   and the phone with whatever it is connected to (earbuds; the room speakers over its Bluetooth, which works
   today because Damage does not care where the phone sends its audio).
5. **Priority:** the alert watcher first; the glasses kit as soon as the second flash's numbers are in; while
   flashes are pending, the interface pieces before the bus system.
6. **R9 agreed:** one web interface for the browser, the TV and the phone app; XFCE as a panel item,
   notifications, shortcuts and that interface in a window.
7. **R13: "For now, yes"** — new windows after, in his recorded order.
8. **The PC is always on.** A nightly copy of its state goes to `/mnt/lilhomie`.
9. **The alert watcher: yes to all** — he places a Home Assistant token in the gitignored config; the PC
   service may read Home Assistant, read-only (it switches nothing); the first conditions are proposed from a
   read of its entities and edited by him; the phone raises "home unreachable" when it has internet and home
   does not answer (the PC cannot report its own silence).

### 14.4 Further answers (2026-11-12, ~21:50)

- **PC-direct BLE is a manual choice, never automatic.** The PC's Bluetooth is switched off on purpose: it kept
  connecting to the glasses again and again, and he almost never drives them from the PC. "I want the option to
  drive glasses from PC directly, but I don't want it automatic." This replaces `HANDOFF.md` §19.1's rule 4. As
  built, the service's standby still takes the glasses by itself when the phone looks absent for six probes
  (30 s) and both arms are seen (`desktop/Main.kt`, `StandbyPolicy.kt`; no setting turns it off — the count
  corrected on 11-12 from a read of the code) — the adapter being off is what holds it today.
- **Home Assistant's own configuration:** guarded actions need their time limits there. The lead writes those
  scripts as files; he reviews and applies them. Nothing in this project writes to Home Assistant.
- **One user.** Only Adam uses any interface or the bus network. A new device on the bus network is still
  approved once from a trusted interface (the lead's recommendation, not objected to).
- **The kitchen TV's job:** "a big infodump" — a dashboard of every bus system, the four camera feeds, graphs
  (energy use; the PC's load, usage and temperatures), and **reminders front and centre**: things to buy,
  things to do, calendar events.
- **`CLAUDE.md`** now names this file for the order of work and states the ruled topology beside what runs
  today (his go).
- **(~22:10) The PC's repeated connects, confirmed by him:** with the phone looking absent, the PC kept
  connecting to and dropping the glasses while they sat on the charger. That is the standby's takeover; the
  manual-only ruling above removes it.
- **(~22:10) Reminders — things to buy, things to do, calendar events — do not exist anywhere yet and are
  created in Damage:** designed in step A so the TV keeps their place front and centre, **built as the first
  new window**, once for every interface. He wants an always-on Claude Code session on the PC (stream-json)
  that keeps the lists populated and current, connected later to the voice system, which will run on the PC's
  own speech-to-text and text-to-speech stack (two earlier projects of his hold it; read in step A, read-only).
  The lead's design notes, for step A's sheet and not yet ruled: the lists live in Damage's state on the PC and
  the session edits them through a narrow set of operations, so the window works whenever the session is not
  available; the session wakes on his input and on a schedule rather than running in a loop; anything it asks
  of the bus goes through the same guarded path as a tap. `POPOVER.md`'s Claude path is the precedent to read.

## 15. Step A — the first refinery sheet (put to Adam 2026-11-13 and RULED the same morning: §15.1)

The explosions: **`INTERFACES.md`** (the web interface, the phone app's screen, XFCE4), **`BASTION.md`** (the
kitchen TV and the bus system) and **`REMINDERS.md`** (the lists, the helper, voice later) — 261 candidates
between them, written from six read-only research reports and then gone over by a challenging reader whose
corrections are applied. Their three draft sheets came to 58 rows; these nineteen are the ones that need him.
What the lead takes as a default unless he objects, and what waits for a later sitting (the app contract, the
glasses kit, the voice design), is in each document's "Decisions" section. Details of his own bus are in a
private annex kept out of this repository. **The recommendation column is the lead's; a ruling is Adam's.**
Answer by number: "yes to all", or "yes except …".

**A. Who may connect**

| # | decision | recommendation | why |
|---|---|---|---|
| A1 | Listener reach: the first build binds every listener to the reach he ruled — the PC on loopback, the tailnet and the bus network; the phone on loopback and the tailnet | yes | §14.3 answer 3; what today's code does is in the private annex |
| A2 | Device levels: every device is approved once and given a level — view, control or full. Full is needed for Tmux, file writes, bus actions and anything that reaches outside (the tracker, the helper, YouTube). His own phone and PC are full | yes | the tailnet says which device it is; approval says what it may do |
| A3 | The kitchen TV's level: read, acknowledge alerts, tick reminders, play and pause — no bus action, no Tmux | yes | a remote on a counter is not a confirm |

**B. The screens**

| # | decision | recommendation | why |
|---|---|---|---|
| B1 | Windows side by side on the desk, up to three; one at a time on the phone | yes | the split-view exclusion was about the glasses' panel |
| B2 | "Send to": from the screen he is at, put a window in front on another (the phone or the desk sending to the TV), beside "continue here" | yes | the TV has only a remote; sending to it is the easy way to steer it |
| B3 | A notice dismissed or acknowledged on one screen is gone on all of them | yes | one record on the PC |
| B4 | Tmux in a browser as a real terminal, for devices at level full | yes | it is what a keyboard is for; the largest single item on the web side |
| B5 | On big screens: a plain link from a Feed article to its original page; no video in Feed or Files | the link yes, video no | Feed's verdict 14 was made for the glasses; "we don't do video" stands |

**C. The kitchen TV**

| # | decision | recommendation | why |
|---|---|---|---|
| C1 | What runs the TV's screen is decided after a ten-minute check at the TV (the lead prepares one test page; `BASTION.md` holds the procedure). If its own browser fails the check: a small computer on its HDMI input (about $110–160, a reported range, and one more always-on device), the TV itself staying off the network | the check first; nothing bought yet | the vendor says a page cannot hold off the screensaver and the set does not rotate; whether that bites on this set is not known |
| C2 | The TV goes into use with the bus step, its reminders zone reserved from the start; the interface step builds what its host needs | yes | most of what he wants on it is the bus system |
| C3 | What may take the TV's screen over: an urgent alert until it is acknowledged; a camera when a person is seen, outside quiet hours | both | the two things worth interrupting a dashboard for |

**D. The bus system**

| # | decision | recommendation | why |
|---|---|---|---|
| D1 | (open since 2026-10-19) Bus action tiers: **A** one confirm; **B** filling and draining tanks, each only once its time-limit script exists in Home Assistant; **C** never from Damage. Every action, tier A included, runs a script there and is offered only once that script exists. The list of actions is in the private annex | as listed | §14.4: the time limits live in Home Assistant |
| D2 | Camera pictures come from the recording software already running on the PC, not through Home Assistant: stills on the TV, live video on the desk and the phone. The lead reads that software's restream settings (the file also holds camera passwords; none is printed or copied) | yes, and yes to the read | the pictures keep working while Home Assistant is down |
| D3 | Graph history: Damage samples the chosen values on the PC into day files (inside the nightly copy), filled once from Home Assistant's own history — rather than a longer keep configured there | the PC's store | Home Assistant keeps ten days of most values (his notes); the PC is the system |
| D4 | Alert severity: a rule is normal or urgent; an urgent alert repeats every ten minutes until acknowledged and passes quiet hours | yes | built into the watcher from the start |

**E. Reminders and the helper**

| # | decision | recommendation | why |
|---|---|---|---|
| E1 | Lists: Buy, Do and Calendar at first, more can be made; the calendar holds events created in Damage, no outside calendar | yes | none exists today; an outside one adds an account |
| E2 | The helper adds items and does what he asked without asking; an edit or a drop of something he wrote comes to him as a question | yes | cheap to undo where it acts alone; his own words stay his |
| E3 | The helper wakes on his messages and at a morning and an evening hour (skipped when nothing changed); a small model with a Settings row; scheduled wakes stop once his usage window is above 80 % | yes; the number is his | it draws on the same limits as his other work |
| E4 | The helper's login: its own configuration folder with a one-year token he creates once, not his own Claude Code login — and three short probe runs of the CLI first, to settle what the documentation leaves open | its own; yes to the probes | no clash with his sessions; his instruction files are never loaded into it |

**Asked of him outside the sheet:** the ten-minute visit to the TV (C1); `haUrl` and `haToken` in the gitignored
config for the alert watcher; his quiet hours and the alert thresholds, when the watcher's first rules are drafted
from the live entity list.

**The alert watcher's size, corrected:** its design pass (kept in the private folder with the research) comes to
about 1,800 lines of product code and as much again in tests — roughly half of APK 0.65's build, not the small
build the lead first told him. Its scope: the read-only reader and rules, alert state with acknowledge, the
glasses notice and the phone notification, a Settings category that exists before the bus window, "home
unreachable" judged from the phone's own network state, PC-direct Bluetooth made manual, the nightly copy, and
row A1's listener reach.

### 15.1 His answers (2026-11-13, ~09:35)

"Yes to all your recommendations except one... Never Haiku. That model sucks. Opus at Medium effort is the lowest
I'd go for anything."

- **All nineteen rows are ruled as recommended, with one change:**
- **E3, the helper's model: Claude Opus at medium effort, never Haiku.** The Settings row offers Opus at medium
  effort and above and nothing smaller. The wakes on his messages, the morning and evening hours and the 80 % stop
  stand. The lead's "small model" went against a choice of his already on record (Music's profile pass:
  "Haiku sucks, Opus with low thinking is better and quick") and should not have been recommended. What it changes
  in the design: a wake costs more of his usage window than the draft assumed, so the bound per wake, the daily
  wake cap and the skipped scheduled wake when nothing changed (`REMINDERS.md` H-05, H-16) carry more weight, and
  the three probe runs (E4) are made on Opus at medium effort so their figures are the ones the helper will have.
- **Not changed, and asked of him:** Music's Ask lane and its profile pass run Opus at LOW effort, his earlier
  choice for them. Whether the medium floor applies there too is his to say; it is two config keys
  (`musicClaudeModel`, `musicClaudeEffort`).
- **D2's read, made the same morning** (the recorder's settings read through a masking script; no password,
  address or host name printed or copied; the file itself holds none — it names a separate environment file, not
  opened): the recorder restreams all four cameras, each in a large and a small size, eight streams in all, and its
  own local interface for stills and detections answers on the PC alone. Camera pictures from the PC (D2) therefore
  need nothing new from the recorder. The details are in the private annex.
- **E4's probe runs** wait for the helper's own token (one `claude setup-token` run, his); **C1's check at the TV**
  and the watcher's `haUrl`/`haToken` are his as before.
- **Next: the contract sitting** — the app contract, `FIRMWARE.md` §5 and §6 from draft to contract, the sized
  build plan (§4 step A).
- **(late morning) Two more answers.** The hub's token: "Just use the tokens in the credentials file in the van/bus
  directory, no need for a new one" — the watcher reads the token from where he keeps it; no new user or token is
  asked of him. One read of the hub's entity list was made with it (read-only; kept in the private folder with what
  it showed); the first alert rules are drafted from it.
  And proportion: "Again this system is just me. Nobody else has access to my network or PC" — the access rules of
  rows A1 to A3 are built in their leanest form; nothing is added to protect read-only, low-value content; what the
  rules are for is keeping a stray request (a web page open in his own browser needs nobody else on his network)
  from typing into Tmux, writing a file or running a bus action. A note the lead had raised about low-value
  read-only content is withdrawn.
- **(~11:05) Three more.** `MOTION.md` §6's eleven open rows (A1, A3–A5, B1–B5, C1, C2), put to him again that
  morning: "I trust your recommendations for the open stuff" — ruled as recommended; the firmware contract
  sections are written on them. **Music stays at low effort:** "it won't need deep thought the way the bus systems
  might. If it makes a mistake on the music, no big deal. A mistake on bus control stuff can really matter
  though." So: never Haiku; how much thought a Claude path gets follows what a mistake would cost — low for
  Music, medium at least for the helper, and the most for anything that touches the bus. And the bus systems the
  hub does not report today (the power system, the appliances) he re-adds to the hub himself; until then he
  reads them in their own apps, and the watcher's first rules cover what the hub reports.
- **The app contract is written: `CONTRACT.md`** (the same day). Three read-only inventories of the windows as built,
  one draft, two challenging readers (41 findings, 19 of them must-fix), the lead's rulings with his words on
  proportion applied, one editor. Its three questions — Feed with the PC out of reach (the last state with its age;
  the phone's own fetching retires), Reader at the desk (Damage's own text, the place exact), next and previous on
  the TV's remote (yes) — are taken as recommended on his "I trust your recommendations for the open stuff", shown
  to him the same hour, and his to reverse. Still to come in step A: `FIRMWARE.md` §5 and §6 to contract, and the
  sized build plan.
