// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-1, package P7 (INTERFACES.md W-22; CONTRACT.md §10.3, §10.8): the devices page — approved devices with
// their levels, rename and revoke; waiting pages with their code, approved by picking a level (no typing), or denied.
// Every action is level full and never queued (`conn`, `k`); the PC checks the level at apply time.

import { h, keepFocus } from '../dom.mjs';
import { hhmm } from '../proto.mjs';

const LEVELS = ['view', 'control', 'full'];

function when(ms) {
  if (!ms) return 'never';
  const d = new Date(ms);
  return `${d.getMonth() + 1}-${d.getDate()} ${hhmm(ms)}`;
}

export function devicesView(ctx) {
  let root = null;
  let renaming = null;
  // ruling 2.3 (6): what he picked and what he is typing outlive a redraw (any table event redraws the page)
  const picked = new Map(); // a waiting page's code → the level picked for it
  let draft = null; // the rename being typed, or null

  function act(action, params, done) {
    ctx.sendConn({ window: 'devices', action, params }, (a) => {
      if (a.a === 'refused') ctx.say(a.reason, { from: 'Devices' });
      else if (done) done();
    });
  }

  function draw() {
    const d = ctx.desc('devices');
    const why = ctx.levelWhy('full');
    if (why) {
      root.replaceChildren(h('h1', { text: 'Devices' }), h('p', { class: 'note', text: `The device table ${why}.` }));
      return;
    }
    if (!d) {
      root.replaceChildren(h('h1', { text: 'Devices' }), h('p', { class: 'note', text: 'Reading the device table…' }));
      return;
    }
    for (const c of [...picked.keys()]) if (!d.pending.some((p) => p.code === c)) picked.delete(c);
    const pending = d.pending.length === 0 ? h('p', { class: 'note', text: 'No page is waiting for approval.' })
      : h('ul', { class: 'rows' }, d.pending.map((p) => {
        const level = picked.get(p.code) || 'view';
        const pick = h('select', { 'aria-label': `level for ${p.code}`, 'data-key': `pick:${p.code}`, focus: true,
          on: { change: (e) => picked.set(p.code, e.target.value) } },
          LEVELS.map((l) => h('option', { value: l, selected: l === level }, l)));
        return h('li', { class: 'row-item' },
          h('div', { class: 'row-main' },
            h('span', { class: 'pcode', text: p.code }),
            h('span', { text: `${p.name} · ${p.tailnetName || p.address} · waiting since ${hhmm(p.since)}` }),
            p.replaces ? h('span', { class: 'note', text: `it names the listed device ${p.replaces}; approving replaces that row` }) : null,
            p.approved ? h('span', { class: 'note', text: `approved at ${p.approved}; the page is taking it` }) : null),
          h('div', { class: 'row-acts' }, pick,
            ctx.actionButton({ label: 'Approve', need: 'full', onClick: () => act('approve', { code: p.code, level: pick.value }) }),
            ctx.actionButton({ label: 'Deny', need: 'full', onClick: async () => {
              if (await ctx.confirm({ title: `Deny ${p.code}?`, body: `The page on ${p.tailnetName || p.address} is told it was refused.`, act: 'Deny' }))
                act('deny', { code: p.code });
            } })));
      }));
    const rows = h('ul', { class: 'rows' }, d.rows.map((r) => {
      const mine = r.id === ctx.S.device;
      const name = renaming === r.id
        ? h('form', { class: 'rename', on: { submit: (e) => {
          e.preventDefault();
          const v = e.target.elements.name.value;
          act('rename', { id: r.id, name: v }, () => { renaming = null; draft = null; });
        } } }, h('input', { name: 'name', value: draft ?? r.name, 'aria-label': 'new name', 'data-key': 'rename', focus: true,
          on: { input: (e) => { draft = e.target.value; } } }),
          h('button', { type: 'submit', focus: true }, 'Keep'),
          h('button', { type: 'button', focus: true, on: { click: () => { renaming = null; draft = null; draw(); } } }, 'Cancel'))
        : h('span', { class: 'row-name', text: r.name + (mine ? ' (this browser)' : '') });
      return h('li', { class: 'row-item' },
        h('div', { class: 'row-main' }, name,
          h('span', { text: `${r.level} · ${r.kind} · ${r.tailnetName || r.address || 'no address'} · ${r.id}` }),
          h('span', { class: 'note', text: `approved ${when(r.approvedAt)} by ${r.approvedBy} · last seen ${when(r.lastSeen)}` })),
        h('div', { class: 'row-acts' },
          ctx.actionButton({ label: 'Rename', need: 'full', onClick: () => { renaming = r.id; draft = null; draw(); } }),
          ctx.actionButton({ label: 'Revoke', need: 'full', onClick: async () => {
            const body = mine ? 'This is the browser you are using: it stops at once and asks for approval again.'
              : `${r.name} stops at once; its unsent changes are refused.`;
            if (await ctx.confirm({ title: `Revoke ${r.name}?`, body, act: 'Revoke' })) act('revoke', { id: r.id });
          } })));
    }));
    keepFocus(root, 'data-key', () => root.replaceChildren(h('h1', { text: 'Devices' }),
      ...(d.problem ? [h('p', { class: 'note', text: `The device table was set aside at ${when(d.problem.at)}: every browser is approved again (${d.problem.why}).` })] : []),
      h('h2', { text: 'Waiting for approval' }), pending,
      h('p', { class: 'note', text: 'A new browser shows a code. Pick its level and approve it here, or on the PC: desktop --approve CODE LEVEL.' }),
      h('h2', { text: 'Approved' }), d.rows.length ? rows : h('p', { class: 'note', text: 'No device is approved.' })));
  }

  return {
    mount() {
      root = h('section', { class: 'page devices' });
      draw();
      return root;
    },
    onDesc(scope) {
      if (scope === 'devices') draw();
    },
    onWelcome: () => draw(),
    // S1-2 Q1 (N7): every dim reads the reach; out of reach and back, the buttons are drawn again with it
    onReach: () => draw(),
  };
}
