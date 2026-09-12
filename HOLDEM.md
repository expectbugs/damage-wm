# Games · Texas Hold'em — the design record and build plan

**Status: BUILT 2026-09-04** (M1–M6, two review passes and a live session — `HANDOFF.md` §26).
Verdicts first, then the design, then the build order, then **§17: what the build did and where
it departed from this design. Where §17 and an earlier section disagree, §17 is what runs.**

This file wins on Hold'em; `DESIGN.md` on shell design; `overview.md` on hardware facts;
`WINDOWS.md` §1 on what every window owes. Read `CLAUDE.md` → `REMINDER.md` → `HANDOFF.md`
§19–§25 → `WINDOWS.md` → `DESIGN.md` §1, §2, §4.6, §6, §9.1 first.

**Scope.** The **Games** window (hub, shared bankroll, standings) plus **Hold'em** built whole
(Adam's no-staging rule, 2026-09-01: *"completely built to its best state before we move on"*).
Later games (blackjack, hearts, gin) are out of scope; the kit is built for them. Also in scope:
one shell contract change and its retrofit across the existing windows (§3) — six as built,
Settings included.

---

## 1. Verdicts (Adam, 2026-09-03/04)

Numbered so later work can cite them.

| # | verdict |
|---|---|
| 1 | **Games is one window**, individual games selectable inside it. Built one game at a time, properly. |
| 2 | **Hold'em first.** *"Once that is in and working well, the rest will be much quicker and easier to implement… as long as we are careful to design it in such a way that most of what goes into Hold'em is modular and reusable, rather than bespoke."* |
| 3 | **Multiplayer is out** — a maybe-future milestone (the seat model survives for the bots' per-seat projection). |
| 4 | **Decent-casual bots.** *"if it gets boring I can scope better bots as a future project."* |
| 5 | **No exclusive mode.** A card game is active; Music Mode is for passive listening. (Reserved for a future idle game — Paperclips.) |
| 6 | **Look as nice as possible** now the G2CC limits are gone. |
| 7 | 🔴 **Suit colour is carried at CARD scale, not pip scale**: black-suit cards are unfilled outline cards, red-suit cards are filled mid-grey. *"I just see no need to sacrifice color when there's only 2."* On robustness: *"A wireframe-esque card vs a filled mid-grey-level card is easy to tell the difference in color regardless of what is behind the transparency in real life."* |
| 8 | **Card art is code-drawn**, not AI-assisted. |
| 9 | 🔴 **All four heights, properly** — 288 / 352 / 416 / 480, small size tight but not degraded, extra height buys **information density** and not bigger cards. (`WINDOWS.md` §1, Adam 2026-09-02.) |
| 10 | **6-max.** |
| 11 | 🔴 **No rebuys. Sit-and-go, last one standing.** *"Once the players sit down, they are in it until only one person is left. You can leave early and cash out, but you can't rebuild your pot. Half the fun of holdem is those desperate moments where your meager pot is at a huge disadvantage with a high blind against rich opponents, if I can just dump another $200 in that wont matter at all."* |
| 12 | **Shared cash pool across every betting game.** |
| 13 | **$1,000 base bankroll · $1/$2 blinds · $1 minimum · no betting limits.** |
| 14 | **Refill to $1,000 when broke, and it increments a prominently displayed Loser Count.** *"to embarass me for being a loser haha."* |
| 15 | **Broke = can't post the blind**, not literal zero. |
| 16 | **Three tables**: Regular ($200), Big Boy ($1,000), Unlimited (any entry, bots random $1k–$10k in $1k steps, $5/$10 doubling). |
| 17 | **Blind escalation is HAND-based** (Adam: timers were never on the table — a clock would escalate while the glasses sit in their case). **20 hands per level.** SB increments, BB is always 2×SB. |
| 18 | **Big Boy accelerates**: SB +$1 per level until SB passes $10, then doubling — keeps the 500bb deep early game and still finishes. |
| 19 | **Busted bots clear the seat.** 6→5→4→3→2→winner. No re-entry into a running tournament. |
| 20 | **Bots have persistent identity, personality and depth.** *"I don't need the opponents to all be expert players, but I do want them to have depth and feel real."* Traits are dials, mood is state, randomness amplitude is itself a trait. |
| 21 | **Character economics**: `General Wealth` sets starting funds ($500–$10,000) and refill size; **1–12 lives** (skewed low) rolled independently of wealth; short downtime between lives set by mood and moodiness; on the last life, retirement with a **wealth-based recovery** — richer characters return sooner. |
| 22 | **Table choice is confidence-driven, only partly tied to wealth.** *"a bad poor player can make the dumb decision to sit at the Unlimited Table, just make it much less likely."* |
| 23 | **Persistent bot cashflow makes Unlimited the difficulty tier** — effective characters accumulate and buy in larger. *"those harder opponents represent bigger opportunities."* |
| 24 | 🔴 **A visible entry FEE, not a hidden rake, and it applies to Adam too.** 5% of buy-in. *"a visible fee rather than a rake, for everyone including me."* |
| 25 | 🔴 **Adam is just another character** — $1,000 wealth, infinite lives, invisible traits, in the standings. His Loser Count is his lives spent. |
| 26 | **Background economy: 2–3 tournaments per tournament Adam plays, roster ~35.** Throughput from frequency, not concurrency (§7.5). |
| 27 | 🔴 **The background economy advances ONLY while Adam is playing.** Never wall-clock. This is what makes verdict 30 true. |
| 28 | **Watch the bots act**, paced, always interruptible (scroll or tap skips to your decision). Pace is a Settings row. |
| 29 | **Tap to deal.** *"I hate missing the result of the entire hand, that's like missing the finale of the movie."* A showdown stays up until you act. |
| 30 | **No notifications by default.** *"the state does not change while I am away from the game so there's no point."* Toggles in Settings → Games, default off. |
| 31 | **Bet sizing**: a preset ladder, plus a Custom row that opens the §4.8 keyboard. |
| 32 | 🔴 **Confirm on EVERY action**, cursor landing on Cancel, configurable in Settings. *"Scrolling one notch and then tapping once to confirm is not a big deal and worth the safety."* |
| 33 | 🔴 **Check and Fold are the SAME top row** — Check when checking is free, Fold when facing a bet — and **not** exempt from the confirm, because the row changes meaning under a fixed muscle memory. (Exempting Check was proposed and **rejected** — do not re-propose.) |
| 34 | **Last action per player on the table; a full hand history behind a menu row.** |
| 35 | 🔴 **General shell rule — activation source matters.** *"Going to Games from the switcher should auto-resume… Going to Games from Main should present the Games List… This should be true of any window that has multiple base functions (like Reader… Similarly, going to Tmux from Main should present a list of sessions, but Switcher to Tmux should go directly into the last session where I left off)."* **Retrofit every window in the same pass.** |
| 36 | **Side pots: read the authority, do not copy code.** *"we can look up the correct math… rather than reinventing the wheel."* Resolved as: the RULES TEXT and a TEST CORPUS, differential-tested against an MIT reference as an oracle; no third-party code in the repo (`CLAUDE.md` clean-room). |
| 37 | 🔴 **No tells.** *"characters don't get a tell, beyond regular traits/playstyle being noticed during play."* Reads are earned from a character's actual play (§7.7), never planted. |

### Rejected, recorded so they are not re-proposed

- ❌ **Exclusive mode for a card game** (verdict 5).
- ❌ **Exempting Check from the confirm** (verdict 33).
- ❌ **A minimum buy-in at Unlimited** — withdrawn once verdict 11 removed rebuys: short in a
  tournament is a real strategic situation and the stated point of the table.
- ❌ **Cash-game format.** Adam's first framing (2026-09-03) was a cash game with rebuys;
  **reversed by verdict 11 the same evening** — rebuys defeat the escalating blinds and erase
  the short-stack pressure. Buy-in is an entry, not a stack you can top up.
- ❌ **A hidden pot rake** — replaced by the visible entry fee (verdict 24).
- ❌ **Permanent death for bots** — overridden by verdict 21's lives.
- ❌ **Multiple Hold'em tables open at once** (*"I only want one game of holdem going at a time"*).
- ❌ **Wall-clock background simulation** (verdict 27).
- ❌ **Bot tells** — a sizing or timing signal tied to hand strength, scaled by `discipline`.
  Proposed and rejected 2026-09-04 (verdict 37).
- ❌ **A Python card daemon** (OpenSpiel / RLCard) — right for G2CC's 2026-06-28 research and a
  multiplayer server; verdicts 3 and 4 remove both reasons, and pure Kotlin needs no host.
- ❌ **The texture cache (modes 12/13/14) for card art in this build.** See §2.

---

## 2. Facts the design stands on

**Measured, from the repo and the hardware record:**

- **Content geometry.** `Layout` puts content at `y 34, h = H − 64`, x 16, w 608; Canvas gets
  the full 608 (the 12 px rail is List/Document only — `DESIGN.md` §4.6).

  | Size | content | vs 480 |
  |---|---|---|
  | 288 | **608 × 224** | 54 % |
  | 352 | 608 × 288 | 69 % |
  | 416 | 608 × 352 | 85 % |
  | 480 | 608 × 416 | 100 % |

  Width is never the constraint; height always is (five 72-wide cards with 16 px gaps = 424 px).
- **Latency** (`overview.md` §5.2, n=1,488 flushes): `ms ≈ 60 + bytes/50` — PC-direct only;
  the daily path is the phone's, ~70 ms + ~120 ms/KB (`REMINDER.md`, 2026-09-05).
- **Rect budget** 5 mode-3 rects at the 3-deep pipeline (`Geometry.rectBudget`).
- **`CanvasView` exists** (Tmux's live grid was its first user): a `Gray8` and a `Rect`, damage
  tracking taken back from the WM (`DESIGN.md` §4.6: *"a full-frame 608×416 canvas repaint is
  3.8–6.3 KB and ~1.57 fps"*).
- **Ink.** Canvas ink is linted as a **warning**. Modeled for verdict 7's scheme (7 cards
  visible, about half filled): **≈8.1 % of content at 288** (48×66) and **≈10.0 % at 480**
  (72×100). A bright-filled scheme for *all* cards would have been ≈29 %.
- **The G2CC constraint set is gone** (≤4 image tiles, ≤288×129 each, uncompressed gray4, ~1 s
  per tile). Two findings from `/home/user/G2CC/games/gamelist.md` §"Card games (researched
  2026-06-28)" carry forward: **suits must read by SHAPE as well as level**, and **the corner
  index is what shows when cards are fanned**.

**Modeled, labelled as such:**

- A card-table repaint of a few KB lands around **100–140 ms** on the measured curve.
- A 7-card evaluation at a few microseconds puts a 2,000-rollout equity estimate near **30 ms**.
  Unverified on the Pixel 10a.
- Tournament length: an earlier estimate priced this on blind erosion alone (~400 hands at
  Regular, ~2,000 at Big Boy). **Wrong** — in no-limit one all-in ends a player at any level;
  real 6-max sit-and-gos run roughly **60–120 hands**. The blind ladder creates **pressure**,
  not the ending. Distributions come from the harness (§13).

**The texture cache is deliberately NOT used here** (as of the build): nothing shipped emitted
modes 12/13/14 then (the simulator models them, `TextureCache.kt` builds them), and 13/14 have
no stereo variant (cached art locks to zero disparity).
Hold'em paints ordinary pixels through `CanvasView`. *(Since 2026-09-06 text and icons ship
through the cache shell-wide — `CLAUDE.md` latency standards.)*

**Licence posture.** Everything here is ours. External artefacts: published **rules text**
(Robert's Rules of Poker, TDA) and an MIT reference used **only as a test oracle in a scratch
venv**, never vendored (§13).

---

## 3. The shell change: activation source, and the retrofit (verdict 35)

**Verified 2026-09-04:** `Shell.focus()` called `onActivate(services)` with no source, and
`ReaderWindow.onActivate` resumed its level, so Main → Reader and switcher → Reader both landed
in the open book (same for Tmux). Adam's rule, general to the
system:

> **Switcher = resume. Main = the window's root list.** A window with multiple base functions
> presents its chooser when entered from Main, and resumes exactly where it was from the switcher.

### The contract addition

```kotlin
enum class ActivationSource { SWITCHER, MAIN, DEEP_LINK, RESTORE }

// WindowContract.kt
open fun onActivate(ctx: ShellServices, from: ActivationSource) {}
```

- `SWITCHER` — resume the deepest persisted level.
- `MAIN` — the window's **root** level. Persisted deep state is **kept, not discarded**: back
  down lands exactly where it was (the rule changes the *entry point*, never the *stored state*;
  §9.1 is not weakened).
- `DEEP_LINK` — `open(target)` decides.
- `RESTORE` — shell start-up. Resumes, like SWITCHER.

### The retrofit, window by window

| window | root when entered from Main | resumes from switcher |
|---|---|---|
| **Reader** | the shelf (folders / library) | the open book at its offset |
| **Tmux** | the session list | the last session, with mode (flowed/grid, frozen/live) intact |
| **Files** | the locations list | the open directory + cursor |
| **Torrents** | the torrent list root | the open torrent / level |
| **Music** | the Music root (**NOW PLAYING** — `HANDOFF.md` §24.4 reversed verdict 4; do NOT change this) | whatever level was open |
| **Games** | the Games list | the live table |

⚠ **NOW PLAYING is the Music root** — not a chooser, not the queue. ⚠ **Preview is not
activation** (`DESIGN.md` §4.3 rule 1): the switcher's preview render must not call `onActivate`.

**Tests:** for every retrofitted window, `focus(w, from = SWITCHER)` reproduces the persisted
level byte-identically (the §9.1 gate) and `focus(w, from = MAIN)` lands on the root **without
mutating the stored deep state**.

---

## 4. The Games window (`GamesWindow`, id `games`)

**Declares:** List root · needs **nothing** (pure Kotlin, no host — a first) · face **Clear
Sans** (the digit-heaviest surface, `DESIGN.md` §4.5b) · `preferredHeight` global · icon: a
drawn card-pair glyph in the §4.5b set · title `Games`, deepening to `Games · Hold'em` and
`Hold'em · Regular`.

**Summary line for Main** (cached state only):

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

The **bankroll and Loser Count live at Games level** because the pool is shared by every betting
game (verdict 12). **The scoreboard** `$847 · W12 · L3` (cash · tournaments won · Loser Count)
is drawn in the **seven-segment digits** the silent-mode clock uses (`DESIGN.md` §1.5 —
*"drawn, never typed"*, so the locked faces stay four). As built the root has a fourth row,
`Settings · games` (§17.1 #9).

### Standings

A List of every roster character plus Adam, sorted by net worth: name · wealth · a state mark
(playing / between lives / retired). A character's detail level: General Wealth band, lives
remaining, career record, **your head-to-head** (hands, net, knockouts each way) and
**observed stats** (§7.6).

---

## 5. Hold'em: format, tables, economy

### 5.1 Format

**6-max single-table sit-and-go.** Buy in once. **No rebuys, no top-ups, no re-entry.** Play
until one player holds every chip. Chips are dollars 1:1, so a table is conserved: 6 × buy-in
in, the same amount out.

🔴 **Cashing out early** (verdict 11: *"You can leave early and cash out, but you can't rebuild
your pot"*): your stack returns to your bankroll and **the remaining characters play the
tournament out in the background** (§7.5) — Adam's correction to an earlier "the table
dissolves" answer; it keeps the economy conserved and lands the winner's cashflow (verdict 23).
As built, cash-out is fold-then-leave from a settled hand (§17.1 #5).

**Winning it** — the whole prize pool moves to the bankroll, `tournamentsWon++`, the table
closes, Hold'em returns to table-select.

### 5.2 The three tables

| | entry | fee (5%) | blinds | escalation (every 20 hands) | opponents |
|---|---|---|---|---|---|
| **Regular** | $200 | $10 | $1/$2 | SB **+$1** — `1,2,3,4,…` | $200 each |
| **Big Boy** | $1,000 | $50 | $1/$2 | SB **+$1 until SB > $10, then doubling** — `1…10, 20, 40, 80, 160, 320` | $1,000 each |
| **Unlimited** | **any** | 5% | $5/$10 | SB **doubling** — `5, 10, 20, 40, 80, 160` | see below |

- **BB is always 2 × SB.** Minimum bet and minimum raise increment are the BB.
- **No betting limits.** All-in is always available.
- **Unlimited opponents** buy in by wealth and nerve (§7.4), in **$1,000 increments where they
  can afford at least $1,000**, otherwise with whatever they bring. A broke character on a
  heater sitting down with $600 against $8,000 stacks is intended.
- **No minimum buy-in at Unlimited** for anybody, Adam included.
- **Heads-up**: the button posts the small blind, acts first preflop and last postflop.
- Big Boy is unaffordable on a fresh bankroll on purpose (§17.1 #4).

### 5.3 The entry fee (verdict 24)

**5% of buy-in, shown on the buy-in row**: `Regular · $200 + $10`. Rounded up to a **$1 floor**
($1 is the chip denomination). Applies to everyone including Adam. Not optional: it is the
money-supply sink (refills and new characters add, nothing else removes), and without it a
break-even player never refills, so the Loser Count would only measure variance. Refill size is
fixed by General Wealth while the fee scales with stakes, so as the economy inflates the sink
grows and the source does not — measured in §7.6, not arithmetic.

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
- **Broke** (verdict 15) = no table running and cash below the cheapest seat: Unlimited with a
  tiny stack, BB $10 + the $1 fee floor ⇒ **broke is cash < $11**, a derivation, not a magic
  number. (A roster character's threshold is one Regular entry plus fee — §17.1 #6.)
- **Refill** = back to $1,000 (Adam's General Wealth), `loserCount++`. Offered when broke and
  reachable from Bankroll at any time. Confirmed, cursor on Cancel. Above $1,000 it SETS the
  cash down and the confirm names what you lose (§17.2b).
- **Adam is a roster character** (verdict 25): General Wealth $1,000, **lives infinite**,
  traits invisible and emergent from play, in the standings.

---

## 6. The card kit — the reusable half (verdict 2)

`core/…/windows/games/kit/`. **Nothing here knows what Hold'em is.**

| module | what it owns |
|---|---|
| `Cards` | `Rank`, `Suit`, `Card`, `Deck`; a **seeded** shuffle (persistence and testability fall out of it, §11). |
| `CardArt` | one card at any ladder size into a `Gray8`: outline body for black suits, mid-grey filled body for red (verdict 7); rank index, pips, face cards, the back, `holdingMark`. Code-drawn (verdict 8). |
| `HandFan` | N cards with overlap chosen from available width; the 1 px separation stroke. |
| `TableLayout` | the **height ladder** (§9) as a slot allocator; never mentions poker. |
| `Seats` | N seats, occupant = human or bot, and the **per-seat view projection** (a seat is only handed what it can see — a bot needs the same projection, verdict 3). `Seats.strip` rotates by `mySeat`. |
| `HandEval` | 5-from-7 evaluation and comparison. |
| `Pots` | contributions, side-pot construction, uncalled-bet return, odd-chip rule. §12/§13. |
| `ActionLevel` | legal actions → a shell level, with the confirm policy and rest positions applied. |
| `Money` | formatting; the seven-segment scoreboard renderer; `chipStack`. |
| `Bankroll` | the shared pool, Loser Count, tournaments won. Persisted and LWW-synced. |
| `Roster` | the ecology (§7) behind one call: *"give me five opponents for this table."* |

**Bespoke to Hold'em:** betting rounds and street order, blind/button rotation and escalation,
the bot decision policy (§8), the table composition (§9). The engine asks the roster for
opponents and never learns the ecology exists.

---

## 6b. File map

```
core/src/main/kotlin/wm/damage/core/
  shell/WindowContract.kt   + ActivationSource, onActivate(ctx, from), contentPlanes   §3, §17.1
  shell/Shell.kt            focus(w, from) — switcher / Main / deep link / restore
  windows/games/GamesWindow.kt                                                        §4
  windows/games/kit/        Cards · CardArt · HandFan · TableLayout · Seats · HandEval ·
                            Pots · ActionLevel · Money · Bankroll · Rng               §6, §9, §11.2, §12
  windows/games/roster/     Character · Mood · Roster · Background                    §7
  windows/games/holdem/     HoldemTable · HoldemRules · HoldemBot · HoldemView ·
                            HoldemLevels                                              §5.2, §12, §8, §9.2, §10
desktop/src/main/kotlin/…/Main.kt        registration + --games-check                 §13.4
phone/src/main/kotlin/…/ShellService.kt  registration
core/src/test/…/games/                   engine, oracle, persistence, activation      §13
core/src/test/resources/holdem/sidepots.json   the corpus we own                      §13.2
```

`holdem/` is the only package that knows what Hold'em is — the seam that makes blackjack and
the trick-takers cheap later (verdict 2).

## 7. The ecology (verdicts 20–23, 26, 27)

### 7.1 A character sheet has two halves

**Behavioural traits — nine dials, fixed for life, never displayed:**

| trait | controls |
|---|---|
| `tightness` | baseline hand-selection threshold |
| `aggression` | bet/raise vs call/check when in a pot |
| `bluffFreq` | how often they represent what they do not have |
| `discipline` | Adam's *fatigue resistance* — how far pressure moves them off baseline |
| `moodiness` | mood swing amplitude and decay rate |
| `tiltSign` | **signed** — loose-aggressive tilt or tight-passive tilt |
| `stackCourage` | short-stack response: correct push-fold, or folding toward the felt |
| `observance` | whether they adapt to opponents at all |
| `consistency` | **per-decision noise amplitude** — an erratic player is *reliably* erratic, a rock reliably a rock |

**Circumstances:** `generalWealth` ($500–$10,000, **skewed low**) · `livesTotal` (1–12, **skewed
low**, rolled **independently** of wealth) · `livesLeft` · `bankroll` · `careerRecord` · `state`
(playing / between-lives / retired) · `returnsAt`. Both distributions are **power-law-ish, not
uniform** (uniform wealth would average $5,250 and empty Regular). Lives give a cast: 1–2 lives
are extras (most characters), 3–6 regulars, 8–12 institutions.

### 7.2 Mood is state, with two readers

**One** mood value per character: in-game it is tilt (scaled by `moodiness`, directed by
`tiltSign`); between games it is ambition (§7.4). It moves with results and decays toward
baseline. Also `form`, a slower decayed streak carried between tournaments. Traits never drift:
**identity is permanent, mood is not.**

### 7.3 Lives, downtime and retirement (verdict 21)

- Bust the bankroll → spend a life → **short downtime** → refill to `generalWealth` → back in.
- **Downtime is how mood resets**: a high-`moodiness` character comes straight back, still
  tilted; a disciplined one takes a long break and returns at baseline.
- Out of lives → **retired**, with a **wealth-based recovery**: `generalWealth` regenerates a
  stake and they return when they can afford one (replaced a proposed flat game counter), with
  a fresh life allocation and their career intact.

### 7.4 Table selection (verdict 22)

```
affordability gate  →  ambition = baseConfidence(traits) × mood × form  →  tier, with noise
```

Only **partially** tied to wealth: a poor character on a heater buying into Unlimited short and
a wealthy cautious one grinding Regular are both intended; it keeps all three tables populated.

### 7.5 The background economy (verdicts 26, 27)

- **2–3 background tournaments per tournament Adam plays.** Roster ~35 characters.
- **A table Adam cashes out of early is played out too** (§5.1), additional to the ratio.
- 🔴 **Advances only while Adam is playing** — never wall-clock, never on a schedule; this is
  what makes "nothing changes while I am away" true.
- **Run in the gaps between Adam's own decisions**, on the loop (§17.2c: a whole 6-seat
  tournament is 13 ms).
- **Same engine, same rules**, or the difficulty curve stops meaning anything. A **cheap
  decision mode** (far fewer rollouts, plus a generated preflop equity table over the 169 hand
  classes) is permitted for background games only.
- Why not more: at a high ratio only a small fraction of a character's action involves Adam, so
  the ecology sorts on bot-vs-bot fitness and the free adaptive difficulty switches off.

### 7.6 What emerges, and what to watch

**Emergent and wanted:** the tiers self-sort into a real poker room (broke characters leave,
entrants replace them at the bottom, winners migrate up; Unlimited holds sharks *and* whales);
the ecology adapts to Adam's weaknesses with no adaptive AI (the styles that counter his rise);
**`observance` reaches Adam for free** (verdict 25 — his stats are tracked like everyone
else's); the meta cycles rather than converges (poker is frequency-dependent).

**Watch, and print a number for it:**

- **Wealth concentration.** Zero-sum play with elimination walks a closed roster toward one
  winner; the **birth rate is a tuning parameter** — too slow and the room empties, too fast and
  nobody reaches Unlimited.
- **Money supply.** §5.3 argues the fee out-scales the injections. 📏 **MEASURED 2026-09-05,
  10,000 tournaments:** the supply grows 92.9 k → 4.04 M (+387 %) while its per-bucket
  INCREMENT falls from ~$130 k to ~$95 k — the growth rate is falling, not compounding, but over
  any horizon Adam will play the room gets richer. `--games-check` reports the RATE and fails on
  a rising one (the old head-to-tail ratio asserted nothing).

### 7.7 Making it visible

Standings (§4) is the world's face; a character's detail level shows career, head-to-head, and
**observed stats you earned** — VPIP and aggression over hands *you actually played against
them*. ⚠ **Never display the trait sheet.** An `Archetype labels` Settings row may show a
coarse label, default **off**.

---

## 8. The bot decision model (verdict 4: decent-casual)

```
equity      = MonteCarlo(hole, board, opponentCount, rollouts)   // ~2,000 live, far fewer background
potOdds     = toCall / (pot + toCall)
state       = { stackRatio, bbDepth, playersLeft, mood, form, handsThisSession, position }
dials       = modulate(traits, state)
action      = policy(equity, potOdds, dials, legalActions)
```

`modulate` is a small readable function, so *"why did Steve do that"* always has an answer:

```
effTightness = base
  + (1 − discipline) · pressure(bbDepth) · scaredMoney
  − bravado(stackRatio) · headroom
  + tiltSign · moodiness · moodBadness
  + noise(consistency)
```

**Scared money is a feature.** Short stacks tightening is realistic and **theoretically wrong**
(push-fold theory says widen or blind out); modelling the mistake is the point — one dial is
simultaneously personality and skill.

**Determinism:** all randomness is counter-based, keyed by `(tournamentSeed, handNo, seatIdx,
decisionNo)` — a stateless splitmix-class function (§11.2). **No tells** (verdict 37).

---

## 9. Layouts — every level at all four heights (verdict 9)

`TableLayout` owns the exact numbers; every rect passes GEO lint (`x`/`w` multiples of 4,
`y`/`h` multiples of 2). Bands below are the design intent and starting allocation.

### 9.1 The card ladder

| Size | card | board strip (5 cards + gaps) |
|---|---|---|
| 288 | **48 × 66** | 5×48 + 4×12 = 288 |
| 352 | **56 × 78** | 5×56 + 4×12 = 328 |
| 416 | **64 × 88** | 5×64 + 4×16 = 384 |
| 480 | **72 × 100** | 5×72 + 4×16 = 424 |

All grid-legal; all near the real 0.714 card aspect; the board is always centred.

### 9.2 The table level

**Extra height buys information density, never bigger cards.**

| Size | content | bands |
|---|---|---|
| **288** | 608×224 | opponent **strip** (~44): 5 cells, name + stack, folded seats dimmed · board (66) · **one status line** (~22): pot / your stack / to call · your hole cards (66) |
| **352** | 608×288 | opponent strip grows (~66): + **last action** per seat, button and blind level marked · board (78) · status (22) · hole (78) |
| **416** | 608×352 | opponents become a **spatial arc** (~88), vertically staggered: + **your observed stats** per character · board (88) · pot (22) · hole (88) · your line (22) |
| **480** | 608×416 | arc (~96) · board (100) · pot (24) · hole (100) · your line (24) · **street-by-street betting history** (~44) · drawn chip stacks in place of bare numbers |

**Ink** (modeled): ≈8.1 % at 288, ≈10.0 % at 480 — the design target.

**As built** (authority §17): status band and your line from MEASURED ink, the seat strip the
LEFTOVER band with a **COMPACT** form (§17.2e, §17.2f); a holding **mark**, never card backs
(§17.1 #2); round chips in the seat cells only (§17.1 #7); the strip reads from your left
(§17.1 #8).

**Depth.** The board and table sit at the content plane **−1**; **your hole cards come forward
to plane 0** (+4 B per rect) via `contentPlanes` (§17.1 #10), registered in
`Compositor.planes`; disparity on the 4 px ladder (GEO006).

**Motion.** Designed as a deal **slide** (`DESIGN.md` §6.2: mode-9 copy + fill, ease-out, 4 px
grid, interruptible per §6.3); built as a staged reveal on the pacer (§17.1 #3).

### 9.3 Other levels

- **Games root / Standings / Bankroll / character detail / hand history** — List and Document;
  the list kit pans through the 64 px lens band.
- **Action, sizing and confirm levels** — `MenuSurface` at plane 0 over the table (§16.11
  floating context menu, LOOP-ONLY, WINDOW mode only; check its boolean return).

---

## 10. Input grammar, level by level (verdicts 28–33)

The §1 grammar: scroll moves focus, tap descends, double-tap backs, a bare long-press is a no-op.

### 10.1 The table (Canvas)

| table state | scroll | tap |
|---|---|---|
| bots acting (paced) | **skip to your decision** | **skip to your decision** |
| your turn | move a seat-inspect cursor | **open the action level** |
| showdown up | inspect | **deal the next hand** (verdict 29) |

🔴 **Double-tap backs out to the Hold'em level. It NEVER cashes out.** Leaving is an explicit
menu row with a confirm; backing out leaves the table as it is (§9.1).

**Pacing** (verdict 28): each bot action is drawn as it happens, default ~600 ms apart (a
Settings row, `0` = instant); the draw is ~100 ms of that. A **pacing loop, not a timeout** —
the bots wait forever for Adam. After Adam folds the hand **plays out**, skip always available.
Cash out as built: §17.1 #5.

### 10.2 The action level

🔴 **Row 0 is always the give-up row, and it is contextual** (verdict 33):

| facing | row 0 | row 1 | row 2 |
|---|---|---|---|
| no bet | **Check** | `Bet →` | |
| a bet | **Fold** | `Call $X` | `Raise →` |

Not exempt from the confirm. **Wrap-end window actions**, ordered so one notch *up* from rest
is harmless: `… Cash out (confirmed) · Standings · Hand history ⟲ back to row 0`.

### 10.3 The sizing level (verdict 31)

`Min $4` · `1/3 pot $12` · `1/2 pot $18` · `3/4 pot $27` · `Pot $36` · `All-in $340` · `Custom →`

Amounts are computed and shown; `Custom` opens the **§4.8 keyboard** in its numeric
arrangement. (ASCII fractions, not U+2153-class glyphs — §17.1 #1.)

### 10.4 The confirm level (verdict 32)

```
Cancel                       ← cursor rests here
Confirm · Raise to $84       ← one notch, one tap
```

The exact amount is shown here, so the confirm sits *after* sizing. Honest tap cost:

| action | taps | scrolls |
|---|---|---|
| Check | 3 | 1 |
| Call | 3 | 2 |
| Raise | 4 | 3+ |

**Settings → Games → `Confirm`**: `All actions` (default) · `Money only` · `All-in only`.
Because the first tap is always harmless, actions sit in natural poker order (§1.7).

---

## 11. Persistence and the state split (verdict 27, `DESIGN.md` §9.1)

§9.1: persist **mode**, not just position; the WM owns it; it survives WM restart;
`ShellBehaviorTest.switchAwayAndBackIsByteIdentical` is the gate, plus the cross-shell
continuity test (`WINDOWS.md` §3 step 6).

### 11.1 Records (sub-records where per-item state exists — `WINDOWS.md` §1)

| record | holds |
|---|---|
| `window.games` | level path, whether a tournament is live |
| `window.games.bankroll` | `cash`, `loserCount`, `tournamentsWon` |
| `window.games.table` | the live tournament (below; as built also `castStake`, `myCashedOut`, `myFee`) |
| `window.games.char.<id>` | one per character: traits, circumstances, mood, form, career record, lives, retirement state |
| `window.games.world` | `worldSeed`, `gameNo`, roster birth/retirement bookkeeping |

### 11.2 The determinism contract

🔴 **All randomness is counter-based and stateless** — a splitmix-class function of an explicit
key, never a mutable RNG stream whose position has to be saved:

```
deck(handNo)                    = shuffle(derive(tournamentSeed, handNo))
botDecision(seat, decisionNo)   = counterRng(tournamentSeed, handNo, seat, decisionNo)
backgroundGame(n)               = derive(worldSeed, gameNo)
```

⇒ the live table persists only `tournamentSeed`, `handNo`, `street`, the **action log**, stacks,
contributions, button, blind level and folded flags; everything else is *derived*, exactly:
**a resumed hand is the same hand, a resumed bot is the same bot.**

### 11.3 What "always on" means here

Nothing runs in the background: a turn-based game waits, and the bots wait forever (no
timeouts). The background economy advances **only while Adam is playing** (verdict 27), so
leaving the window freezes the world — which is what makes verdict 30 correct.

---

## 12. Rules that must be right (verdict 36)

Read the authority — **Robert's Rules of Poker** and the **TDA rules** — and implement from the
prose. The four places implementations go wrong:

1. 🔴 **Folded players' chips still form pots.** Counting only live players is the classic defect.
2. 🔴 **The uncalled portion of a bet returns before pots are formed.** Bet $100, one caller for
   $30, $70 comes back.
3. **Odd chips on a split** go to the first live player clockwise from the button.
4. **An all-in for less than a full raise does not reopen the betting.**

Side-pot construction:

```
levels = sorted distinct total contributions of all-in players, then the maximum
for each level, from prev:
    pot        = Σ over ALL players of (min(contrib, level) − min(contrib, prev))
    contenders = unfolded players whose contrib ≥ level
    emit SidePot(pot, contenders)
```

🔴 **No third-party code enters the repo** (`CLAUDE.md` clean-room): the rules text and a test
corpus only; the reference implementation is an oracle in a scratch venv (§13.2).

---

## 13. Tests, harnesses, gates

### 13.1 Engine correctness

- **`Pots` property tests**: chips conserved on every hand; the pot balances; the four §12
  items each have a case.
- **`HandEval`**: known rankings; ties and kickers; an exhaustive sweep over a sampled subset.
- **Rules**: blind posting, button rotation, heads-up, minimum raise increments, escalation at
  exactly the 20-hand boundary, elimination and table shrink 6→2.

### 13.2 The side-pot oracle

The `LensOracleTest` pattern applied to poker: `pokerkit` (MIT, University of Toronto CPSRG)
in a **scratch venv**, never vendored, never
imported by shipped code. A generator produces thousands of randomised multi-way all-in
scenarios; both implementations settle them; payouts match **to the chip**; the result is
committed as `core/src/test/resources/holdem/sidepots.json` — a corpus **we own** — so CI needs
no Python and no network. ⚠ Spot-check a generated corpus by hand first (`WINDOWS.md` §5).

### 13.3 Persistence and continuity

- `switchAwayAndBackIsByteIdentical` for the table at all four heights.
- `resumeMidStreetReproducesTheHand` — same hole cards, board and bot decisions.
- Cross-shell continuity: save on shell A → sync → restore on shell B → identical frame (§16.4c).
- **Activation source**, all six windows: `MAIN` lands on root; `SWITCHER` resumes
  byte-identically; `MAIN` does not mutate stored deep state.

### 13.4 `--games-check` (a new desktop harness, alongside `--music-check` / `--epub-check`)

Headless, deterministic, against a **scratch world** — never Adam's saved roster, bankroll or
table. Runs N tournaments and prints: per-character **ROI, VPIP, aggression frequency, average
finish**; **roster differentiation** over a realistic number of sessions; **outcome spread**;
🔴 **the money supply's GROWTH RATE**, early against late — a rising rate FAILS the run (§7.6);
**tournament length distribution** per table; the affordability table (§17.1 #4).

### 13.5 Render and the standing battery

- **The card render comes first** (M2): outline vs filled, at 48×66 and 72×100, over dark and
  bright backgrounds, at **true 1×** (never 2×). `desktop --card-render` → `design/shots/cards/`.
- A **selfcheck scene** and **snapshot scenes** for the table at all four heights, the action /
  sizing / confirm levels, Standings and a character detail.
- The whole battery green (`CLAUDE.md`'s list, `--games-check` included); regenerate
  `design/shots/` and read the numbers.

---

## 14. Build order — six milestones, a commit after each

✅ All six landed 2026-09-04: **M1** the shell rule and the retrofit (§3) — `d3da21d` · **M2**
the card kit (§6), the card render Adam judged first (`CardArt` + `design/render_shots.py`),
the side-pot oracle and its corpus — `fdab57a` · **M3** the Hold'em engine (§5, §12), no UI — `14299c7` · **M4** the table (§9,
§10) + **M5** the ecology (§7), Standings, `Bankroll`, the fee, the scoreboard,
`--games-check` — `b9eb6b6` · **M6** integration and the battery (hub wiring, Settings →
Games, both registrations, scenes, docs — `HANDOFF.md` §26, `REMINDER.md`, `WINDOWS.md`,
`EXPLOSION.md` §20, `CLAUDE.md`'s battery list) in the same wave; then `8aa9910` / `20f01a8` (the two
review passes) and the live-session fixes.

**Settings → Games rows:** `Size` (global / 288 / 352 / 416 / 480) · `Font` / `Font size` /
`Font style` · `Confirm` (All actions / Money only / All-in only) · `Bot pace` (0 / 300 / 600 /
1000 / 1500 ms) · `Deal animation` · `Archetype labels` (default off) · **Notifications**: Bot
busted · Tournament won · Character returned — **all default off** (verdict 30), in Games' own
category, never Global (`WINDOWS.md` §1).

---

## 15. Traps and rules for the builder

- 🔴 **Check and Fold are one contextual row and it always confirms** (verdict 33).
- 🔴 **Double-tap never cashes out.** Back leaves the table running.
- 🔴 **The wrap-end action order matters** — one notch *up* from rest must be harmless, so
  `Cash out` is not last.
- 🔴 **NOW PLAYING is the Music root** (`HANDOFF.md` §24.4). The retrofit must not turn Music's
  Main entry into a browse list.
- 🔴 **Preview is a render, never an activation** (`DESIGN.md` §4.3 rule 1).
- 🔴 **`MAIN` activation changes the entry point, never the stored state.**
- **Canvas gets 608 wide**, not 596 — the 12 px rail is List/Document only.
- **Counter-based RNG, never a persisted stream position.**
- **The background economy only advances while Adam is playing.**
- **The pacer is a pacing loop, not a timeout.** Bots wait for Adam forever.
- **Ink**: Canvas is a lint *warning*, but ≈8 % / ≈10 % is the design target.
- **Never display a character's trait sheet; never plant a tell** (verdict 37).
- **The texture cache was out of scope for this build** (§2).
- The general rules apply unchanged and are not restated: `CLAUDE.md` (GEO lint on every rect,
  never mode 15, loud failures, nothing third-party — no engine, no generated art, no
  `pokerkit`), `WINDOWS.md` §1 (`summary()` cheap; nothing specific to Adam's setup baked in —
  every preference is a Settings row with his value as the default).
- The build's own traps — a menu row that can never succeed, a drawn mark that reads as
  punctuation, lines stacked by a guessed pitch, `onRegistered` is not the restore, an
  unchecked generated corpus, `?: 1` defaults, an asynchrony added to hide an unmeasured cost, a
  vacuous pin — are recorded centrally with their mechanisms in `WINDOWS.md` §5.

---

## 16. Kickoff for the build session

✅ Spent 2026-09-04. A later game (blackjack, hearts, gin) starts the same way: read
`CLAUDE.md` · `REMINDER.md` · `HANDOFF.md` §19–§25 · `WINDOWS.md` §1–§5 · `DESIGN.md` §1, §2,
§4.6, §4.8, §6, §9.1 · this file · `/home/user/G2CC/games/gamelist.md` §"Card games"
(read-only); verify the battery green on `main` first (the Games kickoff was `:core:test` 329 ·
`--selfcheck` 139 · lint 0 at `697a062`); land the smallest cross-cutting piece first, with its
tests, and commit. Open then and now: only the background-economy ratio inside the 2–3 band — a
`--games-check` measurement. Not in scope: later card games, multiplayer (verdict 3), the
texture cache (§2).

---

## 17. What was built (2026-09-04) — and where it departed from the design

M1–M6 in one unattended session, then two review passes (11 + 8 verified defects) and a live
session through the browser replica (13 more). `HANDOFF.md` §26 is the narrative; this section
is the **delta** from §1–§16.

### 17.1 Deviations from the design, with the reason

1. **ASCII fractions in the sizing ladder** — `1/3 pot` / `1/2 pot` / `3/4 pot`: U+2153 and
   friends are outside Latin-1, and an absent glyph is silent tofu. `tools/lint.py` SYM002
   (added here) checks **every Kotlin string literal** against the four faces.
2. **Opponents get a holding MARK, never drawn card backs** — ten lit rectangles carrying one
   bit each lost at every rung. `CardArt.holdingMark` draws two small bars.
3. **The deal "animation" is a staged reveal, not a slide** — the board reveals card by card on
   the pacer (`revealed`); `Settings → Games → Deal animation` off = the board jumps complete.
4. **Big Boy is unaffordable on a fresh bankroll, on purpose** — $500 + $25 fee against $1,000
   is two thirds of everything; a new player is steered to Regular. `--games-check` prints the
   affordability table.
5. **Cash out is FOLD-then-leave, from a settled hand.** "Only between hands" is a state that
   does not exist (the replay posts blinds the moment a seat has chips). The window folds you,
   waits for the settle, then leaves; `HoldemTable.cashOut` **requires** a settled hand and
   rolls it forward itself. Found live — every unit test had called `cashOut` in a state the UI
   cannot reach.
6. **"Broke" for a character is one Regular buy-in plus its fee**, not a token floor: at an $11
   floor, 400 background tournaments left 8 of 35 characters able to afford Regular.
   `Roster.brokeFor` uses `REGULAR.entry + fee`.
7. **No drawn chip stack on the status line; seat stacks are round chips** — a short horizontal
   bar stack beside a number reads as punctuation (one bar an em-dash before "pot", two bars an
   equals sign). `Money.chipStack` draws overlapping round chips (`fillEllipse`, 4 px tall on a
   3 px pitch, minimum two) after the amount, in the seat cells only.
8. **The opponent strip reads from the seat on your left** — raw seat order put the two blinds
   at opposite ends on most hands. `Seats.strip` rotates by `mySeat`, fixed for the tournament.
9. **The Games root has a fourth row, `Settings · games`** — a deep link into the window's own
   Settings category (`SettingsWindow.open("cat:<name>")`, §17.2b).
10. **`contentPlanes` joined the window contract** — `DamageWindow.contentPlanes(content)` lets
    any window name up to `Shell.MAX_WINDOW_PLANES` (4) stereo regions, validated and capped by
    the shell. Games returns the hole-card band, at the table level only.

### 17.2 What the live session changed (2026-09-04, sim transport, browser replica)

Thirteen findings no unit test or offline render caught. Beyond items 5, 7 and 8 above:

- **Spade and club stems were detached** — the stem rose from the baseline by `h/5` while the
  lobes ended at `0.82h`/`0.86h`; integer truncation opened a one-row gap at every rung. The
  stem now starts inside the body; `GamesLive20260904Test` pins every pip at every size as ONE
  4-connected shape.
- **The corner pip was 10 px at 288**, where a spade and a diamond are the same lozenge:
  `Size.pipPx` `0.21×w` → `0.25×w`.
- **A seat's committed chips overlapped the holding mark** (both right-aligned into one box, so
  `$44` read as the next seat's money);
  the amount sits in the left column beside its stack, bounded.
- **Three lens lines did not fit a 64 px lens at 18/15/13 on a 6/30/46 ladder.** Measured ink
  (ascent + descent): 27 px at 18 bold, 25 at 17 bold, 23 at 15, 20 at 13, 17 at 11. The
  standings and buy-in lenses use 17 bold / 13 / 11 at `LENS_1/2/3` = 2/28/48; the third line
  is dropped rather than drawn through the second when a font scale makes it too tight.
- **The hand-history header had no spacer** (the blank line `charLines` and `bankLines` had).
- **The bankroll menu's refill row elided both halves** (`MenuSurface` caps a detail at half the
  248 px box) — now `Refill` + `$1,000 · count 2`.
- **Coming in from Main landed wherever the cursor was left** — `goRoot` resets the root cursor
  and clears `standFrom` (the Reader precedent).
- **"time(s)"** became `once` / `N times`.
- Outside Games: `PathTransportTest.aStartCancelledWhileALoserRollsBackStopsTheCompletedWinner`
  asserted "start() has not completed yet" after a fixed 100 ms wait and failed once on a
  loaded machine; it asserts the ORDER now (rollback finished, or start() not completed).

### 17.2b The second review round, and the second live session (2026-09-04, later)

**Sixteen verified defects and two coverage gaps**, each reproduced then pinned. The five that
matter:

1. 🔴 **Cash out still could not succeed in the commonest spot** — `requestCashOut`
   short-circuited on `contributed == 0` (first to act, preflop, out of the blinds: four hands
   in six) into `HoldemTable.cashOut`, which refuses a live hand. §17.2's defect one branch
   over; the test walks it from that exact spot.
2. 🔴 **A tournament that did not resolve paid the whole prize to every seat still in** —
   `finishPlace(seat) ?: 1` reads "no finishing order = first place", true of every seat at a
   table that stopped early. Both settlements rank the survivors by chips.
3. 🔴 **The bots' lifetime net was the GROSS prize, and zero for a loss** at Adam's table
   (`Background.settle` had `won − stake`). The window persists each seat's `castStake` and
   passes the real net.
4. 🔴 **The build gate had a blind spot** — `tools/lint.py`'s Kotlin walker did not know CHAR
   literals, so `'"'` (which `Journal.kt` writes three times) flipped string/code parity for
   the rest of the line. Fixed and pinned.
5. 🔴 **The hand history was in the wrong person** ("You checks", "You wins $412").
   `Occupant.human` decides: "You check" / "Rex G. checks", "You raise to $37".

The rest: a refill above $1,000 SETS the cash down and the confirm names what you lose;
`Settings · games` opened Settings wherever it was last left (`SettingsWindow` gained a
`cat:<name>` deep link); the history band guessed a 14 px pitch under a face whose ink is 17
(`HIST_H` 52, pitch measured); a pending cash-out was invisible once its notice expired (the
status tail says **tap to leave**); the Custom-size keyboard labelled a bet "Raise"; the button
ring left a 4 px gap where the blind words leave 6; `updatePlanes` logged an illegal depth
region once per FRAME; `replay`'s loop guard exited silently instead of raising;
`HoldemTable.load` accepted a live seat holding no chips; `castStake`'s restore could take the
whole main record down. Two new `--selfcheck` checks measure the type ladders (the lens at
2/28/48, the history band's three lines) against the REAL rasterizer.

### 17.2c The third review cycle (2026-09-04, later still)

**Eleven verified defects and two test-quality fixes.** The one that removed a class:

🔴 **The play-out hand-off was on a background coroutine because of a number nobody had
measured.** Its comment said "seconds of bot decisions"; `--games-check` says a WHOLE 6-seat
tournament from scratch is **13 ms** at `CHEAP_ROLLOUTS`. The coroutine cost two defects — a
new table's `cast` and stakes cleared when the OLD table's settlement landed (the pacer then
stalled on *"this seat has nobody to play it"*), and a shell restart inside the window lost the
prize pool. It runs **on the loop** now, where `maybeBackground` already spends 16–80 ms.

The rest, in the order money matters: `finishTournament` reset the CURRENT table's state
unconditionally (guarded); `Background.playOut` reported *"hand N did not resolve"* for a table
handed over already decided — every cash-out with one opponent left; `HoldemRules.sbAt` doubled
in an unbounded loop (bounded at 32; `HoldemTable.load` refuses a hand number past 100,000);
`HoldemRules.fee` overflowed `Int` on an absurd entry (Long arithmetic); `Character.Career.load`
accepted negative counts that `avgFinish` and `vpip` divide by; the Bankroll document said
"puts you back to $1,000" — it **sets** the cash; the cash-out's notice was overwritten by the
settlement's outcome line (a reorder, once synchronous); `SettingsWindow.open`'s adjust-clearing
duplicated `onActivate`'s four assignments (factored); the decided-table guard used
`winner() != null`, which misses a table every seat has LEFT.

**Test quality:** one new pin asserted a return value that was 0 with and without its fix — a
vacuous pin, rewritten to capture the log line that changed and confirmed to fail without the
fix; one pin's comment claimed to reproduce the hand-off race (milliseconds wide) and now says
what it actually locks.

### 17.2d The 2026-09-05 whole-codebase review

**A cash-out was booked as a total loss.** `finishTournament` recorded Adam's lifetime net as
`prize − myStake`; after a cash-out the chips are already in the bankroll and `winner != seat`,
so leaving with a stack read like busting out — and the line omitted the entry FEE every bot's
net carried since `castStake`. The settlement is `prize + cashedOut − stake − fee`;
`myCashedOut` and `myFee` persist with the table;
`Review20260905Test.cashingOutCreditsTheChipsItTookOffTheTable` fails against the unfixed tree
(−$200 where the truth is −$10). The class, for the third time in this file: **an accounting
line exercised on only one path.**

**In the harness:** `--snapshot`'s showdown scene took one action and waited for a result, which
only worked when that action happened to be *Fold* (with no bet to call the contextual row
checked and the table waited for Adam, correctly, forever), and the world was seeded from the
wall clock, so the scene passed or stalled by luck. It acts every time it is his turn, choosing
the contextual row BY NAME, and both harnesses pin the world seed (`HANDOFF.md` §27.6;
`50-games-showdown` is a real showdown again).

### 17.2e The third whole-codebase review and the live walk (2026-09-04, late)

`HANDOFF.md` §28 is the record; the Games items:

- 🔴 **The pacer stalled after a back-and-return inside one bot's pace** (also after leaving for
  another window, and after a peer's table record replaced the live one): every invalidation
  bumped the generation and left the stale completion to clear `thinking`; that completion,
  seeing the bumped generation, returned without pumping, and the re-entry's pump had been
  refused by the flag. `cancelPacer()` clears both together; a superseded completion never
  touches the flag; a replaced table re-pumps. Two pins drive it through the real shell.
- 🔴 **The status line was cut by the hole-card plane under a per-app scale** — a 24 px
  constant; at 130 % the 33 px line ran into the plane-0 region, whose pixels render unshifted
  (seen live at 480, 416, 352). `TableLayout` takes the status band and your line from MEASURED
  ink (30 and 24 at 100 %: Clear Sans 17 bold inks 25 rows; the seat strip gives up the six
  rows), seat row pitches follow the ink, a seat line that would run past its cell is dropped,
  the holding mark sits under the name's ink. `Review28PaintTest` paints at 130 % on a
  rasterizer whose ink follows the size.
- "1 hands", "1 tournaments", "1 lines" — `HoldemView.plural`.
- Verified live at 480/416/352/288, 100 % and 130 %: the action menu's title, the sizing
  ladder, the Custom keyboard, the showdown line, cash-out and settlement.

### 17.2f The fifth whole-codebase review and the third live walk (2026-09-05)

`HANDOFF.md` §30 is the record; the Games items:

- 🔴 **The seat strip drew every opponent's stack THROUGH the board.** §17.2e left the strip as
  the leftover: at 288 with the global scale at 130 % the status band grows to 38 and the strip
  gets a 120×34 cell while the 15 px name and 14 px stack want 52 rows. The seat FACES step down
  until the two rows fit; where even the smallest pair will not fit the strip goes **COMPACT** —
  one row per seat, the money right-aligned and placed FIRST, the name fitted into what is left
  at the largest face whose name-plus-money measures inside the cell; every line is guarded
  against its cell. ⚠ The first fix dropped the stack row instead (every opponent's money off a
  poker screen); the pin asserts BOTH nothing outside the band and something in the money
  column of every cell.
- **The Games documents (character, bankroll, hand history) sized their line box from
  `metrics().lineHeight`**, one to two rows SHORTER than Clear Sans's ink, with a 17 px bold
  heading in a 13/16 px body, so `Shell.paintDocSlice` chopped descenders on the first scroll. `docLineH(vararg faces)` takes
  the tallest ink; each line is centred in it.
- **The medium seven-segment readout** had its last minute digit at an 84 px offset where the
  pitch is 32 ("10:2 1"). 80 now.
- 🔴 **`TableLayout`'s "the bottom bands give way" was not true** — it floored the seat strip
  and pushed the bottom band past `content`; only `check()` would have caught it and nothing on
  the paint path calls it. The optional bands give way bottom first, loudly; `showsYourLine` /
  `showsHistory` answer from the BAND, so a painter and the allocator cannot disagree.
- Verified live at 288/352/480, 100 % and 130 %: the action menu, the sizing ladder, the Custom
  keyboard, the buy-in confirm.

### 17.2g The showdown sentence with side pots (2026-09-06, `HANDOFF.md` §39)

Adam's hand 8 on the Regular table, replayed from the seed: his 8c 8s, Bea L.'s 10c 9d, board
9s 7s 3h 4h 4s. Bea took the main pot with nines and fours, Adam the larger side pot with eights
and fours, and the glass read "You win … with 9s and 4s": `resultLine` named the seat that
collected the most and described the best hand at the table. Now the best hand leads and every
other paid seat follows as a side-pot clause ("Ann R. wins $90 with 9s and 4s · You take the
$140 side pot"); one pot reads as before. Pinned by
`HoldemEngineTest.theShowdownLineDescribesEachPaidSeatsOwnHand`. Open: the sentence's width at
130 % is unverified on glass; a persisted last-hand result is proposed, not built.

### 17.3 The battery, after the build

*(the Games build's own numbers; the battery at HEAD is `REMINDER.md`)*

`./gradlew :core:test` **430** · `./gradlew :desktop:test` **11** · `desktop --selfcheck` **189**
· `desktop --games-check` · `desktop --snapshot` **13 Games scenes** among 49 · `desktop
--card-render` (`design/shots/cards/`) · `desktop --epub-check` · `desktop --music-check` ·
`python3 tools/lint.py` **21 rules, 0 findings** · `./gradlew :phone:assembleDebug` (APK
**28 / 0.28** staged, §17.2e included).

### 17.4 Still open

- **On-glass verdicts.** Everything above was judged on the byte-exact simulator at true 1× and
  through the browser replica: the card art, the depth of the hole-card plane, the arc stagger
  and the pacing want Adam's eye on the real panel. Both sides deployed 2026-09-04 18:16 (the
  `damage` service on the §17.2e build, APK 28/0.28 staged).
- **The background-economy ratio** is tuned to `--games-check`, not to a season of Adam's play;
  the money-supply curve is the thing to watch.
- **Later games.** The kit is built for blackjack, hearts and gin; none is built.
