// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-3, piece B17 (first built as package JT part 1; the lean spec's B8–B12; CONTRACT.md §3.4, §4.2, §8, §9.2;
// TMUX.md's verdicts): Tmux in a browser, without the DOM. The sessions (waiting first, then by activity, as the
// glasses order them) and each host's health as a value counted on the PC's clock; the record's mutes and Alerts row
// with this page's ops on their way; every op's draft (keys, snippets, a typed line, raw bytes, the session actions:
// never queued, carrying the connection; L1: no expectation — tmux takes input from every surface in arrival order, the
// latest real input wins); the paste rule (a line break or a control character asks first, naming the line count); a
// live source's newest picture (B11: no numbering — one socket is ordered, a new one starts with a fresh `init`); the
// history's rows. Pure: no DOM, no clock of its own (callers pass `now`, on the PC's clock where it says so).
//
// The shapes of the PC's Tmux core this page assumes where the lean spec leaves them open are each written once: the
// program ops' params in `liveDraft` and its callers, the mute op in `muteDraft`, the live sources' names in
// `frameScope` / `streamScope`, a frame's fields in `Feed.take` and `term-render.mjs` `layout`, a rename's answer in
// `renamedOf`, a new session's answer in `newName`.

import { atLeast, hhmm } from './proto.mjs';
import { Pending as RecordPending } from './record.mjs';
import { dur, ago } from './torrents-model.mjs';

export { dur, ago };
export const W = 'tmux';
export const DESC = 'desc/tmux';
/** A picture that reaches the page this long after the PC took it is said as behind (never a silent gap). */
export const LATE_MS = 3000;
/** `LocalTmuxProvider.sendKeys`' check on a key name, which stays (spec §4.2). */
const KEY_NAME = /^[A-Za-z0-9!-~]{1,16}$/;

const str = (x) => (x === null || x === undefined ? '' : String(x));
const num = (x) => (typeof x === 'number' && Number.isFinite(x) ? x : null);
const plural = (n, one, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

// ---- addresses (C§5's Tmux row; W-04): `/w/tmux/<target>` ----

/** The address target of a session (C§5): `session:<host>:<name>`. A tmux session name holds no `:` (tmux refuses
 *  one), so the name is what follows the last `:`. */
export const target = (s) => `session:${s.host}:${s.name}`;
export const historyTarget = (s) => `history:${s.host}:${s.name}`;

/** The place an address target names: the sessions (none), a session's live view, or its history page; null for a
 *  target this page does not know. */
export function placeOf(t) {
  if (!t) return { at: 'sessions' };
  const m = /^(session|history):(.+)$/.exec(t);
  if (!m) return null;
  const i = m[2].lastIndexOf(':');
  if (i <= 0 || i === m[2].length - 1) return null;
  return { at: m[1], host: m[2].slice(0, i), name: m[2].slice(i + 1) };
}

/** The PC-served scopes (B9): the canvas, the history, the live frames of one session (B11), and its control-mode
 *  stream (B10; its source name ASSUMED: B10 names none, B11's form is `live/<window>/<src>`). */
export const canvasScope = (s) => `tmux/session:${s.host}:${s.name}`;
export const historyScope = (s) => `tmux/history:${s.host}:${s.name}`;
export const frameScope = (s) => `live/tmux/frame/${s.host}/${s.name}`;
export const streamScope = (s) => `live/tmux/stream/${s.host}/${s.name}`;

// ---- the description: sessions, hosts, keys ----

/** `activity` in ms on the PC's clock (B9), or null. */
export function activityMs(a) {
  const n = num(a);
  return n === null || n <= 0 ? null : n;
}

/** The sessions in today's order (TMUX.md: waiting sessions pinned atop the list), then the most recently active. */
export function sessionsOf(desc) {
  const list = desc && Array.isArray(desc.sessions) ? desc.sessions.slice() : [];
  return list.sort((a, b) => (b.waiting ? 1 : 0) - (a.waiting ? 1 : 0) || (activityMs(b.activity) || 0) - (activityMs(a.activity) || 0)
    || str(a.host).localeCompare(str(b.host)) || str(a.name).localeCompare(str(b.name)));
}

export function findSession(desc, host, name) {
  return ((desc && desc.sessions) || []).find((s) => s.host === host && s.name === name) || null;
}

/** A host's health as one line, counted on the PC's clock from its values (C§8: no text carries an age baked in). */
export function healthLine(host, pcNow) {
  const hl = (host && host.health) || {};
  const last = num(hl.lastOkMs);
  const since = last && last > 0 ? ` · last answered ${ago(last, pcNow)}` : '';
  switch (hl.state) {
    case 'ok':
    case undefined:
      return 'answering';
    case 'quiet':
      return last ? `quiet ${dur(Math.max(0, pcNow - last))}` : 'quiet';
    case 'failing':
      return `not answering${hl.error ? `: ${hl.error}` : ''}${since}`;
    default:
      return `${hl.state}${hl.error ? `: ${hl.error}` : ''}${since}`;
  }
}

/** The one line under a session's name in the list. */
export function sessionLine(s, pcNow) {
  const at = activityMs(s.activity);
  return [plural(num(s.windows) || 0, 'window'), s.attached ? 'attached' : null, at ? `active ${ago(at, pcNow)}` : null].filter(Boolean).join(' · ');
}

/** The quick keys and snippets the PC serves (TmuxConfig: never a list of the page's own), and those it cannot send. */
export function keysOf(desc) {
  const k = (desc && desc.keys) || {};
  const quick = Array.isArray(k.quick) ? k.quick.map(str) : [];
  return { quick: quick.filter((n) => KEY_NAME.test(n)), bad: quick.filter((n) => !KEY_NAME.test(n)), snippets: Array.isArray(k.snippets) ? k.snippets.map(str).filter(Boolean) : [] };
}

/** A tmux key name in words for its button (the glasses' labels: Esc, Ctrl-C). */
export function keyLabel(name) {
  const n = str(name);
  const named = { Escape: 'Esc', BSpace: 'Backspace', BTab: 'Shift-Tab', PPage: 'Page up', NPage: 'Page down', DC: 'Delete', IC: 'Insert', Space: 'Space' };
  if (named[n]) return named[n];
  const m = /^([CMS])-(.+)$/.exec(n);
  if (m) return `${{ C: 'Ctrl', M: 'Alt', S: 'Shift' }[m[1]]}-${m[2].length === 1 ? m[2].toUpperCase() : keyLabel(m[2])}`;
  return n;
}

// ---- the record's rows and this page's ops not yet in it ----

export const muteName = (s) => `mute.${s.host}/${s.name}`;

/** This page's Tmux values on their way (`record.mjs` `Pending`). */
export class Pending extends RecordPending {
  constructor() { super(W); }
}

/** Muted: this page's op first, then the record, then the description's own value. */
export function mutedOf(s, rec, pend) {
  const n = muteName(s);
  if (pend && pend.has(n)) return !!pend.get(n);
  const v = rec && rec.value(W, n);
  return v === undefined ? !!s.muted : !!v;
}

export function rowValue(row, rec, pend) {
  if (pend && pend.has(row.id)) return pend.get(row.id);
  const v = rec && rec.value(W, row.id);
  return v === undefined ? row.value : v;
}
export const optionsOf = (row) => (row.options || []).map((o) => (o && typeof o === 'object' ? [o.id, o.label ?? str(o.id)] : [o, str(o)]));

/** The mute op (B8's `mute.<host>/<name>`, bool; B9 names one record action `mute` — ASSUMED for both ways). */
export const muteDraft = (s, on) => ({ window: W, action: 'mute', targets: [muteName(s)], result: on });
export const setDraft = (row, value) => ({ window: W, action: 'set', targets: [row.id], result: value });

// ---- actions (spec §4.2: every one at level full; the window's minimum is full) ----

export const actionsOf = (desc) => new Map(((desc && desc.actions) || []).map((a) => [a.id, a]));
/** An action the PC lists and this device's level reaches; one the PC does not list is not offered. */
export const offered = (a, level) => !!a && atLeast(level, a.level || 'full');
export const belowWhy = (level) => (atLeast(level, 'full') ? null : `Tmux needs a device at level full (this one is ${level || 'not approved'})`);

/** A program op on a session (B9: never queued; `conn` and `k` are put on by the page's shell). Params (ASSUMED: B9
 *  names the actions only): `key` (a tmux key name), `snippet`, `text` (a typed line, a new name), `data` (the bytes a
 *  terminal produced, as text), `window` (an index). */
export function liveDraft(action, s, params) {
  const d = { window: W, action, targets: [target(s)] };
  if (params) d.params = params;
  return d;
}
export const keyDraft = (s, key) => liveDraft('key', s, { key });
export const snippetDraft = (s, snippet) => liveDraft('snippet', s, { snippet });
export const lineDraft = (s, text) => liveDraft('line', s, { text });
export const rawDraft = (s, data) => liveDraft('raw', s, { data });
export const renameDraft = (s, text) => liveDraft('rename', s, { text });
export const endDraft = (s) => liveDraft('end', s);
export const selectDraft = (s, idx) => liveDraft('select', s, { window: idx });
export const newDraft = (host) => ({ window: W, action: 'new', targets: [str(host)] });

/** A program op's answer in words, or null when it needs none. */
export function answerLine(label, ans) {
  if (!ans) return `${label}: an answer that does not decode`;
  if (ans.a === 'refused') return `${label}: ${ans.code === 'ended' ? 'not known whether it ran: the connection ended before its answer; look at the session' : ans.reason || `refused (${ans.code})`}`;
  if (ans.a !== 'sent' && ans.a !== 'seen') return `${label}: an unknown answer '${ans.a}'`;
  return null;
}

/** A rename's answer names the session's new address (B9: "its answer carries `renamed{from, to}`"; ASSUMED on
 *  `result`, `to` an address target). */
export function renamedOf(ans) {
  const r = ans && ans.result && (ans.result.renamed || ans.result);
  const to = r && placeOf(str(r.to));
  return to && to.at === 'session' ? to : null;
}

/** A new session's name from its answer (ASSUMED on `result`, as the name or `{name}`), or null. */
export function newName(ans) {
  const r = ans && ans.result;
  const name = typeof r === 'string' ? r : r && r.name;
  return name ? String(name) : null;
}

/** `TmuxNames.clean`: one token of letters, digits, - and _, holding at least one letter or digit; else null. */
export function cleanName(raw) {
  const name = str(raw).trim().replace(/\s+/g, '-').replace(/[^A-Za-z0-9_-]/g, '');
  return /[A-Za-z0-9]/.test(name) ? name : null;
}

/** Keys the browser keeps for itself (it closes, opens or quits on them) that a shell reads, sent from the terminal's
 *  bar instead: label → bytes. */
export const BAR_KEYS = [['Ctrl-W', '\x17'], ['Ctrl-T', '\x14'], ['Ctrl-N', '\x0e'], ['Ctrl-Q', '\x11'], ['Ctrl-V', '\x16']];

// ---- paste (JT1's rule: a line break asks) ----

/**
 * A paste: the text as a terminal receives it (line breaks as Enter, `\r`), its line count, and whether it asks
 * first. It asks when it holds a line break (more than one line, or one line that ends in Enter and so runs) or a
 * control character other than a tab (it would act as a key). Nothing to send for an empty paste.
 */
export function pasteShape(text) {
  const norm = str(text).replace(/\r\n?/g, '\n');
  const parts = norm.split('\n');
  const endsWithBreak = parts.length > 1 && parts[parts.length - 1] === '';
  const lines = endsWithBreak ? parts.length - 1 : parts.length;
  // eslint-disable-next-line no-control-regex
  const controls = /[\x00-\x08\x0b-\x1f\x7f]/.test(norm);
  return { data: norm.replace(/\n/g, '\r'), lines, endsWithBreak, controls, asks: parts.length > 1 || controls, empty: norm.length === 0 };
}

/** The paste's confirm (C§4.3: Cancel at rest), naming the line count and the session. */
export function pasteConfirm(p, name) {
  const what = [p.endsWithBreak ? 'It ends with a line break, so its last line runs too.' : null,
    p.controls ? 'It holds control characters, which act as keys.' : null].filter(Boolean).join(' ');
  return { title: `Paste ${plural(p.lines, 'line')} into ${name}?`, body: `Each line break is sent as Enter. ${what}`.trim(), act: 'Paste', cancel: 'Cancel' };
}

/** A typed line's confirm (spec §4.2: "Run it (sends + Enter)"); Cancel keeps the line in its field. */
export const lineConfirm = (text, s) => ({ title: `Run this in ${s.name}?`, body: `“${text}” is typed into ${s.name} on ${s.host}, then Enter.`, act: 'Run it (sends + Enter)', cancel: 'Keep editing' });
export const endConfirm = (s) => ({ title: `End '${s.name}'?`, body: `This ends the tmux session ${s.name} on ${s.host} and every program running in it.`, act: 'End', cancel: 'Cancel' });

// ---- a live source (B11) ----

/**
 * One live source as this page holds it: the newest picture only (a page keeps no queue of frames) and what is wrong
 * with it. `got(m, pcNow)` takes one `live` message (`init` | `frame` | `data` | `end`) and says what the page does:
 * `draw`, `data` (stream bytes for the terminal), `end`, or null. A frame is `{lines, cols, rows, cursor{x, y,
 * visible}, alternate, capturedAt}` (ASSUMED from JT1: B9 says "its lines as colour runs" only).
 */
export class Feed {
  constructor() { this.reset(); }
  reset() {
    this.frame = null;
    this.stale = null; // null · 'reconnect': the last picture is old until the fresh `init`
    this.ended = null;
    this.renamed = null;
    this.lateMs = 0;
    this.drawn = true;
    this.skipped = 0; // pictures replaced by a newer one before this page drew them, since the last draw
    this.lastSkipped = 0; // …at the last draw (what the status line says)
  }
  got(m, pcNow) {
    // S1-3 fix C2: the PC let this view go (no ping reached it) on a socket still up — the session goes on: ask again
    if (m.end && typeof m.end === 'object' && m.end.again) return 'again';
    if (m.end) {
      const e = typeof m.end === 'object' ? m.end : { reason: m.end };
      this.ended = str(e.reason) || 'the PC ended it';
      // ASSUMED: an end for a rename names the new address (B10 reads `%session-renamed`; its message is not named)
      this.renamed = e.to ? placeOf(str(e.to)) : null;
      return 'end';
    }
    if (m.init) {
      // an init may carry the PC's last picture of an idle session: its age is not lateness
      this.take(m.init, null);
      this.stale = null;
      return 'draw';
    }
    if (m.frame) {
      this.take(m.frame, pcNow);
      return 'draw';
    }
    if (m.data !== undefined) return 'data';
    return null;
  }
  /** A picture to draw; a frame (pushed when the pane changed, so taken just before) measures how late it came. */
  take(f, pcNow) {
    if (!this.drawn) this.skipped++;
    this.frame = f;
    this.drawn = false;
    this.lateMs = pcNow !== null && num(f.capturedAt) !== null ? Math.max(0, pcNow - f.capturedAt) : 0;
  }
  /** The newest picture was drawn: the pictures it replaced unseen are said until the next draw. */
  drew() {
    this.lastSkipped = this.skipped;
    this.skipped = 0;
    this.drawn = true;
  }
}

/** The pictures' state in words for the line above the terminal. `reach` = {ok, since}; times on the PC's clock. */
export function feedLine(feed, { reach, pcNow, pcOffset = 0 }) {
  const f = feed.frame;
  const taken = f && num(f.capturedAt) !== null ? f.capturedAt : null;
  const at = taken !== null ? ` · the picture from ${hhmm(taken - pcOffset)} (${ago(taken, pcNow) || 'now'})` : '';
  if (feed.ended) return { cls: 'bad', text: `This session ended: ${feed.ended}.` };
  if (reach && !reach.ok) return { cls: 'bad', text: `PC not answering since ${hhmm(reach.since)}: nothing is sent${at}.` };
  if (feed.stale === 'reconnect') return { cls: 'warn', text: `Connected again: asking the PC for a fresh picture${at}.` };
  if (!f) return { cls: 'note', text: 'Waiting for the first picture from the PC…' };
  const skipped = feed.lastSkipped ? ` · ${plural(feed.lastSkipped, 'picture')} came faster than this page draws; the newest is shown` : '';
  if (feed.lateMs > LATE_MS) return { cls: 'warn', text: `Behind: this picture reached the page ${dur(feed.lateMs)} after the PC took it${skipped}.` };
  return { cls: 'note', text: `Live${skipped}` };
}

// ---- history (B9: the 1,000-line capture as lines of colour runs) ----

/** History's rows as runs (B9: `{capturedAt, lines: [[{t, fg, bg, fl}]]}`). */
export function historyRows(desc) {
  if (!desc || !Array.isArray(desc.lines)) return [];
  return desc.lines.map((l) => (Array.isArray(l) ? l : [{ t: str(l) }]));
}

/** The history's head line. */
export function historyLine(desc, rows, pcOffset) {
  const at = desc && num(desc.capturedAt) !== null ? ` · taken at ${hhmm(desc.capturedAt - pcOffset)}` : '';
  return `${plural(rows.length, 'line')}${at}. The browser's own find (Ctrl-F) searches them.`;
}
