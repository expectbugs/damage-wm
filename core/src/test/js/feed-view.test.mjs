// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-3, piece B17 (first built as package JF): the Feed page in the real page shell (`app.mjs`) over the DOM
// stand-in. A page load, a reload or an address marks nothing read — only the input that opens an item does (its
// source's whole list, B1), and an item opened while the PC is out of reach is marked when it answers again; mark all
// read sends the record's list with the unread ids held; a Flag is sent and its answer drawn; an action the level does
// not allow is not offered; an older PC is never asked for Feed; refresh and browse are program ops with their
// connection; a place is sent once per pause of the scroll, only after input, carrying that input's time; another
// device's newer input on the article shown moves the page and sends nothing (Reader's follow rule); a place answered
// `ended` goes again at the next welcome; the binge's place likewise; `seen` only after input; the comments' place
// stays on this browser; a span's text is text and a link is http or https only. No op carries an expectation (L1).

import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { openPage, browserStorage, handTimers, restore, settle } from './pagehost.mjs';
import { feedDesc, feedRecord, feedPage, feedChange, article, comments, comic, bingeWindow, itemId, row, READ_FRONT, F_NOW } from './feed-fixtures.mjs';
import { blockEl } from '../../main/resources/web/js/doc.mjs';

afterEach(restore);

const CONTRACTS = { reader: 1, notices: 1, torrents: 1, files: 1, feed: 1, tmux: 1 };
const welcome = (stream, extra = {}) => ({ t: 'welcome', proto: 4, device: 'web-1', level: 'full', conn: 7, pcClock: F_NOW, epoch: 3, seq: 10,
  contracts: CONTRACTS, bundle: { hash: 'h' }, ...extra });
/** Runs `fn` with this browser's clock at `ms` (the page stamps an input with `Date.now()` on the PC's clock). */
function at(ms, fn) {
  const real = Date.now;
  Date.now = () => ms;
  try {
    return fn();
  } finally {
    Date.now = real;
  }
}
const noExpect = (ops) => ops.every((o) => !('expect' in o));

async function page({ path = '/w/feed', level = 'full', contracts, record = true, storage = browserStorage() } = {}) {
  const timers = handTimers();
  const p = await openPage({ path, storage, timers, fetchAnswer: () => { throw new Error('no fetch expected'); } });
  const ws = p.sockets[0];
  ws.open();
  ws.frame(welcome(ws.sent[0].stream, { level, ...(contracts ? { contracts } : {}) }));
  if (!contracts) ws.frame({ t: 'snap', scope: 'desc/feed', desc: feedDesc() });
  if (record && !contracts) ws.frame(feedRecord());
  return { p, ws, timers, stream: ws.sent[0].stream };
}

const all = (root) => root.children.filter((c) => c.tagName).flatMap((c) => [c, ...all(c)]);
const main = (p) => p.doc.byId.main;
const find = (p, tag, text) => all(main(p)).find((e) => e.tagName === tag && (text === undefined || e.textContent === text));
const findAll = (p, tag) => all(main(p)).filter((e) => e.tagName === tag);
const byKey = (p, k) => all(main(p)).find((e) => e.getAttribute('data-key') === k);
const byClass = (p, c) => all(main(p)).find((e) => e.classList.contains(c));
const subs = (ws) => ws.sent.filter((f) => f.t === 'sub').map((f) => f.scope);
const queued = (ws, action) => ws.sent.filter((f) => f.t === 'op' && f.op.stream !== undefined && (!action || f.op.action === action)).map((f) => f.op);
const FRONT_READ = READ_FRONT.map((i) => itemId('front', i));
const prog = (ws) => ws.sent.filter((f) => f.t === 'op' && f.op.conn !== undefined).map((f) => f.op);
const snap = (ws, scope, desc) => ws.frame({ t: 'snap', scope, desc });
const ID0 = itemId('news', 0);

test('a page load at an item\'s address marks nothing read; it asks the article, its source\'s first page, Feed\'s description and record', async () => {
  const { p, ws } = await page({ path: `/w/feed/item:news:${ID0}` });
  assert.match(main(p).textContent, /Fetching the article on the PC…/, 'while it arrives');
  snap(ws, `feed/item:news:${ID0}`, article('news', ID0));
  snap(ws, 'feed/src:news/page:0', feedPage('news', 0));
  for (const s of ['desc/feed', 'feed', `feed/item:news:${ID0}`, 'feed/src:news/page:0']) assert.ok(subs(ws).includes(s), s);
  assert.deepEqual(queued(ws, 'read'), [], 'no read on a page load');
  assert.match(main(p).textContent, /every one of them/, 'the article is drawn');
  // a reconnect (a new welcome) marks nothing either
  p.sockets[0].close();
  assert.deepEqual(queued(ws, 'read'), []);
});

test('the input that opens an item marks it read once; an item already read sends nothing; Next item is input too', async () => {
  const { p, ws } = await page({ path: '/w/feed/src:front' });
  snap(ws, 'feed/src:front/page:0', feedPage('front', 0));
  const unread = itemId('front', 2);
  byKey(p, `item:${unread}`).fire('click');
  assert.equal(globalThis.location.pathname, `/w/feed/item:front:${unread}`);
  assert.deepEqual(queued(ws, 'read').map((o) => [o.targets, o.result]), [[['read.front'], [...FRONT_READ, unread]]], 'the source\'s whole list with it');
  assert.ok(noExpect(queued(ws)) && typeof queued(ws, 'read')[0].at === 'number', 'no expectation; the input\'s time');
  p.go('/w/feed/src:front');
  byKey(p, `item:${itemId('front', 1)}`).fire('click'); // read in the record
  assert.equal(queued(ws, 'read').length, 1, 'already read: nothing sent');
  snap(ws, `feed/item:front:${itemId('front', 1)}`, article('front', itemId('front', 1)));
  find(p, 'BUTTON', 'Next item').fire('click');
  // the list on its way: the second read is made from the first one's list, so the first is kept
  const last = queued(ws, 'read').at(-1);
  assert.deepEqual([last.targets, last.result.at(-1), last.result.length], [['read.front'], itemId('front', 2), FRONT_READ.length + 1]);
});

test('mark all read asks first, then carries the explicit unread ids this page holds, never a recipe', async () => {
  const { p, ws } = await page({ path: '/w/feed/src:front' });
  snap(ws, 'feed/src:front/page:0', feedPage('front', 0));
  find(p, 'BUTTON', 'Mark all read').fire('click');
  const dlg = p.doc.byId.confirm;
  assert.equal(dlg.open, true);
  assert.match(dlg.textContent, /Mark all of front page read\?/);
  dlg.querySelector('button.act').fire('click');
  await settle();
  const [op] = queued(ws, 'markAllRead');
  assert.deepEqual(op.targets, ['read.front']);
  assert.equal(op.result.length, 50, 'the ten the record holds read and the forty unread held');
  assert.deepEqual(op.result.slice(0, 10), FRONT_READ, 'the record\'s list first, as it stands');
  assert.equal(op.result.at(-1), itemId('front', 0), 'the newest last');
  assert.ok(noExpect([op]));
  assert.ok(all(main(p)).filter((e) => e.classList.contains('fd-row')).every((r) => r.classList.contains('read')), 'every row dims at once');
});

test('a Flag is sent with the item\'s facts and its answer drawn: Unflag at once, the record\'s value after; a refusal says the PC\'s reason', async () => {
  const { p, ws } = await page({ path: `/w/feed/item:news:${ID0}` });
  snap(ws, `feed/item:news:${ID0}`, article('news', ID0));
  find(p, 'BUTTON', 'Flag').fire('click');
  const [op] = queued(ws, 'flag');
  assert.deepEqual(op.targets, [`flag.${ID0}`]);
  assert.equal(op.result.s, 'news');
  assert.equal(op.result.t, article('news', ID0).item.title);
  assert.ok(noExpect([op]));
  assert.ok(find(p, 'BUTTON', 'Unflag'), 'drawn at once');
  ws.frame({ t: 'ans', stream: op.stream, n: op.n, answer: { a: 'seen' } });
  assert.ok(find(p, 'BUTTON', 'Unflag'), 'applied, the record\'s change not here yet: still drawn');
  ws.frame({ t: 'chg', scope: 'feed', epoch: 3, seq: 41, patches: [{ addr: `rec.feed.flag.${ID0}`, set: { v: op.result, by: 'web-1', at: F_NOW } }] });
  assert.ok(find(p, 'BUTTON', 'Unflag'), 'the record holds it');
  find(p, 'BUTTON', 'Unflag').fire('click');
  const un = queued(ws, 'unflag')[0];
  assert.deepEqual([un.targets, un.result, 'expect' in un], [[`flag.${ID0}`], null, false]);
  ws.frame({ t: 'ans', stream: un.stream, n: un.n, answer: { a: 'refused', code: 'level', reason: 'needs level control on this device' } });
  assert.match(p.said().at(-1), /Unflag: needs level control on this device/);
  assert.ok(find(p, 'BUTTON', 'Unflag'), 'refused: the record\'s value is drawn again');
});

test('a device at level view reads everything and is offered nothing that changes Feed or fetches on demand', async () => {
  const { p, ws } = await page({ path: `/w/feed/item:news:${ID0}`, level: 'view' });
  snap(ws, `feed/item:news:${ID0}`, article('news', ID0));
  snap(ws, 'feed/src:news/page:0', feedPage('news', 0));
  const labels = findAll(p, 'BUTTON').map((b) => b.textContent);
  for (const l of ['Flag', 'Unflag', 'Mark unread', 'Mark read', 'Mark all read', 'Refresh', 'Refresh all']) assert.ok(!labels.includes(l), l);
  assert.ok(labels.some((l) => /^Comments/.test(l)), 'comments are reading');
  assert.match(main(p).textContent, /every one of them/);
  assert.equal(byClass(p, 'fd-browse'), undefined, 'no browse');
  p.go('/w/feed/settings');
  assert.match(main(p).textContent, /Fetch15 minchanging it needs level control/);
});

test('a PC whose build names no Feed contract is never asked for it; the page and the rail say why', async () => {
  const { p, ws } = await page({ contracts: { reader: 1, notices: 1, torrents: 1, files: 1 } });
  assert.equal(subs(ws).some((s) => s === 'desc/feed' || s === 'feed' || s.startsWith('feed/')), false);
  assert.match(main(p).textContent, /Not shown here: the PC's build has no Feed page yet/);
  const rail = all(p.doc.byId.rail).find((e) => e.getAttribute('data-key') === 'rail:feed');
  assert.ok(rail.classList.contains('dim'));
  assert.match(rail.getAttribute('data-why'), /no Feed page yet/);
});

test('out of reach: actions dim with why; an item opened then is marked read when the PC answers again, and only that one', async () => {
  const { p, ws, timers } = await page({ path: '/w/feed/src:front' });
  snap(ws, 'feed/src:front/page:0', feedPage('front', 0));
  timers.tick();
  timers.tick(); // a ping unanswered when the next is due
  assert.ok(p.doc.body.classList.contains('away'));
  const mark = find(p, 'BUTTON', 'Mark all read');
  assert.match(mark.getAttribute('data-why'), /PC not answering since/);
  byKey(p, `item:${itemId('front', 2)}`).fire('click');
  assert.deepEqual(queued(ws, 'read'), [], 'nothing sent out of reach');
  assert.match(main(p).textContent, /as the PC last described it at/);
  ws.frame({ t: 'pong', k: 2 });
  assert.deepEqual(queued(ws, 'read').map((o) => [o.targets, o.result.at(-1)]), [[['read.front'], itemId('front', 2)]], 'sent once it answers');
});

test('refresh is a program op with its connection and source; Sent is said, a refusal says the PC\'s reason', async () => {
  const { p, ws } = await page({ path: '/w/feed/src:front' });
  snap(ws, 'feed/src:front/page:0', feedPage('front', 0));
  find(p, 'BUTTON', 'Refresh').fire('click');
  const [op] = prog(ws);
  assert.deepEqual([op.conn, op.window, op.action, op.targets, op.stream], [7, 'feed', 'refresh', ['front'], undefined]);
  assert.match(main(p).textContent, /Refresh front page · sent, no answer from the PC yet · 0 s/);
  ws.frame({ t: 'ans', conn: 7, k: op.k, answer: { a: 'sent' } });
  assert.match(p.said().at(-1), /Refresh front page: sent to the PC\. The list moves when the PC has fetched\./);
  find(p, 'BUTTON', 'Refresh all').fire('click');
  const all2 = prog(ws).at(-1);
  assert.deepEqual(all2.targets, ['front', 'news', 'stick', 'panel', 'r:gardening'], 'every source listed but the archive');
  ws.frame({ t: 'ans', conn: 7, k: all2.k, answer: { a: 'refused', code: 'program', reason: 'the engine is not running on this PC' } });
  assert.match(p.said().at(-1), /Refresh all: the engine is not running on this PC/);
});

test('browse stages the typed name on its button, opens the source the PC answers and keeps it first in the recents', async () => {
  const { p, ws } = await page({ path: '/w/feed/src:front' });
  snap(ws, 'feed/src:front/page:0', feedPage('front', 0));
  const go = () => byKey(p, 'browse:go');
  assert.match(go().getAttribute('data-why'), /type a subreddit name/);
  const q = byKey(p, 'browse:q');
  q.value = 'r/Knots';
  q.fire('input', { target: q });
  assert.equal(go().textContent, 'Browse r/Knots');
  go().fire('click');
  const [op] = prog(ws);
  assert.deepEqual([op.action, op.params], ['browse', { kind: 'reddit', name: 'Knots' }]);
  ws.frame({ t: 'ans', conn: 7, k: op.k, answer: { a: 'sent', result: { source: 'r:knots' } } });
  const [rc] = queued(ws, 'recent');
  assert.deepEqual([rc.targets, rc.result, 'expect' in rc], [['recent.reddit'], ['knots', 'gardening', 'woodworking'], false]);
  assert.equal(globalThis.location.pathname, '/w/feed/src:r:knots');
  assert.ok(subs(ws).includes('feed/src:r:knots/page:0'));
});

test('an article\'s place is sent once per pause of the scroll after input, carrying that input\'s time, never for the page\'s own moves, and on leaving', async () => {
  const { p, ws, timers } = await page({ path: `/w/feed/item:news:${ID0}` });
  snap(ws, `feed/item:news:${ID0}`, article('news', ID0));
  const read = byClass(p, 'fd-read');
  read.fire('scroll');
  timers.fire(2000);
  assert.deepEqual(queued(ws, 'place'), [], 'a scroll no input made sends nothing');
  const t0 = Date.now();
  at(t0 + 5000, () => read.fire('wheel'));
  for (let i = 0; i < 5; i++) read.fire('scroll');
  assert.deepEqual(queued(ws, 'place'), [], 'nothing before the pause');
  at(t0 + 9000, () => timers.fire(2000));
  const places = queued(ws, 'place');
  assert.equal(places.length, 1, 'one op for the pause, not one per scroll');
  assert.deepEqual(places[0].targets, ['place'], 'the one shared place');
  assert.deepEqual([places[0].result.item, places[0].result.v], [ID0, 1], 'the article and its extractor version');
  // the PC's clock is the welcome's: the wheel's time on it, not the save point's
  assert.ok(Math.abs(places[0].at - (F_NOW + 5000)) < 1000, `the input's time (${places[0].at - F_NOW})`);
  assert.ok(noExpect(places));
  read.fire('wheel');
  read.fire('scroll');
  timers.fire(2000);
  assert.equal(queued(ws, 'place').length, 1, 'the same place is not sent again');
  // read on to the fourth block (the layout the stand-in has none of: the first three end above the column's top)
  all(main(p)).filter((e) => e.classList.contains('blk')).forEach((b, i) => { b.getBoundingClientRect = () => ({ top: 0, left: 0, bottom: i < 3 ? 0 : 1000, height: 0 }); });
  read.fire('wheel');
  read.fire('scroll');
  p.go('/w/feed/src:news');
  const left = queued(ws, 'place');
  assert.equal(left.length, 2, 'leaving before the pause sends the place read on to');
  assert.equal(left[1].result.b, 3);
});

test('fix B5: a click in the reading column (Flag, Open the original) never moves the place — leaving after it, or a scroll the page makes after its save point, sends none; in the archive too', async () => {
  const { p, ws, timers } = await page({ path: `/w/feed/item:news:${ID0}` });
  snap(ws, `feed/item:news:${ID0}`, article('news', ID0));
  const read = byClass(p, 'fd-read');
  read.fire('pointerdown'); // the click on Flag, as the column hears it
  find(p, 'BUTTON', 'Flag').fire('click');
  assert.equal(queued(ws, 'flag').length, 1, 'the flag went');
  read.fire('pointerdown');
  timers.fire(2000); // its save point: the gesture is over
  read.fire('scroll'); // a picture loaded above: the page's own move
  timers.fire(2000);
  p.go('/w/feed/src:news');
  assert.deepEqual(queued(ws, 'place'), [], 'no place from a click, leaving included');
  // the archive: a click, then pictures loading move the top after the save point
  const b = await page({ path: '/w/feed/binge:quest' });
  snap(b.ws, 'feed/binge:quest/around:412', bingeWindow('quest', 412));
  const col = byClass(b.p, 'fd-read');
  col.fire('pointerdown');
  b.timers.fire(2000);
  col.fire('scroll');
  col.fire('scroll');
  b.timers.fire(2000);
  b.p.go('/w/feed/src:news');
  assert.deepEqual(queued(b.ws, 'binge'), [], 'no archive place from a click');
});

test('the page\'s own move into an article (an entry at its place) is never sent, leaving included; another article\'s place opens this one at its top', async () => {
  const { p, ws } = await page({ path: `/w/feed/item:news:${ID0}` });
  ws.frame(feedChange(41, 'place', { item: ID0, b: 4, o: 0, v: 1 }));
  snap(ws, `feed/item:news:${ID0}`, article('news', ID0));
  byClass(p, 'fd-read').fire('scroll'); // the entry's own scroll to the shared place
  p.go('/w/feed/src:news');
  assert.deepEqual(queued(ws, 'place'), []);
});

test('the follow rule: another device\'s newer input on the article shown moves the page there and sends nothing; one older than this page\'s input does not', async () => {
  const { p, ws, timers } = await page({ path: `/w/feed/item:news:${ID0}` });
  snap(ws, `feed/item:news:${ID0}`, article('news', ID0));
  const read = byClass(p, 'fd-read');
  // block i's top at i × 100 px below the column's top, wherever the column is scrolled
  all(main(p)).filter((e) => e.classList.contains('blk')).forEach((b, i) => { b.getBoundingClientRect = () => ({ top: i * 100 - read.scrollTop, left: 0, bottom: i * 100 + 90 - read.scrollTop, height: 90 }); });
  read.scrollTop = 0;
  ws.frame(feedChange(41, 'place', { item: ID0, b: 4, o: 0, v: 1 }, F_NOW + 60e3));
  assert.equal(read.scrollTop, 400 - 8, 'moved to the fourth block\'s top (the pad above it)');
  timers.fire(2000);
  assert.deepEqual(queued(ws, 'place'), [], 'a follow is the page\'s own move: nothing sent');
  ws.frame(feedChange(42, 'place', { item: itemId('news', 3), b: 1, o: 0, v: 1 }, F_NOW + 61e3));
  assert.equal(read.scrollTop, 392, 'another article opened elsewhere: this page stays');
  // his own input here, later than the next move elsewhere: his place stands
  const t0 = Date.now();
  at(t0 + 120e3, () => read.fire('wheel'));
  ws.frame(feedChange(43, 'place', { item: ID0, b: 1, o: 0, v: 1 }, F_NOW + 90e3));
  assert.equal(read.scrollTop, 392, 'older than this page\'s own input: not followed');
  ws.frame(feedChange(44, 'place', { item: ID0, b: 2, o: 0, v: 1 }, F_NOW + 900e3));
  assert.equal(read.scrollTop, 200 - 8, 'a newer input elsewhere moves it again');
});

test('a place answered `ended` (the connection ended before its answer) goes again at the next welcome, with its input\'s time', async () => {
  const { p, ws, timers } = await page({ path: `/w/feed/item:news:${ID0}` });
  snap(ws, `feed/item:news:${ID0}`, article('news', ID0));
  const read = byClass(p, 'fd-read');
  read.fire('wheel');
  read.fire('scroll');
  timers.fire(2000);
  const [op] = queued(ws, 'place');
  ws.close(); // the connection ends: the page answers its open ops itself
  timers.fire(2000); // the reconnect's pacing
  const ws2 = p.sockets[1];
  ws2.open();
  ws2.frame(welcome(ws2.sent[0].stream));
  const again = queued(ws2, 'place');
  assert.equal(again.length, 1, 'sent again on the new socket');
  assert.deepEqual([again[0].result, again[0].at], [op.result, op.at]);
});

test('the binge opens at the shared place and sends its own after input, once per pause', async () => {
  const { p, ws, timers } = await page({ path: '/w/feed/binge:quest' });
  assert.ok(subs(ws).includes('feed/binge:quest/around:412'), 'the record\'s episode');
  snap(ws, 'feed/binge:quest/around:412', bingeWindow('quest', 412));
  assert.match(main(p).textContent, /Episode 409/);
  assert.match(main(p).textContent, /Finding this episode's picture on the PC…/, 'a pending picture is said');
  assert.equal(findAll(p, 'IMG').filter((i) => /^\/blob\/feed\.quest-/.test(i.getAttribute('src'))).length, 8);
  const read = byClass(p, 'fd-read');
  read.fire('wheel');
  read.fire('scroll');
  read.fire('scroll');
  timers.fire(2000);
  const [op] = queued(ws, 'binge');
  assert.deepEqual(op.targets, ['binge.quest']);
  assert.deepEqual([op.result.s, op.result.w], [null, null], 'the glasses\' strip hint is left to the glasses');
  assert.ok(noExpect([op]) && typeof op.at === 'number');
  // the glasses read on to another episode: the page follows by a new window around it, and sends nothing
  ws.frame(feedChange(41, 'binge.quest', { ep: 600, f: null, s: 0, w: 564 }, F_NOW + 3600e3));
  assert.ok(subs(ws).includes('feed/binge:quest/around:600'));
  timers.fire(2000);
  assert.equal(queued(ws, 'binge').length, 1, 'a follow sends nothing');
});

test('`seen` goes when a source opened by input shows a page, never on a page load', async () => {
  const a = await page({ path: '/w/feed/src:front' });
  snap(a.ws, 'feed/src:front/page:0', feedPage('front', 0));
  assert.deepEqual(queued(a.ws, 'seen'), []);
  restore();
  const { p, ws } = await page({ path: '/w/feed' });
  byKey(p, 'src:front').fire('click');
  snap(ws, 'feed/src:front/page:0', feedPage('front', 0));
  const [op] = queued(ws, 'seen');
  assert.deepEqual([op.targets, op.result, 'expect' in op], [['seen.front'], Math.max(...feedPage('front', 0).rows.map((r) => r.published)), false]);
});

test('the comments\' place stays on this browser: kept on a pause of the scroll, never sent to the PC', async () => {
  const storage = browserStorage();
  const id = itemId('news', 0);
  const { p, ws, timers } = await page({ path: `/w/feed/comments:news:${id}`, storage });
  snap(ws, `feed/comments:news:${id}`, comments('news', id));
  assert.ok(byClass(p, 'd4'), 'four deep');
  const read = byClass(p, 'fd-read');
  read.fire('wheel');
  read.fire('scroll');
  timers.fire(2000);
  assert.match(storage.m.get('damage.feed.comments'), new RegExp(id));
  assert.deepEqual(ws.sent.filter((f) => f.t === 'op'), [], 'nothing sent');
});

test('a comic: its bar dims what is not there with why; a flip is input and marks the strip read; random opens a number in range', async () => {
  const { p, ws } = await page({ path: '/w/feed/num:stick:2961' });
  snap(ws, 'feed/num:stick:2961', comic('stick', null, 2961));
  assert.equal(queued(ws, 'read').length, 0, 'a page load marks nothing');
  const next = byKey(p, 'bar:next');
  assert.match(next.getAttribute('data-why'), /this is the latest/);
  byKey(p, 'bar:prev').fire('click');
  assert.equal(globalThis.location.pathname, '/w/feed/num:stick:2960');
  snap(ws, 'feed/num:stick:2960', comic('stick', null, 2960));
  assert.deepEqual(queued(ws, 'read').map((o) => [o.targets, o.result]), [[['read.stick'], [itemId('stick', 1)]]]);
  byKey(p, 'bar:random').fire('click');
  const m = /^\/w\/feed\/num:stick:(\d+)$/.exec(globalThis.location.pathname);
  assert.ok(m && Number(m[1]) >= 1 && Number(m[1]) <= 2961 && Number(m[1]) !== 2960);
  assert.match(main(p).textContent, /Fetching the comic on the PC…/, 'while it arrives');
});

test('an article\'s pictures come from the PC\'s blobs; Images off on this browser draws none; a refusal shows the PC\'s reason', async () => {
  const storage = browserStorage();
  const a = await page({ path: `/w/feed/item:news:${ID0}`, storage });
  snap(a.ws, `feed/item:news:${ID0}`, article('news', ID0));
  assert.ok(findAll(a.p, 'IMG').some((i) => i.getAttribute('src') === '/blob/feed.art-chart'));
  assert.match(main(a.p).textContent, /Fetching|every one/);
  restore();
  storage.m.set('damage.feed.images', 'off');
  const b = await page({ path: `/w/feed/item:news:${ID0}`, storage });
  snap(b.ws, `feed/item:news:${ID0}`, article('news', ID0));
  assert.equal(findAll(b.p, 'IMG').length, 0);
  assert.match(main(b.p).textContent, /Images is off on this browser/);
  b.p.go(`/w/feed/item:news:${itemId('news', 5)}`);
  b.ws.frame({ t: 'ans', scope: `feed/item:news:${itemId('news', 5)}`, answer: { a: 'refused', code: 'program', reason: 'the site answered 403' } });
  assert.match(main(b.p).textContent, /This article could not be read: the site answered 403/);
});

test('a settings row is a `set` op; device rows are this browser\'s own', async () => {
  const { p, ws } = await page({ path: '/w/feed/settings' });
  const sel = byKey(p, 'set:fetch');
  sel.value = '30 min';
  sel.fire('change', { target: sel });
  const [op] = queued(ws, 'set');
  assert.deepEqual([op.targets, op.result, 'expect' in op], [['fetch'], '30 min', false]);
  const n = byKey(p, 'set:notify.reddit');
  assert.equal(n.getAttribute('checked'), '', 'the record\'s value (on) over the description\'s (off)');
  assert.equal(byKey(p, 'set:images').tagName, 'INPUT');
  assert.equal(all(main(p)).filter((e) => e.getAttribute('data-key') === 'set:images').length, 1, 'Images once: this browser\'s, not the PC\'s row');
});

test('the renderer: a span\'s text is text, never markup; a link is http or https only, out of the page with no referrer', async () => {
  await page({ path: '/w/feed' });
  const el = blockEl({ kind: 'para', spans: [{ text: '<img src=x onerror=alert(1)>', marks: ['em'] }, { text: 'run', href: 'javascript:alert(1)' },
    { text: 'ok', href: 'https://example.org/a' }] }, { at: 0, where: 'Damage' });
  const kids = all(el);
  assert.equal(kids.some((e) => e.tagName === 'IMG'), false, 'no element made from text');
  assert.equal(el.textContent, '<img src=x onerror=alert(1)>runok');
  const links = kids.filter((e) => e.tagName === 'A');
  assert.equal(links.length, 1, 'javascript: is not a link');
  assert.deepEqual([links[0].getAttribute('href'), links[0].getAttribute('target'), links[0].getAttribute('rel')], ['https://example.org/a', '_blank', 'noopener noreferrer']);
  assert.match(links[0].getAttribute('title'), /outside Damage/);
  assert.ok(kids[0].classList.contains('em'));
});

test('Flagged lists the record\'s flags with their facts and opens one; Back returns to Flagged', async () => {
  const { p, ws } = await page({ path: '/w/feed/flagged' });
  const r = row('news', 2);
  assert.match(main(p).textContent, new RegExp(r.title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  byKey(p, `item:${r.id}`).fire('click');
  assert.equal(globalThis.location.pathname, `/w/feed/item:news:${r.id}`);
  assert.deepEqual(queued(ws, 'read').map((o) => [o.targets, o.result]), [[['read.news'], [r.id]]]);
  assert.equal(subs(ws).includes('feed/src:news/page:0'), false, 'opened from Flagged: its source\'s list is not asked');
  find(p, 'A', '← Flagged').fire('click');
  assert.equal(globalThis.location.pathname, '/w/feed/flagged');
});

test('a new item opens at its own top, never at the scroll the last one was left at', async () => {
  const { p, ws } = await page({ path: '/w/feed/src:front' });
  snap(ws, 'feed/src:front/page:0', feedPage('front', 0));
  byKey(p, `item:${itemId('front', 2)}`).fire('click');
  snap(ws, `feed/item:front:${itemId('front', 2)}`, article('front', itemId('front', 2)));
  byClass(p, 'fd-read').scrollTop = 900;
  find(p, 'BUTTON', 'Next item').fire('click');
  assert.equal(byClass(p, 'fd-read').scrollTop, 0);
});

test('a countdown and an age move in place: the column is not drawn again under the pointer each second', async () => {
  const { p, timers } = await page({ path: '/w/feed' });
  const real = Date.now;
  const t0 = real();
  const hl = () => all(main(p)).find((e) => e.getAttribute('data-hl') === 'r:gardening');
  const before = hl();
  const row = byKey(p, 'src:front');
  assert.match(before.textContent, /retry in 50 s/);
  Date.now = () => t0 + 20e3;
  try {
    timers.fire(1000);
  } finally {
    Date.now = real;
  }
  assert.match(hl().textContent, /retry in 30 s/);
  assert.equal(hl(), before, 'the same element');
  assert.equal(byKey(p, 'src:front'), row, 'the rows stay');
});

test('a subreddit typed into browse stays where it is typed; only its button follows', async () => {
  const { p, ws } = await page({ path: '/w/feed/src:front' });
  snap(ws, 'feed/src:front/page:0', feedPage('front', 0));
  const q = byKey(p, 'browse:q');
  q.value = 'knots';
  q.fire('input', { target: q });
  assert.equal(byKey(p, 'browse:q'), q, 'the field is not drawn again');
  assert.equal(byKey(p, 'browse:go').textContent, 'Browse r/knots');
});

test('the archive: First and Latest say why until its range is known; a window let go at a jump is not merged', async () => {
  const { p, ws } = await page({ path: '/w/feed/binge:quest' });
  assert.match(byKey(p, 'b:first').getAttribute('data-why'), /still being read/);
  snap(ws, 'feed/binge:quest/around:412', bingeWindow('quest', 412));
  byKey(p, 'b:first').fire('click');
  assert.ok(subs(ws).includes('feed/binge:quest/around:1'));
  snap(ws, 'feed/binge:quest/around:412', bingeWindow('quest', 412)); // a late answer for the window let go
  assert.doesNotMatch(main(p).textContent, /Episode 412/);
  snap(ws, 'feed/binge:quest/around:1', bingeWindow('quest', 1));
  assert.match(main(p).textContent, /Episode 1 · /);
  assert.doesNotMatch(main(p).textContent, /Episode 412/);
});
