// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-1, package P7 (the spec's §2 P7 "Reader"; INTERFACES.md W-30 as CONTRACT.md §14 (a) row 2 rules it;
// decisions A.2, A.10, E.6): Reader in a browser —
//  - the shelf as a cover grid with progress and folders (the progress follows the record's places as they move);
//  - a book in Damage's own text (the PC's document: emphasis, links, pictures), one spine part at a time, with a
//    chapter sidebar; in-book links jump, http(s) links open outside the page;
//  - the place sent as a position op when the scroll rests (2 s after input) and on leaving, carrying the time of the
//    input; only input counts as reading — the page's own moves (opening at the place, following) send nothing;
//  - L1 (the ruling, "The pages"): another device's input moving the open book's place moves the page there, no
//    offer — unless this page's own input is later (the latest real input wins);
//  - bookmark here, go to the bookmark, and reset progress through the confirm shape.
// Nothing is cut: a part is drawn whole and scrolls.

import { h, fill, bar, icon, keepFocus } from '../dom.mjs';
import { blockEl } from '../doc.mjs';
import { offsetOf } from '../anchor.mjs';
import { progress, percent, partFor, placeAt } from '../reader-model.mjs';
import { key } from '../record.mjs';
import { format as address } from '../address.mjs';

const W = 'reader';
const SAVE_MS = 2000;
const TOP_PAD = 8;

export function readerView(ctx) {
  let root = null;
  let shelfScope = null;
  let B = null; // the open book

  const placeWords = (v) => {
    const o = offsetOf(v);
    if (o === null) return 'no place';
    const len = B && B.level && B.level.length;
    return len ? percent(Math.min(1, o / len)) : `character ${o}`;
  };
  const levelOf = (action) => {
    const d = ctx.desc('desc/reader');
    const a = d && (d.actions || []).find((x) => x.id === action);
    return a ? a.level : 'control';
  };

  // ---- the shelf ----

  function openShelf(folder) {
    const scope = folder ? `reader/folder:${folder}` : 'desc/reader';
    if (shelfScope !== scope) {
      if (shelfScope && shelfScope !== 'desc/reader') ctx.unsub(shelfScope);
      shelfScope = scope;
      if (scope !== 'desc/reader') ctx.sub(scope);
    }
    drawShelf();
  }

  function drawShelf() {
    const d = ctx.desc(shelfScope);
    const shelf = d ? d.shelf || d : null;
    const folder = shelfScope.startsWith('reader/folder:') ? shelfScope.slice('reader/folder:'.length) : '';
    const crumbs = [h('a', { href: '/w/reader', 'data-key': 'crumb:', focus: true, on: { click: nav({ window: W }) } }, 'Shelf')];
    let path = '';
    for (const part of folder ? folder.split('/') : []) {
      path = path ? `${path}/${part}` : part;
      crumbs.push(' / ', h('a', { href: address({ window: W, target: `folder:${path}` }), 'data-key': `crumb:${path}`, focus: true, on: { click: nav({ window: W, target: `folder:${path}` }) } }, part));
    }
    const head = h('nav', { class: 'crumbs' }, crumbs);
    if (!shelf) {
      root.replaceChildren(head, h('p', { class: 'note', text: 'Reading the shelf…' }));
      return;
    }
    const state = (ctx.desc('desc/reader') || {}).state;
    const summary = ((ctx.desc('desc/main') || {}).cards || []).find((c) => c.window === W)?.summary;
    const cont = !folder && summary && summary.book ? h('a', { href: `/w/reader/book:${summary.book}`, class: 'continue', 'data-key': 'continue', focus: true,
      on: { click: nav({ window: W, target: `book:${summary.book}` }) } }, h('span', { class: 'label', text: 'Continue' }),
      h('span', { text: summary.line })) : null;
    const folders = shelf.folders.map((f) => {
      const p = folder ? `${folder}/${f}` : f;
      return h('a', { href: address({ window: W, target: `folder:${p}` }), class: 'folder', 'data-key': `folder:${p}`, focus: true, on: { click: nav({ window: W, target: `folder:${p}` }) } },
        icon('folder'), h('span', { text: f }));
    });
    // ruling 2.3 (5): a redraw (any device's save point describes Main again) keeps the focus: a card by its book id,
    // Continue, a folder, a crumb
    keepFocus(root, 'data-key', () => fill(root, head,
      state && state !== 'ok' ? h('p', { class: 'note', text: state === 'loading' ? 'The PC is reading the shelf…' : state }) : null,
      cont,
      folders.length ? h('div', { class: 'folders' }, folders) : null,
      shelf.books.length ? h('div', { class: 'shelf' }, shelf.books.map(card))
        : h('p', { class: 'note', text: folders.length ? '' : 'No books here.' })));
  }

  function card(b) {
    const why = b.unreadable ? `this book could not be read: ${b.unreadable}` : null;
    const cover = h('span', { class: 'cover' }, b.cover ? h('img', { src: `/blob/${b.cover}`, alt: '', loading: 'lazy',
      on: { error: (e) => e.target.replaceWith(textCover(b)) } }) : textCover(b));
    return h('a', { href: `/w/reader/book:${b.id}`, class: 'book' + (why ? ' dim' : ''), 'data-book': b.id, 'data-key': `book:${b.id}`, focus: true,
      'data-why': why, 'aria-disabled': why ? 'true' : null,
      on: { click: (e) => {
        e.preventDefault();
        if (why) ctx.say(why, { from: 'Reader' });
        else ctx.go({ window: W, target: `book:${b.id}` });
      } } },
      cover, h('span', { class: 'book-title', text: b.title }), h('span', { class: 'book-author', text: b.author }),
      progressOf(b));
  }

  function progressOf(b) {
    const f = progress(b, ctx.S.record.value(W, `place.${b.id}`));
    return h('span', { class: 'book-progress' }, f === null ? null : bar(f), h('span', { class: 'pct', text: f ? percent(f) : '' }));
  }

  function textCover(b) {
    let n = 0;
    for (const c of b.id) n = (n * 31 + c.charCodeAt(0)) % 6;
    return h('span', { class: `text-cover c${n}` }, h('span', { class: 'tc-title', text: b.title }), h('span', { class: 'tc-by', text: b.author }));
  }

  /** The record's places moved: each card's progress, in place (a redraw would fetch every cover again). */
  function shelfPlaces(keys) {
    const d = ctx.desc(shelfScope);
    const books = d ? (d.shelf || d).books : [];
    for (const b of books) {
      if (!keys.has(key(W, `place.${b.id}`))) continue;
      const el = root.querySelector(`[data-book="${b.id}"] .book-progress`);
      if (el) el.replaceWith(progressOf(b));
    }
  }

  // ---- the book ----

  function openBook(id, anchor, input) {
    // `offset`: the place this view last sent, took or followed; `inputAt`: its last input, on the PC's clock
    B = { id, level: null, anchor, offset: null, inputAt: 0, part: null, doc: null, partScope: null,
      want: null, input: false, touch: false, saveTimer: null, started: false, side: false, els: {}, byInput: !!input };
    ctx.sub(`reader/book:${id}`);
    // opening by input is a reading effect: the book read most recently (the latest input); ruling 2.3:
    // never on a page load, a reload or a restored tab
    if (input) ctx.sendQueued({ window: W, action: 'open', targets: ['recent'], result: id }, null, { quiet: true });
    drawBook();
    start();
  }

  /** The first view of the book, once its level and the record are both here: an entry takes the shared place (an
   *  address that names a place shows it; it counts as input only when input routed there — a reload or a restored
   *  tab writes nothing, the focused read's S2). */
  function start() {
    if (!B || B.started || !B.level || !ctx.S.recordReady) return;
    B.started = true;
    const item = ctx.S.record.item(W, `place.${B.id}`);
    const off = item && !item.gone ? offsetOf(item.v) : null;
    B.offset = off;
    const linked = B.anchor ? offsetOf(B.anchor) : null;
    if (linked !== null) showAt(linked, B.byInput);
    else showAt(off ?? 0, false);
    drawBar();
  }

  function drawBook() {
    const els = B.els;
    els.bar = h('div', { class: 'bookbar' });
    els.side = h('nav', { class: 'chapters', 'aria-label': 'Chapters' });
    els.text = h('article', { class: 'text' }, h('p', { class: 'note', text: 'Opening the book…' }));
    els.scroll = h('div', { class: 'reading', tabindex: 0 }, els.text);
    // L1 R2 B1: an input of his is stamped once, when it is made; a scroll counts as his only while his gesture is
    // live (until its save point, 2 s after it rests) — a resize, a reflow or the page's own move never stamps or sends
    const input = () => {
      if (!B) return;
      B.touch = true;
      B.inputAt = ctx.pcNow();
      clearTimeout(B.saveTimer);
      B.saveTimer = setTimeout(() => save(), SAVE_MS);
    };
    for (const ev of ['wheel', 'touchmove', 'pointerdown']) els.scroll.addEventListener(ev, input, { passive: true });
    els.scroll.addEventListener('keydown', (e) => {
      if (['PageDown', 'PageUp', ' ', 'ArrowDown', 'ArrowUp', 'Home', 'End'].includes(e.key)) input();
    });
    els.scroll.addEventListener('scroll', () => {
      if (!B || !B.touch) return;
      B.input = true; // the place moves by his input (a drag, a fling) until it rests
      clearTimeout(B.saveTimer);
      B.saveTimer = setTimeout(() => save(), SAVE_MS);
    }, { passive: true });
    root.replaceChildren(els.bar, h('div', { class: 'book-body' + (B.side ? ' side-open' : '') }, els.side, els.scroll));
    drawBar();
    drawSide();
  }

  function drawBar() {
    if (!B) return;
    const lv = B.level;
    const placeItem = ctx.S.record.item(W, `place.${B.id}`);
    const mark = ctx.S.record.value(W, `bookmark.${B.id}`);
    const back = h('a', { href: address(lv && lv.folder ? { window: W, target: `folder:${lv.folder}` } : { window: W }), class: 'back', focus: true,
      on: { click: nav(lv && lv.folder ? { window: W, target: `folder:${lv.folder}` } : { window: W }) } }, '← Shelf');
    B.els.bar.replaceChildren(back,
      h('span', { class: 'book-name' }, h('span', { class: 'book-title', text: lv ? lv.title : 'Opening…' }),
        lv && lv.author ? h('span', { class: 'book-author', text: lv.author }) : null),
      h('span', { class: 'spacer' }),
      h('button', { class: 'side-toggle', focus: true, 'aria-expanded': String(B.side), on: { click: () => {
        B.touch = false; // the text reflows: not his scroll
        B.side = !B.side;
        B.els.side.parentElement.classList.toggle('side-open', B.side);
        drawBar();
      } } }, 'Chapters'),
      ctx.actionButton({ label: 'Bookmark here', need: levelOf('bookmark'), why: B.doc ? null : 'the book is still opening',
        onClick: bookmarkHere }),
      ctx.actionButton({ label: 'Go to bookmark', need: 'view', why: mark ? null : 'this book has no bookmark yet',
        onClick: () => showAt(offsetOf(mark) ?? 0, true) }),
      ctx.actionButton({ label: 'Reset progress', need: levelOf('reset'), cls: 'quiet',
        why: placeItem && !placeItem.gone ? null : 'there is no progress to reset', onClick: resetProgress }));
  }

  function drawSide() {
    if (!B) return;
    const chapters = B.level && B.level.chapters && B.level.chapters.length ? B.level.chapters
      : B.doc ? Array.from({ length: B.doc.parts }, (_, i) => ({ title: i === B.doc.part ? B.doc.title : `Part ${i + 1}`, anchor: null })) : [];
    B.els.side.replaceChildren(h('ol', null, chapters.map((c, i) => h('li', null,
      h('button', { focus: true, class: i === B.part ? 'current' : null, 'aria-current': i === B.part ? 'true' : null,
        on: { click: () => {
          if (c.anchor) showAt(offsetOf(c.anchor), true);
          else loadPart(i, null, true, 0);
          if (window.matchMedia('(max-width: 760px)').matches) {
            B.side = false;
            B.els.side.parentElement.classList.remove('side-open');
          }
        } } }, c.title)))));
  }

  /** L1 (the ruling, "The pages"): another device's input moved this book's place: the page goes there — unless this
   *  page's own input is later (its place is on its way and wins). A move the page makes by itself sends nothing. */
  function follow(item) {
    if (!item || item.gone || item.by === ctx.S.device || item.at < B.inputAt) return;
    const off = offsetOf(item.v);
    if (off === null || off === B.offset) return;
    B.offset = off;
    showAt(off, false);
  }

  /** Shows the book at `offset`: its part, then the offset at the top. [input] = a jump the reader asked for. */
  function showAt(offset, input) {
    const n = partFor(B.level && B.level.chapters, offset);
    loadPart(n, offset, input, 0);
  }

  function loadPart(n, offset, input, dir) {
    if (input && !dir) B.inputAt = ctx.pcNow(); // a jump's time is the click's, not its part's arrival (L1 R2 B1)
    if (B.doc && B.doc.part === n && !B.partScope) {
      placeView(offset ?? B.doc.charStart, input);
      return;
    }
    if (B.partScope) ctx.unsub(B.partScope);
    B.partScope = `reader/book:${B.id}/part:${n}`;
    B.want = { n, offset, input, dir };
    ctx.sub(B.partScope);
  }

  function gotPart(d) {
    const want = B.want || { offset: null, input: false, dir: 0 };
    ctx.unsub(B.partScope);
    B.partScope = null;
    const off = want.offset ?? d.charStart;
    // the chapters name parts by index; a place outside this part walks to its neighbour, one way only
    if (off > d.charEnd && d.part + 1 < d.parts && want.dir >= 0) return loadPart(d.part + 1, off, want.input, 1);
    if (off < d.charStart && d.part > 0 && want.dir <= 0) return loadPart(d.part - 1, off, want.input, -1);
    B.doc = d;
    B.part = d.part;
    renderPart();
    placeView(off, want.input);
    drawSide();
    drawBar();
  }

  function renderPart() {
    const d = B.doc;
    const chapters = (B.level && B.level.chapters) || [];
    const title = (i) => (chapters[i] && chapters[i].title) || `part ${i + 1}`;
    const step = (i, label) => h('button', { class: 'step', focus: true, on: { click: () => {
      const a = chapters[i] && offsetOf(chapters[i].anchor);
      loadPart(i, a, true, 0);
    } } }, label);
    const blocks = d.blocks.map(block);
    B.blockEls = blocks;
    fill(B.els.text,
      d.part > 0 ? step(d.part - 1, `← ${title(d.part - 1)}`) : null,
      ...blocks,
      d.part + 1 < d.parts ? step(d.part + 1, `Next: ${title(d.part + 1)} →`) : h('p', { class: 'end', text: 'The end of the book.' }));
  }

  // S1-3 (B17): the block and span renderer is `doc.mjs`'s, shared with Feed; Reader adds its in-book jumps
  const jump = (s) => h('a', { href: address({ window: W, target: `book:${B.id}`, anchor: s.jump }), class: 'jump',
    on: { click: (e) => { e.preventDefault(); showAt(offsetOf(s.jump) ?? 0, true); } } }, s.text);
  const block = (b) => blockEl(b, { jump });

  /** Puts `offset` at the top of the reading pane. A move the page makes counts as input only when asked ([input]). */
  function placeView(offset, input) {
    const pane = B.els.scroll;
    const blk = blockFor(offset);
    let top = null;
    if (blk) {
      let r = null;
      for (const c of blk.querySelectorAll('[data-c]')) {
        if (Number(c.dataset.c) <= offset) r = c;
        else break;
      }
      const t = r && r.firstChild && r.firstChild.nodeType === 3 ? r.firstChild : null;
      if (t && t.length > 0) {
        const i = Math.max(0, Math.min(offset - Number(r.dataset.c), t.length - 1));
        const range = document.createRange();
        range.setStart(t, i);
        range.setEnd(t, i + 1);
        const rects = range.getClientRects();
        if (rects.length) top = rects[0].top;
      }
      if (top === null) top = blk.getBoundingClientRect().top;
      pane.scrollTop += top - pane.getBoundingClientRect().top - TOP_PAD;
    } else pane.scrollTop = 0;
    clearTimeout(B.saveTimer);
    B.input = !!input;
    B.touch = false;
    if (input) B.saveTimer = setTimeout(() => save(), SAVE_MS);
  }

  /** The block holding `offset` (the last starting at or before it). */
  function blockFor(offset) {
    const els = B.blockEls || [];
    let lo = 0;
    let hi = els.length - 1;
    let best = null;
    while (lo <= hi) {
      const mid = (lo + hi) >> 1;
      if (Number(els[mid].dataset.s) <= offset) {
        best = els[mid];
        lo = mid + 1;
      } else hi = mid - 1;
    }
    return best || els[0] || null;
  }

  /** The book's character offset at the top of the reading pane: the caret there, else the first block in view. */
  function offsetAtTop() {
    const pane = B.els.scroll.getBoundingClientRect();
    const first = (B.blockEls || [])[0];
    const x = (first ? first.getBoundingClientRect().left : pane.left) + 2;
    const y = pane.top + TOP_PAD + 2;
    const range = document.caretRangeFromPoint ? document.caretRangeFromPoint(x, y) : null;
    if (range) {
      const node = range.startContainer;
      const el = node.nodeType === 3 ? node.parentElement : node;
      const r = el && el.closest ? el.closest('[data-c]') : null;
      if (r && node.nodeType === 3 && B.els.text.contains(r)) return Number(r.dataset.c) + range.startOffset;
      const blk = el && el.closest ? el.closest('.blk') : null;
      if (blk && B.els.text.contains(blk)) return Number(blk.dataset.s);
    }
    const els = B.blockEls || [];
    let lo = 0;
    let hi = els.length - 1;
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      if (els[mid].getBoundingClientRect().bottom <= pane.top + TOP_PAD) lo = mid + 1;
      else hi = mid;
    }
    return els[lo] ? Number(els[lo].dataset.s) : B.doc.charStart;
  }

  /** A save point: the place at the top, as a position op, when input moved it (dec. A.10). */
  function save() {
    if (!B) return;
    clearTimeout(B.saveTimer);
    B.saveTimer = null;
    B.touch = false;
    if (!B.input || !B.doc) return;
    const why = ctx.canAct('view');
    if (why) {
      ctx.sayOnce('reader-unsaved', `Reading here is not saved: ${why}.`, { from: 'Reader' });
      return;
    }
    B.input = false;
    const off = offsetAtTop();
    if (off === B.offset) return;
    B.offset = off;
    // L1 C1: the place carries the time of the input that moved it; the PC keeps the latest input's place
    const book = B;
    ctx.sendQueued({ window: W, action: 'place', targets: [`place.${B.id}`], result: placeAt(B.doc, off), at: B.inputAt }, (a) => {
      // L1 R2 B2: an op the connection's end left unanswered is kept as unsent and goes again at the next welcome
      // (onReach), with its input's time: an older or equal `at` changes nothing on the PC (C1)
      if (a.a === 'refused' && a.code === 'ended') { if (book === B && B.offset === off) { B.offset = null; B.input = true; } }
      else if (a.a === 'refused') ctx.say(`this place was not applied: ${a.reason}`, { from: 'Reader' });
    });
  }

  function bookmarkHere() {
    const anchor = placeAt(B.doc, offsetAtTop());
    ctx.sendQueued({ window: W, action: 'bookmark', targets: [`bookmark.${B.id}`], result: anchor }, (a) => {
      if (a.a === 'refused') ctx.say(a.code === 'ended'
        ? 'not known whether the bookmark was set: the connection ended before its answer' : a.reason, { from: 'Reader' });
      else ctx.say(`bookmark set at ${placeWords(anchor)}`, { from: 'Reader' });
    });
  }

  async function resetProgress() {
    const item = ctx.S.record.item(W, `place.${B.id}`);
    if (!item || item.gone) return;
    const title = B.level ? B.level.title : 'this book';
    const yes = await ctx.confirm({ title: 'Reset progress?', act: 'Reset progress',
      body: `The place in “${title}” is removed on every device; the book opens at its start next time.` });
    if (!yes || !B) return;
    ctx.sendQueued({ window: W, action: 'reset', targets: [`place.${B.id}`], result: null }, (a) => {
      if (a.a === 'refused') ctx.say(a.code === 'ended'
        ? 'not known whether progress was reset: the connection ended before its answer' : a.reason, { from: 'Reader' });
      else {
        ctx.say(`progress of “${title}” reset`, { from: 'Reader' });
        // the local reset's meaning on the glasses: the book closes and opens at its start next time
        if (B && B.input !== undefined) B.input = false;
        ctx.go({ window: W });
      }
    });
  }

  function leaveBook() {
    if (!B) return;
    save();
    if (B.partScope) ctx.unsub(B.partScope);
    ctx.unsub(`reader/book:${B.id}`);
    clearTimeout(B.saveTimer);
    B = null;
  }

  // ---- the view ----

  function nav(a) {
    return (e) => {
      e.preventDefault();
      ctx.go(a);
    };
  }

  function show(r, input) {
    const t = r.target || 'shelf';
    root.classList.toggle('is-book', t.startsWith('book:'));
    if (t.startsWith('book:')) {
      const id = t.slice(5);
      if (B && B.id === id && !r.anchor) return;
      leaveBook();
      if (shelfScope && shelfScope !== 'desc/reader') ctx.unsub(shelfScope);
      shelfScope = null;
      openBook(id, r.anchor, input);
    } else {
      leaveBook();
      openShelf(t.startsWith('folder:') ? t.slice('folder:'.length) : '');
    }
  }

  const onHide = () => save();
  const onResize = () => { if (B) B.touch = false; }; // L1 R2 B1: the reflow a resize makes is not his scroll
  const onVisibility = () => { if (document.visibilityState === 'hidden') save(); };

  return {
    mount(r, o = {}) {
      root = h('section', { class: 'page reader' });
      ctx.sub('desc/reader');
      window.addEventListener('pagehide', onHide);
      window.addEventListener('resize', onResize);
      document.addEventListener('visibilitychange', onVisibility);
      show(r, o.input);
      return root;
    },
    update(r, o = {}) {
      show(r, o.input);
      return true;
    },
    unmount() {
      leaveBook();
      if (shelfScope && shelfScope !== 'desc/reader') ctx.unsub(shelfScope);
      shelfScope = null;
      ctx.unsub('desc/reader');
      window.removeEventListener('pagehide', onHide);
      window.removeEventListener('resize', onResize);
      document.removeEventListener('visibilitychange', onVisibility);
    },
    back() {
      if (B) {
        const f = B.level && B.level.folder;
        ctx.go(f ? { window: W, target: `folder:${f}` } : { window: W });
        return true;
      }
      if (shelfScope && shelfScope.startsWith('reader/folder:')) {
        const f = shelfScope.slice('reader/folder:'.length);
        const up = f.includes('/') ? f.slice(0, f.lastIndexOf('/')) : '';
        ctx.go(up ? { window: W, target: `folder:${up}` } : { window: W });
        return true;
      }
      return false;
    },
    onDesc(scope, d) {
      if (B) {
        if (scope === B.partScope) gotPart(d);
        else if (scope === `reader/book:${B.id}`) {
          B.level = d;
          drawBar();
          drawSide();
          start();
        } else if (scope === 'desc/reader') drawBar();
      } else if (shelfScope && (scope === shelfScope || scope === 'desc/reader' || scope === 'desc/main')) drawShelf();
    },
    onRefused(scope, a) {
      if (B && scope === B.partScope) {
        B.partScope = null;
        B.els.text.replaceChildren(h('p', { class: 'note', text: `This part could not be opened: ${a.reason}` }));
        return true;
      }
      return false;
    },
    onRecord(keys) {
      if (B) {
        start();
        const k = key(W, `place.${B.id}`);
        // before the entry nothing is followed: the entry takes the record's place
        if (keys.has(k) && B.started) follow(ctx.S.record.item(W, `place.${B.id}`));
        if (keys.has(k) || keys.has(key(W, `bookmark.${B.id}`))) drawBar();
      } else if (shelfScope) shelfPlaces(keys);
    },
    onReach() {
      drawBar();
      // ruling 2.3: a place read while the PC was out of reach is sent when it answers again (the latest place only:
      // a mark, not a queue)
      if (ctx.S.reach.ok && B && B.input) save();
    },
  };
}
