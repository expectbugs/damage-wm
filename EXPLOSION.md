# The app-layer scope explosion (2026-08-31)

**This is the DELIBERATE feature-creep phase** of the locked methodology — *"a couple hundred
ridiculous feature-creep scope explosions → heavy refinery to bring it back to reality."* Every
idea below is deliberately included rather than pre-filtered; the refinery pass (Adam's) is what
cuts it back. Grades are the builder's first-pass opinion **for the refinery to override**,
nothing more. Nothing in this file is committed work.

**Inputs:** [`CAPABILITIES.md`](CAPABILITIES.md) (what the hardware can do, graded),
[`DESIGN.md`](DESIGN.md) §0 (what is already excluded) and §4.6 (the window contract),
`core/…/shell/WindowContract.kt` (the contract as code), the measured latency curve
(`overview.md` §5.2), and `/home/user/G2CC/server/src/windows/` — twenty shipped windows of
interaction design, read for facts and lessons, no code taken.

**Precedence as usual:** `overview.md` wins on facts, `CLAUDE.md` on rules, `DESIGN.md` on shell
design. This file proposes; it never overrides.

---

## 0. Rules of engagement

### 0.1 Already excluded — do not re-grade these

`DESIGN.md` §0 is binding on this whole document. No idea below may need: the piezo (never),
per-window gesture grammar (tap/double-tap/scroll/long-press mean the same thing everywhere; only
notifications differ), off-panel scratch (does not exist), fades/dissolves, dithering, wear/unwear
differentiation (banked), head tracking as a *required* input (opt-in per feature only), a quit
path, split view (deferred), or the 16 px gutters as content. Where an idea below brushes one of
these, the conflict is named in its row.

### 0.2 What every window declares (the §4.6 contract, as built)

`DamageWindow`: `view()` returning ListView / DocView / CanvasView (List and Document are
WM-driven and nearly free; Canvas owns its own damage) · `title()` · `summary()` (**cheap and
side-effect-free** — called for every window on every Main render) · `icon` · `dirty` ·
`needs` (HOST / PHONE_APIS / BLE — the shell marks the window unavailable and says so, §10.5) ·
`preferredHeight` (global-default pattern) · `appSettings()` (its Settings directory) ·
`saveState()/restoreState()` (full persistence, §9.1 — mode included, not just position) ·
`styleTransform` (per-app typography, wired by the shell) · `onTypedText(line)` (typed lines
from the replicas — **always staged behind the window's own confirm**) · `back()` /
`levelDepth()` (the unified back stack and the bottom divider's depth segments).

### 0.3 The price list every idea is graded against

**Measured** (`overview.md` §5.2, n=1,488 flushes, PC-direct): `ms ≈ 60 + bytes/50`.
**Byte counts below are modeled** (the `DESIGN.md` §4.6/§8.4 render-derived numbers) and re-priced
on that measured curve — say which is which when quoting.

| interaction | bytes (modeled) | latency on the measured curve |
|---|---|---|
| list scroll, one notch (shift + 2 fills) | 860–1,430 B | ~77–90 ms |
| document scroll step | 292–486 B | ~66–70 ms |
| notification appear/dismiss | 210–650 B | ~64–73 ms — effectively free |
| full content repaint 608×416, text | 2.5–5 KB | ~110–160 ms |
| full content repaint, dense | 3.8–6.3 KB | ~136–186 ms |
| dense full-screen keyframe | ~10 KB | ~200–270 ms → **~4 fps sustained** |
| heaviest frame yet observed | 24.6 KB | 540 ms (measured directly) |

Chrome rides content flushes free (§8.3). Depth is +4 B per rect. The **texture cache is in the
firmware but NOT in the compositor** — first-light items 19–20 gate adoption; no v1 idea may
depend on it.

### 0.4 Grades

| grade | means |
|---|---|
| **v1** | belongs in that window's first cut |
| **v1.5** | first follow-up wave |
| **v2** | a later wave, design kept open for it |
| **future** | someday; parked deliberately |
| **never** | conflicts with a decision — the row names it |
| **PROBE** | blocked on a named probe (§17) before it can even be graded |

### 0.5 The G2CC bank

What each candidate mines (read-only, facts only): `mail.ts` (Maildir + reply/forward/compose via
msmtp; the list/read/focus-flip pattern) · `sms.ts` (threaded SMS/MMS, phone as Telephony
provider) · `music.ts` + `media.ts` (his Music app; third-party phone media via
MediaSessionManager + LRCLIB lyrics) · `calendar.ts` (Google Calendar agenda, read-only, 15-min
sync) · `files.ts` (tree + preview/image viewer + ops + trash — the ACTIONS-level pattern §4.6
generalised) · `timers.ts` (durable timers, fires via the notification layer) · `notices.ts`
(notification-history browser) · `search.ts` (one results list across sources, hand-off to the
owning window) · `games.ts` (rpg-cli, chess vs Stockfish, Universal Paperclips, Blackjack, FF1)
· `deliveries.ts` (carrier mail → tracked list). *(`scout.ts` / `aria.ts` / `cc.ts` were in the
bank too — excluded outright, see §8.)*

---

## 1. MAIL

**PC-side by design** (`DESIGN.md` §4.5: Maildir + mbsync on beardos). The §13 promise this
window pays: *"Mail and MMS with embedded images."* G2CC lineage: `mail.ts`.

**Declares:** List (inbox/threads) → Document (read view) → List (actions) · needs **HOST** ·
face **Fira Sans** (already locked for "Mail and other dense lists", §Type) · height global ·
summary `3 unread · Jane Doe + 2` (the §4.2 mock, verbatim) · state: folder, cursor, open
message, read-view offset · settings: account(s), notify rules, list density.

| # | idea | note | grade |
|---|---|---|---|
| 1.1 | Inbox list — sender bright, subject dim, unread by brightness, lens = 2-line full summary | the §4.2 lens pattern verbatim; a scroll notch is ~80 ms | v1 |
| 1.2 | Read view as endless-scroll Document with real typography | the entire point of the platform; 292–486 B per step | v1 |
| 1.3 | HTML mail → flowed text, PC-side sanitised | content-role work on beardos (w3m/lynx-class dump); glasses never see markup | v1 |
| 1.4 | **Inline images in mail bodies** | the Reader `ImageDecoder` pipeline exists and is measured (380/404 decode); MIME parts feed the same seam | v1 |
| 1.5 | Unread badge + dirty tick + top-divider mark | contract fields already exist | v1 |
| 1.6 | Actions level: mark read/unread, archive, delete-with-confirm | destructive rows never at cursor rest (§1.7); Maildir flag ops on the host | v1 |
| 1.7 | Notification tap opens the message (deep link) | needs the §16.1 `open(target)` contract addition — shared plumbing, not Mail-specific | v1 |
| 1.8 | Read state lives in the Maildir itself (S flag) | makes read-state one across PC/phone drivers for free — the §16.4 problem solved by ownership, worth copying elsewhere | v1 |
| 1.9 | Threading (References/In-Reply-To collapse) | list rows = threads, count badge; G2CC did flat + focus-flip | v1.5 |
| 1.10 | Quick-replies — canned lines list, confirm-to-send via msmtp | the Tmux KEYS pattern on mail; config-driven like quick keys | v1.5 |
| 1.11 | Reply/compose via typed text from the replicas | `onTypedText` exists; line-based body builder, always confirm-to-send (G2CC composed via msmtp already) | v1.5 |
| 1.12 | Attachment list; image attachments render inline; "save on PC" action | save = content-host file op, loud result | v1.5 |
| 1.13 | Multi-account (mbsync profiles as folders) | folder rows like Reader's shelf | v1.5 |
| 1.14 | **Deliveries view** — carrier/shipping mail parsed to a tracked-package list | G2CC `deliveries.ts` shipped exactly this (15-min Gmail sync); a Mail sub-level or its own small window — refinery's call | v2 |
| 1.15 | Per-sender notification rules (VIP-only interrupts) | extends the §4.5 source filter downward, honouring its focus-stealing logic | v2 |
| 1.16 | Mail search (typed) | or leave it to the universal Search window (§12) | v2 |
| 1.17 | Calendar-invite detection → Calendar hand-off | .ics part → §16.2 hand-off | v2 |
| 1.18 | Snooze (re-notify at T) | needs durable scheduled work — same substrate as Timers | future |
| 1.19 | Cached-glyph list rendering | texture cache; gated on items 19–20 + compositor adoption | future |
| 1.20 | PGP signature badge | ridiculous, cheap to draw, low value | future |

**Refinery questions:** one account or all? Is compose in scope at all, or is Mail read-and-triage
(G2CC had compose; the ring cannot type — typed text comes from replicas only)? Deliveries inside
Mail or standalone?

---

## 2. SMS

**Phone-side by design** (§4.5: phone is the Telephony provider). G2CC lineage: `sms.ts`
(threaded SMS/MMS). Honest per §10.5: unavailable in bridge/laptop configurations, and says so.

**Declares:** List (threads) → Document (thread view) → List (actions) · needs **PHONE_APIS** ·
face **Fira Sans** · summary `Mom · "on my way"` (§4.2 mock) · state: thread, offset · settings:
notify per-thread, quick-replies.

| # | idea | note | grade |
|---|---|---|---|
| 2.1 | Threaded list, newest-first, unread by brightness | | v1 |
| 2.2 | Thread view: speaker as brightness + indent, never boxes | ink discipline §4.2 — structure from spacing/brightness; bubbles are fill, fill is waste | v1 |
| 2.3 | **MMS images inline** | the other half of the §13 promise; same decoder seam | v1 |
| 2.4 | Reply via typed text, confirm-to-send, phone transmits | `onTypedText` + a phone-side send op over the seam | v1 |
| 2.5 | Notification deep link to the thread | §16.1 | v1 |
| 2.6 | Send/receive failures surfaced on glass | loud per the absolute rules; G2CC surfaced send errors on glass (its terminal lesson generalised) | v1 |
| 2.7 | Contact names from the phone's contacts provider | PHONE_APIS; numbers-only fallback stays honest | v1 |
| 2.8 | Emoji/glyph policy: unrenderable codepoints draw the visible tofu box | the locked faces are Latin-heavy; the texture-table idiom (never silently dropped) satisfies NO TRUNCATION; a small drawn set for the top handful is the upgrade path | v1 |
| 2.9 | Quick-replies list, config-driven | Tmux KEYS pattern again | v1.5 |
| 2.10 | Group threads (names list, per-speaker brightness levels) | | v1.5 |
| 2.11 | Compose new (contact picker list → typed body) | | v1.5 |
| 2.12 | Drawn mini-emoji set (~20 common) as icons | §2.4 rule 9 drawing rules; pays the 2.8 upgrade | v2 |
| 2.13 | SMS search | or universal Search | v2 |
| 2.14 | RCS | depends on what the phone exposes to a normal app | PROBE |
| 2.15 | Scheduled send | same substrate as 1.18 | future |

**Refinery questions:** which contact fields (names only, or photos-as-thumbnails someday)? Is
compose-new v1 or does reply-only cover the daily loop?

---

## 3. MUSIC

**Phone plays the audio** (G2CC decision 2026-08-05: volume is max and phone-owned); the library
can live on the PC. Two G2CC ancestors deliberately distinct: `music.ts` (his player) and
`media.ts` (third-party phone media via MediaSessionManager + LRCLIB lyrics). §4.5 already names
Music a notification source (phone-side, MediaSessionManager).

**Declares:** List (Now Playing as a one-row lens + actions) → List (library) · needs
**PHONE_APIS** (+HOST for the library) · face Clear Sans · summary `▶ Bowie — Blackstar`
(§4.2 mock) · state: view, library cursor · settings: source, notify on track change.

| # | idea | note | grade |
|---|---|---|---|
| 3.1 | Now Playing: title/artist/album, position as a coarse block bar | §4.5b block-bar rule; repaint on track change + coarse position ticks, not per second | v1 |
| 3.2 | Transport controls at the actions level (play/pause/next/prev) | phone MediaSession commands over the seam | v1 |
| 3.3 | Track-change notifications | the §4.5 Music source, already designed | v1 |
| 3.4 | Third-party media control (whatever the phone is playing) | MediaSessionManager remote — G2CC `media.ts` proven shape; makes the window useful before any library work | v1 |
| 3.5 | Album art thumbnail on Now Playing | images are cheap now; box-sample to ~120 px | v1.5 |
| 3.6 | Library browse (PC library over the content port, folders like the Reader shelf) | | v1.5 |
| 3.7 | Lyrics view (LRCLIB, G2CC precedent), Document mode | paced line-advance on the timestamps = one small flush per line — a deliberate radio spend, settable | v1.5 |
| 3.8 | Queue view | | v2 |
| 3.9 | Coarse seek (±10 s notches at an actions row) | | v2 |
| 3.10 | Playlists | | v2 |
| 3.11 | Volume | **never** — phone-owned at max is a recorded G2CC decision of Adam's | never |
| 3.12 | Output target picker (phone / PC speakers) | content host can run a player too; ambitious plumbing | future |
| 3.13 | Song recognition ("what is playing near me") | needs the mic — much later by his word | future |

**Refinery questions:** MediaSession-remote first (3.4) and grow the library later, or the full
player day one? Does lyrics' per-line radio spend feel right on glass?

---

## 4. CALENDAR

G2CC lineage: `calendar.ts` — Google Calendar agenda, **read-only**, synced PC-side every 15 min.
That shape (HOST need, no phone dependency) carries over cleanly.

**Declares:** List (agenda) → Document (event detail) · needs **HOST** · face Clear Sans, and
this is the §Type note's "digit-heavy surface" — **B612 revisit candidate** · summary
`Standup 09:30 · in 22m` (§4.2 mock) · state: cursor, day offset · settings: calendars on/off,
lead-time for reminders.

| # | idea | note | grade |
|---|---|---|---|
| 4.1 | Agenda list (next N events; lens = full title + time + countdown) | | v1 |
| 4.2 | The Main summary row with live countdown | already the §4.2 mock; countdown text changes ride idle chrome ticks free | v1 |
| 4.3 | Event detail: time, location, description, attendees as a Document | | v1 |
| 4.4 | Event-start reminders as notifications (lead time settable) | generated by our own sync = a Damage-specific source, inside the §4.5 filter's logic | v1 |
| 4.5 | Day view (today as a list with hour landmarks) | landmark rows like Settings' old headers | v1.5 |
| 4.6 | Multiple calendars, per-calendar toggle + brightness tier | | v1.5 |
| 4.7 | Imminent-event escalation: the reminder box steps forward on the depth ladder as T approaches | existing notification depth language, zero new machinery | v1.5 |
| 4.8 | Week strip (7 columns of block marks, wide-and-short) | §4.5's shape heuristic: width is the cheap axis | v2 |
| 4.9 | Month mini-grid, drawn coarse blocks | ink-expensive; only if the week strip proves wanted | v2 |
| 4.10 | Create/edit via typed text | G2CC stayed read-only on purpose; revisit only after typed text proves comfortable | v2 |
| 4.11 | "Join on PC" action — meeting link opens in the PC browser | the content host runs `xdg-open`; silly, real, one line | v2 |
| 4.12 | Travel-time hint | needs phone location; against the FAR/ignorable ethos | future |

**Refinery questions:** source of truth (Google sync PC-side like G2CC, or something local)?
Strictly read-only? Is the week strip wanted at all on a glanceable HUD?

---

## 5. FILES

G2CC lineage: `files.ts` — tree + preview + image viewer + ops + trash; its ACTIONS level is the
pattern `DESIGN.md` §4.6 generalised to every window. The §13 promise: *"a file manager with real
icons and thumbnails."*

**Declares:** List (browser) → List (actions per entry) / Document (text preview) / Canvas
(image view) · needs **HOST** (laptop-direct serves its own disk) · face Clear Sans · summary
`~/damagewm` (§4.2 mock) · state: cwd per root, cursor, open preview · settings: roots, hidden
files, trash retention.

| # | idea | note | grade |
|---|---|---|---|
| 5.1 | Browser list: dirs first, name + size + mtime; descend/ascend on tap/double-tap | the Reader folder grammar verbatim | v1 |
| 5.2 | ACTIONS level: Open · Copy · Move · Rename · Delete→trash · Stats | the G2CC level, generalised home; destructive placement per §1.7 | v1 |
| 5.3 | Text preview as Document (real type, endless scroll, NO TRUNCATION) | | v1 |
| 5.4 | **Image viewer** — box-sampled to the content area, 16 levels, no dithering | the Reader image pipeline unchanged | v1 |
| 5.5 | Trash with restore | G2CC had it; delete is never unrecoverable from glass | v1 |
| 5.6 | Configured roots (~/books, ~/downloads, the vault when mounted) | roots that vanish (unmounted) say so rather than erroring | v1.5 |
| 5.7 | Thumbnails on image rows | small per-row images; affordable now, cheap-at-scale later with the texture cache | v1.5 |
| 5.8 | Hand-off: .epub opens in Reader at its shelf entry | §16.2 | v1.5 |
| 5.9 | Rename via typed text | | v1.5 |
| 5.10 | PDF preview (page rasterised PC-side → image path) | content-role work; pages are just images | v2 |
| 5.11 | Video poster frame (ffmpeg first-frame) on preview | | v2 |
| 5.12 | Disk-usage view (du → block bars per entry) | wide-and-short bars | v2 |
| 5.13 | Name search within the tree (typed) | | v2 |
| 5.14 | Slideshow (auto-advance image view) | paced flushes; a setting, off by default | v2 |
| 5.15 | Remote roots over ssh (slappy) | the tmuxHosts pattern; opt-in when actually alive | future |
| 5.16 | "Fetch to phone" (file → phone download via its replica page) | plumbing oddity; park it | future |

**Refinery questions:** which roots day one? Is Move/Copy (a two-ended operation — pick source,
navigate, paste) worth its interaction cost on a ring, or is Files really browse + preview +
delete + rename?

---

## 6. TIMERS

G2CC lineage: `timers.ts` — durable timers, fires through the notification layer. **The one
window with no `needs` at all** — it runs identically in every §10 configuration, *if* its state
survives driver handovers (§16.4 — this window is why that section exists). No buzzer, ever:
fires are a §4.5 notification on glass plus the §9.3 phone escalation for sound.

**Declares:** List (pending + quick rows) → Document/lens detail · needs **none** · face Clear
Sans; countdown digits **drawn seven-segment** (`Icons.sevenSegClock` exists and is the cheap,
quality precedent) · summary `2 pending · next 14m` (§4.2 mock) · state: the timers themselves
(§16.4!) · settings: presets, fire behaviour.

| # | idea | note | grade |
|---|---|---|---|
| 6.1 | Pending list + `New 5/10/20 min` quick rows | the G2CC shape; one tap sets a timer | v1 |
| 6.2 | Durable across restarts and handovers | the §16.4 decision is the actual work; the rows are trivial | v1 |
| 6.3 | Fire = notification box + phone notification (sound lives on the phone) | §9.3 is the out-of-band channel; the box's urgency marker level 15 is finally spent | v1 |
| 6.4 | Detail view: large seven-segment remaining time | horizontal segments are single RLE runs — the silent-clock finding; repaints per minute far out | v1 |
| 6.5 | Final-minute seconds display | a 1 Hz radio spend against §5.15 deep idle — opt-in setting, default off, exactly like the silent-clock second-hand ruling | v1.5 |
| 6.6 | Named timers via typed text ("pizza") | | v1.5 |
| 6.7 | Alarms (time-of-day, repeating by weekday) | same substrate, different trigger | v1.5 |
| 6.8 | Custom presets (settable quick rows) | | v1.5 |
| 6.9 | Stopwatch (start/stop/lap; repaint on interaction + minute tick, honest about no live seconds) | | v2 |
| 6.10 | Pomodoro preset (work/break cycle with auto-notify) | composition of 6.7 | v2 |
| 6.11 | "Timer until next calendar event" | cross-window; cute | future |
| 6.12 | World clock row | | future |

**Refinery questions:** where does timer state live so a timer set phone-only fires PC-driven
(§16.4 — host-owned with phone cache, or shell-state replicated)? Is the final-minute 1 Hz spend
wanted at all?

---

## 7. NOTICES

G2CC lineage: `notices.ts` — browse the persisted notification history, newest-first; reading
marks SEEN. Damage already has the queue, grace, coalescing and read-state machinery (§4.5);
this window is its history surface. Silent-mode pops stay **unread** by design — this is where
they land for later.

**Declares:** List (history) → Document (read view) · needs none (history is shell state — §16.4
applies) · face Fira Sans (it is a dense list) · summary `4 unread` (§4.2 mock) · state: cursor,
filter · settings: retention, per-source view defaults.

| # | idea | note | grade |
|---|---|---|---|
| 7.1 | History list newest-first: source icon, line, time; unread bright | | v1 |
| 7.2 | Read view (full body, Document) | | v1 |
| 7.3 | Reading marks read; badge and divider tick clear | the §4.5 read-state rules already written | v1 |
| 7.4 | Deep link onward: tap a mail notice here → Mail at that message | §16.1 again — Notices is the second consumer | v1 |
| 7.5 | Clear-all, confirmed | | v1 |
| 7.6 | Per-source filter level (just SMS, just Damage events…) | | v1.5 |
| 7.7 | Retention setting (days / count) | | v1.5 |
| 7.8 | Re-show action (present this notice as a box again) | uses the normal queue path | v2 |
| 7.9 | Source statistics (which source interrupts most) | feeds tuning the §4.5 filter honestly | future |
| 7.10 | Notification rules editor (beyond the Settings on/off rows) | only if 1.15-class rules multiply | future |

**Refinery questions:** retention default? Does history live host-side so both shells see one
timeline (§16.4)?

---

## 8. SCOUT / ARIA / CC — excluded from this explosion (Adam, mid-explosion, 2026-08-31)

> *"Ignore Aria, CC, and Scout. Tmux is better and does all of that."* And, same message thread:
> *"Scout specifically will become something rather different, once everything else is built,
> tested and polished."*

Recorded in the §0 style so nobody re-proposes it. G2CC carried three assistant surfaces
(`scout.ts` — the model-controlled display; `aria.ts` — intents; `cc.ts` — live CC sessions).
The overlap question this section was going to hand the refinery is answered before the refinery
started:

- **Aria and CC: covered by Tmux, permanently.** The glasses watch and approve real sessions in
  real terminals — typed text with confirm, quick keys, waiting-pattern alerts, scrollback. That
  IS the daily assistant loop, running against the real thing instead of a wrapper.
- **Scout: parked, not dead.** It returns as *something rather different* only after everything
  else is built, tested and polished — and it is deliberately **not designed now**. No ideas are
  graded here, so nothing anchors what it later becomes.

Ideas that died with the section stay dead unless Scout's future self re-earns them
(model-controlled display frames, answer-prose typography, assistant-branded proactive pushes —
Tmux's waiting-pattern alerts already do the real version of the last one). The wake-word probe
leaves §17 with it. Anything assistant-shaped in the meantime starts life as a tmux session.

---

## 9. INFO — system state

**Open item #5's home** (orphaned when the info popup became the switcher; "deeper system detail
is a window" — `DESIGN.md` §4.3). It also pays a recorded debt: §4.1 narrowed the battery bars
knowing *"exact percentages live in the Info/Stats surface."*

**Declares:** List (sections) → Document (detail per section) · needs none (it reports whatever
is present) · face Clear Sans; **B612 candidate — it is the digit-heaviest surface in the shell**
· summary = the current configuration (`PC via phone · 60ms`) · state: cursor · settings: none
(it IS the diagnostics).

| # | idea | note | grade |
|---|---|---|---|
| 9.1 | Battery detail: G + P exact %, charging states, **case SoC** (sid 0x81 `caseInfo.soc` — decoded in our own captures) | the §4.1 debt paid. No ring: it has no open-source source (`CLAIMS.md`); the chrome R cell was removed 2026-08-31 | v1 |
| 9.2 | **Which configuration is driving** — the §10 row, named: `PC → phone → glasses`, path, since-when | turns the arbitration from invisible to legible; the daily-driver ops crib on glass | v1 |
| 9.3 | Link panel: ack EMA, B/s, RSSI (where readable), seam heartbeat age | all already in `LinkState`/status | v1 |
| 9.4 | Session: lease held/renewals, uptime, flushes, bytes today | journal-derived | v1 |
| 9.5 | Versions: APK/jar/`EVENCFW` capability string + tokens | the §10.12 lesson institutionalised — never the version string alone | v1 |
| 9.6 | Mode-7 flag state + sticky history | the free loss-telemetry channel, surfaced for daily eyes | v1 |
| 9.7 | Error/journal tail view (last N lines) | | v1.5 |
| 9.8 | "Why is it slow" attribution (ack / transfer / compose split) | §9.2b names it; the data is in the journal | v1.5 |
| 9.9 | Throughput self-test action (timed keyframe burst, result written to the journal) | measurement culture as a button; feeds §5 with fresh numbers | v1.5 |
| 9.10 | Tailscale peer states (aphone, slappy) | content-host exec of `tailscale status` | v2 |
| 9.11 | beardos vitals (load, disk, temperatures) | host exec; borders on a Monitoring window — see §15 | v2 |
| 9.12 | Ring firmware/version readout | needs the ring-link query nobody has implemented (G2CC §11 partial decode) | PROBE |

**Refinery questions:** read-only, or does it grow actions (force keyframe, restart transport)?
Where is the line between Info and the status bar (glance vs check is the §4.1 answer)?

---

## 10. GAMES

G2CC lineage: `games.ts` — rpg-cli, chess vs Stockfish, Universal Paperclips, Blackjack, FF1.
The *"$5 turn"* stray-tap lesson (§1.7's cursor-rest discipline) was paid here. **Licensing rule
carried from `CLAUDE.md`:** the FF1 ROM and Universal Paperclips stay out of any release —
personal-only rows. **Honesty rule:** dense full-frame is a measured 2–4 fps; anything needing
more sustained is out of reach and says so up front (§4.6's "whole story for games").

**Declares:** List (games hub) → per-game Canvas/List · needs **HOST** (engines run PC-side) ·
face n/a (drawn boards) / JetBrains Mono for text games · height: per-game (`preferredHeight`) ·
state: per-game saves via the blob · settings: per-game rows.

| # | idea | note | grade |
|---|---|---|---|
| 10.1 | Games hub list | | v1 |
| 10.2 | **Chess vs Stockfish** — drawn board+pieces, scroll = square cursor, tap = select/move, confirm on capture-into-check class moves | turn-based = damage-tiny (a move repaints two squares + clocks ≈ one small flush); thick-stroke pieces per §2.4 rule 9 | v1 |
| 10.3 | Chess: engine "thinking" in the op cell; move arrives as a delta + optional notification when parked | background completion → notify idiom | v1 |
| 10.4 | Board depth: board at content plane, floating move-hints forward | +4 B per rect; the flagship small use of stereo outside chrome | v1.5 |
| 10.5 | **2048** — native, drawn tiles | a move is LITERALLY mode-9 shifts + one fill; the single most damage-shaped game that exists | v1.5 |
| 10.6 | Blackjack (drawn cards; G2CC port of the rules, our rendering) | the $5-turn lesson enforced: Hit/Stand never at cursor rest | v1.5 |
| 10.7 | Minesweeper / solitaire class (drawn, per-cell deltas) | same economics as 2048 | v2 |
| 10.8 | rpg-cli dungeon (its text UI through List/Document) | | v2 |
| 10.9 | **FF1 via the emulator** — Canvas at native 256×240 centred (integer 1× only; 416-px content rules out 2×) | personal-only; turn-based JRPG survives 2–4 fps; whether the compositor's translation rule catches overworld scrolls is a measurement to run, not assume | v2 |
| 10.10 | Universal Paperclips | personal-only; mostly numbers = cheap list, its own weird fit | v2 |
| 10.11 | Wordle-class daily word (typed guesses) | typed text exists; five drawn tiles | v2 |
| 10.12 | Roguelike (native, glyph grid, per-cell deltas) | JBM grid; damage-tiny | future |
| 10.13 | Game Boy emulation | needs sustained fps the link does not have; would disappoint | never (measured ceiling) |
| 10.14 | Turn notifications ("Stockfish moved") when parked | | v1.5 |

**Refinery questions:** which game first (chess is the strongest fit; 2048 the cheapest build)?
Is the FF1 bridge reachable as-is from the content host (G2CC's `games/ff1/` read-only), or does
it wait?

---

## 11. FEED

The §13 promise: *"a Reddit-style feed with endless scroll."* No G2CC ancestor — G2CC could not
afford images, and a feed without images was not worth building. It is now.

**Declares:** List (items) → Document (article + images) · needs **HOST** (fetch runs PC-side) ·
face Fira Sans for the list, **Alegreya for the reading view** (it is long-form) · summary
`12 new · HN + 3 feeds` · state: per-feed cursors, read marks · settings: feeds, fetch cadence,
image loading.

| # | idea | note | grade |
|---|---|---|---|
| 11.1 | RSS/Atom aggregation, PC-side fetch on a pacer | content-role cron; the glasses see a list | v1 |
| 11.2 | Item list with unread brightness + per-feed folders | Reader folder grammar | v1 |
| 11.3 | Article view: endless scroll, inline images | the platform's showcase; everything exists | v1 |
| 11.4 | Read/unread tracking + summary count | | v1 |
| 11.5 | Reddit via its public JSON (old-reddit listing shape) | one fetcher among feeds | v1.5 |
| 11.6 | Hacker News (Algolia/API) | | v1.5 |
| 11.7 | Comment threads, indent by brightness tier | no boxes, no rails — §4.2 discipline | v2 |
| 11.8 | Read-later queue (flag → its own folder) | | v2 |
| 11.9 | "Open on PC" action per item | `xdg-open` on beardos, the 4.11 trick | v2 |
| 11.10 | Long article → Reader hand-off | | future |
| 11.11 | YouTube subscriptions (poster + title; play lands on the PC) | ridiculous; the poster is just an image | future |

**Refinery questions:** which sources day one? Fetch cadence vs staleness honesty (§10.5 says
say it, not hide it)?

---

## 12. SEARCH

G2CC lineage: `search.ts` — one results list across sources, hand-off to the owning window.
G2CC drove it by dictation; **Damage's enabler is typed text, which now exists.** Whole window
graded **v2 overall**: it is only as good as the sources built before it.

| # | idea | note | grade |
|---|---|---|---|
| 12.1 | Typed query → one results list across mail/files/notices/notes | per-source isolated failures (G2CC's shape) | v2 |
| 12.2 | Hand-off to the owning window at the hit | §16.2's consumer | v2 |
| 12.3 | Inline read view for source-less hits | | v2 |
| 12.4 | Recent-queries list (re-run on tap) | typing is expensive on glass; memory is cheap | v2 |
| 12.5 | Live tmux-session grep as a source | odd; occasionally magic | future |

---

## 13. HEALTH

**Every open source of ring data is closed off — this window is DEFERRED, not cheap (2026-08-31).**
Chased to the end and reverted: the glasses never relay `RingRawData` (firmware source), the ring
has no standard Battery Service and no advertised battery, its vendor link (`bae80001-…`) is
request/response with a **custom checksum** (offline scan matched no standard CRC-16), and
**Faceclaw does not read ring battery either** — only the closed Even SDK does (full evidence in
`CLAIMS.md`). So every biometric here needs reverse-engineering the ring's vendor protocol AND
writing to the ring (a live input device, §11.5). That is the real cost of this window: a
protocol-RE sub-project, not an afternoon. Worth it only if the biometrics (hr/steps/sleep) are
wanted for their own sake — not for a battery gauge.

| # | idea | note | grade |
|---|---|---|---|
| 13.1 | Reverse-engineer the ring's vendor protocol (checksum + poll requests) | the gate on EVERYTHING below; writes to the ring | **the sub-project** |
| 13.2 | Today panel: steps, kcal, last hr | | v2 (post-probe) |
| 13.3 | HR sparkline as coarse blocks | host stores history | v2 |
| 13.4 | SpO2 / HRV / temp rows | | v2 |
| 13.5 | Ring battery detail + charge state (would re-add a chrome ring cell, removed 2026-08-31) | needs the §13.1 protocol RE | v2 |
| 13.6 | Big-digit live HR view (workout) | 1 Hz-class radio spend, opt-in like 6.5 | future |
| 13.7 | Threshold alerts | | future |
| 13.8 | Direct ring link (Faceclaw decodes gestures + data on it) | a second central on the ring; real complexity, weigh against what the relay gives free | future |

**Refinery question:** window, or just a Main summary row + Info section? (The probe decides
whether the question is even live.)

---

## 14. WEATHER

New candidate — no G2CC ancestor, no design mention, included because it is the cheapest
high-daily-value window on the list and one row of it already improves Main.

**Declares:** List (now + days) → Document (detail) · needs **HOST** (PC fetch) · face Clear
Sans / B612-digits candidate · summary `72° clear · rain 3pm`.

| # | idea | note | grade |
|---|---|---|---|
| 14.1 | Now + today (temp, condition, precip window) | one API fetch on a pacer, cached, staleness said | v1 |
| 14.2 | Hourly bars — temp/precip as coarse blocks, wide-and-short | the §4.5 shape heuristic's poster child | v1 |
| 14.3 | 7-day list | | v1 |
| 14.4 | **NWS severe-weather alerts → notifications** | a PC-side severe-alert path **independent of the WEA/CMAS probe** — redundancy for the §4.5 emergency promise while the Android question stays open; never the ONLY path, per the §4.5 rule | v1.5 |
| 14.5 | Radar frame (fetched image, box-sampled) | 16 gray levels may read as mush; try once, keep only if legible | v2 |
| 14.6 | Sunrise/sunset + moon phase rows | drawn glyphs, cheap | v2 |

**Refinery question:** provider (NWS api.weather.gov is keyless and includes the alert feed)?

---

## 15. Smaller candidates, one table

| window | what | needs | note | grade |
|---|---|---|---|---|
| **NOTES** | list + read + append-via-typed-text over ~/notes-class files | HOST | or a Files preview convention instead of a window | v2 |
| **NAVIGATION** | big heading tape (compass mode 10 + sid 0x08 — V-graded, never exercised by us), bearing-to-saved-point via phone GPS | PHONE_APIS | the status-bar tape placeholder becomes real first; a window only if he wants one | PROBE (feed) then v2 |
| **MONITORING** | slappy/beardos service dashboards, qBittorrent state, disk fill | HOST | Info 9.11 grown up; his infra, his call | v2 |
| **CLIPBOARD** | PC clipboard → glass as a Damage notification / small window | HOST | one xclip read; weirdly useful for codes/addresses | v2 |
| **PHOTOS** | slideshow-first viewer over a photos root | HOST | Files 5.14 as its own identity | future |
| **HABITS/STATS** | daily checkmarks, streaks | none | §16.4 state questions apply | future |

---

## 16. Cross-cutting — contract work the ideas above share

These are the load-bearing additions multiple windows need. **They are the real v1 engineering**
hiding inside the explosion; each is small, none exists yet.

| # | addition | consumers | note |
|---|---|---|---|
| 16.1 | **`open(target)` deep link** — a window opens AT an item (notification tap → Mail at the message, Notices onward-tap, Search hits) | Mail, SMS, Notices, Search, Calendar | §4.5's gesture table already promises "tap = open it in its source app"; the contract just has no verb for it yet. One nullable method + a shell routing rule |
| 16.2 | **Window hand-off** — window A asks the shell to focus window B at a target | Files→Reader, Search→anything, Mail→Calendar | same verb as 16.1, invoked from inside |
| 16.3 | **Quick-action lists as a shared idiom** — config-driven per-window rows (keys, replies, prompts) | Tmux (built), Mail, SMS | pattern extraction, not new machinery |
| 16.4 | ✅ **Cross-driver state — ANSWERED by Adam 2026-08-31 (`HANDOFF.md` §19): automatic last-write-wins sync** over the content port, per key (`shell.settings` + `window.<id>`), as soon as PC and phone can talk. Built as `Persistence` v2 stamps + `core/sync/SyncNet` | Timers, Notices, Feed, Games, Habits — and Reader/settings today | the per-book (sub-window key) refinement stays open for Reader-class blobs; Maildir's put-state-where-the-data-lives (1.8) remains the better answer where a host-owned store exists |
| 16.5 | **New notification sources stay inside the §4.5 filter's logic** — each addition (Calendar 4.4, Deliveries 1.14, NWS 14.4, tmux waiting-alerts as built) is our own generated event with its own Settings toggle; general phone-notification forwarding stays out | Calendar, Mail, Weather, Tmux | the filter is load-bearing (it is what buys boxes their focus); grow it source by source, never wholesale |
| 16.6 | **Faces for the new windows** (open item #10 paid): Mail/SMS/Notices/Feed-list = Fira Sans · Feed-read = Alegreya · Games-text = JetBrains Mono · Calendar/Timers/Info digits = **B612 revisit** (the §Type note anticipated exactly this) · everything else Clear Sans until earned | all | every choice previews live in Settings since the typography build |
| 16.7 | **Icons** — every new window needs a drawn `IconKind` (§2.4 rule 9: thick strokes, closed forms); lands inside the queued icon-quality pass | all | |
| 16.8 | **Emoji/foreign-glyph policy** — the tofu-box idiom (visible, never silent) as the shell-wide rule; a small drawn set later | SMS, Mail, Feed, Tmux (has it) | NO TRUNCATION's glyph-level corollary |
| 16.9 | **Engine adoptions apps are waiting on** (not app work, listed for honesty): texture-cache adoption (items 19–20 first) pays list-heavy windows most; §5 rule 5 (speculative pre-compression) pays the switcher and games; rule 10 (cross-window deltas) pays every window switch | — | each is priced in `IMPLEMENTATION.md`; none blocks a v1 above |

---

## 17. The probe ledger — cheap experiments this document depends on

In rough order of value per effort. None requires new firmware; all are additive.

| probe | unblocks | effort |
|---|---|---|
| ~~Ring relay watch~~ | ✅ ANSWERED negative (glasses never send `RingRawData`); ring biometrics need the ring's own link + protocol RE — §13.1, `CLAIMS.md` | done |
| **WEA/CMAS visibility on the Pixel 10a** | the §4.5 emergency promise (14.4 is the hedge either way) | an afternoon with the phone |
| **Logger service, sid 0x0F** | live on-glass log stream — turns silent decompress trouble visible; `CAPABILITIES.md` calls it the highest-value untested lead | small transport addition |
| **Compass feed** (mode 10 + sid 0x08) | the status-bar tape placeholder; Navigation | small; V-graded wire, never run by us |
| **IMU enable** (EvenHub Cmd 19/20) | any opt-in head feature, someday | small; stays default-off per §7.1 |
| **File export** (sid 198/199, NOT_SUPPORT-safe) | the "no firmware read-back" claim | small; read-only probe |

*(The wake-word probe (sid 0x07) left this ledger with §8's exclusion — no consumer remains.
It stays graded 🟡S in `CAPABILITIES.md` for whenever Scout's future self returns.)*

---

## 18. Tally and what happens next

**~155 graded ideas across 13 windows, plus 6 smaller candidates, 9 cross-cutting contract
items, and 6 probes — ~175 items in all** (counted, not estimated: 54 v1 · 34 v1.5 · 40 v2 ·
20 future · 2 never · 4 probe-gated). One whole section (§8: Scout/Aria/CC) was excluded by
Adam mid-explosion and recorded rather than deleted. Per the methodology,
**next is the refinery** — Adam's pass: cut, reorder, answer the per-window questions (each
section ends with its 2–4), and above all settle **§16.4 (cross-driver state)** and **§16.1
(deep links)**, which are the two pieces of real contract work nearly everything touches. After
the refinery: consistency passes against `DESIGN.md`/`CAPABILITIES.md`, then a real
implementation plan per surviving window, then — slowly, carefully — code.
