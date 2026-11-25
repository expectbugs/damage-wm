// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-1, package P7 (INTERFACES.md W-01): home — Main's cards from Main's description (`desc/main`: the
// converted windows only, one card in S1-1, Reader), each with its summary, progress and availability; a card the
// device's level does not reach is dim and says why.
// Build S1-5, stage W: a window with no page in the browser (Games, whose card says `page: false`: Hold'em in the
// browser is later, when he asks — decisions Part A answer 1) shows its summary and where it is played, and links
// nowhere.
// R-b stage H3: one line above the cards — the health record's summary ("All well", or the worst line), in its band,
// linking to the Health page.

import { h, icon, bar, keepFocus } from '../dom.mjs';
import { SCOPE as HEALTH } from './health.mjs';

export function homeView(ctx) {
  let root = null;

  function draw() {
    const main = ctx.desc('desc/main');
    const cards = main ? main.cards : null;
    // ruling 2.3 (5)'s class: Main is described again at every save point of any device; the focus stays
    keepFocus(root, 'data-key', () => root.replaceChildren(
      h('h1', { text: 'Damage' }),
      healthLine(),
      cards === null ? h('p', { class: 'note', text: 'Reading the windows from the PC…' })
        : h('div', { class: 'cards' }, cards.map(card)),
    ));
  }

  /** The health record's summary as one link to the Health page; its band colours it (quiet when all is well). */
  function healthLine() {
    const d = ctx.desc(HEALTH);
    const band = d && d.worst ? d.worst.band : 'normal';
    return h('a', { href: '/w/health', class: `home-health band-${band}`, 'data-key': 'health', focus: true,
      on: { click: (e) => { e.preventDefault(); ctx.go({ window: 'health' }); } } },
      icon('pulse'), h('span', { text: d ? (d.summary && d.summary.line) || '' : 'Reading the PC’s health…' }));
  }

  function card(c) {
    const why = ctx.levelWhy(c.min);
    const s = c.summary || {};
    const state = c.availability === 'live' ? null : c.availability === 'loading' ? 'reading the shelf…' : `unavailable: ${c.reason || ''}`;
    const page = c.page !== false && ctx.hasView(c.window);
    const inside = [
      h('span', { class: 'card-head' }, icon(c.icon), h('span', { class: 'card-name', text: c.name })),
      h('span', { class: 'card-line', text: s.line || '' }),
      // S1-2 Q4w: a summary's second line (Torrents' counts, shared with Main's row on the glasses)
      s.detail ? h('span', { class: 'card-state', text: s.detail }) : null,
      typeof s.progress === 'number' ? bar(s.progress) : null,
      state ? h('span', { class: 'card-state', text: state }) : null,
      page ? null : h('span', { class: 'card-state', text: 'on the glasses' })];
    if (!page) return h('div', { class: 'card-wrap' }, h('div', { class: 'card shown' + (why ? ' dim' : ''), 'data-key': `card:${c.window}`, 'data-why': why }, inside));
    const open = h('a', { href: `/w/${c.window}`, class: 'card' + (why ? ' dim' : ''), 'data-key': `card:${c.window}`, focus: true, 'data-why': why,
      'aria-disabled': why ? 'true' : null, on: { click: (e) => {
        e.preventDefault();
        if (why) ctx.say(why, { from: c.name });
        else ctx.go({ window: c.window });
      } } }, inside);
    const cont = s.book && !why ? h('a', { href: `/w/${c.window}/book:${s.book}`, class: 'card-more', 'data-key': `more:${c.window}`, focus: true,
      on: { click: (e) => {
        e.preventDefault();
        ctx.go({ window: c.window, target: `book:${s.book}` });
      } } }, 'Continue reading') : null;
    return h('div', { class: 'card-wrap' }, open, cont);
  }

  return {
    mount() {
      root = h('section', { class: 'page home' });
      draw();
      return root;
    },
    onDesc(scope) {
      if (scope === 'desc/main' || scope === HEALTH) draw();
    },
  };
}
