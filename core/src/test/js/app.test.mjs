// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-2, packages JW0 and Q1 (the spec's §7): the page shell itself (`app.mjs`) over the DOM stand-in of
// pagehost.mjs — the reach the page shows and acts on (JW0, seen in headless Firefox), the web items carried from
// S1-1: N7 (the devices page and the reach) and F2b 5 (the order on a new socket); L1: every op's `at` on the PC's
// clock, and a page refused for its lane version reloading itself. This file runs in its own process; its globals
// reach no other test.

import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { openPage, browserStorage, handTimers, restore, settle } from './pagehost.mjs';

afterEach(restore);

const DEVICES = { rows: [{ id: 'web-1', name: 'desk', kind: 'web', level: 'full', approvedAt: 1, approvedBy: 'the PC', lastSeen: 1 }],
  pending: [{ code: 'K7M4QX', name: 'browser on Linux', kind: 'web', address: '192.0.2.9', since: 1 }] };

const welcome = (extra = {}) => ({ t: 'welcome', proto: 2, device: 'web-1', level: 'full', conn: 7, pcClock: 1, epoch: 1, seq: 1,
  contracts: { reader: 1 }, bundle: { hash: 'h' }, ...extra });

/** A page at `path`, its first socket open and welcomed (`extra` into the welcome). */
async function welcomed(path, extra = {}) {
  const timers = handTimers();
  const storage = browserStorage();
  const page = await openPage({ path, storage, timers, fetchAnswer: () => { throw new Error('no fetch expected'); } });
  const ws = page.sockets[0];
  ws.open();
  ws.frame(welcome(extra));
  return { page, timers, ws, stream: ws.sent[0].stream };
}

const elements = (root) => root.children.filter((c) => c.tagName).flatMap((c) => [c, ...elements(c)]);
const button = (page, label) => elements(page.doc.byId.main).find((e) => e.tagName === 'BUTTON' && e.textContent === label);
const ops = (ws) => ws.sent.filter((f) => f.t === 'op').map((f) => f.op);

/** The socket ends; the 2 s reconnect runs alone (before any other timer due at 2 s), and the new socket opens. */
function reconnect(page, timers) {
  page.sockets.at(-1).close();
  timers.timeouts.at(-1).fn();
  const ws = page.sockets.at(-1);
  ws.open();
  return ws;
}

test('the page is in reach once the PC welcomes it: no "not answering" bar and the actions live (JW0, seen in headless Firefox)', async () => {
  const { page, timers, ws } = await welcomed('/w/devices');
  ws.frame({ t: 'snap', scope: 'devices', desc: DEVICES });
  assert.equal(page.doc.byId.reach.hidden, true, 'the bar that says the PC is not answering is hidden while it answers');
  assert.equal(page.doc.body.classList.contains('away'), false);
  button(page, 'Approve').fire('click');
  assert.deepEqual(ops(ws).map((o) => o.action), ['approve'], 'an action is sent while the PC answers');
  const ws2 = reconnect(page, timers);
  assert.equal(page.doc.byId.reach.hidden, false, 'out of reach until the new socket is answered');
  ws2.frame(welcome());
  assert.equal(page.doc.byId.reach.hidden, true, 'back in reach on the new socket');
});

test('N7: the devices page dims its actions while the PC is out of reach and lives again when it answers', async () => {
  const { page, timers, ws } = await welcomed('/w/devices');
  ws.frame({ t: 'snap', scope: 'devices', desc: DEVICES });
  timers.tick(); // a ping goes out
  timers.tick(); // still unanswered when the next is due: out of reach
  assert.equal(page.doc.byId.reach.hidden, false);
  const away = button(page, 'Approve');
  assert.equal(away.getAttribute('aria-disabled'), 'true', 'a button drawn before the outage is dim during it');
  assert.match(away.getAttribute('data-why'), /PC not answering since/);
  ws.frame({ t: 'pong', k: 2 });
  const back = button(page, 'Approve');
  assert.equal(back.getAttribute('aria-disabled'), null, 'live again once the PC answers: no stale reason');
});

test('L1 C1: every op carries the time of its input on the PC\'s clock (ruling §5: a TV with a wrong clock orders right)', async () => {
  const ahead = Date.now() + 3_600_000; // the PC's clock an hour ahead of this browser's
  const { page, ws } = await welcomed('/w/devices', { pcClock: ahead });
  ws.frame({ t: 'snap', scope: 'devices', desc: DEVICES });
  button(page, 'Approve').fire('click'); // a device op (conn, k)
  page.go('/w/reader/book:b1'); // his input: Reader's `open`, a record op (stream, n)
  const [approve, open] = ops(ws);
  for (const o of [approve, open]) {
    assert.equal(typeof o.at, 'number');
    assert.ok(Math.abs(o.at - ahead) < 5000, `on the PC's clock: ${o.at} vs ${ahead}`);
    assert.equal(o.expect, undefined, 'no expectation: the latest input wins');
  }
  assert.equal(open.n, 1, 'numbered from 1 by the page alone');
});

test('L1: a page refused for its lane version reloads itself on the 2 s pacing (a TV page comes back after a deploy)', async () => {
  const { page, timers, ws } = await welcomed('/');
  ws.frame({ t: 'end', reason: 'proto', detail: 'lane protocol 2 unsupported (this PC speaks 3)' });
  assert.equal(page.win.reloaded, undefined, 'not at once');
  timers.fire(2000);
  assert.equal(page.win.reloaded, true, 'the page the PC serves now is loaded');
});

test('F2b 5: on a new socket, a place read while the PC was away is sent after the welcome\'s subscriptions', async () => {
  const { page, timers, ws } = await welcomed('/w/reader/book:b1');
  ws.frame({ t: 'snap', scope: 'reader', epoch: 1, seq: 1, items: {} });
  ws.frame({ t: 'snap', scope: 'reader/book:b1', desc: { id: 'b1', title: 'One', length: 400, chapters: [] } });
  ws.frame({ t: 'snap', scope: 'reader/book:b1/part:0', desc: { part: 0, parts: 1, charStart: 0, charEnd: 400, blocks: [] } });
  page.sockets[0].close(); // the PC goes away
  const reconnectAt = timers.timeouts.at(-1);
  const pane = elements(page.doc.byId.main).find((e) => e.classList.contains('reading'));
  pane.fire('wheel'); // he reads meanwhile
  pane.fire('scroll');
  reconnectAt.fn();
  const ws2 = page.sockets.at(-1);
  ws2.open();
  ws2.frame(welcome());
  const kinds = ws2.sent.map((f) => f.t);
  const place = ops(ws2).find((o) => o.action === 'place');
  assert.ok(place, 'the place read meanwhile is sent');
  assert.ok(kinds.indexOf('op') > kinds.lastIndexOf('sub'), `after the subscriptions: ${kinds}`);
});

test('L1 R2 B3 (RX FIX 1): a claim the PC answers 500 opens no new socket before the 2 s pacing', async () => {
  const timers = handTimers();
  const claims = [];
  const page = await openPage({ path: '/', storage: browserStorage(), timers, fetchAnswer: (u, i) => {
    claims.push(u);
    return { status: 500, text: async () => 'the device table could not be written', json: async () => ({}) };
  } });
  const ws = page.sockets[0];
  ws.open();
  ws.frame({ t: 'pending', code: 'K7M4QX' });
  ws.frame({ t: 'approved', claim: 'made-up-claim' }); // this PC's own browser, approved at once (A1)
  await settle();
  await settle();
  assert.deepEqual(claims, ['/pair/claim']);
  assert.equal(page.sockets.length, 1, 'no new socket at once: the PC that cannot write its table is not asked again in a loop');
  timers.fire(2000);
  assert.equal(page.sockets.length, 2, 'the page asks again on the pacing');
});
