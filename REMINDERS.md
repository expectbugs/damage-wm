# REMINDERS.md — the Reminders window, the helper that keeps it current, and the voice path: the explosion

**Context for the reader.** Damage is a personal window manager Adam wrote for his own Even Realities G2 glasses, on
firmware he installed with the published patch method; it runs on his own phone, PC and home network. This file is
step A design work (`REPLAN.md` §4): the explosion for the Reminders window (things to buy, things to do, calendar
events), the always-on Claude Code helper on the PC that keeps the lists current, and the voice path that comes
later. Documents only; nothing is built, started or probed. Plain wording throughout (`CLAUDE.md`).

**Status: an EXPLOSION written 2026-11-12/13 for Adam's refinery. Nothing in it is built or in force. The decisions
put to him are in `REPLAN.md` §15; his rulings are recorded there.** The list is wide on purpose, so he can cut it.
The "lean" column is the writer's; the rulings are Adam's (`REPLAN.md` §14 wins). Grades: **read** (file:line or
report), **reported**, **inferred**, **unknown**. No number here was measured for this document; figures tagged "his
note" are his own earlier measurements as recorded in his projects. R1–R6 are step A's research reports
(`private/stepA/`); the few details of his home this design refers to are in a private annex
(`private/REMINDERS-annex.md`), both kept out of the repository. Access, notices and edits made in several places
follow the rules of `INTERFACES.md` §4.

---

## 1. Facts the design stands on

| # | fact | grade | source |
|---|---|---|---|
| F1 | Lists and calendar exist nowhere today; designed now, built as the first new window, once for every interface; front and centre on the TV; the helper is an always-on stream-json Claude Code session; voice later on the PC's own speech stack | read (ruling) | `REPLAN.md:330-346` |
| F2 | The Calendar and Timers windows were axed 2026-09-01, and the scheduled-work piece (§16.13) went with Timers; its sketch said "a duplicate notice, never a missed fire". The 11-12 ruling brings calendar events back inside Reminders | read / inferred | `EXPLOSION.md:190-194,238-243,500` |
| F3 | The hub holds its default shopping list, contents unknown | read (a backup digest) | R4 §1 |
| F4 | aria's reminders: date only (time of day lost), recurrence never worked, marked done *before* delivery (a failure was lost), auto-expired 3 days overdue; its Google push-on-failure was never found in code | read / inferred | R5 §B |
| F5 | ARIA2's rule: compose when saved, deliver on time, **no model call in the firing path**; findings F-411 (double fire without an atomic claim), F-096 (a recurring item stopped for good after one failed fire), F-092 (a stopped process leaves a row "firing") | read | R5 §B |
| F6 | aria nudges: cooldown written only on success, caps 15/day and 2/hour, everything due grouped into one message, quiet hours skipped; G2CC calendar: a 10-min lead, one alert per event across restarts; G2CC timers: a fire missed while down fires once at start, marked late | read | R5 §B |
| F7 | His assistant lessons: a model with a shell edited the database around the confirm gate; it said "no work today" from a missing entry; a second small-model process with its own context contradicted the main one ("one brain") | read (his records) | R5 §B |
| F8 | Claude Code CLI 2.1.322: long-lived `-p` stream-json sessions work; a narrow tool set = `--tools ""` + a strict MCP config + an MCP-only allow + `dontAsk`; every turn is judged by fields, not the exit code; `--max-turns` is documented but absent from the installed `--help`, `--max-budget-usd` is listed; Haiku 5.5 is the small model; on his subscription every model draws on the same session and weekly windows | read (each flag); inferred (the narrow-tool recipe as a whole) | R3 §A–§E; R3:40, R3:89 |
| F9 | Two workflows stopped on the usage limit this week; an expired login stops every request until `/login`; two processes on one config directory can collide on the token refresh | read | R3 §C |
| F10 | Speech stack: G2CC's two warm daemons (ASR canary-qwen-2.5b on the GPU, TTS Kokoro on the CPU), stdin/stdout framed, whole utterance only, nothing running today; ASR "~95 % at normal voice" (his note); a Kokoro dependency is GPL-3.0+ | read | R5 §A |
| F11 | The phone mic is banned for dictation (2026-06-11: "I never ever want to fall back to the phone's mic"; the record notes detection is "a different job"); earbud capture cannot run beside A2DP music and that lane was rejected; the glasses' mic was never used; no audio wake word exists in any of his projects | read | R5 §A, §C |
| F12 | The kitchen voice device stays unplugged until he has customized it; nothing may depend on it listening | read (his words) | memory `damage-endgoal-interfaces-bastion.md`; `REPLAN.md:264` |
| F13 | Glasses notices: tap opens the source, double-tap dismisses and marks read, long-press is a no-op by default, scroll scrolls; a 2–3 s focus grace; in Silent Mode a small box, display-only, left unread | read | `DESIGN.md:35,1076-1143` |
| F14 | `Phone notifications` is off by default and `Notify · Damage` is off on his phone, so the low-battery alert got an Android channel of its own | read | `DESIGN.md:2063-2073` |
| F15 | The popover family (MENU, NOTICE, CONFIRM, PEEK, DECK, ASK) and the Claude path (`damage-show`, decks, answers that never claim "seen") are a spec, not built | read | `POPOVER.md:3,159-194` |
| F16 | Typed text always stages behind the window's confirm; app notification toggles live in the app's own Settings category | read | `WINDOWS.md:41-42,50-52` |
| F17 | XFCE: notification buttons vanish under do-not-disturb; shortcuts run commands; the service is headless, so a session-side companion is needed. Web Push goes through the browser vendor's server, so phone alerts stay in the APK | read | R2 §A; R1 "What this decides" 9 |
| F18 | The TV: portrait by CSS rotation, IR remote (arrows, OK, Back); whether its browser passes arrow keys to a page is unknown | read / unknown | R1 §A |

Sizes below: **S** a small piece inside the window's build · **M** several pieces or a new surface · **L** a subsystem
of its own. Rough, the writer's. Reminders is on the phone's away list beside Reader, Music, Main, Settings and Games
(`REPLAN.md` §14.3 answer 2 named those five): a shopping list works in a shop.

## 2. Candidates

### 2.1 The data (L)

| id | candidate | what it gives him | what it needs | size | lean | conflicts |
|---|---|---|---|---|---|---|
| L-01 | One item record: text, list, due (date, optional time of day, zone), recurrence, source, state, stamps, note, and a reserved `severity` (`normal` \| `urgent`, the watcher's field) | one shape every interface and the helper read | per-item records on the PC; the phone's copy | M | in — the rest hangs on it | — |
| L-02 | Lists as data, any number; Buy, Do and Calendar shown at first; new lists made at the desk | "Bus maintenance" later with no rework | a list id on every item | S | in (§6) | — |
| L-03 | Time of day optional; a date-only item is "today" and notifies at a morning hour he sets | a 3 pm call notifies at 3 pm, not 07:00 (F4) | an explicit "no time" due | S | in | — |
| L-04 | Recurrence on the calendar: every N days/weeks/months, weekdays, yearly | trash night, a yearly renewal | a rule plus a human label the window supplies | M | in | — |
| L-05 | Recurrence counted from completion ("90 days after done") | filters, UV lamp: done late, the next one moves with it | the done time | S | in (§6) | — |
| L-06 | Each firing is an occurrence record on the PC (item + scheduled instant): pending → firing → delivered / failed / unverified, the step to firing an atomic claim inside the PC's store (never across the phone and the PC) | no double fire from the PC, an audit of what went out (F5) | an occurrence table; start-up scan for "firing" | M | in | — |
| L-07 | Missed firings collapse: a run missed while everything was down fires once, marked late; the next one comes from the rule, never from the firing | recurrence that survives a missed firing (F5 F-096) | rule + last delivered | S | in | — |
| L-08 | Source on every item and change: typed on which device, keyboard, voice, share sheet, helper, bus rule, his other sessions | "where did this come from"; undo by source | a source field; device identity (`INTERFACES.md` §4.1) | S | in | — |
| L-09 | States past done: dropped (no longer wanted), trash with restore, a "Recently done" level with Undo; overdue never expires | nothing a slip or the helper removes is gone; no hidden overdue item (F4) | state field; the op log | S | in | CLAUDE.md "no silent failures" |
| L-10 | Buy items carry a quantity and a note ("2 × oat milk") | one line that says enough in a shop | two optional fields | S | in | — |
| L-11 | Buy sections with a per-store order (produce, dairy, hardware) | the list in aisle order; the helper fills the section | a section field; an order per store | M | later — pays once the list is long | — |
| L-12 | Staples: recently bought items offered for a one-tap re-add | "milk again" with no typing | done Buy items kept | S | in — the history exists anyway | — |
| L-13 | Reusable checklists that reset when started (before moving the bus) | the departure routine as a list | a list flag; a Reset action | S | later | — |
| L-14 | His own calendar events: title, start (date or date-time), end or length, all-day, note, lead time | calendar events with nothing outside involved | an event record beside items | M | in | — |
| L-15 | Follow an outside calendar read-only (an ICS URL or Google); two-way sync as aria had is out | work or shared events beside his own | an outside account; a fetch on the PC | M | his call (§6) | CLAUDE.md outside accounts ("Nothing else"); F4 |
| L-16 | Serve his events as a read-only ICS feed on the tailnet | the phone's own calendar app shows them | an endpoint behind the device gate | S | later | reach: tailnet only (§14.3 3) |
| L-17 | Edits travel as operations (add, tick, untick, edit, move, snooze, drop), numbered per device and merged on the PC by the one rule of `INTERFACES.md` §4.4 | a tick made offline in a shop and a helper edit to the same item both survive; a replayed queue adds nothing twice | an op log; op numbers per device | M | in — the rule is settled at the contract sitting (§6) | — |
| L-18 | Who may change what: Adam everything; the helper only through its operations (H-01); a bus rule only adds to one list; the TV's host only ticks (its fixed set, `INTERFACES.md` §4.1) | a narrow reach per writer | the source field; a check per writer on the PC | S | in | — |
| L-19 | Times kept as wall time plus zone, shown in the phone's current zone | the bus moves; the hub's own zone setting cannot be relied on | a zone per due | S | in | ask whether he crosses zones (§7) |
| L-20 | Location triggers (arrive / leave), as aria had | "at the hardware store" | phone location, geofencing | L | out — home moves with the bus | — |

### 2.2 On each interface (L)

| id | candidate | what it gives him | what it needs | size | lean | conflicts |
|---|---|---|---|---|---|---|
| L-21 | TV front zone: overdue and today's items with times, the next three events, the Buy count and first items, the copy's age when it is old | the kitchen glance | a ranked headline block from the window (§4) | M | in | — |
| L-22 | TV remote ticks: arrows to an item, OK ticks, an Undo line stays on the zone | tick milk off at the fridge | arrow and OK reaching the page (unknown, F18); the TV approved | S | in if the keys reach the page | — |
| L-23 | TV and desk: a week strip or month grid of events and due items | the week's shape at a glance | layout only | M | later | the glasses keep fixed-cursor lists (`CLAUDE.md` compositor rules); not proposed there |
| L-24 | Glasses: the Reminders window — root Today · Buy · Do · Calendar; fixed-cursor lists; tap = item menu (Done · Snooze · Edit · Move, Drop last) | the whole thing from the ring | the list kit; MENU | M | in | — |
| L-25 | Glasses: a due notice — tap opens Snooze 10 min (at rest) · Done · 1 h · Tomorrow · Open; double-tap dismisses and leaves it due; presets editable in Settings | snooze and done with the three gestures | notice actions (§4) | S | an input to the glasses kit (K) (§6) | `DESIGN.md` §1.7 rest rule; long-press stays a no-op |
| L-26 | Glasses: Shop mode — the Buy list where tap ticks at once; ticked rows move to a dim "in the cart" group at the end | a shopping list ticked in a shop, one tap an item, PC or not | a check row (POPOVER `check`); the phone's copy | S | in | none: tap commits a row as everywhere |
| L-27 | Glasses glance: Main's row "2 due · 9 to buy", lens line "next 15:00 Call the shop" | the glance without opening the window | `summary()` | S | in | — |
| L-28 | Glasses: "Add…" row → keyboard → the parsed item shown (list, due) → tap keeps it | add with the phone in the pocket | `openKeyboard`; L-37's parser | S | in — the parse check is the staged confirm F16 asks for | — |
| L-29 | Glasses: the next reminder on the silent clock | a glance while silent | a silent-clock change | S | out | Silent Mode means not reading (F13); nothing moves on its own (`MOTION.md` B4) |
| L-30 | Phone: quick add from a Quick Settings tile or an app shortcut — one line into the working copy | add in two seconds | a tile; the parser on the phone | S | in | — |
| L-31 | Phone: share-sheet target "Add to Damage" — shared text or a link becomes a Do item, or Buy if picked | a recipe line, a message, a link | an `ACTION_SEND` activity | S | in | — |
| L-32 | Phone: a home-screen widget — today's items and the Buy count, with tick boxes | a glance without the app | a collection widget; new Android code | M | later | — |
| L-33 | Phone: an Android notification per due item with Done and Snooze buttons, on a "Reminders" channel of its own | due items reach him with the glasses off | a channel outside `Phone notifications` (F14); R6 §3's Acknowledge shape | S | in | — |
| L-34 | Desk: the full editor in the web window — inline edit, move between lists, a recurrence editor, keyboard only (j/k, x ticks, e edits) | bulk work at the PC | the web interface | M | in | — |
| L-35 | Desk: a global shortcut opens a one-line prompt that adds an item | add without leaving his work | an xfconf command + a client mode of the jar (R2 §A 6) | S | in | — |
| L-36 | Desk: the panel item shows the due count; its menu lists today's items with Done; click opens the web window | ambient count; the second path when notification buttons vanish (F17) | the SNI item (R2 §A 2) | S | in, with the XFCE piece | — |

### 2.3 Ways in (L)

| id | candidate | what it gives him | what it needs | size | lean | conflicts |
|---|---|---|---|---|---|---|
| L-37 | A quick-add parser with no model: "milk", "call the shop tomorrow 3pm", "every 90 days after done: UV lamp" → list, due, recurrence; unclear words stay plain text | every interface adds well while the helper is down or limited | a small date grammar, on the PC and the phone | M | in | — |
| L-38 | Add from any notice ("Add as to-do", linking back) | a Tmux alert or a mail becomes a task | notice actions (§4) | S | later — after the popover family | — |
| L-39 | Bus rule → list line: a watcher rule may name a line ("Fresh low" → Do: Fill fresh tank), added once per alert episode, ticked when it clears; a filter's remaining-life reading the same way | the fix lands on the list, not only the alert | a field the watcher's rule does not have yet (R6 §2); the add-only writer | S | in (filter readings later: their state today is in the private annex) | one notice per episode, not two (the watcher already says it) |
| L-40 | From mail and messages once those windows exist: the helper proposes an event or to-do as a question, never adds silently | appointments out of mail | Mail/SMS windows; H-10 | M | later | new windows wait (§14.1 R13) |
| L-41 | Import the hub's default shopping list once; showing Damage's lists inside the hub is out | nothing lost if it holds anything | one GET with the watcher's read-only token | S | only if he says it holds anything | nothing here writes to Home Assistant (§14.4) |
| L-42 | From his other Claude Code sessions: `damage-list add` (or the same operations) | "remind me to merge this Friday" from any terminal | the POPOVER §5.2 CLI pattern; the local command with its local secret (`INTERFACES.md` §4.1) | S | in | — |

### 2.4 Delivery (L)

| id | candidate | what it gives him | what it needs | size | lean | conflicts |
|---|---|---|---|---|---|---|
| L-43 | Where a due item shows: the one notice record on the PC (`INTERFACES.md` §4.3), reflected as a glasses notice, the phone notification and the desk notification; the TV zone only (no pop-up there) | due items reach him wherever he is | a delivery hook per interface | M | in | — |
| L-44 | Who fires: the PC raises one notice record per occurrence and every interface reflects it; the phone fires from its own copy only while the PC has not confirmed the occurrence, and that notice is marked "may repeat" | works away from home; a duplicate is said, never a miss | the occurrence states (L-06); the phone's queue | M | in (`INTERFACES.md` §4.3) | — |
| L-45 | No model call anywhere in the firing path; the text is fixed when the item is saved | due items fire with the helper down (F5) | — | S | in | — |
| L-46 | The delivered state is written only after one interface confirms it; a failure is said (status, journal) and tried again next tick | aria lost failures (F4, F6) | the occurrence states | S | in | — |
| L-47 | After a stop mid-delivery an occurrence left "firing" goes out again marked "(may repeat)" | never a missed one (F2's sketch) | the start-up scan | S | in (§6) | aria chose at-most-once |
| L-48 | Quiet hours: due items inside are held and go out together at the end; an item marked `urgent` passes | no night notices, nothing lost | hours; the reserved `severity` field (L-01) | S | in (§6) | — |
| L-49 | Grouping: items due in the same minute make one notice "3 due" that opens Today | one interruption, not three | the notice thread `reminders.due` | S | in | — |
| L-50 | Caps: at most N reminder notices an hour, the rest grouped | guards against a bulk import or a helper burst | a counter | S | later — matters for helper items only | — |
| L-51 | Repeat until acted on: an untouched notice comes back after 10 / 30 / 60 min | a second chance on silent glasses | a re-fire on the occurrence | S | a setting, off by default | no buzzer (`DESIGN.md` §0); the phone notification already stays |
| L-52 | Event lead time: N min before (default 10); all-day events at the morning hour | G2CC's lead (F6) | lead on the event | S | in | — |
| L-53 | A morning digest at a set hour: one notice "today: 2 to do, 1 event 14:00, 6 to buy", no model | the day in one glance | a scheduled job | S | in | — |

### 2.5 The helper (H)

| id | candidate | what it gives him | what it needs | size | lean | conflicts |
|---|---|---|---|---|---|---|
| H-01 | Nine operations as loopback MCP tools — `query`, `add`, `edit`, `tick`, `snooze`, `move`, `drop` (to trash), `add_event`, `edit_event` — and nothing else; every built-in tool off; the endpoint checks Origin and a bearer (`INTERFACES.md` §4.1); the service refuses to use the helper unless the session's first message lists only the list operations as its tools | it keeps the lists current and can do nothing else (F7) | R3 §D's recipe (inferred as a whole, F8); a small endpoint in the service | M | in (§6) | — |
| H-02 | Read-only context: the time and zone, the item and op history, today's events; later the watcher's current alerts | "what did I buy last week", "what's due" | read operations | S | in; bus read later | — |
| H-03 | Audit level "Changes": every helper op with its one-line reason, Undo on each, "undo all since…" | trust without watching it work | the op log | S | in | — |
| H-04 | Wakes on his message from any interface | it answers when asked | the channel to the process | S | in (§6) | — |
| H-05 | Two scheduled wakes (morning tidy, evening review) that skip when nothing changed since the last | lists kept current with no loop | a deterministic "anything new?" check before a turn | S | in (§6) | — |
| H-06 | Event wakes: a shared line the parser could not read, a bus alert raised, later a new mail | messy inputs sorted | triggers | S | later | each wake spends from his limits |
| H-07 | One long-lived stream-json process idle between wakes; `/clear` daily; each wake reads through `query`, never from memory; its transcript cleared daily, the op log is the record | his always-on session at low cost; no stale memory (F7) | R3 §B, §C | M | in | whether an idle process makes requests is unknown (§7) |
| H-08 | A fresh process per wake instead | no idle surprises, less memory | — | S | out — a fallback only if the idle probe shows requests | his "always on" (F1) |
| H-09 | Talking to it: glasses — "Ask…" row → keyboard → reply as a DECK popover; web, desk, phone — a text box with the reply inline; TV — its last note only | one helper from every screen | DECK; the web interface | M | in | the popover family is not built (F15) |
| H-10 | Its questions arrive as ASK popovers (glasses) or buttons (elsewhere); an unanswered one waits, never defaults on a clock | "Buy or Do?" without a guess | ASK, persistent | S | in | NO TIMEOUTS |
| H-11 | Apply or propose: adds and what he asked for are applied; edits and drops of items he wrote come as one ASK with checks | it cannot quietly rewrite his words | a policy per operation | S | in (§6) | — |
| H-12 | Status wherever it appears, and on Main's row when not up: up · working · limited until 15:45 · login expired · off | he knows why it is quiet (F9) | `rate_limit_event`, `assistant.error`, init fields (R3 §B) | S | in | — |
| H-13 | Held messages: while down or limited, his messages wait in a visible queue (each with its age), run when it is back, or he drops them | nothing he said is lost or run unseen | a queue on the PC | S | in (§6) | — |
| H-14 | Its own config directory and a one-year token in the gitignored config, not his login; the renewal becomes a yearly Do item | no refresh clash with his sessions (F9); his CLAUDE.md never loaded; transcripts apart | `claude setup-token`; `CLAUDE_CONFIG_DIR` | S | in (§6) | — |
| H-15 | Haiku 5.5 pinned by full id at low effort; a Settings row to change it | the smallest draw on the shared limits (F8) | `--model claude-haiku-5-5 --effort low` | S | in (§6) | Music's Ask lane uses Opus (`MUSIC.md:18`) |
| H-16 | Guards: a bound per wake — `--max-budget-usd` (in the installed `--help`), or `--max-turns` only once a probe shows the installed CLI honours it (§7); a daily wake cap; scheduled wakes stopped above a share of the usage window read from `rate_limit_event` | it cannot take the last of his limit | R3 §C fields; his number | S | in (§6) | the figure's exact meaning is unknown (§7) |
| H-17 | Isolation: a cwd outside any repo, `CLAUDE_CODE_DISABLE_CLAUDE_MDS=1`, strict MCP config, `dontAsk`, prompts none, no secret on argv | developer rules and his projects stay out | R3 §F | S | in | — |
| H-18 | Truth rules in its prompt and its tool results: an empty answer reads "nothing recorded"; an edit is claimed only from a tool result | the "no work today" and "it said it did" lessons (F7) | prompt text; result shapes | S | in | — |
| H-19 | Bus: no bus tool; a bus action it suggests arrives on his interface as the same guarded confirm a tap raises — the helper passes only an action id, and the confirm's text and target come from the allowlist entry; never on the TV, which has no bus action | a model never switches anything on the bus, and never words the confirm | the guarded path (S3) | S | in (§6) | the hub holds the limits (§14.4) |
| H-20 | One helper for every window ("one brain"): Music's Ask lane and later Mail through the same session | one context, no contradicting assistants (F7) | a wider tool set later | M | later — after Reminders proves it | `MUSIC.md:18` |
| H-21 | Seed recurring maintenance from his bus notes, read once with his go, proposed as one ASK | a starter maintenance list without typing | a one-time read | S | later | the notes hold private data |

### 2.6 Voice (V)

| id | candidate | what it gives him | what it needs | size | lean | conflicts |
|---|---|---|---|---|---|---|
| V-01 | Voice as its own process on the PC holding the one GPU model, using the G2CC daemons' framing; Damage never imports it | the stack he chose, one model copy (F10) | the 6.3 GB audio venv or a fresh copy | M | in, when voice is built | the GPL-3 dependency stays in that process (F10; not legal advice) |
| V-02 | Warm from PC start, or loaded on first use | ~0.5 s against ~11 s cold (his notes, Parakeet era; canary-qwen never timed) | ~7 GB VRAM (his estimate, not measured) | S | a setting, warm by default | — |
| V-03 | Push-to-talk per interface: a phone button (app, widget), a desk shortcut, a "Dictate" row on the glasses (tap starts, tap stops) | voice with nothing listening | a capture path per interface | M | in (§6, the voice design) | — |
| V-04 | The ring's long-press as push-to-talk | one press from anywhere | the long-press row | S | out | `DESIGN.md:35`: off by default, the most common accidental press |
| V-05 | Capture through the phone from his wireless microphone over Bluetooth (his earlier daily source) | the source his ASR was tuned on | the device still owned (unknown) | M | his call (§6, the voice design) | — |
| V-06 | Capture from a microphone at the desk | desk voice with no phone | a mic on the PC (unknown) | S | his call (§6, the voice design) | — |
| V-07 | The phone's own mic for short commands only | no extra hardware | — | S | out — his 2026-06-11 ruling stands (F11) | never the phone's mic |
| V-08 | Earbud capture over the call profile | hands-free | music on those earbuds stops while listening | M | out | F11: rejected 2026-08 |
| V-09 | An audio wake word or always-on listening | hands-free start | a device always listening | L | out | F11, F12 |
| V-10 | A confirm card for every voice change: "You said: …" Confirm · Retry · Cancel on the interface that started it | nothing misheard reaches a list (his CONFIRM_STT) | CONFIRM | S | in (§6, the voice design) | — |
| V-11 | Simple commands with no model ("add milk", "done milk", "what's on Buy") through the parser; the rest to the helper | quick and free for the common case | L-37 | S | in | — |
| V-12 | Spoken replies on the active output; music there pauses for the speech; no active output, text only | one output at a time kept (§14.3 4) | Kokoro per-sentence streaming; the player's pause | M | in (§6, the voice design) | — |
| V-13 | Kokoro text preparation: no markdown, no parentheses, long runs split, code never read | clean speech (R5 §A) | — | S | in | — |
| V-14 | Speak a due reminder aloud on the active output | heard with the glasses off | V-12; quiet hours | S | later, off by default | — |
| V-15 | Keep the audio and transcript of each voice add for a set number of days | check what was heard | a folder on the PC | S | later | — |

**Count: 89 candidates** (L 53 · H 21 · V 15).

## 3. Reminders beside the existing windows

| window | what Reminders shows or does with it | shared (one copy on the PC) | per interface |
|---|---|---|---|
| Main | a row: counts, next due, the helper's state when not up (L-27, H-12) | the counts, derived from the lists | which row is focused |
| Settings | a Reminders category: Notify · due, Notify · events, Phone · due, quiet hours, morning hour, snooze presets, lead time, Shop-mode order; Helper rows (on, model, wakes, usage share); Voice rows later | the values (they already sync) | the cursor |
| Reader | nothing; "finish this book" is out — Reader keeps the place | — | — |
| Tmux | the helper is not a tmux session (stream-json is not a terminal view); Tmux alerts stay their own notices | — | — |
| Files | the helper's decks and answers sit where POPOVER §5.4 puts them; a plain-text export of a list later | the files | — |
| Torrents | nothing; Notify · done already says "finished" | — | — |
| Music | shares the active output with spoken replies (V-12); its Ask lane is a separate Opus one-shot (H-20) | the queue and output choice | — |
| Games | nothing | — | — |
| Feed | nothing; Flag already marks read-later | — | — |
| the alert watcher | a rule may add one list line (L-39) | the rule and the line | — |

The Reminders window itself: **shared** — items, lists, events, occurrences, snoozes, done states, the op log, the
helper's queue and status, settings. **Per interface** — which list is open, the cursor (anchored to an item id), the
scroll, an open item, Shop mode, a draft in an add box.

## 4. What this asks of the app contract

- **Content kinds:** an item row with a check state, a due label and a source mark; grouped sections (Overdue, Today,
  later; "in the cart"); an event row with start and end; counts for a summary; a status line with an age; a headline
  block ranked for a dashboard zone (the TV front zone, Main's lens), so an interface can take "the top N" without
  knowing the window; a structured recurrence with its human label supplied by the window.
- **Actions:** tick and untick (reversible, idempotent, numbered per device so a replayed phone queue does nothing
  twice — `INTERFACES.md` §4.4); snooze with a choice of presets; done; edit text (text input); move between lists;
  add from free text with the parsed result shown before keeping; drop to trash; undo of a named op; answer a
  question with choices (ASK); and, for the bus later, a guarded action, which carries its confirm. Each action says
  whether it is reversible, so an interface knows where the cursor may rest.
- **Notice actions:** a notice carries actions (Done, Snooze presets) as well as a target — today a notice only opens
  its target (`WindowContract.kt:273`). The notice itself is the one record of `INTERFACES.md` §4.3.
- **Live values:** absolute instants plus a zone, so each interface draws "15:00" or "in 20 min" itself with no push
  each minute; the helper's status with the time it was true; each copy's age.
- **Position anchors:** an item id, never an index (a cursor survives an insert from another interface); a list id; an
  occurrence id for notices; a deep-link target `item:<id>` / `event:<id>`.
- **Writers:** every change says who made it (device, helper, bus rule), so interfaces can show it and the PC can
  check it.

## 5. Failure cases a design must answer

| case | what happens |
|---|---|
| The PC out of reach | the phone works from its copy (Reminders is on its away list): adds and ticks queue ("3 changes waiting"), due items fire from the phone marked "may repeat" (L-44), the helper says "on the PC — not reachable"; the TV and desk have nothing newer and say so with the age |
| A stale value | due times stay right (absolute instants); an item done elsewhere still shows until the copy refreshes — the age line says how old it is |
| A refused action | a tick on an item dropped elsewhere is refused and said, with "restore?"; a losing edit is said on the item (`INTERFACES.md` §4.4) |
| A device never approved | it sees the pairing code only — no list content (reminders are personal) |
| The helper down, limited or logged out | the window works fully; status says which (H-12); his messages wait (H-13); a scheduled wake missed while down runs at most once, late; no automatic retry loop (`CLAUDE_CODE_RETRY_WATCHDOG` stays unset) |
| The helper does something wrong | Undo from the Changes level (H-03); its reach is nine operations (H-01) |
| The helper starts with more tools than the list operations | the service refuses to use it and says so on its status (H-01) |
| The helper runs long | the per-wake bound ends the turn (`--max-budget-usd`, or `--max-turns` once the probe proves it, H-16); said on its status |
| The glasses asleep at a due time | the Silent Mode box shows, display-only, left unread; the phone notification carries it |
| A stop mid-delivery | the start-up scan sends it again, marked "(may repeat)" (L-47) |
| The bus crosses a time zone | wall-time items follow the phone's zone (L-19) |
| Voice hears nothing usable | nothing changes; the confirm card says so; GPU busy or the model not loaded is said |

## 6. Decisions

**Put to Adam:** the rows of `REPLAN.md` §15 that concern this document: lists and the calendar · what the helper
does alone · when the helper wakes and its model · the helper's login and three probe runs · a notice dismissed
once · alert severity (the field Reminders reserves) · the TV's level (its tick) · when the TV goes into use (its
front zone).

**The lead's defaults** (taken as written unless he objects):
- Any number of lists, three shown at first (Buy, Do, Calendar); new lists made at the desk (L-02) · no rework
  later; the glasses stay simple.
- Both kinds of recurrence: on the calendar, and counted from when it was done (L-04, L-05) · maintenance gets done
  late; the next one should move with it.
- A re-sent reminder is marked "may repeat" rather than risk a miss (L-47) · a missed reminder costs more than a
  repeat.
- Quiet hours exist, held items go out together at the end, urgent ones pass; his hours are asked at the build
  (L-48) · no night notices, nothing lost.
- The helper changes the lists only through its operations; no built-in tools, no hard delete (H-01) · his own
  record: given a shell, his last assistant went around the confirm gate.
- His messages wait visibly while the helper is down or limited, and run when it is back (H-13) · nothing lost or
  run unseen; he can drop any.
- The helper has no bus tool; a bus action it suggests passes only an action id, and the confirm's text and target
  come from the allowlist entry (H-19) · a model never switches anything on the bus, nor words the confirm.

**Deferred:**
- To the contract sitting: merge and idempotency (L-17; `INTERFACES.md` §4.4).
- To the contract sitting: how notices travel (L-43, L-44; `INTERFACES.md` §4.3).
- To the glasses kit (K): the reminder notice's gestures (L-25).
- To the voice design: the capture device — his wireless microphone through the phone, or a desk mic; never the
  phone's mic (V-05, V-06, V-07).
- To the voice design: voice starts only on a press (a phone button, a desk shortcut, a glasses row); no wake word;
  not the ring's long-press (V-03, V-04, V-09).
- To the voice design: every voice change is confirmed on screen before it lands (V-10).
- To the voice design: speech plays on the active output with music paused for it; no active output, no speech
  (V-12).

## 7. Facts only Adam or a test at a device can settle

- His quiet hours, morning hour, snooze presets, and whether he crosses time zones in the bus — Adam.
- Whether he wants any outside calendar, and which — Adam.
- Whether the hub's default shopping list holds anything — Adam, or one read with his go once the watcher's token
  exists.
- His plan's window sizes (`/usage`) and how much of them the helper may take; what `rate_limit_event.utilization`
  measures — Adam; the next real limit with `--debug-file` (R3).
- Three probe runs of the CLI (his go; `--debug-file` on each; a config directory of their own, with the helper's
  own token where a run needs a model, never his login): **(1)** the helper's exact argv — whether init's `tools`
  array holds only the list operations (does `--tools ""` remove the tool-search tool; what `--setting-sources` and a
  separate config directory drop), and whether `--max-turns 1` against a prompt that needs two tool calls ends with
  `error_max_turns`; **(2)** the lines an expired or missing login and a refused request produce, and today's exit
  codes; **(3)** an idle process left for some hours — whether it makes requests (idle compaction, a title request),
  and its resident memory.
- Whether a one-year token works for this use and its renewal steps — settled when he creates it (one
  `setup-token` run, his go).
- Which capture device he still has and wants — Adam (the voice design).
- canary-qwen's latency and real VRAM on the 3090; whether the audio venv still loads on the current driver — one run,
  his go (R5).
- Whether the TV's browser passes arrow and OK keys to a page (for L-22) — the key-logging test page at the TV (R1).
- Whether the phone fires a minute-exact reminder on time under Doze from the foreground service — measured on the
  Pixel.
