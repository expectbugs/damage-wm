# The app-layer scope explosion (2026-08-31)

**This is the DELIBERATE feature-creep phase** of the locked methodology — *"a couple hundred
ridiculous feature-creep scope explosions → heavy refinery to bring it back to reality."* Every
idea below is deliberately included rather than pre-filtered; the refinery pass (Adam's) is what
cuts it back. Grades are the builder's first-pass opinion **for the refinery to override**.
⚠ **Role update 2026-09-01: the explosion and refinery are CLOSED** — §5 (Files), §16 (the
contract), §19 (Torrents) and §20 (the verdicts + the rejected-ideas guard) are RECORDS of
settled/built work; the remaining per-window tables are still proposals awaiting their own
refinery pass.

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

*Grown 2026-09-01 with the Files build:* `open(target)` (§16.1 deep links) ·
`saveSubState()/restoreSubState()` (§16.4 per-item sub-records) — and `ShellServices` grew
`openMenu` (the floating context menu), `openWindow(id, target)` (hand-off; the shell records the caller for back-to-caller), `notifyInternal(appId, thread, target)` and `icons()` (theme-icon lookup).

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
bank too — excluded outright, see §8; the calendar/timers/search/deliveries entries are
historical since the 2026-09-01 axe.)*

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
| 1.14 | ~~**Deliveries view**~~ | 🪓 AXED by Adam 2026-09-01 ("never used") — G2CC `deliveries.ts` stays historical reference only | axed |
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

> 🪓 **AXED by Adam, 2026-09-01** (*"axe the stuff I never used and don't care about"*).
> Recorded, not deleted — do not build, do not re-propose. Ripples: reminder source 4.4 dies
> (§16.5 list updated); Mail 1.17's .ics hand-off and Timers 6.11 (itself axed) are moot.

*(The graded idea table was pruned with the axe — git holds it. G2CC lineage: `calendar.ts`.)*

## 5. FILES

> ✅ **BUILT 2026-09-01 (the first conversion — `HANDOFF.md` §22, `IMPLEMENTATION.md`).** The
> shipped grammar SUPERSEDES rows 5.1/5.2 below: **tap = context menu with Open first**
> (uniform for every entry, Adam's settled design) — not tap-descends. Shipped in v1 beyond
> the graded plan: PDF dual-mode (5.10, was v2), rename via typed text (5.9), EPUB→Reader
> hand-off (5.8), trash with explicit Restore + on-glass double-confirm purge, the clipboard
> Copy/Cut→Paste (the Move/Copy refinery question answered), per-volume capacity bars, theme
> icons per file type. NOT shipped: 5.7 row thumbnails (the LENS shows a real thumbnail;
> per-row = v1.5), configurable roots/trash-retention settings (`appSettings()` is empty —
> hidden/sort live in the This-folder menu; recorded deviation).

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

> 🪓 **AXED by Adam, 2026-09-01** (*"never used, don't care"*). Recorded, not deleted. Ripples:
> **§16.13 (the scheduled-work substrate) dies with it** — alarms/snooze/scheduled-send were its
> only other consumers and all were future-graded; the Timers 6.3 notification source dies too.

*(Table pruned with the axe — git holds it; §16.13's scheduled-work substrate sketch died with this window. G2CC lineage: `timers.ts`.)*

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
is present) · face Clear Sans — the digit-heaviest surface in the shell, drawn digits where large (B612 ruled out as a default, §16.6)
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

> 📌 **Steered by Adam 2026-09-01:** *"I'd be more interested in drive space and process usage
> and similar type of stats … Useful info."* ⇒ **9.11 (beardos vitals) promotes from v2 toward
> the first cut** — drive fill per mount (turtle's squeeze at a glance, du/df block bars), load,
> top processes, temperatures — and novelty stats (a "wrapped" view, books-finished counters)
> are REJECTED. §15's MONITORING row largely folds in here.

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
| 10.13 | ~~Game Boy emulation~~ **emulated titles, curated by PACE** | 🔴 REFRAMED 2026-09-01 (Adam: "Game Boy is not a game, it is a system") — "never" belongs to sustained refresh cadence, not silicon; specific titles work when built the FF1 way. See the 2026-09-01 block below | per-title (screener-gated) |
| 10.14 | Turn notifications ("Stockfish moved") when parked | | v1.5 |

**Refinery questions:** which game first (chess is the strongest fit; 2048 the cheapest build)?
Is the FF1 bridge reachable as-is from the content host (G2CC's `games/ff1/` read-only), or does
it wait?

### 10b. The 2026-09-01 additions (Adam's live refinery — revisit AFTER the roster above is built and polished)

**Roster adds, first revisit batch:** card games (solitaire/freecell class — Blackjack is the
precedent) · **Minesweeper** (per-cell deltas, near-native) · **a Chip's Challenge clone**
(turn-stepped tile grid = mode-9 country; clone by necessity — Tile World-class rules are well
documented) · *"other games of that nature."*

**The emulation lane (10.13 reframed — curate by title, not system):**
- Integer fits: GB/GBC 160×144 at **2× = 320×288** inside 608×416; GBA 240×160 at **2× =
  480×320**; NES/SNES/Genesis at 1× (the FF1 shape). **Original GB is natively 4-shade
  grayscale** — a DMG game maps onto the 16 levels with zero loss.
- 🔑 **The ROM pace-screener comes FIRST**: run a title headless in the emulator, feed frames
  through the compositor model, price them on the measured curve → a per-title playability score
  (median frame bytes, effective fps, translation-rule hit rate) before anything is built. The
  10.9 "measurement to run, not assume", made reusable.
- **The input gate is the second gate:** the ring gives scroll + tap (double-tap stays shell
  back, non-negotiable), so a d-pad lives on the phone strip / any replica page — the G2CC
  `ff1-controller` shape modernized. Menu-heavy titles barely notice; free movement wants the
  phone in hand.
- Title shortlist fitting the profile: **Azure Dreams (GBC)** (Adam's pick — the Tower is a
  turn-grid roguelike, near-ideal; the Town is exactly what the screener judges) · Pokémon TCG ·
  Dragon Warrior/Quest Monsters · Pokémon RBY/GSC (the overworld scroll is the perfect
  translation-rule test) · Mario's Picross · Fire Emblem / Advance Wars (GBA 2×) · Golden Sun ·
  FF Legend/SaGa · the Mystery Dungeon class · Tetris with a low-speed asterisk.
- Save states ride the §16.4 state substrate (resume anywhere, replica play included); optional
  game audio on the PC/phone speakers (the glasses stay silent — the piezo rule is untouched).
  Personal-only lane like FF1: ROMs never ship.

**The Balatro real-game interface (Adam's concept, 2026-09-01 — "way, way down the line if
ever", recorded as FEASIBLE):** the real game runs on the PC; DamageWM is a custom interface —
a card-image DB pre-converted to 4bpp from the game's own atlas, game state read from the running
game, selections forwarded back, results repopulated. Feasibility upgrade over his screencap
sketch: Balatro is LÖVE/Lua with the Steamodded/lovely modding ecosystem, so **a small mod can
export authoritative state over a socket and accept plays** — clean data instead of vision;
screencaps stay the fallback for anything unmodded. 🔑 **The pattern generalizes as the
"real-game seam" lane**: any turn-based PC game with a reachable state seam can get a native
DamageWM face. Assets stay personal-only, like FF1.

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
| 11.12 | **Comic-strip sources — xkcd, 8-Bit Theater, "and the like"** (Adam, 2026-09-01: "an interesting add") | xkcd is natively monochrome line art — a 16-gray native fit; 8-Bit Theater is a completed ~1,225-page archive, so this is BINGE mode: sequential reading with position memory riding the §16.4 substrate, not a daily-strip feed | v1.5 |

**Refinery questions:** which sources day one? Fetch cadence vs staleness honesty (§10.5 says
say it, not hide it)?

---

## 12. SEARCH

> 🪓 **AXED by Adam, 2026-09-01** (*"never used, don't care"*). Recorded, not deleted. The §16.1
> deep-link target grammar stays Search-proof by design, so a future revival costs nothing now.

*(Table pruned with the axe — git holds it. G2CC lineage: `search.ts`.)*

## 13. HEALTH

> 🪓 **DEAD ENTIRELY, 2026-09-01.** The ring path was already closed (below), and the alternate
> source is gone too: Adam — *"I don't do health tracking or use Aria anymore"* (a Fitbit-via-aria
> revival was pitched and rejected; note **aria is retired**, whatever the global config still
> says about its services). Do not re-propose from either direction.

*(Table pruned with the axe — git holds it; the ring-biometrics evidence lives in `CLAIMS.md`/`CAPABILITIES.md`.)*

## 14. WEATHER

> 🪓 **AXED by Adam, 2026-09-01**: *"I prefer the weather app on my phone. I check it when I wake
> up and it doesn't really change often enough within the same day."* Recorded, not deleted.
> ⚠ Ripple: **14.4's NWS severe-alert hedge dies with the window** — the `DESIGN.md` §4.5
> emergency promise now rides on the WEA/CMAS probe alone (§17), with the phone's own alarm
> remaining the never-the-only-path backstop, as that rule always required.

*(Table pruned with the axe — git holds it. The §4.5 emergency promise rides the WEA/CMAS probe alone — §17.)*

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
hiding inside the explosion.

🔴 **SETTLED WITH ADAM 2026-09-01** (the pre-refinery general-topics session); **BUILT the
same night with the Files conversion** — 16.1/16.2 (deep links + hand-off with back-to-caller),
16.4a–d (sub-records + reported-guarded tombstones, merge-load + reconciliation, both
continuity gates in the battery), 16.5 (the signature), 16.11 (Draw.fit + MenuSurface +
open-on-PC; no extracted confirm helper yet) are CODE; 16.10 shipped the request/blob channel
with Files as its consumer (**push frames, summaries-over-channel, multi-backend arbitration
and per-backend `needs` remain open** — Music is their first real customer); 16.7 was
re-scoped by Adam's theme-icons ruling (drawn set = fallback + release path). `WINDOWS.md`
is the build-facing distillation.

| # | addition | consumers | status / design |
|---|---|---|---|
| 16.1 | ✅ **`open(target)` deep link — DESIGN AGREED**: `DamageWindow.open(target: String): Boolean`; the target is an OPAQUE per-window string (Mail: message id · SMS: thread id · Calendar: event id) parsed by the window, so the shell stays dumb and Search later stores `(windowId, target)` pairs for free. `false` (unsupported/unresolvable — item deleted since) → the shell says so loudly and lands at the window root. Notification tap = commit + activate + open, **never on preview** (§4.3 rule 1). A deep-linked window synthesizes its internal level path (inbox → message) so back behaves as if navigated by hand | Mail, SMS, Notices, Torrents (T.2) — *(Search/Calendar axed 2026-09-01; the opaque-target grammar keeps a Search revival free)* | agreed 2026-09-01 |
| 16.2 | ✅ **Window hand-off — the same verb from inside**: `ShellServices.openWindow(id, target)`, and it PUSHES the back stack so double-tap returns to the caller (§1.4) | Files→Reader, Search→anything, Mail→Calendar | agreed |
| 16.3 | ✅ **Quick-action lists**: pattern extraction from Tmux KEYS. ⚠ Placement decided: per-window USER config (quick-replies, prompts) lives in the **synced store** — SMS quick-replies must work app-alone — while PC `config.json` stays host-provider tuning only (tmux hosts, fetch credentials) | Tmux (built), Mail, SMS | agreed |
| 16.4 | 🔴 **Cross-driver state — RAISED to Adam's TOP PRIORITY 2026-09-01**: *"an always-active session that can be continued seamlessly from every device connected to DamageWM … 100%. Any proposal must take this into account."* Two layers: the **replica** is same-session-from-anywhere (built — phone screen, browser page, desktop preview, all with input); **LWW sync** (§19, built) converges separate shells. Promoted to MUST-DO before the first conversion: **(a) per-item sub-records** `window.<id>.<item>` — whole-blob LWW clobbers cross-item edits (Reader per-book offsets, Games per-game saves); **(b) close the §19.4 startup micro-race** (the recorded debt; fix shape = post-start reconciliation pass); **(c) a per-window CONTINUITY TEST in the battery** — save on shell A → sync → restore on shell B → identical position/frame (§9.1's regression-gate philosophy applied to sync); **(d) content continuability declared per window** (Reader's copy-on-open generalized — state without bytes is useless). Honest boundary, stated to Adam: simultaneous edits to the SAME item still resolve LWW-newest; the sub-record split makes that rare and its cost a position nudge, never data loss. Put-state-where-the-data-lives (1.8) still beats replication wherever a host-owned store exists (Maildir flags, host-side feed read-marks) | everything | agreed; the foundation everything sits on |
| 16.5 | ✅ **New notification sources stay inside the §4.5 filter's logic** — each addition is our own generated event with its own toggle in its app's Settings category; general phone-notification forwarding stays out. Live source list after the 2026-09-01 cuts: Mail, SMS, **incoming-call caller ID** (approved 2026-09-01 — "not an app, an extension of the notifications"; missed calls land in Notices), Torrents T.2, tmux waiting-alerts (built), Damage events. *(Calendar 4.4, NWS 14.4, Timers 6.3 died with their windows.)* **And the signature grows ONCE**: (source, coalesce/thread key, body, deep-link target, urgency) — coalescing-by-thread and tap-to-open are both already promised by §4.5, so one change now beats four retrofits | Mail, SMS, Torrents, Tmux, phone-calls | agreed |
| 16.6 | 🔴 **Faces — REWRITTEN 2026-09-01. B612 is NEVER a default for anything** (Adam, final, after repeated re-proposals: *"let it go"*) — option-only if the library carries it; the old "B612 revisit" advice here is retracted, and digit-heavy surfaces use the system face or DRAWN digits (`Icons.sevenSegClock` is the precedent, Timers 6.4 its consumer). Defaults stand: Mail/SMS/Notices/Feed-list = Fira Sans · Feed-read = Alegreya · Games-text = JetBrains Mono · everything else Clear Sans until earned. The real work item is the **curated font-library expansion** (`DESIGN.md` §Type carries the plan: sturdy-at-1× survivors of the 66 surveyed, OFL/Apache-clean for the APK, lint coverage row + x-height normalisation per face, defaults untouched) | all | ruling recorded |
| 16.7 | ✅ **Icons** — RE-SCOPED by the theme-icons ruling (built: the desktop theme resolves at render time, `DESIGN.md` §4.7; the band-height 56 px lens icon LANDED). The quality pass now targets the DRAWN set only (fallback + release path), still front-of-wave | all | re-scoped + partly built |
| 16.8 | ✅ **Emoji/foreign-glyph policy** — the visible tofu box is the shell-wide rule NOW (Tmux has it); the small drawn set stays v2 | SMS, Mail, Feed, Tmux (has it) | agreed |
| 16.9 | **Engine adoptions apps are waiting on** (not app work, listed for honesty): texture-cache adoption (items 19–20 first) pays list-heavy windows most; §5 rule 5 (speculative pre-compression) pays the switcher and games; rule 10 (cross-window deltas) pays every window switch | — | standing; none blocks a v1 |
| 16.10 | 🆕 **ONE generic window channel with MULTI-BACKEND providers** — the tmux pattern generalized instead of cloned N times: a `{"t":"win","id":…}` upgrade on the content port (pushed frames + summaries/badges + id-correlated requests), `LocalXProvider` on the PC / `RemoteXProvider` on the phone, keeper-style reconnect, staleness said with duration (§10.5). **Adaptive backends** (Adam's spec, Music the archetype): a window declares a SET of backends in preference order; `needs` becomes per-backend (the window is available if ANY backend's needs are met); the channel's staleness clock drives a settable sustained-loss threshold (a liveness decision of the seam-heartbeat class, not a timeout); **auto-switch fires only under a window-defined condition** — PC library → phone Spotify *only if actively playing*; **switchback is DELIBERATE, never automatic** (no mid-song flapping); Main's summary names the live backend (`▶ Spotify · phone`). Music therefore builds MediaSession-remote (3.4) FIRST — it is the fallback backend. Summaries ride the channel so `summary()` stays cheap (§4.6). PC-only configurations bind the Local providers directly, as tmux does today | every HOST window; Music now, Games engines later | agreed |
| 16.11 | 🆕 **Shared kit** — a fit helper that ALWAYS draws `▸` when it clips (closed the launch-night tmux bare-clip debt and Reader's silent `drawFit` clips: unadvertised cuts become impossible by construction) · a confirm-level helper (every destructive or outbound act stages a confirm; placement per §1.7) · the one-shot notice-riding-the-title failure surface · ONE **"open on PC"** verb (content-host `xdg-open`; Calendar 4.11, Feed 11.9, Files) | all | agreed |
| 16.12 | 🆕 **The Title contract + honest NO-TRUNCATION wording** (Adam, 2026-09-01): the absolute rule governs CONTENT; rows/titles are HANDLES elided only with an advertised, reachable path; the ellipsis ban is style, not principle. **Titles are SHORT BY DESIGN — never long enough to cut**; variable content goes to the body or a notification (which is why notifications are popups here, unlike G2CC). A `▸` in the Title = window-defect tripwire. `DESIGN.md` §2.4 r3 + §4.1 carry the full wording | all | recorded |
| 16.13 | ~~**The scheduled-work substrate**~~ | 🪓 died with Timers (2026-09-01) — alarms/snooze/scheduled-send were its only other consumers, all future-graded. The agreed sketch (host-shell ticks, LWW `fired` stamp, duplicate-notification-never-missed-fire) stays recorded here for any revival | axed |

**Agreed build order for the shared work:** the state substrate (16.4 a–d) → the window channel
(16.10) → deep links + the notification signature together (16.1/16.5) → the kit (16.11)
alongside the first converted window · the icon pass (16.7) before any new icon is drawn · the
font expansion (16.6) as an independent backlog item.

---

## 17. The probe ledger — cheap experiments this document depends on

In rough order of value per effort. None requires new firmware; all are additive.

| probe | unblocks | effort |
|---|---|---|
| ~~Ring relay watch~~ | ✅ ANSWERED negative (glasses never send `RingRawData`); ring biometrics need the ring's own link + protocol RE — §13.1, `CLAIMS.md` | done |
| **WEA/CMAS visibility on the Pixel 10a** | the §4.5 emergency promise rides this probe ALONE (14.4 died with Weather) | an afternoon with the phone |
| **Logger service, sid 0x0F** | live on-glass log stream — turns silent decompress trouble visible; `CAPABILITIES.md` calls it the highest-value untested lead | small transport addition |
| **Compass feed** (mode 10 + sid 0x08) | the status-bar tape placeholder; Navigation | small; V-graded wire, never run by us |
| **IMU enable** (EvenHub Cmd 19/20) | any opt-in head feature, someday | small; stays default-off per §7.1 |
| **File export** (sid 198/199, NOT_SUPPORT-safe) | the "no firmware read-back" claim | small; read-only probe |

*(The wake-word probe (sid 0x07) left this ledger with §8's exclusion — no consumer remains.
It stays graded 🟡S in `CAPABILITIES.md` for whenever Scout's future self returns.)*

---

## 18. Tally — superseded by §20

~175 graded items at the explosion's close (54 v1 · 34 v1.5 · 40 v2 · 20 future · 2 never ·
4 probe-gated). Everything this section once scheduled has happened: the refinery is §20, the
§16 contract is settled AND built, and the first conversion (Files, §5) shipped through
`WINDOWS.md`'s checklist.

---

## 19. TORRENTS — added by Adam 2026-09-01 ("Yes! I always intended for a real qbittorrent integration")

> 🔨 **DESIGN SETTLED 2026-09-01 (evening) — `TORRENTS.md` is the record; BUILD IN PROGRESS.**
> Adam's verdicts supersede the grades below — and his rule for this and every later window
> is **no v1/v1.5 staging: complete and polished before the next app.** Ships whole: T.1–T.6
> and T.8, plus a **seeding-under-a-week list** (TL's hit-and-run window), account Stats, and
> **search through the new on-glass keyboard** (`DESIGN.md` §4.8). 🪓 Cut by him: T.7 (shelf
> glue — everything stays in `~/Downloads`), T.9 (categories), T.10 (RSS — *"i never automate
> torrenting"*), T.11 (a second tracker — TorrentLeech only this iteration), T.12 and any
> magnet/URL typing. Refinery questions answered: TorrentLeech; browse AND search;
> delete-with-data from glass behind a double confirm. Notification toggles live in
> Settings → Torrents — the general rule from now on (`WINDOWS.md` §1).

**His spec, verbatim intent:** *"the ability to log into and browse my private torrent site and
add torrents to qbittorrent all within G2. Especially useful for things like linux distros I
want to try and other large downloads better done via torrent."* qBittorrent already runs on
beardos; the tracker session and credentials live host-side in the gitignored config (the
standing secrets rule).

**Declares:** List (transfers / tracker browse / search results) → Document (torrent detail) →
actions · needs **HOST** · face Fira Sans (dense lists) · summary `2 active · 1.2 MB/s · 1 done`
· state: view, cursors (per-device UI; nothing here wants sync beyond settings) · settings:
notify rules, default category, tracker account rows.

| # | idea | note | grade |
|---|---|---|---|
| T.1 | Transfers list: name, progress **block bar**, speed, ETA, state | qBittorrent Web API host-side on a pacer; block bars per §4.5b | v1 |
| T.2 | Done-notification (§16.5 source) with a deep link to the transfer | the notification everyone actually wants from a torrent client | v1 |
| T.3 | **Private tracker browse** — login session host-side, category listings as rows | per-site HTML adapter; fragility said honestly (a markup change breaks parsing loudly, never silently) | v1 |
| T.4 | Tracker search via typed text (replicas) | `onTypedText`; results = the same row shape | v1 |
| T.5 | Torrent detail Document (description, size, seeders) + **Add** action | Add is an outbound act ⇒ confirm level (§16.11), category picked at confirm | v1 |
| T.6 | Pause / resume / delete (delete-with-data = double confirm) per transfer | destructive placement per §1.7 | v1.5 |
| T.7 | **Shelf glue**: a finished book-category torrent lands in `~/books` → Reader shelf notification | the acquisition pipeline — the hoard grows while you watch | v1.5 |
| T.8 | Torrents → Files deep link at the payload path | §16.1/16.2 consumer | v1.5 |
| T.9 | Categories/labels on add; per-category default paths | | v1.5 |
| T.10 | RSS/auto-watch on the tracker | | v2 |
| T.11 | Multiple trackers (adapter per site) | | v2 |
| T.12 | Files → Torrents hand-off (.torrent file → add) | | v2 |

**Refinery questions:** which tracker(s) day one (decides the adapter)? Is browse-by-category or
search-first the daily entry? Delete-with-data reachable from glass at all, or PC-only?

---

## 20. The 2026-09-01 live refinery — cuts, adds, and the standing wow order

Adam's pass over the G2CC roster and two idea-explosion rounds, in session. This section
supersedes §18's counts.

**🪓 Axed (recorded in place, never re-propose):** Deliveries (1.14) · Calendar (§4) · Timers
(§6, taking §16.13 with it) · Search (§12) · **Weather** (§14 — *"I prefer the weather app on my
phone … it doesn't change often enough within the same day"*) · **Health** (§13, now dead from
both directions — ring closed AND *"I don't do health tracking or use Aria anymore"*; aria is
retired). Scout/Aria/CC were already out (§8).

**✅ Added/steered:** the **TORRENTS window** (§19) · Feed comic sources (11.12: xkcd, 8-Bit
Theater binge) · **incoming-call caller ID as a §16.5 notification source** ("not an app, an
extension of the notifications") · Info steered to useful system stats (§9 note: drive fill,
processes, temps; novelty stats rejected) · the Games 10b block (roster adds, the emulation lane
+ ROM pace-screener, the Balatro real-game seam).

**🚫 Pitched and rejected (do not re-pitch):** TOTP codes on glass · Fitbit-fed health ·
audiobook handoff (*"I'm a reader not a listener"*) · serial/webnovel subscriptions · reading
stats · find-my-phone (his logic: within BLE range it isn't lost; out of range the glasses
aren't working) · star map (*"a ton of work and likely janky"*) · and the earlier lane: watchers,
tickers, a standalone comics/CBZ reader, reader-mode browse window, teleprompter, presentation
remote, PC live captions, chat bridges, a demos/eyecandy toy window.

**The standing wow order (Adam-approved 2026-09-01)** — how cool each survivor can be within
DamageWM's system, which is the intended *build excitement* order (risk still prices the actual
sequencing per window):

1. **Games** (roster first; 10b lanes on revisit)
2. **Torrents** (§19)
3. **Files** — ✅ **BUILT (2026-09-01, the same night — the §5 banner, `HANDOFF.md` §22)**:
   the settled design shipped whole (locations + capacity bars, the context-menu grammar,
   text/image/PDF viewers, clipboard, trash+restore, typed rename, Open-on-PC, EPUB→Reader
   hand-off, theme icons) and survived an eight-round review loop run to convergence
4. **Music** (adaptive PC-library ↔ Spotify per §16.10)
5. **Feed** + comics
6. **Mail**
7. **SMS** (+ caller-ID source)
8. **Info** (useful-stats steer)
9. **Notices**

Shipped and polishing outside the list: Reader, Tmux.
