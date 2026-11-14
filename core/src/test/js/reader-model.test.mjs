// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-1, package P7 (decisions A.7, A.2, E.6): Reader's web page without the DOM, under `node --test` (no
// packages, no network, no browser). The place view's pins: another device's place never moves the page and is
// offered; the first place after a move not taken loses and is said once, in the glasses' words; reading on applies.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { PlaceView, placeAt, progress, percent, partFor, who, lossWords, blockText } from '../../main/resources/web/js/reader-model.mjs';
import { parse } from '../../main/resources/web/js/anchor.mjs';

const ME = 'web-a1b2c3';
const item = (v, by, at = 1_800_000_000_000, extra = {}) => ({ v, rev: 1, by, at, ...extra });
const words = (v) => (parse(v) ? `character ${parse(v).offset}` : 'no place');
const whoOf = (d) => who(d, ME, { 'web-d4e5f6': 'desk' });

test('an entry takes the record place; nothing there opens at the start', () => {
  const p = new PlaceView(ME, 'b1');
  assert.equal(p.enter(item('@c120.1~abc', 'phone')), 120);
  assert.deepEqual(p.expect, { value: '@c120.1~abc' });
  const q = new PlaceView(ME, 'b1');
  assert.equal(q.enter(undefined), null);
  assert.deepEqual(q.expect, { value: null });
  const r = new PlaceView(ME, 'b1');
  assert.equal(r.enter({ v: null, rev: 3, by: 'phone', at: 1, gone: true }), null);
  assert.deepEqual(r.expect, { any: true });
});

test('another device moving the open book never moves the page: it is offered (Go there)', () => {
  const p = new PlaceView(ME, 'b1');
  p.enter(item('@c100.1~a', 'phone'));
  assert.equal(p.fromRecord(item('@c900.1~z', 'phone')), true);
  assert.equal(p.offset, 100, 'the view stays where it is');
  assert.deepEqual(p.moved, { value: '@c900.1~z', by: 'phone', at: 1_800_000_000_000 });
  // this device's own write is only noted, never offered
  const q = new PlaceView(ME, 'b1');
  q.enter(item('@c100.1~a', 'phone'));
  assert.equal(q.fromRecord(item('@c150.1~b', ME)), false);
  assert.equal(q.moved, null);
});

test('the first place after a move not taken loses and is said once; reading on applies', () => {
  const p = new PlaceView(ME, 'b1');
  p.enter(item('@c100.1~a', 'phone'));
  p.fromRecord(item('@c900.1~z', 'phone', 1_800_000_100_000));
  const d1 = p.save(140, '@c140.1~q', item('@c900.1~z', 'phone'));
  assert.deepEqual(d1.expect, { value: '@c100.1~a' }, 'it expects the place it took, so it loses');
  const lost = { a: 'lost', losses: [{ item: 'place.b1', standing: '@c900.1~z', by: 'phone', at: 1_800_000_100_000, mine: '@c140.1~q' }] };
  const loss = p.answered(d1.result, lost);
  assert.ok(loss, 'the loss is returned to be said');
  assert.match(lossWords(loss, 'place', words, whoOf), /^the phone read on to character 900 \(\d+:\d\d\); this place, character 140, was not applied$/);
  assert.deepEqual(p.moved && p.moved.value, '@c900.1~z', 'Go there stays offered');
  const d2 = p.save(180, '@c180.1~r', item('@c900.1~z', 'phone'));
  assert.deepEqual(d2.expect, { value: '@c900.1~z' }, 'the next place expects the standing one');
  assert.equal(p.answered(d2.result, { a: 'seen' }), null, 'reading on never loses twice');
  // ruling 2.2: the applied save comes back written by this device; the phone's place no longer stands
  assert.equal(p.fromRecord(item('@c180.1~r', ME)), true, 'the offer changed');
  assert.equal(p.moved, null, 'Go there is withdrawn once this page\'s own place stands');
  // the last stage: the same withdrawal when the applied save's answer never comes (its connection ended) and the
  // record shows it landed, so the view never took it — ruling 2.2's own-write guard watched alone (the taken-value
  // branch alone covers the order above)
  p.fromRecord(item('@c950.1~y', 'phone', 1_800_000_200_000));
  const d3 = p.save(220, '@c220.1~s', item('@c950.1~y', 'phone'));
  p.answered(d3.result, { a: 'lost', losses: [{ item: 'place.b1', standing: '@c950.1~y', by: 'phone', at: 1_800_000_200_000, mine: '@c220.1~s' }] });
  assert.deepEqual(p.moved && p.moved.value, '@c950.1~y', 'offered again after the loss');
  const d4 = p.save(260, '@c260.1~t', item('@c950.1~y', 'phone'));
  assert.deepEqual(d4.expect, { value: '@c950.1~y' }, 'reading on expects the standing place');
  p.answered(d4.result, null);
  assert.equal(p.fromRecord(item('@c260.1~t', ME)), true, 'the offer changed');
  assert.equal(p.moved, null, 'an own place that landed with no answer withdraws the offer too');
});

test('Go there takes the moved place without writing it', () => {
  const p = new PlaceView(ME, 'b1');
  p.enter(item('@c100.1~a', 'phone'));
  p.fromRecord(item('@c900.1~z', 'web-d4e5f6'));
  assert.equal(p.goThere(), 900);
  assert.equal(p.moved, null);
  assert.deepEqual(p.expect, { value: '@c900.1~z' });
  assert.equal(p.save(900, '@c900.1~z'), null, 'the same place sends nothing');
  assert.equal(p.goThere(), null, 'nothing more is offered');
});

test('a move back to the place the view took withdraws the offer', () => {
  const p = new PlaceView(ME, 'b1');
  p.enter(item('@c100.1~a', 'phone'));
  p.fromRecord(item('@c900.1~z', 'phone'));
  assert.equal(p.fromRecord(item('@c100.1~a', 'local')), true);
  assert.equal(p.moved, null);
});

test('with nothing in flight, a record place this device wrote is what the op expects', () => {
  const p = new PlaceView(ME, 'b1');
  p.enter(item('@c100.1~a', 'phone'));
  const d1 = p.save(200, '@c200.1~b', item('@c100.1~a', 'phone'));
  // the answer never came (the connection ended); the record shows the op landed
  p.answered(d1.result, null);
  p.expect = { value: 'stale' };
  const d2 = p.save(300, '@c300.1~c', item('@c200.1~b', ME));
  assert.deepEqual(d2.expect, { value: '@c200.1~b' });
  // with an op in flight the record copy lags it: the view's own expectation stands
  const d3 = p.save(400, '@c400.1~d', item('@c200.1~b', ME));
  assert.deepEqual(d3.expect, { value: '@c300.1~c' });
});

test('a reset elsewhere is not offered; the place after it loses and says the reset', () => {
  const p = new PlaceView(ME, 'b1');
  p.enter(item('@c100.1~a', 'phone'));
  assert.equal(p.fromRecord({ v: null, rev: 2, by: 'web-d4e5f6', at: 1_800_000_000_000, gone: true }), false);
  const d = p.save(150, '@c150.1~x', null);
  const loss = p.answered(d.result, { a: 'lost', losses: [{ item: 'place.b1', standing: null, by: 'web-d4e5f6', at: 1_800_000_000_000, mine: '@c150.1~x', gone: true }] });
  assert.match(lossWords(loss, 'place', words, whoOf), /^progress of this book was reset by desk \(\d+:\d\d\); this place, character 150, was not applied$/);
  assert.deepEqual(p.expect, { any: true });
  assert.equal(p.moved, null);
});

test('a reset elsewhere, and a save lost to a reset, withdraw a standing Go there offer: nothing is offered (fix round 2, ruling 2.4)', () => {
  const p = new PlaceView(ME, 'b1');
  p.enter(item('@c100.1~a', 'phone'));
  p.fromRecord(item('@c900.1~z', 'phone'));
  assert.ok(p.moved, 'the phone\'s place is offered');
  assert.equal(p.fromRecord({ v: null, rev: 2, by: 'web-d4e5f6', at: 1_800_000_000_000, gone: true }), true, 'the offer changed');
  assert.equal(p.moved, null, 'a place the record no longer holds is not offered');
  assert.equal(p.goThere(), null);
  const q = new PlaceView(ME, 'b1');
  q.enter(item('@c100.1~a', 'phone'));
  q.fromRecord(item('@c900.1~z', 'phone'));
  const d = q.save(150, '@c150.1~x', item('@c900.1~z', 'phone'));
  q.answered(d.result, { a: 'lost', losses: [{ item: 'place.b1', standing: null, by: 'web-d4e5f6', at: 1_800_000_000_000, mine: '@c150.1~x', gone: true }] });
  assert.equal(q.moved, null, 'a save lost to a reset offers nothing');
});

test('the loss words are the glasses’ for every target', () => {
  const at = new Date(2026, 10, 14, 14, 2).getTime();
  const l = (o) => ({ item: 'place.b1', standing: '@c9.1~', by: 'phone', at, mine: '@c5.1~', ...o });
  assert.equal(lossWords(l({}), 'reset', words, whoOf), 'progress not reset: the phone read on (14:02)');
  assert.equal(lossWords(l({ standing: null }), 'place', words, whoOf),
    'this place, character 5, was not applied: the PC holds no place for this book now; reading on sends it again');
  assert.equal(lossWords(l({ item: 'bookmark.b1', by: 'local' }), 'bookmark', words, whoOf), 'bookmark not applied: set by the PC (14:02)');
});

const doc = {
  charStart: 100, charEnd: 162,
  blocks: [
    { kind: 'heading', charStart: 100, charEnd: 109, spans: [{ text: 'Chapter 1', marks: [] }] },
    { kind: 'para', charStart: 111, charEnd: 142, spans: [{ text: 'It was a ', marks: [] }, { text: 'dark', marks: ['em'] }, { text: ' and stormy night.', marks: [] }] },
    { kind: 'image', charStart: 144, charEnd: 145, ref: 'reader.b1.0', spans: [] },
    { kind: 'para', charStart: 147, charEnd: 162, spans: [{ text: 'After the image', marks: [] }] },
  ],
};

test('a place quotes the book text the page holds, joined by a blank line, and stops at an image', () => {
  assert.equal(placeAt(doc, 100), '@c100.1~Chapter 1\n\nIt was a dark and sto');
  assert.equal(placeAt(doc, 120), '@c120.1~dark and stormy night.');
  assert.equal(placeAt(doc, 144), '@c144.1~', 'an image carries no text to vouch for');
  assert.equal(placeAt(doc, 151), '@c151.1~r the image');
  assert.equal(placeAt(doc, 110), '@c110.1~', 'between blocks: no quote');
  assert.equal(parse(placeAt(doc, 100)).quote.length, 32);
  assert.equal(blockText(doc.blocks[1]), 'It was a dark and stormy night.');
  assert.equal(blockText(doc.blocks[2]), null);
});

test('progress, percent, the chapter a place falls in, and a writer in words', () => {
  assert.equal(progress({ length: 1000 }, '@c250.1~x'), 0.25);
  assert.equal(progress({ length: 1000 }, undefined), 0);
  assert.equal(progress({ fraction: 0.4 }, '@c1.1~'), 0.4);
  assert.equal(progress({}, undefined), null);
  assert.equal(percent(0.256), '26%');
  const ch = [{ title: 'a', anchor: '@c0.1~' }, { title: 'b', anchor: '@c500.1~' }, { title: 'c', anchor: '@c900.1~' }];
  assert.equal(partFor(ch, 0), 0);
  assert.equal(partFor(ch, 899), 1);
  assert.equal(partFor(ch, 5000), 2);
  assert.equal(partFor([], 5000), 0);
  assert.equal(whoOf(ME), 'this browser');
  assert.equal(whoOf('local'), 'the PC');
  assert.equal(whoOf('migration'), 'an earlier build');
  assert.equal(whoOf('web-d4e5f6'), 'desk');
  assert.equal(whoOf('web-777777'), 'web-777777');
});
