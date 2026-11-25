// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-1, package P7 (INTERFACES.md W-11, W-12): this device's own looks — the theme (follow the system, dark,
// light, or "glass" in the faces the glasses use, served by the PC), the text size and the density — kept in this
// browser's local storage, never on the PC (CONTRACT.md §2.6: per interface). And this device as the PC knows it.
// Build S1-5, stage W (the lean spec's §6 row W, §5 "The page"; decisions Part A answer 3): above them, every setting
// as a form from the one description (`desc/settings`: Settings' rows by category, each window's own rows in its
// app's category) — each row's label, type, options, value and default as the description gives them. A change is a
// `set` on the item the row names (an app's style row writes the whole style with its field changed); it shows at
// once and holds until the description shows it, the op is refused, or the description after its answer. A row
// below this device's level is dim and says why; a search narrows the rows by their words and their category's name.

import { h, fill, keepFocus } from '../dom.mjs';
import { answerWords } from '../proto.mjs';
import * as R from '../rows.mjs';

export const LOOKS = {
  theme: { key: 'damage.theme', label: 'Theme', options: [['system', 'Follow the system'], ['dark', 'Dark'], ['light', 'Light'],
    ['glass', 'Glass (the glasses’ faces)']], def: 'system' },
  size: { key: 'damage.size', label: 'Text size', options: [['s', 'Small'], ['m', 'Medium'], ['l', 'Large'], ['xl', 'Extra large']], def: 'm' },
  density: { key: 'damage.density', label: 'Density', options: [['comfortable', 'Comfortable'], ['compact', 'Compact']], def: 'comfortable' },
};

/** Puts the stored looks on the page's root (CSS reads them as data attributes). */
export function applyLooks(store) {
  const root = document.documentElement;
  for (const [name, l] of Object.entries(LOOKS)) {
    const v = store.get(l.key);
    root.dataset[name] = l.options.some(([id]) => id === v) ? v : l.def;
  }
}

export const DESC = 'desc/settings';
const SETTINGS = 'settings';
/** A row's scope in words where it is not the glasses' (the description's tag: C§6 as S1-5 simplified it). */
const SCOPES = { shared: 'every device', phone: 'the phone' };

/** The op of a row's value: an app's style row (`field`) writes the whole style `style` with that field changed into
 *  Settings' item; every other row its own item, in the window it belongs to. */
export function draftOf(row, value, style) {
  if (row.field) return R.setDraft(SETTINGS, row, { ...style, [row.field]: value });
  return R.setDraft(row.window || SETTINGS, row, value);
}

/** The categories with only the rows whose label or category name holds every word of `q` (case aside); a category
 *  left with none goes. */
export function narrow(categories, q) {
  const words = String(q || '').toLowerCase().split(/\s+/).filter(Boolean);
  if (!words.length) return categories;
  return categories.map((c) => ({ ...c, rows: c.rows.filter((r) => {
    const text = `${c.name} ${r.label}`.toLowerCase();
    return words.every((w) => text.includes(w));
  }) })).filter((c) => c.rows.length);
}

const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

export function settingsView(ctx) {
  let root = null;
  let box = null;
  let refused = null;
  let q = '';
  // a value this page sent and the description does not show yet: row key → {v, n, applied}
  const pend = new Map();
  let n = 0;
  const keyOf = (row) => `${row.window || SETTINGS}:${row.id}`;
  const rowsNow = () => ((ctx.desc(DESC) || {}).categories || []).flatMap((c) => c.rows);
  const shown = (row) => (pend.has(keyOf(row)) ? pend.get(keyOf(row)).v : row.value);
  const styleOf = (item) => Object.fromEntries(rowsNow().filter((r) => r.item === item && r.field).map((r) => [r.field, shown(r)]));

  /** A description arrived: a value it shows, or one whose op was answered before it, is no longer this page's. */
  function settle() {
    const rows = new Map(rowsNow().map((r) => [keyOf(r), r]));
    for (const [k, e] of pend) {
      const r = rows.get(k);
      if (!r || e.applied || same(r.value, e.v)) pend.delete(k);
    }
  }

  function send(row, value) {
    const k = keyOf(row);
    const token = ++n;
    pend.set(k, { v: value, n: token, applied: false });
    const sent = ctx.sendQueued(draftOf(row, value, row.field ? styleOf(row.item) : null), (ans) => {
      const words = answerWords(ans);
      if (words) ctx.say(`${row.label}: ${words}`, { from: 'Settings' });
      const e = pend.get(k);
      if (e && e.n === token) {
        const r = rowsNow().find((x) => keyOf(x) === k);
        if (ans.a !== 'seen' || (r && same(r.value, e.v))) pend.delete(k);
        else e.applied = true;
      }
      draw();
    }, { need: row.level || 'control' });
    if (!sent) pend.delete(k);
    draw();
  }

  function row(r) {
    const need = r.level || 'control';
    const v = shown(r);
    const def = r.default !== undefined && r.default !== null && !same(r.default, v) ? `default ${R.words(r, r.default)}` : null;
    const note = [SCOPES[r.scope], def].filter(Boolean).join(' · ') || null;
    return R.settingRow(r, v, { level: ctx.S.level, need, why: ctx.canAct(need), send: (value) => send(r, value), note, key: `set:${keyOf(r)}` });
  }

  function body(d) {
    if (!d) return h('p', { class: 'note', text: refused ? `The settings could not be read from the PC: ${refused}` : 'Reading the settings from the PC…' });
    const cats = narrow(d.categories || [], q);
    if (!cats.length) return h('p', { class: 'note', text: `No setting matches “${q.trim()}”.` });
    return cats.map((c) => h('section', { class: 'set-cat' }, h('h2', { text: c.name }), c.rows.map(row)));
  }

  /** Draws the rows only when what they show changed (a control in use is not replaced). */
  function draw() {
    if (!box) return;
    const d = ctx.desc(DESC);
    const sig = JSON.stringify([d ? d.categories : null, [...pend].map(([k, e]) => [k, e.v]), ctx.S.level, ctx.canAct('control'), ctx.canAct('full'), q, refused]);
    if (box.dataset.sig === sig) return;
    box.dataset.sig = sig;
    keepFocus(box, 'data-key', () => fill(box, body(d)));
  }

  function looks() {
    const groups = Object.entries(LOOKS).map(([name, l]) => {
      const now = document.documentElement.dataset[name];
      return h('fieldset', { class: 'looks' }, h('legend', { text: l.label }),
        l.options.map(([id, label]) => h('label', { class: 'choice' },
          h('input', { type: 'radio', name, value: id, checked: id === now, focus: true, on: { change: () => {
            ctx.store.set(l.key, id);
            applyLooks(ctx.store);
          } } }),
          h('span', { text: label }))));
    });
    const S = ctx.S;
    const me = h('dl', { class: 'facts' },
      h('dt', { text: 'This device' }), h('dd', { text: S.names[S.device] ? `${S.names[S.device]} (${S.device})` : S.device || 'not approved' }),
      h('dt', { text: 'Level' }), h('dd', { text: S.level || '—' }),
      h('dt', { text: 'Its stream of changes' }), h('dd', { text: ctx.stream.id }),
      h('dt', { text: 'Kept in this browser' }), h('dd', { text: ctx.store.failed
        ? `nothing lasting: local storage is not available (${ctx.store.failed})` : 'the looks above and the stream' }));
    return [h('h2', { class: 'set-here', text: 'This browser' }),
      h('p', { class: 'note', text: 'These stay on this browser.' }), groups, h('h2', { text: 'This device' }), me];
  }

  return {
    mount() {
      ctx.sub(DESC);
      box = h('div', { class: 'set-cats' });
      root = h('section', { class: 'page settings' },
        h('h1', { text: 'Settings' }),
        h('p', { class: 'note', text: 'Every setting of the glasses, the phone and the windows, as the PC holds it; a change here reaches them at once. A row applies on the glasses unless it says every device or the phone.' }),
        h('input', { type: 'search', class: 'set-search', placeholder: 'Search settings', 'aria-label': 'Search settings', 'data-key': 'set-search', focus: true,
          on: { input: (e) => { q = e.target.value; draw(); } } }),
        box, looks());
      draw();
      return root;
    },
    unmount() {
      ctx.unsub(DESC);
      root = null;
      box = null;
    },
    onDesc(scope) {
      if (scope !== DESC) return;
      refused = null;
      settle();
      draw();
    },
    onRefused(scope, a) {
      if (scope !== DESC) return false;
      refused = answerWords(a);
      draw();
      return true;
    },
    onReach: draw,
  };
}
