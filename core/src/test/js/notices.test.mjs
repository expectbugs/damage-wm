// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-2, package Q3w (the spec's §2.1 "Web", decision A.5): the PC's notices as toasts. First the pure module
// (notices.mjs: the cursor per stream and epoch, what pops, what is held for quiet hours, what leaves, the drafts), then
// the page shell over pagehost.mjs's DOM stand-in (app.mjs: the scope's own record copy, Dismiss, the tap-through, a
// dismiss elsewhere, the hold's end on the PC's clock). Record values are in the spec's shape; names are made up.

import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { NoticeDesk, readCursor, notices, occOf, dismissDraft, readDraft } from '../../main/resources/web/js/notices.mjs';
import { safeStorage } from '../../main/resources/web/js/stream.mjs';
import { openPage, browserStorage, handTimers, restore } from './pagehost.mjs';

afterEach(restore);

/** A notice value as the spec's §2.1 writes it. */
const val = (id, occ, state = 'RAISED', extra = {}) => ({ occ, raisedAt: 1000 + occ, app: 'torrents', source: 'torrents', body: `done · ${id}`,
  urgency: 'NORMAL', ackRequired: false, gatedBy: 'Torrents', target: `t:${id}`, actions: [], state, origin: 'pc', mayRepeat: false, ...extra });
const rec = (id, occ, state, extra) => [`rec.notices.${id}`, { v: val(id, occ, state, extra), by: 'pc', at: 1 }];
const items = (...rs) => new Map(rs);
function mem() {
  const m = new Map();
  return safeStorage({ getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), removeItem: (k) => m.delete(k) });
}
const ids = (r) => r.pop.map((n) => n.id);

// ---- the pure module ----

test('first contact adopts the highest occurrence and pops nothing old; a later raise pops once', () => {
  const store = mem();
  const d = new NoticeDesk(store, 's1');
  assert.deepEqual(ids(d.review(3, items(rec('a', 4), rec('b', 6, 'DISMISSED')), 0)), [], 'nothing old pops');
  assert.deepEqual(readCursor(store, 's1'), { epoch: 3, occ: 6 });
  const now = items(rec('a', 4), rec('b', 6, 'DISMISSED'), rec('c', 7));
  assert.deepEqual(ids(d.review(3, now, 0)), ['c']);
  assert.deepEqual(ids(d.review(3, now, 0)), [], 'once');
});

test('S1-3, G2: a Tmux alert pops on the glasses only — never here, nor when its hold ends; another window\'s beside it pops', () => {
  const d = new NoticeDesk(mem(), 's1');
  d.review(3, items(rec('a', 4)), 0);
  const tmux = (id, occ, extra) => rec(id, occ, 'RAISED', { app: 'tmux', source: 'tmux', ...extra });
  assert.deepEqual(ids(d.review(3, items(rec('a', 4), tmux('tmux:w/build', 5), rec('b', 6)), 0)), ['b']);
  assert.deepEqual(ids(d.review(3, items(tmux('tmux:w/build', 7, { heldUntil: 50 })), 0)), []);
  assert.deepEqual(ids(d.review(3, items(tmux('tmux:w/build', 7, { heldUntil: 50 })), 60)), [], 'not held, so never released');
});

test('the cursor is kept per stream and epoch: a later visit pops what was raised meanwhile, a reload nothing, a new epoch or stream adopts', () => {
  const store = mem();
  new NoticeDesk(store, 's1').review(3, items(rec('a', 4)), 0);
  const later = items(rec('a', 4), rec('c', 7));
  assert.deepEqual(ids(new NoticeDesk(store, 's1').review(3, later, 0)), ['c'], 'raised while no page was open');
  assert.deepEqual(ids(new NoticeDesk(store, 's1').review(3, later, 0)), [], 'a reload pops nothing it handled');
  const restored = items(rec('a', 4), rec('c', 7), rec('e', 8));
  assert.deepEqual(ids(new NoticeDesk(store, 's1').review(4, restored, 0)), [], 'a new epoch adopts (a restored record repeats occurrences)');
  assert.deepEqual(ids(new NoticeDesk(store, 's2').review(4, items(...restored, rec('f', 9)), 0)), [], 'another stream (a cleared store) adopts');
});

test('only a RAISED record pops: one read or dismissed elsewhere before this page saw it does not', () => {
  const d = new NoticeDesk(mem(), 's1');
  d.review(3, items(rec('a', 1)), 0);
  assert.deepEqual(ids(d.review(3, items(rec('a', 1), rec('x', 2, 'READ'), rec('y', 3, 'DISMISSED'), rec('z', 4)), 0)), ['z']);
});

test('a toast leaves when its record is dismissed, acknowledged or cleared elsewhere, or superseded; read elsewhere it stays', () => {
  const d = new NoticeDesk(mem(), 's1');
  d.review(3, items(rec('o', 1)), 0);
  assert.deepEqual(ids(d.review(3, items(rec('a', 2), rec('b', 3), rec('c', 4), rec('e', 5), rec('k', 6)), 0)), ['a', 'b', 'c', 'e', 'k']);
  const r = d.review(3, items(rec('a', 2, 'DISMISSED'), rec('b', 3, 'READ'), rec('c', 4, 'CLEARED'), rec('e', 7), rec('k', 6, 'ACKED')), 0);
  assert.deepEqual(r.withdraw.sort(), ['a', 'c', 'e', 'k']);
  assert.deepEqual(ids(r), ['e'], 'the next occurrence is a new toast');
  d.down('b'); // it timed out here
  assert.deepEqual(d.review(3, items(rec('b', 3, 'DISMISSED'), rec('e', 7)), 0).withdraw, [], 'a toast already gone is not taken down twice');
});

test('quiet hours hold what the record says (heldUntil): released together at its end; one dismissed meanwhile stays down', () => {
  const store = mem();
  const d = new NoticeDesk(store, 's1');
  d.review(3, items(rec('o', 1)), 0);
  const held = (s = 'RAISED') => items(rec('o', 1), rec('a', 2, 'RAISED', { heldUntil: 1000 }), rec('b', 3, 'RAISED', { heldUntil: 1000 }),
    rec('c', 4, s, { heldUntil: 1000 }), rec('u', 5, 'RAISED', { urgency: 'URGENT' }));
  let r = d.review(3, held(), 500);
  assert.deepEqual(ids(r), ['u'], 'held inside the hours; a record the PC did not hold pops');
  assert.equal(r.wake, 1000);
  assert.equal(readCursor(store, 's1').occ, 1, 'the stored cursor never passes a held record');
  r = d.review(3, held('DISMISSED'), 600);
  assert.deepEqual([ids(r), r.wake], [[], 1000]);
  r = d.review(3, held('DISMISSED'), 1000);
  assert.deepEqual([ids(r), r.wake], [['a', 'b'], null], 'released together; the one dismissed meanwhile is not');
});

test('a page closed during quiet hours and opened after their end still pops what it held', () => {
  const store = mem();
  const night = items(rec('o', 1), rec('a', 2, 'RAISED', { heldUntil: 1000 }));
  new NoticeDesk(store, 's1').review(3, items(rec('o', 1)), 0);
  const d = new NoticeDesk(store, 's1');
  assert.deepEqual(ids(d.review(3, night, 500)), []);
  assert.deepEqual(ids(new NoticeDesk(store, 's1').review(3, night, 2000)), ['a'], 'the next visit');
});

test('two tabs of one browser each pop a new notice: the stored cursor only seeds a tab', () => {
  const store = mem();
  const a = new NoticeDesk(store, 's1');
  const b = new NoticeDesk(store, 's1');
  a.review(3, items(rec('o', 1)), 0);
  b.review(3, items(rec('o', 1)), 0);
  const now = items(rec('o', 1), rec('n', 2));
  assert.deepEqual([ids(a.review(3, now, 0)), ids(b.review(3, now, 0))], [['n'], ['n']]);
});

test('a dismiss and a read are the whole value with the state moved, its occurrence in it (L1 C1: no expectation)', () => {
  const n = { id: 'torrents:h1', v: val('h1', 7), occ: 7 };
  assert.deepEqual(dismissDraft(n), { window: 'notices', action: 'dismiss', targets: ['torrents:h1'], result: { ...val('h1', 7), state: 'DISMISSED' } });
  assert.deepEqual([readDraft(n).result.state, readDraft(n).result.occ], ['READ', 7]);
});

test('an occurrence is a whole number or its decimal text; a value without one is listed, never dropped unsaid', () => {
  assert.deepEqual([occOf({ occ: 7 }), occOf({ occ: '12' }), occOf({ occ: 'x' }), occOf({ occ: -1 }), occOf({})], [7, 12, null, null, null]);
  const r = notices(items(rec('a', 1), ['rec.notices.bad', { v: { body: 'x' }, by: 'pc', at: 1 }], ['rec.reader.recent', { v: 'b1' }]));
  assert.deepEqual([r.all.map((n) => n.id), r.bad], [['a'], ['bad']]);
});

// ---- the page ----

const NOW = Date.UTC(2026, 10, 15, 14, 2);
const welcome = (stream, extra = {}) => ({ t: 'welcome', proto: 3, device: 'web-1', level: 'full', conn: 7, pcClock: NOW, epoch: 3, seq: 10,
  contracts: { reader: 1, notices: 1 }, bundle: { hash: 'h' }, ...extra });
const snapN = (seq, ...rs) => ({ t: 'snap', scope: 'notices', epoch: 3, seq, items: Object.fromEntries(rs) });
const chgN = (seq, ...rs) => ({ t: 'chg', scope: 'notices', epoch: 3, seq, patches: rs.map(([addr, set]) => ({ addr, set })) });

async function page(extra) {
  const timers = handTimers();
  const p = await openPage({ path: '/w/reader', storage: browserStorage(), timers, fetchAnswer: () => { throw new Error('no fetch expected'); } });
  const ws = p.sockets[0];
  ws.open();
  ws.frame(welcome(ws.sent[0].stream, extra));
  return { p, ws, timers, stream: ws.sent[0].stream };
}
const all = (root) => root.children.filter((c) => c.tagName).flatMap((c) => [c, ...all(c)]);
const toasts = (p) => p.doc.byId.toasts.children.filter((t) => t.classList.contains('notice'));
const inToast = (t, tag, text) => all(t).find((e) => e.tagName === tag && (!text || e.textContent === text));
const ops = (ws) => ws.sent.filter((f) => f.t === 'op').map((f) => f.op);

test('a notice raised on the PC pops as a toast; Dismiss sends one op carrying its occurrence and the toast leaves', async () => {
  const { p, ws, stream } = await page();
  assert.ok(ws.sent.some((f) => f.t === 'sub' && f.scope === 'notices'), 'the page reads the notice record');
  ws.frame(snapN(10, rec('torrents:old', 4)));
  assert.equal(toasts(p).length, 0, 'first contact pops nothing old');
  ws.frame(chgN(11, rec('torrents:h1', 5)));
  const [t] = toasts(p);
  assert.match(t.textContent, /done · torrents:h1/);
  inToast(t, 'BUTTON', 'Dismiss').fire('click');
  const [op] = ops(ws);
  assert.deepEqual([op.window, op.action, op.targets, op.result.occ, op.result.state], ['notices', 'dismiss', ['torrents:h1'], 5, 'DISMISSED']);
  assert.equal(toasts(p).length, 0);
  ws.frame({ t: 'ans', stream, n: op.n, answer: { a: 'seen' } });
  ws.frame(chgN(12, rec('torrents:h2', 6)));
  inToast(toasts(p)[0], 'BUTTON', 'Dismiss').fire('click');
  ws.frame({ t: 'ans', stream, n: ops(ws).at(-1).n, answer: { a: 'refused', code: 'level', reason: 'needs level view' } });
  assert.match(p.said().at(-1), /Dismiss “done · torrents:h2”: needs level view/, 'a refusal is said');
});

test('a notice dismissed on another device leaves the page without input', async () => {
  const { p, ws } = await page();
  ws.frame(snapN(10));
  ws.frame(chgN(11, rec('torrents:h1', 1)));
  assert.equal(toasts(p).length, 1);
  ws.frame(chgN(12, rec('torrents:h1', 1, 'DISMISSED')));
  assert.equal(toasts(p).length, 0, 'taken down');
  assert.deepEqual(ops(ws), [], 'and nothing sent');
});

test('the tap-through marks the notice read on every screen and opens the window that raised it', async () => {
  const { p, ws } = await page();
  ws.frame(snapN(10));
  ws.frame(chgN(11, rec('h1', 3)));
  const link = inToast(toasts(p)[0], 'A');
  assert.equal(link.getAttribute('href'), '/w/torrents/t:h1');
  link.fire('click');
  assert.deepEqual(ops(ws).map((o) => [o.action, o.result.occ, o.result.state]), [['read', 3, 'READ']]);
  assert.equal(globalThis.location.pathname, '/w/torrents/t:h1');
  assert.equal(toasts(p).length, 0);
});

test('Dismiss while the PC is out of reach sends nothing, says why, and the toast stays', async () => {
  const { p, ws, timers } = await page();
  ws.frame(snapN(10));
  ws.frame(chgN(11, rec('h1', 1)));
  timers.tick();
  timers.tick(); // a ping unanswered when the next is due
  inToast(toasts(p)[0], 'BUTTON', 'Dismiss').fire('click');
  assert.deepEqual(ops(ws), []);
  assert.match(p.said().join(' | '), /Not sent: PC not answering since/);
  assert.equal(toasts(p).length, 1, 'still there to dismiss when the PC answers');
});

test('on a new socket each scope is a snapshot: a notice raised during the outage pops (L1 C2)', async () => {
  const { p, ws, timers, stream } = await page();
  ws.frame({ t: 'snap', scope: 'reader', epoch: 3, seq: 10, items: {} });
  ws.frame(snapN(10, rec('o', 1)));
  p.sockets[0].close();
  timers.timeouts.at(-1).fn(); // the 2 s reconnect
  const ws2 = p.sockets.at(-1);
  ws2.open();
  ws2.frame(welcome(stream));
  const subs = ws2.sent.filter((f) => f.t === 'sub' && (f.scope === 'reader' || f.scope === 'notices')).map((f) => [f.scope, f.seq]);
  assert.deepEqual(subs, [['reader', undefined], ['notices', undefined]], 'no cursor: the PC answers each with a snapshot');
  ws2.frame({ t: 'snap', scope: 'reader', epoch: 3, seq: 12, items: { 'rec.reader.recent': { v: 'b1', by: 'phone-1', at: 1 } } });
  ws2.frame(snapN(12, rec('o', 1), rec('n', 2)));
  assert.equal(toasts(p).length, 1, 'the notice raised during the outage pops');
});

test('an older PC that keeps no notice record is not asked for one', async () => {
  const { ws } = await page({ contracts: { reader: 1 } });
  assert.equal(ws.sent.some((f) => f.t === 'sub' && f.scope === 'notices'), false);
});

test('quiet hours: a held notice pops at the hold\'s end on the PC\'s clock, not before', async () => {
  const real = Date.now;
  let now = NOW - 3600e3; // this browser's clock is an hour behind the PC's
  Date.now = () => now;
  try {
    const { p, ws, timers } = await page();
    ws.frame(snapN(10));
    ws.frame(chgN(11, rec('h1', 1, 'RAISED', { heldUntil: NOW + 600e3 })));
    assert.equal(toasts(p).length, 0, 'held');
    now += 600e3;
    const wake = timers.timeouts.find((x) => x.live && x.fn.name === 'reviewNotices');
    assert.ok(wake, 'the page wakes at the hold\'s end');
    wake.live = false;
    wake.fn();
    assert.equal(toasts(p).length, 1, 'released');
  } finally {
    Date.now = real;
  }
});
