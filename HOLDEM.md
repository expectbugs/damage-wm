# Games · Texas Hold'em — the design record and build plan

**Status:** designed 2026-09-03/04 with Adam, in session. Not built. This file is to
`GamesWindow` what `MUSIC.md` is to `MusicWindow` and `TORRENTS.md` to `TorrentsWindow`:
the refinery verdicts first, then the design, then the build order.

**Read first:** `CLAUDE.md` → `REMINDER.md` → `HANDOFF.md` §19–§25 → `WINDOWS.md` (the bar and
the checklist) → `DESIGN.md` §1 (input), §2 (geometry), §4.6 (content modes), §6 (motion),
§9.1 (persistence). This file wins on Hold'em; `DESIGN.md` wins on shell design; `overview.md`
wins on hardware facts; `WINDOWS.md` §1 wins on what every window owes.

**Scope.** The **Games** window (the hub, the shared bankroll, the standings) plus **Hold'em**
built whole — Adam's no-staging rule (2026-09-01: *"completely built to its best state before we
move on"*). Later games (blackjack, hearts, gin) are out of scope here but the kit is built for
them. Also in scope: one **shell contract change** and its retrofit across five existing windows
(§3) — Adam's rule, stated during this design and general to the whole system.

---

## 1. Verdicts (Adam, 2026-09-03/04)

The refinery pass `WINDOWS.md` §3 step 1 requires. Numbered so later work can cite them.

| # | verdict |
|---|---|
| 1 | **Games is one window**, individual games selectable inside it. Built one game at a time, properly. |
| 2 | **Hold'em first.** *"Once that is in and working well, the rest will be much quicker and easier to implement… as long as we are careful to design it in such a way that most of what goes into Hold'em is modular and reusable, rather than bespoke."* |
| 3 | **Multiplayer is out** — a maybe-future milestone, not designed for now. The seat model survives because bots need the same per-seat projection. |
| 4 | **Decent-casual bots.** *"if it gets boring I can scope better bots as a future project."* |
| 5 | **No exclusive mode.** Music Mode exists for passive listening and to stop accidental ring presses under gloves; a card game is active. (Reserved for a future idle game — Paperclips — where watching numbers grow while working is the point.) |
| 6 | **Look as nice as possible** now the G2CC limits are gone. |
| 7 | 🔴 **Suit colour is carried at CARD scale, not pip scale**: black-suit cards are unfilled outline cards, red-suit cards are filled mid-grey. *"I just see no need to sacrifice color when there's only 2."* Adam on robustness: *"A wireframe-esque card vs a filled mid-grey-level card is easy to tell the difference in color regardless of what is behind the transparency in real life."* |
| 8 | **Card art is code-drawn**, not AI-assisted. With the body carrying the colour, the art has less to do; code-drawn is sharper at 48×66 and ships no generated assets. |
| 9 | 🔴 **All four heights, properly** — 288 / 352 / 416 / 480, small size tight but not degraded, extra height buys **information density** and not bigger cards. (`WINDOWS.md` §1, Adam 2026-09-02.) |
| 10 | **6-max.** |
| 11 | 🔴 **No rebuys. Sit-and-go, last one standing.** *"Once the players sit down, they are in it until only one person is left. You can leave early and cash out, but you can't rebuild your pot. Half the fun of holdem is those desperate moments where your meager pot is at a huge disadvantage with a high blind against rich opponents, if I can just dump another $200 in that wont matter at all."* |
| 12 | **Shared cash pool across every betting game.** Winnings at Hold'em fund a blackjack buy-in and back. |
| 13 | **$1,000 base bankroll · $1/$2 blinds · $1 minimum · no betting limits.** |
| 14 | **Refill to $1,000 when broke, and it increments a prominently displayed Loser Count.** *"to embarass me for being a loser haha."* |
| 15 | **Broke = can't post the blind**, not literal zero. |
| 16 | **Three tables**: Regular ($200), Big Boy ($1,000), Unlimited (any entry, bots random $1k–$10k in $1k steps, $5/$10 doubling). |
| 17 | **Blind escalation is HAND-based** (Adam: timers were never on the table — with always-on persistence a clock would escalate while the glasses sit in their case). **20 hands per level.** SB increments, BB is always 2×SB. |
| 18 | **Big Boy accelerates**: SB +$1 per level until SB passes $10, then doubling — preserves the 500bb deep early game and still finishes. |
| 19 | **Busted bots clear the seat.** The table shrinks 6→5→4→3→2→winner. No re-entry into a running tournament. |
| 20 | **Bots have persistent identity, personality and depth.** *"I don't need the opponents to all be expert players, but I do want them to have depth and feel real."* Traits are dials, mood is state, randomness amplitude is itself a trait. |
| 21 | **Character economics**: `General Wealth` sets starting funds ($500–$10,000) and refill size; **1–12 lives** (skewed low) rolled independently of wealth; short downtime between lives set by mood and moodiness; on the last life, retirement with a **wealth-based recovery** — richer characters return sooner because their outside income is higher. |
| 22 | **Table choice is confidence-driven, only partly tied to wealth.** *"a bad poor player can make the dumb decision to sit at the Unlimited Table, just make it much less likely."* |
| 23 | **Persistent bot cashflow makes Unlimited the difficulty tier** — effective characters accumulate and buy in larger. *"those harder opponents represent bigger opportunities."* |
| 24 | 🔴 **A visible entry FEE, not a hidden rake, and it applies to Adam too.** 5% of buy-in. Adam: *"a visible fee rather than a rake, for everyone including me."* |
| 25 | 🔴 **Adam is just another character** — $1,000 wealth, infinite lives, invisible traits, appears in the standings. His Loser Count is his lives spent. |
| 26 | **Background economy: 2–3 tournaments per tournament Adam plays, roster ~35.** Throughput comes from frequency, not concurrency — a bigger roster dilutes the cast and a high ratio switches off the free adaptive-difficulty property. |
| 27 | 🔴 **The background economy advances ONLY while Adam is playing.** Never wall-clock. This is what makes verdict 30 true. |
| 28 | **Watch the bots act**, paced, always interruptible (scroll or tap skips to your decision). Pace is a Settings row. |
| 29 | **Tap to deal.** *"I hate missing the result of the entire hand, that's like missing the finale of the movie."* A showdown stays up until you act. |
| 30 | **No notifications by default.** *"the state does not change while I am away from the game so there's no point."* Toggles exist in Settings → Games, defaulting off. |
| 31 | **Bet sizing**: a preset ladder, plus a Custom row that opens the §4.8 keyboard. |
| 32 | 🔴 **Confirm on EVERY action**, cursor landing on Cancel, configurable in Settings. *"Scrolling one notch and then tapping once to confirm is not a big deal and worth the safety."* |
| 33 | 🔴 **Check and Fold are the SAME top row** — contextual: Check when checking is free, Fold when facing a bet. It is **not** exempt from the confirm, precisely because the row changes meaning under a fixed muscle memory. (An earlier suggestion to exempt Check was proposed and **rejected** by Adam for this reason — do not re-propose.) |
| 34 | **Last action per player on the table; a full hand history behind a menu row.** |
| 35 | 🔴 **General shell rule — activation source matters.** *"Going to Games from the switcher should auto-resume… Going to Games from Main should present the Games List… This should be true of any window that has multiple base functions (like Reader… Similarly, going to Tmux from Main should present a list of sessions, but Switcher to Tmux should go directly into the last session where I left off)."* **Retrofit every window in the same pass.** |
| 36 | **Side pots: read the authority, do not copy code.** Adam: *"we can look up the correct math… rather than reinventing the wheel."* Resolved as: copy the RULES TEXT and a TEST CORPUS, and differential-test against an MIT reference as an oracle. No third-party code in the repo (a release is intended — `CLAUDE.md` clean-room section). |
| 37 | 🔴 **No tells.** *"characters don't get a tell, beyond regular traits/playstyle being noticed during play."* Reads are earned from a character's actual play (§7.7), never from a planted signal. |

### Rejected, recorded so they are not re-proposed

- ❌ **Exclusive mode for a card game** (verdict 5).
- ❌ **Exempting Check from the confirm** (verdict 33).
- ❌ **A minimum buy-in at Unlimited.** Argued for on the grounds that a short stack makes every pot a shove; **withdrawn** once verdict 11 removed rebuys — being short in a tournament is a real strategic situation and is the stated point of the table.
- ❌ **Cash-game format.** Adam's first framing (2026-09-03) was a cash game with rebuys;
  **reversed by verdict 11 the same evening** once he saw that rebuys defeat the escalating blinds
  and erase the short-stack pressure he wants. The vocabulary changed with it — buy-in is an entry,
  not a stack you can top up.
- ❌ **A hidden pot rake.** Replaced by the visible entry fee (verdict 24).
- ❌ **Permanent death for bots** (proposed, overridden by verdict 21's lives — an arc with stages beats a single fact).
- ❌ **Multiple Hold'em tables open at once** (Adam: *"I only want one game of holdem going at a time"*).
- ❌ **Wall-clock background simulation** (verdict 27).
- ❌ **Bot tells** — a sizing or timing signal tied to hand strength, scaled by `discipline`.
  Proposed and rejected 2026-09-04 (verdict 37): reads are earned from observed play, never
  planted.
- ❌ **A Python card daemon** (OpenSpiel / RLCard). Correct for G2CC's 2026-06-28 research and for a multiplayer table server; wrong here — verdicts 3 and 4 remove both reasons, and pure Kotlin means Games needs no host at all.
- ❌ **The texture cache (modes 12/13/14) for card art in this build.** See §2.

---

## 2. Facts the design stands on

**Measured, from the repo and the hardware record:**

- **Content geometry.** `Layout` puts content at `y 34, h = H − 64`, x 16, w 608. Canvas gets the
  full 608 (the 12 px rail is List/Document only — `DESIGN.md` §4.6).

  | Size | content | vs 480 |
  |---|---|---|
  | 288 | **608 × 224** | 54 % |
  | 352 | 608 × 288 | 69 % |
  | 416 | 608 × 352 | 85 % |
  | 480 | 608 × 416 | 100 % |

  **Width is never the constraint; height always is.** Five cards at 72 wide with 16 px gaps is
  424 px inside 608.
- **Latency** (`overview.md` §5.2, n=1,488 flushes): `ms ≈ 60 + bytes/50`.
- **Rect budget** 5 mode-3 rects at the 3-deep pipeline (`Geometry.rectBudget`).
- **`CanvasView` exists and is exercised** — Tmux's live grid is its first user. It hands the
  window a `Gray8` and a `Rect` and takes damage tracking back from the WM (`DESIGN.md` §4.6:
  *"a full-frame 608×416 canvas repaint is 3.8–6.3 KB and ~1.57 fps"*).
- **Ink.** Canvas ink is linted as a **warning**, not an error. Modeled for verdict 7's scheme
  (7 cards visible, about half red and therefore filled): **≈8.1 % of content at 288**
  (48×66 cards) and **≈10.0 % at 480** (72×100). Both under even the List budget. A
  bright-filled-body scheme for *all* cards would have been ≈29 % — the fill/outline split is
  what makes the look affordable.
- **The G2CC constraint set is gone.** G2CC priced this window at ≤4 image tiles, ≤288×129 each,
  uncompressed gray4, ~1 s per tile. None of that applies. `/home/user/G2CC/games/gamelist.md`
  §"Card games (researched 2026-06-28)" is still worth reading for its interaction findings, and
  two of them carry forward verbatim: **suits must read by SHAPE as well as level** (red and
  black both go dark in 16-gray), and **the corner index is what shows when cards are fanned**,
  so it is the crisp element.

**Modeled, labelled as such:**

- A card-table repaint of a few KB lands around **100–140 ms** on the measured curve.
- A 7-card hand evaluation at a few microseconds puts a 2,000-rollout equity estimate near
  **30 ms**. Unverified on the Pixel 10a — measure before optimising.
- Tournament length. An earlier estimate priced this on blind erosion alone (~400 hands at
  Regular, ~2,000 at Big Boy). **That was wrong** — in no-limit, stacks move in chunks and one
  all-in ends a player at any level. Real 6-max sit-and-gos run roughly **60–120 hands**. The
  blind ladder therefore exists to create **pressure**, not to end the game. Actual distributions
  come from the harness (§13), not from arithmetic.

**The texture cache is deliberately NOT used here.** Mode 13 is 8 bytes to draw a cached image,
has a source-colour-0 transparency bit, and does not burn a fid — a 5-card board would be 40 bytes.
But: no shipped window emits modes 12/13/14 (the simulator models them and `TextureCache.kt`
builds them; nothing else touches them), the compositor's model is *nominal pixels under a plane
map* which cached draws sit outside of, and **13/14 have no stereo variant**, so cached art is
locked to zero disparity. That is a compositor project with its own risk. Hold'em paints ordinary
pixels through `CanvasView` and the existing pipeline. Revisit after Hold'em ships.

**Licence posture.** Everything here is ours. No third-party engine, no generated art assets, no
ROMs. The only external artefacts are (a) published **rules text** (Robert's Rules of Poker, TDA)
which is prose describing rules and is not copyrightable as such, and (b) an MIT reference
implementation used **only as a test oracle in a scratch venv**, never vendored. See §13.

---

## 3. The shell change: activation source, and the retrofit (verdict 35)

**Verified 2026-09-04.** `Shell.focus()` calls `onActivate(services)` from a single path and
`DamageWindow` carries no activation-source argument. `ReaderWindow.onActivate` refreshes the
library and resumes whatever level it was on. So today **Main → Reader and switcher → Reader are
identical** — both land in the open book. Same for Tmux.

Adam's rule, general to the system:

> **Switcher = resume. Main = the window's root list.**
>
> A window with multiple base functions presents its chooser when entered from Main, and resumes
> exactly where it was when entered from the switcher.

### The contract addition

```kotlin
enum class ActivationSource { SWITCHER, MAIN, DEEP_LINK, RESTORE }

// WindowContract.kt
open fun onActivate(ctx: ShellServices, from: ActivationSource) {}
```

- `SWITCHER` — resume the deepest persisted level. The existing behaviour.
- `MAIN` — go to the window's **root** level. Persisted deep state is **kept, not discarded**:
  navigating back down must land exactly where it was (§9.1 is not weakened by this — the rule
  changes the *entry point*, never the *stored state*).
- `DEEP_LINK` — `open(target)` decides; unchanged.
- `RESTORE` — shell start-up. Resumes, like SWITCHER.

Keep the old single-argument `onActivate(ctx)` as a defaulted overload so nothing breaks in one
commit; migrate every window in the same pass and delete it.

### The retrofit, window by window

| window | root when entered from Main | resumes from switcher |
|---|---|---|
| **Reader** | the shelf (folders / library) | the open book at its offset |
| **Tmux** | the session list | the last session, with mode (flowed/grid, frozen/live) intact |
| **Files** | the locations list | the open directory + cursor |
| **Torrents** | the torrent list root | the open torrent / level |
| **Music** | the Music root (**NOW PLAYING** — `HANDOFF.md` §24.4 reversed verdict 4; do NOT change this) | whatever level was open |
| **Games** | the Games list | the live table |

⚠ **Music trap.** NOW PLAYING *is* the Music root. Entering Music from Main goes to NOW PLAYING,
not to a chooser, and not to the queue. Do not "fix" this into a browse list.

⚠ **Preview is not activation** (`DESIGN.md` §4.3 rule 1). The switcher's preview render must not
call `onActivate` with any source. Unchanged, but easy to break while touching this path.

**Tests this needs:** for every retrofitted window, a pair asserting that
`focus(w, from = SWITCHER)` reproduces the persisted level byte-identically (the existing §9.1
gate, unchanged) and that `focus(w, from = MAIN)` lands on the root **without mutating the stored
deep state** — back down must still reach it.

---

## 4. The Games window (`GamesWindow`, id `games`)

**Declares:** List root · needs **nothing** (pure Kotlin, no host — a first for this system) ·
face **Clear Sans** (the system face; digit-heaviest surface reasoning from `DESIGN.md` §4.5b's
Info note) · `preferredHeight` global · icon: a drawn card-pair glyph added to the §4.5b set ·
title `Games`, deepening to `Games · Hold'em` and `Hold'em · Regular`.

**Summary line for Main** (cheap and side-effect-free — reads cached state only):

```
$847 · W12 · L3                    no tournament running
Hold'em · 4 left · $340            a tournament in progress
```

### Levels

```
Games (root, from Main)
├─ Hold'em ──────────────▶ live table, or the table-select level
├─ Standings ────────────▶ the roster: characters by wealth
│                          └─ a character ──▶ career + your head-to-head + observed stats
└─ Bankroll ─────────────▶ cash · Loser Count · tournaments won · refill (confirmed)
   (wrap-to-end)  Settings
```

The **bankroll and Loser Count live at Games level**, not under Hold'em, because the pool is
shared by every betting game (verdict 12).

**The scoreboard** is drawn in the **seven-segment digits** the silent-mode clock already uses
(`DESIGN.md` §1.5 — *"drawn, never typed"*, so the locked faces stay four): `$847 · W12 · L3`. Cash · tournaments won · Loser Count.

### Standings

A List of every roster character plus Adam, sorted by net worth, showing name · wealth · a state
mark (playing / between lives / retired). A character's detail level shows General Wealth band,
lives remaining, career record, **your head-to-head** (hands, net, tournaments they knocked you
out of and vice versa) and **observed stats** — see §7.6.

---

## 5. Hold'em: format, tables, economy

### 5.1 Format

**6-max single-table sit-and-go.** Buy in once. **No rebuys, no top-ups, no re-entry.** Play until
one player holds every chip.

🔴 **Cashing out early** (verdict 11: *"You can leave early and cash out, but you can't rebuild
your pot"*): your stack returns to your bankroll and **the remaining characters play the tournament
out in the background** (§7.5) — the table does *not* evaporate. This is Adam's own correction to
an earlier "the table dissolves" answer and he is right: playing it out is what keeps the economy
conserved and lands the winner's cashflow where it belongs (verdict 23 depends on it).

**Winning it** — you hold every chip: the whole prize pool moves to the bankroll,
`tournamentsWon++`, the table closes, and Hold'em returns to its table-select level.

Chips are dollars 1:1, so a table is conserved: 6 × buy-in in, the same amount out.

### 5.2 The three tables

| | entry | fee (5%) | blinds | escalation (every 20 hands) | opponents |
|---|---|---|---|---|---|
| **Regular** | $200 | $10 | $1/$2 | SB **+$1** — `1,2,3,4,…` | $200 each |
| **Big Boy** | $1,000 | $50 | $1/$2 | SB **+$1 until SB > $10, then doubling** — `1…10, 20, 40, 80, 160, 320` | $1,000 each |
| **Unlimited** | **any** | 5% | $5/$10 | SB **doubling** — `5, 10, 20, 40, 80, 160` | see below |

- **BB is always 2 × SB.** Minimum bet and minimum raise increment are the BB, per no-limit rules.
- **No betting limits.** All-in is always available.
- **Unlimited opponents** buy in according to their wealth and nerve (§7.4), in **$1,000
  increments where they can afford at least $1,000**, and otherwise with whatever they bring. A
  broke character on a heater sitting down with $600 against $8,000 stacks is intended behaviour,
  not a defect.
- **No minimum buy-in at Unlimited** for anybody, Adam included (verdict 11 makes short-stacked a
  real strategic situation rather than a coin flip).
- **Heads-up**: the button posts the small blind, acts first preflop and last postflop.

### 5.3 The entry fee (verdict 24)

**5% of buy-in, shown on the buy-in row**: `Regular · $200 + $10`. Rounded up to a **$1 floor**,
since $1 is the chip denomination and Unlimited accepts tiny entries. It applies to every player
including Adam. Two reasons it is not optional:

1. It is the money-supply sink. Refills and new characters inject; nothing else removes.
2. In a perfectly zero-sum economy a break-even player never refills, so the Loser Count would
   only measure variance. With a fee, staying solvent means actually beating the game.

**Self-correcting by construction:** a character's refill size is fixed by their General Wealth,
but the fee scales with the stakes they play. As the economy inflates, characters move up tiers
and pay larger fees while their injections stay the same size — so the sink grows and the source
does not. Inflation stays real (Adam wants the game to be at higher stakes after months) without
compounding. Where it settles is a harness measurement (§13), not arithmetic.

### 5.4 The bankroll (shared, verdict 12)

```
Bankroll                     shared by every betting game
  cash            $1,000 at the start
  loserCount      0
  tournamentsWon  0
```

- **Buy in, don't bet from the bankroll.** Entry + fee leaves the bankroll; the stack lives with
  the table; cashing out or winning returns it.
- **Net worth** = bankroll cash + the open table's stack (one table at a time).
- **Broke** (verdict 15, *"can't post the blind"*) = no table running and cash below what it takes
  to sit at the cheapest seat in the game. Regular and Big Boy have fixed entries, so the cheapest
  seat is **Unlimited with a tiny stack**; its big blind is $10 and the fee has a $1 floor ⇒
  **broke is cash < $11**. Kept as a derivation rather than a magic number so it stays correct if
  the Unlimited blinds ever move.
- **Refill** = back to $1,000 (Adam's General Wealth), `loserCount++`. Offered when broke and
  reachable manually from Bankroll at any time, with the same cost. Confirmed, cursor on Cancel.
- **Adam is a roster character** (verdict 25): General Wealth $1,000, **lives infinite**, traits
  invisible and emergent from play, appears in the standings. His refills are his lives spent, and
  the Loser Count is the counter for them.

---

## 6. The card kit — the reusable half (verdict 2)

`core/…/windows/games/kit/`. **Nothing here knows what Hold'em is.** This is the §16-machinery
pattern that Files produced, applied to card games.

| module | what it owns |
|---|---|
| `Cards` | `Rank`, `Suit`, `Card`, `Deck`; a **seeded** shuffle. Seeded because persistence and testability both fall out of it (§11). |
| `CardArt` | draws one card at any ladder size into a `Gray8`: outline body for black suits, mid-grey filled body for red (verdict 7); rank index, pips, face cards, the back. Code-drawn (verdict 8). |
| `HandFan` | lays out N cards with overlap chosen from available width; the 1 px separation stroke so fanned cards read as distinct. |
| `TableLayout` | the **height ladder** (§9) as a slot allocator. Any table game asks it for regions; it never mentions poker. |
| `Seats` | N seats, occupant = human or bot, and the **per-seat view projection** (a seat is only ever handed what it can see). Kept from the multiplayer design because a bot needs exactly the same projection (verdict 3). |
| `HandEval` | 5-from-7 evaluation and comparison. Shared by every poker variant. |
| `Pots` | contributions, side-pot construction, uncalled-bet return, odd-chip rule. §12/§13. |
| `ActionLevel` | a list of legal actions → a shell level, with the confirm policy and rest positions applied. |
| `Money` | formatting; the seven-segment scoreboard renderer. |
| `Bankroll` | the shared pool, Loser Count, tournaments won. Persisted and LWW-synced. |
| `Roster` | the ecology (§7) — characters, wealth, lives, mood, table selection, background simulation. Behind one call: *"give me five opponents for this table."* |

**Bespoke to Hold'em:** betting rounds and street order, blind/button rotation and escalation, the
bot decision policy (§8), and the specific table composition (§9).

> 🔑 **The ecology has a clean seam.** The table asks the roster for opponents; everything about
> wealth, mood, lives and retirement lives behind that one call, and the poker engine never learns
> it exists. That is what keeps a large subsystem contained and independently tunable.

---

## 6b. File map

```
core/src/main/kotlin/wm/damage/core/
  shell/
    WindowContract.kt       + ActivationSource, onActivate(ctx, from)      §3
    Shell.kt                focus(w, from) — switcher / Main / deep link / restore
  windows/games/
    GamesWindow.kt          the hub: Hold'em · Standings · Bankroll · Settings   §4
    kit/
      Cards.kt              Rank · Suit · Card · Deck · seeded shuffle
      CardArt.kt            one card at any ladder size; outline vs filled body  §9.1
      HandFan.kt            overlap layout + the separation stroke
      TableLayout.kt        the height ladder as a slot allocator               §9.2
      Seats.kt              seats, occupants, per-seat view projection
      HandEval.kt           5-from-7 evaluation and comparison
      Pots.kt               contributions · side pots · uncalled return · odd chip §12
      ActionLevel.kt        legal actions -> a level, confirm policy applied     §10
      Money.kt              formatting + the seven-segment scoreboard
      Bankroll.kt           the shared pool, Loser Count, tournaments won        §5.4
      Rng.kt                counter-based, stateless                            §11.2
    roster/
      Character.kt          nine traits + circumstances                         §7.1
      Mood.kt               one mood value, two readers                         §7.2
      Roster.kt             selection, lives, downtime, retirement, standings    §7.3-7.4
      Background.kt         the background economy, gap-scheduled                §7.5
    holdem/
      HoldemTable.kt        streets, betting, blinds, button, escalation         §5.2
      HoldemRules.kt        the authority-derived rule set                       §12
      HoldemBot.kt          modulate() + policy()                                §8
      HoldemView.kt         the CanvasView paint, four heights                   §9.2
      HoldemLevels.kt       action / sizing / confirm / hand history             §10
desktop/src/main/kotlin/…/Main.kt      registration + --games-check              §13.4
phone/src/main/kotlin/…/ShellService.kt registration
core/src/test/…/games/                 engine, oracle, persistence, activation   §13
core/src/test/resources/holdem/sidepots.json   the corpus we own                 §13.2
```

**Nothing in `kit/` or `roster/` knows what Hold'em is.** `holdem/` is the only package that does.
That is the seam that makes blackjack and the trick-takers cheap later (verdict 2).

## 7. The ecology (verdicts 20–23, 26, 27)

### 7.1 A character sheet has two halves

**Behavioural traits — nine dials, fixed for life, never displayed:**

| trait | controls |
|---|---|
| `tightness` | baseline hand-selection threshold |
| `aggression` | bet/raise vs call/check when they are in a pot |
| `bluffFreq` | how often they represent what they do not have |
| `discipline` | Adam's *fatigue resistance* — how far pressure moves them off baseline |
| `moodiness` | mood swing amplitude and decay rate |
| `tiltSign` | **signed** — some people tilt loose-aggressive, others tight-passive. Two very different opponents after a bad beat |
| `stackCourage` | short-stack response: correct push-fold, or folding toward the felt |
| `observance` | whether they adapt to opponents at all |
| `consistency` | **per-decision noise amplitude** |

🔑 **`consistency` is the answer to "randomness that does not erode character."** If every
character has the same jitter, jitter dilutes identity. When the amplitude is itself a trait, an
erratic player is *reliably* erratic and a rock is *reliably* a rock — the noise expresses who
they are instead of smearing it.

**Circumstances — the situation they play from:**

`generalWealth` ($500–$10,000, **skewed low**) · `livesTotal` (1–12, **skewed low**, rolled
**independently** of wealth) · `livesLeft` · `bankroll` · `careerRecord` · `state`
(playing / between-lives / retired) · `returnsAt`.

Both distributions are **power-law-ish, not uniform**. Uniform $500–$10,000 would put the average
character at $5,250 and leave Regular populated only by the low-confidence, not the poor — the
pyramid shape is what keeps the bottom table busy. Same reasoning for lives, and it produces a
**cast structure**: 1–2 lives are extras who bust and vanish (most characters), 3–6 are regulars,
8–12 are institutions — the handful you build a history with.

### 7.2 Mood is state, with two readers

There is **one** mood value per character, not a mood system and a tilt system.

- **In-game it is tilt** — how they play this hand, scaled by `moodiness` and directed by
  `tiltSign`.
- **Between games it is ambition** — which table they choose to sit at (§7.4).

It moves with results and decays toward baseline. Roster-level state: also `form`, a slower
decayed streak that carries between tournaments so *"Steve's been running hot lately"* is real,
while traits never drift. **Identity is permanent, mood is not.**

### 7.3 Lives, downtime and retirement (verdict 21)

- Bust the bankroll → spend a life → **short downtime** → refill to `generalWealth` → back in.
- 🔑 **Downtime is how mood resets.** A high-`moodiness` character hits the ATM and comes straight
  back — and **comes back still tilted**, because mood carries over a short break. A disciplined
  one takes a long break and returns at baseline. The trait that brings them back fast is the same
  trait that makes them lose when they get there, and you can watch the loop run.
- Out of lives → **retired**, with a **wealth-based recovery**: `generalWealth` regenerates a
  stake (they went back to work) and they return when they can afford one. Richer characters
  return sooner. A flat game counter was proposed and replaced by this — it is characterful,
  self-tuning, and uses a trait that already exists.
- A returning character comes back with a fresh life allocation and their career record intact.

### 7.4 Table selection (verdict 22)

```
affordability gate  →  ambition = baseConfidence(traits) × mood × form  →  tier, with noise
```

Only **partially** tied to wealth, deliberately. A poor character on a heater buying into
Unlimited short is intended; a wealthy cautious one grinding Regular is intended. This is also
what keeps all three tables populated instead of sorting sterilely by bankroll.

### 7.5 The background economy (verdicts 26, 27)

- **2–3 background tournaments per tournament Adam plays.** Roster ~35 characters.
- **A table Adam cashes out of early is played out too** (§5.1), and that is *additional* to the
  2–3 ratio — it is finishing a table that already exists, not a new one.
- 🔴 **Advances only while Adam is playing** — never wall-clock, never on a schedule. This is what
  makes "nothing changes while I am away" true, and it is what justifies having no notifications.
- **Run in the gaps between Adam's own decisions.** He spends seconds per hand deciding; the CPU
  spends none. The economy advances at no wall-clock cost, and it is thematically right — the
  world plays while you play.
- **Same engine, same rules**, or the economy sorts by a different function than the one Adam
  plays against and the difficulty curve stops meaning anything. A **cheap decision mode** (far
  fewer rollouts, plus a generated preflop equity table over the 169 hand classes) is permitted
  for background games only; fidelity drops only for games nobody sees.
- Why not more: at a high ratio only a small fraction of a character's lifetime action involves
  Adam, so *"characters who beat Adam get rich and migrate up"* becomes a rounding error and the
  ecology sorts purely on bot-vs-bot fitness. **The low ratio is what preserves the free adaptive
  difficulty.** If a busy room is wanted visually, that is a presentation matter for Standings, not
  a reason to simulate at fifteen times Adam's rate.

### 7.6 What emerges, and what to watch

**Emergent and wanted:**

- **The tiers self-sort into a real poker room.** Broke characters leave, fresh entrants replace
  them at the bottom, winners migrate up. Regular stays soft, Unlimited fills with survivors — and
  because some of them got rich on variance rather than skill, Unlimited holds sharks *and* whales,
  which is a better table than six optimal bots.
- **The ecology adapts to Adam's weaknesses with no adaptive AI.** Not because characters observe
  him, but because the ones whose style happens to counter his take more of his money and rise.
- 🔑 **`observance` reaches Adam for free** (verdict 25): if his stats are tracked like everyone
  else's, an observant character who has played 300 hands against him starts folding to his bluffs
  through the machinery that already exists. No player-modelling special case.
- **The meta will cycle, not converge.** Poker is frequency-dependent — a table of maniacs rewards
  tight play, a table of rocks rewards aggression — so the population oscillates instead of
  settling into six clones of whatever was strongest in year one.

**Watch, and print a number for it:**

- **Wealth concentration.** Zero-sum play with elimination walks a closed roster toward one
  winner. Refills and new entrants are the income side, which makes the **birth rate a real tuning
  parameter**: too slow and the room empties, too fast and nobody accumulates enough to reach
  Unlimited.
- **Money supply.** §5.3 argues the fee out-scales the injections, so this should flatten rather
  than compound — but it is a claim, not a fact. `--games-check` prints total money in the system
  over 10,000 tournaments (§13).

### 7.7 Making it visible

The economy is invisible unless drawn. Standings (§4) is the world's face; a character's detail
level shows career, head-to-head, and **observed stats you earned** — VPIP and aggression measured
over hands *you actually played against them*, not their trait sheet. That is the read you would
be keeping in your head at a real table, and it is what makes a recurring opponent worth
remembering.

⚠ **Never display the trait sheet.** An `Archetype labels` Settings row may show a coarse label,
default **off**.

---

## 8. The bot decision model (verdict 4: decent-casual)

Per decision:

```
equity      = MonteCarlo(hole, board, opponentCount, rollouts)   // ~2,000 live, far fewer background
potOdds     = toCall / (pot + toCall)
state       = { stackRatio, bbDepth, playersLeft, mood, form, handsThisSession, position }
dials       = modulate(traits, state)
action      = policy(equity, potOdds, dials, legalActions)
```

`modulate` is a small readable function, deliberately not a black box, so *"why did Steve do
that"* always has an answer:

```
effTightness = base
  + (1 − discipline) · pressure(bbDepth) · scaredMoney
  − bravado(stackRatio) · headroom
  + tiltSign · moodiness · moodBadness
  + noise(consistency)
```

🔑 **Scared money is a feature.** Big stacks loosening is realistic *and* theoretically correct.
Short stacks tightening is realistic and **theoretically wrong** — push-fold theory says a short
stack must widen or blind out. Modelling the mistake is the point: the specific, recognisable way
people play badly under pressure is what makes an opponent feel like a person. It also means one
dial does double duty — a character's discipline under pressure is simultaneously their
personality and their skill.

**Determinism.** All randomness is **counter-based**, keyed by
`(tournamentSeed, handNo, seatIdx, decisionNo)` — a stateless splitmix-class function, not a
mutable stream. Nothing about RNG needs persisting beyond counters that are already part of the
game state, and a resumed decision is bit-identical. See §11.

🔴 **No tells** (Adam, 2026-09-04): *"characters don't get a tell, beyond regular traits/playstyle
being noticed during play."* An artificial sizing or timing signal tied to hand strength was
proposed and **rejected**. The read comes from the same place it would at a real table — a
character's actual play over hands you actually sat through (§7.7) — not from a leak the engine
plants for you to find. Do not add one.

---

## 9. Layouts — every level at all four heights (verdict 9)

`TableLayout` owns the exact numbers and every rect must pass GEO lint (`x`/`w` multiples of 4,
`y`/`h` multiples of 2). Bands below are the **design intent and a starting allocation**, not
final pixels.

### 9.1 The card ladder

| Size | card | board strip (5 cards + gaps) |
|---|---|---|
| 288 | **48 × 66** | 5×48 + 4×12 = 288 |
| 352 | **56 × 78** | 5×56 + 4×12 = 328 |
| 416 | **64 × 88** | 5×64 + 4×16 = 384 |
| 480 | **72 × 100** | 5×72 + 4×16 = 424 |

All grid-legal; all near the real 0.714 card aspect. Every board fits inside 608 with room — the
board is always centred and width is never the problem.

### 9.2 The table level

**Extra height buys information density, never bigger cards.**

| Size | content | bands |
|---|---|---|
| **288** | 608×224 | opponent **strip** (~44): 5 cells, name + stack, folded seats dimmed · board (66) · **one status line** (~22): pot / your stack / to call · your hole cards (66) |
| **352** | 608×288 | opponent strip grows (~66): + **last action** per seat, button and blind level marked · board (78) · status (22) · hole (78) |
| **416** | 608×352 | opponents become a **spatial arc** (~88), vertically staggered: + **your observed stats** per character · board (88) · pot (22) · hole (88) · your line (22) |
| **480** | 608×416 | arc (~96) · board (100) · pot (24) · hole (100) · your line (24) · **street-by-street betting history** (~44) · drawn chip stacks in place of bare numbers |

**Ink** (modeled): ≈8.1 % at 288, ≈10.0 % at 480. Canvas ink is a lint warning; treat these as the
design target and investigate any render that departs from them.

**Opponent card backs are the ink trap** — five seats × two backs is ten lit rectangles carrying
no information. Draw holdings as a **small mark** at 288/352; consider actual backs only at 480.

**Depth.** The board and table sit at the content plane **−1**; **your hole cards come forward to
plane 0** — the shell's existing "focused comes forward" language (§3.1), +4 B per rect, and it
makes your own hand read as yours without lighting one extra pixel. Register the region in
`Compositor.planes`; disparity must sit on the 4 px ladder (GEO006).

**Motion.** A deal is a **slide** — the sanctioned vocabulary (`DESIGN.md` §6.2: *"translation +
small fill"*), which is mode-9 rect-copy plus a fill, not an exception to the rule. Cards deal out
in sequence from a fixed shoe origin; at 480 chips slide to the pot on settlement. Ease-out,
quantised to the 4 px grid, **interruptible and preempted by input** (§6.3).

### 9.3 Other levels

- **Games root / Standings / Bankroll / character detail / hand history** — List and Document,
  which the WM tracks damage for. The list kit pans through the 64 px lens band; rows above and
  below scale with height exactly as every other list window.
- **Action, sizing and confirm levels** — `MenuSurface` at plane 0 over the table (§16.11 floating
  context menu, LOOP-ONLY, WINDOW mode only; check its boolean return).

---

## 10. Input grammar, level by level (verdicts 28–33)

**The §1 grammar is not negotiable**: scroll moves focus, tap descends, double-tap backs, a bare
long-press is a no-op.

### 10.1 The table (Canvas)

Tap does the thing the table is currently telling you it will do, and the table always says which:

| table state | scroll | tap |
|---|---|---|
| bots acting (paced) | **skip to your decision** | **skip to your decision** |
| your turn | move a seat-inspect cursor | **open the action level** |
| showdown up | inspect | **deal the next hand** (verdict 29) |

🔴 **Double-tap backs out to the Hold'em level. It NEVER cashes out.** Leaving a tournament is an
explicit menu row with a confirm. Backing out of the window leaves the table exactly as it is
(§9.1).

**Pacing** (verdict 28): each bot action is drawn as it happens, default ~600 ms apart (a Settings
row, `0` = instant). The measured draw is ~100 ms of that. This is a **pacing loop, not a
timeout** — the absolute rule stands, and the bots themselves wait forever for Adam.

After Adam folds, the hand **plays out** rather than cutting to the next deal — that is where
reads are earned — with the skip always available.

### 10.2 The action level

🔴 **Row 0 is always the give-up row, and it is contextual** (verdict 33):

| facing | row 0 | row 1 | row 2 |
|---|---|---|---|
| no bet | **Check** | `Bet →` | |
| a bet | **Fold** | `Call $X` | `Raise →` |

It is **not exempt from the confirm**. A fixed muscle memory over a row that silently changes
meaning is exactly how a reflex fold happens. (Exempting Check was proposed and rejected — see §1.)

**Wrap-end window actions**, ordered so the row reached by one notch *up* from rest is harmless:

```
… Cash out (confirmed) · Standings · Hand history ⟲ back to row 0
```

### 10.3 The sizing level (verdict 31)

`Min $4` · `⅓ pot $12` · `½ pot $18` · `¾ pot $27` · `Pot $36` · `All-in $340` · `Custom →`

Amounts are computed and shown, never typed by the player unless they choose `Custom`, which opens
the **§4.8 keyboard** in its numeric arrangement. Presets cover the overwhelming majority of
decisions in one scroll.

### 10.4 The confirm level (verdict 32)

```
Cancel                       ← cursor rests here
Confirm · Raise to $84       ← one notch, one tap
```

One notch and one tap, on every action, cursor on Cancel. The exact amount is shown here, which is
why the confirm sits *after* sizing rather than before it.

**Honest tap cost**, so the trade is visible:

| action | taps | scrolls |
|---|---|---|
| Check | 3 | 1 |
| Call | 3 | 2 |
| Raise | 4 | 3+ |

**Settings → Games → `Confirm`**: `All actions` (default) · `Money only` · `All-in only`.

🔑 **A useful consequence:** because the first tap is always harmless, §1.7 no longer forces the
action list to be contorted for misfire safety — actions can sit in natural poker order.

---

## 11. Persistence and the state split (verdict 27, `DESIGN.md` §9.1)

§9.1 is *"Adam's strongest stated requirement"*: persist **mode**, not just position; the WM owns
it; it survives WM restart; and `ShellBehaviorTest.switchAwayAndBackIsByteIdentical` is the gate.
`WINDOWS.md` §3 step 6 makes that test and the cross-shell continuity test mandatory.

### 11.1 Records (sub-records where per-item state exists — `WINDOWS.md` §1)

| record | holds |
|---|---|
| `window.games` | level path, whether a tournament is live |
| `window.games.bankroll` | `cash`, `loserCount`, `tournamentsWon` |
| `window.games.table` | the live tournament (below) |
| `window.games.char.<id>` | one per character: traits, circumstances, mood, form, career record, lives, retirement state |
| `window.games.world` | `worldSeed`, `gameNo`, roster birth/retirement bookkeeping |

### 11.2 The determinism contract

🔴 **All randomness is counter-based and stateless.** Not a mutable RNG stream whose position has
to be saved — a splitmix-class function of an explicit key:

```
deck(handNo)                    = shuffle(derive(tournamentSeed, handNo))
botDecision(seat, decisionNo)   = counterRng(tournamentSeed, handNo, seat, decisionNo)
backgroundGame(n)               = derive(worldSeed, gameNo)
```

⇒ the live table persists only `tournamentSeed`, `handNo`, `street`, the **action log**, stacks,
contributions, button, blind level and folded flags. Everything else — who holds what, what the
board is, what a bot was about to do — is *derived*, exactly, every time.

That gives the two properties the Tmux failure taught us to demand:

- **A resumed hand is the same hand.** Not a reshuffle wearing the old pot's clothes.
- **A resumed bot is the same bot.** Steve's bluff is still Steve's bluff. Re-rolling a bot's
  decision on resume would be the Tmux Focus-mode failure in a new costume.

### 11.3 What "always on" means here

Nothing runs in the background: a turn-based game simply waits, and the bots wait forever (no
timeouts anywhere). The background economy advances **only while Adam is playing** (verdict 27),
so leaving the window genuinely freezes the world — which is what makes verdict 30's "no
notifications" correct rather than a shortcut.

---

## 12. Rules that must be right (verdict 36)

Read the authority — **Robert's Rules of Poker** and the **TDA rules** — and implement from the
prose. This is the project's standing habit: prose describes, code runs, and where an authority
exists you read it rather than deriving from memory.

The four places implementations actually go wrong, none of which is the loop:

1. 🔴 **Folded players' chips still form pots.** Counting only live players is the classic defect.
2. 🔴 **The uncalled portion of a bet returns before pots are formed.** Bet $100, one caller for
   $30, $70 comes back. The most common real bug.
3. **Odd chips on a split** go to the first live player clockwise from the button. State the rule
   in code, do not improvise it.
4. **An all-in for less than a full raise does not reopen the betting.** Betting rules rather than
   pot math, but adjacent and commonly wrong.

The side-pot construction itself:

```
levels = sorted distinct total contributions of all-in players, then the maximum
for each level, from prev:
    pot        = Σ over ALL players of (min(contrib, level) − min(contrib, prev))
    contenders = unfolded players whose contrib ≥ level
    emit SidePot(pot, contenders)
```

🔴 **No third-party code enters the repo.** A public release is intended and the clean-room
section of `CLAUDE.md` governs. What we take is the **rules text** and a **test corpus**; the
reference implementation is an oracle in a scratch venv, never vendored. See §13.2.

---

## 13. Tests, harnesses, gates

### 13.1 Engine correctness

- **`Pots` property tests**: chips are conserved on every hand; the pot always balances; the four
  §12 items each have a dedicated case.
- **`HandEval`**: known rankings; ties and kickers; an exhaustive sweep over a sampled subset.
- **Rules**: blind posting, button rotation, heads-up (button posts SB, first preflop, last
  postflop), minimum raise increments, escalation at exactly the 20-hand boundary, elimination and
  table shrink 6→2.

### 13.2 The side-pot oracle

The `LensOracleTest` pattern applied to poker: an independent authority proves our model rather
than us reading our own implementation and agreeing with ourselves.

- `pokerkit` (MIT, University of Toronto CPSRG) installed into a **scratch venv**, never vendored,
  never imported by shipped code.
- A generator produces thousands of randomised multi-way all-in scenarios; both implementations
  settle them; payouts must match **to the chip**.
- The result is committed as `core/src/test/resources/holdem/sidepots.json` — scenarios and
  expected payouts, a corpus **we own outright** — so CI needs no Python and no network.

### 13.3 Persistence and continuity

- `switchAwayAndBackIsByteIdentical` for the table at all four heights (§9.1).
- `resumeMidStreetReproducesTheHand` — same hole cards, same board, same bot decisions.
- Cross-shell continuity: save on shell A → sync → restore on shell B → identical frame (§16.4c).
- **Activation source**, for all six windows: `MAIN` lands on root; `SWITCHER` resumes
  byte-identically; **`MAIN` does not mutate stored deep state** (back down must still reach it).

### 13.4 `--games-check` (a new desktop harness, alongside `--music-check` / `--epub-check`)

Headless and deterministic, run against a **scratch world** — never Adam's saved roster, bankroll
or table. Runs N tournaments and prints:

- per-character **ROI, VPIP, aggression frequency, average finish** — does each character play the
  way their sheet says?
- **roster differentiation** over a realistic number of sessions — does skill actually separate
  from variance in the time Adam will really play?
- **outcome spread** — is there a spread, or is one archetype quietly eating everyone?
- 🔴 **total money in the system over 10,000 tournaments** — the §7.6 inflation claim, as a number.
- **tournament length distribution** per table, which is what the §2 blind ladder should be tuned
  against rather than arithmetic.

### 13.5 Render and the standing battery

- **The card render comes first** (§14 M2): outline vs filled, at 48×66 and 72×100, over dark and
  bright backgrounds, at **true 1×**. Adam judges the look before any layout is built around it.
  🔴 Never render design shots at 2× — it flatters delicate type and has misled this project
  before.
- A **selfcheck scene** and **snapshot scenes** for the table at all four heights, plus the action,
  sizing and confirm levels, Standings and a character detail.
- The whole battery green: `:core:test`, `:desktop:test`, `--selfcheck`, `--snapshot`,
  `--epub-check`, `--music-check`, `--games-check`, `tools/lint.py`, `:phone:assembleDebug`.
  Regenerate `design/shots/` and read the numbers.

---

## 14. Build order — six milestones, a commit after each

**M1 · The shell rule and the retrofit (§3).** `ActivationSource` on the contract; `Shell.focus`
carries it; all six windows implement root-vs-resume; tests for each. Lands first, independent of
everything else, and it is Adam's general rule rather than a Games detail.

**M2 · The card kit (§6) — and the render Adam is waiting on.** *(The render itself needs only
`CardArt` plus `design/render_shots.py`; if Adam wants to judge the look sooner it can be produced
ahead of M1 without disturbing the order.)* `Cards`, `CardArt`, `HandFan`,
`TableLayout`, `HandEval`, `Pots`, `Money`, `Rng`. **Produce the card render before building
anything on top of it.** The side-pot oracle and its committed corpus land here.

**M3 · The Hold'em engine (§5, §12).** Streets, betting, blinds, button, escalation, elimination,
side pots, showdown. Deterministic scripted tests. **No UI at all** — the engine is provably right
before a pixel depends on it.

**M4 · The table (§9, §10).** `CanvasView` at four heights; action / sizing / confirm levels; the
contextual Check-Fold row; deal slide; pacing and skip; tap-to-deal; showdown; hand history;
plane-0 hole cards.

**M5 · The ecology (§7).** `Character`, `Roster`, mood, lives, downtime, retirement, table
selection, the background economy, Standings, `Bankroll`, the entry fee, the Loser Count
scoreboard. `--games-check` lands here and the distributions get tuned against it.

**M6 · Integration and the battery.** Games hub wiring, Settings → Games, notification toggles
(all default **off**), registration in desktop `Main.kt` and phone `ShellService.kt`, selfcheck and
snapshot scenes, docs (`HANDOFF.md` §26, `REMINDER.md`, `WINDOWS.md` precedent notes,
`EXPLOSION.md` §20 row 1 marked built, and **`CLAUDE.md`'s battery list gains `--games-check`**).

**Settings → Games rows:** `Size` (global / 288 / 352 / 416 / 480) · `Font` / `Font size` /
`Font style` · `Confirm` (All actions / Money only / All-in only) · `Bot pace` (0 / 300 / 600 /
1000 / 1500 ms) · `Deal animation` · `Archetype labels` (default off) · **Notifications**: Bot
busted · Tournament won · Character returned — **all default off** (verdict 30), in Games' own
category, never Global (`WINDOWS.md` §1).

---

## 15. Traps and rules for the builder

- 🔴 **Check and Fold are one contextual row and it always confirms** (verdict 33). Exempting
  Check was proposed and rejected. Do not re-propose.
- 🔴 **Double-tap never cashes out.** Back leaves the table running.
- 🔴 **The wrap-end action order matters** — one notch *up* from cursor rest must be harmless, so
  `Cash out` is not last in the list.
- 🔴 **NOW PLAYING is the Music root** (`HANDOFF.md` §24.4 reversed verdict 4). The M1 retrofit
  must not turn Music's Main entry into a browse list.
- 🔴 **Preview is a render, never an activation** (`DESIGN.md` §4.3 rule 1). The switcher's preview
  must not call `onActivate` with any source.
- 🔴 **`MAIN` activation changes the entry point, never the stored state.** Deep state survives;
  navigating back down lands exactly where it was. §9.1 is not weakened.
- **Canvas gets 608 wide**, not 596 — the 12 px rail is List/Document only.
- **Every rect**: `x`/`w` multiples of 4, `y`/`h` multiples of 2. Run `tools/lint.py`.
- **Counter-based RNG, never a persisted stream position.**
- **The background economy only advances while Adam is playing.** Never a schedule, never
  wall-clock.
- **The pacer is a pacing loop, not a timeout.** No `wait_for`, no `timeout=`, anywhere. Bots wait
  for Adam forever.
- **Loud failures.** A provider or engine error rides the one-shot notice on the title. No
  catch-and-swallow.
- **`summary()` stays cheap and side-effect-free** — it is called for every window on every Main
  render.
- **Ink**: Canvas is a lint *warning*, but ≈8 % / ≈10 % is the design target. Investigate a render
  that departs from it rather than shrugging at the warning.
- **Do not reach for the texture cache** (modes 12/13/14) in this build, and **never emit mode 15**
  — its pixels come from a firmware LVGL font chain, so the per-lens oracle would stop being exact.
- **Nothing third-party in the repo**: no engine, no generated card art, no `pokerkit`. The oracle
  lives in a scratch venv and its output is a corpus we own.
- **Never display a character's trait sheet** — only stats observed in hands actually played
  against them, and **never plant a tell** (verdict 37). The read is earned or it is not there.
- **Nothing specific to Adam's setup is baked in** (`WINDOWS.md` §1): every number here that is a
  preference is a Settings row with his value as the default.

---

## 16. Kickoff for the build session

**Read, in order:** `CLAUDE.md` · `REMINDER.md` · `HANDOFF.md` §19–§25 · `WINDOWS.md` (§1 the bar,
§2 the contract, §3 the checklist, §4 the shared machinery, §5 the paid-for traps) · `DESIGN.md`
§1, §2, §4.6, §4.8, §6, §9.1 · **this file** · then
`/home/user/G2CC/games/gamelist.md` §"Card games" for interaction findings only (read-only; never
edit G2CC).

**Verify before starting:** `./gradlew :core:test` (329) · `--selfcheck` (139) · `tools/lint.py`
(0) all green on `main` at `697a062`, so anything that breaks is yours.

**Then M1.** The shell retrofit is the smallest, most independent, most cross-cutting piece and it
touches five existing windows — do it first, on a green tree, with its tests, and commit before
touching a card.

**Open:** only the exact background-economy ratio inside the 2–3 band (§7.5) — and that is a
`--games-check` measurement, not a decision waiting on Adam. Every design question raised in the
2026-09-03/04 session is answered.

**Not in scope:** blackjack and every later card game — the kit is built for them, none of them is
built here. Multiplayer (verdict 3). The texture cache (§2).
