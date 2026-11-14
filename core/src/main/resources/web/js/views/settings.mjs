// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-1, package P7 (INTERFACES.md W-11, W-12): this device's own looks — the theme (follow the system, dark,
// light, or "glass" in the faces the glasses use, served by the PC), the text size and the density — kept in this
// browser's local storage, never on the PC (CONTRACT.md §2.6: per interface). And this device as the PC knows it.

import { h } from '../dom.mjs';

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

export function settingsView(ctx) {
  return {
    mount() {
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
      return h('section', { class: 'page settings' },
        h('h1', { text: 'Settings on this device' }),
        h('p', { class: 'note', text: 'These stay on this browser. The glasses’ settings are on the glasses’ Settings window.' }),
        groups, h('h2', { text: 'This device' }), me);
    },
  };
}
