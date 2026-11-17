// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-1, package P7 (INTERFACES.md W-21): the diagnosis page at level full — the record's epoch and `seq`, the
// live connections, the versions (the PC's build, the contracts, this page's bundle), and the PC log's tail, whole.
// Read when opened and when asked again; nothing here acts.

import { h } from '../dom.mjs';

export function diagView(ctx) {
  let root = null;

  function draw() {
    const why = ctx.levelWhy('full');
    const d = ctx.desc('diag');
    const w = ctx.S.welcome || {};
    const head = [h('h1', { text: 'Diagnosis' }),
      h('button', { focus: true, on: { click: () => { ctx.unsub('diag'); ctx.sub('diag'); } } }, 'Read again')];
    if (why) {
      root.replaceChildren(h('h1', { text: 'Diagnosis' }), h('p', { class: 'note', text: `The diagnosis ${why}.` }));
      return;
    }
    if (!d) {
      root.replaceChildren(...head, h('p', { class: 'note', text: 'Reading the PC’s state…' }));
      return;
    }
    const facts = [
      ['PC build', d.build],
      ['Record', `epoch ${d.epoch} · seq ${d.seq}`],
      ['Contracts', Object.entries(d.contracts || {}).map(([k, v]) => `${k} ${v}`).join(', ')],
      // AC27: the welcome's bundle is its content hash alone
      ['This page', w.bundle ? `lane ${w.proto} · bundle ${w.bundle.hash}` : '—'],
      ['This device', `${ctx.S.device} · ${ctx.S.level} · connection ${ctx.S.conn} · stream ${ctx.stream.id}`],
    ];
    root.replaceChildren(...head,
      h('dl', { class: 'facts' }, facts.map(([k, v]) => [h('dt', { text: k }), h('dd', { text: v ?? '—' })])),
      h('h2', { text: `Connections (${(d.connections || []).length})` }),
      h('ul', { class: 'plain' }, (d.connections || []).map((c) => h('li', { text: c }))),
      h('h2', { text: `The PC log, last ${(d.log || []).length} lines` }),
      h('pre', { class: 'log', tabindex: 0 }, (d.log || []).join('\n')));
  }

  return {
    mount() {
      root = h('section', { class: 'page diag' });
      if (!ctx.levelWhy('full')) ctx.sub('diag');
      draw();
      return root;
    },
    unmount() {
      ctx.unsub('diag');
    },
    onDesc(scope) {
      if (scope === 'diag') draw();
    },
    onWelcome: () => draw(),
  };
}
