// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-1, package P7: the page's DOM helpers. Every text goes in as text (never as markup), every link out of the
// app is http or https only and opens outside the page, and no style or script is written inline (the bundle's
// content security policy allows neither).

/** An element: `props` holds `class`, `text`, `on` (event → handler), attributes, and `focus` (a focus stop). */
export function h(tag, props, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'text') el.textContent = v;
    else if (k === 'on') for (const [ev, fn] of Object.entries(v)) el.addEventListener(ev, fn);
    else if (k === 'focus') el.dataset.focus = '';
    else el.setAttribute(k, v === true ? '' : String(v));
  }
  add(el, kids);
  return el;
}

function add(el, kids) {
  for (const k of kids) {
    if (k === null || k === undefined || k === false) continue;
    if (Array.isArray(k)) add(el, k);
    else el.append(k instanceof Node ? k : document.createTextNode(String(k)));
  }
}

/** S1-2 JW0 (seen in headless Firefox): replaces `el`'s children with `kids` as `h` takes them — null, undefined and
 *  false are left out and arrays flattened. The DOM's own `replaceChildren` writes a null as the text "null". */
export function fill(el, ...kids) {
  el.replaceChildren();
  add(el, kids);
  return el;
}

export function clear(el) {
  while (el.firstChild) el.firstChild.remove();
  return el;
}

/** A link out of the app: only http and https, opened outside the page with no referrer; else plain text. It is
 *  marked (`a.out::after`) and its title names the host it opens (fix round 1, ruling 2.3 (10)). */
export function outLink(href, kids) {
  if (!/^https?:\/\//i.test(href || '')) return h('span', null, kids);
  return h('a', { href, target: '_blank', rel: 'noopener noreferrer', class: 'out', title: outTitle(href) }, kids);
}

/** The title of a link that leaves the book: the host it opens. Pure. */
export function outTitle(href) {
  let host = '';
  try {
    host = new URL(href).host;
  } catch (e) {
    host = '';
  }
  return host ? `opens ${host}, outside the book` : 'opens outside the book';
}

/**
 * Fix round 1, ruling 2.3 (12): which toasts to remove so at most `cap` stay — `kept` (oldest first) says which are
 * kept until dismissed (a loss notice, said once). Only toasts not kept go, oldest first, and never the newest (the
 * one just said); kept ones stay past the cap. Returns their indices. Pure.
 */
export function toastsToDrop(kept, cap) {
  const drop = [];
  let over = kept.length - cap;
  for (let i = 0; i < kept.length - 1 && over > 0; i++) {
    if (kept[i]) continue;
    drop.push(i);
    over--;
  }
  return drop;
}

/**
 * Fix round 1, ruling 2.3 (5, 6): runs `draw` (which replaces `root`'s elements) and gives keyboard focus back to the
 * element carrying the same value of `attr` as the one focused before (a book's card by its id, a level's pick by its
 * code, the name being typed), with the caret where it was.
 */
export function keepFocus(root, attr, draw) {
  const a = document.activeElement;
  const k = a && root.contains(a) ? a.getAttribute(attr) : null;
  const caret = k !== null && typeof a.selectionStart === 'number' ? [a.selectionStart, a.selectionEnd] : null;
  draw();
  if (k === null) return;
  const b = [...root.querySelectorAll(`[${attr}]`)].find((e) => e.getAttribute(attr) === k);
  if (!b) return;
  b.focus();
  if (caret && typeof b.setSelectionRange === 'function') b.setSelectionRange(caret[0], caret[1]);
}

const ICONS = {
  home: 'M3 11 12 3l9 8v10h-6v-6H9v6H3z',
  book: 'M4 4h7a3 3 0 0 1 3 3v13a3 3 0 0 0-3-3H4zM20 4h-5a3 3 0 0 0-1 .2V20a3 3 0 0 1 2-1h4z',
  devices: 'M3 5h14v10H3zM1 17h18v2H1zM19 8h4v12h-4z',
  pulse: 'M2 12h4l3-7 4 14 3-7h6',
  gear: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM10 2h4l.6 2.6 2.3 1 2.3-1.4 2.8 2.8-1.4 2.3 1 2.3L24 12v0l-2.6.6-1 2.3 1.4 2.3-2.8 2.8-2.3-1.4-2.3 1L14 22h-4l-.6-2.6-2.3-1-2.3 1.4-2.8-2.8 1.4-2.3-1-2.3L0 12l2.6-.6 1-2.3-1.4-2.3 2.8-2.8 2.3 1.4 2.3-1z',
  folder: 'M2 5h7l2 2h11v13H2z',
};

/** A small line icon drawn in the page's own colours. */
export function icon(name) {
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('class', 'icon');
  svg.setAttribute('aria-hidden', 'true');
  const p = document.createElementNS(ns, 'path');
  p.setAttribute('d', ICONS[name] || ICONS.book);
  svg.append(p);
  return svg;
}

/** A progress bar 0..1 (its width set through the style object, never an inline attribute). */
export function bar(f) {
  const fill = h('span', { class: 'fill' });
  fill.style.width = `${Math.round((f || 0) * 1000) / 10}%`;
  return h('span', { class: 'bar', role: 'progressbar', 'aria-valuemin': 0, 'aria-valuemax': 100,
    'aria-valuenow': Math.round((f || 0) * 100) }, fill);
}
