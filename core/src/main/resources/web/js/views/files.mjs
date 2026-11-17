// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-2, package Q5w (spec §4.3; INTERFACES.md W-20, W-34, W-35; decisions A.6, A.7, A.8, A.12, A.15): Files in a
// browser. The locations with their capacity; a folder a page at a time in the shared order, with the shared hidden
// row, as a list or a grid of thumbnails; an entry's pane with its actions and its preview (a picture in colour, text a
// chunk at a time, a PDF's pages drawn on the PC, an EPUB's way into Reader, anything else Download); several entries
// selected for the trash or for download links; the trash with Restore and Delete forever; files dropped on a folder
// uploaded one at a time with their progress; the PC's one job slot with its age. Files is full as a whole: a device
// below full is told so and is shown nothing of the tree, nor asks for it. Every decision is in `files-model.mjs`.

import { h, fill, bar, icon, keepFocus } from '../dom.mjs';
import { format } from '../address.mjs';
import * as M from '../files-model.mjs';

const ROW_PX = 40;
const TEXT_NEAR_PX = 1500;
const FINISHED = ['done', 'failed', 'cut', 'unsent'];

/** The page's uploads, one list per page (kept on its `ctx`): they outlive a visit to another window, so a file being
 *  sent goes on to its end and says how it ended. `hooks` = the mounted view's {draw, pump}, or null. */
function pageUploads(ctx) {
  if (!ctx.filesUploads) ctx.filesUploads = { list: new M.Uploads(), xhrs: new Map(), hooks: null, guarded: false };
  return ctx.filesUploads;
}

/** Draws `make()` into `box` only when `sig` differs from what it holds (a control in use is not replaced). */
function put(box, sig, make) {
  if (box.dataset.sig === sig) return;
  box.dataset.sig = sig;
  keepFocus(box, 'data-key', () => fill(box, make()));
}

/** The PUT of one upload (spec §2.4: `PUT /blob/upload/<id>`, the file as the body; progress from the request's upload
 *  events, since `fetch` has none on Chromium 108). No time limit: Cancel ends it. L1 P41: ended before a byte left,
 *  it calls `letGo()` (the page's connection is made again, so the PC lets go of its place); its supervisor frees the
 *  rest. */
function sendBody(up, u, id, say, where, letGo) {
  const { list: uploads, xhrs } = up;
  if (!up.guarded && typeof window.addEventListener === 'function') {
    up.guarded = true;
    // a page closed mid-upload cuts it off; the browser asks first
    window.addEventListener('beforeunload', (e) => {
      if (!uploads.sending) return;
      e.preventDefault();
      e.returnValue = '';
    });
  }
  const x = new XMLHttpRequest();
  u.state = 'sending';
  u.sent = 0;
  xhrs.set(u.id, x);
  const redraw = () => up.hooks && up.hooks.draw();
  const next = () => up.hooks && up.hooks.pump();
  const cut = (why, stopAll) => {
    xhrs.delete(u.id);
    u.state = 'cut';
    u.reason = why;
    say(`'${u.name}': the upload was cut off at ${M.bytes(u.sent)} of ${M.bytes(u.size)} (${why}); nothing is kept of it (the PC deletes a cut-off upload).`);
    if (!u.sent) letGo();
    if (stopAll) {
      const n = uploads.stopRest(`not sent: the upload before it was cut off (${why})`);
      if (n) say(`${n} more ${n === 1 ? 'file was' : 'files were'} not uploaded. A page keeps no queue; drop them again when the PC answers.`);
    }
    redraw();
    next();
  };
  x.open('PUT', `/blob/upload/${encodeURIComponent(id)}`);
  x.upload.onprogress = (e) => {
    u.sent = e.loaded;
    redraw();
  };
  x.onload = () => {
    xhrs.delete(u.id);
    const r = M.putAnswer(x.status, x.responseText);
    if (r.ok) {
      u.state = 'done';
      u.sent = u.size;
      u.as = r.name || u.name;
      // L1 P40: the PC never replaces a file; its answer names the one it placed (a taken name numbered)
      say(r.name ? `Uploaded '${r.name}' to ${where}.` : `Uploaded '${u.name}' to ${where} (the PC's answer named no file: ${x.responseText}).`);
    } else {
      u.state = 'failed';
      u.reason = r.reason;
      say(`'${u.name}' was not uploaded: ${r.reason}`);
    }
    redraw();
    next();
  };
  x.onerror = () => cut('the connection to the PC dropped', true);
  x.onabort = () => cut('cancelled on this page', false);
  x.send(u.file);
}

export function filesView(ctx) {
  const store = ctx.store;
  const rec = ctx.rec(M.W);
  const sel = new M.Selection();
  const flying = new Map(); // k → {label, n, at}: program ops sent and not answered
  const myJobs = new Map(); // job id → {label, then}: Stats this page asked, whose facts it shows at the job's end
  const refusals = new Map(); // scope → the PC's reason
  const pend = {}; // a register sent and not yet in the record: its value meanwhile
  const up = pageUploads(ctx);
  const uploads = up.list;
  const xhrs = up.xhrs;
  const mq = typeof window.matchMedia === 'function' ? window.matchMedia('(max-width: 760px)') : null;
  let root = null;
  let els = null;
  let started = false;
  let place = { at: 'locations' };
  let lastR = null;
  let last = null; // desc/files
  let listing = null;
  let listCount = null;
  let textDoc = null;
  let pdfScope = null;
  let trashScope = null;
  let forceText = null;
  let reveal = null; // an entry the address opened: its row is scrolled into the list's view once it is held (desk)
  let panel = null; // {kind: 'stats' | 'download', …}
  let ticker = null;
  let retryTimer = null;
  let setOpen = false;
  let mode = store.get(M.KEYS.view) === 'grid' ? 'grid' : 'list';

  const narrow = () => !!(mq && mq.matches);
  const say = (text, opts = {}) => ctx.say(text, { from: 'Files', ...opts });
  const acts = () => M.actionsOf(last);
  const locs = () => (last && Array.isArray(last.locations) ? last.locations : []);
  const sortNow = () => pend.sort ?? M.sortOf(rec);
  const hiddenNow = () => pend.hidden ?? M.hiddenOf(rec);
  const clipNow = () => (pend.clip !== undefined ? pend.clip : M.clipOf(rec));
  const pcNow = ctx.pcNow;
  const where = (dir) => M.folderLabel(dir, locs());
  const go = (target, anchor) => ctx.go(target ? { window: M.W, target, ...(anchor ? { anchor } : {}) } : { window: M.W });
  const link = (text, target, key, cls) => h('a', { href: format(target ? { window: M.W, target } : { window: M.W }), class: cls || null, 'data-key': key, focus: true,
    on: { click: (e) => { e.preventDefault(); go(target); } } }, text);
  const held = () => new Set(listing ? listing.rows().map((r) => r.name) : []);

  // ---- level: Files is full as a whole (C§10.4); below it nothing is asked and nothing of the tree is shown ----

  function start() {
    if (started) return;
    started = true;
    ctx.sub(M.DESC);
    ctx.watch(M.W);
  }

  function stop() {
    if (!started) return;
    started = false;
    setListing(null);
    dropText();
    pdfScope = hold(pdfScope, null);
    trashScope = hold(trashScope, null);
    ctx.unsub(M.DESC);
    ctx.unwatch(M.W);
    last = null;
    sel.clear();
    panel = null;
  }

  function drawBelow() {
    const why = M.belowWhy(ctx.S.level);
    fill(root, h('h1', { text: 'Files' }),
      h('p', { class: 'note', text: `${why}: nothing of the PC's files is shown here, and this page asks the PC for none of it.` }),
      h('p', { class: 'note', text: 'Approve this device at level full on the PC to use Files from it.' }));
  }

  // ---- program ops (spec §2.4; A.7): Sent or Refused; a job's end is the slot's line (L1 P44) ----

  /** Sends action `a` over `targets`; `then(outcome)` gets {ok, words, facts} at the call's answer, or at its job's end
   *  when it names one (Stats); a job's end is otherwise said by the slot's line. */
  function send(a, targets, params, n, then) {
    const w = { label: a.label, n, at: Date.now() };
    const k = ctx.sendProgram(M.programDraft(a.id, targets, params), a.level || 'full', (ans) => {
      flying.delete(k);
      drawJob();
      const bad = M.answerLine(a.label, ans);
      if (bad) return (then || sayOutcome)({ ok: false, words: bad, ans });
      const job = M.jobOf(ans);
      if (job !== null) {
        if (!then) return null;
        myJobs.set(job, { label: a.label, then });
        return checkJobs();
      }
      const res = ans.result && typeof ans.result === 'object' ? ans.result : {};
      const words = a.id === 'openOnPc' ? `${a.label}: sent to the PC.` : `${a.label}: done${res.path ? ` → ${res.path}` : res.text ? ` — ${res.text}` : ''}`;
      return (then || sayOutcome)({ ok: true, words, facts: ans.result });
    });
    if (k === null) return null;
    flying.set(k, w);
    drawJob();
    return k;
  }

  function sayOutcome(o) {
    say(o.words);
  }

  /** Program op `id` after its confirm, if it has one (L1 P33: it goes on the connection current at the act). */
  async function act(id, targets, names, params) {
    const a = acts().get(id);
    if (!a) return;
    const c = M.confirmFor(id, names);
    if (c && !(await ctx.confirm(c))) return;
    send(a, targets, params, targets.length);
  }

  /** The Stats this page asked: at their job's end (done or failed) their outcome is handed on. */
  function checkJobs() {
    const jobs = M.jobsOf(last);
    for (const [id, m] of [...myJobs]) {
      const j = jobs.find((x) => String(x.id) === id);
      if (j && (j.state === 'done' || j.state === 'failed')) {
        myJobs.delete(id);
        m.then({ ok: j.state === 'done', words: M.jobOutcome(m.label, j), facts: j.answer && typeof j.answer === 'object' ? j.answer : null });
      }
    }
  }

  /** A button for action `id`, or null when this device's level does not offer it; a slot action is dim while busy. */
  function btn(id, opts = {}) {
    const a = acts().get(id);
    if (!M.offered(a, ctx.S.level)) return null;
    const why = opts.why || (M.JOB_ACTIONS.has(id) ? M.busyWhy(last) : null);
    // seen in headless Firefox: every `i` action in red read as a warning on New folder and Upload; only the act that
    // cannot be undone is marked
    return ctx.actionButton({ label: opts.label || a.label, need: a.level || 'full', why, cls: opts.cls ?? (id === 'purge' ? 'danger' : ''), onClick: opts.onClick });
  }

  /** Download (a view action, nothing sent): a link to the file with `dl=1`; dim while the PC does not answer. */
  function dlLink(path, name, key) {
    if (!M.offered(acts().get('download'), ctx.S.level)) return null;
    const why = ctx.canAct('full');
    if (why) return ctx.actionButton({ label: 'Download', need: 'full', onClick: () => {} });
    return h('a', { class: 'btn', href: M.blobUrl(path, { dl: true }), download: name, 'data-key': key || `dl:${name}`, focus: true }, 'Download');
  }

  // ---- record ops: the clipboard, sort, hidden, the Size row (registers; L1 C1: the latest input wins) ----

  /** `info` names the label and level when the description lists no action of that id (a settings row). */
  function setReg(action, name, value, info) {
    const a = info || acts().get(action);
    if (!a) return;
    pend[name] = value;
    const at = pcNow();
    const sent = ctx.sendQueued(M.regDraft(action, name, value, at), (ans) => {
      if (ans.a === 'refused') say(`${a.label}: ${ans.reason}`);
      // seen: the value stays shown until the record's change arrives (the PC answers, then publishes it) — unless the
      // record already shows it or a later input: an op that changed nothing publishes no change
      if (ans.a !== 'seen' || M.settled(rec.item(M.W, name), value, at)) delete pend[name];
      draw();
    }, { need: a.level || 'full' });
    if (!sent) delete pend[name];
    draw();
  }

  const setClip = (verb, path) => setReg(verb, 'clip', { verb, path });

  function paste(dest) {
    const took = clipNow();
    const a = acts().get('paste');
    if (!took || !a) return;
    // S1-2 R0 §3: a pasted cut's clipboard is cleared in ONE place, the PC's core, when the move lands (only while the
    // clip still names that cut); the record's change reaches this page like any other
    send(a, [dest], { verb: took.verb, path: took.path }, 1);
  }

  async function rename(path, name) {
    const a = acts().get('rename');
    const taken = held();
    const to = await ctx.ask({ title: `Rename '${name}'`, label: 'New name', initial: name, act: 'Rename', check: (v) => M.nameWhy(v, taken, name) });
    if (to === null || !a) return;
    send(a, [path], { name: to }, 1);
  }

  async function mkdir(dir) {
    const a = acts().get('mkdir');
    const taken = held();
    const name = await ctx.ask({ title: `New folder in ${where(dir)}`, label: 'Name', initial: '', act: 'Create', check: (v) => M.nameWhy(v, taken) });
    if (name === null || !a) return;
    send(a, [dir], { name }, 1);
  }

  function statsOf(path, name) {
    const a = acts().get('stats');
    if (!a) return;
    send(a, [path], null, 1, (o) => {
      const facts = M.statFacts(o.facts);
      if (!o.ok || !facts.length) return say(o.words);
      panel = { kind: 'stats', name, facts };
      drawPanel();
    });
  }

  // ---- uploads: drop files on a folder; one at a time; the PC numbers a taken name (L1 P40) ----

  function startUpload(files, dest) {
    const list = [...(files || [])];
    if (!list.length) return;
    if (!M.offered(acts().get('upload'), ctx.S.level)) return say('Upload is not offered on this device.');
    uploads.add(list, dest);
    drawUploads();
    pump();
  }

  function stopAll(why) {
    const n = uploads.stopRest(`not sent: ${why}`);
    if (n) say(`${n} ${n === 1 ? 'file was' : 'files were'} not uploaded: ${why}. A page keeps no queue; drop them again when the PC answers.`);
  }

  function pump() {
    const u = uploads.next();
    if (!u) return drawUploads();
    const why = ctx.canAct('full');
    if (why) {
      stopAll(why);
      return drawUploads();
    }
    return place_(u);
  }

  /** The `upload` op: the PC mints a single-use id for this device and holds its slot; then the body. */
  function place_(u) {
    const a = acts().get('upload');
    u.state = 'op';
    drawUploads();
    const dest = u.dest;
    const w = { label: `Upload '${u.name}'`, n: 1, at: Date.now() };
    const k = ctx.sendProgram(M.programDraft('upload', [dest], { file: { name: u.name, size: u.size } }), a.level || 'full', (ans) => {
      flying.delete(k);
      drawJob();
      const id = ans && ans.a === 'sent' && ans.result && typeof ans.result === 'object' ? ans.result.upload : undefined;
      if (id === undefined || id === null) {
        u.state = 'failed';
        u.reason = ans && ans.a === 'refused' ? ans.reason || `refused (${ans.code})` : `the PC gave no upload id (${ans ? ans.a : 'no answer'})`;
        say(`'${u.name}' was not uploaded: ${u.reason}`);
        // the slot is another device's: every file after it would be refused the same way
        if (/busy/.test(u.reason)) stopAll('the PC is busy with another operation');
        return up.hooks ? up.hooks.pump() : drawUploads();
      }
      const conn = ctx.S.conn;
      sendBody(up, u, String(id), say, where(dest), () => letGo(u, conn));
    });
    if (k === null) {
      u.state = 'unsent';
      u.reason = 'not sent';
      stopAll('the PC does not answer');
      return drawUploads();
    }
    flying.set(k, w);
    drawJob();
  }

  /** L1 P41 (review B-S4): a PUT that ended before a byte left: the page's connection is made again, and the PC lets go
   *  of every place that connection held whose body had not begun (its other ops in flight are answered "not known
   *  whether it ran"); the PC's supervisor frees what is left. A connection that has ended already let it go. */
  function letGo(u, conn) {
    if (ctx.S.conn !== conn) return;
    u.freed = 'this page reconnected, so the PC lets go of its place';
    ctx.reconnect();
  }

  function drawUploads() {
    if (!root || !started) return;
    const items = uploads.items;
    if (!items.length) return fill(els.uploads);
    const done = items.filter((u) => FINISHED.includes(u.state)).length;
    const waiting = items.filter((u) => u.state === 'waiting').length;
    fill(els.uploads, h('section', { class: 'fuploads', 'aria-label': 'Uploads' },
      h('div', { class: 'fu-head' }, h('h3', { text: M.uploadsHead(items) }),
        waiting ? h('button', { 'data-key': 'up:rest', focus: true, on: { click: () => { uploads.stopRest('not sent: cancelled on this page'); drawUploads(); } } }, 'Cancel the rest') : null,
        done ? h('button', { 'data-key': 'up:clear', focus: true, on: { click: () => { uploads.clearDone(); drawUploads(); } } }, 'Clear finished') : null),
      h('ul', { class: 'fu-list' }, items.map((u) => h('li', { class: `fu ${u.state}` },
        h('span', { class: 'fu-name', text: u.as && u.as !== u.name ? `${u.name} → ${u.as}` : u.name }),
        h('span', { class: 'note fu-line', text: `${M.uploadLine(u)} · into ${where(u.dest)}` }),
        u.state === 'sending' ? [bar(u.size ? u.sent / u.size : 0),
          h('button', { class: 'fu-cancel', 'data-key': `up:cancel:${u.id}`, focus: true, on: { click: () => { const x = xhrs.get(u.id); if (x) x.abort(); } } }, 'Cancel')] : null)))));
  }

  // ---- the frame ----

  function draw() {
    if (!root || !started) return;
    drawHead();
    drawJob();
    drawNav();
    drawPanel();
    drawUploads();
    // the body first: it prunes the selection to what the folder shows, which the selection's bar then counts
    drawBody();
    drawBulk();
    drawPane();
  }

  function drawHead() {
    const d = last;
    const why = refusals.get(M.DESC);
    fill(els.info, h('div', { class: 'thead-line' }, h('h1', { text: 'Files' }), d && d.title && d.title !== 'Files' ? h('span', { class: 'tsum', text: d.title }) : null),
      !d ? h('p', { class: 'note', text: why ? `Files could not be read: ${why}` : 'Reading Files from the PC…' }) : null,
      d && d.availability && d.availability !== 'live' ? h('p', { class: 'health', text: d.reason || d.availability }) : null);
    const rows = ((d && d.settings) || []).filter((r) => r.scope !== 'device');
    put(els.settings, JSON.stringify([rows, rows.map((r) => rec.value(M.W, r.id)), ctx.S.level, ctx.canAct('full')]), () => (rows.length ? settings(rows) : null));
  }

  function settings(rows) {
    const box = h('details', { class: 'tset', open: setOpen, on: { toggle: () => { setOpen = box.open; } } },
      h('summary', { 'data-key': 'set', focus: true }, 'Settings'),
      rows.map((row) => {
        const v = rec.value(M.W, row.id) ?? row.value;
        const why = ctx.canAct(row.level || 'full');
        const opts = (row.options || []).map((o) => (o && typeof o === 'object' ? [o.id, o.label ?? String(o.id)] : [o, String(o)]));
        return h('label', { class: 'trow' }, h('span', { text: row.label }),
          h('select', { 'data-key': `set:${row.id}`, focus: true, class: why ? 'dim' : null, 'data-why': why, on: { change: (e) => {
            const o = opts.find(([id]) => String(id) === e.target.value);
            // a row's own action if the description names one, else `set` (Torrents' row op; spec §4.5: Size, a record row)
            const action = row.action || 'set';
            const value = o ? o[0] : e.target.value;
            setReg(action, row.id, value, { label: row.label, level: row.level || 'full' });
            els.settings.dataset.sig = '';
          } } }, opts.map(([id, l]) => h('option', { value: id, selected: String(id) === String(v) }, l))));
      }));
    return box;
  }

  function drawJob() {
    clearTimeout(ticker);
    if (!root || !started) return;
    // L1 P44: the slot's job whatever its state — running with its age, or how and when it ended (until the next)
    const j = M.jobsOf(last)[0] || null;
    const running = M.runningJob(last);
    const now = Date.now();
    fill(els.job, j ? h('div', { class: 'fjob', role: 'status' }, h('span', { text: M.jobLine(j, pcNow()) }), j === running && typeof j.fraction === 'number' ? bar(j.fraction) : null) : null,
      [...flying.values()].map((w) => h('p', { class: 'note', text: M.waitingLine(w, now) })));
    if (running || flying.size) ticker = setTimeout(drawJob, 1000);
  }

  function drawNav() {
    if (place.at === 'locations') return fill(els.nav);
    const clip = clipNow();
    const sig = JSON.stringify([place.at, place.dir, clip, sortNow(), hiddenNow(), ctx.S.level, ctx.canAct('full'), M.busyWhy(last), mode, locs().map((l) => [l.path, l.label]), [...acts().keys()]]);
    put(els.nav, sig, () => {
      if (place.at === 'trash') return h('nav', { class: 'crumbs' }, link('Locations', null, 'crumb:locs'), ' / Trash');
      const cr = M.crumbs(place.dir, locs());
      const crumbs = h('nav', { class: 'crumbs', 'aria-label': 'Path' }, link('Locations', null, 'crumb:locs'),
        cr.map((c, i) => [' / ', i === cr.length - 1 && place.at === 'folder' ? h('span', { class: 'here', text: c.label }) : link(c.label, M.folderTarget(c.path), `crumb:${c.path}`)]));
      const input = h('input', { type: 'file', multiple: true, hidden: true, 'data-key': 'up:input', on: { change: (e) => { startUpload(e.target.files, place.dir); e.target.value = ''; } } });
      const sortA = acts().get('sort');
      const hidA = acts().get('hidden');
      const reach = ctx.canAct('full');
      return [crumbs, h('div', { class: 'ftools', role: 'toolbar', 'aria-label': 'This folder' },
        btn('upload', { label: 'Upload files…', onClick: () => input.click() }), input,
        btn('mkdir', { onClick: () => mkdir(place.dir) }),
        clip ? btn('paste', { label: `Paste here: '${M.baseName(clip.path)}'`, onClick: () => paste(place.dir) }) : null,
        M.offered(sortA, ctx.S.level) ? h('label', { class: 'fsort' }, h('span', { class: 'note', text: 'Sort' }),
          h('select', { 'data-key': 'sort', focus: true, class: reach ? 'dim' : null, 'data-why': reach, on: { change: (e) => setReg('sort', 'sort', e.target.value) } },
            M.SORTS.map(([id, l]) => h('option', { value: id, selected: id === sortNow() }, l)))) : null,
        M.offered(hidA, ctx.S.level) ? h('label', { class: 'fhid' }, h('input', { type: 'checkbox', checked: hiddenNow(), 'data-key': 'hidden', focus: true,
          class: reach ? 'dim' : null, 'data-why': reach, on: { change: (e) => setReg('hidden', 'hidden', !!e.target.checked) } }), h('span', { text: 'Hidden files' })) : null,
        btn('stats', { label: 'Stats of this folder', onClick: () => statsOf(place.dir, M.baseName(place.dir) || '/') }),
        h('button', { 'data-key': 'refresh', focus: true, on: { click: refresh } }, 'Refresh'),
        h('button', { 'data-key': 'mode', focus: true, 'aria-pressed': mode === 'grid' ? 'true' : 'false', on: { click: () => {
          mode = mode === 'grid' ? 'list' : 'grid';
          store.set(M.KEYS.view, mode);
          els.nav.dataset.sig = '';
          draw();
        } } }, mode === 'grid' ? 'List' : 'Grid')),
      clip ? h('p', { class: 'note fclip', text: `Clipboard: ${clip.verb} '${M.baseName(clip.path)}' from ${where(M.parent(clip.path))}` }) : null];
    });
  }

  function refresh() {
    if (!listing) return;
    const dir = listing.src;
    const want = listing.want;
    setListing(null);
    setListing(dir);
    listing.want = want;
    checkMore();
    draw();
  }

  function drawPanel() {
    if (!panel) return fill(els.panel);
    const close = h('button', { 'data-key': 'panel:close', focus: true, on: { click: () => { panel = null; drawPanel(); } } }, 'Close');
    if (panel.kind === 'stats') {
      return fill(els.panel, h('section', { class: 'fpanel' }, h('div', { class: 'fp-head' }, h('h3', { text: `Stats of '${panel.name}'` }), close),
        h('dl', { class: 'facts' }, panel.facts.map(([k, v]) => [h('dt', { text: k }), h('dd', { text: v })]))));
    }
    const files = panel.rows.filter((r) => !r.dir);
    const dirs = panel.rows.length - files.length;
    return fill(els.panel, h('section', { class: 'fpanel' }, h('div', { class: 'fp-head' }, h('h3', { text: 'Download: one link per file' }), close),
      files.length ? h('ul', { class: 'plain' }, files.map((r) => h('li', null,
        h('a', { href: M.blobUrl(M.join(panel.dir, r.name), { dl: true }), download: r.name, 'data-key': `dl:${r.name}`, focus: true }, r.name), ' ',
        h('span', { class: 'note', text: M.bytes(r.size) })))) : null,
      dirs ? h('p', { class: 'note', text: `${dirs === 1 ? '1 folder is' : `${dirs} folders are`} left out: a folder is not downloaded (Files makes no archive).` }) : null));
  }

  function drawBulk() {
    if (!sel.size) return fill(els.bulk);
    const ids = sel.list();
    const clear = h('button', { 'data-key': 'sel:clear', focus: true, on: { click: () => { sel.clear(); draw(); } } }, 'Clear');
    let acts_;
    if (place.at === 'trash') {
      const names = ids.map((id) => (trashRows().find((r) => r.id === id) || { name: id }).name);
      acts_ = [btn('restore', { onClick: () => act('restore', ids, names) }), btn('purge', { label: 'Delete forever', onClick: () => act('purge', ids, names) })];
    } else {
      const rows = ids.map((n) => listing.find(n)).filter(Boolean);
      acts_ = [btn('trash', { label: 'Move to Trash', onClick: () => act('trash', ids.map((n) => M.join(place.dir, n)), ids) }),
        M.offered(acts().get('download'), ctx.S.level) ? h('button', { 'data-key': 'sel:dl', focus: true, on: { click: () => { panel = { kind: 'download', dir: place.dir, rows }; drawPanel(); } } }, 'Download') : null];
    }
    keepFocus(els.bulk, 'data-key', () => fill(els.bulk, h('div', { class: 'tbulk', role: 'toolbar', 'aria-label': 'Selected' },
      h('span', { class: 'tb-n', text: `${sel.size} selected` }), acts_, clear)));
  }

  function drawBody() {
    if (place.at === 'locations') return keepFocus(els.list, 'data-key', () => fill(els.list, locations()));
    if (place.at === 'trash') return keepFocus(els.list, 'data-key', () => fill(els.list, trash()));
    keepFocus(els.list, 'data-key', () => fill(els.list, folder()));
  }

  function locations() {
    if (!last) return null;
    // S1-2 R2 item 18: a location of kind `trash` is the Trash card below (the trash view), never a folder card
    const ls = locs().filter((l) => !M.isTrashLoc(l));
    return [h('ul', { class: 'flocs' }, ls.map((l) => {
      const c = M.capacity(l);
      return h('li', null, h('a', { class: 'floc', href: format({ window: M.W, target: M.folderTarget(l.path) }), 'data-key': `loc:${l.path}`, focus: true,
        on: { click: (e) => { e.preventDefault(); go(M.folderTarget(l.path)); } } },
        h('span', { class: 'fl-head' }, icon('folder'), h('span', { class: 'fl-label', text: l.label || l.path })),
        h('span', { class: 'note fl-path', text: l.path }), c ? [bar(c.used), h('span', { class: 'note', text: c.words })] : null));
    }), h('li', null, h('a', { class: 'floc', href: format({ window: M.W, target: 'trash' }), 'data-key': 'loc:trash', focus: true,
      on: { click: (e) => { e.preventDefault(); go('trash'); } } }, h('span', { class: 'fl-head' }, icon('trash'), h('span', { class: 'fl-label', text: 'Trash' })),
      h('span', { class: 'note fl-path', text: 'what was moved to the trash, with Restore and Delete forever' })))),
    ls.length ? null : h('p', { class: 'note', text: 'The PC names no locations.' })];
  }

  function trashRows() {
    const d = ctx.desc(M.TRASH);
    const rows = d ? (Array.isArray(d.rows) ? d.rows : Array.isArray(d.entries) ? d.entries : []) : [];
    return rows.map((r) => ({ id: String(r.id), name: String(r.name ?? r.id), origPath: String(r.origPath || ''), dir: !!r.dir, size: r.size, at: r.at ?? r.atMs }))
      .sort((a, b) => (b.at || 0) - (a.at || 0));
  }

  function trash() {
    const d = ctx.desc(M.TRASH);
    const why = refusals.get(M.TRASH);
    if (!d) return h('p', { class: 'note', text: why ? `The trash could not be read: ${why}` : 'Reading the trash from the PC…' });
    const rows = trashRows();
    sel.prune(rows.map((r) => r.id));
    if (!rows.length) return h('p', { class: 'note', text: 'The trash is empty.' });
    const now = pcNow();
    return [h('p', { class: 'note', text: `${rows.length} ${rows.length === 1 ? 'item' : 'items'} in the trash, newest first.` }),
      h('ul', { class: 'flist ftrash' }, rows.map((r) => h('li', { class: 'frow' + (sel.has(r.id) ? ' picked' : '') },
        h('input', { type: 'checkbox', checked: sel.has(r.id), 'aria-label': `select ${r.name}`, 'data-key': `sel:${r.id}`, focus: true, on: { change: () => { sel.toggle(r.id); drawBody(); drawBulk(); } } }),
        h('span', { class: 'fopen static' }, icon(r.dir ? 'folder' : 'file'), h('span', { class: 'fname', text: r.name })),
        h('span', { class: 'fmeta', text: [`from ${r.origPath ? where(M.parent(r.origPath)) : '?'}`, r.dir ? 'folder' : M.bytes(r.size), r.at ? `trashed ${M.ago(r.at, now)}` : null].filter(Boolean).join(' · ') }),
        h('span', { class: 'frow-acts' }, btn('restore', { onClick: () => act('restore', [r.id], [r.name]) }),
          btn('purge', { label: 'Delete forever', onClick: () => act('purge', [r.id], [r.name]) })))))];
  }

  function folder() {
    if (!listing) return null;
    const rows = M.present(listing.rows(), sortNow(), hiddenNow());
    // W-20: a selection is what the folder shows — a name gone or hidden away leaves it
    sel.prune(rows.map((r) => r.name));
    const st = listing.status();
    const n = typeof listCount === 'number' ? listCount : rows.length;
    const end = st.kind === 'first' ? 'Reading the folder from the PC…' : st.kind === 'loading' ? 'loading the next page…'
      : st.kind === 'failed' ? `Page ${st.page + 1} of this folder could not be read: ${st.reason}${st.retry ? ' — asked again every 5 s.' : ''}`
        : st.kind === 'empty' || (st.kind === 'end' && !rows.length) ? `This folder is empty${hiddenNow() ? '' : ' (entries whose names start with a dot are not shown: Hidden files is off)'}.`
          : st.kind === 'end' ? `${n} ${n === 1 ? 'entry' : 'entries'}` : null;
    const endEl = end ? h('p', { class: 'note tend' + (st.kind === 'failed' ? ' failed' : ''), text: end }) : null;
    const retry = st.kind === 'failed' && !st.retry ? h('button', { 'data-key': 'retry', focus: true, on: { click: () => { listing.failed = null; checkMore(true); } } }, 'Try again') : null;
    const all = rows.length > 0 && rows.every((r) => sel.has(r.name));
    const head = rows.length ? h('label', { class: 'fall' }, h('input', { type: 'checkbox', checked: all, 'data-key': 'sel:all', focus: true,
      on: { change: () => { if (all) sel.clear(); else sel.set(rows.map((r) => r.name)); drawBody(); drawBulk(); } } }), h('span', { class: 'note', text: 'Select all shown' })) : null;
    const now = pcNow();
    return [head, rows.length ? h('ul', { class: mode === 'grid' ? 'fgrid' : 'flist' }, rows.map((r) => entry(r, now))) : null, endEl, retry];
  }

  function entry(r, now) {
    const path = M.join(place.dir, r.name);
    const kind = M.kindOf(r);
    const open = () => (r.dir ? go(M.folderTarget(path)) : go(M.entryTarget(place.dir, r.name)));
    const picked = sel.has(r.name);
    const cur = place.at === 'entry' && place.name === r.name;
    const pic = mode === 'grid' && r.thumb && !r.dir ? h('img', { class: 'fthumb', src: M.thumbUrl(path), alt: '', loading: 'lazy' })
      : icon(r.dir ? 'folder' : kind === 'image' ? 'image' : 'file');
    return h('li', { class: `frow${picked ? ' picked' : ''}${cur ? ' open' : ''}${r.stale ? ' stale' : ''}${r.dir ? ' dir' : ''}`, 'data-name': r.name },
      h('input', { type: 'checkbox', checked: picked, 'aria-label': `select ${r.name}`, 'data-key': `sel:${r.name}`, focus: true,
        on: { change: () => { sel.toggle(r.name); drawBody(); drawBulk(); } } }),
      h('button', { class: 'fopen', 'data-key': `row:${r.name}`, focus: true, title: r.name, on: { click: open } }, pic, h('span', { class: 'fname', text: r.name })),
      h('span', { class: 'fmeta', text: [r.dir ? 'folder' : M.bytes(r.size), M.mtimeWords(r.mtime, now)].filter(Boolean).join(' · ') }),
      h('button', { class: 'fmore', 'aria-label': `actions for ${r.name}`, title: `actions for ${r.name}`, 'data-key': `more:${r.name}`, focus: true,
        on: { click: () => go(M.entryTarget(place.dir, r.name)) } }, '⋯'));
  }

  // ---- an entry's pane: its facts, its actions, its preview ----

  function current() {
    if (place.at !== 'entry' || !listing) return null;
    const r = listing.find(place.name);
    if (!r) return null;
    const path = M.join(place.dir, r.name);
    return { r, path, kind: forceText === path ? 'text' : M.kindOf(r) };
  }

  function drawPane() {
    const open = place.at === 'entry';
    els.split.classList.toggle('has-pane', open);
    root.classList.toggle('pane-open', open);
    els.pane.hidden = !open;
    if (!open) {
      els.paneView.dataset.sig = '';
      return fill(els.paneHead), fill(els.paneActs), fill(els.paneView);
    }
    const c = current();
    const close = h('button', { class: 'fp-close', 'data-key': 'p:close', focus: true, on: { click: () => go(M.folderTarget(place.dir), place.name) } }, narrow() ? '← Back' : 'Close');
    keepFocus(els.paneHead, 'data-key', () => fill(els.paneHead, h('div', { class: 'fp-head' }, h('h2', { text: place.name }), close),
      c ? h('p', { class: 'note', text: [c.r.dir ? 'folder' : M.bytes(c.r.size), M.mtimeWords(c.r.mtime, pcNow()) ? `modified ${M.mtimeWords(c.r.mtime, pcNow())}` : null, where(place.dir)].filter(Boolean).join(' · ') }) : null));
    if (!c) {
      fill(els.paneActs);
      els.paneView.dataset.sig = '';
      const ended = listing && listing.status().kind !== 'first' && listing.ended;
      return fill(els.paneView, h('p', { class: 'note', text: ended ? `'${place.name}' is not in ${where(place.dir)} now.` : `Looking for '${place.name}' in ${where(place.dir)}…` }));
    }
    keepFocus(els.paneActs, 'data-key', () => fill(els.paneActs, h('div', { class: 'fp-acts', role: 'toolbar', 'aria-label': `Actions for ${place.name}` }, paneActs(c))));
    const t = textDoc;
    const pdf = pdfScope ? ctx.desc(pdfScope) : null;
    put(els.paneView, JSON.stringify([c.path, c.kind, c.r.reader || null, c.kind === 'text' && t ? [t.chunks.size, t.status()] : null,
      c.kind === 'pdf' ? [pdf, refusals.get(pdfScope) || null] : null]), () => preview(c));
  }

  function paneActs({ r, path }) {
    const clip = clipNow();
    return [
      r.dir ? h('button', { class: 'primary', 'data-key': 'p:open', focus: true, on: { click: () => go(M.folderTarget(path)) } }, 'Open') : null,
      r.reader && M.offered(acts().get('openInReader'), ctx.S.level) ? h('button', { class: 'primary', 'data-key': 'p:reader', focus: true,
        on: { click: () => ctx.go({ window: 'reader', target: `book:${r.reader}` }) } }, 'Open in Reader') : null,
      r.dir ? null : dlLink(path, r.name, 'p:dl'),
      r.dir && clip ? btn('paste', { label: `Paste here: '${M.baseName(clip.path)}'`, onClick: () => paste(path) }) : null,
      btn('openOnPc', { onClick: () => send(acts().get('openOnPc'), [path], null, 1) }),
      btn('copy', { onClick: () => setClip('copy', path) }), btn('cut', { onClick: () => setClip('cut', path) }),
      btn('rename', { onClick: () => rename(path, r.name) }), btn('stats', { onClick: () => statsOf(path, r.name) }),
      btn('trash', { label: 'Move to Trash', onClick: () => act('trash', [path], [r.name]) })];
  }

  function preview({ r, path, kind }) {
    switch (kind) {
      case 'dir': return h('p', { class: 'note', text: 'A folder. Open shows what it holds.' });
      case 'image': return h('img', { class: 'fimg', src: M.blobUrl(path), alt: r.name });
      case 'epub': return h('p', { class: 'note', text: r.reader ? 'An EPUB in the Reader library: Open in Reader shows it with its place.'
        : 'This EPUB is not in the Reader library (only books under the books folder are). Download takes it; Open on PC opens it there.' });
      case 'pdf': {
        const d = ctx.desc(pdfScope);
        const why = refusals.get(pdfScope);
        if (!d) return h('p', { class: 'note', text: why ? `This PDF could not be read: ${why}` : 'Reading the PDF on the PC…' });
        const pages = Math.max(0, Number(d.pages) || 0);
        const width = M.pdfWidth(els.pane.clientWidth || 800, window.devicePixelRatio || 1);
        return [h('p', { class: 'note', text: `${pages} ${pages === 1 ? 'page' : 'pages'}; each is drawn on the PC as a picture. Download gives the PDF itself.` }),
          h('div', { class: 'fpdf' }, Array.from({ length: pages }, (_, i) => h('img', { class: 'fpage', loading: 'lazy', src: M.pdfPageUrl(path, i + 1, width), alt: `page ${i + 1} of ${pages}` })))];
      }
      case 'text': {
        const t = textDoc;
        if (!t) return null;
        const st = t.status();
        const end = st.kind === 'first' ? 'Reading the text on the PC…' : st.kind === 'loading' ? 'reading more…'
          : st.kind === 'failed' ? `The text could not be read: ${st.reason}${st.retry ? ' — asked again every 5 s.' : ''}`
            : st.kind === 'stuck' ? `The PC read no bytes at ${M.bytes(st.at)}; the text stops here.`
              : st.kind === 'end' ? (st.total !== null ? `The end (${M.bytes(st.total)}).` : 'The end.')
                : `${M.bytes(st.read)}${st.total !== null ? ` of ${M.bytes(st.total)}` : ''} shown; more as you scroll.`;
        return [st.kind === 'first' ? null : h('pre', { class: 'ftext', text: t.text() }), h('p', { class: 'note tend' + (st.kind === 'failed' ? ' failed' : ''), text: end })];
      }
      default:
        return [h('p', { class: 'note', text: 'No preview for this kind of file here. Download takes it.' }),
          h('button', { 'data-key': 'p:astext', focus: true, on: { click: () => { forceText = path; syncPreview(); drawPane(); checkMore(); } } }, 'Show it as text')];
    }
  }

  // ---- scopes as the place changes ----

  function hold(scope, want) {
    if (scope === want) return want;
    if (scope) ctx.unsub(scope);
    if (want) {
      refusals.delete(want);
      ctx.sub(want);
    }
    return want;
  }

  function setListing(dir) {
    if (listing && dir !== null && listing.src === M.norm(dir)) return;
    if (listing) for (const s of listing.held()) ctx.unsub(s);
    listing = dir === null ? null : new M.FolderListing(dir);
    listCount = null;
  }

  function dropText() {
    if (textDoc) for (const s of textDoc.held()) ctx.unsub(s);
    textDoc = null;
  }

  /** The preview's scopes follow the entry the pane shows: text chunks for text, the PDF's facts for a PDF. */
  function syncPreview() {
    const c = started ? current() : null;
    if (c && c.kind === 'text') {
      if (!textDoc || textDoc.path !== c.path) {
        dropText();
        textDoc = new M.TextDoc(c.path);
      }
    } else dropText();
    pdfScope = hold(pdfScope, c && c.kind === 'pdf' ? M.pdfScope(c.path) : null);
  }

  /** Asks the next page of the folder (at once for the first, near the end for the next, until an entry the address
   *  names is held) and the next chunk of a text near its end; a failed one again on its pacing. Never while hidden. */
  function checkMore(force) {
    if (!started || document.visibilityState === 'hidden') return;
    const m = document.getElementById('main');
    if (listing) {
      // at a phone's width an entry's pane takes the screen: its scroll is the pane's, never the list's end (the
      // named entry still draws the pages until it is held — seen in headless Firefox: the pane waited forever)
      const paneUp = place.at === 'entry' && narrow();
      const near = !!force || (!paneUp && m ? M.nearEnd(m.scrollTop, m.clientHeight, m.scrollHeight, ROW_PX) : false);
      const n = listing.next(near, Date.now());
      if (n !== null) {
        listing.ask(n);
        refusals.delete(listing.scope(n));
        ctx.sub(listing.scope(n));
        if (root) drawBody();
      }
    }
    if (textDoc) {
      const box = narrow() ? m : els && els.pane;
      const near = !!box && box.scrollHeight - (box.scrollTop + box.clientHeight) <= TEXT_NEAR_PX;
      const at = textDoc.next(near, Date.now());
      if (at !== null) {
        textDoc.ask(at);
        ctx.sub(textDoc.scope(at));
        if (root) drawPane();
      }
    }
  }

  function show(r) {
    lastR = r;
    const before = place;
    place = M.placeOf(r.target, r.anchor);
    if (place.bad !== undefined) say(`'${place.bad}' is not a path on the PC (a path starts with /); showing the locations.`);
    if (!started) return;
    if ((before.at === 'trash') !== (place.at === 'trash') || before.dir !== place.dir) sel.clear();
    if (place.at !== 'entry' || place.dir !== before.dir || place.name !== before.name) forceText = null;
    if (place.dir !== before.dir || place.at !== before.at) panel = null;
    trashScope = hold(trashScope, place.at === 'trash' ? M.TRASH : null);
    setListing(place.at === 'folder' || place.at === 'entry' ? place.dir : null);
    if (listing) listing.want = place.at === 'entry' ? place.name : null;
    reveal = place.at === 'entry' && (before.name !== place.name || before.dir !== place.dir) ? place.name : null;
    syncPreview();
    draw();
    checkMore();
    revealRow();
    if (place.at === 'folder' && place.focus) {
      // back from an entry's pane: its row is where the eye left it
      const b = [...els.list.querySelectorAll('[data-key]')].find((e) => e.getAttribute('data-key') === `row:${place.focus}`);
      if (b) {
        b.focus();
        b.scrollIntoView({ block: 'nearest' });
      }
    }
  }

  /** The open entry's row, scrolled into the list's view on a desk (the pane beside it names a row the eye can find). */
  function revealRow() {
    if (reveal === null || narrow() || !root) return;
    const b = [...els.list.querySelectorAll('[data-key]')].find((e) => e.getAttribute('data-key') === `row:${reveal}`);
    if (!b) return;
    reveal = null;
    b.scrollIntoView({ block: 'center' });
  }

  function retryLater() {
    clearTimeout(retryTimer);
    retryTimer = setTimeout(() => checkMore(), M.RETRY_MS);
  }

  const onVisibility = () => checkMore();
  const onScroll = () => checkMore();
  const onWidth = () => draw();
  const onDragOver = (e) => {
    if (place.at !== 'folder' && place.at !== 'entry') return;
    const types = e.dataTransfer ? [...(e.dataTransfer.types || [])] : [];
    if (!types.includes('Files')) return;
    e.preventDefault();
    root.classList.add('dropping');
  };
  const onDragLeave = (e) => {
    if (!e.relatedTarget || !root.contains(e.relatedTarget)) root.classList.remove('dropping');
  };
  const onDrop = (e) => {
    root.classList.remove('dropping');
    if (place.at !== 'folder' && place.at !== 'entry') return;
    e.preventDefault();
    // S1-2 R2 item 20: folders are left out of a drop, and said
    const { files, folders } = M.dropped(e.dataTransfer);
    if (folders.length) say(M.foldersLeftOut(folders), { keep: true });
    startUpload(files, place.dir);
  };

  return {
    mount(r) {
      root = h('section', { class: 'page files' });
      els = { info: h('div'), settings: h('div'), job: h('div', { class: 'fjobs' }), nav: h('div', { class: 'fnav' }), panel: h('div'), uploads: h('div'),
        bulk: h('div', { class: 'tbulk-box fbulk' }), list: h('div', { class: 'fmain' }), paneHead: h('div'), paneActs: h('div', { class: 'fp-actsbox' }), paneView: h('div', { class: 'fp-view' }) };
      els.pane = h('aside', { class: 'fpane', 'aria-label': 'The entry', hidden: true }, els.paneHead, els.paneActs, els.paneView);
      els.split = h('div', { class: 'fsplit' }, els.list, els.pane);
      root.addEventListener('dragover', onDragOver);
      root.addEventListener('dragleave', onDragLeave);
      root.addEventListener('drop', onDrop);
      document.addEventListener('visibilitychange', onVisibility);
      const m = document.getElementById('main');
      if (m) m.addEventListener('scroll', onScroll, { passive: true });
      els.pane.addEventListener('scroll', onScroll, { passive: true });
      if (mq && mq.addEventListener) mq.addEventListener('change', onWidth);
      up.hooks = { draw: drawUploads, pump };
      if (M.belowWhy(ctx.S.level)) {
        drawBelow();
        place = M.placeOf(r.target, r.anchor);
        lastR = r;
        return root;
      }
      root.append(h('header', { class: 'thead' }, els.info, els.settings), els.job, els.nav, els.panel, els.uploads, els.bulk, els.split);
      start();
      last = ctx.desc(M.DESC) || null;
      show(r);
      return root;
    },
    update(r) {
      show(r);
      return true;
    },
    unmount() {
      // the files still waiting are not sent (a page keeps no queue); the one being sent goes on and says its end
      stopAll('the Files page was left');
      up.hooks = null;
      stop();
      document.removeEventListener('visibilitychange', onVisibility);
      const m = document.getElementById('main');
      if (m) m.removeEventListener('scroll', onScroll);
      if (mq && mq.removeEventListener) mq.removeEventListener('change', onWidth);
      clearTimeout(ticker);
      clearTimeout(retryTimer);
      root = null;
    },
    back() {
      if (!started) return false;
      if (panel) { panel = null; drawPanel(); return true; }
      if (place.at === 'entry') go(M.folderTarget(place.dir), place.name);
      else if (place.at === 'folder') {
        const up = M.parent(place.dir);
        const top = locs().some((l) => M.norm(l.path) === place.dir);
        if (top || place.dir === '/') go(null);
        else go(M.folderTarget(up), M.baseName(place.dir));
      } else if (place.at === 'trash') go(null);
      else return false;
      return true;
    },
    onDesc(scope, d) {
      if (!started) return;
      if (scope === M.DESC) {
        last = d;
        refusals.delete(scope);
        checkJobs();
        drawHead();
        drawJob();
        drawNav();
        if (place.at === 'locations') drawBody();
        drawBulk();
        if (place.at === 'entry') drawPane();
      } else if (listing && listing.pageOf(scope) !== null) {
        listing.got(listing.pageOf(scope), d);
        if (typeof d.count === 'number') listCount = d.count;
        refusals.delete(scope);
        syncPreview();
        drawBody();
        drawBulk();
        drawPane();
        revealRow();
        checkMore();
      } else if (textDoc && textDoc.atOf(scope) !== null) {
        textDoc.got(textDoc.atOf(scope), d);
        drawPane();
        checkMore();
      } else if (scope === M.TRASH) {
        refusals.delete(scope);
        drawBody();
        drawBulk();
      } else if (scope === pdfScope) {
        refusals.delete(scope);
        drawPane();
      }
    },
    onRefused(scope, a) {
      if (!started) return false;
      if (listing && listing.pageOf(scope) !== null) {
        listing.refused(listing.pageOf(scope), a, Date.now());
        // spec §5 V2: `retry` is asked again on the 5 s pacing, never at once; the rows held stay, greyed
        if (a.code === 'retry') retryLater();
        drawBody();
        drawPane();
        return true;
      }
      if (textDoc && textDoc.atOf(scope) !== null) {
        textDoc.refused(textDoc.atOf(scope), a, Date.now());
        if (a.code === 'retry') retryLater();
        drawPane();
        return true;
      }
      if (![M.DESC, M.TRASH, pdfScope].includes(scope)) return false;
      refusals.set(scope, (a && a.reason) || `refused (${a && a.code})`);
      draw();
      return true;
    },
    onWindowRecord(w, changed) {
      if (w !== M.W || !started) return;
      for (const k of changed || []) delete pend[k.slice(`rec.${M.W}.`.length)];
      draw();
    },
    onWelcome() {
      // a level changed by a re-approval: below full everything held is let go and nothing of the tree stays shown
      if (!root) return;
      if (M.belowWhy(ctx.S.level)) {
        if (started) stop();
        return drawBelow();
      }
      if (!started) {
        fill(root, h('header', { class: 'thead' }, els.info, els.settings), els.job, els.nav, els.panel, els.uploads, els.bulk, els.split);
        start();
        place = { at: 'locations' };
        return show(lastR || { target: null });
      }
      draw();
    },
    onReach: () => draw(),
  };
}
