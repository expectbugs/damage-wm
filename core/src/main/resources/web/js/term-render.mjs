// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-3, piece B17 (first built as package JT part 1; CONTRACT.md §3.4): the colour snapshot view — a frame of
// the PC's capture (`lines[[{t, fg, bg, fl}]]`, `cols`, `rows`, `cursor{x, y, visible}`, `alternate`) drawn in a
// monospace face. Columns are exact by construction: every run of plain ASCII in one style is one box and every other
// character (wide, box drawing, combining) a box of its own, each placed at `column × cell width` from the row's left
// (one multiplication per box, never a sum, so no rounding adds up along a row), its width the columns it covers.
// Widths follow the PC's own rule (`Sgr.width`: East Asian wide 2, combining marks 0), so the page and the glasses
// agree with tmux on where a column falls. Colours as the runs carry them — the 16 base colours from the theme's own
// palette, the 256-colour cube and grey ramp, and true colour — kept readable: in the light and dark themes a
// foreground too close to what is behind it is moved toward contrast; in the glass theme every colour becomes a level
// of the glasses' green by its luminance, as the glasses map colours to grey (`Sgr.fgLevel`). The cursor is a static
// inverted cell (TMUX.md: never blinking). Two layouts: `fit` (a full-screen program's frame, row-exact: the session's
// own size, rows as tmux has them, the cursor in its cell) and `flow` (a normal pane's frame, whose lines are LOGICAL
// lines — the glasses' joined capture, B9 — each wrapped at the columns the view holds, still on the grid, trailing
// blanks dropped; its cursor is a mark at the tail of the last line with ink, since a joined line has no pane row).
// The desk's terminal emulator is another surface (`terminal.mjs`); this module draws the grid.

import { h } from './dom.mjs';

const BOLD = 1;
const DIM = 2;
const UNDERLINE = 4;
const REVERSE = 8;
export const FLAGS = { BOLD, DIM, UNDERLINE, REVERSE };

// ---- columns ----

/** Columns a code point takes: the PC's rule (`Sgr.width`, kept identical so the page and the glasses agree). */
export function cellWidth(cp) {
  if ((cp >= 0x0300 && cp <= 0x036f) || (cp >= 0x1ab0 && cp <= 0x1aff) || (cp >= 0x20d0 && cp <= 0x20ff) || cp === 0x200d
    || (cp >= 0xfe00 && cp <= 0xfe0f)) return 0;
  if ((cp >= 0x1100 && cp <= 0x115f) || (cp >= 0x2e80 && cp <= 0x303e) || (cp >= 0x3041 && cp <= 0x33ff)
    || (cp >= 0x3400 && cp <= 0x4dbf) || (cp >= 0x4e00 && cp <= 0x9fff) || (cp >= 0xa000 && cp <= 0xa4cf)
    || (cp >= 0xac00 && cp <= 0xd7a3) || (cp >= 0xf900 && cp <= 0xfaff) || (cp >= 0xfe30 && cp <= 0xfe4f)
    || (cp >= 0xff00 && cp <= 0xff60) || (cp >= 0xffe0 && cp <= 0xffe6)
    || (cp >= 0x1f300 && cp <= 0x1f64f) || (cp >= 0x1f680 && cp <= 0x1f6ff) || (cp >= 0x1f900 && cp <= 0x1f9ff)
    || (cp >= 0x20000 && cp <= 0x3fffd)) return 2;
  return 1;
}

const colourKey = (c) => (c === undefined || c === null ? null : c);

/** A row's runs as cells `{t, w, fg, bg, fl}`: a combining mark joins the cell before it; a control character (none
 *  should reach a frame) is drawn as U+FFFD, never dropped unseen. */
export function cellsOf(runs) {
  const cells = [];
  for (const r of runs || []) {
    const text = String(r.t ?? r.text ?? '');
    const fg = colourKey(r.fg);
    const bg = colourKey(r.bg);
    const fl = r.fl | 0;
    for (const ch of text) {
      const cp = ch.codePointAt(0);
      const w = cp < 0x20 || cp === 0x7f ? 1 : cellWidth(cp);
      if (w === 0 && cells.length) {
        cells[cells.length - 1].t += ch;
        continue;
      }
      cells.push({ t: w === 1 && (cp < 0x20 || cp === 0x7f) ? '�' : ch, w: w || 1, fg, bg, fl });
    }
  }
  return cells;
}

export const colsOf = (cells) => cells.reduce((n, c) => n + c.w, 0);

const plain = (c) => c.w === 1 && c.t.length === 1 && c.t >= ' ' && c.t <= '~';
const sameStyle = (a, b) => a.fg === b.fg && a.bg === b.bg && a.fl === b.fl;

/** The boxes of cells [from, to): `{col, n, t, fg, bg, fl}`, `col` counted from cell `from`. ASCII in one style merges;
 *  anything else stands alone, so a glyph from another face never moves the columns after it. */
export function segments(cells, from = 0, to = cells.length) {
  const out = [];
  let col = 0;
  for (let i = from; i < to; i++) {
    const c = cells[i];
    const last = out[out.length - 1];
    if (last && plain(c) && last.plain && sameStyle(last, c)) {
      last.t += c.t;
      last.n += 1;
    } else out.push({ col, n: c.w, t: c.t, fg: c.fg, bg: c.bg, fl: c.fl, plain: plain(c) });
    col += c.w;
  }
  return out.map(({ plain: _p, ...s }) => s);
}

/** A cell that draws nothing: a space with no background, no reverse and no underline. */
const blank = (c) => c.t === ' ' && c.bg === null && !(c.fl & (REVERSE | UNDERLINE));

/** The cells to draw in the flow layout: trailing blanks dropped, but never the cell under the cursor (`keepCol`). */
export function trimmed(cells, keepCol = -1) {
  let end = cells.length;
  while (end > 0 && blank(cells[end - 1])) end--;
  if (keepCol >= 0) {
    let col = 0;
    let i = 0;
    for (; i < cells.length && col + cells[i].w <= keepCol; i++) col += cells[i].w;
    end = Math.max(end, Math.min(cells.length, i + 1));
  }
  return end;
}

/** The flow layout's chunks of cells [0, end): at most `wrap` columns each; a wide character that would straddle the
 *  edge starts the next chunk. Always at least one chunk. */
export function chunks(cells, end, wrap) {
  const out = [];
  let start = 0;
  let col = 0;
  for (let i = 0; i < end; i++) {
    if (col + cells[i].w > wrap && i > start) {
      out.push([start, i]);
      start = i;
      col = 0;
    }
    col += cells[i].w;
  }
  out.push([start, end]);
  return out;
}

// ---- colours ----

const hexRgb = (s) => [1, 3, 5].map((i) => parseInt(s.slice(i, i + 2), 16));
const PAL = (fg, bg, ansi) => ({ fg: hexRgb(fg), bg: hexRgb(bg), ansi: ansi.map(hexRgb) });
/** The 16 base colours per theme, chosen to be read on that theme's background (the page's own --fg and --bg). */
export const PALETTES = {
  light: PAL('#1c1c1a', '#fbfaf7', ['#1c1c1a', '#b3261e', '#2e7d32', '#8a5a00', '#2457c5', '#8e3fa8', '#0f7a80', '#6b6860',
    '#67645c', '#d3372b', '#3c9a40', '#a06c00', '#3a6fe0', '#a957c4', '#148f96', '#8a877f']),
  dark: PAL('#e8e6e1', '#121212', ['#3a3936', '#ff6b6b', '#81c995', '#f2c14e', '#8fb0ff', '#d79bf0', '#6fd3d8', '#d0cec8',
    '#77746c', '#ff8a80', '#9ee6b0', '#ffd77a', '#aac4ff', '#e6b5f7', '#95e6ea', '#ffffff']),
  // the glasses' green (the page's glass --fg); every colour is a level of it
  glass: { ...PAL('#6dff9c', '#000000', ['#000000', '#cd0000', '#00cd00', '#cdcd00', '#0000ee', '#cd00cd', '#00cdcd', '#e5e5e5',
    '#7f7f7f', '#ff0000', '#00ff00', '#ffff00', '#5c5cff', '#ff00ff', '#00ffff', '#ffffff']), green: true },
};

const CUBE = [0, 95, 135, 175, 215, 255];

/** A run's colour as RGB, or null for the default: an index 0–255 (16–231 the xterm cube, 232–255 its grey ramp) or
 *  `#rrggbb`. */
export function rgbOf(c, pal) {
  if (c === null || c === undefined) return null;
  if (typeof c === 'number' && Number.isInteger(c) && c >= 0 && c <= 255) {
    if (c < 16) return pal.ansi[c].slice();
    if (c < 232) {
      const i = c - 16;
      return [CUBE[Math.floor(i / 36)], CUBE[Math.floor(i / 6) % 6], CUBE[i % 6]];
    }
    const g = 8 + (c - 232) * 10;
    return [g, g, g];
  }
  if (typeof c === 'string' && /^#[0-9a-fA-F]{6}$/.test(c)) return hexRgb(c);
  return null;
}

const lin = (v) => {
  const s = v / 255;
  return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
};
/** WCAG relative luminance and contrast ratio. */
export const luminance = ([r, g, b]) => 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
export function contrast(a, b) {
  const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
}
const mix = (a, b, t) => a.map((v, i) => Math.round(v + (b[i] - v) * t));
const hex = (c) => `#${c.map((v) => Math.max(0, Math.min(255, v)).toString(16).padStart(2, '0')).join('')}`;

/** The contrast a foreground keeps against what is behind it (light and dark themes). */
export const MIN_CONTRAST = 3;

/** `fg` moved toward black or white, the least that reaches MIN_CONTRAST against `bg`. */
export function readable(fg, bg) {
  if (contrast(fg, bg) >= MIN_CONTRAST) return fg;
  const to = luminance(bg) > 0.4 ? [0, 0, 0] : [255, 255, 255];
  let lo = 0;
  let hi = 1;
  for (let i = 0; i < 14; i++) {
    const t = (lo + hi) / 2;
    if (contrast(mix(fg, to, t), bg) >= MIN_CONTRAST) hi = t;
    else lo = t;
  }
  return mix(fg, to, hi);
}

/** The glasses' level 0..15 of a colour (`Sgr`'s luminance). */
const level = ([r, g, b]) => Math.round(((r * 299 + g * 587 + b * 114) / 1000) * 15 / 255);
/** The levels a glass foreground keeps above its field — on the panel's black, a floor: the glasses floor colours at 3
 *  on their panel; a screen's black needs a little more to be read. */
export const GLASS_FLOOR = 6;
const greenAt = (pal, l) => pal.fg.map((v) => Math.round((v * l) / 15));

/**
 * The colours a cell is drawn in: `{color, background (null = the terminal's own), bold, underline}` as CSS values.
 * Reverse swaps the two (the default background becoming the theme's); dim takes the foreground halfway to what is
 * behind it.
 */
export function styleOf(fg, bg, fl, pal) {
  let f = rgbOf(fg, pal);
  let b = rgbOf(bg, pal);
  let out;
  if (pal.green) {
    // on the glasses' scale: the default foreground is the full level, the default field the panel's black
    let fl0 = f ? level(f) : 15;
    let bl = b ? level(b) : 0;
    if (fl & REVERSE) [fl0, bl] = [bl, fl0];
    if (fl & DIM) fl0 = Math.max(4, Math.round(fl0 * 0.6));
    // dark text on a bright field (reverse video), else at least GLASS_FLOOR levels above the field
    if (bl >= 8 && fl0 - bl < GLASS_FLOOR) fl0 = Math.min(fl0, Math.max(0, bl - 8));
    else if (fl0 - bl < GLASS_FLOOR) fl0 = Math.min(15, bl + GLASS_FLOOR);
    out = { f: greenAt(pal, fl0), b: bl > 0 ? greenAt(pal, bl) : null };
  } else {
    if (fl & REVERSE) [f, b] = [b || pal.bg.slice(), f || pal.fg.slice()];
    const behind = b || pal.bg;
    let fc = f || pal.fg;
    if (fl & DIM) fc = mix(fc, behind, 0.45);
    out = { f: readable(fc, behind), b };
  }
  return { color: hex(out.f), background: out.b ? hex(out.b) : null, bold: !!(fl & BOLD), underline: !!(fl & UNDERLINE) };
}

/** The palette of the page's theme now: its `data-theme`, and the system's for "follow the system". */
export function themeOf(root, matchMedia) {
  const t = root && root.dataset ? root.dataset.theme : null;
  if (t === 'glass' || t === 'dark' || t === 'light') return t;
  return matchMedia && matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

// ---- the layout of a frame ----

/**
 * A frame as visual rows: `[{segs, cur}]`, `cur` = {col, n, t} for the cursor's cell on that row. `wrap` = null for
 * the session's own layout (every row as tmux has it, at least `frame.rows` of them), or the columns a row may take
 * (the flow layout of logical lines). `width` = the widest visual row in columns.
 */
export function layout(frame, wrap = null) {
  const lines = (frame && frame.lines) || [];
  let n = Math.max(lines.length, wrap ? 0 : (frame && frame.rows) | 0);
  const shown = frame && frame.cursor && frame.cursor.visible !== false;
  let cur = shown && !wrap ? frame.cursor : null;
  if (wrap) {
    // the flow drops the blank lines below the last one with ink (the capture holds the pane's empty rows); the cursor
    // is a mark at that line's tail (a logical line has no pane row for the cursor's own cell)
    while (n > 0 && cellsOf(lines[n - 1]).every(blank)) n--;
    if (shown) {
      n = Math.max(1, n);
      const cells = cellsOf(lines[n - 1]);
      cur = { y: n - 1, x: colsOf(cells.slice(0, trimmed(cells))) };
    }
  }
  const rows = [];
  let width = wrap ? 0 : (frame && frame.cols) | 0;
  for (let y = 0; y < n; y++) {
    const cells = cellsOf(lines[y]);
    const curCol = cur && cur.y === y ? cur.x | 0 : -1;
    const end = wrap ? trimmed(cells, curCol) : cells.length;
    const parts = wrap ? chunks(cells, end, wrap) : [[0, end]];
    let col0 = 0;
    for (const [a, b] of parts) {
      const segs = segments(cells, a, b);
      const span = colsOf(cells.slice(a, b));
      const row = { segs, cur: null };
      if (curCol >= 0) {
        const at = curCol - col0;
        const last = parts[parts.length - 1][1] === b;
        if (at >= 0 && (at < span || (last && at >= span))) {
          // the cell under the cursor: its character and width, or a blank past the row's end
          let col = 0;
          let cell = null;
          for (let i = a; i < b; i++) {
            if (col + cells[i].w > at) { cell = cells[i]; break; }
            col += cells[i].w;
          }
          row.cur = cell ? { col, n: cell.w, t: cell.t, fg: cell.fg, bg: cell.bg, fl: cell.fl } : { col: at, n: 1, t: ' ', fg: null, bg: null, fl: 0 };
        }
      }
      width = Math.max(width, span, row.cur ? row.cur.col + row.cur.n : 0);
      rows.push(row);
      col0 += span;
    }
  }
  return { rows, width };
}

// ---- the DOM ----

const pxs = (v) => `${Math.round(v * 1000) / 1000}px`;
/** A row's height per px of font: box drawing's vertical strokes meet from one row to the next. */
export const LINE = 1.2;

function segEl(s, pal, cls = 'tm-s') {
  const st = styleOf(s.fg, s.bg, s.fl, pal);
  const e = h('span', { class: cls + (st.bold ? ' b' : '') + (st.underline ? ' u' : ''), text: s.t });
  e.style.setProperty('--c', String(s.col));
  e.style.setProperty('--n', String(s.n));
  e.style.color = st.color;
  if (st.background) e.style.backgroundColor = st.background;
  return e;
}

/** The cursor: the cell drawn again with its colours swapped. */
function curEl(c, pal) {
  const fl = (c.fl ^ REVERSE) & (REVERSE | BOLD | UNDERLINE);
  return segEl({ ...c, fl }, pal, 'tm-s tm-cur');
}

/**
 * Draws `frame` into `box` (its rows as children `div.tm-row`); a row whose content did not change keeps its
 * element. `opts`: `wrap` (columns per visual row, or null), `pal`. Returns `{rows, width}` in visual rows and columns.
 */
export function paint(box, frame, { wrap = null, pal }) {
  const lay = layout(frame, wrap);
  const kids = box.children;
  for (let i = 0; i < lay.rows.length; i++) {
    const r = lay.rows[i];
    const sig = JSON.stringify([r.segs, r.cur]);
    const old = kids[i];
    if (old && old.dataset && old.dataset.sig === sig) continue;
    const row = h('div', { class: 'tm-row', 'data-sig': sig }, r.segs.map((s) => segEl(s, pal)), r.cur ? curEl(r.cur, pal) : null);
    if (old) old.replaceWith(row);
    else box.append(row);
  }
  while (box.children.length > lay.rows.length) box.children[box.children.length - 1].remove();
  box.style.setProperty('--cols', String(Math.max(1, lay.width)));
  box.style.setProperty('--rows', String(lay.rows.length));
  return { rows: lay.rows.length, width: lay.width };
}

/** Forgets what `paint` kept (a theme change draws every row again). */
export function unpaint(box) {
  for (const r of [...box.children]) r.remove();
}

/** The width of one column at the font now on `box`, measured in the face itself (a long run of zeros); null when
 *  the page has no layout yet. */
export function measure(box) {
  const probe = h('span', { class: 'tm-probe', 'aria-hidden': 'true', text: '0'.repeat(64) });
  box.append(probe);
  const w = probe.getBoundingClientRect().width;
  probe.remove();
  return w > 0 ? w / 64 : null;
}

/** Puts the font size, the column width and the row height on `box` (CSS reads them as --cw and --ch). */
export function setMetrics(box, { fontPx, cw, ch }) {
  box.style.setProperty('--fs', pxs(fontPx));
  box.style.setProperty('--cw', pxs(cw));
  box.style.setProperty('--ch', pxs(ch));
}
