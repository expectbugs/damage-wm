// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-1, fix round 2, ruling 2.3: Reader's web view mounted over a small DOM stand-in under `node --test` (no
// packages, no network, no browser; this file runs in its own process, so the stand-in's globals reach no other test).
// What it pins is what the view sends: `open` only on an input that opens a book, and a place read while the PC was
// out of reach sent when it answers again. Layout is not modelled: a part with no blocks puts its start at the top.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ClientRecord } from '../../main/resources/web/js/record.mjs';

class El {
  constructor(tag) {
    Object.assign(this, { tag, children: [], dataset: {}, attrs: {}, on: {}, className: '', textContent: '', parentElement: null, scrollTop: 0 });
    this.classList = { toggle() {}, add() {}, remove() {}, contains: () => false };
  }
  setAttribute(k, v) { this.attrs[k] = v; }
  addEventListener(ev, fn) { (this.on[ev] ||= []).push(fn); }
  removeEventListener() {}
  append(...kids) { for (const k of kids) { k.parentElement = this; this.children.push(k); } }
  replaceChildren(...kids) { this.children = []; this.append(...kids.filter((k) => k)); }
  get firstChild() { return this.children[0] || null; }
  remove() {}
  getBoundingClientRect() { return { top: 0, left: 0, bottom: 0, right: 0 }; }
  querySelectorAll() { return []; }
  /** Every element under this one whose class names `cls`. */
  find(cls) { return [this, ...this.children.flatMap((c) => c.find ? c.find(cls) : [])].filter((e) => (e.className || '').split(' ').includes(cls)); }
}
const winOn = {};
globalThis.Node = El;
globalThis.document = { createElement: (t) => new El(t), createTextNode: (t) => Object.assign(new El('#text'), { textContent: t }), addEventListener() {}, removeEventListener() {} };
globalThis.window = { addEventListener: (ev, fn) => { winOn[ev] = fn; }, removeEventListener() {} };

const { readerView } = await import('../../main/resources/web/js/views/reader.mjs');

const ME = 'web-a1b2c3';

/** A page's context: the record copy ready, every op sent kept in `sent`, the reach and level from `S`. */
function page() {
  const sent = [];
  const S = { device: ME, names: {}, record: new ClientRecord(), recordReady: true, reach: { ok: true, since: 0 }, phase: 'open' };
  S.record.snap({ epoch: 1, seq: 1, items: {} }, ['rec.reader.']);
  const ctx = {
    S, sent,
    desc: () => undefined,
    sub() {}, unsub() {},
    sendQueued: (d) => { sent.push(d); return true; },
    canAct: () => (S.reach.ok ? null : 'PC not answering since 9:00'),
    say() {}, sayOnce() {}, go() {}, confirm: async () => false,
    actionButton: () => new El('button'),
  };
  return ctx;
}

const opens = (ctx) => ctx.sent.filter((d) => d.action === 'open').map((d) => d.result);

test('open is sent on an input that opens a book, never on a page load, a reload or a restored tab (ruling 2.3)', () => {
  const ctx = page();
  const v = readerView(ctx);
  v.mount({ window: 'reader', target: 'book:b1', anchor: null }, { input: false }); // the first route of a page load
  assert.deepEqual(opens(ctx), [], 'a page load that lands on a book sends no open');
  v.update({ window: 'reader', target: 'book:b2', anchor: null }, { input: true }); // a card, Continue, a link
  assert.deepEqual(opens(ctx), ['b2']);
  v.update({ window: 'reader', target: 'book:b3', anchor: null }); // a route with no input named
  assert.deepEqual(opens(ctx), ['b2'], 'only input opens');
});

test('a place read while the PC was out of reach is sent when it answers again: the latest place only (ruling 2.3)', () => {
  const ctx = page();
  const v = readerView(ctx);
  const root = v.mount({ window: 'reader', target: 'book:b1', anchor: null }, { input: false });
  v.onDesc('reader/book:b1', { title: 'One', length: 400, chapters: [] });
  v.onDesc('reader/book:b1/part:0', { part: 0, parts: 1, charStart: 0, charEnd: 400, blocks: [] });
  // he reads (a wheel over the pane) while the PC is out of reach; the save point says so and sends nothing
  ctx.S.reach = { ok: false, since: 0 };
  const pane = root.find('reading')[0];
  for (const fn of pane.on.wheel) fn();
  winOn.pagehide();
  assert.deepEqual(ctx.sent.filter((d) => d.action === 'place'), [], 'nothing is sent out of reach');
  // the PC answers again
  ctx.S.reach = { ok: true, since: 1 };
  v.onReach();
  const places = ctx.sent.filter((d) => d.action === 'place');
  assert.equal(places.length, 1, 'the place read meanwhile is sent once the PC answers');
  assert.deepEqual(places[0].targets, ['place.b1']);
  v.onReach();
  assert.equal(ctx.sent.filter((d) => d.action === 'place').length, 1, 'a mark, not a queue: sent once');
});

// The last stage, the focused read's S2 (ruling 2.3's place sibling: a write no input made): an address that names a
// place shows it on any route, but saves it only when input routed there (a click on a jump, back or forward). A
// reload, a restored tab or a bundle-change reload lands with no input and saves nothing until he scrolls.
function linkedPlaces(input) {
  const ctx = page();
  const v = readerView(ctx);
  v.mount({ window: 'reader', target: 'book:b1', anchor: '@c120.1~' }, { input });
  v.onDesc('reader/book:b1', { title: 'One', length: 400, chapters: [] });
  v.onDesc('reader/book:b1/part:0', { part: 0, parts: 1, charStart: 0, charEnd: 400, blocks: [] });
  winOn.pagehide(); // a save point now, in place of the 2 s timer
  return ctx.sent.filter((d) => d.action === 'place');
}

test('a reload of an address that names a place sends no place; a routed jump to it does (focused read S2)', () => {
  assert.deepEqual(linkedPlaces(false), [], 'a page load that lands on a place writes nothing');
  const sent = linkedPlaces(true);
  assert.equal(sent.length, 1, 'a jump the reader made is saved');
  assert.deepEqual(sent[0].targets, ['place.b1']);
});
