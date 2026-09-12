# The app-layer scope explosion (2026-08-31)

**The DELIBERATE feature-creep phase** of the locked methodology — *"a couple hundred ridiculous
feature-creep scope explosions → heavy refinery to bring it back to reality."* Grades are the
builder's first-pass opinion for the refinery to override. **The explosion and refinery are CLOSED
(2026-09-01/02):** §5 (Files), §16 (the contract), §19 (Torrents), §3 (Music — `MUSIC.md`), §10
(Games — `HOLDEM.md`) and §11 (Feed — `FEED.md`) are RECORDS of built work; §20 holds the verdicts
and the rejected-ideas guard; the remaining per-window tables await their own refinery pass.

Inputs: `CAPABILITIES.md`, `DESIGN.md` §0 and §4.6, `core/…/shell/WindowContract.kt`, the measured
latency curve (`overview.md` §5.2), `/home/user/G2CC/server/src/windows/` (facts and lessons, no
code). This file proposes; `overview.md` wins on facts, `CLAUDE.md` on rules, `DESIGN.md` on shell
design.

---

## 0. Rules of engagement

### 0.1 Already excluded — do not re-grade these

`DESIGN.md` §0 binds this whole document: no piezo, no per-window gesture grammar, no off-panel
scratch, no fades/dissolves, no dithering, no wear/unwear differentiation, no head tracking as a
*required* input, no quit path, no split view (deferred), no content in the 16 px gutters. Where
an idea brushes one of these, its row names the conflict.

### 0.2 What every window declares (the §4.6 contract, as built)

`DamageWindow` is `WindowContract.kt` (`DESIGN.md` §4.6): `view()` (List / Doc / Canvas — List
and Document are WM-driven and nearly free; Canvas owns its damage) · `title()` · `summary()`
(cheap, side-effect-free) · `icon` · `dirty` · `needs` (HOST / PHONE_APIS / BLE) ·
`preferredHeight` · `appSettings()` · `saveState()/restoreState()` · `styleTransform` ·
`onTypedText(line)` (always staged behind the window's own confirm) · `back()` / `levelDepth()`;
grown with Files: `open(target)` (§16.1), `saveSubState()/restoreSubState()` (§16.4), and
`ShellServices.openMenu` / `openWindow(id, target)` / `notifyInternal` / `icons()`; with Torrents
`openKeyboard` (§4.8).

### 0.3 The price list every idea is graded against

**Measured** (`overview.md` §5.2, n=1,488 flushes, PC-direct): `ms ≈ 60 + bytes/50` — superseded
for the daily path by the phone's ~70 ms + ~120 ms/KB (`REMINDER.md`, 2026-09-05). **Byte counts
below are modeled** (`DESIGN.md` §4.6/§8.4); say which is which when quoting.

| interaction | bytes (modeled) | latency on the measured curve |
|---|---|---|
| list scroll, one notch (shift + 2 fills) | 860–1,430 B | ~77–90 ms |
| document scroll step | 292–486 B | ~66–70 ms |
| notification appear/dismiss | 210–650 B | ~64–73 ms — effectively free |
| full content repaint 608×416, text | 2.5–5 KB | ~110–160 ms |
| full content repaint, dense | 3.8–6.3 KB | ~136–186 ms |
| dense full-screen keyframe | ~10 KB | ~200–270 ms → **~4 fps sustained** |
| heaviest frame yet observed | 24.6 KB | 540 ms (measured directly) |

Chrome rides content flushes free (§8.3). Depth is +4 B per rect. The texture cache was not in the
compositor when this was graded; it landed shell-wide 2026-09-06 (`Cached text`, `CLAUDE.md`) —
rows saying "gated on items 19–20" predate that.

### 0.4 Grades

| grade | means |
|---|---|
| **v1** | belongs in that window's first cut |
| **v1.5** | first follow-up wave |
| **v2** | a later wave, design kept open for it |
| **future** | someday; parked deliberately |
| **never** | conflicts with a decision — the row names it |
| **PROBE** | blocked on a named probe (§17) before it can even be graded |

Since 2026-09-01 v1 / v1.5 / v2 are HISTORICAL labels — Adam's rule for every window after Files
is no staging (`WINDOWS.md` §1, `HANDOFF.md` §23); read them as the builder's priority opinion for
that window's refinery pass. **never** and **PROBE** still mean what they say.

### 0.5 The G2CC bank

Read-only, facts only: `mail.ts` (Maildir + reply/forward/compose via msmtp) · `sms.ts` (threaded
SMS/MMS, phone as Telephony provider) · `music.ts` + `media.ts` (his player; third-party phone
media via MediaSessionManager + LRCLIB lyrics) · `files.ts` (tree + preview + ops + trash — the
ACTIONS-level pattern) · `notices.ts` (notification-history browser) · `games.ts` (rpg-cli, chess
vs Stockfish, Universal Paperclips, Blackjack, FF1). `scout.ts` / `aria.ts` / `cc.ts` excluded
(§8); `calendar.ts` / `timers.ts` / `search.ts` / `deliveries.ts` historical since the 2026-09-01
axe.

---

## 1. MAIL

**PC-side by design** (`DESIGN.md` §4.5: Maildir + mbsync on beardos). The §13 promise: *"Mail
and MMS with embedded images."* G2CC lineage: `mail.ts`.

**Declares:** List (inbox/threads) → Document (read view) → List (actions) · needs **HOST** · face
**Fira Sans** · height global · summary `3 unread · Jane Doe + 2` · state: folder, cursor, open
message, read-view offset · settings: account(s), notify rules, list density.

| # | idea | note | grade |
|---|---|---|---|
| 1.1 | Inbox list — sender bright, subject dim, unread by brightness, lens = 2-line full summary | the §4.2 lens pattern; a notch ~80 ms | v1 |
| 1.2 | Read view as endless-scroll Document with real typography | 292–486 B per step | v1 |
| 1.3 | HTML mail → flowed text, PC-side sanitised | w3m/lynx-class dump on beardos | v1 |
| 1.4 | **Inline images in mail bodies** | the Reader `ImageDecoder` pipeline; MIME parts feed the same seam | v1 |
| 1.5 | Unread badge + dirty tick + top-divider mark | | v1 |
| 1.6 | Actions level: mark read/unread, archive, delete-with-confirm | irreversible rows never at cursor rest (§1.7) | v1 |
| 1.7 | Notification tap opens the message (deep link) | §16.1 | v1 |
| 1.8 | Read state lives in the Maildir itself (S flag) | one read state across drivers by ownership — the §16.4 problem solved where the data lives | v1 |
| 1.9 | Threading (References/In-Reply-To collapse) | rows = threads, count badge | v1.5 |
| 1.10 | Quick-replies — canned lines list, confirm-to-send via msmtp | the Tmux KEYS pattern | v1.5 |
| 1.11 | Reply/compose via typed text from the replicas | `onTypedText`; always confirm-to-send | v1.5 |
| 1.12 | Attachment list; image attachments render inline; "save on PC" action | | v1.5 |
| 1.13 | Multi-account (mbsync profiles as folders) | | v1.5 |
| 1.14 | ~~**Deliveries view**~~ | 🪓 AXED by Adam 2026-09-01 ("never used") | axed |
| 1.15 | Per-sender notification rules (VIP-only interrupts) | extends the §4.5 source filter | v2 |
| 1.16 | Mail search (typed) | Search (§12) is axed | v2 |
| 1.17 | Calendar-invite detection → Calendar hand-off | moot — Calendar axed (§4) | v2 |
| 1.18 | Snooze (re-notify at T) | needs §16.13, axed with Timers | future |
| 1.19 | Cached-glyph list rendering | the texture cache | future |
| 1.20 | PGP signature badge | cheap, low value | future |

**Refinery questions (open):** one account or all? Is compose in scope, or is Mail read-and-triage
(the ring cannot type — typed text comes from replicas only)?

---

## 2. SMS

**Phone-side by design** (§4.5: the phone is the Telephony provider). G2CC lineage: `sms.ts`.
Unavailable in bridge/laptop configurations, and says so (§10.5).

**Declares:** List (threads) → Document (thread view) → List (actions) · needs **PHONE_APIS** ·
face **Fira Sans** · summary `Mom · "on my way"` · state: thread, offset · settings: notify
per-thread, quick-replies.

| # | idea | note | grade |
|---|---|---|---|
| 2.1 | Threaded list, newest-first, unread by brightness | | v1 |
| 2.2 | Thread view: speaker as brightness + indent, never boxes | §4.2 — bubbles are fill, fill is waste | v1 |
| 2.3 | **MMS images inline** | the other half of the §13 promise | v1 |
| 2.4 | Reply via typed text, confirm-to-send, phone transmits | `onTypedText` + a phone-side send op | v1 |
| 2.5 | Notification deep link to the thread | §16.1 | v1 |
| 2.6 | Send/receive failures surfaced on glass | | v1 |
| 2.7 | Contact names from the phone's contacts provider | numbers-only fallback stays honest | v1 |
| 2.8 | Emoji/glyph policy: unrenderable codepoints draw the visible tofu box | §16.8 | v1 |
| 2.9 | Quick-replies list, config-driven | | v1.5 |
| 2.10 | Group threads (names list, per-speaker brightness levels) | | v1.5 |
| 2.11 | Compose new (contact picker list → typed body) | | v1.5 |
| 2.12 | Drawn mini-emoji set (~20 common) as icons | §2.4 rule 9; pays 2.8's upgrade | v2 |
| 2.13 | SMS search | | v2 |
| 2.14 | RCS | what the phone exposes to a normal app | PROBE |
| 2.15 | Scheduled send | same substrate as 1.18 | future |

**Refinery questions (open):** which contact fields (names only, or photos-as-thumbnails someday)?
Is compose-new v1 or does reply-only cover the daily loop?

---

## 3. MUSIC

> ✅ **BUILT 2026-09-01/02 — `MUSIC.md` is the record (29 verdicts, the design, as built),
> `HANDOFF.md` §24 the build record.** Adam's verdicts supersede every grade below. The §16.10
> push slice shipped with it; the backend fallback (PC library → Spotify on the phone) lives in
> the player, switchback deliberate.

**Phone plays the audio** (G2CC decision 2026-08-05); the library lives on the PC. G2CC ancestors:
`music.ts` (his player) and `media.ts` (third-party phone media). §4.5 names Music a notification
source.

**Declares (as explored):** List (Now Playing as a one-row lens + actions) → List (library) ·
needs **PHONE_APIS** (+HOST for the library) · face Clear Sans · summary `▶ Bowie — Blackstar` ·
state: view, library cursor · settings: source, notify on track change.

| # | idea | note | grade |
|---|---|---|---|
| 3.1 | Now Playing: title/artist/album, position as a coarse block bar | §4.5b; repaint on track change + coarse ticks | v1 |
| 3.2 | Transport controls at the actions level (play/pause/next/prev) | | v1 |
| 3.3 | Track-change notifications | the §4.5 Music source | v1 |
| 3.4 | Third-party media control (whatever the phone is playing) | MediaSessionManager remote — `media.ts` shape | v1 |
| 3.5 | Album art thumbnail on Now Playing | box-sample to ~120 px | v1.5 |
| 3.6 | Library browse (PC library over the content port, folders like the Reader shelf) | | v1.5 |
| 3.7 | Lyrics view (LRCLIB, G2CC precedent), Document mode | one small flush per line — a deliberate radio spend, settable | v1.5 |
| 3.8 | Queue view | | v2 |
| 3.9 | Coarse seek (±10 s notches at an actions row) | | v2 |
| 3.10 | Playlists | | v2 |
| 3.11 | Volume | **never** as graded (phone-owned at max, a G2CC decision) — **reversed by `MUSIC.md` verdict 13** | never |
| 3.12 | Output target picker (phone / PC speakers) | PC output ruled out (`MUSIC.md` verdict 2); phone outputs are a row | future |
| 3.13 | Song recognition ("what is playing near me") | needs the mic — much later by his word | future |

**Refinery questions:** the first (MediaSession-remote first, or the full player day one) was
answered by the build — the full player, the Spotify remote as the fallback backend. The second —
does lyrics' per-line radio spend feel right on glass — is **still open**, an on-glass item.

---

## 4. CALENDAR

> 🪓 **AXED by Adam, 2026-09-01** (*"axe the stuff I never used and don't care about"*). Recorded,
> not deleted — do not build, do not re-propose. Ripples: reminder source 4.4 goes with it
> (§16.5); Mail 1.17 and Timers 6.11 are moot. Table pruned — git holds it. Lineage: `calendar.ts`.

## 5. FILES

> ✅ **BUILT 2026-09-01 (the first conversion — `HANDOFF.md` §22, `IMPLEMENTATION.md`).** The
> shipped grammar SUPERSEDES rows 5.1/5.2: **tap = context menu with Open first** (uniform for
> every entry, Adam's settled design), not tap-descends. Shipped beyond the graded plan: PDF
> dual-mode (5.10), typed rename (5.9), EPUB→Reader hand-off (5.8), trash with explicit Restore +
> on-glass double-confirm purge, the clipboard Copy/Cut→Paste, per-volume capacity bars, theme
> icons per file type. NOT shipped: 5.7 per-row thumbnails (the LENS shows one); configurable
> roots/trash-retention settings (`appSettings()` is empty — hidden/sort live in the This-folder
> menu; recorded deviation).

G2CC lineage: `files.ts` — its ACTIONS level is the pattern `DESIGN.md` §4.6 generalised to every
window. The §13 promise: *"a file manager with real icons and thumbnails."*

**Declares:** List (browser) → List (actions per entry) / Document (text preview) / Canvas (image
view) · needs **HOST** · face Clear Sans · summary `~/damagewm` · state: cwd per root, cursor, open
preview · settings: roots, hidden files, trash retention.

| # | idea | note | grade |
|---|---|---|---|
| 5.1 | Browser list: dirs first, name + size + mtime; descend/ascend on tap/double-tap | superseded (banner) | v1 |
| 5.2 | ACTIONS level: Open · Copy · Move · Rename · Delete→trash · Stats | irreversible placement per §1.7 | v1 |
| 5.3 | Text preview as Document (real type, endless scroll, NO TRUNCATION) | | v1 |
| 5.4 | **Image viewer** — box-sampled to the content area, 16 levels, no dithering | the Reader image pipeline | v1 |
| 5.5 | Trash with restore | delete is never unrecoverable from glass | v1 |
| 5.6 | Configured roots (~/books, ~/downloads, the vault when mounted) | an unmounted root says so | v1.5 |
| 5.7 | Thumbnails on image rows | not shipped; a follow-up | v1.5 |
| 5.8 | Hand-off: .epub opens in Reader at its shelf entry | §16.2; shipped | v1.5 |
| 5.9 | Rename via typed text | shipped | v1.5 |
| 5.10 | PDF preview (page rasterised PC-side → image path) | shipped | v2 |
| 5.11 | Video poster frame (ffmpeg first-frame) on preview | | v2 |
| 5.12 | Disk-usage view (du → block bars per entry) | | v2 |
| 5.13 | Name search within the tree (typed) | | v2 |
| 5.14 | Slideshow (auto-advance image view) | a setting, off by default | v2 |
| 5.15 | Remote roots over ssh (slappy) | the tmuxHosts pattern | future |
| 5.16 | "Fetch to phone" (file → phone download via its replica page) | parked | future |

**Refinery questions:** answered by the build — locations with capacity bars as roots; Move/Copy
as the clipboard Copy/Cut→Paste.

---

## 6. TIMERS

> 🪓 **AXED by Adam, 2026-09-01** (*"never used, don't care"*). Recorded, not deleted. Ripples:
> **§16.13 (the scheduled-work substrate) goes with it** — alarms/snooze/scheduled-send were its
> only other consumers, all future-graded; the Timers 6.3 notification source goes too. Table
> pruned — git holds it. Lineage: `timers.ts`.

## 7. NOTICES

G2CC lineage: `notices.ts` — browse the persisted notification history, newest-first; reading
marks SEEN. The shell has the queue, grace, coalescing and read-state machinery (§4.5); this
window is its history surface. Silent-mode pops stay **unread** by design and land here.

**Declares:** List (history) → Document (read view) · needs none (history is shell state — §16.4
applies) · face Fira Sans · summary `4 unread` · state: cursor, filter · settings: retention,
per-source view defaults.

| # | idea | note | grade |
|---|---|---|---|
| 7.1 | History list newest-first: source icon, line, time; unread bright | | v1 |
| 7.2 | Read view (full body, Document) | | v1 |
| 7.3 | Reading marks read; badge and divider tick clear | the §4.5 read-state rules | v1 |
| 7.4 | Deep link onward: tap a mail notice here → Mail at that message | §16.1 | v1 |
| 7.5 | Clear-all, confirmed | | v1 |
| 7.6 | Per-source filter level (just SMS, just Damage events…) | | v1.5 |
| 7.7 | Retention setting (days / count) | | v1.5 |
| 7.8 | Re-show action (present this notice as a box again) | | v2 |
| 7.9 | Source statistics (which source interrupts most) | | future |
| 7.10 | Notification rules editor (beyond the Settings on/off rows) | only if 1.15-class rules multiply | future |

**Refinery questions (open):** retention default? Does history live host-side so both shells see
one timeline (§16.4)?

---

## 8. SCOUT / ARIA / CC — excluded from this explosion (Adam, mid-explosion, 2026-08-31)

> *"Ignore Aria, CC, and Scout. Tmux is better and does all of that."* And: *"Scout specifically
> will become something rather different, once everything else is built, tested and polished."*

G2CC carried three assistant surfaces (`scout.ts` — the model-controlled display; `aria.ts` —
intents; `cc.ts` — live CC sessions). Settled before the refinery started:

- **Aria and CC: covered by Tmux, permanently.** The glasses watch and approve real sessions in
  real terminals — typed text with confirm, quick keys, waiting-pattern alerts, scrollback.
- **Scout: parked, not closed.** It returns as *something rather different* only after everything
  else is built, tested and polished — deliberately **not designed now**; nothing anchors it.

Ideas retired with the section stay retired unless Scout's future self re-earns them
(model-controlled display frames, answer-prose typography, assistant-branded proactive pushes —
Tmux's waiting-pattern alerts already do the real version of the last). The wake-word probe leaves
§17 with it. Anything assistant-shaped in the meantime starts life as a tmux session.

**2026-09-12 — Scout's return is designed: the display half only (`POPOVER.md`, `DESIGN.md` §4.11).**
Adam raised it himself. No session window (Tmux keeps that); any Claude Code session pushes a deck
through a skill + CLI and it shows as a popover over the active window, with selectable options
reporting back. Re-earned from the retired list: model-controlled display frames (as decks, durable,
not mid-turn); a proactive push is a NOTICE with a deep link. Still out: live mid-turn frames, image
generation as the architecture. Not built; queue position is Adam's call.

---

## 9. INFO — system state

**Open item #5's home** (orphaned when the info popup became the switcher; "deeper system detail
is a window" — `DESIGN.md` §4.3). It pays the §4.1 debt: *"exact percentages live in the
Info/Stats surface."*

**Declares:** List (sections) → Document (detail per section) · needs none · face Clear Sans,
drawn digits where large (B612 ruled out as a default, §16.6) · summary = the current
configuration (`PC via phone · 60ms`) · state: cursor · settings: none.

| # | idea | note | grade |
|---|---|---|---|
| 9.1 | Battery detail: G + P exact %, charging states, **case SoC** (sid 0x81 `caseInfo.soc` — decoded in our own captures) | no ring: no open-source source (`CLAIMS.md`); the chrome R cell was removed 2026-08-31 | v1 |
| 9.2 | **Which configuration is driving** — the §10 row, named: `PC → phone → glasses`, path, since-when | makes the arbitration legible | v1 |
| 9.3 | Link panel: ack EMA, B/s, RSSI (where readable), seam heartbeat age | all in `LinkState`/status | v1 |
| 9.4 | Session: lease held/renewals, uptime, flushes, bytes today | journal-derived | v1 |
| 9.5 | Versions: APK/jar/`EVENCFW` capability string + tokens | never the version string alone | v1 |
| 9.6 | Mode-7 flag state + sticky history | the free loss-telemetry channel | v1 |
| 9.7 | Error/journal tail view (last N lines) | | v1.5 |
| 9.8 | "Why is it slow" attribution (ack / transfer / compose split) | the data is in the journal | v1.5 |
| 9.9 | Throughput self-test action (timed keyframe burst, result written to the journal) | | v1.5 |
| 9.10 | Tailscale peer states (aphone, slappy) | `tailscale status` on the host | v2 |
| 9.11 | beardos vitals (load, disk, temperatures) | **promoted toward the first cut** (steer below) | v2 |
| 9.12 | Ring firmware/version readout | needs the ring-link query nobody has implemented | PROBE |

**Refinery questions (open):** read-only, or does it grow actions (force keyframe, restart
transport)? Where is the line between Info and the status bar (glance vs check is the §4.1 answer)?

> 📌 **Steered by Adam 2026-09-01:** *"I'd be more interested in drive space and process usage
> and similar type of stats … Useful info."* ⇒ **9.11 promotes from v2 toward the first cut** —
> drive fill per mount (du/df block bars), load, top processes, temperatures — and novelty stats
> (a "wrapped" view, books-finished counters) are REJECTED. §15's MONITORING row folds in here.

---

## 10. GAMES

G2CC lineage: `games.ts` — rpg-cli, chess vs Stockfish, Universal Paperclips, Blackjack, FF1. The
*"$5 turn"* stray-tap lesson (§1.7's cursor-rest discipline) was paid here. **Licensing** per
`CLAUDE.md`'s clean-room section (revised 2026-09-02): the *work* never ships, the *window that
drives it* may — Paperclips and FF1 are no longer personal-only rows; do not reinstate the older
blanket exclusion. **Honesty rule:** dense full-frame is a measured 2–4 fps; anything needing more
says so up front.

**Declares:** List (games hub) → per-game Canvas/List · needs **HOST** (engines run PC-side) ·
face n/a (drawn boards) / JetBrains Mono for text games · height per-game · state: per-game saves
via the blob · settings: per-game rows.

| # | idea | note | grade |
|---|---|---|---|
| 10.1 | Games hub list | ✅ **BUILT 2026-09-04** — the running table, Standings, Bankroll, Settings (`HOLDEM.md` §4) | v1 |
| 10.2 | **Chess vs Stockfish** — drawn board+pieces, scroll = square cursor, tap = select/move, confirm on capture-into-check class moves | turn-based = damage-tiny; thick-stroke pieces per §2.4 rule 9 | v1 |
| 10.3 | Chess: engine "thinking" in the op cell; move arrives as a delta + optional notification when parked | | v1 |
| 10.4 | Board depth: board at content plane, floating move-hints forward | +4 B per rect | v1.5 |
| 10.5 | **2048** — native, drawn tiles | a move is mode-9 shifts + one fill | v1.5 |
| 10.6 | Blackjack (drawn cards; G2CC port of the rules, our rendering) | Hit/Stand never at cursor rest; the Hold'em kit is built for it | v1.5 |
| 10.7 | Minesweeper / solitaire class (drawn, per-cell deltas) | | v2 |
| 10.8 | rpg-cli dungeon (its text UI through List/Document) | | v2 |
| 10.9 | **FF1 via the emulator** — Canvas at native 256×240 centred (integer 1× only; 416-px content rules out 2×) | user-supplied ROM; whether the translation rule catches overworld scrolls is a measurement to run | v2 |
| 10.10 | Universal Paperclips | engine fetched at run time, DOM generated; mostly numbers = cheap list | v2 |
| 10.11 | Wordle-class daily word (typed guesses) | | v2 |
| 10.12 | Roguelike (native, glyph grid, per-cell deltas) | | future |
| 10.13 | ~~Game Boy emulation~~ **emulated titles, curated by PACE** | 🔴 REFRAMED 2026-09-01 (Adam: "Game Boy is not a game, it is a system") — "never" belongs to sustained refresh cadence, not silicon; see 10b | per-title (screener-gated) |
| 10.14 | Turn notifications ("Stockfish moved") when parked | | v1.5 |

**Refinery questions:** which game first — answered: Texas Hold'em, built 2026-09-04 (`HOLDEM.md`;
none of the rows above yet). Open: is the FF1 bridge reachable as-is from the content host (G2CC's
`games/ff1/`, read-only), or does it wait?

### 10b. The 2026-09-01 additions (Adam's live refinery — revisit AFTER the roster above is built and polished)

**Roster adds:** card games (solitaire/freecell class) · **Minesweeper** · **a Chip's Challenge
clone** (turn-stepped tile grid = mode-9 country; Tile World-class rules are well documented) ·
*"other games of that nature."*

**The emulation lane (10.13 reframed — curate by title, not system):** integer fits GB/GBC
160×144 at **2× = 320×288**, GBA 240×160 at **2× = 480×320**, NES/SNES/Genesis at 1×; original GB
is natively 4-shade grayscale. 🔑 **The ROM pace-screener comes FIRST**: run a title headless,
feed frames through the compositor model, price them on the measured curve → a per-title
playability score (median frame bytes, effective fps, translation-rule hit rate). **The input gate
is second:** the ring gives scroll + tap (double-tap stays shell back, non-negotiable), so a d-pad
lives on the phone strip / any replica page (the G2CC `ff1-controller` shape). Shortlist: **Azure
Dreams (GBC)** (Adam's pick — the Tower is a turn-grid roguelike; the Town is what the screener
judges) · Pokémon TCG · Dragon Warrior/Quest Monsters · Pokémon RBY/GSC (the overworld scroll is
the translation-rule test) · Mario's Picross · Fire Emblem / Advance Wars (GBA 2×) · Golden Sun ·
FF Legend/SaGa · the Mystery Dungeon class · Tetris with a low-speed asterisk. Save states ride
the §16.4 substrate; optional game audio on PC/phone speakers (the glasses stay silent); ROMs
never ship.

**The Balatro real-game interface (Adam's concept, 2026-09-01 — "way, way down the line if ever",
recorded as FEASIBLE):** the real game runs on the PC; DamageWM is a custom face — a card-image DB
pre-converted to 4bpp from the game's own atlas, state read from the running game, selections
forwarded back. Balatro is LÖVE/Lua with the Steamodded/lovely modding ecosystem, so **a small mod
can export authoritative state over a socket and accept plays**; screencaps are the fallback for
anything unmodded. 🔑 Generalizes as the **"real-game seam" lane**: any turn-based PC game with a
reachable state seam can get a native DamageWM face. Assets stay personal-only, like FF1.

---

## 11. FEED

> ✅ **BUILT AND ON GLASS 2026-09-09 — `FEED.md` is the record (fifteen verdicts; §8 as built),
> `HANDOFF.md` §43 the build, §43.6 the polish protocol; the measured walk owed.** Adam's verdicts
> supersede the grades below: sources **Reddit r/popular (anonymous), Slashdot, xkcd, 8-Bit
> Theater (binge), SMBC**; root = the **source list**, not a river; comments where reachable; flag
> in; images on; inversion automatic; fit-to-width only; 16 gray levels with a row; notifications
> off; **the phone runs the engine itself when the PC is unreachable**. 🪓 Cut by him: Hacker
> News, 11.9 Open on PC (*"the glasses are intended to be used away from PC exclusively"*), 11.10
> the Reader hand-off, 11.11 YouTube (*"we don't do video"*), a Reddit login, any manga (One Punch
> Man asked and refused on facts), a 1:1 zoom, a headless browser for either site (probed: both
> refuse headless Chromium).

The §13 promise: *"a Reddit-style feed with endless scroll."* No G2CC ancestor.

**Declares (as explored):** List (items) → Document (article + images) · needs **HOST** · face
Fira Sans for the list, **Alegreya for the reading view** · summary `12 new · HN + 3 feeds` ·
state: per-feed cursors, read marks · settings: feeds, fetch cadence, image loading.

| # | idea | note | grade |
|---|---|---|---|
| 11.1 | RSS/Atom aggregation, PC-side fetch on a pacer | | v1 |
| 11.2 | Item list with unread brightness + per-feed folders | | v1 |
| 11.3 | Article view: endless scroll, inline images | the platform's showcase | v1 |
| 11.4 | Read/unread tracking + summary count | | v1 |
| 11.5 | Reddit via its public JSON (old-reddit listing shape) | as built: the `.rss` — the JSON answers 403 | v1.5 |
| 11.6 | Hacker News (Algolia/API) | 🪓 cut by Adam | v1.5 |
| 11.7 | Comment threads, indent by brightness tier | no boxes, no rails — §4.2 | v2 |
| 11.8 | Read-later queue (flag → its own folder) | built (Flagged) | v2 |
| 11.9 | "Open on PC" action per item | 🪓 cut by Adam | v2 |
| 11.10 | Long article → Reader hand-off | 🪓 cut by Adam | future |
| 11.11 | YouTube subscriptions (poster + title; play lands on the PC) | 🪓 cut by Adam | future |
| 11.12 | **Comic-strip sources — xkcd, 8-Bit Theater, "and the like"** (Adam, 2026-09-01: "an interesting add") | xkcd is monochrome line art — a native 16-gray fit; 8-Bit Theater is a completed ~1,225-page archive → BINGE mode with position memory on the §16.4 substrate; built | v1.5 |

---

## 12. SEARCH

> 🪓 **AXED by Adam, 2026-09-01** (*"never used, don't care"*). Recorded, not deleted. The §16.1
> deep-link target grammar stays Search-proof, so a revival costs nothing now. Table pruned — git
> holds it. Lineage: `search.ts`.

## 13. HEALTH

> 🪓 **CLOSED ENTIRELY, 2026-09-01.** The ring path was already closed, and the alternate source is
> gone too: Adam — *"I don't do health tracking or use Aria anymore"* (a Fitbit-via-aria revival
> was pitched and rejected; **aria is retired**, whatever the global config still says). Do not
> re-propose from either direction. Table pruned — git holds it; the ring-biometrics evidence is in
> `CLAIMS.md`/`CAPABILITIES.md`.

## 14. WEATHER

> 🪓 **AXED by Adam, 2026-09-01**: *"I prefer the weather app on my phone. I check it when I wake
> up and it doesn't really change often enough within the same day."* Recorded, not deleted.
> Ripple: **14.4's NWS severe-alert hedge goes with the window** — the `DESIGN.md` §4.5 emergency
> promise rides on the WEA/CMAS probe alone (§17), the phone's own alarm remaining the
> never-the-only-path backstop. Table pruned — git holds it.

## 15. Smaller candidates, one table

| window | what | needs | note | grade |
|---|---|---|---|---|
| **NOTES** | list + read + append-via-typed-text over ~/notes-class files | HOST | or a Files preview convention | v2 |
| **NAVIGATION** | big heading tape (compass mode 10 + sid 0x08 — V-graded, never exercised by us), bearing-to-saved-point via phone GPS | PHONE_APIS | the status-bar tape placeholder becomes real first | PROBE (feed) then v2 |
| **MONITORING** | slappy/beardos service dashboards, disk fill (qBittorrent state is Torrents' now — §19) | HOST | folds into Info 9.11 | v2 |
| **CLIPBOARD** | PC clipboard → glass as a Damage notification / small window | HOST | one xclip read; codes/addresses | v2 |
| **PHOTOS** | slideshow-first viewer over a photos root | HOST | Files 5.14 as its own identity | future |
| **HABITS/STATS** | daily checkmarks, streaks | none | §16.4 state questions apply | future |

---

## 16. Cross-cutting — contract work the ideas above share

🔴 **SETTLED WITH ADAM 2026-09-01; BUILT the same night with Files**: 16.1/16.2, 16.4a–d (both
continuity gates in the battery), 16.5, 16.11 (Draw.fit + MenuSurface + open-on-PC; no extracted
confirm helper yet) are CODE; 16.10 shipped the request/blob channel with Files and Torrents
(version-cursor snapshots + event replay, `TorrentsNet`), Music added **push frames**
(`WinService.Push`, `wpush`) and its backend fallback inside the player; **summaries-over-channel
and a per-backend `needs` contract remain unbuilt** (Music declares `needs` per host); 16.7 was
re-scoped by the theme-icons ruling. `WINDOWS.md` is the build-facing distillation.

| # | addition | consumers | status / design |
|---|---|---|---|
| 16.1 | ✅ **`open(target)` deep link**: `DamageWindow.open(target: String): Boolean`; the target is an OPAQUE per-window string (the shell stays dumb; a Search revival stores `(windowId, target)` pairs for free); `false` (unsupported / item gone) → the shell says so loudly and lands at the window root; notification tap = commit + activate + open, **never on preview** (§4.3 rule 1); a deep-linked window synthesizes its level path so back behaves as if navigated by hand | Mail, SMS, Notices, Torrents (T.2) | agreed 2026-09-01 |
| 16.2 | ✅ **Window hand-off**: `ShellServices.openWindow(id, target)` PUSHES the back stack so double-tap returns to the caller (§1.4) | Files→Reader | agreed |
| 16.3 | ✅ **Quick-action lists** (the Tmux KEYS pattern). Per-window USER config (quick-replies, prompts) lives in the **synced store** — SMS quick-replies must work app-alone; PC `config.json` stays host-provider tuning only | Tmux (built), Mail, SMS | agreed |
| 16.4 | 🔴 **Cross-driver state — Adam's TOP PRIORITY 2026-09-01**: *"an always-active session that can be continued seamlessly from every device connected to DamageWM … 100%. Any proposal must take this into account."* Layers: the **replica** (built) and **LWW sync** (§19, built). MUST-DO before the first conversion: **(a)** per-item sub-records `window.<id>.<item>` (whole-blob LWW overwrites cross-item edits); **(b)** close the §19.4 startup micro-race (post-start reconciliation); **(c)** a per-window CONTINUITY TEST in the battery (save on A → sync → restore on B → identical frame); **(d)** content continuability declared per window (Reader's copy-on-open generalized). Honest boundary: simultaneous edits to the SAME item resolve LWW-newest — rare, a position nudge, never data loss. Put-state-where-the-data-lives (1.8) beats replication where a host-owned store exists | everything | agreed; the foundation |
| 16.5 | ✅ **New notification sources stay inside the §4.5 filter's logic** — each our own generated event with its own toggle in its app's Settings category; general phone-notification forwarding stays out. Live sources: Mail, SMS, **incoming-call caller ID** (approved 2026-09-01 — "not an app, an extension of the notifications"; missed calls land in Notices), Torrents done (built), tmux waiting-alerts (built), Damage events; Calendar 4.4, NWS 14.4, Timers 6.3 went with their windows. **The signature grows ONCE**: (source, coalesce/thread key, body, deep-link target, urgency) | Mail, SMS, Torrents, Tmux, phone-calls | agreed |
| 16.6 | 🔴 **Faces — B612 is NEVER a default for anything** (Adam, final, after repeated re-proposals: *"let it go"*) — option-only if the library carries it; digit-heavy surfaces use the system face or DRAWN digits (`Icons.sevenSegClock`). Defaults: Mail/SMS/Notices/Feed-list = Fira Sans · Feed-read = Alegreya · Games-text = JetBrains Mono · else Clear Sans until earned. The work item is the **curated font-library expansion** (`DESIGN.md` §Type: sturdy-at-1× survivors of the 66 surveyed, OFL/Apache-clean, lint coverage + x-height normalisation per face, defaults untouched) | all | ruling recorded |
| 16.7 | ✅ **Icons** — re-scoped by the theme-icons ruling (built: the desktop theme resolves at render time, `DESIGN.md` §4.7; the 56 px lens icon landed); the quality pass targets the DRAWN set only | all | re-scoped + partly built |
| 16.8 | ✅ **Emoji/foreign-glyph policy** — the visible tofu box is the shell-wide rule; the small drawn set stays v2 | SMS, Mail, Feed, Tmux | agreed |
| 16.9 | **Engine adoptions apps are waiting on**: texture-cache adoption (landed 2026-09-06); §5 rule 5 (speculative pre-compression) pays the switcher and games; rule 10 (cross-window deltas) pays every window switch | — | standing; none blocks a v1 |
| 16.10 | 🆕 **ONE generic window channel with MULTI-BACKEND providers** — the tmux pattern generalized: a `{"t":"win","id":…}` upgrade on the content port (pushed frames + summaries/badges + id-correlated requests), `LocalXProvider` on the PC / `RemoteXProvider` on the phone, keeper-style reconnect, staleness said with duration. **Adaptive backends** (Adam's spec, Music the archetype): backends in preference order; `needs` per backend (available if ANY backend's needs are met); the channel's staleness clock drives a settable sustained-loss threshold (a liveness decision, not a timeout); **auto-switch fires only under a window-defined condition** (PC library → phone Spotify *only if actively playing*); **switchback is DELIBERATE, never automatic**; Main's summary names the live backend (`▶ Spotify · phone`). PC-only configurations bind the Local providers directly | every HOST window; Music, Feed; Games engines later | agreed |
| 16.11 | 🆕 **Shared kit** — a fit helper that ALWAYS draws `▸` when it clips · a confirm-level helper (every irreversible or outbound act stages a confirm; placement per §1.7) · the one-shot notice-riding-the-title failure surface · ONE **"open on PC"** verb (content-host `xdg-open`) | all | agreed |
| 16.12 | 🆕 **The Title contract + honest NO-TRUNCATION wording** (Adam, 2026-09-01): the absolute rule governs CONTENT; rows/titles are HANDLES elided only with an advertised, reachable path; the ellipsis ban is style, not principle. **Titles are SHORT BY DESIGN — never long enough to cut**; variable content goes to the body or a notification. A `▸` in the Title = window-defect tripwire (`DESIGN.md` §2.4 r3 + §4.1) | all | recorded |
| 16.13 | ~~**The scheduled-work substrate**~~ | 🪓 went with Timers (2026-09-01) — alarms/snooze/scheduled-send were its only other consumers, all future-graded; the sketch (host-shell ticks, LWW `fired` stamp, duplicate-notification-never-missed-fire) stays recorded for any revival | axed |

**Agreed build order for the shared work:** the state substrate (16.4 a–d) → the window channel
(16.10) → deep links + the notification signature together (16.1/16.5) → the kit (16.11)
alongside the first converted window · the icon pass (16.7) before any new icon is drawn · the
font expansion (16.6) as an independent backlog item.

---

## 17. The probe ledger — cheap experiments this document depends on

In rough order of value per effort; none requires new firmware.

| probe | unblocks | effort |
|---|---|---|
| ~~Ring relay watch~~ | ✅ ANSWERED negative (glasses never send `RingRawData`); ring biometrics need the ring's own link + protocol RE — §13.1, `CLAIMS.md` | done |
| **WEA/CMAS visibility on the Pixel 10a** | the §4.5 emergency promise rides this probe ALONE (14.4 went with Weather) | an afternoon with the phone |
| **Logger service, sid 0x0F** | live on-glass log stream — makes silent decompress trouble visible; `CAPABILITIES.md`'s highest-value untested lead | small transport addition |
| **Compass feed** (mode 10 + sid 0x08) | the status-bar tape placeholder; Navigation | small; V-graded wire, never run by us |
| **IMU enable** (EvenHub Cmd 19/20) | any opt-in head feature, someday | small; default-off per §7.1 |
| **File export** (sid 198/199, NOT_SUPPORT-safe) | the "no firmware read-back" claim | small; read-only probe |

The wake-word probe (sid 0x07) left this ledger with §8's exclusion — no consumer remains; it
stays graded 🟡S in `CAPABILITIES.md` for whenever Scout's future self returns.

---

## 18. Tally — superseded by §20

~175 graded items at the explosion's close (54 v1 · 34 v1.5 · 40 v2 · 20 future · 2 never ·
4 probe-gated). Everything this section once scheduled has happened: the refinery is §20, the §16
contract is settled AND built, and Files (§5) and Torrents (§19) shipped through `WINDOWS.md`.

---

## 19. TORRENTS — added by Adam 2026-09-01 ("Yes! I always intended for a real qbittorrent integration")

> ✅ **BUILT 2026-09-01 (evening) — `TORRENTS.md` is the record, `HANDOFF.md` §23 the build and
> its four review rounds.** Adam's verdicts supersede the grades below; his rule for this and every
> later window: **no v1/v1.5 staging — complete and polished before the next app.** Ships whole:
> T.1–T.6 and T.8, a **seeding-under-a-week list** (TL's hit-and-run window), account Stats, and
> **search through the on-glass keyboard** (`DESIGN.md` §4.8). 🪓 Cut by him: T.7 (shelf glue —
> everything stays in `~/Downloads`), T.9 (categories), T.10 (RSS — *"i never automate
> torrenting"*), T.11 (a second tracker — TorrentLeech only this iteration), T.12 and any
> magnet/URL typing. Refinery questions answered: TorrentLeech; browse AND search;
> delete-with-data from glass behind a double confirm. Notification toggles live in Settings →
> Torrents — the general rule from now on (`WINDOWS.md` §1).

**His spec, verbatim intent:** *"the ability to log into and browse my private torrent site and
add torrents to qbittorrent all within G2. Especially useful for things like linux distros I want
to try and other large downloads better done via torrent."* Credentials live host-side in the
gitignored config.

**Declares:** List (transfers / tracker browse / search results) → Document (torrent detail) →
actions · needs **HOST** · face Fira Sans · summary `2 active · 1.2 MB/s · 1 done` · state: view,
cursors · settings: notify rules, tracker account rows.

| # | idea | note | grade |
|---|---|---|---|
| T.1 | Transfers list: name, progress **block bar**, speed, ETA, state | qBittorrent Web API host-side on a pacer | v1 |
| T.2 | Done-notification (§16.5 source) with a deep link to the transfer | | v1 |
| T.3 | **Private tracker browse** — login session host-side, category listings as rows | per-site HTML adapter; a markup change breaks parsing loudly | v1 |
| T.4 | Tracker search via typed text (replicas) | as built: the §4.8 keyboard | v1 |
| T.5 | Torrent detail Document (description, size, seeders) + **Add** action | Add is outbound ⇒ confirm level (§16.11) | v1 |
| T.6 | Pause / resume / delete (delete-with-data = double confirm) per transfer | irreversible placement per §1.7 | v1.5 |
| T.7 | **Shelf glue**: a finished book-category torrent lands in `~/books` → Reader shelf notification | 🪓 cut | v1.5 |
| T.8 | Torrents → Files deep link at the payload path | §16.1/16.2 | v1.5 |
| T.9 | Categories/labels on add; per-category default paths | 🪓 cut | v1.5 |
| T.10 | RSS/auto-watch on the tracker | 🪓 cut (*"i never automate torrenting"*) | v2 |
| T.11 | Multiple trackers (adapter per site) | 🪓 cut this iteration | v2 |
| T.12 | Files → Torrents hand-off (.torrent file → add) | 🪓 cut | v2 |

---

## 20. The 2026-09-01 live refinery — cuts, adds, and the standing wow order

Adam's pass over the G2CC roster and two idea-explosion rounds. Supersedes §18's counts.

**🪓 Axed (recorded in place, never re-propose):** Deliveries (1.14) · Calendar (§4) · Timers
(§6, taking §16.13 with it) · Search (§12) · **Weather** (§14 — *"I prefer the weather app on my
phone … it doesn't change often enough within the same day"*) · **Health** (§13, closed from both
directions — ring closed AND *"I don't do health tracking or use Aria anymore"*; aria is retired).
Scout/Aria/CC were already out (§8).

**✅ Added/steered:** the **TORRENTS window** (§19) · Feed comic sources (11.12: xkcd, 8-Bit
Theater binge) · **incoming-call caller ID as a §16.5 notification source** ("not an app, an
extension of the notifications") · Info steered to useful system stats (§9: drive fill, processes,
temps; novelty stats rejected) · the Games 10b block (roster adds, the emulation lane + ROM
pace-screener, the Balatro real-game seam).

**🚫 Pitched and rejected (do not re-pitch):** TOTP codes on glass · Fitbit-fed health · audiobook
handoff (*"I'm a reader not a listener"*) · serial/webnovel subscriptions · reading stats ·
find-my-phone (within BLE range it isn't lost; out of range the glasses aren't working) · star map
(*"a ton of work and likely janky"*) · and the earlier lane: watchers, tickers, a standalone
comics/CBZ reader, reader-mode browse window, teleprompter, presentation remote, PC live captions,
chat bridges, a demos/eyecandy toy window.

**The standing wow order (Adam-approved 2026-09-01)** — how cool each survivor can be within
DamageWM's system; risk still prices the actual sequencing per window:

1. **Games** — ✅ BUILT 2026-09-04 (`HOLDEM.md` §17, `HANDOFF.md` §26): Texas Hold'em whole; the
   kit under `windows/games/kit/` is built for blackjack, hearts and gin, **none of them built**;
   the 10b emulation lanes still on revisit.
2. **Torrents** (§19) — ✅ BUILT 2026-09-01 (`TORRENTS.md`, `HANDOFF.md` §23).
3. **Files** — ✅ BUILT 2026-09-01 (§5, `HANDOFF.md` §22).
4. **Music** (adaptive PC-library ↔ Spotify per §16.10) — ✅ BUILT 2026-09-01/02 (`MUSIC.md`,
   `HANDOFF.md` §24); the on-phone measured items remain.
5. **Feed** + comics — ✅ BUILT 2026-09-09 (`FEED.md`, `HANDOFF.md` §43); the polish session and
   the measured walk are next (`FEED.md` §8.3–§8.4).
6. **Mail**
7. **SMS** (+ caller-ID source)
8. **Info** (useful-stats steer)
9. **Notices**

Shipped and polishing outside the list: Reader, Tmux.
