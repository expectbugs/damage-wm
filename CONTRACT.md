# CONTRACT.md — the app contract: one description per window, every interface a presenter of it

**Context for the reader.** Damage is a personal window manager Adam wrote for his own Even Realities G2 glasses, on
firmware he installed with the published patch method; it runs on his own phone, PC and home network, reached only from
his tailnet and the network of the bus he lives in. This file is display and interface design: how each window
describes what it shows and can do, so that the glasses, a web page, the phone app's own screen, the kitchen TV and the
desktop each present it, and how every interface connects to the PC that holds the state. Plain wording throughout
(`CLAUDE.md`). Documents only: nothing here is built.

**Status: IN FORCE for the build plan (2026-11-13).** The three rows of §14 (a) are taken as recommended on Adam's
word of that day ("I trust your recommendations for the open stuff") and were shown to him the same hour; he can
reverse any of them. Everything else is the lead's design, corrected after two challenging readers, and stands
unless he objects. `REPLAN.md` §14,
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
| **operation (op)** | one invocation of an action. A queued op is named for ever by `(device, stream, n)`; an op that is never queued by `(device, conn, k)` (§2.4) |
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
| Damage's own records | book places, the queue, read marks, flags, pins, recents, mutes, the Files clipboard, sort and hidden, lists, the bankroll | the PC record (§9.7); on the phone, the working copy — for EVERY window | any device's ops | the PC, in arrival order (§2.4) |
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
Both sit behind one `WindowSource` — `desc` (immutable snapshots), `level(addr)`, `act(op) → Answer`, `live(src,
need)` — so a presenter cannot tell which it has. A rebuild after a large `snap` (the catalog, the shelf) is computed
off the executor and swapped in whole, so no apply waits behind it (today one loop does both, `S/Shell.kt:1390-1403`).

### 2.3 A gesture on the glasses: the first flush never crosses the network (`WINDOWS.md` §6.1)

1. **A view gesture** (scroll, back, a level change, a menu opening) changes only the glasses' interface state on the
   driving device; the presenter paints at once; the PC's interface table hears of it later, coalesced, never awaited.
2. **A change to Damage's own records, in any window** (tick, mark read, flag, a mute, the clipboard, a queue move, a
   setting; play and pause when the output is the phone's own player, §2.7) becomes an op: the phone applies it to the
   working copy marked pending and the presenter paints the result — the first flush. Then the writer thread appends
   the op's line to the queue file; the op goes to the PC once that line has landed. No disk write precedes a first flush.
3. **The PC** applies it, refuses it or records it lost (§2.4), writes the op and its effect as one journal line (§9.7),
   then answers and publishes the change with the op's origin (§9.3). The phone rebuilds its state as *the PC's state
   plus the ops still pending*, less those the high-water covers. Equal to what it showed: nothing repaints. Different:
   a later flush repaints and the difference is said — on the glasses in S1 by today's answer notice ("not applied:
   …"), elsewhere on the item (§2.4). A loss never moves a view he is reading (§2.6).
4. **An op into an outside program** (tmux, the torrent client, the filesystem, the tracker, the hub) cannot apply
   locally: the first flush draws the level change and the op cell's "sent" (today's `setOperation` cell); the change
   follows from the PC. With the PC out of reach (§9.3's paced ping) the action is dim and says "PC not answering since
   14:02"; nothing is queued. One mode, as ruled (§14.3 #1): "out of reach" only means the PC has not answered yet.

Taking a Hold'em lease (§2.7) is the one gesture whose result waits on the PC, and it is new. No timeout enters a
Bluetooth, render or input path: the ten-minute repeat, the paced ping and an approval request's life are pacing or
tied to a connection.

### 2.4 The one merge rule

**An op names what it expects to find, and carries its result, not its recipe. The PC applies queued ops in the order
they reach it. An op applies when the PC still holds what it expected, or already holds what the op would make (a
same-value op succeeds and changes nothing). Otherwise it loses, and the loss is said on the item — the standing value,
who set it and when, and an offer to apply the losing value as a new op. No op is ever applied twice.** This settles
`INTERFACES.md` §4.4, `BASTION.md` B-27 and `REMINDERS.md` L-17; no window has a variant. An op is `{device, stream, n`
(or `conn, k`)`, window, action, targets, params, result, expect, base (the device's cursor), iface}`.

- **Numbering.** A *stream* (random 64-bit) is minted when a client store first exists: the phone's working copy (its
  app screen's ops included, §9.1), a browser's local storage, the companion, each helper run. `n` starts at 1 and
  never repeats in a stream. The PC keeps per stream the highest `n` handled — the high-water, written in the op's
  journal line (§9.7) — and its last 1,000 answers; an op at or below it is answered from that record (or "already
  handled" with the current state) and never re-applied; a gap is refused "expected 8" and the client resends.
  `welcome` carries each stream's high-water, and a client drops the pending ops it covers before rebuilding.
- **Never queued.** Live, outbound and guarded ops take no place in a stream or its gap check: each carries the
  connection it was sent on, `(device, conn, k)`; the PC refuses one from an ended connection; one left unanswered at
  its connection's end shows "not known whether it ran — see the state" and is never resent. A key sent at a known
  prompt (a quick key under an alert) carries `(session, alert occurrence)`, refused once that occurrence is closed.
- **Result, not recipe.** An op carries what it made — the shuffled order, every created id as `<stream>.<n>.<k>`, the
  explicit ids marked read — so a replay makes the same state on any host (today shuffle is unseeded,
  `W/music/QueueEngine.kt:22-26,43-54`, and mark all read marks what the window holds, `W/feed/FeedWindow.kt:920-929`).
- **Coalescing** only into an op never sent; a sent op's body is frozen. **Order** is arrival at the PC's executor; no
  device clock orders anything (today's stamps, `S/Persistence.kt:320-330`, retire with sync, §9.7).

| kind | example | `expect` carries | loses when | what is shown |
|---|---|---|---|---|
| create | a Buy item, a queue entry, an event, a log line | nothing; each id `<stream>.<n>.<k>`, minted by the creating device | never (a replay is a no-op) | — |
| register | a tick, read mark, flag, pin, mute, mode, a text, the chosen output, a volume, a setting | the value the device saw; for a tick, untick or snooze of a recurring item, its occurrence | the PC holds a third value, or that occurrence is no longer the open one | "not applied — you: done (phone 13:50) · standing: open, unticked at the desk 14:02" or "done at the desk 14:02" · `Apply mine` · `Keep` |
| position | a book place, an article anchor, a binge place — written only by input | the position seen; an unsent position op coalesces with the next for the item, keeping the first `expect` | another device moved it | the view stays where he reads: "the desk read on to ch. 9 (14:02); this place, ch. 12, was not applied" · `Go there` |
| order | queue or list order | the item's revision seen | another device moved or removed it, or its anchor entry is gone | "not moved: the desk moved it at 14:02" |
| remove | to trash, a queue entry removed | the item's revision seen | another device wrote the item since | "not dropped: changed at the desk 14:02" · `Drop anyway` |
| whole collection | clear or replace the queue | the collection's revision seen | anything was added since | "not cleared: 3 added at the desk since 14:02" · `Apply anyway` |
| edit of a removed item | a tick on a dropped item | as register | the item is gone | "dropped at the desk 14:02" · `Restore` (`REMINDERS.md` §5) |
| log under a lease | a Hold'em decision; the economy's writes to characters and the bankroll | `(table, handNo, actionCount)` (`W/games/holdem/HoldemTable.kt:138`) | not the holder, or the count moved | refused, "the table moved on" |
| live, outbound, guarded | Tmux keys, a tracker add, a bus script | the connection; a key at a known prompt also `(session, occurrence)` | its connection ended; the prompt moved on | refused at the client, "not sent — PC not answering"; unanswered: "not known whether it ran — see the state" |

Each item keeps `rev` and `by` of its last write, so a device's own later ops never conflict with its earlier ones. A
text edit loses whole; there is no character merge. The "not applied" mark is part of the item's shared state, shown
on every interface and counted on the phone's away line (§2.5); the item's next write clears it, and a newer loss makes
a new one. After a lost position the view has been given the standing place, so its next input expects that and
applies: reading on never loses twice.

### 2.5 Away from the PC

The away list is ruled (§14.3 #2; `REMINDERS.md` F1): **Reader, Music, Main, Settings, Games, Reminders** run as
working copies; Files, Torrents and Tmux show their last state with its age; Feed per §14 (a) row 1. In every window
Damage's own records apply at once and queue (§2.3 step 2). The working copy holds each away-list window's whole shared
record, its cursor and its queue, on disk: Reader the whole shelf (a prefetch of every book; today a book is cached
when first opened, `C/content/Content.kt:546-591`); Music the catalog (its first snapshot's size an open fact, §14 (c)),
the prefetched tracks and the pinned playlists (not built). The away line (`INTERFACES.md` P-04) says which windows are
live, the PC's age, "N changes waiting", "N not applied". On return the phone resumes from its cursor (§9.3), takes the
PC's changes, sends its queue in order (§2.3 step 3). The phone app's own screen works from the same copy (§9.1). A
browser or the TV has no queue: last snapshot greyed, actions dim.

### 2.6 Shared, per interface; continue here and send to

Each interface keeps its own focus and cursor (§14.1 R4); the place in a book, the queue and the place in an article are
one copy (§14.2). The line, fixed here for every window (§12 gives each field):

- **What a screen has open is its own.** Each interface keeps which book, article, folder, session or torrent it has
  open; the place inside each item is ONE copy on the PC, written only by input (§2.4 position). A screen that opens
  Reader with nothing open starts at the book read most recently anywhere. Nothing changes what a screen shows without
  input on it — "continue here" and "send to" are the deliberate ways; a view whose item's place moved elsewhere stays
  and offers `Go there`. Now playing is one playback, the same everywhere; a Hold'em table is held by one screen (§2.7).
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
  (Tmux keeps nothing on disk today — inferred: no cache path in `W/tmux/TmuxNet.kt:193-199`); navigation inside cached
  data and Damage's own records are local (§2.3 step 2); ops into the program need the PC. In S1 the mirrors receive
  what they receive today, at today's paces (§8).
- **Music** (§14.1 R10, §14.3 #4). The PC holds the queue (entry ids minted by the adding device, replacing
  per-instance counters, `W/music/QueueEngine.kt:30,38`), the current entry, mode, playlists and the ONE chosen output.
  Two players, each a source: the PC's (mpv over its JSON IPC, `INTERFACES.md` W-43, new) and the phone's (ExoPlayer).
  **The active player's host alone advances the current entry and fills**, as ops expecting the entry it left (today
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
  are ops on Damage's own records and queue like any other (§2.4: create, register, remove).
- **Games.** A Hold'em table is a lease: one holder interface runs the engine, the pacer and the background economy,
  which advance only while he plays there (`HOLDEM.md` verdict 27); for a browser holder the PC's core runs them, only
  while that view has focus (Kotlin in the window today, `W/games/GamesWindow.kt:1090-1100`). The economy's writes to
  characters and the bankroll are log ops under the lease (§2.4). "Continue here" takes the lease (an op expecting the
  holder); when the holder is out of reach its confirm says what is dropped ("the phone has held this table since 14:00
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
| **item** | `id` (stable), `kind`, `title`, typed `fields` (§3.3), `icon`, `badges`, `progress`, `check` none · open · done, `dim{reason}`, `group`, `actions` (ids), `notApplied` (§2.4) | every row on every list |
| **collection** | `id`, item kind, `inline` items or `paged{count (or unknown: the tracker's listing is endless), version, sorts, filters}` queried by anchor, `groups[{id, title}]`, `empty` text, `rev` (whole-collection ops, §2.4) | lists; the Music catalog (~3 k rows) and Feed items are paged |
| **section** | `id`, `title`, item ids | Overdue · Today · In the cart; Feed's sources |
| **document** | `id`, `extractor` version, `blocks[{id, kind: heading(level) · para · item(depth) · quote · mono · image · rule · table, spans[{text, marks: em · strong · code · link(href)}], charStart, charEnd}]`, `toc[{title, block}]`, `sourceUrl`; a `href` is http or https only and opens outside the app's page | Reader, Feed articles and comments, torrent pages (NFO = mono), Files' text and PDF text, Hold'em and Tmux history |
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
| **terminal** | `session` (host, name); two feeds — `snapshot` = `PaneFrame(lines as SGR runs with COLOUR INDICES, cols, rows, cursor, cursorVisible, alternate, capturedAt)` (`W/tmux/TmuxModel.kt:61-73`) and `stream` = tmux control-mode output for one session; `history` (a document of lines); `keys` (the served quick keys and snippets); both feeds at minimum level full | glasses: the polled snapshot through `FlowRender`/`TermRender` as built, the gray mapping moved out of the parser (`W/tmux/Sgr.kt:210-239`) into this renderer. Web: the stream into a terminal emulator, devices at level full only — `TMUX.md` §1's "never a `-C` control-mode attach, never a terminal emulator" (`TMUX.md`:67-68) is amended FOR THE WEB by §15 B4 — attached with the client flag `ignore-size` so the browser never resizes his session (read: `man tmux`, tmux 3.5a) |
| **card table** | projected per viewer before it leaves the core (`W/games/kit/Seats.kt:52`, today never called): `handNo, actionCount, level, blinds, handsToLevel, button/SB/BB, seats[{id, name, human, stack, committed, contributed, lastAction, state, cards visible to this viewer}], board, pots with side-pot clauses, toAct, legal[{action, min, max, presets}], result, cashOutPending, revealStep`; the seed, other hands and the nine trait dials never leave (`HOLDEM.md` verdict 37) | glasses: `HoldemView` as today; web: a table by pointer; TV later |
| **now playing** | `form` library · external (Spotify: no queue, no seek, `W/music/PlayerCore.kt:220-298`); `entry`, `track{id, title, artist, album, durMs, art}`, position `(posMs, sampledAt, rate, state)`, `queue{index, length, mode}`, `output{id, name, kind, player}`, per-output `volume, boost, sleep`, `lyrics{ref, lines[{startMs, text}], offset, source}`, `viz` ref (`W/music/MusicModel.kt:340-422`), `pcAge` and `libraryHealth` as two values (one string carries both today, `W/music/MusicNet.kt:195` — inferred) | glasses: the card, lyrics, volume and Music Mode as today — the visualizer's place is Music Mode, glasses only; web: colour art, scrub, full synced lyrics; TV: a footer; companion: MPRIS |
| **comic** | `item`, `image` (the original, cached today and unserved, `W/feed/FeedEngine.kt:395`), `alt`, `bonus`, `num`, neighbours `{prev, next, first, latest, random}` each available or dim | glasses: strips as a named rendition (§9.4); web: the original, uninverted |
| **paged sequence** | `id`, entries `[{num, title, date, image}]` fetched around an anchor `(episode, fraction)` | Feed's binge (neither one list nor one document) |
| **camera** | `ref` per camera, a `still` with `takenAt` and `refresh`, a `video chain` | TV: stills; desk and phone: live video (`BASTION.md` B-17, B-18) |

### 3.5 Where the documents and the code disagree — the code wins

A queue anchor names an entry id, not "track id + ms" (a track can be queued twice) · Reader's character offset is into
Damage's own extraction (`W/reader/Epub.kt:373-417`), so its anchor carries the extractor version and a quote (§5) ·
Feed's blocks drop emphasis and links (`W/feed/Extract.kt:99-112`): S1 adds spans, links and the source URL without
changing the extracted text, so saved offsets keep their meaning · an album is name + artist (today it filters by name,
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
| `expect` | what the op will carry (§2.4) |
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
and queue away (§2.3); the TV makes none (§10.4). A stale value is never
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
on cancel, `W/tmux/TmuxWindow.kt:654-670`); the popover family's CONFIRM replaces them in K (`POPOVER.md` §4.3). For the
never-queued kinds (outbound, guarded, live) a confirm is void if its interface's connection ends before the act —
nothing is sent on half a confirm (`BASTION.md` §9); a queued kind's confirm stands, since its op applies locally.

### 4.4 The answers, and dim

`Answer = Sent | Seen | Refused(reason, code) | Lost(standing, by, at)`. **Sent:** the PC has the op in its order.
**Seen:** the state shows the effect — for a core's own state, the change itself; for an outside program, a later
reading that shows it, with "sent · waiting for the state 4 min" and the age shown meanwhile, no time limit and no
automatic repeat (`BASTION.md` B-27). **Refused:** the reason in words, to the interface that asked, never only to a log
— the level check at apply time included (§10.5). **Lost:** §2.4. A never-queued op left unanswered at its connection's
end shows "not known whether it ran — see the state". Answers go to the asking interface only; today's `answer = true`
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
| Feed | `src:<id>`, `item:<src>:<id>`, `binge:<id>` | `@b<block>.<offset>` (articles, comments); `@e<episode>.<fraction>` (binge), with the glasses' strip index kept beside it as a glasses-scope hint (`W/feed/FeedWindow.kt:1881`) |
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

`Notice{id = "<source>:<thread>", occurrence, raisedAt (an instant; each presenter formats it — today an H:MM string
taken at raise, S/Shell.kt:361-363), body (fixed at raise, never cut), urgency NORMAL · URGENT, ackRequired, gatedBy (a
category), target (an address), actions, state RAISED · READ · DISMISSED · ACKED · CLEARED, origin (pc or a phone
stream), mayRepeat}`. Identity is (source, thread) as built (`S/Notifications.kt:178-201`): a new raise on the pair is
the next occurrence and supersedes the shown one. The watcher's (rule, episode) and Reminders' (item, scheduled
instant) map onto it (`REMINDERS.md` L-06). An ack or dismiss names `(id, occurrence)`; a stale occurrence is a no-op.
An urgent repeat re-shows the same occurrence, so one acknowledge ends it.

### 7.2 Life

The notice policy (gating, coalescing, read state) moves from `Notifications.kt` to the PC: `POPOVER.md` §4.2
("`Notifications.kt` keeps the POLICY") is amended so by this contract; only the presentation stays.

1. A core raises it on the PC, once — not once per window instance (today each listening window raises Tmux alerts and
   Torrents notices — inferred from `W/tmux/TmuxWindow.kt:160-175`, `W/torrents/TorrentsWindow.kt:292-305`). The shared
   app toggle named by `gatedBy` decides whether it is raised; Global `Notify · Damage` never gates a notice that names
   its app (`WINDOWS.md` §1).
2. Every interface reflects it (§7.3). **Read** comes only from input (§2.6). **Dismissed** on one interface is gone on
   all — except a dismiss on the TV, which hides it on the TV only (§10.4). **Acknowledged** ends an `ackRequired`
   record; dismissing one hides the current showing, and the next repeat shows it again. **Dismiss and acknowledge come
   only from input:** Silent Mode's 5 s drop (`S/Shell.kt:2400-2404`, `S/Notifications.kt:298-303`) sends nothing.
3. Its actions (`Done`, `Snooze 1 h`, `Acknowledge`, `Open`) are ordinary ops from the interface that pressed them, so a
   press after the state moved is refused with the answer. A button outside Damage (Android, the desktop) offers only
   reversible actions; anything with a confirm opens the app (`INTERFACES.md` §7).
4. `CLEARED` when its condition ends. Records leave the live table when dismissed, acknowledged or cleared; their
   history stays in day files (the Notices window later).

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
Tmux has no `Phone ·` row; whether they pop on the desk is asked when S2 builds the desk's rows.

### 7.4 Raised on the phone

- **Phone-origin records** (glasses battery, route loss, home unreachable, a notice of a table the phone holds): created
  in the phone's copy with a deterministic id; `raise` and `clear` ops go to the PC when the wire is up. "Home
  unreachable" is the phone's alone (§14.3 #9).
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
viewer, parking with none: Tmux captures frames only for sessions some view holds at `focus`, at the fastest pace asked
(one pace per session; today one for all listeners, `W/tmux/LocalTmuxProvider.kt:97-104`); Torrents polls fast for any
`focus` viewer, idle otherwise (replacing the shared `"remote"` focus key, `W/torrents/TorrentsNet.kt:55`); `glance`
asks only summaries. **In S1 the phone's mirrors receive what they receive today, at today's paces** (Torrents' idle
snapshots go on feeding the glasses' speed sparkline, `W/torrents/TorrentsWindow.kt:215,277-288`). A viewer ends with
its view or its connection — the socket's end, or §9.3's paced ping left unanswered when the next is due (one liveness
rule in both directions; never a failed write alone, which a half-open link can delay for hours). **The
glasses' own rule is unchanged:** a live window keeps the Bluetooth link busy only while it is the active window there
(`WINDOWS.md` §6.4). Engines that pace themselves go on with no viewer: Feed's fetch, the watcher's poll, and tmux's
status poll that raises alerts (`W/tmux/LocalTmuxProvider.kt:187-208`).

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
| → | `hello` | `proto: 2`, credentials (§10), `stream`, `contracts{window: version}`, `iface{kind, slot}`, a page's `bundle{version, hash}` |
| ← | `pending` | `code`: the waiting state of an unapproved page (§10.3) |
| ← | `approved` | `claim`: single use, redeemed by one HTTP request that sets the cookie (§10.3) |
| ← | `welcome` | `device`, `level`, `set?` (§10.4), `conn`, `pcClock`, `epoch`, `highWater{stream: n}`, `contracts{window: version}`, `bundle{version, hash, protoRange}` |
| → | `sub` / `unsub` | `scope` (a window, a level address, a page of a collection, a live source), `cursor?` (a `seq`, a source scope's `version`, or the notice cursor), `need` |
| ← | `snap` | `scope`, `epoch`, `seq` or the source's `version`, the description or level |
| ← | `chg` | `epoch`, `seq` (or `version`), `origin{device, stream, n` or `conn, k}?`, `patches[{addr, set or remove}]` |
| → / ← | `op` / `ans` | §2.4 / `stream` and `n`, or `conn` and `k`; §4.4 |
| ← | `confirm` | a guarded request's confirm, worded on the PC: `id`, text, the target's present state with its age (§4.3) |
| ← | `instr` | a send-to for this interface: `id`, the view (§2.6) |
| → | `iface` | this interface's own state (§2.6), coalesced, naming the last `instr` applied |
| → / ← | `ping` / `pong` | `k`, on the wire's pacing (§9.3) |
| ← | `live` | `src`, `sseq`, a frame (terminal, position, a value) |
| ← | `end` | `reason`: revoked · denied · replaced · bundle-too-old · shutting down |

### 9.3 Snapshot, ordered changes, cursors, resume

One total order for the record on the PC: every applied change gets the next `seq`, written with its op in the journal
line before it is answered or published (§9.7), so it continues across restarts. The `epoch` names the record store,
minted when the store is created, at a start that finds an incomplete last journal line, and at a start that finds the
store older than the last `seq` it published — kept in the **sequence file**, a small file beside the store and outside
the nightly copy (a restore from the nightly copy looks exactly so); never at a process start (Torrents' epoch is its
provider's start time, `W/torrents/LocalTorrentsProvider.kt:53`, as its ring is not persisted). The PC keeps recent
changes in a ring. **Source snapshots** (transfers, sessions, listings, Feed items, the catalog) are outside that order,
versioned per scope (Torrents' `version` + `since`, `W/torrents/TorrentsNet.kt:200-201`), so a 2 s poll never floods
the ring. **Notices** travel on a cursor of their own (§7.3).

- **First contact:** `snap` per subscribed scope; the client adopts `seq`, each version and the notice cursor, and pops
  nothing old.
- **Reconnect:** `sub` with the cursor. Same epoch and `seq` inside the ring → the changes since, in order; otherwise a
  fresh `snap`, never a silent gap (Torrents' `truncated` becomes "send the snapshot"). A source scope whose version
  moved sends its snapshot. After a new epoch, pending ops that expect values are judged on those values; ops expecting
  a revision lose and are said.
- **Paged collections:** the snapshot carries `count` and `version`; changes patch the rows a client holds and bump the
  version; a client re-queries a page whose version moved.
- **Reach and pacing:** a client pings on the wire's pacing and the PC answers; a ping still unanswered when the next is
  due means the PC is out of reach, and every such decision rests on that, never on a failed write. Reconnects follow
  the existing `RemoteWin` shape (2 s, `C/net/WinNet.kt:198-271`); pages show "PC not answering since 14:02" over the
  greyed state and say when it is back.

### 9.4 Blobs and renditions

`GET /blob/<id>` on the web interface listener serves the original (book, cover, comic, photo, track, camera still, a
download) with Range, under the device secret. Every blob answer carries `X-Content-Type-Options: nosniff` and
`Content-Security-Policy: sandbox`, and `Content-Disposition: attachment` for any type outside a short list of image,
audio and video types (SVG is not on the list), so no file the PC serves runs script as the app's page.
`GET /blob/<id>?r=<rendition>` serves a form a presenter names — the glasses' strips, gray thumbnails, 4-bit art — made
on the PC by today's code (`W/feed/Strips.kt`, Files' `thumb`, `W/music/Art.kt:46-68`), so the glasses' picture and the
phone's CPU cost stay as they are. `PUT /blob/upload/<op>` streams to a temp file renamed when complete; a cut-off
upload or a full disk leaves nothing under the real name and answers the uploader. The phone app's page asks the
phone's own source: a blob the phone holds (a book, a cover, a cached track) is served from its copy; one it does not
hold is fetched from the PC when reachable, and is otherwise shown as "not on the phone".

### 9.5 The page bundle (the phone app's cached pages, `INTERFACES.md` P-02)

The PC serves the web client as one bundle (`version`, content `hash`, `protoRange`), with a content security policy
that allows no inline script; the app keeps the last one it fetched and a fallback set inside the APK. At `welcome`:
same hash → run it; different, PC reachable → fetch into a temp directory, swap whole, reload; PC unreachable, or the
cached set needs a protocol the app's bridge does not speak → the cached or the APK's set, and the away line says which
("pages from 11-02; the PC's are newer"). Renderer loss rebuilds the WebView (`onRenderProcessGone` returns true); the
cores and the glasses' session live in `ShellService`.

### 9.6 The phone's working copy on the same wire

The phone subscribes with scope `record` to every window's Damage-owned records and to each away-list window's whole
record, persists `snap`, changes and its cursor, and sends its queue as ordinary `op`s after the resume, less those
`welcome`'s high-water covers. The same carrier holds its mirrors (`glance`, or `focus` while shown; today's paces in
S1, §8), its `iface` reports for the glasses and the app screen, and its live subscriptions (Tmux frames, the PC
player's position).

### 9.7 What replaces today's stamped-store sync, and when

Today two stamped stores sync per key, last write wins, the loser refused with no word outside a 2 s tolerance
(`S/Persistence.kt:190-215`; `HANDOFF.md` §19.1 rule 5), retired for the design by §14.2; each store is one JSON file
written whole and asynchronously (`S/Persistence.kt:290-315`), 2 s after an input (`S/Shell.kt:2459-2462`). In S1:

1. **The first piece** builds the PC record (today's `Persistence` store, keys `rec.<window>.<item>` holding value,
   `rev`, `by`, `at`; inside the nightly copy) behind an **append-only operation journal**: one line per op holding the
   op, its effect, its `seq` and its stream's high-water, written before the answer and before the change is
   published; the whole store saved from it on pacing (temp-and-move), after which the lines it covers are dropped; the
   sequence file (§9.3); the core executor, §9.2's wire, the device table, and the phone's working-copy store with its
   queue file (§2.3).
2. **Each window's conversion** takes its keys off the sync channel (`syncable()`, `S/Persistence.kt:335-336`, stops
   matching `window.<id>`). `hello` carries a contract version per window; while PC and phone differ for a window, Main
   and the window's status line say "PC and phone on different builds for Reader", and each host runs that window from
   its own copy until both match. A conversion is deployed as one of his sittings: the PC deploy and the APK install
   together. At its first converted start each host migrates its own old records once; the phone's reach the PC as
   ordinary ops with their expectations, a losing one said like any other. Unconverted windows sync as today; nothing
   new is built into the sync channel.
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
   compare with `!=` today, `C/replica/ReplicaServer.kt:147`, `W/music/MediaServer.kt:128`).
3. **Approval, once, from a trusted interface** (§15 A2). An unknown device's page gets the waiting screen only:
   `pending{code}`, a short code in an unambiguous alphabet. A pending row with the same code and the tailnet name or
   the address appears on every full interface; he picks the row and a level (no typing), or denies it (`end{denied}`).
   Approval sends the waiting page `approved{claim}`, a single-use claim value, which the page redeems with one HTTP
   request whose answer sets the `HttpOnly; SameSite=Strict` cookie holding the device secret (an open socket cannot set
   an HttpOnly cookie; no `Secure` over plain HTTP), re-set on each visit so it never reaches Chromium's 400-day cap. A
   pending request lives while its connection is open — no cap, no queue, no timer (not a Bluetooth, render or input
   path, so the no-timeouts rule does not reach it). A Kotlin peer keeps its credential in its preferences, never in the
   build. **The first approval** comes from the command line or the companion (item 6).
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
   `Refused` with the reason; a client's dim is a courtesy, never the check.
6. **The local secret**, a file readable only by his user and written by the service, authorizes the companion and the
   `damage` command line on loopback as device `local`, level full; loopback alone is not trusted. It keeps other users
   out and nothing more.
7. **The helper's endpoint** is loopback only: it checks `Host` as item 5 does, refuses any request carrying an `Origin`
   header, and takes the per-run bearer the service writes into the helper's MCP config only in a header. The service
   refuses the helper unless the CLI's stream-json start event lists only the list operations as its tools (H-01).
8. **Revocation** deletes the secret's hash and closes the device's connections with the reason (`end{revoked}`; the
   page says "this device was removed — approve it again"); its unsent ops are refused; a revoked phone keeps its copy
   and sends nothing. A re-approval from a device already listed offers to replace its old row (`end{replaced}`).
9. **Listeners.** Every PC listener is bound or gated to loopback, the tailnet and the bus network, every phone listener
   to loopback and the tailnet (§15 A1); a gate checks the arriving interface before reading a byte (the setup page's
   pattern, `D/SetupServer.kt:21-33`) and journals a refusal. **Built in W (APK 0.66, `HANDOFF.md` §79) for today's
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
| the web interface: pages, WebSocket, blobs (new) | PC | loopback, tailnet, bus network | the device secret (items 1–5) |
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
| `saveSubState()` / `restoreSubState()` | | ✓ | record items with `rev` and `by` |
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
| **Reader** | PC, which parses books to serve blocks and percent (it serves bytes only today, `C/content/Content.kt:270-307`); working copy with the whole shelf; away: full | shelf (folders nest) → chapters → book (document) → actions; book `{id, title, author, bytes, folder, fraction}`, folder, chapter `{title, anchor}` | open (v; sets the book read most recently), the place (r, C, ⏸, position; written only by input), bookmark here (r, ⏸; it gains a book id, `W/reader/ReaderWindow.kt:147,864-868`), jump (v), rescan (r), reset progress (i, C, ⏸, cancel first as built) | Scroll step, Scroll accel, Size (glasses) | answers only |
| **Tmux** | PC; **min F** (§15 A2); mirror (the session list and each session's last frame, on disk); away: last state with age, keys dim, a mute applies and queues | sessions → live (terminal) ⇄ history → keys · snippets · windows · session actions; session `{host, name, windows, attached, activity, waiting, lastLine}`, window `{idx, name, active, bell}` | all F: quick key (l, unconfirmed as built; under an alert it carries the occurrence, §2.4), snippet (l, unconfirmed as built), typed line (l, confirm), raw keys (l, keyboard interfaces, §15 B4), new session (i), rename (i, text, confirm), select window and fit pane (r: they change tmux for every attached client, and the same action undoes them), end session (i, confirm, cancel at rest), mute (r, ⏸) | Alerts (shared), Update (device: a requested pace, §8), Context rows, Size (glasses) | the alert edge, raised once on the PC by the status poll that runs with no viewer, thread `host/session`, gated by Alerts and the mute set; it survives a disconnect (lost today, `W/tmux/TmuxNet.kt:124-126`); "<host> quiet" as a value (§8) |
| **Files** | PC; **min F** (§10.4); mirror (today's ≤200 listings); away: cached with age; the clipboard, sort and hidden apply and queue | locations → browse (paged) → view (document, or images) · trash; location `{path, label, kind, total, free}`, entry `{name, dir, size, mtime}`, trash entry `{id, name, origPath, size, at}` | open, open in Reader (v; answers an address), open on the desk (send-to the companion), copy and cut (r, ⏸; the clipboard is shared), sort and hidden (r, ⏸; shared, `INTERFACES.md` §3), paste, rename, new folder, upload (jobs), to trash and restore (r), delete forever (i, double confirm), download (v), stats (a job); one job at a time per PC, with progress (today one per window instance, `W/files/FilesWindow.kt:98-100`) | Size (glasses) | answers |
| **Torrents** | PC; mirror (the cached snapshot); away: snapshot with age, actions dim, its recents apply and queue | transfers (cursor follows the hash) → details · categories → listing (paged, count unknown) → torrent page (NFO mono) → add; transfer (23 typed fields, `W/torrents/TorrentsModel.kt:13-57`), tracker item, category, file | start, stop, recheck (r, C, many), delete keeping files (i, C, confirm), delete with files (i, F, double), search, browse, account (o, F; search unconfirmed, its recorded exemption, `TORRENTS.md` §3.1), add, add stopped (o, F, confirm), open in Files (v; dim below full), open on the desk (r, F; send-to; full because it opens a file with the desktop's own opener); a fast poll for any `focus` viewer, and in S1 the idle snapshots go on feeding the speed sparkline (§8) | Notify · done, · errors (shared), Poll (device), Size (glasses) | done and error, raised once on the PC, thread = hash; the torrent client's reach as a value (§8) |
| **Music** | PC; working copy (queue, catalog, prefetched and pinned tracks); away: the phone's player, then Spotify after the tracks run out | now playing (canvas) → queue, browse, artists, albums, vocab, playlists, folder, recent, results, YouTube, seek (the catalog paged) · info (document); queue entry `{entry id, track}`, track `{id, title, artist, album, durMs, genre, hasLyrics}`, artist, album (name + artist), playlist `{id, name, origin, adaptive, count, updatedAt}`, term, YouTube result | play, pause, seek, volume (r, C; ⏸ only when the output is this device's own player, else they need the PC), next, previous (r, C; the active player's host advances, §2.7), boost, sleep (r, C, the active output), move output (r, C; §2.7), add, play next, move, remove (r, C, ⏸; play next re-anchored by the PC), play now, replace, clear (i, C, ⏸; the op carries the order; replace and clear expect the queue's revision; confirms as built), playlist save, rename, delete (i, C; delete double), Ask, YouTube, lyrics search (o, F), lyric offset (r, per output), pre-transcode, rescan (r, jobs) | six Notify rows, default mode, lyric sources (shared); output rows (§6); visualizer, Music Mode, Size (glasses) | raised by the PC; the track notice shows on the glasses only when Music is not in front there, as built (`W/music/MusicWindow.kt:292-345`) |
| **Games** | PC; working copy; the table leased (§2.7); away: a table the phone holds | games → tables → table (canvas) · standings → character · bankroll · history (documents); table kind `{id, label, entry, fee, blinds}` (dim when unaffordable), character `{id, name, worth, state, career}` | sit down (i, C, ⏸, confirm; a table started away is held by the device that started it), check, fold, call, bet, raise, all-in (i, C, holder only, log kind, confirm per the device's Confirm row), deal, skip the pace (holder), the economy's character and bankroll writes (log kind, holder), cash out, refill (i, confirm), continue here (C; takes the lease; its confirm says what is dropped if the holder is out of reach) | Confirm, Bot pace, Deal animation (device); archetype labels, three Notify rows (shared); Size (glasses) | bust, won, return, raised by the holder |
| **Feed** | PC (one engine); away per §14 (a) row 1 | sources → items (paged, cursor by id) → article, comments (documents) · comic · binge (paged sequence) · actions · flagged; source `{id, kind, name, count, newCount, lastOk, state}`, item `{id, source, title, link, author, published, kind, comments, read, flagged}`, comment `{id, author, published, depth, text, score}` (ids and a tree are new) | open (v; read by input, `FEED.md` verdict 6), mark read or unread, flag, pin (r, C, ⏸), mark all read (r, ⏸, confirm; the op carries the explicit ids), refresh, browse (o, F), comic and binge moves (position, ⏸; the binge anchor keeps the strip index as a glasses hint, §5), the original page's link (v, big screens, §15 B5; http or https only), back to PC (r; goes with the phone's engine if §14 (a) row 1 is ruled as recommended) | Fetch, Keep, three Notify rows (shared); Images (device); inversion, comic levels, Size (glasses); PC loss (phone; goes with the phone's engine) | new items per source kind, raised by the PC's engine; health per source, as values (§8) |
| **Bastion** | PC (the watcher now; pages, store, cameras and actions at S3); away: the last table with age, no bus action; an acknowledgement is Damage's own record — it applies on the phone (its repeats stop there), queues, names its episode, and loses on return if that episode has ended | overview (worst first) → a system (values, series, actions) → a graph · cameras · alerts · action log; system `{kind, band, line, asOf}`, value (§3.2), alert `{rule, episode, state, severity, raisedAt}`, log entry `{at, device, iface, action, answer, stateSeen}`; canvas: camera; headline: the TV's bus strip | acknowledge (r; an action on the alert; in the TV's set), mode, test alert (r, C), each bus action (g, F: a request, then a confirm on a screen at full, §4.3; its tier and its script's limit in words; offered only once that script exists, §15 D1) | before the window, built by the watcher: Notify · alerts, · cleared (shared), Phone · alerts, Home unreachable (device: the phone's own preferences), Quiet hours (shared, §6) | alerts normal or urgent (§7.3), built-in watcher alerts, home unreachable (phone-origin) |
| **Reminders** | PC; working copy; away: full | Today · Buy · Do · Calendar and further lists → a list (Overdue, Today, later, In the cart) → an item (form) · Recently done · Changes (the helper's audit, H-03); item `{id, list, text, qty, note, due{instant or date, zone}, recurrence{rule, label}, state open · done · dropped · trash, source, severity}`, event `{id, title, start, end, allDay, lead}`, occurrence `{item, instant, state}`; headline: the TV's front zone, Main's lens | tick, untick (r, C, ⏸; a recurring item's names its occurrence, §2.4; in the TV's set), snooze (r, choice, ⏸; names its occurrence), edit (r, text, ⏸), move, drop to trash, restore (r, ⏸), add from text (parsed locally, shown before keeping, then a create op, ⏸), undo a named op (r, ⏸), answer a question (C), message the helper (o, F); the helper is device `helper` with its fixed set (§10.4): its edits and drops of his items arrive as questions (§15 E2), its replies as a document plus a question (§3.2) | — | one per due occurrence with Done and Snooze; the phone raises any occurrence the PC has not confirmed delivered, "may repeat" (§7.4) |

## 13. The failure envelope

### 13 (a) Every case of the three documents, and the rule that answers it

| case (source) | answered by |
|---|---|
| PC out of reach: browser, TV (INTERFACES §6; BASTION §9) | §9.3 the paced ping decides it; greyed snapshot under "PC not answering since", paced reconnect, resume from epoch and seq; actions dim §4.4; no queue §2.5 |
| PC out of reach, phone app (INTERFACES §6; REMINDERS §5) | §2.5 the away list as working copies, every window's own records applying and queueing; the others' last state with age; the queue count; §2.4 on return; the app's screen on the same copy §9.1 |
| a queue edited in two places (INTERFACES §6) | §2.4 results not recipes, whole-collection revisions, coalescing only unsent ops; §2.7 the active player's host advances, the PC re-anchors a play-next |
| a stale value, one stale value (all three) | §8 age on every value, that value greys alone past `staleAfter`; §4.2 never the base of a guarded action; due times are instants (§3.3) |
| a refused action, a refused bus action (all three) | §4.4 the reason to the asking interface (the confirm shows it; the action log keeps it); §10.5 the level checked at apply time; §2.4 numbering, so a queued op never applies twice; live, outbound and guarded ops never resent, an unanswered one "not known whether it ran"; `Restore` on a dropped item |
| two interfaces at once (INTERFACES §6) | §2.4 arrival order; §9.3 every interface sees the result; a loss never moves a view (§2.6) |
| an unclean PC stop; a restore from the nightly copy | §9.7 the journal line before every answer and publish; §9.3 a new epoch on an incomplete last line or a store older than the sequence file |
| PC and phone on different builds for a window | §9.7 the contract version per window: said on Main and the window's status line; each host runs it from its own copy until both match |
| never approved, or revoked (all three) | §10.3 the waiting screen only, a pending row on every full interface, alive while its connection is open; §10.8 its connections closed with the reason, the page says so |
| a page the device did not mean to trust (INTERFACES §6) | §10.5 Host, Origin and the device secret on every request, the level at apply time; §9.4 blob headers; §3.2 links http or https only; §9.5 no inline script |
| the glasses' session down (INTERFACES §6) | §2.2 cores run without it; send-to the glasses dim with the reason; the panel icon says it |
| the companion, or the service under it, down (INTERFACES §6) | the companion is a Kotlin peer on §9.3's pacing, its icon grey; autostart restarts it only at login, said in its menu |
| WebSocket dropped and back (INTERFACES §6) | §9.3 resume from the cursors; §7.3 the notice cursor: no old notice popped |
| the phone's renderer lost (INTERFACES §6) | §9.5 the WebView rebuilt; the glasses' session is in the service |
| cached pages older than the PC's (INTERFACES §6) | §9.5 the handshake; the away line says which set runs |
| a playback move that fails (INTERFACES §6) | §2.7 B starts at p and A stops only when B plays; B not connected → refused at once; B silent → "sent · waiting", A plays |
| a notification button after the state moved (INTERFACES §6) | §7.2 the button's op no longer meets its `expect` → refused with the answer; a key at a known prompt carries its occurrence (§2.4) |
| an upload cut off, or the disk full (INTERFACES §6) | §9.4 temp file, nothing under the real name; the error to the uploader |
| do-not-disturb on the desktop (INTERFACES §6) | §7.3 every notice action also in the companion's menu and the window |
| a web terminal attached (INTERFACES §6) | §3.4 `ignore-size` |
| the hub out of reach from the PC (BASTION §9) | §8 values `lastKnown` with the reason; §4.4 actions dim "hub not answering 6 min"; never queued (§4.2) |
| hub, network and PC all out of reach (BASTION §9) | §7.4 only the phone's "home unreachable" says it |
| an action whose state never changes (BASTION §9) | §4.4 "sent · no change 4 min" stays; the watcher's "action not confirmed" alert; no automatic repeat |
| the session drops mid-confirm (BASTION §9) | §4.3 a never-queued kind's confirm is void, "not sent"; a guarded op left unanswered is never resent (§2.4) |
| the TV's browser closes or faults (BASTION §9) | §8 its viewer ends; the device's `lastSeen` gives "TV not connected 3 min"; the host's watchdog is S2's |
| a camera source down; a gap in the store (BASTION §9) | §3.2 `takenAt` → "picture 5 min old"; a series gap is an explicit null, no line across it |
| an alert acknowledged elsewhere during a takeover (BASTION §9) | §7.2 one record; the takeover leaves on the change; a repeat is the same occurrence (§7.1) |
| hub and PC clocks disagree (BASTION §9) | §8 ages on the PC's clock from first sight |
| the helper down, limited or logged out (REMINDERS §5) | §12 Reminders never needs it; its status is a value; his messages wait as a visible queue (H-13) |
| the helper does something wrong, starts with more tools, or runs long (REMINDERS §5) | its ops carry device `helper` (§2.4), so Changes lists them with Undo, and its reach is its fixed set (§10.4); §10.7 checks the CLI's start event, said on its status; a bus action only as a request (§4.3); the per-wake bound (H-16) is outside this contract's paths |
| a recurring reminder ticked in two places (REMINDERS §5) | §2.4 the tick names its occurrence and loses when it is no longer the open one |
| the glasses asleep at a due time (REMINDERS §5) | §7.3 the Silent Mode small form; the phone's shade carries it; §7.2 the Silent drop dismisses nothing |
| a stop mid-delivery (REMINDERS §5) | §7.4 raised by the phone at the due instant unless the PC confirmed delivery, "may repeat", on the deterministic occurrence |
| the bus crosses a time zone (REMINDERS §5) | §3.3 wall time plus zone, the phone's zone a device value, formatted by each presenter |
| voice hears nothing usable (REMINDERS §5) | the voice design; the confirm card is a question record (§3.2) |

### 13 (b) `FORK.md` §3.8's six questions for what this contract adds

(a) what clears it at a session boundary, a lapse, a restart, a rebuild · (b) what releases a wait if a write never
succeeds · (c) the guard's identity · (d) other tasks reaching it · (e) one read per decision · (f) siblings.

| new piece | (a) | (b) | (c) | (d) | (e) | (f) |
|---|---|---|---|---|---|---|
| op queue (phone) | no session, lease or link end clears it; an op leaves only on its answer or when `welcome`'s high-water covers it; a lost store means a new stream, said | resent from the oldest unanswered on each `welcome`; no timer | `(device, stream, n)`; a new stream starts at 1 and cannot collide | the presenter paints, the writer thread appends, the wire task sends only landed lines | the next `n` read once per op | a page's stream (no file); the helper's run stream; the PC's journal |
| operation journal (PC) | survives restarts; a whole save of the store covers its lines, which are then dropped; an incomplete last line at a start → a new epoch | a failed append: the op is neither answered nor published, the failure is said (log, state line, built-in alert), and the client's resend retries it | `(stream, n)` and `seq`, in the line | the core executor alone appends; the saver reads on pacing | one append per op | the phone's queue file; the sequence file; the alert store |
| per-stream high-water | lives as long as its stream's record (below) | written in the op's journal line, so it lands with the op or not at all | `(device, stream)` | the executor writes; `welcome` reads | once per op; once per `welcome` | the answer record; the gap check |
| interface rows and per-stream answer records | one per browser profile, interface and helper run; dropped on the device's revocation, or when a client's `hello` says it closed its old stream | — | `(device, iface)`, `(device, stream)` | the executor | once per `hello` | the glasses' row on the driving device |
| working copy | survives APK restarts and stack rebuilds on disk; a new epoch → fresh snapshot, pending ops kept | §2.3 step 3 on every answer or change | item id + `rev`/`by` | the core executor only; rebuilds computed off it and swapped in whole; presenters read immutable snapshots | one snapshot per paint | every window's own records; the away list whole; the mirrors |
| cursors: `(epoch, seq)`, a source's `version`, the notice cursor | persisted beside the copy; moved only by a later change or a snapshot | a resume outside the ring, or a moved version, falls back to a snapshot | the store's epoch, not a process start (§9.3); the notice cursor apart, so source churn never decides whether a notice pops | the wire's read task | once per `sub` | every client: pages, phone, companion |
| PC record and `seq` | survives restarts; a new epoch per §9.3 | a failed save is said and retried on the next pacing tick; the journal holds what it has not covered | `rec.<window>.<item>` | the saver reads the journal; the wire reads published snapshots | once per op | the change ring; the answer record |
| sequence file | never cleared; outside the nightly copy; missing at a start → a new epoch | written by the journal's writer before a change is published; a failed write stops publishing, said | the highest `seq` published | the journal's writer alone | read once at a start | the alert store's epoch |
| contract version per window | changes only with a deploy or an install; compared at every `hello` | a mismatch is said on Main and the window's status line until both match; no timer | `(window, version)` | the hello task; the executor reads the result | once per `hello` | the bundle's `protoRange`; the old sync keys of unconverted windows |
| approval request | its connection's end, approve, deny | none: a person approves | the code shown on both screens | the web listener's threads; the device table under one lock | once per approval | the command line's approve; tailnet and bus-network arrivals alike |
| claim value | single use: void once redeemed, or when its waiting connection ends unredeemed (the approval is then dropped and the page asks again) | none: the page redeems it once | the claim, stored as its hash | the web listener's threads, under the device table's lock | once per redemption | the cookie it sets; the Kotlin peers' credentials |
| per-device secret | revocation or replacement only; the cookie re-set on each visit | — | its hash; constant-time compare | every request thread of the web listener | once per request | the cookie, the blob routes |
| minimum level and the level check | carried by each description and live source; changes only with a build | — | the device's level or set against the window's and the source's minimum | the executor (ops); the wire tasks (subscriptions, attaches, blobs) | the device's level read once per request | op, subscription, live attach, blob; send-to targets; the TV's set |
| confirm request (guarded) | confirmed or cancelled on any screen, or withdrawn by its requester; not kept across a PC restart (its requester is answered "not confirmed"); void on the showing screen's connection end | none: a person confirms; a hub script that never shows its effect stays "sent · waiting" (§4.4) | `(request id, allowlist entry)`, worded on the PC | the executor; every full screen shows it | the target's state read once when shown, with its age | a tap's request, the command line's, the helper's |
| paced ping | per connection; a new connection starts a new count | a ping unanswered when the next is due marks the PC out of reach; the next answer clears it | `(conn, k)` | the wire task alone | one decision per pacing tick | every client; the phone's reminder rule does not depend on it (§7.4) |
| send-to instruction | applied, refused, or its target not connected (refused, said) | answered from the target's report naming the last instruction applied | `(target iface, id)` | the target's input queue alone applies it | once | continue here; the glasses' row |
| notice record | `DISMISSED`, `ACKED`, `CLEARED`; history to day files; occurrences persist across restarts | a record an interface did not show is in its next snapshot | `(id, occurrence)`; a stale occurrence is a no-op; a repeat is the same occurrence | cores raise, interfaces dismiss and acknowledge from input — all through the executor | once per decision | phone-origin records, Android tags, desktop notification ids, the TV takeover |
| viewer count | its view's end or its connection's end | the paced ping unanswered when the next is due ends the connection | `(iface, view, src)` | the wire's tasks register; the source reads the maximum | the pace read once per tick | Tmux frames, Torrents poll, the music position, the TV's glance |
| Games lease | moved only by "continue here" | an absent holder's hands after a take are refused, said (its confirm said so) | `(table, holder)`, `actionCount` | the holder's executor alone advances the table and the economy | once per decision | the bankroll, world and character records the engine writes |
| page bundle cache | replaced whole after a complete swap | a failed fetch keeps the running set, said | `hash` | the app's fetch task; the WebView reads only a complete set | once per `welcome` | the APK's fallback set |
| local secret file | written by the service at its first start, created readable only by his user in one step; rewritten only by an explicit command; deleted under a running companion → its next `hello` is refused, said in its menu, and the service writes a new one at its next start | — | the file's contents, compared in constant time | the service writes; the companion and the command line read | once per connection | the helper's bearer |
| helper's bearer | one per run; cleared (its file removed, its hash dropped) when the run ends; only the current run's is accepted | — | the bearer, compared in constant time, in a header only | the service writes; the endpoint reads | once per request | the local secret |

## 14. Decisions

### 14 (a) Put to Adam — taken as recommended on his word of 2026-11-13 (`REPLAN.md` §15.1); his to reverse

| # | question | recommendation | why |
|---|---|---|---|
| 1 | Feed with the PC out of reach: only the PC's last state with its age (§14.3 #2), which retires the phone's own fetching (`FEED.md` verdict 15, built) — or the phone goes on fetching the public sites itself as built | the last state with its age | one engine, no second copy of read marks to merge, and the PC is always on and reached over the tailnet wherever the phone has internet. His words: verdict 15, "YES, for everything plain HTTP reaches: the engine is core Kotlin and runs on the phone whenever the PC is unreachable"; 11-12 (§14.3 #2), "Files, Torrents, Tmux and Feed show their last state with its age" |
| 2 | Reader at the desk: Damage's own text with emphasis, links and pictures, the place exact on every screen — or the book's own styling, closer to the printed look, the place approximate | Damage's own text | one coordinate for the place; the glasses already read that text |
| 3 | The kitchen TV's remote: next and previous track beside play and pause (§15 A3 lists play and pause; `BASTION.md` T-24 wants next) | yes | as harmless as pause, and the remote has the keys |

### 14 (b) Defaults taken (the alternative turned down after the dash)

- What a screen has open is its own; the place inside each item is one copy on the PC; a screen that opens Reader with
  nothing open starts at the book read most recently anywhere; nothing changes what a screen shows without input on it
  ("continue here" and "send to" are the deliberate ways); now playing is one playback, the same everywhere (§14.1 R4;
  §2.6) — not a shared open item that every screen follows (a view would move while he reads).
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
- Arrival order at the PC; the concurrent edit arriving second loses, said — not "the later device clock wins".
- Value expectations for registers and positions, revisions for moves, removals and whole collections — not revisions
  everywhere.
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
- Only input marks read or acknowledges — not display (the TV would read everything).
- One shared Quiet hours row, in Global beside `Notify · Damage` (the shell's notice policy, not an app's toggle; one
  more row than `CLAUDE.md` lists for Global, his to veto) — not one per app. Approval requests live with their connection — not on a timer. Approval
  by picking the pending row that carries the code — not by typing it (the leanest form, §15.1). The first approval
  comes from the command line or companion — not trust on first use.
- Torrents' delete keeping files at control — not full (no account, no file write).
- A moved book keeps its records through a content-hash alias — not a new book identity (every record would move).
- "Open on PC" as send-to the companion — not the headless service setting `DISPLAY` itself.
- In S1 a losing edit reaches the glasses as today's answer notice — the item mark waits for K.
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
