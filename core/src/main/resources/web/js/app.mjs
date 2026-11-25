// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-1, package P7 (the spec's §2 P7 "Shell"; INTERFACES.md W-01, W-02, W-04, W-07, W-11, W-12, W-18, W-21,
// W-22, W-24, W-25; CONTRACT.md §9, §10): the web client's shell. One connection to the PC's web listener: the
// waiting screen with its code until the device is approved, then the `app` lane — Main's cards, the record's Reader
// entries, the views' scopes. A rail on the desk, a bottom bar on the phone; an address per window and place with back
// and forward; one focus model (arrows, Enter, Back). Out of reach, the page shows what it last had under "PC not
// answering since" and every action is dim; it keeps no queue. A new bundle on the PC, or a lane version it refuses,
// reloads the page. L1 C1: every op carries `at`, the time of the input on the PC's clock.

import { Conn, RETRY_MS } from './conn.mjs';
import * as P from './proto.mjs';
import { ClientRecord } from './record.mjs';
import { Stream, safeStorage, DEVICE_KEY } from './stream.mjs';
import * as A from './address.mjs';
import { h, icon, toastsToDrop, keepFocus } from './dom.mjs';
import { nextFocus } from './focus.mjs';
import { confirmShape, askText } from './confirm.mjs';
import { NoticeDesk, WINDOW as NOTICES, PREFIX as NOTICE_PREFIX, dismissDraft, readDraft } from './notices.mjs';
import { homeView } from './views/home.mjs';
import { readerView } from './views/reader.mjs';
import { torrentsView } from './views/torrents.mjs';
import { filesView } from './views/files.mjs';
import { feedView } from './views/feed.mjs';
import { tmuxView } from './views/tmux.mjs';
import { devicesView } from './views/devices.mjs';
import { healthView, SCOPE as HEALTH } from './views/health.mjs';
import { settingsView, applyLooks } from './views/settings.mjs';

let ls = null;
try {
  ls = window.localStorage;
} catch (e) {
  ls = null;
}
const store = safeStorage(ls);
applyLooks(store);
const stream = new Stream(store, (n) => crypto.getRandomValues(new Uint8Array(n)));

/** The page's state. The views read it through `ctx`. */
const S = {
  phase: 'connecting', // connecting · pending · open · ended
  code: null,
  endText: null,
  welcome: null,
  level: null,
  device: store.get(DEVICE_KEY),
  conn: null,
  reach: { ok: false, since: Date.now() },
  record: new ClientRecord(),
  recordReady: false,
  descs: new Map(),
  names: {},
  bundleHash: null,
  pcOffset: 0, // the PC's clock less this browser's, from the welcome's `pcClock`
};
/** Now on the PC's clock (ruling §5: a page's `at` is the PC's time, so a TV with a wrong clock orders right). */
const pcNow = () => Math.round(Date.now() + S.pcOffset);

// S1-2 Q3w: the PC's notices have a record copy of their own: each scope's snapshot names its own seq, so a copy shared
// with Reader's would pass the other scope's changes over.
const noticeRec = new ClientRecord();
const desk = new NoticeDesk(store, stream.id);
const noticeToasts = new Map(); // id → its toast
let noticeWake = null;

const subs = new Map(); // the views' PC-served scopes → their need (S1-2 Q4w) or null, sent again at each welcome
// S1-3 (B11, B17): the views' live sources (`live/<window>/<src>`) → {need, onFrame}; asked again at each welcome, and
// the PC answers each with a fresh `init` (no numbering: one socket is ordered, a new one starts over)
const lives = new Map();
// S1-2 Q4w: a converted window's record scope beside Reader's (Torrents' recents and rows), each with its own copy for
// the reason the notices have theirs; kept across the view's visits (a return draws it until its new snapshot)
const recs = new Map(); // window → ClientRecord
const recSubs = new Set(); // the windows whose record scope a view holds now
const queued = new Map(); // n → {window, onAnswer}
const connOps = new Map(); // k → onAnswer
let nextK = 1;
const saidOnce = new Set();
/** S1-2 Q1 (F2b 5): the socket ended; the reach that comes back with the next socket is handed to the view from that
 *  socket's `welcome`, after the subscriptions, never before them. */
let reachAtWelcome = false;

const el = {
  app: document.getElementById('app'),
  reach: document.getElementById('reach'),
  rail: document.getElementById('rail'),
  main: document.getElementById('main'),
  toasts: document.getElementById('toasts'),
  dialog: document.getElementById('confirm'),
};

// ---- saying things (no silent failures) ----

function say(text, opts = {}) {
  const t = h('div', { class: 'toast' + (opts.keep ? ' keep' : ''), role: 'status' },
    opts.from ? h('span', { class: 'from', text: opts.from }) : null,
    h('span', { class: 'msg', text }),
    h('button', { class: 'x', 'aria-label': 'dismiss', on: { click: () => t.remove() } }, '×'));
  place(t, opts.keep);
}

function place(t, keep) {
  el.toasts.append(t);
  // ruling 2.3 (12): the cap never pushes out a kept toast (an urgent notice, an end) nor the one just said
  const all = [...el.toasts.children];
  for (const i of toastsToDrop(all.map((c) => c.classList.contains('keep')), 4)) dropToast(all[i]);
  if (!keep) setTimeout(() => dropToast(t), 12000);
}

/** A toast leaves; a notice's leaving here sends nothing (only Dismiss and the tap-through do). */
function dropToast(t) {
  t.remove();
  const id = t.getAttribute('data-notice');
  if (id !== null && noticeToasts.get(id) === t) {
    noticeToasts.delete(id);
    desk.down(id);
  }
}

/** Says `text` once per `tag` until the PC answers again (an outage's line is not repeated at every save point). */
function sayOnce(tag, text, opts) {
  if (saidOnce.has(tag)) return;
  saidOnce.add(tag);
  say(text, opts);
}

// ---- the connection ----

function pageName() {
  const ua = navigator.userAgent || '';
  const d = navigator.userAgentData;
  const platform = (d && d.platform) || (/Android/.test(ua) ? 'Android' : /Linux/.test(ua) ? 'Linux' : /Windows/.test(ua) ? 'Windows' : 'a computer');
  const mobile = d ? d.mobile : /Mobi/.test(ua);
  return `browser on ${platform}${mobile ? ' (phone)' : ''}`;
}

const conn = new Conn({
  url: `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`,
  hello: () => P.hello({ stream: stream.id, name: pageName(), replaces: store.get(DEVICE_KEY) }),
  onFrame,
  onReach,
  WS: WebSocket,
});

function send(f) {
  return conn.send(f);
}

function onFrame(f) {
  switch (f.t) {
    case 'welcome': return welcome(f);
    case 'pending':
      S.phase = 'pending';
      S.code = f.code;
      return render();
    case 'approved': return redeem(f.claim);
    case 'snap': return f.desc ? desc(f.scope, f.desc) : f.scope === NOTICES ? noticeSnap(f) : recs.has(f.scope) ? winRecSnap(f) : recordSnap(f);
    case 'chg': return f.scope === NOTICES ? noticeChg(f) : recs.has(f.scope) ? winRecChg(f) : recordChg(f);
    case 'ans': return answer(f);
    case 'live': return live(f);
    case 'end': return ended(f);
    case 'closed': return closed();
    case 'undecodable': return say(`a message from the PC did not decode (${f.detail})`, { from: 'Damage' });
    default:
      // protocol growth stays additive: said once per kind
      if (!saidOnce.has(`kind ${f.t}`)) {
        saidOnce.add(`kind ${f.t}`);
        console.warn(`the PC sent an unknown message '${f.t}'; ignored`);
      }
  }
}

function welcome(f) {
  const hash = f.bundle && f.bundle.hash;
  if (S.bundleHash && hash && hash !== S.bundleHash) {
    // C§9.5: the PC serves a newer bundle; this page is the old one
    location.reload();
    return;
  }
  S.bundleHash = hash || S.bundleHash;
  const first = S.welcome === null;
  S.phase = 'open';
  S.welcome = f;
  S.level = f.level;
  S.device = f.device;
  S.conn = f.conn;
  S.pcOffset = typeof f.pcClock === 'number' ? f.pcClock - Date.now() : 0;
  store.set(DEVICE_KEY, f.device);
  if (store.failed) sayOnce('storage', `This browser keeps no local storage (${store.failed}): its looks and its stream last only this visit.`, { from: 'Damage' });
  send(P.sub('reader'));
  send(P.sub('desc/main'));
  // R-b H3: the PC's health record, for home's one line and the Health page (level view, as Main's description)
  send(P.sub(HEALTH));
  // a PC that keeps no notice record (an older build) is not asked for one
  if (f.contracts && f.contracts[NOTICES] !== undefined) send(P.sub(NOTICES));
  for (const w of recSubs) send(P.sub(w));
  if (P.atLeast(S.level, 'full') && !subs.has('devices')) subs.set('devices', null);
  for (const [s, need] of subs) send(P.sub(s, need));
  for (const [s, l] of lives) send(P.sub(s, l.need));
  const back = reachAtWelcome;
  reachAtWelcome = false;
  if (first) route();
  else if (view && view.onWelcome) view.onWelcome();
  // F2b 5: what a view sends on the reach coming back (Reader's place read meanwhile) goes now, after the subscriptions
  if (!first && back && view && view.onReach) view.onReach();
  drawRail();
  drawReach();
}

async function redeem(claim) {
  let r;
  try {
    r = await fetch('/pair/claim', { method: 'POST', body: claim, credentials: 'same-origin' });
  } catch (e) {
    say(`the approval could not be taken (${e.message}); the page asks again`, { from: 'Damage' });
    return conn.drop(); // L1 R2 B3: a failed claim paces like any failed connection (a PC that cannot write its table)
  }
  if (r.status === 200) {
    const j = await r.json();
    store.set(DEVICE_KEY, j.device);
    say(`approved at level ${j.level}`, { from: 'Damage' });
    return restart();
  }
  say(r.status === 403 ? 'this approval is no longer valid; the page asks again' : `the approval was answered ${r.status}: ${await r.text()}`, { from: 'Damage' });
  conn.drop();
}

function restart() {
  conn.stop();
  S.phase = 'connecting';
  conn.start();
}

function ended(f) {
  S.endText = P.endWords(f.reason, f.detail);
  say(S.endText, { from: 'Damage', keep: !P.endRetries(f.reason) });
  if (!P.endRetries(f.reason)) {
    conn.stop();
    S.phase = 'ended';
    S.endReason = f.reason;
    render();
    // L1: refused for its lane version (a deploy moved it): the page the PC serves now is loaded on the pacing, so a
    // page left open on the TV comes back by itself
    if (f.reason === 'proto') setTimeout(() => location.reload(), RETRY_MS);
  } else if (f.reason === 'revoked' || f.reason === 'replaced') {
    S.welcome = null;
    if (view && view.unmount) view.unmount();
    view = null;
  }
}

function closed() {
  // C§4.4: an op left unanswered at its connection's end — not known whether it ran; the state says
  const lost = [...queued.values()];
  queued.clear();
  for (const q of lost) if (q.onAnswer) q.onAnswer({ a: 'refused', code: 'ended', reason: 'not known whether it ran: the connection ended before its answer; see the state' });
  const lostConn = [...connOps.values()];
  connOps.clear();
  for (const cb of lostConn) cb({ a: 'refused', code: 'ended', reason: 'not known whether it ran: the connection ended before its answer; see the state' });
  reachAtWelcome = true;
  if (S.phase === 'pending') {
    // ruling 2.3: the code ended with its socket (a PC restart forgets its waiting pages): never shown as live
    S.phase = 'connecting';
    render();
  }
  drawReach();
}

function onReach(r) {
  // S1-2 JW0 (seen in headless Firefox): the page's reach is the connection's; every dim and the bar read it
  S.reach = { ok: r.ok, since: r.since };
  // C§9.3: say when it is back; an outage's lines are said again in the next one
  if (r.ok) {
    if (saidOnce.has('outage')) say('The PC answers again.', { from: 'Damage' });
    for (const t of ['outage', 'away', 'reader-unsaved']) saidOnce.delete(t);
  } else if (S.welcome) saidOnce.add('outage');
  drawReach();
  // F2b 5: back on a new socket, the view hears it from that socket's welcome (after the subscriptions);
  // out of reach, or back on the same socket (a pong), it hears it now (N7: its dims follow the reach)
  if (r.ok && reachAtWelcome) return;
  if (view && view.onReach) view.onReach();
}

// ---- the record and the descriptions ----

function recordSnap(f) {
  const changed = S.record.snap(f, ['rec.reader.']);
  S.recordReady = true;
  if (view && view.onRecord) view.onRecord(changed);
}

function recordChg(f) {
  const changed = S.record.chg(f);
  if (changed === null) {
    // another epoch: a fresh snapshot, never a silent gap
    send(P.sub('reader'));
    return;
  }
  if (changed.size && view && view.onRecord) view.onRecord(changed);
}

/** S1-2 Q4w: a converted window's own record scope (`torrents`); its view hears it as `onWindowRecord`. */
function winRecSnap(f) {
  const changed = recs.get(f.scope).snap(f, [`rec.${f.scope}.`]);
  if (view && view.onWindowRecord) view.onWindowRecord(f.scope, changed);
}

function winRecChg(f) {
  const changed = recs.get(f.scope).chg(f);
  if (changed === null) return send(P.sub(f.scope)); // another epoch: a fresh snapshot, never a silent gap
  if (changed.size && view && view.onWindowRecord) view.onWindowRecord(f.scope, changed);
}

// ---- the PC's notices as toasts (S1-2 Q3w; C§7; decision A.5): no tray and no history in this build ----

function noticeSnap(f) {
  noticeRec.snap(f, [NOTICE_PREFIX]);
  reviewNotices();
}

function noticeChg(f) {
  const changed = noticeRec.chg(f);
  if (changed === null) return send(P.sub(NOTICES)); // another epoch: a fresh snapshot, never a silent gap
  if (changed.size) reviewNotices();
}

/** Pops what the desk says, takes down what moved elsewhere, and wakes at a quiet-hours hold's end. */
function reviewNotices() {
  const now = pcNow();
  const r = desk.review(noticeRec.epoch, noticeRec.items, now);
  for (const id of r.withdraw) {
    // dismissed, acknowledged or cleared on another screen, or a newer occurrence: it leaves without input
    const t = noticeToasts.get(id);
    noticeToasts.delete(id);
    if (t) t.remove();
  }
  for (const id of r.bad) sayOnce(`notice ${id}`, `A notice from the PC (${id}) has no occurrence number, so this page cannot show it.`, { from: 'Damage' });
  for (const n of r.pop) noticeToast(n);
  clearTimeout(noticeWake);
  noticeWake = r.wake === null ? null : setTimeout(reviewNotices, Math.min(Math.max(r.wake - now, 0), 2 ** 31 - 1));
}

function noticeToast(n) {
  const v = n.v;
  const card = ((S.descs.get('desc/main') || {}).cards || []).find((c) => c.window === v.app);
  const from = (card && card.name) || (VIEWS[v.app] && VIEWS[v.app].label) || (typeof v.app === 'string' && v.app ? v.app[0].toUpperCase() + v.app.slice(1) : 'Damage');
  const to = { window: v.app, target: v.target || null };
  // a refusal is said
  const answered = (what) => (a) => {
    if (a && a.a === 'refused') say(`${what} “${v.body}”: ${P.answerWords(a)}`, { from });
  };
  const urgent = v.urgency === 'URGENT';
  const t = h('div', { class: 'toast notice' + (urgent ? ' keep' : ''), role: 'status', 'data-notice': n.id },
    h('div', { class: 'n-head' }, h('span', { class: 'from', text: from }),
      typeof v.raisedAt === 'number' ? h('span', { class: 'when', text: P.hhmm(v.raisedAt) }) : null),
    h('a', { class: 'n-body', href: A.format(to), text: String(v.body), on: { click: (e) => {
      // the tap-through: marked read on every screen, then the window that raised it
      e.preventDefault();
      ctx.sendQueued(readDraft(n), answered('Open'));
      dropToast(t);
      go(to);
    } } }),
    h('button', { class: 'act', on: { click: () => {
      // one op; out of reach it is not sent (said) and the toast stays
      if (ctx.sendQueued(dismissDraft(n), answered('Dismiss'))) dropToast(t);
    } } }, 'Dismiss'));
  const old = noticeToasts.get(n.id);
  if (old) old.remove();
  noticeToasts.set(n.id, t);
  place(t, urgent);
}

/** S1-3 (B11): a message of a live source (`live{src, init | frame | data | end}`) to the view that holds it. One for a
 *  source this page let go was on its way when it did: dropped here (the PC detaches the source at `unsub`). */
function live(f) {
  const l = lives.get(f.src);
  if (l) l.onFrame(f);
}

function desc(scope, d) {
  S.descs.set(scope, d);
  if (scope === 'desc/main') S.names = d.who || {};
  if (scope === 'desc/main' || scope === 'devices') drawRail();
  if (view && view.onDesc) view.onDesc(scope, d);
}

function answer(f) {
  const a = f.answer;
  if (f.scope !== undefined && f.stream === undefined && f.conn === undefined) {
    // a subscription refused (a level, different builds, a scope that names nothing)
    if (view && view.onRefused && view.onRefused(f.scope, a)) return;
    say(`${f.scope}: ${P.answerWords(a)}`, { from: 'Damage' });
    return;
  }
  if (f.stream !== undefined) {
    const q = queued.get(f.n);
    queued.delete(f.n);
    // N2: a `retry` in the page's words (it keeps no queue), never the PC's "kept and sent again"; an answer whose op
    // this page no longer holds is said, never dropped (fix round 2, ruling 2.3)
    const given = P.pageWords(a);
    if (q && q.onAnswer) q.onAnswer(given);
    else if (P.answerWords(given)) say(P.answerWords(given), { from: q ? q.window : 'Damage' });
    return;
  }
  if (f.conn !== undefined) {
    const cb = connOps.get(f.k);
    connOps.delete(f.k);
    // S1-2 Q4w: a program op's `result` (an added torrent's name) on the answer or beside it (spec §2.4, §6 RC)
    if (cb) cb(a && f.result !== undefined && a.result === undefined ? { ...a, result: f.result } : a);
    return;
  }
  say(`the PC refused a message of this page: ${P.answerWords(a)}`, { from: 'Damage' });
}

// ---- what the views use ----

/** Why an action needing `need` cannot be taken now, or null: not connected, the PC out of reach, the level. */
function canAct(need) {
  if (S.phase !== 'open') return 'not connected to the PC';
  if (!S.reach.ok) return `PC not answering since ${P.when(S.reach.since)}`;
  return P.levelWhy(S.level, need);
}

const ctx = {
  S,
  store,
  stream,
  pcNow,
  say,
  sayOnce,
  canAct,
  levelWhy: (need) => P.levelWhy(S.level, need),
  desc: (scope) => S.descs.get(scope),
  /** Subscribes (or asks again: a refused page's retry) to a PC-served scope; `need` for a live source (spec §2.3). */
  sub(scope, need = null) {
    subs.set(scope, need);
    if (S.phase === 'open') send(P.sub(scope, need));
  },
  /** S1-2 Q4w: a held scope's need changed (the page hidden or shown, its Poll row): the subscription is sent again
   *  with it, the PC's viewer table taking the newer need (spec §2.3). */
  need(scope, need) {
    if (!subs.has(scope) || JSON.stringify(subs.get(scope)) === JSON.stringify(need)) return;
    subs.set(scope, need);
    if (S.phase === 'open') send(P.sub(scope, need));
  },
  unsub(scope) {
    if (!subs.delete(scope)) return;
    S.descs.delete(scope);
    if (S.phase === 'open') send(P.unsub(scope));
  },
  /** S1-3 (B11): a live source, its messages to `onFrame`. Called again (a new need: the page hidden or shown) it is
   *  sent again; the PC answers a fresh `init`. */
  live(scope, need, onFrame) {
    lives.set(scope, { need, onFrame });
    if (S.phase === 'open') send(P.sub(scope, need));
  },
  unlive(scope) {
    if (lives.delete(scope) && S.phase === 'open') send(P.unsub(scope));
  },
  /** S1-2 Q4w: a converted window's record copy, and its subscription while a view holds it. */
  rec(window) {
    if (!recs.has(window)) recs.set(window, new ClientRecord());
    return recs.get(window);
  },
  watch(window) {
    ctx.rec(window);
    recSubs.add(window);
    if (S.phase === 'open') send(P.sub(window));
  },
  unwatch(window) {
    if (recSubs.delete(window) && S.phase === 'open') send(P.unsub(window));
  },
  /** Whether this bundle has a page for a window (Open in Files before Files' page exists). */
  hasView: (w) => Object.prototype.hasOwnProperty.call(VIEWS, w),
  /** A queued op (a page keeps no queue: out of reach it is refused here, said once). False = not sent. S1-2 Q4w:
   *  `opts.need`, the action's level (default view); the PC checks it again at apply. `draft.at`: the input's time
   *  when it was earlier than the send (Reader's place at its save point), else now. */
  sendQueued(draft, onAnswer, opts = {}) {
    const why = canAct(opts.need || 'view');
    if (why) {
      if (!opts.quiet) sayOnce('away', `Not sent: ${why}. A page keeps no queue; act again when the PC answers.`, { from: draft.window });
      return false;
    }
    const frame = P.queuedOp({ stream: stream.id, n: stream.next(), ...draft, at: draft.at ?? pcNow() });
    if (!send(frame)) {
      if (!opts.quiet) say('Not sent: the connection to the PC is down.', { from: draft.window });
      return false;
    }
    queued.set(frame.op.n, { window: draft.window, onAnswer });
    return true;
  },
  /** A never-queued op of this connection (the device table). */
  sendConn(draft, onAnswer) {
    const why = canAct('full');
    if (why) {
      say(`Not sent: ${why}.`, { from: draft.window });
      return false;
    }
    const k = nextK++;
    if (!send(P.connOp({ conn: S.conn, k, at: pcNow(), ...draft }))) {
      say('Not sent: the connection to the PC is down.', { from: draft.window });
      return false;
    }
    connOps.set(k, onAnswer);
    return true;
  },
  /** S1-2 Q4w: a program op (spec §2.4: never queued, `conn` and `k`; its level the action's, checked again on the
   *  PC at apply). Answered once: Sent (with `result`) or Refused, or, if the connection ends first, "not known
   *  whether it ran". Returns its `k`, or null when not sent (said). */
  sendProgram(draft, need, onAnswer) {
    const why = canAct(need);
    if (why) {
      say(`Not sent: ${why}.`, { from: draft.window });
      return null;
    }
    const k = nextK++;
    if (!send(P.connOp({ conn: S.conn, k, at: pcNow(), ...draft }))) {
      say('Not sent: the connection to the PC is down.', { from: draft.window });
      return null;
    }
    connOps.set(k, onAnswer);
    return k;
  },
  confirm: (c) => confirmShape(el.dialog, c),
  /** S1-2 Q5w: a name typed behind Apply (`confirm.mjs`). */
  ask: (c) => askText(el.dialog, c),
  /** L1 P41: the socket is let go and made again (the PC lets go of what this connection held: an upload's place). */
  reconnect: () => conn.drop(),
  go,
  /** A button for an action at level `need`: dim with the reason (W-25, C§4.4) when the level, the reach or `why`
   *  stops it; a dim button stays focusable and says why on focus and on a press. */
  actionButton({ label, need, onClick, why, cls }) {
    const stop = why || canAct(need);
    return h('button', { class: (cls || '') + (stop ? ' dim' : ''), focus: true, 'aria-disabled': stop ? 'true' : null,
      'data-why': stop, title: stop, on: { click: () => (stop ? say(stop, { from: label }) : onClick()) } }, label);
  },
};

// ---- views and addresses (W-04) ----

const VIEWS = {
  main: { make: homeView, label: 'Home', icon: 'home' },
  reader: { make: readerView, label: 'Reader', icon: 'book' },
  // S1-3 (B17): Feed, at level view; a PC whose build names no Feed contract is never asked for it
  feed: { make: feedView, label: 'Feed', icon: 'feed', contract: 'feed' },
  // S1-2 Q4w: a window the PC shares only when both name the same contract version (C§9.7 item 2)
  torrents: { make: torrentsView, label: 'Torrents', icon: 'torrents', contract: 'torrents' },
  // S1-2 Q5w: Files, full as a whole (C§10.4): below full its page says so and asks the PC for nothing
  files: { make: filesView, label: 'Files', icon: 'folder', contract: 'files', need: 'full' },
  // S1-3 (B9, B17): Tmux, full as a whole (the window's minimum is full)
  tmux: { make: tmuxView, label: 'Tmux', icon: 'terminal', contract: 'tmux', need: 'full' },
  devices: { make: devicesView, label: 'Devices', icon: 'devices', need: 'full' },
  // R-b H3: every line of the health record; at level full the diagnosis below it (the Diagnosis page merged here)
  health: { make: healthView, label: 'Health', icon: 'pulse' },
  settings: { make: settingsView, label: 'Settings', icon: 'gear' },
};

let view = null;
let viewKey = null;

function go(a, opts = {}) {
  const path = A.format(a);
  if (path !== location.pathname) {
    if (opts.replace) history.replaceState(null, '', path);
    else history.pushState(null, '', path);
  }
  route(true);
}

/** Shows the address. [input]: he asked for it (a card, a link, Back, back and forward), never a page load, a reload,
 *  a restored tab or a reconnect (ruling 2.3: only input sends a reading effect such as Reader's `open`). */
function route(input = false) {
  if (S.phase !== 'open') return render();
  const r = A.parse(location.pathname) || { window: 'main' };
  const spec = VIEWS[r.window];
  if (view && viewKey === r.window && view.update && view.update(r, { input })) {
    drawRail();
    return;
  }
  if (view && view.unmount) view.unmount();
  const unshared = spec && contractWhy(spec);
  view = unshared ? unsharedView(spec, unshared) : spec ? spec.make(ctx) : missingView(r.window);
  viewKey = r.window;
  el.main.replaceChildren(view.mount(r, { input }));
  el.main.focus({ preventScroll: true });
  drawRail();
}

function missingView(w) {
  return {
    mount: () => h('section', { class: 'page' }, h('h1', { text: 'Not here yet' }),
      h('p', { class: 'note', text: `This PC serves no window “${w}” to the web in this build.` }),
      h('a', { href: '/', focus: true, on: { click: (e) => { e.preventDefault(); go({ window: 'main' }); } } }, 'Home')),
  };
}

/** S1-2 Q4w: why a window this page converts is not shared with this PC — its build names another version of the
 *  window's contract, or none (an older PC) — or null. The PC refuses the window's scopes and ops then (C§9.7). */
function contractWhy(v) {
  if (!v.contract) return null;
  const theirs = S.welcome && S.welcome.contracts ? S.welcome.contracts[v.contract] : undefined;
  if (theirs === P.CONTRACTS[v.contract]) return null;
  return theirs === undefined ? `the PC's build has no ${v.label} page yet` : `PC and this page on different builds for ${v.label}`;
}

function unsharedView(spec, why) {
  return {
    mount: () => h('section', { class: 'page' }, h('h1', { text: spec.label }),
      h('p', { class: 'note', text: `Not shown here: ${why}. A reload takes the page the PC serves now; an older PC gains it with its next deploy.` }),
      h('a', { href: '/', focus: true, on: { click: (e) => { e.preventDefault(); go({ window: 'main' }); } } }, 'Home')),
  };
}

window.addEventListener('popstate', () => route(true));

// ---- the frame: rail, reach bar, waiting screen ----

function render() {
  drawRail();
  drawReach();
  if (S.phase === 'open') return route();
  if (view && view.unmount) view.unmount();
  view = null;
  viewKey = null;
  if (S.phase === 'pending') {
    el.main.replaceChildren(h('section', { class: 'page pair' },
      h('h1', { text: 'This browser is waiting for approval' }),
      h('p', { class: 'pcode', text: S.code }),
      h('p', { text: 'Approve it on the PC with' }),
      h('pre', { class: 'cmd', text: `desktop --approve ${S.code} full` }),
      h('p', { class: 'note', text: 'or pick its level on the Devices page of a browser already approved. Levels: view (read), control (bookmarks, reset), full (the device table and everything else). This page stays open while it waits; closing it withdraws the code.' })));
  } else if (S.phase === 'ended') {
    el.main.replaceChildren(h('section', { class: 'page pair' }, h('h1', { text: 'Not connected' }),
      h('p', { text: S.endText || '' }),
      h('button', { focus: true, on: { click: () => restart() } }, S.endReason === 'denied' ? 'Ask again' : 'Connect again')));
  } else {
    el.main.replaceChildren(h('section', { class: 'page' }, h('p', { class: 'note', text: 'Connecting to the PC…' })));
  }
}

function drawRail() {
  if (S.phase !== 'open') {
    el.rail.hidden = true;
    return;
  }
  el.rail.hidden = false;
  const pending = (S.descs.get('devices') || {}).pending || [];
  // ruling 2.3 (5)'s class: the rail is drawn again with Main's description and the device table; the focus stays
  keepFocus(el.rail, 'data-key', () => el.rail.replaceChildren(...Object.entries(VIEWS).map(([w, v]) => {
    const why = contractWhy(v) || (v.need ? P.levelWhy(S.level, v.need) : null);
    const badge = w === 'devices' && pending.length ? h('span', { class: 'badge', 'aria-label': `${pending.length} waiting`, text: String(pending.length) }) : null;
    return h('a', { href: A.format({ window: w }), class: 'rail-item' + (viewKey === w ? ' current' : '') + (why ? ' dim' : ''),
      'aria-current': viewKey === w ? 'page' : null, title: why || v.label, 'data-why': why, 'data-key': `rail:${w}`,
      on: { click: (e) => { e.preventDefault(); go({ window: w }); } } }, icon(v.icon), h('span', { class: 'rail-label', text: v.label }), badge);
  })));
}

function drawReach() {
  const away = S.welcome !== null && !S.reach.ok;
  document.body.classList.toggle('away', away);
  el.reach.hidden = !away;
  if (away) el.reach.textContent = `PC not answering since ${P.when(S.reach.since)} — this page shows what it last had; actions wait until it answers.`;
}

// ---- one focus model (W-07): arrows move among the focus stops in the description's order; Enter acts; Back ----

document.addEventListener('keydown', (e) => {
  const t = e.target;
  const typing = t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT' || t.isContentEditable);
  if (el.dialog.open) return;
  if ((e.key === 'Escape' || (e.key === 'Backspace' && !typing)) && !e.altKey && !e.ctrlKey && !e.metaKey) {
    if (view && view.back && view.back()) return e.preventDefault();
    if (viewKey && viewKey !== 'main') {
      e.preventDefault();
      go({ window: 'main' });
    }
    return;
  }
  if (typing || !e.key.startsWith('Arrow')) return;
  // the reading pane scrolls with the arrows
  if (t && t.closest && t.closest('.reading')) return;
  const stops = [...el.main.querySelectorAll('[data-focus]')].filter((x) => x.offsetParent !== null);
  if (!stops.length) return;
  const boxes = stops.map((x) => {
    const r = x.getBoundingClientRect();
    return { x: r.left, y: r.top, w: r.width, h: r.height };
  });
  const i = nextFocus(boxes, stops.indexOf(document.activeElement), e.key);
  if (i >= 0 && stops[i] !== document.activeElement) {
    e.preventDefault();
    stops[i].focus();
    stops[i].scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }
});

render();
conn.start();
