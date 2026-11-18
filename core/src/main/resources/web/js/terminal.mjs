// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-3, piece B17's `terminal.mjs` (the lean spec's B10–B12): the Tmux page's terminal at a desk's width — the
// terminal emulator the PC serves (`@xterm/xterm`, MIT, from the PC's class path under `/vendor/xterm/`, B12; never a
// copy in this tree) behind the session view's surface seam (`views/tmux.mjs` `surfaceFor`). It writes what the PC's
// control-mode stream of the session sends (`init`: the pane as captured with its cursor; `data`: the pane's output),
// hands every byte the emulator produces for his keyboard and mouse to the view as a `raw` op (pipelined: one op per
// burst, in order), sends a paste through the view's paste rule (a line break asks), and scales its font so the pane's
// columns fit the view — it never asks tmux for a size, so his own client's size never changes. Scrollback holds 5,000
// lines of what streamed since it attached. A library that cannot be loaded is said with its reason, and the view
// draws the PC's colour pictures instead.

import { h } from './dom.mjs';
import { PALETTES, GLASS_FLOOR, themeOf } from './term-render.mjs';

/** B12's route (exactly these names). */
export const LIB = '/vendor/xterm/xterm.mjs';
export const SHEET = '/vendor/xterm/xterm.css';
export const SCROLLBACK = 5000;
/** The font never goes below this; below it the terminal scrolls sideways (JT1's floor). */
export const MIN_FONT_PX = 9;
const FACE = "'DejaVu Sans Mono', 'Liberation Mono', Menlo, Consolas, monospace";

/** How the library is fetched: a dynamic import of B12's route (the node tests and the stand-in hand in a fake). */
export const lib = { load: (url) => import(url) };

let loading = null;
let loadingBy = null;

/** The library's module, once per page (a failed load is tried again at the next session opened). */
export function loadLibrary() {
  if (loading && loadingBy === lib.load) return loading;
  loadingBy = lib.load;
  loading = Promise.resolve().then(() => lib.load(LIB)).then((m) => {
    if (!m || typeof m.Terminal !== 'function') throw new Error(`${LIB} has no Terminal`);
    return m;
  });
  loading.catch(() => { loading = null; });
  return loading;
}

let sheet = null;
/** The library's style sheet, put into the page once; resolves when it loaded or failed (the terminal draws either way,
 *  and a failure is said). */
function loadSheet() {
  if (sheet) return sheet;
  const head = document.head || document.documentElement;
  if (!head || typeof head.append !== 'function') return (sheet = Promise.resolve(null));
  const link = h('link', { rel: 'stylesheet', href: SHEET });
  sheet = new Promise((ok) => {
    link.addEventListener('load', () => ok(null));
    link.addEventListener('error', () => ok(`${SHEET} did not load`));
  });
  head.append(link);
  return sheet;
}

/** Base64 (B11: "stream bytes as base64 in `data`") to the bytes the emulator takes. */
export function bytesOf(b64) {
  const bin = atob(String(b64 || ''));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/**
 * What an `init` writes after a reset: the pane as captured (`capture-pane -p -e`, B10: its lines with their colour
 * escapes, joined by line breaks) with each break as CR LF, a full-screen program's pane in the alternate screen, and
 * the cursor put where tmux has it (hidden when the program hid it). ASSUMED shape (B10 names the commands, not the
 * message): `init = {cols, rows, cursor: {x, y, visible?}, alternate, data: base64 of the capture}` (`visible` from
 * `#{cursor_flag}`, absent = shown). Returns a string of escapes and the capture's bytes.
 */
export function initWrites(init) {
  const cap = new TextDecoder().decode(bytesOf(init.data)).replace(/\r?\n$/, '').replace(/\r?\n/g, '\r\n');
  const c = init.cursor || {};
  const x = Math.max(0, c.x | 0);
  const y = Math.max(0, c.y | 0);
  return `${init.alternate ? '\x1b[?1049h' : ''}\x1b[H${cap}\x1b[${y + 1};${x + 1}H${c.visible === false ? '\x1b[?25l' : ''}`;
}

/** The emulator's 16 colours from the page's theme (the grid's palettes: light, dark; glass maps each to a level of
 *  the glasses' green by its luminance, at least GLASS_FLOOR levels up, as the grid does for these 16). */
export function themeColours(name) {
  const pal = PALETTES[name] || PALETTES.dark;
  const hex = (c) => `#${c.map((v) => Math.round(v).toString(16).padStart(2, '0')).join('')}`;
  const green = (c) => {
    const l = Math.max(GLASS_FLOOR, Math.round(((c[0] * 299 + c[1] * 587 + c[2] * 114) / 1000) * 15 / 255));
    return pal.fg.map((v) => (v * l) / 15);
  };
  const ink = (c) => hex(pal.green ? green(c) : c);
  const names = ['black', 'red', 'green', 'yellow', 'blue', 'magenta', 'cyan', 'white'];
  const t = { foreground: hex(pal.fg), background: hex(pal.bg), cursor: hex(pal.fg), cursorAccent: hex(pal.bg) };
  names.forEach((n, i) => {
    t[n] = i === 0 ? hex(pal.ansi[0]) : ink(pal.ansi[i]);
    t[`bright${n[0].toUpperCase()}${n.slice(1)}`] = ink(pal.ansi[i + 8]);
  });
  return t;
}

/** The font size that fits `cols` columns into `avail` px, a column being `ratio` × the font size wide; never above
 *  the page's own text size, never below MIN_FONT_PX. */
export function fitFont(avail, cols, ratio, maxPx) {
  if (!(avail > 0) || !(cols > 0) || !(ratio > 0)) return maxPx;
  return Math.max(MIN_FONT_PX, Math.min(maxPx, Math.floor((avail / (cols * ratio)) * 10) / 10));
}

/**
 * The terminal surface: `{feed: 'stream', el, take(msg), refit(), retheme(), focus(), focused(), hasSelection(),
 * destroy()}`. `onReady()` once the library runs (the view then subscribes the stream), `onFail(reason)` when it cannot
 * be loaded, `onBytes(text)` for what the emulator produced, `onPaste(text)` for a paste, `onFocus(on)`.
 */
export function terminalSurface({ label, onBytes, onPaste, onFocus, onReady, onFail }) {
  const host = h('div', { class: 'tm-xhost' });
  const el = h('div', { class: 'tm-term tm-xterm', role: 'group', 'aria-label': label }, host,
    h('p', { class: 'note tm-xwait', text: 'Loading the terminal from the PC…' }));
  let term = null;
  let gone = false;
  let focused = false;
  let size = { cols: 80, rows: 24 };
  let ratio = null;
  const theme = () => themeOf(document.documentElement, typeof window.matchMedia === 'function' ? window.matchMedia.bind(window) : null);
  const rootPx = () => {
    const v = typeof getComputedStyle === 'function' ? parseFloat(getComputedStyle(document.documentElement).fontSize) : NaN;
    return Number.isFinite(v) && v > 0 ? v : 17;
  };

  // a paste goes through the view's rule, never straight into the emulator (capture: before its own field hears it)
  el.addEventListener('paste', (e) => {
    e.preventDefault();
    if (e.stopPropagation) e.stopPropagation();
    onPaste(e.clipboardData ? e.clipboardData.getData('text/plain') : '');
  }, true);
  // Escape and Backspace typed here are the session's, never the page's Back
  el.addEventListener('keydown', (e) => { if (e.stopPropagation) e.stopPropagation(); });
  el.addEventListener('focusin', () => { focused = true; el.classList.add('typing'); onFocus(true); });
  el.addEventListener('focusout', () => { focused = false; el.classList.remove('typing'); onFocus(false); });

  /** A column's width per px of font in the face, measured once (null before the page has a layout). */
  function measureRatio() {
    if (ratio) return ratio;
    const probe = h('span', { class: 'tm-probe', 'aria-hidden': 'true', text: '0'.repeat(64) });
    probe.style.fontFamily = FACE;
    probe.style.fontSize = '100px';
    el.append(probe);
    const w = probe.getBoundingClientRect().width;
    probe.remove();
    ratio = w > 0 ? w / 6400 : null;
    return ratio;
  }

  function refit() {
    if (!term) return;
    const p = el.parentElement;
    const avail = p && p.clientWidth ? p.clientWidth - 16 : 0;
    const px = fitFont(avail, size.cols, measureRatio() || 0.6, Math.round(rootPx() * 0.9));
    if (term.options.fontSize !== px) term.options.fontSize = px;
    if (term.cols !== size.cols || term.rows !== size.rows) term.resize(size.cols, size.rows);
  }

  Promise.all([loadLibrary(), loadSheet()]).then(([mod, sheetWhy]) => {
    if (gone) return;
    term = new mod.Terminal({ cols: size.cols, rows: size.rows, scrollback: SCROLLBACK, cursorBlink: false, fontFamily: FACE,
      fontSize: Math.round(rootPx() * 0.9), theme: themeColours(theme()), allowTransparency: false });
    term.open(host);
    // S1-3 fix A2: tmux is the pane's real terminal and answers the program's queries itself; the library would answer
    // them again through the path his keys take. Its replies are swallowed in its parser (device attributes, status and
    // cursor reports, mode and setting requests, colour queries), and a focus report (sent once a program asks for
    // them) is dropped, so only his input becomes a `raw` op
    const p = term.parser;
    for (const id of [{ final: 'c' }, { prefix: '>', final: 'c' }, { final: 'n' }, { prefix: '?', final: 'n' },
      { intermediates: '$', final: 'p' }, { prefix: '?', intermediates: '$', final: 'p' }]) p.registerCsiHandler(id, () => true);
    p.registerDcsHandler({ intermediates: '$', final: 'q' }, () => true);
    for (const n of [4, 10, 11, 12]) p.registerOscHandler(n, (d) => d.includes('?'));
    // his keys and mouse: what the emulator produced, one `raw` op per burst, in order
    const send = (d) => { if (d !== '\x1b[I' && d !== '\x1b[O') onBytes(d); };
    term.onData(send);
    if (typeof term.onBinary === 'function') term.onBinary(send);
    term.attachCustomKeyEventHandler((e) => {
      if (e.type !== 'keydown') return true;
      // Shift+Esc lets the keyboard go (the page's own keys work again); Esc alone is the session's
      if (e.key === 'Escape' && e.shiftKey) {
        term.blur();
        return false;
      }
      return true;
    });
    const wait = el.querySelector('.tm-xwait');
    if (wait) {
      if (sheetWhy) wait.textContent = `The terminal's style sheet did not load (${sheetWhy}); it may look wrong.`;
      else wait.remove();
    }
    refit();
    onReady();
  }, (err) => {
    if (gone) return;
    onFail(`the PC did not serve the terminal library (${(err && err.message) || err})`);
  });

  return {
    feed: 'stream',
    el,
    /** One `live` message of the stream: `init` resets and draws the pane, `data` is its output. */
    take(m) {
      if (!term) return;
      if (m.init) {
        size = { cols: Math.max(2, m.init.cols | 0 || 80), rows: Math.max(2, m.init.rows | 0 || 24) };
        term.reset();
        refit();
        term.write(initWrites(m.init));
      } else if (m.data !== undefined) term.write(bytesOf(m.data));
    },
    refit,
    retheme() { if (term) term.options.theme = themeColours(theme()); },
    focus() { if (term) term.focus(); },
    focused: () => focused,
    hasSelection: () => !!(term && term.hasSelection()),
    ready: () => !!term,
    destroy() {
      gone = true;
      if (term) term.dispose();
      term = null;
      el.remove();
    },
  };
}
