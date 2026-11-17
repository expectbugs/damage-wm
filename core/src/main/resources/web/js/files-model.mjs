// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-2, package Q5w (spec §4.1, §4.3; INTERFACES.md W-20, W-34, W-35; decisions A.8, A.12, A.15; CONTRACT.md
// §4, §5, §9.4, §12): Files in a browser, without the DOM. Addresses and breadcrumbs from a path; a folder's listing a
// page at a time; the order and the hidden filter as the shared rows say; what a file previews as; text read a chunk at
// a time; the blob addresses (the original, `dl=1`, the PDF page and thumbnail renditions); the record ops for the
// clipboard, sort and hidden; the program ops and their confirms; the one job slot's line; the upload list. Every byte
// the page shows or sends is decided here and tested under `node --test`. Pure: no DOM, no clock of its own.

import { atLeast, when } from './proto.mjs';
import { Listing, bytes, dur, pct } from './torrents-model.mjs';

export { Selection, nearEnd, bytes, ago } from './torrents-model.mjs';

export const W = 'files';
export const DESC = 'desc/files';
export const TRASH = 'files/trash';
/** A failed page or chunk is asked again on this pacing when the PC said `retry` (spec §5 V2). */
export const RETRY_MS = 5000;
/** A job whose progress has not moved for this long says so beside its age (A.12: the age is shown, nothing ends it). */
export const QUIET_MS = 10000;
/** The thumbnail rendition's square, in pixels (spec §4.1 `?r=thumb:<px>`). */
export const THUMB_PX = 320;
/** The PDF page rendition's widest form (spec §4.1: the width capped at 2048). */
export const PDF_MAX = 2048;
export const KEYS = { view: 'damage.files.view' };
/** The shared sort row's values (`rec.files.sort`, spec §4.1), in the glasses' order. */
export const SORTS = [['name', 'Name'], ['size', 'Size'], ['mtime', 'Modified']];

// ---- paths and addresses (C§5: `path:<abs>`, a trailing `/` = a folder; the anchor is an entry name) ----

/** A folder's path without its trailing `/` (the root stays `/`). */
export const norm = (p) => (p.length > 1 && p.endsWith('/') ? p.replace(/\/+$/, '') || '/' : p);
export const join = (dir, name) => (dir.endsWith('/') ? dir + name : `${dir}/${name}`);
export function parent(p) {
  const n = norm(p);
  const i = n.lastIndexOf('/');
  return i <= 0 ? '/' : n.slice(0, i);
}
export const baseName = (p) => norm(p).slice(norm(p).lastIndexOf('/') + 1);
/** The address target of a folder (`path:/a/b/`) or of an entry in it (`path:/a/b/name`). */
export const folderTarget = (dir) => `path:${dir === '/' ? '/' : `${norm(dir)}/`}`;
export const entryTarget = (dir, name) => `path:${join(norm(dir), name)}`;

/** What an address target shows: the locations, the trash, a folder, or an entry's pane over its folder. */
export function placeOf(target, anchor) {
  const t = target || '';
  if (t === 'trash') return { at: 'trash' };
  if (!t.startsWith('path:') || t.length === 5) return { at: 'locations' };
  const p = t.slice(5);
  if (!p.startsWith('/')) return { at: 'locations', bad: p };
  if (p.endsWith('/')) return { at: 'folder', dir: norm(p), focus: anchor ? anchor.replace(/^@/, '') : null };
  return { at: 'entry', dir: parent(p), name: baseName(p) };
}

/** S1-2 R2 item 18 (review B-M2): the PC's own trash store is a location of kind `trash`: its card is the trash view,
 *  never a folder card (as the glasses, `FilesGlasses.kt` `l.kind != "trash"`). L1 P45: the PC refuses every write in
 *  it, so the page keeps no guard of its own beyond this. */
export const isTrashLoc = (l) => !!l && l.kind === 'trash';

/** Breadcrumbs of `dir`: from the deepest location that holds it (its label first), else from `/`. */
export function crumbs(dir, locations) {
  const d = norm(dir);
  let base = null;
  for (const l of locations || []) {
    const lp = norm(String(l.path || ''));
    if (!lp || !(d === lp || d.startsWith(lp === '/' ? '/' : `${lp}/`))) continue;
    if (!base || lp.length > norm(base.path).length) base = l;
  }
  const out = base ? [{ label: String(base.label || base.path), path: norm(base.path) }] : [{ label: '/', path: '/' }];
  const from = out[0].path;
  const rest = d === from ? [] : d.slice(from === '/' ? 1 : from.length + 1).split('/').filter(Boolean);
  let at = from;
  for (const part of rest) {
    at = join(at, part);
    out.push({ label: part, path: at });
  }
  return out;
}

/** A folder in words: its location's label and the rest (`Downloads / films`), or its path outside every location. */
export function folderLabel(dir, locations) {
  const c = crumbs(dir, locations);
  return c[0].label === '/' && c[0].path === '/' ? norm(dir) : c.map((x) => x.label).join(' / ');
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
/** A modified time as the glasses' column says it: "04:12" today, "Aug 31" this year, "Aug 31 2024" before. */
export function mtimeWords(ms, now) {
  if (typeof ms !== 'number' || ms <= 0) return '';
  const t = new Date(ms);
  const n = new Date(now);
  if (t.toDateString() === n.toDateString()) return `${String(t.getHours()).padStart(2, '0')}:${String(t.getMinutes()).padStart(2, '0')}`;
  return `${MONTHS[t.getMonth()]} ${t.getDate()}${t.getFullYear() === n.getFullYear() ? '' : ` ${t.getFullYear()}`}`;
}

/** Why `name` cannot be a new name in a folder holding `taken` (rename from `before`, or a new folder), or null. */
export function nameWhy(name, taken, before = null) {
  const n = String(name || '').trim();
  if (!n) return 'A name is needed.';
  if (n.includes('/') || n.includes('\0')) return "A name cannot hold '/'.";
  if (n === '.' || n === '..') return `'${n}' is not a name a file can have.`;
  if (n === before) return 'That is its name already.';
  if (taken.has(n)) return `'${n}' is already in this folder.`;
  return null;
}

// ---- scopes (spec §4.1; the path is raw, the suffix is parsed from the LAST `/page:` or `/at:`) ----

export const folderScope = (dir, n) => `${W}/folder:${norm(dir)}/page:${n}`;
export const textScope = (path, at) => `${W}/text:${path}/at:${at}`;
export const pdfScope = (path) => `${W}/pdf:${path}`;

/** A folder's listing, a page at a time (Torrents' pager over Files' scopes). `want` = an entry the address names: the
 *  pages are asked one after another until it is held or the listing ends (C§5: a cursor re-seated by name). */
export class FolderListing extends Listing {
  constructor(dir) {
    super(norm(dir), null);
    this.want = null;
  }
  scope(n) { return folderScope(this.src, n); }
  pageOf(scope) {
    const pre = `${W}/folder:${this.src}/page:`;
    if (!scope.startsWith(pre)) return null;
    const n = Number(scope.slice(pre.length));
    return Number.isInteger(n) && n >= 0 ? n : null;
  }
  find(name) { return this.rows().find((r) => r.name === name) || null; }
  next(near, now) {
    return super.next(near || (this.want !== null && !this.find(this.want)), now);
  }
}

// ---- rows: the order and the filter as the shared rows say, what a file previews as ----

const lower = (s) => String(s).toLowerCase();
const cmpName = (a, b) => (lower(a.name) < lower(b.name) ? -1 : lower(a.name) > lower(b.name) ? 1 : a.name < b.name ? -1 : a.name > b.name ? 1 : 0);

/** The held rows as the shared sort and hidden rows say (the glasses' order: folders first, then the key; the PC sorts
 *  its pages the same way, so the union of the held pages keeps its order). Hidden off drops a name with a leading dot
 *  at once; hidden on shows them once the PC lists them. */
export function present(rows, sort, hidden) {
  const out = rows.filter((r) => hidden || !String(r.name).startsWith('.'));
  const key = sort === 'size' ? (r) => -(r.size || 0) : sort === 'mtime' ? (r) => -(r.mtime || 0) : null;
  return out.sort((a, b) => (b.dir ? 1 : 0) - (a.dir ? 1 : 0) || (key ? key(a) - key(b) : 0) || cmpName(a, b));
}

const IMAGE = new Set(['png', 'jpg', 'jpeg', 'gif', 'bmp', 'webp', 'avif']);
const TEXT = new Set(['txt', 'md', 'markdown', 'log', 'json', 'jsonl', 'csv', 'tsv', 'xml', 'yaml', 'yml', 'toml', 'ini', 'conf', 'cfg', 'properties',
  'nfo', 'srt', 'vtt', 'ass', 'sub', 'sh', 'bash', 'py', 'js', 'mjs', 'ts', 'kt', 'kts', 'java', 'c', 'h', 'cpp', 'hpp', 'rs', 'go', 'rb', 'pl', 'lua',
  'css', 'html', 'htm', 'sql', 'diff', 'patch', 'gradle', 'tex', 'rst', 'org', 'cue', 'm3u', 'pls', 'gitignore', 'env', 'service', 'desktop']);
export const extOf = (name) => (String(name).includes('.') ? lower(String(name).slice(String(name).lastIndexOf('.') + 1)) : '');

/** What an entry previews as: a folder, a picture (in colour, the original), a PDF (page renditions, A.8), an EPUB
 *  (Open in Reader), text (read in chunks), or other (Download; it may still be shown as text on request). */
export function kindOf(row) {
  if (row.dir) return 'dir';
  const e = extOf(row.name);
  if (IMAGE.has(e)) return 'image';
  if (e === 'pdf') return 'pdf';
  if (e === 'epub') return 'epub';
  if (TEXT.has(e) || (e === '' && !String(row.name).startsWith('.'))) return 'text';
  return 'other';
}

// ---- blobs (spec §4.1, C§9.4): `f.<base64url(path)>`, `r=<rendition>`, `dl=1` ----

/** The UTF-8 bytes of `s` in base64url without padding (RFC 4648 §5). */
export function b64url(s) {
  let bin = '';
  for (const b of new TextEncoder().encode(String(s))) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
export const blobId = (path) => `f.${b64url(path)}`;
export function blobUrl(path, { r, dl } = {}) {
  const q = [r ? `r=${encodeURIComponent(r)}` : null, dl ? 'dl=1' : null].filter(Boolean);
  return `/blob/${blobId(path)}${q.length ? `?${q.join('&')}` : ''}`;
}
export const thumbUrl = (path) => blobUrl(path, { r: `thumb:${THUMB_PX}` });
/** A PDF page's width for a box `css` pixels wide at `dpr`: in steps of 256, at most 2048 (few renditions to cache). */
export const pdfWidth = (css, dpr = 1) => Math.max(256, Math.min(PDF_MAX, Math.ceil((Math.max(1, css) * (dpr || 1)) / 256) * 256));
/** Page `n` (1-based, as `pdftoppm -f`) of a PDF as a PNG rendition. */
export const pdfPageUrl = (path, n, width) => blobUrl(path, { r: `pdf:${n}:${width}` });

// ---- text, a chunk at a time (`files/text:<path>/at:<offset>` → today's 128 KB TextChunk) ----

export class TextDoc {
  constructor(path) {
    Object.assign(this, { path, chunks: new Map(), flying: null, failed: null });
  }
  scope(at) { return textScope(this.path, at); }
  atOf(scope) {
    const pre = `${W}/text:${this.path}/at:`;
    if (!scope.startsWith(pre)) return null;
    const n = Number(scope.slice(pre.length));
    return Number.isInteger(n) && n >= 0 ? n : null;
  }
  get lastAt() { return this.chunks.size ? Math.max(...this.chunks.keys()) : -1; }
  /** The offset after the last chunk held: its offset plus the bytes the PC CONSUMED (`bytesRead`), never the text's
   *  length (a lossy decode of a mid-character slice differs, FilesProvider.kt TextChunk). */
  get nextAt() {
    if (this.lastAt < 0) return 0;
    const c = this.chunks.get(this.lastAt);
    return this.lastAt + c.bytesRead;
  }
  /** Ended: the last chunk says the file stops, or the PC consumed nothing (asking again would read the same bytes). */
  get ended() {
    if (this.lastAt < 0) return false;
    const c = this.chunks.get(this.lastAt);
    return !c.more || !(c.bytesRead > 0);
  }
  next(near, now) {
    if (this.flying !== null) return null;
    if (this.failed) return this.failed.code === 'retry' && now - this.failed.at >= RETRY_MS ? this.failed.at0 : null;
    if (this.lastAt < 0) return 0;
    if (this.ended || !near) return null;
    return this.nextAt;
  }
  ask(at) { this.flying = at; }
  got(at, d) {
    if (this.flying === at) this.flying = null;
    if (this.failed && this.failed.at0 === at) this.failed = null;
    this.chunks.set(at, { text: String(d.text ?? ''), more: !!d.more, total: typeof d.totalBytes === 'number' ? d.totalBytes : null,
      bytesRead: typeof d.bytesRead === 'number' ? d.bytesRead : 0 });
  }
  refused(at, a, now) {
    if (this.flying === at) this.flying = null;
    this.failed = { at0: at, code: a.code, reason: a.reason || `refused (${a.code})`, at: now };
  }
  text() { return [...this.chunks.keys()].sort((a, b) => a - b).map((k) => this.chunks.get(k).text).join(''); }
  held() {
    const s = new Set(this.chunks.keys());
    if (this.flying !== null) s.add(this.flying);
    if (this.failed) s.add(this.failed.at0);
    return [...s].map((at) => this.scope(at));
  }
  /** What the end of the text says. */
  status() {
    if (this.failed) return { kind: 'failed', reason: this.failed.reason, retry: this.failed.code === 'retry' };
    if (this.flying !== null) return { kind: this.lastAt < 0 ? 'first' : 'loading' };
    if (this.lastAt >= 0 && this.ended) {
      const c = this.chunks.get(this.lastAt);
      return c.more ? { kind: 'stuck', at: this.lastAt } : { kind: 'end', total: c.total };
    }
    return { kind: 'more', read: this.nextAt, total: this.lastAt >= 0 ? this.chunks.get(this.lastAt).total : null };
  }
}

// ---- the record: the clipboard, sort, hidden and the Size row (spec §4.1; registers, the latest input wins) ----

export function clipOf(rec) {
  const v = rec && rec.value(W, 'clip');
  return v && typeof v === 'object' && (v.verb === 'copy' || v.verb === 'cut') && typeof v.path === 'string' ? v : null;
}
export const sortOf = (rec) => (SORTS.some(([s]) => s === (rec && rec.value(W, 'sort'))) ? rec.value(W, 'sort') : 'name');
export const hiddenOf = (rec) => (rec && rec.value(W, 'hidden')) === true;

/** A record op on one of Files' registers, made at input time `at` (L1 C1). */
export const regDraft = (action, name, value, at) => ({ window: W, action, targets: [name], result: value, at });
/** Whether the record's `item` already settles a register op of `value` made at `at`: it shows that value, or a later
 *  input stands. An op that changed nothing publishes no change, so the page reads the item rather than wait (L1 C1). */
export const settled = (item, value, at) => !!item && (item.at > at || JSON.stringify(item.gone ? null : item.v) === JSON.stringify(value));
// A pasted cut's clipboard is cleared by the PC's core when the move lands (S1-2 R0 §3), never by a page.

// ---- actions (spec §4.1's table; Files' minimum is full, C§10.4) ----

export const actionsOf = (desc) => new Map(((desc && desc.actions) || []).map((a) => [a.id, a]));
export const offered = (a, level) => !!a && atLeast(level, a.level || 'full');
/** Why a device cannot see Files at all, or null: Files is full as a whole (C§10.4). */
export const belowWhy = (level) => (atLeast(level, 'full') ? null : `Files needs level full on this device (this one is ${level || 'not approved'})`);
/** The actions over several selected entries (A.15: no copy or cut). */
export const BULK_FOLDER = ['trash', 'download'];
export const BULK_TRASH = ['restore', 'purge'];
/** The actions that take the PC's one job slot (spec §4.1 FilesJobs). */
export const JOB_ACTIONS = new Set(['paste', 'trash', 'restore', 'purge', 'rename', 'mkdir', 'stats', 'upload']);

export const programDraft = (action, targets, params) => ({ window: W, action, targets, ...(params ? { params } : {}) });

const many = (n) => (n === 1 ? '1 item' : `${n} items`);

/** The confirm before an action, from the spec's table, or null. Delete forever always asks twice. */
export function confirmFor(id, names) {
  const n = names.length;
  const one = n === 1 ? names[0] : null;
  if (id === 'trash') {
    return { title: one ? `Delete '${one}'?` : `Delete ${n} items?`, act: 'Move to Trash', double: false,
      body: `${one ? 'It moves' : 'They move'} to the PC's trash; Restore brings ${one ? 'it' : 'them'} back.` };
  }
  if (id === 'purge') {
    return { title: one ? `Delete '${one}' forever?` : `Delete ${n} items forever?`, act: 'Delete forever', double: true,
      body: `${one ? 'It is' : 'They are'} removed from the PC's trash and from its disk. This cannot be undone.` };
  }
  return null;
}

/** The words for a program op's answer (A.7: Sent or Refused; nothing claimed that the PC has not said). */
export function answerLine(label, ans) {
  if (!ans) return `${label}: an answer that does not decode`;
  if (ans.a === 'refused') return `${label}: ${ans.reason || `refused (${ans.code})`}`;
  if (ans.a !== 'sent' && ans.a !== 'seen') return `${label}: an unknown answer '${ans.a}'`;
  return null;
}

/** A program op sent and not answered: "Move to Trash · 3 items · sent, no answer from the PC yet · 12 s" (V3). */
export const waitingLine = (w, now) => `${w.label} · ${many(w.n)} · sent, no answer from the PC yet · ${dur(Math.max(0, now - w.at))}`;

/** The job id a program answer names, or null (the call answered at its end, or the PC runs no job for it). */
export const jobOf = (ans) => (ans && ans.result && typeof ans.result === 'object' && ans.result.job !== undefined ? String(ans.result.job) : null);

// ---- the one job slot (spec §4.1 FilesJobs; A.12) ----

export const jobsOf = (desc) => ((desc && Array.isArray(desc.jobs)) ? desc.jobs : []);
export const runningJob = (desc) => jobsOf(desc).find((j) => j.state === 'running' || j.state === 'queued') || null;

/** The slot's job's line (L1 P44: whatever its state). Running: its label, phase and fraction, its age on the PC's
 *  clock, and how long it has shown no progress. Ended: how, and when. */
export function jobLine(job, pcNow) {
  if (job.state === 'done' || job.state === 'failed') {
    return `On the PC: ${jobOutcome(job.label || 'an operation', job)}${typeof job.updatedAt === 'number' ? ` · ${when(job.updatedAt, pcNow)}` : ''}`;
  }
  const age = typeof job.startedAt === 'number' ? Math.max(0, pcNow - job.startedAt) : null;
  const still = typeof job.updatedAt === 'number' ? Math.max(0, pcNow - job.updatedAt) : 0;
  return [`On the PC: ${job.label || 'an operation'}`, job.state === 'queued' ? 'waiting for the slot' : null, job.phase || null,
    typeof job.fraction === 'number' ? pct(job.fraction) : null, age === null ? null : dur(age),
    job.state === 'running' && still >= QUIET_MS ? `no progress for ${dur(still)}` : null].filter(Boolean).join(' · ');
}

/** Why the slot's actions are dim now, or null (the PC is the judge; this is a courtesy). */
export const busyWhy = (desc) => {
  const j = runningJob(desc);
  return j ? `busy — one operation at a time (${j.label || 'an operation'} is running)` : null;
};

/** A job's end in words: done with the PC's answer, or failed with its reason. */
export function jobOutcome(label, job) {
  const said = typeof job.answer === 'string' ? job.answer : job.answer && typeof job.answer === 'object' && job.answer.text ? String(job.answer.text) : '';
  if (job.state === 'failed') return `${label}: failed — ${said || 'the PC gave no reason'}`;
  return `${label}: done${said ? ` — ${said}` : ''}`;
}

/** A stats answer's facts as [label, value] rows (size, modified, mode, owner, kind). */
export function statFacts(f) {
  if (!f || typeof f !== 'object') return [];
  const rows = [];
  if (typeof f.size === 'number') rows.push(['Size', `${bytes(f.size)} (${f.size.toLocaleString('en-US')} bytes)`]);
  const m = f.mtime ?? f.mtimeMs;
  if (typeof m === 'number') rows.push(['Modified', new Date(m).toISOString().replace('T', ' ').slice(0, 16) + ' UTC']);
  if (f.mode) rows.push(['Mode', String(f.mode)]);
  if (f.owner) rows.push(['Owner', String(f.owner)]);
  if (typeof f.dir === 'boolean') rows.push(['Kind', f.dir ? 'folder' : 'file']);
  return rows;
}

// ---- uploads (spec §2.4, §4.3; C§9.4): one file at a time — the PC's one slot ----

/**
 * The files dropped on the page, each with its state: waiting · op (the `upload` op sent) · sending (the PUT) · done ·
 * failed · cut · unsent. One at a time; the list keeps no queue past the PC's reach (`stopRest`). L1 P40: an upload
 * never replaces a file — the PC places a taken name under a numbered one and its answer names the file placed (`as`).
 */
export class Uploads {
  constructor() {
    this.items = [];
    this.seq = 0;
  }
  /** `files` [{name, size}] into `dest`. Returns the items made. */
  add(files, dest) {
    const made = [...files].map((file) => ({ id: ++this.seq, file, name: String(file.name), size: Number(file.size) || 0, dest: norm(dest),
      state: 'waiting', sent: 0, reason: null }));
    this.items.push(...made);
    return made;
  }
  active() { return this.items.find((u) => u.state === 'op' || u.state === 'sending') || null; }
  next() { return this.active() ? null : this.items.find((u) => u.state === 'waiting') || null; }
  get sending() { return this.items.some((u) => u.state === 'sending'); }
  /** Every waiting file is not sent, with the reason (said once by the caller). Returns how many. */
  stopRest(reason) {
    let n = 0;
    for (const u of this.items) if (u.state === 'waiting') { u.state = 'unsent'; u.reason = reason; n++; }
    return n;
  }
  /** The finished rows leave the list (Clear). */
  clearDone() { this.items = this.items.filter((u) => !['done', 'failed', 'cut', 'unsent'].includes(u.state)); }
}

/** One upload's row in words. */
export function uploadLine(u) {
  const of = `${bytes(u.sent)} of ${bytes(u.size)}`;
  switch (u.state) {
    case 'waiting': return 'waiting for the one before it';
    case 'op': return 'asking the PC for a place';
    case 'sending': return `${u.size ? pct(u.sent / u.size) : '0%'} · ${of}`;
    case 'done': return `uploaded${u.as && u.as !== u.name ? ` as '${u.as}'` : ''}`;
    case 'cut': return `cut off at ${of}: ${u.reason}${u.freed ? ` · ${u.freed}` : ''}`;
    default: return u.reason || u.state;
  }
}

/** The upload list's head: what became of the files, in words ("Uploads: 1 uploaded · 1 cut off · 2 not sent"). */
export function uploadsHead(items) {
  const n = (f) => items.filter(f).length;
  const parts = [[n((u) => u.state === 'done'), 'uploaded'], [n((u) => u.state === 'sending' || u.state === 'op'), 'in progress'],
    [n((u) => u.state === 'waiting'), 'waiting'], [n((u) => u.state === 'cut'), 'cut off'], [n((u) => u.state === 'failed'), 'refused'],
    [n((u) => u.state === 'unsent'), 'not sent']].filter(([k]) => k > 0);
  return `Uploads: ${parts.map(([k, w]) => `${k} ${w}`).join(' · ') || 'none'}`;
}

/** The PC's answer to a PUT (spec §2.4): 201 names the file as placed (`{name, bytes}`: a taken name is numbered, L1
 *  P40); 507 no space; anything else in words with its text. */
export function putAnswer(status, text) {
  const why = String(text || '').trim();
  if (status >= 200 && status < 300) {
    let name = null;
    try {
      const j = JSON.parse(why);
      if (j && typeof j.name === 'string' && j.name) name = j.name;
    } catch (e) {
      name = null; // said by the page: the answer named no file
    }
    return { ok: true, name };
  }
  if (status === 507) return { ok: false, reason: `not enough space on the PC${why ? `: ${why}` : ''}` };
  if (status === 401 || status === 403) return { ok: false, reason: `the PC refused this device (${status})${why ? `: ${why}` : ''}` };
  return { ok: false, reason: `the PC answered ${status}${why ? `: ${why}` : ''}` };
}

/** S1-2 R2 item 20 (review B-S4): the files of a drop, its folders left out. A folder's item reads as a directory entry
 *  (`webkitGetAsEntry`, in Firefox and Chromium); its body would fail before a byte reached the PC. A browser without
 *  entries gives the drop's file list whole (the upload's error path then lets the PC's place go). */
export function dropped(dt) {
  if (!dt) return { files: [], folders: [] };
  const items = dt.items && typeof dt.items.length === 'number' ? Array.from({ length: dt.items.length }, (_, i) => dt.items[i]) : [];
  const files = [];
  const folders = [];
  if (!items.length || !items.every((it) => it && typeof it.webkitGetAsEntry === 'function')) return { files: [...(dt.files || [])], folders };
  for (const it of items) {
    if (it.kind !== 'file') continue;
    const en = it.webkitGetAsEntry();
    if (en && en.isDirectory) folders.push(String(en.name));
    else {
      const f = it.getAsFile();
      if (f) files.push(f);
    }
  }
  return { files, folders };
}

/** The words for the folders a drop left out. */
export const foldersLeftOut = (folders) => `${folders.length === 1 ? `The folder '${folders[0]}' was` : `${folders.length} folders were`} left out of the drop: `
  + 'a folder is not uploaded. Open it on this device and drop the files inside it.';

/** A location's capacity: the used fraction and its words, or null when the PC gave no total. */
export function capacity(l) {
  const total = Number(l.total ?? l.totalBytes);
  const free = Number(l.free ?? l.freeBytes);
  if (!(total > 0) || !(free >= 0)) return null;
  return { used: Math.min(1, Math.max(0, (total - free) / total)), words: `${bytes(free)} free of ${bytes(total)}` };
}
