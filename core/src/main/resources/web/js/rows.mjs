// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-5, stage W (the lean spec's §6 row W): a settings row as every page draws it — the sections of Files, Tmux,
// Torrents and Feed, and the Settings page — from a description's row (`{id, label, scope, type, options, value,
// level}`; `from`/`to`/`step` on a number). Each page keeps its own box, notes and what a sent value does.

import { h } from './dom.mjs';
import { atLeast } from './proto.mjs';

/** A row's options as [id, label] (the description's `{id, label}`, or a bare value). */
export const optionsOf = (row) => (row.options || []).map((o) => (o && typeof o === 'object' ? [o.id, o.label ?? String(o.id)] : [o, String(o)]));

/** A row's value: this page's own on its way (`pend` by row id: a `Pending`, or a plain object), else the record's
 *  (`rec`, the window's copy), else the description's. */
export function rowValue(window, row, rec, pend) {
  const keyed = pend && typeof pend.has === 'function';
  if (pend && (keyed ? pend.has(row.id) : Object.prototype.hasOwnProperty.call(pend, row.id))) return keyed ? pend.get(row.id) : pend[row.id];
  const v = rec && rec.value(window, row.id);
  return v === undefined ? row.value : v;
}

/** The op of a row's value (register; L1 C1: the latest input wins): the row's own action if the description names
 *  one, else `set`, on the row's item. */
export const setDraft = (window, row, value) => ({ window, action: row.action || 'set', targets: [row.item ?? row.id], result: value });

const same = (a, b) => String(a) === String(b);

/** A value in the row's words. */
export function words(row, v) {
  if (row.type === 'bool') return v ? 'on' : 'off';
  if (row.type === 'set') {
    const on = optionsOf(row).filter(([id]) => (Array.isArray(v) ? v : []).some((x) => same(x, id)));
    return on.length ? on.map(([, l]) => l).join(', ') : 'none';
  }
  return (optionsOf(row).find(([id]) => same(id, v)) || [v, String(v)])[1];
}

/** A row's control: a checkbox (bool), a checkbox per option (set: the value is the list of those checked, in the
 *  options' order), a slider with its value (number), else a select. `send(value)` takes a new value; `why` dims it
 *  with the reason (it stays focusable; the send says why). */
export function control(row, v, { key, why, send }) {
  const dim = why ? 'dim' : null;
  if (row.type === 'bool') {
    return h('input', { type: 'checkbox', checked: !!v, 'data-key': key, focus: true, class: dim, 'data-why': why, on: { change: (e) => send(!!e.target.checked) } });
  }
  if (row.type === 'set') {
    const now = Array.isArray(v) ? v : [];
    return h('span', { class: 'set-many' + (dim ? ' dim' : ''), role: 'group', 'aria-label': row.label, 'data-why': why },
      optionsOf(row).map(([id, label]) => h('label', { class: 'choice' },
        h('input', { type: 'checkbox', checked: now.some((x) => same(x, id)), 'data-key': `${key}:${id}`, focus: true, on: { change: (e) => {
          const want = (oid) => (same(oid, id) ? e.target.checked : now.some((x) => same(x, oid)));
          send(optionsOf(row).map(([oid]) => oid).filter(want));
        } } }),
        h('span', { text: label }))));
  }
  if (row.type === 'number') {
    const out = h('output', { class: 'set-num', text: String(v) });
    return h('span', { class: 'set-range' },
      h('input', { type: 'range', min: row.from, max: row.to, step: row.step || 1, value: v, 'data-key': key, focus: true, class: dim, 'data-why': why,
        'aria-label': row.label, on: { input: (e) => { out.textContent = e.target.value; }, change: (e) => send(Number(e.target.value)) } }), out);
  }
  // a select's value is text: the option's own id goes back (Size's 288 stays a number)
  return h('select', { 'data-key': key, focus: true, class: dim, 'data-why': why, on: { change: (e) => {
    const o = optionsOf(row).find(([id]) => String(id) === e.target.value);
    send(o ? o[0] : e.target.value);
  } } }, optionsOf(row).map(([id, l]) => h('option', { value: id, selected: same(id, v) }, l)));
}

/**
 * One row: below the level it needs (`need`, at this device's `level`), its value in words and why, dim; else its
 * label and control, then `note` (a scope's words) when given. `key` names its control (default `set:<id>`).
 */
export function settingRow(row, v, { level, need, why, send, note, key = `set:${row.id}` }) {
  const label = h('span', { text: row.label });
  if (!atLeast(level, need)) return h('div', { class: 'trow dim' }, label, h('span', { text: words(row, v) }), h('span', { class: 'note', text: `changing it needs level ${need}` }));
  return h(row.type === 'set' ? 'div' : 'label', { class: 'trow' }, label, control(row, v, { key, why, send }), note ? h('span', { class: 'note', text: note }) : null);
}
