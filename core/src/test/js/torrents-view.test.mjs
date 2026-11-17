// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-2, package Q4w: the Torrents page in the real page shell (`app.mjs`) over the DOM stand-in. The pins: the
// page asks the PC for its transfers as a focused viewer at this device's Poll and lets go while hidden; an action the
// level does not allow is not offered; a program op goes once with its connection and targets, says the PC's answer
// and claims nothing the table does not show; a confirm left open over a connection's end sends nothing; a selection
// is what the table shows; a listing asks one page, then the next near the end, a failed one again after 5 s; the
// recents come from the window's own record copy; an older PC is not asked for Torrents at all.

import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { openPage, browserStorage, handTimers, restore, settle } from './pagehost.mjs';
import { torrentsDesc, transfer, tlPage, T_NOW } from './torrents-fixtures.mjs';

afterEach(restore);

const welcome = (stream, extra = {}) => ({ t: 'welcome', proto: 2, device: 'web-1', level: 'full', conn: 7, pcClock: T_NOW, epoch: 3, seq: 10,
  highWater: {}, contracts: { reader: 1, notices: 1, torrents: 1 }, bundle: { version: 't', hash: 'h', protoRange: [2, 2] }, ...extra });

async function page({ path = '/w/torrents', level = 'full', contracts, narrow = false, desc = torrentsDesc({ n: 6 }) } = {}) {
  const timers = handTimers();
  const p = await openPage({ path, storage: browserStorage(), timers, fetchAnswer: () => { throw new Error('no fetch expected'); } });
  if (narrow) p.win.matchMedia = () => ({ matches: true });
  const ws = p.sockets[0];
  ws.open();
  ws.frame(welcome(ws.sent[0].stream, { level, ...(contracts ? { contracts } : {}) }));
  if (desc) ws.frame({ t: 'snap', scope: 'desc/torrents', desc });
  return { p, ws, timers, stream: ws.sent[0].stream };
}

const all = (root) => root.children.filter((c) => c.tagName).flatMap((c) => [c, ...all(c)]);
const main = (p) => p.doc.byId.main;
const find = (p, tag, text) => all(main(p)).find((e) => e.tagName === tag && (text === undefined || e.textContent === text));
const findAll = (p, tag, text) => all(main(p)).filter((e) => e.tagName === tag && (text === undefined || e.textContent === text));
const byKey = (p, k) => all(main(p)).find((e) => e.getAttribute('data-key') === k);
const subs = (ws) => ws.sent.filter((f) => f.t === 'sub');
const progOps = (ws) => ws.sent.filter((f) => f.t === 'op' && f.op.conn !== undefined).map((f) => f.op);
const queuedOps = (ws) => ws.sent.filter((f) => f.t === 'op' && f.op.stream !== undefined).map((f) => f.op);
const fireVisibility = (p, state) => {
  p.doc.visibilityState = state;
  for (const fn of p.doc.on.visibilitychange || []) fn();
};

test('the page reads its transfers as a focused viewer at this device\'s Poll, lets go while hidden, and asks again shown', async () => {
  const { p, ws } = await page();
  assert.deepEqual(subs(ws).find((f) => f.scope === 'desc/torrents').need, { kind: 'focus', paceMs: 2000 });
  assert.ok(subs(ws).some((f) => f.scope === 'torrents' && f.seq === undefined), 'the window\'s own record scope');
  fireVisibility(p, 'hidden');
  assert.deepEqual(subs(ws).at(-1), { t: 'sub', scope: 'desc/torrents', need: { kind: 'none' } });
  fireVisibility(p, 'visible');
  assert.deepEqual(subs(ws).at(-1).need, { kind: 'focus', paceMs: 2000 });
  // this device's Poll row: 5 s
  const poll = byKey(p, 'set:poll');
  poll.value = '5000';
  poll.fire('change', { target: poll });
  assert.deepEqual(subs(ws).at(-1).need, { kind: 'focus', paceMs: 5000 });
  assert.equal(p.win.localStorage.getItem('damage.torrents.poll'), '5000', 'kept on this device only');
});

test('an action this device\'s level does not allow is not offered; Browse, Search and Account need full', async () => {
  const d = torrentsDesc({ n: 3 });
  const hash = d.transfers[0].id;
  const c = await page({ path: `/w/torrents/t:${hash}`, level: 'control', desc: d });
  const labels = findAll(c.p, 'BUTTON').map((b) => b.textContent);
  assert.ok(labels.includes('Delete'), 'delete at control');
  assert.ok(!labels.includes('Delete with files'), 'delete with files needs full: not offered');
  assert.ok(!labels.includes('Open on the PC'), 'open on the PC needs full: not offered');
  assert.equal(find(c.p, 'A', 'Search'), undefined, 'no Search tab below full');
  assert.match(main(c.p).textContent, /Browse, Search and Account need level full on this device/);
  restore();
  const v = await page({ path: `/w/torrents/t:${hash}`, level: 'view', desc: d });
  const vl = findAll(v.p, 'BUTTON').map((b) => b.textContent);
  assert.ok(!vl.includes('Stop') && !vl.includes('Start') && !vl.includes('Delete'), 'nothing that changes qBittorrent at view');
  const files = find(v.p, 'BUTTON', 'Open in Files');
  assert.ok(files && files.classList.contains('dim'), 'open in Files is offered dim below full');
  assert.match(files.getAttribute('data-why'), /needs level full/);
});

test('a program op goes once with its connection, its number and its targets; Sent is said and the table keeps qBittorrent\'s state', async () => {
  const d = torrentsDesc({ transfers: [transfer(0, { raw: 'downloading', title: 'Salt Road' })] });
  const hash = d.transfers[0].id;
  const { p, ws } = await page({ path: `/w/torrents/t:${hash}`, desc: d });
  find(p, 'BUTTON', 'Stop').fire('click');
  const [op] = progOps(ws);
  assert.deepEqual([op.conn, op.window, op.action, op.targets, op.stream], [7, 'torrents', 'stop', [hash], undefined]);
  assert.match(p.doc.byId.main.textContent, /Stop · 1 torrent · sent, no answer from the PC yet · 0 s/);
  ws.frame({ t: 'ans', conn: 7, k: op.k, answer: { a: 'sent' } });
  assert.match(p.said().at(-1), /Stop: sent to qBittorrent for 1 torrent\. The table shows what qBittorrent reports\./);
  assert.doesNotMatch(main(p).textContent, /sent, no answer/, 'answered: no longer waiting');
  assert.ok(find(p, 'BUTTON', 'Stop'), 'the table still shows it downloading until qBittorrent reports otherwise');
  find(p, 'BUTTON', 'Recheck').fire('click');
  ws.frame({ t: 'ans', conn: 7, k: progOps(ws).at(-1).k, answer: { a: 'refused', code: 'program', reason: 'qBittorrent is not answering on 127.0.0.1' } });
  assert.equal(p.said().at(-1), 'TorrentsRecheck: qBittorrent is not answering on 127.0.0.1×');
});

test('an add says the name qBittorrent took, carried on the answer or beside it', async () => {
  const { p, ws } = await page({ path: '/w/torrents/tl:240001' });
  ws.frame({ t: 'snap', scope: 'torrents/tl:240001', desc: { fid: '240001', name: 'Lantern.S01E02', files: [], nfo: '', description: '' } });
  find(p, 'BUTTON', 'Add').fire('click');
  const dlg = p.doc.byId.confirm;
  assert.equal(dlg.open, true);
  assert.match(dlg.textContent, /Add 'Lantern\.S01E02'\?/);
  dlg.querySelector('button.act').fire('click');
  await settle();
  const [op] = progOps(ws);
  assert.deepEqual([op.action, op.targets], ['add', ['240001']]);
  ws.frame({ t: 'ans', conn: 7, k: op.k, answer: { a: 'sent' }, result: { name: 'Lantern.S01E02' } });
  assert.match(p.said().at(-1), /qBittorrent took 'Lantern\.S01E02'/);
});

test('a confirm left open while the connection ended sends nothing when it is taken (C§4.3)', async () => {
  const d = torrentsDesc({ transfers: [transfer(0, { title: 'Salt Road' })] });
  const hash = d.transfers[0].id;
  const { p, ws, timers, stream } = await page({ path: `/w/torrents/t:${hash}`, desc: d });
  find(p, 'BUTTON', 'Delete').fire('click');
  const dlg = p.doc.byId.confirm;
  assert.match(dlg.textContent, /Delete 'Salt Road'\?/);
  p.sockets[0].close();
  timers.timeouts.filter((x) => x.live && x.ms === 2000).at(-1).fn(); // the reconnect
  const ws2 = p.sockets.at(-1);
  ws2.open();
  ws2.frame(welcome(stream, { conn: 8 }));
  dlg.querySelector('button.act').fire('click');
  await settle();
  assert.deepEqual(progOps(ws).concat(progOps(ws2)), [], 'nothing sent on half a confirm');
  assert.match(p.said().join(' | '), /Delete: nothing sent — the connection to the PC ended while the confirm was open/);
});

// S1-2 R2 item 19 (review B-S3): Torrents' delete with files has the same double confirm: the second ask puts Cancel
// where the first ask's act was, so a double-click's second click meets Cancel
test('Delete with files: a double-click on its act meets Cancel — the second ask puts Cancel where the first act was', async () => {
  const d = torrentsDesc({ transfers: [transfer(0, { title: 'Salt Road' })] });
  const hash = d.transfers[0].id;
  const { p, ws } = await page({ path: `/w/torrents/t:${hash}`, desc: d });
  find(p, 'BUTTON', 'Delete with files').fire('click');
  const dlg = p.doc.byId.confirm;
  const row = () => dlg.querySelector('.row');
  const buttons = () => row().children.filter((c) => c.tagName === 'BUTTON');
  assert.ok(row().classList.contains('split'));
  assert.deepEqual(buttons().map((b) => b.textContent), ['Cancel', 'Delete with files']);
  buttons().at(-1).fire('click');
  await settle();
  assert.deepEqual(buttons().map((b) => b.textContent), ['Delete with files: yes, really', 'Cancel']);
  buttons().at(-1).fire('click');
  await settle();
  assert.equal(dlg.open, false);
  assert.deepEqual(progOps(ws), [], 'nothing deleted');
  // taken on purpose: the act in the left half, twice
  find(p, 'BUTTON', 'Delete with files').fire('click');
  buttons().at(-1).fire('click');
  await settle();
  buttons()[0].fire('click');
  await settle();
  assert.deepEqual(progOps(ws).map((o) => [o.action, o.targets]), [['deleteFiles', [hash]]]);
});

test('several selected: one op over their hashes; a hash gone or filtered away leaves the selection', async () => {
  const ts = [transfer(0, { raw: 'uploading', title: 'a' }), transfer(1, { raw: 'uploading', title: 'b' }), transfer(2, { raw: 'stoppedUP', title: 'c' })];
  const { p, ws } = await page({ desc: torrentsDesc({ transfers: ts, version: 1 }) });
  for (const t of ts) byKey(p, `sel:${t.id}`).fire('change');
  assert.match(main(p).textContent, /3 selected/);
  ws.frame({ t: 'snap', scope: 'desc/torrents', desc: torrentsDesc({ transfers: ts.slice(1), version: 2 }) });
  assert.match(main(p).textContent, /2 selected/, 'a transfer gone leaves the selection');
  byKey(p, 'filter:seeding').fire('click');
  assert.match(main(p).textContent, /1 selected/, 'the stopped one filtered away leaves it');
  find(p, 'BUTTON', 'Stop').fire('click');
  assert.deepEqual(progOps(ws).map((o) => [o.action, o.targets]), [['stop', [ts[1].id]]]);
});

test('an older description is not drawn over a newer one', async () => {
  const { p, ws } = await page({ desc: torrentsDesc({ transfers: [transfer(0, { title: 'newer row' })], version: 9 }) });
  ws.frame({ t: 'snap', scope: 'desc/torrents', desc: torrentsDesc({ transfers: [transfer(0, { title: 'older row' })], version: 8 }) });
  assert.match(main(p).textContent, /newer row/);
  assert.doesNotMatch(main(p).textContent, /older row/);
});

test('out of reach the page dims its actions and says why; back, they are live again', async () => {
  const d = torrentsDesc({ n: 2 });
  const { p, ws, timers } = await page({ path: `/w/torrents/t:${d.transfers[0].id}`, desc: d });
  timers.tick();
  timers.tick(); // a ping unanswered when the next is due
  const del = find(p, 'BUTTON', 'Delete');
  assert.ok(del.classList.contains('dim'));
  assert.match(del.getAttribute('data-why'), /PC not answering since/);
  del.fire('click');
  assert.deepEqual(progOps(ws), []);
  assert.ok(p.doc.body.classList.contains('away'), 'the page greys (A.6)');
});

test('a listing asks its first page at once, the next only near the end, and a failed one again after 5 s, not before', async () => {
  const { p, ws, timers } = await page({ path: '/w/torrents/listing:newest' });
  const pages = () => subs(ws).filter((f) => /\/page:\d+$/.test(f.scope)).map((f) => f.scope);
  assert.deepEqual(pages(), ['torrents/listing:newest/sort:added/page:0']);
  assert.match(main(p).textContent, /Reading the listing from TorrentLeech…/);
  ws.frame({ t: 'snap', scope: 'torrents/listing:newest/sort:added/page:0', desc: tlPage(0, 200) });
  assert.equal(pages().length, 1, 'not near the end: nothing more asked');
  Object.assign(main(p), { scrollTop: 900, clientHeight: 500, scrollHeight: 1600 });
  main(p).fire('scroll');
  assert.deepEqual(pages().at(-1), 'torrents/listing:newest/sort:added/page:1');
  main(p).fire('scroll');
  assert.equal(pages().length, 2, 'one page in flight at a time');
  assert.match(main(p).textContent, /loading the next page…/);
  ws.frame({ t: 'ans', scope: 'torrents/listing:newest/sort:added/page:1', answer: { a: 'refused', code: 'retry', reason: 'TorrentLeech: not answering' } });
  assert.match(main(p).textContent, /Page 2 could not be read: TorrentLeech: not answering — asked again every 5 s\./);
  main(p).fire('scroll');
  assert.equal(pages().length, 2, 'not asked again before its 5 s');
  const real = Date.now;
  const t0 = real();
  Date.now = () => t0 + 5000;
  try {
    timers.fire(5000);
  } finally {
    Date.now = real;
  }
  assert.deepEqual(pages().slice(2), ['torrents/listing:newest/sort:added/page:1'], 'asked again on the 5 s pacing');
});

test('a hidden page asks no listing page', async () => {
  const { p, ws } = await page({ path: '/w/torrents/listing:newest' });
  ws.frame({ t: 'snap', scope: 'torrents/listing:newest/sort:added/page:0', desc: tlPage(0, 200) });
  p.doc.visibilityState = 'hidden';
  Object.assign(main(p), { scrollTop: 900, clientHeight: 500, scrollHeight: 1600 });
  main(p).fire('scroll');
  assert.equal(subs(ws).filter((f) => /\/page:1$/.test(f.scope)).length, 0);
});

test('a torrent\'s page opened from a listing keeps its rows for Back and asks no page on a scroll', async () => {
  const { p, ws } = await page({ path: '/w/torrents/listing:newest' });
  ws.frame({ t: 'snap', scope: 'torrents/listing:newest/sort:added/page:0', desc: tlPage(0, 200) });
  byKey(p, 'tl:240003').fire('click');
  assert.equal(globalThis.location.pathname, '/w/torrents/tl:240003');
  Object.assign(main(p), { scrollTop: 900, clientHeight: 500, scrollHeight: 1600 });
  main(p).fire('scroll');
  assert.equal(subs(ws).filter((f) => /\/page:1$/.test(f.scope)).length, 0);
  assert.equal(ws.sent.some((f) => f.t === 'unsub' && /\/page:0$/.test(f.scope)), false, 'the listing is kept for Back');
  find(p, 'A', 'Back to the list').fire('click');
  assert.equal(globalThis.location.pathname, '/w/torrents/listing:newest');
  assert.ok(byKey(p, 'tl:240003'), 'its rows are there at once');
});

test('the recents come from the window\'s own record; a search keeps itself there and opens its results', async () => {
  const { p, ws } = await page({ path: '/w/torrents/search' });
  ws.frame({ t: 'snap', scope: 'reader', epoch: 3, seq: 20, items: {}, colls: {} });
  ws.frame({ t: 'snap', scope: 'torrents', epoch: 3, seq: 12, items: { 'rec.torrents.recents': { v: ['salt road', 'ferry'], rev: 2, by: 'phone-1', at: 1 } }, colls: {} });
  assert.ok(find(p, 'A', 'salt road') && find(p, 'A', 'ferry'), 'the recents under the box');
  const input = byKey(p, 'q');
  input.value = 'lantern';
  find(p, 'FORM').fire('submit');
  const [op] = queuedOps(ws);
  assert.deepEqual([op.window, op.action, op.targets, op.result, op.expect], ['torrents', 'recent', ['recents'], ['lantern', 'salt road', 'ferry'], { any: true }]);
  assert.equal(globalThis.location.pathname, '/w/torrents/search:lantern');
  assert.ok(subs(ws).some((f) => f.scope === 'torrents/search:lantern/sort:added/page:0'));
  // a change of the reader scope is not the window's: the recents stay as the torrents scope says
  ws.frame({ t: 'chg', scope: 'reader', epoch: 3, seq: 21, patches: [{ addr: 'rec.reader.recent', set: { v: 'b1', rev: 3, by: 'x', at: 1 } }] });
  ws.frame({ t: 'chg', scope: 'torrents', epoch: 3, seq: 13, patches: [{ addr: 'rec.torrents.recents', set: { v: ['lantern', 'salt road', 'ferry'], rev: 3, by: 'web-1', at: 2 } }] });
  assert.ok(find(p, 'A', 'lantern'), 'the change of the torrents scope is drawn');
});

test('a PC whose build names no Torrents contract is never asked for it; the page and the rail say so', async () => {
  const { p, ws } = await page({ contracts: { reader: 1, notices: 1 }, desc: null });
  assert.equal(subs(ws).some((f) => f.scope.startsWith('desc/torrents') || f.scope === 'torrents'), false);
  assert.match(main(p).textContent, /Not shown here: the PC's build has no Torrents page yet/);
  const rail = all(p.doc.byId.rail).find((e) => e.getAttribute('data-key') === 'rail:torrents');
  assert.ok(rail.classList.contains('dim'));
});

test('the notice\'s tap-through address opens the transfer\'s details and reads its files', async () => {
  const d = torrentsDesc({ n: 3 });
  const hash = d.transfers[1].id;
  const { p, ws } = await page({ path: `/w/torrents/t:${hash}`, desc: d });
  assert.ok(subs(ws).some((f) => f.scope === `torrents/t:${hash}`));
  ws.frame({ t: 'snap', scope: `torrents/t:${hash}`, desc: { hash, files: [{ name: 'one.mkv', size: 1e9, progress: 0.5, priority: 1 }], trackers: [] } });
  assert.match(main(p).textContent, /Files \(1\)one\.mkv/);
  find(p, 'BUTTON', 'Close').fire('click');
  assert.equal(globalThis.location.pathname, '/w/torrents');
  assert.ok(ws.sent.some((f) => f.t === 'unsub' && f.scope === `torrents/t:${hash}`), 'its files are no longer read');
});

test('at a phone\'s width a card per transfer; a long press selects, the actions sit in a sheet', async () => {
  const ts = [transfer(0, { raw: 'uploading', title: 'a' }), transfer(1, { raw: 'uploading', title: 'b' })];
  const { p, timers } = await page({ narrow: true, desc: torrentsDesc({ transfers: ts }) });
  assert.equal(find(p, 'TABLE'), undefined);
  const card = byKey(p, `row:${ts[0].id}`);
  card.fire('pointerdown');
  timers.fire(500);
  assert.match(main(p).textContent, /1 selected/);
  assert.ok(all(main(p)).some((e) => e.classList.contains('tbulk-box') && e.classList.contains('sheet')));
  byKey(p, `row:${ts[1].id}`).fire('click');
  assert.match(main(p).textContent, /2 selected/, 'then a tap toggles');
});
