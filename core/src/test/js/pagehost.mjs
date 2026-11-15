// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-2, package Q1: the real page shell (`app.mjs`) loaded under `node --test` over a small DOM stand-in, a
// socket the test drives, timers it fires by hand and a local storage that tells the other tabs of a change as a
// browser does (only when the value changed). No browser, no network. Each `openPage` imports a fresh copy of
// app.mjs (its module state is per page); a test runs its pages one at a time. Layout is not modelled.

class Node {}
class Text extends Node {
  constructor(t) {
    super();
    Object.assign(this, { data: t, nodeType: 3, parentElement: null });
  }
  get textContent() { return this.data; }
  remove() { if (this.parentElement) this.parentElement.drop(this); }
}

const camel = (s) => s.replace(/-([a-z])/g, (_, c) => c.toUpperCase());

class El extends Node {
  constructor(tag, doc) {
    super();
    Object.assign(this, { tagName: tag.toUpperCase(), doc, children: [], attrs: {}, dataset: {}, style: {}, on: {}, parentElement: null,
      className: '', hidden: false, scrollTop: 0, nodeType: 1, offsetParent: {} });
    this.classList = {
      contains: (c) => this.className.split(' ').includes(c),
      add: (c) => { if (!this.classList.contains(c)) this.className = `${this.className} ${c}`.trim(); },
      remove: (c) => { this.className = this.className.split(' ').filter((x) => x && x !== c).join(' '); },
      toggle: (c, on = !this.classList.contains(c)) => { if (on) this.classList.add(c); else this.classList.remove(c); return on; },
    };
  }
  setAttribute(k, v) { if (k.startsWith('data-')) this.dataset[camel(k.slice(5))] = String(v); else this.attrs[k] = String(v); }
  getAttribute(k) { const v = k.startsWith('data-') ? this.dataset[camel(k.slice(5))] : this.attrs[k]; return v === undefined ? null : v; }
  removeAttribute(k) { delete this.attrs[k]; }
  addEventListener(ev, fn) { (this.on[ev] ||= []).push(fn); }
  removeEventListener(ev, fn) { this.on[ev] = (this.on[ev] || []).filter((f) => f !== fn); }
  /** Runs the listeners of `ev` as a browser would. */
  fire(ev, e = {}) { for (const fn of this.on[ev] || []) fn({ preventDefault() {}, target: this, ...e }); }
  /** As the DOM: a child that is not a node is written as its text, so `null` shows as "null". */
  append(...kids) {
    for (const k of kids) {
      const n = k instanceof Node ? k : new Text(String(k));
      if (n.parentElement) n.parentElement.drop(n);
      n.parentElement = this;
      this.children.push(n);
    }
  }
  replaceChildren(...kids) { for (const c of this.children) c.parentElement = null; this.children = []; this.append(...kids); }
  drop(n) { this.children = this.children.filter((c) => c !== n); n.parentElement = null; }
  remove() { if (this.parentElement) this.parentElement.drop(this); }
  replaceWith(n) { const p = this.parentElement; if (!p) return; const i = p.children.indexOf(this); p.drop(this); p.children.splice(i, 0, n); n.parentElement = p; }
  get firstChild() { return this.children[0] || null; }
  get textContent() { return this.children.map((c) => c.textContent).join(''); }
  set textContent(t) { this.replaceChildren(String(t)); }
  contains(n) { for (let x = n; x; x = x.parentElement) if (x === this) return true; return false; }
  focus() { this.doc.activeElement = this; }
  scrollIntoView() {}
  getBoundingClientRect() { return { top: 0, left: 0, bottom: 0, right: 0, width: 0, height: 0 }; }
  /** Every element under this one matching one simple selector: `tag`, `.class`, `tag.class` (S1-2 Q4w: the confirm's
   *  `button.rest`) or `[attr]` (anything else throws). */
  querySelectorAll(sel) {
    let test;
    const tc = /^([a-z]+)\.([\w-]+)$/.exec(sel);
    if (tc) test = (e) => e.tagName === tc[1].toUpperCase() && e.classList.contains(tc[2]);
    else if (/^\.[\w-]+$/.test(sel)) test = (e) => e.classList.contains(sel.slice(1));
    else if (/^\[[\w-]+\]$/.test(sel)) test = (e) => e.getAttribute(sel.slice(1, -1)) !== null;
    else if (/^[a-z]+$/.test(sel)) test = (e) => e.tagName === sel.toUpperCase();
    else throw new Error(`the DOM stand-in reads no selector '${sel}'`);
    const out = [];
    const walk = (e) => { for (const c of e.children) if (c instanceof El) { if (test(c)) out.push(c); walk(c); } };
    walk(this);
    return out;
  }
  querySelector(sel) { return this.querySelectorAll(sel)[0] || null; }
}

function makeDocument() {
  const doc = { activeElement: null, visibilityState: 'visible', on: {} };
  doc.createElement = (t) => new El(t, doc);
  doc.createElementNS = (_, t) => new El(t, doc);
  doc.createTextNode = (t) => new Text(t);
  doc.addEventListener = (ev, fn) => { (doc.on[ev] ||= []).push(fn); };
  doc.removeEventListener = () => {};
  doc.documentElement = new El('html', doc);
  doc.body = new El('body', doc);
  const ids = { app: 'div', reach: 'div', rail: 'nav', main: 'main', toasts: 'div', confirm: 'dialog' };
  doc.byId = Object.fromEntries(Object.entries(ids).map(([id, t]) => [id, new El(t, doc)]));
  doc.getElementById = (id) => doc.byId[id] || null;
  // S1-2 Q4w: the confirm shape opens and closes its dialog
  Object.assign(doc.byId.confirm, { open: false, showModal() { this.open = true; }, close() { this.open = false; } });
  return doc;
}

/** A local storage shared by the tabs of one browser: a change of value tells every other tab (`storage`). */
export function browserStorage() {
  const m = new Map();
  const tabs = [];
  return {
    m,
    forTab(win) {
      tabs.push(win);
      return {
        getItem: (k) => (m.has(k) ? m.get(k) : null),
        removeItem: (k) => m.delete(k),
        setItem: (k, v) => {
          const old = m.has(k) ? m.get(k) : null;
          m.set(k, String(v));
          if (old !== String(v)) for (const w of tabs) if (w !== win) w.fire('storage', { key: k, oldValue: old, newValue: String(v) });
        },
      };
    },
  };
}

/** Timers fired by hand: `fire(ms)` runs every live timeout of that delay (all when omitted), oldest first. */
export function handTimers() {
  const t = { timeouts: [], intervals: [] };
  t.setTimeout = (fn, ms) => t.timeouts.push({ fn, ms, live: true });
  t.clearTimeout = (i) => { if (t.timeouts[i - 1]) t.timeouts[i - 1].live = false; };
  t.setInterval = (fn, ms) => t.intervals.push({ fn, ms, live: true });
  t.clearInterval = (i) => { if (t.intervals[i - 1]) t.intervals[i - 1].live = false; };
  /** The ping pacing: runs every live interval once. */
  t.tick = () => { for (const x of t.intervals) if (x.live) x.fn(); };
  t.fire = (ms) => {
    for (const x of t.timeouts.filter((y) => y.live && (ms === undefined || y.ms === ms))) {
      x.live = false;
      x.fn();
    }
  };
  return t;
}

let pages = 0;
const NAMES = ['Node', 'document', 'window', 'location', 'history', 'WebSocket', 'fetch', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'];

/**
 * Opens a page at `path` over `storage` (from browserStorage) with the timers `timers` (from handTimers) and the
 * answers `fetchAnswer(url, init)` for fetch. Returns {doc, win, sockets, said(), sent(i)}. `restore()` puts node's own
 * globals back; call it when the test ends.
 */
export async function openPage({ path = '/', storage, timers, fetchAnswer }) {
  const doc = makeDocument();
  const win = Object.assign(new El('window', doc), { matchMedia: () => ({ matches: false }) });
  win.localStorage = storage.forTab(win);
  const sockets = [];
  class FakeWS {
    constructor(url) {
      Object.assign(this, { url, readyState: 0, sent: [] });
      sockets.push(this);
    }
    send(t) { this.sent.push(JSON.parse(t)); }
    close() { if (this.readyState === 3) return; this.readyState = 3; if (this.onclose) this.onclose(); }
    open() { this.readyState = 1; this.onopen(); }
    frame(f) { this.onmessage({ data: JSON.stringify(f) }); }
  }
  globalThis.location = { protocol: 'http:', host: 'pc.test', pathname: path, reload() { win.reloaded = true; } };
  globalThis.history = { pushState: (_, __, p) => { globalThis.location.pathname = p; }, replaceState: (_, __, p) => { globalThis.location.pathname = p; } };
  Object.assign(globalThis, { document: doc, window: win, WebSocket: FakeWS, Node, fetch: async (u, i) => fetchAnswer(u, i) });
  for (const k of ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval']) globalThis[k] = timers[k];
  await import(`../../main/resources/web/js/app.mjs?page=${++pages}`);
  return {
    doc, win, sockets,
    /** The toasts' words, oldest first. */
    said: () => doc.byId.toasts.children.map((t) => t.textContent),
    /** Where the page is now (another page of the same test may have moved the shared location). */
    go(p) { globalThis.location.pathname = p; win.fire('popstate'); },
  };
}

const kept = Object.fromEntries(NAMES.map((k) => [k, globalThis[k]]));
export function restore() {
  for (const k of NAMES) {
    if (kept[k] === undefined) delete globalThis[k];
    else globalThis[k] = kept[k];
  }
}

/** Lets the page's awaits (a fetch, its answer's body) run. */
export const settle = () => new Promise((ok) => setImmediate(ok));
