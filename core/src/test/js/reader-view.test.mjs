// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-1, fix round 2, ruling 2.3: Reader's web view mounted over a small DOM stand-in under `node --test` (no
// packages, no network, no browser; this file runs in its own process, so the stand-in's globals reach no other test).
// What it pins is what the view sends: `open` only on an input that opens a book, a place read while the PC was out
// of reach sent when it answers again with the time of its input, and (L1) another device's place followed without a
// word sent. Layout is not modelled: a part with no blocks puts its start at the top.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ClientRecord } from '../../main/resources/web/js/record.mjs';

class El {
  constructor(tag) {
    Object.assign(this, { tag, children: [], dataset: {}, attrs: {}, on: {}, className: '', textContent: '', parentElement: null, scrollTop: 0, style: {} });
    this.classList = { toggle() {}, add() {}, remove() {}, contains: () => false };
  }
  setAttribute(k, v) { this.attrs[k] = v; }
  addEventListener(ev, fn) { (this.on[ev] ||= []).push(fn); }
  removeEventListener() {}
  /** As the DOM (S1-2 JW0): a child that is not an element is written as its text, so a null shows as "null". */
  append(...kids) {
    for (const k of kids) {
      const n = k instanceof El ? k : Object.assign(new El('#text'), { textContent: String(k) });
      n.parentElement = this;
      this.children.push(n);
    }
  }
  replaceChildren(...kids) { this.children = []; this.append(...kids); }
  get firstChild() { return this.children[0] || null; }
  remove() { if (this.parentElement) this.parentElement.children = this.parentElement.children.filter((c) => c !== this); this.parentElement = null; }
  getBoundingClientRect() { return { top: 0, left: 0, bottom: 0, right: 0 }; }
  querySelectorAll() { return []; }
  /** Every element under this one whose class names `cls`. */
  find(cls) { return [this, ...this.children.flatMap((c) => c.find ? c.find(cls) : [])].filter((e) => (e.className || '').split(' ').includes(cls)); }
}
const winOn = {};
globalThis.Node = El;
globalThis.document = { createElement: (t) => new El(t), createElementNS: (_, t) => new El(t), createTextNode: (t) => Object.assign(new El('#text'), { textContent: t }), addEventListener() {}, removeEventListener() {} };
globalThis.window = { addEventListener: (ev, fn) => { winOn[ev] = fn; }, removeEventListener() {}, matchMedia: () => ({ matches: false }) };

const { readerView } = await import('../../main/resources/web/js/views/reader.mjs');

const ME = 'web-a1b2c3';

/** A page's context: the record copy ready, every op sent kept in `sent`, every scope asked in `subs`, the reach and
 *  level from `S`, the PC's clock `S.clock`. */
function page() {
  const sent = [];
  const subs = [];
  const S = { device: ME, names: {}, record: new ClientRecord(), recordReady: true, reach: { ok: true, since: 0 }, phase: 'open', clock: 1000 };
  S.record.snap({ epoch: 1, seq: 1, items: {} }, ['rec.reader.']);
  const ctx = {
    S, sent, subs,
    pcNow: () => S.clock,
    desc: () => undefined,
    sub(s) { subs.push(s); }, unsub() {},
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
  for (const fn of pane.on.scroll) fn(); // the wheel scrolls the pane
  winOn.pagehide();
  assert.deepEqual(ctx.sent.filter((d) => d.action === 'place'), [], 'nothing is sent out of reach');
  // the PC answers again, a minute later
  ctx.S.clock = 61000;
  ctx.S.reach = { ok: true, since: 1 };
  v.onReach();
  const places = ctx.sent.filter((d) => d.action === 'place');
  assert.equal(places.length, 1, 'the place read meanwhile is sent once the PC answers');
  assert.deepEqual(places[0].targets, ['place.b1']);
  assert.equal(places[0].at, 1000, 'L1 C1: it carries the time he read, not the time it was sent');
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

/** Every text under `el`, in order. */
const text = (el) => el.textContent + el.children.map(text).join('');

test('nothing on the shelf or in a book is written as the text "null" (S1-2 JW0, seen in headless Firefox)', () => {
  const ctx = page();
  const shelf = { state: 'ok', shelf: { folder: '', folders: ['Essays'], books: [{ id: 'b1', title: 'One', author: 'Ann' }] } };
  ctx.desc = (scope) => (scope === 'desc/reader' ? shelf : undefined);
  const s = readerView(ctx).mount({ window: 'reader', target: null, anchor: null }, { input: false });
  assert.match(text(s), /One/);
  assert.doesNotMatch(text(s), /null/, 'the shelf with no note and no Continue');
  const ctx2 = page();
  const v = readerView(ctx2);
  const root = v.mount({ window: 'reader', target: 'book:b1', anchor: null }, { input: false });
  v.onDesc('reader/book:b1', { title: 'One', length: 400, chapters: [] });
  v.onDesc('reader/book:b1/part:0', { part: 0, parts: 2, charStart: 0, charEnd: 200,
    blocks: [{ kind: 'para', charStart: 0, charEnd: 5, spans: [{ text: 'First', marks: [] }] }] });
  assert.match(text(root), /First/);
  assert.doesNotMatch(text(root.find('text')[0]), /null/, 'a first part has no step back');
});

/** A book of two parts (chapter 2 from character 200), open and shown at its start. */
function twoParts() {
  const ctx = page();
  const v = readerView(ctx);
  const root = v.mount({ window: 'reader', target: 'book:b1', anchor: null }, { input: false });
  v.onDesc('reader/book:b1', { title: 'One', length: 400, chapters: [{ title: 'One', anchor: '@c0.1~' }, { title: 'Two', anchor: '@c200.1~' }] });
  v.onDesc('reader/book:b1/part:0', { part: 0, parts: 2, charStart: 0, charEnd: 200, blocks: [] });
  const place = (v_, by, at, seq) => {
    ctx.S.record.snap({ epoch: 1, seq, items: { 'rec.reader.place.b1': { v: v_, by, at } } }, ['rec.reader.']);
    v.onRecord(new Set(['rec.reader.place.b1']));
  };
  return { ctx, v, root, place };
}

test('L1: another device\'s place for the open book moves the page there, with no offer and no op', () => {
  const { ctx, root, place } = twoParts();
  place('@c300.1~', 'phone', 5000, 2); // he read on the glasses
  assert.ok(ctx.subs.includes('reader/book:b1/part:1'), 'the page goes to the part that place is in');
  assert.equal(root.find('gothere').length, 0, 'nothing is offered');
  winOn.pagehide(); // a save point
  assert.deepEqual(ctx.sent.filter((d) => d.action === 'place'), [], 'a move the page made by itself writes nothing');
});

test('L1: the page\'s own later input is not moved by an older place from another device; its own place never moves it', () => {
  const { ctx, root, place } = twoParts();
  ctx.S.clock = 9000;
  for (const fn of root.find('reading')[0].on.wheel) fn(); // he reads at the desk at 9000
  place('@c300.1~', 'phone', 5000, 2); // the glasses' place of 5000 reaches the record first
  assert.ok(!ctx.subs.includes('reader/book:b1/part:1'), 'his later input stands: the page stays');
  place('@c300.1~', ME, 9500, 3);
  assert.ok(!ctx.subs.includes('reader/book:b1/part:1'), 'its own place is where it is');
  place('@c350.1~', 'phone', 9800, 4); // later input on the glasses
  assert.ok(ctx.subs.includes('reader/book:b1/part:1'), 'a later input elsewhere is followed');
});

// L1 R2 B1 (RP FIX 1): a place is stamped with the time of HIS input and nothing else.
const places = (ctx) => ctx.sent.filter((d) => d.action === 'place');
/** Every button under `el`. */
const buttons = (el) => [...(el.tag === 'button' ? [el] : []), ...el.children.flatMap((c) => (c.children ? buttons(c) : []))];
const click = (el) => { for (const fn of el.on.click) fn({ preventDefault() {} }); };

test('L1 R2 B1: a tap that scrolls nothing, then a resize that reflows the text, sends no op', () => {
  const { ctx, root } = twoParts();
  const pane = root.find('reading')[0];
  ctx.S.clock = 2000;
  for (const fn of pane.on.pointerdown) fn(); // a tap in the text that scrolls nothing
  ctx.S.clock = 2500;
  if (winOn.resize) winOn.resize(); // the window changes size (a monitor waking, a TV changing mode)
  for (const fn of pane.on.scroll) fn(); // the reflow moves the pane
  winOn.pagehide(); // a save point
  assert.deepEqual(places(ctx), [], 'no input moved the place: nothing is sent');
});

test('L1 R2 B1: a chapter click carries its own time, so an older place from elsewhere does not override it', () => {
  const { ctx, v, root, place } = twoParts();
  ctx.S.clock = 4000;
  click(buttons(root.find('chapters')[0]).find((b) => text(b) === 'Two')); // the sidebar: chapter Two (part 1)
  assert.equal(ctx.subs.at(-1), 'reader/book:b1/part:1');
  place('@c50.1~', 'phone', 3500, 2); // the glasses' place, read before his click, arrives while the part loads
  assert.equal(ctx.subs.at(-1), 'reader/book:b1/part:1', 'his later click stands: the page does not go back to part 0');
  ctx.S.clock = 6000;
  v.onDesc('reader/book:b1/part:1', { part: 1, parts: 2, charStart: 200, charEnd: 400, blocks: [] });
  winOn.pagehide();
  assert.deepEqual(places(ctx).map((d) => d.at), [4000], 'the op carries the click\'s time');
});

test('L1 R2 B1: a jump\'s time is the click\'s, not its part\'s arrival', () => {
  const { ctx, v, root } = twoParts();
  ctx.S.clock = 2000;
  click(root.find('step')[0]); // "Next: Two →"
  ctx.S.clock = 3000; // the part arrives a second later
  v.onDesc('reader/book:b1/part:1', { part: 1, parts: 2, charStart: 200, charEnd: 400, blocks: [] });
  winOn.pagehide();
  assert.deepEqual(places(ctx).map((d) => d.at), [2000]);
});

