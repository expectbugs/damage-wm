// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-3, piece B17 (first built as package JF; CONTRACT.md §3.2, §3.4, §4; FEED.md's verdicts): Feed in
// a browser. On a desk the sources are a column, a source's items a list beside it and an item in a reading column; at
// a phone's width one column at a time with a way back. An article in Damage's own text (emphasis, code, links out of
// the page, pictures from the PC) with a plain link to the original page; its comments as a thread with their own
// place on this browser; a comic as drawn, with its alt text, SMBC's bonus and a bar; the 8-Bit Theater archive as one
// long page; Flagged; the settings. Reading marks the item read only when input opened it; a place is sent 2 s after
// the scroll his input moved rests and on leaving, carrying the time of that input; another device's newer input on
// the article or archive shown moves the page there (Reader's follow rule) and a move the page makes sends nothing;
// out of reach the page greys and its actions say why. Every decision is in `feed-model.mjs`; this module draws and
// sends.

import { h, fill, icon, keepFocus, outLink } from '../dom.mjs';
import { blockEl } from '../doc.mjs';
import { hhmm, atLeast } from '../proto.mjs';
import * as M from '../feed-model.mjs';

const ROW_PX = 48;
const TOP_PAD = 8;

export function feedView(ctx) {
  const store = ctx.store;
  const rec = ctx.rec(M.W);
  const pend = new M.Pending();
  const refusals = new Map(); // scope → the PC's reason
  const flying = new Map(); // k → {label, at}: program ops sent and not answered
  let root = null;
  let els = null;
  let place = { at: 'sources' };
  let anchor = null;
  let last = null; // desc/feed
  let lastAt = null; // when it came, on the PC's clock
  let pages = null; // the open source's items (M.Pages)
  let from = null; // 'flagged' when an item was opened from Flagged
  let art = null; // the reading column's article, comic or comments
  let bg = null; // the binge
  let owedRead = null; // {src, id, at}: an item opened by input while the PC was out of reach: its `read` goes when it answers
  let seenOwed = null; // a source opened by input: its `seen` goes once a page of it is shown
  let ticker = null;
  let flyTimer = null;
  let retryTimer = null;
  let browseOpen = false;
  let browseText = '';
  let section = null;

  const pcNow = () => ctx.pcNow();
  const say = (text, opts = {}) => ctx.say(text, { from: 'Feed', ...opts });
  const acts = () => M.actionsOf(last);
  const act = (id) => acts().get(id);
  const offer = (id) => M.offered(act(id), ctx.S.level);
  const levelOf = (id, d = 'control') => (act(id) && act(id).level) || d;
  const src = (id) => M.sourceOf(last, id);
  const nameOf = (id) => (src(id) ? src(id).name : id);
  const ready = () => rec.epoch !== null;
  const go = (p) => {
    const t = M.targetOf(p);
    ctx.go(t ? { window: M.W, target: t } : { window: M.W });
  };
  const link = (text, p, key, cls) => h('a', { href: `/w/feed${M.targetOf(p) ? `/${M.targetOf(p)}` : ''}`, class: cls || null, 'data-key': key, focus: true,
    on: { click: (e) => { e.preventDefault(); go(p); } } }, text);
  const images = () => store.get(M.KEYS.images) !== 'off';

  // ---- record ops: applied on the page at once, drawn until the record holds them (L1: the latest input wins) ----

  /** Sends a record op whose result is `name`'s new value; the page shows the value until the record holds it. */
  function sendRecord(draft, name, label) {
    const token = pend.set(name, draft.result);
    const sent = ctx.sendQueued(draft, (ans) => {
      if (ans.a === 'refused') say(`${label}: ${ans.code === 'ended' ? 'not known whether it was applied: the connection ended before its answer' : ans.reason}`);
      pend.answered(name, token, ans.a === 'seen');
      redraw();
    }, { need: levelOf(draft.action) });
    if (!sent) pend.answered(name, token, false);
    redraw();
    return sent;
  }

  /** `read` on the input that opened an item (never a page load, a restore, a reconnect or a list shown), stamped with
   *  the time he opened it, also when it goes later (S1-3 fix B6: the PC then keeps the later input's list). */
  function readOnInput(srcId, id, at = pcNow()) {
    if (M.readOf(rec, pend, srcId, id, pages && pages.find(id))) return;
    if (!ctx.S.reach.ok || ctx.S.phase !== 'open') {
      owedRead = { src: srcId, id, at }; // a mark, not a queue: only the item still open when the PC answers
      return;
    }
    owedRead = null;
    const d = M.readDraft(srcId, id, M.readList(rec, pend, srcId));
    if (d) sendRecord({ ...d, at }, `read.${srcId}`, 'Marked read');
  }

  function setRead(row, srcId, read) {
    const d = M.markDraft(srcId, row.id, read, M.readList(rec, pend, srcId));
    if (d) sendRecord(d, `read.${srcId}`, read ? 'Mark read' : 'Mark unread');
  }

  function setFlag(row, srcId, on) {
    sendRecord(M.flagDraft(row, srcId, on, pcNow()), `flag.${row.id}`, on ? 'Flag' : 'Unflag');
  }

  async function markAll(s) {
    const unread = (pages ? pages.rows() : []).filter((r) => !M.readOf(rec, pend, s.id, r.id, r)).map((r) => r.id);
    if (!unread.length) return say(`Nothing unread in ${s.name} on this page.`);
    const yes = await ctx.confirm({ title: `Mark all of ${s.name} read?`, act: 'Mark read',
      body: `The ${unread.length} unread ${unread.length === 1 ? 'item' : 'items'} this page holds are marked read on every device.` });
    if (!yes) return;
    // made after the confirm, from the list as it stands then (a read meanwhile is kept)
    const d = M.markAllDraft(s.id, unread, M.readList(rec, pend, s.id));
    if (d) sendRecord(d, `read.${s.id}`, 'Mark all read');
  }

  // ---- program ops: refresh and browse (o · full; never queued, carrying the connection) ----

  function program(id, label, targets, params, onSent) {
    const a = act(id);
    if (!M.offered(a, ctx.S.level)) return;
    const w = { label, at: Date.now() };
    const k = ctx.sendProgram({ window: M.W, action: id, targets, ...(params ? { params } : {}) }, a.level, (ans) => {
      flying.delete(k);
      drawFlying();
      const bad = M.answerLine(label, ans);
      if (bad) return say(bad);
      if (onSent) onSent(ans);
    });
    if (k === null) return;
    flying.set(k, w);
    drawFlying();
  }

  function refresh(ids, label) {
    program('refresh', label, ids, null, () => say(`${label}: sent to the PC. The list moves when the PC has fetched.`));
  }

  function browse(kind, name) {
    if (!name) return;
    program('browse', `Browse ${kind === 'reddit' ? `r/${name}` : name.toLowerCase()}`, [], { kind, name }, (ans) => {
      const id = M.browsed(ans.result);
      if (!id) return say(`Browse: the PC answered without a source for ${name}.`);
      if (offer('recent')) {
        sendRecord(M.recentDraft(kind, kind === 'reddit' ? name.toLowerCase() : name, rec, pend), `recent.${kind}`, 'Keep the browse');
      }
      browseText = '';
      browseOpen = false;
      go({ at: 'src', src: id });
    });
  }

  /** What waits for an answer, with its age (no repeat, no time limit: spec §5 V3's class). */
  function drawFlying() {
    clearTimeout(flyTimer);
    if (!root) return;
    const now = Date.now();
    fill(els.flying, [...flying.values()].map((w) => h('p', { class: 'note', text: `${w.label} · sent, no answer from the PC yet · ${M.dur(now - w.at)}` })));
    if (flying.size) flyTimer = setTimeout(drawFlying, 1000);
  }

  // ---- the frame ----

  function redraw() {
    if (!root) return;
    drawHead();
    drawSources();
    drawList();
    drawReadBar();
    if (place.at === 'settings') drawSettings();
  }

  function layout() {
    const p = place.at;
    const reading = p === 'item' || p === 'comments' || p === 'num' || p === 'binge' || p === 'settings';
    root.classList.toggle('show-src', p === 'sources');
    root.classList.toggle('show-list', p === 'src' || p === 'flagged');
    root.classList.toggle('show-read', reading);
    root.classList.toggle('has-list', (p === 'item' || p === 'comments' || p === 'num') && (from === 'flagged' || !!pages));
  }

  function drawHead() {
    const s = (last && last.summary) || {};
    const away = !ctx.S.reach.ok && last;
    // out of reach: the last state with its age (spec §3.5's answer for the web: greyed, actions dim)
    fill(els.head, h('h1', { text: 'Feed' }),
      h('span', { class: 'tsum', text: [s.line, s.detail].filter(Boolean).join(' · ') }),
      away ? h('span', { class: 'note', text: `as the PC last described it at ${hhmm(s.asOf || lastAt || pcNow())}` }) : null);
  }

  // ---- the sources column ----

  function drawSources() {
    const now = pcNow();
    const open = place.src || null;
    const why = refusals.get(M.DESC);
    if (!last) {
      return fill(els.src, h('p', { class: 'note', text: why ? `The sources could not be read: ${why}` : 'Reading the sources from the PC…' }));
    }
    const shown = M.sourcesShown(last, (s) => M.pinOf(rec, pend, s), open);
    const nFlag = M.flaggedRows(rec, pend).length;
    const row = (s) => {
      const hl = M.healthOf(s, now);
      const binge = M.isBinge(s);
      const target = binge ? { at: 'binge', src: s.id } : { at: 'src', src: s.id };
      const cur = open === s.id && place.at !== 'sources';
      return h('li', null, link([
        h('span', { class: 'fd-sn' + ((s.newCount || 0) > 0 ? ' unread' : ''), text: s.name }),
        h('span', { class: 'fd-sl', 'data-sl': s.id, text: M.sourceLine(s, now, rec.value(M.W, `binge.${s.id}`)) }),
        hl ? h('span', { class: `fd-sh ${hl.cls}`, 'data-hl': s.id, text: hl.text }) : null,
      ], target, `src:${s.id}`, 'fd-s' + (cur ? ' current' : '')));
    };
    const all = offer('refresh') ? ctx.actionButton({ label: 'Refresh all', need: levelOf('refresh', 'full'), cls: 'quiet',
      onClick: () => refresh(shown.filter((s) => !M.isBinge(s)).map((s) => s.id), 'Refresh all') }) : null;
    keepFocus(els.src, 'data-key', () => fill(els.src,
      h('ul', { class: 'fd-sources' }, shown.map(row),
        h('li', { class: 'fd-sep' }),
        h('li', null, link([h('span', { class: 'fd-sn', text: 'Flagged' }), h('span', { class: 'fd-sl', text: nFlag ? `${nFlag} to read later` : 'nothing flagged' })],
          { at: 'flagged' }, 'flagged', 'fd-s' + (place.at === 'flagged' ? ' current' : ''))),
        h('li', null, link([h('span', { class: 'fd-sn', text: 'Settings' })], { at: 'settings' }, 'settings', 'fd-s' + (place.at === 'settings' ? ' current' : '')))),
      all ? h('div', { class: 'fd-sfoot' }, all) : null));
    tick();
  }

  /** A countdown moves each second, an age each half minute: their words change in place (seen in the read: a whole
   *  column drawn again each second could take a click from under the pointer and a typed name from under the caret). */
  function tick() {
    clearTimeout(ticker);
    if (!root || !last) return;
    const now = pcNow();
    for (const e of root.querySelectorAll('[data-hl]')) {
      const hl = M.healthOf(src(e.getAttribute('data-hl')) || {}, now);
      e.textContent = hl ? hl.text : '';
    }
    for (const e of root.querySelectorAll('[data-sl]')) {
      const s = src(e.getAttribute('data-sl'));
      if (s) e.textContent = M.sourceLine(s, now, e.closest && e.closest('.fd-lhead') ? null : rec.value(M.W, `binge.${s.id}`));
    }
    ticker = setTimeout(tick, M.counting(last, now) ? 1000 : 30000);
  }

  // ---- the list column: a source's items, or Flagged ----

  function setPages(srcId) {
    if (pages && pages.src === srcId) return;
    if (pages) for (const s of pages.held()) ctx.unsub(s);
    pages = srcId ? new M.Pages(srcId) : null;
    if (els) els.list.scrollTop = 0;
    if (pages) checkMore();
  }

  /** Asks the next page when the model says so: the first at once, the next near the list's end, a refused one again
   *  on its pacing. Never while the page is hidden. */
  function checkMore(force) {
    if (!pages || document.visibilityState === 'hidden') return;
    const l = els && els.list;
    const near = !!force || (l ? M.nearEnd(l.scrollTop, l.clientHeight, l.scrollHeight, ROW_PX) : false);
    const n = pages.next(near, Date.now());
    if (n === null) return;
    pages.ask(n);
    refusals.delete(pages.scope(n));
    ctx.sub(pages.scope(n));
    drawList();
  }

  function drawList() {
    if (!root) return;
    if (place.at === 'flagged' || (from === 'flagged' && (place.at === 'item' || place.at === 'num'))) return drawFlagged();
    if (!pages) {
      return fill(els.list, h('p', { class: 'note fd-hint', text: last ? 'Choose a source.' : '' }));
    }
    if (!els.list.querySelector('.fd-lhead') || els.list.dataset.src !== pages.src) {
      els.list.dataset.src = pages.src;
      els.lhead = h('div', { class: 'fd-lhead' });
      els.rows = h('div', { class: 'fd-rowbox' });
      fill(els.list, els.lhead, els.rows);
    }
    drawListHead();
    drawRows();
  }

  function drawListHead() {
    if (!pages || !els.lhead || place.at === 'flagged') return;
    const s = src(pages.src) || { id: pages.src, name: pages.src, kind: '' };
    const hl = M.healthOf(s, pcNow());
    const unread = pages.rows().filter((r) => !M.readOf(rec, pend, s.id, r.id, r)).length;
    const bk = M.browseKind(s);
    const pinned = M.pinOf(rec, pend, s);
    keepFocus(els.lhead, 'data-key', () => fill(els.lhead,
      h('div', { class: 'fd-lt' }, link('← Sources', { at: 'sources' }, 'l:back', 'fd-back'), h('h2', { text: s.name })),
      h('p', { class: 'note', 'data-sl': s.id, text: M.sourceLine(s, pcNow(), null) }),
      hl ? h('p', { class: `fd-sh ${hl.cls}`, 'data-hl': s.id, text: hl.text }) : null,
      h('div', { class: 'fd-acts' },
        offer('refresh') ? ctx.actionButton({ label: 'Refresh', need: levelOf('refresh', 'full'), onClick: () => refresh([s.id], `Refresh ${s.name}`) }) : null,
        offer('markAllRead') ? ctx.actionButton({ label: 'Mark all read', need: levelOf('markAllRead'), why: unread ? null : 'nothing unread on this page',
          onClick: () => markAll(s) }) : null,
        s.transient && offer(pinned ? 'unpin' : 'pin') ? ctx.actionButton({ label: pinned ? 'Unpin' : 'Pin', need: levelOf(pinned ? 'unpin' : 'pin'),
          onClick: () => sendRecord(M.pinDraft(s.id, !pinned), `pin.${s.id}`, pinned ? 'Unpin' : 'Pin') }) : null),
      bk && offer('browse') ? browseBox(bk) : null));
  }

  /** Browse (o · full): a subreddit typed, or one of Slashdot's sections, the name staged on the button (A.16). */
  function browseBox(kind) {
    const recents = M.recentsOf(rec, pend, kind);
    const box = h('details', { class: 'fd-browse', open: browseOpen, on: { toggle: () => { browseOpen = box.open; } } },
      h('summary', { 'data-key': 'browse', focus: true }, kind === 'reddit' ? 'Browse a subreddit' : 'Browse a section'));
    const a = act('browse');
    const opts = (a && a.params && a.params.choice && Array.isArray(a.params.choice.options)) ? a.params.choice.options.map((o) => (typeof o === 'object' ? [o.id, o.label ?? o.id] : [o, String(o).toLowerCase()])) : [];
    if (kind === 'reddit') {
      const goBtn = () => {
        const name = M.subName(browseText);
        const b = ctx.actionButton({ label: name ? M.browseLabel(kind, name) : 'Browse', need: levelOf('browse', 'full'), why: name ? null : 'type a subreddit name',
          onClick: () => browse('reddit', name) });
        b.dataset.key = 'browse:go';
        return b;
      };
      let btn = goBtn();
      // the name is staged on the button as it is typed; the field itself is never drawn again under the caret
      const input = h('input', { type: 'text', value: browseText, placeholder: 'subreddit', 'aria-label': 'subreddit', 'data-key': 'browse:q', focus: true, spellcheck: 'false',
        on: { input: (e) => { browseText = e.target.value; const nb = goBtn(); btn.replaceWith(nb); btn = nb; },
          keydown: (e) => { if (e.key === 'Enter' && M.subName(browseText)) browse('reddit', M.subName(browseText)); } } });
      input.value = browseText;
      box.append(h('div', { class: 'fd-bform' }, h('span', { class: 'note', text: 'r/' }), input, btn));
    } else {
      if (!opts.length) box.append(h('p', { class: 'note', text: 'The PC listed no sections to browse.' }));
      else {
        const cur = opts.some(([id]) => id === section) ? section : opts[0][0];
        const sel = h('select', { 'aria-label': 'section', 'data-key': 'browse:sec', focus: true, on: { change: (e) => { section = e.target.value; drawListHead(); } } },
          opts.map(([id, label]) => h('option', { value: id, selected: id === cur }, label)));
        const btn = ctx.actionButton({ label: M.browseLabel(kind, (opts.find(([id]) => id === cur) || [cur, cur])[1]), need: levelOf('browse', 'full'), onClick: () => browse('slashdot', cur) });
        btn.dataset.key = 'browse:go';
        box.append(h('div', { class: 'fd-bform' }, sel, btn));
      }
    }
    if (recents.length) {
      box.append(h('div', { class: 'recents' }, h('span', { class: 'note', text: 'Recent:' }),
        recents.map((r) => ctx.actionButton({ label: kind === 'reddit' ? `r/${r}` : r.toLowerCase(), need: levelOf('browse', 'full'), cls: 'chip',
          onClick: () => browse(kind, r) }))));
    }
    return box;
  }

  function drawRows() {
    const s = src(pages.src);
    const now = pcNow();
    const st = pages.status();
    const rows = pages.rows();
    // the item the reading column shows (a comic opened by its number names its id in its description)
    const cur = place.at === 'item' || place.at === 'comments' ? place.id : art && art.d && art.d.item ? art.d.item.id : null;
    const end = st.kind === 'first' ? 'Reading the items from the PC…' : st.kind === 'loading' ? 'loading the next page…'
      : st.kind === 'empty' ? 'No items yet.' : st.kind === 'end' ? `The end: ${rows.length} ${rows.length === 1 ? 'item' : 'items'}.`
        : st.kind === 'failed' ? `Page ${st.page + 1} could not be read: ${st.reason}${st.retry ? ' — asked again every 5 s.' : ''}` : null;
    keepFocus(els.rows, 'data-key', () => fill(els.rows,
      rows.length ? h('ul', { class: 'fd-rows' }, rows.map((r) => {
        const read = M.readOf(rec, pend, pages.src, r.id, r);
        const flag = M.flagOf(rec, pend, r.id, r);
        return h('li', { class: 'fd-row' + (read ? ' read' : '') + (r.stale ? ' stale' : '') + (cur === r.id ? ' current' : '') },
          link([flag ? h('span', { class: 'fd-flag', 'aria-label': 'flagged', title: 'flagged' }, icon('flag')) : null,
            h('span', { class: 'fd-t', text: r.title }), h('span', { class: 'fd-tail', text: M.tailOf(r, s, now) })],
          { at: 'item', src: pages.src, id: r.id }, `item:${r.id}`, 'fd-open'));
      })) : null,
      end ? h('p', { class: 'note tend' + (st.kind === 'failed' ? ' failed' : ''), text: end }) : null,
      st.kind === 'failed' && !st.retry ? h('button', { 'data-key': 'retry', focus: true, on: { click: () => { pages.failed = null; checkMore(true); } } }, 'Try again') : null));
  }

  function drawFlagged() {
    const rows = M.flaggedRows(rec, pend);
    const now = pcNow();
    els.list.dataset.src = '';
    els.lhead = null;
    keepFocus(els.list, 'data-key', () => fill(els.list,
      h('div', { class: 'fd-lhead' }, h('div', { class: 'fd-lt' }, link('← Sources', { at: 'sources' }, 'l:back', 'fd-back'), h('h2', { text: 'Flagged' })),
        h('p', { class: 'note', text: rows.length ? `${rows.length} to read later, newest flag first` : '' })),
      rows.length ? h('ul', { class: 'fd-rows' }, rows.map((f) => h('li', { class: 'fd-row' + (place.id === f.id ? ' current' : '') },
        link([h('span', { class: 'fd-flag' }, icon('flag')), h('span', { class: 'fd-t', text: f.title }),
          h('span', { class: 'fd-tail', text: [nameOf(f.src), f.at ? `flagged ${M.ago(f.at, now)}` : ''].filter(Boolean).join(' · ') })],
        { at: 'item', src: f.src, id: f.id }, `item:${f.id}`, 'fd-open'))))
        : h('p', { class: 'note', text: 'Nothing flagged. Flag an item from its page to read it later.' })));
  }

  // ---- the reading column ----

  function leaveRead() {
    if (art) {
      saveArticle();
      saveComments();
      clearTimeout(art.timer);
      if (art.scope) ctx.unsub(art.scope);
      art = null;
    }
    if (bg) {
      saveBinge();
      clearTimeout(bg.timer);
      for (const s of bg.b.held()) ctx.unsub(s);
      bg = null;
    }
  }

  function openRead(input) {
    const p = place;
    if (p.at === 'binge') return openBinge(p.src, input);
    if (p.at === 'settings') return drawSettings();
    if (p.at !== 'item' && p.at !== 'comments' && p.at !== 'num') return fill(els.read);
    const row = p.id && pages ? pages.find(p.id) : null;
    const kind = p.at === 'comments' ? 'comments' : p.at === 'num' || M.isComic(row, src(p.src)) ? 'comic' : 'article';
    const scope = p.at === 'comments' ? M.commentsScope(p.src, p.id) : p.at === 'num' ? M.numScope(p.src, p.n) : kind === 'comic' ? M.comicScope(p.src, p.id) : M.itemScope(p.src, p.id);
    const id = p.id || null;
    art = { kind, src: p.src, id, n: p.n || null, scope, d: ctx.desc(scope) || null, why: null, input: !!input,
      follow: new M.Follow((v) => !!v && id !== null && v.item === id, M.placeKey), timer: null, moved: false, entered: false, blockEls: [], els: {} };
    els.read.scrollTop = 0; // seen in the read: a new item kept the last one's scroll
    refusals.delete(scope);
    ctx.sub(scope);
    // spec §3.2: an input that opens an item is the reading itself; a page load, a reload or a restore marks nothing
    if (input && p.at === 'item') readOnInput(p.src, p.id);
    drawRead();
  }

  function drawRead() {
    if (!art) return;
    art.els = { bar: h('div', { class: 'fd-bar' }), body: h('div', { class: 'fd-body' }) };
    fill(els.read, art.els.bar, art.els.body);
    drawReadBar();
    if (art.kind === 'comments') drawComments();
    else if (art.kind === 'comic') drawComic();
    else drawArticle();
  }

  /** The item the reading column shows: the description's, else the held row, else what Flagged knows. */
  function itemNow() {
    if (!art) return null;
    const d = art.d;
    const it = d && d.item ? d.item : null;
    const row = art.id && pages ? pages.find(art.id) : null;
    const fl = art.id ? M.flaggedRows(rec, pend).find((f) => f.id === art.id) : null;
    const base = it || row || (fl ? { id: fl.id, title: fl.title, link: fl.link, published: fl.published, itemKind: fl.itemKind } : null);
    return base ? { ...base, id: base.id || art.id } : null;
  }

  function drawReadBar() {
    if (!art || !art.els.bar) return;
    const it = itemNow();
    const back = M.parentOf(place, from);
    const s = src(art.src);
    const backLabel = place.at === 'comments' ? '← The article' : from === 'flagged' ? '← Flagged' : `← ${s ? s.name : 'Items'}`;
    const kids = [back ? link(backLabel, back, 'r:back', 'fd-up') : null];
    if (it && art.kind !== 'comments') {
      const flag = M.flagOf(rec, pend, it.id, it);
      const read = M.readOf(rec, pend, art.src, it.id, it);
      if (offer(flag ? 'unflag' : 'flag')) kids.push(ctx.actionButton({ label: flag ? 'Unflag' : 'Flag', need: levelOf(flag ? 'unflag' : 'flag'), onClick: () => setFlag(it, art.src, !flag) }));
      if (offer(read ? 'unread' : 'markRead')) kids.push(ctx.actionButton({ label: read ? 'Mark unread' : 'Mark read', need: levelOf(read ? 'unread' : 'markRead'), onClick: () => setRead(it, art.src, !read) }));
      if (art.kind === 'article') {
        const n = typeof it.comments === 'number' ? it.comments : -1;
        const why = it.hasComments === false ? 'comments are not reachable for this source' : n === 0 ? 'no comments' : null;
        kids.push(ctx.actionButton({ label: n > 0 ? `Comments (${n})` : 'Comments', need: 'view', why, onClick: () => go({ at: 'comments', src: art.src, id: it.id }) }));
        const next = pages && from !== 'flagged' ? pages.after(it.id) : null;
        kids.push(ctx.actionButton({ label: 'Next item', need: 'view', why: next ? null : 'no next item on this page',
          onClick: () => go({ at: 'item', src: art.src, id: next.id }) }));
      }
      // B5: a plain link to the original page, http or https only, in a new tab with no referrer
      if (M.safeHref(it.link)) kids.push(outLink(M.safeHref(it.link), 'Open the original page', 'Damage'));
    }
    keepFocus(art.els.bar, 'data-key', () => fill(art.els.bar, kids));
  }

  // ---- an article (spec §3.3 `feed/item:`) ----

  function drawArticle() {
    const d = art.d;
    const body = art.els.body;
    const it = itemNow();
    const s = src(art.src);
    const why = art.why;
    const head = [h('h1', { class: 'fd-title', text: it ? it.title : 'An item' }), it ? h('p', { class: 'fd-by', text: M.byline(it, s, pcNow()) }) : null];
    if (!d || !d.document) {
      const state = why ? `This article could not be read: ${why}` : d && d.note ? d.note : 'Fetching the article on the PC…';
      return fill(body, h('article', { class: 'text fd-text' }, head, h('p', { class: `note fd-wait${why ? ' failed' : ''}`, text: state })));
    }
    const doc = d.document;
    const blocks = M.blocksOf(doc);
    const els2 = blocks.map((b) => {
      const el = blockEl(b, { at: 0, where: 'Damage', headingBase: 1, image: imageBlock });
      el.dataset.b = b.i;
      return el;
    });
    art.blockEls = els2;
    const src0 = M.safeHref(doc.sourceUrl);
    const srcHost = src0 && src0 !== M.safeHref(it && it.link) ? (() => { try { return new URL(src0).host; } catch (e) { return src0; } })() : null;
    fill(body, h('article', { class: 'text fd-text' }, head,
      d.extracted === false ? h('p', { class: 'note fd-floor', text: `This is the feed's own text: ${d.note || 'the page did not extract'}.` }) : null,
      srcHost ? h('p', { class: 'fd-source' }, 'Source: ', outLink(src0, srcHost, 'Damage')) : null,
      els2.length ? els2 : h('p', { class: 'note', text: 'The article has no text.' }),
      h('p', { class: 'end', text: 'The end of the article.' })));
    enterArticle();
  }

  function imageBlock(b) {
    const url = M.blobUrl(b.ref);
    if (!images()) return h('p', { class: 'note fd-noimg', text: 'A picture, not shown: Images is off on this browser (Settings).' });
    if (!url) return h('p', { class: 'note fd-noimg', text: 'A picture the PC has no copy of.' });
    const img = h('img', { src: url, alt: b.alt || '', loading: 'lazy', on: { error: () => img.replaceWith(h('span', { class: 'note', text: 'Picture not shown: the PC could not serve it.' })) } });
    return h('figure', { class: 'fd-fig' }, img, b.alt ? h('figcaption', { class: 'note', text: b.alt }) : null);
  }

  /** The entry: the shared place when it is this article's, once the article and the record are both here (an address
   *  that names a place shows it; it counts as input only when input routed there). */
  function enterArticle() {
    if (!art || art.kind !== 'article' || art.entered || !art.d || !art.d.document || !ready()) return;
    art.entered = true;
    const v = art.follow.enter(rec.item(M.W, 'place'));
    const a = M.parseAnchor(anchor);
    const at = a && a.b !== undefined ? a : v;
    if (at) showBlock(at.b || 0, at.o || 0);
    if (a && a.b !== undefined && art.input) {
      art.follow.inputAt = pcNow();
      art.moved = true;
      armSave();
    }
  }

  /** How much of the column's top a sticky bar covers (a desk); on a phone the bar scrolls away with the text. */
  function cover(bar) {
    if (!bar || typeof getComputedStyle !== 'function' || getComputedStyle(bar).position !== 'sticky') return 0;
    return bar.getBoundingClientRect().height;
  }

  function showBlock(b, o) {
    art.touch = false; // the page's own move: not his scroll
    const el = art.blockEls[Math.max(0, Math.min(b, art.blockEls.length - 1))];
    const pane = els.read;
    if (!el) return;
    let top = el.getBoundingClientRect().top;
    if (o > 0 && typeof document.createRange === 'function') {
      let r = null;
      for (const c of el.querySelectorAll('[data-c]')) if (Number(c.dataset.c) <= o) r = c;
      const t = r && r.firstChild && r.firstChild.nodeType === 3 ? r.firstChild : null;
      if (t && t.length > 0) {
        const range = document.createRange();
        const i = Math.max(0, Math.min(o - Number(r.dataset.c), t.length - 1));
        range.setStart(t, i);
        range.setEnd(t, i + 1);
        const rs = range.getClientRects();
        if (rs.length) top = rs[0].top;
      }
    }
    pane.scrollTop += top - pane.getBoundingClientRect().top - TOP_PAD - cover(art.els.bar);
  }

  /** The block and character at the top of the reading column. */
  function articleTop() {
    const pane = els.read.getBoundingClientRect();
    const y = pane.top + cover(art.els.bar) + TOP_PAD + 2;
    const list = art.blockEls;
    let lo = 0;
    let hi = list.length - 1;
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      if (list[mid].getBoundingClientRect().bottom <= y) lo = mid + 1;
      else hi = mid;
    }
    const el = list[lo];
    if (!el) return { b: 0, o: 0 };
    let o = 0;
    const x = el.getBoundingClientRect().left + 2;
    const cp = document.caretPositionFromPoint ? document.caretPositionFromPoint(x, y) : document.caretRangeFromPoint ? document.caretRangeFromPoint(x, y) : null;
    const node = cp ? cp.offsetNode || cp.startContainer : null;
    const off = cp ? (cp.offset ?? cp.startOffset) : 0;
    const host = node && node.nodeType === 3 ? node.parentElement : null;
    if (host && host.dataset && host.dataset.c !== undefined && el.contains(host)) o = Number(host.dataset.c) + off;
    return { b: Number(el.dataset.b) || 0, o };
  }

  function armSave() {
    if (!art && !bg) return;
    const t = art || bg;
    clearTimeout(t.timer);
    t.timer = setTimeout(() => (art ? (art.kind === 'comments' ? saveComments() : saveArticle()) : saveBinge()), M.SAVE_MS);
  }

  /** A save point: the place at the top, as a position op carrying its input's time, when input moved it (B1 `place`). */
  function saveArticle() {
    if (art) art.touch = false;
    if (!art || art.kind !== 'article' || !art.moved || !art.entered) return;
    clearTimeout(art.timer);
    const why = ctx.canAct('view');
    if (why) return ctx.sayOnce('feed-unsaved', `The place in this article is not saved: ${why}.`, { from: 'Feed' });
    art.moved = false;
    const t = articleTop();
    const value = M.placeValue(art.id, t.b, t.o, art.d.document.extractor ?? art.d.document.ver);
    if (!art.follow.save(value)) return;
    const a = art;
    ctx.sendQueued(M.placeOp(value, a.follow.inputAt), (ans) => {
      // an op the connection's end left unanswered goes again at the next welcome (onReach), with its input's time
      if (ans.a === 'refused' && ans.code === 'ended') { if (a === art && a.follow.unsent(value)) a.moved = true; }
      else if (ans.a === 'refused') say(`This place was not applied: ${ans.reason}`);
    }, { need: levelOf('place', 'view') });
  }

  /** Another device's input moved the place shown: the page goes there, sending nothing. */
  function followArticle() {
    const v = art.follow.follow(rec.item(M.W, 'place'), ctx.S.device);
    if (!v) return;
    clearTimeout(art.timer);
    art.moved = false;
    showBlock(v.b || 0, v.o || 0);
  }

  // ---- comments (spec §3.3 `feed/comments:`): a thread, its place this browser's own (A.7) ----

  function drawComments() {
    const d = art.d;
    const it = itemNow();
    const body = art.els.body;
    const head = h('h1', { class: 'fd-title', text: it ? `Comments: ${it.title}` : 'Comments' });
    if (!d) return fill(body, h('div', { class: 'fd-cwrap' }, head, h('p', { class: `note fd-wait${art.why ? ' failed' : ''}`, text: art.why ? `The comments could not be read: ${art.why}` : 'Fetching the comments on the PC…' })));
    const entries = Array.isArray(d.entries) ? d.entries : [];
    const now = pcNow();
    art.blockEls = [];
    fill(body, h('div', { class: 'fd-cwrap' }, head, entries.length ? h('ol', { class: 'fd-thread' }, entries.map((c) => {
      const hd = M.commentHead(c, now);
      const li = h('li', { class: `fd-c d${Math.max(0, Math.min(8, Number(c.depth) || 0))}`, 'data-entry': String(c.id) },
        h('div', { class: 'fd-ch' }, hd.title ? h('strong', { text: hd.title }) : null, h('span', { class: 'note', text: hd.line })),
        M.blocksOf({ blocks: c.blocks || [] }).map((b) => blockEl(b, { at: 0, where: 'Damage', headingBase: 2, image: imageBlock })));
      art.blockEls.push(li);
      return li;
    })) : h('p', { class: 'note', text: d.note || 'No comments.' })));
    if (!art.entered) {
      art.entered = true;
      const at = M.commentPlace(store.get(M.KEYS.comments), art.id);
      const el = at ? art.blockEls.find((x) => x.getAttribute('data-entry') === at) : null;
      if (el) els.read.scrollTop += el.getBoundingClientRect().top - els.read.getBoundingClientRect().top - TOP_PAD;
    }
  }

  function saveComments() {
    if (art) art.touch = false;
    if (!art || art.kind !== 'comments' || !art.moved) return;
    art.moved = false;
    const y = els.read.getBoundingClientRect().top + TOP_PAD + 2;
    const el = art.blockEls.find((x) => x.getBoundingClientRect().bottom > y) || art.blockEls[0];
    if (el) store.set(M.KEYS.comments, M.putCommentPlace(store.get(M.KEYS.comments), art.id, el.getAttribute('data-entry')));
  }

  // ---- a comic (spec §2.4: the original, uninverted, at its own size; alt text; SMBC's bonus; the bar) ----

  function drawComic() {
    const c = art.d;
    const body = art.els.body;
    if (!c) return fill(body, h('div', { class: 'fd-comicwrap' }, h('p', { class: `note fd-wait${art.why ? ' failed' : ''}`, text: art.why ? `This comic could not be read: ${art.why}` : 'Fetching the comic on the PC…' })));
    const it = c.item || {};
    if (art.input && !art.readSent && it.id) {
      art.readSent = true;
      readOnInput(art.src, it.id);
    }
    const title = [c.num ? `#${c.num}` : '', it.title || ''].filter(Boolean).join(' · ');
    const pic = (ref, alt, cls) => {
      const url = M.blobUrl(ref);
      if (!url) return h('p', { class: 'note', text: 'The PC has no copy of this picture.' });
      const img = h('img', { class: cls, src: url, alt, on: { error: () => img.replaceWith(h('p', { class: 'note', text: 'Picture not shown: the PC could not serve it.' })) } });
      return img;
    };
    const bar = h('nav', { class: 'fd-cbar', 'aria-label': 'Comics' }, M.BAR.map(([id, label]) => {
      const t = M.barTarget(c, id, art.src);
      const b = ctx.actionButton({ label, need: 'view', why: t.why || null, onClick: () => go(M.placeOf(t.target)) });
      b.dataset.key = `bar:${id}`;
      return b;
    }));
    fill(body, h('div', { class: 'fd-comicwrap' }, h('h1', { class: 'fd-title', text: title || 'A comic' }),
      h('figure', { class: 'fd-comic' }, pic(c.image, it.title || 'the comic', 'fd-strip'), c.alt ? h('figcaption', { class: 'fd-alt', text: c.alt }) : null),
      c.bonus ? h('figure', { class: 'fd-comic fd-bonus' }, h('figcaption', { class: 'note', text: 'Bonus panel' }), pic(c.bonus, 'the bonus panel', 'fd-strip')) : null,
      bar));
  }

  // ---- the binge: one long page of episodes around the shared place (spec §3.3 `feed/binge:`) ----

  function openBinge(srcId, input) {
    const b = new M.Binge(srcId);
    // the binge's place is one register per source: any value of it is this archive's
    bg = { b, src: srcId, follow: new M.Follow((v) => !!v && typeof v.ep === 'number', M.bingeKey), timer: null, moved: false, entered: false, input: !!input,
      want: null, sections: new Map(), els: {} };
    bg.els = { bar: h('div', { class: 'fd-bar' }), top: h('p', { class: 'note fd-bl' }),
      list: h('div', { class: 'fd-binge' }), bottom: h('p', { class: 'note fd-bl' }) };
    fill(els.read, bg.els.bar, bg.els.top, bg.els.list, bg.els.bottom);
    els.read.scrollTop = 0;
    drawBingeBar();
    enterBinge();
  }

  function enterBinge() {
    if (!bg || bg.entered || !ready()) {
      if (bg && !bg.entered) drawBingeEnds();
      return;
    }
    bg.entered = true;
    const v = bg.follow.enter(rec.item(M.W, `binge.${bg.src}`));
    const a = M.parseAnchor(anchor);
    const at = a && a.ep !== undefined ? a : v && v.ep ? v : null;
    jumpBinge(at ? at.ep : 1, at ? at.f || 0 : 0, !!(a && a.ep !== undefined && bg.input));
  }

  /** A new window around `ep` (an entry, a follow, a jump, First, Latest): the page shows `ep` at `f`. [input]: a jump
   *  he asked for, stamped now and sent at its save point. */
  function jumpBinge(ep, f, input) {
    for (const s of bg.b.held()) ctx.unsub(s);
    bg.b = new M.Binge(bg.src);
    bg.sections.clear();
    fill(bg.els.list);
    bg.want = { ep, f };
    clearTimeout(bg.timer);
    bg.moved = !!input;
    if (input) bg.follow.inputAt = pcNow();
    askBinge(ep);
  }

  /** Another device's input moved the archive's place: the page goes there (an episode drawn already, by a scroll; else
   *  a new window around it), sending nothing. */
  function followBinge() {
    const v = bg.follow.follow(rec.item(M.W, `binge.${bg.src}`), ctx.S.device);
    if (!v) return;
    if (!bg.sections.has(v.ep)) return jumpBinge(v.ep, v.f || 0, false);
    clearTimeout(bg.timer);
    bg.moved = false;
    bg.want = { ep: v.ep, f: v.f || 0 };
    settleBinge(null);
  }

  function askBinge(ep) {
    const s = bg.b.ask(ep);
    if (!s) return;
    refusals.delete(s);
    ctx.sub(s);
    drawBingeEnds();
  }

  function drawBingeBar() {
    if (!bg) return;
    const s = src(bg.src);
    const b = bg.b;
    const total = b.total ?? (s && s.bingeTotal) ?? null;
    const top = bingeTop();
    const input = h('input', { type: 'number', min: b.first ?? 1, max: b.last ?? total ?? undefined, placeholder: 'episode', 'aria-label': 'episode number', 'data-key': 'b:ep', focus: true, class: 'fd-epin',
      on: { input: (e) => { bg.epText = e.target.value; } } });
    input.value = bg.epText || '';
    const jump = () => {
      const why = M.jumpWhy(input.value, b);
      if (why) return say(why);
      jumpBinge(Number(input.value), 0, true);
    };
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') jump(); });
    keepFocus(bg.els.bar, 'data-key', () => fill(bg.els.bar, link('← Sources', { at: 'sources' }, 'r:back', 'fd-back'),
      h('h2', { class: 'fd-btitle', text: s ? s.name : bg.src }),
      (bg.els.note = h('span', { class: 'note', text: bingeNote(top, total) })),
      h('span', { class: 'spacer' }),
      ends('First', 'b:first', b.first), ends('Latest', 'b:latest', b.last),
      h('span', { class: 'fd-jump' }, input, h('button', { 'data-key': 'b:go', focus: true, on: { click: jump } }, 'Go'))));
  }

  const bingeNote = (top, total) => (top && total ? `page ${top.ep} of ${total}` : total ? `${total} pages` : '');

  /** First or Latest: dim with why until the archive's range is known. */
  function ends(label, key, ep) {
    const b = ctx.actionButton({ label, need: 'view', why: ep === null ? 'the archive is still being read from the PC' : null, onClick: () => jumpBinge(ep, 0, true) });
    b.dataset.key = key;
    return b;
  }

  function drawBingeEnds() {
    if (!bg) return;
    const b = bg.b;
    const fail = b.failure();
    bg.els.top.textContent = fail && !b.entries.size ? `The archive could not be read: ${fail}` : b.entries.size === 0 ? 'Reading the archive from the PC…'
      : b.atFirst ? 'The first episode.' : b.busy ? 'loading episodes…' : 'Earlier episodes load as you scroll up.';
    bg.els.bottom.textContent = fail && b.entries.size ? `More could not be read: ${fail}` : b.entries.size === 0 ? ''
      : b.atLast ? 'The latest episode.' : b.busy ? 'loading episodes…' : 'More episodes load as you scroll down.';
    bg.els.top.classList.toggle('failed', !!fail);
  }

  function episodeEl(e) {
    const head = h('h3', { class: 'fd-eph' }, h('span', { text: `Episode ${e.num}${e.title ? ` · ${e.title}` : ''}` }),
      e.date ? h('span', { class: 'note', text: new Date(e.date).toISOString().slice(0, 10) }) : null);
    let pic;
    if (e.state === 'failed') pic = h('p', { class: 'note failed', text: `This episode's picture could not be found: ${e.reason || 'the PC gave no reason'}` });
    else if (!e.image || e.state === 'pending') pic = h('p', { class: 'note fd-pending', text: "Finding this episode's picture on the PC…" });
    else {
      const url = M.blobUrl(e.image);
      // a picture not loaded yet holds a screen's height (`.fd-ep img:not(.loaded)`), so the window grows by what is
      // seen and never asks the whole archive while its pictures are on their way
      pic = url ? h('img', { class: 'fd-strip', src: url, alt: `Episode ${e.num}`, loading: 'lazy', on: {
        load: () => { pic.classList.add('loaded'); settleBinge(e.num); },
        error: () => pic.replaceWith(h('p', { class: 'note', text: 'Picture not shown: the PC could not serve it.' })) } })
        : h('p', { class: 'note', text: 'The PC has no copy of this picture.' });
    }
    return h('section', { class: 'fd-ep', 'data-ep': String(e.num) }, head, pic);
  }

  function gotBinge(scope, d) {
    const b = bg.b;
    const before = b.lo;
    const pane = els.read;
    const ref = before !== null ? bg.sections.get(before) : null;
    const refTop = ref ? ref.getBoundingClientRect().top : null;
    const changed = b.got(scope, d);
    for (const n of changed) {
      const e = b.entries.get(n);
      const el = episodeEl(e);
      const old = bg.sections.get(n);
      if (old) old.replaceWith(el);
      else {
        const after = [...bg.sections.keys()].filter((k) => k > n).sort((x, y) => x - y)[0];
        if (after !== undefined) bg.els.list.insertBefore(el, bg.sections.get(after));
        else bg.els.list.append(el);
      }
      bg.sections.set(n, el);
    }
    // earlier episodes put above keep the view where it was
    if (ref && refTop !== null) pane.scrollTop += ref.getBoundingClientRect().top - refTop;
    settleBinge(null);
    drawBingeEnds();
    drawBingeBar();
    bingeMore();
  }

  /** The entry's place, shown once its episode is drawn (and again as its picture loads, until input moves the page). */
  function settleBinge(num) {
    if (!bg || !bg.want) return;
    const el = bg.sections.get(bg.want.ep) || (bg.b.entries.size ? bg.sections.get(bg.b.list().find((e) => e.num >= bg.want.ep)?.num) : null);
    if (!el || (num !== null && num !== bg.want.ep)) return;
    const r = el.getBoundingClientRect();
    els.read.scrollTop += r.top - els.read.getBoundingClientRect().top - cover(bg.els.bar) + (bg.want.f || 0) * r.height;
    bg.touch = false; // the page's own move: not his scroll
    if (bg.moved) armSave();
  }

  /** The episode at the top of the column and how far into it. */
  function bingeTop() {
    if (!bg || !bg.sections.size) return null;
    const y = els.read.getBoundingClientRect().top + cover(bg.els.bar) + TOP_PAD;
    let best = null;
    for (const [n, el] of [...bg.sections].sort((a, b) => a[0] - b[0])) {
      const r = el.getBoundingClientRect();
      best = { ep: n, f: r.height > 0 ? (y - r.top) / r.height : 0 };
      if (r.bottom > y) break;
    }
    return best;
  }

  function saveBinge() {
    if (bg) bg.touch = false;
    if (!bg || !bg.moved || !bg.entered) return;
    clearTimeout(bg.timer);
    const why = ctx.canAct('view');
    if (why) return ctx.sayOnce('feed-unsaved', `The place in the archive is not saved: ${why}.`, { from: 'Feed' });
    const t = bingeTop();
    if (!t) return;
    bg.moved = false;
    const value = M.bingeValue(t.ep, t.f);
    if (!bg.follow.save(value)) return;
    const g = bg;
    ctx.sendQueued(M.bingeOp(g.src, value, g.follow.inputAt), (ans) => {
      if (ans.a === 'refused' && ans.code === 'ended') { if (g === bg && g.follow.unsent(value)) g.moved = true; }
      else if (ans.a === 'refused') say(`This place was not applied: ${ans.reason}`);
    }, { need: levelOf('binge', 'view') });
  }

  /** More episodes as the scroll nears either end. */
  function bingeMore() {
    if (!bg || !bg.b.entries.size) return;
    const p = els.read;
    const view = p.clientHeight || 0;
    if (p.scrollTop < view * 1.5) {
      const ep = bg.b.moreBefore();
      if (ep !== null) askBinge(ep);
    }
    if (p.scrollHeight - (p.scrollTop + view) < view * 1.5) {
      const ep = bg.b.moreAfter();
      if (ep !== null) askBinge(ep);
    }
  }

  // ---- the settings (spec §3.6: the shared and glasses rows; Images this browser's own) ----

  function drawSettings() {
    const rows = ((last && last.settings) || []).filter((r) => r.scope !== 'device');
    const setRow = (row) => {
      const v = M.rowValue(row, rec, pend);
      const need = row.level || 'control';
      const label = h('span', { text: row.label });
      const words = row.type === 'bool' ? (v ? 'on' : 'off') : (M.optionsOf(row).find(([id]) => String(id) === String(v)) || [v, String(v)])[1];
      if (!atLeast(ctx.S.level, need)) return h('div', { class: 'trow' }, label, h('span', { text: words }), h('span', { class: 'note', text: `changing it needs level ${need}` }));
      const why = ctx.canAct(need);
      const send = (value) => sendRecord(M.setDraft(row, value), row.id, row.label);
      const key = `set:${row.id}`;
      const ctl = row.type === 'bool'
        ? h('input', { type: 'checkbox', checked: !!v, 'data-key': key, focus: true, class: why ? 'dim' : null, 'data-why': why, on: { change: (e) => send(!!e.target.checked) } })
        : h('select', { 'data-key': key, focus: true, class: why ? 'dim' : null, 'data-why': why, on: { change: (e) => {
          const o = M.optionsOf(row).find(([id]) => String(id) === e.target.value);
          send(o ? o[0] : e.target.value);
        } } }, M.optionsOf(row).map(([id, l]) => h('option', { value: id, selected: String(id) === String(v) }, l)));
      return h('label', { class: 'trow' }, label, ctl, row.scope === 'glasses' ? h('span', { class: 'note', text: 'the glasses' }) : null);
    };
    keepFocus(els.read, 'data-key', () => fill(els.read, h('div', { class: 'fd-settings' },
      h('div', { class: 'fd-lt' }, link('← Sources', { at: 'sources' }, 'r:back', 'fd-back'), h('h2', { text: 'Feed settings' })),
      h('p', { class: 'note', text: 'Shared rows apply on every device; rows marked "the glasses" change only how the glasses draw Feed.' }),
      last ? rows.map(setRow) : h('p', { class: 'note', text: 'Reading the settings from the PC…' }),
      h('label', { class: 'trow' }, h('span', { text: 'Images (this browser)' }),
        h('input', { type: 'checkbox', checked: images(), 'data-key': 'set:images', focus: true, on: { change: (e) => { store.set(M.KEYS.images, e.target.checked ? 'on' : 'off'); } } }),
        h('span', { class: 'note', text: "pictures inside articles; comics are always drawn" })))));
  }

  // ---- the place as the address changes ----

  function show(r, input, first) {
    const p = M.placeOf(r.target);
    if (p.bad) say(`This address names nothing in Feed (${p.bad}); here are the sources.`);
    if (!first && M.targetOf(place) === M.targetOf(p) && !r.anchor) return;
    anchor = r.anchor || null;
    leaveRead();
    place = p;
    // the list an item was opened from: a row of Flagged says so on its click; any other list place starts afresh
    if (p.at !== 'item' && p.at !== 'comments' && p.at !== 'num') from = p.at === 'flagged' ? 'flagged' : null;
    const ctxSrc = p.at === 'src' ? p.src : (p.at === 'item' || p.at === 'comments' || p.at === 'num') && from !== 'flagged' ? p.src : null;
    setPages(ctxSrc);
    // spec §3.2 `seen`: a list shown after input (a click, Back, back and forward), never a load or a restore
    seenOwed = p.at === 'src' && input ? p.src : null;
    layout();
    drawSources();
    drawList();
    openRead(input);
    sendSeen();
  }

  /** `seen` once a page of a source opened by input is shown (spec §3.2). */
  function sendSeen() {
    if (!seenOwed || !pages || pages.src !== seenOwed || !ready() || !offer('seen')) return;
    const rows = pages.rows();
    if (!rows.length) return;
    const d = M.seenDraft(seenOwed, rows, rec);
    seenOwed = null;
    if (d) ctx.sendQueued(d, (ans) => { if (ans.a === 'refused' && ans.code !== 'ended') say(`New-item mark: ${ans.reason}`); }, { need: levelOf('seen', 'view'), quiet: true });
  }

  // ---- input and the page's own moves ----

  // L1 R2 B1's rule, Reader's (S1-3 fix B5): an input of his is stamped once, when it is made, and starts a gesture; a
  // scroll counts as his only while that gesture is live (until its save point) — a click (Flag, Open the original), a
  // resize, a reflow or the page's own move never marks the place as moved
  const onInput = () => {
    for (const t of [art, bg]) if (t) {
      t.touch = true;
      if (t.follow) t.follow.inputAt = pcNow();
    }
    armSave();
  };
  const onKey = (e) => {
    if (['PageDown', 'PageUp', ' ', 'ArrowDown', 'ArrowUp', 'Home', 'End'].includes(e.key)) onInput();
  };
  const onReadScroll = () => {
    if (bg) bingeMore();
    for (const t of [art, bg]) if (t && t.touch) {
      t.moved = true;
      if (t === bg) bg.want = null; // his scroll moved the page: the entry's place is not put back as pictures load
      armSave();
    }
    if (bg) drawBingeBarSoon();
  };
  let barSoon = null;
  const drawBingeBarSoon = () => {
    if (barSoon !== null) return;
    barSoon = setTimeout(() => {
      barSoon = null;
      if (bg && bg.els.note) bg.els.note.textContent = bingeNote(bingeTop(), bg.b.total ?? (src(bg.src) || {}).bingeTotal ?? null);
    }, 250);
  };
  const onListScroll = () => checkMore();
  const onHide = () => { saveArticle(); saveBinge(); saveComments(); };
  const onVisibility = () => {
    if (document.visibilityState === 'hidden') onHide();
    else checkMore();
  };

  return {
    mount(r, o = {}) {
      root = h('section', { class: 'page feed' });
      els = { head: h('div', { class: 'thead-line fd-head' }), flying: h('div', { class: 'tflying', role: 'status' }), src: h('nav', { class: 'fd-src', 'aria-label': 'Sources' }),
        list: h('section', { class: 'fd-list', 'aria-label': 'Items' }), read: h('section', { class: 'fd-read', tabindex: 0, 'aria-label': 'Reading' }) };
      root.append(h('header', { class: 'fd-top' }, els.head, els.flying), h('div', { class: 'fd-cols' }, els.src, els.list, els.read));
      for (const ev of ['wheel', 'touchmove', 'pointerdown']) els.read.addEventListener(ev, onInput, { passive: true });
      els.read.addEventListener('keydown', onKey);
      els.read.addEventListener('scroll', onReadScroll, { passive: true });
      els.list.addEventListener('scroll', onListScroll, { passive: true });
      window.addEventListener('pagehide', onHide);
      document.addEventListener('visibilitychange', onVisibility);
      ctx.sub(M.DESC);
      ctx.watch(M.W);
      last = ctx.desc(M.DESC) || null;
      drawHead();
      show(r, o.input, true);
      return root;
    },
    update(r, o = {}) {
      show(r, o.input);
      return true;
    },
    unmount() {
      leaveRead();
      setPages(null);
      ctx.unsub(M.DESC);
      ctx.unwatch(M.W);
      els.read.removeEventListener('scroll', onReadScroll);
      els.list.removeEventListener('scroll', onListScroll);
      window.removeEventListener('pagehide', onHide);
      document.removeEventListener('visibilitychange', onVisibility);
      clearTimeout(ticker);
      clearTimeout(retryTimer);
      clearTimeout(barSoon);
      root = null;
    },
    back() {
      const p = M.parentOf(place, from);
      if (!p) return false;
      go(p);
      return true;
    },
    onDesc(scope, d) {
      if (scope === M.DESC) {
        last = d;
        lastAt = pcNow();
        refusals.delete(scope);
        drawHead();
        drawSources();
        drawListHead();
        if (place.at === 'settings') drawSettings();
        if (art) drawReadBar();
        if (bg) drawBingeBar();
      } else if (pages && pages.pageOf(scope) !== null) {
        pages.got(pages.pageOf(scope), d);
        refusals.delete(scope);
        drawList();
        // seen in headless Firefox: an article still arriving said "An item" though its row had come meanwhile
        if (art && !art.d) drawRead();
        else if (art) drawReadBar();
        sendSeen();
        checkMore();
      } else if (art && scope === art.scope) {
        art.d = d;
        art.why = null;
        if (art.kind === 'article' && d && d.item && String(d.item.itemKind).toUpperCase() === 'COMIC') {
          // an item the page did not know to be a comic: its canvas instead (spec §3.3 `feed/comic:`)
          ctx.unsub(art.scope);
          art.kind = 'comic';
          art.scope = M.comicScope(art.src, art.id);
          art.d = ctx.desc(art.scope) || null;
          ctx.sub(art.scope);
        }
        if (art.kind === 'article' && art.entered) drawReadBar();
        else drawRead();
        if (place.at === 'num') drawList();
      } else if (bg && bg.b.asked.has(scope)) {
        // a window this binge asked (a late answer for one let go at a jump is not merged)
        gotBinge(scope, d);
      }
    },
    onRefused(scope, a) {
      const why = (a && a.reason) || `refused (${a && a.code})`;
      if (pages && pages.pageOf(scope) !== null) {
        pages.refused(pages.pageOf(scope), a, Date.now());
        if (a.code === 'retry') {
          clearTimeout(retryTimer);
          retryTimer = setTimeout(() => checkMore(), M.RETRY_MS);
        }
        drawList();
        return true;
      }
      if (art && scope === art.scope) {
        art.why = why;
        drawRead();
        return true;
      }
      if (bg && bg.b.epOf(scope) !== null) {
        bg.b.refused(scope, a);
        drawBingeEnds();
        return true;
      }
      if (scope === M.DESC || scope === M.W) {
        refusals.set(scope, why);
        drawSources();
        return true;
      }
      return false;
    },
    onWindowRecord(w, changed) {
      if (w !== M.W || !root) return;
      pend.landed(changed, rec);
      // before the entry nothing is followed: the entry takes the record's place
      if (art && art.kind === 'article') {
        if (!art.entered) enterArticle();
        else if (changed.has(`rec.${M.W}.place`)) followArticle();
      }
      if (bg) {
        if (!bg.entered) enterBinge();
        else if (changed.has(`rec.${M.W}.binge.${bg.src}`)) followBinge();
      }
      redraw();
      sendSeen();
    },
    onWelcome() {
      redraw();
      if (place.at === 'settings') drawSettings();
    },
    onReach() {
      drawHead();
      redraw();
      if (place.at === 'settings') drawSettings();
      if (bg) drawBingeBar();
      if (!ctx.S.reach.ok) return;
      // a mark, not a queue: the item still open when the PC answers again is marked read; a place read meanwhile
      // is sent once (Reader's rule, C§2.5)
      if (owedRead && art && art.src === owedRead.src && (art.id === owedRead.id || (art.d && art.d.item && art.d.item.id === owedRead.id))) readOnInput(owedRead.src, owedRead.id, owedRead.at);
      owedRead = null;
      if (art && art.moved) saveArticle();
      if (bg && bg.moved) saveBinge();
    },
  };
}
