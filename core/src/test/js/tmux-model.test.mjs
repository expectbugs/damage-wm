// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-3, piece B17 (first built as package JT part 1): the Tmux page's model without a DOM. The paste rule (a
// line break or a control character asks, naming the line count); every op's draft with no expectation (L1: tmux takes
// input in arrival order; the prompt guard is not built); mutes as one record action; a live source's newest picture
// (B11: no numbering, gap or resync), a frame that came late said; the sessions' order; the stream's source name.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as M from '../../main/resources/web/js/tmux-model.mjs';
import { ClientRecord } from '../../main/resources/web/js/record.mjs';
import { tmuxDesc, SESSIONS, T_NOW, shellFrame } from './tmux-fixtures.mjs';

test('the keys the browser keeps are sent from the bar as their control bytes', () => {
  assert.deepEqual(M.BAR_KEYS.map(([l, d]) => [l, d.charCodeAt(0)]), [['Ctrl-W', 23], ['Ctrl-T', 20], ['Ctrl-N', 14], ['Ctrl-Q', 17], ['Ctrl-V', 22]]);
});

test('a paste with a line break or a control character asks first, naming its lines; one plain line does not', () => {
  assert.deepEqual(M.pasteShape('ls -la'), { data: 'ls -la', lines: 1, endsWithBreak: false, controls: false, asks: false, empty: false });
  assert.equal(M.pasteShape('a\tb').asks, false, 'a tab is text');
  const two = M.pasteShape('make clean\r\nmake package');
  assert.deepEqual([two.data, two.lines, two.asks], ['make clean\rmake package', 2, true]);
  const one = M.pasteShape('rm -rf build\n');
  assert.deepEqual([one.data, one.lines, one.endsWithBreak, one.asks], ['rm -rf build\r', 1, true, true], 'one line ending in Enter runs: it asks');
  const three = M.pasteShape('cd x\nmake\nmake test\n');
  assert.equal(three.lines, 3);
  assert.equal(M.pasteShape('a\x03').asks, true, 'a control character acts as a key');
  assert.equal(M.pasteShape('').empty, true);
  const c = M.pasteConfirm(three, 'shell');
  assert.equal(c.title, 'Paste 3 lines into shell?');
  assert.match(c.body, /line break is sent as Enter.*last line runs/);
  assert.equal(c.cancel, 'Cancel');
  assert.equal(M.pasteConfirm(one, 'shell').title, 'Paste 1 line into shell?');
});

test('every op on a session: its params, no expectation (the latest real input wins; tmux orders input by arrival)', () => {
  const build = SESSIONS.find((x) => x.name === 'build');
  const shell = SESSIONS.find((x) => x.name === 'shell');
  assert.deepEqual(M.keyDraft(build, 'y'), { window: 'tmux', action: 'key', targets: ['session:workstation:build'], params: { key: 'y' } }, 'a waiting session\'s key carries nothing more');
  assert.deepEqual(M.rawDraft(shell, '\x03').params, { data: '\x03' });
  assert.deepEqual(M.lineDraft(shell, 'make').params, { text: 'make' });
  assert.deepEqual(M.snippetDraft(shell, '/clear').params, { snippet: '/clear' });
  assert.deepEqual(M.selectDraft(shell, 2).params, { window: 2 });
  assert.deepEqual(M.renameDraft(shell, 'my shell').params, { text: 'my shell' });
  assert.deepEqual(M.newDraft('buildbox'), { window: 'tmux', action: 'new', targets: ['buildbox'] });
  assert.equal(M.endDraft(shell).params, undefined);
  for (const d of [M.keyDraft(build, 'y'), M.rawDraft(shell, 'x'), M.endDraft(shell)]) assert.equal('expect' in d, false);
  assert.equal(M.newName({ a: 'sent', result: 'scratch' }), 'scratch');
  assert.equal(M.newName({ a: 'sent', result: { name: 'scratch' } }), 'scratch');
  assert.equal(M.newName({ a: 'sent' }), null);
});

test('mutes and the Alerts row: the page\'s op on its way first, then the record, then the description; one `mute` action both ways', () => {
  const rec = new ClientRecord();
  rec.snap({ epoch: 1, seq: 5, items: { 'rec.tmux.mute.workstation/notes': { v: true, by: 'phone-1', at: 1 } } }, ['rec.tmux.']);
  const pend = new M.Pending();
  const notes = SESSIONS.find((x) => x.name === 'notes');
  const shell = SESSIONS.find((x) => x.name === 'shell');
  assert.equal(M.mutedOf(notes, rec, pend), true);
  assert.equal(M.mutedOf(shell, rec, pend), false);
  const t = pend.set('mute.workstation/notes', false);
  assert.equal(M.mutedOf(notes, rec, pend), false, 'the op on its way over the record');
  pend.answered('mute.workstation/notes', t, false);
  assert.equal(M.mutedOf(notes, rec, pend), true, 'refused: the record\'s again');
  assert.deepEqual(M.muteDraft(notes, false), { window: 'tmux', action: 'mute', targets: ['mute.workstation/notes'], result: false });
  assert.deepEqual(M.muteDraft(shell, true), { window: 'tmux', action: 'mute', targets: ['mute.workstation/shell'], result: true });
  assert.deepEqual(M.setDraft({ id: 'alerts' }, false), { window: 'tmux', action: 'set', targets: ['alerts'], result: false });
});

test('the sessions: waiting first, then the most recently active (activity on the PC\'s clock, B9)', () => {
  assert.deepEqual(M.sessionsOf(tmuxDesc()).map((x) => x.name), ['build', 'shell', 'editor', 'tables', 'logs', 'notes']);
  assert.equal(M.activityMs(T_NOW), T_NOW);
  assert.equal(M.activityMs(0), null);
  assert.equal(M.sessionLine(SESSIONS[0], T_NOW), '1 window · attached · active 20 s ago');
});

test('a host\'s health is counted on the PC\'s clock from its values', () => {
  assert.equal(M.healthLine({ name: 'a', health: { state: 'ok', lastOkMs: T_NOW } }, T_NOW), 'answering');
  assert.equal(M.healthLine({ name: 'b', health: { state: 'quiet', lastOkMs: T_NOW - 42e3 } }, T_NOW), 'quiet 42 s');
  assert.equal(M.healthLine({ name: 'b', health: { state: 'quiet', lastOkMs: T_NOW - 42e3 } }, T_NOW + 10e3), 'quiet 52 s');
  assert.equal(M.healthLine({ name: 'c', health: { state: 'failing', error: 'ssh: connection refused', lastOkMs: T_NOW - 300e3 } }, T_NOW), 'not answering: ssh: connection refused · last answered 5 m ago');
});

test('addresses: a session and its history; a host may hold a colon, a session name never does', () => {
  assert.deepEqual(M.placeOf(null), { at: 'sessions' });
  assert.deepEqual(M.placeOf('session:workstation:shell'), { at: 'session', host: 'workstation', name: 'shell' });
  assert.deepEqual(M.placeOf('history:host:2:logs'), { at: 'history', host: 'host:2', name: 'logs' });
  for (const bad of ['session:', 'session:x', 'session:x:', 'nothing:a:b']) assert.equal(M.placeOf(bad), null, bad);
  const s = { host: 'workstation', name: 'shell' };
  assert.deepEqual([M.target(s), M.canvasScope(s), M.historyScope(s), M.frameScope(s), M.streamScope(s)],
    ['session:workstation:shell', 'tmux/session:workstation:shell', 'tmux/history:workstation:shell', 'live/tmux/frame/workstation/shell', 'live/tmux/stream/workstation/shell']);
  assert.deepEqual(M.renamedOf({ a: 'sent', result: { renamed: { from: 'session:w:a', to: 'session:w:b' } } }), { at: 'session', host: 'w', name: 'b' });
});

test('key names, labels and new names', () => {
  assert.deepEqual(M.keysOf({ keys: { quick: ['Enter', 'C-c', 'bad key', 'x'.repeat(17)], snippets: ['/clear', ''] } }),
    { quick: ['Enter', 'C-c'], bad: ['bad key', 'x'.repeat(17)], snippets: ['/clear'] });
  assert.deepEqual(['Escape', 'C-c', 'M-x', 'Up', 'BTab', 'q'].map(M.keyLabel), ['Esc', 'Ctrl-C', 'Alt-X', 'Up', 'Shift-Tab', 'q']);
  assert.equal(M.cleanName('my  session!'), 'my-session');
  assert.equal(M.cleanName('!!!'), null);
});

test('a live source: an init draws, every frame draws (one socket is ordered: no numbering), stream bytes are data, an end ends', () => {
  const f = new M.Feed();
  const fr = shellFrame();
  assert.equal(f.got({ frame: fr }, T_NOW), 'draw', 'a frame is a whole picture');
  assert.equal(f.got({ init: fr }, T_NOW), 'draw');
  assert.equal(f.lateMs, 0, 'an init may carry an idle session\'s last picture: its age is not lateness');
  f.stale = 'reconnect';
  assert.match(M.feedLine(f, { reach: { ok: true }, pcNow: T_NOW }).text, /Connected again/);
  assert.equal(f.got({ init: fr }, T_NOW), 'draw');
  assert.equal(f.stale, null, 'the fresh init');
  f.got({ frame: { ...fr, capturedAt: T_NOW } }, T_NOW);
  assert.match(M.feedLine(f, { reach: { ok: true }, pcNow: T_NOW }).text, /^Live$/);
  f.got({ frame: { ...fr, capturedAt: T_NOW - 9000 } }, T_NOW);
  assert.match(M.feedLine(f, { reach: { ok: true }, pcNow: T_NOW }).text, /Behind: this picture reached the page 9 s after the PC took it/);
  assert.equal(f.got({ data: 'aGk=' }, T_NOW), 'data');
  assert.equal(f.got({ end: { reason: 'the session ended' } }, T_NOW), 'end');
  assert.equal(f.ended, 'the session ended');
  const g = new M.Feed();
  g.got({ end: { reason: 'renamed', to: 'session:workstation:kettle' } }, T_NOW);
  assert.deepEqual(g.renamed, { at: 'session', host: 'workstation', name: 'kettle' });
});

test('out of reach the line says since when and the picture\'s age; the history\'s rows from its lines', () => {
  const f = new M.Feed();
  f.got({ init: { ...shellFrame(), capturedAt: T_NOW - 120e3 } }, T_NOW);
  const l = M.feedLine(f, { reach: { ok: false, since: T_NOW - 60e3 }, pcNow: T_NOW });
  assert.equal(l.cls, 'bad');
  assert.match(l.text, /^PC not answering since \d+:\d\d: nothing is sent · the picture from \d+:\d\d \(2 m ago\)\.$/);
  assert.deepEqual(M.historyRows({ lines: [[{ t: 'a', fg: 1 }], 'plain'] }), [[{ t: 'a', fg: 1 }], [{ t: 'plain' }]]);
  assert.deepEqual(M.historyRows({ capturedAt: 1 }), []);
});
