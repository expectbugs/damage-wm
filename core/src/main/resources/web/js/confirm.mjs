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
