// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// R-b stage H3 (`private/build085/RB-lean-spec.md` §2, §4.6, §4.8): the Health page. Every line of the PC's health
// record (`desc/health`, level view; the page shell holds it from every welcome) with its reading, since when, what
// the system is doing about it, and why a line is not current; a line in attention or alarm carries its band word.
// Quiet when all is well: no band word and no colour on a normal line. At level full the diagnosis — the record's
// epoch and seq, the live connections, the versions, the PC log's tail — is the page's lower half (INTERFACES.md
// W-21; the Diagnosis page is merged here). Out of reach the page keeps what it last had, greyed (the reach bar says
// since when); the next socket's welcome asks again and its description replaces the lines, no reload. Nothing here
// acts. R-b F (Part F item 6): the lines and the diagnosis are drawn into two containers, so a line's change never
// rebuilds the log box (its scroll place and focus stay); the summary leads to the worst line's row.

import { h, fill, keepFocus } from '../dom.mjs';
import * as P from '../proto.mjs';

export const SCOPE = 'desc/health';

/** Since when a line holds, in the page's words; a state that began with the service says so (H1's `started`). */
export function sinceWords(line, started, now = Date.now()) {
  if (typeof line.since !== 'number') return '';
  return `since ${P.when(line.since, now)}${line.since === started ? ' (the service’s start)' : ''}`;
}

const NOT_LIVE = { stale: 'not current', lastKnown: 'last known' };

/** One line of the record as a row: label; band word (not on a normal line), reading; since · doing; why not current. */
export function lineRow(l, started, now = Date.now()) {
  const why = l.state && l.state !== 'live' ? `${NOT_LIVE[l.state] || l.state}${l.reason ? `: ${l.reason}` : ''}` : null;
  return h('li', { class: `hl band-${l.band}`, 'data-key': `health:${l.id}` },
    h('span', { class: 'hl-label', text: l.label }),
    h('span', { class: 'hl-body' },
      h('span', { class: 'hl-text' }, l.band !== 'normal' ? h('span', { class: 'hl-band', text: l.band }) : null, l.text),
      h('span', { class: 'hl-meta', text: [sinceWords(l, started, now), l.doing].filter(Boolean).join(' · ') }),
      why ? h('span', { class: 'hl-why', text: why }) : null));
}

export function healthView(ctx) {
  let root = null;
  let top = null; // the lines: drawn again on `desc/health` and at a welcome
  let low = null; // the diagnosis: drawn again on `diag` and at a welcome
  let head = null; // the diagnosis's head: drawn again on a reach change (its button's dim follows the reach)

  function drawLines() {
    keepFocus(top, 'data-key', () => fill(top, h('h1', { text: 'Health' }), lines(ctx.desc(SCOPE))));
  }

  function drawDiag() {
    keepFocus(low, 'data-key', () => fill(low, diagnosis()));
  }

  function drawHead() {
    if (!head) return;
    keepFocus(low, 'data-key', () => {
      const n = diagHead();
      head.replaceWith(n);
      head = n;
    });
  }

  function lines(d) {
    if (!d) return h('p', { class: 'note', text: 'Reading the PC’s health…' });
    const now = Date.now();
    const worst = (d.worst && d.worst.band) || 'normal';
    const rows = (d.lines || []).map((l) => lineRow(l, d.started, now));
    const at = worst === 'normal' ? -1 : (d.lines || []).findIndex((l) => l.id === d.worst.id);
    const say = (d.summary && d.summary.line) || '';
    // the summary leads to the worst line's row; the list keeps the record's order
    return [
      h('p', { class: `hl-summary band-${worst}` }, at < 0 ? say : h('a', { href: '#', focus: true, 'data-key': 'health:summary',
        on: { click: (e) => { e.preventDefault(); rows[at].scrollIntoView({ block: 'start' }); } } }, say)),
      h('ul', { class: 'hlines' }, rows),
    ];
  }

  /** "Read again" is the page's action button: dim out of reach with its reason, and a dim press keeps what is shown. */
  function diagHead() {
    const again = ctx.actionButton({ label: 'Read again', need: 'full', onClick: () => { ctx.unsub('diag'); ctx.sub('diag'); } });
    again.setAttribute('data-key', 'diag:again');
    return h('div', { class: 'hl-diag-head' }, h('h2', { text: 'Diagnosis' }), again);
  }

  function diagnosis() {
    const why = ctx.levelWhy('full');
    head = why ? null : diagHead();
    if (why) return h('p', { class: 'note hl-diag-why', text: `The diagnosis ${why}.` });
    const d = ctx.desc('diag');
    const w = ctx.S.welcome || {};
    if (!d) return [head, h('p', { class: 'note', text: 'Reading the PC’s state…' })];
    const facts = [
      ['PC build', d.build],
      ['Record', `epoch ${d.epoch} · seq ${d.seq}`],
      ['Contracts', Object.entries(d.contracts || {}).map(([k, v]) => `${k} ${v}`).join(', ')],
      // AC27: the welcome's bundle is its content hash alone
      ['This page', w.bundle ? `lane ${w.proto} · bundle ${w.bundle.hash}` : '—'],
      ['This device', `${ctx.S.device} · ${ctx.S.level} · connection ${ctx.S.conn} · stream ${ctx.stream.id}`],
    ];
    return [head,
      h('dl', { class: 'facts' }, facts.map(([k, v]) => [h('dt', { text: k }), h('dd', { text: v ?? '—' })])),
      h('h2', { text: `Connections (${(d.connections || []).length})` }),
      h('ul', { class: 'plain' }, (d.connections || []).map((c) => h('li', { text: c }))),
      h('h2', { text: `The PC log, last ${(d.log || []).length} lines` }),
      h('pre', { class: 'log', tabindex: 0 }, (d.log || []).join('\n'))];
  }

  return {
    mount() {
      top = h('div', { class: 'hl-top' });
      low = h('div', { class: 'hl-low' });
      root = h('section', { class: 'page hview' }, top, low);
      if (!ctx.levelWhy('full')) ctx.sub('diag');
      drawLines();
      drawDiag();
      return root;
    },
    unmount() {
      ctx.unsub('diag');
    },
    onDesc(scope) {
      if (scope === SCOPE) drawLines();
      else if (scope === 'diag') drawDiag();
    },
    onReach: () => drawHead(),
    onWelcome: () => {
      drawLines();
      drawDiag();
    },
  };
}
