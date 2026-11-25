// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-2, package Q2 (S1-2 R0 §1, the real-server look): test code only. A front on a loopback port the OS picks,
// between headless Firefox and the REAL web listener that `LookServer.kt` starts in process (loopback only, a temp
// directory, made-up data). Every request and the WebSocket go through to that listener unchanged but for three
// things a front on another port needs: `Host` and `Origin` name the listener's own port (its access check wants
// them), the listener's content security policy names the front's port for the socket, and the one made-up device's
// cookie is sent unless the scene is a page that has none (`anon`). Like the stand-in it adds a held image and the
// module that walks the scene's steps (Firefox's --screenshot waits for the load event), and `/__wait?ms=` holds a
// step for the PC's own pacing (a place is saved 2 s after the input). L1: `/__other?book=<id>&offset=<n>` writes a
// Reader place as ANOTHER device — the made-up device at control (LOOK_SECRET_CONTROL) on a socket of its own, as his
// glasses' input reaches the PC — so a scene sees the page follow it.

import { createServer, request } from 'node:http';
import { connect } from 'node:net';
import { readFileSync, appendFileSync, existsSync } from 'node:fs';
import { lookModule } from './standin.mjs';
import { format } from '../../main/resources/web/js/address.mjs';
import { PROTO } from '../../main/resources/web/js/proto.mjs';

const TYPES = { mjs: 'text/javascript; charset=utf-8' };

/** Starts the front for `scene` ({path, steps, anon?}) before the listener at `real` (`http://127.0.0.1:PORT`), with
 *  the device `secret`. Resolves to {port, log, close}. */
export function front(scene, real, secret) {
  const up = new URL(real);
  const log = [];
  let ready = false;
  let held = null;
  const socks = new Set();
  // S1-2 Q5: a scene of the made-up device at control (LOOK_SECRET_CONTROL, its secret file) instead of the full one
  const sec = scene.control ? readFileSync(process.env.LOOK_SECRET_CONTROL, 'utf8').trim() : secret;
  const cookie = (h) => {
    if (scene.own) return String(h || ''); // L1 A1: this PC's own browser keeps the cookie A1 gave it
    const others = String(h || '').split(';').map((c) => c.trim()).filter((c) => c && !c.startsWith('damage='));
    return [...others, ...(scene.anon ? [] : [`damage=${sec}`])].join('; ');
  };
  const headsUp = (headers) => {
    const hh = scene.asName ? `${scene.asName}:${up.port}` : up.host; // opened under the machine's name (LOOK_NAME)
    const out = { ...headers, host: hh };
    if (out.origin) out.origin = `http://${hh}`;
    const c = cookie(headers.cookie);
    if (c) out.cookie = c;
    else delete out.cookie;
    return out;
  };

  const server = createServer((req, res) => {
    const url = new URL(req.url, 'http://x');
    const own = (code, type, body) => { res.writeHead(code, { 'Content-Type': type, 'Cache-Control': 'no-cache' }); res.end(body); };
    if (url.pathname === '/__hold') return ready ? own(200, 'image/gif', GIF) : (held = () => own(200, 'image/gif', GIF));
    if (url.pathname === '/__ready') { ready = true; if (held) held(); return own(204, 'text/plain', ''); }
    if (url.pathname === '/__note') { log.push({ t: 'note', text: decodeURIComponent(req.url.slice(req.url.indexOf('?') + 1)) }); return own(204, 'text/plain', ''); }
    // L1 G1: the policy's refusals, each one counted (LOOK_CSP_LOG) and said on the picture's line
    if (url.pathname === '/__csp') {
      const parts = [];
      req.on('data', (d) => parts.push(d));
      req.on('end', () => {
        const t = String(Buffer.concat(parts));
        if (process.env.LOOK_CSP_LOG) appendFileSync(process.env.LOOK_CSP_LOG, `${scene.path} ${t}\n`);
        log.push({ t: 'note', text: `CSP error report: ${t}` });
        own(204, 'text/plain', '');
      });
      return;
    }
    // L1 G1: a deploy — the listener's JVM is ended and started again on the same port (look-loop.sh, LOOK_RESTART_DIR)
    if (url.pathname === '/__restart') {
      const dir = process.env.LOOK_RESTART_DIR;
      const lines = (f) => (existsSync(f) ? String(readFileSync(f)).split('\n').filter(Boolean).length : 0);
      const n = lines(`${dir}/ack`);
      appendFileSync(`${dir}/trig`, `restart ${Date.now()}\n`);
      log.push({ t: 'note', text: `restart asked at ack ${n}` });
      const poll = () => (lines(`${dir}/ack`) > n ? own(200, 'text/plain', 'up again') : setTimeout(poll, 200));
      return poll();
    }
    if (url.pathname === '/__wait') return setTimeout(() => own(204, 'text/plain', ''), Number(url.searchParams.get('ms') || 0));
    if (url.pathname === '/__look.mjs') return own(200, TYPES.mjs, lookModule(scene.steps));
    if (url.pathname === '/__other') {
      return otherPlace(up, url.searchParams.get('book'), Number(url.searchParams.get('offset'))).then((t) => {
        log.push({ t: 'note', text: `another device's place: ${t}` });
        own(200, 'text/plain', t);
      }, (e) => own(502, 'text/plain', e.message));
    }
    // S1-2 Q3: a word for LookServer's control listener (LOOK_CTL, loopback only): raise, dismiss-other, state
    if (url.pathname === '/__ctl') {
      const ctl = ctlNow();
      if (!ctl || !/^http:\/\/127\.0\.0\.1:\d+$/.test(ctl)) return own(400, 'text/plain', 'LOOK_CTL is not a loopback address');
      const word = url.search.slice(1);
      return request(`${ctl}/?${word}`, (r) => {
        const parts = [];
        r.on('data', (d) => parts.push(d));
        r.on('end', () => { const t = String(Buffer.concat(parts)); log.push({ t: 'note', text: `ctl ${word}: ${t}` }); own(200, 'text/plain', t); });
      }).on('error', (e) => own(502, 'text/plain', e.message)).end();
    }
    log.push({ t: 'http', text: `${req.method} ${url.pathname}` });
    const fwd = request({ host: up.hostname, port: up.port, method: req.method, path: req.url, headers: headsUp(req.headers) }, (r) => {
      const h = { ...r.headers };
      if (h['content-security-policy']) h['content-security-policy'] = h['content-security-policy'].replace(/ws:\/\/[^ ;]+/g, `ws://${req.headers.host}`) + '; report-uri /__csp';
      const html = String(h['content-type'] || '').startsWith('text/html');
      if (!html) { res.writeHead(r.statusCode, h); r.pipe(res); return; }
      const parts = [];
      r.on('data', (d) => parts.push(d));
      r.on('end', () => {
        const body = Buffer.from(String(Buffer.concat(parts)).replace('</body>',
          '<img id="look-hold" hidden alt="" src="/__hold">\n<script type="module" src="/__look.mjs"></script>\n' +
          (scene.cspProbe ? '<script>window.inlineRan = 1;</script>\n' : '') + '</body>')); // the policy probe's inline script, refused
        h['content-length'] = String(body.length);
        res.writeHead(r.statusCode, h);
        res.end(body);
      });
    });
    fwd.on('error', (e) => { log.push({ t: 'note', text: `front: the listener did not answer ${url.pathname}: ${e.message}` }); own(502, 'text/plain', e.message); });
    // S1-2 Q5: an upload cut off part way — the body stops after 1 MB and both sockets end, as a dropped link ends them
    if (scene.put === 'cut' && req.method === 'PUT') {
      let got = 0;
      req.on('data', (d) => { got += d.length; if (got <= 1e6) fwd.write(d); else { log.push({ t: 'note', text: `front: the upload cut off after ${got} B` }); fwd.destroy(); req.socket.destroy(); } });
      return;
    }
    req.pipe(fwd);
  });

  // the socket: the upgrade goes through with the same three changes, then bytes both ways as they are
  server.on('upgrade', (req, sock, head) => {
    const h = headsUp(req.headers);
    const upSock = connect(Number(up.port), up.hostname, () => {
      upSock.write(`${req.method} ${req.url} HTTP/1.1\r\n${Object.entries(h).map(([k, v]) => `${k}: ${v}`).join('\r\n')}\r\n\r\n`);
      if (head && head.length) upSock.write(head);
      sock.pipe(upSock);
      upSock.pipe(sock);
    });
    log.push({ t: 'ws', text: 'the socket' });
    for (const s of [sock, upSock]) {
      socks.add(s);
      s.on('error', () => { sock.destroy(); upSock.destroy(); }); // one side ending ends the pair; the page reconnects
      s.on('close', () => { socks.delete(s); sock.destroy(); upSock.destroy(); });
    }
  });

  return new Promise((ok) => server.listen(0, '127.0.0.1', () => ok({
    port: server.address().port,
    log,
    close() {
      for (const s of socks) s.destroy();
      server.closeAllConnections();
      return new Promise((done) => server.close(done));
    },
  })));
}

/** The listener's control address: under look-loop.sh (LOOK_RESTART_DIR) the last `LOOK {` line's, since a restarted
 *  listener opens its control port anew (S1-3's merge: a word sent after a restart went to the ended JVM's port). */
function ctlNow() {
  const dir = process.env.LOOK_RESTART_DIR;
  const lines = dir && existsSync(`${dir}/server.log`) ? String(readFileSync(`${dir}/server.log`)).split('\n').filter((l) => l.startsWith('LOOK {')) : [];
  return lines.length ? JSON.parse(lines[lines.length - 1].slice(5)).ctl : process.env.LOOK_CTL;
}

/** L1: one place op on `place.<book>` at `offset` from the made-up device at control, on its own socket to the
 *  listener at `up`; resolves to the PC's answer as text. */
function otherPlace(up, book, offset) {
  const secret = readFileSync(process.env.LOOK_SECRET_CONTROL, 'utf8').trim();
  const ws = new WebSocket(`ws://${up.host}/ws`, { headers: { origin: `http://${up.host}`, cookie: `damage=${secret}` } });
  let device = null;
  return new Promise((ok, no) => {
    // the lane's protocol from the bundle (S1-3's merge: a literal 3 was ended `proto` once the protocol rose to 4)
    ws.onopen = () => ws.send(JSON.stringify({ t: 'app', proto: PROTO, stream: 'a1a2a3a4a5a6a7a8', contracts: { reader: 1 } }));
    ws.onerror = () => no(new Error('the other device\'s socket failed'));
    ws.onmessage = (e) => {
      const f = JSON.parse(e.data);
      if (f.t === 'welcome') {
        device = f.device;
        ws.send(JSON.stringify({ t: 'op', op: { device: '', stream: 'a1a2a3a4a5a6a7a8', n: 1, window: 'reader', action: 'place',
          targets: [`place.${book}`], result: `@c${offset}.1~`, at: f.pcClock } }));
      } else if (f.t === 'ans' && f.n === 1) {
        ws.close();
        ok(`${f.answer.a}${f.answer.reason ? `: ${f.answer.reason}` : ''} (device ${device})`);
      } else if (f.t === 'end') no(new Error(`ended: ${f.reason}`));
    };
  });
}

/** A 1×1 transparent GIF. */
const GIF = Buffer.from('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7', 'base64');

/** The scenes against the real listener (S1-2 R0 §1): the pages S1-1 built, and what they must be able to do. `book`
 *  is the made-up EPUB's id that LookServer prints. */
export function realScenes(book) {
  const reach = { wait: '#reach[hidden]' };
  return {
    ...filesRealScenes(reach),
    ...feedRealScenes(reach),
    ...tmuxRealScenes(reach),
    'real-waiting': { path: '/', anon: true, steps: [{ wait: '.pair .pcode' }] },
    'real-home': { path: '/', steps: [{ wait: '.cards .card' }, reach] },
    'real-shelf': { path: '/w/reader', steps: [{ wait: '.shelf .book' }, reach] },
    // the input that opens the book (open) and the chapter picked (its place, saved 2 s after the input)
    'real-book': { path: '/w/reader', steps: [{ wait: `.shelf .book[data-book="${book}"]` }, { click: `.shelf .book[data-book="${book}"]` },
      { wait: '.text p' }, { click: '.side-toggle' }, { click: '.chapters li:nth-child(2) button' },
      { wait: '.chapters li:nth-child(2) button.current' }, { fetch: '/__wait?ms=3000' }, reach] },
    // L1 (the ruling, "The pages"): another device's input moves the open book's place; the page goes there by itself,
    // with no offer, and writes nothing (LookServer prints no change after the other device's)
    // (to the book's start first, so a page that opened at that place has a move to follow)
    'real-book-follow': { path: `/w/reader/book:${book}`, steps: [{ wait: '.text p' }, reach, { fetch: '/__wait?ms=800' },
      { fetch: `/__other?book=${book}&offset=0` }, { wait: '.chapters li:first-child button.current' }, { fetch: '/__wait?ms=800' },
      { fetch: `/__other?book=${book}&offset=4000` }, { wait: '.chapters li:not(:first-child) button.current' }, { fetch: '/__wait?ms=3000' }] },
    // L1 G1, standing since R2 A7 — each needs the listener started as its line says (LookServer's header):
    // the policy probe (an inline script the policy must refuse: the count reads the thing it names); LOOK_OWN=1: this
    // PC's own browser approved at once (A1); LOOK_NAME=made-up-pc: a page under the machine's name waits, and is
    // denied; under look-loop.sh: the listener ended and started again under an open book (a deploy)
    'real-policy-probe': { path: '/', cspProbe: true, steps: [{ wait: '.cards .card' }, reach, { fetch: '/__wait?ms=1000' }] },
    'real-own': { path: '/w/devices', anon: true, own: true, steps: [{ wait: '.devices ul.rows li .row-name' }, reach, { fetch: '/__wait?ms=1000' }] },
    'real-as-name': { path: '/', anon: true, asName: 'made-up-pc', steps: [{ wait: '.pair .pcode' }, { fetch: '/__wait?ms=4000' }, { wait: '.pair .pcode' }] },
    'real-denied': { path: '/', anon: true, asName: 'made-up-pc', steps: [{ wait: '.pair .pcode' }, { fetch: '/__ctl?deny-browser' }, { wait: '.page.pair button' }] },
    'real-restart': { path: `/w/reader/book:${book}`, steps: [{ wait: '.text p' }, reach, { fetch: '/__wait?ms=800' },
      { fetch: `/__other?book=${book}&offset=0` }, { wait: '.chapters li:first-child button.current' }, { fetch: '/__wait?ms=800' },
      { fetch: '/__restart' }, { fetch: '/__wait?ms=1000' }, { fetch: `/__other?book=${book}&offset=4000` },
      { wait: '.chapters li:not(:first-child) button.current' }, reach, { fetch: '/__wait?ms=3000' }] },
    // the other device writes only after the page has had time to reconnect: a live change on the new socket
    'real-restart-live': { path: `/w/reader/book:${book}`, steps: [{ wait: '.text p' }, reach, { fetch: '/__wait?ms=800' },
      { fetch: `/__other?book=${book}&offset=0` }, { wait: '.chapters li:first-child button.current' }, { fetch: '/__wait?ms=800' },
      { fetch: '/__restart' }, { fetch: '/__wait?ms=6000' }, reach, { fetch: '/__note?reconnected-before-the-write' },
      { fetch: `/__other?book=${book}&offset=4000` }, { wait: '.chapters li:not(:first-child) button.current' }, { fetch: '/__wait?ms=2000' }] },
    'real-devices': { path: '/w/devices', steps: [{ wait: '.devices li.row-item .pcode' }, reach] },
    // L1 R2 A4 (LOOK_BAD_TABLE=1): the device table set aside at the start, said on the Devices page
    'real-devices-set-aside': { path: '/w/devices', steps: [{ wait: '.devices li.row-item .pcode' }, reach] },
    // L1 R2 B1: watched in the record (LookServer's change lines carry each place's writer and input time), not the
    // page: a tap that scrolls nothing, then a resize and the reflow's scroll, writes nothing; a wheel and its scroll
    // write the place at the wheel's time; a sidebar chapter click writes at the click's time (each after a `mark`)
    'real-book-acts': { path: `/w/reader/book:${book}`, steps: [{ wait: '.text p' }, reach, { fetch: '/__wait?ms=800' },
      { fetch: '/__ctl?mark' }, { fire: ['.reading', 'pointerdown'] }, { fire: ['window', 'resize'] }, { scroll: ['.reading', 400] },
      { fetch: '/__wait?ms=3000' }, { fetch: '/__ctl?mark' }, { fire: ['.reading', 'wheel'] }, { scroll: ['.reading', 400] },
      { fetch: '/__wait?ms=3000' }, { fetch: '/__ctl?mark' }, { fetch: '/__wait?ms=300' }, { click: '.side-toggle' },
      { click: '.chapters li:nth-child(3) button' }, { wait: '.chapters li:nth-child(3) button.current' }, { fetch: '/__wait?ms=3000' }, reach] },
    // L1: the two pages no scene showed — Diagnosis, and Settings with a look picked (it stays on this browser)
    'real-diag': { path: '/w/diag', steps: [{ wait: '.diag dl.facts' }, reach] },
    'real-settings': { path: '/w/settings', steps: [{ wait: '.settings .looks' }, reach, { click: '.looks:last-of-type .choice:last-child input' }] },
    // S1-5 W: every setting as a form; Quiet hours changed through the page's own select, then read back in the record,
    // the PC's fold and the scratch shell (LookServer's word `settings` prints all three)
    'real-settings-quiet': { path: '/w/settings', steps: [{ wait: '.set-cat select[data-key="set:settings:quietHours"]' }, reach,
      { fetch: '/__ctl?settings' }, { pick: ['select[data-key="set:settings:quietHours"]', '22-07'] }, { fetch: '/__wait?ms=1500' },
      { wait: 'select[data-key="set:settings:quietHours"] option[value="22-07"][selected]' }, { fetch: '/__ctl?settings' }] },
    'real-settings-search': { path: '/w/settings', steps: [{ wait: '.set-cat' }, reach, { type: '.set-search', text: 'notify' }, { wait: '.set-cat' }] },
    // the Animations tiers (a tier's effects as checkboxes, wrapping), an app's category (its own rows, then its style
    // rows with Scroll effect), and the same at control (LOOK_SECRET_CONTROL): Tmux's rows need full, dim and saying so
    'real-settings-anim': { path: '/w/settings', steps: [{ wait: '.set-cat' }, reach, { type: '.set-search', text: 'animations' }, { wait: '.set-many' }] },
    'real-settings-tmux': { path: '/w/settings', steps: [{ wait: '.set-cat' }, reach, { type: '.set-search', text: 'tmux' }, { wait: '.set-cat' }] },
    'real-settings-control': { path: '/w/settings', control: true, steps: [{ wait: '.set-cat' }, reach, { type: '.set-search', text: 'tmux' }, { wait: '.trow.dim' }] },
    // the approval of the waiting made-up page, from the devices page
    'real-approve': { path: '/w/devices', steps: [{ wait: '.devices li.row-item .pcode' },
      { click: 'li.row-item:has(.pcode) .row-acts button' }, { wait: '.devices ul.rows li:nth-child(2) .row-name' }, reach] },
    // S1-2 Q3: a notice raised on the PC after the page's first contact pops as a toast; Dismiss from the page lands in
    // the record (LookServer prints its state); a dismiss by another made-up device takes the toast away
    'real-notice': { path: '/', steps: [{ wait: '.cards .card' }, reach, { fetch: '/__wait?ms=800' },
      { fetch: '/__ctl?raise' }, { wait: '.toast.notice' }] },
    'real-notice-dismiss': { path: '/', steps: [{ wait: '.cards .card' }, reach, { fetch: '/__wait?ms=800' },
      { fetch: '/__ctl?raise' }, { wait: '.toast.notice' }, { click: '.toast.notice .act' }, { gone: '.toast.notice' },
      { fetch: '/__wait?ms=1000' }, { fetch: '/__ctl?state' }] },
    'real-notice-elsewhere': { path: '/', steps: [{ wait: '.cards .card' }, reach, { fetch: '/__wait?ms=800' },
      { fetch: '/__ctl?raise' }, { wait: '.toast.notice' }, { fetch: '/__ctl?dismiss-other' }, { gone: '.toast.notice' },
      { fetch: '/__ctl?state' }] },
    // S1-2 Q4: Torrents against the real scopes over LookServer's scripted provider: the table with its graphs (a few
    // polls so the lines have points), a search with results, a torrent's page, an action sent and its effect read back
    // in the table (Stop, then the Stopped filter holds the row), a done notice raised by the core, and the page while
    // the tracker takes its time: the listing waits, and Reader's book still arrives (each provider its own executor)
    'real-torrents': { path: '/w/torrents', steps: [{ fetch: '/__ctl?release' }, { wait: '.ttable tbody tr, .tcards li' }, reach,
      { fetch: '/__wait?ms=5000' }, { wait: '.tg-svg' }] },
    'real-torrents-search': { path: '/w/torrents/search:release', steps: [{ fetch: '/__ctl?release' }, { wait: '.ttable.tl tbody tr, .tcards li' }, reach] },
    'real-torrents-page': { path: '/w/torrents/tl:240001', steps: [{ fetch: '/__ctl?release' }, { wait: '.nfo' }, reach] },
    'real-torrents-act': { path: '/w/torrents', steps: [{ fetch: '/__ctl?release' }, { wait: '.ttable tbody tr, .tcards li' }, reach,
      { click: '[data-key="selecting"], [data-key="sel:c0ffee01"]' }, { click: '.tcard .tc-main[data-key="row:c0ffee01"], .tbulk .tb-n' },
      { wait: '.tbulk' }, { click: '.tbulk button:nth-of-type(2)' }, { fetch: '/__wait?ms=2500' }, { fetch: '/__ctl?ops' },
      { click: '[data-key="filter:stopped"]' }, { wait: '[data-key="row:c0ffee01"]' }] },
    'real-torrents-done': { path: '/w/torrents', steps: [{ wait: '.ttable tbody tr, .tcards li' }, reach, { fetch: '/__wait?ms=800' },
      { fetch: '/__ctl?done' }, { wait: '.toast.notice' }] },
    'real-torrents-slow': { path: '/w/torrents', steps: [{ wait: '.ttable tbody tr, .tcards li' }, reach, { fetch: '/__ctl?slow' },
      { click: '[data-key="tab:browse"]' }, { wait: '.tcat.newest' }, { click: '.tcat.newest' }, { fetch: '/__wait?ms=1500' }] },
    'real-torrents-slow-reader': { path: '/w/torrents', steps: [{ wait: '.ttable tbody tr, .tcards li' }, reach, { fetch: '/__ctl?slow' },
      { click: '[data-key="tab:browse"]' }, { wait: '.tcat.newest' }, { click: '.tcat.newest' }, { fetch: '/__wait?ms=800' },
      { click: '#rail a[href="/w/reader"]' }, { wait: `.shelf .book[data-book="${book}"]` }, { click: `.shelf .book[data-book="${book}"]` },
      { wait: '.text p' }, { fetch: '/__ctl?release' }] },
  };
}

/** S1-2 Q5: Files' scenes against the real listener over LookServer's made-up tree (LOOK_FILES_HOME, the tree's home
 *  folder; none without it): a folder of many entries, list and grid, a text, a picture, a PDF's page, an upload that
 *  lands and one cut off, the trash with a restore, Move to Trash's confirm, the job slot with a running job's age, the
 *  glass theme, and a device below full told so with nothing of the tree shown. */
function filesRealScenes(reach) {
  const home = process.env.LOOK_FILES_HOME;
  if (!home) return {};
  const f = (target) => format({ window: 'files', target });
  const listed = { wait: '.flist .frow, .fgrid .frow' };
  const many = f(`path:${home}/many/`);
  return {
    'real-files-locations': { path: '/w/files', steps: [{ wait: '.flocs .floc' }, reach] },
    'real-files-many': { path: many, steps: [listed, reach] },
    'real-files-grid': { path: f(`path:${home}/pictures/`), steps: [listed, { click: '[data-key="mode"]' }, { wait: '.fgrid .frow' }, reach] },
    'real-files-text': { path: f(`path:${home}/notes.txt`), steps: [{ wait: '.ftext' }, reach] },
    'real-files-picture': { path: f(`path:${home}/pictures/harbour.png`), steps: [{ wait: '.fimg' }, reach] },
    'real-files-pdf': { path: f(`path:${home}/plan.pdf`), steps: [{ wait: '.fpage' }, reach] },
    // L1 P40: the same name dropped twice: the second is placed under the PC's numbered name, said on its row and toast
    'real-files-upload': { path: f(`path:${home}/drop/`), steps: [{ wait: '.tend, .flist .frow' }, reach,
      { drop: '.page.files', files: [{ name: 'made-up survey.txt', size: 200000 }] }, { wait: '.fu.done' },
      { drop: '.page.files', files: [{ name: 'made-up survey.txt', size: 200000 }] }, { wait: '.fu.done ~ .fu.done' }, { fetch: '/__ctl?files' }] },
    'real-files-upload-cut': { path: f(`path:${home}/drop/`), put: 'cut', steps: [{ wait: '.tend, .flist .frow' }, reach,
      { drop: '.page.files', files: [{ name: 'made-up big file.bin', size: 4000000 }] }, { wait: '.fu.cut, .fu.failed' }, { fetch: '/__wait?ms=500' }, { fetch: '/__ctl?files' }] },
    'real-files-trash': { path: f('trash'), steps: [{ wait: '.ftrash .frow' }, reach] },
    // L1 R2 B5: a row whose file is not in the trash says so and offers only Delete forever
    'real-files-trash-missing': { path: '/w/files', steps: [{ wait: '.flocs .floc' }, reach, { fetch: '/__ctl?miss' },
      { click: '[data-key="loc:trash"]' }, { wait: '.ftrash .frow' }, { fetch: '/__wait?ms=500' }] },
    // L1 P44: a job's end is the slot's line, not a toast
    'real-files-restore': { path: f('trash'), steps: [{ wait: '.ftrash .frow' }, reach, { click: '.ftrash .frow .frow-acts button:not(.danger)' },
      { wait: '.fjob' }, { fetch: '/__wait?ms=1500' }, { fetch: '/__ctl?files' }] },
    'real-files-totrash': { path: f(`path:${home}/notes.txt`), steps: [{ wait: '.ftext' }, reach, { click: '.fp-acts button:last-child' }, { wait: 'dialog[open]' }] },
    'real-files-totrash-done': { path: f(`path:${home}/many/`), steps: [{ wait: '.flist .frow' }, reach, { click: '.flist .frow:nth-child(30) input' },
      { wait: '.tbulk' }, { click: '.tbulk button' }, { wait: 'dialog[open]' }, { click: 'dialog[open] button.act' }, { wait: '.fjob' },
      { fetch: '/__wait?ms=1500' }, { fetch: '/__ctl?files' }] },
    'real-files-job': { path: many, steps: [listed, reach, { fetch: '/__ctl?stall' }, { wait: '.fjob' }, { fetch: '/__wait?ms=2500' },
      { late: true, fetch: '/__ctl?release' }] },
    'real-files-glass': { path: many, steps: [listed, reach, { theme: 'glass' }] },
    'real-files-below': { path: many, control: true, steps: [{ wait: '.page.files .note' }] },
  };
}

/** S1-3 (B17): Feed's scenes against the real listener over `ScriptedFeed`'s made-up world (its own source ids; no site
 *  is contacted), once LookServer serves Feed (LOOK_FEED=1; none without it). What each must show it can do, read in
 *  the listener's log: an opened row sends its source's read list and a page load none; a flag lands; mark all read
 *  carries the list; refresh is a program op; a comic opens and random opens a `num:` address the PC answers; the
 *  archive's scroll writes its place once per pause. */
function feedRealScenes(reach) {
  if (!process.env.LOOK_FEED) return {};
  const rows = { wait: '.fd-rows .fd-row' };
  const settle = { fetch: '/__wait?ms=1500' };
  return {
    'real-feed-sources': { path: '/w/feed', steps: [{ wait: '.fd-sources .fd-s' }, reach] },
    'real-feed-open': { path: '/w/feed/src:popular', steps: [rows, reach, { click: '.fd-row:nth-child(2) .fd-open' }, { wait: '.fd-text' }, settle] },
    'real-feed-article': { path: '/w/feed/src:slashdot', steps: [rows, reach, { click: '.fd-row:nth-child(1) .fd-open' }, { wait: '.fd-text .blk, .fd-floor' }, settle] },
    'real-feed-flag': { path: '/w/feed/src:slashdot', steps: [rows, reach, { click: '.fd-row:nth-child(1) .fd-open' }, { wait: '.fd-bar button' },
      { click: '.fd-bar button' }, settle] },
    // S1-3 fix B5: a click in the reading column (its pointerdown, then Flag) past the save point, then leaving by
    // opening the next item: no `place` from this page (the change lines), only the flag and the reads
    'real-feed-flag-place': { path: '/w/feed/src:slashdot', steps: [rows, reach, { click: '.fd-row:nth-child(1) .fd-open' }, { wait: '.fd-bar button' },
      { fire: ['.fd-bar button', 'pointerdown'] }, { click: '.fd-bar button' }, { fetch: '/__wait?ms=2600' }, { click: '.fd-row:nth-child(2) .fd-open' }, settle] },
    'real-feed-markall': { path: '/w/feed/src:popular', steps: [rows, reach, { click: '.fd-acts button:nth-child(2)' }, { wait: 'dialog[open]' },
      { click: 'dialog[open] button.act' }, settle] },
    'real-feed-refresh': { path: '/w/feed/src:popular', steps: [rows, reach, { click: '.fd-acts button:nth-child(1)' }, settle] },
    'real-feed-comic': { path: '/w/feed/src:xkcd', steps: [rows, reach, { click: '.fd-row:nth-child(1) .fd-open' }, { wait: '.fd-comic img' },
      { click: '[data-key="bar:random"]' }, { wait: '.fd-comic img' }, settle] },
    'real-feed-binge': { path: '/w/feed/binge:8bt', steps: [{ wait: '.fd-ep, .fd-bl' }, reach, { fetch: '/__wait?ms=1000' },
      { fire: ['.fd-read', 'wheel'] }, { scroll: ['.fd-read', 600] }, { fetch: '/__wait?ms=2600' }] },
    'real-feed-flagged': { path: '/w/feed/flagged', steps: [{ wait: '.fd-lhead' }, reach] },
    // S1-3 stage M: another device's later place in the open article (block 6 of slashdot's first story, written by
    // LookServer's made-up phone) moves the page by itself, and the page writes no place after it (the change lines)
    'real-feed-follow': { path: '/w/feed/src:slashdot', steps: [rows, reach, { click: '.fd-row:nth-child(1) .fd-open' }, { wait: '.fd-text .blk' },
      { fetch: '/__wait?ms=1000' }, { fetch: '/__ctl?feed-place-other' }, { fetch: '/__wait?ms=3000' }] },
    // S1-3 stage M: the listener ended and started again under an open source (a deploy); the page comes back by itself
    // and shows a new item the restarted PC lands after it (LookServer's word feed-new)
    'real-feed-restart': { path: '/w/feed/src:popular', steps: [rows, reach, { fetch: '/__wait?ms=800' }, { fetch: '/__restart' },
      { fetch: '/__wait?ms=6000' }, reach, { fetch: '/__ctl?feed-new' }, { fetch: '/__wait?ms=2500' }] },
  };
}

/** S1-3 (B17): Tmux's scenes against the real listener over LookServer's scripted Tmux (LOOK_TMUX=<host>:<session>, a
 *  session of its made-up world; none without it): the terminal shows the scripted control-mode output; a key typed in
 *  it reaches the fake provider as a `raw` op (the listener's log); the end confirm's act; a device below full is asked
 *  nothing (LOOK_SECRET_CONTROL). */
function tmuxRealScenes(reach) {
  const at = process.env.LOOK_TMUX;
  if (!at) return {};
  const t = (target) => format({ window: 'tmux', target });
  const field = '.xterm-helper-textarea';
  const drawn = { wait: '.xterm-screen, .tm-rows .tm-row' };
  return {
    'real-tmux-sessions': { path: '/w/tmux', steps: [{ wait: '.tm-list .tm-sess' }, reach] },
    'real-tmux-terminal': { path: t(`session:${at}`), steps: [drawn, reach, { fetch: '/__wait?ms=1500' }] },
    'real-tmux-key': { path: t(`session:${at}`), steps: [drawn, reach, { focus: field }, { key: [field, 'x'] }, { key: [field, 'Enter'] }, { fetch: '/__wait?ms=1500' },
      { fetch: '/__ctl?tmux-ops' }, { fetch: '/__ctl?tmux-echo' }, { fetch: '/__wait?ms=1500' }] },
    'real-tmux-end': { path: t(`session:${at}`), steps: [drawn, reach, { click: '.tm-acts button.danger' }, { wait: 'dialog[open]' },
      { click: 'dialog[open] button.act' }, { fetch: '/__wait?ms=1500' }] },
    'real-tmux-below': { path: t(`session:${at}`), control: true, steps: [{ wait: '.page.tmux .note' }] },
    // S1-3 fix stage: A1 a ';' typed reaches the provider and is echoed; A2 a stream of terminal queries (and the focus
    // they turn on) types nothing; A3 Ctrl+C with nothing selected is sent, with a word selected it copies and sends
    // nothing, Ctrl+V sends no ^V; C1 a new session is not called ended; C2 a view the sweep let go asks again
    'real-tmux-semicolon': { path: t(`session:${at}`), steps: [drawn, reach, { focus: field }, { key: [field, ';'] }, { key: [field, 'Enter'] },
      { fetch: '/__wait?ms=1500' }, { fetch: '/__ctl?tmux-ops' }, { fetch: '/__ctl?tmux-echo' }, { fetch: '/__wait?ms=1500' }] },
    'real-tmux-query': { path: t(`session:${at}`), steps: [drawn, reach, { fetch: '/__wait?ms=800' }, { fetch: '/__ctl?tmux-ops' }, { fetch: '/__ctl?tmux-query' },
      { fetch: '/__wait?ms=1500' }, { focus: field }, { fetch: '/__wait?ms=1000' }, { fetch: '/__ctl?tmux-ops' }] },
    'real-tmux-copy': { path: t(`session:${at}`), steps: [drawn, reach, { focus: field }, { key: [field, 'c', { ctrlKey: true }] }, { fetch: '/__wait?ms=800' },
      { fetch: '/__ctl?tmux-ops' }, { select: '.xterm-screen' }, { key: [field, 'c', { ctrlKey: true }] }, { key: [field, 'v', { ctrlKey: true }] },
      { fetch: '/__wait?ms=1000' }, { fetch: '/__ctl?tmux-ops' }, { key: [field, 'k'] }, { fetch: '/__wait?ms=800' }, { fetch: '/__ctl?tmux-ops' }] },
    'real-tmux-new': { path: '/w/tmux', steps: [{ wait: '.tm-list .tm-sess' }, reach, { click: '.tm-host button' }, drawn, { fetch: '/__wait?ms=4000' },
      { focus: field }, { key: [field, 'z'] }, { fetch: '/__wait?ms=1000' }, { fetch: '/__ctl?tmux-ops' }] },
    'real-tmux-sweep': { path: t(`session:${at}`), steps: [drawn, reach, { fetch: '/__wait?ms=800' }, { fetch: '/__ctl?tmux-sweep' }, { fetch: '/__wait?ms=1500' },
      { fetch: '/__ctl?tmux-out' }, { fetch: '/__wait?ms=1500' }] },
    // S1-3 stage M: the listener ended and started again under an open session; the page subscribes again by itself
    // and a line the restarted PC's stream writes after that is drawn (tmux-out says how many streams it wrote)
    'real-tmux-restart': { path: t(`session:${at}`), steps: [drawn, reach, { fetch: '/__wait?ms=800' }, { fetch: '/__restart' },
      { fetch: '/__wait?ms=6000' }, reach, { fetch: '/__ctl?tmux-out' }, { fetch: '/__wait?ms=1500' }] },
  };
}
