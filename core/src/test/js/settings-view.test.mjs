// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-5, stage W: the Settings page and the home page's Games card in the real page shell (`app.mjs`) over the
// DOM stand-in. The pins: every row of `desc/settings` is drawn as its type's control and a change sends the op its
// row names (Settings' item, an app's whole style with its field changed — a second change carrying the first —, a
// tier's whole list, a number, a window's own item in that window); a value shows at once and holds until the
// description shows it; a refused one goes back and is said; a row below the device's level is dim and says why; a
// search narrows the rows; a refused description is said; the Games card shows its summary and links nowhere.

import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { openPage, browserStorage, handTimers, restore } from './pagehost.mjs';

afterEach(restore);

const welcome = (extra = {}) => ({ t: 'welcome', proto: 3, device: 'web-1', level: 'full', conn: 7, pcClock: 1, epoch: 3, seq: 10,
  contracts: { reader: 1, settings: 1, games: 1, tmux: 1 }, bundle: { hash: 'h' }, ...extra });

const ON_OFF = [{ id: true, label: 'on' }, { id: false, label: 'off' }];
const g = (id, label, type, value, more = {}) => ({ id, window: 'settings', label, scope: 'glasses', type, item: id, value, default: value, level: 'control', ...more });
const style = (field, label, options, value) => ({ id: `app.tmux.${field}`, window: 'tmux', label, scope: 'glasses', type: 'choice', item: 'app.tmux', field,
  options, value, default: value, level: 'control' });

/** A description as `desc/settings` makes it (S1-5 W: Settings' rows by category, a window's own rows first in its app's). */
function settingsDesc({ quiet = 'off', depth = -1 } = {}) {
  return { id: 'settings', name: 'Settings', min: 'view', availability: 'live', categories: [
    { name: 'Global', rows: [
      g('brightness', 'Brightness', 'number', 40, { from: 0, to: 100, step: 5, default: 50 }),
      g('quietHours', 'Quiet hours', 'choice', quiet, { scope: 'shared', default: 'off', options: [{ id: 'off', label: 'off' }, { id: '22-07', label: '22–07' }] }),
      g('phoneNotifications', 'Phone notifications', 'bool', true, { scope: 'phone', options: ON_OFF }),
    ] },
    { name: 'Animations · Swipe', rows: [
      g('swipeEffects', 'Swipe', 'set', ['slide'], { options: [{ id: 'slide', label: 'Slide' }, { id: 'push', label: 'Push' }, { id: 'matrix', label: 'Matrix' }] }),
    ] },
    { name: 'Tmux', rows: [
      { id: 'alerts', label: 'Alerts', scope: 'shared', type: 'bool', value: true, level: 'full', window: 'tmux', item: 'alerts' },
      style('face', 'Font', [{ id: 'default', label: 'default' }, { id: 'Atkinson', label: 'Atkinson' }], 'default'),
      { ...style('depth', 'Depth', [{ id: -1, label: 'global' }, { id: 4, label: '4' }], depth), default: -1 },
    ] },
  ], actions: [{ id: 'set', kind: 'register', level: 'control' }] };
}

async function page({ path = '/w/settings', level = 'full', desc = settingsDesc() } = {}) {
  const timers = handTimers();
  const p = await openPage({ path, storage: browserStorage(), timers, fetchAnswer: () => { throw new Error('no fetch expected'); } });
  const ws = p.sockets[0];
  ws.open();
  ws.frame(welcome({ level }));
  if (desc) ws.frame({ t: 'snap', scope: 'desc/settings', desc });
  return { p, ws };
}

const all = (root) => root.children.filter((c) => c.tagName).flatMap((c) => [c, ...all(c)]);
const main = (p) => p.doc.byId.main;
const byKey = (p, k) => all(main(p)).find((e) => e.getAttribute('data-key') === k);
const ops = (ws) => ws.sent.filter((f) => f.t === 'op').map((f) => f.op);
const chosen = (p, k) => all(byKey(p, k)).find((o) => o.getAttribute('selected') !== null).getAttribute('value');
const change = (el, value) => {
  if (typeof value === 'boolean') el.checked = value;
  else el.value = String(value);
  el.fire('change', { target: el });
};
const pick = (op) => ({ window: op.window, action: op.action, targets: op.targets, result: op.result });

test('every row is drawn as its type\'s control and a change sends the op its row names', async () => {
  const { p, ws } = await page();
  assert.ok(ws.sent.some((f) => f.t === 'sub' && f.scope === 'desc/settings'), 'the page asks for the description');
  assert.deepEqual(all(main(p)).filter((e) => e.tagName === 'H2').map((e) => e.textContent).slice(0, 3), ['Global', 'Animations · Swipe', 'Tmux']);
  const text = main(p).textContent;
  assert.match(text, /Quiet hours.*every device/, 'a shared row says so');
  assert.match(text, /Phone notifications.*the phone/, 'a phone row says so');
  assert.match(text, /Brightness.*default 50/, 'a value away from its default says the default');
  change(byKey(p, 'set:settings:quietHours'), '22-07');
  change(byKey(p, 'set:tmux:app.tmux.depth'), 4);
  change(byKey(p, 'set:tmux:app.tmux.face'), 'Atkinson');
  change(byKey(p, 'set:settings:swipeEffects:matrix'), true);
  const range = byKey(p, 'set:settings:brightness');
  range.value = '45';
  range.fire('change', { target: range });
  change(byKey(p, 'set:tmux:alerts'), false);
  assert.deepEqual(ops(ws).map(pick), [
    { window: 'settings', action: 'set', targets: ['quietHours'], result: '22-07' },
    { window: 'settings', action: 'set', targets: ['app.tmux'], result: { face: 'default', depth: 4 } },
    { window: 'settings', action: 'set', targets: ['app.tmux'], result: { face: 'Atkinson', depth: 4 } },
    { window: 'settings', action: 'set', targets: ['swipeEffects'], result: ['slide', 'matrix'] },
    { window: 'settings', action: 'set', targets: ['brightness'], result: 45 },
    { window: 'tmux', action: 'set', targets: ['alerts'], result: false },
  ], 'Settings\' item, the whole style with the earlier change carried, the whole list, a number, Tmux\'s own item');
  // shown at once; held over an answer while the description is the older one; the description's once it shows it
  assert.equal(chosen(p, 'set:settings:quietHours'), '22-07');
  const q = ops(ws)[0];
  ws.frame({ t: 'ans', stream: q.stream, n: q.n, answer: { a: 'seen' } });
  assert.equal(chosen(p, 'set:settings:quietHours'), '22-07', 'answered, the older description does not step it back');
  ws.frame({ t: 'snap', scope: 'desc/settings', desc: settingsDesc({ quiet: '22-07', depth: 4 }) });
  assert.equal(chosen(p, 'set:settings:quietHours'), '22-07');
  assert.equal(chosen(p, 'set:tmux:app.tmux.depth'), '4');
});

test('a refused change goes back to the description\'s value and is said', async () => {
  const { p, ws } = await page();
  change(byKey(p, 'set:settings:quietHours'), '22-07');
  const q = ops(ws)[0];
  ws.frame({ t: 'ans', stream: q.stream, n: q.n, answer: { a: 'refused', code: 'level', reason: 'needs level control on this device' } });
  assert.equal(chosen(p, 'set:settings:quietHours'), 'off');
  assert.match(p.said().at(-1), /Quiet hours: needs level control on this device/);
});

test('a row below the device\'s level is dim and says why; a search narrows the rows', async () => {
  const { p, ws } = await page({ level: 'view' });
  const rows = all(main(p)).filter((e) => e.classList.contains('trow'));
  assert.equal(rows.length, 7);
  assert.ok(rows.every((r) => r.classList.contains('dim')), 'every row needs control or full');
  assert.match(main(p).textContent, /Quiet hoursoffchanging it needs level control/);
  assert.match(main(p).textContent, /Alertsonchanging it needs level full/);
  assert.equal(byKey(p, 'set:settings:quietHours'), undefined, 'no control to change it');
  const search = byKey(p, 'set-search');
  search.value = 'quiet';
  search.fire('input', { target: search });
  assert.deepEqual(all(main(p)).filter((e) => e.classList.contains('trow')).map((r) => r.children[0].textContent), ['Quiet hours']);
  search.value = 'tmux depth';
  search.fire('input', { target: search });
  assert.deepEqual(all(main(p)).filter((e) => e.classList.contains('trow')).map((r) => r.children[0].textContent), ['Depth'], 'a category\'s name counts');
  search.value = 'nothing like it';
  search.fire('input', { target: search });
  assert.match(main(p).textContent, /No setting matches “nothing like it”/);
  assert.equal(ops(ws).length, 0);
});

test('a refused description is said, and this browser\'s looks stay', async () => {
  const { p, ws } = await page({ desc: null });
  assert.match(main(p).textContent, /Reading the settings from the PC/);
  ws.frame({ t: 'ans', scope: 'desc/settings', answer: { a: 'refused', code: 'contract', reason: 'this PC shares no window \'settings\'' } });
  assert.match(main(p).textContent, /The settings could not be read from the PC: this PC shares no window 'settings'/);
  assert.ok(all(main(p)).some((e) => e.classList.contains('looks')), 'the looks are this browser\'s own');
});

test('the Games card shows its summary and where it is played, and links nowhere', async () => {
  const { p, ws } = await page({ path: '/', desc: null });
  ws.frame({ t: 'snap', scope: 'desc/main', desc: { id: 'main', name: 'Main', who: {}, cards: [
    { window: 'reader', name: 'Reader', icon: 'book', min: 'view', availability: 'live', summary: { line: '3 books' } },
    { window: 'games', name: 'Games', icon: 'games', min: 'view', availability: 'live', page: false,
      summary: { line: '$1,000 · W0 · L0', detail: 'no tournament running', more: false } },
  ] } });
  const games = byKey(p, 'card:games');
  assert.equal(games.tagName, 'DIV', 'no link');
  assert.equal(all(main(p)).filter((e) => e.tagName === 'A' && e.getAttribute('href') === '/w/games').length, 0);
  assert.equal(games.textContent, 'Games$1,000 · W0 · L0no tournament runningon the glasses');
  assert.equal(byKey(p, 'card:reader').tagName, 'A', 'a window with a page still opens it');
});
