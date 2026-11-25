// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-2, package Q4w (spec §3.3; INTERFACES.md W-20, W-37, W-38; CONTRACT.md §3.3, §4, §9.2): Torrents in a
// browser, without the DOM. The transfer table's filters, sorts, state words and bands; the speed rings filled from the
// snapshots this page receives; the selection by hash; the action drafts and their confirms; the tracker's listings
// taken a page at a time; the search recents from the record. Every byte the page shows or sends is decided here and
// tested under `node --test`. Pure: no DOM, no clock of its own (callers pass `now`).

import { atLeast } from './proto.mjs';
import { enc } from './address.mjs';
import * as R from './rows.mjs';

export const W = 'torrents';
export const DESC = 'desc/torrents';
export const WEEK_MS = 7 * 86400e3;
/** A failed page of a listing is asked again on this pacing (the glasses' rule, TORRENTS.md §3.1; spec §5 V2). */
export const RETRY_MS = 5000;
/** The rows before a listing's loaded end at which the next page is asked (the glasses' eight, TORRENTS.md §3.1). */
export const NEAR_ROWS = 8;
export const RECENTS_MAX = 10;
/** This device's Poll row (CONTRACT.md §12: device scope; TORRENTS.md §3.3: 1 s / 2 s / 5 s while focused). */
export const POLLS = [[1000, '1 s'], [2000, '2 s'], [5000, '5 s']];
export const KEYS = { poll: 'damage.torrents.poll', sort: 'damage.torrents.sort', filter: 'damage.torrents.filter', tlSort: 'damage.torrents.tlsort' };
/** The tracker's sorts (`TorrentLeech.SORTS`, all descending). */
export const TL_SORTS = [['added', 'Newest'], ['seeders', 'Seeders'], ['size', 'Size'], ['name', 'Name']];

// ---- a transfer ----

// qBittorrent 5.x's states (TORRENTS.md §2; `Transfer.DL_STATES` / `UP_STATES`, TorrentsModel.kt:54-55)
const DL = new Set(['downloading', 'stalledDL', 'queuedDL', 'metaDL', 'forcedMetaDL', 'forcedDL', 'checkingDL', 'stoppedDL']);
const UP = new Set(['uploading', 'stalledUP', 'queuedUP', 'forcedUP', 'checkingUP', 'stoppedUP']);

/** The classes the filters read, as `Transfer` derives them (TorrentsModel.kt:44-50). Without the raw state the
 *  PC's word and the progress decide, which cannot tell every case apart (said in the build's report). */
export function classify(raw, word, progress) {
  if (typeof raw === 'string' && raw) {
    const stopped = raw === 'stoppedDL' || raw === 'stoppedUP';
    return { downloading: DL.has(raw), finished: UP.has(raw), stopped, error: raw === 'error' || raw === 'missingFiles',
      checking: raw.startsWith('checking') || raw === 'moving' };
  }
  const done = typeof progress === 'number' && progress >= 1;
  return { downloading: !done, finished: done, stopped: word === 'stopped', error: word === 'error', checking: word === 'checking' };
}

const num = (x) => (typeof x === 'number' && Number.isFinite(x) ? x : null);

/** One transfer of the description (`{id, kind, title, fields}`, spec §3.1) as the table reads it. */
export function rowOf(t) {
  const f = t.fields || {};
  const st = f.state && typeof f.state === 'object' ? f.state : { word: typeof f.state === 'string' ? f.state : '' };
  const r = {
    id: String(t.id), title: String(t.title ?? t.id), raw: st.raw || null, word: st.word || '', band: st.band || 'normal',
    progress: num(f.progress) ?? 0, size: num(f.size), downloaded: num(f.downloaded), uploaded: num(f.uploaded),
    dlSpeed: num(f.dlSpeed) ?? 0, upSpeed: num(f.upSpeed) ?? 0, eta: num(f.eta), ratio: num(f.ratio), seeds: num(f.seeds),
    peers: num(f.peers), added: num(f.added), completed: num(f.completed), seedingTime: num(f.seedingTime) ?? 0,
    savePath: f.savePath || '', contentPath: f.contentPath || '', category: f.category || '', tags: f.tags || '', tracker: f.tracker || '',
  };
  Object.assign(r, classify(r.raw, r.word, r.progress));
  r.underAWeek = r.finished && r.seedingTime < WEEK_MS;
  return r;
}

/** Whether a description is the newer of two (spec §2.2: a client ignores an older `{epoch, version}` pair). */
export function newer(prev, d) {
  if (!prev) return true;
  if (d.epoch !== prev.epoch) return true;
  return !(typeof d.version === 'number' && typeof prev.version === 'number' && d.version < prev.version);
}

// ---- filters, sorts, words (TORRENTS.md §3.1) ----

export const FILTERS = [['all', 'All'], ['downloading', 'Downloading'], ['seeding', 'Seeding'], ['stopped', 'Stopped'], ['errors', 'Errors'], ['week', 'Under a week']];

export function inFilter(r, f) {
  switch (f) {
    case 'downloading': return r.downloading && !r.stopped;
    case 'seeding': return r.finished && !r.stopped;
    case 'stopped': return r.stopped;
    case 'errors': return r.error;
    case 'week': return r.underAWeek;
    default: return true;
  }
}

/** The columns a table sorts by, with their first direction. `activity` is the glasses' default order. */
export const SORTS = {
  activity: { label: 'Activity', dir: 1 }, name: { label: 'Name', dir: 1 }, state: { label: 'State', dir: 1 },
  progress: { label: 'Progress', dir: -1 }, size: { label: 'Size', dir: -1 }, dlSpeed: { label: 'Down', dir: -1 },
  upSpeed: { label: 'Up', dir: -1 }, eta: { label: 'ETA', dir: 1 }, ratio: { label: 'Ratio', dir: -1 },
  seeds: { label: 'Seeds', dir: -1 }, added: { label: 'Added', dir: -1 },
};

/** Errors first (they need attention), then downloads, checking, seeds, stopped (`activityRank`, TorrentsWindow). */
export function activityRank(r) {
  if (r.error) return 0;
  if (r.downloading && !r.stopped) return 1;
  if (r.checking) return 2;
  if (r.finished && !r.stopped) return 3;
  return 4;
}

const byName = (a, b) => a.title.toLowerCase().localeCompare(b.title.toLowerCase()) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0);

/** `rows` sorted by `{key, dir}` (dir 1 ascending, -1 descending); an unknown value (no ETA) is last either way. */
export function sortRows(rows, { key = 'activity', dir } = {}) {
  const d = dir === 1 || dir === -1 ? dir : (SORTS[key] || SORTS.activity).dir;
  const out = rows.slice();
  if (key === 'activity' || !SORTS[key]) {
    return out.sort((a, b) => activityRank(a) - activityRank(b)
      || (b.downloading ? b.progress : 0) - (a.downloading ? a.progress : 0)
      || (b.completed || 0) - (a.completed || 0) || (b.added || 0) - (a.added || 0) || byName(a, b));
  }
  if (key === 'name') return out.sort((a, b) => d * byName(a, b));
  const val = key === 'state' ? (r) => r.word.toLowerCase() : key === 'eta' ? (r) => (r.eta === null || r.eta <= 0 ? null : r.eta) : (r) => r[key];
  return out.sort((a, b) => {
    const x = val(a);
    const y = val(b);
    if (x === null || x === undefined) return y === null || y === undefined ? byName(a, b) : 1;
    if (y === null || y === undefined) return -1;
    return (x < y ? -d : x > y ? d : 0) || byName(a, b);
  });
}

/** The table's rows: the description's transfers, filtered and sorted. */
export function tableRows(desc, filter, sort) {
  const rows = ((desc && desc.transfers) || []).map(rowOf);
  return sortRows(rows.filter((r) => inFilter(r, filter)), sort);
}

/** The counts the filter chips show. */
export function counts(desc) {
  const rows = ((desc && desc.transfers) || []).map(rowOf);
  return Object.fromEntries(FILTERS.map(([f]) => [f, rows.filter((r) => inFilter(r, f)).length]));
}

/** The state word: the PC's (the glasses' word), except the under-a-week list says how long it has seeded and how
 *  long is left (TORRENTS.md §3.1). */
export function wordOf(r, filter) {
  if (filter === 'week' && r.underAWeek) return `${dur(r.seedingTime)} seeded · ${dur(WEEK_MS - r.seedingTime)} left`;
  return r.word || (r.error ? 'error' : r.stopped ? 'stopped' : r.finished ? 'seeding' : 'downloading');
}

/** A band as the page's class: normal, attention or alarm (CONTRACT.md §3.2). */
export const bandClass = (b) => (b === 'alarm' || b === 'attention' ? b : 'normal');

// ---- formats (the glasses' `Fmt`, locale-fixed; C§3.3 typed fields: bytes, B/s, ms, epoch ms) ----

const f1 = (x) => x.toFixed(1);
export function bytes(b) {
  if (b === null || b === undefined || b < 0) return '?';
  if (b < 1024) return `${b} B`;
  if (b < 1048576) return `${Math.floor(b / 1024)} KB`;
  if (b < 1073741824) return `${f1(b / 1048576)} MB`;
  if (b < 1099511627776) return `${(b / 1073741824).toFixed(2)} GB`;
  return `${(b / 1099511627776).toFixed(2)} TB`;
}
export function rate(bps) {
  if (!bps || bps <= 0) return '0 B/s';
  if (bps < 1048576) return `${Math.floor(bps / 1024)} KB/s`;
  return `${f1(bps / 1048576)} MB/s`;
}
export function dur(ms) {
  if (ms === null || ms === undefined || ms < 0) return '?';
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s} s`;
  if (s < 3600) return `${Math.floor(s / 60)} m`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ${String(Math.floor((s % 3600) / 60)).padStart(2, '0')}m`;
  return `${Math.floor(s / 86400)}d ${Math.floor((s % 86400) / 3600)}h`;
}
/** No estimate (qBittorrent's 8640000 s, or none): a dash. */
export const eta = (ms) => (ms === null || ms <= 0 || ms >= 8640000e3 ? '—' : dur(ms));
export function ago(at, now) {
  if (!at || at <= 0) return '';
  const s = Math.max(0, Math.floor((now - at) / 1000));
  if (s < 60) return `${s} s ago`;
  if (s < 3600) return `${Math.floor(s / 60)} m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)} h ago`;
  return `${Math.floor(s / 86400)} d ago`;
}
export const pct = (p) => `${Math.max(0, Math.min(100, Math.floor((p || 0) * 100)))}%`;
export const ratio = (r) => (r === null ? '?' : r < 0 ? '∞' : r.toFixed(2));
/** The tracker's own timestamp text (UTC, "2026-11-15 13:02:11") as an age; any other text as it is (never cut). */
export function siteAge(text, now) {
  const m = /^(\d{4})-(\d\d)-(\d\d)[ T](\d\d):(\d\d):(\d\d)/.exec(String(text || ''));
  if (!m) return String(text || '');
  return ago(Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6]), now);
}

// ---- the speed rings (spec §3.3: per hash, filled from the snapshots this page receives) ----

export class Sparks {
  constructor(n = 30) {
    this.n = n;
    this.rings = new Map();
    this.seen = null;
  }

  /** One snapshot: a sample per transfer, once per `{epoch, version}`; rings of transfers gone are dropped. */
  feed(desc) {
    const at = `${desc.epoch}/${desc.version}/${desc.atMs}`;
    if (at === this.seen) return false;
    this.seen = at;
    const present = new Set();
    for (const t of desc.transfers || []) {
      const r = rowOf(t);
      present.add(r.id);
      const ring = this.rings.get(r.id) || [];
      ring.push({ dl: r.dlSpeed, up: r.upSpeed });
      if (ring.length > this.n) ring.splice(0, ring.length - this.n);
      this.rings.set(r.id, ring);
    }
    for (const id of [...this.rings.keys()]) if (!present.has(id)) this.rings.delete(id);
    return true;
  }

  of(id) {
    return this.rings.get(id) || [];
  }
}

/** Points of a polyline for `values` in a `w` × `h` box, scaled to `top` (at least 1). */
export function line(values, w, h, top) {
  const max = Math.max(1, top);
  if (values.length === 0) return '';
  const step = values.length > 1 ? w / (values.length - 1) : 0;
  return values.map((v, i) => `${Math.round(i * step * 10) / 10},${Math.round((h - (Math.max(0, v || 0) / max) * h) * 10) / 10}`).join(' ');
}

// ---- the selection (W-20) ----

export class Selection {
  constructor() {
    this.ids = new Set();
  }
  has(id) { return this.ids.has(id); }
  get size() { return this.ids.size; }
  list() { return [...this.ids]; }
  toggle(id) { if (this.ids.has(id)) this.ids.delete(id); else this.ids.add(id); }
  set(ids) { this.ids = new Set(ids); }
  clear() { this.ids.clear(); }
  /** Hashes no longer in the table leave the selection (an action never goes to a transfer that is gone). */
  prune(present) {
    const keep = new Set(present);
    let n = 0;
    for (const id of [...this.ids]) if (!keep.has(id)) { this.ids.delete(id); n++; }
    return n;
  }
}

// ---- actions (spec §3.1's table; CONTRACT.md §4) ----

/** The description's actions by id. */
export const actionsOf = (desc) => new Map(((desc && desc.actions) || []).map((a) => [a.id, a]));

/** An action this device's level allows (an action it does not allow is not offered at all). */
export const offered = (a, level) => !!a && atLeast(level, a.level);

/** The actions offered over several transfers, in the menu's order (irreversible last). */
export const BULK = ['start', 'stop', 'recheck', 'delete', 'deleteFiles'];

/** A program op (never queued; `conn`, `k`): an outside program's call. */
export const programDraft = (a, targets, params) => ({ window: W, action: a.id, targets, ...(params ? { params } : {}) });

/** The confirm before an action, worded from the spec's table, or null. An irreversible action always asks; delete
 *  with files asks twice. `names` are the targets' names. */
export function confirmFor(a, names) {
  const n = names.length;
  const one = n === 1 ? names[0] : null;
  const asks = !!a.confirm || a.kind === 'i' || a.kind === 'irreversible';
  if (!asks) return null;
  const double = a.id === 'deleteFiles' || !!(a.confirm && a.confirm.double);
  switch (a.id) {
    case 'delete':
      return { title: one ? `Delete '${one}'?` : `Delete ${n} torrents?`, act: 'Delete', double,
        body: `qBittorrent drops ${one ? 'it' : 'them'} from its list; the downloaded files stay on the PC.` };
    case 'deleteFiles':
      return { title: one ? `Delete '${one}' and its files?` : `Delete ${n} torrents and their files?`, act: 'Delete with files', double,
        body: `qBittorrent drops ${one ? 'it' : 'them'} and deletes the downloaded files from the PC.` };
    case 'add':
      return { title: `Add '${one}'?`, act: 'Add → ~/Downloads', double, body: 'The PC fetches the torrent from TorrentLeech and qBittorrent starts it in ~/Downloads.' };
    case 'addStopped':
      return { title: `Add '${one}'?`, act: 'Add stopped → ~/Downloads', double, body: 'The PC fetches the torrent from TorrentLeech and qBittorrent adds it to ~/Downloads without starting it.' };
    default:
      return { title: `${a.label}${one ? ` '${one}'` : ` ${n} torrents`}?`, act: a.label, double, body: 'This cannot be undone from this page.' };
  }
}

const many = (n) => (n === 1 ? '1 torrent' : `${n} torrents`);

/** The words for a program op's answer (spec §2.4; decision A.7: Sent or Refused, never an effect not read). */
export function answerLine(a, n, ans) {
  if (!ans) return `${a.label}: an answer that does not decode`;
  if (ans.a === 'refused') return `${a.label}: ${ans.reason || `refused (${ans.code})`}`;
  if (ans.a !== 'sent' && ans.a !== 'seen') return `${a.label}: an unknown answer '${ans.a}'`;
  const res = ans.result && typeof ans.result === 'object' ? ans.result : {};
  if (a.id === 'add' || a.id === 'addStopped')
    return `${a.label}: qBittorrent took '${res.name || 'the torrent'}'. Transfers shows it once qBittorrent lists it.`;
  if (a.id === 'openOnPc') return `${a.label}: sent to the PC.`;
  return `${a.label}: sent to qBittorrent for ${many(n)}. The table shows what qBittorrent reports.`;
}

/** What waits for an answer: "Stop · 3 torrents · sent, no answer yet · 12 s" (spec §5 V3: no repeat, the age). */
export function waitingLine(w, now) {
  return `${w.label} · ${many(w.n)} · sent, no answer from the PC yet · ${dur(Math.max(0, now - w.at))}`;
}

// ---- the record: search recents and the rows (spec §3.1; decision A.9) ----

/** The search recents, newest first. */
export function recentsOf(rec) {
  const v = rec && rec.value(W, 'recents');
  return Array.isArray(v) ? v.filter((q) => typeof q === 'string' && q) : [];
}

/** The `recent` op after a search: the whole list with `q` first (deduped, at most ten). */
export function recentDraft(q, rec) {
  const list = [q, ...recentsOf(rec).filter((x) => x !== q)].slice(0, RECENTS_MAX);
  return { window: W, action: 'recent', targets: ['recents'], result: list };
}

/** A row's value: the record's when it holds one, else the description's. */
export const rowValue = (row, rec) => R.rowValue(W, row, rec);

/** The `set` op of a row (L1 C1: the latest input wins). */
export const setDraft = (row, value) => R.setDraft(W, row, value);

// ---- the tracker's listings, a page at a time (spec §2.2; TORRENTS.md §3.1) ----

/** A listing's source part of its scopes: a category id or `newest`, or a search. */
export const listingSrc = (cat) => `listing:${cat}`;
export const searchSrc = (q) => `search:${enc(q)}`;
export const pageScope = (src, sort, n) => `${W}/${src}/sort:${sort}/page:${n}`;

export class Listing {
  constructor(src, sort) {
    Object.assign(this, { src, sort, pages: new Map(), flying: null, failed: null, version: null });
  }

  scope(n) { return pageScope(this.src, this.sort, n); }

  /** The page number of `scope` in this listing, or null. */
  pageOf(scope) {
    const pre = `${W}/${this.src}/sort:${this.sort}/page:`;
    if (!scope.startsWith(pre)) return null;
    const n = Number(scope.slice(pre.length));
    return Number.isInteger(n) && n >= 0 ? n : null;
  }

  get last() { return this.pages.size ? Math.max(...this.pages.keys()) : -1; }
  get ended() { return this.last >= 0 && !this.pages.get(this.last).more; }

  /**
   * The next page to ask, or null: one page in flight at a time; the first page at once; a later one only when the
   * view is `near` the loaded end; a failed page again after RETRY_MS when the PC said `retry`, never before.
   */
  next(near, now) {
    if (this.flying !== null) return null;
    if (this.failed) return this.failed.code === 'retry' && now - this.failed.at >= RETRY_MS ? this.failed.page : null;
    if (this.last < 0) return 0;
    if (this.ended || !near) return null;
    return this.last + 1;
  }

  ask(n) { this.flying = n; }

  /** A page arrived (first time or described again after the listing moved). Returns true when rows changed. */
  got(n, d) {
    if (this.flying === n) this.flying = null;
    if (this.failed && this.failed.page === n) this.failed = null;
    const rows = Array.isArray(d.rows) ? d.rows : [];
    const before = JSON.stringify(this.pages.get(n) || null);
    this.pages.set(n, { rows, more: !!d.more, version: d.version ?? null, stale: false });
    if (d.version !== undefined) this.version = d.version;
    return JSON.stringify(this.pages.get(n)) !== before;
  }

  /** A page refused: said in place; rows already held of it stay, greyed, until it is read again. */
  refused(n, a, now) {
    if (this.flying === n) this.flying = null;
    this.failed = { page: n, code: a.code, reason: a.reason || `refused (${a.code})`, at: now };
    const held = this.pages.get(n);
    if (held) held.stale = true;
  }

  /** Every row held, in page order, each id once (a row that moved pages between two reads shows once). */
  rows() {
    const seen = new Set();
    const out = [];
    for (const n of [...this.pages.keys()].sort((a, b) => a - b)) {
      for (const r of this.pages.get(n).rows) {
        if (seen.has(r.id)) continue;
        seen.add(r.id);
        out.push({ ...r, stale: this.pages.get(n).stale });
      }
    }
    return out;
  }

  /** The scopes of the pages this listing holds or asks (to unsubscribe when it is left). */
  held() {
    const ns = new Set(this.pages.keys());
    if (this.flying !== null) ns.add(this.flying);
    if (this.failed) ns.add(this.failed.page);
    return [...ns].map((n) => this.scope(n));
  }

  /** What the end of the list says: loading, failed, the end, or nothing more to say. */
  status() {
    if (this.failed) return { kind: 'failed', page: this.failed.page, reason: this.failed.reason, retry: this.failed.code === 'retry' };
    if (this.flying !== null) return { kind: this.last < 0 ? 'first' : 'loading' };
    if (this.last >= 0 && this.ended) return { kind: this.rows().length ? 'end' : 'empty' };
    return { kind: 'more' };
  }
}

/** Whether a view scrolled to `top` of `height` in a `view`-high box is within NEAR_ROWS rows of `rowH` of its end. */
export const nearEnd = (top, view, height, rowH) => height - (top + view) <= NEAR_ROWS * Math.max(1, rowH);

/** A tracker row (`{id: fid, title, fields}`) as the results table reads it. */
export function tlRowOf(r) {
  const f = r.fields || {};
  return { id: String(r.id), title: String(r.title ?? r.id), size: f.size, seeders: num(f.seeders), leechers: num(f.leechers),
    snatched: num(f.snatched), added: f.added ?? '', category: f.category, freeleech: !!f.freeleech, tags: Array.isArray(f.tags) ? f.tags : [] };
}

/** A category id in words: "Group · Name" from the categories scope, or the id itself (never dropped). */
export function categoryName(id, cats) {
  const c = (cats || []).find((x) => String(x.id) === String(id));
  return c ? `${c.group} · ${c.name}` : id === undefined || id === null || id === '' ? '' : String(id);
}

/** A size the tracker gave: bytes as a number, or its own text. */
export const tlSize = (s) => (typeof s === 'number' ? bytes(s) : String(s ?? ''));

// ---- addresses (W-04): `/w/torrents/<target>` ----

/** The tab and place an address target names. */
export function placeOf(target) {
  const t = target || '';
  if (t.startsWith('t:')) return { tab: 'transfers', hash: t.slice(2) };
  if (t === 'browse') return { tab: 'browse' };
  if (t.startsWith('listing:')) return { tab: 'browse', cat: t.slice(8) };
  if (t === 'search') return { tab: 'search', q: '' };
  if (t.startsWith('search:')) return { tab: 'search', q: t.slice(7) };
  if (t.startsWith('tl:')) return { tab: 'torrent', fid: t.slice(3) };
  if (t === 'account') return { tab: 'account' };
  return { tab: 'transfers' };
}
