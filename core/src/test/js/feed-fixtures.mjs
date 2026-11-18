// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-3, package JF: Feed's descriptions, pages, articles, comments, comics, the binge and the record in the shapes
// the page assumes from the S1-3 spec (§3.1–§3.3; the assumptions are listed in the stage's report for the Kotlin
// lane), for the node tests and the look harness. Every source name, title, author, comment and picture here is made
// up; the pictures are drawn here, pixel by pixel; links name documentation domains (RFC 2606). Nothing is fetched.

import { encodePng } from './standin.mjs';

export const F_NOW = Date.UTC(2026, 10, 15, 14, 2);
const MIN = 60e3;
const HOUR = 3600e3;
const DAY = 86400e3;

/** A 16-hex item id from a source and a number (the shape of `FeedIds.id`; not its hash). */
export function itemId(src, i) {
  let a = 0x811c9dc5;
  let b = 0x01000193;
  for (const c of `${src}|${i}`) {
    a = Math.imul(a ^ c.charCodeAt(0), 16777619) >>> 0;
    b = Math.imul(b + c.charCodeAt(0), 2246822519) >>> 0;
  }
  return (a.toString(16).padStart(8, '0') + b.toString(16).padStart(8, '0')).slice(0, 16);
}

// ---- the sources (spec §3.3 `desc/feed`): made-up names over the five kinds the engine knows ----

export const SOURCES = [
  { id: 'front', kind: 'REDDIT', name: 'front page', count: 120, newCount: 12, health: { state: 'ok' } },
  { id: 'news', kind: 'SLASHDOT', name: 'tech news', count: 30, newCount: 4,
    health: { state: 'failing', failLine: 'feeds.example.net answered 503', failSinceMs: F_NOW - 40 * MIN, retryAtMs: F_NOW + 95e3 } },
  { id: 'stick', kind: 'XKCD', name: 'Stick Strip', count: 20, newCount: 1, health: { state: 'ok' } },
  { id: 'panel', kind: 'SMBC', name: 'Panel Daily', count: 20, newCount: 0, health: { state: 'ok' } },
  { id: 'quest', kind: 'EIGHTBIT', name: 'Pixel Quest', binge: true, count: 0, newCount: 0, bingeTotal: 1225, health: { state: 'ok' } },
  { id: 'r:gardening', kind: 'REDDIT', name: 'r/gardening', transient: true, pinned: true, count: 10, newCount: 3,
    health: { state: 'limited', host: 'feeds.example.com', retryAtMs: F_NOW + 50e3 } },
  { id: 'r:knots', kind: 'REDDIT', name: 'r/knots', transient: true, pinned: false, count: 5, newCount: 5, health: { state: 'ok' } },
].map((s, i) => ({ binge: false, transient: false, pinned: false, version: 10 + i, lastOkMs: F_NOW - (4 + i * 3) * MIN, lastFetchMs: F_NOW - (4 + i * 3) * MIN, ...s }));

/** Spec §3.2's table: every action with its kind and level (`v` view, `r` reversible, `o` outbound). */
export const ACTIONS = [
  ['open', 'v', 'view', 'Open'], ['read', 'r', 'view', 'Read'], ['markRead', 'r', 'control', 'Mark read'], ['unread', 'r', 'control', 'Mark unread'],
  ['markAllRead', 'r', 'control', 'Mark all read'], ['flag', 'r', 'control', 'Flag'], ['unflag', 'r', 'control', 'Unflag'],
  ['pin', 'r', 'control', 'Pin'], ['unpin', 'r', 'control', 'Unpin'], ['recent', 'register', 'full', 'Keep the browse'],
  ['place', 'position', 'view', 'Place'], ['binge', 'position', 'view', 'Binge place'], ['seen', 'register', 'view', 'Seen'],
  ['set', 'register', 'control', 'Set a row'], ['refresh', 'o', 'full', 'Refresh'], ['comments', 'v', 'view', 'Comments'],
  ['original', 'v', 'view', 'Open the original page'],
].map(([id, kind, level, label]) => ({ id, kind, level, label, into: kind === 'o' ? 'program' : kind === 'v' ? null : 'record' }));

/** Browse (o · full) with its two parameters: a typed subreddit and a section from the PC's list of twelve. */
export const SECTIONS = ['Main', 'Apple', 'AskSlashdot', 'Developers', 'Games', 'Hardware', 'IT', 'Linux', 'Mobile', 'Politics', 'Science', 'Search'];
ACTIONS.push({ id: 'browse', kind: 'o', level: 'full', label: 'Browse', into: 'program',
  params: { text: { placeholder: 'subreddit' }, choice: { options: SECTIONS.map((s) => ({ id: s, label: s.toLowerCase() })) } } });

const choice = (id, label, scope, opts, value) => ({ id, label, scope, type: 'choice', level: 'control', value, options: opts.map((o) => ({ id: o, label: String(o) })) });
export const SETTINGS = [
  choice('glasses.size', 'Size', 'glasses', ['global', 288, 352, 416, 480], 'global'),
  { id: 'images', label: 'Images', scope: 'device', type: 'bool', level: 'view', value: true },
  choice('glasses.art.xkcd', 'xkcd art', 'glasses', ['auto', 'never', 'always'], 'auto'),
  choice('glasses.art.smbc', 'SMBC art', 'glasses', ['auto', 'never', 'always'], 'auto'),
  choice('glasses.art.binge', '8-Bit art', 'glasses', ['auto', 'never', 'always'], 'auto'),
  choice('glasses.levels', 'Comic levels', 'glasses', [16, 8, 4], 16),
  choice('fetch', 'Fetch', 'shared', ['5 min', '15 min', '30 min', '60 min'], '15 min'),
  choice('keep', 'Keep', 'shared', ['7 d', '30 d', '90 d'], '30 d'),
  { id: 'notify.reddit', label: 'Notify · Reddit', scope: 'shared', type: 'bool', level: 'control', value: false },
  { id: 'notify.slashdot', label: 'Notify · Slashdot', scope: 'shared', type: 'bool', level: 'control', value: false },
  { id: 'notify.comics', label: 'Notify · comics', scope: 'shared', type: 'bool', level: 'control', value: false },
];

/** `desc/feed`. */
export function feedDesc({ sources = SOURCES, flagged = 3 } = {}) {
  return { id: 'feed', name: 'Feed', icon: 'feed', min: 'view', title: 'feed', availability: 'live',
    summary: { line: '25 new · front page 12 · tech news 4', detail: '1 failing · 1 rate-limited', counts: { new: 25 }, asOf: F_NOW - 4 * MIN },
    sources, flagged, actions: ACTIONS, settings: SETTINGS };
}

// ---- items (spec §3.3: 50 a page, newest first) ----

const W1 = ['Quiet', 'Copper', 'Harbour', 'Lantern', 'Northern', 'Orchard', 'Signal', 'Tidal', 'Paper', 'Granite', 'Saltwater', 'Meadow'];
const W2 = ['bridge', 'garden', 'library', 'ferry', 'workshop', 'kiln', 'radio', 'bakery', 'loom', 'observatory', 'canal', 'market'];
const LONG = [
  'A volunteer crew rebuilt the {a} {b} by hand over three winters, and the town now argues happily about what colour to paint the railings',
  'Ask the board: is it reasonable to keep a {b} running on parts salvaged from two older ones, or is that how fires start?',
  'The {a} {b} reopens after the flood, with the old ledger found dry in a biscuit tin under the stairs and read aloud at the opening',
];

function title(src, i) {
  const a = W1[(i * 7 + src.length) % 12];
  const b = W2[(i * 5 + 3) % 12];
  if (i % 6 === 2) return LONG[i % 3].replace('{a}', a).replace('{b}', b);
  return `${a} ${b}: ${['a first look', 'notes from a week of use', 'what changed', 'the long way round', 'a small fix', 'questions and answers'][i % 6]}`;
}

/** Row `i` of source `src` (spec §3.3's row: `{id, kind: item, title, link, author, published, itemKind, comments,
 *  extra, num?, read, flagged}`). */
export function row(src, i, over = {}) {
  const s = SOURCES.find((x) => x.id === src) || { kind: 'REDDIT' };
  const comic = s.kind === 'XKCD' || s.kind === 'SMBC';
  const id = itemId(src, i);
  return { id, kind: 'item', title: comic ? `${W1[i % 12]} ${W2[(i * 3) % 12]}` : title(src, i), link: `https://example.org/${src}/${id}`,
    author: comic ? '' : `user_${W2[i % 12]}${i}`, published: F_NOW - (i * 47 + 9) * MIN, itemKind: comic ? 'COMIC' : i % 9 === 4 ? 'IMAGE' : 'LINK',
    comments: comic ? -1 : (i * 13 + 5) % 140, extra: s.kind === 'SLASHDOT' ? ['science', 'linux', 'hardware'][i % 3] : s.kind === 'REDDIT' ? (src.startsWith('r:') ? src.slice(2) : ['woodworking', 'cityplanning', 'birding'][i % 3]) : '',
    ...(s.kind === 'XKCD' ? { num: 2961 - i } : {}), read: false, flagged: false, ...over };
}

/** A page of a source's items (S1-2's page shape: `{count, version, from, size, rows, more}`). */
export function feedPage(src, n, { total, version } = {}) {
  const s = SOURCES.find((x) => x.id === src);
  const all = total ?? (s ? s.count : 0);
  const from = n * 50;
  const rows = Array.from({ length: Math.max(0, Math.min(50, all - from)) }, (_, i) => row(src, from + i));
  return { count: all, version: version ?? (s ? s.version : 1), from, size: rows.length, rows, more: from + rows.length < all };
}

// ---- an article (spec §3.3 `feed/item:`; §2.3's spans) ----

const sp = (text, marks = [], href) => (href ? { text, marks: [...marks, 'link'], href } : { text, marks });
const blk = (kind, spans, extra = {}) => ({ kind, spans, ...extra });

export function article(src, id) {
  const r = { ...(findRow(src, id) || row(src, 0)), id };
  if (src === 'news' && r.id === itemId('news', 1)) return floorArticle(src, r);
  return { item: { ...r, target: 'https://example.org/report/harbour-sensors', hasComments: true }, extracted: true, note: '',
    document: { id: `item:${src}:${id}`, extractor: 1, sourceUrl: 'https://example.org/report/harbour-sensors', blocks: [
      blk('para', [sp('A made-up harbour board fitted '), sp('forty', ['strong']), sp(' low-cost tide sensors along its quays last spring, and '),
        sp('every one of them', ['em']), sp(' kept reporting through the winter storms.')]),
      blk('heading', [sp('from example.org')], { level: 1 }),
      blk('para', [sp('The board’s '), sp('own report', [], 'https://example.org/report/harbour-sensors'), sp(' says the readings are sent once a minute over '),
        sp('a plain radio link', ['em']), sp(', and that the firmware is a single loop calling '), sp('read_tide()', ['code']), sp(' then '), sp('send_frame()', ['code']), sp('.')]),
      blk('para', [sp('A link that is not a web page stays plain text: '), sp('run this', [], 'javascript:alert(1)'), sp(', and so does '), sp('this one', [], 'data:text/html,hi'), sp('.')]),
      blk('image', [], { ref: 'feed.art-chart', alt: 'A bar chart of tide heights at four made-up quays' }),
      blk('para', [sp('The engineer who wrote it put the whole build in one line of a '), sp('Makefile', ['code']), sp(':')]),
      blk('mono', [sp('$ make tide FLAGS=-Os\ncc -Os -o tide main.c radio.c\nsize tide\n   text    data     bss\n   7420     112     640')]),
      blk('quote', [sp('We expected to replace half of them by now. We have replaced none.', ['em'])]),
      blk('item', [sp('forty sensors, each on its own post')], { level: 1 }),
      blk('item', [sp('one receiver in the harbour office, with a spare in a drawer')], { level: 1 }),
      blk('item', [sp('readings kept for '), sp('ninety days', ['strong'])], { level: 2 }),
      blk('para', [sp('Nothing in this article is real: the harbour, the board, the engineer and the numbers are all made up for a picture of the page. '
        + 'It runs long on purpose, so the page has something to scroll through and a place to keep. '.repeat(3))]),
      blk('rule', []),
      blk('para', [sp('Next spring the board plans to add '), sp('wind', ['em']), sp(' to the same posts.')]),
    ] } };
}

function floorArticle(src, r) {
  return { item: { ...r, hasComments: true }, extracted: false, note: 'the source page answered 403',
    document: { id: `item:${src}:${r.id}`, extractor: 1, sourceUrl: '', blocks: [
      blk('para', [sp(`${r.title}. A made-up editor’s summary of two sentences stands in for the article. It names a report the page could not reach.`)]),
      blk('heading', [sp('from example.com')], { level: 1 }),
    ] } };
}

function findRow(src, id) {
  const s = SOURCES.find((x) => x.id === src);
  for (let i = 0; i < (s ? s.count : 0); i++) if (itemId(src, i) === id) return row(src, i);
  return null;
}

// ---- comments (spec §3.3 `feed/comments:`): a tree with depths (Slashdot's shape) and a flat list (Reddit's) ----

const SAYINGS = [
  'This matches what we saw on the canal locks: the cheap sensors outlast the expensive ones because nobody is afraid to clean them.',
  'Once a minute seems slow for a tide gauge.',
  'It is a tide, not a race. Once a minute is plenty, and it keeps the radio quiet.',
  'Quiet radio is the whole point. Our board sent every second and the batteries were the first thing to go.',
  'Which batteries? We tried three kinds and only one survived the cold.',
  'The report has a table at the end with the battery figures. Short version: the plain ones.',
  'Did anyone else read the bit about the spare receiver in a drawer and feel seen?',
  'Every working system has a spare in a drawer somewhere.',
];

export function comments(src, id) {
  const flat = src !== 'news';
  const depths = flat ? [0, 0, 0, 0, 0, 0] : [0, 1, 2, 3, 4, 1, 0, 1];
  return { item: findRow(src, id) || null, entries: depths.map((depth, i) => ({ id: itemId(`${id}/c`, i), author: `${W1[(i * 5) % 12].toLowerCase()}_${W2[i % 12]}`,
    published: F_NOW - (180 - i * 17) * MIN, depth, ...(flat ? {} : { title: i === 0 ? 'Cheap and cleanable' : i % 3 === 2 ? 'Batteries' : 'Re: Cheap and cleanable', score: `${5 - (i % 4)}, ${['Insightful', 'Interesting', 'Informative', 'Funny'][i % 4]}` }),
    blocks: [blk('para', [sp(SAYINGS[i % SAYINGS.length])])].concat(i === 2 ? [blk('quote', [sp('Once a minute seems slow for a tide gauge.')])] : []) })) };
}

// ---- comics (spec §2.4: the original as a blob, alt text, the bonus, neighbours available or dim) ----

export function comic(src, id, n) {
  const s = SOURCES.find((x) => x.id === src);
  if (s && s.kind === 'XKCD') {
    const num = n ?? (findRow(src, id) || row(src, 0)).num;
    const i = 2961 - num;
    const r = row(src, Math.max(0, i));
    return { item: { ...r, id: id || itemId(src, i), num }, num, image: `feed.stick-${num % 4}`, bonus: null,
      alt: 'The second panel took longer to draw than the whole of the first week, and the ladder still does not reach the roof.',
      neighbours: { first: num > 1 ? { target: `num:${src}:1` } : { dim: 'this is the first' }, prev: num > 1 ? { target: `num:${src}:${num - 1}` } : { dim: 'this is the first' },
        random: { range: [1, 2961] }, next: num < 2961 ? { target: `num:${src}:${num + 1}` } : { dim: 'this is the latest' },
        latest: num < 2961 ? { target: `num:${src}:2961` } : { dim: 'this is the latest' } } };
  }
  const i = Math.max(0, Array.from({ length: 20 }, (_, k) => itemId(src, k)).indexOf(id));
  const r = row(src, i);
  return { item: r, num: 0, image: 'feed.panel-main', bonus: 'feed.panel-bonus', alt: 'The professor’s footnote is longer than the lecture.',
    neighbours: { first: i < 19 ? { target: `item:${src}:${itemId(src, 19)}` } : { dim: 'this is the oldest fetched' },
      prev: i < 19 ? { target: `item:${src}:${itemId(src, i + 1)}` } : { dim: 'this is the oldest fetched' },
      random: { target: `item:${src}:${itemId(src, (i + 7) % 20)}` },
      next: i > 0 ? { target: `item:${src}:${itemId(src, i - 1)}` } : { dim: 'this is the latest' },
      latest: i > 0 ? { target: `item:${src}:${itemId(src, 0)}` } : { dim: 'this is the latest' } } };
}

// ---- the binge (spec §3.3 `feed/binge:<src>/around:<ep>`: 3 before and 5 after) ----

export function bingeWindow(src, ep, { pending = [414], failed = [] } = {}) {
  const first = 1;
  const last = 1225;
  const at = Math.max(first, Math.min(last, ep));
  const entries = [];
  for (let n = Math.max(first, at - 3); n <= Math.min(last, at + 5); n++) {
    const st = failed.includes(n) ? 'failed' : pending.includes(n) ? 'pending' : 'ready';
    entries.push({ num: n, title: `${W1[n % 12]} ${W2[(n * 7) % 12]}`, date: Date.UTC(2003, 0, 1) + n * 2 * DAY, image: st === 'ready' ? `feed.quest-${n % 5}` : null, state: st,
      ...(st === 'failed' ? { reason: 'the archive page answered 404' } : {}) });
  }
  return { id: `binge:${src}`, total: 1225, first, last, entries };
}

// ---- the record (`rec.feed.*`, the lean spec's B1: reads one list per source; one shared place) ----

export const FLAGGED = [['front', 5], ['news', 2], ['stick', 0]];
/** The front page's read items (the list newest last, as the PC keeps it). */
export const READ_FRONT = [23, 20, 16, 15, 11, 8, 7, 4, 3, 1];

const it = (v, by = 'phone-1', at = F_NOW - HOUR) => ({ v, by, at });

export function feedRecord() {
  const items = {};
  items['rec.feed.read.front'] = it(READ_FRONT.map((i) => itemId('front', i)));
  for (const [src, i] of FLAGGED) {
    const r = row(src, i);
    items[`rec.feed.flag.${r.id}`] = it({ t: r.title, l: r.link, s: src, p: r.published, k: r.itemKind, a: F_NOW - (i + 1) * 2 * HOUR });
  }
  items['rec.feed.pin.r:gardening'] = it(true);
  items['rec.feed.recent.reddit'] = it(['gardening', 'knots', 'woodworking']);
  items['rec.feed.recent.slashdot'] = it(['Linux', 'Science']);
  items['rec.feed.seen.front'] = it(F_NOW - 9 * MIN * 47);
  items['rec.feed.binge.quest'] = it({ ep: 412, f: 0.2, s: 3, w: 564 });
  items['rec.feed.notify.reddit'] = it(true, 'phone-1', F_NOW - DAY);
  return { t: 'snap', scope: 'feed', epoch: 3, seq: 40, items };
}

/** A change of the Feed record made by another device's input (the glasses), as the PC sends it to every page:
 *  `name` = `value` at `at` (the stand-in's `/__push`). */
export const feedChange = (seq, name, value, at = F_NOW + MIN) => ({ t: 'chg', scope: 'feed', epoch: 3, seq,
  patches: [{ addr: `rec.feed.${name}`, set: it(value, 'phone-1', at) }] });

/** What the stand-in answers for a Feed scope (the look harness), or undefined. */
export function feedScope(scope) {
  if (scope === 'desc/feed') return feedDesc();
  let m = /^feed\/src:(.+)\/page:(\d+)$/.exec(scope);
  if (m) return feedPage(m[1], Number(m[2]));
  m = /^feed\/item:(.+):([0-9a-f]{16})$/.exec(scope);
  if (m) return article(m[1], m[2]);
  m = /^feed\/comments:(.+):([0-9a-f]{16})$/.exec(scope);
  if (m) return comments(m[1], m[2]);
  m = /^feed\/comic:(.+):([0-9a-f]{16})$/.exec(scope);
  if (m) return comic(m[1], m[2]);
  m = /^feed\/num:(.+):(\d+)$/.exec(scope);
  if (m) return comic(m[1], null, Number(m[2]));
  m = /^feed\/binge:(.+)\/around:(\d+)$/.exec(scope);
  if (m) return bingeWindow(m[1], Number(m[2]));
  return undefined;
}

// ---- the pictures, drawn here (no file from anywhere) ----

function canvas(w, h, bg) {
  const raw = Buffer.alloc((w * 3 + 1) * h);
  const set = (x, y, c) => {
    if (x < 0 || y < 0 || x >= w || y >= h) return;
    raw.set(c, y * (w * 3 + 1) + 1 + x * 3);
  };
  const rect = (x, y, rw, rh, c) => { for (let j = y; j < y + rh; j++) for (let i = x; i < x + rw; i++) set(i, j, c); };
  rect(0, 0, w, h, bg);
  const line = (x0, y0, x1, y1, t, c) => {
    const n = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0), 1);
    for (let k = 0; k <= n; k++) rect(Math.round(x0 + ((x1 - x0) * k) / n) - (t >> 1), Math.round(y0 + ((y1 - y0) * k) / n) - (t >> 1), t, t, c);
  };
  const ring = (cx, cy, r, t, c) => { for (let a = 0; a < 360; a += 2) rect(Math.round(cx + r * Math.cos((a * Math.PI) / 180)) - (t >> 1), Math.round(cy + r * Math.sin((a * Math.PI) / 180)) - (t >> 1), t, t, c); };
  const frame = (x, y, rw, rh, t, c) => { rect(x, y, rw, t, c); rect(x, y + rh - t, rw, t, c); rect(x, y, t, rh, c); rect(x + rw - t, y, t, rh, c); };
  return { rect, line, ring, frame, png: () => encodePng(w, h, raw) };
}

const INK = [24, 24, 24];
const WHITE = [255, 255, 255];

/** A stick figure at (x, y) (its feet), `s` tall. */
function figure(c, x, y, s, arm = 0) {
  c.ring(x, y - s * 0.85, s * 0.13, 3, INK);
  c.line(x, y - s * 0.72, x, y - s * 0.3, 3, INK);
  c.line(x, y - s * 0.3, x - s * 0.15, y, 3, INK);
  c.line(x, y - s * 0.3, x + s * 0.15, y, 3, INK);
  c.line(x, y - s * 0.6, x - s * 0.2, y - s * (0.45 - arm), 3, INK);
  c.line(x, y - s * 0.6, x + s * 0.2, y - s * (0.45 + arm), 3, INK);
}

/** A strip of three panels, black on white (the numbered comic's look). */
export function stickStrip(k = 0) {
  const w = 740;
  const h = 260;
  const c = canvas(w, h, WHITE);
  for (let p = 0; p < 3; p++) {
    const x = 10 + p * 245;
    c.frame(x, 10, 230, 240, 3, INK);
    figure(c, x + 70, 225, 120, (p + k) % 2 ? 0.2 : -0.1);
    if (p !== 1) figure(c, x + 160, 225, 110, 0.1);
    else { c.line(x + 140, 225, x + 200, 40, 3, INK); c.line(x + 170, 225, x + 225, 50, 3, INK); for (let r = 0; r < 6; r++) c.line(x + 146 + r * 9, 200 - r * 30, x + 176 + r * 9, 200 - r * 30, 3, INK); }
  }
  return c.png();
}

/** A tall strip of four coloured panels (the daily comic's look) and its bonus panel. */
export function panelStrip(bonus = false) {
  const w = 640;
  const h = bonus ? 420 : 1300;
  const c = canvas(w, h, [250, 247, 238]);
  const n = bonus ? 1 : 4;
  for (let p = 0; p < n; p++) {
    const y = 12 + p * ((h - 12) / n);
    const ph = (h - 12) / n - 14;
    c.rect(14, y, w - 28, ph, [[214, 228, 240], [240, 222, 205], [222, 238, 214], [238, 214, 226]][(p + (bonus ? 2 : 0)) % 4]);
    c.frame(14, y, w - 28, ph, 4, INK);
    c.rect(60, y + ph - 40, w - 120, 6, [120, 100, 80]);
    figure(c, 200 + p * 40, y + ph - 40, Math.min(220, ph - 50), 0.1);
    c.rect(w - 260, y + 30, 200, 60, WHITE);
    c.frame(w - 260, y + 30, 200, 60, 3, INK);
  }
  return c.png();
}

/** A page of the pixel-art archive: six panels of blocky scenery and figures. */
export function questPage(k = 0) {
  const w = 760;
  const h = 560;
  const c = canvas(w, h, [20, 20, 40]);
  const pal = [[70, 140, 210], [60, 160, 90], [200, 160, 60], [180, 70, 70], [150, 110, 190]];
  for (let p = 0; p < 6; p++) {
    const x = 10 + (p % 3) * 250;
    const y = 10 + Math.floor(p / 3) * 275;
    c.rect(x, y, 240, 265, [40 + k * 9, 60, 110]);
    c.rect(x, y + 190, 240, 75, pal[(p + k) % 5]);
    for (let b = 0; b < 3; b++) {
      const bx = x + 30 + b * 70;
      const col = pal[(p + b + k + 1) % 5];
      c.rect(bx, y + 110, 36, 80, col);
      c.rect(bx + 6, y + 80, 24, 30, [230, 200, 170]);
      c.rect(bx + 10, y + 90, 4, 4, INK);
      c.rect(bx + 22, y + 90, 4, 4, INK);
    }
    c.frame(x, y, 240, 265, 4, [240, 240, 240]);
    c.rect(x + 12, y + 12, 216, 44, WHITE);
    c.frame(x + 12, y + 12, 216, 44, 3, INK);
  }
  return c.png();
}

/** A bar chart in colour (an article's picture). */
export function chartPicture() {
  const w = 900;
  const h = 500;
  const c = canvas(w, h, [246, 246, 242]);
  c.line(60, 440, 860, 440, 3, INK);
  c.line(60, 440, 60, 40, 3, INK);
  const hs = [260, 340, 190, 380];
  const cols = [[49, 104, 170], [222, 125, 44], [76, 150, 90], [170, 64, 96]];
  hs.forEach((v, i) => c.rect(120 + i * 190, 440 - v, 120, v, cols[i]));
  for (let y = 100; y < 440; y += 60) c.line(64, y, 860, y, 1, [200, 200, 196]);
  return c.png();
}

let made = null;
/** Feed's blobs for the stand-in, drawn once per process. */
export function feedBlobs() {
  if (made) return made;
  made = { 'feed.art-chart': chartPicture(), 'feed.panel-main': panelStrip(false), 'feed.panel-bonus': panelStrip(true) };
  for (let k = 0; k < 4; k++) made[`feed.stick-${k}`] = stickStrip(k);
  for (let k = 0; k < 5; k++) made[`feed.quest-${k}`] = questPage(k);
  return made;
}
