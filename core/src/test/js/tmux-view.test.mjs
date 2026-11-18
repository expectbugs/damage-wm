// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-3, piece B17 (first built as package JT part 1): the Tmux page in the real page shell (`app.mjs`) over the DOM
// stand-in, every session, frame, byte and key made up. At a desk's width the session is the terminal (`terminal.mjs`
// over a FAKE of the library's surface, `fake-xterm.mjs`): its stream is asked once the library runs, the init and the
// output are written to it, and what it produces for his keys is a live op carrying its connection (never queued, no
// expectation), pipelined in order; a desk whose PC serves no library draws the pictures and says why; at a phone's
// width the pictures are drawn newest-only. Out of reach a key is refused at once and said, never held; a paste of
// more than one line asks first naming its lines, and its confirm is void once the connection changed; a typed line
// goes only through Run it's confirm; ending a session asks, Cancel at rest; a device below full and an older PC are
// asked nothing; hidden → need none; a session that ends under the page is said and takes no key; a Tmux alert pops
// no toast (G2).

import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { openPage, browserStorage, handTimers, restore, settle } from './pagehost.mjs';
import { tmuxDesc, tmuxRecord, shellFrame, waitingFrame, editorFrame, historyLines, canvasOf, streamInit, streamData, OUTPUT, SESSIONS, T_NOW } from './tmux-fixtures.mjs';
import * as X from '../../main/resources/web/js/terminal.mjs';
import { Terminal } from './fake-xterm.mjs';

afterEach(restore);

const CONTRACTS = { reader: 1, notices: 1, torrents: 1, files: 1, feed: 1, tmux: 1 };
const welcome = (extra = {}) => ({ t: 'welcome', proto: 4, device: 'web-1', level: 'full', conn: 7, pcClock: T_NOW, epoch: 3, seq: 10,
  contracts: CONTRACTS, bundle: { hash: 'h' }, ...extra });
const FRAMES = (name) => `live/tmux/frame/workstation/${name}`;
const STREAM = (name) => `live/tmux/stream/workstation/${name}`;
const NO_LIB = async () => { throw new Error('the answer was 404: this host serves no terminal library'); };

async function page({ path = '/w/tmux', level = 'full', contracts, storage = browserStorage(), desc = tmuxDesc(), phone = false, lib = 'fake' } = {}) {
  X.lib.load = lib === 'fake' ? async () => ({ Terminal }) : NO_LIB;
  const timers = handTimers();
  const p = await openPage({ path, storage, timers, fetchAnswer: () => { throw new Error('no fetch expected'); } });
  p.win.matchMedia = (q) => ({ matches: phone && /max-width/.test(q), addEventListener() {}, removeEventListener() {} });
  const ws = p.sockets[0];
  ws.open();
  ws.frame(welcome({ level, ...(contracts ? { contracts } : {}) }));
  if (!contracts && level === 'full') {
    ws.frame({ t: 'snap', scope: 'desc/tmux', desc });
    ws.frame(tmuxRecord());
  }
  return { p, ws, timers };
}

/** The library's style sheet arrives (the DOM stand-in loads nothing by itself), and the page's awaits run. */
async function loaded(p) {
  for (const l of p.doc.documentElement.children.filter((c) => c.tagName === 'LINK')) l.fire('load');
  await settle();
  await settle();
}

/** A session at a desk's width: the terminal, its stream asked once the library runs, the init written. */
async function desk(name = 'shell', opts = {}) {
  const r = await page({ path: `/w/tmux/session:workstation:${name}`, ...opts });
  r.ws.frame({ t: 'snap', scope: `tmux/session:workstation:${name}`, desc: canvasOf('workstation', name) });
  await loaded(r.p);
  r.ws.frame({ t: 'live', src: STREAM(name), init: streamInit(name) });
  r.term = Terminal.instances.at(-1);
  return r;
}

/** A session drawn from the PC's pictures (a phone's width, or a desk with no library), its first picture drawn. */
async function pictures(name = 'shell', frame = shellFrame(), opts = {}) {
  const r = await page({ path: `/w/tmux/session:workstation:${name}`, phone: true, ...opts });
  r.ws.frame({ t: 'snap', scope: `tmux/session:workstation:${name}`, desc: canvasOf('workstation', name) });
  await loaded(r.p);
  r.ws.frame({ t: 'live', src: FRAMES(name), init: frame });
  r.timers.fire(0);
  return r;
}

const all = (root) => root.children.filter((c) => c.tagName).flatMap((c) => [c, ...all(c)]);
const main = (p) => p.doc.byId.main;
const find = (p, tag, text) => all(main(p)).find((e) => e.tagName === tag && (text === undefined || e.textContent === text));
const byClass = (p, c) => all(main(p)).find((e) => e.classList.contains(c));
const allClass = (p, c) => all(main(p)).filter((e) => e.classList.contains(c));
const subs = (ws) => ws.sent.filter((f) => f.t === 'sub');
const unsubs = (ws) => ws.sent.filter((f) => f.t === 'unsub').map((f) => f.scope);
const ops = (ws, action) => ws.sent.filter((f) => f.t === 'op' && (!action || f.op.action === action)).map((f) => f.op);
const status = (p) => byClass(p, 'tm-status').textContent;
const dlg = (p) => p.doc.byId.confirm;
const paste = (el, text) => el.fire('paste', { clipboardData: { getData: () => text }, stopPropagation() {} });

test('the page entry at a desk: the stream is asked once the terminal runs, its init is written, and a key typed in it is a live op with its connection', async () => {
  const { p, ws, term } = await desk();
  const sc = subs(ws).map((f) => f.scope);
  for (const s of ['desc/tmux', 'tmux', 'tmux/session:workstation:shell', STREAM('shell')]) assert.ok(sc.includes(s), s);
  assert.equal(sc.includes(FRAMES('shell')), false, 'the terminal reads the stream, not the pictures');
  assert.deepEqual(subs(ws).find((f) => f.scope === STREAM('shell')).need, { kind: 'focus' }, 'no pace of the page\'s own (B8)');
  assert.equal(term.options.scrollback, 5000);
  assert.deepEqual([term.cols, term.rows], [80, 24], 'the pane\'s size: the page never asks tmux for one');
  assert.match(term.text(), /^me@workstation:~\/src\/kettle\$ ls --color/);
  assert.match(term.text(), /tests {6}214 passed, 0 failed/);
  assert.deepEqual([term.y, term.x], [shellFrame().cursor.y, shellFrame().cursor.x], 'the cursor where tmux has it');
  term.type('l');
  const [op] = ops(ws, 'raw');
  assert.deepEqual(op, { device: '', conn: 7, k: op.k, window: 'tmux', action: 'raw', targets: ['session:workstation:shell'], result: null, params: { data: 'l' }, at: op.at });
  assert.equal(typeof op.at, 'number');
  assert.equal(op.stream, undefined, 'never queued');
  assert.equal('expect' in op, false);
  // the session's output, written in order
  ws.frame({ t: 'live', src: STREAM('shell'), data: streamData(OUTPUT) });
  assert.match(term.text(), /\[ok\] {2}packaged kettle-0\.4\.2\.tar\.gz/);
});

test('keys are pipelined in the order typed, never waiting for an answer; Escape stays the session\'s; the bar sends the keys the browser keeps', async () => {
  const { p, ws, term } = await desk();
  for (const d of ['l', 's', '\r', '\x1b', '\x03', '\x1b[A']) term.type(d);
  const raw = ops(ws, 'raw');
  assert.deepEqual(raw.map((o) => o.params.data), ['l', 's', '\r', '\x1b', '\x03', '\x1b[A']);
  assert.deepEqual(raw.map((o) => o.k), [...raw.map((o) => o.k)].sort((a, b) => a - b), 'numbers rise in the order typed');
  assert.equal(new Set(raw.map((o) => o.k)).size, raw.length);
  // a key pressed in the terminal never reaches the page's own keys (Escape is the shell's Back)
  const ev = { key: 'Escape', stopped: false, stopPropagation() { this.stopped = true; } };
  for (const fn of byClass(p, 'tm-xterm').on.keydown) fn(ev);
  assert.equal(ev.stopped, true);
  assert.equal(globalThis.location.pathname, '/w/tmux/session:workstation:shell');
  find(p, 'BUTTON', 'Ctrl-W').fire('click');
  assert.equal(ops(ws, 'raw').at(-1).params.data, '\x17');
  // Shift+Esc lets the keyboard go: nothing sent
  const n = ops(ws).length;
  term.key({ type: 'keydown', key: 'Escape', shiftKey: true });
  assert.equal(ops(ws).length, n);
});

test('a desk whose PC serves no terminal library draws the PC\'s pictures and says why', async () => {
  const r = await page({ path: '/w/tmux/session:workstation:shell', lib: 'none' });
  await loaded(r.p);
  assert.ok(subs(r.ws).some((f) => f.scope === FRAMES('shell')), 'the pictures asked instead');
  assert.equal(subs(r.ws).some((f) => f.scope === STREAM('shell')), false);
  r.ws.frame({ t: 'live', src: FRAMES('shell'), init: shellFrame() });
  r.timers.fire(0);
  assert.match(status(r.p), /No terminal here: the PC did not serve the terminal library \(the answer was 404: this host serves no terminal library\)/);
  assert.match(allClass(r.p, 'tm-row')[0].textContent, /^me@workstation/);
  assert.ok(find(r.p, 'BUTTON', 'Run it'), 'Type… stays');
  assert.equal(find(r.p, 'BUTTON', 'Ctrl-W'), undefined, 'the kept keys are the terminal\'s');
});

test('out of reach a key is refused at once and said, never held; back in reach keys go', async () => {
  const { p, ws, timers, term } = await desk();
  timers.tick();
  timers.tick(); // a ping unanswered when the next is due: out of reach
  term.type('x');
  term.type('y');
  assert.deepEqual(ops(ws, 'raw'), [], 'nothing sent');
  assert.match(status(p), /PC not answering since \d+:\d\d: nothing is sent/);
  assert.match(status(p), /2 keys not sent: PC not answering since/);
  assert.equal(p.said().filter((t) => /Keys are not sent: PC not answering/.test(t)).length, 1, 'said once, not per key');
  const enter = find(p, 'BUTTON', 'Enter');
  assert.ok(enter.classList.contains('dim'));
  enter.fire('click');
  assert.deepEqual(ops(ws), []);
  ws.frame({ t: 'pong', k: 2 });
  assert.deepEqual(ops(ws, 'raw'), [], 'nothing refused is sent later');
  term.type('z');
  assert.deepEqual(ops(ws, 'raw').map((o) => o.params.data), ['z']);
});

test('a paste into the terminal goes through the paste rule: more than one line asks, naming its lines (Cancel at rest); one line goes at once', async () => {
  const { p, ws, term } = await desk();
  const box = byClass(p, 'tm-xterm');
  paste(box, 'echo pasted-once');
  assert.deepEqual(ops(ws, 'raw').map((o) => o.params.data), ['echo pasted-once'], 'one line, no ask');
  assert.equal(term.written.some((w) => /pasted-once/.test(w)), false, 'never straight into the emulator');
  paste(box, 'cd ~/src/kettle\nmake clean\nmake package\n');
  assert.equal(dlg(p).open, true);
  assert.match(dlg(p).textContent, /Paste 3 lines into shell\?/);
  assert.equal(p.doc.activeElement.textContent, 'Cancel');
  dlg(p).querySelector('button.rest').fire('click');
  await settle();
  assert.equal(ops(ws, 'raw').length, 1, 'Cancel sends nothing');
  paste(box, 'cd ~/src/kettle\nmake clean\nmake package\n');
  dlg(p).querySelector('button.act').fire('click');
  await settle();
  assert.deepEqual(ops(ws, 'raw').at(-1).params, { data: 'cd ~/src/kettle\rmake clean\rmake package\r' });
});

test('a paste\'s confirm is void when the connection changed before the act; the new connection asks the stream again (a fresh init)', async () => {
  const { p, ws, timers } = await desk();
  paste(byClass(p, 'tm-xterm'), 'a\nb');
  assert.equal(dlg(p).open, true);
  ws.close();
  timers.fire(2000);
  const ws2 = p.sockets[1];
  ws2.open();
  ws2.frame(welcome({ conn: 8 }));
  assert.deepEqual(subs(ws2).filter((f) => f.scope === STREAM('shell')).map((f) => f.need), [{ kind: 'focus' }]);
  assert.match(status(p), /Connected again: asking the PC for a fresh picture/);
  dlg(p).querySelector('button.act').fire('click');
  await settle();
  assert.deepEqual([...ops(ws, 'raw'), ...ops(ws2, 'raw')], []);
  assert.ok(p.said().some((t) => /Paste: nothing sent — the connection or the session changed/.test(t)));
});

test('a typed line is sent only through Run it\'s confirm; Keep editing keeps the text; a paste of lines into the field asks', async () => {
  const { p, ws } = await pictures();
  const input = byClass(p, 'tm-in');
  input.value = 'make package';
  input.fire('input');
  assert.deepEqual(ops(ws), [], 'typing in the field sends nothing');
  find(p, 'FORM').fire('submit');
  assert.equal(dlg(p).open, true);
  assert.match(dlg(p).textContent, /Run this in shell\?.*“make package” is typed into shell on workstation, then Enter\./);
  assert.equal(dlg(p).querySelector('button.act').textContent, 'Run it (sends + Enter)');
  dlg(p).querySelector('button.rest').fire('click');
  await settle();
  assert.deepEqual(ops(ws), []);
  assert.equal(byClass(p, 'tm-in').value, 'make package', 'kept');
  find(p, 'BUTTON', 'Run it').fire('click');
  dlg(p).querySelector('button.act').fire('click');
  await settle();
  assert.deepEqual(ops(ws, 'line').map((o) => [o.conn, o.params]), [[7, { text: 'make package' }]]);
  assert.equal(byClass(p, 'tm-in').value, '');
  paste(byClass(p, 'tm-in'), 'a\nb');
  assert.match(dlg(p).textContent, /Paste 2 lines into shell\?/);
});

test('ending a session asks, naming it, Cancel at rest; End sends `end` and the page goes back to the sessions', async () => {
  const { p, ws } = await desk();
  find(p, 'BUTTON', 'End session…').fire('click');
  assert.equal(dlg(p).open, true);
  assert.match(dlg(p).textContent, /End 'shell'\?.*ends the tmux session shell on workstation/);
  assert.equal(p.doc.activeElement.textContent, 'Cancel');
  dlg(p).querySelector('button.rest').fire('click');
  await settle();
  assert.deepEqual(ops(ws, 'end'), []);
  find(p, 'BUTTON', 'End session…').fire('click');
  dlg(p).querySelector('button.act').fire('click');
  await settle();
  const [op] = ops(ws, 'end');
  assert.deepEqual([op.conn, op.targets], [7, ['session:workstation:shell']]);
  ws.frame({ t: 'ans', conn: 7, k: op.k, answer: { a: 'sent' } });
  assert.equal(globalThis.location.pathname, '/w/tmux');
  assert.ok(p.said().some((t) => /Ended 'shell'\./.test(t)));
  assert.ok(unsubs(ws).includes(STREAM('shell')), 'its stream let go');
});

test('a waiting session shows its mark and prompt line; a quick key is a plain key op (no prompt guard: the latest input wins); a refusal is said', async () => {
  const { p, ws } = await pictures('build', waitingFrame());
  assert.match(byClass(p, 'tm-prompt').textContent, /^Waiting for input at “Do you want to proceed\?”\.$/);
  find(p, 'BUTTON', 'y').fire('click');
  const [op] = ops(ws, 'key');
  assert.deepEqual([op.params, 'expect' in op, op.conn, op.targets], [{ key: 'y' }, false, 7, ['session:workstation:build']]);
  ws.frame({ t: 'ans', conn: 7, k: op.k, answer: { a: 'refused', code: 'program', reason: 'tmux: no such key' } });
  assert.ok(p.said().some((t) => /y: tmux: no such key/.test(t)));
});

test('a device below full is shown nothing of any session and asks the PC for none of it', async () => {
  const { p, ws } = await page({ path: '/w/tmux/session:workstation:shell', level: 'control' });
  assert.deepEqual(subs(ws).map((f) => f.scope).filter((s) => /tmux/.test(s)), []);
  assert.match(main(p).textContent, /Tmux needs a device at level full \(this one is control\): nothing of any session is shown here/);
  assert.equal(byClass(p, 'tm-term'), undefined);
  const rail = p.doc.byId.rail.children.find((a) => a.getAttribute('data-key') === 'rail:tmux');
  assert.ok(rail.classList.contains('dim'));
});

test('a PC whose build names no Tmux contract is never asked for it', async () => {
  const { p, ws } = await page({ path: '/w/tmux', contracts: { reader: 1, notices: 1, torrents: 1, files: 1, feed: 1 } });
  assert.deepEqual(subs(ws).map((f) => f.scope).filter((s) => /tmux/.test(s)), []);
  assert.match(main(p).textContent, /the PC's build has no Tmux page yet/);
});

test('the pictures at a phone\'s width: logical lines flowed, the cursor a tail mark; frames faster than the page draws are drawn newest-only and said', async () => {
  const { p, ws, timers } = await pictures();
  assert.ok(byClass(p, 'tm-term').classList.contains('flow'));
  const rows = allClass(p, 'tm-row');
  assert.match(rows[0].textContent, /^me@workstation:~\/src\/kettle\$ ls --color/);
  assert.equal(rows.at(-1).children.filter((c) => c.classList.contains('tm-cur')).length, 1, 'the tail mark on the last line');
  assert.match(status(p), /^Live/);
  const f = shellFrame();
  for (let i = 2; i <= 6; i++) ws.frame({ t: 'live', src: FRAMES('shell'), frame: { ...f, lines: [[{ t: `picture ${i}` }]], capturedAt: T_NOW } });
  timers.fire(0);
  assert.equal(allClass(p, 'tm-row')[0].textContent.trimEnd(), 'picture 6', 'the newest');
  assert.match(status(p), /4 pictures came faster than this page draws; the newest is shown/);
  // a full-screen program: its rows as a grid at its own size
  ws.frame({ t: 'live', src: FRAMES('shell'), frame: editorFrame() });
  timers.fire(0);
  assert.equal(byClass(p, 'tm-term').classList.contains('flow'), false);
  assert.equal(allClass(p, 'tm-row').length, 24);
});

test('hidden, the page asks the PC for nothing (need none); shown, focus again; leaving lets its sources go', async () => {
  const { p, ws } = await desk();
  p.doc.visibilityState = 'hidden';
  for (const fn of p.doc.on.visibilitychange) fn();
  assert.deepEqual(subs(ws).filter((s) => s.scope === STREAM('shell')).at(-1).need, { kind: 'none' });
  p.doc.visibilityState = 'visible';
  for (const fn of p.doc.on.visibilitychange) fn();
  assert.deepEqual(subs(ws).filter((s) => s.scope === STREAM('shell')).at(-1).need, { kind: 'focus' });
  const term = Terminal.instances.at(-1);
  p.go('/w/tmux');
  assert.ok(unsubs(ws).includes(STREAM('shell')) && unsubs(ws).includes('tmux/session:workstation:shell'));
  assert.equal(term.disposed, true, 'the terminal let go');
});

test('a session that ends under the page is said, its last picture stays, and it takes no key; the stream\'s end is said likewise', async () => {
  const { p, ws } = await pictures();
  ws.frame({ t: 'snap', scope: 'desc/tmux', desc: tmuxDesc({ sessions: SESSIONS.filter((s) => s.name !== 'shell') }) });
  assert.match(status(p), /This session ended: it is no longer on workstation\. The last picture stays below/);
  assert.ok(unsubs(ws).includes(FRAMES('shell')), 'its pictures let go');
  assert.ok(allClass(p, 'tm-row').length > 0);
  find(p, 'BUTTON', 'Enter').fire('click');
  assert.deepEqual(ops(ws, 'key'), []);
  p.go('/w/tmux');
  all(main(p)).find((e) => e.tagName === 'BUTTON' && e.textContent === 'New session').fire('click');
  assert.deepEqual(ops(ws, 'new').map((o) => o.targets), [['workstation']], 'what stopped that session stays with it');
  // a session missing because its host does not answer is not said as ended
  const r2 = await pictures();
  r2.ws.frame({ t: 'snap', scope: 'desc/tmux', desc: tmuxDesc({ sessions: SESSIONS.filter((s) => s.name !== 'shell'),
    hosts: [{ name: 'workstation', health: { state: 'failing', error: 'tmux did not answer', lastOkMs: T_NOW - 60e3 } }] }) });
  assert.match(status(r2.p), /Not listed: workstation is not answering/);
  // the stream's own end (B10: `%exit` → `end{reason}`)
  const r3 = await desk();
  r3.ws.frame({ t: 'live', src: STREAM('shell'), end: { reason: 'the session was closed in tmux' } });
  assert.match(status(r3.p), /This session ended: the session was closed in tmux/);
  r3.term.type('x');
  assert.deepEqual(ops(r3.ws, 'raw'), []);
});

test('the session actions: mute is a record op with no expectation; rename follows the new address; select and new are program ops', async () => {
  const { p, ws } = await pictures('editor', editorFrame());
  find(p, 'BUTTON', 'Mute alerts').fire('click');
  const [m] = ops(ws, 'mute');
  assert.deepEqual([m.targets, m.result, 'expect' in m, typeof m.stream, typeof m.at], [['mute.workstation/editor'], true, false, 'string', 'number']);
  assert.ok(find(p, 'BUTTON', 'Unmute alerts'), 'drawn at once');
  find(p, 'BUTTON', 'Select').fire('click');
  assert.deepEqual(ops(ws, 'select')[0].params, { window: 1 });
  find(p, 'BUTTON', 'Rename…').fire('click');
  dlg(p).querySelector('input').value = 'my editor';
  dlg(p).querySelector('button.act').fire('click');
  await settle();
  const [rn] = ops(ws, 'rename');
  assert.deepEqual(rn.params, { text: 'my editor' });
  ws.frame({ t: 'ans', conn: 7, k: rn.k, answer: { a: 'sent' }, result: { renamed: { from: 'session:workstation:editor', to: 'session:workstation:my-editor' } } });
  assert.equal(globalThis.location.pathname, '/w/tmux/session:workstation:my-editor');
  assert.ok(subs(ws).some((s) => s.scope === FRAMES('my-editor')));
  assert.ok(unsubs(ws).includes(FRAMES('editor')));
  assert.equal(find(p, 'BUTTON', 'Fit pane to the glasses (64×22)'), undefined, 'no glasses-size action on the web');
});

test('the sessions: waiting first, each host\'s health, the muted mark from the record; New session opens what the PC made; no page pace row', async () => {
  const { p, ws } = await page();
  const keys = all(main(p)).map((e) => e.getAttribute('data-key')).filter((k) => k && k.startsWith('s:'));
  assert.deepEqual(keys, ['s:workstation/build', 's:workstation/shell', 's:workstation/editor', 's:workstation/tables', 's:buildbox/logs', 's:workstation/notes']);
  assert.match(main(p).textContent, /buildbox.*quiet 42 s/);
  const notes = all(main(p)).find((e) => e.getAttribute('data-key') === 's:workstation/notes');
  assert.match(notes.textContent, /alerts muted/);
  assert.doesNotMatch(main(p).textContent, /Update \(this browser\)/);
  const btns = all(main(p)).filter((e) => e.tagName === 'BUTTON' && e.textContent === 'New session');
  btns[1].fire('click');
  const [op] = ops(ws, 'new');
  assert.deepEqual([op.targets, op.conn], [['buildbox'], 7]);
  ws.frame({ t: 'ans', conn: 7, k: op.k, answer: { a: 'sent' }, result: 'g2-3' });
  assert.equal(globalThis.location.pathname, '/w/tmux/session:buildbox:g2-3');
  await loaded(p);
  assert.ok(subs(ws).some((s) => s.scope === 'live/tmux/stream/buildbox/g2-3'));
});

test('history: one page of every line, in colour, opened from the session', async () => {
  const { p, ws } = await desk();
  find(p, 'A', 'History').fire('click');
  assert.equal(globalThis.location.pathname, '/w/tmux/history:workstation:shell');
  assert.ok(subs(ws).some((s) => s.scope === 'tmux/history:workstation:shell'));
  ws.frame({ t: 'snap', scope: 'tmux/history:workstation:shell', desc: { capturedAt: T_NOW, lines: historyLines() } });
  assert.match(main(p).textContent, /1001 lines · taken at \d+:\d\d\. The browser's own find \(Ctrl-F\) searches them\./);
  const text = byClass(p, 'hist').textContent;
  assert.match(text, /ERROR step 97/);
  assert.match(text, /BUILD OK in 4m 12s/);
});

test('a Tmux alert in the notice record pops no toast on the web (G2: the glasses only); another window\'s notice in the same change does', async () => {
  const { p, ws } = await page();
  ws.frame({ t: 'snap', scope: 'notices', epoch: 3, seq: 10, items: {} });
  const v = (occ, app, body, target) => ({ occ, raisedAt: T_NOW, app, source: app, body, urgency: 'NORMAL', state: 'RAISED', target });
  ws.frame({ t: 'chg', scope: 'notices', epoch: 3, seq: 11, patches: [
    { addr: 'rec.notices.tmux:workstation/build', set: { by: 'pc', at: T_NOW, v: v(8, 'tmux', 'build wants input: Do you want to proceed?', 'session:workstation:build') } },
    { addr: 'rec.notices.torrents:c0ffee01', set: { by: 'pc', at: T_NOW, v: v(9, 'torrents', 'done · Lantern Weather', 't:c0ffee01') } }] });
  const toasts = p.doc.byId.toasts.children.filter((t) => t.classList.contains('notice'));
  assert.deepEqual(toasts.map((t) => t.getAttribute('data-notice')), ['torrents:c0ffee01']);
});
