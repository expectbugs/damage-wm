// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-1, package P7 (decision A.7): the web client's pure modules under `node --test` — anchors, addresses, the
// record copy, the browser stream, the lane's messages and levels, the focus model, and the connection's reach decision
// (a fake socket and fake timers: no browser, no network).

import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as anchor from '../../main/resources/web/js/anchor.mjs';
import * as address from '../../main/resources/web/js/address.mjs';
import { ClientRecord, key } from '../../main/resources/web/js/record.mjs';
import { Stream, safeStorage, STREAM_KEY } from '../../main/resources/web/js/stream.mjs';
import * as P from '../../main/resources/web/js/proto.mjs';
import { nextFocus } from '../../main/resources/web/js/focus.mjs';
import { Conn } from '../../main/resources/web/js/conn.mjs';
import { outTitle, toastsToDrop, keepFocus } from '../../main/resources/web/js/dom.mjs';
import { readFileSync } from 'node:fs';

test('an anchor reads and writes as the Kotlin ReaderAnchor does', () => {
  assert.deepEqual(anchor.parse('@c120.1~It was a dark'), { offset: 120, ver: 1, quote: 'It was a dark' });
  assert.deepEqual(anchor.parse('@c7.1'), { offset: 7, ver: 1, quote: '' });
  assert.equal(anchor.parse('@c-1.1~x'), null);
  assert.equal(anchor.parse('@c1.0~x'), null);
  assert.equal(anchor.parse('c1.1~x'), null);
  assert.equal(anchor.parse(null), null);
  assert.equal(anchor.parse('@c1.1~line\nbreak').quote, 'line\nbreak');
  assert.equal(anchor.format({ offset: 9, quote: 'ab' }), '@c9.1~ab');
});

test('an address round-trips a window, a target and an anchor, encoding what a path cannot hold', () => {
  const cases = [
    { window: 'reader', target: null, anchor: null },
    { window: 'reader', target: 'folder:Series/Book two', anchor: null },
    { window: 'reader', target: 'book:0a1b2c', anchor: '@c120.1~a/b@c ü 100%?' },
    { window: 'devices', target: null, anchor: null },
  ];
  for (const c of cases) assert.deepEqual(address.parse(address.format(c)), c);
  assert.equal(address.format({ window: 'reader', target: 'book:0a1b' }), '/w/reader/book:0a1b');
  assert.deepEqual(address.parse('/'), { window: 'main', target: null, anchor: null });
  assert.equal(address.format({ window: 'main' }), '/');
  assert.equal(address.parse('/elsewhere'), null);
  assert.equal(address.parse('/w/reader/%E0%A4'), null, 'a malformed escape is not an address');
});

test('the record copy takes a snapshot, then changes in order; another epoch asks for a snapshot', () => {
  const r = new ClientRecord();
  const a = { v: '@c1.1~', by: 'phone', at: 5 };
  let ch = r.snap({ epoch: 7, seq: 10, items: { [key('reader', 'place.b1')]: a, [key('reader', 'recent')]: { v: 'b1', by: 'phone', at: 5 } } }, ['rec.reader.']);
  assert.equal(ch.size, 2);
  const b = { v: '@c9.1~', by: 'local', at: 6 };
  ch = r.chg({ epoch: 7, seq: 12, patches: [{ addr: key('reader', 'place.b1'), set: b }] });
  assert.deepEqual([...ch], [key('reader', 'place.b1')]);
  assert.equal(r.value('reader', 'place.b1'), '@c9.1~');
  assert.equal(r.chg({ epoch: 7, seq: 11, patches: [{ addr: key('reader', 'place.b1'), set: a }] }).size, 0, 'a change already held is ignored');
  assert.equal(r.value('reader', 'place.b1'), '@c9.1~');
  assert.equal(r.chg({ epoch: 8, seq: 1, patches: [] }), null);
  ch = r.snap({ epoch: 8, seq: 1, items: {} }, ['rec.reader.']);
  assert.equal(ch.size, 2, 'a snapshot replaces the window whole');
  r.snap({ epoch: 8, seq: 2, items: { [key('reader', 'place.b1')]: { gone: true, by: 'x', at: 1 } } }, ['rec.reader.']);
  assert.equal(r.value('reader', 'place.b1'), undefined);
});

function memStore() {
  const m = new Map();
  return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), removeItem: (k) => m.delete(k), m };
}
const bytes = (n) => Uint8Array.from({ length: n }, (_, i) => i + 1);

test('a browser stream lives in local storage; a page numbers its ops from 1 (L1 C1: the numbers only match answers)', () => {
  const ls = memStore();
  const s1 = new Stream(safeStorage(ls), bytes);
  assert.equal(s1.id, '0102030405060708');
  assert.equal(ls.m.get(STREAM_KEY), s1.id);
  assert.deepEqual([s1.next(), s1.next()], [1, 2]);
  const s2 = new Stream(safeStorage(ls), () => { throw new Error('a kept stream mints nothing'); });
  assert.equal(s2.id, s1.id);
  assert.equal(s2.next(), 1, 'another page of the browser counts on its own');
});

test('a storage that throws keeps the values for this visit and says why', () => {
  const bad = { getItem() { throw new Error('denied'); }, setItem() { throw new Error('denied'); }, removeItem() { throw new Error('denied'); } };
  const st = safeStorage(bad);
  st.set('k', 'v');
  assert.equal(st.get('k'), 'v');
  assert.equal(st.failed, 'denied');
  assert.equal(safeStorage(null).get('x'), null);
});

test('the lane messages and the levels', () => {
  assert.deepEqual(P.hello({ stream: 'abc', name: 'desk', replaces: 'web-1' }),
    // S1-2 Q3w (decisions A.1, A.5): the page reads the `notices` window, which the PC shares only with a hello naming it;
    // S1-2 Q4w (spec §5 X1, Contracts.kt): likewise `torrents`, at version 1; S1-2 Q5w (spec §5 X1): likewise `files`
    { t: 'app', proto: 3, stream: 'abc', contracts: { reader: 1, notices: 1, torrents: 1, files: 1 }, name: 'desk', replaces: 'web-1' });
  // L1 C2: every subscription is answered with a snapshot, so none names a cursor
  assert.deepEqual(P.sub('reader'), { t: 'sub', scope: 'reader' });
  assert.deepEqual(P.sub('torrents', { kind: 'focus', paceMs: 5000 }), { t: 'sub', scope: 'torrents', need: { kind: 'focus', paceMs: 5000 } });
  // L1 C1: no expectation; `at`, the input's time
  const op = P.queuedOp({ stream: 's', n: 4, window: 'reader', action: 'place', targets: ['place.b'], result: '@c1.1~', at: 77 });
  assert.deepEqual(op.op, { device: '', stream: 's', n: 4, window: 'reader', action: 'place', targets: ['place.b'], result: '@c1.1~', at: 77 });
  const co = P.connOp({ conn: 9, k: 1, window: 'devices', action: 'deny', params: { code: 'K7M' }, at: 78 }).op;
  assert.deepEqual([co.conn, co.at, co.expect], [9, 78, undefined]);
  assert.equal(P.atLeast('control', 'view'), true);
  assert.equal(P.atLeast('view', 'control'), false);
  assert.equal(P.atLeast(null, 'view'), false);
  assert.equal(P.levelWhy('view', 'full'), 'needs level full on this device (this one is view)');
  assert.equal(P.levelWhy('full', 'full'), null);
  assert.equal(P.answerWords({ a: 'seen' }), null);
  assert.equal(P.answerWords({ a: 'refused', reason: 'not here', code: 'invalid' }), 'not here');
  assert.equal(P.endRetries('denied'), false);
  assert.equal(P.endRetries('slow'), true);
  assert.match(P.endWords('revoked'), /removed/);
  assert.match(P.endWords('proto'), /reloads/);
  assert.equal(P.hhmm(new Date(2026, 0, 1, 9, 5).getTime()), '9:05');
  // L1: the reach line's time carries the day when it is not today
  assert.equal(P.when(new Date(2026, 10, 15, 14, 2).getTime(), new Date(2026, 10, 15, 20, 0).getTime()), '14:02');
  assert.equal(P.when(new Date(2026, 10, 15, 14, 2).getTime(), new Date(2026, 10, 17, 9, 0).getTime()), 'Nov 15 14:02');
});

test('a retry is said in the page\'s words: it keeps no queue, so it never says the change is kept and sent again', () => {
  const said = P.pageWords({ a: 'refused', code: 'retry', reason: 'the PC could not write this change to its log; it is kept and sent again' });
  assert.deepEqual([said.a, said.code], ['refused', 'retry']);
  assert.equal(said.reason, 'not applied: the PC could not write this change to its log; act again');
  assert.deepEqual(P.pageWords({ a: 'seen' }), { a: 'seen' });
});

test('the focus model moves by rows and columns in a grid, and along a list', () => {
  // a 3 x 2 grid of 100 px boxes
  const grid = [0, 1, 2, 3, 4, 5].map((i) => ({ x: (i % 3) * 110, y: Math.floor(i / 3) * 110, w: 100, h: 100 }));
  assert.equal(nextFocus(grid, 0, 'ArrowRight'), 1);
  assert.equal(nextFocus(grid, 0, 'ArrowDown'), 3);
  assert.equal(nextFocus(grid, 4, 'ArrowUp'), 1);
  assert.equal(nextFocus(grid, 2, 'ArrowRight'), 2, 'nothing that way: focus stays');
  assert.equal(nextFocus(grid, -1, 'ArrowDown'), 0, 'no focus yet: the first stop');
  const list = [0, 1, 2].map((i) => ({ x: 0, y: i * 40, w: 300, h: 32 }));
  assert.equal(nextFocus(list, 1, 'ArrowDown'), 2);
  assert.equal(nextFocus(list, 1, 'ArrowUp'), 0);
  assert.equal(nextFocus(list, 1, 'Enter'), 1);
});

/** A socket the test drives, and timers it fires by hand. */
function rig() {
  const socks = [];
  class FakeWS {
    constructor(url) {
      this.url = url;
      this.readyState = 0;
      this.sent = [];
      socks.push(this);
    }
    send(t) { this.sent.push(JSON.parse(t)); }
    close() { this.readyState = 3; if (this.onclose) this.onclose(); }
    open() { this.readyState = 1; this.onopen(); }
    frame(f) { this.onmessage({ data: JSON.stringify(f) }); }
  }
  const t = { now: 1000, intervals: [], timeouts: [] };
  const timers = {
    setInterval: (fn, ms) => { t.intervals.push({ fn, ms, live: true }); return t.intervals.length - 1; },
    clearInterval: (i) => { if (t.intervals[i]) t.intervals[i].live = false; },
    setTimeout: (fn, ms) => { t.timeouts.push({ fn, ms, live: true }); return t.timeouts.length - 1; },
    clearTimeout: (i) => { if (t.timeouts[i]) t.timeouts[i].live = false; },
  };
  const frames = [];
  const reach = [];
  const c = new Conn({ url: 'ws://pc/ws', hello: () => ({ t: 'app', stream: 's' }), onFrame: (f) => frames.push(f),
    onReach: (r) => reach.push({ ...r }), WS: FakeWS, now: () => t.now, timers });
  const tick = () => { for (const i of t.intervals) if (i.live) i.fn(); };
  return { c, socks, t, frames, reach, tick };
}

test('reach rests on the pings alone: a ping unanswered when the next is due is out of reach since it went out', () => {
  const { c, socks, t, reach, tick } = rig();
  c.start();
  socks[0].open();
  assert.deepEqual(socks[0].sent[0], { t: 'app', stream: 's' }, 'the hello first');
  socks[0].frame({ t: 'welcome', conn: 1 });
  assert.equal(c.reach.ok, true);
  t.now = 2000;
  tick(); // ping 1 out at 2000
  assert.deepEqual(socks[0].sent.at(-1), { t: 'ping', k: 1 });
  socks[0].frame({ t: 'pong', k: 1 });
  t.now = 3000;
  tick(); // ping 2 out at 3000, unanswered
  t.now = 4000;
  tick(); // due again: out of reach since 3000
  assert.deepEqual(c.reach, { ok: false, since: 3000 });
  socks[0].frame({ t: 'pong', k: 3 });
  assert.equal(c.reach.ok, true);
  assert.deepEqual(reach.map((r) => r.ok), [true, false, true]);
});

test('L1: a socket with two pings unanswered is let go and made again on the 2 s pacing (a half-open socket)', () => {
  const { c, socks, t, frames, tick } = rig();
  c.start();
  socks[0].open();
  socks[0].frame({ t: 'welcome', conn: 1 });
  t.now = 2000;
  tick(); // ping 1 out, unanswered
  t.now = 3000;
  tick(); // one unanswered: out of reach since 2000, ping 2 out
  assert.deepEqual([c.reach, socks.length], [{ ok: false, since: 2000 }, 1], 'the first stays open');
  t.now = 4000;
  tick(); // the second unanswered: let go
  assert.equal(socks[0].readyState, 3, 'the socket is closed');
  assert.equal(frames.at(-1).t, 'closed', 'the page hears the end at once (its unanswered ops are said)');
  assert.equal(c.reach.since, 2000, 'the streak keeps the time it began');
  socks[0].frame({ t: 'pong', k: 2 });
  assert.equal(c.reach.ok, false, 'a socket let go is no longer heard');
  assert.equal(t.timeouts.filter((x) => x.live).length, 1, 'one new socket on the pacing, not one per close');
  t.timeouts.at(-1).fn();
  assert.equal(socks.length, 2);
  socks[1].open();
  socks[1].frame({ t: 'welcome', conn: 2 });
  assert.equal(c.reach.ok, true);
});

test('a closed socket is out of reach at once and is reconnected on 2 s pacing', () => {
  const { c, socks, t, frames } = rig();
  c.start();
  socks[0].open();
  socks[0].frame({ t: 'pending', code: 'K7M' });
  assert.equal(c.reach.ok, true, 'a waiting page is answered too');
  t.now = 9000;
  socks[0].close();
  assert.deepEqual(c.reach, { ok: false, since: 9000 });
  assert.equal(frames.at(-1).t, 'closed');
  assert.equal(t.timeouts.at(-1).ms, 2000);
  t.timeouts.at(-1).fn();
  assert.equal(socks.length, 2, 'a new socket');
  assert.equal(c.send({ t: 'x' }), false, 'nothing is sent before it opens: a page keeps no queue');
  c.stop();
  assert.equal(t.timeouts.filter((x) => x.live).length, 0);
});

test('the toast cap never pushes out a kept toast nor the one just said (ruling 2.3 (12))', () => {
  // a loss notice (kept) then four plain toasts: the oldest plain one goes, the notice stays
  assert.deepEqual(toastsToDrop([true, false, false, false, false], 4), [1]);
  // four kept notices and a fifth line: nothing kept goes, and the new line is not dropped unsaid
  assert.deepEqual(toastsToDrop([true, true, true, true, false], 4), []);
  assert.deepEqual(toastsToDrop([false, false, false, false, false, false], 4), [0, 1]);
  assert.deepEqual(toastsToDrop([false, false], 4), []);
});

test('nothing is cut and a link that leaves the book says so (ruling 2.3 (7), (10))', () => {
  assert.equal(outTitle('https://example.org/x?y=1'), 'opens example.org, outside the book');
  assert.equal(outTitle('http://example.net:8080/'), 'opens example.net:8080, outside the book');
  const css = readFileSync(new URL('../../main/resources/web/style.css', import.meta.url), 'utf8');
  assert.match(css, /a\.out::after\s*\{\s*content:/, 'an outside link carries a visible mark');
  assert.doesNotMatch(css, /object-fit:\s*cover/, 'a cover is shown whole, never cropped');
  assert.match(css, /\.cover img \{[^}]*object-fit:\s*contain/);
});

test('a redraw gives keyboard focus back by key, with the caret where it was (ruling 2.3 (5), (6))', () => {
  // a stand-in for the few DOM calls the helper makes: no browser here
  const el = (key, caret) => ({
    key, focused: false, sel: null, selectionStart: caret, selectionEnd: caret,
    getAttribute(a) { return a === 'data-key' ? this.key : null; },
    focus() { this.focused = true; globalThis.document.activeElement = this; },
    setSelectionRange(a, b) { this.sel = [a, b]; },
  });
  let kids = [el('book:a'), el('book:b')];
  const root = { contains: (e) => kids.includes(e), querySelectorAll: () => kids };
  globalThis.document = { activeElement: kids[1] };
  try {
    keepFocus(root, 'data-key', () => { kids = [el('continue'), el('book:a'), el('book:b'), el('book:c')]; });
    assert.equal(kids[2].focused, true, 'the card of the same book holds the focus after the redraw');
    assert.equal(kids.filter((k) => k.focused).length, 1);
    // the name being typed: focus and caret come back
    kids = [el('pick:ACDEFG'), el('rename', 5)];
    globalThis.document.activeElement = kids[1];
    keepFocus(root, 'data-key', () => { kids = [el('pick:ACDEFG'), el('rename', 0)]; });
    assert.equal(kids[1].focused, true);
    assert.deepEqual(kids[1].sel, [5, 5]);
    // focus outside the redrawn part is left alone
    const outside = el('rail:main');
    globalThis.document.activeElement = outside;
    keepFocus(root, 'data-key', () => { kids = [el('book:a')]; });
    assert.equal(kids[0].focused, false);
  } finally {
    delete globalThis.document;
  }
});
