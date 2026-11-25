// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-5, stage W: the shared rows module (`rows.mjs`) over the DOM stand-in — the one copy of a settings row that
// Files, Tmux, Torrents, Feed and the Settings page draw: a value's precedence, the op it makes, its words, and each
// type's control sending the value the description names (a select's option id, a set's list in the options' order,
// a number as a number); a row below the device's level is dim, says why and has no control.

import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { bareDocument, restore } from './pagehost.mjs';
import * as R from '../../main/resources/web/js/rows.mjs';
import { Pending } from '../../main/resources/web/js/record.mjs';

afterEach(restore);

const all = (root) => root.children.filter((c) => c.tagName).flatMap((c) => [c, ...all(c)]);
const byKey = (root, k) => all(root).find((e) => e.getAttribute('data-key') === k);
const rec = (items) => ({ value: (w, n) => items[`rec.${w}.${n}`] });

const SIZE = { id: 'glasses.size', label: 'Size', scope: 'glasses', type: 'choice', options: [{ id: 0, label: 'global' }, { id: 288, label: '288' }], value: 0, level: 'full' };
const ALERTS = { id: 'alerts', label: 'Alerts', scope: 'shared', type: 'bool', value: true, level: 'full' };
const SWIPE = { id: 'swipeEffects', item: 'swipeEffects', window: 'settings', label: 'Swipe', type: 'set', value: ['slide'], level: 'control',
  options: [{ id: 'slide', label: 'Slide' }, { id: 'push', label: 'Push' }, { id: 'matrix', label: 'Matrix' }] };
const BRIGHT = { id: 'brightness', item: 'brightness', window: 'settings', label: 'Brightness', type: 'number', from: 0, to: 100, step: 5, value: 40, level: 'control' };

test('a value is this page\'s on its way, else the record\'s, else the description\'s; the op names the row\'s action and item', () => {
  const pend = new Pending('tmux');
  assert.equal(R.rowValue('tmux', ALERTS, rec({}), pend), true, 'the description\'s');
  assert.equal(R.rowValue('tmux', ALERTS, rec({ 'rec.tmux.alerts': false }), pend), false, 'the record\'s');
  pend.set('alerts', true);
  assert.equal(R.rowValue('tmux', ALERTS, rec({ 'rec.tmux.alerts': false }), pend), true, 'this page\'s (a Pending)');
  assert.equal(R.rowValue('files', SIZE, rec({ 'rec.files.glasses.size': 288 }), { 'glasses.size': 416 }), 416, 'this page\'s (a plain object)');
  assert.deepEqual(R.setDraft('tmux', ALERTS, false), { window: 'tmux', action: 'set', targets: ['alerts'], result: false });
  assert.deepEqual(R.setDraft('files', { ...SIZE, action: 'size' }, 288), { window: 'files', action: 'size', targets: ['glasses.size'], result: 288 });
  assert.deepEqual(R.setDraft('settings', { id: 'app.tmux.depth', item: 'app.tmux' }, {}), { window: 'settings', action: 'set', targets: ['app.tmux'], result: {} });
  assert.deepEqual([R.words(ALERTS, false), R.words(SIZE, 288), R.words(SWIPE, ['matrix', 'slide']), R.words(SWIPE, []), R.words(BRIGHT, 45)],
    ['off', '288', 'Slide, Matrix', 'none', '45']);
});

test('each type\'s control sends the value the description names', () => {
  bareDocument();
  const sent = [];
  const opts = { level: 'full', need: 'control', why: null, send: (v) => sent.push(v) };
  const size = R.settingRow(SIZE, 0, opts);
  const sel = byKey(size, 'set:glasses.size');
  sel.value = '288';
  sel.fire('change', { target: sel });
  const alerts = R.settingRow(ALERTS, true, { ...opts, note: 'the glasses' });
  const box = byKey(alerts, 'set:alerts');
  box.checked = false;
  box.fire('change', { target: box });
  assert.match(alerts.textContent, /the glasses$/, 'the note after the control');
  const swipe = R.settingRow(SWIPE, ['slide'], opts);
  assert.equal(swipe.tagName, 'DIV', 'a set is a group of its own boxes, not one label');
  const matrix = byKey(swipe, 'set:swipeEffects:matrix');
  matrix.checked = true;
  matrix.fire('change', { target: matrix });
  const bright = R.settingRow(BRIGHT, 40, opts);
  const range = byKey(bright, 'set:brightness');
  assert.deepEqual([range.getAttribute('min'), range.getAttribute('max'), range.getAttribute('step')], ['0', '100', '5']);
  range.value = '45';
  range.fire('input', { target: range });
  assert.match(bright.textContent, /45/, 'the value follows the slider');
  assert.equal(sent.length, 3, 'a slider moving sends nothing until it is let go');
  range.fire('change', { target: range });
  assert.deepEqual(sent, [288, false, ['slide', 'matrix'], 45], 'the option\'s own id, a bool, the list in the options\' order, a number');
});

test('a row below the device\'s level is dim, says why and has no control; one out of reach is dim with the reason', () => {
  bareDocument();
  const row = R.settingRow(SWIPE, ['push'], { level: 'view', need: 'control', why: 'needs level control', send: () => assert.fail('nothing sent') });
  assert.ok(row.classList.contains('dim'));
  assert.equal(row.textContent, 'SwipePushchanging it needs level control');
  assert.equal(all(row).filter((e) => e.tagName === 'INPUT' || e.tagName === 'SELECT').length, 0);
  const away = R.settingRow(SIZE, 0, { level: 'full', need: 'full', why: 'PC not answering since 14:02', send: () => {} });
  const sel = byKey(away, 'set:glasses.size');
  assert.ok(sel.classList.contains('dim'));
  assert.equal(sel.getAttribute('data-why'), 'PC not answering since 14:02');
});
