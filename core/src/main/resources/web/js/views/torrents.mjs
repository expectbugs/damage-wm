// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-2, package Q4w (spec §3.3; INTERFACES.md W-20, W-37, W-38; decisions A.6, A.7, A.9, A.10): Torrents in a
// browser. Tabs Transfers · Browse · Search · Account. Transfers: the session speed graph, the filters, a table sorted
// by any column (a card per transfer at a phone's width) with a speed line per row, several selected for one action,
// and a transfer's details. Browse and Search: the tracker's listings a page at a time as the view nears the end;
// a torrent's page with its NFO and files and Add behind the confirm. Program ops answer Sent or Refused (A.7): the
// page says what the PC answered and the table shows what qBittorrent reports — never an effect it has not read.
// Out of reach the page dims (A.6). Every decision is in `torrents-model.mjs`; this module draws.

import { h, fill, bar, keepFocus } from '../dom.mjs';
import { hhmm, when, atLeast } from '../proto.mjs';
import * as M from '../torrents-model.mjs';

const NS = 'http://www.w3.org/2000/svg';
const LONG_PRESS_MS = 500;
const ROW_PX = 40;

/** Draws `make()` into `box` only when `sig` differs from what it holds: a control in use (an open select, the
 *  settings) is never replaced under the pointer by a poll that changed nothing it shows. */
function put(box, sig, make) {
  if (box.dataset.sig === sig) return;
  box.dataset.sig = sig;
  keepFocus(box, 'data-key', () => fill(box, make()));
}

function svg(tag, attrs, ...kids) {
  const e = document.createElementNS(NS, tag);
  for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, String(v));
  for (const k of kids) e.append(k);
  return e;
}

/** A speed line: down and up over `samples` in a `w` × `hgt` box; nothing for a transfer that moved nothing (seen in
 *  headless Firefox: a flat line read as an underline). */
function speedLine(samples, w, hgt, cls) {
  const dl = samples.map((s) => s.dl || 0);
  const up = samples.map((s) => s.up || 0);
  const top = Math.max(0, ...dl, ...up);
  if (samples.length < 2 || top === 0) return null;
  return svg('svg', { viewBox: `0 0 ${w} ${hgt}`, preserveAspectRatio: 'none', class: cls, 'aria-hidden': 'true' },
    svg('polyline', { points: M.line(up, w, hgt, top), class: 'sp-up' }), svg('polyline', { points: M.line(dl, w, hgt, top), class: 'sp-dl' }));
}

export function torrentsView(ctx) {
  const store = ctx.store;
  const rec = ctx.rec(M.W);
  const sel = new M.Selection();
  const sparks = new M.Sparks();
  const flying = new Map(); // k → {label, n, at}: program ops sent and not answered
  const refusals = new Map(); // scope → the PC's reason
  const mq = typeof window.matchMedia === 'function' ? window.matchMedia('(max-width: 900px)') : null;
  let root = null;
  let els = null;
  let place = { tab: 'transfers' };
  let last = null; // the newest description taken
  let listing = null;
  let lastListing = null; // the address target of the listing a torrent's page was opened from
  let detailScope = null;
  let tlScope = null;
  let draft = '';
  let selecting = false;
  let setOpen = false;
  let parts = null; // the transfers tab's boxes while it is shown
  let ticker = null;
  let retryTimer = null;
  let sort = readJson(M.KEYS.sort, { key: 'activity' });
  let filter = M.FILTERS.some(([f]) => f === store.get(M.KEYS.filter)) ? store.get(M.KEYS.filter) : 'all';
  let tlSort = M.TL_SORTS.some(([s]) => s === store.get(M.KEYS.tlSort)) ? store.get(M.KEYS.tlSort) : 'added';

  function readJson(k, d) {
    try {
      const v = JSON.parse(store.get(k) || 'null');
      return v && typeof v === 'object' ? v : d;
    } catch (e) {
      return d;
    }
  }
  const narrow = () => !!(mq && mq.matches);
  const full = () => atLeast(ctx.S.level, 'full');
  const pollMs = () => {
    const v = Number(store.get(M.KEYS.poll));
    return M.POLLS.some(([ms]) => ms === v) ? v : 2000;
  };
  // spec §2.3: focus at this device's Poll while the page is visible; none while hidden
  const needNow = () => (document.visibilityState === 'hidden' ? { kind: 'none' } : { kind: 'focus', paceMs: pollMs() });
  const go = (target) => ctx.go(target ? { window: M.W, target } : { window: M.W });
  const link = (text, target, key, cls) => h('a', { href: target ? `/w/torrents/${target}` : '/w/torrents', class: cls || null, 'data-key': key, focus: true,
    on: { click: (e) => { e.preventDefault(); go(target); } } }, text);
  const nameOf = (id) => ((last && last.transfers) || []).find((t) => String(t.id) === id)?.title || id;
  const acts = () => M.actionsOf(last);

  // ---- program ops (spec §2.4; A.7) ----

  async function act(a, ids, names, params) {
    const c = M.confirmFor(a, names || ids.map(nameOf));
    // L1 P33: the act goes on the connection current at the act (its targets were named in the confirm)
    if (c && !(await ctx.confirm(c))) return;
    const w = { label: a.label, n: ids.length, at: Date.now() };
    const k = ctx.sendProgram(M.programDraft(a, ids, params), a.level, (ans) => {
      flying.delete(k);
      drawFlying();
      ctx.say(M.answerLine(a, ids.length, ans), { from: 'Torrents' });
    });
    if (k === null) return;
    flying.set(k, w);
    drawFlying();
  }

  /** A button for action `id` over `ids`, or null when this device's level does not offer it. */
  function actButton(id, ids, opts = {}) {
    const a = acts().get(id);
    if (!M.offered(a, ctx.S.level)) return null;
    return ctx.actionButton({ label: opts.label || a.label, need: a.level, why: opts.why, cls: a.kind === 'i' ? 'danger' : '',
      onClick: () => {
        const list = typeof ids === 'function' ? ids() : ids;
        act(a, list, opts.names, opts.params);
      } });
  }

  function drawFlying() {
    clearTimeout(ticker);
    if (!root) return; // left: the answer is still said as a toast
    const now = Date.now();
    fill(els.flying, [...flying.values()].map((w) => h('p', { class: 'note', text: M.waitingLine(w, now) })));
    if (flying.size) ticker = setTimeout(drawFlying, 1000);
  }

  // ---- the frame ----

  function draw() {
    if (!root) return;
    drawHead();
    drawBody();
    drawDetails();
    drawFlying();
  }

  function drawHead() {
    const d = last;
    const s = (d && d.summary) || {};
    const health = [];
    if (d && d.health) {
      const q = d.health.qbt || {};
      const t = d.health.tracker || {};
      if (q.state && q.state !== 'ok') health.push(`qBittorrent: ${q.state}${q.since ? ` since ${hhmm(q.since)}` : ''}`);
      if (t.state && t.state !== 'ok' && full()) health.push(`TorrentLeech: ${t.state}`);
    }
    if (d && d.availability && d.availability !== 'live') health.push(d.reason || d.availability);
    const tabs = [['transfers', 'Transfers', null]].concat(full() ? [['browse', 'Browse', 'browse'], ['search', 'Search', 'search'], ['account', 'Account', 'account']] : []);
    const cur = place.tab === 'torrent' ? 'browse' : place.tab;
    fill(els.info,
      h('div', { class: 'thead-line' }, h('h1', { text: 'Torrents' }),
        h('span', { class: 'tsum', text: [s.line, s.detail].filter(Boolean).join(' · ') }),
        ctx.S.reach.ok || !d ? null : h('span', { class: 'note', text: `as read at ${when(d.atMs)}` })),
      health.length ? h('p', { class: 'health', text: health.join(' · ') }) : null);
    put(els.tabs, JSON.stringify([cur, full()]), () => [
      h('nav', { class: 'tabs', 'aria-label': 'Torrents' }, tabs.map(([id, label, target]) =>
        link(label, target, `tab:${id}`, 'tab' + (cur === id ? ' current' : '')))),
      full() ? null : h('p', { class: 'note', text: 'Browse, Search and Account need level full on this device.' })]);
    const rows = ((d && d.settings) || []).filter((r) => r.scope !== 'device');
    put(els.settings, JSON.stringify([rows, rows.map((r) => M.rowValue(r, rec)), ctx.S.level, ctx.canAct('view'), pollMs()]), settings);
  }

  // ---- the settings section (spec §3.3: the Notify rows, Size, and this device's Poll) ----

  function settings() {
    const rows = ((last && last.settings) || []).filter((r) => r.scope !== 'device');
    // a row sent and refused here (out of reach) is drawn again from the record, whatever the control shows
    const redraw = () => { els.settings.dataset.sig = ''; drawHead(); };
    const box = h('details', { class: 'tset', open: setOpen, on: { toggle: () => { setOpen = box.open; } } },
      h('summary', { 'data-key': 'set', focus: true }, 'Settings'),
      rows.map(setRow),
      h('label', { class: 'trow' }, h('span', { text: 'Poll (this device)' }),
        h('select', { 'data-key': 'set:poll', focus: true, on: { change: (e) => {
          store.set(M.KEYS.poll, String(e.target.value));
          ctx.need(M.DESC, needNow());
        } } }, M.POLLS.map(([ms, label]) => h('option', { value: ms, selected: ms === pollMs() }, label))),
        h('span', { class: 'note', text: 'while this page is in front; otherwise the PC polls every 15 s' })));
    return box;
  }

  function setRow(row) {
    const v = M.rowValue(row, rec);
    const need = row.level || 'control';
    const label = h('span', { text: row.label });
    if (!atLeast(ctx.S.level, need)) {
      const shown = row.type === 'bool' ? (v ? 'on' : 'off') : (M.optionsOf(row).find(([id]) => String(id) === String(v)) || [v, String(v)])[1];
      return h('div', { class: 'trow' }, label, h('span', { text: shown }), h('span', { class: 'note', text: `changing it needs level ${need}` }));
    }
    const why = ctx.canAct(need);
    const send = (value) => {
      const sent = ctx.sendQueued(M.setDraft(row, value), (ans) => {
        if (ans.a === 'refused') ctx.say(`${row.label}: ${ans.reason}`, { from: 'Torrents' });
        redraw();
      }, { need });
      if (!sent) redraw();
    };
    const key = `set:${row.id}`;
    const ctl = row.type === 'bool'
      ? h('input', { type: 'checkbox', checked: !!v, 'data-key': key, focus: true, class: why ? 'dim' : null, 'data-why': why, on: { change: (e) => send(!!e.target.checked) } })
      // a select's value is text: the option's own id goes back (Size's 288 stays a number)
      : h('select', { 'data-key': key, focus: true, class: why ? 'dim' : null, 'data-why': why, on: { change: (e) => {
        const o = M.optionsOf(row).find(([id]) => String(id) === e.target.value);
        send(o ? o[0] : e.target.value);
      } } }, M.optionsOf(row).map(([id, l]) => h('option', { value: id, selected: String(id) === String(v) }, l)));
    return h('label', { class: 'trow' }, label, ctl);
  }

  // ---- the body: one tab at a time ----

  function drawBody() {
    const tab = place.tab;
    if (tab === 'transfers') return drawTransfers();
    parts = null;
    if (!full()) return fill(els.body, h('p', { class: 'note', text: `This ${ctx.levelWhy('full')}.` }));
    const body = tab === 'browse' ? browse() : tab === 'search' ? search() : tab === 'torrent' ? torrentPage() : account();
    keepFocus(els.body, 'data-key', () => fill(els.body, body));
  }

  /** The transfers tab in boxes of its own, so a poll redraws the rows and leaves the sort control alone. */
  function drawTransfers() {
    if (!parts || parts.narrow !== narrow()) {
      // the bulk box itself is the sticky one (seen in headless Firefox: a sticky bar inside a box of its own height
      // never stuck)
      parts = { graph: h('div'), chips: h('div'), tools: h('div'), bulk: h('div', { class: 'tbulk-box' + (narrow() ? ' sheet' : '') }), list: h('div'), narrow: narrow() };
      // at a phone's width the selection's actions follow the list, held at the bottom (a sheet); on a desk above it
      fill(els.body, parts.graph, parts.chips, parts.tools, parts.narrow ? [parts.list, parts.bulk] : [parts.bulk, parts.list]);
    }
    const why = refusals.get(M.DESC);
    if (!last) {
      for (const b of [parts.graph, parts.chips, parts.bulk]) fill(b);
      return fill(parts.list, h('p', { class: 'note', text: why ? `The transfers could not be read: ${why}` : 'Reading the transfers from the PC…' }));
    }
    const rows = M.tableRows(last, filter, sort);
    // W-20: a selection is what the table shows — a hash gone or filtered away leaves it, so no action reaches a
    // transfer that is not on the screen
    sel.prune(rows.map((r) => r.id));
    const n = M.counts(last);
    fill(parts.graph, graph());
    keepFocus(parts.chips, 'data-key', () => fill(parts.chips, h('div', { class: 'chips', role: 'group', 'aria-label': 'Filter' }, M.FILTERS.map(([f, label]) =>
      h('button', { class: 'chip' + (f === filter ? ' current' : ''), 'aria-pressed': f === filter ? 'true' : 'false', 'data-key': `filter:${f}`, focus: true,
        on: { click: () => { filter = f; store.set(M.KEYS.filter, f); drawBody(); } } }, `${label} ${n[f]}`)))));
    put(parts.tools, JSON.stringify([narrow(), sort, selecting]), () => (narrow() ? h('div', { class: 'ttools' },
      h('select', { 'aria-label': 'Sort', 'data-key': 'sort', focus: true, on: { change: (e) => setSort(e.target.value, null) } },
        Object.entries(M.SORTS).map(([k, s]) => h('option', { value: k, selected: k === sort.key }, `Sort: ${s.label}`))),
      h('button', { 'data-key': 'selecting', focus: true, 'aria-pressed': selecting ? 'true' : 'false', class: selecting ? 'current' : null,
        on: { click: () => { selecting = !selecting; if (!selecting) sel.clear(); drawBody(); } } }, selecting ? 'Done selecting' : 'Select')) : null));
    keepFocus(parts.bulk, 'data-key', () => fill(parts.bulk, bulk()));
    const empty = rows.length ? null : h('p', { class: 'note', text: filter === 'all' ? 'No transfers.' : `No transfers in “${M.FILTERS.find(([f]) => f === filter)[1]}”.` });
    keepFocus(parts.list, 'data-key', () => fill(parts.list, empty || (narrow() ? cards(rows) : table(rows))));
  }

  function setSort(key, dir) {
    sort = { key, dir: dir || M.SORTS[key].dir };
    store.set(M.KEYS.sort, JSON.stringify(sort));
    drawBody();
  }

  function graph() {
    const sp = Array.isArray(last.speeds) ? last.speeds : [];
    const s = last.session || {};
    const span = sp.length > 1 ? M.dur(sp[sp.length - 1].at - sp[0].at) : null;
    const peak = Math.max(0, ...sp.map((x) => x.dl || 0));
    return h('div', { class: 'tgraph' },
      h('p', { class: 'tg-line' }, h('span', { class: 'k-dl', text: `↓ ${M.rate(s.dlSpeed)}` }), h('span', { class: 'k-up', text: `↑ ${M.rate(s.upSpeed)}` }),
        h('span', { class: 'note', text: [span ? `peak ↓ ${M.rate(peak)} in the last ${span}` : 'the graph fills as the PC reads qBittorrent',
          typeof s.free === 'number' && s.free >= 0 ? `${M.bytes(s.free)} free` : null, typeof s.peers === 'number' ? `${s.peers} peers` : null].filter(Boolean).join(' · ') })),
      sp.length > 1 ? speedLine(sp, 600, 80, 'tg-svg') : null);
  }

  function bulk() {
    if (!sel.size) return null;
    const ids = () => sel.list();
    return h('div', { class: 'tbulk', role: 'toolbar', 'aria-label': 'Selected transfers' },
      h('span', { class: 'tb-n', text: `${sel.size} selected` }),
      M.BULK.map((id) => actButton(id, ids)),
      h('button', { 'data-key': 'clear', focus: true, on: { click: () => { sel.clear(); selecting = false; drawBody(); } } }, 'Clear'));
  }

  function check(r) {
    return h('input', { type: 'checkbox', checked: sel.has(r.id), 'aria-label': `select ${r.title}`, 'data-key': `sel:${r.id}`, focus: true,
      on: { change: () => { sel.toggle(r.id); drawBody(); } } });
  }

  function table(rows) {
    const cols = [['name', 'Name'], ['state', 'State'], ['progress', 'Progress'], ['size', 'Size'], ['dlSpeed', 'Down'], ['upSpeed', 'Up'],
      [null, 'Speed'], ['eta', 'ETA'], ['ratio', 'Ratio'], ['seeds', 'Seeds / peers'], ['added', 'Added']];
    const all = rows.length > 0 && rows.every((r) => sel.has(r.id));
    const now = Date.now();
    const head = h('tr', null, h('th', { class: 'c-sel' }, h('input', { type: 'checkbox', checked: all, 'aria-label': 'select every row shown', 'data-key': 'sel:all', focus: true,
      on: { change: () => { if (all) sel.clear(); else sel.set(rows.map((r) => r.id)); drawBody(); } } })),
    cols.map(([k, label]) => h('th', { 'aria-sort': sort.key === k ? (sort.dir === 1 ? 'ascending' : 'descending') : null }, k === null ? label
      : h('button', { class: 'th', 'data-key': `col:${k}`, focus: true, on: { click: () => setSort(k, sort.key === k ? -sort.dir : null) } },
        label + (sort.key === k ? (sort.dir === 1 ? ' ▲' : ' ▼') : '')))));
    return h('div', { class: 'twrap' }, h('table', { class: 'ttable' }, h('thead', null, head), h('tbody', null, rows.map((r) => h('tr', { class: `b-${M.bandClass(r.band)}` + (place.hash === r.id ? ' open' : '') },
      h('td', { class: 'c-sel' }, check(r)),
      h('td', { class: 'c-name' }, link(r.title, `t:${r.id}`, `row:${r.id}`)),
      h('td', { class: 'c-state', text: M.wordOf(r, filter) }),
      h('td', { class: 'c-prog' }, bar(r.progress), h('span', { class: 'pct', text: M.pct(r.progress) })),
      h('td', { class: 'num', text: M.bytes(r.size) }),
      h('td', { class: 'num', text: r.dlSpeed ? M.rate(r.dlSpeed) : '—' }),
      h('td', { class: 'num', text: r.upSpeed ? M.rate(r.upSpeed) : '—' }),
      h('td', { class: 'c-spark' }, speedLine(sparks.of(r.id), 60, 16, 'spark')),
      h('td', { class: 'num', text: M.eta(r.eta) }),
      h('td', { class: 'num', text: M.ratio(r.ratio) }),
      h('td', { class: 'num', text: `${r.seeds ?? '?'} / ${r.peers ?? '?'}` }),
      h('td', { class: 'num', text: M.ago(r.added, now) }))))));
  }

  function cards(rows) {
    return h('ul', { class: 'tcards' }, rows.map((r) => {
      let pressed = null;
      let long = false;
      const open = () => {
        if (long) { long = false; return; }
        if (selecting) { sel.toggle(r.id); drawBody(); } else go(`t:${r.id}`);
      };
      const endPress = () => clearTimeout(pressed);
      return h('li', { class: `tcard b-${M.bandClass(r.band)}` + (sel.has(r.id) ? ' picked' : '') },
        selecting ? check(r) : null,
        h('button', { class: 'tc-main', 'data-key': `row:${r.id}`, focus: true, 'aria-pressed': selecting ? (sel.has(r.id) ? 'true' : 'false') : null, on: {
          click: open,
          // a long press selects (spec §3.3), then the next taps toggle
          pointerdown: () => { long = false; pressed = setTimeout(() => { long = true; selecting = true; sel.toggle(r.id); drawBody(); }, LONG_PRESS_MS); },
          pointerup: endPress, pointerleave: endPress, pointercancel: endPress,
        } },
        h('span', { class: 'tc-name', text: r.title }), bar(r.progress),
        h('span', { class: 'tc-line', text: [M.wordOf(r, filter), M.pct(r.progress), `↓ ${M.rate(r.dlSpeed)}`, `↑ ${M.rate(r.upSpeed)}`,
          r.downloading && !r.stopped ? `${M.eta(r.eta)} left` : null].filter(Boolean).join(' · ') }),
        speedLine(sparks.of(r.id), 60, 16, 'spark')));
    }));
  }

  // ---- a transfer's details (the glasses' DETAILS and transfer menu) ----

  function drawDetails() {
    const hash = place.tab === 'transfers' ? place.hash : null;
    els.details.hidden = !hash;
    if (!hash) return fill(els.details);
    const t = ((last && last.transfers) || []).find((x) => String(x.id) === hash);
    const close = h('button', { class: 'td-close', 'data-key': 'd:close', focus: true, on: { click: () => go(null) } }, 'Close');
    if (!t) {
      return fill(els.details, h('div', { class: 'td-head' }, h('h2', { text: 'A transfer' }), close),
        h('p', { class: 'note', text: last ? `This transfer (${hash}) is not in qBittorrent's list now.` : 'Reading the transfers from the PC…' }));
    }
    const r = M.rowOf(t);
    const d = ctx.desc(detailScope);
    const why = refusals.get(detailScope);
    const filesWhy = !atLeast(ctx.S.level, 'full') ? "needs level full on this device (Files' minimum)" : !ctx.hasView('files') ? 'Files has no page on the web in this build' : null;
    const fact = (k, v) => (v === '' || v === null || v === undefined ? null : [h('dt', { text: k }), h('dd', { text: String(v) })]);
    const now = Date.now();
    keepFocus(els.details, 'data-key', () => fill(els.details,
      h('div', { class: 'td-head' }, h('h2', { text: r.title }), close),
      h('div', { class: 'td-acts' },
        actButton(r.stopped ? 'start' : 'stop', [r.id]), actButton('recheck', [r.id]),
        acts().has('openInFiles') ? ctx.actionButton({ label: acts().get('openInFiles').label, need: 'view', why: filesWhy,
          onClick: () => ctx.go({ window: 'files', target: `path:${r.contentPath || r.savePath}` }) }) : null,
        actButton('openOnPc', [`t:${r.id}`]), actButton('delete', [r.id]), actButton('deleteFiles', [r.id])),
      h('dl', { class: 'facts' },
        fact('State', `${M.wordOf(r, filter)} · ${M.pct(r.progress)}`), fact('Speed', `↓ ${M.rate(r.dlSpeed)} · ↑ ${M.rate(r.upSpeed)} · ETA ${M.eta(r.eta)}`),
        fact('Size', `${M.bytes(r.size)} · ${M.bytes(r.downloaded)} down · ${M.bytes(r.uploaded)} up`), fact('Ratio', M.ratio(r.ratio)),
        fact('Seeds / peers', `${r.seeds ?? '?'} / ${r.peers ?? '?'}`), fact('Added', r.added ? `${M.ago(r.added, now)}` : ''),
        fact('Finished', r.completed ? `${M.ago(r.completed, now)} · ${M.dur(r.seedingTime)} seeded` : ''),
        fact('Saved in', r.savePath), fact('Content', r.contentPath), fact('Category', r.category), fact('Tags', r.tags), fact('Tracker', r.tracker)),
      why ? h('p', { class: 'note', text: `Files and trackers could not be read: ${why}` }) : !d ? h('p', { class: 'note', text: 'Reading its files and trackers…' }) : [
        h('h3', { text: `Files (${(d.files || []).length})` }),
        h('ul', { class: 'td-files' }, (d.files || []).map((f) => h('li', null, h('span', { text: f.name }),
          h('span', { class: 'note', text: `${M.bytes(f.size)} · ${M.pct(f.progress)}` }), bar(f.progress)))),
        h('h3', { text: 'Trackers' }),
        (d.trackers || []).length ? h('ul', { class: 'plain' }, d.trackers.map((u) => h('li', { text: u }))) : h('p', { class: 'note', text: 'None listed.' }),
        d.comment ? [h('h3', { text: 'Comment' }), h('p', { class: 'prose', text: d.comment })] : null]));
  }

  // ---- the tracker: categories, listings, a torrent's page, the account ----

  function cats() {
    const d = ctx.desc('torrents/categories');
    return d && Array.isArray(d.categories) ? d.categories : null;
  }

  function browse() {
    if (place.cat === undefined) {
      const c = cats();
      const why = refusals.get('torrents/categories');
      if (!c) return h('p', { class: 'note', text: why ? `The categories could not be read: ${why}` : 'Reading the categories…' });
      const groups = new Map();
      for (const x of c) (groups.get(x.group) || groups.set(x.group, []).get(x.group)).push(x);
      return [h('div', { class: 'tcats' }, link('Newest', M.listingSrc('newest'), 'cat:newest', 'tcat newest')),
        [...groups].map(([g, xs]) => h('section', { class: 'tgroup' }, h('h3', { text: g }),
          h('div', { class: 'tcats' }, xs.map((x) => link(x.name, M.listingSrc(x.id), `cat:${x.id}`, 'tcat')))))];
    }
    const title = place.cat === 'newest' ? 'Newest' : M.categoryName(place.cat, cats());
    return [h('nav', { class: 'crumbs' }, link('Browse', 'browse', 'crumb:browse'), ` / ${title}`), results()];
  }

  function search() {
    const recents = M.recentsOf(rec);
    const why = ctx.canAct('full');
    const input = h('input', { type: 'search', name: 'q', value: draft, placeholder: 'Search TorrentLeech', 'aria-label': 'search TorrentLeech', 'data-key': 'q', focus: true,
      on: { input: (e) => { draft = e.target.value; } } });
    const form = h('form', { class: 'tsearch', role: 'search', on: { submit: (e) => {
      e.preventDefault();
      const q = String(input.value || '').trim();
      if (!q) return;
      const stop = ctx.canAct('full');
      if (stop) return ctx.say(`Not searched: ${stop}.`, { from: 'Torrents' });
      // A.9: the recents are one register, the whole list sent; TORRENTS.md §3.1: no confirm
      const ra = acts().get('recent');
      if (M.offered(ra, ctx.S.level)) ctx.sendQueued(M.recentDraft(q, rec), (ans) => {
        if (ans.a === 'refused') ctx.say(`The search was not kept in the recents: ${ans.reason}`, { from: 'Torrents' });
      }, { need: ra.level, quiet: true });
      go(`search:${q}`);
    } } }, input, h('button', { type: 'submit', class: why ? 'dim' : null, 'data-why': why, 'data-key': 'q:go', focus: true }, 'Search'));
    const list = recents.length ? h('div', { class: 'recents' }, h('span', { class: 'note', text: 'Recent:' }),
      recents.map((q) => link(q, `search:${q}`, `recent:${q}`, 'chip'))) : h('p', { class: 'note', text: 'No recent searches.' });
    return [form, list, place.q ? results() : null];
  }

  function results() {
    if (!listing) return null;
    const rows = listing.rows().map(M.tlRowOf);
    const st = listing.status();
    const c = cats();
    const now = Date.now();
    const sorter = h('label', { class: 'tsort' }, h('span', { class: 'note', text: 'Sort' }),
      h('select', { 'data-key': 'tlsort', focus: true, on: { change: (e) => { tlSort = e.target.value; store.set(M.KEYS.tlSort, tlSort); setListing(listing.src); drawBody(); } } },
        M.TL_SORTS.map(([id, label]) => h('option', { value: id, selected: id === tlSort }, label))),
      // the glasses' Refresh row: the listing read again from its first page
      h('button', { 'data-key': 'tl:refresh', focus: true, on: { click: () => { const src = listing.src; setListing(null); setListing(src); drawBody(); } } }, 'Refresh'));
    const end = st.kind === 'first' ? (place.tab === 'search' ? 'Searching TorrentLeech…' : 'Reading the listing from TorrentLeech…')
      : st.kind === 'loading' ? 'loading the next page…' : st.kind === 'empty' ? 'No results.' : st.kind === 'end' ? `The end: ${rows.length} results.`
        : st.kind === 'failed' ? `Page ${st.page + 1} could not be read: ${st.reason}${st.retry ? ' — asked again every 5 s.' : ''}` : null;
    const endEl = end ? h('p', { class: 'note tend' + (st.kind === 'failed' ? ' failed' : ''), text: end }) : null;
    const retry = st.kind === 'failed' && !st.retry ? h('button', { 'data-key': 'retry', focus: true, on: { click: () => { listing.failed = null; checkMore(true); } } }, 'Try again') : null;
    const open = (r) => { lastListing = place.tab === 'search' ? `search:${place.q}` : M.listingSrc(place.cat); go(`tl:${r.id}`); };
    const body = narrow()
      ? h('ul', { class: 'tcards' }, rows.map((r) => h('li', { class: 'tcard' + (r.stale ? ' stale' : '') },
        h('button', { class: 'tc-main', 'data-key': `tl:${r.id}`, focus: true, on: { click: () => open(r) } },
          h('span', { class: 'tc-name', text: r.title }),
          h('span', { class: 'tc-line', text: [M.tlSize(r.size), `↑${r.seeders ?? '?'} ↓${r.leechers ?? '?'}`, M.siteAge(r.added, now), r.freeleech ? 'FL' : null,
            M.categoryName(r.category, c)].filter(Boolean).join(' · ') })))))
      : h('div', { class: 'twrap' }, h('table', { class: 'ttable tl' },
        h('thead', null, h('tr', null, ['Name', 'Size', '↑', '↓', 'Snatches', 'Age', 'FL', 'Category'].map((x) => h('th', { text: x })))),
        h('tbody', null, rows.map((r) => h('tr', { class: r.stale ? 'stale' : null },
          h('td', { class: 'c-name' }, h('a', { href: `/w/torrents/tl:${r.id}`, 'data-key': `tl:${r.id}`, focus: true, on: { click: (e) => { e.preventDefault(); open(r); } } }, r.title)),
          h('td', { class: 'num', text: M.tlSize(r.size) }), h('td', { class: 'num', text: String(r.seeders ?? '?') }), h('td', { class: 'num', text: String(r.leechers ?? '?') }),
          h('td', { class: 'num', text: String(r.snatched ?? '?') }), h('td', { class: 'num', text: M.siteAge(r.added, now) }),
          h('td', { text: r.freeleech ? 'FL' : '' }), h('td', { text: M.categoryName(r.category, c) }))))));
    return [sorter, rows.length ? body : null, endEl, retry];
  }

  function torrentPage() {
    const d = ctx.desc(tlScope);
    const why = refusals.get(tlScope);
    const back = lastListing ? link('Back to the list', lastListing, 'tl:back') : link('Browse', 'browse', 'tl:back');
    if (!d) return [h('nav', { class: 'crumbs' }, back), h('p', { class: 'note', text: why ? `This torrent's page could not be read: ${why}` : 'Reading the torrent’s page from TorrentLeech…' })];
    const fact = (k, v) => (v === '' || v === null || v === undefined ? null : [h('dt', { text: k }), h('dd', { text: String(v) })]);
    const name = d.name || place.fid;
    const files = Array.isArray(d.files) ? d.files : [];
    const addA = (id) => {
      const a = acts().get(id);
      if (!M.offered(a, ctx.S.level)) return null;
      return ctx.actionButton({ label: a.label, need: a.level, onClick: () => act(a, [d.fid || place.fid], [name]), cls: id === 'add' ? 'primary' : '' });
    };
    return [h('nav', { class: 'crumbs' }, back),
      h('h2', { class: 'tl-name', text: name }),
      h('div', { class: 'td-acts' }, addA('add'), addA('addStopped'), actButton('openOnPc', [`tl:${d.fid || place.fid}`], { label: 'Open on the PC' })),
      h('dl', { class: 'facts' }, fact('Category', d.category), fact('Size', d.size), fact('Seeders / leechers', `${d.seeders ?? '?'} / ${d.leechers ?? '?'}`),
        fact('Snatches', d.snatched), fact('Added', d.added), fact('Uploader', d.uploader), fact('Tags', Array.isArray(d.tags) ? d.tags.join(', ') : d.tags),
        fact('Freeleech', d.freeleech === undefined ? null : d.freeleech ? 'yes' : 'no')),
      h('h3', { text: 'Description' }), d.description ? h('p', { class: 'prose', text: d.description }) : h('p', { class: 'note', text: 'None.' }),
      h('h3', { text: 'NFO' }), d.nfo ? h('pre', { class: 'nfo', text: d.nfo }) : h('p', { class: 'note', text: 'None.' }),
      h('h3', { text: `Files (${files.length})` }),
      files.length ? h('div', { class: 'twrap' }, h('table', { class: 'ttable' }, h('tbody', null, files.map((f) => h('tr', null,
        h('td', { class: 'c-name', text: f.name }), h('td', { class: 'num', text: M.tlSize(f.size) })))))) : null];
  }

  function account() {
    const d = ctx.desc('torrents/account');
    const why = refusals.get('torrents/account');
    const s = (last && last.session) || {};
    const fact = (k, v) => (v === '' || v === null || v === undefined ? null : [h('dt', { text: k }), h('dd', { text: String(v) })]);
    return [h('h2', { text: 'TorrentLeech' }),
      d ? h('dl', { class: 'facts' }, fact('Account', d.user), fact('Uploaded', d.uploaded), fact('Downloaded', d.downloaded), fact('Ratio', d.ratio),
        fact('Points', d.points), fact('Class', d.klass ?? d.class))
        : h('p', { class: 'note', text: why ? `The account could not be read: ${why}` : 'Reading the account from TorrentLeech…' }),
      h('h2', { text: 'qBittorrent' }),
      last ? h('dl', { class: 'facts' }, fact('Speed', `↓ ${M.rate(s.dlSpeed)} · ↑ ${M.rate(s.upSpeed)}`),
        fact('This session', `${M.bytes(s.dlTotal)} down · ${M.bytes(s.upTotal)} up`), fact('Ratio (all time)', s.ratio),
        fact('Free space', typeof s.free === 'number' && s.free >= 0 ? M.bytes(s.free) : null), fact('Peers', s.peers),
        fact('Connection', s.connection), fact('Version', s.version)) : h('p', { class: 'note', text: 'Reading qBittorrent from the PC…' })];
  }

  // ---- scopes as the place changes ----

  function setListing(src) {
    if (listing && listing.src === src && listing.sort === tlSort) return;
    if (listing) for (const s of listing.held()) ctx.unsub(s);
    listing = src ? new M.Listing(src, tlSort) : null;
    if (listing) checkMore();
  }

  /** Asks the listing's next page when the model says so: at once for the first, near the end for the next, and a
   *  failed one again on its pacing. Never while the page is hidden. */
  function checkMore(force) {
    // only the listing on screen asks (a torrent's page opened from it keeps its rows for Back, and asks nothing)
    if (!listing || document.visibilityState === 'hidden' || (place.tab !== 'browse' && place.tab !== 'search')) return;
    const m = document.getElementById('main');
    const near = !!force || (m ? M.nearEnd(m.scrollTop, m.clientHeight, m.scrollHeight, ROW_PX) : false);
    const n = listing.next(near, Date.now());
    if (n === null) return;
    listing.ask(n);
    refusals.delete(listing.scope(n));
    ctx.sub(listing.scope(n));
    if (root) drawBody(); // the loading row says the page is on its way
  }

  function hold(scope, want) {
    if (!want) {
      if (scope) ctx.unsub(scope);
      return null;
    }
    if (scope !== want) {
      if (scope) ctx.unsub(scope);
      refusals.delete(want);
      ctx.sub(want);
    }
    return want;
  }

  function show(r) {
    place = M.placeOf(r.target);
    const tracker = full();
    detailScope = hold(detailScope, place.tab === 'transfers' && place.hash ? `${M.W}/t:${place.hash}` : null);
    tlScope = hold(tlScope, tracker && place.tab === 'torrent' ? `${M.W}/tl:${place.fid}` : null);
    if (tracker && (place.tab === 'browse' || place.tab === 'search' || place.tab === 'torrent') && !ctx.desc('torrents/categories')) ctx.sub('torrents/categories');
    if (tracker && place.tab === 'account') ctx.sub('torrents/account');
    else ctx.unsub('torrents/account');
    if (place.tab === 'search' && place.q) draft = place.q;
    if (tracker && place.tab === 'browse' && place.cat !== undefined) setListing(M.listingSrc(place.cat));
    else if (tracker && place.tab === 'search' && place.q) setListing(M.searchSrc(place.q));
    else if (place.tab !== 'torrent') setListing(null);
    draw();
  }

  const onVisibility = () => {
    ctx.need(M.DESC, needNow());
    checkMore();
  };
  const onScroll = () => checkMore();
  const onWidth = () => drawBody();

  return {
    mount(r) {
      root = h('section', { class: 'page torrents' });
      els = { info: h('div'), tabs: h('div'), settings: h('div'), body: h('div', { class: 'tbody' }), flying: h('div', { class: 'tflying', role: 'status' }),
        details: h('aside', { class: 'tdetails', 'aria-label': 'Transfer details', hidden: true }) };
      root.append(h('header', { class: 'thead' }, els.info, els.tabs, els.settings), els.flying, els.body, els.details);
      ctx.sub(M.DESC, needNow());
      ctx.watch(M.W);
      document.addEventListener('visibilitychange', onVisibility);
      const m = document.getElementById('main');
      if (m) m.addEventListener('scroll', onScroll, { passive: true });
      if (mq && mq.addEventListener) mq.addEventListener('change', onWidth);
      last = ctx.desc(M.DESC) || null;
      show(r);
      return root;
    },
    update(r) {
      show(r);
      return true;
    },
    unmount() {
      setListing(null);
      detailScope = hold(detailScope, null);
      tlScope = hold(tlScope, null);
      for (const s of ['torrents/categories', 'torrents/account', M.DESC]) ctx.unsub(s);
      ctx.unwatch(M.W);
      document.removeEventListener('visibilitychange', onVisibility);
      const m = document.getElementById('main');
      if (m) m.removeEventListener('scroll', onScroll);
      if (mq && mq.removeEventListener) mq.removeEventListener('change', onWidth);
      clearTimeout(ticker);
      clearTimeout(retryTimer);
      root = null;
    },
    back() {
      if (place.tab === 'transfers' && place.hash) go(null);
      else if (place.tab === 'torrent') go(lastListing || 'browse');
      else if (place.tab === 'browse' && place.cat !== undefined) go('browse');
      else if (place.tab === 'search' && place.q) go('search');
      else if (place.tab !== 'transfers') go(null);
      else return false;
      return true;
    },
    onDesc(scope, d) {
      if (scope === M.DESC) {
        if (!M.newer(last, d)) return;
        last = d;
        refusals.delete(M.DESC);
        sparks.feed(d);
        drawHead();
        if (place.tab === 'transfers' || place.tab === 'account') drawBody();
        drawDetails();
      } else if (scope === detailScope) {
        refusals.delete(scope);
        drawDetails();
      } else if (listing && listing.pageOf(scope) !== null) {
        listing.got(listing.pageOf(scope), d);
        refusals.delete(scope);
        drawBody();
        checkMore();
      } else if (scope === tlScope || scope === 'torrents/categories' || scope === 'torrents/account') {
        refusals.delete(scope);
        drawBody();
      }
    },
    onRefused(scope, a) {
      if (listing && listing.pageOf(scope) !== null) {
        listing.refused(listing.pageOf(scope), a, Date.now());
        // spec §5 V2: the PC said `retry`: asked again on the 5 s pacing (the glasses' rule), never at once
        if (a.code === 'retry') {
          clearTimeout(retryTimer);
          retryTimer = setTimeout(() => checkMore(), M.RETRY_MS);
        }
        drawBody();
        return true;
      }
      if (![M.DESC, detailScope, tlScope, 'torrents/categories', 'torrents/account'].includes(scope)) return false;
      refusals.set(scope, (a && a.reason) || `refused (${a && a.code})`);
      draw();
      return true;
    },
    onWindowRecord(w) {
      if (w !== M.W) return;
      drawHead();
      if (place.tab === 'search') drawBody();
    },
    onWelcome: () => draw(),
    onReach: () => draw(),
  };
}
