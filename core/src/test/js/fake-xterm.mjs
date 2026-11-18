// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-3, piece B17: a small FAKE of the terminal library's surface (the parts `terminal.mjs` calls), for the node
// tests and the stand-in look — never the library itself (that is the PC's, B12). It keeps a screen of cells, takes
// printable text, CR, LF and the cursor's position (CSI row;col H), and draws its rows as plain lines of text (its
// pictures are of the fake, not the library: no colours); a key pressed in its field is handed to `onData` (printable keys,
// Enter, Backspace, Tab, Escape). Like the library it answers a program's terminal queries through `onData` (device
// attributes, status and cursor reports, mode and setting requests, colour queries; a focus report once mode 1004 is
// set) unless a handler registered on its `parser` takes the sequence (S1-3 fix A2). Everything it was written and
// every key it handed on is kept for the tests. No import: the stand-in serves this file alone.

export class Terminal {
  constructor(options = {}) {
    this.options = { ...options };
    this.cols = options.cols || 80;
    this.rows = options.rows || 24;
    this.written = [];
    this.handlers = { data: [], binary: [] };
    this.keyHandler = null;
    this.element = null;
    this.textarea = null;
    this.disposed = false;
    this.selected = false;
    // the parser hooks, newest first as the library asks them; a hook that returns true takes the sequence
    const hooks = { csi: [], dcs: [], osc: [] };
    const reg = (kind) => (id, cb) => { hooks[kind].unshift([id, cb]); return { dispose() {} }; };
    this.parser = { hooks, registerCsiHandler: reg('csi'), registerDcsHandler: reg('dcs'), registerOscHandler: reg('osc') };
    this.clear();
    Terminal.instances.push(this);
  }

  clear() {
    this.screen = Array.from({ length: this.rows }, () => []);
    this.x = 0;
    this.y = 0;
    this.hidden = false;
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
  hasSelection() { return this.selected; }
  clearSelection() { this.selected = false; }
  dispose() { this.disposed = true; if (this.element) this.element.remove(); }

  write(data) {
    const text = typeof data === 'string' ? data : new TextDecoder().decode(data);
    this.written.push(text);
    const re = /\x1b\[([<=>?]?)([0-9;]*)([ -/]*)([@-~])|\x1b\](\d+);([^\x07\x1b]*)(?:\x07|\x1b\\)|\x1bP[0-9;]*([ -/]*)([@-~])[^\x1b]*\x1b\\|\r|\n|[^\x1b\r\n]/gu;
    for (const m of text.matchAll(re)) {
      const t = m[0];
      if (m[4]) this.query('csi', { prefix: m[1], intermediates: m[3], final: m[4] }, m[2]) || this.csi(m[1] + m[2], m[4]);
      else if (m[5]) this.query('osc', Number(m[5]), m[6]);
      else if (m[8]) this.query('dcs', { prefix: '', intermediates: m[7], final: m[8] }, '');
      else if (t === '\r') this.x = 0;
      else if (t === '\n') this.lf();
      else {
        if (this.x >= this.cols) { this.x = 0; this.lf(); }
        this.screen[this.y][this.x++] = { t };
      }
    }
    this.draw();
  }
  /** A query the library answers (as it does, through `onData`), unless a parser hook takes it; true when it was one. */
  query(kind, id, data) {
    const same = (a) => (kind === 'osc' ? a === id : (a.prefix || '') === id.prefix && (a.intermediates || '') === id.intermediates && a.final === id.final);
    const key = kind === 'osc' ? `osc${id}` : `${kind}${id.prefix || ''}${id.intermediates}${id.final}`;
    const replies = { csic: '\x1b[?1;2c', 'csi>c': '\x1b[>0;276;0c', csin: data === '5' ? '\x1b[0n' : `\x1b[${this.y + 1};${this.x + 1}R`,
      'csi?n': `\x1b[?${this.y + 1};${this.x + 1}R`, 'csi$p': `\x1b[${data};2$y`, 'csi?$p': `\x1b[?${data};2$y`, 'dcs$q': '\x1bP1$r0m\x1b\\' };
    for (const n of [4, 10, 11, 12]) replies[`osc${n}`] = String(data).includes('?') ? `\x1b]${n};rgb:0000/0000/0000\x1b\\` : null;
    if (!(key in replies)) {
      if (key === 'csi?h' && data === '1004') this.type('\x1b[O'); // focus reports on: the library reports the focus at once
      return false;
    }
    for (const [a, cb] of this.parser.hooks[kind]) if (same(a) && cb(kind === 'osc' ? data : [Number(data) || 0]) === true) return true;
    if (replies[key]) this.type(replies[key]);
    return true;
  }
  lf() {
    if (this.y < this.rows - 1) this.y++;
    else { this.screen.shift(); this.screen.push([]); }
  }
  csi(p, f) {
    const n = p.replace('?', '').split(';').map((x) => Number(x) || 0);
    if (p === '?25') this.hidden = f === 'l'; // the cursor hidden or shown
    else if (f === 'H') { this.y = Math.min(this.rows - 1, Math.max(0, (n[0] || 1) - 1)); this.x = Math.max(0, (n[1] || 1) - 1); }
  }
  /** The screen as text, rows joined by line breaks (trailing blanks dropped). */
  text() { return this.screen.map((r) => Array.from({ length: r.length }, (_, i) => (r[i] ? r[i].t : ' ')).join('').trimEnd()).join('\n').trimEnd(); }
  draw() {
    if (!this.screenEl) return;
    const doc = this.screenEl.ownerDocument || globalThis.document;
    this.screenEl.replaceChildren(...this.screen.map((r) => {
      const line = doc.createElement('div');
      line.className = 'xterm-row';
      line.append(Object.assign(doc.createElement('span'), { textContent: Array.from({ length: r.length }, (_, i) => (r[i] ? r[i].t : ' ')).join('') || ' ' }));
      return line;
    }));
  }
}
Terminal.instances = [];
