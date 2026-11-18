// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-3, piece B17: `terminal.mjs` against a FAKE of the terminal library's surface (`fake-xterm.mjs`), over the DOM
// stand-in. The init writes the pane as captured with its cursor where tmux has it (a full-screen program in the
// alternate screen); `data` is written as its bytes; what the emulator produces is handed on as typed; a paste goes to
// the paste rule, never into the emulator; the font fits the pane's columns between the floor and the page's size and
// never changes the pane's size; a library that cannot be loaded is said with its reason; the glass theme's colours
// are levels of the glasses' green.

import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { bareDocument, restore, settle } from './pagehost.mjs';
import * as X from '../../main/resources/web/js/terminal.mjs';
import { Terminal } from './fake-xterm.mjs';
import { streamInit, streamData, OUTPUT, shellFrame } from './tmux-fixtures.mjs';

afterEach(restore);

/** A surface over the fake, in a bare document; resolves once the library runs (or fails). */
async function surface(load = async () => ({ Terminal })) {
  const doc = bareDocument();
  globalThis.window = { matchMedia: () => ({ matches: false }) };
  X.lib.load = load;
  const got = { bytes: [], pastes: [], ready: 0, fail: null, focus: [] };
  const parent = doc.createElement('div');
  parent.clientWidth = 800;
  const s = X.terminalSurface({ label: 'Terminal of shell', onBytes: (d) => got.bytes.push(d), onPaste: (t) => got.pastes.push(t),
    onFocus: (on) => got.focus.push(on), onReady: () => got.ready++, onFail: (why) => { got.fail = why; } });
  parent.append(s.el);
  for (const l of doc.documentElement.children.filter((c) => c.tagName === 'LINK')) l.fire('load');
  await settle();
  await settle();
  return { s, got, term: Terminal.instances.at(-1), parent };
}

test('the init: reset, the pane as captured with each line break as CR LF, the cursor where tmux has it; data written as its bytes', async () => {
  const { s, got, term } = await surface();
  assert.equal(got.ready, 1);
  assert.equal(term.options.scrollback, 5000);
  s.take({ init: streamInit('shell') });
  assert.equal(term.written[0], '<reset>');
  const f = shellFrame();
  assert.deepEqual([term.y, term.x], [f.cursor.y, f.cursor.x]);
  assert.match(term.text().split('\n')[0], /^me@workstation:~\/src\/kettle\$ ls --color$/);
  assert.doesNotMatch(term.written[1], /[^\r]\n/, 'every line break is CR LF');
  s.take({ data: streamData(OUTPUT) });
  assert.match(term.text(), /\[ok\] {2}packaged kettle-0\.4\.2\.tar\.gz \(212 KB\)/);
  // a full-screen program's pane goes into the alternate screen
  assert.match(X.initWrites({ alternate: true, cursor: { x: 4, y: 2 }, data: btoa('a\nb\n') }), /^\x1b\[\?1049h\x1b\[Ha\r\nb\x1b\[3;5H$/);
  assert.equal(X.initWrites({ data: btoa('x') }), '\x1b[Hx\x1b[1;1H', 'no cursor named: the top left');
  assert.match(X.initWrites({ data: btoa('x'), cursor: { x: 0, y: 0, visible: false } }), /\x1b\[\?25l$/, 'a cursor the program hid stays hidden');
  assert.deepEqual([...X.bytesOf(Buffer.from('é').toString('base64'))], [195, 169], 'base64 to the bytes (UTF-8 as sent)');
});

test('what the emulator produces is handed on as typed; a paste goes to the paste rule, never into the emulator; Shift+Esc lets go', async () => {
  const { s, got, term } = await surface();
  term.type('l');
  term.type('\r');
  assert.deepEqual(got.bytes, ['l', '\r']);
  s.el.fire('paste', { clipboardData: { getData: () => 'a\nb' }, stopPropagation() {} });
  assert.deepEqual(got.pastes, ['a\nb']);
  assert.equal(term.written.some((w) => /a\nb|a\r\nb/.test(w)), false);
  let blurred = false;
  term.blur = () => { blurred = true; };
  term.key({ type: 'keydown', key: 'Escape', shiftKey: true });
  assert.equal(blurred, true);
  assert.deepEqual(got.bytes, ['l', '\r'], 'nothing sent');
  term.key({ type: 'keydown', key: 'Escape' });
  assert.deepEqual(got.bytes.at(-1), '\x1b', 'Escape alone is the session\'s');
  s.destroy();
  assert.equal(term.disposed, true);
});

test('fix A2: the pane\'s terminal queries are not answered into his session, a focus report is no key of his; his keys still go', async () => {
  const { s, got, term } = await surface();
  s.take({ init: streamInit('shell') });
  s.take({ data: streamData('\x1b[c\x1b[0c\x1b[>c\x1b[5n\x1b[6n\x1b[?6n\x1b[4$p\x1b[?2004$p\x1bP$qm\x1b\\\x1b]11;?\x07\x1b]4;1;?\x1b\\' +
    '\x1b]10;?\x07\x1b]12;?\x07\x1b[?1004h') });
  assert.deepEqual(got.bytes, [], 'no reply typed into the pane');
  term.type('\x1b[I');
  term.type('\x1b[O');
  assert.deepEqual(got.bytes, [], 'a focus report is dropped');
  term.type('\x1b[A');
  term.type('\x1b[<0;3;4M');
  assert.deepEqual(got.bytes, ['\x1b[A', '\x1b[<0;3;4M'], 'a key and a mouse report are his');
  // a colour SET is the library's to apply, never swallowed
  assert.equal(term.parser.hooks.osc.find(([n]) => n === 11)[1]('rgb:12/34/56'), false);
});

test('the font fits the pane\'s columns to the view, between the floor and the page\'s size; the pane\'s own size is kept', async () => {
  assert.equal(X.fitFont(800, 80, 0.6, 15), 15, 'room to spare: the page\'s size');
  assert.equal(X.fitFont(600, 80, 0.6, 15), 12.5);
  assert.equal(X.fitFont(200, 200, 0.6, 15), X.MIN_FONT_PX, 'never below the floor: it scrolls sideways instead');
  assert.equal(X.fitFont(0, 80, 0.6, 15), 15, 'no layout yet');
  const { s, term } = await surface();
  s.take({ init: { cols: 132, rows: 40, cursor: { x: 0, y: 0 }, data: btoa('') } });
  assert.deepEqual([term.cols, term.rows], [132, 40], 'the emulator takes the pane\'s size; tmux is never asked for one');
});

test('a library that cannot be loaded is said with its reason, and nothing else runs', async () => {
  const before = Terminal.instances.length;
  const { got } = await surface(async () => { throw new Error('404 this host serves no terminal library'); });
  assert.match(got.fail, /^the PC did not serve the terminal library \(404 this host serves no terminal library\)$/);
  assert.equal(got.ready, 0);
  assert.equal(Terminal.instances.length, before);
  const { got: g2 } = await surface(async () => ({}));
  assert.match(g2.fail, /has no Terminal/, 'a module that is not the library');
});

test('the glass theme: the 16 colours as levels of the glasses\' green, never below the floor; light and dark as the grid has them', () => {
  const g = X.themeColours('glass');
  assert.equal(g.background, '#000000');
  for (const k of ['red', 'blue', 'brightWhite']) {
    const [r, gr, b] = [1, 3, 5].map((i) => parseInt(g[k].slice(i, i + 2), 16));
    assert.ok(r < gr && b < gr && gr >= Math.round((255 * 6) / 15) - 1, `${k} ${g[k]} is a green level`);
  }
  assert.equal(X.themeColours('dark').red, '#ff6b6b');
  assert.equal(X.themeColours('light').background, '#fbfaf7');
});
