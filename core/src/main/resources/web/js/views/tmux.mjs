// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-3, piece B17 (first built as package JT part 1; the lean spec's B8–B12; CONTRACT.md §3.4, §4.2, §4.3, §8,
// §10.4; TMUX.md's verdicts; the lead's ruling G2): the Tmux page for a device at level full. The sessions with their
// state and each host's health; a session at a desk's width as a real terminal (`terminal.mjs`: the emulator the PC
// serves, on the session's control-mode stream; his keyboard, mouse selection and paste; its font scaled to the view,
// so his session is never resized), or — when the library cannot be loaded — the PC's colour pictures with the reason;
// at a phone's width the pictures (logical lines flowed to the screen's width; a full-screen program as a grid) with
// the quick keys, the snippets and a typed line behind Run it. Every key is a live op (never queued, carrying the
// connection, pipelined in order; tmux orders input from every surface by arrival); out of reach a key is refused at
// once and said, never held. A paste holding a line break asks first, naming its line count. The session actions with
// their confirms; the history as one page the browser's find searches. Below full the page shows nothing of any session
// and asks the PC for none of it. A Tmux alert pops no toast here (G2: the glasses only; the waiting mark is this
// page's).

import { h, fill, keepFocus } from '../dom.mjs';
import { atLeast, hhmm } from '../proto.mjs';
import * as M from '../tmux-model.mjs';
import * as T from '../term-render.mjs';
import { terminalSurface } from '../terminal.mjs';

function put(box, sig, make) {
  if (box.dataset.sig === sig) return;
  box.dataset.sig = sig;
  keepFocus(box, 'data-key', () => fill(box, make()));
}

/** The width the page gives a terminal box `el` (which hugs its grid): its parent's content width, less the box's own
 *  padding and border (6 px and 1 px a side). 0 before the page has a layout. */
function roomFor(el) {
  const p = el.parentElement;
  if (!p || !p.clientWidth) return 0;
  const cs = typeof getComputedStyle === 'function' ? getComputedStyle(p) : null;
  const pad = cs ? (parseFloat(cs.paddingLeft) || 0) + (parseFloat(cs.paddingRight) || 0) : 0;
  return Math.max(0, p.clientWidth - pad - 14);
}

/**
 * The surface a session is drawn by, for a width: `{feed, el, take(msg), refit(), retheme(), focus(), focused(),
 * destroy()}` — `feed` names the live source it reads ('stream': the control-mode output the terminal writes; 'frame':
 * the PC's pictures). At a desk's width the terminal, unless the library could not be loaded (`why`); at a phone's
 * width, and as that fallback, the colour grid.
 */
export function surfaceFor(phone, why) {
  return phone || why ? gridSurface : terminalSurface;
}

/** The colour pictures, drawn by `term-render.mjs`: a normal pane's logical lines flowed to the view (at a desk no
 *  wider than the pane), a full-screen program's rows as a grid at its own size. Input goes by the keys below it. */
export function gridSurface({ label, phone }) {
  const rows = h('div', { class: 'tm-rows' });
  const el = h('div', { class: 'tm-term' + (phone ? ' phone' : ''), role: 'group', 'aria-label': label }, rows);
  let frame = null;
  let palName = null;
  let ratio = null; // a column's width per px of font, measured in the face
  let metrics = null; // {fontPx, cw, ch, wrap}
  let stick = true; // the flow layout follows the newest lines unless he scrolled up
  const theme = () => T.themeOf(document.documentElement, typeof window.matchMedia === 'function' ? window.matchMedia.bind(window) : null);
  const rootPx = () => {
    const v = typeof getComputedStyle === 'function' ? parseFloat(getComputedStyle(document.documentElement).fontSize) : NaN;
    return Number.isFinite(v) && v > 0 ? v : 17;
  };
  const flowing = () => !(frame && frame.alternate);

  function refit() {
    el.classList.toggle('flow', flowing());
    // fit: the box hugs the grid, so the room is the page's; flow: the box takes the width, less its scroll bar
    const avail = flowing() ? Math.max(0, (el.clientWidth || 0) - 12) : roomFor(el);
    // the face's column width per px of font, measured once per face (a theme may change it)
    T.setMetrics(rows, { fontPx: 100, cw: 60, ch: 120 });
    ratio = (T.measure(rows) || 60) / 100;
    const cols = Math.max(1, (frame && frame.cols) | 0 || 80);
    let fontPx;
    if (flowing() && phone) fontPx = Math.round(rootPx() * 0.78);
    // the columns fit the view's width up to the page's own text size; below 9 px the grid scrolls sideways instead
    else fontPx = avail > 0 ? Math.max(9, Math.min(rootPx(), avail / (cols * ratio))) : rootPx() * 0.8;
    T.setMetrics(rows, { fontPx, cw: fontPx * ratio, ch: Math.round(fontPx * T.LINE) });
    // the width itself at that size (a face may round its advance at small sizes): every box is placed by it
    const cw = T.measure(rows) || fontPx * ratio;
    const ch = Math.round(fontPx * T.LINE);
    T.setMetrics(rows, { fontPx, cw, ch });
    const fits = avail > 0 ? Math.max(20, Math.floor(avail / cw)) : 40;
    metrics = { fontPx, cw, ch, wrap: flowing() ? (phone ? fits : Math.min(fits, cols)) : null };
  }

  function draw(force = false) {
    if (!frame) return;
    const name = theme();
    if (force || name !== palName) {
      T.unpaint(rows);
      palName = name;
    }
    el.classList.toggle('flow', flowing());
    T.paint(rows, frame, { wrap: metrics ? metrics.wrap : null, pal: T.PALETTES[name] });
    el.dataset.theme = name;
    // newest at the bottom, as the glasses' flow view (TMUX.md); a view scrolled up stays where it is
    if (flowing() && stick) el.scrollTop = el.scrollHeight || 0;
  }

  el.addEventListener('scroll', () => {
    stick = el.scrollTop + el.clientHeight >= (el.scrollHeight || 0) - 4;
  }, { passive: true });

  return {
    feed: 'frame',
    el,
    take(f) {
      const reshape = !frame || !metrics || f.cols !== frame.cols || !!f.alternate !== !!frame.alternate;
      frame = f;
      if (reshape) refit();
      draw();
    },
    refit() {
      refit();
      draw(true);
    },
    retheme() { draw(); },
    focus() {},
    focused: () => false,
    destroy() { el.remove(); },
  };
}

export function tmuxView(ctx) {
  const rec = ctx.rec(M.W);
  const pend = new M.Pending();
  const flying = new Map(); // k → {label, at}: live and program ops sent, not answered
  const refusals = new Map(); // scope → the PC's reason
  const saidReasons = new Set(); // a refused key's reason, said once per session view
  const mq = typeof window.matchMedia === 'function' ? window.matchMedia('(max-width: 760px)') : null;
  const darkMq = typeof window.matchMedia === 'function' ? window.matchMedia('(prefers-color-scheme: dark)') : null;
  let root = null;
  let els = null;
  let started = false;
  let place = { at: 'sessions' };
  let last = null; // desc/tmux
  let canvas = null; // the open session's canvas
  let hist = null; // the open history
  let feed = new M.Feed();
  let liveScope = null;
  let libWhy = null; // why the terminal library could not be loaded on this page: the grid is drawn instead
  let canvasScope = null;
  let histScope = null;
  let surface = null;
  let gone = null; // why the open session is not there: {ended, why}
  let drawTimer = null;
  let ticker = null;
  let notSent = 0; // keys refused at once in this outage
  let unknown = 0; // keys whose connection ended before their answer
  let observer = null;
  let setOpen = false;
  let typed = ''; // the Type… field's text, kept when the dock is drawn again

  const say = (text, opts = {}) => ctx.say(text, { from: 'Tmux', ...opts });
  const phone = () => !!(mq && mq.matches);
  const pcNow = () => ctx.pcNow();
  const acts = () => M.actionsOf(last);
  const levelOf = (id) => (acts().get(id) || {}).level || 'full';
  const offered = (id) => M.offered(acts().get(id), ctx.S.level);
  // focus while the page is in front, none while hidden (B8: one capture pace per PC, the glasses' Update row)
  const needNow = () => (document.visibilityState === 'hidden' ? { kind: 'none' } : { kind: 'focus' });
  /** The live source the open session's surface reads: the stream for the terminal, else the pictures. */
  const sourceOf = (s) => (surface && surface.feed === 'stream' ? M.streamScope(s) : M.frameScope(s));
  const go = (target, opts) => ctx.go(target ? { window: M.W, target } : { window: M.W }, opts);
  const link = (text, target, key, cls) => h('a', { href: target ? `/w/tmux/${target}` : '/w/tmux', class: cls || null, 'data-key': key, focus: true,
    on: { click: (e) => { e.preventDefault(); go(target); } } }, text);
  const session = () => (place.at === 'session' || place.at === 'history' ? M.findSession(last, place.host, place.name) || { host: place.host, name: place.name } : null);
  /** Whether the page still shows session `s` (a confirm's act is void once it moved on). */
  const stillOn = (s) => !!liveScope && place.at === 'session' && place.host === s.host && place.name === s.name;
  /** Why nothing can be sent to the open session now, or null. */
  const stopWhy = (need = 'full') => (gone ? (gone.ended ? 'the session ended' : gone.why) : ctx.canAct(need));

  // ---- the lifecycle ----

  function start() {
    if (started) return;
    started = true;
    ctx.sub(M.DESC);
    ctx.watch(M.W);
  }

  function stop() {
    if (!started) return;
    started = false;
    closeSession();
    closeHistory();
    ctx.unsub(M.DESC);
    ctx.unwatch(M.W);
    last = null;
  }

  function drawBelow() {
    fill(root, h('h1', { text: 'Tmux' }),
      h('p', { class: 'note', text: `${M.belowWhy(ctx.S.level)}: nothing of any session is shown here, and this page asks the PC for none of it.` }),
      h('p', { class: 'note', text: 'Approve this device at level full on the PC to use Tmux from it.' }));
  }

  function show(r, opts = {}) {
    const p = M.placeOf(r.target);
    if (!p) {
      closeSession();
      closeHistory();
      place = { at: 'unknown', target: r.target };
      return draw();
    }
    const same = (q) => q.host === place.host && q.name === place.name;
    if (p.at !== 'session' || !same(p) || place.at !== 'session') closeSession();
    if (p.at !== 'history' || !same(p) || place.at !== 'history') closeHistory();
    const was = place;
    place = p;
    if (p.at === 'session' && !(was.at === 'session' && same(was))) openSession();
    if (p.at === 'history' && !(was.at === 'history' && same(was))) openHistory();
    draw();
    if (opts.input && p.at === 'session' && surface) surface.focus();
  }

  function openSession() {
    const s = session();
    feed = new M.Feed();
    gone = null;
    notSent = 0;
    unknown = 0;
    saidReasons.clear();
    canvas = ctx.desc(M.canvasScope(s)) || null;
    canvasScope = M.canvasScope(s);
    ctx.sub(canvasScope);
    // the terminal subscribes its stream once its library runs (onReady); the pictures at once
    buildSession();
    if (surface.feed === 'frame') listen();
  }

  /** Subscribes the open session's live source (again: a fresh `init`). */
  function listen() {
    if (liveScope) ctx.unlive(liveScope);
    liveScope = sourceOf(session());
    ctx.live(liveScope, needNow(), onLive);
  }

  function closeSession() {
    if (liveScope) ctx.unlive(liveScope);
    if (canvasScope) ctx.unsub(canvasScope);
    liveScope = null;
    canvasScope = null;
    canvas = null;
    // what stopped the session's keys belongs to it: the sessions list and the next session start clean
    gone = null;
    clearTimeout(drawTimer);
    drawTimer = null;
    if (surface) surface.destroy();
    surface = null;
    els && (els.session = null);
  }

  function openHistory() {
    hist = null;
    histScope = M.historyScope(session());
    refusals.delete(histScope);
    ctx.sub(histScope);
  }

  function closeHistory() {
    if (histScope) ctx.unsub(histScope);
    histScope = null;
    hist = null;
  }

  // ---- the live source (B11) ----

  function onLive(m) {
    if (!liveScope || !surface) return;
    const r = feed.got(m, pcNow());
    if (r === 'end') {
      if (feed.renamed) return go(M.target(feed.renamed), { replace: true });
      return ended(feed.ended);
    }
    if (surface.feed === 'stream') {
      // the terminal writes every message in order (an init resets it)
      if (r === 'draw' || r === 'data') {
        surface.take(m);
        if (r === 'draw') {
          feed.drew();
          drawStatus();
        }
      }
    } else if (r === 'draw') scheduleDraw();
  }

  /** The library could not be loaded: said, and the session drawn from the PC's pictures instead. */
  function terminalFailed(why) {
    libWhy = why;
    if (place.at !== 'session') return;
    buildSession();
    listen();
    drawSession();
  }

  /** The newest picture is drawn when the page next turns: pictures that came meanwhile are replaced, never queued. */
  function scheduleDraw() {
    if (drawTimer !== null) return;
    drawTimer = setTimeout(() => {
      drawTimer = null;
      if (!surface || !feed.frame || surface.feed !== 'frame') return;
      surface.take(feed.frame);
      feed.drew();
      drawStatus();
    }, 0);
  }

  function ended(why) {
    gone = { ended: true, why };
    if (liveScope) ctx.unlive(liveScope);
    liveScope = null;
    drawSession();
  }

  // ---- sending (spec §2.5, §4.2: never queued; out of reach refused here, at once) ----

  /** One live or program op on the open session; its `k`, or null when not sent (said). */
  function sendLive(draft, label, { quiet = false, then } = {}) {
    const why = stopWhy(levelOf(draft.action));
    if (why) {
      // a key typed while the PC is out of reach is never held to fire later: refused now, counted and said
      notSent++;
      if (!quiet) say(`${label}: not sent — ${why}.`);
      else if (gone) {
        if (!saidReasons.has('gone')) say(`Keys are not sent: ${why}.`);
        saidReasons.add('gone');
      } else ctx.sayOnce('away', `Keys are not sent: ${why}. A page keeps no queue; type again when the PC answers.`, { from: 'Tmux' });
      drawStatus();
      return null;
    }
    const at = Date.now();
    const k = ctx.sendProgram(draft, levelOf(draft.action), (ans) => {
      flying.delete(k);
      if (ans && ans.a === 'refused' && ans.code === 'ended') unknown++;
      else {
        const line = M.answerLine(label, ans);
        if (line) {
          const key = `${draft.action}:${ans.code}:${ans.reason}`;
          if (!quiet || !saidReasons.has(key)) say(line);
          saidReasons.add(key);
        }
      }
      if (then) then(ans);
      drawStatus();
    });
    if (k === null) {
      notSent++;
      drawStatus();
      return null;
    }
    flying.set(k, { label, at });
    drawStatus();
    return k;
  }

  /** Bytes a keyboard produced, as the `raw` action (B9: the PC splits them into literal text and the rest). */
  function sendRaw(data, label, quiet = true) {
    if (!offered('raw')) {
      ctx.sayOnce('tmux-raw', 'Typing is not taken: the PC lists no keyboard action for this device.', { from: 'Tmux' });
      return null;
    }
    return sendLive(M.rawDraft(session(), data), label, { quiet });
  }

  async function pasteFlow(text) {
    const s = session();
    const p = M.pasteShape(text);
    if (p.empty) return;
    if (!offered('raw')) return say('Paste: the PC lists no keyboard action for this device.');
    if (p.asks) {
      const why = stopWhy();
      if (why) return say(`Paste: not sent — ${why}.`);
      const conn0 = ctx.S.conn;
      if (!(await ctx.confirm(M.pasteConfirm(p, s.name)))) return;
      // C§4.3: a never-queued op's confirm is void if its connection ended before the act, or the view moved on
      if (ctx.S.conn !== conn0 || !stillOn(s)) return say('Paste: nothing sent — the connection or the session changed while the confirm was open. Paste again.');
    }
    sendRaw(p.data, `Paste of ${p.lines === 1 ? 'one line' : `${p.lines} lines`}`, false);
  }

  async function runLine(input) {
    const s = session();
    const text = String(input.value ?? '');
    if (!text.trim()) return;
    const why = stopWhy(levelOf('line'));
    if (why) return say(`Run it: not sent — ${why}.`);
    const conn0 = ctx.S.conn;
    if (!(await ctx.confirm(M.lineConfirm(text, s)))) return input.focus();
    if (ctx.S.conn !== conn0 || !stillOn(s)) return say('Run it: nothing sent — the connection or the session changed while the confirm was open. Run it again.');
    if (sendLive(M.lineDraft(s, text), 'Run it') !== null) {
      typed = '';
      input.value = '';
    }
  }

  /** On a desk a key pressed with the mouse gives the keyboard back to the terminal. */
  function refocus() {
    if (surface) surface.focus();
  }

  function sendKey(name) {
    sendLive(M.keyDraft(session(), name), M.keyLabel(name));
  }

  /** A record op (a mute, a row): drawn at once, the record's value again if it is refused (L1: the latest input wins). */
  function sendRecord(draft, name, label) {
    const token = pend.set(name, draft.result);
    const sent = ctx.sendQueued(draft, (ans) => {
      if (ans.a === 'refused') say(`${label}: ${ans.code === 'ended' ? 'not known whether it was applied: the connection ended before its answer' : ans.reason}`);
      pend.answered(name, token, ans.a === 'seen');
      draw();
    }, { need: levelOf(draft.action) });
    if (!sent) pend.answered(name, token, false);
    draw();
  }

  async function act(id) {
    const s = session();
    const conn0 = ctx.S.conn;
    if (id === 'end') {
      if (!(await ctx.confirm(M.endConfirm(s)))) return;
      if (ctx.S.conn !== conn0) return say(`End: nothing sent — the connection to the PC ended while the confirm was open. Ask again.`);
      return sendLive(M.endDraft(s), `End '${s.name}'`, { then: (ans) => {
        if (ans && (ans.a === 'sent' || ans.a === 'seen')) {
          say(`Ended '${s.name}'.`);
          if (place.at === 'session' && place.name === s.name) go(null, { replace: true });
        }
      } });
    }
    if (id === 'rename') {
      const text = await ctx.ask({ title: `Rename '${s.name}'`, label: 'New name: letters, digits, - and _ (spaces become -)', initial: s.name, act: 'Rename',
        check: (t) => (M.cleanName(t) ? null : 'A name needs at least one letter or digit.') });
      if (text === null) return;
      if (ctx.S.conn !== conn0) return say('Rename: nothing sent — the connection to the PC ended while it was open. Ask again.');
      return sendLive(M.renameDraft(s, text), `Rename '${s.name}'`, { then: (ans) => {
        const to = M.renamedOf(ans);
        if (to && place.at === 'session' && place.name === s.name) go(M.target(to), { replace: true });
      } });
    }
    return null;
  }

  // ---- drawing ----

  function draw() {
    if (!root || !started) return;
    if (place.at === 'unknown') {
      els.session = null;
      return fill(root, h('h1', { text: 'Tmux' }), h('p', { class: 'note', text: `This page knows no place “${place.target}” in Tmux.` }), link('Sessions', null, 'r:sessions'));
    }
    if (place.at === 'sessions') return drawSessions();
    if (place.at === 'history') return drawHistory();
    return drawSession();
  }

  function headLine() {
    const sm = (last && last.summary) || {};
    return [sm.line, sm.detail].filter(Boolean).join(' · ');
  }

  function drawSessions() {
    root.className = 'page tmux';
    els.session = null;
    els.hist = null;
    if (!els.list || !root.contains(els.list)) {
      els.head = h('div');
      els.hosts = h('div', { class: 'tm-hosts' });
      els.list = h('div');
      els.settings = h('div');
      fill(root, els.head, els.list, h('h2', { class: 'tm-h2', text: 'Hosts' }), els.hosts, els.settings);
    }
    const now = pcNow();
    put(els.head, JSON.stringify([headLine(), refusals.get(M.DESC), !!last, ctx.S.reach.ok, last && last.asOf]), () => [
      h('h1', { text: 'Tmux' }),
      refusals.get(M.DESC) ? h('p', { class: 'note bad', text: `The PC did not describe Tmux: ${refusals.get(M.DESC)}` }) : null,
      last ? h('p', { class: 'note', text: [headLine(), ctx.S.reach.ok || typeof last.asOf !== 'number' ? null : `as the PC last described it at ${hhmm(last.asOf - ctx.S.pcOffset)}`].filter(Boolean).join(' · ') })
        : h('p', { class: 'note', text: 'Reading the sessions from the PC…' })]);
    const hosts = (last && last.hosts) || [];
    put(els.hosts, JSON.stringify([hosts, Math.floor(now / 1000), ctx.canAct('full'), offered('new')]), () => hosts.map((x) => {
      const line = M.healthLine(x, now);
      return h('div', { class: 'tm-host' + (x.health && x.health.state && x.health.state !== 'ok' ? ` ${x.health.state}` : '') },
        h('span', { class: 'tm-hn', text: x.name }), h('span', { class: 'tm-hl', text: line }),
        offered('new') ? ctx.actionButton({ label: 'New session', need: levelOf('new'), onClick: () => newSession(x.name) }) : null);
    }));
    const list = M.sessionsOf(last);
    const many = new Set(list.map((s) => s.host)).size > 1;
    put(els.list, JSON.stringify([list, many, Math.floor(now / 60000), list.map((s) => M.mutedOf(s, rec, pend))]), () => (last && !list.length
      ? h('p', { class: 'note', text: 'No tmux sessions. New session starts one.' })
      : h('ul', { class: 'tm-list' }, list.map((s) => {
        const muted = M.mutedOf(s, rec, pend);
        return h('li', { class: 'tm-item' + (s.waiting ? ' waiting' : '') },
          h('a', { href: `/w/tmux/${M.target(s)}`, class: 'tm-sess', 'data-key': `s:${s.host}/${s.name}`, focus: true,
            on: { click: (e) => { e.preventDefault(); go(M.target(s)); } } },
          h('span', { class: 'tm-sn' }, h('span', { text: s.name }), many ? h('span', { class: 'tm-sh', text: ` on ${s.host}` }) : null,
            s.waiting ? h('span', { class: 'tm-mark wait', text: 'waiting for input' }) : null,
            muted ? h('span', { class: 'tm-mark', text: 'alerts muted' }) : null),
          s.lastLine ? h('span', { class: 'tm-last', text: s.lastLine }) : null,
          h('span', { class: 'tm-sl', text: M.sessionLine(s, now) })));
      }))));
    drawSettings();
    clearTimeout(ticker);
    // the ages and a host's quiet count move on the PC's clock
    ticker = setTimeout(() => { if (place.at === 'sessions') drawSessions(); }, 15000);
  }

  function drawSettings() {
    const rows = ((last && last.settings) || []).filter((r) => r.scope !== 'device');
    put(els.settings, JSON.stringify([rows, rows.map((r) => M.rowValue(r, rec, pend)), ctx.S.level, ctx.canAct('full'), setOpen]), () => {
      const box = h('details', { class: 'tm-set', open: setOpen, on: { toggle: () => { setOpen = box.open; } } },
        h('summary', { 'data-key': 'set', focus: true }, 'Settings'),
        h('p', { class: 'note', text: 'Shared rows apply on every device; rows marked "the glasses" change only how the glasses draw Tmux. Alerts pop on the glasses only.' }),
        rows.map(setRow));
      return box;
    });
  }

  function setRow(row) {
    const v = M.rowValue(row, rec, pend);
    const need = row.level || 'full';
    const label = h('span', { text: row.label });
    const words = row.type === 'bool' ? (v === false ? 'off' : 'on') : (M.optionsOf(row).find(([id]) => String(id) === String(v)) || [v, String(v)])[1];
    if (!atLeast(ctx.S.level, need)) return h('div', { class: 'trow' }, label, h('span', { text: words }), h('span', { class: 'note', text: `changing it needs level ${need}` }));
    const why = ctx.canAct(need);
    const send = (value) => sendRecord(M.setDraft(row, value), row.id, row.label);
    const key = `set:${row.id}`;
    // Alerts: absent is on (TMUX.md verdict 3)
    const ctl = row.type === 'bool'
      ? h('input', { type: 'checkbox', checked: v !== false, 'data-key': key, focus: true, class: why ? 'dim' : null, 'data-why': why, on: { change: (e) => send(!!e.target.checked) } })
      : h('select', { 'data-key': key, focus: true, class: why ? 'dim' : null, 'data-why': why, on: { change: (e) => {
        const o = M.optionsOf(row).find(([id]) => String(id) === e.target.value);
        send(o ? o[0] : e.target.value);
      } } }, M.optionsOf(row).map(([id, l]) => h('option', { value: id, selected: String(id) === String(v) }, l)));
    return h('label', { class: 'trow' }, label, ctl, row.scope === 'glasses' ? h('span', { class: 'note', text: 'the glasses' }) : null);
  }

  function newSession(host) {
    sendLive(M.newDraft(host), `New session on ${host}`, { then: (ans) => {
      const name = M.newName(ans);
      if (ans && (ans.a === 'sent' || ans.a === 'seen') && name) go(M.target({ host, name }));
    } });
  }

  // ---- a session ----

  function buildSession() {
    const s = session();
    const isPhone = phone();
    if (surface) surface.destroy();
    const made = surfaceFor(isPhone, libWhy)({ label: `Terminal of ${s.name}`, phone: isPhone, onPaste: pasteFlow, onFocus: () => drawStatus(),
      // the terminal's bytes (his keys, his mouse): each burst one `raw` op, in order
      onBytes: (data) => sendRaw(data, 'Keys'),
      onReady: () => { if (surface === made && place.at === 'session') listen(); },
      onFail: (why) => { if (surface === made) terminalFailed(why); } });
    surface = made;
    els.hist = null;
    els.session = {
      head: h('header', { class: 'tm-head' }),
      status: h('div', { class: 'tm-status', tabindex: '-1', role: 'status' }),
      dock: h('div', { class: 'tm-dock' }),
      more: h('div', { class: 'tm-more' }),
    };
    const e = els.session;
    root.className = `page tmux at-session${isPhone ? ' phone' : ''}`;
    fill(root, e.head, e.status, surface.el, e.dock, e.more);
    if (feed.frame && surface.feed === 'frame' && !feed.frame.data) {
      surface.take(feed.frame);
      feed.drew();
    }
  }

  function drawSession() {
    if (!root || place.at !== 'session') return;
    if (!els.session || !surface || !root.contains(surface.el)) {
      buildSession();
      if (surface.feed === 'frame' && liveScope !== sourceOf(session())) listen();
    }
    const s = session();
    checkGone(s);
    const e = els.session;
    const muted = M.mutedOf(s, rec, pend);
    root.classList.toggle('gone', !!gone);
    put(e.head, JSON.stringify([s, muted, gone, canvas && [canvas.cols, canvas.rows]]), () => [
      link('Sessions', null, 'r:back', 'tm-back'),
      h('h1', { text: s.name }),
      h('span', { class: 'tm-hostname', text: `on ${s.host}` }),
      canvas && canvas.cols ? h('span', { class: 'note', text: `${canvas.cols}×${canvas.rows}` }) : null,
      s.attached ? h('span', { class: 'tm-mark', text: 'attached' }) : null,
      s.waiting && !gone ? h('span', { class: 'tm-mark wait', text: 'waiting for input' }) : null,
      muted ? h('span', { class: 'tm-mark', text: 'alerts muted' }) : null]);
    drawStatus();
    drawDock();
    drawMore();
  }

  /** The open session missing from the PC's list: ended, or its host not answering (the list says nothing then). */
  function checkGone(s) {
    if (!last || (gone && (gone.ended || gone.refused)) || !ctx.S.reach.ok) return;
    if (M.findSession(last, s.host, s.name)) {
      if (gone && !gone.ended) {
        gone = null;
        if (!liveScope && (surface.feed === 'frame' || surface.ready())) listen();
      }
      return;
    }
    const host = (last.hosts || []).find((x) => x.name === s.host);
    if (host && host.health && host.health.state && host.health.state !== 'ok') gone = { ended: false, why: `${s.host} is not answering, so the PC does not list this session` };
    else {
      gone = { ended: true, why: `it is no longer on ${s.host}` };
      if (liveScope) ctx.unlive(liveScope);
      liveScope = null;
    }
  }

  function drawStatus() {
    if (!els || !els.session || place.at !== 'session') return;
    const s = session();
    const lines = [];
    if (gone) lines.push({ cls: 'bad', text: gone.ended ? `This session ended: ${gone.why}. The last picture stays below; nothing can be sent to it.` : `Not listed: ${gone.why}.` });
    else lines.push(M.feedLine(feed, { reach: ctx.S.reach, pcNow: pcNow(), pcOffset: ctx.S.pcOffset }));
    if (refusals.get(liveScope)) lines.push({ cls: 'bad', text: `The PC refused the session's output: ${refusals.get(liveScope)}` });
    if (!gone && !phone()) {
      if (libWhy) lines.push({ cls: 'warn', text: `No terminal here: ${libWhy}. The PC's pictures are shown; type with Type… and the keys below.` });
      else if (surface && surface.focused()) lines.push({ cls: 'typing', text: `Typing goes to ${s.name}. Shift+Esc or a click outside the terminal stops it.` });
      else lines.push({ cls: 'note', text: `Click the terminal to type into ${s.name}; the keyboard goes to it only while it has focus.` });
    }
    const now = Date.now();
    const oldest = [...flying.values()].reduce((a, w) => Math.min(a, w.at), Infinity);
    if (flying.size && now - oldest >= 1000) lines.push({ cls: 'warn', text: `${flying.size === 1 ? 'One key or action' : `${flying.size} keys or actions`} sent · waiting for the PC ${M.dur(now - oldest)}` });
    if (notSent) lines.push({ cls: 'bad', text: `${notSent === 1 ? 'One key' : `${notSent} keys`} not sent: ${stopWhy() || 'refused before sending'}.` });
    if (unknown) lines.push({ cls: 'warn', text: `${unknown === 1 ? 'One key' : `${unknown} keys`}: not known whether they ran — the connection ended before their answers; look at the session.` });
    fill(els.session.status, lines.map((l) => h('p', { class: `tm-line ${l.cls}`, text: l.text })));
    clearTimeout(ticker);
    // a wait's age and an away picture's age move on
    if (flying.size || (!ctx.S.reach.ok && feed.frame)) ticker = setTimeout(drawStatus, 1000);
  }

  function drawDock() {
    const e = els.session;
    const s = session();
    const keys = M.keysOf(last);
    const why = stopWhy(levelOf('key'));
    const term = surface && surface.feed === 'stream';
    put(e.dock, JSON.stringify([keys, s.waiting, why, offered('key'), offered('snippet'), offered('line'), offered('raw'), phone(), term, s.lastLine]), () => {
      const out = [];
      if (s.waiting && !gone) out.push(h('p', { class: 'tm-prompt', text: `Waiting for input${s.lastLine ? ` at “${s.lastLine}”` : ''}.` }));
      if (offered('key') && keys.quick.length) {
        out.push(h('div', { class: 'tm-keys', role: 'group', 'aria-label': 'Quick keys' }, keys.quick.map((n) =>
          ctx.actionButton({ label: M.keyLabel(n), need: levelOf('key'), why: gone ? stopWhy() : null, cls: 'tm-k', onClick: () => { sendKey(n); refocus(); } }))));
      }
      if (keys.bad.length) out.push(h('p', { class: 'note', text: `Quick keys the PC serves that this page cannot send: ${keys.bad.join(', ')}` }));
      if (term && offered('raw')) {
        out.push(h('div', { class: 'tm-keys kept', role: 'group', 'aria-label': 'Keys the browser keeps' }, h('span', { class: 'note', text: 'Keys the browser keeps:' }),
          M.BAR_KEYS.map(([l, data]) => ctx.actionButton({ label: l, need: levelOf('raw'), why: gone ? stopWhy() : null, cls: 'tm-k', onClick: () => { sendRaw(data, l, false); refocus(); } }))));
      }
      if (offered('line')) {
        const input = h('input', { type: 'text', class: 'tm-in', 'aria-label': `Type a line for ${s.name}`, placeholder: 'Type…', 'data-key': 'type', focus: true,
          enterkeyhint: 'send', autocapitalize: 'off', autocomplete: 'off', spellcheck: 'false' });
        input.value = typed;
        input.addEventListener('input', () => { typed = String(input.value ?? ''); });
        // a paste of more than one line into the field goes through the paste rule; the field would join its lines unseen
        input.addEventListener('paste', (ev) => {
          const text = ev.clipboardData ? ev.clipboardData.getData('text/plain') : '';
          if (!M.pasteShape(text).asks) return;
          ev.preventDefault();
          pasteFlow(text);
        });
        out.push(h('form', { class: 'tm-type', on: { submit: (ev) => { ev.preventDefault(); runLine(input); } } }, input,
          ctx.actionButton({ label: 'Run it', need: levelOf('line'), why: gone ? stopWhy() : null, onClick: () => runLine(input) })));
      }
      if (offered('snippet') && keys.snippets.length) {
        out.push(h('details', { class: 'tm-snips' }, h('summary', { focus: true, 'data-key': 'snips' }, 'Snippets'),
          h('div', { class: 'tm-keys' }, keys.snippets.map((t) => ctx.actionButton({ label: t, need: levelOf('snippet'), why: gone ? stopWhy() : null, cls: 'tm-k',
            onClick: () => sendLive(M.snippetDraft(s, t), `Snippet ${t}`) })))));
      }
      if (!offered('key') && !offered('raw') && !offered('line')) out.push(h('p', { class: 'note', text: 'The PC lists no keys for this device: the session is shown, nothing can be sent to it.' }));
      return out;
    });
  }

  function drawMore() {
    const e = els.session;
    const s = session();
    const muted = M.mutedOf(s, rec, pend);
    const wins = (canvas && canvas.windows) || [];
    put(e.more, JSON.stringify([s.name, muted, wins, gone, ctx.canAct('full'), [...acts().keys()], phone()]), () => {
      const parts = [h('div', { class: 'tm-acts' },
        ctx.actionButton({ label: 'Refresh', need: 'full', why: gone && gone.ended ? stopWhy() : null, onClick: () => {
          // asked again, the PC answers a fresh init; a refusal is asked again
          if (gone && gone.refused) gone = null;
          refusals.delete(liveScope);
          listen();
          drawSession();
        } }),
        link('History', M.historyTarget(s), 'r:history', 'tm-hlink'),
        offered('mute') ? ctx.actionButton({ label: muted ? 'Unmute alerts' : 'Mute alerts', need: levelOf('mute'),
          onClick: () => sendRecord(M.muteDraft(s, !muted), M.muteName(s), muted ? 'Unmute alerts' : 'Mute alerts') }) : null,
        offered('rename') ? ctx.actionButton({ label: 'Rename…', need: levelOf('rename'), why: gone ? stopWhy() : null, onClick: () => act('rename') }) : null,
        offered('end') ? ctx.actionButton({ label: 'End session…', need: levelOf('end'), why: gone ? stopWhy() : null, cls: 'danger', onClick: () => act('end') }) : null),
      wins.length > 1 ? h('div', { class: 'tm-wins' }, h('h2', { text: 'Windows' }), h('ul', { class: 'tm-wl' }, wins.map((w) => h('li', null,
        h('span', { text: `${w.idx}: ${w.name}` }), w.active ? h('span', { class: 'tm-mark', text: 'active' }) : null, w.bell ? h('span', { class: 'tm-mark wait', text: 'bell' }) : null,
        !w.active && offered('select') ? ctx.actionButton({ label: 'Select', need: levelOf('select'), why: gone ? stopWhy() : null,
          onClick: () => sendLive(M.selectDraft(s, w.idx), `Select window ${w.idx}`) }) : null))),
      offered('select') ? h('p', { class: 'note', text: 'Select changes the active window for every client attached to the session.' }) : null) : null];
      // a phone keeps its screen for the session: the actions fold into one row
      return phone() ? h('details', { class: 'tm-fold' }, h('summary', { focus: true, 'data-key': 'fold' }, 'Session: windows, history, actions'), parts) : parts;
    });
  }

  // ---- history ----

  /** The history page: its head drawn again as its state changes; its lines painted once per answer (and again for a
   *  new width or theme), so a redraw never loses the place being read. */
  function drawHistory(repaint = false) {
    const s = session();
    const rows = M.historyRows(hist);
    if (!els.hist || !root.contains(els.hist.term)) {
      root.className = 'page tmux at-history';
      els.session = null;
      const box = h('div', { class: 'tm-rows' });
      els.hist = { head: h('div'), box, term: h('div', { class: 'tm-term hist', tabindex: '0', 'aria-label': `History of ${s.name}` }, box), painted: null };
      fill(root, els.hist.head, els.hist.term);
    }
    const e = els.hist;
    put(e.head, JSON.stringify([s.name, refusals.get(histScope), !!hist, rows.length, hist && hist.capturedAt, ctx.canAct('full')]), () => [
      h('header', { class: 'tm-head' }, link(`Back to ${s.name}`, M.target(s), 'r:back', 'tm-back'), h('h1', { text: `History of ${s.name}` }),
        ctx.actionButton({ label: 'Refresh', need: 'full', onClick: () => { closeHistory(); openHistory(); draw(); } })),
      refusals.get(histScope) ? h('p', { class: 'note bad', text: `The PC did not send the history: ${refusals.get(histScope)}` })
        : hist ? h('p', { class: 'note', text: M.historyLine(hist, rows, ctx.S.pcOffset) }) : h('p', { class: 'note', text: 'Reading the history from the PC…' })]);
    e.term.hidden = !hist;
    if (!hist || (e.painted === hist && !repaint)) return;
    const fresh = e.painted !== hist;
    e.painted = hist;
    // one page, every line in it (the browser's find searches all of them), wrapped at the view's width on the grid
    const pal = T.PALETTES[T.themeOf(document.documentElement, typeof window.matchMedia === 'function' ? window.matchMedia.bind(window) : null)];
    const fontPx = phone() ? 13 : 14;
    T.setMetrics(e.box, { fontPx, cw: fontPx * 0.6, ch: Math.round(fontPx * T.LINE) });
    const cw = T.measure(e.box) || fontPx * 0.6;
    T.setMetrics(e.box, { fontPx, cw, ch: Math.round(fontPx * T.LINE) });
    const avail = roomFor(e.term);
    if (repaint) T.unpaint(e.box);
    T.paint(e.box, { lines: rows, cols: 0, rows: 0 }, { wrap: avail > 0 ? Math.max(20, Math.floor(avail / cw)) : 100, pal });
    // a new answer opens at the newest lines, as the glasses' history does (the page scrolls, not the box)
    const last = e.box.children[e.box.children.length - 1];
    if (fresh && last && last.scrollIntoView) last.scrollIntoView({ block: 'end' });
  }

  // ---- the page's own events ----

  const onVisibility = () => {
    // hidden → need none (the PC stops sending to this viewer); shown → focus, a fresh init
    if (liveScope) ctx.live(liveScope, needNow(), onLive);
  };
  const onWidth = () => {
    if (place.at === 'session') {
      // a phone's width and a desk's draw by different surfaces (and sources)
      buildSession();
      if (surface.feed === 'frame') listen();
      drawSession();
    } else if (place.at === 'history') drawHistory(true);
  };
  const onTheme = () => {
    if (surface) surface.retheme();
    if (place.at === 'history') drawHistory(true);
  };
  const onResize = () => { if (surface) surface.refit(); };
  const onFonts = () => { if (surface) surface.refit(); };

  return {
    mount(r, opts = {}) {
      root = h('section', { class: 'page tmux' });
      els = {};
      if (M.belowWhy(ctx.S.level)) {
        drawBelow();
        place = M.placeOf(r.target) || { at: 'sessions' };
        return root;
      }
      start();
      last = ctx.desc(M.DESC) || null;
      document.addEventListener('visibilitychange', onVisibility);
      if (mq && mq.addEventListener) mq.addEventListener('change', onWidth);
      if (darkMq && darkMq.addEventListener) darkMq.addEventListener('change', onTheme);
      if (typeof MutationObserver === 'function') {
        observer = new MutationObserver(onTheme);
        observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
      }
      if (typeof window.addEventListener === 'function') window.addEventListener('resize', onResize);
      if (document.fonts && document.fonts.addEventListener) document.fonts.addEventListener('loadingdone', onFonts);
      show(r, opts);
      return root;
    },
    update(r, opts = {}) {
      if (!started) return true;
      show(r, opts);
      return true;
    },
    unmount() {
      stop();
      clearTimeout(ticker);
      document.removeEventListener('visibilitychange', onVisibility);
      if (mq && mq.removeEventListener) mq.removeEventListener('change', onWidth);
      if (darkMq && darkMq.removeEventListener) darkMq.removeEventListener('change', onTheme);
      if (observer) observer.disconnect();
      if (typeof window.removeEventListener === 'function') window.removeEventListener('resize', onResize);
      if (document.fonts && document.fonts.removeEventListener) document.fonts.removeEventListener('loadingdone', onFonts);
      root = null;
    },
    back() {
      if (!started) return false;
      if (place.at === 'history') go(M.target(session()));
      else if (place.at === 'session' || place.at === 'unknown') go(null);
      else return false;
      return true;
    },
    onDesc(scope, d) {
      if (!started) return;
      if (scope === M.DESC) {
        last = d;
        refusals.delete(scope);
        draw();
      } else if (scope === canvasScope) {
        canvas = d;
        drawSession();
      } else if (scope === histScope) {
        hist = d;
        refusals.delete(scope);
        draw();
      }
    },
    onRefused(scope, a) {
      if (!started) return false;
      const why = (a && a.reason) || `refused (${a && a.code})`;
      if (scope === liveScope) {
        refusals.set(scope, why);
        // the PC does not serve the session's pictures: said, the last picture kept and nothing sent to it until
        // Refresh asks again (never asked again by itself: a refusal repeated at every description is no news)
        gone = { ended: false, refused: true, why: `the PC does not send its pictures (${why})` };
        ctx.unlive(liveScope);
        liveScope = null;
        drawSession();
        return true;
      }
      if (![M.DESC, canvasScope, histScope].includes(scope)) return false;
      refusals.set(scope, why);
      draw();
      return true;
    },
    onWindowRecord(w, changed) {
      if (w !== M.W || !started) return;
      pend.landed(changed, rec);
      draw();
    },
    onWelcome() {
      if (!root) return;
      // a level changed by a re-approval: below full everything held is let go and nothing of any session stays shown
      if (M.belowWhy(ctx.S.level)) {
        if (started) stop();
        return drawBelow();
      }
      if (!started) {
        start();
        return show({ target: null });
      }
      // a new connection: the shell asks the live source again (a fresh init); the last picture stays, said as old,
      // until it comes; what was in flight was answered at the connection's end
      if (liveScope) feed.stale = 'reconnect';
      flying.clear();
      draw();
    },
    onReach() {
      if (ctx.S.reach.ok) notSent = 0;
      draw();
    },
  };
}
