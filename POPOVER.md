# POPOVER.md — the popover family, and the Claude path that rides it

**Status: SPEC, designed with Adam 2026-09-11/12. Not built.** Built whole in one pass when Adam
calls it (verdict 7); nothing ships before §8's last milestone. Precedence as always: `overview.md`
on facts, `CLAUDE.md` on rules, `DESIGN.md` on shell design (§4.11 is the design summary; this file
is the record). `HANDOFF.md` §46 is the session record.

## 1. Verdicts (Adam)

| # | verdict | date |
|---|---|---|
| 1 | Claude-session content goes to the glasses as a **popover over the active window**, not a dedicated window | 09-11 |
| 2 | The popover is a **general tool for every window and app**; notifications become one popover type; it replaces the notification spawner's presentation | 09-12 |
| 3 | **No dimming** of the content behind a popover — *"if dimming the background would cause 0.5 s higher latency, that's a nope"* | 09-11 |
| 4 | **Banners / timed popovers OUT** | 09-12 |
| 5 | **Width**: whatever makes sense per app, set by the app at spawn, plus a Settings row | 09-12 |
| 6 | **Stacking: yes** — the top popover always nearest, depth done properly | 09-12 |
| 7 | **Built all at once** — *"it all is"*; no staging | 09-12 |
| 8 | Content: text, lists, images, charts, **selectable options that report back** to the owner | 09-11/12 |
| 9 | Decks and answers **saved to a directory** Files can list and Claude can read back | 09-11 |
| 10 | Image generation is **not the architecture** (accepted after pricing, §2.3); an `image` block only | 09-11 |
| 11 | Any Claude Code session pushes through a **skill + CLI**; the session that has the context writes the content | 09-11 |

Standing constraint behind 3 and 4: *less latency, not more.*

## 2. Facts the design stands on

### 2.1 What exists (code, read 2026-09-11)
- `MenuSurface`: a 248-wide hole at plane 0, cursor on the first row that can act, scroll wraps, tap
  commits, double-tap cancels, a pan window for long menus, save-under and restore, ordinary notices
  defer behind it, an emergency cancels it, `Spec.onClose` runs on the loop after the restore.
- `Notifications`: takes focus after the `DESIGN.md` §4.5 grace (not when clearing a queue); dim on arrival,
  full on focus; a queue with `+N`; coalescing by `thread`; persisted and re-enqueued at start; tap
  = commit + activate + `open(target)`; double-tap = dismiss + read; silent-mode variant display-only
  5 s (a display duration, not a wait — `DESIGN.md` §4.5); emergency: tap dismisses, cancels menus.
- `Shell`: an open overlay suspends the window's paint (`Shell.kt` ≈2973); about thirty
  `menu.open || keyboard.open || switcher.open` gates. `CanvasShift.detect` works on a region.
- `ClaudeOneShot` (Music): a `claude -p` one-shot with the env scrub. `SetupServer` (:7300):
  loopback or Tailscale plus token. The window channel's push slice (`WinService.Push`, `wpush`).
- G2CC `docs/SCOUT.md` (2026-07-09): `g2img`/`chart` fenced blocks, `scout_show.py` over loopback
  HTTP + Bearer, exit codes that never claim "seen", a 560 B / 6-row gate on live frames.

### 2.2 Firmware (source: `zlib_glue.c`, `texture_cache.c`)
- No dim-in-place operation: modes 3/6 fill pixels, 9 copies a rect, 13/14 draw from the cache
  through a LUT. Dimming what is on the panel = repainting it.
- Cached draws are FLAT (plane 0). The LUT `top` (0–15) scales a draw's levels; our recorder already
  dims text with it. A plane change of an existing rect is a per-lens mode-9 copy (+4 B, `DESIGN.md` §8.4).
- Only mode 3 burns a fid; the budget is 5 per flush.

### 2.3 Costs (modeled: the firmware's RLE + zlib 6; phone path ≈ 70 ms + 120 ms/KB)
| what | bytes | time |
|---|---:|---:|
| open a text popover (hole + marks) | few hundred B cached · < 1 KB pixels | ~0.15–0.2 s |
| dismiss (restore the under-pixels) | 1–3 KB | 0.2–0.5 s |
| dim the surroundings (rejected, verdict 3) | 1.5–3.5 KB draws · 2.5–6.3 KB pixels, ×2 | +0.5 s each way |
| a 564×160 photo band | 14 KB | 1.7 s |
| an image OF a text table (564×416) | 8.4 KB | 1.1 s |
| the same table as cached text | ~1.5 KB | 0.3 s |
| an 8-bar chart, flat fills, 564×240 | 2 KB | 0.3 s |
| a plane step of a popover | +4 B per rect | — |

### 2.4 Elsewhere
- Claude Code's Bash tool caps one call at 10 min (default 2) → an answer must be durable.
- Comfortable disparity is UNMEASURED (`REMINDER.md` item 3) → the stack step is a setting.
- Chars per line at 17 px Clear Sans (modeled): ~43 at 400 px · ~52 at 480 · ~64 at 596.

## 3. The surface

### 3.1 One family, one stack
`Popover` is one surface with types **MENU** (today's context menu) · **NOTICE** (today's
notification) · **CONFIRM** · **PEEK** (a window level presented as a popover) · **DECK** (rich
read-only content) · **ASK** (a deck with check/radio/text rows and Done). The switcher and the
keyboard keep their own presentations but sit on the same **modal stack**; `Shell` asks `stack.top()`
where it asked three flags. Reverses `DESIGN.md` §0's "no generic overlay abstraction" (recorded there).

### 3.2 Spawn (`PopoverSpec`)
| field | notes |
|---|---|
| `owner` | window id, `shell`, or `claude` — receives the result |
| `type` | one of §3.1 |
| `title` | optional; short by design (`DESIGN.md` §4.1); the band's height is the title face's measured ink |
| `blocks` | body: `heading` · `text` · `bullets` · `kv` rows · `table` (reflows to kv cards when wider than the box) · `chart` (bars / line / gauge; flat fills; real digits) · `image` (path or blob ref) · `rule` · `page` |
| `rows` | optional: `action` · `check` · `radio` · `text` (opens the `DESIGN.md` §4.8 keyboard; shows the draft) · `done` |
| `widthCap` | per-app default → the app's `Popover width` row → the value passed here; ×4; 248 ≤ w ≤ 608 |
| `persistent` | default by type: NOTICE/DECK/ASK yes · MENU/CONFIRM/PEEK no |
| `thread` · `target` · `urgent` | the `EXPLOSION.md` §16.5 notification signature; `thread` also supersedes an open popover of the same thread |
| `onResult` | loop-only callback (`EXPLOSION.md` §16.11) |

**Not parameters:** height (measured from ink, every band — the `HANDOFF.md` §27 rule), coordinates (the focal
axis, §3.3), a timer (verdict 4), dimming (verdict 3).

### 3.3 Geometry
Inherits `DESIGN.md` §4.5/§4.7: a **hole, not a card** — cleared to level 0, two horizontal rules, no vertical
bars, no fills; ink ≤ 25 %. `w` = min(cap, measured content) rounded to ×4, floor 248; `h` = the sum
of measured bands, capped at the content area less an 8 px margin (400 at height 480, 208 at 288;
the body scrolls beyond); centred on x 320 and on y 242 by its own height. x/w ×4, y/h ×2. The box
plus its disparity stays inside x 16–624. Works at all four heights (`WINDOWS.md` §1).

### 3.4 Depth and stacking
- Content at −1. **The focused popover owns plane 0** (flat: cached text lands there for free).
- A second popover: the first **recedes** by `Popover step` notches with a per-lens mode-9 copy
  (+4 B per rect), the new one takes plane 0. Dismissing the top brings the one beneath back.
- **At most two visible.** Further arrivals queue behind with the `+N` badge on the notice band.
- Global `Depth` moves everything together (`DESIGN.md` §3.1 ladder). `Popover step` default 1 notch (4 px);
  calibrated on glass (§2.4).

### 3.5 Focus and grammar (the ring grammar is unchanged)
- The top of the stack has focus. Scroll moves the row cursor (wrapping onto Done / the actions) or
  scrolls the body. Tap: MENU/CONFIRM commit and close; `check` toggles; `radio` selects; `text`
  opens the keyboard; `done` returns the answer; NOTICE opens its target. Double-tap dismisses
  (NOTICE marks read, as today). Long-press per `DESIGN.md` §1.2.
- Cursor rest: the first row that can act, never an irreversible one, never Done (`DESIGN.md` §1.7).
- **An arrival never steals focus from an interactive popover** (rows or a text draft): it shows
  DIM above and takes focus when the top is dismissed — `DESIGN.md` §4.5's arrival/focus language. The §4.5
  grace applies when a popover appears over a window. An emergency cancels MENU/CONFIRM/PEEK and
  takes focus over DECK/ASK.
- The window's paint stays suspended under an open popover (as today; banners out means no layered
  repaint). Close restores the under-pixels; the compositor's diff ships only what changed.
- Check marks are DRAWN (`Icons`), never glyphs (SYM002 refuses ☑; the Hold'em mark trap).

### 3.6 Rendering
Text runs through the owner's `styledText` (its Font / Size / Style rows); decks from Claude use
the `Claude` Settings category (§6). Images are strips through the existing decoder/`Strips` path,
painted in a LATER flush behind a placeholder line (the first-flush rule, `DESIGN.md` §8.6). Charts
are `Draw` primitives. Every string is linted at spawn (the SYM002 coverage check as a runtime
function): a glyph the locked faces cannot draw refuses the spawn loudly, never silently.

### 3.7 Result protocol
`PopoverResult = dismissed | committed(rowId) | answered(checked: [id], text?) | superseded`,
delivered to `owner.onResult` on the loop after the restore (the `MenuSurface.Spec.onClose` shape).
ASK answers are also written to the history sidecar (§5.4). A new spec with the same `thread`
supersedes the open one (its result is `superseded`).

### 3.8 Persistence and continuity
Persistent types survive a restart (the notification queue's re-enqueue path, generalized). An open
ASK's checked set is live state in a synced record so the phone replica and the PC converge (`EXPLOSION.md` §16.4).
The replica shows every popover (it mirrors the frame); input arrives through `injectInput`.

### 3.9 Latency targets (measured before §8 M6 closes)
| gesture | first flush | target |
|---|---:|---:|
| open, text only | ≤ 1 KB | ≤ 250 ms |
| a notch inside | ≤ 500 B | ≤ 120 ms |
| dismiss | ≤ 3 KB | ≤ 500 ms |
| a plane step (stack) | +4 B/rect | in the same flush |
| an image block | its strips, later flush | stated as the pixels cost |

## 4. Migration of the existing surfaces (inside the one build)
1. **MENU** — `MenuSurface` becomes the MENU type; Files' whole grammar is the regression suite.
2. **NOTICE** — `Notifications.kt` keeps the POLICY (queue, `+N`, coalescing, persistence,
   emergency, silent-mode 5 s variant, mark-read, deep-link tap); only the presentation moves.
   History (the Notices window, `EXPLOSION.md` §7) remains a property of this type.
3. **CONFIRM** — one shape for every irreversible act (title, body, Cancel at rest, the act second);
   replaces the per-window shapes (Tmux TYPE_CONFIRM, Torrents' double confirm, Feed's Mark all read).
4. **PEEK** — a window level may declare `presentation = POPOVER`; the shell honours it when the
   content fits the cap and falls back to FULL when it does not. Long reading stays FULL (§2.4).
5. **DECK / ASK** — new; §5.

## 5. The Claude path

### 5.1 The deck (`deck.v1`, JSON; a `markdown` block for prose)
```json
{"id":"shortlist-0912","thread":"van-shortlist","title":"Shortlist · 9 Prevosts",
 "widthCap":480,"notify":true,
 "blocks":[{"t":"markdown","md":"**#16 1996 Marathon XL40** · $109,500 · TX\n- Series 60 · Allison · batteries 2025"},
           {"t":"chart","kind":"bars","unit":"$k","rows":[["#16",109.5],["#12",89.9]]},
           {"t":"image","path":"/home/user/van/coaches/16-1996-prevost-marathon-xl40/01.jpg"}],
 "rows":[{"t":"check","id":"16","label":"#16 Marathon"},{"t":"text","id":"other","label":"Other…"},{"t":"done"}]}
```
`rows` present ⇒ ASK, else DECK. `notify` raises a NOTICE (thread = the deck's) whose tap opens the
deck. The `markdown` subset: headings, paragraphs, bullets, bold; tables reflow; links render as text.

### 5.2 The CLI — `damage-show` (stdlib Python, `~/.local/bin`, tracked under `tools/`)
`damage-show deck FILE|-` pushes and prints the id and a truthful status · `damage-show ask FILE`
pushes and blocks for the answer, printing it as JSON · `--wait ID` resumes a wait after the harness
cap · `--result ID` reads a stored answer · `damage-show list`. Exit codes (the Scout discipline):
0 shown or answered · 3 accepted, not visible (queued, glasses asleep, no shell attached) · 2 refused
(the lint reason on stderr) · 1 transport. It never claims Adam saw it. Endpoint: `POST /popover`,
`GET /popover/<id>` on the service's :7300 server, same gate as `/setup`; no server-side timeouts.
The phone receives through the window channel (`{"t":"win","win":"popover"}`, push slice) and
caches decks locally, so history survives PC loss; nothing new arrives without the PC (Claude runs
there) and the state line says so.

### 5.3 The skill — `~/.claude/skills/glass/SKILL.md` (a copy tracked under `tools/skill/`)
When to push (results, comparisons, a question with options); the deck format; the truths of the
display (≈52 chars per line at 480 wide, four heights, 16 grays, no colour); images 2–3 at most,
bright and landscape, each priced at seconds; **never invent a number in a chart**; ask through
`damage-show ask` instead of the terminal prompt when Adam is on glass; exit codes mean what they
say. Whether a Claude Code hook can route the built-in question tool to `damage-show ask` is a probe.

### 5.4 History
`~/.damage/decks/<id>.json` and `<id>.answer.json`. Files lists the directory as a location
(`Claude`); opening a deck there spawns it as a DECK/ASK popover (the same renderer). Claude reads
answers back through `--result` or the file.

## 6. Settings
- **Global:** `Popover step` 1 · 2 · 3 notches (default 1) · `Popover width` 248 · 320 · 400 · 480 ·
  608 (the default cap, 480).
- **Per app** (every window's category, automatic like Font/Depth): `Popover width` `global` · the
  five values; the app's spawn value overrides for that popover.
- **Claude** (a category owned by the shell's deck service, not a window): Font · Font size · Font
  style · Depth · `Popover width` · `Notify · deck` (per the notification-toggles rule) ·
  `Ask on glass` on · off.
- Type defaults: MENU 248 · NOTICE 248 · CONFIRM 320 · DECK/ASK the cap · PEEK the window's row.

## 7. Tests, harnesses, gates
`PopoverTest` over a real shell (open / dismiss / supersede / stack / recede / queue / result /
restart) · the truth oracle on every settle already covers the hole-plane cut · `FirstFlushTest`
shapes for open (text first, image later) and a plane step · the selfcheck walk grows a
menu → confirm → deck → ask → stacked-arrival leg · snapshot scenes at 288 and 480 with a stacked
pair · `OracleWalkTest`'s gestures include popovers · the runtime SYM002 check on deck strings ·
the CLI's exit codes against a fake endpoint · `desktop --popover-check` (fixture decks rendered at
four heights, priced) · `python3 tools/lint.py` exits 0 · the batteries per `CLAUDE.md`.

## 8. Build order (one pass; a commit per milestone; battery green after each)
1. **M1** the surface, the modal stack, MENU migrated (Files' grammar green, the gates collapsed).
2. **M2** NOTICE migrated; stacking, recede, the queue; persistence.
3. **M3** CONFIRM and PEEK; the block renderer (markdown, kv, table→cards, charts, images).
4. **M4** ASK: rows, the keyboard text row, the result protocol, the synced checked set, the history
   directory, the Files location and viewer.
5. **M5** the service endpoint, the channel slice, `damage-show`, the skill, the `Claude` category.
6. **M6** the record (this file §9 "as built", `HANDOFF.md`, `IMPLEMENTATION.md`, `WINDOWS.md`) and
   the measured walk (`tools/glassdrive.py`, the per-gesture rows into §3.9).

## 9. Traps for the builder
- The `DESIGN.md` §4.5 grace and its queue-clearing exception; a box that looks the same before and after focus
  inverts the bug.
- A menu already open closes PROPERLY first (restore + onClose) or its pixels become the new box's
  "under" (review 2026-09-01 F1).
- Callbacks are loop-only (`EXPLOSION.md` §16.11); an async completion landing after the user moved on must not
  open over someone else's content (`openMenu`'s owner check).
- Every band from measured ink; the title rule painted before the title (`HANDOFF.md` §30).
- 248 not 250 (×4); the box plus disparity inside 16–624; clear the rect first.
- Drawn marks, not glyphs; SYM002 at spawn; `1/2` not `½`.
- The Bash cap: answers durable, `--wait` resumable; the CLI never says "seen".
- Never rebuild the jar under a running instance; one step per snap in the walk.

## 10. Open, to settle on glass
The disparity step (1 · 2 · 3) · the default deck width by eye · whether a dim arrival above an
ASK reads well · the open/dismiss latency per type against §3.9 · an MCP wrapper for the CLI later
(G2CC deferred it; same UX) · the question-tool hook probe (§5.3).
