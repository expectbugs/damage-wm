# Feed on glass — design + build record (2026-09-09)

**Status: DESIGN SETTLED with Adam 2026-09-09 (fifteen verdicts, §1); BUILT the same day, M1–M5
(§8 as built; `HANDOFF.md` §43 the build's account); ON GLASS that evening, five findings fixed
that night (§8.2); APK 43/0.43 staged; the measured walk (§3.8 → §8.4) still owed.** #5 in the
`EXPLOSION.md` §20 wow order; no G2CC ancestor. Precedents: Reader (one tap opens; the page's tap
is the actions level), Files (image strips), Torrents (a live list over the channel, the keyboard,
paging), Music (one window, two hosts, a deliberate switchback).

The §11 promise, verbatim: *"a Reddit-style feed with endless scroll."* Adam's steer since: the
root is a **source list** (*"I will usually want one at a time. Slashdot content is far different
from a xkcd comic"*), the glasses are used **away from the PC exclusively** (so no Open-on-PC), and
the window keeps working **on the phone alone** wherever plain HTTP reaches (*"Let's do the phone
fallback for everything that we can"*). Where §3–§5 (design) and §8 (as built) differ, §8 is what
runs; `IMPLEMENTATION.md` → "Feed" is the running inventory.

---

## 1. Verdicts (Adam, 2026-09-09)

| # | question | verdict |
|---|---|---|
| 1 | sources day one | **Reddit** (r/popular, anonymous — *"no need for a login"*; a typed subreddit through the keyboard to browse one on demand) · **Slashdot** (the main feed; a section chosen from a list on demand) · **xkcd** · **8-Bit Theater** (the binge archive) · **SMBC**. **Hacker News is OUT.** |
| 2 | other comics | none beyond the three. One Punch Man was asked about and is out on facts (§2.6): the licensed manga has no English on any open source and a manga page prices at ~57 KB per screen, three screens a page. The adapters stay generic (an RSS entry's first image; a WordPress archive walk), so a later title is a config line plus a price check. |
| 3 | root | **the SOURCE LIST**, one source at a time. The merged "river" is rejected. |
| 4 | comments | **in** where reachable: Reddit, flat, per post. Slashdot comments were judged **not reachable** (§2.2) — reversed as built, §8.2 item 3. |
| 5 | read-later flag | **in** — a Flagged list reachable from the root menu. |
| 6 | mark read | **on open**. |
| 7 | article images | **on** by default; a Settings row. |
| 8 | line-art inversion | **automatic** by histogram, with a per-source override row. |
| 9 | comic scale | **fit to width only.** No 1:1 zoom level. |
| 10 | gray levels for sprite art | **16 default**, a `Comic levels` row (16 / 8 / 4). Depth and Size are the standard per-app rows (Depth default `global`). |
| 11 | cadence | 15 min per feed, comics hourly, a Refresh row; the PC fetches regardless of the glass; the glass polls the engine only while Feed is active. |
| 12 | retention | 30 days or 500 items per source, whichever first. |
| 13 | notifications | **default OFF**, per-source-kind rows in Settings → Feed. |
| 14 | YouTube / Reader hand-off / Open on PC | all **OUT**. *"YT is a video service, we don't do video."* Open on PC: *"if I'm at my PC I will just open it with the PC."* |
| 15 | phone fallback | **YES, for everything plain HTTP reaches**: the engine is core Kotlin and runs on the phone whenever the PC is unreachable; switchback is deliberate (§3.6). |

Recorded so it is not re-pitched: Hacker News; a Reddit login or private feed token; the merged
river as root; a 1:1 zoom with sideways panning (each pan is a full ~17 KB repaint); YouTube; a
Reader hand-off (Feed's Document IS the reading view); Open on PC; manga of any kind; **a headless
browser for Reddit or Slashdot** (probed 2026-09-09 with `~/aria/fetch_page.py`: Reddit answers
headless Chromium with "blocked by network security", Slashdot puts it behind a Cloudflare bot
check — the plain feeds are the only path, §2.1–§2.2).

---

## 2. Facts the design stands on (verified 2026-09-09, live, read-only)

### 2.1 Reddit

- `https://www.reddit.com/r/popular/.rss` and `/r/<name>/.rss`: Atom, 25 entries — title, link,
  `<published>`, author (`/u/…`), a `content` block with the "submitted by … [link] [comments]"
  HTML (plus the selftext HTML for text posts), a `media:thumbnail` for image posts. The `.json`
  listings answer **403** to a plain and a Firefox user agent. old.reddit post pages redirect to
  login. Headless Chromium is refused ("blocked by network security").
- **Rate limit, measured:** first anonymous fetch 200; a burst of three within seconds 200 · 429 ·
  429; the same URL 200 again about two minutes later. A 15-minute pacer per feed is far inside
  this, but a *typed* subreddit is an immediate fetch — the engine paces Reddit to **one request
  per 60 s per host**, honours `Retry-After`, and says `Reddit rate-limited · retry 60 s`.
- **Comments:** `<post link>/.rss` answers Atom (28 entries seen), each comment's HTML in
  `content`, flat — no nesting, no score. Shown flat, newest last.

### 2.2 Slashdot

- RSS 1.0 (RDF) at `https://rss.slashdot.org/Slashdot/slashdot<Section>`, 15 items, with
  `slash:section`, `slash:comments` (the count), `slash:department`; the description is the story
  summary. Sections that exist (probed, 200): **Main, Apple, AskSlashdot, Developers, Games,
  Hardware, IT, Linux, Mobile, Politics, Science, Search** (Books, Entertainment, Idle, Technology,
  YRO, Meta answer 404). The table lives in `SlashdotFeed.kt` with this lineage.
- Story pages fetch with a browser user agent (71 KB HTML). That morning `<ul id="commentlisting">`
  held one hidden `<li>`; **that evening the same pages rendered the thread server-side** (§8.2
  item 3). Why the morning answer was empty is not known — grade S.
- Comments load client-side via `D2.ajaxFetchComments` (`a.fsdn.com/sd/comments-minified.js`): a
  POST to `/ajax.pl` with `op=comments_fetch`, `discussion_id`, `threshold`, `highlightthresh`,
  `abbreviated`, `read_comments`, `pieces`, and `cids` or `fetch_all=1`/`fetch_num=N`. Probed with
  the page's cookies and referer: a `cids` call answers per-comment HTML (used by the build for the
  ids in `D2.noshow_comments`); `fetch_all=1` without `cids` answers an empty 200; the classic
  `comments.pl` answers a 403 challenge; headless Chromium meets the Cloudflare bot check. Do not
  re-probe those paths without a new fact.

### 2.3 xkcd

- `https://xkcd.com/info.0.json` (latest) and `/<n>/info.0.json`: `num`, `title`, `safe_title`,
  `alt` (the hover text — part of the joke, always shown under the strip), `img`,
  `day/month/year`, `link`. Latest on 2026-09-09: **3296**. Strips are typically 740 px wide PNGs
  (272 to 742 tall in the sample); `<name>_2x.png` exists for recent strips (3296: yes) and is used
  when present. Numbers are not contiguous (404 is missing) — walk by `num`, skip a missing one
  loudly in the log, never fail the source.

### 2.4 8-Bit Theater (nuklearpower.com)

- WordPress. `wp-json/wp/v2/posts?categories=4&per_page=100&order=asc&orderby=date&_fields=id,date,link,title`
  lists the category (id 4): **1,313 posts over 14 pages**, oldest first; `X-WP-Total` in the
  headers. `content.rendered` is EMPTY (ComicPress keeps the comic apart). Episode titles are
  `Episode NNN: …`; non-episode posts are skipped by the walker and counted in the log.
- Each episode page carries one comic image, `<img src="…/comics/8-bit-theater/YYMMDD.(jpg|png)">`,
  plus `rel="prev"`/`rel="next"`. Episode 001 (2001-03-02) is a 630×878 JPEG; Episode 1224
  (2010-03-20) a 720×936 palette PNG; 1224's `next` is "the epilogue" (2010-06-01). The JPEG years
  cost about twice the PNG years on the wire (§2.6).
- The index (1,313 rows: number, title, date, page URL) is fetched **once** and cached forever; a
  page's image URL is read on first open and cached with it.

### 2.5 SMBC

- `https://www.smbc-comics.com/comic/rss` (`/rss.php` is a 301 to it): 20 items; the description
  holds the comic `<img>` and a `Hovertext:` paragraph. The page has the strip as `<img
  id="cc-comic" title="<hovertext>">` and the bonus panel in a hidden `<div id="aftercomic"><img
  src="…after.png">` — part of the joke, so it is always shown under the strip, no button. Today's
  strip is a 900×1103 RGBA PNG.

### 2.6 Modeled costs (596 wide, 16 levels, no dither, the firmware's RLE, `zlib` 6; timed with the §37 numbers — NOT measured on glass)

| strip | scale | first screen | whole | modeled first paint |
|---|---:|---:|---:|---:|
| xkcd 3296 (typical) | 0.81× | 12.5 KB | 12.5 KB | ~1.6 s |
| xkcd 3200 (simple) | 0.81× | 4.8 KB | 4.8 KB | ~0.6 s |
| xkcd 2000 (tall, 651×742) | 0.92× | 26.6 KB | 43.7 KB | ~3.3 s |
| SMBC 2026-09-09 (900×1103) | 0.66× | 15.4 KB | 27.1 KB | ~1.9 s |
| 8BT ep. 1224 (PNG era), 16 levels | 0.83× | 29.8 KB | 59.2 KB | ~3.7 s |
| 8BT ep. 1224, 8 levels | | 22.7 KB | 43.7 KB | ~2.8 s |
| 8BT ep. 1224, 4 levels | | 17.0 KB | 30.4 KB | ~2.1 s |
| 8BT ep. 001 (JPEG era), 16 levels | 0.95× | 54.1 KB | 108.4 KB | ~6.6 s |
| 8BT ep. 001, 4 levels | | 17.7 KB | 33.6 KB | ~2.2 s |
| manga page (1200×1696), for the record | 0.50× | 57.2 KB | 103.5 KB | ~6.9 s |

Inverting changes bytes by under 1 % and drops xkcd's ink from 97 % to 17 %; SMBC and 8BT are
colour art on lit backgrounds, 70–90 % ink either way. Nearest-neighbour downsampling saved 8 % on
8BT. After the first screen a notch ships five 32 px strips (160 px of 416), about 1/2.6 of the
screen figure. **Comics are the heaviest thing the shell has shipped.** As built, strips are fit
to 564, not 596 (§8.1) — these numbers are a few percent high.

### 2.7 Libraries and runtimes

**jsoup** (MIT) on both runtimes; Readability4J was planned and dropped (§8.1) — `Extract.kt` is
our own scorer on jsoup. XML through `javax.xml.parsers` (DOM; external entities and DTD loading
OFF). HTTP through `HttpURLConnection` (the `LyricsFetch` precedent) behind a `FeedHttp` seam so
tests replay the captured responses. Image decode through the existing `ImageDecoder` seam (AWT /
`BitmapFactory`); scaling, inversion, quantization and strip cutting are core code (the Files
`fitToWidth` / `appendStrips` shape).

---

## 3. The window (`FeedWindow`, id `feed`)

**Declares:** `needs` = **none** on every host — the desktop runs the engine locally; the phone
prefers the remote engine with its own as fallback (the staleness line says which serves) · face
**Fira Sans** for lists and comments, **Alegreya** for the article Document (`EXPLOSION.md` §16.6)
· icon `IconKind.FEED` (theme names `application-rss`, `feedreader`, `internet-feed-reader`,
`internet-news-reader`, `com.gitlab.newsflash` — all in Papirus-Dark; the drawn fallback is the rss
mark, a dot and two arcs, judged at 20 and 56 px at 1×) · `preferredHeight` from its Size row ·
title forms: `feed` · `<source>` (`popular`, `r/linux`, `slashdot`, `linux`, `xkcd`, `smbc`,
`8-bit`) · `article` · `comments` · `xkcd 3296` · `8bt 412` · `flagged`.

### 3.1 Grammar — the Reader shape, not the Files shape

Reading is the loop, so **one tap opens**; actions sit on **the Document's tap** and on **the
wrap-end row** of every list (`MenuSurface`). Files' tap-is-a-menu grammar would cost two taps per
article.

```
SOURCES (List, root) ─tap─▶ ITEMS (List, one source) ─tap─▶ ARTICLE (Doc) ─tap─▶ item ACTIONS (List)
   │ wrap-end "Feed" ─▶ root MENU: Refresh all · Flagged (n) · Back to PC (§3.6, when it applies) · Settings
   │                          │ wrap-end "<source>" ─▶ source MENU: Mark all read · Refresh · Browse… · recents · Pin/Unpin
   │                          │ comic items ─tap─▶ COMIC (Doc: strip + text) ─tap─▶ item ACTIONS
   └─ 8-Bit Theater row ─tap─▶ BINGE (Doc, endless) ─tap─▶ binge ACTIONS
   ◀── double-tap backs one level everywhere; the keyboard's own back is §4.8
```

- **SOURCES** (root; `ActivationSource.MAIN` lands here, `SWITCHER` resumes). Rows in config order:
  name (bold while unread) · `12 new` · fetch age; pinned browses after; the 8-Bit Theater row reads
  `page 412 of 1225`. Lens: `25 items · 12 unread · fetched 4 m ago` or the staleness line (`PC
  unreachable 40 s` / `phone engine · PC down 3 m` / `Reddit rate-limited · retry 50 s`). Cursor:
  row 0 on descent, the row left on ascent.
- **ITEMS** (one source). Row: drawn flag mark when flagged · title (fit) · tail per kind — Reddit
  `↑ 45 cmts · 3 h · r/linux` (the Atom has no score), Slashdot `59 cmts · 2 h · linux`, xkcd
  `#3296 · Tue`, SMBC the date; unread `Level.BODY`, read `Level.DIM`. Lens: title bold on two
  lines (`Draw.lineBelow`), else title + domain · author · age. Newest first. **Paging**: up to 500
  per source in the engine, pages of 50 on a version cursor; the cursor follows the item's IDENTITY
  across snapshots, so an inserting fetch never moves the row under it. Empty = one honest row.
  Source menu: Mark all read (confirm) · Refresh · Browse… (Reddit: keyboard; Slashdot: the twelve
  sections) · up to five recent browses · Pin / Unpin (a browsed source becomes a root row).
- **ARTICLE** (Document, Alegreya): title (`HEAD`) · byline (`DIM`: source · author · age · `45
  comments`) · extracted paragraphs, or the feed's summary when extraction yields less · inline
  images as whole-line strips while `Images` is on (a visible placeholder line when one will not
  decode) · a Reddit image post shows the image, a video post `video · not shown`. Opening marks
  read (verdict 6). Extraction is off-loop: the op cell says `loading article`; a failure is a
  notice (`could not extract · showing the summary`), never a blank page. The PC engine extracts
  the newest ten unread per source ahead; the phone on demand only (battery). Tap → **item
  ACTIONS**: Comments (n) — dim with `none` at zero · Flag / Unflag · Mark unread · Next item · Back
  to list; cursor on Comments or, when dim, one row down (the §30 rule).
- **COMMENTS** (Document, Fira Sans): author · age (`DIM`), the text under it, entries separated by
  spacing (no rules, no boxes — §4.2); on demand, cached 15 min, the article's failure discipline.
- **COMIC**: designed as a Document; **as built a CANVAS with a six-button bar — §8.2 item 5.** The
  strip fit to the document column (never upscaled), inverted per §3.4, quantized per `Comic
  levels`, cut into 32 px strips; the title above; xkcd's alt text / SMBC's hovertext wrapped under
  it in Alegreya; SMBC's bonus panel under that. `menu` → item ACTIONS (Flag · Mark unread · Back to
  strip).
- **BINGE** (Document, endless — the 8-Bit Theater row): episode line (`HEAD`: `Episode 412 ·
  <title>`), strip, next episode line, next strip, one virtual document. The engine pre-scales the
  next two episodes; within a screen of the loaded end the next is demanded, at the loaded top the
  previous, inserted above with `topLine` shifted by the inserted strip count so the view does not
  jump (the Files restore-top shape). Position = episode + strip offset, a sub-record (§3.5). Tap →
  **binge ACTIONS**: Next episode · Previous episode · Jump to episode… (keyboard, digits) · First ·
  Latest · Back. Title `8bt 412`.
- **FLAGGED** (from the root menu): flagged items across sources, the source name in the tail.
- **Staleness reaches every level and Main's row** (`WINDOWS.md` §1): the title carries it where
  the level does not paint it; the summary carries it into Main (`12 new · popular 8 · xkcd 1` /
  `PC unreachable 40 s`).

### 3.2 Heights

Every level at 288 / 352 / 416 / 480 with the Size row (Torrents' shape). Lists pan through the
kit's measured rhythm; Documents show `docContentHeight() / lineH` lines. Strips are 32 px: an
ordinary xkcd (596×218, 7 strips) fits one 288 screen (224 px of content) with room to spare; an
8BT page (774 px, 25 strips) is about three 288 screens or two 480 screens. Snapshot scenes cover
288 and 480 for the source list, an item list, an article, a comic and the binge.

### 3.3 Settings → Feed (HostSetting rows; Font / Font size / Font style and Depth are the shell's automatic per-app rows)

| row | options | default | note |
|---|---|---|---|
| Size | global · 288 · 352 · 416 · 480 | global | the standard row |
| Images | on · off | on | verdict 7; article images only — comics are always drawn |
| xkcd art · SMBC art · 8-Bit art | auto · never · always | auto | verdict 8, one row per comic source (no global row — §8.1); article images always follow the automatic rule |
| Comic levels | 16 · 8 · 4 | 16 | verdict 10; a change re-derives strips from the cached source image, never a refetch |
| Fetch | 5 min · 15 min · 30 min · 60 min | 15 min | feeds; comics check hourly regardless |
| Keep | 7 d · 30 d · 90 d | 30 d | with the 500-per-source cap |
| PC loss | 30 s · 1 min · 5 min | 1 min | the sustained-loss threshold that starts the phone engine (§3.6) |
| Notify · Reddit | off · on | off | verdict 13 |
| Notify · Slashdot | off · on | off | |
| Notify · comics | off · on | off | xkcd + SMBC; a binge archive never notifies |

Nothing of Adam's setup is baked in: the day-one source list is the default when `config.json`
has no `feedSources`, and every default above is a row.

### 3.4 Line art: the automatic decision

Per image, once, cached with the strips: a 16-bin luminance histogram of the decoded image; if
more than 60 % of pixels sit in the top two bins (a white page) the image is drawn inverted (ink
becomes light), else as-is. xkcd inverts (measured 96.5 % lit → 16.8 %); SMBC and 8BT do not.
`never` / `always` override per comic source. Article images follow the same rule under `auto` —
a diagram inverts, a photo does not.

### 3.5 State and sync (§16.4 — the phone fallback decides this)

Reading state lives in the **shell's synced store**, never inside an engine, so both engines see
one truth (§16.4's "host-side feed read marks" note is superseded here):

- **`window.feed`**: level, open source id, open item id + doc position, list cursors by identity,
  the five recent browses per kind, pinned browses.
- **`feed.src.<id>`** (per source): read set + flagged set as item ids (bounded by retention) and
  `lastSeen` (the newest item id announced). **Read marks merge as a UNION on live apply** —
  reading is monotone, so two devices reading different items within a sync gap never undo each
  other; `Mark unread` is the one edit LWW can revert, and only while the other device still
  carries the mark. Flags are LWW. Never an empty blob (the §25 #8 tombstone lesson): a source with
  nothing read reports no record.
- **`feed.binge.<comic>`**: `{episode, strip}`, LWW (the Reader per-book precedent).
- **Items are not synced**; ids derive from the entry's guid or link (a stable hash), so read marks
  match across engines. Continuity test: read on shell A → sync → shell B shows the same rows dim,
  the same binge page, the same article at the same line.

### 3.6 The engine, the providers, and the switch (`core/…/windows/feed/`)

**`FeedEngine`** is pure Kotlin and runs anywhere: per-kind fetchers (`RedditAtom`, `SlashdotRss`,
`Xkcd`, `EightBit`, `Smbc`, a generic `RssImage`/`Rss`) → **`FeedStore`** (one JSON per source
with its items, extracted text per item, the 8BT index, the strip cache as raw 4-bit rows with a
small header) → **`Extract`** (paragraphs; the summary as the floor) → **`Strips`** (decode
through `ImageDecoder`, fit to the column, §3.4, quantize, cut to 32 px). **`FeedHttp`** is the
seam: `get(url) → text`, `bytes(url)`, one user agent, a per-host pacer (Reddit 60 s),
`Retry-After` honoured, every refusal said with a duration; no timeouts. The pacer runs at `Fetch`
for feeds, hourly for comics, never on the shell loop.

**Providers on the §16.10 channel** (`{"t":"win","win":"feed"}`): `FeedService` (PC) and
`RemoteFeedProvider` (phone). Ops: `sources` (counts + state line) · `items` (source, version
cursor, page) · `article` (text blob) · `comic` (strips blob) · `binge` (comic, episode → episode
line + strips blob; `index` → the episode table) · `comments` (text blob) · `refresh` (source or
all) · `browse` (kind + name → a transient source id) · push `changed` (source, version) so an
active list repaints only what changed. Strips ride the blob lane as packed 4-bit rows, deflated.

**The switch (verdict 15, Music's shape):** `SwitchingFeedProvider` wraps `remote` (preferred) and
`local`. Past the `PC loss` threshold the phone engine starts its pacer and serves, with a notice
`PC unreachable 1 min · fetching on the phone` and the summary naming `phone engine`.
**Switchback is deliberate**: a `Back to PC` row (detail `the PC is reachable`) appears in the root
menu when the channel is back; tapping it parks the phone engine (no pacer, no socket). On the
desktop the local engine is the only provider. Every source is plain HTTP (§2), so the fallback is
complete; the one difference is that the phone extracts on demand rather than ahead.

**Announcements**: the serving engine decides "new since `lastSeen`" per source once per fetch;
the window raises `notifyInternal("feed", "3 new · xkcd 3297 …", appId = "feed", thread = <source
id>, target = "src:<id>")` (a single new comic targets `item:<id>`), gated on the source kind's
row; coalescing per thread means a PC standby shell and the phone shell replace rather than stack.

**Deep links** (`open(target)`): `src:<id>` → the source's list; `item:<id>` → the item with the
level path synthesized so back returns to the list; `binge:<comic>` → the archive at its saved
position; an unresolvable target returns false (loud, §16.1).

### 3.7 Config (`~/.damage/config.json`; the standing secrets rule is moot — nothing here is a credential)

```
"feedSources": [
  {"id": "popular",  "kind": "reddit",   "name": "popular",       "sub": "popular"},
  {"id": "slashdot", "kind": "slashdot", "name": "slashdot",      "section": "Main"},
  {"id": "xkcd",     "kind": "xkcd",     "name": "xkcd"},
  {"id": "smbc",     "kind": "smbc",     "name": "SMBC"},
  {"id": "8bt",      "kind": "eightbit", "name": "8-Bit Theater"}
],
"feedUserAgent": "damage-wm/0.1 (personal glasses client)",
"feedDataDir": "~/.damage/feed"
```

The phone takes the source list from the PC when it has ever reached it and keeps it; the same
default list applies when it never has. `kind: "rss"` (+ `url`, optional `image: true`) is the
generic adapter for a later title.

### 3.8 Latency profile — targets, then measured

The bar (`WINDOWS.md` §6): a list notch's first flush under ~1 KB and first visible change under
~250 ms at the median. Targets per gesture, to be measured with `tools/glassdrive.py` +
`tools/journal_report.py` into §8.4:

| gesture | first flush (target) | note |
|---|---:|---|
| source / item / flagged list notch | ≤ 540 B | the kit's slide + one row; two-line lens rows through the cache |
| open a source | one row strip, then the list | rows and lens as cached draws |
| open an article | a text screen, ~2 KB | Alegreya through the cache; the body arrives ahead (extracted on fetch) |
| article notch | ≤ 500 B | a Document shift + one line |
| open an xkcd | 5–13 KB | the heaviest first flush in the system; said honestly in the op cell |
| open SMBC / 8BT | 15–30 KB (JPEG-era 8BT 54 KB at 16 levels) | `Comic levels` 4 halves the 8BT numbers |
| comic / binge notch | 5 strips ≈ 1/2.6 of the screen figure | the shift is detected, the strip fills |

---

## 4. Tests, harnesses, gates

- **Captured fixtures** under `core/src/test/resources/feed/` (Reddit popular Atom, a post `.rss`,
  Slashdot RDF ×2, xkcd JSON — latest, 1000, a missing number — the 8BT REST page + an episode
  page, SMBC RSS + a comic page, small PNG/JPEG samples), replayed through `FeedHttp`; a format
  change is a loud parse refusal, never a quietly empty list.
- **`ScriptedFeed`**: seeded sources and items, scripted refreshes (new items under the cursor), a
  scripted rate-limit, a scripted PC loss. The selfcheck walks root → source → article → actions →
  comments → flag → flagged → a comic → the binge (next, previous, jump) → Browse (keyboard) → Pin
  → Mark all read (confirm) → the switch and the `Back to PC` row.
- **Core tests**: parser pins per source; the read-set union; the tombstone rule; the persistence
  round-trip (byte-identical frame); continuity (§3.5); `open(target)` for every form; the identity
  cursor across an inserting refresh; the §3.4 decision on the samples; the strip cutter's bytes
  against the Python pricing script's output for one strip.
- **Snapshot scenes** at 288 and 480: source list, item list, article, xkcd, binge. `--feed-check`:
  the engine over the fixtures end to end, read-only, in a temp dir; `live` fetches the real sites
  once. **Lint**: every drawn string, the rss mark's rects, the ink budgets.
- **The live walk** before the round is called done (`HANDOFF.md` §33): every level on glass
  through `glassdrive.py`, one step per snap around Mark all read, the numbers into §8.4.

## 5. Build order — five milestones, a commit after each ✅ DONE 2026-09-09 (`a66f2c8` · `95fe6c8` · `3ecba02` · `693c122`, then `fa3747d` for §8.2)

**M1** engine (`FeedHttp`, the fetchers, `FeedStore`, `Extract`, `Strips`, the pacer, the
announcements, fixtures, `--feed-check`) `a66f2c8` · **M2** window (`FeedWindow`, `ScriptedFeed`,
the selfcheck walk, snapshots, `IconKind.FEED`, desktop registration in the auto/standby stack and
`--host-only`) `95fe6c8` · **M3** channel + switch (`FeedService` + push, `RemoteFeedProvider`,
`SwitchingFeedProvider`, the `PC loss` and `Back to PC` rows) with **M4** phone (the engine in the
APK — data dir under the app's files, the pacer under the foreground service, `BitmapFactory`
through the decoder) `3ecba02` · **M5** the record `693c122` · the evening's fixes `fa3747d`.

## 6. Traps and rules for the builder

- **Loop-only mutation** through `runOnShell`; generation guards on every completion (a late
  article must not replace the item the user moved to); demand work from `view()` or completions,
  never from a paint; never a provider call on the loop.
- **Cursor by identity** on every live list; a refresh that inserts rows re-resolves it.
- **`saveSubState` never returns an empty blob**; a source with no read marks reports nothing.
- **A restored level below the top loads on the way back** (`MusicWindow.ensureLoaded`).
- **Listeners idempotent; `detach()` in a stack stop's `finally`** (the keeper restart class).
- **Dynamic text everywhere** through `Draw.dynamic` at wrap time: feed titles carry emoji, curly
  quotes, CJK and HTML entities (decode entities BEFORE wrapping; the C1 mojibake lesson).
- **Lint's SYM002 reads every Kotlin literal**: no `½`, no arrows, no glyphs outside the locked
  faces in any string the window might draw.
- **A strip cache is derived state**: `Comic levels` and line-art changes re-derive from the cached
  source image; a refetch is never the answer to a settings change.
- **The phone engine never runs while the remote serves**; parked means no pacer, no socket.
- **Reddit's 429 is a normal state, not a defect**: paced, said with the retry time, never retried
  inside the pacing, never a stacked notice.
- **An 8BT post that is not an episode is skipped and counted**, never a blank page; a missing xkcd
  number the same.
- **Ink**: article Documents ≤ 25 %; a comic is the window's call and is said so in the record — do
  not "fix" a 90 % comic by dimming it.
- **Measured vs modeled**: every number in §2.6 and §3.8 is modeled until the walk measures it.
- **Wording**: `CLAUDE.md`'s plain-engineering table in comments, notices and this record.

## 7. Kickoff for the POLISH session (the build is done; this replaces the build kickoff)

Read `REMINDER.md`, `HANDOFF.md` §43 (§43.6 is the resume protocol), this file's §1, §3 and §8,
`WINDOWS.md` §5–§6, `IMPLEMENTATION.md` → "Feed". Then: the measured walk (§8.4) before any
change, Adam's verdicts on what he sees, §8.3's list by number, the battery green after each
change.

## 8. As built (2026-09-09) — the record a polish session works from

Commits per milestone: §5. `HANDOFF.md` §43 is the build's own account and §43.6 where the next
session picks up. Where the build departed from §3–§5, the plan text stands as the DESIGN and
this section says what RUNS.

### 8.1 Deviations from the plan

- **No Readability4J.** Its 1.0.8 pulls jackson-module-kotlin 2.9 and a 2019 Kotlin stdlib into
  the APK; `Extract.kt` is our own scorer on jsoup (MIT), in Readability's spirit and none of its
  code: paragraphs score their parent and grandparent, link-heavy containers are penalised,
  `article`/`main` favoured, and a page under 200 characters of prose yields nothing so the feed's
  own text shows.
- **Strips are fit to the shell's document column, not to 596.** `docContentWidth()` is 564 at
  full width; §2.6's numbers are a few percent high. The engine caches strips per width, levels
  and policy.
- **Line art is three per-comic rows** (`xkcd art`, `SMBC art`, `8-Bit art`: auto / never /
  always), no global row. Article images always follow the automatic rule.
- **Four of the evening's findings changed the plan** (§8.2): the comic level is a CANVAS with a
  bar (item 5; the 8-Bit archive keeps the endless Document); Slashdot comments are IN (item 3,
  reversing §2.2); a Reddit article leads with the poster's own words (item 1); the reading text
  is Alegreya 17, not 20 (item 4).
- **The phone engine adopts the PC's list.** `SourceStatus.cfg` carries each configured source over
  the channel and `FeedEngine.adopt` takes on the ones the phone lacks; nothing is ever removed on
  the phone.
- **Over the channel a strip is deflate(packed nibbles)** with its dimensions in the answer's data
  — the compositor's own bytes, no JSON of a byte array; the tests pin them byte-identical.
- **`ScriptedFeed` lives in core's main sources** (the `SimMusicPlayer` precedent) so the core tests
  and both desktop harnesses share one scripted world; its stamps are relative to now.
- **A first sight is a baseline.** A source's first status on a device sets `seen` to its newest
  stamp and announces nothing; notices start from the second fetch, and only behind the row.

### 8.2 The first evening on glass (Adam, 2026-09-09) — five findings, all changed the same night

Adam installed 0.42 and walked Feed.

1. **A Reddit post showed its image and title but not its text.** An image, video or gallery post
   can carry a body (6 of 25 popular entries did); the builder dropped it for every kind but TEXT.
   Now the poster's own words come FIRST for every kind, then the image / `video · not shown` / the
   extracted link under a `from <domain>` heading; the comments view opens with the post's text
   above the thread.
2. **A Slashdot story showed only the editor's summary.** The RSS description has no source link
   (its only anchors are share buttons); the story PAGE's `div.body` links it. Now: the summary,
   then `from <domain>` and the source article extracted (`SlashdotRss.sourceFrom`); when the
   source will not extract (the NYT answers 403) the summary stands, the heading names the source
   and the note says why.
3. **Slashdot comments** — Adam: *"if a way can be found … that would be ideal."* The story page,
   fetched that evening, renders the top of the thread server-side (8 bodies of 9 for a 9-comment
   story; 100 of 176 for "Star Trek Turns 60"), each in `li#tree_<cid>` with its depth in the
   `commtree` nesting, title, `(Score:5, Insightful)` and author; those below the threshold and
   those in `D2.noshow_comments([...])` come from the `comments_fetch` POST §2.2 found
   (`parseThread` + `fetchMissing`, the one POST in the engine). Rendered: `title` (when not `Re:`),
   `author · score · age`, the text, indented by depth. 🟡 **Why the same URL answered with an
   EMPTY tree that morning** (the §2.2 probes ran minutes after the story posted, same user agent)
   **is not known — grade S**; a story showing no comments while the feed counts some is the shape
   to look for.
4. **The reading text was far too big.** Alegreya at 20; the Reader uses 17. 17 now; the per-app
   `Font size` row (`default` = the global) scales it.
5. **Flipping through comics wanted the xkcd homepage's buttons, not a menu.** The comic level is a
   canvas with a bar under the strip — `next · prev · random · first · latest · menu` — the ring
   moving the highlight, a tap pressing; a strip that fits above the bar rests on it at once, a
   taller one pans, and one notch UP from the top wraps onto the bar (the list grammar's
   wrap-to-end, `DESIGN.md` §4.6). xkcd flips by NUMBER through the whole archive
   (`FeedEngine.comicAt` fetches any strip on demand, missing numbers skipped, `random` re-drawn,
   the range from `comicRange`); SMBC and the generic image feeds flip through their list. The
   bar's focus stays where it was when the menu opened. The first canvas painted over the list it
   replaced (the snapshot showed it) — a canvas clears its rect first (`WINDOWS.md` §5).

### 8.3 Known limits and rough edges — the polish session's list

Nothing here is a silent failure; each says what it does. In the order a reader on glass meets
them:

1. **Reddit comments right after a fetch say `reddit rate-limited · retry N s`.** One request a
   minute per host, and a comments fetch is a Reddit request like the listing fetch before it. A
   smarter budget (a comments request on its own slot) is the first thing to price.
2. **Reddit comments are flat.** The post's `.rss` carries no nesting and no score; old.reddit's
   threaded HTML redirects to login and the JSON refuses non-browser clients (§2.1). Only a login
   would change this and Adam said no login.
3. **A Slashdot story whose page came without its tree shows `no comments yet`** although the feed
   counts some (item 3's grade-S mystery). The honest line for that state — `the story page
   carried no comments this time · N on the site` — is not written yet.
4. **A source behind a paywall or bot check shows the summary** with `from <domain>` and the HTTP
   status (the NYT, 403). Nothing more without an account; no archive service is consulted.
5. **SMBC and a generic image feed flip within their fetched list** (20 for SMBC); `first` is the
   oldest fetched, not the oldest ever. An SMBC archive walk (its site is not WordPress) is a
   separate adapter, if Adam wants an SMBC binge.
6. **A comic opened by number and left open does not restore after a restart** when it is outside
   the source's list: the restore re-opens through the list and says `that item is gone`.
   Persisting the number (`openItemNum`) and re-fetching it is the fix.
7. **The 8-Bit Theater archive has no bar** — Adam asked for the bar "for comics like xkcd"; the
   archive is an endless document with its actions on tap. Ask before adding one.
8. **The phone fallback is tested over a loopback host, not over Tailscale with the service
   stopped.** One deliberate try, with the journal read after, is owed.
9. **`Notify` rows are off** (verdict 13) and untried on glass; the first-sight baseline means the
   first fetch after turning a row on announces nothing.
10. **The measured walk is owed** (§8.4): every number in §2.6 and §3.8 is modeled.

### 8.4 Measured on glass

*(the walk waits — `tools/glassdrive.py`, snap before every tap, one step per snap around Mark all
read; then `tools/journal_report.py`'s per-gesture section into the table §3.8 left blank; the
comic canvas is the case to watch: a bar highlight change is a small repaint, a pan is a detected
translation, a flip is a whole strip)*
