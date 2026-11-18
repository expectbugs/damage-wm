// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-3, package JF (spec §2.3; CONTRACT.md §3.2 "document"): the one renderer of a document's blocks and spans
// for every page — Reader's book parts (moved here from `views/reader.mjs`, unchanged in what it draws) and Feed's
// articles and comments. A span's text goes in as a text node, never as markup; a link that leaves goes through
// `outLink` (http and https only, a new tab, no referrer, marked); anything else of a span's marks is a class.

import { h, outLink } from './dom.mjs';

/**
 * One span as an element starting at character `at` (kept in `data-c`, the place a page reads back). `opts.jump(s)`
 * makes an in-book link (Reader's `jump`) or returns null; `opts.where` names what a link leaves (its title).
 */
export function spanEl(s, at, opts = {}) {
  let el = null;
  if (s.href) el = outLink(s.href, s.text, opts.where);
  else if (s.jump && opts.jump) el = opts.jump(s);
  if (!el) el = h('span', null, s.text);
  for (const m of s.marks || []) if (m === 'em' || m === 'strong' || m === 'code') el.classList.add(m);
  el.dataset.c = at;
  return el;
}

/**
 * One block as an element: a heading, a list item at its depth, a quote, a mono block, a picture, a rule or a
 * paragraph, its spans laid end to end from `opts.at` (default the block's `charStart`, else 0). `opts.image(b)` draws
 * a picture block (default: the PC's blob by `ref`); `opts.headingBase` is added to a heading's level.
 */
export function blockEl(b, opts = {}) {
  let el;
  switch (b.kind) {
    case 'heading': el = h(`h${Math.min(6, (b.level || 1) + (opts.headingBase ?? 1))}`); break;
    case 'item': el = h('p', { class: `item d${Math.min(6, b.level || 1)}` }); break;
    case 'quote': el = h('blockquote'); break;
    case 'mono': el = h('pre'); break;
    case 'image': el = opts.image ? opts.image(b) : h('figure', null, h('img', { src: `/blob/${b.ref}`, alt: '', loading: 'lazy' })); break;
    case 'rule': el = h('hr'); break;
    default: el = h('p');
  }
  el.classList.add('blk');
  if (typeof b.charStart === 'number') el.dataset.s = b.charStart;
  if (typeof b.charEnd === 'number') el.dataset.e = b.charEnd;
  let at = opts.at ?? b.charStart ?? 0;
  for (const s of b.spans || []) {
    el.append(spanEl(s, at, opts));
    at += String(s.text).length;
  }
  return el;
}
