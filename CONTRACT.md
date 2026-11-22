# CONTRACT.md — the app contract: one description per window, every interface a presenter of it

**Context for the reader.** Damage is a personal window manager Adam wrote for his own Even Realities G2 glasses, on
firmware he installed with the published patch method; it runs on his own phone, PC and home network, reached only from
his tailnet and the network of the bus he lives in. This file is display and interface design: how each window
describes what it shows and can do, so that the glasses, a web page, the phone app's own screen, the kitchen TV and the
desktop each present it, and how every interface connects to the PC that holds the state. Plain wording throughout
(`CLAUDE.md`). Documents only; what S1-1 built (APK 0.67, `HANDOFF.md` §80) and what S1-2 built, as the cleanup L1
left both (APK 0.68, `HANDOFF.md` §84), and what S1-3 built (Feed and Tmux, APK 0.70, `HANDOFF.md` §89) is corrected
below as built. *2026-11-22:* S1-4's first half (Music) is built on a branch and not merged (`HANDOFF.md` §97); its
rows below still read as designed. S1-5 is not started.

**Status: IN FORCE for the build plan (2026-11-13; brought to the code after L1, 2026-11-17).** The three rows of §14
(a) are taken as recommended on Adam's word of that day ("I trust your recommendations for the open stuff") and were
shown to him the same hour; he can reverse any of them. L1's calls (the record ordered by input time, §2.4; the Reader
page following another device's place, §2.6; an upload never replacing a file, §9.4; inline styles allowed on the
pages, §9.5; the PC's own browser approved by itself, §10.3) are the lead's on his "full automation" word of
2026-11-17, listed for him in `HANDOFF.md` §84 as reversible; S1-3's four (typing into Tmux at level full, Tmux alerts
on the glasses only, Feed outside the TV's set, the phone's own Feed fetching retired) are listed in §89.5. Everything else is the lead's design, corrected after two
challenging readers, and stands unless he objects. `REPLAN.md` §14,
§15 and §15.1 are Adam's rulings and win wherever this differs; they are cited without the file name and always with a
row or answer (§14.1 R3, §14.3 #2, §15 A2); this file's own decisions are cited as §14 (a), (b) or (c). `DESIGN.md` §0
and each window's recorded verdicts are not re-opened. Grades: **read** (cites `file:line`: `S/` =
`core/src/main/kotlin/wm/damage/core/shell/`, `C/` = `core/…/core/`, `W/` = `C/windows/`, `D/` = the desktop module's
sources), **inferred**, **unknown**. Nothing here was measured on his devices.

---

## 1. Terms and scope

| term | meaning here |
|---|---|
| **window core** | a window's logic with no pixels: shared state, providers, actions, settings rows, notices, description. Pure Kotlin in `core/`; runs on the PC always, and on the phone as its working copy (§2.5) |
| **description** | the typed model a core publishes: levels, items with stable ids, documents, values, canvases, actions, settings, summary. Kotlin data classes; JSON on the wire. No pixel, line or height in it |
| **presenter** | the code that draws descriptions for one kind of interface and turns its input into view changes and operations: the glasses presenter (today's paint code, §11), the web client (one client for browser, TV and phone screen, §14.3 #6), the desktop companion |
| **interface** | one presenter instance with its own focus: the glasses (ONE interface, whichever device drives them), the phone app's screen, a browser's page on a device, the TV page, the companion. A **view** is one window shown in an interface (up to three on the desk, §15 B1) |
| **device** | an approved machine with a level (§10): his phone, his PC, a browser profile, the TV's host; plus `local` (the companion and the command line on the PC, level full, §10.6) and `helper` (a fixed set, §10.4) |
| **operation (op)** | one invocation of an action, carrying its result and the time of the input that made it. A queued op is named `(device, stream, n)`, an op that is never queued `(device, conn, k)`; the name only matches its answer (§2.4) |
| **owner** | the PC, for every window's shared state (§14.1 R3, §14.2). A **source** is an outside program a value comes from (tmux, the torrent client, the filesystem, the tracker, the hub, the active music player); a **holder** is the one interface running a leased item's engine (§2.7) |
| **working copy** | the phone's copy of every window's Damage-owned records, and of the away-list windows' whole state (§14.3 #1, #2): full cores fed by the PC's changes plus the phone's unconfirmed ops. A **mirror** is the phone's cached copy of what an outside program reports, with its age and no logic |
| **anchor / address** | a position every interface can resolve / a readable string naming a window and a place in it (§5) |

**Covered:** the vocabulary, actions and levels, ownership and the one merge rule, anchors and addresses, settings
scopes, the notice record, live values, the wire, access, and how the glasses keep today's picture through S1. **Left
to others:** how the glasses draw from descriptions (lists, documents, the popover family, staging, motion, local input,
the glasses' confirm for a bus action, `BASTION.md` B-28, and the reminder notice's gestures, `REMINDERS.md` L-25) is
the glasses kit (K); capture, push-to-talk and spoken replies are the voice design (reserved here: an interface kind
`voice`, and the confirm card as a question record, §3.2); everything below the glasses presenter is `FIRMWARE.md`.

## 2. The state model

### 2.1 Where each kind of state lives

| state | example | kept | written by | ordered by |
|---|---|---|---|---|
| Damage's own records | book places, the queue, read marks, flags, pins, recents, mutes, the Files clipboard, sort and hidden, lists, the bankroll | the PC record (§9.7); on the phone, the working copy — for EVERY window | any device's ops | the PC, by input time (§2.4) |
| source state | transfers, tmux sessions, folder listings, Feed items, the music catalog, hub values | the source; the PC's snapshot of it, versioned per scope outside the record's `seq` (§9.3); a mirror on the phone | the source; ops into it (§2.3 step 4) | the source |
| shared "most recent" and playback | the book read most recently, now playing, a Hold'em table's holder | the PC record | input-driven opens; the active player's host (§2.7); "continue here" | the PC |
| per-interface state | the window in front, recency, which item each view has open, each view's level path, cursor and scroll as anchors, layout, the TV's zones | the PC's interface table, one row per interface; the glasses' row mirrored on the driving device | that interface alone; "send to" is an instruction into its input queue (§2.6) | single writer |
| per-device settings | phone notifications, the TV's text size and night hours, theme | that device, reported to the PC (§6) | that device | single writer |
| glasses-scope settings | Size, Font, Depth, Cached text, Slide, Brightness | ONE record on the PC, mirrored to the driving device | the glasses' Settings or the web's Glasses category | the PC |
| per-output settings | volume, boost, hold, profile, lyric offset | the PC record, keyed by output name + kind | ops | the PC |
| live values | a terminal frame, the playback position, speeds, a sensor | not in the record: subscriptions (§8) | the source | the source |
| caches | book bytes, catalog, listings, renditions | each device; never authoritative | the fetching side | — |

### 2.2 The owner and the copies

The PC runs every window core on ONE core executor (today's single-writer rule, `S/WindowContract.kt:341-345`), started
with the service, glasses session or not (today window state advances only on a shell loop launched after the
transport, `S/Shell.kt:1062,1192`, and the standby PC builds no window, `D/Main.kt:701-703`). The phone runs the same
cores on its own executor, started with `ShellService`: whole for the away list; for the others, over their
Damage-owned records beside a mirror of the outside program (Tmux, Files, Torrents, Bastion; Feed per §14 (a) row 1).
Both sit behind one `WindowSource` — `desc` (immutable snapshots), `level(addr)`, `act(op)`, which returns at once
(the answer comes through the source's change flow), `live(src, need)` — so a presenter cannot tell which it has. A
rebuild after a large `snap` (the catalog, the shelf) is computed
off the executor and swapped in whole, so no apply waits behind it (today one loop does both, `S/Shell.kt:1390-1403`).

### 2.3 A gesture on the glasses: the first flush never crosses the network (`WINDOWS.md` §6.1)

1. **A view gesture** (scroll, back, a level change, a menu opening) changes only the glasses' interface state on the
   driving device; the presenter paints at once; the PC's interface table hears of it later, coalesced, never awaited.
2. **A change to Damage's own records, in any window** (tick, mark read, flag, a mute, the clipboard, a queue move, a
   setting; play and pause when the output is the phone's own player, §2.7) becomes an op: the phone applies it to the
   working copy marked pending and the presenter paints the result — the first flush. Then the writer thread appends
   the op's line to the queue file; the op goes to the PC once that line has landed. No disk write precedes a first flush.
3. **The PC** applies it or refuses it (§2.4), writes the op and its effect as one journal line (§9.7), then answers
   and publishes the change (§9.3). The phone's view is *the PC's state plus this device's unanswered ops*, folded by
   the same rule the PC applies (the window's own rule first, §2.4), so it shows what the PC will show; an op the state
   already shows leaves the waiting list, since an op that changed nothing publishes no change. Equal to what it
   showed: nothing repaints. Different (a later input of another device stood): a later flush repaints, and nothing is
   said. Another device's input never moves a glasses view he is reading (§2.6).
4. **An op into an outside program** (tmux, the torrent client, the filesystem, the tracker, the hub) cannot apply
   locally: the first flush draws the level change and the op cell's "sent" (today's `setOperation` cell); the change
   follows from the PC. With the PC out of reach (§9.3's paced ping) the action is dim and says "PC not answering since
   14:02"; nothing is queued. One mode, as ruled (§14.3 #1): "out of reach" only means the PC has not answered yet.

Taking a Hold'em lease (§2.7) is the one gesture whose result waits on the PC, and it is new. No timeout enters a
Bluetooth, render or input path: the ten-minute repeat, the paced ping and an approval request's life are pacing or
tied to a connection.

### 2.4 The one merge rule: the latest input wins

**An op carries its result, not its recipe, and `at`: the time of the input that made it (the click, the key, the
scroll that moved the place). The PC writes each target unless the item holds a later `at`; on an equal `at` the op that
arrives later wins; an item that already holds the op's value is left as it is (its `by` and `at` stay), so applying an
op twice changes nothing. Nothing is expected and nothing is said when an older input changes nothing: the targets
show the latest input, which may be another device's.** His words decide it ("whichever page I'm really on is the one
I'm really on", `CLAUDE.md`, "What is worth building"): a place read on the glasses while the PC was out of reach does
not overwrite a later place set at the desk. This settles `INTERFACES.md` §4.4, `BASTION.md` B-27 and `REMINDERS.md`
L-17. An op is `{device, stream, n` (or `conn, k`)`, window, action, targets, params?, result, at, iface}`.

- **Input time, on one clock.** The PC bounds `at` by its own clock (`min(at, PC now)`), so a device whose clock runs
  ahead cannot hold its items against later input. A page puts `at` on the PC's clock: `Date.now()` corrected by the
  offset `welcome`'s `pcClock` gives (a TV's clock is the one most likely wrong). The phone stamps a draft with its
  input's time, else its own clock; the glasses' Reader stamps a place with the time its save point first saw the place
  move (at most about 2 s after the scroll), kept beside the place so that it survives a restart; the PC's own sources
  (a notice's raise, Files' clear of a cut) stamp with the PC's clock. An op that carries no `at` takes its arrival time
  on the PC (on the phone, a queue line written by 0.67 takes the time it is loaded). Phone and PC keep their clocks by
  NTP. A device behind by D: its input on an item another device wrote less than D before does not apply (nothing is
  said). A device ahead by D: an input it queued offline and sends later can outrank a desk input made up to D after it.
- **Ids only match answers.** A queued op is `(device, stream, n)`: a *stream* (random 64-bit) is minted when a client
  store first exists (the phone's working copy, its app screen's ops included, §9.1; a browser's local storage; the
  companion; a helper run), and `n` counts from 1 (a page counts in memory). The PC keeps no numbers, checks no order
  and never answers a gap: an op that arrives twice changes nothing the second time.
- **Never queued.** Live, outbound and guarded ops carry the connection they were sent on, `(device, conn, k)`; the PC
  refuses one from an ended connection, and one whose connection ended or whose device was revoked while it waited its
  turn (Tmux's keys queue on one thread, S1-3); one left unanswered at its connection's end shows "not known whether
  it ran — see the state" and is never resent. Not built (declined in S1-3): a key that carries the prompt it was
  meant for — tmux takes input from every surface in arrival order, and the latest real input wins.
- **Result, not recipe.** An op carries what it made — the shuffled order, every created id as `<stream>.<n>.<k>`, the
  explicit ids marked read — so a replay makes the same state on any host (today shuffle is unseeded,
  `W/music/QueueEngine.kt:22-26,43-54`); Feed's mark all read writes the source's whole read list as it results (S1-3).
- **Coalescing** only into an op never sent; a sent op's body is frozen; an unsent place that takes the next place for
  the same item takes that input's time too.
- **A window's own rule** stands in for time where an item is not ordered by input: the notice record by occurrence,
  and a state that never moves backwards (§7.1); Files' clear of a cut applies only while the clipboard still names
  that cut. Not built, and of the same shape: a recurring reminder's tick names its occurrence and changes nothing once
  that occurrence is closed; a Hold'em decision or an economy write is taken only from the table's holder at the
  table's count (`W/games/holdem/HoldemTable.kt:138`), else refused, "the table moved on" (§2.7).

| kind | example | what it writes |
|---|---|---|
| register | a tick, read mark, flag, pin, mute, mode, a text, the chosen output, a volume, a setting; the book read most recently; a new item (a Buy item, a queue entry, an event: its id minted by the creating device); a whole ordered list (the queue's order, a clear, a replace) | the value; a text is replaced whole (no character merge) |
| position | a book place, an article anchor, a binge place — written only by input | the place; the phone folds an unsent position into the next one for the item |
| removal | to trash, a queue entry removed | the item `gone` — an entry with `by` and `at`, so an older edit that arrives later does not bring it back |

Each item keeps `by` and `at` of the write that stands. What another device's later input means for a view is the
presenter's: on the glasses Reader offers `Go there` for another device's newer place on the open book (the picture
does not otherwise change); the Reader page follows it (§2.6). Feed as built (S1-3): the glasses take the record's
article or archive place when they enter it, if it is newer than their own input there, and another device's move
never moves an open view; the Feed page follows another device's newer place as the Reader page does, and only his
scroll during a gesture moves its own (a click never does).

### 2.5 Away from the PC

The away list is ruled (§14.3 #2; `REMINDERS.md` F1): **Reader, Music, Main, Settings, Games, Reminders** run as
working copies; Files, Torrents and Tmux show their last state with its age; Feed per §14 (a) row 1. In every window
Damage's own records apply at once and queue (§2.3 step 2). The working copy holds each away-list window's whole shared
record, its cursor and its queue, on disk: Reader the whole shelf (a prefetch of every book; today a book is cached
when first opened, `C/content/Content.kt:546-591`); Music the catalog (its first snapshot's size an open fact, §14 (c)),
the prefetched tracks and the pinned playlists (not built). The away line (`INTERFACES.md` P-04: which windows are
live, the PC's age, "N changes waiting") is not built; the phone keeps its reach and the waiting count for the health
view (`REPLAN.md` §17.4 R-b). On return the phone takes a snapshot (§9.3) and sends its queue in order (§2.3 step 3). The
phone app's own screen works from the same copy (§9.1). A browser or the TV has no queue: last snapshot greyed, actions
dim. As built for the web: a `retry` is said in the page's words ("not applied: …; act again") and never resent by a
page; a place read while the PC was out of reach, or one whose connection ended before its answer (`ended`), is kept
unsent and goes at the next `welcome` with its own input time; a book's text stays readable (only its toolbar greys).

### 2.6 Shared, per interface; continue here and send to

Each interface keeps its own focus and cursor (§14.1 R4); the place in a book, the queue and the place in an article are
one copy (§14.2). The line, fixed here for every window (§12 gives each field):

- **What a screen has open is its own.** Each interface keeps which book, article, folder, session or torrent it has
  open; the place inside each item is ONE copy on the PC, written only by input (§2.4 position). A screen that opens
  Reader with nothing open starts at the book read most recently anywhere. Nothing changes what a screen shows without
  input on it — "continue here" and "send to" are the deliberate ways; on the glasses a view whose item's place moved
  elsewhere stays and offers `Go there`. **The Reader page follows** (the lead's call, L1; his to reverse): when another
  device's input moves the place of the book the page shows, the page goes there, with no offer, unless the page's own
  last input on that book is later; a move the page makes by itself sends nothing. As built for Reader: an entry (a
  session start, the switcher, a restore, an open) takes another device's place when it is newer than this view's own
  input on the book — never this device's own older one — and writes nothing back; on the glasses another device's
  newer place on the open book is offered as `Go there`, withdrawn once the record holds this device's own place; a
  bookmark this device set in a book that is not open is taken back from the record after a restart. A page stamps a
  place only with the time of his input on it (a jump, a chapter picked in the sidebar, a scroll of his), once, when it
  is made: a resize, a reflow, a screen waking, a follow or a restore never stamps and never sends. On the web a place in
  words is the percent of the book (the glasses say the page). Now playing is one playback, the same everywhere; a
  Hold'em table is held by one screen (§2.7).
  Entering by the switcher or a restore shows the open item at its shared place; from Main, the root
  (`S/WindowContract.kt:9-23`).
- **Per interface:** the window in front, recency, which item each view has open, each view's level path, cursor and
  scroll (anchors), sort and filter (Files' sort and hidden excepted: shared, `INTERFACES.md` §3), Settings' open
  category and adjust staging, drafts.
- **Only input counts as reading.** A commit, click or key that opens an item marks it and the app's notices read
  (today's activation rule, `S/Notifications.kt:317-320`, on every interface); a screen that changes without input
  never marks anything read or acknowledged, and nothing viewed on the TV marks anything read (§10.4).
- **Continue here** (`INTERFACES.md` W-09) and **send to** (W-10, §15 B2) need control. Continue here is an op from the
  receiving interface copying another's front view (and taking a Hold'em lease, §2.7). Send to is an instruction
  delivered into the target interface's input queue, applied there in order like a gesture and answered; refused with
  the reason when the target's device is below the window's minimum level or outside its set (Tmux to the TV) or not
  connected. Each `iface` report names the last instruction applied, so an older report never undoes a newer send-to.
  "Open on PC" (Files, Torrents) becomes send-to the desk companion, which has the display (today the headless service
  sets `DISPLAY` itself and a failure reaches only the log, `W/files/LocalFilesProvider.kt:360-377`).
- **The glasses are one interface:** row and settings one copy on the PC, mirrored to whichever device drives — the
  phone daily, the PC when he chooses PC-direct by hand (§14.4); today the row is `shell.state` (never synced).

### 2.7 PC-served windows, Music's players, Games' lease

- **PC-served (Tmux, Files, Torrents, Bastion):** the phone's mirror keeps the last description and its age on disk
  (Tmux keeps nothing on disk: its last list and frame are in the phone's memory, S1-3; a disk mirror is S1-5's); navigation inside cached
  data and Damage's own records are local (§2.3 step 2); ops into the program need the PC. In S1 the mirrors receive
  what they receive today, at today's paces (§8).
- **Music** (§14.1 R10, §14.3 #4). The PC holds the queue (entry ids minted by the adding device, replacing
  per-instance counters, `W/music/QueueEngine.kt:30,38`), the current entry, mode, playlists and the ONE chosen output.
  Two players, each a source: the PC's (mpv over its JSON IPC, `INTERFACES.md` W-43, new) and the phone's (ExoPlayer).
  **The active player's host alone advances the current entry and fills**, as ops (§2.4) (today
  both run in the player's core, `W/music/PlayerCore.kt:522-531,252-262`); a next or previous from another interface
  reaches that host through the PC. The PC re-anchors a play-next to the latest current entry the active player
  reported (today it inserts at `index + 1`, `W/music/QueueEngine.kt:79-84`) and says so on the item when it cannot.
  Play, pause, seek and volume apply locally and queue only when the output is this device's own player; otherwise they
  need the PC and are dim away. The active player reports `(entry, posMs, sampledAt, rate, state)`; viewers
  extrapolate. **A move of the output:** start B at p, stop A when B reports playing; B not connected → refused at once;
  B silent → "sent · waiting" while A plays. A restore never starts audio and a record never sets a device's volume
  (`W/music/PlayerCore.kt:635-648`). Away, the phone's player plays, then Spotify when the tracks run out, as built
  (§14.3 #2). On return, if the PC's record names another active output, the phone's player pauses and says "music
  moved to the PC output at 14:00" — never two outputs. Music on the PC's output keeps playing when the phone leaves. A playlist's save, rename and delete
  are ops on Damage's own records and queue like any other (§2.4: register and removal).
- **Games.** A Hold'em table is a lease: one holder interface runs the engine, the pacer and the background economy,
  which advance only while he plays there (`HOLDEM.md` verdict 27); for a browser holder the PC's core runs them, only
  while that view has focus (Kotlin in the window today, `W/games/GamesWindow.kt:1090-1100`). The economy's writes to
  characters and the bankroll are ops the table's own rule takes only from the holder (§2.4). "Continue here" takes the
  lease; when the holder is out of reach its confirm says what is dropped ("the phone has held this table since 14:00
  and is out of reach; its hands since then are dropped"). The holder's decisions append to the table's log on the PC,
  which serves every other viewer a projection (§3.4). A table started away is held by the device that started it from
  the start; away, the phone continues only a table it holds. The world seed is minted on the PC alone (today from the
  clock at first activation, `W/games/GamesWindow.kt:292-300`, so two devices can mint two worlds — inferred).

## 3. The description vocabulary

### 3.1 A window's description

| field | type | notes |
|---|---|---|
| `id`, `name`, `icon` | string, string, icon name | each presenter maps the name (the glasses to today's `IconKind`) |
| `title` | text | the short context line (`DESIGN.md` §4.1) |
| `minLevel` | view · control · full | the level a device needs to open the window at all (Tmux full, Files full), checked by the PC (§10.5) |
| `summary` | Summary (§3.2) | staleness as fields, not text (`BASTION.md` F18) |
| `attention` | `{count, reason}` or none | replaces `dirty` (`S/WindowContract.kt:47`) |
| `availability` | `live` · `stale{since}` · `unavailable{reason}` | replaces `needs` (`:63,200`) |
| `levels` | kinds `collection` · `document` · `canvas` · `form` · `actions` | instances fetched by address (§5); each names its `rest` row, the harmless cursor rest (`DESIGN.md` §1.7) |
| `current`, `settings`, `jobs`, `search` | anchor · rows (§6) · Job (§3.2) · index name | `current` is the shared item where one exists (the book read most recently, now playing); `jobs` replaces `setOperation`'s text; `search` serves `INTERFACES.md` W-05 |

### 3.2 Content kinds

| kind | fields | for |
|---|---|---|
| **item** | `id` (stable), `kind`, `title`, typed `fields` (§3.3), `icon`, `badges`, `progress`, `check` none · open · done, `dim{reason}`, `group`, `actions` (ids) | every row on every list |
| **collection** | `id`, item kind, `inline` items or `paged{count (or unknown: the tracker's listing is endless), version, sorts, filters}` queried by anchor, `groups[{id, title}]`, `empty` text | lists; the Music catalog (~3 k rows) and Feed items are paged |
| **section** | `id`, `title`, item ids | Overdue · Today · In the cart; Feed's sources |
| **document** | `id`, `extractor` version, `blocks[{id, kind: heading(level) · para · item(depth) · quote · mono · image · rule · table, spans[{text, marks: em · strong · code · link(href)}], charStart, charEnd}]`, `toc[{title, block}]`, `sourceUrl`; a `href` is http or https only and opens outside the app's page. As built for Reader (S1-1): a block's `level` is a heading's level or an item's depth; an image block names its blob in `ref`; an in-book link carries `jump` (an anchor at another spine document's start) instead of an `href`. As built for Feed (S1-3): `feed/item:<src>:<id>` answers `{item, document{id, extractor, sourceUrl, blocks}, extracted, note}`, its blocks Feed's own (heading, para, item, quote, mono, image; `level` 1 on headings and items; an image's `ref` `feed.<key>`), the text exactly the glasses' (spans laid over it, so saved offsets keep their meaning); an article cached before S1-3 is served as plain spans; `feed/comments:<src>:<id>` answers `{entries[{id, author, published, depth, title?, score?, blocks}]}` | Reader, Feed articles and comments, torrent pages (NFO = mono), Files' text and PDF text, Hold'em and Tmux history |
| **value** | `id`, `label`, number or text, `unit`, `system`, `band` normal · attention · alarm, `asOf` (source time), `receivedAt` (PC clock), `source`, `staleAfter`, `state` live · stale · lastKnown, `reason`, `corrected` | bus values, PC figures, speeds, a battery, per-source health |
| **series** | `key`, `span`, `step`, `unit`; points fetched by range; a gap is an explicit null; event marks from the action log | graphs (`BASTION.md` B-08, B-11…B-14) |
| **event** | `id`, `title`, `start` (instant or date), `end` or `length`, `allDay`, `zone`, `kind`, `ref` | calendar, detections, the action log |
| **job** | `id`, `label`, `state` queued · running · done · failed, `fraction?`, `phase`, `startedAt`, `updatedAt`, `answer` | a copy or `du`, a YouTube grab, pre-transcode, a rescan |
| **image / video** | `ref` (blob id), `mime`, `w`, `h`, `alt`, `takenAt`, `refresh`, `renditions` (§9.4); video adds `chain` live → composite → still | covers, comics, photos, cameras |
| **headline** | ranked `lines[{rank, spans, item, urgency}]`, `counts`, `asOf`, a zone claim `{zone front · glance · detail, size number · list · graph · image · wide, order, takeover none · urgent · detection (outside quiet hours, §15 C3), modes}` | the TV's zones, Main's lens (`BASTION.md` §8, `REMINDERS.md` §4); no pixel geometry |
| **summary** | `line` spans, `detail`, `more`, `progress`, `counts`, `asOf`, `availability` | Main's rows, the TV's tiles, the panel tooltip |
| **form** | `fields[param]` (§4.1), `submit` action | an item's editor, a parsed quick-add shown before keeping |
| **question** | `id`, `from` (a window or `helper`), `text`, `choices[{id, label}]`, `multi`, `text?`, `state` open · answered · withdrawn | the helper's asks (`REMINDERS.md` H-10), the voice confirm card later; never defaulted on a clock. A DECK (`POPOVER.md` §5.1; the helper's replies, H-09) is a document plus a question |

### 3.3 Typed fields

`text` (never cut; a presenter elides only with a mark and a reachable full view, `WINDOWS.md` §1) · `bytes` (B) ·
`rate` (B/s) · `duration` (ms) · `instant` (epoch ms, PC clock) and `date` (`YYYY-MM-DD`), with `zone` where wall time
matters (`REMINDERS.md` L-19) — each presenter formats "15:00" or "in 20 min" itself, so nothing is pushed each minute;
the phone reports its zone as a device value and the PC raises wall-time items in it · `count` · `fraction` (0..1) ·
`money` (whole dollars) · `rating` · `state` (a word from the window's list, with its band) · `check`, `bool` · `ref`
(an item id or an address). Where a source gives only text the field stays `text`: the tracker's sizes, dates and
account are scraped strings (`W/torrents/TorrentLeech.kt:267-350`); the code wins over `INTERFACES.md` §5's "typed
fields with units".

### 3.4 Canvases — typed models, one renderer per interface (`REPLAN.md` §4 principle 1)

| canvas | model | renderers |
|---|---|---|
| **terminal** | `session` (host, name). As built (S1-3): **frames** = the live source `live/tmux/frame/<host>/<name>`, the glasses' own capture as `{lines: [[{t, fg?, bg?, fl?}]], cols, rows, cursor{x, y, visible}, alternate, capturedAt}` (a colour an xterm index or `#rrggbb`, absent = default; `fl` bold 1 · dim 2 · underline 4 · reverse 8; logical lines for a normal pane, row-exact for a full-screen one; `Sgr.colourRuns` beside the glasses' gray parse, which is unchanged), at the one capture pace of the PC, only to a viewer at `focus`; **the stream** = the live source `live/tmux/stream/<host>/<name>`: one `tmux -C attach-session -t =<name>: -f ignore-size` client per session while a viewer holds it, `init{cols, rows, cursor, alternate, capturedAt, data}` (`data` the base64 of `capture-pane -p -e`), then the active pane's `%output` as `data` (base64), a fresh `init` on a pane, window or layout change, `end{reason, to?}` (`to` on a rename); the client writes only its init commands, never keys. `tmux/session:<host>:<name>` (size, screen kind, windows `{idx, name, active, bell}`), `tmux/history:<host>:<name>` (`{capturedAt, lines}`, 1,000 lines in colour); `keys` (the served quick keys and snippets); every Tmux scope and source at minimum level full | glasses: the polled frames through `FlowRender`/`TermRender` as before, gray. Web, devices at level full only (`TMUX.md` §1's "never a `-C` attach, never a terminal emulator" is amended FOR THE WEB by §15 B4): at a desk's width the stream into `@xterm/xterm` 6.0.0 (served by the PC from its class path, §9.5), its font scaled so the pane's columns fit — it never asks tmux for a size —, 5,000 lines of scrollback, its answers to a program's terminal queries and its focus reports never sent; a library that does not load leaves the frames with the reason; at a phone's width the frames, flowed in colour (a full-screen program as a grid) |
| **card table** | projected per viewer before it leaves the core (`W/games/kit/Seats.kt:52`, today never called): `handNo, actionCount, level, blinds, handsToLevel, button/SB/BB, seats[{id, name, human, stack, committed, contributed, lastAction, state, cards visible to this viewer}], board, pots with side-pot clauses, toAct, legal[{action, min, max, presets}], result, cashOutPending, revealStep`; the seed, other hands and the nine trait dials never leave (`HOLDEM.md` verdict 37) | glasses: `HoldemView` as today; web: a table by pointer; TV later |
| **now playing** | `form` library · external (Spotify: no queue, no seek, `W/music/PlayerCore.kt:220-298`); `entry`, `track{id, title, artist, album, durMs, art}`, position `(posMs, sampledAt, rate, state)`, `queue{index, length, mode}`, `output{id, name, kind, player}`, per-output `volume, boost, sleep`, `lyrics{ref, lines[{startMs, text}], offset, source}`, `viz` ref (`W/music/MusicModel.kt:340-422`), `pcAge` and `libraryHealth` as two values (one string carries both today, `W/music/MusicNet.kt:195` — inferred) | glasses: the card, lyrics, volume and Music Mode as today — the visualizer's place is Music Mode, glasses only; web: colour art, scrub, full synced lyrics; TV: a footer; companion: MPRIS |
| **comic** | `item`, `image` (the original, served as a blob since S1-3), `alt`, `bonus`, `num`, neighbours `{first, prev, random, next, latest}` each `{target}`, `{range: [lo, hi]}` or `{dim}`; as built `feed/comic:<src>:<id>` and `feed/num:<src>:<n>` (SMBC's random is one other strip chosen by this strip's id: a description carries no fresh pick) | glasses: strips as a named rendition (§9.4); web: the original, uninverted |
| **paged sequence** | `id`, entries `[{num, title, date, image}]` fetched around an anchor `(episode, fraction)`; as built `feed/binge:<src>/around:<ep>` answers `{total, first, last, entries[{num, title, date, image, state, reason?}]}`, 3 before and 5 after, an unknown episode's picture resolved inside the describe, one page fetch at a time | Feed's binge (neither one list nor one document) |
| **camera** | `ref` per camera, a `still` with `takenAt` and `refresh`, a `video chain` | TV: stills; desk and phone: live video (`BASTION.md` B-17, B-18) |

### 3.5 Where the documents and the code disagree — the code wins

A queue anchor names an entry id, not "track id + ms" (a track can be queued twice) · Reader's character offset is into
Damage's own extraction (`W/reader/Epub.kt:373-417`), so its anchor carries the extractor version and a quote (§5) ·
Feed's blocks dropped emphasis and links: S1-3 added spans, links and the source URL without changing the extracted
text (a golden file of every fixture page holds it), so saved offsets keep their meaning · an album is name + artist (today it filters by name,
`W/music/MusicWindow.kt:616,974`) · a per-output setting is keyed by name + kind, never Android's per-connection device
id (`W/music/MusicModel.kt:168-173`) · Settings' navigation is per interface (today it syncs,
`S/SettingsWindow.kt:363-367`) · `notifySms`/`notifyMail` have no row or producer (`S/ShellSettings.kt:123-124`): dropped.

## 4. Actions

### 4.1 An action

| field | notes |
|---|---|
| `id`, `label` | stable id; the window's label |
| `kind`, `level` | §4.2; the window may raise the level, never lower it, and never below its `minLevel` |
| `into` | `record` (Damage's own records) or `program` (an outside program: tmux, the torrent client, the filesystem, the tracker, the hub); decides queueing (§2.3) |
| `targets` | `one` or `many` of an item kind (`INTERFACES.md` W-20) |
| `params` | `text{initial, placeholder, multiline}` (always staged behind the action's confirm, `S/WindowContract.kt:185-191`; the one recorded exemption is Torrents' search, `TORRENTS.md` §3.1) · `choice{options[{id, label}]}` · `number{min, max, step, unit, presets}` · `file` (an upload, §9.4) · `ref` |
| `confirm` | §4.3, or none |
| `inverse` | the undoing action, for reversible kinds (Reminders' Undo now; `INTERFACES.md` W-19 later) |
| `queueable` | true only for reversible and irreversible ops `into` = `record`, in every window |
| `enabled` / `dim{reason}` | §4.4 |

### 4.2 Kinds and their levels

| kind | what it does | default level | queued away | examples |
|---|---|---|---|---|
| `view` | changes only this interface's own state | view | never sent | sort, filter, open a level |
| `reversible` | shared state, with an inverse | control | when `into` = `record` | tick, mark read, a queue move, to trash, mute; pause on this device's player; start a torrent (`program`: never) |
| `irreversible` | shared state, no inverse | control; full when it writes files on the PC | when `into` = `record` | clear the queue, sit down at a table; delete a torrent (`program`) |
| `outbound` | acts under his accounts, or brings content in | full (§15 A2) | never; carries its connection (§2.4) | tracker search and add, Feed's refresh and browse, YouTube grab, Ask, the helper |
| `guarded` | runs one allowlisted hub script, in two steps: a request, then a confirm (§4.3); carries its tier and the script's limit in words | full; tier B only once its time-limit script exists; tier C never offered (§15 D1) | never; carries its connection | the bus actions (S3) |
| `live` | input into a running program; not idempotent | full | never; carries its connection; refused while the PC is out of reach | Tmux keys, snippets, typed lines |

Reading effects — mark read, the book read most recently, dismiss a notice — are reversible register ops on Damage's own
records at level view, the one place a level sits below its kind's default: they are the reading itself, apply at once
and queue away (§2.3); the TV makes none (§10.4). On the web, `open` is sent on an input that opens a book (a card,
Continue, a link, back and forward), never on a page load, a reload, a restored tab or a reconnect; an address that
names a place shows it and saves it only when input routed there (S1-1). A stale value is never
the base of a guarded action: its confirm shows the target's present state with its age, and the action is dim past the
value's `staleAfter` (`INTERFACES.md` §6).

### 4.3 Confirms

`Confirm(title, body, act, cancel, double)`: Cancel is the rest and the act is never at a rest position (`DESIGN.md`
§1.7). **The window core words every confirm from its own data; a guarded action's text and target come from its
allowlist entry, never from a model** (`REMINDERS.md` H-19). **A guarded op is two steps on the PC:** a request — a tap,
the command line or the helper, which passes only an action id — makes the PC word the confirm from the allowlist entry
and show it on his interfaces; the confirm comes only from an interface with a screen at level full, so the command
line and the helper can raise it and never give it. In S1 the glasses keep today's confirm text and shapes (the act
behind a dim spacer, `W/files/FilesWindow.kt:974-980`, `W/torrents/TorrentsWindow.kt:697-706`; Tmux's at row 0, cursor
on cancel, `W/tmux/TmuxWindow.kt:654-670`); the popover family's CONFIRM replaces them in K (`POPOVER.md` §4.3). A
guarded op's confirm is void if its interface's connection ends before the act — nothing is sent on half a confirm
(`BASTION.md` §9; built with the guarded kind). Every other confirm stands: a queued kind's op applies locally, and on a
page an outbound or program act goes on the connection current at the act.

### 4.4 The answers, and dim

`Answer = Sent | Seen | Refused(reason, code)`. **Sent:** the PC has the op. **Seen:** for a core's own state, the op
is in the record and its targets show the latest input, which may be another device's (an older input changes nothing
and nothing is said: there is no other outcome for a record op that is taken); for an outside program, a later reading
that shows the effect, with "sent · waiting for the state 4 min" and the age shown meanwhile, no time limit and no
automatic repeat (`BASTION.md` B-27). **Refused:** the reason in words, to the interface that asked, never only to a log
— the level check at apply time included (§10.5); its codes: `retry` (not taken; the same op may go again; a page says
it and does not resend), `invalid` (an op that does not decode or is malformed), `unknown`, `unsupported`, `contract`,
`ended`, `level`, `program`. A never-queued op left unanswered at its connection's end shows "not known whether it ran —
see the state". Answers go to the asking interface only; today's `answer = true`
notices (`S/WindowContract.kt:270-274`) become answers. **Dim:** an action that cannot succeed is dim and says why on
focus — "the queue is empty", "needs level full on this device", "PC not answering since 14:02", "the glasses' session
is down", "no script of that name in the hub", "the table moved on"; a live row that does nothing would be a silent
failure (`WINDOWS.md` §1).

## 5. Anchors and addresses

An address is `<window>/<target>[@<anchor>]`; the web shows `/w/<window>/<target>` (`INTERFACES.md` W-04). Today's
deep-link targets (`book:`, `path:`, `session:<host>:<name>`, `t:`, `tl:`) are the seeds, made readable: each core
implements `resolve(target) → Resolved(levelPath, anchor) | Gone(reason)` and `format(anchor)`. Path parts are
percent-encoded. Cursors and scroll are stored only as anchors, never as indices or wrapped lines (`REPLAN.md` W9).

| window | targets | anchor |
|---|---|---|
| Reader | `book:<id>` | `@c<offset>.<extractorVersion>~<quote of 32 chars>`; the quote re-finds the place after an extractor change |
| Files | `path:<abs>` (a trailing `/` = folder) | the entry name |
| Tmux | `session:<host>:<name>` (a rename emits `renamed{from, to}` so cursors follow) | history line offset |
| Torrents | `t:<hash>`, `tl:<fid>`, `cat:<id>` | — |
| Music | `t:<trackId>`, `pl:<id>`, `q:<entryId>`, `yt:<job>`, `mode:music` | `@<ms>` on an entry |
| Games | `table`, `char:<id>` | — |
| Feed | `src:<id>`, `item:<src>:<id>`, `binge:<id>` | as built (S1-3) the record's `place` `{item, b, o, v}` (block, offset in the block's text, extractor version; for an image block the glasses' `o` is a strip index) and `binge.<src>` `{ep, f, s, w}` (episode, fraction; the glasses' strip `s` used only where `w` equals their width); a browsed source's id holds a `:`, so `item:<src>:<id>` splits at the last colon |
| Settings | `cat:<name>`, `row:<id>` | — |
| Reminders | `list:<id>`, `item:<id>`, `event:<id>`, `occ:<item>:<instant>` | — |
| Bastion | `sys:<kind>`, `alert:<rule>#<episode>`, `cam:<n>`, `series:<key>@<span>` | — |
| any | `notice:<id>` | — |

Identities: Reader's book id hashes the PC path (`C/content/Content.kt:94-97`) and a moved file keeps its records
through a content-hash alias recorded at rescan (§14 (b)); Files rows are folder + name; Tmux sessions (host, name);
Torrents the hash; Feed items the 16-hex hash of source + guid (`W/feed/FeedModel.kt:151`); queue entries
`<stream>.<n>.<k>`.

## 6. Settings

A row: `{id, label, category, scope, type choice · number · bool · text, options[{id, label}], value, level, tags}`.

| scope | one copy where | examples |
|---|---|---|
| shared | the PC record | every app's Notify rows, Feed fetch and keep, Music default mode and lyric sources, Tmux Alerts, quiet hours |
| device | that device, reported to the PC | Phone notifications and the phone's power rows, the TV's text size, night hours and power schedule (off by default), theme, Games' Confirm and Bot pace (`W/games/GamesWindow.kt:1543-1566`), Feed Images, a requested poll pace |
| glasses | one record on the PC, mirrored to the driving device | Brightness, Size, Depth, Presence, Head tracking, Long-press, fonts, Silent clock, Slide frames and fill, Cached text, Page staging, Link, each app's Font/Size/Style/Depth, Music Mode, visualizer, comic levels and inversion |
| output | the PC record, keyed by output name + kind | volume, boost, hold, quality, channels, normalization, prefetch, Spotify fallback, sleep, lyric offset |

- **The category rule stands:** each app's rows, its notification toggles included, live in its own category; of the
  toggles Global keeps only `Notify · Damage` and the APK-wide `Phone notifications` (`CLAUDE.md`; `WINDOWS.md` §1). Host categories
  exist before their window (Bastion's, built by the alert watcher) and the window later returns the same row ids, so
  nothing migrates. Glasses rows sit under a Glasses category and, on the glasses, in each app's category as today;
  device rows under the device's own category.
- **Rows that are actions become actions** with a kind and a confirm: Reset progress (`W/reader/ReaderWindow.kt:114`),
  Rescan, Pre-transcode, Update, and the Bastion category's `Acknowledge` row (built in W, APK 0.66: the glasses'
  presenter of `ack {id, episode}` until notices carry actions; it moves at S1-5). A setting change is an op of the
  register kind (§2.4).
- **Power-saving rows** (§14.1 R12: nice by default, power saving as settings) are ordinary rows tagged `power`, kept
  with what they slow — the glasses' under Glasses, a device's under that device, an app's (Tmux pace, Torrents poll,
  Feed images) under the app. A "Power saving" view lists every tagged row; every default is the nicer value.
- Today `shell.settings` mixes glasses rows with shared ones in one synced record (`S/ShellSettings.kt:13-131`) and each
  window keeps its rows in its whole-window blob; S1 splits both by scope (§9.7).

## 7. Notices

### 7.1 The one record, on the PC (§15 B3; `INTERFACES.md` §4.3)

As built (S1-2, trimmed in L1): every notice is an item of the pseudo-window `notices` in the PC record,
`rec.notices.<source>:<thread>`, its value `{occ (the occurrence, as text), raisedAt (an instant, the PC's clock; each
presenter formats it), app, source, body (fixed at raise, never cut), urgency NORMAL · URGENT, gatedBy (a category),
target (an address), state RAISED · READ · DISMISSED, heldUntil? (set when Quiet hours hold it, §7.3)}`. Its actions
are `raise` (the PC's alone, level full), `read` and `dismiss` (level view, from input on any interface). Identity is
(source, thread) as built (`S/Notifications.kt:178-201`): a new raise on the pair is the next occurrence and supersedes
the shown one. The notice record is ordered by occurrence, not time (the window's own rule, §2.4): a read or dismiss
carries its occurrence in its result and changes the record only on that occurrence, and never moves a state backwards
— a dismiss of an occurrence a newer raise replaced, or a read after a dismiss of the same occurrence, changes nothing
(never said). The watcher's (rule, episode) and Reminders' (item, scheduled instant) map onto it (`REMINDERS.md` L-06);
the watcher's alerts keep their own store and acknowledge (`ack {id, episode}`, §6) until S1-5 moves them onto this
record. An urgent repeat re-shows the same occurrence.

### 7.2 Life

The notice policy (gating, coalescing, read state) moves from `Notifications.kt` to the PC: `POPOVER.md` §4.2
("`Notifications.kt` keeps the POLICY") is amended so by this contract; only the presentation stays.

1. A core raises it on the PC, once — not once per window instance (as built: Torrents' in S1-2; Feed's and Tmux's in
   S1-3, from the engine's changes and from the status poll that runs with no viewer, so one raised while the phone
   was away arrives on its return). The shared app toggle named by `gatedBy` decides whether it is raised; Global `Notify · Damage` never gates a notice that names
   its app (`WINDOWS.md` §1).
2. Every interface reflects it (§7.3). **Read** comes only from input (§2.6); read and dismissed stay two states (seen
   versus dismissed is usage data the later agents need). **Dismissed** on one interface is gone on all — except a
   dismiss on the TV, which hides it on the TV only (§10.4). **Read and dismiss come only from input:** Silent Mode's
   5 s drop (`S/Shell.kt:2400-2404`, `S/Notifications.kt:298-303`) sends nothing.
3. Actions on a notice (not built: a reminder's `Done` and `Snooze 1 h`, an alert's `Acknowledge` once S1-5 moves the
   alerts here, `Open`) are ordinary ops of their window from the interface that pressed them, ordered by that window's
   rule. A button outside Damage (Android, the desktop) offers only reversible actions; anything with a confirm opens
   the app (`INTERFACES.md` §7).
4. Records leave the live table when dismissed; their history stays in day files (the Notices window later).

### 7.3 Delivery, urgency, quiet hours

Each interface decides only where a record pops, from per-device rows: the glasses show every raised record (in Silent
Mode the small form, `DESIGN.md` §1.5); the phone's shade per its `Phone ·` rows (beside "Glasses battery", not behind
Global `Phone notifications`); the desktop per its rows, urgent ones at critical urgency, every action also in the
companion's menu since buttons vanish under do-not-disturb (`INTERFACES.md` X-08, X-09); the TV a strip, and a takeover
by an urgent record until acknowledged and by a camera on a detection outside quiet hours (§15 C3). A client pops only
records raised after its **notice cursor** — a cursor of its own, apart from the record's `seq` and the sources'
versions (§9.3); on first contact it adopts and lists, never pops old ones (the Torrents rule,
`W/torrents/TorrentsNet.kt:229-247`). **Quiet hours** (one shared row, §6) hold `NORMAL` records and release them
together at the end; `URGENT` passes. As built in W (APK 0.66, the watcher's notices and "home unreachable" only): each
still-raised alert is released once, its text saying since when; a `NORMAL` clear inside the hours is dropped and its
phone notification cancelled silently; a phone restart releases at its first answer from the PC. **An urgent alert repeats every ten minutes until acknowledged** (§15 D4); a
repeat schedule on the PC is pacing, not a timeout. No rule depends on which interface he is using. A window's
recorded delivery verdict stands: Tmux alerts pop on the glasses only (`TMUX.md` verdict 3, "no phone fallback"), so
Tmux has no `Phone ·` row; as built (S1-3, the lead's call, `HANDOFF.md` §89.5) a page never pops a record of app
`tmux` — the Tmux page and its card show the waiting mark; whether they pop on the desk is asked when S2 builds the
desk's rows. **What a record carries is readable at level view**, so Tmux's says only "`<name>`[ · host] wants input",
never the pane's content; the glasses' presenter adds the pane's last line from the session it reads at full, and the
PC raises the record right after the status push that carries the waiting session. Feed's records say "N new · <source> · <newest title>"
(one per source); a first sight of a source raises nothing, and a browsed source that is not pinned raises none.

### 7.4 Raised on the phone

- **Phone-origin records** (glasses battery, route loss, home unreachable, a notice of a table the phone holds): created
  in the phone's copy with a deterministic id and raised on the PC when the wire is up (not built: today the record
  takes a raise from the PC only). "Home unreachable" is the phone's
  alone (§14.3 #9).
- **Records the PC would raise** (a reminder due, a working-copy window's event): at the due instant the phone raises
  any occurrence the PC has not confirmed as delivered to it, marked **"may repeat"** (`REMINDERS.md` L-44) — a
  schedule, not a timeout. The occurrence id is deterministic, so the PC's raise lands on the same record; the phone
  sends `delivered(id, occurrence, at)` and the PC does not deliver it again. A duplicate is said, never a miss (L-47).
  Every "PC out of reach" decision rests on §9.3's paced ping, never on a failed write (a half-open link goes unnoticed
  for "the hours TCP keepalive takes", `C/sync/SyncNet.kt:301-302`).
- Bus alerts are the PC's alone: the phone shows its last table with its age and offers no action (`BASTION.md` B-49).

## 8. Live values and viewers

Every value carries `asOf` and `receivedAt`; ages count on the PC's clock from first sight (`BASTION.md` §9); a client
learns the PC's clock offset at `welcome` and greys a value past `staleAfter` itself; a value whose source is out of
reach is `lastKnown` with the reason ("hub not answering 6 min"). No text carries an age baked in. Per-source health
("<host> quiet 40 s", "torrent client unreachable 12 s") is a value of its own, which carries the in-window health lines.

**Live only while viewed, for several viewers.** A viewer is an interface view showing a window or zone, with a need:
`focus` (in front, being worked in), `glance` (a dashboard zone or tile) or none. Each live source carries a minimum
level (Tmux frames and the terminal stream: full), checked at attach (§10.5), and runs at the pace of its most demanding
viewer, parking with none: Tmux captures frames only for sessions some view holds at `focus` (as built, at one pace
per PC — the glasses' Update row; a pace per session or per viewer is not built); Torrents polls fast for any
`focus` viewer, idle otherwise (replacing the shared `"remote"` focus key, `W/torrents/TorrentsNet.kt:55`); `glance`
asks only summaries. **In S1 the phone's mirrors receive what they receive today, at today's paces** (Torrents' idle
snapshots go on feeding the glasses' speed sparkline, `W/torrents/TorrentsWindow.kt:215,277-288`). A viewer ends with
its view or its connection — the socket's end, or §9.3's paced ping left unanswered when the next is due (one liveness
rule in both directions; never a failed write alone, which a half-open link can delay for hours). **The
glasses' own rule is unchanged:** a live window keeps the Bluetooth link busy only while it is the active window there
(`WINDOWS.md` §6.4). Engines that pace themselves go on with no viewer: Feed's fetch, the watcher's poll, and tmux's
status poll that raises alerts (`W/tmux/LocalTmuxProvider.kt:187-208`).

**As built (S1-3, Tmux's frames and stream):** a live source `live/<window>/<src>` is subscribed with a `need` (`focus`
or `none`; none given counts as `focus`), checked against the source's level at attach (refused `level`), and
detached by `unsub`, a need of `none`, the connection's end, a revoke, or the viewers' sweep — which tells the viewer
(`live{src, end{reason, again: true}}`), so the page says so and subscribes again; a live `sub` counts as the
connection's ping. Its messages are §9.2's `live`; a page that cannot keep up is ended `slow` by the outbox and comes
back to a fresh `init`. Taking and leaving a live scope is a `usage` note like a described scope's.

## 9. The wire between an interface and the PC

### 9.1 One protocol, two existing carriers

JSON messages, binary bodies by reference. **Pages** (browser, TV, the desk's app window) carry them over a WebSocket on
the PC's web interface listener — new, built on the hand-written HTTP/WebSocket server the replica uses
(`C/replica/ReplicaServer.kt`); it also serves the page bundle and blobs (§9.4, §9.5). **Kotlin peers** (the phone's
working copy and mirrors, the companion, the command line) use a new `app` lane of the content port's frames (4-byte
length + JSON + optional raw block, `C/content/Content.kt:130-320`), so the phone gains no WebSocket client. **The
phone app's screen** opens no socket: its page is served by the phone's own `WindowSource` (§2.2) through a message
bridge offered only to the bundle's own pages, and its ops join the phone's stream — no mixed content, no second
identity, and it works away from home. S1's web client takes its data behind that seam, so S2 keeps it.

### 9.2 Messages

| dir | message | fields |
|---|---|---|
| → | `hello` | on the `app` lane: the frame that asks for the lane, `{t: app, proto, stream, contracts}` (`proto` = `AppWire.PROTO`, 4 since S1-3), after the content port's own `proto: 1` hello (S1-1); credentials (§10), `contracts{window: version}`, `iface{kind, slot}`; a page's hello may carry `name` and `replaces`, and without a device secret it is answered `pending` |
| ← | `pending` | `code`: the waiting state of an unapproved page (§10.3) |
| ← | `approved` | `claim`: single use, redeemed by one HTTP request that sets the cookie (§10.3) |
| ← | `welcome` | `proto`, `device`, `level`, `set?` (§10.4), `conn`, `pcClock` (what a page uses to put its `at` on the PC's clock, §2.4), `epoch`, `seq`, `contracts{window: version}`, `bundle{hash}` (only on the web listener) |
| → | `sub` / `unsub` | `scope` (a window, a level address, a page of a collection, a live source; as built, a PC-served scope: `desc/<window>`, `<window>/<target>` (Reader's `shelf`, `folder:<path>`, `book:<id>`, `book:<id>/part:<n>`), `diag` (level full) — each answered `snap{scope, desc}`, outside the record's order; Feed's and Tmux's as in §3.2 and §3.4; a live source `live/<window>/<src>`, answered with `live` messages, §8), `need`; a record scope takes no cursor (every `sub` is answered with a snapshot, §9.3; one sent is passed over), a source scope may name its `version` |
| ← | `snap` | `scope`, `epoch`, `seq` or the source's `version`, `items{addr: {v` or `gone: true`, `by, at}}` (an item's `at` is the input time of the write that stands, §2.4) or the level; or, for a PC-served scope (`devices`, `desc/…`), `desc` with no `seq` |
| ← | `chg` | `scope`, `epoch`, `seq` (or `version`), `patches[{addr, set{v` or `gone`, `by, at}}]`; only an op that changed something publishes one |
| → / ← | `op` / `ans` | §2.4 / `stream` and `n`, or `conn` and `k`, or `scope` (a refused subscription); §4.4; `ans` carries `epoch` and `seq`, the op's own line in the record. An op that changed nothing publishes no `chg`, so a client never waits for a state at that `seq` before dropping its own drawn value: it reads the item (the phone's copy and the Files page do) |
| ← | `confirm` | a guarded request's confirm, worded on the PC: `id`, text, the target's present state with its age (§4.3) |
| ← | `instr` | a send-to for this interface: `id`, the view (§2.6) |
| → | `iface` | this interface's own state (§2.6), coalesced, naming the last `instr` applied |
| → / ← | `ping` / `pong` | `k`, on the wire's pacing (§9.3) |
| ← | `live` | `src` and one of `init` (the source's whole state now), `frame`, `data` (stream bytes, base64) or `end{reason, to?, again?}` (`to`: the new name on a rename; `again`: the PC let the viewer go and it may subscribe again); no numbering, gap or resend: one socket is ordered, and a new connection subscribes again and gets a fresh `init` (S1-3) |
| ← | `end` | `reason`: revoked · denied · replaced · shutting down · slow · proto · not open (`shutting down` is defined but no orderly stop sends it yet); a page ended `proto` reloads itself on the 2 s pacing, so a page left open across a deploy takes the new bundle by itself |

### 9.3 Snapshots, changes, reach

Every journal line on the PC takes the next `seq`, written with its op before it is answered or published (§9.7), so
it continues across restarts. The `epoch` names the record store: it is kept in `record.json`, minted only when the
store is created or a start finds no readable `record.json` (then `max(PC clock, old epoch + 1)`), never at a process
start. **Every `sub` is answered with a `snap`** of what the scope holds now, and a reconnect is a snapshot: the PC
keeps no ring of past changes and no sequence file, and publishes each change at once. **Source snapshots**
(transfers, sessions, listings, Feed items, the catalog) are versioned per scope (Torrents' `version` + `since`,
`W/torrents/TorrentsNet.kt:200-201`). **Notices** travel on a cursor of their own (§7.3).

- **A subscription:** `snap` per subscribed scope; the client takes `seq`, each version and the notice cursor, and pops
  nothing old. A change is sent per scope, and only when it holds an entry of that scope; a client ignores a `chg` at
  or below the `seq` its snapshot holds. A source scope whose version moved sends its snapshot.
- **While the record's log is closed** (a start whose log did not open, §13 (b)): the lane refuses a subscription to
  the record or a window's scope with the reason, keeps it, and serves it on the tick after the log opens.
- **Paged collections:** the snapshot carries `count` and `version`; changes patch the rows a client holds and bump the
  version; a client re-queries a page whose version moved.
- **Reach and pacing:** a client pings on the wire's pacing and the PC answers; a ping still unanswered when the next is
  due means the PC is out of reach, and every such decision rests on that, never on a failed write. Reconnects follow
  the existing `RemoteWin` shape (2 s, `C/net/WinNet.kt:198-271`); pages show "PC not answering since 14:02" over the
  greyed state — with the day when it is not today ("Nov 15 14:02") — and say when it is back. A page lets a socket go
  after a second ping unanswered in a row (its unanswered ops are answered "not known whether it ran") and makes a new
  one on the 2 s pacing. On the phone, a quick request on a persistent PC channel (Torrents' `snap` poll, Feed's
  `sources` poll, Music's `catalog` refresh, the watcher's `table` ask) with no answer for 60 s drops the connection,
  which reconnects and asks again; the 60 s count from the moment no older request of the same channel is pending
  before it. Files' long operations are served inline and are not bounded so.

### 9.4 Blobs and renditions

`GET /blob/<id>` on the web interface listener serves the original (book, cover, comic, photo, track, camera still, a
download) with Range, under the device secret. Every blob answer carries `X-Content-Type-Options: nosniff` and
`Content-Security-Policy: sandbox`, and `Content-Disposition: attachment` for any type outside a short list of image,
audio and video types (SVG is not on the list), so no file the PC serves runs script as the app's page. As built
(S1-1, Reader's covers and images only): a blob past the size served is answered 413 with the reason, one that cannot
be read 500 with the reason; `GET /w/…` answers the page; `GET /font/<face>-<weight>` serves the glasses' four faces
to an approved device.
`GET /blob/<id>?r=<rendition>` serves a form a presenter names — the glasses' strips, gray thumbnails, 4-bit art — made
on the PC by today's code (`W/feed/Strips.kt`, Files' `thumb`, `W/music/Art.kt:46-68`), so the glasses' picture and the
phone's CPU cost stay as they are. `PUT /blob/upload/<id>` (the single-use ticket the `upload` program op answered)
streams to a temp file and places it when complete; a cut-off upload or a full disk leaves nothing under the real name
and answers the uploader. **An upload never replaces a file** (the lead's call, L1): a name that exists is placed as the
first free of `name (1).ext`, `name (2).ext`, … (also when the name appears during the upload), and the `201 {name,
bytes}` answer names the file placed, which the page says; only when the name and its numbered forms up to (999) all
exist is it refused (409, "nothing was replaced"). The upload's socket probes after 60 s idle, every 15 s, 4 times, so
a body whose sender left the network ends in about two minutes ("the connection was lost after N of M bytes; nothing
was kept"). An upload is a Files job (§12); its ticket is ended with the job. The phone app's page asks the
phone's own source: a blob the phone holds (a book, a cover, a cached track) is served from its copy; one it does not
hold is fetched from the PC when reachable, and is otherwise shown as "not on the phone".

### 9.5 The page bundle (the phone app's cached pages, `INTERFACES.md` P-02)

The PC serves the web client as one bundle (named by its content `hash`), with a content security policy that allows no
inline script and allows inline styles (`style-src 'self' 'unsafe-inline'`, the lead's call, L1, so that a terminal
emulator can run on the Tmux page; scripts stay `'self'`). The bundle is served `no-cache`, so a page's reload takes the
deployed one. A page carries the protocol version it speaks (`proto.mjs` `PROTO`, equal to the PC's `AppWire.PROTO`,
tied by `BundleTest`: 4 since S1-3, which added `live`; 3 since L1); a PC that speaks another ends it `proto` and the
page reloads (§9.2). A phone on an older APK is ended the same way at its `hello`: it says the builds differ, asks
again at its slow pacing and reads on from its own copy until the matching APK is installed. The terminal library
(`@xterm/xterm` 6.0.0, MIT) is not in the bundle: the PC reads it from its class path (the WebJar) and serves exactly
`/vendor/xterm/xterm.mjs`, `xterm.css` and `LICENSE` with the bundle's headers; a host without it (the phone) answers
404 with the reason. Not built, for the phone app: the app keeps the last bundle it
fetched and a fallback set inside the APK. At `welcome`:
same hash → run it; different, PC reachable → fetch into a temp directory, swap whole, reload; PC unreachable, or the
cached set needs a protocol the app's bridge does not speak → the cached or the APK's set, and the away line says which
("pages from 11-02; the PC's are newer"). Renderer loss rebuilds the WebView (`onRenderProcessGone` returns true); the
cores and the glasses' session live in `ShellService`.

### 9.6 The phone's working copy on the same wire

The phone subscribes with scope `record` to every window's Damage-owned records and to each away-list window's whole
record, persists `snap` and changes, and sends its queue as ordinary `op`s after each `welcome`. The same carrier holds its mirrors (`glance`, or `focus` while shown; today's paces in
S1, §8), its `iface` reports for the glasses and the app screen, and its live subscriptions (Tmux frames, the PC
player's position).

### 9.7 What replaces today's stamped-store sync, and when

Today two stamped stores sync per key, last write wins, the loser refused with no word outside a 2 s tolerance
(`S/Persistence.kt:190-215`; `HANDOFF.md` §19.1 rule 5), retired for the design by §14.2; each store is one JSON file
written whole and asynchronously (`S/Persistence.kt:290-315`), 2 s after an input (`S/Shell.kt:2459-2462`). In S1:

1. **The first piece** builds the PC record (`record.json`, written by the record's saver only through `Persistence`'s
   atomic write; `{v, epoch, items, covered}`, keys `rec.<window>.<item>` holding `v` or `gone`, `by`, `at`) behind an
   **append-only operation journal** (`oplog.jsonl`): one line per op `{seq, op, eff, ans}`, written before the answer
   and before the change is published; the whole store saved from it on pacing (temp-and-move); a start replays only the
   lines past `covered` (a line's effect only), and `oplog.jsonl` is set aside whole at a process start once a save
   covers it and it is past 64 MiB or holds a bad line, never deleted. A 0.67 file is read as it stands (its `streams`,
   `colls`, `rev`, `na`, `hw`, `expect` and `base` passed over); `record.seq` is no longer read or written (left on
   disk). `record.json` and `devices.json` are in the nightly copy, `oplog.jsonl` never; the core executor, §9.2's
   wire, the device table, and the phone's working-copy store with its queue file (§2.3).
2. **Each window's conversion** takes its keys off the sync channel (`syncable()`, `S/Persistence.kt:335-336`, stops
   matching `window.<id>`). `hello` carries a contract version per window; while PC and phone differ for a window, Main
   and the window's status line say "PC and phone on different builds for Reader", and the window waits until both
   match (its subscriptions and ops are refused `contract`; each host runs it from its own copy). Nothing more is built
   for different builds, downgrades or deploy orders: a conversion is deployed as one of his sittings, the PC deploy and
   the APK install together. Reader's migration ran once on both hosts (S1-1) and is removed (L1). Torrents' and Files'
   rows from before their conversion are written once, on the PC, where the record holds none, from the PC store's
   synced `window.torrents` / `window.files` records, as ops of device `migration` stamped with the record's time (L1);
   no ledger, no phone half. Unconverted windows sync as today; nothing new is built into the sync channel.
3. **The last conversion** removes `SyncNet`, `RemoteSync` and the stamps; `shell.settings` is split by scope (§6) and
   `shell.state` is the glasses' interface row (§2.6). `SyncTest`, `SyncToleranceTest` and the stamp parts of
   `SubstrateTest` retire with what they pin, replaced by tests of §2.4.

## 10. Access — one model, stated once

**What it is for** (§15.1: "this system is just me"): keeping a stray request — above all content that arrives from
outside by itself (a web page open in his own browser, a fetched article, a downloaded file) — from typing into Tmux,
writing a file or running a bus action, and making the kitchen TV's fixed set hold. §15 A1–A3 in their leanest form:
nothing guards read-only, low-value content, and no new account, token or step is asked of him where one exists.

1. **A device is its secret.** On the web interface listener the device secret is the identity and the authorization.
   For a tailnet arrival the service asks the tailnet who the address is and shows that name on the device's approval
   row and in the devices list; it is never a condition — a failed lookup refuses nothing and re-classes nothing, and
   the row shows the address instead. A device has one level wherever it arrives from: the bus network carries full
   like the tailnet. On the bus network the pages are plain HTTP; that is accepted on his word. `TMUX.md` §4's
   "token-gated, Tailscale-only content seam" is amended by his reach ruling (§14.3 #3: the tailnet and the bus network).
2. **The device table** on the PC: `{id, name, kind, level, set?, tailnetName?, secretHash, approvedAt, approvedBy,
   lastSeen, revoked}`. A secret is 32 random bytes, stored only as its SHA-256, compared in constant time (two servers
   compare with `!=` today, `C/replica/ReplicaServer.kt:147`, `W/music/MediaServer.kt:128`). As built (S1-1): every
   credential compare is constant-time (`util/Secrets`); a name is kept printable and whole (no length limit). A
   `devices.json` that cannot be read or does not decode at a start is set aside as `devices.json.bad-<ms>`, said in the
   log and on the Devices page (when, and that browsers need approving again), and the table starts empty: every
   browser is approved again, and approvals are written. When the file cannot be moved aside, that is said and the next
   approval writes a new table over it. No state of the table waits on a restart.
3. **Approval, once, from a trusted interface** (§15 A2). An unknown device's page gets the waiting screen only:
   `pending{code}`, a short code in an unambiguous alphabet. A pending row with the same code and the tailnet name or
   the address appears on every full interface; he picks the row and a level (no typing), or denies it (`end{denied}`).
   Approval sends the waiting page `approved{claim}`, a single-use claim value, which the page redeems with one HTTP
   request whose answer sets the `HttpOnly; SameSite=Strict` cookie holding the device secret (an open socket cannot set
   an HttpOnly cookie; no `Secure` over plain HTTP), re-set on each visit so it never reaches Chromium's 400-day cap. A
   pending request lives while its connection is open — no cap, no queue, no timer (not a Bluetooth, render or input
   path, so the no-timeouts rule does not reach it). A Kotlin peer keeps its credential in its preferences, never in the
   build. **The PC's own browser needs no approval** (the lead's call, L1): a pairing request is approved at once at
   level full, recorded as `this PC's browser` (approved by `this PC's browser`, and noted in the journal like any
   device action), when all three hold: it arrived on the loopback interface; its `Host` names `localhost`, `127.0.0.1`
   or `::1`; and its `Origin` is `http://` + one of those names + `:` + the listener's own port. A foreign page in his
   browser cannot present that Origin, and a process on the PC is him. A page opened on the PC under the machine's name
   or a network address waits for approval as any other. **The first approval** of any other browser comes from that
   desk browser's Devices page, or from the command line (item 6). As built: the device row is written when the claim is
   redeemed, and approval keeps only the claim's hash; the redemption is `POST /pair/claim` and needs the PC's own
   Origin; a failed claim is paced like any failed connection (2 s), so the desk browser cannot re-pair in a tight loop
   when the device table cannot be written.
4. **Levels.** `view` reads, with the reading effects (§4.2); `control` adds reversible and irreversible actions inside
   Damage, "send to" and "continue here"; `full` adds Tmux, Files, Feed's fetches, guarded bus actions and anything that
   reaches outside (§15 A2). His phone and PC are full. A window's description and each live source carry a minimum
   level (§3.1, §8): Tmux full; Files full as a whole — listing, reading and writing alike, since reading any file on
   the PC is as strong as writing one. A device may carry a **fixed set** instead:
   - **the TV** (§15 A3), as operations: the views of Main's dashboard zones, Reminders, the bus pages and Music's card;
     acknowledge an alert; tick and untick a reminder; play and pause, next and previous (§14 (a) row 3). No Tmux, no Files, no bus action, no "send to" or "continue here", on any network. A dismiss on the TV hides
     the notice on the TV only; acknowledging an alert is the one TV action that reaches every screen (§15 A3, B3).
     Nothing viewed on the TV marks anything read.
   - **the helper:** the nine list operations (`REMINDERS.md` H-01), raising questions, a reply to his message (H-09)
     and a request for a guarded action by id (H-19), which raises the confirm and cannot give it (§4.3).
5. **Every request is checked.** On every HTTP request and WebSocket upgrade to the web interface listener, `Host` must
   be one of the PC's configured names or addresses, `Origin`, when present, the PC's own page origin (any page open in
   his browser could otherwise open a WebSocket to the PC; CORS does not cover WebSockets — inferred), and the device
   secret must be present — reads, subscriptions, blobs and the upgrade included. Then, on every listener, the PC checks
   every op, subscription, live attach and blob against the device's level or fixed set at apply time and answers
   `Refused` with the reason; a client's dim is a courtesy, never the check. As built (S1-1): `Host` is one of the
   configured names (`webHosts`), this machine's own name, `localhost`, or the arriving address, with the listener's
   port; `Origin` exactly `http://` + that Host.
6. **The local secret**, a file readable only by his user and written by the service, authorizes the companion and the
   `damage` command line on loopback as device `local`, level full, in the content hello's `local` field, taken on a
   loopback arrival only; loopback alone is not trusted. It keeps other users
   out and nothing more.
7. **The helper's endpoint** is loopback only: it checks `Host` as item 5 does, refuses any request carrying an `Origin`
   header, and takes the per-run bearer the service writes into the helper's MCP config only in a header. The service
   refuses the helper unless the CLI's stream-json start event lists only the list operations as its tools (H-01).
8. **Revocation** deletes the secret's hash and closes the device's connections with the reason (`end{revoked}`; the
   page says "this device was removed — approve it again"); its unsent ops are refused; a revoked phone keeps its copy
   and sends nothing. A re-approval from a device already listed offers to replace its old row (`end{replaced}`). As
   built (S1-1): replace = a waiting page naming the device id it remembers (`replaces`); a level change is a
   re-approval; a revoke or a replace ends the device's live connections at once. Each device action (approve, deny,
   rename, revoke) is noted in the PC's journal with who did it, what and the answer (a `devices` note).
9. **Listeners.** Every PC listener is bound or gated to loopback, the tailnet and the bus network, every phone listener
   to loopback and the tailnet (§15 A1); a gate checks the arriving interface before reading a byte (the setup page's
   pattern, `D/SetupServer.kt:21-33`) and journals a refusal; every socket the web listener holds has TCP keepalive (an
   upload's with short values, §9.4). **Never put a proxy on the PC in front of the web listener's port:** a proxy's
   request arrives on loopback, so the own-browser rule (item 3) could approve a remote client at full.
   **Built in W (APK 0.66, `HANDOFF.md` §79) for today's
   listeners on both hosts:** one gate (`core/…/net/ReachGate.kt`) on the accepted socket, a refusal closed unread and
   said once per source per minute (`reach` notes on the PC); `reachOpen` turns it off on the PC; its three known gaps
   are in `Reach`'s KDoc. The shared token stays on every path it gates today — nothing is rotated and no tool changes;
   the device secret gates the new web interface listener only:

| listener (role) | host | reach after §15 A1 (built in W for the rows that exist today) | credential |
|---|---|---|---|
| the content port: book, icon, tmux, sync and window lanes (`bastion` among them), and the new `app` lane | PC | loopback, tailnet, bus network | the shared token, as today (its holder is his phone, at full); on loopback the local secret for `local` |
| the music media endpoint | PC | loopback, tailnet, bus network | the shared token, as today |
| the setup page | PC | the gate's reach first, then its own tailnet-or-loopback check, as today | none for the page; the shared token on its APK route, as today |
| the browser replica, with its input, probe, journal and log routes | PC and phone | the host's reach | the shared token, as today (the tools carry it unchanged) |
| the transport seam and its status probe | phone | loopback, tailnet | the shared token, as today |
| the web interface: pages, WebSocket, blobs (built in S1-1: the port `webPort` names) | PC | loopback, tailnet, bus network | the device secret (items 1–5); the PC's own browser approved by itself (item 3) |
| the helper's endpoint (new) | PC | loopback | the per-run bearer (item 7) |

## 11. The glasses in step S1

### 11.1 How today's paint code sits behind a core

Each window class `X` becomes **`XCore`** (state, providers, actions, settings rows, notices, `describe()`, `resolve()`)
and **`XGlasses : DamageWindow`** (today's view state and paint code, unchanged). The shell still sees a `DamageWindow`,
so `Shell` keeps its constructor and members, and the 56 test files that build it (131 matches of `Shell(` under
`core/src/test`) stay as they are. `XGlasses` reads typed state from its `WindowSource` and turns input into view
changes or ops. Index cursors stay inside it for paint; it saves anchors through **one shared index-to-anchor helper**,
resolved once content arrives (as the pending-cursor fields do today). Menus are built from an item's actions in
today's order and labels, so `MenuSurface` draws what it draws today; the keyboard opens for a text parameter; notices
come from the PC record's mirror into today's `Notifications` presentation, whose policy has moved to §7 (`POPOVER.md`
§4.2 as amended, §7.2). **Beyond the description the core exposes** the typed objects themselves (`Transfer`,
`PlayerState`, `PaneFrame`, `HoldemTable.View`, `Epub.Book`, … — no JSON round trip on the phone), synchronous local
apply on a working copy (§2.3), named renditions (§9.4), and jobs and answers for the op cell and title notices;
fonts, wraps, heights, planes, styles and the texture cache stay in `XGlasses` and the shell.

**The judge.** `desktop --snapshot` before and after each window's conversion, every scene compared pixel for pixel with
today's renders, in two arrangements: the PC's, as today, and the phone's (the phone-side `WindowSource` over a loopback
PC core, the way `TorrentsTest` drives a remote provider through a loopback host); a difference is fixed or named in
the window's record (`REPLAN.md` §4, S1). With it `--selfcheck` (more than once), `OracleWalkTest`, the `--*-check`
harnesses, `tools/lint.py`, `tools/check_apk_wiring.py`, and the window tests, whose window constructors change (47
sites in 20 files under `core/src/test`: `ActivationTest`, `FeedWindowTest`, `FilesCacheTest`, `FilesTest`,
`GamesReview20260904Test`, `GamesWindowTest`, `MusicModeTest`, `MusicWindowTest`, `OracleWalkTest`,
`QueueWhileDownApiTest`, `QueueWhileDownTest`, `Review20260903Test`, `Review20260905Test`, `Review28Test`,
`Review29Test`, `Review30Test`, `ReviewRound1Test`, `SubstrateTest`, `TmuxTest`, `TorrentsTest`; and `D/Main.kt`,
`D/SelfCheck.kt`, `D/Snapshot.kt`). **Listed for Adam, not a gate the lead can run:** one live pass per converted
window through `tools/glassdrive.py` when he is there (`REMINDER.md`: every glassdrive line is his).

### 11.2 Built once — what S1 builds that something later replaces

| S1 builds | replaced by | how the double work is kept small |
|---|---|---|
| each `XGlasses` adapter | K's presenter for that window | adapters only; no new glasses behaviour in S1 (the picture must not change anyway) |
| the index-to-anchor resolution | K's list presenter | one shared helper for every glasses presenter, so K removes one thing |
| menus and confirms fed from actions into `MenuSurface` and today's confirm shapes | K's popover family (`POPOVER.md` §4) | the actions and confirm specs are final; only the drawing goes |
| glasses renditions as named blobs | K may keep or move them | today's code behind a name; nothing new |
| the sync channel, kept for unconverted windows | removed at S1's end | nothing new built into it, and no bridge between old and new keys (§9.7) |
| the watcher's phone half (`RemoteBastion`, its Android reconcile), built before S1 | the generic mirror and notice delivery | the watcher is built to this contract's rules where they are already fixed: its table, notification tags and `ack{id, episode}` as an action on the alert in §7.1's shape; its epoch kept in the alert store, never the process start (§9.3); the phone's rows (`Phone · alerts`, `Home unreachable`, and from package F `Presence · location`) in the phone's own preferences from the start (§6); the one shared Quiet hours row (§6), built once, in Global beside `Notify · Damage` — so S1 swaps the carrier and keeps the model (built in W, APK 0.66, `HANDOFF.md` §79) |
| the web client, window by window | S2 lays it out for the TV and the phone screen | one client rendering the vocabulary kind by kind, its data behind the `WindowSource` seam (§9.1); per-window code only for canvases |

### 11.3 Mapping today's contract to the new one (`S/WindowContract.kt`)

| today | stays with the glasses presenter | moves to the core | replaced by |
|---|---|---|---|
| `DamageWindow.id`, `name`, `title()`, `summary(): Summary` | | ✓ | `WindowDesc.id`, `name`, `title`; Summary with `asOf`, `counts`, `availability` (§3.2) |
| `icon: IconKind`, `dirty`, `needs: Set<Need>` | the drawn kind; the drawn tick | ✓ | an `icon` name, `attention`, `availability` (§3.1) |
| `view(): WindowView` | ✓ built from the source + view state | | |
| `styleTransform`, `styledText()`, `preferredHeight`, `contentPlanes()`, `onLayoutChanged()`, `onFontScaleChanged()`, `paintExclusive()`, `onExclusive()` | ✓ (`preferredHeight` becomes a glasses-scope row) | | |
| `appSettings(): List<HostSetting>` | `HostSetting` as the glasses' drawing of a row | ✓ | `SettingRow` with id and scope (§6) |
| `saveState()` / `restoreState()` | view state → the glasses' interface row, as anchors | shared state → the PC record | §2.1 |
| `restoreStateLive()` | | | `chg` applied to the source (§9.3) |
| `saveSubState()` / `restoreSubState()` | | ✓ | record items with `by` and `at` |
| `open(target)` | navigation to the resolved levels | `resolve(target)` | addresses (§5) |
| `onRegistered(ctx)` | ✓ once per session, as today | the core's services, once at core start (today every session start, `S/Shell.kt:928`) | |
| `onActivate(ctx, from)`, `onDeactivate()`, `back()`, `levelDepth()` | ✓ per interface (the level stack is per interface) | the viewer need (§8) | |
| `onTypedText(line)` | routes the line to the focused level's text action | the action, confirm-staged | a `text` parameter (§4.1) |
| `WindowView.ListView(rowCount, paintRow, paintLens, onCommit(index))` | ✓ rows from a collection, index → item id | | a commit is the item's default action, by id |
| `WindowView.DocView(lineCount, lineHeight, paintLine, onTap, stepLines, accel, contentKey)` | ✓ lines wrapped from the document's blocks | | |
| `WindowView.CanvasView(paint, onScroll, onTap)` | ✓ | | the canvas models (§3.4); scroll and tap map to its actions |
| `ListModel.cursor`, `DocModel.topLine` | ✓ for paint | | stored as item-id and document anchors, through the shared helper (§11.1) |
| `ShellServices.requestRender(w)` | ✓ for presenter-only effects | | the source's change flow |
| `setOperation(op)` | the op cell | | `jobs` and answers |
| `notifyInternal(…)` | | ✓ | `raise(Notice)` on the PC (§7); `answer = true` → an Answer (§4.4) |
| `openMenu(spec, owner)` / `openKeyboard(spec, owner)` | `MenuSurface` / `KeyboardSurface` until K | | the item's actions by id / a `text` parameter |
| `icons()`, `enterExclusive()`, `exitExclusive()`, `docContentWidth()`, `docContentHeight()` | ✓ | | |
| `openWindow(id, target)` | navigation | | an answer carrying an address |
| `runOnShell(action)` | the shell loop for paint state | the core executor for state | §2.2 |
| `runOnShell(key, action)` (held while no session, `HANDOFF.md` §72) | | | not needed: cores run without a session; the presenter reads the latest description |

## 12. Eleven windows on paper

Kinds **v** view · **r** reversible · **i** irreversible · **o** outbound · **g** guarded · **l** live; levels **C**, **F**
(view-level actions unmarked); ⏸ an op on Damage's own records — it applies at once and queues away, in any window (§2.3);
an op into an outside program is never queued; **min** the window's minimum level (§3.1); "glasses" rows are
glasses-scope (§6).

| window | owner · copy · away | levels · items | actions | settings | notices · health |
|---|---|---|---|---|---|
| **Main** | PC; working copy; away: the working copies' summaries and the PC windows' last ones with their ages — today's "unavailable offline" becomes the last state with its age, his ruling (§14.3 #2), not a regression | one collection: item `{window id, name, icon, summary, attention, availability}`, plus the Reminders headline (`INTERFACES.md` W-28); cursor = window id (an index today, `S/Shell.kt:6262`) | open (v) | Presence, rest, lens (glasses) | — |
| **Settings** | PC; working copy; away: every row | categories → rows → adjust (staging per interface); anchors `cat:`, `row:` | set a row (r, C; ⏸ for shared and glasses rows; device rows stay local); action rows become actions | — | — |
| **Reader** | PC, which parses books to serve blocks and percent (it serves bytes only today, `C/content/Content.kt:270-307`); working copy with the whole shelf; away: full | shelf (folders nest) → chapters → book (document) → actions; book `{id, title, author, bytes, folder, fraction}`, folder, chapter `{title, anchor}`; as built (S1-1), the PC's reader index (`reader-index.json`, a cache read one book at a time with no image bytes) gives each card its `fraction` and `cover`, and the book's level its chapters `{title, anchor}` | open (v; sets the book read most recently), the place (r, ⏸, position, level view as built; written only by input), bookmark here (r, C as built, ⏸; it gains a book id, `W/reader/ReaderWindow.kt:147,864-868`), jump (v), rescan (r), reset progress (i, C, ⏸, cancel first as built) | Scroll step, Scroll accel, Size (glasses) | answers only |
| **Tmux** | PC; **min F** (§15 A2); as built (S1-3) the record holds only `alerts` (absent = on), `mute.<host>/<name>`, `glasses.size` (absent = 480, 0 = global) and `glasses.context`; sessions, windows, host health and the served keys are the PC's source state; frames, the stream and history are live or one-shot (§3.4); the phone keeps the last list and frame in memory (no disk mirror: S1-5); away: that state with its age, keys dim with the reason, a mute applies and queues | sessions → live (terminal) ⇄ history → keys · snippets · windows · session actions; `desc/tmux` `{summary, sessions[{host, name, windows, attached, activity (PC ms), waiting, lastLine, muted}], hosts[{name, health{state ok · quiet · failing, error?, lastOkMs}}], keys{quick, snippets}, actions, settings, asOf}`; a device below full gets nothing of a session (Main's card, any level, carries the summary line: session names) | all F; program ops, never queued, on ONE thread per PC so they reach tmux in arrival order: `key{key}` (a served name), `snippet{snippet}`, `line{text}` (text then Enter; the typed-line confirm is the interface's), `raw{data}` (the desk terminal's bytes: printable runs `send-keys -l --`, the rest `-H`, one exec), `new` (targets `[host]`, answers `{name}`), `rename{text}` (answers `renamed{from, to}`), `end`, `select{window}`; every `send-keys` argument's final `;` escaped once for tmux and key names after `--`; record ops `mute` (r, ⏸) and `set` (the rows). Fit pane stays the glasses' | Alerts (shared), Context rows, Size (glasses); Update (the glasses' own, one capture pace per PC) | the waiting edge, raised once on the PC by the status poll that runs with no viewer, thread `host/session`, gated by Alerts and the mute; its body carries no pane content (§7.3); host health as a value (§8) |
| **Files** | PC; **min F** (§10.4); mirror (today's ≤200 listings); away: cached with age; the clipboard, sort and hidden apply and queue | locations → browse (paged) → view (document, or images) · trash; location `{path, label, kind, total, free}`, entry `{name, dir, size, mtime}`, trash entry `{id, name, origPath, size, at, missing?}` (`missing`: its file is not in the trash — a trash cut off before the file arrived; Restore refuses it with the reason, Delete forever clears it, and the page offers only Delete forever) | open, open in Reader (v; answers an address), open on the desk (send-to the companion), copy and cut (r, ⏸; the clipboard is shared), sort and hidden (r, ⏸; shared, `INTERFACES.md` §3), paste, rename, new folder, upload (jobs; never replaces, §9.4), to trash and restore (r), delete forever (i, double confirm), download (v), stats (a job). **One job at a time per PC**, with progress; a busy refusal names the job that holds the slot and, once its last progress is a minute old, "no progress for N min". **The supervisor** (on the PC's 2 s pacing tick) ends a job that writes nothing and has made no progress — an upload ticket whose body has not begun after 2 min (its ticket goes with it), a stats after 10 min — as failed, "ended by the supervisor: no progress for N min", and frees the slot and the surface that asked; **a job that moves, copies, trashes, restores, deletes or renames is never ended by it**. An upload's body bytes are its progress. **The trash folder** is written only by trash, restore and delete forever: rename, a move in or out, a copy into, a new folder and an upload at or under it are refused with the reason ("use Restore or Delete forever"); listing it is served. **Trash** writes its manifest row (forced to disk) before it moves the file; a failed move takes the row away again. A manifest that cannot be read refuses trash, restore and delete forever with the reason and is never overwritten or set aside. **A file moved across volumes** is copied to a hidden temporary name beside its destination, forced to disk, placed under its real name without replacing anything (a hard link that refuses an existing name), and only then is its source deleted; a failure deletes only what the move made. A single file's copy goes through the same temporary name. A folder copy makes its destination itself first, so its failure deletes only what it made. A file-system check that cannot tell whether a name exists refuses, never reads as "absent" | Size (glasses) | answers |
| **Torrents** | PC; mirror (the cached snapshot); away: snapshot with age, actions dim, its recents apply and queue | transfers (cursor follows the hash) → details · categories → listing (paged, count unknown) → torrent page (NFO mono) → add; transfer (23 typed fields, `W/torrents/TorrentsModel.kt:13-57`), tracker item, category, file | start, stop, recheck (r, C, many), delete keeping files (i, C, confirm), delete with files (i, F, double), search, browse, account (o, F; search unconfirmed, its recorded exemption, `TORRENTS.md` §3.1), add, add stopped (o, F, confirm), open in Files (v; dim below full), open on the desk (r, F; send-to; full because it opens a file with the desktop's own opener); a fast poll for any `focus` viewer, and in S1 the idle snapshots go on feeding the speed sparkline (§8) | Notify · done, · errors (shared), Poll (device), Size (glasses) | done and error, raised once on the PC, thread = hash; the torrent client's reach as a value (§8) |
| **Music** | PC; working copy (queue, catalog, prefetched and pinned tracks); away: the phone's player, then Spotify after the tracks run out | now playing (canvas) → queue, browse, artists, albums, vocab, playlists, folder, recent, results, YouTube, seek (the catalog paged) · info (document); queue entry `{entry id, track}`, track `{id, title, artist, album, durMs, genre, hasLyrics}`, artist, album (name + artist), playlist `{id, name, origin, adaptive, count, updatedAt}`, term, YouTube result | play, pause, seek, volume (r, C; ⏸ only when the output is this device's own player, else they need the PC), next, previous (r, C; the active player's host advances, §2.7), boost, sleep (r, C, the active output), move output (r, C; §2.7), add, play next, move, remove (r, C, ⏸; play next re-anchored by the PC), play now, replace, clear (i, C, ⏸; the op carries the whole order, one register value; confirms as built), playlist save, rename, delete (i, C; delete double), Ask, YouTube, lyrics search (o, F), lyric offset (r, per output), pre-transcode, rescan (r, jobs) | six Notify rows, default mode, lyric sources (shared); output rows (§6); visualizer, Music Mode, Size (glasses) | raised by the PC; the track notice shows on the glasses only when Music is not in front there, as built (`W/music/MusicWindow.kt:292-345`) |
| **Games** | PC; working copy; the table leased (§2.7); away: a table the phone holds | games → tables → table (canvas) · standings → character · bankroll · history (documents); table kind `{id, label, entry, fee, blinds}` (dim when unaffordable), character `{id, name, worth, state, career}` | sit down (i, C, ⏸, confirm; a table started away is held by the device that started it), check, fold, call, bet, raise, all-in (i, C, holder only, log kind, confirm per the device's Confirm row), deal, skip the pace (holder), the economy's character and bankroll writes (log kind, holder), cash out, refill (i, confirm), continue here (C; takes the lease; its confirm says what is dropped if the holder is out of reach) | Confirm, Bot pace, Deal animation (device); archetype labels, three Notify rows (shared); Size (glasses) | bust, won, return, raised by the holder |
| **Feed** | PC (one engine; the phone's own fetching retired in S1-3, §14 (a) row 1); the phone keeps what it loaded in memory (no disk cache: S1-5); away: that state under "PC unreachable since H:MM", reads, flags, pins and places apply and queue, refresh, browse and anything not loaded say "PC not answering" | sources → items (paged, 50 rows, cursor by id) → article, comments (documents, §3.2) · comic · binge (paged sequence) · actions · flagged; as built the record holds `read.<src>` (the source's read ids, the 600 newest, one register), `flag.<item>` (`{t, l, s, p, k, a}`, so a pruned item still lists), `seen.<src>`, `place` (`{item, b, o, v}`: the open article, block, offset, extractor version — one register), `binge.<src>` (`{ep, f, s, w}`; `s` the glasses' strip, used only at `w`), `pin.<src>`, `recent.reddit`, `recent.slashdot`; source `{id, kind, name, binge, transient, pinned, count, newCount, version, lastOkMs, lastFetchMs, health{state ok · limited · failing, host?, failLine?, failSinceMs?, retryAtMs?}, bingeTotal?}` | `read`, `seen`, `place`, `binge` (v; a read carries the time he opened the item; a place written only by input), mark read, unread, mark all read (C; each writes the source's whole list), flag, unflag, pin, unpin, `set` (C, ⏸), `recent` (F), refresh, browse (o, F, program ops, never queued), the original page's link (v, big screens, §15 B5; http or https only) | Fetch, Keep, three Notify rows (shared); Images (device); xkcd, SMBC and 8-Bit art, Comic levels, Size (glasses) | new items per source (§7.3), raised once by the PC's core from the engine's changes; a fetch in flight 5 min is ended, said on the source's health and tried again at its next due time; health per source, as values (§8) |
| **Bastion** | PC (the watcher now; pages, store, cameras and actions at S3); away: the last table with age, no bus action; an acknowledgement is Damage's own record — it applies on the phone (its repeats stop there), queues, names its episode, and changes nothing on return if that episode has ended | overview (worst first) → a system (values, series, actions) → a graph · cameras · alerts · action log; system `{kind, band, line, asOf}`, value (§3.2), alert `{rule, episode, state, severity, raisedAt}`, log entry `{at, device, iface, action, answer, stateSeen}`; canvas: camera; headline: the TV's bus strip | acknowledge (r; an action on the alert; in the TV's set), mode, test alert (r, C), each bus action (g, F: a request, then a confirm on a screen at full, §4.3; its tier and its script's limit in words; offered only once that script exists, §15 D1) | before the window, built by the watcher: Notify · alerts, · cleared (shared), Phone · alerts, Home unreachable (device: the phone's own preferences), Quiet hours (shared, §6) | alerts normal or urgent (§7.3), built-in watcher alerts, home unreachable (phone-origin) |
| **Reminders** | PC; working copy; away: full | Today · Buy · Do · Calendar and further lists → a list (Overdue, Today, later, In the cart) → an item (form) · Recently done · Changes (the helper's audit, H-03); item `{id, list, text, qty, note, due{instant or date, zone}, recurrence{rule, label}, state open · done · dropped · trash, source, severity}`, event `{id, title, start, end, allDay, lead}`, occurrence `{item, instant, state}`; headline: the TV's front zone, Main's lens | tick, untick (r, C, ⏸; a recurring item's names its occurrence, §2.4; in the TV's set), snooze (r, choice, ⏸; names its occurrence), edit (r, text, ⏸), move, drop to trash, restore (r, ⏸), add from text (parsed locally, shown before keeping, then a register op at an id the device mints, ⏸), undo a named op (r, ⏸), answer a question (C), message the helper (o, F); the helper is device `helper` with its fixed set (§10.4): its edits and drops of his items arrive as questions (§15 E2), its replies as a document plus a question (§3.2) | — | one per due occurrence with Done and Snooze; the phone raises any occurrence the PC has not confirmed delivered, "may repeat" (§7.4) |

## 13. The failure envelope

### 13 (a) Every case of the three documents, and the rule that answers it

| case (source) | answered by |
|---|---|
| PC out of reach: browser, TV (INTERFACES §6; BASTION §9) | §9.3 the paced ping decides it; greyed snapshot under "PC not answering since" (with the day when not today), two unanswered pings remake the socket, paced reconnect, a snapshot on return; a page refused `proto` after a deploy reloads itself; actions dim §4.4; no queue §2.5 |
| PC out of reach, phone app (INTERFACES §6; REMINDERS §5) | §2.5 the away list as working copies, every window's own records applying and queueing; the others' last state with age; the queue count; §2.4 on return; the app's screen on the same copy §9.1 |
| a queue edited in two places (INTERFACES §6) | §2.4 results not recipes, the latest input wins (a whole order is one register value), coalescing only unsent ops; §2.7 the active player's host advances, the PC re-anchors a play-next |
| a stale value, one stale value (all three) | §8 age on every value, that value greys alone past `staleAfter`; §4.2 never the base of a guarded action; due times are instants (§3.3) |
| a refused action, a refused bus action (all three) | §4.4 the reason to the asking interface (the confirm shows it; the action log keeps it); §10.5 the level checked at apply time; §2.4 an op applied twice changes nothing; live, outbound and guarded ops never resent, an unanswered one "not known whether it ran"; `Restore` on a dropped item |
| two interfaces at once (INTERFACES §6) | §2.4 the latest input wins, by input time on the PC's clock; §9.3 every interface sees the result; another device's input never moves a glasses view (§2.6) |
| an unclean PC stop; a restore from the nightly copy | §9.7 the journal line before every answer and publish, replayed past `covered` at the next start; every client takes a snapshot when it reconnects (§9.3) |
| PC and phone on different builds for a window | §9.7 the contract version per window: said on Main and the window's status line; the window waits until both match (they are deployed together) |
| never approved, or revoked (all three) | §10.3 the waiting screen only, a pending row on every full interface, alive while its connection is open; §10.8 its connections closed with the reason, the page says so |
| a page the device did not mean to trust (INTERFACES §6) | §10.5 Host, Origin and the device secret on every request, the level at apply time; §10.3 only a loopback page with the listener's own loopback origin approves itself; §9.4 blob headers; §3.2 links http or https only; §9.5 no inline script |
| the glasses' session down (INTERFACES §6) | §2.2 cores run without it; send-to the glasses dim with the reason; the panel icon says it |
| the companion, or the service under it, down (INTERFACES §6) | the companion is a Kotlin peer on §9.3's pacing, its icon grey; autostart restarts it only at login, said in its menu |
| WebSocket dropped and back (INTERFACES §6) | §9.3 a snapshot per scope; §7.3 the notice cursor: no old notice popped |
| the phone's renderer lost (INTERFACES §6) | §9.5 the WebView rebuilt; the glasses' session is in the service |
| cached pages older than the PC's (INTERFACES §6) | §9.5 the handshake; the away line says which set runs |
| a playback move that fails (INTERFACES §6) | §2.7 B starts at p and A stops only when B plays; B not connected → refused at once; B silent → "sent · waiting", A plays |
| a notification button after the state moved (INTERFACES §6) | §7.1 the op names its occurrence: on a superseded occurrence it changes nothing; a key at a known prompt carries its occurrence (§2.4) |
| an upload cut off, or the disk full (INTERFACES §6) | §9.4 temp file, nothing under the real name, never a replaced file; the error to the uploader; a sender gone mid-body ends in about 2 min; §12 the supervisor frees a ticket whose body never began |
| a Files job that stops making progress | §12 a read-only job is ended by the supervisor and says so; a job that writes his files is never ended, and its busy line says how long it has made no progress |
| a stop in the middle of a trash or a cross-volume move | §12 the trash's row is written first and listed `missing` until Delete forever; a moved file appears under its real name only whole, its source deleted only after |
| do-not-disturb on the desktop (INTERFACES §6) | §7.3 every notice action also in the companion's menu and the window |
| a web terminal attached (INTERFACES §6) | §3.4 `ignore-size` |
| the hub out of reach from the PC (BASTION §9) | §8 values `lastKnown` with the reason; §4.4 actions dim "hub not answering 6 min"; never queued (§4.2) |
| hub, network and PC all out of reach (BASTION §9) | §7.4 only the phone's "home unreachable" says it |
| an action whose state never changes (BASTION §9) | §4.4 "sent · no change 4 min" stays; the watcher's "action not confirmed" alert; no automatic repeat |
| the session drops mid-confirm (BASTION §9) | §4.3 a guarded op's confirm is void, "not sent"; a guarded op left unanswered is never resent (§2.4) |
| the TV's browser closes or faults (BASTION §9) | §8 its viewer ends; the device's `lastSeen` gives "TV not connected 3 min"; the host's watchdog is S2's |
| a camera source down; a gap in the store (BASTION §9) | §3.2 `takenAt` → "picture 5 min old"; a series gap is an explicit null, no line across it |
| an alert acknowledged elsewhere during a takeover (BASTION §9) | §7.2 one record; the takeover leaves on the change; a repeat is the same occurrence (§7.1) |
| hub and PC clocks disagree (BASTION §9) | §8 ages on the PC's clock from first sight |
| the helper down, limited or logged out (REMINDERS §5) | §12 Reminders never needs it; its status is a value; his messages wait as a visible queue (H-13) |
| the helper does something wrong, starts with more tools, or runs long (REMINDERS §5) | its ops carry device `helper` (§2.4), so Changes lists them with Undo, and its reach is its fixed set (§10.4); §10.7 checks the CLI's start event, said on its status; a bus action only as a request (§4.3); the per-wake bound (H-16) is outside this contract's paths |
| a recurring reminder ticked in two places (REMINDERS §5) | §2.4 the tick names its occurrence and changes nothing when it is no longer the open one (the window's own rule) |
| the glasses asleep at a due time (REMINDERS §5) | §7.3 the Silent Mode small form; the phone's shade carries it; §7.2 the Silent drop dismisses nothing |
| a stop mid-delivery (REMINDERS §5) | §7.4 raised by the phone at the due instant unless the PC confirmed delivery, "may repeat", on the deterministic occurrence |
| the bus crosses a time zone (REMINDERS §5) | §3.3 wall time plus zone, the phone's zone a device value, formatted by each presenter |
| voice hears nothing usable (REMINDERS §5) | the voice design; the confirm card is a question record (§3.2) |

### 13 (b) `FORK.md` §3.8's six questions for what this contract adds

(a) what clears it at a session boundary, a lapse, a restart, a rebuild · (b) what releases a wait if a write never
succeeds · (c) the guard's identity · (d) other tasks reaching it · (e) one read per decision · (f) siblings.

| new piece | (a) | (b) | (c) | (d) | (e) | (f) |
|---|---|---|---|---|---|---|
| op queue (phone) | no session, lease or link end clears it; an op leaves only on its answer (or once the state already shows it, §2.3 step 3); a lost store means a new stream, said; a queue file that cannot be read is kept aside as `queue.jsonl.bad-<ms>`, said, never overwritten | resent from the oldest unanswered on each `welcome`; no timer; after `Refused(retry)` the head goes again at the next paced ping, `contract` holds the window's ops until a `welcome` whose versions match; a refusal for good with no presenter is journaled; a resend changes nothing (§2.4) | `(device, stream, n)`; a new stream starts at 1 and cannot collide | the presenter paints, the writer thread appends, the wire task sends only landed lines | the next `n` read once per op | a page's stream (no file); the helper's run stream; the PC's journal |
| operation journal (PC) | survives restarts; a whole save of the store covers its lines, and a start replays only the lines past it (as built, the log is set aside whole at a start past 64 MiB or with a bad line, never deleted); an incomplete last line at a start is cut off and said | a failed append answers the op `Refused(retry)`, leaves no line and publishes nothing; the failure is said (log, `record` journal note, built-in alert `record.log`), and the client may send the same op again | `seq`, in the line | the core executor alone appends; the saver reads on pacing | one append per op | the phone's queue file; the alert store |
| interface rows | one per browser profile, interface and helper run; dropped on the device's revocation | — | `(device, iface)` | the executor | once per `hello` | the glasses' row on the driving device |
| working copy | survives APK restarts and stack rebuilds on disk; a new epoch → fresh snapshot, pending ops kept | §2.3 step 3 on every answer or change | item id + `by`/`at` | the core executor only; rebuilds computed off it and swapped in whole; presenters read immutable snapshots | one snapshot per paint | every window's own records; the away list whole; the mirrors |
| cursors: the record's `(epoch, seq)` from the last snapshot, a source's `version`, the notice cursor | persisted beside the copy; moved only by a later change or a snapshot | every `sub` is answered with a snapshot; a moved version sends its snapshot | the store's epoch, not a process start (§9.3); the notice cursor apart, so source churn never decides whether a notice pops | the wire's read task | once per `sub` | every client: pages, phone, companion |
| PC record and `seq` | survives restarts; a new epoch only for a new or unreadable `record.json` (§9.3) | a failed save is said and retried on the next pacing tick; the journal holds what it has not covered. A start whose log does not open or replay saves nothing and answers every op `retry`; the log is opened again on each pacing tick until it opens, its alert saying changes are refused until it opens; meanwhile the lane refuses a subscription to the record or a window's scope with the reason (the PC's own pages still served) and serves each one it refused on the tick after the log opens. A start with no readable `record.json` replays the logs set aside beside it first, oldest first, and raises `record.replay`; an unreadable `record.json` is kept as `.bad`, `.bad.2`, … | `rec.<window>.<item>` | the saver reads the journal; the wire reads published snapshots | once per op | the alert store |
| contract version per window | changes only with a deploy or an install; compared at every `hello` | a mismatch is said on Main and the window's status line until both match; no timer; the PC refuses a mismatched window's subscription and ops (`contract`; the op waits) and leaves it out of the `record` scope | `(window, version)` | the hello task; the executor reads the result | once per `hello` | the pages' `PROTO`; the old sync keys of unconverted windows |
| approval request | its connection's end, approve, deny | none: a person approves (the PC's own browser approves itself, §10.3); as built, a failed write of the table undoes the change and answers the caller; a table file that cannot be read is set aside and the table starts empty (§10.2) | the code shown on both screens | the web listener's threads; the device table under one lock | once per approval | the command line's approve; tailnet and bus-network arrivals alike |
| claim value | single use: void once redeemed, or when its waiting connection ends unredeemed (the approval is then dropped and the page asks again) | none: the page redeems it once | the claim, stored as its hash | the web listener's threads, under the device table's lock | once per redemption | the cookie it sets; the Kotlin peers' credentials |
| per-device secret | revocation or replacement only; the cookie re-set on each visit | — | its hash; constant-time compare | every request thread of the web listener | once per request | the cookie, the blob routes |
| minimum level and the level check | carried by each description and live source; changes only with a build | — | the device's level or set against the window's and the source's minimum | the executor (ops); the wire tasks (subscriptions, attaches, blobs) | the device's level read once per request | op, subscription, live attach, blob; send-to targets; the TV's set |
| confirm request (guarded) | confirmed or cancelled on any screen, or withdrawn by its requester; not kept across a PC restart (its requester is answered "not confirmed"); void on the showing screen's connection end | none: a person confirms; a hub script that never shows its effect stays "sent · waiting" (§4.4) | `(request id, allowlist entry)`, worded on the PC | the executor; every full screen shows it | the target's state read once when shown, with its age | a tap's request, the command line's, the helper's |
| paced ping | per connection; a new connection starts a new count | a ping unanswered when the next is due marks the PC out of reach; the next answer clears it (as built, a fresh `welcome` is an answer; the pong is answered through the core executor) | `(conn, k)` | the wire task alone | one decision per pacing tick | every client; the phone's reminder rule does not depend on it (§7.4) |
| send-to instruction | applied, refused, or its target not connected (refused, said) | answered from the target's report naming the last instruction applied | `(target iface, id)` | the target's input queue alone applies it | once | continue here; the glasses' row |
| notice record | `DISMISSED`; history to day files; occurrences persist across restarts | a record an interface did not show is in its next snapshot | `(id, occurrence)`; a stale occurrence or a state that would move backwards changes nothing; a repeat is the same occurrence | the PC's cores raise, interfaces read and dismiss from input — all through the executor; the phone's copy folds by the same rule | once per decision | phone-origin records, Android tags, desktop notification ids, the TV takeover |
| the Files slot and its supervisor (L1) | the job's own end, or the supervisor's; a restart starts with an empty slot and no tickets | the supervisor, on every 2 s tick, ends a read-only job or an unbegun upload ticket past its limit (§12); nothing ends a job that writes his files — its busy line says how long it has made no progress | the job id; the upload's single-use ticket id | each job on its own thread; the glasses', the phone's and the pages' callers; the upload's PUT thread (one atomic remove decides between the PUT and the supervisor) | the slot read once under its lock per tick | every Files writer; the upload ticket |
| the trash manifest and its rows (L1) | on disk, written whole through a forced temp file; a row whose file is not in the trash stays, listed `missing`, until Delete forever | an unreadable manifest refuses trash, restore and delete forever until it reads (fixed by hand); never overwritten or set aside (that would orphan what the trash holds) | the entry id, naming `trash/<id>/<name>` | every trash op holds the trash lock across its read, move and write | the manifest read once per op; the `missing` mark one check per row at each listing | restore's and delete forever's own order; the cross-volume move's temporary name |
| viewer count | its view's end or its connection's end | the paced ping unanswered when the next is due ends the connection | `(iface, view, src)` | the wire's tasks register; the source reads the maximum | the pace read once per tick | Tmux frames, Torrents poll, the music position, the TV's glance |
| Games lease | moved only by "continue here" | an absent holder's hands after a take are refused, said (its confirm said so) | `(table, holder)`, `actionCount` | the holder's executor alone advances the table and the economy | once per decision | the bankroll, world and character records the engine writes |
| page bundle cache (not built) | replaced whole after a complete swap | a failed fetch keeps the running set, said | `hash` | the app's fetch task; the WebView reads only a complete set | once per `welcome` | the APK's fallback set |
| local secret file | written by the service at its first start, created readable only by his user in one step; a file another user could read is refused (said), never rewritten; rewritten only by an explicit command (none built in S1-1); deleted under a running companion → its next `hello` is refused, said in its menu, and the service writes a new one at its next start | — | the file's contents, compared in constant time | the service writes; the companion and the command line read | once per connection | the helper's bearer |
| helper's bearer | one per run; cleared (its file removed, its hash dropped) when the run ends; only the current run's is accepted | — | the bearer, compared in constant time, in a header only | the service writes; the endpoint reads | once per request | the local secret |

## 14. Decisions

### 14 (a) Put to Adam — taken as recommended on his word of 2026-11-13 (`REPLAN.md` §15.1); his to reverse

| # | question | recommendation | why |
|---|---|---|---|
| 1 | Feed with the PC out of reach: only the PC's last state with its age (§14.3 #2), which retires the phone's own fetching (`FEED.md` verdict 15) — or the phone goes on fetching the public sites itself | the last state with its age — **built in S1-3** (APK 0.70, `HANDOFF.md` §89): the phone reads the PC's engine only; `PC loss` and `Back to PC` are gone | one engine, no second copy of read marks to merge, and the PC is always on and reached over the tailnet wherever the phone has internet. His words: verdict 15, "YES, for everything plain HTTP reaches: the engine is core Kotlin and runs on the phone whenever the PC is unreachable"; 11-12 (§14.3 #2), "Files, Torrents, Tmux and Feed show their last state with its age" |
| 2 | Reader at the desk: Damage's own text with emphasis, links and pictures, the place exact on every screen — or the book's own styling, closer to the printed look, the place approximate | Damage's own text | one coordinate for the place; the glasses already read that text |
| 3 | The kitchen TV's remote: next and previous track beside play and pause (§15 A3 lists play and pause; `BASTION.md` T-24 wants next) | yes | as harmless as pause, and the remote has the keys |

### 14 (b) Defaults taken (the alternative turned down after the dash)

- What a screen has open is its own; the place inside each item is one copy on the PC; a screen that opens Reader with
  nothing open starts at the book read most recently anywhere; nothing changes what a screen shows without input on it
  ("continue here" and "send to" are the deliberate ways), with one exception, the lead's call in L1: the Reader page
  follows another device's newer place on the book it shows (§2.6); now playing is one playback, the same everywhere
  (§14.1 R4; §2.6) — not a shared open item that every screen follows (a view would move while he reads).
- A Hold'em table is held by one screen at a time, taken by "continue here", whose confirm says what is dropped if the
  holder is out of reach; a table started away is held by the phone from the start (§2.7) — not merged logs (one
  tournament cannot be merged, and hidden cards stay hidden).
- Music on the PC's output keeps playing when the phone leaves (§14.3 #4) — not a move on leaving (no rule depends on
  where he is).
- Feed's refresh and browse at full (§15 A2: "anything that reaches outside") — not control. Files at full as a whole,
  outside the TV's set — not a level per action with a deny-list (reading any file is as strong as writing one).
- One level per device on either network, the bus network's plain-HTTP pages accepted on his word (§15.1) — not control
  and full held to the tailnet (its only threat is another person on his network).
- The shared token stays on every path it gates today; the device secret gates the new web interface only (§10.9) —
  not a rotation of the token and the tools (nothing he uses changes).
- The latest input wins, by the input's time bounded by the PC's clock; an older input changes nothing, unsaid (L1,
  the lead's call on his words, `HANDOFF.md` §84) — not arrival order (a place read on the glasses while the PC was out
  of reach would overwrite a later place set at the desk), and not expectations with loss notices (conflict machinery
  for one person who is in one place at a time).
- An op carries its result, created ids minted by the creating device as `<stream>.<n>.<k>` — not a recipe replayed on
  the PC, nor ids minted there (a remap on return).
- Queued or not is decided by what an op touches (Damage's own records, or an outside program) — not by the window (a
  mute or a read mark would wait a round trip).
- One core executor per host — not a thread per window (today's single-writer rule, no new races).
- The PC record in today's `Persistence` store, per-item keys, behind an append-only journal — not Postgres (outside the
  nightly copy).
- One protocol on two existing carriers — not a WebSocket client added to the phone. The phone screen's page served by
  the phone's own source — not by the PC through a relay (it works away, and its ops join the phone's one queue).
- Glasses renditions made on the PC by today's code — not on the phone (its CPU is a cost term).
- Only input marks read or dismisses — not display (the TV would read everything).
- One shared Quiet hours row, in Global beside `Notify · Damage` (the shell's notice policy, not an app's toggle; one
  more row than `CLAUDE.md` lists for Global, his to veto) — not one per app. Approval requests live with their connection — not on a timer. Approval
  by picking the pending row that carries the code — not by typing it (the leanest form, §15.1). The PC's own browser
  approves itself on loopback (L1); every other first approval comes from it or the command line — not trust on first
  use from any network.
- Torrents' delete keeping files at control — not full (no account, no file write).
- A moved book keeps its records through a content-hash alias — not a new book identity (every record would move).
- "Open on PC" as send-to the companion — not the headless service setting `DISPLAY` itself.
- The web terminal attaches with `ignore-size` — not `window-size manual` (a change to his tmux setup).
- Tmux quick keys stay unconfirmed on the glasses, as built — a change is K's verdict, not S1's.
- Music's Ask lane and profile pass stay on Opus at low effort (his word, 11-13: a mistake there is no big deal; how
  much thought a Claude path gets follows what a mistake would cost) — not the helper's medium floor.

### 14 (c) Open facts — a test at a device settles each

- The phone's WebView version and what a WebView costs beside the shell (at the phone; `HANDOFF.md` §71's memory history).
- Whether a desk browser and the phone reach the JVM directly on the tailnet, so who-is can name them on their rows (one
  connection each).
- Whether a browser terminal with `ignore-size` shows his session usably while a desk client is attached (one test).
- Whether the TV's browser keeps cookies across a power cycle and a WebSocket through its screensaver (the visit).
- Whether a fixed address for the PC on the bus network is set (his router's setting): without one, a changed address
  stops the TV's host-only cookie from travelling and the TV asks for approval again.
- Whether a due reminder fires on time from the phone's foreground service under Doze (at the phone).
- The phone's CPU per description change at Torrents' focused pace; the size of the Music catalog's first snapshot on
  the phone (the journal).
- The PC clock offset's error over a relayed tailnet path (at the phone); whether mpv resumes at the requested position
  closely enough for an output move (one run at the desk).
