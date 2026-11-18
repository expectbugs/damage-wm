// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-3, piece B17 (the lean spec's B1, B2; CONTRACT.md §2.4, §3.2, §3.4, §4, §5; FEED.md's verdicts): Feed in a
// browser, without the DOM. The sources with their counts and health (a countdown from `retryAtMs` and the PC's clock,
// never from text); a source's items a page at a time; read, flag and pin state from the window's record copy with
// this page's ops on their way; every record op's draft (L1: no expectation — the PC keeps the latest input, `at`;
// reads are one list per source, `read.<src>`, newest last, at most 600); the one shared place of the open article
// and the binge's place, which the page FOLLOWS (Reader's rule); the spans as the renderer takes them (a link http or
// https only); the binge's window around its anchor; the comic's neighbours; the addresses. Pure: no DOM, no clock of
// its own (callers pass `now`, on the PC's clock).
//
// Every shape of the PC's Feed core this page assumes (the lean spec leaves them open) is written once, in the
// function named in the report's list: the record keys and action ids in the drafts below, the description's fields in
// `healthOf` / `sourceLine`, the comic's neighbours in `barTarget`, a browse's answer in `browsed`.

import { atLeast, hhmm } from './proto.mjs';
import { Listing, nearEnd } from './torrents-model.mjs';
import { Pending as RecordPending } from './record.mjs';

export { nearEnd };
export const W = 'feed';
export const DESC = 'desc/feed';
/** A place (an article's, the binge's) is sent this long after the scroll that moved it rests (spec §3.8). */
export const SAVE_MS = 2000;
/** A refused page with `retry` is asked again on this pacing (the Torrents and Files rule; spec §5 V2's class). */
export const RETRY_MS = 5000;
/** The browse recents kept per kind (FEED.md §3.1: up to five). */
export const RECENTS_MAX = 5;
/** The comments' places this browser keeps (decision A.7: per interface), most recent first. */
export const COMMENT_PLACES_MAX = 50;
/** Per-browser keys: Images is a device row (spec §3.6); the comments' places are this interface's (A.7). */
export const KEYS = { images: 'damage.feed.images', comments: 'damage.feed.comments' };

const str = (x) => (x === null || x === undefined ? '' : String(x));
const num = (x) => (typeof x === 'number' && Number.isFinite(x) ? x : null);

// ---- addresses (C§5's Feed row; W-04): `/w/feed/<target>[@<anchor>]` ----

/** The last `:`-part of `rest` and what is before it: a source id may hold `:` (`r:linux`), an item id never does. */
function splitLast(rest) {
  const i = rest.lastIndexOf(':');
  return i <= 0 ? null : [rest.slice(0, i), rest.slice(i + 1)];
}

/**
 * The place an address target names: the sources (none), a source's items (`src:<id>`), an item (`item:<src>:<id>`),
 * its comments (`comments:<src>:<id>`, this page's own), a numbered comic (`num:<src>:<n>`), the binge
 * (`binge:<id>`), Flagged (`flagged`) or the settings (`settings`). Anything else is the sources, with `bad` set.
 */
export function placeOf(target) {
  const t = str(target);
  if (!t) return { at: 'sources' };
  if (t === 'flagged') return { at: 'flagged' };
  if (t === 'settings') return { at: 'settings' };
  const c = t.indexOf(':');
  const head = c < 0 ? t : t.slice(0, c);
  const rest = c < 0 ? '' : t.slice(c + 1);
  if ((head === 'src' || head === 'binge') && rest) return { at: head, src: rest };
  if (head === 'item' || head === 'comments' || head === 'num') {
    const p = splitLast(rest);
    if (p && p[1]) {
      if (head === 'num') return /^\d+$/.test(p[1]) ? { at: 'num', src: p[0], n: Number(p[1]) } : { at: 'sources', bad: t };
      return { at: head, src: p[0], id: p[1] };
    }
  }
  return { at: 'sources', bad: t };
}

/** The target of a place (the inverse of `placeOf`); null for the sources. */
export function targetOf(p) {
  switch (p.at) {
    case 'src': return `src:${p.src}`;
    case 'binge': return `binge:${p.src}`;
    case 'item': return `item:${p.src}:${p.id}`;
    case 'comments': return `comments:${p.src}:${p.id}`;
    case 'num': return `num:${p.src}:${p.n}`;
    case 'flagged': return 'flagged';
    case 'settings': return 'settings';
    default: return null;
  }
}

/** Where Back goes from a place: an item to the list it was opened from, comments to their item, a list home. */
export function parentOf(p, from) {
  if (p.at === 'comments') return { at: 'item', src: p.src, id: p.id };
  if (p.at === 'item' || p.at === 'num') return from === 'flagged' ? { at: 'flagged' } : { at: 'src', src: p.src };
  if (p.at === 'sources') return null;
  return { at: 'sources' };
}

/** An anchor (C§5): `@b<block>.<offset>` (an article) or `@e<episode>.<fraction>` (the binge); null otherwise. */
export function parseAnchor(a) {
  const s = str(a).replace(/^@/, '');
  let m = /^b(\d+)\.(\d+)$/.exec(s);
  if (m) return { b: Number(m[1]), o: Number(m[2]) };
  m = /^e(\d+)\.(\d+(?:\.\d+)?)$/.exec(s);
  if (m) return { ep: Number(m[1]), f: Math.min(1, Number(m[2])) };
  return null;
}

// ---- the scopes (spec §3.3) ----

export const itemScope = (src, id) => `${W}/item:${src}:${id}`;
export const commentsScope = (src, id) => `${W}/comments:${src}:${id}`;
export const comicScope = (src, id) => `${W}/comic:${src}:${id}`;
export const numScope = (src, n) => `${W}/num:${src}:${n}`;
export const bingeScope = (src, ep) => `${W}/binge:${src}/around:${ep}`;
export const pageScope = (src, n) => `${W}/src:${src}/page:${n}`;
/** A picture the PC serves from Feed's blobs (spec §2.9): only an id the PC described, never a URL. */
export const blobUrl = (ref) => (/^feed\.[A-Za-z0-9._-]+$/.test(str(ref)) ? `/blob/${ref}` : null);

// ---- the sources (spec §3.3 `desc/feed`) ----

export const kindOf = (s) => str(s && s.kind).toUpperCase();
export const isBinge = (s) => !!(s && (s.binge || kindOf(s) === 'EIGHTBIT'));
export const isComicSource = (s) => kindOf(s) === 'XKCD' || kindOf(s) === 'SMBC';
export const sourceOf = (desc, id) => ((desc && desc.sources) || []).find((s) => s.id === id) || null;

/**
 * The sources the column lists, in the description's order: every configured one, a browsed one while pinned (the
 * record's value, this page's pin not yet in it, else the description's) or while it is the one open (the glasses'
 * `srcRows`).
 */
export function sourcesShown(desc, pinnedOf, openSrc) {
  return ((desc && desc.sources) || []).filter((s) => !s.transient || pinnedOf(s) || s.id === openSrc);
}

/** A span of time in words: "40 s", "12 min", "3 h", "2 d". */
export function dur(ms) {
  const s = Math.max(0, Math.round(ms / 1000));
  if (s < 60) return `${s} s`;
  if (s < 3600) return `${Math.floor(s / 60)} min`;
  if (s < 86400) return `${Math.floor(s / 3600)} h`;
  return `${Math.floor(s / 86400)} d`;
}
/** An age: "4 min ago"; empty for no stamp. */
export const ago = (at, now) => (num(at) && at > 0 ? `${dur(now - at)} ago` : '');

/**
 * A source's health as a line and its class (spec §3.3: values, never text with an age baked in): rate-limited with
 * the countdown to `retryAtMs`, failing since `failSinceMs` with the PC's own line and the next try, or null when
 * healthy. `now` is the PC's clock.
 */
export function healthOf(s, now) {
  const hl = (s && s.health) || {};
  const st = str(hl.state) || 'ok';
  if (st === 'ok') return null;
  const at = num(hl.retryAtMs);
  const left = at !== null ? at - now : null;
  const retry = left === null ? '' : left > 0 ? `retry in ${dur(left)}` : 'retrying now';
  if (st === 'limited') {
    return { cls: 'limited', text: [`${hl.host ? `${hl.host} ` : ''}rate-limited`, retry].filter(Boolean).join(' · ') };
  }
  const since = num(hl.failSinceMs);
  return { cls: 'failing', text: [`fetch failing${since ? ` since ${hhmm(since)}` : ''}${hl.failLine ? `: ${hl.failLine}` : ''}`,
    retry ? retry.replace('retry', 'next try') : ''].filter(Boolean).join(' · ') };
}

/** Whether a countdown is showing (the column is drawn again each second while one is). */
export const counting = (desc, now) => ((desc && desc.sources) || []).some((s) => num(s.health && s.health.retryAtMs) !== null && s.health.retryAtMs > now - 1000);

/** A source's line under its name: the binge's page, else counts and the last good fetch (the glasses' `srcDetail`). */
export function sourceLine(s, now, bingePlace) {
  if (isBinge(s)) {
    const total = num(s.bingeTotal) || 0;
    if (!total) return 'archive not indexed yet';
    const ep = bingePlace && num(bingePlace.ep);
    return ep ? `page ${ep} of ${total}` : `${total} pages · from the start`;
  }
  const n = num(s.newCount) || 0;
  const age = num(s.lastOkMs) ? `fetched ${ago(s.lastOkMs, now)}` : num(s.lastFetchMs) ? 'fetching' : 'not fetched yet';
  return `${num(s.count) || 0} items · ${n ? `${n} new` : 'nothing new'} · ${age}`;
}

// ---- this page's record ops on their way (drawn until the record holds them or they are answered) ----

const same = (a, b) => JSON.stringify(a ?? null) === JSON.stringify(b ?? null);

/** This page's Feed values on their way (`record.mjs` `Pending`). */
export class Pending extends RecordPending {
  constructor() { super(W); }
}

/** The most read ids a source's list keeps (B1: newest last, at most 600). */
export const READ_MAX = 600;

/** A source's read ids, newest last: the list this page has on its way, else the record's, else null (none held). */
export function readList(rec, pend, srcId) {
  const n = `read.${srcId}`;
  const v = pend && pend.has(n) ? pend.get(n) : rec && rec.value(W, n);
  return Array.isArray(v) ? v.filter((x) => typeof x === 'string') : null;
}

/** An item's read mark: in its source's list (this page's on its way, else the record's), else the row's. */
export function readOf(rec, pend, srcId, id, row) {
  const list = readList(rec, pend, srcId);
  if (list) return list.includes(str(id));
  return !!(row && row.read);
}

/** An item's flag (its facts) or null: this page's on its way, else the record's, else the row's. */
export function flagOf(rec, pend, id, row) {
  const n = `flag.${id}`;
  if (pend && pend.has(n)) return pend.get(n) || null;
  const it = rec && rec.item(W, n);
  if (it) return it.gone ? null : it.v || null;
  return row && row.flagged ? { t: row.title } : null;
}

/** A source's pin, as `flagOf` decides (the description's `pinned` when the record holds nothing). */
export function pinOf(rec, pend, s) {
  const n = `pin.${s.id}`;
  if (pend && pend.has(n)) return pend.get(n) === true;
  const v = rec && rec.value(W, n);
  return v === undefined ? !!s.pinned : v === true;
}

/** Flagged, from the record's flag values (B1: the facts ride the value, so a pruned item still lists), newest flag
 *  first. */
export function flaggedRows(rec, pend) {
  const out = new Map();
  const pre = `rec.${W}.flag.`;
  for (const [k, it] of (rec && rec.items) || []) {
    if (!k.startsWith(pre) || it.gone || !it.v) continue;
    out.set(k.slice(pre.length), it.v);
  }
  for (const [n, e] of (pend && pend.m) || []) {
    if (!n.startsWith('flag.')) continue;
    if (e.v) out.set(n.slice(5), e.v);
    else out.delete(n.slice(5));
  }
  return [...out].map(([id, f]) => ({ id, src: str(f.s), title: str(f.t) || id, link: str(f.l), published: num(f.p), itemKind: str(f.k), at: num(f.a) || 0 }))
    .sort((a, b) => b.at - a.at || (a.id < b.id ? -1 : 1));
}

// ---- the record ops (B1's keys; the action ids are ASSUMED from the old spec's §3.2 — B2 names none) ----

/** `list` with `ids` read, newest last, the oldest let go past READ_MAX; null when every id is in it already. */
export function withRead(list, ids) {
  const had = list || [];
  const add = [...new Set(ids.map(str))].filter((id) => id && !had.includes(id));
  if (!add.length) return null;
  return [...had, ...add].slice(-READ_MAX);
}

/** `read` (view: a reading effect, sent only on input that opens the item — the view decides that): the source's
 *  whole list with the item in it, or null when it is read already. */
export function readDraft(srcId, id, list) {
  const v = withRead(list, [id]);
  return v && { window: W, action: 'read', targets: [`read.${srcId}`], result: v };
}

/** `markRead` / `unread` (control): one item into or out of its source's list, or null when nothing moves. */
export function markDraft(srcId, id, read, list) {
  const had = list || [];
  const v = read ? withRead(had, [id]) : had.includes(str(id)) ? had.filter((x) => x !== str(id)) : null;
  return v && { window: W, action: read ? 'markRead' : 'unread', targets: [`read.${srcId}`], result: v };
}

/** `markAllRead` (control): the source's list with the unread ids this page holds (the lean spec's B17: the record's
 *  list ∪ the ids held, 600 newest), or null when it holds none. `ids` newest first, as the list shows them. */
export function markAllDraft(srcId, ids, list) {
  const v = withRead(list, [...ids].reverse());
  return v && { window: W, action: 'markAllRead', targets: [`read.${srcId}`], result: v };
}

/** A flag's facts (B1's `{t, l, s, p, k, a}`: title, link, source, published, item kind, when flagged). */
export const factsOf = (row, src, at) => ({ t: str(row.title), l: str(row.link), s: str(src), p: num(row.published) || 0, k: str(row.itemKind), a: at });

/** `flag` / `unflag` (control): the facts, or null (a removal). */
export const flagDraft = (row, src, on, at) => ({ window: W, action: on ? 'flag' : 'unflag', targets: [`flag.${row.id}`],
  result: on ? factsOf(row, src, at) : null });

/** `pin` / `unpin` (control): a browsed source. */
export const pinDraft = (srcId, on) => ({ window: W, action: on ? 'pin' : 'unpin', targets: [`pin.${srcId}`], result: on });

/** A kind's browse recents (`recent.reddit`, `recent.slashdot`), newest first. */
export function recentsOf(rec, pend, kind) {
  const n = `recent.${kind}`;
  const v = pend && pend.has(n) ? pend.get(n) : rec && rec.value(W, n);
  return Array.isArray(v) ? v.filter((x) => typeof x === 'string' && x) : [];
}

/** `recent` (full): the whole list with `name` first, deduped, at most five. */
export function recentDraft(kind, name, rec, pend) {
  const list = [name, ...recentsOf(rec, pend, kind).filter((x) => x !== name)].slice(0, RECENTS_MAX);
  return { window: W, action: 'recent', targets: [`recent.${kind}`], result: list };
}

/** The browse kind of a source (`reddit`, `slashdot`) or null: only these two browse (FEED.md verdict 1). */
export const browseKind = (s) => (kindOf(s) === 'REDDIT' ? 'reddit' : kindOf(s) === 'SLASHDOT' ? 'slashdot' : null);

/** A typed subreddit as the PC takes it (`r/` and spaces off), or null. */
export function subName(text) {
  const n = str(text).trim().replace(/^\/?r\//i, '').replace(/^\//, '').trim();
  return /^[A-Za-z0-9_+-]{1,64}$/.test(n) ? n : null;
}

/** The browse button's words, staged with the name (C§4.1). */
export const browseLabel = (kind, name) => (kind === 'reddit' ? `Browse r/${name}` : `Browse ${str(name).toLowerCase()}`);

/** The source id a browse answered (B2: "`browse` (`{kind, name}` → the source id)"; ASSUMED on `result`, as the id or
 *  `{source}` / `{id}`), or null. */
export function browsed(result) {
  if (typeof result === 'string' && result) return result;
  if (result && typeof result === 'object') return str(result.source || result.id) || null;
  return null;
}

/** `seen` (view): the newest publish stamp shown, sent only when it is above the record's (B1; no max rule, C). */
export function seenDraft(srcId, rows, rec) {
  const top = Math.max(0, ...rows.map((r) => num(r.published) || 0));
  const had = num(rec && rec.value(W, `seen.${srcId}`)) || 0;
  if (!top || top <= had) return null;
  return { window: W, action: 'seen', targets: [`seen.${srcId}`], result: top };
}

/** A shared or glasses row's value: this page's on its way, else the record's, else the description's. */
export function rowValue(row, rec, pend) {
  if (pend && pend.has(row.id)) return pend.get(row.id);
  const v = rec && rec.value(W, row.id);
  return v === undefined ? row.value : v;
}

/** The `set` op of a row (control; L1: the latest input wins). */
export const setDraft = (row, value) => ({ window: W, action: 'set', targets: [row.id], result: value });

/** A choice row's options as [id, label]. */
export const optionsOf = (row) => (row.options || []).map((o) => (o && typeof o === 'object' ? [o.id, o.label ?? str(o.id)] : [o, str(o)]));

/** The description's actions by id. */
export const actionsOf = (desc) => new Map(((desc && desc.actions) || []).map((a) => [a.id, a]));

/** An action this device's level allows (one it does not allow is not offered at all). */
export const offered = (a, level) => !!a && atLeast(level, a.level);

/** The words for a program op's answer (Sent or Refused; never an effect not read). */
export function answerLine(label, ans) {
  if (!ans) return `${label}: an answer that does not decode`;
  if (ans.a === 'refused') return `${label}: ${ans.reason || `refused (${ans.code})`}`;
  if (ans.a !== 'sent' && ans.a !== 'seen') return `${label}: an unknown answer '${ans.a}'`;
  return null;
}

// ---- a source's items, a page at a time (spec §3.3: 50 a page, newest first; a moved version re-sends held pages) ----

export class Pages extends Listing {
  constructor(src) {
    super(src, null);
  }
  scope(n) { return pageScope(this.src, n); }
  pageOf(scope) {
    const pre = `${W}/src:${this.src}/page:`;
    if (!scope.startsWith(pre)) return null;
    const n = Number(scope.slice(pre.length));
    return Number.isInteger(n) && n >= 0 ? n : null;
  }
  find(id) { return this.rows().find((r) => r.id === id) || null; }
  /** The item after `id` in the list (the glasses' Next item: the next older), or null. */
  after(id) {
    const rows = this.rows();
    const i = rows.findIndex((r) => r.id === id);
    return i >= 0 ? rows[i + 1] || null : null;
  }
}

/** The row's tail (the glasses' `itemTail`): xkcd's number, a comic's age, comments and section for the rest. */
export function tailOf(row, s, now) {
  const age = num(row.published) ? dur(now - row.published) : '';
  const k = kindOf(s);
  if (k === 'XKCD') return [row.num ? `#${row.num}` : '', age].filter(Boolean).join(' · ');
  if (k === 'SMBC' || k === 'RSS') return age;
  return [num(row.comments) !== null && row.comments >= 0 ? `${row.comments} comments` : '', age, str(row.extra)].filter(Boolean).join(' · ');
}

/** An item of a list or an address is drawn as a comic, not an article. */
export const isComic = (row, s) => (row ? str(row.itemKind).toUpperCase() === 'COMIC' : isComicSource(s));

// ---- documents: spans as the renderer takes them (spec §2.3; C§3.2) ----

/** An `href` the page may draw as a link: http or https only; anything else (a script or data URL, a relative path)
 *  is not a link. */
export function safeHref(href) {
  const s = str(href).trim();
  return /^https?:\/\/[^\s]+$/i.test(s) ? s : null;
}

/** A block's spans as the renderer takes them: text as text; marks em, strong, code; `link` only with a safe href.
 *  A block without spans (a cached article, `ver` 0: spec §3.4, Q10) is one plain span of its `text`. */
export function spansOf(b) {
  const spans = Array.isArray(b.spans) && b.spans.length ? b.spans : typeof b.text === 'string' && b.text ? [{ text: b.text }] : [];
  return spans.map((s) => {
    const marks = (Array.isArray(s.marks) ? s.marks : []).filter((m) => m === 'em' || m === 'strong' || m === 'code');
    const href = safeHref(s.href);
    return href ? { text: str(s.text), marks, href } : { text: str(s.text), marks };
  });
}

/** A document's blocks ready to draw, each with its index (`b` of a place) and its spans made safe. */
export const blocksOf = (doc) => ((doc && doc.blocks) || []).map((b, i) => ({ ...b, i, kind: str(b.kind) || 'para', spans: spansOf(b) }));

/** A block's text length (its spans joined). */
export const blockLen = (b) => (b.spans || []).reduce((n, s) => n + str(s.text).length, 0);

// ---- the shared places this page shows: the open article's (`place`) and the binge's (`binge.<src>`) ----

/**
 * One shared position the page shows, under Reader's follow rule (`views/reader.mjs` `follow`; L1's ruling "The
 * pages"): the entry takes the record's value when it is this thing's; another device's input moving it moves the page,
 * unless this page's own input is later (its place is on its way and wins); a move the page makes by itself (the entry,
 * a follow, a picture arriving) sends nothing. `inputAt` is this page's last input on it, on the PC's clock, stamped
 * once when it is made. `ofThis(v)` says whether a record value is this thing's (the article's id; the binge's source).
 */
export class Follow {
  constructor(ofThis, key = (v) => JSON.stringify(v)) {
    Object.assign(this, { ofThis, key, inputAt: 0, last: null });
  }

  /** The entry: the record's value when it is this thing's, else null. */
  enter(item) {
    const v = item && !item.gone && this.ofThis(item.v) ? item.v : null;
    this.last = v === null ? null : this.key(v);
    return v;
  }

  /** The record's item changed: the value to move the page to, or null (its own, older than this page's input, the
   *  place shown, another thing's). */
  follow(item, me) {
    if (!item || item.gone || item.by === me || !(item.at >= this.inputAt) || !this.ofThis(item.v)) return null;
    const k = this.key(item.v);
    if (k === this.last) return null;
    this.last = k;
    return item.v;
  }

  /** A save point at `value`: true when it is a new place to send (not the one last sent, taken or followed). */
  save(value) {
    const k = this.key(value);
    if (k === this.last) return false;
    this.last = k;
    return true;
  }

  /** The op that carried `value` ended with its connection: it goes again at the next welcome, with its input's time
   *  (an older or equal `at` changes nothing on the PC). True when it is still the place to send. */
  unsent(value) {
    if (this.last !== this.key(value)) return false;
    this.last = null;
    return true;
  }
}

/** The op of the open article's place (B1: ONE register `place` = `{item, b, o, v}`, position · view), stamped with
 *  the time of the input that moved it. */
export const placeOp = (value, at) => ({ window: W, action: 'place', targets: ['place'], result: value, at });
/** The op of the binge's place (`binge.<src>` = `{ep, f, s, w}`, position · view), the glasses' strip hint left empty
 *  (B1: `s` is used only where `w` equals the glasses' width). */
export const bingeOp = (src, value, at) => ({ window: W, action: 'binge', targets: [`binge.${src}`], result: value, at });
/** The open article's place value; `v` the document's extractor version. */
export const placeValue = (item, b, o, v) => ({ item: str(item), b, o, v: num(v) ?? 0 });
/** The binge's place value on this page: the episode at the top and how far into it (0..1, two decimals). */
export const bingeValue = (ep, f) => ({ ep, f: Math.round(Math.max(0, Math.min(1, f)) * 100) / 100, s: null, w: null });
/** Whether two article places are the same place (the page's own redraw is not a move). */
export const placeKey = (v) => JSON.stringify([v && v.item, v && v.b, v && v.o]);
/** Whether two binge places are the same place. */
export const bingeKey = (v) => JSON.stringify([v && v.ep, v && num(v.f) !== null ? v.f : 0]);

// ---- the binge, one long page around its anchor (spec §3.3: 3 before and 5 after an episode) ----

export const BEFORE = 3;
export const AFTER = 5;

export class Binge {
  constructor(src) {
    Object.assign(this, { src, entries: new Map(), asked: new Set(), flying: new Set(), failed: new Map(), total: null, first: null, last: null });
  }

  scope(ep) { return bingeScope(this.src, ep); }

  /** The episode an asked scope names, or null. */
  epOf(scope) {
    const pre = `${W}/binge:${this.src}/around:`;
    if (!scope.startsWith(pre)) return null;
    const n = Number(scope.slice(pre.length));
    return Number.isInteger(n) ? n : null;
  }

  /** Asks the window around `ep` (once per scope while held). Returns the scope, or null when asked already. */
  ask(ep) {
    const s = this.scope(ep);
    if (this.asked.has(s)) return null;
    this.asked.add(s);
    this.flying.add(s);
    return s;
  }

  /** A window arrived (or was described again: a pending picture landed). Returns the nums that changed. */
  got(scope, d) {
    this.flying.delete(scope);
    this.failed.delete(scope);
    if (num(d.total) !== null) this.total = d.total;
    if (num(d.first) !== null) this.first = d.first;
    if (num(d.last) !== null) this.last = d.last;
    const changed = [];
    for (const e of Array.isArray(d.entries) ? d.entries : []) {
      if (num(e.num) === null) continue;
      const v = { num: e.num, title: str(e.title), date: num(e.date), image: e.image || null, state: str(e.state) || (e.image ? 'ready' : 'pending'), reason: str(e.reason) };
      if (!same(this.entries.get(e.num), v)) changed.push(e.num);
      this.entries.set(e.num, v);
    }
    return changed;
  }

  /** A window refused: said in place, asked again only by the view. */
  refused(scope, a) {
    this.flying.delete(scope);
    this.asked.delete(scope);
    this.failed.set(scope, (a && a.reason) || `refused (${a && a.code})`);
  }

  list() { return [...this.entries.values()].sort((a, b) => a.num - b.num); }
  get lo() { return this.entries.size ? Math.min(...this.entries.keys()) : null; }
  get hi() { return this.entries.size ? Math.max(...this.entries.keys()) : null; }
  get atFirst() { return this.lo !== null && this.first !== null && this.lo <= this.first; }
  get atLast() { return this.hi !== null && this.last !== null && this.hi >= this.last; }
  get busy() { return this.flying.size > 0; }

  /** The episode to ask around for more after the loaded end, or null (one window in flight; the end reached). */
  moreAfter() {
    if (this.busy || this.hi === null || this.atLast) return null;
    return this.hi + 1 + BEFORE;
  }

  /** The episode to ask around for more before the loaded start, or null. */
  moreBefore() {
    if (this.busy || this.lo === null || this.atFirst) return null;
    return Math.max(this.first ?? 1, this.lo - 1 - AFTER);
  }

  /** Every scope held (to let go when the binge is left). */
  held() { return [...this.asked]; }

  /** The newest refusal's words, or null. */
  failure() { return this.failed.size ? [...this.failed.values()].at(-1) : null; }
}

/** The episode a jump names, in the archive's range, or why not. */
export function jumpWhy(text, b) {
  const n = Number(str(text).trim());
  if (!Number.isInteger(n) || n <= 0) return 'type an episode number';
  if (b.first !== null && b.last !== null && (n < b.first || n > b.last)) return `episodes run from ${b.first} to ${b.last}`;
  return null;
}

// ---- the comic canvas (spec §2.4; C§3.4): a bar of first · prev · random · next · latest ----

export const BAR = [['first', 'First'], ['prev', 'Prev'], ['random', 'Random'], ['next', 'Next'], ['latest', 'Latest']];

/**
 * Where a bar button goes: `{target}` (an address the PC chose), a number picked in the range for random on a numbered
 * comic (`num:<src>:<n>`), or `{why}` when nothing is there. A neighbour is `{target}`, `{range: [lo, hi]}` (random), or
 * `{dim: reason}` / absent.
 */
export function barTarget(c, which, src, rand = Math.random) {
  const n = (c && c.neighbours && c.neighbours[which]) || null;
  if (n && typeof n.target === 'string' && n.target) return { target: n.target };
  if (n && Array.isArray(n.range) && n.range.length === 2 && num(n.range[0]) !== null && num(n.range[1]) !== null && n.range[1] >= n.range[0]) {
    const [lo, hi] = n.range;
    const cur = num(c.num);
    let pick = lo + Math.floor(rand() * (hi - lo + 1));
    if (pick === cur && hi > lo) pick = pick === hi ? lo : pick + 1;
    return { target: `num:${src}:${pick}` };
  }
  return { why: (n && n.dim) || (which === 'latest' ? 'this is the latest' : which === 'first' ? 'this is the first' : 'nothing there') };
}

// ---- the comments' places, per interface (decision A.7): this browser only ----

/** The places map from its stored text (bad text = none). */
export function commentPlaces(raw) {
  try {
    const v = JSON.parse(raw || '[]');
    return Array.isArray(v) ? v.filter((x) => Array.isArray(x) && x.length === 2 && typeof x[0] === 'string' && typeof x[1] === 'string') : [];
  } catch (e) {
    return [];
  }
}

/** The stored text with `entry` as `item`'s place, most recent first, at most COMMENT_PLACES_MAX. */
export const putCommentPlace = (raw, item, entry) => JSON.stringify([[item, entry], ...commentPlaces(raw).filter(([i]) => i !== item)].slice(0, COMMENT_PLACES_MAX));

/** The entry `item`'s comments were left at, or null. */
export const commentPlace = (raw, item) => (commentPlaces(raw).find(([i]) => i === item) || [])[1] || null;

/** A comment's head: its title (Slashdot, not a `Re:`), author, score, age. */
export function commentHead(c, now) {
  const title = str(c.title);
  return {
    title: title && !/^re:/i.test(title.trim()) ? title : '',
    line: [str(c.author) || 'anonymous', str(c.score), num(c.published) ? dur(now - c.published) : ''].filter(Boolean).join(' · '),
  };
}

/** The item's byline (the glasses' `itemDetail`): source · author · host · age · comments. */
export function byline(item, s, now) {
  let host = '';
  try {
    host = new URL(str(item.target || item.link)).host.replace(/^www\./, '');
  } catch (e) {
    host = '';
  }
  const kind = str(item.itemKind).toUpperCase();
  return [s ? str(s.name) : '', str(item.author), kind === 'LINK' ? host : kind === 'IMAGE' ? 'image' : kind === 'VIDEO' ? 'video' : '',
    num(item.published) ? `${dur(now - item.published)} ago` : '', num(item.comments) !== null && item.comments >= 0 ? `${item.comments} comments` : '']
    .filter(Boolean).join(' · ');
}
