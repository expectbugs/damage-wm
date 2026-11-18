// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-3, piece B17 (first built as package JT part 1): the colour snapshot view's renderer. Every character after
// a wide one, a box drawing one, a combining mark or an emoji keeps its column (each box placed by its column, never by
// the length of the text before it); the flow layout of logical lines (B9) wraps by columns, moves a wide character
// whole and puts the cursor as a mark at the tail of the last line with ink; the grid keeps the cursor's cell; the
// 16, 256 and true colours as the runs carry them, reverse and dim, a foreground moved to be read on its theme, the
// glass theme's green levels; a row that did not change keeps its element.

import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import * as T from '../../main/resources/web/js/term-render.mjs';
import { bareDocument, restore } from './pagehost.mjs';
import { tablesFrame, rulerRow, RULER_KINDS, shellFrame, editorFrame } from './tmux-fixtures.mjs';

afterEach(restore);

test('columns: wide 2, combining 0 (joined to the cell before), box drawing and plain 1, as the PC counts them', () => {
  assert.deepEqual(['a', '日', 'カ', '─', '│', '\u{1F600}', '́', 'é'].map((c) => T.cellWidth(c.codePointAt(0))), [1, 2, 2, 1, 1, 2, 0, 1]);
  const cells = T.cellsOf([{ t: 'café!', fg: 2 }, { t: '\x07' }]);
  assert.deepEqual(cells.map((c) => [c.t, c.w]), [['c', 1], ['a', 1], ['f', 1], ['é', 1], ['!', 1], ['�', 1]]);
  assert.equal(cells[0].fg, 2);
});

test('every ruler bar sits at columns 0, 10, … 50 whatever stands between them (the grid never drifts)', () => {
  for (const [kind, fill] of RULER_KINDS) {
    const segs = T.segments(T.cellsOf(rulerRow(fill)));
    const bars = segs.filter((s) => s.t === '|').map((s) => s.col);
    assert.deepEqual(bars, [0, 10, 20, 30, 40, 50], kind);
    assert.equal(T.colsOf(T.cellsOf(rulerRow(fill))), 51, kind);
  }
  // plain ASCII in one style is one box; anything else a box of its own at its column
  const segs = T.segments(T.cellsOf([{ t: 'ab日c─d', fg: 1 }, { t: 'ef', fg: 2 }]));
  assert.deepEqual(segs.map((s) => [s.col, s.n, s.t]), [[0, 2, 'ab'], [2, 2, '日'], [4, 1, 'c'], [5, 1, '─'], [6, 1, 'd'], [7, 2, 'ef']]);
  // the table's rows are 36 columns each, its wide names included
  for (const row of tablesFrame().lines.slice(0, 7)) assert.equal(T.colsOf(T.cellsOf(row)), 36);
});

test('the flow layout of logical lines: wraps by columns, a wide character moves whole, trailing blanks go, the cursor a tail mark', () => {
  const cells = T.cellsOf([{ t: 'abcdefghi日xyz   ' }]);
  const end = T.trimmed(cells);
  assert.equal(end, 13, 'trailing blanks dropped');
  assert.deepEqual(T.chunks(cells, end, 10), [[0, 9], [9, 13]], 'the wide character does not straddle column 10');
  assert.equal(T.trimmed(T.cellsOf([{ t: '$ ' }]), 2), 2, 'the cursor after the prompt keeps the blank before it');
  const f = shellFrame();
  const lay = T.layout(f, 30);
  const curRows = lay.rows.filter((r) => r.cur);
  assert.equal(curRows.length, 1);
  assert.equal(lay.rows.at(-1), curRows[0], 'on the last line with ink');
  assert.equal(curRows[0].cur.col, 'me@workstation:~/src/kettle$'.length, 'at its tail, the trailing blank under it');
  // a pane's empty bottom rows are dropped from the flow, wherever the pane's cursor stood (a logical line has no row)
  const tall = { ...f, lines: [...f.lines, [], [{ t: '   ' }], []] };
  assert.equal(T.layout(tall, 30).rows.length, lay.rows.length);
  assert.equal(T.layout({ ...tall, cursor: { x: 0, y: tall.lines.length - 1, visible: true } }, 30).rows.length, lay.rows.length);
  const narrow = T.layout(f, 20);
  assert.deepEqual([narrow.rows.at(-1).cur.col, narrow.rows.at(-2).cur], [8, null], 'a wrapped prompt: the mark on its last visual row');
  assert.equal(T.layout({ lines: [[], []], cursor: { x: 3, y: 1, visible: true } }, 20).rows.length, 1, 'nothing with ink: the mark alone');
  const fit = T.layout(f, null);
  assert.equal(fit.rows.length, 24, 'the session\'s own rows');
  assert.equal(fit.width, 80);
  assert.deepEqual(fit.rows[f.cursor.y].cur, { col: f.cursor.x, n: 1, t: ' ', fg: null, bg: null, fl: 0 });
  assert.equal(T.layout({ ...f, cursor: { ...f.cursor, visible: false } }).rows.some((r) => r.cur), false, 'a hidden cursor is not drawn');
});

test('colours: the 16 from the theme, the cube and grey ramp, true colour; reverse and dim', () => {
  const p = T.PALETTES.dark;
  assert.deepEqual(T.rgbOf(1, p), p.ansi[1]);
  assert.deepEqual(T.rgbOf(196, p), [255, 0, 0]);
  assert.deepEqual(T.rgbOf(16 + 36 * 1 + 6 * 2 + 3, p), [95, 135, 175]);
  assert.deepEqual(T.rgbOf(232, p), [8, 8, 8]);
  assert.deepEqual(T.rgbOf(255, p), [238, 238, 238]);
  assert.deepEqual(T.rgbOf('#2aa198', p), [42, 161, 152]);
  for (const bad of [256, -1, 'red', '#12345', 1.5, null, undefined]) assert.equal(T.rgbOf(bad, p), null, String(bad));
  assert.deepEqual(T.styleOf(null, null, 0, p), { color: '#e8e6e1', background: null, bold: false, underline: false });
  assert.deepEqual(T.styleOf(null, null, T.FLAGS.REVERSE, p), { color: '#121212', background: '#e8e6e1', bold: false, underline: false });
  assert.equal(T.styleOf('#2aa198', null, 0, p).color, '#2aa198', 'a colour that reads is drawn as given');
  assert.equal(T.styleOf(2, 4, T.FLAGS.BOLD | T.FLAGS.UNDERLINE, p).background, '#8fb0ff');
  assert.notEqual(T.styleOf(null, null, T.FLAGS.DIM, p).color, '#e8e6e1', 'dim');
});

test('every foreground is read on its theme: moved toward contrast only as far as needed', () => {
  for (const [name, pal] of [['light', T.PALETTES.light], ['dark', T.PALETTES.dark]]) {
    for (const c of [0, 7, 8, 15, 11, 226, 232, 255, '#ffffff', '#000000', '#999999']) {
      const st = T.styleOf(c, null, 0, pal);
      const fg = [1, 3, 5].map((i) => parseInt(st.color.slice(i, i + 2), 16));
      assert.ok(T.contrast(fg, pal.bg) >= T.MIN_CONTRAST - 0.01, `${name} ${c}: ${st.color}`);
    }
  }
  // white text on the light theme becomes dark enough; a colour that already reads is kept
  assert.notEqual(T.styleOf('#ffffff', null, 0, T.PALETTES.light).color, '#ffffff');
  assert.notEqual(T.styleOf(0, null, 0, T.PALETTES.dark).color, '#3a3936', 'the dark theme\'s black is lifted');
  assert.equal(T.styleOf(1, null, 0, T.PALETTES.light).color, '#b3261e');
});

test('the glass theme: every colour a level of the glasses\' green, never below the floor, text on a bright field dark', () => {
  const g = T.PALETTES.glass;
  const green = (hex) => {
    const [r, gg, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16));
    return Math.abs(r / 0x6d - gg / 0xff) < 0.02 && Math.abs(b / 0x9c - gg / 0xff) < 0.02 ? Math.round((gg / 0xff) * 15) : null;
  };
  for (const c of [0, 1, 4, 7, 12, 15, 196, 232, '#2aa198', '#d77757']) {
    const lv = green(T.styleOf(c, null, 0, g).color);
    assert.ok(lv !== null && lv >= T.GLASS_FLOOR, `${c} → level ${lv}`);
  }
  const rev = T.styleOf(null, null, T.FLAGS.REVERSE, g);
  assert.equal(green(rev.background), 15);
  assert.ok(green(rev.color) <= 7, 'dark text on the bright field');
  assert.equal(T.styleOf(2, 0, 0, g).background, null, 'a black field is the panel itself');
});

test('paint: one row element per visual row, boxes placed by column, the cursor inverted; an unchanged row keeps its element', () => {
  const doc = bareDocument();
  const box = doc.createElement('div');
  const f = tablesFrame();
  const r = T.paint(box, f, { pal: T.PALETTES.light });
  assert.equal(r.rows, 16);
  assert.equal(box.children.length, 16);
  assert.equal(box.style['--cols'], '60');
  const ruler = box.children[8];
  const bars = ruler.children.filter((s) => s.textContent === '|');
  assert.deepEqual(bars.map((s) => s.style['--c']), ['0', '10', '20', '30', '40', '50']);
  assert.deepEqual(bars.map((s) => s.style['--n']), ['1', '1', '1', '1', '1', '1']);
  const cur = box.children[f.cursor.y].children.find((s) => s.classList.contains('tm-cur'));
  assert.equal(cur.style['--c'], String(f.cursor.x));
  // a frame where one row changed: the other rows keep their elements
  const keep = box.children[3];
  const next = { ...f, lines: f.lines.map((l, i) => (i === 0 ? [{ t: 'changed' }] : l)) };
  T.paint(box, next, { pal: T.PALETTES.light });
  assert.ok(box.children[3] === keep, 'row 3 did not change: its element is kept');
  assert.equal(box.children[0].textContent, 'changed');
  // fewer rows: the rest go
  T.paint(box, { ...f, lines: f.lines.slice(0, 3), rows: 3, cursor: null }, { pal: T.PALETTES.light });
  assert.equal(box.children.length, 3);
});

test('paint in colour: a run\'s colours and flags reach its box', () => {
  const doc = bareDocument();
  const box = doc.createElement('div');
  T.paint(box, editorFrame(), { pal: T.PALETTES.dark });
  const status = box.children[22].children[0];
  assert.equal(status.style.backgroundColor, '#e8e6e1', 'the status line in reverse video');
  const kw = box.children[1].children.find((s) => s.textContent === 'function');
  assert.ok(kw.classList.contains('b'), 'bold');
  assert.equal(kw.style.color, '#d79bf0');
});
