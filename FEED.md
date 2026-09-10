# Feed on glass — design + build record (2026-09-09)

**Status: DESIGN SETTLED with Adam 2026-09-09 (fifteen verdicts, §1); BUILT the same day,
M1–M5 (§8 is the as-built record; `HANDOFF.md` §43 the build's own account); ON GLASS the same
evening, and its five findings fixed that night (§8.2); APK 43/0.43 staged; the measured walk
(§3.8 → §8.1) still owed.** The next window after
Games in the `EXPLOSION.md` §20 wow order (Feed + comics, #5). Not a G2CC
conversion — G2CC never had a feed window ("a feed without images was not worth building"),
so `WINDOWS.md` step 2 has nothing to mine; Reader (a reading window: one tap opens, the page's
tap is the actions level), Files (image strips), Torrents (a live list over the channel, the
keyboard, paging) and Music (a window written once against a contract with two hosts and a
deliberate switchback) are the precedents.

The §11 promise, verbatim: *"a Reddit-style feed with endless scroll."* Adam's steer since:
the root is a **source list** (*"I will usually want one at a time. Slashdot content is far
different from a xkcd comic"*), the glasses are used **away from the PC exclusively** (so no
Open-on-PC), and the window must keep working **on the phone alone** wherever plain HTTP
reaches (*"Let's do the phone fallback for everything that we can"*).

Precedence: `overview.md` facts · `CLAUDE.md` rules · `DESIGN.md` shell design · `EXPLOSION.md`
§16 contract · `WINDOWS.md` checklist and latency bar. This file is the window's design
rationale and, after the build, its record; `IMPLEMENTATION.md` → "Feed" will be what runs.

---

## 1. Verdicts (Adam, 2026-09-09)

| # | question | verdict |
|---|---|---|
| 1 | sources day one | **Reddit** (r/popular, anonymous — *"no need for a login"*; a typed subreddit through the keyboard to browse one on demand) · **Slashdot** (the main feed; a section chosen from a list on demand) · **xkcd** · **8-Bit Theater** (the binge archive) · **SMBC**. **Hacker News is OUT.** |
| 2 | other comics | none beyond the three. One Punch Man was asked about and is out on facts (§2.6): the licensed manga has no English on any open source and a manga page prices at ~57 KB per screen, three screens a page. The adapters stay generic (an RSS entry's first image; a WordPress archive walk) so a title later is a config line plus a price check. |
| 3 | root | **the SOURCE LIST**, one source at a time. The merged "river" is rejected. |
| 4 | comments | **in** where reachable: Reddit, flat, per post. Slashdot comments are **not reachable** (§2.2) — the count is shown and the row is dim. |
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
river as root; a 1:1 zoom with sideways panning (each pan is a full ~17 KB repaint); YouTube;
a Reader hand-off (Feed's Document IS the reading view); Open on PC; manga of any kind;
**a headless browser for Reddit or Slashdot** (probed 2026-09-09 with `~/aria/fetch_page.py`:
Reddit answers headless Chromium with "blocked by network security", Slashdot puts it behind a
Cloudflare bot check — the plain feeds are the only path, §2.1–§2.2).

---

## 2. Facts the design stands on (verified 2026-09-09, live, read-only)

### 2.1 Reddit

- `https://www.reddit.com/r/popular/.rss` and `/r/<name>/.rss` answer Atom with 25 entries:
  title, link (the post), `<published>`, author (`/u/…`), a `content` block holding the
  "submitted by … [link] [comments]" HTML (and the selftext HTML for text posts), and a
  `media:thumbnail` for image posts. The JSON listings (`.json`) answer **403** to both a plain
  and a Firefox user agent. old.reddit post pages redirect to login.
- **Rate limit, measured:** the first anonymous feed fetch answered 200; a burst of three within
  seconds answered 200 · 429 · 429; the same URL answered 200 again about two minutes later. A
  15-minute pacer per feed is far inside this, but a *typed* subreddit is an immediate fetch and
  can meet a 429 — the engine paces Reddit to **one request per 60 s per host**, honours
  `Retry-After`, and says `Reddit rate-limited · retry 60 s` on the state line.
- **Comments:** `<post link>/.rss` answers Atom (28 entries seen) with each comment's HTML in
  `content`, flat — nesting and score are not in the feed. Shown flat, newest last.
- Headless Chromium (`fetch_page.py`) is refused outright ("blocked by network security").

### 2.2 Slashdot

- RSS 1.0 (RDF) at `https://rss.slashdot.org/Slashdot/slashdot<Section>`, 15 items each, with
  `slash:section`, `slash:comments` (the count) and `slash:department`; the description carries
  the story summary. Sections that exist (probed, 200): **Main, Apple, AskSlashdot, Developers,
  Games, Hardware, IT, Linux, Mobile, Politics, Science, Search** (Books, Entertainment, Idle,
  Technology, YRO, Meta answer 404). The table lives in `SlashdotFeed.kt` with this lineage.
- Story pages fetch fine with a browser user agent (71 KB HTML); the comment listing in them is
  empty (`<ul id="commentlisting">` holds one hidden `<li>`; the noscript block says to switch
  to the classic discussion system).
- **Comments are loaded client-side** by `D2.ajaxFetchComments` (`a.fsdn.com/sd/comments-minified.js`):
  a POST to `/ajax.pl` with `op=comments_fetch`, `discussion_id`, `threshold`,
  `highlightthresh`, `abbreviated`, `read_comments`, `pieces`, and either `cids` or
  `fetch_all=1`/`fetch_num=N`. Probed with the story page's cookies and referer: a call with a
  `cids` list answers per-comment HTML (so the endpoint works), but the discussion's comment-id
  list is not in the anonymous page (`D2.noshow_comments([])`), `fetch_all=1` without `cids`
  answers an empty 200, the classic `comments.pl` path answers a 403 challenge page, and
  headless Chromium meets the Cloudflare bot check. ⇒ **Slashdot comments are out**; the count
  from the feed is shown. Do not re-probe without a new fact.

### 2.3 xkcd

- `https://xkcd.com/info.0.json` (latest) and `/<n>/info.0.json`: `num`, `title`,
  `safe_title`, `alt` (the hover text — part of the joke, always shown under the strip), `img`,
  `day/month/year`, `link`. Latest on 2026-09-09: **3296**. Strips are typically 740 px wide
  PNGs (272 to 742 tall in the sample); `<name>_2x.png` exists for recent strips (3296: yes) and
  is used when present. Numbers are not contiguous (404 is famously missing) — walk by `num`,
  skip a missing one loudly in the log, never fail the source.

### 2.4 8-Bit Theater (nuklearpower.com)

- WordPress. `wp-json/wp/v2/posts?categories=4&per_page=100&order=asc&orderby=date&_fields=id,date,link,title`
  lists the category ("8-Bit Theater", id 4): **1,313 posts over 14 pages**, oldest first;
  `X-WP-Total` in the headers. `content.rendered` is EMPTY (ComicPress keeps the comic apart).
  Episode titles are `Episode NNN: …`; posts in the category that are not episodes are skipped
  by the walker and counted in the log, never shown as blank pages.
- Each episode page carries exactly one comic image, `<img src="…/comics/8-bit-theater/YYMMDD.(jpg|png)">`,
  plus `rel="prev"`/`rel="next"` links. Episode 001 (2001-03-02) is a 630×878 JPEG; Episode
  1224 (2010-03-20) is a 720×936 palette PNG; 1224's `next` is "the epilogue" (2010-06-01).
  The early JPEG years cost about twice the PNG years on the wire (§2.6).
- The index (1,313 rows: number, title, date, page URL) is fetched **once** and cached
  forever; a page's image URL is read from the page on first open and cached with it.

### 2.5 SMBC

- `https://www.smbc-comics.com/comic/rss` (the `/rss.php` path is a 301 to it): 20 items; the
  description holds the comic `<img>` and a `Hovertext:` paragraph. The comic page has the strip
  as `<img id="cc-comic" title="<hovertext>">` and the bonus panel in a hidden
  `<div id="aftercomic"><img src="…after.png">` — the bonus panel is part of the joke, so it is
  shown under the strip, always, with no button. Today's strip is a 900×1103 RGBA PNG.

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

Inverting a strip changes its bytes by under 1 % (the same runs, reversed) and drops xkcd's ink
from 97 % to 17 %; SMBC and 8BT are colour art on lit backgrounds and stay 70–90 % ink either
way. Nearest-neighbour downsampling saved 8 % on 8BT, not enough to change the picture. After
the first screen a notch ships five 32 px strips (160 px of 416), about 1/2.6 of the screen
figure. **Comics are the heaviest thing the shell has shipped**; the source list, the item lists
and the article Documents are ordinary List/Document costs.

### 2.7 Libraries and runtimes

- **jsoup** (MIT) and **Readability4J** (Apache-2.0, a Kotlin port of Mozilla's Readability on
  jsoup) run on both the JVM and Android — about 700 KB of APK. Article extraction runs wherever
  the engine runs.
- XML through `javax.xml.parsers` (DOM; present on both runtimes; external entities and DTD
  loading OFF). HTTP through `HttpURLConnection` (both runtimes; the `LyricsFetch` precedent),
  behind a `FeedHttp` seam so tests replay today's captured responses.
- Image decode through the existing `ImageDecoder` seam (AWT on the desktop, `BitmapFactory` on
  the phone); scaling, inversion, quantization and strip cutting are core code (the Files
  `fitToWidth` / `appendStrips` shape).

---

## 3. The window (`FeedWindow`, id `feed`)

**Declares:** `needs` = **none** on every host — the engine runs locally on the desktop, and on
the phone the remote engine is preferred with the local one as the fallback, so the window is
available whenever either can run (the staleness line says which serves) · face **Fira Sans**
for every list and for comments, **Alegreya** for the article Document (`EXPLOSION.md` §16.6's
locked per-window defaults) · icon `IconKind.FEED` (theme names `application-rss`,
`feedreader`, `internet-feed-reader`, `internet-news-reader`, `com.gitlab.newsflash` — all in
Papirus-Dark; the drawn fallback is the rss mark: a dot and two arcs, judged at 20 and 56 px
at 1×) · `preferredHeight` from its Size row · title forms (short by design, §4.1): `feed` ·
`<source>` (`popular`, `r/linux`, `slashdot`, `linux`, `xkcd`, `smbc`, `8-bit`) · `article` ·
`comments` · `xkcd 3296` · `8bt 412` · `flagged`.

### 3.1 Grammar — the Reader shape, not the Files shape

Reading is the loop, so **one tap opens**: a source row opens its list, an item row opens the
item. Actions live where Reader puts them — **the Document's tap** opens the item's actions
level, and **the wrap-end row** of every list opens that level's menu (`MenuSurface`). Files'
tap-is-a-menu grammar would cost two taps per article; it stays with windows whose rows have
several equal actions.

```
SOURCES (List, root) ─tap─▶ ITEMS (List, one source) ─tap─▶ ARTICLE (Doc) ─tap─▶ item ACTIONS (List)
   │ wrap-end "Feed" ─▶ root MENU: Refresh all · Flagged (n) · Back to PC (§3.6, when it applies) · Settings
   │                          │ wrap-end "<source>" ─▶ source MENU: Mark all read · Refresh · Browse… · recents · Pin/Unpin
   │                          │ comic items ─tap─▶ COMIC (Doc: strip + text) ─tap─▶ item ACTIONS
   └─ 8-Bit Theater row ─tap─▶ BINGE (Doc, endless) ─tap─▶ binge ACTIONS
   ◀── double-tap backs one level everywhere; the keyboard's own back is §4.8
```

- **SOURCES** (List, root; `ActivationSource.MAIN` lands here, `SWITCHER` resumes). Rows in
  config order: name (bold while it has unread) · `12 new` · the fetch age. Lens: name · one
  detail line — `25 items · 12 unread · fetched 4 m ago`, or the staleness line when the engine
  is behind (`PC unreachable 40 s`, `phone engine · PC down 3 m`, `Reddit rate-limited · retry
  50 s`). Pinned browses (a subreddit or a section the user pinned) are rows here too, after
  the configured ones. The 8-Bit Theater row reads `page 412 of 1225`. Cursor rest: row 0 on
  descent, the row left on ascent.
- **ITEMS** (List; one source). Row: a flag mark at the left when flagged (a drawn glyph,
  judged at 1× beside real titles) · title (fit) · a short tail per kind — Reddit `↑ 45 cmts ·
  3 h · r/linux` (the Atom has no score; the tail is comments and age), Slashdot `59 cmts · 2 h
  · linux`, xkcd `#3296 · Tue`, SMBC the date. Unread at `Level.BODY`, read at `Level.DIM`.
  Lens: title bold, wrapped to the two lens lines through `Draw.lineBelow`, else title + detail
  (domain · author · age). Newest first. **Paging**: the engine keeps up to 500 per source; the
  window asks for pages of 50 with a version cursor and the cursor follows the item's IDENTITY
  (the id, or the wrap-end row) across snapshots — a fetch that inserts rows never moves the row
  under the cursor (the Torrents lesson). Empty = one honest row (`nothing yet` / the state
  line). Wrap-end row `<source>` → the source menu: Mark all read (confirm) · Refresh · Browse…
  (Reddit: the keyboard for a subreddit name; Slashdot: the twelve sections as a list) · up to
  five recent browses as rows · Pin / Unpin (a browsed source becomes a root row).
- **ARTICLE** (Document, Alegreya): title (`HEAD`) · byline (`DIM`: source · author · age · `45
  comments`) · the extracted body as paragraphs, or the feed's own summary when extraction
  yields less than it · inline images as whole-line strips (Reader's shape) while `Images` is
  on, each with a visible placeholder line when it will not decode · a Reddit image post shows
  the image itself; a video post shows one line, `video · not shown`. Opening marks the item
  read (verdict 6). Extraction is an engine call, off-loop: the op cell says `loading article`,
  a failure is a notice (`could not extract · showing the summary`) and the summary shows —
  never a blank page. The newest ten unread items per source are extracted ahead by the PC
  engine on each fetch; the phone engine extracts on demand only (battery). Tap → **item
  ACTIONS** (List): Comments (n) — dim with `not reachable` for Slashdot and with `none` at
  zero · Flag / Unflag · Mark unread · Next item · Back to list. Cursor rests on Comments or,
  when it is dim, one row down (the §30 rule).
- **COMMENTS** (Document, Fira Sans): author · age on a `DIM` line, the comment text wrapped
  under it, entries separated by spacing (no rules, no boxes — §4.2). Fetched on demand, cached
  15 min, the same loading/failure discipline as the article.
- **COMIC** (Document): the strip fit to 596 (never upscaled), inverted per §3.4, quantized per
  `Comic levels`, cut into 32 px strips; the title line above it; xkcd's alt text and SMBC's
  hovertext wrapped under it in Alegreya; SMBC's bonus panel under that. A strip taller than
  the content scrolls like a page. Tap → item ACTIONS (Flag · Mark unread · Next · Back).
- **BINGE** (Document, endless — the 8-Bit Theater row): one virtual document, episode after
  episode: an episode line (`HEAD`: `Episode 412 · <title>`), the strip, the next episode line,
  the next strip. The engine pre-scales the next two episodes; scrolling within a screen of the
  loaded end demands the next, scrolling to the loaded top demands the previous, inserted above
  with `topLine` shifted by the inserted strip count so the view does not jump (the Files
  restore-top shape). Position = the episode number and the strip offset inside it, a
  sub-record (§3.5). Tap → **binge ACTIONS**: Next episode · Previous episode · Jump to
  episode… (the keyboard, digits) · First · Latest · Back. Title `8bt 412`.
- **FLAGGED** (List, from the root menu): flagged items across sources, the item row shape plus
  the source name in the tail.
- **Staleness reaches every level and Main's row** (`WINDOWS.md` §1, the §30 rule): the title
  carries it wherever the level does not paint it, and the summary carries it into Main:
  `12 new · popular 8 · xkcd 1` / `PC unreachable 40 s`.

### 3.2 Heights

Every level at 288 / 352 / 416 / 480 with the Size row (Torrents' shape). Lists pan through the
kit's measured rhythm; Documents show `docContentHeight() / lineH` lines. Strips are 32 px: an
ordinary xkcd (596×218, 7 strips) fits one 288 screen (224 px of content) with room to spare;
an 8BT page (774 px, 25 strips) is about three 288 screens or two 480 screens. Snapshot scenes
cover 288 and 480 for the source list, an item list, an article, a comic and the binge.

### 3.3 Settings → Feed (HostSetting rows; Font / Font size / Font style and Depth are the shell's automatic per-app rows)

| row | options | default | note |
|---|---|---|---|
| Size | global · 288 · 352 · 416 · 480 | global | the standard row |
| Images | on · off | on | verdict 7; article images only — comics are always drawn |
| Line art | auto · never · always | auto | verdict 8; `always` is per-source in effect: the override is exposed as one row per comic source (`xkcd art`, `SMBC art`, `8-Bit art`), each auto/never/always |
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
more than 60 % of pixels sit in the top two bins (a white page) the image is drawn inverted
(ink becomes light), else as-is. xkcd inverts (measured 96.5 % lit → 16.8 %); SMBC and 8BT do
not (colour on colour). `never` / `always` override per comic source. Article images follow
the same rule under `auto` — a diagram inverts, a photo does not.

### 3.5 State and sync (§16.4 — the phone fallback decides this)

Reading state lives in the **shell's synced store**, never inside an engine, so the PC engine
and the phone engine see one truth and the §16.4 note "host-side feed read marks" is
superseded for this window:

- **Main record** (`window.feed`): level, the open source id, the open item id and its doc
  position, list cursors by identity, the five recent browses per kind, pinned browses.
- **`feed.src.<id>`** (one sub-record per source): the read set and the flagged set as item ids,
  bounded by retention, and `lastSeen` (the newest item id announced). **Read marks merge as a
  UNION on live apply** — reading is monotone, so two devices reading different items of one
  source within a sync gap never undo each other; `Mark unread` is the one edit LWW can revert,
  and only while the other device still carries the mark. Flags are LWW. Never an empty blob
  (the §25 #8 tombstone lesson): a source with nothing read reports no record.
- **`feed.binge.<comic>`**: `{episode, strip}` — LWW, the Reader per-book precedent, so the
  archive continues on any device exactly where it stopped.
- **Items themselves are not synced.** Each engine fetches its own; item ids are derived from
  the entry's guid or link (a stable hash), so read marks match across engines.
- Continuity test: read on shell A → sync → shell B shows the same rows dim, the same binge
  page, the same open article at the same line.

### 3.6 The engine, the providers, and the switch (`core/…/windows/feed/`)

**`FeedEngine`** is pure Kotlin and runs anywhere: the source list → per-kind fetchers
(`RedditAtom`, `SlashdotRss`, `Xkcd`, `EightBit`, `Smbc`, and a generic `RssImage`/`Rss` for
later titles) → **`FeedStore`** (files under a data dir: one JSON file per source with its
items, extracted text per item, the 8BT index, and the strip cache as raw 4-bit rows with a
small header) → **`Extract`** (Readability4J → paragraphs; the feed's summary as the floor) →
**`Strips`** (decode through `ImageDecoder`, fit to 596, the §3.4 decision, quantize to N
levels, cut to 32 px). **`FeedHttp`** is the seam: `get(url) → text`, `bytes(url)`, one user
agent, a per-host pacer (Reddit 60 s), `Retry-After` honoured, every refusal said with a
duration. Pacing and liveness only — no timeouts anywhere. The pacer runs at `Fetch` for
feeds and hourly for comics, and never on the shell loop.

**Providers on the §16.10 channel** (`{"t":"win","win":"feed"}`): `FeedService` adapts the
PC's engine to the wire; `RemoteFeedProvider` is the phone's client. Ops: `sources` (the list
with counts and the engine's state line) · `items` (source, version cursor, page) · `article`
(item → text blob) · `comic` (item → strips blob) · `binge` (comic, episode → the episode
line + strips blob; `index` → the episode table) · `comments` (item → text blob) · `refresh`
(source or all) · `browse` (kind + name → a transient source id) · push `changed` (source,
version) so an active list repaints only what changed. Strips ride the blob lane as the packed
4-bit rows the compositor wants, deflated.

**The switch (verdict 15, Music's shape):** `SwitchingFeedProvider` wraps `remote` (preferred)
and `local` (the phone's own engine). The remote serves while its channel is up; when the
channel's staleness passes the `PC loss` threshold, the phone engine starts its pacer and
serves, with a notice `PC unreachable 1 min · fetching on the phone` and the summary naming
the engine (`phone engine`). **Switchback is deliberate**: when the channel is back, a `Back to
PC` row appears in the root menu (detail: `the PC is reachable`); tapping it parks the phone
engine and returns to the remote. The phone engine holds no pacer while parked (§6 rule 4). On
the desktop the local engine is the only provider and nothing switches. What the phone engine
cannot do is exactly what needs the PC and nothing else: nothing in this window does — every
source is plain HTTP (§2) — so the fallback is complete; the one difference is that the phone
extracts articles on demand rather than ahead.

**Announcements** (the notification sources): the engine that is serving decides "new since
`lastSeen`" per source once per fetch; the window raises `notifyInternal("feed", "3 new ·
xkcd 3297 …", appId = "feed", thread = <source id>, target = "src:<id>")` (a single new comic
targets `item:<id>`), gated on the source kind's row. Coalescing per source thread means a
PC standby shell and the phone shell raising the same event replace rather than stack.

**Deep links** (`open(target)`): `src:<id>` → that source's list; `item:<id>` → the item
(the article or the comic) with the level path synthesized so back returns to the list;
`binge:<comic>` → the archive at its saved position; an unresolvable target returns false
(loud, per §16.1).

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

The bar (`WINDOWS.md` §6): a list notch's first flush under ~1 KB and first visible change
under ~250 ms at the median. Targets per gesture, to be measured with `tools/glassdrive.py` +
`tools/journal_report.py` after the build and written into §8:

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

- **Captured fixtures** under `core/src/test/resources/feed/`: today's Reddit popular Atom, a
  post `.rss`, the Slashdot RDF (two sections), xkcd JSON (latest, 1000, a missing number), the
  8BT REST page and an episode page, the SMBC RSS and a comic page, and small PNG/JPEG samples
  — replayed through `FeedHttp`, so every parser is pinned to real bytes and a format change
  shows up as a loud parse refusal in the test, never a quietly empty list.
- **`ScriptedFeed`** (desktop): a deterministic provider with a seeded set of sources and
  items, scripted refresh events (new items arriving under the cursor), a scripted rate-limit,
  a scripted PC loss for the switch. The selfcheck walks: root → a source → an article →
  actions → comments → flag → flagged list → a comic → the binge (next, previous, jump) →
  Browse (keyboard) → Pin → Mark all read (confirm) → the switch and the `Back to PC` row.
- **Core tests**: parser pins per source; the read-set union; the tombstone rule (no empty
  blob); the persistence round-trip (byte-identical frame); the continuity test (§3.5);
  `open(target)` for every form; the identity cursor across an inserting refresh; the §3.4
  decision on the sample images; the strip cutter's byte-exactness against the Python pricing
  script's output for one strip (the same bytes, or the model is wrong).
- **Snapshot scenes** at 288 and 480: source list, an item list, an article, xkcd, the binge.
  `--feed-check`: the engine against the fixtures end to end (parse → store → extract →
  strips), read-only, touching nothing outside a temp dir.
- **Lint** (`tools/lint.py`): every drawn string, the rss mark's rects, the ink budgets.
- **The live walk** before the round is called done (`HANDOFF.md` §33): every level on glass
  through `glassdrive.py`, one step per snap around Mark all read, the numbers into §8.

## 5. Build order — five milestones, a commit after each

1. **M1 engine**: `FeedHttp`, the five fetchers + the generic one, `FeedStore`, `Extract`,
   `Strips`, the pacer, the announcements; fixtures + parser pins; `--feed-check`. Desktop deps:
   jsoup + Readability4J.
2. **M2 window**: `FeedWindow` (every level, four heights, the menus, the keyboard browse,
   Settings rows, notifications, summary, deep links, sub-records with the union rule),
   `ScriptedFeed`, the selfcheck walk, snapshot scenes, `IconKind.FEED` (theme names + the
   drawn mark), desktop registration in both the auto/standby stack and `--host-only`.
3. **M3 channel + switch**: `FeedService` (+ push), `RemoteFeedProvider`,
   `SwitchingFeedProvider` with the `PC loss` row and the `Back to PC` row; tests over a fake
   link.
4. **M4 phone**: the engine hosted in the APK (data dir under the app's files, the pacer under
   the foreground service, `BitmapFactory` through the existing decoder), `ShellService`
   registration, the APK deps; version bump; staged.
5. **M5 record**: this file → build record (as-built numbers, deviations), `IMPLEMENTATION.md`
   "Feed", `HANDOFF.md`, `REMINDER.md`, `WINDOWS.md` (a seventh precedent: the reading grammar +
   the two-engine switch), `EXPLOSION.md` §11/§20 status, memory; jar staged, service
   restarted; then the live walk and §8.

## 6. Traps and rules for the builder

- **Loop-only mutation** through `runOnShell`; generation guards on every completion (a late
  article must not replace the item the user moved to); demand work from `view()` or
  completions, never from a paint (the L1 class); never a provider call on the loop.
- **Cursor by identity** on every live list; a refresh that inserts rows re-resolves it.
- **`saveSubState` never returns an empty blob**; a source with no read marks reports nothing.
- **A restored level below the top loads on the way back** (`MusicWindow.ensureLoaded`).
- **Listeners idempotent; `detach()` in a stack stop's `finally`** (the keeper restart class).
- **Dynamic text everywhere** through `Draw.dynamic` at wrap time: feed titles carry emoji,
  curly quotes, CJK and HTML entities (decode entities BEFORE wrapping; the C1 mojibake lesson).
- **Lint's SYM002 reads every Kotlin literal**: no `½`, no arrows, no glyphs outside the locked
  faces in any string the window might draw.
- **A strip cache is derived state**: `Comic levels` and `Line art` changes re-derive from the
  cached source image; a refetch is never the answer to a settings change.
- **The phone engine never runs while the remote serves**; parked means no pacer, no socket.
- **Reddit's 429 is a normal state, not a defect**: paced, said with the retry time, never
  retried inside the pacing, never a stacked notice.
- **An 8BT post that is not an episode is skipped and counted**, never a blank page; a missing
  xkcd number the same.
- **Ink**: article Documents ≤ 25 %; a comic is the window's call and is said so in the record
  — do not "fix" a 90 % comic by dimming it.
- **Measured vs modeled**: every number in §2.6 and §3.8 is modeled until the walk measures it.
- **Wording**: `CLAUDE.md`'s plain-engineering table in comments, notices and this record.

## 7. Kickoff for the build session

Read, in order: `CLAUDE.md` (loaded), this file whole, `WINDOWS.md` (§1, §5, §6),
`TORRENTS.md` §3–§4 and `core/…/windows/torrents/{TorrentsNet,LocalTorrentsProvider,TorrentsWindow}.kt`
(the channel, paging, the keyboard, the live-list cursor), `ReaderWindow.kt` (the reading
grammar, image strips, per-item sub-records), `FilesWindow.kt`'s Viewer (strip Documents),
`MusicWindow.kt`'s backend switch and `Back to PC library` row, `DESIGN.md` §4.6–§4.8. Then
M1 → M5 in order, the battery green after each, the numbers last.

## 8. As built (2026-09-09) — deviations from §3–§5, the numbers, what waits

Built in one session, a commit per milestone (`a66f2c8` M1 · `95fe6c8` M2 · `3ecba02` M3+M4 ·
M5 = this record). Where the build departed from the plan above, the plan text stands as the
design and this section says what runs:

- **No Readability4J.** Its 1.0.8 pulls jackson-module-kotlin 2.9 and a 2019 Kotlin stdlib into
  the APK; `Extract.kt` is our own scorer on jsoup (MIT), in Readability's spirit and none of its
  code: paragraphs score their parent and grandparent, link-heavy containers are penalised,
  `article`/`main` favoured, and a page under 200 characters of prose yields nothing so the
  feed's own text shows. Live today it turned a Reddit link post into 8 blocks.
- **Strips are fit to the shell's document column, not to 596.** `docContentWidth()` is 564 at
  full width (the column the rail and margins leave); §2.6's numbers are priced at 596 and are
  a few percent high for that reason. The engine caches per width, so a Size change re-derives.
- **Line art is three per-comic rows** (`xkcd art`, `SMBC art`, `8-Bit art`: auto / never /
  always) with no global row — the global one would have said nothing the three do not.
  Article images always follow the automatic rule.
- **Slashdot comments are out** exactly as §2.2 found: the row is dim, `not reachable · N on
  the site`. Reddit's are flat per post.
- **The phone engine adopts the PC's list.** `SourceStatus.cfg` carries each configured source
  over the channel and `FeedEngine.adopt` takes on the ones the phone lacks, so a source added in
  `config.json` reaches the fallback; nothing is ever removed on the phone.
- **Over the channel a strip is deflate(packed nibbles)** with its dimensions in the answer's
  data — the compositor's own bytes, no JSON of a byte array. The tests pin them byte-identical.
- **`ScriptedFeed` lives in core's main sources** (the `SimMusicPlayer` precedent) so the core
  tests and both desktop harnesses share one scripted world; its stamps are relative to now so
  the scenes' ages read the same every day.
- **The harness scripts hit the JVM's 64 KB method limit.** `SelfCheck.script` and
  `Snapshot.script` each stopped compiling with one more call in them; the Torrents walk, the
  Feed walk and the Feed scenes are functions of their own now (Games already was). The
  harnesses step to source rows by identity (`FeedWindow.rootRowId()`), never by counting —
  the MAIN entry keeps the root cursor where it was, which is what the first blind version got
  wrong.
- **The oracle walk fails under load.** `OracleWalkTest` reported "the shell did not settle" once
  when `:core:test` ran in the same gradle invocation as `:phone:assembleDebug`; alone it passes
  twice in a row. Run the APK build separately from the batteries.
- **A first sight is a baseline.** A source's first status on a device sets `seen` to its newest
  stamp and announces nothing; notices start from the second fetch, and only behind the row.

**Battery at the end of the build (before §8.2's changes; after them core 521, the rest unchanged):** core **519** tests (the 14 parser/engine tests, 6 window
tests over a real shell, 2 channel/switch tests over a loopback host), desktop **12**,
`--selfcheck` (228 checks; 230 after §8.2) with the Feed walk and its three ink checks (source list 4.7 %, item
list 13.4 %, article 7.6 %), green 3 of 3 once the harness waited for the rows, `--snapshot` with eight Feed scenes at 480 and 288 (57 PNGs), `--feed-check`
over the fixtures and `--feed-check live` against the real sites (all five fetched cleanly,
1,218 pages indexed, xkcd 3296 at 596×218 inverted 16.4 % ink), lint 0. APK 42/0.42 staged; the
PC service restarted onto the same core.

### 8.2 The first evening on glass (Adam, 2026-09-09) — five findings, all changed the same night

Adam installed 0.42 and walked Feed. What he saw, what it was, what runs now:

1. **A Reddit post showed its image and title but not its text.** Reddit lets an image, video
   or gallery post carry a body, and 6 of the 25 popular entries did; the article builder
   dropped the body for every kind but TEXT. Now the poster's own words come FIRST for every
   kind, then the image / the `video · not shown` line / the extracted link under a `from
   <domain>` heading — and the comments view opens with the post's text above the thread.
2. **A Slashdot story showed only the editor's summary.** The RSS description carries no source
   link (its only anchors are share buttons); the story PAGE's `div.body` links the source in
   its prose. The article is now the summary, then `from <domain>` and the source article
   extracted; when the source will not extract, the summary stands and the note says why.
3. **Slashdot comments** — Adam: *"if a way can be found … that would be ideal."* The story
   page, fetched now, renders the top of the thread server-side: 8 bodies of 9 for a 9-comment
   story, 100 of 176 for "Star Trek Turns 60", each in `li#tree_<cid>` with its depth in the
   `commtree` nesting, its title, its `(Score:5, Insightful)` and its author; the ones below
   the threshold and those listed in `D2.noshow_comments([...])` come from the `comments_fetch`
   call §2.2 had already found works once it has ids. (Why the 2026-09-09 afternoon page had
   an empty tree — the same URL, the same user agent — is not known; the probes that morning
   ran minutes after the story posted. Grade S.) `SlashdotRss.parseThread` + `fetchMissing`;
   the `Comments` row is live for Slashdot; comments render `title` (when not `Re:`), `author
   · score · age`, the text, indented by depth.
4. **The reading text was far too big.** The window sized Alegreya at 20; the Reader uses 17.
   17 now, and the per-app `Font size` row (the shell's, `default` = the global) scales it.
5. **Flipping through comics wanted the xkcd homepage's buttons, not a menu.** The comic level
   is a canvas with a bar under the strip — `next · prev · random · first · latest · menu` —
   the ring moving the highlight and a tap pressing it; a strip that fits above the bar rests on
   it at once, a taller one pans, and one notch UP from the top wraps onto the bar (the list
   grammar's wrap-to-end). xkcd flips by NUMBER through the whole archive: `FeedEngine.comicAt`
   fetches any strip on demand (missing numbers skipped, `random` re-drawn), the range from the
   latest known; SMBC and the generic image feeds flip through their list. The archive keeps its
   endless document.

### 8.1 Measured on glass

*(the walk waits on the 0.42 install — `tools/glassdrive.py`, snap before every tap, one step
per snap around Mark all read; then `tools/journal_report.py`'s per-gesture section into the
table §3.8 left blank)*
