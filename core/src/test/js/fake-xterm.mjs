// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-3, piece B17: a small FAKE of the terminal library's surface (the parts `terminal.mjs` calls), for the node
// tests and the stand-in look — never the library itself (that is the PC's, B12). It keeps a screen of cells, takes
// printable text, CR, LF, the cursor's position (CSI row;col H), a few colours (CSI … m) and the alternate screen,
// and draws its rows as lines of coloured spans; a key pressed in its field is handed to `onData` (printable keys,
// Enter, Backspace, Tab, Escape). Everything it was written and every key it handed on is kept for the tests. No
// import: the stand-in serves this file alone.

const BASE = ['#000000', '#cd3131', '#0dbc79', '#e5e510', '#2472c8', '#bc3fbc', '#11a8cd', '#e5e5e5'];
const NAMES = ['black', 'red', 'green', 'yellow', 'blue', 'magenta', 'cyan', 'white'];

export class Terminal {
  constructor(options = {}) {
    // as the library: a changed option (the theme, the font size) is drawn at once
    this.options = new Proxy({ ...options }, { set: (o, k, v) => { o[k] = v; this.draw(); return true; } });
    this.cols = options.cols || 80;
    this.rows = options.rows || 24;
    this.written = [];
    this.handlers = { data: [], binary: [] };
    this.keyHandler = null;
    this.element = null;
    this.textarea = null;
    this.disposed = false;
    this.clear();
    Terminal.instances.push(this);
  }

  clear() {
    this.screen = Array.from({ length: this.rows }, () => []);
    this.x = 0;
    this.y = 0;
    this.fg = null;
  }

  open(parent) {
    const doc = parent.ownerDocument || globalThis.document;
    this.element = doc.createElement('div');
    this.element.className = 'xterm fake-xterm';
    this.textarea = doc.createElement('textarea');
    this.textarea.className = 'xterm-helper-textarea';
    this.textarea.setAttribute('aria-label', 'Terminal input');
    this.textarea.addEventListener('keydown', (e) => this.key(e));
    this.screenEl = doc.createElement('div');
    this.screenEl.className = 'xterm-screen';
    this.element.append(this.textarea, this.screenEl);
    parent.append(this.element);
    this.draw();
  }

  onData(cb) { this.handlers.data.push(cb); return { dispose() {} }; }
  onBinary(cb) { this.handlers.binary.push(cb); return { dispose() {} }; }
  attachCustomKeyEventHandler(fn) { this.keyHandler = fn; }
  /** What a keyboard would make of one key event (the parts of xterm's table a test needs). */
  key(e) {
    if (this.keyHandler && this.keyHandler(e) === false) return;
    const map = { Enter: '\r', Backspace: '\x7f', Tab: '\t', Escape: '\x1b', ArrowUp: '\x1b[A', ArrowDown: '\x1b[B' };
    const d = map[e.key] || (e.key && e.key.length === 1 ? (e.ctrlKey ? String.fromCharCode(e.key.toLowerCase().charCodeAt(0) - 96) : e.key) : null);
    if (d === null) return;
    if (e.preventDefault) e.preventDefault();
    this.type(d);
  }
  /** Hands `d` on as typed (a test's keyboard). */
  type(d) { for (const cb of this.handlers.data) cb(d); }
  reset() { this.clear(); this.written.push('<reset>'); this.draw(); }
  resize(cols, rows) {
    this.cols = cols;
    this.rows = rows;
    while (this.screen.length < rows) this.screen.push([]);
    this.screen.length = rows;
    this.draw();
  }
  focus() { if (this.textarea) this.textarea.focus(); }
  blur() { if (this.textarea && this.textarea.blur) this.textarea.blur(); }
  hasSelection() { return false; }
  dispose() { this.disposed = true; if (this.element) this.element.remove(); }

  write(data) {
    const text = typeof data === 'string' ? data : new TextDecoder().decode(data);
    this.written.push(text);
    const re = /\x1b\[([?0-9;]*)([A-Za-z])|\r|\n|[^\x1b\r\n]/gu;
    for (const m of text.matchAll(re)) {
      const t = m[0];
      if (m[2]) this.csi(m[1], m[2]);
      else if (t === '\r') this.x = 0;
      else if (t === '\n') this.lf();
      else {
        if (this.x >= this.cols) { this.x = 0; this.lf(); }
        this.screen[this.y][this.x++] = { t, fg: this.fg };
      }
    }
    this.draw();
  }
  lf() {
    if (this.y < this.rows - 1) this.y++;
    else { this.screen.shift(); this.screen.push([]); }
  }
  csi(p, f) {
    const n = p.replace('?', '').split(';').map((x) => Number(x) || 0);
    if (f === 'H') { this.y = Math.min(this.rows - 1, Math.max(0, (n[0] || 1) - 1)); this.x = Math.max(0, (n[1] || 1) - 1); }
    else if (f === 'm') {
      for (let i = 0; i < n.length; i++) {
        const c = n[i];
        if (c === 0 || c === 39) this.fg = null;
        else if (c >= 30 && c <= 37) this.fg = c - 30;
        else if (c >= 90 && c <= 97) this.fg = c - 90;
        else if (c === 38 && n[i + 1] === 2) { this.fg = `rgb(${n[i + 2]},${n[i + 3]},${n[i + 4]})`; i += 4; }
        else if (c === 38 && n[i + 1] === 5) { this.fg = n[i + 2] % 8; i += 2; }
      }
    }
  }
  /** The screen as text, rows joined by line breaks (trailing blanks dropped). */
  text() { return this.screen.map((r) => Array.from({ length: r.length }, (_, i) => (r[i] ? r[i].t : ' ')).join('').trimEnd()).join('\n').trimEnd(); }
  draw() {
    if (!this.screenEl) return;
    const doc = this.screenEl.ownerDocument || globalThis.document;
    const lines = this.screen.map((r, y) => {
      const line = doc.createElement('div');
      line.className = 'xterm-row';
      let span = null;
      let fg;
      const cells = Array.from({ length: Math.max(r.length, y === this.y ? this.x + 1 : 0) }, (_, i) => r[i] || { t: ' ', fg: null });
      cells.forEach((c, x) => {
        const cur = y === this.y && x === this.x;
        if (!span || c.fg !== fg || cur) {
          span = doc.createElement('span');
          // the 8 base colours from the theme the page handed in (as the library takes it), else the fake's own
          const theme = this.options.theme || {};
          if (c.fg !== null && c.fg !== undefined) span.style.color = typeof c.fg === 'number' ? theme[NAMES[c.fg]] || BASE[c.fg] : c.fg;
          if (cur) span.className = 'xterm-cursor';
          fg = cur ? Symbol('cur') : c.fg;
          line.append(span);
        }
        span.textContent += c.t;
      });
      return line;
    });
    this.screenEl.replaceChildren(...lines);
    this.screenEl.style.fontSize = `${this.options.fontSize || 15}px`;
    const theme = this.options.theme || {};
    if (theme.foreground) this.screenEl.style.color = theme.foreground;
    if (theme.background) this.screenEl.style.backgroundColor = theme.background;
  }
}
Terminal.instances = [];
