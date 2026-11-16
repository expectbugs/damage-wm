// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-2, package Q2 (S1-2 R0 §1, the real-server look): test code only. A front on a loopback port the OS picks,
// between headless Firefox and the REAL web listener that `LookServer.kt` starts in process (loopback only, a temp
// directory, made-up data). Every request and the WebSocket go through to that listener unchanged but for three
// things a front on another port needs: `Host` and `Origin` name the listener's own port (its access check wants
// them), the listener's content security policy names the front's port for the socket, and the one made-up device's
// cookie is sent unless the scene is a page that has none (`anon`). Like the stand-in it adds a held image and the
// module that walks the scene's steps (Firefox's --screenshot waits for the load event), and `/__wait?ms=` holds a
// step for the PC's own pacing (a place is saved 2 s after the input).

import { createServer, request } from 'node:http';
import { connect } from 'node:net';
import { readFileSync } from 'node:fs';
import { lookModule } from './standin.mjs';
import { format } from '../../main/resources/web/js/address.mjs';

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
    const others = String(h || '').split(';').map((c) => c.trim()).filter((c) => c && !c.startsWith('damage='));
    return [...others, ...(scene.anon ? [] : [`damage=${sec}`])].join('; ');
  };
  const headsUp = (headers) => {
    const out = { ...headers, host: up.host };
    if (out.origin) out.origin = `http://${up.host}`;
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
    if (url.pathname === '/__wait') return setTimeout(() => own(204, 'text/plain', ''), Number(url.searchParams.get('ms') || 0));
    if (url.pathname === '/__look.mjs') return own(200, TYPES.mjs, lookModule(scene.steps));
    // S1-2 Q3: a word for LookServer's control listener (LOOK_CTL, loopback only): raise, dismiss-other, state
    if (url.pathname === '/__ctl') {
      const ctl = process.env.LOOK_CTL;
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
      if (h['content-security-policy']) h['content-security-policy'] = h['content-security-policy'].replaceAll(`ws://${up.host}`, `ws://${req.headers.host}`);
      const html = String(h['content-type'] || '').startsWith('text/html');
      if (!html) { res.writeHead(r.statusCode, h); r.pipe(res); return; }
      const parts = [];
      r.on('data', (d) => parts.push(d));
      r.on('end', () => {
        const body = Buffer.from(String(Buffer.concat(parts)).replace('</body>',
          '<img id="look-hold" hidden alt="" src="/__hold">\n<script type="module" src="/__look.mjs"></script>\n</body>'));
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

/** A 1×1 transparent GIF. */
const GIF = Buffer.from('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7', 'base64');

/** The scenes against the real listener (S1-2 R0 §1): the pages S1-1 built, and what they must be able to do. `book`
 *  is the made-up EPUB's id that LookServer prints. */
export function realScenes(book) {
  const reach = { wait: '#reach[hidden]' };
  return {
    ...filesRealScenes(reach),
    'real-waiting': { path: '/', anon: true, steps: [{ wait: '.pair .pcode' }] },
    'real-home': { path: '/', steps: [{ wait: '.cards .card' }, reach] },
    'real-shelf': { path: '/w/reader', steps: [{ wait: '.shelf .book' }, reach] },
    // the input that opens the book (open) and the chapter picked (its place, saved 2 s after the input)
    'real-book': { path: '/w/reader', steps: [{ wait: `.shelf .book[data-book="${book}"]` }, { click: `.shelf .book[data-book="${book}"]` },
      { wait: '.text p' }, { click: '.side-toggle' }, { click: '.chapters li:nth-child(2) button' },
      { wait: '.chapters li:nth-child(2) button.current' }, { fetch: '/__wait?ms=3000' }, reach] },
    'real-devices': { path: '/w/devices', steps: [{ wait: '.devices li.row-item .pcode' }, reach] },
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
    'real-files-upload': { path: f(`path:${home}/drop/`), steps: [{ wait: '.tend, .flist .frow' }, reach,
      { drop: '.page.files', files: [{ name: 'made-up survey.txt', size: 200000 }] }, { wait: '.fu.done, .fu.asking' }, { fetch: '/__ctl?files' }] },
    'real-files-upload-cut': { path: f(`path:${home}/drop/`), put: 'cut', steps: [{ wait: '.tend, .flist .frow' }, reach,
      { drop: '.page.files', files: [{ name: 'made-up big file.bin', size: 4000000 }] }, { wait: '.fu.cut, .fu.failed' }, { fetch: '/__wait?ms=500' }, { fetch: '/__ctl?files' }] },
    'real-files-trash': { path: f('trash'), steps: [{ wait: '.ftrash .frow' }, reach] },
    'real-files-restore': { path: f('trash'), steps: [{ wait: '.ftrash .frow' }, reach, { click: '.ftrash .frow .frow-acts button:not(.danger)' },
      { wait: '.toast' }, { fetch: '/__ctl?files' }] },
    'real-files-totrash': { path: f(`path:${home}/notes.txt`), steps: [{ wait: '.ftext' }, reach, { click: '.fp-acts button:last-child' }, { wait: 'dialog[open]' }] },
    'real-files-totrash-done': { path: f(`path:${home}/many/`), steps: [{ wait: '.flist .frow' }, reach, { click: '.flist .frow:nth-child(30) input' },
      { wait: '.tbulk' }, { click: '.tbulk button' }, { wait: 'dialog[open]' }, { click: 'dialog[open] button.act' }, { wait: '.toast' }, { fetch: '/__ctl?files' }] },
    'real-files-job': { path: many, steps: [listed, reach, { fetch: '/__ctl?stall' }, { wait: '.fjob' }, { fetch: '/__wait?ms=2500' },
      { late: true, fetch: '/__ctl?release' }] },
    'real-files-glass': { path: many, steps: [listed, reach, { theme: 'glass' }] },
    'real-files-below': { path: many, control: true, steps: [{ wait: '.page.files .note' }] },
  };
}
