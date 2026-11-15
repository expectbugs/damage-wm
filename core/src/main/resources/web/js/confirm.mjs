// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-1, package P7 (INTERFACES.md W-18; CONTRACT.md §4.3): one confirm shape for every irreversible action —
// Cancel is the rest (focused when it opens, first in the row) and the act is never at a rest position; a double
// confirm where the glasses have one asks a second time, Cancel again at rest. Escape cancels.

import { h, clear } from './dom.mjs';

/** Shows the confirm in `dialog`; resolves true only when the act is chosen (twice for a double). */
export function confirmShape(dialog, { title, body, act, cancel = 'Cancel', double = false }) {
  return new Promise((resolve) => {
    let step = 0;
    const done = (yes) => {
      dialog.removeEventListener('cancel', onCancel);
      if (dialog.open) dialog.close();
      resolve(yes);
    };
    const onCancel = (e) => {
      e.preventDefault();
      done(false);
    };
    const draw = () => {
      const no = h('button', { class: 'rest', on: { click: () => done(false) } }, cancel);
      const yes = h('button', { class: 'act danger', on: { click: () => {
        if (double && step === 0) {
          step = 1;
          draw();
        } else done(true);
      } } }, step === 0 ? act : `${act}: yes, really`);
      clear(dialog).append(
        h('h2', { text: title }),
        h('p', { text: step === 0 ? body : `${body} This is the second ask; it cannot be undone.` }),
        h('div', { class: 'row' }, no, h('span', { class: 'spacer' }), yes),
      );
      no.focus();
    };
    dialog.addEventListener('cancel', onCancel);
    draw();
    if (!dialog.open) dialog.showModal();
    dialog.querySelector('button.rest').focus();
  });
}

/** S1-2 Q5w: a choice between acts (an upload whose name is taken: another name, or Replace). Cancel is the rest,
 *  first and focused; a `danger` choice is marked. Resolves the choice's id, or null for Cancel and Escape. */
export function chooseShape(dialog, { title, body, choices, cancel = 'Cancel' }) {
  return new Promise((resolve) => {
    const done = (v) => {
      dialog.removeEventListener('cancel', onCancel);
      if (dialog.open) dialog.close();
      resolve(v);
    };
    const onCancel = (e) => {
      e.preventDefault();
      done(null);
    };
    const no = h('button', { class: 'rest', on: { click: () => done(null) } }, cancel);
    clear(dialog).append(h('h2', { text: title }), h('p', { text: body }),
      h('div', { class: 'row wrap' }, no, h('span', { class: 'spacer' }),
        choices.map((c) => h('button', { class: 'act' + (c.danger ? ' danger' : ''), 'data-choice': c.id, on: { click: () => done(c.id) } }, c.label))));
    dialog.addEventListener('cancel', onCancel);
    if (!dialog.open) dialog.showModal();
    no.focus();
  });
}

/** S1-2 Q5w: a name typed behind Apply (C§4.1: a text parameter is always staged behind the action's confirm).
 *  `check(text)` returns why it cannot be taken, shown in place, or null. Resolves the text, or null for Cancel. */
export function askText(dialog, { title, label, initial = '', act, cancel = 'Cancel', check }) {
  return new Promise((resolve) => {
    const done = (v) => {
      dialog.removeEventListener('cancel', onCancel);
      if (dialog.open) dialog.close();
      resolve(v);
    };
    const onCancel = (e) => {
      e.preventDefault();
      done(null);
    };
    const input = h('input', { type: 'text', class: 'ask-in', 'aria-label': label, spellcheck: 'false' });
    input.value = initial;
    const why = h('p', { class: 'ask-why', role: 'alert' });
    const apply = () => {
      const v = String(input.value ?? '').trim();
      const bad = check ? check(v) : null;
      if (bad) why.textContent = bad;
      else done(v);
    };
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        apply();
      }
    });
    clear(dialog).append(h('h2', { text: title }), h('label', { class: 'ask' }, h('span', { class: 'note', text: label }), input), why,
      h('div', { class: 'row' }, h('button', { class: 'rest', on: { click: () => done(null) } }, cancel), h('span', { class: 'spacer' }),
        h('button', { class: 'act', on: { click: apply } }, act)));
    dialog.addEventListener('cancel', onCancel);
    if (!dialog.open) dialog.showModal();
    input.focus();
    // the name without its extension is selected, as a file manager does
    const dot = initial.lastIndexOf('.');
    if (typeof input.setSelectionRange === 'function') input.setSelectionRange(0, dot > 0 ? dot : initial.length);
  });
}
