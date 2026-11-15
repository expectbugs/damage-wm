// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-2, package JW0 (S1-2-decisions.md C): a stand-in for the PC's web listener, for looking at the pages in a
// headless browser. Test code only: it serves the bundle's files (files.txt) with the PC's content security policy,
// and speaks the `app` lane (CONTRACT.md §9.2) from the fixtures of one scene, on a loopback port the OS picks. No
// dependency: node has no WebSocket server, so the handshake and the framing (RFC 6455) are here, small and tested.
// For the picture it adds two things to the page: a hidden image whose answer is held (Firefox's --screenshot waits
// for the load event) and a module that walks the scene's steps, then releases it.

import { createServer } from 'node:http';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { deflateSync, crc32 } from 'node:zlib';

const WEB = new URL('../../main/resources/web/', import.meta.url);
const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'; // RFC 6455 §1.3
const TYPES = { html: 'text/html; charset=utf-8', mjs: 'text/javascript; charset=utf-8', css: 'text/css; charset=utf-8', svg: 'image/svg+xml' };

/** The `Sec-WebSocket-Accept` for a client's key (RFC 6455 §4.2.2). */
export const acceptKey = (key) => createHash('sha1').update(key + GUID).digest('base64');

/** One unfragmented server frame, unmasked (§5.2); text unless `op` says otherwise. */
export function frame(body, op = 1) {
  const b = Buffer.isBuffer(body) ? body : Buffer.from(body);
  let head;
  if (b.length < 126) head = Buffer.from([0x80 | op, b.length]);
  else if (b.length < 65536) head = Buffer.from([0x80 | op, 126, b.length >> 8, b.length & 255]);
  else {
    head = Buffer.from([0x80 | op, 127, 0, 0, 0, 0, 0, 0, 0, 0]);
    head.writeBigUInt64BE(BigInt(b.length), 2);
  }
  return Buffer.concat([head, b]);
}

/** The whole client frames at the start of `buf` (masked, §5.3), and what is left. A frame without a mask or a
 *  fragment throws: a browser sends neither for these small messages, and the harness says so loudly. */
export function readFrames(buf) {
  const frames = [];
  let i = 0;
  for (;;) {
    if (buf.length - i < 2) break;
    let n = buf[i + 1] & 127;
    let p = i + 2;
    if (n === 126) {
      if (buf.length < p + 2) break;
      n = buf.readUInt16BE(p);
      p += 2;
    } else if (n === 127) {
      if (buf.length < p + 8) break;
      n = Number(buf.readBigUInt64BE(p));
      p += 8;
    }
    if (!(buf[i + 1] & 128)) throw new Error('a client frame without a mask (RFC 6455 §5.1)');
    if (!(buf[i] & 128)) throw new Error('a fragmented client frame: the stand-in reads whole frames only');
    if (buf.length < p + 4 + n) break;
    const mask = buf.subarray(p, p + 4);
    const data = Buffer.alloc(n);
    for (let j = 0; j < n; j++) data[j] = buf[p + 4 + j] ^ mask[j & 3];
    frames.push({ op: buf[i] & 15, data });
    i = p + 4 + n;
  }
  return { frames, rest: buf.subarray(i) };
}

/** A flat RGB PNG of w × h with a darker band across its middle (for layout only). */
export function png(w, h, [r, g, b]) {
  const raw = Buffer.alloc((w * 3 + 1) * h);
  for (let y = 0; y < h; y++) {
    const k = Math.abs(y - h / 2) < h / 8 ? 0.6 : 1;
    for (let x = 0; x < w; x++) raw.set([r * k, g * k, b * k], y * (w * 3 + 1) + 1 + x * 3);
  }
  const chunk = (type, data) => {
    const td = Buffer.concat([Buffer.from(type), data]);
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(td));
    return Buffer.concat([len, td, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr.set([8, 2], 8); // 8 bits, RGB
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}

/** The PC's policy for the bundle (`Bundle.csp`). */
const csp = (host) => `default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; font-src 'self'; connect-src 'self' ws://${host}; base-uri 'none'; form-action 'none'; frame-ancestors 'none'`;

/** The module that walks a scene's steps in the page, then releases the held image. */
function lookModule(steps) {
  return `const steps = ${JSON.stringify(steps)};
const until = (sel) => new Promise((ok) => { const t = setInterval(() => { const e = document.querySelector(sel); if (e) { clearInterval(t); ok(e); } }, 50); });
const frames = () => new Promise((ok) => setTimeout(ok, 0)); // a hidden screenshot frame runs no animation frames
const note = (t) => fetch('/__note?' + encodeURIComponent(t));
addEventListener('error', (e) => note('page error: ' + e.message));
addEventListener('unhandledrejection', (e) => note('page error: ' + (e.reason && e.reason.message)));
async function step(s) {
  if (s.wait) await until(s.wait);
  if (s.click) (await until(s.click)).click();
  if (s.drop) {
    // S1-2 Q5w: files dropped on the page, as a drag from the desktop delivers them
    const dt = new DataTransfer();
    for (const f of s.files) dt.items.add(new File([new Uint8Array(f.size)], f.name));
    (await until(s.drop)).dispatchEvent(new DragEvent('drop', { dataTransfer: dt, bubbles: true, cancelable: true }));
  }
  if (s.fetch) await fetch(s.fetch);
  note('step ' + JSON.stringify(s));
}
// S1-2 Q5w: a step marked late runs after the picture is released: a request in flight before the load event (an
// upload's body) holds Firefox's screenshot until it ends, so an upload in progress is pictured from one started after
for (const s of steps) if (!s.late) await step(s);
await frames();
// a hidden frame runs no lazy loading: every picture of the page is loaded as a visible one would be
for (const i of document.images) if (i.loading === 'lazy') i.loading = 'eager';
await frames();
const seen = [...document.images].filter((i) => i.id !== 'look-hold' && !i.complete);
await Promise.all(seen.map((i) => new Promise((ok) => { i.addEventListener('load', ok); i.addEventListener('error', ok); })));
await frames();
await fetch('/__ready');
for (const s of steps) if (s.late) await step(s);
`;
}

/**
 * Starts the stand-in for `scene` ({hello, steps}; see fixtures.mjs) with the fixtures `fx`. Resolves to
 * {port, log, close}: `log` holds every message the page sent, in order.
 */
export function standin(scene, fx) {
  const files = new Set(readFileSync(new URL('files.txt', WEB), 'utf8').split('\n').map((l) => l.trim()).filter((l) => l && !l.startsWith('#')));
  const log = [];
  const socks = new Set();
  const senders = new Set();
  let ready = false;
  let held = null;
  let away = false;

  function onMessage(m, send) {
    log.push(m);
    switch (m.t) {
      case 'app':
        if (scene.hello === 'pending') return send({ t: 'pending', code: fx.code });
        if (scene.hello) return send({ t: 'end', reason: scene.hello });
        return send(fx.welcome(m.stream, scene));
      case 'sub':
        if (m.scope === 'reader') return send(fx.record());
        if (m.scope === 'notices' && fx.notices) return send(fx.notices());
        if (fx.records && fx.records[m.scope]) return send(fx.records[m.scope]()); // S1-2 Q4w: a window's record scope
        // S1-2 Q4w: a scene may answer a scope its own way (an empty table); fixed scopes, then those made by function
        if (scene.descs && scene.descs[m.scope]) return send({ t: 'snap', scope: m.scope, desc: scene.descs[m.scope] });
        // a list of descriptions is sent as successive snapshots (Torrents' polls)
        if (fx.descs[m.scope]) return [].concat(fx.descs[m.scope]).forEach((desc) => send({ t: 'snap', scope: m.scope, desc }));
        if (fx.scope && fx.scope(m.scope)) return send({ t: 'snap', scope: m.scope, desc: fx.scope(m.scope) });
        return send({ t: 'ans', scope: m.scope, answer: { a: 'refused', code: 'scope', reason: `the stand-in serves no scope '${m.scope}'` } });
      case 'ping':
        return send({ t: 'pong', k: m.k });
      case 'op':
        // S1-2 Q5w: a program op the fixtures answer their own way (an upload's id)
        if (m.op.conn !== undefined && fx.program && fx.program(m.op)) {
          // a scene may leave a program op unanswered (an upload waiting for its place)
          if (scene.hold && scene.hold === m.op.action) return undefined;
          return send({ t: 'ans', conn: m.op.conn, k: m.op.k, answer: fx.program(m.op) });
        }
        return send(m.op.stream ? { t: 'ans', stream: m.op.stream, n: m.op.n, answer: { a: 'seen' } } : { t: 'ans', conn: m.op.conn, k: m.op.k, answer: { a: 'seen' } });
      default:
    }
  }

  const server = createServer((req, res) => {
    const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    if (process.env.LOOK_DEBUG) console.log("http", req.method, path);
    const reply = (code, type, body, extra = {}) => {
      res.writeHead(code, { 'Content-Type': type, 'Cache-Control': 'no-cache', 'X-Content-Type-Options': 'nosniff', ...extra });
      res.end(body);
    };
    if (path === '/__hold') {
      const end = () => reply(200, 'image/png', png(1, 1, [0, 0, 0]));
      return ready ? end() : (held = end);
    }
    if (path === '/__ready') {
      ready = true;
      if (held) held();
      return reply(204, 'text/plain', '');
    }
    if (path === '/__away') {
      // the PC drops out of reach: every socket ends and new ones are refused
      away = true;
      for (const s of socks) s.destroy();
      return reply(204, 'text/plain', '');
    }
    if (path === '/__raise') {
      // S1-2 Q3w: the PC raises `n` notices; each open page gets the record's changes
      const n = Number(new URL(req.url, 'http://x').searchParams.get('n') || 1);
      for (const s of senders) for (const f of fx.raise(n)) s(f);
      return reply(204, 'text/plain', '');
    }
    if (path === '/__note') {
      log.push({ t: 'note', text: decodeURIComponent(req.url.slice(req.url.indexOf('?') + 1)) });
      return reply(204, 'text/plain', '');
    }
    if (path === '/__look.mjs') return reply(200, TYPES.mjs, lookModule(scene.steps));
    if (req.method === 'PUT' && path.startsWith('/blob/upload/')) {
      // S1-2 Q5w: an upload's body. A body still in flight holds Firefox's screenshot (seen: it waits for the page's
      // requests), so a scene pictures either the op waiting for its place (`hold`) or a body cut off part way (`cut`)
      let got = 0;
      req.on('data', (d) => {
        got += d.length;
        if (scene.put === 'cut' && got >= 8e6) req.socket.destroy();
      });
      req.on('error', () => {});
      req.on('end', () => reply(201, 'text/plain', ''));
      return;
    }
    if (path.startsWith('/blob/f.')) {
      // S1-2 Q5w: a file's original, its thumbnail or a PDF page: a generated picture of its kind
      const r = new URL(req.url, 'http://x').searchParams.get('r') || '';
      const hue = [...path].reduce((n, c) => (n * 31 + c.charCodeAt(0)) % 997, 7);
      const rgb = [60 + (hue % 140), 60 + ((hue * 7) % 140), 60 + ((hue * 13) % 140)];
      const [w, h, c] = r.startsWith('pdf:') ? [595, 842, [245, 243, 236]] : r.startsWith('thumb:') ? [320, 213, rgb] : [1600, 1000, rgb];
      return reply(200, 'image/png', png(w, h, c), { 'Content-Security-Policy': 'sandbox' });
    }
    if (path.startsWith('/blob/')) {
      const b = fx.blobs[path.slice(6)];
      return b ? reply(200, 'image/png', png(...b), { 'Content-Security-Policy': 'sandbox' }) : reply(404, 'text/plain', 'no such blob');
    }
    const name = path === '/' || path.startsWith('/w/') ? 'index.html' : path.slice(1);
    if (!files.has(name)) return reply(404, 'text/plain', `not in files.txt: ${name}`);
    let body = readFileSync(new URL(name, WEB));
    if (name === 'index.html') {
      body = String(body).replace('</body>', '<img id="look-hold" hidden alt="" src="/__hold">\n<script type="module" src="/__look.mjs"></script>\n</body>');
    }
    reply(200, TYPES[name.split('.').pop()] || 'application/octet-stream', body, { 'Content-Security-Policy': csp(req.headers.host) });
  });

  server.on('upgrade', (req, sock) => {
    if (away || req.url !== '/ws') {
      sock.end('HTTP/1.1 503 Service Unavailable\r\n\r\n');
      return;
    }
    sock.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${acceptKey(req.headers['sec-websocket-key'])}\r\n\r\n`);
    socks.add(sock);
    const send = (o) => sock.write(frame(JSON.stringify(o)));
    senders.add(send);
    sock.on('close', () => senders.delete(send));
    let buf = Buffer.alloc(0);
    sock.on('data', (d) => {
      const r = readFrames(Buffer.concat([buf, d]));
      buf = r.rest;
      for (const f of r.frames) {
        if (f.op === 1) onMessage(JSON.parse(String(f.data)), send);
        else if (f.op === 8) sock.end(frame(Buffer.alloc(0), 8));
        else if (f.op === 9) sock.write(frame(f.data, 10));
      }
    });
    sock.on('close', () => socks.delete(sock));
    sock.on('error', () => socks.delete(sock)); // the page ending its socket is not a failure of the stand-in
  });

  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve({
    port: server.address().port,
    log,
    close() {
      for (const s of socks) s.destroy();
      server.closeAllConnections();
      return new Promise((ok) => server.close(ok));
    },
  })));
}
