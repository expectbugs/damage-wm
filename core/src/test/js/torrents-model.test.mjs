// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-2, package Q4w: Torrents' web page without the DOM, under `node --test`. The pins: the filters and the
// order are the glasses'; an older description is ignored; a speed ring takes one sample per snapshot; a selection
// leaves with its row; an irreversible action always asks, delete with files twice; a program op's answer never
// claims an effect; the recents are one list, deduped, ten at most; a listing is taken one page at a
// time, the next only near the end, a failed one again after 5 s only when the PC said retry.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as M from '../../main/resources/web/js/torrents-model.mjs';
import { ClientRecord } from '../../main/resources/web/js/record.mjs';
import { transfer, torrentsDesc, tlPage, T_NOW } from './torrents-fixtures.mjs';

const DAY = 86400e3;
const row = (raw, over = {}) => M.rowOf(transfer(0, { raw, ...over }));

test('a transfer is classified as the glasses classify it (Transfer, TorrentsModel.kt:44-50)', () => {
  const c = (raw) => M.rowOf(transfer(1, { raw }));
  assert.deepEqual(['downloading', 'stoppedDL', 'uploading', 'stoppedUP', 'error', 'missingFiles', 'checkingUP', 'moving'].map((raw) => {
    const r = c(raw);
    return [r.downloading, r.finished, r.stopped, r.error, r.checking];
  }), [[true, false, false, false, false], [true, false, true, false, false], [false, true, false, false, false], [false, true, true, false, false],
    [false, false, false, true, false], [false, false, false, true, false], [false, true, false, false, true], [false, false, false, false, true]]);
  assert.equal(c('uploading').underAWeek, M.rowOf(transfer(1, { raw: 'uploading' })).seedingTime < 7 * DAY);
  assert.equal(row('uploading', { fields: { seedingTime: 7 * DAY } }).underAWeek, false, 'a full week seeded is not under a week');
  assert.equal(row('downloading', { fields: { seedingTime: 0 } }).underAWeek, false, 'a download is not in the week list');
});

test('the filters are today\'s: downloading and seeding leave stopped ones out; under a week is finished and seeded < 7 d', () => {
  const ts = [transfer(0, { raw: 'downloading' }), transfer(1, { raw: 'stoppedDL' }), transfer(2, { raw: 'uploading', fields: { seedingTime: 2 * DAY } }),
    transfer(3, { raw: 'stoppedUP', fields: { seedingTime: 9 * DAY } }), transfer(4, { raw: 'error' }), transfer(5, { raw: 'stalledUP', fields: { seedingTime: 6.9 * DAY } })];
  const d = torrentsDesc({ transfers: ts });
  const ids = (f) => M.tableRows(d, f, { key: 'name' }).map((r) => r.id).sort();
  const id = (i) => ts[i].id;
  assert.deepEqual(ids('downloading'), [id(0)]);
  assert.deepEqual(ids('seeding'), [id(2), id(5)].sort());
  assert.deepEqual(ids('stopped'), [id(1), id(3)].sort());
  assert.deepEqual(ids('errors'), [id(4)]);
  assert.deepEqual(ids('week'), [id(2), id(5)].sort());
  assert.deepEqual(M.counts(d), { all: 6, downloading: 1, seeding: 2, stopped: 2, errors: 1, week: 2 });
  assert.equal(M.wordOf(M.rowOf(ts[2]), 'week'), '2d 0h seeded · 5d 0h left', 'the week list says how long it seeded and how long is left');
  assert.equal(M.wordOf(M.rowOf(ts[2]), 'all'), 'seeding');
});

test('activity order: errors first, then downloads by progress, checking, seeds, stopped; columns sort both ways, unknowns last', () => {
  const ts = [transfer(0, { raw: 'stoppedUP', title: 'e' }), transfer(1, { raw: 'uploading', title: 'd' }), transfer(2, { raw: 'checkingDL', title: 'c' }),
    transfer(3, { raw: 'downloading', progress: 0.2, title: 'b2' }), transfer(4, { raw: 'downloading', progress: 0.8, title: 'b1' }), transfer(5, { raw: 'error', title: 'a' })];
  const order = (sort) => M.sortRows(ts.map(M.rowOf), sort).map((r) => r.title);
  // checkingDL is in DL_STATES, so it ranks with the downloads (by its progress, 0.74), as `activityRank` does
  assert.deepEqual(order({ key: 'activity' }), ['a', 'b1', 'c', 'b2', 'd', 'e']);
  assert.deepEqual(order({ key: 'name', dir: 1 }), ['a', 'b1', 'b2', 'c', 'd', 'e']);
  assert.deepEqual(order({ key: 'name', dir: -1 }), ['e', 'd', 'c', 'b2', 'b1', 'a']);
  const eta = [transfer(0, { title: 'x', fields: { eta: null } }), transfer(1, { title: 'y', fields: { eta: 5000 } }), transfer(2, { title: 'z', fields: { eta: 9000 } })].map(M.rowOf);
  assert.deepEqual(M.sortRows(eta, { key: 'eta', dir: 1 }).map((r) => r.title), ['y', 'z', 'x']);
  assert.deepEqual(M.sortRows(eta, { key: 'eta', dir: -1 }).map((r) => r.title), ['z', 'y', 'x'], 'no estimate is last either way');
});

test('formats follow the glasses\' Fmt, locale-fixed', () => {
  assert.deepEqual([M.bytes(512), M.bytes(1536), M.bytes(1.5 * 1048576), M.bytes(2.5 * 1073741824), M.bytes(-1)], ['512 B', '1 KB', '1.5 MB', '2.50 GB', '?']);
  assert.deepEqual([M.rate(0), M.rate(2048), M.rate(1.25 * 1048576)], ['0 B/s', '2 KB/s', '1.3 MB/s']);
  assert.deepEqual([M.dur(45e3), M.dur(12 * 60e3), M.dur(3 * 3600e3 + 5 * 60e3), M.dur(2 * DAY + 4 * 3600e3)], ['45 s', '12 m', '3h 05m', '2d 4h']);
  assert.deepEqual([M.eta(null), M.eta(8640000e3), M.eta(90e3)], ['—', '—', '1 m']);
  assert.equal(M.siteAge('2026-11-15 11:02:00', T_NOW), '3 h ago');
  assert.equal(M.siteAge('yesterday-ish', T_NOW), 'yesterday-ish', 'text the page does not read stays whole');
});

test('an older description is ignored; another epoch is taken', () => {
  const a = { epoch: 5, version: 10 };
  assert.equal(M.newer(null, a), true);
  assert.equal(M.newer(a, { epoch: 5, version: 9 }), false);
  assert.equal(M.newer(a, { epoch: 5, version: 10 }), true);
  assert.equal(M.newer(a, { epoch: 6, version: 1 }), true, 'a restarted provider');
});

test('a speed ring takes one sample per snapshot, keeps the last n, and drops a transfer gone', () => {
  const s = new M.Sparks(3);
  const d = (v, ts) => ({ epoch: 1, version: v, atMs: v, transfers: ts });
  const a = transfer(0, { raw: 'downloading' });
  const b = transfer(1, { raw: 'uploading' });
  assert.equal(s.feed(d(1, [a, b])), true);
  assert.equal(s.feed(d(1, [a, b])), false, 'the same snapshot twice is one sample');
  s.feed(d(2, [a, b]));
  s.feed(d(3, [a, b]));
  s.feed(d(4, [a]));
  assert.equal(s.of(a.id).length, 3);
  assert.deepEqual(s.of(b.id), [], 'gone');
  assert.equal(M.line([0, 5, 10], 100, 10, 10), '0,10 50,5 100,0');
});

test('a selection is by hash and leaves with its row', () => {
  const s = new M.Selection();
  s.toggle('a');
  s.toggle('b');
  s.toggle('c');
  s.toggle('c');
  assert.deepEqual(s.list(), ['a', 'b']);
  assert.equal(s.prune(['b', 'x']), 1);
  assert.deepEqual(s.list(), ['b']);
});

test('an irreversible action always asks; delete with files asks twice; the words are the spec\'s', () => {
  const del = { id: 'delete', label: 'Delete', kind: 'i' };
  assert.deepEqual(M.confirmFor(del, ['Salt Road']).title, "Delete 'Salt Road'?");
  assert.deepEqual(M.confirmFor(del, ['a', 'b', 'c']).title, 'Delete 3 torrents?');
  assert.equal(M.confirmFor(del, ['a']).double, false);
  const files = { id: 'deleteFiles', label: 'Delete with files', kind: 'i' };
  assert.equal(M.confirmFor(files, ['a']).double, true);
  assert.equal(M.confirmFor({ id: 'add', label: 'Add', kind: 'o', confirm: {} }, ['Lantern']).title, "Add 'Lantern'?");
  assert.match(M.confirmFor({ id: 'add', label: 'Add', kind: 'o', confirm: {} }, ['Lantern']).act, /~\/Downloads/);
  assert.equal(M.confirmFor({ id: 'start', label: 'Start', kind: 'r' }, ['a']), null, 'start asks nothing');
  assert.notEqual(M.confirmFor({ id: 'purge', label: 'Purge', kind: 'i' }, ['a']), null, 'an irreversible action with no confirm in the description still asks');
});

test('an action the level does not allow is not offered', () => {
  const a = { id: 'deleteFiles', level: 'full' };
  assert.deepEqual([M.offered(a, 'view'), M.offered(a, 'control'), M.offered(a, 'full'), M.offered(undefined, 'full')], [false, false, true, false]);
});

test('a program op\'s answer says what the PC answered, never an effect the page has not read', () => {
  const stop = { id: 'stop', label: 'Stop' };
  const sent = M.answerLine(stop, 3, { a: 'sent' });
  assert.match(sent, /sent to qBittorrent for 3 torrents/);
  assert.doesNotMatch(sent, /stopped/);
  assert.equal(M.answerLine(stop, 1, { a: 'refused', code: 'program', reason: 'qBittorrent answered 403' }), 'Stop: qBittorrent answered 403');
  assert.match(M.answerLine({ id: 'add', label: 'Add' }, 1, { a: 'sent', result: { name: 'Lantern' } }), /took 'Lantern'.*once qBittorrent lists it/);
  assert.match(M.waitingLine({ label: 'Stop', n: 2, at: 0 }, 12e3), /Stop · 2 torrents · sent, no answer from the PC yet · 12 s/);
  assert.deepEqual(M.programDraft(stop, ['h1', 'h2']), { window: 'torrents', action: 'stop', targets: ['h1', 'h2'] });
});

test('the recents are one register: the whole list, newest first, deduped, ten at most (A.9)', () => {
  const rec = new ClientRecord();
  rec.snap({ epoch: 1, seq: 1, items: { 'rec.torrents.recents': { v: ['b', 'a', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j'], by: 'phone', at: 1 } } }, ['rec.torrents.']);
  assert.deepEqual(M.recentsOf(rec).slice(0, 2), ['b', 'a']);
  const d = M.recentDraft('a', rec);
  assert.deepEqual(d, { window: 'torrents', action: 'recent', targets: ['recents'], result: ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j'] });
  assert.equal(M.recentDraft('new', rec).result.length, 10);
  assert.deepEqual(M.recentDraft('x', new ClientRecord()).result, ['x']);
});

test('a row\'s value is the record\'s when it holds one; its set op is the value', () => {
  const rec = new ClientRecord();
  const row = { id: 'notify.done', value: true };
  assert.equal(M.rowValue(row, rec), true);
  rec.snap({ epoch: 1, seq: 1, items: { 'rec.torrents.notify.done': { v: false, by: 'phone', at: 1 } } }, ['rec.torrents.']);
  assert.equal(M.rowValue(row, rec), false);
  assert.deepEqual(M.setDraft(row, true), { window: 'torrents', action: 'set', targets: ['notify.done'], result: true });
});

test('a listing is taken one page at a time: the first at once, the next only near the end, never two in flight', () => {
  const l = new M.Listing(M.listingSrc('newest'), 'added');
  assert.equal(l.next(false, 0), 0, 'the first page at once');
  l.ask(0);
  assert.equal(l.next(true, 0), null, 'one page in flight at a time');
  l.got(0, tlPage(0, 100));
  assert.equal(l.next(false, 0), null, 'not near the end: nothing asked');
  assert.equal(l.next(true, 0), 1);
  l.ask(1);
  l.got(1, tlPage(1, 60));
  assert.equal(l.ended, true);
  assert.equal(l.next(true, 0), null, 'the end: nothing more asked');
  assert.deepEqual(l.status(), { kind: 'end' });
  assert.deepEqual(l.held(), ['torrents/listing:newest/sort:added/page:0', 'torrents/listing:newest/sort:added/page:1']);
});

test('a refused page is asked again after 5 s when the PC said retry, never before, and never for another refusal', () => {
  const l = new M.Listing(M.searchSrc('salt'), 'added');
  l.ask(l.next(false, 0));
  l.refused(0, { a: 'refused', code: 'retry', reason: 'TorrentLeech not answering' }, 1000);
  assert.deepEqual(l.status(), { kind: 'failed', page: 0, reason: 'TorrentLeech not answering', retry: true });
  assert.equal(l.next(true, 5999), null, 'not before 5 s');
  assert.equal(l.next(false, 6000), 0, 'at 5 s, near the end or not');
  const k = new M.Listing(M.searchSrc('salt'), 'added');
  k.ask(0);
  k.refused(0, { a: 'refused', code: 'level', reason: 'needs level full' }, 0);
  assert.equal(k.next(true, 60000), null, 'a refusal that is not retry is not asked again');
});

test('a page described again after the listing moved replaces its rows; a row shows once; held rows grey on a failed re-read', () => {
  const l = new M.Listing(M.listingSrc(8), 'seeders');
  l.got(0, { version: 1, rows: [{ id: 'a' }, { id: 'b' }], more: true });
  l.got(1, { version: 1, rows: [{ id: 'b' }, { id: 'c' }], more: false });
  assert.deepEqual(l.rows().map((r) => r.id), ['a', 'b', 'c'], 'b moved pages between two reads: once');
  assert.equal(l.got(0, { version: 2, rows: [{ id: 'z' }, { id: 'a' }], more: true }), true);
  assert.deepEqual(l.rows().map((r) => r.id), ['z', 'a', 'b', 'c']);
  l.refused(1, { code: 'retry', reason: 'x' }, 0);
  assert.deepEqual(l.rows().map((r) => r.stale), [false, false, true, true]);
});

test('a search\'s scopes carry its words encoded, so a slash or a space never splits the scope', () => {
  assert.equal(M.pageScope(M.searchSrc('AC/DC live'), 'added', 2), 'torrents/search:AC%2FDC%20live/sort:added/page:2');
  const l = new M.Listing(M.searchSrc('AC/DC live'), 'added');
  assert.equal(l.pageOf('torrents/search:AC%2FDC%20live/sort:added/page:3'), 3);
  assert.equal(l.pageOf('torrents/search:AC%2FDC%20live/sort:size/page:3'), null, 'another sort is another listing');
  assert.equal(M.nearEnd(1000, 800, 2100, 40), true);
  assert.equal(M.nearEnd(0, 800, 2100, 40), false);
});

test('an address names its tab and place', () => {
  assert.deepEqual(M.placeOf(null), { tab: 'transfers' });
  assert.deepEqual(M.placeOf('t:abc'), { tab: 'transfers', hash: 'abc' });
  assert.deepEqual(M.placeOf('listing:newest'), { tab: 'browse', cat: 'newest' });
  assert.deepEqual(M.placeOf('search:a b'), { tab: 'search', q: 'a b' });
  assert.deepEqual(M.placeOf('tl:240001'), { tab: 'torrent', fid: '240001' });
  assert.deepEqual(M.placeOf('account'), { tab: 'account' });
  assert.equal(M.categoryName(8, [{ id: 8, group: 'Movies', name: 'HD' }]), 'Movies · HD');
  assert.equal(M.categoryName(99, []), '99', 'an unknown id shows as its number, never dropped');
});
