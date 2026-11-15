// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-2, packages JW0 and Q1 (the spec's §7): the page shell itself (`app.mjs`) over the DOM stand-in of
// pagehost.mjs — the reach the page shows and acts on (JW0, seen in headless Firefox), and the web items carried from
// S1-1: N2 (the retry timer after the connection's end), N7 (the devices page and the reach), F2b 3 (a gap renumbering
// onto a number held for its resend), F2b 4 (a re-approval under the same device id) and F2b 5 (the order on a new
// socket). This file runs in its own process; its globals reach no other test.

import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { openPage, browserStorage, handTimers, restore, settle } from './pagehost.mjs';

afterEach(restore);

const RETRY = 'the PC could not write this change to its log; it is kept and sent again';
const DEVICES = { rows: [{ id: 'web-1', name: 'desk', kind: 'web', level: 'full', approvedAt: 1, approvedBy: 'the PC', lastSeen: 1 }],
  pending: [{ code: 'K7M4QX', name: 'browser on Linux', kind: 'web', address: '192.0.2.9', since: 1 }] };

const welcome = (stream, hw = 0, extra = {}) => ({ t: 'welcome', proto: 2, device: 'web-1', level: 'full', conn: 7, pcClock: 1, epoch: 1, seq: 1,
  highWater: hw ? { [stream]: hw } : {}, contracts: { reader: 1 }, bundle: { version: 't', hash: 'h', protoRange: [2, 2] }, ...extra });

/** A page at `path`, its first socket open and welcomed with the high-water `hw`. */
async function welcomed(path, hw = 0) {
  const timers = handTimers();
  const storage = browserStorage();
  const page = await openPage({ path, storage, timers, fetchAnswer: () => { throw new Error('no fetch expected'); } });
  const ws = page.sockets[0];
  ws.open();
  ws.frame(welcome(ws.sent[0].stream, hw));
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
  ws2.frame(welcome(ws.sent[0].stream));
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

test('N2: an op held for its one resend is not sent after the connection\'s end told its view it ended', async () => {
  const { page, timers, ws, stream } = await welcomed('/w/reader');
  page.go('/w/reader/book:b1'); // his input: Reader's `open`
  const [open] = ops(ws);
  ws.frame({ t: 'ans', stream, n: open.n, answer: { a: 'refused', code: 'retry', reason: RETRY } });
  const ws2 = reconnect(page, timers); // the connection ends inside the 2 s and the page reconnects
  ws2.frame(welcome(stream, open.n - 1));
  timers.fire(2000); // the resend's timer
  assert.deepEqual(ops(ws2), [], 'nothing is sent again on the new connection');
  // an answer for a number the page no longer holds is said in the page's words, never "kept and sent again"
  ws2.frame({ t: 'ans', stream, n: open.n, answer: { a: 'refused', code: 'retry', reason: RETRY } });
  const said = page.said().at(-1);
  assert.match(said, /this page keeps no queue/);
  assert.doesNotMatch(said, /sent again/);
});

test('F2b 3: a gap never renumbers an op onto a number still held for another op\'s resend', async () => {
  const { page, timers, ws, stream } = await welcomed('/w/reader', 10);
  page.go('/w/reader/book:b1');
  page.go('/w/reader/book:b2');
  const [a, b] = ops(ws);
  assert.deepEqual([a.n, b.n], [11, 12]);
  ws.frame({ t: 'ans', stream, n: a.n, answer: { a: 'refused', code: 'retry', reason: RETRY } }); // 11 held for 2 s
  ws.frame({ t: 'ans', stream, n: b.n, answer: { a: 'refused', code: 'gap', reason: 'expected 11' } });
  const resent = ops(ws).at(-1);
  assert.equal(resent.result, 'b2');
  assert.notEqual(resent.n, a.n, 'the gap\'s resend takes a number no held op carries');
  timers.fire(2000);
  const again = ops(ws).at(-1);
  assert.deepEqual([again.result, again.n], ['b1', 11], 'the held op is sent again under its own number');
});

test('F2b 4: a re-approval under the same device id still tells a second waiting tab, which connects with the cookie', async () => {
  const timers = handTimers();
  const storage = browserStorage();
  storage.m.set('damage.device', 'web-1'); // a remembered device
  const fetchAnswer = async () => ({ status: 200, json: async () => ({ device: 'web-1', level: 'full' }), text: async () => '' });
  const a = await openPage({ path: '/', storage, timers, fetchAnswer });
  a.sockets[0].open();
  a.sockets[0].frame({ t: 'pending', code: 'AAA111' });
  const b = await openPage({ path: '/', storage, timers, fetchAnswer });
  b.sockets[0].open();
  b.sockets[0].frame({ t: 'pending', code: 'BBB222' });
  a.sockets[0].frame({ t: 'approved', claim: 'c1' });
  await settle();
  await settle();
  assert.equal(a.sockets.length, 2, 'the approved tab connects again');
  assert.equal(b.sockets.length, 2, 'the waiting tab drops its code and connects too');
  assert.equal(b.sockets[0].readyState, 3);
});

test('F2b 5: on a new socket, a place read while the PC was away is sent after the welcome\'s adopt and subscriptions', async () => {
  const { page, timers, ws, stream } = await welcomed('/w/reader/book:b1');
  ws.frame({ t: 'snap', scope: 'reader', epoch: 1, seq: 1, items: {} });
  ws.frame({ t: 'snap', scope: 'reader/book:b1', desc: { id: 'b1', title: 'One', length: 400, chapters: [] } });
  ws.frame({ t: 'snap', scope: 'reader/book:b1/part:0', desc: { part: 0, parts: 1, charStart: 0, charEnd: 400, blocks: [] } });
  page.sockets[0].close(); // the PC goes away
  elements(page.doc.byId.main).find((e) => e.classList.contains('reading')).fire('wheel'); // he reads meanwhile
  timers.timeouts.at(-1).fn();
  const ws2 = page.sockets.at(-1);
  ws2.open();
  ws2.frame(welcome(stream, 50));
  const kinds = ws2.sent.map((f) => f.t);
  const place = ops(ws2).find((o) => o.action === 'place');
  assert.ok(place, 'the place read meanwhile is sent');
  assert.equal(place.n, 51, 'numbered from the welcome\'s high-water');
  assert.ok(kinds.indexOf('op') > kinds.lastIndexOf('sub'), `after the subscriptions: ${kinds}`);
});
