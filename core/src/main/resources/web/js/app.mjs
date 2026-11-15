// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-1, package P7 (the spec's §2 P7 "Shell"; INTERFACES.md W-01, W-02, W-04, W-07, W-11, W-12, W-18, W-21,
// W-22, W-24, W-25; CONTRACT.md §9, §10): the web client's shell. One connection to the PC's web listener: the
// waiting screen with its code until the device is approved, then the `app` lane — Main's cards, the record's Reader
// entries, the views' scopes. A rail on the desk, a bottom bar on the phone; an address per window and place with back
// and forward; one focus model (arrows, Enter, Back). Out of reach, the page shows what it last had under "PC not
// answering since" and every action is dim; it keeps no queue. A new bundle on the PC reloads the page.

import { Conn } from './conn.mjs';
import * as P from './proto.mjs';
import { ClientRecord } from './record.mjs';
import { Stream, safeStorage, DEVICE_KEY, APPROVED_KEY } from './stream.mjs';
import * as A from './address.mjs';
import { h, icon, toastsToDrop, keepFocus } from './dom.mjs';
import { nextFocus } from './focus.mjs';
import { confirmShape } from './confirm.mjs';
import { homeView } from './views/home.mjs';
import { readerView } from './views/reader.mjs';
import { devicesView } from './views/devices.mjs';
import { diagView } from './views/diag.mjs';
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
};

const subs = new Set(); // the views' PC-served scopes, sent again at each welcome
const queued = new Map(); // n → {frame, onAnswer, gapped}
const connOps = new Map(); // k → onAnswer
let nextK = 1;
const saidOnce = new Set();
/** S1-2 Q1 (F2b 5): the socket ended; the reach that comes back with the next socket is handed to the view from that
 *  socket's `welcome`, after `adopt` and the subscriptions, never before them. */
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
  el.toasts.append(t);
  // ruling 2.3 (12): the cap never pushes out a kept toast (a loss notice is said once) nor the one just said
  const all = [...el.toasts.children];
  for (const i of toastsToDrop(all.map((c) => c.classList.contains('keep')), 4)) all[i].remove();
  if (!opts.keep) setTimeout(() => t.remove(), 12000);
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
    case 'snap': return f.desc ? desc(f.scope, f.desc) : recordSnap(f);
    case 'chg': return recordChg(f);
    case 'ans': return answer(f);
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
  store.set(DEVICE_KEY, f.device);
  const line = stream.adopt(f.highWater);
  if (line) say(line, { from: 'Damage' });
  if (store.failed) sayOnce('storage', `This browser keeps no local storage (${store.failed}): its looks and its stream last only this visit.`, { from: 'Damage' });
  if (f.bundle && Array.isArray(f.bundle.protoRange) && !(P.PROTO >= f.bundle.protoRange[0] && P.PROTO <= f.bundle.protoRange[1]))
    say(`this page speaks lane ${P.PROTO}; the PC's bundle speaks ${f.bundle.protoRange.join('–')}. Reload the page.`, { from: 'Damage', keep: true });
  send(P.sub('reader', S.record.cursor()));
  send(P.sub('desc/main'));
  if (P.atLeast(S.level, 'full')) subs.add('devices');
  for (const s of subs) send(P.sub(s));
  const back = reachAtWelcome;
  reachAtWelcome = false;
  if (first) route();
  else if (view && view.onWelcome) view.onWelcome();
  // F2b 5: what a view sends on the reach coming back (Reader's place read meanwhile) goes now, numbered from `adopt`
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
    return restart();
  }
  if (r.status === 200) {
    const j = await r.json();
    store.set(DEVICE_KEY, j.device);
    // F2b 4: a re-approval of a remembered device writes the same id, which tells no other tab; this value changes at
    // every approval, so a second waiting tab hears it
    store.set(APPROVED_KEY, String(Date.now()));
    say(`approved at level ${j.level}`, { from: 'Damage' });
  } else {
    say(r.status === 403 ? 'this approval is no longer valid; the page asks again' : `the approval was answered ${r.status}: ${await r.text()}`, { from: 'Damage' });
  }
  restart();
}

function restart() {
  conn.stop();
  S.phase = 'connecting';
  conn.start();
}

// ruling 2.3: another tab of this browser took its approval (its redeem writes the device id once the cookie is set):
// a waiting tab drops its code and connects with the cookie, never left waiting to be approved as a second row
window.addEventListener('storage', (e) => {
  if ((e.key === DEVICE_KEY || e.key === APPROVED_KEY) && e.newValue && S.phase === 'pending') restart();
});

function ended(f) {
  S.endText = P.endWords(f.reason, f.detail);
  say(S.endText, { from: 'Damage', keep: !P.endRetries(f.reason) });
  if (!P.endRetries(f.reason)) {
    conn.stop();
    S.phase = 'ended';
    S.endReason = f.reason;
    render();
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
  // F2b 5: back on a new socket, the view hears it from that socket's welcome (after adopt and the subscriptions);
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
    if (!q) {
      // fix round 2, ruling 2.3: an answer whose op this page no longer holds is said, never dropped; N2: a `retry`
      // in the page's words (it keeps no queue), never the PC's "kept and sent again"
      const held = a && a.a === 'refused' && a.code === 'retry' ? P.givenUp(a) : a;
      if (P.answerWords(held)) say(P.answerWords(held), { from: 'Damage' });
      return;
    }
    queued.delete(f.n);
    if (a.a === 'refused' && a.code === 'gap' && !q.gapped) {
      // the PC expects an earlier number (an op of an ended connection never landed): number from there, once per
      // op and once per gap value (ruling 2.3: two ops answered `gap` in one round trip take E and E + 1)
      const m = /expected (\d+)/.exec(a.reason || '');
      if (m) {
        // F2b 3: never a number still held for another op's resend (a `retry` keeps its number for 2 s)
        let n = stream.gapTo(Number(m[1]));
        while (queued.has(n)) n = stream.next();
        q.frame.op.n = n;
        q.gapped = true;
        queued.set(q.frame.op.n, q);
        if (send(q.frame)) return;
        queued.delete(q.frame.op.n);
      }
    }
    if (a.a === 'refused' && a.code === 'retry' && !q.retried) {
      q.retried = true;
      queued.set(f.n, q);
      setTimeout(() => {
        // N2: only an op still held; the connection's end (`closed`) has already told its view it ended
        if (queued.get(f.n) !== q) return;
        if (!send(q.frame)) {
          queued.delete(f.n);
          if (q.onAnswer) q.onAnswer({ a: 'refused', code: 'ended', reason: 'not sent again: the connection ended' });
        }
      }, 2000);
      return;
    }
    // ruling 2.3: past its one resend a retry is said in the page's words (it keeps no queue), never the PC's
    const given = a.a === 'refused' && a.code === 'retry' ? P.givenUp(a) : a;
    if (q.onAnswer) q.onAnswer(given);
    else if (P.answerWords(given)) say(P.answerWords(given), { from: q.frame.op.window });
    return;
  }
  if (f.conn !== undefined) {
    const cb = connOps.get(f.k);
    connOps.delete(f.k);
    if (cb) cb(a);
    return;
  }
  say(`the PC refused a message of this page: ${P.answerWords(a)}`, { from: 'Damage' });
}

// ---- what the views use ----

/** Why an action needing `need` cannot be taken now, or null: not connected, the PC out of reach, the level. */
function canAct(need) {
  if (S.phase !== 'open') return 'not connected to the PC';
  if (!S.reach.ok) return `PC not answering since ${P.hhmm(S.reach.since)}`;
  return P.levelWhy(S.level, need);
}

const ctx = {
  S,
  store,
  stream,
  say,
  sayOnce,
  canAct,
  levelWhy: (need) => P.levelWhy(S.level, need),
  desc: (scope) => S.descs.get(scope),
  sub(scope) {
    subs.add(scope);
    if (S.phase === 'open') send(P.sub(scope));
  },
  unsub(scope) {
    if (!subs.delete(scope)) return;
    S.descs.delete(scope);
    if (S.phase === 'open') send(P.unsub(scope));
  },
  /** A queued op (a page keeps no queue: out of reach it is refused here, said once). False = not sent. */
  sendQueued(draft, onAnswer, opts = {}) {
    const why = canAct('view');
    if (why) {
      if (!opts.quiet) sayOnce('away', `Not sent: ${why}. A page keeps no queue; act again when the PC answers.`, { from: draft.window });
      return false;
    }
    const frame = P.queuedOp({ stream: stream.id, n: stream.next(), ...draft });
    if (!send(frame)) {
      if (!opts.quiet) say('Not sent: the connection to the PC is down.', { from: draft.window });
      return false;
    }
    queued.set(frame.op.n, { frame, onAnswer });
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
    if (!send(P.connOp({ conn: S.conn, k, ...draft }))) {
      say('Not sent: the connection to the PC is down.', { from: draft.window });
      return false;
    }
    connOps.set(k, onAnswer);
    return true;
  },
  confirm: (c) => confirmShape(el.dialog, c),
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
  devices: { make: devicesView, label: 'Devices', icon: 'devices', need: 'full' },
  diag: { make: diagView, label: 'Diagnosis', icon: 'pulse', need: 'full' },
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
  view = spec ? spec.make(ctx) : missingView(r.window);
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
    const why = v.need ? P.levelWhy(S.level, v.need) : null;
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
  if (away) el.reach.textContent = `PC not answering since ${P.hhmm(S.reach.since)} — this page shows what it last had; actions wait until it answers.`;
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
