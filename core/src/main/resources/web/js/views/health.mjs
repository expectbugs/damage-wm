// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// R-b stage H3 (`private/build085/RB-lean-spec.md` §2, §4.6, §4.8): the Health page. Every line of the PC's health
// record (`desc/health`, level view; the page shell holds it from every welcome) with its reading, since when, what
// the system is doing about it, and why a line is not current; a line in attention or alarm carries its band word.
// Quiet when all is well: no band word and no colour on a normal line. At level full the diagnosis — the record's
// epoch and seq, the live connections, the versions, the PC log's tail — is the page's lower half (INTERFACES.md
// W-21; the Diagnosis page is merged here). Out of reach the page keeps what it last had, greyed, with when it was last
// heard and how long ago; the next socket's welcome asks again and its description replaces the lines, no reload.
// Nothing here acts.

import { h, fill, keepFocus } from '../dom.mjs';
import * as P from '../proto.mjs';

export const SCOPE = 'desc/health';
/** Out of reach, the age is said again at this pace (the page's own pacing; nothing waits on it). */
export const AGE_EVERY_MS = 30000;

/** Since when a line holds, in the page's words; a state that began with the service says so (H1's `started`). */
export function sinceWords(line, started, now = Date.now()) {
  if (typeof line.since !== 'number') return '';
  return `since ${P.when(line.since, now)}${line.since === started ? ' (the service’s start)' : ''}`;
}

/** How long ago `ms` was. */
export function agoWords(ms, now = Date.now()) {
  const min = Math.floor((now - ms) / 60000);
  if (min < 1) return 'under a minute ago';
  if (min < 60) return `${min} min ago`;
  const hr = Math.floor(min / 60);
  return hr < 24 ? `${hr} h ${min % 60} min ago` : `${Math.floor(hr / 24)} d ${hr % 24} h ago`;
}

const NOT_LIVE = { stale: 'not current', lastKnown: 'last known' };

/** One line of the record as a row: label; band word (not on a normal line), reading; since · doing; why not current. */
export function lineRow(l, started, now = Date.now()) {
  const why = l.state && l.state !== 'live' ? `${NOT_LIVE[l.state] || l.state}${l.reason ? `: ${l.reason}` : ''}` : null;
  return h('li', { class: `hl band-${l.band}${why ? ' not-live' : ''}`, 'data-key': `health:${l.id}` },
    h('span', { class: 'hl-label', text: l.label }),
    h('span', { class: 'hl-body' },
      h('span', { class: 'hl-text' }, l.band !== 'normal' ? h('span', { class: 'hl-band', text: l.band }) : null, l.text),
      h('span', { class: 'hl-meta', text: [sinceWords(l, started, now), l.doing].filter(Boolean).join(' · ') }),
      why ? h('span', { class: 'hl-why', text: why }) : null));
}

export function healthView(ctx) {
  let root = null;
  let ager = null;

  /** Out of reach (with lines to age) the age is said again on the pacing; in reach, or gone, it stops. */
  function pace(on) {
    if (on && ager === null) ager = setInterval(draw, AGE_EVERY_MS);
    if (!on && ager !== null) {
      clearInterval(ager);
      ager = null;
    }
  }

  function draw() {
    const d = ctx.desc(SCOPE);
    const away = ctx.S.reach.ok === false;
    pace(away && !!d);
    keepFocus(root, 'data-key', () => fill(root, h('h1', { text: 'Health' }), lines(d, away), diagnosis()));
  }

  function lines(d, away) {
    if (!d) return h('p', { class: 'note', text: 'Reading the PC’s health…' });
    const now = Date.now();
    const worst = (d.worst && d.worst.band) || 'normal';
    return [
      h('p', { class: `hl-summary band-${worst}`, text: (d.summary && d.summary.line) || '' }),
      away ? h('p', { class: 'hl-age', text: `As last heard from the PC at ${P.when(ctx.S.reach.since, now)}, ${agoWords(ctx.S.reach.since, now)}.` }) : null,
      h('ul', { class: 'hlines' }, (d.lines || []).map((l) => lineRow(l, d.started, now))),
    ];
  }

  function diagnosis() {
    const why = ctx.levelWhy('full');
    if (why) return h('p', { class: 'note hl-diag-why', text: `The diagnosis ${why}.` });
    const d = ctx.desc('diag');
    const w = ctx.S.welcome || {};
    const head = h('div', { class: 'hl-diag-head' }, h('h2', { text: 'Diagnosis' }),
      h('button', { focus: true, 'data-key': 'diag:again', on: { click: () => { ctx.unsub('diag'); ctx.sub('diag'); } } }, 'Read again'));
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
      root = h('section', { class: 'page hview' });
      if (!ctx.levelWhy('full')) ctx.sub('diag');
      draw();
      return root;
    },
    unmount() {
      pace(false);
      ctx.unsub('diag');
    },
    onDesc(scope) {
      if (scope === SCOPE || scope === 'diag') draw();
    },
    onReach: () => draw(),
    onWelcome: () => draw(),
  };
}
