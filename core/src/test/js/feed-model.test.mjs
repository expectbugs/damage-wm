// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-3, piece B17 (first built as package JF): Feed's page model under `node --test` (pure: no DOM, no network).
// Addresses with source ids that hold `:`; a countdown from `retryAtMs` and the PC's clock, never from a text; a
// browsed source listed only while pinned or open; reads as one list per source (B1), a second read made from the
// first one's list on its way; mark all read as the record's list with the ids held; a flag carries its facts; no op
// carries an expectation (L1); recents deduped and capped; `seen` only forward; a link only for http or https; a cached
// block without spans drawn as one plain span; the follow rule of a shared place; the binge's window grows one window
// at a time; the comic bar's random stays in its range.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as M from '../../main/resources/web/js/feed-model.mjs';
import { ClientRecord } from '../../main/resources/web/js/record.mjs';
import { feedDesc, feedRecord, feedPage, bingeWindow, comic, row, itemId, F_NOW } from './feed-fixtures.mjs';

const recOf = (items = {}) => {
  const r = new ClientRecord();
  r.snap({ epoch: 1, seq: 1, items }, ['rec.feed.']);
  return r;
};
const it = (v, by = 'phone-1', at = F_NOW) => ({ v, by, at });

test('addresses: a source id may hold a colon; an item id never does; anything else is the sources', () => {
  const id = itemId('r:gardening', 3);
  assert.deepEqual(M.placeOf(`item:r:gardening:${id}`), { at: 'item', src: 'r:gardening', id });
  assert.deepEqual(M.placeOf('src:r:gardening'), { at: 'src', src: 'r:gardening' });
  assert.deepEqual(M.placeOf('num:stick:2961'), { at: 'num', src: 'stick', n: 2961 });
  assert.deepEqual(M.placeOf('binge:quest'), { at: 'binge', src: 'quest' });
  assert.deepEqual(M.placeOf(`comments:news:${id}`), { at: 'comments', src: 'news', id });
  assert.deepEqual(M.placeOf(null), { at: 'sources' });
  assert.equal(M.placeOf('num:stick:x').bad, 'num:stick:x');
  assert.equal(M.placeOf('weather:today').bad, 'weather:today');
  for (const t of ['src:front', `item:r:knots:${id}`, 'num:stick:7', 'binge:quest', 'flagged', 'settings', `comments:front:${id}`]) assert.equal(M.targetOf(M.placeOf(t)), t);
  assert.deepEqual(M.parentOf({ at: 'item', src: 'front', id }, 'flagged'), { at: 'flagged' });
  assert.deepEqual(M.parentOf({ at: 'comments', src: 'front', id }), { at: 'item', src: 'front', id });
  assert.deepEqual(M.parseAnchor('@b12.40'), { b: 12, o: 40 });
  assert.deepEqual(M.parseAnchor('@e412.0.25'), { ep: 412, f: 0.25 });
  assert.equal(M.parseAnchor('@c120.1~x'), null);
});

test('health: a countdown from retryAtMs on the PC\'s clock, never from a text the PC wrote', () => {
  const s = { id: 'r:x', health: { state: 'limited', host: 'feeds.example.com', retryAtMs: F_NOW + 50e3 }, state: 'rate-limited · retry 9 h' };
  assert.deepEqual(M.healthOf(s, F_NOW), { cls: 'limited', text: 'feeds.example.com rate-limited · retry in 50 s' });
  assert.equal(M.healthOf(s, F_NOW + 20e3).text, 'feeds.example.com rate-limited · retry in 30 s', 'it counts down as the clock moves');
  assert.equal(M.healthOf(s, F_NOW + 60e3).text, 'feeds.example.com rate-limited · retrying now');
  const f = { health: { state: 'failing', failLine: 'feeds.example.net answered 503', failSinceMs: Date.UTC(2026, 10, 15, 13, 22), retryAtMs: F_NOW + 95e3 } };
  assert.match(M.healthOf(f, F_NOW).text, /^fetch failing since \d+:22: feeds\.example\.net answered 503 · next try in 1 min$/);
  assert.equal(M.healthOf({ health: { state: 'ok' } }, F_NOW), null);
  assert.equal(M.counting(feedDesc(), F_NOW), true);
  assert.equal(M.counting(feedDesc(), F_NOW + 200e3), false);
});

test('the sources listed: every configured one; a browsed one only while pinned (the record over the description) or open', () => {
  const d = feedDesc();
  const rec = recOf({ 'rec.feed.pin.r:gardening': it(false) });
  const pend = new M.Pending();
  const ids = (open) => M.sourcesShown(d, (s) => M.pinOf(rec, pend, s), open).map((s) => s.id);
  assert.deepEqual(ids(null), ['front', 'news', 'stick', 'panel', 'quest'], 'unpinned in the record: gone though the description says pinned');
  assert.ok(ids('r:knots').includes('r:knots'), 'the one open is listed');
  pend.set('pin.r:gardening', true);
  assert.ok(ids(null).includes('r:gardening'), 'this page\'s pin shows at once');
  assert.equal(M.sourceLine(d.sources[0], F_NOW, null), '120 items · 12 new · fetched 4 min ago');
  assert.equal(M.sourceLine(d.sources[4], F_NOW, { ep: 412 }), 'page 412 of 1225');
  assert.equal(M.sourceLine(d.sources[4], F_NOW, null), '1225 pages · from the start');
});

test('reads are one list per source: the record\'s, else the row\'s; this page\'s list on its way over the record', () => {
  const r0 = row('front', 0, { read: true, flagged: true });
  const r1 = row('front', 1);
  const rec = recOf({ 'rec.feed.read.front': it([r1.id]) });
  const pend = new M.Pending();
  assert.equal(M.readOf(rec, pend, 'front', r0.id, r0), false, 'the record\'s list over the row');
  assert.equal(M.readOf(rec, pend, 'front', r1.id, r1), true);
  assert.equal(M.readOf(recOf(), pend, 'front', r0.id, r0), true, 'no list in the record: the row');
  // two reads in one source before the first lands: the second is made from the first one's list
  const d1 = M.readDraft('front', r0.id, M.readList(rec, pend, 'front'));
  assert.deepEqual(d1, { window: 'feed', action: 'read', targets: ['read.front'], result: [r1.id, r0.id] });
  const t1 = pend.set('read.front', d1.result);
  const r2 = row('front', 2);
  const d2 = M.readDraft('front', r2.id, M.readList(rec, pend, 'front'));
  assert.deepEqual(d2.result, [r1.id, r0.id, r2.id], 'the first read is kept in the second list');
  const t2 = pend.set('read.front', d2.result);
  assert.equal(M.readDraft('front', r0.id, M.readList(rec, pend, 'front')), null, 'read already: nothing to send');
  pend.answered('read.front', t1, true);
  assert.equal(pend.has('read.front'), true, 'an earlier op\'s answer leaves a later one on its way');
  rec.chg({ epoch: 1, seq: 2, patches: [{ addr: 'rec.feed.read.front', set: it(d1.result, 'web-1') }] });
  pend.landed(new Set(['rec.feed.read.front']), rec);
  assert.equal(M.readOf(rec, pend, 'front', r2.id, r2), true, 'the record holds the first list; the second is still shown');
  rec.chg({ epoch: 1, seq: 3, patches: [{ addr: 'rec.feed.read.front', set: it(d2.result, 'web-1') }] });
  pend.landed(new Set(['rec.feed.read.front']), rec);
  assert.equal(pend.has('read.front'), false, 'held by the record: the record\'s');
  pend.answered('read.front', t2, true);
  // applied, then another device's later input on the same list: that one is shown
  const t3 = pend.set('read.front', [r0.id]);
  pend.answered('read.front', t3, true);
  rec.chg({ epoch: 1, seq: 4, patches: [{ addr: 'rec.feed.read.front', set: it([], 'phone-1') }] });
  pend.landed(new Set(['rec.feed.read.front']), rec);
  assert.equal(M.readOf(rec, pend, 'front', r0.id, r0), false, 'the later input on another device wins');
  const t4 = pend.set('pin.r:x', true);
  pend.answered('pin.r:x', t4, false);
  assert.equal(pend.has('pin.r:x'), false, 'refused or unanswered: the record\'s again');
  const full = new ClientRecord();
  full.snap(feedRecord(), ['rec.feed.']);
  const fl = M.flaggedRows(full, new M.Pending());
  assert.deepEqual(fl.map((f) => f.src), ['stick', 'news', 'front'], 'newest flag first');
  const p2 = new M.Pending();
  p2.set(`flag.${fl[0].id}`, null);
  assert.equal(M.flaggedRows(full, p2).length, 2, 'an unflag on its way leaves Flagged at once');
});

test('mark all read: the record\'s list with the unread ids held, the newest last, at most 600; nothing new is nothing', () => {
  const d = M.markAllDraft('front', ['a3', 'a2', 'a1', 'a2'], ['a0', 'a2']);
  assert.deepEqual(d, { window: 'feed', action: 'markAllRead', targets: ['read.front'], result: ['a0', 'a2', 'a1', 'a3'] });
  assert.equal(M.markAllDraft('front', ['a0'], ['a0']), null);
  const long = Array.from({ length: 600 }, (_, i) => `o${i}`);
  const big = M.markAllDraft('front', ['n2', 'n1'], long).result;
  assert.deepEqual([big.length, big[0], big.at(-2), big.at(-1)], [600, 'o2', 'n1', 'n2'], 'the oldest let go');
});

test('a flag carries the item\'s facts; mark read and unread move one id; no op carries an expectation (L1)', () => {
  const r = row('news', 2);
  const d = M.flagDraft(r, 'news', true, F_NOW);
  assert.deepEqual(d, { window: 'feed', action: 'flag', targets: [`flag.${r.id}`], result: { t: r.title, l: r.link, s: 'news', p: r.published, k: 'LINK', a: F_NOW } });
  assert.deepEqual(M.flagDraft(r, 'news', false, F_NOW).result, null);
  assert.deepEqual(M.markDraft('news', r.id, false, ['x', r.id]), { window: 'feed', action: 'unread', targets: ['read.news'], result: ['x'] });
  assert.equal(M.markDraft('news', r.id, false, ['x']), null, 'unread already: nothing');
  assert.deepEqual(M.markDraft('news', r.id, true, null).result, [r.id]);
  assert.deepEqual(M.pinDraft('r:x', true), { window: 'feed', action: 'pin', targets: ['pin.r:x'], result: true });
  assert.deepEqual(M.setDraft({ id: 'fetch' }, '5 min'), { window: 'feed', action: 'set', targets: ['fetch'], result: '5 min' });
  for (const x of [d, M.pinDraft('r:x', false), M.recentDraft('reddit', 'a', recOf(), null), M.markAllDraft('f', ['a'], [])]) assert.equal('expect' in x, false);
});

test('recents: the whole list with the new name first, deduped, at most five; seen only moves forward', () => {
  const rec = recOf({ 'rec.feed.recent.reddit': it(['a', 'b', 'c', 'd', 'e']) });
  assert.deepEqual(M.recentDraft('reddit', 'c', rec, null).result, ['c', 'a', 'b', 'd', 'e']);
  assert.deepEqual(M.recentDraft('reddit', 'z', rec, null).result, ['z', 'a', 'b', 'c', 'd']);
  const rows = feedPage('front', 0).rows;
  const top = Math.max(...rows.map((x) => x.published));
  assert.deepEqual(M.seenDraft('front', rows, recOf()), { window: 'feed', action: 'seen', targets: ['seen.front'], result: top });
  assert.equal(M.seenDraft('front', rows, recOf({ 'rec.feed.seen.front': it(top) })), null, 'nothing newer shown: nothing sent');
  assert.equal(M.subName(' r/Knots '), 'Knots');
  assert.equal(M.subName('two words'), null);
  assert.equal(M.browseLabel('reddit', 'knots'), 'Browse r/knots');
  assert.equal(M.browseLabel('slashdot', 'Linux'), 'Browse linux');
  assert.equal(M.browsed({ source: 'r:knots' }), 'r:knots');
  assert.equal(M.browsed('s:linux'), 's:linux');
  assert.equal(M.browsed({}), null);
});

test('spans: a link only for http or https; marks em, strong and code; a cached block without spans is one plain span', () => {
  const b = { kind: 'para', spans: [{ text: 'a', marks: ['em'] }, { text: 'b', marks: ['link'], href: 'javascript:alert(1)' },
    { text: 'c', marks: ['link'], href: 'data:text/html,x' }, { text: 'd', marks: ['link', 'strong'], href: 'https://example.org/x' }, { text: 'e', href: '/relative' }] };
  assert.deepEqual(M.spansOf(b), [{ text: 'a', marks: ['em'] }, { text: 'b', marks: [] }, { text: 'c', marks: [] },
    { text: 'd', marks: ['strong'], href: 'https://example.org/x' }, { text: 'e', marks: [] }]);
  assert.deepEqual(M.spansOf({ kind: 'para', text: 'old cached text' }), [{ text: 'old cached text', marks: [] }]);
  assert.equal(M.safeHref(' https://example.org/a b'), null, 'a space inside is not a link');
  assert.equal(M.blobUrl('feed.ab12'), '/blob/feed.ab12');
  assert.equal(M.blobUrl('https://example.org/x.png'), null, 'never a URL as a picture');
  assert.equal(M.blobUrl('f.abc'), null, "never another window's blob");
  const blocks = M.blocksOf({ blocks: [{ kind: 'heading', level: 1, spans: [{ text: 'h' }] }, { kind: 'para', spans: [{ text: 'p' }] }] });
  assert.deepEqual(blocks.map((x) => x.i), [0, 1]);
});

test('the follow rule (Reader\'s): the entry takes this thing\'s place; another device\'s newer input moves the page; the page\'s own later input wins', () => {
  const art = 'a1';
  const f = new M.Follow((v) => !!v && v.item === art, M.placeKey);
  assert.equal(f.enter({ v: { item: 'other', b: 3, o: 0, v: 1 }, by: 'phone-1', at: 1 }), null, 'another article\'s place: the top');
  assert.deepEqual(f.enter({ v: { item: art, b: 3, o: 0, v: 1 }, by: 'phone-1', at: 1 }), { item: art, b: 3, o: 0, v: 1 });
  assert.equal(f.save(M.placeValue(art, 3, 0, 1)), false, 'the place taken is not sent back');
  f.inputAt = 100;
  assert.equal(f.save(M.placeValue(art, 5, 10, 1)), true);
  assert.equal(f.save(M.placeValue(art, 5, 10, 1)), false, 'the same place twice is one op');
  assert.equal(f.follow({ v: { item: art, b: 9, o: 0, v: 1 }, by: 'phone-1', at: 99 }, 'web-1'), null, 'older than this page\'s own input: its place wins');
  assert.equal(f.follow({ v: { item: art, b: 9, o: 0, v: 1 }, by: 'web-1', at: 200 }, 'web-1'), null, 'its own write is not a move');
  assert.equal(f.follow({ v: { item: 'other', b: 9, o: 0, v: 1 }, by: 'phone-1', at: 200 }, 'web-1'), null, 'another article opened elsewhere: not followed');
  assert.deepEqual(f.follow({ v: { item: art, b: 9, o: 0, v: 1 }, by: 'phone-1', at: 200 }, 'web-1'), { item: art, b: 9, o: 0, v: 1 });
  assert.equal(f.save(M.placeValue(art, 9, 0, 1)), false, 'the place followed is not sent back');
  assert.equal(f.unsent(M.placeValue(art, 9, 0, 1)), true, 'an op ended unanswered is sent again');
  assert.equal(f.save(M.placeValue(art, 9, 0, 1)), true);
  assert.deepEqual(M.placeOp(M.placeValue(art, 9, 0, 1), 100), { window: 'feed', action: 'place', targets: ['place'], result: { item: art, b: 9, o: 0, v: 1 }, at: 100 });
  assert.deepEqual(M.bingeOp('quest', M.bingeValue(412, 0.256), 7), { window: 'feed', action: 'binge', targets: ['binge.quest'], result: { ep: 412, f: 0.26, s: null, w: null }, at: 7 });
  assert.equal(M.bingeKey({ ep: 412, f: null, s: 3, w: 564 }), M.bingeKey({ ep: 412, f: 0, s: null, w: null }), 'the glasses\' strip hint is not another place');
});

test('the binge: one window in flight; more after asks past the loaded end, more before before its start; the ends stop it', () => {
  const b = new M.Binge('quest');
  const s0 = b.ask(412);
  assert.equal(s0, 'feed/binge:quest/around:412');
  assert.equal(b.ask(412), null, 'asked once while held');
  assert.equal(b.moreAfter(), null, 'nothing loaded yet');
  b.got(s0, bingeWindow('quest', 412));
  assert.deepEqual([b.lo, b.hi, b.first, b.last, b.total], [409, 417, 1, 1225, 1225]);
  assert.equal(b.entries.get(414).state, 'pending');
  assert.equal(b.moreAfter(), 421, 'around 421: 418 to 426');
  assert.equal(b.moreBefore(), 403, 'around 403: 400 to 408');
  b.ask(421);
  assert.equal(b.moreBefore(), null, 'one window in flight at a time');
  b.got('feed/binge:quest/around:421', bingeWindow('quest', 421));
  assert.equal(b.hi, 426);
  const e = new M.Binge('quest');
  e.got(e.ask(1), bingeWindow('quest', 1));
  assert.equal(e.atFirst, true);
  assert.equal(e.moreBefore(), null, 'the first episode: nothing before');
  const changed = e.got('feed/binge:quest/around:1', bingeWindow('quest', 1, { pending: [] }));
  assert.deepEqual(changed, [], 'described again with nothing new: nothing changes');
  e.refused('feed/binge:quest/around:9', { code: 'retry', reason: 'the archive did not answer' });
  assert.equal(e.failure(), 'the archive did not answer');
  assert.equal(M.jumpWhy('2000', b), 'episodes run from 1 to 1225');
  assert.equal(M.jumpWhy('x', b), 'type an episode number');
  assert.equal(M.jumpWhy('5', b), null);
});

test('the comic bar: a neighbour the PC named, random in the range and never the strip shown, a dim one with its reason', () => {
  const c = comic('stick', null, 2961);
  assert.deepEqual(M.barTarget(c, 'prev', 'stick'), { target: 'num:stick:2960' });
  assert.deepEqual(M.barTarget(c, 'next', 'stick'), { why: 'this is the latest' });
  assert.deepEqual(M.barTarget(c, 'random', 'stick', () => 0.9999999), { target: 'num:stick:1' }, 'the last of the range is the strip shown: another one');
  assert.deepEqual(M.barTarget(c, 'random', 'stick', () => 0.5), { target: 'num:stick:1481' });
  assert.deepEqual(M.barTarget({ neighbours: {} }, 'first', 'x'), { why: 'this is the first' });
});

test('the comments\' place is this browser\'s: the most recent first, at most fifty, bad text read as none', () => {
  let raw = null;
  for (let i = 0; i < 60; i++) raw = M.putCommentPlace(raw, `item${i}`, `c${i}`);
  assert.equal(M.commentPlaces(raw).length, 50);
  assert.equal(M.commentPlace(raw, 'item59'), 'c59');
  assert.equal(M.commentPlace(raw, 'item0'), null, 'the oldest left');
  raw = M.putCommentPlace(raw, 'item30', 'c99');
  assert.equal(M.commentPlaces(raw)[0][1], 'c99');
  assert.deepEqual(M.commentPlaces('{not json'), []);
});

test('a source\'s pages: their scopes, the row after an item, a comic by its kind', () => {
  const p = new M.Pages('r:knots');
  assert.equal(p.scope(2), 'feed/src:r:knots/page:2');
  assert.equal(p.pageOf('feed/src:r:knots/page:2'), 2);
  assert.equal(p.pageOf('feed/src:front/page:2'), null);
  p.got(0, feedPage('front', 0));
  assert.equal(p.after(itemId('front', 3)).id, itemId('front', 4));
  assert.equal(M.isComic(row('stick', 0), null), true);
  assert.equal(M.isComic(null, { kind: 'SMBC' }), true);
  assert.equal(M.isComic(null, { kind: 'REDDIT' }), false);
  assert.equal(M.tailOf(row('stick', 2), { kind: 'XKCD' }, F_NOW), '#2959 · 1 h');
  assert.match(M.tailOf(row('news', 1), { kind: 'SLASHDOT' }, F_NOW), /^18 comments · 56 min · linux$/);
});
